import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { ScriptTarget, transpileModule } from 'typescript'
import { ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { errorDetails } from '@/utils/userMessage'
import { resolveImpactEnvironmentDefinition } from '@/utils/device'
import { validateDeviceRuntimeConfig, validateTemplateVariableValue } from '@/utils/deviceRuntime'
import { BOARD_ITEM_EDIT_OUTCOME_UNKNOWN } from '@/composables/useUnconfirmedBoardItemEdit'
import { readBoardWriteReceipt as readWriteReceipt } from '@/utils/boardWriteReceipt'

const source = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const extract = (start: string, end: string) => {
  const first = source.indexOf(start)
  const last = source.indexOf(end, first)
  if (first < 0 || last < 0) throw new Error(`Missing production function: ${start}`)
  return source.slice(first, last)
}
const handlers = extract('const saveEnvironmentVariables =', '// Portable-scene normalization/')
  + extract('const handleDeviceRuntimeSave =', 'const viewDeviceDetails =')
  + extract('const readBoardWriteReceipt =', 'type AsyncTaskStatus =')
const template = { name: 'Thermostat', manifest: { Name: 'Thermostat', InternalVariables: [
  { Name: 'temperature', IsInside: false, LowerBound: 0, UpperBound: 100 },
  { Name: 'threshold', IsInside: true, LowerBound: 0, UpperBound: 100 }
] } }
const setup = (error?: unknown) => {
  const owner = { token: 'session-A', active: true }
  const nodes = ref([{ id: 'thermostat', templateName: template.name }])
  const environmentVariables = ref([{ name: 'temperature', value: '20', trust: 'trusted', privacy: 'private' }])
  const mutation = { operation: 'updated', environmentVariables: [{ name: 'temperature', value: '25' }],
    patchResults: [{ name: 'temperature', changedFields: ['value'] }], environmentChanges: [], currentNodes: nodes.value }
  const dependencies = { errorDetails, resolveImpactEnvironmentDefinition, validateTemplateVariableValue, validateDeviceRuntimeConfig,
    BOARD_ITEM_EDIT_OUTCOME_UNKNOWN, unconfirmedEnvironmentPatches: new Map(), isPollingAbortedError: () => false, readWriteReceipt,
    ensurePlaybackClosedForMutation: () => true, ensureBoardDataReady: () => true,
    getToken: () => owner.token, isCurrentSessionToken: (token: string) => owner.token === token,
    boardAuthScopeEpoch: 0, isCurrentBoardAuthScope: () => owner.active,
    environmentMutationPending: ref(false), deviceRuntimeSaving: ref(false), nodes, environmentVariables,
    resolveTemplateForNode: () => template, deepClone: (value: unknown) => structuredClone(value), deviceRuntimeSnapshot: () => ({}),
    boardApi: { saveEnvironment: vi.fn(async () => { if (error) throw error; return mutation }),
      updateNodeRuntime: vi.fn(async () => { if (error) throw error; return mutation }) },
    enqueueBoardMutation: (work: () => Promise<unknown>) => work(), commitSemanticScene: vi.fn(),
    t: (key: string) => key, notifyBlocked: vi.fn(), notifyError: vi.fn(), notifyInfo: vi.fn(), notifySuccess: vi.fn(),
    reportEnvironmentChanges: vi.fn(), formatEnvironmentPatchResults: () => '',
    refreshBoardSnapshot: vi.fn().mockResolvedValue(false),
    markVerificationResultStale: vi.fn(), deviceRuntimeMatches: () => false,
    extractApiErrorMessage: (_error: unknown, fallback: string) => fallback }
  const create = new Function(...Object.keys(dependencies), transpileModule(handlers
    + '\nreturn { saveEnvironmentVariables, handleDeviceRuntimeSave }', { compilerOptions: { target: ScriptTarget.ES2022 } }).outputText)
  return { ...create(...Object.values(dependencies)), ...dependencies, owner }
}
const patch = (value: string) => [{ name: 'temperature', expected: { value: '20', trust: 'trusted', privacy: 'private' }, desired: { value } }]
const rejection = { response: { status: 422, data: { message: 'Value exceeds domain', data: {
  errors: { 'environmentVariables[0].value': 'Allowed integer range 0..100' }
} } } }

describe('field feedback in production Board mutation handlers', () => {
  it.each(['', '101', '1.5'])('rejects environment value %s before dispatch, without unknown reconciliation', async value => {
    const state = setup()
    const complete = vi.fn()
    await state.saveEnvironmentVariables(patch(value), complete)
    expect(state.boardApi.saveEnvironment).not.toHaveBeenCalled()
    expect(complete).toHaveBeenCalledWith(false, expect.objectContaining({ fields: {
      'environmentVariables[0].desired.value': value ? 'app.variableValueIntegerDomain' : 'app.variableValueRequired'
    } }))
    expect(state.refreshBoardSnapshot).not.toHaveBeenCalled()
    expect(state.notifyError).not.toHaveBeenCalled()
  })
  it.each(['environment', 'runtime'])('%s: returns a known rejection to its field owner', async kind => {
    const state = setup(rejection)
    const complete = vi.fn()
    if (kind === 'environment') await state.saveEnvironmentVariables(patch('25'), complete)
    else await state.handleDeviceRuntimeSave('thermostat', { variables: [{ name: 'threshold', value: '25' }] }, complete)
    expect(complete).toHaveBeenCalledExactlyOnceWith(false, errorDetails(rejection))
    expect(state.notifyError).not.toHaveBeenCalled()
    expect(state.commitSemanticScene).not.toHaveBeenCalled()
    expect(state.refreshBoardSnapshot).not.toHaveBeenCalled()
  })
  it('accepts a legal environment integer and reports its authoritative commit', async () => {
    const state = setup()
    const complete = vi.fn()
    await state.saveEnvironmentVariables(patch('+01'), complete)
    expect(state.boardApi.saveEnvironment).toHaveBeenCalledOnce()
    expect(state.commitSemanticScene).toHaveBeenCalledOnce()
    expect(complete).toHaveBeenCalledExactlyOnceWith(true, undefined)
    expect(state.notifySuccess).not.toHaveBeenCalled()
  })
  it('keeps an unknown Environment Pool write unconfirmed and blocks replay of the same intent', async () => {
    const state = setup(new Error('Lost response'))
    state.refreshBoardSnapshot.mockImplementationOnce(async () => {
      state.environmentVariables.value = [{ ...state.environmentVariables.value[0], value: '25' }]
      return true
    })
    const complete = vi.fn()
    await state.saveEnvironmentVariables(patch('25'), complete)
    await state.saveEnvironmentVariables(patch('25'), complete)
    expect(state.boardApi.saveEnvironment).toHaveBeenCalledOnce()
    expect(state.refreshBoardSnapshot).toHaveBeenCalledOnce()
    expect(complete).toHaveBeenCalledTimes(2)
    expect(complete.mock.calls).toEqual(Array.from({ length: 2 }, () => [false, expect.objectContaining({
      reasonCode: BOARD_ITEM_EDIT_OUTCOME_UNKNOWN, cause: 'app.environmentSaveOutcomeRefreshed'
    })]))
    expect(state.commitSemanticScene).not.toHaveBeenCalled()
    expect(state.notifySuccess).not.toHaveBeenCalled()
  })
  it.each([false, true])('does not describe an unconfirmed runtime write as rejected after refresh=%s', async refreshed => {
    const state = setup(new Error('Lost response'))
    state.refreshBoardSnapshot.mockResolvedValue(refreshed)
    const complete = vi.fn()
    await state.handleDeviceRuntimeSave('thermostat', { variables: [{ name: 'threshold', value: '25' }] }, complete)
    expect(state.notifyBlocked).not.toHaveBeenCalled()
    expect(state.notifyError).not.toHaveBeenCalled()
    expect(state.notifySuccess).not.toHaveBeenCalled()
    expect(state.commitSemanticScene).not.toHaveBeenCalled()
    expect(complete).toHaveBeenCalledExactlyOnceWith(false, expect.objectContaining({
      reasonCode: 'DEVICE_WRITE_OUTCOME_UNKNOWN',
      cause: refreshed ? 'app.deviceRuntimeOutcomeNotMatched' : 'app.deviceRuntimeOutcomeUnknownRefreshFailed'
    }))
  })
  it.each(['environment', 'runtime'])('%s: a late rejection cannot attach to a different account', async kind => {
    const state = setup()
    let reject!: (error: unknown) => void
    const pending = new Promise<never>((_, fail) => { reject = fail })
    if (kind === 'environment') state.boardApi.saveEnvironment.mockReturnValue(pending)
    else state.boardApi.updateNodeRuntime.mockReturnValue(pending)
    const complete = vi.fn()
    const submitted = kind === 'environment' ? state.saveEnvironmentVariables(patch('25'), complete)
      : state.handleDeviceRuntimeSave('thermostat', { variables: [{ name: 'threshold', value: '25' }] }, complete)
    state.owner.token = 'session-B'
    reject(rejection)
    await submitted
    expect(complete).not.toHaveBeenCalled()
    expect(state.refreshBoardSnapshot).not.toHaveBeenCalled()
  })
  it.each(['environment', 'runtime'])('%s: finishing an old unknown refresh cannot notify a different account', async kind => {
    const state = setup(new Error('Lost response'))
    let resolve!: (value: boolean) => void
    const pending = new Promise<boolean>(complete => { resolve = complete })
    const refresh = state.refreshBoardSnapshot
    refresh.mockReturnValue(pending)
    const complete = vi.fn()
    const submitted = kind === 'environment' ? state.saveEnvironmentVariables(patch('25'), complete)
      : state.handleDeviceRuntimeSave('thermostat', { variables: [{ name: 'threshold', value: '25' }] }, complete)
    await flushPromises()
    expect(refresh).toHaveBeenCalledOnce()
    state.owner.token = 'session-B'
    resolve(false)
    await submitted
    expect(state.notifyBlocked).not.toHaveBeenCalled()
    expect(state.notifyError).not.toHaveBeenCalled()
    expect(complete).not.toHaveBeenCalled()
  })
})
