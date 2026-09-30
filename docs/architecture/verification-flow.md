# Verification Flow

End-to-end behavior from a verification request to an interpretable conclusion,
counterexample, and optional fix attempt. Field-level contracts live in
[../api/verification.md](../api/verification.md); model construction lives in
[nusmv-model.md](nusmv-model.md).

Verified against code on 2026-09-30. Primary sources:
`VerificationController`, `SimulationController`, `VerificationServiceImpl`,
`SimulationServiceImpl`, `SmvGenerator`, `NusmvExecutor`, and `SmvTraceParser`.

## Pipeline

```text
devices + environmentVariables + rules + specs
  + attackScenario + enablePrivacy
        |
        | POST /api/verify or /api/verify/async
        v
DTO validation + current-template semantic validation
  + capture referenced template manifests once
        |
        | known invalid references/domains -> 400/422, no task id
        v
SmvGenerator
  -> model.smv
  -> emitted-spec { specId, expression } mapping
  -> disabledRuleCount / skippedSpecCount / generationIssues
        |
        v
NusmvExecutor (bounded concurrency + timeout)
        |
        v
result parser + SmvTraceParser
        |
        v
VerificationResultDto {
  isAttack, attackBudget, enablePrivacy, modelSemantics, modelSnapshot,
  outcome, modelComplete, specResults, traces,
  disabledRuleCount, skippedSpecCount, generationIssues,
  checkLogs, nusmvOutput
}
```

The request needs at least one device and one specification. There is no `saveTrace`
input: parsed counterexamples are persisted automatically.

Known semantic mismatches, such as unknown actions, illegal enum values, non-signal API
conditions, and invalid template-7 reliability sources, are rejected before execution.
Residual generation failures fail closed:

- an unusable rule receives guard `FALSE`, is counted in `disabledRuleCount`, and gets
  an item-level `RULE_DISABLED` issue;
- an unusable specification is not emitted, is counted in `skippedSpecCount`, and gets
  an item-level `SPECIFICATION_SKIPPED` issue;
- neither case is silently treated as complete success.

## Outcome and completeness

`outcome` and `modelComplete` answer different questions.

| Outcome | Complete | User-facing meaning |
| :--- | :--- | :--- |
| `SATISFIED` | `true` | Every submitted specification was emitted, reliably parsed, and satisfied in the complete generated model |
| `SATISFIED` | `false` | The emitted subset was satisfied, but one or more submitted rules/specifications were omitted; this is not a full safety conclusion |
| `VIOLATED` | either | At least one reliably mapped emitted property is false; completeness still states whether the rest of the submitted model was represented |
| `INCONCLUSIVE` | `false` | No reliable conclusion could be formed, for example no emitted specs or mismatched parser output; this is neither satisfied nor violated |

`specResults[]` contains only emitted properties and identifies each by stable `specId`.
Its `expression` is the actual generated CTL/LTL input. The persisted
`SpecificationDto.formula` is only a preview/cache and is never parsed for verification.

The frontend must render every `generationIssues[].itemLabel` and `reason`. Counts alone
are insufficient because users need to know which automation or specification was not
part of the claim.

## Run assumptions

Every sync result, async task/detail, and full saved trace returns the run context rather
than relying on the current form:

- derived `isAttack` says whether compromised device-instance and automation-link behavior was modeled;
- `attackBudget` is the exhaustive upper bound or exact selected-point count;
- `enablePrivacy` says whether sensitivity propagation was modeled;
- `modelSemantics` distinguishes exact selection from budget-based exhaustive selection,
  records exact points when applicable, and defines attack effects actually present in
  this scene, the falsifiable-reading device subset, and trust/privacy propagation policies.
- `modelSnapshot` reports capture time and effective device/rule/spec/environment/template
  counts, with `templatesFrozen=true` proving that generation reused the manifests
  captured at acceptance rather than mutable current definitions.

Clients should reject or visibly downgrade interpretation if `modelSemantics` is absent
or contradicts the run flags, or if `modelSnapshot` cannot establish frozen templates.
The current frontend performs these checks. It compares current modelable input only for
runs submitted in the same browser tab; historical results explicitly say that the
current Board was not compared.

