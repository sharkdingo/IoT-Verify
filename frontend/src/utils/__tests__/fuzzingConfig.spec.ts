// @vitest-environment node
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import type { Specification } from '@/types/spec'
import {
  FUZZ_ITERATIONS_MAX,
  FUZZ_ITERATIONS_MIN,
  FUZZ_PATH_LENGTH_MAX,
  FUZZ_PATH_LENGTH_MIN,
  FUZZ_POPULATION_MAX,
  FUZZ_POPULATION_MIN,
  getKnownFuzzingSpecificationIssue,
  getFuzzingConfigurationIssue,
  hasValidFuzzingBudget,
  isFuzzingPreviewCurrent,
  isKnownFuzzingSpecificationSupported,
  isKnownFuzzingTemplateSupported
} from '../fuzzingConfig'

const config = {
  maxIterations: 500,
  pathLength: 20,
  populationSize: 10,
  targetSpecIds: [] as string[]
}

describe('fuzzing configuration limits', () => {
  it('recognizes only the finite-trace templates supported by the current explorer', () => {
    expect(isKnownFuzzingTemplateSupported('1')).toBe(true)
    expect(isKnownFuzzingTemplateSupported(3)).toBe(true)
    expect(isKnownFuzzingTemplateSupported('4')).toBe(true)
    expect(isKnownFuzzingTemplateSupported('2')).toBe(false)
    expect(isKnownFuzzingTemplateSupported('7')).toBe(false)
  })

  it('keeps typed trust and privacy conditions outside the finite explorer', () => {
    const specification: Specification = { name: 'Safety requirement',
      id: 'security-spec',
      templateId: '1',
      templateLabel: 'Security',
      aConditions: [{
        id: 'condition-1',
        side: 'a',
        deviceId: 'device-1',
        deviceLabel: 'Device',
        targetType: 'trust',
        key: 'PowerMode',
        propertyScope: 'state',
        relation: '=',
        value: 'trusted'
      }],
      ifConditions: [],
      thenConditions: []
    }
    expect(getKnownFuzzingSpecificationIssue(specification))
      .toBe('TRUST_PRIVACY_UNSUPPORTED')
    expect(isKnownFuzzingSpecificationSupported(specification)).toBe(false)
    expect(isKnownFuzzingSpecificationSupported({
      ...specification,
      aConditions: [{ ...specification.aConditions[0], targetType: 'state' }]
    })).toBe(true)
  })

  it('rejects invalid integer fields instead of relying on silent normalization', () => {
    expect(getFuzzingConfigurationIssue({ ...config, maxIterations: 0 }, 1))
      .toMatchObject({ code: 'INVALID_INTEGER_FIELD', field: 'maxIterations' })
    expect(getFuzzingConfigurationIssue({ ...config, pathLength: 1.5 }, 1))
      .toMatchObject({ code: 'INVALID_INTEGER_FIELD', field: 'pathLength' })
    expect(getFuzzingConfigurationIssue({ ...config, seed: -1 }, 1))
      .toMatchObject({ code: 'INVALID_INTEGER_FIELD', field: 'seed' })
  })

  it('requires an explicit bounded selection when the board has over 100 specifications', () => {
    expect(getFuzzingConfigurationIssue(config, 101)).toMatchObject({
      code: 'TARGET_SELECTION_REQUIRED',
      availableSpecCount: 101,
      limit: 100
    })
    expect(getFuzzingConfigurationIssue({ ...config, targetSpecIds: ['spec-1'] }, 101)).toBeNull()
  })

  it('uses only the backend assessment for the frozen-model workload ceiling', () => {
    expect(getFuzzingConfigurationIssue(config, 2)).toBeNull()
    expect(getFuzzingConfigurationIssue({
      maxIterations: 5_000,
      pathLength: 50,
      populationSize: 50,
      targetSpecIds: ['spec-1', 'spec-2']
    }, 2, {
      workload: 12_740_000,
      limit: 12_500_000
    })).toEqual({
      code: 'WORKLOAD_EXCEEDED',
      workload: 12_740_000,
      limit: 12_500_000
    })
  })
})

describe('isFuzzingPreviewCurrent', () => {
  const budget = { maxIterations: 100, pathLength: 8, populationSize: 12 }
  const fresh = () => ({
    preview: { ...budget },
    loading: false,
    error: null,
    previewSemanticKey: 'board-v1'
  })

  it('accepts a preview computed for exactly this board and budget', () => {
    expect(isFuzzingPreviewCurrent(fresh(), budget, 'board-v1')).toBe(true)
  })

  it('rejects a preview computed for a different board', () => {
    // The user can edit the board while a preview is in flight; the late response describes a
    // model that no longer exists.
    expect(isFuzzingPreviewCurrent(fresh(), budget, 'board-v2')).toBe(false)
    expect(isFuzzingPreviewCurrent(
      { ...fresh(), previewSemanticKey: null }, budget, 'board-v1')).toBe(false)
  })

  it('rejects a preview whose budget no longer matches the form', () => {
    // Showing an estimate next to inputs it was not computed for would misstate the cost.
    for (const changed of [
      { ...budget, maxIterations: 101 },
      { ...budget, pathLength: 9 },
      { ...budget, populationSize: 13 }
    ]) {
      expect(isFuzzingPreviewCurrent(fresh(), changed, 'board-v1'), JSON.stringify(changed))
        .toBe(false)
    }
  })

  it('is never ready while loading, failed, or absent', () => {
    expect(isFuzzingPreviewCurrent({ ...fresh(), loading: true }, budget, 'board-v1')).toBe(false)
    expect(isFuzzingPreviewCurrent(
      { ...fresh(), error: new Error('offline') }, budget, 'board-v1')).toBe(false)
    expect(isFuzzingPreviewCurrent({ ...fresh(), preview: null }, budget, 'board-v1')).toBe(false)
  })
})

