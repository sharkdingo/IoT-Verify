import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { computed, ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { createPagedRequestCoordinator } from '@/utils/pagedRequestCoordinator'
import { activeTaskProgressStage } from '@/utils/runResponse'
import { errorDetails, localizedErrorMessage, type ApiErrorDetails } from '@/utils/userMessage'
import { classifyTrackedFuzzRunError } from './fuzzingRecovery'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const setupStart = board.indexOf('<script setup')
const setup = board.slice(board.indexOf('>', setupStart) + 1, board.indexOf('</script>', setupStart))
const source = ts.createSourceFile('Board.ts', setup, ts.ScriptTarget.Latest, true)
const bind = (name: string, ports: Record<string, unknown>) => {
  const statement = source.statements.find(statement => ts.isVariableStatement(statement)
    && statement.declarationList.declarations.some(item => item.name.getText(source) === name))
  if (!statement) throw new Error(`Missing Board function ${name}`)
  const javascript = ts.transpileModule(statement.getText(source), {
    compilerOptions: { target: ts.ScriptTarget.ES2022 }
  }).outputText
  return new Function('ports', `with (ports) { ${javascript}; return ${name} }`)(ports)
}

const context = (locale = 'en') => {
  const ports = {
    boardLifecycleDisposed: false, taskInboxLoadingEpoch: 0,
    taskInboxRequests: createPagedRequestCoordinator(), loadingTaskHistory: ref(false),
    taskInboxError: ref<ApiErrorDetails | null>(null),
    verificationTasks: ref([{ id: 1 }]), simulationTasks: ref([{ id: 2 }]), fuzzingTasks: ref([{ id: 3 }]),
    fetchVerificationTasks: vi.fn().mockResolvedValue({ tasks: [{ id: 11 }], excludedIds: [] }),
    fetchSimulationTasks: vi.fn().mockResolvedValue({ tasks: [{ id: 12 }], excludedIds: [] }),
    fetchFuzzingTasks: vi.fn().mockResolvedValue({ tasks: [{ id: 13 }], excludedIds: [] }),
    reconcileTrackedFuzzTasks: vi.fn().mockResolvedValue(undefined),
    taskSummaryTimestamp: () => 0, notifyError: vi.fn(), t: (key: string) => key,
    errorDetails, extractApiErrorMessage: (error: Error, fallback: string) => localizedErrorMessage(error, fallback, locale),
    mergeTaskSummariesPreservingExcluded: null as any
  }
  ports.mergeTaskSummariesPreservingExcluded = bind('mergeTaskSummariesPreservingExcluded', ports)
  return { ports, load: bind('loadTaskInbox', ports) }
}

const deferred = () => {
  let resolve!: (value?: any) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<any>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

const openingContext = () => {
  const state = context()
  const ports = Object.assign(state.ports, {
    loadTaskInbox: state.load, taskInboxBackgroundRefreshPromise: null as Promise<boolean> | null,
    isModelPlaybackActive: ref(false), isSceneReplacementInProgress: ref(false), historyPanelIntentEpoch: 0,
    pendingHistoryRunTarget: null,
    historyDetailRequests: { invalidate: vi.fn() }, closeResultSurfaces: vi.fn(),
    activeWorkflowPanel: ref<{ kind: 'history' } | null>(null), computed,
    activeHistoryLayer: ref<'tasks' | 'results'>('results'),
    unreadFuzzNotifications: ref([{ taskId: 7, kind: 'FAILED' as const }]),
    persistFuzzNotificationState: vi.fn(), notifyBlocked: vi.fn(),
    refreshCurrentBoardModelFingerprints: vi.fn().mockResolvedValue(undefined),
    isAnyRecommendationRunning: vi.fn<() => boolean>().mockReturnValue(false),
    showHistoryPanel: null as any, isCurrentHistoryPanelIntent: null as any
  })
  for (const name of ['showHistoryPanel', 'clearFuzzNotifications', 'isCurrentHistoryPanelIntent', 'refreshHistoryLayer',
    'setHistoryLayer', 'retireHistoryPanelIntent', 'closeWorkflowPanel', 'closeOtherWorkflowSurfaces',
    'canOpenWorkflowPanel', 'openHistoryPanel']) {
    Object.assign(ports, { [name]: bind(name, ports) })
  }
  return { ports, open: bind('openTaskInbox', ports),
    refreshInBackground: bind('refreshTaskInboxInBackground', ports) }
}

describe('task inbox read failures', () => {
  it('keeps a confirmed cancellation despite a late inbox cancel error and still reports unknown failures', async () => {
    const state = context()
    const response = deferred()
    const ports = Object.assign(state.ports, {
      verificationTasks: ref([{ id: 1, status: 'RUNNING' }, { id: 11, status: 'RUNNING' }]),
      pendingTaskActionKeys: ref(new Set<string>()), watchedTaskId: () => undefined,
      showHistoryPanel: ref(false), loadTaskInbox: state.load,
      asyncVerificationTask: ref({ taskId: null, statusKey: 'app.taskInitializing' }),
      boardApi: { cancelTask: vi.fn().mockReturnValueOnce(response.promise)
        .mockRejectedValueOnce(new Error('Unconfirmed cancellation')) }
    })
    for (const name of ['taskActionKey', 'withTaskActionLock', 'hasCancelledTaskRow',
      'isWatchedTaskCancelled', 'isTaskCancellationKnown']) {
      Object.assign(ports, { [name]: bind(name, ports) })
    }
    const cancel = bind('cancelVerificationTaskFromInbox', ports)
    const cancelling = cancel(1)
    ports.fetchVerificationTasks.mockResolvedValue({
      tasks: [{ id: 1, status: 'CANCELLED' }, { id: 11, status: 'RUNNING' }], excludedIds: []
    })
    expect(await state.load()).toBe(true)

    response.reject(new Error('Cancel response failed'))
    await cancelling
    expect(ports.notifyError).not.toHaveBeenCalled()
    expect(ports.verificationTasks.value[0]?.status).toBe('CANCELLED')

    await cancel(11)
    expect(ports.notifyError).toHaveBeenCalledExactlyOnceWith('app.failedToCancelVerificationTask')
    expect(ports.fetchVerificationTasks).toHaveBeenCalledTimes(3)
  })

  it('keeps a confirmed cancellation visible while an unrelated inbox read fails', async () => {
    const state = context()
    const refresh = deferred()
    const receipt = deferred()
    const watching = ref({ taskId: null as number | null, statusKey: 'app.taskStatusRunning' })
    const ports = Object.assign(state.ports, {
      verificationTasks: ref([{ id: 1, status: 'RUNNING', progress: 35, progressStage: 'GENERATING_MODEL' }]),
      pendingTaskActionKeys: ref(new Set<string>()), watchedTaskId: () => watching.value.taskId ?? undefined,
      asyncVerificationTask: watching, verificationCancelRequested: ref(false),
      activeTaskProgressStage,
      tracksRun: () => watching.value.taskId !== null, showHistoryPanel: ref(true), activeHistoryLayer: ref('tasks'),
      notifyInfo: vi.fn(), loadTaskInbox: state.load, taskInboxBackgroundRefreshPromise: null,
      boardApi: { cancelTask: vi.fn().mockReturnValue(receipt.promise) }
    })
    for (const name of ['taskActionKey', 'withTaskActionLock', 'invalidateTaskInboxRequests',
      'upsertVerificationTaskSummary', 'taskProgressStatusKey', 'applyTaskCancellationReceipt',
      'hasCancelledTaskRow', 'hasVisibleCancelledTaskRow', 'notifyTaskCancellationResult']) {
      Object.assign(ports, { [name]: bind(name, ports) })
    }
    ports.fetchSimulationTasks.mockReturnValue(refresh.promise)
    const cancelling = bind('cancelVerificationTaskFromInbox', ports)(1)
    watching.value.taskId = 1
    ports.showHistoryPanel.value = false
    receipt.resolve({ taskId: 1, taskStatus: 'CANCELLED', cancellationOutcome: 'ACCEPTED',
      cancellationAccepted: true, executionMayStillBeStopping: true })
    await vi.waitFor(() => expect(ports.fetchSimulationTasks).toHaveBeenCalledOnce())
    const whileRefreshing = ports.verificationTasks.value[0]
    refresh.reject(new Error('Simulation inbox failed'))
    await cancelling

    expect(whileRefreshing).toEqual({ id: 1, status: 'CANCELLED', progress: 35, progressStage: undefined })
    expect(watching.value.statusKey).toBe('app.taskStatusCancelled')
    expect(ports.verificationCancelRequested.value).toBe(true)
    expect(ports.verificationTasks.value[0]).toEqual(whileRefreshing)
    expect(ports.taskInboxError.value?.cause).toBe('Simulation inbox failed')
    expect(ports.notifyInfo).not.toHaveBeenCalled()
  })

  it.each(['task status', 'completed result'])('distinguishes a missing %s when reconciling tracked exploration', async missing => {
    const absent = { response: { status: 404 } }
    const ports = {
      trackedFuzzTaskIds: ref([7]), classifyTrackedFuzzRunError, trackFuzzTask: vi.fn(),
      untrackFuzzTask: vi.fn(), canAttemptFuzzRunRecovery: () => true,
      fuzzingApi: { getTask: vi.fn().mockRejectedValue(absent) },
      loadFuzzRunSingleFlight: vi.fn().mockRejectedValue(absent), isActiveTaskStatus: () => false,
      upsertFuzzingRunSummary: vi.fn(), markFuzzNotificationUnread: vi.fn(), notifyError: vi.fn(),
      t: (key: string) => key
    }
    const reconcile = bind('reconcileTrackedFuzzTasks', ports)
    const tasks = missing === 'task status' ? [] : [{ id: 7, status: 'COMPLETED', runId: 7 }]
    await reconcile(tasks, [], () => true)

    if (missing === 'task status') {
      expect(ports.untrackFuzzTask).toHaveBeenCalledExactlyOnceWith(7)
      expect(ports.loadFuzzRunSingleFlight).not.toHaveBeenCalled()
      expect(ports.upsertFuzzingRunSummary).not.toHaveBeenCalled()
      expect(ports.markFuzzNotificationUnread).not.toHaveBeenCalled()
      expect(ports.notifyError).not.toHaveBeenCalled()
    } else {
      expect(ports.upsertFuzzingRunSummary).toHaveBeenCalledWith(expect.objectContaining({ id: 7, dataAvailable: false }))
      expect(ports.markFuzzNotificationUnread).toHaveBeenCalledWith(expect.objectContaining({ taskId: 7, kind: 'UNAVAILABLE' }))
    }
  })

  it.each(['failure', 'before-reconcile', 'during-reconcile'] as const)(
    'does not acknowledge unread failures after a superseded foreground read settles: %s', async phase => {
      const { ports, open, refreshInBackground } = openingContext()
      const olderRead = deferred()
      const newerRead = deferred()
      const reconciliation = deferred()
      ports.fetchVerificationTasks.mockReturnValueOnce(olderRead.promise).mockReturnValueOnce(newerRead.promise)
      if (phase === 'during-reconcile') ports.reconcileTrackedFuzzTasks.mockReturnValueOnce(reconciliation.promise)
      const opening = open()
      if (phase === 'during-reconcile') {
        olderRead.resolve({ tasks: [{ id: 21 }], excludedIds: [] })
        await vi.waitFor(() => expect(ports.reconcileTrackedFuzzTasks).toHaveBeenCalledOnce())
      }
      const background = refreshInBackground()
      newerRead.reject(new Error('Latest task read failed'))
      expect(await background).toBe(false)
      if (phase === 'failure') olderRead.reject(new Error('Older task read failed'))
      else if (phase === 'before-reconcile') olderRead.resolve({ tasks: [{ id: 21 }], excludedIds: [] })
      else reconciliation.resolve()
      await opening

      expect(ports.isCurrentHistoryPanelIntent(1, 'tasks')).toBe(true)
      expect(ports.taskInboxError.value).toMatchObject({ cause: 'Latest task read failed' })
      expect(ports.unreadFuzzNotifications.value).toEqual([{ taskId: 7, kind: 'FAILED' }])
      expect(ports.persistFuzzNotificationState).not.toHaveBeenCalled()
    })

  it('acknowledges unread failures after the current successful read opens the task inbox', async () => {
    const { ports, open } = openingContext()
    await open()
    expect(ports.showHistoryPanel.value).toBe(true)
    expect(ports.unreadFuzzNotifications.value).toEqual([])
    expect(ports.persistFuzzNotificationState).toHaveBeenCalledOnce()
    expect(ports.taskInboxError.value).toBeNull()
  })

  it('opens Task Status directly while recommendations are still generating', async () => {
    const { ports, open } = openingContext()
    ports.isAnyRecommendationRunning.mockReturnValue(true)
    await open()
    expect(ports.showHistoryPanel.value).toBe(true)
    expect(ports.activeHistoryLayer.value).toBe('tasks')
    expect(ports.notifyBlocked).not.toHaveBeenCalled()
  })

  it('keeps known rows and a persistent retry reason instead of only a toast', async () => {
    const { ports, load } = context()
    ports.fetchSimulationTasks.mockRejectedValue(new Error('Request timed out'))
    expect(await load()).toBe(false)
    expect(ports.taskInboxError.value).toMatchObject({ cause: 'Request timed out' })
    expect(ports.taskInboxError.value).not.toHaveProperty('message')
    expect(ports.verificationTasks.value).toEqual([{ id: 1 }])
    expect(ports.simulationTasks.value).toEqual([{ id: 2 }])
    expect(ports.fuzzingTasks.value).toEqual([{ id: 3 }])
    expect(ports.loadingTaskHistory.value).toBe(false)
    expect(ports.notifyError).not.toHaveBeenCalled()
  })

  it('clears the old failure only after a successful authoritative read, preserving excluded tasks', async () => {
    const { ports, load } = context()
    ports.taskInboxError.value = { cause: 'Previous failure', details: [], fields: {} }
    ports.fetchVerificationTasks.mockResolvedValue({ tasks: [{ id: 11 }], excludedIds: [1] })
    expect(await load()).toBe(true)
    expect(ports.taskInboxError.value).toBeNull()
    expect(ports.verificationTasks.value).toEqual([{ id: 1 }, { id: 11 }])
    expect(ports.simulationTasks.value).toEqual([{ id: 12 }])
    expect(ports.fuzzingTasks.value).toEqual([{ id: 13 }])
  })

  it('does not let an older failure overwrite a newer successful read', async () => {
    const { ports, load } = context()
    let reject!: (error: Error) => void
    ports.fetchVerificationTasks.mockImplementationOnce(() => new Promise((_, fail) => { reject = fail }))
    const old = load()
    expect(await load()).toBe(true)
    reject(new Error('Old request failed'))
    const oldResult = await old
    expect(ports.taskInboxError.value).toBeNull()
    expect(ports.verificationTasks.value).toEqual([{ id: 11 }])
    expect(ports.notifyError).not.toHaveBeenCalled()
    expect(oldResult).toBe(false)
  })

  it('records background failures without changing loading ownership or notifying twice', async () => {
    const { ports, load } = context()
    ports.fetchFuzzingTasks.mockRejectedValue(new Error('Background read failed'))
    expect(await load({ showLoading: false })).toBe(false)
    expect(ports.taskInboxError.value).toMatchObject({ cause: 'Background read failed' })
    expect(ports.loadingTaskHistory.value).toBe(false)
    expect(ports.notifyError).not.toHaveBeenCalled()
  })

  it('preserves the server cause and fields when Chinese UI copy falls back', async () => {
    const { ports, load } = context('zh-CN')
    ports.fetchVerificationTasks.mockRejectedValue({ response: { status: 503, data: {
      message: 'Task storage could not be read', data: { errors: { tasks: 'Database query timed out' } }
    } } })
    expect(await load()).toBe(false)
    expect(ports.taskInboxError.value).toMatchObject({
      cause: 'Task storage could not be read',
      fields: { tasks: 'Database query timed out' }, details: expect.arrayContaining(['HTTP 503'])
    })
  })
})
