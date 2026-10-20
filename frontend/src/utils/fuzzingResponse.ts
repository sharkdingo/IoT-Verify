import type {
  AvailableFuzzingRunSummary,
  FuzzingEligibility,
  FuzzingFinding,
  FuzzingFindingReplay,
  FuzzingModelRunSnapshot,
  FuzzPaperDomainPreview,
  FuzzWorkloadPreview,
  FuzzingRun,
  FuzzingRunSummary,
  FuzzingTask,
  FuzzingTaskSummary
} from '@/types/fuzzing'
import {
  FUZZ_BASE_LIMITATION_CODES,
  FUZZ_PAPER_SEMANTICS_CODES,
  FUZZING_EXPLORATION_MODES,
  isValidFuzzPaperDomainFingerprint
} from '@/types/fuzzing'
import type { AsyncTaskStatus, TaskProgressStage } from '@/types/task'
import { validateTraceStatePayload } from './traceStateResponse'
import { isRunInitiator, type ModelPlaybackScene } from '@/types/model'
import { MODEL_TOKEN_SOURCE_SET } from '@/types/modelToken'
import { normalizeNuSmvDeviceName } from './modelRequest'
import { validateModelPlaybackScene } from './playbackSceneResponse'

export const FUZZ_RESPONSE_INCOMPLETE_CODE = 'FUZZ_RESPONSE_INCOMPLETE'
const FUZZ_ACTIVE_TASK_LIMIT_REACHED_CODE = 'FUZZ_ACTIVE_TASK_LIMIT_REACHED'
const FUZZ_STORED_TASK_LIMIT_REACHED_CODE = 'FUZZ_STORED_TASK_LIMIT_REACHED'

export interface FuzzActiveTaskLimit {
  activeTaskCount?: number
  maxActiveTasksPerUser?: number
}

export const getFuzzActiveTaskLimit = (error: any): FuzzActiveTaskLimit | null => {
  const data = error?.response?.data?.data
  if (Number(error?.response?.status) !== 429
    || data?.reasonCode !== FUZZ_ACTIVE_TASK_LIMIT_REACHED_CODE) return null
  const activeTaskCount = Number(data.activeTaskCount)
  const maxActiveTasksPerUser = Number(data.maxActiveTasksPerUser)
  if (!Number.isSafeInteger(activeTaskCount) || activeTaskCount < 0
    || !Number.isSafeInteger(maxActiveTasksPerUser) || maxActiveTasksPerUser < 1) return {}
  return { activeTaskCount, maxActiveTasksPerUser }
}

export interface FuzzStoredTaskLimit {
  storedTaskCount?: number
  maxStoredTasksPerUser?: number
}

export const getFuzzStoredTaskLimit = (error: any): FuzzStoredTaskLimit | null => {
  const data = error?.response?.data?.data
  if (Number(error?.response?.status) !== 429
    || data?.reasonCode !== FUZZ_STORED_TASK_LIMIT_REACHED_CODE) return null
  const storedTaskCount = Number(data.storedTaskCount)
  const maxStoredTasksPerUser = Number(data.maxStoredTasksPerUser)
  if (!Number.isSafeInteger(storedTaskCount) || storedTaskCount < 0
    || !Number.isSafeInteger(maxStoredTasksPerUser) || maxStoredTasksPerUser < 1) return {}
  return { storedTaskCount, maxStoredTasksPerUser }
}

export class FuzzResponseContractError extends Error {
  readonly code = FUZZ_RESPONSE_INCOMPLETE_CODE

  constructor(context: string, detail: string) {
    super(`${context} returned an incomplete result: ${detail}`)
    this.name = 'FuzzResponseContractError'
  }
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

const OUTCOMES = new Set(['FOUND_VIOLATION', 'BUDGET_EXHAUSTED', 'INCONCLUSIVE'])
const INPUT_EVENT_KINDS = new Set([
  'DEVICE_STATE',
  'DEVICE_VARIABLE',
  'ENVIRONMENT_VALUE',
  'ENVIRONMENT_RATE'
])
const INPUT_EVENT_SOURCES = new Set(['MODEL_CHOICE', 'RANDOM_INITIAL_STATE', 'SEED_EVENT'])
const INPUT_EVENT_SOURCE_ORDER: Record<string, number> = {
  RANDOM_INITIAL_STATE: 0,
  SEED_EVENT: 1,
  MODEL_CHOICE: 2
}
const PAPER_EVENT_VALUE_KINDS = new Set(['CHANGE_RATE', 'DIRECT_VALUE_EXTENSION'])
const EXPLORATION_MODES = new Set<string>(FUZZING_EXPLORATION_MODES)

const record = (value: unknown, context: string): Record<string, any> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new FuzzResponseContractError(context, 'result must be an object')
  }
  return value as Record<string, any>
}

