import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ControlCenter from '../ControlCenter.vue'
import i18n from '@/assets/i18n'

const create = (activeSection: string) => mount(ControlCenter, {
  props: { activeSection },
  slots: {
    'device-recommendations': '<p>Device suggestions</p>',
    'rule-recommendations': '<p>Rule suggestions</p>',
    'spec-recommendations': '<p>Requirement suggestions</p>'
  },
  global: { plugins: [i18n], stubs: { RuleBuilder: true, HintTooltip: { props: ['content'], template: '<slot />' } } }
})

describe('recommendations beside their creation form', () => {
  it.each(['templates', 'devices', 'rules', 'specs'])('starts every top-level %s disclosure closed', section => {
    const wrapper = create(section)
    try {
      const disclosures = wrapper.get(`[data-testid="control-section-${section}"]`).element.querySelectorAll(':scope > details')
      expect(disclosures.length).toBeGreaterThan(0)
      for (const disclosure of disclosures) expect((disclosure as HTMLDetailsElement).open).toBe(false)
    } finally { wrapper.unmount() }
  })

  it.each(['devices', 'rules', 'specs'] as const)('opens %s creation on an explicit request and remembers a manual choice', async section => {
    const wrapper = create(section)
    try {
      const vm = wrapper.vm as unknown as { openCreation: (section: 'devices' | 'rules' | 'specs') => void }
      vm.openCreation(section)
      await wrapper.vm.$nextTick()
      const details = wrapper.get(`[data-testid="control-section-${section}"] details`)
      expect((details.element as HTMLDetailsElement).open).toBe(true)
      ;(details.element as HTMLDetailsElement).open = false
      await details.trigger('toggle')
      await wrapper.setProps({ activeSection: 'templates' })
      await wrapper.setProps({ activeSection: section })
      expect((wrapper.get(`[data-testid="control-section-${section}"] details`).element as HTMLDetailsElement).open).toBe(false)
      vm.openCreation(section)
      await wrapper.vm.$nextTick()
      expect((wrapper.get(`[data-testid="control-section-${section}"] details`).element as HTMLDetailsElement).open).toBe(true)
    } finally { wrapper.unmount() }
  })

  it.each([
    ['devices', 'device', 'Device suggestions'],
    ['rules', 'rule', 'Rule suggestions'],
    ['specs', 'spec', 'Requirement suggestions']
  ])('groups %s creation and recommendations as sibling disclosures', (section, kind, text) => {
    const wrapper = create(section!)
    try {
      const panel = wrapper.get(`[data-testid="control-section-${section}"]`)
      const recommendation = panel.get(`[data-testid="${kind}-recommendation-section"]`)
      expect(recommendation.element.tagName).toBe('DETAILS')
      expect(recommendation.element.parentElement).toBe(panel.element)
      expect(recommendation.find('summary').exists()).toBe(true)
      expect(recommendation.attributes('open')).toBeUndefined()
      expect(recommendation.text()).toContain(text)
      expect(panel.element.querySelectorAll(':scope > details')).toHaveLength(2)
    } finally { wrapper.unmount() }
  })

  it('preserves an expanded recommendation when changing sections and exposes running state', async () => {
    const wrapper = create('devices')
    try {
      const details = wrapper.get('[data-testid="device-recommendation-section"]')
      ;(details.element as HTMLDetailsElement).open = true
      await details.trigger('toggle')
      await wrapper.setProps({ activeSection: 'rules', recommendationRunning: { devices: true } })
      expect(wrapper.get('[data-testid="control-tab-devices"]').text()).toContain(i18n.global.t('app.recommendationInProgress'))
      await wrapper.setProps({ activeSection: 'devices' })
      expect((wrapper.get('[data-testid="device-recommendation-section"]').element as HTMLDetailsElement).open).toBe(true)
      expect(wrapper.emitted('cancel-recommendation')).toBeUndefined()
    } finally { wrapper.unmount() }
  })
})
