// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { getTemplateUsage, summarizeImportedTemplates, summarizeTemplateUsage } from '../templateUsage'

describe('template library and scene usage', () => {
  const catalog = [
    { name: 'Light', defaultTemplate: true, deviceCount: 2 },
    { name: 'Request', defaultTemplate: false, deviceCount: 1 },
    { name: 'Unused', defaultTemplate: false, deviceCount: 0 }
  ]

  it('counts distinct referenced templates, not device instances or the entire library', () => {
    const usage = getTemplateUsage(catalog, [
      { templateName: 'light' }, { templateName: 'Light' }, { templateName: 'Request' }
    ])
    expect(usage).toEqual(catalog)
    expect(summarizeTemplateUsage(usage)).toEqual({ total: 3, custom: 2, used: 2, usedCustom: 1 })
    expect(summarizeTemplateUsage(getTemplateUsage(catalog, [])))
      .toEqual({ total: 3, custom: 2, used: 0, usedCustom: 0 })
  })

  it('reuses stored provenance and classifies missing scene dependencies as new custom templates', () => {
    expect(summarizeImportedTemplates([
      { name: 'light' }, { name: 'Request' }, { name: 'New sensor' }
    ], catalog)).toEqual({ total: 3, custom: 2, added: 1 })
    expect(summarizeImportedTemplates([], catalog)).toEqual({ total: 0, custom: 0, added: 0 })
  })
})
