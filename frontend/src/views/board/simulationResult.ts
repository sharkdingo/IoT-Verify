import type {
  AvailableSimulationTraceSummary,
  SimulationResult,
  SimulationTrace
} from '@/types/simulation'

export type SavedSimulationResult = SimulationResult & Pick<SimulationTrace, 'createdAt' | 'initiator'>

/** Keep the frozen result intact when adapting its saved-record identity for playback. */
export const simulationResultFromTrace = (trace: SimulationTrace): SavedSimulationResult => {
  const { id, ...result } = trace
  return { ...result, traceId: id }
}

export const summarizeSimulationResult = (
  result: SavedSimulationResult
): AvailableSimulationTraceSummary | null => {
  if (result.traceId === undefined) return null
  return {
    id: result.traceId,
    initiator: result.initiator,
    createdAt: result.createdAt,
    requestedSteps: result.requestedSteps,
    steps: result.steps,
    modelComplete: result.modelComplete,
    disabledRuleCount: result.disabledRuleCount,
    generationIssues: result.generationIssues,
    isAttack: result.isAttack,
    attackBudget: result.attackBudget,
    enablePrivacy: result.enablePrivacy,
    modelSnapshot: result.modelSnapshot,
    hasSmvModel: result.hasSmvModel,
    dataAvailable: true
  }
}
