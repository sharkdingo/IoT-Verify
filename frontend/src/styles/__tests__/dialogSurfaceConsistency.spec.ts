import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Every modal is built from styles/dialog.css, and nothing re-derives a dialog shell locally.
 *
 * This exists because the drift was invisible: adding a dialog with its own overlay tint and its own card
 * radius breaks no test and looks fine in isolation. Only when you open two of them in sequence does the
 * product stop reading as one thing — which is how a user reported it, comparing the Clear Scene
 * confirmation with the logout prompt. Measured across 13 hand-rolled modals at the time: 4 overlay tints,
 * 3 card radii, 8 widths, 4 footer alignments and 5 confirm-button heights.
 *
 * These checks are structural, on source text, because the alternative (mounting 13 dialogs and measuring)
 * cannot see a value that a scoped rule overrides later anyway.
 */

const SRC = join(__dirname, '../..')
const SHEET = readFileSync(join(SRC, 'styles/dialog.css'), 'utf8')

const vueFiles = (dir: string): string[] => readdirSync(join(SRC, dir), { withFileTypes: true })
  .flatMap(entry => {
    if (entry.isDirectory()) return entry.name === '__tests__' ? [] : vueFiles(`${dir}/${entry.name}`)
    return entry.name.endsWith('.vue') ? [`${dir}/${entry.name}`] : []
  })

/**
 * Every spec and E2E source, concatenated. A class with no CSS rule is still legitimate if a test addresses it
 * as a handle, so the orphan check below needs to see them all.
 */
const CONSUMER_TEXT = (() => {
  const walk = (dir: string): string[] => readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) return walk(path)
    return /\.(spec|test)\.ts$/.test(entry.name) ? [readFileSync(path, 'utf8')] : []
  })
  return [...walk(SRC), ...walk(join(SRC, '../e2e'))].join('\n')
})()

const modalSources = (): { path: string; source: string }[] => {
  const found = vueFiles('.')
    .map(path => ({ path, source: readFileSync(join(SRC, path), 'utf8') }))
    // A component may mention aria-modal in a comment or a selector; require an actual attribute.
    .filter(file => /aria-modal="true"/.test(file.source))
  // The empty scan is the failure mode this guards against: a glob that stops matching reports success.
  expect(found.length, 'should find the product modals').toBeGreaterThanOrEqual(8)
  return found
}

