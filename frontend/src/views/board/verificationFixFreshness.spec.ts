import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { computed, ref } from 'vue'
import { transpileModule, ScriptTarget } from 'typescript'
import { describe, expect, it, vi } from 'vitest'
import { compareModelSnapshot } from '@/utils/modelComparison'
import { getVerificationRepairRunBlock, getVerificationRepairTraceBlock } from '@/utils/verificationRepair'
import type { ModelRunSnapshot, RunBoardComparison } from '@/types/modelSemantics'
import i18n from '@/assets/i18n'

const board = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const resultStart = board.indexOf('const verificationRepairBlocks =')
const resultEnd = board.indexOf('const cancelAsyncVerification =', resultStart)
const historyStart = board.indexOf('const openFixForVerificationTrace =')
const historyEnd = board.indexOf('/**', historyStart)
if ([resultStart, resultEnd, historyStart, historyEnd].some(index => index < 0)) {
  throw new Error('Verification Fix admission is missing')
}
const createAdmission = new Function('computed', 'ref', 'verificationResultStale', 'verificationResult',
  'verificationBoardComparison', 'loadingCurrentBoardModelFingerprints', 'hasPersistedVerificationTrace',
  'openFixDialog', 't', 'verificationRuns', 'compareRunToCurrentBoard',
  'getVerificationRepairRunBlock', 'getVerificationRepairTraceBlock',
  'closeHistoryPanel', 'showFixDialog', 'fixTraceId', 'refreshCurrentBoardModelFingerprints',
  'boardLifecycleDisposed', 'currentAuthUserId', 'boardOwnerUserId',
  transpileModule(`${board.slice(resultStart, resultEnd)}
    ${board.slice(historyStart, historyEnd)}
    const localizeBlock = key => key == null ? null : t(key)
    return { blockedReason: computed(() => localizeBlock(verificationRunRepairBlock.value)),
      traceBlockReason: trace => localizeBlock(verificationResultTraceRepairBlock(trace)),
      canFix: canFixVerificationResultTrace,
      needsReverify: verificationNeedsReverify, openResult: openFixForVerificationResultTrace,
      openHistory: openFixForVerificationTrace, rejectEvidence: handleFixModelSemanticsChanged }`, {
    compilerOptions: { target: ScriptTarget.ES2022 }
  }).outputText)

const trace = { id: 4, verificationTaskId: 3, violatedSpecId: 'never_on',
  violatedSpec: { name: 'Keep the light off' }, dataAvailable: true }
const currentModels = { verification: 'a'.repeat(64), simulation: 'b'.repeat(64), modelSemanticsVersion: 1 }
const createState = () => {
  const comparison = ref<RunBoardComparison>('UNCHANGED')
  const stale = ref(false)
  const loading = ref(false)
  const persisted = vi.fn().mockReturnValue(true)
  const open = vi.fn()
  const closeHistory = vi.fn()
  const showFixDialog = ref(true)
  const fixTraceId = ref(trace.id)
  const refresh = vi.fn()
  const currentUserId = ref(10)
  const result = ref({ traces: [trace], historyPersistence: { status: 'SAVED', runId: 3 } })
  const fingerprints = ref<typeof currentModels | null>(currentModels)
  const modelSnapshot: ModelRunSnapshot = {
    capturedAt: '2026-11-27T00:00:00', deviceCount: 1, ruleCount: 1, specificationCount: 1,
    environmentVariableCount: 0, deviceTemplateCount: 1, templatesFrozen: true,
    modelFingerprint: currentModels.verification, modelSemanticsVersion: currentModels.modelSemanticsVersion
  }
  const runs = ref([{ id: 3, dataAvailable: true, modelSnapshot }])
  const state = createAdmission(computed, ref, stale, result, comparison, loading, persisted, open,
    (key: string) => i18n.global.t(key, {}, { locale: 'en' }), runs,
    (run: { modelSnapshot: ModelRunSnapshot }) => compareModelSnapshot(run.modelSnapshot, fingerprints.value, 'verification'),
    getVerificationRepairRunBlock, getVerificationRepairTraceBlock,
    closeHistory, showFixDialog, fixTraceId, refresh, false, currentUserId, 10)
  return { ...state, comparison, stale, loading, persisted, open, closeHistory, runs, fingerprints,
    showFixDialog, fixTraceId, refresh, result, currentUserId }
}

