import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import ControlCenter from '../ControlCenter.vue'
import SystemInspector from '../SystemInspector.vue'
import DeviceDialog from '../DeviceDialog.vue'
import { i18n } from '@/assets/i18n'
import type { Specification, SpecCondition } from '@/types/spec'
import type { DeviceNode } from '@/types/node'
import type { DeviceTemplate } from '@/types/device'
import { presentSpecificationResult } from '@/views/board/verificationResult'

const node: DeviceNode = { id: 'light', label: 'Desk light', templateName: 'Light', state: 'off',
  position: { x: 0, y: 0 }, width: 176, height: 128 }
const template: DeviceTemplate = { name: 'Light', defaultTemplate: true, manifest: {
  Name: 'Light', Modes: ['SwitchState'], InitState: 'off',
  WorkingStates: [{ Name: 'off', Trust: 'trusted', Privacy: 'public' }, { Name: 'on', Trust: 'trusted', Privacy: 'public' }],
  APIs: [{ Name: 'on', StartState: 'off', EndState: 'on', Signal: true }],
  InternalVariables: [{ Name: 'temperature', LowerBound: 0, UpperBound: 40, IsInside: false,
    FalsifiableWhenCompromised: true, Reads: true, Trust: 'trusted', Privacy: 'public', NaturalChangeRate: '1' }]
} }
const condition = (overrides: Partial<SpecCondition> = {}): SpecCondition => ({
  id: 'condition', side: 'a', deviceId: node.id, deviceLabel: 'Old device name',
  targetType: 'state', key: 'state', relation: '=', value: 'off', ...overrides
})
const spec: Specification = { id: 'spec', templateId: '6', templateLabel: 'Persistence', aConditions: [],
  ifConditions: [condition({ side: 'if', targetType: 'variable', variableSource: 'reported', key: 'temperature', relation: '>', value: '25' })],
  thenConditions: [condition({ side: 'then', value: 'on' })], formula: 'STALE CTL nonsense' }
afterEach(() => { i18n.global.locale.value = 'en' })

