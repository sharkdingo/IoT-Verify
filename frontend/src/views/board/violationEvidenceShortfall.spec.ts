import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * A verdict that reports more violations than it can show evidence for has to say so.
 *
 * `violatedSpecCount` and the counterexample list come from independent sources: the backend counts
 * `specResults` with `outcome == VIOLATED` (`VerificationServiceImpl.countViolatedSpecs`), while a trace
 * exists only where NuSMV returned a *parseable* counterexample. The product already acknowledges the
 * shortfall as a real state — `app.someViolationsHaveNoReplayableCounterexample` says "Some
 * specifications were violated, but NuSMV did not return a parseable counterexample" — and the run
 * history panel renders it (`TraceHistoryPanel.vue`).
 *
 * The verification result dialog did not, and that is the surface a user lands on the instant a run
 * finishes. So the dialog could read "Violated: 2" beside a list holding one counterexample, or — worse,
 * because the whole section is `v-if="traces?.length"` — beside no list at all, with nothing accounting
 * for the difference. The two readings a user takes from that are both wrong: that the tool lost their
 * evidence, or that one violation is somehow not real.
 *
 * Source-text assertions rather than a mounted render: `Board.vue` is far too large to mount cheaply
 * (see `actionDockHierarchy.spec.ts`).
 */
describe('violation evidence shortfall', () => {
  const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
  const history = readFileSync(join(process.cwd(), 'src/components/TraceHistoryPanel.vue'), 'utf8')

  const dialog = (() => {
    const start = board.indexOf('data-testid="verification-result-dialog"')
    expect(start, 'the verification result dialog should exist').toBeGreaterThan(-1)
    const end = board.indexOf('<PlaybackTerminal', start)
    expect(end, 'the playback terminal should follow it').toBeGreaterThan(start)
    return board.slice(start, end)
  })()

  it('states the shortfall in the result dialog, not only in run history', () => {
    // The history panel already owns this sentence; the point is that the dialog must too.
    expect(history, 'run history explains the shortfall')
      .toContain('someViolationsHaveNoReplayableCounterexample')
    expect(dialog, 'and so must the dialog the user lands on after a run')
      .toContain('someViolationsHaveNoReplayableCounterexample')
    expect(dialog, 'with a stable hook for the notice')
      .toContain('data-testid="verification-evidence-shortfall"')
  })

  it('derives the shortfall from the two independent counts', () => {
    const at = board.indexOf('const verificationEvidenceShortfall')
    expect(at, 'the shortfall computed should exist').toBeGreaterThan(-1)
    const body = board.slice(at, at + 900)

    // Violated *specifications*, not the run outcome: a run is VIOLATED as a whole, which says nothing
    // about how many specs failed or how many produced evidence.
    expect(body, 'counts violated specification results').toContain('verificationSpecResultSummary')
    expect(body, 'against the counterexamples actually parsed').toMatch(/traces/)
    // Must not report a shortfall when the run is not violated at all: an INCONCLUSIVE run legitimately
    // has fewer traces than specs and is explained by its own notice.
    expect(body, 'scoped to a violated verdict').toContain('VIOLATED')
  })

  it('renders the notice independently of the counterexample section', () => {
    /*
     * The zero-trace case is the one that matters most and the easiest to miss: the counterexample
     * section is `v-if="verificationResult?.traces?.length"`, so with no parseable trace it does not
     * render, and a notice placed inside it would disappear exactly when it is needed. The assertion is
     * positional: the notice must sit outside that section's element.
     */
    const sectionAt = dialog.indexOf('aria-labelledby="violations-title"')
    const noticeAt = dialog.indexOf('data-testid="verification-evidence-shortfall"')
    expect(sectionAt, 'the counterexample section should exist').toBeGreaterThan(-1)
    expect(noticeAt, 'the notice should exist').toBeGreaterThan(-1)
    expect(noticeAt, 'the notice comes before the conditional section, so it survives an empty list')
      .toBeLessThan(sectionAt)
  })
})
