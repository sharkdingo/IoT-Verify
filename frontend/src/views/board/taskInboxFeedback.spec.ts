import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { createPagedRequestCoordinator } from '@/utils/pagedRequestCoordinator'
import { errorDetails, localizedErrorMessage, type ApiErrorDetails } from '@/utils/userMessage'

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
    isModelPlaybackActive: ref(false), historyPanelIntentEpoch: 0,
    historyDetailRequests: { invalidate: vi.fn() }, closeOtherWorkflowSurfaces: vi.fn(),
    activeHistoryLayer: ref<'tasks' | 'results'>('results'), showHistoryPanel: ref(false),
    unreadFuzzNotifications: ref([{ taskId: 7, kind: 'FAILED' as const }]),
    persistFuzzNotificationState: vi.fn(), notifyBlocked: vi.fn(),
    clearFuzzNotifications: null as any, isCurrentHistoryPanelIntent: null as any
  })
  ports.clearFuzzNotifications = bind('clearFuzzNotifications', ports)
  ports.isCurrentHistoryPanelIntent = bind('isCurrentHistoryPanelIntent', ports)
  return { ports, open: bind('openTaskInbox', ports),
    refreshInBackground: bind('refreshTaskInboxInBackground', ports) }
}

describe('task inbox read failures', () => {
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
