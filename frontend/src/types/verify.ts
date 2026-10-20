// src/types/verify.ts

import type { ModelPlaybackScene, RunInitiator } from './model'
import type { ModelRunSnapshot, ModelSemantics } from './modelSemantics'
import type { SpecCondition, Specification } from './spec'
import type { AsyncTaskStatus, TaskProgressStage } from './task'
import type { RunPersistence } from './runPersistence'
import type { AttackScenario } from './attackScenario'
import type { ModelTokenSource } from './modelToken'

/**
 * Run parameters only. The scene is read server-side from the caller's persisted board, so a run
 * always describes the saved board rather than a client-supplied model.
 */
export interface VerificationRequest {
  attackScenario: AttackScenario;
  enablePrivacy: boolean;
}

export interface SpecResult {
  specId: string;
  templateId: string;
  specificationLabel: string;
  formulaPreview: string;
  specificationSnapshot?: SpecificationSnapshot | null;
  formulaKind: 'CTL' | 'LTL';
  outcome: VerificationOutcome;
  expression: string;
}

/** The checked conditions and display identities, never resolved from the current board. */
export interface SpecificationSnapshot {
  id: string;
  templateId: Specification['templateId'];
  aConditions: SpecCondition[];
  ifConditions: SpecCondition[];
  thenConditions: SpecCondition[];
  devices: Record<string, {
    label: string;
    modelTokenSource: ModelTokenSource;
    modeCount: number | null;
  }>;
}

export type VerificationOutcome = 'SATISFIED' | 'VIOLATED' | 'INCONCLUSIVE';

export type ModelGenerationIssueReasonCode =
  | 'RULE_NO_TRIGGER_CONDITIONS'
  | 'RULE_NULL_TRIGGER_CONDITION'
  | 'RULE_UNRESOLVABLE_TRIGGER_CONDITION'
  | 'RULE_NO_RESOLVABLE_TRIGGER_CONDITIONS'
  | 'RULE_PROPERTY_PROPAGATION_UNAVAILABLE'
  | 'RULE_UNRESOLVABLE_COMMAND_ACTION'
  | 'SPEC_NO_CHECKABLE_CONDITIONS'
  | 'SPEC_PRIVACY_MODELING_DISABLED'
  | 'SPEC_UNSUPPORTED_RELATION'
  | 'SPEC_AMBIGUOUS_STATE'
  | 'SPEC_UNDECLARED_SECURITY_PROPERTY'
  | 'SPEC_UNKNOWN_DEVICE'
  | 'SPEC_TEMPLATE_SHAPE_MISMATCH'
  | 'SPEC_VARIABLE_SOURCE_REQUIRED'
  | 'SPEC_INVALID_VALUE'
  | 'SPEC_UNSUPPORTED_CONDITION'
  | 'UNCLASSIFIED_GENERATION_ISSUE';

export interface ModelGenerationIssue {
  issueType: 'RULE_DISABLED' | 'SPECIFICATION_SKIPPED' | string;
  itemLabel: string;
  reasonCode: ModelGenerationIssueReasonCode;
  reason: string;
}

export interface VerificationResult {
  isAttack: boolean;
  attackBudget: number;
  enablePrivacy: boolean;
  modelSemantics: ModelSemantics;
  modelSnapshot: ModelRunSnapshot;
  historyPersistence: RunPersistence;
  outcome: VerificationOutcome;
  modelComplete: boolean;
  traces: Trace[];
  specResults: SpecResult[];
  checkLogs: string[];
  nusmvOutput: string;
  disabledRuleCount: number;
  skippedSpecCount: number;
  generationIssues: ModelGenerationIssue[];
  /*
   * No `runIdForSmv`. It was declared here and never assigned or read — its doc comment claimed it was
   * "filled from historyPersistence.runId", and nothing filled it. The download handlers read
   * `historyPersistence.runId` directly, which is the single source for "is this run addressable".
   */
  /** See `VerificationRun.hasSmvModel`. */
  hasSmvModel?: boolean;
}

