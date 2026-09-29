import type {
  ConditionAdjustment,
  FaultLocalizationResult,
  FaultRule,
  FixResult,
  FixStrategyAttempt,
  FixStrategyAttemptStatus,
  FixStrategyName,
  FixSuggestion,
  ParameterAdjustment,
  ParameterTarget,
  PreferredRangeSelection
} from '@/types/fix'
import type { ModelGenerationIssue } from '@/types/verify'
import { MODEL_TOKEN_SOURCE_SET } from '@/types/modelToken'

export const FIX_RESPONSE_INCOMPLETE_CODE = 'FIX_RESPONSE_INCOMPLETE'

class FixResponseContractError extends Error {
  readonly code = FIX_RESPONSE_INCOMPLETE_CODE

  constructor(context: string, detail: string) {
    super(`${context} returned an incomplete automatic-fix result: ${detail}`)
    this.name = 'FixResponseContractError'
  }
}

const STRATEGIES = new Set<FixStrategyName>(['parameter', 'condition', 'remove'])
const ATTEMPT_STATUSES = new Set<FixStrategyAttemptStatus>([
  'VERIFIED',
  'NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE',
  'ALL_CANDIDATES_REJECTED',
  'INCONCLUSIVE',
  'FAILED_MODEL_GENERATION',
  'FAILED_SOLVER_EXECUTION',
  'SEARCH_BUDGET_EXHAUSTED',
  'TIMED_OUT',
  'SKIPPED_TIMEOUT',
  'SKIPPED_NO_SPEC',
  'SKIPPED_NO_PARAMETERIZABLE_VALUES',
  'SKIPPED_NO_FAULT_RULES',
  'SKIPPED_INCOMPLETE_SOURCE_MODEL',
  'SKIPPED_UNSUPPORTED'
])
const TEMPLATE_SNAPSHOT_COMPARISONS = new Set([
  'NOT_CHECKED',
  'UNCHANGED',
  'CHANGED',
  'UNAVAILABLE'
])
const TARGET_ID_PATTERN = /^param_[A-Za-z0-9_-]{24}$/

const record = (value: unknown, context: string, field = 'result'): Record<string, any> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new FixResponseContractError(context, `${field} must be an object`)
  }
  return value as Record<string, any>
}

const array = (value: Record<string, any>, field: string, context: string): any[] => {
  if (!Array.isArray(value[field])) {
    throw new FixResponseContractError(context, `${field} must be an array`)
  }
  return value[field]
}

const text = (value: Record<string, any>, field: string, context: string): string => {
  if (typeof value[field] !== 'string' || !value[field].trim()) {
    throw new FixResponseContractError(context, `${field} must be non-blank text`)
  }
  return value[field]
}

const bool = (value: Record<string, any>, field: string, context: string): boolean => {
  if (typeof value[field] !== 'boolean') {
    throw new FixResponseContractError(context, `${field} must be boolean`)
  }
  return value[field]
}

const integer = (value: Record<string, any>, field: string, context: string, min = 0): number => {
  if (!Number.isSafeInteger(value[field]) || value[field] < min) {
    throw new FixResponseContractError(context, `${field} must be an integer >= ${min}`)
  }
  return value[field]
}

const validateModelTokenSource = (value: Record<string, any>, context: string, field: string) => {
  if (!MODEL_TOKEN_SOURCE_SET.has(value.modelTokenSource)) {
    throw new FixResponseContractError(context, `${field}.modelTokenSource is invalid`)
  }
}

const stringArray = (value: Record<string, any>, field: string, context: string): string[] => {
  const values = array(value, field, context)
  values.forEach((item, index) => {
    if (typeof item !== 'string' || !item.trim()) {
      throw new FixResponseContractError(context, `${field}[${index}] must be non-blank text`)
    }
  })
  return values
}

const validateSourceModel = (value: Record<string, any>, context: string) => {
  const complete = bool(value, 'sourceModelComplete', context)
  const disabledRuleCount = integer(value, 'sourceDisabledRuleCount', context)
  const skippedSpecCount = integer(value, 'sourceSkippedSpecCount', context)
  const issues = array(value, 'sourceGenerationIssues', context)
  issues.forEach((issue, index) => {
    const row = record(issue, context, `sourceGenerationIssues[${index}]`)
    text(row, 'issueType', context)
    text(row, 'itemLabel', context)
    text(row, 'reasonCode', context)
    text(row, 'reason', context)
  })
  const disabledIssues = issues.filter(issue => issue.issueType === 'RULE_DISABLED').length
  const skippedIssues = issues.filter(issue => issue.issueType === 'SPECIFICATION_SKIPPED').length
  if (disabledIssues !== disabledRuleCount || skippedIssues !== skippedSpecCount) {
    throw new FixResponseContractError(context, 'source omission counts must match sourceGenerationIssues')
  }
  if (complete && (disabledRuleCount !== 0 || skippedSpecCount !== 0 || issues.length !== 0)) {
    throw new FixResponseContractError(context, 'sourceModelComplete contradicts source omissions')
  }
  return issues as ModelGenerationIssue[]
}

