import { isModelFingerprint } from '@/utils/modelComparison'
import { INTERACTIVE_OPERATION_STAGES as INTERACTIVE_OPERATION_STAGE_VALUES } from '@/types/task'
import type {
  SimulationResult,
  SimulationTask,
  SimulationTaskSummary,
  SimulationTrace,
  SimulationTraceSummary
} from '@/types/simulation'
import type {
  AsyncTaskStatus,
  InteractiveOperationStatus,
  TaskProgressStage,
  TaskCancellationOutcome,
  TaskCancellationResult
} from '@/types/task'
import type {
  ImmediateTrace,
  ModelGenerationIssue,
  PersistedTrace,
  SpecResult,
  Trace,
  TraceSummary,
  TraceState,
  VerificationResult,
  VerificationRun,
  VerificationRunSummary,
  VerificationTask,
  VerificationTaskSummary
} from '@/types/verify'
import { isModelSemanticsConsistent } from './modelSemantics'
import type { RunPersistence, RunPersistenceStatus } from '@/types/runPersistence'
import { validateTraceStatePayload } from './traceStateResponse'
import { MODEL_TOKEN_SOURCE_SET } from '@/types/modelToken'
import { isRunInitiator } from '@/types/model'
import { validateModelPlaybackScene } from './playbackSceneResponse'
import { normalizeModelRelation } from './modelRequest'

export const RUN_RESPONSE_INCOMPLETE_CODE = 'RUN_RESPONSE_INCOMPLETE'

class RunResponseContractError extends Error {
  readonly code = RUN_RESPONSE_INCOMPLETE_CODE

  constructor(context: string, detail: string) {
    super(`${context} returned an incomplete run result: ${detail}`)
    this.name = 'RunResponseContractError'
  }
}

const requireRecord = (value: unknown, context: string, field = 'result'): Record<string, any> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new RunResponseContractError(context, `${field} must be an object`)
  }
  return value as Record<string, any>
}

const requireArray = (value: Record<string, any>, field: string, context: string): any[] => {
  if (!Array.isArray(value[field])) {
    throw new RunResponseContractError(context, `${field} must be an array`)
  }
  return value[field]
}

const requireString = (
  value: Record<string, any>,
  field: string,
  context: string,
  allowBlank = true
): string => {
  if (typeof value[field] !== 'string' || (!allowBlank && !value[field].trim())) {
    throw new RunResponseContractError(
      context,
      `${field} must be ${allowBlank ? 'text' : 'non-blank text'}`
    )
  }
  return value[field]
}

const requireBoolean = (value: Record<string, any>, field: string, context: string): boolean => {
  if (typeof value[field] !== 'boolean') {
    throw new RunResponseContractError(context, `${field} must be boolean`)
  }
  return value[field]
}

const requireRunInitiator = (value: Record<string, any>, context: string) => {
  if (!isRunInitiator(value.initiator)) {
    throw new RunResponseContractError(context, 'initiator is invalid')
  }
}

const requireInteger = (
  value: Record<string, any>,
  field: string,
  context: string,
  min = 0
): number => {
  if (!Number.isSafeInteger(value[field]) || value[field] < min) {
    throw new RunResponseContractError(context, `${field} must be an integer >= ${min}`)
  }
  return value[field]
}

const requireIntegerInRange = (
  value: Record<string, any>,
  field: string,
  context: string,
  min: number,
  max: number
): number => {
  const result = requireInteger(value, field, context, min)
  if (result > max) {
    throw new RunResponseContractError(context, `${field} must be an integer between ${min} and ${max}`)
  }
  return result
}

const requireOptionalTimestamp = (value: Record<string, any>, field: string, context: string) => {
  if (value[field] !== undefined && value[field] !== null) {
    requireString(value, field, context, false)
  }
}

const requireAttackContext = (result: Record<string, any>, context: string) => {
  const isAttack = requireBoolean(result, 'isAttack', context)
  const attackBudget = requireInteger(result, 'attackBudget', context)
  const enablePrivacy = requireBoolean(result, 'enablePrivacy', context)
  if ((!isAttack && attackBudget !== 0) || (isAttack && attackBudget < 1)) {
    throw new RunResponseContractError(context, 'attackBudget does not match isAttack')
  }
  if (!isModelSemanticsConsistent(result.modelSemantics, {
    isAttack,
    attackBudget,
    enablePrivacy
  })) {
    throw new RunResponseContractError(context, 'modelSemantics does not match the run context')
  }
  validateModelSnapshot(result, context)
}

const validateRunPersistence = (
  value: unknown,
  context: string,
  allowed: ReadonlySet<RunPersistenceStatus>
): RunPersistence => {
  const persistence = requireRecord(value, context, 'historyPersistence')
  if (!allowed.has(persistence.status)) {
    throw new RunResponseContractError(context, 'historyPersistence.status is invalid for this operation')
  }
  if (persistence.status === 'SAVED') {
    requireInteger(persistence, 'runId', context, 1)
    if (persistence.reasonCode !== undefined) {
      throw new RunResponseContractError(context, 'saved history cannot include a failure reason')
    }
  } else {
    if (persistence.runId !== undefined && persistence.runId !== null) {
      throw new RunResponseContractError(context, 'unsaved history cannot include runId')
    }
    if (persistence.status !== 'NOT_REQUESTED') {
      requireString(persistence, 'reasonCode', context, false)
    }
  }
  return persistence as RunPersistence
}