const integer = (
  value: unknown,
  field: string,
  context: string,
  minimum = 0
): number => {
  if (!Number.isSafeInteger(value) || Number(value) < minimum) {
    throw new FuzzResponseContractError(context, `${field} must be an integer >= ${minimum}`)
  }
  return Number(value)
}

const safeIntegerProduct = (
  values: number[],
  field: string,
  context: string
): number => {
  let product = 1
  for (const value of values) {
    product *= value
    if (!Number.isSafeInteger(product)) {
      throw new FuzzResponseContractError(context, `${field} must remain a safe integer`)
    }
  }
  return product
}

export const assertFuzzingFindingBelongsToRun = (
  finding: Pick<FuzzingFinding, 'fuzzTaskId'>,
  runId: number,
  context = 'Fuzz finding'
): void => {
  const ownerId = integer(finding.fuzzTaskId, 'fuzzTaskId', context, 1)
  const expectedRunId = integer(runId, 'runId', context, 1)
  if (ownerId !== expectedRunId) {
    throw new FuzzResponseContractError(context, 'finding fuzzTaskId must match the owning run')
  }
}

export const resolveFuzzingRunFinding = (
  run: Pick<FuzzingRun, 'id' | 'findings'>,
  findingId: number,
  context = 'Fuzz finding replay'
): FuzzingFinding => {
  const expectedFindingId = integer(findingId, 'findingId', context, 1)
  const finding = run.findings.find(candidate => candidate.id === expectedFindingId)
  if (!finding) {
    throw new FuzzResponseContractError(
      context,
      'the owning run does not contain the requested finding evidence'
    )
  }
  assertFuzzingFindingBelongsToRun(finding, run.id, context)
  return finding
}

const text = (value: unknown, field: string, context: string, allowEmpty = false): string => {
  if (typeof value !== 'string' || (!allowEmpty && !value.trim())) {
    throw new FuzzResponseContractError(context, `${field} must be text`)
  }
  return value
}

const optionalText = (value: unknown, field: string, context: string) => {
  if (value === undefined || value === null) return
  text(value, field, context, true)
}

const validateExplorationMode = (value: unknown, context: string) => {
  if (typeof value !== 'string' || !EXPLORATION_MODES.has(value)) {
    throw new FuzzResponseContractError(context, 'explorationMode is invalid')
  }
}

const stringArray = (value: unknown, field: string, context: string): string[] => {
  if (!Array.isArray(value) || value.some(item => typeof item !== 'string')) {
    throw new FuzzResponseContractError(context, `${field} must be a string array`)
  }
  return value
}

const optionalIntegerRange = (
  lower: unknown,
  upper: unknown,
  field: string,
  context: string
): boolean => {
  const absent = (lower === undefined || lower === null)
    && (upper === undefined || upper === null)
  if (absent) return false
  if (!Number.isSafeInteger(lower) || !Number.isSafeInteger(upper)
    || Number(lower) > Number(upper)) {
    throw new FuzzResponseContractError(context, `${field} must contain ordered integer bounds`)
  }
  return true
}

const stableCodeArray = (
  value: unknown,
  field: string,
  context: string,
  requiredCodes: readonly string[]
): string[] => {
  const codes = stringArray(value, field, context)
  if (codes.some(code => !code.trim()) || new Set(codes).size !== codes.length) {
    throw new FuzzResponseContractError(context, `${field} must contain unique non-blank codes`)
  }
  const missingCodes = requiredCodes.filter(code => !codes.includes(code))
  if (missingCodes.length > 0) {
    throw new FuzzResponseContractError(context, `${field} is missing required codes`)
  }
  return codes
}

const validatePaperDeviceDomains = (
  value: unknown,
  field: 'deviceDomains' | 'localVariableDomains',
  context: string
) => {
  if (!Array.isArray(value)) {
    throw new FuzzResponseContractError(context, `${field} must be an array`)
  }
  const identities = new Set<string>()
  value.forEach((item: unknown, index: number) => {
    const domain = record(item, `${context} ${field}[${index}]`)
    const targetId = text(domain.targetId, 'targetId', context)
    text(domain.label, 'label', context)
    const property = text(domain.property, 'property', context)
    const values = stringArray(domain.legalValues, 'legalValues', context)
    const numericRange = optionalIntegerRange(
      domain.lowerBound, domain.upperBound, `${field} numeric range`, context)
    if (values.some(item => !item.trim()) || new Set(values).size !== values.length
      || (field === 'deviceDomains' && (values.length === 0 || numericRange))
      || (field === 'localVariableDomains' && ((values.length > 0) === numericRange))) {
      throw new FuzzResponseContractError(context, `${field} legalValues must be unique non-empty text`)
    }
    const identity = `${targetId}\u0000${property}`
    if (identities.has(identity)) {
      throw new FuzzResponseContractError(context, `${field} target/property pairs must be unique`)
    }
    identities.add(identity)
  })
}

