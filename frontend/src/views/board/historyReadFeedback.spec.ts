import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { computed, effectScope, nextTick, reactive, ref, watch } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { shouldReportUnusableHistoryDeepLink } from '@/views/Board.vue'
import { i18n } from '@/assets/i18n'
import { createPagedRequestCoordinator } from '@/utils/pagedRequestCoordinator'
import { isDefinitiveMutationRejection } from '@/utils/boardWriteReceipt'
import { errorDetails, localizedErrorMessage } from '@/utils/userMessage'
import type { BoardLayoutDto } from '@/types/canvas'
import { applyBoardRunTarget, isSameBoardRunTarget } from './runDeepLink'
import { useBoardModelComparison } from './useBoardModelComparison'

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

const bindLayoutWrites = (ports: Record<string, any>) => {
  for (const name of ['persistBoardLayout', 'drainBoardLayoutSaveQueue', 'waitForBoardLayoutSaveIdle',
    'saveBoardLayout', 'flushPendingBoardLayout']) ports[name] = bind(name, ports)
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
    wideLayoutToRestore: null, wasNarrowViewport: false,
    panelStateTouchedBeforeLayout: false, canvasStateTouchedBeforeLayout: false,
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
    isDefinitiveMutationRejection,
    pendingBoardLayoutSave: null, boardLayoutSaveDrainRunning: false, boardLayoutSaveIdleResolvers: [],
    layoutSaveErrorShown: false, layoutSaveFeedbackSuppressed: true,
    boardLifecycleDisposed: true, layoutSaveTimer: null, layoutHydrated: ref(true),
    isNarrowViewport: () => false, notifyError: vi.fn(), t: (key: string) => key
  }
  bindLayoutWrites(ports)
  return { ports, session }
}

