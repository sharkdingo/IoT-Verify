// @vitest-environment node
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Complementary media queries must split at one value, not at two adjacent integers.
 *
 * `max-width: 1100px` paired with `min-width: 1101px` leaves 1100.5px matching **neither** rule, and a
 * fractional viewport width is routine on a scaled display. That pair was real and it broke a layout rather
 * than a detail: `.hero-section--with-auth` kept `padding-right: min(36rem, 38vw)` — the ~418px corridor
 * reserved for an absolutely positioned panel — while `.auth-panel` fell back to `position: relative` and
 * stacked under the title, leaving the corridor empty beside it. `max-width: 767px` / `min-width: 768px`
 * had the same shape one breakpoint down.
 *
 * The fix is `.98`: `max-width: 1100.98px` / `min-width: 1101px` covers the gap. Tailwind's own prefixes
 * are min-width only, so a hand-written compact counterpart to `sm:` (640px) ends at 639.98px.
 *
 * This is mechanically checkable, which is why it is a test instead of another line of prose that a future
 * pair of breakpoints will not read.
 */

const DIRS = ['components', 'components/common', 'views', 'styles']
const EXT = ['.vue', '.css']

const sources = () => {
  const files: Array<{ name: string, text: string }> = []
  for (const dir of DIRS) {
    const full = join(__dirname, '../..', dir)
    for (const entry of readdirSync(full, { withFileTypes: true })) {
      if (entry.isFile() && EXT.some(e => entry.name.endsWith(e))) {
        files.push({ name: `${dir}/${entry.name}`, text: readFileSync(join(full, entry.name), 'utf8') })
      }
    }
  }
  return files
}

/** Integer-px bounds, per axis. A fractional bound (`767.98px`) is already correct and not collected. */
const integerBounds = (text: string, kind: 'max' | 'min', axis: 'width' | 'height') =>
  [...text.matchAll(new RegExp(`${kind}-${axis}:\\s*(\\d+)px`, 'g'))].map(m => Number(m[1]))

describe('breakpoint complement', () => {
  for (const axis of ['width', 'height'] as const) {
    it(`leaves no fractional-${axis} gap between complementary queries`, () => {
      const offenders: string[] = []

      for (const { name, text } of sources()) {
        const maxima = integerBounds(text, 'max', axis)
        const minima = new Set(integerBounds(text, 'min', axis))
        for (const max of maxima) {
          // The complement of `max-width: Npx` is `min-width: (N+1)px`; together they skip N.5.
          if (minima.has(max + 1)) {
            offenders.push(
              `${name}: max-${axis}: ${max}px pairs with min-${axis}: ${max + 1}px `
              + `— ${max}.5px matches neither. Write max-${axis}: ${max}.98px.`,
            )
          }
        }
      }

      expect(offenders).toEqual([])
    })
  }
})
