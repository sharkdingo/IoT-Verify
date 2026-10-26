// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RuleBuilder from '../RuleBuilder.vue'
import ControlCenter from '../ControlCenter.vue'
import SystemInspector from '../SystemInspector.vue'
import { i18n } from '@/assets/i18n'
import type { RuleForm } from '@/types/rule'
import type { Specification } from '@/types/spec'

vi.mock('@/api/board', () => ({ default: { checkDuplicateRule: vi.fn() } }))
Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() })
const wrappers: VueWrapper[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()) })
const nodes = [{ id: 'lamp', label: 'Hall light', templateName: 'Light', state: 'off', position: { x: 0, y: 0 }, width: 176, height: 128 }]
const templates = [{ name: 'Light', manifest: { Name: 'Light', Modes: ['power'], WorkingStates: [{ Name: 'off' }, { Name: 'on' }], APIs: [{ Name: 'turn_on', Signal: true }], InternalVariables: [] } }]
const rule = (): RuleForm => ({ id: '7', name: 'Original rule', sources: [{ fromId: 'lamp', fromApi: 'state', itemType: 'state', relation: '=', value: 'off' }], toId: 'lamp', toApi: 'turn_on' })
const spec = (id = 'spec-7'): Specification => ({ name: 'Safety requirement', id, templateId: '1', templateLabel: 'Always', aConditions: [{ id: 'condition-1', side: 'a', deviceId: 'lamp', deviceLabel: 'Hall light', targetType: 'state', key: 'state', relation: '=', value: 'off' }], ifConditions: [], thenConditions: [] })
const keep = <T extends VueWrapper>(wrapper: T): T => { wrappers.push(wrapper); return wrapper }

