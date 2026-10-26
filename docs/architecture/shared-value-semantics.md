# Shared value semantics

This page is the single authoritative semantic model for a **shared value** — a quantity that exists
in the scenario rather than inside one device, such as room temperature, illuminance, or weather.
Every other layer (JSON schema, template validation, board assembly, NuSMV generation, the bounded
explorer, trace presentation, `modelSemantics`) implements this page and nothing else. If a layer
disagrees with this page, the layer is wrong.

Each rule below is tagged with its origin:

- **[MEDIC]** — defined by MEDIC §3.1 (ISSTA '23). The local paper text is quoted where it matters.
- **[EXT]** — a deliberate product extension beyond the paper.
- **[EXACT]** — executable semantics that mean exactly what the user declared.
- **[ABSTRACTION]** — a deliberate over-approximation, disclosed in `modelSemantics`.
- **[REJECTED]** — a shape the product refuses to model rather than guess at.

Verified against code on 2026-08-13. Source:
`backend/src/main/java/cn/edu/nju/Iot_Verify/component/nusmv/generator/`,
`component/template/DeviceTemplateNuSmvValidator.java`, `backend/device-template-schema.json`.
Provenance behaviour is pinned by `EnvironmentProvenanceCollectorTest`; label scope (§2, invariant 13)
by `NusmvEnvironmentPoolTest`.

## 1. Value identity and namespace

A shared value is identified by its **name**, in one flat scenario-wide namespace. Two devices naming
`temperature` mean the same physical quantity; that is the entire point of the concept. **[MEDIC]**
mirrors this: the paper generates "A Environment model for the variable `v`" — one model per value,
not per device.

The name is authored text. Generated NuSMV identifiers (`a_<name>`, `<device>.<name>_rate`) are
internal and never user aliases.

## 2. Who owns the definition

A shared value's **contract** is its type, domain, and natural-evolution interval. Every template
that participates in a shared value carries the full contract, and every participating template must
agree; disagreement is rejected at board assembly with a named mismatch. **[EXACT]**

This is a deliberate choice among three candidates:

| Candidate | Why not / why yes |
| :--- | :--- |
| Board Environment Pool owns the contract, templates reference it | **Rejected.** A template is a portable artifact that is imported, AI-generated, and validated with no board in scope (`DeviceTemplateServiceImpl` performs zero board reads). `WorkingStates.Dynamics` values are checked against the domain at persist time, so a template that carried no domain could not be validated standalone. |
| One template is the canonical owner, others reference it | **Rejected.** It makes template import order significant and leaves a scene broken when the owning template is deleted, replacing a visible disagreement with an invisible dangling reference. |
| **Every participant carries the contract; agreement is enforced** | **Chosen.** Each template stays independently valid and portable, and the redundancy is safe because it is *checked*, not assumed. Repetition is retained only because the invariant in §9 makes it verifiable. |

The Environment Pool owns the **runtime state** of a shared value — its current value, trust, and
privacy — not its contract. That split is why the pool table stores `value/trust/privacy` only.

### The scope of a security label

A shared value's trust and privacy labels are **scenario-wide**: one label per name, owned by the pool,
identical on every device that declares it. This follows §1 — if two devices naming `temperature` mean
one physical quantity, its provenance classification is a property of that quantity, not of each
reader. **[EXT]**

This needs stating because the *model* could express the alternative. Labels are emitted per device
(`<device>.trust_<name>`, `SmvDeviceModuleBuilder`), never as a pool-level `trust_a_<name>`, so
`applyEnvironmentPoolLabels` fans one pool label out into N per-device model labels. MEDIC does not
decide this for us: it places labels on "states and variables" (MEDIC §3.3) without indexing them by
device, so the scope is a product decision. The alternatives:

| Candidate | Why not / why yes |
| :--- | :--- |
| Each device instance labels the shared values it reads | **Rejected for now**, and the cost is real: a scene cannot say "the outdoor thermometer is untrusted while the indoor one is trusted", which is exactly the distinction a threat model wants. It is rejected because a label is not only a display attribute: it feeds MEDIC's propagation as a *source* (see [theory-sources.md](theory-sources.md), which explains why a variable's label is never a propagation target) and it is a specification subject in its own right. Two labels for one value make "was this value trusted?" ambiguous, and answering it would require naming a device in every label reference, including those that read the pool value rather than a reading. Reconsider together with per-instance falsifiability, not before. |
| Derive the label from the declaring templates and drop the pool's copy | **Rejected.** Templates already must agree on the default label (invariant 2), so the derived value carries no scene-specific information — and a scene needs to override it without editing a portable template. |
| **The pool owns one label; every declaring device receives it** | **Chosen.** One value, one provenance answer, and a scene-level override that no template edit is needed for. The fan-out is uniform by construction and pinned by invariant 13 rather than assumed. |