const validateModelSnapshot = (result: Record<string, any>, context: string) => {
  const snapshot = requireRecord(result.modelSnapshot, context, 'modelSnapshot')
  requireString(snapshot, 'capturedAt', context, false)
  const deviceCount = requireInteger(snapshot, 'deviceCount', context, 1)
  const templateCount = requireInteger(snapshot, 'deviceTemplateCount', context, 1)
  requireInteger(snapshot, 'ruleCount', context)
  requireInteger(snapshot, 'specificationCount', context)
  requireInteger(snapshot, 'environmentVariableCount', context)
  if (templateCount > deviceCount) {
    throw new RunResponseContractError(context, 'modelSnapshot deviceTemplateCount cannot exceed deviceCount')
  }
  if (snapshot.templatesFrozen !== true) {
    throw new RunResponseContractError(context, 'modelSnapshot.templatesFrozen must be true')
  }
  if (snapshot.modelFingerprint != null && !isModelFingerprint(snapshot.modelFingerprint)) {
    throw new RunResponseContractError(context, 'modelSnapshot.modelFingerprint must be a SHA-256 value')
  }
}

const validateGenerationIssues = (
  value: Record<string, any>,
  context: string,
  disabledRuleCount: number,
  skippedSpecCount?: number
): ModelGenerationIssue[] => {
  const issues = requireArray(value, 'generationIssues', context)
  issues.forEach((issue, index) => {
    const row = requireRecord(issue, context, `generationIssues[${index}]`)
    requireString(row, 'issueType', context, false)
    requireString(row, 'itemLabel', context, false)
    requireString(row, 'reasonCode', context, false)
    requireString(row, 'reason', context, false)
  })
  const disabledIssues = issues.filter(issue => issue.issueType === 'RULE_DISABLED').length
  if (disabledIssues !== disabledRuleCount) {
    throw new RunResponseContractError(context, 'disabledRuleCount must match generationIssues')
  }
  if (skippedSpecCount !== undefined) {
    const skippedIssues = issues.filter(issue => issue.issueType === 'SPECIFICATION_SKIPPED').length
    if (skippedIssues !== skippedSpecCount) {
      throw new RunResponseContractError(context, 'skippedSpecCount must match generationIssues')
    }
  }
  return issues as ModelGenerationIssue[]
}

const validateStateList = (states: unknown, context: string): TraceState[] => {
  validateTraceStatePayload(states, {
    firstStateIndex: 1,
    fail: detail => {
      throw new RunResponseContractError(context, detail)
    }
  })
  return states as TraceState[]
}

const validateSpecResults = (result: Record<string, any>, context: string): SpecResult[] => {
  const specResults = requireArray(result, 'specResults', context)
  specResults.forEach((item, index) => {
    const row = requireRecord(item, context, `specResults[${index}]`)
    requireString(row, 'specId', context, false)
    requireString(row, 'templateId', context, false)
    requireString(row, 'specificationLabel', context, false)
    requireString(row, 'formulaPreview', context, false)
    const snapshot = requireRecord(row.specificationSnapshot, context, 'specificationSnapshot')
    requireString(snapshot, 'name', context, false)
    if (!snapshot.name.trim() || snapshot.name.length > 100 || snapshot.name !== row.specificationLabel) {
      throw new RunResponseContractError(context, 'specification name must match its frozen label')
    }
    if (snapshot.id !== row.specId || snapshot.templateId !== row.templateId || !/^[1-7]$/.test(snapshot.templateId)) {
      throw new RunResponseContractError(context, 'specificationSnapshot identity must match its verdict')
    }
    const devices = requireRecord(snapshot.devices, context, 'specificationSnapshot.devices')
    Object.entries(devices).forEach(([id, item]) => {
      const device = requireRecord(item, context, `specificationSnapshot.devices.${id}`)
      requireString(device, 'label', context, false)
      if (!id.trim() || !MODEL_TOKEN_SOURCE_SET.has(device.modelTokenSource)) {
        throw new RunResponseContractError(context, 'specificationSnapshot device identity or provenance is invalid')
      }
      if (device.modeCount !== null) requireInteger(device, 'modeCount', context)
    })
    for (const [field, side] of [['aConditions', 'a'], ['ifConditions', 'if'], ['thenConditions', 'then']]) {
      requireArray(snapshot, field, context).forEach(item => {
        const condition = requireRecord(item, context, `specificationSnapshot.${field}`)
        for (const key of ['deviceId', 'targetType', 'key', 'relation', 'value']) requireString(condition, key, context, false)
        if (!Object.prototype.hasOwnProperty.call(devices, condition.deviceId)
            || !['state', 'mode', 'api', 'variable', 'trust', 'privacy'].includes(condition.targetType)
            || !normalizeModelRelation(condition.relation)
            || (condition.side != null && condition.side !== side)
            || (condition.targetType === 'variable' && !['environment', 'reported'].includes(condition.variableSource))
            || (['trust', 'privacy'].includes(condition.targetType) && !['state', 'variable'].includes(condition.propertyScope))) {
          throw new RunResponseContractError(context, `specificationSnapshot.${field} contains an invalid condition`)
        }
      })
    }
    const singleSide = ['1', '2', '3', '7'].includes(snapshot.templateId)
    if (singleSide ? snapshot.aConditions.length === 0 || snapshot.ifConditions.length !== 0 || snapshot.thenConditions.length !== 0
        : snapshot.aConditions.length !== 0 || snapshot.ifConditions.length === 0 || snapshot.thenConditions.length === 0) {
      throw new RunResponseContractError(context, 'specificationSnapshot conditions must match its template')
    }
    if (row.formulaKind !== 'CTL' && row.formulaKind !== 'LTL') {
      throw new RunResponseContractError(context, `specResults[${index}].formulaKind is invalid`)
    }
    requireString(row, 'expression', context, false)
    if (!['SATISFIED', 'VIOLATED', 'INCONCLUSIVE'].includes(row.outcome)) {
      throw new RunResponseContractError(context, `specResults[${index}].outcome is invalid`)
    }
    const allowedReachability = ['4', '5', '6'].includes(row.templateId)
      ? ['REACHABLE', 'UNREACHABLE', 'INCONCLUSIVE'] : ['NOT_APPLICABLE']
    if (!allowedReachability.includes(row.antecedentReachability)) {
      throw new RunResponseContractError(context, `specResults[${index}].antecedentReachability is invalid for its template`)
    }
  })
  return specResults as SpecResult[]
}