const validateSnapshot = (value: unknown, context: string) => {
  const snapshot = record(value, `${context} modelSnapshot`)
  text(snapshot.capturedAt, 'capturedAt', context)
  const deviceCount = integer(snapshot.deviceCount, 'deviceCount', context)
  integer(snapshot.ruleCount, 'ruleCount', context)
  integer(snapshot.specificationCount, 'specificationCount', context)
  integer(snapshot.environmentVariableCount, 'environmentVariableCount', context)
  const deviceTemplateCount = integer(
    snapshot.deviceTemplateCount,
    'deviceTemplateCount',
    context
  )
  if (deviceTemplateCount > deviceCount) {
    throw new FuzzResponseContractError(
      context,
      'deviceTemplateCount cannot exceed deviceCount'
    )
  }
  if (snapshot.templatesFrozen !== true) {
    throw new FuzzResponseContractError(context, 'modelSnapshot.templatesFrozen must be true')
  }
  if (typeof snapshot.modelFingerprint !== 'string'
    || !/^[0-9a-f]{64}$/.test(snapshot.modelFingerprint)) {
    throw new FuzzResponseContractError(context, 'modelSnapshot.modelFingerprint must be a SHA-256 value')
  }
}

const validateTraceStateList = (value: unknown, context: string) => {
  validateTraceStatePayload(value, {
    firstStateIndex: 0,
    fail: detail => {
      throw new FuzzResponseContractError(context, detail)
    }
  })
  const states = value as Array<Record<string, any>>
  const rejectEvidence = (path: string) => {
    throw new FuzzResponseContractError(
      context,
      `${path} must not contain trust/privacy evidence because bounded exploration does not model label propagation`
    )
  }
  const rejectVariableTrust = (variables: Array<Record<string, any>>, path: string) => {
    variables.forEach((variable, index) => {
      if (variable.trust !== undefined && variable.trust !== null) {
        rejectEvidence(`${path}[${index}].trust`)
      }
    })
  }
  states.forEach((state, stateIndex) => {
    const statePath = `states[${stateIndex}]`
    if ((state.trustPrivacies || []).length > 0) rejectEvidence(`${statePath}.trustPrivacies`)
    rejectVariableTrust(state.envVariables || [], `${statePath}.envVariables`)
    rejectVariableTrust(state.globalVariables || [], `${statePath}.globalVariables`)
    state.devices.forEach((device: Record<string, any>, deviceIndex: number) => {
      const devicePath = `${statePath}.devices[${deviceIndex}]`
      if ((device.trustPrivacy || []).length > 0) rejectEvidence(`${devicePath}.trustPrivacy`)
      if ((device.privacies || []).length > 0) rejectEvidence(`${devicePath}.privacies`)
      rejectVariableTrust(device.variables, `${devicePath}.variables`)
    })
  })
}

const validateEligibility = (value: unknown, context: string): FuzzingEligibility => {
  const eligibility = record(value, `${context} eligibility`)
  const eligibleSpecIds = stringArray(eligibility.eligibleSpecIds, 'eligibleSpecIds', context)
  if (eligibleSpecIds.some(id => !id.trim()) || new Set(eligibleSpecIds).size !== eligibleSpecIds.length) {
    throw new FuzzResponseContractError(context, 'eligibility.eligibleSpecIds must be unique and non-blank')
  }
  const eligibleSpecLabels = record(eligibility.eligibleSpecLabels, `${context} eligibility.eligibleSpecLabels`)
  const labeledSpecIds = Object.keys(eligibleSpecLabels)
  if (labeledSpecIds.length !== eligibleSpecIds.length
    || labeledSpecIds.some(id => !eligibleSpecIds.includes(id))) {
    throw new FuzzResponseContractError(context, 'eligibility.eligibleSpecLabels must label every eligible specification')
  }
  eligibleSpecIds.forEach(id => text(eligibleSpecLabels[id], `eligibleSpecLabels.${id}`, context))
  if (!Array.isArray(eligibility.ineligibleSpecs)) {
    throw new FuzzResponseContractError(context, 'eligibility.ineligibleSpecs must be an array')
  }
  const ineligibleSpecIds = eligibility.ineligibleSpecs.map((item: unknown, index: number) => {
    const issue = record(item, `${context} eligibility.ineligibleSpecs[${index}]`)
    const specId = text(issue.specId, 'specId', context)
    optionalText(issue.specificationLabel, 'specificationLabel', context)
    text(issue.reasonCode, 'reasonCode', context)
    text(issue.reason, 'reason', context)
    return specId
  })
  if (new Set(ineligibleSpecIds).size !== ineligibleSpecIds.length
    || ineligibleSpecIds.some((id: string) => eligibleSpecIds.includes(id))) {
    throw new FuzzResponseContractError(context, 'eligible and ineligible specification IDs must be unique and disjoint')
  }
  const requestedSpecCount = integer(eligibility.requestedSpecCount, 'requestedSpecCount', context)
  const eligibleSpecCount = integer(eligibility.eligibleSpecCount, 'eligibleSpecCount', context)
  if (eligibleSpecCount !== eligibleSpecIds.length
    || requestedSpecCount !== eligibleSpecIds.length + ineligibleSpecIds.length) {
    throw new FuzzResponseContractError(context, 'eligibility counts must match the itemized specifications')
  }
  return eligibility as FuzzingEligibility
}

