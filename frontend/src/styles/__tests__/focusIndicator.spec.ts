// @vitest-environment node
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * A focus ring nobody can see is not a focus indicator.
 *
 * The first full keyboard traversal of the board — 36 stops in light, 27 in dark — found every stop named, on
 * screen, and carrying *something*. But **7 of them had a ring under 3:1**, which WCAG 2.2 SC 1.4.11 sets as
 * the minimum for a non-text indicator. Three separate hardcoded blues were responsible:
 *
 * | Value | Where | Against its ground |
 * | :--- | :--- | ---: |
 * | `rgba(147, 197, 253, 0.95)` | nav bar controls | **1.72** |
 * | `rgba(53, 158, 255, 0.36)` | canvas fit control | worse |
 * | `rgba(53, 158, 255, 0.32)` | theme + language toggles | **2.76** |
 *
 * All three predate the audit's focus-indicator consolidation and survived it because they are raw `rgba`
 * rather than palette hues, so a sweep for hues did not see them. `--accent-border` is the token `base.css`
 * documents for this job ("also serves as a focus ring") and clears 3:1 in both themes.
 *
 * The template cards were a different defect: they shared hover's styling and then set `outline: none`, so a
 * keyboard user got a lift and a shadow but no ring, and could not tell focused from hovered. The fix had to
 * go on the **inner button** — the card is a `div` wrapping a transparent full-width `button` — and targeting
 * the card looked right while doing nothing in dark theme.
 */

const DIRS = ['components', 'components/common', 'views', 'styles']

const sources = () => {
  const files: Array<{ name: string, text: string }> = []
  for (const dir of DIRS) {
    const full = join(__dirname, '../..', dir)
    for (const entry of readdirSync(full, { withFileTypes: true })) {
      if (!entry.isFile() || !/\.(vue|css)$/.test(entry.name)) continue
      files.push({ name: `${dir}/${entry.name}`, text: readFileSync(join(full, entry.name), 'utf8') })
    }
  }
  return files
}

describe('focus indicator', () => {
  const withoutComments = (text: string) =>
    text.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '')

  it('draws a theme-dependent focus ring from the token, never from a hardcoded colour', () => {
    // One meaning, one colour. A literal is how three separate blues accumulated — each locally plausible, none
    // checked against its own ground, and all three under 3:1 where they landed.
    //
    const offenders: string[] = []
    for (const { name, text } of sources()) {
      const scanned = withoutComments(text)
      scanned.split('\n').forEach((line, index) => {
        if (!/outline:/.test(line)) return
        // `outline: none` and `outline: 0` are legitimate — usually paired with a box-shadow ring elsewhere.
        if (/outline:\s*(none|0)\b/.test(line)) return
        // A raw colour in an outline: rgba(), rgb(), or a hex literal.
        if (/outline:[^;]*(rgba?\(|#[0-9a-f]{3,8}\b)/i.test(line)) {
          offenders.push(`${name}:${index + 1}  ${line.trim().slice(0, 88)}`)
        }
      })
    }
    expect(offenders).toEqual([])
  })

  it('gives the template list a focus ring distinct from hover, on the element that receives focus', () => {
    const control = readFileSync(join(__dirname, '../../components/ControlCenter.vue'), 'utf8')

    // The interactive element is the inner button, not the card div. Styling the card passed in light theme by
    // accident — the browser's default outline was visible there — and did nothing in dark.
    expect(control, 'the focus ring belongs on the button inside the card')
      .toMatch(/\.template-card\s*>\s*button:focus-visible\s*\{[^}]*outline:\s*2px solid var\(--accent-border\)/)

    // And hover must no longer suppress it: `outline: none` in the shared hover/focus rule was the original
    // defect, so its return would silently remove the indicator again.
    const board = readFileSync(join(__dirname, '../board.css'), 'utf8')
    const hoverAt = board.indexOf('.iot-board .board-inventory-card:hover,')
    expect(hoverAt).toBeGreaterThan(-1)
    const shared = board.slice(hoverAt)
    const sharedRule = shared.slice(0, shared.indexOf('}'))
    expect(sharedRule, 'the shared hover/focus rule must not suppress the outline')
      .not.toMatch(/outline:\s*none/)
  })
})
