export type RunPersistenceStatus = 'SAVED' | 'NOT_REQUESTED' | 'FAILED' | 'OUTCOME_UNKNOWN'

export type RunPersistence = {
  status: 'SAVED'
  runId: number
  reasonCode?: never
} | {
  status: Exclude<RunPersistenceStatus, 'SAVED'>
  runId?: never
  reasonCode?: string
}
