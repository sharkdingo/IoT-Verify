import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * A scoped rule must not silently defeat a Tailwind size cap on the same element.
 *
 * Vue compiles `<style scoped>` selectors with a `[data-v-…]` attribute, so `.foo { max-width: 100% }` is
 * specificity 0-2-0 while Tailwind's `.max-w-4xl` is 0-1-0. The scoped rule wins, and the cap in the template
 * — the thing a reviewer reads to learn how wide the element is — does nothing.
 *
 * `DeviceDialog` had exactly this: `max-w-4xl` (896px) in the class list and `.device-dialog-surface` in a
 * scoped `max-width: 100%` list intended for the *body's* overflow containment. Measured on a 2548×1465
 * screen, the dialog rendered 2516×1433 — 98.7% × 97.8% of the viewport — for content that needs 896px. It
 * read as the app being replaced by a settings screen rather than a device panel opening over the board.
 *
 * The failure mode is why this is a test and not a review note: both halves look correct in isolation. The
 * template says `max-w-4xl`, the stylesheet says "contain overflow", and only the rendered width disagrees.
 */

const COMPONENT_DIRS = ['components', 'components/common', 'views']

/**
 * Tailwind's caps, per axis.
 *
 * Axis-aware on purpose. A first version compared any `max-*` against any `max-*` and reported two
 * `ControlCenter` dialogs that cap **width** in the template and **height** in scoped CSS — different
 * properties, no conflict. A guard that reports non-conflicts teaches people to ignore it, which is the same
 * harm as one that reports nothing.
 */
const TAILWIND_CAP = {
  width: /\bmax-w-(?:xs|sm|md|lg|xl|[0-9]xl|full|screen|\[[^\]]+\])/,
  height: /\bmax-h-(?:xs|sm|md|lg|xl|[0-9]xl|full|screen|\[[^\]]+\])/
} as const

const sources = () => {
  const files: Array<{ name: string, text: string }> = []
  const src = join(__dirname, '../..')
  for (const dir of COMPONENT_DIRS) {
    for (const entry of readdirSync(join(src, dir), { withFileTypes: true })) {
      if (entry.isFile() && entry.name.endsWith('.vue')) {
        files.push({ name: `${dir}/${entry.name}`, text: readFileSync(join(src, dir, entry.name), 'utf8') })
      }
    }
  }
  return files
}

/** Class names a scoped block constrains, split by which axis it constrains. */
const scopedCappedClasses = (text: string): Record<'width' | 'height', Set<string>> => {
  const capped = { width: new Set<string>(), height: new Set<string>() }
  const styleAt = text.indexOf('<style scoped>')
  if (styleAt === -1) return capped
  // Comments stripped first: a `/* … */` block explaining a past conflict names the very class it warns
  // about, and the selector scan cannot tell prose from a selector. Leaving them in made this test report
  // `DeviceDialog` as still broken after it was fixed — a false positive sourced from its own fix note.
  const scoped = text.slice(styleAt).replace(/\/\*[\s\S]*?\*\//g, '')

  // Each declaration block whose body sets a max-width/height, paired with its selector list.
  for (const match of scoped.matchAll(/(^|\n)([^{}@\n][^{}]*?)\{([^{}]*)\}/g)) {
    const axes: Array<'width' | 'height'> = []
    if (/max-width\s*:/.test(match[3])) axes.push('width')
    if (/max-height\s*:/.test(match[3])) axes.push('height')
    if (!axes.length) continue
    for (const member of match[2].split(',')) {
      for (const cls of member.matchAll(/\.([A-Za-z][\w-]*)/g)) {
        for (const axis of axes) capped[axis].add(cls[1])
      }
    }
  }
  return capped
}

describe('scoped size rules do not defeat Tailwind caps', () => {
  it('never puts a scoped max-width/height on an element that also carries a Tailwind cap', () => {
    const offenders: string[] = []

    for (const { name, text } of sources()) {
      const capped = scopedCappedClasses(text)
      if (!capped.width.size && !capped.height.size) continue

      // Only the template half — a scoped selector naming another scoped selector is not the conflict.
      const template = text.slice(0, text.indexOf('<style') === -1 ? undefined : text.indexOf('<style'))
      for (const [, classList] of template.matchAll(/\sclass="([^"{}]*)"/g)) {
        const tokens = new Set(classList.split(/\s+/))
        for (const axis of ['width', 'height'] as const) {
          const cap = classList.match(TAILWIND_CAP[axis])?.[0]
          if (!cap) continue
          for (const cls of capped[axis]) {
            if (tokens.has(cls)) {
              offenders.push(`${name}: .${cls} has a scoped max-${axis} and the Tailwind cap `
                + `${cap} on the same element`)
            }
          }
        }
      }
    }

    expect(offenders, `the scoped rule wins at 0-2-0 and the cap does nothing:\n${offenders.join('\n')}`)
      .toEqual([])
  })

  it('keeps the device dialog capped at a readable column rather than the viewport', () => {
    // The specific regression: the cap must be in the class list, and the surface must stay out of the
    // scoped containment list that is there for the body.
    const dialog = readFileSync(join(__dirname, '../../components/DeviceDialog.vue'), 'utf8')

    const surfaceTag = dialog.slice(
      dialog.lastIndexOf('<div', dialog.indexOf('data-testid="device-dialog"')),
      dialog.indexOf('>', dialog.indexOf('data-testid="device-dialog"')))
    // The cap is now a size on the shared dialog layer (`--md` is 40rem) rather than a per-dialog Tailwind
    // utility. Asserting the size class keeps the original guarantee — a readable column, not the viewport —
    // while leaving the actual value in one place for every dialog.
    expect(surfaceTag, 'the dialog should cap its width').toMatch(/iot-dialog--md/)

    const scoped = dialog.slice(dialog.indexOf('<style scoped>'))
    const containment = scoped.slice(scoped.indexOf('.device-dialog-body'))
    expect(containment.slice(0, containment.indexOf('}')), 'the surface must not be in the body containment list')
      .not.toContain('device-dialog-surface')
  })
})