const validateFaultRule = (value: unknown, context: string, index: number): FaultRule => {
  const row = record(value, context, `faultRules[${index}]`)
  // `ruleString` is a preview with no `@NotBlank` on the server and a nullable column, so null is a legitimate
  // value — not a contract violation. Requiring it here rejected the *whole* fault-localization response for one
  // rule saved without a preview, so a fix request failed as "malformed result". The UI supplies the localised
  // `Rule {number}` fallback, as it already does for `TraceRule.ruleLabel`.
  if (row.ruleString !== null && row.ruleString !== undefined && typeof row.ruleString !== 'string') {
    throw new FixResponseContractError(context, `faultRules[${index}].ruleString must be text when present`)
  }
  integer(row, 'transitionNumber', context, 1)
  text(row, 'targetDeviceLabel', context)
  text(row, 'targetActionLabel', context)
  const conflicting = bool(row, 'conflicting', context)
  if (!['TRIGGERED', 'CONFLICTING_END_STATES'].includes(row.reasonCode)) {
    throw new FixResponseContractError(context, `faultRules[${index}].reasonCode is invalid`)
  }
  text(row, 'reason', context)
  validateModelTokenSource(row, context, `faultRules[${index}]`)
  if (conflicting) {
    // Nullable for the same reason as `ruleString`: the conflicting rule may have no preview of its own. The
    // server sends the raw value or null and the UI supplies the localised copy, so requiring it here would
    // reject the whole response over a rule the user never named.
    if (row.conflictingRuleString !== null && row.conflictingRuleString !== undefined
      && typeof row.conflictingRuleString !== 'string') {
      throw new FixResponseContractError(
        context, `faultRules[${index}].conflictingRuleString must be text when present`)
    }
    if (row.reasonCode !== 'CONFLICTING_END_STATES') {
      throw new FixResponseContractError(context, 'a conflicting rule needs the conflicting reason code')
    }
  }
  return row as FaultRule
}

const validateFaultRules = (value: Record<string, any>, context: string): FaultRule[] =>
  array(value, 'faultRules', context).map((rule, index) => validateFaultRule(rule, context, index))

const validatePreferredRangeSelection = (
  value: unknown,
  context: string,
  field: string
): PreferredRangeSelection => {
  const row = record(value, context, field)
  const targetId = text(row, 'targetId', context)
  if (!TARGET_ID_PATTERN.test(targetId)) {
    throw new FixResponseContractError(context, `${field}.targetId is invalid`)
  }
  const lower = integer(row, 'lower', context, Number.MIN_SAFE_INTEGER)
  const upper = integer(row, 'upper', context, Number.MIN_SAFE_INTEGER)
  if (lower > upper) {
    throw new FixResponseContractError(context, `${field} lower must not exceed upper`)
  }
  return row as PreferredRangeSelection
}

const validateParameterAdjustment = (
  value: unknown,
  context: string,
  index: number
): ParameterAdjustment => {
  const row = record(value, context, `parameterAdjustments[${index}]`)
  if (!TARGET_ID_PATTERN.test(text(row, 'targetId', context))) {
    throw new FixResponseContractError(context, `parameterAdjustments[${index}].targetId is invalid`)
  }
  for (const field of ['attribute', 'relation', 'originalValue', 'newValue', 'description']) {
    text(row, field, context)
  }
  validateModelTokenSource(row, context, `parameterAdjustments[${index}]`)
  const lower = integer(row, 'lowerBound', context, Number.MIN_SAFE_INTEGER)
  const upper = integer(row, 'upperBound', context, Number.MIN_SAFE_INTEGER)
  if (lower > upper) {
    throw new FixResponseContractError(context, 'parameter adjustment bounds are reversed')
  }
  const newValue = Number(row.newValue)
  if (!Number.isSafeInteger(newValue) || newValue < lower || newValue > upper) {
    throw new FixResponseContractError(context, 'parameter adjustment newValue is outside its declared range')
  }
  return row as ParameterAdjustment
}

