// @vitest-environment node
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * A `hover:`/`focus:`/`disabled:`/`dark:` prefix on a hand-written class only works if this repo defines it.
 *
 * Tailwind generates variants for utilities *it* owns. `board-*` and `iot-*` are hand-written in `board.css`, so
 * `hover:board-chip-danger` in a template emitted **nothing** — the class sat in the DOM and no rule matched it.
 * Verified against the built bundle: grepping all 14 emitted CSS files for any `<variant>\:board-*` selector
 * returned zero matches, while the unprefixed base classes were present.
 *
 * That silently disabled **90 declarations** across the components, with nothing detecting it. The ones that
 * mattered, all confirmed in a browser:
 *
 *   - The device delete button asked for `hover:board-chip-danger hover:board-text-danger`. Colour and background
 *     were **identical** on hover (`rgb(97,113,135)`, transparent) — a destructive action with no danger cue.
 *   - Cancel/delete on running verification tasks (5 sites) — same.
 *   - `disabled:board-chip-info` on the runtime save button: the only `disabled:` utility Tailwind could emit
 *     there is `cursor-not-allowed`, so a *disabled* primary kept its accent fill and read as enabled.
 *   - `group-focus-within:board-text-info` on 13 rule-builder select icons — the keyboard-focus cue was absent.
 *
 * There is a second trap this does not catch, recorded here because it cost three attempts: defining the rule is
 * not sufficient if it loses the cascade. The ink variants had to be placed *after* the neutral-text normaliser
 * (`.iot-board .board-side-panel .text-slate-500`), because at equal specificity source order decides. Only CDP
 * matched-styles showed which rule won; the symptom was a danger-tinted background under a grey glyph.
 */

const DIRS = ['components', 'components/common', 'views']
const STYLE_FILES = ['styles/board.css', 'styles/base.css', 'style.css']

/** Every variant-prefixed hand-written class used in a template, with where it came from. */
const usedVariants = () => {
  const found = new Map<string, string[]>()
  const src = join(__dirname, '../..')
  for (const dir of DIRS) {
    for (const entry of readdirSync(join(src, dir), { withFileTypes: true })) {
      if (!entry.isFile() || !entry.name.endsWith('.vue')) continue
      const text = readFileSync(join(src, dir, entry.name), 'utf8')
      text.split(/\r?\n/).forEach((line, index) => {
        // `class="…"` and `:class="[…]"` alike — the whole line is scanned, since a binding may span it.
        for (const match of line.matchAll(
          /\b((?:hover|focus|focus-visible|active|disabled|dark|group-hover|group-focus|group-focus-within):(?:board|iot)-[A-Za-z0-9_-]+)/g)) {
          const site = `${dir}/${entry.name}:${index + 1}`
          found.set(match[1], [...(found.get(match[1]) ?? []), site])
        }
      })
    }
  }
  return found
}

const declaredSelectors = () => {
  const src = join(__dirname, '../..')
  return STYLE_FILES.map(file => readFileSync(join(src, file), 'utf8')).join('\n')
}

/**
 * The same text with comments blanked out.
 *
 * The substring check above may read a comment, because a class named in prose is still a class someone
 * defined somewhere. The reachability check below may **not**: `board.css:1953` explains in prose that a bare
 * `.hover\:board-text-danger:hover` lost the cascade, and reading that sentence as a definition made the check
 * pass a class that is only ever declared under `.iot-board `. Caught by mutation, not by review.
 */