const validateTask = (value: unknown, context: string): FuzzingTask => {
  const task = record(value, context)
  integer(task.id, 'id', context, 1)
  if (!isRunInitiator(task.initiator)) {
    throw new FuzzResponseContractError(context, 'initiator is invalid')
  }
  validateExplorationMode(task.explorationMode, context)
  if (!TASK_STATUSES.has(task.status)) {
    throw new FuzzResponseContractError(context, 'status is invalid')
  }
  text(task.createdAt, 'createdAt', context)
  optionalText(task.startedAt, 'startedAt', context)
  optionalText(task.completedAt, 'completedAt', context)
  optionalText(task.errorMessage, 'errorMessage', context)
  integer(task.progress, 'progress', context)
  if (task.progress > 100) {
    throw new FuzzResponseContractError(context, 'progress must be <= 100')
  }
  if (task.progressStage !== undefined && !TASK_PROGRESS_STAGES.has(task.progressStage)) {
    throw new FuzzResponseContractError(context, 'progressStage is invalid')
  }
  if (task.processingTimeMs !== undefined) integer(task.processingTimeMs, 'processingTimeMs', context)
  if (task.runId !== undefined) integer(task.runId, 'runId', context, 1)
  if (task.outcome !== undefined && !OUTCOMES.has(task.outcome)) {
    throw new FuzzResponseContractError(context, 'outcome is invalid')
  }
  validateSnapshot(task.modelSnapshot, context)
  integer(task.maxIterations, 'maxIterations', context, 1)
  integer(task.pathLength, 'pathLength', context, 1)
  integer(task.populationSize, 'populationSize', context, 1)
  if (task.seed !== undefined) integer(task.seed, 'seed', context)
  stringArray(task.targetSpecIds, 'targetSpecIds', context)
  return task as FuzzingTask
}

