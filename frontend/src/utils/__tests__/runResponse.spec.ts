// @vitest-environment node
import { describe, expect, it } from 'vitest'
import type { ModelSemantics } from '@/types/modelSemantics'
import {
  RUN_RESPONSE_INCOMPLETE_CODE,
  activeTaskProgressStage,
  hasPersistedVerificationTrace,
  validateInteractiveOperationStatus,
  validateSimulationResult,
  validateSimulationTask,
  validateSimulationTrace,
  validateSimulationTraceSummary,
  validateTaskCancellationResult,
  validateTaskProgress,
  validateTaskSubmissionId,
  validateVerificationRun,
  validateVerificationRunSummary,
  validateVerificationTask,
  validateVerificationResult
} from '../runResponse'

const modelSemantics: ModelSemantics = {
  attackPointUnit: 'BEHAVIOR_CHANGING_DEVICE_INSTANCE_OR_AUTOMATION_LINK',
  attackSelectionPolicy: 'NOT_MODELED',
  attackEffects: [],
  modeledDeviceAttackPointCount: 2,
  modeledFalsifiableReadingDeviceCount: 1,
  modeledAutomationLinkAttackPointCount: 3,
  modeledAttackPointCount: 5,
  trustPropagationPolicy: 'TARGET_UNTRUSTED_IF_ALL_TRIGGER_SOURCES_UNTRUSTED',
  privacyPropagationPolicy: 'NOT_MODELED',
  labelPropagationScope: 'AUTOMATION_RULE_COMMANDS_ONLY',
  environmentEvolutionEffects: [
    'DECLARED_NUMERIC_RATES_AND_DEVICE_EFFECTS_WITHIN_DOMAIN',
    'UNWRITTEN_DISCRETE_VALUES_NONDETERMINISTIC_WITHIN_DECLARED_DOMAIN',
    'DEVICE_WRITTEN_DISCRETE_VALUES_HOLD_WHEN_NO_DECLARED_EFFECT_APPLIES'
  ],
  localVariableFallbackPolicy: 'STUTTER_WHEN_NO_DECLARED_EVOLUTION'
}

const state = (stateIndex: number) => ({
  stateIndex,
  devices: [{
    deviceId: 'device_1',
    deviceLabel: 'Hall sensor',
    templateName: 'Motion Detector',
    modelTokenSource: 'BUNDLED',
    state: 'idle',
    variables: []
  }],
  triggeredRules: [],
  compromisedAutomationLinks: [],
  trustPrivacies: [],
  envVariables: [],
  globalVariables: []
})

const modelSnapshot = (specificationCount: number) => ({
  capturedAt: '2026-07-12T00:00:00',
  deviceCount: 2,
  ruleCount: 3,
  specificationCount,
  environmentVariableCount: 1,
  deviceTemplateCount: 2,
  templatesFrozen: true
})

const playbackScene = () => ({
  nodes: [
    { id: 'device_1', templateName: 'Motion Detector', label: 'Hall sensor', position: { x: 20, y: 40 }, state: 'idle', width: 160, height: 120 },
    { id: 'device_2', templateName: 'Alarm', label: 'Hall alarm', position: { x: 360, y: 40 }, state: 'off', width: 160, height: 120 }
  ],
  rules: [1, 2, 3].map(id => ({
    id,
    conditions: [{ deviceName: 'device_1', attribute: 'state', targetType: 'state', relation: '=', value: 'active' }],
    command: { deviceName: 'device_2', action: 'on' },
    ruleString: `Rule ${id}`
  }))
})

const validVerification = () => ({
  isAttack: false,
  attackBudget: 0,
  enablePrivacy: false,
  modelSemantics,
  modelSnapshot: modelSnapshot(1),
  historyPersistence: { status: 'SAVED', runId: 7 },
  outcome: 'SATISFIED',
  modelComplete: true,
  traces: [],
  specResults: [{
    specId: 'spec_1',
    templateId: '1',
    specificationLabel: 'Safety requirement',
    specificationSnapshot: specificationSnapshot(),
    formulaPreview: 'CTL AG("Hall sensor".state = "active")',
    formulaKind: 'CTL',
    antecedentReachability: 'NOT_APPLICABLE',
    outcome: 'SATISFIED',
    expression: 'CTLSPEC AG motion'
  }],
  checkLogs: [],
  disabledRuleCount: 0,
  skippedSpecCount: 0,
  generationIssues: [],
  nusmvOutput: '-- specification is true'
})