const declaredRules = () => declaredSelectors().replace(/\/\*[\s\S]*?\*\//g, '')

/**
 * The markup of the two replay bars, which live **outside** `.iot-board`.
 *
 * `.board-timeline-host` is a sibling of the board shell, not a descendant — the same structure that made the
 * `--board-*` variables unreadable inside it. So an `.iot-board`-prefixed rule cannot reach anything here, and
 * a class defined *only* that way renders nothing in a replay bar while the substring check above stays green.
 * Sliced from the `.board-timeline` element (the host is one wrapper above it) to the end of the file, which
 * covers both bars: `SimulationTimeline.vue` is the whole component and `Board.vue`'s trace bar is its last
 * template region. Over-reading is safe here — a false positive names a real unreachable definition wherever
 * it sits — but under-reading is not, so a missing marker throws rather than skipping the file. A guard that
 * silently checks nothing is the failure mode this whole file exists to prevent.
 */
const timelineMarkup = () => {
  const src = join(__dirname, '../..')
  return ['components/PlaybackTerminal.vue', 'components/PlaybackChangesPanel.vue', 'components/SimulationTimeline.vue', 'views/Board.vue'].map(file => {
    const text = readFileSync(join(src, file), 'utf8')
    const at = file === 'views/Board.vue' ? text.indexOf('<PlaybackTerminal\n') : text.indexOf('<template>')
    if (at < 0) {
      throw new Error(
        `${file} no longer opens its playback template, so this guard would check `
        + 'nothing. Update the marker rather than deleting the case.')
    }
    return {
      name: file,
      text: text.slice(at),
      // Line of the opening element, so an offender reports a number that resolves in the editor.
      startLine: text.slice(0, at).split('\n').length,
    }
  })
}

describe('role class variants', () => {
  it('defines every variant-prefixed role class that a template uses', () => {
    const css = declaredSelectors()
    const offenders: string[] = []

    for (const [variantClass, sites] of usedVariants()) {
      // In CSS the colon is escaped: `hover:board-chip-danger` is written `.hover\:board-chip-danger`.
      // One backslash in the file, so one backslash in the needle — `'\\\\:'` was two and matched nothing,
      // which made this check report all 15 as undefined even after they were defined.
      const escaped = variantClass.replace(':', '\\:')
      if (!css.includes(escaped)) {
        offenders.push(`${variantClass} — used at ${sites.slice(0, 3).join(', ')}${sites.length > 3 ? ` (+${sites.length - 3} more)` : ''}`)
      }
    }

    expect(offenders, `these classes render nothing; define them in board.css:\n${offenders.join('\n')}`)
      .toEqual([])
  })

  it('defines the replay bars\' variant classes where a sibling of .iot-board can reach them', () => {
    /*
     * The check above is a substring match, so it cannot tell "defined" from "defined somewhere this element
     * is not". Every rule in `board.css` that themes a role variant is written `.iot-board .hover\:board-…`,
     * and the two replay bars are **siblings** of `.iot-board` — so for them, that is not a definition.
     *
     * This is the fourth incident from the same structure (the `--board-*` variables, a layout contract where
     * only the pair rule matched, and both classes migrated here), which is why it becomes a check rather than
     * another paragraph in the docs. `hover:board-text-strong` was `.iot-board`-only when the transport controls
     * started using it; the class was "defined", the suite was green, and the hover did nothing.
     *
     * Reachable means: at least one selector defining the class is either unprefixed or prefixed with a
     * `.board-timeline*` scope. A `.iot-board`-prefixed definition alone is what this rejects.
     */
    const css = declaredRules()
    const offenders: string[] = []

    for (const { name, text, startLine } of timelineMarkup()) {
      const used = new Set<string>()
      for (const match of text.matchAll(
        /\b((?:hover|focus|focus-visible|active|disabled|dark|group-hover|group-focus|group-focus-within):(?:board|iot)-[A-Za-z0-9_-]+)/g)) {
        used.add(match[1])
      }
      for (const variantClass of used) {
        const escaped = variantClass.replace(':', '\\\\:').replace(/[.*+?^${}()|[\]]/g, '\\$&')
        // Every selector list that ends in this class, capturing whatever precedes it on the same selector.
        const rules = [...css.matchAll(new RegExp(`([^{}\\n,]*\\.${escaped}[^{}\\n,]*)\\s*(?:,|\\{)`, 'g'))]
        const reachable = rules.some(([, selector]) => !selector.includes('.iot-board '))
        if (rules.length === 0) continue // the definition check above owns "not defined at all"
        if (!reachable) {
          offenders.push(
            `${name} (from line ${startLine}): ${variantClass} is only defined under \`.iot-board \`, `
            + 'which cannot match inside a replay bar')
        }
      }
    }

    expect(offenders, offenders.join('\n')).toEqual([])
  })

  it('never puts an opacity modifier on a hand-written class', () => {
    /*
     * `board-chip-warning/70` is not a Tailwind utility, so **no rule at all** is generated — not even the
     * un-suffixed strength. That is worse than the `primary/20` case `tailwind.config.js` warns about, where the
     * base utility still renders: here a danger chip and its text both come out as unstyled neutral.
     *
     * Eleven of these existed. Use `color-mix()` in a real rule if a softened role is genuinely wanted.
     */
    const offenders: string[] = []
    const src = join(__dirname, '../..')
    for (const dir of DIRS) {
      for (const entry of readdirSync(join(src, dir), { withFileTypes: true })) {
        if (!entry.isFile() || !entry.name.endsWith('.vue')) continue
        const text = readFileSync(join(src, dir, entry.name), 'utf8')
        text.split(/\r?\n/).forEach((line, index) => {
          for (const match of line.matchAll(/\b((?:board|iot)-[a-z]+(?:-[a-z]+)*(?:--[a-z-]+)?\/\d+)/g)) {
            offenders.push(`${dir}/${entry.name}:${index + 1}  ${match[1]}`)
          }
        })
      }
    }

    expect(offenders, `an opacity modifier on a hand-written class renders nothing:\n${offenders.join('\n')}`)
      .toEqual([])
  })

  it('never asks a borderless chip role for a border', () => {
    /*
     * `board-chip-*` declares `border: 0` deliberately — the roles are badges, where a border would add a
     * second edge inside a dense row. So `board-chip-warning border board-border-subtle` is a markup
     * contradiction, and the cascade resolves it the way the reader does not expect: `board.css` is
     * **unlayered** while Tailwind's `.border` lives in `@layer utilities`, and unlayered wins regardless
     * of order or specificity. Measured in the real host chain: that markup renders
     * `border-top-width: 0px`, while `board-surface-warning` renders 0.667px from the role's own border
     * token.
     *
     * Five sites read this way, all of them callouts rather than badges — including the verification
     * result dialog's error notice and its generation-warning notice. An edgeless tint reads as a
     * background wash rather than a bounded notice, so a warning strip stopped looking like one.
     * `board-surface-*` is the bounded form and needs no width utility.
     *
     * Only the `border` *width* utility counts. A bare `border-[color:…]` on a chip is a different
     * pattern — it sets a colour for an edge the chip does not draw, which is inert but not misleading —
     * and several small badges legitimately carry it.
     */
    const offenders: string[] = []
    const src = join(__dirname, '../..')
    for (const dir of DIRS) {
      for (const entry of readdirSync(join(src, dir), { withFileTypes: true })) {
        if (!entry.isFile() || !entry.name.endsWith('.vue')) continue
        const text = readFileSync(join(src, dir, entry.name), 'utf8')
        text.split(/\r?\n/).forEach((line, index) => {
          // Comments explain the defect and necessarily quote it; skip them or the guard fails on its
          // own documentation — a trap this repo has hit three times.
          if (/^\s*(\/\/|\*|<!--)/.test(line)) return
          // An UNPREFIXED chip role only. `hover:board-chip-info` tints on hover and does not claim the
          // resting border, so a `focus:ring` or a resting border beside it is not a contradiction.
          if (!/(^|[\s'"])board-chip-[a-z]+/.test(line)) return
          // Any border the chip would silence: the bare width utility or a numeric width. Measured: a
          // chip role zeroes `border-2 border-dashed` exactly as it zeroes `border`, while the same
          // markup without the chip renders 2px dashed.
          if (!/(^|[\s'"])border(-[0-9]+)?(?=[\s'"])/.test(line)) return
          offenders.push(`${dir}/${entry.name}:${index + 1}  ${line.trim().slice(0, 90)}`)
        })
      }
    }

    expect(offenders, `a chip role cannot render a border; use board-surface-* instead:\n${offenders.join('\n')}`)
      .toEqual([])
  })

  it('never asks a chip role for a resting ink it will overrule', () => {
    /*
     * The same contradiction in the ink dimension, and it resolves against the author the same way.
     * `board-chip-*` sets `color` as part of the role — `board-chip-neutral` is `var(--text-muted)` — and
     * `.board-text-strong` is a bare 0-1-0 rule that sits **earlier** in `board.css`. So at equal
     * specificity the chip wins on source order, and its `.iot-board .board-chip-neutral` arm (0-2-0) wins
     * outright. Confirmed in the emitted bundle rather than inferred: `.board-text-strong{color:var(--text)}`
     * appears 2,806 bytes before the chip rule in `index-*.css`.
     *
     * `board-chip-neutral board-text-strong` therefore renders muted, and nothing says so. Three sites read
     * this way, all introduced while migrating `bg-slate-100 text-slate-700` to role classes — the ground
     * moved to the role and the ink silently did not. Let the chip own the resting ink and change it on
     * hover (`hover:board-text-strong`), which is a different rule and does apply.
     *
     * Two shapes are *not* flagged. A `hover:`/`focus:`-prefixed ink targets a state the resting role does
     * not claim. And `board-chip-danger board-text-danger` — the chip and the ink naming the **same** role —
     * is the overwhelmingly common pairing here (~60 sites): the chip already sets that colour, so the pair
     * is redundant rather than misleading, and it renders what it says. Only a *mismatched* pair is a lie,
     * because there the losing class is the one the author wrote for a reason.
     */
    const offenders: string[] = []
    const src = join(__dirname, '../..')
    for (const dir of DIRS) {
      for (const entry of readdirSync(join(src, dir), { withFileTypes: true })) {
        if (!entry.isFile() || !entry.name.endsWith('.vue')) continue
        const text = readFileSync(join(src, dir, entry.name), 'utf8')
        text.split(/\r?\n/).forEach((line, index) => {
          // Comments quote the defect they explain; see the sibling rule above.
          if (/^\s*(\/\/|\*|<!--)/.test(line)) return
          // Pair them inside one quoted class group, so two branches of a ternary are not read as a
          // contradiction — `chip-success … : … chip-danger board-text-danger` is two separate lists.
          for (const group of line.match(/'[^']*'|"[^"]*"/g) ?? [line]) {
            const chip = group.match(/(^|[\s'"])board-chip-([a-z]+)(?=[\s'"])/)
            if (!chip) continue
            const inkMatch = group.match(/(^|[\s'"])board-text-(strong|muted|info|danger|warning|success)(?=[\s'"])/)
            if (!inkMatch) continue
            if (inkMatch[2] === chip[2]) continue
            offenders.push(
              `${dir}/${entry.name}:${index + 1}  board-chip-${chip[2]} + board-text-${inkMatch[2]}`)
          }
        })
      }
    }

    expect(offenders, `a chip role owns its resting ink; move the ink to hover: or drop it:\n${offenders.join('\n')}`)
      .toEqual([])
  })
})
