# Data Authority Model

This document is the project authority for board data contracts. The project is
in active development: invalid legacy shapes should be fixed at the source or by
clearing development data, not by adding fallback branches.

Verified against code on 2026-08-13. Source: board/fuzz DTOs and services,
`BoardDataConverter`, `modelRequest.ts`, scene import/export, fuzzing, and NuSMV generation.

## Principles

- Board storage keeps authoring data: raw `DeviceNode.id`, display labels,
  positions, rules, specs, templates, the board environment pool, and layout.
- Verification, simulation, bounded exploration, traces/findings, and fix use the model boundary: each device
  reference is the NuSMV-safe `varName` produced from `DeviceNode.id`.
- Display labels never resolve identity. `label`, `deviceLabel`, and
  `deviceName` labels are readable snapshots only.
- Edges are derived display geometry from rules. There is no persisted edge
  endpoint or table in the current code path.
- Downstream consumers fail fast on unknown references. A saved board rule/spec
  may reference only an existing `DeviceNode.id`; a model request may reference
  only an existing `DeviceVerificationDto.varName`.
- A canvas node may reference only an existing device template. Deleting a
  template that is still used by canvas devices is rejected; delete or retarget
  those devices first.

The backend user write lock and transaction define persistence order and atomicity. The
Board additionally serializes its own semantic mutations and post-error reconciliation
before applying collection snapshots; device-type mutations use the same parent runner,
and assistant-triggered refreshes join the queue after their server tool completes. This
client ordering prevents stale response presentation but does not combine separate REST
commands into one transaction. Verification/simulation wait for the queue before taking
their immutable input snapshot. Full scene import/clear separately locks interaction from
confirmation through reconciliation and is atomic because it is one batch request.

## Device Template

Backend DTO: `DeviceTemplateDto`. Frontend type: `DeviceTemplate`.

| Field | Authority | Purpose | Downstream |
| --- | --- | --- | --- |
| `id` | DB | Template row id for delete/reset UI | Template APIs |
| `name` | DB, same as manifest `Name` | Template lookup key for node `templateName` | Node creation, NuSMV manifest load |
| `manifest` | JSON schema validated | Device semantics: modes, states, variables, APIs, privacy, icon | Rule/spec builders, model generation |
| `defaultTemplate` | DB | Splits default vs custom templates | Template repository UI |
| `manifest.Name` | Template authoring | Canonical template name | Node `templateName`, current device details |
| `manifest.Description` | Template authoring | User-facing explanation | Template cards, AI context |
| `manifest.Icon` | Template authoring | Self-contained `data:image` URI | Template cards, node icons |
| `manifest.Modes` | Template authoring | Mode dimensions for multi-mode state strings | Rule/spec options, NuSMV state variables |
| `manifest.InternalVariables` | Template authoring | Device variables and bounds/enums. Required `IsInside=true` means device-local; required `IsInside=false` means a shared environment variable and declares that this device reads/references it in the model. This is not authorization. Shared declarations require explicit `Trust` and `Privacy`. Required `FalsifiableWhenCompromised` explicitly says whether compromise may replace that reported value inside its declared domain; API presence never infers ownership or attack behavior. Required `Reads` on a shared declaration says whether this device observes the value: `true` grants a read mirror and rule/spec source capability, `false` is affect-only. It is rejected on a device-local declaration. See [shared-value-semantics.md](shared-value-semantics.md). | Rule/spec options, environment pool, NuSMV variables, attack behavior, fix ranges |
| `manifest.ImpactedVariables` | Template authoring | Shared environment variables affected by the device. This creates/keeps the variable in the environment pool but does not declare a device read/reference. Each name must resolve in the same manifest to a shared `InternalVariable` (`IsInside=false`), whose `Reads` flag says whether this device also observes it | NuSMV environment model |
| `manifest.InitState` | Template authoring | Concrete complete initial configuration matching one `WorkingState`; no wildcard/partial tuple | Node creation default, NuSMV init |
| `manifest.WorkingStates` | Template authoring | Legal states and state dynamics | Node state UI, rule/spec validation, NuSMV transitions |
| `manifest.Transitions` | Template authoring | Autonomous single-effect behavior: one concrete mode update or one internal/shared-variable assignment. Triggers read only declared modes/readable variables and values must fit their domains. Multi-effect declarations are rejected rather than partially modeled | NuSMV state/variable transitions |
| `manifest.APIs` | Template authoring | Stateful device commands/actions, not network endpoints. They have no Trigger or variable assignments; `Signal=true` exposes a one-step actual state-route event | Rule commands may target any API; rule/spec source conditions may use only `Signal=true` APIs |
| `manifest.Contents` | Template authoring | Privacy content names and optional descriptions | Privacy rule actions and content privacy variables |

Portable scene snapshots require the outer template `name` and `manifest.Name` to be
exactly equal. They are one canonical identity, not aliases; scene import rejects a
mismatch before any template or board write instead of silently renaming the manifest.
The frontend does not use a browser-stored manifest as a semantic fallback. If the
current template catalog cannot resolve a device's `templateName`, device details fail
closed and say that the definition is unavailable instead of presenting stale states,
variables, APIs, or trust/privacy labels as the current backend model.

## Device Node

Backend DTO: `DeviceNodeDto`. Frontend type: `DeviceNode`.

