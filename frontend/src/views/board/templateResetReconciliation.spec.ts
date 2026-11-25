import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { ScriptTarget, transpileModule } from 'typescript'
import { ref } from 'vue'
import { expect, it, vi } from 'vitest'
import type { DeviceNode } from '@/types/node'
import type { DeviceTemplate } from '@/types/device'
import type { ModelEnvironmentVariable } from '@/types/model'

const source = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const bind = (name: string, nextDeclaration: string, ports: Record<string, unknown>) => {
  const declaration = source.slice(source.indexOf(`const ${name} =`), source.indexOf(`const ${nextDeclaration} =`))
  const javascript = transpileModule(declaration, { compilerOptions: { target: ScriptTarget.ES2022 } }).outputText
  return new Function('ports', `with (ports) { ${javascript}; return ${name} }`)(ports)
}

it('adopts committed default template devices before deriving canvas state', () => {
  const oldNode = { id: 'sensor', label: 'Sensor', templateName: 'Sensor', state: 'custom-state',
    position: { x: 0, y: 0 }, width: 180, height: 120 } satisfies DeviceNode
  const nodes = ref<DeviceNode[]>([oldNode])
  const deviceTemplates = ref<DeviceTemplate[]>([])
  const environmentVariables = ref<ModelEnvironmentVariable[]>([])
  const boardDataLoadState = { nodes: 'error', templates: 'error', environment: 'error' }
  const expected = { templates: [{ name: 'Sensor', defaultTemplate: true, manifest: { Name: 'Sensor' } }],
    nodes: [{ ...oldNode, state: 'off' }],
    environmentVariables: [{ name: 'room', value: '20', privacy: 'public', trust: 'trusted' }] }
  const reconcileDanglingBoardFocus = vi.fn(() => {
    expect(nodes.value).toEqual(expected.nodes)
    expect(deviceTemplates.value).toEqual(expected.templates)
    expect(environmentVariables.value).toEqual(expected.environmentVariables)
  })
  const replaceNodesFromServer = (value: DeviceNode[]) => { nodes.value = value }
  const ports = { deviceTemplates, environmentVariables, nodes, boardDataLoadState, replaceNodesFromServer,
    reconcileDanglingBoardFocus, reloadUndoAvailability: vi.fn() }
  const adopt = bind('replaceTemplateState', 'replaceTemplateCatalog', ports)

  adopt(expected)

  expect(nodes.value).toEqual(expected.nodes)
  expect(reconcileDanglingBoardFocus).toHaveBeenCalledWith({ nodes: expected.nodes })
  expect(boardDataLoadState).toEqual({ nodes: 'ready', templates: 'ready', environment: 'ready' })
})

it('drops a concurrently removed device and its references together after reset recovery', () => {
  const board = {
    nodes: ref([{ id: 'lamp' }]), rules: ref([{ id: 'rule', toId: 'lamp' }]),
    specifications: ref([{ id: 'spec', deviceIds: ['lamp'] }]),
    deviceTemplates: ref([]), environmentVariables: ref([]), boardSnapshotError: ref(null),
    boardAuthScopeEpoch: 1, hydratedBoardAuthScopeEpoch: 0,
    allBoardDataKeys: ['nodes', 'rules', 'specs', 'templates', 'environment'],
    boardDataLoadState: {}, reconcileDanglingBoardFocus: vi.fn(),
    refreshCurrentBoardModelFingerprints: vi.fn(), syncBoardUndoAvailability: vi.fn(),
    replaceNodesFromServer: (value: Array<{ id: string }>) => { board.nodes.value = value },
    syncRuleDerivedEdges: vi.fn(() => {
      expect(board.nodes.value).toEqual([])
      expect(board.rules.value).toEqual([])
      expect(board.specifications.value).toEqual([])
    })
  }
  bind('adoptBoardSnapshot', 'refreshBoardSnapshot', board)({
    nodes: [], rules: [], specifications: [], deviceTemplates: [], environmentVariables: [],
    editHistory: { canUndo: false, canRedo: false, editHistoryRevision: '0:0:0' }
  })
  expect(board.syncRuleDerivedEdges).toHaveBeenCalledOnce()
  expect(board.boardDataLoadState).toEqual({ nodes: 'ready', rules: 'ready', specs: 'ready', templates: 'ready', environment: 'ready' })
})