export interface TraceEvidence {
  violatedSpecId: string;
  violatedSpec?: Specification;
  checkedExpression: string;
  modelComplete: boolean;
  disabledRuleCount: number;
  skippedSpecCount: number;
  generationIssues: ModelGenerationIssue[];
  states: TraceState[];
  // Verification-context flags derived from the trace's stored request snapshot (backend TraceDto).
  isAttack?: boolean;
  attackBudget?: number;
  enablePrivacy?: boolean;
  modelSemantics?: ModelSemantics;
  modelSnapshot: ModelRunSnapshot;
  playbackScene: ModelPlaybackScene;
  createdAt: string;
}

export interface ImmediateTrace extends TraceEvidence {
  id?: never;
  verificationTaskId?: never;
}

export interface PersistedTrace extends TraceEvidence {
  id: number;
  verificationTaskId: number;
  /**
   * Whether the backend still holds the SMV model this run checked. The content itself is never sent
   * (it runs to tens of thousands of characters), so this is the only way to know whether the download
   * can succeed — a trace persisted before the model was stored has none, and offering the button
   * anyway produced a failed download the user could not explain.
   */
  hasSmvModel?: boolean;
}

export type Trace = ImmediateTrace | PersistedTrace;

export interface AvailableTraceSummary {
  id: number
  verificationTaskId: number
  violatedSpecId: string
  violatedSpec: Specification
  modelTokenSourcesByDeviceId: Record<string, ModelTokenSource>
  stateCount: number
  createdAt: string
  dataAvailable: true
  // No `hasSmvModel`: the model is addressed by run, so the flag lives on the run summary this
  // counterexample is nested under. It was briefly declared here to gate a per-counterexample
  // download that no longer exists, and the backend no longer sends it.
}

export interface UnavailableTraceSummary {
  id: number
  verificationTaskId: number
  violatedSpecId?: string
  createdAt?: string
  dataAvailable: false
  unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID' | string
}

export type TraceSummary = AvailableTraceSummary | UnavailableTraceSummary

export interface TraceState {
  stateIndex: number;
  devices: TraceDevice[];
  triggeredRules: TraceTriggeredRule[]; // rule snapshots that drove the transition into this state
  compromisedAutomationLinks: TraceTriggeredRule[]; // rule delivery links selected as compromised
  trustPrivacies?: TraceTrustPrivacy[]; // state-level trust/privacy entries (backend List<TraceTrustPrivacyDto>)
  envVariables?: TraceVariable[];       // board environment variables using user-facing names (e.g. temperature)
  globalVariables?: TraceVariable[];    // NuSMV runtime/global variables, e.g. attack count
  // This state begins the repeating cycle of an infinite counterexample. A liveness property (templates 2,
  // 5, 6) is refuted by a lasso path, not a finite prefix, so the cycle *is* the violation.
  loopStart?: boolean;
  // This state closes the cycle by repeating `loopStart`, so it always equals the entry state. Whether it
  // also equals its own predecessor depends on the cycle length: a one-state cycle prints no variable lines
  // and plays back as a step where nothing changes, while a longer cycle prints the deltas back to the entry
  // and plays back as an ordinary step. Both hide the repetition, which is why the flag is carried.
  loopBack?: boolean;
}

export interface TraceTriggeredRule {
  ruleIndex: number;
  ruleId?: string | null;
  ruleLabel?: string | null;
}

export interface TraceDevice {
  deviceId: string;
  deviceLabel: string;
  templateName: string;
  modelTokenSource: ModelTokenSource;
  state?: string;
  mode?: string;                       // 新增：状态机名称
  compromised?: boolean;
  variables: TraceVariable[];
  trustPrivacy?: TraceTrustPrivacy[];   // 改为可选
  privacies?: TraceTrustPrivacy[];      // 改为可选
}

