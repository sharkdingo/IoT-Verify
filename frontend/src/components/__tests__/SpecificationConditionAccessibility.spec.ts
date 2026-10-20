import { DOMWrapper, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { i18n } from '@/assets/i18n'
import ControlCenter from '../ControlCenter.vue'

let wrapper: ReturnType<typeof mount>
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })

const mountEditor = async () => {
  wrapper = mount(ControlCenter, { attachTo: document.body,
    props: { activeSection: 'specs', nodes: [{ id: 'ac', label: 'AC', templateName: 'AC' }] as any,
      deviceTemplates: [{ name: 'AC', manifest: { Name: 'AC', Modes: ['AirState'], InitState: 'auto',
        WorkingStates: [{ Name: 'auto' }, { Name: 'heat' }], APIs: [],
        InternalVariables: [{ Name: 'temperature', LowerBound: 0, UpperBound: 40, IsInside: true }] } }] as any },
    global: { plugins: [i18n] } })
  await wrapper.get('[data-testid="spec-template-select"]').setValue('3')
  await wrapper.get('[data-testid="spec-add-condition-a"]').trigger('click')
  return new DOMWrapper(document.querySelector('[data-testid="spec-condition-dialog"]')!)
}

const expectLabel = (id: string, key: string) => {
  const control = document.getElementById(id) as HTMLInputElement
  expect(control, id).not.toBeNull()
  expect(Array.from(control.labels ?? []).map(label => label.textContent?.trim())).toContain(i18n.global.t(key))
}

