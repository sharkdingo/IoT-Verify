# Specification Templates & Validation

How a `SpecificationDto` becomes a CTL/LTL formula, and the P1–P5 model-validation
rules run before SMV generation.

Request field contract for specs → [../api/verification.md](../api/verification.md).
Modeling pipeline → [nusmv-model.md](nusmv-model.md).

Verified against code on 2026-07-24. Source:
`component/nusmv/generator/module/SmvSpecificationBuilder.java`,
`component/nusmv/generator/SmvModelValidator.java`,
`frontend/src/assets/config/specTemplates.ts`.

---

## The seven templates

`SmvSpecificationBuilder` selects a CTL or LTL structure by `templateId`. Six are CTL;
`templateId = "6"` (Persistence) is the only LTL one. Each spec has three condition
groups — `aConditions` (A), `ifConditions` (IF), `thenConditions` (THEN).

The shape is strict in both the backend builder and the frontend `requiredSides`
configuration:

| ids | Required groups | Forbidden groups |
| :--- | :--- | :--- |
| `1`, `2`, `3`, `7` | non-empty `aConditions` | `ifConditions`, `thenConditions` |
| `4`, `5`, `6` | non-empty `ifConditions` and `thenConditions` | `aConditions` |

### CTL templates (`templateId != "6"`)

| id | Name | Positive formula | Semantics |
| :--- | :--- | :--- | :--- |
| `1` | Always | `AG(A)` | A holds globally |
| `2` | Eventually | `AF(A)` | A eventually holds on all paths |
| `3` | Never | `AG !(A)` | A never holds on any path |
| `4` | Immediate | `AG((IF) -> AX(THEN))` | Right after IF, THEN holds in the next state |
| `5` | Response | `AG((IF) -> AF(THEN))` | After IF, THEN eventually holds on all paths |
| `7` | Untrusted-labelled event safety | `AG !(body)` where `body` = A conditions + each condition's resolved untrusted-label term | A protected A condition must not hold while its propagated control-source label is `untrusted`. For an automation target, MEDIC propagation makes that label untrusted only when all actual trigger sources were untrusted; for a composite mode-state tuple, any participating mode-state label can taint the tuple. |

### LTL template (`templateId == "6"`)

| id | Name | Positive formula | Semantics |
| :--- | :--- | :--- | :--- |
| `6` | Persistence | `G((IF) -> F G(THEN))` | After IF, THEN eventually holds persistently |

### Counterexample search (negated forms)

**A verification run checks the positive formulas above, not these.** `SmvGenerator` emits the
negation only when a `ParameterizationConfig` is supplied, which is the automatic-fix search
(ActFeedback §5) asking whether a candidate repair still admits `¬ρ`; an ordinary run takes the
`else` branch and NuSMV reports its own counterexample for the positive form. This matters when
reasoning about where a violation sits in a trace: the shape you get is NuSMV's counterexample to
`AG …`/`AF …`, not a witness path for the negation. For reference, the negated forms in
`generateNegatedCtlSpec` / `generateNegatedLtlSpec`:

| id | Negated formula |
| :--- | :--- |
| `1` | `EF(!(A))` |
| `2` | `EG(!(A))` |
| `3` | `EF(A)` |
| `4` | `EF((IF) & EX(!(THEN)))` |
| `5` | `EF((IF) & EG(!(THEN)))` |
| `6` | `LTLSPEC F((IF) & G F(!(THEN)))` |
| `7` | `EF(body)` |

### Bounded exploration eligibility

The HAFuzz-inspired counterexample explorer does not replace these CTL/LTL formulas.
Its finite-path monitor supports only the safety templates whose violations have a
finite witness: `1` (a state where A is false), `3` (a state where A is true), and `4`
(an IF state followed by a non-THEN state). Templates `2`, `5`, and `6` need liveness
reasoning beyond a bounded prefix; template `7` needs trust-label propagation. The
exploration API reports those templates as ineligible instead of treating them as
satisfied. See [fuzzing-flow.md](fuzzing-flow.md).

---

## Safety template (id 7) detail

`buildSafetySpec()` injects the matching untrusted control-source term for each
A-condition. Attack selection is not used to exclude a condition from the property:

```smv
-- input: aConditions = [{ deviceId: "fan_1", targetType: "state", key: "FanMode", value: "auto" }]
CTLSPEC AG !(fan_1.FanMode=auto & fan_1.trust_FanMode_auto=untrusted)
```

