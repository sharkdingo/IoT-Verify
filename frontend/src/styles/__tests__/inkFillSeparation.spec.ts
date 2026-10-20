import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { readThemeTokens, themeColor, contrast } from './themeTokens'

/**
 * A role's ink half must not be used as a fill under light text.
 *
 * `--danger`/`--warning`/`--success`/`--info`/`--accent` are tuned to be legible **as text on the page
 * ground**, so the dark theme lightens them (`--danger` → #fca5a5, `--warning` → #fcd34d). Painting a
 * surface with one and writing white on top inverts that: measured **1.90:1** on the attacked-device badge
 * and **1.44:1** on the template-reset button — the two worst readings in the interface, both passing in
 * light theme (6.47 and 5.02), which is exactly why a light-only look never found them.
 *
 * The `-fill` half exists for this job and is theme-stable by construction. `base.css` documents the split;
 * this test is the mechanical half, because the rule is decidable from the source: a `background` painted
 * with a bare role plus a `color` of white is always wrong.
 *
 * Related but distinct: `badgeContrast.spec.ts` catches *lightening a fill* (`white/20` over an accent),
 * a different way to collapse the same contrast. Both patterns look like a subtle surface and fail silently.
 */

const SRC = join(__dirname, '../..')
const SKIP = new Set(['__tests__', 'assets', 'testing'])

const darkTokens = readThemeTokens('dark')

const sources = () => {
  const files: Array<{ name: string, text: string }> = []
  const walk = (dir: string, prefix: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name)
      if (statSync(full).isDirectory()) {
        if (!SKIP.has(entry.name)) walk(full, `${prefix}${entry.name}/`)
      } else if (entry.name.endsWith('.css') || entry.name.endsWith('.vue')) {
        files.push({ name: `${prefix}${entry.name}`, text: readFileSync(full, 'utf8') })
      }
    }
  }
  walk(SRC, '')
  return files
}

const stripComments = (text: string) =>
  text.replace(/\/\*[\s\S]*?\*\//g, match => match.replace(/[^\n]/g, ' '))

describe('ink/fill separation', () => {
  it('never paints a surface with a role ink token under light text', () => {
    const offenders: string[] = []

    for (const { name, text } of sources()) {
      const clean = stripComments(text)
      for (const rule of clean.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
        const selector = rule[1].trim().replace(/\s+/g, ' ')
        if (!selector || selector.startsWith('@')) continue
        const body = rule[2]

        const background = /(?:^|[;\s])(?:background|background-color)\s*:\s*([^;]+)/.exec(body)
        const foreground = /(?:^|[;\s])color\s*:\s*([^;]+)/.exec(body)
        if (!background || !foreground) continue
        if (!/^\s*(#fff(fff)?|white|var\(--text-on-fill\))\s*$/i.test(foreground[1])) continue

        // A bare role, not its `-fill`/`-surface`/`-border` sibling.
        const ink = /var\(--(danger|warning|success|info|accent)\)/.exec(background[1])
        if (!ink) continue

        const line = clean.slice(0, rule.index).split('\n').length
        const measured = contrast(themeColor(darkTokens, `--${ink[1]}`), '#ffffff')
        offenders.push(
          `${name}:${line}  ${selector.slice(0, 44)} — white on var(--${ink[1]}) is `
          + `${measured.toFixed(2)}:1 in dark theme; use var(--${ink[1]}-fill)`,
        )
      }
    }

    expect(offenders).toEqual([])
  })
})
