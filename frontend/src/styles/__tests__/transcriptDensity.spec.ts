import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * A sequential log is one transcript, not one card per line.
 *
 * The verification result dialog is where this product delivers its whole value, and the audit has repeatedly
 * added honesty to it — a frozen-submission snapshot, model-scope caveats, specification results, an engine log,
 * a violation block. Each addition was right on its own. Together they had produced a screen measured at
 * **15 bordered boxes competing as units**, and **7 of those were individual lines of the engine log**, each
 * wearing its own `board-card` border inside an already-bordered section.
 *
 * That is what semantic transparency turning into visual clutter looks like: nothing was inaccurate, but one
 * ordered list was asking the eye to treat seven cumulative lines as seven independent facts. Removing the
 * per-line frames took the dialog to 8 bordered boxes — one per genuine section — while keeping every line
 * present, monospace and scrollable.
 *
 * Measured after the change: 7 lines, 12px, monospace, scrollable, `borderPerLine: false`, and still exactly
 * **one** element at the largest type size, so the verdict keeps its focal point.
 *
 * Pinned because the regression is the natural edit. Reaching for `board-card` on a list item looks like
 * consistency with the surrounding panels, and nothing else would fail.
 */

const boardVue = () => readFileSync(join(__dirname, '../../views/Board.vue'), 'utf8')

describe('transcript density', () => {
  /**
   * The engine-log block, located by its own scroll region rather than by line number.
   *
   * Comments are stripped first. The explanation written above the fixed markup names the old class, and without
   * this the rule failed on its own documentation — the third time in this audit a text-matching check has
   * punished documenting the fix rather than catching the defect.
   */
  const checkLogBlock = () => {
    const source = boardVue().replace(/<!--[\s\S]*?-->/g, '')
    const anchor = source.indexOf("t('app.executionLogs')")
    expect(anchor, 'the check-log block should exist in Board.vue').toBeGreaterThan(-1)
    // From the heading to the end of the list: enough to cover the rendered rows.
    const end = source.indexOf('</ol>', anchor)
    expect(end, 'the check-log list should be an ordered list').toBeGreaterThan(anchor)
    return source.slice(anchor, end)
  }

  it('renders engine log lines without a per-line card or border', () => {
    const block = checkLogBlock()
    // `board-card` carries a surface, a border and a shadow — three framing signals on a single log line.
    expect(block, 'a log line must not be a card').not.toMatch(/board-card/)
    expect(block, 'a log line must not carry its own border utility').not.toMatch(/\bborder\b(?!-)/)
  })

  it('keeps the log an ordered, monospace, scrollable list', () => {
    const block = checkLogBlock()
    // Ordered because the lines *are* a sequence: the model is generated, then run, then a verdict lands.
    // A reader who cannot tell the order has lost the only thing the log offers over the summary above it.
    expect(block, 'the log should be an ordered list').toMatch(/<ol[^>]*>/)
    expect(block, 'engine output is monospace so columns and identifiers line up').toMatch(/font-mono/)
    // The block is capped and scrolls: an unbounded log would push the violation section off screen.
    expect(block, 'the log owns a scroll region rather than growing without limit')
      .toMatch(/iot-scroll-region/)
    expect(block, 'the log stays capped in height').toMatch(/max-h-\d+/)
  })

  it('leaves the section itself bordered, so the group is still scoped', () => {
    // The fix is about *per-line* framing, not about removing structure. The enclosing section keeps its border,
    // which is what tells a reader these lines belong together — one frame doing the job seven were doing.
    const source = boardVue()
    const anchor = source.indexOf("t('app.executionLogs')")
    const sectionStart = source.lastIndexOf('<details', anchor)
    const section = source.slice(sectionStart, anchor)
    expect(section, 'the check-log section keeps a single enclosing border').toMatch(/border/)
  })
})