const specificationSnapshot = () => ({ name: 'Safety requirement',
  id: 'spec_1', templateId: '1',
  aConditions: [{ id: 'reading', side: 'a', deviceId: 'sensor', deviceLabel: 'Old label',
    targetType: 'variable', variableSource: 'reported', key: 'temperature', relation: '>', value: '25' }],
  ifConditions: [], thenConditions: [],
  devices: { sensor: { label: 'Frozen sensor', modelTokenSource: 'BUNDLED', modeCount: 1 } }
})

describe('frozen specification interpretation contract', () => {
  it.each([undefined, null, 'UNKNOWN', 'REACHABLE'])('rejects invalid or inapplicable trigger evidence: %s', evidence => {
    const result = validVerification()
    Object.assign(result.specResults[0], { antecedentReachability: evidence })
    expect(() => validateVerificationResult(result)).toThrow(/antecedentReachability/)
  })

  it.each(['4', '5', '6'])('requires explicit trigger evidence for response template %s', templateId => {
    const result = validVerification()
    const frozen = specificationSnapshot()
    Object.assign(result.specResults[0], { templateId, antecedentReachability: 'NOT_APPLICABLE',
      specificationSnapshot: { ...frozen, templateId, aConditions: [],
        ifConditions: frozen.aConditions.map(condition => ({ ...condition, side: 'if' })),
        thenConditions: frozen.aConditions.map(condition => ({ ...condition, side: 'then' })) } })
    expect(() => validateVerificationResult(result)).toThrow(/antecedentReachability/)
    for (const antecedentReachability of ['REACHABLE', 'UNREACHABLE', 'INCONCLUSIVE']) {
      Object.assign(result.specResults[0], { antecedentReachability })
      result.outcome = antecedentReachability === 'INCONCLUSIVE' ? 'INCONCLUSIVE' : 'SATISFIED'
      result.modelComplete = antecedentReachability !== 'INCONCLUSIVE'
      expect(validateVerificationResult(result).specResults[0]).toMatchObject({ antecedentReachability })
      if (antecedentReachability === 'INCONCLUSIVE') {
        expect(() => validateVerificationResult({ ...result, outcome: 'SATISFIED', modelComplete: true })).toThrow()
      }
    }
  })
  it('requires frozen requirements instead of accepting older incomplete records', () => {
    const result = validVerification()
    for (const missing of [undefined, null]) {
      expect(() => validateVerificationResult({ ...result, specResults: [
        { ...result.specResults[0], specificationSnapshot: missing }
      ] })).toThrow(/specificationSnapshot/)
    }
    const snapshot = specificationSnapshot()
    const withSnapshot = { ...result, specResults: [{ ...result.specResults[0], specificationSnapshot: snapshot }] }
    expect(validateVerificationResult(withSnapshot).specResults[0].specificationSnapshot).toBe(snapshot)
  })

  it.each([
    ['name', (snapshot: any) => { delete snapshot.name }],
    ['mismatched name', (snapshot: any) => { snapshot.name = 'Different requirement' }],
    ['identity', (snapshot: any) => { snapshot.id = 'different_spec' }],
    ['template', (snapshot: any) => { snapshot.templateId = '6' }],
    ['condition collection', (snapshot: any) => { snapshot.aConditions = null }],
    ['condition value', (snapshot: any) => { snapshot.aConditions[0].value = 25 }],
    ['reading source', (snapshot: any) => { delete snapshot.aConditions[0].variableSource }],
    ['condition side', (snapshot: any) => { snapshot.aConditions[0].side = 'then' }],
    ['device identity', (snapshot: any) => { snapshot.devices = {} }],
    ['token provenance', (snapshot: any) => { snapshot.devices.sensor.modelTokenSource = 'guessed' }],
    ['mode count', (snapshot: any) => { snapshot.devices.sensor.modeCount = -1 }],
    ['template shape', (snapshot: any) => { snapshot.aConditions = [] }]
  ])('rejects malformed %s instead of displaying an invented explanation', (_name, corrupt) => {
    const result = validVerification()
    const snapshot = specificationSnapshot()
    corrupt(snapshot)
    expect(() => validateVerificationResult({ ...result,
      specResults: [{ ...result.specResults[0], specificationSnapshot: snapshot }] })).toThrow()
  })
})