| Field | Authority | Purpose | Downstream |
| --- | --- | --- | --- |
| `id` | System-generated for ordinary UI/AI creation; explicit and preserved in standard scene JSON | Stable device instance reference, not a display name | Rules/specs/AI board references; normalized into model `varName` |
| `templateName` | Template `manifest.Name` | Selects device semantics | Template lookup, NuSMV manifest load |
| `label` | User | Display name only | UI text, trace readability; never identity |
| `position.x/y` | Canvas UI | Finite board coordinates in `-1000000..1000000` | Canvas, minimap, derived edge geometry |
| `state` | User/template init for templates with `Modes`; canvas placeholder for no-mode devices | Current full state value only for modeled state machines | Model request initial state for mode-based devices; omitted for no-mode sensors/devices |
| `width` / `height` | Canvas UI | Integer node geometry; width `80..2000`, height `60..2000` | Canvas hit testing, edge geometry, minimap bounds |
| `currentStateTrust` | Optional user/runtime override | Overrides trust for the current state; omitted means use `WorkingStates.Trust` | NuSMV trust modeling |
| `currentStatePrivacy` | Optional user/runtime override | Overrides sensitivity for the current state; omitted means use `WorkingStates.Privacy` | NuSMV privacy modeling when enabled |
| `variables[].name/value/trust` | User/runtime override, **except a value the device's state determines** (see below) | Device-local variable values and trust (`InternalVariables[].IsInside=true`) | NuSMV local variable init/trust, fix fingerprint |
| `privacies[].name/privacy` | User/runtime override | Device-local variable sensitivity keyed by literal template variable name | Privacy modeling and fix fingerprint |

The public update boundary follows those ownership columns. Canvas geometry is a
complete `PUT /api/board/nodes/{id}/layout` subresource, and modeled device-local values
are a separate complete `PUT /api/board/nodes/{id}/runtime` subresource. Neither command
accepts `id`, `templateName`, or `label`, and each persists only its own database columns.
Renaming remains a dedicated command because it also rebuilds specification display
labels. There is no whole-device update command: a stale drag response cannot overwrite
a newer runtime selection, and a runtime save cannot move or retype a device. The update
response supplies before/after device snapshots, changed fields, and the current device
collection so clients can verify that preserved fields really stayed preserved.
The standard-scene parser, AI scene validator, REST DTO, and service-layer board
validator use the same coordinate and dimension domain. This is a closure requirement:
every valid saved canvas geometry remains valid after export and re-import, including
boundary values, while internal service calls cannot bypass the public range checks.
The same closure applies to scalar types and required semantics: portable string fields
are never synthesized from JSON numbers/booleans, and export fails on incomplete
environment/rule/specification values instead of filtering or defaulting them. This is
required for the portable file mapping to remain an inverse of the canvas semantic model rather
than a best-effort cleanup pass.

Board saves reject duplicate `id` values and duplicate NuSMV-normalized ids. A device
named `AC 1` and another named `ac_1` would both normalize to `ac_1`, so the second one
is invalid before it can reach simulation, verification, history, or fix. Board saves
also canonicalize case-insensitive template-name input to the stored template name, so
downstream code only sees the authoritative `manifest.Name`/DB `name` spelling. Runtime
overrides are also validated at the board-save boundary against the selected template:
mode-based devices must provide a legal current state, no-mode devices may use the
canvas placeholder `Working`, and unknown variables, out-of-range variable values, and
invalid trust/privacy values are rejected with `422`.

The portable `iot-verify.board-scene` v5 shape does not expose the no-mode canvas
placeholder. Stateful templates require `devices[].state` and may carry current-state
source/sensitivity overrides; stateless templates omit `state`, `currentStateTrust`, and
`currentStatePrivacy`. The frontend restores `Working` only in its canvas DTO and removes
it again on export, preserving the formal device semantics in both directions.
AI scenario REST responses use dedicated portable template/device/rule/specification
DTOs. They do not reuse persistence DTOs whose null database ids, template flags,
condition sides, or display labels would become observable JSON fields and break the
standard-scene inverse mapping.

Device instance runtime editors intentionally exclude environment variables. If a user
or AI/tool request tries to place an `IsInside=false` variable into
`DeviceNode.variables`/`privacies` or `DeviceVerificationDto.variables`/`privacies`, the
backend rejects it instead of silently treating it as an environment value.

They also exclude a device-local variable the device's **state** determines, for the same reason: it is
not the user's fact to state. A `WorkingState`'s `Dynamics` value constrains *being in* that state, and
the generator emits it as a branch of `next(<device>.<var>)` guarded by that state — so when **every**
working state declares a value for the variable, the `TRUE:` hold-current branch is unreachable and any
instance value survives only step 0 before the state overwrites it. Storing one is storing a derived
fact, which is how a car came to be saved in state `away` reporting `location = garage`: a step 0 the
model's own transition relation forbids, and the step every verification starts from. Such a variable is
initialised from the starting state (`DeviceManifestModes.localInitialValue`), and shown read-only in all
three runtime editors. A pair that disagrees is **canonicalised to the state's value** at both writer
boundaries rather than rejected: the value is derived, so correcting it is lossless, whereas refusing it
would let one device stored before this rule existed block every unrelated write to the board — the
failure shape `requireResolvedVariableSource` is deliberately scoped to avoid.

Two exclusions keep that narrow, and both matter for custom templates:

- **Partial coverage stays editable.** If some state declares no value for the variable, the hold-current
  branch is live there and the instance value genuinely carries it. The gates are per-state and stay
  silent wherever the state declares nothing.
