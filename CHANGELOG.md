# Changelog

All notable changes to IoT-Verify are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/). **Until a formal release/tagging
process exists, changes are recorded under `Unreleased` with dated entries.** When the
first tagged release is cut, the relevant entries will be moved under a version heading
(e.g. `[1.0.0] - 2026-xx-xx`).

These entries were migrated from the implementation-alignment sections
(§13, §14) of the former `backend/NuSMV_Module_Documentation.md`, which mixed change
history into a technical spec. The spec content itself now lives under
`docs/architecture/`.

---

## [Unreleased]

### 2026-09-27 (automatic fix)

#### Changed

- **Each strategy lists every minimal repair it verifies, and the user chooses.** Forward verification
  proves the specifications, not unstated preferences, so which guard to add or which redundant rule to
  remove was decided by search order. A strategy now returns up to five verified repairs, smallest change
  set first. A repair that contains a listed one is never listed, and the search excludes such supersets.
  Moving the same thresholds to other values counts as one repair. After its first repair a strategy
  looks for others for as long again as that repair took (at least ten seconds), and never for more
  than an equal share of the remaining time, so a single requested strategy does not keep the user
  waiting minutes after it found an answer and later strategies are not starved.
  The new `FixStrategyAttemptDto.alternativesComplete` (on `VERIFIED` only) says whether the list is every
  minimal repair of that kind. The fix dialog shows the options as one choice, each with its own
  pre-existing violations, and says when the list may be incomplete. The assistant is told to present
  every option rather than pick one. Because several alternatives now share a strategy, the client accepts an
  apply response only when its `appliedSuggestion` is the alternative the user submitted.
- **A strategy that finds no repair now says whether none exists, and the dialog stops offering retries
  that cannot change the answer.** `NO_VERIFIED_SUGGESTION` covered three different outcomes, and the dialog
  offered "try this strategy again" under each. But the fix works on the trace's frozen snapshot, so the
  same input returns the same answer. On trace 834, parameter adjustment proved in three NuSMV calls that no
  threshold value avoids the counterexample, then still showed a retry button and a range editor. The status
  is split into `NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE` (no allowed edit prevents the pinned counterexample)
  and `ALL_CANDIDATES_REJECTED` (every edit that does was rejected), which are both proofs over the searched
  space, and `INCONCLUSIVE` (ended without that proof). The dialog now names the proof and its scope. It
  offers retry only when a retry can change the outcome, or for parameter adjustment once a range differs
  from the last request. After a proof over the template ranges it hides the range rows. Otherwise it offers
  the next strategy not yet tried.
- **Condition adjustment learns from each candidate it rejects.** A candidate that avoided the pinned
  counterexample but failed forward verification used to exclude only its own assignment, so the search went
  on to check, one full-model verification each, assignments that the same violating path already refuted.
  The forward-verification model now reports each scoped rule's guard along that path. The search then
  excludes every assignment whose guards agree with the rejected one's wherever the rule could fire, and
  when that is every assignment the search is exhausted at once. Learning changes how many
  candidates are checked, not which repairs are listed. Paths of specifications that read trust or privacy
  labels teach nothing. The probes are named `iot_verify_guard_probe_<n>`, so a device id may no longer start
  with that prefix, just as it may not start with `param_`, `lambda_` or `condition_value_`.

#### Fixed

- **No repair could ever be accepted on a Board with a second, unrelated violation.** Forward verification
  required every specification to pass, so a candidate that repaired the counterexample's property was
  rejected because some other property had been violated all along (trace 196: two of the Board's
  specifications were already false). A candidate is now accepted when the violated property holds and every
  property still false was already false for the original rules. Those are checked on the same model the
  first time a candidate needs the comparison, and the verdict is reused for the rest of the request; a run
  refused for capacity is retried and one cut off by the deadline is not kept. A candidate that breaks a
  property the original rules satisfied is still rejected. The suggestion lists what it leaves violated as
  `preexistingViolations[]`, and the fix dialog shows them beside it, so a verified suggestion never hides a
  broken property. When verdicts cannot be attributed to specification ids, or the original rules cannot be
  re-checked, only a candidate under which every specification passes is accepted; any other is left
  unjudged rather than rejected, so the listing is not reported complete.
- **Condition adjustment could guard a rule on something the rule itself causes.** The candidate filter
  dropped a condition that the rule's own command establishes, but not one reached through the rules that
  command triggers. On trace 196 it offered "Hallway Camera is taking a photo" for "light the way", yet the
  camera takes that photo only because the lamp turned on. The guard passed the safety property by keeping
  the rule from ever firing. The filter now follows the chain: a state, mode or API event established by
  the rule's command, or by any rule such an effect triggers, is not offered as a condition, nor as a value
  of a free one.
- **A backend test deleted files from the system temp directory.** `FixContextCancellationTest` created its
  model with `Files.createTempFile`, and the strategy's cleanup removes every file in the model's directory.
  It now uses a JUnit temporary directory.
- **One failing fix-dialog test could fail the tests after it.** `vi.clearAllMocks()` keeps queued
  `mockResolvedValueOnce` responses, so a test that stopped early handed its leftovers to the next one. The
  dialog spec now resets those mocks before each test.
- **Parameter adjustment timed out on a counterexample it could have settled in one solve.** The search
  parameterized every threshold of every rule sharing a device with the property, including rules that
  cannot reach it, then probed boundary hints and coordinated values with a full-model forward verification
  each (15–83 s apiece on the reported board). The `¬ρ` solve that would have answered the question was
  killed by the 300 s deadline and reported as a NuSMV failure next to the timeout. The search is now
  solve-first as in Salus §5.1: one `¬ρ` check per threshold, then a joint solve, and only a solver-returned
  value pays for forward verification. The per-threshold checks are skipped when a preferred range excludes
  some threshold's original value, since holding it there would list repairs outside the requested range.
  The boundary-hint and same-command coordination probes are removed. Refinement no longer re-verifies the
  counterexample's own board, and a failed refinement solve ends that threshold's refinement with the value
  already accepted instead of retrying the identical model up to three times.
- **All three strategies searched rules that cannot influence the violated property.** A new rule-level
  cone of influence (`RuleInfluenceScope`) defines the repair scope once in `RuleFixer`: fault rules are the
  fired rules inside the cone, followed by its dormant rules. It replaces the per-strategy
  `expandRuleIndices`, which followed shared devices but not what a relevant rule reads. A fired rule
  outside the cone is no longer listed as a fault rule, in `/fault-rules` or `/fix`. The skip reasons now
  tell "no rule fired" apart from "the rules that fired cannot influence the property".
- **A joint threshold solve could outlast the NuSMV timeout on a three-device scene.** Repair models leave
  their search `FROZENVAR`s free at init, and NuSMV's default reachable-state pass enumerated every
  combination. Every automatic-fix NuSMV run (search solves, forward verification, and the original-rules
  check) now passes `-df`, with or without a deadline; no `FAIRNESS` is generated, so verdicts are unchanged.
- **A failed parameter or condition search run was repeated until the deadline.** A failed run excludes
  nothing, so the retry rebuilt the identical model. The joint parameter solve and the joint condition
  search now stop with `FAILED_SOLVER_EXECUTION`; a failed single-threshold solve or pinned condition
  configuration moves on to the next one. A condition run whose output lacks part of the solved assignment
  counts as a failed run.
- **A NuSMV run refused by the concurrency cap was treated as a failure.** A refused permit says nothing
  about the model, yet a forward verification refused one could leave a valid repair unjudged. Search
  solves now retry it within their attempt budget, and forward verification and the original-rules check
  retry it until the deadline.
- **A deadline-killed NuSMV run was reported as a solver failure**, and the "strategies not attempted"
  warning appeared when the only requested strategy had run. A proof a strategy reached before the deadline
  passed now keeps its status instead of turning into `TIMED_OUT`.
- **The fix dialog stated one outcome three times, with three names for the same check.** The strategy
  tab repeated the attempt status under its description, the suggestion card repeated that description, and
  an unverified card repeated its own title; "forward verification", "forward check" and "review" all meant
  the recheck. Each fact now appears once, under one name, and the verified card states the acceptance rule.
  A condition adjustment's `ruleDescription` also arrived pre-quoted, so the dialog showed it double-quoted.

#### Removed

- The `NOT_VERIFIED` strategy status, unverified suggestions and `FixSuggestionDto.verified`. A candidate
  forward verification did not accept is not a repair, so it is no longer returned, and a flag that could
  only ever be `true` said nothing; the signed `suggestionToken`, which only listed suggestions carry, is
  what apply checks. The assistant's `apply_fix` suggestion schema drops the field too. The dialog's
  "Other Strategies" panel, which repeated the strategy tabs, is removed as well.
- `FixStrategyAttemptDto.attemptsUsed` / `attemptLimit`. The Board's "main search 11/20" counted
  heterogeneous checks (solves, full verifications, refinements) under one number, and the status already
  says whether a search finished.
- The `NO_VERIFIED_SUGGESTION` strategy status (split as described under Changed). The client rejects a
  response that still carries it.
- The fix dialog's "Run with preferences" button and per-suggestion "prefer / lock" buttons. It
  duplicated the footer retry, and "use current suggestion" wrongly marked a verified suggestion stale. It
  is replaced by one fixed search-range row per parameter target: a row at template bounds means no
  preference, only narrowed rows are sent, and "Keep original" pins a value.

### 2026-08-17 (review pass)

#### Fixed

- **Neutral controls gave no hover feedback, or lit up unreadably, in the dark theme.** Every semantic role
  had a hover ground except the neutral one, so 26 controls — the two replay bars' transport, panel toggles,
  close buttons, search-clear buttons, the run-history Dismiss button — reached for Tailwind's
  `hover:bg-slate-100/200`. Tailwind emits its utilities into `@layer utilities`, and an unlayered rule beats
  a layered one at any specificity, so on a board surface those could never win. Measured, both outcomes were
  wrong: where the element also carried a bare `bg-slate-*`, a theme remap owned the background and the hover
  did nothing at all (11.82:1 at rest and hovered), so the Play button and Dismiss button were inert under
  the pointer; where nothing competed, the utility applied and painted near-white rgb(241,245,249) under
  light ink at **1.13:1**, so the label vanished on contact. Adding `dark:` variants would have changed
  nothing — the same layer loses either way. Now `hover:board-control-hover`, backed by a per-theme
  `--surface-control-hover` (`#273548` dark, `#e8edf3` light; the two directions are asymmetric because a
  hover has to move *toward* the viewer), with a strengthened ink required alongside it wherever the resting
  ink is muted — the light ground reaches only 4.23:1 under `--text-muted`, which is tuned against the
  resting control. All 26 sites verified in a browser against the built bundle in both themes: the ground
  changes on hover and the hovered label measures 10.04:1 (dark) / 15.17:1 (light). Three of those controls
  had also asked for strong resting ink beside a chip role that sets its own — the chip wins, so the ink class
  had never rendered; the chip now owns the resting ink and the hover is what strengthens it. The guard that was
  supposed to catch a role class rendering nothing had reported one of these as fine, because it matched the
  class name anywhere in the stylesheet and the only declaration was scoped to `.iot-board` — which the two
  replay bars are siblings of, not descendants. It now checks that a variant class used in a replay bar is
  declared somewhere that surface can actually match.
- **One failing assistant-panel test reported twelve more, hiding which one was real.** `ChatView` re-arms a
  1-second background-session poll after every tick, and the flag gating it is module-scoped, so a component
  that outlived its test kept polling — each tick consuming one queued response intended for a later test.
  Most cases unmounted only on the success path, so any genuine failure (or one slow tick on a loaded
  machine) manufactured a cascade of unrelated ones. Measured by forcing a single assertion to fail: 13
  failures before, 1 after. The teardown now runs for every mount regardless of how the test ended. No
  product code changed; this only affects what the suite reports.
- **A run deleted elsewhere left its counterexample or finding replaying, because only its result dialog
  was reconciled.** Deleting a verification run or an exploration run from the AI assistant or another tab
  arrives as a history reload, and the board closes any surface still showing the deleted record — but it
  looked for that surface in the result dialog only. Opening a counterexample or a finding closes that
  dialog, so during the replay there was nothing to find: the bar kept animating evidence for a run the
  server no longer had, still offering Run details and the model download. Both kinds now recognise the
  replaying evidence by the run it belongs to, close the replay itself rather than a dialog that is
  already shut, and say that playback ended instead of claiming a panel was closed. Simulation was already
  correct — its trajectory reference deliberately outlives every surface, so it identifies the run a
  different way.
- **A verification verdict arriving during a counterexample replay covered that replay with a modal
  dialog.** Sharper than the two cases below, because opening a counterexample closes the result dialog:
  `verificationResult` is empty for the whole time the replay bar animates, so the arriving verdict
  re-populated it and the result dialog — modal, with a focus trap — appeared on top of the trace the user
  was in the middle of watching, hiding the playback controls behind it. Both paths were affected: the
  async one presents when watched from the task inbox, and the synchronous one presents whenever its
  request returns. A verdict arriving while a replay is on screen is now announced as a notification
  instead, at the severity the outcome deserves, with a line saying it was saved to run history and can be
  opened there once the replay is closed. A failed history save is still reported first, so the pointer to
  run history is never given for a verdict that did not reach it.
- **A background exploration run finishing behind an open replay threw a modal result dialog over it.**
  The task-watch path presented the completed run unconditionally, so the exploration result dialog —
  `aria-modal`, with a focus trap and a background scroll lock — appeared over the replay bar, which is
  deliberately non-modal and kept animating underneath: the user's own playback controls were suddenly
  behind a trap they never opened, and dismissing it was the only way back to the trace they were
  watching. The panel-driven path already declined to present in a comparable case and routed the
  completion to the task notification; the watch path now does the same while a replay is on screen,
  reporting the run's own outcome (a budget-exhausted run is still flagged as such, not softened) plus a
  note saying the result is waiting in the task inbox.
- **An async simulation completing behind an open replay repainted that replay's header with the new
  run's semantics.** `lastSimulationResult` is the manifest every simulation surface describes — the
  replay bar takes its attack and privacy chips, step counts and model snapshot from it, while the states
  it animates come from a separate ref — and both async paths wrote it the moment the poll returned,
  before checking whether a replay was already on screen. So the visible trajectory kept animating while
  its header described a different run, one trajectory's steps sitting under another's attack budget, and
  Run details opened the wrong run outright. Reachable in ordinary use, since playback admission does not
  consider whether a run is in flight: replaying something from history while an async run finishes is a
  normal thing to do. A run arriving behind a replay is now deferred rather than silently adopted, and the
  manifest and its staleness flag are written together as a pair. The deferral says which reason applies:
  the existing notice names an open editor as the cause, so a replay deferral gets its own wording rather
  than asserting a cause the client knows to be false.

- **An exploration run opened from page 2 of history could be closed as "deleted" when it was only
  off-page.** Exploration is the one paginated run history (`/fuzz/runs` takes `page`/`size`, defaults to
  25, ordered newest-first against a 100-run stored quota), and a history reload replaces the list with
  page 0 — so the new reconciliation below, which reads absence from the reloaded list as a deletion, had
  a false positive for any run the user had paged back to find. It now stands down while `hasMore` says
  later pages exist. Verification and simulation return their whole lists, so absence there is real.

- **A deleted simulation trajectory kept replaying, and its model download still offered the deleted
  record.** `delete_simulation_trace` is a shipped assistant tool, and unlike the other two run kinds a
  trajectory's primary surface is the replay *bar* — a non-modal sibling of the board — so the assistant
  stayed one click away for the whole replay. Deleting the trajectory being replayed left it animating,
  and its Run details → Download SMV still resolved the run id from the deleted record, answering with
  the same misleading “may be a record saved before model persistence was enabled” 404 that the
  verification fix was written for. The trajectory is now reconciled against a successful history reload
  like the other kinds: the bar, the details dialog, the replayed states and the run manifest are all
  dropped, the `?run=simulation:<id>` deep link is cleared exactly once by whichever closer owns the
  addressed surface, and the message says the *playback ended* rather than that a panel closed, because a
  trajectory is neither a verdict nor a candidate finding.

- **An exploration result outlived its own run when the deletion came from anywhere but this tab.** The
  assistant tool `delete_fuzz_run` and another tab both arrive as a run-history reload, and only the
  *verification* surface was reconciled against it — so the exploration dialog kept rendering a run, its
  findings and its eligibility report for a record the server had already dropped. Exploration now uses
  the same successful-reload reconciliation, which dispatches per run kind so a future kind is a sibling
  rather than a new call site. Its explanation is its own string: the verification copy offers “re-run to
  get a conclusion”, which would present bounded exploration, whose output is candidate findings, as
  formal verification. The same journey also fixed the in-tab delete, which closed the dialog with the
  internal-transition closer and so left `?run=exploration:<deleted id>` in the URL for the deep-link
  watcher to reload and reject.

- **A new lease-boundary test was ~45% flaky, and its own fixture was the cause.** It asserted the
  equality case by writing `LocalDateTime.now()` as the lease and passing the same value as the sweep
  cutoff — but H2 *rounds* a `TIMESTAMP(6)` column to the nearest microsecond, and `now()` carries
  finer digits than that. Whenever the sub-microsecond remainder was >= 500ns the stored lease landed
  strictly after the cutoff, so the row correctly did not match and the test failed having never created
  the equality it was written to check. Measured on H2 2.3.232: 91 of 200 raw samples miss the boundary,
  which is why it passed twice locally and then failed Fast CI while Full CI passed on the same commit.
  The instant is now truncated. Also corrected an incorrect precision claim this change had introduced in
  three places: `lease_expires_at` is `datetime(6)`, not whole seconds, so the equality is rare in
  production and the fix is about thirteen ownership predicates agreeing on one boundary.

- **The exploration lease sweep disagreed with its two siblings on when a lease is expired.** Verification
  and simulation reclaim a task whose lease expiry is at or before the sampled instant; exploration used a
  strict comparison, so a lease landing exactly on that instant was treated as live. Every renewal, start,
  and terminal-commit guard in all three repositories requires the lease to be strictly *after* the sampled
  time, so such a row could no longer renew, progress, or commit a result — it was work no worker could
  advance, left unreclaimed until the next maintenance tick ten seconds later. Both the clock and the lease
  column carry microsecond precision, so hitting the equality exactly is rare.
  User-visible effect was small and self-healing, but the three sweeps now express one rule, and a test
  pins the equality case for all of them — the previous coverage only ever used a lease a second in the
  past or a minute in the future, which is why the divergence survived.

- **"Show step changes" offered to restore a panel that was already on screen.** The counterexample
  replay bar showed that button whenever the step-changes popover had *ever* been dismissed, rather than
  when it was actually hidden. The dismissal is scoped to one step by design — dismissing at step 3 must
  not silence step 4 — so scrubbing to another step brought the popover back while the button stayed,
  sitting beside the panel it claimed to restore for the rest of the playback session. It now reads the
  popover's own visibility, which is the condition the simulation replay bar was already using through
  its `changePanelVisible` prop, so the two bars no longer answer the same question differently.

- **The simulation replay bar accepted a run manifest the product cannot produce.** Its
  `modelSemantics` prop was optional even though `SimulationResult.modelSemantics` is required, the run
  response validator rejects a manifest that disagrees with its run context, and the bar's only opener
  refuses to show without a loaded result. Nothing user-visible was wrong, but 17 of the 18 mounts in its
  spec omitted the prop and so rendered the "model semantics unavailable" warning while claiming to
  describe an ordinary simulation — tests that cannot fail for the reason they name. The prop is required
  now, every mount supplies a manifest the consistency predicate accepts, and one test asserts that
  warning's absence. The counterexample bar keeps its optional equivalent on purpose: `TraceEvidence` is
  shared with the hand-assembled exploration trace, which carries no manifest.

  The render condition stays on `simulationAnimationState.visible` alone. Gating it on the result too
  looked like the tidier way to satisfy the type, but that flag is the single authority for "the simulation
  replay bar is up" that 33 sites read — the board edit lock among them — so a rendered surface with its own
  slightly different condition could have held the lock with nothing on screen to explain it.

- **Two dead CSS rules for the playback replay bars, one of them half a layout contract.** The two
  timeline hosts are `position: fixed` **siblings** of `.iot-board`, so every rule written as a board
  descendant matched nothing — quietly, because the declarations parse and the surface renders with
  whatever the unprefixed rules give it.

  `.iot-board .board-timeline [data-testid$="-timeline-close"]` declared the 44px touch floor for both
  replay bars' close buttons inside the narrow/short media query. Dead, so on the viewport where a coarse
  pointer needs it most both buttons stayed at the ~32px their padding produced, and no target-size check
  covers that surface.

  `.iot-board.has-playback-change-popover .board-timeline-host` was worse than dead: its *pair* rule
  matched. On a short landscape viewport the change inspector duly narrowed to 42vw to share the width,
  while the timeline never yielded the column — so the two overlapped anyway, which is the case the block
  exists to prevent. Measured against the built bundle at 1280×560, 1024×500 and 900×560: the inspector
  covered the replay bar over its **full 352px width** at all three (the bar ran to the same right edge the
  inspector started from), and now clears it by the intended 16px gap. The timeline keys off
  `data-playback-change-popover`, published by both hosts the way `boardShellStyle` already publishes the
  width variables. Nine further dead colour rules restated
  what the live unprefixed block does, with different values, and are gone.

  This is the third form of one structural mistake — the first cost 10 of 12 replay controls their pointer
  cursor, the second made `--board-floating-gap` unreadable inside the hosts — so
  `timelineHostScope.spec.ts` now rejects the board-descendant selector shape outright rather than
  guarding any single declaration. It also guards the second way in: the shared role classes the bars use
  (`board-chip-*`, `board-surface-*`) are declared as two-arm selector lists, and dropping the bare arm
  would stop the class applying on the replay bars alone. A class-by-class sweep of every `board-*` name
  used outside the board root found no further live instance.

- **The sentence explaining a liveness counterexample's final step never said the cycle was the
  violation.** The playback change panel has two wordings for the state that closes a lasso, and the
  liveness one exists precisely to state something the safety one must not: that the path repeats
  forever and the required state is never reached. Both were mechanical — "State 3 loops back to
  state 2" — so the branch conveyed nothing beyond arithmetic the state counter already showed, on the
  one surface a reader consults to find out why the last step of a counterexample shows nothing moving.

  The liveness sentence now names the repeating range and says the cycle is the violation; the safety
  sentence says the violation is the marked state rather than the repetition, since NuSMV reports a loop
  for safety counterexamples too (measured on both a CTL `AX` and an LTL `G(p)` refutation) and there the
  fault is a single state. The wording had no rendering coverage at all — the only guard was a source
  read of the element ordering, which cannot see what the block says — so `PlaybackChangePopover.spec.ts`
  now mounts four cases against the shipped strings: liveness, safety-with-loop, no resolved range, and a
  multi-state cycle whose closing state carries real device changes — the last confirming the `v-else-if`
  chain selects only the explanatory box and does not suppress the change list beside it, which is the
  shape where the explanation is the *only* thing distinguishing the step from the path continuing.

- **Eight comments and two docs stated a lasso's closing state is identical to its predecessor; that
  holds only for a one-state cycle.** The claim is the stated justification for carrying `loopStart` /
  `loopBack` rather than deriving them, so getting it wrong invites exactly the derivation it warns
  against. Measured on NuSMV 2.7.1: a one-state cycle prints the closing state with no variable lines,
  so the delta merge reproduces the predecessor and playback freezes; a longer cycle prints the deltas
  that return to the entry, so the closing state differs from its predecessor, equals the loop *entry*,
  and plays back as an ordinary step. A consumer testing "did anything change?" to spot the repetition
  therefore sees nothing at all in the second case — the worse of the two failures, and the one no test
  covered, since every existing loop test used an empty closing state.

  The worst copy was `get_trace`'s own schema description, which is contract text the model reads: it said
  "its values matching its predecessor is the repetition", instructing the assistant to use exactly the
  comparison that fails on a multi-state cycle. It now states which shape occurs when and says to rely on
  the flags rather than on comparing values.

  Corrected in `TraceStateDto`, `SmvTraceParser`, `ModelTraceToolPresenter`, `GetTraceTool`,
  `types/verify.ts`, `Board.vue`'s two loop computeds, the two affected test docblocks,
  `docs/api/ai-tools.md`, `docs/api/verification.md`, and
  `docs/architecture/verification-flow.md` (which now owns the fact, and whose own later paragraph still
  restated the narrow case as general), and pinned by
  `parseCounterexample_marksAMultiStateCycleWhoseClosingStateCarriesChanges`.

- **`get_trace` told the assistant to decide liveness from `templateId`, which no trace tool emitted.**
  The description says the trailing cycle "for a liveness specification (templateId 2, 5, 6) is itself the
  violation" — the same judgement the frontend gates on `LIVENESS_TEMPLATES` — but
  `ModelTraceToolPresenter.violatedSpecification` emitted only `specificationLabel`, `formulaPreview`, the
  condition lists and `formulaKind`. An existing test asserted the field's *absence*, beside the assertion
  that keeps the persistence `id` out; `templateId` is not that kind of id, since `spec_list`,
  `manage_spec` and both recommenders already take and return it. Left as it was, the model could only
  guess from the display label ("Eventually", "Eventual response", "Persistence"), which is wording rather
  than a contract — and a wrong guess turns a cycle that *is* the violation into an unexplained repetition,
  or the reverse. Now emitted on every trace tool that shares this presenter (`get_trace`, `list_traces`,
  `delete_trace`, `get_fuzz_finding`, `get_fuzz_run`), pinned in `GetTraceToolTest` and `ListTracesToolTest`,
  and documented where `ai-tools.md` explains why the neighbouring `specResults` projection omits it.

- **Two backend Javadocs named the liveness template set as "5/6", omitting template 2.** Every other
  site — `spec-templates.md`, `types/verify.ts`, `Board.vue`'s `LIVENESS_TEMPLATES`, the AI-tool docs —
  lists 2, 5 and 6, and template 2 (`AF`) is refuted by a lasso like the others: measured on NuSMV
  2.7.1, its marker precedes State 1.1, so the whole trace is the cycle. Corrected in `TraceStateDto`
  and `SmvTraceParser`, with the measurement recorded where the set is defined.

### 2026-08-16 (review pass)

#### Fixed

- **A board edit made while a run was in flight produced a verdict that claimed to describe the
  current canvas.** Editing the board is what makes a displayed conclusion stale, and the board says
  so with a re-run banner and by withdrawing the per-counterexample Fix action. That worked for an
  edit made *after* a result appeared. It did nothing for an edit made *during* the run: the staleness
  hook can only flag a result that exists, and both run paths clear the result before submitting, so
  for the entire duration of a run there was nothing to flag — and the completion path then marked the
  arriving verdict current unconditionally.

  A user who applied a fix, added a rule, or changed a device while NuSMV was working therefore
  received a verdict about the model frozen at submission, presented as a verdict about the scene in
  front of them, still offering a Fix computed against a scene that no longer existed. All four paths
  that await a run — synchronous and background verification, synchronous and background simulation,
  and watching an existing task from the inbox — now capture a semantic-change counter at submission
  and compare it on arrival, so such a result arrives stale. Runs read back from history are
  unaffected: they were never in flight, so they stay current rather than depending on unrelated
  editing history.

  Both banners now say the board changed "after this run was submitted" rather than "after it ran",
  because for a reader who edited mid-run the old sentence named a moment they know they did not edit
  in.

- **Eight controls promised a hover and delivered nothing, and the three Apply buttons were three
  different colours.** A `hover:` that names the colour the control already has renders no change. Every
  filled destructive control on the board had this — the four Stop buttons on the recommendation panels,
  the three "add condition" buttons, the counterexample View button — so the accent buttons beside them
  lit up under the pointer while the red ones stayed inert, and the pattern read as reused when it was
  not. Two new tokens (`--danger-fill-hover`, `--warning-fill-hover`) give those controls a real hover
  that keeps white ink above AA in both themes; the confirmation dialog's destructive button now reads
  the token instead of repeating the same mix inline.

  Seven of the same controls were also filled with the *text* half of their role under white ink, which
  measures 1.90:1 (danger) and 1.52:1 (success) in dark theme — the four Stop buttons and the three
  recommendation "Applied" states. And the three panels' Apply button, which performs one action, was
  amber in one panel, blue in another and red in the third, each broken differently: the amber one
  hovered white ink onto a pale tint, the red one onto itself. All three now share the accent pair, so
  the same action reads the same way wherever the user meets it.

  A related truncation: the unvisited step markers on the counterexample rail ended in a bare `hover:`
  with no utility after it, so the one control whose whole job is "click me to seek here" gave no pointer
  feedback. It now highlights its border on hover, matching the identical rail in the simulation timeline.

- **The specification builder labelled every formula "Model" instead of CTL or LTL.** The chip sits
  immediately beside the formula preview, and the preview begins with the very word the chip failed to
  read: `ControlCenter` derived the logic by looking for a `CTLSPEC`/`LTLSPEC` prefix, which
  `buildSpecFormula` never emits — it writes `CTL AG(...)` / `LTL G(...)`, and NuSMV's keyword form
  appears only in a trace's `checkedExpression`. So the one surface that teaches this distinction fell
  through to the generic label for all seven templates while contradicting its own neighbour.

  There were three implementations of the same rule: this one, a correct `templateId === '6'` copy in
  `DeviceDialog`, and the backend's `formulaKind`. A single `specFormulaKindFromTemplate` now owns the
  client side, keyed on the same template `type` switch that builds the formula rather than on the string
  it produces, and it returns `null` for an unrecorded template so a caller falls back instead of
  claiming a logic the data does not support.

- **The playback header's violation chip appeared for an exploration finding and never for a
  counterexample.** It tested `activeFuzzingFinding.firstViolationStep` directly instead of reading the
  computed that owns the question, so stepping a verification counterexample onto its violating state left
  the chip silent while the rail immediately beneath it marked that very step and the canvas outlined the
  bound devices — two surfaces in the same header disagreeing about whether the step the user is standing
  on is the failure. It now reads `traceStateViolationLabel`, the same helper the rail uses, which also
  means a liveness cycle says "cycle" here rather than naming a single state that is not the fault. Its
  `data-testid` changes from `fuzzing-timeline-first-violation` to `trace-timeline-violation-chip`, since
  the control is no longer exploration-only; no E2E spec referenced the old id.

- **A committed test class had never run, and the suite's green total said nothing about it.**
  `VerifyCorrectedScene` carried an `@Test` method under a name matching none of surefire's include
  patterns (`Test*`, `*Test`, `*Tests`, `*TestCase`), so it was silently skipped on every CI run while
  reading, in the source tree, as coverage. It was in fact a probe script — no assertions, only
  `System.out.println`, a live NuSMV binary required, 44 seconds — and belonged outside the checkout. It
  is deleted, and a new `TestClassNamingReachabilityTest` fails on any test-bearing class under a
  non-matching name, so the next one cannot hide the same way. The shipped scene it probed keeps its real
  coverage in `ShippedSceneImportTest`, which globs `docs/examples` at the import request boundary.

- **The assistant could not tell a counterexample's cycle from a path that stopped changing.** `get_trace`
  projected each state's index, devices, triggered rules and variables but dropped `loopStart` and
  `loopBack`, the two flags that mark the infinite cycle NuSMV ends on — and for a liveness specification
  (`templateId` 2, 5, 6) that cycle *is* the violation rather than any single state. The flags cannot be
  inferred from the values: NuSMV re-prints the loop entry carrying no variable lines, so after the delta
  merge the closing state is identical to its predecessor. An assistant explaining such a trace therefore
  saw a path that merely stalls, which is the same misreading the frontend needed a dedicated field to
  avoid — and paging makes it worse here, because one 10-state window need not contain both ends of the
  cycle.

  Both flags are now projected, present only when true so that finite simulation and fuzz traces — which
  never carry them, since the NuSMV trace parser is their only writer — keep their existing output rather
  than gaining two always-false keys. The tool description states what they mean, since a tool's schema
  description is part of its contract.

- **A liveness counterexample marked no step on the trace rail.** Templates 2, 5 and 6 are refuted by an
  infinite lasso path, so no single state is at fault and the single-step violation index is `undefined`
  for them by construction. The rail tested only that index, so it labelled nothing — while the canvas
  emphasised every device in the failing cycle and the change panel explained the loop. The one surface
  that shows *where* in the path the failure lives showed only the playback cursor, which is the same
  silence template 4 had below, in the branch that fix did not reach. Measured on NuSMV 2.7.1 with the
  generator's own template-5 shape over a non-responding model: a six-state counterexample whose cycle is
  states 5–6, so two steps to mark and none marked. Five of the 42 specifications in the shipped example
  scenes are template 5, including the away-mode unlock scene.

  The rail now reads the same step set the canvas emphasis reads, through one helper that both the visible
  marker and the accessible name call — so the two cannot drift apart again, which is how the wording
  mismatch fixed below arose. A cycle is labelled "Violation cycle" rather than repeating "Violation" on
  each of its states, because the same word on several steps reads as several separate faults instead of
  one cycle that is the fault. Every step of the cycle is ringed and carries the word in its accessible
  name, so a reader landing mid-cycle learns they are inside the failing loop; the visible word prints
  once, at the step where the cycle begins, because the label is about 80px wide against markers packed
  38px apart and repeating it would stack overlapping labels across the rings that show the cycle's extent.

- **The most common specification template's counterexamples marked no violating state anywhere.**
  Template 4, immediate response (`AG(IF → AX(THEN))`), was excluded from the set of templates whose
  violation is the trace's last state, so replaying one of its counterexamples showed no rail marker, no
  badge in the change panel, and no canvas emphasis — the star on the rail marks the cursor, which is
  what a reader is then left to interpret as the verdict. It is the template used by 13 of the 42
  specifications in the shipped example scenes, appears in all seven of them, and is what the acceptance
  demo violates under attack modeling.

  The exclusion was reasoned from the **negated** specification: `EF(IF & EX(!THEN))` would end where the
  trigger holds, with the fault in a successor the trace does not show. Verification does not emit that
  form — it emits the positive one, and only the automatic-fix strategies use the negated model, as a
  satisfiability check they parse no trace from. For a model that actually violates the property the
  negated formula is *true*, so NuSMV prints no trace for it and no user ever sees one.

  Measured on NuSMV 2.7.1 across 21 falsifying models of the positive form: the trigger is at state *n*,
  the violating successor at *n+1*, and *n+1* is always the last state in the trace — it never stops at
  the trigger. Two other parts of the platform already agreed on that state: the bounded exploration
  engine reports the successor as the violation, and the exploration semantics table documents "State
  `n+1` where `IF` held at `n` and `THEN` is false at `n+1`". Only the replay surfaces disagreed. They
  now mark the same state, so a user stepping to the end of an immediate-response counterexample sees
  which device failed to respond and when.

  With a minimum length, because this template is the one where the trace can be too short to contain a
  violation: it needs a trigger *and* the successor that fails to respond, so a single-state trace
  claiming it is inconsistent evidence rather than a violation at state 0 — marking that one state would
  name the trigger as the fault. The other safety templates have no such floor, since an initial state can
  break `AG(p)` on its own. The canvas emphasis now derives its state from the same computed the rail
  marker reads instead of carrying a second copy of the last-state rule, so the two cannot drift.

- **Four primary buttons in Run History had no hover state, and the rail told a screen reader a
  different word than it showed.** Watch task, Open result, and both Replay buttons carried a filled
  accent background and nothing else, while every *secondary* button beside them — Cancel, Delete,
  Download — did respond to the pointer. In a run row the fuzz Replay button hovered and the
  counterexample Replay button directly above it did not, so one panel gave the same action two
  behaviours and the most consequential control in each row was the inert one. This is the companion of
  the no-op hover fixed above: there the hover named the resting colour, here there was no hover at all,
  which the guard for the first case cannot see. It is now guarded as well, scoped to `<button>` so a
  selected segment — where the fill marks the selection and reacting to the pointer would suggest it is
  still a choice — stays exempt.

  Separately, the counterexample rail's step buttons announced "First violation" to a screen reader on
  the state whose visible marker read "Violation": the accessible name was hardcoded to the exploration
  wording. One state under two names, on the same control. The accessible name now follows the marker.

- **The playback panel marked the violating state during exploration and stayed silent during
  verification.** Two surfaces explain a replayed step: the rail marker beneath the canvas, and the
  change panel above it that says what happened *at this state*. For a counterexample of a safety
  property the marker said "Violation" on a state while the panel directly above it said nothing
  about it, so the panel that carries the explanation was the one place the fault was not named.
  Liveness counterexamples were covered — the loop sentence explains the cycle — and so was
  exploration, whose badge was the only path the panel's condition accepted.

  The badge now reads the same owner the rail marker reads, which prefers an exploration finding's own
  first-violation step, restricts a verification counterexample to the templates whose witness ends at
  the fault, and reports no single state for a liveness cycle. Exploration keeps "First violation"
  because its search may find several; a counterexample has exactly one, so it says "Violation" —
  matching the marker word a reader compares it against. The panel is shared with simulation replay and
  neither the selected trace nor the selected finding is cleared when one starts, so the badge is gated
  on the active playback kind — the same gate the canvas emphasis already carried, which the old
  exploration-only condition had been providing by accident.

- **An unavailable SMV model blamed a cause that was often not the user's.** The notice beside a
  disabled download told every reader to "check whether the run is still in your history". That is
  sound advice for a run that was saved and whose model is genuinely gone — but four
  `RunPersistenceStatus` values reach that notice, and for three of them there is no history record to
  check and never will be: the user did not ask to save the run, the save failed, or its outcome is
  unconfirmed. Those readers were sent to look for a record that does not exist, with the real reason
  never stated.

  The wording now follows the status. A run that was never persisted says so and names the way
  forward (run again and save successfully); an unconfirmed write keeps its own wording rather than
  being reported as a confirmed absence, the same distinction the automatic-fix action already drew
  for counterexamples; a saved run keeps the original message. An unrecognized status falls through to
  the wording that asserts no cause, because not knowing what a status means is not evidence that
  nothing was saved. The rule is a pure function in `views/board/smvUnavailableReason.ts` with its own
  tests, rather than a third status branch inline in the template.

- **Choosing a device-import file could import the payload it replaced.** Reading a file is
  asynchronous, and nothing invalidated the preview across that gap: between the file dialog closing
  and the contents arriving, the parsed list, the validity count and the create button all still
  described the *previous* text — and the button was still enabled from it. A click landing in that
  window imported the old payload. `setImportTextImmediately` already closed the 300ms debounce for a
  file selection, but it can only run once `await file.text()` resolves, so it could not cover this.

  The preview is now invalidated before the read begins (after the synchronous size check, so a
  rejected oversized file still leaves an existing paste intact). No text means no parsed devices,
  which means a disabled button, so the worst case becomes a click that does nothing instead of one
  that imports something the user did not choose.

  Found in CI rather than by reading, and the diagnosis turned on an artifact detail worth recording:
  the E2E import test failed two consecutive nights with the pasted JSON imported a second time and
  neither CSV device present. Both payloads happen to parse to exactly two devices, so `toBeEnabled()`
  and the button's own "Create 2 device(s)" label were already satisfied by the state the step was
  trying to replace — the assertion never waited for the file at all. The test now waits for the CSV's
  own preview rows, content the previous payload cannot produce.

- **A viewport resize cancelled panel drags up to 200ms late.** `handleChatViewportResize` ran
  `stopPanelInteraction()` and `clampExistingChatPosition()` inside one trailing-edge
  `throttle(..., 200)`, so the cancel could execute long after the resize that scheduled it — landing
  on a drag the user had begun in between and killing it, leaving the panel a few pixels from where it
  started. Only the clamp keeps the throttle: it reads geometry and a resize fires continuously while a
  window is dragged, whereas `stopPanelInteraction` is idempotent and does no layout work.

- **The device-import create handler returned silently when it refused a click.** Five guards share
  their conditions with the button's `:disabled`, but the binding and the guard evaluate at different
  moments, so anything changing the preview in between lets the guard see a state the button did not —
  and a silent return is indistinguishable from a click that worked. It now names the blocking
  condition in the console (deliberately not a toast: each state is already explained inline above the
  button), which puts a reason in the Playwright trace.

- **40 callouts asked a borderless role for a border and rendered none.** `board-chip-*` declares
  `border: 0` deliberately — the roles are badges, and the stylesheet says so: *"The `board-surface-*`
  classes are containers and include a border"*. A site combining a chip with `border` is asking for a
  container in badge vocabulary, and the cascade resolves it invisibly: `board.css` is unlayered while
  Tailwind's border utilities live in `@layer utilities`, so unlayered wins regardless of order or
  specificity. Measured in the real host chain: that markup rendered `border-top-width: 0px`, and a chip
  silences `border-2 border-dashed` just as completely (the same markup without the chip renders 2px
  dashed). An edgeless tint reads as a background wash, so warning and error strips stopped looking like
  strips — including the verification result dialog's error notice and its generation-warning notice.

  Converted to `board-surface-*`, which measured strictly better on both axes: the border appears, and
  ink contrast rises from 5.15:1 to 16.94:1 because the container role carries `--text` rather than the
  role tint. Two dashed drop targets keep their dashed border and take the tint from
  `bg-[color:var(--<role>-surface)]` instead, since a solid container border is the opposite of the
  affordance a drop zone wants.

- **The counterexample dialog's escalation to its owning run was invisible.** It carried
  `iot-dialog-btn--secondary`, a variant `dialog.css` does not define (only primary/danger/ghost/quiet),
  so it computed to a bare `iot-dialog-btn`: transparent fill *and* transparent border, measured in a real
  dialog card. The single control carrying the evidence→run level transition had no visible boundary. Now
  `--primary`, which is what its last-in-footer position means in this codebase.

- **The replay bar named the wrong repeating cycle on a counterexample with two loop markers.** NuSMV can
  print `-- Loop starts here` more than once, and the parser resolves that deliberately by keeping the
  **last** one — `SmvTraceParserTest.parseCounterexample_usesTheLastMarkerWhenNuSmvPrintsSeveral` pins a
  five-state trace where states 3 and 4 both carry `loopStart` and state 5 carries `loopBack`. The
  frontend took the **first**, so the popover said "State 5 loops back to state 3" where the cycle is 4–5:
  a wrong statement about formal evidence, in the one place a reader goes to find out why the final step
  shows nothing moving.

- **The verification result dialog could report more violations than it showed evidence for, silently.**
  `violatedSpecCount` counts `specResults` with `outcome == VIOLATED`, while a counterexample exists only
  where NuSMV returned a *parseable* one, so the two legitimately disagree — and the product already names
  that state, but only in run history. The dialog is where a user lands the instant a run finishes, and it
  showed "Violated: 2" beside one counterexample, or beside none at all (the whole section is
  `v-if="traces?.length"`), with nothing accounting for the difference. The notice now sits outside that
  conditional section, so it survives the zero-evidence case that needs it most.

- **Deleting a verification run left every surface showing it open, and its download lied about why it
  then failed.** Measured end to end: with the result dialog open, deleting the run left the dialog
  rendering a verdict for a record the server had dropped, with the model download still enabled — and
  clicking it answered *"SMV model not available (may be a record saved before model persistence was
  enabled)"*, blaming a historical data limitation for a deletion one click old. The result dialog is
  `aria-modal`, so the same-tab path is the impossible one; the reachable ones are the assistant's
  `DeleteVerificationRunTool` and another tab, which both arrive as a history reload. Both now reconcile
  the open run, and the 404 copy no longer asserts a cause the client cannot know.

- **The solver's own output was carried through the entire stack and rendered nowhere.** `nusmvOutput` is
  captured by the executor, persisted on the run, mapped through every DTO and *required* by the client
  contract validator — and the dialog-consolidation pass deleted the disclosure that showed it, after
  which a dead-key sweep deleted its now-orphaned label. Between them they removed the only channel by
  which a NuSMV message can reach a user, and each change looked correct on its own. Restored in the
  run-context section, where a run-level artifact belongs.

  This matters beyond tidiness. The trace parser models exactly one kind of solver line
  (`-- specification ... is true/false`); everything else NuSMV says survives only here. Measured against
  NuSMV 2.7.1 on a hand-built model whose fair-states set is empty: it prints *"This might make results of
  model checking not trustable"* and then answers two contradictory specifications both `true`. Nothing in
  the repo parses that warning. Today's generator emits total transition relations — every `case` carries
  a `TRUE:` default, with no `TRANS`/`FAIRNESS` sections — so the condition is not reachable through the
  product now; this surface is what makes it visible if that ever changes.

### 2026-08-16 (later)

#### Removed

- **`GET /api/verify/traces/{id}/smv` and the `trace.smv_model_content` column.** The endpoint could only
  ever answer a byte-identical copy of the run's model: one model string is generated per run and was
  written to the run row *and* to every trace of that run, so a run with three violated specifications
  stored and served the same bytes from four addresses — 345 KB of duplication across 30 trace rows on a
  development database, growing with every run. Its documented justification, that a counterexample stays
  self-contained after its run is deleted, was contradicted by `deleteRunInternal`, which deletes every
  trace and then the run in one transaction. Nothing called it: no client method, no test, no script. The
  run-keyed endpoint strictly dominates it and is the only one that works for a run where every
  specification holds. `TraceDto.hasSmvModel`, `TraceSummaryDto.hasSmvModel` (queried, mapped, serialized
  and read by nothing) and the frontend's `AvailableTraceSummary.hasSmvModel` went with it.
  `ddl-auto: update` never drops a column, so an existing database keeps `smv_model_content` as dead
  nullable storage until dropped by hand.

- **35 orphaned i18n keys, and two dead `boardApi` client methods.** Each key was translated twice and
  rendered nowhere; six of them (`keyMetrics`, `specificationResults`, `traceSummary`,
  `verificationContext`, `specResultsSummary`, `viewTrace`) were added and abandoned in one refactor. The
  bundle→source direction now has a guard, which is why they accumulated: the existing check only
  verified that a key a source file *names* resolves. `getVerificationTraces` and
  `deleteVerificationTrace` had no callers of any kind — both endpoints stay, exercised by E2E and by the
  assistant's `DeleteTraceTool` respectively. Also removed: a never-assigned `runIdForSmv` type field and
  two over-broad `export` keywords on internal validators.

#### Fixed

- **Interactive controls in both replay bars had no pointer cursor.** Measured on the counterexample bar:
  10 of 12 enabled controls rendered `cursor: default` — play, close, run details, previous/next state,
  both state chips, both help buttons. `board.css` carries one clickability rule added precisely so
  per-component opt-in could not miss anything, and it missed both replay bars entirely, because they are
  `position: fixed` **siblings** of `.iot-board` rather than descendants. This is the same structural fact
  that stops `--board-floating-gap` resolving inside them: one DOM relationship, two unrelated-looking
  bugs. Two further surfaces were uncovered for the same reason and are now fixed at the surface level:
  any button inside a dialog overlay that is not built from `iot-dialog-btn` (measured: 2 of 13 in the
  verification result dialog), and the teleported template-preview close button.

- **The device node's drag affordance was inverted.** A node is `<div role="button" aria-disabled>`, so
  `.iot-board [role='button']:not([aria-disabled='true'])` at specificity (0,3,0) outranked
  `.iot-board .device-node { cursor: grab }` at (0,2,0). Verified with an injected probe: an **unlocked**
  node showed `pointer` and a **locked** one showed `grab` — the one draggable object on the canvas
  invited a click, and a node locked by read-only playback invited a drag that cannot happen.

- **A saved trajectory's model download vanished when the same run was reopened from history.**
  `lastSimulationResult` has three writers and the history-replay one dropped `hasSmvModel` and
  `historyPersistence`, so whether the artifact was offered depended on *which UI path opened the run*
  rather than on the run. The same trajectory offered its model right after executing and then claimed
  "SMV model not available (may be a record saved before model persistence was enabled)" after a reload —
  specific enough to be believed, so a user would not retry, while the model sat on disk.

- **Model-generation warnings rendered as `[object Object]` in the counterexample dialog.** The only
  `{{ issue }}` interpolation in the codebase; the five other renderers of that array all destructure it
  and localize the reason code. The block said "N of your rules were not modeled" — the statement that the
  verdict beside it is weaker than it looks — and rendered as garbage. It and the incomplete-model warning
  also sat outside both labelled sections of that dialog, which is why the run-vs-evidence split missed
  them; both are now inside the run-context section where they belong.

- **A verification or simulation history row silently claimed the canvas was unchanged.** Its drift check
  compares five integers, so inverting a rule's relation operator, changing an environment variable's
  value or moving a specification's threshold left every count equal — and the row rendered *nothing*,
  which reads as "this verdict still describes my canvas". That is the one claim this product never makes
  elsewhere. The verdict is now three-valued and states what was compared. A real fingerprint is not
  available here by contract, not by omission: `PersistedModelContextIntegrity` *rejects* a
  `modelFingerprint` on a verification or simulation snapshot, so populating it would make existing rows
  unreadable. The doc said "currently omit it", implying an unfinished feature, and now says so correctly.

- **`docs/examples/elderly-care-comprehensive-scene.json` is now tracked.** It is generated, and it is
  byte-identical to generator output (all five shipped scenes reproduce exactly), so a fresh clone no
  longer has a broken reference from the docs.

### 2026-08-16

#### Changed

- **The SMV model download moved to where the model actually belongs, and became a visible action.**
  The model is a scene-level artifact: one is generated per run, and every counterexample that run
  produced came out of that same model. It was offered per counterexample — in the
  counterexample-details dialog and on every trace row in run history — which handed out the same file
  once per counterexample under a name implying one model each, and put a scene-level artifact behind a
  per-evidence surface. It was also a footer `--secondary` button beside Close in both result dialogs,
  i.e. styled as an afterthought, which is how it stayed unfound even after it started rendering.

  Both result dialogs now carry an `Artifact from this run` section in the dialog body, with the model
  named and its scope stated from the run's own frozen snapshot (device, rule and specification
  counts), so a reader knows what they are downloading before clicking. The control is `--primary`, and
  when a run stores no model it is **disabled with the reason shown** rather than hidden — a control
  that silently vanishes is indistinguishable from a missing feature, which is exactly how this read
  while the backing flag was never sent. The counterexample dialog links to its owning verification
  run instead, and the per-trace download is gone from history (the run row keeps the single copy,
  which is also the only one that exists for a run where every specification held).

  `GET /api/verify/traces/{id}/smv` remains supported for API consumers holding only a trace id, but
  the web client no longer calls it and its client method is deleted. Placement is pinned by
  `runArtifactPlacement.spec.ts`, and `e2e/smv-model-download.spec.ts` now covers the download
  end to end — that clicking delivers a NuSMV model of the submitted scene, which nothing verified
  before.

- **The counterexample-details dialog now separates the counterexample from the run that produced
  it.** Its contents read as one flat list of "counterexample properties", but only the violated
  specification and the state count describe that counterexample; the attack and privacy chips and
  model completeness describe the run and are identical across every counterexample it produced. A
  reader comparing two counterexamples had no way to tell which differences were even possible. The
  frozen per-trace copy of that context stays — a counterexample must survive its run being deleted —
  but it is now labelled as run context, under a heading that says it repeats.

### 2026-08-15

#### Fixed

- **The SMV model download was unreachable from every surface that offered it.** Four buttons —
  verification result dialog, simulation result dialog, and both per-record buttons in the run-history
  panel — were each gated on a `hasSmvModel` flag that the backend never sent for that response.
  `SimulationResultDto` had no such property at all; `VerificationRunSummaryDto`, `TraceSummaryDto` and
  `SimulationTraceSummaryDto` declared none, so the history rows could not answer either. The
  frontend then dropped the one flag the backend *did* send: the builders that construct a result from
  a history run and from a completed async task both omitted it. Every gate read `undefined`, no
  button rendered, and nothing reported an error — the feature looked absent rather than broken.

  All three summary DTOs now carry the flag, computed in SQL (`CASE WHEN smvModelContent IS NOT
  NULL AND <> ''`) so a history page never loads tens of thousands of characters per row to decide
  whether one button can succeed. `SimulationResultDto` gained the derived getter its verification
  counterpart already had, and the four frontend builders carry the flag through. The
  counterexample-details dialog reached from the replay bar gained the download it never had, which is
  the surface where a reader is actually looking at the model's evidence.
  `VerificationTaskRepository.findCompletedRunSummaries` replaces the derived
  `findByUserIdAndStatusOrderByCompletedAtDescIdDesc`, because a closed projection cannot name a
  computed column that no entity property backs. Which DTO gates which surface is now a table in
  [docs/api/verification.md](docs/api/verification.md).

- **The verification result dialog rendered its counterexamples and its per-specification verdicts
  twice each.** A consolidation pass added a promoted counterexample list and a collapsible
  spec-results section without removing the originals further down the dialog, so a run with three
  violations showed six entries under two different headings, and every verdict appeared in both an
  always-open card and a collapsed one. The card also restated the satisfied/violated/inconclusive
  counts that the summary grid states at the top of the same dialog.

  One list each now. The promoted copy of the counterexample list called
  `selectAndPlayVerificationTrace(index)` — that function takes a trace **id**, so its "view" button
  requested traces 0, 1, 2 — and it carried none of the `data-testid`s the E2E flow addresses, nor the
  notice explaining why "Fix" is withheld on a stale result. The surviving list keeps the working
  handler and both. The spec-results list keeps the collapsible wrapper and regained the fields the
  new copy had dropped: the variable-source chips that distinguish two specifications sharing a
  template label, the labelled formula block, and the per-row technical disclosure.

- **Untranslated `app.*` keys were rendered to the user in the verification and counterexample
  dialogs.** `app.modelSnapshot`, `app.modelGenerationIssues` and `app.createdAt` were never defined,
  so the dialogs displayed the raw key strings. The first two now use the existing owners
  (`app.modelRunSnapshotTitle`, `app.generationWarnings`) and the third has a new `app.traceCreatedAt`.
  Twelve duplicate keys added alongside them are gone: each shadowed an existing definition, `technicalDetails`
  silently changed an unrelated dialog's label, and `sceneImportTemplateNameMismatch` collided with the
  import validator's parameterised message, leaving `{name}` placeholders unresolved in the dialog
  heading (now `sceneImportTemplateNameMismatchTitle`). The dead
  `viewTimelineForSequence` hint — defined, never rendered — was removed with them.

- **Counterexample emphasis on the canvas was never scoped to the violated specification.** It read
  `violatedSpec.boundDeviceIds`, a field `Specification` does not declare, so the list was permanently
  empty and the documented "all devices in the state" fallback ran on every violation. The subject
  devices are now derived from the specification's own `devices` list and its A/IF/THEN condition
  `deviceId`s. The guard that covered this asserted the fabricated field by name, so it had pinned the
  defect in place.

- **The simulation result dialog lost its "view timeline" button** while
  `handleSimulationTimelineAction` stayed behind unused, leaving state-by-state playback of a run
  reachable only by reopening it from history. Restored. Eight `acknowledge()` calls in the scene-import
  dialogs passed `okText`, which is not an option on that helper (`confirmText` is), so those buttons
  silently fell back to the generic label.

### 2026-08-14

#### Changed

- **Scene import is now a single server-owned contract: `POST /api/board/scene` takes an exported
  scene file verbatim.** The portable format and the internal board write DTOs are deliberately
  different — the file carries no database ids, no derived caches, and no `impactToken`, which is what
  makes an export re-importable and portable between accounts. But the *conversion* between them had
  been written by hand three times: in the frontend's `api/board.ts` for file import, in the backend's
  `ScenarioDraftBatchMapper` for chat-applied drafts, and a third time inside the scene NuSMV tests.
  Nothing tied the copies together, so the same scene could be admitted differently depending on which
  button the user pressed, and the `variableSource` field was dropped by each copy independently —
  three separate user-visible failures (a 502 on a whole recommendation response, and two rejections
  naming a field the scene actually carried) before all three agreed.

  There is now one converter, `PortableSceneBatchMapper`, shared by the new endpoint and
  `apply_scenario`. Clients upload the validated file and no longer restate the mapping.
  `POST /api/board/batch` remains the lower-level command for scene clear, which has no portable file
  behind it. The schema id and version, previously written out at four independent sites — including
  one left at 4 while the producer emitted 5, which rejected every generated scenario as
  "unsupported" — now come from `PortableSceneFormat`, and a cross-stack contract test pins the Java
  and TypeScript declarations against each other so a field added to one side cannot be silently
  dropped by the other. Both full-scene endpoints share the dedicated 64 MiB body limit.

  The two scene NuSMV tests previously hand-built rules and specifications from the scene JSON, so they
  verified "this file, parsed the way the test author understood it, generates correct SMV" rather than
  "this file, imported, generates correct SMV" — a scene could pass the suite and be refused by the
  real endpoint. They now import through the product's own path, and the six shipped scenes in
  `docs/examples/` regression-test admission as well as generation.

#### Fixed

- **An infinite ("eventual response") counterexample now shows its loop instead of a frozen final step.**
  Templates 2, 5, and 6 assert liveness, so NuSMV refutes them with a lasso path — a prefix plus a cycle
  that repeats forever without ever reaching the required state — and it terminates that path by
  re-printing the loop entry with *no* variable lines. The `-- Loop starts here` marker was discarded in
  two places: `NusmvExecutor` began copying the trace at the first state line, dropping any marker printed
  above it, and `SmvTraceParser` had no notion of the marker at all. So the closing state materialized
  identical to its predecessor and played back as a step where nothing moved, with the change panel
  reporting "no observable changes": an accurate diff of the state, and a complete misreading of the
  trace. Compounding it, template 5 was absent from the set of templates whose violation step is known, so
  a "the front door never re-locks while nobody is home" violation marked no step on the rail and
  emphasised no device on the canvas either — the run's whole point arrived in silence. Measured on a real
  board (5 states, cycle from index 3, closing state byte-identical to its predecessor).

  `TraceStateDto` now carries `loopStart` / `loopBack`; the canvas emphasises every state of the cycle
  rather than one step, because no single state is at fault; the rail rings the cycle and labels its
  return edge; and the change panel explains the repetition where it previously reported an empty diff.
  Whether the cycle counts as the violation is decided by the **template**, not by the marker's presence:
  NuSMV also prints it for safety counterexamples (verified on a CTL `AX` trace and an LTL `G(p)` trace,
  the latter carrying it twice), so keying off the marker alone marked a cycle for a safety violation and
  re-admitted template 4 — the one template that must deliberately claim no step. Template 2 likewise
  left the last-state set, which cannot describe a liveness fault. Where several markers appear the last
  one begins the cycle, matching how `loopBack` is derived.
  (`backend/.../executor/NusmvExecutor.java`, `backend/.../parser/SmvTraceParser.java`,
  `backend/.../dto/trace/TraceStateDto.java`, `frontend/src/views/Board.vue`,
  `frontend/src/components/CanvasBoard.vue`, `frontend/src/components/PlaybackChangePopover.vue`,
  `frontend/src/types/verify.ts`, `docs/architecture/verification-flow.md`,
  `docs/architecture/spec-templates.md`, `docs/api/verification.md`)

- **A simulation replay no longer marks violation steps borrowed from a counterexample.** `savedTraces` is
  never cleared, and `currentTrace` prefers it unconditionally, so a counterexample opened earlier in the
  session stayed selected while a simulation replayed through the same `highlightedTrace`. The canvas then
  outlined the old counterexample's bound devices mid-simulation, on a run that violated nothing. The
  canvas emphasis is now gated on the active playback kind. (`frontend/src/views/Board.vue`)

- **The counterexample dialog no longer repeats its own entry point, and returns to the playback.** Its
  footer carried "Run details" — the exact label of the replay-bar button that opens it — and that action
  navigated *away* to the verification result. So the one primary action echoed the control the user had
  just pressed and led further from the trace they were watching. The simulation run-details dialog is
  the model: its footer returns to the timeline it came from. This now does the same, which is honest
  because opening the dialog never touches `traceAnimationState.visible` — the timeline stays mounted
  underneath. Escalating to the owning run moved into the body, labelled "Verification Result" after its
  destination. Verified in a real browser: the footer returns to the timeline and the dialog closes.
  (`frontend/src/views/Board.vue`, `frontend/src/views/board/counterexampleDetailsDialog.spec.ts`)

- **A violated run with no replayable counterexample now says so.** History gated its whole evidence
  block on the trace list being non-empty, which hid it in precisely the case its own inner warning
  exists to explain: a run can count a specification as violated and produce no replayable
  counterexample — `VerificationServiceImpl` logs "violated (no counterexample)" when NuSMV returns
  none, and skips the trace when the parsed state list is empty. So the row showed "2 violations" with
  nothing explaining why none could be replayed, while the sentence written for it
  (`counterexampleCount < violatedSpecCount`, true at zero) sat inside the hidden block. The block is
  now gated on the run having evidence to describe. (`frontend/src/components/TraceHistoryPanel.vue`,
  `frontend/src/components/__tests__/TraceHistoryPanel.spec.ts`)

- **A verification verdict is announced once, not twice at the same moment.** `showResultDialog` derives
  from `verificationResult`, so every path that set the result opened the dialog and then toasted the
  same fact over it — measured in a browser as a toast reading "Found 1 specification violation(s)"
  covering a dialog subtitled "Found 1 violation(s)". `utils/feedback.ts` states the rule ("a success
  whose result is already visible on screen gets no toast at all") and `presentFuzzingRun` already
  applied it, dismissing transient notices so they cannot cover the result's title or primary actions;
  verification was the path that had not been given the same treatment. The toast still fires when
  nothing on screen carries the verdict — an async run finishing while the user is elsewhere — so the
  suppression is per-call-site rather than a removal, and the separate persistence-failure notice is
  untouched, since a save that did not happen is not something the verdict dialog states. Attention on a
  violation is unchanged and was measured: the dialog still auto-opens in danger tone with 8
  danger-toned elements and the violation count. (`frontend/src/views/Board.vue`,
  `frontend/src/views/board/verdictAnnouncedOnce.spec.ts`)

- **The counterexample dialog reports skipped specifications, not just disabled rules.** Its
  incomplete-model sentence named only `disabledRuleCount`, while both sibling verification surfaces —
  the result dialog and the replay bar — name both counts. Since a trace's `modelComplete` is false when
  *either* count is non-zero, a run that skipped specifications but disabled no rules rendered as "0
  rule(s) were disabled", and a reader arriving from the replay bar saw a smaller omission set than the
  bar they came from. The wording keeps its caveat about what a reduced-model counterexample does and
  does not establish. (`frontend/src/views/Board.vue`, `frontend/src/assets/i18n.ts`)

- **A change row no longer claims a reading that never existed.** `playbackDeviceChangeDetails` built its
  variable diff without filtering `observed`, while both sibling readers — the rail's summary and the
  canvas badges — filter it, for a reason recorded beside one of them: an unobserved row is omitted
  rather than shown as `N/A`, because `N/A` claims a reading was expected and went missing. The flag can
  flip between states, so the change popover reported transitions like `illuminance: 20 -> N/A` for a
  device that never had that reading, while the canvas correctly drew nothing.
  (`frontend/src/utils/traceView.ts`)

- **An id-less rule now highlights its edge instead of silently highlighting nothing.**
  `TraceTriggeredRuleDto.ruleId` is nullable, and the frozen scene sets the edge's `ruleId` to
  `undefined` for those same rules — so both sides lose the id together, and requiring it meant no edge
  ever lit: the rail named a rule the canvas ignored, while the on-screen explanation blamed board
  drift, which was false. When *both* ids are absent the match falls back to position, which is sound
  here and not a guess: during playback the edges come from the frozen scene, both indices index the one
  submitted rule list, and `copyRules` maps it one-to-one without filtering. A present-but-different id
  still means "not this rule", and an id on one side only still refuses to match — that combination
  means the two snapshots disagree about identity, where position would be coincidence. This does not
  reopen what the id-only rule forbade, whose recorded concern was guessing from a *current* list
  position on a board that may have been reordered since the run.
  (`frontend/src/utils/traceEdgePlayback.ts`, `frontend/src/utils/__tests__/traceEdgePlayback.spec.ts`)

- **A fuzz replay cannot present a NuSMV artifact.** The counterexample rail's checked-expression
  disclosure was the one absent-field read there with no `activeFuzzingFinding` gate, where all five
  sibling chips have one. It was safe only because the synthesized fuzz trace happens to set
  `checkedExpression: ''`, which is falsy — safety by accident, and giving that placeholder any
  non-empty value would have shown a checked expression for a run that never invoked NuSMV.
  (`frontend/src/views/Board.vue`)

- **The SMV download works, and is offered only when it can.** Three defects made it fail in every
  case a user could reach. `TraceMapper.toEntity` never copied the model, so `smv_model_content` was
  `NULL` on every verification trace ever written; neither mapper read it back, so both controllers saw
  a blank model and returned `500`. And the button was gated on the record's **id** — which every
  persisted run has — rather than on whether a model exists, so it appeared for records that had none
  and the click failed with a bare "download failed". The responses now carry a derived `hasSmvModel`
  and the buttons require it. Verified end to end in a browser against a rebuilt database: the model
  persists (9182 bytes for the RFID demo), the button renders, and the file downloads with
  `MODULE main` intact.
  (`backend/.../util/mapper/TraceMapper.java`, `.../SimulationTraceMapper.java`,
  `.../dto/trace/TraceDto.java`, `.../dto/simulation/SimulationTraceDto.java`,
  `frontend/src/views/Board.vue`, `frontend/src/types/verify.ts`, `frontend/src/types/simulation.ts`)

- **Device import no longer creates the payload you just replaced.** The import preview is debounced by
  300ms to avoid re-parsing on every keystroke, and everything downstream of the box — the preview list,
  the validity count, the Create button's label — is derived from that lagging copy. The button,
  however, was gated on the *count* alone, so for 300ms after new content arrived it was enabled and
  armed with the previous payload: paste or choose a second file, click inside the window, and the
  earlier one was imported instead. Reproduced deterministically in E2E, where a CSV import produced
  duplicate JSON devices (`import_phone_1` / `import_alarm_1`) and the CSV's own devices never arrived.
  The gate now also requires the preview to match the current text, which covers typing, paste, file
  selection and any entry point added later; the file path additionally flushes the debounce, since a
  file selection is one discrete event rather than a keystroke. Fixing only that path left the paste
  window open — measured, as the E2E failure moved one assertion further along. Present since the
  2026-08-13 frontend performance pass that introduced the debounce.
  (`frontend/src/components/ControlCenter.vue`,
  `frontend/src/components/__tests__/ControlCenterImportFreshness.spec.ts`)

- **An exploration finding the server rejected can no longer be replayed or handed to the verifier.**
  `dataAvailable === false` marks a finding whose own detail load was rejected as corrupt.
  `TraceHistoryPanel` disables both of its actions for exactly that, and the exploration result dialog
  binds the same run object — but omitted the check, so a finding greyed out in history stayed fully
  armed in the dialog. Replay merely re-failed; "verify formally" was worse, because that handoff is
  seeded from the summary without re-fetching the finding, so it proceeded on evidence the server had
  already refused. The marker exists only on the summary arm of the finding union, so the guard narrows
  with `'dataAvailable' in finding` rather than casting the union away.
  (`frontend/src/components/FuzzingResultDialog.vue`,
  `frontend/src/components/__tests__/FuzzingResultDialog.spec.ts`)

- **An absent model reports `404`, not `500`.** A run recorded before the model was stored has none,
  and no migration can invent one — so absence is a fact about the record, not a server fault. `500`
  blamed the server and told the user nothing actionable.
  (`backend/.../controller/VerificationController.java`, `.../SimulationController.java`)

- **Downloaded files keep their `.smv` name.** Both endpoints passed UTF-8 to
  `ContentDisposition.filename(...)`, which makes Spring emit the legacy parameter as an RFC 2047
  encoded-word — observed in a real browser download as
  `filename="=?UTF-8?Q?verification-trace-7.smv?="`. The filenames are pure ASCII, so the charset
  overload is dropped. (`backend/.../controller/VerificationController.java`,
  `.../SimulationController.java`)

- **A simulation opened from history offers its model.** The history-load path built its result
  without the `traceId` the button needs, although it received it as an argument — so the download was
  unreachable for exactly the stored runs a user opens to inspect, while both live-run paths carried
  it and looked correct. (`frontend/src/views/Board.vue`)

- **The counterexample details dialog now compiles, and its download reaches a real run.** The dialog
  shipped with three type errors, so `npm run build` — whose first step is `vue-tsc -b` — produced no
  bundle at all: its ref was declared as the shared `TraceEvidence` base while being assigned a `Trace`,
  making the `id` the SMV download addresses inaccessible, and it bound `violatedSpec.name`, a field
  `Specification` does not have. The ref is now the `Trace` union, so `v-if` can discriminate the
  persisted arm (an unsaved run genuinely has no id, and a cast would have offered it a download it
  cannot serve); the label falls back `templateLabel → formula → id`, as fuzzing findings already do.
  Its footer promised "view trace" with a play icon while the trace was already playing and only
  dismissed the dialog — it now escalates to the owning run, restoring the per-spec verdicts and the
  run's other counterexamples, which rewiring the replay bar to this dialog had re-stranded. Its trace
  lookup reused `currentTrace` instead of keeping a second copy that implemented only one of that
  accessor's two branches and reported "no run details available" mid-replay. Raw `red-*` utilities
  throughout its body became danger roles, which `semanticColourOwnership.spec.ts` requires of every
  component and view. (`frontend/src/views/Board.vue`, `frontend/src/assets/i18n.ts`,
  `frontend/src/views/board/counterexampleDetailsDialog.spec.ts`)

- **A simulation opened from history offers its SMV model.** The download is gated on `traceId`, and the
  history-load path built its result without one although it received it as an argument — so the model
  was unreachable for exactly the stored runs a user opens in order to inspect them, while both live-run
  paths carried it and looked correct. (`frontend/src/views/Board.vue`)

- **Both SMV download endpoints stop answering 500 for every run.** `TraceMapper.toEntity` never copied
  `smvModelContent`, so `smv_model_content` was `NULL` on every verification trace ever written, and
  neither mapper read it back, so both controllers saw a blank model — which they correctly treat as a
  persistence defect and report as `500`. Three hand-written copy sites, each independently pinned by a
  test now. (`backend/src/main/java/cn/edu/nju/Iot_Verify/util/mapper/TraceMapper.java`,
  `backend/src/main/java/cn/edu/nju/Iot_Verify/util/mapper/SimulationTraceMapper.java`,
  and their tests)

- **Playback shows what caused the selected step without a click, on both rails.** Collapsing the
  counterexample rail's cause row into a disclosure broke the guard that names this requirement
  ("shows what caused the selected counterexample step without an extra click") and reverted a
  considered decision, whose recorded rationale had been left in place above the inverted code. The
  simulation rail had been collapsed wholesale for symmetry. Both now split by role rather than by
  surface: the cause is chrome-free and always visible, while the simulation rail's device and
  environment tables keep their disclosure, since they are genuinely tall and the canvas nodes are the
  richer authority for device values. The simulation rail had no unit guard at all — the reason this
  shipped — and now has one; the E2E helper asserted the panel only after clicking it open, so that
  assertion is now stated as containment, which holds whatever the expansion state.
  (`frontend/src/views/Board.vue`, `frontend/src/components/SimulationTimeline.vue`,
  `frontend/src/components/__tests__/SimulationTimeline.spec.ts`,
  `frontend/e2e/authority-model-audit.spec.ts`)

- **Three i18n keys the new dialog referenced existed in neither locale.** `app.violationFound` and
  `app.expression` now reuse the existing `specificationViolationFound` and `actualCheckedExpression`
  rather than adding duplicates; `verificationIncompleteModelDetail` is new because the verification
  side had no dialog-body wording, and it deliberately differs from the simulation one — a
  counterexample really does refute the specification for the model that was checked, so its caveat is
  about scope, not validity. (`frontend/src/assets/i18n.ts`, `frontend/src/views/Board.vue`)

- **A counterexample replay can reach the run that produced it.** The trace timeline had no way back to
  the verdict: opening a replay clears `verificationResult`, so the per-spec results, the other
  counterexamples of the same run, and the SMV download were unreachable until the user left the replay
  and re-opened the run from history. The replay bar now carries a "Run details" button. Because the bar
  addresses one trace, it opens a counterexample-scoped dialog — the violated specification, that
  trace's own state/step counts, its model snapshot, its completeness warnings, and the SMV model of the
  run that produced it — and that dialog's footer escalates to the full run when one is retained. An
  exploration finding opens the exploration dialog instead, chosen on `activeFuzzingFinding`, so a
  candidate result is never dressed in the verifier's surface. The staleness flag keys on the retained
  run rather than on the dialog, so a board edit made while only the replay was open still warns that the
  canvas moved under the run.
  (`frontend/src/views/Board.vue`, `frontend/src/assets/i18n.ts`,
  `frontend/src/views/board/counterexampleDetailsDialog.spec.ts`)

- **Dialog dismiss preserves deep link when timeline is visible.** `dismissResultDialog()` and
  `dismissSimulationResultDialog()` now conditionally clear the `run=` deep link: only when the
  corresponding timeline (`traceAnimationState.visible` or `simulationAnimationState.visible`) is not
  visible. This ensures that closing the dialog while watching a trace keeps the URL naming the run, so
  refreshing or sharing the link still addresses the playback surface.

- **The simulation result dialog no longer offers two different closes.** Its footer button relabelled
  itself "Return to Timeline" once playback was visible and then merely dismissed the dialog, so the same
  control meant "open the timeline" or "close this" depending on state, and it sat disabled whenever a
  counterexample replay held the canvas. The footer now carries only "View Timeline", which always opens
  playback; dismissing is the header close, as in every other dialog. Deleted i18n key `returnToTimeline`.
  (`frontend/src/views/Board.vue`, `frontend/src/assets/i18n.ts`)

- **Leaving playback reads as leaving, not as closing a panel.** Both timelines exited through an unlabelled
  grey `close` glyph, indistinguishable from a dialog dismiss although it discards the replay overlay and
  returns the canvas to the live scene. Both now use a labelled red "Exit" control (`app.exit`,
  `app.exitTimeline`); the `simulation-timeline-close` and `trace-timeline-close` test ids are unchanged.
  (`frontend/src/views/Board.vue`, `frontend/src/components/SimulationTimeline.vue`,
  `frontend/src/assets/i18n.ts`)

- **Warning banners in the simulation dialog matched the verification dialog's radius.** The stale,
  incomplete-model, short-horizon and unavailable-semantics banners were `rounded-lg` against
  `rounded-xl` next door. (`frontend/src/views/Board.vue`)

#### Added

- **The model a verification run checked is downloadable from the run itself.** `GET
  /api/verify/runs/{id}/smv`, stored on the run (`verification_task.smv_model_content`). A run where
  every specification holds produces no counterexample, so a trace-keyed download left that run's
  model unreachable — the case where a reader most wants to confirm what was actually proved.
  Measured before the change: a passing run persisted with 0 traces and no route to its model; after,
  the same run serves 7286 bytes. The verification result dialog now keys its download on the run
  rather than on `traces[0]`, which was an arbitrary choice among counterexamples that all share one
  model. The per-trace endpoint stays, so a counterexample remains self-contained after its run is
  deleted. (`backend/.../po/VerificationTaskPo.java`, `.../repository/VerificationTaskRepository.java`,
  `.../service/VerificationService.java`, `.../service/impl/VerificationServiceImpl.java`,
  `.../dto/verification/VerificationRunDto.java`, `.../dto/verification/VerificationResultDto.java`,
  `.../util/mapper/VerificationTaskMapper.java`, `.../controller/VerificationController.java`,
  `frontend/src/api/board.ts`, `frontend/src/views/Board.vue`, `frontend/src/types/verify.ts`,
  `docs/api/verification.md`, `docs/api/rest-endpoints.md`)


- **The SMV model a run checked is downloadable from its run details.** Previously the only window into
  the generated model was the NuSMV diagnostic output — stdout from the checker, truncated to 10,000
  characters, and not the model itself. The model source was written to a temp file, handed to NuSMV, and
  discarded, so a stored verdict could not be re-examined in a third-party checker or cited in a report.
  Each run now persists the exact model it checked alongside its result (`smv_model_content`, `TEXT`,
  capped at 65,000 UTF-8 **bytes** — the column's own unit — cut on a character boundary and marked
  `-- [TRUNCATED: Original size N bytes]` so a truncated file cannot pass as complete), and both
  run-details dialogs offer it as a download:
  `GET /api/verify/traces/{id}/smv` → `verification-trace-{id}.smv` and
  `GET /api/simulate/traces/{id}/smv` → `simulation-trace-{id}.smv`, both `text/plain;charset=UTF-8`
  attachments. A missing model on a persisted run is a persistence defect, not a normal state, so it
  returns `500` rather than an empty file; the simulation button appears only for a saved trajectory,
  since a preview-only run has no id to address. The verification result dialog and the simulation run
  details no longer print the model text inline.
  (`backend/src/main/java/cn/edu/nju/Iot_Verify/po/TracePo.java`,
  `backend/src/main/java/cn/edu/nju/Iot_Verify/po/SimulationTracePo.java`,
  `backend/src/main/java/cn/edu/nju/Iot_Verify/dto/trace/TraceDto.java`,
  `backend/src/main/java/cn/edu/nju/Iot_Verify/dto/simulation/SimulationTraceDto.java`,
  `backend/src/main/java/cn/edu/nju/Iot_Verify/service/impl/AbstractAsyncTaskService.java`,
  `backend/src/main/java/cn/edu/nju/Iot_Verify/service/impl/VerificationServiceImpl.java`,
  `backend/src/main/java/cn/edu/nju/Iot_Verify/service/impl/SimulationServiceImpl.java`,
  `backend/src/main/java/cn/edu/nju/Iot_Verify/controller/VerificationController.java`,
  `backend/src/main/java/cn/edu/nju/Iot_Verify/controller/SimulationController.java`,
  `frontend/src/api/board.ts`, `frontend/src/api/simulation.ts`,
  `frontend/src/types/simulation.ts`, `frontend/src/views/Board.vue`,
  `frontend/src/assets/i18n.ts`, `docs/api/verification.md`, `docs/api/rest-endpoints.md`,
  `backend/src/test/java/cn/edu/nju/Iot_Verify/service/impl/SmvModelContentTruncationTest.java`)

- **The verification result dialog has a footer close.** It closed only through the header glyph, unlike
  every neighbouring dialog, so a user who had scrolled through a long list of violations had to scroll back
  up to leave. Added a ghost Close in `iot-dialog__footer` (`data-testid="close-verification-result-footer"`)
  that runs the same `dismissResultDialog`. No "View counterexample" was added beside it: a run can hold
  several, so the per-trace "View trace" buttons in the violations list remain the entry point.
  (`frontend/src/views/Board.vue`)

- **The simulation run details name the model that was run.** The verification dialog had shown the run
  snapshot — device, rule, specification, environment-variable and template counts with the capture
  timestamp — while the simulation dialog reported a trajectory without saying what it was a trajectory of.
  The same card now precedes its run summary, rendered when the result carries a `modelSnapshot`.
  (`frontend/src/views/Board.vue`)

#### Changed

- **Both playback rails read in the same order.** The counterexample rail put the step's values above
  the rail while the simulation rail puts the rail first; nothing defended either choice. Navigate-then-
  read is the order the sibling surface already used, so the counterexample rail now matches it, pinned
  by an assertion in the spec that already slices that overlay.
  (`frontend/src/views/Board.vue`, `frontend/src/views/__tests__/testIdNamespaces.spec.ts`)

- **A local E2E run no longer defaults to half the machine's cores.** `playwright.config.ts` set no
  `workers`, so a bare `npx playwright test` took half the logical cores — 14 on the 28-thread machine
  this was measured on — against one backend, one MySQL and one NuSMV, while every timing decision in
  that file, and the full-suite contract in
  `.github/scripts/run-e2e.sh`, assume serialized or near-serialized execution. Measured consequence: a
  rule-builder save whose duplicate pre-check errored under that load left a confirm overlay open and
  failed a spec that passes when serialized, which cost a full investigation to attribute. Now pinned
  to `1`, matching the full-suite contract. CI is unaffected — it passes `--workers` explicitly on
  every path, and the flag overrides the config. (`frontend/playwright.config.ts`)

- **Attachment downloads share one blob-saving helper.** `api/board.ts` and `api/simulation.ts` each
  carried a byte-identical copy of the blob/anchor/`Content-Disposition` dance, and the run-keyed
  download would have made a third. The helper prefers RFC 5987 `filename*` over the legacy parameter
  and rejects an encoded-word rather than saving it as a filename.
  (`frontend/src/utils/attachmentDownload.ts`, `frontend/src/api/board.ts`,
  `frontend/src/api/simulation.ts`)

- **All three run tools share the evidence tier in the action dock.** Verification was the filled
  `board-tool-button--primary` and simulation/exploration were `board-tool-button--evidence`, a deliberate
  split by epistemic weight: only verification returns a formal conclusion. At the user's request the three
  now render identically as `--evidence`. `actionDockHierarchy.spec.ts` carries the new expectation and
  records that the distinction was dropped by preference, not because it was wrong.
  (`frontend/src/views/Board.vue`, `frontend/src/views/board/actionDockHierarchy.spec.ts`)

- **`dismissSimulationResultDialog` invalidates in-flight history detail loads.** Without it, a run load
  still in flight when the user dismissed the dialog reopened it — the race `dismissResultDialog` had
  already been fixed for on the verification side. (`frontend/src/views/Board.vue`)

- **Dismissing a result dialog keeps the deep link while its playback is on screen.** Both dismiss handlers
  cleared `run=` unconditionally, so closing the dialog over a running replay stripped the params naming the
  surface still visible — a refresh or a shared link then landed on a bare board. They now clear it only
  when the corresponding timeline is hidden; leaving playback still clears the whole link, as before.
  (`frontend/src/views/Board.vue`)

- **Chinese run-status labels follow one pattern.** `验证中` (was `验证中...`), `模拟中` (was `仿真运行中`),
  `探索中` (was `正在探索`) — the dock and the notifications had mixed an ellipsis, a different verb and a
  different aspect marker across the three. (`frontend/src/assets/i18n.ts`)

- **Model trace playback opens with state details collapsed.** The step-values panel and the run
  scope/snapshot block in `SimulationTimeline` are both closed on open, so the playback surface starts as
  the canvas plus the timeline rail rather than a full-height reading panel. Either can still be expanded
  per step. (`frontend/src/components/SimulationTimeline.vue`)

### 2026-08-13

#### Fixed

- **The RFID demo's state-determined variable is documented as load-bearing, not redundant.** The
  `rfid_1` instance carries a `variables` entry for `RFID` even though the `authorized` state declares a
  `Dynamics` value for it, which reads as a duplicate source of truth. It is not: an instance entry is
  the only channel for a variable's *trust* label — `Dynamic` carries no trust field, and a state's
  `Trust` never reaches a state-determined variable. Removing the entry left
  `init(trust_RFID) := untrusted` (the template default) as the generated model's single difference,
  and that alone refutes the scene's trust specification at baseline — the property the scene exists to
  demonstrate. `canonicalizeVariables` overwrites the entry's `value` with the state-declared value, so
  the value is not a second source of truth either. The generator now records this so the entry is not
  deleted again as dead weight. (`scripts/generate-default-template-scenes.mjs`)

#### Performance

- **Trace playback optimized.** Model trace replay (auto-play and manual navigation) was stuttering due to redundant
  per-edge calculations and O(E²) template lookups. Fixed: (1) memoized edge playback state - `getEdgePlaybackClass()`
  and `shouldRenderEdgeFlow()` each called 3× per edge, now cached in `edgePlaybackStateCache` (300→100 scans per
  transition). (2) memoized trace device lookups - watch callback on `selectedStateIndex` called `isNodeTraceChanged()`
  for every node doing 2× O(S) backward scans each, now pre-computed in `traceDeviceCache` (60 O(S) scans → 30 cached
  lookups). (3) pre-computed edge indices - template called `indexOf()` 3× per edge (O(E²)), now included in
  `edgesWithAdjustedPoints`. Combined impact: ~70% faster state transitions, smooth auto-play at 1.5s intervals.
  (`frontend/src/components/CanvasBoard.vue`)

- **Memoized expensive per-node functions.** Four template functions were called multiple times per node per render,
  causing massive redundancy: `getNodeRuntimeBadges()` 6×, `isDeviceAttacked()` 4×, `getNodeSecurityBadges()` 3×.
  With 30 nodes and trace active, this meant 180 + 120 + 90 = 390 redundant calls per render, totaling ~100K
  operations per trace step or zoom frame. Created computed Map caches for each function, reducing calls to 1× per
  node. Also optimized `hasBidirectionalEdges()` from O(E²) to O(E) using pre-computed Set. Combined impact:
  trace step 76% faster (107K→25K ops), zoom 75% faster (6.6M→1.7M ops/sec at 60fps). 
  (`frontend/src/components/CanvasBoard.vue`)

- **Node drag now bypasses Vue reactivity during movement.** Previously, every `pointermove` event (60+ fps) directly
  modified `node.position.x/y`, triggering Vue's reactivity system and forcing Board.vue's 155 computed properties to
  re-evaluate, including expensive operations like `boardAttackSurface` (O(rules + nodes)) and `canvasMapData` 
  (O(nodes + edges)). Now uses a temporary non-reactive position during drag, only committing to the reactive object
  on drag end. This eliminates ~155 computed evaluations per frame, dramatically reducing stutter during node dragging.
  (`frontend/src/utils/canvas/nodeDrag.ts`, `frontend/src/components/CanvasBoard.vue`)

- **Canvas drag performance optimized.** Node dragging was stuttering because every `pointermove` event (60+ fps)
  triggered immediate edge recalculation and the template rendered edges with 8-12 redundant `props.nodes.find()`
  calls per edge. Optimizations: (1) RAF-throttled edge updates during drag, (2) precomputed
  `edgesWithAdjustedPoints` with node Map lookups, (3) precomputed edge style attributes (`particleColor`,
  `arrowMarker`, `particleFillColor`), eliminating all template-time node searches. Reduces per-frame operations
  from ~200 to ~30. (`frontend/src/components/CanvasBoard.vue`, `frontend/src/utils/canvas/geometry.ts`)

- **Board.vue deep watch removed.** The `watch([nodes, rules], syncRuleDerivedEdges, { deep: true })` was
  triggering full edge regeneration on any node/rule mutation, causing O(rules × sources × nodes) complexity
  with repeated `nodes.find()` calls. Created `nodesById` Map for O(1) lookups and removed `{ deep: true }`,
  reducing 2,400+ array searches to 30 Map lookups per update. Also optimized bidirectional edge detection
  from O(edges²) to O(edges) using pre-computed Set. (`frontend/src/views/Board.vue`)

- **SystemInspector.vue template lookup optimized.** Device rendering performed O(D×T) template searches with
  repeated string normalization (20 devices × 50 templates = 1,000 operations). Created `templatesByName` Map
  for O(1) lookups. Also created `devicesById` Map to optimize rule device lookups and added 200ms search
  debouncing to reduce re-computation frequency. (`frontend/src/components/SystemInspector.vue`)

- **ChatView.vue message keys fixed.** Changed from index-based `v-for` keys to stable IDs, eliminating full
  message list re-renders (including expensive Markdown parsing) when loading history.
  (`frontend/src/components/ChatView.vue`)

#### Fixed

- **Counterexample exploration refused its own default settings on every scene the product ships.** The
  admission guard multiplies the search budget by the frozen Board's model complexity and compared the result
  against `MAX_EFFECTIVE_WORK`, but that constant was *identical* to the raw budget ceiling — the three
  per-field maxima multiply to exactly 12,500,000 — so the complexity factor had no headroom to trim and
  consumed the whole budget instead. Measured through the real converter, the six scenes in `docs/examples/`
  cost 90–151 units, which left 0.7–1.1% of the nominal budget: all six rejected the documented default
  (1000 × 20 × 10), the shipped UI default (500 × 20 × 10) was refused by the away-mode scene the demo guide
  is written around, and `fuzz_model_async` failed with a `VALIDATION_ERROR` on every scene, so "run bounded
  exploration" through the assistant could not succeed at all. Benchmarked at 119–168 µs per state step, the
  budget being refused was a 16-second search.

  Four things were wrong, in both directions:

  - **The default mode paid for work it never performs.** `predecessorRuleUnits` prices the paper monitor's
    predecessor walk, reachable only through `evaluatePaper`, yet it was charged unconditionally — and it was
    the single largest term for all six scenes. Complexity is now mode-dependent, which drops
    `BOARD_SNAPSHOT` to 62–85 units (`PAPER_COMPATIBLE` reproduces the old values exactly). Both
    `POST /api/fuzz/workload/preview` and submission therefore take `explorationMode`; a preview computed for
    the other mode would report an estimate submission disagrees with.
  - **A collection the engine never reads inflated the estimate.** `devicePrivacies` was charged although
    `FuzzModel` only ever writes empty privacy lists. Bounding and charging are now separate concerns, so the
    persistence limit on that field is kept while its cost is not — previously the only way to stop charging a
    field was to stop bounding it.
  - **The two ceilings are now two constants.** `MAX_EFFECTIVE_WORK` is 30,000,000, calibrated so a maximal
    run lands near a minute at either end of the measured complexity range. The raw
    `maxIterations × pathLength × populationSize` product keeps its own overflow guard — which stays ahead of
    the per-field range checks so an absurd combination is reported against the request rather than one
    arbitrary field — but no longer shares the weighted ceiling's constant.
  - **The default budget is now pinned against the shipped scenes.** `maxIterations` defaults to `200`
    (40,000 step-slots) in `FuzzRequestDto`, the preview request, `fuzz_model_async` and its schema
    description, and the Board form, with a test asserting the default remains admissible across the measured
    62–151 range. That invariant broke silently before because nothing checked it.

- **Mutation was shrinking the search space instead of exploring it.** A gene is a *residue*, not a choice
  index — `GeneCursor.choose` decodes `floorMod(gene, bound)` — but `mutatedCopy` wrote the decoded index
  back, collapsing a full-range `long` into `[0, bound)`. The same gene index sees different bounds on
  different trajectories, because `nextCandidates` clamps and dedups at a domain edge (measured on `[0,10]`
  with `NaturalChangeRate [-1,1]`: 2 candidates at either edge, 3 in the interior), while
  `mutableChoiceBounds` only records the bound from the one observed path. So a gene mutated while its slot
  showed 2 candidates could never afterwards select index 2 once the slot showed 3 — measured as a hard zero
  in that bucket over 300k samples, and a fixed point, since a bound-2 mutation then orbits `{0,1}` forever.
  The effect was monotone and directional: each lineage drifted toward the lowest-delta end of every clamped
  slot, which is precisely the wrong bias for a specification only violable near its *upper* bound. The
  mutation now rewrites the gene's residue while keeping its magnitude, so every candidate stays reachable.
  Two overflow edges are covered by tests: `gene + offset` wraps to a wrong residue near `Long.MAX_VALUE`,
  and even the `floorDiv` form wraps there when the new choice exceeds the old one, silently producing a
  mutation that changes nothing.

- **A specification that could not be evaluated at all competed as a search parent.** `FuzzModel`'s distance
  score clamped a non-finite distance to `1.0`, and the engine breeds the *minimum*-distance candidate, so an
  unscorable target outranked a genuinely close one scoring in `[0, 1]`. Reachable for a template 4
  specification at `pathLength` 1, where no successor state exists and the scoring loop never runs. Such a
  distance is now `POSITIVE_INFINITY`, which loses every comparison — correct, because the specification
  offers no guidance. (The paper monitors cannot reach this: `PaperStructuredMonitorFactory` always wires
  `ACTIVE -> VIOLATION` directly and the BFS ignores guard satisfiability, measured at 1 hop for templates 1/3
  and 2 for template 4.)

- **The scenario prompt never stated the reachability rule its own validator enforces.** `RecommendScenarioTool`
  reports `unreachableRuleConditions`, `ruleCommandPrestateUnreachable` and `unreachableSpecConditionGroup`, but
  the words 可达 / Transitions / NaturalChangeRate appeared nowhere in its prompt — the rule and specification
  prompts each gained that paragraph while this one was missed, so a scene candidate could still be discarded
  for a constraint the model was never given. The new guard did not catch it because the table mapped all three
  reachability codes to the *satisfiability* sentences, which are a different check with a different remedy: the
  table looked total while the gap stayed open, which is the exact failure its own comment says it exists to
  prevent. Two further rows keyed on bare enum alternations (`trusted|untrusted`, `public|private`) that occur
  three times each in that prompt, so deleting the environment-variable line they describe left them passing on
  the device-level occurrences; both are now anchored to that line. The per-tool reason-code floor was pinned at
  the current count, which would have reported a legitimate removal of a rejection path as "the scan is broken" —
  it now asserts what it means (the scan found something) and leaves coverage to the aggregate floor.

- **Several prompt rules were stricter than the code, which costs recommendations invisibly.** Drift had been
  audited in one direction only — rules the validators enforce but the prompt never stated. The opposite
  direction produces no test failure and no error: nothing is wrongly accepted, the model simply declines
  candidates the backend would have taken. Corrected: the rule prompt flatly forbade writing an API event as
  `= TRUE`, which `validateRecommendation` in fact accepts, normalizes and reports as
  `apiEventSyntaxNormalized` — the tool's own adjustment copy already said so, so the prompt contradicted a
  message the same file emits. The blanket "any extra field discards the candidate" now names invented fields
  instead, because several allowlisted keys (`deviceLabel` as a tolerated alias; `contentPrivacy`,
  `contentDeviceLabel` and `templateLabel` written by the backend after validation) are harmless, and the
  blanket wording also read as pressure to fill in every skeleton field — for a specification, omitting the
  condition arrays the template does not use is the correct answer, not a missing field. Key and enum-value
  matching is `equalsIgnoreCase` plus trim throughout, so "逐字" (verbatim) was replaced everywhere it appeared
  except the one place it is true: the similarity tool's `ruleRef` is an exact map lookup. And the
  reachability filter is narrower than stated — it skips any variable whose value is unknown *or* whose
  `NaturalChangeRate` is non-zero, so a drifting reading is always reachable and only a static device-local
  value can be judged dead.

- **Prompt/code drift is now mechanically detectable.** `AiPromptContractTest` adds four source-level guards, each
  justified by an incident it was replayed against: a reason code with no prompt warning (checked in both
  directions against the tool's own reason switch, so a deleted warning line and a new unwarned rejection both
  fail), a prompt skeleton that does not parse as JSON once placeholders are substituted, a prompt literal that
  disagrees with its Java constant, and the confirmation prompt's kinds against the `ConfirmationKind` enum.
  Three candidate guards were prototyped and deliberately not built: comparing every prompt alternation against a
  Java `Set` produces junk matches (`in / not in` yields `in|not`), legitimate partial alternations, and two
  prompts spelling their enums as Chinese prose, so it would need the hand-maintained exception table it was
  meant to replace; the skeleton-field ⇔ allowlist check gave five false positives out of five on a clean tree,
  because several allowlisted fields are written by the backend after validation; and a shared single source of
  truth for constraint text would turn ~80 reason codes into a third parallel table while flattening prompt
  sections whose order is deliberate. The first version of the enum guard scanned the whole source file rather
  than the prompt text, so the enum declaration satisfied its own assertion and deleting a kind from the prompt
  still passed — caught by replaying that mutation, which is why every check here carries a coverage floor.

- **The prompts described the verification model in ways that could earn a confident verdict about the wrong
  question.** The specification prompt offered every variable as a trust/privacy *target*, but a variable label
  is a propagation source: `SmvMainModuleBuilder.appendVariablePropertyTransitions` emits
  `next(trust_v) := trust_v` for every variable except an attack-path `FalsifiableWhenCompromised` one, and
  privacy labels never get a live branch at all. So "the humidity reading must always be trusted" returned
  SATISFIED for a question the model cannot answer — the label was initialised that way and nothing can move
  it. The prompt now says so and points at `propertyScope=state` or template 7 instead. Template 7's formula
  was stated as a per-condition pairing when `buildSafetyBody` conjoins the A terms and **disjoins** the
  source labels across them, so a two-condition property fires when either source is untrusted — a violation
  trace a user would read as being about the other condition. `variableSource=environment` was gated on
  `deviceLocal=false` alone, while the generator also requires `Reads=true` and refuses an affect-only shared
  declaration outright; the capability view did not even expose `reads`, so the model was held to a rule using
  a field it never received. It is exposed now, and both prompts state both halves. The visible-reply prompt
  treated `SATISFIED` + `modelComplete=true` as a complete pass with no vacuity caveat, although a verified fix
  can make an implication template's antecedent unreachable — measured in `theory-sources.md` on a shipped
  example scene, where a template-5 Response property passes while carrying no information; the prompt now
  distinguishes implication templates from prohibition templates, where an unreachable condition is the
  property succeeding. Also closed: `NaturalChangeRate` is a constraint on `v' - v` where every integer in the
  interval can happen and none outside it can (both the omit-interior and add-a-stutter failure modes have
  shipped before), the reachability filter narrows only `deviceLocal=true` locals so a shared value's declared
  readings must not be self-censored against the current pool value, template 7 silently skips a device with no
  Modes/WorkingStates, only templates 1/3/4 are eligible for bounded exploration, and an attack run reaching a
  state the clean run proves impossible is faithful shadowed-rule promotion rather than a modeling bug.

- **The AI prompts told the model rules the validators do not enforce, and hid rules they do.** A systematic
  pass over all seven prompts against their own validators. The one that could fail outright: the
  similarity prompt's skeleton put `0.0-1.0` and `true或false`
  where the parser hard-requires a JSON number and booleans, so a model copying the shape guaranteed a 502 for
  the whole call. The scenario prompt's scene skeleton also advertised `version: 4` while the codec, the importer
  and the docs are all v5 — v5 exists precisely because a v4 file cannot carry the `variableSource` the same
  prompt demands, so the skeleton contradicted its own constraint. That one could not fail a request (the tool
  overwrites the field unconditionally), but it taught the model the wrong contract in the prompt whose whole
  subject is the portable shape. Rules the model was being filtered for but never told: that `in`/`not in` values are one
  comma-separated string rather than a JSON array (an array stringifies to `[open, closed]` and both halves
  then fail enum matching), that numeric values must be plain integers inside the declared range, that a
  condition group must be *reachable* and not merely domain-consistent, that any extra field discards the whole
  candidate, that a specification condition may not carry `side`, and that template 7's api condition needs a
  non-blank `EndState`. Rules stated more strictly than the code: the scenario prompt forbade the `= TRUE`
  api-event spelling that `normalizeRuleSources` in fact accepts, strips and reports as
  `apiEventSyntaxNormalized`. Claims the input cannot support: the similarity prompt demanded the model match
  real manifest names, but that tool sends no manifest, and it described an empty-rule-list case the tool
  short-circuits before the model ever runs. Both `## 推荐策略` taxonomies were left over from the removed
  category field — four numbered "类" in a prompt whose output has no taxonomy field read as an instruction to
  classify, and the specification one was a second, vaguer home for what `## 规约模板类型` already owns.
  Examples that violated their own prompt's constraints (variable conditions with no `variableSource`, bare
  numeric values in a string field) now match it. Separately, the chat planner's two-turn list omitted
  `reset_default_templates` — a confirmation-gated operation with its own `ConfirmationKind` that also
  reconciles the Environment Pool and clears edit history — and it never told the model to set the `language`
  argument, which defaults to `en` and decides the language of candidate names, reasons and filter
  explanations, so a Chinese-speaking user got English candidates under a Chinese reply. Three planner
  guidelines that duplicated the visible-reply prompt's job were removed: they pulled against the planning
  round's own rule that its text is reasoning, not narration. "Clearing unusable edit history" described a
  precondition no code checks — the tool's schema, the prompt and `docs/api/ai-tools.md` all said it, and the
  journal's only special case is an empty one.

- **Three recommendation prompts asked the model to translate a field the backend writes.** The rule, specification
  and device tools all instructed it to use the requested language for "message" — but every `message` is backend
  copy from a hardcoded `recommendationMessage(language, key, count)` switch, so there was none to translate. On the
  rule and specification tools the instruction was actively harmful: they validate candidates against a strict
  `hasOnlyFields` allowlist, so a model that complied and emitted `message` had its entire candidate filtered as
  `unknownCandidateField`. The specification prompt also named `reason`, which is not one of its fields either — its
  candidates carry `rationale`. Each instruction now names only that tool's own model-authored fields, matching the
  scenario tool the other three had drifted from, and `AiToolLayeringContractTest` fails if a language instruction
  names a backend-owned key again — the per-tool tests only asserted the instruction's locale prefix, so they passed
  whatever field list followed it.

- **A rule recommendation's explanation is now localized like the other three panels'.** The rule card printed
  the model's `reason` verbatim while the device, specification and scenario cards all route theirs through
  `localizedRecommendationText`, which substitutes a translated line when the model's free text is not in the
  active UI locale — these fields carry no localization contract. So a Chinese-locale user could be shown an
  English-only explanation with nothing translated beside it. Four hand-maintained copies of one card, three of
  them right: the parity spec that already pins their Generate-button states now pins this call too, because a
  screenshot is what caught it and a screenshot cannot fail a build.

- **Refusing a contradicting pair was worse than the defect; it is canonicalised instead.** The gate added
  earlier ran inside the validation that re-checks *every stored node*, and ~19 write paths call it — so one
  device saved by the old defect would have blocked adding a device, creating a rule, renaming, even
  deleting, naming an unrelated device in the error. The repair path revalidates the whole board too, so two
  such devices could not be fixed at all. `requireResolvedVariableSource` already carries the comment
  explaining this shape ("or a legacy row would block this write too"); the rule was documented in the same
  file I broke it in. Because the value is derived, correcting it is lossless: both writer boundaries now
  rewrite it to the value the state declares, and the model-boundary factory does the same, so a
  contradiction is unrepresentable at rest rather than guarded against. The device dialog re-derives on open
  for the same reason — a legacy node would otherwise show `garage` beneath the label *set by the initial
  state* while its state read `away`, the panel asserting something false, fixable only by toggling the
  state away and back.
  Also from that review: `syncStateDerivedVariables` was not scope-filtered, so a shared variable could
  enter the draft record and make the unsaved-changes check dirty on a field no save can carry; and the
  three watchers' `previous === undefined` guard was dead, since none is `immediate`. The E2E helper that
  fills runtime fields skipped the whole entry when a value control was absent, which silently dropped the
  still-editable **trust** label and made `authority-model-audit` fail on a field the change never touched.
- **A variable the device's state determines is no longer stored, offered, or accepted as an instance
  choice.** Deriving the *default* correctly was not the root cause. The contradiction was representable:
  the same fact had two independent homes — the state, and a per-instance `variables[].value` — so a user
  could still pick `away` in one dropdown and leave `garage` in the next, and every writer accepted it.
  It is representable no longer, because for these variables the instance value was never meaningful:
  when every working state declares a `Dynamics` value, the generated `next(<device>.<var>)` has a branch
  per state and its `TRUE:` hold-current branch is unreachable, so a stored value survives exactly one
  step — step 0, the step every verification and simulation begins from. That is why an `AG` property
  could be refuted, and an `EF` property *proved*, on a configuration the device's own transition relation
  forbids.
  So such a variable is now read-only in all three runtime editors (shown as the state's consequence),
  re-derived through one shared helper when the state changes, and refused by both writer boundaries if a
  stored pair disagrees — refused rather than silently corrected, since the user chose both halves and
  naming the conflict beats discarding one. The "use template default" hint in all three editors also
  named `Values[0]` while the panel's own gate required the state's value; it now names the state's.
  Two exclusions keep this narrow, and both were found by review rather than by me: **partial coverage
  stays editable**, because in a state that declares nothing the hold branch is live and the value really
  is the user's; and **a `Transitions` assignment on the same variable disqualifies it**, because
  transition branches are emitted ahead of the state branches in one `case` and first match wins, so that
  variable is genuinely driven by something other than its state. Nine bundled variables are
  state-determined, none partially covered, and the five numeric locals are untouched. Spoofing a reading
  is unaffected — that is `FalsifiableWhenCompromised`, whose attack branch precedes the state branches —
  so no modelling power is lost. `frontend/scripts/real-board-check.mjs` held a fourth copy of the old
  `Values[0]` rule and would have been rejected by the new gates; it now derives from the node's state.
- **A device's initial state and its own local variables contradicted each other, on six bundled
  templates.** Reported from the UI: a Car showing initial state *away* with location *garage*. The
  generated model really said both — `init(CarLocation) := away;` beside `init(location) := garage;` —
  because the state came from `InitState` and the variable from `Values[0]`, two sources ten lines apart in
  `NodeServiceImpl.effectiveRuntime` that were never compared. Step 1 looked like it healed, but that is
  the guard reading the unprimed mode, i.e. a one-step lag, not a repair. Step 0 is where every
  verification and simulation starts, so an `AG` property could be refuted, and an `EF` property *proved*,
  on a configuration the device's own transition relation calls impossible.
  A WorkingState's `Dynamics` value constrains *being in* that state, so the starting state already fixes
  such a variable; deriving it independently was the defect. It now comes from the state the device
  actually starts in — not the template's `InitState`, because a user who sets a car to `garage` means its
  location to read `garage` — falling back to the old default only when that state declares nothing. Enum
  only: a numeric target declares a `ChangeRate`, which says nothing about the current value, so Water
  Heater's `waterTemperature` keeps its lower bound.
  The same rule was copied to six sites; all now call one helper, `DeviceManifestModes.localInitialValue`.
  That includes `BoardSemanticFingerprint`, whose javadoc claimed to "mirror generation" and would
  otherwise have fingerprinted a step 0 the model no longer has, and `FuzzEngineTest`'s harness, which was
  feeding the bounded explorer a start state NuSMV proves unreachable — the two engines would have
  described different systems. `RecommendScenarioTool`'s defaulter served locals *and* the Environment Pool
  through one method; that is now split, since the pool is scenario authority and no single device's state
  may dictate a shared value. All four documented scene verdicts are unchanged (each sets its local
  variables explicitly), and a new bundled-manifest guard names every offending template when the
  derivation is removed.
- **The same privacy value looked different in two adjacent tables, and one of them looked like plain
  text.** Each privacy cell was a three-way ternary whose middle branch was the empty string — and the
  two tables chose *opposite* values for it, so an unstyled label meant "public" in the variables table
  and "private" in the states table one screen-inch below. A user comparing them could only conclude the
  chip carried meaning it did not. Both now use one vocabulary: `private` takes `info` — a classification,
  not a hazard, which is the role `SimulationTimeline` settled on after two reviews read amber as implying
  a security defect in an ordinary trace — and everything else is neutral. The two privacy cells' hardcoded
  `bg-slate-100 text-slate-600` fallbacks are gone with them (a `board-chip-*` token follows the theme; a
  raw slate pair needs the dialog's `!important` dark repair). The trust and falsifiability cells still
  carry that pair and are left for a separate pass.
- **The section accent bars stopped marking their sections once the headers grew a second line.** A bar
  is `h-5` on an `items-center` row, which was right for a one-line header and wrong for title + hint: it
  centred across the boundary and read as decoration beside the hint. All eight are now `h-7` — exactly
  `text-lg`'s line height — on an `items-start` row, so each bar spans its title.

#### Added

- **A running counterexample search now has a wall-clock ceiling (5 minutes).** The workload guard bounds the
  *nominal* budget, but its calibration is per-step timing measured on one machine — a slower host, a
  contended worker pool, or a board whose real per-step cost exceeds the estimate could all overrun it, and
  the renewable two-minute task lease is a liveness heartbeat rather than a deadline. So an admitted run
  previously had no upper bound on its duration at all. The ceiling sits at roughly 5x a maximal admitted run,
  so it never fires on a correctly-estimated search and only catches what the static estimate got wrong.

  **A timeout is deliberately not a cancellation.** The worker settles a cancellation signal as `CANCELLED`
  and persists no result, so routing a deadline through it would report a timeout as something the user did
  and discard the work. A spent deadline instead ends the search the way a spent iteration budget does —
  `BUDGET_EXHAUSTED`, already contracted as "not found within this bounded search" and never as safety, or
  `FOUND_VIOLATION` when candidates were already located, which stay valid — and adds the conditional
  limitation code `TIME_BUDGET_EXHAUSTED` so the reason is disclosed rather than indistinguishable from a
  completed budget. `iterations` reports what actually ran. Both search modes check it at the same points they
  already check cancellation, so a long path cannot outrun it by more than one path, and the check uses
  monotonic `nanoTime` differences so a system-clock adjustment cannot shorten or extend a search.

#### Changed

- **A rejected exploration budget now explains itself and names what would fit.** The estimate showed
  `15,100,000 / 12,500,000` while every knob on screen multiplied to 100,000: the board's complexity
  multiplier was fetched, parsed, and never rendered. The preview now returns `maxAcceptedIterations` — the
  largest admissible iteration count at the chosen path length and population size, or `0` when even one
  iteration is too many, which means a different field has to change — and both the panel and the backend
  rejection message state all four factors plus that ceiling. The response validator cross-checks the ceiling
  against `accepted` in both directions so the remedy cannot contradict the verdict beside it. Two smaller
  fixes on the same surface: the "confirmed against the current Board" line no longer renders underneath a
  rejection of the same number, and Advanced settings force open on a workload error because the remedy names
  "candidate paths per iteration", which lives inside that disclosure.

- **Every section of the device dialog now shares one header shape and explains itself.** The new
  transitions table arrived with its hint as a sibling paragraph and a tighter header margin, which made
  it visibly the odd one out next to States and APIs — and giving only that section an explanation
  implied the other five were self-evident, which they are not: nothing said that a variable's row is
  owned by the Environment Pool when it is shared but by the instance when it is local, that state
  labels are per-state and propagate, that only an API badged *Automation trigger* can be a rule's IF
  source, or that the specifications listed are the board's rather than the template's. All six now use
  the header shape the instance-runtime panel already established (bar, title, nested hint), and three
  sections that had no `data-testid` gained one. A structural test pins the shape and the presence of
  every hint, because a screenshot cannot fail a build.

#### Added

- **The variables table now distinguishes a shared variable the device only *writes*.** `IsInside` was
  already visible (environment vs internal), but `Reads: false` was not, so an affect-only declaration and
  a read-capable one both read as plain "environment variable" — and nothing explained why `temperature` is
  selectable as a rule condition on a Thermostat and refused on an Air Conditioner. That refusal is real and
  enforced at three backend boundaries: no read mirror, not a rule/spec condition source, not a transition
  trigger attribute. The existing *affects environment* badge cannot stand in for it, because a read-capable
  variable may also affect (Thermostat's does), so it appears in both cases. Seven rows across five bundled
  templates are now marked, and the variables hint states the consequence rather than the flag.
- **The compromise-behaviour column claimed a falsified *reading* for values the device never reads.** Both
  its labels talk about a reading, but `SmvMainModuleBuilder`'s value-falsification branch requires
  `Reads !== false`; for an affect-only declaration the flag can only force `trust_<name>` untrusted, since
  that loop has no read gate. All seven bundled affect-only declarations are `false`, so the old wording was
  true by luck — a custom template setting it `true` would have promised a falsifiable reading for a value
  with no read mirror at all. Those rows now say the label is what compromise moves.
- **The States table now shows what each state does, not only how it is labelled.** `Dynamics` was the
  third invisible manifest field, and the largest: 15 bundled templates declare 81 entries. Trust and
  privacy describe a state's *labels*; `Dynamics` is the variable value the state holds while it is active.
  The Environment Pool shows these grouped by variable and therefore only for shared values — 9 of the 15
  templates target a device-local variable exclusively (Oven's `ovenJobState`, Washer's `washerJobState`,
  Car's `location`, …), so those had no surface at all, and an earlier version of the States hint wrongly
  sent the reader to the pool for them.
  Worded as a standing constraint rather than an action: the generator emits a `Value` as a branch of
  `next(<device>.<var>)` guarded by *being in that state*, so "holds X" is what it means, where "set to X"
  read as something the state does at a moment — which is what an API does. That also stopped Car's
  `garage → holds garage` looking like a redundant instruction: it is the reported-value mirror, the
  falsifiable reading a rule sees, distinct from the true state it mirrors.
- **Device details now list the template's declared contents.** No surface showed a device's contents *as
  a property of the device*: `RuleBuilderDialog` names them with their sensitivity while you pick one for a
  rule, and the canvas badge aggregates the private ones, but neither answers "what does this device
  declare, and how is it classified" before you start authoring. That matters because each content's
  `privacy_<name>` ORs into the command target's privacy condition when the rule fires, so attaching a
  private photo marks that target private. Only two bundled templates declare any (Mobile Phone's `photo`,
  Ventilator's `content`), which is how the gap stayed invisible.
- **Device details now list the template's state transitions.** A transition is the one part of the
  state machine no rule drives — it fires on its own once its trigger holds — and nothing in the product
  showed them, so a counterexample where a camera left `taking photo` by itself had no explanation
  anywhere, while `FixResultDialog` was already telling the reader the violation "may be caused by
  device transitions". Ten bundled templates declare transitions. The read-only table gives name, start
  state, end state, trigger and effect, reusing the APIs table's state and trigger formatters so one
  relation cannot render two ways. Two cases it states rather than implies: an absent `EndState` is not
  an empty target but *no state change* with the assignment still applying (`Clock.reset`), and within a
  step a rule command overrides a transition, because the generator emits rule branches ahead of
  transition branches in the same `case`. Every listed row is genuinely in the model —
  `SmvModelValidator` refuses a transition with no trigger, one that changes nothing, or one whose
  trigger names an unreadable attribute — so the table cannot advertise dropped behaviour.

#### Removed

- **The rule and specification recommendation categories are gone — the field, the two dropdowns, and
  the enum on both sides.** A category was never part of the verification model: it was a model-authored
  label that was dropped when a candidate was applied, so it reached neither the canvas nor NuSMV.
  On the specification side it was pure dead weight — the generated JSON schema never asked for one, the
  validator never read one, and the candidate card never rendered one, so the four buckets (safety, response,
  consistency, privacy) duplicated, less precisely, the classification `templateId` already carries
  formally. On the rule side it actively cost the user recommendations: an explicitly selected category
  made the backend discard, as `categoryMismatch`, any candidate whose self-assigned label differed —
  so a correct security rule the model happened to label `automation` was thrown away over the label
  alone, before its conditions were ever checked, and a whole response could be emptied that way.
  Both request dropdowns were a prompt-only hint besides. `userRequirement` (2,000 characters) already
  expresses intent strictly better than four fixed buckets, and it is now the only steering input.
  The request argument is now rejected rather than silently ignored, while a `category` the model still
  volunteers is stripped from the candidate exactly as `confidence` already was — dropping an otherwise
  valid recommendation over a label with no verification meaning would have reproduced the defect this
  change removes.

#### Fixed

- **Regenerating the default scenes corrupted the RFID one into an unimportable file.** `Door RFID`
  declares Modes, so the scene codec requires an explicit device state, but the generator's `rfid_1`
  definition passed only `variables`/`privacies` — so `node scripts/generate-default-template-scenes.mjs`,
  which `default-template-scenarios.md` tells you to run after a template change, dropped `state`,
  `currentStateTrust` and `currentStatePrivacy` and produced a scene rejected with
  `sceneImportStateRequiredForStatefulDevice`. The state also has to be `authorized` specifically: both
  `InitState` and the first working state are `idle`, which freezes the whole scene. This was fixed in the
  fixture by hand once (3712d73) and the generator kept overwriting it; the generator now carries the
  runtime, so its output is canonical again and the fixture no longer needs a hand edit.
- **Four more copies of the single-bound domain rule, found by sweeping for the shape below.** The rule
  builder's value placeholder accepted *either* bound and advertised `(5 - ∞)`; two device-dialog domain
  columns required both bounds but let an explicit `null` through and rendered `[null, 30]`; the
  `recommend_related_devices` tool half-validated a one-bound domain and accepted `"25.5"` for an integer
  one, so a suggestion could pass the tool and then be rejected by the board; `recommend_scenario`
  defaulted a value from a lone `LowerBound`, unlike every other backend defaulter. A dev probe script
  had the same defaulting plus a `'0'` fallback that could sit outside the declared domain, and now
  fails loudly instead. The two live surfaces are pinned by new tests; the AI-tool value guard now
  filters the candidate with `invalidInitialRuntime` rather than repairing it into a different
  suggestion, matching its sibling rejections.
- **The Environment Pool's range chip advertised a domain the server refuses to store.** It carried a
  fifth copy of a single-bound rule `utils/deviceRuntime.ts` already owns: it accepted *either* numeric
  bound and printed the missing side as `-∞`/`∞`, while `device-template-schema.json` admits only an
  enum or *both* bounds. Two such declarations also passed every domain-conflict check — those key off
  "both bounds present" — and surfaced as a vague "mixed ranges" chip instead of a named mismatch. The
  chip now uses the owning predicates, and that fallback is gone: any two declarations rendering
  different ranges already differ in a way the conflict list reports specifically. Nothing asserted the
  chip before, so the divergence was invisible; the range is now pinned, including the `null`-bound case
  that separated the two rules. Two local re-implementations of the same predicates, which treated an
  explicit `null` bound as a declared domain, are now aliases of the owner.

#### Documentation

- Corrected four claims the code contradicts. `fuzzing-flow.md` said the workload guard "includes the
  effective target-specification count" — no such factor exists, and a test name asserted the same thing.
  `theory-sources.md` said the HAFuzz reference artifact "obtains the same denominator by summing the powers
  used by all levels"; the artifact sets `DETECTION_LAYER_NUM = 1`, so its denominator is 1 rather than 7, and
  `PAPER_SOLVER_LEVELS = 3` is this project's extension rather than paper conformance — `TheorySourceConformanceTest`
  greps only the two `Math.scalb` substrings, so it pinned the formula's shape while leaving its instantiation
  unchecked, and now fails if the level count changes without the doc following. `api/fuzzing.md` documented a
  `reasonCode` (`ATTACK_CONCEPT_UNSUPPORTED`) no backend site emits while omitting the one it does
  (`REPORTED_READING_UNSUPPORTED`), and never mentioned `UNKNOWN_TARGET_SPEC_IDS_IGNORED`.

- **Recorded the scope of a shared value's security label, which was enforced but never argued.** The
  Environment Pool is the only writer of a shared value's trust/privacy, so all devices declaring the
  name carry identical labels in the generated model — yet the model *could* express per-device labels
  (`<device>.trust_<name>` is emitted per device, with no pool-level identifier), MEDIC does not index
  labels by device, and no invariant or test pinned the fan-out: keying it off the read-capability set
  or writing only the first device would have left the suite green. `shared-value-semantics.md` §2 now
  argues the choice against its alternatives, states the cost (a scene cannot mark one of two
  thermometers untrusted) and names attack modelling as the sole divergence; §9 adds invariant 13, now
  pinned by `NusmvEnvironmentPoolTest.environmentPoolLabelsAreIdenticalAcrossEveryDeclaringDevice`,
  which asserts per module so a partial fan-out cannot pass. Invariant 2 also gained the default trust,
  default privacy and name-casing agreement the code has always enforced.
- **"A trust label is device-scoped" was copied into six places and is half wrong.** A label is *emitted*
  per device — there is genuinely no pool-level `trust_a_<key>`, which is the load-bearing half — but for
  a shared value the pool writes every declaring device the same one, so it is not *scoped* per device.
  Two of those copies went further and told the reader that "under two devices the choice changes what is
  proved", which holds only when the named instance is an attack compromise point. Corrected in
  `spec-templates.md`, `utils/spec.ts`, `SpecificationFormulaPreview` and their two tests.
- **`nusmv-model.md` invited users to "override initial labels" without saying where.** For a shared
  value there is no per-device surface, and one sentence attached the pool's fallback to a paragraph about
  device runtime overrides with "similarly", implying an override path that does not exist. The pool's own
  UI hint had the mirror-image gap: its device-side sibling says "this device instance" while the pool's
  said nothing about scope, so a user comparing them would infer the pool label is per-instance too. Both
  now state it, and `api/board.md` states it on the contract that owns the pool.

### 2026-08-12

#### Changed

- **Live-AI CI skips instead of failing when no API key is configured**: A `preflight` job now probes
  `IOT_VERIFY_OPENAI_API_KEY` and publishes a boolean the gate depends on, because the `secrets` context
  is unavailable in a job-level `if:`. A missing or whitespace-only key produces a warning annotation
  and a job summary stating the AI path was *not* verified, then skips the suite. Previously the gate
  hard-failed at a credential check, producing eleven consecutive nightly reds that each exited in
  under a minute without building anything. Because a skipped job still makes the whole run report
  `success`, a third `status` job renames itself to *Live AI NOT verified (no API key)* when the gate
  was skipped, so the check list carries a state the run conclusion cannot. A key containing whitespace
  is now rejected at the preflight rather than ~10 minutes later at the model call, and the summary
  states whether `IOT_VERIFY_OPENAI_BASE_URL` is set so a later auth or 404 failure is diagnosable.

- **`actions/cache` bumped from v4.3.0 to v6.1.0** in all three workflows and the `setup-nusmv`
  composite action. It was the last action on the deprecated Node 20 runtime, which GitHub now forces
  onto Node 24; v5.0.0 made that migration and v6.0.0 moved to ESM. Inputs and outputs are unchanged.
  The stale `# v4.2.0` pin comments were corrected to match the SHA they annotate.

#### Fixed

- **Repository name typo in the Live AI CI fork guard**: the `if:` compared against
  `sharkdingo/IoT-Verfify`, matching a misspelled remote. The GitHub repository was renamed to
  `IoT-Verify` and the guard now matches it. The check is now also repeated on the job that spends the
  external quota, instead of reaching it only through a `needs:` edge.

- **CI guard gaps**: the action-pin assertion scanned only `.github/workflows/`, so the `setup-nusmv`
  composite action's pin was unchecked; local composite actions are now asserted to declare a supported
  runtime, and the live-AI gate's three invariants are pinned by tests.

- **9 device templates with frozen local enum variables**: Fixed Refrigerator Door Sensor, Car, Door RFID, Washer Machine, Dryer, Oven, Thermostat, Mobile Phone, and Email templates that had local enum variables without driver mechanisms, causing them to hold nondeterministically chosen initial values throughout benign runs. Variables now properly driven by WorkingState Dynamics or changed to shared environment variables where semantically appropriate.

- **Door RFID security vulnerability (Critical)**: Changed idle state from `RFID="authorized"` to `RFID="none"` to prevent fail-open behavior where door would unlock by default without any card scan. Added `"none"` value to RFID variable domain.

- **Thermostat incomplete fix (High)**: Added missing `thermostatOperatingState` Dynamics to 5 circulate states (circulate;auto, circulate;cool, circulate;heat, circulate;emergency heat, circulate;off) that were reporting stale operating states. All 15/15 WorkingStates now have complete Dynamics.

#### Added

- **Template validator enhancement**: Added `checkLocalEnumVariablesHaveDrivers()` validation rule in `DeviceTemplateNuSmvValidator` that rejects device templates with local enum variables lacking driver mechanisms (WorkingState Dynamics or Transition Assignment). This prevents future frozen-variable regressions. Note: Current implementation checks for at least one WorkingState with Dynamics; partial coverage (some states without Dynamics) is permitted.

- **Frozen-variable test coverage**: Added `DeviceTemplateNuSmvValidatorFrozenVariableTest` with 4 test cases covering: rejecting local enum without driver, accepting local enum with Dynamics, accepting shared enum without driver, accepting local numeric with NaturalChangeRate.

- **`docs/development/known-traps.md`**: One archive for every failure mode that mimics a product or compile bug, grouped by cause — test authoring (the four shapes of a test that cannot fail, subset-scoped guards, correct-by-accident state), build environment (Maven incremental overload resolution, `target/classes` contention, the stale `spring-boot:run` JVM), E2E environment (auth rate limits, the port and CORS traps, known-flaky specs with measured rates), and blast-radius misjudgements. These narratives previously made up roughly half of the four agent rule files; the rules stay there in one or two lines and link to the section holding their evidence. Registered in the Doc Map.

- **Working-state artwork for the three newly stateful templates**: Car (`garage`, `away`), Door RFID (`idle`, `authorized`, `not authorized`), and Refrigerator Door Sensor (`closed`, `open`) previously rendered a single `Working.svg`; each declared state now owns its own icon, so the canvas shows which state a device is in.

- **Localized labels for the new model tokens**: the two new Modes (`CarLocation`, `ScanState`), six Signal APIs (`arrive home`, `leave home`, `scan authorized card`, `scan unauthorized card`, `detect open`, `detect closed`) and two Transitions (`reset from authorized`, `reset from unauthorized`) now display translated text in both zh-CN and en instead of the raw manifest identifier.

#### Changed

- **`api/verification.md` listed a progress stage that verification never emits.** It documented `PERSISTING_RESULT` as one of six stages "for verification"; the worker sets exactly five, and that member is only ever written by simulation (`SimulationServiceImpl:768`) and fuzz (`FuzzServiceImpl:539`). A stage-driven progress UI built from the doc would carry a verification label that never renders, and would expect the 80→100% window to report persistence when verification actually stays on `PARSING_RESULTS` through completion. The neighbouring simulation and fuzz stage lists were both already correct, so this was isolated drift rather than a systemic problem.

- **`api/board.md` described `BoardReplacementPreviewDto` inline as five fields; it has six.** `editHistoryEntryCount` was already documented correctly in the surrounding prose and the endpoint table, so only the brace list was stale.

- **`installation.md` presented three unenforced version floors as requirements.** MySQL 8.0+, Maven 3.6+ and Redis 6.0+ are pinned by no manifest — and there is no Maven wrapper — unlike JDK 17 (`pom.xml`) and the Node range (`package.json` + `.npmrc` `engine-strict`). Each now says so, because a floor a reader cannot verify is worth less than one labelled as tested-but-unenforced.

- **`vite.config.ts` and the E2E specs disagreed on one variable's fallback** — `http://localhost:8080` versus `http://127.0.0.1:8080` for `E2E_API_BASE_URL`. Harmless in practice, but that file's own comment exists to explain why the proxy half and the spec half of a run must never diverge, and an unset variable was the one path that could still split them. Both now use the identical spelling.

- **`guides/troubleshooting.md` told you to accept "NuSMV 2.6+"** while `installation.md`'s prerequisites table on a neighbouring page says 2.6–2.7, which is the range the trace parser actually supports. The open-ended bound would have waved through a future 2.8. `installation.md`'s own verification comment had the same drift and is fixed too.

- **`guides/frontend-integration.md` no longer lists `types/` file by file.** The block was headed "Directory layout (actual)" and was nine files out of date — a hand-copied inventory of a directory that grows with the domain cannot stay true. `api/` is still listed exhaustively, because *which* module owns a call is the thing people get wrong; `types/` now states the naming rule and the two exceptions instead.

- **`architecture/overview.md` had two factual errors, one of which made the two architecture docs disagree.** It counted "six configurable thread pools" where `ThreadConfig` declares **seven** — the missing one is `fuzzTaskExecutor`, which `fuzzing-flow.md:67` names explicitly, so a reader tuning pools from the overview would have missed the fuzz pool entirely. It also listed `device-template-schema.json` inside `resources/`, where the file does not exist: it lives at the module root and a second `<resource>` block in `pom.xml` copies it onto the classpath at build time, so "edit the resources copy" meant editing nothing or creating a shadow file that fights the build. Both now state what the code does, with the pom mechanism explained.

- **Two documents were missing the "Verified against code" provenance line the Doc Map promises every document carries** — `theory-sources.md` and `shared-value-semantics.md`, both of which are pinned by backend tests and so are the *most* re-verifiable in the tree.

- **`guides/troubleshooting.md` cited `configure/SecurityConfig.java`; the class is in `security/`.** Found by mechanically resolving every backtick-quoted source path in `docs/` against the filesystem — the other 200-plus citations resolve, as does every Java type the docs name.

- **The Doc Map now records which six documents the backend test suite checks, and what each test enforces.** Editing them can turn `mvn test` red — `ModelSnapshotDocumentationTest` fails until a new run-snapshot DTO field is documented, `AwayModeUnlockSceneNusmvTest` re-proves a walkthrough's counterexample claims under real NuSMV — and nothing said so, so the coupling was discoverable only by breaking it. The note also states the rule that a doc test must pin facts rather than phrasing.

- **Two documentation contradictions fixed, both found by auditing `docs/` for duplicated facts.** `architecture/nusmv-model.md` claimed `Reads` "(default true)" in a table while stating eight lines below — correctly — that it is required on shared variables and rejected on device-local ones. `DeviceTemplateNuSmvValidator:106-117` and `device-template-schema.json` confirm the latter: there is no default, and a reader following the table would author a template the validator refuses. Separately, the same clamp worked example ran with rate `[2, 5]` in one doc and `[2, 4]` in another; both are arithmetically valid, which is precisely why nobody noticed the copies diverging.

- **`NaturalChangeRate` semantics collapsed from six homes to one.** The same argument — exhaustive integer deltas, the domain clamp beating the rate, what `0` versus `[-1, 1]` assert — was restated in `nusmv-model.md`, `api/board.md`, `api/ai-tools.md`, `data-authority-model.md` and `theory-sources.md`, three of which ended by naming a *different* owner after restating it. `shared-value-semantics.md` now owns the semantics (and gained the `MAX_NATURAL_CHANGE_RATE_SPAN` bound the others carried), `theory-sources.md` keeps the MEDIC attribution, `nusmv-model.md` keeps only the two emitted-model facts that are genuinely its own, and the API docs keep field syntax.

- **Fix-apply drift guards, the largest duplication in the tree (~30 lines across four parallel passages).** `api/verification.md` and `architecture/auto-fix.md` each explained the whole mechanism while pointing at the other for it. The DTO doc now carries only what a client observes — which condition returns `400`, which returns `503`, and that a `400` means nothing was written — and links the mechanism to `auto-fix.md`.

- **Three cross-cutting API conventions given an owner in `api/overview.md`.** The `requestId` format (three homes) and the interactive-request token-fencing plus `503` contract (three homes) belonged to no document, which is why they drifted; they now sit beside the timestamp convention that was already there. The HAFuzz mutation operators and batch sizing moved out of an `api/fuzzing.md` table cell into `architecture/fuzzing-flow.md`, which owns the search algorithm.

- **Environment-variable defaults removed from four docs that are not their SSOT.** `FIX_TIMEOUT_MS` appeared as `300000`, "5-minute", and `300000`; the Windows `NUSMV_PATH` literal and the OpenAI base-URL/model defaults were duplicated in `installation.md`, whose export block now shows override placeholders instead of shipped values; and `known-traps.md` restated two auth rate-limit defaults 110 lines after declaring that it would not. Each now names the variable and links [configuration.md](docs/getting-started/configuration.md).

- **`SchemaDocumentationTruthTest` no longer dictates the documentation's wording.** Its composite-key scan hard-coded the connector `has a\s*composite PK`, so rephrasing a sentence for brevity silently dropped the claim from the scan instead of failing — a documentation test that constrains prose blocks the pruning it is supposed to protect. The pattern now tolerates any short connector while refusing to cross a claim boundary, and the existing count assertion catches an edit that outruns it. Verified by mutation: removing the `@IdClass` from `BoardEnvironmentVariablePo` reddens the test with the intended message.

- **The Doc Map now matches its own layering rules.** `docs/development/ci.md` and `known-traps.md` were listed under **Architecture**, which is where a reader looking for CI would never find them — they now have their own **Development** section. `guides/` silently held two unrelated kinds of document, so it is split into "conventions and integration" (read while writing code) and "scenes and walkthroughs" (scripted product runs). The `Source (if not ready)` column held a literal `—` in 32 of 33 rows and is gone; the `🚧 planned` legend entry described a state no document is in any more; and the "removed legacy docs" block recorded a finished migration, which is `CHANGELOG.md`'s job, not the map's. Coverage verified mechanically: 29 files, 29 entries, no orphans and no dead entries; every link and anchor in every repo Markdown file resolves.

- **The doc-ownership rules now cover every declared owner.** They listed five and the Doc Map declared eight: `shared-value-semantics.md` (what a shared value means, versus `nusmv-model.md` for how it becomes SMV text and `data-authority-model.md` for who may write it), `theory-sources.md` (which paper owns which behaviour), `ai-tools.md`, and `default-template-scenarios.md` (scene semantics and expected counts, which a presenter walkthrough references rather than redefines). An unlisted owner is how a fact grows a second home.

- **`frontend-ui-conventions.md` said "two decision records" while carrying ten sections.** Its own summary line had not kept up with the document.

- **The agent rule files no longer claim a hook enforces the E2E port check.** `.claude/hooks/guard-e2e-port.sh` was deleted in `cc366ff`, but `CLAUDE.md`, `AGENTS.md`, and `frontend/CLAUDE.md` still described it as blocking a Playwright run while port 3000 is held. All three now state the truth — nothing checks the port up front, and the symptom is a mid-suite failure that reads like a product bug — so the reader stops relying on a guard that does not exist.

- **All four agent rule files trimmed to rules, with the narratives moved into `docs/`.** The files had grown to 1,397 lines, roughly half of it incident prose and content the codebase or another doc already owned — the two things their own closing section ("detail lives in `docs/`", "prune when you add") tells a contributor to remove. Now 1,041 lines with no rule lost. Cut as derivable: both `Codebase map` blocks (already in `docs/architecture/overview.md`, including the same AI-tool count), the stack/version lists (`pom.xml`, `package.json`, `installation.md`), and the doc-sync table plus language policy (duplicated from `CONTRIBUTING.md`, which now also carries the `theory-sources.md` row the copy had gained). Moved into the new `docs/development/known-traps.md`: the E2E rate-limit recipe and known-flaky specs, and the Maven/`target-classes`/stale-JVM traps. Moved to `frontend-ui-conventions.md` §7: the `cqmin` clamp and `position: fixed` variable-scope measurements. Every non-derivable rule stayed, in one or two lines, with a link to its evidence.

- **Email and Mobile Phone semantic scope**: Changed `Email.receiveKey`, `Email.receiveMail`, and `Mobile Phone.location` from per-device-instance variables (`IsInside: true`) to scene-level shared variables (`IsInside: false`, `Reads: true`).
  - **User impact**: Multiple Email instances now share the same receive events; multiple Mobile Phone instances share the same location value. This represents a shift from "independent device state" to "environmental input" semantics.
  - **Rationale**: These variables represent external environmental inputs (email server events, GPS location) rather than device-internal state. The shared-variable semantics allow proper modeling of external triggers while solving the frozen-variable problem.

- **Door RFID RFID variable domain**: Extended value set from `["authorized", "not authorized"]` to `["authorized", "not authorized", "none"]` to properly represent idle state (no card present/scanned).

- **`docs/examples/default-rfid-access-scene.json` badge reader now carries its working state**: Door RFID gaining WorkingStates turned this into a stateful device, and a stateful device without a `state` is refused on import — the shipped scene could not be imported at all. Added `state: "authorized"` plus `currentStateTrust`/`currentStatePrivacy`, matching the reader's existing `RFID: "authorized"` value and the other two devices in the scene. Verification results are unchanged (`5 / 0` baseline, `2 / 3` under attack budget 1).
  - The scene deliberately starts *after* a badge has been presented. `idle` is reachable only through the `scan …` **Signal** APIs, and interactive simulation never fires a Signal by itself, so an `idle` / `none` start freezes the scene — measured at 30 steps, the reader never leaves `idle` and the door never unlocks. The template's fail-closed `idle` semantics are unaffected; this is only the scene's starting point. Documented in `docs/guides/default-template-scenarios.md` so it is not "corrected" again.

#### Technical Details

- **Modified templates** (9): Car.json, Door RFID.json, Dryer.json, Email.json, Mobile Phone.json, Oven.json, Refrigerator Door Sensor.json, Thermostat.json, Washer Machine.json
- **Modified validator**: DeviceTemplateNuSmvValidator.java (added 3 helper methods, 95 lines)
- **Modified tests**: BoardStorageServiceImplTemplatePrecheckTest.java (adapted 4 test cases for frozen-variable guard)
- **Updated demo scene**: docs/examples/default-rfid-access-scene.json (Door RFID template snapshot)
- **Test results**: 2310/2310 backend tests passing, frontend type checks passing

#### Known Limitations

- **Single-environment model**: Thermostat.temperature and other shared environment variables model a single thermal/environmental space per board. Multi-zone scenarios (e.g., separate bedroom and living room thermostats with independent temperatures) are currently out of scope. This is consistent with the project's MEDIC-based single-environment formalism.

- **Local sensor variables and cross-device rules**: Car.location and 10 other templates use `IsInside: true` (local variables), which limits certain cross-device automation scenarios. A systematic review of all sensor variables for appropriate IsInside configuration is planned for a future release. Current behavior matches existing patterns (Garage Door, Window) and does not break existing functionality.

#### Migration Notes

- **Backward compatibility**: Existing boards using the fixed templates continue to work without changes. Templates are automatically reloaded on backend restart.
- **No user action required**: Fixes take effect immediately for new device instances. Existing device instances retain their configuration.
- **Breaking change detection**: The new frozen-variable validation will reject custom templates with undriven local enum variables. If you have custom templates, review them against the new validation rule before uploading.

### 2026-08-11

#### Fixed

- **Three documented request limits were enforced on the REST path only, so an AI tool could store data
  the UI cannot submit.** `MAX_RULE_CONDITIONS`, `MAX_SPEC_CONDITIONS` (both 50) and the 4000-character
  `ruleString` cap existed solely as `@Size` annotations on `RuleDto`/`SpecificationDto`, which Spring
  applies only where `@Valid` runs. The AI tools call `BoardStorageServiceImpl` straight from a chat
  turn and no service class carries `@Validated`, while `ManageRuleTool`/`ManageSpecTool` loop the JSON
  arrays uncapped — measured by disabling the new check: a 51-condition rule persisted with no
  exception at all. `ruleString` was worse hidden, because its column is `TEXT` rather than `VARCHAR`,
  so an over-long preview never even failed at the insert.

  All three are now checked on the record being authored — `addRule`, `addSpec` and `saveBoardBatch` —
  rather than inside `validateBoardReferences`. That re-validator was the first choice, by analogy with
  the device-label length checked there, and the analogy does not hold: `device_node.label` is
  `length = 255`, so an over-long label cannot already be stored and the check can only ever fire on the
  request. `rule_string` is `TEXT` and the condition arrays are JSON, so an oversized row *can* already
  exist — precisely the rows this change exists to stop the assistant from writing. Since the
  re-validator re-reads the whole stored collection on every device add, layout move, spec add and undo,
  checking it there would have rejected all of those over a rule the request never mentioned, leaving no
  way to get unstuck; the same trap `requireResolvedVariableSource` already documents.
  `saveNodes_isNotBlockedByAStoredRuleThatExceedsTheConditionCap` pins that, and fails with the lockout
  when the check is moved back.

  Two further details the first attempt got wrong. The `ruleString` bound sat below the
  `conditions == null` guard although the preview does not depend on conditions, so a request omitting
  the array skipped it entirely and persisted an unbounded preview — it is now independent
  (`saveBoardBatch_whenConditionsAreAbsent_stillBoundsTheRulePreview`, driven through the batch path
  because `canonicalizeRuleRelationsForStorage` rewrites absent conditions to an empty list before
  `addRule` can see the null). And the spec cap reported under the bare `aConditions`/`ifConditions` key
  that `validateSpecTemplateShape` already claims, so a template-4 spec with 51 A-conditions showed only
  the shape complaint; the key is now `<group>.size`.

- **A rule the product accepts could compose a rule preview the product then rejects.** `ruleString` is
  generated server-side in two places — `ManageRuleTool` when the model supplies no `label`, and
  `FixStrategyApplier` when an automatic fix rewrites a rule — both before validation runs, and its
  rendered length is driven by device labels that are legal up to 255 characters. Measured: 50
  conditions (the legal maximum) with 60-character labels renders 4,226 characters, so the new cap above
  would have failed an otherwise-valid automatic fix on a display string the caller never supplied. Both
  composition sites now pass through `RulePreviewText.bounded`, which truncates with an ellipsis so a cut
  preview is not read as the complete rule. A caller-supplied `label` is still rejected rather than
  silently trimmed — that value is the caller's choice. `RulePreviewTextTest` pins the arithmetic, not
  just the helper, so the reason survives.

- **`apply_scenario` described its `confirmed` argument as the gate that decides whether the board is
  replaced.** It is not, and must not be: authority comes from `UserContextHolder`, set in
  `ChatServiceImpl` from the user's own confirming turn. Ignoring a model-supplied `confirmed:false`
  when the user *has* confirmed is the correct behaviour — otherwise the model could veto the user — but
  the schema promised a gate that does not exist, which is the same class of contract defect the
  backend rules already name. The description now states that the argument is the model's intent and
  that the decision is server-side. No logic changed.

- **`search_devices` understated what its `keyword` matches, and nothing checked that a tool's schema
  agrees with what it accepts.** The description offered "template keyword or device name" while
  `NodeServiceImpl.searchNodes` matches a case-insensitive substring against the device label, the
  template name **and** the device id. Understating is milder than the reverse — it wastes no model
  round — but it hides a capability the model would otherwise use after it has a device id in hand.
  Description and `docs/api/ai-tools.md` now state all three fields.

  `everyAdvertisedArgumentIsAcceptedByTheToolThatAdvertisesIt` now enforces the underlying rule across
  all 53 tools: every argument a schema advertises must be accepted by that tool's
  `requireOnlyFields` allowlist. The catalog was already consistent, so this fixes nothing — it
  removes the need to remember a rule that was carried only as prose, and which is easy to break
  invisibly because arguments are declared two different ways (`props.put(...)` and an inline
  `Map.of(...)` passed straight to the constructor). Checked in one direction only: a name the
  allowlist accepts but the top-level schema omits is legitimate, because nested object and array
  members are validated by the same helper deeper in the payload.

- **A freshly placed Alarm started with its siren and strobe already sounding.** `Alarm.json` declared
  `"InitState": "both"`, so dragging an Alarm onto the canvas produced a device already in full alert.
  On a verification platform that is more than cosmetic: a safety property stating the alarm must not
  sound without cause is violated at step 0 by the initial state alone, before any rule fires.

  It was not a deliberate choice. 27 of the 29 stateful bundled templates take `WorkingStates[0]` as
  their `InitState`, which is harmless while entry 0 is a resting state — but Alarm's list is
  alphabetical (`both`, `off`, `siren`, `strobe`), so entry 0 was `both`. Every other template's
  default is a resting state (`off`, `idle`, `closed`, `locked`, `ready`), and all five bundled
  example scenes that place an Alarm already override it to `off`, i.e. each scene author corrected
  this by hand. Now `"off"`.

  **This reaches new accounts only.** Bundled templates are seeded per user at first login and the
  stored copy is authoritative afterwards (`DeviceTemplateServiceImpl.initDefaultTemplates` skips a
  user who already has templates), which is deliberate — a redeploy must not silently overwrite a
  user's own template edits. An existing account keeps `both` until it runs the bundled-template reset
  (`POST /api/board/templates/defaults/reset`, the "restore bundled defaults" command in the UI).
  Measured on this database: 147 existing users, all still holding `both`. Note the reset is an undo
  history boundary by design, so it reports the affected entry count before confirmation.

  `noBundledDeviceStartsInAnAlertingState` pins it. The check is deliberately narrow — it names the
  alerting state values and skips templates that declare none. A first attempt asserted that
  `InitState` must equal the state the example scenes give the device, which sounded stronger and was
  wrong: four of the five overridden templates already agreed, and the fifth was Light, which those
  scenes set to `off` because they depict night and away-from-home situations. A light starting on is
  an ordinary default, so that version would have reported scene intent as a defect.

- **Removed `frontend/src/assets/AC_Cooler/`, artwork for a template that no longer exists.** The
  single-purpose `AC Cooler`/`AC Heater` pair was superseded by the mode-based `Air Conditioner`
  template when device templates moved from a frontend list into the backend seed (commit `4542709`);
  `AC_Heater/` was cleaned up then, `AC_Cooler/` was not. It was the last asset directory with no
  matching template, and every bundled template still has its own directory. The only reference to
  the name anywhere was the `search_devices` tool schema, whose example keyword advertised the
  nonexistent `'AC Cooler'` to the model; it now names `'Air Conditioner'`.

- **Eight bundled device states rendered the wrong icon, or none, because the resolver never tried
  the underscored filename.** A state name may contain a space, a filename may not, so the bundled
  assets encode the space as `_` (`taking photo` → `taking_photo.svg`, `auto;emergency heat` →
  `auto;emergency_heat.svg`). `normalizeAssetFolder` applied that `\s+`→`_` substitution to the
  folder, but `normalizeStateName` only trimmed, so `getStateVariants` never probed the underscored
  form. Six states had their correct icon sitting on disk and unreachable: Camera and Mobile Phone
  `taking photo` plus Mobile Phone `uploading to cloud` fell through to `on.svg`, and the three
  Thermostat `…;emergency heat` tuples exhausted the variant chain and were served
  `auto;auto.svg` — `getDeviceIconUrl` falls back to the alphabetically first file in the folder
  before it ever reaches the generated placeholder, so a wrong icon, not a missing one, is what this
  class of failure looks like. `getStateVariants` now probes the underscored name alongside the raw
  one at each precedence level.

  Two further states had no artwork at all and were being served another state's icon by that same
  first-in-folder fallback: `Home Mode` `sleep;idle` (matched `Working.svg` via the variant chain)
  and `Washer Machine` `rinse;run` (served `heavy;pause.svg`). Both are now authored to match their
  siblings — `Home_Mode/sleep;idle.svg` and `Washer_Machine/rinse;run.svg`.

  All 116 bundled working states now resolve to an icon of their own; previously 108 did. The
  regression test asserts that whole invariant over every bundled template rather than the eight
  names found here, so a new template shipping a state without artwork fails too. It checks the
  filesystem for ownership *and* compares rendered icons, because the first-in-folder fallback makes
  a missing asset indistinguishable from a present one by URL shape: an earlier version that only
  looked for the generated placeholder and for two siblings colliding passed with
  `Home_Mode/sleep;idle.svg` deleted, since that state then matched `Working.svg`, which no sibling
  uses. Icon content cannot be compared against the file on disk — Vite inlines some of these SVGs
  as minified percent-encoded text and others as raw base64, so equal artwork is not equal bytes.

  Three follow-ups after review, all in the guard rather than the resolver. Its two identity checks were
  each other's blind spot: with bundled resolution dead entirely, the generated placeholder embeds the
  state name, so every state gets a *distinct* data URI, no siblings collide, nothing matches a generic
  name, and the test reported success against a fully dead pipeline. It now asserts positively that real
  artwork was reached first — the placeholder is the only artwork on a 72×72 canvas, which holds under
  both inlining forms. It also re-implemented the production template→folder mapping instead of calling
  it, so changing the separator to `-` left it green; `normalizeAssetFolder` is now exported and used.
  And it skipped all 16 stateless templates (the sensors, Clock, Car, Door RFID, Weather and the rest),
  which are served *entirely* by the first-in-folder fallback this test exists to police; they are now
  checked under the resolver's default state. Each of the three was verified by mutation.

  One dead variant removed from the resolver in the same pass: the title-cased underscored spelling
  (`Taking_photo`, `Auto;emergency_heat`) matches no bundled asset, because no bundled state name has
  both a space and a capital first letter, and where it equalled the plain form the `Set` discarded it.
  The `;` first-segment split is now trimmed, so a state written `auto ; heat` still probes `auto`
  rather than the `auto_` no asset uses — `normalizeStateName` only trims the outer edges.

- **An AI tool offered the model an example device template that does not exist.** `search_devices`
  described its `keyword` argument as "e.g. 'AC Cooler'", but no such bundled template exists — the
  name survives only as a stale `frontend/src/assets/AC_Cooler/` asset directory. A schema
  description is part of a tool's contract, so an example naming a non-existent template invites a
  round that returns nothing. Now names `Air Conditioner`, which is bundled.

- **A missing translation key would have rendered as literal `app.unknownDevice` in a
  counterexample trace.** `traceView.ts` formats a trace condition whose device is unnamed via
  `translate('app.unknownDevice')`, but that key existed in no locale — vue-i18n returns the key
  itself when lookup fails, so the user would read `app.unknownDevice` where a device name belongs.
  Added `unknownDevice` to both locales (`未知设备` / `Unknown device`).

  The existing `i18nLiteralKeys` guard could not have caught this: it matched only a callee named
  `t`/`$t`, while `traceView.ts` receives an injected translator named `translate`, making every key
  in that file invisible to it. The unit spec passed because it stubs its own dictionary, which
  *defined* the key production lacked. The guard now also audits any dotted string literal under a
  real message namespace, regardless of what consumes it — taking the namespace list from the loaded
  bundle rather than a hand-kept one, since a hardcoded `app|specTemplates` would have left all 43
  `auth.*` keys unaudited. Both checks assert their scan matched something before concluding it found
  no problems. The call-shaped pattern is kept alongside the literal one: the literal pattern finds
  every key the call pattern does and 423 more, but being anchored to namespaces that exist it cannot
  see a key invented under a namespace that does not, which the call-shaped one reports as missing.

- **Six bundled model-token labels were ambiguous or wrong in Chinese.** `formatBuiltInModelToken`
  resolves labels through one flat, capability-unaware map, so a token that means two things across
  two capabilities gets a single label. An audit of every state, API, variable and variable-domain
  value in all 45 bundled templates (158 distinct token segments) found six defects; none was a
  missing label (coverage was already complete).
  - `dry` rendered as `除湿` (dehumidify), the Air Conditioner HVAC mode, but Soil Moisture Sensor
    uses `dry` as a value of its `water` domain (`dry`/`wet`), where dehumidification is not
    something a sensor does. Now `干燥`, which is correct for the soil reading and consistent with
    the neighbouring `dryClean` = `干燥清洁` on the Air Conditioner. Rendered via
    `DeviceDialog.vue` variable list and the instance-config domain dropdown.
  - `sendingPhoto`, `sendingAlertMessage` and `uploadingToCloud` were byte-identical to the
    commands `send photo`, `send alert message` and `upload to cloud`, losing the in-progress
    aspect on the same device (Home Mode, Mobile Phone). Now `正在…`, matching the convention the
    map already applied to every other such pair (`sending`/`sent`, `posting`/`posted`,
    `takingPhoto`/`takePhoto`, `running`/`run`).
  - `closed` shared `关闭` with the switch value `off`, which reads as "powered down" rather than
    physically shut for the `contact` domains of Garage Door, Refrigerator Door Sensor and Window.
    Now `已关闭`, following the participle convention already used by `locked` = `已锁定`,
    `paused` = `已暂停` and `finished` = `已完成`, which also separates the state from the `close`
    command.
  - The Alarm value `both` named a quantity rather than the state. Now `警笛与闪光` / `Siren +
    strobe`, naming the two outputs that fire together.

  Left unchanged as verified-benign collisions: `fanOnly`/`fan only` and `emergency
  heat`/`emergencyHeat` are spelling variants of one concept (they collide in English too), and
  `off`/`close` never co-occur — their device sets are disjoint.

- **An uppercase `A_` variable name slipped past the environment-pool prefix guard.** Template admission
  rejected an InternalVariable named `a_temperature` — the generator prepends `a_` for the environment
  pool, so the author's name would have compiled to `a_a_temperature` and collided with another device's
  shared `temperature`. The guard compared `startsWith("a_")` on the raw name, but NuSMV identifier
  registration normalizes pool identifiers to lowercase when checking for collisions, so `A_temperature` 
  was admitted and then produced a normalized collision the guard exists to prevent. The comparison now 
  folds the name first (`toLowerCase(Locale.ROOT)`), closing the case-variant hole. The reserved-word 
  gate was already case-insensitive. The mode-collision gate has two layers: a shallow case-sensitive 
  exact match (line 135) and a deep case-insensitive check using token normalization (line 522); both 
  are unchanged. Found by adversarial audit (round 11). The fix is defence-in-depth: the collision 
  requires `A_temperature` from one device to normalize to the same pool identifier as `a_temperature` 
  from another, but the second form was already rejected by the case-sensitive check, making the 
  vulnerability unexploitable via normal admission. Compilation rules: 
  [nusmv-model.md](docs/architecture/nusmv-model.md).

- **Twenty-one i18n keys rendered literally when Board data failed to load or during fix operations.**
  When the backend returns an incomplete Board snapshot (network interruption, partial 5xx failure), the
  UI blocks editing and shows which collections failed: `app.boardDataEditBlocked` takes a
  `{collections}` placeholder that was filled with `boardDataKey_templates`, `boardDataKey_nodes`,
  etc.—but those five keys had no translations, so the error displayed raw key names like
  `app.boardDataKey_templates, app.boardDataKey_nodes` instead of localized labels. Similarly, 8
  `taskProgressStage_*` keys for async verification/simulation progress (QUEUED, STARTING,
  GENERATING_MODEL, etc.) were missing, causing raw key fallback in the run-history spinner. Finally, 8
  `fixProgressStage_*` keys for interactive fix operations (QUEUED, RUNNING, PREPARING_CONTEXT,
  REQUESTING_MODEL, VALIDATING_RESULT, PREPARING_MODEL, FINALIZING, CANCELLING) were missing—only
  SEARCHING_AND_VERIFYING had a translation—so the fix dialog showed literal keys during AI-assisted
  repair stages. The three scenario issue collections (`scenarioObjectiveIssues`, `scenarioReadiness`,
  `scenarioSemanticWarnings`) already had translations deeper in the file and were working correctly.
  All 21 keys (5 + 8 + 8) are now defined in both Chinese and English. Found by user report with
  screenshot showing literal keys in a load-failed banner, then expanded via full-stack dynamic i18n
  audit using parallel subagents.

### 2026-08-10

#### Added

- **A specification now states which of two questions it asks about a shared value.** A shared value has
  two identifiers in the generated model — the environment pool value and the reporting device's mirror —
  and they diverge exactly when that device is compromised. The generator used to pick the pool value
  whenever the key was shared and silently discard the device the author had selected, so a specification
  reading "temperature never exceeds 30" was reported SATISFIED while a falsified reading of 40 drove the
  automation. Spec conditions with `targetType: variable` now carry a required `variableSource`:
  `environment` ("did this actually happen in the home") or `reported` ("is this what this device said").
  There is deliberately no default — presenting either as the author's intent is a false statement about
  what was verified — so a condition without it is refused on every path that authors a specification, and
  generation reports it as a skipped specification rather than guessing. Whole-board revalidation, which
  device and rule writes run over already-stored specifications, deliberately tolerates an absent reading. `environment` additionally requires a shared declaration: a device-local value has
  no pool identifier, and that is now refused with the declaration named instead of compiling to an
  identifier the model never declares. Semantics: [shared-value-semantics.md](docs/architecture/shared-value-semantics.md).
- **The specification builder now asks that question instead of answering it silently.** The condition
  editor presents both readings side by side for a shared value — "the actual value in the home" and
  "what this device reports" — with neither preselected and the compromise divergence explained where the
  choice is made; a device-local value offers only the device's reading, because the home has no
  counterpart to compare against. Every display surface says which reading it means: the formula preview,
  the plain-language description, the saved condition rows, and counterexample verdicts. A stored
  condition that never recorded a choice renders as unresolved and blocks verification and simulation with
  an inline reason rather than being assigned a side on load. Scene files are version **5**: a `variable`
  condition must carry `variableSource`, and version-4 files are rejected rather than half-read, since no
  guess preserves what their specifications assert. A recommended condition without the field is rejected
  as malformed rather than completed on the model's behalf.

#### Fixed

- **Template admission now guards against three variable-name collision vulnerabilities.** Variable names
  starting with `a_` (the environment pool prefix), NuSMV reserved words (`INIT`, `case`, etc.), and names
  colliding with mode names are now rejected at template upload with an explicit error. These shapes would
  have caused identifier collisions in the generated NuSMV module or broken parse entirely. Found by
  adversarial audit (round 10) following the `variableSource` field addition.
- **Counterexample environment value chips now adapt to dark theme.** The trace playback strip rendering
  environment pool values used hardcoded light-mode colors (`border-slate-200 bg-slate-50 text-slate-700`),
  making them illegible in dark mode. They now use CSS variables (`var(--board-border)`,
  `var(--board-surface-subtle)`, `var(--board-text)`) that adapt to the user's theme. Cosmetic only; found
  by completeness audit (round 10).
- **Deletion preview now tested for `environment` specifications.** A new test drives deletion of a device
  anchoring an `environment` specification and asserts the spec appears in `removedSpecifications`. Defect 13
  was fixed in round 9, but the fix had no test exercising the mechanism. Test coverage gap closed.
- **A compromised sensor could not drive its own controller, so sensor spoofing was proved impossible.**
  A device's autonomous `Transition` guard compiled to the environment pool value (`a_<name>`) whenever
  the trigger attribute was a shared reading, instead of to the device's own read mirror
  (`<device>.<name>`). The mirror is where compromise takes effect — under attack it becomes
  `case is_attack=TRUE: <domain>; TRUE: a_<name>; esac` — so a pool-reading guard made the device
  omniscient about the real value in the home and immune to the falsified reading it was itself
  reporting. The canonical attack (a spoofed smoke level driving a detector into alarm) was returned as
  `SATISFIED`; the same model now returns `VIOLATED`, confirmed against real NuSMV. Trigger guards now
  always read `<device>.<attribute>`; the write target of an environment transition is unchanged and
  remains the pool. Compilation rules: [nusmv-model.md](docs/architecture/nusmv-model.md).
- **A safety property's trust predicate named a label the model never declares.** Template 7 pairs each
  A condition with its untrusted-source term, and both formula previews built that term from the
  condition's *value* target — so a condition asking about the home rendered
  `controlSource(Environment."temperature")`. A trust label is device-scoped: the generator emits
  `<device>.trust_<key>` whatever the reading, and there is no pool-level `trust_a_<key>`. The preview
  therefore described a property about the home's own provenance while NuSMV checked one named device's
  label, and under two devices declaring the key the choice changes what is proved. The value term still
  names the pool, because that is what `environment` means; the label term now names the device. A `mode`
  condition's term names the mode's active state, matching the `trust_<mode>_<value>` the generator emits,
  and an `api` condition's names the end state the action leads to. Two-subject formulas are documented in
  [spec-templates.md](docs/architecture/spec-templates.md).
- **A mode condition was reported as an unanswered question.** Making the formula preview read the declared
  reading let every non-`variable` condition fall into that logic, and a `mode` condition carries no reading
  and never can — so it rendered as `<unresolved>."FanMode"` on every template, telling the user a choice
  was missing from a condition nobody is ever asked to make one for.
- **Bounded exploration answered a question it cannot ask.** The explorer keeps one value per shared reading
  and models no compromised device, so a specification asking what a device *reports* was evaluated against
  the pool value and given a verdict — the falsification case the author asked about was never covered, and
  nothing said so. Such a specification is now reported as unexplored with its own reason, alongside the
  existing trust/privacy exclusion, and the panel says so before the run rather than after.
- **A counterexample could not show the divergence it was proving.** Canvas nodes render each device's
  reported reading and the change popover lists only environment values that *changed*, so in the case this
  distinction exists for — the home holds 20 while a compromised sensor reports 40 — the pool value is
  stable, produced no change row, and appeared nowhere on screen. The trace step now carries the pool's
  values as absolutes beside the reported readings.
- **The same condition displayed two different formulas.** The client rendered NuSMV booleans lowercase
  while the server rendered them uppercase, so one specification read `= true` in the editor and `= TRUE`
  in its verdict. The two previews are independent implementations of one contract and nothing compared
  them; a cross-side test now pins the literals.
- **A specification asserting that a device's report disagrees with the home was refused as
  self-contradictory.** The assistant's satisfiability pre-check grouped variable conditions by device and
  key alone, so "the home is actually below 30 **and** this sensor reports 30 or above" — the falsified
  reading a compromised device produces, and the reason the two readings are distinguishable at all —
  collapsed into one group with no common legal value and was rejected. The reading is now part of the
  grouping key, and a genuine contradiction within one reading is still caught.
- **Two verdicts about the same value read as identical rows.** A verification result is titled by its
  specification template, so two specifications asking different questions about one key produced two rows
  with the same title and opposite verdicts, distinguishable only by one token inside the monospace
  formula. Each row now names the reading it answered about, and a specification mixing both names both.
- **A specification stored before this change made the whole board fail to load.** The client rejected a
  returned condition with no `variableSource` as a contract violation, so the specifications collection
  errored out with a retry that could never succeed — and the unresolved-state handling built for exactly
  that data was unreachable. An absent value now loads and renders as unresolved; a present but
  unrecognised value is still a contract violation.
- **A skipped specification blamed the wrong thing.** The generation-issue classifier matches on message
  substrings, and the missing-reading message necessarily talks about values, so it was reported as
  `SPEC_INVALID_VALUE` — sending the user to check a value domain that was correct. It now has its own
  `SPEC_VARIABLE_SOURCE_REQUIRED` code and message.

### 2026-08-09

#### Fixed

- **A counterexample published an unconstrained value as a device reading.** The same affect-only shared
  declaration that the `Trigger` fix below closed at generation still reached the UI through the trace:
  because `<device>.<name>` is declared but never assigned, NuSMV prints an arbitrary domain member, and
  the shipped away-mode demo drew "Porch Light: illuminance 0" beside an environment strip reading 20.
  `TraceVariableDto` now carries `observed`, `false` for such a row, with an empty `value`; the canvas
  strip and the playback summary omit it rather than print a blank or `N/A`. The row itself stays, because
  it carries the variable's trust and counterexample-based fixing requires one row per manifest variable —
  deleting the value outright had broken automatic fix on this scene. The true shared value keeps being
  published once, in the state's `envVariables[]`. See `docs/api/verification.md`.

Ten admission and modelling defects, each reproduced against real NuSMV 2.7.1 and pinned by a
mutation-checked regression. Nine of them share one shape: input that passed validation, persisted, and
then failed at *run* time — either in the engine or as a wrong answer.

- **A Transition `Trigger` could read a value the device never observes.** An affect-only shared
  declaration (`IsInside: false, Reads: false`) gets no read mirror, so `device.<name>` was declared and
  never assigned — an unconstrained variable NuSMV re-picks every step. A template declaring "switch off
  when illuminance >= 80" fired on noise while the real reading sat at 20. Now refused at generation.
- **`Contents[].Name` reached NuSMV unvalidated.** A content named `my photo` emitted
  `privacy_my photo` and the engine refused the model (`at token "photo": syntax error`) for a template
  already saved. Validated on both the schema and Java sides, like every other emitted identifier.
- **`InternalVariables[].Values[]` reached NuSMV unvalidated**, in two steps: punctuation
  (`{hot!, ok}` → parse error), then reserved words (`{next, ok}` → parse error, which the identifier
  pattern cannot catch). Both validated after space removal, so the bundled values containing spaces
  (`not authorized`, `fan only`, `pending cool`, `vent economizer`) still load. The reserved-word check is
  case-**sensitive** because NuSMV's lexer is: `{Next, ok}` compiles, `{next, ok}` does not.
- **Two devices could declare conflicting effects on one shared discrete value.** Each template was
  valid alone; only the pair contradicted, and no per-template gate can see a pair. The board persisted
  and every verification then returned HTTP 500. Now refused when the board is saved.
- **A device id starting with an automatic-fix reserved prefix** (`param_`, `lambda_`,
  `condition_value_`) was accepted at save and rejected on every verification afterwards. Now refused at
  save, with a remedy that names something the user can actually do — a device id is immutable.
- **An over-long device label reached the database as a 500.** `@Size` binds only on the REST path;
  assistant-authored labels bypassed it and hit the column limit as a `DataIntegrityViolationException`.
- **A partial multi-mode state tuple leaked into the mode it left blank**, silently overriding the
  template's `InitState`. Invisible in every bundled template, because the only one with a value shared
  between two modes has an `InitState` where the wrong and right answers coincide.
- **An Environment Pool trust/privacy edit was discarded for affect-only rows** the panel renders as
  editable — the label source used the read-capability set where the declaration set was required.
- **A generated-identifier collision could pass the guard** when a mode name needed sanitising: the
  guard compared the pre-rescue name while the generator emits the rescued one, so mode `Next` admitted a
  template that emitted a duplicate declaration.
- **A pair-wise write invariant was rejecting a plain read.** The discrete-writer check was wired into a
  path reached by `GET /api/board/environment`, so a board that already held a conflicting pair could no
  longer read its own environment pool. A per-declaration check is safe to repeat on a read; a pair-wise
  one is not.

#### Changed

- The CI risk router now escalates changes to the template admission gate, the board admission gate, the
  verification request validator, and the example scene files. Each decides what may be persisted or
  verified; a change to one previously routed as an ordinary source change and skipped full validation.

### 2026-08-08

#### Fixed

- **Dragging a device no longer invalidates a valid verification verdict.** Moving or resizing a node
  committed with `semanticChanged: mutation.operation === 'updated'`, and a layout write always reports
  `updated` because a layout row genuinely changed — so nudging a node raised the "re-verify" banner over
  a result that still described the current model. Canvas coordinates never reach the generated model
  (`buildDevices` omits them), so the staleness flag was reporting a semantic change that had not
  happened, against this repo's own rule that only semantic changes invalidate a verdict. The layout call
  site now states the no-op explicitly instead of deriving it from an operation field that cannot express
  it. `layoutStaleness.spec.ts` pins both halves: the call site's committed value, and the premise that a
  moved node produces an identical model fingerprint. Both were verified to redden when the fix is
  reverted. Coverage stops at the unit level on purpose: an E2E attempt failed **identically** with
  the fix present and reverted, because observing this needs an open verdict and a pointer-reachable
  canvas at once, and the result dialog's `position: fixed; inset: 0` overlay covers the board — a
  raw `page.mouse` drag performs no hit-target check, so it lands on the scrim and never sends a
  layout request. Closing the dialog first does not help either, since `dismissResultDialog` clears
  the staleness flag. The reason is recorded in the spec so the next attempt does not repeat it.

- **A conformance guard that could not fail.** `TheorySourceConformanceTest.incompleteModelIsNeverAVacuousPass`
  asserted the vacuous-pass refusal through one `||` whose fallback string
  (`"genResult.disabledRuleCount() > 0"`) was a substring of the primary pattern, so deleting the
  **skipped-specification** half of `forwardVerify`'s refusal left the test green — and a skipped
  specification is exactly how a repair gets certified against a property that was never checked. The two
  halves are now asserted separately; verified by deleting each half and watching the matching assertion
  redden. Also widened `documentStillNamesItsSources` to the four papers its display name claims: the FSM
  thesis was pinned by a sibling assertion but absent from the coverage loop.

#### Changed

- **The walkthrough now says which of its four green properties actually carry information.** Two do
  not, and both readings are measured rather than argued. The template-7 property is satisfied at
  baseline only because a trust label cannot degrade with no attacker present — this scene declares its
  `motion` source `trusted`, and `EF (door_1.trust_LockState_unlocked = untrusted)` is `false` — so its
  baseline green is a control condition whose entire value is the Act 2 contrast. The template-1 privacy
  property is satisfied because nothing here can make a lock-state label public (`EF (privacy_… =
  public)` is `false` for both), so it demonstrates that the dimension propagates rather than that a
  risk was avoided. Only the two Immediate properties earn their green with reachable antecedents.
  Marking the source `untrusted` would fix the vacuity and cost more than it buys: measured, it turns
  the baseline into *three* violations, destroys the satisfied-then-violated contrast Act 2 is built on,
  and duplicates `multi-violation-repair-scene.json`, which already covers that shape. The regression
  now asserts the attack model makes the trust term reachable, so the contrast cannot silently become
  two vacuous verdicts in a row (mutation-checked by clearing the source's
  `FalsifiableWhenCompromised`).
- **`theory-sources.md` no longer claims `forwardVerify` rules out every vacuous pass.** It rules out
  one kind — a repair certified against a model that never emitted the property
  (`disabledRuleCount`/`skippedSpecCount`). It does not rule out the kind where the repair itself makes
  an *implication* property's antecedent unreachable, and that kind looks identical on screen. Measured
  on the away-mode scene: the verified removal makes
  `EF (a_occupancy = absent & door_1.LockState = unlocked)` **false**, so its template-5 Response
  property passes while describing nothing. The direction depends on the template and no single rule
  covers both — for `AG !(P)` an unreachable `P` is the property *succeeding*; for `AG (P -> …)` it is
  vacuity — so the page now states both and says a green forward verification means "nothing violated",
  never "everything still meaningful". The presenter walkthrough reads its own `6/0` the same way, and
  `AwayModeUnlockSceneNusmvTest` pins the asymmetry with a reachability probe over the repaired model
  (mutation-checked: swapping the porch-light rule for an auto-lock rule reddens it).
- **Cleaned stale wording out of `AwayModeUnlockSceneNusmvTest`.** Its class comment still called itself
  a "temporary probe for candidate B", its test was named `…IsRepairedByConditionStrategy` while the
  condition strategy is precisely the one that declines, and a javadoc promised a "non-destructive
  repair" that does not exist for this scene. A test whose name contradicts its assertions misleads the
  next reader faster than no name at all.

- **The example-scene template guard now checks scene-defined types instead of rejecting them.**
  `documentedSceneTemplateSnapshots_matchBundledTemplates` asserted every template in every
  `docs/examples/` scene resolves to a bundled manifest, which made a legitimate custom device type
  unrepresentable. Copied bundled manifests still must stay byte-equal; a scene-defined type is now
  validated against the canonical `backend/device-template-schema.json` — the same gate the template
  endpoint applies — so it is held to a different standard rather than waved through. Verified by
  removing a required `Reads` field and watching the guard fail with the schema path. The scan also
  asserts it found templates, so a scene with an empty `templates` array can no longer pass by
  iterating nothing.
- **`theory-sources.md` now declares that attack falsification is capability-scoped.** A variable is
  falsifiable only when its manifest sets `FalsifiableWhenCompromised: true`, so `AttackSurface` admits a
  device to the reading-falsification surface only if it declares one. That narrows MEDIC §3.4, which treats
  any sensor reading as spoofable, and it was documented in `nusmv-model.md` and `backend/CLAUDE.md` but
  missing from the page that owns paper deviations — the worst category, a real deviation left undeclared.
  The note also states its cost: a template omitting the flag is unattackable, so the omission weakens an
  attack run rather than failing it.
- **Corrected an overstated Salus §5.3 conformance claim.** The page presented distance-ordered parameter
  candidates as an unqualified property of `ParameterAdjustStrategy`. It holds for the single-parameter
  walk; the coordinated multi-parameter path deliberately selects the *extreme* in-bounds tightening hint
  because several parameters must hold together, and the joint FROZENVAR solve narrows NuSMV's assignment
  with a budget-capped greedy pass rather than proving minimality.
- **Corrected an over-absolute `NaturalChangeRate` claim in four documents.** They stated that an
  interval excluding zero means the value changes every step. It does not at a domain boundary: with
  domain `0..10` and rate `[2, 5]`, a value of `10` clamps every candidate back to `10`, and NuSMV
  proves `AG (v = 10)` — measured against real NuSMV, not reasoned. The domain bound wins over the
  rate, so the claim now carries that qualifier in `theory-sources.md` (which owns the semantics),
  `docs/api/board.md`, `docs/api/ai-tools.md`, and `docs/architecture/data-authority-model.md`.
  Stating it unconditionally would make a provable model behaviour read as a generator bug — the
  opposite of the honesty the surrounding paragraph exists to enforce.
- **Three documents still described a `NaturalChangeRate` semantics the repo itself calls unsound.**
  `docs/api/board.md`, `docs/api/ai-tools.md`, and `docs/architecture/data-authority-model.md` said a custom
  interval samples "the unique lower endpoint, zero, and upper endpoint" — the endpoint shortlist that once
  made NuSMV *prove* a false `SATISFIED`, and that a stutter-injecting variant made mandatory-change
  intervals unstatable. All three now state the exhaustive `v' - v` semantics and defer to
  `theory-sources.md`.

#### Added

- **A presentation scene whose defect is a feature, not a labelled mistake.** The bundled demo scenes
  either name their bad rule (`Unsafe conflicting rule: …`) or pair obviously dangerous devices, so an
  audience sees the answer before the tool finds it. The new away-mode scene
  (`docs/examples/default-away-mode-unlock-scene.json`, generated by
  `scripts/generate-default-template-scenes.mjs`) instead composes three individually reasonable
  automations — nobody home so the alarm arms, porch motion unlocks the front door for convenience, the
  same motion lights the porch — and proves the house can sit empty with the front door unlocked.
  Baseline is `4` satisfied / `2` violated: a Never property and a Response property asking different
  questions about the same worry, both repaired by one removal. Walkthrough:
  `docs/guides/away-mode-unlock-demo.md`.
- **The first bundled scene that declares its own device type.** `Occupancy Sensor` is defined in the
  scene file rather than bundled, which also demonstrates that a version 4 scene is a self-contained
  import including new types. It declares `occupancy` as a **shared environment** variable
  (`IsInside: false`, `Reads: true`), and that choice is load-bearing rather than cosmetic: a
  device-local variable with no API writing it compiles to `next(v) := v`, frozen for the whole run. An
  earlier draft keyed the scene on the bundled `Car.location` and was discarded after NuSMV showed what
  that produces — `AG !(car_1.location = garage)` provably true, so the garage rule was dead code, a
  Never property over it was vacuously satisfied, and the "keep the rule, add a guard" repair silently
  disabled the rule it claimed to keep. All three read as a clean demo.
- **A scene that shows two fix strategies declining, with stated reasons.** Parameter adjustment reports
  `SKIPPED_NO_PARAMETERIZABLE_VALUES` (the scene is enum-valued) and condition adjustment reports
  `NO_VERIFIED_SUGGESTION` — adding "only unlock when someone is home" genuinely does not repair the
  property, because occupancy evolves freely and no rule re-locks the door after the resident leaves.
  Permanent removal of the convenience-unlock rule is the only verified repair and clears both
  violations. A tool that refuses to offer a guard that fails re-checking is more credible than one that
  always has an answer, and no other bundled scene exercises that path.
- **`AwayModeUnlockSceneNusmvTest`** pins what both documents publish, against real NuSMV: the baseline
  verdict and violated formulas, the three-state trace, the blamed rules, each strategy's status,
  forward verification of the offered repair, and the budget-one untrusted-label failure carrying
  `is_attack = TRUE`. A second test asserts every state the walkthrough presents is **reachable**, so
  the frozen-variable trap above fails the suite instead of the demonstration. Both verified to redden:
  pre-repairing the defect breaks the first, and reverting `occupancy` to a device-local variable breaks
  both — and makes the condition strategy report `VERIFIED` for the repair that disables the rule.

### 2026-08-07

#### Fixed

- **A focused device no longer glows forever.** Clicking a device in the inspector list, or having the
  assistant create one, panned the canvas and painted that node with a 28px accent bloom and an *infinitely*
  pulsing ring. The highlight cleared on only five of its exits — replacing or clearing the scene, focusing a
  rule, focusing a specification, and post-deletion reconciliation — so clicking empty canvas, pressing
  Escape, closing the device dialog, focusing another device by any other path, or simply moving on all left
  it up. One device then glowed indefinitely while identical neighbours did not, and nothing on screen
  explained why: it read as a property of that device rather than as the board answering "where is it?". The
  focused *rule* highlight had the same defect on its canvas edges.
  The three focus ids are now owned by `views/board/focusHighlight.ts`, a cue that expires on a timer, so a
  missed exit costs a second of highlight instead of a permanent one, and the three targets are mutually
  exclusive by construction rather than by three hand-written clears per setter. Both pulse animations are
  bounded to two iterations, which ends the motion before the cue retires. Deleting the focused item still
  drops the cue immediately, reusing `reconcileBoardFocus` so existence has one owner.
- **The focus cue no longer looks identical to "this device changed during playback".** Its 4px accent ring
  plus 28px bloom was within 2% of `.trace-changed`, in the same hue, with both animating a scaling accent
  ring — two unrelated meanings wearing one mark, and they co-occur when a device is focused during playback.
  The cue is now a dashed outline with a lift and no bloom, matching the focused-edge cue; a bloom stays
  exclusive to playback semantics.
- **Minimum-sized device nodes can now be resized at any zoom level.** A node at the model-space minimum
  (80×60) lost all resize handles below zoom 0.867, because the visibility check used a 52 **screen-pixel**
  threshold that did not account for the minimum height of 60. At zoom 0.85, the node rendered as 68×51
  screen pixels, falling one pixel short, and became permanently locked at its smallest size with no
  discoverable way to grow it except the keyboard shortcut (Ctrl+arrows). The bottom-right handle is now
  guaranteed for nodes at their minimum dimensions regardless of zoom. To prevent the handle from smothering
  the node body at extreme zoom-out (where a 44px touch target can exceed the node's own screen footprint), the
  handle's **inward reach** is capped at 35% of the node's smaller dimension, with the remainder extending
  outward to preserve the full 44px target — so at zoom 0.3 an 80×60 node (24×18 screen) gets a handle that
  sits 6px in / 38px out, staying accessible without blocking the node. The other three handles still require
  88 screen pixels in both axes to avoid crowding.
- **Mouse clicks on a device node no longer leave resize handles visible after the pointer moves away.** The
  handles appeared on `:focus`, which includes mouse clicks, so clicking a node once left four handles stuck
  on it until something else took focus. Changed to `:focus-visible`, the standard pattern where handles
  appear only for keyboard navigation.
- **Removed `.animate-pulse-glow`**, an unused CSS class (no literal references, no dynamic construction) that
  had a motion exemption and a test guarding it — noise defending something that painted nothing.

#### Changed

- **Every dialog in the product is now built from one shared surface layer**
  (`frontend/src/styles/dialog.css`). Thirteen hand-rolled modals plus the Element Plus MessageBox had each
  invented their own: four overlay tints, three card radii, eight widths, four footer alignments and five
  confirm-button heights. Logout painted itself with a hardcoded navy gradient and a pulsing red halo;
  template deletion wore a full-bleed red banner with a 64px icon for a reversible catalog edit, shouting
  louder than permanent account deletion. Two dialogs from the same product did not look related.
  The layer supplies one scrim, one card, three sizes and four tones — tone is set once on the card and the
  header's icon tile reads it, so a destructive dialog is the same dialog with one token changed rather than a
  differently built one. The confirm button stays accent in every tone except danger, because the primary
  action moving colour between surfaces is what made them feel unrelated. Footers are always
  trailing-aligned with the primary action last.
- **A dialog surface is opaque again.** The shared card and the dark-theme MessageBox were painted with
  `--iot-color-card-bg` (`rgba(…, 0.3)`) — a token for a card resting on an opaque panel. A dialog is
  `position: fixed` with only the scrim behind it, so at 30% the board showed through the title, the message,
  and the account-deletion form's password field, with a `blur(18px)` masking the cause. Both now use
  `--surface-elevated`. The MessageBox's two disagreeing per-theme gradients are merged into one rule.
- **Dialogs stay centred at every viewport.** A narrow-viewport bottom sheet was tried and reverted: Element
  Plus centres MessageBox from its own overlay and cannot dock, so docking the hand-rolled ones put the
  logout prompt on the bottom edge while the scene-clear confirmation floated mid-screen at the same width.
  Under 640px a dialog releases its width cap, tightens padding and raises actions to 44px touch targets —
  its position does not change.
- **`confirmChoice` joins `confirmDestructive` in `utils/feedback.ts`.** All seventeen confirmations shared a
  danger button, including "apply this AI suggestion anyway", "save a duplicate rule" and logging out with an
  unknown chat outcome. None destroys anything, and a red button on all of them is how a real deletion stops
  standing out. Six call sites moved to the new helper, which is covered for the danger-button absence and for
  the modal-depth registration that keeps the board's Ctrl+Z blocked behind an open confirmation.
- Specs that addressed dialog controls by appearance class (`button.danger`,
  `.template-reset-dialog__btn.secondary`, `.control-center-delete-dialog`) now use `data-testid`; they broke
  on a pure restyle while asserting nothing about behaviour. `dialogSurfaceConsistency.spec.ts` fails if a
  modal skips the layer (counted per dialog, so a second stale overlay in a file with seven of them cannot
  hide), if a dialog surface goes translucent, if the narrow block re-docks, if the MessageBox button bypasses
  the shared `--dialog-action-height` token, or if a migration leaves a class on markup that nothing styles
  and no test addresses. `localeParity.spec.ts` additionally fails if a history-boundary notice states its
  entry count without its reason, in either locale.

- **Every history-boundary confirmation now explains why undo/redo history is discarded.** Clearing
  the scene, replacing it with an imported one, deleting a device type, and resetting bundled types
  each stated the entry count they would drop but never the reason, so losing undo read as an
  unrelated side effect of the action the user actually asked for. The four notices now name the
  cause: a scene boundary leaves each entry with no device, rule, or specification to return to,
  while a template boundary removes the manifest an entry's device snapshot needs to interpret its
  own attributes and values. Behaviour is unchanged — these boundaries always cleared the journal
  (`BoardEditJournal.clear`).

### 2026-08-06

#### Fixed

- **The assistant's status prose now follows the interface language instead of guessing from the message.** The
  backend chose between Chinese and English by looking for a Han character in the user's own message, so a
  Chinese interface asked "hi" replied in English — as did any turn whose message was a device id, an English
  product name, or a greeting. The chat request now carries an optional `locale`, and that decides; message
  inspection remains only as the fallback for a client that sends none. This affected every backend-authored
  notice sharing the decision, not just the one visible in the report: error messages, interruption audits, and
  persistence-failure text. The decision had two independent copies — `ChatController` carried its own scan for
  the admission-outcome-unknown warning, which is among the least affordable to mislocalise because it tells the
  user not to retry until they reconcile. Both now read one owner (`ChatLanguagePreference`).
- **Removed the "no platform tool ran" sentence that duplicated its own badge.** The execution-trace header
  already carries a `No platform tools ran` badge from the same signal, rendered through the client's i18n. The
  sentence restated it at greater length, above the answer, in a language the backend could only infer — so one
  turn showed the Chinese badge beside English prose saying the same thing. The badge is the surviving owner: it
  follows the UI language and does not push the reply down the bubble.

#### Changed

- **Replay now has three surfaces with one question each, instead of three copies of one answer.** The canvas
  nodes, the trace timeline and the change popover all read the same `currentTraceState`. Measured against a real
  violated specification, the timeline's content was 529px inside a 318px viewport — 211px hidden behind a
  scrollbar — while it used 44% of the width the host reserved for it. The canvas turned out to be the *richest*
  of the three, not the weakest: it already prints each variable's value, its previous value, a `changed` tint,
  trust and the security pills, with `shortLabel` variants for a narrow node. So the timeline's device and
  environment chips were the weaker copy of what the user was already looking at, and they were the dominant cost.
  The timeline now answers only *when and what caused it*: step position, the rail, and the rule that produced the
  state. Content: **529 → 471 → 361 → 317px, with nothing hidden** — the overlay no longer scrolls.
  Three findings made this more than a deletion. The node strip caps at three variables and that cap was
  **silent**, with the timeline's full `traceDeviceSummary` as its unmarked fallback — so removing the duplicate
  first would have turned a redundancy into a hole. It now shows a `+N` chip naming the remainder, verified
  against a five-variable custom template (none of the 45 bundled templates declares more than three, which is
  why the gap had never surfaced). The replay-scope notice was an unconditional ~50-word paragraph filed under
  "state details" while describing the whole session, re-read on every step; it is a header hint now. And the
  height cap `min(44dvh, 20rem)` reads as responsive but `20rem` binds on every viewport taller than ~727px, so
  after the reduction it sat **1px** above the content — the next label would have re-armed the clipping and
  quietly undone the whole thing. It is 26rem now, which leaves ~99px of headroom while still holding the overlay
  to roughly a third of a 900px viewport.
  The popover was **not** removed, and that is the part worth recording: it is the only surface with room for
  `previous → current`. A node's changed-chip is capped at `58cqmin` — 64px on a 150×110 node — where
  "Temperature 24 → 26" truncates to a fragment, which the code already documented. Judged by inputs it looked
  redundant; judged by the question it answers it is not. `docs/guides/frontend-ui-conventions.md` §9 records the
  division so it is not re-litigated.
- **The trace overlay sat flush against the left edge, and on a long trace its controls left the screen.** Its
  host is `position: fixed` and a **sibling** of `.iot-board` — deliberately, so it floats above every panel — but
  it is positioned by `--board-control-width`, `--board-inspector-width` and `--board-floating-gap`, all declared
  *on* `.iot-board`. Inside the host those variables never resolved, `calc()` became invalid at computed-value
  time, and `left`/`right` fell back to `auto`. A fixed box with both set to `auto` shrink-wraps its content at its
  static position, which is x=0. Measured: `left` computed to `0px` against a declared `calc(56px + 16px)`, and the
  host was **the same width at 2556px and 1440px** (859.859px) — the tell, since a corridor-positioned element has
  to change with the viewport. On a 101-state trace the shrink-wrap reached **3258px**, putting the play button at
  x=2086 and off-screen at 1440x900: Playwright refused to click it, so playback was genuinely unreachable.
  This was a regression from `e91a109`, which removed the `var(…, 1rem)` fallbacks as dead text on the recorded
  premise that the gap is declared at `:root`. It is declared on `.iot-board`, and the fallback had been
  load-bearing for exactly the two elements outside that scope. Restoring it would only hide the defect again, so
  `boardShellStyle` now injects the variables onto the hosts — they are positioned by values they can see. The gap
  moves to `BOARD_FLOATING_GAP_CSS` in `constants/boardLayout.ts`, and `boardDockGeometry.spec.ts` fails if the two
  readers drift. The wrong premise is corrected where it was recorded, in
  `BoardSurfaceAccessibility.spec.ts`, rather than left to mislead the next reader.
- **Autoplay kept the advancing step in view.** Above 15 states the rail becomes a horizontal scroll region at
  38px per step. Every manual way of moving already centred the new step — keyboard, pointer, and stopping — but
  the autoplay tick advanced the selection without revealing it, so pressing play on a long trace left the user
  watching a rail that never moved.

#### Fixed

- **The device details dialog filled the whole screen.** It declared `max-w-4xl` (896px), but a scoped
  `max-width: 100%` listing `.device-dialog-surface` — there to contain overflow in the dialog *body* — carries
  Vue's `[data-v-…]` attribute and so outranked it, 0-2-0 against 0-1-0. The cap never applied. Measured on a
  2548×1465 display: **2516×1433, 98.7% × 97.8% of the viewport**, for content that needs 896px. It read as the
  app being replaced by a settings screen rather than a device panel opening over the board; now 896px, 35% of
  the width, with the board visible around it. The height cap moved from `calc(100vh - 2rem)` to `88vh`, matching
  the sibling dialogs, so the surface keeps a visible margin instead of reaching the edges. The remaining height
  is earned rather than imposed — content measures 2684px with 1587px scrolled out of view.
  `styles/__tests__/scopedWidthOverride.spec.ts` now fails on any scoped `max-width`/`max-height` that lands on
  an element also carrying the matching Tailwind cap. Writing it produced two instructive false positives, both
  fixed before it was kept: comparing any `max-*` against any `max-*` reported two `ControlCenter` dialogs that
  cap width in the template and height in CSS (different axes, no conflict), and scanning the scoped block
  without stripping comments reported `DeviceDialog` as still broken — matching the class name inside its own
  fix note. This is the third defect this session from the same specificity trap, so it is now a documented
  rule with its own section in `docs/guides/frontend-ui-conventions.md` rather than three separate comments.

### 2026-08-05

#### Changed

- **Recorded where the device-template manifest caps are actually enforced.** A dead-code audit reported the ten
  `RequestLimits.MAX_TEMPLATE_*` constants as declared-and-never-referenced and concluded the manifest
  collections were unbounded on an authenticated write path. Acting on that, `@Size` annotations were added to
  `DeviceTemplateDto` — and then reverted, because both premises were wrong. The template endpoint accepts a raw
  `JsonNode` and calls `validateRawManifest` *before* converting to the DTO, so Bean Validation never sees a
  manifest and a `@Size` there cannot run; and `backend/device-template-schema.json` already carried every one
  of those bounds as `maxItems` at the same values. The schema states this in a `$comment` that was in the
  repository the whole time. Verified against the live API: a 21-mode template is rejected `400` with
  `$.Modes: at most 20 items, found 21`. `MAX_TEMPLATE_ENVIRONMENT_DOMAINS`, deleted on the same false premise,
  is restored. What remains is documentation plus two guards against repeating the mistake:
  `DeviceTemplateSchemaValidatorTest` pins the schema bounds and the rejection message, and
  `credentialLimitsMirror.spec.ts` checks each declared constant against the schema rather than the DTO. The
  lesson is in the guard comments — an unreferenced constant is not automatically dead when its mechanism lives
  in another language.

#### Fixed

- **Five guards added in this change set could not fail, and a mutation audit caught them.** Every one read as
  coverage while protecting nothing, which is worse than no guard because it stops the next person looking:
  - `actionDockHierarchy`'s canvas-map check sliced `<div` up to the `data-testid`, so it only ever examined
    `'<div\n          '` — whitespace. A `v-show` written after the testid, which is where the original one was,
    was invisible to it. It now slices the whole opening tag; re-adding the `v-show` fails it.
  - The same test asserted the *absence* of `t('app.canvasView')` — a key that no longer exists anywhere, so
    nothing could reintroduce it. Replaced with a positive assertion that the heading does not branch on state.
  - `boardDockGeometry` looped over `.board-action-dock--packed`, which has no rule in `board.css` at all
    (packed mode is deliberately width-less), so that iteration asserted nothing. The scan now proves it found a
    rule before looping.
  - `FixDtoSerializationTest` asserted `doesNotContain("isSourceModelComplete")`, which is unfalsifiable: Jackson
    never emits a getter name as a JSON key under either typing. Now serializes an all-defaults builder and
    requires `false`/`0`, which boxed types would render as `null`.
  - `FaultLocalizerTest`'s null-preview test used a single rule, so no conflict occurred and the `rulePreview`
    helper it names was never invoked — the production change could be reverted entirely and it stayed green. A
    two-rule conflict test replaces it, and reverting the split now fails two tests.
  Two further guards passed but were weaker than advertised, and were tightened: the template-caps mirror
  value-compared only `MAX_TEMPLATE_MODES` and merely acknowledged the other six, so one could have drifted to
  500 unnoticed; and `LocaleSensitiveComparisonTest` silently `continue`d past a missing scan directory. One
  tautological test was deleted outright — it asserted JDK case-folding over its own literals, so no repo change
  could redden it; the premise it claimed is proven for real by the test that swaps the JVM default locale.
- **A never-rendered decoration was about to become visible.** `SystemInspector`'s spec cards carried
  `<div class="absolute inset-0 board-chip-info/30">` labelled "Subtle background pulse". There is no animation
  anywhere in the file — the slashed class generated no rule, so the element had always been inert and invisible.
  De-suffixing it as part of the opacity-modifier fix would have painted a flat full-strength wash over every
  spec card for the first time. Deleted instead. The other ten de-suffixed sites are genuine completions: each
  pairs an explicit `border-[color:var(--warning-border)]` with warning ink, so the surface was the missing third
  of a deliberate callout (measured 4.84:1 light / 10.66:1 dark, both clear AA).
- **`Result.java` had been rewritten with CRLF line endings**, against the repo's `eol=lf` attribute — the only
  such file in the change set, introduced by a scripted edit. Converted back to LF.
- **An orphaned `is-inspector-collapsed` class binding** stayed on the board root after its CSS rule was removed.
  Nothing read it: no CSS, no test, no E2E selector. Removed.
- **A conflicting rule's name rendered double-quoted, and in English when that rule had no preview.** One
  `describeRule` return value fed two consumers needing different things: the English `reason` diagnostic, where
  prose and `'quotes'` are fine, and `conflictingRuleString`, which the client interpolates into an
  already-translated sentence. A zh-CN user therefore saw 与“'When motion, turn on light'”冲突 — quoted twice — and
  与“another localized rule”冲突 when the other rule had no preview. Split into `rulePreview`, which returns the raw
  value or null, leaving `describeRule` to build only the English diagnostic. The client quotes and localises,
  the same division of labour as `ruleString`. The whole `conflicting` branch of `validateFaultRule` had no test
  at all — the shared fixture only ever set `conflicting: false` — so three were added and mutation-checked.
- **A rule saved without a preview string made every fix request on it fail as "malformed result".**
  `RuleDto.ruleString` is a user-facing preview with no `@NotBlank` and a nullable TEXT column, so a rule
  legitimately persists without one — verified against the running API, which accepts a rule with the field
  omitted and echoes `"ruleString": null`. `FaultLocalizer` copied that null straight into `FaultRuleDto`, and the
  frontend's `validateFaultRule` calls `text(row, 'ruleString')`, which throws on null and rejects the **entire**
  fault-localization response. So asking for a fix on a trace involving such a rule produced a contract error
  about a malformed result rather than the fix — fail-closed, but telling the user the wrong thing about the wrong
  surface. The fallback belongs in the UI, not the server: `FaultRule.ruleString` is now `string | null`, the
  validator accepts null while still rejecting a non-string, and `FixResultDialog` renders
  `rule.ruleString?.trim() || t('app.ruleNumber', …)` — matching what `PlaybackChangePopover`,
  `SimulationTimeline` and `Board.vue` already do for `TraceRule.ruleLabel`. My first attempt put an English
  fallback ("Automation rule at position N") in `FaultLocalizer`; reviewing it caught that this field renders
  directly in the UI, so a server-side English label would show a zh-CN user English text — which the bilingual
  rule in `CLAUDE.md` forbids. The backend now passes the null through, pinned by a test that fails if it
  fabricates a label.
- **Two TypeScript types mirrored one backend `RuleDto` and disagreed about null.** `BackendRuleDto` in
  `api/board.ts` serves the live-board read and `ModelRule` in `types/model.ts` serves the frozen playback scene —
  and `ModelPlaybackSceneDto` is `record(List<DeviceNodeDto>, List<RuleDto>)`, so both really do mirror the same
  Java DTO over two endpoints. They declared it differently: `id?: number` versus `id: number | null`, and
  `ruleString?: string` versus `string | null`. The nullable forms are the accurate ones — `RuleDto.id` is a
  `Long`, and `ruleString` is populated by `optionalText`, which returns null — so the playback mirror forbade a
  value the server can send. Nothing misread it in practice (`playbackScene.ts` uses `rule.id ?? ruleIndex` and
  `rule.id == null`, which cover null and undefined alike), but a type that contradicts the wire is a trap, and
  the next field to diverge may not be read so defensively. `RuleDto.createdAt` is deliberately left out of both:
  it is on the wire and nothing reads it, so declaring it would document an unused field.
  `types/__tests__/ruleDtoMirrors.spec.ts` compares the two. Writing it produced two instructive near-misses,
  both fixed before the guard was kept: the field-name scan keyed on an indentation width that matched only one
  file, so it silently compared an empty set against a full one; and the optionality check accepted `?:` as
  proof, which meant narrowing `id?: number | null` back to `id?: number` still passed — a guard blind to the
  exact drift it was written for.
- **Corrected a wrong diagnosis in the agent instruction files.** A `BUILD FAILURE` with an empty error list,
  and separately a run reporting `746 tests, 22 failures, 624 errors` with mass `NoClassDefFoundError` and
  `MockitoException: Could not modify all classes`, were both attributed to a second concurrent Maven build.
  Measurement says otherwise: the VS Code `redhat.java` language server's JDT project for this repo declares
  `kind="output" path="target/classes"` — verified in its `.classpath` under `workspaceStorage` — so it
  auto-builds into Maven's own output directory and rewrites class files mid-compile. It is a ~1.3 GB `java.exe`
  indistinguishable from any other JVM in the process list, and the tree it condemned was in fact 2216/2216
  green. The two contenders have different symptoms and must not be conflated: the language server produces mass
  `NoClassDefFoundError`, while the `spring-boot:run` dev JVM produces a failed `clean`
  (`Failed to delete …/target`, zero tests, succeeds on one retry). I briefly deleted the latter claim after one
  run cleaned successfully with that JVM alive — then the next run reproduced it, so it is intermittent and a
  single clean run does not disprove it. `backend/CLAUDE.md` now names both, and records that an isolated build
  copy needs `../docs/`, the root `README.md`, `CLAUDE.md` and `backend/device-template-schema.json` — omitting
  the schema alone fabricates ~90 failures.
- **The three dismiss tools each carried a byte-identical error-preview truncator.** `DismissFuzzTaskTool`,
  `DismissSimulateTaskTool` and `DismissVerifyTaskTool` all declared `ERROR_PREVIEW_LIMIT = 1_000` and the same
  `errorPreview` method. They agreed, and the consequence of drift is modest — but the model reads these strings,
  so one diverging would have summarised the same failure at two different lengths depending on which run kind
  the user dismissed, and nothing compared them. Both now live on `AbstractAiTool`, which all three already
  extend, and `AiToolLayeringContractTest` fails if a tool re-declares either.
- **The guard that refuses to boot with default secrets could be skipped by the server's locale.**
  `ProductionSafetyCheck` decided "is this production" with a bare `toLowerCase()`, and a Turkish default locale
  folds `PRODUCTION` to `productıon` — which matches neither `prod` nor `production`. The fail-fast check for
  default `JWT_SECRET`, `DB_PASSWORD` and `IOT_VERIFY_OPENAI_API_KEY` would then not fire, and the application
  would start in production with insecure defaults, silently. `spring.profiles.active=prod` was unaffected
  (`PROD` folds to `prod` either way), so only the long form was exposed. Now pinned to `Locale.ROOT`.
  This was the eleventh instance of the same defect and the one that mattered most — and my first guard **missed
  it**, because I scoped the scan to the model-generation and request-validation directories. A guard whose scope
  excludes the highest-stakes fold in the product reads as coverage while providing none, so the scan now covers
  `configure` and `security` too, and finding it that way is the only reason it was found at all.
  The decision also existed twice, here and in `JwtUtil` — both answering the same security question, one
  refusing to boot and one warning about the default JWT secret. Reviewing my own first fix caught that sharing
  the `Set` alone was not enough: it left each class with its own copy of the case fold and the loop, and the
  fold was the part that had drifted, so that version would have fixed the symptom and kept the mechanism
  duplicated. `ProductionSafetyCheck.isProductionProfile(Environment)` now owns the vocabulary, the fold and the
  loop, and the set is private again. It reads a null `Environment` as "not production" rather than throwing,
  since `JwtUtil` calls it during `@PostConstruct`. Pinned by a test that actually swaps the JVM default locale
  to Turkish — the source-scanning guard proves the pin is written, this proves it works.
- **Three more locale-dependent folds, found by widening that guard's own scope.** The first version scanned six
  hand-picked directories while its own doc warned that a guard excluding a high-stakes fold "reads as coverage"
  — which is what it was doing. Now it walks all of `src/main/java`, and found: `AuthRateLimitException` building
  `reasonCode` as `"AUTH_" + operation.toUpperCase() + "_RATE_LIMIT_REACHED"`, so a Turkish-locale server emits
  `AUTH_LOGİN_RATE_LIMIT_REACHED` and the frontend's exact-match on `AUTH_LOGIN_RATE_LIMIT_REACHED`
  (`Landing.spec.ts:108`) misses it, so a rate-limited user sees a generic error instead of retry guidance;
  `ListAsyncTasksTool` folding four LLM-supplied arguments before matching keyword sets, so a valid
  `ai_assistant` becomes `AI_ASSİSTANT` and is rejected as invalid; and `UserOperationGuard` building its Redis
  admission key from `kind.name().toLowerCase()` — the worst of the three, because two servers with different
  default locales would compute different keys for the same operation kind, making the lease mutually invisible
  and silently doubling the concurrency limit. The two remaining hits are English diagnostic prose interpolated
  into an exception message, exempted with that reason stated.
- **Ten case folds that decide a NuSMV keyword ignored the locale, and the codebase already documented why that
  is wrong.** `SmvSpecificationBuilder.normalizeSpecTargetType` carries the rule — a Turkish default locale maps
  `I` to the dotless `ı`, so `"API".toLowerCase()` becomes `"apı"` and matches no keyword — but a comment was the
  only thing enforcing it, and ten sites had drifted. The worst were the three `NUSMV_RESERVED_WORDS`
  comparisons in `DeviceSmvDataFactory` (and two more in `DeviceTemplateNuSmvValidator`), each checked in both
  cases: `INIT` folds to `ınıt`, misses the reserved-word set, and is emitted verbatim, so a reserved word would
  reach NuSMV as a variable name instead of being rescued with a `_` prefix. The rest reject valid input rather
  than admitting invalid: spec `targetType` normalization in `NusmvRequestValidator` and `FixStrategyApplier`
  would refuse a legitimate `API` condition, `CounterexampleInitialStateConstraints` would throw on a valid
  `Trusted`, and `SmvRelationUtils`' relation switches and `JwtUtil`'s production-profile check would miss their
  keywords. All ten now pin `Locale.ROOT`. Behaviourally identical under an English locale, which is why nothing
  caught them. `LocaleSensitiveComparisonTest` scans for bare folds in the directories where case decides a
  keyword, with a short exemption list for genuine substring searches (the JVM's `os.name`, NuSMV's English
  stdout), and separately asserts the dotless-i premise so the rule cannot be relaxed on a false assumption.
  I found only the lowercase half by hand; the guard is what surfaced the `toUpperCase()` siblings and four
  other files.
- **A device could take a name the automatic fixer needs, and only found out when a fix was requested.** The
  fix strategies mint frozen variables as `param_*`, `lambda_*` and `condition_value_*`. Unlike every other
  generated identifier — all namespaced `iot_verify_*` precisely so user input cannot reach them — these are bare
  prefixes, and `param_` cannot be renamed because it is the wire format for
  `PreferredRangeSelection.targetId`, validated by a `@Pattern` on the DTO and by `^param_[A-Za-z0-9_-]{24}$` in
  the frontend. Verified against the running API: `/api/board/nodes` accepts a device with canonical id
  `condition_value_r0_c1`, so the board saved and verified normally and `SmvMainModuleBuilder` only threw when the
  user later asked for a fix — fail-closed and diagnosable, but blaming a surface they were not editing, long
  after the name was chosen. The three prefixes are now reserved in `NusmvRequestValidator`, which runs on every
  verify and simulate, so the clash is reported on `devices[i].varName` at the user's first verification. Only a
  *prefix* is reserved; `my_param_sensor` remains a legal device name, and that boundary is pinned by a test.
  The generation-time check stays as a backstop, because `param_<hash>` is not known until a strategy picks it.
- **Two device-template mode names could collide in the generated model, and the user saw a NuSMV type error.**
  `sanitizeSmvToken` rescues a NuSMV reserved word by prefixing `_`, so a mode named `next` becomes `_next`. The
  collision check compared the *raw* name, so modes `next` and `_next` passed as distinct and generation then
  declared the same enum constant twice. Verified against NuSMV 2.7.1: the model is rejected with
  `TYPE ERROR: duplicate constants in the enum type of variable`, i.e. verification died with an engine error
  instead of a message naming the field to rename. Modes and working states are the only identifier kinds that
  skip the reserved-word rejection variables get — deliberately, because generation rescues them — which is
  exactly what made comparing pre-rescue names wrong. The check now compares the token generation will emit, for
  modes, internal variables and impacted variables alike.
- **101 style declarations did nothing at runtime.** Tailwind only generates `hover:`/`focus:`/`disabled:`/`dark:`
  variants for utilities *it* owns, so `hover:board-chip-danger` on a hand-written class emitted no rule at all —
  confirmed against the built bundle, where no `<variant>\:board-*` selector exists while the base classes do.
  Ninety sites were affected. Measured in a browser: the **device delete button's colour and background were
  identical on hover** (a destructive action with no danger cue), the cancel/delete buttons on running
  verification tasks likewise, and `disabled:board-chip-info` on the runtime save button meant a *disabled*
  primary kept its accent fill and read as enabled. Eleven further sites used opacity modifiers
  (`board-chip-warning/70`), which generate nothing at all — not even the base strength — so danger and warning
  blocks rendered as unstyled neutral text. The variant forms are now declared once in `board.css`; the opacity
  modifiers are gone.
  Placing them was the hard part and is worth recording: the ink variants had to go *after* the neutral-text
  normaliser, because at equal specificity source order decides. Raising specificity twice did not work; only CDP
  matched-styles showed which rule won. The symptom of getting it half-right was a danger-tinted background under
  a grey glyph.
- **A username length problem could be reported as a character-set problem.** `utils/accountIdentifier.ts`
  hardcoded `3`/`20` — the same defect as `UsernameNormalizer` last round, recurred on the client. `Landing.vue`
  checks length against `CREDENTIAL_LIMITS` and reports `auth.usernameLength`, then calls
  `isValidNormalizedUsername` and reports `auth.usernameInvalidCharacters`; on divergence the user reads "invalid
  characters" about a name containing none. The mirror spec could not see it — its call-site scan targeted
  `Landing.vue` and the identifier `usernameLength` — and now scans every participating module with a
  name-agnostic pattern.
- **Four AI tools described the same argument to the LLM in two different ways.** `attackPoints` was built by four
  private `attackPointsSchema()` copies; three said "Required for attackMode exact" and one "Required *only* for
  attackMode exact", while `attackScenarioArg` rejects a non-empty value for both `none` and `exhaustive` — so
  three tools understated the constraint and invited the model to send an argument that gets refused.
  `attackBudget` had the same split across the two verification tools. Both schemas now sit on `AbstractAiTool`
  beside the validator that enforces them. Reviewing that fix caught the mirror-image mistake in it: my first
  shared description named `exhaustive`, but simulation passes `allowExhaustive=false` and offers only
  `none`/`exact`, so it would have told the model about a mode simulation rejects — the same defect, introduced
  from the other side while removing it. The schema now takes the capability as a parameter, and two guards in
  `AiToolLayeringContractTest` pin both directions: no tool may declare its own copy or inline the description,
  and each tool's declared capability must match its own `attackScenarioArg` call. A divergence in tool-facing
  prose is a behavioural difference, not a wording preference.
- **The default value of a template variable was decided by four functions, two of them laxer than the schema.**
  `deviceRuntime.ts` accepted *either* numeric bound and defaulted on `LowerBound` alone, and `SystemInspector`
  carried a fourth copy of the same rule. `device-template-schema.json` requires both bounds together
  (`oneOf`), and `BoardStorageServiceImpl.defaultValueForVariable` agrees, so a single-bound variable cannot
  reach the client — the laxity was unreachable rather than harmless, and it documented a rule the product does
  not have. One owner now.
- **`FuzzController` hardcoded `@Size(max = 100)`** where `SimulationController` and `VerificationController` use
  `RequestLimits.MAX_TASK_EXCLUSIONS` for the same parameter. Same value; now the same source.
- **The fuzz iteration/path/population bounds were declared three times** — `utils/fuzzingConfig.ts`,
  `FuzzRequestDto`, `FuzzWorkloadPreviewRequestDto`, with both DTOs separately `@Valid` on live endpoints and the
  frontend rendering the ranges to the user as the allowed values. They agree today; nothing compared them, so a
  future edit to one would have told the user the wrong rule. `fuzzingConfig.spec.ts` now mirrors both DTOs.
- **The verification spec-result matcher's positional fallback is no longer silent.** When a NuSMV result cannot
  be matched to a submitted specification by expression, it is back-filled by position — a guess that left the
  run reporting a definite verdict with guessed per-spec attribution, since the result count still matched. It now
  emits a `[spec-attribution-uncertain]` check log, which the result dialog renders. Investigated whether it can
  fire: NuSMV echoes each specification verbatim (measured), so the expression match succeeds even though NuSMV
  *does* reorder results (also measured — three specs came back with the third first). The normalizer strips all
  parentheses, which means `A & (B | C)` and `(A & B) | C` share a key and NuSMV really does give them different
  verdicts, but two specs can only collide if they are duplicates, and `validateNoIdenticalSpecifications`
  rejects those on both write paths. Unreachable today, explicit if that ever changes.
- **The username validator ignored the constants the rest of the product mirrors.** `UsernameNormalizer` had its
  own copy of the phone pattern and its own `3`/`20` length literals, while `RequestLimits` owns
  `PHONE_PATTERN`, `MIN_USERNAME_DISPLAY_LENGTH` and `MAX_USERNAME_DISPLAY_LENGTH` — and
  `credentialLimitsMirror.spec.ts` asserts the *frontend* agrees with exactly those. Those two length constants
  had no other backend reader at all, so the bound was mirrored into every layer except the validator that
  enforces it: changing one would have moved the client and the mirror test while this validator kept the old
  numbers. It reads `RequestLimits` now. Values were already identical, so no behaviour changed.
- **Removed dead backend code, each verified as having no caller in `src/main` or `src/test`.**
  `LevenshteinDistanceUtil` (the whole class — zero references anywhere including docs and the frontend); an
  injected-but-never-dereferenced `SmvGenerator` field on `BoardStorageServiceImpl`, which was a real
  constructor parameter and so also removed the corresponding positional argument at 32 call sites across 8
  test files; seven exception factory methods (`ForbiddenException.accessDenied/resourceNotOwned`,
  `InternalServerException.databaseError/aiServiceError`,
  `ValidationException.invalidPhone/invalidPassword/invalidUsername` — the last two also hardcoded the password
  and username bounds `RequestLimits` owns); `Result.validationError`/`tooManyRequests`, the only members of
  that family with no caller, because `GlobalExceptionHandler` builds 422/429 through its own helper;
  `NusmvTempArtifactRegistry.isProtected`; `JwtUtil.getPhoneFromToken` and the throwing
  `validateTokenOrThrow` variant (the boolean `validateToken` is live);
  `SpecificationFormulaPreview.labelsOnly`; and `FuzzMapper`'s two list wrappers.
- **`findDeviceSmvData` and `findDeviceSmvDataStrict` were one behaviour behind two names.** Both delegated to
  the same internal method with the same arguments, while the Strict javadoc claimed it "does not fall back to
  the template name" — that fallback had already been removed, so the comment described a distinction that no
  longer existed and callers had to choose between two names for one behaviour. Collapsed to the one the seven
  real call sites use.
- **Three copies of the Jackson error-path formatter became one.** `BoardBatchRequestParser.formatPath` and
  `ModelRequestParser.formatPath` were byte-identical and `GlobalExceptionHandler.jsonPath` was the same loop
  differing only in its empty-path fallback. All three exist to tell a user which field of their request was
  rejected, so a format change had to be made three times or the same rejection would read differently
  depending on which layer caught it. `util/JsonPointerPath` owns it, with the fallback as a parameter.
- **Two byte-identical blocks in `Board.vue` became one owner each.** "Clear every workflow surface except the
  one I am opening" was written out as the same eight lines, in the same order, in `toggleHistoryPanel` and
  `openTaskInbox` — so a seventh surface would have had to be remembered in both. It is now
  `closeOtherWorkflowSurfaces()`, the closing-side counterpart to the `isWorkflowPanelOpen` predicate that
  already owned the reading side. Separately, the two formal-verification handoff openers
  (`openFormalVerificationForFuzzFinding`, `openFormalVerificationForCurrentBoard`) differ only in the handoff
  object they build; their identical five-line tail is now `showVerificationPanelForHandoff()`.
- **A dock button's accent colour and a file-picker's hover were both silent no-ops.** `ControlCenter` applied
  `board-text-accent` at two sites and `hover:board-chip-accent` at one. Neither existed: the `board-text-*`
  family had no accent member, and `hover:` is a Tailwind variant that Tailwind only generates for utilities it
  owns — so the advanced-overrides icon rendered in inherited text colour and the "choose file" label had no
  hover response at all. The label is fixed by `.board-file-trigger`, which owns both states in CSS.
  The icon needed a second fix, because simply adding `.board-text-accent` did not work: `ControlCenter`'s own
  scoped `.device-runtime-box span { color: inherit }` — there to neutralise the Tailwind slate utilities that
  markup still carries — matched the icon's span, and a scoped rule carries `[data-v-…]`, so it outranked the
  global class no matter what. Adding an `.iot-board` prefix for specificity did not help, and neither did
  appending an identical rule last; equal specificity was never the problem. The blanket rule now exempts the
  role ink. Measured after the fix: the icon paints `--accent` in both themes, at 5.17:1 (light) and 4.91:1
  (dark) against its box.
- **Three domain vocabularies had four, three and two copies respectively, each with an owner already in
  place.** Trust/privacy values were rebuilt as local `Set`s in `api/board.ts`,
  `recommendationMaterialization.ts` and `traceStateResponse.ts` (that one reversed the privacy order) plus
  inline literals in `device.ts`, while `deviceRuntime.ts` already owned `TRUST_OPTIONS`/`PRIVACY_OPTIONS` for
  the dropdowns — so a new domain value would have been offered by the UI and rejected by four validators. The
  `Set`s are now derived from those same arrays. `MODEL_TOKEN_SOURCES` was rebuilt in three boundary validators
  while `types/modelToken.ts` owned the type; the runtime list is now derived from the same array as the type.
  The two `validateModelTokenSource` implementations stay separate: each throws its own typed contract error,
  which the repo's typed-error rule requires.
- **Removed dead frontend code, each verified by reference search.** Two CSS rules (`.board-text`,
  `.board-muted` — the live classes are `.board-text-strong`/`.board-text-muted` and `.board-muted-surface`);
  three declared-but-never-emitted events (`SystemInspector`'s `toggle-rule`, `ControlCenter`'s
  `verify`/`simulate` — the `@verify` in `Board.vue` belongs to `FuzzingResultDialog`); and four unread props.
  `DeviceDialog`'s `rules` prop took the whole chain with it — the `dialogMeta.rules` field, its type member,
  and the `edges.value.filter(...)` that computed it on every dialog open purely to feed a prop nobody read.
  `ControlCenter`'s `edges`/`canvasPan`/`canvasZoom` had to be removed *together with* their parent bindings:
  the component sets `inheritAttrs: false` and spreads `v-bind="attrs"`, and declared props are excluded from
  `$attrs`, so dropping only the declarations would have started spreading three attributes onto a DOM element.
- **Removed the last of the canvas map's overlay-era styling, including a second `.canvas-map` rule.** It set
  `background` and `backdrop-filter` and silently overrode the `background` from the shared board-surface rule
  200 lines above it — one property, two owners, the later winning by position. Neither declaration was doing
  anything visible: measured, the card painted as 77%-opaque white with a 12px backdrop blur *on the
  inspector's opaque `#f8fafc` panel body*, so the blur had nothing behind it and the alpha only washed the
  card against its own parent.
- **The canvas zoom and fit-to-content controls no longer disappear when a result panel opens.** The canvas
  map card was hidden whenever any of eleven surfaces was visible — verification, simulation, exploration,
  run history, the four recommendation panels, either playback timeline, or the fix dialog — and the zoom
  field, zoom buttons and fit-to-content live inside that card. So opening a counterexample took away every
  pointer zoom control on the board, with nothing to say where they had gone, at exactly the moment a user
  wants to zoom in on what the panel points at. The condition is gone entirely, because the collision it
  guarded against cannot happen: the floating panels carry a `right` inset that clears the inspector and the
  action rail, so a panel spans x=660..948 against an inspector at 1120..1440 (1440x900) and 336..692 against
  780..1100 (1100x800) — neither touches the inspector, let alone the map card inside it. At narrow widths the
  inspector is a 56px rail and the map is not rendered at all. The second half of the old condition
  (`inspector.collapsed`) was also dead: the slot renders inside the inspector's own `v-if`.
- **The action dock's collapse handle overlapped the button below it and stuck out of the dock.** The handle
  was a 1.45rem badge grown to a 44px target by `padding: 0.65rem` with `margin: -0.65rem` cancelling the
  growth, on the theory that `background-clip: content-box` kept the visible chevron small while the target
  stayed large. `background-clip` does not clip the border, so the bordered box painted at **45.97px** while
  reserving **23px** of layout, and the negative margin spent the difference on its neighbours: it overhung
  the dock's right edge by 2px (`scrollWidth` 141 in a 138px content box) and pressed onto the first tool
  button. `targetSizeFloor.spec.ts` passed throughout because it read the padding arithmetic rather than the
  painted result. The handle is now a plain 44px border-box button whose header row reserves 44px, and the
  packed mode's launcher — the same position doing the same job — matches it instead of being 56px, which is
  what made the packed strip look like a larger widget than the other two. Verified in a browser: all three
  modes now measure a 44x44 top block with an 8px radius and zero overflow.
- **The action dock's three modes were three different shapes.** `expanded` and `packed` were floating
  panels (`--iot-radius-panel`), `compact` was a capsule (`--iot-radius-pill`), and each mode restated its
  own padding and gap — a third set again under short landscape. Its collapse handle switched sizing model
  *and* radius between modes, so one control looked like two. All three modes now share the panel radius and
  one padding/gap pair; the mode layer declares only what genuinely differs (labels, square buttons, the
  packed launcher). The shared padding is `0.3125rem` because that is the only value that fits: 44px button +
  2×5px padding + 2×1px border = the 56px rail, exactly. `0.45rem` had been overflowing it by 2px per side,
  which is what the per-mode overrides were papering over.
- **The dock's rail width had four owners that disagreed, and fit-to-content trusted the wrong one.**
  `8.75rem`/`3.5rem` in `Board.vue`, `150`/`64` px in the canvas fit math, and
  `--board-action-rail-width` in `board.css` as `3.1rem` with an `8.25rem` override above 1280px. The
  corridor the CSS reserved, the width the dock painted, and the width `getVisibleCanvasFrame` subtracted
  were three different numbers, so fitting content could place a node underneath the rail. One table in
  `constants/boardLayout.ts` now owns it; the CSS token is a pre-hydration default only. The gap between the
  inspector and the dock had the same problem one level down: `getVisibleCanvasFrame` added its own `8`/`16`
  px on top of a reserved width that already accounted for spacing, double-counting the gap by up to 12px in
  the one function whose job is to know where the free canvas is. It now reads the same
  `actionDockGapPx` the dock is positioned by. Verified in a browser: with four spread devices,
  fit-to-content leaves every node clear of the dock, inspector and control panel.
- **The collapsed side-rail width had drifted between its copies.** `COLLAPSED_PANEL_RAIL_WIDTH = 56` in
  `Board.vue`, a `3.5rem` literal in each of the two panels, and `--board-inspector-collapsed-width: 3rem`
  in CSS — and it was the stale 48px copy that the floating panels and the dock measured their right inset
  from, so the reserved space was wrong whenever the inspector was collapsed. All four now read
  `COLLAPSED_PANEL_RAIL_PX`; the CSS token is gone because the injected panel width already carries the
  collapsed value.
- **Removed the canvas map's dead positioning rules.** `top`, `left`, `bottom`, `max-width` and
  `transition: left, top` described its previous life as an overlay docked to the canvas's top-left corner.
  It has lived in the inspector's overview slot, in normal flow with no `position`, since the workflow
  alignment change — every one of those declarations was inert. The CHANGELOG entry claiming it "docks to
  the visible canvas top-left" was describing behaviour that no longer exists.
- **Elevation is now a three-step scale instead of eight hand-written shadows.** `--shadow-elevated` was the
  only depth token, so anything wanting a different depth wrote its own literal: eight distinct neutral
  elevations across `board.css` and `base.css`, no two agreeing. Every one carried a light-theme wash
  (`rgba(15, 23, 42, …)`) in a property whose token the dark theme overrides — so on a near-black ground those
  shadows did nothing while their neighbours deepened, which is the "the shadow is still those same few
  colours" complaint. Now `--shadow-raised` (a control lifting off its surface), `--shadow-floating` (a
  transient chip or canvas node above content) and `--shadow-elevated` (a panel above the page), each declared
  per theme with the dark steps deeper and more opaque, because a shadow darkens its ground and a near-black
  ground has almost no headroom left.
- **Two more elevation tokens existed, and both were wrong in dark theme the same way.** `--iot-node-shadow`
  and `--iot-color-card-shadow` were declared `rgba(15, 23, 42, 0.9)` for dark — the *light* palette's navy at
  90% opacity, where every dark shadow is `rgba(2, 6, 23, …)`. The node one also broke depth continuity: the
  node's resting rule used the scale while its four state rules (focus-visible, node-focused, trace-active,
  trace-changed) used that token, so highlighting a node silently changed its base depth as well as adding its
  ring. Both tokens are now removed and their five call sites read the scale; the Element Plus dialog takes the
  panel step and the toast the floating step.
- **The board nav no longer carries a panel-sized shadow.** It is full width and flush to the top with its own
  bottom border, so an 18px/42px lift under something with nowhere to float to reads as a smear across the
  viewport — and it was darker than the literal it replaced (0.16 against 0.08). All it has to say is "content
  scrolls under this", which is the floating step.
- **Hovering a dock button dropped a panel-sized shadow across the canvas.** The tooltip used
  `--shadow-elevated` — an 18px offset, 42px blur panel lift — on a two-line hover label. It now uses the
  chip step. Depth says what kind of thing something is, so a chip claiming a panel's depth was making a false
  claim, not merely looking heavy.
- **Hovering a canvas node inverted its edge instead of lifting it.** The resting hairline is a dark line at
  12% of `--text`; hover replaced it with `rgba(255, 255, 255, 0.72)`, a near-opaque white ring — so in light
  theme the edge flipped dark to white, and in dark theme a bright white outline appeared on a navy node. That
  is a colour change dressed as a depth change, which reads as a glitch rather than a hover. The hairline now
  stays put and only the elevation moves (floating to elevated).
- **One of eight dock buttons floated above the strip.** The verification button carried Tailwind's
  `shadow-lg` in the markup while its seven siblings declare `box-shadow: none`, inside a panel that is itself
  elevated — so the tier difference read as a depth difference, and `shadow-lg` is a raw light-theme shadow
  that never followed the theme. Emphasis between tiers is the fill and border, per
  `frontend-ui-conventions.md` §4.
- **Two dock tiers meaning the same thing sat on two different neutrals.** Run History used
  `--board-control-bg` (#f1f5f9) while the four AI suggestions used `--board-card-bg` (#ffffff), four rows
  apart in one strip — a grey band among white buttons, when both tiers mean "not the primary action". A
  background change down a vertical list implies a category change; what actually separates these is the
  border hue and the label. Their hover and pressed states were still mixing against the old ground, which
  would have made hover *lighter* than rest in light theme. Border contrast was re-measured on the new ground
  in a real browser (changing the ground invalidates the old reading): 4.76 vs fill / 3.02 vs panel in light,
  3.73 / 3.90 in dark — every dock border still clears the 3:1 component minimum in both themes.
- `elevationScale.spec.ts` pins all of the above for `base.css` and `board.css`; each of its five assertions
  was confirmed to fail against the value it replaced. **Twenty hand-written elevations remain outside the
  board** and are listed in the spec and in `frontend-ui-conventions.md` §7 rather than silently skipped —
  each needs its depth chosen and measured on its own surface.
- **The action dock's heading was tied with the labels it introduces.** "Board tools" rendered at
  `0.72rem`/800 while the group labels *beneath* it use `--iot-font-min` (11px)/700 — half a pixel of
  difference at a heavier weight, so two heading levels rendered as one and a reader saw three rows of small
  uppercase text with no indication of which named the panel. It cleared `typographyFloor` (11.52px over an
  11px floor), which is why nothing flagged it; a floor is a minimum for body text, not a target for a
  heading. It now uses `0.875rem`, the tier the side panels' own titles use, and drops `uppercase`, which
  costs apparent x-height and word shape and is most of why four characters read as smaller than they
  measured. The button labels beside it had the same bespoke-value problem (`0.78rem`, used nowhere else in
  the product) and now use the scale's `0.75rem` step, as does the tool tooltip, which was the third site
  carrying that same bespoke `0.72rem`. Measured in both locales: no truncation, tooltip still inside the
  viewport.
- **Four copies of "which dock modes does this viewport allow" became one.** Two hardcoded cycle arrays
  selected by a `>= 1280` check, a separate clamp restating the same threshold, a restore handler
  re-deriving the same answer, and a `>= 1280` ternary inline in the launcher's `aria-label` — agreeing only
  by coincidence, so a new width rule had to be remembered in three other places. Everything now derives
  from one `availableActionDockModes` list, with the 720px launcher-only fallback stated as the single
  documented exception rather than emerging from overlapping conditions. Verified equivalent to the previous
  logic across every width/preference combination, and in a browser at wide, mid and phone widths.
- **The set of open workflow panels was enumerated up to three times per predicate.** The canvas-map
  viewport and `showCanvasEmptyState` both ask "is a workflow panel open"; each listed the five members, and
  one also re-listed the four recommendation flags `isAnyRecommendationPanelVisible()` owns and the two
  timeline flags `isAnimationLocked` owns. A sixth panel meant remembering up to three lists. One
  `isWorkflowPanelOpen` now names the set. The empty-state predicate turned out to be covered only by E2E —
  a unit-level mutation of it was silent — so `actionDockHierarchy.spec.ts` now covers it.
- **The canvas map's zoom/fit buttons had their size split across two blocks 66 lines apart**, one setting a
  22px icon box and the other the 44px floor, plus a third copy behind a coarse-pointer query that implied
  the floor was conditional. They render 44x44 either way — but only because `min-*` happens to beat `width`
  in the cascade, which is not a decision anyone made. Now one block, and `targetSizeFloor.spec.ts` asserts
  the floor specifically rather than requiring every declared size to clear it (a rule that only passed while
  the two owners were separated, and would have read the icon box as a violation once they were merged).
- **The canvas map has one heading and no hiding rule.** An intermediate version of the fix above hid only the
  map rectangle and renamed the card to "Canvas View" while it was hidden. Both were treating a symptom: once
  the panels were measured and shown never to reach the inspector, the card needs no state-dependent visibility
  and therefore no second name. A card that renames itself is a worse answer than a card that stays put.
- `boardDockGeometry.spec.ts` pins each of the above: one declaration of the rail token, one per-mode table
  read by both paint and fit math, one surface shape across modes, the 44px-in-56px arithmetic, and one
  owner for the collapsed rail. Each assertion was confirmed to fail against the value it replaced.

### 2026-08-04

#### Fixed

- **Two filled controls were unreadable in dark theme.** The attacked-device badge and the template-reset
  button painted their surface with a role's *ink* token and wrote white on it — `--danger` and `--warning`
  are tuned as text and the dark theme lightens them, so the labels measured **1.90:1** and **1.44:1**, the
  worst readings in the interface. Both passed in light theme (6.47 and 5.02), which is why looking at one
  theme never found them. They now use the `-fill` half and deepen rather than lighten across the gradient,
  so both stops clear AA. `inkFillSeparation.spec.ts` makes the pattern unrepeatable.
- **The theme and language toggles now show a hover border you can see.** Both used
  `rgba(53, 158, 255, 0.45)`, which measures **1.54:1** against the surface it sits on — the same `#359eff`
  `focusIndicator.spec.ts` already records as a failed focus ring elsewhere. `--accent-border` is the token
  for a 3:1 edge and measures 3.15:1.
- **The attacked-device ring was drawn in two different reds.** Its pulse had been tokenised while
  `border: 3px solid #EF4444` had not, so one indicator disagreed with itself. As a non-text indicator it
  owes 3:1 (WCAG 1.4.11); `--danger-fill` measures 4.41:1 on the light canvas against the literal's 3.44:1.
- **Two filled buttons became unreadable when disabled.** `opacity` multiplies the whole control, so a white
  label on a role fill moves toward the fill as the fill moves toward the page: the template-reset primary
  measured **5.02:1 → 2.49:1** at `opacity: 0.6`, and the protected-action confirm **4.83:1 → 2.51:1** at
  `0.55`. A disabled control is exempt from AA, but a user still has to read which button they cannot press —
  and on a confirmation, which one they are being asked about. Both now desaturate the fill toward a neutral,
  keeping the ink above 5:1 while plainly reading as drained, the treatment `AccountDeleteDialog` already used
  after a faded-but-saturated danger button had read as *armed*. `disabledFillLegibility.spec.ts` scopes the
  rule to role-filled controls: loading states (`cursor: wait`), label-less controls, and panels that already
  desaturate via `filter` keep their fade, because opacity is the right answer there.
- **A chat style referenced a token that does not exist.** `ChatMarkdown`'s image-alt colour read
  `var(--chat-text-muted, …)`; the token `ChatView` declares is `--chat-muted`. The typo was invisible
  because the fallback resolved to the same colour by coincidence, and would have surfaced the first time the
  chat panel's muted tone diverged from the page's. `tokenReferenceIntegrity.spec.ts` now requires every
  `var(--x)` to name a token that is either declared or provably injected at runtime.
- **A destructive action's red halo now stays red in dark theme.** Fourteen coloured shadows carried raw
  light-theme hues (`rgba(239, 68, 68, …)`, `rgba(220, 38, 38, …)`, `rgba(37, 99, 235, …)`) while the
  `background` one line above them already read `var(--danger-fill)` / `var(--accent-fill)` — the fill
  followed the theme and its own glow did not, on the logout confirmation, the account-deletion button, the
  attacked-device pulse, the compromised/active/focused edge glows and the chat error banner. A red halo is
  how the interface says "this destroys something", so it has to survive a theme switch. All of them now
  derive from the token the element is painted with. `shadowRoleOwnership.spec.ts` covers `box-shadow`,
  `text-shadow` and `filter: drop-shadow()` — the last two were the gap that let five edge glows through a
  box-shadow-only sweep.
- **The landing hero's auth panel no longer half-collapses at fractional viewport widths.** `max-width:
  1100px` paired with `min-width: 1101px` left 1100.5px — routine on a scaled display — matching neither
  rule, so `.hero-section--with-auth` kept the ~418px corridor reserved for an absolutely positioned panel
  while the panel itself fell back into the flow and stacked under the title. `.hero-title` had the same
  shape at 767/768px. Both now split at one value, and `breakpointComplement.spec.ts` checks every
  complementary pair on both axes.

#### Changed

- **Corner radius is now a containment scale instead of 29 hand-written values.** The radius in this product
  encodes depth — a marker inside a button, a button inside a well, a well inside a card, a card inside a
  floating panel, a panel inside a modal surface — so a user reading the corner can tell which level they are
  looking at. That only holds while one role has one radius, and it did not: `0.6rem`, `0.625rem`, `0.65rem`
  and `10px` were all "a well", and `.el-message-box` shared a single token with the buttons *inside* it,
  three levels apart. Eight role-named steps (`--iot-radius-marker` … `--iot-radius-pill`) replace 115
  declarations; the largest visible shift is 2px, on the auth panel, toward the step its neighbours already
  used. `radiusScale.spec.ts` keeps literals out.

#### Removed

- Four dead global utility classes (`.text-primary`, `.text-secondary`, `.bg-online`, `.bg-offline`) defined
  13 times across three files with no template consuming any of them. `board.css` loads after Tailwind, so
  its `.text-primary { color: #2563EB }` silently beat the generated theme-aware `var(--iot-color-accent)` —
  both rules shipped and the palette-driven one lost. A note claiming the four were "still in use" had read
  those files' own redefinitions as usage.
- 47 CSS custom properties in `base.css` that were defined (colour roles twice, once per theme) and consumed
  nowhere, plus the orphaned `tsconfig.app.json` and three unused Tailwind theme entries.
- Twelve more dead global classes and an orphaned `attackedPulse` keyframes block, found by sweeping all 250
  classes in the global stylesheets rather than by eye. Two were broken rather than merely unused:
  `.animate-spin-slow` animated a `spin` keyframes that file never defines, and `.data-badge` hardcoded
  `background: white`. `.trace-change-badge` and `.device-runtime-chip__previous` **stay** — they render
  nowhere on purpose (a 64px node cannot fit `24 → 26` without truncating to a fragment), and three comments
  plus a spec cite them as the evidence for that decision, so deleting them would leave the record pointing
  at nothing.
- The unused `.board-dropzone`, whose note argued for an accent-tinted drop target. Both real drop targets —
  the import panel and the canvas overlay — are dashed `--warning-border`, so this was a proposal the product
  did not adopt rather than a half-finished migration.
- 120 unreachable `var()` fallbacks, including three-deep chains like
  `var(--board-panel-bg, var(--surface-panel, #ffffff))`. Every token in them is declared at `:root` in all
  three theme blocks, so no fallback could ever fire; what they did instead was put a light-theme hex in front
  of a reader trying to learn what a dark panel is painted with. The fallbacks on runtime-injected tokens
  (`--node-accent-color`, `--canvas-zoom`, `--resize-hit-size`, …) are the real default and were kept.

### 2026-08-03

#### Added

- **Every semantic role now has a fill half as well as a text half** (`--accent-fill`, `--danger-fill`,
  `--warning-fill`, `--success-fill`, `--info-fill`, plus `--accent-fill-hover` and
  `--accent-fill-disabled`). One token cannot be both the ink and the paper: each bare role is tuned to be
  legible **as text on the page ground**, so the dark theme lightens it — and used as a **fill under white
  ink** that inverts. Measured in dark theme: `--accent` **2.54:1**, `--warning` **1.44**, `--danger`
  **1.90**, `--success` **1.52**, and `--accent-strong` **1.80** — the last of which is the *hover*, so
  contrast fell as the user interacted with the control. Light theme passed throughout, because one dark blue
  happens to serve both jobs there, which is why this went unnoticed. The base fill tokens are identical in every theme by
  construction — a ground that does not flip means one ink is correct everywhere — and all of them, hover and
  disabled states included, are solved for white ink at >= 4.5:1. The hover is the one value that must differ
  by theme, because "darker" and "brighter" swap meaning with the ground.

#### Changed

- **The action dock now distinguishes a formal proof from candidate evidence from a view.** Four run-group
  controls rendered byte-identical — same fill, weight, 124x44 box, radius and shadow — while returning
  fundamentally different things. Verification (a NuSMV proof or counterexample) keeps the filled primary
  treatment; Simulation (one concrete trace) and Explore (bounded candidate counterexamples) move to a tinted
  bordered tier; Run History, which writes nothing, becomes a neutral view control. Colour still marks the
  family, so no new hue was introduced. Each tooltip gained a second line naming what the run *returns*,
  because a visual tier can express importance but not outcome — an independent review of the retiered dock
  ranked the four correctly and still could not tell which one produces a proof. Rationale and the measured
  before/after in [docs/guides/frontend-ui-conventions.md](docs/guides/frontend-ui-conventions.md) §4.
- **The two demoted dock tiers now have a visible edge.** Their surfaces sit 1.10-1.22:1 against the panel
  behind the dock, so the border alone is the control boundary — and it measured **1.48:1** against the
  3:1 WCAG minimum, meaning the edge of the control was effectively invisible. Two independent design reviews
  had both called the quietest control "too quiet"; measuring turned that from taste into a value. Lowering a
  control's emphasis must not stop it reading as a control.

#### Fixed
- **`npm run test:unit` no longer crashes on a high-core machine.** Vitest defaults to one worker per logical
  core, and each worker builds a full jsdom environment — the dominant cost in this suite. On a 28-core / 16 GB
  machine that meant 28 concurrent environments and a hard `heap out of memory` crash rather than a slow run.
  Capped at 6 workers in `vitest.config.ts`. Runtime is unchanged (~50s) because the suite was never CPU-bound,
  and the crash was V8 failing to commit pages from the OS, so raising `--max-old-space-size` made it worse.
  Three separate sessions had each worked around this with a different ad-hoc flag, meaning the documented
  command did not work for anyone who had not already learned the trick.
- **`vite.config.ts` reads `E2E_API_BASE_URL` for its `/api` proxy target.** Both `server` and `preview`
  hardcoded `localhost:8080`, while the E2E specs already honoured that variable — so pointing a run at a
  different backend silently half-worked: the direct API calls moved and the browser did not, leaving one run
  talking to two servers. This is what made a full E2E pass impossible, since the suite needs ~67 registrations
  against a default 60/hour rate limit and the remedy is a second backend with raised caps.

- **Canvas node text no longer renders below the readable minimum.** Three declarations sized node text
  as `clamp(<sub-floor>, N cqmin, <large ceiling>)`, but `cqmin` is a percentage of the node's own box
  and a node is 110–137px, so the preferred term evaluated to 4.7–6.9px and could never beat the floor.
  The floor was therefore the rendered size — a flat 9.28px on the runtime and provenance chips and 10px
  on the state value, identical at desktop, tablet and mobile; reaching 11px would have needed roughly
  215cqmin. All three now use `--iot-font-min`. The two node labels whose `cqmin` terms genuinely do win
  on a resized node are unchanged, as is the `--canvas-zoom` counter-scaling label (measured 11px at
  1.0× and 14.4px at 0.4×). The typography-floor check had exempted container-relative sizes on the
  strength of an unverified comment claiming these rendered at 16px; that exemption is removed.
- **A device node's provenance pill states its conclusion instead of a fragment of one.** The pill is
  54px wide inside a 187px node, so "Shown sources trusted" was ellipsized to a few characters at any
  font size. The pill now prints the category ("Trusted" / "Untrusted" / "Private"), while its `title`
  and an `sr-only` span carry the full statement — the same split the node already uses for "no state
  machine". Its width cap changed from `46cqmin` (63px, which still cut 28% off the short label) to
  `min(100%, 7rem)`; measured unclipped in both locales at all three viewports.
- **Every filled action now carries readable text in both themes.** 60 fills across 14 files moved to the new
  fill tokens: 28 Tailwind accent fills, 18 role fills, 10 declared in CSS (the logout and account-delete
  confirmations, the template-reset dialog, the error boundary, the public header, an Element Plus danger
  button), and the six panel banners. The banners were the clearest case: they used `--accent`, which flips
  lightness between themes, so black ink measured 4.06 in light and 8.26 in dark while white measured the
  reverse — no single markup could be correct, and one panel title rendered at **1.44:1**. Pinning the ground
  so it cannot flip made one ink right in both themes, and their 16 `text-black` declarations became white.
  Two buttons that hovered white text onto a pale tint were fixed at the same time.
- **A disabled control is no longer the least readable text on screen.** Fading a control with opacity fades
  its label too: a filled primary button measured **2.42:1** while disabled, the exploration panel's
  "Select all" **2.00**, and undo/redo — disabled on every fresh board, so the first state of those controls
  anyone sees — **1.80**. Filled buttons now desaturate to a neutral fill (`board-action-disarmed`) and keep
  their ink opaque; muted text simply stays muted. The other 69 opacity-faded controls were **measured, not
  changed**: every one still clears the floor across 10 surface/theme combinations, so opacity is only a
  problem where it multiplies a value already sitting near the minimum.
- **All 24 controls below the 44px touch target were raised** (WCAG 2.2 SC 2.5.8): the six panel close buttons
  at 32px — now one shared `board-panel-close` class giving a 24px glyph a 44px target through transparent
  padding, so dismissal does not become the largest thing in the header — eleven rule-builder inputs, selects
  and footer buttons at 40–42px, five recommendation-panel fields at 30–31px, a 34px device-name input, and
  three generate buttons at 40px. The earlier "0 violations" measurement was accurate but taken on the board
  shell, which never opens these panels.

- **A progress spinner keeps turning under `prefers-reduced-motion: reduce`.** It shared an
  `animation: none !important` list with the decorative halos, so reducing motion froze every spinner
  mid-rotation — and a stopped spinner reads as **stalled**, not as "motion is disabled". NuSMV runs take real
  seconds, so during a verification the spinner is the only signal that the run is still alive. It is now
  slowed and stepped (`1s linear` becomes `2.4s steps(8)`, still infinite) rather than stopped; a 16px glyph
  turning slowly is not the fast, large-area motion the preference exists to suppress. Decoration still stops.
- **Light-theme body text no longer uses a 400-step neutral.** `text-slate-400` is 2.56:1 on white, and it
  was set on 126 text elements across 9 files; they now use the 500 step, while `dark:text-slate-400` is kept
  where the same value measures 5.71 on a dark card. Decorative icon glyphs beside their own explanatory text
  were marked `aria-hidden` instead of recoloured, since a faint illustration is intended to be faint.
  This was invisible for the whole audit because of a **measurement** bug rather than a reasoning one: the
  browser probe could not parse `oklch()`, which is what Tailwind v4 emits, so every element using a modern
  colour was counted "unmeasurable" while its surface still reported clean.

#### Removed

- **The panel-scoped hue remaps and five Tailwind utility overrides are gone with their callers**
  (`styles/board.css`, 1,298 bytes). The 42 remap selectors rewrote raw palette utilities to
  token-derived colours by class name, which is what kept the dark theme readable while components still
  shipped light-theme hues; every component has since moved to the `board-*` role classes, so all of them
  matched nothing. The five utility redefinitions (`.bg-green-200`, `.border-blue-100`,
  `.border-green-200`, `.border-purple-100`, `.border-purple-200`) were worse than unused: each pinned a
  light-theme hex and was **unscoped**, so any future use of those Tailwind class names anywhere in the
  app would have silently received a hardcoded colour instead of the palette value. Confirmed 0 consumers
  across every `.vue` file with no dynamic class construction before removal; `.text-primary`,
  `.text-secondary`, `.bg-online` and `.bg-offline` share the block, are still in use, and stayed.

### 2026-08-02

#### Changed
- **The automatic-fix dialog's primary action no longer sits behind a scroll.** `Try This Strategy`
  and `Apply This Fix` were the last elements of the dialog's scrolling body, and the strategy detail
  is routinely taller than the fold: at a 900px-tall viewport the body showed 601px of 912px and the
  action ended 19px past the visible edge with the body never scrolled. Two independent reviews read
  the half-drawn control as broken and blamed the footer, but the footer is a static flex sibling that
  overlays nothing — the action was scrolled past, not covered. Both actions now live in the
  non-scrolling footer on the convention the rule builder already sets (dismiss left, one primary
  right); they are mutually exclusive, so exactly one shows. A single `applyBlockedReason` owns the
  disabled state and the inline explanation it is linked to by `aria-describedby`.
- **One scroll-region primitive replaces five disagreeing scrollbars.** Control Center, the device
  dialog, and the inspector each declared `.custom-scrollbar` in their own `<style scoped>` with
  different widths and thumb colours — and `<style scoped>` does not scope a `::-webkit-scrollbar`
  pseudo-element away from its class name, so all three matched every such element app-wide and the
  winner depended on stylesheet order rather than on which component rendered it. The rule builder
  keyed a scrollbar skin to the Tailwind utility `.space-y-2`, so unrelated layout markup silently
  carried it. `.iot-scroll-region` now owns all of them from the semantic token layer, and adds the
  standard `scrollbar-width`/`scrollbar-color` the webkit-only rules had left Firefox without.

#### Removed
- **`verificationRechecked` is gone from the fix-apply response** (`FixApplyResultDto`, the
  `apply_fix` AI tool payload, and `types/fix.ts`). Apply never repeats the strategy search, so the
  field was hardcoded `false` and already documented as "always false", yet the client carried a
  whole `=== true` branch: two success messages, four translations, and a validator clause that
  accepted a shape the backend cannot send. `verificationEvidenceReused` is now the single evidence
  basis, and the client rejects an apply response that does not confirm it. Docs updated in
  [docs/api/verification.md](docs/api/verification.md) and
  [docs/architecture/auto-fix.md](docs/architecture/auto-fix.md).

#### Fixed
- **A pinned parameter range is no longer discarded in silence.** Both paths that return before any
  strategy runs — no localized fault rule, and an incomplete source model — ignored the caller's
  preferred ranges while reporting `unusedPreferredRangeSelections` as empty, so a user who locked a
  threshold to an exact value got no indication it was never applied. That is precisely the
  silent-honour failure the field exists to prevent. All three paths now share one projection.
- **An unattempted template comparison no longer asks the user to wait for one.** A fix result whose
  source model was incomplete keeps `templateSnapshotComparison` at its `NOT_CHECKED` default, because
  the comparison is skipped entirely on that path. The dialog rendered it with the `UNAVAILABLE` copy
  — "cannot confirm whether device templates still match … until comparison succeeds" — advising a
  retry that can never succeed, alongside the message already naming the real blocker. `NOT_CHECKED`
  is now silent; `UNAVAILABLE`, where a comparison was genuinely attempted and failed, still reports.
- **Unsupported fix strategy names are rejected, not skipped** — corrected in
  [docs/architecture/auto-fix.md](docs/architecture/auto-fix.md), which described a `SKIPPED_UNSUPPORTED`
  path that three separate validation layers make unreachable.
- **A task that did not finish no longer reports 100% progress.** Across verification, simulation, and
  counterexample exploration, nine of twelve terminal-transition queries hardcoded `progress = 100` —
  including every cancellation and every worker-reported failure, not just the expired-lease sweep. A
  run cancelled at 30% was observed publishing 100%, and an abandoned exploration task was observed
  reporting `status: FAILED, progress: 100`. Only the three `completeTaskIfRunning` paths, which do
  finish the work, still write 100; every other terminal path preserves the last progress the run
  actually reported, and `status` carries the outcome. The four read-path mappers were narrowed in the
  same change: they required 100 of every terminal status, so a sub-100 cancelled row made both
  `GET /api/{verify,fuzz,simulate}/tasks/{id}` **and the whole task list** answer HTTP 500
  (`PERSISTED_SEMANTIC_DATA_INVALID`). Only `COMPLETED` is now held to 100 on read. Affects every
  consumer of the task DTOs, including the `/api/*/tasks` endpoints and the AI tools. The client
  validator was aligned in the same change: it rejected any terminal task whose progress was not 100,
  which would have turned a truthful failure response into a dropped inbox row. It now requires 100
  only for `COMPLETED`, matching what `docs/api/fuzzing.md` and two other passages in
  `docs/api/verification.md` already specified.

### 2026-08-01

#### Added
- **CI now validates documentation cross-references.** A documentation-only push routes as inert and
  skips every test tier, so nothing in CI read those files: a link could rot and reach `main`
  unchecked. Because the docs deliberately link between pages instead of repeating facts, a broken
  link hides the authoritative source a reader was sent to find. The check runs in the one job that
  executes for every push, resolves relative paths and `#fragment` anchors, ignores links inside
  fenced examples, and does not fetch external URLs — a network failure must not fail an unrelated
  commit. It reports the document, the link, the cause, and the correction. Local equivalent in
  [docs/development/ci.md](docs/development/ci.md#local-equivalents).
- **A stored run now records why each shared value was allowed to move.** A counterexample was only
  explainable by reading the current Board, which may have changed since the run — so the explanation
  could contradict the trace it claimed to explain. `modelSnapshot.environmentProvenance` now carries,
  per shared value, its type, domain, declared writers and readers, whether evolution is exogenous,
  device-controlled or composed, and whether that rule is exact or a disclosed abstraction. It is
  captured at the model boundary before generation and persisted with the run, so editing the Board
  afterwards cannot change a stored run's explanation. The simulation timeline annotates a changed
  value with its cause. New API field, documented in
  [docs/api/verification.md](docs/api/verification.md#environmentvalueprovenancedto); the semantics it
  reports are owned by
  [shared-value-semantics.md](docs/architecture/shared-value-semantics.md) §10.

#### Fixed
- **The most common shared value showed a change with no cause.** A numeric value no device writes —
  what a single temperature sensor produces — matched none of the timeline's provenance branches, so
  its trace step read `20 -> 21` with nothing saying why. That is the one thing provenance exists to
  prevent: the user cannot tell whether their own rule moved it or the model did. It now cites the
  declared interval, and `SimulationTimeline.spec.ts` covers all four authorship/semantics
  combinations so an unhandled one fails rather than rendering silently.
- **Provenance described composed discrete values as an order-dependent race.** Both the DTO javadoc
  and the user-visible summary said "last active effect wins" — the device-iteration-order behaviour
  removed in `eceaf2d`, which the product now guarantees against (invariant 10). Conflicting discrete
  writers are rejected at board assembly, so writers that reach a run agree and there is no winner to
  pick; the wording claimed a verdict the user can trust is arbitrary. Both now state the actual rule,
  and `EnvironmentProvenanceCollectorTest` fails on the old phrasing.
- **`ModelRunSnapshot` and `ModelTokenSource` were each declared twice in the frontend.** The
  duplicates were unreferenced — every consumer imports `ModelRunSnapshot` from `types/modelSemantics`
  and `ModelTokenSource` from `types/modelToken` — so the second copies could drift from the contract
  without breaking a build. Removed; `types/model.ts` now imports the canonical token source.

### 2026-08-01 (later)

#### Fixed
- **Read capability was not actually enforced where templates are created.** The template endpoint
  accepts a raw `JsonNode` and builds the DTO with `treeToValue`, so bean validation never ran on it —
  the `@AssertTrue` guard added when `Reads` became mandatory was dead code on the one path the REST
  client and the `add_template` AI tool both use. A live call proved it: a manifest omitting `Reads`
  was accepted with `200`, silently gaining read capability from a missing field, which is exactly the
  implicit-capability problem that removing `EnvironmentDomains` was meant to end. The JSON schema now
  requires `Reads` on every shared declaration and rejects it on a device-local one, and the NuSMV
  validator restates the rule in language a template author can act on rather than as a schema path.
- **The AI tool prompt told the model to omit `Reads`.** It said "omit Reads (or set true)", so a model
  following it produced templates that are now correctly rejected. It states the requirement and both
  values explicitly.
- **The Environment Pool panel described the opposite of what the verifier does.** A discrete value was
  labelled "may change nondeterministically when no device effect applies" — the behaviour a
  device-written value specifically does *not* have, since it holds. The label now branches on
  authorship exactly as the generator does, names the exogenous case as a deliberate abstraction, and
  names the device-written case as exact.
- **An affect-only declaration was shown as "Reads this environment variable".** The inspector derived
  the role from `IsInside` alone and ignored `Reads`, mislabelling 7 declarations across 5 bundled
  templates as reads when the generator emits no read mirror for them.
- **Five error messages told template authors to add a field that no longer exists.** Removing
  `EnvironmentDomains` left behind messages instructing the author to "Add `EnvironmentDomains[].Name=`"
  to resolve a rejection — advice the JSON schema now refuses, so following it produced a second error
  with no path forward. They now name the `InternalVariables` entry to declare, with `IsInside=false`
  and an explicit `Reads`. Three dead reads of the deleted array were removed along with one
  unreachable validation loop, and a test now fails on any production line that looks up the removed
  key or names it in a message, so this class of residue cannot return quietly.
- **An affect-only shared value could be used as a rule or specification condition source on every
  path except the one that was fixed first.** A rule whose condition reads a value the device never
  observes compiles to a condition with no model behind it, which a user cannot tell apart from a
  working rule. Hiding it in the rule builder only closed the click-through route: persist-time
  validation, the verification request validator, and the assistant's own pre-flight validator each
  still accepted it, and the specification builder offered it alongside the rule builder that had just
  stopped doing so — so the same intention produced a different model depending on who expressed it
  and by which route. All five writer boundaries now apply one rule and name the variable when they
  refuse. Two things deliberately did *not* change: the capability-blind existence lookup beside each
  gate, whose callers ask whether a declaration exists at all (domain resolution, runtime overrides,
  contradiction detection), and a specification's reference to an affect-only value's **trust or
  privacy label** — the model declares one for every variable, so asking about a label is not reading
  the value, and refusing it would make admission stricter than what NuSMV would decide.

- **Two devices that disagree about a shared on/off or category value are now refused instead of
  silently resolved.** Enum values have no additive composition, so the generator emitted one `case`
  branch per writer and NuSMV took whichever came first — meaning the verdict depended on device
  iteration order. The same scene proved `AX (airQuality = good)` *true* under one order and *false*
  under the other. A verdict nobody can predict is not actionable, so such a scene is rejected naming
  both devices and both assignments; writers that agree are unaffected, and numeric writers still sum
  as MEDIC §3.1 defines.

#### Changed
- **One declaration per shared value, with read capability mandatory.** `EnvironmentDomains` is gone,
  not deprecated: it only ever existed because read capability was implied by *which array* a
  declaration lived in. `InternalVariables` now requires an explicit `Reads` on every shared
  declaration (and rejects it on a device-local one), for the same reason `IsInside` and
  `FalsifiableWhenCompromised` are required — a capability must never come from a missing field. The
  earlier `Reads` default of `true` was itself a silent grant and has been removed. The read/affect
  truth table is now fully representable in one place, and the new
  [shared-value-semantics.md](docs/architecture/shared-value-semantics.md) is the single authority
  for it, tagging every rule as paper-defined, a product extension, exact, a disclosed abstraction,
  or rejected.
- The AI tool catalog size is no longer hand-maintained in six documents: a test derives it from the
  code and names any document that disagrees.

- **One declaration per shared value, with read capability stated instead of implied.** A device that
  only *affects* a shared value used to repeat its whole domain in a second array
  (`EnvironmentDomains`) whose only job was to withhold rule/specification source capability — so a
  single boolean was expressed by choosing between two array shapes, and three arrays encoded a 2x2
  truth table. `InternalVariables` now carries an explicit `Reads` flag (default true), so
  `IsInside=false, Reads=false` says "affects but does not read" in the same place as everything
  else. All five bundled templates that used the second array were migrated and it is now unused;
  it remains parsed as a deprecated spelling for hand-authored manifests. Capability now keys off the
  declaration rather than off which array it happened to live in, which also fixed a latent bug: the
  read-mirror emitter had ignored the distinction entirely.

#### Fixed
- **A success toast no longer swallows clicks on the controls it covers.** Toasts sit above the
  whole workspace and stayed pointer-interactive, so the scene-import confirmation overlaid the undo
  button and absorbed a click aimed at it — undo simply did nothing until the message auto-dismissed.
  A toast is an announcement, not a control, so it no longer receives pointer events while its own
  children (the close button) still do.
- **A shared on/off or category value that a device controls no longer changes on its own.** Such a
  value could previously take any value in its declared domain in any step no declared effect covered,
  so with a humidifier switched off across a step NuSMV still let `airQuality` become `good` — and
  refuted the natural property "air quality only improves while the humidifier runs" with a trace
  containing no cause. Evolution now follows authorship: a value no submitted device declares it
  writes stays a free exogenous input (weather, a clock, an occupant), while one a device declares it
  writes follows that device's declared `Dynamics` and otherwise holds, matching the policy already
  used for device-local variables. `modelSemantics.environmentEvolutionEffects` reports the two rules
  separately instead of claiming all discrete values are nondeterministic. Every bundled template is
  unaffected: all six shared enum values they declare (weather, season, date, motion, smoke, soil
  moisture) are genuinely exogenous.

#### Changed
- **A declared `NaturalChangeRate` interval now means exactly itself, so "this always changes" is
  finally expressible.** The interval excluding zero (`[-4, -2]`, "this tank always drains 2-4 per
  step") is a *mandatory* per-step change; including zero (`[-4, 0]`) means the value may also hold.
  Previously zero was injected into every interval, which silently rewrote the first meaning into the
  second — the only declarations whose meaning depends on zero being absent. NuSMV showed the cost:
  for that tank it reported `AF (level = 0)` false and `EG (level = 10)` true, offering a trace in
  which the mandatory drain never happened. That is a pseudo-counterexample; a user cannot act on
  behaviour their own declaration forbids. Both verdicts invert now. MEDIC §3.1 Fig. 2b never re-adds
  a stutter either, because zero is already inside `[-1, 1]` — which is also why no bundled template
  changes behaviour: every one declares `[-1, 1]`, `[0, 1]`, or `0`.

- **A verification or simulation run now always describes the board you saved.** The request carries
  run parameters only (`attackScenario`, `enablePrivacy`, and simulation's `steps`); the server reads
  devices, rules, specifications, the environment pool, and the canvas layout from your own persisted
  board. Sending a scene is refused with an explanatory `400` rather than silently ignored. Previously
  the client supplied the model and the server only checked it for internal consistency, so an account
  whose board held no devices could post a fabricated two-device scene and have the resulting
  `VIOLATED` verdict persisted into its own run history — where the UI presents a run as "this saved
  scene was checked". Counterexample exploration and the AI assistant already read the board this way,
  so verification and simulation now follow one rule instead of two, and the board read happens before
  the run snapshot is frozen so persisted evidence copies the scene instead of aliasing live objects.

#### Fixed
- **A device now moves a shared environment value in the same step it is acting.** Its per-variable
  impact rate was a state variable, so the environment transition read the *previous* step's rate:
  switching an air conditioner on took two steps to change the temperature, and a device that started
  in a cooling mode never applied its effect on the first transition at all. MEDIC §3.1, Fig. 2b
  combines the device effect with the environment step contemporaneously, so the rate is now a
  definition over the device's current state. NuSMV confirms the difference — with the old encoding
  `AG (a_temperature = 30 -> AX a_temperature <= 27)` was false for an AC initialised to `cool`, and
  it is true now. The bounded explorer derives the effect from the same live state, so counterexamples
  and findings agree. The model is also slightly smaller, since a definition adds no state variable.
- **A declared `NaturalChangeRate` interval is now modeled in full, so verification can no longer
  report a scene safe on the strength of steps it never explored.** The declaration constrains
  `v' - v` (MEDIC §3.1, Fig. 2b), but the generator emitted only the lower, zero, and upper deltas.
  For `[-1, 1]` those *are* the whole interval, which is why every bundled template hid the problem;
  for anything wider the interior was missing, and NuSMV would prove a variable declared `[-3, 3]`
  could not move by 1 in a step. Both the formal generator and the bounded explorer now admit every
  integer the interval permits, combined with active device effects and clamped to the declared
  domain, so a wider interval is a genuinely weaker assumption rather than a different one.
  A step may always apply no drift, so an interval that excludes zero still lets a value hold still.
  Because the span is therefore a state-space cost, an unmodelably wide interval is rejected at
  template authoring and at generation instead of being silently narrowed. The redundant
  at-boundary branches are gone: clamping already pins both ends of the domain.

### 2026-08-01

#### Fixed
- **Parallel assistant work now remains coherent across tabs, panel lifecycle, and logout.** Before
  the lazy panel mounts, the application shell alone owns session observation; afterwards the chat
  view alone polls the full list while visible or hidden, including a low-frequency idle poll that
  discovers work newly started in another tab. Logout refreshes and settles every authoritative
  active conversation even when this tab never opened the panel, while the backend records an
  account-wide explicit chat stop before revoking the token. The running and unread indicators are
  shown independently, active rows cannot offer a doomed Delete action, and reconciliation now has
  one shared state instead of a local shadow that could remain stale.
- **Background assistant completion now reconciles tabs that never mounted the chat panel.** The
  session projection now includes the latest terminal-message id. The application retains that id
  plus per-session activity while the panel is closed, so it also detects work that starts and
  finishes between two polls, then reloads the authoritative Board plus run history even when the
  original SSE tab was closed. A failed reload remains visibly marked and keeps destructive scene
  operations and playback guarded until the shared retry path succeeds.
- **Independent accounts no longer deadlock while recording their first reversible Board edits.**
  The journal now checks for an abandoned redo branch before issuing its bulk delete, avoiding the
  empty-range MySQL next-key lock that made concurrent first edits contend on the unique-index end
  gap while preserving redo invalidation after an actual undo.
- **The assistant now describes condition fixes in both supported directions.** The
  `fix_violation` tool definition states that Salus-style condition adjustment may add or remove
  triggering conditions, matching the strategy implementation and its existing API contract.
- **NuSMV diagnostic output no longer claims to be an unabridged raw stream.** Result dialogs,
  API documentation, and the retained-output marker now disclose the 10,000-character
  storage/display cap while keeping clear that formal results and traces are parsed before it.

### 2026-07-31

#### Changed
- **The default assistant model is now GPT-5.6 Luna.** The OpenAI-compatible model id is
  `gpt-5.6-luna`; an explicit `IOT_VERIFY_OPENAI_MODEL` deployment override still takes
  precedence.
- **The assistant receives more conversation context without a narrower planning policy.**
  Its coherent history window is now configurable with `CHAT_HISTORY_CHAR_LIMIT` and defaults
  to 32,000 characters. Tool-backed model summaries are preserved when the current turn has
  relevant authoritative evidence; unsupported read or mutation claims are still hidden.
- **AI task and history capabilities now close across conversations.** The assistant can
  discover all verification, simulation, and counterexample-search tasks, list and inspect
  completed formal-verification runs including satisfied runs with no trace, use the Board's
  authoritative undo/redo journal, preview/confirm clearing an unusable undo/redo journal without
  changing Board data, and preview/confirm an atomic full-Board clear.
- **Chat sessions now run independently.** Switching to a new conversation detaches only the
  browser's SSE transport, so a long-running scene generation or replacement continues under its
  original session while another conversation can be used. Active rows remain visible, background
  completion reconciles the Board and notifies the user, and explicit Stop remains the only normal
  cancellation path. Closing a tab or browser also leaves accepted work running. Completed results
  retain a server-persisted unread marker and terminal-status label across browser restarts, with an
  unread count on the assistant entry button; still-running sessions are also restored into the
  entry indicator and shared Board guard after reload or in another tab. A result is acknowledged
  only after its exact terminal message is rendered. A failed new-conversation creation leaves the
  original live stream attached.
- **Assistant conversation concurrency is configurable and defaults to four sessions per user.**
  The admission response reports the actual limit, and the UI names it precisely while preserving
  each already-running conversation. Redis coordinates the limit across instances and the documented
  fail-open fallback enforces it per process.

#### Fixed
- **Concurrent chat navigation now preserves the user's latest visible state.** A stale active-row
  poll can no longer erase, duplicate, or resurrect conversation rows, foreground refresh no longer
  races background polling, and a delayed New Chat response no longer steals focus after the user
  explicitly selects another conversation. Foreground refresh now also reconciles the Board and
  notifies the user when it is the first observer of a background completion.
- **Assistant work is now visible through one authoritative result and receipt path.** Every
  chat-dispatched success or no-write preview carries an explicit execution status, and confirmed
  actions reuse one operation-aware summary for progress plus the post-refresh UI receipt. Rule
  reorder and rule/specification deletion previews are no longer mislabeled as completed deletes.
  The shared protected-action confirmation is no longer mislabeled as a deletion when it represents
  formal-fix application or Board/history clear.
  Rule recommendations show model-authored rationale, and an explicitly selected category is
  enforced by backend validation instead of relying on the prompt alone.
- **Mutation tool results now require tool-specific completion evidence.** A message-only or
  malformed result is surfaced as `RESULT_UNAVAILABLE` with conservative mutation uncertainty;
  the assistant refreshes authoritative state and stops dependent planning until the user can
  see whether the requested change actually committed. Successful list, availability, preview,
  unchanged, and unaccepted-cancellation results no longer count as evidence for a model claim
  that the assistant changed platform state.
- **Formal verification and bounded exploration now expose MEDIC numeric-environment semantics.**
  Every shared numeric value must explicitly declare `NaturalChangeRate`: `[-1, 1]` exactly models
  MEDIC §3.1's per-step physical disturbance, `0` explicitly disables independent natural change,
  and other intervals are visible lower/zero/upper endpoint extensions. Formal and bounded paths combine the
  declared interval with every active device effect and clamp to the domain, without adding a second
  hidden disturbance. The Environment Pool now separates model initial value from subsequent
  evolution, shows device effects, detects case/domain/rate/default-label conflicts, and disables
  ambiguous edits; the backend rejects the same conflicts on every write path. The live canvas now
  displays effective template-default labels as well as instance overrides. Fuzz traces no longer
  expose template trust values as evidence, and clients reject non-empty fuzz label evidence because
  bounded exploration does not model MEDIC trust/privacy propagation. Privacy wording states
  throughout that public/private is a sensitivity-propagation
  label and never changes platform authorization, encryption, or transmission behavior.
- **Template evolution validation and local-variable execution now use one rate contract.**
  Frontend template preflight rejects malformed or descending natural-change rates, reversed
  numeric bounds, and rates attached to enum domains before upload, matching backend admission.
  Backend admission, formal generation, bounded exploration, and shared-domain comparison now use
  one canonical parser, so malformed persisted or internal values cannot be reinterpreted differently.
  Formal and bounded execution now both retain the lower endpoint for a same-direction local
  rate such as `[2,3]`, alongside stutter and the upper endpoint.
- **Model controls now name their semantics directly.** Public/private choices are presented as
  sensitivity labels, the privacy run control names sensitivity-label propagation, and the
  Environment Pool states the MEDIC `[-1,1]` baseline plus the single-combination/zero-rate rule.
  The verification control recognizes privacy-specification target types with the same normalized
  comparison as the backend, so its visible required/on state cannot disagree with execution.
- **Formal-run controls now explain the same prerequisites that execution enforces.** Verification
  and simulation show visible, accessibility-linked reasons for unavailable actions; device-local
  initial-value controls name the concrete template starting value; and Environment Pool CAS edits
  use the same effective template labels shown to the user.
- **Assistant task polling now closes on persisted results without leaking technical payloads.**
  Completed verification and simulation statuses expose the correct run/trace id and next tool,
  omit raw NuSMV output and execution logs, and synchronous verification announces a synchronized
  Run History only after persistence is confirmed. Top-level and nested task progress now repeat
  the same authoritative live value instead of exposing a stale persisted value beside it. Stale
  documentation now reports all 53 tools.
- **The floating AI assistant remains draggable and resizable after browser layout changes.**
  Dragging and resizing now share one interaction lifecycle, which is released when the viewport
  changes, the window or tab loses focus, pointer capture is lost, or the panel closes. Repeated
  browser resizing can therefore no longer leave the panel permanently stuck. The panel also
  keeps useful movement room at short desktop heights and exposes a larger resize target. The
  draggable playback-change popover now follows the same interruption cleanup instead of reusing
  a stale drag origin after a viewport change, and keeps its pointer gesture usable when browser
  pointer capture is unavailable.
- **Historical evidence now degrades per selected record.** Replaying a verification trace no
  longer loads every sibling trace in its run, so one damaged record cannot hide another valid
  counterexample. Counterexample-search finding replay now obtains the selected finding together
  with its validated frozen run snapshot and canvas, rather than requiring every sibling finding
  to decode. Both paths still reject ownership mismatches and malformed frozen evidence.
- **Deep-linked results now always describe the URL the user opened.** Switching from one result
  link to another removes the older surface before the new detail load starts, and each navigation
  owns its failure handling. A late response from an earlier link can therefore neither clear nor
  validate child evidence for the newer one. Confirmed missing, forbidden, or damaged finding
  targets now converge the URL and displayed state; temporary failures remain retryable.
- **Saved partial verification evidence is no longer hidden.** A verification run can be
  `INCONCLUSIVE` after a different property result is incomplete while still retaining a parsed,
  replayable counterexample. Result and history views now load and show that evidence with an
  explicit partial-evidence qualifier, without turning it into a final violation conclusion.
- **Damaged historical detail no longer leaves the Board in a retry loop.** When a detail
  endpoint explicitly reports `PERSISTED_SEMANTIC_DATA_INVALID`, the Board marks only the
  affected verification trace or run, simulation result, exploration run, or individually
  identified exploration finding unavailable and clears any deep link to it. Transport,
  authorization, and temporary service failures remain retryable, so valid evidence is never
  hidden merely because a request could not complete. A history-list refresh now retains that
  confirmed unavailable state for the active Board session. Historical
  links now retain transient failures for retry and reject a trace that is not part of its named
  verification run.

### 2026-07-30

#### Changed
- **The landing-page background video is now self-hosted.** The production UI loads the versioned
  MP4 asset from the application origin instead of depending on a third-party CDN at runtime, and
  preloads it for immediate playback when reduced-motion preferences permit video.

#### Fixed
- **Large verification and simulation histories no longer fail while loading summaries.** Result
  and task-inbox sorting now selects only summary-width columns instead of materializing complete
  state arrays, frozen requests, or detail-only diagnostics. Full trajectory and exploration
  finding collections retain their public order by sorting after owned rows are loaded, so their
  large JSON payloads also never enter a database filesort. Full trajectory and rule-evidence
  integrity checks remain authoritative when a result is opened for replay or repair. Repeated
  identical error notifications are grouped instead of stacking over the Board while independent
  refreshes fail together.
- **AI execution progress now stays readable and recoverable throughout a turn.** The live
  reasoning/tool card uses the same full conversation width as its persisted completion state
  instead of collapsing to a narrow strip. Read-only model playback no longer disables the
  assistant composer; sending a new turn closes playback first, while an in-progress atomic scene
  replacement remains a hard interaction boundary.
- **Historical trajectory replay now preserves its own canvas evidence.** Verification, saved
  simulation, and exploration findings render the run-captured node layout and rule links instead
  of the current Board. A later Board or template change therefore makes a conclusion stale for
  the current model without hiding the original evidence or mixing it with current template
  presentation; stale verification replay still withholds repair actions.
- **Assistant actions are now visible without restricting what the assistant may do.** Confirmed
  Board mutations and model-run submissions show a localized receipt only after authoritative
  refresh, while previews, reads, rejected choices, no-ops, and unaccepted cancellations no longer
  claim a change. A final-response failure no longer repeats an already delivered action receipt.
  Verification, simulation, and counterexample-search history persists whether a
  run was initiated by the user or assistant and labels assistant/unknown provenance in the UI.
  Unexpected failures and structurally unusable returns from mutation-capable tools now remain
  visibly unconfirmed, stop dependent work, and request authoritative reconciliation; read-only
  failures remain independent. Error
  history also records when a pending refresh instruction could not be fully delivered instead of
  claiming that the client received it.
- **Long model traces no longer abort an otherwise healthy assistant turn.** Verification
  counterexamples and simulation traces now return bounded pageable state windows, matching the
  existing counterexample-search detail flow; all three expose the same counts and next offset and
  cap one request at ten states before the global tool-result size guard. A definitively read-only
  unavailable result also no longer skips independent calls from the same plan; possibly committed
  mutations still stop immediately. Transient synchronous simulation now returns only projected
  initial/final state previews and directs complete-sequence work to the saved pageable path instead
  of duplicating up to eleven full states and success logs. Formal verification conclusions and
  incomplete-model warnings remain unchanged.
- **Empty-Board template drops now work across the complete visible canvas.** During a template
  drag, the centered guidance and its buttons no longer intercept the drop target. Cancelling the
  subsequent device-name dialog restores those actions because no node has been created yet.
- **Successful full-scene imports no longer report an unconfirmed response when optional template
  fields round-trip as omitted.** Immediate response checks and authoritative reload checks now use
  one manifest canonicalizer that treats an explicit `null` object property as equivalent to the
  backend DTO omitting it, while preserving array order and all non-null value differences.
- **Destructive template and scene confirmations now cover undo/redo history.** Full-scene
  replacement, device-type deletion, and bundled-default reset previews report the exact edit-history
  count and bind their impact token to the complete journal. A concurrent edit, undo, or redo now
  makes an old confirmation return `409` without writing; immutable journal identity also prevents a
  clear-and-recreate cycle from reviving an old token. Successful template deletion/reset clears
  history atomically because retained device snapshots may depend on the removed manifest semantics;
  the UI states this impact before confirmation and refreshes availability only after the outcome is
  authoritative. If a scene write response is lost or rejected but reconciliation confirms the
  requested replacement, the same history, scene-generation, and recommendation boundary now runs.
  A stale confirmation refresh now invalidates verification and recommendations only when the
  semantic-scene fingerprint changed; history-only drift no longer makes a valid verdict look stale.
- **Undo/redo now matches the user's visible unit of work across the Board.** Device layout,
  runtime, and rename edits; direct Environment Pool edits; and automatic-fix rule-set replacement
  are each recorded as one reversible action. Semantic no-ops create no history, rename no longer
  rewrites unrelated nullable runtime fields, and malformed device-update entries cannot change a
  template or combine multiple edit kinds. Fix apply now returns authoritative undo availability
  instead of discarding earlier history. A conflicted, unusable journal can be cleared only through
  an explicit confirmation; that command leaves the current Board unchanged and disables both undo
  and redo. Its preview token binds the confirmation to the exact journal, so a newer edit/undo/redo
  from another tab makes the clear return `409` instead of deleting history the user did not review.
  Unconfirmed ordinary mutation responses now refresh journal availability together with Board data.
  Confirmed full-scene replacement/clear remains the deliberate history boundary because it can also
  replace template snapshots outside the four visible semantic collections.
- **Device creation and deletion now undo and redo as complete user actions.** Manual batch creation
  records one entry for the request instead of one entry per device, assistant-created devices use
  the same journal path, and deletion records the device plus every cascaded rule/specification at
  its original position. Both sides also snapshot the exact Environment Pool, so undo followed by
  redo is symmetric and refuses genuine newer edits instead of reporting success after a no-op.
  Operation-specific transition checks also reject malformed or no-op device journal entries without
  consuming them. Restored cascaded rules keep their original ids, including across the native-insert
  path.
- **All board-edit journal types now fail closed on malformed or no-op transitions.** Rule and
  specification entries validate operation, payload presence, snapshot identity, and collection
  position; rule-order entries validate their metadata, unique positive ids, unchanged membership,
  and an actual ordering change. Submitting an unchanged rule order now returns `400` before writing
  rules or history. A recorded rule, specification, or compound-device position that cannot
  reconstruct the exact ordered collection now returns `409` instead of being silently clamped to
  the end and reported as a successful but different Board. Rejected or unconfirmed undo/redo responses make the frontend reconcile the
  complete authoritative snapshot through its mutation queue, and refresh failure is reported as
  an unknown outcome instead of claiming the board was unchanged.
- **Undo responses now carry all authoritative semantic collections.** Nodes and Environment Pool
  values join rules and specifications in undo/redo results; availability-only responses return all
  four as empty non-state collections. The frontend validates every item, uniqueness, enum, direction,
  `applied`/`reasonCode`, supported entity/operation pairing, and entity-metadata invariant before
  committing the snapshot. Applied undo/redo and `NOTHING_TO_APPLY` must also agree with the
  direction's inverse/availability invariant. Every reversible device/rule/specification/reorder
  response must also report `canUndo: true` and `canRedo: false`; missing or contradictory availability is rejected
  instead of silently disabling the affordance. Assistant device refreshes re-read journal availability.
  A mutation's authoritative availability now also invalidates older in-flight reads, and concurrent
  refreshes use the latest-started response, so a delayed query cannot roll the buttons back. Device
  undo also clears inspector focus when the focused device no longer exists.
- **Device deletion emits one success notification.** Per-variable Environment Pool notifications
  are suppressed for the committed delete because its summary already reports the environment,
  rule, and specification impact.
- **Authoritative Board updates keep selection and keyboard ownership coherent.** Ordinary targeted
  device, Environment Pool, rule, and specification responses now use one semantic commit path;
  explicit server no-ops preserve a current verification verdict. Partial refresh, full
  reconciliation, and cross-tab reload clear inspector focus only when their authoritative
  collection no longer contains the selected item, including a device deletion whose response was
  lost. In nested dialogs, one `Escape` now closes only the innermost surface instead of bubbling
  into and closing its ancestor too.

### 2026-07-29

#### Added
- **Board edit journal ordinals are fenced by the database.** `sequence` was allocated
  read-max-then-add-one under an in-JVM per-user lock only, so two application instances could write
  the same ordinal for one account — making "the newest edit still in effect" ambiguous and silently
  stranding one reversible edit. `BoardEditJournalSequenceUniqueness` adds a unique
  `(user_id, sequence)` constraint. A duplicate-key race fails and rolls back the complete Board
  mutation; it is not retried inside the already rollback-only transaction. The migration is
  idempotent, a no-op on non-MySQL, and renumbers any pre-existing duplicates (oldest-first,
  preserving each account's relative order) rather than
  refusing to start against real data. Rollback is
  `DROP INDEX uk_board_edit_journal_user_sequence ON board_edit_journal`.

#### Fixed
- **A multi-mode conflicting end state is localized again.** `FaultLocalizer.describeEndState` joined
  the per-mode states with `" / "`, but the frontend's `formatBuiltInModelToken` splits on `[;,|]` to
  translate each token — so for a bundled template the whole string missed the catalogue and rendered
  raw (`on / idle`) in a non-English UI. It now joins with `"; "`, and the test pins the exact string
  rather than only asserting that the raw tuple is absent.
- **A padded specification-condition key no longer fails generation after passing admission.** Rule
  attributes and command actions were normalized at the storage boundary but the spec `key` was not,
  while `buildVariableCondition` trimmed and `validateApiSignalExists` / `resolveApiUntrustedSource`
  compared raw. The key is now stored trimmed and both comparison sites trim; the fixer's
  `expandRuleIndices` also trims, where a padded key silently dropped an environment domain and
  narrowed the fix search to fewer rules. `FuzzModel` now normalizes it in one accessor shared by
  validation and evaluation, which previously disagreed: a padded key validated and then resolved to
  no domain, changing what the explorer actually checked.
- **Applying a fix no longer leaves a permanently-conflicting undo.** `updateRulesAgainstSnapshot` —
  the fix-apply path — rewrites the whole rule collection, deleting omitted ids and renumbering
  execution order, but it neither recorded a journal entry nor cleared the journal. So an applied fix
  could delete the very rule an entry named: undo then threw a conflict on every press while
  availability kept reporting `canUndo: true`, leaving the button armed on an entry that could never
  apply. The journal is now discarded there, as it already was for device deletion and scene
  replacement, and the board re-reads availability when a fix is applied.
- **A dismissed confirmation no longer disables board undo for the session.** `ElMessageBox.close()`
  closes the surface without settling its promise, so the modal-surface count taken by
  `confirmDestructive` was never released — and since the board's Ctrl+Z reads that count, undo stayed
  silently blocked while the toolbar button still looked enabled. Reachable whenever a rule dialog
  closes underneath its own "save anyway" prompt. Each occurrence leaked another count.
- **Closing every result surface at once invalidates its in-flight loads.** `closeResultSurfaces`
  bumped only the exploration epoch, so a verification or simulation run detail still loading could
  repopulate a surface it had just cleared — the same defect as the dismissed-dialog one below.
- **A dismissed run dialog no longer reopens itself.** `dismissResultDialog` cleared the result and the
  deep link but left the in-flight history-detail request running, and `openVerificationRun` only
  guards on "is this still the newest request" — so a load that resolved just after the user pressed
  Escape re-assigned the result and the dialog came back, with its URL already stripped. It now
  invalidates the request coordinator first, matching what the exploration surface already did.
- **Escape closes a deep-linked modal on the first press.** `useModalAccessibility` bound its Escape
  handler to the modal's own element, so the key only worked once focus was inside the dialog — and
  focus arrives in a `nextTick` after a post-flush watcher. Opening a surface from a deep link on page
  load left a window in which the first Escape was silently dropped and the dialog just stayed open. A
  document-level fallback now covers it, scoped to focus-trapping surfaces so one keypress cannot close
  several of the board's non-modal tool panels at once.
- **Ctrl+Z no longer mutates the board behind an open dialog.** The accelerator is on `window`, and
  `targetOwnsNativeUndo` exempts only text inputs — so the keystroke pressed while focus sat on a
  modal's button undid a persisted edit underneath it, leaving the dialog showing a draft built from
  the pre-undo collections. `useBodyScrollLock` now exposes the open-modal depth and the board's
  undo-blocked predicate reads it. Confirmations raised through `utils/feedback.ts` register that depth
  too: an Element Plus `MessageBox` is modal to the user but passes `lockScroll: false` (the board
  shell is a fixed `100vh` surface), so tying depth to the scroll lock alone left every
  `confirmDestructive` window unguarded — pressing Ctrl+Z while a "delete this rule?" prompt was open
  still reversed the previous edit behind it.
- **A conflicted undo stops inviting the same failure.** The 409 branch reported the conflict but
  never re-read availability, so the button stayed enabled on an entry guaranteed to conflict again.
  Availability reads also carry an epoch now, so one in flight across a mutation cannot restore the
  pre-undo state.
- **The undo boundary validates its enum fields.** `reasonCode` defaulted to `NOTHING_TO_APPLY` when
  absent — contradicting `applied: true` — and `entityType`/`originalOperation` were cast unchecked,
  so a renamed server code became a typed value no consumer branches on. The `rules` array is now
  validated too: only `specs` was, though the function's own comment claimed both were, so a rule
  missing `command` reached board state and the canvas edge projection with an empty target and id.
- **A session list no longer fails over one untitled row.** `validateChatSession` accepted
  `title: null` but rejected the field being absent, though the DTO's nullable `title` makes them the
  same information.
- **`stopListening` no longer calls both `abort` and `stop`.** `recognition.abort?.() ?? recognition.stop?.()`
  always ran the right-hand side, because `abort()` returns `undefined`.
- **A collapsed reasoning panel stays collapsed.** The collapse was keyed by `turnId`, which is
  optional, so a row without one could record nothing and `:open` re-expanded it on the next render.
- **The auth watcher no longer fights the route guard.** `revalidateSession()` inside `beforeEach`
  flips `isLoggedIn`, and App.vue's `flush: 'sync'` watcher then navigated mid-guard — building
  `redirect=` from the route being left and cancelling the in-flight navigation with an unhandled
  NavigationAborted. The watcher now stands down while a navigation is resolving.
- **`border-primary/20` rendered at full strength.** Tailwind cannot derive an alpha channel from an
  arbitrary `var()`, so the canvas minimap border ignored its opacity modifier; it now uses
  `color-mix`, and the config documents why the modifiers do not work on that token.
- **The three MySQL-dependent undo tests run again.** They were `@Disabled` on the belief that H2
  could not read back a rule restored through the native insert into a `JSON` column. Once the H2
  fixture re-types those columns as text the restore round-trips correctly, so all twelve cases in
  `BoardEditUndoIntegrationTest` now run with no skips; `BoardEditUndoMySqlIntegrationTest` re-asserts
  the same path against a real MySQL, where the server's own JSON parsing returns the value unchanged.
  Both MySQL-only classes skip themselves when no server is reachable, so the H2-only CI job is
  unaffected. Two assertions in the re-enabled tests were also wrong: walking undo past the beginning
  reverses the create as well, so the board legitimately ends empty.
- **The rule dialog's "save anyway" confirmation is defined once.** Four copies of the same
  confirm-plus-epoch-recheck differed only in wording, and the epoch re-check is the race guard that
  stops a stale "yes" saving into a draft the user has since replaced — so a fix to it had to be
  applied four times.
- **Template-name rejections name the constraint that actually failed.** The rule was widened to allow
  uncased non-ASCII, but both messages still said "Only printable ASCII characters are allowed" — false
  for the now-accepted `温度传感器`, and misleading for a name rejected over a tab. `TemplateNameRule`
  now returns the reason and both call sites render it.
- **Case folding on the modeling path is locale-independent.** Several `toLowerCase()` calls on
  `targetType` and on trust/privacy enum tokens used the default locale; under a Turkish locale
  `"API"` folded to `"apı"`, matched nothing, and the rule was silently dropped from the model. All are
  pinned to `Locale.ROOT`, matching `normalizeSpecTargetType`.
- **An unreadable AI scenario draft no longer 500s a read-only request.** The lapsed-confirmation write
  was guarded, but the sibling cleanup in `active()` was not, so the same `@Modifying` delete enlisted
  in `getPendingConfirmation`'s `readOnly` transaction and failed at flush.
- **A malformed journal entry is a conflict, not a 500.** `applyRuleJournalEntry` parsed the entity key
  with a bare `Long.parseLong` where every sibling unreadable-payload case produced a typed
  `ConflictException`. The same undo path also read the user's whole rule list to find one id; it now
  looks the rule up directly.
- **A non-numeric node id no longer leaks into user-visible reasoning.** The identifier redaction
  required a digit in the tail to avoid rewriting English compounds ("rule-based"), which also let
  `device_a` and `spec_x` through — the only mechanical guard behind a system-prompt rule. The
  underscore form no longer requires a digit (English prose does not join words with underscores);
  the hyphenated form still does.
- **A null manifest API entry no longer NPEs during generation.** `DeviceSmvDataFactory.findApi` now
  skips null entries like both sibling implementations, so malformed manifest data records a disabled
  rule instead of failing the run.

#### Changed
- **Undo refuses a journal entry with no recorded position instead of appending.** Rule and
  specification restore fell back to appending when `entity_order` was absent. Since execution order
  decides which rule wins when guards overlap, appending silently restores a different board; such an
  entry (only possible for rows written before the column existed) is now rejected with `409`.
  Creates record their position too, so redo of a create restores the rule or specification instead of
  hitting that guard: they carried no `entity_order` (nothing preceded them), which made every redo of
  a create fail with a false "saved details are unreadable" conflict while leaving `canRedo` true, so
  the button stayed armed and the user could retry indefinitely.
- **A preferred range disjoint from the device's limits is reported as unused.** It was marked as
  matched before the empty-intersection check, telling the user their constraint held when nothing had
  tested it. Step A likewise only retries the original value when it lies inside the effective bounds,
  so a user who narrowed a range to exclude the original is not handed the original back labelled with
  the range they asked for.

### 2026-07-28

#### Changed
- **The COMPLETED terminal guard is now tested on the authoritative side.**
  `hasCompletedToolEvidence` decides whether a turn may be persisted as COMPLETED — at both the live
  terminal transition and history reload — and had no test of its own, only a tested mirror in
  `api/chat.ts`. Its rules are now pinned: an execution must pair with a result of the same tool and
  round, a failed or unconfirmable outcome disqualifies the turn however much usable work preceded it,
  an execution-guard stop makes the turn PARTIAL, a tool left PARTIAL blocks completion until a later
  round resolves that same tool, and a null frame makes the whole trace untrusted rather than
  partially believed.
- **The planning prompt's tool catalogue is now checked against the real registry.** The prompt
  hand-lists every tool under "Available tools:" while the model is *also* sent the actual schema set
  from `AiToolManager.getAllToolDefinitions()` — two independent sources of one fact, where adding a
  tool without editing the prose left the catalogue describing a capability set the model could not
  match, silently. A test now compares the list against the tool classes' own `getName()` literals and
  names any tool missing from either side. The platform self-description that both the planning and
  visible-reply prompts open with was duplicated verbatim in two text blocks; it is now one constant,
  also asserted, so the two rounds the model sees within a turn cannot describe the platform
  differently.
- **The assistant's reasoning is now reasoning, not narration.** The planning prompt asked only for
  "a concise summary" of goal, facts, next action, and remaining work — a well-written progress log
  — so that is what the model produced. It is now asked to work the problem out: decompose the
  question into the sub-questions that decide it, cite the specific devices, rules, and results that
  constrain the answer (and name what is still unknown), state the alternative it rejected when the
  call is a judgement rather than a forced step, and check the returned state against what it
  expected before concluding.
- **Reasoning is presented as an argument rather than a status line.** Its sanitizer had been
  flattening every line break, so a three-part decomposition arrived as one run-on sentence; the
  identifier redaction was case-insensitive and unanchored, rewriting ordinary English
  ("rule-based", "device-level", "trace-driven") as `[internal reference]` mid-sentence; and an
  800-character cut landed mid-word. Line structure is now preserved and rendered, the redaction
  requires a digit in the identifier tail, the budget is 1600, and truncation lands on a sentence or
  line boundary. The newest completed turn's panel also stays expanded — it used to shut the instant
  the stream ended, so anyone not watching live never read the argument behind the answer — while a
  deliberate collapse is remembered.
- A round that returns no reasoning now says so, instead of showing "Summarizing the current goal,
  observed facts, and next action" — wording that described reasoning which never happened.

#### Fixed
- **An unreadable tool result was reported to the model as "nothing was written".**
  `mutationMayHaveCommitted` returned false when a tool's JSON could not be parsed, so a tool that had
  committed and then produced truncated or non-object output was summarised as a no-op: the
  uncertain-mutation count stayed 0 and the notice injected into the next planning round told the
  model those steps reported no write, inviting a retry against state nobody had inspected. Unknown
  now fails toward "may have committed".
- **A transient unwritable connection could erase the execution audit from the stored turn.** The
  "N steps failed" and execution-guard notices were appended to the persisted answer only if their
  SSE send succeeded, so a failed send dropped them from the live stream *and* the database — the
  only place the user could still read them after a reload. They are now appended unconditionally,
  and a failed send is treated as the disconnect it is.
- **A chat session row missing `active` was read as idle.** `getSessionList` and `createSession`
  returned their payload unvalidated, unlike every other endpoint in `api/chat.ts`. Since `active`
  gates `isAssistantBusy`, an absent flag became `undefined` → falsy → idle, unlocking a second
  assistant mutation while one was still running server-side. Both now validate every row.
- **`AiToolManager` swallowed an interruption along with the flag.** Its broad `catch (Exception)`
  turned any failure into `TOOL_EXECUTION_ERROR` without re-arming the interrupt. The chat worker is
  stopped cooperatively, so this is never the chat request's own cancel — but a tool delegating to
  synchronous verification or simulation runs interruptible work, and a cleared flag leaves a later
  interruptible call on that thread unable to see it.
- **`apply_fix`'s confirmed call rejected the arguments it had just asked for.** The confirmation
  branch allow-listed only `traceId`, `confirmed`, and `impactToken`, while the preview branch
  *requires* `suggestion` — so the natural follow-up call, resending the previewed object plus the
  token, spent a whole round on a guaranteed `VALIDATION_ERROR` that no description warned about.
  Those two fields are now accepted and ignored; the write still comes from the server-stored
  proposal, which is what makes a confirmation unforgeable. Omitting `confirmed` also degrades to a
  preview now instead of failing validation, matching the other nine confirmation-gated tools —
  identical schemas had two different behaviours.
- **Undoing a specification deletion put it back at the end of the list.** Rule restore had been
  taught to honour its recorded position, but the specification path still appended, and
  `saveSpecsInternal` rewrites `list_order` from the list index — so undoing the deletion of the
  first of three specifications silently moved it last. It now reuses the same `entity_order`
  journal column, clamped when neighbours were deleted meanwhile.
- **A restored rule whose id had been taken now says so.** `rules.id` is a single global primary key
  (unlike `device_node`'s composite `(id, user_id)`), but the drift check only inspects the current
  account's rows, so an id held by another account reached `insertWithId` and surfaced as a generic
  primary-key conflict. The collision is now detected first and reported as what it is. The
  transaction rolled back before and still does; only the message changes.
- **A cancelled automatic-fix search could keep taking NuSMV permits.** Cancellation arrives as a
  thread interrupt, but two paths consumed it. `FixStrategyUtils.forwardVerify` — the entry point
  every strategy reaches NuSMV through — and `ParameterAdjustStrategy`'s refinement solver both had a
  broad `catch (Exception)` that swallowed the `InterruptedException` and cleared the flag with it, so
  `FixContext.isExpired()` stopped reporting the cancellation and the search ran its remaining
  attempts for a request whose response had already been sent. Both now re-arm the flag. Relatedly,
  `RuleFixer.fix` had started clearing the interrupt on entry, to guard against a flag leaked by a
  previous task on the pooled thread; that guard was unnecessary — `ThreadPoolExecutor` already clears
  interrupt status before each task — and actively harmful, because fault localization and context
  loading run first, so it discarded a cancel that had already arrived. The clear was removed.
- **Board undo raced ordinary board mutations.** `useBoardUndo` called the API directly, guarded only
  against a second undo, while the shortcut listener is on `window` — so Ctrl+Z during an in-flight
  rule delete issued two unordered requests and whichever response landed last won permanently,
  leaving the rule list and the undo affordance describing a server state that no longer existed. Undo
  now goes through the board mutation queue like every other mutation.
- **Rule condition attributes and spec target types are now normalized identically at admission and
  generation.** The same asymmetry behind the command-action defect below appeared three more times
  on the same boundary: the request validator resolved a condition `attribute` trimmed while both
  generator resolvers matched manifest variable, mode, and signal-API names untrimmed, and two of the
  four spec target-type comparisons in `SmvSpecificationBuilder` trimmed before lowercasing while two
  did not. A padded attribute therefore disabled a rule the validator had accepted, and a padded
  target type passed the safety-shape check before falling through the expression builder's dispatch.
  Normalization is now one helper per side, and `Locale.ROOT` is pinned so a Turkish default locale
  cannot fold `I` out of a keyword.
- **A rule whose command action had surrounding whitespace was silently omitted from the verified
  model.** Both admission validators compared the action trimmed, while the generator's canonical
  `findApi` compared it exactly, so such a rule passed validation and then resolved to no API at
  generation time. Because that lookup is the only source of rule branches for state, property, and
  probe assignments, the rule vanished from the model while `disabledRuleCount` stayed 0 and
  `modelComplete` stayed true — a `SATISFIED` verdict for a scene whose automation was never
  checked. Actions are now stored trimmed and compared trimmed everywhere, and an action that still
  resolves to no API disables the rule with the new `RULE_UNRESOLVABLE_COMMAND_ACTION` reason code
  instead of being skipped.
- **A run pinned to chosen attack points no longer claims an exhaustive search.** History's
  assumption chip inferred the attack mode from `attackBudget`, but the backend reports
  `effectiveBudget() == points.size()` for an exact-points run — so two deliberately chosen points
  rendered as "up to 2 of N compromised", inverting what the number means. The chip now
  discriminates on `attackSelectionPolicy`, matching every other surface.
- **A specification whose condition names a deleted device is refused inline.** Draft conditions
  hold device references captured at save time; a device removed afterwards (canvas, another tab,
  the assistant, an undo) left Create enabled, the backend refusing, and an opaque toast naming no
  row. The create button now states the reason inline and the offending rows are struck through. A
  deleted device also no longer renders identically to an unnamed one.
- **The scene-import diagnostics were unreadable in dark theme.** Element Plus teleports its message
  box to `<body>`, outside every `.dark`-scoped board override, so the hardcoded slate text that
  explains *which* scene field was rejected rendered near-invisible on the dark box. It now uses
  theme tokens, which follow the box itself.
- **An open device dialog could be painted over.** Its overlay used a raw `2200` — numerically the
  board *banner* layer, below alerts and below every other modal — and now uses `--z-modal`. The
  chat wrapper's raw `1200` became `--z-chat-panel`; both were the values the tokens already hold,
  but a literal stops the scale being authoritative the moment someone renumbers it.
- **Keyboard focus was invisible on the seven spec-condition controls.** They declare
  `focus:outline-none focus:border-red-400`, i.e. the border colour *is* the cue — but the dialog's
  `border-color: … !important` skin outranks it, leaving nothing. An outline is a different property,
  so it cannot be overridden the same way.
- **The info tooltip's hover and focus did nothing in dark theme.** Its `[data-theme='dark']`
  background override was more specific than `:hover, :focus-visible`, so it silently disabled both;
  the base blend now uses `--surface-elevated` (which already differs per theme), the override is
  gone, and focus gets a real ring instead of `outline: none`.
- **Indigo, sky, fuchsia, emerald, and teal are now remapped for dark panels.** `bg-white` was
  already remapped, so a `text-indigo-800` label on a dark card — the *selected* exploration-mode tab
  — was dark navy on near-black, and a dozen light chips across the exploration, timeline, and
  history panels stayed near-white inside dark surfaces.
- **Three modal overlays butted against the viewport edge.** The fix, simulation-result, and
  verification-result overlays centred their surface with no padding while every peer pads; all three
  now use `p-3 sm:p-4`.
- **A stale AI confirmation no longer turns "nothing pending" into a 500.** Both lazy-expiry
  cleanups reachable from the read-only `GET /api/chat/sessions/{id}/confirmation` enlisted a
  write in the caller's read-only transaction, failing the request at flush time. The state-store
  delete now runs in its own transaction and the scenario-draft clear is opportunistic; the
  scheduled expiry sweep remains the backstop.

#### Changed
- **Verification is staged by blast radius** (root `CLAUDE.md` / `AGENTS.md`). Narrow spec plus
  mutation check per edit, type check per slice, full suite per completed area, E2E only at
  session milestones or when touching routing/auth/deep links/cross-tab sync/backend contracts.
  Running the whole suite after every isolated edit spent minutes for information the focused run
  already gave, and encouraged skimming results instead of reading them.
- Four tests strengthened after review found they could not fail: the preferred-range Reset guard
  (its click landed on a disabled button, so neither half of the guard was exercised — both halves
  are now checked independently), two `TraceHistoryPanel` assertions that matched raw i18n keys
  rather than the rendered sentence, a `ControlCenter` mock that collapsed `notifyBlocked` and
  `notifyInfo` onto one spy, and an E2E test whose title claimed assistant journalling it never
  exercised.

### 2026-07-27

#### Added
- **Board edit undo/redo.** `Ctrl/Meta+Z` undoes and `Ctrl/Meta+Shift+Z` (or `Ctrl+Y`) redoes a
  persisted board edit, with matching nav-bar buttons. Reversible: **rule and specification
  create/delete** — single-record edits whose inverse is unambiguous and can always reach a legal
  board — and **rule reorder**, which changes no individual record but is reached through an
  explicit up/down button, so users read one press as one edit and expect `Ctrl+Z` to take it
  back. Its journal entry stores the previous *ordering* rather than a record snapshot, and is
  refused when the current order or the rule set is no longer what that edit produced.
  A per-user append-only journal (`board_edit_journal`) records each edit's before/after snapshot
  **in the same transaction as the edit**, so an undo can never describe a state that never
  existed. The server is the authority: the client keeps no snapshot stack, never inverts an edit
  locally, and reads availability from the journal (so it survives reload, a second tab, and
  another device). Undo is refused with a conflict when the affected record changed after the edit
  was recorded, so it cannot overwrite newer work; a new edit discards the abandoned redo branch;
  and "nothing to undo" is a normal, idempotent outcome.
  Deliberately **not** undoable: device deletion and scene replace/clear (they rewrite or cascade
  across collections, so no per-record inverse reaches a legal board — both clear the journal),
  environment variables (a shared pool other readers depend on), and async verification/simulation/
  exploration runs (those have cancel, stop, and delete-result, which are different operations).
  Native undo is never intercepted in text fields, `contenteditable` editors, or during an IME
  composition. Boundaries recorded in
  [docs/guides/frontend-ui-conventions.md](docs/guides/frontend-ui-conventions.md).
- **Deep-linkable run results.** The board's URL now carries `run=verification:<id>` /
  `simulation:<id>` / `exploration:<id>`, plus `trace=<id>` for a counterexample and
  `finding=<id>` for an exploration candidate. Refresh, Back/Forward, and a shared link all
  restore the same surface. The URL is the single authority: openers navigate and one watcher
  applies it, so component state cannot disagree with the address bar. Opening pushes;
  correcting or clearing replaces. Invalid params are stripped, and a well-formed link to a
  run this account cannot load degrades to the plain board with a persistent, dismissible
  explanation instead of a fabricated empty verdict.
  Panel layout, widths, `activeSection`, and canvas pan/zoom are deliberately **excluded** —
  they are already persisted per user server-side via `BoardLayoutDto`.
- **One feedback vocabulary (`utils/feedback.ts`).** All 421 toast call sites and every
  confirmation now route through intent-named helpers (`notifySuccess`, `notifyInfo`,
  `notifyBlocked`, `notifyError`, `confirmDestructive`, `acknowledge`,
  `dismissAllNotifications`, `dismissOpenConfirmation`). No component imports `ElMessage` or
  `ElMessageBox` any more, so severity, duration, danger-button styling, button order, and
  scroll behaviour are decided in one file.
- **Decision records** for both of the above:
  [docs/guides/frontend-ui-conventions.md](docs/guides/frontend-ui-conventions.md).

#### Fixed
- **An undo did not invalidate other tabs.** `isBoardMutationRequest` did not match
  `/board/edits/*`, so a second tab kept rendering rules and specifications the undo had already
  changed — while every direct mutation correctly refreshed it. Undo/redo now classify as board
  mutations; `edits/availability` stays a read.
- **A restored rule had no canvas connection line.** `applyResult` replaced `rules`/`specs` but did
  not rebuild the rule-derived edges the way every other rule mutation does. The line only
  reappeared once an unrelated refresh happened to run — correct by accident. Now rebuilt in the
  same step, alongside clearing an inspector focus that points at a record a redo just removed.
- **The undo availability endpoint returned the full rule and spec lists.** A query shipped
  collections on every board load, inviting callers to treat a read as an authoritative board
  update. It now returns availability only, typed as `BoardUndoAvailability` on the client.
- **Field validation was still delivered as a toast in the device runtime editor**, and the
  schema-conflict case duplicated an inline panel that already disabled the save button. Now one
  `runtimeSaveBlockedReason` drives the disabled state and an inline message.
- **Login and registration showed a success toast while navigating to the workspace.** Arriving at
  the board is the feedback; the toast repeated it.
- **A replay or a surface handoff could reopen the run the user just left.** The result-surface
  closers doubled as internal transitions (opening a counterexample replay hides the result
  dialog), so making them clear the URL meant replaying a trace stripped the params describing
  it — and closing a replay left `run=` behind, letting the sync reopen the result dialog over
  the board. Split into `close*` (internal, URL untouched) and `dismiss*` (user-facing, clears
  the deep link), with replay close counted as leaving the artifact.
- **Field validation was delivered as toasts.** ~15 call sites announced "select a device",
  "enter a device name", "add a condition for side A" and similar in a toast that fades while
  the user is still fixing the field — and several were unreachable duplicates of an inline
  error whose submit button was already disabled. Device create, batch create, device import,
  the specification condition dialog, and specification create now derive one
  `*BlockedReason` computed that drives both the disabled state and an inline `role="status"`
  message linked by `aria-describedby`.
- **Any query change remounted the whole board.** `App.vue` keyed the route component on
  `route.fullPath`, so adding or clearing a query param destroyed and rebuilt the workspace,
  discarding its state and re-running its entire load. Keyed on route identity + account
  instead — the actual ownership boundary. This also made deep links impossible.
- **A client-side `error.message` could reach the user**, exposing internal transport detail
  (`connect ECONNREFUSED 127.0.0.1:8080`). Only the backend's own `message` is shown, and only
  when it matches the active locale.
- **Cancelling a confirmation was modelled as a thrown exception**, which every call site had
  to catch and re-classify by comparing against the strings `'cancel'`/`'close'`.
  `confirmDestructive` resolves a boolean, and the three stale catch branches are gone.
- **Fuzzing preview freshness is now a tested rule (`utils/fuzzingConfig.ts`).** Whether a fetched
  workload preview may be shown as describing the current form was an inline computed in
  `Board.vue`; it is now `isFuzzingPreviewCurrent` alongside the bounds it already owned, with tests
  for the case that matters: a preview requested before the user changed a budget field or edited the
  board must not be rendered next to inputs it was never computed for. `hasValidFuzzingBudget`
  replaced a second inline copy of the bounds check, so `Board.vue` no longer imports those
  constants at all. The paper-domain preview keeps its own check on purpose — that payload has no
  budget fields, so sharing the helper would mean weakening it.
- **Two more pure slices out of `Board.vue` (`views/board/`).** `recommendationFilterText.ts` owns the
  wording for filtered recommendation candidates, and `sceneImportDiagnostics.ts` owns the parsing of
  scene-import validation rejections and stale-replacement previews. Both take `t`/`locale` as
  arguments rather than reading a component, so 18 new tests pin rules that were previously
  unreachable: that a backend `reason` is only shown when it matches the active locale, that
  `devices[]` and `nodes[]` name the same collection, and that an incomplete replacement preview is
  refused rather than shown with understated counts.
- **One owner for board semantic mutation follow-ups (`views/board/semanticCommit.ts`).** Applying
  a rule/specification mutation means replacing the authoritative collections and then rebuilding
  everything derived from them — canvas edges, dangling inspector focus, undo availability, verdict
  staleness — in a fixed order. Those four were previously hand-assembled at each of seven call
  sites with slightly different omissions, which is the root cause of the two staleness bugs fixed
  below: nothing detected them because a later unrelated refresh usually repaired the state. Call
  sites now pass the result and the ordering is guaranteed in one tested place.
- **Rule reorder returns the standard mutation envelope** (`CollectionMutationResultDto`) instead of
  a bare list, so it reports undo availability like every other reversible edit.

#### Changed
- **Added `.claude/` hooks so a mechanical rule is enforced, not remembered.** Following the
  official Claude Code guidance ("if a rule is mechanically checkable, convert it to a hook"), a
  `PreToolUse` hook now blocks a Playwright command while port 3000 is held. That is the failure
  that previously let the suite report green against stale code; the written rule is reduced to a
  one-line pointer. Verified all three paths: blocks on a busy port, ignores unrelated commands,
  honours the `E2E_BASE_URL` escape hatch.
- **Reworked the agent instruction files (`CLAUDE.md`, `AGENTS.md`).** "No AI slop" and
  "Maintainability and Change Discipline" had drifted into near-duplicates and were merged; added
  explicit autonomy tiers (act / confirm / never without asking) and a single traceability check;
  reframed verification around **reading the source first**, naming the three ways a green suite
  misleads (correct-by-accident, a test that cannot fail, verifying stale artifacts); added the rule
  to delegate long test/E2E/live-AI runs to background subagents rather than blocking the main
  thread; and added a maintenance section with a dated change log so the file is pruned as it grows
  instead of only appended to. Root file is 40 lines shorter net of the additions.

#### Fixed
- **AI recommendations silently dropped the platform's core requests.** The recommendation
  reachability filter narrowed a variable's reachable values to "current value plus whatever some
  template writes" — and applied that to *shared environment* variables too. But the generated model
  gives every enum environment variable a final `TRUE: {<all declared values>}` branch, so the pool
  value is only `init` and any declared value is reachable on step one. With the bundled sensor
  templates (smoke, motion, soil moisture, weather — none of which any template writes), "recommend a
  rule that sounds the alarm when smoke is detected" was filtered out as "legal but unreachable"
  whenever the pool read `clear`, and the user's only workaround was to hand-edit the Environment
  Pool first. The narrowing is kept for `IsInside=true` locals, which the model genuinely does hold
  constant when nothing writes them.
- **Device templates could not be named in Chinese, Japanese, or Korean.** Three duplicated copies of
  a `^[ -~]+$` pattern rejected every non-ASCII template name, justified as keeping
  `Locale.ROOT toLowerCase` and MySQL `LOWER()` in agreement for case-insensitive uniqueness. That
  reasoning only covers *cased* letters; caseless scripts fold identically in both engines. Device
  labels on the same board already accepted any Unicode. The three copies are now one
  `TemplateNameRule` that permits caseless non-ASCII and still rejects cased non-ASCII and control
  characters. Template names are display metadata — NuSMV identifier safety is enforced separately on
  variable/mode/state tokens.
- **`manage_rule` / `manage_spec` schemas described a field they reject.** `confirmed` was documented
  as "Ignored for add", but an add carrying it fails `requireOnlyFields` with `VALIDATION_ERROR`, so a
  model following the schema burned a round on a rejected create. The strict rejection is the intended
  no-silent-coercion policy; the description now matches it.
- **Account deletion left the undo journal behind.** `board_edit_journal` was missing from all three
  places that have to know about a user-owned table: `deleteUserOwnedData`, and both
  `USER_OWNED_TABLES` and `FOREIGN_KEYS` in `UserOwnedOrphanCleanup` — so there was no cascade
  either. Since journal entries store complete before/after snapshots of a user's rules and
  specifications, deleting an account left that content in the database, and `docs/api/auth.md`
  claimed a cascade that did not hold for the table. A structural test now derives the expectation
  from `USER_OWNED_TABLES` itself, so a newly added user-owned table cannot be forgotten again.
- **A streaming assistant reply could be written into an archived message.** The active row was
  owned by array index, but "load older messages" prepends a page and shifts every index, so
  subsequent chunks, the terminal status, and the execution trace landed on an unrelated historical
  message while the real placeholder stayed empty. The row is now found by its `turnId`, which the
  message already carried. Browsing history mid-stream stays allowed — the ownership was the bug,
  not the operation.
- **A stale simulation verdict could be replayed after closing its dialog.** Staleness was set and
  cleared against the run-details dialog ref, while replay admission is decided for the run, which
  survives every close. So a board change while only the timeline was open never set the flag, and
  closing the dialog cleared it — after which "view timeline" replayed an old trace over a changed
  canvas. Both now key on the surviving run, and closing a dialog no longer counts as a fresh result.
- **Closing the simulation timeline left `run=simulation:<id>` in the URL**, so a refresh or shared
  link reopened the playback the user had deliberately closed and put the board back into read-only
  playback mode. The counterexample path already cleared its deep link; this one now does too.
- **Automatic-fix Reset destroyed the user's preferred ranges and then did nothing.** Neither
  preference action was disabled during an in-flight search, and Reset cleared the rows *before*
  the request silently returned — losing the typed bounds and hiding the returning result behind
  "preferences changed, re-run". Both actions now derive their disabled state and a visible reason
  from one computed value.
- **Three surfaces could report evidence they did not have.** An AI scenario could apply a full
  scene while the accounting strip read "raw 0, inspected 0, kept 0", because only the standalone
  recommendation path tied `validatedCount` to its kept items. A `VERIFIED` automatic-fix attempt
  with no suggestion rendered "passed forward verification" beside the no-suggestion empty state,
  since only the suggestion-to-attempt direction was checked. And a counterexample's device summary
  rendered a valueless variable as `name=` while the same step's change list said `N/A`.
- **A drag and a resize could own one device node at once.** Each pointer gate checked only its own
  gesture; `isPrimary` stops a second touch but a pen and a mouse are each primary within their own
  type. The resize rewrote `node.position` while the drag read its origin from it, and the first
  pointer to lift ended the layout interaction, dropping the board's ownership guard while the other
  gesture was still writing geometry.
- **A stale delete could land on an edited rule or specification.** Semantic signatures canonicalized
  conditions into a `TreeSet`, so cardinality was invisible: a record edited from `[C, C]` to `[C]`
  compared equal to its pre-edit snapshot. Because that predicate also gates `removeRuleIfUnchanged`,
  the specification delete-if-unchanged path, and both undo/redo conflict checks, the "review the
  current record before deleting it" guarantee did not hold — one user could confirm a deletion
  against a rule another had changed underneath them. Both `exactlyMatches` implementations now
  compare multisets, staying order-insensitive while counting occurrences.
  `RuleSemanticSignature.Signature` deliberately keeps set semantics, since its only consumer
  reasons about subset and overlap between *different* rules.
- **A cancelled automatic-fix search kept running NuSMV.** The strategy loops exited only on the
  deadline, and their broad `catch (Exception)` swallowed the `InterruptedException` that
  `Semaphore.tryAcquire` throws on cancellation — clearing the interrupt flag with it. After a user
  cancelled `/api/fix`, the worker therefore ran its remaining attempts (up to 20, each holding one
  of the 6 shared NuSMV permits for up to 120s) against a request whose response had already been
  sent, which could starve concurrent verification and simulation of solver capacity for the full
  300s fix budget. `FixContext.isExpired()` now reports an interrupt as well as an expired deadline,
  and `FixStrategyUtils.preserveInterrupt` re-arms the flag from those catches. Fixing `isExpired`
  covers `RemoveRulesFixStrategy` too, since it shares the same guard.
- **A cancel could interrupt an unrelated task.** In `VerificationServiceImpl` and
  `SimulationServiceImpl`, `registerRunningTask` and `updateTaskProgress` sat *outside* the `try`
  whose `finally` is the only place those registrations are removed — and `updateTaskProgress`
  writes its in-memory map before its database write. A database blip during startup therefore left
  the pooled worker thread registered against a task it was no longer running; cancelling that task
  later interrupted whatever the thread had picked up next, and the victim was then failed by the
  lease sweep with a message unrelated to its real cause. `FuzzServiceImpl` already had the correct
  shape, which is what identified the other two as the deviation.
- **The same leak on the cancellation path.** `handleCancellation` runs in every worker's `finally`
  ahead of that cleanup and touches the database twice, so a failure there skipped the cleanup
  entirely. It now catches and logs; the row is left to the user's own cancel or the expired-lease
  sweep, both of which remain authoritative, so nothing is reported as success.
- **The account-delete confirmation could never be satisfied on Android.** The typed-confirmation
  gate required a preceding `keydown` with a printable `key`, but Android soft keyboards report
  `Unidentified` and dropped text fires no `keydown` at all. The user typed their username
  correctly, the hint showed no error because the text matched, and the delete button stayed
  permanently disabled with nothing explaining why. Now keyed on `InputEvent.inputType`, which every
  real edit carries and which programmatic autofill (a plain `Event`) does not — so the
  password-manager protection the gate existed for is preserved.
- **E2E could silently report green against stale code.** The production-build web server was
  configured with `reuseExistingServer` on outside CI, so a dev server left running on :3000 was
  adopted, the build step skipped, and the suite ran against whatever that process was serving —
  a 2.7s run instead of 42s gave it away. It is now off, so a busy port fails loudly instead.
- **Assistant refresh targets are pinned to the backend.** A test now parses the `REFRESH_DATA`
  targets `ChatServiceImpl` actually emits and requires the frontend table to declare exactly those,
  because a target the backend sends but the frontend does not declare is dropped as "unsupported" —
  leaving the workspace stale after a successful tool run.
- **Parallel E2E runs failed on tests that had nothing wrong with them.** Playwright served the app
  through the Vite dev server, so two browsers loading the board at once could exceed the 30s
  `board-root` wait — the failure moved between tests run to run, which is what gave it away. E2E now
  runs against a production build via `vite preview` (with the `/api` proxy declared under `preview`,
  which does not inherit `server.proxy`). The full suite is reliable at `--workers=2` and ~40%
  faster than the serial workaround.
- **A flaky auth test.** `validToken()` embeds a whole-second `exp`, so building the "same" token
  twice across a second boundary produced two different strings and the cross-tab assertion failed
  intermittently. The token is now built once.
- **The undo button stayed enabled after a device deletion emptied the journal.** Deleting a device
  cascades into the rules and specs referencing it, so the server drops the whole journal; the
  client applied that mutation inline without re-reading availability, leaving a button that would
  only report "nothing to undo". Both journal-clearing commands now go through one named
  `notifyUndoJournalCleared`.
- **Assistant `REFRESH_DATA` dispatch was duplicated and unvalidated.** `App.vue` carried a switch
  mapping each target to a board method and to whether it invalidates other tabs, while `Board.vue`
  held the same knowledge again; an unknown target fell through to a warning. Both now read one
  table (`views/board/assistantRefresh.ts`), whose tests pin the method names against the board's
  real `defineExpose` block so a typo cannot silently make the assistant report failure.
  Investigating this confirmed — against the real model — that an assistant-created rule *is*
  already as undoable as a user-created one (`e2e/live-ai-no-mock.spec.ts`), because publishing a
  board invalidation reloads the snapshot and with it the undo availability.
- **Expired token no longer passes the route guard** — replacing the guard's `localStorage`
  read with the auth store lost the per-navigation expiry check. The store now owns
  `revalidateSession()`, so a JWT that lapses while the tab stays open drops the session on
  the next navigation instead of reading as authenticated until a request 401s.
- **Fractional-width viewports fell between complementary media queries** — pairs written as
  `max-width: 1023px` / `min-width: 1024px` (and `599px`/`600px`, `767px`/`768px`,
  `1100px`/`1101px`) matched neither rule at e.g. 1023.5px, which is routine on scaled
  displays. Each pair now splits at a single value. `DeviceDialog`'s compact overlay padding
  also overlapped Tailwind's `sm:` at exactly 640px, applying both layouts at once.
- **404 page dead link** — the "back home" button pointed at `/home`, which is not a route;
  clicking it redirected back to `/404`, trapping the user.
- **Recommendation panel self-close** — `openScenarioRecommendationPanel()` called its own
  close handler, discarding the state it had just reset. The four panel openers are now one
  table-driven function, so the mutual-exclusion invariant lives in a single place.
- **Uncontrolled side-panel selection** — `ControlCenter` / `SystemInspector` declared a
  default for the optional `activeSection` prop, so the prop was always "controlling" and an
  uncontrolled mount silently ignored every selection change.
- **Redundant Board remount after login** — a `router.afterEach` hook rewrote `/board?redirect=…`
  to `/board` *after* navigation completed, which changed the route-keyed component key and
  remounted the freshly mounted workspace. The login surface now navigates to a clean path.
- **Hash-history deep links under a sub-path deployment** — the pre-router path rewrite
  hardcoded a root-relative `/#…`, producing a 404 when the app is not served from `/`.

#### Changed
- **Route guard reads the auth store** instead of re-parsing `localStorage`, removing a second
  source of truth for "is the user signed in". Guard logic is extracted as a pure function.
- **401 redirect unified** — the axios interceptor, the SSE transport, and the app-level auth
  watcher shared three copies of the same redirect construction; they now call one owner
  (`router/loginRedirect.ts`).
- **Document title** is now applied from `route.meta.title` (previously the meta field was
  never read, so every page showed the static `index.html` title).
- **Theme follows the OS by default** and the toggle cycles light → dark → follow-system.
  The `resetThemeToSystem` / `followsSystem` capability existed but had no caller, so the
  registered `prefers-color-scheme` listener was dead.
- **Stacking order is a named scale** (`--z-board-nav` … `--z-toast` in `styles/base.css`).
  The ad-hoc literals (100, 1000, 2000, 2200, 2350, 9999, 10000) gave no way to tell which
  surface should win.
- **Tailwind `primary` and the font stacks resolve to CSS tokens**, so utilities and
  hand-written CSS cannot drift into different values for the same colour.

#### Accessibility
- Board run-setting switches are a shared `role="switch"` + `aria-checked` component; the five
  hand-rolled variants exposed neither an accessible name nor state.
- `ControlCenter`'s section tabs now use the same `role="tablist"` + roving-tabindex keyboard
  model as `SystemInspector` (shared `useRovingTablist` composable).
- The eight non-modal tool panels are `role="region"` rather than `role="dialog"`; they
  deliberately do not trap focus, so the dialog role misdescribed them.
- Modals lock background scroll (reference-counted for nested confirmations).
- Heading hierarchy: the Board has an `<h1>`; two dialogs no longer claim page-level `<h1>`.
- Named the six previously unnamed condition edit/remove icon buttons.
- Removed seven duplicate native `title` tooltips that stacked on top of custom ones.

#### Removed
- `components/LogoutConfirm.vue` (superseded by `LogoutConfirmDialog.vue`; unreferenced).
- `assets/auth-styles.css` — all of its class rules were unreachable; its `body` font
  declaration moved to the shared style layer.
- 232 lines of dead `board.css` (the `.floating-card` / docking block, whose markup no longer
  exists) and six overridden gradient utilities.
- `router`'s `clearInvalidTokens()` (triple-removed the same keys), an unreachable guard
  branch, and the never-read `meta.usesOwnHeader`.

#### Tests
- Added `e2e/ui-contracts.spec.ts` (15 tests) covering the routing, session, theme, and
  accessibility contracts that only a real browser can assert: expired-token refusal, clean
  post-login URL, no-hash deep links, document titles, single `<h1>` per route, the
  three-state theme cycle, switch role/name/state, focus restoration from a non-modal panel,
  keyboard-driven tab strips, background scroll lock, that no board button lacks an
  accessible name, and both sides of the 1023/1024 and 640px breakpoint boundaries.
- Added a worker-scoped `sharedReadOnlyAccount` fixture. `accountCleanup` is per-test and
  *deletes* the accounts a test registered, so a spec that cached one account across tests
  handed later tests a token for a deleted user; sharing per worker also keeps a spec under
  the backend's registrations-per-hour cap.

### 2026-07-26

#### Fixed
- **Stale verification verdict tracking** — the frontend now correctly marks verification results
  as stale when error-recovery paths refresh the board outside the normal mutation queue:
  - Environment save CAS-stale / unknown-outcome recovery (two paths)
  - Device delete 404 / unknown-outcome recovery (two paths)
  - Scene import/clear unknown-outcome recovery (two paths)
  - Fuzzing paper-domain stale recovery
  - Scene replacement drift detection
  
  These paths call `refreshBoardSnapshot()` or `refreshSceneForReconciliation()` directly, bypassing
  the semantic-change callback that normally marks stale. If a verification result was displayed when
  these errors occurred, the verdict would incorrectly claim to describe the now-changed board.
  
  Fix: explicitly mark stale after successful reconciliation in each recovery path. The async
  verification replay guard (commit 092124d) was already correct; this closes the error-path gaps.

### 2026-07-25

#### Added
- Closed four AI-assistant capability gaps so device editing, rule ordering, run cleanup, and the
  bounded counterexample-search (fuzz) workflow can be completed in chat instead of forcing a switch
  back to the UI. The assistant tool catalog grew from 35 to 48 tools.
  - `edit_device` edits one existing device in place — `field=label` renames it via the same
    compare-and-set rename as the UI (cascading the new name into referencing specifications, with a
    case-insensitive conflict returning a no-write `409 DEVICE_LABEL_CONFLICT` and a suggestion),
    `field=runtime` compare-and-set-replaces its initial state/trust/privacy/variables (concurrent
    change returns `409 DEVICE_RUNTIME_CONFLICT` with the current device, no write), and
    `field=layout` compare-and-set moves or resizes its canvas card (concurrent canvas changes return
    `409 DEVICE_LAYOUT_CONFLICT` with the current device, no write). Each edit is reversible and
    targeted; fields belonging to another aspect are rejected rather than ignored.
  - `manage_rule` gained an `action=reorder` that atomically replaces only the
    verification-significant rule execution order. `expectedRuleIds` preserves the complete order
    the caller observed and `ruleIds` supplies the desired permutation, so a concurrent reorder is
    rejected instead of silently overwritten. It is reversible and needs no confirmation.
  - Added the conversational bounded-search workflow: `fuzz_model_async` (reproducible
    `BOARD_SNAPSHOT` strategy only),
    `fuzz_task_status`, `cancel_fuzz_task`, two-turn `dismiss_fuzz_task`, `list_fuzz_runs`,
    `get_fuzz_run`, `get_fuzz_finding`, and the two-turn cascade `delete_fuzz_run`. Findings remain heuristic
    candidate evidence, are kept strictly separate from formal traces, and have no route into
    `fix_violation`/`apply_fix`; budget exhaustion is never reported as satisfaction.
  - Added verification/simulation history cleanup: two-turn cascade `delete_verification_run` and
    two-turn `dismiss_verify_task` / `dismiss_simulate_task` (which preview the diagnostics retained
    by already-dead, resultless tasks and refuse active/completed ones). `list_traces` now also
    returns each trace's `runId` so runs are addressable from chat.
  - Run deletion and terminal-task dismissal tools reuse the existing two-turn
    `AiDestructiveActionGuard` impact-token flow; all new mutating tools are registered in
    `AiToolManager.MUTATION_CAPABLE_TOOLS` so oversized-result handling reports possible commits.

#### Changed
- Raised the supported frontend runtime to Node.js `^20.19.0 || >=22.12.0`, declared the
  constraint in `package.json`, and pinned CI to Node.js 20.19.5 so unsupported Node 18
  installations fail at the package boundary instead of proceeding after engine warnings.

#### Fixed
- Stopped a displayed verification verdict from continuing to claim it describes the current
  board after the model changed. Applying an automatic fix, or editing rules/specifications/
  devices from the inspector or the AI chat while the result dialog is open, now marks the
  verdict stale: the dialog shows an explicit "re-run verification" banner, the per-counterexample
  Fix action is withdrawn, and counterexample replay is refused instead of animating a trace over
  a canvas it no longer describes. Staleness is driven by the existing semantic scene fingerprint,
  so every mutation path is covered, and a newly presented result always starts clean.
- Stopped the System Inspector from silently discarding Environment Pool edits for a variable
  whose authoritative value is blank. A variable with no declared value domain (not verifiable)
  now shows its value, trust, and privacy controls disabled with an explanation instead of
  accepting an edit that could never be persisted. The compare-and-set baseline is now built
  strictly from the authoritative Environment Pool snapshot rather than from a template-derived
  display value, so trust/privacy-only edits no longer send a spurious baseline that the server
  rejects as stale.
- Required interactive recommendation and automatic-fix results to pass one atomic Redis
  completion fence before delivery. The fence checks request ownership, per-user ownership,
  request cancellation, and account-deletion cancellation together; an expired/replaced lease,
  stop request, or uncertain Redis response now fails closed instead of returning stale work as
  successful.
- Measured a chat scenario recommendation's prospective UTF-8 result before storing its validated
  full-scene draft. An oversized result now returns `TOOL_RESULT_TOO_LARGE` without creating a
  hidden draft or replacing the user's previous visible draft.
- Prevented verification, simulation, and bounded-search queue/dispatcher failures from accumulating
  dead stored-task rows against the user's quota. A failed start now conditionally removes only the
  submitting worker's still-pending row for both executor rejection and other runtime failures. If
  cleanup cannot be confirmed, AI tools preserve the task id as
  `TASK_DISPATCH_OUTCOME_UNKNOWN`, stop the tool loop, refresh run history, and require status
  reconciliation before retrying.
- Revalidated historical verification, simulation, and bounded-search playback after their detail
  requests return. Playback now waits for already-admitted Board writes and is deferred if a new
  edit, scene replacement, playback surface, recommendation, or live editor appears while history
  is loading, instead of opening a read-only timeline over an active edit.
- Preserved the authoritative task id when an async verification, simulation, or bounded-search
  submission succeeds but its initial status cannot be read or serialized. The tool now directs the
  assistant to poll that exact task instead of presenting a retryable start failure. Bounded-search
  finding detail is also paged (`stateOffset`/`stateLimit`) with matching input-event windows, so a
  large saved path does not make the only detail tool unusable.
- Made verification/fuzz run-deletion previews count exact persisted trace/finding rows, including
  unavailable or damaged evidence, without deserializing their payloads. Confirmed deletion locks
  the user-owned completed run and rechecks both the expected and actually deleted row counts, so
  impact drift cannot silently remove more evidence and corrupt fuzz history remains cleanable.
- Removed the internal persisted specification id from bounded-search run and finding tool
  responses. The assistant keeps the finding/run operation handles and user-semantic violated
  specification projection without exposing a second persistence identity.
- Closed the assistant's formal-fix workflow with a separate `apply_fix` tool. It verifies an
  exact signed `fix_violation` suggestion before a no-write preview, stores that payload only in
  the expiring session confirmation state, and requires a later explicit impact-token confirmation.
  Confirmed application calls the existing signed fix service so signature expiry, complete
  Board/template/spec/device/environment drift checks, formal-operation admission, and the
  transaction commit fence remain authoritative. Confirmation mismatch, replay, expiry, malformed
  schemas, and fuzz-finding inputs fail before mutation. Undeliverable previews are no longer left
  confirmable, including previews discarded by the chat result-size limit, and admission/settlement
  ambiguity after service invocation is reported as an unknown mutation result that refreshes rules
  before retry.
- Made interactive AI recommendation and automatic-fix ownership acquisition one atomic Redis
  command. Initial Redis unavailability still falls back to process-local tracking, while an
  uncertain or post-TTL acquisition now performs token-fenced cleanup and returns `503` instead
  of creating a possible second owner. Lease polling likewise uses the monotonic call-start time,
  so a delayed success response cannot revive ownership after the 30-second TTL.
- Prevented an AI-generated scene from replacing newer Board edits after it waited behind a
  pending mutation. Scene application now rechecks recommendation ownership after queue drain,
  replacement preview, user confirmation, and final mutation admission.
- Made unknown template-import, default-reset, and template-delete outcomes fail closed when
  authoritative reconciliation also fails. The affected template/environment collections become
  unavailable, stale recommendations close, and model runs remain blocked until a full refresh
  succeeds.
- Validated device-type deletion conflict previews before replacing the open confirmation. Invalid
  or unexpected `409` payloads now refresh the authoritative type catalog and close the stale
  confirmation instead of exposing an unverified target, blocker list, or confirmation token.
- Bound recommendation, chat, and automatic-fix cancellation to the authentication token that
  started the operation. Sign-out now stops active interactive work before revoking that token,
  cross-tab account changes cannot cancel Alice's work with Bob's token, and permanent account
  deletion publishes a bounded cross-instance stop fence for recommendation and fix execution.
- Kept interactive cancellation aligned with same-user token renewal. Chat streams adopt a renewed
  credential for later Stop requests, while recommendation teardown and sign-out retries retain a
  captured owner credential even after the original POST settles with an uncertain transport result.
- Kept all four AI recommendation requests busy and cancellable when their POST receives no HTTP
  response. POST, status, and cancellation now use one explicitly captured owner credential; bounded
  recovery blocks duplicate request ids across transport loss or account switching, sign-out accepts
  owner-authenticated `FINISHED` status when cancellation returns `false`, and an old terminal POST
  settlement cannot release a newer recommendation.
- Kept automatic-fix searches tracked when their POST loses transport before any HTTP response.
  The dialog now pins one initiating credential across POST, status, and cancellation, rejects
  unauthenticated starts, and retains the request id and credential through bounded cancellation/status
  recovery, remains mounted while hidden, blocks a parallel search after close or reopen, and reports
  logout uncertainty unless cancellation or authoritative completion is confirmed. A `FINISHED`
  status now releases a hung POST, stale cleanup cannot clear a newer same-strategy request, hidden
  searches remain bound to their original trace, and lifecycle control calls use short timeouts.
- Protected unsaved device-instance configuration drafts across close buttons, Escape, backdrop
  clicks, and the details-to-rename transition. Closing now requires confirmation when normalized
  runtime/trust/privacy/variable values differ from the server baseline; saving temporarily blocks
  close, rename, and deletion, while edits made during an in-flight save remain dirty after its
  response. Long custom device metadata now wraps within narrow details dialogs instead of being
  clipped outside the visible table.
- Kept saved-model playback usable in short landscape viewports by placing the step-change inspector
  and timeline in separate columns while both are visible; dismissing the inspector restores the
  timeline's full available width.
- Kept visible confirmation after applying a standalone rule, device, or specification recommendation.
  The applied candidate remains as a disabled item while every other candidate generated for the old
  Board context and its candidate-accounting metadata are discarded, so stale suggestions cannot be
  applied after the scene changes. An unknown rule-create outcome is still reconciled after the user
  closes the recommendation panel, preventing a committed rule from remaining absent in the UI.

### 2026-07-24

#### Changed
- Made public Environment Pool edits compare-and-set operations. The Board now submits a
  complete baseline with each field patch, rejects stale edits with a structured conflict,
  and refreshes the current value instead of allowing another tab to overwrite it silently.
  The baseline carries a non-blank value from the variable's finite template domain plus
  complete trust/privacy labels. Omitted desired fields preserve their current value, while
  explicit JSON `null` for value, trust, or privacy is rejected instead of becoming an
  ambiguous no-op.
- Made scene-recommendation completion use explicit minimum device, automation-rule,
  and specification targets instead of treating any non-empty category as complete.
  The backend and Board now validate the same target counts, report missing versus
  insufficient content deterministically, and show unmet targets before scene application.
- Removed the development-only login collision reader. Registration keeps phone-shaped
  usernames invalid, and login now classifies an identifier once and queries only the
  phone or username namespace instead of probing both for obsolete conflicting rows.
- Made current development persistence contracts strict at every history boundary. Fuzz
  input envelopes now require all six frozen-snapshot fields with their canonical JSON
  types, and full run reads recompute the same normalized semantic fingerprint used at
  write time instead of trusting a well-formed digest plus matching item counts.
- Made verification, simulation, and counterexample-search task mapping enforce the
  service's actual lifecycle state machine: identity, progress, timestamp ordering,
  processing duration, terminal metadata, failure diagnostics, saved-trace ownership,
  and result fields must agree with the persisted status.
- Made chat-stream completion a persisted protocol fact. The backend now emits a unique final
  `{ turnId, executionStatus }` terminal frame only after saving the matching assistant row;
  clean EOF without it is incomplete, while accepted failures settle to authoritative history
  even when the durable result is a user-only turn. Transport loss before response headers is
  treated as an unknown admission outcome, and failed or inconsistent history reloads keep the
  assistant locked until authoritative reconciliation succeeds.
- Made pre-admission chat Stop fences finite and turn-specific across backend instances. Fences
  use the database clock, expire after two minutes, and are bounded to 64 live turns per session;
  an expired fence is purged instead of cancelling a later request. Untitled session titles now
  fold Unicode whitespace and truncate by Unicode code point (12 code points plus `...`) so
  multilingual text is not split in the middle of a surrogate pair. Chat admission now uses
  the same definition and rejects messages made only of Unicode whitespace.
- Made Device Details retain only still-legal dirty runtime edits when a same-node template
  schema changes. Save remains blocked until the user explicitly adopts the latest runtime or
  continues with the compatible subset; untouched drafts adopt the new schema immediately. State,
  current-state trust, and current-state privacy now reconcile as one context so a refresh cannot
  combine one state's trust/privacy overrides with a different state.
- Applied the signed automatic-fix mutation under the same per-user formal-operation lease and
  commit fence as verification and simulation, including a fence registered inside the board-write
  transaction so an expired lease cannot publish a stale repair.
- Kept user-defined state and variable identifiers unchanged in fuzzing and playback displays,
  even when an identifier collides with the bundled `workingState` token.

#### Fixed
- Fenced rule duplicate/similarity checks to the open editor that started them, so closing
  the dialog cannot let a delayed check save or report against a discarded draft. Every
  accepted local or external semantic Board change now closes and cancels recommendations
  produced for the previous scene, and queued recommendation applications recheck admission
  immediately before writing. Collection reordering, no-op saves, and canvas-only layout
  changes keep still-valid recommendations open.
- Kept cancellation of a history-delete confirmation from cancelling an unrelated in-flight
  detail or replay request. Detail requests are invalidated only after the user confirms the
  verification, simulation, or counterexample-search deletion.
- Made Control Center read-only behavior match playback and scene-replacement semantics.
  Template preview, search, export, and schema download remain available, while device,
  template, rule, and specification mutation controls are disabled. Open specification-
  condition and template confirmation dialogs now close when the lock begins, and late
  impact-preview responses cannot reopen them.
- Separated chat SSE failures from model-authored text with a structured `error` frame. A
  valid assistant response beginning with the literal text `[ERROR]` is no longer mistaken
  for a server failure by the frontend, and a parsed server error is retained if the transport
  resets before its terminal frame arrives.
- Preserved quoted and code-formatted platform-action text in assistant explanations and
  translations. Sentence buffering now recognizes ASCII and typographic quote pairs, inline
  backticks, and backtick/tilde fences with longer closing delimiters. It ignores punctuation
  inside closed literal spans while final claim checking fails closed on unfinished spans, so
  an unclosed quote or fence cannot hide a later unsupported execution claim.
- Released the assistant interaction lock after an explicit early Stop or reattached remote
  execution is confirmed idle with authoritative user-only history. The frontend now removes
  optimistic output and restores the draft only when the corresponding user turn was not admitted.
- Made local chat Stop requests turn-aware and durable before transport abort. A quick Stop now
  fences a request that has not entered admission yet, cannot target a newer turn, and does not
  begin idle polling until the backend acknowledges the fence. Multiple pre-admission turns retain
  independent bounded fences, with orphan cleanup and database-level session deletion cascades.
  Remote settlement also invalidates stale initial-history reads and clears a recovered
  history-load error.
- Invalidated a coupled-scene recommendation whenever its minimums, maximums, or requirement
  text changes, so a draft generated for earlier criteria can no longer be exported or applied
  as though it satisfied the edited request.
- Made an acknowledged cross-instance chat Stop authoritative inside the terminal-message
  transaction, so the following browser abort cannot downgrade it to `DISCONNECTED` or let a
  previously computed completion/error status win. Server-observed stops now close the SSE after
  the persisted terminal frame instead of leaving the stream allocated until timeout. An explicit
  Stop also cancels the matching same-instance provider request when its database lease has just
  expired. Chat lease
  admission and renewal now also reject slow commits that leave less than one heartbeat interval
  before expiry, and each instance renews all of its active chat leases in one commit so later
  sessions cannot consume an earlier session's remaining heartbeat window. Expired-row cleanup
  now precedes renewal, with a final margin check before the scheduled pass returns.
- Ordered bounded LLM chat context by database message id and used the database clock for
  session-list timestamps, so skewed backend instance clocks cannot reorder later turns.
  A `COMPLETED` chat trace now also requires each tool execution to pair in order with the
  same tool and round's result at both backend persistence and frontend boundaries. A later
  usable result from the same tool may explicitly recover an intermediate partial result;
  unresolved partial, failed, unavailable, or confirmation-required results remain incomplete.
- Kept cross-tab assistant coordination locked when more than one chat session is active.
  Foreground refresh now prioritizes the selected active session regardless of list order,
  switches from an idle selection to authoritative active work, and hands monitoring to any
  remaining active session before Board interactions are released.
- Invalidated pending history-detail and finding-replay requests when the fuzzing-result dialog
  closes, so a delayed response cannot reopen playback after the user dismissed the result.
- Kept every Device Details action at least 44 pixels high (and the icon-only close
  action 44 pixels wide) for touch use, and restored the dedicated Door and Garage Door
  icons that were previously shadowed by the generic sensor-name classifier.
- Rejected non-positive or duplicate non-null rule ids at the shared verification and
  simulation request boundary. Unsaved rules may still omit ids, while persisted ids now
  provide an unambiguous current-canvas correlation key for triggered-rule trace evidence.
- Bound triggered-rule and compromised-link trace evidence to the exact zero-based rule
  position in each immutable run request. Fault localization and automatic-fix replay no
  longer broaden one execution probe to every rule sharing a nullable/duplicate id or label,
  and verification, simulation, and counterexample-search history now rejects missing,
  duplicate, out-of-range, or forged rule snapshots before returning full replay evidence.
  Lightweight fuzz finding summaries no longer claim `dataAvailable` before their LONGTEXT
  evidence and frozen run are loaded and validated. Finding replay now loads the full owning
  run once and selects the requested finding from its validated embedded evidence instead of
  joining independent finding and run responses. The single-finding endpoint verifies the
  run's declared `findingCount` against the actual owned-row count before returning surviving
  detail, and unavailable run summaries carrying findings are rejected. Canvas playback associates validated
  historical evidence with current
  edges only when the persisted rule id identifies one current rule; ambiguous or id-less
  evidence is left unhighlighted instead of guessing from a current list position.
- Made fault-localization conflicts follow the generated multi-mode transition semantics:
  two commands conflict only when they write different values to an overlapping mode;
  simultaneous writes to disjoint modes are reported as independent executions.
- Closed the synchronous formal-operation lease-to-commit window with a monotonically
  increasing database fencing epoch. Each admitted operation claims an epoch before
  expensive work; final history persistence locks that user's epoch row through physical
  commit and rejects a superseded epoch as well as an expired Redis lease. Ownership loss
  remains a service-unavailable failure instead of being downgraded to a usable result
  whose history status is merely unknown.
- Made verification and simulation history summaries validate the complete persisted
  state array, contiguous state indexes, and scalar count before advertising replayable
  evidence. Damaged state JSON now produces an unavailable placeholder and cannot inflate
  `counterexampleCount`.
- Made internal verification/simulation request cloning reject device-count, null-element,
  or token-provenance drift instead of silently returning or substituting `UNKNOWN`.
- Persisted the user turn and session execution lease atomically before chat dispatch, rejected
  reused per-session turn ids, and made queue rejection remove that exact turn before returning
  `503`. Ambiguous commit or cleanup outcomes now return an explicit no-terminal reconciliation
  stream instead of pretending either rejection or execution was confirmed.
- Required lowercase `trusted`/`untrusted` and `public`/`private` literals at the canonical
  device-template JSON boundary, matching the typed scene contract.
- Preserved custom `ruleString` text on rules untouched by an automatic fix; only changed rules
  are regenerated from their updated conditions/actions.
- Kept immediate verification traces replayable when history persistence fails or remains
  unknown, but removed provisional persistence identities after an unconfirmed commit and
  exposed automatic Fix only when the trace belongs to the response's confirmed saved run.

### 2026-07-23

#### Changed
- Added an explicit anti-AI-slop engineering standard to the repository, backend, and
  frontend contributor manuals: generated changes must have a named requirement or defect,
  one authoritative implementation path, boundary and failure-state coverage, synchronized
  contracts and documentation, and a maintainer-readable full-diff review before delivery.
- Codified the active-development compatibility policy in the canonical `AGENTS.md` and the
  root, backend, and frontend `CLAUDE.md` files: change all in-repository callers and tests
  together, reject obsolete or malformed development state, and do not add speculative
  old-format readers, dual writes, aliases, rolling-deployment bridges, or silent fallbacks.
- Removed the obsolete top-level verification/simulation request fields `isAttack` and
  `attackBudget`; model requests now use only the structured `attackScenario`, while the
  derived summary fields remain available in run responses.
- Made the structured attack contract explicit throughout NuSMV generation and automatic
  fix orchestration. Generator, main-module, specification, and fixer entry points no
  longer accept a separate attack boolean and budget; callers supply `AttackScenarioDto`.
  Request DTOs require a non-null scenario and explicit mode, while malformed or absent
  scenarios now fail instead of silently becoming `NONE`.
- Removed implicit `MODEL_CHOICE` provenance for fuzz input events. Current producers and
  persisted findings must carry an explicit event source; missing evidence now fails closed.
- Made the semantic model fingerprint mandatory for every counterexample-search task and
  run, removing the old count-only history comparison for rows with missing fingerprints.
- Removed persisted-trace compatibility readers that invented empty event arrays, unknown
  token provenance, model semantics from scalar columns, or template provenance from an
  unversioned manifest map. Persisted verification and simulation evidence now fails closed
  when any of those current fields or its verification-run owner is missing.
- Made automatic-fix replay require a complete current versioned template snapshot: manifest
  and device-source keys must exactly match the saved verification request, and every device
  source must be explicitly `BUNDLED` or `CUSTOM`. Corrupt persisted context now stops with a
  data-integrity failure instead of assigning `UNKNOWN` provenance.
- Made chat `turnId` a required request field. The frontend transport always supplies one,
  and the backend no longer invents correlation identity for an obsolete request shape.
- Removed legacy local timestamp parsing and the NuSMV trace parser's implicit `device.state`
  interpretation. API `LocalDateTime` requests now require a configured-zone offset, and a
  trace field named `state` is handled only through the current device declaration.
- Removed the API-level `Assignments` template field and invalid state-machine fallback.
  APIs now express only state transitions, while partial stateful templates fail closed and
  stateless templates retain their explicit non-semantic canvas placeholder.
- Made automatic-fix fault localization depend only on frozen per-transition rule-execution
  probes. It no longer reconstructs firings heuristically from current rule conditions or
  coincidental device-state changes, and unexpected device-map failures now propagate instead
  of being mislabeled as an unknown device.

#### Fixed
- Rejected device-type rows whose bundled/custom provenance is missing instead of
  treating a null `defaultTemplate` value as bundled and localizing custom model tokens.
- Rejected contradictory persisted simulation tasks before API mapping, including missing
  lifecycle fields, invalid step counts, failed tasks without an error, completed tasks
  without a saved trace, and non-completed tasks that claim one.
- Locked the specification editor while a create request is unresolved and submitted a
  detached draft snapshot, so a fast next edit cannot mutate the in-flight request or be
  erased by the previous request's success reset.
- Rejected verification and simulation model-context JSON whose required integer or
  boolean fields are missing or encoded as coercible strings/decimals. Persisted enum and
  selected-attack-point scalars must also retain their canonical JSON types instead of
  being silently reinterpreted by Jackson.
- Made Board renames compare-and-set against the name shown when the dialog opened. A stale
  edit or a different device claiming the requested case-insensitive name now returns
  `409 Conflict` before any write; the client refreshes the full semantic snapshot, preserves
  the user's draft for explicit resubmission, recognizes an already-achieved rename, and
  validates the returned old name, new name, and affected-specification count before showing
  success. Open rename dialogs now rebind to external snapshots without discarding a dirty
  draft or its original compare-and-set baseline, adopt an external rename when untouched,
  and close when the device was deleted, including after an in-flight request settles.
- Made Board device nodes theme-aware and responsive to their rendered size, kept the
  canvas camera stable when opening ordinary device details, distinguished stateless
  devices from manifest-defined states, and added pointer- and keyboard-safe move/resize
  behavior. Server snapshots now preserve every pending or active node layout instead of
  rolling back a second drag, pointer cancellation cannot leave canvas/minimap panning
  stuck, pointer resize handles collapse to one corner before disappearing when a low-zoom
  node cannot retain a 44-pixel central drag target while keyboard resize remains available,
  and the dark-theme device context menu stays inside the viewport
  with complete keyboard focus, navigation, and restoration behavior. Runtime and
  private-data badges remain readable in dark mode; hidden, inert, collapsed, or detached
  controls cannot capture modal focus; and device dialogs return focus to the originating
  node or a stable scene control even after failed or completed deletion paths.
- Preserved dirty device-runtime drafts across foreground and cross-tab snapshots with a
  field-level three-way merge: disjoint local/server edits combine automatically, while
  divergent edits to the same field block Save until the user chooses the latest value or
  keeps the local value. Normalized equivalent nullable/omitted runtime values and prevented late save/delete responses from
  reopening a closed dialog or accepting repeated destructive submissions. Stale device
  dialogs and context menus now reconcile to the authoritative node or close when it was
  deleted. A save-start edit revision also preserves input made while a runtime save is in
  flight, including the ambiguous case where the user deliberately returns to the old value;
  the authoritative baseline advances even while that draft is preserved, preventing a later
  refresh from overwriting it. Save acknowledgements now compare the exact submitted draft
  with the server-canonical, template-materialized result, so clearing an override adopts the
  template default while edits made after Save remain local. Replacing a same-id device with
  a different runtime schema
  resets the edit session instead of mixing old fields into the new template. Correctly
  scoped dark-theme selectors now keep the complete device-details surface, runtime editor,
  controls, labels, Markdown code blocks, and information-tooltip triggers readable instead
  of leaving teleported or scoped component surfaces in their light palette.
  Device deletion previews are now single-flight, visibly identify their target, close stale
  details, menus, and confirmations after a cross-tab deletion, report that external removal
  once, restore keyboard focus to the visible Devices tab, and reconcile an already-deleted
  response without showing a contradictory failure. Every entry path now opens a cancellable
  loading confirmation, suspends the underlying details surface without discarding its draft,
  rejects malformed nested preview/delete responses, and serializes preview reads behind
  pending Board writes. Changes to the target or its actual rule/specification/environment
  impact close the stale confirmation while unrelated Board edits do not; repeated final
  confirmation still emits only one delete request. When that automatic invalidation came
  from Device Details and the same device still exists, the details resume from the current
  snapshot; cancellation, submitted deletion, and non-dialog entry paths do not force it open.
  Rule and specification deletion
  confirmations now also bind to the exact scene generation and item snapshot, so a scene
  replacement or same-id external edit cannot redirect the approved delete to newer content.
  Runtime saves now send both the edit baseline and desired complete value; the backend
  compares the baseline under the per-user database write lock and returns an explicit
  `DEVICE_RUNTIME_STALE` conflict before writing when another tab changed the device. Runtime
  template names, runtime values, variable/privacy names, and security labels are now
  canonicalized at every complete device-write boundary. A runtime no-op also repairs a
  non-canonical stored representation instead of reporting an unchanged value that remains dirty.
- Localized bundled model tokens at the display boundary without changing canonical model
  values, completed compact and short-viewport drawer behavior, improved floating-panel
  focus and Escape handling, and added dark-theme surfaces for device, verification,
  simulation, fuzzing, and fix-result dialogs. Scene-import manifest validation now presents
  structured reasons in the selected UI language. Default-template reset previews now enumerate
  exact Environment Pool changes, localize each old/new value only from server-confirmed
  bundled provenance, and warn when verification and simulation must be rerun.
  Device API names and states, recommendation details, and automatic-fix conditions now
  localize bundled model tokens and `in`/`not in` relations while preserving custom model
  tokens verbatim. Every token in the bundled device manifests is covered by a data-driven
  Chinese/English localization regression, while custom `workingState` values remain visibly
  distinct from bundled provenance.
- Made fault-localization and automatic-fix token localization provenance-safe: new traces
  freeze bundled/custom source per device, entries without provenance stay unknown and preserve
  raw text, shared environment values require unanimous source provenance, and parameter,
  condition, action, and end-state labels no longer translate custom tokens that happen to
  collide with bundled names. Counterexample-search tasks now persist frozen model input and
  server-only provenance atomically in one strict, versioned envelope. Unsupported,
  incomplete, or unversioned development data and findings with missing or conflicting
  source fields are rejected instead of inferred from the current template catalog. Findings
  emit the source on device, local-variable, and shared-environment finding states. Canvas
  playback now derives state-machine presence, device
  state, variable labels/values, and security badges only from that frozen trace evidence;
  changing the current template cannot relabel historical nodes. Fuzz domain previews also
  normalize Board and model device ids before applying bundled-token localization, including
  generated UUID ids whose hyphens become NuSMV underscores. Verification, simulation, and
  counterexample-search replay now share nested trace validation for device/variable values,
  triggered-rule snapshots, trust/privacy labels, and token provenance, so malformed evidence
  is rejected before rendering or localization. The shared validator accepts legitimate
  stateless empty state/mode values but rejects duplicate identities, trust/privacy evidence
  in the wrong list, incomplete device or variable membership, local/global provenance
  mismatches, missing/null provenance, and device or environment provenance that changes
  between saved states.
- Stopped chat, verification, simulation, and counterexample-search workers after a full
  lease TTL without database confirmation; enforced stored-run quotas for synchronous
  verification and saved simulation; and bounded verification and simulation history
  summaries by their configured stored-run quotas with bounded result sets. Formal
  and simulation playback now rejects non-contiguous or non-one-based persisted states, and
  trace placeholders that are damaged or do not explicitly confirm availability no longer
  inflate the replayable counterexample count in verification run summaries or details. Task lease renewal now locks each row before
  sampling the database clock, so lock wait cannot revive or locally confirm an expired lease, and
  synchronous result transactions recheck distributed admission immediately before commit.
  Long AI tool transactions no longer block chat lease heartbeats, ordinary AI writes recheck
  ownership immediately before commit, and a heartbeat delayed behind a row lock cannot
  revive a lease that expired while it waited. Redis admission acquisition and renewal now
  measure confirmation from before each round trip, so a delayed response cannot extend the
  local validity window beyond the distributed TTL.
- Bounded process-local authentication rate-limit state with a fail-closed active-window
  ceiling. Capacity exhaustion now has its own `CAPACITY` scope, reports the earliest tracked
  expiry instead of a misleading full-window delay, and shows localized busy feedback instead
  of blaming an account or source. Cross-account custom-template preview/delete lookups now
  return the same not-found response as an absent template.
- Rejected ambiguous legacy phone/username logins when the supplied password matches both
  colliding accounts, instead of selecting the phone-owned account by lookup order.
- Replaced scenario recommendation's model-authored rationale with a deterministic summary
  of the final retained scene, and separated structural verification readiness from
  semantic-coverage warnings for filtered candidates, missing automation rules, and
  devices unused by every retained rule/specification. A submit-ready draft therefore no
  longer implies that it satisfies the user's natural-language requirement or forms a
  closed automation loop.
- Made zero-tool assistant replies explicitly state that no platform tool ran and no current
  Board state was read or changed. Provider prose on every turn is sentence-buffered before
  display; explicit claims of tool evidence or current-platform completion/mutation are replaced
  with exact successful server tool records, or a deterministic warning when none exists,
  instead of being streamed or persisted. All later provider prose is suppressed and an
  otherwise completed turn is downgraded to `PARTIAL`. API documentation, historical
  descriptions, and ordinary sample content are not treated as platform mutations.
  Zero-tool replies
  now persist as the existing `PARTIAL` status and display as "No platform tools ran" only
  when a non-empty trace has no tool execution or result activity; a missing trace or a tool
  that started without a result remains explicitly partial. `COMPLETED` is reserved for turns
  that actually invoked at least one platform tool, without implying that every user objective
  completed. Structurally valid but
  semantically incomplete scenario recommendations
  persist and report `PARTIAL` instead of being presented as fully successful. The
  standalone scenario-recommendation REST
  contract now carries the same objective status and issues, and both backend and frontend
  recompute them from the returned canonical scene instead of trusting inconsistent model
  claims. Chat history and live progress are structurally validated at the frontend boundary;
  malformed roles, content, cursors, counters, stages, outcomes, or execution traces are
  rejected instead of reaching rendering and being mistaken for a verified terminal state.
  Terminal assistant persistence is now single-attempt and fail-visible: trace serialization
  failures or database write failures emit an explicit SSE error instead of normal completion
  or a second misleading disconnect row. Chat history restores only explicit, structurally
  valid persisted traces; a `COMPLETED` row with missing, damaged, guarded, or non-usable trace
  evidence has its status cleared. Empty or malformed AI-tool result objects fail closed rather
  than increasing the successful-step count.
- Kept Board account actions reachable at tablet widths, made the permanent-account
  deletion dialog scroll within short viewports, and stopped Tab or other navigation keys
  from satisfying its deliberate-edit guard. Long canvas labels now expose their full text
  on hover, the mobile brand target remains at least 44 pixels, and the action dock scrolls
  to every command in short landscape viewports while its mode control announces whether
  labels are expanded.
  Refreshed the real browser check to use the current scene-replacement, layered-history,
  simulation, and eight-tool contracts and to fail on page exceptions.

### 2026-07-22

#### Changed
- Added one explicit application time-zone contract (`IOT_VERIFY_TIME_ZONE`, default
  `Asia/Shanghai`): API date-times now include the configured UTC offset, while legacy
  offset-free request values remain accepted and mismatched supplied offsets are rejected.
- Changed chat history to a cursor-paged contract with bounded raw-row scanning and an
  in-panel "load older" workflow. Chat catalogs now retain at most 100 sessions per user,
  while individual requests remain limited to 10,000 characters.
- Added a dedicated 64 MiB authenticated scene-replacement boundary so portable scenes
  can round-trip referenced template snapshots and embedded icons without widening the
  4 MiB limit for unrelated JSON endpoints. Device-type catalogs and manifest arrays now
  have explicit capacity limits.

#### Fixed
- Enforced formal-operation admission at the service boundary for REST and assistant-tool
  callers, including compatibility fix-recomputation paths; cancelled nested solver work on
  interruption, and terminated descendant NuSMV processes so cancelled or lease-lost work cannot
  continue consuming formal capacity.
- Made interactive recommendation and automatic-fix status/cancellation token-fenced and
  observable across backend instances, including renewable cancellation ownership and
  stale-request-id protection; browser stop recovery now tolerates registration races but
  exits with a visible uncertainty warning after bounded status failures. Redis status
  scripts now use single-field `HSET` calls so older development servers keep distributed
  tracking instead of rejecting the registry script and silently falling back to local state.
- Kept asynchronous simulation completion in the user-lock-before-task-lock order used by
  account deletion, preventing completion and deletion from deadlocking or persisting a
  trace for a deleted account.
- Made method/media negotiation errors deterministic (`405`, empty `406`, and `415`),
  separated case-sensitive authentication rate buckets, rejected phone-shaped new
  usernames, resolved legacy phone/username login collisions by the matching password,
  and made invalid-login messages account-neutral for both identifier types.
- Added recoverable chat-history loading and single-flight session creation, atomic
  cross-tab authentication snapshots, account-scoped workspace/assistant remounting, and
  request-token-bound `401` handling so a late response cannot expose or sign out the next
  account; also added explicit account-deletion confirmation typing and bounded
  completed-task/fix/recommendation recovery from transient backend failures.
- Treat account-deletion network failures and `5xx` responses as an unknown commit outcome:
  the client now clears only the matching local session and asks the user to sign in again
  to confirm whether the account still exists, while explicit `4xx` rejections remain
  correctable in the open deletion dialog.
- Sanitized AI Markdown links and disabled raw HTML, blocked automatic third-party image
  requests, removed dynamic template-icon HTML rendering, and added a deployment CSP
  example as defense in depth.
- Made chat stop requests close the active OpenAI stream or cancel a pending planning
  future instead of waiting for another provider chunk or timeout.
- Bounded AI tool calls, UTF-8 result size, and stored messages per conversation; detailed
  template results now exclude UI-only icons and oversized results become structured
  unavailable outcomes.
- Canonicalized usernames across registration, login, account confirmation, and throttling;
  validation now counts Unicode code points and rejects invisible formatting controls.
- Made the documented MySQL username collation case- and accent-sensitive, with an
  idempotent startup migration that changes only `app_user.username`; fresh and upgraded
  databases retain the same schema-level comparison semantics for every other text column.
- Restored standalone scenario recommendations by keeping chat-only draft metadata out of
  the strict REST response, and stopped read-only rule/specification recommendations from
  causing unnecessary cross-tab Board reloads.
- Serialized specification recommendations with one canonical `aConditions` property,
  removing the case-different `aconditions` duplicate that broke case-insensitive JSON
  clients.
- Preserved the original JSON status and error envelope for synchronous chat-stream
  rejections when standards-compliant SSE clients send `Accept: text/event-stream`, instead
  of allowing internal error dispatch to misreport business errors as `401`.
- Excluded credentials, provider keys, private chat payloads, and destructive-action
  capability tokens from automatic string logging; provider failures no longer log external
  response bodies, including cancellation paths, and model-response validation logs retain
  only candidate indexes, stable reason codes, and exception classes rather than generated
  or user-derived values.
- Enforced durable AI workflow-state ownership with a composite chat-session cascade and
  startup orphan repair, and removed inactive NuSMV lock markers left behind after direct
  temporary-directory cleanup.
- Distinguished valid stateless device placeholders from broken stateful-template fallback,
  so no-mode device creation no longer reports a false initialization warning.
- Self-hosted application and Material icon fonts, removing the Google Fonts dependency.
- Removed an unused Bouncy Castle runtime dependency from the backend package.
- Removed unused frontend UI and legacy Markdown dependencies and their inactive resolver.
- Restricted custom template icons to self-contained `data:image` values.
- Corrected nested template/chat controls and completed keyboard focus handling for
  template confirmations and verification results.
- Updated pinned GitHub Actions dependencies to their Node.js 24-based releases, removing
  the hosted-runner deprecation warnings while retaining immutable commit pinning.
- Made missing NuSMV model validation deterministic before artifact locking, keeping the
  Linux and Windows test paths aligned while retaining the post-lock race check. CI and E2E
  account probes now use credentials that match the current registration contract.
- Aligned interactive recommendation and automatic-fix request-id validation across DTOs,
  controllers, and execution services, and preserved accepted chat turns when a successful
  HTTP response later exposes no readable SSE body.
- Added visible feedback when explicit chat-session creation fails or returns an incomplete
  response, kept the mobile session list open on failure, and removed duplicate serialization
  of large portable scenes during export.
- Stopped an old synchronous or assistant worker when its Redis admission lease is lost or
  remains unconfirmed through its TTL, preventing it from overlapping a replacement worker.
- Replaced line-based NuSMV process output reads with fixed-size byte draining, so one
  unterminated output line cannot allocate memory outside the configured retention bound.
- Removed optimistic chat turns when the server rejects a request before SSE acceptance,
  restored ordinary drafts, preserved pending protected confirmations, and added a
  frontend/backend session-id and model-scalar validation boundary.
- Bounded verification/simulation task exclusion lists and interactive fix request ids,
  and aligned the frontend, backend, reverse-proxy example, tests, and API documentation
  with the resulting contracts.

### 2026-07-21

#### Added
- Added server-authoritative protected-action discovery and structured assistant
  confirmation buttons. Destructive, bundled-default reset, and full-scene replacement
  authority can no longer be inferred from model-classified natural language.
- Added bounded HTTP/model collections, browser import sizes, NuSMV output retention and
  diagnostic-directory cleanup, authentication attempt limits, stronger registration
  password bounds, and Redis-coordinated per-user admission for synchronous formal work
  and assistant streams.

#### Changed
- Changed standalone rule and specification recommendation requests from query-bearing
  `GET` calls to typed JSON `POST` bodies, so user requirements are not exposed in URLs.
- Updated the production reverse-proxy example to terminate TLS, redirect HTTP, enable
  HSTS, forward HTTPS metadata, and enforce the same 4 MiB request limit as the backend.

#### Fixed
- Updated the real-backend recommendation journey for JSON `POST` requests; authentication
  throttling now uses low per-account/phone limits plus higher source ceilings, returns
  structured retry data through CORS, and no longer locks ordinary users behind one NAT
  after a handful of unrelated attempts.
- Completed frontend capacity enforcement for drag/drop, recommendation, nested-condition,
  runtime-override, and scene-import paths. Early layout interactions are now persisted after
  hydration and the one-shot protection flags no longer break later responsive restoration.
- Added structured formal/chat admission reason codes and localized conflict feedback, kept
  protected-action controls recoverable after a transient confirmation-state failure, and
  held NuSMV artifact exclusion atomically from cleanup inspection through deletion.
- Prevented delayed Board layout hydration from overwriting zoom or pan changes made while
  the initial layout request was still in flight. Device-template manifests now use a
  `LONGTEXT` column so valid 256 KiB icon payloads fit the persistence schema, and durable
  assistant state is removed inside the account-deletion transaction.
- Moved JSON body buffering behind authentication, applied public authentication throttling
  after bounded DTO validation, and enforced Board-wide device/rule/spec/environment totals in the same transaction as
  targeted creation, and kept legacy over-limit data deletable instead of truncating it.
- Renewed active Redis per-user admission leases with owner-token checks and guaranteed
  chat slot release even when database execution cleanup fails.
- Protected active NuSMV diagnostic directories with cross-process file locks, and moved
  assistant confirmation controls into the normal flex layout so multiple confirmations
  and expanded input cannot cover conversation content.
- Updated the frontend lockfile to patched dependency versions; `npm audit` now reports no
  known vulnerabilities.
- Classified an empty current Board template set as confirmed automatic-fix model drift
  when the verification snapshot contains templates. Apply now rejects with `400` and asks
  for a new verification instead of returning a retryable preflight `503`. The global API
  error map and NuSMV candidate-generation contract are now synchronized with the code.

### 2026-07-20

#### Added
- Added a real-NuSMV three-strategy acceptance matrix and live browser coverage for
  parameter threshold repair, occupancy guard addition, and permanent rule removal. Each
  flow now asserts a complete counterexample model, the exact user-visible edit, signed
  apply, persistence reload, and a complete post-repair verification.
- Added a combined-scene regression where one counterexample yields all three verified
  strategies, plus explicit `UNKNOWN_SPEC` gate coverage and sequential frontend result
  merging across parameter, condition, and removal searches.
- Added real-NuSMV interaction regressions for inverse duplicate boundaries, redundant
  same-command rules requiring coordinated edits or pair removal, environment-only
  counterexamples with no rule root cause, and an unrepairable numeric upper-bound case
  where condition and removal strategies must still complete. Full-stack browser coverage
  now independently applies, reloads, and re-verifies multi-item coordinated suggestions
  for all three strategies through the live REST API and database.
  Signed-token round-trip regressions additionally prove that every hidden locator in a
  multi-item parameter, condition, or removal proposal is restored and applied.

#### Fixed
- Unified asynchronous task creation, progress, lease, and terminal timestamps on the
  microsecond database clock. Progress writes now require the live owning lease, while
  completion and evidence persistence lock the task row before sampling terminal time;
  fast tasks and workers delayed behind a row lock can no longer produce inverted
  timestamps or commit after their lease expires.
- Extended chat execution fencing to verification/simulation task creation, cancellation,
  synchronous verification-history persistence, and trace deletion. Account deletion now
  clears durable AI session state in independent post-commit transactions and isolates each
  cleanup stage so one failure cannot skip the remaining cleanup.
- Added same-user Board invalidation over `BroadcastChannel` for successful semantic
  mutations and assistant refresh commands. Tabs coalesce refreshes through the existing
  mutation queue, retain invalidations received while hidden, and still reconcile on focus
  for browser compatibility.
- Added route and assistant subtree error boundaries with localized retry and page-reload
  recovery, preventing one render failure from replacing the entire application with a
  blank screen.
- Fixed automatic-fix condition search so disabled free-value candidates are excluded by
  semantic assignment, same-shape policy literals share one candidate, and command outcome
  values are pruned without discarding the remaining valid value domain. One-condition
  additions/removals are now tried before unrestricted joint edits.
- Added suggestion-independent numeric parameter targets and accurate preferred-range match
  reporting. The Board can refine ranges after a search finds no suggestion, and unrelated
  same-named device attributes no longer bias the bounded near-value probe.
- Prevented automatic-fix search from certifying parameter or condition edits that would be
  rejected by Board persistence as an identical rule. Integer policy-boundary probes now
  include adjacent values and continue past duplicate boundaries without spending a NuSMV
  attempt, avoiding an unnecessary joint-solver timeout and returning an applicable edit.
- Added bounded coordinated parameter and condition probes for redundant rules issuing the
  same command, avoiding expensive unrestricted Cartesian solving when several thresholds or
  guards must move together. Permanent removal now expands through the violated specification
  so a dormant lower-priority rule cannot take over after the localized rule is deleted.
- Preserved exact device as well as automation-link attack points during every fix candidate
  generation, preventing rule removal from certifying a model after silently disabling a
  selected actuator attack variable.
- Serialized Board mount, retry, and cross-tab semantic snapshot reads through one refresh
  coordinator, so a delayed initial response cannot suppress a queued invalidation refresh.
  Returning from a hidden tab now consumes its deferred invalidation with one snapshot read
  instead of scheduling a redundant second refresh.
- Distinguished NuSMV execution or result-parsing failures and truncated candidate searches
  from a completed automatic-fix search with no verified suggestion. Strategy attempts now
  expose their main-search count and limit, and the Board explains both incomplete outcomes.
- Aligned coordinated parameter/condition grouping with the Board's full command identity,
  including content device and content value, so unrelated payload commands are not repaired
  together. Parameter closest-value refinement now handles the full signed 32-bit domain
  without distance or bound overflow.
- Corrected boundary-effect messaging for parameter repairs: only impossible strict bounds
  are identified as making a rule unreachable, while reachable non-strict endpoints are not
  described as disabled. The fix dialog now shows that consequence directly and renders every
  removal item even when multiple distinct rules have the same display name.
- Cancelled in-flight automatic-fix requests when the dialog is hidden programmatically or
  unmounted, not only when its own close button is used. A failed best-effort server cancellation
  is now contained and logged after the local request is aborted instead of becoming an unhandled
  promise rejection.
- Aligned the frontend automatic-fix types and strict response validator with the backend's
  required condition display snapshots and always-present collection fields.

### 2026-07-19

#### Changed
- Automatic-fix parameter search now spends a bounded first phase on one-threshold,
  near-original repairs before joint tuple solving, and the Board can lock a suggested
  parameter at its original value for the next search. Multi-threshold scenes no longer
  depend entirely on arbitrary Cartesian-product witness order.
- Condition adjustment now models Salus's candidate-clause value `Y` as a NuSMV
  `FROZENVAR` for enum and numeric mode/variable domains. Added conditions carry the
  solver-selected value instead of blindly copying the violated policy's value.
- Automatic-fix parameter and condition discovery now reproduces the complete first
  counterexample state, including concrete attack choices, while final forward verification
  still checks the unconstrained complete model. Condition-search scope also includes shared
  environment domains and positive API-event candidates.
- Automatic-fix strategy attempts now distinguish candidate-model generation failure from a
  completed search with no verified suggestion. First-state replay derives required fields from
  the resolved model and fails closed on every missing device, value, property, environment, or
  attack choice.
- Fix apply UI and responses now distinguish fresh rechecking from reuse of signed verification
  evidence. Confirmed or unavailable template comparison disables apply, and retryable `503`
  preflight failures carry a stable reason code; unclassified infrastructure `503` responses
  remain outcome-uncertain and trigger board reconciliation.
- Chat session-list responses now expose authoritative execution activity. On reload or in
  another tab, the assistant automatically opens a running session, shows a live activity
  marker and reconnected status, retains a working Stop control, and reloads persisted
  history plus Board/run state when execution finishes.
- The assistant now refreshes cross-tab activity whenever its panel opens or the browser
  document returns to the foreground. A session already reported active is locked and
  monitored before history loading, so a history error cannot expose mutation controls.

#### Fixed
- Signed fix tokens now carry every hidden operation locator required by apply, including
  nonzero parameter/condition indices and condition-add device references. Public JSON
  round-trips can no longer collapse an applicable suggestion onto rule 0/condition 0.
- Parameterized fix generation now fails closed when a rule/specification is omitted or the
  negated specification cannot be translated, instead of silently searching a reduced model or
  substituting a trivially true property.
- Replaced the fixed two-hour chat execution lease with a configurable 30-second renewable
  lease, a 10-second execution-id-guarded heartbeat, and scheduled expired-row cleanup.
  Backend crashes no longer leave sessions busy for hours, while healthy long-running tool
  workflows renew their lease instead of aborting when the original deadline passes.
- Bound chat workers and controller cleanup to the exact acquired execution id and moved
  every lease comparison to the database clock. A delayed queued worker or older request
  cleanup can no longer start without ownership or clear a replacement execution lease.
- Reloaded terminal assistant history even when the separate Board/run reconciliation
  fails, while retaining the visible locked retry state for the failed reconciliation.
- Replaced verification/simulation startup-wide active-task failure with renewable
  per-instance database leases covering queued and running work. Rolling deployments now
  preserve healthy work on other instances, expired work is recovered as failed, and a
  queued task that loses ownership cannot later start. Counterexample-search start and
  renewal now also reject an already-expired lease instead of reviving abandoned work.
- Fenced verification, simulation, and counterexample-search success/failure commits by the
  current worker id and unexpired database-clock lease. An expired worker can no longer publish
  a terminal result before recovery maintenance runs; user cancellation remains authoritative.
- Fenced AI-originated Board transactions, chat messages, confirmation state, scene drafts,
  and task continuations by the exact live chat execution id. A replaced or expired worker can
  no longer mutate shared state or append terminal history after ownership changes.
- The Board now refreshes its full semantic snapshot through the mutation queue whenever its
  tab becomes visible or its window regains focus, reconciling changes made in another tab.

### 2026-07-18

#### Changed
- Separated persistent trust labels from per-run attack selection. Device templates now
  remain the default trust/privacy authority, ordinary device creation omits instance
  label overrides, and users can override or restore template defaults only from
  advanced device settings.
- Replaced the verification/simulation attack switch with structured run scenarios.
  Verification can either fix explicit device/rule-link points or exhaust every
  combination up to a budget; simulation requires explicit points and no longer chooses
  compromised points randomly. Results and histories persist and display the exact
  selection policy and selected points.
- Updated `verify_model*` and `simulate_model*` AI tools to use `attackMode`,
  `attackPoints`, and verification-only `attackBudget`, matching the Board workflow.
- Expanded the shared AI recommendation capability view with explicit per-mode values,
  API triggers, and behavior-derived descriptions for otherwise blank manifests,
  states, variables, transitions, APIs, and content. Each description now identifies
  whether it was declared by the template or derived from modeled facts.
- Made chat execution records durable across reloads. Assistant rows persist the exact
  emitted activity trace and elapsed time, including task resumption and execution-guard
  stops; missing execution evidence remains unavailable. Visible summaries are
  operation-aware and omit raw internal identifiers and provider exception text.
- Added ReAct-style user-visible reasoning summaries before tool actions. The summaries
  explain the current goal, observed facts, next action, and remaining work, are sanitized
  before streaming/persistence, retain enough context to explain multi-step decisions, and
  do not expose private hidden chain-of-thought.
- Persisted expiring AI continuation, scene-draft, and protected-action confirmation
  state in the shared database with scheduled cleanup and atomic single-use consumption.
  Normal backend restarts and load-balanced follow-up turns now preserve live pending work.
- Added explicit `COMPLETED`, `AWAITING_CONFIRMATION`, `PARTIAL`, `STOPPED`,
  `DISCONNECTED`, and `FAILED` chat terminal outcomes. Results now reflect failed,
  uncertain, guarded, and confirmation-pending tool work instead of treating every
  normally closed stream as completed. Explicit user stops are distinguished from transport
  failures, and history reconciliation keeps the local response when no matching terminal
  assistant record has reached persistence.
- Serialized chat execution across backend instances with an expiring database lease and
  distributed stop flags. Stopping now preserves the authoritative result of a tool that
  already returned, and per-turn ids prevent an older terminal reply from replacing the
  current local conversation.

#### Added
- Added the `reset_default_templates` AI tool. It previews the exact bundled-template and
  Environment Pool impact, requires a later action-specific confirmation, then uses the
  same atomic refresh authority as the Board UI while preserving custom templates.

#### Fixed
- Bounded isolated in-memory AI-state fallbacks, replaced unbounded per-session chat locks
  with fixed lock stripes, and made empty/invalid scenario recommendations report whether
  the previous valid draft remains active.
- Added compatible-provider support for explicitly safe reasoning-summary fields while
  continuing to ignore raw `reasoning_content` and other hidden-reasoning fields.
- Kept left-panel single-device creation on template-owned trust/privacy defaults unless
  the user explicitly selects an advanced override, and added a restore-to-template option
  for each state and local-variable label.
- Preserved an explicitly selected state trust/privacy override when the user changes the
  initial state; switching the state no longer silently discards an advanced setting, and
  the user can deliberately choose the template-default option when desired.
- Aligned the canvas exact-attack-point limit with the backend contract: selections above
  50 points are rejected before a run request is sent, with localized guidance.
- Rejected automatic-fix candidates that remove or duplicate an explicitly selected
  automation-link attack point, preventing forward verification from silently changing
  the counterexample's fixed attack scenario.
- Kept the in-flight assistant status compact even when it already contains execution
  activity; completed execution records remain full-width for review.
- Synchronized the climate-conflict example's Air Conditioner template snapshot with the
  corrected bundled public privacy labels, so strict full-scene import no longer rejects it.
- Added deterministic conjunction checks for AI-authored rules and specifications.
  Direct AI mutations, standalone recommendations, and full-scene recommendations now
  reject condition groups with no common legal state/value and rules whose target
  conditions cannot satisfy an action API's declared `StartState`. Ordinary user Board
  editing remains governed by the existing structural contract.
- Filtered AI recommendation conditions and command prestates that are provably unreachable
  from current runtime plus declared APIs, transitions, dynamics, and natural change, while
  retaining uncertain/open candidates through conservative over-approximation.
- Made deletion, default-template reset, and scene-replacement confirmations authorize only
  their matching protected action, with semantic classification performed by the configured
  model instead of fixed natural-language phrase matching.
- Made the AI default-template reset preview enumerate each Environment Pool change with
  its previous and current value, trust, and privacy instead of exposing only a count.
- Interpreted `_` and empty segments consistently as per-mode wildcards in authored state
  conditions across NuSMV generation, fault localization, and fix validation.
- Kept relation-free API event input valid across scenario recommendation and confirmed
  atomic Board replacement by canonicalizing omitted relation/value to explicit `= TRUE`
  before the strict persistence contract, matching standalone specification authoring.
- Completed scenario recommendation readiness propagation through the REST DTO and
  frontend parser. The controller now rejects a payload whose `verificationReady` or
  ordered `readinessIssues` disagree with the returned canonical scene.
- Corrected remaining built-in privacy labels for social posting, phone photo/upload
  activity, and door/window/garage contact or open-state facts, keeping routine idle and
  ordinary appliance states public.

### 2026-07-17

#### Changed
- Enabled the chat assistant to apply its latest validated full-scene recommendation
  through the same confirmed atomic Board replacement authority as UI scene import,
  rather than decomposing replacement into per-device deletions and additions.
- Replaced deterministic chat intent routing and keyword-selected tool subsets with
  model-driven planning over the complete AI tool catalog. The assistant can now answer
  current-scene count questions from `board_overview` and compose targeted device,
  environment, rule, and specification tools to complete an existing scene without
  turning it into a full-replacement draft.
- Made chat planning objective-oriented across confirmation boundaries. A destructive
  preview now pauses only its protected step; after explicit confirmation the assistant
  resumes the original multi-step task and can continue composing deletion, creation,
  rule, specification, simulation, and verification tools until another real boundary.
- Replaced the assistant's normal five-round planning cutoff with progress-aware
  continuation, exact repeated-call/result stagnation detection, and a configurable
  high emergency runaway guard. Long requests now retain full tool capability and still
  receive a model-written final explanation if a guard stops duplicate execution.
- Extended the default chat SSE lifetime to 60 minutes and added structured tool-result
  and execution-guard progress events with cumulative success, failure, and unconfirmed
  counts.
- Reworked the assistant trace into a full-width activity record with localized action
  names, visible confirmation points, per-step outcomes, cumulative result counts, and
  responsive desktop/mobile layouts instead of a narrow abstract text column.
- Defaulted Board verification and simulation to background execution while retaining
  the synchronous options, so users can leave the submit panel, observe progress, and
  cancel long work without reducing formal-analysis capability.

#### Added
- Added the `apply_scenario` AI tool and a server-side per-chat draft lifecycle. The
  assistant now previews current/replacement counts, waits for explicit confirmation,
  and then commits devices, shared environment values, rules, specifications, and
  required template snapshots in one transaction.
- Added an accumulating, bilingual execution trace inside the active assistant message.
  It shows context loading, planning rounds, tool starts and outcomes, retry decisions,
  and response generation, then remains available as a collapsible record after the
  response completes without presenting private model chain-of-thought.
- Added a 15-minute per-user, per-session task-continuation store and `TASK_RESUMED` SSE
  progress event carrying a bounded summary of the original user-authored objective.
- Added server-observed phase reporting for standalone AI recommendations, automatic-fix
  searches, and persisted verification/simulation/exploration tasks. The frontend now
  displays these real phases instead of guessing from elapsed time or showing an
  indefinite generic spinner.

#### Fixed
- Unified recommendation capability context across scenario, rule, specification, and
  related-device tools. Models now receive falsifiability, domains, natural change rates,
  working-state dynamics, autonomous transitions, API content/state behavior, content
  descriptions, and the current shared Environment Pool.
- Corrected AI-facing specification semantics to use the formal `AG`/`AF`/`AX`/`GF`
  definitions, including `AF` (not `EF`) for Eventually. Scenario recommendation results
  now expose `verificationReady` and structured `readinessIssues`; empty-spec drafts remain
  user-applicable but are clearly marked as not ready for verification.
- Made specification persistence canonicalize `propertyScope` and trust/privacy literals,
  including set-valued conditions, and stopped failed/empty scenario recommendations from
  deleting the previous valid draft.
- Added optional template content descriptions and aligned the built-in privacy defaults:
  routine appliance/environment facts are public, while location, activity, access,
  communication, financial, health, and personal-content facts remain private.
- Made pending AI confirmations follow the user's latest instruction instead of requiring
  an immediately adjacent one-word reply. Ordinary questions and task changes now preserve
  the preview, action-specific confirmations may include follow-up work, and a generic
  confirmation is rejected as ambiguous when several action kinds are pending.
- Replaced fixed confirmation phrase matching with model-based semantic interpretation
  scoped to the server-known pending action kinds; invalid or unavailable classification
  fails closed without authorizing a protected mutation.
- Preserved bounded user-authored task updates and sanitized pending tool output across a
  confirmation pause, allowing the assistant to resume the revised objective without
  exposing model reasoning or repeating a completed preview.
- Kept destructive confirmation executable when detailed tool results exceed the chat
  history character window by injecting the pending tool, target, and opaque token from
  server-side confirmation state. Scene application likewise keeps its draft and Board
  impact token outside model history, preventing repeated no-write preview loops.
- Corrected `add_device` planning guidance to use the authoritative `list_templates`
  catalog instead of claiming that `board_overview` lists available device types. Scene
  completion now obtains an exact template name before targeted creation rather than
  guessing or silently degrading the requested composition.
- Kept ordinary continuation requests distinct from protected-action authorization through
  the model's pending-action semantic classification rather than keyword routing.
- Repaired blank or repeated tool-call correlation ids returned by OpenAI-compatible
  providers before persistence and execution, keeping assistant calls and tool results
  protocol-complete instead of failing after a tool may already have run.
- Kept multi-call AI conversations provider-valid when an earlier call requires user
  confirmation or returns an unavailable result. Remaining same-round calls are now
  explicitly recorded as skipped without execution, so the final explanation no longer
  fails with a missing tool-output protocol error. History loading also omits older
  incomplete tool-call blocks so affected sessions recover on their next request.
- Made terminal background tasks display their completed, failed, or cancelled status
  instead of a stale last active phase, and prevented a closing automatic-fix request
  from stopping progress updates for a newly opened request.
- Retained completed interactive-operation status briefly so final progress polling no
  longer produces a spurious 404.

### 2026-07-16

#### Added
- Added a canonical current-Board exploration fingerprint endpoint and stored that
  fingerprint with completed exploration snapshots so history detects same-count model
  edits before replay or formal-verification handoff.
- Added atomic per-user active and stored task quotas for async verification and
  simulation, with configurable limits and structured HTTP 429 reason data.
- Added atomic `GET /api/board/snapshot` hydration so the first Board screen receives
  devices, templates, environment, rules, and specifications as one authoritative model
  snapshot while layout and task history load in parallel.
- Added request-scoped cancellation for standalone AI recommendations and automatic-fix
  searches, backed by bounded executors, server-side task interruption, and explicit progress
  displays with elapsed time and observable processing phases.
- Added non-persisted chat SSE progress frames for context loading, tool planning, tool
  execution, and visible-response generation.
- Added a contextual empty-canvas start state with direct device, AI-scene, and scene-import
  actions for newly registered users.

#### Changed
- Made narrow-screen Board layout transient and task-focused: side panels collapse,
  existing devices fit into view, scene commands move into a compact menu, touch targets
  remain at least 44px, and the saved wide-screen workspace is restored on expansion.
- Registration now returns `AuthResponseDto` with a JWT and the frontend enters the Board
  directly. Bundled default device definitions are parsed and schema-validated once per backend
  process before per-user transactional insertion.
- Signed each verified automatic-fix suggestion and changed apply to persist the exact proposal
  the user reviewed after atomic model-drift checks. Apply no longer repeats the full strategy
  search or silently substitutes a newly recomputed suggestion.
- Added an optional recommendation-specific LLM model, compacted scenario template prompts to
  capability projections, and logged prompt size, output size, and model latency.
- Revised authentication and recommendation wait states using Nielsen-style status visibility:
  action-specific loading text, persistent recoverable errors, explicit panel exit, and progress
  copy that does not guess private model phases from elapsed time.

#### Fixed
- Made rule creation, inspector device cards, delete actions, and copy controls keyboard-operable;
  copy failures now remain visible and recoverable. Rename and simulation dialogs have named
  controls, focus trapping, and Escape handling; reduced-motion mode now suppresses landing
  animation and video playback.
- Kept chat sessions locked when backend activity could not be confirmed, preventing a second
  interaction from starting while an earlier tool might still be running.
- Made AI rule/specification deletion compare the confirmed entity snapshot inside the same
  user-level transaction as deletion, closing the confirmation-to-delete race.
- Stopped blocked template-deletion conflicts from incorrectly requesting confirmation when no
  fresh confirmation token can be issued.
- Rejected expired or malformed JWTs before routing to the Board, avoiding a burst of initial
  authenticated requests that can only return `401`.
- Fixed a recommendation-state initialization order that could leave the Board completely blank
  immediately after registration, and restored parent read-only styling on the teleported Control
  Center component.
- Added accessible names to icon-only recommendation, simulation, and verification-result close
  controls and focused the first invalid authentication field after validation.
- Mapped missing required query parameters to structured `400 Bad Request` responses instead of
  reporting them as internal server failures, and aligned full-stack repair tests with request ids
  and exact signed-suggestion application.
- Removed a duplicate first-round chat planning progress event observed in the live SSE path.
- Restricted public security access to registration and login only, removed Spring Boot's
  generated default user from the JWT-only service, disabled Open Session in View, and updated
  Hibernate to detect its supported MySQL dialect from the live JDBC connection.
- Kept cancelled recommendation and automatic-fix request slots reserved until the underlying
  provider or checker call actually exits, and stopped a successful fuzz-run deletion from being
  misreported as failed when only the follow-up history refresh fails.
- Preserved the active recommendation request id when a stop call cannot reach the server, so the
  interface remains locked to the real operation and the user can retry stopping it.
- Disabled rule creation and similarity checks until an IF condition is explicitly added and the
  THEN action and optional content payload are complete, with a persistent inline reason.

### 2026-07-15

#### Added
- Added `POST /api/fuzz/workload/preview`, which calculates the same frozen-model
  complexity and 12,500,000-unit ceiling used at submission. The search panel now waits
  for this authoritative check, refreshes it when the Board or budget changes, and shows
  a retryable status instead of estimating from visible item counts.
- Added a server-authoritative semantic fingerprint to paper-domain previews and
  submissions. Paper runs now fail before task creation when devices, environment,
  rules, specifications, or referenced manifests changed after preview; canvas-only
  layout changes do not invalidate it.
- Added paper-mode device-local initial-value domains to preflight and frozen eligible
  specification labels to result history, so both upcoming randomization and historical
  no-finding targets remain understandable without relying on the current Board.
- Added inclusive numeric bounds to paper-domain preflight so large device-local and
  environment integer domains remain complete without expanding thousands of values.

#### Changed
- Bound every AI-assisted destructive deletion confirmation to one authenticated chat
  session, tool, target, and canonical preview digest through a 15-minute opaque token.
  Tokens are single-use, only one deletion can be pending per session, and device/template,
  rule/specification, and saved verification/simulation trace deletion now reject target
  drift, wrong-session use, and replay without writing.
- Added a full-stack CI suite with real MySQL, Redis, NuSMV, backend, and Chromium
  services. It now runs every Chromium E2E workflow, including account deletion, chat,
  portable-scene import, authority boundaries, counterexample search/replay, and formal
  verification, rather than only the counterexample journey.
- Deferred conditionally opened Board dialogs and overlays, including account actions,
  device/rule editors, simulation/fix views, counterexample search, results, history, and
  playback, until first use instead of including them in the initial Board route chunk.
- Replaced research-paper and internal algorithm terminology in counterexample-search
  screens with user-facing choices: Board initial state or random initial state with
  reproducible inputs. Capability disclosures now explain behavior and safety boundaries
  without exposing FSM/BFS/predecessor implementation names or the underlying verifier
  brand in the ordinary exploration workflow.
- Aligned paper-compatible mutation with the paper's position-first selection and the
  reference artifact's mutable initial vector. The 95% local branch can now modify one
  device state, device-local variable, or environment initial value without resampling
  unrelated initial targets; sparse overrides remain reproducible, while only the 5%
  fresh-random branch replaces the nonce. Mutation counts now use the artifact's rounded
  1%-10% formula with the existing 128-operation safety cap.
- Made the three-level, rule-produced state/mode/API scope of `GetPrevConditions`
  explicit as a stable limitation code instead of implying a general variable-producer
  graph.
- Exposed multi-specification per-target guidance as a localized paper-mode product
  extension instead of leaving the single-monitor difference only in architecture docs.

#### Fixed
- Made AI-tool refresh commands completion-aware across `ChatView`, `App`, and the
  current Board. Failed targeted refreshes now fall back to full Board/run-history
  reconciliation; a second failure presents a bilingual retry state and keeps assistant,
  scene-replacement, and trace-playback interactions locked instead of exposing stale UI.
- Bounded failed chat-activity checks with a dedicated 2.5-second timeout, settled active
  assistant work before sign-out (with explicit confirmation for unknown outcomes), and
  aligned SSE authentication handling with the REST client: `401` returns to login while
  `403` no longer logs out an authorized session.
- Stopped trace playback from rendering an empty automation card on every state. The
  summary now distinguishes observable device/environment changes from user automations,
  and the automation section appears only when the backend reports a rule that actually
  ran in that step.
- Preserved complete AI tool JSON Schemas when adapting vendor-neutral definitions to
  the OpenAI SDK. Root and nested `additionalProperties`, required fields, array-item
  schemas, and nested property constraints are no longer dropped before model calls.
- Prevented account deletion from leaving or recreating AI-chat rows: counterexample bulk
  cleanup now flushes earlier derived deletes before clearing JPA state, every chat write
  locks the active user and revalidates session ownership, and committed deletion stops local
  queued or in-flight SSE chat work. Idempotent startup integrity repair removes legacy orphan
  rows and installs cascade ownership constraints, so writes committed first are deleted with
  the account and writes arriving after deletion are rejected by the database.
- Kept keyboard focus inside the active workflow when opening a saved counterexample
  result from history, exposed selected history layers and filters to assistive technology,
  and removed per-user counterexample notification state after account deletion without
  allowing browser-storage failures to override a successful server deletion.
- Made counterexample response handling fail closed when finding evidence belongs to a
  different run or contradicts the frozen run budget, eligible targets, effective seed,
  trace length, uniqueness, model counts, or exact safe-integer workload product.
- Rejected impossible counterexample execution statistics across engine output,
  persistence/history mapping, and frontend responses, and enforced causal replay-event
  ordering by step and provenance before evidence can be stored or displayed.
- Restored portable-scene duplicate-name rejection for device-local variables, privacy
  overrides, and shared environment variables before any replacement preview is opened.
- Matched the HAFuzz reference aggregation for mixed direct conjunctions: a direct
  condition without a rule predecessor now contributes zero at the first predecessor
  level, while absent branches deeper in the rule chain remain omitted.
- Made paper-compatible environment Event interpretation domain-aware, so discrete
  direct values beginning with `rate:` are no longer misread as numeric deltas.
- Rejected engine and persisted finding prefixes whose state count exceeds the owning
  run's captured `pathLength`, including lightweight history projections.
- Made accepted background-task cancellation conservative across process boundaries:
  only work definitively removed from the local queue reports that no execution remains,
  while remote, racing, or already-running execution reports that it may still be stopping.
- Strengthened the counterexample-search workload guard with the engine's key operational
  cross-products, including environment/device traversal, device transition/API lookup,
  same-target rule arbitration, and specification predecessor search. High-interaction
  Boards are now rejected consistently by preflight and submission before they can monopolize
  the bounded executor.
- Rotated paper-compatible multi-target guidance parents across generations when the
  unresolved target count exceeds the population size, so later targets are not permanently
  excluded from mutation while preserving the per-offspring parent/fresh-random policy.
- Prevented Board teardown and logout races from issuing unauthenticated or user-visible
  late requests: pending layout saves are serialized, coalesced to the latest layout, and
  given a bounded best-effort flush while authenticated,
  recommendation requests are aborted on unmount, and task/history refreshes stop with the
  disposed workspace. Historical result and replay actions now honor the latest user choice,
  unread updates clear only for the history view the user still has open, and task-box
  cancel/dismiss actions are single-flight with consistent failure feedback.
- Preserved unrelated exploration errors when random-input preflight succeeds, made history
  and exploration controls meet touch-target sizing, exposed task progress semantics to
  assistive technology, and formatted result timestamps and workload counts in the selected
  application language.
- Disabled verification and simulation submission while Board collections are still loading,
  failed, or being replaced, and exposed the loading state directly on the primary action so
  an early click cannot be silently rejected before the run request is created.
- Stopped empty or ineligible Boards from being presented as counterexample-service failures:
  workload and random-input preflight now wait for a loaded device model and an eligible target,
  and the frontend accepts the backend's valid zero complexity for a structurally empty Board.
- Separated completed-run detail from list-only availability markers in the frontend contract
  and now reject a summary-only `dataAvailable` field on detail responses instead of hiding
  backend drift with a type assertion.
- Pinned the NuSMV archive checksum directly in both CI installation paths so the artifact and
  its integrity value are not fetched from the same mutable origin.
- Preserved precise, localized client-side scene-import validation messages for unknown
  and internal fields regardless of identifier language, mapped malformed JSON to a
  stable localized error, and continued to reduce backend diagnostics to safe item-level
  coordinates so users can identify and correct rejected portable-scene fields.
- Prevented stale task-inbox and verification, simulation, and counterexample-history
  responses from overwriting newer local or remote state or re-inserting deleted items,
  while keeping counterexample-history page append requests single-flight.
- Scoped specification persistence identity by `(id, user_id)` and added an idempotent
  startup migration for the legacy global primary key, preventing concurrent users who
  import the same scene from overwriting or losing each other's specifications.
- Unmounted cached workspace routes when users navigate away, so Board polling, task
  refresh, and global keyboard/resize listeners stop immediately after logout or route changes.
- Prevented the asynchronous Board startup sequence from installing polling or global
  listeners after users leave during initial loading.
- Kept counterexample-search panels to one scroll region, added an explicit retry for
  unavailable random-state input ranges, kept history headers/actions fixed above one
  scrolling body, restored launcher focus on close, labelled task progress/cancel controls,
  and enlarged primary controls for touch use.
- Added counterexample-search admission guards before large frozen-snapshot
  serialization: cross-instance atomic per-user active and total stored-task quotas
  (HTTP 429 with stable reason payloads) and a process-wide executor-capacity permit.
  Stored evidence is never silently evicted; users at the configured total must delete
  old history or failed/cancelled tasks. A final executor rejection now deletes the
  never-dispatched pending row instead of retaining an unreachable failed task with its
  snapshot.
- Removed every persisted-but-undispatched fuzz task when status rechecks or executor
  dispatch fail, instead of leaving an unknown `PENDING` row until lease expiry.
- Reclaimed local executor queue slots and capacity permits immediately when a queued
  counterexample search is cancelled, including account deletion, while retaining the
  permit until a running worker has actually stopped. Lease reconciliation now performs
  the same cleanup when cancellation was accepted by another backend instance.
- Kept the account-deletion task-stop hook proxyable so Spring transactional service
  proxies actually interrupt or dequeue local verification, simulation, and fuzz work.
- Added renewable per-instance leases for fuzz tasks, so startup and rolling deployments
  recover only expired work instead of failing another healthy instance's tasks. Lease
  decisions now use the database clock rather than each JVM's timezone or clock; worker
  initialization failures fail by task ID and always release local lifecycle state.
- Made account deletion defer token revocation and worker interruption until after the
  deletion transaction commits, preventing rollback from leaving a valid account with
  cancelled work or a revoked session.
- Bounded frozen snapshots and persisted finding evidence, counted every captured
  specification even under explicit target selection, and changed history lists to use
  lightweight finding projections while preserving full run-context validation on detail.
- Applied the frozen-snapshot byte ceiling to workload and paper-domain previews, rebuilt
  queued engine inputs from their persisted snapshots instead of retaining duplicate Board object graphs,
  and persisted async progress only when its visible percentage changes.
- Bounded eligibility labels and diagnostics before persistence, capped combined run metadata,
  and removed frozen finding specifications from list projections; history summaries now use
  an explicit label/count shape while full detail remains snapshot-validated.
- Made exploration eligibility structural and visible before submission: trust/privacy
  predicates are marked formal-only in the panel, while ordinary identifiers containing words
  such as `attack` or `privacy` no longer trigger unsupported semantics. Missing persisted
  exploration modes or required limitation disclosures now fail closed instead of being
  synthesized during history reads.
- Kept ineligible-target explanations on stable localized reason categories and stopped
  rendering backend diagnostic prose in the ordinary result dialog; persisted diagnostics
  remain available to authenticated API clients for development and support tooling.
- Enforced the finding-prefix invariant across engine output, persistence mapping, list
  summaries, and frontend response validation: every replay now ends exactly at its first
  violating state instead of accepting unexplained trailing states.
- Excluded presentation-only labels, descriptions, icons, formula previews, and rule text
  from paper-domain fingerprints while retaining executable Board semantics, including
  device and rule order where first-match transition priority depends on it.
- Restored the paper's binary atomic satisfaction rule, flattened nested
  conjunction/disjunction guards before aggregation, fixed negative API predecessor
  polarity and state-change guards, and prevented integer-range overflow in the
  remaining product-mode distance calculations.
- Made completed fuzz-result recovery single-flight and retry transient network,
  `408`/`425`/`429`, and server failures with bounded exponential backoff instead of
  marking the task failed. Inline result recovery now hands off after three failed reads
  so the main workflow cannot remain locked for hours; tracked background reconciliation
  continues independently. Historical target restoration now preserves the frozen scope,
  and unavailable explicit targets require a visible user decision rather than silently
  broadening the next run.
- Corrected paper trace formation so an environment rate affects its own Event position,
  replaces that step's free natural-rate choice, combines device impact once, and yields
  to explicit formal transition assignments. Discrete paper environments now remain
  stable without an Event or formal effect, and replay preserves causal Event/model-choice
  order.
- Separated the previous transition source from the Event-modified target state, so
  rules and formal guards read `s(i-1)` while writing `s(i)`, and advanced paper monitor
  FSMs online so violating prefixes stop path formation immediately.
- Corrected `GetPrevConditions` priority guidance: only explicitly written modes count
  as action outputs, earlier overlapping rule guards block lower actions, and synthetic
  arbitration guards no longer recurse as if they were model-produced conditions.
- Removed the 1,000-value sampling loss from numeric paper domains and fixed direct-value
  numeric mutation at single-value bounds.
- Canonicalized enum-backed environment rates to `rate:<integer>` before seed formation,
  preventing a valid rate from being applied as an absolute value, and made enum-backed
  mutation uniform across all legal alternatives after excluding the current value.
- Invalidated stale paper previews across semantic and path changes, blocked submission
  without a current preview, validated the complete limitation-code contract, displayed
  local domains and device labels, and preserved an historical implicit-all target set
  when restoring settings.
- Stopped retrying permanently unavailable fuzz tasks: response-contract failures and
  explicit 4xx responses are now untracked and surfaced in result history, while only
  network failures and 5xx responses remain retryable. Stale-fingerprint rejection now
  refreshes inline without leaving a covered or persistent error.

### 2026-07-14

#### Added
- Added a clean-room, HAFuzz-inspired bounded counterexample exploration module as a
  supporting workflow before formal verification. It captures an immutable Board
  snapshot, runs deterministic seed-based finite-path search in a dedicated background
  pool, and persists independent task/run/finding history with replayable candidate paths.
- Added Board controls, global task status, combined history, bilingual result surfaces,
  eligibility/limitation reporting, reproducible seeds, cancellation, lazy finding
  playback, and a formal-verification handoff for exploration results.
- Added `/api/fuzz` task, run, and finding endpoints plus dedicated API and architecture
  documentation. The finite monitor supports specification templates 1, 3, and 4; other
  templates and attack/trust/privacy/content semantics fail closed as ineligible.
- Added a persisted `explorationMode` contract for bounded exploration. `BOARD_SNAPSHOT`
  remains the default product workflow; optional `PAPER_COMPATIBLE` uses HAFuzz-style
  event tuples, random legal initial states, explicit monitor FSM/BFS guidance,
  predecessor-condition weighting, and the paper's 95% mutation / 5% random-seed split.
  The mode is returned by task and run history and remains a templates 1/3/4,
  integer-domain subset rather than a complete paper reproduction.
- Added a read-only paper-mode input-domain preview backed by the executable server
  model. It exposes legal device initial states, environment initial values, Event
  values, and the per-seed initialization policy without creating a task or pretending
  that one concrete random initial state exists before candidate generation.
- Added explicit `historyPersistence` metadata to synchronous verification and
  simulation responses, separating a completed formal/model result from whether its
  run-history write was saved, failed, not requested, or has an unknown outcome.
- Added lightweight counterexample summaries under verification-run history and
  unavailable-row placeholders, so one damaged persisted result no longer prevents the
  user from loading other history.
- Added per-session Smart Assistant activity status and busy conflicts. Stopped browser
  streams now wait for server work to settle before conversation switching, deletion,
  or another assistant mutation.
- Added token-bound device-template deletion previews with itemized device-instance
  blockers for REST, frontend, and AI-tool callers.

#### Changed
- Kept NuSMV verification as the only proof and automatic-fix authority. Exploration
  budget exhaustion is presented neutrally rather than as satisfaction, and heuristic
  findings remain separate from formal counterexample `trace` rows.
- Bounded exploration now applies one server-authoritative workload cap across path
  budgets, frozen device/rule counts, and effective target specifications; task and run
  history reads are paged so persisted evidence cannot create unbounded list queries.
- Made persisted model-bearing JSON fail closed. Missing, blank, malformed, or unknown
  rule/spec/template/trace/task fields are no longer reinterpreted as empty/default
  semantics, and a database template name cannot silently overwrite a different
  `manifest.Name`.
- Moved full verification traces and simulation states behind on-demand history detail
  loading. History lists now carry only the fields needed to explain and select a run.
- Made async task progress reach 100 only with the atomic final-result persistence
  operation, preventing a visible 100% state without a committed result.

#### Fixed
- Hardened bounded counterexample exploration after the paper and interaction review:
  search guidance now preserves a best candidate per unresolved specification instead
  of averaging opposing goals, mutates only choices that affected the generated path,
  periodically restarts even a single-member population, and responds to cancellation
  during path simulation.
- Bound exploration work to the frozen model's structural complexity and validate
  persisted run eligibility and findings against that same frozen input. Malformed,
  unsupported, or cross-snapshot evidence now fails closed instead of appearing
  replayable.
- Made exploration results causally inspectable and keyboard accessible. User-facing
  steps are consistently one-based, replay distinguishes injected inputs from rule and
  state changes, and paper-mode replay identifies random initialization, seed Events,
  device states, environment rates, and ordinary model choices separately. Dialogs trap
  and restore focus, unsupported targets are identified
  before submission, budget fields report validation errors without silent rewriting,
  and completed background results remain visible until reviewed.
- Closed the exploration-to-verification workflow gaps: all stable paper limitation
  codes are localized, multi-target results remain itemized, reproduction settings can
  be restored without auto-running or broadening targets, and the verification handoff
  persistently states that NuSMV checks the complete current Board rather than the
  historical random state, Event sequence, or snapshot. Budget-exhausted completion is
  no longer shown as a green success.
- Made paper-domain preview capture compatible with MySQL's locking rules and suppressed
  late background-completion notices when that same run is already open, preventing a
  database error in preflight and a notification from covering the result title.
- Disabled the exploration entry while an atomic scene replacement or clear is still
  committing, preventing a late import-success message from covering the newly opened
  exploration panel.
- Preserved completed verification conclusions and playable simulation trajectories
  when their separate history save cannot be confirmed, while showing an explicit
  outcome-unknown warning and reconciling history instead of claiming success.
- Returned the current structured template-deletion preview from AI-tool stale/blocked
  conflicts instead of collapsing the reason into a generic business error.
- Bounded Smart Assistant activity-query failures so a network outage cannot leave the
  interface locked for the full stream timeout.

### 2026-07-13

#### Added
- Added three self-contained standard-scene examples generated from bundled default
  templates: fire evacuation, conflicting climate control, and RFID access. Real NuSMV
  and Playwright regressions lock their importability, baseline/attack outcomes,
  animatable simulations, and the verified removals for the two intentionally unsafe
  scenes.
- Added `docs/examples/multi-violation-repair-scene.json`, a self-contained default-template
  scene with two baseline violations sharing one root automation and a verified single-rule
  repair path.

#### Changed
- Mapped the persisted environment-variable value to the non-reserved
  `variable_value` database column and added an H2 repository regression test, so schema
  creation can no longer log a hidden failure while the broader application-context test
  still reports success.
- Renamed the physical authentication table from the reserved identifier `user` to
  `app_user` and added a repository write/read regression test. The REST authentication
  contract and user-facing account model are unchanged.
- Kept raw model-checker execution logs in simulation run details when a run produces no
  states; the primary failure notice now uses a localized user-facing explanation instead
  of exposing the final technical log line.
- Replaced the three peer run-history tabs with a two-layer model: Task Status now
  contains only active, failed, or cancelled background work, while History Results
  contains one entry per completed verification or saved simulation. Verification
  counterexamples are nested under their owning result, with violated-property and
  replayable-counterexample counts shown separately.
- Added completed verification-run DTOs and `/api/verify/runs` endpoints. Synchronous
  verification now persists its complete conclusion and counterexamples atomically;
  deleting a verification result removes its linked counterexamples, and failed or
  cancelled no-result tasks can be dismissed explicitly.
- Increased AI recommendation `userRequirement` inputs from 500 to 2,000 characters
  across REST DTOs, AI tool schemas, Board controls, tests, and documentation. Detailed
  coupled-scene requests no longer need compressed shorthand, while the input remains
  bounded and is never silently truncated.
- Relaxed only the semantically equivalent rule-event edge case in AI full-scene
  validation: `api = TRUE` is retained as an API event source, normalized by removing
  redundant relation/value fields, and disclosed through `adjustedItems`. `FALSE`,
  partial fields, and other relations remain rejected instead of changing rule meaning.
- Applied the same transparent API-event normalization to standalone AI rule
  recommendations. Equivalent `= TRUE` candidates are kept with an itemized adjustment
  shown by the Board; ambiguous, false, or non-equality comparisons are now filtered
  with an explicit reason instead of having their comparison fields silently erased.
- Restored complete automatic-fix strategy regression coverage against the production
  snapshot-aware model-generation entry points, and made missing fix-context environment
  values explicitly default to an empty pool. `SpecificationDto.devices` now also
  recursively rejects null bindings, missing device ids, and null selected-API entries.

#### Fixed
- Added stable model-generation omission reason codes across verification, simulation,
  history, and automatic-fix contracts. Ordinary UI now localizes disabled-rule and
  skipped-specification explanations instead of exposing English generator diagnostics;
  the original diagnostic remains available only for technical logs and advanced detail.
- Failed background tasks now show a localized no-result explanation first. The raw
  executor error remains available only in a collapsed Technical Details disclosure,
  instead of appearing as the task's primary user-facing status.
- Automatic-fix template drift now has a stable `templateSnapshotComparison` contract.
  The dialog derives source-model and template-snapshot limitations from structured
  fields, while English fix diagnostics remain behind a collapsed technical disclosure.
- Rule duplicate and AI similarity checks now return stable reason codes; AI similarity
  also returns the authoritative `requiresReview` decision. Rule creation and
  recommendation application localize these codes instead of inserting deterministic or
  LLM-generated English prose into Chinese confirmation dialogs.
- Historical counterexample titles now format specification templates through i18n.
  Chinese history and playback surfaces no longer fall back to hard-coded English
  `Always`, `Never`, `Response`, `IF`, or `THEN` wording when rebuilding a trace label.
- Trace-playback mutual-exclusion guards now return reason codes rather than English UI
  sentences, so blocked playback uses the existing localized simulation/recommendation
  guidance.
- Default-device-type reset blockers now include stable reason codes. The reset preview
  localizes device/rule/spec/environment incompatibilities and moves the original
  validation sentence into a collapsed technical disclosure.
- Added a shared locale guard for backend free text and applied it to Board mutation
  errors. Scene-import validation now presents localized item coordinates and keeps raw
  field diagnostics behind Technical Details instead of mixing English exceptions into
  Chinese dialogs.
- Applied the same locale guard to AI recommendation summaries, rationales, filtered
  reasons, intended-use notes, and placements. A provider response in the wrong language
  now falls back to localized advisory copy rather than silently mixing languages.
- Localized deterministic Smart Assistant safety notices and fallback explanations from
  the current user message. Chinese destructive-action previews no longer start with an
  English no-write control sentence, while confirmation, partial execution, uncertain
  mutation, planning-limit, missing-reply, and stream-error safeguards remain explicit.
- Stopped exposing chat implementation placeholders and parser errors as display text.
  Untitled sessions now return `title=null` so the client renders a localized label, the
  final AI reply is explicitly instructed to follow the latest user-message language,
  and client-detected SSE failures use stable error kinds mapped through frontend i18n.
- Fixed two Smart Assistant lifecycle regressions. An empty streamed response placeholder
  now contains the "replying" status inside one compact assistant bubble, and reopening
  the assistant no longer leaves conversation history blank until the user creates or
  sends another chat. The history sidebar now exposes loading, empty, retryable-failure,
  and loaded states explicitly while panel close preserves the mounted conversation.
- Prevented automatic condition fixes from using a rule command's resulting device state as that
  same rule's trigger or adding a condition that contradicts the command API's concrete start state.
  Both cases could make an automation unreachable while falsely looking like a useful repair.
  Fixed apply-time mapping from normalized NuSMV device names back to raw canvas node ids, and
  replaced the leaked internal-reference error with a no-write, user-facing message.
- Raised the automatic-fix dialog above the verification-result modal. Opening
  "Fix" from a violation now exposes the strategy controls and apply workflow instead
  of rendering an interactive dialog behind the still-open verification result.
- Made automatic-fix response collections stable JSON arrays. Strategy-specific details
  and unused preferred-range selections now serialize as `[]` when not applicable,
  preventing a valid verified suggestion from being rejected as an incomplete contract.
- Clarified the automatic-fix empty state: a completed condition-adjustment search now tells
  the user that no condition change passed full-model rechecking, instead of implying that
  the strategy was not run or that suggestions failed to load.
- Clarified the boundary between condition adjustment and permanent rule removal. Condition
  adjustment now describes changing trigger timing while retaining the rule and command;
  removal remains an explicit rule-set deletion rather than an empty-condition workaround.
- Replaced the generic run-history load failure with a source-specific partial-load
  message. If verification results or simulation results fail independently, the Board
  now names the unavailable source and keeps any successfully loaded history visible.
- Restored the canvas device context menu for mouse right-click and added a persistent
  Rename action to device details. Renaming is now discoverable without a keyboard-only
  Context Menu shortcut while retaining the same targeted, identity-preserving API.
- Kept canvas rule labels hidden when a rule is merely selected or newly created. The
  readable label now appears only while its connection is hovered or keyboard-focused,
  preventing persistent rule text from covering devices and nearby links.
- Replaced decorative simulation-step arrows with real decrement/increment controls and
  added direct numeric entry alongside the range slider. All three inputs stay aligned
  to the backend-supported integer range of `1..100`.
- Moved long attack-budget, privacy-propagation, and Environment Pool explanations into
  reusable accessible info tooltips across simulation, verification, and inspector
  panels. Active limits, invalid input, required privacy modeling, and incomplete-model
  warnings remain visible rather than being hidden as optional help.
- Reworked simulation playback around the user's review task. State navigation, playback,
  and selected-state deltas now stay in the first visible layer; frozen run scope is
  collapsible; the run-details action lives in the timeline header instead of covering
  the bottom controls; and diagnostic logs, state tables, and raw NuSMV output are
  collapsed behind a compact run summary.
- Corrected simulation result counting to show actual model states, actual parsed
  transitions, and requested steps as separate concepts. A trajectory shorter than the
  requested horizon now remains visibly qualified in both run details and playback.
- Localized playback changes on the canvas: only devices changed by the selected model
  transition receive motion/emphasis, and backend-reported triggered rules retain
  command-flow emphasis on their edges.
  Current Board ids are normalized with the same NuSMV device-name rule as saved trace
  ids, preventing live devices from being mislabeled as historical.
- Moved playback before/after explanations out of compact device nodes into a bounded,
  draggable change panel. Simulation and counterexample timelines now keep state
  navigation and the timeline in the primary layer; verbose state details remain
  collapsed, and the panel position is constrained to the viewport.
- Reworked model playback motion so device icons and state labels cross-fade to the new
  state, every semantic device delta can retrigger a short settle highlight, and delivered
  command flow completes within one playback step. Change-panel content now transitions
  between steps, and reduced-motion preferences suppress non-essential movement.
- Kept the draggable playback-change panel present for initial and no-delta states, added
  environment-value and automation-cause summaries, and explained why rule edges remain
  still. Delivered-rule edge flow now restarts once per selected transition, including
  consecutive transitions driven by the same rule, instead of continuing an unrelated
  infinite animation.
- Moved the long counterexample model-scope explanation behind an accessible info tooltip.
  The playback header keeps only compact attack, privacy, and completeness status labels,
  while the full interpretation remains available on hover, focus, or click.
- Fixed verification, simulation, and automatic-fix modal headers being compressed by long
  scrollable content. Headers now remain fully visible while only the body scrolls;
  localized the default fault-localization and strategy-attempt explanations so Chinese UI
  does not display backend English summaries.

### 2026-07-12

#### Fixed
- Removed duplicate `aconditions`/`aConditions` keys from typed AI scene responses.
  Portable specifications now serialize exactly the canonical standard-scene field, so
  strict JSON clients can parse and import the recommended draft.
- Aligned the AI full-scene prompt with its rule validator: observable API event sources
  now explicitly omit `relation`/`value`, while specification API conditions retain
  `= TRUE`. The previous example instructed the model to emit a shape that the backend
  would then itemize and filter, commonly breaking otherwise valid event chains.
- Restored the documented `isDuplicate` and `isSimilar` JSON field names on typed rule
  check responses. Manual rule creation no longer treats a successful deterministic
  duplicate check as a broken response and unnecessarily asks the user to bypass it.
- Prevented automatic-fix apply from falsely reporting default-template drift after a
  verification snapshot round trip. Drift checks now compare the persisted manifest
  projection and exact template-name set, so an omitted versus empty legacy API
  `Assignments` list is treated as the same model while real template changes still
  block stale repairs.
- Removed literal wrapper quotes from automatic-removal rule descriptions, so the fix
  dialog names the affected automation exactly as the user authored it.
- Made the long full-scene import/clear confirmation usable on short viewports. The
  Element Plus message-box style is now loaded explicitly, its warning icon is bounded,
  and the consequence text scrolls while the destructive action buttons remain
  reachable.
- Kept `TraceDeviceDto.variables` as an explicit array for state-only devices. Saved
  simulation responses no longer omit the field and get rejected by the frontend's
  completeness guard before an otherwise valid model trajectory can be animated; old
  persisted traces without the field also deserialize to an empty array.
- Fixed full-scene replacement for ordinary API-event rule triggers, whose comparison
  relation is intentionally absent. Generated-namespace validation now classifies those
  triggers as non-parameterizable instead of throwing an internal error before the
  atomic scene write.
- Added a canonical acceptance-demo scene and real-NuSMV regression covering standard
  scene JSON, a four-device event chain, complete mixed verification results, animatable
  simulation/counterexample traces, budget-one trust/privacy/automation-link attack
  behavior, single-rule fault localization, forward-verified permanent removal, and
  all-properties-pass post-repair verification. The accompanying runbook defines manual,
  AI-assisted, and JSON construction paths without treating AI generation as atomic or
  already verified; its exact default-template AI scene prompt fits the public
  500-character requirement limit.
- Applied the same user-semantic projection to saved simulation AI tools: list results
  name the operational handle `simulationId`, detail states omit device/rule model ids,
  and every success explains that a simulation is one possible model trajectory. The AI
  tool no longer exposes an `includeRaw` escape hatch for NuSMV output or internal request
  snapshots; REST technical diagnostics remain separate.
- Removed persistence/model identities from ordinary AI counterexample and fix-analysis
  output. Trace tools now expose only the operational `traceId`, user-facing
  specification/device/rule context, completeness evidence, and projected states;
  automatic fix no longer accepts a numeric specification reference as a hidden list
  index or prints an unresolved internal id in its summary.
- Made rule-trigger authoring commit only on an explicit Add-source action (or Enter from
  the value field). Selecting an event API or leaving a value input no longer silently
  inserts a trigger while the visible Add button suggests the row is still a draft.
- Stopped the verification/simulation forms from silently reducing an attack budget or
  turning attack modeling off when Board edits shrink the modeled attack surface. The
  original selection remains visible, receives an explicit invalid-state explanation,
  and must be revised or disabled before a run can start.
- Made Board-backed AI verification and simulation consume one atomic semantic snapshot
  across devices, the Environment Pool, rules, specifications, and exact template
  manifests. Automatic-fix apply now performs every final drift check against the same
  complete snapshot inside the rule-write transaction. Chat verification results also
  use user-facing specification labels and previews instead of exposing `specId`, while
  retaining the actual checked expression as explicitly named technical evidence.
- Made the low-level NuSMV generator enforce the same exact attack-option contract as
  REST and AI callers. It now rejects out-of-range budgets, enabled attack mode with a
  zero budget, and disabled attack mode with a positive budget instead of silently
  clamping or discarding the caller's meaning. Specification-template 7 copy now also
  distinguishes a protected condition's propagated untrusted label from the individual
  trigger sources that produced it, and rule previews describe target APIs as commands
  being executed rather than new trigger events.
- Qualified automatic-fix evidence in the ordinary UI. Suggestions are now labelled as
  having passed recomputation in the current complete formal model; apply success states
  that every submitted specification passed before the write and that unmodelled reality
  is outside that claim. The UI no longer presents a raw English backend message as an
  unconditional "verified solution", and rule-drift errors identify the readable
  automation plus a one-based position instead of exposing `Rule #0`.
- Made every AI tool enforce its declared exact argument object at execution time,
  including recommendation, search/list, task/status, trace, and no-argument tools.
  Unknown fields and wrongly typed scalars can no longer be ignored after a permissive
  caller bypasses the model-facing schema. Duplicate/similarity checks now accept an
  exact rule-candidate shape rather than silently coercing a full persistence DTO.
- Made standalone recommendation REST payloads preserve omission semantics. Optional
  fields are no longer reintroduced as explicit `null`, specification conditions no
  longer expose derived `side` or persistence `id`, and the Board validates kept
  candidates before display/application without inventing relations or coercing values.
  The assistant response contract now also distinguishes candidates from applied or
  verified state and requires complete kept/filtered/truncated disclosure.
- Added a source-wide check that every literal frontend translation call resolves in
  both supported languages, and fixed the rule-similarity clear result so it explains
  that an AI non-match is not proof of conflict freedom or safe behavior instead of
  exposing a missing translation key.
- Removed the browser manifest cache as a device-detail authority. When a device type
  cannot be resolved from the current backend catalog, the detail dialog now fails
  closed, keeps only instance identity visible, and does not present stale states,
  variables, APIs, trust/privacy labels, or related formula previews as current facts.
- Stopped the Board's local duplicate-rule key from inventing `=` when a value-based
  condition has no relation. Missing relation remains distinguishable and is rejected
  by the authoritative rule contract instead of being compared as equality.
- Closed the remaining playback overlay entry points. Run History and the task inbox now
  share the same model-trace lock as simulation, verification, and recommendations, so a
  user must close the current timeline before selecting another run or task surface.
- Made strict REST rejection actionable. Unknown JSON fields and DTO type mismatches now
  return a safe field path in `message` and `data.errors`, without echoing values or Java
  types, instead of collapsing every deserialization failure into an unexplained
  `Malformed request body` response.
- Removed generator-level silent changes to explicit model values. Invalid local or shared
  enum/numeric initial values, unknown environment entries, duplicate entries, and
  undeclared domains now fail model generation; malformed natural-change rates and
  signal APIs without a representable state transition fail as well. Only an omitted
  valid initial value may use the template's intentional default or nondeterministic
  behavior.
- Made AI board-mutation arguments exact instead of best-effort. Tool JSON schemas now
  disallow extra root fields, and device/rule/specification/environment/template/fix
  tools reject unknown, action-irrelevant, or wrongly typed fields before writing.
  `manage_spec` no longer turns a malformed condition collection into an empty side or
  invents equality for a non-API condition with only a value; API signal checks retain
  their documented `= TRUE` authoring default.
- Changed manual and recommendation rule/specification creation feedback to state that
  persistence succeeded but formal verification has not run, matching the existing AI
  tool response contract instead of letting a save toast imply a checked property.
- Made attack toggle/budget combinations exact across REST and AI verification and
  simulation entry points. Non-attack requests now use the DTO default `0` and reject a
  positive budget instead of silently discarding it; enabled runs require `1..50`.
  Unknown AI run options fail before board loading, and async start responses now echo
  the task's effective attack/privacy context alongside its frozen model scope.
- Made standard scene v4 scalar handling lossless and fail-fast. Import no longer turns
  JSON numbers/booleans into text fields, while export no longer filters incomplete
  environment entries or rule sources or invents a missing relation; malformed semantic
  state is rejected instead of being presented as a successful but changed scene.
- Made targeted device-list JSON import equally explicit about input interpretation.
  Runtime/template/name values no longer coerce non-string scalars, competing aliases
  such as `template` plus `type` are rejected instead of silently prioritized, and the
  preview now exposes every error and planned duplicate-name rename in a scrollable list.
- Stopped silently clamping manual batch-device counts. Values outside the displayed
  `1..50` range now keep the create action disabled with an inline error, so the preview
  and persisted count cannot differ from what the user entered.
- Made automatic-fix strategy selection exact across REST, AI tools, DTO validation, and
  the service boundary. Defaults now apply only when `strategies` is omitted; explicit
  empty, malformed, unknown, or duplicate lists are rejected instead of unexpectedly
  restoring the default chain that includes permanent rule removal.
- Made recommendation controls strict across REST and AI tools. Unsupported language or
  category values, non-string requirements, and requirements over 500 characters now
  return validation errors instead of silently switching to English/all categories or
  truncating the user's scenario before it reaches the model.
- Rejected null device-local value/sensitivity override items at the model-service boundary
  instead of allowing lower-level expansion to discard them. Shared environment trust and
  privacy documentation now distinguishes intentional omission/default inheritance from
  explicit blank or invalid labels, which are rejected before model generation.
- Made assistant `add_device` honor explicitly requested instance names exactly. Name
  conflicts are now detected inside the atomic create operation and return a structured
  no-write `409` with the requested and available suggested names; only omitted names may
  be generated with a suffix. The obsolete successful `nameAdjusted` contract was removed,
  and the chat loop stops at the alternative-name prompt instead of accepting its own
  suggestion in a later tool round.
- Closed the cross-tab/external-writer gap in destructive scene replacement. Import and
  clear now preview authoritative server counts and bind confirmation to an opaque
  current-board impact token; the server rechecks it under the database user-row lock
  before any template or scene write, returns an itemized fresh preview on drift, and the
  frontend refreshes without retrying or claiming success. `/board/batch` no longer
  supports the contradictory `null = preserve this collection` behavior: all four scene
  collections and the exact template snapshot set are required complete inputs.
- Aligned AI device creation with manual and JSON-import name semantics. A colliding AI
  suggestion now shows the exact available instance name before writing and requires
  explicit confirmation; a second collision while the mutation is queued aborts instead
  of silently adding another suffix after confirmation.
- Removed a nonexistent priority concept from AI rule/specification candidates. Rule
  recommendations now return `name`, which is the exact name persisted on apply;
  specification recommendations return an advisory `rationale`, while the UI states that
  only the template and structured conditions enter the formal property. Missing names or
  rationales are itemized filters, and recommendation order carries relevance without
  pretending to alter rule execution order or specification checking order.
- Stopped presenting AI recommendation attempts as completed Board changes. Rule,
  device, and specification candidates now have separate processing and committed states,
  remain tied to the response epoch that produced them, and show "Added" only after an
  authoritative response or reconciliation confirms persistence.
- Serialized all Board semantic writes, device-type mutations, automatic-fix refreshes,
  and assistant-triggered collection refreshes before applying authoritative snapshots.
  Verification/simulation now wait for pending writes before capturing their immutable
  requests. Scene replacement locks live editing from a stable confirmation preview
  through reconciliation and cannot start while an assistant tool stream may still be
  mutating the Board; playback likewise blocks new assistant requests.
- Split device edits into complete, intent-owned layout and runtime subresources and
  removed the ambiguous whole-device update contract. Canvas moves/resizes can no longer
  overwrite modeled runtime fields, runtime saves cannot change identity/label/layout,
  dimensions stay inside the backend's integer domain, and each update returns validated
  before/after snapshots plus itemized changed fields. The Board now serializes create,
  layout, runtime, rename, and confirmed-delete snapshots to prevent late responses from
  overwriting newer state.
- Unified portable geometry across saved canvases, standard scene JSON, and AI scene
  drafts. All paths now accept finite coordinates in `-1000000..1000000`, integer widths
  in `80..2000`, and integer heights in `60..2000`; service-layer validation prevents AI
  or batch callers from bypassing the REST contract, and invalid AI candidates are
  reported as itemized filters instead of failing only during application.
- Replaced AI scene REST reuse of full template/device/specification DTOs with exact
  portable nested DTOs. Serialization can no longer add null template ids/flags,
  condition ids/sides/display labels, or stateless-device label fields that make a tool-
  validated scene fail the standard importer. Frontend types now model that portable
  contract directly, exports self-check against their own importer, and missing optional
  AI rule titles no longer discard otherwise valid trigger/action semantics.
- Made the shared file/AI scene confirmation describe the selected scene and its full
  replacement consequence, rather than incorrectly referring to a file when the user
  entered from an AI draft. Shared success and validation-result messages now use the
  same source-neutral scene language.
- Aligned manual, batch, and device-list import name handling with the backend's
  case-insensitive device-instance uniqueness rule. Single creation now blocks and keeps
  the user's typed name instead of committing an unconfirmed suffix; batch/JSON previews
  show the exact case-insensitive rename plan, rename prechecks use the same rule, and
  submission still aborts on drift.
- Clarified the destructive Clear Scene boundary: it atomically removes devices, the
  Environment Pool, rules, and specifications, while preserving device types, run
  history, and AI conversations.
- Moved standard-scene relation and specification-template shape checks before the
  destructive replacement confirmation. Unsupported operators and missing/unexpected
  condition sections are now identified in the JSON instead of asking for confirmation
  and only then relying on backend rejection.
- Added one-based user coordinates to local and single-item scene validation feedback
  while retaining the exact zero-based JSON field path as technical editing detail.
- Made each verification property result self-contained at submission time. `specResults`
  now carry the submitted template semantics, user-facing label, rebuilt formula preview,
  and CTL/LTL kind beside the actual checked expression. Async/history UI no longer joins
  a technical `specId` to the mutable current Board, and generated NuSMV expressions and
  ids moved into expandable technical details instead of the ordinary result row.
- Made Environment Pool editing genuinely field-level. A value, source-label, or
  sensitivity-label edit now preserves every omitted field instead of resetting it to a
  template default. The REST response itemizes supplied, changed, and preserved fields
  with before/after values and the authoritative pool; the Board validates and presents
  that outcome before claiming success. Device-create environment patches use the same
  semantics, while complete scene replacement and explicit internal default reset remain
  separate contracts.
- Made Stop/close/regenerate authoritative for all four AI recommendation flows. Each
  request now owns an epoch and abort controller, so a late canceled response cannot
  clear or overwrite a newer request. Device panels also show every backend-kept
  candidate instead of silently re-slicing results when the next-request count input is
  edited.
- Made AI-assistant interruption and conversation switching request-owned. Aborted
  streams no longer fire the normal-completion callback, late chunks/tool commands and
  stale history responses are ignored, and old callbacks cannot clear a newly started
  stream. Conversation-history loading now has its own status instead of showing a Stop
  control that could not cancel it.
- Reclassified AI device `role`/`location` output as advisory `intendedUse` and
  `suggestedPlacement`. The Board states that this context is not persisted or modeled,
  and both manual and AI device creation now preview currently missing shared
  Environment Pool names that creation will initialize; final server-reported changes
  remain authoritative.
- Persisted specification list order explicitly in the internal
  `specification.list_order` column and ordered all reads by it. Multi-spec scene
  import/export can no longer depend on incidental database row order while claiming a
  stable, byte-identical portable round trip.
- Made specification writes semantic-only across manual, AI, and scene paths. Clients no
  longer submit template labels, formula text, device summaries, or condition display
  fields as authority; board storage rebuilds those caches from current nodes and
  structured conditions. AI list/create and board-overview previews now use current
  device/template context, show shared values as Environment concepts, and cannot leak a
  stale persisted NuSMV expression. Scene replacement also checks the authoritative 200
  response for semantic equivalence before reporting a complete import.
- Closed a portable-scene defaulting gap: v4 import/export now requires every shared
  Environment value to be explicit and non-blank, so `null` cannot silently mean
  "restore the template default" in a supposedly lossless round trip. AI prompts now call
  their output a self-contained full-replacement draft and explicitly avoid claims that
  it is complete, safe, verified, or already applied.
- Made the MEDIC label-propagation boundary machine-readable and visible in results.
  Runs now return `labelPropagationScope=AUTOMATION_RULE_COMMANDS_ONLY`; frontend
  contract checks and trace/verification assumptions explain that template-internal
  transitions and natural dynamics do not relabel their outputs.
- Aligned the remaining built-in external-input defaults with MEDIC origin semantics.
  Inbound email, vehicle/mobile location, step counts, exterior RFID events, and
  door/window contact readings now start as `untrusted`; this avoids treating a sensor
  observation or outside-house event as retained in-house user control.
- Closed the model-trace animation/current-Board identity gap. Playback now uses only
  saved trace state, dims and labels current devices absent from that trace, blocks live
  device-detail editing while playback is open, and defers background playback instead
  of interrupting an active editor. Chinese locale timestamps now use `zh-CN` formatting.
- Distinguished the scene's full attack surface from the 50-point per-run input cap.
  Verification and simulation controls no longer describe a capped slider maximum as the
  number of modeled points, and explicitly disclose the branches a capped run excludes.
- Added an explicit `API.AcceptsContent` capability for privacy-label flows. Manual rules,
  Board/model validation, AI recommendations, and scene recommendations now reject
  attaching content sensitivity to ordinary actions; built-in mail, social-post, photo
  send, and cloud-upload actions declare the capability and the UI only offers content
  selection for those actions.
- Removed remaining internal-position and certainty leakage from user-facing AI paths.
  Rule/specification condition errors now use one-based display positions, chat presets
  no longer fall back to persistence ids or call preview text a raw formula, and
  violation-oriented prompts require simulation/formal verification instead of promising
  a counterexample. Attack-budget help now states that each submitted rule contributes
  one logical delivery point regardless of how many trigger edges the canvas renders.
- Corrected built-in Clock and Calendar control-source defaults to `untrusted`. MEDIC
  treats environment-driven time/date events as outside direct in-house user control;
  leaving them `trusted` could hide unauthorized-control findings for scheduled rules.
- Separated AI tool success, failure, and unavailable-result outcomes. A serialization
  failure now stops the tool loop, is never counted as success, refreshes authoritative
  state only when a mutation may already have committed, and blocks automatic retry until
  the user can inspect that state.
- Unified AI rule identity with board/recommendation contracts: `manage_rule` now accepts
  `deviceId` plus display-only labels, while rule/spec list and mutation results expose
  semantic views instead of raw persistence DTO names. Created rules and specifications
  explicitly return `verificationStatus=NOT_VERIFIED`; specification tool views call
  cached formula text `formulaPreview`.
- Split background-task lifecycle from result meaning in Run History. A completed task is
  now neutral, with a separate verification-outcome or generated-model-completeness badge,
  so a completed violated verification can no longer look like a green safety result.
- Stopped the Board from inferring `modelComplete=true` when a run omitted that required
  field. Only an explicit backend `true` can produce a complete-model result state.
- Made `board_overview` usable as semantic AI context instead of an internal-id summary.
  Rules and specifications now expose typed conditions with separate stable device refs
  and current display labels; generated summaries use labels, content-flow commands stay
  visible, and specification formula text is explicitly named `formulaPreview`.
- Removed the remaining "complete scenario" claim from AI scene tool definitions and
  success/error messages. The backend now calls the output an importable scene draft,
  states that it passed structure/capability checks only, and explicitly says it has not
  been formally verified or applied to the Board.
- Froze every referenced device-template manifest at the verification/simulation model
  boundary and returned a user-facing `modelSnapshot` on results, tasks, and traces.
  Queued runs can no longer validate one template version and execute another. The Board
  now shows the exact submitted item counts and distinguishes same-tab unchanged input,
  changed input, unavailable comparison, and historical results that were not compared
  with the current Board.
- Replaced bare async verification/simulation submit ids with authoritative accepted-task
  DTOs carrying status, progress, model semantics, and the frozen model scope. REST, AI,
  TypeScript, polling code, and documentation now state that acceptance is not completion.
- Made automatic-fix template replay exact and template comparison three-state. Suggestions
  run against the counterexample's saved manifests; confirmed drift is explained, an
  unavailable comparison is warned rather than hidden, and apply blocks the latter with
  retryable `503` instead of falsely claiming templates changed.
- Replaced the bare fault-localization rule array with an explanatory result carrying
  source-model completeness, itemized generation issues, warnings, and a causally
  conservative summary. The automatic-fix UI and AI contract now describe localized
  automations as involved in counterexample transitions rather than proven independent
  root causes. Missing source completeness metadata fails closed, and fix apply rejects
  it before template checks or NuSMV recomputation.
- Corrected AI-assistant interruption semantics. Stopping or losing the SSE response no
  longer implies that an already-started tool transaction was cancelled: the UI explains
  the uncertainty and reconciles all board collections plus run history. Async task and
  trace tools now refresh run history, pending mutation refreshes are still sent when a
  later AI step fails, and max-round/missing-reply fallbacks report usable and failed
  tool-step counts without claiming the whole request completed.
- Renamed the AI scene action to generate an importable, unverified scene draft rather
  than a "complete scene". Browser JSON exports now report that a download was requested,
  not that the browser or file system definitely saved it.
- Model request construction now rejects a nameless shared-environment entry instead of
  silently filtering it out and verifying or simulating a different input than the Board
  displayed.

### 2026-07-11

#### Fixed
- Distinguished automation **Action Event** from **Action** in rule and specification
  authoring. Only a template action explicitly marked `Signal=true` is selectable as an
  IF event; all valid template actions remain selectable as a THEN command. This avoids
  presenting a model event pulse and a device command as the same user concept.
- Removed the obsolete internal preferred-range locator map from the public automatic-fix
  request DTOs. REST and AI callers now have one contract only: select an opaque
  `ParameterAdjustment.targetId` and submit `preferredRangeSelections`; extra locator
  fields are rejected by strict request parsing.
- Made device-driven Environment Pool changes explicit. Device mutation DTOs now return
  itemized added/updated/removed shared values. Device deletion has an authoritative REST
  preview used by the Board and AI, displays environment removals before confirmation,
  and rechecks rule, specification, and environment impact sets in the same locked
  transaction; any drift returns `409` without a partial delete. AI `add_device` now
  returns the authoritative pool and the same itemized transaction changes instead of
  reporting only the created device while shared values changed silently. Chat-driven
  device creation/deletion and environment edits now also refresh the Board Environment
  Pool rather than leaving its displayed model inputs stale.
- Made Board write responses authoritative at the frontend boundary. Device, rule,
  specification, Environment Pool, and complete-scene operations now reject missing
  snapshots, mismatched affected sets, wrong operations, and inconsistent counts instead
  of substituting local drafts or empty arrays and showing success. Unknown device-delete
  and environment-edit outcomes refresh from the server and remain visibly unconfirmed.
- Reconciled rule-order and device-type catalog writes after lost or incomplete
  responses. The UI now compares the refreshed rule order, reloads templates after
  import/delete/reset uncertainty, and never retries a destructive request implicitly.
- Closed the automatic-fix apply response loop. The frontend now validates the
  server-recomputed verified suggestion, strategy, before/after counts, and full
  persisted rule snapshot, then updates the Board directly from that response instead
  of creating a second refresh-failure window.
- Made AI recommendation candidate accounting end to end. All four panels now show raw,
  inspected, kept, rejected, and limit-truncated counts; inconsistent/missing counters or
  per-item reasons reject the response. Backend messages also distinguish an AI model
  returning zero candidates from candidates rejected by capability validation.
- Corrected scenario-recommendation accounting when the backend synthesizes a required
  Environment Pool entry. The frontend now checks final `count` against the four scene
  collections while keeping `validatedCount` tied to inspected raw AI candidates, and it
  validates every filtered/adjusted item rather than accepting empty reason objects.
- Replaced all four untyped AI recommendation REST maps and the two POST request maps
  with explicit Java DTOs, including strong candidate and portable-scene shapes. The
  controller now revalidates candidate accounting and explanation rows before returning
  HTTP 200, so tool/DTO drift becomes an explicit 502 instead of a partial success.
- Removed the permanently empty `warnings` field from REST device-mutation results and
  blocked batch device creation when names would change after the displayed preview. The
  Board now shows the proposed changes and submits no devices instead of silently renaming
  them behind a generic count-only success message.
- Replaced untyped rule duplicate/similarity REST maps with explicit result DTOs and
  matching frontend runtime validation. Missing booleans or explanations, out-of-range
  similarity scores, and contradictory AI duplicate results now fail the check instead
  of being interpreted as evidence that no conflict was found.
- Added runtime conclusion contracts for verification, counterexamples, simulation, and
  persisted simulation history. The Board now requires explicit completeness, matching
  itemized generation issues, consistent model semantics, and structurally playable
  states; completed async verification tasks can no longer infer a conclusion from
  missing fields.
- Extended those contracts across asynchronous task creation, inbox/status polling,
  progress, completed simulation trace references, and cancellation outcomes. Malformed
  200 responses now fail explicitly instead of becoming a false timeout or success, and
  opening a pending, failed, or cancelled simulation task no longer says that a completed
  task merely lost its trace. Cancellation documentation now matches the explanatory DTO.
- Replaced the count-only default-template reload with an impact-token preview and an
  atomic reset result containing every type change, affected device label, blocker,
  Environment Pool change, and final type/environment snapshot. Ordinary catalog GETs
  no longer resurrect deleted defaults, malformed bundled resources fail the whole
  import instead of being skipped, and registration now rolls back when its initial
  default catalog cannot be created.
- Restored the unavailable-attack explanation to the verification and simulation panels.
  The previous condition hid it exactly when the effective attack surface was zero, and
  the simulation copy had been rendered inside the unrelated rename-device dialog.
- Made the attack-effect contract scene-exact. `attackEffects` no longer claims reading
  falsification for scenes without a falsifiable reading, or command loss for scenes
  without rules. The immutable run snapshot now also carries and persists the
  falsifiable-reading device subset count; backend analysis deduplicates by canonical
  device instance identity, and the Board explains the exact mechanism counts.
- Made canvas motion truthful to the saved model trace. Ordinary topology edges and
  idle rules are now static; command-flow particles appear only when the backend says a
  rule produced the selected state, and a compromised delivery link never animates.
- Renamed the machine-readable command-loss attack effects from `MAY_BE_DROPPED` to
  `IS_DROPPED`. The NuSMV guards deterministically block a command once its target device
  or delivery link is selected as compromised; DTOs, frontend checks, UI wording, and
  documentation now state that behavior exactly.
- Made device-list JSON import reject conflicting declarations of the same shared
  Environment Pool value, control-source label, or sensitivity label. Identical and
  complementary declarations still merge, while conflicts name both input rows and no
  device in the batch is created.
- Made every template API explicitly declare `Signal`. Omitting the field no longer
  silently turns a state-changing action into a command-only action; `true` means the
  event can trigger automations/specifications and `false` means command-only. Device
  details now use that user-facing wording instead of the implementation term “Signal”.
  Observable API routes are also rejected when another API or autonomous Transition
  could produce the same pulse, preventing one action from being reported as another.
- Made API `StartState` explicit. Template import no longer interprets a missing command
  precondition as “callable from any state”; authors must write an empty string/`_`
  pattern for that deliberate choice or provide a concrete start state.
- Rejected unknown JSON fields across user-facing REST request DTOs instead of silently
  discarding misspelled board, model, fix, auth, or chat inputs. Verification and
  simulation now report the exact unsupported nested path, retain HTTP `400` for DTO
  shape errors, and reserve `422` for model/template semantic validation.
- Validated raw verification/simulation environment overrides before default merging.
  Duplicate names, explicit blank values or trust/privacy labels, and invalid domains now
  fail visibly; omitted or `null` model-request fields retain their documented
  template-default meaning. Board Environment Pool REST edits use the separate
  field-patch contract documented above.
- Made device-list JSON import reject and identify unsupported top-level and nested
  runtime fields rather than creating a device after silently dropping them.
- Corrected the specification architecture contract for Safety template 7. The
  executable property checks attacked and non-attacked paths alike and never injects an
  `is_attack=FALSE` escape clause; only the main-module invariant limits the selected
  attack budget. The previous document example described the opposite behavior.
- Aligned the Environment Pool's defensive display fallback with the backend: an
  unresolved legacy source label is shown as untrusted rather than silently appearing
  trusted. Valid current templates still provide explicit trust/privacy labels.
- Made `InternalVariables[].IsInside` required across the canonical schema, Java DTO,
  frontend type/import preflight, and AI template guidance. Template authors must now
  explicitly choose device-local versus scene-shared ownership; omission can no longer
  silently turn an instance value into a shared environment value.
- Removed the implicit boolean domain for template variables. Every internal/shared
  reading must now declare either enum `Values` (including explicit `TRUE`/`FALSE` for a
  boolean) or complete numeric bounds, so a forgotten domain cannot be accepted with
  different model semantics.
- Rejected attack-enabled verification/simulation when a scene has no automation link
  and no reading declared falsifiable on compromise. The Board now disables the option
  with an explicit reason instead of reporting a behaviorally identical no-op run as
  attack analysis.
- Excluded behaviorally inert device instances from the MEDIC compromise budget. A
  device is now a countable attack point only when it has a declared falsifiable reading
  or receives an automation command; rule delivery links remain separate points. Model
  generation, request validation, async task snapshots, result semantics, Board budget
  controls, AI tool descriptions, and documentation now use the same effective surface.
- Required a stateful template's `InitState` to be one concrete complete
  `WorkingState`. Wildcard, partial, or undefined initial configurations now fail at the
  canonical backend boundary, matching manual device creation and portable-scene
  semantics.
- Replaced remaining Safety-template preview text that called MEDIC control-source
  labels an "untrusted state" or generic "untrusted data". User-facing summaries now
  match the generated property: the protected condition must not hold when any related
  control-source label is untrusted.
- Aligned the executable trust aggregation and machine-readable run contract with MEDIC
  Definition 3.3. Trust now means retained trusted control rather than generic integrity
  taint: one trusted trigger source keeps the target trusted, and only an all-untrusted
  trigger set marks it untrusted. Verification UI text now states this limitation.
- Revalidate raw stored template manifests at every model-build boundary. Unknown fields
  and missing required security labels now stop verification before DTO conversion;
  content privacy is no longer silently defaulted to public for stale template records.
- Made standard scene import fail closed on unknown JSON fields. The frontend now reports
  the exact unsupported path before confirmation, while `/api/board/batch` strictly
  parses every non-template DTO and validates raw template manifests before typed
  conversion. Response-only and template-catalog ids are rejected, preventing a lossy
  import from being reported as a complete replacement.
- Removed the unreferenceable `Transition.Signal` field from the canonical template
  schema, backend/frontend DTOs, AI guidance, and docs. Autonomous transitions now
  require a structured trigger and one modeled effect; user-visible one-step event
  pulses remain available only on state-changing APIs with `Signal=true`.
- Removed the misleading `WorkingState.Invariant` field from schema, DTOs, frontend
  types, device details, defaults, and examples. It was stored and displayed as if it
  constrained verification but was never read by the generator; device behavior and
  checked requirements now remain exclusively in structured Dynamics, Transitions,
  rules, and specifications.
- Removed unsafe implicit `trusted`/`public` template labels. Working states and internal
  variables now require explicit trust and privacy, content items require privacy, and
  frontend import preflight plus AI template guidance enforce the same contract. The
  built-in Oven template now labels its three local variables explicitly. Runtime
  builders inherit template labels only when an override is omitted; an explicit invalid
  label remains visible to validation instead of being silently replaced.
- Declared read-only trust/privacy labels for stateful sensor templates that have modes
  but no command APIs. Rules and specifications can now use those states as labeled
  sources without generating references to undeclared NuSMV variables; compromise still
  affects only explicitly falsifiable readings rather than hijacking sensor state.
- Corrected variable-evolution semantics and made them user-visible: undeclared
  device-local enum/boolean behavior now stutters instead of changing arbitrarily,
  while verification, simulation, and counterexample results return and display the
  declared-rate/device-effect policy for shared numeric values and domain-bounded
  nondeterminism for shared enum/boolean environment values.
- Rejected accepted-looking template behavior that the formal model could only apply
  partially or ignore. APIs are now state-only device commands (not network endpoints),
  API assignments/triggers and stateless APIs fail fast, API signals pulse only on an
  actual complete state change, and autonomous Transitions are limited to one validated
  state or variable effect. Environment-triggered state transitions now read the shared
  environment value instead of an undeclared device-local field.
- Made state-dependent template Dynamics domain-safe and executable. Numeric targets use
  integer rates; enum/boolean targets use validated values; unknown, duplicate, wrong-type,
  descending-range, normalized-duplicate, and malformed natural-rate declarations fail
  before save/model generation instead of being silently skipped or assigned a fallback
  rate. Boolean impacted variables no longer receive invalid numeric `_rate` machinery.
- Persisted immutable device/link/total attack-point counts with verification and
  simulation tasks and returned them in `modelSemantics`. Current and historical result
  UIs now explain the selected budget against the run snapshot rather than silently
  recomputing a potentially different denominator from the current board.
- Made automation execution precedence visible and user-controlled. Rules now persist an
  explicit order, the Board provides identity-preserving up/down controls backed by an
  atomic reorder endpoint, and standard scene JSON preserves the same semantics through
  `rules[]` order. State transitions, triggered-rule traces, and MEDIC trust/privacy
  propagation now share the exact first-selected branch, including API start-state and
  attack-delivery guards, so an unexecuted lower rule can no longer update labels.
- Corrected the machine-readable privacy contract to include rule-selected content
  sensitivity as well as trigger-source labels. UI scope text, TypeScript consistency
  checks, API/architecture docs, and tests now describe the actual propagation model
  without implying payload copying, access control, encryption, or transmission blocking.
- Separated user-facing device names from persistent device identity. Manual UI and AI
  `add_device` creation now assign opaque `device_*` references while preserving explicit
  display labels; case-insensitive conflicts are rejected without a write, and renaming
  never changes rule/specification identity.
- Treated complete-scene AI ids as response-local graph aliases instead of permanent
  user identity. `recommend_scenario` now assigns portable `device_1...` references and
  rewrites rule sources/targets, content sources, and specification conditions together,
  so punctuation/case in an LLM alias cannot merge devices at the NuSMV boundary.
- Added board-write model-namespace preflight for generated shared-environment, rule
  playback, attack-analysis, and existing-condition automatic-fix identifiers. Invalid
  standard scene JSON is rejected before any collection write with a reason attached to
  the offending device reference; the display name may remain unchanged.
- Extended direct verification/simulation namespace validation to include generated
  rule execution and automation-link attack markers, using shared constants with the
  generator and trace parser instead of drifting duplicate strings.
- Preserved every semantic `ValidationException` field error in REST `422` responses as
  `data.errors`. Scene import now shows a complete diagnostic list for compound failures
  and explicitly leaves the current board unchanged instead of surfacing only the first
  rejected field.
- Added explicit AI-scene adjustment feedback for deterministic runtime/layout defaults
  and missing required environment entries, separated from rejected and truncated
  candidates. The preview now shows device-local/environment runtime semantics and does
  not present a no-mode sensor's internal `Working` placeholder as a real state machine.
- Made standalone device recommendations expose the exact initial runtime that creation
  will use. Omitted labels, state/source/sensitivity labels, and local variable values are
  materialized from deterministic template defaults and listed in `adjustedItems`; explicit
  invalid values still reject the whole candidate instead of being silently replaced.
- Aligned AI-assistant `add_device` with the same effective-runtime semantics: partial or
  empty local runtime arrays now retain explicit values, fill each omitted template value
  and label, and report the exact defaulted paths. Its tool schema no longer calls the
  user-facing device name an id.
- Unified manual and device-list JSON creation on the same effective local-runtime helper.
  Omitted values now materialize enum-first or numeric-lower-bound defaults before save,
  while explicit blank scalar fields are rejected instead of being silently interpreted
  as defaults; scene exports continue to carry those effective values losslessly.
- Upgraded portable board scenes to schema version 4 and removed the canvas-only
  `Working` state from stateless device semantics. Stateful templates require an initial
  state; stateless scenes reject state-level source/sensitivity fields, restore the
  rendering placeholder only inside the canvas, and omit it again on export and AI scene
  responses.

### 2026-07-10

#### Fixed
- Corrected MEDIC trust propagation so a target event becomes untrusted only when every
  contributing trigger source is untrusted; one trusted source retains a trusted control
  path as defined by MEDIC Definition 3.3. Template-7 safety checks combine protected
  conditions with any untrusted protected-state label,
  and frontend formula previews use the same semantics as generated NuSMV. Multi-mode
  full-state conditions now propagate labels only from the modes they actually read,
  combining trust with AND and privacy with OR; multi-mode APIs include every changed
  state. Template import rejects incomplete state tuples or conflicting labels for a
  reused mode-state component instead of silently selecting a label by JSON order.
- Expanded the attack budget from device instances only to device instances plus one
  logical command-delivery link per submitted automation rule. Compromised links can
  drop their rule command independently, are included in the same upper-bound counter,
  and are returned as stable rule snapshots for named broken-link trace playback rather
  than exposing generated link indexes. Broken-link playback now stops command-flow
  particles, and the UI states that one run does not calculate a minimum violating
  compromise count. Attack-enabled requests now require a positive budget; disabled
  attack mode is the only user-facing state with an effective zero budget.
- Structured trace trust/privacy entries as literal `{ propertyScope, mode?, name }`
  labels and translated internal attack flags/counters to `compromised` and
  `compromisedPointCount`, keeping generated `Mode_state`, `trust_*`, `privacy_*`, and
  link-choice identifiers out of the user-facing trace contract.
- Replaced the ambiguous verification `safe`-only conclusion with explicit
  `SATISFIED` / `VIOLATED` / `INCONCLUSIVE` outcomes and `modelComplete` across sync,
  async, AI-tool, and Board result/history flows. No-emission and parser/count failures
  are now inconclusive rather than being presented as property violations.
- Replaced per-specification `passed` booleans with explicit per-item outcomes and
  stopped emitting `CTLSPEC FALSE` for specifications that cannot be translated.
  Omitted specifications can no longer manufacture violations or counterexamples;
  verification, simulation, async tasks, saved traces, AI tools, and Board history now
  carry and render item-level `{ issueType, itemLabel, reason }` generation issues.
- Made synchronous simulation fail with structured reason codes on timeout,
  interruption, execution failure, or zero parsed states instead of returning an empty
  success DTO. Simulation results, tasks, saved traces, AI history tools, and the Board
  now expose and display reduced-model status through `modelComplete` and
  `disabledRuleCount`.
- Added per-strategy automatic-fix attempt statuses and warnings, blocked fix generation
  and apply for counterexamples produced from incomplete generated models, and made
  forward verification reject any candidate that disables rules, skips specs, or has an
  incomplete emitted/parsed property result set.
- Changed automatic-fix discovery from an implicit serial run of every strategy to an
  explicit choose-and-try flow. Fault localization and strategy execution now have
  independent progress/error states, so a slow parameter search cannot be presented as
  “no suggestions” or hide a viable disable/condition strategy. Trying a strategy is
  explicitly read-only; only the separate Apply action writes server-recomputed rules.
- Renamed the destructive automatic-fix strategy from `disable` to `remove` and its
  response field to `removedRuleDescriptions`. The implementation permanently deletes
  rules and has no reversible enabled state, so REST, AI, frontend, docs, and tests no
  longer imply temporary disablement. Applying this strategy now requires a destructive
  confirmation that names the number of rules to be removed.
- Made `FIX_TIMEOUT_MS` a real pipeline deadline: each fix-time NuSMV capacity wait and
  process run is capped by the remaining budget. Strategy attempts distinguish
  `TIMED_OUT` (started but incomplete) from `SKIPPED_TIMEOUT` (never started), and the
  frontend's server-bounded long requests no longer fail at the generic 100-second CRUD
  timeout while the backend is still computing.
- Fixed the automatic-fix dialog's independent loading state and preference lifecycle:
  opening performs fault localization only, users explicitly try one strategy, editing a
  parameter preference invalidates the old apply action without losing selectable
  targets, and a mutating apply cannot be dismissed as though it were cancelled.
- Preserved persisted rule identity across the frontend verification/simulation model
  boundary. Triggered-rule and compromised-link snapshots now correlate to current
  rule-derived canvas edges, so animation no longer labels every active automation as a
  removed historical rule. Portable scene files and temporary UI ids remain id-free.
- Replaced raw verification `violatedSpecJson` in client responses with a structured
  `violatedSpec` snapshot, kept trace/simulation ownership and persisted request JSON
  server-internal, and returned structured attack/privacy execution context instead.
- Tightened portable scene files to the exact `iot-verify.board-scene` schema/version and
  a self-contained template dependency set: every referenced template requires a
  matching snapshot even if already installed, unreferenced snapshots are rejected, and
  missing device coordinates no longer default silently. Scene v3 also removes derived
  specification labels and requires the exact environment pool with explicit trust and
  privacy labels. Snapshot `name` must exactly match `manifest.Name`, so import cannot
  silently rename a template. Both frontend and backend reject these defects before any
  board mutation.
- Made shared environment semantics self-contained and order-independent. Impact-only
  templates now declare `EnvironmentDomains` without gaining read capability; unused
  account templates no longer supply hidden domains. Board/model boundaries reject
  same-name conflicts in casing, domain/enum order, natural change rate, default trust,
  or default privacy, and shared declarations require explicit trust/privacy labels.
- Documented `currentStatePrivacy` across the Board and AI recommendation contracts so
  manual creation, AI suggestions, and portable-scene JSON all preserve the same
  initial-state sensitivity label without implying access control.
- Tightened user-facing guarantee boundaries across verification, simulation, scene
  import/export, rule duplicate/similarity checks, and auto-fix apply. The Board now
  presents explicit verification outcome plus model completeness rather than a `safe`
  boolean that could be mistaken for full system safety,
  presents simulation as a model trace, includes environment variables in scene
  summaries, and keeps trace/spec/device technical ids in technical details.
- Replaced ordinary board full-list writes with targeted device/rule/spec create,
  update, rename, and delete commands. Mutation responses identify the affected item and
  return authoritative post-mutation collections; `/board/batch` is now reserved for
  explicitly confirmed atomic scene replacement/clear.
- Added dependency-aware device deletion: the UI confirms the exact related rules/specs,
  the backend compares those dependency ids under the write lock, and drift returns 409
  before any write instead of deleting newly related items.
- Made AI deletion of devices, templates, rules, specifications, and saved traces a
  server-enforced two-turn flow. The first call is a no-write preview, the tool loop
  stops, and a later explicit user confirmation is required; device previews use an
  opaque impact token and reject changed collateral effects.
- Kept rule/device/spec creation drafts open until targeted persistence acknowledges
  success, prevented duplicate submits while saving, and made device creation messages
  use the server-confirmed instance name after any conflict rename.
- Changed AI response-serialization fallback from a false success message to
  `RESULT_UNAVAILABLE`, distinguishing read-only failures from mutations that may have
  committed and instructing callers to refresh before retrying.
- Changed deterministic duplicate and AI similarity checks to return readable
  `matchedRule` text instead of rule database ids, and surfaced that rule in manual-create
  and recommendation-apply confirmation dialogs while retaining the explicit
  "not a conflict-free proof" boundary for negative results.
- Completed the auto-fix preferred-range contract: REST, AI tools, frontend types, and
  docs now submit `{ targetId, lower, upper }` copied from `ParameterAdjustment.targetId`
  instead of exposing zero-based rule/condition selectors to callers. Target ids are
  opaque trace-scoped selectors rather than reversible wrappers around internal
  rule/condition keys.
- Tightened the auto-fix apply contract so clients submit only a strategy and optional
  preferred ranges. The server recomputes and verifies the concrete repair before saving;
  normal REST/AI fix responses now expose readable adjustment and removed-rule
  descriptions instead of rule/condition positions or rule database ids.
- Added `rawCandidateCount`, `inspectedCount`, and `truncatedCount` to AI rule, device,
  specification, and coupled-scene recommendation responses, and surfaced truncated raw
  candidates in the Board recommendation panels.
- Removed ambiguous AI-rule `requiresUserInput` output from directly applicable
  recommendations, derived specification template labels from validated `templateId`,
  and stopped truncating an applied rule's displayed name to 30 characters.
- Changed malformed or schema-less whole AI recommendation responses from misleading
  empty HTTP-200 results into structured `AI_RESPONSE_INVALID` / HTTP-502 errors, while
  retaining `filteredItems` for parseable individual candidates that fail validation.
  AI rule-similarity parse failures likewise no longer degrade to a false "not similar"
  result.
- Made AI device and coupled-scene recommendations candidate-atomic for device runtime,
  rule-source, rule-content, and specification-condition validation so invalid nested
  items are reported through `filteredItems` instead of being silently defaulted or
  dropped from an otherwise "successful" recommendation.
- Rejected scene JSON imports that contain environment variables outside the retained
  device-template environment domains, and made `recommend_scenario` report those
  variables as filtered items so scene export/apply round trips do not lose them silently.
- Renamed the frontend specification-template preview field from `ltlFormula` to
  `formulaPreview` and explicitly labels the Control Center formula code as a formula
  preview while keeping CTL/LTL badges as type hints.
- Made specification `formula` and `devices[]` presentation caches derived from
  structured conditions in manual creation, AI-assistant creation, recommendation
  apply, and standard scene import; AI/file-provided preview caches no longer override
  the semantics shown to users.
- Made scene import and clear save the complete board semantic model
  (`nodes`, `environmentVariables`, `rules`, and `specs`) through one `/board/batch`
  transaction, so imported topology cannot be persisted with the previous environment
  pool. Batch device creation appends devices and applies environment patches through a
  targeted atomic mutation rather than replacing unrelated board collections.
- Extended scene-import batch saves so portable template snapshots are checked and any
  missing referenced templates are created in the same transaction as the complete board
  replacement. The response reports `createdTemplates`; failed imports no longer rely on
  a best-effort frontend rollback that could leave template side effects behind. A
  snapshot-marked scene request must provide all four semantic collections, and omitted
  required environment variables are rejected instead of being inherited or defaulted.
- Preserved authored `rules[]`, rule `sources[]`, `specs[]`, and specification
  condition-list order in portable scene JSON canonicalization because previews,
  generation, trace localization, and fix suggestions use positions from the board
  snapshot.

### 2026-07-09

#### Fixed
- Added per-candidate `filteredItems` feedback to AI rule, device, specification, and
  complete-scene recommendations so the Board can show why individual AI candidates were
  rejected by backend capability validation instead of only showing `filteredCount`.
- Reworked auto-fix parameter preferences in the Board dialog to choose from actual
  `ParameterAdjustment` targets; users no longer type rule/condition numbers to produce
  internal `r{idx}_c{idx}` preferred-range keys.
- Changed the REST and AI auto-fix steering contract to `preferredRangeSelections[]`
  (`targetId`, `lower`, `upper`) and reject the old internal
  `preferredRanges` locator map instead of accepting or silently ignoring it.
- Relabeled device/specification formula displays as formula previews with CTL/LTL
  badges where applicable, labeled verification result expressions as actual checked
  expressions, and moved device/specification technical ids behind collapsible technical
  details.
- Aligned coding-agent manuals with the current device-identity contract: board
  references are canonical node ids, labels are display-only snapshots, and stack
  guidance lives in the `CLAUDE.md` files while root `AGENTS.md` mirrors Codex rules.
- Added board scene import/export in the Board header for reusable devices,
  environment variables, rules, specifications, and referenced template snapshots, with
  import validation and rollback on failed saves.
- Improved Board ergonomics: larger default device nodes, direct minimap zoom controls,
  clearer rule edges, inspector device clicks that focus/highlight nodes instead of
  opening details, and environment-pool text that hides backend-only NuSMV names.
- Tightened scene portability and board orientation: scene JSON export is now canonical
  and round-trip comparable, scene import rejects mismatched template snapshots, the
  Board header includes a confirmed Clear Scene action, inspector device/rule/spec tabs
  have consistent collapsible search panels, and created devices/rules are focused on
  the canvas after save.
- Added an end-to-end guard that exports a scene, clears the board, imports the scene,
  exports it again byte-identically, and verifies the imported scene can be modeled.
- Aligned AI `add_device` and backend node creation defaults with the Board UI's
  176x128 device node size.
- Aligned AI-created rules/specifications with user-visible board semantics:
  `manage_rule` now persists readable `ruleString` text, `manage_spec` now derives
  `formula` and bound `devices[]`, and environment variable keys are matched literally
  instead of treating `a_` as a user-facing alias for generated SMV names.
- Removed remaining reserved-prefix leaks from the user contract: `variable_` is no
  longer treated as a hidden canvas-node prefix, `trust_`/`privacy_` condition keys are
  matched literally like `a_` variables, trace environment variables are serialized and
  replayed with literal user/template names instead of generated NuSMV aliases, and
  custom template creation now rejects concrete generated NuSMV identifier collisions.
- Added a coupled AI scene recommendation tool and Action Dock entry. The backend
  `recommend_scenario` tool returns one importable `iot-verify.board-scene` draft whose
  devices, environment variables, rules, and specifications are validated together,
  instead of stitching together independent device/rule/spec recommendations.
- Improved AI recommendation UX: rule/device/spec/scene responses now expose validation
  counters so the Board can explain filtered suggestions, and device recommendations now
  return/apply concrete instance hints such as suggested label, role, location, initial
  state, and local runtime values instead of only a template name.
- Tightened AI recommendation application semantics: applying a recommended rule now runs
  the explicit AI similarity check before saving, and scene recommendation no longer
  reports a complete scenario when validation removes every generated device.
- Synchronized AI-tool documentation/index references after adding `recommend_scenario`
  and `check_rule_similarity`: the endpoint index now lists `/api/board/scenario/recommend`
  and `/api/board/rules/check-similarity`, and the documented tool count/category map
  reflects 33 tools including environment management, scene recommendation, and AI
  rule-similarity tools.
- Split rule duplicate detection from AI semantic similarity analysis. `Create Rule`
  now uses a deterministic typed trigger/action duplicate check before saving, while the
  original external-LLM analysis is available through the explicit AI similarity check
  action and `/api/board/rules/check-similarity`.
- Fixed fix-apply semantic drift detection for environment-only changes: the canonical
  board fingerprint now includes shared environment variables that are affected by
  devices even when no current device reads them, so stale fixes cannot be applied after
  changing those environment initial values.
- Tightened Board scene-import and creation feedback: imported rules now fail fast when
  required target command fields are missing, and newly created specifications focus and
  highlight in the inspector just like devices/rules.
- Bound retained NuSMV temporary model directories to diagnostic identity: verification
  and simulation model dirs now include `user_<userId>` plus `sync` or `task_<taskId>`,
  saved simulation dirs use `saved_trace`, and auto-fix model dirs include
  `trace_<traceId>`.
- Aligned async simulation feedback with sync simulation and async verification: when a
  user waits for an async simulation to complete and no other animation is active, the
  Board opens the simulation timeline immediately; if another timeline is active, the
  completed trace stays in history without stealing focus, and the user is told where
  to find the saved run instead of seeing a silent completion. Playback now stops as
  soon as the final state is shown, resets when a same-length run replaces the visible
  simulation, and disables Play for one-state counterexamples.
- Clarified verification-result status in the Board UI: a `safe=true` response with
  disabled rules or skipped specs now shows a warning state instead of presenting the
  incomplete emitted-spec check as full system safety.
- Removed the remaining auto-fix preferred-range rule/condition number inputs in favor
  of selecting parameter-adjustment targets with fault-rule context.
- Corrected specification UI terminology: the spec panel no longer describes the
  mixed CTL/LTL template set as LTL-only, and the creation preview now says
  specification description while deriving the CTL/LTL badge from the generated formula.
  The preview sentence is now localized and formats state/mode/variable/API/trust/privacy
  targets as user-facing device properties instead of hard-coded English fragments.
- Clarified the rule-save contract in docs and code comments: `saveRules` takes the
  complete desired rule list, preserves surviving ids/`createdAt`, inserts new rules,
  and deletes existing rules omitted from the request.
- Changed AI `manage_spec` add semantics to require an explicit `templateId` instead of
  silently defaulting missing ids to the Always template, preventing ambiguous AI-created
  specifications.
- Prevented incomplete async verification tasks from opening as unsafe results: failed,
  cancelled, pending, and running tasks now show task-state feedback instead of coercing
  a missing `isSafe` value to `false`.
- Separated trace runtime globals from board environment variables: the compromised-point counter and
  other NuSMV globals now serialize under `TraceStateDto.globalVariables`, while
  `envVariables` contains only literal user environment names.
- Preserved literal device variables that look like generated NuSMV helper names
  (`trust_*`, `privacy_*`, `*_rate`, `*_a`) during trace parsing instead of routing or
  dropping them by prefix/shape alone.
- Added a concrete `MODULE main` namespace collision guard so device instance ids cannot
  collide with generated environment identifiers (`a_<name>`), the compromised-point counter, or
  auto-fix `param_*` / `lambda_*` variables in the emitted SMV model.
- Aligned AI verification/simulation tool argument validation with REST/service
  semantics: out-of-range `attackBudget` and simulation `steps` now return a structured
  validation error instead of being silently clamped.
- Disabled silent JSON scalar coercion and count clamping at user/API boundaries:
  integer and boolean fields now reject quoted strings/floats, AI id/count arguments
  fail before board loading when malformed, and the Board UI validates recommendation
  counts, simulation steps, attack budget, and scene-import geometry without rewriting
  user input.
- Clarified NuSMV init-value semantics in docs and generator comments: user-facing
  device/environment initial values are rejected at save/model boundaries when illegal;
  generator-level clamping is only defensive fallback behavior for direct low-level calls.
- Hardened AI integer-argument validation against oversized JSON integers and documented
  non-integer rejection. `attackBudget` is an upper bound over device-instance and
  submitted automation-link points; attack-mode requests cannot exceed that total, and
  the Board UI uses `min(50, deviceCount + ruleCount)` as the visible slider limit.
- Hardened `fix_violation.preferredRangeSelections` parsing so oversized JSON integers
  cannot be truncated into accepted 32-bit bounds.

### 2026-07-06

#### Changed
- **Board panel state now lives only in `board_layout`.** `BoardLayoutDto.panels`
  carries control/inspector collapsed state, width, and active section alongside canvas
  pan/zoom. The old `board_active` DTO/entity/repository/API was removed.
- **Verification spec results are now identity-bearing objects.** Sync verification,
  completed async verification tasks, and `verify_model` AI tool output now return
  `specResults` as `{ specId, outcome, expression }` entries for the specifications
  actually emitted to NuSMV. Async task persistence stores this object-array JSON in the
  existing `specResultsJson` column; `specResults` is strictly the structured shape (the
  pre-release boolean-array format is no longer read, so the column must not contain it).
- Verification now treats "no specifications emitted to NuSMV" as an unreliable failure
  (`safe=false`, empty `specResults`) instead of a vacuous pass, and completed async task
  `violatedSpecCount` now counts failed structured spec results rather than only saved
  traces.
- Async verification submission is centralized through `submitVerification`: invalid
  requests are rejected before task creation, queue saturation marks the created task
  failed and returns `503`, and retained low-level `verifyAsync` calls now require a
  non-null task id and use the same submit-before-poll semantics.
- Async simulation now follows the same centralized submission pattern through
  `submitSimulation`, so REST and AI callers no longer duplicate task creation,
  dispatch, and failure-compensation logic.
- Task inbox summary endpoints now accept optional `excludeTaskIds`, and the frontend
  uses it while explicitly watching a task so the 5s inbox refresh does not re-fetch the
  same task already covered by the 1s per-task polling loop.
- Verification and simulation service-layer entry points now deep-snapshot requests and
  run NuSMV runtime validation before execution or task creation. Direct service and
  AI-tool callers can no longer mutate queued DTO objects after submission, bypass null
  list-item checks, or skip runtime constraints such as attack intensity, simulation
  steps, device identity, and executable specification conditions. REST endpoints still
  keep their full DTO Bean Validation at the HTTP boundary.
- Synchronous simulation now propagates validation errors instead of returning a
  success-shaped empty result; `simulate_model` reports those failures as structured
  `BUSINESS_ERROR` responses.
- Saved simulation traces now persist the same validated execution snapshot used by the
  NuSMV run, so direct service callers cannot mutate the original request object during
  execution and skew `requestJson`.
- Async verification and simulation now expose lightweight per-user task inbox endpoints
  (`GET /api/verify/tasks`, `GET /api/simulate/tasks`). The Board UI uses them for a
  task inbox and global mini task indicator, so background tasks remain visible and
  cancellable after closing the submit panel or refreshing the page.
- **Board UI chrome was normalized around responsive layout and theme tokens.** The
  side panels, floating panels, task/history surfaces, canvas map, and trace/simulation
  timelines now use shared board surface/card/form tokens; timelines are bottom-centered
  between side panels, node labels/states have bounded responsive layout, and stable
  Playwright selectors cover the primary user-facing regions.
- **Canvas tool overlays were tightened for real canvas work.** The canvas map is now
  compact/translucent, docks to the visible canvas top-left, keeps its mini-map dots
  and edges inside the viewport, and its fit/center buttons are covered by the real
  browser check. Trace/simulation timelines are lighter, the right action rail no
  longer overlaps the inspector, and the grid is painted as an infinite viewport
  background driven by pan/zoom.
- **The board action rail is now grouped and accessible.** Run actions and AI
  suggestion actions are visually separated, desktop buttons show short text labels,
  icons are distinct, and every tool button exposes an explicit accessible name and
  pressed state for keyboard and screen-reader users.
- **The system inspector now uses entity tabs with local create affordances.** Devices,
  rules, and specifications are browsed one category at a time, each list has a nearby
  add action that opens the matching control-center form, and the inspector active tab
  is persisted through `board_layout.panels.inspector.activeSection`.
- **Board usability checks now cover tool panels, language/theme variants, and trace
  edge cases.** Floating tool panels expose stable selectors, recommendation panels use
  localized labels/actions, side panels clamp or wrap long text, missing device images
  fall back to inline SVG, and the simulation timeline is checked with short, standard,
  and long traces from the real backend.

#### Removed
- The `VerificationService` / `SimulationService` interfaces no longer expose the
  low-level async task plumbing (`verifyAsync`/`simulateAsync`, `createTask`,
  `failTaskById`). REST and AI callers go through `submitVerification` /
  `submitSimulation`, which own validation, task creation, and failure compensation
  internally; the remaining pieces are package-private implementation details.

### 2026-07-05

#### Fixed
- **Empty verification requests no longer succeed vacuously.** Sync and async verification now
  require at least one specification at the DTO boundary (`@NotEmpty`), the Board UI blocks
  no-spec verification before calling the API, and service-layer defensive paths fail instead of
  returning a satisfied result with empty `specResults`. NuSMV generation warnings and fix
  forward verification remain explicit: omitted specs surface as structured issues, and empty
  NuSMV result sets are not treated as verified fixes.
- **Fix-apply now blocks spec/device-only drift.** Applying a verified fix previously replayed the
  trace's stored context on the server, so editing spec conditions or device instance state
  (variables, privacies, initial state, trust) after verifying — without touching rules or templates —
  was not detected and a stale fix could be persisted onto the current rules. apply now compares a
  canonical **semantic fingerprint** of the trace snapshot against the current board (device names
  canonicalized, empty variable/privacy lists manifest-defaulted, values de-quoted, so an untouched
  board still matches), and rejects with `400` on drift. The check runs **inside the same per-user
  write lock + transaction** as the rule save (read → check → write is one atomic critical section), so
  a concurrent spec/device/node edit cannot slip in between the check and the write. When the current
  board fails to build a device model it fails closed, distinguishing cause: an invalid/changed board
  rejects with `400`, while an unconfirmable infrastructure error (e.g. template repository unavailable)
  rejects with `503` ("retry later") instead of misattributing it to a board change.
- **Device reference resolution unified across spec generation, drift fingerprint, and fix candidates.**
  A single `DeviceReferenceResolver` now owns the `deviceId → deviceLabel → normalized-label` fallback
  order, so the NuSMV generator, semantic fingerprint, and fix-candidate builder no longer each guess
  device names independently (they previously disagreed for AI/historical specs carrying a node-id
  `deviceId` plus a label). condition-fix `add` now maps verification-time names (`d_1Lamp`) back to the
  current board's persisted label (`1Lamp`) before saving, so the frontend can resolve the node.
- **Template-drift check fails closed on apply.** A template-repository error during apply no longer
  proceeds to recompute and persist; it is treated as unverifiable drift and rejected. `/fix` still
  fails open (a repo error only drops the advisory warning).
- **Digit-leading device labels no longer misfire the rule-drift check.** The frontend prefixes such
  labels (`1Lamp` → `d_1Lamp`) in the verification snapshot while rules persist the raw label; the
  apply-time rule fingerprint now canonicalizes both sides, so an untouched board is no longer rejected.
- **Historical trace playback respects the same guards as current-result playback.** Viewing a saved
  verification trace now checks the simulation-timeline / recommendations mutex, takes the animation
  lock, and closes the result dialog; the `View` button is disabled while those panels are open.
- **Trace attack/intensity labels reflect the viewed trace.** `TraceDto` now derives and exposes
  `isAttack` / `intensity` / `enablePrivacy` from its stored request snapshot, and the trace control bar
  reads them instead of the live verification form, so a historical trace is labelled with the
  parameters it was actually run under.
- **Successful-simulation logs are reachable.** A successful simulation opens the timeline directly; its
  execution logs and raw NuSMV output are now retained and openable on demand via a "View logs" action
  (previously the result dialog holding them was only reachable on error).
- **AI board overview now reports the same rule connections as the canvas.** `board_overview` derives
  edge summaries from rule conditions and commands instead of the optional persisted `/board/edges`
  geometry table, matching the frontend's rule-derived canvas connections.
- **Async verification and simulation can be cancelled from the Board UI.** The existing cancel REST
  endpoints are now reachable from the async progress panels, and user-requested cancellation is shown
  as an informational outcome rather than a generic failure.
- **`fix_violation` is discoverable by the AI planner.** The tool was registered and documented, but the
  system prompt and explicit tool-name router omitted it; both now list the tool.

### 2026-07-04

#### Changed
- **Decoupled the LLM backend from Volcengine Ark; the AI assistant now targets any
  OpenAI-compatible endpoint.** Introduced a vendor-neutral LLM layer under
  `component/ai/`: a domain model (`LlmMessage`, `LlmToolCall`, `LlmToolSpec`,
  `LlmChatRequest`/`LlmChatResponse`), a `LlmProvider` strategy interface, and an
  `OpenAiLlmProvider` adapter that is the sole holder of an LLM SDK import. Facades
  `LlmChatService` (tool loop + streaming), `PromptCompletionService` (one-shot
  recommend/duplicate-check completions), and `LlmMessageCodec` (persistence wire-format
  ⇄ domain conversion) sit between the business layer and the provider. `ChatServiceImpl`
  and all AI tools now depend only on the domain model — no SDK type leaks into business
  code. Tool declarations return `LlmToolSpec` instead of the SDK's `ChatTool`.

#### Fixed
- **NuSMV generation warnings are now part of the verification contract.** Rule
  conditions that cannot be generated fail closed to `FALSE`, skipped/invalid specs are
  omitted rather than emitted as false properties, and verification/simulation DTOs
  return `generationIssues` alongside `checkLogs`, `disabledRuleCount`, and
  `skippedSpecCount` so a satisfied subset cannot hide that part of
  the requested model was excluded. Warning collection is passed through a request-scoped
  `SmvGenerationContext` instead of global mutable state, and completed async verification
  tasks now persist/return the same two count fields.
- **Rule creation no longer fabricates dummy triggers.** Frontend save, duplicate-check,
  direct verify, and simulation paths validate that every rule has at least one concrete
  trigger condition; the backend also requires non-empty `RuleDto.conditions`.
- **Specification persistence now keeps the authored formula and device bindings.**
  `SpecificationDto`/`SpecificationPo`/`SpecificationMapper` preserve `formula` and
  `devices` metadata across board saves.
- **Board edge storage is geometry-only again.** Frontend API mapping no longer expects
  phantom edge fields such as `fromApi`, `toApi`, `itemType`, `relation`, or `value`.

#### AI Tools
- **AI rule/spec recommendations now use device labels as their primary references.**
  `recommend_rules` emits `deviceName` values that match board labels, and
  `recommend_specifications` requires `templateId` to be one of `"1"` through `"7"`;
  recommendations with illegal template ids or unresolvable devices are filtered out.
  Tool validators, prompt schemas, and backend DTO validation now also converge on
  supported relation operators and non-empty condition values.
- **Device template manifests now have an executed canonical schema.**
  `backend/device-template-schema.json` is validated by REST template import,
  AI `add_template`, and default-template initialization before DTO mapping and NuSMV
  pre-checks. API triggers are now explicitly schema-invalid; conditional template
  behavior must be modeled through `Transitions`.

#### Configuration (breaking)
- **Config keys renamed `volcengine.ark.*` → `llm.*`.** Env vars: `VOLCENGINE_API_KEY` →
  `OPENAI_API_KEY`, `VOLCENGINE_MODEL_ID` → `OPENAI_MODEL`, `VOLCENGINE_BASE_URL` →
  `OPENAI_BASE_URL`, `ARK_TIMEOUT_MINUTES` → `LLM_TIMEOUT_MINUTES`; new `LLM_PROVIDER`
  (default `openai`). Point `OPENAI_BASE_URL` at the official API or a relay.
  `ProductionSafetyCheck` now guards `llm.api-key` (`OPENAI_API_KEY`).

#### Dependencies
- Replaced `com.volcengine:volcengine-java-sdk-ark-runtime` with
  `com.openai:openai-java` (4.41.0). Removed `ArkAiClient` and `ArkAiConfig`.

### 2026-07-03

#### Security
- **Fixed a `UserContextHolder` ThreadLocal cross-request leak in `JwtAuthenticationFilter`.**
  The filter set the per-request `UserContextHolder` (a `ThreadLocal` read by the AI-tool
  path via `AbstractAiTool`) but never cleared it. On a reused Tomcat worker thread, a
  later request arriving without a valid token would neither set nor clear it and would
  observe the previous request's `userId` — a potential cross-user access on the AI-tool
  path. The filter now clears `UserContextHolder` in a `finally` around
  `filterChain.doFilter` (covering the blacklisted-token early path too). The REST path
  was unaffected — `@CurrentUser` reads `SecurityContextHolder`, which Spring clears per
  request.

#### Changed

- **Integrated duplicate-rule checking in rule creation dialog.**
  The `POST /api/board/rules/check-duplicate` endpoint (backend implementation existed
  but was frontend-unintegrated) is now callable via `boardApi.checkDuplicateRule(rule)`.
  `RuleBuilderDialog.vue` added a visible check action and confirmation flow; the current
  2026-07-09 contract splits deterministic save-time duplicate detection from the
  explicit AI similarity action.
- **Frontend API base URL is now relative by default and configurable end-to-end.**
  Both `src/api/http.ts` (axios, `(VITE_API_BASE_URL || '') + '/api'`) and
  `src/api/chat.ts` (SSE) derive their base URL from `import.meta.env.VITE_API_BASE_URL`.
  When it is empty (the default) requests go to a relative `/api`, which the Vite dev
  server and a production reverse proxy (Nginx) forward to the backend — so dev and
  same-origin prod need no config. Previously axios hardcoded `http://localhost:8080/api`,
  which bypassed the Vite dev proxy and, in prod without an override, pointed the
  browser at the user's own machine. Set an absolute `VITE_API_BASE_URL` only for
  cross-origin deployments (resolves former frontend fix item F-11).

#### Fixed
- **Custom-template API editor now emits a backend-valid manifest.** The template
  creator (`CustomTemplateCreator.vue`) previously produced API definitions that the
  NuSMV pre-check rejected:
  - `Trigger` was built from a single pseudo-value (`user`/`auto`/`event`) as
    `{ Attribute: <pseudo>, Relation: 'EQ', Value: '' }` — Attribute wasn't a legal
    mode/internal-variable and `Value` was blank, both failing P1
    (`validateTriggerCompleteness` / `illegalTriggerAttribute`). It is now edited as
    three fields `{ Attribute, Relation, Value }` — Attribute a dropdown of legal
    modes + internal variables, Relation ∈ `= != > >= < <=`, all required when set
    (or omitted entirely for no trigger).
  - API `Assignments` used the wrong field names `{ VariableName, ChangeRate }`; the
    backend DTO is `{ Attribute, Value }`, so assignment data was dropped and validation
    failed. Now emitted as `{ Attribute, Value }`.
- **Spec trust/privacy conditions restrict the relation operator.** For `targetType`
  `trust`/`privacy` (enum-valued), the operator dropdown now offers only `= != in not_in`
  (was also offering `> >= < <=`, which generate meaningless ordering comparisons on
  enum domains).
- **Async verification/simulation polling reworked for correct lifecycle & error
  handling** (`Board.vue`):
  - Verification async polling is now an awaited `pollAsyncVerification(taskId)`
    (a serial `while` + `await sleep` loop, matching `pollAsyncSimulation`) instead of a
    fire-and-forget `setInterval`. Two problems are fixed: (a) the `async` branch used to
    return immediately, so the outer `finally` set `isVerifying=false` while polling was
    still running — the progress bar disappeared at once and the verify button
    re-enabled, letting the user launch duplicate tasks; now `isVerifying` stays true
    until polling truly ends. (b) the old `setInterval(async …)` callback could re-enter
    concurrently when a status request took longer than the 1s tick (duplicate requests,
    duplicate toasts, stale progress overwrites); the serial loop can't re-enter.
  - Both pollers surface terminal states (`FAILED`/`CANCELLED`) immediately with the
    task's `errorMessage` (simulation previously swallowed `FAILED` in its transient
    `catch` and only reported a generic timeout after 2 minutes).
  - Both pollers distinguish **permanent** status-fetch errors (HTTP 4xx — auth,
    forbidden, task-not-found) from **transient** ones (network blips, 5xx): permanent
    errors fail fast; transient errors retry until the poll ceiling.
  - Both are bounded (simulation 2 min, verification 10 min) so a task stuck in
    `RUNNING` (e.g. a hung NuSMV run) surfaces a timeout instead of polling forever.
- **Rule saving now honors the incremental-upsert contract.** `boardApi.getRules` used
  to prefix every existing rule's DB id as `rule_<id>`, and `saveRules` treated any
  `rule_`-prefixed id as new (sent `id: null`) — so every save of an existing rule
  re-inserted it and deleted the old row, churning ids and resetting `createdAt` (against
  the backend design and `docs/api/board.md`). `getRules` now returns the raw numeric id;
  only client-created rules (`rule_<timestamp>`) send `id: null`, so existing rules are
  updated in place.
- **Custom-template JSON import no longer corrupts enum variables.** The importer used to
  inject a default `LowerBound: 0 / UpperBound: 100` even when the source variable had
  `Values` (enum) — producing a manifest the backend rejects (`@AssertTrue
  isValidVariableDefinition`: `Values` XOR range, never both). It now emits `Values`
  as-is and only writes bounds when the source actually had them.
- **Custom-template API editor validates Trigger/Assignment completeness.** `confirmSaveApi`
  now blocks a Trigger with an Attribute but no Relation/Value, and any half-filled
  assignment, matching backend P1 (`validateTriggerCompleteness`) / assignment validation.
- **Custom-template manual-build path applies the same `Values` XOR range rule.** The
  form's build-manifest step used to always emit `LowerBound`, `UpperBound`, and filtered
  `Values` together; it now emits enum `Values` when present, otherwise the range —
  matching the JSON-import path (the UI currently exposes only range inputs, so this is a
  robustness/consistency fix rather than a live UI bug).
- **`boardApi.saveRules` return type corrected to `Promise<void>`.** It previously claimed
  to return `RuleForm[]` but actually unpacked the backend `RuleDto[]` shape; no caller
  consumed it. Callers needing the persisted rules (e.g. server-assigned ids) should
  re-fetch via `getRules()`.

#### Types (frontend contracts aligned to backend DTOs)
- `DeviceManifest` gained `Contents` (`DeviceContent { Name, Privacy, IsChangeable }`);
  `Dynamic` gained the optional `Value` (backend allows `Value` XOR `ChangeRate`);
  `DeviceAPI.Assignments` is now `DeviceAssignment[]` (`{ Attribute, Value }`) instead of
  `any[]`.
- `SimulationState` gained `rules?` and `trustPrivacies?` (backend `TraceStateDto`).
- `DeviceNode` gained `currentStateTrust?`, `variables?`, `privacies?` (backend
  `DeviceNodeDto`), removing the need for `(node as any)` casts.
- Board panel active state is no longer modeled by a separate frontend type; layout
  state belongs to `BoardLayoutDto`.

#### Removed (dead / duplicate frontend types)
- `types/panel.ts` was removed. Layout DTO types live in `types/canvas.ts`, and panel
  collapsed/active-section state is part of `BoardLayoutDto.panels`.
- `types/spec.ts` no longer exports `relationOperators`, `RelationOperator`,
  `targetTypes`, `TargetType` — unused duplicates of the live versions in
  `assets/config/specTemplates.ts` (which is what components import). Prevents the two
  copies from drifting.
- `api/rules.ts` no longer exports `getRules`, `saveRules`, or the `Rule` interface —
  a dead second rule-persistence surface. Rule persistence lives only on `boardApi`
  (`api/board.ts`); `api/rules.ts` now owns rule *recommendation* only.
- `assets/config/specTemplates.ts` no longer redefines `SpecTemplateType` /
  `SpecTemplateDetail`; it imports them from `types/spec.ts` (and re-exports for existing
  importers), leaving `types/spec.ts` as the single source.
- `types/spec.ts` dropped the unused `SpecForm` interface; `types/rule.ts` dropped the
  unused `GLOBAL_VARIABLES` const and `GlobalVariableName` type (a runtime list that did
  not belong in a types file and had no references).

#### Documentation
- Clarified architecture documentation ownership and kept durable backend design notes
  in the owning API / architecture overview documents rather than a dated audit report.
- **`POST /api/board/rules/check-duplicate` integrated in frontend** through
  `RuleBuilderDialog`; the 2026-07-09 contract now uses this endpoint as the deterministic
  save-time duplicate check and exposes AI semantic analysis separately.
- Aligned `ChatSession`/`ChatMessage` frontend types to backend DTOs (`userId: number`,
  added missing `createdAt`/`sessionId` fields).
- Updated `docs/guides/frontend-integration.md` to clarify `Trace*` / `Simulation*` type
  trees are intentionally parallel but NOT fully isomorphic (e.g., `TraceTrustPrivacy.trust`
  is `boolean | null` mirroring backend `TraceTrustPrivacyDto`, whereas
  `SimulationTrustPrivacy` uses looser `boolean` / optional `privacy`).

### 2026-03-04

#### Fixed
- **Blank `InitState` on modeless sensors**: 16 modeless sensor templates (Weather,
  Clock, Temperature Sensor, Humidity Sensor, …) had an empty-string `InitState`,
  which the AI device-creation path passed through verbatim, persisting `state=""`;
  a later `POST /api/board/nodes` was then rejected (400) by `DeviceNodeDto.state`'s
  `@NotBlank`. Fixes:
  - `NodeServiceImpl.getInitStateFromTemplate()` now `isBlank()`-checks `InitState`
    and falls back to `HARD_FALLBACK_STATE` (`"Working"`); log message corrected to
    "has missing or blank InitState".
  - `DeviceNodeMapper.toDto()` now backfills a null/blank `state` with `"Working"`
    (`FALLBACK_STATE`), preventing legacy dirty data from failing `@NotBlank` on a
    GET → POST round-trip.
  - Added 3 unit tests (`NodeServiceImplMutationTest`): empty, normal, and missing
    `InitState`.
- **Sprinkler Controller.json**: corrected an API-definition key from the erroneous
  `"s"` to `"Assignments"` (two occurrences).

### 2026-03-03

#### Changed — NuSMV generation pipeline
- **Identifier safety** (`DeviceSmvDataFactory`, `BoardStorageServiceImpl`):
  `sanitizeSmvToken()` now escapes NuSMV reserved words case-insensitively (including
  `W`), on top of space stripping, illegal-character replacement, and digit-prefix
  handling, for generation-time cleaning of mode/state names. `toVarName()` gained the
  same digit-prefix and reserved-word defenses. `computeIdentifiers()` guards
  `result` (varName), `base` (module-name prefix), and `suffix`. InternalVariable /
  ImpactedVariable names are validated at persistence time by
  `validateTemplateManifestForNuSmv()` (regex + reserved words + collision) and are
  **not** run through generation-time sanitization.
- **trust/privacy three-layer defense**: entry normalization
  (`normalizeTrustPrivacy()` = trim + lowercase); validation
  (`SmvModelValidator.validatePropertyValues()`); output-layer re-normalization in
  `SmvDeviceModuleBuilder`, defaulting null content privacy to `public`.
- **Numeric-bound clamping** (`SmvBoundsUtils`, `SmvMainModuleBuilder`): attack-mode
  upper-bound expansion centralized in `SmvBoundsUtils.resolveEffectiveUpperBound()`;
  `next()` candidate expressions clamp via `max(lower, min(upper, expr))`.
- **Defensive intensity handling**: `SmvGenerator` clamps `intensity` to `0..50`;
  `SmvBoundsUtils` additionally zeroes negative intensities.
- **`SmvSpecificationBuilder.build()`** gained a 5th parameter `enablePrivacy`; with
  privacy off, a privacy spec is omitted and reported as a structured generation issue
  (defense-in-depth; `validateNoPrivacySpecs` upstream is the primary guard). No
  always-false placeholder is emitted.
- **Regression coverage** (`SmvGeneratorFixesTest`): WITH-rate extreme NCR, internal
  variable boundary branches, `range=0` and negative-intensity cases.

#### Added / Changed — backend hardening
- **Production safety guard**: new `ProductionSafetyCheck` (`@PostConstruct`) refuses
  startup under a `prod`/`production` profile if `jwt.secret`,
  `spring.datasource.password` (`DB_PASSWORD`), or `volcengine.ark.api-key`
  (`VOLCENGINE_API_KEY`) still hold unsafe defaults. `PRODUCTION_MODE` removed; profile
  matching is case-insensitive. `JwtUtil.@PostConstruct` also WARN-logs a default key
  under prod. Superseded by the 2026-07-04 LLM config rename above: the production guard now
  checks `llm.api-key` / `OPENAI_API_KEY`.
- **Exception handling**: `IllegalArgumentException` → masked generic 400,
  `IllegalStateException` → 500, new `DataIntegrityViolationException` → 409 CONFLICT.
- **Thread-pool context propagation**: `ThreadConfig.TaskDecorator` deep-copies
  `Authentication`, `UserContextHolder.userId`, and MDC into child threads.
- **Async-task TOCTOU elimination**: `completeTask`/`failTask` became atomic
  conditional UPDATEs (`WHERE status <> CANCELLED`, `@Modifying(clearAutomatically =
  true)`); `handleCancellation` → `cancelTaskIfStillActive`
  (`WHERE status IN (PENDING, RUNNING)`). `TransactionTemplate` used for
  `VerificationServiceImpl.saveTraces()` and `ChatServiceImpl.processStreamChat()`.
- **Redis resilience**: `RedisTokenBlacklistService` counts consecutive failures
  (`AtomicInteger`), ERROR-alerting every 10th (`% ALERT_THRESHOLD`).
- **Request validation**: `@NotEmpty` devices, `@Size(max=10000)` chat content,
  `@NotNull @RequestBody` coverage (`BoardStorageController` via class-level
  `@Validated`).
- **Read-only transactions**: `@Transactional(readOnly = true)` on all read methods of
  `BoardStorageServiceImpl`, `VerificationServiceImpl`, `SimulationServiceImpl`,
  `ChatServiceImpl`.
- **Entity indexes/constraints**: indexes on `device_edge(user_id)`,
  `verification_task(user_id)`, `simulation_task(user_id)`; unique constraint on
  `device_templates(user_id, name)`.
- **DTO `progress` field**: exposed on `VerificationTaskDto` / `SimulationTaskDto`.
- **`VerificationTaskDto` consistency**: added `@NoArgsConstructor`,
  `@AllArgsConstructor`, `@JsonInclude(NON_NULL)` to align with `SimulationTaskDto`.
- **AI-tool safety**: `AiToolManager.execute()` catch-all returns a generic
  "Tool execution failed due to an internal error" (no `e.getMessage()` leak);
  `AddTemplateTool` pre-builds a `tolerantMapper`; `AddNodeTool` uses
  `parseDoubleOrNull()` / `parseIntOrNull()` to handle JSON null, non-numeric, and
  empty strings (all passed as `null` to trigger default layout values).
- **`ArkAiClient` configurable timeout**: `volcengine.ark.timeout-minutes` (default 5).
- **`JsonUtils`**: registered `JavaTimeModule`; added `fromJsonToStringList()` /
  `fromJsonList()`.
- **DELETE trace 404**: a missing trace now throws `ResourceNotFoundException`
  (previously a silent 200).
- **`JwtAuthenticationFilter`**: `getUserIdFromToken()` wrapped in try-catch —
  malformed tokens treated as unauthenticated.
- **Async controller pattern**: `verifyAsync` / `simulateAsync` return `Result<Long>`
  (no longer `ResponseEntity`); `TaskRejectedException` → `ServiceUnavailableException`.
- **Temp-file retention**: `cleanupTempFile()` is a no-op — `nusmv_*` temp directories
  are kept for post-mortem debugging.
- **Surefire JVM config**: `maven-surefire-plugin` adds
  `-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading`, fixing
  Mockito/ByteBuddy `MockMaker` init on JDK 17.
- **Error-message suppression**: `application.yaml` sets
  `server.error.include-message: never` and `include-binding-errors: never`.
- **`SecurityConfig` ObjectMapper**: `SecurityFilterChain` uses the Spring-managed
  `ObjectMapper` (inherits `JavaTimeModule` etc.).
- **`ArkAiClient` ObjectMapper**: injected via constructor (Spring-managed) instead of
  a field-initialized `new ObjectMapper()`.
- **`ArkAiClient` parse pre-check**: `parseToolMessage()` / `parseAssistantToolCalls()`
  fast-check `content.stripLeading().startsWith("{")` before `readTree()`, keeping
  plain-text messages off the JSON path; fallback path logs at DEBUG.