const validateStringArray = (result: Record<string, any>, field: string, context: string) => {
  requireArray(result, field, context).forEach((item, index) => {
    if (typeof item !== 'string') {
      throw new RunResponseContractError(context, `${field}[${index}] must be text`)
    }
  })
}

const TASK_STATUSES = new Set<AsyncTaskStatus>([
  'PENDING',
  'RUNNING',
  'COMPLETED',
  'FAILED',
  'CANCELLED'
])

const TASK_PROGRESS_STAGES = new Set<TaskProgressStage>([
  'QUEUED',
  'STARTING',
  'GENERATING_MODEL',
  'EXECUTING_MODEL_CHECKER',
  'PARSING_RESULTS',
  'RUNNING_SIMULATION',
  'PREPARING_EXPLORATION',
  'EXPLORING_CANDIDATES',
  'PERSISTING_RESULT'
])

const INTERACTIVE_OPERATION_STAGES = new Set<string>(INTERACTIVE_OPERATION_STAGE_VALUES)

const TERMINAL_TASK_STATUSES = new Set<AsyncTaskStatus>(['COMPLETED', 'FAILED', 'CANCELLED'])

export const activeTaskProgressStage = (
  stage?: TaskProgressStage | null,
  status?: AsyncTaskStatus | string
): TaskProgressStage | null => {
  if (!stage || (status && TERMINAL_TASK_STATUSES.has(status as AsyncTaskStatus))) return null
  return stage
}

const validateTaskBase = (value: unknown, context: string): Record<string, any> => {
  const task = requireRecord(value, context)
  requireInteger(task, 'id', context, 1)
  requireRunInitiator(task, context)
  if (!TASK_STATUSES.has(task.status)) {
    throw new RunResponseContractError(context, 'status is invalid')
  }
  const status = task.status as AsyncTaskStatus
  requireString(task, 'createdAt', context, false)
  requireAttackContext(task, context)
  requireOptionalTimestamp(task, 'startedAt', context)
  requireOptionalTimestamp(task, 'completedAt', context)
  if (task.processingTimeMs !== undefined && task.processingTimeMs !== null) {
    requireInteger(task, 'processingTimeMs', context)
  }
  if (task.progress !== undefined && task.progress !== null) {
    requireIntegerInRange(task, 'progress', context, 0, 100)
  }
  if (task.progressStage !== undefined && task.progressStage !== null
    && !TASK_PROGRESS_STAGES.has(task.progressStage)) {
    throw new RunResponseContractError(context, 'progressStage is invalid')
  }
  if (status === 'RUNNING' || status === 'COMPLETED') {
    requireString(task, 'startedAt', context, false)
  }
  if (TERMINAL_TASK_STATUSES.has(status)) {
    requireString(task, 'completedAt', context, false)
    // `progress` reports how much of the work finished, so only a COMPLETED task may claim 100.
    // Requiring it of every terminal status forced FAILED and CANCELLED runs to publish "100%"
    // for work that was abandoned — and the backend's expired-lease sweep duly overwrote it,
    // making an abandoned run indistinguishable from a finished one. A failed run keeps whatever
    // its last heartbeat reported; `status` is what says it ended.
    const progress = requireIntegerInRange(task, 'progress', context, 0, 100)
    if (status === 'COMPLETED' && progress !== 100) {
      throw new RunResponseContractError(context, 'completed task progress must be 100')
    }
  } else if (status === 'RUNNING' && task.progress === undefined) {
    throw new RunResponseContractError(context, 'running task progress is required')
  }
  if (status === 'FAILED') {
    requireString(task, 'errorMessage', context, false)
  }
  return task
}

