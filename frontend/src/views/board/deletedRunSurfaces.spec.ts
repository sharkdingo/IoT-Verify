import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Deleting a run must close every surface still showing it.
 *
 * Found by behaviour, not by reading: run a sync verification, leave the result dialog open, delete that
 * run, then click its model download. Measured on the running app — the dialog stayed open, the download
 * stayed enabled, and the click answered *"SMV model not available (may be a record saved before model
 * persistence was enabled)"*. Two separate defects in one journey:
 *
 * 1. `deleteVerificationRun` removed the run from the history list and nothing else, so the dialog kept
 *    rendering a verdict, per-specification results and counterexample evidence for a record the server
 *    had already deleted — a result surface outliving its own run.
 * 2. That 404 message named a cause the client cannot know. The server's 404 covers *two* states, "the
 *    run is gone" and "the run stored no model", and the copy asserted the second as fact. So the
 *    explanation for a deletion one click old was a historical persistence limitation.
 *
 * Inspect the owning handlers directly because mounting all of `Board.vue` adds unrelated setup.
 */
describe('surfaces for a deleted verification run', () => {
  const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
  const i18n = readFileSync(join(process.cwd(), 'src/assets/i18n.ts'), 'utf8')

  it('tears down the open result and any replay of the deleted run', () => {
    const at = board.indexOf('const deleteVerificationRun')
    expect(at, 'the deletion handler should exist').toBeGreaterThan(-1)
    const body = board.slice(at, board.indexOf('\n}', board.indexOf('finally', at)))

    expect(body, 'deletion must invalidate the surfaces showing that run')
      .toContain('dismissRunSurfacesForDeletedVerificationRun(runId)')
    // After the server confirmed the delete, not before: an unconfirmed delete must leave the surface
    // alone, because the run may still exist.
    expect(body.indexOf('dismissRunSurfacesForDeletedVerificationRun'))
      .toBeGreaterThan(body.indexOf('await boardApi.deleteVerificationRun'))
  })

  it('matches on the run id, so deleting one run cannot close another run\'s surface', () => {
    const at = board.indexOf('const dismissRunSurfacesForDeletedVerificationRun')
    expect(at, 'the teardown helper should exist').toBeGreaterThan(-1)
    const body = board.slice(at, board.indexOf('\n}', at) + 2)

    // The dialog's run is identified by its persistence record; a playing counterexample by the run it
    // belongs to. Closing unconditionally would shut a dialog showing an unrelated run.
    expect(body, 'the open result is matched by its persisted run id')
      .toContain('historyPersistence?.runId')
    expect(body, 'a playing counterexample is matched through its owning run')
      .toContain('verificationTaskId')
    expect(body, 'both comparisons are against the deleted id')
      .toMatch(/=== runId/)

    // `dismissResultDialog`, not `closeResultDialog`: the deep link has to go too, or the URL sync
    // reopens the deleted run and then fails to load it.
    expect(body, 'the deep link must be cleared with the surface')
      .toContain('dismissResultDialog()')
    expect(body, 'and the replay teardown clears its own deep link via closeTraceAnimation')
      .toContain('closeTraceAnimation()')
    expect(body, 'stale evidence for the deleted run must not survive in savedTraces')
      .toContain('savedTraces.value = []')
  })

  it('reconciles the open result when the deletion happened somewhere else', () => {
    /*
     * `deleteVerificationRun` only covers deletions this tab performed, and the result dialog is
     * `aria-modal`, so the history panel is unreachable while it is open — meaning the same-tab path is
     * the *impossible* one. The reachable paths both arrive as a history reload:
     *
     *  - the assistant's `DeleteVerificationRunTool`, which emits `REFRESH_DATA run_history`
     *    (verified: `component/aitool/verification/DeleteVerificationRunTool.java` exists, and the chat
     *    toggle has layout while the dialog is open)
     *  - another tab deleting the run, whose invalidation lands in the same reload
     *
     * So the reconciliation has to hang off the reload, not off the delete handler.
     */
    // Anchored on the verification-specific function, not the `reconcileOpenRunAgainstHistory`
    // dispatcher that now calls it — a window opened on the dispatcher stops at its own closing brace
    // and sees none of the guards below.
    const at = board.indexOf('const reconcileOpenVerificationRunAgainstHistory')
    expect(at, 'the reconciliation should exist').toBeGreaterThan(-1)
    const body = board.slice(at, board.indexOf('\n}', at) + 2)

    expect(body, 'it reuses the same teardown as an in-tab deletion')
      .toContain('dismissRunSurfacesForDeletedVerificationRun')
    expect(body, 'and tells the user why the surface closed')
      .toContain('openRunDeletedElsewhere')

    // The guard that keeps this from destroying a live verdict: an empty list is also what a
    // still-loading or scoped-empty history looks like, so only absence from a POPULATED list counts.
    expect(body, 'an empty history must not be read as "deleted"')
      .toContain('verificationRuns.value.length === 0')
    expect(body, 'and a run still present must be left alone')
      .toMatch(/some\(run => run\.id === openRunId\)/)

    // Wired to a *successful* reload only: a failed load leaves the lists stale, and treating that as a
    // deletion would close a surface over a transport error.
    //
    // Anchored on the full declaration: `indexOf('const refreshRunHistory')` matches the *chat adapter*
    // `refreshRunHistoryFromChat`, which is declared ~3500 lines earlier, so the window landed on the
    // wrong function entirely.
    const reloadAt = board.indexOf('const refreshRunHistory = async')
    const reload = board.slice(reloadAt, board.indexOf('\n}', reloadAt) + 2)
    expect(reload, 'reconciliation runs after the reload succeeded')
      .toMatch(/if \(results\.every\(Boolean\)\) reconcileOpenRunAgainstHistory\(\)/)
  })

  it('reconciles a replaying counterexample, not only an open result dialog', () => {
    /*
     * The two are different states, not one. Opening a counterexample calls `closeResultDialog`, so
     * `verificationResult` is null for the entire replay — reading only it made the reconciliation above
     * return early on precisely the surface most expensive to get wrong: an animating trace whose run the
     * assistant or another tab had just deleted, still offering Run details and the SMV download for a
     * record the server had dropped. `dismissRunSurfacesForDeletedVerificationRun` already tears the replay
     * down; nothing was reaching it.
     */
    const at = board.indexOf('const reconcileOpenVerificationRunAgainstHistory')
    const body = board.slice(at, board.indexOf('\n}', at) + 2)

    expect(body, 'the replaying counterexample carries the run id when the dialog is closed')
      .toMatch(/\?\?\s*currentTrace\.value\?\.verificationTaskId/)

    // And the notice has to match which surface went away: the dialog string says the panel was closed,
    // which is false for a replay. Same defect class as reusing the editor notice for a replay deferral in
    // `midRunStaleness.spec.ts`.
    expect(body, 'a replay teardown must not claim a panel was closed')
      .toContain('openCounterexampleRunDeletedElsewhere')
    expect(body, 'and the wording must be chosen from the surface, not the run kind')
      .toMatch(/currentTrace\.value\?\.verificationTaskId === openRunId/)

    // The wording choice must also test that a replay is actually on screen, and this is not defensive:
    // `currentTrace` falls back to `verificationResult.traces[selectedTraceIndex]`, so with only the dialog
    // open it still resolves to a trace carrying this run's id — which would report "playback ended" to a
    // user who was reading a dialog, the same fabricated-cause defect as the 404 above. Asserted through
    // `activePlaybackKind`, the existing owner of "what is on screen", rather than a second reading of the
    // animation flags.
    expect(body, 'a closed replay must not be described as one that ended')
      .toMatch(/activePlaybackKind\.value === 'counterexample'/)
  })

  it('does not blame model persistence for a model the server simply does not have', () => {
    // The old copy asserted "may be a record saved before model persistence was enabled", which is one of
    // two possible causes and was stated for both. Naming a specific historical limitation is what made
    // it convincing enough that a user would not re-check their history.
    /*
     * Comment lines are stripped first, and that matters: the comment recording *why* the old wording was
     * wrong necessarily quotes it. Checking the raw file made this assertion fail on its own explanation —
     * the third time in this session that a guard matched a comment instead of the code it guards.
     */
    const strings = i18n
      .split('\n')
      .filter(line => !line.trim().startsWith('//') && !line.trim().startsWith('*'))
      .join('\n')
    expect(strings, 'the misleading persistence excuse must not return')
      .not.toContain('before model persistence was enabled')
    expect(strings, 'nor its Chinese equivalent').not.toContain('模型持久化功能上线前')
    // Both causes named, neither asserted, and an action the user can take.
    const at = i18n.indexOf('smvModelNotAvailable:', i18n.indexOf('smvModelNotAvailable:') + 1)
    const english = i18n.slice(at, at + 400)
    expect(english, 'names deletion as a possible cause').toMatch(/deleted/i)
    expect(english, 'names the modelless-record cause too').toMatch(/without one|no model/i)
    expect(english, 'and tells the user how to tell which').toMatch(/history/i)
  })
})

