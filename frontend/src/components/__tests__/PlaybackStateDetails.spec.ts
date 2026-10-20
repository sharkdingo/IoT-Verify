import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import PlaybackStateDetails from '../PlaybackStateDetails.vue'
import { i18n } from '@/assets/i18n'
import type { TraceDevice } from '@/types/verify'

describe('playback state evidence', () => {
  afterEach(() => { i18n.global.locale.value = 'en' })

  it.each(['en', 'zh-CN'] as const)('shows current-board absence as readable evidence in %s, without inferring it from a missing rule id', async locale => {
    i18n.global.locale.value = locale
    const wrapper = mount(PlaybackStateDetails, {
      props: {
        devices: [{ deviceId: 'sensor', deviceLabel: 'Recorded sensor', templateName: 'Sensor', modelTokenSource: 'CUSTOM', variables: [] }],
        environmentVariables: [],
        compromisedAutomationLinks: [
          { ruleId: 'removed', ruleIndex: 0, ruleLabel: 'Recorded removed rule' },
          { ruleIndex: 1, ruleLabel: 'Recorded rule without identity' }
        ],
        currentDeviceIds: [], currentRuleIds: [],
        formatDeviceToken: (_device, value) => String(value),
        formatEnvironmentToken: (_name, value) => String(value)
      },
      global: { plugins: [i18n] }
    })
    try {
      const absentDevice = i18n.global.t('app.traceVisualization.historicalDeviceNotOnCurrentBoard')
      const absentRule = i18n.global.t('app.traceVisualization.historicalRuleNotOnCurrentBoard')
      const devices = wrapper.get('[data-testid="playback-state-devices"]')
      const rules = wrapper.get('[data-testid="playback-state-compromised-links"]').findAll('li')
      expect(devices.text()).toContain(absentDevice)
      expect(rules[0].text()).toContain(absentRule)
      expect(rules[1].text()).not.toContain(absentRule)
      for (const element of [devices, rules[0]]) {
        expect(element.findAll('[aria-hidden="true"]').some(node => node.text().includes(absentRule) || node.text().includes(absentDevice))).toBe(false)
      }
      await wrapper.setProps({ currentDeviceIds: ['sensor'], currentRuleIds: ['removed'] })
      expect(devices.text()).not.toContain(absentDevice)
      expect(rules[0].text()).not.toContain(absentRule)
      expect(devices.text()).toContain('Recorded sensor')
      expect(rules[0].text()).toContain('Recorded removed rule')
    } finally { wrapper.unmount() }
  })
  it('separates reported readings, stable actual values and security without inventing unobserved readings', () => {
    const device: TraceDevice = { deviceId: 'sensor', deviceLabel: 'Historical hall sensor', templateName: 'Sensor',
      modelTokenSource: 'CUSTOM', state: 'on', compromised: true,
      variables: [{ name: 'temperature', value: '40', trust: 'untrusted', modelTokenSource: 'CUSTOM' },
        { name: 'affectOnly', value: '', observed: false, modelTokenSource: 'CUSTOM' }],
      privacies: [{ name: 'temperature', propertyScope: 'variable', privacy: 'private' }] }
    const input = structuredClone(device)
    const wrapper = mount(PlaybackStateDetails, { props: { devices: [device],
      environmentVariables: [{ name: 'temperature', value: '20', modelTokenSource: 'CUSTOM' }],
      compromisedAutomationLinks: [{ ruleId: 'old_rule', ruleIndex: 0, ruleLabel: 'Historical rule' }],
      currentDeviceIds: [], currentRuleIds: [], formatDeviceToken: (_device, value) => String(value),
      formatEnvironmentToken: (_name, value) => String(value) }, global: { plugins: [i18n] } })
    const readings = wrapper.get('[data-testid="playback-state-devices"]')
    expect(readings.text()).toContain('40')
    expect(readings.text()).not.toContain('20')
    expect(readings.text()).not.toContain('affectOnly')
    expect(wrapper.get('[data-testid="playback-state-environment"]').text()).toContain('20')
    expect(wrapper.get('[data-testid="playback-state-security"]').text()).toContain('temperature')
    expect(wrapper.get('[data-testid="playback-state-compromised-links"]').text()).toContain('Historical rule')
    expect(device).toEqual(input)
    wrapper.unmount()
  })

  it('pairs each state-machine name with its value instead of showing two semicolon lists', () => {
    i18n.global.locale.value = 'en'
    const device: TraceDevice = {
      deviceId: 'hub', deviceLabel: 'Care hub', templateName: 'Custom hub', modelTokenSource: 'CUSTOM',
      mode: 'HomeMode;WorkState', state: 'away;idle', variables: []
    }
    const wrapper = mount(PlaybackStateDetails, {
      props: {
        devices: [device], environmentVariables: [], compromisedAutomationLinks: [],
        currentDeviceIds: ['hub'], currentRuleIds: [],
        formatDeviceToken: (_device, value) => String(value),
        formatEnvironmentToken: (_name, value) => String(value)
      },
      global: { plugins: [i18n] }
    })
    const readings = wrapper.get('[data-testid="playback-state-devices"]')
    expect(readings.findAll('dt').map(row => row.text())).toEqual(['HomeMode', 'WorkState'])
    expect(readings.findAll('dd').map(row => row.text())).toEqual(['away', 'idle'])
    expect(readings.text()).not.toContain(';')
    wrapper.unmount()
  })
})