const validateVerificationOutcomeAgainstResults = (
  outcome: unknown,
  specResults: SpecResult[],
  context: string
) => {
  const violatedCount = specResults.filter(result => result.outcome === 'VIOLATED').length
  const inconclusiveCount = specResults.filter(result => result.outcome === 'INCONCLUSIVE'
    || result.antecedentReachability === 'INCONCLUSIVE').length
  if (outcome === 'SATISFIED'
      && (specResults.length === 0 || violatedCount !== 0 || inconclusiveCount !== 0)) {
    throw new RunResponseContractError(context, 'SATISFIED contradicts per-specification results')
  }
  if (outcome === 'VIOLATED' && (violatedCount === 0 || inconclusiveCount !== 0)) {
    throw new RunResponseContractError(context, 'VIOLATED contradicts per-specification results')
  }
  if (outcome === 'INCONCLUSIVE' && specResults.length > 0 && inconclusiveCount === 0) {
    throw new RunResponseContractError(context, 'INCONCLUSIVE contradicts per-specification results')
  }
}

const validateCompletedVerificationConclusion = (
  task: Record<string, any>,
  context: string,
  requireDetails: boolean
) => {
  if (!['SATISFIED', 'VIOLATED', 'INCONCLUSIVE'].includes(task.outcome)) {
    throw new RunResponseContractError(context, 'outcome is required')
  }
  const modelComplete = requireBoolean(task, 'modelComplete', context)
  const violatedSpecCount = requireInteger(task, 'violatedSpecCount', context)
  const disabledRuleCount = requireInteger(task, 'disabledRuleCount', context)
  const skippedSpecCount = requireInteger(task, 'skippedSpecCount', context)
  validateGenerationIssues(task, context, disabledRuleCount, skippedSpecCount)
  const expectedModelComplete = task.outcome !== 'INCONCLUSIVE'
    && disabledRuleCount === 0
    && skippedSpecCount === 0
  if (modelComplete !== expectedModelComplete) {
    throw new RunResponseContractError(context, 'modelComplete contradicts the conclusion or omissions')
  }
  if (task.outcome === 'SATISFIED' && violatedSpecCount !== 0) {
    throw new RunResponseContractError(context, 'SATISFIED cannot report violated specifications')
  }
  if (task.outcome === 'VIOLATED' && violatedSpecCount === 0) {
    throw new RunResponseContractError(context, 'VIOLATED requires at least one violated specification')
  }
  if (requireDetails) {
    const specResults = validateSpecResults(task, context)
    const countedViolations = specResults.filter(result => result.outcome === 'VIOLATED').length
    if (violatedSpecCount !== countedViolations) {
      throw new RunResponseContractError(context, 'violatedSpecCount must match specResults')
    }
    validateVerificationOutcomeAgainstResults(task.outcome, specResults, context)
    validateStringArray(task, 'checkLogs', context)
    requireString(task, 'nusmvOutput', context)
  }
}

export const validateTaskSubmissionId = (value: unknown, context: string): number => {
  if (!Number.isSafeInteger(value) || Number(value) < 1) {
    throw new RunResponseContractError(context, 'task id must be a positive integer')
  }
  return value as number
}

export const validateTaskProgress = (value: unknown, context: string): number => {
  if (!Number.isSafeInteger(value) || Number(value) < 0 || Number(value) > 100) {
    throw new RunResponseContractError(context, 'progress must be an integer between 0 and 100')
  }
  return value as number
}

export const validateInteractiveOperationStatus = (value: unknown): InteractiveOperationStatus => {
  const context = 'Interactive operation status'
  const status = requireRecord(value, context)
  requireString(status, 'requestId', context, false)
  if (!['WAITING', 'RUNNING', 'FINISHED'].includes(status.state)) {
    throw new RunResponseContractError(context, 'state is invalid')
  }
  if (!INTERACTIVE_OPERATION_STAGES.has(status.stage)) {
    throw new RunResponseContractError(context, 'stage is invalid')
  }
  requireInteger(status, 'elapsedMs', context)
  return status as unknown as InteractiveOperationStatus
}

export const validateTaskCancellationResult = (
  value: unknown,
  expectedTaskId: number,
  context: string
): TaskCancellationResult => {
  const result = requireRecord(value, context)
  const taskId = requireInteger(result, 'taskId', context, 1)
  if (taskId !== expectedTaskId) {
    throw new RunResponseContractError(context, 'taskId does not match the cancellation request')
  }
  const cancellationAccepted = requireBoolean(result, 'cancellationAccepted', context)
  const executionMayStillBeStopping = requireBoolean(result, 'executionMayStillBeStopping', context)
  const outcomes = new Set<TaskCancellationOutcome>([
    'ACCEPTED',
    'ALREADY_CANCELLED',
    'ALREADY_COMPLETED',
    'ALREADY_FAILED',
    'NO_LONGER_CANCELLABLE'
  ])
  if (!outcomes.has(result.cancellationOutcome)) {
    throw new RunResponseContractError(context, 'cancellationOutcome is invalid')
  }
  if (!TASK_STATUSES.has(result.taskStatus)) {
    throw new RunResponseContractError(context, 'taskStatus is invalid')
  }
  if (cancellationAccepted !== (result.cancellationOutcome === 'ACCEPTED')) {
    throw new RunResponseContractError(context, 'cancellationAccepted contradicts cancellationOutcome')
  }
  const expectedStatuses: Partial<Record<TaskCancellationOutcome, AsyncTaskStatus>> = {
    ACCEPTED: 'CANCELLED',
    ALREADY_CANCELLED: 'CANCELLED',
    ALREADY_COMPLETED: 'COMPLETED',
    ALREADY_FAILED: 'FAILED'
  }
  const expectedStatus = expectedStatuses[result.cancellationOutcome as TaskCancellationOutcome]
  if (expectedStatus && result.taskStatus !== expectedStatus) {
    throw new RunResponseContractError(context, 'taskStatus contradicts cancellationOutcome')
  }
  if (executionMayStillBeStopping
      && !['ACCEPTED', 'ALREADY_CANCELLED'].includes(result.cancellationOutcome)) {
    throw new RunResponseContractError(context, 'executionMayStillBeStopping contradicts cancellationOutcome')
  }
  return result as TaskCancellationResult
}

