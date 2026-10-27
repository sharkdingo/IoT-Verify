# Counterexample Exploration API

Field-level contract for the bounded, HAFuzz-inspired counterexample exploration
module. The endpoint index lives in [rest-endpoints.md](rest-endpoints.md); global
authentication, `Result<T>`, and error conventions live in
[overview.md](overview.md).

Verified against code on 2026-10-27. Source: `controller/FuzzController.java`,
`service/impl/FuzzServiceImpl.java`, and `dto/fuzz/`.

---

## Product boundary

This module is a background **candidate counterexample search**, not a model checker.
It explores finite paths from a frozen Board snapshot and may return paths that violate
supported specification templates. A finding is useful investigation evidence, but it
is not a NuSMV counterexample. `BUDGET_EXHAUSTED` means only that this run found no
violation within its finite budget; it never proves the Board safe or a specification
satisfied. Run formal verification before using the automatic-fix workflow.

**There is deliberately no SMV-model download here.** Verification and simulation each offer one
(`docs/api/verification.md`), and their absence on this path is not an oversight to be corrected for
symmetry: `FuzzEngine` explores the Board snapshot directly and never calls `SmvGenerator` or NuSMV,
so no SMV model exists for a fuzz run to serve. Adding one would mean generating a model the run
never checked — which would misrepresent candidate evidence as a formal artifact.

The implementation is a clean-room Java 17 integration inspired by HAFuzz's
seed -> trace -> monitor -> distance-guided mutation loop. `BOARD_SNAPSHOT` keeps the
product-oriented Board explorer as the default, while `PAPER_COMPATIBLE` selects a
closer, explicitly bounded subset of the paper's event and monitor algorithm. Neither
mode changes the proof boundary above. The inspected external artifact is not a runtime
dependency and its source is not copied into this repository; its top-level code has no
confirmed redistribution license.

## Submit and task lifecycle

### `GET /api/fuzz/model-fingerprint`

