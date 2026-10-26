# Additional Default-Template Scenarios

These scenes exercise care, security, and comfort rules beyond the night-watch acceptance demo. Each
file is a standard version 6 `iot-verify.board-scene` document that can be imported from
the Board with its bundled and scene-specific template snapshots.

Verified against code on 2026-10-25. Real NuSMV checks include `HouseholdEntrySceneNusmvTest`. Sources:
`scripts/generate-default-template-scenes.mjs`, the bundled manifests under
`backend/src/main/resources/deviceTemplate/`, and
`AcceptanceDemoScenarioNusmvTest`.

The files are generated from the current bundled template manifests by
`scripts/generate-default-template-scenes.mjs`. Do not hand-edit copied manifests inside
the JSON files. Change the user-level scene definition in the generator and rerun it so
the embedded snapshots remain exact, self-contained import dependencies.

## Expected checks

The counts below are deterministic for the generated files and were verified with real
NuSMV. `Baseline` and `attack` show satisfied/violated specification counts.

| Scene | Devices / environment / rules / specs | Baseline | Attack budget 1 | Verified repair |
| :--- | :--- | :--- | :--- | :--- |
| [Household entry](../examples/household-entry-scene.json) | `4 / 3 / 3 / 3` | `2 / 1`, all three triggers reachable | Not part of this example's claim | Add an authorized-owner guard to the lamp-to-door rule, or delete that coupling; then `3 / 0`, all three triggers still reachable |
| [Fire evacuation](../examples/default-fire-evacuation-scene.json) | `4 / 2 / 3 / 5` | `4 / 1` | `1 / 4` | Remove the alarm-to-door unlock rule; then `5 / 0` |
| [Climate conflict](../examples/default-climate-conflict-scene.json) | `2 / 2 / 2 / 4` | `2 / 2` | `1 / 3` | Remove the first hot-room heating rule; then `4 / 0` |
| [RFID access](../examples/default-rfid-access-scene.json) | `3 / 0 / 2 / 5` | `5 / 0` | `2 / 3` | No baseline violation to repair |
| [Away-mode unlock](../examples/default-away-mode-unlock-scene.json) | `5 / 3 / 3 / 6` | `4 / 2` | `1 / 5` | No accepted automatic repair: deletion would disable the previously reachable re-lock response |
| [Elderly care](../examples/elderly-care-comprehensive-scene.json) | `14 / 8 / 12 / 12` | `11 / 1` | Not covered by this baseline regression | Removal is rejected because it would disable the photo-sending response trigger; a manual deletion makes the formulas pass vacuously |

Every baseline and attack run emits all requested properties with zero disabled rules
and zero skipped specifications. Simulation produces an animatable trajectory for each
scene. These results describe the formal model, not guaranteed physical-home behavior.

## Household entry

Import [household-entry-scene.json](../examples/household-entry-scene.json) for the main
end-to-end demonstration, or [the Chinese display version](../examples/household-entry-scene-zh.json)
used by the recorded demo. Both are generated from the same model; only device labels, rule names,
and requirement names differ. It uses bundled `Door` and `Light` plus scene-defined resident presence and a
`Door Access Request` input (displayed as **开门请求** in Chinese). The shared `entryRequest` domain is `none`, `visitor`,
or `authorized_owner`: classification is supplied by an assumed trusted external
authentication system. The scene does not implement authentication or prove that this input
cannot be forged in a physical home. Presence and request values may change on every step;
the initial `none` value does not freeze them.

Three automations light the porch for a visitor while nobody is home, unlock whenever the
porch is lit (the unsafe coupling), and independently unlock for an authorized owner.
The requirements are deliberately paired:

1. If nobody is home, the request is a visitor, and the door is currently locked, it must
   remain locked in the next state.
2. A visitor while nobody is home must receive porch lighting in the next state.
3. An authorized-owner request must unlock the door in the next state.

The first property is about a visitor causing an unlock. It does not claim the door must
be locked whenever a visitor is present: an owner may already have opened it. The baseline
violates the first property through the lighting-to-unlock chain and satisfies the latter
two, with all three antecedents reachable. Inspect the counterexample, request automatic
`remove` repair suggestions, and review the verified suggestion that removes only the unsafe
lighting-to-unlock rule. Confirm its application, then verify the saved board again: all three
properties hold and all three triggers remain reachable. An explicit rule edit adding
`entryRequest = authorized_owner` also passes, but is a separate workflow from automatic Fix.
Removing the owner entry rule or visitor lighting fails a useful-response
requirement. Undo the change to recover the violation, and redo it to recover the passing model.

