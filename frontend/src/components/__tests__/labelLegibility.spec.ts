import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Structural rules for text that must stay readable.
 *
 * These pin the shape of fixes that took six passes to land, because the defect they prevent is
 * invisible in a diff and invisible in a unit test: a name given 23px of the 102px it needs still
 * renders, still has its `title`, and still passes every assertion about its content.
 *
 * The measurements behind each rule live in `.audit/LEDGER.md`; the point here is that a later edit
 * cannot quietly reintroduce the layout that caused them.
 */

const read = (relative: string) =>
  readFileSync(join(__dirname, '..', relative), 'utf8')

describe('label legibility', () => {
  it('gives the device name its own line rather than a share of the row', () => {
    const source = read('SystemInspector.vue')
    const nameAt = source.indexOf(':data-full-text="device.name"')
    expect(nameAt).toBeGreaterThan(-1)
    const row = source.slice(nameAt - 1400, nameAt + 1600)

    // The block name precedes a separate metadata row.
    expect(row).toMatch(/board-inventory-name block[^>]*:data-full-text="device.name"/)
    expect(row).toContain('board-device-meta')
    // Both qualifiers live below the name rather than beside it.
    expect(row).toContain(':data-full-text="device.type"')
    expect(row).toContain(':data-full-text="device.state"')
    // The name must not carry `flex-1`: that is `flex-basis: 0`, so it entered every squeeze at zero
    // width and grew only from what the chips left over — measured at 23px of the 102px
    // "Air Conditioner" needs.
    const nameSpan = source.slice(source.lastIndexOf('<span', nameAt), nameAt)
    expect(nameSpan).not.toContain('flex-1')
  })

  it('sizes the template grid by available width, not a fixed column count', () => {
    const source = read('ControlCenter.vue')
    const grid = source.slice(
      source.indexOf('template-group__grid'),
      source.indexOf('template-group__grid') + 200
    )

    // `grid-cols-2` in a 320px panel left each title 51px regardless of the name; one needed 217px.
    expect(grid).not.toMatch(/grid-cols-2\b/)
    expect(grid).toContain('auto-fill')
  })

  it('keeps template format guidance in contextual help without truncating it', () => {
    const source = read('ControlCenter.vue')
    const hint = source.slice(
      source.indexOf('app.deviceTemplateSchemaHint') - 700,
      source.indexOf('app.deviceTemplateSchemaHint') + 200
    )

    expect(hint).toContain('<InfoTooltip :text="t(\'app.deviceTemplateSchemaHint\')"')
    expect(hint).toContain('test-id="template-format-help"')
    expect(hint).not.toMatch(/class="[^"]*\btruncate\b[^"]*"[^>]*>\s*\{\{ t\('app\.deviceTemplateSchemaHint'\)/)
  })

  it('prioritizes section names over decorative tab icons', () => {
    for (const component of ['ControlCenter.vue', 'SystemInspector.vue']) {
      const source = read(component)
      const tabAt = source.indexOf('role="tab"')
      const tabButton = source.slice(tabAt, source.indexOf('</button>', tabAt))
      expect(tabButton).toContain('board-section-tab__label')
      expect(tabButton).not.toContain('material-symbols-outlined')
    }
  })
})
