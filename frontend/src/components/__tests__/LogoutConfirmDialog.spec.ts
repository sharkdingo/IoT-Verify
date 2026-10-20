import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import { i18n } from '@/assets/i18n'
import LogoutConfirmDialog from '../LogoutConfirmDialog.vue'

let wrapper: ReturnType<typeof mount>
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
})

describe('logout confirmation focus', () => {
  it('keeps a localized accessible action name and blocks repeat actions while signing out', async () => {
    const previousLocale = i18n.global.locale.value
    wrapper = mount(LogoutConfirmDialog, {
      props: { visible: true, loading: true },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    try {
      for (const language of ['en', 'zh-CN'] as const) {
        i18n.global.locale.value = language
        await nextTick()
        const button = wrapper.get('[data-testid="logout-confirm"]')
        expect(button.text()).toBe(i18n.global.t('account.signingOut'))
        expect(button.text()).not.toContain('account.')
        expect(button.attributes('aria-busy')).toBe('true')
        expect(button.attributes('disabled')).toBeDefined()
        await button.trigger('click')
        expect(wrapper.emitted('confirm')).toBeUndefined()
      }
      await wrapper.setProps({ loading: false })
      expect(wrapper.get('[data-testid="logout-confirm"]').text()).toBe(i18n.global.t('app.logout'))
    } finally { i18n.global.locale.value = previousLocale }
  })

  it('starts on Cancel and restores the opener on Escape without entering account deletion', async () => {
    const opener = document.createElement('button')
    document.body.append(opener)
    opener.focus()
    wrapper = mount(LogoutConfirmDialog, {
      props: { visible: true }, attachTo: document.body,
      global: { plugins: [i18n], stubs: { transition: false } }
    })
    await nextTick()
    expect(document.querySelector('[data-testid="open-account-delete"]')).toBeNull()
    expect(document.querySelector('[data-testid="logout-dialog"]')!.querySelectorAll('button')).toHaveLength(2)
    expect(document.querySelector('.iot-dialog__body')?.textContent).toBe(i18n.global.t('account.sessionDescription'))
    const cancel = Array.from(document.querySelectorAll('button')).find(button => button.textContent?.trim() === i18n.global.t('app.cancel'))!
    expect(document.activeElement).toBe(cancel)
    cancel.click()
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    expect(wrapper.emitted('delete-account')).toBeUndefined()

    cancel.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await wrapper.setProps({ visible: false })
    expect(document.activeElement).toBe(opener)
  })
})