/**
 * The exploration half of the same rule, which was missing entirely.
 *
 * `delete_fuzz_run` is a shipped assistant tool, and `ChatToolProgressPresenter.potentialRefreshTargets`
 * lists it alongside `delete_verification_run` under `run_history` — so an exploration run has exactly the
 * two reachable out-of-band deletion paths a verification run has (the assistant, and another tab), and
 * `FuzzingResultDialog` is `aria-modal="true"` just like the verification dialog, which makes the same-tab
 * history-panel path the unreachable one. Only verification was reconciled, so the exploration dialog kept
 * rendering a run, its findings and its eligibility report for a record the server had dropped.
 *
 * The second defect here is the closer: `closeFuzzingResult` documents itself as *not* touching the URL
 * (it serves internal transitions such as opening a finding replay), and the delete handler used it — so
 * `?run=exploration:<id>` outlived its own run and the deep-link watcher would reload it.
 */
describe('surfaces for a deleted exploration run', () => {
  const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')

  it('clears the deep link when the user deletes the run in this tab', () => {
    const at = board.indexOf('const deleteFuzzingRun')
    expect(at, 'the deletion handler should exist').toBeGreaterThan(-1)
    const body = board.slice(at, board.indexOf('\n}', board.indexOf('finally', at)))

    expect(body, 'the open result for the deleted run must close')
      .toMatch(/fuzzingResult\.value\?\.id === run\.id/)
    // The URL-clearing closer, not the internal-transition one.
    expect(body, 'and take its deep link with it')
      .toMatch(/=== run\.id\) dismissFuzzingResult\(\)/)
    expect(body, 'closeFuzzingResult leaves ?run= behind, so it must not be the closer here')
      .not.toMatch(/=== run\.id\) closeFuzzingResult\(\)/)
    // Only after the server confirmed it: an unconfirmed delete must leave the surface alone.
    expect(body.indexOf('dismissFuzzingResult'))
      .toBeGreaterThan(body.indexOf('await fuzzingApi.deleteRun'))
  })

  it('reconciles the open exploration run when the deletion happened somewhere else', () => {
    const at = board.indexOf('const reconcileOpenFuzzingRunAgainstHistory')
    expect(at, 'the exploration reconciliation should exist').toBeGreaterThan(-1)
    const body = board.slice(at, board.indexOf('\n}', at) + 2)

    expect(body, 'the open run is matched by its own id').toContain('fuzzingResult.value?.id')
    expect(body, 'against the authoritative reloaded list')
      .toMatch(/fuzzingRuns\.value\.some\(run => run\.id === openRunId\)/)
    expect(body, 'closing must clear the deep link too').toContain('dismissFuzzingResult()')
    // Its own string. The verification copy says "verification result" and offers "re-run to get a
    // conclusion" — reusing it would describe bounded exploration, which yields candidate findings, as
    // formal verification. That distinction is a product invariant, not wording taste.
    expect(body, 'and tell the user why the surface closed')
      .toContain('openFuzzingRunDeletedElsewhere')
    // The guard that keeps this from destroying live findings: an empty list is also what a still-loading
    // or scoped-empty history looks like, so only absence from a POPULATED list counts as deleted.
    expect(body, 'an empty history must not be read as "deleted"')
      .toContain('fuzzingRuns.value.length === 0')

    /*
     * The guard the other two kinds do not need, and the one this reconciliation shipped without.
     * Exploration is the only paginated run history: `FUZZ_RUN_HISTORY_PAGE_SIZE` is 25, the backend orders
     * `createdAt DESC` against a 100-run stored quota, and `refreshRunHistory` reloads page 0 with
     * `append: false`, replacing the list. So a run opened from page 2 is legitimately absent from the
     * reloaded list — closing it and blaming a deletion would be a fabricated cause, the same defect class
     * as the 404 that started this file. Verification and simulation load their entire list, so absence
     * there is real.
     */
    expect(body, 'a truncated page must not be read as "deleted"')
      .toContain('fuzzingRunsHasMore.value')
  })

  it('reconciles a replaying finding, whose dialog is already closed', () => {
    /*
     * Exploration's version of the verification blind spot, and it needed a teardown as well as an id:
     * `selectAndPlayFuzzingFinding` calls `closeFuzzingResult()` before opening the bar, so `fuzzingResult`
     * is null throughout a finding replay — the reconciliation returned early, and `dismissFuzzingResult`
     * would only have closed a dialog that was already closed, leaving the bar animating candidate evidence
     * for a run the server had dropped.
     */
    const at = board.indexOf('const reconcileOpenFuzzingRunAgainstHistory')
    const body = board.slice(at, board.indexOf('\n}', at) + 2)

    expect(body, 'the replaying finding carries the owning run id')
      .toMatch(/\?\?\s*activeFuzzingFinding\.value\?\.fuzzTaskId/)
    // The replay needs the trace teardown, not the dialog dismissal — that is the half that was missing.
    expect(body, 'a replaying finding must have its bar closed')
      .toContain('closeTraceAnimation()')
    expect(body, 'and its evidence dropped').toContain('savedTraces.value = []')
    expect(body, 'and its own wording, since no panel was closed')
      .toContain('openFuzzingFindingRunDeletedElsewhere')
    // Gated on what is actually playing, not on the ref: `activeFuzzingFinding` is not cleared when a
    // simulation replay starts over it, so the ref alone does not mean a finding is on screen.
    expect(body, 'a finding that is not the visible playback must not claim its replay ended')
      .toMatch(/activePlaybackKind\.value === 'fuzzing'/)

    // The pagination guard must still precede the teardown, or an off-page run gets torn down over a cause
    // that was refuted in e9452f8.
    expect(body.indexOf('fuzzingRunsHasMore.value'), 'the page guard must come before any teardown')
      .toBeLessThan(body.indexOf('closeTraceAnimation()'))
  })

  it('hangs off the same successful-reload hook as verification', () => {
    // Not off the delete handler: the same-tab path is unreachable, and a failed reload leaves the lists
    // stale, so treating that as a deletion would close a surface over a transport error.
    const at = board.indexOf('const reconcileOpenRunAgainstHistory')
    expect(at, 'the shared entry point should exist').toBeGreaterThan(-1)
    const body = board.slice(at, board.indexOf('\n}', at) + 2)
    expect(body, 'the shared entry point must invoke the exploration reconciliation')
      .toContain('reconcileOpenFuzzingRunAgainstHistory()')
    expect(body, 'and the verification one, so neither kind can be dropped')
      .toContain('reconcileOpenVerificationRunAgainstHistory()')
    expect(body, 'and the simulation one')
      .toContain('reconcileOpenSimulationRunAgainstHistory()')

    /*
     * Every declared reconciliation must be dispatched, checked by enumeration rather than by listing the
     * three names above. Deleting a name from the dispatcher is the mutation that kills a whole run kind's
     * reconciliation while leaving its function, its wording and its own tests intact — measured: removing
     * the simulation call left all 11 tests green until this assertion existed. A hand-picked list cannot
     * catch that for the *next* kind, which is the failure mode `known-traps.md` calls a guard that scans a
     * subset.
     */
    const declared = [...board.matchAll(/^const (reconcileOpen\w+AgainstHistory) = /gm)]
      .map(match => match[1])
      .filter(name => name !== 'reconcileOpenRunAgainstHistory')
    expect(declared.length, 'the per-kind reconciliations should be discoverable').toBeGreaterThan(2)
    for (const name of declared) {
      expect(body, `${name} is declared but never dispatched`).toContain(`${name}()`)
    }

    const reloadAt = board.indexOf('const refreshRunHistory = async')
    const reload = board.slice(reloadAt, board.indexOf('\n}', reloadAt) + 2)
    expect(reload, 'and that entry point still runs only after a successful reload')
      .toMatch(/if \(results\.every\(Boolean\)\) reconcileOpenRunAgainstHistory\(\)/)
  })
})

