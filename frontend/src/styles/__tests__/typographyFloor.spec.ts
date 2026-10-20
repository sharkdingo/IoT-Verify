import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * No interface text may be declared below the product's own readable minimum.
 *
 * `--iot-font-min` (0.6875rem = 11px at a 16px root) was introduced when a visual review found panel
 * text too small to read comfortably. The token was added and the offending sites at the time were
 * fixed — but nothing stopped new ones, and twelve remained or appeared afterwards: `0.58rem` (9.3px) on
 * a section label, `0.62rem` on tab labels, and `9px` on a trace variable name and an attack badge.
 *
 * A written rule that nothing checks is a rule that drifts. These files are scanned as text because the
 * declarations live in scoped `<style>` blocks, where no runtime test would see them.
 */

const DIRS = ['components', 'components/common', 'views', 'styles']

const sources = () => {
  const files: Array<{ name: string, text: string }> = []
  for (const dir of DIRS) {
    const full = join(__dirname, '../..', dir)
    for (const entry of readdirSync(full, { withFileTypes: true })) {
      if (!entry.isFile()) continue
      if (!/\.(vue|css)$/.test(entry.name)) continue
      files.push({ name: `${dir}/${entry.name}`, text: readFileSync(join(full, entry.name), 'utf8') })
    }
  }
  return files
}

describe('typography floor', () => {
  /** Strip comments, so an explanation that quotes a bad size is not mistaken for one. */
  const withoutComments = (text: string) =>
    text.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '')

  /**
   * Every `font-size` value in a declaration or a `clamp()` floor, as a number of pixels.
   *
   * Parsing beats pattern-matching here, and I learned that the hard way twice: my first version used the regex
   * `0\.(?:[0-5]\d*|6[0-7]\d*)rem`, which has a **gap between 0.68 and 0.6874** — neither branch matches `0.68rem`,
   * so a `0.68rem` label passed this spec and rendered at 10.88px until a browser measurement found it. Writing
   * the threshold once, as arithmetic, cannot develop that kind of blind spot.
   */
  const declaredSizes = (text: string) => {
    const found: Array<{ px: number, raw: string, index: number }> = []
    const toPx = (value: string, unit: string) => unit === 'rem' ? Number(value) * 16 : Number(value)

    for (const match of text.matchAll(/font-size:\s*([\d.]+)(rem|px)\b/g)) {
      found.push({ px: toPx(match[1], match[2]), raw: match[0], index: match.index! })
    }
    // A `clamp()` floor is the smallest size the text can take, so it is subject to the same rule.
    for (const match of text.matchAll(/font-size:\s*clamp\(\s*([\d.]+)(rem|px)\s*,/g)) {
      const [, value, unit] = match
      found.push({ px: toPx(value, unit), raw: match[0].slice(0, 52), index: match.index! })
    }
    return found
  }

  it('declares no font-size below the readable minimum', () => {
    const FLOOR_PX = 0.6875 * 16

    // The tolerance is for float representation only, not for "close enough". My first version used `0.01`, which
    // let `0.6874rem` (10.9984px) pass — a value genuinely under the floor, and the same near-miss region that the
    // old regex left open. A slack epsilon on a threshold check re-creates the gap it was meant to close.
    const FLOAT_TOLERANCE = 1e-9

    const offenders: string[] = []
    for (const { name, text } of sources()) {
      const scanned = withoutComments(text)
      for (const size of declaredSizes(scanned)) {
        if (size.px < FLOOR_PX - FLOAT_TOLERANCE) {
          const line = scanned.slice(0, size.index).split('\n').length
          offenders.push(`${name}:${line}  ${size.raw}  (${size.px.toFixed(2)}px)`)
        }
      }
    }

    // Named, not counted: the failure should point at the declaration to raise.
    expect(offenders).toEqual([])
  })

  it('sets no arbitrary Tailwind size below the minimum', () => {
    // `text-[6px]` is the same defect in a different syntax, and the rule above never saw it: the first
    // version of this spec only matched CSS `font-size:` declarations, so the counterexample rail kept
    // printing its step numbers at **6px** and the simulation rail at **7px** while this file passed.
    // Both were found by an external audit, not by the check that existed to prevent them.
    const offenders: string[] = []
    for (const { name, text } of sources()) {
      const scanned = withoutComments(text)
      for (const match of scanned.matchAll(/text-\[(\d+(?:\.\d+)?)px\]/g)) {
        if (Number(match[1]) < 11) {
          offenders.push(`${name}:${scanned.slice(0, match.index).split('\n').length}  ${match[0]}`)
        }
      }
      for (const match of scanned.matchAll(/text-\[(0\.\d+)rem\]/g)) {
        if (Number(match[1]) < 0.6875) {
          offenders.push(`${name}:${scanned.slice(0, match.index).split('\n').length}  ${match[0]}`)
        }
      }
    }
    expect(offenders).toEqual([])
  })

  it('defines the minimum as a token so call sites can state the constraint', () => {
    const base = readFileSync(join(__dirname, '../base.css'), 'utf8')
    expect(base).toMatch(/--iot-font-min:\s*0\.6875rem/)
  })

  it('uses that token rather than restating the number', () => {
    // A site that hardcodes `0.6875rem` is at the floor but does not say why, so the next edit has no
    // reason not to go lower.
    const restated: string[] = []
    for (const { name, text } of sources()) {
      for (const match of text.matchAll(/font-size:\s*0\.6875rem/g)) {
        const line = text.slice(0, match.index).split('\n').length
        restated.push(`${name}:${line}`)
      }
    }
    expect(restated).toEqual([])
  })
})