const validateRun = (
  value: unknown,
  context: string,
  availabilityRequired = true
): FuzzingRunSummary => {
  const run = record(value, context)
  integer(run.id, 'id', context, 1)
  if (!isRunInitiator(run.initiator)) {
    throw new FuzzResponseContractError(context, 'initiator is invalid')
  }
  if (availabilityRequired && typeof run.dataAvailable !== 'boolean') {
    throw new FuzzResponseContractError(context, 'dataAvailable must be boolean')
  }
  if (run.dataAvailable === false) {
    if (!availabilityRequired) {
      throw new FuzzResponseContractError(context, 'run details cannot be unavailable')
    }
    optionalText(run.createdAt, 'createdAt', context)
    optionalText(run.completedAt, 'completedAt', context)
    if (run.findingCount !== undefined) integer(run.findingCount, 'findingCount', context)
    if (!Array.isArray(run.findings)) {
      throw new FuzzResponseContractError(context, 'findings must be an array')
    }
    if (run.findings.length !== 0) {
      throw new FuzzResponseContractError(context, 'unavailable runs cannot expose findings')
    }
    text(run.unavailableReasonCode, 'unavailableReasonCode', context)
    return run as FuzzingRunSummary
  }
  if (run.dataAvailable !== undefined && run.dataAvailable !== true) {
    throw new FuzzResponseContractError(context, 'dataAvailable must be boolean')
  }
  if (!OUTCOMES.has(run.outcome)) {
    throw new FuzzResponseContractError(context, 'outcome is invalid')
  }
  validateExplorationMode(run.explorationMode, context)
  const effectiveSeed = integer(run.effectiveSeed, 'effectiveSeed', context)
  const iterations = integer(run.iterations, 'iterations', context)
  const generatedPaths = integer(run.generatedPaths, 'generatedPaths', context)
  integer(run.elapsedMs, 'elapsedMs', context)
  validateSnapshot(run.modelSnapshot, context)
  if (!availabilityRequired) {
    validateModelPlaybackScene(
      run.playbackScene,
      run.modelSnapshot.deviceCount,
      run.modelSnapshot.ruleCount,
      detail => { throw new FuzzResponseContractError(context, detail) }
    )
  }
  const eligibility = validateEligibility(run.eligibility, context)
  const specifications = record(run.eligibleSpecifications, `${context} eligibleSpecifications`)
  const sources = record(run.modelTokenSourcesByDeviceId, `${context} modelTokenSourcesByDeviceId`)
  if (Object.keys(specifications).length !== eligibility.eligibleSpecIds.length
    || Object.keys(specifications).some(id => !eligibility.eligibleSpecIds.includes(id))) {
    throw new FuzzResponseContractError(context, 'frozen specifications must match eligible IDs')
  }
  if (Object.keys(sources).length > 100 || Object.keys(sources).length !== run.modelSnapshot.deviceCount
    || Object.entries(sources).some(([id, source]) => !id.trim() || id.length > 100 || !MODEL_TOKEN_SOURCE_SET.has(String(source)))) {
    throw new FuzzResponseContractError(context, 'frozen model token sources must match the device count')
  }
  const deviceIds = new Set(Object.keys(sources).map(normalizeNuSmvDeviceName))
  if (deviceIds.size !== Object.keys(sources).length) {
    throw new FuzzResponseContractError(context, 'frozen model token source identities must be unique')
  }
  for (const [id, value] of Object.entries(specifications)) {
    const spec = record(value, `${context} frozen specification`)
    if (spec.id !== id || !['1', '3', '4'].includes(spec.templateId)) {
      throw new FuzzResponseContractError(context, 'frozen specification identity or template is invalid')
    }
    for (const side of ['a', 'if', 'then']) {
      const conditions = spec[`${side}Conditions`]
      if (!Array.isArray(conditions) || conditions.length > 50
        || (conditions.length > 0) !== (spec.templateId === '4' ? side !== 'a' : side === 'a')) {
        throw new FuzzResponseContractError(context, 'frozen specification condition groups are invalid')
      }
      for (const item of conditions) {
        const condition = record(item, `${context} frozen condition`)
        if (!deviceIds.has(normalizeNuSmvDeviceName(text(condition.deviceId, 'deviceId', context)))) {
          throw new FuzzResponseContractError(context, 'frozen condition must reference a captured device')
        }
        text(condition.targetType, 'targetType', context)
        text(condition.key, 'key', context)
        text(condition.relation, 'relation', context)
        text(condition.value, 'value', context)
      }
    }
  }
  const eligibleSpecIds = new Set(eligibility.eligibleSpecIds)
  if ((run.outcome === 'INCONCLUSIVE') !== (eligibleSpecIds.size === 0)) {
    throw new FuzzResponseContractError(
      context,
      'outcome must agree with whether eligible specifications exist'
    )
  }
  const maxIterations = integer(run.maxIterations, 'maxIterations', context, 1)
  const pathLength = integer(run.pathLength, 'pathLength', context, 1)
  const populationSize = integer(run.populationSize, 'populationSize', context, 1)
  if (iterations > maxIterations) {
    throw new FuzzResponseContractError(context, 'iterations cannot exceed maxIterations')
  }
  const maximumGeneratedPaths = safeIntegerProduct(
    [iterations, populationSize],
    'generatedPaths budget',
    context
  )
  if ((iterations === 0) !== (generatedPaths === 0)
    || (iterations > 0 && generatedPaths < iterations)
    || generatedPaths > maximumGeneratedPaths) {
    throw new FuzzResponseContractError(context, 'execution statistics are inconsistent')
  }
  stableCodeArray(
    run.limitations,
    'limitations',
    context,
    run.explorationMode === 'PAPER_COMPATIBLE'
      ? [...FUZZ_BASE_LIMITATION_CODES, ...FUZZ_PAPER_SEMANTICS_CODES]
      : FUZZ_BASE_LIMITATION_CODES
  )
  if (!Array.isArray(run.findings)) {
    throw new FuzzResponseContractError(context, 'findings must be an array')
  }
  if (availabilityRequired) {
    const findingIds = new Set<number>()
    const findingSpecIds = new Set<string>()
    run.findings.forEach((item: unknown, index: number) => {
      const finding = record(item, `${context} findings[${index}]`)
      const findingId = integer(finding.id, 'id', context, 1)
      if (findingIds.has(findingId)) {
        throw new FuzzResponseContractError(context, 'finding IDs must be unique')
      }
      findingIds.add(findingId)
      assertFuzzingFindingBelongsToRun(finding as Pick<FuzzingFinding, 'fuzzTaskId'>, run.id, context)
      const violatedSpecId = text(finding.violatedSpecId, 'violatedSpecId', context)
      if (!eligibleSpecIds.has(violatedSpecId)) {
        throw new FuzzResponseContractError(context, 'finding must reference an eligible specification')
      }
      if (findingSpecIds.has(violatedSpecId)) {
        throw new FuzzResponseContractError(
          context,
          'findings must contain at most one result per specification'
        )
      }
      findingSpecIds.add(violatedSpecId)
      optionalText(finding.specificationLabel, 'specificationLabel', context)
      if (finding.violatedSpec !== undefined && finding.violatedSpec !== null) {
        const violatedSpec = record(finding.violatedSpec, `${context} finding violatedSpec`)
        if (text(violatedSpec.id, 'violatedSpec.id', context) !== violatedSpecId) {
          throw new FuzzResponseContractError(context, 'finding specification ID must match violatedSpecId')
        }
      }
      const firstViolationStep = integer(
        finding.firstViolationStep,
        'firstViolationStep',
        context
      )
      const seed = integer(finding.seed, 'seed', context)
      if (seed !== effectiveSeed) {
        throw new FuzzResponseContractError(context, 'finding seed must match effectiveSeed')
      }
      text(finding.createdAt, 'createdAt', context)
      const stateCount = integer(finding.stateCount, 'stateCount', context, 1)
      if (stateCount > pathLength) {
        throw new FuzzResponseContractError(context, 'finding stateCount cannot exceed pathLength')
      }
      if (firstViolationStep !== stateCount - 1) {
        throw new FuzzResponseContractError(
          context,
          'finding trace must end at the first violation'
        )
      }
      if ('dataAvailable' in finding) {
        throw new FuzzResponseContractError(
          context,
          'finding summaries must not claim availability before evidence is loaded'
        )
      }
    })
  }
  text(run.createdAt, 'createdAt', context)
  text(run.completedAt, 'completedAt', context)
  const findingCount = integer(run.findingCount, 'findingCount', context)
  if (findingCount !== run.findings.length) {
    throw new FuzzResponseContractError(context, 'findingCount must match findings length')
  }
  if ((run.outcome === 'FOUND_VIOLATION') !== (findingCount > 0)) {
    throw new FuzzResponseContractError(context, 'outcome must agree with whether findings exist')
  }
  if (!availabilityRequired) stringArray(run.targetSpecIds, 'targetSpecIds', context)
  return { ...run, dataAvailable: true } as AvailableFuzzingRunSummary
}

