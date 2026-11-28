import { buildSpecificationSemanticKey, isSameSpecification } from '@/utils/spec'
import { BOARD_ITEM_EDIT_OUTCOME_UNKNOWN } from '@/composables/useUnconfirmedBoardItemEdit'
import { getConfirmedBoardItemStatus } from '@/views/Board.vue'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { transpileModule, ScriptTarget } from 'typescript'
import { describe, expect, it, vi } from 'vitest'
import { computed, ref, watch } from 'vue'
import { readBoardWriteReceipt as readWriteReceipt } from '@/utils/boardWriteReceipt'

const source = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const start = source.indexOf('const saveBoardItemEdit =')
const end = source.indexOf('const editRuleFromInspector =', start)
const body = start < 0 || end < 0 ? '' : source.slice(start, end)

const bindDeletion = (name: string, next: string, ports: Record<string, unknown>, prefix = '') => {
  const start = source.indexOf(`const ${name} =`)
  const body = source.slice(start, source.indexOf(`const ${next} =`, start))
  return new Function('ports', `with (ports) { ${transpileModule(prefix + body, {
    compilerOptions: { target: ScriptTarget.ES2022 }
  }).outputText}; return ${name} }`)(ports)
}

const writeReceiptSource = source.slice(source.indexOf('const readBoardWriteReceipt ='), source.indexOf('type AsyncTaskStatus ='))
const deletionPorts = {
  readWriteReceipt,
  getToken: () => 'owner', isCurrentSessionToken: () => true, boardAuthScopeEpoch: 0, isCurrentBoardAuthScope: () => true,
  deleteConfirmReviewSnapshotKey: { value: 'reviewed-scene' }, currentDeletionReviewSnapshotKey: () => 'reviewed-scene',
  ensureBoardDataReady: () => true, enqueueBoardMutation: (work: () => Promise<unknown>) => work(),
  bundledBoardEnvironmentNames: { value: [] }
}

const bindBoardItemDeletion = (ports: Record<string, unknown>) => {
  const deletion = source.slice(source.indexOf('const normalizeConfirmationText ='), source.indexOf('const ruleExecutionOrder ='))
  return new Function('ports', `with (ports) { ${transpileModule(writeReceiptSource + deletion, {
    compilerOptions: { target: ScriptTarget.ES2022 }
  }).outputText}; return { deleteRule, deleteSpecification, warning: boardItemDeletionWarning } }`)({
    ...deletionPorts, boardSceneGeneration: 0, isSceneReplacementInProgress: { value: false },
    ensurePlaybackClosedForMutation: () => true, getConfirmedBoardItemStatus, buildSpecificationSemanticKey,
    reactive: (value: object) => value, isPollingAbortedError: () => false,
    t: (key: string) => key, deepClone: (value: unknown) => structuredClone(value),
    ...ports
  })
}

const setupRuleReorder = () => {
  const rules = ref([{ id: '1' }, { id: '2' }, { id: '3' }])
  const ports = {
    ...deletionPorts, rules, rulesReordering: ref(false), computed, ref, watch,
    boardSceneGeneration: 0, ensurePlaybackClosedForMutation: () => true,
    boardApi: { reorderRules: vi.fn() }, commitSemanticScene: vi.fn(), focusHighlight: { show: vi.fn() },
    refreshBoardSnapshot: vi.fn().mockResolvedValue(true), t: (key: string) => key,
    notifyBlocked: vi.fn(), notifySuccess: vi.fn(), notifyError: vi.fn(),
    isPollingAbortedError: () => false
  }
  const start = source.indexOf('const ruleExecutionOrder =')
  const reorder = source.slice(start, source.indexOf('watch(() => [authState.token', start))
  const move = new Function('ports', `with (ports) { ${transpileModule(writeReceiptSource + reorder, {
    compilerOptions: { target: ScriptTarget.ES2022 }
  }).outputText}; return moveRule }`)(ports)
  return { ...ports, move, queue: ports }
}