describe('dialog surface consistency', () => {
  it('gives every modal the shared overlay and card, with one declared size', () => {
    const offenders: string[] = []

    for (const { path, source } of modalSources()) {
      /*
       * Counted per modal, not merely "the class appears somewhere in the file".
       *
       * The first version of this check asked `source.includes('iot-dialog-overlay')`, which a file with
       * several dialogs satisfies as soon as *one* of them is migrated. Board.vue has seven, and a stale
       * `fixed inset-0 z-[var(--z-modal)] bg-black/60` overlay on the verification result dialog sat there
       * green: same scrim as before the layer, in a file the guard called compliant.
       */
      // Counted as an *attribute on its own line*, which is how these are authored. Matching the bare string
      // also caught a JS `closest('[aria-modal="true"]')` and a prose comment, inflating Board.vue to 7 and
      // making the guard report two phantom un-migrated dialogs alongside the one real one.
      const modals = source.match(/^\s*aria-modal="true"$/gm)?.length ?? 0
      const overlays = source.match(/class="iot-dialog-overlay/g)?.length ?? 0
      const cards = source.match(/class="iot-dialog iot-dialog--/g)?.length ?? 0
      if (overlays < modals) {
        offenders.push(`${path}: ${modals} modals but ${overlays} .iot-dialog-overlay — one still hand-rolled`)
      }
      if (cards < modals) {
        offenders.push(`${path}: ${modals} modals but ${cards} sized .iot-dialog cards`)
      }
      /*
       * No leftover hand-rolled scrim. Scoped to a *modal* z-index: the non-modal floating panels
       * (`role="region"`, seven of them in Board.vue) legitimately carry `shadow-2xl` and a `z-30`, so a bare
       * elevation or blur check flags correct code — it did, on all seven, before this was narrowed.
       */
      if (/fixed inset-0 z-\[var\(--z-(?:modal|session-modal|modal-nested)\)\]/.test(source)) {
        offenders.push(`${path} still declares a hand-rolled modal overlay`)
      }
    }

    expect(offenders, offenders.join('\n')).toEqual([])
  })

  it('keeps modal shells out of component stylesheets', () => {
    // A local `position: fixed; inset: 0` with a background is a second overlay, and a local radius or
    // elevation on a dialog card is a second surface. Both are how the four tints and three radii appeared.
    const offenders: string[] = []

    for (const { path, source } of modalSources()) {
      const styleStart = source.indexOf('<style')
      if (styleStart < 0) continue
      const style = source.slice(styleStart)
      // Scoped to a *modal z-index*, which only an overlay claims. Matching `backdrop-filter` alone was too
      // broad and flagged ControlCenter's glass side panel, which is not a dialog at all — a guard that
      // fires on legitimate code gets its assertion loosened rather than the defect fixed.
      if (/z-index:\s*var\(--z-(?:modal|session-modal|modal-nested)\)/.test(style)) {
        offenders.push(`${path} sets a modal z-index locally; use the shared overlay`)
      }
    }

    expect(offenders, offenders.join('\n')).toEqual([])
  })

  it('paints every blocking surface opaque, in both themes', () => {
    // Blocking surfaces own an opaque elevated ground, independent of decorative card tokens.
    const base = readFileSync(join(SRC, 'styles/base.css'), 'utf8')
    const box = base.slice(base.indexOf('.el-message-box {'))
    const boxBody = box.slice(0, box.indexOf('}'))
    expect(boxBody).toContain('background: var(--surface-elevated)')

    const card = SHEET.slice(SHEET.indexOf('.iot-dialog {'), SHEET.indexOf('.iot-dialog--sm'))
    expect(card).toContain('background: var(--surface-elevated)')
    // Matched on the `background` declaration, not on any mention of the token: the border legitimately uses
    // `--iot-color-card-border`, whose name contains `--iot-color-card-b…`, so a bare substring check failed
    // on correct code.
    const paintedWith = (rule: string) => rule.match(/background:[^;]*/g)?.join(' ') ?? ''
    for (const translucent of ['--iot-color-card-bg', '--surface-overlay']) {
      expect(paintedWith(card), `the dialog card must not be painted with ${translucent}`)
        .not.toContain(translucent)
      expect(paintedWith(boxBody), `the message box must not be painted with ${translucent}`)
        .not.toContain(translucent)
    }
  })

  it('keeps a dialog centred at every viewport', () => {
    // Element Plus centres MessageBox from its own overlay and cannot dock, so a bottom-sheet rule here gives
    // one class of surface two positions: the logout prompt on the bottom edge, the scene-clear confirmation
    // mid-screen, same app, same width. The narrow block may change size and padding, never position.
    expect(SHEET.slice(SHEET.indexOf('.iot-dialog-overlay {'), SHEET.indexOf('.iot-dialog-overlay--nested')))
      .toContain('align-items: center')

    const narrow = SHEET.slice(SHEET.indexOf('@media (max-width: 639.98px)'))
    for (const docking of ['align-items: flex-end', 'align-items: flex-start', 'bottom: 0']) {
      expect(narrow, `the narrow-viewport block must not dock (${docking})`).not.toContain(docking)
    }
  })

  it('leaves no dialog class on markup that nothing styles and nothing addresses', () => {
    /*
     * Migrating a dialog to the shared layer deletes its local rules, and it is easy to delete the rule but
     * leave the class sitting in the `class="…"` attribute. Three such orphans survived this migration
     * (`control-center-spec-dialog`, `-dialog-body`, `-dialog-footer`) — each invisible, each implying to the
     * next reader that some stylesheet still cares about it.
     *
     * A class with no rule is still legitimate when a test or an E2E spec addresses it (`account-delete-dialog`
     * is a handle, not a style), so both are accepted as consumers.
     */
    const allCss = [
      ...vueFiles('.').map(path => readFileSync(join(SRC, path), 'utf8')),
      readFileSync(join(SRC, 'styles/dialog.css'), 'utf8'),
      readFileSync(join(SRC, 'styles/base.css'), 'utf8'),
      readFileSync(join(SRC, 'styles/board.css'), 'utf8')
    ].join('\n')

    const candidates = new Set<string>()
    for (const { source } of modalSources()) {
      for (const attr of source.match(/class="[^"]*"/g) ?? []) {
        for (const cls of attr.slice(7, -1).split(/\s+/)) {
          // Dialog-ish, hyphenated, and not part of the shared layer (covered by the checks above).
          if (/^[a-z][a-z0-9-]*(dialog|modal|overlay)[a-z0-9-]*$/.test(cls) && !cls.startsWith('iot-dialog')) {
            candidates.add(cls)
          }
        }
      }
    }
    expect(candidates.size, 'the scan should find dialog classes to check').toBeGreaterThan(0)

    const orphans = [...candidates].filter(cls => {
      const styled = new RegExp('\\.' + cls + '(?![\\w-])').test(allCss)
      return !styled && !CONSUMER_TEXT.includes(cls)
    })
    expect(orphans, `these classes are styled by nothing and addressed by nothing:\n${orphans.join('\n')}`)
      .toEqual([])
  })

  it('uses only action variants the layer actually declares', () => {
    /*
     * The orphan scan above deliberately skips `iot-dialog*` names as "covered by the checks above" — and
     * nothing above checked the *variant modifiers*. `iot-dialog-btn--secondary` was written once and
     * declared nowhere, so it computed to a bare `iot-dialog-btn`: transparent fill AND transparent
     * border, measured in a real dialog card. The control it dressed was the counterexample dialog's
     * escalation to its owning run — the single button carrying an evidence→run level transition — and it
     * had no visible boundary.
     *
     * An undeclared modifier is invisible in exactly the wrong way: the markup reads as deliberate, the
     * name reads as vocabulary (a spec elsewhere even asserts a *different* button must not carry it), and
     * nothing errors. This makes the vocabulary closed.
     */
    const declared = new Set(
      [...SHEET.matchAll(/\.iot-dialog-btn--([a-z][a-z0-9-]*)/g)].map(match => match[1]))
    expect(declared.size, 'the layer should declare some action variants').toBeGreaterThan(2)

    const used = new Map<string, string[]>()
    for (const { path, source } of modalSources()) {
      for (const match of source.matchAll(/iot-dialog-btn--([a-z][a-z0-9-]*)/g)) {
        if (!used.has(match[1])) used.set(match[1], [])
        used.get(match[1])!.push(path)
      }
    }

    const undeclared = [...used.entries()]
      .filter(([variant]) => !declared.has(variant))
      .map(([variant, paths]) => `${variant} (used in ${[...new Set(paths)].join(', ')})`)
    expect(undeclared, `action variants used in markup but declared in no stylesheet:\n${undeclared.join('\n')}`)
      .toEqual([])
  })

  it('states one overlay tint, one card radius and one elevation', () => {
    // The point of the layer: these appear once each. A second declaration is a fork.
    expect(SHEET.match(/^\.iot-dialog-overlay \{/gm)).toHaveLength(1)
    const card = SHEET.slice(SHEET.indexOf('.iot-dialog {'), SHEET.indexOf('.iot-dialog--sm'))
    expect(card).toContain('border-radius: var(--iot-radius-surface)')
    expect(card).toContain('box-shadow: var(--shadow-elevated)')
  })

  it('sizes the MessageBox button from the same token as the shared dialog button', () => {
    // Element Plus owns those elements, so they cannot carry `.iot-dialog-btn` and base.css must declare the
    // height itself. Both therefore reference one custom property rather than repeating a literal — comparing
    // two literals is what let five button heights coexist here before, each plausible on its own.
    const heightOf = (css: string, selector: string) => {
      const i = css.indexOf(selector)
      expect(i, `${selector} should exist`).toBeGreaterThan(-1)
      return css.slice(i, css.indexOf('}', i)).match(/min-height:\s*([^;]+)/)?.[1]?.trim()
    }
    expect(heightOf(SHEET, '.iot-dialog-btn {')).toBe('var(--iot-target-min)')
    expect(heightOf(readFileSync(join(SRC, 'styles/base.css'), 'utf8'),
      '.el-message-box__btns .el-button {')).toBe('var(--iot-target-min)')
  })

  it('offers a non-destructive confirm, so a red button still means removal', () => {
    // Every confirmation used to be `confirmDestructive`, which made the danger button meaningless: it
    // appeared on "apply this suggestion anyway" and on "delete this device" alike.
    const feedback = readFileSync(join(SRC, 'utils/feedback.ts'), 'utf8')
    // Anchored on the full declaration, not a prefix: `toContain('export const confirmChoice')` also matched
    // `confirmChoiceX`, so renaming the export away left this assertion green.
    expect(feedback).toMatch(/export const confirmChoice\s*=/)
    const body = feedback.slice(feedback.search(/export const confirmChoice\s*=/))
    expect(body).not.toContain('el-button--danger')
    // And it is the one the non-destructive call sites actually reach.
    expect(readFileSync(join(SRC, 'views/Board.vue'), 'utf8')).toContain('confirmChoice({')
  })
})