Board-backed AI verification and simulation first capture every persisted model-defining
collection plus device-template definitions in one per-user locked transaction. They
derive the request and referenced manifest set from that single snapshot and pass both
to the service layer, so a concurrent edit cannot produce a run assembled from Board
states that never coexisted. Direct REST callers instead define the device/environment/
rule/spec request snapshot in their submitted DTO; the service captures the referenced
templates once during request acceptance and uses that frozen set for the run.

## Sync, async, and simulation

| Mode | Endpoint | Result |
| :--- | :--- | :--- |
| Synchronous verification | `POST /api/verify` | Full `VerificationResultDto`; the completed result and any counterexamples are also persisted as one verification run |
| Asynchronous verification | `POST /api/verify/async` | Accepted `VerificationTaskDto`; use its server-generated `id` to poll |
| Transient simulation | `POST /api/simulate` | One `SimulationResultDto` trajectory; not persisted |
| Saved simulation | `POST /api/simulate/traces` | Full persisted `SimulationTraceDto` |
| Asynchronous simulation | `POST /api/simulate/async` | Accepted `SimulationTaskDto`; a completed task references `simulationTraceId` |

Async validation and template capture complete before task creation. Invalid requests
return no pollable task. Queue saturation returns 503. A successful submit returns the
task's persisted current status; acceptance never implies completion.
Task completion, worker failure, and user cancellation use conditional state transitions so
a terminal state cannot be overwritten by a late worker. Success and worker-failure writes
require the current worker id plus an unexpired lease; cancellation remains authoritative
without depending on worker ownership.