const requireFrozenSpecificationName = (value: unknown, context: string): void => {
  const spec = requireRecord(value, context, 'violatedSpec')
  if (typeof spec.name !== 'string' || !spec.name.trim() || spec.name.length > 100) {
    throw new RunResponseContractError(context, 'frozen specification name is required')
  }
}

const validateVerificationTraceShape = (
  value: unknown,
  context: string
): Record<string, any> => {
  const trace = requireRecord(value, context)
  requireString(trace, 'violatedSpecId', context, false)
  requireFrozenSpecificationName(trace.violatedSpec, context)
  /*
   * Nullable, and the server has always been free to send it so.
   *
   * `TraceDto.checkedExpression` is a plain `String` with no `@NotBlank`, over a nullable `TEXT` column — so a
   * trace can legitimately carry none. Requiring it non-blank here was not a per-row problem: the caller at
   * `:534` is a `value.map(...)`, so **one** such row threw and discarded the user's entire verification history
   * list. The consumer already guards with `v-if="currentTrace?.checkedExpression"` (`Board.vue:17645`), so the
   * UI never needed the guarantee this was enforcing.
   *
   * Same defect and same shape as `ruleString` and `conflictingRuleString` in `fixResponse.ts`, both of which
   * rejected a null the backend can produce and turned a fix request into "malformed result". Present-but-wrong
   * type is still a contract breach and still rejected.
   */
  if (trace.checkedExpression !== null && trace.checkedExpression !== undefined
    && typeof trace.checkedExpression !== 'string') {
    throw new RunResponseContractError(context, 'checkedExpression must be text when present')
  }
  validateStateList(trace.states, context)
  const disabledRuleCount = requireInteger(trace, 'disabledRuleCount', context)
  const skippedSpecCount = requireInteger(trace, 'skippedSpecCount', context)
  const modelComplete = requireBoolean(trace, 'modelComplete', context)
  validateGenerationIssues(trace, context, disabledRuleCount, skippedSpecCount)
  if (modelComplete && (disabledRuleCount !== 0 || skippedSpecCount !== 0)) {
    throw new RunResponseContractError(context, 'modelComplete contradicts generation omissions')
  }
  requireAttackContext(trace, context)
  validateModelPlaybackScene(
    trace.playbackScene,
    trace.modelSnapshot.deviceCount,
    trace.modelSnapshot.ruleCount,
    detail => { throw new RunResponseContractError(context, detail) }
  )
  if (trace.modelSnapshot.specificationCount < 1) {
    throw new RunResponseContractError(context, 'modelSnapshot must include at least one specification')
  }
  requireString(trace, 'createdAt', context, false)
  return trace
}

export const validateVerificationTrace = (
  value: unknown,
  context = 'Verification trace',
  expectedVerificationTaskId?: number
): PersistedTrace => {
  const trace = validateVerificationTraceShape(value, context)
  requireInteger(trace, 'id', context, 1)
  const verificationTaskId = requireInteger(trace, 'verificationTaskId', context, 1)
  if (expectedVerificationTaskId !== undefined && verificationTaskId !== expectedVerificationTaskId) {
    throw new RunResponseContractError(context, 'verificationTaskId does not match the owning run')
  }
  return trace as PersistedTrace
}

const validateImmediateVerificationTrace = (
  value: unknown,
  context: string
): ImmediateTrace => {
  const trace = validateVerificationTraceShape(value, context)
  if (trace.id !== undefined || trace.verificationTaskId !== undefined) {
    throw new RunResponseContractError(context, 'unconfirmed history cannot expose trace persistence identity')
  }
  return trace as ImmediateTrace
}

export const hasPersistedVerificationTrace = (
  result: Pick<VerificationResult, 'historyPersistence'> | null | undefined,
  trace: Trace | null | undefined
): trace is PersistedTrace => {
  const runId = result?.historyPersistence.status === 'SAVED'
    ? result.historyPersistence.runId
    : undefined
  return Number.isSafeInteger(runId)
    && Number(runId) >= 1
    && Number.isSafeInteger(trace?.id)
    && Number(trace?.id) >= 1
    && Number.isSafeInteger(trace?.verificationTaskId)
    && trace?.verificationTaskId === runId
}

export const validateVerificationTraceList = (
  value: unknown,
  expectedVerificationTaskId?: number
): PersistedTrace[] => {
  if (!Array.isArray(value)) {
    throw new RunResponseContractError('Verification trace history', 'result must be an array')
  }
  return value.map(trace => validateVerificationTrace(
    trace,
    'Verification trace history',
    expectedVerificationTaskId
  ))
}

