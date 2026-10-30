// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SystemInspector from '../SystemInspector.vue'
import { i18n } from '@/assets/i18n'
import type { DeviceNode } from '@/types/node'
import type { DeviceTemplate } from '@/types/device'
import type { RuleForm } from '@/types/rule'
import type { Specification } from '@/types/spec'

const devices: DeviceNode[] = [
  {
    id: 'sensor-1',
    label: 'Hall sensor',
    templateName: 'Sensor',
    position: { x: 0, y: 0 },
    state: 'Working',
    width: 176,
    height: 128
  },
  {
    id: 'light-1',
    label: 'Hall light',
    templateName: 'Light',
    position: { x: 200, y: 0 },
    state: 'off',
    width: 176,
    height: 128
  }
]

describe('SystemInspector inventory loading', () => {
  it('distinguishes an unreceived inventory from a confirmed empty inventory in every tab', async () => {
    const wrapper = mount(SystemInspector, {
      props: { collapsed: false, dataLoading: true },
      global: { plugins: [i18n] }
    })
    try {
      for (const activeSection of ['devices', 'rules', 'specs', 'environment']) {
        await wrapper.setProps({ activeSection, dataLoading: true, dataUnavailable: false })
        expect(wrapper.get('.board-inventory-empty p').text()).toBe(i18n.global.t('app.boardSnapshotLoading'))
        expect(wrapper.get('.board-section-tab[aria-selected="true"] .board-section-tab__count').text()).toBe('—')
        expect(wrapper.get('.board-inventory-count').text()).toBe('—')
        await wrapper.setProps({ dataLoading: false, dataUnavailable: true })
        expect(wrapper.get('.board-inventory-empty p').text()).toBe(i18n.global.t('app.boardDataUnavailableShort'))
        await wrapper.setProps({ dataUnavailable: false })
        expect(wrapper.get('.board-section-tab[aria-selected="true"] .board-section-tab__count').text()).toBe('0')
        expect(wrapper.get('.board-inventory-count').text()).toBe('0/0')
        expect(wrapper.get('.board-inventory-empty p').text()).not.toBe(i18n.global.t('app.boardSnapshotLoading'))
        expect(wrapper.get('.board-inventory-empty p').text()).not.toBe(i18n.global.t('app.boardDataUnavailableShort'))
      }
    } finally { wrapper.unmount() }
  })

})

const mountEnvironmentEditor = async (attachTo?: HTMLElement) => {
  const template: DeviceTemplate = { name: 'Thermometer', manifest: { Name: 'Thermometer', InternalVariables: [{
    Name: 'temperature', IsInside: false, LowerBound: 0, UpperBound: 100, Trust: 'trusted', Privacy: 'private',
    FalsifiableWhenCompromised: false
  }] } }
  const wrapper = mount(SystemInspector, { attachTo, props: { activeSection: 'devices', deviceTemplates: [template],
    devices: [{ ...devices[0], templateName: template.name }],
    environmentVariables: [{ name: 'temperature', value: '20', trust: 'trusted', privacy: 'private' }] },
    global: { plugins: [i18n] } })
  await wrapper.setProps({ activeSection: 'environment' })
  await wrapper.get('article button').trigger('click')
  return wrapper
}

