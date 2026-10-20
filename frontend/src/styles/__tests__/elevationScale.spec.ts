import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { readThemeTokens } from './themeTokens'

/**
 * Depth comes from the elevation scale, never from a literal offset/blur/alpha.
 *
 * `shadowRoleOwnership.spec.ts` owns the *hue* rule and deliberately exempts neutral shade as "elevation, not
 * meaning". That left elevation itself unchecked, and it drifted exactly the way the radius scale had:
 * `--shadow-elevated` was the only token, so anything wanting a different depth wrote its own value. Eight
 * distinct neutral elevations, no two agreeing, each plausible in isolation.
 *
 * Two defects came out of that, both visible on screen rather than theoretical:
 *
 * - **A hand-written shadow does not follow the theme.** Every literal carried a light-theme wash
 *   (`rgba(15, 23, 42, …)`) in a property whose token the dark theme overrides, so on a near-black ground the
 *   shadow was doing nothing while its neighbours' tokens deepened. Reviewed as "the shadow is still those
 *   same few colours".
 * - **The wrong step misdescribes the thing.** The dock's hover tooltip used `--shadow-elevated`, an 18px/42px
 *   panel lift, on a two-line hover chip — so hovering a dock button dropped a panel-sized shadow across the
 *   canvas. Depth says what kind of thing something is; a chip claiming a panel's depth is a lie about it.
 *
 * Three steps, matching the three depths the product actually has: `--shadow-raised` (a control lifting off
 * its own surface), `--shadow-floating` (a transient chip or node above content), `--shadow-elevated` (a panel
 * above the page).
 *
 * `inset` shadows are exempt: an inset ring is an edge or a pressed-state marker, not elevation. `none` and
 * `0 0 0 …` spread-only rings are exempt for the same reason — a focus ring or hairline is a boundary, and
 * `focusIndicator.spec.ts` already measures those against WCAG 1.4.11.
 */

const SRC = join(__dirname, '../..')
const STEPS = ['--shadow-raised', '--shadow-floating', '--shadow-elevated']

// This guard covers shared board stylesheets; component-scoped elevations need separate review.
const FILES = ['styles/base.css', 'styles/board.css']

const sources = () => FILES.map(name => ({ name, text: readFileSync(join(SRC, name), 'utf8') }))

/** Comments quote the defects they explain, so counting them inflates every measurement. */
const withoutComments = (text: string) =>
  text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/<!--[\s\S]*?-->/g, '')

