// @vitest-environment jsdom
import { DOMWrapper, flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { expect, it } from 'vitest'
import { ElMessageBox } from 'element-plus'
import en from 'element-plus/es/locale/lang/en'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import App from './App.vue'
import { i18n } from '@/assets/i18n'
import { confirmChoice, dismissOpenConfirmation } from '@/utils/feedback'

it('keeps built-in confirmation controls in the UI language after a language change', async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', meta: { public: true },
    component: defineComponent({ setup: () => () => h('div', 'Public page') }) }] })
  await router.push('/')
  await router.isReady()
  const previousLocale = i18n.global.locale.value
  i18n.global.locale.value = 'zh-CN'
  const wrapper = mount(App, { attachTo: document.body, global: { plugins: [router, i18n] } })
  try {
    for (const [locale, language] of [['zh-CN', zhCn], ['en', en]] as const) {
      i18n.global.locale.value = locale
      await nextTick()
      const title = `Confirmation ${locale}`
      const confirmation = confirmChoice({ title, message: 'Review this test action.' })
      await flushPromises()
      const dialog = new DOMWrapper(document.body).findAll('[role="dialog"]').find(item => item.text().includes(title))!
      const close = dialog.get('button[aria-label]')
      expect(close.attributes('aria-label')).toBe(language.el.messagebox.close)
      await close.trigger('click')
      expect(await confirmation).toBe(false)
    }
  } finally {
    dismissOpenConfirmation()
    ElMessageBox.close()
    wrapper.unmount()
    i18n.global.locale.value = previousLocale
  }
})