describe('specification explanations across both panels', () => {
  it('does not present a placeholder formula as a specification before its conditions are complete', async () => {
    const editor = mount(ControlCenter, { props: { activeSection: 'specs', nodes: [node], deviceTemplates: [template] },
      global: { plugins: [i18n] } })
    try {
      await editor.get('[data-testid="spec-template-select"]').setValue('1')
      expect(editor.find('[data-testid="spec-description"]').exists()).toBe(false)
      expect(editor.find('[data-testid="spec-formula-expression"]').exists()).toBe(false)
      const draft = (editor.vm as unknown as { specForm: Specification }).specForm
      draft.aConditions.push(condition())
      await nextTick()
      expect(editor.get('[data-testid="spec-description"]').text()).toContain(node.label)
      expect(editor.get('[data-testid="spec-formula-expression"]').text()).not.toBe('AG(TRUE)')
      const disclosure = editor.get<HTMLDetailsElement>('[data-testid="spec-formula-details"]')
      expect(disclosure.element.open).toBe(false)
      expect(disclosure.element.contains(editor.get('[data-testid="spec-description"]').element)).toBe(false)
      await disclosure.get('summary').trigger('click')
      expect(disclosure.element.open).toBe(true)
      expect(disclosure.get('[data-testid="spec-formula-expression"]').text()).not.toBe('AG(TRUE)')
      draft.aConditions.splice(0)
      await nextTick()
      expect(editor.find('[data-testid="spec-formula-expression"]').exists()).toBe(false)
    } finally { editor.unmount() }
  })

  it('shares the editor explanation with saved specs, reacts to locale changes and separates logic from formula', async () => {
    i18n.global.locale.value = 'en'
    const editor = mount(ControlCenter, { props: { activeSection: 'specs', nodes: [node], deviceTemplates: [template] },
      global: { plugins: [i18n] } })
    const inspector = mount(SystemInspector, { props: { activeSection: 'specs', devices: [node], deviceTemplates: [template], specifications: [spec] },
      global: { plugins: [i18n] } })
    const device = mount(DeviceDialog, { props: { visible: true, deviceName: template.name, label: node.label,
      nodeId: node.id, manifest: template.manifest, nodes: [node], deviceTemplates: [template], specs: [spec] },
      global: { plugins: [i18n], stubs: { teleport: true } } })
    try {
      await editor.get('[data-testid="spec-template-select"]').setValue('6')
      const draft = (editor.vm as unknown as { specForm: Specification }).specForm
      draft.ifConditions.push(...spec.ifConditions)
      draft.thenConditions.push(...spec.thenConditions)
      await nextTick()
      for (const locale of ['en', 'zh-CN'] as const) {
        i18n.global.locale.value = locale
        await nextTick()
        const description = inspector.get('[data-testid="inspector-spec-description"]')
        expect(description.element.tagName).toBe('DD')
        const descriptionGroup = inspector.get('[data-spec-id="spec"] dl')
        expect(descriptionGroup.classes()).toContain('board-copy')
        expect(descriptionGroup.get('dt').text()).toBe(i18n.global.t('app.specificationDescription'))
        expect(inspector.get('[data-spec-id="spec"] h4').text()).toBeTruthy()
        expect(description.text()).toBe(editor.get('[data-testid="spec-description"]').text())
        expect(device.get('[data-testid="device-dialog-spec-description"]').text()).toBe(description.text())
        const result = presentSpecificationResult({
          specId: spec.id, templateId: spec.templateId, specificationLabel: spec.templateLabel,
          formulaPreview: '', formulaKind: 'LTL', outcome: 'SATISFIED', expression: '',
          specificationSnapshot: { ...spec, devices: {
            [node.id]: { label: node.label, modelTokenSource: 'BUNDLED', modeCount: 1 }
          } }
        }, i18n.global.t)
        expect(result.description).toBe(description.text())
        expect(result.variableSourceLabels.map(source => source.label))
          .toEqual(inspector.findAll('[data-testid="inspector-spec-variable-source"]').map(source => source.text()))
        expect(description.text()).toContain(locale === 'en' ? 'the Temperature Desk light reports' : 'Desk light 上报的 温度')
        expect(description.text()).toContain(locale === 'en' ? 'remain true' : '持续保持')
        if (locale === 'zh-CN') expect(description.text()).toContain(`${node.label}的状态`)
        expect(description.text()).not.toContain('Old device name')
        expect(description.text()).not.toContain(locale === 'en' ? 'After A, B' : 'A 发生后')
        const disclosure = inspector.get<HTMLDetailsElement>('[data-testid="inspector-spec-formula-details"]')
        expect(disclosure.element.open).toBe(false)
        await disclosure.get('summary').trigger('click')
        expect(disclosure.element.open).toBe(true)
        expect(inspector.get('[data-testid="inspector-spec-formula-kind"]').text()).toBe('LTL')
        expect(editor.get('[data-testid="spec-formula-kind"]').text()).toBe('LTL')
        const formula = inspector.get('[data-testid="inspector-spec-formula"]')
        expect(formula.text()).toBe(editor.get('[data-testid="spec-formula-expression"]').text())
        const deviceFormula = device.get<HTMLDetailsElement>('[data-testid="device-dialog-spec-formula-details"]')
        expect(deviceFormula.element.open).toBe(false)
        expect(deviceFormula.get('[data-testid="device-dialog-spec-formula"]').text()).toBe(formula.text())
        await deviceFormula.get('summary').trigger('click')
        expect(deviceFormula.element.open).toBe(true)
        await deviceFormula.get('summary').trigger('click')
        expect(formula.text()).toMatch(/^G\(/)
        expect(formula.text()).not.toContain('LTL')
        expect(formula.text()).not.toContain('STALE')
        expect(formula.element.contains(inspector.get('[data-testid="inspector-spec-formula-kind"]').element)).toBe(false)
        expect(description.classes().some(name => /truncate|line-clamp/.test(name))).toBe(false)
        await disclosure.get('summary').trigger('click')
        expect(disclosure.element.open).toBe(false)
      }
    } finally { editor.unmount(); inspector.unmount(); device.unmount() }
  })

  it('explains actual and undecided readings explicitly while retaining the unresolved warning and formula', async () => {
    i18n.global.locale.value = 'zh-CN'
    const makeSpec = (id: string, variableSource?: 'environment' | 'reported'): Specification => ({
      id, templateId: '1', templateLabel: 'Always',
      aConditions: [condition({ targetType: 'variable', key: 'temperature', relation: '>', value: '25', variableSource })],
      ifConditions: [], thenConditions: []
    })
    const inspector = mount(SystemInspector, { props: { activeSection: 'specs', devices: [node], deviceTemplates: [template],
      specifications: [makeSpec('actual', 'environment'), makeSpec('unresolved')] }, global: { plugins: [i18n] } })
    try {
      const actual = inspector.get('[data-spec-id="actual"]')
      const unresolved = inspector.get('[data-spec-id="unresolved"]')
      expect(actual.get('[data-testid="inspector-spec-description"]').text()).toContain('环境中的 温度')
      expect(actual.get('[data-testid="inspector-spec-formula-kind"]').text()).toBe('CTL')
      expect(actual.get('[data-testid="inspector-spec-formula"]').text()).toMatch(/^AG\(/)
      expect(actual.get('[data-testid="inspector-spec-formula"]').text()).toContain('Environment.')
      expect(unresolved.get('[data-testid="inspector-spec-description"]').text()).toContain('尚未选择读取哪个值')
      expect(unresolved.get('[data-testid="inspector-spec-formula"]').text()).toContain('<unresolved>')
      expect(unresolved.get('[data-testid="inspector-spec-variable-source"]').classes()).toContain('board-chip-danger')
    } finally { inspector.unmount() }
  })

  it('keeps custom model tokens intact and describes API conditions as events', () => {
    i18n.global.locale.value = 'zh-CN'
    const apiSpec: Specification = { ...spec, templateId: '2', aConditions: [condition({ targetType: 'api', key: 'on', value: 'TRUE' })], ifConditions: [], thenConditions: [] }
    const inspector = mount(SystemInspector, { props: { activeSection: 'specs', devices: [node],
      deviceTemplates: [{ ...template, defaultTemplate: false }], specifications: [apiSpec] }, global: { plugins: [i18n] } })
    try {
      const description = inspector.get('[data-testid="inspector-spec-description"]').text()
      expect(description).toContain('Desk light 执行动作 on')
      expect(description).not.toContain('TRUE')
      expect(description).not.toContain('开启')
      expect(inspector.get('[data-testid="inspector-spec-formula"]').text()).toContain('actionEvent("Desk light", "on")')
    } finally { inspector.unmount() }
  })
})
