import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { computed, nextTick, ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { errorDetails, technicalErrorLines } from '@/utils/userMessage'
import { countResponseTriggerWarnings, getVerificationOutcome } from './verificationResult'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const setupStart = board.indexOf('<script setup')
const setup = board.slice(board.indexOf('>', setupStart) + 1, board.indexOf('</script>', setupStart))
const source = ts.createSourceFile('Board.ts', setup, ts.ScriptTarget.Latest, true)

// Execute the component's own functions so a missing guard fails the workflow assertion.
const bind = (name: string, ports: Record<string, unknown>) => {
  const declaration = source.statements.find(statement => ts.isVariableStatement(statement)
    && statement.declarationList.declarations.some(item => item.name.getText(source) === name))
  if (!declaration) throw new Error(`Missing Board function ${name}`)
  const javascript = ts.transpileModule(declaration.getText(source), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None }
  }).outputText
  // Keep mutable component-scope counters live across awaits and deferred result actions.
  return new Function('ports', `with (ports) { ${javascript}; return ${name} }`)(ports)
}

const deferred = () => {
  let resolve!: (value?: any) => void
  const promise = new Promise<any>(done => { resolve = done })
  return { promise, resolve }
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
})

describe('tracking an existing background task', () => {
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
  ])('opens %s progress and reports a confirmed local cancellation once', async (_kind, suffix, api) => {
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
      [api]: { cancelTask: vi.fn().mockResolvedValue({ cancellationAccepted: true,
        cancellationOutcome: 'ACCEPTED', taskStatus: 'CANCELLED', executionMayStillBeStopping: false }) },
      [`pollAsync${suffix}`]: vi.fn(() => new Promise((_resolve, reject) => { rejectPoll = reject }))
    }
    for (const taskKind of ['verification', 'simulation', 'fuzzing']) {
      const title = taskKind[0]!.toUpperCase() + taskKind.slice(1)
      ports[`is${taskKind === 'verification' ? 'Verifying' : taskKind === 'simulation' ? 'Simulating' : 'Fuzzing'}`] = ref(false)
      ports[`show${title}Panel`] = ref(false)
      ports[`${taskKind}Tasks`] = ref([])
      ports[`${taskKind}Error`] = ref(null)
      ports[`async${title}Active`] = ref(false)
      ports[`async${title}Task`] = ref({ taskId: null })
      ports[`cancelling${title}Task`] = ref(false)
      ports[`${taskKind}CancelRequested`] = ref(false)
    }
    ports.notifyTaskCancellationResult = bind('notifyTaskCancellationResult', ports)
    const watching = bind(`watch${suffix}Task`, ports)(7)
    try {
      expect(ports[`show${suffix}Panel`].value).toBe(true)
      ports[`show${suffix}Panel`].value = false
      await bind(`cancelAsync${suffix}`, ports)()
      expect(ports.notifyInfo).toHaveBeenCalledOnce()
    } finally {
      rejectPoll(new Error('Task cancelled'))
      await watching
    }
    expect(ports.notifyInfo).toHaveBeenCalledOnce()
    expect(ports.loadTaskInbox).toHaveBeenCalledOnce()
  })

  it.each(['verification', 'simulation', 'fuzzing'])('uses visible %s status instead of a duplicate cancellation toast', kind => {
    const ports: Record<string, any> = { t: (key: string) => key,
      notifyInfo: vi.fn(), notifyBlocked: vi.fn(), showHistoryPanel: ref(true), activeHistoryLayer: ref('tasks'),
      showVerificationPanel: ref(false), showSimulationPanel: ref(false), showFuzzingPanel: ref(false) }
    const notify = bind('notifyTaskCancellationResult', ports)
    const result = { cancellationOutcome: 'ACCEPTED', executionMayStillBeStopping: true }
    notify(kind, result)
    expect(ports.notifyInfo).not.toHaveBeenCalled()
    ports.showHistoryPanel.value = false
    ports[`show${kind[0]!.toUpperCase() + kind.slice(1)}Panel`].value = true
    notify(kind, result)
    expect(ports.notifyInfo).not.toHaveBeenCalled()
    ports[`show${kind[0]!.toUpperCase() + kind.slice(1)}Panel`].value = false
    notify(kind, result)
    expect(ports.notifyInfo).toHaveBeenCalledOnce()
  })

  it.each(['verification', 'simulation'])('avoids a late cancellation notice over the matching %s result', kind => {
    const ports = { t: (key: string) => key, notifyInfo: vi.fn(), notifyBlocked: vi.fn(),
      showHistoryPanel: ref(false), activeHistoryLayer: ref('tasks'),
      showVerificationPanel: ref(false), showSimulationPanel: ref(false), showFuzzingPanel: ref(false),
      showResultDialog: ref(true), verificationResult: ref({ historyPersistence: { status: 'SAVED', runId: 7 } }),
      simulationAnimationState: ref({ visible: true }),
      lastSimulationResult: ref({ historyPersistence: { status: 'SAVED', runId: 17 } }),
      simulationTasks: ref([{ id: 7, simulationTraceId: 17 }]) }
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
      clearRunDeepLink: vi.fn(), closeResultSurfaces: vi.fn(), loadDeepLinkTarget: vi.fn()
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
  it.each([false, true])('opens settings when their previous visibility was %s', alreadyOpen => {
    const showVerificationPanel = ref(alreadyOpen)
    const dismissResultDialog = vi.fn()
    const openVerificationFromActionDock = vi.fn(() => {
      expect(showVerificationPanel.value).toBe(false)
      showVerificationPanel.value = true
    })
    bind('reopenVerificationSettingsFromResult', {
      isModelPlaybackActive: ref(false), isAnyRecommendationRunning: () => false,
      showVerificationPanel, dismissResultDialog, openVerificationFromActionDock
    })()
    expect(dismissResultDialog).toHaveBeenCalledOnce()
    expect(openVerificationFromActionDock).toHaveBeenCalledOnce()
    expect(dismissResultDialog.mock.invocationCallOrder[0])
      .toBeLessThan(openVerificationFromActionDock.mock.invocationCallOrder[0]!)
    expect(showVerificationPanel.value).toBe(true)
  })

  it.each(['playback', 'recommendation'])('keeps the result visible when %s blocks the existing opener', blocker => {
    const dismissResultDialog = vi.fn()
    const showVerificationPanel = ref(true)
    const openVerificationFromActionDock = vi.fn()
    bind('reopenVerificationSettingsFromResult', {
      isModelPlaybackActive: ref(blocker === 'playback'),
      isAnyRecommendationRunning: () => blocker === 'recommendation',
      showVerificationPanel, dismissResultDialog, openVerificationFromActionDock
    })()
    expect(openVerificationFromActionDock).toHaveBeenCalledOnce()
    expect(dismissResultDialog).not.toHaveBeenCalled()
    expect(showVerificationPanel.value).toBe(true)
  })
})

