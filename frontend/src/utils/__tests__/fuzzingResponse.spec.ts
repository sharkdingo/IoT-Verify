import { describe, expect, it } from 'vitest'
import {
  FUZZ_RESPONSE_INCOMPLETE_CODE,
  assertFuzzingFindingBelongsToRun,
  getFuzzActiveTaskLimit,
  getFuzzStoredTaskLimit,
  resolveFuzzingRunFinding,
  validateFuzzingFinding,
  validateFuzzingFindingReplay,
  validateFuzzPaperDomainPreview,
  validateFuzzWorkloadPreview,
  validateFuzzingRun,
  validateFuzzingRunSummaryList,
  validateFuzzingTask,
  validateFuzzingTaskSummaryList
} from '../fuzzingResponse'
import {
  FUZZ_BASE_LIMITATION_CODES,
  FUZZ_PAPER_SEMANTICS_CODES
} from '@/types/fuzzing'
import type { FuzzingRun } from '@/types/fuzzing'

const snapshot = {
  capturedAt: '2026-07-14T10:00:00',
  deviceCount: 2,
  ruleCount: 1,
  specificationCount: 1,
  environmentVariableCount: 1,
  deviceTemplateCount: 2,
  modelFingerprint: 'a'.repeat(64),
  templatesFrozen: true as const
}

const traceState = (stateIndex: number) => ({
  stateIndex,
  devices: [],
  triggeredRules: [],
  compromisedAutomationLinks: []
})

const playbackScene = {
  nodes: [
    { id: 'sensor_1', templateName: 'Sensor', label: 'Sensor', position: { x: 20, y: 30 }, state: 'on', width: 160, height: 120 },
    { id: 'alarm_1', templateName: 'Alarm', label: 'Alarm', position: { x: 360, y: 30 }, state: 'off', width: 160, height: 120 }
  ],
  rules: [{
    id: 7,
    conditions: [{ deviceName: 'sensor_1', attribute: 'state', targetType: 'state' as const, relation: '=', value: 'on' }],
    command: { deviceName: 'alarm_1', action: 'on' },
    ruleString: 'Sensor turns on alarm'
  }]
}

const run = {
  id: 4,
  initiator: 'USER' as const,
  explorationMode: 'BOARD_SNAPSHOT',
  outcome: 'BUDGET_EXHAUSTED',
  effectiveSeed: 42,
  iterations: 500,
  generatedPaths: 5000,
  elapsedMs: 1200,
  modelSnapshot: snapshot,
  playbackScene,
  eligibility: {
    eligibleSpecIds: ['spec-1'],
    eligibleSpecLabels: { 'spec-1': 'Door remains closed' },
    ineligibleSpecs: [],
    requestedSpecCount: 1,
    eligibleSpecCount: 1
  },
  eligibleSpecifications: { 'spec-1': { id: 'spec-1', templateId: '1' as const, templateLabel: 'Always',
    aConditions: [{ id: 'c1', side: 'a' as const, deviceId: 'sensor_1', deviceLabel: 'Sensor', targetType: 'state' as const, key: 'state', relation: '=', value: 'on' }],
    ifConditions: [], thenConditions: [] } },
  modelTokenSourcesByDeviceId: { sensor_1: 'BUNDLED' as const, alarm_1: 'CUSTOM' as const },
  limitations: [...FUZZ_BASE_LIMITATION_CODES],
  maxIterations: 500,
  pathLength: 20,
  populationSize: 10,
  createdAt: '2026-07-14T10:00:00',
  completedAt: '2026-07-14T10:00:01',
  findingCount: 0,
  targetSpecIds: [],
  findings: []
}

