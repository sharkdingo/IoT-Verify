# Automatic Fix

When verification finds a violation, the fix system localizes potentially responsible
rules. Callers choose which repair strategy to try; REST/AI callers that omit the list use
the three-strategy default order. Each offered candidate is re-verified against all specs:
the violated one must hold and none the original rules satisfied may break.

A suggestion is only advisory until the user chooses to **apply** it; applying writes the
repaired rules back to the board (see [Applying a suggestion](#applying-a-suggestion-fixstrategyapplier)).

API contract (`fault-rules`, `fix`, `fix/apply`) → [../api/verification.md](../api/verification.md).
Spec formulas → [spec-templates.md](spec-templates.md).

Verified against code on 2026-09-29. Source: `component/nusmv/fixer/` — `RuleFixer`,
`localize/{FaultLocalizer, RuleInfluenceScope}`, `strategy/{ParameterAdjustStrategy, ConditionAdjustStrategy,
RemoveRulesFixStrategy, FixAlternatives, FixStrategyUtils, FixStrategyApplier}`, `BoardSemanticFingerprint`,
`parameterize/{ParameterExtractor, CounterexampleInitialStateConstraints}`;
`service/impl/FixServiceImpl` (apply flow),
`component/aitool/verification/ApplyFixTool` (conversational adapter), and
`component/aitool/AiDestructiveActionGuard` (two-turn confirmation). Config keys
(`FIX_*`) → [../getting-started/configuration.md](../getting-started/configuration.md).

---

## Pipeline (`RuleFixer`)

1. **Fault localization** (`FaultLocalizer.localize`): identify which rules were
   triggered in the counterexample trace (a fast pass, no NuSMV invocation). This also
   backs `GET /api/verify/traces/{id}/fault-rules`. The persisted
   `TraceStateDto.triggeredRules` probe is the sole execution authority; localization does
   not re-evaluate current rule conditions or infer firings from coincidental state changes.
   Its required frozen `ruleIndex` selects one exact run-request rule, so duplicate or absent
   database ids and repeated labels cannot broaden the match. The frozen target API is
   resolved only to describe the recorded transition and detect simultaneous commands that
   write different target values to at least one shared device mode. Commands that update
   disjoint modes are independent and are not labeled as conflicts.
2. **Repair scope** (`localize/RuleInfluenceScope`): a rule-level cone of influence of the
   violated property. The property's devices are relevant; a device brings every environment
   domain it declares, and a domain brings every device that declares an impact on it; a rule
   whose command targets a relevant device is relevant, and everything it reads (condition
   devices, content device) becomes relevant; repeat to a fixpoint. The cone deliberately
   over-approximates, because missing a relevant rule would make a strategy report "no repair"
   for a repairable model. Fault rules are the fired rules inside the cone; a fired rule outside
   it cannot change the property's truth value, so it is neither reported nor searched. If no
   fired rule is in the cone, every strategy is `SKIPPED_NO_FAULT_RULES`. The repair scope that
   all three strategies search is the fault rules first, then the cone's dormant rules (a
   dormant backup automation can take over once the fired one is changed). When the property
   references no modeled device, the scope is unknown and the fired rules are kept unfiltered.
3. **Strategy attempts**: run the requested strategies in order. The default order is
   `parameter → condition → remove`: the first two implement Salus §5.1/§5.2, while
   `remove` is an IoT-Verify destructive fallback. A caller may override the list and order via
   `FixRequestDto.strategies`.
4. Each strategy lists the minimal **verified** `FixSuggestionDto`s it finds (see
   [listing alternatives](#listing-alternatives-fixalternatives) and forward verification
   below), and the user chooses one. Results accumulate into `FixResultDto`; `strategyAttempts`
   records a status and reason for every requested strategy, including those skipped
   before execution.

Before strategy search, `FixServiceImpl` checks the counterexample's source-generation
metadata. If any rule or specification was omitted, no strategy is run: the result has
`fixable=false`, an empty suggestion list, `sourceModelComplete=false`, warnings, and
`SKIPPED_INCOMPLETE_SOURCE_MODEL` for every requested strategy. Apply rejects the same
trace. A repair must not be certified against a counterexample from a reduced model.

The fixer also reuses the trace's complete per-run `attackScenario`. Candidate discovery
pins the first complete counterexample state, including device compromise flags and every
automation-link choice. Exact selections therefore stay fixed; for an exhaustive
`ANY_UP_TO_BUDGET` run, discovery reproduces the concrete attacker branch that produced
this counterexample while forward verification deliberately removes those candidate-only
`INIT` constraints and checks the original exhaustive budget. Automation links are
correlated by their required frozen `ruleIndex`, the exact zero-based position in the
submitted rule snapshot; mutable or non-unique rule ids are presentation metadata only.
Any candidate that removes or duplicates an explicitly selected automation-link rule is
ineligible because it would change that fixed attacker choice rather than repair the model
under the original scenario.

A deadline (`FIX_TIMEOUT_MS`, default in
[configuration.md](../getting-started/configuration.md)) bounds the strategy pipeline. It is checked
before each strategy and inside search loops; every NuSMV capacity wait and child process
also receives the remaining deadline and uses the smaller of that budget and
`NUSMV_TIMEOUT_MS`. A strategy that starts but exceeds the deadline is `TIMED_OUT`;
later strategies that never start are `SKIPPED_TIMEOUT`. Strategies that need spec
negation (`parameter`, `condition`) are skipped if no valid `violatedSpecIndex` is
available; `remove` does not require it
(`FixStrategy.requiresViolatedSpec()`). Unsupported strategy names are **rejected with `400`**
before the fixer runs (`FixRequestDto` `@Pattern`, `FixServiceImpl.SUPPORTED_FIX_STRATEGIES`), so
`SKIPPED_UNSUPPORTED` is unreachable in practice.
NuSMV process failures and incomplete or unparseable result sets are reported as
`FAILED_SOLVER_EXECUTION`, rather than as a completed no-result search. A failed search run excludes
nothing, so the next run would be the identical model and could only repeat the failure; each
strategy below says how its search moves past one instead of retrying it. The one exception is a
run the NuSMV concurrency cap (`NUSMV_MAX_CONCURRENT`) refused a permit, which says nothing about the
model. A search solve retries it within its attempt budget; forward verification and the
original-rules check retry it until the deadline. A NuSMV run killed by
the deadline or by cancellation is not a solver failure: it is part of `TIMED_OUT` and adds no
separate diagnostic, so one event is never reported with two contradictory causes. A finite candidate
limit that leaves unchecked assignments before anything was listed is `SEARCH_BUDGET_EXHAUSTED`;
this is independent of the wall-clock timeout. A proof a strategy reached before the deadline passed
keeps its status; which recorded outcome is reported when several apply is defined with the
statuses in [../api/verification.md](../api/verification.md).

## Listing alternatives (`FixAlternatives`)

Forward verification proves the submitted specifications, not an unstated preference such as
"keep this event trigger", so several formally equivalent repairs can differ in what the user
actually wants. A strategy therefore lists every minimal repair it verifies, up to five, smallest
change first, instead of returning whichever one its search order reached first.

- **Identity.** A repair is identified by its change set: the thresholds it moves, the conditions
  it removes or adds, or the rules it removes. The same thresholds at other values, or an added
  guard with another free value `Y`, are variants of one repair, not alternatives.
- **Minimality.** A repair whose change set contains a listed one edits more of the board without
  being needed. It is never listed. Each strategy also excludes every superset of a listed change
  set from its further search, so the budget goes to genuinely different repairs.
- **Completeness.** `strategyAttempts[].alternativesComplete` is `true` only when the strategy
  exhausted its search space and every candidate whose forward verification was inconclusive is
  covered by a listed repair; such a candidate might have been a repair. The listing limit, the
  attempt budget, the time share, or an error make it `false`.
- **Time share.** Once a strategy has its first repair, it looks for further alternatives for as
  long again as that repair took, but at least ten seconds, so the wait for the others stays
  proportional to the wait for the first answer. It never gets more than an equal share of the
  remaining `FIX_TIMEOUT_MS` among itself and the strategies still to run, so enumerating
  alternatives cannot starve a later strategy.
  A strategy stopped by its share with something listed is `VERIFIED`; only one that listed nothing
  is `TIMED_OUT`.
- **Pre-existing violations** are computed per repair, so alternatives can differ in them.

---

## Strategy 1 — parameter adjustment (`ParameterAdjustStrategy`)

Turns a rule's numeric threshold conditions into `FROZENVAR` parameters, then uses
NuSMV to solve `¬ρ` (the negated spec) for corrected values. Targets are the bounded
thresholds of the repair-scope rules, fault rules first. The search phases are solve-first:
every NuSMV call in them is one `¬ρ` check on the pinned counterexample, and only a value the
solver returns pays for a complete-model forward verification.

1. **One threshold at a time** (multi-threshold scope only, at most half of
   `FIX_MAX_ATTEMPTS`): each threshold becomes the only `FROZENVAR` while the others keep their
   original values. `¬ρ` holding (UNSAT) settles that threshold in one call; a returned value
   that fails forward verification is excluded and the threshold is solved again. Once a
   threshold is listed, the search moves to the next one, since its other values are only
   variants. One-value edits come first because they are the easiest for a user to judge. A
   [failed solve](#pipeline-rulefixer) moves on to the next threshold; the joint solve still
   covers it. The phase is skipped when a preferred range excludes some threshold's original
   value: that threshold must move, and holding it at its original would list repairs outside
   the range the user asked for.
2. **Joint solve**: all thresholds are `FROZENVAR`s together, which redundant rules need
   (moving either alone leaves the other able to reproduce the violation). A returned tuple
   that fails forward verification is excluded exactly, and a listed repair by an `INVAR`
   forbidding every tuple that moves at least its thresholds; the search then solves again.
   UNSAT means no tuple in the searched ranges is left that avoids the pinned counterexample, so
   the search is exhausted; the listing is complete unless a tuple forward verification could not
   judge is still uncovered ([completeness](#listing-alternatives-fixalternatives)). With nothing
   listed, a complete search is `ALL_CANDIDATES_REJECTED` if forward verification rejected some
   solved value, and `NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE` if the solver never returned one: no
   threshold values in the searched ranges avoid the pinned counterexample. A failed solve ends
   the search as `FAILED_SOLVER_EXECUTION`.
3. **Refinement toward the original** (`FIX_MAX_REFINE_ATTEMPTS`, a separate budget for each
   listed repair): each changed value is narrowed toward its original by `¬ρ` solves over a
   shrinking window. The original value itself is tried only when another threshold moved;
   otherwise it would rebuild the counterexample board and spend a full verification
   re-proving a violation that is already on record. A threshold refined back to its original
   value is no longer part of that repair. A failed solve ends that threshold's refinement, and
   the value already accepted is listed as it stands.

Refinement is a bounded preference for values close to the original, not a proof of the
closest verified value when its budget or the time share expires.

Eligibility is deliberately narrower than the paper's abstract value-substitution step:
the persisted condition must use `>`, `>=`, `<`, or `<=`, contain an integer value, and
resolve to a manifest variable that declares both `LowerBound` and `UpperBound`. Equality,
mode, state, and enum conditions are handled as condition-clause candidates rather than
parameter-adjustment targets. When no eligible target exists, the attempt is reported as
`SKIPPED_NO_PARAMETERIZABLE_VALUES` instead of looking like a failed solver search.

The parameterized discovery model uses the candidate-only replay and fail-closed negation
contract defined by the [NuSMV model](nusmv-model.md). Candidate generation also rejects
any model that disables a rule or skips a specification.

- `ParameterExtractor` reads solved numeric values from the `¬ρ` counterexample. A solver
  error is not an UNSAT: it never ends the search with "no repair exists".
- Every automatic-fix NuSMV run (`NusmvExecutor.executeRepairSearch`) passes `-df`, with or
  without a request deadline: search solves, forward verification, and the original-rules check.
  Search `FROZENVAR`s are unconstrained at init, so NuSMV's default forward reachable-state
  computation enumerates all their combinations; a joint solve over three 0..100 thresholds
  stalled past `NUSMV_TIMEOUT_MS` with it and finishes in about 2 s without it, with the same
  witness. The generated models declare no `FAIRNESS`, so that set is an optimisation only:
  verdicts are unchanged, though a printed counterexample may follow a different, equally valid
  path. Board verification does not use the flag.
- Every eligible condition is returned independently in `FixResultDto.parameterTargets`,
  even when no candidate passes forward verification. Each target is `{ targetId, attribute,
  relation, originalValue, lowerBound, upperBound, description }`, so clients can offer a
  preferred range after an unsuccessful first search rather than depending on a successful
  `ParameterAdjustment`.
- Candidate values are searched within optional caller-supplied
  `preferredRangeSelections[]`. Each selection is chosen from a concrete
  `ParameterTarget.targetId` and carries `{ targetId, lower, upper }`;
  `lower`/`upper` are inclusive 32-bit integer bounds. The API-facing `targetId` is an
  opaque selector scoped to the trace/fix context; it keeps zero-based rule/condition
  locators out of REST and AI-tool requests, and the parameter strategy matches it
  against the currently available adjustment targets.
  A selection counts as used once it matches an eligible target *and* that target was actually
  searched, regardless of whether the constrained search found a verified suggestion.
  `FixResultDto.unusedPreferredRangeSelections` therefore reports both a selection that matched
  no parameterizable condition and one whose range does not intersect the device's own limits —
  the latter was never tested, so calling it honoured would tell the user a constraint held when
  nothing exercised it; an accompanying diagnostic names the conflicting bounds.
  The Board shows one fixed search-range row per target, labelled with the rule text,
  condition context, attribute, and relation, so users never type internal locators. A row
  starts at the target's template bounds, which means "no preference", and only rows the user
  narrowed are sent. "Keep original" narrows a row to `[originalValue, originalValue]`,
  an explicit no-change constraint. A verified suggestion stays current until a row is
  actually narrowed; the footer's retry then runs the search with the narrowed rows.
  A settled outcome (a proof, an exhausted budget, or a `SKIPPED_*` precondition) offers no
  retry for the same input, because the fix works on the trace's frozen snapshot and would
  return the same answer. For parameter adjustment the retry comes back once a range row
  differs from the last request. A proof over the template ranges also hides the range rows,
  because narrowing cannot turn "no value works" into a repair. A proof within narrowed rows
  keeps them and says the values outside them were not checked. When the current strategy has
  nothing to apply or retry, the footer offers the next strategy that has not been tried yet.
- Bounded by `FIX_MAX_ATTEMPTS` (`¬ρ` solves in phases 1–2), `FIX_MAX_REFINE_ATTEMPTS`
  (refinement iterations per listed repair), and the overall `FIX_TIMEOUT_MS` deadline. Distance and
  refinement-window arithmetic uses `long` intermediates so the full signed 32-bit manifest
  domain cannot overflow.
- Emits API-facing `ParameterAdjustment` entries: `{ targetId, attribute, relation,
  originalValue, newValue, lowerBound, upperBound, description }`. Internal rule and
  condition positions remain inside the fixer and are not serialized to REST or AI callers.
  A strict `> upperBound` or `< lowerBound` result makes its rule unreachable and is labeled
  explicitly in the Board. Non-strict `>= upperBound` and `<= lowerBound` results still match
  one domain value and are not described as disabled.
- A candidate must also satisfy the Board's exact-duplicate rule invariant before it can be
  certified. Forward verification uses the same `RuleSemanticSignature` as persistence and
  rejects, without running NuSMV, an edit that would duplicate another automation; the
  parameter search then excludes that value and solves again.
- Witness extraction is covered by a real NuSMV 2.7.1 smoke test on minimal false
  EF/EG CTL models. CI must install NuSMV 2.7.1 for this check; local runs without
  NuSMV skip the smoke test. This confirms the core `FROZENVAR` output behavior for
  that version and environment, but it is not an exhaustive proof for every generated
  template shape.

## Strategy 2 — condition adjustment (`ConditionAdjustStrategy`)

Adds a boolean lambda guard to a rule and uses NuSMV to decide which conditions must be
adjusted. Internally, existing conditions can be `keep` or `remove`, and candidates can
be `add` or ignored; the returned suggestion filters out `keep` entries and emits only
actionable `ConditionAdjustment` entries: `{ action, attribute, targetType, description,
ruleDescription, deviceLabel, relation, value }`. Internal rule/condition positions and
the model device reference needed for an add operation are retained inside the signed
suggestion token and restored only after the server verifies that token during apply.

The lambda scope is the [repair scope](#pipeline-rulefixer). Candidate additions may be state,
mode, variable, or positive API-event conditions taken from the specification. For mode
and variable candidates with a declared enum or integer range, the policy supplies the
clause shape while an additional `FROZENVAR` supplies Salus §5.2's free value `Y`; the
selected concrete value is included in the signed suggestion and used by forward
verification and apply. State-tuple and positive API-event candidates retain the concrete
policy value because their persisted rule semantics are not a single scalar domain. Trust
and privacy conditions remain specification-only because the persisted rule language has
no corresponding trigger type.

Before the unrestricted lambda search, the strategy tries bounded deterministic
configurations. It first adds one policy-derived guard to a localized rule while retaining
every existing trigger, then tries removing one existing trigger while adding none. For
redundant rules issuing the same command, it also tries adding the same candidate-clause
shape to those rules together; this handles a dormant backup rule without asking NuSMV to
enumerate the full lambda product. Expanded-rule single additions remain available after
those higher-value probes. Each selected free guard may still solve over its declared `Y`
domain. A configuration that contains a listed repair is skipped, since it can only yield
supersets of it. Only after those configurations are unsatisfiable, fail complete-model forward
verification, or are covered does the strategy search unrestricted joint add/remove
combinations, where each listed repair adds an `INVAR` forbidding every assignment that makes
at least its changes. An unsatisfiable joint solve exhausts the search, which makes the listing
complete unless an assignment forward verification could not judge is still uncovered. With
nothing listed, a complete search is `ALL_CANDIDATES_REJECTED` when some assignment was solved and
then rejected, and `NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE` when none was. A
[failed run](#pipeline-rulefixer), including output that lacks part of the solved assignment, on a
deterministic configuration moves on to the next one, since the joint search still covers it; a
failed joint run ends the search as `FAILED_SOLVER_EXECUTION`. This
prioritized phase receives at most half of `FIX_MAX_ATTEMPTS`; the remaining budget stays
available to joint search, and removal probes that would necessarily empty a one-condition
rule are not generated. As in parameter coordination, command equality includes target,
action, content device, and content value.

Free-value candidates are deduplicated by clause shape (resolved device/domain, target
type, attribute, and relation), not by the policy's placeholder literal. Search exclusions
are semantic: when a candidate lambda is `FALSE`, its unused `Y` value is omitted from the
excluded assignment, preventing the attempt budget from being spent on equivalent disabled
candidates. Command `EndState` and incompatible concrete `StartState` values are removed
from a free candidate's domain; one unsafe policy literal therefore does not discard other
valid values for the same clause shape. Values that a rule the command triggers establishes are
removed the same way.

A candidate that forward verification rejects excludes more than itself (`CounterexampleLemma`).
Its forward-verification model carries [guard probes](nusmv-model.md#rule-semantics) reporting,
in every state of the rejecting path, each scoped rule's guard and each search condition. The
rejecting path is the target's own, or that of the first specification the candidate newly broke,
never a pre-existing violation. Another assignment whose scoped guards take the same values
wherever the rule could otherwise fire follows the same path, so forward verification would reject
it on the same specification. The strategy adds an `INVAR` excluding every such assignment; when
that is every assignment, the search space is exhausted, exactly as an unsatisfiable joint solve
exhausts it. The argument does not cover
trust and privacy labels, whose propagation reads the conditions themselves, so nothing is learned
from a specification that reads one (or from template `7`), nor from a path whose probes are
missing, non-boolean, contradict the reported guard, or report an operand that is not a single
literal. Only the exact assignment is excluded then. Learning changes how many candidates are
checked, never which repairs are listed.

The candidate filter rejects a condition that the rule itself brings about. That is a positive
state or mode condition satisfied by the declared API `EndState`, or an API condition asserting
the event, of the rule's own command, or of any rule that such an effect triggers, transitively.
That would be a consequence masquerading as a trigger (directly, `camera.state = taking_photo ->
camera.take_photo`; through a chain, `camera.state = taking_photo` on a rule that turns on the
lamp whose `on` event makes the camera take a photo) and can make the useful automation
unreachable while still making a safety property pass. The chain over-approximates: a rule counts
as triggered when one of its conditions is established, whatever its other conditions and
priority. Negative conditions and internal variables are never established. A
candidate on the command target is also rejected when it is provably false under every concrete
state allowed by the API `StartState`; wildcard start-state segments remain eligible instead of
being treated as contradictions. A rule with no meaningful condition adjustment is reported as
having no condition fix; the user should revise the property, adjust a genuine precondition, or
choose permanent rule removal.

Condition adjustment is not a hidden form of rule removal. It keeps the rule and its command and
must leave at least one valid trigger condition. Although an always-false trigger could resemble
removal at the level of one state transition, empty or synthetic trigger conditions are not a
valid persisted rule representation: the DTO rejects an empty condition list and SMV generation
fails closed for one. Permanent rule removal operates on the rule set itself, deletes the complete
automation, and therefore has a separate destructive confirmation and search strategy.

### Paper alignment boundaries

The implementation follows Salus §5 for fault-localized parameter/condition solving,
fixed-counterexample candidate discovery, exclusion of tried assignments, user-preferred
numeric ranges, and forward verification without the candidate search's counterexample-only
initial-state constraints. Within one API call a strategy enumerates minimal repairs by
excluding the supersets of each listed one, up to the [listing limit](#listing-alternatives-fixalternatives).
Condition adjustment's generalization of a rejected assignment from its violating path is an
extension beyond the paper, which excludes only the tried assignment itself.
Two further points go beyond the paper. The paper parametrizes the rules that share devices or
domains with the violated policy together with the rules on the violating transitions; the
[repair scope](#pipeline-rulefixer) follows command targets instead, so a fired rule that writes
nothing the property depends on is left out, and a dormant rule is searched only when it can reach
the property. The paper re-verifies a solved configuration against the violated policy under every
initial state; [forward verification](#forward-verification-fixstrategyutilsforwardverify) also
checks every other submitted specification and separates the ones the original rules already
violated.
The service does not persist an exclusion history across calls: re-running parameter
adjustment with a different range searches afresh rather than continuing the previous list.

The UI can protect an individual numeric parameter with "Keep original", which narrows its
range to the original value. A general "do not modify this app rule" constraint is not currently part of the
fix request contract; users can avoid the destructive strategy, review the exact signed
condition edits before apply, or edit the rule manually. This is a product boundary rather
than a claimed implementation of the paper's full rule-protection control.

Forward verification proves only the submitted formal specifications. It does not infer an
unstated preference such as "retain this event trigger" or "do not broaden this automation."
That is why several equal-size edits that satisfy the complete model are all listed, and the
user chooses between them; a formally verified edit can still be undesirable under an
unmodeled user intent. Acceptance scenarios must select the concrete edit they expect from the
listed options rather than take the first one, and users must review that signed edit before
apply.

## Strategy 3 — permanent rule removal (`RemoveRulesFixStrategy`)

Destructive fallback. Lists the minimal sets of rules to remove: it checks candidate sets
smallest first, in lexicographic order, regenerating the model without those rules and
re-verifying each. A set containing a listed one is never checked. Emits readable
`removedRuleDescriptions` so a user can review what will be permanently deleted;
internal rule positions are not part of the external contract. The product has no
persisted enabled/disabled rule state, so this action must never be described as
"disable" or imply that it can later be re-enabled. The candidate rules are the
[repair scope](#pipeline-rulefixer), fault rules first; its dormant rules are needed because a
lower-priority automation may take over only after the executed rule is removed. Under an exact attack
scenario, the strategy skips every removal set that would remove a selected automation-link
point or make a selected device cease to be behavior-changing. Request validation and every
fix candidate use the same exact-point-versus-attack-surface validator, so deleting the last
rule targeting a selected actuator cannot silently disable that actuator's attack variable.
Reaching `FIX_MAX_ATTEMPTS` while combinations remain is reported as budget exhaustion when
nothing was listed, and as an incomplete listing otherwise. Every combination is judged by forward
verification or the attack-scenario check, and no pinned-counterexample step rules any out
earlier. A complete search that listed nothing is therefore `ALL_CANDIDATES_REJECTED`.

---

## Forward verification (`FixStrategyUtils.forwardVerify`)

Every candidate fix — a modified rule set — is turned back into an SMV model and
re-checked against **all** specs before it is accepted. A candidate that would change the
fixed attack scenario is rejected. If generation disables any rule or skips any specification
(`FAILED_MODEL_GENERATION`), or NuSMV emits or returns a different number of results
(`FAILED_SOLVER_EXECUTION`), nothing was checked, so the candidate is left unjudged: it is
neither listed nor counted as rejected, and the listing cannot be
[complete](#listing-alternatives-fixalternatives) while it is uncovered.

A candidate is accepted when every specification holds, or when the **violated (target)
specification holds and every specification still false was already false for the original
rules**. A fix repairs one counterexample; requiring an unrelated, already-broken property to pass
as well would reject every candidate on a Board with more than one violation. So the original
rules are checked on the same forward-verification model the first time a candidate repairs the
target while other specifications still fail, that verdict is reused for the rest of the request,
and a candidate that breaks a property they satisfied is rejected. The remaining pre-existing
violations travel with the suggestion as `preexistingViolations[]` and are shown beside it
([response shape](../api/verification.md)), so "verified" never hides a broken property.

The relaxed rule applies only when every verdict is attributable to a specification id: NuSMV
results are matched to emitted specifications by expression. With any positional guess, blank,
placeholder, or duplicate id, an unidentifiable target, or a failed baseline check, only a
candidate under which every specification holds is accepted, and any other is left unjudged
rather than rejected. A failed baseline is not retried and adds a diagnostic saying so. Two
baseline outcomes are not failures and are not kept: a capacity refusal, which is retried, and a
run cut off by the deadline or a cancellation, which is reported through the strategy's
`TIMED_OUT` status and which a later strategy's time may still complete.

This is why a returned `FixSuggestionDto` means the proposal passed
the complete generated model used by that fix attempt; it remains a model-level result,
not a guarantee about unmodelled physical behavior. The ordinary UI therefore presents
this state as **passed recomputation in the current complete formal model**, rather than
as an unqualified "verified solution".
This second check removes the counterexample-replay constraints used to discover the candidate,
but retains the Board/template initial assignments of the ordinary model. It therefore covers
every modeled execution reachable from the authored starting state, not every arbitrary value in
each declared legal domain. This is the product's concrete form of Salus §5's required second
verification pass.
For `EXACT_POINTS`, generation first recomputes the candidate rule set's attack surface
and rejects it unless every selected device remains behavior-changing and every selected
automation-link rule id still occurs exactly once.

---

## Result shape

`FixResultDto`, `FixSuggestionDto` and the `strategyAttempts[]` statuses, including which
fields ordinary UI localizes and which are English diagnostics, are owned by
[../api/verification.md](../api/verification.md).

---

## Applying a suggestion (`FixStrategyApplier`)

`/fix` only *offers* verified suggestions; nothing is written to the board until the user
applies one (`POST /api/verify/traces/{id}/fix/apply`, handled by
`FixServiceImpl.applyFix`). Because a suggestion is computed against the trace's
verification-time snapshot, apply cannot trust that the board still matches — so it
**never trusts unsigned client data** and checks the exact signed proposal plus the current
model snapshot before persisting.

**The server signs the exact suggestion.** Every verified `/fix` proposal receives a short-lived
HMAC token bound to the authenticated user, trace, strategy, complete user-visible proposal,
preferred ranges, expiry, and all hidden operation locators (parameter and condition positions,
the internal device reference for condition additions, and remove-rule positions). Apply submits that displayed proposal
and token. Any edit, replay in another context, or expired token rejects with `400`; otherwise the
same proposal is applied. This removes the second expensive strategy search and prevents apply
from silently choosing a different valid suggestion than the one the user reviewed.

The assistant's `apply_fix` tool uses the same boundary. Its first call verifies the exact
signed suggestion and returns a no-write impact preview. The session-scoped confirmation
guard stores that complete request for 15 minutes, so the confirmed call does not reconstruct
security-sensitive suggestion data from truncated model history. A later explicit confirmation
consumes the request once and calls `FixService.applyFix`; it does not call a lower-level rule
mutator. Confirmation expiry/mismatch stops before the service, while signature expiry and all
current-model drift checks still run inside the service. Fuzz findings remain in their separate
bounded-exploration domain and cannot be passed to either formal-fix tool. A preview that cannot
be serialized, or that exceeds the configured chat tool-result limit, is removed from confirmation
state rather than leaving an unseen action available.
Once the mutation service starts, an unclassified admission or settlement failure is treated as an
unknown mutation outcome and requires a rule refresh before retry; the specialized apply-preflight
`503` remains the explicit no-write exception.

**Drift guards** (all reject with `400` unless noted) ensure the earlier verification evidence
still describes the model being changed:

- **Source-model completeness** — apply rejects traces whose verification disabled any
  rule or skipped any specification. The user must resolve generation warnings and
  verify again before asking the system to certify a repair.

- **Frozen-template replay and drift** — the trace stores the exact parsed manifests used
  by verification and one explicit `BUNDLED` or `CUSTOM` model-token source for every
  captured device. `/fix` rebuilds from that saved set, never from whichever versions are
  current. The versioned snapshot's manifest keys and device-source keys must exactly match
  the saved verification request; a missing, unknown, or mismatched source is persisted-data
  corruption (`500`) and stops the operation rather than being downgraded to `UNKNOWN`.
  Apply first compares current manifests with the saved
  set: a confirmed difference blocks with `400`; an unavailable repository comparison
  is reported as unknown and blocks with retryable `503`. `/fix` remains usable against
  the frozen model but adds an explicit warning for either confirmed drift or an
  unavailable comparison, so the degraded applicability is never silent.
  Apply-side `503` responses proven to occur before the write use the specialized mapping
  defined in the [API error mapping](../api/overview.md#error-and-status-codes); clients must not
  infer a no-write result from an unclassified proxy or infrastructure `503`.
- **Board-rule drift** — the server's internal rule/condition positions are relative to
  the snapshot; apply aligns snapshot and current rules by index + an **order-preserving**
  fingerprint and rejects if rules were added/removed/edited/reordered, so a stale index
  never edits the wrong rule.
- **Spec/device/environment drift** — a spec-, device-, or environment-pool-only edit
  touches neither rules nor templates. Apply compares a canonical
  **semantic fingerprint** (`BoardSemanticFingerprint`) of the trace snapshot against the
  current board — not raw-JSON equality: both sides run through the same normalization
  (device names canonicalized via `DeviceNameNormalizer`, effective variable/trust/privacy
  values derived from the same manifests NuSMV uses, values de-quoted) so an untouched board
  matches its model-boundary normalized snapshot instead of misfiring. Environment
  variables, including variables that are only affected by devices, are compared as the
  board-level pool; missing required pool values use the same default merge as
  verification. Omitted internal enum/numeric variables use the
  generator's effective defaults. If the current board no longer
  builds a valid model it fails **closed**, distinguishing a genuinely changed/invalid
  board (`400`, "re-run verification") from an infrastructure error that leaves drift
  unconfirmable (`503`, "retry later").

All final drift checks use one complete current semantic snapshot containing devices,
the Environment Pool, rules, specifications, and exact template manifests. That snapshot
is captured **inside the same per-user write lock + transaction** as the save (read →
check → apply → write is one atomic critical section), so the checks cannot themselves
mix different Board moments and a concurrent save cannot slip between a check and the
write.

The signed apply entry also runs under the per-user formal-operation admission. Its
commit fence is registered from inside the board-write transaction, which prevents an
expired or superseded lease from committing a repair after another formal operation has
claimed the user.

**Per-strategy effect** (`FixStrategyApplier.apply`): `parameter` overwrites the target
condition's value (and relation); `condition` adds/removes conditions; `remove` permanently deletes
the flagged rules. Parameter and condition fixes regenerate each touched rule's
`ruleString` from the persisted typed conditions and command so board lists, history, AI
context, and scene export do not keep showing the pre-fix text. A `condition` fix that
would leave a rule with **no** trigger
conditions is rejected — and `ConditionAdjustStrategy` already excludes such solutions
during the search, since an empty-condition rule is fail-closed in NuSMV (never fires) and
so could otherwise verify yet be un-appliable (`RuleDto.conditions` is `@NotEmpty`).
Parameter and condition searches generate NuSMV-only FROZENVARs such as `param_r0_c0`
and `lambda_r0_c0`, and condition adjustment's forward verification defines guard probes
(`iot_verify_guard_probe_<n>`). These prefixes are
[reserved device-id prefixes](data-authority-model.md#environment-pool): a device id may not start with one. Refused at board admission
(`validateGeneratedMainNamespace`) and again per request
(`NusmvRequestValidator.rejectFixGeneratedPrefix`), so the clash surfaces when the board is
saved rather than when a fix is later requested. During
parameterized model generation, `SmvMainModuleBuilder` checks them against the active
`MODULE main` namespace (device instances, generated `a_<environmentName>` variables,
the internal compromised-point counter, automation-link choices, and rule probes) and fails generation rather
than emitting a colliding model.

When applying a condition fix, model-boundary NuSMV `varName` references are translated back to
the raw `DeviceNode.id` from the current board snapshot before persistence. If a reference cannot
be mapped, the transaction fails closed and no rule is written; the internal model identifier is
not returned as a user-facing validation error.

The response (`FixApplyResultDto`) returns the signed `appliedSuggestion`,
`verificationEvidenceReused=true`, before/after rule counts, the full persisted rule list, and
`canUndo`/`canRedo`. Apply never repeats the strategy search, so reused evidence is the only basis it
can report; the client rejects the whole response unless `verificationEvidenceReused` is `true`,
`canUndo`/`canRedo` are exactly `true`/`false`, and `appliedSuggestion` is the alternative the user
submitted, compared field by field without its token.
The localized UI derives its success explanation from these structured fields instead
of displaying the backend's English `message`; it states both the all-submitted-spec
scope and the unmodelled-real-world limitation.
Full request/response field tables and the exact `400`-vs-`503` semantics are in
[../api/verification.md](../api/verification.md).
