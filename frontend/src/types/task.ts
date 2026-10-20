export type AsyncTaskStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export type TaskProgressStage =
  | 'QUEUED'
  | 'STARTING'
  | 'GENERATING_MODEL'
  | 'EXECUTING_MODEL_CHECKER'
  | 'PARSING_RESULTS'
  | 'RUNNING_SIMULATION'
  | 'PREPARING_EXPLORATION'
  | 'EXPLORING_CANDIDATES'
  | 'PERSISTING_RESULT'

export const INTERACTIVE_OPERATION_STAGES = [
  'QUEUED',
  'RUNNING',
  'PREPARING_CONTEXT',
  'REQUESTING_MODEL',
  'VALIDATING_RESULT',
  'PREPARING_MODEL',
  'SEARCHING_AND_VERIFYING',
  'COMPARING_VERIFIED_OPTIONS',
  'FINALIZING',
  'CANCELLING'
] as const

export type InteractiveOperationStage = typeof INTERACTIVE_OPERATION_STAGES[number]

export interface InteractiveOperationStatus {
  requestId: string
  state: 'WAITING' | 'RUNNING' | 'FINISHED'
  stage: InteractiveOperationStage
  elapsedMs: number
}

export type TaskCancellationOutcome =
  | 'ACCEPTED'
  | 'ALREADY_CANCELLED'
  | 'ALREADY_COMPLETED'
  | 'ALREADY_FAILED'
  | 'NO_LONGER_CANCELLABLE'

export interface TaskCancellationResult {
  taskId: number
  cancellationAccepted: boolean
  cancellationOutcome: TaskCancellationOutcome
  taskStatus: AsyncTaskStatus
  executionMayStillBeStopping: boolean
}
