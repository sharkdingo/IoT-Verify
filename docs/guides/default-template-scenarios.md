# Additional Default-Template Scenarios

These scenes exercise default templates beyond the night-watch acceptance demo. Each
file is a standard version 5 `iot-verify.board-scene` document that can be imported from
the Board without creating or editing a custom template.

Verified against code and real NuSMV on 2026-08-13. Sources:
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
| [Fire evacuation](../examples/default-fire-evacuation-scene.json) | `4 / 2 / 3 / 5` | `4 / 1` | `1 / 4` | Remove the alarm-to-door unlock rule; then `5 / 0` |
| [Climate conflict](../examples/default-climate-conflict-scene.json) | `2 / 2 / 2 / 4` | `2 / 2` | `1 / 3` | Remove the first hot-room heating rule; then `4 / 0` |
| [RFID access](../examples/default-rfid-access-scene.json) | `3 / 0 / 2 / 5` | `5 / 0` | `2 / 3` | No baseline violation to repair |
| [Away-mode unlock](../examples/default-away-mode-unlock-scene.json) | `5 / 3 / 3 / 6` | `4 / 2` | `1 / 5` | Remove the convenience-unlock rule; one removal clears both violations, then `6 / 0` — but see the section below for which of those greens carry information |

Every baseline and attack run emits all requested properties with zero disabled rules
and zero skipped specifications. Simulation produces an animatable trajectory for each
scene. These results describe the formal model, not guaranteed physical-home behavior.

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
custom type, `Occupancy Sensor`. This is the only bundled scene that declares its own device
type, so it also demonstrates that a scene file is a self-contained import including new types.

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

All three rules fired in that trace, so all three appear as localization candidates; the
porch-light rule shares the convenience rule's trigger. Only one removal is verified, and it
clears **both** violated properties — though not in the same way, as the next paragraph but one explains.

Repair is where this scene is most instructive, and it is not the flattering result. Parameter
adjustment reports `SKIPPED_NO_PARAMETERIZABLE_VALUES` (the scene is entirely enum-valued) and
condition adjustment reports `NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE` — adding "only unlock when someone is
home" genuinely does not repair the property, because occupancy evolves freely and no rule
re-locks the door after the resident leaves. Permanent removal of the convenience-unlock rule
is the only verified repair, and forward verification confirms `6 / 0`. A tool that declines
two strategies with stated reasons is more credible than one that always produces a guard.

Read the repaired `6 / 0` property by property, because the two violations do not clear the same
way. Removing the only rule that ever unlocked the door leaves it permanently locked
(`AG (door_1.LockState = locked)` is provable), which *achieves* the Never property and makes the
Response property **vacuously** true — its antecedent
`EF (a_occupancy = absent & door_1.LockState = unlocked)` becomes unreachable, so it holds while
describing nothing. Two of the four baseline greens are likewise uninformative: the template-7
property cannot be violated without an attacker in the model (this scene's `motion` source is
`trusted`), and the template-1 privacy property cannot be violated at all here. A green forward
verification means "nothing violated", never "everything still meaningful";
[away-mode-unlock-demo.md](away-mode-unlock-demo.md) walks the distinction, and
`AwayModeUnlockSceneNusmvTest` pins it with reachability probes.

With attack budget `1`, five of six properties fail. The one to show is the untrusted-labelled
event safety property, which held at baseline: a single compromised sensor spoofs its reading
and the counterexample carries `door_1.trust_LockState_unlocked = untrusted`. The lock-state
privacy property stays satisfied, so the attack is not indiscriminate. This is a modeled
attack-space result, not a claim that a physical sensor will be compromised.

## Reproduce

Regenerate the scene JSON after a bundled template changes:

```bash
node scripts/generate-default-template-scenes.mjs
```

Run the real-NuSMV regression for the original acceptance scene and the three scenes it
covers (fire evacuation, climate conflict, RFID access):

```bash
cd backend
mvn -Dtest=AcceptanceDemoScenarioNusmvTest test
```

On Windows, if Maven and the temporary directory are on different drives and Surefire
rejects its manifest classpath before running any test, use the equivalent non-forked
command:

```bash
mvn -DforkCount=0 -Dtest=AcceptanceDemoScenarioNusmvTest test
```

The away-mode scene has its own regression, which pins the numbers this guide and the
presenter walkthrough publish (baseline verdict, blamed rule, non-destructive repair, and
the budget-one untrusted-label failure):

```bash
mvn -DforkCount=0 -Dtest=AwayModeUnlockSceneNusmvTest test
```

For a UI check, import a file, review the explicit full-replacement preview, run
synchronous simulation and play the timeline, then run baseline and budget-one attack
verification. Apply only the two removals named above; do not treat attack findings as
ordinary baseline repair requests.
