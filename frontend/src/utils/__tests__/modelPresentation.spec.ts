import { afterEach, describe, expect, it } from 'vitest'
import { i18n } from '@/assets/i18n'
import type { DeviceNode } from '@/types/node'
import type { RuleDto, RuleForm } from '@/types/rule'
import type { SpecCondition, Specification } from '@/types/spec'
import { formatBuiltInModelToken } from '../modelTokenDisplay'
import { describeSpecification, formatRelationGlyph, presentRule, specConditionPropertyLabel } from '../modelPresentation'
import { buildSpecFormulaPreview } from '../spec'

const node: DeviceNode = { id: 'light', label: 'Desk light', templateName: 'Light', state: 'off',
  position: { x: 0, y: 0 }, width: 176, height: 128 }
const context = () => ({
  t: i18n.global.t,
  resolveDevice: (id: string) => id === node.id ? node : undefined,
  formatToken: (_id: string, value: unknown) => formatBuiltInModelToken(value, i18n.global.t),
  modeCount: () => 1
})
const condition = (overrides: Partial<SpecCondition> = {}): SpecCondition => ({
  id: 'condition', side: 'a', deviceId: node.id, deviceLabel: 'Old light name',
  targetType: 'state', key: 'state', relation: '=', value: 'on', ...overrides
})
const specification = (templateId: Specification['templateId']): Specification => ({
  id: 'spec', templateId, templateLabel: 'Old template title',
  aConditions: [condition()], ifConditions: [condition({ side: 'if' })],
  thenConditions: [condition({ side: 'then', value: 'off' })]
})
afterEach(() => { i18n.global.locale.value = 'en' })

