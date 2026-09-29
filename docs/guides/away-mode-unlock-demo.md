# Presenter Walkthrough: "Nobody Home, Door Unlocked"

A 10–12 minute live demonstration built on one relatable defect: a convenience
automation that can leave the front door unlocked while the house is empty.

Scene file: [`../examples/default-away-mode-unlock-scene.json`](../examples/default-away-mode-unlock-scene.json).
Scene semantics and expected counts: [default-template-scenarios.md](default-template-scenarios.md#away-mode-unlock).

Verified against code and real NuSMV 2.7.1 on 2026-08-08. Every number below is pinned by
`AwayModeUnlockSceneNusmvTest`; if that test fails, this walkthrough is wrong.

---

## Why this scene

The other bundled scenes name their defect (`Unsafe conflicting rule: …`) or pair obviously
dangerous devices, so the audience sees the answer before the tool finds it. This one does
not. All three automations are individually reasonable, and a reviewer reading them one at a
time will approve all three. The failure exists only in their composition — which is the
argument for formal verification over code review and testing.

The three automations:

1. `When nobody is home, arm the entry alarm`
2. `When porch motion is detected, unlock the front door for convenience`
3. `When porch motion is detected, turn on the porch light`

Rule 2 is the defect. Nobody wrote "unlock the door when the house is empty"; they wrote
"do not make me find my keys", and forgot that the porch sees motion whether or not the
motion is you.

The scene declares its own `Occupancy Sensor` device type rather than reusing a bundled one.
That is deliberate and worth one sentence on stage if anyone asks: `occupancy` is declared as
a **shared environment** reading (`IsInside: false`, `Reads: true`), so a resident can
actually come and go during a run. A device-local variable would be frozen at its initial
value for the whole run, which silently turns "require someone to be home" into "this rule
can never fire" — a repair that disables the rule it claims to keep. The second regression
test in `AwayModeUnlockSceneNusmvTest` exists to keep that trap closed.

One consequence for the demo: **import the scene rather than rebuilding it live.** A device type is
created from a manifest JSON. The Templates section offers an import and a **download of the
canonical schema** (`GET /api/board/templates/schema`, saved as `device-template-schema.json`), and
its own subtitle is "Import and validate device templates" — so authoring a type means writing that
JSON against the schema, not filling in a form. The assistant's `add_template` tool and
`POST /api/board/templates` accept the same JSON, so `Occupancy Sensor` *can* be created without a
file. Devices, rules and specifications are all hand-authorable once the type exists.

If someone asks whether the missing form is a gap, the schema download is the answer to give: the
product's chosen path is schema-assisted JSON, which a JSON-Schema-aware editor turns into
completion and inline validation. That is a deliberate trade, not an omission — and the manifest's
shape is why it is a reasonable one: multi-mode `WorkingStates` are semicolon-joined tuples, `APIs`
take partial start/end tuples, `Dynamics` has mutually exclusive `Value`/`ChangeRate`, and `Reads`
is required-or-forbidden depending on `IsInside`. It stays a real barrier for a non-technical user;
it is not a hole in the design.

All three paths funnel into `BoardStorageServiceImpl.addDeviceTemplate`, which owns every
authoritative check — name rule, canonical schema, the NuSMV-specific template validation, the
per-user cap and the duplicate-name conflict. The assistant tool validates the raw JSON and the
Modes/InitState/WorkingStates trio *before* calling it, which is early failure so the model gets a
specific error in the same round, not a stricter standard. So the paths cannot diverge: this is
unbuilt, not broken.

## Before you start

- [ ] Backend running with NuSMV configured, MySQL and Redis up.
- [ ] Board is empty or holds throwaway content — import is a **full-scene replacement**.
- [ ] Run `mvn -DforkCount=0 -Dtest=AwayModeUnlockSceneNusmvTest test` once. It takes
      seconds and proves the machine you are about to present on produces these numbers.
- [ ] Decide in advance whether you are doing Act 2 (attack). It adds ~3 minutes and one
      hard question you must be ready to answer — see [Act 2](#act-2-optional--one-spoofed-sensor).
- [ ] If you plan to open the AI panel, **send one throwaway message first.** There is no
      availability pre-flight anywhere in the client: the panel opens unconditionally, and a bad
      key, an unreachable endpoint, or a wrong base URL all surface only once the stream is
      already running. The placeholder key is the likeliest cause — `IOT_VERIFY_OPENAI_API_KEY`
      defaults to `your_api_key_here` and the boot-time guard only refuses to start under a
      `prod` profile — but the reason to warm it up is the missing pre-flight, not that one
      default.
- [ ] Know that the **synchronous** run has no progress bar and no cancel button; both are
      gated on the async path. NuSMV's own ceiling is 120s. This scene verifies in seconds, so
      sync is the right choice for the demo — but if you want a visible progress bar while you
      talk, run it async instead.

## Act 1 — the defect nobody would catch by reading (7 min)

**1. Import and read the scene aloud.** Board → Import Scene → the file above. The preview
must show `5` devices, `3` environment values, `3` rules, `6` specifications. Confirm the
replacement.

Read the three rules out loud and ask the audience to spot the bug. They usually cannot,
which is the point of the next 60 seconds.

**2. Show the properties in plain language.** Six specifications, four of which you expect
to hold:

| # | Template | Plain meaning | Baseline |
| :--- | :--- | :--- | :--- |
| 0 | Immediate (`4`) | Nobody home ⇒ the alarm is armed in the next state | Satisfied |
| 1 | Immediate (`4`) | Porch motion ⇒ the porch light is on in the next state | Satisfied |
| 2 | Never (`3`) | Nobody home **and** the front door unlocked — never | **Violated** |
| 3 | Response (`5`) | If the door is ever unlocked while nobody is home, it must eventually re-lock | **Violated** |
| 4 | Untrusted-label safety (`7`) | The door is never unlocked under an untrusted control source | Satisfied — *vacuously*, see below |
| 5 | Always (`1`) | The door's lock state stays private | Satisfied — *trivially*, see below |

Say the number: **4 satisfied, 2 violated**, zero disabled rules, zero skipped
specifications. A tool that reports everything red is broken; a tool that reports everything
green is not being asked anything.

Properties 2 and 3 are deliberately two different questions about the same worry. Property 2
asks "can this ever happen?"; property 3 asks "if it happens, does the home recover?" Both
fail, and one removal clears both — that is the shared-root-cause story. Step 7 comes back to
*how* each of them goes green, because it is not the same way.

**Two of the four green properties carry no information at baseline, and knowing which is the
point.** Both readings are measured on this scene, not inferred:

- **Property 4** is satisfied because a trust label cannot degrade with no attacker in the
  model: this scene's `motion` source is declared `trusted`, and
  `EF (door_1.trust_LockState_unlocked = untrusted)` is **false**, so the property has nothing
  to catch. Its whole value is the Act 2 contrast — the same property fails the moment one
  sensor is compromised. Presenting it as a baseline achievement would be overselling; call it
  the control condition.
- **Property 5** is satisfied because nothing in this scene can make a lock-state label public:
  `EF (privacy_… = public)` is **false** for both labels. It demonstrates that the privacy
  dimension exists and propagates, not that a risk was avoided.

Properties 0 and 1 are the ones that genuinely earn their green: their antecedents are
reachable and their conclusions depend on rules that really fire. If you have time for only one
sentence, say that a satisfied property is only evidence when the situation it forbids can
arise at all — which is exactly the trap the scene's own regression test exists to catch.

**3. Run verification.** Synchronous, attack mode `NONE`. Properties 2 and 3 come back
violated.

**4. Walk the counterexample.** Three states — short enough to read on one screen:

| State | Readings | Devices | Fired in the step that produced this state |
| :--- | :--- | :--- | :--- |
| 1 | nobody home, no porch motion | door locked, alarm off, light off | — (initial state) |
| 2 | **somebody home, porch motion** | **alarm armed** | the arm-the-alarm rule |
| 3 | **nobody home again, motion gone** | **front door unlocked**, light on | the convenience-unlock and porch-light rules |

Name the rules rather than numbering them when you narrate this, and be careful if you do number
them: the UI lists rules from 1, while the model's own firing flags (`iot_verify_rule_fired_0…2`)
and the fix suggestion's `removedRuleIndices` are **0-based** — the verified removal targets index
`1`, which is the *second* rule in the list.

Read the last column carefully, because this is the one place the demo's temporal convention shows
through and it is easy to narrate backwards. A rule reads the **current** state and writes the
**next** one, so the motion that unlocked the door is the motion visible in state 2 — by state 3 the
reading is already back to `inactive` while the unlock it caused has just landed. The violation is
state 3: nobody home, door open.

The same convention explains the one thing an audience does query about state 2: it shows *somebody
home* next to an armed alarm, which looks contradictory until you see that the arming was decided by
state 1's `nobody home`. Nothing arms the alarm in state 2; state 2 is where state 1's decision
became visible.

Play it on the timeline. The point: this is not a sampled test run or a guess — it is a path
the model checker constructed as proof, and it is the shortest one. A 3-state trace plays,
steps and scrubs like any other; nothing about it is degenerate.

**5. Rule attribution.** All three rules fired in this trace, so all three are listed as
candidates — the porch-light rule shares the convenience rule's trigger and fires in the same
step. Say plainly what this list is: rules that *actually executed* on the counterexample,
read from recorded firings rather than re-guessing which rule looks suspicious. Narrowing
three candidates to one repair is the next step's job, not localization's.

**6. Let the strategies disagree, and say why.** Request a fix. Three strategies report:

| Strategy | Result |
| :--- | :--- |
| Parameter adjustment | `SKIPPED_NO_PARAMETERIZABLE_VALUES` — nothing numeric to move |
| Condition adjustment | `NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE` — no condition change prevents this counterexample |
| Permanent removal | **Verified**: delete the convenience-unlock rule |

This is the most honest minute of the demo, so do not rush it. Two strategies decline, and
both decline for a *reason the tool states*. Parameter tuning has no threshold to move
because the scene is entirely enum-valued. Condition tightening genuinely cannot repair this
property: occupancy evolves freely, so any guard permitting an unlock while someone is home
is still followed by a step where they leave — and nothing re-locks the door. Adding "only
unlock when someone is home" really does not fix it. The tool proves this over every allowed
condition change and says so, rather than offering a guard that only looks plausible.

The verified repair is the destructive one: remove the convenience-unlock rule. Every
candidate was re-checked against **all six** properties on the complete model before being
offered.

**7. Apply and re-verify.** Apply the removal (confirm the destructive board mutation), then
verify again: **6 satisfied, 0 violated.** One removal cleared both violations.

**Then read the 6/0 honestly, because the two properties did not go green the same way.** In the
repaired model the front door is permanently locked — measured, not inferred: `AG (LockState =
locked)` is provable and `EF (LockState = unlocked)` is false, because the convenience rule was the
only automation that ever unlocked it. The porch light and the alarm still work. Now apply that to
each property:

- **Property 2, `Never (nobody home & door unlocked)` — genuinely achieved.** It exists to make that
  state impossible, and now it is. This is what a prohibition property succeeding looks like.
- **Property 3, the Response property — now vacuously true.** Its antecedent
  `EF (occupancy = absent & door unlocked)` is **false** in the repaired model, so "if the door is
  ever unlocked while nobody is home, it must eventually re-lock" holds for the empty reason:
  the situation it talks about can no longer arise. It passes while telling you nothing.

That asymmetry is the single most useful thing this scene teaches, and it is not a tool defect: a
green forward verification means "the target property holds and no property the original rules
satisfied is violated", never "every property is still meaningful". An implication property whose
antecedent a repair removes is reported as verified. (The three shapes this takes, and why the
boundary sits there, are in
[../architecture/theory-sources.md](../architecture/theory-sources.md).)
Saying "one removal repaired both properties" without this distinction is the one place this demo
could fairly be called dishonest — and it sets up the closing note below, where a different repair
satisfies property 3 *without* emptying it.

That is the loop: authored automations → machine-checked properties → proof of failure →
executed-rule attribution → verified repair → confirmed clean. And a tool that tells you
"the convenient fix does not work" is worth more than one that always has an answer.

### The repair the tool cannot propose (a strong closing note, 90 seconds)

If the room is technical, this is the most honest thing you can show. All three strategies edit or
delete *existing* rules; none of them adds one. So ask what a competent engineer would actually do,
and check it with the model rather than asserting it.

Add a fourth automation — "when nobody is home, lock the front door" — placed ahead of the
convenience rule, and keep the convenience unlock. Measured on this scene with real NuSMV:

Put the two repairs side by side. Both make property 3 green; only one makes it *mean* anything.

| | Verified removal (what the tool offers) | Add an auto-lock rule (what it cannot) |
| :--- | :--- | :--- |
| Property 3 (Response) | satisfied — but **vacuously**: `EF (absent & unlocked)` is `false` | satisfied **substantively**: the antecedent stays reachable and the door really does re-lock |
| Property 2 (Never) | satisfied | **still violated** |
| Convenience feature (`EF door = unlocked`) | gone — door permanently locked | **kept** |

So a better repair exists than the one the tool offers, and it costs a *specification* change rather
than a code change: you accept "if it happens, the home recovers" instead of "it can never happen".
The Never property cannot hold while the unlock rule exists, because rules act in one step — the
resident leaves, and the lock command needs the next step to take effect, so one intermediate state
always shows the door open with nobody home.

This is the payoff of reading the 6/0 carefully in step 7. The tool's repair scores 2 green
properties by deleting the feature and emptying one of them; this one keeps the feature and earns a
green property that still describes real behaviour, at the price of a weaker safety claim you have to
state out loud. No automated strategy can make that trade for you, because it is a decision about
what you are willing to promise.

That is the honest division of labour: the checker proves what is broken and what any candidate
repair really guarantees. Which trade-off to accept is a human decision it deliberately does not
make for you.

## Act 2 (optional) — one spoofed sensor

Re-import the original scene (Act 1 mutated the board) and verify with attack mode
`ANY_UP_TO_BUDGET`, budget `1`: the attacker may compromise **one** point.

Five of six properties now fail. Show **property 4**, the untrusted-label one that was
satisfied a minute ago. The exhaustive search picks the **porch motion detector** as its one
compromised point — worth saying out loud, because that is the device an attacker can
physically reach on your doorstep, not the presence sensor inside the house. It spoofs
`motion = active`, and the counterexample ends with

```
door_1.LockState = unlocked
door_1.trust_LockState_unlocked = untrusted
```

The door is open, and the model knows an untrusted source opened it. The property that held
at baseline is the one that breaks — that contrast is the whole act.

Two things to pre-empt rather than be asked:

- **Most properties flip, not all.** Property 5 (lock-state privacy) stays satisfied, so the
  attack is not simply painting everything red. Properties 0 and 1 also flip, because a
  spoofed reading breaks the arm-the-alarm and porch-light responses — correct, but
  collateral, not the story.
- **This is a modeled attack space with a stated budget.** It is not a prediction that a
  particular sensor will be compromised, and the compromise model is a fixed set chosen once
  per run, not a different device each step.

Note that the Act 1 repair is a *removal*, so it does not hand the attacker a new lever the
way a guard on a falsifiable reading would. If someone asks whether a condition-based repair
would have survived this act: it would not have, and that is part of why the tool declined to
offer one.

## If you have extra time

**Bounded exploration** (the `/api/fuzz` family — submission is `POST /api/fuzz/async`) as a
fast pre-check before the formal run. Fix the `seed` so the same candidate path reproduces on stage. Be precise about what it claims: a
finite path violating a supported property under the explorer's semantics — a *candidate*
counterexample, not a verdict, and not accepted by the fix pipeline. Three of this scene's six
properties are eligible (the two Immediate ones and the Never one); the other three are declined
for two different stated reasons, and the distinction is worth one sentence if anyone asks:

- The Response property (template `5`) and the untrusted-label property (template `7`) are
  ineligible **by template** — `UNSUPPORTED_TEMPLATE`, "Only specification templates 1, 3, and 4
  are supported."
- The privacy property is a template `1`, which *is* a supported template, but its condition
  asserts a label rather than a state, so it is ineligible **by content** —
  `TRUST_PRIVACY_UNSUPPORTED`, "Bounded exploration does not model trust/privacy label
  propagation; use formal verification for this specification."

Either way the explorer names a reason instead of silently "passing".

**Simulation** (`/api/simulate`) for an animated trajectory with no properties involved.
Useful if the audience wants to see the home move before seeing it proven wrong. It is a
model trajectory, not a prediction of a physical house.

## Failure modes on stage

| Symptom | Cause | Fix |
| :--- | :--- | :--- |
| Apply is rejected after a long tangent | Fix suggestions are HMAC-signed with a **15-minute** TTL (`FixSuggestionTokenService.java:33`). Two shorter clocks exist but bound different things: the fix *search* is bounded by `FIX_TIMEOUT_MS`, and a completed `/fix` request's live status is readable for only 15 seconds | Re-run `/fix`, then apply promptly |
| Apply is rejected right away | The board changed after `/fix`; the proposal is checked against the current snapshot | Re-run verification and `/fix` |
| Result dialog shows a stale banner | The board was edited after that run | Re-verify; do not narrate a stale result |
| `429`, `USER_FORMAL_OPERATION_BUSY` | One formal operation per user at a time across verification, simulation **and** fix, sync or async, assistant-initiated included. Redis-backed across instances; with Redis down it degrades to a per-instance guard | Wait for the running one — a synchronous run cannot be cancelled |
| Import preview counts are wrong | A bundled device template was edited on this machine | Reset templates to project defaults |
| Numbers differ from this document | The scene, generator, or model semantics drifted | Run `AwayModeUnlockSceneNusmvTest`; trust it over this file |
| Recreating spec 5 by hand is confusing | `propertyScope` has no separate control; it is folded into the merged property list, where **"Current state"** (or `Current <mode> state` on a multi-mode device) encodes `propertyScope: "state"`. This applies to every trust/privacy condition, not just template 1 | Import the scene instead, or say the label out loud when authoring live |
| A rule targeting its own device is accepted | No self-loop guard exists in the rule builder | Not reachable in this scene; avoid improvising such a rule on stage |
| Scripting the demo by hand: `/fix` returns `400` about `requestId` | The fix endpoint takes a caller-supplied `requestId` query parameter — 8–80 characters matching `^[A-Za-z0-9][A-Za-z0-9._:-]*$` — so a live search can be tracked and cancelled. A UUID is fine; a leading `-` or an embedded `/` is a 400 | Pass one; the UI does this for you |
| Scripting the demo by hand: `POST /api/fuzz` returns `404` | Exploration has no synchronous endpoint — it is `POST /api/fuzz/async` plus task polling | Submit async and poll the task |

## Claims to make, and claims to avoid

Say: these properties hold on **this finite model** of the authored automations; the counterexample
is a real path in that model; the repair was re-verified against every submitted property before
being offered — **and one of those properties is now vacuous**, which is a fact about the repair, not
a caveat to bury. If you say the first three and not the fourth, you have overstated the result.

Do not say: the house is secure; the door cannot be opened; this covers firmware, network,
authentication, encryption, physical installation, or real-world timing. None of those are
in the model. The attack results describe a modeled attack space with a stated budget, not
a prediction that a particular sensor will be compromised.