export const validateVerificationResult = (value: unknown): VerificationResult => {
  const context = 'Verification'
  const result = requireRecord(value, context)
  const persistence = validateRunPersistence(result.historyPersistence, context,
    new Set<RunPersistenceStatus>(['SAVED', 'FAILED', 'OUTCOME_UNKNOWN']))
  requireAttackContext(result, context)
  if (result.modelSnapshot.specificationCount < 1) {
    throw new RunResponseContractError(context, 'modelSnapshot must include at least one specification')
  }
  if (!['SATISFIED', 'VIOLATED', 'INCONCLUSIVE'].includes(result.outcome)) {
    throw new RunResponseContractError(context, 'outcome is required')
  }
  const modelComplete = requireBoolean(result, 'modelComplete', context)
  const disabledRuleCount = requireInteger(result, 'disabledRuleCount', context)
  const skippedSpecCount = requireInteger(result, 'skippedSpecCount', context)
  validateGenerationIssues(result, context, disabledRuleCount, skippedSpecCount)
  const expectedModelComplete = result.outcome !== 'INCONCLUSIVE'
    && disabledRuleCount === 0
    && skippedSpecCount === 0
  if (modelComplete !== expectedModelComplete) {
    throw new RunResponseContractError(context, 'modelComplete contradicts the conclusion or omissions')
  }
  const traces = requireArray(result, 'traces', context)
  if (persistence.status === 'SAVED') {
    traces.forEach(trace => validateVerificationTrace(trace, context, persistence.runId))
  } else {
    traces.forEach(trace => validateImmediateVerificationTrace(trace, context))
  }
  const specResults = validateSpecResults(result, context)
  validateVerificationOutcomeAgainstResults(result.outcome, specResults, context)
  validateStringArray(result, 'checkLogs', context)
  requireString(result, 'nusmvOutput', context)
  return result as VerificationResult
}

export type CompletedVerificationTask = VerificationTask & Required<Pick<
  VerificationTask,
  | 'outcome'
  | 'modelComplete'
  | 'violatedSpecCount'
  | 'disabledRuleCount'
  | 'skippedSpecCount'
  | 'generationIssues'
  | 'specResults'
  | 'checkLogs'
  | 'nusmvOutput'
>>

export const validateVerificationTask = (value: unknown): VerificationTask => {
  const context = 'Verification task'
  const task = validateTaskBase(value, context)
  if (task.modelSnapshot.specificationCount < 1) {
    throw new RunResponseContractError(context, 'modelSnapshot must include at least one specification')
  }
  if (task.status === 'COMPLETED') {
    validateCompletedVerificationConclusion(task, context, true)
  } else if (task.status === 'PENDING' || task.status === 'RUNNING') {
    if (task.outcome !== undefined || task.modelComplete !== undefined) {
      throw new RunResponseContractError(context, 'an active task cannot report a conclusion')
    }
  } else {
    if (task.outcome !== 'INCONCLUSIVE' || task.modelComplete !== false) {
      throw new RunResponseContractError(context, 'a failed or cancelled task must be inconclusive')
    }
  }
  return task as VerificationTask
}

// Not exported: the list validator below is the module's public surface, and this is its element step.
// An exported symbol with no importer reads as public API and invites a second entry point past the
// list-level checks.
const validateVerificationTaskSummary = (value: unknown): VerificationTaskSummary => {
  const context = 'Verification task summary'
  const task = validateTaskBase(value, context)
  if (task.modelSnapshot.specificationCount < 1) {
    throw new RunResponseContractError(context, 'modelSnapshot must include at least one specification')
  }
  if (task.status === 'COMPLETED') {
    validateCompletedVerificationConclusion(task, context, false)
  } else if (task.status === 'PENDING' || task.status === 'RUNNING') {
    if (task.outcome !== undefined || task.modelComplete !== undefined) {
      throw new RunResponseContractError(context, 'an active task cannot report a conclusion')
    }
  } else if (task.outcome !== 'INCONCLUSIVE' || task.modelComplete !== false) {
    throw new RunResponseContractError(context, 'a failed or cancelled task must be inconclusive')
  }
  return task as VerificationTaskSummary
}

export const validateVerificationTaskSummaryList = (value: unknown): VerificationTaskSummary[] => {
  if (!Array.isArray(value)) {
    throw new RunResponseContractError('Verification task inbox', 'result must be an array')
  }
  return value.map(validateVerificationTaskSummary)
}

const validateVerificationRunBase = (
  value: unknown,
  context: string,
  requireDetails: boolean
): Record<string, any> => {
  const run = requireRecord(value, context)
  requireInteger(run, 'id', context, 1)
  requireRunInitiator(run, context)
  requireString(run, 'createdAt', context, false)
  requireString(run, 'startedAt', context, false)
  requireString(run, 'completedAt', context, false)
  if (run.processingTimeMs !== undefined && run.processingTimeMs !== null) {
    requireInteger(run, 'processingTimeMs', context)
  }
  requireAttackContext(run, context)
  if (run.modelSnapshot.specificationCount < 1) {
    throw new RunResponseContractError(context, 'modelSnapshot must include at least one specification')
  }
  validateCompletedVerificationConclusion(run, context, requireDetails)
  requireInteger(run, 'counterexampleCount', context)
  return run
}