describe('revising existing automation without changing identity', () => {
  it('revises a condition in place without removing or reordering the saved conditions', async () => {
    const original = rule()
    original.sources.push({ ...original.sources[0], value: 'on' })
    const wrapper = keep(mount(RuleBuilder, { props: { nodes, deviceTemplates: templates, editRule: original }, global: { plugins: [i18n] } }))
    await flushPromises()
    await wrapper.get('[data-testid="rule-edit-source-0"]').trigger('click')
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.currentSource).toMatchObject(original.sources[0])
    vm.currentSource.value = 'on'
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-testid="rule-add-source"]').trigger('click')
    expect(vm.ruleData.sources).toEqual([{ ...original.sources[0], value: 'on' }, original.sources[1]])
    expect(original.sources[0].value).toBe('off')
    await wrapper.get('[data-testid="rule-edit-source-0"]').trigger('click')
    vm.currentSource.value = 'off'
    await wrapper.get('[data-testid="rule-clear-source"]').trigger('click')
    expect(vm.ruleData.sources[0].value).toBe('on')
  })

  it('loads a rule into the existing builder and submits detached original and edited snapshots', async () => {
    const original = rule()
    const wrapper = keep(mount(RuleBuilder, { props: { nodes, deviceTemplates: templates, editRule: original }, global: { plugins: [i18n] } }))
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.ruleData.id).toBe('7')
    vm.ruleData.sources[0].value = 'on'
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-testid="rule-save"]').trigger('click')
    await flushPromises()
    const request = wrapper.emitted('save-rule')?.[0]?.[0] as any
    expect(request.expected).toEqual(original)
    expect(request.rule.id).toBe('7')
    expect(request.rule.sources[0].value).toBe('on')
    expect(original.sources[0].value).toBe('off')
    request.complete(false, { cause: 'Changed elsewhere', details: [], fields: {} })
    await flushPromises()
    expect(vm.ruleData.sources[0].value).toBe('on')
    expect(wrapper.get('[data-testid="rule-save-failure"]').text()).toContain('Changed elsewhere')
  })

  it('does not clear a newer rule edit when an earlier save completes', async () => {
    const wrapper = keep(mount(RuleBuilder, { props: { nodes, deviceTemplates: templates, editRule: rule() }, global: { plugins: [i18n] } }))
    await flushPromises()
    await wrapper.get('[data-testid="rule-save"]').trigger('click')
    await flushPromises()
    const request = wrapper.emitted('save-rule')?.[0]?.[0] as any
    await wrapper.setProps({ editRule: { ...rule(), id: '8', name: 'Another rule' } })
    request.complete(true)
    await flushPromises()
    expect((wrapper.vm as any).ruleData.id).toBe('8')
    expect(wrapper.emitted('finish-edit')).toBeUndefined()
  })

  it.each(['rule', 'spec'] as const)('preserves a rejected %s draft and explains how to recover from a stale baseline', async kind => {
    const wrapper = keep(kind === 'rule'
      ? mount(RuleBuilder, { props: { nodes, deviceTemplates: templates, editRule: rule() }, global: { plugins: [i18n] } })
      : mount(ControlCenter, { props: { nodes, deviceTemplates: templates, activeSection: 'specs', editSpecification: spec() }, global: { plugins: [i18n] } }))
    await flushPromises()
    await wrapper.get(`[data-testid="${kind === 'rule' ? 'rule-save' : 'spec-create'}"]`).trigger('click')
    const request = wrapper.emitted(kind === 'rule' ? 'save-rule' : 'add-spec')?.[0]?.[0] as any
    request.complete(false, { reasonCode: kind === 'rule' ? 'RULE_EDIT_STALE' : 'SPEC_EDIT_STALE', cause: 'Changed elsewhere', details: [], fields: {} })
    await flushPromises()
    expect(wrapper.get(`[data-testid="${kind}-save-failure"]`).text()).toContain(i18n.global.t('app.editConflictRecoveryHint'))
    if (kind === 'rule') expect((wrapper.vm as any).ruleData.sources).toEqual(rule().sources)
    else expect((wrapper.vm as any).specForm.aConditions).toEqual(spec().aConditions)
  })

  it('loads a specification, retains its original condition snapshot, and preserves a newer editing session', async () => {
    const original = spec()
    const wrapper = keep(mount(ControlCenter, { props: { nodes, deviceTemplates: templates, activeSection: 'specs', editSpecification: original }, global: { plugins: [i18n] } }))
    const vm = wrapper.vm as any
    expect(vm.specForm.templateId).toBe('1')
    await wrapper.get('[data-testid="spec-name"]').setValue('  Prevent an unexpected unlock  ')
    vm.specForm.aConditions[0].value = 'on'
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-testid="spec-create"]').trigger('click')
    const request = wrapper.emitted('add-spec')?.[0]?.[0] as any
    expect(request.expected).toEqual(original)
    expect(request.name).toBe('Prevent an unexpected unlock')
    expect(request.aConditions[0].value).toBe('on')
    await wrapper.setProps({ editSpecification: spec('spec-8') })
    request.complete(true)
    await flushPromises()
    expect(vm.specForm.aConditions[0].value).toBe('off')
    expect(vm.specForm.templateId).toBe('1')
    expect(wrapper.emitted('finish-spec-edit')).toBeUndefined()
  })

  it.each(['rules', 'specs'] as const)('offers an accessible %s edit action and respects playback read-only state', async kind => {
    const wrapper = keep(mount(SystemInspector, { props: { devices: nodes, rules: [rule()], specifications: [spec()], activeSection: kind, collapsed: false }, global: { plugins: [i18n] } }))
    const selector = kind === 'rules' ? '[data-testid="edit-rule-7"]' : '[data-testid="edit-spec-spec-7"]'
    await wrapper.get(selector).trigger('click')
    expect(wrapper.emitted(kind === 'rules' ? 'edit-rule' : 'edit-spec')?.[0]).toEqual([kind === 'rules' ? '7' : 'spec-7'])
    await wrapper.setProps({ readOnly: true })
    expect(wrapper.get(selector).attributes('disabled')).toBeDefined()
  })
})
