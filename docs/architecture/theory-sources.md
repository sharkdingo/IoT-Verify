# Theory sources

The modeling, verification, fix, and bounded-exploration semantics in this repo draw on published
algorithms. This page records which paper motivates each behaviour, which parts the product adopts,
and where it deliberately differs. The product contract is the explicit behavior documented here,
in the owning API/model documents, and in `modelSemantics`; a paper is not a silent override for a
different user-facing contract.

Read the cited section before changing generated-model semantics, then keep the formal checker,
bounded explorer, API contract, tests, and UI explanation aligned with the chosen product meaning.

Verified against code on 2026-10-22. Source: `backend/src/main/java/cn/edu/nju/Iot_Verify/component/nusmv/`,
`component/fuzz/`. Conformance is also pinned by `TheorySourceConformanceTest`, so a claim here that
the code contradicts fails the build.

## The four papers

| Paper | Venue | Owns | Local copy |
| :--- | :--- | :--- | :--- |
| **MEDIC** — Security Checking of Trigger-Action-Programming Smart Home Integrations | ISSTA '23 | Device/rule/environment FSM construction, trust & privacy propagation, attack model + attack intensity, CTL/LTL spec templates | `D:\组\MEDIC.pdf` |
| **Salus** — Systematically Debugging IoT Control System Correctness for Building Automation | BuildSys '16 | Counterexample fault localization and automatic fix strategies (§4–§5) | `D:\组\2993422.2993426.pdf` |
| **HAFuzz** — Temporal Specification Oriented Fuzzing for TAP Smart Home Integrations | ICSE '26 | Bounded candidate exploration: runtime-verification monitor over finite steps, seed selection, mutation | `D:\HAFuzz\icse26-26.pdf` |
| **智能家居物联网系统形式化模型建模与验证** (NJU undergraduate thesis, 2018) | — | Earlier FSM formulation this project's device/state modeling descends from | `D:\组\门神 FSM.pdf` |

The local paths are outside the repo and are not tracked. Anyone reviewing modeling semantics needs
their own copy; the citations below are precise enough to check against any copy of each paper.

## Where each behaviour comes from

- **FSM per device** — WorkingStates → states, InternalVariables → variables, APIs → transitions
  carrying a `Signal_Device_API` label. MEDIC §3.1, Fig. 1. Implementation:
  `SmvDeviceModuleBuilder`, `DeviceSmvDataFactory`.
- **Rule execution** — MEDIC §3.2, Def. 3.2, Fig. 3 introduces a two-state handling node
  (`Ready`/`Waiting`). IoT-Verify collapses that internal handling step: IF reads the current state and
  the command writes the target's next state, matching HAFuzz §3.3, Fig. 5 and the product's Immediate
  template. Implementation: `SmvMainModuleBuilder`'s rule branches and execution probes.