- **A `Transitions` assignment on the same variable disqualifies it.** Transition branches are emitted
  *ahead* of the state branches in one `case`, and first match wins, so a firing transition overrides
  what the state declares. That variable is not a function of its state, and locking it would refuse a
  value the model legitimately produces.

Nine bundled variables are state-determined today (`Car.location`, `Door RFID.RFID`, the three
`contact` sensors, and the four job/operating states); none is partially covered, and the five numeric
locals are untouched because a numeric target declares a `ChangeRate`, which says nothing about the
current value. Spoofing a reading is still expressible — that is `FalsifiableWhenCompromised`, whose
attack branch precedes the state branches — so read-only here removes no modelling power.

## Environment Pool

Backend DTO: `BoardEnvironmentVariableDto`. Backend table:
`board_environment_variable`.

| Field | Authority | Purpose | Downstream |
| --- | --- | --- | --- |
| `name` | Current nodes and only their own template declarations | Shared scenario variable name. A device may read it only if its template declares that `InternalVariable` with `IsInside=false`; a device may affect it if its template lists it in `ImpactedVariables` and supplies the same-manifest domain | Rule/spec validation, NuSMV `a_<name>` |
| `value` | User/environment pool editor; defaulted by backend | Initial value for the shared environment variable. Missing values default to `Values[0]` for enums or `LowerBound` for bounded numeric variables | NuSMV `init(a_<name>)`, simulation, verification, fix fingerprint |
| `trust` | User/environment pool editor; template default fallback | Initial trust for the shared variable | NuSMV trust variables, specs/traces |
| `privacy` | User/environment pool editor; template default fallback | Initial privacy for the shared variable | Privacy modeling, specs/traces |