describe('run failures during playback', () => {
  const context = (playback: boolean) => {
    const owner = { current: true }
    const ports: Record<string, any> = {
      isModelPlaybackActive: ref(playback), verificationError: ref<string | null>(null),
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

  it.each(['verification', 'simulation'] as const)('keeps a rejected watched %s task from interrupting replay', async kind => {
    const failure = context(true).ports
    const flag = () => ({ value: false })
    const ports = {
      isVerifying: flag(), isSimulating: flag(),
      asyncVerificationTask: { value: { taskId: null } }, asyncSimulationTask: { value: { taskId: null } },
      showVerificationPanel: flag(), showSimulationPanel: flag(), notifyInfo: vi.fn(),
      verificationTasks: { value: [] }, simulationTasks: { value: [] },
      asyncVerificationActive: flag(), asyncSimulationActive: flag(),
      verificationCancelRequested: flag(), simulationCancelRequested: flag(),
      cancellingVerificationTask: flag(), cancellingSimulationTask: flag(),
      normalizeTaskProgress: () => 0, taskProgressStatusKey: () => 'app.taskInitializing', closeHistoryPanel: vi.fn(),
      pollAsyncVerification: vi.fn().mockRejectedValue(new Error('run failed')),
      pollAsyncSimulation: vi.fn().mockRejectedValue(new Error('run failed')),
      isPollingAbortedError: () => false, isCompletedTaskResultUnavailableError: () => false,
      extractApiErrorMessage: (error: Error) => error.message, isAsyncTaskCancelledError: () => false,
      ...failure, presentRunFailure: bind('presentRunFailure', failure),
      semanticSceneChangeCount: 0, boardLifecycleDisposed: false, loadTaskInbox: vi.fn().mockResolvedValue(true)
    }
    await bind(kind === 'verification' ? 'watchVerificationTask' : 'watchSimulationTask', ports)(7)
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

describe('stale fuzz domain feedback', () => {
  const context = (visible: boolean, refreshed: boolean, validPath = true) => {
    const ports: Record<string, any> = {
      isFuzzing: ref(false), isSceneReplacementInProgress: ref(false),
      waitForPendingFixRefresh: vi.fn(), waitForPendingBoardMutations: vi.fn(),
      ensureBoardDataReady: () => true, nodes: ref([{}]), specifications: ref([{ id: 'spec-1' }]),
      rules: ref([]), assertRulesHaveTriggers: () => true,
      fuzzingWorkloadReady: ref(true), fuzzingContentCommandUnsupported: ref(false),
      knownFuzzEligibleSpecifications: ref([{ id: 'spec-1' }]), effectiveFuzzingConfigurationError: ref(null),
      fuzzingForm: { explorationMode: 'PAPER_COMPATIBLE', targetSelectionMode: 'ALL',
        maxIterations: 2, populationSize: 1, pathLength: 2, seed: 42 },
      paperDomainPreview: ref({ modelFingerprint: 'a'.repeat(64) }), isValidFuzzPaperDomainFingerprint: () => true,
      fuzzingWatchedTask: ref(null), asyncFuzzingActive: ref(false), cancellingFuzzingTask: ref(false),
      fuzzingCancelRequested: ref(false), fuzzingError: ref<string | null>(null), fuzzingSettingsNotice: ref(null),
      asyncFuzzingTask: ref({}), showFuzzingPanel: ref(visible), fuzzingFailureDetails: ref({}),
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
    return { ports, run: bind('runFuzzing', ports) }
  }

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
  it.each(['SAVED', 'NOT_REQUESTED', 'FAILED', 'OUTCOME_UNKNOWN'])(
    'addresses only a saved run when opening %s simulation playback', status => {
      const ports = {
        lastSimulationResult: ref({ playbackScene: { nodes: [] },
          historyPersistence: { status, ...(status === 'SAVED' ? { runId: 17 } : {}) } }),
        savedSimulationStates: ref([{ stateIndex: 0 }]), simulationAnimationState: ref({ visible: false }),
        playbackPanelOpen: ref(false), highlightedTrace: ref(null), staleDeepLink: ref(true),
        activatePlaybackScene: vi.fn(), focusCanvasForReplay: vi.fn(),
        navigateToRunTarget: vi.fn(), clearRunDeepLink: vi.fn()
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
      isLiveBoardEditorVisible: flag(), showSimulationPanel: ref(true),
      lastSimulationResult, frozenScene, unsavedRunNotifications: new Set<() => void>(),
      adoptSimulationRunResult: vi.fn(), openSimulationAnimationFromSavedStates: vi.fn(),
      notifySimulationOutcome: vi.fn(), notifyAutomaticPlaybackDeferred: vi.fn(),
      notifyAutomaticPlaybackDeferredForReplay: vi.fn(), notifyAvailableResult: vi.fn(),
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
    ports.notifyAutomaticPlaybackDeferredForReplay.mockImplementation(
      bind('notifyAutomaticPlaybackDeferredForReplay', ports))
    const run = bind('handleSimulate', ports)
    const start = () => run({ steps: 1, isAsync, saveToHistory: status !== 'NOT_REQUESTED' })
    return { previous, result, ports, owner, pending, start,
      savedStates: bind('savedSimulationStates', { computed, lastSimulationResult }) }
  }

  it.each([false, true])('opens the completed trajectory without a duplicate toast (async=%s)', async isAsync => {
    const state = context('SAVED', isAsync)
    const completion = state.start()
    await vi.waitFor(() => expect(state.ports.simulationApi[isAsync ? 'simulateAsync' : 'simulateAndSave']).toHaveBeenCalledOnce())
    state.pending.resolve(state.result)
    expect(await completion).toBe(true)
    expect(state.ports.openSimulationAnimationFromSavedStates).toHaveBeenCalledOnce()
    expect(state.ports.notifySimulationOutcome).not.toHaveBeenCalled()
  })

  it.each(['SAVED', 'NOT_REQUESTED', 'FAILED', 'OUTCOME_UNKNOWN'])(
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
    ['UNREACHABLE', true], ['INCONCLUSIVE', true], ['REACHABLE', false], ['NOT_APPLICABLE', false]
  ] as const)(
    'announces a background formula pass according to its %s trigger evidence', (antecedentReachability, warning) => {
      const ports = {
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
      buildVerificationResultFromTask: () => result, showVerificationPanel: ref(true),
      historyDetailRequests: { invalidate: vi.fn() }, deepLinkLoadRequests: { invalidate: vi.fn() },
      staleDeepLink: ref(true), navigateToRunTarget: vi.fn().mockResolvedValue(undefined), clearRunDeepLink: vi.fn(),
      adoptVerificationRunResult: vi.fn(),
      isModelPlaybackActive: flag(), notifyVerificationOutcome: vi.fn(),
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
    return { ports, pending, owner, result, start: isAsync
      ? () => bind('pollAsyncVerification', ports)(17, { presentResult: true })
      : bind('handleVerify', ports) }
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
    expect(state.ports.navigateToRunTarget).toHaveBeenCalledExactlyOnceWith({ kind: 'verification', runId: 17 }, 'push')
    expect(state.ports.historyDetailRequests.invalidate).toHaveBeenCalledOnce()
    expect(state.ports.deepLinkLoadRequests.invalidate).toHaveBeenCalledOnce()
    expect(state.ports.staleDeepLink.value).toBe(false)
    expect(state.ports.clearRunDeepLink).not.toHaveBeenCalled()
    expect(state.ports.notifyVerificationOutcome).toHaveBeenCalledWith(state.result, { presenting: true })
  })

  it('keeps a background asynchronous completion from changing the selected run', async () => {
    const state = context('SAVED', true)
    state.ports.showVerificationPanel.value = false
    const completion = bind('pollAsyncVerification', state.ports)(17)
    state.pending.resolve(state.result)
    await completion
    expect(state.ports.adoptVerificationRunResult).not.toHaveBeenCalled()
    expect(state.ports.navigateToRunTarget).not.toHaveBeenCalled()
    expect(state.ports.notifyVerificationOutcome).toHaveBeenCalledWith(state.result)
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
