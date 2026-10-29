// @vitest-environment node
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { readThemeTokens } from './themeTokens'

/**
 * Interactive controls are 44px targets, declared as a floor rather than left to padding.
 *
 * This defect has now been found in three separate places, each time by measuring rather than reading:
 *
 * | surface | was | controls |
 * | :--- | :--- | ---: |
 * | board nav strip | 37-40px, five different sizes | 7 of 10 |
 * | System Inspector | 24-36px | 9 |
 * | action dock | 40px, uniformly | 8 of 8 |
 *
 * The pattern in every case: the height came from `padding` plus a glyph's line-height, so it was whatever the
 * content happened to produce. A control sized by its contents has no target size — it has a coincidence. The dock
 * is the clearest example, because all eight buttons agreed on 40px and nothing in the code said 40.
 *
 * These are the surfaces a user crosses before every task — undo, the workflow entry points, the panel that lists
 * their devices — so a near-miss here costs more than the 4px suggests. `--iot-target-min` names the value once so a
 * call site states the constraint instead of restating a number.
 */

const STYLE_DIR = join(__dirname, '..')

const css = (file: string) => readFileSync(join(STYLE_DIR, file), 'utf8')

/** The declaration block of a selector, up to its closing brace. */
const ruleBody = (file: string, selector: string): string => {
  const source = css(file).replace(/\/\*[\s\S]*?\*\//g, '')
  const requested = selector.split(',').map(value => value.trim())
  const rule = [...source.matchAll(/([^{}]+)\{([^{}]*)\}/g)].find(match => {
    const declared = match[1].split(',').map(value => value.trim())
    return requested.every(value => declared.includes(value))
  })
  expect(rule, `${selector} should exist in ${file}`).toBeDefined()
  return rule![2]
}

/** 2.75rem at a 16px root. */
const MINIMUM_REM = 2.75

/** Every `min-height`/`height`/`min-width`/`width` in a block, as pixels. */
const declaredSizes = (block: string) => {
  const target = /--iot-target-min:\s*([\d.]+(?:rem|px));/.exec(css('base.css'))
  expect(target, 'the shared target must have a measurable size').not.toBeNull()
  const resolved = block.split('var(--iot-target-min)').join(target![1])
  const sizes: Array<{ raw: string, px: number }> = []
  for (const match of resolved.matchAll(/(?:min-)?(?:width|height):\s*([\d.]+)(rem|px)\b/g)) {
    sizes.push({ raw: match[0], px: match[2] === 'rem' ? Number(match[1]) * 16 : Number(match[1]) })
  }
  return sizes
}

describe('interactive target size floor', () => {
  it('sizes every action-dock button at the minimum', () => {
    // All eight measured 124x40 — the entry points to simulation, verification, exploration, run history and the
    // four recommendation panels, i.e. every primary workflow on the board.
    const block = ruleBody('board.css', '.iot-board .board-tool-button')
    expect(block).toMatch(/min-height:\s*var\(--iot-target-min\)/)
    expect(readThemeTokens('light')['--iot-target-min']).toBe(`${MINIMUM_REM}rem`)
    expect(block).not.toMatch(/min-height:\s*2\.5rem/)
  })

  it('keeps canvas map tools on the shared full target with a quiet face', () => {
    const board = readFileSync(join(STYLE_DIR, '../views/Board.vue'), 'utf8')
    const buttons = [...board.matchAll(/<button\b[^>]*class="([^"]*\bcanvas-map__tool\b[^"]*)"/g)]
    expect(buttons).toHaveLength(3)
    for (const button of buttons) expect(button[1].split(/\s+/)).toContain('board-panel-icon-action')
    // The painted face is smaller; the shared button itself still owns the entire hit target.
    const block = ruleBody('board.css', '.iot-board .board-panel-icon-action')
    const sizes = declaredSizes(block)
    expect(sizes).toHaveLength(2)
    for (const size of sizes) expect(size.px).toBeGreaterThanOrEqual(MINIMUM_REM * 16)
    expect(block).not.toMatch(/max-(width|height):/)
  })

  it('sizes the nav strip controls at the minimum in the base rule', () => {
    // Not only inside a narrow-viewport media query. The nav had exactly that inversion: mobile was correct and
    // desktop was 4px short, because the rule lived in `max-width: 1023.98px` instead of the base declaration.
    for (const selector of [
      '.board-nav-bar .board-edit-history-btn',
      '.board-nav-bar .nav-account-btn'
    ]) {
      const block = ruleBody('board.css', selector)
      const sizes = declaredSizes(block)
      expect(sizes.length, `${selector} must declare its target size`).toBeGreaterThan(0)
      for (const size of sizes) {
        expect(size.px, `${selector}: ${size.raw}`).toBeGreaterThanOrEqual(MINIMUM_REM * 16)
      }
    }
  })

  it('never grants a narrow viewport a larger target than the base', () => {
    // A media query for a *smaller* screen handing out a *larger* size means the wide case is the unnoticed
    // exception. Found twice in this audit — here and in the playback overlay caps.
    const source = css('board.css')
    const offenders: string[] = []
    for (const block of source.match(/@media \(max-width: 1023\.98px\)[^{]*\{[\s\S]*?\n\}/g) || []) {
      for (const rule of block.matchAll(/(\.board-nav-bar [^{]+|\.iot-board \.board-tool-button[^{]*)\{([^}]*)\}/g)) {
        for (const size of declaredSizes(rule[2])) {
          if (size.px < MINIMUM_REM * 16) offenders.push(`${rule[1].trim()} -> ${size.raw}`)
        }
      }
    }
    expect(offenders).toEqual([])
  })

  it('gives the help trigger a full target without inflating its badge', () => {
    /*
     * The badge must paint what it occupies, and the target must live outside the box model.
     *
     * This test used to require the opposite: `box-sizing: content-box` with `padding: 0.625rem` and
     * `background-clip: content-box`, on the theory that padding grows the target while the background stays
     * small. `board.css` had already measured that this does not hold - `background-clip` clips the background but
     * NOT the border, so a bordered box paints at the full padding size. Measured on the board: the badge rendered
     * 46x46px beside 16px text while telling the layout it was 24px, and the negative margin that cancelled the
     * growth spent the difference on its neighbours.
     *
     * So this assertion was certifying the defect it existed to prevent. The target now comes from a `::before`
     * overlay, which enlarges the hit area without entering the box model or the paint.
     */
    const tooltip = readFileSync(join(__dirname, '../../components/common/InfoTooltip.vue'), 'utf8')
    const at = tooltip.indexOf('.iot-info-tooltip-trigger {')
    expect(at).toBeGreaterThan(-1)
    const block = tooltip.slice(at, at + tooltip.slice(at).indexOf('}'))

    expect(block).toContain('box-sizing: border-box')
    expect(block).not.toMatch(/margin:\s*-/)
    expect(block).not.toContain('background-clip: content-box')

    const overlayAt = tooltip.indexOf('.iot-info-tooltip-trigger::before')
    expect(overlayAt, 'the hit target should be an overlay').toBeGreaterThan(-1)
    const overlay = tooltip.slice(overlayAt, overlayAt + tooltip.slice(overlayAt).indexOf('}'))
    expect(overlay).toMatch(/width:\s*44px/)
    expect(overlay).toMatch(/height:\s*44px/)
    expect(overlay).toContain('position: absolute')
  })

  it('sizes the inspector tabs and add-device control at the minimum', () => {
    const inspector = readFileSync(join(__dirname, '../../components/SystemInspector.vue'), 'utf8')

    // Shared styling must still guarantee a full target for every tab.
    expect(inspector).toMatch(/class="board-section-tab(?:\s[^\"]*)?"/)
    const tab = ruleBody('board.css', '.iot-board .board-section-tab')
    expect(tab).toContain('min-height: var(--iot-target-min)')
    const target = declaredSizes(tab)
    expect(target.length).toBeGreaterThan(0)
    expect(target.every(size => size.px >= MINIMUM_REM * 16)).toBe(true)
    // `inspector-add-device` measured 26x36 and is the primary way to add a device to the board.
    const addAt = inspector.indexOf('data-testid="inspector-add-device"')
    expect(addAt).toBeGreaterThan(-1)
    const addButton = inspector.slice(addAt, addAt + 400)
    expect(addButton).toContain('board-panel-icon-action')
    const action = ruleBody('board.css', '.iot-board .board-panel-icon-action')
    expect(action).toContain('width: var(--iot-target-min)')
    expect(action).toContain('height: var(--iot-target-min)')
  })
})
