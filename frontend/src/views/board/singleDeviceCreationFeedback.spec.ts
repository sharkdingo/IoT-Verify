import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { ScriptTarget, transpileModule } from 'typescript'
import { ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { errorDetails } from '@/utils/userMessage'

const source = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const extract = (start: string, end: string) => source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start)))
const handler = extract('const handleCreateDevice =', 'const handleCreateDevices =')
  + extract('const isDefinitiveMutationRejection =', 'type AsyncTaskStatus =')
const node = { id: 'new-device', templateName: 'Local probe', label: 'Hall probe', variables: [{ name: 'level', value: '29' }] }
const template = { name: 'Local probe', manifest: { Name: 'Local probe', InitState: 'Working', InternalVariables: [] } }
const setup = () => {
  const owner = { token: 'session-A', active: true }
  const nodes = ref<any[]>([])
  const mutation = { currentNodes: [node], currentSpecifications: [], environmentVariables: [],
    affectedDevices: [node], environmentChanges: [] }
  const dependencies = {
    ensurePlaybackClosedForMutation: () => true, ensureBoardDataReady: () => true,
    ensureBoardItemCapacity: () => true, ensureDeviceRuntimeCapacity: () => true, REQUEST_LIMITS: { devices: 300 },
    getToken: () => owner.token, isCurrentSessionToken: (token: string) => owner.token === token,
    boardAuthScopeEpoch: 0, isCurrentBoardAuthScope: () => owner.active,
    getVisibleDeviceNodes: () => nodes.value, getUniqueLabel: (label: string) => label,
    createDeviceInstanceId: () => node.id, getNextNodePosition: () => ({ x: 0, y: 0 }),
    DEFAULT_NODE_WIDTH: 176, DEFAULT_NODE_HEIGHT: 128,
    enqueueBoardMutation: vi.fn((work: () => Promise<unknown>) => work()),
    boardApi: { addNodes: vi.fn().mockResolvedValue(mutation) },
    commitSemanticScene: vi.fn(), focusCreatedDeviceNode: vi.fn(), reportEnvironmentChanges: vi.fn(),
    refreshDevices: vi.fn().mockResolvedValue(false), refreshEnvironmentVariables: vi.fn().mockResolvedValue(false),
    reloadUndoAvailability: vi.fn(), nodes, nextTick: vi.fn().mockResolvedValue(undefined),
    notifyBlocked: vi.fn(), notifyError: vi.fn(), t: (key: string) => key,
    localizedErrorMessage: (_error: unknown, fallback: string) => fallback, locale: ref('zh'), errorDetails,
    isPollingAbortedError: (error: any) => error?.name === 'PollingAbortedError'
  }
  const create = new Function(...Object.keys(dependencies), transpileModule(handler + '\nreturn handleCreateDevice',
    { compilerOptions: { target: ScriptTarget.ES2022 } }).outputText)
  return { ...dependencies, owner, handler: create(...Object.values(dependencies)), mutation }
}
const payload = (complete: (...args: any[]) => void) => ({ template, customName: node.label, runtime: { variables: node.variables }, complete })
const rejection = { response: { status: 422, data: { message: 'Invalid runtime value', data: {
  errors: { 'nodes[0].variables[0].value': 'Allowed integer range 0..100' }
} } } }

