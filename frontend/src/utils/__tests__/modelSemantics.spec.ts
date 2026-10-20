import { describe, expect, it } from 'vitest'
import { i18n } from '@/assets/i18n'
import type { ModelSemantics } from '@/types/modelSemantics'
import { ENVIRONMENT_EVOLUTION_EFFECTS, isModelSemanticsConsistent } from '../modelSemantics'

const semantics = (privacy: ModelSemantics['privacyPropagationPolicy']): ModelSemantics => ({
  attackPointUnit: 'BEHAVIOR_CHANGING_DEVICE_INSTANCE_OR_AUTOMATION_LINK',
  attackSelectionPolicy: 'UP_TO_ATTACK_BUDGET_NONDETERMINISTIC',
  attackEffects: [
    'DECLARED_FALSIFIABLE_READING_NONDETERMINISTIC_WITHIN_DECLARED_DOMAIN',
    'COMMAND_TO_COMPROMISED_TARGET_IS_DROPPED',
    'COMMAND_ON_COMPROMISED_AUTOMATION_LINK_IS_DROPPED'
  ],
  modeledDeviceAttackPointCount: 2,
  modeledFalsifiableReadingDeviceCount: 1,
  modeledAutomationLinkAttackPointCount: 3,
  modeledAttackPointCount: 5,
  trustPropagationPolicy: 'TARGET_UNTRUSTED_IF_ALL_TRIGGER_SOURCES_UNTRUSTED',
  privacyPropagationPolicy: privacy,
  labelPropagationScope: 'AUTOMATION_RULE_COMMANDS_ONLY',
  environmentEvolutionEffects: [
    'DECLARED_NUMERIC_RATES_AND_DEVICE_EFFECTS_WITHIN_DOMAIN',
    'UNWRITTEN_DISCRETE_VALUES_NONDETERMINISTIC_WITHIN_DECLARED_DOMAIN',
    'DEVICE_WRITTEN_DISCRETE_VALUES_HOLD_WHEN_NO_DECLARED_EFFECT_APPLIES'
  ],
  localVariableFallbackPolicy: 'STUTTER_WHEN_NO_DECLARED_EVOLUTION'
})

describe('isModelSemanticsConsistent', () => {
  it('accepts the documented attack and privacy policies', () => {
    expect(isModelSemanticsConsistent(
      semantics('TARGET_PRIVATE_IF_ANY_TRIGGER_OR_SELECTED_CONTENT_PRIVATE'),
      { isAttack: true, attackBudget: 2, enablePrivacy: true }
    )).toBe(true)
  })

  it('requires no attack effects when attack modeling was disabled', () => {
    expect(isModelSemanticsConsistent({
      ...semantics('NOT_MODELED'),
      attackSelectionPolicy: 'NOT_MODELED',
      attackEffects: []
    }, { isAttack: false, enablePrivacy: false })).toBe(true)
    expect(isModelSemanticsConsistent(
      semantics('NOT_MODELED'),
      { isAttack: false, enablePrivacy: false }
    )).toBe(false)
  })

  it('accepts only the attack effects that this scene can exercise', () => {
    const linkOnly: ModelSemantics = {
      ...semantics('NOT_MODELED'),
      attackEffects: [
        'COMMAND_TO_COMPROMISED_TARGET_IS_DROPPED',
        'COMMAND_ON_COMPROMISED_AUTOMATION_LINK_IS_DROPPED'
      ],
      modeledFalsifiableReadingDeviceCount: 0
    }
    expect(isModelSemanticsConsistent(
      linkOnly,
      { isAttack: true, attackBudget: 1, enablePrivacy: false }
    )).toBe(true)
    expect(isModelSemanticsConsistent({
      ...linkOnly,
      attackEffects: [
        ...linkOnly.attackEffects,
        'DECLARED_FALSIFIABLE_READING_NONDETERMINISTIC_WITHIN_DECLARED_DOMAIN'
      ]
    }, { isAttack: true, attackBudget: 1, enablePrivacy: false })).toBe(false)
  })

  it('validates exact selected point identities', () => {
    const exact: ModelSemantics = {
      ...semantics('NOT_MODELED'),
      attackSelectionPolicy: 'EXACT_ATTACK_POINTS',
      selectedAttackPoints: [
        { kind: 'DEVICE', deviceId: 'sensor_1', displayLabel: 'Hall sensor' },
        { kind: 'AUTOMATION_LINK', ruleId: 7, displayLabel: 'Turn on hall light' }
      ]
    }
    expect(isModelSemanticsConsistent(
      exact,
      { isAttack: true, attackBudget: 2, enablePrivacy: false }
    )).toBe(true)
    expect(isModelSemanticsConsistent({
      ...exact,
      selectedAttackPoints: [
        { kind: 'DEVICE', deviceId: 'sensor_1' },
        { kind: 'DEVICE', deviceId: 'sensor_1' }
      ]
    }, { isAttack: true, attackBudget: 2, enablePrivacy: false })).toBe(false)
    expect(isModelSemanticsConsistent({
      ...exact,
      selectedAttackPoints: [{ kind: 'AUTOMATION_LINK', ruleId: 0 }]
    }, { isAttack: true, attackBudget: 1, enablePrivacy: false })).toBe(false)
    expect(isModelSemanticsConsistent({
      ...exact,
      selectedAttackPoints: [{ kind: 'DEVICE', deviceId: 'sensor_1', displayLabel: ' ' }]
    }, { isAttack: true, attackBudget: 1, enablePrivacy: false })).toBe(false)
  })

  it('rejects missing or contradictory model semantics', () => {
    expect(isModelSemanticsConsistent(undefined, { isAttack: true, enablePrivacy: true })).toBe(false)
    expect(isModelSemanticsConsistent(
      semantics('NOT_MODELED'),
      { isAttack: true, enablePrivacy: true }
    )).toBe(false)
    expect(isModelSemanticsConsistent({
      ...semantics('TARGET_PRIVATE_IF_ANY_TRIGGER_OR_SELECTED_CONTENT_PRIVATE'),
      modeledAttackPointCount: 4
    }, { isAttack: true, attackBudget: 2, enablePrivacy: true })).toBe(false)
    expect(isModelSemanticsConsistent(
      semantics('TARGET_PRIVATE_IF_ANY_TRIGGER_OR_SELECTED_CONTENT_PRIVATE'),
      { isAttack: true, attackBudget: 6, enablePrivacy: true }
    )).toBe(false)
    expect(isModelSemanticsConsistent({
      ...semantics('TARGET_PRIVATE_IF_ANY_TRIGGER_OR_SELECTED_CONTENT_PRIVATE'),
      modeledFalsifiableReadingDeviceCount: 3
    }, { isAttack: true, attackBudget: 2, enablePrivacy: true })).toBe(false)
    expect(isModelSemanticsConsistent({
      ...semantics('TARGET_PRIVATE_IF_ANY_TRIGGER_OR_SELECTED_CONTENT_PRIVATE'),
      environmentEvolutionEffects: []
    }, { isAttack: true, attackBudget: 2, enablePrivacy: true })).toBe(false)
    expect(isModelSemanticsConsistent({
      ...semantics('TARGET_PRIVATE_IF_ANY_TRIGGER_OR_SELECTED_CONTENT_PRIVATE'),
      labelPropagationScope: undefined
    } as unknown as ModelSemantics, { isAttack: true, attackBudget: 2, enablePrivacy: true })).toBe(false)
  })
})

