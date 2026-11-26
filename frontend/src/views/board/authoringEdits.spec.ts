import { isSameSpecification } from '@/utils/spec'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { transpileModule, ScriptTarget } from 'typescript'
import { describe, expect, it, vi } from 'vitest'

const source = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const start = source.indexOf('const saveBoardItemEdit =')
const end = source.indexOf('const editRuleFromInspector =', start)
const body = start < 0 || end < 0 ? '' : source.slice(start, end)

const bindDeletion = (name: string, next: string, ports: Record<string, unknown>) => {
  const start = source.indexOf(`const ${name} =`)
  const body = source.slice(start, source.indexOf(`const ${next} =`, start))
  return new Function('ports', `with (ports) { ${transpileModule(body, {
    compilerOptions: { target: ScriptTarget.ES2022 }
  }).outputText}; return ${name} }`)(ports)
}

const setup = (enqueue?: (work: () => Promise<unknown>) => Promise<unknown>) => {
  const ownership = { active: true, token: 'owner' }
  const dependencies = {
    isSameSpecification,
    deviceModeCount: (id: string) => id === 'light' ? 1 : undefined,
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
  it.each([409, 503])('reconciles a rename HTTP %i from one complete Board snapshot', async status => {
    const node = { id: 'kept', label: 'Original' }
    const nodes = { value: [node] }
    const label = status === 409 ? 'Changed elsewhere' : 'Requested label'
    const renameDialogData = { node, originalLabel: node.label }
    const ports = {
      nodes, renameDialogData, renameDialogVisible: { value: true },
      ensurePlaybackClosedForMutation: () => true, ensureBoardDataReady: () => true,
      deviceLabelKey: (value: string) => value.toLowerCase(),
      enqueueBoardMutation: (work: () => Promise<unknown>) => work(),
      boardApi: { renameNode: vi.fn().mockRejectedValue({ response: { status } }) },
      isDefinitiveMutationRejection: (error: { response: { status: number } }) => error.response.status === 409,
      refreshSceneForReconciliation: vi.fn(async () => {
        nodes.value = [{ ...node, label }]
        return true
      }),
      refreshDevices: vi.fn(), refreshSpecifications: vi.fn(), refreshEnvironmentVariables: vi.fn(),
      reloadUndoAvailability: vi.fn(), notifyBlocked: vi.fn(), notifyError: vi.fn(),
      extractApiErrorMessage: () => 'Rename failed', t: (key: string) => key
    }
    const rename = bindDeletion('handleRenameDevice', 'handleDeviceRuntimeSave', ports)

    const result = await rename(node.id, 'Requested label', node.label)

    expect(ports.refreshSceneForReconciliation).toHaveBeenCalledOnce()
    if (status === 409) {
      expect(result).toBe(false)
      expect(renameDialogData.originalLabel).toBe(label)
    } else {
      expect(result).toEqual({ responseConfirmed: false, environmentChanges: [] })
    }
  })

  it('identifies the locally removed device when adopting a confirmed deletion snapshot', async () => {
    const snapshot = { nodes: [] }
    const ports = {
      ensureBoardDataReady: () => true,
      enqueueBoardMutation: (work: () => Promise<unknown>) => work(),
      bundledBoardEnvironmentNames: { value: [] },
      boardApi: { deleteNode: vi.fn().mockResolvedValue({ snapshot, environmentChanges: [] }) },
      adoptBoardSnapshot: vi.fn(), reportEnvironmentChanges: vi.fn()
    }
    const remove = bindDeletion('forceDeleteNode', 'deleteCurrentNodeWithConfirm', ports)

    expect(await remove('removed-here', 'reviewed-token')).toEqual({ responseConfirmed: true })
    expect(ports.adoptBoardSnapshot).toHaveBeenCalledExactlyOnceWith(snapshot, 'removed-here')
  })

  it('refreshes the full scene and its template catalog after a stale deletion confirmation', async () => {
    const node = { id: 'retained', label: 'Original device', template: 'original-template' }
    const otherNode = { id: 'added', label: 'New device', template: 'added-template' }
    const board = { nodes: [node], templates: ['original-template'] }
    const ports = {
      ensureBoardDataReady: () => true,
      enqueueBoardMutation: (work: () => Promise<unknown>) => work(),
      bundledBoardEnvironmentNames: { value: [] },
      boardApi: { deleteNode: vi.fn().mockRejectedValue({ response: { status: 409 } }) },
      extractApiErrorMessage: () => 'Conflict', t: (key: string) => key, notifyBlocked: vi.fn(),
      console: { error: vi.fn() },
      refreshDevices: () => { board.nodes = [{ ...node, label: 'Changed elsewhere' }, otherNode] },
      refreshEnvironmentVariables: vi.fn(), refreshRules: vi.fn(), refreshSpecifications: vi.fn(),
      reloadUndoAvailability: vi.fn(), refreshSceneForReconciliation: vi.fn(async () => {
        board.nodes = [{ ...node, label: 'Changed elsewhere' }, otherNode]
        board.templates = ['original-template', 'added-template']
        return true
      })
    }
    const remove = bindDeletion('forceDeleteNode', 'deleteCurrentNodeWithConfirm', ports)

    expect(await remove(node.id, 'expired-token')).toMatchObject({ responseConfirmed: false })
    expect(board.nodes.map(device => device.label)).toEqual(['Changed elsewhere', 'New device'])
    expect(board.templates).toEqual(['original-template', 'added-template'])
  })

  it('uses the deletion preview and Board snapshot from the same response', async () => {
    const node = { id: 'retained', label: 'Preview target', templateName: 'original-template' }
    const added = { id: 'added', label: 'New device', templateName: 'added-template' }
    const nodes = { value: [node] }
    const templates = { value: ['original-template'] }
    const snapshot = { nodes: [node, added], deviceTemplates: ['original-template', 'added-template'] }
    const dialog: Record<string, unknown> = {}
    const ports = {
      nodes, dialogVisible: { value: false }, deleteConfirmDialogData: dialog,
      deletePreviewLoading: { value: false }, deleteConfirmSubmitting: { value: false },
      deleteConfirmDialogVisible: { value: false }, deleteConfirmReviewSnapshotKey: { value: null },
      deletePreviewRequestEpoch: 0, deletePreviewNodeId: null, deleteDialogReturnFocusNodeId: null,
      deleteConfirmSourceDeviceDialogNodeId: null,
      ensurePlaybackClosedForMutation: () => true, ensureBoardDataReady: () => true,
      resolveCurrentBoardNode: (devices: typeof nodes.value, id: string) => devices.find(item => item.id === id),
      enqueueBoardMutation: (work: () => Promise<unknown>) => work(),
      boardApi: { previewNodeDeletion: vi.fn().mockResolvedValue({ deletedDevice: node, impactToken: 'reviewed',
        removedRules: [], removedSpecifications: [], environmentChanges: [], snapshot }) },
      refreshSceneForReconciliation: vi.fn().mockResolvedValue(true),
      adoptBoardSnapshot: vi.fn((value: typeof snapshot) => {
        nodes.value = value.nodes
        templates.value = value.deviceTemplates
      }),
      currentDeletionReviewSnapshotKey: () => 'key'
    }

    await bindDeletion('deleteCurrentNodeWithConfirm', 'handleDialogDelete', ports)(node.id)

    expect(dialog.impactToken).toBe('reviewed')
    expect(nodes.value).toEqual([node, added])
    expect(templates.value).toContain(added.templateName)
    expect(ports.adoptBoardSnapshot).toHaveBeenCalledExactlyOnceWith(snapshot)
    expect(ports.refreshSceneForReconciliation).not.toHaveBeenCalled()
  })

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

  it('keeps repair evidence current when a specification uses equivalent state-set operands', async () => {
    const { handlers, dependencies, mutation } = setup()
    const condition = { deviceId: 'light', targetType: 'state', key: 'state', relation: 'in', value: 'off;on' }
    const expected = { id: '7', name: 'Safety', templateId: '1', aConditions: [condition],
      ifConditions: [], thenConditions: [] }
    dependencies.boardApi.updateSpec.mockResolvedValue({ ...mutation, currentItems: [expected] })

    await handlers.handleSaveSpecification({ ...expected, expected, complete: vi.fn(),
      aConditions: [{ ...condition, value: 'on,off,off' }] })

    expect(dependencies.commitSemanticScene).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      specs: [expected], semanticChanged: false
    }))
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
