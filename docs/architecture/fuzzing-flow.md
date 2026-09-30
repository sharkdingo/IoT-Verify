# Counterexample Exploration Flow

Architecture and semantic boundary for the HAFuzz-inspired bounded search module.
API fields and endpoints are owned by [../api/fuzzing.md](../api/fuzzing.md); formal
model semantics remain owned by [nusmv-model.md](nusmv-model.md).

Verified against code on 2026-09-30. Source: `component/fuzz/`,
`service/impl/FuzzServiceImpl.java`, `po/FuzzTaskPo.java`, and
`po/FuzzFindingPo.java`.

---

## Role in IoT-Verify

Counterexample exploration is a **supporting module** between Board authoring and formal
verification. It is not the platform's primary proof engine and does not replace NuSMV.

```text
Board snapshot
    |
    +--> bounded candidate-path search --> candidate finding --> replay/investigate
    |                                                        \-> run NuSMV verification
    |
    +--> NuSMV model checking ----------> formal result -----> automatic fix
```

The module gives fast, reproducible falsification attempts on selected safety
properties. Its strongest valid statement is "this finite path violates this supported
property under the explorer's modeled semantics." Only NuSMV can produce the formal
verification conclusion and formal counterexample accepted by the fix pipeline.

## Why this is a clean-room integration

The inspected artifact accompanies Dai et al.,
["Temporal Specification Oriented Fuzzing for Trigger-Action-Programming Smart Home
Integrations"](https://doi.org/10.1145/3744916.3764543) (2026 International Conference
on Software Engineering). HAFuzz combines
finite-step runtime verification with fuzzing so monitor progress can guide later seed
selection. Its research artifact implements seed generation, trace formation,
runtime monitoring, distance-guided selection, and mutation around Java 8 plus bundled
native LTL tooling. Its top-level application source does not include a confirmed
redistribution license, its input model differs from IoT-Verify's persisted Board
contract, and it assumes an Ubuntu/JDK 8 toolchain.

IoT-Verify therefore implements the same high-level search loop independently against
its own Java 17 DTOs and frozen Board snapshots. It does not copy the artifact source,
ship its native tools, parse its text files, or make that artifact a deployment dependency.

## Pipeline

Before submission, the Board UI may call `POST /api/fuzz/paper-domain/preview` when the
research strategy is selected. The endpoint builds domains through the same executable
`FuzzModel` as a real run and performs no persistence. It shows what can be sampled; it
does not invent a concrete initial state before candidate seeds exist. The response also
binds the preview to a server-generated semantic fingerprint. Paper-mode submission
must return that fingerprint, and the service rejects it if a fresh Board snapshot no
longer matches. Preview and submission share the 8 MiB frozen-snapshot admission limit.
Canvas coordinates and dimensions do not invalidate a semantic preview.

1. `FuzzServiceImpl.submit` validates the bounded request and target IDs.
2. `BoardDataConverter.getModelInputSnapshot` captures normalized devices, effective
   environment values, ordered rules, structured specifications, and exact referenced
   manifests.
