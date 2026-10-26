# Check a household automation

IoT-Verify helps a household author understand whether an automation can violate their
requirements under an explicit device model. Its main task is **describe the intended
behavior, check the saved configuration, understand a failure, revise, and check again**.
It does not control a real home or certify real hardware.

Verified against code on 2026-10-24. Sources: `HouseholdGoalComposer.vue`, `ChatWelcome.vue`, `ChatView.vue`,
`Board.vue`, `ChatServiceImpl`, and the domain implementations linked below.

## Start with three questions

Open the Board assistant and describe a need in the message composer, or choose a contextual
task to prepare a draft. For more guidance, expand **Describe your needs step by step**.
The household form separates:

| Question | Entrance example | Why it matters |
| :--- | :--- | :--- |
| What should happen? | A visitor arriving while I am away turns on the entrance light. | Defines the intended automation. |
| What must not happen? | The visitor must not cause the locked door to open. | Defines an independent safety requirement. |
| What must still work? | An authenticated owner must still be able to open the door. | Prevents a repair from being accepted merely because everything was disabled. |

Preparing the message only fills the composer. Sending it asks the assistant to inspect
the actual capabilities, restate the requirements, and map each to rules and independent
checks before changing the Board. An existing message draft is never overwritten. Unsent
form fields are conversation-local drafts; sent requirements and the assistant's mapping
are retained in conversation history. There is no automatic proof that a natural-language
sentence and its formal translation mean the same thing: review that mapping explicitly.

Requirements should come from the household's needs, not simply restate the rules being
checked. Otherwise a flawed rule and a flawed requirement can agree. Unspecified wishes
and unsupported concepts must remain explicit questions, not invented sensor capabilities.
The inventory and editor explain these independent checks as **requirements (specifications)**. Their shared
explanation distinguishes requirements that must hold from rules that determine device actions.
Short result summaries must retain the requirement's preconditions and temporal scope: a
check that an already locked door stays locked does not prove that a visitor request locks
an unlocked door. Proposed edits must also correspond to supported operations; rules have no
enable/disable toggle.

## What can be changed

| Author | Owns | Boundary |
| :--- | :--- | :--- |
| Household author | Device instances and labels, permitted initial states and values, rule conditions/actions/order, requirements | Every value must fit the installed template capabilities. Rules and requirements can be edited in place and undone. |
| Model/template author | State machines, available APIs, value domains and evolution | These define what the analysis can mean. Importing a template requires understanding and reviewing its behavior. |
| Analysis engine | Frozen run inputs, outcomes and evidence | Neither the user nor the assistant can edit a verdict into a passing result. A changed Board requires a new check. |

Canvas position and a name such as "bedroom" do not create an independent physical room.
Environment identity and dynamics are defined in [shared-value semantics](../architecture/shared-value-semantics.md).
One model step has no fixed number of seconds. A time deadline or an authentication
guarantee requires an explicit model; naming a sensor "authenticated" does not implement
authentication. See [data authority](../architecture/data-authority-model.md) for field ownership.

## Choose a question, not an algorithm pipeline

| Board action | Question | Evidence boundary |
| :--- | :--- | :--- |
| Check | Can these saved requirements fail in the modeled home? | NuSMV checks the modeled paths under the run's initial state and assumptions. A response also reports whether its trigger is reachable. |
| Observe | What could one sequence of actions look like? | Random simulation shows one possible path. It does not check requirements or prove safety. |
| Explore | Can a bounded search find a candidate failure? | An optional finite search on supported semantics. No finding is not a proof; random-initial mode has different initial/input assumptions. |

There is one simulation engine, presented as **Observe** in settings, task controls,
results, and history; "simulation" and "emulation" are not separate product stages.
Attack selection is a model assumption applied to an analysis, not a fourth
analysis engine. See the owning [verification](../architecture/verification-flow.md) and
[exploration](../architecture/fuzzing-flow.md) contracts for supported semantics.