const validateParameterTarget = (
  value: unknown,
  context: string,
  index: number
): ParameterTarget => {
  const row = record(value, context, `parameterTargets[${index}]`)
  if (!TARGET_ID_PATTERN.test(text(row, 'targetId', context))) {
    throw new FixResponseContractError(context, `parameterTargets[${index}].targetId is invalid`)
  }
  for (const field of ['attribute', 'relation', 'originalValue', 'description']) {
    text(row, field, context)
  }
  validateModelTokenSource(row, context, `parameterTargets[${index}]`)
  const lower = integer(row, 'lowerBound', context, Number.MIN_SAFE_INTEGER)
  const upper = integer(row, 'upperBound', context, Number.MIN_SAFE_INTEGER)
  if (lower > upper) {
    throw new FixResponseContractError(context, 'parameter target bounds are reversed')
  }
  return row as ParameterTarget
}

const validateConditionAdjustment = (
  value: unknown,
  context: string,
  index: number
): ConditionAdjustment => {
  const row = record(value, context, `conditionAdjustments[${index}]`)
  if (!['remove', 'keep', 'add'].includes(row.action)) {
    throw new FixResponseContractError(context, `conditionAdjustments[${index}].action is invalid`)
  }
  for (const field of ['attribute', 'description', 'ruleDescription', 'deviceLabel']) {
    text(row, field, context)
  }
  if (!['api', 'variable', 'mode', 'state'].includes(row.targetType)) {
    throw new FixResponseContractError(context, `conditionAdjustments[${index}].targetType is invalid`)
  }
  validateModelTokenSource(row, context, `conditionAdjustments[${index}]`)
  return row as ConditionAdjustment
}

export const validateFixSuggestion = (
  value: unknown,
  context: string,
  expectedStrategy?: FixStrategyName,
  requireSuggestionToken = false
): FixSuggestion => {
  const suggestion = record(value, context, 'suggestion')
  if (!STRATEGIES.has(suggestion.strategy)) {
    throw new FixResponseContractError(context, 'suggestion strategy is invalid')
  }
  if (expectedStrategy && suggestion.strategy !== expectedStrategy) {
    throw new FixResponseContractError(context, 'suggestion strategy does not match the request')
  }
  text(suggestion, 'description', context)
  if (requireSuggestionToken) {
    text(suggestion, 'suggestionToken', context)
  }
  const parameters = array(suggestion, 'parameterAdjustments', context).map(
    (item: unknown, index: number) => validateParameterAdjustment(item, context, index))
  const conditions = array(suggestion, 'conditionAdjustments', context).map(
    (item: unknown, index: number) => validateConditionAdjustment(item, context, index))
  const removed = stringArray(suggestion, 'removedRuleDescriptions', context)
  const detailCount = suggestion.strategy === 'parameter'
    ? parameters.length
    : suggestion.strategy === 'condition'
      ? conditions.length
      : removed.length
  if (detailCount === 0) {
    throw new FixResponseContractError(context, `${suggestion.strategy} suggestion has no concrete changes`)
  }
  const preexisting = array(suggestion, 'preexistingViolations', context)
  preexisting.forEach((item: unknown, index: number) => {
    const row = record(item, context, `preexistingViolations[${index}]`)
    for (const field of ['specId', 'templateId', 'formulaPreview']) {
      text(row, field, context)
    }
  })
  return suggestion as FixSuggestion
}

const validateAttempt = (value: unknown, context: string, index: number): FixStrategyAttempt => {
  const attempt = record(value, context, `strategyAttempts[${index}]`)
  if (!STRATEGIES.has(attempt.strategy)) {
    throw new FixResponseContractError(context, `strategyAttempts[${index}].strategy is invalid`)
  }
  if (!ATTEMPT_STATUSES.has(attempt.status)) {
    throw new FixResponseContractError(context, `strategyAttempts[${index}].status is invalid`)
  }
  text(attempt, 'reason', context)
  // Completeness describes a listing, so only a strategy that listed suggestions can state it.
  if (attempt.status === 'VERIFIED') {
    bool(attempt, 'alternativesComplete', context)
  } else if (attempt.alternativesComplete !== null && attempt.alternativesComplete !== undefined) {
    throw new FixResponseContractError(
      context, `strategyAttempts[${index}].alternativesComplete is only meaningful on VERIFIED`)
  }
  return attempt as FixStrategyAttempt
}

