import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { computed, defineComponent, effectScope, nextTick, reactive, ref, watch } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/assets/i18n'
import { createPagedRequestCoordinator } from '@/utils/pagedRequestCoordinator'
import { errorDetails, localizedErrorMessage } from '@/utils/userMessage'
import FailureDetails from '@/components/FailureDetails.vue'
import type { BoardLayoutDto } from '@/types/canvas'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const start = board.indexOf('<script setup')
const setup = board.slice(board.indexOf('>', start) + 1, board.indexOf('</script>', start))
const source = ts.createSourceFile('Board.ts', setup, ts.ScriptTarget.Latest, true)
const bind = (name: string, ports: Record<string, unknown>) => {
  const statement = source.statements.find(statement => ts.isVariableStatement(statement)
    && statement.declarationList.declarations.some(item => item.name.getText(source) === name))
  if (!statement) throw new Error(`Missing Board function ${name}`)
  return new Function('ports', `with (ports) { ${ts.transpileModule(statement.getText(source), {
    compilerOptions: { target: ts.ScriptTarget.ES2022 }
  }).outputText}; return ${name} }`)(ports)
}
const deferred = () => {
  let resolve!: (value: any) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<any>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}
const failure = (message = 'History storage unavailable') => ({
  response: { status: 503, data: { message, data: { errors: { storage: 'Reconnect the history store' } } } }
})
const savedLayout: BoardLayoutDto = {
  canvasPan: { x: 120, y: -80 }, canvasZoom: 0.75,
  panels: {
    control: { collapsed: false, width: 300, activeSection: 'rules' },
    inspector: { collapsed: true, width: 280, activeSection: 'devices' }
  }
}
const layoutState = () => {
  const ports: Record<string, any> = {
    watch, canvasPan: ref({ x: 0, y: 0 }), canvasZoom: ref(1),
    boardPanels: reactive({
      control: { collapsed: false, width: 320, activeSection: 'templates' },
      inspector: { collapsed: false, width: 320, activeSection: 'devices' }
    }),
    layoutHydrated: ref(false), boardLifecycleDisposed: false, getToken: () => 'session-A',
    isNarrowViewport: () => false, layoutSaveTimer: null, LAYOUT_SAVE_DEBOUNCE_MS: 500,
    persistedWideLayout: null, panelStateTouchedBeforeLayout: false, canvasStateTouchedBeforeLayout: false,
    MAX_ZOOM: 2, DEFAULT_CONTROL_PANEL_WIDTH: 320, DEFAULT_INSPECTOR_PANEL_WIDTH: 320,
    clampPanelWidth: (width: number) => width, isControlCenterSection: () => true,
    isInspectorSection: () => true, getVisibleDeviceNodes: () => [],
    saveBoardLayout: vi.fn().mockResolvedValue(true)
  }
  for (const name of ['applyViewportPanelConstraints', 'buildBoardLayoutPayload', 'applyBoardLayout',
    'scheduleBoardLayoutSave', 'applyLoadedBoardLayout']) {
    ports[name] = bind(name, ports)
  }
  const watchers = source.statements.filter(statement => ts.isExpressionStatement(statement)
    && ts.isCallExpression(statement.expression) && statement.expression.expression.getText(source) === 'watch'
    && statement.getText(source).includes('scheduleBoardLayoutSave'))
  if (watchers.length === 0) throw new Error('Board layout persistence watcher is missing')
  const scope = effectScope()
  scope.run(() => new Function('ports', `with (ports) { ${ts.transpileModule(
    watchers.map(statement => statement.getText(source)).join('\n'), {
      compilerOptions: { target: ts.ScriptTarget.ES2022 }
    }).outputText} }`)(ports))
  return { ports, stop: () => scope.stop() }
}
const layoutSaveState = () => {
  const session = { token: 'session-A' }
  const ports: Record<string, any> = {
    getToken: () => session.token,
    isCurrentSessionToken: (token: string) => session.token === token,
    currentAuthUserId: ref(1), boardOwnerUserId: 1,
    boardApi: { saveLayout: vi.fn().mockResolvedValue(savedLayout) },
    buildBoardLayoutPayload: () => savedLayout,
    pendingBoardLayoutSave: null, boardLayoutSaveDrainRunning: false, boardLayoutSaveIdleResolvers: [],
    persistedWideLayout: null, layoutSaveErrorShown: false, layoutSaveFeedbackSuppressed: true,
    boardLifecycleDisposed: true, layoutSaveTimer: null, layoutHydrated: ref(true),
    isNarrowViewport: () => false, notifyError: vi.fn(), t: (key: string) => key
  }
  for (const name of ['persistBoardLayout', 'drainBoardLayoutSaveQueue', 'waitForBoardLayoutSaveIdle',
    'saveBoardLayout', 'flushPendingBoardLayout']) ports[name] = bind(name, ports)
  return { ports, session }
}
const state = () => {
  const ports: Record<string, any> = {
    boardLifecycleDisposed: false,
    verificationHistoryRequests: createPagedRequestCoordinator(),
    simulationHistoryRequests: createPagedRequestCoordinator(),
    fuzzingHistoryRequests: createPagedRequestCoordinator(),
    boardApi: { getVerificationRuns: vi.fn().mockResolvedValue([{ id: 1 }]), getLayout: vi.fn().mockResolvedValue(savedLayout) },
    simulationApi: { getUserSimulations: vi.fn().mockResolvedValue([{ id: 2 }]) },
    fuzzingApi: { getRuns: vi.fn().mockResolvedValue([{ id: 7 }]) },
    verificationRuns: ref([{ id: 1 }]), simulationRuns: ref([{ id: 2 }]), fuzzingRuns: ref([{ id: 7 }]),
    retainKnownVerificationHistoryAvailability: (runs: unknown) => runs,
    retainKnownSimulationHistoryAvailability: (runs: unknown) => runs,
    retainKnownFuzzingHistoryAvailability: (runs: unknown) => runs,
    withUnreadFuzzUnavailablePlaceholders: (runs: unknown) => runs,
    refreshCurrentBoardModelFingerprints: vi.fn().mockResolvedValue(undefined),
    historyResultErrors: { verification: null, simulation: null, fuzzing: null },
    historyResultsLoadingEpoch: 0, loadingResultHistory: ref(false),
    fuzzingRunsPage: ref(0), fuzzingRunsHasMore: ref(false), FUZZ_RUN_HISTORY_PAGE_SIZE: 20,
    fuzzingHistoryAppendPromise: null, loadingMoreFuzzingRuns: ref(false),
    showHistoryPanel: ref(true), activeHistoryLayer: ref('results'), activeHistoryResultFilter: ref('all'),
    locale: ref('zh-CN'), t: (key: string, params?: Record<string, unknown>) => i18n.global.t(key, params || {}),
    localizedErrorMessage, errorDetails,
    extractApiErrorMessage: (error: unknown, fallback: string) => localizedErrorMessage(error, fallback, 'zh-CN'),
    notifyError: vi.fn(), loadTaskInbox: vi.fn().mockResolvedValue(true),
    reconcileOpenRunAgainstHistory: vi.fn(), historyPanelIntentEpoch: 0,
    historyDetailRequests: { invalidate: vi.fn() }, isModelPlaybackActive: ref(false),
    isAnyRecommendationRunning: () => false, closeOtherWorkflowSurfaces: vi.fn(), notifyBlocked: vi.fn(),
    unreadFuzzNotifications: ref([{ kind: 'COMPLETED', runId: 7, taskId: 7 }]), persistFuzzNotificationState: vi.fn(),
    currentAuthUserId: ref(1), boardAuthScopeEpoch: 0,
    isCurrentBoardAuthScope: (epoch: number) => epoch === ports.boardAuthScopeEpoch,
    hydrateFuzzNotificationState: vi.fn(), requestBoardSnapshotRefresh: vi.fn().mockResolvedValue(false),
    applyLoadedBoardLayout: vi.fn(), isBoardDataReady: ref(false),
    boardForegroundRefreshPromise: null,
    boardLayoutLoadError: ref(null), loadingBoardLayout: ref(false)
  }
  for (const name of ['isHistoryResultErrorVisible', 'loadVerificationRuns', 'loadSimulationRuns',
    'executeFuzzingRunsRequest', 'loadFuzzingRuns', 'loadMoreFuzzingRuns', 'loadHistoryResults', 'refreshRunHistory',
    'refreshHistoryLayer', 'isCurrentHistoryPanelIntent', 'clearVisibleFuzzResultNotifications',
    'toggleHistoryPanel', 'loadBoardLayout', 'loadBoardAuthScope']) {
    if (name === 'isHistoryResultErrorVisible' && !setup.includes(`const ${name} =`)) continue
    ports[name] = bind(name, ports)
  }
  return ports
}

