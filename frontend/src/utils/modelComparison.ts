import type { BoardModelFingerprints, ModelRunSnapshot, RunBoardComparison } from '@/types/modelSemantics'

export const isModelFingerprint = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)

export const compareModelSnapshot = (
  snapshot: ModelRunSnapshot | undefined,
  current: BoardModelFingerprints | null | undefined,
  kind: 'verification' | 'simulation' | 'fuzzing'
): RunBoardComparison => {
  if (!snapshot || !Number.isSafeInteger(snapshot.modelSemanticsVersion) || snapshot.modelSemanticsVersion < 0) {
    return 'NOT_COMPARED'
  }
  // The server explicitly identifies unversioned archives as version zero.
  if (snapshot.modelSemanticsVersion === 0) return 'SEMANTICS_CHANGED'
  const currentVersion = current?.modelSemanticsVersion
  const currentVersionAvailable = typeof currentVersion === 'number' && Number.isSafeInteger(currentVersion) && currentVersion > 0
  if (currentVersionAvailable && snapshot.modelSemanticsVersion !== currentVersion) return 'SEMANTICS_CHANGED'
  if (!isModelFingerprint(snapshot.modelFingerprint)) return 'NOT_COMPARED'
  const fingerprint = current?.[kind === 'simulation' ? 'simulation' : 'verification']
  if (!currentVersionAvailable || !isModelFingerprint(fingerprint)) return 'UNAVAILABLE'
  return snapshot.modelFingerprint === fingerprint ? 'UNCHANGED' : 'CHANGED'
}

export const hasRunBoardDifference = (comparison: RunBoardComparison | undefined): boolean =>
  comparison === 'CHANGED' || comparison === 'SEMANTICS_CHANGED'
