import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { createPagedRequestCoordinator } from '@/utils/pagedRequestCoordinator'
import { isRecordMissing, readDeletionReceipt } from '@/utils/boardWriteReceipt'
import { isSameBoardRunTarget, type BoardRunTarget } from './runDeepLink'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const start = board.indexOf('<script setup')
const source = ts.createSourceFile('Board.ts',
  board.slice(board.indexOf('>', start) + 1, board.indexOf('</script>', start)), ts.ScriptTarget.Latest, true)

const bind = (name: string, ports: Record<string, unknown>) => {
  const statement = source.statements.find(statement => ts.isVariableStatement(statement)
    && statement.declarationList.declarations.some(item => item.name.getText(source) === name))
  if (!statement) throw new Error('Missing Board function ' + name)
  const code = ts.transpileModule(statement.getText(source), {
    compilerOptions: { target: ts.ScriptTarget.ES2022 }
  }).outputText
  return new Function('ports', 'with (ports) { ' + code + '; return ' + name + ' }')(ports)
}

const failure = (status: number) => ({ response: { status } })

const historyState = (target: BoardRunTarget | null, playback: string | null) => {
  const queryTarget = ref<BoardRunTarget | null>(target)
  const gone = () => vi.fn().mockRejectedValue(failure(404))
  const ports = {
    appliedDeepLinkTarget: target as BoardRunTarget | null,
    deepLinkTarget: queryTarget,
    pendingHistoryRunTarget: null as BoardRunTarget | null,
    isSameBoardRunTarget, isRecordMissing,
    captureBoardRunOwner: vi.fn<() => () => boolean>(() => () => true),
    historyDetailRequests: createPagedRequestCoordinator(),
    boardApi: { getVerificationRun: gone(), getVerificationTrace: gone() },
    simulationApi: { getSimulation: gone() },
    fuzzingApi: { getTask: gone() },
    verificationResult: ref<{ historyPersistence: { runId: number }; traces: { id?: number }[] } | null>(null),
    fuzzingResult: ref<{ id: number } | null>(null),
    showFuzzingResultDialog: ref(false),
    activePlaybackKind: ref(playback),
    lastSimulationResult: ref<{ historyPersistence: { runId: number } } | null>(
      target && { historyPersistence: { runId: target.runId } }
    ),
    simulationResultStale: ref(true),
    applyDeepLinkTarget: vi.fn(async (next: BoardRunTarget | null, _checkedRun?: unknown) => {
      ports.appliedDeepLinkTarget = next
    }),
    closeFuzzingResult: vi.fn(),
    clearRunDeepLink: vi.fn(() => { queryTarget.value = null }),
    notifyBlocked: vi.fn(),
    t: (key: string) => key
  }
  return { ports, recheck: bind('recheckAddressedHistory', ports) }
}

const deletionState = (target: BoardRunTarget) => {
  const base = historyState(target, null).ports
  const remove = vi.fn().mockRejectedValue(new Error('Deletion response lost'))
  const ports = Object.assign(base, {
    boardApi: { ...base.boardApi, deleteVerificationRun: remove },
    simulationApi: { ...base.simulationApi, deleteSimulation: remove },
    fuzzingApi: { ...base.fuzzingApi, deleteRun: remove, getRun: vi.fn().mockResolvedValue({ id: target.runId }) },
    confirmDestructive: vi.fn().mockResolvedValue(true),
    pendingHistoryDeleteKeys: ref(new Set<string>()), readDeletionReceipt,
    formatRunTimestamp: () => '', locale: ref('en'),
    loadFuzzingRuns: vi.fn().mockResolvedValue(true),
    verificationHistoryRequests: createPagedRequestCoordinator(), fuzzingHistoryRequests: createPagedRequestCoordinator(),
    simulationHistoryRequests: createPagedRequestCoordinator(), simulationRuns: ref([{ id: target.runId }, { id: 9 }]),
    verificationRuns: ref([{ id: target.runId }, { id: 9 }]), fuzzingRuns: ref([{ id: target.runId }, { id: 9 }]),
    unavailableVerificationRunIds: new Set(), unavailableFuzzingRunIds: new Set(),
    unavailableSimulationTraceIds: new Set(), simulationPlaybackResult: ref(base.lastSimulationResult.value),
    unavailableVerificationTraceIds: new Set(), unavailableFuzzingFindingIds: new Set(),
    verificationRepairBlocks: ref(new Map()), fuzzingRunsPage: ref(1), fuzzingRunsHasMore: ref(true),
    closePlayback: vi.fn(), closeResultDialog: vi.fn(), clearFuzzNotifications: vi.fn(),
    currentTrace: ref(null), activeFuzzingFinding: ref(null), fuzzingResultLoading: ref(false),
    notifySuccess: vi.fn(), notifyError: vi.fn(),
    localizedErrorMessage: (_error: unknown, fallback: string) => fallback,
    publishBoardInvalidation: vi.fn(), currentAuthUserId: ref(42)
  })
  for (const name of ['beginHistoryDelete', 'finishHistoryDelete', 'dismissDeletedRunSurfaces', 'deleteHistoryRun']) {
    Object.assign(ports, { [name]: bind(name, ports) })
  }
  const deleteHandler = { verification: 'deleteVerificationRun', simulation: 'deleteSimulationRun', exploration: 'deleteFuzzingRun' }
  return { ports, remove, deleteRun: bind(deleteHandler[target.kind], ports) }
}