The exploration handoff checks the **current Board**. It does not replay or formally
confirm a historical candidate's sampled initial state or injected events. Keep that
distinction when comparing results. The engines need not all run for a household task.

## Interpret, revise, and recheck

Read the natural-language requirement beside its result. A violated requirement may
have a replayable counterexample explaining which actions caused the failure. A passing
response whose trigger is unreachable is shown as a warning: the implication is true,
but it did not establish that the desired function can occur. A reachable trigger alone
does not establish the response; both pieces of evidence matter.

The result dialog and background completion notice retain these trigger warnings.
History summaries do not include per-requirement trigger evidence: a neutral formula-pass
label directs you to open the result before judging whether the functions are preserved.

Edit a rule or requirement from the inspector. Editing retains identity and order, checks
that the record has not changed in another tab, and creates one undoable edit. A stale
save preserves the draft instead of overwriting newer work. After editing, rerun the check
and compare the frozen results. Automatic repairs additionally protect the reachable
triggers of response requirements; their precise acceptance rule belongs in
[auto-fix](../architecture/auto-fix.md).

The research supports this loop: Salus motivates explain–repair–recheck, MEDIC supplies
formal modeling and security assumptions, and HAFuzz motivates optional candidate search.
The FSM thesis is modeling ancestry. These are complementary responsibilities, not four
features the household must understand. [Theory sources](../architecture/theory-sources.md)
owns citations, adopted semantics and departures from the papers.

## Run the entrance demo

Import [household-entry-scene.json](../examples/household-entry-scene.json), or its
[Chinese display version](../examples/household-entry-scene-zh.json), through the
Board's scene import and confirm the replacement preview. Its exact model and expected
outcomes belong in [household entry](default-template-scenarios.md#household-entry).
The custom input classifies requests as none, visitor or externally authenticated owner;
it assumes that classification and does not verify an authentication implementation.

1. Open the assistant and expand **Describe your needs step by step**. Enter the three entrance
   wishes from the first table, prepare the draft, and review it before sending. Ask for the
   mapping without changing the scene.
   The assistant should connect each wish to the actual devices, rules and requirements.
2. Open **Check**, use synchronous mode, and turn off attack and privacy modeling for this
   example. Inspect the locked-door failure and replay its counterexample. The unsafe
   convenience rule treats a lit porch as permission to unlock; visitor lighting makes
   that assumption false. Also inspect the two useful response requirements and their
   reachable triggers.
3. Ask the assistant for automatic removal suggestions for that formal counterexample,
   without applying anything. It must call `fix_violation`. Review the verified candidate
   that removes only **Unsafe convenience: a lit porch unlocks the door**. Request its
   application preview through `apply_fix`, review the named rule, and explicitly confirm
   with the confirmation control. The board stays unchanged through search and preview;
   confirmation removes that one rule, keeping both useful automations and all requirements.
4. Check again under the same assumptions. The locked-door requirement now holds alongside
   visitor lighting and owner access, with all three triggers reachable. The conclusion
   applies to this model, including the external authentication assumption.
5. Undo and check again to reproduce the failure. Redo and recheck to restore the repaired
   result. This provides a causal comparison rather than a single green screenshot.

The landing illustration uses the same removal example, explicitly labeled as a preset.
The input is named **Door access request / 开门请求**, supplied by an external authentication
system; it is not a physical sensor or a built-in Door authentication feature. The example's
template and domain belong in [household entry](default-template-scenarios.md#household-entry).
Alternatively, a user may explicitly edit the unsafe rule to add an authenticated-owner
condition. That uses `manage_rule`, not automatic Fix, and must be described accordingly.

`HouseholdEntrySceneNusmvTest` exercises those positive and negative cases using NuSMV.
The browser regressions in `household-demo.spec.ts` exercise the real page and API, including
the unchanged requirement identities, undo/redo and explicit AI rule editing. The formal
suggestion/confirmation/reverification flow is exercised by `live-ai-no-mock.spec.ts` when
live-AI tests are enabled. `household-spec-edit.spec.ts` separately exercises in-place
requirement editing and undo/redo.