- **Environment model** — MEDIC §3.1, Fig. 2b defines a numeric shared value by
  `v' - v ∈ [-1 + env.D.v, 1 + env.D.v]`: the device effect is combined with a per-step
  `[-1, 1]` physical disturbance. IoT-Verify exposes that fixed interval as the required
  `NaturalChangeRate` parameter. `[-1, 1]` therefore reproduces the paper rule exactly; `0`
  explicitly disables independent natural change; another declared interval is a visible
  parameterized extension. The declaration is a **constraint on `v' - v`**, so the formal
  generator and the bounded explorer both admit *exactly* the integers in it — combined with the
  active device effect and clamped to the declared domain. **The interval is the whole meaning: no
  value is added to it and none omitted.** Two failure modes bracket this, and both actually shipped:

  - *Omitting interior values* is **unsound**. Emitting only `{lower, 0, upper}` made NuSMV *prove*
    `AG (v = 5 -> AX v != 6)` for a variable declared `[-3, 3]`, i.e. a `SATISFIED` verdict for a step
    the declaration permits.
  - *Adding a stutter* is **unfaithful**. Injecting `0` into every interval made
    `[-4, -2]` ("this tank always drains 2–4 per step") unstatable: NuSMV reported `AF (level = 0)`
    false and `EG (level = 10)` true, offering a trace where the mandatory drain never happened — a
    pseudo-counterexample the user cannot act on. MEDIC never re-adds a stutter either, because `0` is
    already inside `[-1, 1]`.

  So an interval **excluding** `0` means the value changes on every step *that is not clamped by the
  declared domain*; one **including** `0` means it *may* hold anywhere. A user who wants "drains 2–4,
  or holds" writes `[-4, 0]`, which is a strictly weaker claim and says so on its face.

  The clamp qualifier is load-bearing, not a caveat: the domain bound wins over the rate, so stating
  the rate as unconditionally mandatory would make a provable model behaviour read as a generator bug.
  This is still exact semantics rather than a verification abstraction — no stutter is injected; the
  value is held because the declared domain has no room left. The worked example and the span bound
  are in [shared-value-semantics.md](shared-value-semantics.md#5-natural-evolution).
- **Device effect timing** — Fig. 2b combines the device effect and the environment step in the same
  transition, so each device's `<var>_rate` is a `DEFINE` over its current state rather than a state
  variable. A stored rate is read unprimed while it is itself computed from the current mode, which
  delayed every effect by one step and made a device that started in an acting mode contribute
  nothing on the first transition. The bounded explorer derives the same effect from the live state.
- **Trust and privacy propagation** — `trust`/`privacy` labels belong to states and variables. Under
  MEDIC §3.3, Def. 3.3, Fig. 4, a target becomes untrusted only when every contributing trigger source
  is untrusted, while any private source makes the target private. Implementation:
  `SmvMainModuleBuilder.appendPropertyTransitions`, whose target is always a **state** label. A
  variable's label is a propagation *source* and never a target, which has a user-visible consequence
  recorded with the vacuity shapes below — follow that before writing a property over one.
- **Attack model** — a per-point *compromised* flag; a compromised sensor reports a random in-domain
  value with `trust := untrusted`, and a compromised actuator or automation link drops the command via a
  not-compromised transition guard. Compromise adds no new actuator state transition. MEDIC §3.4,
  Figs. 5–6. Implementation: `AttackSurface`, `SmvDeviceModuleBuilder`, `SmvMainModuleBuilder`.

  **Narrower than the paper in one declared way:** falsification is *capability-scoped*. A variable is
  falsifiable only when its manifest sets `FalsifiableWhenCompromised: true`, and `AttackSurface` admits a
  device to the reading-falsification surface only if it declares at least one such variable
  (`AttackSurface.java:112-120`). MEDIC treats any sensor reading as spoofable. The product requires the
  template author to say so, because "which readings an attacker can forge" is a per-device physical claim
  rather than a property of being a sensor — and a silent default would let an attack run report a surface
  the template never justified. The cost is real and worth stating: a template that omits the flag is
  unattackable, so its absence weakens an attack run rather than failing it. `nusmv-model.md` and
  `backend/CLAUDE.md` own the mechanics.

  MEDIC writes this as a boolean `attacked` per device. IoT-Verify names it *compromised* throughout, and the
  generated per-rule identifier is `iot_verify_automation_link_compromised_<n>`
  (`SmvConstants.AUTOMATION_LINK_ATTACK_PREFIX`) — because the attack surface is **points**, meaning device
  instances *and* automation links, not devices alone.
- **Attack intensity** — a `FROZENVAR` counter `iot_verify_compromised_point_count: 0..<surface size>`
  (`SmvConstants.NUSMV_COMPROMISED_POINT_COUNT`), bounded by an `INVAR` against the requested budget. That is
  MEDIC's `intensity = Σ d.attacked` with `attack.intensity ≤ v`, under this project's naming. MEDIC §4.2–§4.3.
  Implementation: the attack-budget handling in `SmvMainModuleBuilder`; the user-facing trace field is
  `compromisedPointCount`.

  Frozen rather than a running sum, which is a deliberate deviation worth naming: the count is fixed for a run,
  so NuSMV chooses the compromised set once instead of varying it per step. That is what makes an exhaustive
  budget search finite.
- **Spec templates** — the trustworthiness and privacy invariants
  `AG !(d.st.trust=untrusted AND d.st=True)` and `AG !(d.st.privacy=private AND d.st=True)`, plus the
  attack-extended forms. MEDIC §4.1, §4.3. Implementation: `SmvSpecificationBuilder`; see
  [spec-templates.md](spec-templates.md).
- **Fault localization and fixes** — Salus §4–§5. Implementation: `FaultLocalizer` and the strategies
  under `component/nusmv/fixer/`; see [auto-fix.md](auto-fix.md).
  [The authors' paper](https://seg.nju.edu.cn/uploadPublication/copyright/120715743892.pdf)
  §5.1's kitchen ventilation example is represented by the importable
  [ventilation scene](../examples/salus-ventilation-repair-scene.json): CO2 starts at 999 ppm,
  can rise by zero or one per step, and the running fan offsets the maximum rise. The original `>1000` rule is
  idle until after the limit is crossed; repair must search idle relevant rules and find an
  earlier threshold. The device action updates its next state, so its physical effect starts
  on the following step under this project's timing convention; the paper's illustrative
  `999` recommendation is not imposed on this model. `AcceptanceDemoScenarioNusmvTest`
  checks the actual repaired threshold with real NuSMV and requires every listed repair to
  satisfy both the CO2 limit and the response requiring the fan to run at or above 999 ppm.
  The original rules violate both requirements: a permitted zero-rise path can leave the
  fan off forever at 999 ppm. The response guards against a repair that merely disables ventilation.
  Its occupancy/heating case also exercises §5.2's `temp<=18` rule: add a positive occupancy
  condition, keep the rule, and verify heating remains available when the room is occupied.
- **Bounded exploration** — HAFuzz's runtime-verification monitor checking LTL violations within finite
  steps, used to guide seed selection. Implementation: `FuzzEngine`, `FuzzModel`, and the
  `component/fuzz/paper/` monitor FSM; see [fuzzing-flow.md](fuzzing-flow.md). HAFuzz findings are
  candidate evidence, never formal conclusions — budget exhaustion is not satisfaction.

HAFuzz §3.2–§3.4 is the first reference for exploration strategy changes within the supported
model. Implementation defects are checked against the paper's seed generation, monitor feedback,
predecessor guidance, selection, and mutation before introducing a different heuristic. The
reference artifact's incidental choices are not automatically paper requirements. Extensions
address an identified remaining limitation and retain an explicit comparison with the applicable
HAFuzz mechanism; comparisons must preserve the search question and distinguish the two
[initial-state policies](fuzzing-flow.md#relationship-to-the-hafuzz-paper).

The project's flattened form of Algorithm 1's condition average preserves every supplied
atomic occurrence in the monitor condition, including repeats. Flattening alone does not
authorize deduplicating operands: Boolean equivalence does not preserve their quantitative
weights. This is the project's condition-construction contract, not a claim that the paper's
native LTL-to-FSM tool preserves the original formula's syntax. Exact violation checks remain
separate from this guidance metric.

### Model-aware distance guidance (2026-10-27)

Board-initial numeric guidance is a project extension, not an additional HAFuzz conformance
claim. [Lin, Li and Althoff, *Model Predictive Robustness of Signal Temporal Logic
Predicates*](https://arxiv.org/pdf/2209.07881), §II-C–III, motivates incorporating system
dynamics into predicate robustness instead of relying only on geometric proximity. Their
method evaluates predicted trajectories and learns robustness with Gaussian processes;
IoT-Verify does not implement that probabilistic or learned metric.

[Bogomolov et al., *Falsification of Hybrid Systems using Symbolic Reachability and
Trajectory Splicing*](https://doi.org/10.1145/3302504.3311813) uses reachability constraints
to restrict a falsification search. The applicable design principle here is narrower:
exclude a numeric target only when a conservative bound proves it impossible at that
step. The project's per-step support and distance rules are defined in
[fuzzing-flow.md](fuzzing-flow.md#pipeline). A remaining target in an over-approximation
does not establish a reachable joint trajectory or a formal violation.

## Reproducing the Salus house experiment

The [Microsoft Research publication page](https://www.microsoft.com/en-us/research/publication/systematically-debugging-iot-control-system-correctness-building-automation/)
and authors' paper describe the experiment, but the checked public pages and GitHub
repository searches did not expose its complete device profiles, 29 ordered rules, initial
states, or participant records as of 2026-10-11. The
[CASAS data portal](https://casas.wsu.edu/datasets/) provides sensor observations. These
belong to the preference-learning experiment in §8, and are not a substitute for the
automation and policy inputs of the repair experiment in §7. A complete reproduction of
the original user study, its Pex comparison, and its Z3 timing measurements therefore
cannot be claimed from these inputs.

The importable [house reconstruction](../examples/salus-house-repair-scene.json) uses
§7's reported scale: 41 devices, 29 ordered rules, and six policies. Its six areas include
bedroom and living-room heating/cooling, indoor lights and daylight blinds, kitchen
extraction and a gas valve, RFID entrance access, a balcony door, and a kitchen-to-entrance-to-hall
alarm chain. Every device is referenced by automation or a policy; extra disconnected
devices do not inflate the scale. Temperature varies by `[-1,1]` per step with heater and
cooler effects; the open gas valve raises gas and extraction lowers it. Sensors for home
occupancy, room motion, sleep and RFID keep their selected initial state during each run;
the regression also tests the occupied, awake branch after repair.

The six policies reconstruct the paper's stated intentions, rather than unpublished
original formulas: bedroom temperature stays between 21 and 27; sleeping occupants have
neither indoor lights nor an open daylight blind; away lighting is off throughout the
house; unknown RFID leaves the entrance locked; absence leaves the balcony locked; and
kitchen gas stays below the danger threshold. Lighting and lock responses use the
product's next-state convention. The brightness policy conservatively forbids its active
sources, rather than inventing a lux calibration absent from the original inputs.

The regression also expands the ten lights into thirty, producing 61 devices and 49 rules.
The additional lights share their original occupancy inputs, and the sleep/away policies
include every corresponding actuator.

`AcceptanceDemoScenarioNusmvTest` checks discovery, successive application of parameter,
condition and removal repairs on the same scene, all listed alternatives, and all earlier satisfied
requirements after every repair. Additional alarm and monitoring response requirements
start satisfied and must stay satisfied; changing to home-present/awake inputs after
repair must still turn on the required lights. The two incorrect unlocking automations are
explicitly removed through the removal strategy. Every retained rule and submitted specification
must remain in the generated model. These are model-level reconstructions, not a replication
of the original hardware deployment or its human acceptance statistics.

The occupied branch also tests gas initially at its limit. With an open valve and stopped
extractor, the first physical step crosses that limit before a command takes effect; none
of the three rule strategies may claim a repair for this unavoidable initial transient.
With extraction already active, the same warning-level input must satisfy every safety
and response requirement, including the alarm chain and useful lighting.

## Where this project deliberately differs from or goes beyond a paper

These are intentional, not drift. Keep the list honest when adding more.

- Device templates are user-authored JSON schemas rather than vendor capability documents, so the repo
  validates manifests at persist time (`DeviceTemplateSchemaValidator`, `SmvModelValidator`) — a step
  MEDIC does not need.
- Rule execution collapses MEDIC's internal `Ready`/`Waiting` handling-node transition into one
  user-visible current-state-to-next-state step. This keeps verification, simulation, traces, fixes,
  HAFuzz path formation, and specification template 4 on one temporal convention.
- Numeric environment evolution parameterizes MEDIC's fixed per-step `[-1, 1]` disturbance.
  Every shared numeric declaration must state `NaturalChangeRate`: use `[-1, 1]` for exact
  MEDIC behavior, `0` for explicit stutter absent device effects, or another interval for a
  deliberate domain-specific widening. The interval is modeled exhaustively rather than as a
  shortlist of interesting values, so a wider interval is a genuinely weaker assumption instead of a
  different one. The generator never layers a second hidden `[-1, 1]` term on top of that
  declaration. An interval that excludes zero (say `[2, 4]`) is a per-step change that is mandatory
  wherever the declared domain leaves room for it — at a domain boundary the clamp holds the value
  instead; to allow holding still anywhere the user writes an interval containing zero (`[0, 4]`).
  Optional device-local
  numeric rates use the same convention as a project extension; they are not part of MEDIC's shared
  physical-environment equation.
- Specification templates 1–7 extend MEDIC's two primitive security templates with safety and
  reachability shapes. MEDIC §4.1 explicitly anticipates this ("More CTL templates ... can be defined
  and integrated into MEDIC for different requirements on demand").
- Generation is *observable*: a rule or spec that cannot be modeled is reported through
  `disabledRuleCount` / `skippedSpecCount` / `generationIssues` rather than silently dropped. The
  papers assume well-formed input.
- Auto-fix departs from Salus §5 in its repair scope, its acceptance rule across several
  specifications, and its listing of alternatives; each is stated once in
  [auto-fix.md](auto-fix.md#paper-alignment-boundaries).

## Conformance checked against the papers (2026-08-04)

Read alongside the implementation, and now **pinned by `TheorySourceConformanceTest`**, whose checks fail if a
claim below stops being true of the code, naming the paragraph that has become false. A dated conformance note is
otherwise the kind of assertion that rots silently: the paper does not change, the code does, and nothing fails when
they diverge. Verified conforming:

- **Salus §5.3 parameter refinement** — a solved value is moved toward the original by
  `refineToClosest` (`ParameterAdjustStrategy`): each iteration re-solves `¬ρ` with that threshold
  confined to `refinementWindow`, the values strictly closer than the current best
  (`|value - original| < distance(best, original)`), so every accepted value is a smaller edit than the
  one before. This does **not** prove a minimum edit: the pass is greedy, one threshold at a time with the
  others held at their solved values, and capped by `FIX_MAX_REFINE_ATTEMPTS` and the fix deadline. When
  the budget runs out the suggestion is the closest value verified so far.
- **Salus §5.2 condition candidates** — candidate conditions to add are derived from the violated
  specification's own conditions (`FixStrategyUtils`), not invented.
- **HAFuzz distance-guided search** — the explorer keeps the minimum-distance seed per round and
  persists both the requested and effective seed, so a finding is replayable. Its per-level weight
  is Algorithm 1 line 25's `2^(l_up-l) / (2^l_up - 1)`, and the composition
  `combinedDistance = Dist_graph - Dist_cond` is line 30 literally (the artifact's
  `DistMeasurement.java:152` returns `nodeDist - condDist`). The weights sum to exactly 1.0 in
  IEEE-754 for every level count in `[1, 30]`.

  **The level count is this project's choice, not the paper's or the artifact's.**
  `PAPER_SOLVER_LEVELS = 3` (`FuzzEngine`) instantiates a formula the paper leaves as the parameter
  `l_up` (§3.4.1, line 23) and never fixes; the reference artifact sets `DETECTION_LAYER_NUM = 1`
  (`DistMeasurement.java:25`), whose `powerMap` denominator is therefore `2^0 = 1`, not `7`. At one
  level the weight degenerates to `1`, so the artifact never descends a predecessor chain at all and
  the three-level descent is an extension *beyond* it. This paragraph previously claimed the artifact
  "obtains the same denominator by summing the powers used by all levels", which is false as written
  and is the kind of claim `TheorySourceConformanceTest` cannot catch: it greps the two `Math.scalb`
  substrings and so pins the formula's shape while leaving its instantiation unchecked.
- **FSM thesis ch.4 repair loop** — the ¬ρ search proposes candidate values and `forwardVerify`
  re-checks each against the real specification; a candidate it does not accept is excluded by an
  invariant on the next solve rather than retried. `forwardVerify` also refuses to confirm a fix whose regenerated
  model is incomplete (`disabledRuleCount`/`skippedSpecCount`), so a repair is never certified
  against a property that was never emitted.

  Complete emission alone does not prevent vacuous responses. Response templates `4`/`5`/`6`
  now receive an independent same-model `!EF(IF)` diagnostic: a false result witnesses a
  reachable trigger from some allowed initial state, including alternative attack choices.
  An unreachable antecedent leaves
  the original CTL/LTL property true but does not demonstrate a response. Automatic repair
  preserves every response antecedent that the original model could reach, including those
  of originally violated properties; missing evidence is inconclusive. This is an explicit
  product constraint beyond the paper's forward-verification truth check, documented in
  [auto-fix.md](auto-fix.md#forward-verification-fixstrategyutilsforwardverify).

  The distinction remains directional: making the forbidden state of a prohibition (`3`/`7`)
  unreachable achieves its purpose. It is not a response-trigger regression. The old away-mode
  example demonstrates the difference: deleting its sole unlock rule satisfies its Never property
  but empties its template-5 re-lock response. That deletion is therefore no longer offered as
  an automatic repair, while an explicit user edit still receives the unchanged property truth
  plus the unreachable-trigger diagnostic.
  **A third shape sits outside both, and it is structural rather than discovered: a property whose
  *subject cannot change*.** Trust and privacy labels on *variables* are propagation **sources**, never
  targets — MEDIC §3.3's relation drives the label of a rule's command target, and in this product that
  target is always a *state* label (`SmvMainModuleBuilder.appendPropertyTransitions`). A variable's
  label classifies the value's provenance, so it is emitted as `FROZENVAR` on sensors and as
  `next(d.trust_v) := d.trust_v` elsewhere, with one exception: the attack path sets
  `trust_v := untrusted` for a variable declaring `FalsifiableWhenCompromised: true`. That exception is
  gated on the flag alone and **never on read capability**, so an affect-only shared value is not a
  special case here — measured: `AG (light_1.trust_illuminance = untrusted)` is provable in the
  away-mode scene because that variable declares `FalsifiableWhenCompromised: false`, not because it
  declares `Reads: false`.

  The consequence is user-visible. A template 1/3/7 condition with `propertyScope: "variable"` conjoins
  a *constant* with the rest of the property, so a specification whose only condition is such a label is
  decided at `init` by the manifest and Environment Pool rather than by the automation. It is correctly
  decided — the identifier is declared, initialised and in scope, which is why admission stays
  capability-blind (`NusmvRequestValidator.validatePropertyReference`) instead of rejecting a formula
  `SmvSpecificationBuilder` emits successfully. But unlike the two shapes above this is a fact about the
  transition relation, not about the reachable state space: it is fixed before exploration begins and no
  repair can introduce or remove it. When explaining such a verdict, "no reachable violation" and "this
  subject never varies" are different claims, and only the first describes the automation.

  Thus a verified repair preserves known response-trigger reachability as well as property
  satisfaction. It still does not mean every authored requirement is useful: a trigger may
  already have been unreachable, a condition may describe a constant label, and reachability
  alone does not prove a desirable action. The [household entry example](../guides/default-template-scenarios.md#household-entry)
  pairs a visitor safety condition with explicit lighting and authorized-entry responses to
  make those useful behaviors part of the checked contract.
## Review discipline

- A test or NuSMV run confirms what the implementation does; paper conformance still requires reading
  the cited definition and comparing the complete transition, not one boundary example.
- A paper-inspired assumption that widens behavior must be represented in the product contract. Do not
  introduce undeclared nondeterminism merely because it is conservative for one analysis goal.
- When the product deliberately abstracts a paper mechanism, name both semantics and explain why the
  product convention wins, rather than describing a subset as an exact reproduction.