describe('visible saved history after a deletion', () => {
  it('keeps an exploration row on a later page when a direct read proves it still exists', async () => {
    const target = { kind: 'exploration', runId: 7 } as const
    const { ports, remove, deleteRun } = deletionState(target)
    const run = { id: 7, findings: [] }

    await deleteRun(run)

    expect(remove).toHaveBeenCalledExactlyOnceWith(7)
    expect(ports.fuzzingApi.getRun).toHaveBeenCalledExactlyOnceWith(7)
    expect(ports.loadFuzzingRuns).not.toHaveBeenCalled()
    expect(ports.fuzzingRuns.value).toContainEqual({ id: 7 })
    expect(ports.fuzzingRunsPage.value).toBe(1)
    expect(ports.clearRunDeepLink).not.toHaveBeenCalled()
    expect(ports.notifySuccess).not.toHaveBeenCalled()
    expect(ports.notifyBlocked).toHaveBeenCalledExactlyOnceWith('app.historyDeleteOutcomeUnconfirmed')
  })

  it.each(['lost response', 'already missing'] as const)('removes only the deleted verification and clears its pending replay link after %s', async outcome => {
    const target = { kind: 'verification', runId: 7, traceId: 88 } as const
    const { ports, remove, deleteRun } = deletionState(target)
    ports.pendingHistoryRunTarget = target
    if (outcome === 'already missing') remove.mockRejectedValueOnce(failure(404))

    await deleteRun({ id: 7, counterexamples: [{ id: 88 }] })

    expect(remove).toHaveBeenCalledExactlyOnceWith(7)
    expect(ports.boardApi.getVerificationRun).toHaveBeenCalledExactlyOnceWith(7)
    expect(ports.verificationRuns.value).toEqual([{ id: 9 }])
    expect(ports.pendingHistoryRunTarget).toBeNull()
    expect(ports.clearRunDeepLink).toHaveBeenCalledOnce()
    expect(ports.publishBoardInvalidation).toHaveBeenCalledExactlyOnceWith(42, 'manual', 'run-history')
    expect(ports.notifySuccess).not.toHaveBeenCalled()
  })

  it.each(['cancelled', 'session changed'])('does not send repeated deletions after a confirmation is %s', async outcome => {
    const { ports, remove, deleteRun } = deletionState({ kind: 'verification', runId: 7 })
    let ownsBoard = true
    ports.captureBoardRunOwner.mockReturnValue(() => ownsBoard)
    let answer!: (confirmed: boolean) => void
    ports.confirmDestructive.mockReturnValueOnce(new Promise(resolve => { answer = resolve }))
    const pendingDetail = ports.historyDetailRequests.beginReplace()
    const run = { id: 7, counterexamples: [] }
    const deleting = deleteRun(run)
    await deleteRun(run)
    expect(ports.confirmDestructive).toHaveBeenCalledOnce()

    ownsBoard = outcome !== 'session changed'
    answer(outcome !== 'cancelled')
    await deleting

    expect(remove).not.toHaveBeenCalled()
    expect(ports.historyDetailRequests.isCurrent(pendingDetail)).toBe(true)
    expect(ports.pendingHistoryDeleteKeys.value.size).toBe(0)
  })

  it('removes the confirmed simulation result from replay and cache without another read', async () => {
    const { ports, remove, deleteRun } = deletionState({ kind: 'simulation', runId: 7 })
    remove.mockResolvedValueOnce(undefined)

    await deleteRun({ id: 7 })

    expect(ports.simulationApi.getSimulation).not.toHaveBeenCalled()
    expect(ports.closePlayback).toHaveBeenCalledOnce()
    expect(ports.lastSimulationResult.value).toBeNull()
    expect(ports.simulationRuns.value).toEqual([{ id: 9 }])
    expect(ports.clearRunDeepLink).toHaveBeenCalledOnce()
    expect(ports.notifySuccess).toHaveBeenCalledExactlyOnceWith('app.simulationRunDeleted')
  })

  it.each([
    ['verification', { kind: 'verification', runId: 7 }, null, 'openRunDeletedElsewhere'],
    ['counterexample', { kind: 'verification', runId: 7, traceId: 88 }, 'counterexample',
      'openCounterexampleRunDeletedElsewhere'],
    ['pending counterexample', { kind: 'verification', runId: 7, traceId: 88 }, null,
      'openCounterexampleRunDeletedElsewhere'],
    ['simulation', { kind: 'simulation', runId: 7 }, 'simulation', 'openSimulationRunDeletedElsewhere'],
    ['finding', { kind: 'exploration', runId: 7, findingId: 99 }, 'fuzzing', 'openFuzzingFindingRunDeletedElsewhere']
  ] as const)('closes a missing %s after checking the addressed record', async (_name, target, playback, notice) => {
    const { ports, recheck } = historyState(target, playback)

    await expect(recheck()).resolves.toBe(true)

    if (target.kind === 'verification') {
      if ('traceId' in target) {
        expect(ports.boardApi.getVerificationTrace).toHaveBeenCalledWith(target.traceId)
        expect(ports.boardApi.getVerificationRun).not.toHaveBeenCalled()
      } else {
        expect(ports.boardApi.getVerificationRun).toHaveBeenCalledWith(target.runId)
      }
    } else if (target.kind === 'simulation') {
      expect(ports.simulationApi.getSimulation).toHaveBeenCalledWith(target.runId)
      expect(ports.lastSimulationResult.value).toBeNull()
      expect(ports.simulationResultStale.value).toBe(false)
    } else {
      expect(ports.fuzzingApi.getTask).toHaveBeenCalledWith(target.runId)
    }
    expect(ports.applyDeepLinkTarget).toHaveBeenCalledWith(null)
    expect(ports.deepLinkTarget.value).toBeNull()
    expect(ports.notifyBlocked).toHaveBeenCalledExactlyOnceWith('app.' + notice)
  })

  it('keeps the view and link when the record exists or storage is temporarily unavailable', async () => {
    const target = { kind: 'verification', runId: 7, traceId: 88 } as const
    const { ports, recheck } = historyState(target, 'counterexample')
    ports.boardApi.getVerificationTrace.mockResolvedValueOnce({}).mockRejectedValueOnce(failure(503))

    await expect(recheck()).resolves.toBe(true)
    await expect(recheck()).resolves.toBe(false)

    expect(ports.deepLinkTarget.value).toEqual(target)
    expect(ports.applyDeepLinkTarget).not.toHaveBeenCalled()
    expect(ports.notifyBlocked).not.toHaveBeenCalled()
  })

  it('removes only the deleted counterexample from an open verification result', async () => {
    const target = { kind: 'verification', runId: 7 } as const
    const { ports, recheck } = historyState(target, null)
    ports.verificationResult.value = { historyPersistence: { runId: 7 }, traces: [{ id: 88 }, { id: 89 }] }
    ports.boardApi.getVerificationRun.mockResolvedValueOnce({ counterexamples: [{ id: 89 }] })

    await expect(recheck()).resolves.toBe(true)

    expect(ports.verificationResult.value.traces).toEqual([{ id: 89 }])
    expect(ports.deepLinkTarget.value).toEqual(target)
    expect(ports.applyDeepLinkTarget).not.toHaveBeenCalled()
    expect(ports.notifyBlocked).not.toHaveBeenCalled()
  })

  it('reloads an addressed verification result when its evidence request is still pending', async () => {
    const target = { kind: 'verification', runId: 7 } as const
    const { ports, recheck } = historyState(target, null)
    const checkedRun = { counterexamples: [{ id: 89 }] }
    ports.boardApi.getVerificationRun.mockResolvedValue(checkedRun)

    await expect(recheck()).resolves.toBe(true)
    expect(ports.applyDeepLinkTarget).not.toHaveBeenCalled()

    ports.historyDetailRequests.beginReplace()
    await expect(recheck()).resolves.toBe(true)
    expect(ports.applyDeepLinkTarget).toHaveBeenCalledExactlyOnceWith(target, checkedRun)
    expect(ports.deepLinkTarget.value).toEqual(target)
    expect(ports.notifyBlocked).not.toHaveBeenCalled()
  })

  it('reuses the checked run for replacement evidence and cancels it when History closes', async () => {
    const target = { kind: 'verification', runId: 7 } as const
    const { ports, recheck } = historyState(target, null)
    const navigation = Object.assign(ports, {
      staleDeepLink: ref(false),
      boardLifecycleDisposed: false,
      historyPanelIntentEpoch: 0,
      closePlayback: vi.fn(), closeResultSurfaces: ports.historyDetailRequests.invalidate,
      closeHistoryPanel: vi.fn(), refreshCurrentBoardModelFingerprints: vi.fn(),
      shouldLoadVerificationEvidence: () => true,
      buildVerificationResultFromRun: () => ({ historyPersistence: { runId: 7 }, traces: [] }),
      navigateToRunTarget: async (next: BoardRunTarget) => { ports.appliedDeepLinkTarget = next }
    })
    const openRunTarget = bind('openRunTarget', navigation)
    const retireHistoryPanelIntent = bind('retireHistoryPanelIntent', navigation)
    ports.boardApi.getVerificationRun.mockResolvedValue({ counterexamples: [] })

    let releaseOld!: () => void
    let releaseNew!: () => void
    const oldResponse = new Promise<void>(resolve => { releaseOld = resolve })
    const newResponse = new Promise<void>(resolve => { releaseNew = resolve })
    const getVerificationRunTraces = vi.fn().mockReturnValueOnce(oldResponse).mockReturnValueOnce(newResponse)
    Object.assign(ports.boardApi, { getVerificationRunTraces })
    Object.assign(navigation, {
      openVerificationRun: bind('openVerificationRun', navigation),
      loadDeepLinkTarget: bind('loadDeepLinkTarget', navigation)
    })
    ports.applyDeepLinkTarget.mockImplementation(bind('applyDeepLinkTarget', navigation))

    const opening = openRunTarget(target, true)
    await vi.waitFor(() => expect(getVerificationRunTraces).toHaveBeenCalledOnce())
    const refreshing = recheck()
    await vi.waitFor(() => expect(getVerificationRunTraces).toHaveBeenCalledTimes(2))
    releaseOld()
    await opening

    retireHistoryPanelIntent()
    releaseNew()
    await refreshing
    expect(ports.boardApi.getVerificationRun).toHaveBeenCalledTimes(2)
    expect(ports.deepLinkTarget.value).toBeNull()
    expect(ports.verificationResult.value).toBeNull()
  })

  it('closes a deleted foreground exploration result without changing the address', async () => {
    const { ports, recheck } = historyState(null, null)
    ports.fuzzingResult.value = { id: 7 }
    ports.showFuzzingResultDialog.value = true

    await expect(recheck()).resolves.toBe(true)

    expect(ports.fuzzingApi.getTask).toHaveBeenCalledExactlyOnceWith(7)
    expect(ports.closeFuzzingResult).toHaveBeenCalledOnce()
    expect(ports.applyDeepLinkTarget).not.toHaveBeenCalled()
    expect(ports.clearRunDeepLink).not.toHaveBeenCalled()
    expect(ports.notifyBlocked).toHaveBeenCalledExactlyOnceWith('app.openFuzzingRunDeletedElsewhere')
  })

  it('keeps a newer foreground exploration from being closed by the previous result lookup', async () => {
    const { ports, recheck } = historyState(null, null)
    ports.fuzzingResult.value = { id: 7 }
    ports.showFuzzingResultDialog.value = true
    let reject!: (error: unknown) => void
    ports.fuzzingApi.getTask.mockReturnValueOnce(new Promise((_, fail) => { reject = fail }))
    const pending = recheck()
    expect(ports.fuzzingApi.getTask).toHaveBeenCalledExactlyOnceWith(7)
    ports.fuzzingResult.value = { id: 9 }
    reject(failure(404))

    await expect(pending).resolves.toBe(true)
    expect(ports.closeFuzzingResult).not.toHaveBeenCalled()
    expect(ports.notifyBlocked).not.toHaveBeenCalled()
  })

  it('keeps an incoming replay when a missing-record response arrives for the old link', async () => {
    const { ports, recheck } = historyState({ kind: 'verification', runId: 7, traceId: 88 }, 'counterexample')
    let reject!: (error: unknown) => void
    ports.boardApi.getVerificationTrace.mockReturnValueOnce(new Promise((_, fail) => { reject = fail }))
    const pending = recheck()
    ports.deepLinkTarget.value = { kind: 'simulation', runId: 9 }
    reject(failure(404))

    await expect(pending).resolves.toBe(true)
    expect(ports.deepLinkTarget.value).toEqual({ kind: 'simulation', runId: 9 })
    expect(ports.applyDeepLinkTarget).not.toHaveBeenCalled()
    expect(ports.notifyBlocked).not.toHaveBeenCalled()
  })

  it('does not close a different visible replay when a same-tab deletion matches cached evidence', () => {
    const target = { kind: 'verification', runId: 7 } as const
    const { ports } = deletionState(target)
    Object.assign(ports, { currentTrace: ref({ verificationTaskId: 7 }) })
    ports.activePlaybackKind.value = 'simulation'
    ports.appliedDeepLinkTarget = { kind: 'simulation', runId: 9 }
    const dismiss = bind('dismissDeletedRunSurfaces', ports)

    dismiss(target)

    expect(ports.closePlayback).not.toHaveBeenCalled()
    expect(ports.closeResultDialog).not.toHaveBeenCalled()
    expect(ports.clearRunDeepLink).not.toHaveBeenCalled()
  })
})
