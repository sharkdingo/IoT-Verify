import { DOMWrapper, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { i18n } from '@/assets/i18n'
import ControlCenter from '../ControlCenter.vue'

const createEditor = () => mount(ControlCenter, { attachTo: document.body,
    props: { activeSection: 'specs', nodes: [{ id: 'ac', label: 'AC', templateName: 'AC' }] as any,
      deviceTemplates: [{ name: 'AC', manifest: { Name: 'AC', Modes: ['AirState'], InitState: 'auto',
        WorkingStates: [{ Name: 'auto' }, { Name: 'heat' }], APIs: [],
        InternalVariables: [{ Name: 'temperature', LowerBound: 0, UpperBound: 40, IsInside: true }] } }] as any },
    global: { plugins: [i18n] } })

let wrapper: ReturnType<typeof createEditor>
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })

const mountEditor = async () => {
  wrapper = createEditor()
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
    ['=', 'FALSE'], ['!=', 'TRUE'], ['in', 'FALSE,TRUE'], ['not in', 'FALSE'],
    ['=', ' false '], ['IN', ' false | true ']
  ])('preserves an existing API condition %s %s when editing it', async (relation, value) => {
    await mountEditor()
    await wrapper.setProps({
      deviceTemplates: [{ name: 'AC', manifest: { Name: 'AC', Modes: ['AirState'], InitState: 'auto',
        WorkingStates: [{ Name: 'auto' }, { Name: 'heat' }], InternalVariables: [],
        APIs: [{ Name: 'warm', StartState: 'auto', EndState: 'heat', Signal: true }] } }] as any,
      editSpecification: { id: 'existing', name: 'Preserve the event condition', templateId: '1', templateLabel: '',
        aConditions: [{ id: 'event', side: 'a', deviceId: 'ac', deviceLabel: 'AC',
          targetType: 'api', key: 'warm', relation, value }], ifConditions: [], thenConditions: [] } as any
    })
    await wrapper.get(`button[aria-label="${i18n.global.t('app.editConditionNumbered', { number: 1 })}"]`).trigger('click')
    const dialog = new DOMWrapper(document.querySelector('[data-testid="spec-condition-dialog"]')!)
    const values = value.split(/[,;|]/).map(part => part.trim().toUpperCase()).sort()
    expect(dialog.get<HTMLSelectElement>('[data-testid="spec-condition-relation"]').element.value).toBe(relation.toLowerCase())
    const control = dialog.get<HTMLSelectElement>('[data-testid="spec-condition-value"]')
    expect(Array.from(control.element.selectedOptions).map(option => option.value)
      .sort()).toEqual(values)
    if (['in', 'not in'].includes(relation.toLowerCase())) await control.setValue(values)
    await dialog.get('[data-testid="spec-condition-save"]').trigger('click')
    const saved = (wrapper.vm as any).specForm.aConditions[0]
    expect(saved.relation).toBe(relation.toLowerCase())
    expect(saved.value.split(/[,;|]/).map((part: string) => part.trim()).sort()).toEqual(values)
    expect(wrapper.find('[data-testid="spec-target-change-notice"]').exists()).toBe(false)
    await wrapper.get('[data-testid="spec-create"]').trigger('click')
    const request = wrapper.emitted('add-spec')![0]![0] as any
    expect(request.aConditions[0].relation).toBe(relation.toLowerCase())
    expect(request.aConditions[0].value).toBe(saved.value)
    expect(request.expected.aConditions[0].value).toBe(value)
    request.complete(false)
    await flushPromises()
  })

  it('offers event occurrence values and restricts template 7 to a positive event', async () => {
    await mountEditor()
    await wrapper.setProps({ deviceTemplates: [{ name: 'AC', manifest: { Name: 'AC', Modes: ['AirState'], InitState: 'auto',
      WorkingStates: [{ Name: 'auto' }, { Name: 'heat' }], InternalVariables: [],
      APIs: [{ Name: 'warm', StartState: 'auto', EndState: 'heat', Signal: true }] } }] as any })
    const dialog = new DOMWrapper(document.querySelector('[data-testid="spec-condition-dialog"]')!)
    await dialog.get('[data-testid="spec-condition-device"]').setValue('ac')
    await dialog.get('[data-testid="spec-condition-type"]').setValue('api')
    await dialog.get('[data-testid="spec-condition-key"]').setValue('warm')
    expect(dialog.get('[data-testid="spec-api-event-hint"]').text()).toBe(i18n.global.t('app.specApiEventHint'))
    expect(dialog.findAll('[data-testid="spec-condition-relation"] option').map(option => option.attributes('value')))
      .toEqual(['=', '!=', 'in', 'not in'])
    expect(dialog.get<HTMLSelectElement>('[data-testid="spec-condition-value"]').element.value).toBe('TRUE')
    await dialog.get('[data-testid="spec-condition-value"]').setValue('FALSE')
    expect(dialog.get('[data-testid="spec-condition-preview"]').text()).toContain(i18n.global.t('app.apiEventNotOccurred'))
    await dialog.get('[data-testid="spec-condition-save"]').trigger('click')
    expect((wrapper.vm as any).specForm.aConditions[0].value).toBe('FALSE')

    await wrapper.get('[data-testid="spec-template-select"]').setValue('7')
    await wrapper.get('[data-testid="spec-add-condition-a"]').trigger('click')
    const safetyDialog = new DOMWrapper(document.querySelector('[data-testid="spec-condition-dialog"]')!)
    await safetyDialog.get('[data-testid="spec-condition-device"]').setValue('ac')
    await safetyDialog.get('[data-testid="spec-condition-type"]').setValue('api')
    await safetyDialog.get('[data-testid="spec-condition-key"]').setValue('warm')
    expect(safetyDialog.findAll('[data-testid="spec-condition-relation"] option').map(option => option.attributes('value'))).toEqual(['='])
    expect(safetyDialog.findAll('[data-testid="spec-condition-value"] option').map(option => option.attributes('value')).filter(Boolean)).toEqual(['TRUE'])
    ;(wrapper.vm as any).editingConditionData.value = 'FALSE'
    await wrapper.vm.$nextTick()
    expect(safetyDialog.get('[data-testid="spec-condition-save"]').attributes('disabled')).toBeDefined()
    expect(safetyDialog.get('[data-testid="spec-condition-blocked-reason"]').text()).toBe(i18n.global.t('app.specSafetyApiConditionRequired'))
  })

  it('blocks a state left over from another device until a valid value is chosen', async () => {
    const dialog = await mountEditor()
    await wrapper.setProps({
      nodes: [...(wrapper.props('nodes') ?? []), { id: 'heater', label: 'Heater', templateName: 'Heater' }] as any,
      deviceTemplates: [...(wrapper.props('deviceTemplates') ?? []), { name: 'Heater', manifest: {
        Name: 'Heater', Modes: ['Power'], InitState: 'off', WorkingStates: [{ Name: 'off' }, { Name: 'heat' }],
        APIs: [], InternalVariables: [] } }] as any
    })
    await dialog.get('[data-testid="spec-condition-device"]').setValue('ac')
    await dialog.get('[data-testid="spec-condition-value"]').setValue('auto')
    await dialog.get('[data-testid="spec-condition-device"]').setValue('heater')
    expect((wrapper.vm as any).editingConditionData.value).toBe('auto')
    expect(dialog.get('[data-testid="spec-condition-save"]').attributes('disabled')).toBeDefined()
    expect(dialog.get('[data-testid="spec-condition-blocked-reason"]').text()).toBe(i18n.global.t('app.specConditionValueUnavailable'))
    expect(dialog.find('[data-testid="spec-condition-preview"]').exists()).toBe(false)
    await (wrapper.vm as any).saveCondition()
    expect((wrapper.vm as any).specForm.aConditions).toHaveLength(0)
    await dialog.get('[data-testid="spec-condition-value"]').setValue('heat')
    await dialog.get('[data-testid="spec-condition-save"]').trigger('click')
    expect((wrapper.vm as any).specForm.aConditions[0]).toMatchObject({ deviceId: 'heater', value: 'heat' })
  })

  it.each([
    ['mode', '='], ['mode', 'in'], ['variable', '='], ['variable', 'not in']
  ])('rejects stale %s %s values after selecting another property', async (targetType, relation) => {
    const dialog = await mountEditor()
    await wrapper.setProps({ deviceTemplates: [{ name: 'AC', manifest: {
      Name: 'AC', Modes: ['AirState', 'Units'], InitState: 'auto;celsius',
      WorkingStates: [{ Name: 'auto;celsius' }, { Name: 'heat;fahrenheit' }], APIs: [],
      InternalVariables: [{ Name: 'AirState', IsInside: true, Values: ['auto', 'heat'] },
        { Name: 'Units', IsInside: true, Values: ['celsius', 'fahrenheit'] }] } }] as any })
    await dialog.get('[data-testid="spec-condition-device"]').setValue('ac')
    await dialog.get('[data-testid="spec-condition-type"]').setValue(targetType)
    await dialog.get('[data-testid="spec-condition-key"]').setValue('AirState')
    await dialog.get('[data-testid="spec-condition-relation"]').setValue(relation)
    const isSet = relation === 'in' || relation === 'not in'
    await dialog.get('[data-testid="spec-condition-value"]').setValue(isSet ? ['auto', 'heat'] : 'auto')
    await dialog.get('[data-testid="spec-condition-key"]').setValue('Units')
    expect(dialog.get('[data-testid="spec-condition-save"]').attributes('disabled')).toBeDefined()
    expect(dialog.get('[data-testid="spec-condition-blocked-reason"]').text()).toBe(i18n.global.t('app.specConditionValueUnavailable'))
    await dialog.get('[data-testid="spec-condition-value"]').setValue(isSet ? ['celsius'] : 'celsius')
    expect(dialog.get('[data-testid="spec-condition-save"]').attributes('disabled')).toBeUndefined()
    await dialog.get('[data-testid="spec-condition-save"]').trigger('click')
    expect((wrapper.vm as any).specForm.aConditions[0]).toMatchObject({ targetType, key: 'Units', value: 'celsius' })
  })

  it.each([
    ['mode', 'AirState', 'IN', ' auto | heat ', 'in', ['auto', 'heat']],
    ['trust', 'AirState', 'EQ', ' trusted ', '=', ['trusted']],
    ['variable', 'temperature', 'GTE', ' 25 ', '>=', ['25']]
  ])('preserves an existing %s condition while normalizing its supported spelling', async (targetType, key, relation, value, canonical, values) => {
    await mountEditor()
    await wrapper.setProps({ editSpecification: { id: 'existing', name: 'Existing requirement', templateId: '1',
      templateLabel: '', aConditions: [{ id: 'condition', side: 'a', deviceId: 'ac', deviceLabel: 'AC',
        targetType, key, relation, value, ...(targetType === 'trust' ? { propertyScope: 'state' } : {}),
        ...(targetType === 'variable' ? { variableSource: 'reported' } : {}) }], ifConditions: [], thenConditions: [] } as any })
    await wrapper.get(`button[aria-label="${i18n.global.t('app.editConditionNumbered', { number: 1 })}"]`).trigger('click')
    const dialog = new DOMWrapper(document.querySelector('[data-testid="spec-condition-dialog"]')!)
    expect(dialog.get<HTMLSelectElement>('[data-testid="spec-condition-relation"]').element.value).toBe(canonical)
    const input = dialog.get<HTMLInputElement | HTMLSelectElement>('[data-testid="spec-condition-value"]').element
    expect(input instanceof HTMLSelectElement ? Array.from(input.selectedOptions).map(option => option.value) : [input.value])
      .toEqual(values)
    await dialog.get('[data-testid="spec-condition-save"]').trigger('click')
    expect((wrapper.vm as any).specForm.aConditions[0]).toMatchObject({ targetType, key, relation: canonical })
    expect(wrapper.find('[data-testid="spec-target-change-notice"]').exists()).toBe(false)
  })

  it('blocks an action event whose API is no longer observable without replacing the draft', async () => {
    const dialog = await mountEditor()
    const template = { name: 'AC', manifest: { Name: 'AC', Modes: ['AirState'], InitState: 'auto',
      WorkingStates: [{ Name: 'auto' }, { Name: 'heat' }], InternalVariables: [],
      APIs: [{ Name: 'warm', StartState: 'auto', EndState: 'heat', Signal: true }] } }
    await wrapper.setProps({ deviceTemplates: [template] as any })
    await dialog.get('[data-testid="spec-condition-device"]').setValue('ac')
    await dialog.get('[data-testid="spec-condition-type"]').setValue('api')
    await dialog.get('[data-testid="spec-condition-key"]').setValue('warm')
    await wrapper.setProps({ deviceTemplates: [{ ...template, manifest: { ...template.manifest,
      APIs: [{ ...template.manifest.APIs[0], Signal: false }] } }] as any })
    expect(dialog.get('[data-testid="spec-condition-save"]').attributes('disabled')).toBeDefined()
    expect(dialog.get('[data-testid="spec-condition-blocked-reason"]').text()).toBe(i18n.global.t('app.specConditionPropertyUnavailable'))
    expect((wrapper.vm as any).editingConditionData).toMatchObject({ key: 'warm', value: 'TRUE' })
    expect(dialog.find('[data-testid="spec-condition-preview"]').exists()).toBe(false)
  })

  it('revalidates saved draft conditions against updated templates before submitting a specification', async () => {
    const dialog = await mountEditor()
    await wrapper.get('[data-testid="spec-name"]').setValue('Check the configured state')
    await dialog.get('[data-testid="spec-condition-device"]').setValue('ac')
    await dialog.get('[data-testid="spec-condition-value"]').setValue('auto')
    await dialog.get('[data-testid="spec-condition-save"]').trigger('click')
    expect(wrapper.get('[data-testid="spec-create"]').attributes('disabled')).toBeUndefined()
    const originalTemplate = wrapper.props('deviceTemplates')?.[0]
    if (!originalTemplate) throw new Error('Missing AC template fixture')
    await wrapper.setProps({ deviceTemplates: [{ ...originalTemplate, manifest: { ...originalTemplate.manifest,
      WorkingStates: [{ Name: 'cool' }] } }] as any })
    expect(wrapper.get('[data-testid="spec-create"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="spec-create-blocked-reason"]').text()).toBe(i18n.global.t('app.specConditionNeedsEdit', {
      side: 'A', number: 1, reason: i18n.global.t('app.specConditionValueUnavailable')
    }))
    expect(wrapper.find('[data-testid="spec-description"]').exists()).toBe(false)
    await (wrapper.vm as any).createSpecification()
    expect(wrapper.emitted('add-spec')).toBeUndefined()
    expect((wrapper.vm as any).specForm.aConditions[0].value).toBe('auto')
  })

  it.each(['enum', 'device-local'] as const)('blocks a saved numeric condition after its declaration becomes %s', async change => {
    const dialog = await mountEditor()
    const originalTemplate = wrapper.props('deviceTemplates')![0]!
    await wrapper.setProps({ deviceTemplates: [{ ...originalTemplate, manifest: { ...originalTemplate.manifest,
      InternalVariables: [{ Name: 'temperature', IsInside: false, Reads: true, LowerBound: 0, UpperBound: 40 }] } }] as any })
    await wrapper.get('[data-testid="spec-name"]').setValue('Check the temperature')
    await dialog.get('[data-testid="spec-condition-device"]').setValue('ac')
    await dialog.get('[data-testid="spec-condition-type"]').setValue('variable')
    await dialog.get('[data-testid="spec-condition-key"]').setValue('temperature')
    await dialog.get('[data-testid="spec-condition-variable-source-environment"] input').setValue(true)
    await dialog.get('[data-testid="spec-condition-relation"]').setValue('>=')
    await dialog.get('[data-testid="spec-condition-value"]').setValue('25')
    await dialog.get('[data-testid="spec-condition-save"]').trigger('click')
    expect(wrapper.get('[data-testid="spec-create"]').attributes('disabled')).toBeUndefined()
    const saved = { ...(wrapper.vm as any).specForm.aConditions[0] }
    const variable = change === 'enum'
      ? { Name: 'temperature', IsInside: false, Reads: true, Values: ['25', '30'] }
      : { Name: 'temperature', IsInside: true, LowerBound: 0, UpperBound: 40 }
    await wrapper.setProps({ deviceTemplates: [{ ...originalTemplate, manifest: { ...originalTemplate.manifest,
      InternalVariables: [variable] } }] as any })
    expect(wrapper.get('[data-testid="spec-create"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="spec-create-blocked-reason"]').text()).toBe(i18n.global.t('app.specConditionNeedsEdit', {
      side: 'A', number: 1, reason: i18n.global.t(change === 'enum'
        ? 'app.specConditionRelationUnavailable' : 'app.specVariableSourceDeviceLocalHelp')
    }))
    await (wrapper.vm as any).createSpecification()
    expect(wrapper.emitted('add-spec')).toBeUndefined()
    expect((wrapper.vm as any).specForm.aConditions[0]).toEqual(saved)
  })

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
