// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import { i18n } from '@/assets/i18n'
import AccountDeleteDialog from '../AccountDeleteDialog.vue'

/* Addressed by its test id, not by a styling class: the confirm button's appearance classes come from the
   shared dialog layer and change with it, while this contract is about the gate, not the paint. */
const CONFIRM = '[data-testid="account-delete-confirm"]'

const mountDialog = () => mount(AccountDeleteDialog, {
  props: {
    visible: true,
    username: 'alice',
    phone: '13800138000',
    loading: false
  },
  global: {
    plugins: [i18n],
    stubs: { Teleport: true }
  }
})

const confirmationField = 'input[name="delete-account-confirmation"]'

/**
 * Edits the field the way a real user does: an `InputEvent` carrying an `inputType`. Programmatic
 * autofill assigns `.value` and fires a plain `Event` with no `inputType`, which `setValue`
 * reproduces — so the two cases stay distinguishable here rather than assumed equivalent.
 */
const typeInto = async (element: Element, value: string) => {
  (element as HTMLInputElement).value = value
  element.dispatchEvent(new InputEvent('input', { inputType: 'insertText', bubbles: true }))
  await Promise.resolve()
}

describe('AccountDeleteDialog confirmation gate', () => {
  it('keeps a localized action name while deletion is pending without enabling a second submission', async () => {
    const previousLocale = i18n.global.locale.value
    const wrapper = mountDialog()
    try {
      await wrapper.setProps({ loading: true })
      for (const language of ['en', 'zh-CN'] as const) {
        i18n.global.locale.value = language
        await wrapper.vm.$nextTick()
        const button = wrapper.get(CONFIRM)
        expect(button.text()).toBe(i18n.global.t('app.deleting'))
        expect(button.attributes('aria-busy')).toBe('true')
        expect(button.attributes('disabled')).toBeDefined()
        await wrapper.get('form').trigger('submit')
        expect(wrapper.emitted('confirm')).toBeUndefined()
      }
      await wrapper.setProps({ loading: false })
      expect(wrapper.get(CONFIRM).text()).toBe(i18n.global.t('app.deleteAccountConfirm'))
      expect(wrapper.get(CONFIRM).attributes('disabled')).toBeDefined()
    } finally { wrapper.unmount(); i18n.global.locale.value = previousLocale }
  })

  it('keeps the irreversible consequences and exact confirmation requirements without repeated prompts', () => {
    const wrapper = mountDialog()
    expect(wrapper.find('.iot-dialog__subtitle').exists()).toBe(false)
    expect(wrapper.get('.account-delete-warning span:last-child').text()).toBe(i18n.global.t('app.deleteAccountDataWarning'))
    expect(wrapper.get('[data-testid="account-delete-export-note"]').text()).toContain(i18n.global.t('app.deleteAccountExportHint'))
    expect(wrapper.get('.account-delete-field small').text()).toBe(i18n.global.t('app.deleteAccountConfirmationHint'))
    const descriptionId = wrapper.get(confirmationField).attributes('aria-describedby')
    expect(descriptionId).toBe('account-delete-confirmation-hint')
    expect(wrapper.get(`#${descriptionId}`).text()).toBe(i18n.global.t('app.deleteAccountConfirmationHint'))
    expect(wrapper.get('[data-testid="account-delete-password"]').element.parentElement?.textContent?.trim()).toBe(i18n.global.t('account.currentPassword'))
    for (const input of wrapper.findAll('input')) expect(input.attributes('placeholder')).toBeUndefined()
    wrapper.unmount()
  })

  it('refuses a confirmation the user did not enter', async () => {
    const wrapper = mountDialog()
    // `setValue` fires a plain `input` Event with no `inputType`, as autofill does.
    await wrapper.get(confirmationField).setValue('alice')
    await wrapper.get('input[type="password"]').setValue('Password123')

    expect(wrapper.get(CONFIRM).attributes('disabled')).toBe('')
    wrapper.unmount()
  })

  it('accepts text the user typed, including on keyboards that report no printable key', async () => {
    const wrapper = mountDialog()
    // Android soft keyboards report `key` as `Unidentified`, and dropped text fires no keydown at
    // all; both still produce an InputEvent. Gating on printable keys disabled the button forever.
    await typeInto(wrapper.get(confirmationField).element, 'alice')
    await wrapper.get('input[type="password"]').setValue('Password123')

    expect(wrapper.get(CONFIRM).attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('normalizes the confirmation it emits', async () => {
    const wrapper = mountDialog()
    await typeInto(wrapper.get(confirmationField).element, '  alice  ')
    await wrapper.get('input[type="password"]').setValue('Password123')

    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('confirm')).toEqual([[
      { password: 'Password123', confirmation: 'alice' }
    ]])
    wrapper.unmount()
  })

  it('keeps raw rejection diagnostics in a closed disclosure and clears them on correction or cancel', async () => {
    const wrapper = mountDialog()
    try {
      await wrapper.setProps({ failure: {
        message: 'The account was not deleted.',
        diagnostics: { cause: 'Current password is incorrect.', details: ['HTTP 400'],
          fields: { password: 'Current password is incorrect.' } }
      } })
      const rejection = wrapper.get('[data-testid="account-delete-rejection"]')
      expect(rejection.find('[data-testid="failure-fields"]').exists()).toBe(false)
      expect(rejection.get('[data-testid="failure-technical-details"]').attributes('open')).toBeUndefined()
      expect(rejection.get('[data-testid="failure-technical-details"]').text()).toContain('password: Current password is incorrect.')
      expect(rejection.find('[data-testid="failure-cause"]').exists()).toBe(false)
      await wrapper.get('input[type="password"]').setValue('CorrectedPassword123')
      expect(wrapper.emitted('clear-failure')).toHaveLength(1)
      await typeInto(wrapper.get(confirmationField).element, 'alice')
      expect(wrapper.emitted('clear-failure')).toHaveLength(2)
      await wrapper.get('[data-testid="account-delete-cancel"]').trigger('click')
      expect(wrapper.emitted('clear-failure')).toHaveLength(3)
      expect(wrapper.get<HTMLInputElement>('input[type="password"]').element.value).toBe('')
    } finally { wrapper.unmount() }
  })
})
