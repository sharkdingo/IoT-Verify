// @vitest-environment node
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/** Guard the availability, request identity and run-level ownership of the SMV model download. */
describe('run artifact placement', () => {
  const root = join(process.cwd(), 'src')
  const board = readFileSync(join(root, 'views/Board.vue'), 'utf8')
  const history = readFileSync(join(root, 'components/TraceHistoryPanel.vue'), 'utf8')

  const verificationDialog = () => {
    const start = board.indexOf('data-testid="verification-result-dialog"')
    const end = board.indexOf('<PlaybackTerminal', start)
    expect(start).toBeGreaterThan(-1)
    expect(end).toBeGreaterThan(start)
    return board.slice(start, end)
  }
  const counterexampleRunInfo = () => {
    const start = board.indexOf('data-testid="counterexample-run-info"')
    expect(start).toBeGreaterThan(-1)
    const end = board.indexOf('<SimulationTimeline', start)
    expect(end, 'simulation replay should follow counterexample replay').toBeGreaterThan(start)
    return board.slice(start, end)
  }

  it('disables the download with a reason instead of hiding it', () => {
    // A control that silently vanishes is indistinguishable from a missing feature — which is exactly
    // how this feature read while the flag was never sent. Absence is now stated.
    const dialog = verificationDialog()
    const at = dialog.indexOf('data-testid="verification-result-download-smv"')
    const element = dialog.slice(dialog.lastIndexOf('<button', at), dialog.indexOf('</button>', at))
    expect(element, 'unavailability disables rather than removes')
      .toContain(':disabled="!verificationRunSmvAvailable"')
    expect(element, 'the button itself carries no v-if').not.toContain('v-if')
    expect(dialog, 'and the reason is shown next to it')
      .toContain('data-testid="verification-result-smv-unavailable"')

    // Both conditions, or the click 404s: a preview-only run has no id, and a run persisted before
    // the model was stored holds no model.
    const guardAt = board.indexOf('const verificationRunSmvAvailable')
    expect(guardAt, 'the availability computed should exist').toBeGreaterThan(-1)
    const guard = board.slice(guardAt, guardAt + 320)
    expect(guard, 'requires a stored model').toContain('hasSmvModel === true')
    expect(guard, 'and an addressable run id').toContain('historyPersistence?.runId')
  })

  it('hides the icon ligatures inside the counterexample action buttons from assistive tech', () => {
    // A Material Symbols span renders its ligature *as text*, so without `aria-hidden` the glyph name
    // joins the accessible name: these two announced as "build Fix Rules" and "play_arrow View". Found
    // by an E2E `getByRole('button', { name: /^View$/ })` timing out — the button was on screen the
    // whole time under a different name, which is also what a screen reader reads out.
    const listAt = board.indexOf('data-testid="verification-trace-fix"')
    expect(listAt, 'the counterexample action row should exist').toBeGreaterThan(-1)
    const row = board.slice(listAt, board.indexOf('</div>', board.indexOf('app.replay', listAt)))
    const icons = [...row.matchAll(/<span class="material-symbols-outlined[^"]*"([^>]*)>/g)]
    expect(icons.length, 'both action buttons carry an icon').toBeGreaterThanOrEqual(2)
    for (const [, attributes] of icons) {
      expect(attributes, 'every icon ligature must be hidden from the accessible name')
        .toContain('aria-hidden="true"')
    }
  })

  it('resolves the run id in a handler rather than asserting it non-null in the template', () => {
    // `downloadVerificationRunSmv(verificationResult.historyPersistence!.runId!)` typechecked only
    // because the same button carried `:disabled="!verificationRunSmvAvailable"`. The assertion's
    // safety lived in a *different attribute*, so renaming or dropping the guard would have put a
    // silent `undefined` into the request path with no type error. Both handlers now re-check.
    const code = board
      .split('\n')
      .filter(line => !line.trim().startsWith('*') && !line.trim().startsWith('//'))
      .join('\n')
    expect(code, 'no non-null assertion chain on the persistence record')
      .not.toContain('historyPersistence!')

    for (const handler of ['downloadCurrentVerificationRunSmv', 'downloadCurrentSimulationRunSmv']) {
      const at = board.indexOf(`const ${handler}`)
      expect(at, `${handler} should exist`).toBeGreaterThan(-1)
      const body = board.slice(at, at + 260)
      expect(body, `${handler} must re-check the id`).toContain("typeof runId !== 'number'")
      expect(body, `${handler} must return without calling on a missing id`).toContain('return')
    }
  })

  it('keeps run-level artifacts out of counterexample surfaces', () => {
    // The rule this whole spec exists for. Asserted on run information and on the history panel's
    // per-trace row, because the same category error appeared in both.
    const runInfo = counterexampleRunInfo()
    expect(runInfo, 'no artifact download in counterexample run information')
      .not.toContain('download-counterexample-smv')
    expect(runInfo, 'and no trace-keyed download call').not.toContain('downloadVerificationTraceSmv')

    expect(history, 'no per-counterexample download row in history')
      .not.toContain('download-verification-trace-smv')
    expect(history, 'the run row keeps the single copy')
      .toContain('download-verification-run-smv-')
    expect(history, 'and the simulation trajectory keeps its own run-level one')
      .toContain('download-simulation-trace-smv-')
  })

})