The fan-out keys off the device's *shared declarations*, not its narrower read-capability set, because
a label is not a reading: the device module declares `trust_<name>`/`privacy_<name>` for every declared
variable, so an affect-only declaration (§4) has a label to initialise even though it gets no value
mirror. Keying it off the capability set silently dropped a user's label edit for exactly the rows the
Environment Pool renders as editable.

Two consequences worth knowing before reading further. A per-device label for a shared name is
**[REJECTED]** at both writer boundaries rather than merged — `BoardStorageServiceImpl.validateNodeVariables`
and `NusmvRequestValidator.validateDeviceVariables` both name the pool as the place to supply it — so
this is a diagnosable `422` naming the field, not a silent overwrite. And the device dialog's runtime section renders
only when the template has modes *with* working states or at least one device-local variable
(`DeviceDialog.hasRuntimeFields`), so a template that is purely a shared-value reader has no per-device
runtime surface at all — 13 of the bundled ones, every single-reading sensor among them. For those the
Environment Pool is not merely the preferred place to edit a label but the only one.

Attack modelling is the sole place a label diverges across devices, and only for a value whose
declaration sets `FalsifiableWhenCompromised`: such an instance's `trust_<name>` is forced to
`untrusted` while its peers keep the pool's label. Compromising a device whose declaration omits the
flag moves no label. That is per-run selection, not a stored per-device label — see
[theory-sources.md](theory-sources.md) for why falsifiability is a per-device physical claim while the
label's resting value is not.

## 3. Type and domain

A shared value is **numeric** (`LowerBound..UpperBound`, ascending) or **discrete** (an explicit
`Values` enum; booleans use `["TRUE", "FALSE"]`). Exactly one form must be declared; an omitted
domain is **[REJECTED]** rather than defaulted.

**[MEDIC]** models only integers — "`V` is a finite set of integer Variables". Discrete shared values
are therefore **[EXT]**, and every rule about them below is a product decision that must stand on the
user's mental model, not on paper authority.

Clamping to the declared domain is **[EXACT]**: the domain is what the user said the value can be.

## 4. Capabilities: read and affect

Two independent booleans per (device, shared value):

| reads | affects | Meaning | How declared |
| :---: | :---: | :---: | :--- |
| yes | no | A sensor observes the value | `Reads: true` on the shared declaration |
| no | yes | An actuator changes it without observing it | `Reads: false` + listed in `ImpactedVariables` |
| yes | yes | Observes and changes it | `Reads: true` + listed in `ImpactedVariables` |
| no | no | Not a participant | not declared at all |

**[MEDIC]** supplies exactly this pair: Internal Variables are "variables that are supposed to be
sensed by the related sensors", Impacted Variables are "the environment variables that can be
affected by the devices".

*Read* capability governs two things together, and they may never diverge: whether the device gets a
`device.name := a_name` mirror in the model, and whether its rules and specifications may use the
value as a condition source. **[EXACT]**

No capability may be inferred from array placement, a missing field, a deprecated format, generator
behaviour, or a historical default. `Reads` is meaningful only on a shared declaration; on a
device-local variable it is **[REJECTED]** as a contradiction.

**Where that is enforced matters.** The template endpoint accepts a raw `JsonNode` and builds the DTO
with `treeToValue`, so bean validation never runs on it — the DTO's `@AssertTrue` guard is dead code on
the path both the REST client and the `add_template` AI tool actually use. A live call proved the gap: a
manifest omitting `Reads` was accepted with `200`, silently gaining read capability from a missing
field. The authoritative gates are therefore `device-template-schema.json` (a conditional `allOf`
clause per `IsInside` value) and `DeviceTemplateNuSmvValidator`, which restates the rule in language a
template author can act on rather than as a schema path.

