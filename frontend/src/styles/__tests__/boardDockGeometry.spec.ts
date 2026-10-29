// @vitest-environment node
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * The action dock has one shape, and its width has one owner.
 *
 * Three modes (`expanded` / `compact` / `packed`) had visibly different geometry because four places each
 * declared their own version of it: `ACTION_DOCK_RAIL_PX` in `Board.vue`, a px pair in the fit math,
 * `--board-action-rail-width` in `board.css`, and a `.board-action-dock--packed { width }` literal. None
 * of the four agreed — the corridor CSS reserved, the width the dock painted, and the width
 * `getVisibleCanvasFrame` subtracted were three different numbers, so fit-to-content could place a node
 * under the rail. Shape drifted the same way: the compact panel had a pill radius against the other two
 * modes' `--iot-radius-panel`, plus its own padding and gap, restated a third time under short landscape.
 *
 * These are cheap to re-break by editing one site and not the others, which is why they are pinned here
 * rather than in a comment.
 */

const STYLE_DIR = join(__dirname, '..')
const board = () => readFileSync(join(STYLE_DIR, 'board.css'), 'utf8')
const boardVue = () => readFileSync(join(STYLE_DIR, '../views/Board.vue'), 'utf8')
const layoutConstants = () => readFileSync(join(STYLE_DIR, '../constants/boardLayout.ts'), 'utf8')

/**
 * Every declaration block whose selector list *ends* with `selector`.
 *
 * The `${selector} {` search alone also matched the trailing member of a comma-separated group — the dock
 * panel shares one with the launcher — and returned that group's `pointer-events` body as if it were the
 * panel's own, so the first version of this spec failed against correct CSS.
 */
const ruleBodies = (source: string, selector: string): string[] => {
  const bodies: string[] = []
  let from = 0
  for (;;) {
    const at = source.indexOf(`${selector} {`, from)
    if (at === -1) break
    from = at + 1
    const body = source.slice(at, at + source.slice(at).indexOf('}'))
    // A group's last member is preceded by `,\n`; a rule of its own by `}`, a comment, or nothing.
    if (/,\s*$/.test(source.slice(0, at))) continue
    bodies.push(body)
  }
  return bodies
}

