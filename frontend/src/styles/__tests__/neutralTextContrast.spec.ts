// @vitest-environment node
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Structural neutrals are legitimate, but not every step of the ramp is legible as text.
 *
 * `semanticColourOwnership.spec.ts` deliberately exempts slate/gray/zinc: they are the structural greys, not
 * semantic roles, and banning them would be wrong. That exemption quietly covered a real defect, though —
 * `text-slate-400` is **2.56:1 on white**, well under the 4.5:1 AA minimum for normal text, and it was used
 * on 103 text elements with no light-theme alternative.
 *
 * It stayed invisible for the whole audit because of a *measurement* bug rather than a reasoning one: Tailwind
 * v4 emits these colours as `oklch()`, the browser probe's colour parser returned nothing for `oklch`, and
 * every element using one was counted "unmeasurable" while the surface still reported CLEAN. A resolver that
 * silently declines to measure turns a defect into an apparent pass.
 *
 * Contrast against a white card, per step:
 *
 * | Step | On white | Verdict |
 * | :--- | ---: | :--- |
 * | `slate-400` | **2.56** | fails normal text; fine as a large decorative glyph or on a dark ground (5.71) |
 * | `slate-500` | 4.76 | passes |
 * | `slate-600` | 7.58 | passes |
 *
 * So the rule is about the *light* theme specifically, and it is narrow: a 400-step neutral is fine inside a
 * `dark:` variant, on an `aria-hidden` glyph, or as a border/background. It fails only as light-theme text.
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

describe('neutral text contrast', () => {
  /**
   * Blank comment content while keeping one line per line, so reported line numbers stay true.
   *
   * Deleting comments outright is simpler and was what this rule did first — but it collapses every
   * multi-line comment, and several of the comments in these files explain the very values being checked.
   * The first real failure then cited `Board.vue:17068` for a line that lives at 17281.
   */
  const blankComments = (lines: string[]) => {
    const out: string[] = []
    let inBlock = false
    for (const raw of lines) {
      let line = raw
      if (inBlock) {
        const close = line.indexOf('-->') >= 0 ? line.indexOf('-->') + 3 : (line.indexOf('*/') >= 0 ? line.indexOf('*/') + 2 : -1)
        if (close < 0) { out.push(''); continue }
        line = ' '.repeat(close) + line.slice(close)
        inBlock = false
      }
      // Single-line comments of either syntax, then an unterminated opener.
      line = line.replace(/<!--[\s\S]*?-->/g, m => ' '.repeat(m.length))
        .replace(/\/\*[\s\S]*?\*\//g, m => ' '.repeat(m.length))
      const open = Math.max(line.lastIndexOf('<!--'), line.lastIndexOf('/*'))
      if (open >= 0) { line = line.slice(0, open); inBlock = true }
      out.push(line)
    }
    return out
  }

  /**
   * Is this line's text sitting on a container that is dark in *both* themes?
   *
   * One place in the app is: the NuSMV diagnostic output, rendered as a console on `bg-slate-900`. Light ink
   * is the only correct choice there (`slate-300` measures 12.0:1), so a rule about the light theme's pale
   * card must not flag it. Searching a few lines up finds the wrapper, since the ground is declared on the
   * element that contains the `<pre>`.
   */
  const onDarkGround = (lines: string[], index: number) => {
    const window = lines.slice(Math.max(0, index - 4), index + 1).join(' ')
    return /\bbg-(slate|gray|zinc|neutral|stone)-(800|900|950)\b/.test(window)
      || /\bbg-black\b/.test(window)
  }

  it('never sets light-theme body text to a 400-step neutral', () => {
    // Only the bare utility counts. `dark:text-slate-400` is a different declaration entirely — on a dark card
    // that same value measures 5.71 and is the correct choice there, which is why the two halves of a
    // `text-slate-500 dark:text-slate-400` pair are both right.
    const NEUTRALS = ['slate', 'gray', 'zinc', 'neutral', 'stone']
    const offenders: string[] = []

    for (const { name, text } of sources()) {
      // Blank the comments in place rather than deleting them. `withoutComments` collapses every multi-line
      // comment, so line indices after one are wrong — this rule's first failure pointed at Board.vue:17068
      // when the real line was 17281, and a check that misreports the location wastes the reader's time
      // even when the finding is correct.
      const blanked = blankComments(text.split('\n'))

      blanked.forEach((line, index) => {
        for (const neutral of NEUTRALS) {
          // Word boundary at the start excludes `dark:text-…`, `hover:text-…`, `group-focus-within:text-…`:
          // a variant-prefixed utility applies conditionally and is judged against its own ground.
          const bare = new RegExp(`(^|[\\s"'\`])text-${neutral}-(300|400)\\b`)
          if (!bare.test(line)) continue
          // An icon-font glyph marked aria-hidden is decoration, and SC 1.4.3 does not apply to it. The
          // attribute may sit a little after the class on a wrapped element, so allow the same line.
          const decorative = /aria-hidden="true"/.test(line)
            && /material-symbols-outlined|material-icons-round/.test(line)
          if (decorative) continue
          // Ink on a deliberately dark container. The NuSMV diagnostic block is a console: `bg-slate-900` in
          // *both* themes, so its ink has to be light, and `slate-300` there measures **12.0:1**. This rule
          // is about the light theme's white-ish card, and it flagged the one place in the app whose ground is
          // dark by design — the mirror of the `dark:` exemption above, which I had not written.
          if (onDarkGround(blanked, index)) continue
          offenders.push(`${name}:${index + 1}  ${line.trim().slice(0, 96)}`)
        }
      })
    }

    // Named, not counted: a failure should point at the line to change.
    expect(offenders).toEqual([])
  })
})
