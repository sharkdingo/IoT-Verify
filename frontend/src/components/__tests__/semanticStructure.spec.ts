// @vitest-environment node
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * The heading outline and landmark names are how a screen-reader user navigates without reading everything.
 *
 * Two defects, both measured on a 12-device board and both invisible to sighted review:
 *
 * **A skipped heading level.** Template card titles were `h4` while the nearest heading above them is the panel's own
 * `h2` ("Control Center"), so the outline read `h1 → h2 控制中心 → h4 Air Conditioner`. A user stepping that outline is
 * told an `h3` exists and hunts for a section that was never there. The group label above the cards
 * ("Default Templates") is a `<span>` inside a `<summary>` and carries no level, so `h3` is the correct rung for the
 * card titles themselves rather than a reason to invent another heading.
 *
 * **An unnamed landmark.** `<section data-testid="environment-pool">` is a bare `<section>` — an implicit `region`
 * landmark — with no accessible name. It was the one unnamed landmark on the board, and an unnamed region is
 * indistinguishable from any other in a landmark list. It already contained an `h3` naming it, so the fix is
 * `aria-labelledby` pointing at that heading: one translated string instead of two that can drift apart.
 *
 * After both: **0 skipped levels, 0 unnamed regions**, in light and dark.
 *
 * Pinned because heading level is the attribute most likely to be chosen for its font size. Nothing about `h4` looks
 * wrong next to an `h2` in a template, and no visual check would catch it.
 */

const controlCenter = () => readFileSync(join(__dirname, '../ControlCenter.vue'), 'utf8')
const inspector = () => readFileSync(join(__dirname, '../SystemInspector.vue'), 'utf8')
const withoutComments = (text: string) => text.replace(/<!--[\s\S]*?-->/g, '')

describe('semantic structure', () => {
  it('gives template card titles the level directly below the panel heading', () => {
    const source = withoutComments(controlCenter())
    // The panel title is an h2; its cards must be h3, not h4.
    expect(source, 'the Control Center panel title should be an h2')
      .toMatch(/<h2[^>]*>\s*\{\{ t\('app\.controlCenter'\) \}\}/)
    expect(source, 'template card titles should be h3, one level below the panel heading')
      .toMatch(/<h3[^>]*class="template-card__title/)
    expect(source, 'an h4 card title would skip the h3 level entirely')
      .not.toMatch(/<h4[^>]*class="template-card__title/)
  })

  it('names the environment pool region by reference to its own heading', () => {
    const source = withoutComments(inspector())
    const at = source.indexOf('data-testid="environment-pool"')
    expect(at, 'the environment pool section should exist').toBeGreaterThan(-1)
    // The opening tag: a generous window from the element start, since attributes span several lines.
    const tagStart = source.lastIndexOf('<section', at)
    const tag = source.slice(tagStart, tagStart + 260)
    expect(tag, 'a bare <section> is an unnamed region landmark')
      .toMatch(/aria-labelledby="environment-pool-title"/)

    // And the referenced id must exist, or the label resolves to nothing and the region is unnamed again.
    expect(source, 'the referenced heading id must exist')
      .toMatch(/id="environment-pool-title"/)
  })

  it('keeps the referenced heading and the label pointing at the same element', () => {
    // The failure mode of aria-labelledby: renaming the heading id without updating the reference leaves a region
    // that looks labelled in source and is unnamed at runtime. Assert both halves agree.
    const source = withoutComments(inspector())
    const refs = (source.match(/aria-labelledby="environment-pool-title"/g) || []).length
    const ids = (source.match(/id="environment-pool-title"/g) || []).length
    expect(refs, 'exactly one region should reference the heading').toBe(1)
    expect(ids, 'exactly one heading should carry the referenced id').toBe(1)
  })
})