describe('shared model explanations', () => {
  it.each(['zh-CN', 'en'] as const)('localizes security values independently of template provenance in %s', locale => {
    i18n.global.locale.value = locale
    const spec = specification('1')
    const customContext = { ...context(), formatToken: (_id: string, value: unknown) => String(value) }
    spec.aConditions = [condition({ targetType: 'privacy', propertyScope: 'state', value: 'private' })]
    expect(describeSpecification(spec, customContext)).toContain(`“${i18n.global.t('app.private')}”`)
    spec.aConditions = [condition({ targetType: 'trust', propertyScope: 'state', relation: 'in', value: 'trusted,untrusted' })]
    expect(describeSpecification(spec, customContext)).toContain(`“${i18n.global.t('app.trusted')}, ${i18n.global.t('app.untrusted')}”`)
    spec.aConditions = [condition({ value: 'private' })]
    expect(describeSpecification(spec, customContext)).toContain('“private”')
  })

  it.each([['GTE', '≥'], ['!=', '≠'], ['LTE', '≤'], ['in', '∈'], ['not in', '∉']])(
    'uses the same reading glyph for %s', (raw, glyph) => expect(formatRelationGlyph(raw)).toBe(glyph))

  it('describes every trigger and the command without treating an API event as a comparison', () => {
    i18n.global.locale.value = 'en'
    const rule: RuleForm = { sources: [
      { fromId: 'light', fromApi: 'state', itemType: 'state', relation: '=', value: 'on' },
      { fromId: 'light', fromApi: 'temperature', itemType: 'variable', relation: '>=', value: '0' },
      { fromId: 'light', fromApi: 'off', itemType: 'api', relation: '=', value: 'TRUE' }
    ], toId: 'light', toApi: 'on', contentDevice: 'light', content: 'state' }
    const result = presentRule(rule, context())
    expect(result.conditions).toEqual(['Desk light State = On', 'Desk light Temperature ≥ 0', 'Desk light triggers Off'])
    expect(result.description).toContain('AND')
    expect(result.description).toContain('send On to Desk light')
    expect(result.contentSource).toBe('Desk light State')
    expect(result.description).not.toContain('TRUE')
  })

  it('uses all conditions and label sources from a frozen rule', () => {
    i18n.global.locale.value = 'en'
    const rule: RuleDto = { id: 7, conditions: [
      { deviceName: 'light', attribute: 'LegacyMode', targetType: 'state', relation: '=', value: 'off' },
      { deviceName: 'missing', attribute: 'custom_reading', targetType: 'variable', relation: '>', value: '25' }
    ], command: { deviceName: 'light', action: 'on', contentDevice: 'missing', content: 'private_payload' } }
    const result = presentRule(rule, { ...context(), formatToken: (_id, value) => String(value ?? '') })
    expect(result.conditions).toEqual(['Desk light state = off', 'missing custom_reading > 25'])
    expect(result.contentSource).toBe('missing private_payload')
    expect(result.action).toBe('on')
  })

  it.each(['en', 'zh-CN'] as const)('describes all seven templates with their own temporal meaning in %s', locale => {
    i18n.global.locale.value = locale
    const words = locale === 'en'
      ? ['must always hold', 'must eventually hold', 'must never hold', 'in the next state', 'must eventually hold', 'remain true', 'untrusted control source']
      : ['必须始终成立', '必须最终成立', '必须永不成立', '下一状态', '必须最终成立', '持续保持', '控制来源都不得标为不可信']
    for (let index = 0; index < words.length; index++) {
      const spec = specification(String(index + 1) as Specification['templateId'])
      const description = describeSpecification(spec, context())
      expect(description).toContain(words[index])
      expect(description).toContain('Desk light')
      expect(description).not.toContain('Old light name')
      const preview = buildSpecFormulaPreview(spec, { nodes: [node] })
      expect(preview.kind).toBe(index === 5 ? 'LTL' : 'CTL')
      expect(preview.expression).not.toMatch(/^(CTL|LTL) /)
      expect(preview.expression).toContain('"Desk light".state')
    }
  })

  it('keeps actual, reported and unresolved values distinct in the explanation', () => {
    i18n.global.locale.value = 'en'
    const spec = specification('1')
    spec.aConditions = [condition({ targetType: 'variable', key: 'temperature', value: '25', variableSource: 'environment' })]
    expect(describeSpecification(spec, context())).toContain('environment Temperature')
    spec.aConditions[0].variableSource = 'reported'
    expect(describeSpecification(spec, context())).toContain('the Temperature Desk light reports')
    delete spec.aConditions[0].variableSource
    expect(describeSpecification(spec, context())).toContain('which value it means is not chosen yet')
    expect(buildSpecFormulaPreview(spec, { nodes: [node] }).expression).toContain('<unresolved>')
  })

  it('localizes set values individually and preserves all simultaneous conditions', () => {
    i18n.global.locale.value = 'zh-CN'
    const spec = specification('1')
    spec.aConditions = [condition({ relation: 'in', value: 'on|off' }), condition({ targetType: 'api', key: 'on', value: 'TRUE' })]
    const description = describeSpecification(spec, context())
    expect(description).toContain('∈ “开启, 关闭”')
    expect(description).toContain('且 Desk light 执行动作 开启')
    expect(description).not.toContain('TRUE')
  })

  it('names current-state trust and variable sensitivity rather than confusing them with values', () => {
    i18n.global.locale.value = 'en'
    const trust = condition({ targetType: 'trust', propertyScope: 'state', key: 'SwitchState', value: 'untrusted' })
    expect(specConditionPropertyLabel(trust, context())).toBe('Current state')
    expect(specConditionPropertyLabel(trust, { ...context(), modeCount: () => 2 })).toContain('Switch state')
    const spec = specification('1')
    spec.aConditions = [trust, condition({ targetType: 'privacy', propertyScope: 'variable', key: 'temperature', value: 'private' })]
    const description = describeSpecification(spec, context())
    expect(description).toContain('source label')
    expect(description).toContain('Temperature sensitivity label')
    expect(description).toContain('must always hold')
  })

  it.each(['en', 'zh-CN'] as const)('distinguishes a current-state response from a required next-state response in %s', locale => {
    i18n.global.locale.value = locale
    const response = specification('5')
    response.thenConditions = [condition({ side: 'then' })]
    const immediate: Specification = { ...response, templateId: '4' }
    expect(buildSpecFormulaPreview(response, { nodes: [node] }).expression).toContain('-> AF(')
    expect(buildSpecFormulaPreview(immediate, { nodes: [node] }).expression).toContain('-> AX(')
    expect(describeSpecification(response, context())).toContain(locale === 'en' ? 'same state' : '同一状态')
    expect(i18n.global.t('app.specTemplates.response.description')).toContain(locale === 'en' ? 'same state' : '同一状态')
    expect(describeSpecification(immediate, context())).toContain(locale === 'en' ? 'next state' : '下一状态')
    expect(describeSpecification(immediate, context())).not.toContain(locale === 'en' ? 'same state' : '同一状态')
  })

  it.each(['en', 'zh-CN'] as const)('explains that one untrusted source violates a simultaneously satisfied safety group in %s', locale => {
    i18n.global.locale.value = locale
    const spec = specification('7')
    spec.aConditions.push(condition({ targetType: 'variable', variableSource: 'environment', key: 'temperature', relation: '>', value: '25' }))
    const formula = buildSpecFormulaPreview(spec, { nodes: [node] }).expression
    // All protected conditions must hold, but a single untrusted source suffices for the violation.
    expect(formula).toContain(' AND Environment.')
    expect(formula).toContain('controlSource("Desk light".state) = untrusted OR controlSource("Desk light"."temperature") = untrusted')
    const description = describeSpecification(spec, context())
    expect(description).toContain(locale === 'en' ? 'hold together' : '同时成立')
    expect(description).toContain(locale === 'en' ? 'no condition may have an untrusted' : '任一条件的控制来源都不得标为不可信')
    expect(description).not.toMatch(/all trigger sources|全部触发来源/)
  })

  it('identifies the current state of a named mode without repeating a translated state label', () => {
    i18n.global.locale.value = 'zh-CN'
    const privacy = condition({ targetType: 'privacy', propertyScope: 'state', key: 'WorkState', value: 'public' })
    expect(specConditionPropertyLabel(privacy, {
      ...context(), modeCount: () => 2, formatToken: (_id, value) => value === 'WorkState' ? '状态' : String(value)
    })).toBe('“状态”的当前状态')
  })

  it('marks incomplete drafts explicitly instead of describing them as a finished requirement', () => {
    i18n.global.locale.value = 'en'
    const spec = specification('4')
    spec.thenConditions = []
    expect(describeSpecification(spec, context())).toContain('add the THEN condition')
    spec.ifConditions = []
    expect(describeSpecification(spec, context())).toBe('Configure IF and THEN conditions')
    spec.templateId = '1'
    spec.aConditions = []
    expect(describeSpecification(spec, context())).toBe('Configure the A condition')
  })
})