The run proves these statements in the declared synchronous model, without attack selection
or privacy modeling. It does not establish real authentication, physical timing, attack
resistance, or behavior omitted from the scene. Trigger reachability and response satisfaction
are separate evidence, as defined in [the result contract](../api/verification.md#specresultdto).

## Fire evacuation

Default templates: `Smoke Sensor`, `Alarm`, `Door`, and `Light`.

The trusted `smoke = detected` reading sounds the alarm. The observable alarm event then
unlocks the front door and turns on the exit light. The intentionally violated property
says the front door must never become unlocked; the two response properties, the alarm
trust property, and its public-sensitivity property hold in the baseline model.

The counterexample reaches `Front Door = unlocked`. Automatic repair considers the full
rule chain but forward verification accepts only removal of **When the alarm sounds,
unlock the front door for evacuation**. The alarm and exit-light behavior remain, and
all five properties pass after applying that removal.

## Climate conflict

Default templates: `Temperature Sensor` and `Air Conditioner`.

Both rules use `temperature >= 28` and target the same air-conditioner mode. The unsafe
heating rule is deliberately first, followed by the cooling rule. Board rule order is
execution priority: for one target setting, the first matching command wins. Therefore
the baseline reaches `HvacMode = heat`; both the immediate-cooling property and the
never-heat-while-hot property fail.

This scene makes order visible instead of describing the two rules as an unspecified
conflict. Removing the first heating rule leaves the cooling rule authoritative, and all
four properties pass. The temperature is labelled private household telemetry so this
scene tests rule priority independently from an unrelated sensitivity-label violation.

## RFID access

Default templates: `Door RFID`, `Door`, and `Alarm`.

The badge reader uses a device-local `RFID` value, not an Environment Pool value. Its
initial value is `authorized`, trusted, and private. An authorized reading unlocks the
door; a `not authorized` reading sounds the alarm. All five baseline properties pass.

That starting value is deliberate and load-bearing, not a leftover: the template's `idle`
state is reached only through the `scan authorized card` / `scan unauthorized card` **Signal**
APIs, and interactive simulation never fires a Signal on its own. Starting the reader at
`idle` / `RFID = none` therefore freezes the whole scene — measured at 30 steps, the reader
stays `idle` and the door stays `locked` for every one of them, so there is no automation to
animate and one counterexample disappears with it. The reader's fail-closed `idle` semantics
live in the template (`RFID = none`); this scene begins *after* a badge has been presented.

With attack budget `1`, admissible compromised-reader or automation-link branches expose
three counterexamples: the authorized response can be disrupted, an unauthorized state
can coexist with an already unlocked door, and an unlock can carry an untrusted control
label. The privacy property and unauthorized-alarm response remain satisfied. This is a
modeled attack-space result, not a statement that a physical badge will be compromised.

## Away-mode unlock

Default templates: `Alarm`, `Door`, `Light`, and `Motion Detector`, plus one scene-defined
custom type, `Occupancy Sensor`. It demonstrates that a scene file is a self-contained import including new types.

This is the presentation scene; the presenter walkthrough is
[away-mode-unlock-demo.md](away-mode-unlock-demo.md). Its defect is a convenience automation
rather than a rule marked unsafe, so the scene reads like something a real household would
install: nobody is home so the alarm arms, porch motion unlocks the front door "so you do not
have to find your keys", and the same motion turns on the porch light.

`Occupancy Sensor` declares `occupancy` as a **shared environment** variable
(`IsInside: false`, `Reads: true`) rather than a device-local one. That is load-bearing. A
device-local variable with no API writing it compiles to `next(v) := v` — frozen for the whole
run — which would make every property mentioning "someone is home" vacuous and would let a
condition repair "keep" a rule it had actually made unreachable.
`AwayModeUnlockSceneNusmvTest.awayModeUnlockScene_presentsNoVacuouslySatisfiedProperty` pins
the reachability of every state the walkthrough presents.

Two properties are violated at baseline and they ask different questions about the same worry:
`Never (nobody home & front door unlocked)`, and a Response property saying that if the door is
ever unlocked while nobody is home it must eventually re-lock. The counterexample is three
states — nobody home, alarm arms, then porch motion unlocks the door and lights the porch.

All three fired rules remain localization candidates. Parameter adjustment has no numeric
threshold; condition adjustment cannot prevent an eventual empty-house unlocked state while
keeping the convenience rule. Removal would make the Never property true, but would also
make the re-lock response's formerly reachable antecedent unreachable. It is now rejected
with a response-trigger warning rather than offered as a complete automatic repair.

If a user explicitly deletes the only unlock rule, the formal property counts become `6 / 0`,
but the response is visibly qualified as having an unreachable trigger. That distinction does
not change CTL truth. Two other baseline greens concern trust/privacy labels rather than
successful entry behavior. This scene is therefore a useful diagnostic counterexample, while
[household entry](#household-entry) is the demonstration of safety repaired without disabling
useful visitor lighting or authorized entry.
With attack budget `1`, five of six properties fail. The one to show is the untrusted-labelled
event safety property, which held at baseline: a single compromised sensor spoofs its reading
and the counterexample carries `door_1.trust_LockState_unlocked = untrusted`. The lock-state
privacy property stays satisfied, so the attack is not indiscriminate. This is a modeled
attack-space result, not a claim that a physical sensor will be compromised.

## Elderly care

The care apartment combines night-time bed-exit lighting, a vital-signs emergency chain,
ventilation, and a lamp-to-camera-to-hub photo chain. Bed occupancy can change while a
command travels through that photo chain; the resident can be back in bed when the camera
starts taking a picture. That privacy violation is intentional and remains discoverable.

Lighting is an immediate response: night-time bed exit must turn the lamp on in the next
step. Requiring it to be on in the trigger state would fail the initial snapshot before
any automation can execute. The motion-based energy-saving rule has lower priority and
cannot override bed-exit lighting.

`Care Ventilation Window` is a separate scene-defined type. The bundled `Window` senses
contact and changes its position; it declares no CO2 effect. The care window retains those
operations and explicitly lowers the scene's normalized `carbonDioxide` value by `2` per
open-window step against the shared `[-1, 1]` disturbance. This is a declared modeling
assumption, not a measured physical ventilation rate. The bundled `Air Purifier` also
declares a CO2 rate of `-1`, but the `+1` disturbance can exactly cancel that effect;
its modeled effect alone cannot guarantee a decrease. The eventual fresh-air requirement
remains a guarantee over all modeled paths rather than a possibility check.

Removing the lamp-to-camera rule resolves the privacy formula while preserving lighting,
but disables the previously reachable photo-sending antecedent of the hub's idle response.
Automatic repair therefore rejects this deletion. This exposes an authoring decision:
is photography required, or is the idle response only a conditional safety check? The tool
does not silently choose that meaning. Inspect the response-trigger warning and revise the
requirements or rules explicitly before re-verifying. This baseline does not certify the
attacked scene.

## Reproduce

Regenerate the scene JSON after a bundled template changes:

```bash
node scripts/generate-default-template-scenes.mjs
```

Run the real-NuSMV regression for the acceptance, default-template, and care scenes:

```bash
cd backend
mvn -Dtest=HouseholdEntrySceneNusmvTest,AcceptanceDemoScenarioNusmvTest test
```

On Windows, if Maven and the temporary directory are on different drives and Surefire
rejects its manifest classpath before running any test, use the equivalent non-forked
command:

```bash
mvn -DforkCount=0 -Dtest=AcceptanceDemoScenarioNusmvTest test
```

The away-mode scene has its own regression, which pins the numbers this guide and the
presenter walkthrough publish (baseline verdict, blamed rule, refusal of a vacuous repair, and
the budget-one untrusted-label failure):

```bash
mvn -DforkCount=0 -Dtest=AwayModeUnlockSceneNusmvTest test
```

For a UI check, import a file, review the explicit full-replacement preview, run
synchronous simulation and play the timeline, then run baseline and budget-one attack
verification. Review the proposed changes before applying; do not treat attack findings as
ordinary baseline repair requests.
