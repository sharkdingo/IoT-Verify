import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Where the SMV model download may appear, and where it may not.
 *
 * The model is a **scene-level artifact**: one model is generated per run, and every counterexample
 * that run produced came out of that same model. So the download belongs to the run surfaces
 * (verification result, simulation result, and one button per run row in history) and must not appear
 * on a per-counterexample surface, which would imply one model per counterexample.
 *
 * Three defects sit behind these assertions, all of which typechecked and rendered fine:
 *
 * - the button was gated on a `hasSmvModel` the backend never sent, so it never rendered anywhere and
 *   the feature looked absent rather than broken
 * - it was a footer `--secondary` control beside Close, i.e. styled as an afterthought, which is how
 *   users failed to find it even once it did render
 * - it was offered per counterexample — in the details dialog and on every trace row in history —
 *   handing out the same file N times under a name implying N different models
 *
 * Source-text assertions rather than a mounted render: `Board.vue` is far too large to mount cheaply
 * for this structure check. The end-to-end behaviour — that clicking actually delivers a
 * model file — is `e2e/smv-model-download.spec.ts`; this pins the structure that E2E cannot express.
 */
describe('run artifact placement', () => {
  const root = join(process.cwd(), 'src')
  const board = readFileSync(join(root, 'views/Board.vue'), 'utf8')
  const history = readFileSync(join(root, 'components/TraceHistoryPanel.vue'), 'utf8')
  const boardApi = readFileSync(join(root, 'api/board.ts'), 'utf8')

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

  it('puts the model download in the verification result, as a primary action', () => {
    const dialog = verificationDialog()
    const at = dialog.indexOf('data-testid="verification-result-download-smv"')
    expect(at, 'the run download should live in the verification result').toBeGreaterThan(-1)

    // Primary, not secondary: the button's own class list, read from its element.
    const element = dialog.slice(dialog.lastIndexOf('<button', at), dialog.indexOf('</button>', at))
    expect(element, 'the artifact download is a primary action')
      .toContain('iot-dialog-btn--primary')
    expect(element, 'and must not be styled as a secondary afterthought')
      .not.toContain('iot-dialog-btn--secondary')

    // In the body, not the footer. The footer is where it used to hide beside Close.
    const footerAt = dialog.indexOf('iot-dialog__footer')
    expect(footerAt, 'the dialog should have a footer').toBeGreaterThan(-1)
    expect(at, 'the artifact belongs in the body, not the footer').toBeLessThan(footerAt)
  })

  it('names the artifact and what it covers, so the reader knows before clicking', () => {
    const dialog = verificationDialog()
    const sectionAt = dialog.indexOf('data-testid="verification-run-artifact"')
    expect(sectionAt, 'the artifact section should exist').toBeGreaterThan(-1)
    const section = dialog.slice(sectionAt, dialog.indexOf('</section>', sectionAt))
    expect(section, 'the section is labelled as a run artifact').toContain('app.runArtifact')
    expect(section, 'the artifact is named').toContain('app.smvModelArtifactTitle')
    // The scope line reads the run's own frozen snapshot, so it cannot drift from what was checked.
    expect(section, 'and its scope is stated from the run snapshot')
      .toContain('app.smvModelArtifactScope')
    for (const field of ['deviceCount', 'ruleCount', 'specificationCount']) {
      expect(section, `scope reads ${field} from modelSnapshot`).toContain(`modelSnapshot.${field}`)
    }
  })

  it('shows counterexample actions and requirement outcomes before the downloadable model', () => {
    const dialog = verificationDialog()
    const positions = ['verification-trace-replay', 'spec-results-section', 'verification-run-artifact', 'run-context-section']
      .map(id => dialog.indexOf(`data-testid="${id}"`))
    positions.forEach(position => expect(position).toBeGreaterThan(-1))
    expect(positions[0]).toBeLessThan(positions[2]!)
    expect(positions[1]).toBeLessThan(positions[2]!)
    expect(positions[2]).toBeLessThan(positions[3]!)
  })

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

  it('uses the shared result projection on sync, async and history simulation paths', () => {
    expect([...board.matchAll(/simulationResultFromTrace\(trace\)/g)]).toHaveLength(3)
    expect([...board.matchAll(/summarizeSimulationResult\(result\)/g)]).toHaveLength(2)
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

  it('applies the same shape to the simulation result', () => {
    // Simulations retain an explicit run-level download after removing their details dialog.
    const at = board.indexOf('data-testid="simulation-run-artifact"')
    expect(at, 'the simulation artifact section should exist').toBeGreaterThan(-1)
    const downloadAt = board.indexOf('data-testid="simulation-result-download-smv"')
    expect(downloadAt, 'the simulation download should exist').toBeGreaterThan(-1)
    const element = board.slice(board.lastIndexOf('<button', downloadAt), board.indexOf('</button>', downloadAt))
    expect(element, 'an explicit download action in the terminal').toContain('playback-run__download')
    expect(element, 'disabled with a reason here too')
      .toContain(':disabled="!simulationRunSmvAvailable"')
    const guardAt = board.indexOf('const simulationRunSmvAvailable')
    expect(guardAt, 'the simulation availability computed should exist').toBeGreaterThan(-1)
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

  it('leaves no unreachable client for the trace-keyed endpoint', () => {
    // The endpoint still exists server-side, and removing it is an API-contract decision. What must
    // not exist is a client method nothing calls — dead code that reads as a supported path.
    expect(boardApi, 'the unused trace-keyed client is gone')
      .not.toMatch(/downloadTraceSmvModel\s*:/)
    expect(boardApi, 'the run-keyed client remains').toMatch(/downloadRunSmvModel\s*:/)
    expect(board, 'and nothing calls the removed client')
      .not.toContain('downloadTraceSmvModel')
  })
})