export const validateFuzzingTask = (value: unknown): FuzzingTask =>
  validateTask(value, 'Fuzz task')

export const validateFuzzingTaskSummaryList = (value: unknown): FuzzingTaskSummary[] => {
  if (!Array.isArray(value)) {
    throw new FuzzResponseContractError('Fuzz task inbox', 'result must be an array')
  }
  return value.map(item => validateTask(item, 'Fuzz task summary'))
}

export const validateFuzzingRun = (value: unknown): FuzzingRun => {
  const candidate = record(value, 'Fuzz run')
  if ('dataAvailable' in candidate) {
    throw new FuzzResponseContractError(
      'Fuzz run',
      'run details must not include summary-only dataAvailable'
    )
  }
  const run = validateRun(candidate, 'Fuzz run', false) as AvailableFuzzingRunSummary & Pick<FuzzingRun, 'targetSpecIds'>
  const findings = (run.findings as unknown[]).map(validateFuzzingFinding)
  const eligibleSpecIds = new Set(run.eligibility.eligibleSpecIds)
  const findingIds = new Set<number>()
  const findingSpecIds = new Set<string>()
  for (const finding of findings) {
    assertFuzzingFindingBelongsToRun(finding, run.id, 'Fuzz run')
    if (findingIds.has(finding.id)) {
      throw new FuzzResponseContractError('Fuzz run', 'finding IDs must be unique')
    }
    findingIds.add(finding.id)
    if (!eligibleSpecIds.has(finding.violatedSpecId)) {
      throw new FuzzResponseContractError(
        'Fuzz run',
        'finding must reference an eligible specification'
      )
    }
    if (findingSpecIds.has(finding.violatedSpecId)) {
      throw new FuzzResponseContractError(
        'Fuzz run',
        'findings must contain at most one result per specification'
      )
    }
    findingSpecIds.add(finding.violatedSpecId)
    if (finding.seed !== run.effectiveSeed) {
      throw new FuzzResponseContractError('Fuzz run', 'finding seed must match effectiveSeed')
    }
    if (finding.states.length > run.pathLength) {
      throw new FuzzResponseContractError('Fuzz run', 'finding states cannot exceed pathLength')
    }
  }
  const { dataAvailable: _summaryAvailability, ...detail } = run
  return { ...detail, findings } as FuzzingRun
}

export const validateFuzzingRunSummaryList = (value: unknown): FuzzingRunSummary[] => {
  if (!Array.isArray(value)) {
    throw new FuzzResponseContractError('Fuzz run history', 'result must be an array')
  }
  return value.map(item => validateRun(item, 'Fuzz run summary'))
}

