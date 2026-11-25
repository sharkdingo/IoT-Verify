import type { Specification } from '@/types/spec'

export const FUZZ_ITERATIONS_MIN = 1
export const FUZZ_ITERATIONS_MAX = 5_000
export const FUZZ_PATH_LENGTH_MIN = 1
export const FUZZ_PATH_LENGTH_MAX = 50
export const FUZZ_POPULATION_MIN = 1
export const FUZZ_POPULATION_MAX = 50
export const FUZZ_TARGET_SPEC_MAX = 100
const FUZZ_SEED_MAX = Number.MAX_SAFE_INTEGER
const FUZZ_SUPPORTED_TEMPLATE_IDS = new Set(['1', '3', '4'])

export const isKnownFuzzingTemplateSupported = (templateId: unknown): boolean =>
  FUZZ_SUPPORTED_TEMPLATE_IDS.has(String(templateId ?? '').trim())

export type KnownFuzzingSpecificationIssue =
  | 'UNSUPPORTED_TEMPLATE'
  | 'TRUST_PRIVACY_UNSUPPORTED'
  | 'REPORTED_READING_UNSUPPORTED'

export type IsSharedFuzzingVariable = (deviceId: string, key: string) => boolean

export const getKnownFuzzingSpecificationIssue = (
  specification: Pick<Specification, 'templateId' | 'aConditions' | 'ifConditions' | 'thenConditions'>,
  isSharedVariable: IsSharedFuzzingVariable
): KnownFuzzingSpecificationIssue | null => {
  if (!isKnownFuzzingTemplateSupported(specification.templateId)) return 'UNSUPPORTED_TEMPLATE'
  const conditions = [
    ...(specification.aConditions || []),
    ...(specification.ifConditions || []),
    ...(specification.thenConditions || [])
  ]
  const normalizedTargetTypes = conditions.map(condition =>
    ({ condition, targetType: String(condition?.targetType ?? '').trim().toLowerCase() }))
  if (normalizedTargetTypes.some(({ targetType }) =>
    targetType === 'trust' || targetType === 'privacy')) {
    return 'TRUST_PRIVACY_UNSUPPORTED'
  }
  // The explorer tracks local values directly, but keeps only the environment value for shared readings.
  if (normalizedTargetTypes.some(({ condition, targetType }) =>
    targetType === 'variable'
    && String(condition?.variableSource ?? '').trim().toLowerCase() === 'reported'
    && isSharedVariable(condition.deviceId.trim(), condition.key.trim()))) {
    return 'REPORTED_READING_UNSUPPORTED'
  }
  return null
}

export const isKnownFuzzingSpecificationSupported = (
  specification: Pick<Specification, 'templateId' | 'aConditions' | 'ifConditions' | 'thenConditions'>,
  isSharedVariable: IsSharedFuzzingVariable
): boolean => getKnownFuzzingSpecificationIssue(specification, isSharedVariable) === null

export interface FuzzingWorkloadConfig {
  maxIterations: number
  pathLength: number
  populationSize: number
  seed?: number | null
  targetSpecIds: string[]
}

export interface FuzzingWorkloadAssessment {
  workload: number
  limit: number
}

export type FuzzingConfigurationIssue =
  | {
      code: 'INVALID_INTEGER_FIELD'
      field: 'maxIterations' | 'pathLength' | 'populationSize' | 'seed'
      minimum: number
      maximum: number
    }
  | { code: 'TARGET_SELECTION_REQUIRED'; availableSpecCount: number; limit: number }
  | { code: 'TOO_MANY_TARGETS'; selectedSpecCount: number; limit: number }
  | { code: 'WORKLOAD_EXCEEDED'; workload: number; limit: number }

