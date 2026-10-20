// @vitest-environment jsdom
import { defineAsyncComponent, defineComponent, h, nextTick, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AppErrorBoundary from '../AppErrorBoundary.vue'
import { i18n as appI18n } from '@/assets/i18n'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      app: {
        viewErrorTitle: 'View failed',
        retry: 'Retry',
        reloadPage: 'Reload page'
      }
    }
  },
  missingWarn: false,
  fallbackWarn: false
})

describe('AppErrorBoundary', () => {
  it.each(['en', 'zh-CN'] as const)('offers document reload for a captured lazy-panel failure in %s', async locale => {
    const previousLocale = appI18n.global.locale.value
    appI18n.global.locale.value = locale
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const LazyPanel = defineAsyncComponent(() => Promise.reject(new Error('Offline panel module')))
    const wrapper = mount(AppErrorBoundary, {
      global: { plugins: [appI18n] }, slots: { default: () => h(LazyPanel) }
    })
    try {
      await flushPromises()
      const alert = wrapper.get('[role="alert"]')
      expect(alert.findAll('button').map(button => button.text())).toEqual([appI18n.global.t('app.reloadPage')])
      const browserWindow = window
      const reload = vi.fn()
      vi.stubGlobal('window', new Proxy(browserWindow, { get: (target, property) => property === 'location'
        ? { reload } : Reflect.get(target, property, target) }))
      await alert.get('button').trigger('click')
      expect(reload).toHaveBeenCalledOnce()
      expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    } finally { wrapper.unmount(); appI18n.global.locale.value = previousLocale }
  })

  it('uses document reload when a custom fallback retries a lazy-panel failure', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const LazyPanel = defineAsyncComponent(() => Promise.reject(new Error('Offline panel module')))
    const wrapper = mount(AppErrorBoundary, {
      global: { plugins: [i18n] }, slots: {
        default: () => h(LazyPanel),
        fallback: ({ retry }: { retry: () => void }) => h('button', { onClick: retry }, 'Recover')
      }
    })
    try {
      await flushPromises()
      const browserWindow = window
      const reload = vi.fn()
      vi.stubGlobal('window', new Proxy(browserWindow, { get: (target, property) => property === 'location'
        ? { reload } : Reflect.get(target, property, target) }))
      await wrapper.get('button').trigger('click')
      expect(reload).toHaveBeenCalledOnce()
    } finally { wrapper.unmount() }
  })

  it('retains structured diagnostics in one disclosure while global failure controls follow language changes', async () => {
    const previousLocale = appI18n.global.locale.value
    const wrapper = mount(AppErrorBoundary, {
      props: { error: { response: { status: 503, data: {
        message: 'Profile store unavailable', data: { errors: { profile: 'Profile store unavailable' } }
      } } } }, global: { plugins: [appI18n] }
    })
    try {
      for (const language of ['en', 'zh-CN'] as const) {
        appI18n.global.locale.value = language
        await nextTick()
        const alert = wrapper.get('[role="alert"]')
        expect(alert.get('h2').text()).toBe(appI18n.global.t('app.viewErrorTitle'))
        expect(alert.get('button').text()).toBe(appI18n.global.t('app.reloadPage'))
        expect(alert.findAll('button')).toHaveLength(1)
        const disclosure = alert.get('[data-testid="failure-technical-details"]')
        expect(disclosure.attributes('open')).toBeUndefined()
        expect(disclosure.text()).toContain('profile: Profile store unavailable')
        expect(disclosure.text().match(/Profile store unavailable/g)).toHaveLength(1)
        expect(disclosure.text()).toContain('HTTP 503')
      }
    } finally { wrapper.unmount(); appI18n.global.locale.value = previousLocale }
  })

  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

  it('contains a descendant render error and can recover after retry', async () => {
    const shouldThrow = ref(true)
    const BrokenView = defineComponent({
      setup: () => () => shouldThrow.value
        ? (() => { throw new Error('render failure') })()
        : h('p', { 'data-testid': 'recovered' }, 'Recovered')
    })
    vi.spyOn(console, 'error').mockImplementation(() => undefined)

    const wrapper = mount(AppErrorBoundary, {
      global: { plugins: [i18n] },
      slots: { default: () => h(BrokenView) }
    })

    await nextTick()
    expect(wrapper.get('[role="alert"]').text()).toContain('View failed')

    shouldThrow.value = false
    await wrapper.get('button').trigger('click')
    await nextTick()

    expect(wrapper.get('[data-testid="recovered"]').text()).toBe('Recovered')
    wrapper.unmount()
  })

  it('offers document reload rather than render retry for an external route failure', async () => {
    const error = new Error('Failed to fetch dynamically imported module: /assets/Board-test.js')
    const wrapper = mount(AppErrorBoundary, {
      props: { error, resetKey: 'initial' },
      global: { plugins: [i18n] },
      slots: { default: () => h('p', { 'data-testid': 'route-view' }, 'Route view') }
    })
    try {
      const fallback = wrapper.get('[data-testid="app-error-fallback"]')
      expect(fallback.text()).toContain('View failed')
      expect(fallback.findAll('button').map(button => button.text())).toEqual(['Reload page'])
      expect(wrapper.find('[data-testid="route-view"]').exists()).toBe(false)
      expect(fallback.find('[data-testid="failure-cause"]').exists()).toBe(false)
      const details = fallback.get('[data-testid="failure-technical-details"]')
      expect(details.attributes('open')).toBeUndefined()
      expect(details.text()).toContain(error.message)
      await wrapper.setProps({ resetKey: 'changed-account' })
      expect(wrapper.find('[data-testid="route-view"]').exists()).toBe(false)

      const browserWindow = window
      const reload = vi.fn()
      vi.stubGlobal('window', new Proxy(browserWindow, {
        get: (target, property) => property === 'location' ? { reload } : Reflect.get(target, property, target)
      }))
      await fallback.get('button').trigger('click')
      expect(reload).toHaveBeenCalledOnce()

      await wrapper.setProps({ error: null })
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      expect(wrapper.get('[data-testid="route-view"]').text()).toBe('Route view')
    } finally { wrapper.unmount() }
  })

  it('still resets captured render failures when the route identity changes', async () => {
    const shouldThrow = ref(true)
    const BrokenView = defineComponent({
      setup: () => () => shouldThrow.value
        ? (() => { throw new Error('render failure') })()
        : h('p', { 'data-testid': 'recovered' }, 'Recovered')
    })
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const wrapper = mount(AppErrorBoundary, {
      props: { resetKey: 'alice' },
      global: { plugins: [i18n] },
      slots: { default: () => h(BrokenView) }
    })
    try {
      await nextTick()
      expect(wrapper.get('[role="alert"]').text()).toContain('View failed')
      shouldThrow.value = false
      await wrapper.setProps({ resetKey: 'bob' })
      expect(wrapper.get('[data-testid="recovered"]').text()).toBe('Recovered')
    } finally { wrapper.unmount() }
  })
})
