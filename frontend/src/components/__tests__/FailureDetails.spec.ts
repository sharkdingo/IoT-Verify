// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import FailureDetails from '../FailureDetails.vue'
import { i18n } from '@/assets/i18n'

afterEach(() => { i18n.global.locale.value = 'en' })

describe('actionable failure details', () => {
  it('omits the visible headline from technical details even when no separate cause is supplied', async () => {
    const message = 'The request was rejected.'
    const wrapper = mount(FailureDetails, { props: { message, suggestion: '', details: [message, 'HTTP 409'] } })
    try {
      expect(wrapper.get('details').text()).not.toContain(message)
      expect(wrapper.get('details').text()).toContain('HTTP 409')
      await wrapper.setProps({ details: [message] })
      expect(wrapper.find('details').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })
  it('keeps Chinese guidance with raw English diagnostics and field errors as text', () => {
    i18n.global.locale.value = 'zh-CN'
    const wrapper = mount(FailureDetails, { props: {
      cause: 'Template definition is invalid',
      suggestion: '核对当前规则并重新验证',
      details: ['NuSMV timed out after 60000ms', '<script>privateCall()</script>'],
      fields: { 'rules[0].conditions[0]': 'Unknown condition device: lamp' }
    } })
    expect(wrapper.get('[data-testid="failure-next-step"]').text()).toContain('核对当前规则并重新验证')
    expect(wrapper.get('summary').text()).toBe(i18n.global.t('app.technicalDetails'))
    expect(wrapper.get('details').text()).toContain('NuSMV timed out after 60000ms')
    expect(wrapper.get('dt').text()).toBe('rules[0].conditions[0]')
    expect(wrapper.get('dd').text()).toBe('Unknown condition device: lamp')
    expect(wrapper.get('[data-testid="failure-cause"]').text()).toBe('Template definition is invalid')
    expect(wrapper.get('[data-testid="failure-fields"]').text()).toContain('Unknown condition device: lamp')
    expect(wrapper.get('details').attributes('open')).toBeUndefined()
    expect(wrapper.get('dt').element.closest('details')).toBeNull()
    expect(wrapper.get('[data-testid="failure-cause"]').element.closest('details')).toBeNull()
    expect(wrapper.find('script').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows English guidance without inventing technical evidence when none was returned', () => {
    i18n.global.locale.value = 'en'
    const wrapper = mount(FailureDetails, { props: { suggestion: 'Inspect history before retrying.' } })
    expect(wrapper.text()).toContain('Inspect history before retrying.')
    expect(wrapper.find('details').exists()).toBe(false)
    expect(wrapper.find('[data-testid="failure-cause"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="failure-fields"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('does not repeat a cause already used as the summary or hide code diagnostics', () => {
    const wrapper = mount(FailureDetails, { props: {
      message: 'File is unavailable', cause: 'File is unavailable', suggestion: 'Select it again.',
      details: ['File is unavailable', 'HTTP 404']
    } })
    expect(wrapper.find('[data-testid="failure-cause"]').exists()).toBe(false)
    expect(wrapper.get('details').text()).toContain('HTTP 404')
    expect(wrapper.get('details').text()).not.toContain('File is unavailable')
    wrapper.unmount()
  })

  it.each(['reason', 'field and reason'])('shows a cause repeated as %s once, alongside its correction path', format => {
    const reason = 'APIs require at least one Mode to define API state tracking.'
    const cause = format === 'reason' ? reason : `manifest: ${reason}`
    const wrapper = mount(FailureDetails, { props: {
      message: 'Invalid device template definition', cause, suggestion: 'Correct the field.',
      fields: { manifest: reason }, details: [cause, 'HTTP 400']
    } })
    expect(wrapper.find('[data-testid="failure-cause"]').exists()).toBe(false)
    expect(wrapper.get('dt').text()).toBe('manifest')
    expect(wrapper.get('dd').text()).toBe(reason)
    expect(wrapper.text().split(reason)).toHaveLength(2)
    expect(wrapper.get('dt').element.closest('details')).toBeNull()
    expect(wrapper.get('details').text()).toContain('HTTP 400')
    wrapper.unmount()
  })
})