const setup = (enqueue?: (work: () => Promise<unknown>) => Promise<unknown>) => {
  const ownership = { active: true, token: 'owner' }
  const dependencies = {
    isSameSpecification, BOARD_ITEM_EDIT_OUTCOME_UNKNOWN, readWriteReceipt,
    deviceModeCount: (id: string) => id === 'light' ? 1 : undefined,
    getToken: () => ownership.token, boardAuthScopeEpoch: 0,
    isCurrentSessionToken: (token: string) => token === ownership.token,
    isCurrentBoardAuthScope: () => ownership.active,
    ensurePlaybackClosedForMutation: () => true,
    enqueueBoardMutation: enqueue || ((work: () => Promise<unknown>) => work()),
    ensureBoardDataReady: () => true,
    errorDetails: (error: unknown) => error,
    refreshBoardSnapshot: vi.fn(), t: (key: string) => key,
    notifySuccess: vi.fn(),
    deepClone: (value: unknown) => structuredClone(value),
    boardApi: { updateRule: vi.fn(), updateSpec: vi.fn() },
    commitSemanticScene: vi.fn(), focusRuleOnCanvas: vi.fn(), focusSpecInInspector: vi.fn(),
    handleAddRule: vi.fn(), handleAddSpec: vi.fn()
  }
  const handlers = new Function(...Object.keys(dependencies), transpileModule(writeReceiptSource + body + '\nreturn { saveBoardItemEdit, handleSaveRule, handleSaveSpecification }', { compilerOptions: { target: ScriptTarget.ES2022 } }).outputText)(...Object.values(dependencies))
  const expected = { id: '7', value: 'before' }
  const desired = { id: '7', value: 'after' }
  const mutation = { operation: 'updated', affectedItem: desired, currentItems: [desired], currentCount: 1, canUndo: true, canRedo: false }
  const options = { expected, desired, requiredKeys: ['rules'], update: vi.fn().mockResolvedValue(mutation), commit: vi.fn(), focus: vi.fn(), complete: vi.fn() }
  return { run: handlers.saveBoardItemEdit, handlers, options, mutation, dependencies, ownership }
}

