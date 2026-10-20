import { describe, expect, it } from 'vitest'
import type { SimulationTrace } from '@/types/simulation'
import { simulationResultFromTrace, summarizeSimulationResult } from './simulationResult'

const trace = (): SimulationTrace => ({
  id: 17,
  initiator: 'AI_ASSISTANT',
  createdAt: '2026-10-07T00:00:00',
  modelComplete: false,
  disabledRuleCount: 1,
  generationIssues: [],
  requestedSteps: 20,
  steps: 0,
  isAttack: true,
  attackBudget: 2,
  enablePrivacy: true,
  modelSnapshot: {
    capturedAt: '2026-10-07T00:00:00', deviceCount: 0, ruleCount: 0,
    specificationCount: 0, environmentVariableCount: 0, deviceTemplateCount: 0,
    templatesFrozen: true
  },
  modelSemantics: {
    attackPointUnit: 'BEHAVIOR_CHANGING_DEVICE_INSTANCE_OR_AUTOMATION_LINK',
    attackSelectionPolicy: 'NOT_MODELED', attackEffects: [],
    modeledDeviceAttackPointCount: 0, modeledFalsifiableReadingDeviceCount: 0,
    modeledAutomationLinkAttackPointCount: 0, modeledAttackPointCount: 0,
    trustPropagationPolicy: 'TARGET_UNTRUSTED_IF_ALL_TRIGGER_SOURCES_UNTRUSTED',
    privacyPropagationPolicy: 'NOT_MODELED', labelPropagationScope: 'AUTOMATION_RULE_COMMANDS_ONLY',
    environmentEvolutionEffects: [], localVariableFallbackPolicy: 'STUTTER_WHEN_NO_DECLARED_EVOLUTION'
  },
  playbackScene: { nodes: [], rules: [] },
  states: [{
    stateIndex: 0, devices: [], triggeredRules: [], compromisedAutomationLinks: [],
    envVariables: [{ name: 'temperature', value: '', observed: false, trust: null, modelTokenSource: 'CUSTOM' }]
  }],
  logs: ['generation incomplete'],
  nusmvOutput: 'no transition',
  historyPersistence: { status: 'SAVED', runId: 17 },
  hasSmvModel: true
})

describe('simulation result projections', () => {
  it('preserves the entire saved result and unobserved readings for playback', () => {
    const saved = trace()
    const { id, ...payload } = saved
    const result = simulationResultFromTrace(saved)
    expect(result).toEqual({ ...payload, traceId: id })
    expect(result.states).toBe(saved.states)
    expect(result.playbackScene).toBe(saved.playbackScene)
    expect(result.historyPersistence).toBe(saved.historyPersistence)
  })

  it.each([true, false, undefined])('preserves stored-model availability %s in the history row', hasSmvModel => {
    const saved = { ...trace(), hasSmvModel }
    const summary = summarizeSimulationResult(simulationResultFromTrace(saved))
    expect(summary).toMatchObject({
      id: 17, initiator: 'AI_ASSISTANT', createdAt: saved.createdAt,
      modelComplete: false, steps: 0, disabledRuleCount: 1, dataAvailable: true
    })
    expect(summary?.hasSmvModel).toBe(hasSmvModel)
    expect(summary?.modelSnapshot).toBe(saved.modelSnapshot)
  })

  it('keeps an unsuccessful history save usable without inventing a history row', () => {
    const saved: SimulationTrace = { ...trace(), id: undefined, historyPersistence: { status: 'FAILED' } }
    const result = simulationResultFromTrace(saved)
    expect(result.states).toBe(saved.states)
    expect(result.historyPersistence.status).toBe('FAILED')
    expect(summarizeSimulationResult(result)).toBeNull()
  })
})
