import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { createSceneCodec } from './portableScene'
import { prepareSceneImport } from './sceneImportPreparation'
import type { DeviceTemplate } from '@/types/device'

const codec = createSceneCodec(key => key)
const household = () => codec.normalizeSceneFile(JSON.parse(readFileSync(
  join(process.cwd(), '..', 'docs/examples/household-entry-scene.json'), 'utf8')))

describe('scene import repair preview', () => {
  it('keeps both definitions without changing device ids, rules, specs or the original input', () => {
    const scene = household()
    const original = JSON.stringify(scene)
    const catalog = scene.templates.map(template => ({ ...template, manifest: { ...template.manifest, Description: 'Existing definition' } }))
    const plan = prepareSceneImport(scene, catalog)
    expect(plan.templateRenames).toHaveLength(4)
    expect(plan.scene.devices.map(device => device.id)).toEqual(scene.devices.map(device => device.id))
    expect(plan.scene.rules).toEqual(scene.rules)
    expect(plan.scene.specs).toEqual(scene.specs)
    for (const rename of plan.templateRenames) {
      expect(rename.to).toBe(`${rename.from} (imported)`)
      expect(plan.scene.templates.find(template => template.name === rename.to)?.manifest.Name).toBe(rename.to)
      expect(plan.scene.devices.filter(device => device.templateName === rename.to)).toHaveLength(
        scene.devices.filter(device => device.templateName === rename.from).length)
    }
    expect(JSON.stringify(scene)).toBe(original)
    expect(catalog.every(template => template.manifest.Description === 'Existing definition')).toBe(true)
  })

  it('reuses the same imported copy on repeat imports and skips other occupied names', () => {
    const scene = household()
    const original = scene.templates[0]!
    const catalog = [{ ...original, manifest: { ...original.manifest, Description: 'Different' } }]
    const occupied: DeviceTemplate = { name: `${original.name} (imported)`, manifest: { ...original.manifest, Name: `${original.name} (imported)`, Description: 'Occupied' } }
    const first = prepareSceneImport(scene, [...catalog, occupied])
    expect(first.templateRenames[0]!.to).toBe(`${original.name} (imported 2)`)
    const second = prepareSceneImport(scene, [...catalog, occupied, ...first.scene.templates])
    expect(second.scene).toEqual(first.scene)
    expect(prepareSceneImport(first.scene, [...catalog, occupied, ...first.scene.templates]).templateRenames).toEqual([])
  })

  it('previews state-determined variable corrections but preserves transition-driven values', () => {
    const manifest = JSON.parse(readFileSync(join(process.cwd(), '..', 'backend/src/main/resources/deviceTemplate/Car.json'), 'utf8'))
    const scene = codec.normalizeSceneFile({ schema: 'iot-verify.board-scene', version: 6,
      templates: [{ name: 'Car', manifest }], devices: [{ id: 'car', label: 'My car', templateName: 'Car', state: 'away',
        position: { x: 0, y: 0 }, width: 176, height: 128, variables: [{ name: 'location', value: 'garage' }] }],
      rules: [], specs: [], environmentVariables: [] })
    const plan = prepareSceneImport(scene, scene.templates)
    expect(plan.variableCorrections).toEqual([{ device: 'My car', variable: 'location', state: 'away', from: 'garage', to: 'away' }])
    expect(plan.scene.devices[0]!.variables![0]!.value).toBe('away')
    expect(scene.devices[0]!.variables![0]!.value).toBe('garage')
    scene.templates[0]!.manifest.Transitions = [{ Name: 'move', StartState: 'away', Assignments: [{ Attribute: 'location', Value: 'garage' }] }]
    const independent = prepareSceneImport(scene, scene.templates)
    expect(independent.variableCorrections).toEqual([])
    expect(independent.scene.devices[0]!.variables![0]!.value).toBe('garage')
  })

  it.each([{ full: false, suffix: 2 }, { full: true, suffix: 2 }, { full: false, suffix: 99 }])(
    'reuses an existing copy after earlier suffixes are freed ($full, $suffix)', ({ full, suffix }) => {
    const scene = household()
    const original = scene.templates[0]!
    const copyName = `${original.name} (imported ${suffix})`
    const catalog = scene.templates.map(template => template.name === original.name
      ? { ...template, manifest: { ...template.manifest, Description: 'Different stored definition' } }
      : template)
    catalog.push({ name: copyName, manifest: { ...original.manifest, Name: copyName } })
    if (full) {
      const missing = 100 - catalog.length
      catalog.push(...Array.from({ length: missing }, (_, index) => ({
        name: `Spare ${index}`, manifest: { ...original.manifest, Name: `Spare ${index}` }
      })))
    }
    const plan = prepareSceneImport(scene, catalog)
    expect(plan.templateRenames).toEqual([{ from: original.name, to: copyName }])
    expect(plan.addedTemplates).toBe(0)
    expect(plan.libraryLimitExceeded).toBe(false)
    expect(plan.scene.rules).toEqual(scene.rules)
    expect(plan.scene.specs).toEqual(scene.specs)
  })

  it('reserves incoming names and respects the 100-character name limit', () => {
    const scene = household()
    const original = scene.templates[0]!.name
    const longName = 'T'.repeat(100)
    scene.templates[0]!.name = longName
    scene.templates[0]!.manifest.Name = longName
    scene.devices.filter(device => device.templateName === original).forEach(device => { device.templateName = longName })
    const occupied = `${'T'.repeat(89)} (imported)`
    const extra = { name: occupied, manifest: { ...scene.templates[0]!.manifest, Name: occupied } }
    scene.templates.push(extra)
    const existing = { ...scene.templates[0]!, manifest: { ...scene.templates[0]!.manifest, Description: 'Different' } }
    const plan = prepareSceneImport(scene, [existing])
    expect(plan.templateRenames[0]!.to).not.toBe(occupied)
    expect(plan.templateRenames[0]!.to.length).toBeLessThanOrEqual(100)
    expect(new Set(plan.scene.templates.map(template => template.name.toLowerCase())).size).toBe(plan.scene.templates.length)
  })

  it('reports capacity before writes while allowing a full library to reuse identical definitions', () => {
    const scene = household()
    const filler = Array.from({ length: 100 - scene.templates.length }, (_, index) => ({
      name: `Spare ${index}`, manifest: { ...scene.templates[0]!.manifest, Name: `Spare ${index}` }
    }))
    expect(prepareSceneImport(scene, [...scene.templates, ...filler]).libraryLimitExceeded).toBe(false)
    const changed = scene.templates.map(template => ({ ...template, manifest: { ...template.manifest, Description: 'Different' } }))
    const plan = prepareSceneImport(scene, [...changed, ...filler])
    expect(plan.addedTemplates).toBe(4)
    expect(plan.libraryLimitExceeded).toBe(true)
  })
})
