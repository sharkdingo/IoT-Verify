import type { VerificationRepairBlock } from '@/types/fix'
import type { RunBoardComparison } from '@/types/modelSemantics'

export const getVerificationRepairRunBlock = (comparison: RunBoardComparison, loading = false, stale = false) => {
  if (comparison === 'SEMANTICS_CHANGED') return 'app.runBoardSemanticsChanged'
  if (stale || comparison === 'CHANGED') return 'app.verificationResultStaleReverify'
  if (comparison === 'UNCHANGED') return null
  if (comparison === 'UNAVAILABLE') {
    return loading ? 'app.runBoardComparisonLoading' : 'app.runBoardComparisonUnavailable'
  }
  return 'app.runBoardNotCompared'
}

export const getVerificationRepairTraceBlock = (
  trace: { id?: number; violatedSpecId?: string },
  blocks?: ReadonlyMap<number, VerificationRepairBlock>
) => {
  const recordedBlock = trace.id == null ? undefined : blocks?.get(trace.id)
  return recordedBlock ?? (trace.violatedSpecId ? null : 'app.traceMissingViolatedSpec')
}