const validateTraceSummary = (value: unknown, context: string): TraceSummary => {
  const trace = requireRecord(value, context)
  requireInteger(trace, 'id', context, 1)
  requireInteger(trace, 'verificationTaskId', context, 1)
  const dataAvailable = requireBoolean(trace, 'dataAvailable', context)
  requireOptionalTimestamp(trace, 'createdAt', context)
  if (!dataAvailable) {
    requireString(trace, 'unavailableReasonCode', context, false)
    return trace as TraceSummary
  }
  requireString(trace, 'violatedSpecId', context, false)
  requireFrozenSpecificationName(trace.violatedSpec, context)
  const sources = requireRecord(trace.modelTokenSourcesByDeviceId, context, 'modelTokenSourcesByDeviceId')
  if (Object.keys(sources).length > 100
    || Object.entries(sources).some(([id, source]) => !id.trim() || !MODEL_TOKEN_SOURCE_SET.has(String(source)))) {
    throw new RunResponseContractError(context, 'modelTokenSourcesByDeviceId is invalid')
  }
  requireInteger(trace, 'stateCount', context, 1)
  requireString(trace, 'createdAt', context, false)
  return trace as TraceSummary
}

export const validateVerificationRunSummary = (value: unknown): VerificationRunSummary => {
  const context = 'Verification run summary'
  const candidate = requireRecord(value, context)
  requireInteger(candidate, 'id', context, 1)
  requireRunInitiator(candidate, context)
  const dataAvailable = requireBoolean(candidate, 'dataAvailable', context)
  requireOptionalTimestamp(candidate, 'createdAt', context)
  requireOptionalTimestamp(candidate, 'startedAt', context)
  requireOptionalTimestamp(candidate, 'completedAt', context)
  validateRunCounterexamples(candidate, context)
  if (!dataAvailable) {
    requireString(candidate, 'unavailableReasonCode', context, false)
    return candidate as VerificationRunSummary
  }
  return validateVerificationRunBase(candidate, context, false) as VerificationRunSummary
}

const validateRunCounterexamples = (candidate: Record<string, any>, context: string): void => {
  const counterexamples = requireArray(candidate, 'counterexamples', context)
    .map((trace, index) => validateTraceSummary(trace, `${context} counterexamples[${index}]`))
  if (counterexamples.some(trace => trace.verificationTaskId !== candidate.id)) {
    throw new RunResponseContractError(context, 'counterexamples must belong to this verification run')
  }
  const counterexampleCount = requireInteger(candidate, 'counterexampleCount', context)
  const availableCounterexampleCount = counterexamples
    .filter(trace => trace.dataAvailable !== false).length
  if (counterexampleCount !== availableCounterexampleCount) {
    throw new RunResponseContractError(
      context,
      'counterexampleCount must match available counterexamples'
    )
  }
}

export const validateVerificationRunSummaryList = (value: unknown): VerificationRunSummary[] => {
  if (!Array.isArray(value)) {
    throw new RunResponseContractError('Verification run history', 'result must be an array')
  }
  return value.map(validateVerificationRunSummary)
}

export const validateVerificationRun = (value: unknown): VerificationRun => {
  const context = 'Verification run'
  const run = validateVerificationRunBase(value, context, true)
  validateRunCounterexamples(run, context)
  return run as VerificationRun
}

export const validateCompletedVerificationTask = (
  value: VerificationTask
): CompletedVerificationTask => {
  const task = validateVerificationTask(value)
  if (task.status !== 'COMPLETED') {
    throw new RunResponseContractError('Completed verification task', 'status must be COMPLETED')
  }
  return task as CompletedVerificationTask
}

const validateSimulationShape = (
  value: unknown,
  context: string,
  requirePersistedId: boolean
): Record<string, any> => {
  const result = requireRecord(value, context)
  const persistence = validateRunPersistence(result.historyPersistence, context,
    requirePersistedId
      ? new Set<RunPersistenceStatus>(['SAVED', 'FAILED', 'OUTCOME_UNKNOWN'])
      : new Set<RunPersistenceStatus>(['NOT_REQUESTED']))
  if (requirePersistedId && persistence.status === 'SAVED') {
    const id = requireInteger(result, 'id', context, 1)
    if (persistence.runId !== id) {
      throw new RunResponseContractError(context, 'historyPersistence.runId must match id')
    }
  } else if (requirePersistedId && result.id !== undefined && result.id !== null) {
    throw new RunResponseContractError(context, 'an unconfirmed history result cannot claim a persisted id')
  }
  requireAttackContext(result, context)
  validateModelPlaybackScene(
    result.playbackScene,
    result.modelSnapshot.deviceCount,
    result.modelSnapshot.ruleCount,
    detail => { throw new RunResponseContractError(context, detail) }
  )
  if (result.modelSnapshot.specificationCount !== 0) {
    throw new RunResponseContractError(context, 'simulation modelSnapshot specificationCount must be 0')
  }
  const modelComplete = requireBoolean(result, 'modelComplete', context)
  const disabledRuleCount = requireInteger(result, 'disabledRuleCount', context)
  validateGenerationIssues(result, context, disabledRuleCount)
  if (modelComplete !== (disabledRuleCount === 0)) {
    throw new RunResponseContractError(context, 'modelComplete contradicts disabled rules')
  }
  const states = validateStateList(result.states, context)
  const steps = requireInteger(result, 'steps', context)
  const requestedSteps = requireInteger(result, 'requestedSteps', context, 1)
  if (steps !== states.length - 1 || steps > requestedSteps) {
    throw new RunResponseContractError(context, 'steps must match states and not exceed requestedSteps')
  }
  validateStringArray(result, 'logs', context)
  requireString(result, 'nusmvOutput', context)
  return result
}

