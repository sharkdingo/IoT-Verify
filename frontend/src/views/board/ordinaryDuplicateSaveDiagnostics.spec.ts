import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { ScriptTarget, transpileModule } from 'typescript'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { errorDetails, localizedErrorMessage } from '@/utils/userMessage'
import { REQUEST_LIMITS } from '@/constants/requestLimits'
import { defaultSpecTemplates } from '@/assets/config/specTemplates'

const source = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const extract = (start: string, end: string) => {
  const first = source.indexOf(start)
  const last = source.indexOf(end, first)
  if (first < 0 || last < 0) throw new Error(`Missing production save handler: ${start}`)
  return source.slice(first, last)
}
const handlers = extract('const handleAddRule =', 'const ruleRecommendationTargetTypes =')
  + extract('const handleAddSpec =', 'const getNextNodePosition =')
  + extract('const isDefinitiveMutationRejection =', 'type AsyncTaskStatus =')

const setup = (error: unknown, recovered = false) => {
  const specifications = ref<any[]>([])
  const ownership = { token: 'session-A', active: true }
  const dependencies = {
    REQUEST_LIMITS, defaultSpecTemplates, errorDetails,
    boardAuthScopeEpoch: 0, getToken: () => ownership.token,
    isCurrentSessionToken: (token: string) => token === ownership.token,
    isCurrentBoardAuthScope: () => ownership.active,
    ensurePlaybackClosedForMutation: () => true,
    enqueueBoardMutation: (work: () => Promise<unknown>) => work(),
    ensureBoardDataReady: () => true, ensureBoardItemCapacity: () => true,
    ensureNestedItemCapacity: () => true, assertRulesHaveTriggers: () => true,
    resolveNodeRef: () => ({}),
    boardApi: { addRule: vi.fn().mockRejectedValue(error), addSpec: vi.fn().mockRejectedValue(error) },
    rules: ref([]), nodes: ref([]), specifications,
    commitSemanticScene: vi.fn(), focusRuleOnCanvas: vi.fn(), focusSpecInInspector: vi.fn(),
    refreshRules: vi.fn().mockResolvedValue(recovered),
    refreshSpecifications: vi.fn(async () => { if (recovered) specifications.value = [{}]; return recovered }),
    reloadUndoAvailability: vi.fn(), ruleExists: () => recovered,
    isSameSpecification: () => recovered, buildSpecFormula: () => 'G off',
    buildSpecDeviceRefsFromConditions: () => [], nextTick: () => Promise.resolve(),
    t: (key: string) => key, notifyBlocked: vi.fn(), notifyError: vi.fn(),
    extractApiErrorMessage: (cause: unknown, fallback: string) => localizedErrorMessage(cause, fallback, 'zh-CN')
  }
  const create = new Function(...Object.keys(dependencies), transpileModule(
    handlers + '\nreturn { handleAddRule, handleAddSpec }',
    { compilerOptions: { target: ScriptTarget.ES2022 } }).outputText)
  return { ...create(...Object.values(dependencies)), ...dependencies, ownership }
}
const failure = (reasonCode: string, status = 409) => ({ response: { status,
  data: { message: 'An identical item exists; change the checked condition',
    data: { reasonCode, errors: { 'rules[1]': 'Remove the duplicate' } } } } })

describe('ordinary save rejection diagnostics in production Board handlers', () => {
  for (const [kind, code] of [['rule', 'DUPLICATE_RULE'], ['spec', 'DUPLICATE_SPECIFICATION']] as const) {
    const submit = async (state: ReturnType<typeof setup>, complete: ReturnType<typeof vi.fn>) => kind === 'rule'
      ? state.handleAddRule({ rule: { sources: [{ fromId: 'lamp', fromApi: 'state' }], toId: 'lamp', toApi: 'on' }, complete })
      : state.handleAddSpec({ templateId: '1', aConditions: [], ifConditions: [], thenConditions: [], complete })

    it(`${kind}: passes a typed duplicate's exact details back to its editing form`, async () => {
      const error = failure(code)
      const state = setup(error)
      const complete = vi.fn()
      await submit(state, complete)
      expect(complete).toHaveBeenCalledExactlyOnceWith(false, errorDetails(error))
      expect(state.notifyError).not.toHaveBeenCalled()
      expect(state.commitSemanticScene).not.toHaveBeenCalled()
      expect(kind === 'rule' ? state.refreshRules : state.refreshSpecifications).toHaveBeenCalledWith('session-A')
      expect(state.reloadUndoAvailability).toHaveBeenCalledOnce()
    })

    it.each([400, 409, 422])(`${kind}: does not refresh another account or attach a late rejection to its form (HTTP %s)`, async status => {
      const error = failure(status === 409 ? code : 'INVALID_REQUEST', status)
      const state = setup(error)
      let reject!: (error: unknown) => void
      const pending = new Promise((_, fail) => { reject = fail })
      const write = kind === 'rule' ? state.boardApi.addRule : state.boardApi.addSpec
      write.mockReturnValue(pending)
      const complete = vi.fn()
      const submitted = submit(state, complete)
      state.ownership.token = 'session-B'
      reject(error)
      await submitted
      expect(complete).toHaveBeenCalledExactlyOnceWith(false, undefined)
      expect(state.refreshRules).not.toHaveBeenCalled()
      expect(state.refreshSpecifications).not.toHaveBeenCalled()
      expect(state.reloadUndoAvailability).not.toHaveBeenCalled()
      expect(state.notifyError).not.toHaveBeenCalled()
    })

    it.each([400, 422])(`${kind}: keeps rejected input diagnostics in its form without claiming or reading a mutation (HTTP %s)`, async status => {
      const error = failure('INVALID_REQUEST', status)
      const state = setup(error)
      const complete = vi.fn()
      await submit(state, complete)
      expect(complete).toHaveBeenCalledExactlyOnceWith(false, errorDetails(error))
      expect(state.notifyError).not.toHaveBeenCalled()
      expect(state.commitSemanticScene).not.toHaveBeenCalled()
      expect(state.refreshRules).not.toHaveBeenCalled()
      expect(state.refreshSpecifications).not.toHaveBeenCalled()
      expect(state.reloadUndoAvailability).not.toHaveBeenCalled()
    })

    it(`${kind}: does not misclassify a generic conflict or unconfirmed response`, async () => {
      const state = setup(failure('OTHER_CONFLICT'))
      const complete = vi.fn()
      await submit(state, complete)
      expect(complete.mock.calls[0]?.[0]).toBe(false)
      expect(complete.mock.calls[0]?.[1]).toBeUndefined()
      expect(state.notifyError).toHaveBeenCalledOnce()

      const recovered = setup(failure(code, 503), true)
      const confirmed = vi.fn()
      await submit(recovered, confirmed)
      expect(confirmed.mock.calls[0]?.[0]).toBe(true)
      expect(confirmed.mock.calls[0]?.[1]).toBeUndefined()
      expect(recovered.notifyError).not.toHaveBeenCalled()
      expect(recovered.reloadUndoAvailability).toHaveBeenCalledOnce()
    })
  }
})