describe('elevation scale', () => {
  it('keeps every palette depth defined and deepens shadows for dark grounds', () => {
    for (const palette of ['copper', 'monochrome'] as const) {
      const light = readThemeTokens('light', palette)
      const dark = readThemeTokens('dark', palette)
      const alpha = (value: string) => Number(/rgba\([^)]*?,\s*([\d.]+)\)/.exec(value)?.[1])
      for (const step of STEPS) {
        expect(alpha(light[step]), palette + ' light ' + step).toBeGreaterThan(0)
        expect(alpha(dark[step]), palette + ' dark ' + step).toBeGreaterThan(alpha(light[step]))
      }
    }
  })

  it('has no elevation token outside the scale', () => {
    /*
     * Two more existed and both had the same dark-theme bug, which is what a fourth owner buys you:
     * `--iot-node-shadow` and `--iot-color-card-shadow` were declared as `rgba(15, 23, 42, 0.9)` in dark
     * theme — the *light* palette's navy at 90% opacity, where every dark shadow is `rgba(2, 6, 23, …)`. The
     * node one was worse than a wrong colour: the node's resting rule used the scale while its four state
     * rules (focus, focused, trace-active, trace-changed) used that token, so highlighting a node silently
     * changed its base depth as well as adding a ring.
     *
     * `--iot-color-resize-shadow` is exempt and stays: it is a *colour* for a 1px handle ring, not an
     * offset/blur elevation, and the ring is a boundary.
     */
    const base = readFileSync(join(SRC, 'styles/base.css'), 'utf8')
    const tokens = new Set<string>()
    for (const match of base.matchAll(/^\s*(--[a-z0-9-]*shadow[a-z0-9-]*):\s*[^;]*\d+(?:px|rem)\s+[\d.]+(?:px|rem)/gmi)) {
      tokens.add(match[1])
    }
    expect([...tokens].sort()).toEqual([...STEPS].sort())
  })

  it('never hand-writes a neutral elevation', () => {
    const offenders: string[] = []
    for (const { name, text } of sources()) {
      withoutComments(text).split(/\r?\n/).forEach((line, index) => {
        const match = /box-shadow:\s*([^;}]+)/.exec(line)
        if (!match) return
        for (const layer of match[1].split(/,(?![^(]*\))/)) {
          const value = layer.trim()
          if (!value || value === 'none' || value.includes('inset')) continue
          if (value.startsWith('var(')) continue
          // A spread-only ring (`0 0 0 <n>`) is a boundary, not a lift.
          if (/^0\s+0\s+0\s/.test(value)) continue
          // Only neutral shade is this spec's business; hue belongs to shadowRoleOwnership.
          if (!/rgba\(\s*(15,\s*23,\s*42|2,\s*6,\s*23|0,\s*0,\s*0)/.test(value)) continue
          offenders.push(`${name}:${index + 1}  ${value}`)
        }
      })
    }
    expect(offenders, `use a --shadow-* step:\n${offenders.join('\n')}`).toEqual([])
  })

  it('gives the dock hint a chip depth, not a panel depth', () => {
    // The original defect: an 18px/42px panel lift on a hover label, which made hovering a dock button
    // drop a panel-sized shadow across the canvas. The dock's hint is now the shared `HintTooltip`
    // popper rather than a hand-rolled span, so the depth constraint has to hold where that popper is
    // styled. Asserting it here keeps the rule that outlived the element it was written for.
    const popperCss = readFileSync(join(SRC, 'styles/base.css'), 'utf8')
    const at = popperCss.indexOf('.el-popper.iot-info-tooltip-popper:is(')
    expect(at, 'the shared tooltip popper rule should exist').toBeGreaterThan(-1)
    const body = popperCss.slice(at, at + popperCss.slice(at).indexOf('}'))
    expect(body).toMatch(/box-shadow:\s*var\(--shadow-floating\)/)
    expect(body).not.toMatch(/box-shadow:\s*var\(--shadow-elevated\)/)
  })

  it('keeps help above its owning panel and nested modal but below confirmations', () => {
    const tokens = readThemeTokens('light')
    for (const owner of ['--z-board-panel-above-alert', '--z-modal-nested', '--z-session-modal']) {
      expect(Number(tokens['--z-tooltip'])).toBeGreaterThan(Number(tokens[owner]))
    }
    expect(Number(tokens['--z-tooltip'])).toBeLessThan(Number(tokens['--z-message-box']))
    const css = withoutComments(readFileSync(join(SRC, 'styles/base.css'), 'utf8'))
    const at = css.indexOf('.el-popper.iot-info-tooltip-popper:is(')
    expect(at).toBeGreaterThan(-1)
    const body = css.slice(at, css.indexOf('}', at))
    // Element Plus assigns an inline z-index; normal stylesheet specificity cannot override it.
    expect(body).toMatch(/z-index:\s*var\(--z-tooltip\)\s*!important/)
  })

  it('keeps portaled template details above navigation and sidebars but below modals', () => {
    const tokens = readThemeTokens('light')
    const component = readFileSync(join(SRC, 'components/ControlCenter.vue'), 'utf8')
    const preview = /\.template-preview\s*\{([^}]+)\}/.exec(withoutComments(component))?.[1] ?? ''
    const layer = /z-index:\s*([^;]+)/.exec(preview)?.[1]?.trim() ?? ''
    const token = /^var\((--[a-z-]+)\)$/.exec(layer)?.[1]
    const elevation = Number(token ? tokens[token] : layer)
    expect(elevation).toBeGreaterThan(Number(tokens['--z-board-nav']))
    expect(elevation).toBeLessThan(Number(tokens['--z-modal']))
  })

  it('routes every dock button hint through the shared popper, not a clipped span', () => {
    // Eight of the nine dock buttons showed no hint at all: their hand-rolled `<span>` sat inside the
    // dock's own clipping context and was positioned outside it, so it was clipped away. Run History
    // looked like the exception only because it was already wrapped in `HintTooltip`, which teleports
    // to <body>. Locking the mechanism in place is what stops one button drifting back.
    const board = readFileSync(join(SRC, 'views/Board.vue'), 'utf8')
    const dockAt = board.indexOf('board-floating-actions board-action-dock')
    expect(dockAt, 'the action dock should be found').toBeGreaterThan(-1)
    const dock = board.slice(dockAt, board.indexOf('<TraceHistoryPanel', dockAt))

    expect(dock, 'the clipped span must not come back').not.toContain('board-tool-tooltip')

    const buttons = dock.match(/class="board-tool-button[^"]*"/g) ?? []
    expect(buttons.length, 'the dock buttons should be found').toBeGreaterThanOrEqual(8)
    // Functional tools keep hints; layout switches retain only their accessible names.
    const wrappers = dock.match(/<HintTooltip/g) ?? []
    expect(wrappers.length).toBeGreaterThanOrEqual(buttons.length)
  })

  it('keeps every dock button inside the toolbar elevation', () => {
    // `open-verification-panel` carried Tailwind's `shadow-lg` while its seven siblings declared
    // `box-shadow: none` — one of eight floating above a strip they all belong to, inside a panel that is
    // itself elevated. It read as a depth difference where the product means an emphasis difference.
    const board = readFileSync(join(SRC, 'views/Board.vue'), 'utf8')
    const tags = board.match(/class="board-tool-button[^"]*"/g) ?? []
    expect(tags.length, 'the dock buttons should be found').toBeGreaterThanOrEqual(8)
    for (const tag of tags) {
      expect(tag, `a dock button must not carry its own elevation: ${tag}`).not.toMatch(/\bshadow-(sm|md|lg|xl|2xl)\b/)
    }
  })

})
