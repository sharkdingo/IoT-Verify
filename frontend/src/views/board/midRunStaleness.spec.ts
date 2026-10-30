import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * A verdict that arrives after the board changed under it must arrive stale.
 *
 * `markVerificationResultStale` can only raise the flag on a result that *exists* — it reads
 * `verificationResult.value` / `lastSimulationResult.value` and returns early on null. Both run
 * paths null their result ref before submitting, so for the entire duration of a run there is
 * nothing to mark: a device edit, a rule change or an applied fix made while NuSMV is working
 * marked nothing at all. The completion paths then wrote `stale = false` unconditionally, so the
 * verdict presented itself as describing the canvas the user was now looking at — and kept offering
 * the per-counterexample Fix action, computed against a scene that no longer existed. That is the
 * one thing the repo's staleness rule exists to prevent.
 *
 * A watcher on the result ref cannot close this: the gap is precisely the window in which the ref
 * is null. The mechanism has to be a counter captured at submission and compared on arrival, which
 * is what this pins.
 *
 * It counts `semanticSceneChangeCount` rather than `boardMutationAdmissionEpoch` on purpose, and
 * the difference is load-bearing: the epoch counts *admitted mutations*, incremented before
 * `trackSemanticChange` is consulted, so the undo-history preview and clear advance it while
 * touching only the journal. Comparing that would mark a perfectly current verdict stale. The
 * counter increments inside `markVerificationResultStale` itself, so the two mechanisms cannot
 * disagree about what counts as a semantic change.
 *
 * Asserted over `Board.vue`'s source: the logic is inline in a component too large to mount
 * cheaply, and extracting a comparison of one module-scoped
 * counter would need the reactive scope injected — the pass-through layer the frontend rules
 * forbid. `loopRangeAgreement.spec.ts` uses the same approach for the same reason.
 */
