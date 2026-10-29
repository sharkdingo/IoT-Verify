// @vitest-environment node
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * A fixed-pixel height on a text container breaks at 200% text size (WCAG 2.2 SC 1.4.4).
 *
 * Measured, and the board passes: at a 32px root font — what a browser's 200% text setting actually does — there
 * is **no clipping, no horizontal scroll, and no target below 44px**. Worth pinning the *reason*, because it is
 * one decision away from being false.
 *
 * The reason is that the 173 `h-*` utilities in the markup are rem-based, so they grow with the root font. Only
 * four arbitrary pixel values exist, all `max-h-[…]` on an `iot-scroll-region` — a scroll *cap* rather than a
 * clip, so the content stays reachable. A single `h-[40px]` on a text row would reintroduce the failure, and it
 * would look completely reasonable in review.
 *
 * The probe that established this had two defects first, both of which produced a wrong number rather than an
 * error: it measured a control's own box instead of its effective target (136 false failures against p37's 0 on
 * the same board), and its clipping detector had to be proved capable of firing at all — forced an 8px clip and
 * confirmed it reported 7 elements — because "zero clipping" is indistinguishable from a blind detector.
 */

const DIRS = ['components', 'components/common', 'views']

const sources = () => {
  const files: Array<{ name: string, text: string }> = []
  for (const dir of DIRS) {
    const full = join(__dirname, '../..', dir)
    for (const entry of readdirSync(full, { withFileTypes: true })) {
      if (!entry.isFile() || !entry.name.endsWith('.vue')) continue
      files.push({ name: `${dir}/${entry.name}`, text: readFileSync(join(full, entry.name), 'utf8') })
    }
  }
  return files
}

describe('text zoom resilience', () => {
  const withoutComments = (text: string) => text.replace(/<!--[\s\S]*?-->/g, '')

  it('never pins a height in pixels, so a box grows with the text inside it', () => {
    // `h-[40px]` cannot grow; `h-10` is 2.5rem and does. The distinction is invisible in review and decides
    // whether a 200% user can read the row.
    //
    // `max-h-[…]` is exempt: it caps a *scroll region*, which keeps content reachable rather than losing it.
    // All four current pixel values are that case, every one on an `iot-scroll-region`.
    // The `(?<!max-)` matters: `\bh-\[` also matches the `h-[` inside `max-h-[500px]`, so without it this rule
    // fails on its own documented exemption. It did — four times, at baseline, before the lookbehind was added.
    const offenders: string[] = []
    for (const { name, text } of sources()) {
      withoutComments(text).split('\n').forEach((line, index) => {
        for (const match of line.matchAll(/(?<!max-)\b(?:min-)?h-\[(\d+)px\]/g)) {
          offenders.push(`${name}:${index + 1}  ${match[0]}  ${line.trim().slice(0, 70)}`)
        }
      })
    }
    expect(offenders).toEqual([])
  })

  it('caps a scroll region rather than clipping it, wherever a pixel height is used', () => {
    // The exemption above is only safe while every pixel height is a `max-h` on something that scrolls. A
    // `max-h-[500px]` on a container with `overflow: hidden` would silently lose content at large text.
    const offenders: string[] = []
    for (const { name, text } of sources()) {
      withoutComments(text).split('\n').forEach((line, index) => {
        if (!/\bmax-h-\[\d+px\]/.test(line)) return
        if (/iot-scroll-region|overflow-y-auto|overflow-auto|overflow-y-scroll/.test(line)) return
        offenders.push(`${name}:${index + 1}  ${line.trim().slice(0, 80)}`)
      })
    }
    expect(offenders).toEqual([])
  })
})
