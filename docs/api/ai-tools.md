# AI Tools

The IoT-Verify AI assistant is backed by any OpenAI-compatible LLM endpoint (configured via `llm.*`; see [configuration.md](../getting-started/configuration.md)) and uses tool/function-calling: the model selects a tool by its snake_case `name`, and the backend runs the matching implementation. Each tool declares itself via a vendor-neutral `LlmToolSpec` (`getDefinition()`); the `LlmProvider` adapter translates specs and messages to the underlying SDK, so tools never depend on an SDK type. All 53 tools are Spring beans that implement `AiTool` (most extend `AbstractAiTool`) and are dispatched at runtime by `AiToolManager`. The chat planner receives the complete registered catalog on every round, so it can choose zero tools for conversation or combine reads, recommendations, targeted mutations, atomic scene replacement, verification, and task-status operations from the user's meaning and conversation context. There is no keyword-selected tool subset. `AiToolManager.execute()` wraps every dispatch in a catch-all, so raw exception detail is never leaked back to the model. Read-only exceptions return an ordinary tool error; an exception or malformed result from a mutation-capable tool becomes an explicit unavailable outcome because the write boundary may already have been crossed.

**Tool responses are not the REST `Result<T>` envelope.** Internally each tool returns
a raw JSON string (built by `AiToolResponseHelper`): on error, `{ "error", "errorCode",
"status" }`; on success, a tool-specific body. For chat dispatch, `AiToolManager` adds
`resultStatus="SUCCESS"` and `resultAvailable=true`, or `resultStatus="PREVIEW"` for a
no-write confirmation preview. These execution fields do not replace the domain payload.
For mutation-capable tools, a successful or preview payload must also carry the tool-specific
authoritative marker that proves it reached its result boundary: for example `operation`,
`deleted`, `dismissed`, `cancellationAccepted` plus `taskId`, `taskAccepted` plus `taskId`, or
the formal `outcome`. A free-form `message` (even one that says the operation succeeded) is not
completion evidence. If the marker is absent or malformed after execution starts, the manager
converts the response to `RESULT_UNAVAILABLE`, marks that the mutation may have committed, and
the chat loop refreshes state before it can plan another dependent call.
That envelope only appears when a tool is
also exposed as an HTTP endpoint — e.g. `/api/board/*/recommend`, where the controller
inspects the tool's JSON (`throwIfToolError`) and wraps the result in
the endpoint's typed recommendation DTO inside `Result<T>` (see [board.md](board.md) and
[overview.md](overview.md)).

Verified against code on 2026-08-13. Source: component/aitool/, component/ai/.

## Argument Contract Notes

Tool argument schemas are JSON objects declared by each tool's `getDefinition()`.
The backend validates again at execution time and returns `{ "error", "errorCode",
"status" }` for invalid arguments. Root tool schemas set `additionalProperties=false`.
The OpenAI provider adapter forwards the complete structured schema, including root and
nested `additionalProperties`, `required`, array-item, and property constraints; it does
not flatten or stringify nested schema objects.
Every tool enforces that shape again at runtime, not only through the model-facing
schema. No-argument tools accept exactly `{}`. Action tools also enforce action-specific
fields and nested condition/runtime objects. A field for another action, an unknown
field, or a non-string JSON scalar in a string field is rejected before any read or
write; it is never ignored or coerced and then reported as success.

An otherwise completed tool result that cannot be safely serialized, bounded, persisted,
or returned is a third outcome, not a successful tool result. The same conservative outcome
applies when a mutation-capable tool throws unexpectedly or returns an empty, non-object, or
malformed result after execution starts, because the write boundary may already have been
crossed. It is returned as
`{ resultStatus: "RESULT_UNAVAILABLE", resultAvailable: false,
mutationMayHaveCommitted, message }`; individual paths may also add `warning`,
`errorCode`, or size metadata. The step is not counted as successful and is never retried
automatically. When
`mutationMayHaveCommitted=true`, the affected authoritative collection is refreshed and
the loop stops before later calls because their assumptions may now be stale; the visible
reply tells the user to inspect current state before retrying. A read-only unavailable
result is also not counted as success, but does not request a mutation refresh or prevent
independent later calls and planning rounds from returning useful evidence. An unexpected
exception from a read-only tool remains an ordinary failure because it cannot have committed a
Board or run-history mutation.

Async start tools distinguish a definitively rejected submission from either an accepted
task or an uncertain dispatch. A queue/dispatch failure whose still-pending row is
successfully removed returns the ordinary error and consumes no stored-task quota. If the
dispatch call fails and that conditional cleanup cannot be confirmed, the tool returns
`RESULT_UNAVAILABLE`, `errorCode=TASK_DISPATCH_OUTCOME_UNKNOWN`, the original `taskId`,
and its `statusTool`; the chat loop refreshes run history and the caller must poll that id
before retrying. Once submission returns an id normally, a later status-read or response-
serialization failure instead returns `taskAccepted=true`, the original `taskId`, the
matching `statusTool`, `resultAvailable=false`, and
`errorCode=ACCEPTED_TASK_STATUS_UNAVAILABLE`. That id must likewise be polled before any
retry because submitting the start tool again could create a duplicate task.

Numeric and boolean tool arguments use strict JSON scalar validation. Integer arguments
must be JSON integer numbers (not quoted strings or floats), positive id arguments must
fit in `long`, and boolean arguments must be JSON booleans. Optional count arguments
use their documented defaults only when omitted or `null`; explicit out-of-range values
return `VALIDATION_ERROR` instead of being silently clamped.
Recommendation `language` accepts only English or Chinese locale spellings normalized to
`en` / `zh-CN`; unsupported locales are rejected rather than silently becoming English.
`userRequirement` must be a JSON string of at most 2,000 trimmed characters. It is never
silently truncated.

Recommendation candidate counts form a complete audit trail: inspected candidates equal
kept plus filtered candidates, and raw candidates equal inspected plus truncated
candidates. `filteredItems` has one reason per filtered candidate. A zero-length AI array
is reported as "no candidates returned", not as backend filtering or a successful empty
recommendation set.
Rule recommendations ask the model for a short user-facing `reason` grounded in the
selected goal and actual device capabilities, and the Board shows it before apply. Neither
recommendation tool has a category argument or a candidate `category` field: a category was a
model-authored label with no verification semantics, it was dropped when a candidate was applied,
and its four fixed buckets duplicated what `userRequirement` and — for specifications — the
`templateId` already express. The request argument is rejected rather than ignored, but a `category`
the model still volunteers is stripped from the candidate, exactly as an unrequested `confidence` score
already was: discarding an otherwise valid recommendation over a label with no verification meaning is
exactly the failure the removed requested-category filter used to cause.