export const validateFaultLocalizationResult = (
  value: unknown,
  expectedTraceId: number
): FaultLocalizationResult => {
  const context = 'Fault localization'
  const result = record(value, context)
  if (integer(result, 'traceId', context, 1) !== expectedTraceId) {
    throw new FixResponseContractError(context, 'traceId does not match the request')
  }
  text(result, 'violatedSpecId', context)
  validateSourceModel(result, context)
  validateFaultRules(result, context)
  text(result, 'summary', context)
  const warnings = stringArray(result, 'warnings', context)
  if (!result.sourceModelComplete && warnings.length === 0) {
    throw new FixResponseContractError(context, 'incomplete source model requires a warning')
  }
  return result as FaultLocalizationResult
}

export const validateFixResult = (
  value: unknown,
  expectedTraceId: number,
  requestedStrategies: FixStrategyName[]
): FixResult => {
  const context = 'Automatic fix analysis'
  const result = record(value, context)
  if (integer(result, 'traceId', context, 1) !== expectedTraceId) {
    throw new FixResponseContractError(context, 'traceId does not match the request')
  }
  text(result, 'violatedSpecId', context)
  validateSourceModel(result, context)
  validateFaultRules(result, context)
  const suggestions = array(result, 'suggestions', context).map(suggestion =>
    validateFixSuggestion(suggestion, context, undefined, true))
  const attempts = array(result, 'strategyAttempts', context).map((attempt, index) =>
    validateAttempt(attempt, context, index))
  const expected = requestedStrategies.length > 0
    ? requestedStrategies
    : ['parameter', 'condition', 'remove'] as FixStrategyName[]
  if (new Set(expected).size !== expected.length
      || attempts.length !== expected.length
      || expected.some(strategy => attempts.filter(attempt => attempt.strategy === strategy).length !== 1)) {
    throw new FixResponseContractError(context, 'strategyAttempts must explain every requested strategy exactly once')
  }
  // A strategy may list several alternative suggestions; each must belong to a VERIFIED attempt.
  const suggestionStrategies = new Set<FixStrategyName>()
  suggestions.forEach(suggestion => {
    suggestionStrategies.add(suggestion.strategy)
    const attempt = attempts.find(item => item.strategy === suggestion.strategy)
    if (!attempt || attempt.status !== 'VERIFIED') {
      throw new FixResponseContractError(context, 'a suggestion needs a VERIFIED strategy attempt')
    }
  })
  // The reciprocal direction. Without it a VERIFIED attempt with no suggestion passes, because
  // `fixable` is derived from the suggestions alone and stays consistently false — so the dialog
  // reports that a concrete suggestion passed forward verification while showing the no-suggestion
  // empty state and offering no Apply control.
  attempts.forEach(attempt => {
    if (attempt.status === 'VERIFIED' && !suggestionStrategies.has(attempt.strategy)) {
      throw new FixResponseContractError(
        context, 'a VERIFIED strategy attempt must carry its suggestion')
    }
  })
  const fixable = bool(result, 'fixable', context)
  if (fixable !== suggestions.length > 0) {
    throw new FixResponseContractError(context, 'fixable must match the listed suggestions')
  }
  if (!result.sourceModelComplete) {
    if (fixable || suggestions.length !== 0
        || attempts.some(attempt => attempt.status !== 'SKIPPED_INCOMPLETE_SOURCE_MODEL')) {
      throw new FixResponseContractError(context, 'an incomplete source model cannot produce an applicable fix')
    }
  }
  if (!TEMPLATE_SNAPSHOT_COMPARISONS.has(result.templateSnapshotComparison)) {
    throw new FixResponseContractError(context, 'templateSnapshotComparison is invalid')
  }
  text(result, 'summary', context)
  stringArray(result, 'warnings', context)
  const parameterTargets = array(result, 'parameterTargets', context).map(
    (target, index) => validateParameterTarget(target, context, index))
  if (new Set(parameterTargets.map(target => target.targetId)).size !== parameterTargets.length) {
    throw new FixResponseContractError(context, 'parameterTargets contains duplicate targets')
  }
  const unusedPreferredRangeSelections = array(result, 'unusedPreferredRangeSelections', context)
  const seenUnusedTargets = new Set<string>()
  unusedPreferredRangeSelections.forEach((selection: unknown, index: number) => {
    const validated = validatePreferredRangeSelection(
      selection, context, `unusedPreferredRangeSelections[${index}]`)
    if (seenUnusedTargets.has(validated.targetId)) {
      throw new FixResponseContractError(context, 'unusedPreferredRangeSelections contains duplicate targets')
    }
    seenUnusedTargets.add(validated.targetId)
  })
  return result as FixResult
}
