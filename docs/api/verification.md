# Verification, Simulation & Fix API

Field-level contract for the verification, simulation, and auto-fix endpoints. This
document owns the DTO detail for these endpoints; the endpoint list itself lives in
[rest-endpoints.md](rest-endpoints.md).

Responses are wrapped in the standard `Result<T>` envelope (authoritative definition:
[overview.md](overview.md)). The `data` shapes below are what appears under that
envelope's `data` field.

Verified against code on 2026-08-01. Source:
`controller/VerificationController.java`, `controller/SimulationController.java`,
and the DTOs under `dto/verification/`, `dto/simulation/`, `dto/device/`,
`dto/rule/`, `dto/spec/`, `dto/trace/`, `dto/fix/`.

---

## 1. Verification

### `POST /api/verify` — synchronous

**Request body**: `VerificationRequestDto`

This endpoint consumes the **model-boundary** shape. Device references inside
`RuleDto` and `SpecificationDto` must already match `devices[].varName` (the
NuSMV-safe normalized form of the board `DeviceNode.id`). Board storage endpoints use
raw board node ids for the same DTO fields; the frontend `modelRequest.ts` builder and
backend `BoardDataConverter` perform the boundary conversion before verification or
simulation.

The request is strict at every nesting level. Unknown fields and scalar type coercions
return HTTP `400`; a misspelled `attackScenario`, `enablePrivacy`, device label, rule field, or
environment override is never ignored and cannot silently weaken the model. DTO shape
constraints also return structured HTTP `400` errors; model/template semantic mismatches
discovered after parsing return `422`.

**The request carries run parameters only — the scene comes from your saved board.** Sending
`devices`, `rules`, `specs`, `environmentVariables`, or `playbackNodes` returns HTTP `400`. The
server reads those from the caller's own persisted board so a run always describes the board the
user saved; while they were client input, an account with an empty board could post a fabricated
scene and have the resulting `VIOLATED` verdict stored in its own run history. A board with no
devices, or none with a specification, returns `422` naming what to add.

| Field | Type | Required | Default | Notes |
| :--- | :--- | :--- | :--- | :--- |
| `attackScenario` | `AttackScenarioDto` | yes | — | Explicit per-run attack selection, independent from persistent trust labels. `mode` is required. Verification accepts `NONE`, `EXACT_POINTS`, or `ANY_UP_TO_BUDGET`. Exact mode requires `1..50` explicit points and no budget. Exhaustive mode requires budget `1..50`, no explicit points, and a budget no greater than the current behavior-changing attack surface. |
| `enablePrivacy` | `boolean` | no | `false` | Adds privacy-label variables and enlarges state space. Any privacy condition in a submitted specification makes the effective value `true`, even if the caller omitted or set this field to `false`; responses return the effective value |

> Note: there is **no** `saveTrace` field. Traces are saved automatically when a
> violation is detected.
> Verification requires at least one specification for both synchronous and asynchronous
> requests. Use the simulation endpoint for no-spec state exploration.

Each user may run only one synchronous verification, simulation, or automatic-fix search
at a time. Redis coordinates the admission across backend instances; when Redis is down,
the same rule remains process-local. A conflicting request returns `429` before model
parsing or NuSMV execution with `data.reasonCode=USER_FORMAL_OPERATION_BUSY`, plus
`operationKind`, coordination `scope`, and `limit`. Async endpoints retain their separate
stored/active task quotas.

Admission is enforced at the verification, simulation, and fix service boundary, not only
in REST controllers. Assistant tools and other internal callers therefore cannot bypass the
same per-user formal-work limit. If an admitted worker is interrupted by cancellation,
shutdown, or loss of its distributed lease, it cancels nested solver futures and terminates
the NuSMV process tree before releasing capacity; interruption is not persisted as a normal
completed history result.

`AttackScenarioDto.points` contains either `{ kind: "DEVICE", deviceId }`, where
`deviceId` is the normalized model-boundary device id, or
`{ kind: "AUTOMATION_LINK", ruleId }`, where `ruleId` is the submitted persisted rule
identity. Exact points must belong to the current effective attack surface and are fixed
for the entire run. `ANY_UP_TO_BUDGET` is verification-only and asks NuSMV to explore
every modeled selection from zero through the selected upper bound. Persistent
`trusted`/`untrusted` labels remain model inputs and never implicitly select attack
points.

Completed run semantics expose exact selections as `{ kind, deviceId?, ruleId?,
displayLabel? }`. `deviceId`/`ruleId` remain the stable identities; `displayLabel` is a
display-only name captured from the submitted device or rule so history does not depend
on the mutable current Board.

Requests use only the structured `attackScenario`. Obsolete top-level `isAttack` and
`attackBudget` request fields are unknown fields and return HTTP `400`; callers must state
`NONE`, `EXACT_POINTS`, or verification-only `ANY_UP_TO_BUDGET` explicitly. Run responses
still expose derived `isAttack` and `attackBudget` summary fields for convenient display.

**Response**: `VerificationResultDto`

| Field | Type | Notes |
| :--- | :--- | :--- |
| `isAttack` | `Boolean` | Whether compromised device-instance and automation-link behavior was included |
| `attackBudget` | `int` | Derived run size: exhaustive mode returns its upper bound; exact mode returns the fixed selected-point count; no-attack mode returns `0` |
| `enablePrivacy` | `boolean` | Whether privacy-label propagation was modeled |
| `modelSemantics` | `ModelSemanticsDto` | Machine-readable environment-evolution, local-variable, attack, trust, and privacy assumptions required to interpret the conclusion |
| `modelSnapshot` | `ModelRunSnapshotDto` | User-facing scope captured at the model boundary: item counts, confirmation that referenced template manifests were frozen for this run, and the per-shared-value evolution rules that keep a stored trace explainable |
| `outcome` | `SATISFIED \| VIOLATED \| INCONCLUSIVE` | User-facing conclusion. `INCONCLUSIVE` means no reliable property conclusion was produced; it is not a violation. |
| `modelComplete` | `boolean` | Whether every submitted rule/spec entered the generated model and the emitted result set was parsed reliably |
| `traces` | `TraceDto[]` | Counterexample traces for parsed property violations. An `INCONCLUSIVE` run can retain traces parsed before another result became incomplete; clients must present them as partial evidence, not as a complete verdict. |
| `specResults` | `SpecResultDto[]` | Per-emitted-spec result objects; specs skipped before SMV emission are counted/logged separately |
| `checkLogs` | `String[]` | Human-readable check log |
| `nusmvOutput` | `String` | NuSMV diagnostic output retained for storage/display. Values longer than 10,000 characters contain the first 10,000 characters plus an explicit truncation marker. Verdicts, per-spec results, and traces are parsed before this presentation cap is applied. **This is the only channel by which anything NuSMV says other than `-- specification … is true/false` can reach a user** — the trace parser models no other line — so the client must render it. It appears in the verification result dialog's run-context section, guarded by `frontend/src/views/board/solverOutputVisibility.spec.ts` after a refactor once deleted the disclosure and a later sweep deleted its now-unreferenced label. |
| `disabledRuleCount` | `Integer` | Count of rules whose generated guard failed closed to `FALSE` |
| `skippedSpecCount` | `Integer` | Count of specs omitted because no valid NuSMV property could be generated |
| `generationIssues` | `ModelGenerationIssueDto[]` | Item-level name and reason for every disabled rule or omitted specification |
| `historyPersistence` | `RunPersistenceDto` | Separate status for adding this completed result to run history; it does not change the formal outcome |

Every synchronous verification attempts to persist its completed result as one run.
The response keeps the model-checking conclusion even when that separate persistence
step fails. `historyPersistence.status=SAVED` includes the authoritative `runId`.
Only that status may expose trace `id` and `verificationTaskId`, and every returned
counterexample must identify the same owning `runId`. `FAILED` and `OUTCOME_UNKNOWN`
responses omit both trace identity fields, including when an exception occurred after
the transaction callback assigned provisional database ids in memory.
`OUTCOME_UNKNOWN` with `reasonCode=RUN_HISTORY_SAVE_OUTCOME_UNKNOWN` means the formal
result is still usable, but the client must refresh history before deciding whether a
row exists; it must not retry the verification automatically or claim that the result
was saved. Counterexamples and the completed run are written in one transaction, so a
confirmed saved run cannot contain only part of its counterexamples. Requests rejected
before a result exists do not create a history row.

Every distributed formal-operation admission also claims a monotonically increasing
database fencing epoch for that user before expensive work starts. In the final
transaction's `beforeCommit` phase, the server locks the user's epoch row through physical
commit, requires the claimed epoch to remain current, and rechecks the Redis lease. A newer
claim either supersedes an old transaction before it reaches the fence or waits for the
old physical commit before starting its work. Expiry or supersession therefore rolls back
history persistence instead of publishing stale or duplicate evidence, and the request
fails with service-unavailable semantics. Ordinary persistence failures that do not prove
ownership loss may still preserve the completed in-memory result with an explicit failed
or outcome-unknown history-persistence status.

The signed fix-application endpoint uses the same admission and commit fence as verification
and simulation. Its atomic rule mutation registers that fence inside the board-write transaction,
so an expired or superseded formal-operation lease cannot publish a stale repair.

`RunPersistenceDto` is `{ status, runId?, reasonCode? }`. `status` is `SAVED`,
`NOT_REQUESTED`, `FAILED`, or `OUTCOME_UNKNOWN`. `NOT_REQUESTED` is used by preview-only
simulation; `FAILED` means the server knows no history row was created; and
`OUTCOME_UNKNOWN` means the write outcome could not be confirmed after an exception.

`ModelSemanticsDto` also carries the immutable run-snapshot counts
`modeledDeviceAttackPointCount`, `modeledFalsifiableReadingDeviceCount`,
`modeledAutomationLinkAttackPointCount`, and `modeledAttackPointCount` (the device/link
sum). The falsifiable-reading count is a subset of the device count; the device count
includes only instances whose
compromise can change behavior: a declared falsifiable reading and/or an incoming rule
command. Clients must use these values when explaining an
attack budget or historical trace; recomputing the denominator from the current board can
misdescribe an older run after devices or rules change.

To estimate the minimum number of compromise points needed for a violation, callers
must run complete verification repeatedly with different `attackScenario.budget` upper bounds
under `ANY_UP_TO_BUDGET`.
The API does not present the selected upper bound or a counterexample's runtime count as
the minimum attack intensity.

Generation warnings are also appended to `checkLogs` with `[rule-disabled]` and
`[spec-skipped]` markers for technical diagnostics. `generationIssues` is the
user-facing explanation source; clients must render each `itemLabel` and localize its
stable `reasonCode`. The English `reason` is a technical diagnostic and must not be used
as ordinary localized UI copy.
`SATISFIED` with `modelComplete=false` means the emitted properties were satisfied on
a reduced generated model; clients must present it as "checked properties satisfied,
but model incomplete". If no specification is emitted, or the emitted result set
cannot be mapped reliably, the backend returns `outcome=INCONCLUSIVE`,
`modelComplete=false`, and a `[verification-inconclusive]` log. Only a reliable false
property result produces `outcome=VIOLATED`.

The frontend treats this response as an authoritative conclusion contract rather than a
best-effort payload. Before showing a successful, violated, or inconclusive result it
requires explicit `outcome` and `modelComplete`, consistent run-context
`modelSemantics`, a frozen `modelSnapshot`, all result/log/trace arrays, and itemized `generationIssues` matching
the omission counts. A successful HTTP response missing those fields is shown as an
uninterpretable run error; the client does not infer completeness from absent warnings.
The same check is applied to persisted counterexamples. A completed async task must
contain the complete result fields before the Board reconstructs its result; it is not
allowed to infer the task outcome from traces or logs.

`specResults` contains only specifications actually emitted to NuSMV, in emitted order.
Each item carries its own `specId`, so clients must not infer identity from array index
alone. When output parsing is incomplete, missing emitted specs may still appear with
`outcome=INCONCLUSIVE` for diagnostic alignment. They must not be presented as proven
violations. A specification that generation cannot translate is omitted rather than
replaced by an always-false property, so it cannot create an artificial violation or
counterexample.

### `POST /api/verify/async` — asynchronous

