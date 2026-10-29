// @vitest-environment node
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Every control in the board nav is a 44px target, at every width.
 *
 * Measured at 1440×900 before the fix: **seven of ten** nav controls were under 44px, and they disagreed with each
 * other — 37px, 39px, 39px, 39px and 40px beside a 44px theme toggle and a 44px language switch. The heights came
 * from padding plus line-height rather than a floor, so each label length produced a slightly different button.
 *
 * This is not a generic accessibility sweep. The strip holds **undo and redo** — the affordance that makes
 * deleting a device recoverable — and the scene commands that clear or replace an entire board, and the account
 * button that leads to session and account management. A user reaching for undo has just done something they want to
 * take back, which is the worst moment to hand them a target smaller than the platform minimum.
 *
 * It had already been half-fixed: a `max-width: 1023.98px` query raised these to `2.75rem`, so **mobile was
 * correct and the desktop case was the exception**. That is exactly the shape a regression takes when the rule
 * lives in a media query instead of in the base declaration, which is why this asserts the base.
 */

const boardCss = () => readFileSync(join(__dirname, '../board.css'), 'utf8')

/** The declaration block of a selector, up to its closing brace. */
const ruleBody = (selector: string): string => {
  const css = boardCss().replace(/\/\*[\s\S]*?\*\//g, '')
  const requested = selector.split(',').map(value => value.trim())
  const rule = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].find(match => {
    const declared = match[1].split(',').map(value => value.trim())
    return requested.every(value => declared.includes(value))
  })
  expect(rule, `${selector} should exist`).toBeDefined()
  return rule![2]
}

const MINIMUM = 'var(--iot-target-min)'
const targetValue = () => {
  const base = readFileSync(join(__dirname, '../base.css'), 'utf8')
  const value = /--iot-target-min:\s*([\d.]+)(rem|px);/.exec(base)
  expect(value, 'the shared target must have a measurable size').not.toBeNull()
  const px = Number(value![1]) * (value![2] === 'rem' ? 16 : 1)
  expect(px).toBeGreaterThanOrEqual(44)
  return `${value![1]}${value![2]}`
}

describe('board nav target size', () => {
  it('resolves the shared target to at least 44px', () => {
    targetValue()
  })
  it('sizes undo and redo at the minimum in the base rule, not only on narrow screens', () => {
    const rule = ruleBody('.board-nav-bar .board-edit-history-btn')
    expect(rule).toContain(`width: ${MINIMUM}`)
    expect(rule).toContain(`height: ${MINIMUM}`)
    // 40px was the previous value and is the specific regression this guards.
    expect(rule).not.toMatch(/(?:width|height):\s*40px/)
  })

  it('sizes the account control at the minimum in its shared base rule', () => {
    const rule = ruleBody('.board-nav-bar .nav-account-btn')
    expect(rule).toContain(`width: ${MINIMUM}`)
    expect(rule).toContain(`height: ${MINIMUM}`)
    expect(rule).not.toMatch(/(?:width|height):\s*40px/)
  })

  it('gives the label-bearing nav actions a height floor', () => {
    // These are sized by their labels, so they need a `min-height` rather than a fixed height — that is what
    // produced 37/39/39/39px from the same rule.
    const rule = ruleBody('.board-nav-bar .scene-action-btn,\n.board-nav-bar .ai-assistant-btn')
    expect(rule).toContain(`min-height: ${MINIMUM}`)
  })

  it('keeps the narrow-viewport rules no smaller than the base', () => {
    // The inversion this audit found twice: a media query for a *smaller* screen granting a *larger* size, which
    // leaves the wide case as the unnoticed exception. Any narrow override must now match the base minimum.
    const css = boardCss().split(MINIMUM).join(targetValue())
    const narrowBlocks = css.match(/@media \(max-width: 1023\.98px\)[^{]*\{[\s\S]*?\n\}/g) || []
    /*
     * Both units, and the scan has to find something.
     *
     * The first version matched `(\d+)px` only. Measured: the six narrow blocks contain **0 px sizes and 7 rem
     * sizes**, so the filter excluded every value present and the check asserted nothing — an empty scan wearing
     * the shape of coverage. `targetSizeFloor.spec.ts:90` in this same repo uses `([\d.]+)(rem|px)` and finds all
     * seven, which is what showed the omission.
     */
    const offenders: string[] = []
    let inspected = 0
    for (const block of narrowBlocks) {
      for (const match of block.matchAll(/(\.board-nav-bar [^{]+)\{([^}]*)\}/g)) {
        const [, selector, body] = match
        for (const size of body.matchAll(/(?:min-)?(?:width|height):\s*([\d.]+)(rem|px)/g)) {
          inspected += 1
          const px = size[2] === 'rem' ? Number(size[1]) * 16 : Number(size[1])
          if (px < 44) offenders.push(`${selector.trim()} -> ${size[0]}`)
        }
      }
    }
    expect(inspected, 'the size scan should not come back empty').toBeGreaterThan(0)
    expect(offenders).toEqual([])
  })
})