const refreshers = extract('const refreshRules =', 'const retryBoardDataLoad =')
describe('partial collection refresh production ownership', () => {
  for (const kind of ['rules', 'specs'] as const) {
    const setupRead = () => {
      const ownership = { token: 'session-A', active: true }
      let resolve!: (items: any[]) => void
      let reject!: (error: unknown) => void
      const pending = new Promise<any[]>((done, fail) => { resolve = done; reject = fail })
      const dependencies = {
        boardAuthScopeEpoch: 0, getToken: () => ownership.token,
        isCurrentSessionToken: (token: string) => token === ownership.token,
        isCurrentBoardAuthScope: () => ownership.active,
        boardApi: { getRules: vi.fn(() => pending), getSpecs: vi.fn(() => pending) },
        rules: ref([{ id: 'new-account-rule' }]), specifications: ref([{ id: 'new-account-spec' }]),
        boardDataLoadState: { rules: 'ready', specs: 'ready' },
        reconcileDanglingBoardFocus: vi.fn(), syncRuleDerivedEdges: vi.fn()
      }
      const create = new Function(...Object.keys(dependencies), transpileModule(
        refreshers + '\nreturn { refreshRules, refreshSpecifications }',
        { compilerOptions: { target: ScriptTarget.ES2022 } }).outputText)
      const handlers = create(...Object.values(dependencies))
      return { ...dependencies, ownership, resolve, reject,
        read: kind === 'rules' ? handlers.refreshRules : handlers.refreshSpecifications }
    }

    it(`${kind}: rejects a retired owner before starting a read`, async () => {
      const state = setupRead()
      state.ownership.token = 'session-B'
      const read = state.read('session-A')
      state.resolve([{ id: 'old-account-item' }])
      expect(await read).toBe(false)
      expect(state.boardApi.getRules).not.toHaveBeenCalled()
      expect(state.boardApi.getSpecs).not.toHaveBeenCalled()
      expect(state.boardDataLoadState[kind]).toBe('ready')
    })

    it.each([false, true])(`${kind}: the current owner can settle its read (failed=%s)`, async failed => {
      const state = setupRead()
      const read = state.read('session-A')
      if (failed) state.reject(new Error('Read unavailable'))
      else state.resolve([{ id: 'current-item' }])
      expect(await read).toBe(!failed)
      expect(state.boardDataLoadState[kind]).toBe(failed ? 'error' : 'ready')
      if (!failed) expect(kind === 'rules' ? state.rules.value : state.specifications.value).toEqual([{ id: 'current-item' }])
    })

    for (const retired of ['switch', 'unmount'] as const) {
      it.each([false, true])(`${kind}: a late ${retired} read cannot land or mark the new owner unavailable (failed=%s)`, async failed => {
        const state = setupRead()
        const read = state.read('session-A')
        if (retired === 'switch') state.ownership.token = 'session-B'
        else state.ownership.active = false
        state.boardDataLoadState[kind] = 'ready'
        if (failed) state.reject(new Error('Old read unavailable'))
        else state.resolve([{ id: 'old-account-item' }])
        expect(await read).toBe(false)
        expect(state.boardDataLoadState[kind]).toBe('ready')
        expect(state.rules.value).toEqual([{ id: 'new-account-rule' }])
        expect(state.specifications.value).toEqual([{ id: 'new-account-spec' }])
        expect(state.reconcileDanglingBoardFocus).not.toHaveBeenCalled()
        expect(state.syncRuleDerivedEdges).not.toHaveBeenCalled()
      })
    }
  }
})
