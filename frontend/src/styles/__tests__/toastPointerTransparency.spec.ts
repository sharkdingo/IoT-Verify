import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * A toast lets every click through it, including clicks that land on its text.
 *
 * The first fix set `pointer-events: none` on `.el-message` and then handed pointer events back to its
 * children with `.el-message > *` "so the close button still works". No toast here has a close button, and
 * the child that got pointer events back was `.el-message__content`, the paragraph filling most of the toast
 * and the exact element the original CI trace named as intercepting a click on `board-undo`. So the fix
 * looked complete in review and changed almost nothing a user could feel.
 *
 * Scope: every `.css` file and every `.vue` `<style>` block under `src/`, including rules nested in `@media`.
 * `.el-message-box` is a modal and is meant to take pointer events, so it is excluded.
 */

const SRC = join(__dirname, '../..')

/** Stylesheet text per file: whole `.css` files, and only the `<style>` blocks of `.vue` files. */
const stylesheets = (): Array<{ name: string, css: string }> => {
  const out: Array<{ name: string, css: string }> = []
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name)
      if (entry.isDirectory()) {
        if (entry.name !== '__tests__') walk(full)
        continue
      }
      if (entry.name.endsWith('.css')) {
        out.push({ name: full, css: readFileSync(full, 'utf8') })
      } else if (entry.name.endsWith('.vue')) {
        const blocks = [...readFileSync(full, 'utf8').matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)]
        if (blocks.length) out.push({ name: full, css: blocks.map(block => block[1]).join('\n') })
      }
    }
  }
  walk(SRC)
  return out
}

/** `.el-message` and everything Element Plus names under it (`__content`, `--warning`, `-icon--*`), but not `.el-message-box`. */
const TOAST_SELECTOR = /\.el-message(?!-box)/

/** Innermost rule blocks only, so a rule nested in `@media` is matched on its own selector. */
const toastRules = () => stylesheets().flatMap(({ name, css }) =>
  [...css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{([^{}]*)\}/g)]
    .map(([, selector, body]) => ({ name, selector: selector.trim(), body }))
    .filter(rule => TOAST_SELECTOR.test(rule.selector)))

describe('toast pointer transparency', () => {
  it('takes pointer events off the toast root', () => {
    const roots = toastRules().filter(rule => /(^|,)\s*\.el-message\s*$/.test(rule.selector))
    expect(roots.some(rule => /pointer-events:\s*none/.test(rule.body)),
      'a bare `.el-message` rule should set `pointer-events: none`').toBe(true)
  })

  it('never hands pointer events back to anything inside a toast', () => {
    const rules = toastRules()
    // The content paragraph is where the regression lived; if the scan cannot see its rule, it sees nothing.
    expect(rules.some(rule => rule.selector.includes('.el-message__content'))).toBe(true)

    const offenders = rules
      .filter(rule => /pointer-events:\s*(?!none\b)[\w-]+/.test(rule.body))
      .map(rule => `${rule.name}: ${rule.selector}`)
    expect(offenders).toEqual([])
  })
})
