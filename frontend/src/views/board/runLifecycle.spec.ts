import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { computed, effectScope, h, nextTick, reactive, ref, watch } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { BoardModelFingerprints, ModelRunSnapshot } from '@/types/modelSemantics'
import { compareModelSnapshot } from '@/utils/modelComparison'
import { hasValidFuzzingBudget, isFuzzingPreviewCurrent } from '@/utils/fuzzingConfig'
import { errorDetails, technicalErrorLines } from '@/utils/userMessage'
import { openModalDepth, registerModalSurface } from '@/composables/useBodyScrollLock'
import { useModalAccessibility } from '@/composables/useModalAccessibility'
import { createPagedRequestCoordinator } from '@/utils/pagedRequestCoordinator'
import { chooseRunPresentation, foregroundRunBlock, type BoardWorkflowPanel } from './runPresentation'
import { countResponseTriggerWarnings, getVerificationOutcome } from './verificationResult'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const setupStart = board.indexOf('<script setup')
const setup = board.slice(board.indexOf('>', setupStart) + 1, board.indexOf('</script>', setupStart))
const source = ts.createSourceFile('Board.ts', setup, ts.ScriptTarget.Latest, true)

// Execute the component's own functions so a missing guard fails the workflow assertion.
const bind = (name: string, ports: Record<string, unknown>) => {
  if (name.startsWith('pollAsync')) {
    ports.isWatchedTaskCancelled ??= bind('isWatchedTaskCancelled', ports)
    ports.ensureActiveWatchedTaskPoll ??= bind('ensureActiveWatchedTaskPoll', ports)
    ports.readWatchedTaskStatus ??= bind('readWatchedTaskStatus', ports)
    ports.isAsyncTaskCancelledError ??= (error: unknown) =>
      typeof error === 'object' && error !== null && 'name' in error && error.name === 'AsyncTaskCancelledError'
  }
  if (name === 'notifyTaskCancellationResult') {
    ports.hasVisibleCancelledTaskRow ??= bind('hasVisibleCancelledTaskRow', ports)
  }
  if (name === 'hasVisibleCancelledTaskRow') {
    ports.hasCancelledTaskRow ??= bind('hasCancelledTaskRow', ports)
  }
  const declaration = source.statements.find(statement => ts.isVariableStatement(statement)
    && statement.declarationList.declarations.some(item => item.name.getText(source) === name))
  if (!declaration) throw new Error(`Missing Board function ${name}`)
  const javascript = ts.transpileModule(declaration.getText(source), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None }
  }).outputText
  // Keep mutable component-scope counters live across awaits and deferred result actions.
  return new Function('ports', `with (ports) { ${javascript}; return ${name} }`)(ports)
}

const bindWorkflowPanel = (ports: Record<string, any>) => {
  ports.computed ??= computed
  ports.activeWorkflowPanel ??= ref<BoardWorkflowPanel | null>(null)
  ports.historyPanelIntentEpoch ??= 0
  ports.pendingHistoryRunTarget ??= null
  ports.historyDetailRequests ??= createPagedRequestCoordinator()
  for (const name of ['showVerificationPanel', 'showSimulationPanel', 'showFuzzingPanel', 'showHistoryPanel', 'tracksRun',
    'verificationPanelTracksRun', 'simulationPanelTracksRun', 'fuzzingPanelTracksRun', 'retireHistoryPanelIntent',
    'openRunPanel', 'setRunPanelView', 'closeWorkflowPanel']) ports[name] = bind(name, ports)
}

const bindCancellation = (suffix: string, ports: Record<string, any>) => {
  ports.computed ??= computed
  ports.pendingTaskActionKeys = ref(new Set<string>())
  for (const kind of ['verification', 'simulation', 'fuzzing']) ports[`${kind}Tasks`] ??= ref([])
  for (const name of ['taskActionKey', 'withTaskActionLock', `watched${suffix}TaskIds`, 'watchedTaskId',
    'isWatchedTaskCancelled', 'applyTaskCancellationReceipt',
    'isWatchedTaskCancellationPending', `cancelling${suffix}Task`, 'cancelTaskFromProgress', `cancelWatched${suffix}`]) {
    ports[name] = bind(name, ports)
  }
  return bind(`cancelAsync${suffix}`, ports)
}

const bindRun = (name: string, ports: Record<string, any>) => {
  ports.boardAuthScopeEpoch ??= 0
  ports.currentAuthUserId ??= ref(1)
  ports.isCurrentBoardAuthScope ??= (epoch: number) => epoch === ports.boardAuthScopeEpoch && !ports.boardLifecycleDisposed
  ports.captureBoardRunOwner = bind('captureBoardRunOwner', ports)
  ports.openModalDepth ??= ref(0)
  ports.isModelPlaybackActive ??= computed(() => !!(ports.traceAnimationState?.value.visible
    || ports.simulationAnimationState?.value.visible))
  Object.assign(ports, { chooseRunPresentation, foregroundRunBlock })
  ports.historyDetailRequests ??= createPagedRequestCoordinator()
  ports.historyDetailRequests.hasPendingReplace ??= () => false
  bindWorkflowPanel(ports)
  ports.hasVisibleCancelledTaskRow ??= bind('hasVisibleCancelledTaskRow', ports)
  ports.notifyObservedTaskCancellation ??= bind('notifyObservedTaskCancellation', ports)
  for (const name of ['automaticRunPresentationBlock', 'automaticRunPresentation', 'completeRunPresentation',
    'deliverCompletedFuzzingRun', 'deliverSimulationRunResult', 'deliverVerificationRunResult']) ports[name] = bind(name, ports)
  return bind(name, ports)
}