Accepted async verification and simulation tasks receive a renewable instance lease
before executor dispatch. Lease timestamps come from the database clock and cover both
queued and running work. Renewal runs in a short transaction that first obtains the task
row's pessimistic write lock and only then samples the database clock, checks the persisted
worker/status/unexpired lease, and flushes the extension. A time sampled before the lock,
including SQL statement-start `CURRENT_TIMESTAMP`, must never confirm a heartbeat because
the lease may expire while that statement waits. Atomic start and progress updates hold
the same row lock before sampling database time and binding it to their persisted worker-id,
status, and unexpired-lease predicates. Start confirmation is anchored to the monotonic time
sampled after that lock, before the update and commit response; lock waiting cannot revive
an expired original lease or consume a newly granted lease's confirmation budget.
Worker success/failure uses that same
ownership predicate, lease/start/terminal timestamps come from the database clock, and
terminal transitions clear ownership. Maintenance on every instance renews only its local
work and marks only expired active rows failed; it does not scan and fail all
`PENDING`/`RUNNING` rows at process startup. A lease whose expiry equals the sampled instant
counts as **expired**, in all three sweeps: every renewal, start, and terminal-commit guard
requires `leaseExpiresAt > currentTime`, so such a row can no longer renew, progress, or commit
a result, and treating it as live would leave work no worker can advance unreclaimed. Both sides
carry microsecond precision (`CURRENT_TIMESTAMP(6)` against Hibernate's `datetime(6)`), so the
equality is rare in practice; the reason it is specified is that thirteen ownership predicates must
not disagree about where the boundary lies. This preserves
healthy work during rolling deployments and prevents a delayed queued worker from starting
after ownership has been lost. Each local worker also tracks its last successful lease
confirmation with a monotonic clock: transient database errors are retried, but a worker
that cannot confirm ownership for the complete two-minute task TTL is interrupted.
Progress updates use the same worker-id and lease predicate. Terminal transactions acquire
the task row lock before sampling the microsecond database time and before writing linked
counterexamples or simulation traces, so a worker waiting behind cancellation/recovery cannot
publish stale evidence and a sub-second task cannot have `completedAt` before `createdAt`.
Synchronous verification and saved simulation use a distributed admission lease plus a
per-user database fencing epoch rather than a task-row lease. Admission advances the epoch
in its own short transaction before solver work. Their final persistence transaction locks
that epoch row in `beforeCommit`, requires the epoch and Redis lease to remain current, and
holds the row lock through physical commit. A newer owner therefore either supersedes the
old transaction before its fence or waits until the old commit completes before starting;
stale history cannot pass through a lease-check-to-commit pause.

Run history separates execution lifecycle from completed evidence. Task-list endpoints
exclude `COMPLETED`; completed verification rows are exposed through `/api/verify/runs`,
while saved simulation trajectories are exposed through `/api/simulate/traces`.
Counterexamples remain children of one verification run. Deleting that run deletes its
counterexamples atomically. `violatedSpecCount` records false properties;
`counterexampleCount` records retained traces with valid summary metadata and may be smaller.
History queries exclude state arrays, frozen requests, and solver output so database sorting
operates on summary-width rows. Verification summaries validate the violated-specification
snapshot and positive state count; simulation summaries validate step/count metadata,
generation diagnostics, and structured run context. Full detail reads remain authoritative:
they validate every saved state and bind each rule event to the exact indexed rule in the
server-internal frozen request before returning replay or fix evidence.

Simulation has no specifications and therefore no safety conclusion. `states` is one
possible trajectory through the generated model. `requestedSteps` is the requested
horizon; `steps` is the parsed trajectory length minus the initial state and may be
smaller. Timeout, interruption, execution failure, or no parsed states is an error, not
an empty successful simulation.

Simulation also returns `modelComplete`, `disabledRuleCount`, `generationIssues`, and
`modelSemantics`. A non-empty trajectory from an incomplete model must be labeled as
such.

## Counterexample contract

NuSMV prints internal identifiers and state deltas. `SmvTraceParser` converts them into
complete user-facing snapshots. A representative API state is:

```json
{
  "stateIndex": 2,
  "triggeredRules": [
    {
      "ruleIndex": 0,
      "ruleId": "42",
      "ruleLabel": "When hallway motion is detected, turn on the light"
    }
  ],
  "compromisedAutomationLinks": [
    {
      "ruleIndex": 0,
      "ruleId": "42",
      "ruleLabel": "When hallway motion is detected, turn on the light"
    }
  ],
  "envVariables": [
    { "name": "temperature", "value": "35" }
  ],
  "globalVariables": [
    { "name": "compromisedPointCount", "value": "2" }
  ],
  "loopStart": true,
  "devices": [
    {
      "deviceId": "hall_sensor",
      "deviceLabel": "Hall sensor",
      "templateName": "Motion Detector",
      "compromised": true,
      "state": "active",
      "mode": "SensorMode",
      "variables": [
        { "name": "motion", "value": "detected", "trust": "untrusted" }
      ],
      "trustPrivacy": [],
      "privacies": []
    }
  ]
}
```

The ordinary UI displays `deviceLabel`, rule labels, literal variable names, and the
`compromised` boolean. Canonical ids remain available only for identity/debug details.

### Parser boundaries

- NuSMV delta states are materialized into independent complete snapshots. An omitted
  value means unchanged, not unknown.
- Formal and simulation snapshots start at `stateIndex=1` and remain exactly contiguous;
  persisted detail reads fail closed on a missing, duplicate, zero, or gap.
- **A liveness counterexample is an infinite path, and its loop is the violation.** Templates 2, 5,
  and 6 assert liveness (`AF` / `AG(IF → AF THEN)` / LTL `G(IF → FG THEN)`), so NuSMV refutes them with
  a lasso: a finite prefix followed by a cycle that repeats forever without ever reaching the required
  state. The parser captures NuSMV's own `-- Loop starts here` marker as `loopStart: true`, and flags the
  trailing state that closes the cycle as `loopBack: true`. Both are absent for a finite path, which is
  every simulation and fuzz trace. Simulation shares this parser, so that absence is worth stating as a
  measurement rather than an assumption: NuSMV 2.7.1's `simulate -r -k N` + `show_traces` output is headed
  `Trace Type: Simulation` and prints no `-- Loop starts here` line at all, even for a model whose only
  transition is a self-loop. It does print later states with no variable lines — the same delta shape as a
  one-state cycle's closing state — which is why the per-step panel's "no observable changes" wording has
  to describe an unmoving step without implying the run stopped.

  **The closing state repeats the loop entry, not necessarily its own predecessor** — the distinction
  decides how a lasso is misread without the flags, and both halves were measured on NuSMV 2.7.1. A
  **one-state** cycle prints the closing state with no variable lines, so the delta merge reproduces the
  previous state exactly and playback shows a step where nothing moves (reads as a frozen animation or a
  truncated run). A **longer** cycle prints the deltas needed to return to the entry, so the closing state
  differs from its predecessor and playback shows an ordinary-looking step (reads as the path simply
  continuing). Consumers therefore cannot recover the flags by comparing adjacent states, and comparing
  against the loop entry is not available either where the state list is paged, as it is in `get_trace`.

  **The marker is not by itself evidence of a liveness violation, and clients must not treat it as such.**
  It describes the witness path NuSMV happened to find, not the property. Measured on NuSMV 2.7.1: a CTL
  `AG(motion → AX light)` counterexample (template 4) and an LTL `G(p)` counterexample both carry the
  marker, and the LTL trace carried it **twice**. So whether the cycle *is* the fault follows from the
  template, which the client knows; only templates 2, 5, and 6 may claim their cycle. Where several
  markers appear, the **last** one begins the cycle — that is what `loopBack` is computed against.
  Two positions the marker can take that a reader will not guess: it precedes the state it names, so
  when the loop entry is the *initial* state it precedes the first state line as well (`NusmvExecutor`
  retains it there rather than starting extraction at the first state), and CTL `AG`/`AG !` traces never
  carry it at all.

  The closing state is why this needs a field rather than an inference — in the one-state case above,
  the final step of a liveness violation plays back as one where nothing changes and the per-step
  change panel reports "no observable changes": an accurate diff of the state, and a complete
  misreading of the trace. Clients mark the whole cycle rather than one step, because no single state
  is at fault.

  **"The whole cycle" means every replay surface, not just the canvas.** A single-step violation index is
  `undefined` for these templates by construction, so any surface derived from it alone goes silent on
  exactly the traces this section describes. The step rail marked nothing for a liveness counterexample
  for this reason while the canvas emphasised every device in the cycle and the change panel explained
  the loop — the one surface that shows *where* in the path the failure lives showed only the playback
  cursor. A surface that marks a violation reads the cycle's step set, and labels it as a cycle rather
  than repeating a single-step word on each of its states — a distinction that is about meaning, not
  only space: the same word on several steps reads as several separate faults.
- **A safety counterexample's violation is its last state, including template 4.** Verification emits the
  **positive** specification: `SmvGenerator.buildSmvContent` passes a null `ParameterizationConfig`, and
  only that null forks to `specBuilder.build`. The negated form (`buildNegated`, `EF(...)`) is reached
  solely by the fix/parameterization strategies, which read its verdict as a satisfiability bit and parse
  no trace from it. So a client is always looking at NuSMV's counterexample to `AG(...)`, a path that
  *ends* where the property breaks — templates 1, 3, 4 and 7 all mark their final state.

  Template 4 (`AG(IF → AX(THEN))`) is the one worth stating explicitly, because reasoning about the
  negated form suggests otherwise: `EF(IF & EX(!THEN))` would end at the trigger with the fault in an
  unshown successor. That formula is **true** for a violating model, so NuSMV prints no trace for it and
  no user sees one. Measured on NuSMV 2.7.1 across 21 falsifying models of the positive form: the trigger
  sits at index *n*, the violating successor at *n+1*, and *n+1* is always the final printed state — the
  trace never stops at the trigger. This matches the bounded engine, which reports the same state
  (see [fuzzing-flow.md](fuzzing-flow.md#supported-finite-semantics)).

  One shape to know when reading such a trace by eye: where the violating successor differs from the
  trigger in no variable — a frozen actuator, a self-loop — the delta encoding prints its state header
  with zero value lines. That looks like truncation and is not; the materialization above carries the full
  valuation forward, confirmed against `show_traces -v 1`. Half the traces in the larger measurement pass
  (8 of 16) had that shape, so it is the common case rather than a corner one.
- Each device and environment variable carries frozen `BUNDLED`, `CUSTOM`, or `UNKNOWN`
  token provenance. Mixed environment providers and parser-global values are `UNKNOWN`;
  absent provenance makes the persisted trajectory invalid.
- Internal rule probes are converted to `{ ruleIndex, ruleId, ruleLabel }`. The generated
  probe name stays internal, while its zero-based frozen rule position is retained so
  duplicate or absent rule ids cannot make another rule appear executed. The position is
  never interpreted against a changed current Board. Persisted verification and simulation
  reads require the index to be in range and the optional id/label to match the frozen rule;
  inconsistent evidence fails closed before replay.
- Internal automation-link attack choices are converted to
  `compromisedAutomationLinks[]` using the same frozen rule identity.
- Internal `device.is_attack` is converted to `device.compromised`; it is not mixed into
  `variables[]`.
- The internal device-plus-link counter is exposed as
  `globalVariables[].name="compromisedPointCount"`.
- Generated environment aliases are mapped back to literal board names under
  `envVariables[]`.
- Generated rate and event-signal control values are filtered. A real template variable
  with a similar prefix is preserved because routing checks the manifest, not just text
  prefixes.
- Generated trust/privacy values are attached to variable or active state labels.
  Multi-mode state values are finalized before serialization.
- `TraceDto.violatedSpec` is the structured historical spec snapshot and
  `checkedExpression` is the exact emitted expression. Internal persistence JSON and
  ownership ids are not serialized.

## Trace playback

Verification traces and simulation traces share the animation timeline. Playback is a
read-only historical model snapshot:

- it does not mutate the current board;
- it renders the canonical run-captured `playbackScene` rather than the current canvas, so
  removed, renamed, moved, or replaced historical devices and rules retain their recorded visual
  positions and relationships. Frozen rule labels are used for trace evidence, and playback never
  resolves node presentation through the current template or rule catalog;
- a later Board edit makes the conclusion stale for the current model and withdraws repair actions,
  but it does not suppress this historical replay. **"Later" includes during the run**: a semantic
  edit made while a verification or simulation is in flight leaves the arriving result stale, because
  it already describes the model frozen at submission. The client cannot detect that by watching the
  displayed result — both run paths clear it before submitting, so there is nothing to flag for the
  whole duration — so each path captures a semantic-change counter at submission and compares it on
  arrival (`semanticSceneChangeCount` in `Board.vue`). A run read back from history was never in
  flight and is simply current;
- active edges come from backend `triggeredRules`, not frontend re-evaluation guesses;
- edges are static outside playback, and command-flow particles appear only for a
  backend-reported triggered rule whose delivery link is not compromised. Each selected
  transition remounts a finite flow animation, including consecutive transitions driven
  by the same rule; initial states and steps driven only by environment evolution or a
  device-internal transition deliberately keep rule edges still;
- the configured exact/exhaustive selection and actual `compromisedPointCount` are shown separately;
- compromised automation links are named by their rule labels and highlighted as broken
  links on the canvas;
- trust/privacy badges describe model labels, not authentication or access control.
- the selected transition's changed devices receive a canvas highlight, while explicit
  before/after state, runtime, compromise, trust/privacy, and environment-value facts
  appear in a separate draggable change panel; the node itself does not carry a tiny
  delta label that can be hidden by a compact layout. The panel remains present for the
  initial state and no-delta transitions, where it explains that no previous state or no
  visible value change exists. It also names backend-reported triggered rules and explains
  whether matching edges animate, remain still, or represent a compromised command drop.
  Device icons and state labels cross-fade directly to the selected model state instead of
  holding a stale value through a decorative flip. The node settle effect and change panel
  retrigger for each transition, and delivered-rule edge motion completes within the state
  dwell period. Unchanged devices remain still; reduced-motion preferences remove
  non-essential movement without hiding the result;
- simulation summaries distinguish model-state count, actual parsed transitions, and
  requested steps. A shorter parsed horizon is a visible limitation, not a successful
  full-length run.

The Board prevents overlapping trace playback, simulation playback, and recommendation
panels because all three use the same canvas emphasis surface.

## Fix bridge

A counterexample can be analyzed through fault localization and `fix`. Fix generation is
advisory: `fixable=true` means at least one candidate was forward-verified against the
stored model context, not that applying it to a later board is guaranteed.

Applying a fix is a separate confirmed mutation. The server recomputes the selected
strategy against the trace's exact frozen manifests, verifies it again, and rejects
board/template/environment/spec drift inside
the same per-user lock and transaction before writing. Stable `targetId` and
`adjustmentId` values locate user-visible adjustments; rule/condition indexes and
generated `param_*` names stay internal. Details live in [auto-fix.md](auto-fix.md).

## Related

- [Verification, simulation, and fix API](../api/verification.md)
- [NuSMV model generation](nusmv-model.md)
- [Specification templates](spec-templates.md)
- [Auto-fix](auto-fix.md)
