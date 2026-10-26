/** Template identity follows the server's case-insensitive catalog lookup. */
export const normalizeTemplateLookupName = (value: unknown): string =>
  String(value ?? '').trim().toLowerCase()

export interface TemplateUsage {
  name: string
  defaultTemplate: boolean
  deviceCount: number
}

export const getTemplateUsage = (
  templates: Array<{ name: string; defaultTemplate?: boolean }>,
  devices: Array<{ templateName?: string }>
): TemplateUsage[] => {
  const counts = new Map<string, number>()
  for (const device of devices) {
    const key = normalizeTemplateLookupName(device.templateName)
    counts.set(key, (counts.get(key) || 0) + 1)
  }
  return templates.map(template => ({
    name: template.name,
    defaultTemplate: template.defaultTemplate === true,
    deviceCount: counts.get(normalizeTemplateLookupName(template.name)) || 0
  }))
}

export const summarizeTemplateUsage = (templates: TemplateUsage[]) => ({
  total: templates.length,
  custom: templates.filter(template => !template.defaultTemplate).length,
  used: templates.filter(template => template.deviceCount > 0).length,
  usedCustom: templates.filter(template => !template.defaultTemplate && template.deviceCount > 0).length
})

/** Portable files have no provenance; missing imports become custom templates in the target account. */
export const summarizeImportedTemplates = (
  incoming: Array<{ name: string }>,
  catalog: TemplateUsage[]
) => {
  const existing = new Map(catalog.map(template => [normalizeTemplateLookupName(template.name), template]))
  return {
    total: incoming.length,
    custom: incoming.filter(template => existing.get(normalizeTemplateLookupName(template.name))?.defaultTemplate !== true).length,
    added: incoming.filter(template => !existing.has(normalizeTemplateLookupName(template.name))).length
  }
}