describe('environment value correction ownership', () => {
  beforeEach(() => { i18n.global.locale.value = 'en' })
  it('keeps an invalid value beside its input without emitting a save', async () => {
    const value = '101'
    const wrapper = await mountEnvironmentEditor()
    await wrapper.get('[data-testid="environment-value-temperature"]').setValue(value)
    expect(wrapper.emitted('save-environment')).toBeUndefined()
    const input = wrapper.get('[data-testid="environment-value-temperature"]')
    expect(input.attributes('aria-invalid')).toBe('true')
    expect(input.attributes('aria-describedby')).toBe('environment-value-failure-temperature')
    expect(wrapper.get('#environment-value-failure-temperature').isVisible()).toBe(true)
    expect(wrapper.get('[data-testid="environment-value-failure-temperature"]').text())
      .toContain(value ? 'Enter an integer from 0 to 100.' : 'Enter a value.')
    expect(wrapper.get('[data-testid="environment-value-failure-temperature"] [data-testid="failure-cause"]').isVisible()).toBe(true)
    await wrapper.setProps({ environmentSaving: true })
    await wrapper.setProps({ environmentSaving: false })
    expect(wrapper.get('[data-testid="environment-value-temperature"]').element).toHaveProperty('value', value)
    wrapper.unmount()
  })

  it('reveals only the focused field diagnostic without stealing focus', async () => {
    const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView')
    const reveal = vi.fn()
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: reveal })
    const wrapper = await mountEnvironmentEditor(document.body)
    try {
      const input = wrapper.get<HTMLInputElement>('[data-testid="environment-value-temperature"]')
      input.element.focus()
      await input.setValue('101')
      expect(reveal).toHaveBeenCalledWith({ block: 'nearest' })
      expect(reveal.mock.contexts[0]).toBe(wrapper.get('#environment-value-failure-temperature').element)
      expect(document.activeElement).toBe(input.element)
      await input.setValue('20')
      const search = wrapper.get<HTMLInputElement>('[data-testid="inspector-search-environment"]')
      search.element.focus()
      reveal.mockClear()
      await input.setValue('')
      expect(reveal).not.toHaveBeenCalled()
      expect(document.activeElement).toBe(search.element)
    } finally {
      wrapper.unmount()
      if (original) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', original)
      else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView')
    }
  })

  it('keeps a known rejection with its current draft, then clears it on correction', async () => {
    const wrapper = await mountEnvironmentEditor()
    const input = wrapper.get('[data-testid="environment-value-temperature"]')
    await input.setValue('25')
    const [updates, complete] = wrapper.emitted('save-environment')!.at(-1)! as any[]
    expect(typeof complete).toBe('function')
    expect(updates[0]).toEqual({ name: 'temperature', expected: { value: '20', trust: 'trusted', privacy: 'private' }, desired: { value: '25' } })
    await wrapper.setProps({ environmentSaving: true })
    await wrapper.setProps({ environmentSaving: false })
    complete(false, { details: ['Rejected'], fields: { 'environmentVariables[0].value': 'Allowed range 0..100' } })
    await flushPromises()
    expect(input.element).toHaveProperty('value', '25')
    expect(wrapper.get('[data-testid="environment-value-failure-temperature"]').text()).toContain('Allowed range 0..100')
    await input.setValue('26')
    expect(input.attributes('aria-invalid')).toBeUndefined()
    expect(input.attributes('aria-describedby')).toBeUndefined()
    complete(false, { details: ['Late old rejection'] })
    await flushPromises()
    expect(wrapper.find('[data-testid="environment-value-failure-temperature"]').exists()).toBe(false)
    expect(input.element).toHaveProperty('value', '26')
    wrapper.unmount()
  })

  it('does not clear a newer value draft when an older save succeeds', async () => {
    const wrapper = await mountEnvironmentEditor()
    const input = wrapper.get('[data-testid="environment-value-temperature"]')
    await input.setValue('25')
    const complete = wrapper.emitted('save-environment')!.at(-1)![1] as Function
    expect(typeof complete).toBe('function')
    await input.setValue('26')
    complete(true)
    await flushPromises()
    expect(input.element).toHaveProperty('value', '26')
    expect(wrapper.props('environmentVariables')).toEqual([{ name: 'temperature', value: '20', trust: 'trusted', privacy: 'private' }])
    wrapper.unmount()
  })
  it('preserves an invalid value draft when saving only its trust label', async () => {
    const wrapper = await mountEnvironmentEditor()
    const input = wrapper.get('[data-testid="environment-value-temperature"]')
    await input.setValue('101')
    await wrapper.get('[data-testid="environment-trust-temperature"]').setValue('untrusted')
    const [updates, complete] = wrapper.emitted('save-environment')!.at(-1)! as any[]
    expect(updates[0].desired).toEqual({ trust: 'untrusted' })
    complete(true)
    await flushPromises()
    expect(input.element).toHaveProperty('value', '101')
    expect(wrapper.get('[data-testid="environment-value-failure-temperature"] [data-testid="failure-cause"]').text())
      .toContain('Enter an integer from 0 to 100.')
    expect(wrapper.props('environmentVariables')![0].value).toBe('20')
    wrapper.unmount()
  })
})

