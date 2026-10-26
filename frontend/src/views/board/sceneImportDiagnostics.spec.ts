import { describe, expect, it } from 'vitest'

import {
  formatSceneValidationCoordinate,
  getStructuredValidationErrors,
  readBoardReplacementStalePreview
} from './sceneImportDiagnostics'

const rejection = (data: unknown) => ({ response: { data: { data } } })
const t = (key: string, named?: Record<string, unknown>) =>
  named ? `${key}(${JSON.stringify(named)})` : key

describe('getStructuredValidationErrors', () => {
  it('keeps the field/reason pairs a rejection reported', () => {
    expect(getStructuredValidationErrors(rejection({
      errors: { 'rules[0].name': 'Unknown field', 'devices[1].label': 'Required' }
    }))).toEqual([
      ['rules[0].name', 'Unknown field'],
      ['devices[1].label', 'Required']
    ])
  })

  it('reports no diagnostics rather than a half-trusted list', () => {
    // A shape we cannot read must not become an empty "no problems" claim upstream either — the
    // caller distinguishes "no structured errors" from "import succeeded".
    for (const data of [undefined, null, {}, { errors: null }, { errors: [] }, { errors: 'nope' }]) {
      expect(getStructuredValidationErrors(rejection(data)), JSON.stringify(data)).toEqual([])
    }
    expect(getStructuredValidationErrors(new Error('offline'))).toEqual([])
  })

  it('drops entries whose reason is not usable text', () => {
    expect(getStructuredValidationErrors(rejection({
      errors: { 'rules[0]': '   ', 'specs[0]': 42, 'devices[0]': 'Required' }
    }))).toEqual([['devices[0]', 'Required']])
  })
})

describe('formatSceneValidationCoordinate', () => {
  it('names the collection and a 1-based position the user can find', () => {
    expect(formatSceneValidationCoordinate('rules[0].name', t))
      .toBe('app.sceneImportValidationRule({"index":1})')
    expect(formatSceneValidationCoordinate('specs[2]', t))
      .toBe('app.sceneImportValidationSpecification({"index":3})')
    expect(formatSceneValidationCoordinate('environmentVariables[4]', t))
      .toBe('app.sceneImportValidationEnvironment({"index":5})')
  })

  it('treats the export and API spellings of devices as the same collection', () => {
    // The export format says `devices`; the API says `nodes`.
    expect(formatSceneValidationCoordinate('devices[0]', t))
      .toBe(formatSceneValidationCoordinate('nodes[0]', t))
  })

  it('falls back to a scene-level problem instead of guessing', () => {
    for (const field of ['', 'impactToken', 'unknown[0]', 'rules', 'rules[x]']) {
      expect(formatSceneValidationCoordinate(field, t), field)
        .toBe('app.sceneImportValidationScene')
    }
  })
})

describe('readBoardReplacementStalePreview', () => {
  const preview = {
    impactToken: 'token-1',
    deviceCount: 2,
    environmentVariableCount: 1,
    ruleCount: 3,
    specificationCount: 0,
    editHistoryEntryCount: 0,
    templates: [{ name: 'Light', defaultTemplate: true, deviceCount: 2 }]
  }

  it('accepts a complete stale-replacement preview', () => {
    expect(readBoardReplacementStalePreview(rejection({
      reasonCode: 'BOARD_REPLACEMENT_STALE',
      currentPreview: preview
    }))).toEqual(preview)
  })

  it('ignores a rejection that is not a stale replacement', () => {
    expect(readBoardReplacementStalePreview(rejection({
      reasonCode: 'SOMETHING_ELSE',
      currentPreview: preview
    }))).toBeNull()
    expect(readBoardReplacementStalePreview(rejection({ currentPreview: preview }))).toBeNull()
  })

  it('refuses a preview that cannot be shown honestly', () => {
    // The counts tell the user what they are about to overwrite, so a partial preview is worse
    // than none: it would understate the loss.
    const bad: Array<Record<string, unknown>> = [
      { ...preview, impactToken: '' },
      { ...preview, impactToken: '   ' },
      { ...preview, impactToken: 7 },
      { ...preview, deviceCount: -1 },
      { ...preview, ruleCount: 1.5 },
      { ...preview, specificationCount: undefined },
      { ...preview, environmentVariableCount: 'many' },
      { ...preview, editHistoryEntryCount: undefined },
      { ...preview, templates: undefined },
      { ...preview, templates: [{ name: 'Light', defaultTemplate: true, deviceCount: 0 }] }
    ]
    for (const currentPreview of bad) {
      expect(readBoardReplacementStalePreview(rejection({
        reasonCode: 'BOARD_REPLACEMENT_STALE',
        currentPreview
      })), JSON.stringify(currentPreview)).toBeNull()
    }
  })

  it('survives a transport failure with no payload at all', () => {
    expect(readBoardReplacementStalePreview(new Error('offline'))).toBeNull()
    expect(readBoardReplacementStalePreview(undefined)).toBeNull()
  })
})
