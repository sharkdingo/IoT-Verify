// @vitest-environment node
import { describe, expect, it } from 'vitest'
import type { DeviceManifest } from '@/types/device'
import type { SpecCondition } from '@/types/spec'
import {
  getSpecConditionProperties, getSpecConditionValues,
  isSpecConditionRelationSupported, validateSpecConditionDomain
} from '../specConditionDomain'

const manifest: DeviceManifest = {
  Name: 'Heater', Modes: ['Power', 'Mode'],
  WorkingStates: ['on;heat', 'off;idle'].map(Name => ({ Name, Trust: 'trusted', Privacy: 'public' })),
  APIs: [{ Name: 'warm', Signal: true, StartState: 'off;idle', EndState: 'on;heat' }],
  InternalVariables: [{ Name: 'Power', IsInside: false, Reads: false, FalsifiableWhenCompromised: false,
    Trust: 'trusted', Privacy: 'public', LowerBound: 0, UpperBound: 40 }]
}
const condition = (overrides: Partial<SpecCondition>): Partial<SpecCondition> => ({
  targetType: 'state', key: 'state', relation: 'in', value: 'on;heat | off;idle', ...overrides
})
const t = (key: string) => key

describe('specification condition domains', () => {
  it('checks the supplied model independently without changing a condition or template', () => {
    const draft = Object.freeze(condition({ targetType: 'api', key: 'warm', relation: '=', value: 'FALSE' }))
    const original = structuredClone(manifest)
    expect(validateSpecConditionDomain(draft, manifest, '1', t)).toBeNull()
    expect(validateSpecConditionDomain(draft, manifest, '7', t)).toBe('app.specSafetyApiConditionRequired')
    const otherModel = { ...manifest, APIs: [] }
    expect(validateSpecConditionDomain(draft, otherModel, '1', t)).toBe('app.specConditionPropertyUnavailable')
    expect(validateSpecConditionDomain(draft, manifest, '1', t)).toBeNull()
    expect(manifest).toEqual(original)
    expect(draft.value).toBe('FALSE')
  })

  it('keeps full-state tuples intact while offering individual mode values', () => {
    expect(validateSpecConditionDomain(condition({}), manifest, '1', t)).toBeNull()
    expect(validateSpecConditionDomain(condition({ value: 'on;heat|off;heat' }), manifest, '1', t))
      .toBe('app.specConditionValueUnavailable')
    expect(getSpecConditionValues(condition({ targetType: 'mode', key: 'Mode' }), manifest, '1')).toEqual(['heat', 'idle'])
    expect(isSpecConditionRelationSupported(condition({ targetType: 'mode' }), manifest, '1', '>')).toBe(false)
  })

  it.each([
    ['state', 'state', undefined, 'on;heat'],
    ['mode', 'Mode', undefined, 'heat'],
    ['trust', 'Power', 'state', 'trusted'],
    ['privacy', 'Power', 'variable', 'private']
  ] as const)('rejects ordering on the %s domain even when the selected value is valid', (targetType, key, propertyScope, value) => {
    expect(validateSpecConditionDomain(condition({ targetType, key, propertyScope, relation: '>', value }), manifest, '1', t))
      .toBe('app.specConditionRelationUnavailable')
  })

  it('revalidates the operator after a numeric declaration becomes an enum', () => {
    const draft = condition({ targetType: 'variable', key: 'temperature', variableSource: 'reported', relation: '>=', value: '25' })
    const numeric: DeviceManifest = { ...manifest, InternalVariables: [{ Name: 'temperature', IsInside: true,
      FalsifiableWhenCompromised: false, Trust: 'trusted', Privacy: 'public', LowerBound: 0, UpperBound: 40 }] }
    expect(validateSpecConditionDomain(draft, numeric, '1', t)).toBeNull()
    const enumerated: DeviceManifest = { ...numeric, InternalVariables: [{ Name: 'temperature', IsInside: true,
      FalsifiableWhenCompromised: false, Trust: 'trusted', Privacy: 'public', Values: ['25', '30'] }] }
    expect(validateSpecConditionDomain(draft, enumerated, '1', t)).toBe('app.specConditionRelationUnavailable')
    expect(validateSpecConditionDomain({ ...draft, relation: 'IN' }, enumerated, '1', t)).toBeNull()
  })

  it('rejects environment readings after a variable becomes device-local', () => {
    const draft = condition({ targetType: 'variable', key: 'temperature', variableSource: 'environment', relation: '=', value: '25' })
    const shared: DeviceManifest = { ...manifest, InternalVariables: [{ Name: 'temperature', IsInside: false, Reads: true,
      FalsifiableWhenCompromised: false, Trust: 'trusted', Privacy: 'public', LowerBound: 0, UpperBound: 40 }] }
    expect(validateSpecConditionDomain(draft, shared, '1', t)).toBeNull()
    const local: DeviceManifest = { ...shared, InternalVariables: shared.InternalVariables!.map(variable => ({ ...variable, IsInside: true })) }
    expect(validateSpecConditionDomain(draft, local, '1', t)).toBe('app.specVariableSourceDeviceLocalHelp')
    expect(validateSpecConditionDomain({ ...draft, variableSource: 'reported' }, local, '1', t)).toBeNull()
  })

  it('requires an explicit variable value source when revalidating a condition', () => {
    const model: DeviceManifest = { ...manifest, InternalVariables: [{ Name: 'temperature', IsInside: true,
      FalsifiableWhenCompromised: false, Trust: 'trusted', Privacy: 'public', LowerBound: 0, UpperBound: 40 }] }
    expect(validateSpecConditionDomain(condition({ targetType: 'variable', key: 'temperature', relation: '=', value: '25' }), model, '1', t))
      .toBe('app.specVariableSourceRequired')
  })

  it('enforces template 7 equality restrictions during whole-condition validation', () => {
    for (const targetType of ['state', 'mode'] as const) {
      const draft = condition({ targetType, key: targetType === 'state' ? 'state' : 'Mode', relation: '!=',
        value: targetType === 'state' ? 'on;heat' : 'heat' })
      expect(validateSpecConditionDomain(draft, manifest, '7', t)).toBe('app.specConditionRelationUnavailable')
    }
  })

  it('distinguishes state labels, variable labels and value-reading capabilities on the same key', () => {
    const properties = getSpecConditionProperties('trust', manifest, '1')
    expect(properties).toContainEqual({ key: 'Power', propertyScope: 'state' })
    expect(properties).toContainEqual({ key: 'Power', propertyScope: 'variable' })
    for (const propertyScope of ['state', 'variable'] as const) {
      expect(validateSpecConditionDomain(condition({ targetType: 'trust', key: 'Power',
        propertyScope, relation: '=', value: 'trusted' }), manifest, '1', t)).toBeNull()
    }
    expect(validateSpecConditionDomain(condition({ targetType: 'trust', key: 'Power',
      relation: '=', value: 'trusted' }), manifest, '1', t)).toBe('app.specConditionPropertyUnavailable')
    expect(validateSpecConditionDomain(condition({ targetType: 'variable', key: 'Power',
      variableSource: 'reported', relation: '=', value: '20' }), manifest, '1', t))
      .toBe('app.specConditionPropertyUnavailable')
  })
})
