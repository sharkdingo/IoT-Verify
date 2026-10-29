// @vitest-environment node
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * A coloured edge or glow is a semantic signal, so it comes from the role token — never a literal hue.
 *
 * The halo under a destructive button tells the user "this action destroys something". That claim has to hold
 * in both themes, and a literal does not follow a theme: fourteen shadows carried raw light-theme hues
 * (`rgba(239, 68, 68, …)`, `rgba(220, 38, 38, …)`, `rgba(37, 99, 235, …)`, `rgba(124, 58, 237, …)`) while the
 * `background` one line above them was already `var(--danger-fill)` / `var(--accent-fill)`. The fill followed
 * the theme and its own halo did not — the same missed-migration shape `semanticColourOwnership.spec.ts`
 * catches for Tailwind hue utilities, in the hand-written CSS it does not read.
 *
 * Derive from the token the element is painted with: `color-mix(in srgb, var(--danger-fill) 30%, transparent)`.
 *
 * The property list grew twice, each time because a narrower net had let a real defect through:
 * `filter: drop-shadow()` and `text-shadow` hid five edge-state glows, and `border`/`outline`/`stroke` hid
 * `border: 3px solid #EF4444` on the attacked-device ring — the same mark whose pulse *was* tokenised, so one
 * indicator was drawn in two different reds. Hex is checked alongside `rgba()` for the same reason.
 *
 * Neutral shade (`rgba(15, 23, 42, …)`, `rgba(0, 0, 0, …)`) is exempt: that is elevation, not meaning, and it
 * reads correctly on both grounds. This test is about hue.
 *
 * Two exemptions, both narrow and both with a named owner rather than a blanket allowance:
 *
 * - **Focus rings** answer to `focusIndicator.spec.ts`, which measures them against WCAG 1.4.11's 3:1 for a
 *   non-text indicator. Two survivors here (`RuleBuilder` select, the Landing auth input) are the same
 *   class of hardcoded blue that spec was written for — a contrast problem, not a token-plumbing one, so it
 *   belongs to that spec and fixing it blind here could lower a ring below its floor.
 * A `pulse-border` exemption used to sit here for dead code. The dead code is gone, so the exemption went
 * with it — an exemption that outlives what it covered silently forgives the next rule that takes the name.
 */

const DIRS = ['components', 'components/common', 'views', 'styles']

const sources = () => {
  const files: Array<{ name: string, text: string }> = []
  for (const dir of DIRS) {
    const full = join(__dirname, '../..', dir)
    for (const entry of readdirSync(full, { withFileTypes: true })) {
      if (entry.isFile() && (entry.name.endsWith('.vue') || entry.name.endsWith('.css'))) {
        files.push({ name: `${dir}/${entry.name}`, text: readFileSync(full + '/' + entry.name, 'utf8') })
      }
    }
  }
  return files
}

/** Comments quote past defects verbatim; counting them as declarations inflated every earlier measurement. */
const stripComments = (text: string) =>
  text.replace(/\/\*[\s\S]*?\*\//g, match => match.replace(/[^\n]/g, ' '))

/** A hue is chromatic when its channels spread — neutral shade stays within a narrow band. */
const isChromatic = (r: number, g: number, b: number) =>
  Math.max(r, g, b) - Math.min(r, g, b) >= 40

/**
 * Exempt declarations, keyed by the selector or at-rule that owns them so the exemption cannot drift onto a
 * neighbour. Each entry names the spec or the cleanup that is responsible instead.
 */
const EXEMPT = [
  { owner: 'select:focus', reason: 'focus ring — focusIndicator.spec.ts owns its contrast' },
  { owner: '.auth-form input:focus', reason: 'focus ring — focusIndicator.spec.ts owns its contrast' },
]

/** The selector or at-rule a line sits under, so an exemption can be scoped to it. */
const ownerOf = (lines: string[], index: number) => {
  for (let i = index; i >= 0 && i > index - 40; i--) {
    const match = /^\s*([.#&:[a-zA-Z@][^{}]*)\{\s*$/.exec(lines[i])
    if (match) return match[1].trim()
  }
  return ''
}

describe('shadow role ownership', () => {
  it('expresses every coloured shadow through a role token', () => {
    const offenders: string[] = []

    for (const { name, text } of sources()) {
      const clean = stripComments(text)
      const lines = clean.split(/\r?\n/)

      // Scan whole declarations, not lines: these values wrap, and `.edge-line--focused` stacks two
      // `drop-shadow()`s in one `filter`, so a per-line first-match regex saw only half of them.
      // `(?<![-\w])` keeps a custom property out: `--danger-border: #d96c6c` ends in `border:` and would
      // otherwise be read as a border declaration, which made the check demand that token *definitions*
      // be tokenised. Eighteen of the first run's twenty-two hits were that mistake.
      const pattern = /(?<![-\w])(box-shadow|text-shadow|filter|border|border-color|border-[a-z]+-color|outline|outline-color|stroke)\s*:\s*([^;}]*)/g
      for (const declaration of clean.matchAll(pattern)) {
        const [property, value] = [declaration[1], declaration[2]]
        if (property === 'filter' && !value.includes('drop-shadow')) continue

        const index = clean.slice(0, declaration.index).split('\n').length - 1
        // A keyframe step's owner is the @keyframes rule, not the percentage selector.
        let owner = ownerOf(lines, index)
        if (/^[\d.]|^(from|to)\b/.test(owner)) owner = ownerOf(lines, index - 1)
        if (EXEMPT.some(entry => owner === entry.owner || owner.endsWith(entry.owner))) continue

        // A hex inside `var(--token, #fallback)` is not the painted colour — the token is, and it is only
        // reached when the token is missing. Strip those before judging, or the check reports a site that
        // is already doing the right thing. (Whether a fallback *matches* its token is a separate concern:
        // several here are stale purples for a blue accent, tracked separately from this rule.)
        const painted = value.replace(/var\(\s*--[a-zA-Z0-9-]+\s*,[^)]*\)/g, 'var(--token)')

        for (const colour of painted.matchAll(/rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)/g)) {
          const [r, g, b] = [Number(colour[1]), Number(colour[2]), Number(colour[3])]
          if (!isChromatic(r, g, b)) continue
          offenders.push(`${name}:${index + 1} (${property})  rgb(${r}, ${g}, ${b}) — derive from the role token`)
        }

        // Hex, not just `rgba()` — `border: 3px solid #EF4444` on the attacked-device ring survived a
        // shadow sweep that only understood `rgba(…)`, leaving that one mark drawn in two different reds.
        for (const colour of painted.matchAll(/#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})\b/g)) {
          const raw = colour[1].length === 3 ? colour[1].split('').map(c => c + c).join('') : colour[1]
          const [r, g, b] = [0, 2, 4].map(i => parseInt(raw.slice(i, i + 2), 16))
          if (!isChromatic(r, g, b)) continue
          offenders.push(`${name}:${index + 1} (${property})  #${raw} — derive from the role token`)
        }
      }
    }

    expect(offenders).toEqual([])
  })
})