describe('hasValidFuzzingBudget', () => {
  it('accepts values at both ends of each documented bound', () => {
    expect(hasValidFuzzingBudget({
      maxIterations: FUZZ_ITERATIONS_MIN,
      pathLength: FUZZ_PATH_LENGTH_MIN,
      populationSize: FUZZ_POPULATION_MIN
    })).toBe(true)
    expect(hasValidFuzzingBudget({
      maxIterations: FUZZ_ITERATIONS_MAX,
      pathLength: FUZZ_PATH_LENGTH_MAX,
      populationSize: FUZZ_POPULATION_MAX
    })).toBe(true)
  })

  it('rejects out-of-range and non-integer budgets', () => {
    const valid = { maxIterations: 10, pathLength: 5, populationSize: 5 }
    for (const bad of [
      { ...valid, maxIterations: FUZZ_ITERATIONS_MAX + 1 },
      { ...valid, maxIterations: 0 },
      { ...valid, pathLength: FUZZ_PATH_LENGTH_MAX + 1 },
      { ...valid, populationSize: 0 },
      { ...valid, pathLength: 1.5 },
      { ...valid, maxIterations: Number.NaN }
    ]) {
      expect(hasValidFuzzingBudget(bad), JSON.stringify(bad)).toBe(false)
    }
  })
})

describe('fuzzing bounds mirror the backend', () => {
  /*
   * These are rendered to the user as the allowed range ("1-5000") in `FuzzingPanel.vue`, so a divergence does
   * not merely mis-validate — it *tells the user the wrong rule*. They are declared three times: here, on
   * `FuzzRequestDto`, and on `FuzzWorkloadPreviewRequestDto`, and both DTOs are separately `@Valid` on live
   * endpoints. Nothing compared them.
   *
   * Same pattern as `credentialLimitsMirror.spec.ts`: read the Java source, since there is no way to import it.
   */
  const javaBounds = (file: string) => readFileSync(
    join(__dirname, `../../../../backend/src/main/java/cn/edu/nju/Iot_Verify/dto/fuzz/${file}`), 'utf8')

  const boundsOf = (source: string, field: string) => {
    const at = source.indexOf(`private Integer ${field}`)
    expect(at, `${field} should be declared`).toBeGreaterThan(-1)
    const block = source.slice(Math.max(0, at - 400), at)
    const min = /@Min\(value = ([\d_]+)/g
    const max = /@Max\(value = ([\d_]+)/g
    const mins = [...block.matchAll(min)].map(m => Number(m[1].replace(/_/g, '')))
    const maxes = [...block.matchAll(max)].map(m => Number(m[1].replace(/_/g, '')))
    return { min: mins[mins.length - 1], max: maxes[maxes.length - 1] }
  }

  it.each(['FuzzRequestDto.java', 'FuzzWorkloadPreviewRequestDto.java'])(
    'agrees with %s on every bound the panel shows', (file) => {
      const source = javaBounds(file)
      const pairs: Array<[string, number, number]> = [
        ['maxIterations', FUZZ_ITERATIONS_MIN, FUZZ_ITERATIONS_MAX],
        ['pathLength', FUZZ_PATH_LENGTH_MIN, FUZZ_PATH_LENGTH_MAX],
        ['populationSize', FUZZ_POPULATION_MIN, FUZZ_POPULATION_MAX]
      ]
      const diverged = pairs
        .map(([field, min, max]) => ({ field, min, max, java: boundsOf(source, field) }))
        .filter(row => row.min !== row.java.min || row.max !== row.java.max)
        .map(row => `${row.field}: ts=${row.min}-${row.max} vs java=${row.java.min}-${row.java.max}`)

      expect(diverged).toEqual([])
    })

  it('flags a reported-reading specification as unexplorable, mirroring the backend', () => {
    /*
     * The explorer keeps one value per shared reading and models no compromised device, so it cannot tell
     * "what this device said" from "what the home held" — it would answer the `environment` question and
     * label the answer as this specification's. The backend refuses these with
     * `REPORTED_READING_UNSUPPORTED`; this rule exists so the panel does not offer a run that will be
     * refused, and must stay in step with it.
     */
    const spec = (variableSource?: string) => ({ name: 'Safety requirement',
      templateId: '1',
      aConditions: [{
        deviceId: 'd1',
        targetType: 'variable',
        key: 'temperature',
        variableSource,
        relation: '>',
        value: '30'
      }],
      ifConditions: [],
      thenConditions: []
    }) as any

    expect(getKnownFuzzingSpecificationIssue(spec('reported'))).toBe('REPORTED_READING_UNSUPPORTED')
    // `environment` IS answerable: it is the one question the explorer's single value represents.
    expect(getKnownFuzzingSpecificationIssue(spec('environment'))).toBeNull()
    // A legacy condition that never chose is blocked earlier, at the run gate, not misreported here.
    expect(getKnownFuzzingSpecificationIssue(spec(undefined))).toBeNull()
  })
})