describe('specification condition editor accessibility', () => {
  it.each([
    ['privacy', 'private'], ['trust', 'untrusted']
  ] as const)('localizes %s values in a custom-template condition without changing the saved token', async (targetType, value) => {
    const dialog = await mountEditor()
    await dialog.get('[data-testid="spec-condition-device"]').setValue('ac')
    await dialog.get('[data-testid="spec-condition-type"]').setValue(targetType)
    await dialog.get('[data-testid="spec-condition-key"]').setValue(JSON.stringify(['state', 'AirState']))
    await dialog.get('[data-testid="spec-condition-value"]').setValue(value)
    try {
      for (const locale of ['en', 'zh-CN'] as const) {
        i18n.global.locale.value = locale
        await wrapper.vm.$nextTick()
        const option = dialog.get(`[data-testid="spec-condition-value"] option[value="${value}"]`)
        expect(option.text()).toBe(i18n.global.t(`app.${value}`))
        expect(dialog.get('[data-testid="spec-condition-preview"]').text()).toContain(i18n.global.t(`app.${value}`))
        expect(dialog.get('[data-testid="spec-condition-preview"]').text()).toContain(i18n.global.t(`app.${targetType}`))
        expect(dialog.get('[data-testid="spec-condition-preview"]').text()).toContain(i18n.global.t('app.currentStateProperty'))
      }
      await dialog.get('[data-testid="spec-condition-save"]').trigger('click')
      expect((wrapper.vm as any).specForm.aConditions[0].value).toBe(value)
      expect(wrapper.get('[data-testid="spec-description"]').text()).toContain(`“${i18n.global.t(`app.${value}`)}”`)
    } finally { i18n.global.locale.value = 'en' }
  })

  it('validates numeric values and sets before adding a specification condition', async () => {
    const dialog = await mountEditor()
    await dialog.get('[data-testid="spec-condition-device"]').setValue('ac')
    await dialog.get('[data-testid="spec-condition-type"]').setValue('variable')
    await dialog.get('[data-testid="spec-condition-key"]').setValue('temperature')
    const value = dialog.get('[data-testid="spec-condition-value"]')
    expect(value.attributes('placeholder')).toContain('0 - 40')
    for (const invalid of ['abc', '1.5', '-1', '41']) {
      await value.setValue(invalid)
      expect(dialog.get('[data-testid="spec-condition-save"]').attributes('disabled'), invalid).toBeDefined()
      expect(value.attributes('aria-invalid')).toBe('true')
      expect(dialog.get('[data-testid="spec-condition-blocked-reason"]').text()).toBe(i18n.global.t('app.variableValueIntegerDomain', { lower: 0, upper: 40 }))
      expect(dialog.find('[data-testid="spec-condition-preview"]').exists()).toBe(false)
      await (wrapper.vm as any).saveCondition()
      expect((wrapper.vm as any).specForm.aConditions).toHaveLength(0)
    }
    await dialog.get('[data-testid="spec-condition-relation"]').setValue('in')
    expect(value.attributes('placeholder')).toBe(i18n.global.t('app.conditionIntegerSetPlaceholder', { lower: 0, upper: 40 }))
    await value.setValue('0,41')
    expect(dialog.get('[data-testid="spec-condition-save"]').attributes('disabled')).toBeDefined()
    await value.setValue(' ,;| ')
    expect(dialog.get('[data-testid="spec-condition-save"]').attributes('disabled')).toBeDefined()
    await value.setValue('0; +01 |40')
    expect(value.attributes('aria-invalid')).toBeUndefined()
    expect(dialog.get('[data-testid="spec-condition-save"]').attributes('disabled')).toBeUndefined()
    await dialog.get('[data-testid="spec-condition-save"]').trigger('click')
    expect((wrapper.vm as any).specForm.aConditions).toHaveLength(1)
  })
  it('previews a configured condition without inventing a value for an incomplete condition', async () => {
    const dialog = await mountEditor()
    expect(dialog.find('[data-testid="spec-condition-preview"]').exists()).toBe(false)
    await dialog.get('[data-testid="spec-condition-device"]').setValue('ac')
    await dialog.get('[data-testid="spec-condition-type"]').setValue('state')
    expect(dialog.find('[data-testid="spec-condition-preview"]').exists()).toBe(false)
    await dialog.get('[data-testid="spec-condition-value"]').setValue('auto')
    expect(dialog.get('[data-testid="spec-condition-preview"]').text()).toContain('auto')
    await dialog.get('[data-testid="spec-condition-value"]').setValue('')
    expect(dialog.find('[data-testid="spec-condition-preview"]').exists()).toBe(false)
  })

  it('shows one source explanation for the selected variable capability and keeps affect-only label checks available', async () => {
    const dialog = await mountEditor()
    const variable = { Name: 'temperature', LowerBound: 0, UpperBound: 40, IsInside: false,
      Reads: true, FalsifiableWhenCompromised: true, Trust: 'trusted', Privacy: 'public', NaturalChangeRate: '0' }
    const updateVariable = async (fields = {}) => wrapper.setProps({ deviceTemplates: [{ name: 'AC', manifest: {
      Name: 'AC', Modes: ['AirState'], InitState: 'auto',
      WorkingStates: [{ Name: 'auto', Trust: 'trusted', Privacy: 'public' }], APIs: [],
      InternalVariables: [{ ...variable, ...fields }] } }] as any })
    await updateVariable()
    await dialog.get('[data-testid="spec-condition-device"]').setValue('ac')
    await dialog.get('[data-testid="spec-condition-type"]').setValue('variable')
    await dialog.get('[data-testid="spec-condition-key"]').setValue('temperature')
    const help = () => dialog.get('[data-testid="spec-condition-variable-source-help"]')
    expect(dialog.get('[data-testid="spec-condition-variable-source"]').findAll('p')).toHaveLength(1)
    expect(help().text()).toBe(i18n.global.t('app.specVariableSourceFalsifiableHint'))
    expect(dialog.find('[data-testid="spec-condition-variable-source-falsifiable"]').exists()).toBe(false)
    await updateVariable({ FalsifiableWhenCompromised: false })
    expect(help().text()).toBe(i18n.global.t('app.specVariableSourceNotFalsifiableHint'))
    await updateVariable({ Reads: false })
    expect(dialog.get('[data-testid="spec-condition-key"]').findAll('option').map(option => option.attributes('value')))
      .not.toContain('temperature')
    await dialog.get('[data-testid="spec-condition-type"]').setValue('trust')
    expect(dialog.get('[data-testid="spec-condition-key"]').findAll('option').map(option => option.attributes('value')))
      .toContain(JSON.stringify(['variable', 'temperature']))
  })

  it('names every field and uses a selection prompt before a device is chosen', async () => {
    const dialog = await mountEditor()
    expectLabel('spec-template-select', 'app.selectTemplate')
    expectLabel('spec-condition-device', 'app.device')
    expect(dialog.get('[data-testid="spec-condition-blocked-reason"]').text()).toBe(i18n.global.t('app.selectDevice'))
    expect(dialog.find('[data-testid="spec-condition-relation"]').exists()).toBe(false)
    expect(dialog.find('[data-testid="spec-condition-value"]').exists()).toBe(false)
    await dialog.get('[data-testid="spec-condition-device"]').setValue('ac')
    expectLabel('spec-condition-type', 'app.type')
    await dialog.get('[data-testid="spec-condition-type"]').setValue('state')
    expectLabel('spec-condition-relation', 'app.conditionOperator')
    expectLabel('spec-condition-value', 'app.value')
    await dialog.get('[data-testid="spec-condition-type"]').setValue('variable')
    expectLabel('spec-condition-key', 'app.property')
    await dialog.get('[data-testid="spec-condition-key"]').setValue('temperature')
    expectLabel('spec-condition-value', 'app.value')
  })

  it('retains the deleted-device warning for a genuinely missing saved reference', async () => {
    const dialog = await mountEditor()
    const vm = wrapper.vm as any
    vm.editingConditionData.deviceId = 'removed-device'
    await wrapper.vm.$nextTick()
    expect(dialog.get('[data-testid="spec-condition-blocked-reason"]').text()).toBe(i18n.global.t('app.specConditionDeviceMissing', { count: 1 }))
    expect(dialog.get('[data-testid="spec-condition-save"]').attributes('disabled')).toBeDefined()
  })
})