describe('verification Fix requires current-model evidence', () => {
  it.each(['CHANGED', 'UNAVAILABLE', 'NOT_COMPARED'] as const)('withholds Fix for %s and blocks direct opening', comparison => {
    const state = createState()
    state.comparison.value = comparison
    expect(state.canFix(trace)).toBe(false)
    expect(state.blockedReason.value).toBeTruthy()
    state.openResult(trace)
    expect(state.open).not.toHaveBeenCalled()
  })

  it('distinguishes pending comparison from an unavailable read without enabling Fix', () => {
    const state = createState()
    state.comparison.value = 'UNAVAILABLE'
    state.loading.value = true
    expect(state.blockedReason.value).toContain('Comparing the current board')
    expect(state.canFix(trace)).toBe(false)
    state.loading.value = false
    expect(state.blockedReason.value).toContain('could not be read')
    expect(state.canFix(trace)).toBe(false)
  })

  it('allows a matching persisted trace and preserves the stale and persistence guards', () => {
    const state = createState()
    expect(state.blockedReason.value).toBeNull()
    expect(state.canFix(trace)).toBe(true)
    state.openResult(trace)
    expect(state.open).toHaveBeenCalledExactlyOnceWith(trace.id, trace.violatedSpecId, trace.violatedSpec.name)
    state.stale.value = true
    expect(state.canFix(trace)).toBe(false)
    state.stale.value = false
    state.persisted.mockReturnValue(false)
    expect(state.canFix(trace)).toBe(false)
  })

  it('uses the owning history run fingerprint before opening a trace', () => {
    const state = createState()
    state.openHistory(trace)
    expect(state.closeHistory).toHaveBeenCalledOnce()
    expect(state.open).toHaveBeenCalledExactlyOnceWith(trace.id, trace.violatedSpecId, trace.violatedSpec.name)
    state.open.mockClear()
    state.fingerprints.value = null
    state.openHistory(trace)
    state.fingerprints.value = { ...currentModels, verification: 'b'.repeat(64) }
    state.openHistory(trace)
    state.fingerprints.value = currentModels
    state.runs.value = []
    state.openHistory(trace)
    expect(state.open).not.toHaveBeenCalled()
  })

  it('requires verification again before using a trace from another modeling version', () => {
    const state = createState()
    state.comparison.value = 'SEMANTICS_CHANGED'
    state.stale.value = true
    state.runs.value[0]!.modelSnapshot.modelSemanticsVersion = 0
    expect(state.blockedReason.value).toContain('modeling version')
    state.openResult(trace)
    state.openHistory(trace)
    expect(state.open).not.toHaveBeenCalled()
    expect(state.closeHistory).not.toHaveBeenCalled()
  })

  it('retires an explicitly rejected trace in the owning result and history before comparison refreshes', () => {
    const state = createState()
    const otherTrace = { ...trace, id: 6 }
    state.rejectEvidence(otherTrace.id)
    expect(state.refresh).not.toHaveBeenCalled()
    expect(state.canFix(trace)).toBe(true)
    state.currentUserId.value = 11
    state.rejectEvidence(trace.id)
    expect(state.refresh).not.toHaveBeenCalled()
    expect(state.canFix(trace)).toBe(true)
    state.currentUserId.value = 10

    state.rejectEvidence(trace.id)
    state.showFixDialog.value = false
    expect(state.refresh).toHaveBeenCalledOnce()
    expect(state.needsReverify.value).toBe(true)
    expect(state.blockedReason.value).toBeNull()
    expect(state.traceBlockReason(trace)).toContain('modeling version')
    state.openResult(trace)
    state.openHistory(trace)
    expect(state.open).not.toHaveBeenCalled()

    state.result.value.traces = [otherTrace]
    expect(state.needsReverify.value).toBe(false)
    state.openResult(otherTrace)
    expect(state.open).toHaveBeenCalledExactlyOnceWith(otherTrace.id, otherTrace.violatedSpecId, otherTrace.violatedSpec.name)
  })
})
