import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { i18n } from '@/assets/i18n'
import { createPagedRequestCoordinator } from '@/utils/pagedRequestCoordinator'
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
    isSameBoardRunTarget,
    captureBoardRunOwner: vi.fn(() => () => true),
    historyDetailRequests: createPagedRequestCoordinator(),
    boardApi: { getVerificationRun: gone(), getVerificationTrace: gone() },
    simulationApi: { getSimulation: gone() },
    fuzzingApi: { getTask: gone() },
    verificationResult: ref<{ historyPersistence: { runId: number }; traces: { id?: number }[] } | null>(null),
    fuzzingResult: ref<{ id: number } | null>(null),
    showFuzzingResultDialog: ref(false),
    activePlaybackKind: ref(playback),
    savedTraces: ref<unknown[]>([{}]),
    lastSimulationResult: ref<{ historyPersistence: { runId: number } } | null>(
      target && { historyPersistence: { runId: target.runId } }
    ),
    simulationResultStale: ref(true),
    applyDeepLinkTarget: vi.fn(async (next: BoardRunTarget | null) => { ports.appliedDeepLinkTarget = next }),
    openRunTarget: vi.fn<(next: BoardRunTarget, fromHistory: boolean) => Promise<void>>(),
    closeFuzzingResult: vi.fn(),
    clearRunDeepLink: vi.fn(() => { queryTarget.value = null }),
    notifyBlocked: vi.fn(),
    t: (key: string) => key
  }
  return { ports, recheck: bind('recheckAddressedHistory', ports) }
}

describe('visible saved history after a deletion', () => {
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
    ports.boardApi.getVerificationRun.mockResolvedValue({ counterexamples: [{ id: 89 }] })

    await expect(recheck()).resolves.toBe(true)
    expect(ports.applyDeepLinkTarget).not.toHaveBeenCalled()

    ports.historyDetailRequests.beginReplace()
    await expect(recheck()).resolves.toBe(true)
    expect(ports.applyDeepLinkTarget).toHaveBeenCalledExactlyOnceWith(target)
    expect(ports.deepLinkTarget.value).toEqual(target)
    expect(ports.notifyBlocked).not.toHaveBeenCalled()
  })

  it('closing History also cancels its restarted verification result after the old load settles', async () => {
    const target = { kind: 'verification', runId: 7 } as const
    const { ports, recheck } = historyState(target, null)
    const navigation = Object.assign(ports, {
      staleDeepLink: ref(false),
      boardLifecycleDisposed: false,
      historyPanelIntentEpoch: 0,
      navigateToRunTarget: async (next: BoardRunTarget) => { ports.appliedDeepLinkTarget = next }
    })
    const openRunTarget = bind('openRunTarget', navigation)
    const retireHistoryPanelIntent = bind('retireHistoryPanelIntent', navigation)
    ports.openRunTarget.mockImplementation(openRunTarget)
    ports.boardApi.getVerificationRun.mockResolvedValue({ counterexamples: [] })

    let releaseOld!: () => void
    let releaseNew!: () => void
    const oldResponse = new Promise<void>(resolve => { releaseOld = resolve })
    const newResponse = new Promise<void>(resolve => { releaseNew = resolve })
    const loadVerdict = (response: Promise<void>) => async () => {
      const request = ports.historyDetailRequests.beginReplace()
      await response
      if (ports.historyDetailRequests.isCurrent(request)) {
        ports.verificationResult.value = { historyPersistence: { runId: 7 }, traces: [] }
      }
      ports.historyDetailRequests.finish(request)
    }
    ports.applyDeepLinkTarget.mockImplementationOnce(loadVerdict(oldResponse))
      .mockImplementationOnce(loadVerdict(newResponse))

    const opening = openRunTarget(target, true)
    await vi.waitFor(() => expect(ports.historyDetailRequests.hasPendingReplace()).toBe(true))
    const refreshing = recheck()
    await vi.waitFor(() => expect(ports.applyDeepLinkTarget).toHaveBeenCalledTimes(2))
    releaseOld()
    await opening

    retireHistoryPanelIntent()
    releaseNew()
    await refreshing
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
    const ports = {
      verificationResult: ref(null),
      activePlaybackKind: ref('simulation'),
      currentTrace: ref({ verificationTaskId: 7 }),
      savedTraces: ref([{ verificationTaskId: 7 }]),
      closeTraceAnimation: vi.fn(),
      dismissResultDialog: vi.fn()
    }
    const dismiss = bind('dismissRunSurfacesForDeletedVerificationRun', ports)

    dismiss(7)

    expect(ports.closeTraceAnimation).not.toHaveBeenCalled()
    expect(ports.dismissResultDialog).not.toHaveBeenCalled()
  })

  it('describes both possible reasons a saved SMV model is unavailable', () => {
    expect(i18n.global.t('app.smvModelNotAvailable', {}, { locale: 'en' }))
      .toMatch(/deleted.*without one.*history/i)
    expect(i18n.global.t('app.smvModelNotAvailable', {}, { locale: 'zh-CN' }))
      .toMatch(/删除.*未留下模型.*运行历史/)
  })
})
