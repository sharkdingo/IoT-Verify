import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { ScriptTarget, transpileModule } from 'typescript'
import { describe, expect, it, vi } from 'vitest'
import {
  recommendationSceneFingerprint, reconcileBoardNodeSnapshot, runTrackedBoardMutation
} from '@/views/Board.vue'
import type { DeviceNode } from '@/types/node'
import { createBoardSemanticCommit } from './semanticCommit'
import { readBoardWriteReceipt as readWriteReceipt } from '@/utils/boardWriteReceipt'

const source = readFileSync(resolve(__dirname, '..', 'Board.vue'), 'utf8')
const extract = (start: string, end: string) => source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start)))
const handler = extract('const readBoardWriteReceipt =', 'type AsyncTaskStatus =')
  + extract('const applyLayoutToNode =', 'const deviceRuntimeMatches =')
  + extract('const handleNodeMovedOrResized =', 'const isRuleDuplicateRejection =')

const nodeAt = (x: number): DeviceNode => ({
  id: 'lamp', label: 'Lamp', templateName: 'Light', state: 'off', position: { x, y: 20 }, width: 176, height: 128
})
const availability = (editHistoryRevision: string) => ({ canUndo: true, canRedo: false, editHistoryRevision })

const setup = () => {
  const nodes = { value: [nodeAt(0)] }
  const pendingNodeLayouts = new Map<string, { layout: Pick<DeviceNode, 'position' | 'width' | 'height'> }>()
  const activeNodeLayoutInteractions = new Map<string, undefined>()
  const markVerificationResultStale = vi.fn()
  const commitSemanticScene = createBoardSemanticCommit({
    setNodes: incoming => { nodes.value = reconcileBoardNodeSnapshot(nodes.value, incoming, pendingNodeLayouts, activeNodeLayoutInteractions) },
    setEnvironmentVariables: vi.fn(), setRules: vi.fn(), setSpecs: vi.fn(), syncRuleDerivedEdges: vi.fn(),
    clearDanglingFocus: vi.fn(), markVerificationResultStale, syncUndoAvailability: vi.fn()
  })
  const fingerprint = () => recommendationSceneFingerprint({ nodes: nodes.value, deviceTemplates: [], environmentVariables: [], rules: [], specifications: [] })
  const enqueueBoardMutation = (work: () => Promise<void>) =>
    runTrackedBoardMutation(work, fingerprint(), fingerprint, markVerificationResultStale)
  const snapshot = { nodes: [nodeAt(40)], editHistory: availability('recovered') }
  const adoptBoardSnapshot = (snapshot: { nodes: DeviceNode[]; editHistory: ReturnType<typeof availability> }) =>
    commitSemanticScene({ nodes: snapshot.nodes, availability: snapshot.editHistory, semanticChanged: false })
  const refreshBoardSnapshot = vi.fn(async (options?: { adopt?: typeof adoptBoardSnapshot }) => {
    const adopt = options?.adopt ?? adoptBoardSnapshot
    adopt(snapshot)
    return true
  })
  const ports = {
    nodes, pendingNodeLayouts, activeNodeLayoutInteractions,
    ensurePlaybackClosedForMutation: () => true, ensureBoardDataReady: () => true,
    getToken: () => 'owner', isCurrentSessionToken: () => true, boardAuthScopeEpoch: 0, isCurrentBoardAuthScope: () => true,
    enqueueBoardMutation, commitSemanticScene, adoptBoardSnapshot, refreshBoardSnapshot, readWriteReceipt,
    boardApi: { updateNodeLayout: vi.fn() },
    notifyBlocked: vi.fn(), notifyError: vi.fn(), t: (key: string) => key,
    extractApiErrorMessage: () => 'Layout error', isPollingAbortedError: () => false
  }
  const run = new Function('ports', `with (ports) { ${transpileModule(handler, {
    compilerOptions: { target: ScriptTarget.ES2022 }
  }).outputText}; return handleNodeMovedOrResized }`)(ports)
  return { ...ports, snapshot, markVerificationResultStale, move: (x: number): Promise<void> => {
    nodes.value[0].position.x = x
    return run('lamp')
  } }
}

describe('queued device layout receipts', () => {
  it('keeps a verdict current after a confirmed layout-only change', async () => {
    const state = setup()
    state.boardApi.updateNodeLayout.mockResolvedValue({
      operation: 'updated', currentNodes: [nodeAt(40)], ...availability('confirmed')
    })

    await state.move(40)

    expect(state.markVerificationResultStale).not.toHaveBeenCalled()
  })

  it.each([false, true])('reports an unconfirmed save and shows the actual layout (persisted=%s)', async persisted => {
    const state = setup()
    const recoveredX = persisted ? 40 : 0
    state.snapshot.nodes = [nodeAt(recoveredX)]
    state.boardApi.updateNodeLayout.mockRejectedValue(new Error('Lost response'))
    await state.move(40)
    expect(state.nodes.value[0].position.x).toBe(recoveredX)
    expect(state.boardApi.updateNodeLayout).toHaveBeenCalledOnce()
    expect(state.notifyBlocked).toHaveBeenCalledExactlyOnceWith(persisted ? 'app.deviceLayoutOutcomeRefreshed' : 'app.deviceLayoutOutcomeNotMatched')
    expect(state.notifyError).not.toHaveBeenCalled()
    expect(state.markVerificationResultStale).not.toHaveBeenCalled()
  })

  it('still invalidates a verdict if layout recovery observes a model change', async () => {
    const state = setup()
    state.snapshot.nodes[0].state = 'on'
    state.boardApi.updateNodeLayout.mockRejectedValue(new Error('Lost response'))
    await state.move(40)
    expect(state.nodes.value[0].state).toBe('on')
    expect(state.markVerificationResultStale).toHaveBeenCalledOnce()
  })
})