Same request body, including the non-empty `specs` constraint. **Response `data`** is
the newly created `VerificationTaskDto`, not a bare id. Its `id` is the server-generated
polling identity; `status`, `progress`, `progressStage`, `modelSemantics`, and `modelSnapshot` are the
authoritative values already persisted for the accepted task. A successful submission
means only that the task was accepted. It does not mean verification completed, and
active tasks do not contain a property `outcome`.

Persisted verification tasks/runs and simulation tasks/traces expose `initiator` with one
of three values: `USER` for a direct REST/UI command, `AI_ASSISTANT` for a tool executed
inside an owned chat turn, and `UNKNOWN` when provenance is unavailable. Clients must not
render `UNKNOWN` as user-authored. Task summaries, task detail, completed-run summaries,
and completed-run detail preserve the same value; unavailable history placeholders retain
the known value or use `UNKNOWN` rather than guessing.

Async submission snapshots the request and validates it before creating a task. It also
captures every referenced device-template manifest once. Validation and later worker
generation reuse that exact captured set; editing a template while the task is queued
cannot change the model behind the accepted task. REST
calls first run full DTO Bean Validation (HTTP 400 for malformed request shapes);
service and AI-tool callers run the NuSMV runtime validation needed for execution
(`devices`/`specs` present, null list items rejected, device identity, specification id
and template id, specification conditions, `attackScenario`, etc.). After structural
validation, the backend loads the current user's device templates and rejects
template-semantic mismatches before NuSMV generation: non-signal APIs cannot be used
as rule/spec conditions, command actions must exist on the target template, and
state/mode/trust/privacy condition keys and enum values must be legal. Variable
conditions are also checked against template domains: declared enum `Values` and
numeric `LowerBound..UpperBound`. Validation failures are
returned before task creation (`ValidationException`, HTTP/tool status 422), so no
`taskId` is returned and clients must not start polling. If dispatch fails after task
creation, the backend conditionally removes the still-`PENDING` row owned by that worker
before returning an error, so a definitive failed submit consumes no stored-task quota.
Queue rejection maps to `503 ServiceUnavailableException`; another runtime dispatch
failure keeps its normal error mapping. If removal cannot be confirmed, the `503` response
contains `data={ reasonCode: "TASK_DISPATCH_OUTCOME_UNKNOWN", taskKind, taskId }` and
clients must reconcile that task before retrying instead of creating a possible duplicate.

Before insertion, the service atomically enforces the configured per-user stored-task
and active-task limits. HTTP 429 returns structured quota data. The stable reason codes
are `VERIFICATION_ACTIVE_TASK_LIMIT_REACHED` and
`VERIFICATION_STORED_TASK_LIMIT_REACHED`; clients should localize these codes and use
the returned counts rather than parsing the English message.

An async task reaches `progress=100` only in the same atomic completion operation that
stores its final result and counterexamples. If that completion write does not commit,
the task is marked `FAILED` when possible; clients must never interpret an earlier 100%
progress update as a completed result. The expired-lease sweep therefore leaves `progress`
untouched when it fails an abandoned task: overwriting it with 100 would publish a
completed-work claim for work that stopped partway.

Every accepted verification task is owned by one backend instance through a renewable
database lease, including its time in the executor queue. The two-minute lease is renewed
every ten seconds using the database clock. Worker start and renewal require the same
worker id and a still-unexpired lease; an expired queued or running worker cannot revive
its row. Startup and rolling deployment maintenance therefore leave another instance's
live task untouched and atomically mark only expired `PENDING`/`RUNNING` rows `FAILED`.
Worker completion and failure also require that same live ownership, while user cancellation
remains authoritative regardless of the worker. Lease, start, and terminal transitions use the
database clock and clear ownership on a committed terminal state. Local queued/running work is stopped
when renewal proves that this instance no longer owns it.

Progress updates are fenced by that same worker id and unexpired lease. Terminal transactions
lock the task row before sampling the microsecond database time and before persisting linked
verification counterexamples or simulation traces. This prevents a lock-delayed worker from
publishing stale evidence and keeps creation/completion ordering valid even for sub-second tasks.

### `GET /api/verify/tasks` — verification task status layer

Optional query parameter: `excludeTaskIds=1,2,3`. Use it when the frontend is already
polling specific task ids through `GET /api/verify/tasks/{id}` and wants the inbox
summary refresh to skip those same tasks. The list accepts at most 100 positive ids.

**Response**: `VerificationTaskSummaryDto[]`, ordered by `createdAt` descending.

This list contains only background work that still needs task-level attention:
`PENDING`, `RUNNING`, `FAILED`, or `CANCELLED`. `COMPLETED` tasks are deliberately
excluded because they are user results, not pending work; retrieve them through
`GET /api/verify/runs`. Active rows expose progress and frozen run context. Failed and
cancelled rows explain why no result exists. Heavy result details remain available from
the per-id polling endpoint while a client finishes its own accepted task. The ordered
inbox query uses a closed summary projection and does not select check logs, specification
results, or solver output.

`DELETE /api/verify/tasks/{id}` dismisses a `FAILED` or `CANCELLED` task that produced
no result. Active tasks must be cancelled first. Completed rows must be deleted through
the run-history endpoint so their counterexamples are removed atomically.

### Completed verification runs

- `GET /api/verify/runs` returns every retained `VerificationRunSummaryDto` item, up to
  the configured `VERIFICATION_MAX_STORED_TASKS_PER_USER` bound, ordered by completion
  time descending. It includes run scope/context,
  `outcome`, `modelComplete`, omission
  counts and itemized `generationIssues`, `violatedSpecCount`, `counterexampleCount`,
  and lightweight nested `counterexamples` summaries. It does not return trace states.
- `GET /api/verify/runs/{id}` returns `VerificationRunDto`, adding `specResults`,
  `checkLogs`, and `nusmvOutput` without task lifecycle fields such as status/progress.
- `GET /api/verify/runs/{id}/traces` returns the run's replayable `TraceDto[]`.
- `GET /api/verify/traces/{id}` returns one replayable `TraceDto` with its frozen request,
  model snapshot, and playback scene. A selected history row or
  `run=verification:<runId>&trace=<traceId>` link must use this leaf endpoint and verify that
  `verificationTaskId` equals the named `runId`; sibling traces must not be required to decode.
- `DELETE /api/verify/runs/{id}` deletes the complete result and all linked
  counterexamples in one transaction.

An `INCONCLUSIVE` run can still have a nonzero `counterexampleCount`: a parsed property
violation and its trace may be retained before another emitted property is missing or
unreliable. The overall conclusion remains `INCONCLUSIVE`; clients may expose that trace
for replay or repair only with a clear partial-evidence qualifier.

`VerificationRunSummaryDto` fields:

