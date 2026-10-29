// @vitest-environment node
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Scroll regions are declared by the shared primitives, not assembled per site.
 *
 * `iot-scroll-region` (vertical) and `iot-scroll-region-x` (horizontal) each own four things: overflow,
 * overscroll containment, the token scrollbar, and scroll-padding so a programmatically revealed control
 * does not land flush against the edge. Thirty-one vertical regions had declared only the first two by
 * hand; six horizontal rails had declared overflow plus `scrollbar-thin`, **a class defined nowhere in
 * this project and provided by no plugin**, so they carried a default browser scrollbar — light-on-dark
 * in dark theme.
 *
 * Each of those regions looked correct in isolation, which is why the drift went unnoticed. Only a rule
 * about ownership catches it.
 */

const DIRS = ['components', 'components/common', 'views']

const sources = () => {
  const files: Array<{ name: string, text: string }> = []
  for (const dir of DIRS) {
    const full = join(__dirname, '../..', dir)
    for (const entry of readdirSync(full, { withFileTypes: true })) {
      if (entry.isFile() && entry.name.endsWith('.vue')) {
        files.push({ name: `${dir}/${entry.name}`, text: readFileSync(join(full, entry.name), 'utf8') })
      }
    }
  }
  return files
}

const classAttributes = (text: string) =>
  [...text.matchAll(/\sclass="([^"{}]*)"/g)].map(match => match[1])

describe('scroll region ownership', () => {
  it('declares vertical scrolling only through the primitive', () => {
    const offenders: string[] = []
    for (const { name, text } of sources()) {
      for (const cls of classAttributes(text)) {
        if (!/\boverflow-y-auto\b/.test(cls)) continue
        // A modal backdrop uses overflow to centre a tall dialog; it is not a scroll region of its own.
        if (/\bfixed\b/.test(cls) && /\binset-0\b/.test(cls)) continue
        offenders.push(`${name}: ${cls.slice(0, 70)}`)
      }
    }
    expect(offenders).toEqual([])
  })

  it('declares horizontal scrolling only through the primitive', () => {
    const offenders: string[] = []
    for (const { name, text } of sources()) {
      for (const cls of classAttributes(text)) {
        if (/\boverflow-x-auto\b/.test(cls)) offenders.push(`${name}: ${cls.slice(0, 70)}`)
      }
    }
    expect(offenders).toEqual([])
  })

  it('references no scrollbar class that nothing defines', () => {
    // `scrollbar-thin` was used in two places and defined in none, so it read as intent while doing
    // nothing. A class that cannot be found is worse than no class: it stops the next reader looking.
    const stylesheets = readdirSync(join(__dirname, '..'))
      .filter(name => name.endsWith('.css'))
      .map(name => readFileSync(join(__dirname, '..', name), 'utf8'))
      .join('\n')

    const referenced = new Set<string>()
    for (const { text } of sources()) {
      for (const cls of classAttributes(text)) {
        for (const token of cls.split(/\s+/)) {
          if (/^scrollbar-/.test(token)) referenced.add(token)
        }
      }
    }

    const undefinedClasses = [...referenced].filter(token => !stylesheets.includes(`.${token}`))
    expect(undefinedClasses).toEqual([])
  })

  it('does not put a horizontal rail on wrapping text', () => {
    // Wrapping and horizontal scrolling are mutually exclusive, so `overflow-x` there was always inert —
    // and the primitive adds `overflow-y: hidden`, which would clip the second line of a wrapped
    // formula. Caught while migrating: the spec formula preview wraps by design.
    const conflicts: string[] = []
    for (const { name, text } of sources()) {
      for (const cls of classAttributes(text)) {
        if (/iot-scroll-region-x/.test(cls) && /whitespace-pre-wrap|whitespace-normal|break-all/.test(cls)) {
          conflicts.push(`${name}: ${cls.slice(0, 70)}`)
        }
      }
    }
    expect(conflicts).toEqual([])
  })

  it('offers a neutral chip so a count need not borrow a status role', () => {
    const board = readFileSync(join(__dirname, '../board.css'), 'utf8')
    expect(board).toContain('.board-chip-neutral')

    // A bare count wearing a warning chip was read by a review as a queue of things needing attention when it
    // was only how many templates were listed.
    //
    // Comments are stripped before the search. Without that, the rule failed on the *explanation* written
    // above the fixed markup — a comment naming the old class is not a use of it, and a check that cannot tell
    // the difference punishes documenting the fix.
    const controlCenter = readFileSync(
      join(__dirname, '../../components/ControlCenter.vue'), 'utf8')
      .replace(/<!--[\s\S]*?-->/g, '')
    const countChip = controlCenter.slice(
      controlCenter.indexOf('filteredTemplates.length }}') - 500,
      controlCenter.indexOf('filteredTemplates.length }}')
    )
    expect(countChip).toContain('board-chip-neutral')
    expect(countChip).not.toContain('board-chip-warning')
  })
})
