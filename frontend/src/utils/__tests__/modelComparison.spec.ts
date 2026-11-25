import { describe, expect, it } from 'vitest'
import { compareModelSnapshot, isModelFingerprint } from '../modelComparison'
import type { ModelRunSnapshot } from '@/types/modelSemantics'

const snapshot: ModelRunSnapshot = { capturedAt: '2026-10-03T10:00:00', deviceCount: 1,
  ruleCount: 1, specificationCount: 1, environmentVariableCount: 0, deviceTemplateCount: 1,
  templatesFrozen: true, modelFingerprint: 'a'.repeat(64), modelSemanticsVersion: 1 }
const current = { verification: 'a'.repeat(64), simulation: 'b'.repeat(64), modelSemanticsVersion: 1 }

describe('run model comparison', () => {
  it('compares each run against the inputs consumed by that kind of run', () => {
    expect(compareModelSnapshot(snapshot, current, 'verification')).toBe('UNCHANGED')
    expect(compareModelSnapshot(snapshot, current, 'fuzzing')).toBe('UNCHANGED')
    expect(compareModelSnapshot(snapshot, current, 'simulation')).toBe('CHANGED')
  })

  it('distinguishes absent historical evidence from unavailable current evidence', () => {
    expect(compareModelSnapshot({ ...snapshot, modelFingerprint: null }, current, 'verification')).toBe('NOT_COMPARED')
    expect(compareModelSnapshot(snapshot, null, 'verification')).toBe('UNAVAILABLE')
    expect(compareModelSnapshot(snapshot, undefined, 'fuzzing')).toBe('UNAVAILABLE')
  })

  it.each(['verification', 'simulation', 'fuzzing'] as const)('does not equate a %s run with another modeling version', kind => {
    const sameHash = { ...snapshot, modelFingerprint: current[kind === 'simulation' ? 'simulation' : 'verification'] }
    expect(compareModelSnapshot({ ...sameHash, modelSemanticsVersion: 0 }, current, kind)).toBe('SEMANTICS_CHANGED')
    expect(compareModelSnapshot({ ...sameHash, modelSemanticsVersion: 2, modelFingerprint: null }, current, kind)).toBe('SEMANTICS_CHANGED')
  })

  it('identifies an explicitly unversioned run even when the current comparison is unavailable', () => {
    expect(compareModelSnapshot({ ...snapshot, modelSemanticsVersion: 0 }, null, 'verification')).toBe('SEMANTICS_CHANGED')
  })

  it('rejects malformed identifiers instead of claiming equality', () => {
    for (const value of [null, undefined, '', 'a'.repeat(63), 'A'.repeat(64), 123]) {
      expect(isModelFingerprint(value)).toBe(false)
    }
    expect(isModelFingerprint('a'.repeat(64))).toBe(true)
  })
})