describe('single device creation production feedback', () => {
  it('returns a definitive server rejection to the current form with all field diagnostics', async () => {
    const state = setup()
    state.boardApi.addNodes.mockRejectedValue(rejection)
    const complete = vi.fn()
    await state.handler(payload(complete))
    expect(complete).toHaveBeenCalledExactlyOnceWith(false, errorDetails(rejection))
    expect(state.notifyError).not.toHaveBeenCalled()
    expect(state.refreshDevices).not.toHaveBeenCalled()
    expect(state.commitSemanticScene).not.toHaveBeenCalled()
  })

  it('commits a current confirmed write and settles its form exactly once', async () => {
    const state = setup()
    const complete = vi.fn()
    await state.handler(payload(complete))
    expect(state.commitSemanticScene).toHaveBeenCalledOnce()
    expect(state.focusCreatedDeviceNode).toHaveBeenCalledExactlyOnceWith(node)
    expect(complete).toHaveBeenCalledOnce()
    expect(complete.mock.calls[0]?.[0]).toBe(true)
    expect(complete.mock.calls[0]?.[1]).toBeUndefined()
    expect(state.notifyError).not.toHaveBeenCalled()
  })

  it.each([false, true])('unknown response retains reconciliation and warning (authoritative read=%s)', async refreshed => {
    const state = setup()
    state.boardApi.addNodes.mockRejectedValue(new Error('Lost response'))
    state.refreshDevices.mockResolvedValue(refreshed)
    state.refreshEnvironmentVariables.mockResolvedValue(refreshed)
    const complete = vi.fn()
    await state.handler(payload(complete))
    expect(state.reloadUndoAvailability).toHaveBeenCalledOnce()
    expect(state.notifyBlocked).toHaveBeenCalledExactlyOnceWith(refreshed
      ? 'app.deviceCreateOutcomeNotMatched' : 'app.deviceCreateOutcomeUnknownRefreshFailed')
    expect(state.notifyError).not.toHaveBeenCalled()
    expect(complete).toHaveBeenCalledExactlyOnceWith(false, undefined)
  })

  it('a lost response confirmed by authoritative reads commits no second write and completes successfully', async () => {
    const state = setup()
    state.boardApi.addNodes.mockRejectedValue(new Error('Lost response'))
    state.nodes.value = [node]
    state.refreshDevices.mockResolvedValue(true)
    state.refreshEnvironmentVariables.mockResolvedValue(true)
    const complete = vi.fn()
    await state.handler(payload(complete))
    expect(state.boardApi.addNodes).toHaveBeenCalledOnce()
    expect(state.focusCreatedDeviceNode).toHaveBeenCalledExactlyOnceWith(node)
    expect(complete).toHaveBeenCalledOnce()
    expect(complete.mock.calls[0]?.[0]).toBe(true)
    expect(complete.mock.calls[0]?.[1]).toBeUndefined()
    expect(state.notifyBlocked).toHaveBeenCalledExactlyOnceWith('app.deviceCreateOutcomeRefreshed')
    expect(state.notifyError).not.toHaveBeenCalled()
  })

  it('a retired write still settles its callback without publishing, focusing, or reporting to a new account', async () => {
    const state = setup()
    let resolve!: (result: typeof state.mutation) => void
    state.boardApi.addNodes.mockReturnValue(new Promise(done => { resolve = done }))
    const complete = vi.fn()
    const pending = state.handler(payload(complete))
    state.owner.token = 'session-B'
    resolve(state.mutation)
    await pending
    expect(state.commitSemanticScene).not.toHaveBeenCalled()
    expect(state.focusCreatedDeviceNode).not.toHaveBeenCalled()
    expect(complete).toHaveBeenCalledExactlyOnceWith(false, undefined)
    expect(state.notifyBlocked).not.toHaveBeenCalled()
  })

  it('a retired unknown refresh cannot continue into the new account history or feedback', async () => {
    const state = setup()
    state.boardApi.addNodes.mockRejectedValue(new Error('Lost response'))
    let resolve!: (refreshed: boolean) => void
    state.refreshDevices.mockReturnValue(new Promise(done => { resolve = done }))
    const complete = vi.fn()
    const pending = state.handler(payload(complete))
    await flushPromises()
    state.owner.token = 'session-B'
    resolve(false)
    await pending
    expect(state.reloadUndoAvailability).not.toHaveBeenCalled()
    expect(state.notifyBlocked).not.toHaveBeenCalled()
    expect(state.notifyError).not.toHaveBeenCalled()
    expect(complete).toHaveBeenCalledExactlyOnceWith(false, undefined)
  })

  it('a queue admission retired before dispatch settles the waiting form', async () => {
    const state = setup()
    state.enqueueBoardMutation.mockRejectedValue(Object.assign(new Error('Retired admission'), { name: 'PollingAbortedError' }))
    const complete = vi.fn()
    await state.handler(payload(complete))
    expect(complete).toHaveBeenCalledExactlyOnceWith(false)
    expect(state.boardApi.addNodes).not.toHaveBeenCalled()
    expect(state.notifyError).not.toHaveBeenCalled()
  })
})