A `variable` A-condition with `variableSource: environment` produces a property with **two subjects**, by
design: the value term is the shared pool value `a_<key>` while the label term stays the selected device's
`<device>.trust_<key>`, because a trust label is emitted per device and no pool-level `trust_a_<key>` exists.
So the property reads "the home's value held while *this* device's label was untrusted". The device is
therefore load-bearing even though it does not appear in the value half. Under two devices declaring the
same key, which one you name changes what is proved **only in an attack run**: the Environment Pool is
the single writer of a shared value's labels, so outside compromise every declaring device carries the
same one (invariant 13 in
[shared-value-semantics.md](shared-value-semantics.md#9-invariants)), while attack modelling forces the
compromised instance's label to `untrusted` and leaves its peers alone. Both formula previews render this exactly — earlier they
reused the value target and displayed `controlSource(Environment."<key>")`, naming a label the model never
declares.

```smv
-- input: aConditions = [{ deviceId: "sensor_1", targetType: "variable",
--                         variableSource: "environment", key: "temperature", relation: ">", value: "30" }]
CTLSPEC AG !(a_temperature>30 & sensor_1.trust_temperature=untrusted)
```

The same formula shape is emitted with attack modeling on or off. When attack modeling
is on, compromised falsifiable readings can make the trust predicate untrusted and are
therefore included in the safety check; there is deliberately no
`device.is_attack=FALSE` escape clause. The selected attack budget is enforced once by
the main-module invariant, not duplicated in individual properties.

For an environment-variable A-condition whose literal key is `varName`, the
SMV value expression uses `a_varName` and the injected trust term uses
`deviceVarName.trust_varName`. If the real template variable name itself starts with
`a_`, that prefix is part of the literal name, so `key: "a_temperature"` generates the
SMV value identifier `a_a_temperature` and the trust key `trust_a_temperature`.
The same literal-key rule applies to `trust` and `privacy` conditions: generated
prefixes are not stripped from user keys. `key: "trust_temperature"` targets a real
property key named `trust_temperature` and therefore generates
`device.trust_trust_temperature`.

---

## Condition → SMV expression

Each `SpecConditionDto` maps by `targetType`:

```
state    → deviceVarName.ModeName = stateName
mode     → deviceVarName.ModeName OP stateName   (key is the concrete mode name; value must be legal in that mode)
variable → decided by the condition's own `variableSource`, never inferred from whether the key is shared:
           variableSource=environment → a_varName OP value            (the value in the home; requires a shared declaration)
           variableSource=reported    → deviceVarName.varName OP value (what this device reported)
           absent → the specification is skipped, with a reason (no default: the two differ under compromise)
api      → deviceVarName.apiName_a OP TRUE/FALSE   (only =, !=, IN, NOT_IN; authoring helpers default omitted value to TRUE)
trust    → deviceVarName.trust_{key} OP value      (key is literal; no generated-prefix stripping)
privacy  → deviceVarName.privacy_{key} OP value    (key is literal; no generated-prefix stripping)
unknown  → InvalidConditionException  (fail-closed; no guessing)
```

Conditions in a group are joined with `&`. `IN` / `NOT_IN` take a set value: enum-like
mode/variable/API/trust/privacy values may be comma-, semicolon-, or pipe-separated,
while multi-mode `state` values use semicolon inside each tuple and therefore separate
tuples with comma or pipe (`home;idle,sleep;idle`). `deviceId` is the canonical model
`varName` derived from the board node id; display labels and template names are not
identity fallbacks.

### Explicit omission behavior

- If a condition cannot be built (unsupported relation, empty value, unresolvable key,
  unsupported targetType), the spec is omitted from NuSMV emission. It increments
  `skippedSpecCount` and adds a `SPECIFICATION_SKIPPED` item to `generationIssues`
  with the specification label and an actionable reason. It is never replaced by
  `CTLSPEC FALSE`, because an always-false placeholder would manufacture a violation
  and counterexample for a property that was not actually checked.
- A privacy spec with `enablePrivacy = false` is rejected by
  `validateNoPrivacySpecs` before execution. The builder's defense-in-depth path also
  omits it and records the same structured issue instead of emitting a property.
- Specs with an unsupported `templateId` throw `InvalidConditionException` during
  generation and are recorded as `[spec-skipped]` warnings. Empty A/IF specs are also
  skipped explicitly. Both paths increment `skippedSpecCount` and surface in verification
  `generationIssues` plus technical `checkLogs`; they are neither silently satisfied nor
  reported as violated.
- `SpecificationDto.templateId` is also constrained at the DTO boundary to `"1"` through
  `"7"`; AI recommendation prompts and validators enforce the same enum before data reaches
  persistence or SMV generation.

### Attack budget and vacuity

The builder does **not** inject the attack budget or an `is_attack=FALSE` filter into a
spec's antecedent (either could hide relevant attacked paths or cause vacuous truth).
The attack budget lives in `main` instead:

```smv
MODULE main
FROZENVAR
    iot_verify_compromised_point_count: 0..(EFFECTFUL_DEVICE_COUNT + RULE_COUNT);
INVAR iot_verify_compromised_point_count <= 3;
```

The generated name is internal. Public requests express the upper bound through
`attackScenario.mode=ANY_UP_TO_BUDGET` and its `budget`, and traces expose the actual
branch count as `compromisedPointCount`. Only device instances with a declared
falsifiable reading or an incoming rule command are device points; logical automation
command-delivery links are separate points. The invariant is an upper bound;
it includes every modeled selection from zero through the budget.

---

## Model validation — P1–P5

`SmvModelValidator` runs before SMV text is generated. Failures abort generation.

| Rule | Checks | On failure |
| :--- | :--- | :--- |
| P1 | `Trigger.Attribute` is in modes + internalVariables, and normalized `Trigger.Relation` is one of `=`/`!=`/`>`/`>=`/`<`/`<=` | `illegalTriggerAttribute` / `illegalTriggerRelation` |
| P2 | API/Transition `StartState`/`EndState` format & semantics: multi-mode → semicolon segment count equals mode count; single-mode → no semicolons; state values must be legal for their mode | `invalidStateFormat` |
| P3 | Same `(mode, stateName)` has consistent trust/privacy across WorkingStates | `trustPrivacyConflict` |
| P4 | trust values ∈ {`trusted`,`untrusted`}, privacy ∈ {`public`,`private`} (case-insensitive, normalized); applies to instance-, template-, and content-level | `SmvGenerationException` (`[INVALID_PROPERTY_VALUE]`) |
| P5 | A shared environment variable (`IsInside=false`) has consistent range/enum values across templates | `envVarConflict` |

P4 describes defense in depth at the low-level generator boundary. External template imports
first pass the canonical JSON Schema, which requires lowercase manifest literals and performs
no case normalization.

### Generator input behavior

The low-level generator follows the same fail-fast semantic contract as board, REST, AI,
fix, and import callers. An explicit device-local or shared-environment initial value is
never replaced with the first enum member, numerically clamped, assigned an invented
`0..100` domain, or omitted after a validation failure. Unknown/duplicate environment
entries, values outside declared domains, local variables without a domain, malformed
natural-change rates, and signal APIs without a representable state change fail model
generation. Only an *omitted* valid local initial value may use the template's declared
default or documented nondeterministic initialization.

Every generator entry requires an explicit `AttackScenarioDto`. `NONE` permits only an
omitted or zero budget, `ANY_UP_TO_BUDGET` requires `1..50`, and `EXACT_POINTS` derives
its size from `1..50` selected points. The service layer additionally rejects an exhaustive
budget above the current behavior-changing attack-point count.

### Generation-time fail-closed (not part of P1–P5)

- Rule-condition parse failure (missing device, empty/unknown attribute, unsupported
  relation, empty value, empty `IN`/`NOT_IN` list, illegal API-signal relation/value)
  → the rule guard becomes `FALSE`, a `[rule-disabled]` warning is emitted, and
  `disabledRuleCount` increments.
- Rule/command/spec references use canonical device ids only. Unknown or non-canonical
  references are rejected by service validation before generation; direct builder calls
  fail closed or throw `DEVICE_NOT_FOUND` depending on the generation phase.
- Transition triggers referencing an environment variable are rewritten to `a_<attr>`
  (not `device.<attr>`) during generation, avoiding references to undeclared internal
  variables.

---

## Logic-integrity checklist

_Migrated from the former `NuSMV_Module_Documentation.md` §12._

The following chains are closed loops in the current backend implementation:

1. **Input-constraint loop**: DTO validation
   (`@NotNull/@NotEmpty/@Pattern/@Min/@Max/@Size`) plus generation-time fail-closed
   behavior (rule/spec condition parse failures are never silently passed). `@NotEmpty`
   replaces `@NotNull` on the device list (rejects empty lists), `@Size(max=10000)` bounds
   chat content, and `@NotNull` covers every `@RequestBody` in `BoardStorageController`.
2. **Template loop**: catalog templates (in resources) are seeded into the DB first, then
   read at runtime by `userId + templateName`. If a template is missing, `loadManifest()`
   returns `null`; the caller `buildDeviceSmvMap()` collects all missing devices and
   ultimately throws `SmvGenerationException.multipleDevicesFailed()`. Custom-template
   creation runs a NuSMV probe-generate pre-check: InternalVariable/ImpactedVariable names
   must be legal identifiers and not reserved words; `Contents[].Name` and each
   `InternalVariables[].Values[]` entry are validated the same way, because they too are
   emitted verbatim; mode/state names are sanitized automatically at generation time; normalized identifiers of different kinds must not
   collide (Mode vs InternalVariable vs ImpactedVariable). InternalVariable and
   ImpactedVariable may share a name only when the InternalVariable is an environment
   variable (`IsInside=false`), never when it is local. `loadManifest()` classifies
   exceptions precisely: `BaseException` is re-thrown as-is, a JSON parse error →
   `MANIFEST_PARSE_ERROR`, and anything else → `TEMPLATE_LOAD_ERROR`.
3. **Modeling loop**: `DeviceSmvDataFactory` → `SmvModelValidator (P1–P5)` →
   `SmvDevice/Main/SpecificationBuilder` produce a complete `model.smv`. Mode/state names
   are sanitized centrally via `sanitizeSmvToken()` (spaces / illegal characters / leading
   digit / case-insensitive reserved-word escaping); InternalVariable/ImpactedVariable
   names are **not** sanitized at generation time (they are cross-referenced in many
   places, and partial sanitization would break `.equals()` matching) — instead illegal
   values are strictly rejected at persistence time. `toVarName()` provides parallel
   defense for device IDs; `computeIdentifiers()` applies leading-digit guarding and
   reserved-word escaping to `varName`, the module-name prefix (base, from templateName),
   and the module-name suffix. Trust/privacy land via a three-layer loop of "entry
   normalization + validation + re-normalization at emit".
4. **Execution loop**: `NusmvExecutor` supports batch verification and interactive
   simulation, with timeout (configured centrally by `NusmvConfig.timeoutMs`, `@Min(100)`
   startup validation, overridable via the YAML `${NUSMV_TIMEOUT_MS:120000}` environment
   variable), a concurrency gate, busy returns, and stdout/stderr handling.
5. **Result loop (verification)**: per-spec result parsing, counterexample parsing, trace
   persistence, task status and progress (progress persisted to the DB with a three-tier
   fallback chain: memory → DB column → terminal-state inference), and unified sync/async
   error semantics.
6. **Result loop (simulation)**: trace parsing, `steps` vs `requestedSteps` reconciliation
   (`steps = states.size() - 1`, which may be less than `requestedSteps`), optional
   persistence, and async task lifecycle management. Cross-instance lifecycle safety:
   `completeTask` completes only `RUNNING` tasks, while `failTask` fails only
   `PENDING`/`RUNNING` tasks. These atomic conditional updates eliminate TOCTOU races and
   keep terminal states immutable.
7. **Observability loop**: `model.smv` / `request.json` / `output.txt` / `result.json`
   (the main verification and simulation paths) are available for replay and debugging;
   the cancel early-exit path must be diagnosed together with the task status.
8. **Numeric-bounds loop**: `next()` candidate expressions for both environment and
   internal variables uniformly clamp with `max(lower, min(upper, expr))`, covering the
   boundary branches (WITH-rate / no-rate) and the TRUE branch, preventing arithmetic
   results from exceeding the NuSMV VAR declared range. Attack falsification also stays
   inside the same declared range. Which values are falsifiable is explicit in required
   template field `InternalVariables[].FalsifiableWhenCompromised`; API presence does not
   infer it.
9. **Fix loop (Fix Pipeline)**: `FixServiceImpl` → `RuleFixer` → three strategies
   (parameter/condition/rule removal) chained. A global deadline is controlled by
   `FixConfig.fixTimeoutMs` (default 300s, `@Min(1000)`); `FixContext.isExpired()` checks
   the deadline at the strategy-loop entry, the `forwardVerify()` entry, and inside each
   strategy loop; after timeout the summary appends "(fix timed out; results may be
   partial)". Template-drift detection: `DeviceTemplatePo.updatedAt`
   (`@PrePersist`/`@PreUpdate`) is compared against `trace.createdAt`, and on drift the
   summary appends a WARNING. Known limitation: soft timeout — after the deadline, at most
   one in-flight NuSMV call may still run (bounded by `nusmv.timeout-ms`).

Runtime prerequisites still required:

- MySQL, Redis, JWT, LLM, CORS, thread-pool, and NuSMV runtime settings are centralized in
  [../getting-started/configuration.md](../getting-started/configuration.md), including the
  production startup guards.
- The current user's templates are initialized and consistent with the `templateName` in
  the frontend request.
- The `device_templates` table gains a `(user_id, name)` unique constraint; confirm there
  is no duplicate data before the first deployment.
