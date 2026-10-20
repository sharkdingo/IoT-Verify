import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { computed, ref } from 'vue'
import { transpileModule, ScriptTarget } from 'typescript'
import { describe, expect, it, vi } from 'vitest'
import { compareModelSnapshot } from '@/utils/modelComparison'
import type { RunBoardComparison } from '@/types/modelSemantics'
import i18n from '@/assets/i18n'

const board = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const resultStart = board.indexOf('const verificationFixBlockedReason =')
const resultEnd = board.indexOf('const cancelAsyncVerification =', resultStart)
const historyStart = board.indexOf('const openFixForVerificationTrace =')
const historyEnd = board.indexOf('/**', historyStart)
if ([resultStart, resultEnd, historyStart, historyEnd].some(index => index < 0)) {
  throw new Error('Verification Fix admission is missing')
}
const createAdmission = new Function('computed', 'verificationResultStale', 'verificationResult',
  'verificationBoardComparison', 'loadingCurrentBoardModelFingerprints', 'hasPersistedVerificationTrace',
  'openFixDialog', 't', 'verificationRuns', 'currentBoardModelFingerprints', 'compareModelSnapshot',
  'notifyBlocked', 'closeHistoryPanel', transpileModule(`${board.slice(resultStart, resultEnd)}
    ${board.slice(historyStart, historyEnd)}
    return { blockedReason: verificationFixBlockedReason, canFix: canFixVerificationResultTrace,
      openResult: openFixForVerificationResultTrace, openHistory: openFixForVerificationTrace }`, {
    compilerOptions: { target: ScriptTarget.ES2022 }
  }).outputText)

const trace = { id: 4, verificationTaskId: 3, violatedSpecId: 'never_on', dataAvailable: true }
const currentModels = { verification: 'a'.repeat(64), simulation: 'b'.repeat(64) }
const createState = () => {
  const comparison = ref<RunBoardComparison>('UNCHANGED')
  const stale = ref(false)
  const loading = ref(false)
  const persisted = vi.fn().mockReturnValue(true)
  const open = vi.fn()
  const closeHistory = vi.fn()
  const fingerprints = ref<typeof currentModels | null>(currentModels)
  const runs = ref([{ id: 3, dataAvailable: true, modelSnapshot: { modelFingerprint: 'a'.repeat(64) } }])
  const state = createAdmission(computed, stale, ref({}), comparison, loading, persisted, open,
    (key: string) => i18n.global.t(key, {}, { locale: 'en' }), runs, fingerprints, compareModelSnapshot,
    vi.fn(), closeHistory)
  return { ...state, comparison, stale, loading, persisted, open, closeHistory, runs, fingerprints }
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
    expect(state.open).toHaveBeenCalledExactlyOnceWith(trace.id, trace.violatedSpecId)
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
    expect(state.open).toHaveBeenCalledExactlyOnceWith(trace.id, trace.violatedSpecId)
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
})