describe('a run that completes after the board changed', () => {
  const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')

  /** Strip comments before locating anything: this file explains the mechanism in prose beside it. */
  const stripComments = (source: string): string => source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/[^\n]*/g, '')

  /** Comment-free source, so a brace scan cannot be thrown off by prose or by commented-out code. */
  const code = stripComments(board)

  /**
   * One named function body, delimited by matching braces from its arrow.
   *
   * Cutting at the next `\n}` instead is what a first draft did, and it silently produced a body
   * containing only the signature: `handleSimulate` declares a multi-line inline parameter type
   * whose closing `}` sits in column 0. Every assertion then ran against the wrong text, and the
   * failure blamed the code rather than the extractor.
   */
  const bodyOf = (declaration: string): string => {
    const at = code.indexOf(declaration)
    expect(at, `${declaration} should exist in Board.vue`).toBeGreaterThan(-1)
    const open = code.indexOf('=> {', at)
    expect(open, `${declaration} should be an arrow function`).toBeGreaterThan(at)

    let depth = 0
    for (let index = open + 3; index < code.length; index++) {
      if (code[index] === '{') depth += 1
      else if (code[index] === '}') {
        depth -= 1
        if (depth === 0) return code.slice(at, index + 1)
      }
    }
    throw new Error(`${declaration} has unbalanced braces`)
  }

  it('counts semantic changes from the hook that owns the staleness rule', () => {
    const hook = bodyOf('const markVerificationResultStale = ')
    expect(hook, 'the counter must advance from the same callback that flags an open result')
      .toMatch(/semanticSceneChangeCount \+= 1/)
    // The epoch counts admitted mutations, which includes journal-only operations. Using it here
    // would mark a current verdict stale after an undo-history preview.
    expect(hook, 'the counter is not the mutation-admission epoch')
      .not.toContain('boardMutationAdmissionEpoch')
  })

  /*
   * Every function that awaits a run and then writes a staleness flag. `watchSimulationTask` is the
   * one reached from the task inbox, which is when a user is most likely to keep editing; it had the
   * same unconditional `false` as the others. `watchVerificationTask` is absent on purpose — it
   * delegates to `pollAsyncVerification`, which owns the capture, so a second one there would
   * measure the wrong window.
   *
   * The third column names the function the path delegates its arrival write to, or `null` when the
   * comparison is inline. The simulation paths acquired one: the flag and the run manifest it qualifies
   * must be written as a pair (a run arriving behind an open replay is deferred, and adopting only half
   * of it repainted the visible run's header with another run's semantics), so both now hand the captured
   * count to `adoptSimulationRunResult`. This test resolves that one hop instead of accepting a
   * comparison-free body — the invariant is unchanged, and dropping the assertion because the code moved
   * is how a guard stops guarding.
   */
  it.each([
    ['const handleVerify = ', 'verificationResultStale', 'adoptVerificationRunResult'],
    ['const handleSimulate = ', 'simulationResultStale', 'adoptSimulationRunResult'],
    ['const pollAsyncVerification = ', 'verificationResultStale', 'adoptVerificationRunResult'],
    ['const watchSimulationTask = ', 'simulationResultStale', 'adoptSimulationRunResult']
  ])('%s captures the counter at submission and compares it on arrival', (declaration, flag, adopter) => {
    const body = bodyOf(declaration)

    // The trailing `$` matters: without it this also matches the *comparison*
    // (`const boardChangedDuringRun = semanticSceneChangeCount !== …`), so the count came out at 2
    // and the captured name was the comparison's.
    const captures = [...body.matchAll(/const (\w+) = semanticSceneChangeCount$/gm)]
    expect(captures, `${declaration} must snapshot the counter before awaiting the run`)
      .toHaveLength(1)
    const captured = captures[0][1]

    // The comparison must reach the flag. Asserting only that a comparison exists somewhere would
    // pass on a captured value that is computed and then dropped.
    if (adopter === null) {
      const comparison = new RegExp(`semanticSceneChangeCount !== ${captured}`)
      expect(body, `${declaration} must compare the counter across the run`).toMatch(comparison)
    } else {
      // One hop, resolved rather than trusted: the captured count must actually be handed to the
      // adopter, and the adopter must be what performs the comparison and writes the flag. Checking
      // only that the call exists would pass if it were handed the live counter, which always compares
      // equal to itself and would report every run as current.
      const handoff = new RegExp(`${adopter}\\([^)]*\\b${captured}\\b`)
      expect(body, `${declaration} must hand the captured count to ${adopter}`).toMatch(handoff)

      const adoptBody = bodyOf(`const ${adopter} = `)
      const parameter = /\(\s*[^,]+,\s*(\w+)\s*(?::[^)]*)?\)/.exec(adoptBody)
      expect(parameter, `${adopter} should take the captured count as its second parameter`).not.toBeNull()
      const comparison = new RegExp(`semanticSceneChangeCount !== ${parameter![1]}`)
      expect(adoptBody, `${adopter} must compare the counter across the run`).toMatch(comparison)
      expect(adoptBody, `${adopter} must write ${flag} from that comparison`)
        .toMatch(new RegExp(`${flag}\\.value = semanticSceneChangeCount !== ${parameter![1]}`))
    }

    // Every write of the flag that happens *after* the capture must be derived from the comparison
    // rather than a bare `false`. Writes before the capture are the pre-submission reset, which is
    // legitimate — and which lives in `handleVerify`/`handleSimulate` but not in
    // `pollAsyncVerification`, so the count of writes differs by path and cannot be asserted.
    const captureAt = body.search(/const \w+ = semanticSceneChangeCount$/m)
    const arrivalWrites = [...body.matchAll(new RegExp(`${flag}\\.value =([^\\n]*)`, 'g'))]
      .filter(match => (match.index ?? 0) > captureAt)
    if (adopter === null) {
      expect(arrivalWrites.length, `${declaration} should write ${flag} on arrival`).toBeGreaterThan(0)
    } else {
      // A delegating path must not also write the flag itself: two writers is how the banner ends up
      // describing a different run than the header, which is the defect the adopter exists to prevent.
      expect(arrivalWrites.length, `${declaration} must leave the ${flag} write to ${adopter}`).toBe(0)
    }
    for (const write of arrivalWrites) {
      expect(write[1].trim(), `${flag} must not be forced current after the run completed`)
        .not.toBe('false')
    }
  })

  /*
   * The other half of pairing the flag with the manifest: a run that arrives while a replay is on screen
   * must not be adopted at all.
   *
   * `lastSimulationResult` is what every simulation surface describes — the replay bar reads its
   * attack/privacy chips, step counts and `modelSnapshot` from it while animating `savedSimulationStates`
   * derived from that owner — so writing it on arrival would replace the visible trajectory while its
   * canvas still owns the opened run's frozen scene. Reachable because
   * `ensureHistoricalPlaybackUiAdmission` does not consider `isSimulating`: starting an async run and then
   * replaying something from history while waiting is ordinary use.
   *
   * Asserted by ordering rather than by counting calls: what makes it correct is that no adoption is
   * reachable before the branch that returns on a visible replay.
   */
  it.each([
    ['const handleSimulate = '],
    ['const watchSimulationTask = ']
  ])('%s defers a run that arrives behind an open replay', declaration => {
    const body = bodyOf(declaration)

    const deferralAt = body.search(
      /if \([^)]*simulationAnimationState\.value\.visible[^)]*\) \{/
    )
    expect(deferralAt, `${declaration} should check for a visible replay before adopting`)
      .toBeGreaterThan(-1)

    const firstAdoption = body.indexOf('adoptSimulationRunResult(')
    expect(firstAdoption, `${declaration} should adopt the result somewhere`).toBeGreaterThan(-1)
    expect(firstAdoption, 'no adoption may precede the visible-replay check').toBeGreaterThan(deferralAt)

    // And the deferral must tell the user, or the run silently vanishes: the trajectory is in history and
    // the task is complete, but nothing on screen changed.
    const deferralBody = body.slice(deferralAt, body.indexOf('return', deferralAt))
    expect(deferralBody, 'a deferred run must be reported, not dropped')
      .toContain('notifyAutomaticPlaybackDeferredForReplay(result,')
    // And with the reason that applies. The editor notice names the open editor as the cause, so using it
    // for a replay would assert something the client knows to be false — the same defect class as the
    // "saved before model persistence was enabled" 404 in `deletedRunSurfaces.spec.ts`.
    expect(deferralBody, 'the editor notice states a cause that does not apply here')
      .not.toMatch(/notifyAutomaticPlaybackDeferred\(\)/)
  })

  it('does not open a modal exploration result over a running replay', () => {
    /*
     * The third run kind, and the same rule from the other direction: `FuzzingResultDialog` is
     * `aria-modal="true"`, so presenting it mid-replay takes the focus trap and the background scroll lock
     * while the replay bar — deliberately a non-modal `role="region"` — keeps animating underneath, putting
     * the user's own playback controls behind a trap they did not ask for.
     *
     * `handleFuzzing` already declines to present when its panel is closed and routes the run to the task
     * notification; `watchFuzzingTask` presented unconditionally, which is the only reason the two paths
     * disagreed about the same completion.
     */
    const body = bodyOf('const watchFuzzingTask = ')
    const presentAt = body.indexOf('presentFuzzingRun(run)')
    expect(presentAt, 'the watch path should present its completed run').toBeGreaterThan(-1)

    const guardAt = body.indexOf('if (isModelPlaybackActive.value) {')
    expect(guardAt, 'presenting must be gated on playback').toBeGreaterThan(-1)
    expect(guardAt, 'and the gate must come first').toBeLessThan(presentAt)

    // Deferred, not dropped: the notification is what the task inbox and the history badge read, and the
    // outcome is still reported — a `BUDGET_EXHAUSTED` run must not be softened into a clean result, which
    // is why the severity is taken from the shared completion message rather than fixed here.
    const guardBody = body.slice(guardAt, body.indexOf('return', guardAt))
    expect(guardBody, 'a deferred run must reach the task notification')
      .toContain('markFuzzNotificationUnread')
    expect(guardBody, 'and report its own outcome').toContain('fuzzingCompletionMessage(run)')
    expect(guardBody, 'and say why nothing opened').toContain('fuzzResultDeferredForPlayback')
  })

  /*
   * The same rule for the last run kind, where it is worse than stacking. Opening a counterexample calls
   * `closeResultDialog`, which nulls `verificationResult` — so the dialog is *closed* for the whole time the
   * replay bar animates, and re-assigning the ref on arrival raised an `aria-modal` dialog over the trace the
   * user was watching. Reachable identically: playback admission does not consider `isVerifying`.
   *
   * Both paths are covered because the two windows are different — `pollAsyncVerification` awaits a poll loop,
   * `handleVerify` awaits one request — and only the async one has a presenting/non-presenting split to reuse.
   */
  it.each([
    ['const pollAsyncVerification = '],
    ['const handleVerify = ']
  ])('%s does not raise the result dialog over a running replay', declaration => {
    const body = bodyOf(declaration)

    const guardAt = body.search(/isModelPlaybackActive\.value/)
    expect(guardAt, `${declaration} should check for playback before presenting`).toBeGreaterThan(-1)

    // Adoption opens the result and owns its URL; every call must remain after playback admission.
    const presentations = [...body.matchAll(/adoptVerificationRunResult\(/g)]
    expect(body).not.toMatch(/verificationResult\.value = (?!null)/)
    expect(presentations.length, `${declaration} should present its verdict somewhere`).toBeGreaterThan(0)
    for (const presentation of presentations) {
      expect(presentation.index, 'no presentation may precede the playback check').toBeGreaterThan(guardAt)
    }

    // Deferred, not dropped. The verdict is announced without `presenting: true` (which suppresses the
    // toast on the assumption the dialog is showing it) and the notice says where it went.
    const deferralBody = body.slice(guardAt)
    expect(deferralBody, 'a deferred verdict must still be announced')
      .toMatch(/notifyVerificationOutcome\((?!verificationResult)[^)]*\)(?!,)/)
    expect(deferralBody, 'and say why no dialog opened')
      .toContain('verificationResultDeferredForPlayback')
    // And run history must be reloaded, since it becomes the only route to this verdict.
    expect(deferralBody, 'run history is the only surface left, so it must be current')
      .toMatch(/loadVerificationRuns\(/)
  })

  it('does not blame the replay when nothing would have been presented anyway', () => {
    // `pollAsyncVerification` also serves runs whose panel is closed, which never open a dialog. Saying
    // "the dialog did not open because a replay is running" to that user names a cause the client knows to
    // be false — the same defect class as reusing the editor notice for a replay deferral. So the deferral
    // is conditioned on presentation, not on playback alone. `handleVerify` needs no equivalent: it always
    // presents.
    const body = bodyOf('const pollAsyncVerification = ')
    const guard = /const deferForPlayback = (\w+) && isModelPlaybackActive\.value/.exec(body)
    expect(guard, 'the deferral must be conjoined with the would-present condition').not.toBeNull()
    expect(body, `${guard![1]} should be the presentation condition`)
      .toMatch(new RegExp(`const ${guard![1]} = options\\.presentResult \\|\\| showVerificationPanel\\.value`))
  })

  it('leaves a freshly loaded historical run current', () => {
    // The counter answers "did the board change while *this* run was in flight". A run read back
    // from history was not in flight, so its flag stays a plain `false` — comparing a counter there
    // would make the banner depend on unrelated editing history. Pinned so the mechanism above is
    // not spread onto paths it does not describe.
    const open = bodyOf('const openVerificationRun = ')
    expect(open, 'a history read is not a run in flight').toContain('verificationResultStale.value = false')
    expect(open, 'and needs no counter comparison').not.toContain('semanticSceneChangeCount')
  })
})
