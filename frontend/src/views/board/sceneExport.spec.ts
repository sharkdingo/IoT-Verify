import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { describe, expect, it, vi } from 'vitest'
import actualI18n from '@/assets/i18n'
import { createSceneCodec, normalizeTemplateLookupName, SCENE_FILE_SCHEMA, SCENE_FILE_VERSION } from './portableScene'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const start = board.indexOf('<script setup')
const source = ts.createSourceFile('Board.ts', board.slice(board.indexOf('>', start) + 1,
  board.indexOf('</script>', start)), ts.ScriptTarget.Latest, true)
const bind = (name: string, ports: Record<string, any>) => {
  const statement = source.statements.find(statement => ts.isVariableStatement(statement)
    && statement.declarationList.declarations.some(item => item.name.getText(source) === name))
  if (!statement) throw new Error(`Missing Board declaration ${name}`)
  const javascript = ts.transpileModule(statement.getText(source), {
    compilerOptions: { target: ts.ScriptTarget.ES2022 }
  }).outputText
  return new Function('ports', `with (ports) { ${javascript}; return ${name} }`)(ports)
}

describe('scene export workflow', () => {
  it.each(['en', 'zh-CN'])('exports a re-importable scene with accurate download-request feedback in %s', locale => {
    const i18n = createI18n({ legacy: false, locale, messages: {
      en: actualI18n.global.getLocaleMessage('en'), 'zh-CN': actualI18n.global.getLocaleMessage('zh-CN')
    } })
    const t = (key: string, params: Record<string, unknown> = {}) => i18n.global.t(key, params)
    const codec = createSceneCodec(t)
    const scene = codec.normalizeSceneFile(JSON.parse(readFileSync(join(process.cwd(),
      '../docs/examples/default-climate-conflict-scene.json'), 'utf8')))
    const ports: Record<string, any> = {
      ...codec, t, SCENE_FILE_SCHEMA, SCENE_FILE_VERSION, normalizeTemplateLookupName,
      nodes: ref(scene.devices), environmentVariables: ref(scene.environmentVariables),
      rules: ref(scene.rules.map((rule, index) => ({ ...rule, id: String(index + 71) }))),
      specifications: ref(scene.specs),
      deviceTemplates: ref([...scene.templates, { name: 'Unused', manifest: { Name: 'Unused' } }]),
      ensureBoardDataReady: () => true, MAX_SCENE_IMPORT_BYTES: 64 * 1024 * 1024,
      downloadJsonFile: vi.fn(), notifySuccess: vi.fn(), notifyError: vi.fn(),
      getSceneErrorMessage: (error: Error) => error.message
    }
    for (const name of ['deepClone', 'getVisibleDeviceNodes', 'cloneVisibleDeviceNodes',
      'getReferencedSceneTemplates', 'buildSceneExport']) ports[name] = bind(name, ports)
    const before = JSON.stringify([ports.nodes.value, ports.rules.value, ports.specifications.value])
    bind('exportScene', ports)()
    expect(ports.notifyError).not.toHaveBeenCalled()
    expect(ports.downloadJsonFile).toHaveBeenCalledOnce()
    const [filename, serialized] = ports.downloadJsonFile.mock.calls[0]
    expect(filename).toMatch(/^iot-verify-scene-.*\.json$/)
    const exported = JSON.parse(serialized)
    expect(exported).toEqual(codec.canonicalizeSceneFile(scene))
    expect(exported.templates.map((template: any) => template.name)).not.toContain('Unused')
    expect(exported.rules.every((rule: any) => rule.id === undefined)).toBe(true)
    expect(codec.canonicalizeSceneFile(codec.normalizeSceneFile(exported))).toEqual(exported)
    expect(ports.notifySuccess).toHaveBeenCalledExactlyOnceWith(t('app.sceneExportStarted', {
      devices: 2, variables: 2, rules: 2, specs: 4
    }))
    expect(JSON.stringify([ports.nodes.value, ports.rules.value, ports.specifications.value])).toBe(before)
  })
})