| Tool | Required / important arguments | Notes |
| --- | --- | --- |
| `add_device` | `templateName`, optional `label`, `x`, `y`, `state`, `currentStateTrust`, `currentStatePrivacy`, `variables`, `privacies`, `w`, `h` | `templateName` must be an exact available name returned by `list_templates`; `board_overview` describes instantiated Board devices and is not a template catalog. The targeted mutation preserves unrelated Board items, uses the same node-save authority as the UI, and triggers `REFRESH_DATA` for both `device_list` and `environment_list`. It creates exactly one device instance with a system-generated stable id independent of its display name. String-valued identity/runtime fields and nested local values are type-checked rather than coerced from numbers or booleans; unknown nested overrides reject the whole request. An explicit `label` is exact: a case-insensitive conflict returns `409 DEVICE_LABEL_CONFLICT` with `operation=notCreated`, `requestedLabel`, and an available `suggestedLabel`; no device is written and the caller must ask before retrying. Only an omitted label is generated and made unique automatically. Omitted `w`/`h` use the board default `176`/`128`. Missing local variable values/source labels/sensitivity labels are materialized from the selected template without overwriting explicit values and are named in `defaultsApplied`; the returned `device` is the effective persisted runtime. `initialStateSource=statelessPlaceholder` identifies the non-semantic `Working` canvas placeholder for a valid no-mode template. A partial state machine or missing valid `InitState` is rejected rather than assigned an invented runtime state. The response also returns the authoritative `environmentVariables` pool and itemized `environmentChanges` from the same transaction, so shared values introduced by the new device are not hidden. Template `InternalVariables` remain manifest/runtime-overrides data, not canvas nodes. |
| `edit_device` | `id`, `field=label\|runtime\|layout`; `label` for label; `state`/`currentStateTrust`/`currentStatePrivacy`/`variables`/`privacies` for runtime; `x`/`y`/`w`/`h` for layout | Edits one existing device in place, one aspect per call; every edit is reversible and targeted and needs no confirmation. Fields belonging to another `field` are rejected, not ignored. `label` calls the same compare-and-set rename as `PATCH /nodes/{id}/label` using the current label as the expected baseline, and cascades the new name into referencing specifications; a case-insensitive conflict returns `409 DEVICE_LABEL_CONFLICT` with `operation=notUpdated` and a `suggestedLabel`, and no write occurs. `runtime` builds the expected baseline from the device's current persisted runtime and the desired runtime from supplied fields (omitted runtime fields are preserved); a concurrent change returns `409 DEVICE_RUNTIME_CONFLICT` with the current device and no write. `layout` builds a complete expected position/size baseline from the current device, defaults omitted fields into the desired layout, and writes only if that baseline is still current; concurrent movement or resizing returns `409 DEVICE_LAYOUT_CONFLICT` with the latest device and no write. Every successful path returns the authoritative `operation` (`updated`/`renamed`/`unchanged`) and effective persisted `device`. Runtime and layout responses also return `changedFields`; label responses instead return `previousLabel`, `updatedSpecificationCount`, and `environmentChanges`. |
| `manage_environment` | `action=list\|set\|reset`; `name` for set/reset; optional `value`, `trust`, `privacy` for set | Reads or patches one shared environment variable without replacing the scene. `list` accepts no mutation fields, `set` changes only supplied fields, and `reset` accepts only a name and explicitly restores template defaults; fields belonging to another action are rejected instead of ignored. `set` returns previous/current values plus applied and preserved field names. Explicit null is rejected rather than treated as an implicit reset. A successful call refreshes `environment_list`, so a mutation performed through chat is reflected in the Board Environment Pool. `set` is compare-and-set: it reads the pool, submits that complete row (value, trust and privacy) as the `expected` baseline through the same `saveEnvironmentVariables` authority the UI uses, and a concurrent change to any of those fields rejects the write with `ENVIRONMENT_VARIABLE_STALE` (409) and the variable's current row, having written nothing. Baselining only the fields being written would let a partial update silently revert a field the caller did not intend to touch. `reset` is deliberately not compare-and-set: it clears all three fields so the template defaults are re-projected, carrying nothing over from a snapshot. |
| `delete_device` | `id`, `confirmed`; `impactToken` when confirmed | First call with `confirmed=false`: returns the user-facing device plus every rule/spec and no-longer-required shared environment variable that would be removed, with an opaque impact token; no write occurs. After a later explicit user confirmation, call with `confirmed=true` and that token. The public token is session-scoped and resolves server-side to the Board deletion impact token; any impact drift returns `409` with a fresh preview and no write. A completed delete returns itemized Environment Pool changes and refreshes devices, the Environment Pool, rules, and specs. |
| `check_duplicate_rule` | `newRule` | Deterministic duplicate check used by rule creation. `newRule` is an exact candidate shape containing only optional positive `id`, `conditions`, `command`, and optional `ruleString`; every nested object rejects unknown or wrongly typed fields. Each condition carries `targetType`; `targetType=api` omits `relation`/`value`, while `variable`/`mode`/`state` requires them. It does not call the external LLM and returns a stable `reasonCode`; English `reason`/`message` are technical diagnostics, not localized UI copy. |
| `check_rule_similarity` | `newRule` | Explicit AI semantic similarity check using the same exact candidate shape as `check_duplicate_rule`. It may call the configured LLM and returns `isSimilar`, `isDuplicate`, authoritative `requiresReview`, readable `matchedRule`, `similarity`, stable `reasonCode`, and technical `reason`/`message`; temporary model-facing candidate references and LLM prose are not exposed as ordinary UI concepts. |
| `manage_rule` | `action=add\|delete\|reorder`; for `add`, `conditions[].deviceId`, `command.deviceId`, and optional display-only labels / `label`; for `delete`, `ruleId`, `confirmed`, and preview `impactToken`; for `reorder`, `expectedRuleIds` and `ruleIds` | Stable references use the same `deviceId` spelling as `board_overview` and rule recommendations; the internal `RuleDto.deviceName` persistence name is not a tool argument or response field. Add accepts only the add shape, strictly validates every condition/command scalar, and appends the rule; delete-only fields cannot be silently ignored. AI-authored additions are also checked as a conjunction against declared state/variable domains, and target-device conditions must be compatible with the command API's non-empty `StartState`. It returns `verificationStatus=NOT_VERIFIED`, its one-based `executionOrder`, and a semantic rule view with separate ids/labels; creation is not a formal-verification conclusion. Delete is two-turn: `confirmed=false` returns the exact readable rule and an opaque token without changing it; `confirmed=true` is honored only after a later explicit user confirmation with that token. The complete confirmed rule is re-read and compared inside the same user-level transaction as deletion; drift returns `CONFIRMATION_STALE` with a fresh preview. `action=reorder` atomically replaces only the verification-significant execution order: `expectedRuleIds` copies the complete current order from `list_rules`, while `ruleIds` contains the complete desired order. Both must contain positive, unique ids, and the desired order must differ from the expected order. The same transaction rejects a changed current order or a list that no longer matches the current rules (`409 CONFLICT`, refresh and retry); an unchanged order is rejected with `400 BAD_REQUEST` without writing history. It is a reversible full-list replacement and needs no confirmation; it returns `operation=reordered`, `verificationStatus=NOT_VERIFIED`, and the new ordered rule views. Successful mutations refresh `rule_list`. |
| `recommend_rules` | optional `maxRecommendations`, `language`, `userRequirement` | Recommends complete, capability-validated automation rules from current board devices and existing rules. The model context includes complete template capabilities and the current shared Environment Pool, including explicit `modeValues`, domains, falsifiability, natural change, dynamics, transitions, API content/state behavior, and declared-or-derived descriptions. Each kept candidate has a required `name` that becomes the persisted rule name; there is no candidate `priority` because apply appends to the real user-controlled execution order. Candidates whose conditions have no common legal value are filtered as `contradictoryConditionGroup`; an action whose `StartState` conflicts with target-device conditions is filtered as `commandPrestateIncompatible`. `maxRecommendations` must be a JSON integer in `1..10` when provided and defaults to `5` when omitted/null; natural-language output follows `language`; `userRequirement` can steer the scenario/goal and is limited to 2,000 characters. Returned candidates never use an ambiguous `requiresUserInput` flag: an incomplete candidate is filtered rather than presented as directly applicable. An API event written as the equivalent `= TRUE` is kept, normalized to relation/value-free event syntax, and disclosed in `adjustedItems`; partial fields, `FALSE`, and other relations are filtered. |
| `recommend_related_devices` | optional `maxRecommendations`, `language`, `userRequirement` | Reads the authenticated user's saved board, current shared Environment Pool, and template repository on the backend. The model receives the complete template behavior capability view, including falsifiability, domains, dynamics, transitions, API signal/content behavior, and content descriptions; it must not infer behavior from names alone. `maxRecommendations` must be a JSON integer in `1..10` when provided and defaults to `5` when omitted/null; `userRequirement` is limited to 2,000 characters. The tool recommends device instances (`templateName`, an effective display label, optional advisory `intendedUse`/`suggestedPlacement`, and effective local runtime values); it does not mutate the board. The advisory context is not a persisted device field or formal-model input. |
| `recommend_scenario` | required `minDevices`, `minRules`, `minSpecs`, `maxDevices`, `maxRules`, `maxSpecs`; optional `language`, `userRequirement` | Generates one coupled, importable `iot-verify.board-scene` v5 **full-replacement draft** with devices, every required environment-pool item explicit, rules, and specifications. All six count fields must be JSON integers in `1..10`, and each minimum must not exceed its matching maximum. The model receives these explicit count ranges plus complete template behavior capabilities. The result echoes the minimums as `objectiveTargets`; `objectiveStatus=COMPLETE` only when the final canonical scene reaches every target, otherwise deterministic `objectiveIssues` identify missing or insufficient devices, rules, and specifications. It also includes structural `verificationReady`/`readinessIssues` plus deterministic `semanticWarnings`; a draft with no specifications remains saveable/applicable but is explicitly not ready for `verify_model`, while a structurally ready draft can still warn about filtered candidates, no retained automation rules, or devices unused by every retained rule/specification. Readiness and target completion are deterministic count/structure checks, not an NLP judgment that the rest of the user's prose was satisfied. The returned `rationale` is regenerated from the final canonical item counts and explicitly disclaims requirement satisfaction and formal-verification success, so model prose cannot describe rules or specifications that validation removed. The draft passes structure/capability checks but is not a safety conclusion or a board mutation. `userRequirement` is limited to 2,000 characters. AI device ids are temporary graph aliases; the tool assigns portable scene ids and rewrites all dependencies consistently before returning. A rule API event written as the equivalent boolean condition `= TRUE` is retained, normalized to relation/value-free event syntax, and reported in `adjustedItems`; partial fields, `FALSE`, or any other relation remain invalid. A specification API event may omit relation/value in model output and is canonicalized to explicit `= TRUE` in the returned portable scene so confirmed atomic application satisfies the strict Board persistence contract. Stateless devices omit canvas-only state/source/sensitivity fields. Before storage, the complete prospective response is measured against `CHAT_MAX_TOOL_RESULT_BYTES`; an oversized draft is not stored and does not replace the previous visible draft. It does not mutate the board. |
| `apply_scenario` | `confirmed` | Applies the latest non-empty, validated `recommend_scenario` draft stored for the authenticated chat session. The `confirmed` argument states the model's intent and does not decide the outcome: the gate is the user's own server-side confirmation, so `confirmed=true` cannot force a replacement and `confirmed=false` cannot cancel one the user already confirmed (a mismatch is applied and logged). Without that confirmation, or with no pending application, the call returns current/replacement counts, `verificationReady`, and `readinessIssues`, then starts a no-write full-scene replacement preview. With it, the stored portable scene is converted to the strict `BoardBatchDto` contract and committed through the same atomic Board replacement authority as UI scene import. The scene and Board impact token remain server-side; the model never has to repeat them from chat history. Board drift returns `409 BOARD_REPLACEMENT_STALE` with fresh current counts and requires another confirmation. Success refreshes `board_state`, reports `verificationStatus=NOT_VERIFIED`, and preserves the readiness status without blocking application. |
| `manage_spec` | `action`; for `add`, explicit string `templateId` plus condition lists; for `delete`, `specId`, `confirmed`, and preview `impactToken` | Adds are template-validated before persistence and return `verificationStatus=NOT_VERIFIED`; structure acceptance does not mean the property has passed verification. A supplied condition collection must be an array and every nested scalar must match its declared JSON type, so malformed sides cannot disappear as empty. Each A, IF, or THEN list is additionally checked as a conjunction; a provably empty state/value intersection returns `CONTRADICTORY_CONDITION_GROUP`. Non-API conditions require an explicit relation and value; only an API condition may omit them to materialize `= TRUE`. Tool-facing list/add/delete views expose structured conditions and `formulaPreview`, never an ambiguous raw `formula` field. Delete first returns an exact no-write preview and opaque token, then requires explicit confirmation with that token in a later user turn. The complete confirmed specification is re-read and compared inside the same user-level transaction as deletion; drift returns `CONFIRMATION_STALE` with a fresh preview. Successful mutations refresh `spec_list`. Conditions use `targetType` in `state`, `mode`, `variable`, `api`, `trust`, `privacy`; identifiers are literal template keys, not generated SMV aliases. A `variable` condition must also carry `variableSource` (`environment` or `reported`) and is refused without one — the two ask different questions once a device is compromised, so there is no safe default ([shared-value-semantics.md](../architecture/shared-value-semantics.md)). |
| `recommend_specifications` | optional `maxRecommendations`, `language`, `userRequirement` | Recommendations use the same condition model as `manage_spec`; every kept candidate has a required advisory `rationale`, while only `templateId` plus structured conditions become the formal property. The model receives complete template capabilities and the current shared Environment Pool, including user-overridden labels. Each A, IF, or THEN conjunction must retain at least one legal state/value; otherwise the candidate is filtered as `contradictoryConditionGroup`. A recommended `variable` condition without `variableSource` is filtered as `conditionMissingVariableSource` rather than completed on the model's behalf; the capability view exposes `deviceLocal` so the model can tell which readings allow `environment`. There is no verification-priority field because every accepted property is checked. `maxRecommendations` must be a JSON integer in `1..10` when provided and defaults to `5` when omitted/null; `userRequirement` is limited to 2,000 characters, `templateId` is required in the generated JSON and must be one of `"1"` through `"7"`, and `currentState` is not a valid condition key. The returned template display label is derived by the client from `templateId`; AI text cannot redefine it. |
| `add_template` | `name`, `manifest` | `manifest` must define modes/initial state/working states consistently. `InitState` exactly names one concrete complete `WorkingState`; wildcard/partial/case aliases are invalid. A multi-mode `WorkingState.Name` is a complete tuple; reused mode-state components must keep the same `Trust`/`Privacy` labels so the model can represent them losslessly. `APIs` are state-changing device commands, require at least one mode, use `Trigger: null`, and do not define an `Assignments` field. Every API explicitly supplies `StartState` (empty/`_` means any state) and boolean `Signal` (`true` = observable automation/specification event, `false` = command-only); observable routes cannot overlap another API or Transition. Autonomous conditional behavior belongs in a single-effect `Transition`: one concrete mode target or one assignment, never both/several. Transition triggers and assignment values are checked against declared domains. Every `InternalVariables[]` item explicitly sets `IsInside`, one domain (`Values`, including `TRUE/FALSE` for booleans, or `LowerBound`+`UpperBound`), and `FalsifiableWhenCompromised`; `IsInside=true` is instance-local and `false` is scene-shared. Scope/domain omission is invalid. Shared environment readings also explicitly set `Trust`/`Privacy`. Every `ImpactedVariables` name must get its domain from the same manifest: a readable external `InternalVariable`, or an impact-only `shared `InternalVariables`` entry that grants no read capability. Optional `Icon` is UI-only and does not affect behavior. The same backend template validator used by UI imports rejects concrete generated NuSMV identifier collisions, but does not reserve broad business-name prefixes such as `variable_`, `a_`, `trust_`, or `privacy_`. Mutates templates and refreshes `template_list`. |
| `delete_template` | `templateId`, `confirmed`, optional `impactToken` | `confirmed=false` previews the exact template, per-device blockers, and `editHistoryEntryCount` without writing. A later explicitly confirmed call must return the exact session-scoped preview `impactToken`; it resolves to the storage-layer impact token only after the binding is consumed. Stale template, usage, or edit-history commits return `409` with a fresh confirmable preview; blocked previews return `requiresUserConfirmation=false` because no valid replacement token exists. Completed deletion clears undo/redo history and refreshes `template_list`. |
| `reset_default_templates` | `confirmed`; preview `impactToken` when confirmed | Uses the same atomic default-template refresh authority as the Board UI. `confirmed=false` returns exact bundled-template, affected-device, blocker, itemized Environment Pool changes (change type plus previous/current value, trust, and privacy), and `editHistoryEntryCount` without writing. A later explicit default-template-reset confirmation plus the opaque preview token refreshes bundled defaults while preserving custom template names, clears undo/redo history, then refreshes `board_state`. Deletion, scene-replacement, and reset confirmations are separate authorization kinds. |
| `manage_board_history` | `action=availability\|undo\|redo\|clear`; `confirmed` and preview `impactToken` for `clear` | Reads authoritative undo/redo availability or invokes the same conflict-aware reversible edit journal as the Board UI. `undo`, `redo`, and `clear` are used only on an explicit user request. Clear first returns the exact entry count and availability without changing Board data; a later structured protected-action confirmation plus the opaque token discards only that unchanged journal state. A successful undo/redo returns its entity type, original operation, current collection counts, and remaining availability. Applied undo/redo and confirmed history clear refresh `board_state`; empty history is an explicit no-op and emits no action receipt. |
| `clear_board` | `confirmed`; preview `impactToken` when confirmed | Atomically clears devices, the Environment Pool, rules, specifications, and Board edit history. `confirmed=false` returns exact current counts and performs no write. A later structured destructive confirmation plus the same session-scoped token authorizes replacement with an empty Board only while the preview is still current. An already empty Board is an explicit no-op. |
| `list_async_tasks` | optional `kind`, `status`, `initiator`, `limit` | Discovers non-completed verification, simulation, and bounded counterexample-search tasks across conversations and UI/assistant initiators, newest first. It supplies the task ids needed by status, cancellation, and dismissal tools instead of assuming the current chat created the task. Completed conclusions belong to the corresponding run-history tools. |
| `list_verification_runs` | optional `limit` | Lists every completed formal-verification conclusion, including `SATISFIED` runs that have no counterexample trace. Summaries expose outcome, completeness, skipped/disabled counts, and counterexample count. |
| `get_verification_run` | `runId` | Loads one completed formal run with per-spec conclusions, model completeness, snapshot, semantics, and generation issues. Raw NuSMV stdout is omitted; counterexample steps remain owned by `get_trace`. |
| Async task tools | `taskId` for status/cancel operations | Start tools return the authoritative accepted task snapshot, including its current lifecycle status, progress, frozen model scope, semantics, and `taskId`; acceptance is not completion. A confirmed pre-dispatch failure removes its pending row so it consumes no stored-task quota. If conditional cleanup is unconfirmed, `TASK_DISPATCH_OUTCOME_UNKNOWN` preserves the `taskId` and matching `statusTool`; poll it before retrying. If the initial status cannot be returned after acceptance, the result instead preserves `taskAccepted=true`, `taskId`, and `statusTool`; poll it before retrying. Status results are compact model-facing projections: execution logs and raw NuSMV stdout stay on REST/debug surfaces. The top-level `progress` and nested `task.progress` repeat the same authoritative live value, even when the persisted task snapshot is slightly behind. Completed verification returns `runId` plus `nextTool=get_verification_run`; a completed saved simulation returns `simulationId` plus `nextTool=get_simulation_trace`. Polling/cancel tools require the task id. |
| Trace tools | `traceId` or `simulationId` depending on domain | Verification traces use `traceId`; simulation traces use `simulationId`. |

