// @vitest-environment node
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * A range slider's track must be visible, and the panel field-normaliser must not paint it.
 *
 * `board.css` normalises form fields inside every board panel with
 * `.iot-board .board-floating-panel input, … { background-color: var(--field-bg) }`. At 0-3-0 that beat whatever
 * the markup asked for, and `input` matches `input[type="range"]` as readily as a text box — so both sliders were
 * painted the *panel's own colour*. Measured: **1.0:1** for the simulation-steps track on a white panel, 1.01:1 in
 * dark. The groove was not faint, it was the same colour as the surface behind it, which is exactly how a user
 * reported it: "the line doesn't show".
 *
 * WCAG asks 3:1 for a control boundary, and this repo has already raised two dock borders for that reason. The fix
 * excludes `[type='range']` from the normaliser and paints the track from its role's *border* token
 * (`--info-border` / `--danger-border`, measured 3.14:1 light and 3.81:1 dark) rather than its surface token,
 * whose whole purpose is to sit quietly against a panel.
 *
 * One wrong turn worth recording: the first suspect was the `board-chip-info` class in the markup, and replacing
 * it changed nothing. Only asking the browser which rule actually won named the normaliser. A track colour is
 * decided by the cascade, not by the class list.
 */

const SRC = join(__dirname, '../..')

/** Only an `appearance: none` slider paints its own track; a native one is drawn by the browser. */
const CUSTOM_TRACK = /appearance-none/

const vueSources = () => {
  const files: Array<{ name: string, text: string }> = []
  for (const dir of ['components', 'components/common', 'views']) {
    for (const entry of readdirSync(join(SRC, dir), { withFileTypes: true })) {
      if (entry.isFile() && entry.name.endsWith('.vue')) {
        files.push({ name: `${dir}/${entry.name}`, text: readFileSync(join(SRC, dir, entry.name), 'utf8') })
      }
    }
  }
  return files
}

describe('range slider tracks stay visible', () => {
  it('keeps range inputs out of the panel field-background normaliser', () => {
    const board = readFileSync(join(SRC, 'styles/board.css'), 'utf8')

    const offenders: string[] = []
    for (const match of board.matchAll(/\.iot-board \.([\w-]+) input(:not\(\[type='range'\]\))?,/g)) {
      if (!match[2]) offenders.push(`.iot-board .${match[1]} input`)
    }

    expect(offenders, `these normalisers would repaint a slider track:\n${offenders.join('\n')}`).toEqual([])
  })

  it('paints a custom track from a role border token, never a surface token', () => {
    /*
     * A `-surface` token is designed to sit quietly against a panel — that is what made the track invisible
     * (`--danger-surface` measured 1.09:1 on white). The `-border` half of the same pair is solved for 3:1,
     * which is what a control boundary needs.
     */
    const offenders: string[] = []
    for (const { name, text } of vueSources()) {
      for (const match of text.matchAll(/<input\b[^>]*?type="range"[^>]*?>/gs)) {
        const tag = match[0]
        if (!CUSTOM_TRACK.test(tag)) continue
        const classAttr = /class="([^"]*)"/.exec(tag)?.[1] ?? ''
        if (/board-chip-|-surface\)/.test(classAttr)) {
          offenders.push(`${name}: a custom slider track must not use a surface token — ${classAttr.slice(0, 70)}`)
        }
        if (!/-border\)|--border/.test(classAttr)) {
          offenders.push(`${name}: a custom slider track needs an explicit visible colour — ${classAttr.slice(0, 70)}`)
        }
      }
    }

    expect(offenders, offenders.join('\n')).toEqual([])
  })
})
