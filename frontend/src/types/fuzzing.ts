import type { ModelRunSnapshot } from './modelSemantics'
import type { ModelPlaybackScene, RunInitiator } from './model'
import type { Specification } from './spec'
import type { AsyncTaskStatus, TaskProgressStage } from './task'
import type { TraceState } from './verify'
import type { ModelTokenSource } from './modelToken'

export const FUZZING_EXPLORATION_MODES = ['BOARD_SNAPSHOT', 'PAPER_COMPATIBLE'] as const
export type FuzzingExplorationMode = typeof FUZZING_EXPLORATION_MODES[number]

export const FUZZ_BASE_LIMITATION_CODES = [
  'FINITE_TRACE_TEMPLATES_1_3_4_ONLY',
  'ATTACK_TRUST_PRIVACY_CONTENT_UNSUPPORTED',
  'FINAL_ANTECEDENT_WITHOUT_SUCCESSOR_INCONCLUSIVE'
] as const

export const FUZZ_PAPER_SEMANTICS_CODES = [
  'PAPER_EVENT_FSM_DISTANCE_ENABLED',
  'PAPER_RANDOM_INITIAL_STATE_ENABLED',
  'PAPER_STRUCTURED_LTL_TEMPLATES_ONLY',
  'PAPER_INTEGER_NUMERIC_DOMAIN_ONLY',
  'PAPER_DISCRETE_ENVIRONMENT_DIRECT_VALUE_EXTENSION',
  'PAPER_PREDECESSOR_STATE_OUTPUTS_THREE_LEVELS_ONLY',
  'PAPER_MULTI_TARGET_PRODUCT_EXTENSION',
  'NUSMV_REMAINS_PROOF_AUTHORITY'
] as const

export const isValidFuzzPaperDomainFingerprint = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)

interface FuzzingRequestFields {
  targetSpecIds?: string[]
  maxIterations: number
  pathLength: number
  populationSize: number
  seed?: number
}

export type FuzzingRequest = FuzzingRequestFields & (
  | {
    explorationMode: 'BOARD_SNAPSHOT'
    paperDomainFingerprint?: never
  }
  | {
    explorationMode: 'PAPER_COMPATIBLE'
    paperDomainFingerprint: string
  }
)

export type FuzzPaperEventValueKind = 'CHANGE_RATE' | 'DIRECT_VALUE_EXTENSION'

export interface FuzzPaperDeviceDomain {
  targetId: string
  label: string
  property: string
  legalValues: string[]
  lowerBound?: number | null
  upperBound?: number | null
}

export interface FuzzPaperEnvironmentDomain {
  name: string
  targetId: string
  property: string
  eventValueKind: FuzzPaperEventValueKind
  initialValues: string[]
  eventValues: string[]
  initialLowerBound?: number | null
  initialUpperBound?: number | null
  eventRateLowerBound?: number | null
  eventRateUpperBound?: number | null
}

export interface FuzzPaperDomainPreview {
  pathLength: number
  modelFingerprint: string
  initializationPolicy: 'RANDOM_LEGAL_PER_SEED'
  paperSemanticsCodes: string[]
  deviceDomains: FuzzPaperDeviceDomain[]
  localVariableDomains: FuzzPaperDeviceDomain[]
  environmentDomains: FuzzPaperEnvironmentDomain[]
}

export interface FuzzWorkloadPreview {
  maxIterations: number
  pathLength: number
  populationSize: number
  explorationMode: FuzzingExplorationMode
  modelComplexityUnits: number
  estimatedWorkload: number
  workloadLimit: number
  accepted: boolean
  /** Largest `maxIterations` this board admits at the previewed path length and population size. */
  maxAcceptedIterations: number
}

export type FuzzingOutcome = 'FOUND_VIOLATION' | 'BUDGET_EXHAUSTED' | 'INCONCLUSIVE'

export interface FuzzingModelRunSnapshot extends ModelRunSnapshot {
  modelFingerprint: string
}

export interface FuzzingEligibilityIssue {
  specId: string
  specificationLabel?: string
  reasonCode: string
  reason: string
}

