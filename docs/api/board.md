# Board Storage & Recommendation API

Field-level contract for `/api/board` — the persisted canvas (nodes, rules, specs,
layout), device templates, and AI-backed recommendation endpoints.

`GET /api/board/snapshot` returns `BoardSemanticSnapshotDto` with `nodes`,
`environmentVariables`, `rules`, `specifications`, and `deviceTemplates` captured under one
user-row lock and transaction. Standalone captures share read ownership, so concurrent captures
can overlap while Board writes still require exclusive ownership. Calls joining an existing
transaction retain the exclusive capture path because their caller may write afterwards.
The frontend uses it for initial Board hydration so an
unloaded collection is never presented as an authoritative zero count. Layout remains separate
because it does not affect formal-model semantics.
`deviceTemplates` is an unordered account-library inventory; compare entries by ID and content,
not by their array positions. This does not change the order semantics of scene devices or rules.
All scalar and JSON columns are fetched before releasing the capture lock. DTO/JSON decoding runs
after that capture; standalone reads have already released their transaction and connection, while
calls inside an enclosing transaction retain its ownership. Later edits cannot change the captured rows.
The `Result<T>` envelope, auth, and error codes are defined in
[overview.md](overview.md).

All endpoints are authenticated and scoped to the current user (`@CurrentUser`).
Each account has one development board and one reusable template library: multiple
tabs of that account view the same board. Moving a portable scene between accounts
resolves dependencies in the target account without modifying the source account.
Verified against code on 2026-10-26. Source:
`service/impl/BoardStorageServiceImpl.java`, `service/recommendation/`,
`controller/BoardStorageController.java`, `dto/device/`, `dto/board/`, `dto/rule/`,
`dto/spec/`, `dto/recommendation/`, `component/board/PortableSceneDiagnostics.java`,
`component/template/DeviceTemplateSchemaValidator.java`.

**Mutation semantics matter:** ordinary device, rule, and specification operations are
targeted commands. A client sends only the item it intends to create/update/delete; an
omitted item is not deleted. Each mutation is serialized under the user's board write
lock and returns both the affected item(s) and the authoritative post-mutation snapshot,
so a stale client can replace its local collection without issuing a hidden full-list save.
Device and specification collections retain existing primary keys during reconciliation;
only removed records are deleted. Environment Pool reconciliation writes only changed or
missing variables, so reading an already materialized, unchanged pool writes no rows.

