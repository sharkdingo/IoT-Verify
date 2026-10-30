// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { i18n } from '@/assets/i18n'
import ControlCenter from '../ControlCenter.vue'
import SystemInspector from '../SystemInspector.vue'

vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return {
    ...actual,
    ElMessage: Object.assign(vi.fn(), { success: vi.fn(), warning: vi.fn(), error: vi.fn() })
  }
})

beforeEach(() => {
  i18n.global.locale.value = 'en'
})

afterEach(() => {
  document.body.innerHTML = ''
})

const SECTIONS = ['templates', 'devices', 'rules', 'specs'] as const

describe('board side panel tab semantics', () => {
  it.each(['en', 'zh-CN'] as const)('names each collapse control by its panel in %s', async locale => {
    i18n.global.locale.value = locale
    const options = { props: { collapsed: true }, global: { plugins: [i18n] } }
    const control = mount(ControlCenter, options)
    const inspector = mount(SystemInspector, options)
    const labels = locale === 'en'
      ? ['Expand Control Center', 'Expand System Inspector', 'Collapse Control Center', 'Collapse System Inspector']
      : ['展开控制中心', '展开系统检查器', '收起控制中心', '收起系统检查器']
    try {
      expect(control.get('[data-testid="control-center-toggle"]').attributes('aria-label')).toBe(labels[0])
      expect(inspector.get('[data-testid="system-inspector-toggle"]').attributes('aria-label')).toBe(labels[1])
      await control.setProps({ collapsed: false })
      await inspector.setProps({ collapsed: false })
      expect(control.get('[data-testid="control-center-toggle"]').attributes('aria-label')).toBe(labels[2])
      expect(inspector.get('[data-testid="system-inspector-toggle"]').attributes('aria-label')).toBe(labels[3])
    } finally { control.unmount(); inspector.unmount() }
  })

  it('owns rule creation inside the panel and preserves its draft across navigation and collapse', async () => {
    const wrapper = mount(ControlCenter, {
      attachTo: document.body,
      props: { activeSection: 'rules', collapsed: false },
      global: { plugins: [i18n] }
    })
    try {
      const creation = wrapper.get<HTMLDetailsElement>('[data-testid="rule-creation-card"]')
      expect(creation.element.open).toBe(false)
      creation.element.open = true
      await creation.trigger('toggle')
      const editor = wrapper.get('[data-testid="control-section-rules"] [data-testid="rule-builder"]')
      expect(editor.isVisible()).toBe(true)
      expect(wrapper.find('[aria-modal="true"]').exists()).toBe(false)
      const name = editor.get('#rule-builder-name')
      await name.setValue('Evening automation')
      await wrapper.setProps({ activeSection: 'devices' })
      expect(editor.isVisible()).toBe(false)
      await wrapper.setProps({ activeSection: 'rules', collapsed: true })
      expect(editor.isVisible()).toBe(false)
      await wrapper.setProps({ collapsed: false })
      expect(editor.isVisible()).toBe(true)
      expect(editor.get('#rule-builder-name').element).toHaveProperty('value', 'Evening automation')
      await editor.get('[data-testid="rule-reset"]').trigger('click')
      expect(name.element).toHaveProperty('value', '')
    } finally {
      wrapper.unmount()
    }
  })

  it.each(['en', 'zh-CN'] as const)('uses the same short device, rule and specification labels on both sides in %s', locale => {
    i18n.global.locale.value = locale
    const options = { props: { width: 240 }, global: { plugins: [i18n] } }
    const control = mount(ControlCenter, options)
    const inspector = mount(SystemInspector, options)
    try {
      for (const section of ['devices', 'rules', 'specs']) {
        expect(control.get(`#control-tab-${section} .board-section-tab__label`).text())
          .toBe(inspector.get(`#inspector-tab-${section} .board-section-tab__label`).text())
      }
      expect(control.get('#control-tab-specs .board-section-tab__label').text())
        .toBe(locale === 'en' ? 'Specs' : '规约')
    } finally { control.unmount(); inspector.unmount() }
  })

  it.each(['control', 'inspector'])('preserves toggle focus across collapse and expansion in %s', async panel => {
    const options = { attachTo: document.body, global: { plugins: [i18n], stubs: { transition: false } } }
    const wrapper = panel === 'control' ? mount(ControlCenter, options) : mount(SystemInspector, options)
    const toggle = wrapper.get(`[data-testid="${panel === 'control' ? 'control-center' : 'system-inspector'}-toggle"]`)
    const element = toggle.element as HTMLButtonElement
    const body = wrapper.get('.board-panel-body').element
    element.focus()

    await toggle.trigger('click')
    expect(element.isConnected).toBe(true)
    expect(document.activeElement).toBe(element)
    expect(element.getAttribute('aria-expanded')).toBe('false')
    expect(body.hasAttribute('inert')).toBe(true)

    await toggle.trigger('click')
    expect(document.activeElement).toBe(element)
    expect(element.getAttribute('aria-expanded')).toBe('true')
    expect(wrapper.find('[role="tablist"]').exists()).toBe(true)
    expect(wrapper.get('.board-panel-body').element.hasAttribute('inert')).toBe(false)
    wrapper.unmount()
  })

  it('exposes the control center sections as a single-selection tablist', () => {
    const wrapper = mount(ControlCenter, {
      attachTo: document.body,
      props: { activeSection: 'templates' },
      global: { plugins: [i18n] }
    })

    const tablist = wrapper.get('[role="tablist"]')
    const tabs = tablist.findAll('[role="tab"]')
    expect(tabs).toHaveLength(SECTIONS.length)
    expect(tabs.filter(tab => tab.attributes('aria-selected') === 'true')).toHaveLength(1)

    const selected = tabs.find(tab => tab.attributes('aria-selected') === 'true')!
    expect(selected.attributes('id')).toBe('control-tab-templates')
    // Roving tabindex: only the selected tab is reachable with Tab.
    expect(tabs.filter(tab => tab.attributes('tabindex') === '0')).toHaveLength(1)

    const panel = wrapper.get(`#${selected.attributes('aria-controls')}`)
    expect(panel.attributes('role')).toBe('tabpanel')
    expect(panel.attributes('aria-labelledby')).toBe('control-tab-templates')

    wrapper.unmount()
  })

  it('moves control center selection with the arrow keys', async () => {
    const wrapper = mount(ControlCenter, {
      attachTo: document.body,
      global: { plugins: [i18n] }
    })

    await wrapper.get('#control-tab-templates').trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.get('#control-tab-devices').attributes('aria-selected')).toBe('true')
    expect(wrapper.emitted('update:active-section')?.at(-1)).toEqual(['devices'])

    await wrapper.get('#control-tab-devices').trigger('keydown', { key: 'End' })
    expect(wrapper.get('#control-tab-specs').attributes('aria-selected')).toBe('true')

    await wrapper.get('#control-tab-specs').trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.get('#control-tab-templates').attributes('aria-selected')).toBe('true')

    wrapper.unmount()
  })

  it('gives the system inspector the same tablist contract', async () => {
    const wrapper = mount(SystemInspector, {
      attachTo: document.body,
      global: { plugins: [i18n] }
    })

    const tabs = wrapper.get('[role="tablist"]').findAll('[role="tab"]')
    expect(tabs.filter(tab => tab.attributes('aria-selected') === 'true')).toHaveLength(1)
    expect(tabs.filter(tab => tab.attributes('tabindex') === '0')).toHaveLength(1)

    await wrapper.get('#inspector-tab-devices').trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.get('#inspector-tab-rules').attributes('aria-selected')).toBe('true')

    wrapper.unmount()
  })
})