describe('disclosure of environment evolution abstractions', () => {
  // The backend reports two distinct discrete rules in modelSemantics. If the UI only ever describes
  // one of them, a user reading the panel learns the opposite of what the verifier does for the other
  // -- which is exactly what happened: the copy said "may change when no device effect applies", the
  // behaviour a DEVICE-WRITTEN value specifically does not have. These assertions fail if either
  // wording disappears or if a new backend effect arrives with no user-facing explanation.
  const EFFECT_TO_COPY: Record<string, string> = {
    UNWRITTEN_DISCRETE_VALUES_NONDETERMINISTIC_WITHIN_DECLARED_DOMAIN:
      'app.environmentDiscreteExogenousEvolution',
    DEVICE_WRITTEN_DISCRETE_VALUES_HOLD_WHEN_NO_DECLARED_EFFECT_APPLIES:
      'app.environmentDiscreteWrittenEvolution',
    DECLARED_NUMERIC_RATES_AND_DEVICE_EFFECTS_WITHIN_DOMAIN:
      'app.environmentNumericEvolution'
  }

  it('has user-facing copy for every environment evolution effect the backend reports', () => {
    for (const [effect, key] of Object.entries(EFFECT_TO_COPY)) {
      expect(ENVIRONMENT_EVOLUTION_EFFECTS).toContain(effect)
      const en = i18n.global.getLocaleMessage('en') as Record<string, any>
      const zh = i18n.global.getLocaleMessage('zh-CN') as Record<string, any>
      const path = key.split('.')
      const resolve = (msgs: Record<string, any>) => path.reduce<any>((acc, part) => acc?.[part], msgs)
      expect(typeof resolve(en), `${effect} needs English copy at ${key}`).toBe('string')
      expect(typeof resolve(zh), `${effect} needs Chinese copy at ${key}`).toBe('string')
    }
  })

  it.each(['en', 'zh-CN'] as const)('explains exogenous choices and device-written hold behavior in %s', locale => {
    // Describe reachable behavior rather than asking users to interpret implementation adjectives.
    const messages = i18n.global.getLocaleMessage(locale) as Record<string, any>
    const exogenous = String(messages.app.environmentDiscreteExogenousEvolution)
    expect(exogenous).toContain(locale === 'en' ? 'any declared value each step' : '每步可取声明范围内的任意值')
    const written = String(messages.app.environmentDiscreteWrittenEvolution)
    expect(written).toContain(locale === 'en' ? 'unchanged when no write takes effect' : '没有生效的写入时保持不变')
    expect(written).not.toContain(locale === 'en' ? 'any declared value' : '任意值')
  })
})