describe('inspector environment inventory', () => {
  beforeEach(() => { i18n.global.locale.value = 'en' })

  it('opens Environment as a peer tab and keeps it out of the device inventory', async () => {
    const wrapper = await mountEnvironmentEditor()
    await wrapper.setProps({ activeSection: undefined })
    expect(wrapper.find('[data-testid="environment-pool"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="inspector-section-devices"]').exists()).toBe(true)
    const tab = wrapper.get('[data-testid="inspector-tab-environment"]')
    expect(wrapper.findAll('[role="tab"]')).toHaveLength(4)
    expect(tab.text()).toContain('1')
    await tab.trigger('click')
    expect(wrapper.emitted('update:active-section')?.at(-1)).toEqual(['environment'])
    expect(tab.attributes('aria-selected')).toBe('true')
    expect(tab.attributes('aria-controls')).toBe('inspector-panel-environment')
    expect(wrapper.get('#inspector-panel-environment').attributes('aria-labelledby')).toBe(tab.attributes('id'))
    expect(wrapper.find('[data-testid="inspector-section-devices"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="environment-pool"]').text()).toContain('Traces never overwrite these values.')
    wrapper.unmount()
  })

  it('retains an invalid initial-value draft across tabs and searches related devices', async () => {
    const wrapper = await mountEnvironmentEditor()
    await wrapper.get('[data-testid="environment-value-temperature"]').setValue('101')
    await wrapper.setProps({ activeSection: 'devices' })
    await wrapper.setProps({ activeSection: 'environment' })
    expect((wrapper.get('[data-testid="environment-value-temperature"]').element as HTMLInputElement).value).toBe('101')
    expect(wrapper.get('[data-testid="environment-value-failure-temperature"]').text()).toContain('0 to 100')
    vi.useFakeTimers()
    try {
      await wrapper.get('[data-testid="inspector-search-environment"]').setValue('Hall sensor')
      await vi.advanceTimersByTimeAsync(200)
      expect(wrapper.findAll('[data-environment-name]')).toHaveLength(1)
      await wrapper.get('[data-testid="inspector-search-environment"]').setValue('absent')
      await vi.advanceTimersByTimeAsync(200)
      expect(wrapper.findAll('[data-environment-name]')).toHaveLength(0)
      expect(wrapper.text()).toContain('No matching environment variables')
      expect(wrapper.emitted('save-environment')).toBeUndefined()
    } finally {
      wrapper.unmount()
      vi.useRealTimers()
    }
  })

  it('groups reader and affect-only device links separately for one shared value', async () => {
    const definition = { Name: 'temperature', IsInside: false, Reads: true,
      LowerBound: 0, UpperBound: 100, NaturalChangeRate: '0', Trust: 'trusted', Privacy: 'public',
      FalsifiableWhenCompromised: false }
    const reader: DeviceTemplate = { name: 'Reader', manifest: { Name: 'Reader', InternalVariables: [definition] } }
    const writer: DeviceTemplate = { name: 'Writer', manifest: { Name: 'Writer',
      InternalVariables: [{ ...definition, Reads: false }], ImpactedVariables: ['temperature'] } }
    const wrapper = mount(SystemInspector, { props: { activeSection: 'environment',
      deviceTemplates: [reader, writer], devices: [
        { ...devices[0], templateName: reader.name, label: 'Thermometer' },
        { ...devices[1], templateName: writer.name, label: 'Heater' }
      ], environmentVariables: [{ name: 'temperature', value: '20', trust: 'trusted', privacy: 'public' }] },
      global: { plugins: [i18n] } })
    expect(wrapper.findAll('[data-environment-name]')).toHaveLength(1)
    expect(wrapper.get('.board-environment-labels').text()).toContain('1 reading · 1 affecting')
    await wrapper.get('[data-environment-name] button').trigger('click')
    const groups = wrapper.findAll('.board-environment-sources > div')
    expect(groups[0].text()).toContain('Reading devices')
    expect(groups[0].text()).toContain('Thermometer')
    expect(groups[0].text()).not.toContain('Heater')
    expect(groups[1].text()).toContain('Affecting devices')
    expect(groups[1].text()).toContain('Heater')
    await groups[1].get('button').trigger('click')
    expect(wrapper.emitted('device-click')).toEqual([['light-1']])
    wrapper.unmount()
  })
})

const rule = (id: string, name: string, action: string): RuleForm => ({
  id,
  name,
  sources: [{
    fromId: 'sensor-1',
    fromApi: 'motion',
    itemType: 'variable',
    relation: '=',
    value: 'detected'
  }],
  toId: 'light-1',
  toApi: action
})

