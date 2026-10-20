// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import postcss, { type Rule } from 'postcss'

/**
 * Reduced motion stops decoration. It must not stop the one animation that carries information.
 *
 * `.animate-spin` sat in the same `animation: none !important` list as the ping halos and shimmer, so a user
 * with `prefers-reduced-motion: reduce` saw every spinner frozen mid-rotation. A stopped spinner does not read
 * as "motion is disabled" — it reads as **stalled**, and NuSMV runs take real seconds, so the one thing a user
 * needs during a verification is confidence that it is still alive. Freezing that signal turns a healthy run
 * into an apparent hang, which is the repo's "never present an unknown as a settled outcome" rule pointing the
 * other way.
 *
 * Measured in a browser during a live verification: `1s linear` normally, `2.4s steps(8)` under reduce — still
 * infinite. A slow stepped rotation of a 16px glyph is not the fast, large-area, parallax motion the
 * preference exists to suppress.
 *
 * The probe that confirmed this was wrong twice first, and both failures produced a *zero* that looked like
 * success: an idle board animates nothing, and a fixed 1.5s wait let a small model finish before the
 * measurement. It now waits for a spinner to exist and says so when the run outpaces it.
 */

const boardCss = () => readFileSync(join(__dirname, '../board.css'), 'utf8')