| Field | Type | Meaning |
| :--- | :--- | :--- |
| `id` | `Long` | Stable run identity used only for result actions/API correlation |
| `initiator` | `RunInitiator` | `USER`, `AI_ASSISTANT`, or `UNKNOWN` persisted when the run was created |
| `createdAt` / `startedAt` / `completedAt` | `LocalDateTime` | Run timestamps; there is no task status in this completed-result DTO |
| `processingTimeMs` | `Long` | Elapsed processing time when available |
| `isAttack` / `attackBudget` / `enablePrivacy` | `Boolean` / `Integer` / `Boolean` | Frozen verification assumptions |
| `modelSemantics` / `modelSnapshot` | DTOs | Structured model meaning and submitted scope |
| `outcome` / `modelComplete` | enum / `Boolean` | Verification conclusion and completeness qualifier |
| `violatedSpecCount` | `Integer` | Reliably false emitted specifications |
| `counterexampleCount` | `Integer` | Persisted traces whose history-summary metadata validates; damaged summary placeholders are excluded and the count may be lower than `violatedSpecCount` or `counterexamples.length` |
| `disabledRuleCount` / `skippedSpecCount` | `Integer` | Model omissions |
| `generationIssues` | `ModelGenerationIssueDto[]` | Itemized omission explanations |
| `counterexamples` | `TraceSummaryDto[]` | Lightweight nested evidence with id, violated-specification snapshot, state count and timestamp — no model flag, because the model is run-level and this run's own `hasSmvModel` answers for all of them; the history query does not read the full states or frozen request, and damaged summary fields remain as `dataAvailable=false` placeholders |
| `hasSmvModel` | `Boolean` | Whether the run still holds the model it checked, gating the per-run download; see [Downloading the SMV model](#downloading-the-smv-model--one-model-per-run-addressed-by-run) |
| `dataAvailable` | `Boolean` | `true` when the persisted summary fields decoded successfully; full state and frozen-request integrity is checked when detail is opened |
| `unavailableReasonCode` | `String` | Present for an unavailable row; currently `PERSISTED_SEMANTIC_DATA_INVALID` |

`VerificationRunDto` contains the run metadata and semantic result fields plus
`specResults`, `checkLogs`, and `nusmvOutput`. It reports `counterexampleCount` but does
not embed counterexample summaries or full trace states; load those from
`GET /api/verify/runs/{id}/traces`.

`violatedSpecCount` and `counterexampleCount` are intentionally separate. NuSMV can
report a property as false without returning a saved trace summary;
clients must say how many violations were concluded and how many counterexample records
were retained. Both run-list and run-detail responses derive this count from the same
bounded lightweight trace projections; a known-damaged summary remains visible in the
summary array but is not counted. A verification run is the top-level history item. Its
counterexamples are evidence/actions nested under that result, not independent runs.
One malformed run or trace summary does not fail the whole history list. The backend
returns an unavailable placeholder with its stable id/timestamp where possible. Clients
may offer deletion, but must disable open, replay, and repair actions for that item.
Trace summary queries return only ownership, the violated-specification snapshot, persisted
state count, and timestamps. Mapping validates the owning task id, snapshot, and positive
count. They deliberately exclude `statesJson` and `requestJson`, so sorting history cannot
materialize full trajectories.
When a user opens, replays, or repairs a trace, the detail path parses the saved states,
requires a non-empty contiguous one-based sequence, matches the scalar count, validates the
frozen request's rule count, and binds every triggered-rule or compromised-link entry to
the exact frozen rule at `ruleIndex`. Detail corruption fails explicitly; it is never
replaced with empty or guessed evidence. A damaged sibling can make the aggregate
`/runs/{id}/traces` view unavailable, but it never invalidates a different trace's own detail
endpoint or prevents that independently validated evidence from replaying.

### `GET /api/verify/tasks/{id}` — task status

**Response**: `VerificationTaskDto`

| Field | Type | Notes |
| :--- | :--- | :--- |
| `id` | `Long` | Task id |
| `initiator` | `RunInitiator` | Persisted origin of the accepted task |
| `status` | `String` | `PENDING` / `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED` |
| `createdAt` / `startedAt` / `completedAt` | `LocalDateTime` | Lifecycle timestamps |
| `processingTimeMs` | `Long` | |
| `isAttack` / `attackBudget` / `enablePrivacy` | `Boolean` / `Integer` / `Boolean` | Stored run context, not the current frontend form |
| `modelSemantics` | `ModelSemanticsDto` | Structured assumptions for this task's model |
| `modelSnapshot` | `ModelRunSnapshotDto` | Frozen submission scope, available from task creation onward rather than only after completion |
| `outcome` | `VerificationOutcome` | `SATISFIED`, `VIOLATED`, or `INCONCLUSIVE` once completed |
| `modelComplete` | `Boolean` | Whether the completed task checked the complete generated model reliably |
| `violatedSpecCount` | `Integer` | Number of explicit `VIOLATED` structured `specResults` once completed; falls back to trace count when no per-spec results are available |
| `disabledRuleCount` | `Integer` | Completed-task copy of generation-disabled rule count |
| `skippedSpecCount` | `Integer` | Completed-task copy of omitted specification count |
| `generationIssues` | `ModelGenerationIssueDto[]` | Item-level omitted-rule/specification explanations |
| `specResults` | `SpecResultDto[]` | Per-emitted-spec result objects once completed |
| `checkLogs` | `String[]` | Generation warnings and NuSMV execution/check logs when available (`COMPLETED` or `FAILED`) |
| `nusmvOutput` | `String` | The same capped NuSMV diagnostic output as a synchronous result, once completed |
| `errorMessage` | `String` | Technical failure diagnostic present on `FAILED`; clients show a localized no-result state first and place this text in an advanced/technical disclosure |
| `progress` | `Integer` | 0–100 |
| `progressStage` | `TaskProgressStage` | Server-observed active phase. Verification emits exactly five: `QUEUED`, `STARTING`, `GENERATING_MODEL`, `EXECUTING_MODEL_CHECKER`, `PARSING_RESULTS`. It never emits `PERSISTING_RESULT` — that member belongs to simulation and fuzz — so verification stays on `PARSING_RESULTS` from 80% through completion. Do not render a persistence phase for a verification run |

`@JsonInclude(NON_NULL)` — null fields are omitted. Completed async verification
tasks carry the same conclusion and per-spec fields as synchronous verification:
`outcome`, `modelComplete`, `specResults`, `checkLogs`, `nusmvOutput`,
`disabledRuleCount`, `skippedSpecCount`, and `generationIssues`.
Failed async tasks may still carry `checkLogs` for the steps reached before failure.
Task fields are status-dependent: active tasks do not publish `outcome` or
`modelComplete`; terminal tasks have `completedAt`, and only `COMPLETED` has `progress=100` —
a `FAILED` or `CANCELLED` task keeps the progress its last heartbeat reported, because it did not
finish the work and `status` is what reports the outcome; timestamps cannot
precede creation or start; processing duration cannot be negative or exist without both
boundary timestamps; `FAILED` has a non-blank `errorMessage`; and `COMPLETED` has a start
time, processing duration, non-negative result counts, and the complete conclusion fields above.
The frontend validates these invariants before it renders an inbox row or reconstructs
a result. A malformed HTTP 200 is an uninterpretable task response, not a poll timeout
or evidence that the run completed.
`progress` and `progressStage` are persisted by the same atomic active-task update, so
clients do not combine a percentage from one phase with a label from another phase.

### `SpecResultDto`

| Field | Type | Notes |
| :--- | :--- | :--- |
| `specId` | `String` | Stable technical `SpecificationDto.id` for correlation; ordinary result UI keeps it in technical details |
| `templateId` | `String` | Submitted specification-template semantics captured for this run |
| `specificationLabel` | `String` | User-facing template label captured for self-contained result interpretation |
| `formulaPreview` | `String` | Descriptive formula rebuilt from the submitted structured conditions, submitted device labels, and frozen template semantics; contains user concepts rather than generated NuSMV identifiers |
| `formulaKind` | `CTL \| LTL` | Language of the emitted property, derived from the actual emitted expression/template |
| `outcome` | `SATISFIED \| VIOLATED \| INCONCLUSIVE` | Per-property conclusion. Missing or unreliable parser output is `INCONCLUSIVE`, never a synthetic violation. |
| `expression` | `String` | Actual NuSMV CTL/LTL expression checked for this emitted specification; technical detail that may contain generated identifiers |

### `ModelGenerationIssueDto`

| Field | Type | Notes |
| :--- | :--- | :--- |
| `issueType` | `RULE_DISABLED \| SPECIFICATION_SKIPPED` | Kind of model omission |
| `itemLabel` | `String` | User-readable automation or specification label; not a database/NuSMV id |
| `reasonCode` | `ModelGenerationIssueReasonCode` | Stable localization key describing why the item did not enter the generated model |
| `reason` | `String` | English technical diagnostic for logs/advanced details; clients localize ordinary UI from `reasonCode` |

`ModelGenerationIssueReasonCode` is one of
`RULE_NO_TRIGGER_CONDITIONS`, `RULE_NULL_TRIGGER_CONDITION`,
`RULE_UNRESOLVABLE_TRIGGER_CONDITION`, `RULE_NO_RESOLVABLE_TRIGGER_CONDITIONS`,
`RULE_PROPERTY_PROPAGATION_UNAVAILABLE`, `RULE_UNRESOLVABLE_COMMAND_ACTION`,
`SPEC_NO_CHECKABLE_CONDITIONS`,
`SPEC_PRIVACY_MODELING_DISABLED`, `SPEC_UNSUPPORTED_RELATION`,
`SPEC_AMBIGUOUS_STATE`, `SPEC_UNDECLARED_SECURITY_PROPERTY`,
`SPEC_UNKNOWN_DEVICE`, `SPEC_TEMPLATE_SHAPE_MISMATCH`, `SPEC_VARIABLE_SOURCE_REQUIRED`, `SPEC_INVALID_VALUE`,
`SPEC_UNSUPPORTED_CONDITION`, or `UNCLASSIFIED_GENERATION_ISSUE`.

### `ModelSemanticsDto`

| Field | Values / meaning |
| :--- | :--- |
| `attackPointUnit` | `BEHAVIOR_CHANGING_DEVICE_INSTANCE_OR_AUTOMATION_LINK`; a device contributes a point only when it has a declared falsifiable reading or is a rule-command target, and each submitted rule contributes one logical command-delivery link point |
| `attackSelectionPolicy` | `NOT_MODELED`, `EXACT_ATTACK_POINTS`, or `UP_TO_ATTACK_BUDGET_NONDETERMINISTIC` |
| `selectedAttackPoints` | Exact device/rule-link snapshots for `EXACT_ATTACK_POINTS`, each with stable `kind` plus `deviceId` or `ruleId` and an optional frozen display-only `displayLabel`; empty for the other policies |
| `attackEffects` | Empty when attack modeling is off. When enabled, contains only effects that this scene can exercise: `DECLARED_FALSIFIABLE_READING_NONDETERMINISTIC_WITHIN_DECLARED_DOMAIN` when at least one device has such a declaration, and the two deterministic command-drop effects when at least one submitted automation link/target exists. It is not a fixed capability list. |
| `modeledDeviceAttackPointCount` | Distinct device instances whose compromise can change this model: the union of devices with a falsifiable reading and devices that receive a rule command |
| `modeledFalsifiableReadingDeviceCount` | Subset of the preceding count whose declared readings may be replaced nondeterministically within their domains; this is not added again to the total |
| `modeledAutomationLinkAttackPointCount` | Logical rule command-delivery points in the submitted model: one per submitted rule, not one per canvas trigger edge or physical network segment |
| `modeledAttackPointCount` | `modeledDeviceAttackPointCount + modeledAutomationLinkAttackPointCount` |
| `trustPropagationPolicy` | `TARGET_UNTRUSTED_IF_ALL_TRIGGER_SOURCES_UNTRUSTED`; under MEDIC's retained-control interpretation, one trusted contributing trigger source keeps the target event trusted, and the target becomes untrusted only when all contributing trigger sources are untrusted |
| `privacyPropagationPolicy` | `TARGET_PRIVATE_IF_ANY_TRIGGER_OR_SELECTED_CONTENT_PRIVATE` or `NOT_MODELED`; the optional content item selected on a rule command contributes its template sensitivity label in addition to trigger sources. The label affects propagation and specification results, but does not enforce account, Board, or API authorization, encryption, or transmission blocking. |
| `labelPropagationScope` | `AUTOMATION_RULE_COMMANDS_ONLY`; trust/privacy reset assignments are attached to synchronized automation-rule commands. Template-internal Transitions, WorkingState Dynamics, and natural evolution change modeled values/states without copying a trigger label into the result. Attack falsification may still force a declared reading's trust to `untrusted`. |
| `environmentEvolutionEffects` | Always contains `DECLARED_NUMERIC_RATES_AND_DEVICE_EFFECTS_WITHIN_DOMAIN`, `UNWRITTEN_DISCRETE_VALUES_NONDETERMINISTIC_WITHIN_DECLARED_DOMAIN`, and `DEVICE_WRITTEN_DISCRETE_VALUES_HOLD_WHEN_NO_DECLARED_EFFECT_APPLIES`. Shared numeric values combine every delta their declared interval admits with device effects once and clamp to the declared domain; an interval excluding `0` is a mandatory per-step change wherever that domain leaves room for it, and at a saturated boundary the clamp holds the value instead. `NaturalChangeRate = [-1, 1]` is MEDIC's per-step disturbance; no second undeclared noise term is added. A shared enum/boolean value that no submitted device declares it writes is an exogenous input and may take any value in its declared domain each step; one that a device does declare it writes follows that device's declared `Dynamics` and otherwise holds. |
| `localVariableFallbackPolicy` | `STUTTER_WHEN_NO_DECLARED_EVOLUTION`; a device-local value retains its current value unless a declared Transition assignment, WorkingState Dynamic, natural rate, or enabled attack effect changes it |

`DECLARED_FALSIFIABLE...` applies only to template variables whose required
`FalsifiableWhenCompromised` flag is `true`, whether shared or device-local. The model
does not infer sensor behavior from API presence and does not widen declared domains.
An attack-enabled request with no applicable reading or automation link is rejected with
`422` instead of returning a run indistinguishable from attack mode off. Behaviorally
inert device instances are not generated as compromise choices and do not consume the
budget; the returned point counts therefore describe the effective attack surface, not
the raw number of canvas devices.

### `ModelRunSnapshotDto`

This object identifies the model population actually accepted for a verification or
simulation run. It is returned by synchronous results, newly submitted and queried
tasks, task summaries, and persisted trace detail/summary DTOs.

| Field | Type | Meaning |
| :--- | :--- | :--- |
| `capturedAt` | `LocalDateTime` | Time the referenced manifests were resolved at the model boundary |
| `deviceCount` | `int` | Device instances in the normalized model input |
| `ruleCount` | `int` | Submitted automation rules, including any later reported as generation-disabled |
| `specificationCount` | `int` | Submitted specifications; always `0` for simulation |
| `environmentVariableCount` | `int` | Effective board environment entries after required defaults were merged |
| `deviceTemplateCount` | `int` | Distinct referenced template manifests captured for the run |
| `templatesFrozen` | `boolean` | Always `true`; generation reused the captured manifests and did not reload mutable definitions |
| `modelFingerprint` | `String` | Canonical semantic fingerprint, **exploration-only by contract**. Counterexample-exploration runs populate it for exact current-Board drift checks. On a verification or simulation snapshot it must be `null`: `PersistedModelContextIntegrity` *rejects* a non-null value as a persisted-integrity violation, so this is an enforced invariant rather than an unfinished feature, and populating it would make existing rows unreadable |
| `environmentProvenance` | `EnvironmentValueProvenanceDto[]` | Per-shared-value evolution rules frozen for this run, so a stored trace stays explainable after the Board changes. Empty when the run had no shared values. See [`EnvironmentValueProvenanceDto`](#environmentvalueprovenancedto) |

`modelSnapshot` is scope metadata, not a claim that the current Board still matches.
The Board compares current modelable input with an in-memory submission signature only
for runs submitted in the same browser tab. It labels changed input explicitly; after a
reload or when opening historical results, it says the current Board was not compared
and limits the conclusion to this snapshot.

**A history row can only compare counts, and says so.** With no fingerprint available for verification
and simulation, a run history row compares device, rule, specification, environment-variable and
template *counts* against the current canvas. Every semantic edit that preserves those counts is
invisible to it — inverting a rule's relation operator, changing an environment variable's value, moving
a specification's threshold, swapping one device's template. (A device rename is deliberately not drift
anywhere in this product: the exploration fingerprint strips `deviceLabel` as presentation.) So the row
renders three distinct states, never a bare absence: the drift warning when a count differs, an explicit
"counts match, contents not compared" notice when they agree, and nothing only when there is no current
scope to compare against. Rendering nothing for the middle case asserted that the verdict still
described the canvas, which is the one claim this product never makes.

### `EnvironmentValueProvenanceDto`

One entry per shared value in the run, recording which rule permitted that value to move.
Without it, explaining a stored counterexample would require re-reading the current Board —
which may have changed since the run, so the explanation could contradict the trace.

| Field | Type | Meaning |
| :--- | :--- | :--- |
| `name` | `String` | User-facing shared value name, matching the `envVariables` entries in trace states |
| `type` | `NUMERIC` \| `DISCRETE_ENUM` \| `DISCRETE_BOOLEAN` | Domain shape resolved from the declaring manifest |
| `lowerBound` / `upperBound` | `Integer` | Declared numeric domain; both `null` for a discrete value |
| `naturalChangeRate` | `String` | Declared per-step interval for a numeric value; `null` for a discrete value, which has no ordering to move along |
| `values` | `String[]` | Declared discrete domain; `null` for a numeric value |
| `authorship` | `EXOGENOUS` \| `DEVICE_CONTROLLED` \| `COMPOSED` | Who may change it: nobody in the scene, exactly one device, or several |
| `writers` | `DeviceWriter[]` | Submitted devices declaring this name in `ImpactedVariables`, each with `deviceVarName`, `templateName`, and `templateSource` |
| `readers` | `DeviceReader[]` | Submitted devices declaring `Reads: true` for it, each with `deviceVarName` |
| `semantics` | `EXACT` \| `ABSTRACTION` | Whether the evolution rule means exactly what the user declared, or deliberately over-approximates it |
| `evolutionSummary` | `String` | Prose statement of the same rule, for direct display next to a changed value |

`authorship` is derived from `writers`, so the two cannot disagree: an empty list is
`EXOGENOUS`, one writer is `DEVICE_CONTROLLED`, and more than one is `COMPOSED`.

`semantics` is `ABSTRACTION` for exactly one case — an `EXOGENOUS` discrete value, which the
model lets take any declared value each step because no device in the scene controls it and a
discrete domain has no natural-evolution rule to fall back on. Every other combination is
`EXACT`. The rules behind both tags live in
[shared-value-semantics.md](../architecture/shared-value-semantics.md) §7, which this DTO
reports rather than defines.

Conflicting discrete writers never reach this DTO: board assembly rejects that scene before
generation, so a `COMPOSED` discrete value is one whose writers agree.

### Other verification endpoints

- `GET /api/verify/tasks/{id}/progress` → `Integer` (0–100)
- `POST /api/verify/tasks/{id}/cancel` → `TaskCancellationResultDto`
- `DELETE /api/verify/tasks/{id}` → dismiss a failed/cancelled task with no result
- `GET /api/verify/runs` → `VerificationRunSummaryDto[]`
- `GET /api/verify/runs/{id}` → `VerificationRunDto`
- `GET /api/verify/runs/{id}/traces` → `TraceDto[]`
- `DELETE /api/verify/runs/{id}` → atomically delete the run and its counterexamples
- `GET /api/verify/traces` → `TraceDto[]`
- `GET /api/verify/traces/{id}` → `TraceDto`
- `DELETE /api/verify/traces/{id}` → `null` (404 `ResourceNotFoundException` if absent)

There is no trace-keyed SMV download. A run checks one model and every counterexample under it came
out of that model, so the model is addressed by run: `GET /api/verify/runs/{id}/smv`.

The full `GET /api/verify/traces` collection preserves newest-first ordering after loading
owned rows. Its database query is deliberately unordered so MySQL does not include complete
state and frozen-request JSON in a filesort; full-detail integrity validation is unchanged.

### `TaskCancellationResultDto`

Used by both verification and simulation cancellation endpoints. An HTTP 200 means the
request was evaluated, not that cancellation won the race.

| Field | Type | Notes |
| :--- | :--- | :--- |
| `taskId` | `Long` | The task named in the request |
| `cancellationAccepted` | `Boolean` | `true` only when an active task was atomically changed to `CANCELLED` |
| `cancellationOutcome` | `ACCEPTED \| ALREADY_CANCELLED \| ALREADY_COMPLETED \| ALREADY_FAILED \| NO_LONGER_CANCELLABLE` | User-facing result of the attempt |
| `taskStatus` | `PENDING \| RUNNING \| COMPLETED \| FAILED \| CANCELLED` | Authoritative status after the attempt |
| `executionMayStillBeStopping` | `Boolean` | The persisted status is already `CANCELLED`, but queued or running work may still exist locally or on another backend instance; `false` is returned only when this instance definitively removed the queued execution before it started |

Clients must branch on `cancellationOutcome`; they must not turn every HTTP 200 into
"cancelled successfully". The frontend also verifies that the returned `taskId`,
outcome, accepted flag, and status agree before updating the task UI.

---

## 2. Nested request DTOs

### `DeviceVerificationDto`

| Field | Type | Required | Notes |
| :--- | :--- | :--- | :--- |
| `varName` | `String` | yes | Instance identifier used as the SMV variable name |
| `templateName` | `String` | yes | Resolved from the user's template table by `userId + templateName` |
| `state` | `String` | no | Current state |
| `currentStateTrust` | `String` | no | Device-level trust override (`trusted` / `untrusted`) |
| `variables` | `VariableStateDto[]` | no | `{ name, value, trust }`; `name` must be a template `InternalVariable.Name` |
| `privacies` | `PrivacyStateDto[]` | no | Device-local variable sensitivity overrides as `{ name, privacy }` |

For templates with `Modes`, `state` initializes the mode state machine and
`currentStateTrust` is an explicit trust override for the current state. If it is omitted,
the model uses the selected `WorkingStates.Trust`. No-mode devices should omit both
fields; their model behavior comes from device-local `variables` and `privacies`.

`DeviceVerificationDto.variables` and `.privacies` may contain only device-local
template variables (`InternalVariables[].IsInside=true`). Current-state sensitivity is
expressed by `currentStatePrivacy`; clients never author generated state-property keys.
Environment variables (`IsInside=false`) are rejected in these fields; they
are supplied through the top-level `environmentVariables` pool.

### `BoardEnvironmentVariableDto`

| Field | Type | Required | Notes |
| :--- | :--- | :--- | :--- |
| `name` | `String` | yes | Shared environment variable name exactly as declared in the template. Do not add or strip NuSMV's generated `a_` prefix; a real variable named `a_temperature` is valid and generates `a_a_temperature` in SMV |
| `value` | `String` | yes when the template declares enum/range | Initial value for NuSMV `a_<name>` |
| `trust` | `String` | no | `trusted` / `untrusted`; omission inherits the template's required explicit shared-environment `Trust`; an explicit blank/invalid value is rejected before merging |
| `privacy` | `String` | no | `public` / `private`; omission inherits the template's required explicit shared-environment `Privacy`; an explicit blank/invalid value is rejected before merging |

Before verification, simulation, or fix generation, the backend collects environment
variables required by the submitted devices' templates, merges missing values from
defaults (`Values[0]` for enums, `LowerBound` for bounded numeric variables), validates
the pool, and passes that shared pool to NuSMV generation as the authoritative source for
`init(a_<name>)`, where `<name>` is the literal template variable name. Devices whose templates declare an environment `InternalVariable`
receive a read mirror such as
`sensor_1.temperature := a_temperature`; devices that only list the name in
`ImpactedVariables` can change `a_temperature` but cannot be used as the rule/spec
source for that variable. Rules/specs still use a device prefix such as
`sensor_1.temperature`; the prefix checks that the referenced device declares that shared value
as readable model state and anchors the model identity. It is not account, Board, or API
authorization; the actual value comes from this pool.

An impact-only template must carry its own shared `InternalVariables` entry (`Reads: false`); another installed
or submitted device template cannot silently supply it. Only submitted device instances
contribute semantics. Declarations of the same shared name must agree on literal casing,
domain/enum order, natural change rate, default trust, and default privacy or the request
is rejected before NuSMV execution.

When these fields originate from the saved board, `POST /api/board/nodes` has already
validated the same runtime semantics against the selected template: legal current
state, legal device-local variable override names and values, `trusted|untrusted`
trust values, and `public|private` privacy values. `/api/board/environment` has already
validated the environment pool. Verification/simulation still revalidate the
model-boundary request because AI tools, scripts, and service callers can construct
requests without passing through board storage.

### `RuleDto`

| Field | Type | Notes |
| :--- | :--- | :--- |
| `id` | `Long` | Null for unsaved rules |
| `conditions` | `Condition[]` | `@NotEmpty`; at most 50 conditions |
| `command` | `Command` | `@NotNull` |
| `ruleString` | `String` | Human-readable form; at most 4,000 chars. A caller-supplied value over that is rejected, while one the server composes is truncated — see [board.md](board.md) |
| `createdAt` | `LocalDateTime` | |

Rule ownership is derived from authentication. The internal `userId` field is
`@JsonIgnore` and is neither accepted as caller authority nor serialized to clients.

`Condition`: `{ deviceName (req), attribute (req), targetType (req), relation?, value? }`.
`deviceName` carries the canonical device id / NuSMV varName, not a display label.
`targetType` is required (`api | variable | mode | state`) and drives NuSMV condition
generation; the backend does not infer semantics from `attribute`. `relation` must be
one of `=`, `!=`, `>`, `>=`, `<`, `<=`, `in`, `not_in`, or `not in` when present;
aliases `EQ`, `NEQ`, `GT`, `GTE`, `LT`, `LTE`, `IN`, `NOT_IN`, and `NOT IN` are accepted
and normalized before storage/model generation. `value` is required whenever `relation`
is present.
For rule conditions, `targetType=api` is a boolean event-signal source: `attribute`
must name a template API with `Signal=true`, and both `relation` and `value` must be
omitted. Value-based conditions (`variable`, `mode`, `state`) require both
`relation` and `value`. `targetType=mode` and `targetType=state` are enum conditions,
so they support only `=`, `!=`, `in`, and `not in`; numeric `targetType=variable`
conditions support those operators and additionally support ordering comparisons.
Rules use current-step IF semantics: conditions are evaluated against the source
device values visible in the current NuSMV state, and the command changes the target
device in the next state. Verification specs and parsed traces use that same
current-step condition model.
`Command`: `{ deviceName (req), action (req), contentDevice?, content? }` — the last
two carry privacy-rule content. `action` names a template API command; commands may use
any API, not only signal APIs.

### `SpecificationDto`

| Field | Type | Notes |
| :--- | :--- | :--- |
| `id` | `String` | `@NotBlank`, ≤100 chars |
| `templateId` | `String` | `@NotBlank`, `@Pattern("^[1-7]$")`; `"6"` → LTLSPEC, `"1"`–`"5"`/`"7"` → CTLSPEC. Unknown ids are rejected at the DTO boundary and fail closed during generation |
| `templateLabel` | `String` | `@NotBlank`, ≤255 chars |
| `formula` | `String` | Optional display formula preview/cache persisted with board specs; ignored for verification semantics |
| `devices` | `DeviceRefDto[]` | Selected-device metadata (`@NotNull`, max 50); persisted as JSON only when saved through board storage |
| `aConditions` | `SpecConditionDto[]` | `@NotNull`; at most 50; serialized as `aConditions` |
| `ifConditions` | `SpecConditionDto[]` | `@NotNull`; at most 50 |
| `thenConditions` | `SpecConditionDto[]` | `@NotNull`; at most 50 |

`SpecConditionDto`:

| Field | Rules |
| :--- | :--- |
| `id` | Optional client-side identifier |
| `side` | Optional; when present must be `a`, `if`, or `then` and match the containing collection |
| `deviceId` | **Required** model device varName, matching one `devices[].varName` |
| `deviceLabel` | Optional display snapshot. Ignored for validation/resolution |
| `targetType` | **Required**; `state`, `mode`, `variable`, `api`, `trust`, or `privacy` |
| `key` | **Required** |
| `propertyScope` | Required only for `trust`/`privacy`: `state` means the active state in the mode named by `key`; `variable` means the literal template variable named by `key`. Generated `Mode_state` names are invalid |
| `variableSource` | **Required for `targetType: variable`**, rejected on every other target type. `environment` asks about the value in the home (compiles to the shared pool value, and requires a shared declaration); `reported` asks what this device said. The two differ only when that device is compromised, so there is **no default** — a missing value is rejected here rather than guessed. See [shared-value-semantics.md](../architecture/shared-value-semantics.md) |
| `relation` | **Required**; same enum as rule conditions |
| `value` | **Required**; API conditions may be authored without a value in UI/AI helpers and are normalized to `TRUE` before this API boundary |

`side` is derived from the containing collection on save/read and, when supplied by a
client, must match that collection.
`formula` is never parsed by verification. The backend rebuilds every CTL/LTL property
from `templateId` and the structured condition arrays, so clients must keep
`aConditions`, `ifConditions`, and `thenConditions` complete even when a preview string
is present. Controlled UI/AI/scene-import paths regenerate `formula` and `devices[]`
from those conditions instead of trusting imported/generated cache text.
User-facing UI should label `formula` as a formula preview/cache. Verification result
UIs use the run-captured `SpecResultDto.formulaPreview` in the ordinary result row and
label `SpecResultDto.expression` as the actual checked expression in technical details.
They must not resolve `specId` against the current Board to reconstruct a historical or
asynchronous result: the current specification may have changed while the run was active.
Template shape is strict: `templateId` `1`, `2`, `3`, and `7` require non-empty
`aConditions` and empty `ifConditions`/`thenConditions`; `templateId` `4`, `5`, and
`6` require non-empty `ifConditions` and `thenConditions` and empty `aConditions`.
`DeviceRefDto`: `{ deviceId, deviceLabel, selectedApis: String[] }`; `deviceId` is
required and is also the model device varName at this API boundary. `deviceLabel` is
display-only. The `devices` collection is recursively validated: null device entries,
missing ids, a null `selectedApis` collection, and null API-name entries are rejected
instead of being accepted as incomplete display bindings.

- `targetType` ∈ `state | mode | variable | api | trust | privacy` (backend `@Pattern`,
  case-insensitive). For `trust`/`privacy`, `value` must be a legal enum —
  `trusted|untrusted` and `public|private` respectively (the SMV variable domains); an
  arbitrary string is rejected before generation.
- For specification conditions, `targetType=api` checks a generated boolean API signal
  variable. `key` must name a template API with `Signal=true`; relation is limited to
  `=`, `!=`, `IN`, or `NOT_IN`; values must be `TRUE`/`FALSE` (or a boolean list for
  `IN`/`NOT_IN`). Frontend and AI authoring helpers may omit relation/value for an API
  condition; they normalize the model request to `= TRUE`.
- `targetType=state` checks the full device state tuple. `targetType=mode` checks one
  concrete mode variable named by `key` (for example `ThermostatMode`) against a legal
  value from that mode. With `IN`/`NOT_IN`, ordinary enum-like values may be separated
  by comma, semicolon, or pipe; multi-mode `state` tuples reserve semicolon for the tuple
  itself, so tuple sets must use comma or pipe (`home;idle,sleep;idle`).
- `targetType=variable` checks a template device-local or environment variable declared
  by the referenced device. `key` is the literal template variable name; it is not a
  shorthand for the generated SMV name. For example, a template variable named
  `temperature` uses `key: "temperature"` and generates `a_temperature`, while a real
  template variable named `a_temperature` uses `key: "a_temperature"` and generates
  `a_a_temperature`. For environment variables, the device prefix identifies a template
  that declares the value readable in the model; it is not authorization. The
  value/trust/privacy come from the top-level environment pool. Enum variable
  values must be one of the template `Values`; numeric variables with bounds must
  receive integer values inside `LowerBound..UpperBound`.
- `targetType=trust` and `targetType=privacy` also use literal property keys. Do not
  submit generated names as aliases: `key: "trust_temperature"` means the real property
  key is `trust_temperature`, so the generated SMV variable is
  `device.trust_trust_temperature`. If the template only declares `temperature`, use
  `key: "temperature"`.
- Device references are resolved from canonical ids only. Labels and template names are
  display metadata, not fallback identity. See
  [../architecture/device-identity.md](../architecture/device-identity.md) and
  [identifier handling](../architecture/nusmv-model.md#identifier-handling).

See [../architecture/spec-templates.md](../architecture/spec-templates.md) for the
template ↔ CTL/LTL semantics and how each `targetType` maps to an SMV expression.
Only template ids `"1"` through `"7"` are supported and enforced by
`SpecificationDto.@Pattern("^[1-7]$")`; invalid ids are rejected at the DTO boundary or
recorded as skipped generation warnings rather than being silently accepted.

---

## 3. Trace structure

`TraceDto`

| Field | Type | Notes |
| :--- | :--- | :--- |
| `id` / `verificationTaskId` | `Long` | Persisted trace identity and owning completed verification-run identity. Both are required on history/detail reads and must match the enclosing saved run. An immediate result whose history persistence failed or remains unknown contains an unpersisted trace without either field. Such a trace remains locally replayable, but the client does not offer automatic fix because the fix endpoint requires confirmed persisted evidence. Ownership is enforced server-side and `userId` is not serialized |
| `violatedSpecId` | `String` | |
| `violatedSpec` | `SpecificationDto` | Structured verification-time specification snapshot used for user-facing labels and conditions |
| `checkedExpression` | `String` | Exact generated CTL/LTL expression checked by NuSMV; distinct from `violatedSpec.formula`, which is only a preview/cache |
| `states` | `TraceStateDto[]` | Ordered counterexample states |
| `playbackScene` | `ModelPlaybackSceneDto` | Canonical frozen `{ nodes, rules }` used only to render this trace. Clients replay it independently of the live Board and must not resolve its nodes or rules through current template or rule data. |
| `modelComplete` | `Boolean` | Whether the verification that produced this trace used the complete generated model |
| `disabledRuleCount` / `skippedSpecCount` | `Integer` | Generation omissions inherited from the source verification |
| `generationIssues` | `ModelGenerationIssueDto[]` | Item-level names and reasons for those inherited omissions |
| `isAttack` | `Boolean` | Attack mode persisted with this trace |
| `attackBudget` | `Integer` | Persisted derived run size: exhaustive upper bound, exact selected-point count, or `0` |
| `enablePrivacy` | `Boolean` | Privacy-modeling flag persisted with this trace |
| `modelSemantics` | `ModelSemanticsDto` | Required persisted structured policy, exact selected points/display labels, effects, and effective attack-surface counts. A missing or malformed snapshot makes the trace unavailable |
| `modelSnapshot` | `ModelRunSnapshotDto` | Frozen verification-time item/template counts; this does not assert equality with the current Board |
| `createdAt` | `LocalDateTime` | |

> The internal trace DTO also carries `requestJson`, exact `templateSnapshotsJson`,
> `violatedSpecJson`, and `userId`,
> but they are `@JsonIgnore` — **not serialized to clients**. The raw fields restore
> server-side fix context. Automatic fix replays the exact saved manifests rather than
> whichever template versions happen to be current. New verification traces store the manifests
> in a versioned internal envelope together with one explicit `BUNDLED` or `CUSTOM`
> model-token source for each canonical request device id. Unversioned, missing, unknown,
> or request-mismatched snapshot entries are persisted-data integrity failures; the server
> never infers a source from a template or token name.
> `TraceMapper` instead returns structured `violatedSpec` and
> `isAttack` / `attackBudget` / `enablePrivacy` / `modelSemantics`, so clients do not parse persistence JSON.
> A non-attack request and historical snapshot use `attackBudget=0`. A positive budget
> with `isAttack=false` is rejected before model generation rather than normalized away.
> Records without all dedicated run-context columns, including the falsifiable-reading
> subset count, return no `modelSemantics`; the mapper
> does not guess a denominator from `devices.length`, which could reintroduce inert points.

`TraceStateDto`: `{ stateIndex, devices: TraceDeviceDto[],
triggeredRules: TraceTriggeredRuleDto[], compromisedAutomationLinks: TraceTriggeredRuleDto[],
trustPrivacies: TraceTrustPrivacyDto[], envVariables: TraceVariableDto[],
globalVariables: TraceVariableDto[], loopStart?: boolean, loopBack?: boolean }`.
`loopStart` / `loopBack` mark the repeating cycle of an infinite (liveness) counterexample and are
absent for every finite path. The closing state repeats the loop *entry*, so whether it differs from
its own predecessor depends on the cycle length — see
[verification-flow.md](../architecture/verification-flow.md#parser-boundaries) for both measured
shapes and why neither flag can be inferred from the values.
Formal verification and simulation state arrays are strictly one-based and contiguous:
the first `stateIndex` is `1`, and each following item increments by exactly one. Empty,
zero-based, duplicate, or gapped persisted trajectories are rejected as unavailable.
Fuzz findings use their separately documented zero-based finite-prefix contract.
`envVariables[].name` uses the literal board/template name. Generated NuSMV aliases are
not part of this API boundary: `a_temperature` in SMV is serialized as `temperature`,
while a real variable named `a_temperature` is serialized as `a_temperature`.
`globalVariables[]` contains runtime model values that are not part of the user's
environment pool. The internal attack counter is exposed as the user-facing
`compromisedPointCount`; its generated NuSMV name is not serialized. A user environment
variable cannot overwrite this separate namespace.

`TraceTriggeredRuleDto` is `{ ruleIndex, ruleId, ruleLabel }`. `ruleIndex` is the required,
zero-based position in the immutable rule list submitted for that run; it binds internal
probe evidence to exactly one frozen rule even when ids are absent or duplicated. It is
historical evidence, not an index into the current Board after any edit. `ruleId` remains
the preferred current-canvas correlation key when present. A triggered rule is a selected
model branch whose command-delivery guards passed and whose command produced the transition
into this state; it is not merely a condition that looked true in the frontend.
`compromisedAutomationLinks` uses the
same stable rule snapshot shape, so users see the affected automation rather than an
internal link index. Persisted verification and simulation detail reads reject an
index outside the frozen request's rule list or an id/label that does not exactly match the
rule at that position; such evidence is unavailable rather than guessed from current Board
state.

`TraceDeviceDto`: `{ deviceId, deviceLabel, templateName, modelTokenSource, state, mode, compromised,
variables: TraceVariableDto[], trustPrivacy[], privacies[] }`. `deviceId` is the
model-boundary identity; user-facing trace and simulation UIs should display
`deviceLabel` when it is present and keep `deviceId` in technical/debug details.
`compromised` is the user-facing boolean translated from NuSMV's internal attack flag;
the internal `is_attack` variable is not mixed into `variables[]`.
`TraceVariableDto`: `{ name, value, trust, observed, modelTokenSource }`.
`observed` is `false` only for an affect-only shared declaration (`IsInside=false`,
`Reads=false`): the model declares `<device>.<name>` so the trust/impact machinery has an
identifier, but never constrains it, so there is no reading to report and `value` is the
empty string. The row is still present in every state — it carries the variable's trust, and
counterexample-based fixing requires one row per manifest variable — but clients must not
render its value as a device reading. The true shared value is published once, in the
state's `envVariables[]`. A persisted trace with `observed: false` and a non-empty `value` is
invalid. Anything else is `observed: true`, which is also the reading for a trace persisted
before this field existed.
`modelTokenSource` is `BUNDLED`, `CUSTOM`, or `UNKNOWN`. Device provenance is frozen
from the exact template captured for the run. An environment variable has one source
only when every active template declaring it has that same source; mixed declarations
become `UNKNOWN`, and global parser values are always `UNKNOWN`. Clients may localize
exact known tokens only for `BUNDLED`; `CUSTOM` and `UNKNOWN` must remain verbatim. The field is required on every device, device-local
variable, environment variable, and global variable entry; a missing or null source makes
the persisted trace invalid rather than implicitly selecting `UNKNOWN`.
`TraceTrustPrivacyDto`: `{ name, propertyScope ('state'|'variable'|'content'),
mode?, trust? (Boolean), privacy? ('private'|'public') }`. For a state property, `name`
is the literal state and `mode` is its literal mode. Generated `Mode_state`, `trust_*`,
and `privacy_*` identifiers are parser-only details and are never serialized.
Verification, simulation, and fuzz replay clients validate these nested objects before
rendering them, including string/Boolean shapes, legal trust/privacy values, and the frozen
token-source enum. Device identity and provenance must remain stable across states; every
local variable uses its device source, each environment variable keeps one source across the
trace, and global values are always `UNKNOWN`. Because these are complete snapshots, device
ids and local/environment/global variable names keep the same membership in every state.
Those identities are unique within their containing state/device, as are trust/privacy
property identities within each evidence list. `trustPrivacy[]` carries trust evidence, `privacies[]` carries
privacy evidence, and state-scoped entries require a non-blank mode. Malformed nested evidence
is an incomplete response, not playable history. A stateless device may legitimately serialize
empty `state` and `mode` strings.

---

## 4. Simulation

### `POST /api/simulate` — synchronous (not persisted)

**Request body**: `SimulationRequestDto` — same as `VerificationRequestDto` but with
**no `specs`** field and an added `steps`:

The same strict unknown-field, scalar-type, duplicate-environment, and explicit-blank
validation applies. A misspelled attack/privacy option is an HTTP `400`, not a simulation
with that option silently disabled.

| Field | Type | Required | Default | Notes |
| :--- | :--- | :--- | :--- | :--- |
| `devices` | `DeviceVerificationDto[]` | yes (`@NotEmpty`) | — | 1–100 devices; per-device override limits match verification |
| `playbackNodes` | `DeviceNodeDto[]` | yes (`@NotEmpty`) | — | Same frozen visual replay contract as verification: one layout node for each submitted model device, with matching id/template/label and valid finite layout. It is excluded from NuSMV semantics. |
| `environmentVariables` | `BoardEnvironmentVariableDto[]` | no | `[]` | At most 200; same unique-name, omitted/null-default, and explicit-blank rejection contract as verification |
| `rules` | `RuleDto[]` | no | `[]` | At most 100, with at most 50 conditions per rule. The same positive, request-unique non-null id contract as verification applies |
| `steps` | `int` (1–100) | no | `10` | Number of simulation steps |
| `attackScenario` | `AttackScenarioDto` | yes | — | Explicit per-run attack selection; `mode` is required. Simulation accepts only `NONE` or `EXACT_POINTS`. It never chooses compromised devices or links randomly, and rejects budget-based exhaustive selection. |
| `enablePrivacy` | `boolean` | no | `false` | |

**Response**: `SimulationResultDto` — `{ isAttack, attackBudget, enablePrivacy,
modelSemantics, modelSnapshot, modelComplete, disabledRuleCount,
generationIssues: ModelGenerationIssueDto[], states: TraceStateDto[], playbackScene,
steps,
requestedSteps, nusmvOutput, logs: String[], historyPersistence, hasSmvModel }`.

Plain `POST /api/simulate` is a preview and returns
`historyPersistence.status=NOT_REQUESTED`; no history row is expected.

`modelComplete=false` means one or more submitted rules were disabled fail-closed while
generating the model. The returned states are still model states, but they do not
represent the complete submitted board. Simulation is model exploration, not a
prediction of physical-home behavior.

The frontend validates synchronous, saved, and asynchronously loaded simulation
responses before opening the timeline. `modelComplete`, omission counts and itemized
issues, run-context `modelSemantics`, non-empty states, and `steps = states.length - 1`
are required. Nested devices, variables, rule snapshots, security labels, and token sources
must also satisfy the trace contract above. Persisted summaries require explicit
attack/privacy context. Missing or malformed fields therefore cannot be interpreted as a
complete model or a playable successful trajectory.

Synchronous simulation uses the same request snapshot and runtime validation as async
simulation. Validation failures are returned as errors (REST 400 for DTO Bean Validation
or 422 for service `ValidationException`), not as a success-shaped empty simulation
result. Timeout, interruption, execution failure, or a run with no parsed states also
returns an error rather than an empty success DTO. `SimulationExecutionException` uses
HTTP 504/503/500 and error `data` shaped as `{ reasonCode, logs }`, where `reasonCode`
is `TIMED_OUT`, `INTERRUPTED`, `NO_TRACE_STATES`, or `EXECUTION_FAILED`.

### `POST /api/simulate/async`

**Response `data`**: the newly created `SimulationTaskDto`. Its server-generated `id`
is used for polling, while `status`, `progress`, `progressStage`, `requestedSteps`, `modelSemantics`, and
`modelSnapshot` are the authoritative accepted-task context. HTTP success means the task
was accepted, not that a trajectory already exists.

Async simulation submission follows the same lifecycle contract as async verification:
the backend snapshots and validates the request and captures referenced template
manifests before task creation. The queued worker reuses those manifests rather than
reloading mutable templates. REST calls first
run full DTO Bean Validation; service and AI-tool callers run NuSMV runtime validation
for required devices, null list items, device identity, `steps`, `attackScenario`, and
current-template rule semantics.
Structurally invalid rules are rejected at the boundary: null rule elements, null
commands, and blank `command.deviceName` / `command.action` all return validation
errors instead of silently disappearing from the model. Known semantic mismatches
such as non-signal API conditions, unknown target actions, unknown content keys, and
illegal state/mode values return `ValidationException` (422) before task creation.
Rules that pass request validation but still cannot be emitted by NuSMV generation may
be disabled fail-closed with warnings in `checkLogs`. Validation failure returns no
`taskId`. Dispatch failure uses the same conditional cleanup contract as verification:
a confirmed still-`PENDING` row deletion consumes no quota, queue rejection returns
`503`, and an unconfirmed cleanup identifies the task id for reconciliation before retry.
Clients should start ordinary polling only after this endpoint successfully returns a task id.

Before insertion, the service atomically enforces the configured per-user stored-task
and active-task limits. HTTP 429 uses reason codes
`SIMULATION_ACTIVE_TASK_LIMIT_REACHED` and
`SIMULATION_STORED_TASK_LIMIT_REACHED` with the same structured quota data as async
verification.

As with verification, `progress=100` is written only together with the completed task
and its saved simulation trace. A failed completion write is not exposed as a completed
100% task.

Async simulation uses the same database-clock, per-instance renewable lease contract as
async verification. Queue wait is leased as well as execution; start/renewal fail closed
after expiry or ownership loss, worker success/failure cannot commit after the lease is lost,
healthy work on another backend instance survives startup, and only expired active rows are
recovered as `FAILED`.

### `GET /api/simulate/tasks` — simulation task status layer

Optional query parameter: `excludeTaskIds=1,2,3`. Use it when the frontend is already
polling specific task ids through `GET /api/simulate/tasks/{id}` and wants the inbox
summary refresh to skip those same tasks. The list accepts at most 100 positive ids.

**Response**: `SimulationTaskSummaryDto[]`, ordered by `createdAt` descending.

This list excludes `COMPLETED` tasks. It contains active work plus failed/cancelled
tasks that produced no saved result. Active rows expose progress and frozen run
context; unsuccessful terminal rows expose a technical `errorMessage`, which clients
place behind an advanced disclosure rather than using as the primary localized status.
Completed simulations are
listed once through `GET /api/simulate/traces`, where the persisted trajectory is the
user-facing run result. The ordered inbox query selects only summary context and diagnostics;
worker ownership and lease fields remain detail/internal state.

`DELETE /api/simulate/tasks/{id}` dismisses a failed or cancelled task. Active tasks
must be cancelled first. Completed simulation results must be deleted through their
persisted-run endpoint.

### `GET /api/simulate/tasks/{id}` — `SimulationTaskDto`

`{ id, initiator, status, createdAt, startedAt, completedAt, processingTimeMs, isAttack,
attackBudget, enablePrivacy, modelSemantics, modelSnapshot, requestedSteps, steps, modelComplete,
disabledRuleCount, generationIssues, simulationTraceId, checkLogs: String[],
errorMessage, progress, progressStage }`. Simulation uses `QUEUED`, `STARTING`,
`RUNNING_SIMULATION`, and `PERSISTING_RESULT`. The percentage and phase are updated
atomically while the task remains active.

Completed async simulations store the full states, execution logs, request JSON, and
the capped NuSMV diagnostic output in the referenced `SimulationTraceDto`; the task DTO
stays a polling summary. Persisted task rows are validated before exposure: request/actual step counts,
lifecycle timestamps and progress, failure details, and the completed-task trace link
must agree with the task status. A contradictory row is reported as a data-integrity
failure rather than normalized into an apparently valid polling response.

### Persisted simulations

- `POST /api/simulate/traces` → `SimulationTraceDto` `{ id?, initiator, requestedSteps, steps,
  modelComplete, disabledRuleCount, generationIssues, states, logs, nusmvOutput,
  isAttack, attackBudget, enablePrivacy, modelSemantics, modelSnapshot, playbackScene, createdAt,
  historyPersistence }`
- `GET /api/simulate/traces` → every retained `SimulationTraceSummaryDto` item, up to
  the configured `SIMULATION_MAX_STORED_TASKS_PER_USER` bound, `{ id, initiator, requestedSteps,
  steps, modelComplete, disabledRuleCount, generationIssues, isAttack, attackBudget,
  enablePrivacy, modelSnapshot, createdAt, hasSmvModel, dataAvailable,
  unavailableReasonCode? }` (summary, no states)
- `GET /api/simulate/traces/{id}` → `SimulationTraceDto` (full states)
- `DELETE /api/simulate/traces/{id}` → `null`; also removes any completed background
  task row that only referenced this persisted result, preventing a stale task pointer
- `GET /api/simulate/tasks/{id}/progress` → `Integer`
- `POST /api/simulate/tasks/{id}/cancel` → `TaskCancellationResultDto` (same
  evaluated-attempt semantics documented above)
- `DELETE /api/simulate/tasks/{id}` → dismiss a failed/cancelled task with no result

Persisted simulation `requestJson` is the validated execution snapshot used for the
NuSMV run, not a later serialization of the caller's mutable request object. Exact
`templateSnapshotsJson`, `requestJson`, and `userId` remain server-internal; REST clients receive structured execution-context
fields instead. Effective device/link attack-point counts are persisted in dedicated
columns, so reopening a trace does not recount raw devices and change the budget meaning.

The save endpoint returns the generated trajectory after an ordinary history-persistence
failure, but an operation-ownership or fencing failure rejects the request because the
trajectory may have been superseded.
`historyPersistence.status=SAVED` supplies its id. `OUTCOME_UNKNOWN` omits the id and
requires a history refresh before the UI says whether the trace was saved; the states
remain valid for immediate animation. A known `FAILED` status means no row was created.
History list conversion isolates malformed summary rows as `dataAvailable=false`; it never
silently replaces malformed semantic JSON or inconsistent step/count metadata with defaults.
Simulation summary queries validate `stateCount = steps + 1`, run context, model snapshot,
and generation diagnostics without selecting `statesJson`, `requestJson`, or solver output.
Opening detail parses the saved state sequence and cross-checks every rule event against the
exact server-internal `requestJson.rules` snapshot and model rule count. Detail corruption
fails explicitly rather than being presented as an empty trajectory.
Both synchronous saved-simulation and verification requests check the configured stored-run
quota before NuSMV starts. A full history returns HTTP 429 with the same stable async-task
quota details; a concurrent fill detected only during persistence reports a known
`historyPersistence.status=FAILED` while preserving the completed formal result or trajectory.

---

## 5. Auto-fix

For the algorithm (strategies, forward verification), see
[../architecture/auto-fix.md](../architecture/auto-fix.md). This
section is the API contract only.

### Downloading the SMV model — one model per run, addressed by run

A verification run checks exactly one model, and every counterexample it produced came out of that
model. The model is therefore owned by the **run**, and there are exactly two download endpoints —
one per run kind, because a simulation trajectory *is* its own run.

| Endpoint | Returns | Filename |
| :--- | :--- | :--- |
| `GET /api/verify/runs/{id}/smv` | The model a verification run checked | `verification-run-{id}.smv` |
| `GET /api/simulate/traces/{id}/smv` | The model executed for one trajectory | `simulation-trace-{id}.smv` |

**A trace-keyed download used to exist and was removed** (`GET /api/verify/traces/{id}/smv`, along with
the `trace.smv_model_content` column and `TraceDto.hasSmvModel`). It could only ever answer a
byte-identical copy of the run's model: one model string was generated per run and written to the run
row *and* to each of its traces, so a run with three violated specifications stored and served the same
bytes from four addresses — measured at 345 KB of duplication across 30 trace rows on a development
database, growing with every run.

Its documented justification was that a counterexample must stay self-contained after its run is
deleted. The code contradicts that: `VerificationServiceImpl.deleteRunInternal` deletes every trace and
then the run in one transaction, so a trace cannot outlive its run. Nothing called it — no client
method, no test, no script — and the run-keyed endpoint strictly dominates it, being the only one that
works for a run where every specification holds and there is no counterexample to key on.

`ddl-auto: update` never drops a column, so an existing database keeps `trace.smv_model_content` as
dead storage until dropped by hand. It is nullable and unread, so leaving it is harmless.

Frontend placement rules are pinned by `frontend/src/views/board/runArtifactPlacement.spec.ts`; that a
counterexample carries no model in either mapping direction is pinned by `TraceMapperTest`.

**Response** (both): `text/plain;charset=UTF-8`, `Content-Disposition: attachment`. The
filename is emitted as a plain ASCII `filename` parameter; passing a charset would make Spring
encode it as an RFC 2047 encoded-word, which clients save literally.

**Errors** (both):
- `404 ResourceNotFoundException` — the record does not exist, belongs to another user, or stores
  no model. A run or trace recorded before the model was persisted has none, and no migration can
  invent one, so absence is a state rather than a server fault. Never an empty attachment: a
  zero-byte `.smv` would be mistaken for the checked model.

**Clients decide from the response, not by guessing.** Every DTO a download can be offered from
carries a `hasSmvModel` boolean; the content itself is `@JsonIgnore` because it runs to tens of
thousands of characters. Offer the download only when that flag is true — gating on the record's id
instead shows the control for records that have no model, and the click then fails with nothing the
user can act on.

**Two independent reasons a download is unavailable, and they need different wording.** A run must
also be *addressable*, which `historyPersistence` decides: only `status: SAVED` yields a run id the
endpoint can be called with. So "no model for a saved run" and "no run to ask about" are separate
states, and telling the second group to look for the record in their history sends them after
something that does not exist. `OUTCOME_UNKNOWN` is a third case again — the save may or may not have
landed, and reporting it as an absence would resolve an unknown the client cannot resolve. The board
maps these in `views/board/smvUnavailableReason.ts`.

| DTO | Surface it gates | How it is produced |
| :--- | :--- | :--- |
| `VerificationResultDto` | Verification result dialog, straight after a sync run | derived getter over `smvModelContent` |
| `VerificationTaskDto` | Same dialog, after an async run completes | mapped from the task row |
| `VerificationRunDto` | Same dialog, for a run reopened from history | mapped from the run row |
| `VerificationRunSummaryDto` | Per-run button in the history panel | `CASE WHEN … <> ''` in the run-summary query |
| `SimulationResultDto` | Simulation result dialog | derived getter over `smvModelContent` |
| `SimulationTraceDto` | Replay of a saved trajectory | derived getter over `smvModelContent` |
| `SimulationTraceSummaryDto` | Per-trajectory button in the history panel | `CASE WHEN … <> ''` in the trace-summary query |

The summary rows compute the flag in SQL rather than selecting `smvModelContent`, so a history page
never loads tens of thousands of characters per row to decide whether one button can succeed. This is
why `VerificationTaskRepository.findCompletedRunSummaries` is an explicit `@Query` instead of a
derived one: `hasSmvModel` is not an entity property, so a closed projection cannot name it without
the query aliasing it.

A DTO that omits the flag disables the feature silently — the client's `v-if` reads `undefined`, the
button never renders, and nothing reports an error. Adding a download surface therefore means
checking that its DTO is in the table above.

The stored model is capped at 65,000 UTF-8 bytes — the unit the `TEXT` column is bounded in,
which matters because the model embeds user-authored rule text as comments and is routinely
non-ASCII — and carries an explicit `-- [TRUNCATED: Original size N bytes]` marker when the
generated model was larger, so a truncated download can never be mistaken for a complete one.
Truncation lands on a character boundary, so the downloaded file always decodes.

### `GET /api/verify/traces/{id}/fault-rules` — fault localization

Fast, no NuSMV invocation. **Response**: `FaultLocalizationResultDto`

| Field | Type | Notes |
| :--- | :--- | :--- |
| `traceId` | `Long` | Trace being analyzed |
| `violatedSpecId` | `String` | User-facing specification reference from the trace |
| `sourceModelComplete` | `boolean` | `true` only when the saved source verification explicitly recorded a complete generated model; missing metadata fails closed as `false` |
| `sourceDisabledRuleCount` / `sourceSkippedSpecCount` | `int` | Source-verification omission counts |
| `sourceGenerationIssues` | `ModelGenerationIssueDto[]` | Itemized `{ issueType, itemLabel, reasonCode, reason }` explanations copied from the source verification |
| `faultRules` | `FaultRuleDto[]` | Automation rules that fired in counterexample transitions *and* can influence the violated property (the [repair scope](../architecture/auto-fix.md#pipeline-rulefixer)); a fired rule that writes nothing the property depends on is omitted. When the property references no modeled device, every fired rule is kept |
| `summary` | `String` | Interpretation that distinguishes transition involvement from proven independent causation |
| `warnings` | `String[]` | Source-completeness limitations that the UI must present |

`FaultRuleDto` entries have the following user-facing fields:

| Field | Type | Notes |
| :--- | :--- | :--- |
| `ruleString` | `String` | |
| `transitionNumber` | `int` | One-based counterexample transition where the rule fired |
| `targetDeviceLabel` | `String` | User-facing target device label |
| `targetActionLabel` | `String` | For `BUNDLED`, the canonical action token that clients may localize; for `CUSTOM`/`UNKNOWN`, the preserved display label |
| `conflicting` | `boolean` | |
| `conflictingRuleString` | `String` | Readable conflicting rule when `conflicting=true` |
| `targetEndState` / `conflictingEndState` | `String` | Conflicting target outcomes when available. Reader-facing state names, never the raw manifest tuple; a multi-mode device's per-mode states are joined with `"; "`, which keeps each token translatable by the client's built-in token catalogue |
| `reasonCode` | `String` | `TRIGGERED` or `CONFLICTING_END_STATES`; clients localize from this code |
| `reason` | `String` | Backend-readable fallback explanation |
| `modelTokenSource` | `BUNDLED \| CUSTOM \| UNKNOWN` | Frozen source for `targetActionLabel` and end-state tokens. Localize exact known tokens only for `BUNDLED`; preserve `CUSTOM` and `UNKNOWN` verbatim |

Trace-snapshot rule positions and database ids stay server-internal. Clients identify a
localized rule through its readable `ruleString`, target, and trigger context; they do
not submit or operate on zero-based indices.

Fault localization is evidence for review, not a causal proof: a listed automation was
involved in a counterexample transition, but may not independently cause the violation.
An empty list can mean the violating path arose from device or environment evolution.
Clients must show `summary`, `warnings`, and `sourceGenerationIssues`; they must not turn
the endpoint name or a non-empty list into a claim that the root cause was proven.

`TraceStateDto.triggeredRules[]` and `compromisedAutomationLinks[]` contain readable
`{ ruleIndex, ruleId, ruleLabel }` snapshots. `ruleIndex` is required, non-negative, and
unique within each list for one state; it binds the event to the run's frozen request and
must not be applied to the current Board. `ruleId` is present only when the submitted model
rule carried a persisted id and is the current-canvas correlation key. `ruleLabel` remains
available when the current rule was later removed.

### `POST /api/verify/traces/{id}/fix` — fix suggestions

May invoke NuSMV multiple times (bounded by `FIX_TIMEOUT_MS`, see
[configuration.md](../getting-started/configuration.md)). An opaque `requestId` query parameter
is required so the client can cancel this exact search through
`DELETE /api/verify/fix-requests/{requestId}`. Its format is the shared one in
[overview.md](overview.md#client-supplied-requestid).

While the search is active, `GET /api/verify/fix-requests/{requestId}` returns
`InteractiveOperationStatusDto` as `{ requestId, state, stage, elapsedMs }`. The final
`FINISHED` status is retained briefly so the last polling tick does not race cleanup; an
unknown request returns 404. The fix workflow reports `QUEUED`, `RUNNING`,
`PREPARING_CONTEXT`, `PREPARING_MODEL`, `SEARCHING_AND_VERIFYING`, `FINALIZING`, and
`CANCELLING` as applicable. These are server-observed operational phases, not inferred
timers or hidden model reasoning.

Ownership, admission and the `503` contract follow the shared interactive-request rules in
[overview.md](overview.md#interactive-request-ownership-and-the-503-contract). Specific to the fix
search: the cancellation record is renewed until the callable actually exits, so an expired worker
cannot finish over a newer owner that reused the same id.

The frontend makes five short cancellation attempts to cover POST-registration races and
aborts the POST transport only after the server accepts cancellation. It pins the initiating
credential on the POST and retains it for owner-authenticated status and cancellation during account
transitions. An error with an HTTP response is a terminal POST outcome; transport loss before
any response is an unknown admission outcome and retains the request id, owner credential, and
local busy guard while cancellation/status recovery continues. After 30 consecutive unavailable
status reads the client stops automatic polling and aborts its transport, but it does not infer
completion or allow a second search under a new id; logout remains outcome-unknown until
cancellation is accepted or authoritative status reports `FINISHED`. Board keeps the request-owning
fix component mounted while its surface is hidden, so closing retains logout reconciliation and
cannot admit another local search or reuse its suggestions for another trace. A current `FINISHED`
status is terminal even when the original POST transport remains hung; stale POST cleanup is fenced
from a newer request. Fix status and cancellation calls use a 2.5-second client timeout so bounded
retry and logout reconciliation cannot stall on the ordinary API timeout. Route teardown retries
cancellation before aborting the POST transport.

**Request body**: `FixRequestDto` — optional; omit or send `null` for defaults.

| Field | Type | Default | Notes |
| :--- | :--- | :--- | :--- |
| `strategies` | `String[]` | `["parameter","condition","remove"]` when omitted | Exact, duplicate-free order of 1–3 strategies when supplied. Values are limited to `parameter`, `condition`, and `remove`. An explicit empty/invalid list is rejected rather than replaced by defaults. `remove` permanently deletes suggested automation rules; it is not a reversible enable/disable toggle |
| `preferredRangeSelections` | `PreferredRangeSelection[]` | `null` | Optional ranges selected from `FixResultDto.parameterTargets[].targetId`. Each item is `{ targetId, lower, upper }`; all fields are required, `targetId` is an opaque trace-scoped selector copied from a returned target, `lower`/`upper` are integers, and `lower <= upper` |

Clients should build `preferredRangeSelections` from selectable `parameterTargets`
and display the fault rule text, condition context, attribute, and relation. Targets are
returned even when the parameter search produces no verified suggestion.
No rule/condition locator map is part of either public request DTO. As with other REST
requests, any extra field is rejected by strict JSON parsing instead of being silently
ignored. Defaults apply only when `strategies` is omitted (or the optional request body is
omitted), never when the caller explicitly supplies an empty or malformed selection.

**Response**: `FixResultDto`

| Field | Type | Notes |
| :--- | :--- | :--- |
| `traceId` | `Long` | |
| `violatedSpecId` | `String` | |
| `faultRules` | `FaultRuleDto[]` | Same schema as fault localization |
| `suggestions` | `FixSuggestionDto[]` | Advisory proposals that passed forward verification in the complete formal model used for the fix attempt: the violated specification holds and no specification the original rules satisfied is broken ([acceptance rule](../architecture/auto-fix.md#forward-verification-fixstrategyutilsforwardverify)). A strategy may list several alternatives, smallest change first ([listing rule](../architecture/auto-fix.md#listing-alternatives-fixalternatives)); the user applies one. Each suggestion includes a short-lived `suggestionToken` binding the exact visible proposal to this user, trace, strategy, and preferred ranges |
| `strategyAttempts` | `FixStrategyAttemptDto[]` | One entry per requested strategy, including skipped/failed attempts and a user-readable reason |
| `fixable` | `boolean` | Whether `suggestions` is non-empty, i.e. at least one complete-model, forward-verified suggestion was found; not whether repair was merely attempted |
| `sourceModelComplete` | `boolean` | Whether the counterexample source verification used a complete model |
| `sourceDisabledRuleCount` / `sourceSkippedSpecCount` | `int` | Source-verification generation omissions |
| `sourceGenerationIssues` | `ModelGenerationIssueDto[]` | Itemized `{ issueType, itemLabel, reasonCode, reason }` explanations from the source verification |
| `templateSnapshotComparison` | `NOT_CHECKED \| UNCHANGED \| CHANGED \| UNAVAILABLE` | Structured comparison between current device templates and the frozen run snapshot; clients localize drift/unavailable limitations from this field |
| `summary` | `String` | Overall result summary |
| `warnings` | `String[]` | English technical diagnostics for logs/advanced details; ordinary UI derives localized limitations from source-completeness fields, `templateSnapshotComparison`, generation issues, and strategy-attempt statuses |
| `parameterTargets` | `ParameterTarget[]` | Every bounded numeric inequality eligible for preferred-range selection in this attempt, independent of whether a verified suggestion was found |
| `unusedPreferredRangeSelections` | `PreferredRangeSelection[]` | Preferred range selections that matched no parameter-adjustment target |

`FixStrategyAttemptDto` is `{ strategy, status, reason, alternativesComplete }`. It carries no
attempt counter: the strategies spend their budget on different kinds of NuSMV checks, so one
number could not mean the same thing across them, and the status already says whether the search
finished. Status is one of
`VERIFIED`, `NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE`, `ALL_CANDIDATES_REJECTED`, `INCONCLUSIVE`,
`FAILED_MODEL_GENERATION`, `FAILED_SOLVER_EXECUTION`, `SEARCH_BUDGET_EXHAUSTED`, `TIMED_OUT`,
`SKIPPED_TIMEOUT`, `SKIPPED_NO_SPEC`, `SKIPPED_NO_PARAMETERIZABLE_VALUES`, `SKIPPED_NO_FAULT_RULES`,
`SKIPPED_UNSUPPORTED`, or `SKIPPED_INCOMPLETE_SOURCE_MODEL`. This distinguishes "no repair of this
kind exists" from "the strategy started but did not finish" (`TIMED_OUT`) and "the strategy was
not run" (`SKIPPED_*`).

Two statuses are proofs over the strategy's searched space, which is the allowed edits against the
pinned counterexample. Re-running with the same inputs returns the same answer, because the fix
works on the trace's frozen snapshot:

- `NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE`: the search space was exhausted and no allowed edit prevents
  the pinned counterexample, so no candidate ever reached forward verification. A parameter search
  whose preferred ranges share no value with the template bounds is the vacuous case.
- `ALL_CANDIDATES_REJECTED`: edits that prevent the pinned counterexample exist, but every one was
  rejected. Forward verification found the target specification still violated or a
  baseline-satisfied specification broken, or the edit was ineligible (a rule left without a trigger
  condition, a changed attack scenario). Rule removal has no pinned-counterexample step, so a
  complete rule-removal search that listed nothing always ends here.

`INCONCLUSIVE` means the search ended without a repair and without that proof. Forward verification
could not settle some candidate, so a repair of this kind may still exist.

`VERIFIED` means the strategy listed at least one suggestion, and
`alternativesComplete` (a boolean on `VERIFIED` only, `null` otherwise) says whether that list is
every minimal repair of its kind (`true`) or the search stopped first (`false`). The
suggestion/attempt correspondence is checked in both directions: every suggestion belongs to a
`VERIFIED` attempt of its strategy, and a `VERIFIED` attempt has at least one suggestion. Checking
only one direction let a response claim a repair passed forward verification while offering
nothing to apply. `FAILED_MODEL_GENERATION` means the strategy could not construct
a complete candidate model, for example because the persisted counterexample initial state
could not be replayed. `FAILED_SOLVER_EXECUTION` covers NuSMV execution failure and missing,
incomplete, or unparseable solver output; a NuSMV run cut off by the fix deadline is not a
solver failure but part of `TIMED_OUT`. `SEARCH_BUDGET_EXHAUSTED` means the strategy used its
`FIX_MAX_ATTEMPTS` budget while unchecked candidates remained and listed nothing; a listing cut
short after its first repair is `VERIFIED` with `alternativesComplete=false`. None of these
incomplete outcomes is evidence that no repair exists.

A strategy run can record more than one of these outcomes, for example a transient solver failure
and later a proof, and reports one, in this order: `VERIFIED`; a proof
(`NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE`, `ALL_CANDIDATES_REJECTED`) or
`SKIPPED_NO_PARAMETERIZABLE_VALUES`; `TIMED_OUT`; `FAILED_MODEL_GENERATION`;
`FAILED_SOLVER_EXECUTION`; `SEARCH_BUDGET_EXHAUSTED`; `INCONCLUSIVE`. A proof or a precondition
does not depend on the clock, so a deadline that passes after the strategy reached it does not turn
it into `TIMED_OUT`.

`FixSuggestionDto`: `{ suggestionToken, strategy, description, parameterAdjustments[],
conditionAdjustments[], removedRuleDescriptions: String[], preexistingViolations[] }`.
Only candidates forward verification accepted are returned, so every suggestion is a verified one;
its `suggestionToken` is what `/fix/apply` checks.
`PreexistingViolation`: `{ specId, templateId, formulaPreview }` — a specification the original
rules already violated on the same model and this suggestion leaves violated. It is `[]` when every
specification holds; alternatives of one strategy can differ in it. It is part of the signed suggestion,
so `/fix/apply` and the AI `apply_fix` tool must receive it unchanged. Clients label the row from
`templateId`; `specId` is an identifier, not display text.
All collection fields in `FixResultDto` and `FixSuggestionDto` are always serialized as
JSON arrays. A collection that does not apply to the selected strategy is `[]`, never
`null`, so clients can distinguish "no such changes" from a malformed response.
`ParameterAdjustment`: `{ targetId, attribute, relation, originalValue, newValue,
lowerBound, upperBound, description, modelTokenSource }`.
`ParameterTarget`: `{ targetId, attribute, relation, originalValue, lowerBound,
upperBound, description, modelTokenSource }`.
`ConditionAdjustment`: `{ action, attribute, targetType, description, ruleDescription,
deviceLabel, relation, value, modelTokenSource }`. `ruleDescription` and `deviceLabel` are required,
non-blank display snapshots; `relation` and `value` remain action-dependent optional fields.
The internal model device reference used to apply an add operation is not serialized.
`targetType` is required and is one of `api`, `variable`, `mode`, or `state`; trust and
privacy labels are not rule-condition targets.
`targetId` is the opaque API-facing selector for preferred ranges within the same
trace/fix context. Clients should copy it from a returned `ParameterTarget`, not generate it.
For all three row types, `modelTokenSource` is `BUNDLED`, `CUSTOM`, or `UNKNOWN` and
governs the row's model-facing attribute/value tokens. Clients may localize exact known
tokens only for `BUNDLED`. `CUSTOM` and `UNKNOWN` must remain
verbatim. The English `description` is a technical fallback, not the primary localized
parameter-target label; clients should construct that label from the structured attribute,
relation, and original value.
If a copied target is not available during generation, the response reports it in
`unusedPreferredRangeSelections`. A target that matched and constrained the search is not
unused merely because no verified suggestion was found. Rule/condition positions remain server-side
trace-snapshot locators for verification, drift checks, and patching; they are not part of
the REST or AI response contract.

### `POST /api/verify/traces/{id}/fix/apply` — apply a fix suggestion

Applies the exact signed repair suggestion the user is viewing. The server verifies its
short-lived signature and saves that same proposal only when the complete current model snapshot
still matches the verification context.

The conversational `apply_fix` adapter is documented with the other [AI tools](ai-tools.md).
Its confirmation storage and security boundary are described in
[Automatic Fix](../architecture/auto-fix.md#applying-a-suggestion-fixstrategyapplier).

**Request body**: `FixApplyRequestDto`

| Field | Type | Notes |
| :--- | :--- | :--- |
| `strategy` | `String` | `parameter` / `condition` / `remove` |
| `suggestion` | `FixSuggestionDto` | Required exact suggestion returned by `/fix` |
| `suggestionToken` | `String` | Required opaque token copied from that suggestion |
| `preferredRangeSelections` | `PreferredRangeSelection[]` | Optional exact selections used for the preceding `/fix`; they are covered by the signature. Omit when generation used no selections |

**Safety** — the server does **not** trust the client:

- **Exact-suggestion signature.** The client submits the displayed proposal, but cannot alter it:
  the HMAC-protected token binds the user, trace, strategy, complete visible suggestion,
  preferred ranges, expiry, and all hidden operation locators (parameter/condition positions,
  the internal device reference for condition additions, and remove-rule positions). Tampering, expiry, or mixing a token
  with another trace/range rejects with `400`. Apply therefore cannot silently substitute a
  different newly searched proposal.
- **Complete-source-model guard.** A trace produced while any rule/specification was
  omitted cannot be used for automatic fix generation or apply. Suggestion generation
  returns explicit skipped strategy attempts; apply rejects with `400` and requires the
  user to resolve generation warnings and verify again. A saved trace with missing
  `modelComplete` metadata also fails closed: zero omission counts alone are not evidence
  that the source model was complete. Apply checks this before template reads or persistence.
- **Three drift guards, and what each returns.** Apply refuses a suggestion whose trace no longer
  describes the current board. It compares rules (index + order-preserving fingerprint), the frozen
  template manifests, and a canonical semantic fingerprint of specs, device instance state and the
  environment pool. Client-observable outcomes:
  - A **confirmed** difference — rules added/removed/edited/reordered, a modeled template field or
    the template set changed, a spec/device/environment edit, or a board that no longer builds a
    valid model — rejects with **`400`** and requires re-verification.
  - An **unconfirmable** comparison (e.g. the template repository cannot be read) rejects with
    retryable **`503`** and is never reported as proven drift. `/fix` itself stays usable against the
    frozen model but adds an explicit warning, so the degradation is never silent.
  - All checks run inside the same per-user write lock and transaction as the save, so a `400` means
    nothing was written.

  The specialized, proven-pre-write `503` mapping is defined in the
  [API error mapping](overview.md#error-and-status-codes); treat any *unclassified* `503` as an
  uncertain mutation outcome and reconcile the current rule snapshot before retrying. The mechanism
  behind these guards — fingerprint construction, the normalization both sides share, and the
  atomicity argument — is owned by
  [../architecture/auto-fix.md](../architecture/auto-fix.md#applying-a-suggestion-fixstrategyapplier).
- **Frozen run settings.** The structured `attackScenario` and `enablePrivacy` settings belong to the
  frozen request snapshot; `isAttack` and `attackBudget` are derived response fields. Apply re-proves
  the candidate under that frozen context. Start a new verification to evaluate different settings.

The server then applies the token-verified suggestion to a deep copy of the persisted rules. The
unchanged complete snapshot means the earlier NuSMV evidence still describes the model being
changed; apply does not repeat the expensive strategy search.

**Response**: `FixApplyResultDto`

| Field | Type | Notes |
| :--- | :--- | :--- |
| `applied` | `boolean` | `true` on success |
| `strategy` | `String` | The applied strategy |
| `verificationEvidenceReused` | `boolean` | Always `true` for the public signed-suggestion flow: existing verification evidence was reused only after all rule/template/spec/device/environment drift checks passed atomically. Apply never repeats the strategy search, so this is the only evidence basis the response can report; the client rejects the response if it is not `true` |
| `appliedSuggestion` | `FixSuggestionDto` | Server-trusted suggestion actually applied; user-facing descriptions are included while internal rule/condition positions remain hidden |
| `previousRuleCount` / `currentRuleCount` | `int` | Rule-set size before/after the atomic write; particularly important for the destructive `remove` strategy |
| `message` | `String` | English API summary for logs and non-localized callers; the frontend uses the structured fields above to render a localized, scope-qualified result instead of treating this text as an unconditional guarantee |
| `rules` | `RuleDto[]` | The full persisted rule list after applying. The frontend validates its count and directly replaces the Board rule collection from this authoritative snapshot; it refreshes separately only when the apply response itself is unconfirmed. |
| `canUndo` / `canRedo` | `boolean` | Required authoritative Board-edit availability. A successful apply is one reversible `RULE_SET/UPDATE` edit and returns `true` / `false` |

Applying a suggestion is one user action even when it edits, reorders, adds, or removes multiple
rules. Undo restores the exact ordered pre-fix rule collection under the original rule ids; redo
re-applies the saved fixed collection. A later rule change that no longer matches the expected side
returns `409` rather than discarding newer work.

Effect per strategy: `parameter` overwrites the target condition's value (and relation);
`condition` adds/removes conditions; `remove` permanently deletes the flagged rules. A condition fix
that would leave a rule with no trigger conditions is rejected; which conditions are offered at all
is the candidate filter in [condition adjustment](../architecture/auto-fix.md). During apply, NuSMV-normalized
device references are mapped back to the current raw board node ids; an unmappable reference fails
the transaction without writing a rule.

`/fix` is a synchronous, server-bounded operation. Every NuSMV call
inside the fix pipeline is capped by the smaller of `NUSMV_TIMEOUT_MS` and the remaining
`FIX_TIMEOUT_MS` budget, including the wait for NuSMV execution capacity. The Board shows the
selected strategy, validation phase, and elapsed time. Closing the suggestion view calls the
request-specific cancellation endpoint before aborting transport; the backend interrupts the
tracked search and purges cancelled queued work. `/fix/apply` performs no strategy search and
keeps the dialog open until a definitive write response; transport uncertainty still triggers
authoritative rule reconciliation.

### `GET /api/verify/tasks/{id}/traces` — traces for an accepted background run

Returns the `TraceDto[]` produced by a specific async verification task, scoped to the
current user. Used by the frontend to display counterexamples for the task that just
finished, rather than mixing in traces from earlier/concurrent runs.

History UIs use `GET /api/verify/runs/{id}/traces` for the complete-result view and
`GET /api/verify/traces/{id}` for an individually selected counterexample; the
task-oriented path exists for the polling flow that already holds an accepted background
task id.