describe('SystemInspector rule execution order', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'en'
  })

  it('separates all conditions, target action and label source', () => {
    const locale = 'en'
    i18n.global.locale.value = locale
    const entry = rule('1', 'Hall lighting', 'on')
    entry.sources.push({ fromId: 'sensor-1', fromApi: 'alarm', itemType: 'api' })
    entry.contentDevice = 'sensor-1'
    entry.content = 'motion'
    const wrapper = mount(SystemInspector, {
      props: { activeSection: 'rules', devices, rules: [entry] },
      global: { plugins: [i18n] }
    })
    try {
      const card = wrapper.get('[data-rule-id="1"]')
      expect(card.get('dt').text()).toBe('Trigger conditions · all required')
      expect(card.findAll('dt')[1]!.text()).toBe('Action to execute')
      expect(card.findAll('[data-testid="inspector-rule-conditions"] li').map(item => item.text()))
        .toEqual(['Hall sensor motion = detected', `Hall sensor ${i18n.global.t('app.triggers')} alarm`])
      expect(card.get('[data-testid="inspector-rule-command"]').findAll('p').map(item => item.text()))
        .toEqual(['Hall light', 'on'])
      expect(card.get('[data-testid="inspector-rule-content-source"]').text()).toBe('Hall sensor motion')
      const ordering = card.get('[role="group"]')
      expect(ordering.attributes('aria-label')).toBe(i18n.global.t('app.ruleExecutionOrder'))
      expect(ordering.findAll('button')).toHaveLength(2)
      expect(card.get(`button[aria-label="${i18n.global.t('app.deleteRule')}"]`).element.closest('[role="group"]')).toBeNull()
    } finally {
      wrapper.unmount()
    }
  })

  it.each(['devices', 'rules', 'specs'] as const)('only mounts the %s search tooltip when its clear control exists', async section => {
    const warnings = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const wrapper = mount(SystemInspector, {
      props: { activeSection: section, devices, rules: [rule('1', 'Hall light', 'on')] },
      global: { plugins: [i18n] }
    })
    try {
      const clearSelector = `button[aria-label="${i18n.global.t('app.clearSearch')}"]`
      expect(wrapper.find(clearSelector).exists()).toBe(false)
      const input = wrapper.get(`[data-testid="inspector-search-${section}"]`)
      await input.setValue('Hall')
      await wrapper.get(clearSelector).trigger('click')
      expect((input.element as HTMLInputElement).value).toBe('')
      expect(wrapper.find(clearSelector).exists()).toBe(false)
      expect(warnings.mock.calls.flat().map(String).join('\n')).not.toContain('ElOnlyChild')
    } finally {
      wrapper.unmount()
      warnings.mockRestore()
    }
  })

  it('shows first-match semantics and emits an explicit complete-order move intent', async () => {
    const wrapper = mount(SystemInspector, {
      props: {
        activeSection: 'rules',
        devices,
        rules: [rule('1', 'Turn off first', 'off'), rule('2', 'Turn on second', 'on')]
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.get('[data-testid="rule-execution-order-hint"]').text())
      .toContain('first matching rule')
    expect(wrapper.text()).toContain('#1')
    expect(wrapper.text()).toContain('#2')
    expect(wrapper.get('[data-rule-id="1"]').attributes('tabindex')).toBe('-1')

    const moveEarlierButtons = wrapper.findAll('button[aria-label="Move earlier"]')
    expect(moveEarlierButtons).toHaveLength(2)
    expect(moveEarlierButtons[0].attributes('disabled')).toBeDefined()
    await moveEarlierButtons[1].trigger('click')

    expect(wrapper.emitted('move-rule')).toEqual([['2', 'up']])
  })

  it('lets keyboard users select a device and keeps its delete action discoverable without hover', async () => {
    const wrapper = mount(SystemInspector, {
      props: {
        activeSection: 'devices',
        devices,
        rules: []
      },
      global: { plugins: [i18n] }
    })

    const device = wrapper.get('[data-device-id="sensor-1"]')
    const openButton = device.get('button[aria-label="Hall sensor"]')
    expect(openButton.element.tagName).toBe('BUTTON')
    expect(openButton.attributes('type')).toBe('button')
    await openButton.trigger('click')
    expect(wrapper.emitted('device-click')).toEqual([['sensor-1']])

    const deleteButton = device.get('button[aria-label="Remove device"]')
    expect(deleteButton.classes()).not.toContain('opacity-0')
  })

  it('implements roving tab focus and links each tab to its panel', async () => {
    const wrapper = mount(SystemInspector, {
      attachTo: document.body,
      props: { activeSection: 'devices', devices, rules: [] },
      global: { plugins: [i18n] }
    })

    const devicesTab = wrapper.get('[data-testid="inspector-tab-devices"]')
    const rulesTab = wrapper.get('[data-testid="inspector-tab-rules"]')
    expect(devicesTab.attributes('tabindex')).toBe('0')
    expect(rulesTab.attributes('tabindex')).toBe('-1')
    expect(devicesTab.attributes('aria-controls')).toBe('inspector-panel-devices')
    expect(wrapper.get('#inspector-panel-devices').attributes('aria-labelledby'))
      .toBe('inspector-tab-devices')

    ;(devicesTab.element as HTMLElement).focus()
    await devicesTab.trigger('keydown', { key: 'ArrowRight' })
    expect(document.activeElement).toBe(rulesTab.element)
    expect(wrapper.emitted('update:active-section')?.at(-1)).toEqual(['rules'])

    await wrapper.setProps({ activeSection: 'rules' })
    expect(wrapper.get('[data-testid="inspector-tab-rules"]').attributes('tabindex')).toBe('0')
    expect(wrapper.get('#inspector-panel-rules').attributes('role')).toBe('tabpanel')
    await wrapper.get('[data-testid="inspector-tab-rules"]').trigger('keydown', { key: 'ArrowLeft' })
    expect(document.activeElement).toBe(wrapper.get('[data-testid="inspector-tab-devices"]').element)
    expect(wrapper.emitted('update:active-section')?.at(-1)).toEqual(['devices'])

    await wrapper.setProps({ activeSection: 'devices' })
    await wrapper.get('[data-testid="inspector-tab-devices"]').trigger('keydown', { key: 'End' })
    expect(document.activeElement).toBe(wrapper.get('[data-testid="inspector-tab-environment"]').element)
    expect(wrapper.emitted('update:active-section')?.at(-1)).toEqual(['environment'])

    await wrapper.setProps({ activeSection: 'specs' })
    await wrapper.get('[data-testid="inspector-tab-specs"]').trigger('keydown', { key: 'Home' })
    expect(document.activeElement).toBe(wrapper.get('[data-testid="inspector-tab-devices"]').element)
    expect(wrapper.emitted('update:active-section')?.at(-1)).toEqual(['devices'])
    wrapper.unmount()
  })

  it('distinguishes a device-written discrete value from an exogenous one', async () => {
    // The two branches say opposite things about what the verifier does, so the panel must pick the
    // right one. A device that declares it writes the value makes it hold when no effect applies
    // (exact); a value nobody writes may move freely (a disclosed abstraction). Showing the
    // "nondeterministic" wording for a written value is what the old copy did.
    const writtenTemplate: DeviceTemplate = {
      name: 'Purifier',
      defaultTemplate: false,
      manifest: {
        Name: 'Purifier',
        Modes: ['Power'],
        InitState: 'on',
        WorkingStates: [{
          Name: 'on',
          Trust: 'trusted',
          Privacy: 'public',
          Dynamics: [{ VariableName: 'airQuality', Value: 'good' }]
        }],
        InternalVariables: [{
          Name: 'airQuality',
          IsInside: false,
          Reads: false,
          FalsifiableWhenCompromised: false,
          Trust: 'trusted',
          Privacy: 'public',
          Values: ['poor', 'good']
        }],
        ImpactedVariables: ['airQuality']
      }
    }
    const wrapper = mount(SystemInspector, {
      props: {
        activeSection: 'devices',
        deviceTemplates: [writtenTemplate],
        devices: [{ ...devices[0], id: 'purifier-1', label: 'Purifier', templateName: 'Purifier', state: 'on' }],
        environmentVariables: [{ name: 'airQuality', value: 'poor' }]
      },
      global: { plugins: [i18n] }
    })

    await wrapper.setProps({ activeSection: 'environment' })
    await wrapper.get('article button').trigger('click')
    const evolution = wrapper.get('[data-testid="environment-evolution-airQuality"]').text()
    expect(evolution).toContain('Written by device dynamics')
    expect(evolution).not.toContain('External input')

    // Reads=false must not be presented as a read, which is the claim the generator contradicts by
    // emitting no read mirror for this device.
    expect(wrapper.text()).not.toContain('Reads this environment variable')
  })

  it('localizes bundled model tokens while preserving canonical edit values and custom states', async () => {
    i18n.global.locale.value = 'zh-CN'
    const bundledTemplate: DeviceTemplate = {
      name: 'Light',
      defaultTemplate: true,
      manifest: {
        Name: 'Light',
        Modes: ['SwitchState'],
        InitState: 'off',
        WorkingStates: [
          { Name: 'off', Trust: 'trusted', Privacy: 'public' },
          { Name: 'on', Trust: 'trusted', Privacy: 'public' }
        ],
        InternalVariables: [{
          Name: 'temperature',
          IsInside: false,
          FalsifiableWhenCompromised: false,
          Trust: 'trusted',
          Privacy: 'private',
          Values: ['off', 'on']
        }]
      }
    }
    const customTemplate: DeviceTemplate = {
      name: 'Custom device',
      defaultTemplate: false,
      manifest: {
        Name: 'Custom device',
        Modes: ['CustomMode'],
        InitState: 'ecoBoost',
        WorkingStates: [{ Name: 'ecoBoost', Trust: 'trusted', Privacy: 'public' }]
      }
    }
    const wrapper = mount(SystemInspector, {
      props: {
        activeSection: 'devices',
        deviceTemplates: [bundledTemplate, customTemplate],
        devices: [
          { ...devices[1], templateName: 'Light', state: 'off' },
          { ...devices[0], id: 'custom-1', label: 'Custom', templateName: 'Custom device', state: 'ecoBoost' }
        ],
        environmentVariables: [{ name: 'temperature', value: 'off' }]
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.text()).toContain('关闭')
    expect(wrapper.text()).toContain('ecoBoost')
    await wrapper.setProps({ activeSection: 'environment' })
    expect(wrapper.text()).toContain('温度')
    await wrapper.get('article button').trigger('click')
    // This fixture's `temperature` has an enum domain and no device declaring it writes the value,
    // so it is an exogenous input and the panel must say so -- including that the free choice is a
    // deliberate abstraction. The old copy said "may change when no device effect applies", which is
    // what a DEVICE-WRITTEN value does NOT do: it holds. Pinning the exogenous wording here and the
    // written wording below keeps the panel honest about which of the two a user is looking at.
    expect(wrapper.get('[data-testid="environment-evolution-temperature"]').text())
      .toContain('外部输入')
    const valueSelect = wrapper.get('[data-testid="environment-value-temperature"]')
    expect(valueSelect.find('option[value="off"]').text()).toBe('关闭')
    await wrapper.setProps({ environmentSaving: true })
    expect(valueSelect.attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="environment-trust-temperature"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="environment-privacy-temperature"]').attributes('disabled')).toBeDefined()
    await wrapper.setProps({ environmentSaving: false })
    await valueSelect.setValue('on')
    expect(wrapper.emitted('save-environment')?.at(-1)?.[0]).toEqual([
      {
        name: 'temperature',
        // Missing labels use the same template fallbacks in the visible row and CAS baseline.
        expected: { value: 'off', trust: 'trusted', privacy: 'private' },
        desired: { value: 'on' }
      }
    ])
  })

  // The range chip had its own single-bound rule: it accepted either bound and printed the missing
  // side as an infinity, which `device-template-schema.json` cannot produce. Nothing asserted the chip,
  // so the divergence from `templateVariableUsesNumericBounds` was invisible. A `null` bound is the
  // case that separated the two predicates, because board responses serialize an absent bound that way.
  it('renders a numeric range only when both bounds are declared', async () => {
    const boundedTemplate: DeviceTemplate = {
      name: 'Thermometer',
      manifest: {
        Name: 'Thermometer',
        InternalVariables: [{
          Name: 'temperature',
          IsInside: false,
          FalsifiableWhenCompromised: false,
          Trust: 'untrusted',
          Privacy: 'public',
          LowerBound: 15,
          UpperBound: 35,
          NaturalChangeRate: '[-1, 1]'
        }]
      }
    }
    const halfBounded: DeviceTemplate = {
      name: 'Drifting probe',
      manifest: {
        Name: 'Drifting probe',
        InternalVariables: [{
          Name: 'pressure',
          IsInside: false,
          FalsifiableWhenCompromised: false,
          Trust: 'untrusted',
          Privacy: 'public',
          LowerBound: 5,
          UpperBound: null,
          NaturalChangeRate: '[-1, 1]'
        } as never]
      }
    }
    const wrapper = mount(SystemInspector, {
      props: {
        activeSection: 'devices',
        deviceTemplates: [boundedTemplate, halfBounded],
        devices: [
          { ...devices[0], id: 'thermo-1', label: 'Indoor', templateName: 'Thermometer' },
          { ...devices[1], id: 'probe-1', label: 'Probe', templateName: 'Drifting probe' }
        ],
        environmentVariables: [
          { name: 'temperature', value: '20', trust: 'untrusted', privacy: 'public' },
          { name: 'pressure', value: '7', trust: 'untrusted', privacy: 'public' }
        ]
      },
      global: { plugins: [i18n] }
    })

    await wrapper.setProps({ activeSection: 'environment' })
    const chips = wrapper.findAll('[data-testid="environment-pool"] article button span[data-full-text]')
      .map(chip => chip.attributes('data-full-text'))
    expect(chips).toContain('15 - 35')
    // A half-declared domain is not a range: it reports "default value", never "5 - ∞".
    expect(chips).toContain('Default value')
    expect(chips.some(chip => chip?.includes('∞'))).toBe(false)
  })

  it('disables environment controls and explains when the authoritative value is missing', async () => {
    const finiteTemplate: DeviceTemplate = {
      name: 'Signal source',
      defaultTemplate: false,
      manifest: {
        Name: 'Signal source',
        InternalVariables: [{
          Name: 'signal',
          IsInside: false,
          FalsifiableWhenCompromised: false,
          Trust: 'untrusted',
          Privacy: 'public',
          Values: ['off', 'on']
        }]
      }
    }
    const wrapper = mount(SystemInspector, {
      props: {
        activeSection: 'devices',
        deviceTemplates: [finiteTemplate],
        devices: [{
          ...devices[0],
          id: 'signal-1',
          label: 'Signal source',
          templateName: 'Signal source'
        }],
        environmentVariables: [{
          name: 'signal',
          trust: 'untrusted',
          privacy: 'public'
        }]
      },
      global: { plugins: [i18n] }
    })

    await wrapper.setProps({ activeSection: 'environment' })
    await wrapper.get('[data-testid="environment-pool"] article button').trigger('click')
    const valueSelect = wrapper.get('[data-testid="environment-value-signal"]')
    expect((valueSelect.element as HTMLSelectElement).value).toBe('off')
    // The control is disabled and an explanation is shown; there is no verifiable baseline to edit.
    expect(valueSelect.attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="environment-trust-signal"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="environment-privacy-signal"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="environment-not-editable-signal"]').exists()).toBe(true)
    await valueSelect.setValue('on')

    expect(wrapper.emitted('save-environment')).toBeUndefined()
  })

  it('separates the model initial value from declared per-step evolution', async () => {
    const template: DeviceTemplate = {
      name: 'Heater',
      manifest: {
        Name: 'Heater',
        Modes: ['Power'],
        InitState: 'off',
        InternalVariables: [{
          Name: 'temperature',
          IsInside: false,
          FalsifiableWhenCompromised: false,
          Trust: 'untrusted',
          Privacy: 'public',
          LowerBound: 0,
          UpperBound: 50,
          NaturalChangeRate: '[-1, 1]'
        }],
        ImpactedVariables: ['temperature'],
        WorkingStates: [
          { Name: 'off', Trust: 'trusted', Privacy: 'public', Dynamics: [{ VariableName: 'temperature', ChangeRate: '0' }] },
          { Name: 'on', Trust: 'trusted', Privacy: 'public', Dynamics: [{ VariableName: 'temperature', ChangeRate: '2' }] }
        ]
      }
    }
    const wrapper = mount(SystemInspector, {
      props: {
        activeSection: 'devices',
        deviceTemplates: [template],
        devices: [{ ...devices[0], id: 'heater-1', label: 'Hall heater', templateName: 'Heater', state: 'on' }],
        environmentVariables: [{ name: 'temperature', value: '20', trust: 'untrusted', privacy: 'public' }]
      },
      global: { plugins: [i18n] }
    })

    await wrapper.setProps({ activeSection: 'environment' })
    await wrapper.get('[data-testid="environment-pool"] article button').trigger('click')
    const evolution = wrapper.get('[data-testid="environment-evolution-temperature"]')
    expect(wrapper.get('[data-testid="environment-value-temperature"]').element.closest('label')?.textContent)
      .toContain('Model initial value')
    expect(evolution.text()).toContain('[-1, 1]')
    expect(evolution.text()).toContain('-1, 0, +1')
    expect(evolution.text()).toContain('Hall heater · on: rate 2 per step')
    expect(evolution.text()).toContain('does not write the trace back')
  })

  it('shows conflicting active definitions and prevents every environment edit', async () => {
    const template = (name: string, rate: string): DeviceTemplate => ({
      name,
      manifest: {
        Name: name,
        InternalVariables: [{
          Name: 'temperature',
          IsInside: false,
          FalsifiableWhenCompromised: false,
          Trust: 'untrusted',
          Privacy: 'public',
          LowerBound: 0,
          UpperBound: 100,
          NaturalChangeRate: rate
        }]
      }
    })
    const wrapper = mount(SystemInspector, {
      props: {
        activeSection: 'devices',
        deviceTemplates: [template('Indoor Sensor', '[-1, 1]'), template('Outdoor Sensor', '[-5, 5]')],
        devices: [
          { ...devices[0], id: 'indoor-1', label: 'Indoor', templateName: 'Indoor Sensor' },
          { ...devices[1], id: 'outdoor-1', label: 'Outdoor', templateName: 'Outdoor Sensor' }
        ],
        environmentVariables: [{ name: 'temperature', value: '20', trust: 'untrusted', privacy: 'public' }]
      },
      global: { plugins: [i18n] }
    })

    await wrapper.setProps({ activeSection: 'environment' })
    await wrapper.get('[data-testid="environment-pool"] article button').trigger('click')
    expect(wrapper.get('[data-testid="environment-conflict-temperature"]').text())
      .toContain('natural change rates differ')
    const value = wrapper.get('[data-testid="environment-value-temperature"]')
    expect(value.attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="environment-trust-temperature"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="environment-privacy-temperature"]').attributes('disabled')).toBeDefined()
    await value.setValue('21')
    expect(wrapper.emitted('save-environment')).toBeUndefined()
  })

  it('keeps device and panel navigation available while mutation controls are read-only', async () => {
    const wrapper = mount(SystemInspector, {
      props: {
        activeSection: 'devices',
        devices,
        readOnly: true,
        readOnlyMessage: 'The scene is being replaced.'
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.get('[data-testid="inspector-add-device"]').attributes('disabled')).toBeDefined()
    const deviceButton = wrapper.get('[data-device-id="sensor-1"] button[aria-label="Hall sensor"]')
    expect(deviceButton.attributes('disabled')).toBeUndefined()
    expect(deviceButton.element.closest('[aria-disabled="true"]')).toBeNull()
    await deviceButton.trigger('click')
    expect(wrapper.emitted('device-click')).toEqual([['sensor-1']])
    expect(wrapper.get('[data-device-id="sensor-1"] button[aria-label="Remove device"]')
      .attributes('disabled')).toBeDefined()
    const collapse = wrapper.get('button[aria-label="Collapse System Inspector"]')
    expect(collapse.element.closest('[aria-disabled="true"]')).toBeNull()
    await collapse.trigger('click')
    expect(wrapper.emitted('update:collapsed')).toEqual([[true]])
    wrapper.unmount()
  })

  it('localizes the synthetic lowercase state field in bundled rule summaries', () => {
    i18n.global.locale.value = 'zh-CN'
    const bundledTemplate: DeviceTemplate = {
      name: 'Light',
      defaultTemplate: true,
      manifest: {
        Name: 'Light',
        Modes: ['SwitchState'],
        InitState: 'off',
        WorkingStates: [
          { Name: 'off', Trust: 'trusted', Privacy: 'public' },
          { Name: 'on', Trust: 'trusted', Privacy: 'public' }
        ]
      }
    }
    const stateRule: RuleForm = {
      id: 'state-rule',
      name: 'State rule',
      sources: [{
        fromId: 'light-1',
        fromApi: 'state',
        itemType: 'state',
        relation: '=',
        value: 'off'
      }],
      toId: 'light-1',
      toApi: 'on'
    }
    const wrapper = mount(SystemInspector, {
      props: {
        activeSection: 'rules',
        deviceTemplates: [bundledTemplate],
        devices: [{ ...devices[1], templateName: 'Light', state: 'off' }],
        rules: [stateRule]
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.get('[data-rule-id="state-rule"]').text()).toContain('状态 = 关闭')
    expect(stateRule.sources[0]).toMatchObject({ fromApi: 'state', value: 'off' })
  })

  it('preserves shared environment tokens when any provider is a custom template', async () => {
    i18n.global.locale.value = 'zh-CN'
    const environmentDefinition = {
      Name: 'temperature',
      IsInside: false,
      FalsifiableWhenCompromised: false,
      Trust: 'trusted',
      Privacy: 'public',
      Values: ['off', 'on']
    }
    const bundledTemplate: DeviceTemplate = {
      name: 'Light',
      defaultTemplate: true,
      manifest: {
        Name: 'Light',
        Modes: ['SwitchState'],
        InitState: 'off',
        WorkingStates: [{ Name: 'off', Trust: 'trusted', Privacy: 'public' }],
        InternalVariables: [environmentDefinition]
      }
    }
    const customTemplate: DeviceTemplate = {
      name: 'Custom provider',
      defaultTemplate: false,
      manifest: {
        Name: 'Custom provider',
        Modes: ['CustomMode'],
        InitState: 'off',
        WorkingStates: [{ Name: 'off', Trust: 'trusted', Privacy: 'public' }],
        InternalVariables: [environmentDefinition]
      }
    }
    const wrapper = mount(SystemInspector, {
      props: {
        activeSection: 'devices',
        deviceTemplates: [bundledTemplate, customTemplate],
        devices: [
          { ...devices[1], templateName: 'Light' },
          { ...devices[0], id: 'custom-provider', templateName: 'Custom provider' }
        ],
        environmentVariables: [{ name: 'temperature', value: 'off' }]
      },
      global: { plugins: [i18n] }
    })

    await wrapper.setProps({ activeSection: 'environment' })
    expect(wrapper.get('[data-testid="environment-pool"]').text()).toContain('temperature')
    await wrapper.get('article button').trigger('click')
    const valueSelect = wrapper.get('[data-testid="environment-value-temperature"]')
    expect(valueSelect.find('option[value="off"]').text()).toBe('off')
    expect(valueSelect.find('option[value="on"]').text()).toBe('on')
  })

  it('does not add a dead tab stop to a specification card', () => {
    const specification: Specification = { name: 'Safety requirement',
      id: 'spec-1',
      templateId: '1',
      templateLabel: 'Always',
      aConditions: [],
      ifConditions: [],
      thenConditions: []
    }
    const wrapper = mount(SystemInspector, {
      props: { activeSection: 'specs', devices, specifications: [specification] },
      global: { plugins: [i18n] }
    })

    expect(wrapper.get('[data-spec-id="spec-1"]').attributes('tabindex')).toBe('-1')
    expect(wrapper.get('[data-spec-id="spec-1"] button').attributes('aria-label')).toBeTruthy()
  })
})
