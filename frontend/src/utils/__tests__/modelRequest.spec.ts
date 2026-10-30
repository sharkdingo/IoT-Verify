// @vitest-environment node
import { describe, expect, it } from 'vitest'
import {
  buildSimulationRequestPayload,
  buildVerificationRequestPayload,
  normalizeModelRelation,
  normalizeNuSmvDeviceName,
  specificationsRequirePrivacy
} from '../modelRequest'

describe('modelRequest', () => {
  it('normalizes device identifiers used by attack points and imported scenes', () => {
    expect(normalizeNuSmvDeviceName('1Lamp')).toBe('_1lamp')
    expect(normalizeNuSmvDeviceName('Lamp')).toBe('lamp')
    expect(normalizeNuSmvDeviceName('node-1')).toBe('node_1')
    expect(normalizeNuSmvDeviceName('FROZENVAR')).toBe('_frozenvar')
    expect(normalizeNuSmvDeviceName('CTLSPEC')).toBe('_ctlspec')
  })

  it('matches model identities for supplementary Unicode characters', () => {
    expect(normalizeNuSmvDeviceName('lamp🌡')).toBe('lamp_')
    expect(normalizeNuSmvDeviceName('🌡')).toBe('device_0')
    expect(buildVerificationRequestPayload({ attackScenario: { mode: 'EXACT_POINTS', points: [
      { kind: 'DEVICE', deviceId: 'lamp🌡' }
    ] }, enablePrivacy: false }).attackScenario).toEqual({ mode: 'EXACT_POINTS', points: [
      { kind: 'DEVICE', deviceId: 'lamp_' }
    ] })
  })

  it('normalizes relation aliases used by board writes and presentation', () => {
    for (const [alias, expected] of Object.entries({ EQ: '=', '==': '=', NEQ: '!=',
      GT: '>', GTE: '>=', LT: '<', LTE: '<=', IN: 'in', NOT_IN: 'not in' })) {
      expect(normalizeModelRelation(` ${alias} `)).toBe(expected)
    }
    expect(normalizeModelRelation(null)).toBeUndefined()
    expect(normalizeModelRelation('')).toBeUndefined()
  })

  it('requires privacy for a specification with privacy conditions', () => {
    const specification = { aConditions: [{ targetType: ' Privacy ' }] } as any
    expect(specificationsRequirePrivacy([specification])).toBe(true)
    expect(specificationsRequirePrivacy([{ aConditions: [{ targetType: 'state' }] }] as any)).toBe(false)
  })

  it('submits run parameters while the server owns board input', () => {
    const params = { attackScenario: { mode: 'NONE', budget: 0, points: [] }, enablePrivacy: true,
      nodes: [{ id: 'local-only' }], rules: [], specifications: [], environmentVariables: [] } as any
    expect(buildVerificationRequestPayload(params)).toEqual({
      attackScenario: { mode: 'NONE', budget: 0, points: [] }, enablePrivacy: true
    })
    expect(buildSimulationRequestPayload({ ...params, steps: 8 })).toEqual({
      attackScenario: { mode: 'NONE', budget: 0, points: [] }, enablePrivacy: true, steps: 8
    })
  })

  it('discards inactive attack selections for NONE and budget-based runs', () => {
    const points = [{ kind: 'DEVICE', deviceId: 'node-1' }]
    expect(buildVerificationRequestPayload({ attackScenario: { mode: 'NONE', budget: 2, points },
      enablePrivacy: false } as any).attackScenario).toEqual({ mode: 'NONE', budget: 0, points: [] })
    expect(buildVerificationRequestPayload({ attackScenario: { mode: 'ANY_UP_TO_BUDGET', budget: 2, points },
      enablePrivacy: false } as any).attackScenario).toEqual({ mode: 'ANY_UP_TO_BUDGET', budget: 2, points: [] })
  })

  it('normalizes exact device attack points and preserves automation-link identities', () => {
    expect(buildVerificationRequestPayload({ attackScenario: { mode: 'EXACT_POINTS', points: [
      { kind: 'DEVICE', deviceId: 'node-1' }, { kind: 'AUTOMATION_LINK', ruleId: 41 }
    ] }, enablePrivacy: false }).attackScenario).toEqual({ mode: 'EXACT_POINTS', points: [
      { kind: 'DEVICE', deviceId: 'node_1' }, { kind: 'AUTOMATION_LINK', ruleId: 41 }
    ] })
  })
})