The frontend labels `value` as the **model initial value**, not the current physical value.
Each verification, simulation, or bounded-exploration path starts from it; later trace states
do not write back to the pool. The expanded pool also shows the template-owned evolution:
the required `NaturalChangeRate` for numeric shared values and each WorkingState device effect
**on that shared value**. The pool groups effects by variable, so it can only show the shared ones;
a `Dynamics` entry targeting a device-local variable — which is the only kind nine of the fifteen
templates declaring `Dynamics` have, a different nine from the state-determined variables counted
below — is shown per state in the device dialog's States table instead. Two
surfaces, one split by question: the pool answers "what moves this shared value", the dialog answers
"what does this state do".
What that rate means is owned by
[shared-value-semantics.md](shared-value-semantics.md#5-natural-evolution); its attribution to
MEDIC §3.1 by [theory-sources.md](theory-sources.md).

The environment pool is the only persisted source for environment values. `GET
/api/board/environment` reads current devices/templates, inserts missing required rows,
keeps existing values, and prunes variables no current device can read or affect. Public
edits use the [Environment Pool compare-and-set contract](../api/board.md#boardenvironmentvariabledto),
which prevents one browser tab from silently overwriting another tab's edit. Complete
scene replacement and targeted edits validate against the same current-node domain and
prune variables no device can read or affect. This write-on-read behavior is deliberate in the
single-board development model so a newly created device immediately exposes its
required scenario variables without relying on frontend-only inference.

Rules and specifications reference an environment variable with a device prefix, e.g.
`thermostat_1.temperature`. The prefix does not create a per-device environment value;
it identifies `thermostat_1` as a template that declares `temperature` readable in the
model. A device that only lists
`temperature` in `ImpactedVariables` can change `a_temperature` through its rate/value
dynamics, but it cannot be used as a rule/spec source for `temperature`. The actual
value, trust, and privacy are read from the board environment pool. NuSMV generation
uses that pool to initialize the single shared `a_temperature` variable; only devices
that declare the shared value as readable model state receive a
`device.temperature := a_temperature` mirror. This is model wiring, not access control.

Unused templates in the account never contribute a domain or default to the current
board. For one active shared name, every participating template must agree on literal
casing, numeric/enum domain (including enum order because its first item is the default),
`NaturalChangeRate`, default trust, and default privacy. Board writes and direct model
generation reject conflicts rather than selecting whichever device happens to appear first.
The Environment Pool groups names case-insensitively, lists every active source and mismatch,
and disables value/label editing when no single shared definition exists.

Environment names are literal user/template names. The `a_` marker is added only when
generating SMV identifiers. If a real template variable is named `a_temperature`, board
JSON still uses `a_temperature`, and the generated SMV environment identifier is
`a_a_temperature`.

NuSMV instance names are normalized by the backend `DeviceNameNormalizer`; the frontend
`modelRequest.ts` mirrors the same function so requests built in the browser and requests
built from saved board data use identical ids. The reserved-word list includes NuSMV
module sections and property keywords such as `FROZENVAR`, `INVAR`, `CTLSPEC`, and
`LTLSPEC`; reserved ids are escaped with a leading `_`.

The main SMV module has one namespace for device instances, generated environment
variables (`a_<name>`), the internal compromised-point counter, automation-link choices,
rule probes, and
auto-fix parameterization variables.
Generation rejects concrete collisions in that namespace. The automatic-fix prefixes
`param_`, `lambda_`, `condition_value_` and `iot_verify_guard_probe_` **are** part of the user
contract: a device id may not *start* with any of them, refused at board admission and again
at request time. A
device id may still *contain* them (`my_param_x` is fine), and `a_` is not prefix-reserved —
a collision with a generated `a_<name>` is judged on the concrete name, not the prefix. Board writes
preflight collisions derivable from stored environment domains, rules, attack analysis,
and existing fixable conditions before persistence; run-specific generation repeats the
check. Ordinary creation uses opaque `device_*` references, so this diagnostic is mainly
relevant to technical scene JSON. Its error points to `devices[].id`; the display label
does not need to change.

## Rule

Frontend authoring type: `RuleForm`. Backend persistence/model DTO: `RuleDto`.

| Field | Authority | Purpose | Downstream |
| --- | --- | --- | --- |
| `RuleForm.id` | Frontend temporary or DB id string | Stable UI key; numeric ids round-trip to backend | Update/delete rules |
| `RuleForm.name` / `RuleDto.ruleString` | User/AI | Human-readable rule text | Inspector, history, AI summaries |
| `sources[].fromId` / `conditions[].deviceName` | Board storage: raw `DeviceNode.id`; model boundary: normalized `varName` | Source device reference | Edge derivation, NuSMV rule guard |
| `sources[].fromApi` / `conditions[].attribute` | Template `Signal=true` API, variable, mode, or `state` | Source signal/key | NuSMV rule guard |
| `sources[].itemType` / `conditions[].targetType` | User/AI | `api`, `variable`, `mode`, or `state` | Determines guard semantics; no inference from attribute |
| `sources[].relation` / `conditions[].relation` | User/AI | Comparator for value-based conditions | Required for `variable/mode/state`; forbidden for rule `api` signals. All value conditions support `=`, `!=`, `in`, `not in`; numeric variables additionally support ordering |
| `sources[].value` / `conditions[].value` | User/AI | Expected value for value-based conditions | Required for `variable/mode/state`; forbidden for rule `api` signals |
| `toId` / `command.deviceName` | Board storage: raw `DeviceNode.id`; model boundary: normalized `varName` | Target device reference | NuSMV command/action |
| `toApi` / `command.action` | Template API name | Target action | NuSMV command/action |
| `contentDevice` | Board storage: raw `DeviceNode.id`; model boundary: normalized `varName` | Optional privacy content source device | NuSMV privacy action context |
| `content` | Template content name | Optional privacy content key | NuSMV privacy action context |
| `createdAt` | DB | Server-managed creation time | Sorting/history |

Rule list order and condition list order are part of the board semantic snapshot. Rule
order is user-controlled execution precedence: the first matching rule wins when several
rules command the same target mode in one step. The backend persists that order in an
internal `execution_order` column, exposes an atomic complete-id reorder command, and
always returns rules in effective order. NuSMV state changes, execution probes, and
trust/privacy propagation use the same selected branch. The generator also walks rule
conditions in list order when building guards and internal parameterized fix locators.
Fault localization and repair application retain zero-based positions as trace-snapshot
diagnostics. Trace REST DTOs expose the frozen `ruleIndex` needed to identify execution
evidence, but repair suggestions do not ask clients to apply raw rule or condition
positions: they expose readable descriptions and the opaque `ParameterTarget.targetId`.
Portable scene JSON therefore omits backend rule row ids but preserves the `rules[]` and
`sources[]` array order during import/export.

Backend save APIs validate that board rules reference existing `DeviceNode.id` and
the current template manifest semantics: source keys must exist, command actions must
exist, content keys must exist on `contentDevice`, and state/mode values must be legal.
Rule `targetType=api` is an event-signal condition and may reference only a template
API where `Signal=true`; it carries no relation/value. Verification/simulation
validate that model rules reference existing `varName` and then re-check the same
template semantics against the current user's manifests before NuSMV generation.
Rule `targetType=state` and `targetType=mode` are enum conditions. Enum internal
variables follow the same enum-operator rule; numeric internal variables may also use
ordering comparisons.
Rule conditions are current-step guards: IF reads the source device state, variable,
mode, or signal visible in the current trace state, while the command writes the target
device's next state. Specs, traces, and fix localization consume the same canonical
current-step condition semantics.

Relations are canonicalized at every backend boundary: `EQ/==` -> `=`, `NEQ` -> `!=`,
`GT` -> `>`, `GTE` -> `>=`, `LT` -> `<`, `LTE` -> `<=`, `IN` -> `in`, and
`NOT_IN`/`NOT IN` -> `not in`.
For `in`/`not in`, enum-like mode/variable/API/trust/privacy values may be separated
with comma, semicolon, or pipe. Multi-mode `state` values use semicolon inside a tuple
(`home;idle`), so state sets are separated only by comma or pipe
(`home;idle,sleep;idle`). This same rule is used by the manual dialogs, AI
recommendation validators, NuSMV generation, traces, and fix localization.

## Specification

Frontend type: `Specification`. Backend DTO: `SpecificationDto`.

| Field | Authority | Purpose | Downstream |
| --- | --- | --- | --- |
| `id` | Frontend/AI UUID, scoped to the authenticated user | Stable spec identity within one account | Save/delete, trace `violatedSpecId`, fix |
| `templateId` | Spec template config | P1-P7 property shape | NuSMV CTL/LTL generation |
| `templateLabel` | Backend/UI derived from `templateId` | Localized/display label cache | Response/inspector only; ignored as write authority |
| `aConditions` | User/AI | A-side condition list | Spec formula generation |
| `ifConditions` | User/AI | IF-side condition list | Implication specs |
| `thenConditions` | User/AI | THEN-side condition list | Implication specs |
| `formula` | Backend/frontend derived | User-domain formula preview cache | Response/display only; never parsed or trusted on write |
| `devices[].deviceId` | Backend derived from structured conditions | Referenced device summary | Response/spec UI only |
| `devices[].deviceLabel` | Backend derived from current node label | Readability | Response/UI only |
| `devices[].selectedApis` | Backend derived from API conditions | Referenced action-event summary | Response/UI only; API conditions remain authoritative |

`SpecConditionDto` fields:

| Field | Authority | Purpose | Downstream |
| --- | --- | --- | --- |
| `id` | Frontend/AI | Condition UI key | UI only |
| `side` | Containing collection | `a`, `if`, or `then` | Backend mapper normalizes side |
| `deviceId` | Board storage: raw `DeviceNode.id`; model boundary: normalized `varName` | Device reference | NuSMV spec builder |
| `deviceLabel` | Backend derived from the current node label | Readable UI | Response/display only; never identity |
| `targetType` | User/AI | `state`, `mode`, `variable`, `api`, `trust`, `privacy` | NuSMV expression semantics |
| `key` | Template key or `state` | Attribute being checked | NuSMV expression; API keys must be `Signal=true`; trust/privacy keys must be a variable or state-property key |
| `propertyScope` | User/AI for trust/privacy only | Distinguishes an active state label from a literal variable label without generated `Mode_state` keys | Trust/privacy expression resolution |
| `relation` | User/AI; derived `=` for API helpers when omitted | Comparator | NuSMV relation |
| `value` | User/AI; derived `TRUE` for API helpers when omitted | Expected value | NuSMV expression |

Specification template shape is strict. Templates `1`, `2`, `3`, and `7` use
`aConditions` only. Templates `4`, `5`, and `6` use `ifConditions` and
`thenConditions` only. Hidden or mixed forms are rejected at board-save, model-request,
AI-tool, and NuSMV-generation boundaries.

Specification list order and condition list order are preserved by portable scene JSON.
Most generated formulas treat conditions as conjunctions, but the authored order remains
part of the canvas snapshot for previews, review, and stable user-facing round trips.
Persistence uses the composite primary key `(id, user_id)`, so importing the same portable
scene into different accounts cannot overwrite or transfer another user's specifications.
Board storage writes specification array positions to the internal
`specification.list_order` column and orders every read by it (then by id only as a
deterministic tie-breaker). This persistence detail is not exported.
Portable scene v4 stores only `templateId` and structured conditions for each
specification. It excludes `templateLabel`, `formula`, `devices[]`, condition ids/sides,
and condition display labels, so a display cache cannot contradict the semantic model.

`formula` is not an authored source of truth. Targeted and batch specification writes
send only `id`, `templateId`, and the structured condition semantics. Board storage
rebuilds `templateLabel`, condition labels, `devices[]`, and a user-domain formula
preview from the accepted current board; AI views and the frontend likewise rebuild
their previews instead of displaying a persisted formula verbatim. Backend validation
and NuSMV generation ignore every preview and rebuild the actual checked expression from
`templateId` plus the structured condition arrays. Each emitted `SpecResultDto` captures
the submitted `templateId`, user-facing template label, a newly rebuilt user-domain
`formulaPreview`, `formulaKind`, and the actual technical `expression`. Result UIs do not
join `specId` back to the mutable current Board to explain an earlier run.

## Edge

Frontend type: `DeviceEdge`.

| Field | Authority | Purpose | Downstream |
| --- | --- | --- | --- |
| `id` | Derived from rule id/source index | Canvas key | Canvas/minimap only |
| `from` / `to` | Derived from rule source/command ids | Edge endpoints | Canvas/minimap only |
| `fromLabel` / `toLabel` | Derived from node labels | Readable line metadata | UI only |
| `fromPos` / `toPos` | Derived from node geometry | SVG line endpoints | Canvas only |
| `fromApi` / `toApi` | Derived from rule source/command | Tooltip/readability | UI only |
| `itemType` / `relation` / `value` | Derived from rule condition | Tooltip/readability | UI only |

Edges are never saved. `SystemInspector` must read `rules`, not infer rules from
`edges`. The Board UI regenerates this display list from the current `nodes` and
`rules` together so initial loading, rule edits, node movement, and deletion cannot
leave a stale topology. Edge labels are an inspection affordance only: they are hidden
by default and shown on hover/focus, while animation highlighting is driven by the
selected trace state and the original rule condition semantics. For playback, API rule
sources use the same signal name generated by NuSMV (`apiName_a`); environment-variable
rule sources read `TraceStateDto.envVariables` by the literal user/template name
(`temperature`, `a_temperature`, etc.) when the mirrored device variable is absent from
a sparse trace.

## Model Request Boundary

Frontend builder: `frontend/src/utils/modelRequest.ts`. Backend converter:
`BoardDataConverter`.

**A run request carries run parameters only; the scene is read server-side from the caller's own
persisted board.** `attackScenario`, `enablePrivacy`, and (for simulation) `steps` are the whole
client contract. `devices`, `rules`, `specs`, `environmentVariables`, and `playbackNodes` are
**rejected** by `ModelRequestParser` if a client sends them, because a run must describe the board
the user saved: while they were client input, an account whose board held no devices could post a
fabricated two-device scene and have the resulting `VIOLATED` verdict persisted into its own run
history. Verification and simulation now call `BoardDataConverter.getModelInputSnapshot(userId)` —
the same read fuzz and the AI tools already used — before freezing the request snapshot, so the
persisted evidence deep-copies the board scene rather than aliasing live objects.

The table below therefore describes the **frozen snapshot** the service builds from that board read
and persists as run evidence, not fields a client may supply.

| Field | Authority | Purpose | Downstream |
| --- | --- | --- | --- |
| `devices[].varName` | Normalized `DeviceNode.id` | NuSMV instance variable name | Generator map key, traces, fix |
| `devices[].templateName` | Node `templateName` | Manifest lookup | DeviceSmvDataFactory |
| `devices[].state` | Node state, only when the template has `Modes` | Initial state for modeled state machines | NuSMV init; omitted for no-mode sensors/devices |
| `devices[].currentStateTrust` | Explicit node trust override, only when the template has `Modes` | Overrides template trust for the current state; omitted means use `WorkingStates.Trust` | Privacy/trust model; omitted for no-mode sensors/devices |
| `devices[].currentStatePrivacy` | Explicit node sensitivity override, only when the template has `Modes` | Overrides template privacy for the current state; omitted means use `WorkingStates.Privacy` | Privacy model; omitted for no-mode sensors/devices |
| `devices[].variables` | Explicit device-local variable overrides; omitted fields use generator-effective manifest defaults. Environment variables are invalid here | Local variable init/trust | NuSMV device variables |
| `devices[].privacies` | Explicit device-local/state privacy overrides; environment-variable privacy is invalid here | Privacy init | Privacy model |
| `environmentVariables[]` | Board environment pool | Shared environment values/trust/privacy, merged with required defaults before generation | NuSMV `a_<name>`, traces, fix fingerprint |
| `rules` | Normalized board rules | Automation behavior | NuSMV main module |
| `specs` | Normalized board specs | Properties to check | NuSMV specs |
| `steps` | User | Simulation length | Simulation executor |
| `attackScenario` | User; `NONE`, exact points, or verification-only exhaustive budget | Per-run attack selection. Exact points identify normalized device ids or persisted rule ids; exhaustive mode uses a validated `1..50` upper bound. Persistent trust labels never select points | Attack flag initialization, optional count invariant, and result context |
| `enablePrivacy` | User plus specification requirement | Privacy dimension toggle; any privacy specification forces the effective value on and the result reports that effective value | Privacy variables/specs |

Model requests reject non-normalized `varName`, unknown rule/spec references,
environment values placed on device instances, and template-semantic mismatches before
NuSMV generation. This applies to synchronous and asynchronous
verification/simulation, including AI-tool calls that invoke those services.
Variable conditions must respect template value domains: enum variables accept only
declared `Values` (spaces are normalized to the NuSMV enum token form), and bounded
numeric variables accept only integers within `LowerBound..UpperBound`.
Spec `targetType=api` checks a boolean API signal variable. It may use `=`, `!=`,
`IN`, or `NOT_IN` with `TRUE`/`FALSE` values and may reference only `Signal=true`
APIs. The signal is true only for an actual complete `StartState -> EndState` change in
that step; remaining in `EndState` does not retrigger it. UI and AI authoring helpers may let users choose only the API signal; the model
request boundary sends the explicit `= TRUE` condition. Spec `targetType=trust`/`privacy`
uses literal variable/state/mode choices from the structured condition. Generated
`mode_state`, `trust_*`, and `privacy_*` names are not accepted as user aliases. Content privacy variables are
generated for privacy propagation, but the current specification builder does not
resolve content names as `targetType=privacy` keys.

## Trace

Backend DTOs: `TraceDto`, `TraceStateDto`, `TraceDeviceDto`,
`TraceVariableDto`, `TraceTrustPrivacyDto`. Frontend types: `Trace*` and
`Simulation*`.

| Field | Authority | Purpose | Downstream |
| --- | --- | --- | --- |
| `TraceDto.id` | DB | Trace identity | History/fix |
| `userId` | DB/security | Ownership | Backend authorization |
| `verificationTaskId` | Completed verification run | Link each counterexample to the one sync/async result that produced it | Run history, polling trace lookup, atomic run deletion |
| `violatedSpecId` | NuSMV result/spec mapper | Failed spec identity | History/fix |
| `violatedSpecJson` | Internal persisted spec snapshot | Fix context; not serialized | Trace restore/fix |
| `violatedSpec` | Parsed structured spec snapshot | Human-readable historical context | Trace details |
| `checkedExpression` | Emitted-spec mapping | Exact CTL/LTL expression checked, distinct from the preview/cache | Trace details |
| `states` | NuSMV parser | Counterexample/simulation states | Visualization/fix |
| `requestJson` | Verification request snapshot | Internal fix context | Not serialized to API |
| `isAttack` / `attackBudget` / `enablePrivacy` | Derived from request snapshot | Historical run context | History UI |
| `modelSemantics` | Persisted from the immutable request snapshot | Machine-readable attack selection policy, exact selected points when applicable, trust/privacy assumptions, and distinct device, falsifiable-reading subset, link, and total countable attack points for that run. `attackEffects` lists only mechanisms present in the scene. Historical clients must not recompute these values from the current board | Result interpretation guard |
| `createdAt` | DB | Trace creation time | History sorting |
| `TraceStateDto.stateIndex` | NuSMV parser | Step index | Timeline |
| `TraceStateDto.devices` | NuSMV parser | Per-device state in this step | Visualization |
| `TraceStateDto.triggeredRules` | Internal rule probes resolved by the parser | Frozen `{ ruleIndex, ruleId, ruleLabel }` snapshots for rules whose modeled transition drove this state. The required zero-based index identifies exactly one rule in the run request even when ids/labels collide; it is not current-Board identity | Visualization/fault localization |
| `TraceStateDto.compromisedAutomationLinks` | Internal fixed link choices resolved by the parser | The same frozen rule identity for logical automation delivery links selected as compromised | Trace explanation and broken-link canvas emphasis |
| `trustPrivacies` | NuSMV parser | State-level trust/privacy | Visualization |
| `envVariables` | NuSMV parser | Board environment variables; generated NuSMV `a_<name>` identifiers are mapped back to literal board names before serialization | Visualization |
| `globalVariables` | NuSMV parser | Runtime values such as user-facing `compromisedPointCount`; generated names are translated and kept separate from the environment namespace | Visualization/run context |
| `TraceDeviceDto.deviceId` | Model `varName` | Device identity at trace boundary | Visualization/fix |
| `TraceDeviceDto.deviceLabel` | Parser/display snapshot | Readability | UI only |
| `templateName` | Request/model snapshot | Manifest context | UI/fix |
| `state` / `mode` | NuSMV parser | Current state/mode | Visualization |
| `variables` | NuSMV parser | Per-variable values | Visualization/fix |
| `trustPrivacy` / `privacies` | NuSMV parser | Structured `{ propertyScope, mode?, name, trust?/privacy? }` labels; state labels preserve literal mode and state separately | Visualization without generated `Mode_state` names |

## Task

Backend DTOs: `VerificationTaskDto`, `VerificationTaskSummaryDto`,
`SimulationTaskDto`, `SimulationTaskSummaryDto`, `FuzzTaskDto`, and
`FuzzTaskSummaryDto`.

| Field | Authority | Purpose | Downstream |
| --- | --- | --- | --- |
| `id` | DB | Task identity | Polling/cancel/history |
| `status` | Backend task lifecycle | `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED` | UI state |
| `createdAt` / `startedAt` / `completedAt` | Backend lifecycle | Timing | History |
| `processingTimeMs` | Backend lifecycle | Duration | History |
| `progress` | Backend lifecycle | 0-100 progress | Polling UI |
| `errorMessage` | Backend lifecycle | Failure/cancel reason | UI feedback |
| `outcome` | Verification or fuzz result | Verification: `SATISFIED`, `VIOLATED`, `INCONCLUSIVE`; fuzz: `FOUND_VIOLATION`, `BUDGET_EXHAUSTED`, `INCONCLUSIVE` | Result/history presentation |
| `modelComplete` | Generator/result reconciliation | Whether every requested rule/spec was modeled and parsed reliably | Verification history diagnostics |
| `violatedSpecCount` | Verification result | Explicitly violated spec count | Verification history |
| `disabledRuleCount` / `skippedSpecCount` | Generator/result | Model generation adjustments | UI diagnostics |
| `generationIssues` | Generator/result | User-readable label and reason for each omitted rule/spec | Result and history UI |
| `specResults` | NuSMV executor plus immutable submitted spec/device/template context | Per-spec `SATISFIED` / `VIOLATED` / `INCONCLUSIVE` outcome, user-domain formula preview/type/label, and actual technical expression | Verification result UI without current-Board reinterpretation |
| `checkLogs` | Service/generator/executor | Diagnostic log lines | Result/history |
| `nusmvOutput` | Executor, then service retention cap | Capped NuSMV diagnostic output; see the [verification API contract](../api/verification.md) | Debug/result details |
| `requestedSteps` / `steps` | Simulation service | Requested/actual simulation steps | Simulation history |
| `simulationTraceId` | Simulation trace persistence | Link task to saved simulation trace | History lookup |
| `maxIterations` / `pathLength` / `populationSize` | Counterexample-exploration request | Bounded search configuration | Fuzz worker/result history |
| `seed` | User or absent | Requested deterministic seed | Fuzz worker; absent values receive a generated effective seed |
| `runId` | Completed fuzz task | Same ID as the completed task | Fuzz result lookup |

`status=COMPLETED` is only a lifecycle fact. UI color and wording must not reuse it as a
verification verdict: `outcome` is shown independently, and `modelComplete` independently
states whether the requested model was fully represented.

Cancellation is authoritative at the task row and mirrored by an in-memory marker on the
running backend instance. Task terminal states are immutable. The legal async transitions
are:

- `PENDING -> RUNNING` by the worker atomic start.
- `PENDING | RUNNING -> CANCELLED` by explicit cancellation.
- `PENDING | RUNNING -> FAILED` by validation, queue, generation, execution, or parsing
  failure.
- `RUNNING -> COMPLETED` only after the result is ready to commit.

A cancelled async task must not complete as success after cancellation has won the status
transition. Verification/simulation/fuzz completion also checks the in-memory cancellation
marker inside the same transaction that writes traces or findings, so a cancellation request that
arrives before completion persistence rolls back the history result instead of leaving a
trace/finding for a cancelled task.

## Counterexample exploration run and finding

Backend DTOs: `FuzzRunDto`, `FuzzRunSummaryDto`, `FuzzEligibilityDto`,
`FuzzFindingDto`, and `FuzzFindingSummaryDto`. The complete internal model input remains
server-owned persisted JSON and is never exposed through the API.

| Field | Authority | Purpose | Downstream |
| --- | --- | --- | --- |
| `FuzzRun.id` | Completed `fuzz_task` row | Run identity; identical to task ID | History/detail/delete |
| `outcome` | Fuzz engine + service mapping | `FOUND_VIOLATION`, `BUDGET_EXHAUSTED`, or `INCONCLUSIVE`; never a proof of satisfaction | Neutral/red/amber result presentation |
| `effectiveSeed` | Fuzz engine | Exact reproducibility seed | Retry/research reporting |
| `iterations` / `generatedPaths` / `elapsedMs` | Fuzz engine | Bounded execution statistics; completed data must have `iterations <= maxIterations` and either `0/0` or `iterations <= generatedPaths <= iterations * populationSize` | Result explanation only; not coverage |
| `modelSnapshot` | Board capture boundary | Frozen run-scope counts/time plus the required canonical semantic fingerprint | Historical scope explanation and exact exploration-history drift checks |
| `eligibility` | Fuzz model/spec classifier | Eligible IDs plus itemized unsupported/invalid specs | Fail-closed user explanation |
| `limitations` | Fuzz engine | Stable codes for the run's semantic boundary | Localized result detail |
| `FuzzRunSummary.dataAvailable` / `FuzzRunSummary.unavailableReasonCode` | Fuzz mapper | Fail-closed history-row decode status; these fields do not exist on run detail or finding summaries | Disable open/replay while preserving delete |
| `FuzzFinding.id` | DB | Candidate evidence identity | Lazy detail/replay |
| `fuzzTaskId` | Owning completed run | Finding ownership and grouping | Run history |
| `violatedSpecId` / `violatedSpec` | Captured structured specification | Historical target identity and readable context | Replay/formal-verification handoff |
| `firstViolationStep` | Finite monitor | First zero-based violating state | Timeline focus |
| `states` | Deterministic finite simulation | Candidate state prefix | Read-only playback; never fix input |
| `inputEvents` | Genome, paper initializer, or paper seed | Non-decreasing-step evidence with same-step precedence `RANDOM_INITIAL_STATE -> SEED_EVENT -> MODEL_CHOICE`; random initialization is restricted to step `0` | Reproduction/diagnostics without conflating initialization and overrides |
| `seed` | Owning run | Finding reproducibility | UI/detail |

Fuzz findings are deliberately separate from NuSMV `trace` rows. They do not carry a
formal `checkedExpression`, cannot be submitted to fault localization or fix endpoints,
and must not be rebuilt from the mutable current Board. Run detail loads and validates
the complete states for every finding. Replay independently loads both the selected finding
detail and its owning full run detail; it never treats lightweight history metadata as
snapshot or replay authority. The UI offers formal verification as the next action.

## Fix

Backend DTOs: `FaultRuleDto`, `FixSuggestionDto`, `ParameterAdjustment`,
`ConditionAdjustment`. Frontend type: `Fix*`.

| Field | Authority | Purpose | Downstream |
| --- | --- | --- | --- |
| `traceId` | Trace history | Fix target | Fix endpoints |
| `violatedSpecId` | Trace | Failed property target | Fix context |
| internal fault-rule position / id | Fault localizer / rule snapshot | Trace-snapshot locator | Server-side fix strategies and drift checks only; not serialized |
| `faultRules[].ruleString` | Rule snapshot | Readability | UI |
| `faultRules[].transitionNumber` | Trace/localizer | One-based transition where the rule fired | UI/fix scoring |
| `faultRules[].targetDeviceLabel` / `targetActionLabel` | Device/rule snapshot | Readable affected target | UI |
| `faultRules[].reasonCode` | Fault localizer | Stable reason category | Localized UI explanation |
| `parameterAdjustments[].targetId` | Parameter strategy | Opaque selector for preferred ranges within the same trace/fix context | Fix request DTOs |
| `parameterAdjustments[]` | Parameter strategy | Numeric threshold edits plus readable description | UI; server recompute/apply |
| `conditionAdjustments[].targetType` | Condition strategy | Rule condition semantics | UI; server recompute/apply |
| `removedRuleDescriptions[]` | Remove strategy | Readable rules that would be permanently deleted | UI; server retains positions internally |
| `preexistingViolations[]` | Forward verification vs. the original rules on the same model | Specifications already violated before the fix and left violated by it | UI note beside the suggestion; signed with it |
| `preferredRangeSelections` | User selection from parameter-adjustment targets | Numeric tuning constraints | Parameter strategy |

Fix apply accepts the exact suggestion returned by `/fix` together with its signed token (and the
preferred ranges it was generated under); the token covers the visible suggestion and every hidden
operation locator, so the client cannot alter what is written and never sees an internal locator.
Contract and drift guards: [../api/verification.md](../api/verification.md).

Persisted board rule ids cross the verification/simulation model boundary as correlation
identity only: `modelRequest.ts` includes a positive database id so parsed
`triggeredRules[].ruleId` and `compromisedAutomationLinks[].ruleId` can highlight the
corresponding current canvas rule. Every non-null rule id in one model request must be
positive and unique; repeated `null` values remain valid for unsaved rules, whose frozen
identity is their run-local `ruleIndex`. The id has no NuSMV behavioral meaning. Portable
scene files and unsaved `rule_<timestamp>` drafts omit it; after scene import, the atomic
batch response supplies newly persisted ids before any model run.