export const getFuzzingConfigurationIssue = (
  config: FuzzingWorkloadConfig,
  availableSpecCount: number,
  workloadAssessment?: FuzzingWorkloadAssessment | null
): FuzzingConfigurationIssue | null => {
  const integerFields = [
    ['maxIterations', config.maxIterations, FUZZ_ITERATIONS_MIN, FUZZ_ITERATIONS_MAX],
    ['pathLength', config.pathLength, FUZZ_PATH_LENGTH_MIN, FUZZ_PATH_LENGTH_MAX],
    ['populationSize', config.populationSize, FUZZ_POPULATION_MIN, FUZZ_POPULATION_MAX]
  ] as const
  for (const [field, value, minimum, maximum] of integerFields) {
    if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
      return { code: 'INVALID_INTEGER_FIELD', field, minimum, maximum }
    }
  }
  if (config.seed !== undefined && config.seed !== null
    && (!Number.isSafeInteger(config.seed) || config.seed < 0 || config.seed > FUZZ_SEED_MAX)) {
    return {
      code: 'INVALID_INTEGER_FIELD',
      field: 'seed',
      minimum: 0,
      maximum: FUZZ_SEED_MAX
    }
  }
  if (availableSpecCount > FUZZ_TARGET_SPEC_MAX && config.targetSpecIds.length === 0) {
    return {
      code: 'TARGET_SELECTION_REQUIRED',
      availableSpecCount,
      limit: FUZZ_TARGET_SPEC_MAX
    }
  }
  if (config.targetSpecIds.length > FUZZ_TARGET_SPEC_MAX) {
    return {
      code: 'TOO_MANY_TARGETS',
      selectedSpecCount: config.targetSpecIds.length,
      limit: FUZZ_TARGET_SPEC_MAX
    }
  }
  if (workloadAssessment
    && (!Number.isSafeInteger(workloadAssessment.workload)
      || workloadAssessment.workload > workloadAssessment.limit)) {
    return {
      code: 'WORKLOAD_EXCEEDED',
      workload: workloadAssessment.workload,
      limit: workloadAssessment.limit
    }
  }
  return null
}

/**
 * The budget fields a server-side preview is computed for.
 *
 * A preview is only meaningful for the exact budget it was requested with, so these three are
 * compared field by field rather than trusted because a response arrived.
 */
export interface FuzzingBudget {
  maxIterations: number
  pathLength: number
  populationSize: number
}

/** The default budget product, kept aligned with the backend `FuzzRequestDto` default. */
export const FUZZ_DEFAULT_MAX_ITERATIONS = 200
export const FUZZ_DEFAULT_PATH_LENGTH = 20
export const FUZZ_DEFAULT_POPULATION_SIZE = 10

/** A preview the server returned, tagged with the board semantics it was computed against. */
export interface FuzzingPreviewState<T extends FuzzingBudget> {
  preview: T | null
  loading: boolean
  error: unknown
  /** Board semantics the preview was computed for, as captured when the request was issued. */
  previewSemanticKey: string | null
}

/**
 * Whether a fetched workload preview may be shown as describing the current form.
 *
 * This is the guard against presenting a stale estimate as current: the user can change a budget
 * field or edit the board while a preview is in flight, and a late response would otherwise be
 * rendered next to inputs it was never computed for. Both the board semantics and every budget
 * field must still match, and a preview that is loading or failed is never "ready".
 */
export const isFuzzingPreviewCurrent = <T extends FuzzingBudget>(
  state: FuzzingPreviewState<T>,
  budget: FuzzingBudget,
  currentSemanticKey: string
): boolean => {
  const { preview } = state
  return !!preview
    && !state.loading
    && !state.error
    && state.previewSemanticKey === currentSemanticKey
    && preview.maxIterations === budget.maxIterations
    && preview.pathLength === budget.pathLength
    && preview.populationSize === budget.populationSize
}

/** True when every budget field is an integer inside its documented bound. */
export const hasValidFuzzingBudget = (budget: FuzzingBudget): boolean => ([
  [budget.maxIterations, FUZZ_ITERATIONS_MIN, FUZZ_ITERATIONS_MAX],
  [budget.pathLength, FUZZ_PATH_LENGTH_MIN, FUZZ_PATH_LENGTH_MAX],
  [budget.populationSize, FUZZ_POPULATION_MIN, FUZZ_POPULATION_MAX]
] as const).every(([value, minimum, maximum]) => Number.isInteger(value)
  && value >= minimum
  && value <= maximum)
