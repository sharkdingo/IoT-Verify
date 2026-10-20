import type { BoardModelFingerprints, ModelRunSnapshot, RunBoardComparison } from '@/types/modelSemantics'

export const isModelFingerprint = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)

export const compareModelSnapshot = (
  snapshot: ModelRunSnapshot | undefined,
  current: BoardModelFingerprints | null | undefined,
  kind: 'verification' | 'simulation' | 'fuzzing'
): RunBoardComparison => {
  if (!isModelFingerprint(snapshot?.modelFingerprint)) return 'NOT_COMPARED'
  const fingerprint = current?.[kind === 'simulation' ? 'simulation' : 'verification']
  if (!isModelFingerprint(fingerprint)) return 'UNAVAILABLE'
  return snapshot.modelFingerprint === fingerprint ? 'UNCHANGED' : 'CHANGED'
}