The user-facing panel derives its label from the same flag: an affect-only declaration is shown as
*affects*, never as *reads*, because the generator emits no read mirror for it. The device dialog's
variables table marks the same declaration explicitly, because "environment variable" alone left a reader
unable to see why one template's `temperature` is offered as a rule condition and another's is refused —
and because the compromise-behaviour column has to say *label*, not *reading*, for a value the device never
reports (§4's falsification gate gives it no value to falsify).

**Condition-source eligibility is gated five times, once per writer boundary**, because a rule can
reach storage by three routes and each was independently permissive:

| Gate | Covers | Behaviour |
| :--- | :--- | :--- |
| `RuleBuilder.getDeviceVariables` | a person building a rule | an affect-only value is not offered |
| `ControlCenter.getAvailableKeys` | a person building a specification | an affect-only value is not offered |
| `BoardSemanticValidator.findVariable` | the assistant's rule/spec tools, before writing | refused with an actionable reason |
| `BoardStorageServiceImpl.conditionSourceVariable` | persist time — REST board endpoints, assistant tools, scene import | rejected with the variable named |
| `NusmvRequestValidator.internalVariable` | a verification/simulation request | rejected before generation |

A sixth read-capability narrowing exists outside that table, deliberately. A Transition `Trigger` is
template authoring rather than a condition source, so it has no writer boundary among the five above,
but it is still a *read*: `SmvModelValidator.buildLegalAttributeSet` excludes an affect-only name from
the legal trigger attributes. Without it a Trigger compiled into a comparison against `device.<name>`,
which for an affect-only declaration is declared and never assigned — an unconstrained variable NuSMV
re-picks every step. Measured before the check: a lamp declaring "switch off when illuminance >= 80"
fired while the real shared value sat at 20.

**Two lookups that must stay distinct, and one that must stay permissive.** Each of the gates above
is a *narrowing* of a capability-blind existence lookup that is kept beside it and left alone: those
answer whether a declaration exists at all — domain resolution, runtime overrides, contradiction
detection — where an affect-only declaration is a legitimate answer. Conflating the two questions is
what made this gap span four boundaries.

A specification may still reference an affect-only value's **trust or privacy label**
(`propertyScope: variable`). The device module declares `trust_<name>` and `privacy_<name>` for every
declared variable, so that reference is something the generated model really permits: asking about a
label is not reading the value. Narrowing it would make admission stricter than the model and refuse
a specification NuSMV would have decided.

The value branch is not merely "stricter than the model" — it forbids something different in kind.
`SmvMainModuleBuilder` emits the read mirror `device.<name> := a_<name>` only for a read-capable
declaration, while `appendInternalVariables` declares `device.<name>` regardless. So for an
affect-only value that identifier exists but is **never assigned**: a value condition on it would
compare an unconstrained free variable over the declared domain, not the shared value. Admitting it
would return `200` and then answer a question about something the device never observes, which is why
this branch fails closed while the label branch stays open. Two model facts, not one inconsistency.

### Two questions, and which one a specification asks

Because the mirror exists, a shared value has **two** identifiers in the model, and a specification
condition on one is a different question from the same condition on the other:

| `variableSource` | Compiles to | Asks |
| :--- | :--- | :--- |
| `environment` | `a_<name>` | Did this actually happen in the home? |
| `reported` | `<device>.<name>` | Is this what this device said? |

Outside attack modelling the two are provably equal, because the mirror is *defined* as the pool
value. Under compromise they diverge: a falsifiable declaration's mirror is free over its domain, so
the device can report a value the home never held. This is why `SpecConditionDto.variableSource` has
**no default** — presenting either as the author's intent is a false statement about what was
verified. A `variable` condition without it is refused on every path that **authors** a
specification — board storage's `addSpec` and `/board/batch`, the verify request validator, and
`ManageSpecTool` — and generation reports it as a skipped specification rather than guessing.

Whole-board revalidation is the deliberate exception. Device, rule, and layout writes re-check the
specifications *already in storage*, and demanding a reading there made one specification written
before the field existed block every unrelated mutation: adding a device failed with
`specs[0].aConditions[0].variableSource is required`, naming a specification the request did not
contain. Absence is already fail-closed where it decides an answer — the generator refuses to compile
it, the verify validator rejects the run, and the board blocks the run with an inline reason and badges
the specification unresolved — so a fourth refusal there bought nothing and cost the user every other
operation. An **invalid** value, and `environment` on a device-local declaration, stay refused wherever
they appear: those are reference errors, not an unanswered question.

The generator's `environment` branch inherits the read-capability gate rather than re-deriving it: it
resolves the key through the Reads-gated `getEnvVariables()`, so an affect-only declaration lands on the
existing "no value in the home to compare against" refusal instead of compiling against a pool value the
device never observes. Combined with the narrowing in §4, which rejects such a key at both writer
boundaries before `variableSource` is examined, the two questions are only ever asked of a read-capable
declaration. Widening that would mean deciding an affect-only value is a legitimate *specification*
subject — a product question, not a normalization detail. Separately, `environment` requires a shared
declaration: a device-local value (`IsInside: true`) has no pool identifier at all, so asking it is
refused with the declaration named.

The client carries the same "no default" rule rather than filling it in before the request: the
condition editor presents both readings for a shared value with neither preselected, offers only
`reported` for a device-local one, and names the chosen reading on every display surface (formula
preview, plain-language description, condition rows, counterexample verdicts). A stored condition
without a recorded choice renders as unresolved and blocks the run instead of being assigned a side
on load. The [portable scene contract](../api/board.md#boardbatchdto-scene-import-fields)
requires this field; older files that omit it are rejected because no guess preserves what
their specifications assert.

The asymmetry with rules is intended and load-bearing. A rule or a device transition is *inside* the
system and can only act on what a sensor reported, so it always compiles to the mirror. A
specification is an observer's assertion *about* the system, so it may read ground truth. Without
that asymmetry a spoofed reading would be both the question and the answer, and attack modelling
could prove nothing.

Its Environment Pool labels do reach the model, on the same scenario-wide terms as any other shared
value's — §2 owns that rule and the reason the fan-out keys off `sharedDeclarations` rather than the
narrower read-capability set. The affect-only half of it is pinned by
`NusmvEnvironmentPoolTest.environmentPoolLabelsReachAffectOnlySharedVariables`.

What such a label cannot do is *change*: see the third vacuity shape in
[theory-sources.md](theory-sources.md) — a variable label is frozen unless the variable declares
`FalsifiableWhenCompromised` and the run models an attack, so a property over one is decided at
`init`. That is a fact about the label model as a whole, not about affect-only-ness.

## 5. Natural evolution

A shared numeric value declares `NaturalChangeRate`, an integer interval constraining the per-step
change. **[MEDIC]** writes the environment self-loop as `v' - v ∈ [-1 + env.D.v, 1 + env.D.v]`, so
`[-1, 1]` reproduces the paper exactly and any other interval is **[EXT]** parameterization.

The interval means **exactly itself** — every integer in it, nothing added, nothing omitted.
**[EXACT]** An interval that excludes `0` is a per-step change that is mandatory *wherever the
declared domain leaves room for it*; one that includes `0` permits holding still anywhere. `0` alone
means no independent evolution.

The domain qualifier is load-bearing, because the declared bound wins over the declared rate. Each
candidate delta is clamped with `max(lower, min(upper, expr))`, so at a saturated boundary every
candidate collapses to the same value: with domain `0..10` and rate `[2, 5]`, a value of `10` yields
`10` for all four deltas and NuSMV *proves* `AG (v = 10)` — measured, not reasoned. No stutter is
injected into the interval; the value holds because the domain has no room left. Stating the rate as
unconditionally mandatory would make a provable model behaviour read as a generator bug.

Because the interval's span is a state-space cost, it is bounded by
`RequestLimits.MAX_NATURAL_CHANGE_RATE_SPAN`. A wider declaration is rejected at authoring and at
generation rather than silently narrowed — narrowing it would verify a model the author did not
declare.

Discrete shared values have no natural-change interval: there is no ordering to move along.

## 6. Device effects

A device's effect on a shared value comes from `WorkingStates[].Dynamics`, evaluated against the
device's **current** state. **[MEDIC]** puts it in the device model — "a Variable `env.D.v` will be
generated to describe how the environment variable `v` will be changed by `D`" — so the effect is
emitted as a `DEFINE` over the live state and applies in the same step the device is acting.
**[EXACT]**

- Numeric: `ChangeRate`, an integer added to the value.
- Discrete: `Value`, an in-domain assignment. **[EXT]**

A state with no matching `Dynamics` entry contributes no effect.

## 7. Composition, and what happens when nothing fires

Per step, for a **numeric** shared value:

```
v' = clamp(v + Σ(active device effects) + δ),  δ ∈ declared NaturalChangeRate
```

Summing concurrent device effects is **[MEDIC]**-faithful (`env.D.v` is additive) and **[EXACT]**.

For a **discrete** shared value the composition depends on who participates — this is **[EXT]**:

| Situation | Behaviour |
| :--- | :--- |
| No device declares it affects the value (purely exogenous) | Free choice within the declared domain each step. **[ABSTRACTION]** — a value nobody in the scene controls may do anything the domain allows. Disclosed as `UNWRITTEN_DISCRETE_VALUES_NONDETERMINISTIC_WITHIN_DECLARED_DOMAIN`. |
| Some device declares it affects the value, and one such effect is active | That declared value. **[EXACT]** |
| Some device declares it affects the value, and no effect is active this step | The value **holds**. **[EXACT]** — the same stutter-when-nothing-declared policy used for device-local variables. Free choice here would invent a change with no cause in the user's scene. Disclosed as `DEVICE_WRITTEN_DISCRETE_VALUES_HOLD_WHEN_NO_DECLARED_EFFECT_APPLIES`. |
| Two or more active effects assign the **same** value | That value. **[EXACT]** |
| Two or more active effects assign **conflicting** values | **[REJECTED]** at board assembly. See §8. |

## 8. Conflicting discrete writers

Two devices whose declared effects can be simultaneously active and assign different values to one
discrete shared value is a **modelling contradiction**, not a resolvable merge. There is no additive
composition for enums, so any resolution would be arbitrary.

Emitting sequential `case` branches silently resolved this by **device iteration order**: the same
scene produced `AX (airQuality = good)` *true* under one order and *false* under the other. That is
backend-specific behaviour the user cannot see, predict, or act on.

Such a scene is therefore **[REJECTED]** with both device names, the value, and both assignments. The
user resolves the contradiction — by changing a declaration or removing a device — rather than
receiving a verdict that depends on an internal ordering.

Numeric writers are never in conflict: they compose additively by §7.

## 9. Invariants

Checkable statements this model must satisfy. Each is enforced somewhere and tested.

1. **One namespace.** All participants in a name refer to one value with one contract.
2. **Contract agreement.** Participating templates declare an identical type, domain, natural rate,
   default trust, default privacy, and literal name casing; disagreement is rejected with a named
   mismatch (`EnvironmentDomainUtils.incompatibility`, reached from both board writes and generation).
3. **Capability is declared, never inferred.** No code path grants read or affect capability from
   placement, absence, or defaults.
4. **Read implies mirror, and only read implies mirror.** A device gets `device.name := a_name` if
   and only if it reads the value.
5. **Affect-only cannot be read.** An affect-only declaration is not a rule or specification condition
   source, at every writer boundary (§4). Its trust/privacy label remains referenceable, because the
   model declares one for every variable and a label is not the value.
6. **Exact interval.** The admitted numeric delta set equals the declared interval.
7. **Contemporaneous effects.** A device's effect reaches the value in the step it is acting.
8. **Additive numeric composition.** Concurrent numeric effects sum.
9. **No uncaused discrete change.** A discrete value with a declared writer changes only when a
   declared effect is active.
10. **No order-dependent outcome.** No verdict depends on device iteration order.
11. **Both engines agree.** NuSMV and the bounded explorer implement one transition relation.
12. **Disclosure.** Every abstraction in this page appears in `modelSemantics`, and the rule that
    applied to each individual value appears in that run's frozen provenance (§10).
13. **One label per value, on every declaring device.** The pool is the only writer of a shared
    value's trust/privacy, so all devices declaring the name carry identical labels in the generated
    model. A per-device label for a shared name is rejected, not merged (§2). The single exception is
    attack modelling, which forces one compromised instance's `trust_<name>` to `untrusted`.

## 10. How a stored run stays explainable

A verdict is only actionable if the user can tell *why* a value moved. Reading the current Board to
answer that is wrong: the Board may have changed since the run, so the explanation could contradict
the trace it claims to explain.

So each run freezes, per shared value, the rule this page assigned to it. That record is
`EnvironmentValueProvenanceDto`, carried on `modelSnapshot.environmentProvenance`, captured at the
model boundary before generation and persisted with the run. Its field-level contract lives in
[../api/verification.md](../api/verification.md#environmentvalueprovenancedto); this section owns
only the semantics it reports:

| Reported | Source in this page |
| :--- | :--- |
| `authorship` = `EXOGENOUS` | no submitted device declares the value in `ImpactedVariables` (§7 row 1) |
| `authorship` = `DEVICE_CONTROLLED` | exactly one submitted device declares it (§7 rows 2–3) |
| `authorship` = `COMPOSED` | several declare it — summing for numeric (§7), agreeing for discrete (§8) |
| `semantics` = `ABSTRACTION` | the exogenous discrete case below, and only that case |
| `semantics` = `EXACT` | every other combination |

Note that `EXOGENOUS` splits on type, and the split is the whole point of the tag: an exogenous
*numeric* value carries a declared `NaturalChangeRate`, so its movement is `EXACT`, while an
exogenous *discrete* value has no such rule and is therefore `ABSTRACTION`. Both are common — a
single temperature sensor produces the first — so a trace must name the cause in both cases. Showing
a bare `20 -> 21` with no cause is the defect this section exists to prevent, and
`SimulationTimeline.spec.ts` covers all four combinations.

Because a conflicting discrete scene is rejected at board assembly (§8), a `COMPOSED` discrete value
in a stored run is one whose writers agree. Provenance therefore never has to describe a winner, and
must not: doing so would tell the user that a verdict invariant 10 guarantees is order-independent
depends on ordering. `EnvironmentProvenanceCollectorTest` fails on that wording.

Editing the Board afterwards does not change a stored run's provenance, which is the point.

## 11. What is deliberately abstract

Only two things, both disclosed:

- A purely exogenous discrete value may take any declared value each step (§7). This is an
  over-approximation: it can produce counterexamples driven by an input nobody controls, which is
  correct for weather or an occupant but must be recognisable as such.

  This is **[ABSTRACTION]**, not **[EXACT]**, and the distinction is real: a user who declares
  `weather: {sunny, cloudy, rainy}` gets a model admitting `sunny → rainy` in one step, which they
  may not consider physically plausible. Three narrower alternatives were compared and rejected:

  | Alternative | Why rejected |
  | :--- | :--- |
  | Hold until an external event updates it | Needs an external-event model the product does not have. Inventing one would fabricate a cause the user never declared — the same defect as injecting a stutter into a numeric interval (§5). |
  | Let the user declare permitted transitions | Sound, and strictly more expressive. Rejected for now as authoring cost: it asks every user to model transition structure to describe a value, and most cannot say which transitions are realistic. Revisit if users ask for it. |
  | An opt-in "adjacent values only" mode | Requires a total order the domain does not have. `{sunny, cloudy, rainy}` has no defensible adjacency, so the mode would silently impose the authoring order. |

  Retaining free choice keeps the model sound (it never omits a transition the user's declaration
  permits) and keeps the cost visible instead of hidden: the value is labelled an external input
  before a run, and each stored run records the same rule in its provenance (§10). A narrower rule
  would need to come from the user's declaration, not from the product's guess.
- A numeric interval wider than `[-1, 1]` weakens the physical assumption relative to MEDIC. It is
  the user's declaration, so it is exact with respect to what they wrote, and `[-1, 1]` recovers the
  paper baseline exactly.

Everything else on this page is exact: it means what the user declared, or it is rejected.
