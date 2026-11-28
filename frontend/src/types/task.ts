const ASYNC_TASK_STATUS_VALUES = ['PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'] as const
export type AsyncTaskStatus = typeof ASYNC_TASK_STATUS_VALUES[number]

const TASK_PROGRESS_STAGE_VALUES = [
  'QUEUED',
  'STARTING',
  'GENERATING_MODEL',
  'EXECUTING_MODEL_CHECKER',
  'PARSING_RESULTS',
  'RUNNING_SIMULATION',
  'PREPARING_EXPLORATION',
  'EXPLORING_CANDIDATES',
  'PERSISTING_RESULT'
] as const
export type TaskProgressStage = typeof TASK_PROGRESS_STAGE_VALUES[number]

const taskStatuses = new Set<unknown>(ASYNC_TASK_STATUS_VALUES)
const taskProgressStages = new Set<unknown>(TASK_PROGRESS_STAGE_VALUES)

export const isAsyncTaskStatus = (value: unknown): value is AsyncTaskStatus => taskStatuses.has(value)
export const isTaskProgressStage = (value: unknown): value is TaskProgressStage => taskProgressStages.has(value)

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
