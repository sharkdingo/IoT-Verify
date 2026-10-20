import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ElMessageBox } from 'element-plus'

import DeviceDialog from '@/components/DeviceDialog.vue'
import InfoTooltip from '@/components/common/InfoTooltip.vue'
import { i18n } from '@/assets/i18n'
import type { DeviceManifest, DeviceTemplate } from '@/types/device'

const mountRuntimeEditor = () => {
  const manifest: DeviceManifest = { Name: 'Controller', Modes: [], APIs: [], InternalVariables: [{
    Name: 'threshold', IsInside: true, LowerBound: 0, UpperBound: 100,
    FalsifiableWhenCompromised: false, Trust: 'trusted', Privacy: 'public'
  }] }
  return mount(DeviceDialog, { attachTo: document.body, props: { visible: true, deviceName: manifest.Name,
    label: 'Controller', nodeId: 'controller', manifest,
    deviceTemplates: [{ name: manifest.Name, manifest }],
    nodes: [{ id: 'controller', templateName: manifest.Name, label: 'Controller', position: { x: 0, y: 0 },
      state: 'Working', width: 176, height: 128, variables: [{ name: 'threshold', value: '10' }] }] },
    global: { plugins: [i18n] } })
}
const setRuntimeValue = async (value: string) => {
  const input = document.querySelector<HTMLInputElement>('[data-testid="device-runtime-variable-threshold"]')!
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

describe('DeviceDialog runtime rejection ownership', () => {
  afterEach(() => { document.body.innerHTML = ''; localStorage.removeItem('iot_verify_token') })
  it('blocks fractional text inline, while allowing the template default', async () => {
    const wrapper = mountRuntimeEditor()
    await setRuntimeValue('1.5')
    expect(document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-save"]')!.disabled).toBe(true)
    expect(document.querySelector('[data-testid="device-runtime-blocked-reason"]')!.textContent).toMatch(/0.*100/)
    const save = document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-save"]')!
    const reason = document.querySelector('[data-testid="device-runtime-blocked-reason"]')!
    expect(save.closest('.device-runtime-header')).not.toBeNull()
    expect(reason.closest('.device-runtime-header')).toBe(save.closest('.device-runtime-header'))
    expect(save.getAttribute('aria-describedby')).toBe(reason.id)
    expect(wrapper.emitted('save-runtime')).toBeUndefined()
    await setRuntimeValue('')
    expect(document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-save"]')!.disabled).toBe(false)
    wrapper.unmount()
  })
  it('keeps a current server rejection with the draft and clears it when edited', async () => {
    const wrapper = mountRuntimeEditor()
    await setRuntimeValue('25')
    document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-save"]')!.click()
    await flushPromises()
    const complete = wrapper.emitted('save-runtime')![0][2] as Function
    expect(typeof complete).toBe('function')
    complete(false, { details: ['Rejected value'], fields: { 'variables[0].value': 'Allowed range 0..100' } })
    await flushPromises()
    expect(document.querySelector('[data-testid="device-runtime-save-failure"]')!.textContent).toContain('Allowed range 0..100')
    expect(document.querySelector('[data-testid="device-runtime-save-failure"] [data-testid="failure-next-step"]')).toBeNull()
    expect(document.querySelector<HTMLInputElement>('[data-testid="device-runtime-variable-threshold"]')!.value).toBe('25')
    await setRuntimeValue('26')
    complete(false, { details: ['Late old rejection'] })
    await flushPromises()
    expect(document.querySelector('[data-testid="device-runtime-save-failure"]')).toBeNull()
    wrapper.unmount()
  })
  it.each(['close', 'account', 'unmount'])('does not attach an old runtime rejection after %s', async action => {
    const wrapper = mountRuntimeEditor()
    await setRuntimeValue('25')
    document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-save"]')!.click()
    await flushPromises()
    const complete = wrapper.emitted('save-runtime')![0][2] as Function
    expect(typeof complete).toBe('function')
    if (action === 'close') { await wrapper.setProps({ visible: false }); await wrapper.setProps({ visible: true }); await setRuntimeValue('25') }
    if (action === 'account') localStorage.setItem('iot_verify_token', 'different-session')
    if (action === 'unmount') wrapper.unmount()
    complete(false, { details: ['Late old rejection'] })
    await flushPromises()
    expect(document.querySelector('[data-testid="device-runtime-save-failure"]')).toBeNull()
    if (action !== 'unmount') wrapper.unmount()
  })
})

describe('DeviceDialog template authority', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('does not present missing or stale device-type semantics as current details', async () => {
    const wrapper = mount(DeviceDialog, {
      attachTo: document.body,
      props: {
        visible: true,
        deviceName: 'Thermostat',
        label: 'Hall thermostat',
        nodeId: 'node-1',
        manifest: null,
        nodes: [{
          id: 'node-1',
          templateName: 'Thermostat',
          label: 'Hall thermostat',
          position: { x: 0, y: 0 },
          state: 'heat',
          width: 176,
          height: 128
        }],
        deviceTemplates: [],
        specs: []
      },
      global: { plugins: [i18n] }
    })

    expect(document.querySelector('[data-testid="device-template-details-unavailable"]')?.textContent)
      .toContain('Thermostat')
    expect(document.querySelector('[data-testid="device-dialog-states"]')).toBeNull()
    expect(document.body.textContent).not.toContain('heat')

    wrapper.unmount()
  })

  it('exposes device rename as a visible instance action', () => {
    const wrapper = mount(DeviceDialog, {
      attachTo: document.body,
      props: {
        visible: true,
        deviceName: 'Light',
        label: 'Hall light',
        nodeId: 'light-1',
        manifest: null,
        nodes: [],
        deviceTemplates: [],
        specs: []
      },
      global: { plugins: [i18n] }
    })

    const renameButton = document.querySelector<HTMLButtonElement>('[data-testid="device-rename"]')
    expect(renameButton).not.toBeNull()
    // The shared dialog layer owns responsive wrapping and target sizes; local utility assertions
    // previously passed while different dialogs still rendered different button heights.
    expect(document.querySelector('.iot-dialog')?.classList).toContain('iot-dialog--md')
    expect(document.querySelector('.iot-dialog__header')).not.toBeNull()
    expect(document.querySelector('.iot-dialog__body')).not.toBeNull()
    expect(document.querySelector('.iot-dialog__footer')).not.toBeNull()
    for (const testid of ['device-rename', 'device-delete', 'device-dialog-footer-close']) {
      expect(document.querySelector(`[data-testid="${testid}"]`)?.classList, testid)
        .toContain('iot-dialog-btn')
    }
    expect(document.querySelector('[data-testid="device-dialog-close"]')?.classList)
      .toContain('iot-dialog__close')
    renameButton?.click()
    expect(wrapper.emitted('rename')).toHaveLength(1)

    wrapper.unmount()
  })

  it('wraps long custom metadata instead of clipping it on narrow dialogs', () => {
    const longName = 'CustomDeviceNameWithoutAnyNaturalBreakPoint0123456789'
    const longLabel = 'LivingRoomInstanceNameWithoutAnyNaturalBreakPoint0123456789'
    const longDescription = 'DescriptionWithoutAnyNaturalBreakPoint0123456789'.repeat(3)
    const manifest: DeviceManifest = {
      Name: longName,
      Description: longDescription,
      Modes: [],
      WorkingStates: [],
      InternalVariables: [],
      APIs: []
    }
    const wrapper = mount(DeviceDialog, {
      attachTo: document.body,
      props: {
        visible: true,
        deviceName: longName,
        label: longLabel,
        nodeId: 'long-custom-device',
        manifest,
        nodes: [{
          id: 'long-custom-device',
          templateName: longName,
          label: longLabel,
          position: { x: 0, y: 0 },
          state: 'Working',
          width: 176,
          height: 128
        }],
        deviceTemplates: [{ name: longName, manifest, defaultTemplate: false }],
        specs: []
      },
      global: { plugins: [i18n] }
    })

    expect(document.querySelector('.device-basic-table')?.classList).toContain('table-fixed')
    const values = Array.from(document.querySelectorAll<HTMLElement>('.device-basic-value'))
    expect(values.find(cell => cell.textContent?.includes(longName))?.classList).toContain('break-words')
    expect(values.find(cell => cell.textContent?.includes(longLabel))?.classList).toContain('break-words')
    expect(values.find(cell => cell.textContent?.includes(longDescription))?.classList).toContain('break-words')
    wrapper.unmount()
  })

  it('uses reachable dedicated icons for doors and garage doors', async () => {
    const wrapper = mount(DeviceDialog, {
      attachTo: document.body,
      props: {
        visible: true,
        deviceName: 'Garage Door',
        label: 'Garage Door',
        nodeId: 'garage-1',
        manifest: null,
        nodes: [],
        deviceTemplates: [],
        specs: []
      },
      global: { plugins: [i18n] }
    })

    const headerIcon = () => document.querySelector(
      '.iot-dialog__icon .material-icons-round'
    )?.textContent?.trim()
    expect(headerIcon()).toBe('garage')

    await wrapper.setProps({ deviceName: 'Door', label: 'Front Door' })
    expect(headerIcon()).toBe('door_front_door')

    await wrapper.setProps({ deviceName: 'Door Sensor', label: 'Entry Sensor' })
    expect(headerIcon()).toBe('sensors')
    wrapper.unmount()
  })

  it('localizes bundled model tokens but preserves tokens from custom or unresolved templates', () => {
    i18n.global.locale.value = 'zh-CN'
    const manifest: DeviceManifest = {
      Name: 'Camera',
      Modes: ['SwitchState'],
      InitState: 'off',
      WorkingStates: [
        { Name: 'off', Trust: 'trusted', Privacy: 'public' },
        { Name: 'on', Trust: 'trusted', Privacy: 'public' }
      ],
      APIs: [{
        Name: 'take photo',
        StartState: 'on',
        EndState: 'on',
        Trigger: null,
        Signal: true
      }]
    }
    const node = {
      id: 'camera-1',
      templateName: 'Camera',
      label: 'Camera',
      position: { x: 0, y: 0 },
      state: 'retired-by-template-upgrade',
      width: 176,
      height: 128
    }
    const mountDialog = (deviceTemplates: DeviceTemplate[]) => mount(DeviceDialog, {
      attachTo: document.body,
      props: {
        visible: true,
        deviceName: 'Camera',
        label: 'Camera',
        nodeId: 'camera-1',
        manifest,
        nodes: [node],
        deviceTemplates,
        specs: []
      },
      global: { plugins: [i18n] }
    })

    const bundled = mountDialog([{ name: 'Camera', manifest, defaultTemplate: true }])
    expect(document.querySelector('[data-testid="device-dialog-apis"]')?.textContent).toContain('拍照')
    expect(document.querySelector('[data-testid="device-dialog-apis"]')?.textContent).toContain('开启')
    expect(document.querySelector('[data-testid="device-dialog-states"]')?.textContent).toContain('关闭')
    expect(document.body.textContent).toContain('开关状态')
    expect((document.querySelector('[data-testid="device-runtime-state"]') as HTMLSelectElement | null)?.value)
      .toBe('off')
    bundled.unmount()

    const custom = mountDialog([{ name: 'Camera', manifest, defaultTemplate: false }])
    expect(document.querySelector('[data-testid="device-dialog-apis"]')?.textContent).toContain('take photo')
    expect(document.querySelector('[data-testid="device-dialog-apis"]')?.textContent).toContain('on')
    expect(document.querySelector('[data-testid="device-dialog-states"]')?.textContent).toContain('off')
    expect(document.body.textContent).toContain('SwitchState')
    custom.unmount()

    const missingProvenance = mountDialog([{ name: 'Camera', manifest }])
    expect(document.querySelector('[data-testid="device-dialog-apis"]')?.textContent).toContain('take photo')
    expect(document.querySelector('[data-testid="device-dialog-states"]')?.textContent).toContain('off')
    expect(document.body.textContent).toContain('SwitchState')
    missingProvenance.unmount()

    const unresolved = mountDialog([])
    expect(document.querySelector('[data-testid="device-dialog-apis"]')?.textContent).toContain('take photo')
    unresolved.unmount()
  })

  it('names the effective template starting value when an override is cleared', () => {
    i18n.global.locale.value = 'en'
    const manifest: DeviceManifest = {
      Name: 'Defaulted Controller',
      Modes: [],
      WorkingStates: [],
      InternalVariables: [
        {
          Name: 'mode',
          IsInside: true,
          FalsifiableWhenCompromised: false,
          Trust: 'trusted',
          Privacy: 'public',
          Values: ['idle', 'active']
        },
        {
          Name: 'threshold',
          IsInside: true,
          FalsifiableWhenCompromised: false,
          LowerBound: 5,
          UpperBound: 20,
          Trust: 'trusted',
          Privacy: 'public'
        }
      ],
      APIs: []
    }
    const template: DeviceTemplate = {
      name: manifest.Name,
      manifest,
      defaultTemplate: false
    }
    const wrapper = mount(DeviceDialog, {
      attachTo: document.body,
      props: {
        visible: true,
        deviceName: template.name,
        label: 'Controller',
        nodeId: 'controller-defaults',
        manifest,
        nodes: [{
          id: 'controller-defaults',
          templateName: template.name,
          label: 'Controller',
          position: { x: 0, y: 0 },
          state: 'Working',
          width: 176,
          height: 128,
          variables: []
        }],
        deviceTemplates: [template],
        specs: []
      },
      global: { plugins: [i18n] }
    })

    const enumSelect = document.querySelector<HTMLSelectElement>(
      '[data-testid="device-runtime-variable-mode"]'
    )!
    const numericInput = document.querySelector<HTMLInputElement>(
      '[data-testid="device-runtime-variable-threshold"]'
    )!
    expect(enumSelect.querySelector('option[value=""]')?.textContent)
      .toBe('Use template default (idle)')
    expect(numericInput.placeholder).toBe('Use template default (5) / 5 - 20')
    expect(numericInput.value).toBe('5')
    wrapper.unmount()
  })

  it('merges disjoint refreshes and requires an explicit choice for conflicting runtime edits', async () => {
    const manifest: DeviceManifest = {
      Name: 'Custom Controller',
      Modes: ['CustomMode'],
      InitState: 'idle',
      WorkingStates: [
        { Name: 'idle', Trust: 'trusted', Privacy: 'public' },
        { Name: 'active', Trust: 'trusted', Privacy: 'public' }
      ],
      InternalVariables: [
        {
          Name: 'threshold',
          IsInside: true,
          FalsifiableWhenCompromised: false,
          LowerBound: 0,
          UpperBound: 100,
          Trust: 'trusted',
          Privacy: 'public'
        },
        {
          Name: 'limit',
          IsInside: true,
          FalsifiableWhenCompromised: false,
          LowerBound: 0,
          UpperBound: 100,
          Trust: 'trusted',
          Privacy: 'public'
        }
      ],
      APIs: []
    }
    const template: DeviceTemplate = {
      name: 'Custom Controller',
      manifest,
      defaultTemplate: false
    }
    const node = {
      id: 'controller-1',
      templateName: template.name,
      label: 'Controller',
      position: { x: 0, y: 0 },
      state: 'idle',
      width: 176,
      height: 128,
      variables: [
        { name: 'threshold', value: '10' },
        { name: 'limit', value: '40' }
      ]
    }
    const wrapper = mount(DeviceDialog, {
      attachTo: document.body,
      props: {
        visible: true,
        deviceName: template.name,
        label: node.label,
        nodeId: node.id,
        manifest,
        nodes: [node],
        deviceTemplates: [template],
        specs: []
      },
      global: { plugins: [i18n] }
    })

    const input = () => document.querySelector<HTMLInputElement>(
      '[data-testid="device-runtime-variable-threshold"]'
    )
    const limitInput = () => document.querySelector<HTMLInputElement>(
      '[data-testid="device-runtime-variable-limit"]'
    )
    expect(input()?.value).toBe('10')
    input()!.value = '25'
    input()!.dispatchEvent(new Event('input', { bubbles: true }))
    await wrapper.vm.$nextTick()

    await wrapper.setProps({ suspended: true })
    expect(document.querySelector('[data-testid="device-dialog"]')).toBeNull()
    await wrapper.setProps({ suspended: false })
    expect(input()?.value).toBe('25')

    const disjointRefresh = {
      ...node,
      variables: [
        { name: 'threshold', value: '10' },
        { name: 'limit', value: '50' }
      ]
    }
    await wrapper.setProps({ nodes: [disjointRefresh] })
    expect(input()?.value).toBe('25')
    expect(limitInput()?.value).toBe('50')
    expect(document.querySelector('[data-testid="device-runtime-conflict"]')).toBeNull()

    document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-save"]')!.click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('save-runtime')?.[0]?.[1]).toMatchObject({
      variables: [
        { name: 'threshold', value: '25' },
        { name: 'limit', value: '50' }
      ]
    })

    const conflictingRefresh = {
      ...disjointRefresh,
      variables: [
        { name: 'threshold', value: '15' },
        { name: 'limit', value: '50' }
      ]
    }
    await wrapper.setProps({ nodes: [conflictingRefresh] })
    expect(input()?.value).toBe('25')
    expect(document.querySelector('[data-testid="device-runtime-conflict"]')).not.toBeNull()
    expect(document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-save"]')?.disabled).toBe(true)
    expect(document.querySelector('[data-testid="device-runtime-save"]')?.classList).toContain('board-action-inline')
    expect(document.querySelector('[data-testid="device-runtime-adopt-latest"]')?.classList).toContain('min-h-11')
    expect(document.querySelector('[data-testid="device-runtime-keep-local"]')?.classList).toContain('min-h-11')

    document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-keep-local"]')!.click()
    await wrapper.vm.$nextTick()
    expect(input()?.value).toBe('25')
    expect(document.querySelector('[data-testid="device-runtime-conflict"]')).toBeNull()
    expect(document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-save"]')?.disabled).toBe(false)

    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true })
    expect(input()?.value).toBe('15')

    const latestNode = {
      ...conflictingRefresh,
      variables: [
        { name: 'threshold', value: '18' },
        { name: 'limit', value: '50' }
      ]
    }
    input()!.value = '30'
    input()!.dispatchEvent(new Event('input', { bubbles: true }))
    limitInput()!.value = '60'
    limitInput()!.dispatchEvent(new Event('input', { bubbles: true }))
    await wrapper.vm.$nextTick()
    await wrapper.setProps({ nodes: [latestNode] })
    expect(input()?.value).toBe('30')
    expect(limitInput()?.value).toBe('60')
    expect(document.querySelector('[data-testid="device-runtime-conflict"]')).not.toBeNull()

    document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-adopt-latest"]')!.click()
    await wrapper.vm.$nextTick()
    expect(input()?.value).toBe('18')
    expect(limitInput()?.value).toBe('60')
    expect(document.querySelector('[data-testid="device-runtime-conflict"]')).toBeNull()

    wrapper.unmount()
  })

  it('keeps state, trust, and privacy together when a snapshot races a local edit', async () => {
    const manifest: DeviceManifest = {
      Name: 'Stateful Controller',
      Modes: ['OperatingMode'],
      InitState: 'idle',
      WorkingStates: [
        { Name: 'idle', Trust: 'trusted', Privacy: 'public' },
        { Name: 'active', Trust: 'untrusted', Privacy: 'private' }
      ],
      APIs: []
    }
    const template: DeviceTemplate = {
      name: manifest.Name,
      manifest,
      defaultTemplate: false
    }
    const node = {
      id: 'stateful-1',
      templateName: template.name,
      label: 'Stateful controller',
      position: { x: 0, y: 0 },
      state: 'idle',
      currentStateTrust: 'trusted',
      currentStatePrivacy: 'public',
      width: 176,
      height: 128
    }
    const wrapper = mount(DeviceDialog, {
      attachTo: document.body,
      props: {
        visible: true,
        deviceName: template.name,
        label: node.label,
        nodeId: node.id,
        manifest,
        nodes: [node],
        deviceTemplates: [template],
        specs: []
      },
      global: { plugins: [i18n] }
    })

    const state = () => document.querySelector<HTMLSelectElement>('[data-testid="device-runtime-state"]')!
    const trust = () => document.querySelector<HTMLSelectElement>('[data-testid="device-runtime-state-trust"]')!
    const privacy = () => document.querySelector<HTMLSelectElement>('[data-testid="device-runtime-state-privacy"]')!

    trust().value = 'untrusted'
    trust().dispatchEvent(new Event('change', { bubbles: true }))
    await wrapper.vm.$nextTick()

    await wrapper.setProps({
      nodes: [{
        ...node,
        state: 'active',
        currentStateTrust: 'untrusted',
        currentStatePrivacy: 'private'
      }]
    })

    expect(state().value).toBe('idle')
    expect(trust().value).toBe('untrusted')
    expect(privacy().value).toBe('public')
    expect(document.querySelector('[data-testid="device-runtime-conflict"]')).not.toBeNull()
    expect(document.querySelector('[data-testid="device-runtime-conflict"]')?.textContent)
      .toContain(i18n.global.t('app.deviceRuntimeConflict', { count: 1 }))
    const adoptLatest = document.querySelector<HTMLButtonElement>(
      '[data-testid="device-runtime-adopt-latest"]'
    )!
    expect(adoptLatest.classList).toContain('device-runtime-adopt-latest')

    adoptLatest.click()
    await wrapper.vm.$nextTick()
    expect(state().value).toBe('active')
    expect(trust().value).toBe('untrusted')
    expect(privacy().value).toBe('private')
    expect(document.querySelector('[data-testid="device-runtime-conflict"]')).toBeNull()
    wrapper.unmount()
  })

  it('rebases the complete state context atomically when the device schema changes', async () => {
    const originalManifest: DeviceManifest = {
      Name: 'Schema Controller',
      Modes: ['OperatingMode'],
      InitState: 'idle',
      WorkingStates: [
        { Name: 'idle', Trust: 'trusted', Privacy: 'public' },
        { Name: 'active', Trust: 'untrusted', Privacy: 'private' }
      ],
      APIs: []
    }
    const revisedManifest: DeviceManifest = {
      ...originalManifest,
      WorkingStates: [
        ...originalManifest.WorkingStates!,
        { Name: 'standby', Trust: 'trusted', Privacy: 'private' }
      ]
    }
    const originalTemplate: DeviceTemplate = {
      name: originalManifest.Name,
      manifest: originalManifest,
      defaultTemplate: false
    }
    const revisedTemplate: DeviceTemplate = {
      ...originalTemplate,
      manifest: revisedManifest
    }
    const node = {
      id: 'schema-stateful-1',
      templateName: originalTemplate.name,
      label: 'Schema controller',
      position: { x: 0, y: 0 },
      state: 'idle',
      currentStateTrust: 'trusted',
      currentStatePrivacy: 'public',
      width: 176,
      height: 128
    }
    const wrapper = mount(DeviceDialog, {
      attachTo: document.body,
      props: {
        visible: true,
        deviceName: originalTemplate.name,
        label: node.label,
        nodeId: node.id,
        manifest: originalManifest,
        nodes: [node],
        deviceTemplates: [originalTemplate],
        specs: []
      },
      global: { plugins: [i18n] }
    })

    const state = () => document.querySelector<HTMLSelectElement>('[data-testid="device-runtime-state"]')!
    const trust = () => document.querySelector<HTMLSelectElement>('[data-testid="device-runtime-state-trust"]')!
    const privacy = () => document.querySelector<HTMLSelectElement>('[data-testid="device-runtime-state-privacy"]')!
    privacy().value = 'private'
    privacy().dispatchEvent(new Event('change', { bubbles: true }))
    await wrapper.vm.$nextTick()

    await wrapper.setProps({
      manifest: revisedManifest,
      deviceTemplates: [revisedTemplate],
      nodes: [{
        ...node,
        state: 'active',
        currentStateTrust: 'untrusted',
        currentStatePrivacy: 'private'
      }]
    })

    expect(state().value).toBe('idle')
    expect(trust().value).toBe('trusted')
    expect(privacy().value).toBe('private')
    expect(document.querySelector('[data-testid="device-runtime-schema-conflict"]')).not.toBeNull()

    document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-adopt-latest"]')!.click()
    await wrapper.vm.$nextTick()
    expect(state().value).toBe('active')
    expect(trust().value).toBe('untrusted')
    expect(privacy().value).toBe('private')
    expect(document.querySelector('[data-testid="device-runtime-schema-conflict"]')).toBeNull()
    wrapper.unmount()
  })

  it('does not overwrite an edit that returns to the old value while a save is in flight', async () => {
    const manifest: DeviceManifest = {
      Name: 'Custom Controller',
      Modes: [],
      WorkingStates: [],
      InternalVariables: [{
        Name: 'threshold',
        IsInside: true,
        FalsifiableWhenCompromised: false,
        LowerBound: 0,
        UpperBound: 100,
        Trust: 'trusted',
        Privacy: 'public'
      }],
      APIs: []
    }
    const template: DeviceTemplate = {
      name: manifest.Name,
      manifest,
      defaultTemplate: false
    }
    const node = {
      id: 'controller-1',
      templateName: template.name,
      label: 'Controller',
      position: { x: 0, y: 0 },
      state: 'idle',
      width: 176,
      height: 128,
      variables: [{ name: 'threshold', value: '10' }]
    }
    let markSaving: (() => void) | null = null
    const wrapper = mount(DeviceDialog, {
      attachTo: document.body,
      props: {
        visible: true,
        runtimeSaving: false,
        deviceName: template.name,
        label: node.label,
        nodeId: node.id,
        manifest,
        nodes: [node],
        deviceTemplates: [template],
        specs: [],
        onSaveRuntime: () => markSaving?.()
      },
      global: { plugins: [i18n] }
    })
    markSaving = () => {
      void wrapper.setProps({ runtimeSaving: true })
    }
    const input = () => document.querySelector<HTMLInputElement>(
      '[data-testid="device-runtime-variable-threshold"]'
    )!
    const setInput = async (value: string) => {
      input().value = value
      input().dispatchEvent(new Event('input', { bubbles: true }))
      await wrapper.vm.$nextTick()
    }

    await setInput('25')
    document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-save"]')!.click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('save-runtime')?.[0]?.[1]).toMatchObject({
      variables: [{ name: 'threshold', value: '25' }]
    })

    await setInput('10')
    await wrapper.setProps({
      nodes: [{ ...node, variables: [{ name: 'threshold', value: '25' }] }],
      runtimeSaving: false
    })

    expect(input().value).toBe('10')

    await wrapper.setProps({
      nodes: [{ ...node, variables: [{ name: 'threshold', value: '25' }] }]
    })
    expect(input().value).toBe('10')
    wrapper.unmount()
  })

  it('treats canonical trim and security-label normalization as its own save acknowledgement', async () => {
    const manifest: DeviceManifest = {
      Name: 'Canonical Controller',
      Modes: [],
      WorkingStates: [],
      InternalVariables: [{
        Name: 'threshold',
        IsInside: true,
        FalsifiableWhenCompromised: false,
        LowerBound: 0,
        UpperBound: 100,
        Trust: 'trusted',
        Privacy: 'public'
      }],
      APIs: []
    }
    const template: DeviceTemplate = {
      name: manifest.Name,
      manifest,
      defaultTemplate: false
    }
    const node = {
      id: 'canonical-controller-1',
      templateName: template.name,
      label: 'Canonical controller',
      position: { x: 0, y: 0 },
      state: 'idle',
      width: 176,
      height: 128,
      variables: [{ name: 'threshold', value: '10', trust: 'trusted' }]
    }
    const wrapper = mount(DeviceDialog, {
      attachTo: document.body,
      props: {
        visible: true,
        runtimeSaving: false,
        deviceName: template.name,
        label: node.label,
        nodeId: node.id,
        manifest,
        nodes: [node],
        deviceTemplates: [template],
        specs: [],
        onSaveRuntime: () => void wrapper.setProps({ runtimeSaving: true })
      },
      global: { plugins: [i18n] }
    })

    const valueInput = () => document.querySelector<HTMLInputElement>(
      '[data-testid="device-runtime-variable-threshold"]'
    )!
    const trustSelect = () => document.querySelector<HTMLSelectElement>(
      '[data-testid="device-runtime-variable-trust-threshold"]'
    )!
    const setInput = async (value: string) => {
      valueInput().value = value
      valueInput().dispatchEvent(new Event('input', { bubbles: true }))
      await wrapper.vm.$nextTick()
    }

    await setInput(' 25 ')
    trustSelect().value = 'untrusted'
    trustSelect().dispatchEvent(new Event('change', { bubbles: true }))
    await wrapper.vm.$nextTick()

    document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-save"]')!.click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('save-runtime')?.[0]?.[1]).toMatchObject({
      variables: [{ name: 'threshold', value: '25', trust: 'untrusted' }]
    })

    // The backend trims values and lower-cases security labels in its response.
    await wrapper.setProps({
      nodes: [{
        ...node,
        variables: [{ name: 'threshold', value: '25', trust: 'UNTRUSTED' }]
      }]
    })
    expect(document.querySelector('[data-testid="device-runtime-conflict"]')).toBeNull()
    expect(valueInput().value).toBe('25')
    expect(trustSelect().value).toBe('untrusted')

    // A local edit made after Save remains local when the same acknowledgement arrives.
    await setInput(' 30 ')
    await wrapper.setProps({
      nodes: [{
        ...node,
        variables: [{ name: 'threshold', value: '25', trust: 'untrusted' }]
      }]
    })
    expect(valueInput().value).toBe(' 30 ')
    expect(document.querySelector('[data-testid="device-runtime-conflict"]')).toBeNull()

    await wrapper.setProps({ runtimeSaving: false })
    wrapper.unmount()
  })

  it('adopts template defaults when a saved runtime override is cleared', async () => {
    const manifest: DeviceManifest = {
      Name: 'Defaulted Controller',
      Modes: [],
      WorkingStates: [],
      InternalVariables: [{
        Name: 'threshold',
        IsInside: true,
        FalsifiableWhenCompromised: false,
        LowerBound: 0,
        UpperBound: 100,
        Trust: 'trusted',
        Privacy: 'public'
      }],
      APIs: []
    }
    const template: DeviceTemplate = {
      name: manifest.Name,
      manifest,
      defaultTemplate: false
    }
    const node = {
      id: 'defaulted-controller-1',
      templateName: template.name,
      label: 'Defaulted controller',
      position: { x: 0, y: 0 },
      state: 'idle',
      width: 176,
      height: 128,
      variables: [{ name: 'threshold', value: '25', trust: 'untrusted' }]
    }
    let markSaving: (() => void) | null = null
    const wrapper = mount(DeviceDialog, {
      attachTo: document.body,
      props: {
        visible: true,
        runtimeSaving: false,
        deviceName: template.name,
        label: node.label,
        nodeId: node.id,
        manifest,
        nodes: [node],
        deviceTemplates: [template],
        specs: [],
        onSaveRuntime: () => markSaving?.()
      },
      global: { plugins: [i18n] }
    })
    markSaving = () => {
      void wrapper.setProps({ runtimeSaving: true })
    }

    const valueInput = () => document.querySelector<HTMLInputElement>(
      '[data-testid="device-runtime-variable-threshold"]'
    )!
    const trustSelect = () => document.querySelector<HTMLSelectElement>(
      '[data-testid="device-runtime-variable-trust-threshold"]'
    )!
    valueInput().value = ''
    valueInput().dispatchEvent(new Event('input', { bubbles: true }))
    await wrapper.vm.$nextTick()

    document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-save"]')!.click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('save-runtime')?.[0]?.[1]).toMatchObject({
      variables: []
    })

    await wrapper.setProps({ nodes: [{ ...node, variables: [] }] })
    expect(document.querySelector('[data-testid="device-runtime-conflict"]')).toBeNull()
    expect(valueInput().value).toBe('0')
    expect(trustSelect().value).toBe('')

    await wrapper.setProps({ runtimeSaving: false })
    wrapper.unmount()
  })

  it('adopts a new runtime schema without prompting when the draft was not edited', async () => {
    const manifest = (upperBound: number): DeviceManifest => ({
      Name: 'Controller',
      Modes: [],
      WorkingStates: [],
      InternalVariables: [{
        Name: 'threshold',
        IsInside: true,
        FalsifiableWhenCompromised: false,
        LowerBound: 0,
        UpperBound: upperBound,
        Trust: 'trusted',
        Privacy: 'public'
      }],
      APIs: []
    })
    const originalManifest = manifest(100)
    const revisedManifest = manifest(50)
    const node = {
      id: 'controller-1',
      templateName: 'Controller',
      label: 'Controller',
      position: { x: 0, y: 0 },
      state: 'Working',
      width: 176,
      height: 128,
      variables: [{ name: 'threshold', value: '10' }]
    }
    const wrapper = mount(DeviceDialog, {
      attachTo: document.body,
      props: {
        visible: true,
        deviceName: 'Controller',
        label: node.label,
        nodeId: node.id,
        manifest: originalManifest,
        nodes: [node],
        deviceTemplates: [{
          name: 'Controller',
          manifest: originalManifest,
          defaultTemplate: false
        }],
        specs: []
      },
      global: { plugins: [i18n] }
    })

    await wrapper.setProps({
      manifest: revisedManifest,
      nodes: [{ ...node, variables: [{ name: 'threshold', value: '7' }] }],
      deviceTemplates: [{
        name: 'Controller',
        manifest: revisedManifest,
        defaultTemplate: false
      }]
    })

    expect(document.querySelector<HTMLInputElement>(
      '[data-testid="device-runtime-variable-threshold"]'
    )?.value).toBe('7')
    expect(document.querySelector('[data-testid="device-runtime-schema-conflict"]')).toBeNull()
    expect(document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-save"]')?.disabled)
      .toBe(false)
    wrapper.unmount()
  })

  it('preserves compatible edits and requires a choice when a same-id device schema changes', async () => {
    const variable = (name: string, upperBound = 100) => ({
      Name: name,
      IsInside: true,
      FalsifiableWhenCompromised: false,
      LowerBound: 0,
      UpperBound: upperBound,
      Trust: 'trusted',
      Privacy: 'public'
    })
    const originalManifest: DeviceManifest = {
      Name: 'Original Controller',
      Modes: [],
      WorkingStates: [],
      InternalVariables: [variable('shared'), variable('alpha')],
      APIs: []
    }
    const replacementManifest: DeviceManifest = {
      Name: 'Replacement Controller',
      Modes: [],
      WorkingStates: [],
      InternalVariables: [variable('shared', 30), variable('beta')],
      APIs: []
    }
    const originalTemplate: DeviceTemplate = {
      name: originalManifest.Name,
      manifest: originalManifest,
      defaultTemplate: false
    }
    const replacementTemplate: DeviceTemplate = {
      name: replacementManifest.Name,
      manifest: replacementManifest,
      defaultTemplate: false
    }
    const originalNode = {
      id: 'controller-1',
      templateName: originalTemplate.name,
      label: 'Controller',
      position: { x: 0, y: 0 },
      state: 'Working',
      width: 176,
      height: 128,
      variables: [
        { name: 'shared', value: '10' },
        { name: 'alpha', value: '15' }
      ]
    }
    const wrapper = mount(DeviceDialog, {
      attachTo: document.body,
      props: {
        visible: true,
        deviceName: originalTemplate.name,
        label: originalNode.label,
        nodeId: originalNode.id,
        manifest: originalManifest,
        nodes: [originalNode],
        deviceTemplates: [originalTemplate],
        specs: []
      },
      global: { plugins: [i18n] }
    })

    const sharedInput = () => document.querySelector<HTMLInputElement>(
      '[data-testid="device-runtime-variable-shared"]'
    )!
    const originalInput = document.querySelector<HTMLInputElement>(
      '[data-testid="device-runtime-variable-alpha"]'
    )!
    sharedInput().value = '25'
    sharedInput().dispatchEvent(new Event('input', { bubbles: true }))
    originalInput.value = '35'
    originalInput.dispatchEvent(new Event('input', { bubbles: true }))
    await wrapper.vm.$nextTick()

    await wrapper.setProps({
      deviceName: replacementTemplate.name,
      manifest: replacementManifest,
      deviceTemplates: [replacementTemplate],
      nodes: [{
        ...originalNode,
        templateName: replacementTemplate.name,
        variables: [
          { name: 'shared', value: '7' },
          { name: 'beta', value: '9' }
        ]
      }]
    })

    expect(document.querySelector('[data-testid="device-runtime-variable-alpha"]')).toBeNull()
    expect(sharedInput().value).toBe('25')
    expect(document.querySelector<HTMLInputElement>(
      '[data-testid="device-runtime-variable-beta"]'
    )?.value).toBe('9')
    expect(document.querySelector('[data-testid="device-runtime-schema-conflict"]')?.textContent)
      .toContain(i18n.global.t('app.deviceRuntimeSchemaConflict'))
    expect(document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-save"]')?.disabled)
      .toBe(true)
    expect(document.querySelector('[data-testid="device-runtime-keep-local"]')?.textContent)
      .toContain(i18n.global.t('app.deviceRuntimeContinueCompatible'))

    document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-keep-local"]')!.click()
    await wrapper.vm.$nextTick()
    expect(document.querySelector('[data-testid="device-runtime-schema-conflict"]')).toBeNull()
    expect(sharedInput().value).toBe('25')
    document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-save"]')!.click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('save-runtime')?.[0]?.[1]).toMatchObject({
      variables: [
        { name: 'shared', value: '25' },
        { name: 'beta', value: '9' }
      ]
    })

    sharedInput().value = '20'
    sharedInput().dispatchEvent(new Event('input', { bubbles: true }))
    await wrapper.vm.$nextTick()
    const revisedManifest: DeviceManifest = {
      ...replacementManifest,
      InternalVariables: [variable('shared', 40), variable('beta')]
    }
    const revisedTemplate: DeviceTemplate = {
      ...replacementTemplate,
      manifest: revisedManifest
    }
    await wrapper.setProps({
      manifest: revisedManifest,
      deviceTemplates: [revisedTemplate],
      nodes: [{
        ...originalNode,
        templateName: revisedTemplate.name,
        variables: [
          { name: 'shared', value: '11' },
          { name: 'beta', value: '12' }
        ]
      }]
    })
    expect(sharedInput().value).toBe('20')
    expect(document.querySelector('[data-testid="device-runtime-schema-conflict"]')).not.toBeNull()

    document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-adopt-latest"]')!.click()
    await wrapper.vm.$nextTick()
    expect(sharedInput().value).toBe('11')
    expect(document.querySelector<HTMLInputElement>(
      '[data-testid="device-runtime-variable-beta"]'
    )?.value).toBe('12')
    expect(document.querySelector('[data-testid="device-runtime-schema-conflict"]')).toBeNull()
    wrapper.unmount()
  })

  it('disables the delete action while its impact preview is loading', async () => {
    const wrapper = mount(DeviceDialog, {
      attachTo: document.body,
      props: {
        visible: true,
        deleteLoading: true,
        deviceName: 'Light',
        label: 'Hall light',
        nodeId: 'light-1',
        manifest: null,
        nodes: [],
        deviceTemplates: [],
        specs: []
      },
      global: { plugins: [i18n] }
    })

    const deleteButton = document.querySelector<HTMLButtonElement>('[data-testid="device-delete"]')!
    expect(deleteButton.disabled).toBe(true)
    expect(deleteButton.getAttribute('aria-busy')).toBe('true')
    expect(deleteButton.textContent).toContain(i18n.global.t('app.loading'))
    deleteButton.click()
    expect(wrapper.emitted('delete')).toBeUndefined()

    await wrapper.setProps({ deleteLoading: false })
    deleteButton.click()
    expect(wrapper.emitted('delete')).toHaveLength(1)
    wrapper.unmount()
  })

  const mountRuntimeCloseGuard = (runtimeSaving = false) => {
    const manifest: DeviceManifest = {
      Name: 'Draft Controller',
      Modes: [],
      WorkingStates: [],
      InternalVariables: [{
        Name: 'threshold',
        IsInside: true,
        FalsifiableWhenCompromised: false,
        LowerBound: 0,
        UpperBound: 100,
        Trust: 'trusted',
        Privacy: 'public'
      }],
      APIs: []
    }
    const template: DeviceTemplate = {
      name: manifest.Name,
      manifest,
      defaultTemplate: false
    }
    const node = {
      id: 'draft-controller-1',
      templateName: template.name,
      label: 'Draft controller',
      position: { x: 0, y: 0 },
      state: 'Working',
      width: 176,
      height: 128,
      variables: [{ name: 'threshold', value: '10' }]
    }
    const wrapper = mount(DeviceDialog, {
      attachTo: document.body,
      props: {
        visible: true,
        runtimeSaving,
        deviceName: template.name,
        label: node.label,
        nodeId: node.id,
        manifest,
        nodes: [node],
        deviceTemplates: [template],
        specs: []
      },
      global: { plugins: [i18n] }
    })
    const input = () => document.querySelector<HTMLInputElement>(
      '[data-testid="device-runtime-variable-threshold"]'
    )!
    const edit = async (value: string) => {
      input().value = value
      input().dispatchEvent(new Event('input', { bubbles: true }))
      await wrapper.vm.$nextTick()
    }
    return { wrapper, node, edit }
  }

  it('requires confirmation before discarding a runtime draft and keeps it after cancellation', async () => {
    const { wrapper, edit } = mountRuntimeCloseGuard()
    await edit('25')
    const confirm = vi.spyOn(ElMessageBox, 'confirm')
      .mockRejectedValueOnce('cancel')
      .mockResolvedValue('confirm' as never)

    document.querySelector<HTMLButtonElement>('[data-testid="device-dialog-close"]')!.click()
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(
      i18n.global.t('app.deviceRuntimeDiscardMessage'),
      i18n.global.t('app.deviceRuntimeDiscardTitle'),
      expect.objectContaining({
        confirmButtonText: i18n.global.t('app.discardChanges'),
        cancelButtonText: i18n.global.t('app.cancel')
      })
    )
    expect(wrapper.emitted('update:visible')).toBeUndefined()
    expect(document.querySelector<HTMLInputElement>(
      '[data-testid="device-runtime-variable-threshold"]'
    )?.value).toBe('25')

    document.querySelector<HTMLButtonElement>('[data-testid="device-dialog-footer-close"]')!.click()
    await flushPromises()
    expect(wrapper.emitted('update:visible')).toEqual([[false]])
    wrapper.unmount()
  })

  it.each([
    ['backdrop click', (overlay: HTMLElement) => overlay.click()],
    ['Escape', (overlay: HTMLElement) => overlay.dispatchEvent(new KeyboardEvent(
      'keydown', { key: 'Escape', bubbles: true }
    ))]
  ])('guards %s with the same runtime-draft confirmation', async (_label, trigger) => {
    const { wrapper, edit } = mountRuntimeCloseGuard()
    await edit('25')
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    trigger(document.querySelector<HTMLElement>('.iot-dialog-overlay')!)
    await flushPromises()

    expect(confirm).toHaveBeenCalledOnce()
    expect(wrapper.emitted('update:visible')).toEqual([[false]])
    wrapper.unmount()
  })

  it('uses the acknowledged runtime as the new baseline and blocks leaving while saving', async () => {
    const { wrapper, node, edit } = mountRuntimeCloseGuard()
    const exposed = wrapper.vm as unknown as {
      prepareClose: () => Promise<boolean>
    }
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')

    await edit('25')
    document.querySelector<HTMLButtonElement>('[data-testid="device-runtime-save"]')!.click()
    await wrapper.setProps({ runtimeSaving: true })

    expect(await exposed.prepareClose()).toBe(false)
    expect(confirm).not.toHaveBeenCalled()
    expect(document.querySelector<HTMLButtonElement>('[data-testid="device-dialog-close"]')?.disabled)
      .toBe(true)
    expect(document.querySelector<HTMLButtonElement>('[data-testid="device-rename"]')?.disabled)
      .toBe(true)
    const deleteButton = document.querySelector<HTMLButtonElement>('[data-testid="device-delete"]')!
    expect(deleteButton.disabled).toBe(true)
    deleteButton.click()
    expect(wrapper.emitted('delete')).toBeUndefined()

    await wrapper.setProps({
      nodes: [{ ...node, variables: [{ name: 'threshold', value: '25' }] }],
      runtimeSaving: false
    })
    expect(await exposed.prepareClose()).toBe(true)
    expect(confirm).not.toHaveBeenCalled()

    await edit('30')
    expect(await exposed.prepareClose()).toBe(false)
    expect(confirm).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  /**
   * A transition is the one part of the state machine no rule drives, and nothing rendered them: a
   * counterexample where a camera left `taking photo` by itself had no explanation in the product,
   * while `FixResultDialog` already told the reader the violation "may be caused by device
   * transitions". Ten bundled templates declare transitions.
   */
  const mountWithManifest = (
    manifest: DeviceManifest,
    name: string,
    state = 'idle',
    specs: unknown[] = []
  ) => mount(DeviceDialog, {
    attachTo: document.body,
    props: {
      visible: true,
      deviceName: name,
      label: `${name} 1`,
      nodeId: 'transition-node',
      manifest,
      nodes: [{
        id: 'transition-node',
        templateName: name,
        label: `${name} 1`,
        position: { x: 0, y: 0 },
        state,
        width: 176,
        height: 128
      }],
      deviceTemplates: [{ name, manifest, defaultTemplate: false }],
      specs
    },
    global: { plugins: [i18n] }
  } as never)

  it.each(['en', 'zh-CN'] as const)('shows only authored metadata and no invented action trigger in %s', async locale => {
    i18n.global.locale.value = locale
    const manifest: DeviceManifest = {
      Name: 'Switch', Description: ' ', Modes: ['SwitchState'], InitState: 'off',
      WorkingStates: [{ Name: 'off', Description: ' ', Trust: 'trusted', Privacy: 'public' },
        { Name: 'on', Description: '', Trust: 'trusted', Privacy: 'public' }],
      APIs: [{ Name: 'on', StartState: '', EndState: 'on', Signal: false, Trigger: null }],
      Contents: [{ Name: 'sample', Description: ' ', Privacy: 'public' }]
    }
    const wrapper = mountWithManifest(manifest, manifest.Name, 'off')
    try {
      const descriptionLabels = () => Array.from(document.querySelectorAll('th, .device-basic-table td'))
        .filter(cell => cell.textContent?.trim() === i18n.global.t('app.description'))
      expect(descriptionLabels()).toHaveLength(0)
      expect(document.querySelector('.device-api-card')!.textContent).not.toContain(i18n.global.t('app.trigger'))
      const described: DeviceManifest = { ...manifest, Description: 'Current author note',
        WorkingStates: [{ ...manifest.WorkingStates![0], Description: 'Idle state' }, manifest.WorkingStates![1]],
        Contents: [{ ...manifest.Contents![0], Description: 'Selected content' }] }
      await wrapper.setProps({ manifest: described })
      expect(descriptionLabels()).toHaveLength(3)
      expect(document.querySelector('.device-basic-table')!.textContent).toContain('Current author note')
      expect(document.querySelector('[data-testid="device-dialog-content-sample"]')!.textContent).toContain('Selected content')
      await wrapper.setProps({ manifest })
      expect(descriptionLabels()).toHaveLength(0)
      expect(document.body.textContent).not.toContain('Current author note')
      expect(document.body.textContent).not.toContain('Idle state')
      expect(document.querySelector('[data-testid="device-dialog-content-sample"]')!.querySelectorAll('td')).toHaveLength(2)
    } finally { wrapper.unmount(); i18n.global.locale.value = 'en' }
  })

  it('lists each device-declared transition with its start, end and trigger', () => {
    // The `Door RFID` shape: two transitions falling back to one shared state.
    const manifest: DeviceManifest = {
      Name: 'Door RFID',
      Modes: ['ScanState'],
      InitState: 'idle',
      WorkingStates: [
        { Name: 'idle', Trust: 'trusted', Privacy: 'public' },
        { Name: 'authorized', Trust: 'trusted', Privacy: 'private' }
      ],
      InternalVariables: [],
      APIs: [],
      Transitions: [
        {
          Name: 'reset from authorized',
          StartState: 'authorized',
          EndState: 'idle',
          Trigger: { Attribute: 'ScanState', Relation: '=', Value: 'authorized' }
        }
      ]
    } as never
    const wrapper = mountWithManifest(manifest, 'Door RFID')

    const row = document.querySelector('[data-testid="device-dialog-transition-reset from authorized"]')
    expect(row).not.toBeNull()
    const cells = Array.from(row!.querySelectorAll('td')).map(cell => cell.textContent?.trim())
    expect(cells[1]).toBe('authorized')
    expect(cells[2]).toBe('idle')
    expect(cells[3]).toContain('ScanState')
    // A trigger renders its relation as words, the same way the APIs table does.
    expect(cells[3]).toBe('ScanState Equals authorized')
    wrapper.unmount()
  })

  /**
   * `EndState` is optional, and its absence is NOT an empty state: the transition still fires and its
   * assignment still applies (`device-template-schema.json` note 10). Rendering a blank would read as
   * "moves to no state". `Clock.reset` is the bundled case, and it is also modeless — so this section
   * cannot live behind the runtime panel's mode gate.
   */
  it('shows an assignment-only transition as no state change rather than a blank target', () => {
    const manifest: DeviceManifest = {
      Name: 'Clock',
      Modes: [],
      WorkingStates: [],
      InternalVariables: [
        { Name: 'time', IsInside: false, Reads: true, FalsifiableWhenCompromised: true, Trust: 'trusted', Privacy: 'public', LowerBound: 0, UpperBound: 23, NaturalChangeRate: '1' }
      ],
      APIs: [],
      Transitions: [
        {
          Name: 'reset',
          Trigger: { Attribute: 'time', Relation: '=', Value: '23' },
          Assignments: [{ Attribute: 'time', Value: '0' }]
        }
      ]
    } as never
    const wrapper = mountWithManifest(manifest, 'Clock', 'Working')

    const row = document.querySelector('[data-testid="device-dialog-transition-reset"]')
    expect(row).not.toBeNull()
    const cells = Array.from(row!.querySelectorAll('td')).map(cell => cell.textContent?.trim())
    expect(cells[2]).toBe('No state change')
    expect(cells[4]).toBe('time := 0')
    wrapper.unmount()
  })

  // Section headers share a layout; explanations are reserved for non-obvious device semantics.
  it('keeps section headers consistent without restating basic fields', () => {
    const manifest: DeviceManifest = {
      Name: 'Door RFID',
      Modes: ['ScanState'],
      InitState: 'idle',
      WorkingStates: [{ Name: 'idle', Trust: 'trusted', Privacy: 'public' }],
      InternalVariables: [
        { Name: 'RFID', IsInside: true, FalsifiableWhenCompromised: true, Trust: 'trusted', Privacy: 'private', Values: ['none', 'authorized'] }
      ],
      APIs: [{ Name: 'scan', StartState: '', Signal: true }],
      Contents: [{ Name: 'badgePhoto', Privacy: 'private' }],
      Transitions: [
        {
          Name: 'reset',
          StartState: 'idle',
          EndState: 'idle',
          Trigger: { Attribute: 'ScanState', Relation: '=', Value: 'idle' }
        }
      ]
    } as never
    // One specification referencing this device, so the specs section renders too.
    const wrapper = mountWithManifest(manifest, 'Door RFID', 'idle', [{
      id: 'spec-1',
      templateId: '3',
      aConditions: [{ deviceId: 'transition-node', deviceLabel: 'Door RFID 1', targetType: 'state', key: 'ScanState', relation: '=', value: 'idle' }],
      ifConditions: [],
      thenConditions: [],
      devices: [{ deviceId: 'transition-node', deviceLabel: 'Door RFID 1' }]
    }])

    for (const section of ['basic', 'variables', 'states', 'transitions', 'apis', 'contents', 'specs']) {
      const host = document.querySelector(`[data-testid="device-dialog-${section}"]`)
      expect(host, `section ${section} is missing`).not.toBeNull()
      // Anchor on the accent bar: it identifies the header row unambiguously, where a class query
      // for `.flex.items-center` also matched inner rows and passed for the wrong element.
      const bar = host!.querySelector('.board-section-marker')
      expect(bar, `section ${section} accent bar`).not.toBeNull()
      const header = bar!.parentElement!
      expect(header.className, `section ${section} header spacing`).toContain('mb-4')
      expect(header.className, `section ${section} header alignment`).toContain('items-start')
      expect(header!.querySelector('h2'), `section ${section} title`).not.toBeNull()
      const hint = header!.querySelector('h2 + p')
      expect(hint).toBeNull()
      if (section === 'basic') {
        expect(header.querySelector('.iot-info-tooltip-trigger')).toBeNull()
      } else {
        const help = Array.from(wrapper.findAllComponents(InfoTooltip))
          .find(component => header.contains(component.element))!
        expect(help, `section ${section} semantics`).toBeTruthy()
        expect(help.props('text')).toBe(i18n.global.t(`app.device${{
          variables: 'Variables', states: 'States', transitions: 'Transitions',
          apis: 'Apis', contents: 'Contents', specs: 'Specs'
        }[section]}Hint`))
        expect(help.props('label')).toBe(i18n.global.t('app.showHelpFor', { topic: header.querySelector('h2')!.textContent }))
      }
    }
    wrapper.unmount()
  })

  /**
   * `Contents` was the second whole manifest array with no surface anywhere: `RuleBuilder` offers
   * these as a rule's `contentDevice`/`content` and the model propagates each one's `privacy_<name>` to
   * the command target, so the sensitivity a rule inherits was unreadable until a run. Only two bundled
   * templates declare any, which is how it stayed invisible.
   *
   * Also pins one privacy vocabulary across the dialog. The tables used to disagree — each left the
   * OPPOSITE value with an empty class, so an unstyled chip meant "public" in one table and "private" in
   * the next. The assertion is that every private chip carries the SAME class, not that it carries a
   * particular one: hardcoding the role would cement it here and let it drift from the rest of the
   * product, which is how `warning` (a hazard) briefly displaced `info` (a classification).
   */
  it('lists declared contents and marks private the same way everywhere', () => {
    const manifest: DeviceManifest = {
      Name: 'Mobile Phone',
      Modes: ['State'],
      InitState: 'on',
      WorkingStates: [
        { Name: 'on', Trust: 'trusted', Privacy: 'public' },
        { Name: 'taking photo', Trust: 'trusted', Privacy: 'private' }
      ],
      InternalVariables: [
        { Name: 'steps', IsInside: true, FalsifiableWhenCompromised: true, Trust: 'trusted', Privacy: 'private', LowerBound: 0, UpperBound: 100 }
      ],
      APIs: [],
      Contents: [{ Name: 'photo', Description: '', Privacy: 'private' }]
    } as never
    const wrapper = mountWithManifest(manifest, 'Mobile Phone', 'on')

    const row = document.querySelector('[data-testid="device-dialog-content-photo"]')
    expect(row).not.toBeNull()
    const cells = Array.from(row!.querySelectorAll('td'))
    expect(cells[0].textContent?.trim()).toBe('photo')
    const contentChip = cells.at(-1)!.querySelector('span')
    expect(contentChip?.textContent?.trim()).toBe('Private')

    // Exactly three: the variable, the state and the content. A floor (`>= 3`) would still pass if one
    // stopped rendering, because a chip that is not emitted is invisible to the filter that finds them.
    const privateChips = Array.from(document.querySelectorAll('[data-testid="device-dialog"] span'))
      .filter(node => node.textContent?.trim() === 'Private')
    expect(privateChips.length, 'a private label stopped rendering').toBe(3)

    // Same value, same appearance — asserted as sameness rather than against a fixed role, so the guard
    // survives a deliberate role change and still catches one table drifting from another.
    const classes = new Set(privateChips.map(chip => chip.className.trim()))
    expect(classes.size, `private chips disagree: ${[...classes].join(' | ')}`).toBe(1)
    expect([...classes][0], 'a private label rendered with no chip styling').toMatch(/board-chip-/)
    wrapper.unmount()
  })

  /**
   * `Dynamics` is what a state DOES, as opposed to how it is labelled, and it was rendered nowhere for a
   * device-local target. The Environment Pool groups these by variable, so it can only ever show shared
   * ones — and 9 of the 15 bundled templates that declare Dynamics target a local variable exclusively
   * (Oven's `ovenJobState`, Washer's `washerJobState`, …), so for those the effect had no surface at all.
   * That is why the states hint must not send the reader to the pool for this.
   */
  it('shows what each state does, including a device-local target', () => {
    const manifest: DeviceManifest = {
      // Mode names, state tuples and the Dynamics target all match `deviceTemplate/Oven.json`, so the
      // fixture exercises the real multi-mode shape rather than an invented one.
      Name: 'Oven',
      Modes: ['OvenMode', 'MachineState'],
      InitState: 'heating;ready',
      WorkingStates: [
        {
          Name: 'heating;running',
          Trust: 'trusted',
          Privacy: 'public',
          Dynamics: [{ VariableName: 'ovenJobState', Value: 'cooking' }]
        },
        { Name: 'heating;ready', Trust: 'trusted', Privacy: 'public' }
      ],
      InternalVariables: [
        { Name: 'ovenJobState', IsInside: true, FalsifiableWhenCompromised: false, Trust: 'trusted', Privacy: 'public', Values: ['ready', 'cooking'] }
      ],
      APIs: []
    } as never
    const wrapper = mountWithManifest(manifest, 'Oven', 'heating;running')

    const withEffect = document.querySelector('[data-testid="device-dialog-state-effects-heating;running"]')
    expect(withEffect?.textContent?.trim()).toBe('ovenJobState: Next value: cooking')
    // A state that declares no Dynamics reads as "-", not as an empty cell.
    const withoutEffect = document.querySelector('[data-testid="device-dialog-state-effects-heating;ready"]')
    expect(withoutEffect?.textContent?.trim()).toBe('-')
    wrapper.unmount()
  })

  /**
   * Two shapes no bundled template produces, so nothing else would catch a regression here.
   *
   * A blank `ChangeRate` is *absent* to the backend — `DeviceTemplateDto.Dynamic.isValidDynamic` tests
   * `!= null && !isBlank()` — so it must fall through to `Value` instead of rendering "rate  per step".
   * And a trigger `Relation` may arrive as `GTE`, which `SmvRelationUtils` folds to `>=` server-side;
   * rendering the raw token would give one operator two spellings in one product.
   */
  it('treats a blank change rate as absent and localizes an aliased trigger relation', () => {
    const manifest: DeviceManifest = {
      Name: 'Probe',
      Modes: ['ProbeMode'],
      InitState: 'idle',
      WorkingStates: [
        {
          Name: 'idle',
          Trust: 'trusted',
          Privacy: 'public',
          Dynamics: [{ VariableName: 'level', ChangeRate: '   ', Value: 'high' }]
        }
      ],
      InternalVariables: [
        { Name: 'level', IsInside: true, FalsifiableWhenCompromised: false, Trust: 'trusted', Privacy: 'public', Values: ['high', 'low'] }
      ],
      APIs: [],
      Transitions: [
        {
          Name: 'escalate',
          StartState: 'idle',
          EndState: 'idle',
          Trigger: { Attribute: 'level', Relation: 'GTE', Value: 'high' }
        }
      ]
    } as never
    const wrapper = mountWithManifest(manifest, 'Probe')

    // Falls through to Value rather than printing an empty rate.
    expect(document.querySelector('[data-testid="device-dialog-state-effects-idle"]')?.textContent?.trim())
      .toBe('level: Next value: high')

    const row = document.querySelector('[data-testid="device-dialog-transition-escalate"]')
    const trigger = Array.from(row!.querySelectorAll('td'))[3].textContent?.trim()
    expect(trigger).toBe('level Greater or equal high')
    expect(trigger).not.toContain('GTE')
    wrapper.unmount()
  })

  /**
   * `Reads: false` is a capability the table used to hide: both an affect-only and a read-capable shared
   * variable rendered as plain "environment variable", so nothing explained why `temperature` is
   * selectable as a rule condition on a Thermostat and not on an Air Conditioner. `affectsEnvironment`
   * cannot stand in for it — a read-capable variable may also affect, so that badge shows in both cases.
   *
   * The falsifiability cell needed the same split. Its two labels both speak about a *reading*, and the
   * value-falsification branch in `SmvMainModuleBuilder` requires `Reads !== false`, so for an affect-only
   * declaration the flag cannot move a value; it only forces `trust_<name>` untrusted. All seven bundled
   * affect-only declarations are `false`, so the old wording was true by luck.
   */
  it('marks an affect-only shared variable and scopes what compromise can falsify', () => {
    // The real Air Conditioner shape: temperature is IsInside=false, Reads=false, and impacted.
    const manifest: DeviceManifest = {
      Name: 'Air Conditioner',
      Modes: ['HvacMode'],
      InitState: 'auto',
      WorkingStates: [{ Name: 'auto', Trust: 'trusted', Privacy: 'public' }],
      ImpactedVariables: ['temperature'],
      InternalVariables: [
        {
          Name: 'temperature',
          IsInside: false,
          Reads: false,
          FalsifiableWhenCompromised: true,
          Trust: 'untrusted',
          Privacy: 'public',
          LowerBound: 0,
          UpperBound: 100,
          NaturalChangeRate: '[-1, 1]'
        }
      ],
      APIs: []
    } as never
    const wrapper = mountWithManifest(manifest, 'Air Conditioner', 'auto')

    const badge = document.querySelector('[data-testid="device-dialog-variable-affect-only-temperature"]')
    expect(badge, 'an affect-only declaration is not distinguished').not.toBeNull()
    expect(badge!.textContent?.trim()).toBe('Affects only, does not read')

    // Even with the flag true, the cell must not claim a falsified *reading* for a value never read.
    // The cell text carries the icon ligature too, as the neighbouring chips do, so match on content.
    const falsifiable = document.querySelector('[data-testid="device-dialog-variable-falsifiable-temperature"]')
    expect(falsifiable?.textContent).toContain('Only the source label changes under compromise')
    expect(falsifiable?.textContent).not.toContain('Reading may be falsified')
    wrapper.unmount()
  })

  /**
   * Reported: a Car showing initial state *away* with location *garage*, saveable as-is.
   *
   * Fixing the default was not enough — the panel offers both halves, so a user could still build the
   * contradiction by hand. The writers now refuse that pair, which would have turned a plausible edit into
   * an error message, so the panel keeps the pair consistent as it is edited: the state is the half that
   * decides, because a `Dynamics` value is a property of being in that state. A variable the new state says
   * nothing about is left alone, since that is a real instance choice rather than a consequence.
   */
  it('re-derives a state-constrained variable when the user picks another state', async () => {
    const manifest: DeviceManifest = {
      Name: 'Car',
      Modes: ['CarLocation'],
      InitState: 'away',
      WorkingStates: [
        { Name: 'garage', Trust: 'untrusted', Privacy: 'private', Dynamics: [{ VariableName: 'location', Value: 'garage' }] },
        { Name: 'away', Trust: 'untrusted', Privacy: 'private', Dynamics: [{ VariableName: 'location', Value: 'away' }] }
      ],
      InternalVariables: [
        { Name: 'location', IsInside: true, FalsifiableWhenCompromised: true, Trust: 'untrusted', Privacy: 'private', Values: ['garage', 'away'] },
        { Name: 'odometer', IsInside: true, FalsifiableWhenCompromised: false, Trust: 'untrusted', Privacy: 'private', LowerBound: 0, UpperBound: 100 }
      ],
      APIs: []
    } as never
    const wrapper = mountWithManifest(manifest, 'Car', 'away')
    await flushPromises()

    const stateSelect = document.querySelector<HTMLSelectElement>('[data-testid="device-runtime-state"]')!
    // Every state constrains `location`, so it is shown as the state's consequence, not offered as an input.
    const derived = () => document.querySelector('[data-testid="device-runtime-variable-derived-location"]')
    expect(document.querySelector('[data-testid="device-runtime-variable-location"]'),
      'a state-derived variable must not be editable').toBeNull()
    // Seeded consistently: `away` declares `location = away`, not the first enum literal `garage`.
    expect(derived()?.textContent).toContain('away')

    const odometer = document.querySelector<HTMLInputElement>('[data-testid="device-runtime-variable-odometer"]')!
    odometer.value = '42'
    odometer.dispatchEvent(new Event('input'))
    await flushPromises()

    stateSelect.value = 'garage'
    stateSelect.dispatchEvent(new Event('change'))
    await flushPromises()

    expect(derived()?.textContent, 'the state changed but its variable did not follow').toContain('garage')
    // `garage` says nothing about the odometer, so an explicit instance value survives.
    expect(document.querySelector<HTMLInputElement>('[data-testid="device-runtime-variable-odometer"]')!.value).toBe('42')
    wrapper.unmount()
  })

  /**
   * A node stored before this rule existed still carries the contradiction, and it must not be displayed as
   * truth: without re-deriving on open, the read-only field showed `garage` under the label "set by the
   * initial state" while the state read `away`, and the only way to correct it was to toggle the state away
   * and back. The server canonicalises a write the same way, so what is shown is what a save would store.
   */
  it('re-derives a legacy node whose stored variable contradicts its state', async () => {
    const manifest: DeviceManifest = {
      Name: 'Car',
      Modes: ['CarLocation'],
      InitState: 'away',
      WorkingStates: [
        { Name: 'garage', Trust: 'untrusted', Privacy: 'private', Dynamics: [{ VariableName: 'location', Value: 'garage' }] },
        { Name: 'away', Trust: 'untrusted', Privacy: 'private', Dynamics: [{ VariableName: 'location', Value: 'away' }] }
      ],
      InternalVariables: [
        { Name: 'location', IsInside: true, FalsifiableWhenCompromised: true, Trust: 'untrusted', Privacy: 'private', Values: ['garage', 'away'] }
      ],
      APIs: []
    } as never
    const wrapper = mount(DeviceDialog, {
      attachTo: document.body,
      props: {
        // Mounted closed and then opened, because the draft loads from the node only on that transition —
        // mounting already-visible seeds it from the template instead and would not exercise this path.
        visible: false,
        deviceName: 'Car',
        label: 'My car',
        nodeId: 'car-legacy',
        manifest,
        nodes: [{
          id: 'car-legacy',
          templateName: 'Car',
          label: 'My car',
          position: { x: 0, y: 0 },
          state: 'away',
          width: 176,
          height: 128,
          // The contradiction as the old defect persisted it.
          variables: [{ name: 'location', value: 'garage', trust: 'untrusted' }]
        }],
        deviceTemplates: [{ name: 'Car', manifest, defaultTemplate: false }],
        specs: []
      },
      global: { plugins: [i18n] }
    } as never)
    await wrapper.setProps({ visible: true })
    await flushPromises()

    const derived = document.querySelector('[data-testid="device-runtime-variable-derived-location"]')
    expect(derived?.textContent, 'a stored contradiction was displayed as truth').toContain('away')
    expect(derived?.textContent).not.toContain('garage')
    wrapper.unmount()
  })

  it('omits the transitions section for a template that declares none', () => {
    const manifest: DeviceManifest = {
      Name: 'Air Conditioner',
      Modes: ['HvacMode'],
      InitState: 'auto',
      WorkingStates: [{ Name: 'auto', Trust: 'trusted', Privacy: 'public' }],
      InternalVariables: [],
      APIs: [],
      Transitions: []
    } as never
    const wrapper = mountWithManifest(manifest, 'Air Conditioner', 'auto')

    expect(document.querySelector('[data-testid="device-dialog-states"]')).not.toBeNull()
    expect(document.querySelector('[data-testid="device-dialog-transitions"]')).toBeNull()
    wrapper.unmount()
  })

  it('explains stateless devices once without suggesting missing state data', () => {
    const manifest: DeviceManifest = { Name: 'Motion Detector', Modes: [], APIs: [],
      InternalVariables: [{ Name: 'motion', IsInside: true, Values: ['active', 'inactive'],
        Trust: 'trusted', Privacy: 'public', FalsifiableWhenCompromised: false }] }
    const wrapper = mountWithManifest(manifest, 'Motion Detector', 'stateless')
    try {
      expect(document.querySelector('[data-testid="device-dialog-stateless"]')?.textContent)
        .toContain(i18n.global.t('app.noStateMachineDetail'))
      expect(document.querySelector('[data-testid="device-dialog-states"]')).toBeNull()
      const basic = document.querySelector('[data-testid="device-dialog-basic"]')!
      const labels = Array.from(basic.querySelectorAll('td:first-child')).map(cell => cell.textContent?.trim())
      expect(labels).not.toContain(i18n.global.t('app.modes'))
      expect(labels).not.toContain(i18n.global.t('app.initState'))
      expect(labels).not.toContain(i18n.global.t('app.templateInitialState'))
      expect(document.querySelector('[data-testid="device-dialog-variables"]')).not.toBeNull()
      const help = wrapper.findAllComponents(InfoTooltip).find(item => item.props('testId') === 'device-runtime-help')!
      expect(help.props('text')).toBe(i18n.global.t('app.instanceRuntimeHint'))
    } finally { wrapper.unmount() }
  })
})