const responsiveLayoutState = () => {
  const { ports, stop } = layoutState()
  const viewport = { innerWidth: 1440, innerHeight: 900 }
  Object.assign(ports, {
    window: viewport, wasNarrowViewport: false, actionDockViewportWidth: ref(1440), boardViewportHeight: ref(900),
    isNarrowViewport: () => viewport.innerWidth < 1024,
    currentAuthUserId: ref(1), boardOwnerUserId: 1, isCurrentSessionToken: (token: string) => token === 'session-A',
    pendingBoardLayoutSave: null, boardLayoutSaveDrainRunning: false, boardLayoutSaveIdleResolvers: [],
    boardApi: { saveLayout: vi.fn().mockResolvedValue(savedLayout) },
    isDefinitiveMutationRejection, layoutSaveErrorShown: false, layoutSaveFeedbackSuppressed: false,
    notifyError: vi.fn(), t: (key: string) => key
  })
  bindLayoutWrites(ports)
  const updateViewport = bind('updateActionDockViewport', ports)
  const resize = (width: number) => {
    viewport.innerWidth = width
    updateViewport()
  }
  ports.applyLoadedBoardLayout(savedLayout, true)
  return { ports, resize, stop }
}
const state = () => {
  const ports: Record<string, any> = {
    computed,
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
    invalidateCurrentBoardModelFingerprints: vi.fn(),
    historyResultErrors: { verification: null, simulation: null, fuzzing: null },
    historyResultsLoadingEpoch: 0, loadingResultHistory: ref(false),
    fuzzingRunsPage: ref(0), fuzzingRunsHasMore: ref(false), FUZZ_RUN_HISTORY_PAGE_SIZE: 20,
    fuzzingHistoryAppendPromise: null, loadingMoreFuzzingRuns: ref(false),
    activeWorkflowPanel: ref({ kind: 'history' }), activeHistoryLayer: ref('results'),
    activeHistoryResultFilter: ref('all'),
    locale: ref('zh-CN'), t: (key: string, params?: Record<string, unknown>) => i18n.global.t(key, params || {}),
    localizedErrorMessage, errorDetails,
    extractApiErrorMessage: (error: unknown, fallback: string) => localizedErrorMessage(error, fallback, 'zh-CN'),
    notifyError: vi.fn(), loadTaskInbox: vi.fn().mockResolvedValue(true),
    recheckAddressedHistory: vi.fn().mockResolvedValue(true),
    historyPanelIntentEpoch: 0, pendingHistoryRunTarget: null,
    historyDetailRequests: createPagedRequestCoordinator(), isModelPlaybackActive: ref(false),
    isAnyRecommendationRunning: () => false, isSceneReplacementInProgress: ref(false),
    closeResultSurfaces: vi.fn(), notifyBlocked: vi.fn(),
    unreadFuzzNotifications: ref([{ kind: 'COMPLETED', runId: 7, taskId: 7 }]), persistFuzzNotificationState: vi.fn(),
    currentAuthUserId: ref(1), boardAuthScopeEpoch: 0,
    isCurrentBoardAuthScope: (epoch: number) => epoch === ports.boardAuthScopeEpoch,
    hydrateFuzzNotificationState: vi.fn(), requestBoardSnapshotRefresh: vi.fn().mockResolvedValue(false),
    applyLoadedBoardLayout: vi.fn(), isBoardDataReady: ref(false),
    boardForegroundRefreshPromise: null,
    boardLayoutLoadError: ref(null), loadingBoardLayout: ref(false)
  }
  for (const name of ['isHistoryResultErrorVisible', 'loadUnpagedRuns', 'loadVerificationRuns', 'loadSimulationRuns',
    'executeFuzzingRunsRequest', 'loadFuzzingRuns', 'loadHistoryResults', 'refreshRunHistory',
    'refreshHistoryLayer', 'isCurrentHistoryPanelIntent', 'clearVisibleFuzzResultNotifications',
    'showHistoryPanel', 'clearFuzzNotifications', 'setHistoryLayer', 'retireHistoryPanelIntent',
    'closeWorkflowPanel', 'closeHistoryPanel', 'canOpenWorkflowPanel',
    'closeOtherWorkflowSurfaces', 'openHistoryPanel', 'loadBoardLayout', 'loadBoardAuthScope']) {
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

  it('closes the open Results history when its dock button is pressed during an active run', async () => {
    const ports = state()
    Object.assign(ports, {
      isVerifying: ref(true), isSimulating: ref(false), isFuzzing: ref(false),
      unreadFuzzNotificationCount: ref(0), unreadFailedFuzzCount: ref(0)
    })
    bind('openHistoryFromActionDock', ports)()
    await flushPromises()

    expect(ports.showHistoryPanel.value).toBe(false)
    expect(ports.activeHistoryLayer.value).toBe('results')
    expect(ports.loadTaskInbox).not.toHaveBeenCalled()
    expect(ports.isVerifying.value).toBe(true)
  })

  it('keeps a newer URL load when closing the History list', () => {
    const ports = state()
    const newer = { kind: 'verification', runId: 8 }
    const details = createPagedRequestCoordinator()
    const request = details.beginReplace()
    Object.assign(ports, {
      pendingHistoryRunTarget: { kind: 'verification', runId: 7 },
      appliedDeepLinkTarget: newer, deepLinkTarget: ref(newer),
      historyDetailRequests: details, clearRunDeepLink: vi.fn(), isSameBoardRunTarget
    })

    ports.closeHistoryPanel()

    expect(ports.showHistoryPanel.value).toBe(false)
    expect(details.isCurrent(request)).toBe(true)
    expect(ports.clearRunDeepLink).not.toHaveBeenCalled()
  })

  it.each(['history', 'fuzzing'] as const)('closes the %s panel on logo reset while task tracking continues', panel => {
    const ports = state()
    Object.assign(ports, {
      closePlayback: vi.fn(),
      route: { query: { run: 'verification:7', trace: '8', device: 'kitchen' } },
      router: { replace: vi.fn() }, applyBoardRunTarget,
      isSimulating: ref(true), asyncSimulationActive: ref(true), asyncSimulationTask: ref({ taskId: 12 }),
      trackedFuzzTaskIds: ref([7])
    })
    ports.activeWorkflowPanel.value = panel === 'history' ? { kind: 'history' } : { kind: 'fuzzing', view: 'run' }

    bind('resetWorkspace', ports)()

    expect(ports.activeWorkflowPanel.value).toBeNull()
    expect(ports.router.replace).toHaveBeenCalledExactlyOnceWith({ query: { device: 'kitchen' } })
    expect(ports.isSimulating.value).toBe(true)
    expect(ports.asyncSimulationActive.value).toBe(true)
    expect(ports.asyncSimulationTask.value.taskId).toBe(12)
    expect(ports.trackedFuzzTaskIds.value).toEqual([7])
  })

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

  it('uses the adopted snapshot for a combined board and history refresh', async () => {
    const ports = state()
    const scene = { nodes: [], environmentVariables: [], rules: [], specifications: [], deviceTemplates: [] }
    const response = { scene, fingerprints: { verification: 'v', simulation: 's', modelSemanticsVersion: 1 } }
    const readComparison = vi.fn().mockResolvedValue(response)
    const scope = effectScope()
    const comparison = scope.run(() => useBoardModelComparison(() => scene, readComparison, () => true))!
    try {
      await comparison.refresh()
      readComparison.mockClear()
      const snapshot = deferred()
      const delayedComparison = deferred()
      readComparison.mockReturnValueOnce(delayedComparison.promise)
      ports.refreshCurrentBoardModelFingerprints = comparison.refresh
      ports.refreshBoardSnapshot = async () => {
        await snapshot.promise
        comparison.adoptSnapshot(() => {})
        return true
      }
      const pending = bind('refreshAllBoardState', ports)()
      snapshot.resolve(undefined)
      await flushPromises()
      delayedComparison.resolve(response)
      expect(await pending).toBe(true)
      expect(readComparison).not.toHaveBeenCalled()
      expect(comparison.fingerprints.value).toEqual(response.fingerprints)
      expect(ports.fuzzingApi.getRuns).toHaveBeenCalledOnce()
    } finally { scope.stop() }
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
      boardMutationAdmissionEpoch: 0, ensureHistoricalPlaybackUiAdmission: () => true,
      isPersistedHistoryDataInvalid: () => false, shouldReportUnusableHistoryDeepLink,
      closeHistoryPanel: vi.fn(), fuzzingResultRequestEpoch: 0, fuzzingError: ref(null),
      fuzzingResult: ref(null), fuzzingResultLoading: ref(false), showFuzzingResultDialog: ref(false), nextTick
    })
    ports.boardApi.getVerificationRun = reject()
    ports.boardApi.getVerificationTrace = reject()
    ports.simulationApi.getSimulation = reject()
    ports.fuzzingApi.getRun = reject()
    ports.fuzzingApi.getFinding = reject()
    ports.reportHistoryDetailFailure = bind('reportHistoryDetailFailure', ports)
    ports.playHistoricalResult = bind('playHistoricalResult', ports)
    const open = bind(opener, ports)
    const isCurrent = () => true
    const child = opener === 'selectAndPlayVerificationTrace' || opener === 'selectAndPlayFuzzingFinding'
    for (const language of ['zh-CN', 'en'] as const) {
      i18n.global.locale.value = language
      if (child) await open(7, 10, isCurrent)
      else await open(7, isCurrent)
      expect(ports.notifyError).toHaveBeenLastCalledWith(i18n.global.t(`app.${key}`))
    }
  })

  it('preserves history until the lazy exploration result has mounted', async () => {
    const ports = state()
    const frame: Array<{ resultVisible: boolean; historyClosed: boolean }> = []
    Object.assign(ports, {
      isPersistedHistoryDataInvalid: () => false, shouldReportUnusableHistoryDeepLink,
      closeHistoryPanel: vi.fn(), fuzzingResultRequestEpoch: 0, fuzzingError: ref(null),
      fuzzingResult: ref(null), fuzzingResultLoading: ref(false), showFuzzingResultDialog: ref(false),
      nextTick: async () => { frame.push({ resultVisible: ports.showFuzzingResultDialog.value,
        historyClosed: ports.closeHistoryPanel.mock.calls.length > 0 }) }
    })
    ports.fuzzingApi.getRun = vi.fn().mockRejectedValue(failure('Read failed'))
    ports.reportHistoryDetailFailure = bind('reportHistoryDetailFailure', ports)
    await bind('openFuzzingRun', ports)(7, () => true)
    expect(frame[0]).toEqual({ resultVisible: true, historyClosed: false })
    expect(ports.closeHistoryPanel).not.toHaveBeenCalled()
  })

  it('opens a saved exploration result with one awaited fingerprint read', async () => {
    const ports = state()
    const run = { id: 7 }
    Object.assign(ports, {
      fuzzingResultRequestEpoch: 0,
      fuzzingError: ref(null), fuzzingResult: ref(null), fuzzingResultLoading: ref(false),
      showFuzzingResultDialog: ref(false), nextTick,
      dismissAllNotifications: vi.fn(), summarizeFuzzingRun: () => ({ id: 7 }),
      upsertFuzzingRunSummary: vi.fn(), clearFuzzNotifications: vi.fn()
    })
    ports.fuzzingApi.getRun = vi.fn().mockResolvedValue(run)
    ports.presentFuzzingRun = bind('presentFuzzingRun', ports)

    await bind('openFuzzingRun', ports)(7, () => true)

    expect(ports.refreshCurrentBoardModelFingerprints).toHaveBeenCalledOnce()
    expect(ports.fuzzingResult.value).toEqual(run)
    expect(ports.showFuzzingResultDialog.value).toBe(true)
    expect(ports.fuzzingResultLoading.value).toBe(false)
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
    expect(await first).toBe(false)
    expect(await queued).toBe(false)
    expect(ports.boardApi.saveLayout).toHaveBeenCalledOnce()
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

  it('reports an unconfirmed layout save expedited by a resize into a narrow window', async () => {
    vi.useFakeTimers()
    const { ports, resize, stop } = responsiveLayoutState()
    ports.notifyBlocked = vi.fn()
    ports.boardApi.saveLayout.mockRejectedValueOnce(new Error('Response lost'))
    try {
      ports.canvasZoom.value = 0.95
      resize(600)
      await ports.waitForBoardLayoutSaveIdle()
      expect(ports.notifyBlocked).toHaveBeenCalledExactlyOnceWith('app.boardLayoutSaveUnconfirmed')
      resize(1440)
      await vi.advanceTimersByTimeAsync(1000)
      expect(ports.canvasZoom.value).toBe(0.95)
      expect(ports.boardApi.saveLayout).toHaveBeenCalledOnce()
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
        expect(ports.wideLayoutToRestore).toEqual(savedLayout)
        expect(ports.saveBoardLayout).not.toHaveBeenCalled()
      } finally { stop() }
    }
  )

  it('fits the narrow canvas when the saved layout arrives before navigation', async () => {
    const { ports, stop } = layoutState()
    ports.isNarrowViewport = () => true
    ports.nextTick = nextTick
    ports.getVisibleDeviceNodes = () => [{ id: 'lamp' }]
    ports.fittedViewportForNodes = () => ({ pan: { x: 30, y: 40 }, zoom: 0.4 })
    ports.fitNodesToCanvas = bind('fitNodesToCanvas', ports)
    ports.canvasPan.value = { x: 180, y: -90 }
    ports.canvasZoom.value = 0.9
    try {
      ports.applyLoadedBoardLayout(savedLayout, true)
      await nextTick()
      expect(ports.canvasPan.value).toEqual({ x: 30, y: 40 })
      expect(ports.canvasZoom.value).toBe(0.4)
      expect(ports.wideLayoutToRestore).toEqual(savedLayout)
      expect(ports.saveBoardLayout).not.toHaveBeenCalled()
    } finally { stop() }
  })

  it('starts the initial snapshot in a hidden tab and hydrates the saved layout after a failed snapshot', async () => {
    const ports = state()
    Object.assign(ports, {
      document: { visibilityState: 'hidden' }, boardRefreshRequestedWhileBusy: false,
      enqueueBoardMutation: (work: () => Promise<boolean>) => work(),
      refreshBoardSnapshot: vi.fn().mockResolvedValue(false)
    })
    ports.requestBoardSnapshotRefresh = bind('requestBoardSnapshotRefresh', ports)

    await ports.loadBoardAuthScope(0, { initialLayout: true })

    expect(ports.refreshBoardSnapshot).toHaveBeenCalledOnce()
    expect(ports.applyLoadedBoardLayout).toHaveBeenCalledExactlyOnceWith(savedLayout, true)
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

  it('a single-source refresh cannot turn a superseded aggregate into a hidden failure notice', async () => {
    const ports = state()
    const older = deferred()
    ports.closeHistoryPanel()
    ports.fuzzingApi.getRuns.mockReturnValueOnce(older.promise).mockResolvedValueOnce([])
    const pending = ports.loadHistoryResults()
    expect(await ports.loadFuzzingRuns()).toBe(true)
    older.reject(failure('Older history read failed'))
    expect(await pending).toBe(false)
    expect(ports.historyResultErrors.fuzzing).toBeNull()
    expect(ports.notifyError).not.toHaveBeenCalled()
  })

  it.each(['verification', 'simulation'] as const)(
    'a superseded %s success returns false and preserves the newest collection', async kind => {
      const ports = state()
      const get = kind === 'verification' ? ports.boardApi.getVerificationRuns : ports.simulationApi.getUserSimulations
      const load = kind === 'verification' ? ports.loadVerificationRuns : ports.loadSimulationRuns
      const older = deferred()
      get.mockReturnValueOnce(older.promise).mockResolvedValueOnce([{ id: 42 }])
      const pending = load()
      expect(await load()).toBe(true)
      older.resolve([{ id: 99 }])
      expect(await pending).toBe(false)
      expect(ports[`${kind}Runs`].value).toEqual([{ id: 42 }])
      expect(ports.notifyError).not.toHaveBeenCalled()
    })

  it.each(['verification', 'simulation'] as const)(
    'a superseded %s success cannot clear unread completion or replace the latest failure', async kind => {
      const ports = state()
      const get = kind === 'verification' ? ports.boardApi.getVerificationRuns : ports.simulationApi.getUserSimulations
      const older = deferred()
      get.mockReturnValueOnce(older.promise).mockRejectedValueOnce(failure('Latest history read failed'))
      ports.closeHistoryPanel()
      const opening = ports.openHistoryPanel('results')
      expect(await ports.refreshRunHistory()).toBe(false)
      older.resolve([{ id: 99 }])
      await opening
      expect(ports.unreadFuzzNotifications.value).toHaveLength(1)
      expect(ports.persistFuzzNotificationState).not.toHaveBeenCalled()
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

  it.each(['successful', 'partial'] as const)('rechecks the addressed result after a %s history refresh', async status => {
    const ports = state()
    if (status === 'partial') ports.fuzzingApi.getRuns.mockRejectedValueOnce(failure())
    expect(await ports.refreshRunHistory()).toBe(status === 'successful')
    expect(ports.recheckAddressedHistory).toHaveBeenCalledOnce()
  })

  it('rejects a finished source batch superseded before aggregation', async () => {
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
    expect(await ports.loadFuzzingRuns({ append: true })).toBe(false)
    expect(ports.fuzzingApi.getRuns).not.toHaveBeenCalled()
    ports.fuzzingHistoryRequests.finish(token)
    expect(await ports.loadFuzzingRuns({ append: true })).toBe(true)
    expect(ports.fuzzingApi.getRuns).toHaveBeenCalledOnce()
  })

  it('does not replace a new account snapshot failure with a late old read', async () => {
    const ports = state()
    const older = deferred()
    const adoptBoardSnapshot = vi.fn()
    Object.assign(ports, { allBoardDataKeys: ['nodes'], boardDataLoadState: { nodes: 'ready' },
      boardOwnerUserId: 1, currentAuthUserId: ref(1),
      boardSnapshotError: ref(null), templatesLoading: ref(false), hasHydratedBoardSnapshot: ref(false), adoptBoardSnapshot })
    ports.boardApi.getSnapshot = vi.fn().mockReturnValue(older.promise)
    const pending = bind('refreshBoardSnapshot', ports)()
    ports.boardAuthScopeEpoch++
    const latest = errorDetails(failure('New account snapshot failed'))
    ports.boardSnapshotError.value = latest
    older.reject(failure('Old account snapshot failed'))
    expect(await pending).toBe(false)
    expect(ports.boardSnapshotError.value).toEqual(latest)
    expect(ports.templatesLoading.value).toBe(true)
    expect(adoptBoardSnapshot).not.toHaveBeenCalled()
    expect(ports.notifyError).not.toHaveBeenCalled()
  })
})
