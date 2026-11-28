import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { ScriptTarget, transpileModule } from 'typescript'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { errorDetails, localizedErrorMessage } from '@/utils/userMessage'
import { REQUEST_LIMITS } from '@/constants/requestLimits'
import { defaultSpecTemplates } from '@/assets/config/specTemplates'
import { readBoardWriteReceipt as readWriteReceipt } from '@/utils/boardWriteReceipt'
import { BOARD_ITEM_EDIT_OUTCOME_UNKNOWN } from '@/composables/useUnconfirmedBoardItemEdit'

const source = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const extract = (start: string, end: string) => {
  const first = source.indexOf(start)
  const last = source.indexOf(end, first)
  if (first < 0 || last < 0) throw new Error(`Missing production save handler: ${start}`)
  return source.slice(first, last)
}
const handlers = extract('const isRuleDuplicateRejection =', 'const ruleRecommendationTargetTypes =')
  + extract('const isSpecificationDuplicateRejection =', 'const getNextNodePosition =')
  + extract('const readBoardWriteReceipt =', 'type AsyncTaskStatus =')

const setup = (error: unknown, recovered = false) => {
  const specifications = ref<any[]>([])
  const ownership = { token: 'session-A', active: true }
  const dependencies = {
    REQUEST_LIMITS, defaultSpecTemplates, errorDetails, readWriteReceipt, BOARD_ITEM_EDIT_OUTCOME_UNKNOWN,
    boardAuthScopeEpoch: 0, getToken: () => ownership.token,
    isCurrentSessionToken: (token: string) => token === ownership.token,
    isCurrentBoardAuthScope: () => ownership.active,
    ensurePlaybackClosedForMutation: () => true,
    enqueueBoardMutation: (work: () => Promise<unknown>) => work(),
    ensureBoardDataReady: () => true, ensureBoardItemCapacity: () => true,
    ensureNestedItemCapacity: () => true, assertRulesHaveTriggers: () => true,
    resolveNodeRef: () => ({}),
    boardApi: { addRule: vi.fn().mockRejectedValue(error), addSpec: vi.fn().mockRejectedValue(error),
      checkDuplicateRule: vi.fn(async () => ({ isDuplicate: recovered })) },
    rules: ref([]), nodes: ref([]), specifications,
    commitSemanticScene: vi.fn(), focusRuleOnCanvas: vi.fn(), focusSpecInInspector: vi.fn(),
    refreshBoardSnapshot: vi.fn(async () => { if (recovered) specifications.value = [{}]; return recovered }),
    specificationExists: () => specifications.value.length > 0 && recovered,
    isPollingAbortedError: () => false, deviceModeCount: () => undefined, buildSpecFormula: () => 'G off',
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
      : state.handleAddSpec({ name: 'Safety requirement', templateId: '1', aConditions: [], ifConditions: [], thenConditions: [], complete })

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
      expect(state.refreshBoardSnapshot).not.toHaveBeenCalled()
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
      expect(state.refreshBoardSnapshot).not.toHaveBeenCalled()
    })

    if (kind === 'rule') it('does not install a confirmed rule write after its account changes', async () => {
      const state = setup(undefined)
      const complete = vi.fn()
      let confirm!: (mutation: unknown) => void
      state.boardApi.addRule.mockReturnValueOnce(new Promise(resolve => { confirm = resolve }))
      const submitted = submit(state, complete)
      state.ownership.token = 'session-B'
      confirm({ currentItems: [{ id: '12' }], affectedItem: { id: '12' } })
      await submitted

      expect(state.commitSemanticScene).not.toHaveBeenCalled()
      expect(state.focusRuleOnCanvas).not.toHaveBeenCalled()
      expect(complete).toHaveBeenCalledExactlyOnceWith(false, undefined)
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
      if (kind === 'rule') {
        expect(confirmed).toHaveBeenCalledWith(false, expect.objectContaining({
          reasonCode: BOARD_ITEM_EDIT_OUTCOME_UNKNOWN, cause: 'app.ruleCreateOutcomeRefreshed'
        }))
        expect(recovered.boardApi.checkDuplicateRule).toHaveBeenCalledOnce()
      } else {
        expect(confirmed).toHaveBeenCalledWith(false, expect.objectContaining({
          reasonCode: 'BOARD_ITEM_EDIT_OUTCOME_UNKNOWN', cause: 'app.specCreateOutcomeRefreshed'
        }))
      }
      expect(recovered.notifyError).not.toHaveBeenCalled()
      expect(recovered.refreshBoardSnapshot).toHaveBeenCalledOnce()
    })
  }
})
