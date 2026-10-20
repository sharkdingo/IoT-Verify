import { describe, expect, it } from 'vitest'
import { compareModelSnapshot, isModelFingerprint } from '../modelComparison'
import type { ModelRunSnapshot } from '@/types/modelSemantics'

const snapshot: ModelRunSnapshot = { capturedAt: '2026-10-03T10:00:00', deviceCount: 1,
  ruleCount: 1, specificationCount: 1, environmentVariableCount: 0, deviceTemplateCount: 1,
  templatesFrozen: true, modelFingerprint: 'a'.repeat(64) }
const current = { verification: 'a'.repeat(64), simulation: 'b'.repeat(64) }

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

  it('rejects malformed identifiers instead of claiming equality', () => {
    for (const value of [null, undefined, '', 'a'.repeat(63), 'A'.repeat(64), 123]) {
      expect(isModelFingerprint(value)).toBe(false)
    }
    expect(isModelFingerprint('a'.repeat(64))).toBe(true)
  })
})
