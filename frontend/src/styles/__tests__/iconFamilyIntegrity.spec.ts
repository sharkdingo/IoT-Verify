import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Both icon families must stay installed, declared, and mutually fallback-safe.
 *
 * The project deliberately uses two Material families — `material-symbols-outlined` (370 uses) and
 * `material-icons-round` (47, confined to `DeviceDialog`, `RuleBuilder` and `Board`). That is a choice, not
 * drift: both are installed via fontsource, both classes declare an explicit `font-family`, and **each lists the other
 * as its fallback**.
 *
 * That cross-fallback is the load-bearing detail. An icon class whose font fails to resolve does not render a blank —
 * it renders its ligature as literal text, so `expand_more` appears as the word "expand_more" in the middle of the
 * interface. With the fallback in place a round icon degrades to the outlined glyph, which is a visual inconsistency
 * rather than a broken screen.
 *
 * Measured in the browser on a 12-device board: **194 icons, all resolving, median aspect ratio 1.0** — square, as a
 * glyph should be. An unresolved ligature would exceed 2.
 *
 * Pinned at the unit level because the browser probe could only reach the outlined family: the round one renders
 * exclusively inside `DeviceDialog`, which that run never opened. Rather than let the probe claim a comparison it did
 * not make, the structural guarantee is asserted here — the two imports and the two cross-referencing declarations —
 * where it holds regardless of which dialog happens to be on screen.
 */

const styleCss = () => readFileSync(join(__dirname, '../../style.css'), 'utf8')
const mainTs = () => readFileSync(join(__dirname, '../../main.ts'), 'utf8')

describe('icon family integrity', () => {
  it('imports both icon fonts', () => {
    const main = mainTs()
    // A missing import breaks silently: the class still applies, the ligature still renders — as a word.
    expect(main, 'the outlined family must be imported').toMatch(/@fontsource\/material-symbols-outlined/)
    expect(main, 'the round family must be imported').toMatch(/@fontsource\/material-icons-round/)
  })

  it('declares an explicit family for each icon class', () => {
    const css = styleCss()
    expect(css, 'the outlined class must declare its family')
      .toMatch(/\.material-symbols-outlined[\s\S]{0,200}font-family:[^;]*Material Symbols Outlined/)
    expect(css, 'the round class must declare its family')
      .toMatch(/\.material-icons-round[\s\S]{0,200}font-family:[^;]*Material Icons Round/)
  })

  it('gives each family the other as a fallback, so a load failure degrades instead of printing words', () => {
    const css = styleCss()
    // The specific ordering matters: each rule names its own family first, then the sibling. Dropping the sibling
    // would leave `sans-serif` as the only fallback — and a text font has no glyph for `expand_more`, so the
    // ligature name renders literally.
    // The shared base rule is a GROUPED selector — `.material-symbols-outlined, .material-icons-round { … }` — so a
    // regex expecting a class followed immediately by a brace matched nothing, and this rule failed at baseline while
    // the mutation went uncaught. The grouping is in fact the stronger arrangement: one declaration, both classes.
    const grouped = /\.material-symbols-outlined,\s*\.material-icons-round\s*\{[^}]*font-family:\s*([^;]+);/.exec(css)
    expect(grouped, 'both classes should share one base font-family declaration').toBeTruthy()
    expect(grouped?.[1], 'the shared base names both families before sans-serif')
      .toMatch(/Material Symbols Outlined.*Material Icons Round/)

    // The round class then overrides the order so its own font wins, keeping the sibling as its fallback.
    //
    // Anchored to a line start, because `.material-icons-round {` also appears as the *second half* of the grouped
    // selector above — so an unanchored match found the shared rule (which names Symbols first) and reported the
    // override as backwards.
    // Requires a BLANK line before it. In the grouped selector the two classes are written one per line, so
    // `\n.material-icons-round {` matched there too and captured the shared declaration — which names Symbols
    // first, making the override look backwards. The standalone rule is the one separated by an empty line.
    const roundOverride = /\n\s*\n\.material-icons-round\s*\{\s*font-family:\s*([^;]+);/.exec(css)
    expect(roundOverride, 'the round class should override the family order').toBeTruthy()
    expect(roundOverride?.[1], 'round names its own font first, then the outlined family as fallback')
      .toMatch(/Material Icons Round.*Material Symbols Outlined/)
  })
})