describe('board action dock geometry', () => {
  it('keeps the rail width owned by ACTION_DOCK_RAIL_PX alone', () => {
    // The token may declare a pre-hydration default once; nothing may override it behind a breakpoint,
    // because the live value is injected per mode and an override would win in a way the JS cannot see.
    const declarations = board().match(/--board-action-rail-width:/g) ?? []
    expect(declarations).toHaveLength(1)

    /*
     * The dock's own width must read the injected token rather than restate a literal.
     *
     * `--compact` only. The first version also looped over `.iot-board .board-action-dock--packed`, which has
     * **no rule at all** in `board.css` — packed mode is deliberately width-less, so `ruleBodies` returned `[]`,
     * the inner loop never ran, and that half of the check asserted nothing. Empty coverage that reads as
     * coverage is the failure mode this file exists to prevent, so the scan now proves it found a rule.
     */
    const compactRules = ruleBodies(board(), '.iot-board .board-action-dock--compact')
    expect(compactRules.length, 'the compact dock rule should exist to be checked').toBeGreaterThan(0)
    for (const body of compactRules) {
      expect(body, 'the compact dock must not declare its own rail width')
        .not.toMatch(/^\s*width:\s*[\d.]+(rem|px)/m)
    }
  })

  it('derives the reserved fit width from the same per-mode table as the paint', () => {
    // One table in `constants/boardLayout.ts`, read by both the injected CSS width and the fit math.
    expect(layoutConstants()).toMatch(/export const ACTION_DOCK_RAIL_PX = Object\.freeze\(\{/)
    const source = boardVue()
    expect(source).toMatch(/actionDockRailWidth = computed\(\(\) => `\$\{actionDockRailPx\.value\}px`\)/)
    expect(source).toMatch(/actionDockReservedWidth = computed\(\(\) =>\s*\n?\s*actionDockRailPx\.value \+/)
  })

  it('gives the dock one top block, occupying the space it paints', () => {
    const source = board()

    // The collapse handle and the packed launcher are the same position doing the same job in different
    // modes, so they are one size.
    for (const selector of ['.iot-board .board-action-dock__toggle', '.iot-board .board-action-dock__launcher']) {
      const [body] = ruleBodies(source, selector)
      expect(body, selector).toMatch(/width:\s*var\(--board-dock-handle\)/)
      expect(body, selector).toMatch(/height:\s*var\(--board-dock-handle\)/)
      // A control that paints 44px must claim 44px. `content-box` + padding + negative margin painted the
      // handle at 45.97px while reserving 23px, so it overhung the dock and covered the button below it —
      // and `targetSizeFloor` still passed, because it read the padding arithmetic rather than the result.
      expect(body, `${selector} must not give back the space it paints`).not.toMatch(/margin:\s*-/)
      expect(body, `${selector} must not size its target through content-box padding`)
        .not.toMatch(/box-sizing:\s*content-box/)
    }

    // The row must reserve the handle's real height, or the handle overlaps its neighbour.
    const [header] = ruleBodies(source, '.iot-board .board-action-dock__header')
    expect(header).toMatch(/min-height:\s*var\(--board-dock-handle\)/)

    // 44px: the same floor the tool buttons carry, so the strip has one control size.
    expect(source).toMatch(/--board-dock-handle:\s*2\.75rem/)
  })

  it('gives every dock mode the same surface radius, padding and gap', () => {
    const source = board()
    const [panel] = ruleBodies(source, '.iot-board .board-action-dock__panel')
    expect(panel).toMatch(/border-radius:\s*var\(--iot-radius-panel\)/)
    expect(panel).toMatch(/padding:\s*var\(--board-dock-pad\)/)
    expect(panel).toMatch(/gap:\s*var\(--board-dock-gap\)/)

    // No mode override may reintroduce its own surface shape — that is what made one strip read as three
    // unrelated widgets.
    for (const body of ruleBodies(source, '.iot-board .board-action-dock--compact .board-action-dock__panel')) {
      expect(body).not.toMatch(/border-radius|(?:^|\s)padding:|(?:^|\s)gap:/m)
    }
  })

  it('sizes the compact panel so a 44px button fits its content box exactly', () => {
    const source = board()
    const railPx = Number(/export const COLLAPSED_PANEL_RAIL_PX = (\d+)/.exec(layoutConstants())?.[1])
    const padRem = Number(/--board-dock-pad:\s*([\d.]+)rem/.exec(source)?.[1])
    const [panel] = ruleBodies(source, '.iot-board .board-action-dock__panel')
    const borderPx = Number(/border:\s*(\d+)px/.exec(panel)?.[1])

    expect(railPx).toBe(56)
    // 56 - 2x1px border - 2x5px padding = 44, the target-size floor the buttons carry. A larger padding
    // overflows the rail; a smaller one crowds the button against the edge.
    expect(railPx - (2 * borderPx) - (2 * padRem * 16)).toBe(44)
  })

  it('keeps one owner for the collapsed side-rail width', () => {
    // `--board-inspector-width` is injected already carrying the collapsed width, so a second collapsed
    // token drifted to 48px against the 56px the rail renders at — and the floating panels measured
    // their right inset from the stale copy.
    expect(board()).not.toMatch(/--board-(?:inspector|control)-collapsed-width/)

    // The two side panels bind their collapsed width to the constant, not to a matching literal. They each
    // held a `3.5rem` of their own, kept in step only by a comment in the other file.
    for (const file of ['../components/SystemInspector.vue', '../components/ControlCenter.vue']) {
      const text = readFileSync(join(STYLE_DIR, file), 'utf8')
      expect(text, `${file} should bind the shared constant`)
        .toMatch(/isCollapsed \? COLLAPSED_PANEL_RAIL_CSS : panelWidth/)
    }
  })

  it('keeps the floating gap agreeing between the stylesheet and the injected style', () => {
    /*
     * The fixed timeline hosts are siblings of `.iot-board`, not descendants, so they cannot see
     * `--board-floating-gap` scoped to it. `boardShellStyle` injects the value onto them, which means the number
     * has two readers and can drift.
     *
     * It drifted once already, in the other direction: `e91a109` removed the `var(…, 1rem)` fallbacks on the
     * premise that the gap is declared at `:root`. It is not, so `calc()` became invalid, `left`/`right` fell back
     * to `auto`, and the overlay shrink-wrapped at x=0 — flush left with the whole right side empty. On a
     * 101-state trace the shrink-wrap reached 3258px and put the play button off-screen at a laptop viewport.
     */
    const declared = /--board-floating-gap:\s*([^;]+);/.exec(board())
    expect(declared, 'board.css should declare the gap').not.toBeNull()

    const constant = /BOARD_FLOATING_GAP_CSS = '([^']+)'/.exec(layoutConstants())
    expect(constant, 'boardLayout.ts should own the injected value').not.toBeNull()

    expect(constant![1].trim(), 'the two readers must agree').toBe(declared![1].trim())
  })
})