const unpersistedViolation = () => ({
  ...validVerification(),
  historyPersistence: {
    status: 'FAILED',
    reasonCode: 'RUN_HISTORY_SAVE_FAILED'
  },
  outcome: 'VIOLATED',
  traces: [{
    violatedSpecId: 'spec_1',
    violatedSpec: specificationSnapshot(),
    checkedExpression: 'CTLSPEC AG motion',
    modelComplete: true,
    disabledRuleCount: 0,
    skippedSpecCount: 0,
    generationIssues: [],
    states: [state(1)],
    isAttack: false,
    attackBudget: 0,
    enablePrivacy: false,
    modelSemantics,
    modelSnapshot: modelSnapshot(1),
    playbackScene: playbackScene(),
    createdAt: '2026-07-12T00:00:02'
  }],
  specResults: validVerification().specResults.map(result => ({
    ...result,
    outcome: 'VIOLATED'
  })),
  nusmvOutput: '-- specification is false'
})

const validSimulation = () => ({
  isAttack: false,
  attackBudget: 0,
  enablePrivacy: false,
  modelSemantics,
  modelSnapshot: modelSnapshot(0),
  playbackScene: playbackScene(),
  historyPersistence: { status: 'NOT_REQUESTED' },
  modelComplete: true,
  disabledRuleCount: 0,
  generationIssues: [],
  states: [state(1), state(2)],
  steps: 1,
  requestedSteps: 1,
  nusmvOutput: 'Trace Type: Simulation',
  logs: []
})

const validCompletedVerificationTask = () => ({
  id: 7,
  initiator: 'USER',
  status: 'COMPLETED',
  createdAt: '2026-07-12T00:00:00',
  startedAt: '2026-07-12T00:00:01',
  completedAt: '2026-07-12T00:00:02',
  processingTimeMs: 1000,
  progress: 100,
  isAttack: false,
  attackBudget: 0,
  enablePrivacy: false,
  modelSemantics,
  modelSnapshot: modelSnapshot(1),
  outcome: 'SATISFIED',
  modelComplete: true,
  violatedSpecCount: 0,
  disabledRuleCount: 0,
  skippedSpecCount: 0,
  generationIssues: [],
  specResults: validVerification().specResults,
  checkLogs: [],
  nusmvOutput: '-- specification is true'
})

const validVerificationRun = () => ({
  id: 7,
  initiator: 'AI_ASSISTANT',
  createdAt: '2026-07-12T00:00:00',
  startedAt: '2026-07-12T00:00:01',
  completedAt: '2026-07-12T00:00:02',
  processingTimeMs: 1000,
  isAttack: false,
  attackBudget: 0,
  enablePrivacy: false,
  modelSemantics,
  modelSnapshot: modelSnapshot(1),
  outcome: 'SATISFIED',
  modelComplete: true,
  violatedSpecCount: 0,
  counterexampleCount: 0,
  counterexamples: [],
  disabledRuleCount: 0,
  skippedSpecCount: 0,
  generationIssues: [],
  specResults: validVerification().specResults,
  checkLogs: [],
  nusmvOutput: '-- specification is true'
})

const validCompletedSimulationTask = () => ({
  id: 8,
  initiator: 'USER',
  status: 'COMPLETED',
  createdAt: '2026-07-12T00:00:00',
  startedAt: '2026-07-12T00:00:01',
  completedAt: '2026-07-12T00:00:02',
  processingTimeMs: 1000,
  progress: 100,
  isAttack: false,
  attackBudget: 0,
  enablePrivacy: false,
  modelSemantics,
  modelSnapshot: modelSnapshot(0),
  requestedSteps: 2,
  steps: 2,
  modelComplete: true,
  disabledRuleCount: 0,
  generationIssues: [],
  simulationTraceId: 21,
  checkLogs: []
})