describe('reduced motion', () => {
  it('keeps authentication progress moving while stopping decorative motion', () => {
    const view = readFileSync(join(__dirname, '../../views/Landing.vue'), 'utf8')
    const css = postcss.parse(view.slice(view.indexOf('<style scoped>') + 14, view.indexOf('</style>')))
    const rules: Rule[] = []
    css.walkAtRules('media', media => {
      if (media.params.includes('prefers-reduced-motion: reduce')) media.walkRules(rule => { rules.push(rule) })
    })
    const host = document.createElement('div')
    host.className = 'landing-wrapper'
    const spinner = document.createElement('span')
    spinner.className = 'auth-submit__spinner'
    const decoration = document.createElement('div')
    decoration.className = 'showcase-node'
    host.append(spinner, decoration)
    const applies = (rule: Rule, element: Element) => rule.selectors.some(selector => element.matches(selector))
    const stops = rules.filter(rule => rule.nodes.some(node =>
      node.type === 'decl' && /^(animation|animation-name)$/.test(node.prop) && node.value === 'none'))
    expect(stops.filter(rule => applies(rule, spinner))).toEqual([])
    expect(stops.some(rule => applies(rule, decoration))).toBe(true)
    const duration = rules.filter(rule => applies(rule, spinner)).flatMap(rule => rule.nodes)
      .find(node => node.type === 'decl' && node.prop === 'animation-duration')
    expect(duration?.type).toBe('decl')
    if (duration?.type === 'decl') expect(Number.parseFloat(duration.value)).toBeGreaterThanOrEqual(2)
    const base = css.nodes.find(node => node.type === 'rule' && node.selector === '.auth-submit__spinner')
    expect(base?.toString()).toMatch(/animation:[^;]+infinite/)
  })

  /** The `@media (prefers-reduced-motion: reduce)` block that owns the animation overrides. */
  const motionBlock = () => {
    const css = boardCss()
    // The block containing the animate-* list, not merely the first reduced-motion block in the file.
    const marker = css.indexOf('.iot-board .animate-ping')
    expect(marker, 'the reduced-motion animation list should exist').toBeGreaterThan(-1)
    const start = css.lastIndexOf('@media (prefers-reduced-motion: reduce)', marker)
    expect(start, 'the animate-* list should sit inside a reduced-motion block').toBeGreaterThan(-1)
    /*
     * The block's own extent, by balancing braces — not "everything up to the next `@media`".
     *
     * That earlier heuristic ran **17,904 characters past** the block it meant to read (20,026 against a real
     * 2,122), so every assertion below was free to match a rule outside reduced-motion entirely. It passed only
     * because one `animation-duration` happened to fall inside the window; a rule deleted from the block and a
     * rule added anywhere in those 17.9k characters would look identical to it.
     */
    let depth = 0
    let cursor = css.indexOf('{', start)
    while (cursor < css.length) {
      if (css[cursor] === '{') depth += 1
      else if (css[cursor] === '}') {
        depth -= 1
        if (depth === 0) break
      }
      cursor += 1
    }
    expect(depth, 'the reduced-motion block should be closed').toBe(0)
    return css.slice(start, cursor + 1)
  }

  it('keeps a progress spinner turning, because a frozen one reports a stall that is not happening', () => {
    const block = motionBlock()

    // The spinner must not be in the kill list.
    const killList = block.slice(0, block.indexOf('animation: none'))
    expect(killList, '.animate-spin must not be silenced with the decorative animations')
      .not.toMatch(/\.animate-spin\b/)

    // And it must be given an explicit slowed duration, not merely left alone: leaving it untouched would keep
    // the full-speed 1s rotation, which is the thing the preference is asking to soften.
    const spinRule = block.slice(block.indexOf('.iot-board .animate-spin'))
    expect(spinRule, 'the spinner should be slowed rather than stopped').toMatch(/animation-duration:\s*[\d.]+s/)
    const duration = /animation-duration:\s*([\d.]+)s/.exec(spinRule)
    expect(Number(duration?.[1]), 'a slowed spinner should take longer than the default 1s')
      .toBeGreaterThanOrEqual(2)
  })

  it('bounds the focus cue, because a pointer that never stops reads as a property of the thing', () => {
    /*
     * `.node-focused::after` and `.edge-line--focused` answer "where is that device/rule?" after the canvas
     * pans. Both ran `infinite`, and the highlight that switched them on cleared on only five of its ten
     * exits — so one device pulsed indefinitely while its neighbours sat still, and the halo read as
     * something *about that device* rather than as the board pointing at it. Reported exactly that way:
     * "why do some device instances glow and others don't?"
     *
     * The lifetime fix lives in `board/focusHighlight.ts` (the cue expires on a timer). This is the other
     * half: the motion must be finite even while the cue is up, and must end before the cue does. Without
     * this assertion, reverting either `2` to `infinite` is invisible — measured: no test in the suite
     * noticed.
     */
    const css = boardCss()
    for (const selector of ['.iot-board .device-node.node-focused::after', '.edge-line--focused']) {
      const at = css.indexOf(selector)
      expect(at, `${selector} should exist`).toBeGreaterThan(-1)
      const rule = css.slice(at, css.indexOf('}', at))
      const animation = /animation:\s*([^;]+)/.exec(rule)?.[1]
      expect(animation, `${selector} should animate`).toBeTruthy()
      expect(animation, `${selector} is a cue, not a status — it must not run forever`)
        .not.toContain('infinite')
      // An explicit iteration count, so "finite" is stated rather than inferred from the absence of a word.
      expect(animation, `${selector} should declare an iteration count`).toMatch(/\s\d+$/)
    }
  })

  it('keeps the focus cue visually distinct from the playback-changed mark', () => {
    /*
     * These mean unrelated things — "here is the device you asked for" versus "this device's state changed at
     * this step of the counterexample" — and they co-occur, because focusing a device during playback applies
     * both. They were nonetheless the same mark: a 4px accent ring plus a 28px accent bloom against 30% and
     * 28px, same hue, both animating a scaling accent ring. §5's "state never depends on colour alone" is
     * doubly violated when the shape does not differ either.
     *
     * The distinction is form: the cue is a *dashed* outline with no bloom (matching `.edge-line--focused`,
     * the other cue), and a bloom stays exclusive to playback semantics. Asserting the two properties that
     * carry that difference, rather than exact values, so retuning either mark stays free.
     */
    const css = boardCss()
    const rule = (selector: string) => {
      const at = css.indexOf(selector + ' {')
      expect(at, `${selector} should exist`).toBeGreaterThan(-1)
      return css.slice(at, css.indexOf('}', at))
    }

    // A bloom (a blur-radius shadow) belongs to the playback mark and not to the cue.
    const bloom = /0 0 \d\d+px/
    expect(rule('.iot-board .device-node.trace-changed'), 'the changed mark should keep its bloom')
      .toMatch(bloom)
    expect(rule('.iot-board .device-node.node-focused'), 'the cue must not reuse the changed mark\'s bloom')
      .not.toMatch(bloom)

    // And the cue's ring is dashed where the playback ring is solid.
    expect(rule('.iot-board .device-node.node-focused::after')).toMatch(/border:[^;]*dashed/)
    expect(rule('.iot-board .device-node.trace-change-pulse::after')).toMatch(/border:[^;]*solid/)
  })

  it('still stops the animations that carry no information', () => {
    const block = motionBlock()
    // These exist to draw the eye and nothing else, so reduced motion removes them outright.
    for (const decorative of ['.animate-ping', '.animate-pulse', '.fade-in']) {
      expect(block, `${decorative} should be stopped under reduced motion`).toContain(decorative)
    }
    expect(block, 'the decorative list should be silenced').toMatch(/animation:\s*none\s*!important/)
  })
})
