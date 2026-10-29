// @vitest-environment node
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

// Structural guards complement the rendered geometry checks in canvas-runtime-environment.spec.ts.

const boardCss = () => readFileSync(join(__dirname, '../board.css'), 'utf8')

describe('canvas node legibility', () => {
  it('never lets the icon column outgrow the text beside it', () => {
    // The icon column's upper bound was `clamp(4.5rem, 42cqmin, 52rem)`. A `clamp()` *lower* bound on a
    // maximum guarantees the decoration its size while the content absorbs every shortfall: 4.5rem (72px)
    // beat the 42cqmin term on any node up to ~171px, so the icon took 72px of a 132px content row and the
    // column holding label, state and chips resolved to 26px.
    const columns = boardCss().match(/\.device-node--expanded \.device-node-content\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*calc\(([\d.]+)rem\s*\/\s*var\(--node-ui-scale\)\)\)/)
    expect(columns, 'the expanded icon column must have a screen-size cap and be shrinkable').not.toBeNull()

    // The icon's guaranteed floor must stay small enough that a default node keeps most of its row for text.
    // 132px content row minus a 2rem (32px) icon leaves 100px; at 4.5rem it left 60px before gaps.
    expect(Number(columns![1])).toBeLessThanOrEqual(2)
  })

  it('shrinks a runtime chip\'s label before its value', () => {
    // Both were equally shrinkable, so a 65px chip holding "Temperature" (58px) and "26" (15px) split the
    // shortfall and printed the value in 6px. The label names the variable; the value is the fact.
    const css = boardCss()
    const ruleBody = (selector: string) => {
      const at = css.indexOf(`${selector} {`)
      expect(at, `${selector} should exist`).toBeGreaterThan(-1)
      return css.slice(at, at + css.slice(at).indexOf('}'))
    }
    const label = ruleBody('.iot-board .device-runtime-chip__label')
    const value = ruleBody('.iot-board .device-runtime-chip__value')

    // The label may shrink; the value may not.
    expect(label).toMatch(/flex:\s*0\s+1\s+auto/)
    expect(value).toMatch(/flex:\s*0\s+0\s+auto/)
    // And the label keeps a floor, so it still identifies itself by prefix rather than vanishing.
    expect(label).toMatch(/min-width:\s*[\d.]+rem/)
  })

  it('lets the runtime chips wrap into the node\'s spare height', () => {
    // The strip was a single non-wrapping row while the node had 29–32px of unused vertical space, so its
    // chips compressed each other instead of using it.
    const css = boardCss()
    const strip = css.slice(css.indexOf('.iot-board .device-runtime-strip {'))
    expect(strip.slice(0, strip.indexOf('}'))).toMatch(/flex-wrap:\s*wrap/)
  })

  it('gives a node provenance pill room for its own label', () => {
    // The pill states whether the shown sources are trusted. It printed the full sentence ("Shown sources
    // trusted") inside a 54px box, so it was ellipsized to a fragment — at any font size. The node now prints
    // the category and keeps the sentence in `title` plus an `sr-only` span, which is how the same node already
    // handles `noStateMachineShort`; that only works while the cap is wide enough for the short word, and the
    // previous `46cqmin` (63px on a 137px node) cut 28% off "Trusted".
    const css = boardCss()
    const at = css.indexOf('.iot-board .device-node-trust {')
    expect(at, '.device-node-trust should exist').toBeGreaterThan(-1)
    const rule = css.slice(at, at + css.slice(at).indexOf('}'))
    expect(rule).not.toMatch(/max-width:\s*[\d.]+cq/)
    expect(rule).toMatch(/max-width:\s*min\(100%,\s*calc\([\d.]+rem\s*\/\s*var\(--node-ui-scale\)\)\)/)
  })
})