export interface TraceVariable {
  name: string;
  value: string;
  trust?: string | null;    // 改为可选
  // False when `value` is not a reading this device took: an affect-only shared declaration
  // (IsInside=false, Reads=false) is declared but never constrained, so the model has no value to
  // report and `value` is empty. The true shared value is in the state's envVariables[].
  // Absent on a trace persisted before this field existed; treat a missing flag as observed.
  observed?: boolean;
  modelTokenSource: ModelTokenSource;
}

export interface TraceTrustPrivacy {
  name: string;
  propertyScope: 'state' | 'variable' | 'content';
  mode?: string | null;
  trust?: boolean | null;   // 后端 Boolean 包装类型，支持 true/false/null
  privacy?: string | null;
}

export interface VerificationTask {
  id: number;
  initiator: RunInitiator;
  // userId 已删除 — 后端不返回，前端无使用处
  status: AsyncTaskStatus;
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
  processingTimeMs?: number;
  progress?: number;       // 新增：0-100 进度
  progressStage?: TaskProgressStage;
  isAttack: boolean;
  attackBudget: number;
  enablePrivacy: boolean;
  modelSemantics: ModelSemantics;
  modelSnapshot: ModelRunSnapshot;
  outcome?: VerificationOutcome;
  modelComplete?: boolean;
  violatedSpecCount?: number;
  checkLogs?: string[];
  disabledRuleCount?: number;
  skippedSpecCount?: number;
  generationIssues?: ModelGenerationIssue[];
  specResults?: SpecResult[];
  nusmvOutput?: string;
  /**
   * Whether this run still holds its SMV model. A completed async task *is* the run, so this is the
   * flag the polling client reads to decide whether to offer the download — see
   * `VerificationRun.hasSmvModel`.
   */
  hasSmvModel?: boolean;
  errorMessage?: string;
}

export type VerificationTaskSummary = Pick<
  VerificationTask,
  | 'id'
  | 'initiator'
  | 'status'
  | 'createdAt'
  | 'startedAt'
  | 'completedAt'
  | 'processingTimeMs'
  | 'progress'
  | 'progressStage'
  | 'isAttack'
  | 'attackBudget'
  | 'enablePrivacy'
  | 'modelSemantics'
  | 'modelSnapshot'
  | 'outcome'
  | 'modelComplete'
  | 'violatedSpecCount'
  | 'disabledRuleCount'
  | 'skippedSpecCount'
  | 'generationIssues'
  | 'errorMessage'
>

export interface AvailableVerificationRunSummary {
  id: number
  initiator: RunInitiator
  createdAt: string
  startedAt: string
  completedAt: string
  processingTimeMs?: number
  isAttack: boolean
  attackBudget: number
  enablePrivacy: boolean
  modelSemantics: ModelSemantics
  modelSnapshot: ModelRunSnapshot
  outcome: VerificationOutcome
  modelComplete: boolean
  violatedSpecCount: number
  counterexampleCount: number
  disabledRuleCount: number
  skippedSpecCount: number
  generationIssues: ModelGenerationIssue[]
  counterexamples: TraceSummary[]
  dataAvailable: true
  hasSmvModel?: boolean
}

export interface UnavailableVerificationRunSummary {
  id: number
  initiator: RunInitiator
  createdAt?: string
  startedAt?: string
  completedAt?: string
  processingTimeMs?: number
  counterexampleCount: number
  counterexamples: TraceSummary[]
  dataAvailable: false
  unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID' | string
}

export type VerificationRunSummary = AvailableVerificationRunSummary | UnavailableVerificationRunSummary

export interface VerificationRun extends Omit<AvailableVerificationRunSummary, 'dataAvailable'> {
  specResults: SpecResult[]
  checkLogs: string[]
  nusmvOutput: string
  /**
   * Whether the backend still holds this run's SMV model, gating
   * `GET /api/verify/runs/{id}/smv`. Keyed on the run because all of its counterexamples share one
   * model — and because a run where every specification holds has no counterexample to key on, which
   * is exactly when a reader wants to confirm what was proved.
   */
  hasSmvModel?: boolean
}