describe('verification and simulation response contracts', () => {
  it('retains valid fingerprints and rejects malformed comparison evidence', () => {
    const result = validVerification()
    const captured = { ...result.modelSnapshot, modelFingerprint: 'a'.repeat(64) }
    expect(validateVerificationResult({ ...result, modelSnapshot: captured }).modelSnapshot.modelFingerprint).toBe('a'.repeat(64))
    for (const modelFingerprint of ['', 'a'.repeat(63), 'A'.repeat(64), 1]) {
      expect(() => validateVerificationResult({ ...result, modelSnapshot: { ...captured, modelFingerprint } })).toThrow()
    }
  })
  it('accepts an explicitly complete verification result', () => {
    expect(validateVerificationResult(validVerification())).toEqual(validVerification())
  })

  it('keeps an unpersisted counterexample replayable without treating it as fixable', () => {
    const result = validateVerificationResult(unpersistedViolation())
    expect(result.traces[0].id).toBeUndefined()
    expect(hasPersistedVerificationTrace(result, result.traces[0])).toBe(false)
    expect(() => validateVerificationResult({
      ...unpersistedViolation(),
      traces: [{
        ...unpersistedViolation().traces[0],
        id: 17,
        verificationTaskId: 7
      }]
    })).toThrow(expect.objectContaining({ code: RUN_RESPONSE_INCOMPLETE_CODE }))
  })

  it('requires a saved trace identity to belong to the reported history run', () => {
    const savedViolation = {
      ...unpersistedViolation(),
      historyPersistence: { status: 'SAVED', runId: 7 },
      traces: [{
        ...unpersistedViolation().traces[0],
        id: 17,
        verificationTaskId: 7
      }]
    }
    const result = validateVerificationResult(savedViolation)

    expect(hasPersistedVerificationTrace(result, result.traces[0])).toBe(true)
    expect(() => validateVerificationResult({
      ...savedViolation,
      traces: [{ ...savedViolation.traces[0], verificationTaskId: 8 }]
    })).toThrow(expect.objectContaining({ code: RUN_RESPONSE_INCOMPLETE_CODE }))
    expect(() => validateVerificationResult({
      ...savedViolation,
      traces: [{
        ...savedViolation.traces[0],
        id: undefined,
        verificationTaskId: undefined
      }]
    })).toThrow(expect.objectContaining({ code: RUN_RESPONSE_INCOMPLETE_CODE }))
  })

  it('does not infer verification completeness from missing fields', () => {
    const result = validVerification() as Record<string, unknown>
    delete result.modelComplete

    expect(() => validateVerificationResult(result)).toThrow(expect.objectContaining({
      code: RUN_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('requires omission counts to have matching itemized generation issues', () => {
    expect(() => validateVerificationResult({
      ...validVerification(),
      modelComplete: false,
      skippedSpecCount: 1
    })).toThrow(expect.objectContaining({
      code: RUN_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('does not accept an overall satisfied conclusion that contradicts per-spec results', () => {
    expect(() => validateVerificationResult({
      ...validVerification(),
      specResults: [{
        specId: 'spec_1',
        templateId: '1',
        specificationLabel: 'Always',
        formulaPreview: 'CTL AG("Hall sensor".state = "active")',
        formulaKind: 'CTL',
        antecedentReachability: 'NOT_APPLICABLE',
        outcome: 'VIOLATED',
        expression: 'CTLSPEC AG motion'
      }]
    })).toThrow(expect.objectContaining({
      code: RUN_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('does not accept a satisfied conclusion that checked no specifications at all', () => {
    /*
     * The vacuous case, and the one worth pinning separately from its sibling above.
     *
     * The backend derives the outcome with `specResults.stream().allMatch(o == SATISFIED)`, and `allMatch` is
     * true for an empty list — so a run whose specifications were all dropped before SMV emission would label
     * itself SATISFIED. An early return upstream reports INCONCLUSIVE instead
     * (`EmptySpecOutcomeContractTest` pins that), and this is the client's independent half: "safe" is the one
     * answer a user acts on without reading further, so it should take two failures to produce it, not one.
     */
    expect(() => validateVerificationResult({
      ...validVerification(),
      specResults: []
    })).toThrow(expect.objectContaining({
      code: RUN_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('accepts a playable simulation whose step count matches its states', () => {
    expect(validateSimulationResult(validSimulation())).toEqual(validSimulation())
  })

  it.each([false, true])('rejects empty simulation states at the API boundary, saved=%s', saved => {
    const result = {
      ...validSimulation(),
      ...(saved ? { id: 3, initiator: 'USER', createdAt: '2026-10-08T00:00:00',
        historyPersistence: { status: 'SAVED', runId: 3 } } : {}),
      steps: 0,
      states: []
    }
    const validate = saved ? validateSimulationTrace : validateSimulationResult
    expect(() => validate(result)).toThrow(expect.objectContaining({ code: RUN_RESPONSE_INCOMPLETE_CODE }))
  })

  it.each([false, true])('accepts a zero-transition simulation with its initial state, saved=%s', saved => {
    const result = {
      ...validSimulation(),
      ...(saved ? { id: 3, initiator: 'USER', createdAt: '2026-10-08T00:00:00',
        historyPersistence: { status: 'SAVED', runId: 3 } } : {}),
      steps: 0,
      states: [state(1)]
    }
    const validate = saved ? validateSimulationTrace : validateSimulationResult
    expect(validate(result)).toEqual(result)
  })

  it.each([
    { label: 'zero-based', indexes: [0, 1] },
    { label: 'duplicate', indexes: [1, 1] },
    { label: 'gapped', indexes: [1, 3] }
  ])('rejects $label formal state indexes', ({ indexes }) => {
    expect(() => validateSimulationResult({
      ...validSimulation(),
      states: indexes.map(state)
    })).toThrow(expect.objectContaining({
      code: RUN_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('validates nested trace variables, security labels, and frozen token sources', () => {
    const detailedState = {
      stateIndex: 1,
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
          trust: 'trusted',
          modelTokenSource: 'BUNDLED'
        }],
        trustPrivacy: [{
          name: 'idle',
          propertyScope: 'state',
          mode: 'MotionMode',
          trust: true,
          privacy: null
        }],
        privacies: [{
          name: 'recording',
          propertyScope: 'content',
          privacy: 'private'
        }]
      }],
      triggeredRules: [{ ruleIndex: 0, ruleId: 'rule-1', ruleLabel: 'Record motion' }],
      compromisedAutomationLinks: [],
      trustPrivacies: [],
      envVariables: [{
        name: 'temperature',
        value: '21',
        trust: 'untrusted',
        modelTokenSource: 'UNKNOWN'
      }],
      globalVariables: [{
        name: 'compromisedPointCount',
        value: '0',
        trust: null,
        modelTokenSource: 'UNKNOWN'
      }]
    }

    expect(validateSimulationResult({
      ...validSimulation(),
      states: [detailedState, { ...detailedState, stateIndex: 2 }]
    }).states[0]).toMatchObject({ stateIndex: 1 })

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
          trustPrivacy: [{ name: 'idle', propertyScope: 'generated_state', trust: true }]
        }]
      },
      {
        ...detailedState,
        devices: [{
          ...detailedState.devices[0],
          trustPrivacy: [{
            name: 'idle',
            propertyScope: 'state',
            mode: 'MotionMode',
            privacy: 'private'
          }]
        }]
      },
      {
        ...detailedState,
        devices: [{
          ...detailedState.devices[0],
          privacies: [{
            name: 'recording',
            propertyScope: 'content',
            trust: true
          }]
        }]
      },
      {
        ...detailedState,
        devices: [{
          ...detailedState.devices[0],
          trustPrivacy: [
            detailedState.devices[0].trustPrivacy[0],
            { ...detailedState.devices[0].trustPrivacy[0], trust: false }
          ]
        }]
      },
      {
        ...detailedState,
        envVariables: [{ name: 'temperature', value: '21', trust: true }]
      },
      {
        ...detailedState,
        globalVariables: [{
          name: 'compromisedPointCount',
          value: '0',
          modelTokenSource: 'BUNDLED'
        }]
      },
      {
        ...detailedState,
        triggeredRules: [{ ruleId: 'rule-1', ruleLabel: 'Missing frozen index' }]
      },
      {
        ...detailedState,
        triggeredRules: [
          detailedState.triggeredRules[0],
          { ...detailedState.triggeredRules[0] }
        ]
      }
    ]

    malformedStates.forEach(malformedState => {
      expect(() => validateSimulationResult({
        ...validSimulation(),
        states: [malformedState, state(2)]
      })).toThrow(expect.objectContaining({ code: RUN_RESPONSE_INCOMPLETE_CODE }))
    })

    const sourceDriftState = {
      ...detailedState,
      stateIndex: 2,
      devices: [{
        ...detailedState.devices[0],
        modelTokenSource: 'CUSTOM',
        variables: detailedState.devices[0].variables.map(variable => ({
          ...variable,
          modelTokenSource: 'CUSTOM'
        }))
      }]
    }
    expect(() => validateSimulationResult({
      ...validSimulation(),
      states: [detailedState, sourceDriftState]
    })).toThrow(expect.objectContaining({ code: RUN_RESPONSE_INCOMPLETE_CODE }))

    expect(() => validateSimulationResult({
      ...validSimulation(),
      states: [detailedState, {
        ...detailedState,
        stateIndex: 2,
        envVariables: detailedState.envVariables.map(variable => ({
          ...variable,
          modelTokenSource: 'CUSTOM'
        }))
      }]
    })).toThrow(expect.objectContaining({ code: RUN_RESPONSE_INCOMPLETE_CODE }))

    expect(() => validateSimulationResult({
      ...validSimulation(),
      states: [detailedState, {
        ...detailedState,
        stateIndex: 2,
        devices: []
      }]
    })).toThrow(expect.objectContaining({ code: RUN_RESPONSE_INCOMPLETE_CODE }))

    expect(() => validateSimulationResult({
      ...validSimulation(),
      states: [detailedState, {
        ...detailedState,
        stateIndex: 2,
        devices: [{ ...detailedState.devices[0], variables: [] }]
      }]
    })).toThrow(expect.objectContaining({ code: RUN_RESPONSE_INCOMPLETE_CODE }))
  })

  it('does not treat missing simulation completeness as a complete model', () => {
    const result = validSimulation() as Record<string, unknown>
    delete result.modelComplete

    expect(() => validateSimulationResult(result)).toThrow(expect.objectContaining({
      code: RUN_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('rejects a simulation history summary with contradictory attack context', () => {
    expect(() => validateSimulationTraceSummary({
      id: 3,
      initiator: 'USER',
      requestedSteps: 2,
      steps: 2,
      modelComplete: true,
      disabledRuleCount: 0,
      generationIssues: [],
      isAttack: false,
      attackBudget: 1,
      enablePrivacy: false,
      modelSnapshot: modelSnapshot(0),
      createdAt: '2026-07-12T00:00:00',
      dataAvailable: true
    })).toThrow(expect.objectContaining({
      code: RUN_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('validates completed task conclusions and requires a saved simulation trace', () => {
    expect(validateVerificationTask(validCompletedVerificationTask())).toEqual(validCompletedVerificationTask())
    expect(validateSimulationTask(validCompletedSimulationTask())).toEqual(validCompletedSimulationTask())

    expect(() => validateSimulationTask({
      ...validCompletedSimulationTask(),
      simulationTraceId: undefined
    })).toThrow(expect.objectContaining({
      code: RUN_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('validates completed verification history independently from task lifecycle', () => {
    expect(validateVerificationRun(validVerificationRun())).toEqual(validVerificationRun())
    const { specResults: _specResults, checkLogs: _checkLogs, nusmvOutput: _nusmvOutput, ...runSummary } = validVerificationRun()
    const summary = { ...runSummary, dataAvailable: true, counterexamples: [] }
    expect(validateVerificationRunSummary(summary)).toEqual(summary)

    expect(() => validateVerificationRunSummary({
      ...summary,
      counterexampleCount: undefined
    })).toThrow(expect.objectContaining({ code: RUN_RESPONSE_INCOMPLETE_CODE }))

    const traceSummary = {
      id: 17,
      verificationTaskId: summary.id,
      violatedSpecId: 'spec-1',
      violatedSpec: { name: 'Safety requirement',
        id: 'spec-1',
        templateId: '1',
        aConditions: [],
        ifConditions: [],
        thenConditions: []
      },
      stateCount: 2,
      modelTokenSourcesByDeviceId: { door_1: 'BUNDLED' },
      createdAt: '2026-07-12T00:00:00',
      dataAvailable: true
    }
    expect(validateVerificationRunSummary({
      ...summary,
      outcome: 'VIOLATED',
      violatedSpecCount: 1,
      counterexampleCount: 1,
      counterexamples: [traceSummary]
    }).counterexamples).toEqual([traceSummary])

    for (const sources of [undefined, { door_1: 'INVENTED' }, { '': 'BUNDLED' }]) {
      expect(() => validateVerificationRunSummary({
        ...summary, outcome: 'VIOLATED', violatedSpecCount: 1, counterexampleCount: 1,
        counterexamples: [{ ...traceSummary, modelTokenSourcesByDeviceId: sources }]
      })).toThrow(expect.objectContaining({ code: RUN_RESPONSE_INCOMPLETE_CODE }))
    }

    const unavailableTraceSummary = {
      id: 18,
      verificationTaskId: summary.id,
      dataAvailable: false,
      unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID'
    } as const
    const mixedCounterexamples = [traceSummary, unavailableTraceSummary]
    expect(validateVerificationRunSummary({
      ...summary,
      outcome: 'VIOLATED',
      violatedSpecCount: 1,
      counterexampleCount: 1,
      counterexamples: mixedCounterexamples
    }).counterexamples).toEqual(mixedCounterexamples)

    expect(() => validateVerificationRunSummary({
      ...summary,
      outcome: 'VIOLATED',
      violatedSpecCount: 1,
      counterexampleCount: 2,
      counterexamples: mixedCounterexamples
    })).toThrow(expect.objectContaining({ code: RUN_RESPONSE_INCOMPLETE_CODE }))

    const { createdAt: _createdAt, ...traceWithoutTimestamp } = traceSummary
    expect(() => validateVerificationRunSummary({
      ...summary,
      outcome: 'VIOLATED',
      violatedSpecCount: 1,
      counterexampleCount: 1,
      counterexamples: [traceWithoutTimestamp]
    })).toThrow(expect.objectContaining({ code: RUN_RESPONSE_INCOMPLETE_CODE }))
  })

  it('validates exact run-scoped counterexample references on detail reads', () => {
    const run = validVerificationRun()
    const unavailable = {
      id: 18,
      verificationTaskId: run.id,
      dataAvailable: false,
      unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID'
    }
    expect(validateVerificationRun({ ...run, counterexamples: [unavailable] }).counterexamples)
      .toEqual([unavailable])
    for (const counterexamples of [undefined, null, {},
      [{ ...unavailable, verificationTaskId: run.id + 1 }],
      [{ ...unavailable, dataAvailable: 'false' }]]) {
      expect(() => validateVerificationRun({ ...run, counterexamples }))
        .toThrow(expect.objectContaining({ code: RUN_RESPONSE_INCOMPLETE_CODE }))
    }
    expect(() => validateVerificationRun({ ...run, counterexamples: [unavailable], counterexampleCount: 1 }))
      .toThrow(/counterexampleCount must match available counterexamples/)
  })

  it('rejects results that cannot prove templates were frozen at submission', () => {
    expect(() => validateVerificationResult({
      ...validVerification(),
      modelSnapshot: {
        ...modelSnapshot(1),
        templatesFrozen: false
      }
    })).toThrow(expect.objectContaining({
      code: RUN_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('does not allow an active task to publish a premature conclusion', () => {
    expect(() => validateVerificationTask({
      ...validCompletedVerificationTask(),
      status: 'RUNNING',
      completedAt: undefined,
      progress: 40
    })).toThrow(expect.objectContaining({
      code: RUN_RESPONSE_INCOMPLETE_CODE
    }))
  })

  // `progress` says how much of the work finished, so only COMPLETED may claim 100. Demanding it of
  // every terminal status made a failed run publish "100%" for work it abandoned, and the backend's
  // expired-lease sweep overwrote progress to match — so an abandoned run looked finished.
  it('accepts a failed task that reports the partial progress it actually reached', () => {
    const failedPartway = {
      ...validCompletedVerificationTask(),
      status: 'FAILED',
      progress: 35,
      // A failed run reaches no verdict: the validator requires INCONCLUSIVE and modelComplete=false
      // for exactly that reason, so the fixture has to satisfy both to isolate the progress rule.
      outcome: 'INCONCLUSIVE',
      modelComplete: false,
      errorMessage: 'The verification worker stopped before the task completed'
    }
    expect(validateVerificationTask(failedPartway)).toEqual(failedPartway)
  })

  it('rejects a completed task that does not report finished work', () => {
    expect(() => validateVerificationTask({
      ...validCompletedVerificationTask(),
      progress: 35
    })).toThrow(expect.objectContaining({
      code: RUN_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('validates task ids, progress, and cancellation outcomes before the UI acts on them', () => {
    expect(validateTaskSubmissionId(9, 'Task submission')).toBe(9)
    expect(validateTaskProgress(63, 'Task progress')).toBe(63)
    expect(validateTaskCancellationResult({
      taskId: 9,
      cancellationAccepted: true,
      cancellationOutcome: 'ACCEPTED',
      taskStatus: 'CANCELLED',
      executionMayStillBeStopping: true
    }, 9, 'Task cancellation')).toMatchObject({ cancellationAccepted: true })

    expect(() => validateTaskProgress(101, 'Task progress')).toThrow(expect.objectContaining({
      code: RUN_RESPONSE_INCOMPLETE_CODE
    }))
    expect(() => validateTaskCancellationResult({
      taskId: 10,
      cancellationAccepted: false,
      cancellationOutcome: 'ALREADY_COMPLETED',
      taskStatus: 'COMPLETED',
      executionMayStillBeStopping: false
    }, 9, 'Task cancellation')).toThrow(expect.objectContaining({
      code: RUN_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it.each(['REQUESTING_MODEL', 'COMPARING_VERIFIED_OPTIONS'])(
    'validates the server-observed %s interactive phase', stage => {
      const status = {
        requestId: 'request-123',
        state: 'RUNNING',
        stage,
        elapsedMs: 1250
      }

      expect(validateInteractiveOperationStatus(status)).toEqual(status)
      expect(() => validateInteractiveOperationStatus({
        ...status,
        stage: 'THINKING_FOR_A_WHILE'
      })).toThrow(expect.objectContaining({ code: RUN_RESPONSE_INCOMPLETE_CODE }))
    })

  it('does not present a stale active phase after a task reaches a terminal state', () => {
    expect(activeTaskProgressStage('PERSISTING_RESULT', 'RUNNING')).toBe('PERSISTING_RESULT')
    expect(activeTaskProgressStage('PERSISTING_RESULT', 'COMPLETED')).toBeNull()
    expect(activeTaskProgressStage('EXPLORING_CANDIDATES', 'FAILED')).toBeNull()
    expect(activeTaskProgressStage('STARTING', 'CANCELLED')).toBeNull()
  })
})

describe('a verification trace without a checked expression', () => {
  /*
   * `TraceDto.checkedExpression` has no `@NotBlank` and a nullable `TEXT` column, so a trace can legitimately
   * carry none. Requiring it non-blank was not a per-row problem: traces are validated through a `value.map(...)`,
   * so **one** such row threw and discarded the user's whole verification history list. The consumer already
   * guards with `v-if="currentTrace?.checkedExpression"`, so the UI never needed the guarantee.
   */
  it('is accepted, and does not take the rest of the list with it', () => {
    const result = validateVerificationResult({
      ...unpersistedViolation(),
      traces: [
        { ...unpersistedViolation().traces[0], checkedExpression: null },
        { ...unpersistedViolation().traces[0] }
      ]
    })

    expect(result.traces).toHaveLength(2)
  })

  it('still rejects a non-string expression, which is a real contract breach', () => {
    expect(() => validateVerificationResult({
      ...unpersistedViolation(),
      traces: [{ ...unpersistedViolation().traces[0], checkedExpression: 42 }]
    })).toThrowError(/checkedExpression/)
  })
})
