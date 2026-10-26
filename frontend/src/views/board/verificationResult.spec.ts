import { describe, expect, it } from 'vitest'
import { countResponseTriggerWarnings, getVerificationOutcome, groupVerificationEvidence, presentSpecificationResult } from './verificationResult'
import { i18n } from '@/assets/i18n'
import type { SpecResult } from '@/types/verify'

describe('verification result semantics', () => {
  it('groups evidence with its requirement, prioritizes problems and keeps original replay indexes', () => {
    const results = [
      { specId: 'safe', outcome: 'SATISFIED', antecedentReachability: 'NOT_APPLICABLE' },
      { specId: 'bad', outcome: 'VIOLATED', antecedentReachability: 'REACHABLE' },
      { specId: 'unknown', outcome: 'INCONCLUSIVE', antecedentReachability: 'INCONCLUSIVE' },
      { specId: 'unreachable', outcome: 'SATISFIED', antecedentReachability: 'UNREACHABLE' }
    ] as SpecResult[]
    const traces = [{ violatedSpecId: 'orphan' }, { violatedSpecId: 'bad' }, { violatedSpecId: 'bad' }]
    const groups = groupVerificationEvidence(results, traces)
    expect(groups.map(group => group.result?.specId)).toEqual(['bad', undefined, 'unknown', 'unreachable', 'safe'])
    expect(groups[0]!.traces.map(item => item.index)).toEqual([1, 2])
    expect(groups[1]!.traces).toEqual([{ trace: traces[0], index: 0 }])
    expect(groups.flatMap(group => group.traces).map(item => item.index).sort()).toEqual([0, 1, 2])
    expect(results[0]!.specId).toBe('safe')
  })

  it('keeps trace-only history and requirements with no replayable evidence visible', () => {
    expect(groupVerificationEvidence([], [{ violatedSpecId: '' }])[0]!.traces).toHaveLength(1)
    const result = { specId: 'bad', outcome: 'VIOLATED', antecedentReachability: 'NOT_APPLICABLE' } as SpecResult
    expect(groupVerificationEvidence([result], [])).toEqual([{ result, traces: [] }])
    expect(groupVerificationEvidence([], [])).toEqual([])
  })

  it('counts only unreachable or unconfirmed response triggers, independently of formula truth', () => {
    const results = ['REACHABLE', 'UNREACHABLE', 'INCONCLUSIVE', 'NOT_APPLICABLE'].map((antecedentReachability, index) => ({
      specId: `spec-${index}`, templateId: '4', specificationLabel: 'Response', formulaKind: 'CTL',
      formulaPreview: 'AG(x -> AX y)', expression: 'AG(x -> AX y)',
      outcome: 'SATISFIED', antecedentReachability
    })) as SpecResult[]
    expect(countResponseTriggerWarnings(results)).toBe(2)
    results[1]!.outcome = 'VIOLATED'
    expect(countResponseTriggerWarnings(results)).toBe(2)
    expect(countResponseTriggerWarnings([])).toBe(0)
  })

  it.each([
    ['REACHABLE', false, 'possible'],
    ['UNREACHABLE', true, 'unreachable'],
    ['INCONCLUSIVE', true, 'unconfirmed']
  ] as const)('exposes %s trigger evidence without changing the formula verdict', (antecedentReachability, warning, detail) => {
    const result: SpecResult = {
      specId: 'response', templateId: '4', specificationLabel: 'Response', formulaKind: 'CTL',
      specificationSnapshot: { id: 'response', name: 'Response', templateId: '4', aConditions: [],
        ifConditions: [{ id: 'trigger', side: 'if', deviceId: 'light', deviceLabel: 'Light', targetType: 'state', key: 'state', relation: '=', value: 'off' }],
        thenConditions: [{ id: 'response', side: 'then', deviceId: 'light', deviceLabel: 'Light', targetType: 'state', key: 'state', relation: '=', value: 'on' }],
        devices: { light: { label: 'Light', modelTokenSource: 'BUNDLED', modeCount: 1 } } },
      formulaPreview: 'AG(x -> AX y)', expression: 'AG(x -> AX y)', outcome: 'SATISFIED', antecedentReachability
    }
    const text: Record<string, string> = {
      'household.triggerReachable': 'possible', 'household.triggerUnreachableDetail': 'unreachable',
      'household.triggerUnknownDetail': 'unconfirmed'
    }
    const view = presentSpecificationResult(result, key => text[key] || key)
    expect(view).toMatchObject({ triggerWarning: warning, triggerDetail: detail })
    expect(result.outcome).toBe('SATISFIED')
  })
  it.each(['en', 'zh-CN'] as const)('reads the frozen conditions and token provenance in %s', locale => {
    const savedLocale = i18n.global.locale.value
    i18n.global.locale.value = locale
    try {
      const result: SpecResult = {
        specId: 'saved_spec', templateId: '1', specificationLabel: 'Always', formulaKind: 'CTL',
        antecedentReachability: 'NOT_APPLICABLE',
        formulaPreview: 'CTL AG("Saved light".state = "on")', expression: 'CTLSPEC AG(x)', outcome: 'SATISFIED',
        specificationSnapshot: { name: 'Always', id: 'saved_spec', templateId: '1', aConditions: [{ id: 'reading', side: 'a',
          deviceId: 'light', deviceLabel: 'Outdated label', targetType: 'variable', variableSource: 'reported',
          key: 'temperature', relation: '>', value: '25' }], ifConditions: [], thenConditions: [],
          devices: { light: { label: 'Saved light', modelTokenSource: 'BUNDLED', modeCount: 1 } } }
      }
      const view = presentSpecificationResult(result, i18n.global.t)
      expect(view.description).toContain(locale === 'en' ? 'the Temperature Saved light reports' : 'Saved light 上报的 温度')
      expect(view.description).not.toContain('Outdated label')
      expect(view.variableSourceLabels.map(item => item.key)).toEqual(['app.specVariableSourceReportedShort'])
      expect(view.formula).toBe('AG("Saved light".state = "on")')
      result.specificationSnapshot!.devices.light.modelTokenSource = 'CUSTOM'
      expect(presentSpecificationResult(result, i18n.global.t).description).toContain('temperature')
    } finally { i18n.global.locale.value = savedLocale }
  })

  it.each(['SATISFIED', 'VIOLATED', 'INCONCLUSIVE'] as const)('preserves the authoritative %s outcome', outcome => {
    const result = {
      outcome, traces: [{ id: 1 }], checkLogs: ['[verification-inconclusive]'],
      specResults: [{ outcome: 'VIOLATED' }]
    }
    expect(getVerificationOutcome(result)).toBe(outcome)
  })

  it('keeps a missing result inconclusive while the surface is loading', () => {
    expect(getVerificationOutcome(null)).toBe('INCONCLUSIVE')
    expect(getVerificationOutcome(undefined)).toBe('INCONCLUSIVE')
  })
})