export const validateFuzzingFinding = (value: unknown): FuzzingFinding => {
  const context = 'Fuzz finding'
  const finding = record(value, context)
  integer(finding.id, 'id', context, 1)
  integer(finding.fuzzTaskId, 'fuzzTaskId', context, 1)
  const violatedSpecId = text(finding.violatedSpecId, 'violatedSpecId', context)
  const violatedSpec = record(finding.violatedSpec, `${context} violatedSpec`)
  if (text(violatedSpec.id, 'violatedSpec.id', context) !== violatedSpecId) {
    throw new FuzzResponseContractError(context, 'violatedSpec.id must match violatedSpecId')
  }
  integer(finding.firstViolationStep, 'firstViolationStep', context)
  integer(finding.seed, 'seed', context)
  text(finding.createdAt, 'createdAt', context)
  validateTraceStateList(finding.states, context)
  if (finding.firstViolationStep !== finding.states.length - 1) {
    throw new FuzzResponseContractError(context, 'finding trace must end at the first violation')
  }
  if (!Array.isArray(finding.inputEvents)) {
    throw new FuzzResponseContractError(context, 'inputEvents must be an array')
  }
  let previousStep = -1
  let previousSourceOrder = -1
  finding.inputEvents.forEach((event: unknown, index: number) => {
    const candidate = record(event, `${context} inputEvents[${index}]`)
    const step = integer(candidate.step, 'step', context)
    const kind = text(candidate.kind, 'kind', context)
    if (!INPUT_EVENT_KINDS.has(kind)) {
      throw new FuzzResponseContractError(context, 'input event kind is invalid')
    }
    const source = candidate.source
    if (typeof source !== 'string' || !INPUT_EVENT_SOURCES.has(source)) {
      throw new FuzzResponseContractError(context, 'input event source is invalid')
    }
    if (source === 'RANDOM_INITIAL_STATE' && step !== 0) {
      throw new FuzzResponseContractError(
        context,
        'random initial-state events are only valid at step 0'
      )
    }
    const sourceOrder = INPUT_EVENT_SOURCE_ORDER[source]
    if (step < previousStep || (step === previousStep && sourceOrder < previousSourceOrder)) {
      throw new FuzzResponseContractError(context, 'input events must be in causal order')
    }
    previousStep = step
    previousSourceOrder = sourceOrder
    text(candidate.targetId, 'targetId', context)
    text(candidate.property, 'property', context)
    text(candidate.value, 'value', context)
    if (step > finding.firstViolationStep) {
      throw new FuzzResponseContractError(context, 'input event step cannot follow the first violation')
    }
  })
  return finding as FuzzingFinding
}

/**
 * The single-finding endpoint carries the frozen run context needed for replay. It must be
 * validated together with the finding so the canvas never falls back to the current Board.
 */
export const validateFuzzingFindingReplay = (value: unknown): FuzzingFindingReplay => {
  const context = 'Fuzz finding replay'
  const replay = record(value, context)
  const finding = validateFuzzingFinding(replay.finding)
  validateSnapshot(replay.modelSnapshot, context)
  const snapshot = replay.modelSnapshot as FuzzingModelRunSnapshot
  validateModelPlaybackScene(
    replay.playbackScene,
    snapshot.deviceCount,
    snapshot.ruleCount,
    detail => { throw new FuzzResponseContractError(context, detail) }
  )
  return {
    finding,
    modelSnapshot: snapshot,
    playbackScene: replay.playbackScene as ModelPlaybackScene
  }
}

export const validateFuzzingFindingList = (value: unknown): FuzzingFinding[] => {
  if (!Array.isArray(value)) {
    throw new FuzzResponseContractError('Fuzz findings', 'result must be an array')
  }
  return value.map(validateFuzzingFinding)
}