/**
 * The simulation half, which was the worst of the three and the last to be wired.
 *
 * A trajectory's primary surface is the replay *bar*, a `role="region"` sibling of the board rather than a
 * modal — so the assistant stays one click away for the whole replay (the chat button is gated on
 * `isBoardDataReady` alone), and `DeleteSimulationTraceTool` ships. Deleting the trajectory being replayed
 * left it animating, and its Run details → download still read `historyPersistence.runId` off the deleted
 * record, reproducing the same misleading "may be a record saved before model persistence was enabled" 404.
 */
describe('surfaces for a deleted simulation trajectory', () => {
  const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
  const i18n = readFileSync(join(process.cwd(), 'src/assets/i18n.ts'), 'utf8')

  it('reconciles the replayed trajectory when the deletion happened somewhere else', () => {
    const at = board.indexOf('const reconcileOpenSimulationRunAgainstHistory')
    expect(at, 'the simulation reconciliation should exist').toBeGreaterThan(-1)
    const body = board.slice(at, board.indexOf('\n}', at) + 2)

    // `historyPersistence.runId` is the saved trace id, which is also how `simulationRuns` is keyed, so
    // one id covers both the bar and the details dialog.
    expect(body, 'the open run is matched by its persisted trace id')
      .toContain('lastSimulationResult.value?.historyPersistence?.runId')
    expect(body, 'against the authoritative reloaded list')
      .toMatch(/simulationRuns\.value\.some\(run => run\.id === openRunId\)/)
    expect(body, 'an empty history must not be read as "deleted"')
      .toContain('simulationRuns.value.length === 0')
    expect(body, 'and it tears the surfaces down through the shared helper')
      .toContain('dismissSimulationSurfacesForDeletedRun()')
    expect(body, 'with wording of its own').toContain('openSimulationRunDeletedElsewhere')

    // The guard the other two kinds do not need. Their ids come from refs cleared on close, so "has an
    // id" implies "is on screen"; `lastSimulationResult` deliberately outlives every surface, so without
    // this the user would be told a panel closed while nothing was showing.
    expect(body, 'it must act only while a simulation surface is actually up')
      .toMatch(/if \(!simulationAnimationState\.value\.visible\) return/)
  })

  it('closes the bar, the details dialog and the deep link exactly once', () => {
    const at = board.indexOf('const dismissSimulationSurfacesForDeletedRun')
    expect(at, 'the teardown helper should exist').toBeGreaterThan(-1)
    const body = board.slice(at, board.indexOf('\n}', at) + 2)

    // For `run=simulation:<id>` the bar is the addressed surface and its closer owns the link, so the
    // dialog then takes the internal-transition closer. With no bar, the dialog's close is the
    // user-facing one and has to carry the link itself.
    expect(body, 'the bar is closed through the closer that clears the deep link')
      .toContain('closeSimulationTimeline()')
    expect(body, 'and with the bar gone the dialog must not clear the link a second time')
      .toContain('closeSimulationResultDialog()')
    expect(body, 'while a dialog open without a bar takes the URL-clearing closer')
      .toContain('dismissSimulationResultDialog(lastSimulationResult.value?.historyPersistence.runId)')

    // The replay states are derived from this owner, so clearing it drops the whole run.
    expect(board, 'states must have no separately writable copy')
      .not.toMatch(/savedSimulationStates\.value\s*=/)
    expect(body, 'the run manifest must not survive deletion')
      .toContain('lastSimulationResult.value = null')
  })

  it('leaves the in-tab deletion path alone, and says why', () => {
    const at = board.indexOf('const deleteSimulationRun')
    expect(at, 'the deletion handler should exist').toBeGreaterThan(-1)
    const body = board.slice(at, board.indexOf('\n}', board.indexOf('finally', at)))

    // Unlike verification, this handler adds no teardown: reaching it means the history panel is open,
    // and both simulation surfaces exclude it. Asserted so a future reader does not "complete" the
    // pattern by adding a same-tab branch whose condition can never hold — and so the reasoning is
    // pinned to the code rather than living only in a comment.
    expect(body, 'no surface teardown belongs here').not.toContain('dismissSimulationSurfacesForDeletedRun')
    expect(body, 'and the reason must be recorded where the absence is')
      .toContain('reconcileOpenSimulationRunAgainstHistory')
  })

  it('describes a trajectory rather than a verdict or a candidate finding', () => {
    // Three kinds, three strings. Reusing verification's copy would offer "re-run to get a conclusion"
    // for something that produces neither a conclusion nor findings, and would say "panel was closed"
    // when what stopped was a replay.
    const keyAt = i18n.indexOf('openSimulationRunDeletedElsewhere:', i18n.indexOf('openSimulationRunDeletedElsewhere:') + 1)
    expect(keyAt, 'both locales should define the key').toBeGreaterThan(-1)
    const english = i18n.slice(keyAt, keyAt + 300)
    expect(english, 'names the artifact as a trajectory').toMatch(/trajectory/i)
    expect(english, 'says the replay stopped, not that a panel closed').toMatch(/playback ended/i)
    expect(english, 'names both out-of-band causes').toMatch(/assistant or another tab/i)
    expect(english, 'and does not promise a conclusion').not.toMatch(/conclusion/i)
  })
})