export const validateSimulationResult = (value: unknown): SimulationResult =>
  validateSimulationShape(value, 'Simulation', false) as unknown as SimulationResult

export const validateSimulationTrace = (value: unknown): SimulationTrace => {
  const result = validateSimulationShape(value, 'Simulation trace', true)
  requireRunInitiator(result, 'Simulation trace')
  requireString(result, 'createdAt', 'Simulation trace', false)
  return result as unknown as SimulationTrace
}

export const validateSimulationTraceSummary = (value: unknown): SimulationTraceSummary => {
  const context = 'Simulation history summary'
  const result = requireRecord(value, context)
  requireInteger(result, 'id', context, 1)
  requireRunInitiator(result, context)
  const dataAvailable = requireBoolean(result, 'dataAvailable', context)
  requireOptionalTimestamp(result, 'createdAt', context)
  if (!dataAvailable) {
    requireString(result, 'unavailableReasonCode', context, false)
    return result as SimulationTraceSummary
  }
  const requestedSteps = requireInteger(result, 'requestedSteps', context, 1)
  const steps = requireInteger(result, 'steps', context)
  if (steps > requestedSteps) {
    throw new RunResponseContractError(context, 'steps cannot exceed requestedSteps')
  }
  const modelComplete = requireBoolean(result, 'modelComplete', context)
  const disabledRuleCount = requireInteger(result, 'disabledRuleCount', context)
  validateGenerationIssues(result, context, disabledRuleCount)
  if (modelComplete !== (disabledRuleCount === 0)) {
    throw new RunResponseContractError(context, 'modelComplete contradicts disabled rules')
  }
  const isAttack = requireBoolean(result, 'isAttack', context)
  const attackBudget = requireInteger(result, 'attackBudget', context)
  if ((!isAttack && attackBudget !== 0) || (isAttack && attackBudget < 1)) {
    throw new RunResponseContractError(context, 'attackBudget does not match isAttack')
  }
  requireBoolean(result, 'enablePrivacy', context)
  validateModelSnapshot(result, context)
  if (result.modelSnapshot.specificationCount !== 0) {
    throw new RunResponseContractError(context, 'simulation history modelSnapshot specificationCount must be 0')
  }
  requireString(result, 'createdAt', context, false)
  return result as SimulationTraceSummary
}

export const validateSimulationTraceSummaryList = (value: unknown): SimulationTraceSummary[] => {
  if (!Array.isArray(value)) {
    throw new RunResponseContractError('Simulation history', 'result must be an array')
  }
  return value.map(validateSimulationTraceSummary)
}

const validateSimulationTaskShape = (
  value: unknown,
  context: string,
  requireDetails: boolean
): Record<string, any> => {
  const task = validateTaskBase(value, context)
  if (task.modelSnapshot.specificationCount !== 0) {
    throw new RunResponseContractError(context, 'simulation task modelSnapshot specificationCount must be 0')
  }
  const requestedSteps = requireInteger(task, 'requestedSteps', context, 1)
  if (task.steps !== undefined && task.steps !== null) {
    const steps = requireInteger(task, 'steps', context)
    if (steps > requestedSteps) {
      throw new RunResponseContractError(context, 'steps cannot exceed requestedSteps')
    }
  }
  if (task.status === 'COMPLETED') {
    requireInteger(task, 'steps', context)
    requireInteger(task, 'simulationTraceId', context, 1)
    const modelComplete = requireBoolean(task, 'modelComplete', context)
    const disabledRuleCount = requireInteger(task, 'disabledRuleCount', context)
    validateGenerationIssues(task, context, disabledRuleCount)
    if (modelComplete !== (disabledRuleCount === 0)) {
      throw new RunResponseContractError(context, 'modelComplete contradicts disabled rules')
    }
    if (requireDetails) {
      validateStringArray(task, 'checkLogs', context)
    }
  } else {
    if (task.modelComplete !== undefined || task.simulationTraceId !== undefined) {
      throw new RunResponseContractError(context, 'a non-completed task cannot report a saved result')
    }
    if (task.disabledRuleCount !== undefined || task.generationIssues !== undefined) {
      const disabledRuleCount = requireInteger(task, 'disabledRuleCount', context)
      validateGenerationIssues(task, context, disabledRuleCount)
    }
  }
  return task
}

export const validateSimulationTask = (value: unknown): SimulationTask =>
  validateSimulationTaskShape(value, 'Simulation task', true) as SimulationTask

// Not exported, for the same reason as `validateVerificationTaskSummary` above.
const validateSimulationTaskSummary = (value: unknown): SimulationTaskSummary =>
  validateSimulationTaskShape(value, 'Simulation task summary', false) as SimulationTaskSummary

export const validateSimulationTaskSummaryList = (value: unknown): SimulationTaskSummary[] => {
  if (!Array.isArray(value)) {
    throw new RunResponseContractError('Simulation task inbox', 'result must be an array')
  }
  return value.map(validateSimulationTaskSummary)
}