describe('Board edit ownership and reconciliation', () => {
  it('does not resend an unconfirmed swap through either rule when recovery still shows the original order', async () => {
    const { move, boardApi, refreshBoardSnapshot, notifySuccess, notifyError } = setupRuleReorder()
    boardApi.reorderRules.mockRejectedValue(new Error('Lost response'))

    await move('1', 'down')
    await move('2', 'up')

    expect(boardApi.reorderRules).toHaveBeenCalledExactlyOnceWith(['1', '2', '3'], ['2', '1', '3'])
    expect(refreshBoardSnapshot).toHaveBeenCalledOnce()
    expect(notifySuccess).not.toHaveBeenCalled()
    expect(notifyError).not.toHaveBeenCalled()
  })

  it('expires a move if the displayed rule order changes while the move is queued', async () => {
    const { move, boardApi, rules, queue, notifyBlocked } = setupRuleReorder()
    boardApi.reorderRules.mockResolvedValue({})
    queue.enqueueBoardMutation = async work => {
      rules.value = [rules.value[0], rules.value[2], rules.value[1]]
      return work()
    }

    await move('1', 'down')

    expect(boardApi.reorderRules).not.toHaveBeenCalled()
    expect(notifyBlocked).toHaveBeenCalledExactlyOnceWith('app.ruleOrderChanged')
  })

  it('blocks another confirmed deletion of the same item after a lost response leaves it on the board', async () => {
    const item = { id: '7', name: 'Retained rule' }
    const ports = {
      rules: { value: [item] }, confirmDestructive: vi.fn().mockResolvedValue(true),
      boardApi: { removeRule: vi.fn().mockRejectedValue(new Error('Lost response')) },
      refreshBoardSnapshot: vi.fn().mockResolvedValue(true),
      notifyBlocked: vi.fn(), notifySuccess: vi.fn(), notifyError: vi.fn()
    }
    const { deleteRule, warning } = bindBoardItemDeletion(ports)
    await deleteRule(item.id)
    expect(warning('rule', item.id)).toBe('app.boardItemDeleteStillPresent')
    await deleteRule(item.id)
    expect(ports.boardApi.removeRule).toHaveBeenCalledExactlyOnceWith(item)
    expect(ports.refreshBoardSnapshot).toHaveBeenCalledOnce()
    expect(ports.notifySuccess).not.toHaveBeenCalled()
    expect(ports.notifyError).not.toHaveBeenCalled()
  })

  it('expires a queued specification deletion if only the variable source changes after confirmation', async () => {
    const condition = { targetType: 'variable', deviceId: 'light', key: 'level', relation: '=', value: '20' }
    const item = { id: 's7', name: 'Requirement', templateId: '1', aConditions: [{ ...condition, variableSource: 'reported' }] }
    const specifications = { value: [item] }
    const ports = {
      specifications, confirmDestructive: vi.fn().mockResolvedValue(true),
      enqueueBoardMutation: async (work: () => Promise<unknown>) => {
        specifications.value = [{ ...item, aConditions: [{ ...condition, variableSource: 'environment' }] }]
        return work()
      },
      boardApi: { removeSpec: vi.fn().mockRejectedValue({ response: { status: 409 } }) },
      refreshBoardSnapshot: vi.fn().mockResolvedValue(true), notifyBlocked: vi.fn()
    }
    await bindBoardItemDeletion(ports).deleteSpecification(item.id)
    expect(ports.confirmDestructive).toHaveBeenCalledOnce()
    expect(ports.boardApi.removeSpec).not.toHaveBeenCalled()
    expect(ports.notifyBlocked).toHaveBeenCalledExactlyOnceWith('app.specificationChangedBeforeDelete')
  })

  it.each([409, 503])('reconciles a rename HTTP %i from one complete Board snapshot', async status => {
    const node = { id: 'kept', label: 'Original' }
    const nodes = { value: [node] }
    const label = status === 409 ? 'Changed elsewhere' : 'Requested label'
    const renameDialogData = { node, originalLabel: node.label, newName: 'Requested label' }
    const ports = {
      readWriteReceipt, nodes, renameDialogData, renameDialogVisible: { value: true }, renameDialogSubmitting: { value: false },
      renameNameBlockedReason: { value: '' }, unconfirmedRenames: new Map(),
      getToken: () => 'owner', boardAuthScopeEpoch: 0, isCurrentSessionToken: () => true, isCurrentBoardAuthScope: () => true,
      ensurePlaybackClosedForMutation: () => true, ensureBoardDataReady: () => true,
      deviceLabelKey: (value: string) => value.toLowerCase(),
      enqueueBoardMutation: (work: () => Promise<unknown>) => work(),
      boardApi: { renameNode: vi.fn().mockRejectedValue({ response: { status } }) },
      refreshBoardSnapshot: vi.fn(async () => {
        nodes.value = [{ ...node, label }]
        return true
      }),
      notifyBlocked: vi.fn(), notifyError: vi.fn(),
      reconcileRenameDialogWithBoard: vi.fn(), extractApiErrorMessage: () => 'Rename failed', t: (key: string) => key
    }
    const between = (first: string, last: string) => source.slice(source.indexOf(first), source.indexOf(last, source.indexOf(first)))
    const rename = writeReceiptSource
      + between('const isDeviceRenameConflict =', 'const handleDeviceRuntimeSave =')
      + between('const confirmRename =', 'const isRenameDialogOpen =')
    const confirm = new Function('ports', `with (ports) { ${transpileModule(rename, {
      compilerOptions: { target: ScriptTarget.ES2022 }
    }).outputText}; return confirmRename }`)(ports)

    await confirm()

    expect(ports.refreshBoardSnapshot).toHaveBeenCalledOnce()
    expect(ports.renameDialogVisible.value).toBe(true)
    expect(renameDialogData.newName).toBe('Requested label')
    expect(renameDialogData.originalLabel).toBe(label)
    if (status === 409) {
      expect(ports.notifyBlocked).toHaveBeenCalledWith('app.deviceRenameConflictRefreshed')
      expect(ports.unconfirmedRenames.size).toBe(0)
    } else {
      expect(ports.unconfirmedRenames.get(node.id)).toEqual({ name: label, message: 'app.deviceRenameOutcomeRefreshed' })
    }
  })

  it.each([true, false])('dispatches and adopts a local deletion only while the review matches (%s)', async matches => {
    const snapshot = { nodes: [] }
    const ports = {
      ...deletionPorts, currentDeletionReviewSnapshotKey: () => matches ? 'reviewed-scene' : 'changed-scene',
      boardApi: { deleteNode: vi.fn().mockResolvedValue({ snapshot, environmentChanges: [] }) },
      adoptBoardSnapshot: vi.fn(), reportEnvironmentChanges: vi.fn()
    }
    const remove = bindDeletion('forceDeleteNode', 'deleteCurrentNodeWithConfirm', ports, writeReceiptSource)

    const result = await remove('removed-here', 'reviewed-token', 'reviewed-scene')
    if (matches) {
      expect(result).toMatchObject({ receipt: { status: 'confirmed' } })
      expect(ports.adoptBoardSnapshot).toHaveBeenCalledExactlyOnceWith(snapshot, 'removed-here')
    } else {
      expect(result).toBeNull()
      expect(ports.boardApi.deleteNode).not.toHaveBeenCalled()
      expect(ports.adoptBoardSnapshot).not.toHaveBeenCalled()
    }
  })

  it('keeps an unconfirmed deletion with its reviewed impact when the device remains, and blocks a second submit', async () => {
    const node = { id: 'retained', label: 'Original device' }
    const unconfirmedDeviceDeletions = new Map<string, string>()
    const ports = {
      ...deletionPorts,
      nodes: { value: [node] }, unconfirmedDeviceDeletions,
      deleteConfirmDialogVisible: { value: true }, deleteConfirmSubmitting: { value: false },
      deletePreviewLoading: { value: false },
      deleteConfirmDialogData: { node, impactToken: 'reviewed-impact', relatedRules: [], relatedSpecs: [], environmentChanges: [] },
      deleteUnconfirmedMessage: { get value() { return unconfirmedDeviceDeletions.get('reviewed-impact') } },
      ensurePlaybackClosedForMutation: () => true, invalidateDeletePreview: vi.fn(),
      boardApi: { deleteNode: vi.fn().mockRejectedValue(new Error('Lost response')) },
      refreshBoardSnapshot: vi.fn().mockResolvedValue(true),
      clearDeleteConfirmDialog: vi.fn(), notifySuccess: vi.fn(),
      t: (key: string) => key
    }
    const forceDeleteNode = bindDeletion('forceDeleteNode', 'deleteCurrentNodeWithConfirm', ports, writeReceiptSource)
    const confirm = bindDeletion('confirmDelete', 'cancelDelete', { ...ports, forceDeleteNode })
    await confirm()
    expect(ports.clearDeleteConfirmDialog).not.toHaveBeenCalled()
    expect(ports.notifySuccess).not.toHaveBeenCalled()
    expect(unconfirmedDeviceDeletions.get('reviewed-impact')).toBe('app.deviceDeleteOutcomeUnconfirmedAfterRefresh')
    await confirm()
    expect(ports.boardApi.deleteNode).toHaveBeenCalledOnce()
    expect(ports.refreshBoardSnapshot).toHaveBeenCalledOnce()
  })

  it('refreshes the full scene and its template catalog after a stale deletion confirmation', async () => {
    const node = { id: 'retained', label: 'Original device', template: 'original-template' }
    const otherNode = { id: 'added', label: 'New device', template: 'added-template' }
    const board = { nodes: [node], templates: ['original-template'] }
    const ports = {
      ...deletionPorts, nodes: { get value() { return board.nodes } },
      boardApi: { deleteNode: vi.fn().mockRejectedValue({ response: { status: 409 } }) },
      refreshBoardSnapshot: vi.fn(async () => {
        board.nodes = [{ ...node, label: 'Changed elsewhere' }, otherNode]
        board.templates = ['original-template', 'added-template']
        return true
      })
    }
    const remove = bindDeletion('forceDeleteNode', 'deleteCurrentNodeWithConfirm', ports, writeReceiptSource)

    expect(await remove(node.id, 'expired-token', 'reviewed-scene')).toMatchObject({
      receipt: { status: 'rejected', snapshotRefreshed: true }, targetMissing: false
    })
    expect(ports.refreshBoardSnapshot).toHaveBeenCalledOnce()
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
      emptyDeleteConfirmDialogData: bindDeletion('emptyDeleteConfirmDialogData', 'deleteConfirmDialogData', {}),
      deletePreviewLoading: { value: false }, deleteConfirmSubmitting: { value: false },
      deleteConfirmDialogVisible: { value: false }, deleteConfirmReviewSnapshotKey: { value: null },
      deletePreviewRequestEpoch: 0, deletePreviewNodeId: null, deleteDialogReturnFocusNodeId: null,
      deleteConfirmSourceDeviceDialogNodeId: null,
      ensurePlaybackClosedForMutation: () => true, ensureBoardDataReady: () => true,
      resolveCurrentBoardNode: (devices: typeof nodes.value, id: string) => devices.find(item => item.id === id),
      enqueueBoardMutation: (work: () => Promise<unknown>) => work(),
      boardApi: { previewNodeDeletion: vi.fn().mockResolvedValue({ deletedDevice: node, impactToken: 'reviewed',
        removedRules: [], removedSpecifications: [], environmentChanges: [], snapshot }) },
      refreshBoardSnapshot: vi.fn().mockResolvedValue(true),
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
    expect(ports.refreshBoardSnapshot).not.toHaveBeenCalled()
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
    expect(state.dependencies.refreshBoardSnapshot).toHaveBeenCalledOnce()
    expect(state.options.complete).toHaveBeenCalledExactlyOnceWith(false, conflict)
  })

  it('returns an unconfirmed edit after recovering the board without committing a success receipt', async () => {
    const state = setup()
    state.options.update.mockRejectedValue(new Error('Lost response'))
    state.dependencies.refreshBoardSnapshot.mockResolvedValue(true)
    await state.run(state.options)
    expect(state.options.update).toHaveBeenCalledOnce()
    expect(state.dependencies.refreshBoardSnapshot).toHaveBeenCalledOnce()
    expect(state.options.commit).not.toHaveBeenCalled()
    expect(state.options.focus).not.toHaveBeenCalled()
    expect(state.options.complete).toHaveBeenCalledExactlyOnceWith(false, expect.objectContaining({
      reasonCode: 'BOARD_ITEM_EDIT_OUTCOME_UNKNOWN', cause: 'app.boardItemEditOutcomeRefreshed'
    }))
  })

  it('holds the mutation queue through the conflict snapshot refresh', async () => {
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
    let visibleSnapshot = 'opened'
    let followingMutationStarted = false
    state.options.update.mockImplementation(() => { updateStarted.resolve(); return update.promise })
    state.dependencies.refreshBoardSnapshot.mockImplementation(async () => {
      refreshStarted.resolve()
      await refresh.promise
      visibleSnapshot = 'recovered'
      return true
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
    expect(state.dependencies.refreshBoardSnapshot).not.toHaveBeenCalled()
    expect(state.options.complete).toHaveBeenCalledExactlyOnceWith(false, undefined)
  })
})