export const validateFuzzWorkloadPreview = (
  value: unknown,
  expected: Pick<
    FuzzWorkloadPreview,
    'maxIterations' | 'pathLength' | 'populationSize' | 'explorationMode'
  >
): FuzzWorkloadPreview => {
  const context = 'Counterexample-search workload preview'
  const preview = record(value, context)
  const maxIterations = integer(preview.maxIterations, 'maxIterations', context, 1)
  const pathLength = integer(preview.pathLength, 'pathLength', context, 1)
  const populationSize = integer(preview.populationSize, 'populationSize', context, 1)
  const modelComplexityUnits = integer(preview.modelComplexityUnits, 'modelComplexityUnits', context, 0)
  const estimatedWorkload = integer(preview.estimatedWorkload, 'estimatedWorkload', context, 1)
  const workloadLimit = integer(preview.workloadLimit, 'workloadLimit', context, 1)
  const maxAcceptedIterations = integer(
    preview.maxAcceptedIterations, 'maxAcceptedIterations', context, 0)
  if (maxIterations !== expected.maxIterations
    || pathLength !== expected.pathLength
    || populationSize !== expected.populationSize) {
    throw new FuzzResponseContractError(context, 'budget fields must match the requested preview')
  }
  // Complexity is mode-dependent, so a preview computed for the other mode describes a different
  // estimate than submission would apply. Checked here rather than trusted because it arrived.
  if (preview.explorationMode !== expected.explorationMode) {
    throw new FuzzResponseContractError(
      context, 'explorationMode must match the requested preview')
  }
  const expectedWorkload = safeIntegerProduct(
    [maxIterations, pathLength, populationSize, Math.max(1, modelComplexityUnits)],
    'estimatedWorkload',
    context
  )
  if (estimatedWorkload !== expectedWorkload) {
    throw new FuzzResponseContractError(
      context,
      'estimatedWorkload must equal the authoritative workload product'
    )
  }
  if (typeof preview.accepted !== 'boolean'
    || preview.accepted !== (estimatedWorkload <= workloadLimit)) {
    throw new FuzzResponseContractError(context, 'accepted must agree with the workload limit')
  }
  // The ceiling must agree with acceptance in both directions: a rejected budget cannot claim the
  // requested iteration count still fits, and an accepted one cannot report a ceiling below it. A
  // one-way check would let the remedy contradict the verdict it is displayed beside.
  if (maxAcceptedIterations > 0 && preview.accepted !== (maxIterations <= maxAcceptedIterations)) {
    throw new FuzzResponseContractError(
      context, 'maxAcceptedIterations must agree with accepted')
  }
  if (maxAcceptedIterations === 0 && preview.accepted) {
    throw new FuzzResponseContractError(
      context, 'an accepted budget cannot report a zero iteration ceiling')
  }
  return {
    maxIterations,
    pathLength,
    populationSize,
    explorationMode: expected.explorationMode,
    modelComplexityUnits,
    estimatedWorkload,
    workloadLimit,
    accepted: preview.accepted,
    maxAcceptedIterations
  }
}

export const validateFuzzPaperDomainPreview = (
  value: unknown,
  expectedPathLength?: number
): FuzzPaperDomainPreview => {
  const context = 'Random-state input preview'
  const preview = record(value, context)
  const pathLength = integer(preview.pathLength, 'pathLength', context, 1)
  if (pathLength > 50) {
    throw new FuzzResponseContractError(context, 'pathLength must be <= 50')
  }
  if (expectedPathLength !== undefined && pathLength !== expectedPathLength) {
    throw new FuzzResponseContractError(context, 'pathLength must match the requested preview')
  }
  if (!isValidFuzzPaperDomainFingerprint(preview.modelFingerprint)) {
    throw new FuzzResponseContractError(context, 'modelFingerprint must be a 64-character lowercase hexadecimal value')
  }
  if (preview.initializationPolicy !== 'RANDOM_LEGAL_PER_SEED') {
    throw new FuzzResponseContractError(context, 'initializationPolicy is invalid')
  }
  stableCodeArray(
    preview.paperSemanticsCodes,
    'paperSemanticsCodes',
    context,
    FUZZ_PAPER_SEMANTICS_CODES
  )
  validatePaperDeviceDomains(preview.deviceDomains, 'deviceDomains', context)
  validatePaperDeviceDomains(preview.localVariableDomains, 'localVariableDomains', context)
  if (!Array.isArray(preview.environmentDomains)) {
    throw new FuzzResponseContractError(context, 'environmentDomains must be an array')
  }
  preview.environmentDomains.forEach((value: unknown, index: number) => {
    const domain = record(value, `${context} environmentDomains[${index}]`)
    text(domain.name, 'name', context)
    text(domain.targetId, 'targetId', context)
    text(domain.property, 'property', context)
    if (!PAPER_EVENT_VALUE_KINDS.has(domain.eventValueKind)) {
      throw new FuzzResponseContractError(context, 'eventValueKind is invalid')
    }
    const initialValues = stringArray(domain.initialValues, 'initialValues', context)
    const eventValues = stringArray(domain.eventValues, 'eventValues', context)
    const initialRange = optionalIntegerRange(
      domain.initialLowerBound, domain.initialUpperBound, 'initial range', context)
    const eventRateRange = optionalIntegerRange(
      domain.eventRateLowerBound, domain.eventRateUpperBound, 'event rate range', context)
    const invalidInitialDomain = (initialValues.length > 0) === initialRange
    const invalidEventDomain = domain.eventValueKind === 'CHANGE_RATE'
      ? (eventValues.length > 0) === eventRateRange
      : eventValues.length === 0 || eventRateRange
    if (invalidInitialDomain || invalidEventDomain
      || [...initialValues, ...eventValues].some(item => !item.trim())) {
      throw new FuzzResponseContractError(context, 'environment domains must contain non-empty values')
    }
  })
  return preview as FuzzPaperDomainPreview
}