This read-only endpoint returns the current authenticated user's canonical bounded-search
model fingerprint as a 64-character lowercase SHA-256 string in `Result<String>.data`.
Its identity equals the verification/exploration value in the shared
[current model fingerprint contract](board.md#current-model-fingerprints). That contract owns
the included semantic inputs and excluded presentation fields.

Completed runs store this fingerprint in `modelSnapshot.modelFingerprint`. The frontend
uses exact fingerprint equality to decide whether a historical run still matches the
current Board, including edits that leave all public item counts unchanged. The field is
required on every stored task and run; missing or malformed fingerprints make the row
unavailable instead of weakening the comparison to item counts. When full run detail is
read, the backend decodes the strict versioned frozen-input envelope, recomputes this same
canonical fingerprint, and requires exact equality with the stored public snapshot; a
same-count content change is persisted-data corruption, not a compatible history shape.

### `POST /api/fuzz/workload/preview`

This read-only preflight calculates the exact workload guard for the current server
Board without creating a task or writing history. Its request contains the three bounded
integer budget fields `maxIterations`, `pathLength`, and `populationSize`, with the same
ranges and defaults as `FuzzRequestDto`, plus `explorationMode` (default `BOARD_SNAPSHOT`).
The mode is required because model complexity depends on it; a preview computed for the other mode
would report an estimate submission disagrees with.

The response is `FuzzWorkloadPreviewDto`:

| Field | Type | Meaning |
| :--- | :--- | :--- |
| `maxIterations` / `pathLength` / `populationSize` | integer | Echoed validated budget, allowing clients to reject a stale response |
| `explorationMode` | enum | Echoed mode the complexity was computed for |
| `modelComplexityUnits` | non-negative integer | Conservative operational complexity calculated from the current server Board using the same method and mode as submission; zero is valid for a structurally empty Board, and populated Boards include bounded structure counts and key cross-product loop costs |
| `estimatedWorkload` | integer | Exact product of the three budget fields and `max(1, modelComplexityUnits)` |
| `workloadLimit` | integer | Current server ceiling, `30,000,000` |
| `accepted` | boolean | Whether `estimatedWorkload <= workloadLimit` |
| `maxAcceptedIterations` | integer | Largest `maxIterations` this Board admits at the previewed path length and population size, clamped to `0..5000`. `0` means even one iteration exceeds the limit, so the path length or population size must come down instead — a remedy naming only the iteration budget cannot express |

The client must invalidate this response when the Board or any budget field changes and
must not submit while a replacement preview is pending or failed. Submission still
captures a fresh Board snapshot and repeats the calculation, so a concurrent edit can
be rejected rather than relying on stale preflight state. Target selection is omitted
intentionally because every frozen specification contributes to the structural guard.
The captured Board must also fit the same 8 MiB UTF-8 snapshot ceiling enforced at
submission, so an accepted workload cannot describe a Board that the task cannot freeze.

### `POST /api/fuzz/paper-domain/preview`

This read-only preflight exposes the paper-compatible input domain generated by the
same `FuzzModel` used during execution. It does not create a task, select a seed,
materialize a candidate, or write history. The request is
`FuzzPaperDomainPreviewRequestDto` with an optional integer `pathLength` in `1..50`;
when omitted, it defaults to `20`, matching `FuzzRequestDto`.
The captured Board must also fit the same 8 MiB UTF-8 snapshot ceiling enforced at
submission, so a domain cannot be previewed for a Board that cannot be frozen safely.

The response is `FuzzPaperDomainPreviewDto`:

| Field | Type | Meaning |
| :--- | :--- | :--- |
| `pathLength` | integer | Validated bounded trace length; Event positions are one-based from `1` through this value |
| `initializationPolicy` | string | `RANDOM_LEGAL_PER_SEED`: a seed's nonce deterministically materializes its legal initial state; mutated offspring inherit the parent nonce, while fresh-random seeds receive a new nonce |
| `modelFingerprint` | string | Lowercase SHA-256 fingerprint of the Board semantics and requested path length used to build this preview; required unchanged when starting a paper-compatible task |
| `paperSemanticsCodes` | `string[]` | The same eight paper-mode capability/boundary codes returned by an executed paper-compatible run |
| `deviceDomains` | array | `{ targetId, label, property, legalValues, lowerBound, upperBound }`; device states use `legalValues`, leave both bounds null, and permit Events only at position `1` |
| `localVariableDomains` | array | The same shape; enum domains use `legalValues`, numeric domains use inclusive `lowerBound`/`upperBound`, and these values are sampled only for random initial states, not seed Events |
| `environmentDomains` | array | `{ name, targetId, property, eventValueKind, initialValues, eventValues, initialLowerBound, initialUpperBound, eventRateLowerBound, eventRateUpperBound }` |

`eventValueKind` is `CHANGE_RATE` for a declared numeric rate domain or
`DIRECT_VALUE_EXTENSION` for the documented discrete-value compatibility extension.
Each enum domain uses its value array and null bounds; each numeric domain uses inclusive
bounds and an empty value array. The preview therefore represents every legal Java
integer without expanding large ranges. Concrete numeric rate Events use the lossless
`rate:<integer>` encoding in seed and replay evidence. Enum-backed rate domains are
canonicalized to the same representation before seed generation, so a rate cannot be
misread as a direct environment value. Ordinary UI may display the integer as a rate
while preserving that transport prefix in stored evidence.
The declared domain remains authoritative: a `rate:*` token is interpreted as a delta
only for a numeric environment with a rate domain. The same token in a discrete direct-
value domain remains an ordinary value.
The endpoint intentionally cannot return the "actual random initial state" before a
run: there is no single run-level initial state. Initial population members and the
`5%` fresh-random branch receive new initialization nonces. The `95%` mutation branch
clones the selected parent nonce and changes only selected Events, matching mutation of
the paper seed instead of silently randomizing the rest of the candidate.
A client must present this response as a legal-domain preview, then use persisted
finding input events to explain the concrete choices after a candidate is found.

The paper-domain identity adds the requested `pathLength` to the shared
[canonical model input](board.md#current-model-fingerprints). It is distinct from the stored
run's Board identity, which does not include search configuration.
The submit endpoint recomputes this value from a fresh server snapshot before writing a
task; a mismatch fails validation and requires the client to refresh the preview.

### `POST /api/fuzz/async`

The server captures the current user's devices, effective environment pool, rules,
specifications, and referenced template manifests before accepting the task. Later Board
or template edits cannot rewrite the task's model input.

Request body: `FuzzRequestDto`.

| Field | Type | Required | Default | Validation |
| :--- | :--- | :--- | :--- | :--- |
| `explorationMode` | enum | no | `BOARD_SNAPSHOT` | `BOARD_SNAPSHOT` or `PAPER_COMPATIBLE`; the exact uppercase enum value is persisted with the task and completed run |
| `paperDomainFingerprint` | string | paper mode only | none | Required for `PAPER_COMPATIBLE`: the 64-character lowercase SHA-256 value returned by the current paper-domain preview. It is rejected for `BOARD_SNAPSHOT` and is not persisted as run configuration |
| `targetSpecIds` | `string[]` | no | `[]` | Empty means all captured specifications; otherwise 1-100 unique, non-blank IDs of at most 100 characters, all present in the snapshot |
| `maxIterations` | integer | no | `200` | `1..5000`. With the default path length and population size this is 40,000 step-slots, which every scene in `docs/examples/` admits in both modes with headroom |
| `pathLength` | integer | no | `20` | `1..50` captured states per generated path |
| `populationSize` | integer | no | `10` | `1..50` paths evaluated per full iteration |
| `seed` | integer | no | generated | `0..9007199254740991`; this JavaScript-safe range preserves exact browser round trips |

The frozen Board must contain at least one specification. An empty target list selects
all captured specifications only after this precondition is satisfied.

The server derives `modelComplexityUnits` from the same frozen Board snapshot used by
the worker. Its additive structure count includes devices, environment values, rules, every captured
specification (including unselected specifications), every rule/specification condition, device runtime values, and
each referenced manifest's modes, internal/environment variables and enum values,
impacted variables, working states and dynamics, transitions and assignments, and
APIs. Manifest structure is counted once per device instance because each instance is
parsed and simulated independently.

Every counted collection is limited to 1,000 items for exploration, and the summed model
structure is limited to 10,000 additive units. **Bounding and charging are separate:** every
collection is bounded, because the whole frozen snapshot is persisted regardless of what the search
reads, but a collection the engine never reads does not enter the cost estimate. Device privacies are
bounded only — `FuzzModel` writes empty privacy lists and never reads them.

The workload complexity then adds the cross-product costs the engine actually performs: environment
domains across devices, device modes across transitions and APIs, and ordered arbitration among rules
targeting the same device. `modelComplexityUnits` can therefore exceed 10,000 even when every additive
structure limit passes.

**Complexity is mode-dependent.** `PAPER_COMPATIBLE` additionally pays for the monitor's predecessor
walk (specification conditions across rules and their conditions), because only `evaluatePaper` reaches
`PaperMonitorFsm.distanceToViolation`. A `BOARD_SNAPSHOT` run performs none of that work and is no
longer charged for it — it previously was, and it was the largest term for every scene in
`docs/examples/`, so the default mode's estimate bore little relation to what it executed. Both
preview and submit therefore need the mode, which is why
`POST /api/fuzz/workload/preview` takes `explorationMode` and echoes it back.

The combined work guard requires
`maxIterations * pathLength * populationSize * max(1, modelComplexityUnits) <= 30,000,000`.
All additions and multiplications are checked for overflow and fail closed. The raw
`maxIterations * pathLength * populationSize` product is separately checked for overflow only: its
ceiling is the product of the three per-field maxima, so it cannot reject anything the field ranges
admit. Those two checks previously shared one constant, which left the complexity factor no headroom
to trim — it consumed the entire budget instead, and every shipped example scene refused the
product's own default settings for searches measured at about 16 seconds.

The limit is calibrated against measured throughput rather than chosen: a worst-case
`BUDGET_EXHAUSTED` run costs roughly 120-170 µs per state step, and admissible steps are
`limit / complexity`, which puts a maximal run near a minute.

**A running search also has a five-minute wall-clock budget**, checked between population members
and iterations, independently of that guard.
The guard bounds the *nominal* budget, but its calibration is per-step timing measured on one machine —
a slower host, a contended worker pool, or a board whose real per-step cost exceeds the estimate can
all overrun it, and the renewable two-minute task lease is a liveness heartbeat rather than a deadline.
The budget is roughly five times the measured duration of a maximal admitted run. It is a
cooperative deadline: current simulation, evaluation, or population construction may finish before
the next deadline check.

A search the clock ends is **not** reported as cancelled: cancellation is the user's own action and
persists no result, so labelling a timeout that way would misattribute it. It settles exactly as a spent
iteration budget does — `BUDGET_EXHAUSTED`, or `FOUND_VIOLATION` if candidates were already located,
which stay valid — and adds the conditional limitation code `TIME_BUDGET_EXHAUSTED` so the reason is
disclosed instead of being indistinguishable from a completed budget. Its `iterations` count reports what
actually ran, including a partially evaluated iteration. It can equal `maxIterations` when the
deadline interrupts the final population; `generatedPaths` records how many paths completed.
The complete frozen input snapshot is also limited to 8 MiB of UTF-8 JSON at both
preflight endpoints and submission because it is persisted independently of target selection.
Explicit target IDs therefore cannot
bypass structural or persistence limits on the captured Board. A Board with more than
100 specifications may still select at most 100 targets, but all captured specifications
remain part of the snapshot guard. The frontend obtains this value from
`POST /api/fuzz/workload/preview` and blocks submission while that authoritative check
is missing or stale. The submit endpoint repeats the calculation from its newly captured
snapshot to close concurrent-edit races.

### Admission and overload behavior

Submission reserves a process-wide capacity permit before reading the Board. The permit
count is the configured fuzz executor's `maxPoolSize + queueCapacity`. A running task
keeps its permit until its worker actually ends; cancellation of a task that has not
started removes its local queued Future and releases the permit immediately. If no permit
is available, the endpoint returns HTTP 503 without capturing, serializing, or persisting
a Board snapshot.

After the ordinary request, target, and workload checks, task creation locks the user's
database row and counts both all stored exploration tasks and the `PENDING` plus `RUNNING`
subset in the same transaction. The environment variables, defaults, and validation rules
for both limits are owned by the
[configuration reference](../getting-started/configuration.md#counterexample-search-admission).
Because the row lock, both counts, frozen-snapshot serialization, and insert are one
transaction, concurrent requests and multiple backend instances cannot bypass either
per-user limit.

The global status and `Result<T>` mapping for quota failures is owned by
[overview.md](overview.md#error-and-status-codes). This domain adds the following stable
response `data` payloads so clients do not parse English messages:

| Limit | `reasonCode` | Additional `data` fields |
| :--- | :--- | :--- |
| Active tasks | `FUZZ_ACTIVE_TASK_LIMIT_REACHED` | `activeTaskCount`, `maxActiveTasksPerUser` |
| Stored tasks | `FUZZ_STORED_TASK_LIMIT_REACHED` | `storedTaskCount`, `maxStoredTasksPerUser` |

The stored-task limit counts active, completed, failed, and cancelled task rows. It is
checked before the frozen snapshot is serialized.

The service rechecks task status before final executor acceptance. If that status read or
executor acceptance fails after persistence but before dispatch, it deletes the just-created,
still-`PENDING` row by task ID, user ID, worker ID, and status and releases its capacity
permit. A confirmed deletion, or confirmation that the row is already absent, preserves the
ordinary queue/runtime error and consumes no stored-task quota. If deletion cannot be confirmed,
HTTP 503 returns `data={ reasonCode: "TASK_DISPATCH_OUTCOME_UNKNOWN", taskKind: "fuzz",
taskId }`; clients must reconcile that task before retrying. A row independently observed as
`CANCELLED` before executor submission is retained as the cancellation record and returned as
the authoritative task rather than treated as dispatch failure.

Response data: the accepted `FuzzTaskDto`. Acceptance is not completion. Its fields are
`id`, `initiator`, `status`, `progress`, `progressStage`, lifecycle timestamps, `processingTimeMs`, optional
`errorMessage`, optional `runId`/`outcome`, frozen `modelSnapshot`, the five request
configuration fields (`explorationMode`, `maxIterations`, `pathLength`,
`populationSize`, and optional `seed`), and `targetSpecIds`. `runId` equals the task ID
after successful completion. Task detail and task-list summaries both return the
persisted `explorationMode`; it is not inferred from the current UI selection.
`initiator` is `USER` for direct REST/UI submission, `AI_ASSISTANT` for a task submitted
by a tool inside an owned chat turn, or `UNKNOWN` when provenance is unavailable. Task
and completed-run responses preserve this persisted value and never infer it from current UI state.
The exploration phases are `QUEUED`, `STARTING`, `PREPARING_EXPLORATION`,
`EXPLORING_CANDIDATES`, and `PERSISTING_RESULT`. The backend persists the percentage
and phase in one atomic active-task update so the UI can describe real work without
guessing from elapsed time.

Task statuses are `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, and `CANCELLED`.
`FAILED` is an execution status, not a search outcome. The task APIs are:

Persisted task responses fail closed when lifecycle fields contradict that status.
Identity, request budgets, progress, timestamp ordering, processing duration, finding
count, failure diagnostics, and outcome presence are validated before mapping. A completed
task requires a start/completion time, `progress=100`, non-negative processing duration,
and an outcome; pending cancellation may legitimately have no start time.

Each accepted task has an instance lease that is renewed while it is queued or executing.
Lease creation, renewal, and expiry comparison use the database clock, so JVM timezone or
host-clock differences cannot make another instance expire healthy work. Another backend
instance leaves a live lease untouched and only converts an expired active lease to `FAILED`;
this prevents a rolling deployment from terminating work owned by a healthy instance while
still recovering work abandoned by a stopped process. Start and renewal both require the
persisted lease to remain unexpired, so a delayed local Future cannot revive abandoned work
in the interval before recovery maintenance runs. Worker success and failure require the same
live owner and lease, use the database clock for terminal timestamps, and cannot commit stale
results before recovery runs; user cancellation remains independently authoritative. Future and
executor control remain process-local: when another instance commits cancellation, the
owner observes the failed lease renewal on its next maintenance cycle and removes a queued
Future or interrupts its running worker. The database status predicate remains the
cross-instance correctness authority throughout that bounded reconciliation delay.

Progress writes use the same worker-id and unexpired-lease predicate. Completion locks the
task row before the database terminal time is sampled and writes findings in that transaction,
so a worker delayed behind cancellation or recovery cannot commit stale evidence and a very
fast task cannot finish before its persisted creation timestamp.

- `GET /api/fuzz/tasks`: active plus failed/cancelled no-result tasks; completed rows are
  excluded. Optional repeated/comma-separated `excludeTaskIds` prevents duplicate polling
  and accepts at most 100 submitted positive IDs; duplicates are normalized after that
  request-size check. `page` defaults to `0` (`0..10000`),
  while `size` defaults to `100` (`1..200`); rows are newest first.
- `GET /api/fuzz/tasks/{id}` and `GET /api/fuzz/tasks/{id}/progress`: owned task detail
  and progress.
- `POST /api/fuzz/tasks/{id}/cancel`: returns `TaskCancellationResultDto`; terminal
  writes use atomic status predicates, so cancellation cannot later become completion.
  Status reads follow the shared [cancellation transaction ordering](../architecture/verification-flow.md#sync-async-and-simulation).
  Locally queued work is removed immediately when this instance owns it. The response
  reports no remaining execution only after that pre-start removal is definitive;
  otherwise it conservatively reports that execution may still be stopping while a local
  worker unwinds or another instance reconciles the cancellation. A running worker retains
  its capacity permit until its `finally` cleanup completes.
- `DELETE /api/fuzz/tasks/{id}`: dismisses only failed/cancelled tasks. Active work must
  be cancelled first; completed results are deleted through run history.

Automatic terminal-history eviction is intentionally not part of admission control.
Completed runs are user-visible investigation evidence, so the server never silently
deletes the oldest run. Instead, the stored-task quota rejects a new submission with an
actionable reason once the configured total is reached. Per-run snapshot and evidence
byte ceilings bound each row group; users free capacity by deleting completed runs or
failed/cancelled tasks through the endpoints above, and account deletion removes all
owned task/finding rows.

### Exploration modes

Both modes consume the same immutable Board snapshot, target selection, bounded request,
eligibility checks, result DTOs, and persistence lifecycle. They differ only in how a
candidate path is initialized, represented, scored, and mutated:

| Mode | Search semantics |
| :--- | :--- |
| `BOARD_SNAPSHOT` | Default product mode. It starts from the authored Board state, represents a path as bounded semantic choices, directly monitors structured templates `1`, `3`, and `4`, keeps an independent best candidate per unresolved target, mutates only decoded choices that had more than one legal successor, and schedules deterministic random restarts. |
| `PAPER_COMPATIBLE` | Optional paper-compatible subset. A deterministic nonce materializes legal initial device-state, device-local-variable, and environment values; sparse per-target overrides retain mutations of that initial state, while ordinary one-based `(Type, Name, Value, Position)` Events retain later inputs. Device Events are restricted to position `1`; environment Events may occur across the bounded path. Only structured templates `1`, `3` and `4` are supported, and a discrete environment without a rate domain accepts direct-value Events as an explicit IoT-Verify extension. The mutation operators, batch sizing, and guidance heuristics are owned by [../architecture/fuzzing-flow.md](../architecture/fuzzing-flow.md#pipeline). |

`explorationMode` is stored in the `fuzz_task` row before execution. Because a completed
task is also the run row, history and detail responses preserve which algorithm produced
the evidence even after the Board or frontend defaults change. A missing persisted mode or
required limitation code is invalid metadata; the API does not infer a mode or synthesize
semantic disclosures while reading history.

`PAPER_COMPATIBLE` is intentionally a compatibility subset, not a claim of complete
paper reproduction. It still accepts only IoT-Verify's structured templates `1`, `3`,
and `4`, uses the platform's integer-valued numeric domains, and keeps the same bounded
multi-target product lifecycle. It does not accept arbitrary LTL, reproduce the paper's
64-bit `double` data model, implement MEDIC attack/trust/privacy/content semantics, or
ship the paper's benchmark generation and comparative evaluation harness. Paper-reported
coverage, performance, and detection rates therefore do not describe this endpoint.

Algorithm 1 condition satisfaction follows the paper text: every atomic proposition,
including an integer inequality, contributes exactly `1` when true and `0` when false;
conjunctions average their flattened constituent conditions and disjunctions take the
maximum. The inspected artifact's additional `double`-domain numeric-proximity heuristic
is not silently enabled in this integer subset.

## Run results

A completed `fuzz_task` row is also the run row; its run ID is the same task ID.
`GET /api/fuzz/runs` returns the requested newest-first page as
`FuzzRunSummaryDto[]`; `page` defaults to `0` (`0..10000`) and `size` defaults to `25`
(`1..100`). The list shape intentionally stays an array; a client requests the next
page when the current page length equals its requested size. Meanwhile,
`GET /api/fuzz/runs/{id}` returns `FuzzRunDto`. Both expose:

| Field | Type | Meaning |
| :--- | :--- | :--- |
| `id` | integer | Run/task identity |
| `initiator` | enum | Persisted `USER`, `AI_ASSISTANT`, or `UNKNOWN` origin |
| `outcome` | enum | `FOUND_VIOLATION`, `BUDGET_EXHAUSTED`, or `INCONCLUSIVE` |
| `explorationMode` | enum | Persisted `BOARD_SNAPSHOT` or `PAPER_COMPATIBLE` algorithm used for this run |
| `effectiveSeed` | integer | Exact seed used; see the [reproduction conditions](../architecture/fuzzing-flow.md#determinism-and-budgets) |
| `iterations` | integer | Evaluated iterations, including a partially evaluated final population |
| `generatedPaths` | integer | Paths evaluated, including every population member evaluated before early completion |
| `elapsedMs` | integer | Engine elapsed time |
| `modelSnapshot` | object | Captured counts/time with `templatesFrozen=true` and the canonical `modelFingerprint` used for current-Board drift checks |
| `playbackScene` | object | `FuzzRunDto` detail only: canonical frozen `{ nodes, rules }` used to render its findings without consulting the live Board. It is omitted from list summaries. |
| `eligibility` | object | Eligible IDs plus frozen `eligibleSpecLabels`, itemized ineligible specifications, requested count, and eligible count |
| `eligibleSpecifications` | `Record<string, SpecificationDto>` | Frozen structured conditions keyed by exactly the eligible specification IDs; used to distinguish targets sharing one template |
| `modelTokenSourcesByDeviceId` | `Record<string, ModelTokenSource>` | Frozen device token provenance for condition display, with exact captured-device membership. Uses the [trace token-provenance contract](verification.md#3-trace-structure); built-in tokens may be localized, custom or unknown tokens keep their spelling |
| `limitations` | `string[]` | Stable limitation codes that apply to this run |
| `createdAt` / `completedAt` | datetime | Lifecycle timestamps |
| `findingCount` | integer | Number of persisted candidate violations |
| `maxIterations` / `pathLength` / `populationSize` | integer | Effective search budget |
| `findings` | array | Lightweight summaries in run lists; full findings in run detail |

Frozen specifications retain semantic validation for their operators, condition groups,
device references, and collection sizes. Their identifiers use the
[normalized model identity contract](../architecture/device-identity.md), including
the possible prefix added to a valid Board ID. Server-derived display caches such as
device labels and formula previews are not subject to authoring-field limits when
reading frozen evidence. Authoring requests keep their existing validation limits;
malformed semantic data still makes the stored result unavailable.

Completed statistics are accepted only when `iterations <= maxIterations` and either both
`iterations` and `generatedPaths` are zero, or
`iterations <= generatedPaths <= iterations * populationSize`. The engine boundary,
persisted-data mapper, history projection, and frontend response validator all enforce the
same relation with overflow-safe multiplication.

`FuzzRunDto` additionally exposes `targetSpecIds`. Each list-only
`FuzzRunSummaryDto` also has `dataAvailable`: valid rows set it to `true`; a damaged
persisted row remains visible with `dataAvailable=false`,
`unavailableReasonCode=PERSISTED_SEMANTIC_DATA_INVALID`, its available identity and
timestamps, and an empty `findings` list. Run detail fails closed instead of returning
an unavailable detail object. When a complete-result read identifies an individual
`FuzzFinding` as damaged, clients disable only that finding and retain independently
validated siblings for direct replay. Deleting a run through `DELETE /api/fuzz/runs/{id}`
deletes its findings in the same transaction.

Run metadata uses immutable constructor projections that exclude frozen inputs and diagnostic
logs. Run-list decoding uses a lightweight finding projection that excludes per-finding
specification, trace-state, and input-event LONGTEXT. The run, presentation, and finding projections
are captured in one read-only transaction. JSON validation and DTO assembly follow that capture,
after a standalone read has returned its database connection; an enclosing transaction retains
its ownership and original rollback propagation for decoding exceptions that escape the service.
Run detail likewise captures its owned run and complete ordered findings together, then validates
and assembles the captured evidence after releasing a standalone connection.
A separate bounded projection extracts
only the specification array and token-source map from the owning run's version-`1`
frozen-input envelope. MySQL validates and materializes each selected envelope as JSON once
before extracting both fields, rather than repeatedly parsing its LONGTEXT representation.
Malformed or unsupported envelopes still produce unavailable presentation metadata. The mapper
validates specification shapes/counts, eligible membership,
and condition device references, and populates each finding's `violatedSpec` from that array.
It also returns `violatedSpecId`, the frozen requirement name as `specificationLabel`, and `stateCount`, and validates run
metadata, finding ownership/counts, seed, timestamps, and bounded state-count/violation-step
metadata, including `stateCount <= pathLength`. A finding summary intentionally has no
`dataAvailable` field: the list projection has not loaded enough evidence to make that
claim. Replay loads the selected finding with its atomically returned frozen run context;
it never combines a separately returned finding with another response's model snapshot. This
lets a valid finding remain replayable when a sibling's full payload is damaged. Full run or
finding detail validates the complete frozen
specification and state/event prefix before returning it. Corruption inside full evidence
therefore fails closed at that detail boundary even when the preceding list metadata was valid.
Full finding collections preserve ascending creation/id order after loading owned rows. Their
database query is deliberately unordered so complete state and input-event LONGTEXT does not
inflate a filesort; the same full-detail validation still runs before the response is returned.

Detail decoding treats the persisted, versioned `modelInputSnapshotJson` envelope as the
integrity root. The public model counts must exactly match
the model snapshot; target IDs must belong to its
specification set; eligibility must partition every effective target exactly once; an
eligible specification must use finite template `1`, `3`, or `4` with the required
condition side shape; and every finding must belong to the run, use its effective seed,
match the exact frozen specification snapshot, and contain a valid bounded state/event
prefix whose state count does not exceed the run's captured `pathLength`. Any contradiction
makes the complete run unavailable rather than presenting
partial heuristic evidence as trustworthy. One finding is read directly by ID and is still
checked against its owning run's frozen specification, eligibility, effective seed, and
timestamps. The single-finding endpoint also compares the run's declared `findingCount`
with the actual number of owned finding rows before returning detail, so a partially missing
finding set cannot make one surviving row appear trustworthy.

### Eligibility and outcomes

Both finite-trace modes evaluate templates `1` (Always), `3` (Never), and `4`
(Immediate). Other templates are itemized as `UNSUPPORTED_TEMPLATE`; invalid structured
conditions and unsupported model inputs are fail-closed rather than guessed. Numeric
environment and variable domains remain integer-valued in both modes. Attack,
compromise, trust/privacy propagation, and content-bearing commands are not modeled.
See [fuzzing-flow.md](../architecture/fuzzing-flow.md) for the exact boundary.

- `FOUND_VIOLATION`: at least one persisted candidate path violated an eligible spec.
- `BUDGET_EXHAUSTED`: eligible specs existed, but no candidate violation was found in
  the budget. This is a neutral negative search result, not satisfaction.
- `INCONCLUSIVE`: no requested specification was eligible. Read
  `eligibility.ineligibleSpecs[]` and `limitations[]` before changing the model or retrying.

Each ineligible item is `{ specId, specificationLabel, reasonCode, reason }`.
Specification labels are captured user-authored names, not template labels; the name
contract is owned by [SpecificationDto](verification.md#specificationdto).
`reasonCode` is a stable category and is one of `UNSUPPORTED_TEMPLATE`,
`TRUST_PRIVACY_UNSUPPORTED`, `REPORTED_READING_UNSUPPORTED`,
`CONTENT_COMMAND_UNSUPPORTED`, `MODEL_INVALID`, or `INVALID_SPECIFICATION` —
the exact set `FuzzEngine` emits (`MODEL_INVALID` plus the six eligibility reasons).
`REPORTED_READING_UNSUPPORTED` covers a `variable` condition reading a *reported* value: the
explorer keeps one value per shared reading and models no compromised device, so it cannot separate
"what this device said" from "what the home held".
`reason` is an English technical diagnostic and is not ordinary localized UI copy.
The ordinary result dialog does not render this raw diagnostic; it uses the stable
`reasonCode` for localized guidance. The raw `reason` remains available to authenticated
API clients for development and support tooling.

All runs report `FINITE_TRACE_TEMPLATES_1_3_4_ONLY`,
`ATTACK_TRUST_PRIVACY_CONTENT_UNSUPPORTED`, and
`FINAL_ANTECEDENT_WITHOUT_SUCCESSOR_INCONCLUSIVE`. `PAPER_COMPATIBLE` additionally
reports `PAPER_EVENT_FSM_DISTANCE_ENABLED`, `PAPER_RANDOM_INITIAL_STATE_ENABLED`,
`PAPER_STRUCTURED_LTL_TEMPLATES_ONLY`, `PAPER_INTEGER_NUMERIC_DOMAIN_ONLY`,
`PAPER_DISCRETE_ENVIRONMENT_DIRECT_VALUE_EXTENSION`,
`PAPER_PREDECESSOR_STATE_OUTPUTS_THREE_LEVELS_ONLY`,
`PAPER_MULTI_TARGET_PRODUCT_EXTENSION`, and
`NUSMV_REMAINS_PROOF_AUTHORITY`. These codes describe both enabled semantics and
important interpretation boundaries. Clients localize them and use a generic localized
fallback for future unknown codes.

Two further codes are conditional rather than always present. `UNKNOWN_TARGET_SPEC_IDS_IGNORED`
is added when `targetSpecIds` named an ID absent from the frozen snapshot. The submit endpoint
rejects unknown IDs outright, so this only surfaces on a path that reaches the engine with a stale
selection; the run continues against the IDs that did resolve. `TIME_BUDGET_EXHAUSTED` is added when the
wall-clock ceiling ended the search before its iteration budget was spent, so the run covered less than
was requested — see the workload section above.

## Findings

`GET /api/fuzz/runs/{id}/findings` returns all full findings for one owned run.
`GET /api/fuzz/findings/{id}` returns `FuzzFindingReplayDto`, containing the selected
`finding: FuzzFindingDto` plus the owning run's validated `modelSnapshot` and
`playbackScene`. This is the replay contract for a selected history item or
`run=exploration:<runId>&finding=<findingId>` link: clients verify that
`finding.fuzzTaskId` equals the URL's `runId`, then render only this frozen scene. A
`FuzzFindingDto` is:

| Field | Type | Meaning |
| :--- | :--- | :--- |
| `id` / `fuzzTaskId` | integer | Finding identity and owning run/task |
| `violatedSpecId` | string | Captured specification identity |
| `violatedSpec` | `SpecificationDto` | Frozen structured specification snapshot; never rebuilt from the current Board |
| `firstViolationStep` | integer | Zero-based first violating state; always `states.length - 1` |
| `states` | `TraceStateDto[]` | Candidate finite prefix ending exactly at the first violation. Device, device-local-variable, and environment entries carry the frozen `modelTokenSource` contract defined in [verification trace structure](verification.md#3-trace-structure). Because neither exploration mode implements MEDIC trust/privacy propagation, fuzz trace variables leave `trust` null and device/state `trustPrivacy`/`privacies` collections empty. Clients reject a finding carrying any non-null/non-empty label evidence instead of presenting template labels as finding evidence |
| `seed` | integer | Run's effective seed |
| `inputEvents` | `FuzzInputEventDto[]` | Ordered replay evidence as `{ step, kind, targetId, property, value, source }`. `kind` is `DEVICE_STATE`, `DEVICE_VARIABLE`, `ENVIRONMENT_VALUE`, or `ENVIRONMENT_RATE`; `source` is `MODEL_CHOICE`, `RANDOM_INITIAL_STATE`, or `SEED_EVENT`. Paper-compatible one-based `Position` is exposed as zero-based `step` |
| `createdAt` | datetime | Persistence time |

Run-list finding summaries retain `violatedSpec` from the owning run's frozen presentation
projection and replace `states` and `inputEvents` with bounded `specificationLabel` and
`stateCount` fields. They do not expose `dataAvailable`, because summary metadata alone
cannot establish trace replayability.
For every summary, `firstViolationStep` likewise equals `stateCount - 1`.
Findings use separate persistence from formal `trace` rows and cannot be sent to fix
endpoints.

The task stores `modelInputSnapshotJson` as one strict schema-version-`1` envelope containing
the complete `ModelInputSnapshot` and an exact canonical device-id to
`BUNDLED | CUSTOM | UNKNOWN` source map. Keeping both parts in one column makes the frozen
input and its server-resolved display-token provenance atomic. Missing fields, unsupported
versions, incomplete device membership, null sources, or extra root fields make the full
evidence unavailable; obsolete unversioned development data is not inferred or upgraded.
Finding states must copy the frozen source to each device and local variable. A shared
environment variable is `BUNDLED` or `CUSTOM` only when every captured template declaring it
agrees; mixed declarations are `UNKNOWN`. Missing or conflicting finding sources are invalid
and are never synthesized from current templates. Clients localize only `BUNDLED`.

Before replay, the frontend validates the selected finding together with its returned frozen run
snapshot and playback scene, then validates every nested state, device, variable, triggered-rule,
trust/privacy, and environment/global entry. Invalid scalar types, property scopes,
trust/privacy enum values, duplicate identities, incomplete device/variable membership between
complete snapshots, per-list evidence semantics, cross-state source drift, or missing, null, or
unsupported `modelTokenSource` values reject the finding as an incomplete response;
malformed provenance therefore cannot opt custom tokens into bundled localization. Empty
`state` and `mode` strings remain valid for captured stateless devices.

The source field makes causal precedence inspectable and machine-checked. Steps must be
non-decreasing; within one step the only valid source order is
`RANDOM_INITIAL_STATE`, then `SEED_EVENT`, then `MODEL_CHOICE`, and random-initial-state
evidence is valid only at step `0`. At position `1`, paper-compatible evidence first records
the nonce-materialized legal baseline, then any sparse initial override and ordinary seed
Event that overrides the same property. At each later position, the previous state is
the transition source while scheduled Events modify the copied target state. Rule
conditions, API start states, autonomous-transition guards, dynamics, and impacts read
the previous source state; their outputs write the target and therefore do not react to
an Event one step too early. Scheduled seed Events precede model-choice evidence for the
state they help form. A numeric environment rate is applied at its own position,
replaces that step's unconstrained natural-rate choice, and combines once with the
current device impact before clamping; an explicit formal transition assignment still
has final authority. A paper-mode discrete environment stays unchanged when neither an
Event nor a formal effect writes it. Numeric environment seed values are reported as
`ENVIRONMENT_RATE`; discrete direct-value compatibility Events remain
`ENVIRONMENT_VALUE`. Every persisted event must contain an explicit valid `source`;
missing or unknown kinds and sources fail closed.