const deferred = () => {
  let resolve!: (value?: any) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<any>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

describe('side-panel opening focus', () => {
  it.each(['control', 'inspector'] as const)('focuses the selected %s tab instead of its collapse hint', async kind => {
    const panel = document.createElement('aside')
    panel.dataset.testid = kind === 'control' ? 'control-center' : 'system-inspector'
    panel.innerHTML = '<button class="board-panel-toggle">Expand</button><button role="tab" aria-selected="true">Devices</button>'
    document.body.append(panel)
    panel.querySelector('button')!.focus()
    const ports = { document, nextTick, layoutHydrated: ref(true), isNarrowViewport: () => true,
      boardPanels: { control: { collapsed: true }, inspector: { collapsed: true } }, focusSidePanelTab: undefined as any }
    ports.focusSidePanelTab = bind('focusSidePanelTab', ports)
    try {
      bind(kind === 'control' ? 'handleControlCollapsedUpdate' : 'handleInspectorCollapsedUpdate', ports)(false)
      await nextTick()
      expect(document.activeElement).toBe(panel.querySelector('[role="tab"]'))
      expect(ports.boardPanels[kind].collapsed).toBe(false)
    } finally { panel.remove() }
  })

  it('keeps focus in an active editor when a completed workflow panel closes', async () => {
    const open = ref(false)
    const ports = { isModelPlaybackActive: ref(false), openModalDepth, foregroundRunBlock }
    const options = bind('floatingPanelAccessibility', {
      automaticRunPresentationBlock: bind('automaticRunPresentationBlock', ports)
    })
    const wrapper = mount({
      setup() {
        const panel = useModalAccessibility(open, () => { open.value = false }, undefined, options)
        return () => h('div', [
          h('button', { id: 'workflow-opener' }, 'Open run'),
          open.value ? h('section', { ref: panel.setDialogRef, tabindex: -1 }, 'Progress') : null,
          h('input', { id: 'active-draft' })
        ])
      }
    }, { attachTo: document.body })
    const opener = wrapper.get<HTMLButtonElement>('#workflow-opener').element
    const draft = wrapper.get<HTMLInputElement>('#active-draft').element
    opener.focus()
    open.value = true
    await flushPromises()
    const release = registerModalSurface()
    try {
      draft.focus()
      open.value = false
      await flushPromises()
      expect(document.activeElement).toBe(draft)
    } finally {
      release()
      wrapper.unmount()
    }
  })
})

describe('tracking an existing background task', () => {
  it.each([
    { suffix: 'Verification', running: 'isVerifying', activeId: 7, requestedId: 8, isAsync: true },
    { suffix: 'Verification', running: 'isVerifying', activeId: 7, requestedId: 7, isAsync: false },
    { suffix: 'Simulation', running: 'isSimulating', activeId: 7, requestedId: 7, isAsync: false },
    { suffix: 'Fuzzing', running: 'isFuzzing', activeId: 7, requestedId: 8, isAsync: true }
  ])('keeps Task Status open when $suffix cannot attach to task $requestedId (async=$isAsync)',
    async ({ suffix, running, activeId, requestedId, isAsync }) => {
      const ports: Record<string, any> = {
        [running]: ref(true), ...(suffix === 'Fuzzing' ? {} : { [`async${suffix}Active`]: ref(isAsync) }),
        [`async${suffix}Task`]: ref({ taskId: activeId }),
        activeWorkflowPanel: ref<BoardWorkflowPanel>({ kind: 'history' }),
        notifyInfo: vi.fn(), t: (key: string) => key
      }
      await bindRun(`watch${suffix}Task`, ports)(requestedId)
      expect(ports.showHistoryPanel.value).toBe(true)
      expect(ports[`show${suffix}Panel`].value).toBe(false)
      expect(ports.notifyInfo).toHaveBeenCalledExactlyOnceWith('app.taskWatchAlreadyActive')
    })

  it('reattaches Task Status to the already watched asynchronous observation without polling twice', async () => {
    const ports: Record<string, any> = {
      isSimulating: ref(true), asyncSimulationActive: ref(true), asyncSimulationTask: ref({ taskId: 7 }),
      activeWorkflowPanel: ref<BoardWorkflowPanel>({ kind: 'history' }), pollAsyncSimulation: vi.fn(), notifyInfo: vi.fn()
    }
    await bindRun('watchSimulationTask', ports)(7)
    expect(ports.showSimulationPanel.value).toBe(true)
    expect(ports.asyncSimulationTask.value.taskId).toBe(7)
    expect(ports.showHistoryPanel.value).toBe(false)
    expect(ports.pollAsyncSimulation).not.toHaveBeenCalled()
    expect(ports.notifyInfo).not.toHaveBeenCalled()
  })

  it.each([
    { closePanel: true, playback: false, modal: false }, { closePanel: false, playback: true, modal: false },
    { closePanel: false, playback: false, modal: true }
  ])('keeps a watched exploration in the inbox after close=$closePanel, replay=$playback, modal=$modal', async ({ closePanel, playback, modal }) => {
    const pending = deferred()
    const ports = {
      isFuzzing: ref(false), fuzzingTasks: ref([]), fuzzingWatchedTask: ref(null), fuzzingSettingsNotice: ref(null),
      fuzzingCancelRequested: ref(false), cancellingFuzzingTask: ref(false),
      fuzzingError: ref(null), paperDomainStaleRecoveryActive: ref(false), asyncFuzzingTask: ref(null),
      normalizeTaskProgress: () => 0, taskProgressStatusKey: () => 'app.taskInitializing',
      trackFuzzTask: vi.fn(), untrackFuzzTask: vi.fn(), closeHistoryPanel: vi.fn(),
      pollAsyncFuzzing: () => pending.promise, isModelPlaybackActive: ref(playback),
      openModalDepth: ref(0),
      refreshCurrentBoardModelFingerprints: vi.fn(), presentFuzzingRun: vi.fn(), boardLifecycleDisposed: false,
      loadTaskInbox: vi.fn(), markFuzzNotificationUnread: vi.fn(() => true), fuzzingCompletionMessage: () => 'finished',
      notifyInfo: vi.fn(), notifyBlocked: vi.fn(), t: (key: string) => key
    }
    const watching = bindRun('watchFuzzingTask', ports)(17)
    if (closePanel) bind('closeWorkflowPanel', ports)('fuzzing')
    if (modal) ports.openModalDepth.value = 1
    pending.resolve({ id: 17, initiator: 'USER', outcome: 'BUDGET_EXHAUSTED' })
    await watching
    expect(ports.presentFuzzingRun).not.toHaveBeenCalled()
    expect(ports.markFuzzNotificationUnread).toHaveBeenCalledWith(expect.objectContaining({ runId: 17, kind: 'COMPLETED' }))
    const expected = [['finished']]
    if (playback) expected.push(['app.fuzzResultDeferredForPlayback'])
    if (modal) expected.push(['app.fuzzResultDeferredForModal'])
    expect(ports.notifyInfo.mock.calls).toEqual(expected)
  })

  it('drops completed exploration detail after its board is disposed', async () => {
    const pending = deferred()
    class PollingAbortedError extends Error {}
    const ports: Record<string, any> = {
      boardLifecycleDisposed: false, pollingEpoch: 0, ASYNC_TASK_MAX_POLLS: 1, PollingAbortedError,
      fuzzingApi: { getTask: vi.fn().mockResolvedValue({ id: 17, status: 'COMPLETED', runId: 17 }) },
      asyncFuzzingTask: ref({ taskId: 17, progress: 0 }), normalizeTaskProgress: () => 100,
      taskProgressStatusKey: () => 'app.taskStatusCompleted', upsertFuzzingTaskSummary: vi.fn(),
      loadFuzzRunSingleFlight: () => pending.promise, clearFuzzRunRecoveryState: vi.fn(),
      summarizeFuzzingRun: (run: unknown) => run, upsertFuzzingRunSummary: vi.fn()
    }
    ports.throwIfPollingAborted = bind('throwIfPollingAborted', ports)
    ports.isPollingAbortedError = bind('isPollingAbortedError', ports)
    const polling = bind('pollAsyncFuzzing', ports)(17)
    await flushPromises()
    ports.boardLifecycleDisposed = true
    pending.resolve({ id: 17 })
    await expect(polling).rejects.toBeInstanceOf(PollingAbortedError)
    expect(ports.upsertFuzzingRunSummary).not.toHaveBeenCalled()
  })

  it('continues polling when an exploration finishes before cancellation takes effect', async () => {
    const status = deferred()
    const cancellation = deferred()
    const nextPoll = deferred()
    const result = { id: 17, outcome: 'BUDGET_EXHAUSTED' }
    class AsyncTaskCancelledError extends Error {}
    const ports = {
      boardLifecycleDisposed: false, pollingEpoch: 0, ASYNC_TASK_MAX_POLLS: 2, AsyncTaskCancelledError,
      throwIfPollingAborted: vi.fn(), waitForNextPoll: vi.fn(() => nextPoll.promise),
      fuzzingApi: {
        getTask: vi.fn().mockReturnValueOnce(status.promise)
          .mockResolvedValueOnce({ id: 17, status: 'COMPLETED', runId: 17 }),
        cancelTask: vi.fn(() => cancellation.promise)
      },
      isFuzzing: ref(true), asyncFuzzingTask: ref({ taskId: 17 }), fuzzingCancelRequested: ref(false),
      fuzzingPanelTracksRun: ref(false),
      normalizeTaskProgress: () => 0, taskProgressStatusKey: vi.fn(), upsertFuzzingTaskSummary: vi.fn(),
      loadFuzzRunSingleFlight: vi.fn().mockResolvedValue(result), clearFuzzRunRecoveryState: vi.fn(),
      summarizeFuzzingRun: (run: unknown) => run, upsertFuzzingRunSummary: vi.fn(), notifyTaskCancellationResult: vi.fn()
    }
    const polling = bind('pollAsyncFuzzing', ports)(17).catch((error: unknown) => error)
    const cancelling = bindCancellation('Fuzzing', ports)()
    status.resolve({ id: 17, status: 'RUNNING' })
    await flushPromises()
    expect(ports.waitForNextPoll).toHaveBeenCalledOnce()

    cancellation.resolve({ cancellationAccepted: false, cancellationOutcome: 'ALREADY_COMPLETED', taskStatus: 'COMPLETED' })
    await cancelling
    nextPoll.resolve()
    expect(await polling).toBe(result)
    expect(ports.upsertFuzzingRunSummary).toHaveBeenCalledWith(result)
  })

  it.each([
    ['verification', 'Verification', 'boardApi'],
    ['simulation', 'Simulation', 'simulationApi'],
    ['fuzzing', 'Fuzzing', 'fuzzingApi']
  ])('keeps a new %s cancellation pending when the previous response arrives', async (kind, taskType, apiName) => {
    const previous = deferred()
    const current = deferred()
    const task = ref({ taskId: 17 })
    const requested = ref(false)
    const ports: Record<string, any> = {
      boardLifecycleDisposed: false, [`async${taskType}Task`]: task,
      [`${kind}CancelRequested`]: requested, [`async${taskType}Active`]: ref(true),
      isVerifying: ref(kind === 'verification'), isSimulating: ref(kind === 'simulation'), isFuzzing: ref(kind === 'fuzzing'),
      [apiName]: { cancelTask: vi.fn().mockReturnValueOnce(previous.promise).mockReturnValueOnce(current.promise) },
      notifyTaskCancellationResult: vi.fn(), taskProgressStatusKey: vi.fn()
    }
    bindWorkflowPanel(ports)
    const cancel = bindCancellation(taskType, ports)
    const cancelling = ports[`cancelling${taskType}Task`]
    const oldRequest = cancel()
    // The first task finished and released its slot before the acknowledgement reached the browser.
    task.value = { taskId: 18 }
    requested.value = false
    const currentRequest = cancel()
    previous.resolve({ taskId: 17, cancellationAccepted: true, cancellationOutcome: 'ACCEPTED', taskStatus: 'CANCELLED' })
    await oldRequest
    expect(cancelling.value).toBe(true)
    expect(requested.value).toBe(false)
    expect(ports.notifyTaskCancellationResult).not.toHaveBeenCalled()

    const accepted = { taskId: 18, cancellationAccepted: true, cancellationOutcome: 'ACCEPTED', taskStatus: 'CANCELLED' }
    current.resolve(accepted)
    await currentRequest
    expect(ports.notifyTaskCancellationResult).toHaveBeenCalledExactlyOnceWith(kind, accepted)
    expect(cancelling.value).toBe(false)
  })

  it.each([
    ['simulation', 'current', 'success'], ['simulation', 'disposed', 'success'], ['simulation', 'invalidated', 'success'],
    ['simulation', 'current', 'failure'], ['simulation', 'disposed', 'failure'], ['simulation', 'invalidated', 'failure'],
    ['verification', 'current', 'failure'], ['verification', 'disposed', 'failure'], ['verification', 'invalidated', 'failure']
  ] as const)(
    'keeps a completed %s owned through its final history refresh: %s / %s', async (kind, owner, outcome) => {
      const history = deferred()
      const loadHistory = vi.fn(() => history.promise)
      const result = { traceId: 17 }
      const resultFailure = new Error('Saved result unavailable')
      class PollingAbortedError extends Error {}
      const ports: Record<string, any> = {
        boardLifecycleDisposed: false, pollingEpoch: 0, ASYNC_TASK_MAX_POLLS: 1, PollingAbortedError,
        semanticSceneChangeCount: 0,
        simulationApi: { getTask: vi.fn().mockResolvedValue({
          id: 7, status: 'COMPLETED', progress: 100, simulationTraceId: 17
        }), getSimulation: vi.fn().mockResolvedValue(result) },
        asyncSimulationTask: ref({ taskId: 7, progress: 0, statusKey: '' }),
        boardApi: { getTask: vi.fn().mockResolvedValue({
          id: 7, status: 'COMPLETED', progress: 100, outcome: 'VIOLATED'
        }), getTaskTraces: vi.fn().mockRejectedValue(resultFailure) },
        asyncVerificationTask: ref({ taskId: 7, progress: 0, statusKey: '' }),
        normalizeTaskProgress: (value: number) => value, taskProgressStatusKey: () => 'app.taskStatusCompleted',
        upsertSimulationTaskSummary: vi.fn(), upsertVerificationTaskSummary: vi.fn(),
        loadCompletedTaskResult: (read: () => Promise<unknown>) => read(),
        simulationResultFromTrace: (trace: unknown) => trace,
        loadSimulationRuns: loadHistory, loadVerificationRuns: loadHistory
      }
      if (outcome === 'failure') ports.simulationApi.getSimulation.mockRejectedValue(resultFailure)
      ports.completedTaskResultError = (_kind: string, error: unknown) => error
      ports.throwIfPollingAborted = bind('throwIfPollingAborted', ports)
      ports.isPollingAbortedError = bind('isPollingAbortedError', ports)
      const polling = bind(kind === 'simulation' ? 'pollAsyncSimulation' : 'pollAsyncVerification', ports)(7)
      await flushPromises()
      expect(loadHistory).toHaveBeenCalledOnce()
      if (owner === 'disposed') ports.boardLifecycleDisposed = true
      if (owner === 'invalidated') ports.pollingEpoch += 1
      history.resolve(false)
      if (owner !== 'current') await expect(polling).rejects.toBeInstanceOf(PollingAbortedError)
      else if (outcome === 'failure') await expect(polling).rejects.toBe(resultFailure)
      else await expect(polling).resolves.toEqual(result)
    }
  )

  it.each([
    ['verification', 'Verification', 'boardApi'],
    ['simulation', 'Simulation', 'simulationApi'],
    ['fuzzing', 'Fuzzing', 'fuzzingApi']
  ])('opens %s progress and reports a confirmed local cancellation once', async (kind, suffix, api) => {
    let rejectPoll!: (error: Error) => void
    const ports: Record<string, any> = {
      boardLifecycleDisposed: false, semanticSceneChangeCount: 0,
      showHistoryPanel: ref(false), activeHistoryLayer: ref('results'),
      notifyInfo: vi.fn(), notifyBlocked: vi.fn(), closeHistoryPanel: vi.fn(),
      t: (key: string) => key, normalizeTaskProgress: () => 31,
      taskProgressStatusKey: () => 'app.taskStatusRunning',
      loadTaskInbox: vi.fn().mockResolvedValue(true),
      isPollingAbortedError: () => false, isAsyncTaskCancelledError: () => true,
      fuzzingWatchedTask: ref(null), fuzzingSettingsNotice: ref(null), paperDomainStaleRecoveryActive: ref(false),
      trackFuzzTask: vi.fn(), untrackFuzzTask: vi.fn(),
      [api]: { cancelTask: vi.fn().mockResolvedValue({ taskId: 7, cancellationAccepted: true,
        cancellationOutcome: 'ACCEPTED', taskStatus: 'CANCELLED', executionMayStillBeStopping: false }) },
      [`pollAsync${suffix}`]: vi.fn(() => new Promise((_resolve, reject) => { rejectPoll = reject }))
    }
    for (const taskKind of ['verification', 'simulation', 'fuzzing']) {
      const title = taskKind[0]!.toUpperCase() + taskKind.slice(1)
      ports[`is${taskKind === 'verification' ? 'Verifying' : taskKind === 'simulation' ? 'Simulating' : 'Fuzzing'}`] = ref(false)
      ports[`${taskKind}Tasks`] = ref([])
      ports[`${taskKind}Error`] = ref(null)
      if (taskKind !== 'fuzzing') ports[`async${title}Active`] = ref(false)
      ports[`async${title}Task`] = ref({ taskId: null })
      ports[`${taskKind}CancelRequested`] = ref(false)
    }
    ports.notifyTaskCancellationResult = bind('notifyTaskCancellationResult', ports)
    const watching = bindRun(`watch${suffix}Task`, ports)(7)
    try {
      expect(ports[`show${suffix}Panel`].value).toBe(true)
      ports.closeWorkflowPanel(kind)
      await bindCancellation(suffix, ports)()
      expect(ports.notifyInfo).toHaveBeenCalledOnce()
    } finally {
      rejectPoll(new Error('Task cancelled'))
      await watching
    }
    expect(ports.notifyInfo).toHaveBeenCalledOnce()
    expect(ports.loadTaskInbox).toHaveBeenCalledOnce()
  })

  it.each(['verification', 'simulation', 'fuzzing'] as const)('uses visible %s status instead of a duplicate cancellation toast', kind => {
    const ports: Record<string, any> = { t: (key: string, params?: { task: string }) => params ? `${key} ${params.task}` : key,
      notifyInfo: vi.fn(), notifyBlocked: vi.fn(), activeHistoryLayer: ref('tasks'),
      activeWorkflowPanel: ref<BoardWorkflowPanel | null>({ kind: 'history' }),
      [`${kind}Tasks`]: ref([{ id: 7, status: 'CANCELLED' }]),
      [`watched${kind[0]!.toUpperCase()}${kind.slice(1)}TaskIds`]: ref([7]) }
    bindWorkflowPanel(ports)
    ports.watchedTaskId = bind('watchedTaskId', ports)
    const notify = bind('notifyTaskCancellationResult', ports)
    const result = { taskId: 7, cancellationOutcome: 'ACCEPTED', executionMayStillBeStopping: true }
    notify(kind, result)
    expect(ports.notifyInfo).not.toHaveBeenCalled()
    notify(kind, { ...result, taskId: 8 })
    expect(ports.notifyInfo).toHaveBeenCalledExactlyOnceWith(expect.stringContaining(' #8'))
    ports.notifyInfo.mockClear()
    ports.openRunPanel(kind, 'run')
    notify(kind, result)
    expect(ports.notifyInfo).not.toHaveBeenCalled()
    notify(kind, { ...result, taskId: 8, cancellationOutcome: 'ALREADY_CANCELLED' })
    expect(ports.notifyInfo).toHaveBeenCalledExactlyOnceWith(expect.stringContaining(' #8'))
    ports.notifyInfo.mockClear()
    ports.closeWorkflowPanel(kind)
    notify(kind, result)
    expect(ports.notifyInfo).toHaveBeenCalledOnce()
  })

  it.each(['verification', 'simulation'])('avoids a late cancellation notice over the matching %s result', kind => {
    const ports = { t: (key: string) => key, notifyInfo: vi.fn(), notifyBlocked: vi.fn(),
      activeHistoryLayer: ref('tasks'),
      showResultDialog: ref(true), verificationResult: ref({ historyPersistence: { status: 'SAVED', runId: 7 } }),
      simulationAnimationState: ref({ visible: true }),
      lastSimulationResult: ref({ historyPersistence: { status: 'SAVED', runId: 17 } }),
      simulationTasks: ref([{ id: 7, simulationTraceId: 17 }]) }
    bindWorkflowPanel(ports)
    const notify = bind('notifyTaskCancellationResult', ports)
    notify(kind, { taskId: 7, cancellationOutcome: 'ALREADY_COMPLETED' })
    expect(ports.notifyBlocked).not.toHaveBeenCalled()
    notify(kind, { taskId: 8, cancellationOutcome: 'ALREADY_COMPLETED' })
    expect(ports.notifyBlocked).toHaveBeenCalledOnce()
    ports.showResultDialog.value = false
    ports.simulationAnimationState.value.visible = false
    notify(kind, { taskId: 7, cancellationOutcome: 'ALREADY_COMPLETED' })
    expect(ports.notifyBlocked).toHaveBeenCalledTimes(2)
  })
})

describe('run navigation leaves playback', () => {
  it.each(['counterexample', 'simulation'] as const)('closes %s playback before restoring a result without clearing the new address', async kind => {
    const target = { kind: 'verification', runId: 19 }
    const ports: Record<string, any> = {
      deepLinkLoadRequests: { begin: () => 1 }, appliedDeepLinkTarget: null,
      traceAnimationState: ref({ visible: kind === 'counterexample' }),
      simulationAnimationState: ref({ visible: kind === 'simulation' }),
      highlightedTrace: ref({ states: [{}] }), activeFuzzingFinding: ref({ id: 1 }),
      stopTraceAnimation: vi.fn(), deactivatePlaybackScene: vi.fn(), resetPlaybackTerminal: vi.fn(),
      clearRunDeepLink: vi.fn(), closeResultSurfaces: vi.fn(), loadDeepLinkTarget: vi.fn(),
      pendingHistoryRunTarget: null
    }
    for (const name of ['closeTraceAnimation', 'closeSimulationTimeline', 'closePlaybackForNavigation', 'applyDeepLinkTarget']) {
      if (name === 'closePlaybackForNavigation' && !setup.includes(`const ${name} =`)) continue
      ports[name] = bind(name, ports)
    }
    await ports.applyDeepLinkTarget(target)
    expect(ports.traceAnimationState.value.visible).toBe(false)
    expect(ports.simulationAnimationState.value.visible).toBe(false)
    expect(ports.highlightedTrace.value).toBeNull()
    expect(ports.deactivatePlaybackScene).toHaveBeenCalledOnce()
    expect(ports.resetPlaybackTerminal).toHaveBeenCalledOnce()
    expect(ports.clearRunDeepLink).not.toHaveBeenCalled()
    expect(ports.appliedDeepLinkTarget).toEqual(target)
    expect(ports.loadDeepLinkTarget).toHaveBeenCalledWith(target, { requestEpoch: 1 })
  })
})

describe('reopening verification settings from a stale result', () => {
  const settings = { kind: 'verification', view: 'settings', handoff: null } as const
  const setupSettings = (selected: BoardWorkflowPanel | null, blocker?: 'playback' | 'recommendation' | 'verification') => {
    const ports: Record<string, any> = {
      isModelPlaybackActive: ref(blocker === 'playback'),
      isAnyRecommendationRunning: () => blocker === 'recommendation',
      isSceneReplacementInProgress: ref(false), isVerifying: ref(blocker === 'verification'),
      isFuzzing: ref(false), isSimulating: ref(false), activeWorkflowPanel: ref(selected),
      notifyBlocked: vi.fn(), notifyInfo: vi.fn(), t: (key: string) => key, dismissResultDialog: vi.fn()
    }
    bindWorkflowPanel(ports)
    for (const name of ['canOpenWorkflowPanel', 'isRunKindActive', 'canOpenTaskSettings']) ports[name] = bind(name, ports)
    return ports
  }

  it.each([false, true])('opens settings when their previous visibility was %s', alreadyOpen => {
    const ports = setupSettings(alreadyOpen ? settings : null)
    bind('reopenVerificationSettingsFromResult', ports)()
    expect(ports.dismissResultDialog).toHaveBeenCalledOnce()
    expect(ports.activeWorkflowPanel.value).toEqual(settings)
  })

  it.each(['playback', 'recommendation', 'verification'] as const)('keeps the result visible when %s blocks settings', blocker => {
    const ports = setupSettings(null, blocker)
    const reason = { playback: 'app.playbackReadOnlyCloseFirst', recommendation: 'app.recommendationGenerationInProgress',
      verification: 'app.taskSettingsBlockedByActiveRun' }[blocker]
    bind('reopenVerificationSettingsFromResult', ports)()
    expect(ports.dismissResultDialog).not.toHaveBeenCalled()
    expect(ports.activeWorkflowPanel.value).toBeNull()
    expect(blocker === 'verification' ? ports.notifyInfo : ports.notifyBlocked).toHaveBeenCalledExactlyOnceWith(reason)
  })
})

describe('exploration finding handoff', () => {
  it('drops the handoff when History closes its panel and an unrelated verdict task is watched', async () => {
    const ports: Record<string, any> = {
      activeWorkflowPanel: ref<BoardWorkflowPanel | null>({ kind: 'verification', view: 'settings',
        handoff: { runId: 24, specificationId: 'old-target' } }),
      closeResultSurfaces: vi.fn(), closeHistoryPanel: vi.fn(), pollAsyncVerification: vi.fn(),
      verificationTasks: ref([]), verificationError: ref(null), isVerifying: ref(false),
      asyncVerificationActive: ref(false), verificationCancelRequested: ref(false),
      cancellingVerificationTask: ref(false), asyncVerificationTask: ref(null),
      normalizeTaskProgress: () => 0, taskProgressStatusKey: () => 'app.taskInitializing', loadTaskInbox: vi.fn()
    }

    const watchVerification = bindRun('watchVerificationTask', ports)
    bind('closeOtherWorkflowSurfaces', ports)()
    expect(ports.showVerificationPanel.value).toBe(false)
    expect(ports.activeWorkflowPanel.value).toBeNull()
    await watchVerification(17)
    expect(ports.showVerificationPanel.value).toBe(true)
    expect(ports.activeWorkflowPanel.value?.handoff).toBeNull()
    expect(ports.pollAsyncVerification).toHaveBeenCalledExactlyOnceWith(17)
  })

  it('keeps the historical target while the current Board comparison and target presence change', () => {
    const original = 'a'.repeat(64)
    const modelSnapshot: ModelRunSnapshot = {
      capturedAt: '2026-11-25T12:00:00', deviceCount: 1, ruleCount: 1, specificationCount: 1,
      environmentVariableCount: 0, deviceTemplateCount: 1, templatesFrozen: true,
      modelFingerprint: original, modelSemanticsVersion: 7
    }
    const sourceRun = { id: 24, modelSnapshot }
    const fuzzingResult = ref<typeof sourceRun | null>(sourceRun)
    const currentBoardModelFingerprints = ref<BoardModelFingerprints | null>({
      verification: original, simulation: original, modelSemanticsVersion: 7
    })
    const boardDataLoadState = reactive({ specs: 'ready' })
    const ports: Record<string, any> = {
      computed, ref, compareModelSnapshot, currentBoardModelFingerprints,
      fuzzingResult, fuzzingRuns: ref([]), activeWorkflowPanel: ref({ kind: 'fuzzing', view: 'settings' }),
      specifications: ref([{ id: 'spec-a', name: 'Current name' }]),
      boardDataLoadState, allBoardDataKeys: ['specs'],
      isBoardDataReady: computed(() => boardDataLoadState.specs === 'ready'),
      loadingCurrentBoardModelFingerprints: ref(false),
      closeHistoryPanel: vi.fn(), dismissFuzzingResult: () => { fuzzingResult.value = null }
    }
    bindWorkflowPanel(ports)
    for (const name of ['fuzzVerificationHandoff', 'fuzzVerificationHandoffNoticeKey',
      'availableFuzzRunForFinding', 'showVerificationPanelForHandoff']) {
      ports[name] = bind(name, ports)
    }

    bind('openFormalVerificationForFuzzFinding', ports)({
      fuzzTaskId: 24, violatedSpecId: 'spec-a', specificationLabel: 'Captured target'
    })
    expect(fuzzingResult.value).toBeNull()
    expect(ports.showVerificationPanel.value).toBe(true)
    expect(ports.fuzzVerificationHandoff.value).toMatchObject({
      runId: 24, specificationLabel: 'Captured target', targetPresent: true, boardComparison: 'UNCHANGED'
    })

    // A Board reload leaves the old collections in place while admission is still unknown.
    boardDataLoadState.specs = 'loading'
    expect(ports.fuzzVerificationHandoff.value).toMatchObject({
      targetPresent: null, boardComparison: 'UNAVAILABLE'
    })
    expect(ports.fuzzVerificationHandoffNoticeKey.value).toBe('app.fuzzVerificationHandoffChecking')

    boardDataLoadState.specs = 'ready'
    currentBoardModelFingerprints.value = {
      verification: 'b'.repeat(64), simulation: original, modelSemanticsVersion: 7
    }
    expect(ports.fuzzVerificationHandoffNoticeKey.value).toBe('app.fuzzVerificationHandoffScopeChanged')

    ports.specifications.value = [{ id: 'spec-b', name: 'Replacement target' }]
    expect(ports.fuzzVerificationHandoff.value).toMatchObject({
      runId: 24, specificationLabel: 'Captured target', targetPresent: false, boardComparison: 'CHANGED'
    })
    expect(ports.fuzzVerificationHandoffNoticeKey.value).toBe('app.fuzzVerificationHandoffTargetMissing')
    boardDataLoadState.specs = 'error'
    expect(ports.fuzzVerificationHandoffNoticeKey.value).toBe('app.fuzzVerificationHandoffTargetUnavailable')
  })
})

describe('run failures during playback', () => {
  const context = (playback: boolean) => {
    const owner = { current: true }
    const ports: Record<string, any> = {
      isModelPlaybackActive: ref(playback), openModalDepth: ref(0), verificationError: ref<string | null>(null),
      chooseRunPresentation, foregroundRunBlock,
      historyDetailRequests: createPagedRequestCoordinator(),
      simulationError: ref<any>(null), technicalErrorLines, computed,
      isCompletedTaskResultUnavailableError: () => false, isFuzzCompletedResultUnavailableError: () => false,
      RUN_RESPONSE_INCOMPLETE_CODE: 'RUN_RESPONSE_INCOMPLETE', FUZZ_RESPONSE_INCOMPLETE_CODE: 'FUZZ_RESPONSE_INCOMPLETE',
      formalOperationBusyMessage: () => null, asyncTaskQuotaMessage: () => null,
      notifyError: vi.fn(), errorDetails, t: (key: string) => key,
      boardAuthScopeEpoch: 3, currentAuthUserId: ref<number | null>(1),
      isCurrentBoardAuthScope: (epoch: number) => owner.current && epoch === 3,
      unsavedRunNotifications: new Set<() => void>(), notifyAvailableResult: vi.fn(),
      closeOtherWorkflowSurfaces: vi.fn(),
      ensureHistoricalPlaybackUiAdmission: vi.fn(() => !ports.isModelPlaybackActive.value)
    }
    ports.notifyAvailableResult.mockImplementation((options: { onClose: () => void }) => vi.fn(options.onClose))
    ports.makeRunFailure = bind('makeRunFailure', ports)
    ports.automaticRunPresentationBlock = bind('automaticRunPresentationBlock', ports)
    ports.automaticRunPresentation = bind('automaticRunPresentation', ports)
    ports.formatRunFailure = bind('formatRunFailure', ports)
    ports.runFailureTitle = bind('runFailureTitle', ports)
    ports.runFailureDiagnostics = bind('runFailureDiagnostics', ports)
    return { ports, owner, present: bind('presentRunFailure', ports) }
  }
  const failure = () => ({
    message: 'Request failed', response: { status: 422, data: {
      message: 'Invalid run settings', data: { errors: { steps: 'Must be positive' }, logs: ['original diagnostic'] }
    } }
  })

  it.each(['verification', 'simulation'] as const)('retains a %s failure without opening a modal over replay', kind => {
    const { ports, present } = context(true)
    present(kind, `app.${kind}Failed`, failure())
    expect(ports.verificationError.value).toBeNull()
    expect(ports.simulationError.value).toBeNull()
    expect(ports.notifyError).not.toHaveBeenCalled()
    const notice = ports.notifyAvailableResult.mock.calls[0]![0]
    expect(notice.title()).toBe(`app.${kind}Failed`)
    expect(notice.message()).toBe(`app.${kind}Failed`)
    expect(notice.actionLabel()).toBe('app.viewDetails')
    expect(notice.discardLabel()).toBe('app.close')
    expect(ports.unsavedRunNotifications.size).toBe(1)
    expect(notice.onAction()).toBe(false)
    expect(ports.closeOtherWorkflowSurfaces).not.toHaveBeenCalled()
    expect(ports.verificationError.value).toBeNull()
    expect(ports.simulationError.value).toBeNull()
  })

  it.each(['verification', 'simulation'] as const)('opens only the %s error surface when playback is absent', kind => {
    const { ports, present } = context(false)
    present(kind, `app.${kind}Failed`, failure())
    expect(ports[kind === 'verification' ? 'verificationError' : 'simulationError'].value.key).toBe(`app.${kind}Failed`)
    expect(ports.runFailureDiagnostics.value[kind]).toMatchObject({
      details: expect.arrayContaining(['Invalid run settings', 'steps: Must be positive']),
      suggestion: 'app.runValidationNextStep', message: `app.${kind}Failed`
    })
    expect(ports.notifyError).not.toHaveBeenCalled()
    expect(ports.notifyAvailableResult).not.toHaveBeenCalled()
  })

  it('opens the retained diagnostics after admission, then releases their notification owner', () => {
    const { ports, present } = context(true)
    const error = failure()
    present('verification', 'app.verificationFailed', error)
    error.response.data.data.errors.steps = 'later response'
    expect(ports.notifyAvailableResult).toHaveBeenCalledOnce()
    const notice = ports.notifyAvailableResult.mock.calls[0]![0]
    expect(notice.onAction()).toBe(false)
    ports.isModelPlaybackActive.value = false
    expect(notice.onAction()).toBe(true)
    expect(ports.closeOtherWorkflowSurfaces).toHaveBeenCalledOnce()
    expect(ports.verificationError.value.key).toBe('app.verificationFailed')
    expect(ports.runFailureDiagnostics.value.verification).toMatchObject({
      details: expect.arrayContaining(['steps: Must be positive', 'original diagnostic', 'HTTP 422'])
    })
    const close = [...ports.unsavedRunNotifications][0]!
    close()
    expect(ports.unsavedRunNotifications.size).toBe(0)
  })

  it.each(['scope', 'account'] as const)('does not open retained errors for a stale %s owner', reason => {
    const { ports, owner, present } = context(true)
    present('simulation', 'app.simulationFailed', new Error('Connection closed'))
    expect(ports.unsavedRunNotifications.size).toBe(1)
    if (reason === 'scope') owner.current = false
    else ports.currentAuthUserId.value = 2
    ports.isModelPlaybackActive.value = false
    expect(ports.notifyAvailableResult.mock.calls[0]![0].onAction()).toBe(true)
    expect(ports.simulationError.value).toBeNull()
    expect(ports.closeOtherWorkflowSurfaces).not.toHaveBeenCalled()
  })

  it('discards a failure without claiming a result or opening an error surface', () => {
    const { ports, present } = context(true)
    present('simulation', 'app.simulationFailed', new Error('Connection closed'))
    expect(ports.unsavedRunNotifications.size).toBe(1)
    const close = [...ports.unsavedRunNotifications][0]!
    close()
    expect(ports.unsavedRunNotifications.size).toBe(0)
    expect(ports.simulationError.value).toBeNull()
    expect(ports.runFailureDiagnostics.value).toEqual({ verification: null, simulation: null })
  })

  it.each([
    ['verification', true], ['simulation', true], ['verification', false]
  ] as const)('defers a watched %s failure when replay is active=%s and presentation is unavailable', async (kind, playback) => {
    const failure = context(playback).ports
    const pending = deferred()
    const flag = () => ({ value: false })
    const ports = {
      isVerifying: flag(), isSimulating: flag(),
      asyncVerificationTask: { value: { taskId: null } }, asyncSimulationTask: { value: { taskId: null } },
      notifyInfo: vi.fn(),
      verificationTasks: { value: [] }, simulationTasks: { value: [] },
      asyncVerificationActive: flag(), asyncSimulationActive: flag(),
      verificationCancelRequested: flag(), simulationCancelRequested: flag(),
      cancellingVerificationTask: flag(), cancellingSimulationTask: flag(),
      normalizeTaskProgress: () => 0, taskProgressStatusKey: () => 'app.taskInitializing', closeHistoryPanel: vi.fn(),
      pollAsyncVerification: vi.fn(() => pending.promise),
      pollAsyncSimulation: vi.fn(() => pending.promise),
      isPollingAbortedError: () => false, isCompletedTaskResultUnavailableError: () => false,
      extractApiErrorMessage: (error: Error) => error.message, isAsyncTaskCancelledError: () => false,
      ...failure, presentRunFailure: bind('presentRunFailure', failure),
      semanticSceneChangeCount: 0, boardLifecycleDisposed: false, loadTaskInbox: vi.fn().mockResolvedValue(true)
    }
    const watching = bindRun(kind === 'verification' ? 'watchVerificationTask' : 'watchSimulationTask', ports)(7)
    if (!playback) bind('closeWorkflowPanel', ports)('verification')
    pending.resolve(Promise.reject(new Error('run failed')))
    await watching
    expect(failure.verificationError.value).toBeNull()
    expect(failure.simulationError.value).toBeNull()
    expect(failure.notifyError).not.toHaveBeenCalled()
    expect(failure.notifyAvailableResult).toHaveBeenCalledOnce()
  })

  it.each(['verification', 'simulation'] as const)('does not clear an active replay link when dismissing a %s error', kind => {
    const clearRunDeepLink = vi.fn()
    const invalidate = vi.fn()
    bind(kind === 'verification' ? 'dismissResultDialog' : 'dismissSimulationResultDialog', {
      verificationResult: { value: null }, isModelPlaybackActive: { value: true }, deepLinkTarget: { value: { kind: 'simulation', runId: 9 } },
      historyDetailRequests: { invalidate }, closeResultDialog: vi.fn(), closeSimulationResultDialog: vi.fn(),
      clearRunDeepLink
    })()
    expect(clearRunDeepLink).not.toHaveBeenCalled()
    expect(invalidate).not.toHaveBeenCalled()
  })

  it('clears a displayed verification result only while its run still owns the URL', () => {
    const clearRunDeepLink = vi.fn()
    const invalidate = vi.fn()
    const target = { value: { kind: 'verification', runId: 9 } }
    const result = { value: { historyPersistence: { runId: 7 } } }
    const dismiss = bind('dismissResultDialog', {
      isModelPlaybackActive: { value: false }, deepLinkTarget: target, verificationResult: result,
      historyDetailRequests: { invalidate }, closeResultDialog: vi.fn(), clearRunDeepLink
    })
    dismiss()
    expect(clearRunDeepLink).not.toHaveBeenCalled()
    expect(invalidate).not.toHaveBeenCalled()
    result.value.historyPersistence.runId = 9
    dismiss()
    expect(clearRunDeepLink).toHaveBeenCalledOnce()
    expect(invalidate).toHaveBeenCalledOnce()
  })

  it.each(['verification', 'simulation'] as const)('preserves a pending same-kind run link when dismissing a %s error', kind => {
    const clearRunDeepLink = vi.fn()
    const invalidate = vi.fn()
    bind(kind === 'verification' ? 'dismissResultDialog' : 'dismissSimulationResultDialog', {
      isModelPlaybackActive: { value: false }, deepLinkTarget: { value: { kind, runId: 9 } },
      verificationResult: { value: null }, historyDetailRequests: { invalidate },
      closeResultDialog: vi.fn(), closeSimulationResultDialog: vi.fn(), clearRunDeepLink
    })()
    expect(clearRunDeepLink).not.toHaveBeenCalled()
    expect(invalidate).not.toHaveBeenCalled()
  })

  it('clears a deleted simulation link only for the deletion owner', () => {
    const clearRunDeepLink = vi.fn()
    const dismiss = bind('dismissSimulationResultDialog', {
      isModelPlaybackActive: { value: false }, deepLinkTarget: { value: { kind: 'simulation', runId: 9 } },
      closeSimulationResultDialog: vi.fn(), clearRunDeepLink
    })
    dismiss(7)
    expect(clearRunDeepLink).not.toHaveBeenCalled()
    dismiss(9)
    expect(clearRunDeepLink).toHaveBeenCalledOnce()
  })
})

describe('exploration launch', () => {
  const context = (visible: boolean, refreshed: boolean, validPath = true) => {
    const ports: Record<string, any> = {
      isFuzzing: ref(false), isSceneReplacementInProgress: ref(false),
      boardAuthScopeEpoch: 3, currentAuthUserId: ref<number | null>(1),
      isCurrentBoardAuthScope: (epoch: number) => epoch === 3 && !ports.boardLifecycleDisposed,
      waitForPendingFixRefresh: vi.fn(), waitForPendingBoardMutations: vi.fn(),
      ensureBoardDataReady: () => true, nodes: ref([{}]), specifications: ref([{ id: 'spec-1' }]),
      rules: ref([]), assertRulesHaveTriggers: () => true,
      fuzzingWorkloadReady: ref(true), fuzzingContentCommandUnsupported: ref(false),
      knownFuzzEligibleSpecifications: ref([{ id: 'spec-1' }]), effectiveFuzzingConfigurationError: ref(null),
      fuzzingForm: { explorationMode: 'PAPER_COMPATIBLE', targetSelectionMode: 'ALL',
        maxIterations: 2, populationSize: 1, pathLength: 2, seed: 42 },
      paperDomainPreview: ref({ modelFingerprint: 'a'.repeat(64) }), isValidFuzzPaperDomainFingerprint: () => true,
      fuzzingWatchedTask: ref(null), cancellingFuzzingTask: ref(false),
      fuzzingCancelRequested: ref(false), fuzzingError: ref<string | null>(null), fuzzingSettingsNotice: ref(null),
      asyncFuzzingTask: ref({}), activeWorkflowPanel: ref<BoardWorkflowPanel | null>(visible ? { kind: 'fuzzing', view: 'settings' } : null),
      fuzzingFailureDetails: ref({}),
      paperDomainStaleRecoveryActive: ref(false),
      fuzzingApi: { startAsync: vi.fn().mockRejectedValue({ response: { status: 422, data: {
        message: 'Paper domain changed', data: { errors: { paperDomainFingerprint: 'Refresh the domain preview' } }
      } } }) },
      isPollingAbortedError: () => false, isAsyncTaskCancelledError: () => false,
      errorDetails, hasApiValidationError: null as any, refreshSceneForReconciliation: vi.fn().mockResolvedValue(refreshed),
      markVerificationResultStale: vi.fn(), invalidatePaperDomainPreview: vi.fn(),
      validPaperPathLength: () => validPath, schedulePaperDomainPreview: vi.fn(),
      notifyBlocked: vi.fn(), notifyError: vi.fn(), boardLifecycleDisposed: false,
      loadTaskInbox: vi.fn().mockResolvedValue(true), t: (key: string) => key
    }
    Object.assign(ports, { computed, technicalErrorLines, fuzzTaskQuotaMessage: () => null,
      isCompletedTaskResultUnavailableError: () => false, isFuzzCompletedResultUnavailableError: () => false,
      RUN_RESPONSE_INCOMPLETE_CODE: 'RUN_RESPONSE_INCOMPLETE', FUZZ_RESPONSE_INCOMPLETE_CODE: 'FUZZ_RESPONSE_INCOMPLETE' })
    ports.makeRunFailure = bind('makeRunFailure', ports)
    ports.formatRunFailure = bind('formatRunFailure', ports)
    ports.runFailureTitle = bind('runFailureTitle', ports)
    ports.fuzzingFailureDetails = bind('fuzzingFailureDetails', ports)
    ports.fuzzingErrorMessage = bind('fuzzingErrorMessage', ports)
    ports.hasApiValidationError = bind('hasApiValidationError', ports)
    return { ports, run: bindRun('runFuzzing', ports) }
  }

  it('keeps only one submitter while waiting for a pending board edit', async () => {
    const { ports, run } = context(true, false)
    const boardEdit = deferred()
    ports.waitForPendingBoardMutations.mockReturnValue(boardEdit.promise)
    const first = run()
    const duplicate = run()
    await flushPromises()
    expect(ports.fuzzingApi.startAsync).not.toHaveBeenCalled()
    boardEdit.resolve()
    await Promise.all([first, duplicate])
    expect(ports.fuzzingApi.startAsync).toHaveBeenCalledOnce()
  })

  const watchPreviews = (ports: Record<string, any>) => {
    const modelFingerprint = 'a'.repeat(64)
    Object.assign(ports, {
      watch, hasValidFuzzingBudget, isFuzzingPreviewCurrent,
      paperDomainSemanticKey: ref('unchanged-board'), fuzzingWorkloadSemanticKey: ref('current-budget'),
      fuzzingPreviewPrerequisitesReady: ref(true), paperDomainPreview: ref({ pathLength: 2, modelFingerprint }),
      paperDomainPreviewSemanticKey: ref('unchanged-board'), paperDomainPreviewEpoch: 0, paperDomainPreviewTimer: null,
      paperDomainPreviewLoading: ref(false), paperDomainPreviewError: ref(null),
      fuzzingWorkloadPreview: ref({ maxIterations: 2, populationSize: 1, pathLength: 2 }),
      fuzzingWorkloadPreviewSemanticKey: ref('current-budget'), fuzzingWorkloadPreviewEpoch: 0,
      fuzzingWorkloadPreviewTimer: null, fuzzingWorkloadPreviewLoading: ref(false), fuzzingWorkloadPreviewError: ref(null),
      scheduleFuzzingWorkloadPreview: vi.fn(),
      trackFuzzTask: vi.fn(), upsertFuzzingTaskSummary: vi.fn(), normalizeTaskProgress: () => 0,
      taskProgressStatusKey: () => 'app.taskInitializing', pollAsyncFuzzing: vi.fn().mockResolvedValue({ id: 17 }),
      deliverCompletedFuzzingRun: vi.fn()
    })
    for (const name of ['fuzzingPreviewsInUse', 'invalidatePaperDomainPreview', 'invalidateFuzzingWorkloadPreview',
      'paperDomainReady', 'fuzzingWorkloadReady', 'paperDomainConfigurationError']) ports[name] = bind(name, ports)
    ports.effectiveFuzzingConfigurationError = ports.paperDomainConfigurationError
    const previewWatchers = source.statements.filter(statement => ts.isExpressionStatement(statement)
      && ts.isCallExpression(statement.expression) && statement.expression.expression.getText(source) === 'watch'
      && /paperDomainSemanticKey|fuzzingWorkloadSemanticKey|schedulePaperDomainPreview|scheduleFuzzingWorkloadPreview/
        .test(statement.getText(source)))
    const scope = effectScope()
    scope.run(() => new Function('ports', `with (ports) { ${ts.transpileModule(
      previewWatchers.map(statement => statement.getText(source)).join('\n'),
      { compilerOptions: { target: ts.ScriptTarget.ES2022 } }
    ).outputText} }`)(ports))
    return { scope, modelFingerprint }
  }

  it('submits the approved exploration after opening History while a layout save settles', async () => {
    const { ports, run } = context(true, false)
    const boardSave = deferred()
    const { scope, modelFingerprint } = watchPreviews(ports)
    ports.waitForPendingBoardMutations.mockReturnValue(boardSave.promise)
    ports.fuzzingApi.startAsync.mockResolvedValue({ id: 17, initiator: 'USER' })

    try {
      expect(ports.fuzzingWorkloadReady.value).toBe(true)
      expect(ports.paperDomainReady.value).toBe(true)
      const completion = run()
      await flushPromises()
      expect(ports.waitForPendingBoardMutations).toHaveBeenCalledOnce()
      expect(ports.fuzzingApi.startAsync).not.toHaveBeenCalled()
      ports.activeWorkflowPanel.value = { kind: 'history' }
      boardSave.resolve()
      expect(await completion).toBe(true)
      expect(ports.fuzzingApi.startAsync).toHaveBeenCalledExactlyOnceWith({
        explorationMode: 'PAPER_COMPATIBLE', maxIterations: 2, populationSize: 1, pathLength: 2,
        seed: 42, paperDomainFingerprint: modelFingerprint
      })
      expect(ports.activeWorkflowPanel.value).toEqual({ kind: 'history' })
    } finally {
      scope.stop()
    }
  })

  it.each([true, false])('only reads draft previews while watching when the frozen summary is absent (summary=%s)', async hasSummary => {
    const { ports } = context(false, false)
    const { scope, modelFingerprint } = watchPreviews(ports)
    const pending = deferred()
    const aborted = new Error('Stop watching')
    Object.assign(ports, {
      fuzzingTasks: ref(hasSummary ? [{ id: 17 }] : []),
      pollAsyncFuzzing: () => pending.promise, isPollingAbortedError: (error: unknown) => error === aborted
    })
    const previewPaperDomain = vi.fn().mockResolvedValue({ pathLength: 2, modelFingerprint })
    const previewWorkload = vi.fn().mockResolvedValue({ maxIterations: 2, populationSize: 1, pathLength: 2 })
    Object.assign(ports.fuzzingApi, { previewPaperDomain, previewWorkload })
    for (const name of ['refreshPaperDomainPreview', 'refreshFuzzingWorkloadPreview',
      'schedulePaperDomainPreview', 'scheduleFuzzingWorkloadPreview']) ports[name] = bind(name, ports)

    vi.useFakeTimers()
    try {
      const tracking = bindRun('watchFuzzingTask', ports)(17)
      expect(ports.activeWorkflowPanel.value).toEqual({ kind: 'fuzzing', view: 'run' })
      await vi.advanceTimersByTimeAsync(250)
      expect.soft(previewPaperDomain).toHaveBeenCalledTimes(hasSummary ? 0 : 1)
      expect.soft(previewWorkload).toHaveBeenCalledTimes(hasSummary ? 0 : 1)

      pending.resolve(Promise.reject(aborted))
      await tracking
      expect(ports.activeWorkflowPanel.value).toEqual({ kind: 'fuzzing', view: 'settings' })
      await vi.advanceTimersByTimeAsync(250)
      expect(previewPaperDomain).toHaveBeenCalledOnce()
      expect(previewWorkload).toHaveBeenCalledOnce()
    } finally {
      scope.stop()
      vi.useRealTimers()
    }
  })

  it('leaves the new account untouched when an old exploration launch is accepted', async () => {
    const { ports, run } = context(true, false)
    const accepted = deferred()
    const closed = new Error('Board disposed')
    ports.fuzzingApi.startAsync.mockReturnValue(accepted.promise)
    Object.assign(ports, {
      trackFuzzTask: vi.fn(), upsertFuzzingTaskSummary: vi.fn(), normalizeTaskProgress: () => 0,
      taskProgressStatusKey: () => 'app.taskInitializing',
      pollAsyncFuzzing: vi.fn().mockRejectedValue(closed), isPollingAbortedError: (error: unknown) => error === closed
    })
    const completion = run()
    await vi.waitFor(() => expect(ports.fuzzingApi.startAsync).toHaveBeenCalledOnce())
    ports.currentAuthUserId.value = 2
    ports.boardLifecycleDisposed = true
    accepted.resolve({ id: 17, initiator: 'USER' })
    expect(await completion).toBe(false)
    expect(ports.trackFuzzTask).not.toHaveBeenCalled()
    expect(ports.upsertFuzzingTaskSummary).not.toHaveBeenCalled()
    expect(ports.pollAsyncFuzzing).not.toHaveBeenCalled()
  })

  it('keeps the exploration notification storage key tied to the mounted board owner', () => {
    const storageKey = bind('fuzzNotificationStorageKey', {
      boardOwnerUserId: 1, currentUser: ref({ userId: 2 }), fuzzNotificationStorageKeyForUser: (id: number) => `fuzz-${id}`
    })
    expect(storageKey()).toBe('fuzz-1')
  })

  it.each([{ refreshed: false, validPath: true }, { refreshed: true, validPath: false }])(
    'keeps visible stale-domain feedback inline after refresh=$refreshed and validPath=$validPath', async options => {
      const { ports, run } = context(true, options.refreshed, options.validPath)
      expect(await run()).toBe(false)
      expect(ports.fuzzingError.value.key).toBe(options.refreshed
        ? 'app.fuzzPaperDomainStale' : 'app.fuzzPaperDomainStaleBoardRefreshFailed')
      expect(ports.fuzzingError.value.diagnostics).toMatchObject({
        fields: { paperDomainFingerprint: 'Refresh the domain preview' }
      })
      expect(ports.notifyBlocked).not.toHaveBeenCalled()
      expect(ports.notifyError).not.toHaveBeenCalled()
      expect(ports.schedulePaperDomainPreview).not.toHaveBeenCalled()
    })

  it('still warns when the stale-domain failure has no visible panel', async () => {
    const { ports, run } = context(false, false)
    expect(await run()).toBe(false)
    expect(ports.notifyBlocked).toHaveBeenCalledWith('app.fuzzPaperDomainStaleBoardRefreshFailed')
    expect(ports.notifyError).not.toHaveBeenCalled()
  })

  it('keeps refreshing the domain preview when the visible panel can recover', async () => {
    const { ports, run } = context(true, true)
    expect(await run()).toBe(false)
    expect(ports.schedulePaperDomainPreview).toHaveBeenCalledOnce()
    expect(ports.notifyBlocked).not.toHaveBeenCalled()
  })
})

describe('validated result presentation', () => {
  it('keeps omission evidence unchanged and reads the supplied warning counts', () => {
    const generationIssues = [{ issueType: 'RULE_DISABLED', itemLabel: 'Rule 7', reasonCode: 'RULE_UNRESOLVABLE_COMMAND_ACTION', reason: 'Original diagnostic' }]
    const result = { generationIssues, disabledRuleCount: 0, skippedSpecCount: 2, checkLogs: ['[rule-disabled]'] }
    expect(bind('getGenerationIssues', {})(result)).toBe(generationIssues)
    expect(bind('getGenerationWarningCounts', {})(result)).toEqual({ disabledRuleCount: 0, skippedSpecCount: 2, total: 2 })
    expect(bind('getGenerationIssues', {})(null)).toEqual([])
    expect(bind('getGenerationWarningCounts', {})(null)).toEqual({ disabledRuleCount: 0, skippedSpecCount: 0, total: 0 })
  })

  it('reads actual and requested transition counts independently of trace length', () => {
    const result = { steps: 3, requestedSteps: 20, states: [{}], disabledRuleCount: 0, logs: ['[rule-disabled]'] }
    expect(bind('getSimulationActualStepCount', {})(result)).toBe(3)
    expect(bind('getSimulationRequestedStepCount', {})(result)).toBe(20)
    expect(bind('getSimulationDisabledRuleCount', {})(result)).toBe(0)
    expect(bind('getSimulationActualStepCount', {})(null)).toBe(0)
  })

  it('retains real failure accounting when stored traces and per-spec results differ', () => {
    const count = bind('countVerificationFailures', {})
    expect(count({ specResults: [{ outcome: 'VIOLATED' }, { outcome: 'VIOLATED' }], traces: [{}] })).toBe(2)
    expect(count({ specResults: [{ outcome: 'VIOLATED' }], traces: [{}, {}, {}] })).toBe(3)
    expect(count(null)).toBe(0)
  })
})

describe('simulation run ownership', () => {
  it.each(['SAVED', 'OUTCOME_UNKNOWN'])(
    'addresses only a saved run when opening %s simulation playback', status => {
      const ports = {
        lastSimulationResult: ref({ playbackScene: { nodes: [] },
          historyPersistence: { status, ...(status === 'SAVED' ? { runId: 17 } : {}) } }),
        savedSimulationStates: ref([{ stateIndex: 0 }]), simulationAnimationState: ref({ visible: false }),
        playbackPanelOpen: ref(false), highlightedTrace: ref(null), staleDeepLink: ref(true),
        activatePlaybackScene: vi.fn(), focusCanvasForReplay: vi.fn(),
        navigateToRunTarget: vi.fn(), clearRunDeepLink: vi.fn(), refreshCurrentBoardModelFingerprints: vi.fn()
      }
      bind('openSimulationAnimationFromSavedStates', ports)()
      expect(ports.simulationAnimationState.value.visible).toBe(true)
      if (status === 'SAVED') {
        expect(ports.navigateToRunTarget).toHaveBeenCalledWith({ kind: 'simulation', runId: 17 }, 'push')
        expect(ports.staleDeepLink.value).toBe(false)
        expect(ports.clearRunDeepLink).not.toHaveBeenCalled()
      } else {
        expect(ports.navigateToRunTarget).not.toHaveBeenCalled()
        expect(ports.clearRunDeepLink).toHaveBeenCalledOnce()
      }
    })

  it('derives replay states from the adopted run while hidden and clears them with its owner', () => {
    const previous = { states: [{ stateIndex: 0 }] }
    const next = { states: [{ stateIndex: 7 }] }
    const lastSimulationResult = ref<typeof previous | null>(previous)
    const savedStates = bind('savedSimulationStates', { computed, lastSimulationResult })
    expect(savedStates.value).toBe(lastSimulationResult.value!.states)
    bind('adoptSimulationRunResult', {
      lastSimulationResult, simulationResultStale: ref(false), semanticSceneChangeCount: 0
    })(next, 0)
    expect(savedStates.value).toBe(lastSimulationResult.value!.states)
    expect(savedStates.value).toEqual(next.states)
    lastSimulationResult.value = null
    expect(savedStates.value).toEqual([])
  })
})

describe('simulation completion during another replay', () => {
  const context = (status = 'NOT_REQUESTED', isAsync = false) => {
    const previous = { states: [{ stateIndex: 1 }], playbackScene: { nodes: ['old-scene'] } }
    const result = { states: [{ stateIndex: 7 }], playbackScene: { nodes: ['new-scene'] },
      historyPersistence: { status, ...(status === 'SAVED' ? { runId: 17 } : {}) } }
    const lastSimulationResult = ref<any>(previous)
    const frozenScene = ref(previous.playbackScene)
    const owner = { current: true }
    const pending = deferred()
    const flag = () => ({ value: false })
    const ports = {
      isSimulating: flag(), synchronousVerificationRunning: flag(), isSceneReplacementInProgress: flag(),
      boardAuthScopeEpoch: 3, currentAuthUserId: ref<number | null>(1),
      isCurrentBoardAuthScope: (epoch: number) => owner.current && epoch === 3,
      waitForPendingFixRefresh: vi.fn().mockResolvedValue(undefined),
      waitForPendingBoardMutations: vi.fn().mockResolvedValue(undefined), ensureBoardDataReady: () => true,
      nodes: ref([{}]), rules: ref([]), assertRulesHaveTriggers: () => true,
      validateSimulationSteps: (value: number) => value, attackConfigurationError: () => '',
      asyncSimulationActive: flag(), simulationCancelRequested: flag(), cancellingSimulationTask: flag(),
      simulationError: ref(null), simulationResultStale: ref(false), semanticSceneChangeCount: 0,
      asyncSimulationTask: ref<any>({}), buildSimulationRequestPayload: () => ({}), buildRunAttackScenario: () => ({}),
      simulationApi: { simulate: vi.fn(() => pending.promise), simulateAndSave: vi.fn(() => pending.promise),
        simulateAsync: vi.fn().mockResolvedValue({ id: 17 }) },
      pollAsyncSimulation: vi.fn(() => pending.promise), normalizeTaskProgress: () => 0,
      taskProgressStatusKey: () => 'app.taskInitializing', upsertSimulationTaskSummary: vi.fn(),
      simulationResultFromTrace: (value: unknown) => value, summarizeSimulationResult: () => null,
      simulationHistoryRequests: { invalidate: vi.fn() }, simulationRuns: ref([]),
      loadSimulationRuns: vi.fn().mockResolvedValue(undefined),
      traceAnimationState: ref({ visible: false }), simulationAnimationState: ref({ visible: false }),
      openModalDepth: ref(0), activeWorkflowPanel: ref<BoardWorkflowPanel | null>({ kind: 'simulation', view: isAsync ? 'run' : 'settings' }),
      lastSimulationResult, frozenScene, unsavedRunNotifications: new Set<() => void>(),
      adoptSimulationRunResult: vi.fn(), openSimulationAnimationFromSavedStates: vi.fn(),
      notifySimulationOutcome: vi.fn(), notifyAutomaticPlaybackDeferred: vi.fn(), notifyAvailableResult: vi.fn(),
      ensureHistoricalPlaybackUiAdmission: vi.fn((): boolean =>
        !ports.traceAnimationState.value.visible && !ports.simulationAnimationState.value.visible),
      closeOtherWorkflowSurfaces: vi.fn(), notifyInfo: vi.fn(), notifyBlocked: vi.fn(), notifyError: vi.fn(),
      isPollingAbortedError: () => false, isCompletedTaskResultUnavailableError: () => false,
      formalOperationBusyMessage: () => '', asyncTaskQuotaMessage: () => '',
      extractApiErrorMessage: (error: Error) => error.message, isAsyncTaskCancelledError: () => false,
      presentRunFailure: vi.fn(), t: (key: string) => key
    }
    ports.adoptSimulationRunResult.mockImplementation(bind('adoptSimulationRunResult', ports))
    ports.openSimulationAnimationFromSavedStates.mockImplementation(() => {
      frozenScene.value = lastSimulationResult.value.playbackScene
    })
    ports.notifyAvailableResult.mockImplementation((options: { onClose: () => void }) => vi.fn(() => options.onClose()))
    ports.notifyAutomaticPlaybackDeferred.mockImplementation(bind('notifyAutomaticPlaybackDeferred', ports))
    const run = bindRun('handleSimulate', ports)
    const start = () => run({ steps: 1, isAsync, saveToHistory: status !== 'NOT_REQUESTED' })
    return { previous, result, ports, owner, pending, start,
      savedStates: bind('savedSimulationStates', { computed, lastSimulationResult }) }
  }

  it.each([false, true])('opens the completed trajectory without a duplicate toast (async=%s)', async isAsync => {
    const state = context('SAVED', isAsync)
    const completion = state.start()
    await vi.waitFor(() => expect(state.ports.simulationApi[isAsync ? 'simulateAsync' : 'simulateAndSave']).toHaveBeenCalledOnce())
    state.ports.semanticSceneChangeCount += 1
    state.pending.resolve(state.result)
    expect(await completion).toBe(true)
    expect(state.ports.simulationResultStale.value).toBe(true)
    expect(state.ports.openSimulationAnimationFromSavedStates).toHaveBeenCalledOnce()
    expect(state.ports.notifySimulationOutcome).not.toHaveBeenCalled()
  })

  it('submits a single async simulation after the board mutation queue settles', async () => {
    const state = context('SAVED', true)
    const boardEdit = deferred()
    state.ports.waitForPendingBoardMutations.mockReturnValue(boardEdit.promise)
    const first = state.start()
    const duplicate = state.start()
    await flushPromises()
    expect(state.ports.simulationApi.simulateAsync).not.toHaveBeenCalled()
    boardEdit.resolve()
    await flushPromises()
    state.pending.resolve(state.result)
    await Promise.all([first, duplicate])
    expect(state.ports.simulationApi.simulateAsync).toHaveBeenCalledOnce()
  })

  it.each(['closed', 'replay', 'progress'] as const)('delivers a watched simulation from %s presentation', async surface => {
    const state = context('SAVED', true)
    const ports = Object.assign(state.ports, {
      simulationTasks: ref([]), closeHistoryPanel: vi.fn(), loadTaskInbox: vi.fn(), boardLifecycleDisposed: false,
      summarizeSimulationResult: () => ({ id: 17 })
    })
    const watching = bindRun('watchSimulationTask', ports)(17)
    if (surface === 'closed') bind('closeWorkflowPanel', ports)('simulation')
    if (surface === 'replay') ports.simulationAnimationState.value.visible = true
    ports.semanticSceneChangeCount += 1
    state.pending.resolve(state.result)
    await watching
    expect(ports.simulationRuns.value).toEqual([{ id: 17 }])
    if (surface === 'progress') {
      expect(ports.openSimulationAnimationFromSavedStates).toHaveBeenCalledOnce()
      expect(ports.simulationResultStale.value).toBe(true)
      expect(ports.notifySimulationOutcome).not.toHaveBeenCalled()
      return
    }
    expect(ports.openSimulationAnimationFromSavedStates).not.toHaveBeenCalled()
    expect(ports.notifySimulationOutcome).toHaveBeenCalledWith(state.result, true)
    if (surface === 'replay') expect(ports.notifyInfo).toHaveBeenCalledWith('app.simulationPlaybackDeferredForReplay')
  })

  it('keeps an unsaved trajectory accessible when a modal prevents automatic playback', async () => {
    const state = context('NOT_REQUESTED')
    const completion = state.start()
    await vi.waitFor(() => expect(state.ports.simulationApi.simulate).toHaveBeenCalledOnce())
    state.ports.openModalDepth.value = 1
    state.pending.resolve(state.result)
    expect(await completion).toBe(true)
    expect(state.ports.adoptSimulationRunResult).not.toHaveBeenCalled()
    expect(state.ports.openSimulationAnimationFromSavedStates).not.toHaveBeenCalled()
    expect(state.ports.notifyAvailableResult).toHaveBeenCalledOnce()
    const open = state.ports.notifyAvailableResult.mock.calls[0][0].onAction
    expect(open()).toBe(false)
    state.ports.openModalDepth.value = 0
    expect(open()).toBe(true)
    expect(state.ports.openSimulationAnimationFromSavedStates).toHaveBeenCalledOnce()
    expect(state.ports.lastSimulationResult.value).toEqual(state.result)
  })

  it('keeps a local synchronous trajectory available after the user closes its progress panel', async () => {
    const state = context('NOT_REQUESTED')
    const completion = state.start()
    await vi.waitFor(() => expect(state.ports.simulationApi.simulate).toHaveBeenCalledOnce())
    bind('closeWorkflowPanel', state.ports)('simulation')
    state.pending.resolve(state.result)
    expect(await completion).toBe(true)
    expect(state.ports.adoptSimulationRunResult).not.toHaveBeenCalled()
    expect(state.ports.openSimulationAnimationFromSavedStates).not.toHaveBeenCalled()
    expect(state.ports.notifyAvailableResult).toHaveBeenCalledOnce()
  })

  it.each(['SAVED', 'NOT_REQUESTED', 'OUTCOME_UNKNOWN'])(
    'keeps a synchronous %s completion from adopting over another replay', async status => {
      const state = context(status)
      const completion = state.start()
      await vi.waitFor(() => expect(state.ports.simulationApi[status === 'NOT_REQUESTED'
        ? 'simulate' : 'simulateAndSave']).toHaveBeenCalledOnce())
      state.ports.simulationAnimationState.value.visible = true
      state.pending.resolve(state.result)
      expect(await completion).toBe(true)
      expect(state.ports.adoptSimulationRunResult).not.toHaveBeenCalled()
      expect(state.ports.openSimulationAnimationFromSavedStates).not.toHaveBeenCalled()
      expect(state.ports.lastSimulationResult.value).toEqual(state.previous)
      expect(state.savedStates.value).toEqual(state.previous.states)
      expect(state.ports.frozenScene.value).toEqual(state.previous.playbackScene)
      expect(state.ports.presentRunFailure).not.toHaveBeenCalled()
      if (status === 'SAVED') {
        expect(state.ports.notifyAvailableResult).not.toHaveBeenCalled()
        expect(state.ports.notifyInfo).toHaveBeenCalledWith('app.simulationPlaybackDeferredForReplay')
      } else {
        expect(state.ports.notifyAvailableResult).toHaveBeenCalledOnce()
        expect(state.ports.notifyInfo).not.toHaveBeenCalledWith('app.simulationPlaybackDeferredForReplay')
        const action = state.ports.notifyAvailableResult.mock.calls[0][0].onAction
        expect(action()).toBe(false)
        expect(state.ports.adoptSimulationRunResult).not.toHaveBeenCalled()
        state.ports.simulationAnimationState.value.visible = false
        expect(action()).toBe(true)
        expect(state.ports.lastSimulationResult.value).toEqual(state.result)
        expect(state.savedStates.value).toEqual(state.result.states)
        expect(state.ports.frozenScene.value).toEqual(state.result.playbackScene)
      }
    }
  )

  it('keeps history deferral for an asynchronous completion during counterexample replay', async () => {
    const state = context('SAVED', true)
    const completion = state.start()
    await vi.waitFor(() => expect(state.ports.pollAsyncSimulation).toHaveBeenCalledOnce())
    state.ports.traceAnimationState.value.visible = true
    state.pending.resolve(state.result)
    expect(await completion).toBe(true)
    expect(state.ports.adoptSimulationRunResult).not.toHaveBeenCalled()
    expect(state.ports.notifyInfo).toHaveBeenCalledWith('app.simulationPlaybackDeferredForReplay')
  })

  it('opens a normal synchronous completion directly without an extra result notice', async () => {
    const state = context()
    const completion = state.start()
    state.pending.resolve(state.result)
    expect(await completion).toBe(true)
    expect(state.ports.lastSimulationResult.value).toEqual(state.result)
    expect(state.ports.notifyAvailableResult).not.toHaveBeenCalled()
  })

  it('does not create a result notice for a disposed run owner', async () => {
    const state = context()
    const completion = state.start()
    await vi.waitFor(() => expect(state.ports.simulationApi.simulate).toHaveBeenCalledOnce())
    state.owner.current = false
    state.ports.simulationAnimationState.value.visible = true
    state.pending.resolve(state.result)
    expect(await completion).toBe(false)
    expect(state.ports.notifyAvailableResult).not.toHaveBeenCalled()
    expect(state.ports.adoptSimulationRunResult).not.toHaveBeenCalled()
  })

  it.each(['scope', 'account'])('discards an old notice action when its %s owner is gone', async change => {
    const state = context()
    const completion = state.start()
    state.ports.simulationAnimationState.value.visible = true
    state.pending.resolve(state.result)
    await completion
    state.ports.simulationAnimationState.value.visible = false
    if (change === 'scope') state.owner.current = false
    else state.ports.currentAuthUserId.value = 2
    expect(state.ports.notifyAvailableResult.mock.calls[0][0].onAction()).toBe(true)
    expect(state.ports.adoptSimulationRunResult).not.toHaveBeenCalled()
    expect(state.ports.ensureHistoricalPlaybackUiAdmission).not.toHaveBeenCalled()
  })

  it('closes every notice owned by the board when that view is torn down', () => {
    const close = vi.fn()
    const unsavedRunNotifications = new Set([close, vi.fn()])
    const registration = source.statements.find(statement => ts.isExpressionStatement(statement)
      && statement.getText(source).startsWith('onBeforeUnmount(')
      && statement.getText(source).includes('unsavedRunNotifications'))!
    const javascript = ts.transpileModule(registration.getText(source), {
      compilerOptions: { target: ts.ScriptTarget.ES2022 }
    }).outputText
    let teardown!: () => void
    new Function('onBeforeUnmount', 'unsavedRunNotifications', javascript)(
      (callback: () => void) => { teardown = callback }, unsavedRunNotifications)
    teardown()
    expect(close).toHaveBeenCalledOnce()
    expect(unsavedRunNotifications.size).toBe(0)
  })
})

describe('verification completion', () => {
  it('reports a missing result without inventing a formal outcome', () => {
    const ports = { notifyBlocked: vi.fn(), notifySuccess: vi.fn(), t: (key: string) => key }
    bind('notifyVerificationOutcome', ports)(null)
    expect(ports.notifyBlocked).toHaveBeenCalledWith('household.resultUnavailable')
    expect(ports.notifySuccess).not.toHaveBeenCalled()
  })

  it.each([
    ['UNREACHABLE', true], ['REACHABLE', false]
  ] as const)(
    'announces a background formula pass according to its %s trigger evidence', (antecedentReachability, warning) => {
      const ports = {
        compareRunToCurrentBoard: () => 'UNCHANGED',
        getGenerationWarningCounts: () => ({ total: 0 }),
        getVerificationOutcome, countResponseTriggerWarnings,
        isVerificationModelComplete: (result: { modelComplete: boolean }) => result.modelComplete,
        notifyBlocked: vi.fn(), notifySuccess: vi.fn(),
        t: (key: string, values?: { count: number }) => `${key}:${values?.count ?? ''}`
      }
      const result = { outcome: 'SATISFIED', modelComplete: true,
        specResults: [{ antecedentReachability }] }
      bind('notifyVerificationOutcome', ports)(result)
      if (warning) {
        expect(ports.notifyBlocked).toHaveBeenCalledWith('household.coverageDetail:1')
        expect(ports.notifySuccess).not.toHaveBeenCalled()
      } else {
        expect(ports.notifySuccess).toHaveBeenCalledWith('app.verificationSatisfiedComplete:')
        expect(ports.notifyBlocked).not.toHaveBeenCalled()
      }
      expect(result.outcome).toBe('SATISFIED')
    }
  )

  it('announces an earlier modeling version instead of success for a background verdict', () => {
    const ports = {
      compareRunToCurrentBoard: () => 'SEMANTICS_CHANGED',
      getGenerationWarningCounts: () => ({ total: 0 }), getVerificationOutcome,
      countResponseTriggerWarnings, isVerificationModelComplete: () => true,
      notifyBlocked: vi.fn(), notifySuccess: vi.fn(), t: (key: string) => key
    }
    bind('notifyVerificationOutcome', ports)({ outcome: 'SATISFIED', modelComplete: true, specResults: [] })
    expect(ports.notifyBlocked).toHaveBeenCalledExactlyOnceWith('app.runBoardSemanticsChanged')
    expect(ports.notifySuccess).not.toHaveBeenCalled()
  })

  const context = (status = 'FAILED', isAsync = false) => {
    const flag = () => ({ value: false })
    const result = { historyPersistence: { status, ...(status === 'SAVED' ? { runId: 17 } : {}) },
      outcome: 'VIOLATED', traces: [{ states: [{ stateIndex: 1 }] }], checkLogs: ['original diagnostic'] }
    const owner = { current: true }
    const pending = deferred()
    const ports = {
      isVerifying: flag(), verificationForm: { isAsync: false, attackMode: 'NONE', enablePrivacy: false },
      boardAuthScopeEpoch: 3, currentAuthUserId: ref<number | null>(1),
      isCurrentBoardAuthScope: (epoch: number) => owner.current && epoch === 3,
      synchronousSimulationRunning: flag(), isSceneReplacementInProgress: flag(),
      waitForPendingFixRefresh: vi.fn().mockResolvedValue(undefined), waitForPendingBoardMutations: vi.fn().mockResolvedValue(undefined),
      ensureBoardDataReady: () => true, nodes: { value: [{}] }, specifications: { value: [{}] }, rules: { value: [] },
      assertRulesHaveTriggers: () => true, verificationAttackConfigurationError: { value: null },
      asyncVerificationActive: flag(), verificationCancelRequested: flag(), cancellingVerificationTask: flag(),
      verificationError: { value: null }, verificationResult: { value: null }, verificationResultStale: flag(),
      semanticSceneChangeCount: 0, buildVerificationRequestPayload: (value: unknown) => value,
      buildRunAttackScenario: () => ({}), boardApi: { verify: vi.fn(() => pending.promise),
        getTask: vi.fn(() => pending.promise.then(() => ({ id: 17, status: 'COMPLETED', outcome: 'VIOLATED' }))),
        getTaskTraces: vi.fn().mockResolvedValue(result.traces) },
      pollingEpoch: 0, ASYNC_TASK_MAX_POLLS: 1, throwIfPollingAborted: vi.fn(),
      asyncVerificationTask: ref({ progress: 0, statusKey: 'app.taskInitializing' }),
      normalizeTaskProgress: () => 100, taskProgressStatusKey: () => 'app.taskInitializing', upsertVerificationTaskSummary: vi.fn(),
      loadCompletedTaskResult: (read: () => Promise<unknown>) => read(),
      buildVerificationResultFromTask: () => result,
      activeWorkflowPanel: ref<BoardWorkflowPanel | null>({ kind: 'verification', view: isAsync ? 'run' : 'settings', handoff: null }),
      historyDetailRequests: { invalidate: vi.fn() }, deepLinkLoadRequests: { invalidate: vi.fn() },
      staleDeepLink: ref(true), navigateToRunTarget: vi.fn().mockResolvedValue(undefined), clearRunDeepLink: vi.fn(),
      adoptVerificationRunResult: vi.fn(), refreshCurrentBoardModelFingerprints: vi.fn(),
      isModelPlaybackActive: flag(), openModalDepth: ref(0), notifyVerificationOutcome: vi.fn(),
      unsavedRunNotifications: new Set<() => void>(), notifyAvailableResult: vi.fn(),
      ensureHistoricalPlaybackUiAdmission: vi.fn(() => !ports.isModelPlaybackActive.value),
      closeOtherWorkflowSurfaces: vi.fn(),
      dismissCompletionNotifications: vi.fn(),
      notifyBlocked: vi.fn(), notifyInfo: vi.fn(), notifyError: vi.fn(), loadVerificationRuns: vi.fn().mockResolvedValue(undefined),
      isPollingAbortedError: () => false, isCompletedTaskResultUnavailableError: () => false,
      formalOperationBusyMessage: () => null, asyncTaskQuotaMessage: () => null,
      extractApiErrorMessage: (error: Error) => error.message, isAsyncTaskCancelledError: () => false,
      presentRunFailure: vi.fn(), t: (key: string) => key
    }
    ports.adoptVerificationRunResult.mockImplementation(bind('adoptVerificationRunResult', ports))
    ports.notifyAvailableResult.mockImplementation((options: { onClose: () => void }) => vi.fn(() => options.onClose()))
    const verify = bindRun('handleVerify', ports)
    return { ports, pending, owner, result, start: isAsync
      ? () => bind('pollAsyncVerification', ports)(17)
      : verify }
  }

  it.each([false, true])('makes a saved completion addressable without refetching it (async=%s)', async isAsync => {
    const state = context('SAVED', isAsync)
    const completion = state.start()
    await vi.waitFor(() => expect(isAsync ? state.ports.boardApi.getTask : state.ports.boardApi.verify).toHaveBeenCalledOnce())
    state.ports.semanticSceneChangeCount += 1
    state.pending.resolve(state.result)
    await completion
    expect(state.ports.verificationResult.value).toBe(state.result)
    expect(state.ports.verificationResultStale.value).toBe(true)
    expect(state.ports.refreshCurrentBoardModelFingerprints).toHaveBeenCalledOnce()
    expect(state.ports.navigateToRunTarget).toHaveBeenCalledExactlyOnceWith({ kind: 'verification', runId: 17 }, 'push')
    expect(state.ports.historyDetailRequests.invalidate).toHaveBeenCalledOnce()
    expect(state.ports.deepLinkLoadRequests.invalidate).toHaveBeenCalledOnce()
    expect(state.ports.staleDeepLink.value).toBe(false)
    expect(state.ports.clearRunDeepLink).not.toHaveBeenCalled()
    expect(state.ports.notifyVerificationOutcome).toHaveBeenCalledWith(state.result, { presenting: true })
  })

  it('keeps a background asynchronous completion from changing the selected run', async () => {
    const state = context('SAVED', true)
    bind('closeWorkflowPanel', state.ports)('verification')
    state.ports.isModelPlaybackActive.value = true
    const completion = bind('pollAsyncVerification', state.ports)(17)
    state.pending.resolve(state.result)
    await completion
    expect(state.ports.adoptVerificationRunResult).not.toHaveBeenCalled()
    expect(state.ports.navigateToRunTarget).not.toHaveBeenCalled()
    expect(state.ports.notifyVerificationOutcome).toHaveBeenCalledWith(state.result)
    expect(state.ports.notifyInfo).not.toHaveBeenCalled()
  })

  it('submits one async verification and keeps its owner through a pending board edit', async () => {
    const state = context('SAVED')
    const boardEdit = deferred()
    const verifyAsync = vi.fn().mockResolvedValue({ id: 17, progress: 0 })
    const pollAsyncVerification = vi.fn(() => state.pending.promise)
    Object.assign(state.ports, { pollAsyncVerification })
    Object.assign(state.ports.boardApi, { verifyAsync })
    state.ports.verificationForm.isAsync = true
    state.ports.waitForPendingBoardMutations.mockReturnValue(boardEdit.promise)
    const first = state.start()
    const duplicate = state.start()
    await flushPromises()
    expect(verifyAsync).not.toHaveBeenCalled()
    boardEdit.resolve()
    await flushPromises()
    state.pending.resolve()
    await Promise.all([first, duplicate])
    expect(verifyAsync).toHaveBeenCalledOnce()
    expect(pollAsyncVerification).toHaveBeenCalledExactlyOnceWith(17)
  })

  it('keeps a watched verification out of the foreground after closing progress', async () => {
    const state = context('SAVED', true)
    const ports = Object.assign(state.ports, {
      verificationTasks: ref([]), closeHistoryPanel: vi.fn(), loadTaskInbox: vi.fn(),
      boardLifecycleDisposed: false,
      pollAsyncVerification: bind('pollAsyncVerification', state.ports)
    })
    const watching = bindRun('watchVerificationTask', ports)(17)
    bind('closeWorkflowPanel', ports)('verification')
    state.pending.resolve(state.result)
    await watching
    expect(ports.adoptVerificationRunResult).not.toHaveBeenCalled()
    expect(ports.navigateToRunTarget).not.toHaveBeenCalled()
    expect(ports.notifyVerificationOutcome).toHaveBeenCalledWith(state.result)
    expect(ports.loadVerificationRuns).toHaveBeenCalledOnce()
  })

  it('does not restore active progress from a status read overtaken by a cancellation receipt', async () => {
    const state = context('SAVED', true)
    const previousRead = deferred()
    const cancelResponse = deferred()
    const receipt = { taskId: 17, taskStatus: 'CANCELLED', cancellationOutcome: 'ACCEPTED',
      cancellationAccepted: true, executionMayStillBeStopping: true }
    class AsyncTaskCancelledError extends Error {}
    const ports: Record<string, any> = Object.assign(state.ports, {
      boardLifecycleDisposed: false, verificationTasks: ref([{ id: 17, status: 'RUNNING', progress: 48 }]),
      asyncVerificationTask: ref({ taskId: 17, progress: 48, statusKey: 'app.taskStatusRunning' }),
      normalizeTaskProgress: (progress: number) => progress,
      taskProgressStatusKey: (_stage: unknown, status: string) => status === 'CANCELLED'
        ? 'app.taskStatusCancelled' : 'app.taskStatusRunning',
      AsyncTaskCancelledError, isAsyncTaskCancelledError: (error: unknown) => error instanceof AsyncTaskCancelledError,
      notifyTaskCancellationResult: vi.fn(), waitForNextPoll: vi.fn(), isPermanentPollError: () => false
    })
    ports.isVerifying.value = true
    ports.asyncVerificationActive.value = true
    ports.boardApi.getTask.mockReturnValue(previousRead.promise)
    ports.boardApi.cancelTask = vi.fn().mockReturnValue(cancelResponse.promise)
    bindWorkflowPanel(ports)
    const cancel = bindCancellation('Verification', ports)
    const polling = bind('pollAsyncVerification', ports)(17).catch((error: unknown) => error)
    const cancelling = cancel()
    // The status-read helper resumes first, then the cancel receipt, then the poller's caller.
    previousRead.resolve({ id: 17, status: 'RUNNING', progress: 31 })
    cancelResponse.resolve(receipt)
    await cancelling

    expect(await polling).toBeInstanceOf(AsyncTaskCancelledError)
    expect(ports.asyncVerificationTask.value).toMatchObject({ progress: 48, statusKey: 'app.taskStatusCancelled' })
    expect(ports.upsertVerificationTaskSummary).toHaveBeenCalledExactlyOnceWith({
      id: 17, status: 'CANCELLED', progressStage: undefined
    })
    expect(ports.waitForNextPoll).not.toHaveBeenCalled()
  })

  it.each([['succeeds', false], ['fails', false], ['fails', true]] as const)(
    'keeps a confirmed status when the pending cancel later %s (inbox visible=%s)', async (outcome, inboxVisible) => {
    const state = context('SAVED', true)
    const statusRead = deferred()
    const cancelResponse = deferred()
    class AsyncTaskCancelledError extends Error {}
    const ports: Record<string, any> = Object.assign(state.ports, {
      boardLifecycleDisposed: false, verificationTasks: ref([{ id: 17, status: 'RUNNING' }]),
      closeHistoryPanel: vi.fn(), loadTaskInbox: vi.fn(), notifyTaskCancellationResult: vi.fn(),
      activeHistoryLayer: ref('tasks'), invalidateTaskInboxRequests: vi.fn(),
      presentTaskCancellationFailure: vi.fn(), isPermanentPollError: () => false,
      taskProgressStatusKey: (_stage: unknown, status: string) => status === 'CANCELLED'
        ? 'app.taskStatusCancelled' : 'app.taskStatusRunning',
      AsyncTaskCancelledError, isAsyncTaskCancelledError: (error: unknown) => error instanceof AsyncTaskCancelledError
    })
    ports.boardApi.getTask.mockReturnValue(statusRead.promise)
    ports.boardApi.cancelTask = vi.fn().mockReturnValue(cancelResponse.promise)
    ports.upsertVerificationTaskSummary = bind('upsertVerificationTaskSummary', ports)
    ports.pollAsyncVerification = bind('pollAsyncVerification', ports)
    const watching = bindRun('watchVerificationTask', ports)(17)
    const cancelling = bindCancellation('Verification', ports)()
    if (inboxVisible) ports.activeWorkflowPanel.value = { kind: 'history' }
    statusRead.resolve({ id: 17, status: 'CANCELLED', progress: 31 })
    await watching

    if (outcome === 'succeeds') {
      cancelResponse.resolve({ taskId: 17, taskStatus: 'CANCELLED', cancellationOutcome: 'ACCEPTED',
        cancellationAccepted: true, executionMayStillBeStopping: true })
    } else {
      cancelResponse.reject(new Error('Cancel response failed'))
    }
    await cancelling

    if (inboxVisible) {
      expect(ports.verificationTasks.value[0].status).toBe('CANCELLED')
      expect(ports.notifyInfo).not.toHaveBeenCalled()
    } else {
      expect(ports.notifyInfo).toHaveBeenCalledExactlyOnceWith('app.verificationCancelled')
    }
    expect(ports.notifyTaskCancellationResult).not.toHaveBeenCalled()
    expect(ports.presentTaskCancellationFailure).not.toHaveBeenCalled()
    expect(ports.asyncVerificationTask.value.statusKey).toBe('app.taskStatusCancelled')
  })

  it('ends a watch with a neutral notice when the task was removed before status was read', async () => {
    const state = context('SAVED', true)
    const ports: Record<string, any> = Object.assign(state.ports, {
      verificationTasks: ref([{ id: 17, status: 'RUNNING' }]),
      closeHistoryPanel: vi.fn(), loadTaskInbox: vi.fn(), boardLifecycleDisposed: false,
      isPermanentPollError: () => true, invalidateTaskInboxRequests: vi.fn()
    })
    ports.boardApi.getTask.mockRejectedValue({ response: { status: 404 } })
    ports.pollAsyncVerification = bind('pollAsyncVerification', ports)
    await bindRun('watchVerificationTask', ports)(17)

    expect(ports.presentRunFailure).not.toHaveBeenCalled()
    expect(ports.notifyInfo).toHaveBeenCalledExactlyOnceWith('app.taskNoLongerAvailable')
    expect(ports.verificationTasks.value).toEqual([])
    expect(ports.isVerifying.value).toBe(false)
  })

  it('keeps a synchronous verdict in history after the user closes its progress panel', async () => {
    const state = context('SAVED')
    const completion = state.start()
    await vi.waitFor(() => expect(state.ports.boardApi.verify).toHaveBeenCalledOnce())
    bind('closeWorkflowPanel', state.ports)('verification')
    state.pending.resolve(state.result)
    await completion
    expect(state.ports.adoptVerificationRunResult).not.toHaveBeenCalled()
    expect(state.ports.navigateToRunTarget).not.toHaveBeenCalled()
    expect(state.ports.notifyVerificationOutcome).toHaveBeenCalledWith(state.result)
    expect(state.ports.loadVerificationRuns).toHaveBeenCalledOnce()
  })

  it('preserves the selected historical verdict while a newer task finishes during its read', async () => {
    const state = context('SAVED', true)
    const historical = { ...state.result, historyPersistence: { status: 'SAVED', runId: 9 } }
    const read = deferred()
    const historyDetailRequests = createPagedRequestCoordinator()
    const ports = Object.assign(state.ports, {
      appliedDeepLinkTarget: null, boardLifecycleDisposed: false, historyDetailRequests,
      deepLinkLoadRequests: { begin: () => 1, invalidate: vi.fn() },
      closePlaybackForNavigation: vi.fn(), closeHistoryPanel: vi.fn(),
      closeResultSurfaces: () => historyDetailRequests.invalidate(), isCurrentDeepLinkLoad: () => true,
      buildVerificationResultFromRun: () => historical, shouldLoadVerificationEvidence: () => false
    })
    const getVerificationRun = vi.fn(() => read.promise)
    Object.assign(ports.boardApi, { getVerificationRun })
    Object.assign(ports, {
      openVerificationRun: bind('openVerificationRun', ports),
      loadDeepLinkTarget: bind('loadDeepLinkTarget', ports)
    })

    const completion = state.start()
    const target = { kind: 'verification', runId: 9 }
    const navigation = bind('applyDeepLinkTarget', ports)(target)
    expect(getVerificationRun).toHaveBeenCalledExactlyOnceWith(9)
    state.pending.resolve(state.result)
    await completion
    expect(ports.adoptVerificationRunResult).not.toHaveBeenCalled()
    expect(ports.navigateToRunTarget).not.toHaveBeenCalled()
    expect(ports.notifyVerificationOutcome).toHaveBeenCalledWith(state.result)
    read.resolve(historical)
    await navigation
    expect(ports.appliedDeepLinkTarget).toEqual(target)
    expect(ports.verificationResult.value).toBe(historical)
  })

  it('keeps a new exploration handoff open when the unrelated watched verdict completes', async () => {
    const state = context('SAVED', true)
    const explorationRun = { id: 24 }
    const ports = Object.assign(state.ports, {
      verificationTasks: ref([]), closeHistoryPanel: vi.fn(), loadTaskInbox: vi.fn(),
      boardLifecycleDisposed: false,
      pollAsyncVerification: bind('pollAsyncVerification', state.ports),
      fuzzingResult: ref(explorationRun), dismissFuzzingResult: vi.fn()
    })
    Object.assign(ports, { showVerificationPanelForHandoff: bind('showVerificationPanelForHandoff', ports) })
    const watching = bindRun('watchVerificationTask', ports)(17)
    await vi.waitFor(() => expect(ports.boardApi.getTask).toHaveBeenCalledOnce())
    bind('openFormalVerificationForCurrentBoard', ports)()
    expect(bind('tracksRun', ports)('verification')).toBe(false)
    state.pending.resolve(state.result)
    await watching
    expect(ports.adoptVerificationRunResult).not.toHaveBeenCalled()
    expect(ports.activeWorkflowPanel.value).toMatchObject({ kind: 'verification', view: 'settings', handoff: { runId: 24 } })
    expect(ports.loadVerificationRuns).toHaveBeenCalledOnce()
  })

  it('preserves the active replay URL when an asynchronous verdict arrives', async () => {
    const state = context('SAVED', true)
    const completion = state.start()
    state.ports.isModelPlaybackActive.value = true
    state.pending.resolve(state.result)
    await completion
    expect(state.ports.adoptVerificationRunResult).not.toHaveBeenCalled()
    expect(state.ports.navigateToRunTarget).not.toHaveBeenCalled()
    expect(state.ports.clearRunDeepLink).not.toHaveBeenCalled()
    expect(state.ports.notifyInfo).toHaveBeenCalledWith('app.verificationResultDeferredForPlayback')
  })

  it.each([false, true])('leaves an active draft in front when a verdict arrives (async=%s)', async isAsync => {
    const state = context('SAVED', isAsync)
    const completion = state.start()
    await vi.waitFor(() => expect(isAsync ? state.ports.boardApi.getTask : state.ports.boardApi.verify).toHaveBeenCalledOnce())
    state.ports.openModalDepth.value = 1
    state.pending.resolve(state.result)
    await completion
    expect(state.ports.adoptVerificationRunResult).not.toHaveBeenCalled()
    expect(state.ports.navigateToRunTarget).not.toHaveBeenCalled()
    expect(state.ports.notifyInfo).toHaveBeenCalledWith('app.verificationResultDeferredForModal')
    expect(state.ports.loadVerificationRuns).toHaveBeenCalledOnce()
  })

  it.each(['FAILED', 'OUTCOME_UNKNOWN'])(
    'retains an unconfirmed %s verdict with its original evidence until explicitly opened', async status => {
      const state = context(status)
      const completion = state.start()
      await vi.waitFor(() => expect(state.ports.boardApi.verify).toHaveBeenCalledOnce())
      state.ports.isModelPlaybackActive.value = true
      state.pending.resolve(state.result)
      expect(await completion).toBe(true)
      expect(state.ports.notifyBlocked).toHaveBeenCalledWith(status === 'FAILED'
        ? 'app.verificationHistorySaveFailed' : 'app.verificationHistorySaveOutcomeUnknown')
      expect(state.ports.loadVerificationRuns).toHaveBeenCalledWith(false)
      expect(state.ports.notifyInfo).not.toHaveBeenCalledWith('app.verificationResultDeferredForPlayback')
      expect(state.ports.verificationResult.value).toBeNull()
      expect(state.ports.presentRunFailure).not.toHaveBeenCalled()
      const action = state.ports.notifyAvailableResult.mock.calls[0][0].onAction
      expect(action()).toBe(false)
      expect(state.ports.closeOtherWorkflowSurfaces).not.toHaveBeenCalled()
      expect(state.ports.verificationResult.value).toBeNull()
      state.ports.isModelPlaybackActive.value = false
      state.ports.semanticSceneChangeCount += 1
      expect(action()).toBe(true)
      expect(state.ports.closeOtherWorkflowSurfaces).toHaveBeenCalledOnce()
      expect(state.ports.verificationResult.value).toBe(state.result)
      expect(state.ports.verificationResultStale.value).toBe(true)
      expect(state.ports.verificationResult.value).toMatchObject({
        historyPersistence: { status }, traces: state.result.traces, checkLogs: state.result.checkLogs
      })
    }
  )

  it('leaves a confirmed saved verdict in history while another replay is open', async () => {
    const state = context('SAVED')
    const completion = state.start()
    await vi.waitFor(() => expect(state.ports.boardApi.verify).toHaveBeenCalledOnce())
    state.ports.isModelPlaybackActive.value = true
    state.pending.resolve(state.result)
    expect(await completion).toBe(true)
    expect(state.ports.notifyInfo).toHaveBeenCalledWith('app.verificationResultDeferredForPlayback')
    expect(state.ports.loadVerificationRuns).toHaveBeenCalledWith(false)
    expect(state.ports.notifyAvailableResult).not.toHaveBeenCalled()
    expect(state.ports.verificationResult.value).toBeNull()
    expect(state.ports.navigateToRunTarget).not.toHaveBeenCalled()
    expect(state.ports.clearRunDeepLink).not.toHaveBeenCalled()
  })

  it.each(['FAILED', 'OUTCOME_UNKNOWN'])(
    'presents a synchronous %s verdict directly when no replay is open', async status => {
      const state = context(status)
      const completion = state.start()
      state.pending.resolve(state.result)
      expect(await completion).toBe(true)
      expect(state.ports.verificationResult.value).toBe(state.result)
      expect(state.ports.verificationResultStale.value).toBe(false)
      expect(state.ports.notifyAvailableResult).not.toHaveBeenCalled()
      expect(state.ports.notifyVerificationOutcome).toHaveBeenCalledWith(state.result, { presenting: true })
      expect(state.ports.loadVerificationRuns).toHaveBeenCalledWith(false)
      expect(state.ports.navigateToRunTarget).not.toHaveBeenCalled()
      expect(state.ports.clearRunDeepLink).toHaveBeenCalledOnce()
    }
  )

  it.each(['scope', 'account'])(
    'rejects a deferred verdict action after its %s owner changes', async change => {
      const state = context()
      const completion = state.start()
      state.ports.isModelPlaybackActive.value = true
      state.pending.resolve(state.result)
      await completion
      state.ports.isModelPlaybackActive.value = false
      if (change === 'scope') state.owner.current = false
      else state.ports.currentAuthUserId.value = 2
      expect(state.ports.notifyAvailableResult.mock.calls[0][0].onAction()).toBe(true)
      expect(state.ports.ensureHistoricalPlaybackUiAdmission).not.toHaveBeenCalled()
      expect(state.ports.verificationResult.value).toBeNull()
    }
  )

  it.each(['scope', 'account'])(
    'does not present or retain a late verdict after its request %s owner changes', async change => {
      const state = context()
      const completion = state.start()
      await vi.waitFor(() => expect(state.ports.boardApi.verify).toHaveBeenCalledOnce())
      state.ports.isModelPlaybackActive.value = true
      if (change === 'scope') state.owner.current = false
      else state.ports.currentAuthUserId.value = 2
      state.pending.resolve(state.result)
      expect(await completion).toBe(false)
      expect(state.ports.notifyAvailableResult).not.toHaveBeenCalled()
      expect(state.ports.notifyVerificationOutcome).not.toHaveBeenCalled()
      expect(state.ports.verificationResult.value).toBeNull()
    }
  )

  it('discards the local notice without adopting its verdict or changing history', async () => {
    const state = context('OUTCOME_UNKNOWN')
    const completion = state.start()
    state.ports.isModelPlaybackActive.value = true
    state.pending.resolve(state.result)
    await completion
    expect(state.ports.unsavedRunNotifications.size).toBe(1)
    const close = [...state.ports.unsavedRunNotifications][0]!
    close()
    expect(state.ports.unsavedRunNotifications.size).toBe(0)
    expect(state.ports.verificationResult.value).toBeNull()
    expect(state.result.historyPersistence.status).toBe('OUTCOME_UNKNOWN')
    expect(state.ports.loadVerificationRuns).toHaveBeenCalledOnce()
  })
})
