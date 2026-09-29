/**
 * Fix 功能相关类型定义
 * 对应后端 DTO: FaultRuleDto, FixResultDto, FixSuggestionDto, PreexistingViolationDto, ParameterAdjustment,
 * ConditionAdjustment
 */

import type { RuleForm, RuleSourceItemType } from './rule'
import type { ModelGenerationIssue } from './verify'
import type { ModelTokenSource } from './modelToken'

export type { ModelTokenSource } from './modelToken'

// 故障规则定位结果
export interface FaultRule {
  /**
   * The rule's own preview text, or null when it has none.
   *
   * `RuleDto.ruleString` has no `@NotBlank` and a nullable TEXT column, so a rule legitimately persists without
   * one — verified against the running API, which accepts a rule with the field omitted and echoes
   * `"ruleString": null`. This was declared `string`, and `validateFaultRule` enforced that with
   * `text(row, 'ruleString')`, which throws on null and rejected the **entire** fault-localization response: one
   * such rule turned a fix request into "malformed result" instead of a fix.
   *
   * The fallback belongs on this side, not on the server. `verify.ts` already types the sibling field as
   * `ruleLabel?: string | null`, and `PlaybackChangePopover`, `SimulationTimeline` and `Board.vue` all render
   * `rule.ruleLabel?.trim() || t('app.ruleNumber', …)` — localised. `FixResultDialog` uses
   * `t('app.noDescription')` instead of a rule number, because that row already renders a numbered badge beside
   * the label; repeating the number there would read as a rendering glitch. A server-side English
   * label would have shown a zh-CN user English text, which the bilingual rule in `CLAUDE.md` forbids.
   */
  ruleString?: string | null
  transitionNumber: number
  targetDeviceLabel: string
  targetActionLabel: string
  conflicting: boolean
  /** The conflicting rule preview, or null when it has none; the UI supplies the localised fallback. */
  conflictingRuleString?: string | null
  targetEndState?: string
  conflictingEndState?: string
  reasonCode: 'TRIGGERED' | 'CONFLICTING_END_STATES'
  reason: string
  modelTokenSource: ModelTokenSource
}

export interface FaultLocalizationResult {
  traceId: number
  violatedSpecId: string
  sourceModelComplete: boolean
  sourceDisabledRuleCount: number
  sourceSkippedSpecCount: number
  sourceGenerationIssues: ModelGenerationIssue[]
  faultRules: FaultRule[]
  summary: string
  warnings: string[]
}

export type FixStrategyName = 'parameter' | 'condition' | 'remove'

// Numeric condition available for trace-scoped preferred-range selection.
export interface ParameterTarget {
  targetId: string
  attribute: string
  relation: string
  originalValue: string
  lowerBound: number
  upperBound: number
  description: string
  modelTokenSource: ModelTokenSource
}

// §5.1 参数调整结果
export interface ParameterAdjustment extends ParameterTarget {
  newValue: string
}

// §5.2 条件调整结果
export interface ConditionAdjustment {
  action: 'remove' | 'keep' | 'add'
  attribute: string
  targetType: RuleSourceItemType
  description: string
  ruleDescription: string
  deviceLabel: string
  relation?: string
  value?: string
  modelTokenSource: ModelTokenSource
}

// 原规则就已违反、该建议也未改变的规格（验收只要求目标规格通过且不破坏原本满足的规格）
export interface PreexistingViolation {
  specId: string
  templateId: string
  formulaPreview: string
}

// 修复建议。服务端只返回通过前向验证的方案，因此建议本身就是已验证的，不再单独携带标记。
export interface FixSuggestion {
  suggestionToken?: string
  strategy: FixStrategyName
  description: string
  parameterAdjustments: ParameterAdjustment[]
  conditionAdjustments: ConditionAdjustment[]
  removedRuleDescriptions: string[]
  preexistingViolations: PreexistingViolation[]
}

export type FixStrategyAttemptStatus =
  | 'VERIFIED'
  | 'NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE'
  | 'ALL_CANDIDATES_REJECTED'
  | 'INCONCLUSIVE'
  | 'FAILED_MODEL_GENERATION'
  | 'FAILED_SOLVER_EXECUTION'
  | 'SEARCH_BUDGET_EXHAUSTED'
  | 'TIMED_OUT'
  | 'SKIPPED_TIMEOUT'
  | 'SKIPPED_NO_SPEC'
  | 'SKIPPED_NO_PARAMETERIZABLE_VALUES'
  | 'SKIPPED_NO_FAULT_RULES'
  | 'SKIPPED_INCOMPLETE_SOURCE_MODEL'
  | 'SKIPPED_UNSUPPORTED'

export interface FixStrategyAttempt {
  strategy: FixStrategyName
  status: FixStrategyAttemptStatus
  reason: string
  /**
   * Only on VERIFIED: true when the strategy checked every candidate, so its listed suggestions are every
   * minimal repair of that kind; false when the listing limit, the budget or the time share stopped it first.
   */
  alternativesComplete?: boolean | null
}

export type TemplateSnapshotComparison = 'NOT_CHECKED' | 'UNCHANGED' | 'CHANGED' | 'UNAVAILABLE'

// 修复结果
export interface FixResult {
  traceId: number
  violatedSpecId: string
  faultRules: FaultRule[]
  suggestions: FixSuggestion[]
  strategyAttempts: FixStrategyAttempt[]
  fixable: boolean
  sourceModelComplete: boolean
  sourceDisabledRuleCount: number
  sourceSkippedSpecCount: number
  sourceGenerationIssues: ModelGenerationIssue[]
  templateSnapshotComparison: TemplateSnapshotComparison
  summary: string
  warnings: string[]
  parameterTargets: ParameterTarget[]
  unusedPreferredRangeSelections: PreferredRangeSelection[]
}

// 修复请求（可选）
export interface FixRequest {
  strategies?: FixStrategyName[]
  preferredRangeSelections?: PreferredRangeSelection[]
}

// Preferred value range for parameter-adjustment fixes.
// Must match backend PreferredRange DTO: both fields required, inclusive lower ≤ upper.
export interface PreferredRange {
  lower: number
  upper: number
}

// User/API-facing preferred value range target selected from a parameter adjustment.
export interface PreferredRangeSelection extends PreferredRange {
  targetId: string
}

// Apply the exact signed suggestion shown to the user.
export interface FixApplyRequest {
  strategy: FixStrategyName
  suggestion: FixSuggestion
  suggestionToken: string
  preferredRangeSelections?: PreferredRangeSelection[]
}

// Applied-fix result after boardApi maps the authoritative backend RuleDto snapshot.
export interface FixApplyResult {
  applied: boolean
  strategy: FixStrategyName
  /** Apply reuses the run's verification evidence after drift checks; it never re-runs the search. */
  verificationEvidenceReused: boolean
  appliedSuggestion: FixSuggestion
  previousRuleCount: number
  currentRuleCount: number
  message: string
  rules: RuleForm[]
  canUndo: boolean
  canRedo: boolean
}