Every tool result is measured as UTF-8 before it is persisted or returned to the model.
Results over `CHAT_MAX_TOOL_RESULT_BYTES` become a bounded `RESULT_UNAVAILABLE` response
with `errorCode=TOOL_RESULT_TOO_LARGE`; mutation-capable tools conservatively report that
state may already have changed. `list_templates(detail=true)` returns the semantic manifest
but deliberately omits the UI-only `Icon` field.

The three state-sequence detail tools, `get_trace`, `get_simulation_trace`, and
`get_fuzz_finding`, page their projected states before this global result boundary.
`stateOffset` is optional, zero-based, defaults to `0`, and accepts any non-negative
32-bit integer;
`stateLimit` is optional, defaults to `10`, and accepts `1..10`. Responses report
`stateCount`, `stateOffset`, `stateLimit`, `returnedStateCount`, `hasMoreStates`, and
`nextStateOffset` when another window exists. An offset beyond the end is a valid empty
window rather than a validation failure. Fuzz-finding input events are restricted to the
same returned state window.

A `get_trace` state may additionally carry `loopStart: true` or `loopBack: true`, present only when
true and only on verification counterexamples — simulation and fuzz traces are finite paths that never
carry them. They mark the infinite cycle NuSMV ends on, which for a liveness specification
(`violatedSpecification.templateId` 2, 5, 6 — emitted for exactly this decision) *is* the violation
rather than any single state; see
[verification-flow.md](../architecture/verification-flow.md#parser-boundaries). They cannot be
inferred from the values. NuSMV closes the path by re-printing the loop entry, and how that looks
depends on the cycle length (measured on NuSMV 2.7.1): a one-state cycle prints no variable lines, so
the closing state merges identical to its predecessor and the trace reads as a stalled or truncated
run; a longer cycle prints the deltas that return to the entry, so it reads as the path simply
continuing. Recovering the flags would mean comparing against the loop entry, and paging means one
window need not contain both ends of the cycle.

`get_trace` and `get_simulation_trace` return the run's `modelSnapshot`, which carries
`environmentProvenance` — the frozen per-shared-value evolution rules. The assistant needs it to
explain a changed environment value: the states alone show that `temperature` moved from `20` to
`21`, not whether a device did it or the declared interval permitted it. Because the rules come from
the snapshot rather than the live Board, an assistant explanation of a historical run stays correct
after the Board changes, and matches what the frontend shows for the same run. Field contract:
[verification.md](verification.md#environmentvalueprovenancedto).

All recommendation tools share one behavior-capability projection. It includes explicit
`modeValues`, full variable/environment domains, falsifiability and natural change,
working-state dynamics, transition triggers/assignments, API triggers and start/end
states, impacts, content behavior, and sensitivity labels. Every description has
`descriptionSource=declared|derived`; an empty authored description is replaced only by
a deterministic summary of modeled fields, not guessed product behavior.

Scenario validation applies the same conjunction checks as the standalone tools. A rule
with mutually exclusive triggers, a rule whose target predicates conflict with the
command API's `StartState`, or a specification with an unsatisfiable A/IF/THEN group is
reported in `filteredItems` and omitted from the returned draft.

Recommendation validation also removes a condition group or command prestate only when
the current runtime plus declared APIs, transitions, dynamics, and natural-change rates
prove it unreachable. The analysis deliberately over-approximates triggers and open
environment behavior, so uncertainty keeps a candidate instead of inventing a false
negative. Concretely, value narrowing applies only to device-local (`IsInside=true`)
variables, which the generated model holds constant when nothing writes them. Shared
environment variables are never narrowed: the model gives every enum environment variable an
unconstrained `TRUE: {<all declared values>}` branch, so the Environment Pool value is only
the initial state and every declared value is reachable immediately. A sensor reading the
user wants to reason about ("when smoke is detected") must therefore not be filtered out
because the pool currently holds a different value. Direct user-requested targeted mutations retain the ordinary Board contract;
this extra reachability filter governs AI recommendations.

Usable successful tools are translated by the chat service into frontend refresh commands for
every board collection they may have changed: device creation refreshes `device_list`
and `environment_list`; device deletion additionally refreshes `rule_list` and
`spec_list`; environment operations refresh `environment_list`; and template mutations
refresh `template_list`. Synchronous verification, asynchronous task creation or
cancellation, and saved-trace deletion refresh `run_history`. Stopping the chat stream
does not roll back an already-started tool; the frontend performs a full reconciliation
as documented in [chat-sse.md](chat-sse.md).
An unavailable result from a possibly committed mutation requests the same refresh but
is reported separately as unconfirmed, never as a usable success.
Confirmed meaningful actions also attach a typed `assistantAction` receipt and, when the
tool returned a specific bounded summary, `assistantSummary` to the primary refresh. The
client shows the exact summary (or the localized typed fallback) only after authoritative
state has loaded. Tool-specific
markers such as `operation`, `changesApplied`, `taskAccepted`, `cancellationAccepted`,
`deleted`, and `dismissed` decide whether an action occurred; a generic success message is
insufficient. Lists, previews, rejected alternatives, semantic no-ops, and unaccepted
cancellations therefore do not claim that the assistant changed anything. The final-reply
evidence guard makes the same distinction: merely calling a mutation-capable multi-action tool
does not support a completion claim unless that exact result produced an action receipt.
Verification,
simulation, and counterexample-search records created through a chat tool persist
`initiator=AI_ASSISTANT`; direct UI/REST work persists `USER`.
No-write previews and proposed alternatives carry `requiresUserConfirmation=true`, do
not emit refresh commands, and stop the current tool loop so the model cannot approve its
own deletion, reset, rename, or substitute choice. When protected work is pending, the
client reads the server-authoritative pending kind and sends an explicit structured
`CONFIRM` or `CANCEL` button command. Ordinary message text and model classification
cannot authorize deletion, formal-fix application, Board/history clear, bundled-default
reset, or full-scene replacement. The model
may still classify only a non-destructive alternative choice; malformed/unavailable
classification authorizes nothing, and merely including `confirmed=true` in
model-generated arguments is never authorization.
Each protected mutation preview issues a random, opaque
`impactToken` bound on the server to the authenticated user, chat session, tool name,
target, and canonical preview digest. A session holds at most one such pending protected
action. The token expires after 15 minutes and is consumed atomically before the mutation,
so it cannot authorize another target or a second mutation in the same planning turn. A
wrong token, changed preview, expired token, cross-user/session use, or replay returns
`409`, makes no change, and asks the user to review a fresh preview. An ordinary question
or task update preserves the live preview; explicit cancellation, a replacement preview,
session/account deletion and expiry invalidate it. The pending action is stored in the
shared database, so a normal backend restart or load-balanced confirmation turn preserves
it until expiry or explicit cleanup.
On an explicit confirmation turn, the chat service injects the compact pending tool,
target, and token from this server-side state. Confirmation therefore remains executable
even when the original detailed tool result is larger than the persisted chat-history
window; the visible assistant response must never expose that internal token.
For a device-name conflict, the assistant must explain that nothing was created and ask
whether to use the returned `suggestedLabel` or another name before calling the tool
again. Visible replies summarize the reason, target, alternative, and collateral impact
that apply. Saved verification/simulation trace deletion follows the same two-turn
contract.

## Board

| Tool | Summary |
| --- | --- |
| `board_overview` | Return the current semantic board: device runtime values, shared environment pool, rule-derived edges, typed rules, and typed specifications. Stable device ids remain separate tool references; every natural-language condition/command summary uses the current device label. Specifications include structured conditions and an explicitly named `formulaPreview`, not only template/count metadata. |
| `manage_environment` | List or patch/reset one shared environment variable through the same board authority as the UI. |
| `manage_board_history` | Read undo/redo availability, explicitly undo/redo, or preview/confirm clearing the undo/redo history through the same authoritative edit journal as the Board UI. History clear never changes current Board data. |
| `clear_board` | Preview exact current counts, then atomically clear the whole Board only after a later structured destructive confirmation. |

The assistant uses `board_overview` as the first source of truth for current-scene
questions, including device, rule, and specification counts. To extend an existing
scene, it can then combine `recommend_related_devices`, `recommend_rules`,
`recommend_specifications`, `add_device`, `manage_environment`, `manage_rule`, and
`manage_spec` in one contextual plan. Existing scene content is preserved unless the
user explicitly requests a complete replacement/import draft through
`recommend_scenario`.

## Scene Recommendation

| Tool | Summary |
| --- | --- |
| `recommend_scenario` | Use AI to design one coupled, importable scene draft whose device aliases, shared environment variables, rules, and specifications are validated as a single semantic unit and converted to portable references. The draft is not formally verified and does not mutate the board. |
| `apply_scenario` | Preview or atomically apply the current chat session's latest validated scenario draft without decomposing replacement into device/rule/specification deletions and additions. |

`recommend_scenario` is the scene-level tool for the Action Dock "AI Scene" workflow.
It is not implemented as three independent recommendations glued together. The LLM is
asked for one full scene draft, and the backend keeps only items whose cross-references
are valid inside that draft: device templates must exist, generated device initial
runtime must match the template, environment variables must be declared or impacted by at
least one kept device template, rule sources/commands must use template capabilities,
specification conditions must reference generated scene device ids, and the returned
`scene` follows the same canonical import/export contract as Board scene JSON. Kept
specifications contain `templateId` and structured conditions; derived `templateLabel`,
formula, device-list, and condition display caches are not returned. Every environment
variable required by a kept template is emitted exactly once with explicit value,
trust, and privacy; duplicate AI entries are reported in `filteredItems` instead of
silently overwriting one another. The model's device ids are temporary aliases within
one response. The validator assigns deterministic `device_1`, `device_2`, ... scene
references and rewrites rule sources/targets, content sources, and specification
conditions together; no AI alias becomes a user-facing identity or a NuSMV variable.
The REST response uses dedicated portable DTOs rather than reusing Board persistence
DTOs. Template wrappers contain only `name` and `manifest`; devices contain only the
standard scene fields; and specification conditions contain only `deviceId`,
`targetType`, `key`, optional `propertyScope`, `variableSource` (required for
`targetType: variable`), `relation`, and `value`. Jackson therefore
cannot reintroduce null database ids, template flags, condition sides/display labels, or
state-label fields that the standard importer correctly rejects as internal/derived.
Kept
devices/rules/specifications are candidate-atomic: if a generated device has an invalid
initial state or trust value, geometry outside the portable canvas domain (finite
coordinates within `-1000000..1000000`, width `80..2000`, height `60..2000`), or if any
nested rule source, rule content binding, or
specification condition fails validation, the whole generated candidate is filtered with
an item-level reason instead of being returned after silently defaulting or dropping only
the invalid nested item. Prefix-like names (`a_*`, `trust_*`, `privacy_*`, `variable_*`) are literal user
names here too; NuSMV-generated aliases are not exposed as tool arguments.
Rule `name` remains optional display metadata, as it is in standard scene JSON. A missing
AI title does not invalidate otherwise complete trigger/action semantics; the preview
uses its one-based rule label instead of filtering the rule.
If validation removes every generated device, the tool returns an empty canonical `scene`
with a no-applicable-scene message rather than a misleading "complete scenario" success.
Every successful tool response also reports `objectiveTargets`, `objectiveStatus`, and
`objectiveIssues` from the final canonical scene. The three required `min*` arguments are
the explicit target, and `requestedCount` is their sum. `COMPLETE` means that the final
device, automation-rule, and formal-specification counts all meet their corresponding
minimums. A zero count uses stable `NO_DEVICES`, `NO_AUTOMATION_RULES`, or
`NO_SPECIFICATIONS`; a positive count below its target uses `INSUFFICIENT_DEVICES`,
`INSUFFICIENT_AUTOMATION_RULES`, or `INSUFFICIENT_SPECIFICATIONS`. This is a deterministic
count boundary, not a claim that any other natural-language requirement was satisfied or
that verification passed.

When `recommend_scenario` runs inside chat, the backend keeps the latest non-empty
validated scene for that authenticated user/session for up to one hour. A failed or empty
new recommendation does not erase the previous valid draft. Every response reports
`draftStored` and `previousDraftRetained`; when the latter is true, the message explicitly
warns that "apply latest draft" still refers to the earlier valid draft. The raw scene is
not reconstructed from the model's later conversation window. The prospective response,
including its template snapshots and draft-status fields, is serialized and measured before
the store write. If it exceeds `CHAT_MAX_TOOL_RESULT_BYTES`, the tool returns a compact
`TOOL_RESULT_TOO_LARGE` error, stores nothing, and leaves any earlier visible draft unchanged.
Asking the assistant to
apply it calls `apply_scenario`, which reads the authoritative current Board replacement
preview and returns both current and replacement counts without writing. The resulting
pending application is valid for 15 minutes. An explicit confirmation such as
`confirm applying the scenario` or `确认应用` is recorded server-side, and that recorded
confirmation — not the tool's `confirmed` argument — is what admits the write on the next
call. The tool then supplies the stored scene and impact token
directly to `BoardStorageService.saveBoardBatch`, so devices, shared environment values,
rules, specifications, and required template snapshots commit or reject together.
It never simulates replacement through a sequence of `delete_device` calls.

If the Board changes after preview, the atomic storage authority writes nothing and
returns a fresh preview; the assistant asks for another confirmation. A new scenario
recommendation replaces the earlier draft, a normal intervening message clears only a
pending application preview, and session/account deletion or expiration removes the
database-backed draft. A normal backend restart or instance switch does not discard it.
Successful application clears the draft and refreshes the complete
frontend `board_state`. Structural validation and persistence do not constitute formal
verification, so the result remains `NOT_VERIFIED` until a verification run completes.

The response separates three outcomes. `filteredItems` explains candidates rejected by
validation, `truncatedCount` counts raw candidates never inspected after a requested
limit was reached, and `adjustedItems` explains deterministic values applied to kept
candidates or required environment entries added for completeness. Each adjustment is
`{ type, index?, reasonCode, reason, label?, appliedValues }`. `count` is the final total
of devices plus environment variables plus rules plus specifications; `validatedCount`
counts kept raw candidates and therefore need not equal `count` when required environment
entries were added.

## Devices / Nodes

| Tool | Summary |
| --- | --- |
| `add_device` | Add one device instance to the canvas from a template. An explicitly requested display name is never silently changed; a conflict is a no-write result with an available suggestion. Template internal variables stay in the manifest and optional runtime overrides; they are not separate canvas nodes. |
| `edit_device` | Edit one existing device in place — `field=label` renames it (label conflicts are a no-write result with a suggestion), `field=runtime` compare-and-set-replaces its initial state/trust/privacy/variables, and `field=layout` compare-and-set moves or resizes its canvas card. One reversible, targeted aspect per call; other board items are preserved. |
| `delete_device` | Preview the device and all cascading rule/spec/Environment Pool removals, then perform the atomic delete only after later explicit confirmation and an unchanged impact token. |
| `search_devices` | Search devices on the canvas. `keyword` is a case-insensitive substring matched against device name, template name, and device id; empty returns every device. |

## Rules

| Tool | Summary |
| --- | --- |
| `check_duplicate_rule` | Use deterministic typed trigger/action signatures to check whether a new rule duplicates existing rules before save. This path is fast and does not call the external LLM. |
| `check_rule_similarity` | Use AI to analyze whether a candidate rule is semantically similar to existing rules. This is an explicit user-triggered analysis, not a required save gate. |
| `list_rules` | List automation rules on the current board as semantic tool views, optionally filtered by stable reference, current device label, rule label, trigger, or action. |
| `manage_rule` | Add a validated automation rule, preview and later explicitly confirm deletion of one rule, or `action=reorder` to compare-and-set the full verification-significant execution order from `expectedRuleIds` to a different `ruleIds` order (both list every current rule id exactly once). |
| `recommend_related_devices` | Use AI to recommend new devices from available templates that enhance the IoT system. |
| `recommend_rules` | Use AI to analyze device capabilities (APIs, variables, modes, states) and recommend automation rules for linkage, security, energy-saving, or comfort scenarios. |

Rule recommendations use canonical board node ids in `conditions[].deviceId`,
`command.deviceId`, and `command.contentDevice`. Display names are returned separately
for UI text. Each returned condition must include `targetType`. For
`targetType=api`, the condition may use only APIs where the template has
`Signal=true`, and its canonical returned form omits `relation`/`value`. The equivalent
AI spelling `relation="="`, `value="TRUE"` is normalized and reported as an
`apiEventSyntaxNormalized` adjustment; partial fields, `FALSE`, or another relation are
filtered as `invalidApiEventSyntax` rather than silently erased. For `variable`, `mode`, and
`state`, both `relation` and `value` are required. `mode`, `state`, and enum-variable
values support only `=`, `!=`, `in`, `not in`; numeric variables support those operators
and additionally support ordering comparisons. Unknown device ids, non-signal API conditions, or invalid
relation operators are filtered out by the tool validator before the result is returned.
`command.contentDevice` and `command.content` must appear together, the selected item
must be declared by the source template, and the target action must declare
`AcceptsContent=true`. A candidate that attaches content sensitivity to an ordinary
action is filtered as a whole with an item-level reason.
`recommend_rules`, `recommend_related_devices`, and `recommend_specifications` all accept
optional `language` (`en` by default; `zh*` normalizes to `zh-CN`) and optional
`userRequirement`. The backend localizes its own fallback/success `message` field and
instructs the LLM to return natural-language fields in the same language. Recommendation
responses include `requestedCount`, `validatedCount`, `filteredCount`, `filteredItems`,
`rawCandidateCount`, `inspectedCount`, and `truncatedCount` so the UI can distinguish
validated suggestions, inspected-but-rejected suggestions, and raw AI candidates that
were not inspected after the requested limit was reached. Each `filteredItems[]` entry
has `{ type, index, reasonCode, reason, label? }`, where `index` is the 1-based candidate
position within that recommendation type and `reason` is localized according to
`language`. The device recommendation tool builds its device/template context on the
backend from the saved board; the frontend and assistant do not pass a second simplified
copy of board state. Device recommendations are also candidate-atomic for initial runtime:
if a candidate includes an invalid initial state, malformed runtime arrays, unknown local
variables, invalid local variable values, or invalid trust/privacy values, the whole
candidate is filtered rather than returned with those runtime settings silently removed.
An explicit blank runtime value is invalid rather than another spelling of "use default".
Omitted display labels, states, and local values are materialized from location/template
defaults. Omitted trust/privacy labels remain absent instance overrides so the active
template stays authoritative; only explicit AI values create advanced label overrides.
`adjustedCount` and `adjustedItems` identify the deterministic value/layout
changes as `{ type, index?, reasonCode, reason, label?, appliedValues }`; they are not
validation failures or hidden mutations.
Rule recommendation responses use the same `adjustedCount`/`adjustedItems` shape for
semantics-preserving API-event normalization. The Board presents those adjustments
before the user applies the candidate.
Rule/specification candidates are ordered by recommendation relevance. They do not expose
`priority`: rule execution priority is the persisted Board order, and specifications have
no checking priority or short-circuit semantics. A rule candidate's required `name` is
the exact name saved on apply. A specification candidate's required `rationale` is shown
as recommendation context only; it is not part of the stored or checked property. Missing
names/rationales are itemized filters, and the REST boundary rejects an incomplete kept
candidate rather than returning HTTP 200.
Applying a recommended rule on the Board uses the same explicit AI semantic similarity
check endpoint as the rule builder before it mutates saved rules. A kept recommendation
is appended at the end of the current execution order; it does not silently outrank an
existing automation. The user can inspect and reorder it in the Board rule inspector.
The Board distinguishes an application attempt from a committed item for rule, device,
and specification recommendations. While validation/similarity checking or persistence
is outstanding, the candidate is disabled and labeled as not yet confirmed. It becomes
"Added to board" only after the authoritative mutation response or a reconciliation read
finds the semantic item. Application state is scoped to the recommendation response
epoch, so a late completion cannot mark an unrelated candidate at the same list index
after regenerate/close.

`list_rules` and `manage_rule` use that same reference shape. A presented rule is
`{ ruleId?, label?, conditions[], command?, description }`; condition and command rows
carry `deviceId` beside `deviceLabel` and readable summaries. `ruleId` remains available
to the tool planner for delete correlation, while visible replies use labels and
descriptions unless the user explicitly requests technical ids. A created rule reports
`verificationStatus=NOT_VERIFIED`: capability validation and persistence do not prove
the resulting board satisfies its specifications.

Duplicate and similarity checks identify a match with readable rule text in
`matchedRule`, not a rule database id. Their negative result means only that the selected
deterministic signature or AI pass found no obvious match; it is not a proof that the
automation set is conflict-free.

Device recommendations are instance suggestions, not only template names. Returned items
include `templateName`, an effective `suggestedLabel`, optional advisory
`intendedUse`/`suggestedPlacement`, and,
when the template has the corresponding runtime concepts, `initialState` and
`initialVariables`; explicit advanced overrides may additionally supply
`currentStateTrust`, `currentStatePrivacy`, per-variable trust, and `initialPrivacies`.
Omitted labels use the template and are not copied into the recommendation. The two
current-state fields are source and sensitivity labels for the selected initial state;
they are not authentication, attack probability, or access control.
Runtime values are normalized against the selected template and local variables only.
Display labels are unique across all board instances ignoring case, including across
different templates; the same template can still be recommended more than once when the
labels or advisory contexts differ. Applying one suggestion persists only the device
type, effective label, layout, and effective runtime values. Advisory context is shown
as recommendation rationale and is not silently presented as a saved device property.
Creating the device may add template-required shared values to the Environment Pool;
the Board previews currently missing names and displays the authoritative itemized
changes returned by the create transaction.

Whole-response parsing is not reported as an empty successful recommendation. If the
provider returns malformed JSON or omits the required recommendation/scene arrays, all
four recommendation tools return `{ errorCode: "AI_RESPONSE_INVALID", status: 502,
phase: "response_parse" }`; REST recommendation endpoints surface HTTP 502 and the
Board enters its error state. `filteredItems` remains reserved for individual candidates
that were parseable enough to inspect and reject.
The explicit AI rule-similarity check uses the same boundary: an empty, malformed, or
schema-less AI answer is `AI_RESPONSE_INVALID` rather than `isSimilar=false`, so callers
cannot mistake a failed analysis for evidence that no similar rule exists.

## Specifications

| Tool | Summary |
| --- | --- |
| `list_specs` | List formal specifications on the current board. |
| `manage_spec` | Add a validated formal specification, or preview and later explicitly confirm deletion of one specification. |
| `recommend_specifications` | Use AI to analyze board devices, rules, and existing specs and recommend new formal specifications for correctness, safety, and reliability. |

Specification recommendations use canonical board node ids in `deviceId`; `deviceLabel`
is display-only. Recommendations with illegal template ids, missing `targetType`, or
unknown device ids are filtered before returning to the frontend. The prompt schema,
tool-side validator, and backend DTO share the same core contract:
`templateId in "1".."7"`, `targetType in state|mode|variable|api|trust|privacy`, relation in
the supported operator enum for that target type, and non-empty `value` for non-API
conditions. `manage_spec` add operations must provide a string `templateId`; the tool
rejects missing/non-string ids instead of defaulting or coercing one. A condition-list
field that is present but not an array rejects the request instead of becoming an empty
side. State, mode, API, trust, privacy, and enum variables use `=`, `!=`, `in`,
`not in`; numeric variables use those operators and may additionally use ordering comparisons. Spec
`targetType=api` may use only `Signal=true` APIs and boolean values (`TRUE`/`FALSE`);
the API authoring helper defaults an omitted relation to `=` and an omitted value to
`TRUE`. Every non-API condition must supply both fields; the backend does not
guess equality from a lone value. Spec `targetType=trust` and
`targetType=privacy` values are constrained to `trusted|untrusted` and
`public|private`. State-level trust/privacy conditions use the ordinary user-selected
state or mode key; generated `mode_state`, `trust_*`, and `privacy_*` identifiers are
never tool arguments. Template shape is strict:
`1`, `2`, `3`, and `7` use `aConditions` only; `4`, `5`, and `6` use
`ifConditions`/`thenConditions` only. `manage_spec` does not accept an AI-authored
formula preview or template label and does not submit `formula`/`devices[]` as write
authority. It sends the accepted structured conditions to board storage; the storage
boundary rebuilds all display metadata from the canonical device ids and current board.

The persisted DTO field remains `formula` because board verification rebuilds the actual
expression from `templateId + conditions`. AI tool views deliberately rename it to
`formulaPreview`. `list_specs` and `manage_spec` return current device labels beside
stable references and keep `specId` only for tool correlation. Created specifications
report `verificationStatus=NOT_VERIFIED`; only a later run's `checkedExpression` and
outcome describe what NuSMV actually checked. AI formula previews use user concepts such
as device labels, `Environment.<name>`, `controlSource`, and `sensitivity`; generated
NuSMV instance names and `a_`/`trust_`/`privacy_` identifiers are never preview text.
Validation/filter explanations refer to conditions with one-based display positions
(`Condition 1`, not an internal zero-based array index).

Rule and specification recommendation tools canonicalize AI output before returning
results. Device references are resolved to canonical board node ids, relation aliases
are normalized (`IN` becomes `in`, and `NOT_IN`/`NOT IN` becomes `not in`), and template
keys/enum values are rewritten to the exact names declared in the current device
templates. For set relations, ordinary enum-like values may be separated with comma,
semicolon, or pipe. Multi-mode `state` values reserve semicolon for the state tuple
itself, so AI output must separate state tuples with comma or pipe, for example
`home;idle,sleep;idle`.

## Templates

| Tool | Summary |
| --- | --- |
| `add_template` | Add a custom device template defining states, transitions, and APIs. |
| `delete_template` | Preview a template, its device-instance blockers, and an impact token; delete only after later explicit confirmation of that unchanged preview. |
| `list_templates` | List available device templates. Detail mode includes semantic manifest fields but never the UI-only icon payload. |
| `reset_default_templates` | Preview and, after an explicit action-specific confirmation, atomically refresh bundled defaults and reconcile affected Board/environment data while preserving custom templates. |

`add_template` uses `backend/device-template-schema.json` as the manifest authority.
The tool validates the raw `manifest` JSON before DTO mapping and persistence, matching
the REST template-import path. Use PascalCase manifest keys; `APIs[].Trigger` must be
`null`, and `Assignments` is not an API field. An API is a device action, not a network
endpoint. `StartState` and `Signal` are explicit required semantics; a signal is true
only for the step in which its unique declared state route actually occurs. Conditional
behavior is represented by `Transitions[].Trigger`.
Each accepted Transition has one atomic modeled effect: either one concrete mode target
or one internal/shared-variable assignment. Trigger attributes/values and assignment
targets/values must fit the manifest domains; an impact-only shared declaration (`Reads: false`) can be
written but not read as triggers. `WorkingStates[].Dynamics` is also domain-driven:
numeric targets use `ChangeRate`, enum/boolean targets use `Value`, and every target must
be a declared local variable or `ImpactedVariables` entry. Optional
`Icon` may be a self-contained `data:image/...` URI; remote URLs are rejected. It is ignored
by NuSMV generation and exists only for frontend rendering. Each internal variable must include
`FalsifiableWhenCompromised`: `true` means a compromised instance may report any value
inside that variable's declared domain and the source becomes untrusted. API presence
does not infer this behavior, and attack modeling never widens the declared domain.
Every shared numeric `InternalVariable` must explicitly declare `NaturalChangeRate`; device-local
numeric variables may omit it and then retain their value unless a structured Dynamic or Transition
changes them. What the declaration means is owned by
[../architecture/shared-value-semantics.md](../architecture/shared-value-semantics.md#5-natural-evolution).

## Simulation

| Tool | Summary |
| --- | --- |
| `simulate_model` | Run a transient synchronous NuSMV random simulation on the board, returning result counts plus compact initial/final user-semantic state previews. |
| `simulate_model_async` | Submit an asynchronous NuSMV simulation task and return its current status, progress, requested steps, effective attack/privacy context, frozen model snapshot, model semantics, and taskId for polling. Acceptance is not completion. |
| `simulate_task_status` | Query status and progress by taskId without execution logs. A completed saved trajectory exposes `simulationId` and `nextTool=get_simulation_trace`. |
| `cancel_simulate_task` | Cancel an async simulation task by taskId. |
| `dismiss_simulate_task` | Preview a failed or cancelled simulation task and its retained diagnostics, then remove it only after explicit confirmation with the returned `impactToken` in a later user turn. It refuses active tasks (cancel first); saved traces are removed with `delete_simulation_trace`. |
| `list_simulation_traces` | List all saved simulation traces for the current user. |
| `get_simulation_trace` | Get a saved simulation trace by simulationId with a bounded, pageable state window. |
| `delete_simulation_trace` | Preview a saved run and receive an opaque `impactToken`, then delete it only after explicit confirmation with that token in a later user turn. |

`simulate_model` and `simulate_model_async` accept `attackMode=none|exact`. Exact mode
requires `attackPoints`, whose items are `{kind:"device",deviceId}` or
`{kind:"automation_link",ruleId}`. Device points accept the canonical id returned by
`board_overview` and normalize it at the model boundary; link points use the persisted
rule id. Simulation does not accept an attack budget and never
chooses compromised points randomly. Unknown, empty, duplicate, or inapplicable points
are rejected before execution.

Both tools capture the persisted devices, Environment
Pool, rules, specifications, and device-template definitions in one per-user locked
transaction. Simulation uses the devices, Environment Pool, rules, and exact referenced
manifests from that snapshot; it never combines collections or template versions read at
different times. The captured manifests are passed unchanged through service validation
and, for async runs, task acceptance. Both tools then use the same NuSMV runtime
validation as the REST services. Tool-argument shape/type errors, such as non-integer
`steps`, `steps` outside `1..100`, exhaustive mode, or exact mode without points, return
`VALIDATION_ERROR` with status `400` before the board is loaded. Service-layer semantic
validation errors, including a point outside the current attack surface, return a
structured `BUSINESS_ERROR` with status
`422` instead of a
success-shaped simulation result.
Unknown run-option fields are rejected before the board is loaded.
Async task creation happens only after validation passes; queue saturation is returned
as `SERVICE_UNAVAILABLE` (`503`). A successful response includes the authoritative
accepted task's `taskId`, `taskStatus`, `progress`, `requestedSteps`, effective derived
`isAttack` / `attackBudget`, the submitted `attackScenario`, `enablePrivacy`,
`modelSnapshot`, and `modelSemantics`. It
says the task was accepted, not that simulation completed.
Polling returns a compact task projection rather than the REST DTO. Its top-level and nested
progress fields use one authoritative live value. A saved completed task exposes its
`simulationId` and exact next tool, while technical execution logs remain on the REST/debug
surface.

`simulate_model` returns `modelComplete`, `disabledRuleCount`, and item-level
`generationIssues`; success means a model
trace was produced, not that it predicts the physical home. Empty states, timeout,
interruption, and execution failure are structured errors. Its transient result exposes
`stateCount` plus `statePreviewKind=INITIAL_AND_FINAL`, `previewedStateCount`, and a
projected `statePreview`; it does not duplicate the complete sequence or successful-run
logs into the chat result. Use `simulate_model_async`, poll its task, and then page the
saved sequence through `get_simulation_trace` when intermediate states are needed. It also returns
`historyPersistence.status=NOT_REQUESTED` and explicitly says that this preview did not
create a run-history entry. Saved-trace list/detail tools
also return model completeness, `generationIssues`, and structured `isAttack` /
`attackBudget` / `enablePrivacy` / `modelSemantics` context. Chat trace states contain
device/rule labels rather than model ids, and the tool does not expose raw NuSMV output,
execution logs, or the internal request snapshot. Those remain available only through
the documented REST technical/debug surface; the REST DTO keeps `userId` and persisted
request context internal.
List responses include `availableCount` and `unavailableCount`. An unavailable saved run
contains only its correlation id, timestamp when known, `dataAvailable=false`, and
`unavailableReasonCode`; the tool never fabricates zero steps or default model context
from damaged persistence.

## Verification & Fix

| Tool | Summary |
| --- | --- |
| `verify_model` | Run synchronous NuSMV formal verification on the board and report whether every emitted specification was satisfied, plus generation warnings and property-violation details. |
| `verify_model_async` | Submit an asynchronous NuSMV verification task and return its current status, progress, effective attack/privacy context, frozen model snapshot, model semantics, and taskId for polling. Acceptance is not completion. |
| `verify_task_status` | Query status and progress by taskId without raw NuSMV stdout or execution logs. A completed task exposes `runId` and `nextTool=get_verification_run`. |
| `cancel_verify_task` | Cancel an async verification task by taskId. |
| `dismiss_verify_task` | Preview a failed or cancelled verification task and its retained diagnostics, then remove it only after explicit confirmation with the returned `impactToken` in a later user turn. It refuses active tasks (cancel first) and completed tasks (use `delete_verification_run`). |
| `list_async_tasks` | Discover non-completed verification, simulation, and counterexample-search task ids across conversations, with kind/status/initiator filters; completed results stay in run history. |
| `list_verification_runs` | List all completed formal conclusions, including satisfied runs with no counterexample. |
| `get_verification_run` | Read one completed run's per-spec results and completeness without exposing raw NuSMV output. |
| `list_traces` | List all saved verification counterexample traces (each a state sequence leading to a violation). Each row now also carries its `runId` for run-level history operations. |
| `get_trace` | Get a saved verification trace by traceId with a bounded, pageable state window. States carry `loopStart`/`loopBack` where the trace ends in a cycle, which is the violation itself for a liveness specification. |
| `delete_trace` | Preview a saved verification trace and receive an opaque `impactToken`, then delete it only after explicit confirmation with that token in a later user turn. |
| `delete_verification_run` | Preview a completed verification run and the exact number of stored trace rows with an opaque `impactToken`, then cascade-delete the run and every trace row it produced only after explicit confirmation with that token in a later user turn. The count includes unavailable or damaged evidence and is intentionally distinct from the run's replayable counterexample count. |
| `fix_violation` | Analyze a violation trace to localize fault rules and suggest fixes via parameter, condition, or permanent rule-removal strategies (needs a traceId). |
| `apply_fix` | Preview one exact signed suggestion returned by `fix_violation`, then apply it only after explicit confirmation in a later user turn. The confirmed call reuses the server-stored proposal and invokes the same signed apply service as REST. |

`verify_model` and `verify_model_async` accept `attackMode=none|exact|exhaustive`.
Exact mode uses the same explicit `attackPoints` objects as simulation. Exhaustive mode
uses `attackBudget` in `1..50` and checks every modeled selection up to that bound.
Persistent trust labels are not attack-point selections.

Both tools capture devices, the Environment Pool, rules,
specifications, and device-template definitions in one per-user locked transaction. The
request and exact referenced manifests therefore describe one persisted Board state,
not a mixture of separately timed reads. Those manifests are passed unchanged through
service validation and, for async runs, task acceptance. The tools otherwise use the
same NuSMV runtime validation as the REST verification services. `verify_model_async` creates
a task only after validation passes. Queue saturation is reported as a structured
`SERVICE_UNAVAILABLE` error after the still-pending task row is removed; if that cleanup
cannot be confirmed, `TASK_DISPATCH_OUTCOME_UNKNOWN` preserves the task id and directs the
assistant to refresh run history and poll before retrying. A successful response returns
the authoritative accepted task's `taskId`, `taskStatus`, `progress`, effective derived `isAttack` / `attackBudget`,
the submitted `attackScenario`, `enablePrivacy`, `modelSnapshot`, and `modelSemantics`;
it does not claim that verification completed. Tool-argument mode/shape/range errors
return `VALIDATION_ERROR` with status `400` before the board is loaded. Service-layer
semantic validation errors, including an exhaustive budget larger than the effective
surface or an exact point outside it, are returned as `BUSINESS_ERROR` with status `422`.
Unknown run-option fields are rejected before the board is loaded.
Polling returns a compact task projection rather than the REST DTO. Its top-level and nested
progress fields use one authoritative live value. Completed tasks expose their `runId` and
exact next tool; raw NuSMV stdout and technical execution logs remain on the REST/debug surface.

`verify_model` returns `outcome`, `modelComplete`, `requestedSpecCount`,
`emittedSpecCount`, run context (`isAttack`, `attackBudget`, `attackScenario`, `enablePrivacy`,
`modelSemantics`), `historyPersistence`, and structured
chat-facing `specResults` entries shaped as `{ specificationLabel, formulaPreview,
formulaKind, outcome, checkedExpression }`, plus item-level
`generationIssues` and `checkLogCount`. Technical check-log contents are omitted. `emittedSpecCount`
matches the length of `specResults`; requested specs skipped before NuSMV emission are
reported through `skippedSpecCount` and `generationIssues`. `violationCount` counts
only explicit `VIOLATED` spec results; `traceCount` counts saved counterexample traces,
which can be lower when a violated spec has no parsed counterexample. `INCONCLUSIVE` is distinct from
`VIOLATED`; a parser/count/no-emission failure must never be described as a proven
property violation. `formulaPreview` and `specificationLabel` explain the immutable
submitted property in user concepts; `checkedExpression` is technical evidence and
should be shown only on request. The chat projection deliberately omits persistence
`specId` and template ids because follow-up trace/fix operations use `traceId` instead.
The REST verification DTO retains stable correlation ids for clients. Saved verification trace tools return structured `violatedSpec`
plus source-model completeness; raw `violatedSpecJson` and ownership ids remain internal.
That structure carries `templateId` — unlike the `specResults` projection above, which omits it because
its follow-ups key on `traceId`. A trace tool needs it: `get_trace` tells the model that the cycle *is*
the violation for templates 2, 5 and 6, and `specificationLabel` is display wording rather than a
contract, so gating that judgement on the label would break the first time the wording changed.
The tool's message distinguishes a saved history row from `FAILED` or
`OUTCOME_UNKNOWN`; an unknown history outcome instructs the assistant to refresh history
before retrying and does not weaken or erase the formal conclusion.
The post-refresh `FORMAL_VERIFICATION_RUN` receipt is emitted only when
`historyPersistence.status=SAVED` includes a positive `runId`. A completed conclusion whose
history write failed or remains unknown is still reported to the model, but the UI does not
claim that Run History was synchronized.
`list_traces` reads the completed-run summary hierarchy rather than downloading every
full state sequence. It returns `availableCount`/`unavailableCount` and preserves an
unavailable trace as an id plus reason code, without presenting it as a zero-state valid
counterexample.

The chat-facing `list_traces`, `get_trace`, and `delete_trace` projections use `traceId`
as the only operational handle. They present the violated property through its label,
formula preview/kind, structured user-facing conditions, and checked expression; trace
states use device/rule labels. Persistence `violatedSpecId`, device ids, rule ids, and the
raw `TraceDto` are omitted from ordinary assistant output. `fix_violation` likewise omits
the trace's `violatedSpecId` and always states that analysis itself applied no Board
change. Its suggestions are the signed payloads `apply_fix` must echo verbatim, so they keep
`preexistingViolations[].specId`; the system prompt's id rule keeps it out of the reply. REST
trace DTOs retain ids in their documented technical contract.

`fix_violation` is advisory only: it returns automations observed in counterexample
transitions and forward-verified suggestions, but it does not prove that every listed
automation independently caused the violation and does not apply anything to the board.
A strategy may return several alternative suggestions
([listing rule](../architecture/auto-fix.md#listing-alternatives-fixalternatives)); the system
prompt has the assistant present all of them for the user to choose, disclose an
incomplete listing (`alternativesComplete=false`), and say that a suggestion's
`preexistingViolations` stay violated after applying it. It also has the assistant treat the two
[proof statuses](verification.md) as settled for the same strategy and ranges, rather than call
`fix_violation` again to see whether the answer changes.
The separate `apply_fix` tool closes that conversational workflow without weakening the
REST contract. Its first call uses `confirmed=false`, an exact returned suggestion, and
the exact `preferredRangeSelections` used to generate it. The server verifies the
suggestion signature before returning a no-write preview, then stores that exact request
under the authenticated chat session for at most 15 minutes. A later explicitly confirmed
call supplies `traceId`, `confirmed=true`, and the preview `impactToken`; `suggestion` and
`preferredRangeSelections` may be resent unchanged and are ignored, because the tool
consumes the stored request once and calls the same `FixService.applyFix` boundary as
`POST /api/verify/traces/{id}/fix/apply`. Omitting `confirmed` degrades to a preview rather than a
validation error, matching every other confirmation-gated tool. Signature expiry, rule/template/specification/
device/environment drift, formal-operation admission, and the transaction commit fence
therefore still fail closed exactly as described in
[auto-fix.md](../architecture/auto-fix.md). A confirmation mismatch, replay, or expiry
does not call the mutation service. If the no-write preview cannot be serialized for the
conversation, its pending confirmation is cleared so an unseen proposal cannot later be
confirmed. After the mutation service is invoked, an unclassified admission/settlement failure
is reported as `RESULT_UNAVAILABLE` with `mutationMayHaveCommitted=true`; the assistant refreshes
rules before any retry. Only the specialized preflight-unavailable error is proven to precede the
write. The tool accepts only formal verification `traceId` evidence and has no fuzz-finding
argument or route. A preview that exceeds `CHAT_MAX_TOOL_RESULT_BYTES` is likewise cleared and
reported as a no-mutation `TOOL_RESULT_TOO_LARGE` result instead of leaving a hidden confirmation.
For parameter-fix steering, the tool accepts `preferredRangeSelections[]` items with
`{ targetId, lower, upper }`, where `targetId` is copied from a returned
trace-scoped `parameterTargets[].targetId` from the same fix context. Eligible numeric
targets are returned even when the first parameter search finds no verified suggestion,
so a later tool call can narrow the range without inventing an internal locator. The older internal
rule/condition locator map and zero-based selectors are not part of the tool schema;
unsupported fields are rejected instead of being accepted or ignored.
The response also includes `strategyAttempts`, `parameterTargets`, technical `warnings`,
`sourceModelComplete`, `sourceDisabledRuleCount`, `sourceSkippedSpecCount`, itemized
`sourceGenerationIssues`, and the stable `templateSnapshotComparison` status. Every
requested strategy gets an explicit attempted/skipped status. A trace from an incomplete generated model, or one without explicit completeness
metadata, yields no certified suggestion and all strategies are marked
`SKIPPED_INCOMPLETE_SOURCE_MODEL`.
Omitting `strategies` selects the documented default order. Supplying it has exact
semantics: it must be a non-empty, duplicate-free array containing only `parameter`,
`condition`, and/or `remove`. A wrong scalar, empty array, blank/unknown item, or duplicate
returns `VALIDATION_ERROR`; it never falls back to the default (which could otherwise
attempt a removal strategy the caller did not actually select).
The strategy key is `remove`, not `disable`: this candidate permanently removes the
listed rules when the user later applies it through REST/UI or the confirmed `apply_fix`
tool. `fix_violation` itself remains read-only.
`TIMED_OUT` means a strategy started but did not complete; `SKIPPED_TIMEOUT` means it
never ran because the shared fix deadline had already expired.

## Counterexample Search (Bounded Fuzz)

| Tool | Summary |
| --- | --- |
| `fuzz_model_async` | Submit an async bounded counterexample search over the current board snapshot (reproducible `BOARD_SNAPSHOT` strategy only), with optional `targetSpecIds`, `maxIterations` (1–5000, default `200`), `pathLength` (1–50, default `20`), `populationSize` (1–50, default `10`), and `seed`. The admission guard multiplies those three by the board's model complexity, so a larger budget can be refused; the rejection names the multiplier and the largest admissible iteration count. Returns the authoritative accepted task status, frozen model snapshot, and taskId; acceptance is not completion. |
| `fuzz_task_status` | Query an async counterexample-search task status and progress by taskId. A completed task exposes its `runId`. |
| `cancel_fuzz_task` | Cancel an async counterexample-search task by taskId. |
| `dismiss_fuzz_task` | Preview a failed or cancelled counterexample-search task and its retained diagnostics, then remove it only after explicit confirmation with the returned `impactToken` in a later user turn. It refuses active tasks (cancel first) and completed tasks (use `delete_fuzz_run`). |
| `list_fuzz_runs` | List completed counterexample-search runs (history), newest first, with outcome, effective seed, and finding counts. Paginated via `page`/`size`. |
| `get_fuzz_run` | Get one completed run: outcome, parameters, eligibility, limitations, and finding summaries. |
| `get_fuzz_finding` | Get one finding, including its violated specification, first violation step, and the shared bounded state/input-event window documented above. |
| `delete_fuzz_run` | Preview a completed run and the exact number of stored finding rows with an opaque `impactToken`, then cascade-delete the run and every finding row it produced only after explicit confirmation with that token in a later user turn. The preview uses lightweight user-scoped metadata, so damaged finding payloads remain cleanable. |

The paper-compatible random-state strategy is intentionally not exposed to the assistant:
it requires a preview-issued input-range fingerprint whose multi-step handshake does not
fit a single tool call, so it stays UI-only. `fuzz_model_async` therefore always submits
the reproducible `BOARD_SNAPSHOT` strategy. Tool-argument shape/range errors return
`VALIDATION_ERROR` with status `400` before the board is loaded. Process-capacity rejection
before task creation is a structured `SERVICE_UNAVAILABLE` and creates no row. A dispatcher
or status-recheck failure after persistence keeps its ordinary error only after confirmed
deletion of the still-pending row. If that cleanup cannot be confirmed,
`TASK_DISPATCH_OUTCOME_UNKNOWN` preserves the task id, refreshes run history, and requires
`fuzz_task_status` reconciliation before retrying.
`dismiss_fuzz_task` previews an already-terminal,
resultless task and its diagnostics, then removes them only after a later explicit user
confirmation with the preview's unchanged `impactToken`. The service refuses
`PENDING`/`RUNNING` tasks (cancel first) and `COMPLETED` tasks (remove their run with
`delete_fuzz_run`) with `BUSINESS_ERROR`.
`get_fuzz_finding` never returns an unbounded trace. Its state page uses user-semantic
device/rule labels rather than persistence/model ids, and `inputEvents` contains only
events whose steps fall within the returned state window. `stateCount` and
`inputEventCount` remain totals so the assistant can distinguish an empty page from an
empty finding.

Counterexample-search **findings are heuristic candidate evidence, not formal
counterexample traces**, and a bounded search never proves a specification holds — budget
exhaustion (`BUDGET_EXHAUSTED`) is not satisfaction. Findings are kept strictly separate
from formal traces: they carry no signed fix suggestion and there is no route from a fuzz
finding into `fix_violation` or `apply_fix`, both of which accept only formal-verification
`traceId` evidence. See [fuzzing-flow.md](../architecture/fuzzing-flow.md).
