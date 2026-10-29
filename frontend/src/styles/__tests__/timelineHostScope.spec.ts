// @vitest-environment node
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * A rule for a replay bar must not be written as a descendant of `.iot-board`.
 *
 * Both timeline hosts are `position: fixed` **siblings** of `.iot-board` — `Board.vue` closes the board
 * root before the trace host, and `SimulationTimeline.vue` is a separate component — so every
 * `.iot-board .board-timeline…` selector matches nothing. Nothing fails loudly: the declarations parse,
 * the file looks maintained, and the surface renders with whatever the unprefixed rules happen to give it.
 *
 * This is the third form of one structural mistake, and the first two are documented where they bit:
 * `--board-floating-gap` was unreadable inside a fixed host (the comment near line 1456 of `board.css`),
 * and `.iot-board button` missed 10 of 12 enabled replay controls (`buttonCursor.spec.ts`). Found this
 * time by grepping the prefix rather than by a report, because the symptoms are individually plausible:
 *
 * - `.iot-board .board-timeline [data-testid$="-timeline-close"]` declared the 44px touch floor for both
 *   close buttons inside the narrow/short media query. Dead, so on the viewport where a 44px target
 *   matters most they stayed at the ~32px their padding produced — and `targetSizeFloor.spec.ts` does not
 *   cover this surface, so nothing said otherwise.
 * - Nine colour rules restated the neutral-to-token mapping that the unprefixed `.board-timeline` block
 *   already performs, with **different** values: `--board-card-bg` vs `--surface-muted` for backgrounds,
 *   `--board-panel-bg` vs a `color-mix` overlay for the panel itself. Had the prefix ever matched, the
 *   higher-specificity dead copy would have won and contradicted the measured overlay treatment.
 *
 * So the guard is on the selector shape, not on any one declaration: the mistake is writing a replay-bar
 * rule as a board descendant at all.
 */

const STYLE_DIR = join(__dirname, '..')
const boardCss = () => readFileSync(join(STYLE_DIR, 'board.css'), 'utf8')

describe('replay-bar rules are not scoped to the board', () => {
  it('renders the trace replay bar outside .iot-board, which is what makes the prefix dead', () => {
    // If this ever nests, the guard below is protecting a non-problem and should be revisited rather
    // than trusted — the same reasoning `buttonCursor.spec.ts` states for its own host check.
    const board = readFileSync(join(STYLE_DIR, '../views/Board.vue'), 'utf8')
    const rootAt = board.indexOf("'iot-board',")
    expect(rootAt, 'the board root applies the iot-board class').toBeGreaterThan(-1)

    // The root div is indented two spaces in the template, so its closing tag at that indent ends it.
    const rootClose = board.indexOf('\n  </div>', rootAt)
    const traceHost = board.indexOf('<PlaybackTerminal\n')
    expect(traceHost, 'the trace replay bar has a host').toBeGreaterThan(-1)
    expect(traceHost, 'and it is a sibling of the board root, not a descendant').toBeGreaterThan(rootClose)
  })

  it('has no .iot-board-prefixed rule targeting a replay bar', () => {
    const offenders = [...boardCss().matchAll(/\.iot-board\s+\.board-timeline[^,{]*/g)].map(m => m[0].trim())
    expect(offenders, 'a replay-bar rule scoped to the board matches nothing').toEqual([])
  })

  it('pairs an unscoped arm onto every board-scoped rule for a class a replay bar uses', () => {
    /*
     * The rule above catches `.iot-board .board-timeline…`, the shape used for the bar itself. The other way
     * in is a *shared* helper class: the replay bars use `board-chip-danger`, `board-surface-warning` and
     * four siblings, and each of those is declared as a two-arm selector list — the scoped arm for
     * specificity inside the board, the bare arm so surfaces outside it (these two hosts, dialogs) get the
     * same treatment. Drop the bare arm and the class silently stops applying on the replay bars only.
     *
     * Checked by parsing the selector lists rather than by naming the current values, so it keeps holding as
     * the palette changes. Swept at the time of writing: all six paired, so this is a guard rather than a fix.
     */
    const css = boardCss()
    const sim = readFileSync(join(STYLE_DIR, '../components/SimulationTimeline.vue'), 'utf8')
    const board = readFileSync(join(STYLE_DIR, '../views/Board.vue'), 'utf8')
    const traceBar = board.slice(board.indexOf('<PlaybackTerminal\n'))

    const shared = [...new Set(
      [...sim.matchAll(/board-(?:chip|surface|text|border)-[a-z0-9-]+/g),
       ...traceBar.matchAll(/board-(?:chip|surface|text|border)-[a-z0-9-]+/g)].map(m => m[0])
    )]
    expect(shared.length, 'the replay bars use shared role classes').toBeGreaterThan(0)

    const unpaired: string[] = []
    for (const name of shared) {
      const scoped = new RegExp(`\\.iot-board\\s+\\.${name}(?![a-z0-9-])`)
      const bare = new RegExp(`(^|,)\\s*\\.${name}(?![a-z0-9-])`, 'm')
      for (const rule of css.matchAll(/([^{}]+)\{/g)) {
        if (scoped.test(rule[1]) && !bare.test(rule[1])) unpaired.push(`${name}: ${rule[1].trim()}`)
      }
    }
    expect(unpaired, 'a board-scoped role class needs a bare arm, or it skips both replay bars').toEqual([])
  })

  it('gives terminal icon actions the shared target size at every viewport', () => {
    const css = boardCss()
    expect(css).toMatch(/\.board-timeline \.playback-terminal__icon\s*\{[^}]*width: var\(--iot-target-min\)/)
    expect(css).toMatch(/\.board-timeline button:where\([^}]+min-height: var\(--iot-target-min\)/)
  })

})
