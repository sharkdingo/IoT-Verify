// @vitest-environment node
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Corner radius comes from the containment scale, never from a literal.
 *
 * The radius in this product carries meaning: a marker sits inside a button, a button inside a well, a well
 * inside a card, a card inside a floating panel, a panel inside a modal surface. A user reading the corner
 * should be able to tell which level they are looking at. That only holds while one role has one radius.
 *
 * It did not hold. 29 distinct literals across 115 declarations spelled the same roles several ways — `0.6rem`,
 * `0.625rem`, `0.65rem` and `10px` were all "a well", and `.el-message-box` shared a single `--iot-radius-card`
 * with the buttons *inside* it, three containment levels apart. Each value looked deliberate alone; side by side
 * they read as no system at all, which is what "not designed" actually looks like on screen.
 *
 * `50%` and `inherit` are exempt: `50%` is a circle (dots, spinners, avatars) whose shape is defined by its box,
 * and `inherit` defers to a parent that already picked a step.
 */

const SRC = join(__dirname, '../..')
const SKIP_DIRS = new Set(['__tests__', 'assets', 'testing'])

const sources = () => {
  const files: Array<{ name: string, text: string }> = []
  const walk = (dir: string, prefix: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name)
      if (entry.isDirectory()) {
        if (!SKIP_DIRS.has(entry.name)) walk(full, `${prefix}${entry.name}/`)
      } else if (entry.name.endsWith('.css') || entry.name.endsWith('.vue')) {
        files.push({ name: `${prefix}${entry.name}`, text: readFileSync(full, 'utf8') })
      }
    }
  }
  walk(SRC, '')
  return files
}

const ROLES = ['marker', 'control', 'action', 'well', 'card', 'panel', 'surface', 'pill', 'resize']

describe('radius scale', () => {
  it('declares every corner radius through a containment role', () => {
    const offenders: string[] = []

    for (const { name, text } of sources()) {
      text.split(/\r?\n/).forEach((line, index) => {
        const match = /border-radius:\s*([^;}]+)/.exec(line)
        if (!match) return
        const value = match[1].trim()
        if (/^(inherit|50%)$/.test(value)) return
        // Every space-separated corner must be a role token (or an exempt keyword).
        for (const part of value.split(/\s+/)) {
          if (part === '0' || part === '50%') continue
          if (/^var\(--iot-radius-[a-z]+\)$/.test(part)) continue
          offenders.push(`${name}:${index + 1}  ${value}`)
          break
        }
      })
    }

    expect(offenders).toEqual([])
  })

  it('defines every role the scale advertises, and no radius token outside it', () => {
    const base = readFileSync(join(SRC, 'styles/base.css'), 'utf8')
    const defined = [...base.matchAll(/^\s*--iot-radius-([a-z]+):/gm)].map(m => m[1]).sort()
    expect(defined).toEqual([...ROLES].sort())
  })
})