export interface FuzzingEligibility {
  eligibleSpecIds: string[]
  eligibleSpecLabels: Record<string, string>
  ineligibleSpecs: FuzzingEligibilityIssue[]
  requestedSpecCount: number
  eligibleSpecCount: number
}

export type FuzzingInputEventKind =
  | 'DEVICE_STATE'
  | 'DEVICE_VARIABLE'
  | 'ENVIRONMENT_VALUE'
  | 'ENVIRONMENT_RATE'

export type FuzzingInputEventSource = 'MODEL_CHOICE' | 'RANDOM_INITIAL_STATE' | 'SEED_EVENT'

export interface FuzzingInputEvent {
  step: number
  kind: FuzzingInputEventKind
  targetId: string
  property: string
  value: string
  source: FuzzingInputEventSource
}

export interface FuzzingFinding {
  id: number
  fuzzTaskId: number
  violatedSpecId: string
  violatedSpec: Specification
  firstViolationStep: number
  states: TraceState[]
  seed: number
  inputEvents: FuzzingInputEvent[]
  createdAt: string
}

/** One candidate finding with the immutable scene captured by its owning exploration run. */
export interface FuzzingFindingReplay {
  finding: FuzzingFinding
  modelSnapshot: FuzzingModelRunSnapshot
  playbackScene: ModelPlaybackScene
}

export type FuzzingFindingSummary = Pick<
  FuzzingFinding,
  | 'id'
  | 'fuzzTaskId'
  | 'violatedSpecId'
  | 'firstViolationStep'
  | 'seed'
  | 'createdAt'
> & {
  violatedSpec?: Specification
  specificationLabel?: string
  stateCount: number
  /**
   * History summaries omit full trajectory evidence. This flag is added only after a selected
   * finding's own detail has been rejected, so sibling findings remain independently usable.
   */
  dataAvailable?: true | false
  unavailableReasonCode?: 'PERSISTED_SEMANTIC_DATA_INVALID' | string
}

export interface FuzzingTask {
  id: number
  initiator: RunInitiator
  explorationMode: FuzzingExplorationMode
  status: AsyncTaskStatus
  progress: number
  progressStage?: TaskProgressStage
  createdAt: string
  startedAt?: string
  completedAt?: string
  processingTimeMs?: number
  errorMessage?: string
  runId?: number
  outcome?: FuzzingOutcome
  modelSnapshot: FuzzingModelRunSnapshot
  maxIterations: number
  pathLength: number
  populationSize: number
  seed?: number
  targetSpecIds: string[]
}

export type FuzzingTaskSummary = FuzzingTask

export interface AvailableFuzzingRunSummary {
  id: number
  initiator: RunInitiator
  explorationMode: FuzzingExplorationMode
  outcome: FuzzingOutcome
  effectiveSeed: number
  iterations: number
  generatedPaths: number
  elapsedMs: number
  modelSnapshot: FuzzingModelRunSnapshot
  eligibility: FuzzingEligibility
  eligibleSpecifications: Record<string, Specification>
  modelTokenSourcesByDeviceId: Record<string, ModelTokenSource>
  limitations: string[]
  createdAt: string
  completedAt: string
  findingCount: number
  maxIterations: number
  pathLength: number
  populationSize: number
  dataAvailable: true
  findings: FuzzingFindingSummary[]
}

export interface UnavailableFuzzingRunSummary {
  id: number
  initiator: RunInitiator
  explorationMode?: FuzzingExplorationMode
  createdAt?: string
  completedAt?: string
  findingCount?: number
  findings: FuzzingFindingSummary[]
  dataAvailable: false
  unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID' | string
}

export type FuzzingRunSummary = AvailableFuzzingRunSummary | UnavailableFuzzingRunSummary

export interface FuzzingRun extends Omit<
  AvailableFuzzingRunSummary,
  'dataAvailable' | 'findings'
> {
  dataAvailable?: never
  playbackScene: ModelPlaybackScene
  targetSpecIds: string[]
  findings: FuzzingFinding[]
}
