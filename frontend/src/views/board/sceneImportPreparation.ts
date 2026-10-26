import { REQUEST_LIMITS } from '@/constants/requestLimits'
import { stateDeclaredVariableValue } from '@/utils/deviceRuntime'
import { templateManifestSemanticKey } from '@/utils/templateManifestCanonicalization'
import { normalizeTemplateLookupName } from '@/utils/templateUsage'
import type { DeviceTemplate } from '@/types/device'
import type { BoardSceneModel } from './portableScene'

/** A local proposal only: callers must show every change before submitting this scene. */
export const prepareSceneImport = (input: BoardSceneModel, catalog: DeviceTemplate[]) => {
  const scene: BoardSceneModel = JSON.parse(JSON.stringify(input))
  const templateRenames: Array<{ from: string; to: string }> = []
  const variableCorrections: Array<{ device: string; variable: string; state: string; from: string; to: string }> = []
  const stored = new Map(catalog.map(template => [normalizeTemplateLookupName(template.name), template]))
  const reserved = new Set(scene.templates.map(template => normalizeTemplateLookupName(template.name)))
  const sameDefinition = (left: DeviceTemplate, right: DeviceTemplate) =>
    templateManifestSemanticKey(left.manifest) === templateManifestSemanticKey(right.manifest)

  for (const template of scene.templates) {
    const from = template.name
    const key = normalizeTemplateLookupName(from)
    const existing = stored.get(key)
    if (!existing || sameDefinition(existing, template)) continue
    // Reuse a previous imported copy when it still matches; repeated imports must not grow the library.
    const copyName = (suffix: string) => `${from.slice(0, 100 - suffix.length).trimEnd()}${suffix}`
    const reusable = [...stored.values()]
      .sort((left, right) => left.name.localeCompare(right.name, 'en', { numeric: true }))
      .find(candidate => {
        const suffix = candidate.name.match(/ \(imported(?: [1-9]\d*)?\)$/)?.[0]
        return suffix && candidate.name === copyName(suffix)
          && !reserved.has(normalizeTemplateLookupName(candidate.name))
          && sameDefinition(candidate, { ...template, manifest: { ...template.manifest, Name: candidate.name } })
      })
    let to = reusable?.name ?? copyName(' (imported)')
    let index = 1
    // Each suffix is distinct; finite occupied/reserved names bound the search for a free one.
    while (!reusable && (reserved.has(normalizeTemplateLookupName(to)) || stored.has(normalizeTemplateLookupName(to)))) {
      to = copyName(` (imported ${++index})`)
    }
    template.name = to
    template.manifest.Name = to
    reserved.add(normalizeTemplateLookupName(to))
    templateRenames.push({ from, to })
    for (const device of scene.devices) {
      if (normalizeTemplateLookupName(device.templateName) === key) device.templateName = to
    }
  }
  const templates = new Map(scene.templates.map(template => [normalizeTemplateLookupName(template.name), template]))
  for (const device of scene.devices) {
    const template = templates.get(normalizeTemplateLookupName(device.templateName))
    for (const variable of device.variables || []) {
      const declared = stateDeclaredVariableValue(template, device.state, variable.name)
      if (declared === null || declared === variable.value) continue
      variableCorrections.push({ device: device.label, variable: variable.name, state: device.state || '', from: variable.value, to: declared })
      variable.value = declared
    }
  }
  const addedTemplates = scene.templates.filter(template => !stored.has(normalizeTemplateLookupName(template.name))).length
  return { scene, templateRenames, variableCorrections, addedTemplates,
    libraryLimitExceeded: catalog.length + addedTemplates > REQUEST_LIMITS.templates }
}
