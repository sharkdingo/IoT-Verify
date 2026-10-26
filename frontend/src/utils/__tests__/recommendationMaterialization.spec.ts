import { describe, expect, it } from 'vitest'
import {
  RecommendationCandidateError,
  materializeRuleRecommendation,
  materializeSpecificationRecommendationConditions,
  validateDeviceRecommendationCandidate,
  validateSpecificationRecommendationCandidate
} from '../recommendationMaterialization'

describe('recommendation materialization', () => {
  it('does not invent a missing relation for a value-based rule condition', () => {
    expect(() => materializeRuleRecommendation({
      name: 'Cool the room',
      reason: 'The declared temperature signal can trigger the air conditioner.',
      conditions: [{
        deviceId: 'sensor-1',
        deviceName: 'Temperature sensor',
        attribute: 'temperature',
        targetType: 'variable',
        value: '28'
      }],
      command: {
        deviceId: 'ac-1',
        deviceName: 'Air conditioner',
        action: 'cool'
      }
    }, 'temporary-rule')).toThrow(expect.objectContaining<Partial<RecommendationCandidateError>>({
      field: 'conditions[1].relation'
    }))
  })

  it('rejects scalar coercion and API conditions with value semantics', () => {
    const base = {
      name: 'Alert on motion',
      reason: 'The motion signal can trigger the alarm.',
      command: { deviceId: 'alarm-1', deviceName: 'Alarm', action: 'on' }
    }

    expect(() => materializeRuleRecommendation({
      ...base,
      conditions: [{
        deviceId: 'sensor-1',
        deviceName: 'Sensor',
        attribute: 'motion',
        targetType: 'api',
        relation: '=',
        value: 'TRUE'
      }]
    }, 'temporary-rule')).toThrow(expect.objectContaining({
      field: 'conditions[1].relation/value'
    }))

    expect(() => materializeRuleRecommendation({
      ...base,
      name: 42 as any,
      conditions: [{
        deviceId: 'sensor-1',
        deviceName: 'Sensor',
        attribute: 'motion',
        targetType: 'api'
      }]
    }, 'temporary-rule')).toThrow(expect.objectContaining({ field: 'name' }))
  })

  it('normalizes declared relation aliases without changing rule intent', () => {
    const rule = materializeRuleRecommendation({
      name: 'Cool the room',
      reason: 'The declared temperature signal can trigger the air conditioner.',
      conditions: [{
        deviceId: 'sensor-1',
        deviceName: 'Temperature sensor',
        attribute: 'temperature',
        targetType: 'variable',
        relation: 'GTE',
        value: '28'
      }],
      command: {
        deviceId: 'ac-1',
        deviceName: 'Air conditioner',
        action: 'cool'
      }
    }, 'temporary-rule')

    expect(rule.sources[0]).toMatchObject({ relation: '>=', value: '28' })
  })

  it('keeps canonical model tokens intact when a recommendation is applied', () => {
    const rule = materializeRuleRecommendation({
      name: 'Send the photo',
      reason: 'The phone API accepts the declared photo content.',
      conditions: [{
        deviceId: 'phone-1',
        deviceName: 'Phone',
        attribute: 'SwitchState',
        targetType: 'variable',
        relation: '=',
        value: 'off'
      }],
      command: {
        deviceId: 'phone-1',
        deviceName: 'Phone',
        action: 'send photo',
        contentDevice: 'phone-1',
        content: 'photo'
      }
    }, 'temporary-rule')

    expect(rule.sources[0]).toMatchObject({ fromApi: 'SwitchState', value: 'off' })
    expect(rule).toMatchObject({
      toApi: 'send photo',
      contentDevice: 'phone-1',
      content: 'photo'
    })
  })

  it('requires exact semantic fields for specification recommendations', () => {
    expect(() => materializeSpecificationRecommendationConditions([{
      deviceId: 'door-1',
      targetType: 'state',
      key: 'state',
      value: 'open'
    }], 'a', index => `condition-${index}`)).toThrow(expect.objectContaining({
      field: 'aConditions[1].relation'
    }))

    expect(() => materializeSpecificationRecommendationConditions([{
      deviceId: 'door-1',
      targetType: 'state',
      key: 'state',
      relation: '=',
      value: 1
    }], 'a', index => `condition-${index}`)).toThrow(expect.objectContaining({
      field: 'aConditions[1].value'
    }))
  })

  it('keeps trust/privacy property scope explicit', () => {
    const [condition] = materializeSpecificationRecommendationConditions([{
      deviceId: 'sensor-1',
      targetType: 'privacy',
      key: 'reading',
      propertyScope: 'variable',
      relation: '=',
      value: 'private'
    }], 'a', index => `condition-${index}`)

    expect(condition).toMatchObject({
      targetType: 'privacy',
      propertyScope: 'variable',
      relation: '=',
      value: 'private'
    })
  })

  it('requires a recommended variable condition to say which value it means', () => {
    // Defaulting it would put words in the model's mouth about a distinction that only matters
    // under compromise, so an incomplete candidate is malformed rather than assumed.
    expect(() => materializeSpecificationRecommendationConditions([{
      deviceId: 'sensor-1',
      targetType: 'variable',
      key: 'temperature',
      relation: '>',
      value: '28'
    }], 'a', index => `condition-${index}`)).toThrow(expect.objectContaining({
      field: 'aConditions[1].variableSource'
    }))

    const [condition] = materializeSpecificationRecommendationConditions([{
      deviceId: 'sensor-1',
      targetType: 'variable',
      variableSource: 'reported',
      key: 'temperature',
      relation: '>',
      value: '28'
    }], 'a', index => `condition-${index}`)
    expect(condition).toMatchObject({ variableSource: 'reported' })

    // The field is meaningless on any other condition type, and admission refuses it there.
    expect(() => materializeSpecificationRecommendationConditions([{
      deviceId: 'door-1',
      targetType: 'state',
      variableSource: 'environment',
      key: 'state',
      relation: '=',
      value: 'open'
    }], 'a', index => `condition-${index}`)).toThrow(expect.objectContaining({
      field: 'aConditions[1].variableSource'
    }))
  })

  it('rejects REST-null fields instead of treating them as omitted device defaults', () => {
    expect(() => validateDeviceRecommendationCandidate({
      templateName: 'DoorSensor',
      suggestedLabel: 'Front door sensor',
      initialState: null
    }, 0)).toThrow(expect.objectContaining({ field: 'recommendations[1].initialState' }))

    expect(() => validateDeviceRecommendationCandidate({
      templateName: 'DoorSensor',
      suggestedLabel: 'Front door sensor',
      initialVariables: [{ name: 'battery', value: '90' }]
    }, 0)).not.toThrow()
  })

  it('rejects a kept specification candidate before it can be displayed as applicable', () => {
    expect(() => validateSpecificationRecommendationCandidate({ name: 'Safety requirement',
      rationale: 'Check the door',
      templateId: 3,
      aConditions: [],
      ifConditions: [],
      thenConditions: []
    }, 0)).toThrow(expect.objectContaining({ field: 'recommendations[1].templateId' }))
  })
})