Two endpoints replace the whole scene, and both require an authoritative
`GET /api/board/replacement-preview` plus explicit user confirmation first.
`POST /api/board/scene` takes a portable scene file verbatim and is what scene import uses;
the server owns the portable → internal mapping, so no client restates how portable rules and
specifications become write DTOs. `POST /api/board/batch` takes the internal collections
directly and is what scene clear uses, having no portable file behind it. All four semantic
collections and the
exact template-snapshot dependency set are required complete desired collections; omitted
or `null` collections are rejected rather than interpreted as hidden partial preservation.
The submitted Environment Pool is checked against the incoming scene's device domains and
replaces the previous values and labels. Saving the incoming nodes does not first project
the old pool into those domains: a shared name may have different allowed values or bounds
in the two scenes. Ordinary targeted device edits still reconcile the existing pool.
The replacement commits or rolls back as one transaction. Its preview also reports the
current `editHistoryEntryCount`; the opaque token binds both the complete scene and the
exact undo/redo journal. A successful replacement discards that journal because its
recorded inverses no longer describe the replacement scene. If only the journal changes
after confirmation, the request still returns `409` and writes nothing.
The same transaction advances the user's internal scene revision. This distinguishes
replacement/clear from ordinary edits for [task-memory scope](chat-sse.md#task-memory-and-original-requirements);
the revision is not a semantic model fingerprint or a client-authored field.

Rejected portable imports report authored file coordinates in `data.errors`, including collection
indexes: for example `rules[1].toId`, `rules[0].sources[1].fromApi`, and
`templates[0].manifest.WorkingStates[0].Trust`. Clients preserve the file's collection order when
sending an import; sorting for canonical exports or semantic comparison must not change those
coordinates. Missing strings/collections and unknown or wrongly
typed fields retain `400`; semantic validation retains `422`. Errors produced after conversion are
mapped back from internal `nodes`/`conditions`/`command` fields to portable
`devices`/`sources`/`toId`/`toApi`. A condition-wide constraint names the source object rather than an
internal validator method. `POST /api/board/batch` still reports its own internal request fields.
The chat `apply_scenario` tool uses the same portable coordinates in its `errors` result.

Content conflicts retain `409` and include `data.reasonCode` plus `data.errors`:
`DUPLICATE_RULE` identifies the duplicate rule and advises changing its trigger/action or removing
it; `DUPLICATE_SPECIFICATION` identifies a repeated specification identity or semantics;
`SCENE_TEMPLATE_MISMATCH` identifies the mismatched manifest and also returns `data.templateName`.
Clients select the conflict presentation from this code, keeping unrecognised conflicts generic and
preserving their details. Template schema rejections report up to five detailed manifest fields;
the message states when further schema errors remain. A template request body with an invalid
field type likewise reports the authored field in `data.errors`. These diagnosed rejections do not replace
the scene; transport/server failures still require reconciliation before retrying.

---

## Canvas storage

The five targeted device commands require exactly one `nodeId` query value. Missing or
repeated values return `400` before the command runs. Pass the raw id to the query
serializer; the server uses the decoded value exactly, including literal commas, valid
URL delimiters, and internal whitespace. Device identity validation is defined in
[Device identity authority](../architecture/device-identity.md#rules).

| Method | Path | Body / Response | Notes |
| :--- | :--- | :--- | :--- |
| GET | `/api/board/model-fingerprints` | → `BoardModelFingerprintsDto` | Canonical current-model identities for verification/exploration and simulation, captured under one Board lock |
| GET | `/api/board/nodes` | → `DeviceNodeDto[]` | List device instances |
| POST | `/api/board/nodes` | `DeviceBatchCreateRequestDto` → `DeviceMutationResultDto` | Atomically append one or more device instances; every `templateName` must exist. Optional environment patches commit with the devices. Reversible as one user action regardless of batch size; the result carries `canUndo`/`canRedo` |
| PUT | `/api/board/nodes/layout?nodeId={nodeId}` | `DeviceLayoutDto` → `DeviceUpdateResultDto` | Replace only the complete canvas layout subresource (`position`, `width`, `height`); device identity and modeled runtime are preserved. A real change is one reversible edit and returns `canUndo`/`canRedo`; an unchanged command writes no history and omits both fields |
| PUT | `/api/board/nodes/runtime?nodeId={nodeId}` | `DeviceRuntimeUpdateDto` → `DeviceUpdateResultDto` | Replace only the complete device-local modeled runtime subresource; identity, type, display name, and canvas layout are preserved. Same changed/unchanged undo contract as layout |
| PATCH | `/api/board/nodes/label?nodeId={nodeId}` | `{ label, expectedLabel }` → `DeviceMutationResultDto` | Rename one instance and update specification display labels atomically as one reversible edit; stable references remain `nodeId`. `expectedLabel` must equal the label originally edited or stale submission returns `409` without writing. The new label must differ from the current label |
| GET | `/api/board/nodes/deletion-preview?nodeId={nodeId}` | → `DeviceDeletionResultDto` | Read-only authoritative preview of the device, every referencing rule/spec, and each Environment Pool item that would be removed |
| POST | `/api/board/nodes/delete?nodeId={nodeId}` | `DeviceDeleteRequestDto` → `DeviceDeletionResultDto` | Delete one instance plus every previewed consequence atomically. The opaque `impactToken` from the latest preview must still match the complete server-calculated impact or the server returns `409` before writing. Reversible as one compound edit containing the device, cascaded records with their positions, and exact Environment Pool state; the result carries `canUndo`/`canRedo` |
| GET | `/api/board/environment` | → `BoardEnvironmentVariableDto[]` | Board-level environment pool; self-heals from current nodes/templates |
| POST | `/api/board/environment` | `EnvironmentVariableUpdateRequestDto[]` → `EnvironmentMutationResultDto` | Atomically apply at most 200 name-keyed compare-and-set field patches; every item carries a complete `expected` baseline and a non-empty `desired` patch. All changed items form one reversible Environment Pool edit; a semantic no-op writes no history |
| GET | `/api/board/rules` | → `RuleDto[]` | List rules in effective execution order |
| POST | `/api/board/rules` | `RuleDto` → `CollectionMutationResultDto<RuleDto>` | Create exactly one validated rule; request `id` is ignored and the server assigns identity. Reversible: the result carries `canUndo`/`canRedo`, and the journal entry records the execution position so a redo re-creates the rule where it was |
| PUT | `/api/board/rules/order` | `{ expectedRuleIds: Long[], ruleIds: Long[] }` → `CollectionMutationResultDto<RuleDto>` | Compare-and-set replacement of execution order only. Both lists accept at most 100 positive ids and must contain every current rule id exactly once. `expectedRuleIds` must match the current order element-for-element; stale, duplicate, partial, or unchanged input is rejected without writing (`400` for unchanged order). Reversible: one button press is one edit, recorded as a `RULE_ORDER` journal entry holding the previous ordering, so the result carries `canUndo`/`canRedo` |
| PUT | `/api/board/rules/{ruleId}` | `RuleUpdateRequestDto` → `CollectionMutationResultDto<RuleDto>` | Edit one rule with complete `expected` and `desired` snapshots; see [targeted rule/specification edits](#targeted-rulespecification-edits) |
| DELETE | `/api/board/rules/{ruleId}` | `RuleDto` (the confirmed current snapshot) → `CollectionMutationResultDto<RuleDto>` | Delete only when the id and authored rule semantics still match; a stale snapshot returns `409` without writing, and a missing rule returns `404`. Reversible: the result carries `canUndo`/`canRedo`. Undo restores the original id at the recorded index in the ordered rule list (`entity_order`), even when persisted execution numbers have gaps. Surviving rules are renumbered contiguously. Missing or impossible recorded positions return `409` without writing |
| GET | `/api/board/specs` | → `SpecificationDto[]` | List specs in stable authored order |
| POST | `/api/board/specs` | `SpecificationDto` → `CollectionMutationResultDto<SpecificationDto>` | Create exactly one validated specification. An existing `id` returns `409` without overwriting the original or recording history. Reversible: the result carries `canUndo`/`canRedo`, and the journal entry records the list position so a redo re-creates it where it was |
| PUT | `/api/board/specs/{specId}` | `SpecificationUpdateRequestDto` → `CollectionMutationResultDto<SpecificationDto>` | Edit one requirement with complete `expected` and `desired` snapshots; see [targeted rule/specification edits](#targeted-rulespecification-edits) |
| DELETE | `/api/board/specs/{specId}` | `SpecificationDto` (the confirmed current snapshot) → `CollectionMutationResultDto<SpecificationDto>` | Delete only when the id and authored specification semantics still match; a stale snapshot returns `409` without writing, and a missing specification returns `404`. Reversible: the result carries `canUndo`/`canRedo`, and undo restores the specification at its recorded list position (same `entity_order` mechanism as rules). Missing or impossible recorded positions return `409` without writing |
| GET | `/api/board/edits/availability` | → `BoardUndoResultDto` (`applied: false`, `AVAILABILITY_ONLY`, all four semantic collections empty) | Current undo/redo availability from the per-user edit journal, with no side effects. Read `canUndo`/`canRedo` only — the empty collections are not an authoritative board state. Read on board load so the affordance survives reload, a second tab, and another device |
| POST | `/api/board/edits/undo` | → `BoardUndoResultDto` | Reverse the newest reversible edit: device create/update/rename/delete, Environment Pool update, rule/spec create/update/delete, rule reorder, or an assistant rule edit / automatic-fix rule-set update. Returns authoritative nodes, Environment Pool, rules, specs, and remaining availability. Device deletion restores the complete cascade under the original rule ids and collection positions. `applied: false` with `NOTHING_TO_APPLY` when there is no history — a normal, idempotent outcome. `409` when any affected state changed after the edit was recorded, leaving the board untouched. History is capped at the 50 most recent edits per account; confirmed scene replacement/clear is a history boundary. Ordinal allocation is fenced by a unique `(user_id, sequence)` constraint, so a concurrent write fails rather than producing two entries that share a position |
| POST | `/api/board/edits/redo` | → `BoardUndoResultDto` | Re-apply the oldest undone edit. Same conflict and idempotency rules. A new edit discards the abandoned redo branch |
| GET | `/api/board/edits/clear-preview` | → `BoardEditHistoryClearPreviewDto` | Return the current entry count, availability, and opaque impact token that must be shown/bound to an explicit history-clear confirmation |
| POST | `/api/board/edits/clear` | `BoardEditHistoryClearRequestDto` → `BoardUndoResultDto` (`applied: false`, `HISTORY_CLEARED`, empty collections, both availability flags false) | Explicitly discard unusable undo/redo history without changing any Board collection. The SHA-256 `impactToken` from the preview is required; if any entry is added, undone, redone, changed, or removed before submit, stale confirmation returns `409` and preserves all history |
| GET | `/api/board/replacement-preview` | → `BoardReplacementPreviewDto` | Return the opaque impact token plus authoritative current scene counts and `editHistoryEntryCount` that must be shown before a full scene replacement/clear |
| POST | `/api/board/scene` | `{ impactToken, scene }` → `BoardBatchDto` | **Portable scene import.** `scene` is an exported file verbatim; its `schema`/`version` must match this build. The server maps portable rules/specifications onto the write contract, so clients send no internal shapes. Requires the still-current preview `impactToken`, kept outside `scene` because it is never portable semantics |
| POST | `/api/board/batch` | `BoardBatchDto` → `BoardBatchDto` | **Explicit atomic full-scene replacement** of complete `nodes` + `environmentVariables` + `rules` + `specs` plus exact `templateSnapshots`; requires the still-current preview `impactToken`. Used directly for scene clear; portable files go through `/api/board/scene` |
| GET | `/api/board/layout` | → `BoardLayoutDto` | Panel/canvas layout |
| POST | `/api/board/layout` | `BoardLayoutDto` → `BoardLayoutDto` | |

### Targeted rule/specification edits

`RuleUpdateRequestDto` and `SpecificationUpdateRequestDto` each contain required
`expected` and `desired` objects of the corresponding item DTO type. Send the record
read when editing began as `expected`, and the complete edited record as `desired`.
Both ids must equal the path id; omission or retargeting returns `400`. A missing or
other-account record returns `404`. The current authored semantics must still match
`expected`; a stale snapshot returns `409` without changing the record or edit history,
with `data.reasonCode: RULE_EDIT_STALE` or `SPEC_EDIT_STALE` and an `errors.expected`
message. Keep the user's draft and reload the current record before opening a new edit.
Rule comparison includes its display string; specification comparison includes the
authored `name`. Derived specification display caches do not participate in this comparison. Condition ordering is immaterial, while
condition multiplicity remains part of the snapshot.

The update preserves the record's id, owner, and current collection position, including
rule execution priority and server-managed `createdAt`. Only the requested record is
written. The desired record passes the same reference, domain, size, and duplicate checks
as creation, and specification previews and device labels are rebuilt by the server.
Every edited variable condition must choose `variableSource`; an older unresolved
condition is allowed in `expected`, so editing can supply the missing choice.

The result uses `operation: "updated"`, the canonical saved `affectedItem`, the complete
authoritative `currentItems`/`currentCount`, and `canUndo`/`canRedo`. One edit records one
`UPDATE` journal entry in the same transaction. Undo/redo checks both record content and
its recorded position before applying the inverse; it never moves an independently
reordered item. An unchanged request returns `operation: "unchanged"` with current
availability, writes no history, and preserves any redo branch. Neither outcome is a
verification result: rerun the relevant check after changing rule or requirement logic.
A name-only change preserves the model fingerprint and does not stale a verified result.
The required name contract is defined by [SpecificationDto](verification.md#specificationdto).

### Current model fingerprints

`BoardModelFingerprintsDto` contains two required 64-character lowercase SHA-256 strings:

| Field | Meaning |
| :--- | :--- |
| `verification` | Current modeled devices/runtime, environment, ordered rules, structured specifications, and referenced template manifests; also used for exploration history |
| `simulation` | The same canonical input with an empty specification collection, because simulation does not consume specifications |

`ModelFingerprint` owns canonical serialization for both task capture and current-Board reads.
It sorts object/map keys and environment entries; device and rule order remain meaningful
because simultaneous writes use ordered priority. Canvas geometry, panel layout, display names,
descriptions, icons, formula previews, rule display strings, and creation timestamps do not enter
the identity. Specification condition ids/sides and device-display caches are also excluded:
the fingerprint uses the same structured logical signature as specification equality.
Referenced template content does; unused template-library entries do not.
Presentation-field exclusions apply to manifest properties, never to template-name map keys:
a template named `icon` or `description` retains its identity and semantic content in the hash.
Run configuration such as attack/privacy settings, requested simulation steps, and search budgets
is recorded separately and is not a claim about the current Board's content.

Compare a run's frozen `modelSnapshot.modelFingerprint` with the appropriate current value,
including after reload and undo. Matching item counts never establish a model match. Missing
historical comparison evidence and a failed current read are different unknown states; neither
may be presented as matching or changed. The endpoint reads one captured model for both values,
so a concurrent edit cannot mix two Board revisions in its response.
Both hashes reuse one canonical model tree, replacing only its specification collection for the
simulation identity. This does not mutate the captured snapshot or change canonical hash values.

The persisted Board has hard totals of 100 devices, 100 rules, 100 specifications, and
200 derived Environment Pool variables. Targeted creation locks the user row and checks
the authoritative current total plus the proposed addition in the same transaction; it
does not treat the per-request device limit as a reusable way around the Board total.
A device addition that would derive more than 200 Environment Pool variables also rolls
back. The Board UI blocks known over-capacity additions across dialogs, template drag/drop,
recommendation application, and scene import before sending them. It also enforces the
per-device override and per-rule/per-specification-group condition limits while authoring;
the backend remains authoritative for stale or concurrent clients. A rule accepts at most
50 conditions (`conditions`) and a 4,000-character display preview (`ruleString`); each
specification condition group — `aConditions`, `ifConditions`, `thenConditions` — accepts at
most 50 conditions (reported under `<group>.size`). The service checks these itself on the
record being written, not only through HTTP DTO validation, because the AI tools call it
directly with no `@Valid` step. They are deliberately checked on the record being authored
rather than during whole-board revalidation, so an account already holding an over-limit row
can still add devices, move them, and undo. A server-composed `ruleString` — one the AI rule
tool or an automatic fix generated rather than the caller supplying it — is truncated to the
limit instead of rejected, because long device labels can push a legal rule past it. Legacy data
already above a limit is never truncated automatically: deletion remains available so the
user can reduce it to the supported range before creating more items, reordering rules,
running a complete model, or round-tripping a scene.

Initial layout hydration never overwrites zoom, pan, or panel changes made while the layout
request was in flight. If an early interaction occurred on a wide viewport, the client
persists the resulting current layout after hydration, then clears the one-shot protection
flags so later narrow/wide viewport restoration still works.
Restoring the saved layout alone does not write it back. If the layout read fails, the client
keeps its state unknown, pauses automatic layout saves, and shows a persistent retry action;
it never treats the failure as a missing layout or replaces the saved layout with defaults.
Retry uses the same early-interaction protection as initial hydration.

### `DeviceNodeDto`

| Field | Type | Rules |
| :--- | :--- | :--- |
| `id` | `String` | Required |
| `templateName` | `String` | Required; must match an existing template name for the current user |
| `label` | `String` | Required |
| `position` | `{ x: Double, y: Double }` | Required |
| `state` | `String` | Required only when the selected template has a state machine (`Modes` plus non-empty `WorkingStates`); stateless/no-mode devices may omit it and are stored with the UI placeholder `Working` |
| `width` / `height` | `Integer` | Required; width is `80..2000`, height is `60..2000`; UI and AI creation paths default to `176` / `128` when the creator omits explicit dimensions |
| `currentStateTrust` | `String` | Optional only for templates with a state machine (`Modes` plus non-empty `WorkingStates`); `trusted` / `untrusted` |
| `currentStatePrivacy` | `String` | Optional only for templates with a state machine; `public` / `private`. This is the selected initial state's data-sensitivity label, not access control |
| `variables` | `VariableStateDto[]` | Optional; `{ name, value, trust? }` for device-local template variables (`InternalVariables[].IsInside=true`) only. `value` is the instance's initial value; omitted `trust` inherits the active template default and is omitted from responses rather than serialized as `null`, while an explicit value is an advanced instance override |
| `privacies` | `PrivacyStateDto[]` | Optional; `{ name, privacy }` for device-local variables or generated state privacy keys only |

> `DeviceNodeDto` is the canvas-CRUD shape (includes UI layout). The verification path
> uses the leaner `DeviceVerificationDto` — see
> [verification.md](verification.md).

Targeted device creation returns `initialStateSource`. Its values are `requested` for an
explicit runtime state, `templateDefault` for a state-machine `InitState`,
`statelessPlaceholder` for the non-semantic `Working` canvas placeholder on a no-mode
template. A template with a partial state-machine definition or no valid `InitState` is
rejected as corrupt persisted data; creation never invents a semantic runtime state.

`DeviceBatchCreateRequestDto` is `{ devices, environmentVariablePatches }`; `devices`
must contain 1–100 items, must keep the resulting Board at no more than 100 devices, and
`environmentVariablePatches` may contain at most 200.
`DeviceMutationResultDto` returns `operation`, `affectedDevices`,
`currentNodes`, `environmentVariables`, `environmentChanges`, `currentSpecifications`,
`previousLabel`, `updatedSpecificationCount`, `currentCount`, `canUndo`, and `canRedo`.
The availability fields are required for `operation=created` and `operation=renamed`.
Callers should use the
returned current collections rather than assuming their local optimistic state won.
`environmentChanges[]` is structured as `{ changeType: ADDED|UPDATED|REMOVED, name,
previousValue, currentValue, previousModelTokenSource, currentModelTokenSource }` and
explains automatic Environment Pool synchronization caused by the device operation,
including explicit create-time environment patches. Token sources are
`BUNDLED|CUSTOM|UNKNOWN`; only `BUNDLED` authorizes display-time localization of the
corresponding machine-facing name/value, and missing or `UNKNOWN` provenance stays raw.
The frontend treats these mutation snapshots as required authority, not optional legacy
metadata. A successful HTTP response with a missing collection, wrong `operation`,
missing affected item, or inconsistent `currentCount` is an unconfirmed outcome: the
Board refreshes the affected collections and never substitutes its local draft or an
empty array before displaying success.
Create-time `environmentVariablePatches` retain their existing field-level semantics:
each non-null field overrides that shared value, while an omitted or null field retains the
existing value (or the newly materialized template default when the row did not exist).
They are an internal part of device creation and are not the public environment-update
request shape. Device creation and these patches commit in one transaction.

`POST /api/board/environment` is the public compare-and-set command. Its canonical
request, merge, and result semantics are defined under
[`BoardEnvironmentVariableDto`](#boardenvironmentvariabledto). Its stale-write response
uses the specialized conflict contract owned by [API error and status codes](./overview.md#error-and-status-codes).

Device update ownership is deliberately split by user intent. There is no public
whole-`DeviceNodeDto` update endpoint: moving a node must not overwrite a runtime edit,
and saving runtime values must not move, rename, or retarget the device. `DeviceLayoutDto`
is the complete `{ position: { x, y }, width, height }` layout representation. Coordinates
must be finite and within `-1000000..1000000`; dimensions use the bounds in the table
above. The canvas rounds dimensions while resizing, so it never displays a shape the
integer storage contract cannot save.

`DeviceRenameRequestDto` is `{ label, expectedLabel }`. Both values are required strings
of at most 255 characters. The service compares `expectedLabel` with the current persisted
label while holding the Board write lock; a second dialog opened before another rename
therefore receives `409 Conflict` instead of silently overwriting the newer label. The same
locked check rejects a case-insensitive collision with any other current device as `409`,
including when another browser tab claimed the requested name after the dialog opened.
While the dialog is open, Board snapshots rebind it to the current node without replacing
a dirty name or its original compare-and-set baseline. An untouched field adopts an
external rename, and an externally deleted device closes the dialog after any in-flight
request settles instead of leaving stale controls on screen.

`DeviceRuntimeUpdateDto` is a compare-and-set replacement command:
`{ expected: DeviceRuntimeConfigDto, desired: DeviceRuntimeConfigDto }`, where each nested
runtime value has the complete shape
`{ state, currentStateTrust, currentStatePrivacy, variables, privacies }`. The server reads
the current device after acquiring the per-user database write lock, canonicalizes its
runtime values, and compares them with `expected`. A mismatch returns the specialized
device-runtime `409 Conflict`; no field is written. Its structured response is owned by the
[API error mapping](overview.md#error-and-status-codes).

When `expected` still matches, `desired` replaces the complete device-local runtime
subresource. Omitting/nulling optional source/sensitivity overrides means use the selected
template state's labels; omitting/nulling `variables` or `privacies` means no explicit local
overrides. Values are trimmed, security labels are lower-cased, and local-variable/privacy
names are stored using the exact manifest declaration. Each collection accepts at most 100
non-null items. `state` accepts at most 50 characters, while `currentStateTrust` and
`currentStatePrivacy` accept at most 20 each; shape violations return `400`. Stateful
devices still require a legal `state`; no-mode devices use no modeled state and may be
stored with the canvas-only `Working` placeholder. Shared environment values are not part
of this subresource.

The same runtime canonicalization runs before every complete device write, including
targeted creation and full-scene replacement. A runtime compare-and-set also compares the
canonical current value but rewrites a non-canonical stored representation even when the
requested semantic value is unchanged; successful reads and writes therefore converge on
one representation instead of preserving whitespace or label-case drift.

The device-details form performs a field-level three-way merge between its last accepted
server baseline, the local draft, and each incoming Board snapshot. An untouched local field
adopts the incoming value; a locally edited field is preserved when the server-side field did
not change; and disjoint local/server edits merge automatically. If the same state context,
variable, or privacy entry changed differently on both sides, the dialog lists the conflict
and blocks Save until the user explicitly keeps the local value or uses the latest server value.
It also records a monotonic edit revision when Save begins, so edits made while the request is in
flight remain local even when the user deliberately returns a field to its pre-save value;
the matching response still advances the authoritative baseline. The state, current-state
trust, and current-state privacy fields form one state context: reconciliation preserves or
adopts that complete context rather than combining one state's trust/privacy overrides with
another state.
When the same node receives a different runtime schema, an untouched draft immediately adopts
the authoritative runtime. A dirty draft is rebased onto that runtime: a locally changed state
context is retained only when the complete context remains legal, while compatible local-variable
edits are retained independently. Removed or incompatible fields are discarded. Save remains
blocked behind an explicit schema-conflict decision. **Use latest** discards the draft, while
**Continue with compatible edits** accepts the new baseline and keeps only the retained
compatible changes. Closing the dialog remains authoritative; a late response updates the Board
snapshot but does not reopen the details surface.

Both update commands return `DeviceUpdateResultDto`: `{ operation: updated|unchanged,
mutationType: layout|runtime, changedFields, previousDevice, currentDevice, currentNodes,
currentCount, canUndo?, canRedo? }`. A real update records one `DEVICE/UPDATE` edit and must
return `canUndo: true`, `canRedo: false`; an unchanged command writes no history and must omit both
availability fields. `changedFields` is restricted to the selected subresource and is derived
from the before/after snapshots. The frontend rejects a response that changes a preserved
field, disagrees with the request, omits the updated device, or contradicts its own
`changedFields`/`currentCount`; it then reconciles from the server instead of announcing
success. All ordinary Board writes, device-type mutations, and their post-error refreshes
are applied through one client-side queue; assistant-triggered refreshes join the same
queue after the server tool completes. This prevents an older response snapshot from
rolling back a newer local action but does not make separate REST calls transactional.

The Board does not silently rename a device during final batch submission. Imported-list
name adjustments are shown item by item in the preview; if the current board would require
another adjustment by submit time, the complete batch is blocked before the request and
the proposed name changes are shown for review.

`DeviceDeleteRequestDto` is `{ impactToken }`, copied opaquely from the latest server
deletion preview. `DeviceDeletionResultDto` uses `operation=preview` for the read-only preview and
`operation=deleted` after commit; it returns the deleted device, exact `removedRules` /
`removedSpecifications`, itemized `environmentChanges`, and authoritative
`currentNodes` / `environmentVariables` / `currentRules` / `currentSpecifications`. A committed
deletion also returns required `canUndo` / `canRedo`; previews omit them because they do not edit
the journal.
The preview's opaque `impactToken` binds the complete server-calculated device, rule,
specification, and Environment Pool effect; it intentionally does not require clients to
submit internal rule/spec ids. If any actual impact set differs from the preview, the endpoint returns
`409 Conflict` and writes nothing; the UI refreshes all affected collections and opens a
fresh preview. Environment changes are calculated by the same backend merge/prune logic
used for the commit, not inferred from frontend template caches. A shared Environment
Pool value is removed only when no remaining canvas device template requires it; deleting
one of several devices that depend on the same value keeps the value and its current
trust/privacy labels unchanged.

The UI permits only one deletion-preview request at a time and immediately opens a named,
cancellable confirmation in an explicit loading state. An underlying device-details dialog
is suspended rather than destroyed, so cancelling the preview restores any unsaved runtime
draft without exposing two active modal dialogs. Confirmation is disabled until the complete
response passes nested device/rule/specification/environment validation and supplies a usable
token. The preview joins the client Board-mutation queue, then reconciles its authoritative
pre-delete collections before showing consequences.

While confirmation is open, the client watches only inputs that can change the deletion
impact: the target snapshot, referencing rules/specifications, affected Environment Pool
values and their active template providers. A relevant change closes the old confirmation;
an unrelated Board edit does not. Removing the target closes both details and confirmation.
If the preview itself returns `404`, the client reloads the complete semantic scene; an
already-absent target is reported as an externally completed outcome rather than as a second
contradictory failure. A final delete command is single-submit even under repeated click or
Enter input. When a foreground or cross-tab snapshot removes a device referenced by an open
details, rename, context-menu, preview, or confirmation surface, all stale surfaces close,
one localized external-deletion warning is shown, and keyboard focus moves to the visible
Devices tab instead of remaining on a detached element.

Rule/spec create/update/delete returns `CollectionMutationResultDto<T>`:
`{ operation, affectedItem, currentItems, currentCount, canUndo, canRedo }`. This is deliberately richer
than success/id/count so clients can explain exactly what changed and reconcile stale
local state. A REST delete must include the complete item snapshot that the user confirmed
(`RuleDto` or `SpecificationDto`); the backend compares the identity and authored semantic
fields while holding the board write lock. It rejects a changed snapshot with `409` and
performs no write, so a second tab cannot silently delete a newer edit. The returned
`affectedItem` is the exact deleted snapshot and `currentItems` is authoritative.
New rules receive a server-managed `createdAt` at the database's microsecond precision;
the creation response, edit journal, reload, and redo preserve the same complete rule metadata.
Every committed reversible mutation, including device create/update/rename/delete, Environment Pool
update, rule/spec create/update/delete, rule reorder, assistant rule edit, and automatic-fix apply, returns
`canUndo: true` and `canRedo: false`: the newly recorded entry is reversible and it discards any
abandoned redo branch. Clients reject missing or contradictory values instead of guessing.

Rule reorder shares this envelope with one difference: it reports `operation: "reordered"` and a
`null` `affectedItem`, because one up/down press changes no single record — the whole new ordering
is in `currentItems`. Clients must not require `affectedItem` on that path. The desired order must
differ from `expectedRuleIds`; an identical order is rejected with `400` before either the rules or
the edit journal is written.

Before applying any journal entry, the backend validates that its entity type, operation, business
key, before/after payload shape, and recorded position form one supported, non-no-op transition.
Rule/specification snapshot ids must match the journal key, and reorder snapshots must contain the
same unique positive ids in different orders. A malformed entry returns `409` without changing the
board or consuming the entry.
Specification ids must also be unique within a scene replacement; duplicate ids return
`409` before any collection or journal changes, regardless of whether their semantics differ.

Board node mutations validate more than shape. Device ids must be unique and must not
collide after NuSMV normalization (`AC 1` and `ac_1` are the same model id). For every
node, `templateName` must exist. Internal template variables are not canvas nodes; they
live in the template manifest and optional `variables` runtime overrides. Devices with a complete state machine (`Modes` plus `WorkingStates`) must provide
a legal state value for that template; no-mode devices do not send `state` or
`currentStateTrust` / `currentStatePrivacy` to NuSMV and may use/omit the canvas placeholder.
For mode devices, both overrides are optional. If omitted, the model uses the selected
`WorkingStates.Trust` / `WorkingStates.Privacy`; provided values must be
`trusted|untrusted` and `public|private`, respectively. These are source and sensitivity
labels used by propagation checks, not authentication, probability, encryption, or
access-control settings. Variable trust follows the same trust domain.
Variable runtime overrides must name declared device-local `InternalVariables` and stay
in their enum/range domains, and privacy overrides must name generated state/local-variable
privacy keys with values `public|private`. Template variables with `IsInside=false`
are not saved on a device instance; they are board-level environment variables
stored through `/api/board/environment`.

All ordinary creation paths materialize the same device-local starting values: the template initial
state, and for each local variable the value that state declares in its `Dynamics` — falling back to the
enum's first value or the bounded number's lower bound only when that state declares none. The state is
what fixes the variable, because a `Dynamics` value is a standing constraint on being in that state, not
an action. Defaulting the two independently produced a device whose own step 0 contradicted itself
(`InitState: away` beside `location = garage`), on six bundled templates;
`DeviceManifestModes.localInitialValue` is the single home for the derivation and
`DeviceTemplateSchemaValidatorTest` pins it for every bundled manifest.
Trust/privacy labels remain template-owned fallbacks unless the user or AI explicitly
supplies an advanced instance override. A device-list JSON field that is omitted or
`null` means "use the template default"; an explicit blank scalar or invalid value is
rejected. Standard scene JSON persists explicit overrides only, so export then import is
lossless without freezing template labels into every device.

### `BoardEnvironmentVariableDto`

| Field | Type | Rules |
| :--- | :--- | :--- |
| `name` | `String` | Required; must be read by at least one current device template (`InternalVariables[].IsInside=false`) or affected by at least one current device through `ImpactedVariables`; affected-only devices do not gain a device-scoped rule/spec reference |
| `value` | `String` | Required non-blank GET/response value from the canonical finite template domain. Create-time/internal patches accept a legal non-blank override or preserve the materialized template default when omitted/null. Complete scene replacement requires an explicit legal non-blank value |
| `trust` | `String` | GET/response label; create-time/internal patches accept `trusted` / `untrusted` or preserve it when omitted/null. Complete scene replacement requires the field explicitly |
| `privacy` | `String` | GET/response label; create-time/internal patches accept `public` / `private` or preserve it when omitted/null. Complete scene replacement requires the field explicitly |

The environment pool is persisted in `board_environment_variable` and scoped to the
current user's single development board. `trust` and `privacy` here are **scenario-wide**: this pool is
their only writer, so every device declaring the name is generated with the same labels, and supplying
one per device is rejected rather than merged — see
[shared value semantics](../architecture/shared-value-semantics.md#the-scope-of-a-security-label) for
the rule and the single attack-modelling exception. `GET /api/board/environment` is intentionally
self-healing: it reads current nodes/templates, inserts missing required variables with
the default value above, preserves existing values, and prunes variables no current
device can read or affect. An unchanged derived pool performs no persistence writes.
`POST /api/board/environment` accepts only names required by
the current board. The public request is an `EnvironmentVariableUpdateRequestDto[]`:
each item contains a complete `expected` `{ value, trust, privacy }` baseline and a
non-empty `desired` field patch. All three expected fields are required;
`expected.value` is the current non-blank value from the variable's finite template domain.
Omitted desired fields preserve their materialized current values. Explicit JSON `null`
for `desired.value`, `desired.trust`, or `desired.privacy` is rejected.
The server compares every baseline under the board
write lock, then validates names, domains, and labels before replacing the pool. A stale
or removed baseline performs no write and uses the
[specialized conflict response](./overview.md#error-and-status-codes). Explicit blanks,
duplicate submitted names, unknown names, and out-of-domain values are rejected before
persistence. Accepted scalar values are trimmed, and trust/privacy labels are stored in
their canonical lower-case form.

`EnvironmentMutationResultDto` contains `{ operation: updated|unchanged, patchResults,
environmentVariables, environmentChanges, currentCount, canUndo?, canRedo? }`. An `updated`
result records the complete materialized pool as one `ENVIRONMENT/UPDATE` edit and must return
`canUndo: true`, `canRedo: false`; an `unchanged` result writes no history and omits both fields.
Each `patchResults[]` row is
`{ name, suppliedFields, changedFields, preservedFields, previousValue, currentValue }`;
field names are `value`, `trust`, or `privacy`. It explains every submitted item,
including a valid no-change selection. `environmentVariables` is the authoritative full
pool, `environmentChanges` also reports any self-heal side effects, and `currentCount`
must match that pool. The frontend rejects an HTTP 200 response if an omitted field
changed despite being reported as preserved.
If the environment update response is lost or incomplete, the frontend reloads the
authoritative Environment Pool and tells the user to review those current values; it
does not claim that the requested patch was applied.
Ordinary device create/update/delete responses report every resulting pool addition,
update, or removal in `environmentChanges`; device deletion previews expose removals
before confirmation. These pool effects commit in the same transaction as the device
mutation and are never presented as a separate successful step.
`POST /api/board/batch` evaluates `environmentVariables` against the batch's resulting
`nodes`. Strict scene-replacement semantics always apply: all required variables and their
`trust`/`privacy` labels must be submitted explicitly.

All typed board mutation bodies reject unknown JSON fields. The standard scene batch
parser reports exact nested paths and validates template snapshots as raw schema objects,
so a misspelled field cannot be discarded and then reported as a successful import.

### `BoardBatchDto` scene-import fields

First call `GET /api/board/replacement-preview`. `BoardReplacementPreviewDto` is
`{ impactToken, deviceCount, environmentVariableCount, ruleCount, specificationCount, editHistoryEntryCount, templates }`.
The counts describe the authoritative server scene the user is about to replace, not a
possibly stale local canvas.
`templates` lists the account's retained template library as
`{ name, defaultTemplate, deviceCount }[]`, sorted by case-insensitive name. Each device
counts against exactly one template; a template with `deviceCount: 0` is available for
reuse but is not part of the current scene. The client derives distinct-template and
custom-template counts from this inventory. A missing incoming template becomes custom;
an existing matching template retains its stored provenance. Import and clear retain the
library; scene export includes only referenced templates.

The subsequent batch requires non-null complete `nodes`, `environmentVariables`, `rules`,
`specs`, and `templateSnapshots` arrays. `nodes`, `rules`, `specs`, and
`templateSnapshots` each accept at most 100 items; `environmentVariables` accepts at most
200. The service repeats these limits for internal/AI callers rather than relying only on
HTTP DTO validation, and does the same for the per-record rule and specification condition
limits described above — a full replacement authors every record it carries. There is no
partial-update form of this endpoint; ordinary targeted
endpoints own partial intent.

| Field | Direction | Meaning |
| :--- | :--- | :--- |
| `impactToken` | Request | Opaque token copied from the exact preview the user confirmed. It is request-only and never becomes portable scene semantics. |
| `templateSnapshots` | Request | Exact self-contained `DeviceTemplateDto[]` dependency set for submitted `nodes`: every snapshot must contain equal, case-sensitive `name` and `manifest.Name` values after trimming; every referenced template must have one matching snapshot; no unreferenced snapshot is accepted; an existing template must have the same manifest; and a missing template is created in the transaction. Import never renames a template implicitly. Submit `[]` when the replacement scene contains no devices, including Clear Scene. |
| `createdTemplates` | Response | `DeviceTemplateDto[]` created during this request. It is empty when every referenced template already existed. |

Template lookup ignores name case and surrounding whitespace. Snapshot `name` and
`manifest.Name` are trimmed before both creation and reuse comparisons; device
`templateName` references adopt the resolved template's exact spelling on import/export.
This normalization does not change device ids or rule/specification references.
Reuse still requires the complete manifest to match, including description, icon,
declaration order, and the case of `Name`. A same-name lookup with a different manifest
returns `SCENE_TEMPLATE_MISMATCH`; it never overwrites the library definition or silently
creates a renamed copy. Consequently repeated imports of the same exported scene reuse
dependencies without accumulating templates, while an independently changed same-name
definition must be resolved explicitly before importing.

The scene-import UI prepares a local proposal against the current template catalog before
confirmation. For a conflicting definition it proposes an available ` (imported)` name
(numbered when necessary), searches existing matching imported copies before allocating a
new name (including when lower-numbered copies have been deleted),
and updates only the snapshot name, manifest `Name`, and device `templateName` references.
The preview lists these changes; only explicit confirmation submits the adjusted scene.
Cancelling writes nothing. Library-capacity failures block submission with the required
additional count; the client never deletes another template to make room.

Full-scene replacement rejects an explicitly supplied local variable value that contradicts
the selected state's fixed declaration with `422` and the variable's field path, before
canonicalization can rewrite it. Variables assigned by transitions remain independent.
The import UI can propose the required value and display its old value, new value and
controlling state for confirmation. Ordinary targeted device edits retain their state/value
linkage; this full-scene check does not change that behavior.

Under the same user row lock used by board writes, the server recomputes the complete
current-board impact before resolving a template or changing a collection. If any device
layout/runtime/identity, Environment Pool value/label, rule/order, or specification/order
or template-library contents/provenance changed after preview, the token no longer matches and the server returns `409` before
writing. Its error `data` is `{ reasonCode: "BOARD_REPLACEMENT_STALE", currentPreview }`,
where `currentPreview` is a fresh `BoardReplacementPreviewDto`. The client must say that
nothing was replaced, refresh the Board, and require another explicit confirmation; it
must not retry automatically.

Template resolution, node/template capability validation, environment-pool validation,
and all board writes share the same user lock and transaction. A template mismatch,
missing snapshot, invalid template, invalid environment value, or invalid rule/spec
rolls back the whole request: the server does not leave a newly created template beside
a partially applied scene. An unreferenced snapshot is rejected rather than silently
ignored, and a referenced
template still requires its snapshot even when that template already exists in the
target account. This makes scene validity independent of target-account state. Missing,
duplicate, or unknown environment names and omitted environment `trust`/`privacy` labels
reject the whole scene; the server never fills or inherits a value behind a successful
scene-import result.

The batch request boundary is strict. Unknown fields anywhere in nodes, environment
values, rules, specifications, or their nested request objects are rejected before the
transaction instead of being discarded by DTO deserialization. Template manifests are
validated from the raw JSON against the canonical schema before conversion, so a typo or
new unsupported behavior field cannot produce a successful but lossy import. The
response-only `createdTemplates` field and template-catalog fields such as snapshot `id`
or `defaultTemplate` are also rejected in requests.

Portable scene fields and scalar types are strict before the batch request is built on
both frontend and backend. Unknown fields are rejected before conversion; an API event
condition carrying comparison fields is rejected instead of losing them, and a state
source must explicitly name `fromApi="state"` rather than having another attribute
rewritten to it. REST scene import and confirmed AI scene application share this admission
path. Shape/type errors return `400`; domain-invalid conditions return `422` before writing.
Fields defined
as strings remain JSON strings: the frontend does not coerce numbers or booleans into
device ids, labels, runtime values, rule/specification operands, or template names.
Export likewise rejects an incomplete current semantic item instead of dropping an
unnamed environment value/rule source or inventing a missing relation. A completed
import/export therefore never means that malformed input was silently repaired into a
different scene.

The standard portable JSON shape is `{ schema: "iot-verify.board-scene", version: 6,
templates, devices, environmentVariables, rules, specs }`. It contains stable device
references and authored semantics, but excludes database rule/spec/template ids and
derived caches (`templateLabel`, `formula`, `devices[]`, condition ids/sides/display
labels). Every shared environment variable required by a referenced template appears
exactly once with explicit `value`, `trust`, and `privacy`. Export
canonicalizes ordering, relations, and trust/privacy condition labels (lower case, with
comma-space-separated membership lists); it preserves arbitrary enum-value case.
Import rejects unsupported versions, unknown
device references, incomplete environment/template snapshots, and internal/derived fields before
uploading the file to `POST /api/board/scene`, which re-checks schema/version server-side.
Version 6 requires a user-authored `name` on every specification, following
[SpecificationDto](verification.md#specificationdto), and `variableSource` on every
`variable` condition (rejected on other target types). Older formats are rejected; no
name is synthesized from a template label or inferred from conditions. Consequently a valid exported file can be imported
and exported byte-identically, while the reconstructed canvas may use new database ids
for rules/specs without changing its semantic model. Portable device geometry uses the
same domain as targeted canvas writes and AI scene drafts: coordinates are finite within
`-1000000..1000000`, width is `80..2000`, and height is `60..2000`. An exported boundary
value is therefore a valid import value; no narrower file-import range may crop or reject
a valid saved canvas.

The browser enforces separate file-size limits for portable scenes, targeted device-list
imports, and device-template imports. Authenticated `POST /api/board/scene` and
`POST /api/board/batch` both use the dedicated scene request-body boundary; other JSON
requests keep the ordinary pre-binding limit. Limits are owned by the
[configuration reference](../getting-started/configuration.md#server). This lets a valid
scene carry its referenced self-contained template snapshots and embedded icons without
widening unrelated endpoints.

Rule and specification array order is persisted explicitly. The database-only
`rules.execution_order` and `specification.list_order` columns are written from the
submitted array positions and never appear in portable JSON. Repository reads order by
those columns, so database iteration order cannot silently reorder a scene before its
next export. `specification.list_order` is review/round-trip order only; unlike rule
execution order, it does not assign verification priority or short-circuit checks.

Portable device-local `variables[]` entries contain a unique non-blank `name` and an
explicit non-blank `value`; `privacies[]` names are also unique. Every shared
`environmentVariables[]` entry likewise has a unique non-blank `name` and an explicit
non-blank `value`. Duplicate names fail before the replacement preview or batch request.
An omitted or empty runtime value would mean "use a default" in targeted creation/update semantics,
which is not lossless scene semantics, so v6 import rejects it. A rule's human-readable
`name` is optional; rule array order and its structured trigger/action fields carry the
model semantics.

Portable semantics exclude the canvas-only `Working` placeholder. A device
whose template has both `Modes` and non-empty `WorkingStates` must include `state` and may
include `currentStateTrust` / `currentStatePrivacy`. A no-state-machine device must omit all
three fields and express readings through local variables or the shared environment pool.
Import materializes `Working` only inside the canvas DTO when needed for rendering, and
export removes it again, so the placeholder never becomes a user-authored state.

Ordinary UI creation and the AI `add_device` command generate a stable device reference
independently of the user-facing device name. Renaming therefore changes only `label`;
it never rewrites rule/specification references. Standard scene JSON is the technical
portability layer and preserves its submitted `devices[].id` values exactly. Before any
scene collection is written, the backend checks those ids (after model normalization)
against the shared-environment variables, rule playback markers, attack-analysis
bookkeeping, and stored-condition fix variables that the scene can generate. A collision
returns `422` with every field reason under `data.errors` and rolls back the complete
replacement. The error may require changing `devices[].id` and its matching references;
the display `label` can remain unchanged.

Across manual creation, standalone AI recommendation apply, and assistant `add_device`,
an explicitly chosen display name has exact semantics: a case-insensitive conflict causes
no write and the user must confirm a different available name. Automatic suffixing is
limited to system-generated names and the device-import preview, where every deterministic
rename is shown before the atomic append/replacement is confirmed.

Manual creation, AI recommendation apply, and standard scene import converge on the same
stored DTO semantics: device references are canonical node ids, rule trigger types are
explicit, specification conditions are authoritative, and formula/device lists are
rebuilt display caches. Device-list JSON/CSV import is intentionally a targeted append,
not a scene replacement; its preview reports validation errors and deterministic
duplicate-name renames before the atomic create request. JSON values for template/name
aliases and runtime fields must have the documented string types, and only one alias from
each template/name group may be supplied; the importer never chooses between conflicting
aliases or converts numbers/booleans silently. The scrollable preview keeps every row,
including errors and planned renames beyond the first screen, inspectable before the
all-or-nothing create action is enabled.

After a scene-replacement `200` response, the Board canonicalizes the returned nodes,
environment pool, rules, and specifications and compares them with the requested v6
semantics before showing a completed replacement. Template-manifest object properties
that are explicitly `null` in the request compare equal to properties omitted by the
backend's non-null DTO serialization. This equivalence does not apply to non-null values
or array order. A missing or non-equivalent response enters the same authoritative
reload/reconciliation path as a lost response; it is not presented as a successful full
import merely because HTTP status was successful.

Rules and specifications still reference environment variables through a device prefix
(for example `sensor_1.temperature`). That prefix means "this device's template declares
`temperature` as readable model state"; the actual initial value, trust, and privacy come
from `BoardEnvironmentVariableDto`. NuSMV generation initializes one shared
`a_<name>` variable from that pool and mirrors it only into devices that declare the shared value as
readable model state. This controls model references, not user or API authorization.

### Device-list input formats

JSON and CSV are separate sub-options under **Import**. Each has its own draft, file filter,
paste prompt, format help and downloadable starter file. Selecting a format never imports data
or auto-detects the other format. Switching formats invalidates pending file reads and restores
the selected draft and its validation immediately.

Each format offers mutually exclusive **Choose a file** and **Paste content** input methods.
Only the selected method supplies the preview and append command. File and pasted drafts are retained
separately per format; switching methods restores that draft immediately and invalidates pending file
reads. File input shows the selected filename and a Remove file command, while pasted input exposes
the text editor. Removing a file clears only that format's file draft, including its preview.

Device-list JSON files use UTF-8, with or without a BOM. CSV files offer an explicit file-encoding
choice: UTF-8 (default, optional BOM), or GB18030 (including GBK input). Decoding is strict: invalid
bytes, a mismatched UTF-8 BOM, and unsupported UTF-16 BOM files are rejected before a preview can
produce importable devices. Encoding is never guessed and invalid bytes are not replaced with
apparently valid name characters. Changing the CSV encoding invalidates pending reads and applies
only when a file is selected again; it does not reinterpret an existing draft or pasted Unicode text.

Both formats support the same initial device configuration and use the same runtime validator,
shared-environment merge, preview and atomic append request. JSON accepts device-entry objects
with the fields documented above. CSV uses these headers:

| Header | Equivalent JSON field |
| :--- | :--- |
| `templateName`, `label` (first two columns, required) | Template name and device label |
| `state` | `state` |
| `currentStateTrust`, `currentStatePrivacy` | Initial state labels |
| `variable:<name>` | `variables[].value`, identified by `name` |
| `trust:<name>` | `variables[].trust`, with a value supplied for that variable |
| `privacy:<name>` | `privacies[].privacy`, identified by `name` |

Optional blank CSV cells omit the corresponding override and use template defaults. A two-column,
name-only list may omit its header. Unknown and duplicate columns, extra populated cells and invalid
CSV quoting are rejected. Quoted commas, escaped double quotes and multiline cells are supported;
LF, CRLF and CR record/cell newlines retain accurate physical row locations. A numeric `0` is an
explicit value, not an omitted cell. Required template/name cells cannot be blank.
The CSV adapter expands entries into the JSON device shape before common validation; neither format
silently ignores invalid runtime fields. The bundled JSON and CSV starter files describe equivalent
devices and initial values.

Preview errors retain the device name when it can be parsed. Problem rows and planned renames
show a JSON entry number or a CSV physical line number (including the header). Shared-value,
source-label and sensitivity-label conflicts are listed together with both locations. A single
blocking notice makes the all-or-nothing append explicit; no valid subset is submitted while
any row or shared-environment conflict remains. Duplicate-name copy describes the planned
rename, which is committed only after the user imports the batch.

### Rule-derived canvas connections

There is no persisted `/api/board/edges` endpoint. The Board UI derives visible rule
connections from `RuleDto.conditions` and `RuleDto.command`, and the `board_overview`
AI tool uses the same rule-derived topology. Frontend `DeviceEdge` is only a canvas
display shape, not a backend DTO.

### `RuleDto` / `SpecificationDto`

The Java DTO classes are shared with [verification.md](verification.md), but device
reference **values** are boundary-specific. Board storage endpoints persist raw
`DeviceNode.id` values. Verification/simulation services normalize those ids through
`BoardDataConverter` in the server-captured scene; their REST endpoints accept only
[run parameters](verification.md#post-apiverify--synchronous).

The authoritative specification write shape is `{ id, name, templateId, aConditions,
ifConditions, thenConditions }`. Each condition write contains only `deviceId`,
`targetType`, `key`, optional `propertyScope`, `variableSource` (required for
`targetType: variable`, rejected on every other target type — see
[shared-value-semantics.md](../architecture/shared-value-semantics.md)), `relation`, and
`value`. The shared Java
response DTO also contains `templateLabel`, `formula`, `devices[]`, condition `id`,
`side`, and `deviceLabel`; callers must not treat those fields as writable semantics.
Board storage ignores contradictory supplied display values and rebuilds them from the
accepted template id, containing condition collection, current node labels, and
structured conditions. Frontend targeted and batch writers omit those caches entirely.

Rule sources and targets created by the frontend use canonical board node ids as their
stored references. Before creating a rule, duplicate-check,
verification, or simulation, the frontend must ensure each rule has at least one trigger
source and each source has `itemType` so the backend receives `targetType`. The backend
enforces non-empty conditions with `@NotEmpty`.

Rule source `targetType=api` means "this device emitted an API signal". It may use only
template APIs whose `Signal` flag is `true`, and it must not carry `relation` or
`value`. Rule commands may target any template API action; commands are not restricted
to signal APIs. The signal is a one-step event pulse derived from an actual
`StartState -> EndState` change. Remaining in `EndState` does not keep the condition
true. Multi-mode events require the complete start/end tuple, and a template cannot
declare two signal APIs with the same modeled transition because the model could not
distinguish them.

The Board labels this source kind **Action Event**, not merely "API": it is an
observable one-step event produced by a state-changing action, whereas the THEN side
labels an **Action** because it requests a device command. A command-only API can be a
THEN action but cannot be selected as an IF event.
Optional `command.contentDevice` and `command.content` are a pair: they select one
template-authored `Content` sensitivity label to propagate with the action. They do not
represent payload bytes or successful transmission. The pair is legal only when the
target action declares `AcceptsContent=true`; neither field may appear alone.
Rule source `targetType=mode` and `targetType=state` are enum conditions and support
only `=`, `!=`, `in`, and `not in`. Numeric `targetType=variable` conditions support
those set/equality operators and additionally support ordering comparisons. Enum
variables declared with `Values` follow the enum-operator rule.
Rule sources are current-step guards: IF evaluates the source value/signal currently
visible on the board and in traces, while THEN writes the target device's next state.
This is the shared semantic contract used by saved boards, AI recommendations,
NuSMV generation, simulation, verification, history replay, and fix suggestions.
Storage, model admission, and recommendations use the same manifest predicates for
enum values, integer bounds, state tuples, API signal capability, and observable
variable declarations. Specification source scope follows the
[shared-value contract](../architecture/shared-value-semantics.md#two-questions-and-which-one-a-specification-asks).
Their boundary
errors and policies remain separate: recommendation reachability filtering does not
prevent users from saving structurally valid contradictory conditions.

Rule array order is also model behavior. If several enabled rules command the same
device mode in one model step, the first matching rule in the saved order wins. An API
that affects several modes is one action: if an earlier matching action overlaps any of
those modes, the later action is blocked as a whole rather than partly applied. Rules
whose API effects are disjoint can still take effect together. The
Board inspector displays this order and changes it through `PUT /rules/order`. A newly
created manual or AI-recommended rule is appended last. The endpoint is a targeted,
identity-preserving compare-and-set reorder, not a scene replacement. The client sends the
complete order it displayed as `expectedRuleIds` together with the desired `ruleIds`, so a
concurrent reorder returns `409` instead of being overwritten. It returns the authoritative
ordered collection. Submitting the displayed order unchanged returns `400` and creates no undo
history. Changing order can change verification/simulation results, so the client asks the user to
run them again.

The database-only `rules.execution_order` column stores this behavior but is not a
portable identifier or an authored DTO field. Standard scene files carry the same
semantics solely through `rules[]` array order, so export/import remains lossless without
exposing a storage index.
Reorder and its undo update only changed order columns on the existing rule rows;
they preserve authored rule JSON, identity, and creation time. The order write and
its journal entry still commit in the same transaction.

Board saves perform lightweight template-semantic validation before persistence. Rules
are rejected with `422 ValidationException` when a source variable/mode/state/API key is
not defined by the referenced device template, a command action is not a template API,
a content key is unknown on `contentDevice`, content is attached to an action whose API
does not declare `AcceptsContent=true`, or a state/mode/variable value is not legal.
Specifications are likewise checked against the current template manifest: API
conditions require `Signal=true` APIs and boolean values; trust/privacy values must be
`trusted|untrusted` and `public|private`; trust/privacy keys must resolve to a variable
or state property key. Variable conditions must match declared enum `Values` or numeric
`LowerBound..UpperBound` ranges when the template defines them; enum variables cannot
use ordering comparisons.

### `BoardUndoResultDto`

Returned by `/api/board/edits/availability`, `/undo`, `/redo`, and `/clear`.

| Field | Type | Meaning |
| :--- | :--- | :--- |
| `applied` | `boolean` | Whether an edit was actually reversed or re-applied. False is a normal idempotent outcome, not an error |
| `entityType` | `String?` | `DEVICE`, `ENVIRONMENT`, `RULE`, `SPECIFICATION`, `RULE_ORDER`, or `RULE_SET`. Absent when nothing was applied |
| `originalOperation` | `String?` | `CREATE`, `UPDATE`, or `DELETE` — what the *original* edit did, not what this undo did. Absent when nothing was applied |
| `reasonCode` | `String` | `UNDONE`, `REDONE`, `NOTHING_TO_APPLY`, `AVAILABILITY_ONLY`, or `HISTORY_CLEARED` |
| `nodes` / `environmentVariables` | `DeviceNodeDto[]` / `BoardEnvironmentVariableDto[]` | Authoritative post-operation collections; replace local state outright |
| `rules` / `specs` | `RuleDto[]` / `SpecificationDto[]` | Authoritative post-operation collections; replace local state outright. All four collections are empty for `AVAILABILITY_ONLY`, where they are **not** a board state |
| `canUndo` / `canRedo` | `boolean` | Remaining availability from the journal, reported even when nothing was applied |

An applied undo always reports `canRedo=true`, and an applied redo always reports
`canUndo=true`, because the operation just made its inverse available. `NOTHING_TO_APPLY`
reports the requested direction as unavailable.

Restoring a rule preserves its original id and server-recorded `createdAt`, including
device deletion cascades and automatic-fix rule-set history. That time comes from the
saved journal snapshot; ordinary rule writes cannot choose it. A journal snapshot
missing the required creation time is unreadable restoration data and returns `409`
without writing or consuming the edit.

Undo/redo validates that a journal entry describes a real transition for its recorded operation.
An unreadable, malformed, or no-op entry returns `409 Conflict`, leaves the board unchanged, and is
not consumed, so availability does not claim that an unverified edit was applied. Recorded rule,
specification, and compound-device positions are exact reconstruction data: if a position cannot be
inserted into the current ordered collection, the entry is rejected rather than clamped or appended.
After such a conflict, the UI may offer an explicit history clear. Clearing requires confirmation,
does not mutate Board data, and always returns both availability flags as false. The confirmation
uses `BoardEditHistoryClearPreviewDto`: `{ impactToken, entryCount, canUndo, canRedo }`. Empty history
has count zero and both flags false; non-empty history must have at least one available direction.
`BoardEditHistoryClearRequestDto` is `{ impactToken }`, copied opaquely from that preview.

### `BoardLayoutDto`

`{ canvasPan: {x,y}, canvasZoom: Double, panels: { control: PanelLayout,
inspector: PanelLayout } }`.

`PanelLayout` is `{ collapsed: Boolean, width: Double, activeSection: String }`.
`panels.control.activeSection` is one of `devices`, `templates`, `rules`, `specs`;
`panels.inspector.activeSection` is one of `devices`, `rules`, `specs`, `environment`. The board
stores panel collapsed state, width, active section, and canvas pan/zoom in this one
layout document. Panel widths are normalized to `240..520`, and `canvasZoom`
must be finite and positive, with an upper bound of `2.0`; non-positive or non-finite
values normalize to `1.0`. Overview fitting may use a zoom below the ordinary manual
zoom floor, and saving/reloading preserves it. There is no separate active-tabs endpoint.

### Current schema

Development databases use the current entity schema; obsolete schemas are rebuilt
instead of migrated. Specification identity is `(id, user_id)`, edit-journal sequence
numbers are unique per user, and trace state counts are required on every new write.
After Hibernate creates the scalar-id columns, `DatabaseSchemaConstraints` establishes
and verifies the ownership foreign keys, cascading deletion, and username collation.
Startup performs no historical primary-key upgrade, trace backfill, or orphan cleanup.

---

## Device templates

| Method | Path | Body / Response | Notes |
| :--- | :--- | :--- | :--- |
| GET | `/api/board/templates` | → `DeviceTemplateDto[]` | Side-effect-free read of the current user's type catalog; an empty catalog stays empty |
| GET | `/api/board/templates/schema` | → JSON Schema | Returns the authoritative `backend/device-template-schema.json` for UI download/inspection |
| POST | `/api/board/templates` | `DeviceTemplateDto` → `DeviceTemplateDto` | Create a custom template (validates `manifest` against `backend/device-template-schema.json`, then runs NuSMV-specific validation and a probe pre-check) |
| GET | `/api/board/templates/{id}/deletion-preview` | → `DeviceTemplateDeletionResultDto` (`operation=preview`) | No-write preview with exact device-instance blockers, `editHistoryEntryCount`, and an opaque impact token |
| POST | `/api/board/templates/{id}/delete` | `DeviceTemplateDeletionRequestDto { impactToken }` → `DeviceTemplateDeletionResultDto` (`operation=deleted`) | Deletes only if the complete preview, including edit history, is still current and has no blockers; success clears undo/redo history |
| GET | `/api/board/templates/defaults/reset-preview` | → `DefaultTemplateResetResultDto` (`operation=preview`) | Computes exact type, device, Environment Pool, and edit-history effects without writing |
| POST | `/api/board/templates/defaults/reset` | `DefaultTemplateResetRequestDto { impactToken }` → `DefaultTemplateResetResultDto` (`operation=reset`) | Atomically applies the unchanged preview, clears undo/redo history, and returns authoritative final snapshots |

Destructive-preview tokens include immutable journal row identity as well as entry payload,
ordering, and undo/redo state. Clearing and recreating content-identical history therefore cannot
make an already reviewed confirmation current again.

### `DeviceTemplateDto`

| Field | Type | Rules |
| :--- | :--- | :--- |
| `id` | `Long` | Null on create; assigned by DB |
| `name` | `String` | Required |
| `manifest` | `DeviceManifest` | Required |
| `defaultTemplate` | `Boolean` | `true` for system-imported defaults, `false` for user-created/custom uploads; used by the UI for grouping and default-template reset |

Each user may store at most 100 device types. The canonical manifest schema also bounds
collection cardinality before semantic or NuSMV validation: 20 modes and 100 each for
working states, per-state dynamics, internal variables, values, environment domains,
impacted variables, transitions, APIs, and content items. Default reset performs the same
projected-catalog check and reports a no-write blocker when restoring bundled definitions
would exceed the catalog limit.

Default reset is a two-step destructive command. The preview classifies every bundled
type as `RESTORE_MISSING`, `REFRESH_DEFAULT`, or
`REPLACE_CUSTOM_NAME_COLLISION`, and reports obsolete bundled types as
`REMOVE_OBSOLETE_DEFAULT`. Each row says whether the manifest semantics change. The
preview also lists existing device instances that use changed definitions, itemized
Environment Pool changes, and blockers caused by device/rule/spec incompatibility.
Each blocker carries a user-facing `itemLabel`, stable `reasonCode`, and English
technical `reason`; ordinary UI localizes the reason code and keeps the diagnostic in a
technical disclosure. `canApply=false` exactly when blockers are present.
For each reset Environment Pool change, the backend derives previous and current token
provenance separately from the templates actually referenced by canvas devices. A shared
variable is `BUNDLED` only when every provider on that side is a bundled type; a custom
provider or unresolved source prevents localization. This matters when a custom
name-collision is replaced by a bundled definition: the old and new snapshots can have
different provenance even though the variable name is unchanged.

The POST accepts only the opaque `impactToken` from that preview. Under the per-user
write lock it recomputes the preview; board/type/edit-history drift returns `409` and performs no
write. It then deletes replaced/obsolete defaults, imports **all** bundled definitions,
runs template and NuSMV pre-checks, validates the existing device instances, rules, and
specifications against the new manifests, and updates the Environment Pool in one
transaction. Existing environment values outside a new domain are reset to that
definition's explicit default and appear as `environmentChanges`; they are not silently
retained or dropped. Any resource, validation, import, or pre-check failure rolls back
the complete reset. A successful reset clears the previewed undo/redo journal in that
same transaction: prior device/environment/rule/spec inverses were recorded against the
old manifest semantics and cannot safely be replayed under the refreshed definitions.

`DefaultTemplateResetResultDto` contains `{ operation, impactToken, canApply,
editHistoryEntryCount,
templateChanges, affectedDevices, blockers, environmentChanges, currentTemplates,
environmentVariables }`. `affectedDevices` carries stable `deviceId` plus the
user-facing `deviceLabel` and `templateName`; the ordinary UI displays the labels, not
the internal id. On a committed reset, `currentTemplates` and `environmentVariables`
are authoritative post-transaction snapshots. On a lost/incomplete response, the
frontend reads both collections and presents that reconciled current state without
retrying the destructive request or claiming success.

Blocker `reasonCode` is one of `DEVICE_INSTANCE_INCOMPATIBLE`,
`AUTOMATION_RULE_INCOMPATIBLE`, `SPECIFICATION_INCOMPATIBLE`,
`ENVIRONMENT_POOL_INCOMPATIBLE`, or `BOARD_MODEL_INCOMPATIBLE`.

Deleting a template is intentionally strict in development. Because canvas nodes use
`templateName` as their semantic pointer into the template manifest, the backend rejects
deletion while that template is still referenced by any canvas device. This prevents a
board that looks valid in the UI but cannot be consumed by NuSMV, AI recommendations, or
fix logic.

Template deletion is a two-step command, not a one-click `DELETE`. The preview response
contains `{ operation, impactToken, canDelete, editHistoryEntryCount, template, blockers,
currentTemplates }`.
Each blocker is `{ reasonCode, itemId, itemLabel, reason }`; ordinary UI shows the
device label and localizes `DEVICE_INSTANCE_USES_TEMPLATE`, while the internal id and
English diagnostic stay in technical detail. A blocked preview has `canDelete=false`
and cannot be committed.

The commit request accepts only the preview's opaque `impactToken`. Under the per-user
write lock and transaction, the backend recomputes the template, blocker set, and exact
edit journal. A stale
token returns `409` with `reasonCode=TEMPLATE_DELETION_PREVIEW_STALE` and
`currentPreview`; a still-in-use template returns `TEMPLATE_DELETION_BLOCKED`. Neither
case writes. A successful result includes `deletedTemplate` and authoritative
`currentTemplates`, which the client uses directly instead of guessing local state, and
clears undo/redo history in the same transaction. Deletion is a history boundary even
when no current device uses the type: a redo snapshot may still contain a device whose
semantics depend on the deleted manifest.
Both preview and commit scope lookup by `templateId + userId`. A missing id and a
template owned by another account therefore return the same `404`, without revealing
whether another user's resource exists.

`DeviceManifest` uses **PascalCase** JSON keys (`@JsonProperty`): `Name`,
`Description`, `Icon`, `Modes`, `InternalVariables`,
`ImpactedVariables`, `InitState`,
`WorkingStates`, `Transitions`, `APIs`, `Contents`. Key nested shapes:

`backend/device-template-schema.json` is the authoritative structural schema for the
`manifest` object. The backend validates the raw incoming `manifest` JSON against that
schema before DTO mapping, so unknown fields and lower-case alternatives are rejected
at the API boundary. Frontend helpers may normalize user-uploaded JSON before sending,
but the final request body must match this schema. The Maven build packages the same
file to `classpath:device-template-schema.json`, and `DEVICE_TEMPLATE_SCHEMA_PATH` may
override its filesystem location for deployments.

Structurally, the schema requires only `Name` at the manifest top level. Other arrays
may be omitted and are treated as empty by consumers. Semantically, a mode-based
template must provide the full state-machine trio: non-empty `Modes`, non-blank
`InitState`, and non-empty `WorkingStates`. If all three are absent/empty, the backend
treats the template as a no-mode device template and validates only variables,
environment impacts, contents, and the NuSMV probe pre-check. APIs are state actions and
therefore require at least one mode; stateless autonomous variable behavior belongs in a
triggered `Transition`.

`InitState` is a concrete complete configuration: it contains exactly one value per mode
and must exactly match one `WorkingStates.Name`. Empty segments, `_` wildcards, and
case-different aliases are rejected rather than allowing the generator to miss the
declared initial state and fall back to nondeterminism. Partial tuples remain valid only
where an API/Transition endpoint explicitly denotes a partial state change; they are
rejected for initial state because manual creation and portable scenes require one
deterministic starting point.

Template names and user-authored variable/property keys are literal business names.
There is no reserved `variable_` template-name prefix, and generated NuSMV prefixes or
suffixes such as `a_`, `trust_`, `privacy_`, `_rate`, `_a`, and `is_attack` are not
accepted as user-facing aliases. During template creation the backend rejects concrete
generated identifier collisions inside the same NuSMV module, for example an
InternalVariable `temperature_rate` colliding with the generated rate variable for
ImpactedVariable `temperature`, or InternalVariable `trust_temperature` colliding with
the generated trust variable for InternalVariable `temperature`. A prefixed business name
is valid when it does not collide with an actually generated identifier. Model
generation applies the same concrete-collision rule in `MODULE main`: device instance
ids must not collide with generated `a_<environmentName>`, the internal compromised-point
counter, or
auto-fix parameterization identifiers active in that model.

Three further admission gates apply to `InternalVariables[].Name` and reject the upload
with `400`:

- `Template '<t>': InternalVariable name '<n>' must not start with 'a_' (reserved for
  environment pool identifiers).` — compared case-insensitively.
- `Template '<t>': InternalVariable name '<n>' is a NuSMV reserved word.`
- `Template '<t>': InternalVariable name '<n>' collides with a mode name (both emit 
  device.<n> in the same namespace).` — compared after NuSMV token normalization, so 
  `Power` and `power` collide.

- `InternalVariable`: `Name`, `Description`, required `IsInside`,
  `FalsifiableWhenCompromised`, required `Trust`, required `Privacy`,
  `LowerBound`, `UpperBound`, `NaturalChangeRate`, `Values`. `Privacy` is a model
  sensitivity label used for propagation analysis; it does not configure runtime
  visibility, authorization, or encryption. `FalsifiableWhenCompromised` is required
  and states whether a compromised instance may replace this reported value with any
  value in its declared domain and mark the source untrusted. It applies to both shared
  (`IsInside=false`) and device-local (`IsInside=true`) readings, so API
  presence is not used to guess whether the value is sensor data. Use `false` for
  actuator progress/setpoints and other values outside that threat model. **Provide
  exactly one explicit domain: `Values` (enum, including `TRUE`/`FALSE` for booleans)
  XOR `LowerBound`+`UpperBound` (numeric range); never omit the domain, provide both
  forms, or provide only one bound** (backend `@AssertTrue isValidVariableDefinition`).
  Numeric bounds must be ascending. Enum values are emitted as bare SMV tokens, so after
  spaces are removed each must be distinct, must match `^[a-zA-Z_][a-zA-Z0-9_]*$`, and must
  not be a NuSMV reserved word (compared case-sensitively, matching the engine's lexer). `NaturalChangeRate` is valid only for numeric domains and accepts exactly
  a 32-bit integer such as `1` or a bracketed, ascending two-integer interval such as
  `[-1, 1]`; unbracketed pairs and extra endpoints are invalid. It is **required** when the numeric
  variable is shared (`IsInside=false`). A bare integer is shorthand for the interval between it and
  zero (`1` means `[0, 1]`, `-2` means `[-2, 0]`).
  What the declaration *means* for the model — exhaustive deltas, the domain clamp winning over the
  rate, and what `0` versus `[-1, 1]` each assert — is owned by
  [../architecture/shared-value-semantics.md](../architecture/shared-value-semantics.md#5-natural-evolution);
  its attribution to MEDIC §3.1 by
  [../architecture/theory-sources.md](../architecture/theory-sources.md).
- `WorkingState`: `Name`, `Description`, required `Trust`, required `Privacy`, `Dynamics[]`
  (`Dynamic` = `VariableName`, `Value`, `ChangeRate`). In a multi-mode template, every
  working-state name is a complete semicolon-separated tuple with one concrete value per
  mode. If the same mode-state component appears in several tuples, all of those rows
  must assign it the same effective `Trust` and `Privacy`; otherwise the manifest is
  rejected because the component-level formal labels cannot represent it losslessly.
  Trust is MEDIC's control-source label used when that state triggers a rule or safety
  property: one trusted contributing trigger source retains trusted control, while only
  an all-untrusted trigger set makes the target untrusted. It is not authentication or a
  generic data-integrity label. Privacy is the state's sensitivity label. These
  labels are modeled for stateful sensors even when the template has no command APIs.
  Raw `Invariant` strings are not accepted: the previous field was stored and displayed
  but never entered the generated model. Express device evolution through structured
  `Dynamics`/`Transitions`, and user requirements through structured specifications.
- `Content`: `Name`, optional explanatory `Description`, and required `Privacy`. Content sensitivity has no safe implicit
  default: omitting it is rejected rather than silently treating the item as public.
  Each Dynamic target must be either a device-local `InternalVariable` or a shared name
  listed in `ImpactedVariables`, and may appear once per WorkingState. Numeric targets use
  integer `ChangeRate`; enum/boolean targets use an in-domain `Value`. Wrong-domain,
  duplicate, or unknown effects are rejected rather than saved and skipped by generation.
- `Transition`: `Name`, `Description`, `StartState`, `EndState`, `Trigger`
  (`Attribute`, `Relation`, `Value`), `Assignments[]` (`Attribute`, `Value`). A Transition
  is autonomous device behavior, not a user-callable command. The current formal model
  accepts exactly one effect per Transition: one concrete mode target or one variable
  assignment, never both and never several. This fail-fast restriction prevents a
  conflict from applying only part of an accepted-looking action. A variable-changing
  transition requires a trigger. Trigger attributes and values must belong to a readable
  mode/variable domain; enum, mode, and boolean triggers use only `=`/`!=`. Every assignment
  target must be a declared `InternalVariable`, and its value must
  belong to that declaration's enum/range/boolean domain. Stateless templates may use
  trigger-plus-assignment transitions, but cannot declare state endpoints. `Signal` is not
  accepted on Transitions because rules and specifications cannot reference an internal
  transition pulse; user-consumable event pulses belong to state-changing APIs.
- `API`: `Name`, `Description`, `Signal`, optional `AcceptsContent`, `StartState`, `EndState`,
  `Trigger: null`. Despite the manifest field name, these are device commands/actions,
  not HTTP or network endpoint definitions. APIs change modeled state only; `Assignments`
  is not an API field. Stateless APIs and APIs with no possible state effect are rejected.
  API triggers are not a supported generator semantic; conditional autonomous behavior
  belongs in `Transitions`. `StartState` is required; an explicit empty string or `_`
  pattern means “callable from any state”, while a concrete value is the command
  precondition. `Signal` is a required explicit choice: `true` makes the
  action observable as an automation/specification trigger, while `false` leaves it
  command-only. When `Signal=true`, the API event is a one-step observation of
  an actual complete `StartState -> EndState` change, not a persistent "currently in the
  end state" flag. An observable API route may not overlap another API or autonomous
  Transition route, because a state-change-derived pulse could not identify which action
  occurred. `AcceptsContent` defaults to `false`; when `true`, a rule invoking this action
  may select exactly one template `Content` item as an additional sensitivity-propagation
  source. It does not model payload bytes, an upload protocol, access control, encryption,
  or successful delivery. Rules cannot attach content labels to ordinary actions that do
  not declare this capability.
- `Content`: `Name` (an SMV identifier — it is emitted as `privacy_<Name>`), optional
  `Description`, `Privacy`.

Only templates instantiated by current board devices contribute environment semantics.
Installed but unused templates are irrelevant. Declarations for the same active shared
name must use the same literal casing, domain (including enum order), natural change
rate, default trust, and default privacy; node/scene writes reject conflicts before
persistence, so device ordering cannot choose the model silently.
Environment Pool compare-and-set writes run the same active-definition check before
reading baselines or persisting a patch.

`Icon` is optional and is ignored by NuSMV generation. It exists for UI rendering:
custom JSON imports may provide a self-contained `data:image/...` URI (max 256 KB).
Remote URLs are rejected so opening a catalog cannot contact a template-controlled host.
If `Icon` is omitted, the frontend first looks for a bundled
default asset matching the template/state, then generates a stable fallback icon from
the template name so custom templates never render as blank images.

Rule conditions must include `targetType` (`api | variable | mode | state`). The
backend stores and returns it as the authoritative semantic type for UI, AI, NuSMV
generation, and fix logic. Device references use canonical board node ids; labels are
display-only. See [../architecture/device-identity.md](../architecture/device-identity.md).

After schema validation, the NuSMV-specific constraints (identifier legality and
StartState/EndState format) are enforced by runtime validation —
see
[../architecture/nusmv-model.md](../architecture/nusmv-model.md) and the P1–P5 rules in
[../architecture/spec-templates.md](../architecture/spec-templates.md). In particular,
`Trust` must be exactly `trusted` or `untrusted` and `Privacy` must be exactly `public`
or `private` (lowercase; P4). The canonical JSON Schema rejects other casing at import
instead of storing a value that the typed scene contract cannot re-import. Default template
JSON lives in `backend/src/main/resources/deviceTemplate/` and is also checked against
the canonical schema when imported.

---

## Recommendation, duplicate check, and AI similarity check

REST controllers pass the authenticated user and typed request directly to the
recommendation application services in `service/recommendation/`. The
[chat tools](ai-tools.md) call those same implementations after parsing their tool
arguments. Provider output is normalized and validated at the shared application
boundary, including candidate semantics, audit counts, and scenario analysis;
both entry points receive the same typed result. Typed failures retain the HTTP
semantics defined in [overview.md](overview.md). JSON is serialized at the provider
and transport boundaries.

Every standalone recommendation request requires an opaque `requestId` in the shared format defined in
[overview.md](overview.md#client-supplied-requestid). The same rule applies wherever it appears:
request bodies, query parameters, status paths, and cancellation paths.

Standalone recommendations are locally registered as `WAITING` before distributed ownership
acquisition. A stop accepted during that wait prevents model execution and releases any late lease.
Successful completion and local cancellation share one terminal decision: releasing a successfully
completed lease must not be interpreted as lease loss or turn the result into a cancelled request.

| Method | Path | Query / Body | Notes |
| :--- | :--- | :--- | :--- |
| POST | `/api/board/rules/recommend` | JSON body `StandaloneRecommendationRequestDto`: required `requestId`, `maxRecommendations` (default 5; integer `1..10`), `language` (default `en`), and optional `userRequirement` | Returns `RecommendationResponseDto<RuleRecommendationDto>` |
| POST | `/api/board/specs/recommend` | JSON body `StandaloneRecommendationRequestDto`: required `requestId`, `maxRecommendations` (default 5; integer `1..10`), `language` (default `en`), and optional `userRequirement` | Returns `RecommendationResponseDto<SpecificationRecommendationDto>` |
| POST | `/api/board/devices/recommend` | Required `requestId` query parameter plus typed `DeviceRecommendationRequestDto`: `{ maxRecommendations, language, userRequirement }` | Returns `RecommendationResponseDto<DeviceRecommendationDto>` |
| POST | `/api/board/scenario/recommend` | Required `requestId` query parameter plus typed `ScenarioRecommendationRequestDto`: required integer `minDevices`, `minRules`, `minSpecs`, `maxDevices`, `maxRules`, and `maxSpecs` in `1..10` (each minimum no greater than its maximum), plus optional `language`, `userRequirement`, and `templateNames` | Returns `ScenarioRecommendationResponseDto`, including `scenarioName`, a deterministic post-validation `rationale`, echoed `objectiveTargets`, objective completeness, validation counters, structural readiness, semantic warnings, and a typed `PortableSceneDto` using the canonical `iot-verify.board-scene` import/export shape. The optional template scope follows the [scene recommendation contract](ai-tools.md#rules). |
| GET | `/api/board/recommendations/{requestId}` | Reads the authenticated user's matching active or just-finished request | Returns `InteractiveOperationStatusDto`; terminal status is retained briefly for the final polling tick, while unknown requests return 404 |
| DELETE | `/api/board/recommendations/{requestId}` | Cancels the authenticated user's matching in-flight request | Returns `boolean`; `true` means the durable stop signal was accepted for the matching local or remote execution. The callable may still be unwinding, so status remains authoritative until `FINISHED` |
| POST | `/api/board/rules/check-duplicate` | body: typed `RuleDto`; every condition includes `targetType`; rule API-signal conditions omit `relation`/`value` | Deterministic duplicate-rule check used by `RuleBuilder` before saving. It does not call the external LLM and returns a typed `DuplicateRuleCheckResultDto`: required `isDuplicate`, `requiresReview`, `similarity` (`0..1`), `matchType`, stable `reasonCode`, technical `reason`/`message`, plus nullable readable `matchedRule`. Clients localize the ordinary explanation from `reasonCode`. |
| POST | `/api/board/rules/check-similarity` | body: typed `RuleDto`; every condition includes `targetType`; rule API-signal conditions omit `relation`/`value` | Explicit AI semantic similarity check available from `RuleBuilder` and the Board rule-recommendation apply flow. It may call the configured LLM and returns a typed `RuleSimilarityResultDto`: required `isSimilar`, `isDuplicate`, authoritative `requiresReview`, `similarity` (`0..1`), stable `reasonCode`, technical `reason`/`message`, plus nullable readable `matchedRule`. Internal candidate references and LLM prose are not ordinary UI concepts. |

Duplicate-check `reasonCode` is one of `NO_EXISTING_RULES`, `EXACT_MATCH`,
`TRIGGER_SET_CONTAINS_OTHER`, `SAME_TRIGGER_SHAPE_DIFFERENT_VALUES`,
`PARTIAL_TRIGGER_OVERLAP`, or `NO_MATCHING_SIGNATURE`. AI similarity uses
`NO_EXISTING_RULES`, `AI_DUPLICATE`, `AI_SIMILAR`, `AI_HIGH_SCORE_REVIEW`, or
`AI_NO_SIGNIFICANT_SIMILARITY`. `requiresReview` is the server decision; clients do not
recompute it from the score. The score and AI reason are evidence, not proof of safety or
conflict freedom.

Both checks exclude the saved rule whose id equals the candidate's optional `id`,
so editing a rule never compares it with itself. With no other saved rules, AI
similarity returns `NO_EXISTING_RULES` without calling the provider.

> Standalone device/rule/spec recommendation responses include backend-generated `message`,
> `count`, `requestedCount`, `validatedCount`, `filteredCount`, `filteredItems`,
> `rawCandidateCount`, `inspectedCount`, `truncatedCount`, and `recommendations`.
> Device and rule recommendation responses additionally include `adjustedCount` and
> `adjustedItems` for deterministic values added to, or semantics-preservingly
> normalized within, kept candidates.
> `count` and `validatedCount` are the kept recommendation count; `filteredCount` is the
> number of AI suggestions inspected but rejected because they did not match current
> board/template capabilities. `filteredItems[]` gives per-candidate feedback as
> `{ type, index, reasonCode, reason, label? }`; `reason` is localized according to
> `language`, and `label` is best-effort display text such as the saved rule name,
> specification rationale, device label, or template name. `rawCandidateCount` is the number of parsed AI
> candidate entries, `inspectedCount` is the number the backend actually validated, and
> `truncatedCount` is the number left uninspected after the requested limit was reached.
> The accounting identities are contractual: `inspectedCount = validatedCount +
> filteredCount` and `rawCandidateCount = inspectedCount + truncatedCount`, while
> `filteredCount` equals `filteredItems.length`. The Board displays all five counts in
> every recommendation panel and rejects an inconsistent response instead of showing a
> partially explained result. A parsed empty AI array is explicitly reported as zero raw
> candidates, distinct from candidates that the backend inspected and rejected.
> The backend localizes its own fallback/success messages according to `language`; the
> LLM prompt also instructs generated natural-language fields such as `name`, `rationale`, and
> `reason` to use that language. Omitted/blank parameters use their documented defaults, but
> an unsupported language, non-string requirement, or `userRequirement` longer
> than 2,000 trimmed characters returns `400`; no explicit parameter is silently downgraded or
> truncated before prompting.
> A standalone rule API event normally omits `relation` and `value`. The semantically
> equivalent AI spelling `{ relation: "=", value: "TRUE" }` is kept, normalized to the
> event form, returned as an `apiEventSyntaxNormalized` adjustment, and shown by the
> Board before application. Partial comparison fields, `FALSE`, and non-equality
> relations are filtered with `invalidApiEventSyntax`; the backend no longer silently
> erases a comparison that may change the event meaning.
> The recommendation application services parse model output into these DTOs and check
> every accounting identity before returning a result to REST or chat callers. Missing audit fields, unexplained filtered or
> adjusted entries, unknown kept-candidate fields, and malformed portable-scene fields
> return HTTP 502 rather than crossing the REST boundary as an untyped success map.
> The standalone scenario endpoint likewise returns only its typed REST fields. Chat-only
> `draftStored` and `previousDraftRetained` metadata is added only when the service runs
> for an authenticated chat session; it never leaks into `/scenario/recommend` success
> responses.
> Standalone recommendation DTOs omit genuinely absent optional values instead of
> serializing them as explicit `null`. Rule API conditions therefore remain relation/value-
> free, and specification recommendation conditions contain only authored semantic fields:
> persistence-only condition `id` and derived group `side` never cross this boundary.
> The Board validates every kept candidate before display/application and fails closed if
> this exact shape is violated; it does not invent a relation or coerce a scalar locally.
>
> Rule candidates contain required `name`, `conditions`, and `command`. `name` is not
> advisory copy: it is the exact user-facing rule name persisted when Apply succeeds.
> Candidates are returned in relevance order and do not contain a `priority` field;
> applying one appends it to the end of the current execution order and never silently
> promotes it above existing rules. Specification candidates contain `rationale`,
> `templateId`, and all three condition arrays. `rationale` explains why the AI suggests
> checking the property but is not persisted or verified; the formal property consists of
> `templateId` plus structured conditions. Specification recommendations likewise have no
> priority/check-order field because every accepted property is checked. A missing rule
> name or specification rationale is an itemized filtered candidate, and the REST boundary
> rejects a kept candidate missing either field rather than returning a misleading HTTP 200.
>
> Specification model output may omit condition groups unused by its chosen template.
> After validating the template shape and every condition, the application service returns
> these inactive groups as empty arrays. A present group must be an array: explicit `null`
> or scalar/object groups, missing required groups, and non-empty inactive groups are
> filtered as individual candidates without discarding other valid recommendations.
>
> Device recommendations are instance-level. Each kept item contains `templateName`, an
> effective `suggestedLabel`, and may contain advisory `intendedUse`/`suggestedPlacement`;
> these two context fields are neither persisted device properties nor formal-model inputs. For a stateful or locally
> parameterized template it also contains effective `initialState` and
> `initialVariables`; explicit advanced overrides may add `currentStateTrust`,
> `currentStatePrivacy`, per-variable trust, and `initialPrivacies`. Runtime values are
> constrained to local template variables (`IsInside=true`). If an AI candidate supplies an
> invalid initial state, malformed runtime arrays, unknown local variables, invalid local
> variable values, values contradicting the selected state's declared dynamics, or invalid
> trust/privacy values, the whole device candidate is rejected
> with a `filteredItems[]` reason instead of being returned with those fields silently
> dropped. Omitted state fields use the template's initial state; omitted local values are
> derived from the effective selected state, then template defaults. Transition-assigned
> variables remain independent of state-derived values. These adjustments are reported in
> `adjustedItems[]` as `{ type, index?, reasonCode, reason, label?, appliedValues }`, so the
> preview and later create request use the same initial model. Omitted trust/privacy
> fields stay absent so the active template remains authoritative. Display labels are unique
> across the whole board ignoring case, even across different templates. Multiple
> recommendations follow the [instance-capability constraints](ai-tools.md).
> Applying a recommendation writes the effective device type, label,
> layout, and runtime values only. It may also initialize currently missing shared
> Environment Pool items required by the template; the Board previews their names and
> presents the authoritative `environmentChanges` returned by the create transaction.
>
> `/scenario/recommend` is intentionally not a wrapper around the three standalone
> recommendation endpoints. It asks the AI for one coupled scene draft, then validates
> temporary device aliases, template names, device initial runtime, rule capabilities, specification
> condition keys, and the scene environment pool against that single draft. If a generated
> device supplies an initial `state`, `currentStateTrust`, or `currentStatePrivacy` that does not match its
> template, the whole device candidate is filtered instead of being returned with a silent
> default. Environment variables are not arbitrary JSON attachments: a kept variable name
> must be declared as an external template variable or listed in `ImpactedVariables` by at
> least one kept device template; unknown environment candidates are filtered with
> `type: "environment"` so applying/exporting the scene will not silently drop them. Its response includes the same
> `requestedCount`, `validatedCount`, `filteredCount`, `filteredItems`, `adjustedCount`, `adjustedItems`,
> `rawCandidateCount`, `inspectedCount`, and `truncatedCount` feedback for generated
> devices/environment entries/rules/specs. `count` is the number of items in the final
> scene, including required environment variables; `requestedCount` is the sum of the
> three explicit minimum targets, not the maximum caps. `validatedCount` counts inspected raw
> candidates that were kept, while `adjustedItems[]` reports deterministic defaults or
> missing required environment entries added to make the scene complete as
> `{ type, index?, reasonCode, reason, label?, appliedValues }`. These adjustments are
> distinct from rejected `filteredItems` and uninspected `truncatedCount`. Because a
> missing required environment entry can be synthesized rather than accepted from the
> raw AI output, scenario `count` is not required to equal `validatedCount`; clients
> verify `count` against `scene.devices + scene.environmentVariables + scene.rules +
> scene.specs` instead. The raw-candidate identities still use `validatedCount`.
> Every applied scene item must nonetheless be accounted for: clients require
> `validatedCount + adjustedCount >= count`, so a response cannot hand over a scene while
> reporting that it inspected no candidates. This is a floor rather than an equality
> precisely because an adjustment is not obliged to add an item.
> The response carries authoritative `objectiveTargets` (`{ minDevices, minRules,
> minSpecs }`), `objectiveStatus=COMPLETE|PARTIAL`, and ordered `objectiveIssues[]`
> (`{ code, message }`). The application service requires the echoed targets to match the
> submitted minimums, and both the service and frontend recompute status from the canonical scene.
> A missing item kind produces `NO_DEVICES`, `NO_AUTOMATION_RULES`, or `NO_SPECIFICATIONS`;
> a positive count below its explicit minimum produces `INSUFFICIENT_DEVICES`,
> `INSUFFICIENT_AUTOMATION_RULES`, or `INSUFFICIENT_SPECIFICATIONS`. `COMPLETE` means only
> that all three explicit count targets were reached; it is not a claim that the rest of the
> user's prose was satisfied or that formal verification passed.
> The response also carries authoritative `verificationReady` and ordered
> `readinessIssues[]` (`{ code, message }`). The application service and frontend recompute
> readiness from the canonical returned scene and reject a mismatch; currently
> `NO_DEVICES` and `NO_SPECIFICATIONS` are the deterministic issue codes. A draft that
> lacks specifications remains reviewable/applicable but is not presented as ready for
> `verify_model`.
> `verificationReady` means only that the retained scene has the devices and
> specifications required by the verification entry point. It does not mean that the
> draft satisfies the natural-language requirement. That separate boundary is exposed as
> ordered `semanticWarnings[]` (`{ code, message }`): `FILTERED_CANDIDATES` reports that
> validation removed generated content, `NO_AUTOMATION_RULES` reports that the retained
> scene establishes no automation loop, and `UNREFERENCED_DEVICES` names retained devices
> that participate in neither a retained rule nor a retained specification. These warnings
> are computed from the final canonical graph, not inferred from the user's prose. The
> returned `rationale` is likewise a backend-generated count summary of retained content;
> model-authored rationale is not passed through after filtering and the summary explicitly
> disclaims requirement satisfaction and formal-verification success.
> A rule API event source normally omits `relation` and `value`. If the AI emits the
> semantically equivalent pair `relation = "="`, `value = "TRUE"`, the backend keeps the
> rule, removes those redundant fields, and returns an `apiEventSyntaxNormalized`
> adjustment. It still rejects partial pairs, `FALSE`, and non-equality relations because
> those do not describe the same event-occurrence semantics.
>
> AI-proposed device ids are one-response aliases used only to bind the generated graph.
> The backend assigns portable `device_1`, `device_2`, ... references and rewrites every
> rule, content source, and specification reference consistently before returning the
> canonical scene. The aliases are not shown as user identity and cannot leak unsafe
> model names into an otherwise valid recommendation. A kept rule/specification is candidate-atomic: if any generated
> rule source, rule content binding, or specification condition fails validation, the whole
> rule/specification is rejected with a `filteredItems[]` reason rather than being returned
> after silently dropping only the invalid nested item. If validation removes every generated device, the
> response keeps an empty canonical `scene` and returns a no-applicable-scene message
> instead of reporting a complete scenario. Prefix-like business names such as `a_noise`, `trust_score`,
> `privacy_level`, or `variable_mode` stay literal in the returned scene; generated NuSMV
> identifiers remain an internal modeling detail.
> Candidate validation also intersects conditions within each rule and each A/IF/THEN
> specification group. Individually legal predicates that have no common declared state
> or variable value are rejected as a whole. Rule target-device predicates must also
> intersect the command API's non-empty `StartState`. These stricter checks apply to
> AI-authored candidates/mutations; ordinary user Board persistence keeps its existing
> structural authoring contract.

Standalone recommendations run in a bounded dedicated executor. Only one is admitted per user;
pool saturation returns `503`. While a request is active, its status DTO is
`{ requestId, state, stage, elapsedMs }`. `state` is `WAITING`, `RUNNING`, or briefly
`FINISHED`; `stage` is one of
`QUEUED`, `RUNNING`, `PREPARING_CONTEXT`, `REQUESTING_MODEL`, `VALIDATING_RESULT`, or
`CANCELLING` for this workflow. The UI polls this endpoint and shows the submitted Board-context
counts, selected tool, elapsed time, and the actual server-observed stage. It does not infer a
phase from elapsed time, and these operational stages are not hidden model reasoning. Stop and
panel-close actions call the cancellation endpoint before aborting the browser request.
Ownership, admission and the `503` contract follow the shared interactive-request rules in
[overview.md](overview.md#interactive-request-ownership-and-the-503-contract). Specific to this
workflow: active cancellation records renew for the callable's lifetime and the final status is
retained briefly, so a poll routed to another backend instance still observes and can stop the
accepted request.

After a stop action the Board keeps the workflow visibly stopping until status reports
`FINISHED`. A first `404` can be a registration-versus-cancellation race and is not treated
as proof of completion. Five consecutive unavailable status reads end local polling with a
visible outcome-unknown warning, preventing both a false success claim and an endless spinner.
Recommendation prompts use a compact capability projection; the complete template manifests
remain server-side for authoritative validation.

If the AI provider's whole response is malformed JSON or lacks the required
recommendation/scene arrays, recommendation endpoints return HTTP `502` through
`BadGatewayException` (`AI_RESPONSE_INVALID` at the tool boundary). This is distinct
from a valid response whose individual candidates were rejected through
`filteredItems`, and from a valid empty recommendation set.
The same HTTP-502 behavior applies to an unusable AI similarity response; the endpoint
does not degrade it to `isSimilar=false`. A duplicate must also be marked similar. Both
rule-check endpoints and the frontend reject missing required fields, non-numeric or
out-of-range scores, and wrong field types instead of interpreting them as a clear check.