describe('Board authoritative read feedback', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'zh-CN'
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
  })
  afterEach(() => vi.restoreAllMocks())

  it.each(['results', 'tasks'] as const)('refreshes the %s layer with one awaited fingerprint read', async layer => {
    const ports = state()
    const fingerprints = deferred()
    ports.refreshCurrentBoardModelFingerprints.mockReturnValueOnce(fingerprints.promise)
    let completed = false
    const pending = ports.refreshHistoryLayer(layer).then((loaded: boolean) => {
      completed = true
      return loaded
    })
    await flushPromises()
    expect(completed).toBe(false)
    fingerprints.resolve(undefined)
    expect(await pending).toBe(true)
    expect(ports.refreshCurrentBoardModelFingerprints).toHaveBeenCalledOnce()
    expect(ports.loadTaskInbox).toHaveBeenCalledTimes(layer === 'tasks' ? 1 : 0)
    expect(ports.boardApi.getVerificationRuns).toHaveBeenCalledTimes(layer === 'results' ? 1 : 0)
    expect(ports.simulationApi.getUserSimulations).toHaveBeenCalledTimes(layer === 'results' ? 1 : 0)
    expect(ports.fuzzingApi.getRuns).toHaveBeenCalledTimes(layer === 'results' ? 1 : 0)
  })

  it('keeps separate authoritative fingerprint reads for successive result refreshes', async () => {
    const ports = state()
    expect(await ports.refreshHistoryLayer('results')).toBe(true)
    expect(await ports.refreshHistoryLayer('results')).toBe(true)
    expect(ports.refreshCurrentBoardModelFingerprints).toHaveBeenCalledTimes(2)
    expect(ports.fuzzingApi.getRuns).toHaveBeenCalledTimes(2)
  })

  it.each([
    ['openVerificationRun', 'failedToLoadVerificationRun'],
    ['selectAndPlayVerificationTrace', 'failedToLoadTrace'],
    ['selectAndPlaySimulationTrace', 'failedToLoadSimulationRun'],
    ['openFuzzingRun', 'failedToLoadFuzzingRun'],
    ['selectAndPlayFuzzingFinding', 'failedToLoadFuzzingFinding']
  ])('keeps a failed historical opener %s localized without exposing service prose', async (opener, key) => {
    const ports = state()
    const reject = () => vi.fn().mockRejectedValue(failure('Historical storage query failed'))
    Object.assign(ports, {
      historyDetailRequests: createPagedRequestCoordinator(), boardMutationAdmissionEpoch: 0,
      ensureHistoricalPlaybackUiAdmission: () => true, isCurrentDeepLinkLoad: () => false,
      isPersistedHistoryDataInvalid: () => false, shouldReportUnusableHistoryDeepLink: () => false,
      closeHistoryPanel: vi.fn(), fuzzingResultRequestEpoch: 0, fuzzingError: ref(null),
      fuzzingResult: ref(null), fuzzingResultLoading: ref(false), showFuzzingResultDialog: ref(false), nextTick
    })
    ports.boardApi.getVerificationRun = reject()
    ports.boardApi.getVerificationTrace = reject()
    ports.simulationApi.getSimulation = reject()
    ports.fuzzingApi.getRun = reject()
    ports.fuzzingApi.getFinding = reject()
    ports.extractApiErrorMessage = (error: unknown, fallback: string) =>
      localizedErrorMessage(error, fallback, i18n.global.locale.value)
    const open = bind(opener, ports)
    for (const language of ['zh-CN', 'en'] as const) {
      i18n.global.locale.value = language
      await open(7)
      expect(ports.notifyError).toHaveBeenLastCalledWith(i18n.global.t(`app.${key}`))
    }
  })

  it('preserves history until the lazy exploration result has mounted', async () => {
    const ports = state()
    const frame: Array<{ resultVisible: boolean; historyClosed: boolean }> = []
    Object.assign(ports, {
      historyDetailRequests: createPagedRequestCoordinator(), isCurrentDeepLinkLoad: () => false,
      isPersistedHistoryDataInvalid: () => false, shouldReportUnusableHistoryDeepLink: () => false,
      closeHistoryPanel: vi.fn(), fuzzingResultRequestEpoch: 0, fuzzingError: ref(null),
      fuzzingResult: ref(null), fuzzingResultLoading: ref(false), showFuzzingResultDialog: ref(false),
      nextTick: async () => { frame.push({ resultVisible: ports.showFuzzingResultDialog.value,
        historyClosed: ports.closeHistoryPanel.mock.calls.length > 0 }) }
    })
    ports.fuzzingApi.getRun = vi.fn().mockRejectedValue(failure('Read failed'))
    await bind('openFuzzingRun', ports)(7)
    expect(frame[0]).toEqual({ resultVisible: true, historyClosed: false })
    expect(ports.closeHistoryPanel).not.toHaveBeenCalled()
  })

  it('opens a saved exploration result with one awaited fingerprint read', async () => {
    const ports = state()
    const run = { id: 7 }
    Object.assign(ports, {
      historyDetailRequests: createPagedRequestCoordinator(), fuzzingResultRequestEpoch: 0,
      fuzzingError: ref(null), fuzzingResult: ref(null), fuzzingResultLoading: ref(false),
      showFuzzingResultDialog: ref(false), nextTick,
      dismissAllNotifications: vi.fn(), summarizeFuzzingRun: () => ({ id: 7 }),
      upsertFuzzingRunSummary: vi.fn(), clearFuzzNotifications: vi.fn()
    })
    ports.fuzzingApi.getRun = vi.fn().mockResolvedValue(run)
    ports.presentFuzzingRun = bind('presentFuzzingRun', ports)

    expect(await bind('openFuzzingRun', ports)(7)).toBe(true)

    expect(ports.refreshCurrentBoardModelFingerprints).toHaveBeenCalledOnce()
    expect(ports.fuzzingResult.value).toEqual(run)
    expect(ports.showFuzzingResultDialog.value).toBe(true)
    expect(ports.fuzzingResultLoading.value).toBe(false)
  })

  it('does not repeat the initial persisted board failure in a toast', async () => {
    const ports = state()
    await ports.loadBoardAuthScope(0, { hydrateNotifications: true, initialLayout: true })
    expect(ports.requestBoardSnapshotRefresh).toHaveBeenCalledWith({ force: true })
    expect(ports.applyLoadedBoardLayout).toHaveBeenCalled()
    expect(ports.notifyError).not.toHaveBeenCalled()
  })

  it('does not flush an old board layout into the next account during teardown', async () => {
    const { ports, session } = layoutSaveState()
    ports.layoutSaveTimer = setTimeout(() => undefined, 10_000)
    session.token = 'session-B'
    ports.currentAuthUserId.value = 2
    expect(await ports.flushPendingBoardLayout({ silent: true })).toBe(false)
    expect(ports.boardApi.saveLayout).not.toHaveBeenCalled()
  })

  it('drops a queued layout write after its session changes and ignores the old response', async () => {
    const { ports, session } = layoutSaveState()
    const response = deferred()
    ports.boardApi.saveLayout.mockReturnValueOnce(response.promise)
    const first = ports.saveBoardLayout()
    const queued = ports.saveBoardLayout()
    session.token = 'session-B'
    ports.currentAuthUserId.value = 2
    response.resolve(savedLayout)
    await first
    expect(await queued).toBe(false)
    expect(ports.boardApi.saveLayout).toHaveBeenCalledOnce()
    expect(ports.persistedWideLayout).toBeNull()
    expect(ports.notifyError).not.toHaveBeenCalled()
  })

  it('pins a layout write to its initiating token', async () => {
    const { ports } = layoutSaveState()
    expect(await ports.saveBoardLayout()).toBe(true)
    expect(ports.boardApi.saveLayout).toHaveBeenCalledExactlyOnceWith(savedLayout, 'session-A')
  })

  it('replaces an unsent old-session layout without rebinding its waiters to the new session', async () => {
    const { ports, session } = layoutSaveState()
    const response = deferred()
    ports.boardApi.saveLayout.mockReturnValueOnce(response.promise)
    const first = ports.saveBoardLayout()
    const oldQueued = ports.saveBoardLayout()
    session.token = 'new-session-A'
    const newQueued = ports.saveBoardLayout()
    response.resolve(savedLayout)
    await first
    expect(await oldQueued).toBe(false)
    expect(await newQueued).toBe(true)
    expect(ports.boardApi.saveLayout).toHaveBeenCalledTimes(2)
    expect(ports.boardApi.saveLayout).toHaveBeenNthCalledWith(1, savedLayout, 'session-A')
    expect(ports.boardApi.saveLayout).toHaveBeenNthCalledWith(2, savedLayout, 'new-session-A')
  })

  it('still flushes layout when the same session leaves the board', async () => {
    const { ports } = layoutSaveState()
    ports.layoutSaveTimer = setTimeout(() => undefined, 10_000)
    expect(await ports.flushPendingBoardLayout({ silent: true })).toBe(true)
    expect(ports.boardApi.saveLayout).toHaveBeenCalledOnce()
  })

  it('restores a saved layout without writing it back, then persists an actual user change', async () => {
    vi.useFakeTimers()
    const { ports, stop } = layoutState()
    try {
      ports.applyLoadedBoardLayout(savedLayout, true)
      await nextTick()
      await vi.runAllTimersAsync()
      expect(ports.canvasZoom.value).toBe(0.75)
      expect(ports.saveBoardLayout).not.toHaveBeenCalled()

      ports.canvasZoom.value = 0.9
      await nextTick()
      await vi.runAllTimersAsync()
      expect(ports.saveBoardLayout).toHaveBeenCalledOnce()
    } finally {
      stop()
      vi.useRealTimers()
    }
  })

  it('persists an early viewport edit once after restoring the other saved layout fields', async () => {
    vi.useFakeTimers()
    const { ports, stop } = layoutState()
    try {
      ports.canvasZoom.value = 0.9
      ports.canvasStateTouchedBeforeLayout = true
      ports.applyLoadedBoardLayout(savedLayout, true)
      await nextTick()
      await vi.runAllTimersAsync()
      expect(ports.canvasZoom.value).toBe(0.9)
      expect(ports.boardPanels.control.width).toBe(300)
      expect(ports.saveBoardLayout).toHaveBeenCalledExactlyOnceWith({ silent: true })
    } finally {
      stop()
      vi.useRealTimers()
    }
  })

  it.each(['control', 'inspector', null] as const)(
    'preserves the early narrow panel choice %s when the saved layout arrives', async panel => {
      const { ports, stop } = layoutState()
      ports.isNarrowViewport = () => true
      if (panel) {
        ports.boardPanels.control.collapsed = panel !== 'control'
        ports.boardPanels.inspector.collapsed = panel !== 'inspector'
        ports.panelStateTouchedBeforeLayout = true
      }
      try {
        ports.applyLoadedBoardLayout(savedLayout, true)
        await nextTick()
        expect(ports.boardPanels.control.collapsed).toBe(panel !== 'control')
        expect(ports.boardPanels.inspector.collapsed).toBe(panel !== 'inspector')
        expect(ports.layoutHydrated.value).toBe(true)
        expect(ports.persistedWideLayout).toEqual(savedLayout)
        expect(ports.saveBoardLayout).not.toHaveBeenCalled()
      } finally { stop() }
    }
  )

  it('restores layout after the snapshot is ready without waiting for the task inbox', async () => {
    const ports = state()
    const snapshot = deferred()
    const tasks = deferred()
    ports.requestBoardSnapshotRefresh.mockReturnValueOnce(snapshot.promise)
    ports.loadTaskInbox.mockReturnValueOnce(tasks.promise)
    const pending = ports.loadBoardAuthScope(0, { initialLayout: true })
    await flushPromises()
    expect(ports.applyLoadedBoardLayout).not.toHaveBeenCalled()

    snapshot.resolve(true)
    await flushPromises()
    expect(ports.applyLoadedBoardLayout).toHaveBeenCalledExactlyOnceWith(savedLayout, true)
    expect(ports.loadingBoardLayout.value).toBe(false)
    tasks.resolve(true)
    await pending
  })

  it('keeps a rejected layout read unknown until a successful retry instead of hydrating defaults', async () => {
    const ports = state()
    ports.boardApi.getLayout.mockRejectedValueOnce(failure('Layout storage unavailable'))
    await ports.loadBoardAuthScope(0, { initialLayout: true })
    expect(ports.applyLoadedBoardLayout).not.toHaveBeenCalled()
    expect(ports.boardLayoutLoadError.value).toMatchObject({ cause: 'Layout storage unavailable' })
    expect(ports.loadingBoardLayout.value).toBe(false)

    expect(await ports.loadBoardLayout(true)).toBe(true)
    expect(ports.applyLoadedBoardLayout).toHaveBeenCalledExactlyOnceWith(savedLayout, true)
    expect(ports.boardLayoutLoadError.value).toBeNull()
    expect(ports.notifyError).not.toHaveBeenCalled()
  })

  it.each(['resolve', 'reject'] as const)('does not apply a late layout %s across the auth boundary', async outcome => {
    const ports = state()
    const layout = deferred()
    ports.boardApi.getLayout.mockReturnValueOnce(layout.promise)
    const pending = ports.loadBoardLayout(true)
    ports.boardAuthScopeEpoch++
    if (outcome === 'resolve') layout.resolve(savedLayout)
    else layout.reject(failure())
    expect(await pending).toBe(false)
    expect(ports.applyLoadedBoardLayout).not.toHaveBeenCalled()
    expect(ports.boardLayoutLoadError.value).toBeNull()
  })

  it.each(['verification', 'simulation', 'fuzzing'] as const)(
    'keeps the visible %s rejection diagnostic and known rows without a duplicate toast', async kind => {
      const ports = state()
      const get = kind === 'verification' ? ports.boardApi.getVerificationRuns
        : kind === 'simulation' ? ports.simulationApi.getUserSimulations : ports.fuzzingApi.getRuns
      get.mockRejectedValue(failure())
      const load = kind === 'verification' ? ports.loadVerificationRuns
        : kind === 'simulation' ? ports.loadSimulationRuns : ports.loadFuzzingRuns
      expect(await load()).toBe(false)
      expect(ports.historyResultErrors[kind]).toMatchObject({
        cause: 'History storage unavailable', fields: { storage: 'Reconnect the history store' }, details: ['History storage unavailable', 'HTTP 503']
      })
      expect(ports.historyResultErrors[kind]).not.toHaveProperty('message')
      expect(ports[`${kind === 'fuzzing' ? 'fuzzing' : kind}Runs`].value).toHaveLength(1)
      expect(ports.notifyError).not.toHaveBeenCalled()
      get.mockResolvedValue([])
      expect(await load()).toBe(true)
      expect(ports.historyResultErrors[kind]).toBeNull()
    })

  it.each(['hidden', 'tasks', 'other-filter'] as const)(
    'retains a needed notification outside a visible source owner: %s', async mode => {
      const ports = state()
      if (mode === 'hidden') ports.showHistoryPanel.value = false
      if (mode === 'tasks') ports.activeHistoryLayer.value = 'tasks'
      if (mode === 'other-filter') ports.activeHistoryResultFilter.value = 'verification'
      ports.fuzzingApi.getRuns.mockRejectedValue(failure())
      expect(await ports.loadFuzzingRuns()).toBe(false)
      expect(ports.notifyError).toHaveBeenCalledOnce()
    })

  it.each([false, true])('keeps an append failure with its visible owner unless that panel was closed: %s', async closed => {
    const ports = state()
    ports.activeHistoryResultFilter.value = 'fuzzing'
    ports.fuzzingRunsHasMore.value = true
    const page = deferred()
    ports.fuzzingApi.getRuns.mockReturnValueOnce(page.promise)
    const loading = ports.loadMoreFuzzingRuns()
    expect(ports.fuzzingApi.getRuns).toHaveBeenCalledWith(1, ports.FUZZ_RUN_HISTORY_PAGE_SIZE)
    if (closed) ports.showHistoryPanel.value = false
    page.reject(failure())
    await loading
    expect(ports.fuzzingRuns.value).toEqual([{ id: 7 }])
    expect(ports.fuzzingRunsPage.value).toBe(0)
    expect(ports.historyResultErrors.fuzzing).toMatchObject({ cause: 'History storage unavailable' })
    expect(ports.loadingMoreFuzzingRuns.value).toBe(false)
    expect(ports.notifyError).toHaveBeenCalledTimes(closed ? 1 : 0)
  })

  it('leaves aggregate source failures with their existing owner after the panel is closed', async () => {
    const ports = state()
    ports.boardApi.getVerificationRuns.mockRejectedValue(failure())
    expect(await ports.loadHistoryResults()).toBe(false)
    expect(ports.notifyError).not.toHaveBeenCalled()
    ports.showHistoryPanel.value = false
    expect(await ports.loadHistoryResults()).toBe(false)
    expect(ports.notifyError).not.toHaveBeenCalled()
  })

  it('a single-source refresh cannot turn a superseded aggregate into a hidden failure notice', async () => {
    const ports = state()
    const older = deferred()
    ports.showHistoryPanel.value = false
    ports.fuzzingApi.getRuns.mockReturnValueOnce(older.promise).mockResolvedValueOnce([])
    const pending = ports.loadHistoryResults()
    expect(await ports.loadFuzzingRuns()).toBe(true)
    older.reject(failure('Older history read failed'))
    expect(await pending).toBe(false)
    expect(ports.historyResultErrors.fuzzing).toBeNull()
    expect(ports.notifyError).not.toHaveBeenCalled()
  })

  it.each(['verification', 'simulation', 'fuzzing'] as const)(
    'a superseded %s success returns false and preserves the newest collection', async kind => {
      const ports = state()
      const get = kind === 'verification' ? ports.boardApi.getVerificationRuns
        : kind === 'simulation' ? ports.simulationApi.getUserSimulations : ports.fuzzingApi.getRuns
      const load = kind === 'verification' ? ports.loadVerificationRuns
        : kind === 'simulation' ? ports.loadSimulationRuns : ports.loadFuzzingRuns
      const older = deferred()
      get.mockReturnValueOnce(older.promise).mockResolvedValueOnce([{ id: 42 }])
      const pending = load()
      expect(await load()).toBe(true)
      older.resolve([{ id: 99 }])
      expect(await pending).toBe(false)
      expect(ports[`${kind}Runs`].value).toEqual([{ id: 42 }])
      expect(ports.notifyError).not.toHaveBeenCalled()
    })

  it.each(['verification', 'simulation', 'fuzzing'] as const)(
    'a superseded %s success cannot clear unread completion or reconcile old lists', async kind => {
      const ports = state()
      const get = kind === 'verification' ? ports.boardApi.getVerificationRuns
        : kind === 'simulation' ? ports.simulationApi.getUserSimulations : ports.fuzzingApi.getRuns
      const older = deferred()
      get.mockReturnValueOnce(older.promise).mockRejectedValueOnce(failure('Latest history read failed'))
      ports.showHistoryPanel.value = false
      const opening = ports.toggleHistoryPanel('results')
      expect(await ports.refreshRunHistory()).toBe(false)
      older.resolve([{ id: 99 }])
      await opening
      expect(ports.unreadFuzzNotifications.value).toHaveLength(1)
      expect(ports.persistFuzzNotificationState).not.toHaveBeenCalled()
      expect(ports.reconcileOpenRunAgainstHistory).not.toHaveBeenCalled()
      expect(ports.historyResultErrors[kind]).not.toBeNull()
      expect(ports.notifyError).not.toHaveBeenCalled()
    })

  it.each(['verification', 'simulation', 'fuzzing'] as const)(
    'a superseded %s rejection returns false without replacing the latest error', async kind => {
      const ports = state()
      const get = kind === 'verification' ? ports.boardApi.getVerificationRuns
        : kind === 'simulation' ? ports.simulationApi.getUserSimulations : ports.fuzzingApi.getRuns
      const load = kind === 'verification' ? ports.loadVerificationRuns
        : kind === 'simulation' ? ports.loadSimulationRuns : ports.loadFuzzingRuns
      const older = deferred()
      get.mockReturnValueOnce(older.promise).mockRejectedValueOnce(failure('Latest history read failed'))
      const pending = load()
      expect(await load()).toBe(false)
      const latest = ports.historyResultErrors[kind]
      older.reject(failure('Older history read failed'))
      expect(await pending).toBe(false)
      expect(ports.historyResultErrors[kind]).toEqual(latest)
      expect(ports.notifyError).not.toHaveBeenCalled()
    })

  it('does not reconcile open result ownership after an older history refresh loses authority', async () => {
    const ports = state()
    const older = deferred()
    ports.fuzzingApi.getRuns.mockReturnValueOnce(older.promise).mockRejectedValueOnce(failure())
    const pending = ports.refreshRunHistory()
    expect(await ports.refreshRunHistory()).toBe(false)
    older.resolve([{ id: 99 }])
    expect(await pending).toBe(false)
    expect(ports.reconcileOpenRunAgainstHistory).not.toHaveBeenCalled()
  })

  it('does not acknowledge a finished source batch once a newer aggregate owns the history read', async () => {
    const ports = state()
    const finished = new Set<string>()
    let latest: Promise<boolean> | undefined
    let scheduled = false
    for (const kind of ['verification', 'simulation', 'fuzzing']) {
      const coordinator = ports[`${kind}HistoryRequests`]
      const finish = coordinator.finish.bind(coordinator)
      vi.spyOn(coordinator, 'finish').mockImplementation((token: any) => {
        finish(token)
        finished.add(kind)
        if (finished.size === 3 && !scheduled) {
          scheduled = true
          // Source writes have completed, while Promise.all has not yet resumed its caller.
          void Promise.resolve().then(() => {
            ports.fuzzingApi.getRuns.mockRejectedValue(failure())
            latest = ports.loadHistoryResults()
          })
        }
      })
    }
    expect(await ports.loadHistoryResults()).toBe(false)
    expect(latest).toBeDefined()
    expect(await latest).toBe(false)
    expect(ports.notifyError).not.toHaveBeenCalled()
    expect(ports.loadingResultHistory.value).toBe(false)
  })

  it('does not claim a blocked append applied an authoritative page', async () => {
    const ports = state()
    const token = ports.fuzzingHistoryRequests.beginReplace()
    expect(await ports.loadFuzzingRuns(true, { append: true })).toBe(false)
    expect(ports.fuzzingApi.getRuns).not.toHaveBeenCalled()
    ports.fuzzingHistoryRequests.finish(token)
    expect(await ports.loadFuzzingRuns(true, { append: true })).toBe(true)
    expect(ports.fuzzingApi.getRuns).toHaveBeenCalledOnce()
  })

  it('acknowledges the current successful visible lists and reconciles current history', async () => {
    const ports = state()
    ports.showHistoryPanel.value = false
    await ports.toggleHistoryPanel('results')
    expect(ports.unreadFuzzNotifications.value).toEqual([])
    expect(ports.persistFuzzNotificationState).toHaveBeenCalledOnce()
    expect(await ports.refreshRunHistory()).toBe(true)
    expect(ports.reconcileOpenRunAgainstHistory).toHaveBeenCalledOnce()
  })

  it('retains one snapshot read cause beside Retry until a successful retry replaces the snapshot', async () => {
    const ports = state()
    const keys = ['templates', 'nodes', 'environment', 'rules', 'specs']
    Object.assign(ports, {
      allBoardDataKeys: keys, boardDataLoadState: reactive(Object.fromEntries(keys.map(key => [key, 'ready']))),
      boardSnapshotError: ref(null), templatesLoading: ref(false), hydratedBoardAuthScopeEpoch: 0,
      deviceTemplates: ref([{ id: 1 }]), nodes: ref([{ id: 'known-device' }]),
      environmentVariables: ref([]), rules: ref([{ id: 5 }]), specifications: ref([{ id: 6 }]),
      replaceNodesFromServer: (nodes: unknown) => { ports.nodes.value = nodes },
      reconcileDanglingBoardFocus: vi.fn(), syncRuleDerivedEdges: vi.fn(), loadBoardUndoAvailability: vi.fn()
    })
    ports.boardApi.getSnapshot = vi.fn().mockRejectedValue(failure('Snapshot service unavailable'))
    const load = bind('refreshBoardSnapshot', ports)
    const bannerIndex = board.indexOf('data-testid="board-data-load-error"')
    const template = board.slice(board.lastIndexOf('<div', bannerIndex),
      board.indexOf('<!-- A shared link naming a run', bannerIndex))
    const wrapper = mount(defineComponent({
      components: { FailureDetails }, template,
      setup: () => ({ ...ports,
        failedBoardDataKeys: computed(() => keys.filter(key => ports.boardDataLoadState[key] === 'error')),
        boardDataKeyLabel: (key: string) => key, retryBoardDataLoad: load })
    }))
    try {
      expect(await load()).toBe(false)
      await wrapper.vm.$nextTick()
      const alert = wrapper.get('[data-testid="board-data-load-error"]')
      expect(alert.get('[data-testid="failure-cause"]').text()).toBe('Snapshot service unavailable')
      expect(alert.get('[data-testid="failure-fields"]').text()).toContain('Reconnect the history store')
      expect(alert.get('[data-testid="failure-technical-details"]').attributes('open')).toBeUndefined()
      expect(ports.nodes.value).toEqual([{ id: 'known-device' }])
      expect(ports.rules.value).toEqual([{ id: 5 }])
      expect(ports.specifications.value).toEqual([{ id: 6 }])
      ports.boardDataLoadState.nodes = 'loading'
      await wrapper.vm.$nextTick()
      // A partial reload does not replace the failed full snapshot read for the other collections.
      expect(ports.boardSnapshotError.value).toMatchObject({ cause: 'Snapshot service unavailable' })
      expect(wrapper.get('[data-testid="failure-cause"]').text()).toBe('Snapshot service unavailable')
      expect(await load()).toBe(false)
      await wrapper.vm.$nextTick()
      ports.boardApi.getSnapshot.mockResolvedValue({ deviceTemplates: [], nodes: [{ id: 'new-device' }],
        environmentVariables: [], rules: [], specifications: [] })
      await alert.get('button').trigger('click')
      await vi.waitFor(() => expect(ports.boardSnapshotError.value).toBeNull())
      await wrapper.vm.$nextTick()
      expect(wrapper.find('[data-testid="board-data-load-error"]').exists()).toBe(false)
      expect(ports.nodes.value).toEqual([{ id: 'new-device' }])
      expect(ports.notifyError).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })

  it('does not replace a new account snapshot failure with a late old read', async () => {
    const ports = state()
    const older = deferred()
    Object.assign(ports, { allBoardDataKeys: ['nodes'], boardDataLoadState: { nodes: 'ready' },
      boardSnapshotError: ref(null), templatesLoading: ref(false) })
    ports.boardApi.getSnapshot = vi.fn().mockReturnValue(older.promise)
    const pending = bind('refreshBoardSnapshot', ports)()
    ports.boardAuthScopeEpoch++
    const latest = errorDetails(failure('New account snapshot failed'))
    ports.boardSnapshotError.value = latest
    older.reject(failure('Old account snapshot failed'))
    expect(await pending).toBe(false)
    expect(ports.boardSnapshotError.value).toEqual(latest)
    expect(ports.templatesLoading.value).toBe(true)
    expect(ports.notifyError).not.toHaveBeenCalled()
  })
})