3. The service validates every captured specification and an 8 MiB serialized-snapshot
   ceiling, then persists a `PENDING` `fuzz_task` containing both the full internal input
   snapshot and public `ModelRunSnapshotDto` counts. The row receives an instance lease
   before dispatch to the dedicated `fuzzTaskExecutor`; the owner renews queued and
   executing tasks, while any instance may fail only an expired lease — including one whose
   expiry equals the sampled instant, on the same boundary as the verification and simulation
   sweeps. Start, progress, renewal, and terminal transitions use the shared
   [row-lock-before-clock ownership boundary](verification-flow.md#sync-async-and-simulation).
   A queued command
   captures only task identity; the worker reconstructs its engine input from the persisted
   frozen snapshot after it starts, so queued tasks do not retain duplicate multi-megabyte
   object graphs in heap. Local execution handles bind task IDs to cancellable Futures and
   idempotent capacity permits. Cancelling before execution removes the decorated Future
   from the executor queue and releases capacity; cancelling after execution starts only
   interrupts the worker, which releases capacity from its final cleanup. If cancellation
   is committed through another backend instance, failed owner-lease renewal triggers the
   same local cleanup on the next maintenance cycle.
4. `FuzzEngine` classifies each requested specification. Unsupported or invalid inputs
   become itemized ineligibility; they are never treated as satisfied. The persisted
   `explorationMode` then selects one of two bounded search strategies.
5. `BOARD_SNAPSHOT` uses one `SplittableRandom` seeded by `effectiveSeed` to create a
   decision-genome population from the authored Board initial state. A genome
   deterministically selects bounded environment/device-variable choices. Only decoded
   decisions with more than one semantic successor are mutation targets; every twentieth
   offspring is a deterministic random restart.
6. `PAPER_COMPATIBLE` creates seeds from a nonce that materializes random legal initial
   device-state, device-local-variable, and environment values, sparse per-target initial
   overrides, and ordinary one-based `(Type, Name, Value, Position)` Events. Device Events
   are legal only at position `1`; environment Events span the bounded trace. New offspring
   use a `95%` mutation / `5%` fresh-random split. Mutation clones the selected parent's
   nonce and overrides, then changes a selected mandatory initial target or ordinary Event;
   the fresh-random branch receives a new nonce, no inherited overrides, and a new Event
   collection. Mandatory initial nodes are always at position `1`, can only modify the same
   target to another legal value, and remain in the mutation pool whether or not they already
   have an override. Environment Event mutation adds, deletes, or modifies an Event, and
   device Event mutation only changes a selected legal position-`1` value. Mutation selects
   a trace position first and then one node at that position, retrying an occupied position
   if the first choice is empty. Matching the reference artifact, the operation count is
   `min(128, round((0.01 + 0.09u) * n))` for deterministic `u` in `[0, 1)` and
   `n = initialTargetCount + eventCount`; the cap is a product safety bound, and small
   vectors may produce zero-operation clones. Addition samples uniformly
   from every legal unoccupied environment `(Name, Position)` slot. Environment
   modification changes both value and position and becomes a no-op if the legal domain
   cannot change both. After excluding the current value, enum-backed modifications
   sample each remaining legal value uniformly. Numeric initial values and rates are
   sampled directly from the complete inclusive integer ranges rather than from an
   enumerated sample; every rate Event uses the canonical encoding documented in the
   [fuzzing API contract](../api/fuzzing.md).
7. Paper path formation follows the paper's state order. Position `1` starts from a
   random legal state and applies position-`1` Events. Each later position copies the
   preceding state and applies its scheduled Event inputs to the copied target. Rule
   conditions, API start states, autonomous-transition guards, dynamics, and impacts
   read the preceding source state; their results write the Event-modified target. This
   preserves the paper's example in which a condition in `s(i-1)` determines an output
   in `s(i)` and prevents a position-`i` Event from triggering a rule one step early.
   A numeric environment rate is effective at that position:
   it replaces the unconstrained natural-rate choice and combines once with the current
   device impact before clamping. A forced formal transition assignment overrides it.
   A paper-mode discrete environment otherwise remains stable instead of making an
   undocumented free enum choice.
8. `FuzzModel` advances device modes, ordered automation actions, autonomous transitions,
   variables, environment values, API pulses, and triggered-rule snapshots. Cancellation
   is checked while forming a state. Paper monitor FSMs advance online as each state is
   produced. A single-target candidate stops at its first violation, matching the paper;
   for the product's multi-target extension, one candidate continues only until every
   currently unresolved target has reached its violation state or the path bound is met.
9. In `BOARD_SNAPSHOT`, templates `1`, `3`, and `4` use direct specialized distance
   monitors. In `PAPER_COMPATIBLE`, those same structured templates compile to explicit
   monitor FSMs. The paper-compatible score combines BFS graph distance to a violation
   state with satisfaction of the next shortest-path transition conditions. It recursively
   resolves predecessor rule conditions (`GetPrevConditions`) and weights them by solver
   level across a fixed three-level chain. The per-level weight is
   `2^(l_up-l) / (2^l_up - 1)`, exactly as printed in Algorithm 1 line 25. The denominator
   is the sum of the powers assigned to all levels, so a fully satisfied condition chain
   contributes exactly `1` to `Dist_cond`; the HAFuzz reference artifact computes the same
   normalization by summing its power map. `SeedSelection` takes the *minimum* distance,
   and the implementation also clamps the final score to a non-negative distance. Every
   atomic proposition uses the paper's
   explicit binary satisfaction (`1` when true, `0` otherwise), including numeric
    predicates. Conjunctions average all constituent conditions and disjunctions take
    their maximum satisfaction. Predecessors are resolved for rule-produced state,
    mode, and API outputs; IoT-Verify actions do not currently assign variables, so the
    resolver cannot claim the paper artifact's general variable-producer graph. A
    missing predecessor for a direct transition conjunct contributes binary `0` to the
    first predecessor level, matching the reference artifact's first aggregation; a
    missing branch discovered while descending deeper rule chains is omitted. Resolver
    memoization is therefore keyed by both condition and solver level. Only a
    mode explicitly written by an API `endState` is a mode predecessor; an unchanged mode
   mentioned only by `startState` is not treated as an action output. Ordered-rule
   predecessor guards also include the absence of an enabled earlier overlapping action,
   and that arbitration-only condition is not recursively misclassified as another
   rule-produced output. A violating prefix becomes one finding per specification;
   otherwise each unresolved specification retains its own guidance candidate.
10. The worker limits each serialized finding to 4 MiB and aggregate run evidence to
    16 MiB, then locks the task row, stamps findings with the microsecond database clock,
    and writes all findings before atomically transitioning `RUNNING -> COMPLETED` in one
    transaction. The transition and every progress update require the current worker id and
    unexpired lease. A concurrent cancellation rolls that transaction back. Worker cleanup
    releases its lease even when initial progress or failure persistence throws.

Replay evidence preserves both input meaning and provenance. Device state Events,
device-local variable choices, environment direct values, and environment rates use
separate kinds. Ordinary decoded nondeterminism is `MODEL_CHOICE`; a paper seed's
materialized state is `RANDOM_INITIAL_STATE`; and its explicit Event collection is
`SEED_EVENT`. If initialization and a position-`1` Event name the same property, both
ordered facts remain visible so the later seed override is not mistaken for the sampled
initial value. At later positions, the scheduled Event is recorded before any model
choice produced while forming that same state.

The worker also freezes display-token provenance independently of those causal Event
sources. One strict, versioned model-input envelope atomically stores the complete
`ModelInputSnapshot` and the server-resolved source for every canonical device. Generated
finding states apply that frozen source to device state and local variables; shared
environment values receive a known source only when all declaring captured templates agree.
The envelope is accepted only when its current schema contains exactly the six frozen
snapshot fields (`nodes`, `devices`, `environmentVariables`, `rules`, `specifications`,
and `templateManifests`) with their canonical array/object types and its complete device-id
set matches the frozen snapshot. Missing or conflicting finding sources are rejected, so
history never changes meaning when the current template catalog is reset or replaced.

The full input snapshot is internal. Public history stores the smaller model-scope
summary, its canonical semantic fingerprint, run configuration, statistics,
eligibility, limitations, and independent finding evidence. The Board can read the
current fingerprint without creating a task and uses it to detect same-count semantic
drift before replay-to-verification handoff. Missing or malformed stored fingerprints fail
closed and make the affected development row unavailable. Full run mapping also recomputes
the fingerprint from the decoded frozen input using the writer's same normalization and
requires equality, so content corruption cannot pass merely because collection counts are
unchanged.

The frontend keeps the proof handoff explicit: a finding can focus the user's attention
on one historical target, but the verification request still contains the complete
specification set from the current Board. It does not silently import the finding's
historical snapshot, random initial state, or Event sequence. Search settings can be
restored for review, using the effective seed, but restoration never starts a task and
never silently broadens a target set when historical targets are unavailable.

## Relationship to the HAFuzz paper

The mode selector makes the product/research tradeoff explicit instead of silently mixing
two mental models:

| Aspect | `BOARD_SNAPSHOT` | `PAPER_COMPATIBLE` |
| :--- | :--- | :--- |
| Initial state | Frozen authored Board values | Random legal device and environment values derived from the seed nonce |
| Candidate representation | Fixed-length decoded decision genome | `(Type, Name, Value, Position)` event collection |
| Feedback | Direct template-specific condition distance | Explicit FSM, BFS violation distance, next-transition conditions, and recursive `GetPrevConditions` weighting |
| Diversity | Semantic-choice mutation plus scheduled restart | `95%` event mutation and `5%` fresh random seeds |
| Intended use | Default product investigation workflow | Optional paper-alignment and algorithm-comparison workflow |

The paper-compatible path implements the paper's core Event representation and the
Algorithm 1 mechanisms that can be expressed faithfully over IoT-Verify's existing
structured specifications and Board simulator. It remains a compatibility subset, not
a full experimental reproduction:

- Only structured templates `1`, `3`, and `4` are compiled; arbitrary LTL input and the
  paper artifact's general formula/tool pipeline are absent.
- Numeric environment and device-variable domains use IoT-Verify's integer schema, not
  the paper's 64-bit `double` model.
- Numeric environment seed events use declared integer rates. For a discrete environment
  that has values but no rate domain, IoT-Verify permits direct-value events so the Board
  model remains searchable. Domain type, not the `rate:` text prefix alone, determines
  whether a value is applied as a delta; `PAPER_DISCRETE_ENVIRONMENT_DIRECT_VALUE_EXTENSION`
  marks this deliberate compatibility extension.
- `GetPrevConditions` follows rule-produced state, mode, and API outputs for a fixed
  three solver levels. General action assignments to arbitrary variables are outside the
  Board action model and are reported through
  `PAPER_PREDECESSOR_STATE_OUTPUTS_THREE_LEVELS_ONLY`.
- MEDIC attack, compromised-link, trust, privacy, and content semantics are not part of
  either exploration mode.
- The paper's benchmark generation, generated scenarios, comparative baselines, and
  evaluation harness are not shipped. No paper-reported coverage, performance, or
  detection rate can be attributed to this implementation without a controlled study.
- A product task may target several specifications. It keeps evidence and guidance per
  unresolved target and continues until all eligible targets have findings, the bounded
  budget ends, or cancellation is requested, rather than pretending one multi-target
  average is the paper's single-monitor experiment.
  `PAPER_MULTI_TARGET_PRODUCT_EXTENSION` exposes this difference before and after a run.

## Determinism and budgets

A supplied seed plus identical captured input, `explorationMode`, and search
configuration produces the same search decisions and findings. In paper-compatible mode
the seed also determines random legal initial-state materialization, sparse initial-value
overrides, and the `95/5` offspring strategy. The server-generated seed is always returned so a run can be
reproduced. Wall-clock `elapsedMs` is observational and is not expected to match across
executions.

Completed run statistics must satisfy `iterations <= maxIterations` and either be
`0 iterations / 0 paths` or satisfy
`iterations <= generatedPaths <= iterations * populationSize`. Input evidence is likewise
ordered as a causal sequence: non-decreasing steps, with
`RANDOM_INITIAL_STATE -> SEED_EVENT -> MODEL_CHOICE` inside one step and random
initialization restricted to step `0`. These invariants are enforced before persistence,
when reading full/history data, and in the frontend response boundary.

The API bounds each dimension and applies a combined workload guard over the search budget
and the frozen Board's model complexity. It carries **no** target-specification factor: the
target count is bounded separately (at most 100) and never enters the workload product, even
though every iteration evaluates every still-unresolved target. The exact validation contract
lives in [the API document](../api/fuzzing.md#submit-and-task-lifecycle). The engine performs no
unbounded search.
It checks the cancellation marker and worker interrupt between population members, while
forming each path, and before completion persistence. Progress is execution feedback, not
coverage.

A wall-clock ceiling is checked at the same points, because the workload guard bounds the *nominal*
budget from per-step timing measured on one machine and a slower host or a worse-than-estimated board
can still overrun it. A search the clock ends settles as an ordinary bounded outcome plus the
`TIME_BUDGET_EXHAUSTED` limitation — never as a cancellation, which is the user's own action and
persists no result. Findings already located remain valid. The ceiling and its rationale are in
[the API document](../api/fuzzing.md#submit-and-task-lifecycle).

## Supported finite semantics

| Template | Violation on one finite path |
| :--- | :--- |
| `1` Always, `AG(A)` | First state where `A` is false |
| `3` Never, `AG !(A)` | First state where `A` is true |
| `4` Immediate, `AG(IF -> AX(THEN))` | State `n+1` where `IF` held at `n` and `THEN` is false at `n+1` |

An `IF` at the final state has no observed successor and is not called a violation.
Templates `2`, `5`, and `6` require liveness reasoning beyond a bounded finite prefix;
template `7` requires trust-label propagation. They remain formal-verification features
and are reported ineligible here.

Structured conditions support state, mode, signal API, and declared variable predicates.
Numeric domains are integer-valued; `double`-precision input is not silently rounded or
claimed as supported.
Device references are canonical model IDs. Ordered rule commands follow the same
full-action priority boundary as the NuSMV model: a matching higher-priority action that
shares any affected mode blocks a lower action as a whole, while disjoint actions may
run together.

## Deliberate exclusions

The MVP fails closed or marks specifications ineligible when faithful interpretation is
not available. It does not model:

- compromised devices or automation links;
- trust/privacy propagation or template `7`;
- content-bearing commands;
- formal branching coverage or infinite-path liveness;
- arbitrary LTL input, the paper artifact's native formula pipeline, or `double` numeric domains;
- the paper's MEDIC/benchmark-generation/evaluation environment;
- NuSMV formula parsing, infinite-trace automata, or proof completeness.

`BUDGET_EXHAUSTED` therefore means "not found within this bounded search." It must not
be rendered green, called safe, or fed into automatic fix. `FOUND_VIOLATION` findings are
candidate evidence; the Board offers replay and a transition to formal verification,
not a direct Fix action.

Eligibility and limitation explanations cross the API as stable codes plus optional
English diagnostics. Ordinary UI localizes the codes and uses a generic localized
fallback for an unknown future code; backend diagnostic text is not inserted directly
into localized prose.

## Persistence and ownership

`fuzz_task` owns lifecycle, frozen input, configuration including `explorationMode`,
result statistics, and the completed run identity. New tasks persist the selected mode
before dispatch; a missing mode in persisted task/run data is treated as corruption and
fails closed rather than being relabeled as `BOARD_SNAPSHOT`.
`fuzz_finding` owns candidate evidence and references the task ID logically. Run-list
queries project only summary columns: finding specifications, states, and input events are
omitted and replaced by a bounded label plus counts. A finding summary does not claim
`dataAvailable`; complete evidence is loaded and checked on run/finding detail before replay.
Both projections and complete evidence must report no more states than
the owning run's `pathLength`, and engine output is checked against that budget before any
finding is serialized. Eligibility labels and diagnostics are normalized to bounded single-line
text, and the combined persisted run metadata is capped at 256 KiB before completion is
committed. Every query includes `userId`; entities are mapped to DTOs and are never exposed
directly. Account deletion deletes findings before tasks while holding the
same user-level persistence lock used by completion. Irreversible token revocation and
local worker interruption occur only after that transaction commits; a rollback leaves
the session and workers untouched.
