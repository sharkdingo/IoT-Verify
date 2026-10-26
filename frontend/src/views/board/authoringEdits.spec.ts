import { isSameSpecification } from '@/utils/spec'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { transpileModule, ScriptTarget } from 'typescript'
import { describe, expect, it, vi } from 'vitest'

const source = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const start = source.indexOf('const saveBoardItemEdit =')
const end = source.indexOf('const editRuleFromInspector =', start)
const body = start < 0 || end < 0 ? '' : source.slice(start, end)

const setup = (enqueue?: (work: () => Promise<unknown>) => Promise<unknown>) => {
  const ownership = { active: true, token: 'owner' }
  const dependencies = {
    isSameSpecification,
    getToken: () => ownership.token, boardAuthScopeEpoch: 0,
    isCurrentSessionToken: (token: string) => token === ownership.token,
    isCurrentBoardAuthScope: () => ownership.active,
    ensurePlaybackClosedForMutation: () => true,
    enqueueBoardMutation: enqueue || ((work: () => Promise<unknown>) => work()),
    ensureBoardDataReady: () => true,
    errorDetails: (error: unknown) => error,
    reloadUndoAvailability: vi.fn(), t: (key: string) => key,
    notifySuccess: vi.fn(),
    deepClone: (value: unknown) => structuredClone(value),
    boardApi: { updateRule: vi.fn(), updateSpec: vi.fn() },
    commitSemanticScene: vi.fn(), focusRuleOnCanvas: vi.fn(), focusSpecInInspector: vi.fn(),
    refreshRules: vi.fn(), refreshSpecifications: vi.fn(),
    handleAddRule: vi.fn(), handleAddSpec: vi.fn()
  }
  const handlers = new Function(...Object.keys(dependencies), transpileModule(body + '\nreturn { saveBoardItemEdit, handleSaveRule, handleSaveSpecification }', { compilerOptions: { target: ScriptTarget.ES2022 } }).outputText)(...Object.values(dependencies))
  const expected = { id: '7', value: 'before' }
  const desired = { id: '7', value: 'after' }
  const mutation = { operation: 'updated', affectedItem: desired, currentItems: [desired], currentCount: 1, canUndo: true, canRedo: false }
  const options = { expected, desired, requiredKeys: ['rules'], update: vi.fn().mockResolvedValue(mutation), commit: vi.fn(), focus: vi.fn(), refresh: vi.fn(), complete: vi.fn() }
  return { run: handlers.saveBoardItemEdit, handlers, options, mutation, dependencies, ownership }
}

describe('Board edit ownership and reconciliation', () => {
  it.each(['rule', 'spec'] as const)('routes a %s edit to its targeted update and reconciles the returned snapshot', async kind => {
    const state = setup()
    const api = kind === 'rule' ? state.dependencies.boardApi.updateRule : state.dependencies.boardApi.updateSpec
    api.mockResolvedValue(state.mutation)
    const request = { name: 'Safety requirement', expected: state.options.expected, rule: state.options.desired, templateId: '1', aConditions: [], ifConditions: [], thenConditions: [], complete: state.options.complete }
    if (kind === 'rule') await state.handlers.handleSaveRule(request)
    else await state.handlers.handleSaveSpecification(request)
    expect(api).toHaveBeenCalledWith(state.options.expected, expect.objectContaining({ id: '7' }))
    expect(state.dependencies.commitSemanticScene).toHaveBeenCalledWith({ [kind === 'rule' ? 'rules' : 'specs']: state.mutation.currentItems, availability: state.mutation, semanticChanged: true })
    expect(state.dependencies.handleAddRule).not.toHaveBeenCalled()
    expect(state.dependencies.handleAddSpec).not.toHaveBeenCalled()
  })

  it('sends the opened baseline, adopts the authoritative collection and undo availability', async () => {
    const state = setup()
    await state.run(state.options)
    expect(state.options.update).toHaveBeenCalledExactlyOnceWith(state.options.expected, state.options.desired)
    expect(state.options.commit).toHaveBeenCalledExactlyOnceWith(state.mutation)
    expect(state.options.focus).toHaveBeenCalledExactlyOnceWith('7')
    expect(state.options.complete).toHaveBeenCalledExactlyOnceWith(true, undefined)
  })

  it('keeps a stale edit rejected, refreshes authoritative data, and passes the failure to the draft', async () => {
    const state = setup()
    const conflict = { response: { status: 409 }, cause: 'The rule changed elsewhere' }
    state.options.update.mockRejectedValue(conflict)
    await state.run(state.options)
    expect(state.options.commit).not.toHaveBeenCalled()
    expect(state.options.refresh).toHaveBeenCalledExactlyOnceWith('owner')
    expect(state.options.complete).toHaveBeenCalledExactlyOnceWith(false, conflict)
  })

  it('holds the mutation queue through conflict refresh and undo reconciliation', async () => {
    const deferred = <T,>() => {
      let resolve!: (value: T) => void
      let reject!: (reason: unknown) => void
      const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
      return { promise, resolve, reject }
    }
    let queue: Promise<unknown> = Promise.resolve()
    const enqueue = (work: () => Promise<unknown>) => {
      const next = queue.then(work, work)
      queue = next.then(() => undefined, () => undefined)
      return next
    }
    const state = setup(enqueue)
    const updateStarted = deferred<void>()
    const update = deferred<unknown>()
    const refreshStarted = deferred<void>()
    const refresh = deferred<void>()
    const undoStarted = deferred<void>()
    const undo = deferred<void>()
    let visibleSnapshot = 'opened'
    let followingMutationStarted = false
    state.options.update.mockImplementation(() => { updateStarted.resolve(); return update.promise })
    state.options.refresh.mockImplementation(async () => {
      refreshStarted.resolve()
      await refresh.promise
      visibleSnapshot = 'recovered'
      return true
    })
    state.dependencies.reloadUndoAvailability.mockImplementation(async () => {
      undoStarted.resolve()
      await undo.promise
    })
    const editing = state.run(state.options)
    await updateStarted.promise
    const following = enqueue(async () => {
      followingMutationStarted = true
      visibleSnapshot = 'newer committed edit'
    })
    const conflict = { response: { status: 409 }, cause: 'Changed elsewhere' }
    update.reject(conflict)
    await refreshStarted.promise
    await new Promise<void>(resolve => setImmediate(resolve))
    expect.soft(followingMutationStarted).toBe(false)
    refresh.resolve()
    await undoStarted.promise
    await new Promise<void>(resolve => setImmediate(resolve))
    expect.soft(followingMutationStarted).toBe(false)
    undo.resolve()
    await Promise.all([editing, following])
    expect(visibleSnapshot).toBe('newer committed edit')
    expect(state.options.complete).toHaveBeenCalledExactlyOnceWith(false, conflict)
  })

  it.each([false, true])('ignores a retired account after the request settles (failed=%s)', async failed => {
    const state = setup()
    let settle!: (value: unknown) => void
    const pending = new Promise((resolve, reject) => { settle = failed ? reject : resolve })
    state.options.update.mockReturnValue(pending)
    const run = state.run(state.options)
    state.ownership.token = 'other-account'
    settle(failed ? new Error('late failure') : state.mutation)
    await run
    expect(state.options.commit).not.toHaveBeenCalled()
    expect(state.options.focus).not.toHaveBeenCalled()
    expect(state.options.refresh).not.toHaveBeenCalled()
    expect(state.options.complete).toHaveBeenCalledExactlyOnceWith(false, undefined)
  })
})