describe('fuzzing response contracts', () => {
  it('parses the stable active-task quota response without exposing backend prose', () => {
    expect(getFuzzActiveTaskLimit({
      response: {
        status: 429,
        data: {
          message: 'backend-only wording',
          data: {
            reasonCode: 'FUZZ_ACTIVE_TASK_LIMIT_REACHED',
            activeTaskCount: 2,
            maxActiveTasksPerUser: 2
          }
        }
      }
    })).toEqual({ activeTaskCount: 2, maxActiveTasksPerUser: 2 })
    expect(getFuzzActiveTaskLimit({ response: { status: 429, data: { data: {} } } })).toBeNull()
  })

  it('parses the stable stored-task quota response without exposing backend prose', () => {
    expect(getFuzzStoredTaskLimit({
      response: {
        status: 429,
        data: {
          message: 'backend-only wording',
          data: {
            reasonCode: 'FUZZ_STORED_TASK_LIMIT_REACHED',
            storedTaskCount: 100,
            maxStoredTasksPerUser: 100
          }
        }
      }
    })).toEqual({ storedTaskCount: 100, maxStoredTasksPerUser: 100 })
    expect(getFuzzStoredTaskLimit({ response: { status: 429, data: { data: {} } } })).toBeNull()
  })

  it('validates the authoritative paper input-domain preview', () => {
    const preview = {
      pathLength: 20,
      modelFingerprint: 'a'.repeat(64),
      initializationPolicy: 'RANDOM_LEGAL_PER_SEED',
      paperSemanticsCodes: [...FUZZ_PAPER_SEMANTICS_CODES, 'FUTURE_PAPER_SEMANTICS'],
      deviceDomains: [{
        targetId: 'door-1',
        label: 'Front door',
        property: 'workingState',
        legalValues: ['open', 'closed']
      }],
      localVariableDomains: [{
        targetId: 'door-1',
        label: 'Front door',
        property: 'battery',
        legalValues: ['20', '100']
      }],
      environmentDomains: [{
        name: 'temperature',
        targetId: 'environment',
        property: 'temperature',
        eventValueKind: 'CHANGE_RATE',
        initialValues: ['18', '19', '20'],
        eventValues: ['-1', '0', '1']
      }]
    }
    expect(validateFuzzPaperDomainPreview(preview)).toMatchObject({ pathLength: 20 })
    expect(validateFuzzPaperDomainPreview({
      ...preview,
      localVariableDomains: [{
        targetId: 'door-1',
        label: 'Front door',
        property: 'battery',
        legalValues: [],
        lowerBound: -2_000_000_000,
        upperBound: 2_000_000_000
      }],
      environmentDomains: [{
        name: 'temperature',
        targetId: 'environment',
        property: 'temperature',
        eventValueKind: 'CHANGE_RATE',
        initialValues: [],
        eventValues: [],
        initialLowerBound: -2_000_000_000,
        initialUpperBound: 2_000_000_000,
        eventRateLowerBound: -20_000,
        eventRateUpperBound: 20_000
      }]
    })).toMatchObject({ pathLength: 20 })
    expect(() => validateFuzzPaperDomainPreview({
      ...preview,
      environmentDomains: [{
        ...preview.environmentDomains[0],
        initialValues: [],
        initialLowerBound: 0
      }]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzPaperDomainPreview({
      ...preview,
      environmentDomains: [{ ...preview.environmentDomains[0], eventValueKind: 'VALUE' }]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzPaperDomainPreview({
      ...preview,
      modelFingerprint: 'A'.repeat(64)
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzPaperDomainPreview({
      ...preview,
      localVariableDomains: undefined
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzPaperDomainPreview({
      ...preview,
      deviceDomains: [preview.deviceDomains[0], { ...preview.deviceDomains[0] }]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzPaperDomainPreview({
      ...preview,
      paperSemanticsCodes: FUZZ_PAPER_SEMANTICS_CODES.slice(1)
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzPaperDomainPreview({
      ...preview,
      paperSemanticsCodes: [...FUZZ_PAPER_SEMANTICS_CODES, FUZZ_PAPER_SEMANTICS_CODES[0]]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzPaperDomainPreview(preview, 19)).toThrowError(
      expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE })
    )
  })

  it('requires workload acceptance to agree with the backend estimate and requested budget', () => {
    const request = {
      maxIterations: 1300,
      pathLength: 4,
      populationSize: 50,
      explorationMode: 'BOARD_SNAPSHOT' as const
    }
    const preview = {
      ...request,
      modelComplexityUnits: 49,
      estimatedWorkload: 12_740_000,
      workloadLimit: 12_500_000,
      accepted: false,
      // 12,500,000 / (4 x 50 x 49) = 1,275, below the requested 1,300 — consistent with accepted: false.
      maxAcceptedIterations: 1_275
    }

    expect(validateFuzzWorkloadPreview(preview, request)).toEqual(preview)
    expect(() => validateFuzzWorkloadPreview({ ...preview, accepted: true }, request))
      .toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzWorkloadPreview(preview, { ...request, pathLength: 5 }))
      .toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))

    // Complexity is mode-dependent, so a preview from the other mode describes a different estimate.
    expect(() => validateFuzzWorkloadPreview(
      preview, { ...request, explorationMode: 'PAPER_COMPATIBLE' as const }))
      .toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))

    // The remedy must not contradict the verdict it sits beside.
    expect(() => validateFuzzWorkloadPreview(
      { ...preview, maxAcceptedIterations: 5_000 }, request))
      .toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzWorkloadPreview(
      { ...preview, accepted: true, maxAcceptedIterations: 0 }, request))
      .toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))

    expect(validateFuzzWorkloadPreview({
      ...request,
      modelComplexityUnits: 0,
      estimatedWorkload: request.maxIterations * request.pathLength * request.populationSize,
      workloadLimit: 12_500_000,
      accepted: true,
      maxAcceptedIterations: 5_000
    }, request).modelComplexityUnits).toBe(0)

    expect(() => validateFuzzWorkloadPreview({
      ...preview,
      estimatedWorkload: preview.estimatedWorkload - 1
    }, request)).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))

    const unsafeRequest = {
      maxIterations: 2,
      pathLength: 1,
      populationSize: 1,
      explorationMode: 'BOARD_SNAPSHOT' as const
    }
    expect(() => validateFuzzWorkloadPreview({
      ...unsafeRequest,
      modelComplexityUnits: Number.MAX_SAFE_INTEGER,
      estimatedWorkload: Number.MAX_SAFE_INTEGER,
      workloadLimit: Number.MAX_SAFE_INTEGER,
      accepted: true,
      maxAcceptedIterations: 5_000
    }, unsafeRequest)).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })

  it('requires frozen snapshot and execution counts to respect their run budgets', () => {
    expect(() => validateFuzzingRun({
      ...run,
      modelSnapshot: { ...snapshot, deviceCount: 1, deviceTemplateCount: 2 }
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingRun({
      ...run,
      modelSnapshot: { ...snapshot, modelFingerprint: undefined }
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingRun({
      ...run,
      iterations: run.maxIterations + 1
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingRun({
      ...run,
      iterations: 0,
      generatedPaths: 1
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingRun({
      ...run,
      iterations: 1,
      generatedPaths: 0
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingRun({
      ...run,
      iterations: 2,
      generatedPaths: 1,
      populationSize: 1
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingRun({
      ...run,
      iterations: 2,
      generatedPaths: 3,
      populationSize: 1
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingRun({
      ...run,
      iterations: Number.MAX_SAFE_INTEGER,
      generatedPaths: Number.MAX_SAFE_INTEGER,
      maxIterations: Number.MAX_SAFE_INTEGER,
      populationSize: 2
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(validateFuzzingRun({
      ...run,
      iterations: 0,
      generatedPaths: 0
    })).toMatchObject({ iterations: 0, generatedPaths: 0 })
  })

  it('requires mode-specific limitation codes while allowing future additions', () => {
    expect(validateFuzzingRun({
      ...run,
      limitations: [...FUZZ_BASE_LIMITATION_CODES, 'FUTURE_LIMITATION']
    }).limitations).toContain('FUTURE_LIMITATION')
    expect(() => validateFuzzingRun({
      ...run,
      limitations: FUZZ_BASE_LIMITATION_CODES.slice(1)
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingRun({
      ...run,
      limitations: [...FUZZ_BASE_LIMITATION_CODES, FUZZ_BASE_LIMITATION_CODES[0]]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(validateFuzzingRun({
      ...run,
      explorationMode: 'PAPER_COMPATIBLE',
      limitations: [
        ...FUZZ_BASE_LIMITATION_CODES,
        ...FUZZ_PAPER_SEMANTICS_CODES,
        'FUTURE_PAPER_LIMITATION'
      ]
    }).explorationMode).toBe('PAPER_COMPATIBLE')
    expect(() => validateFuzzingRun({
      ...run,
      explorationMode: 'PAPER_COMPATIBLE',
      limitations: [...FUZZ_BASE_LIMITATION_CODES, ...FUZZ_PAPER_SEMANTICS_CODES.slice(1)]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })

  it('keeps budget exhaustion distinct from a verification success', () => {
    expect(validateFuzzingRun(run).outcome).toBe('BUDGET_EXHAUSTED')
  })

  it('accepts a maximum-length board ID after model normalization in details and history', () => {
    const boardId = '1'.repeat(100)
    const modelId = `_${boardId}`
    const result = {
      ...run,
      playbackScene: {
        nodes: [{ ...playbackScene.nodes[0], id: boardId }, playbackScene.nodes[1]],
        rules: [{ ...playbackScene.rules[0], conditions: [
          { ...playbackScene.rules[0].conditions[0], deviceName: boardId }
        ] }]
      },
      eligibleSpecifications: { 'spec-1': { ...run.eligibleSpecifications['spec-1'],
        aConditions: [{ ...run.eligibleSpecifications['spec-1'].aConditions[0], deviceId: modelId }] } },
      modelTokenSourcesByDeviceId: { [modelId]: 'BUNDLED', alarm_1: 'CUSTOM' }
    }
    expect(validateFuzzingRun(result).modelTokenSourcesByDeviceId[modelId]).toBe('BUNDLED')
    expect(validateFuzzingRunSummaryList([{ ...result, dataAvailable: true }])[0])
      .toMatchObject({ modelTokenSourcesByDeviceId: { [modelId]: 'BUNDLED' } })

    const oversized = { ...result,
      modelTokenSourcesByDeviceId: { ['x'.repeat(201)]: 'BUNDLED', alarm_1: 'CUSTOM' },
      eligibleSpecifications: { 'spec-1': { ...result.eligibleSpecifications['spec-1'],
        aConditions: [{ ...result.eligibleSpecifications['spec-1'].aConditions[0], deviceId: 'x'.repeat(201) }] } } }
    expect(() => validateFuzzingRun(oversized)).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingRunSummaryList([{ ...oversized, dataAvailable: true }]))
      .toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })

  it('requires complete frozen specification and token provenance for details and history', () => {
    const malformed = [
      { ...run, eligibleSpecifications: undefined },
      { ...run, eligibleSpecifications: {} },
      { ...run, modelTokenSourcesByDeviceId: undefined },
      { ...run, modelTokenSourcesByDeviceId: { sensor_1: 'BUNDLED' } },
      { ...run, modelTokenSourcesByDeviceId: { sensor_1: 'BUNDLED', alarm_1: 'INVENTED' } },
      { ...run, modelTokenSourcesByDeviceId: { sensor_1: 'BUNDLED', 'sensor-1': 'CUSTOM' } },
      { ...run, eligibleSpecifications: { 'spec-1': { ...run.eligibleSpecifications['spec-1'],
        aConditions: [{ ...run.eligibleSpecifications['spec-1'].aConditions[0], deviceId: 'missing' }] } } }
    ]
    expect(validateFuzzingRun(run)).toEqual(run)
    expect(validateFuzzingRunSummaryList([{ ...run, dataAvailable: true }])).toHaveLength(1)
    for (const value of malformed) {
      expect(() => validateFuzzingRun(value)).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
      expect(() => validateFuzzingRunSummaryList([{ ...value, dataAvailable: true }]))
        .toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    }
  })

  it('keeps the run-detail contract free of the summary-only availability field', () => {
    const detail: FuzzingRun = {
      ...run,
      explorationMode: 'BOARD_SNAPSHOT',
      outcome: 'BUDGET_EXHAUSTED'
    }

    expect(validateFuzzingRun(detail)).not.toHaveProperty('dataAvailable')
    expect(() => validateFuzzingRun({ ...detail, dataAvailable: true })).toThrowError(
      expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE })
    )
  })

  it('rejects an unknown outcome', () => {
    expect(() => validateFuzzingRun({ ...run, outcome: 'SAFE' })).toThrowError(
      expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE })
    )
  })

  it('requires a recognized exploration mode on tasks and available runs', () => {
    expect(() => validateFuzzingRun({ ...run, explorationMode: 'LEGACY' })).toThrowError(
      expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE })
    )
    expect(() => validateFuzzingRun({ ...run, explorationMode: undefined })).toThrowError(
      expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE })
    )
    expect(() => validateFuzzingTask({
      id: 1,
      initiator: 'USER',
      status: 'RUNNING',
      progress: 10,
      createdAt: '2026-07-14T10:00:00',
      modelSnapshot: snapshot,
      maxIterations: 500,
      pathLength: 20,
      populationSize: 10,
      targetSpecIds: []
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })

  it('requires outcomes, finding counts, and itemized findings to agree', () => {
    expect(() => validateFuzzingRun({ ...run, outcome: 'FOUND_VIOLATION' })).toThrowError(
      expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE })
    )
    expect(() => validateFuzzingRun({ ...run, findingCount: 1 })).toThrowError(
      expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE })
    )
    expect(() => validateFuzzingRun({
      ...run,
      outcome: 'INCONCLUSIVE'
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })

  it('requires itemized specification IDs and matching specification snapshots', () => {
    expect(() => validateFuzzingRun({
      ...run,
      eligibility: {
        eligibleSpecIds: [],
        eligibleSpecLabels: {},
        ineligibleSpecs: [{ reasonCode: 'UNSUPPORTED_TEMPLATE', reason: 'unsupported' }],
        requestedSpecCount: 1,
        eligibleSpecCount: 0
      }
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingRun({
      ...run,
      eligibility: {
        ...run.eligibility,
        eligibleSpecLabels: {}
      }
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingFinding({
      id: 2,
      fuzzTaskId: 1,
      violatedSpecId: 'spec-1',
      violatedSpec: { id: 'spec-2' },
      firstViolationStep: 0,
      states: [traceState(0)],
      seed: 42,
      inputEvents: [],
      createdAt: '2026-07-14T10:00:01'
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })

  it('accepts a minimal unavailable history placeholder and requires availability on summaries', () => {
    expect(validateFuzzingRunSummaryList([{
      id: 4,
      initiator: 'UNKNOWN',
      createdAt: '2026-07-14T10:00:00',
      completedAt: '2026-07-14T10:00:01',
      findings: [],
      dataAvailable: false,
      unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID'
    }])).toMatchObject([{ id: 4, dataAvailable: false }])
    expect(() => validateFuzzingRunSummaryList([{
      id: 4,
      initiator: 'USER',
      findings: [{ id: 2 }],
      dataAvailable: false,
      unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID'
    }])).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingRunSummaryList([run])).toThrowError(
      expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE })
    )
    expect(validateFuzzingRunSummaryList([{ ...run, dataAvailable: true }])).toHaveLength(1)
  })

  it('keeps detail findings distinct from history finding summaries', () => {
    const detailFinding = {
      id: 2,
      fuzzTaskId: 4,
      violatedSpecId: 'spec-1',
      violatedSpec: { id: 'spec-1' },
      firstViolationStep: 0,
      states: [traceState(0)],
      seed: 42,
      inputEvents: [],
      createdAt: '2026-07-14T10:00:01'
    }
    const validatedRun = validateFuzzingRun({
      ...run,
      outcome: 'FOUND_VIOLATION',
      findingCount: 1,
      findings: [detailFinding]
    })
    expect(validatedRun.findings[0].states).toHaveLength(1)
    expect(resolveFuzzingRunFinding(validatedRun, detailFinding.id))
      .toBe(validatedRun.findings[0])
    expect(() => resolveFuzzingRunFinding(validatedRun, 999))
      .toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingRun({
      ...run,
      outcome: 'FOUND_VIOLATION',
      findingCount: 1,
      findings: [{ ...detailFinding, fuzzTaskId: 99 }]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingRunSummaryList([{
      ...run,
      dataAvailable: true,
      findings: [detailFinding]
    }])).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })

  it('accepts a lightweight finding summary with a frozen label and no specification body', () => {
    const { targetSpecIds, ...summaryRun } = run
    const [validated] = validateFuzzingRunSummaryList([{
      ...summaryRun,
      outcome: 'FOUND_VIOLATION',
      findingCount: 1,
      dataAvailable: true,
      findings: [{
        id: 2,
        fuzzTaskId: 4,
        violatedSpecId: 'spec-1',
        specificationLabel: 'Frozen door safety label',
        firstViolationStep: 0,
        stateCount: 1,
        seed: 42,
        createdAt: '2026-07-14T10:00:01'
      }]
    }])

    expect(validated.findings[0]).toMatchObject({
      violatedSpecId: 'spec-1',
      specificationLabel: 'Frozen door safety label'
    })
    expect(validated.findings[0].violatedSpec).toBeUndefined()
  })

  it('requires history findings to match the owning run evidence contract', () => {
    const { targetSpecIds, ...summaryRun } = run
    const summaryFinding = {
      id: 2,
      fuzzTaskId: 4,
      violatedSpecId: 'spec-1',
      specificationLabel: 'Frozen door safety label',
      firstViolationStep: 0,
      stateCount: 1,
      seed: 42,
      createdAt: '2026-07-14T10:00:01'
    }
    const availableRun = {
      ...summaryRun,
      outcome: 'FOUND_VIOLATION',
      findingCount: 1,
      dataAvailable: true,
      findings: [summaryFinding]
    }

    expect(validateFuzzingRunSummaryList([availableRun])).toHaveLength(1)
    expect(() => validateFuzzingRunSummaryList([{
      ...availableRun,
      findings: [{ ...summaryFinding, dataAvailable: true }]
    }])).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingRunSummaryList([{
      ...availableRun,
      findings: [{ ...summaryFinding, seed: 43 }]
    }])).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingRunSummaryList([{
      ...availableRun,
      findings: [{ ...summaryFinding, violatedSpecId: 'spec-2' }]
    }])).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingRunSummaryList([{
      ...availableRun,
      pathLength: 1,
      findings: [{ ...summaryFinding, firstViolationStep: 1, stateCount: 2 }]
    }])).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingRunSummaryList([{
      ...availableRun,
      findingCount: 2,
      findings: [summaryFinding, { ...summaryFinding }]
    }])).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })

  it('requires full findings and independently loaded findings to retain run ownership', () => {
    const detailFinding = {
      id: 2,
      fuzzTaskId: 4,
      violatedSpecId: 'spec-1',
      violatedSpec: { id: 'spec-1' },
      firstViolationStep: 0,
      states: [traceState(0)],
      seed: 42,
      inputEvents: [],
      createdAt: '2026-07-14T10:00:01'
    }
    expect(() => validateFuzzingRun({
      ...run,
      outcome: 'FOUND_VIOLATION',
      findingCount: 1,
      findings: [{ ...detailFinding, seed: 43 }]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => assertFuzzingFindingBelongsToRun(detailFinding, 99, 'Fuzz finding replay'))
      .toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))

    expect(validateFuzzingFindingReplay({
      finding: detailFinding,
      modelSnapshot: snapshot,
      playbackScene
    })).toMatchObject({ finding: { id: 2 }, modelSnapshot: snapshot })
    expect(() => validateFuzzingFindingReplay({
      finding: detailFinding,
      modelSnapshot: { ...snapshot, deviceCount: 1 },
      playbackScene
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })

  it('requires completed metadata on available runs and targets only on details', () => {
    const { targetSpecIds, ...summaryRun } = run
    expect(targetSpecIds).toEqual([])
    expect(validateFuzzingRunSummaryList([{ ...summaryRun, dataAvailable: true }])).toHaveLength(1)
    expect(() => validateFuzzingRun({ ...run, targetSpecIds: undefined })).toThrowError(
      expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE })
    )
    expect(() => validateFuzzingRun({ ...run, completedAt: undefined })).toThrowError(
      expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE })
    )
    expect(() => validateFuzzingRunSummaryList([{
      ...summaryRun,
      findingCount: undefined,
      dataAvailable: true
    }])).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })

  it('rejects a task progress value outside 0-100', () => {
    expect(() => validateFuzzingTask({
      id: 1,
      initiator: 'USER',
      explorationMode: 'BOARD_SNAPSHOT',
      status: 'RUNNING',
      progress: 120,
      createdAt: '2026-07-14T10:00:00',
      modelSnapshot: snapshot,
      maxIterations: 500,
      pathLength: 20,
      populationSize: 10,
      targetSpecIds: []
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })

  it('requires inbox summaries to carry the frozen model snapshot and targets', () => {
    expect(validateFuzzingTaskSummaryList([{
      id: 1,
      initiator: 'AI_ASSISTANT',
      explorationMode: 'PAPER_COMPATIBLE',
      status: 'RUNNING',
      progress: 20,
      createdAt: '2026-07-14T10:00:00',
      maxIterations: 500,
      pathLength: 20,
      populationSize: 10,
      modelSnapshot: snapshot,
      targetSpecIds: []
    }])).toHaveLength(1)
    expect(() => validateFuzzingTaskSummaryList([{
      id: 1,
      initiator: 'USER',
      explorationMode: 'BOARD_SNAPSHOT',
      status: 'RUNNING',
      progress: 20,
      createdAt: '2026-07-14T10:00:00',
      maxIterations: 500,
      pathLength: 20,
      populationSize: 10
    }])).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })

  it('requires a replayable finding to contain states', () => {
    expect(() => validateFuzzingFinding({
      id: 2,
      fuzzTaskId: 1,
      violatedSpecId: 'spec-1',
      violatedSpec: { id: 'spec-1' },
      firstViolationStep: 3,
      states: [],
      seed: 42,
      inputEvents: [],
      createdAt: '2026-07-14T10:00:01'
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingFinding({
      id: 2,
      fuzzTaskId: 1,
      violatedSpecId: 'spec-1',
      violatedSpec: { id: 'spec-1' },
      firstViolationStep: 0,
      states: [{}],
      seed: 42,
      inputEvents: [],
      createdAt: '2026-07-14T10:00:01'
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })

  it('rejects malformed nested trace fields and forged token provenance before replay', () => {
    const detailedState = {
      stateIndex: 0,
      devices: [{
        deviceId: 'device_1',
        deviceLabel: 'Hall sensor',
        templateName: 'Motion Detector',
        modelTokenSource: 'BUNDLED',
        state: 'idle',
        compromised: false,
        variables: [{
          name: 'workingState',
          value: 'idle',
          trust: null,
          modelTokenSource: 'BUNDLED'
        }],
        trustPrivacy: [],
        privacies: []
      }],
      triggeredRules: [{ ruleIndex: 0, ruleId: 'rule-1', ruleLabel: 'Record motion' }],
      compromisedAutomationLinks: [],
      trustPrivacies: [],
      envVariables: [{
        name: 'temperature',
        value: '21',
        trust: null,
        modelTokenSource: 'UNKNOWN'
      }],
      globalVariables: [{
        name: 'compromisedPointCount',
        value: '0',
        trust: null,
        modelTokenSource: 'UNKNOWN'
      }]
    }
    const finding = {
      id: 2,
      fuzzTaskId: 1,
      violatedSpecId: 'spec-1',
      violatedSpec: { id: 'spec-1' },
      firstViolationStep: 0,
      states: [detailedState],
      seed: 42,
      inputEvents: [],
      createdAt: '2026-07-14T10:00:01'
    }

    expect(validateFuzzingFinding(finding).states).toHaveLength(1)
    const malformedStates = [
      {
        ...detailedState,
        devices: [{ ...detailedState.devices[0], modelTokenSource: null }]
      },
      {
        ...detailedState,
        devices: [{
          ...detailedState.devices[0],
          variables: [{ name: 'workingState', value: 'idle' }]
        }]
      },
      {
        ...detailedState,
        envVariables: [{ name: 'temperature', value: '21', trust: 'untrusted' }]
      },
      {
        ...detailedState,
        devices: [{ ...detailedState.devices[0], modelTokenSource: 'FORGED' }]
      },
      {
        ...detailedState,
        devices: [{
          ...detailedState.devices[0],
          variables: [{
            name: 'workingState',
            value: 'idle',
            modelTokenSource: 'CUSTOM'
          }]
        }]
      },
      {
        ...detailedState,
        devices: [{
          ...detailedState.devices[0],
          variables: [{ name: 'workingState', value: 1 }]
        }]
      },
      {
        ...detailedState,
        devices: [{
          ...detailedState.devices[0],
          trustPrivacy: [{ name: 'idle', propertyScope: 'state', mode: 'MotionMode', trust: true }]
        }]
      },
      {
        ...detailedState,
        devices: [{
          ...detailedState.devices[0],
          privacies: [{ name: 'recording', propertyScope: 'content', privacy: 'secret' }]
        }]
      },
      {
        ...detailedState,
        trustPrivacies: [{
          name: 'idle',
          propertyScope: 'state',
          mode: 'MotionMode',
          trust: true
        }]
      },
      {
        ...detailedState,
        envVariables: [{ name: 'temperature', value: '21', trust: true }]
      },
      {
        ...detailedState,
        devices: [{
          ...detailedState.devices[0],
          variables: [{
            ...detailedState.devices[0].variables[0],
            trust: 'trusted'
          }]
        }]
      },
      {
        ...detailedState,
        globalVariables: [{
          ...detailedState.globalVariables[0],
          trust: 'trusted'
        }]
      },
      {
        ...detailedState,
        globalVariables: [{
          name: 'compromisedPointCount',
          value: '0',
          modelTokenSource: 'BUNDLED'
        }]
      }
    ]

    malformedStates.forEach(malformedState => {
      expect(() => validateFuzzingFinding({ ...finding, states: [malformedState] }))
        .toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    })

    expect(() => validateFuzzingFinding({
      ...finding,
      states: [detailedState, {
        ...detailedState,
        stateIndex: 1,
        devices: [{
          ...detailedState.devices[0],
          modelTokenSource: 'CUSTOM',
          variables: detailedState.devices[0].variables.map(variable => ({
            ...variable,
            modelTokenSource: 'CUSTOM'
          }))
        }]
      }]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))

    expect(() => validateFuzzingFinding({
      ...finding,
      states: [detailedState, {
        ...detailedState,
        stateIndex: 1,
        devices: []
      }]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))

    expect(() => validateFuzzingFinding({
      ...finding,
      states: [detailedState, {
        ...detailedState,
        stateIndex: 1,
        envVariables: []
      }]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })

  it('requires violation and input-event steps to stay within the replayed prefix', () => {
    const finding = {
      id: 2,
      fuzzTaskId: 1,
      violatedSpecId: 'spec-1',
      violatedSpec: { id: 'spec-1' },
      firstViolationStep: 1,
      states: [traceState(0), traceState(1)],
      seed: 42,
      inputEvents: [{
        step: 1,
        kind: 'DEVICE_VARIABLE',
        targetId: 'device-1',
        property: 'temperature',
        value: '20',
        source: 'MODEL_CHOICE'
      }],
      createdAt: '2026-07-14T10:00:01'
    }
    expect(validateFuzzingFinding(finding)).toMatchObject({ firstViolationStep: 1 })
    expect(() => validateFuzzingFinding({
      ...finding,
      inputEvents: [{
        step: 1,
        kind: 'DEVICE_VARIABLE',
        targetId: 'device-1',
        property: 'temperature',
        value: '20'
      }]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingFinding({ ...finding, firstViolationStep: 2 })).toThrowError(
      expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE })
    )
    expect(() => validateFuzzingFinding({ ...finding, firstViolationStep: 0 })).toThrowError(
      expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE })
    )
    expect(() => validateFuzzingFinding({
      ...finding,
      inputEvents: [{ ...finding.inputEvents[0], step: 2 }]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })

  it('requires input events to retain causal step and source order', () => {
    const finding = {
      id: 2,
      fuzzTaskId: 1,
      violatedSpecId: 'spec-1',
      violatedSpec: { id: 'spec-1' },
      firstViolationStep: 1,
      states: [traceState(0), traceState(1)],
      seed: 42,
      inputEvents: [{
        step: 0,
        kind: 'DEVICE_VARIABLE',
        targetId: 'device-1',
        property: 'temperature',
        value: '20',
        source: 'RANDOM_INITIAL_STATE'
      }, {
        step: 0,
        kind: 'DEVICE_VARIABLE',
        targetId: 'device-1',
        property: 'temperature',
        value: '21',
        source: 'SEED_EVENT'
      }, {
        step: 0,
        kind: 'DEVICE_VARIABLE',
        targetId: 'device-1',
        property: 'temperature',
        value: '22',
        source: 'MODEL_CHOICE'
      }, {
        step: 1,
        kind: 'DEVICE_VARIABLE',
        targetId: 'device-1',
        property: 'temperature',
        value: '23',
        source: 'SEED_EVENT'
      }, {
        step: 1,
        kind: 'DEVICE_VARIABLE',
        targetId: 'device-1',
        property: 'temperature',
        value: '24',
        source: 'MODEL_CHOICE'
      }],
      createdAt: '2026-07-14T10:00:01'
    }

    expect(validateFuzzingFinding(finding).inputEvents.at(-1)?.source).toBe('MODEL_CHOICE')
    expect(() => validateFuzzingFinding({
      ...finding,
      inputEvents: [finding.inputEvents[3], finding.inputEvents[2]]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingFinding({
      ...finding,
      inputEvents: [finding.inputEvents[2], finding.inputEvents[1]]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingFinding({
      ...finding,
      inputEvents: [{ ...finding.inputEvents[0], step: 1 }]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })

  it('rejects unknown input-event kinds and empty required fields', () => {
    const finding = {
      id: 2,
      fuzzTaskId: 1,
      violatedSpecId: 'spec-1',
      violatedSpec: { id: 'spec-1' },
      firstViolationStep: 1,
      states: [traceState(0), traceState(1)],
      seed: 42,
      inputEvents: [{
        step: 1,
        kind: 'UNKNOWN',
        targetId: 'device-1',
        property: 'temperature',
        value: '20'
      }],
      createdAt: '2026-07-14T10:00:01'
    }
    expect(() => validateFuzzingFinding(finding)).toThrowError(
      expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE })
    )
    expect(() => validateFuzzingFinding({
      ...finding,
      inputEvents: [{ ...finding.inputEvents[0], kind: 'DEVICE_VARIABLE', value: ' ' }]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingFinding({
      ...finding,
      inputEvents: [{
        ...finding.inputEvents[0],
        kind: 'ENVIRONMENT_RATE',
        source: 'UNKNOWN'
      }]
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })

  it('rejects seeds outside JavaScript safe integer range across task, run, and finding payloads', () => {
    const unsafeSeed = Number.MAX_SAFE_INTEGER + 1
    expect(() => validateFuzzingRun({ ...run, effectiveSeed: unsafeSeed })).toThrowError(
      expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE })
    )
    expect(() => validateFuzzingTask({
      id: 1,
      initiator: 'USER',
      explorationMode: 'PAPER_COMPATIBLE',
      status: 'RUNNING',
      progress: 10,
      createdAt: '2026-07-14T10:00:00',
      modelSnapshot: snapshot,
      maxIterations: 500,
      pathLength: 20,
      populationSize: 10,
      seed: unsafeSeed,
      targetSpecIds: []
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateFuzzingFinding({
      id: 2,
      fuzzTaskId: 1,
      violatedSpecId: 'spec-1',
      violatedSpec: { id: 'spec-1' },
      firstViolationStep: 0,
      states: [traceState(0)],
      seed: unsafeSeed,
      inputEvents: [],
      createdAt: '2026-07-14T10:00:01'
    })).toThrowError(expect.objectContaining({ code: FUZZ_RESPONSE_INCOMPLETE_CODE }))
  })
})
