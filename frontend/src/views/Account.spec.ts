// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n, setLocale } from '@/assets/i18n'
import { useAuth } from '@/stores/auth'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import Account, { isAccountDeletionOutcomeUncertain } from './Account.vue'

const mocks = vi.hoisted(() => ({
  getAccount: vi.fn(), updateUsername: vi.fn(), changePassword: vi.fn(),
  logout: vi.fn(), deleteAccount: vi.fn()
}))
const feedback = vi.hoisted(() => ({ confirmChoice: vi.fn(), notifyBlocked: vi.fn(), notifyError: vi.fn(), notifySuccess: vi.fn() }))
vi.mock('@/api/auth', () => ({ authApi: mocks }))
vi.mock('@/utils/feedback', async importOriginal => ({
  ...await importOriginal<typeof import('@/utils/feedback')>(),
  ...feedback
}))
const RETURN_PATH = '/board?run=verification:7&trace=15'
const routers = new WeakMap<ReturnType<typeof mount>, Router>()

const auth = useAuth()
const token = (signature = 'alice') => `header.${btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 })).replace(/=/g, '')}.${signature}`
const alice = { userId: 7, phone: '13800138000', username: 'alice' }
const profile = { ...alice, createdAt: '2026-10-01T08:00:00+08:00' }
const rejection = (status: number, reasonCode: string) => ({ response: { status, data: { data: { reasonCode } } } })
const deferred = <T,>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
const wrappers: ReturnType<typeof mount>[] = []
beforeEach(() => {
  vi.clearAllMocks()
  mocks.getAccount.mockResolvedValue({ code: 200, data: profile })
  mocks.updateUsername.mockResolvedValue({ code: 200, data: { ...profile, username: 'new-alice' } })
  mocks.changePassword.mockResolvedValue({ code: 200, data: null })
  mocks.logout.mockResolvedValue({ code: 200, data: null })
  mocks.deleteAccount.mockResolvedValue({ code: 200, data: null })
  feedback.confirmChoice.mockResolvedValue(true)
  setLocale('en')
  auth.login(token(), alice)
})
afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  auth.logout()
  document.body.innerHTML = ''
  setLocale('zh-CN')
})
const mountAccount = async (prepareChatForLogout = vi.fn().mockResolvedValue('ready')) => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/account', component: Account, props: { prepareChatForLogout } },
      { path: '/board', component: { template: '<main data-testid="board-stub">Board</main>' } },
      { path: '/', component: { template: '<main data-testid="login-stub">Login</main>' } }
    ]
  })
  await router.push(RETURN_PATH)
  await router.push({ path: '/account', query: { returnTo: RETURN_PATH } })
  await router.isReady()
  const wrapper = mount(RouterView, {
    attachTo: document.body,
    global: { plugins: [i18n, router], stubs: { Teleport: true, Transition: false, ThemeToggle: true, LanguageToggle: true } }
  })
  routers.set(wrapper, router)
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

const usernameForm = async (wrapper: ReturnType<typeof mount>, name = 'new-alice') => {
  if (!wrapper.find('#account-username').exists()) {
    await wrapper.get('[data-testid="account-edit-username"]').trigger('click')
    await flushPromises()
  }
  await wrapper.get('#account-username').setValue(name)
  await wrapper.get('[data-testid="account-username-form"]').trigger('submit')
  await flushPromises()
  await wrapper.get('#account-username-password').setValue('Password123')
}
const passwordForm = async (wrapper: ReturnType<typeof mount>) => {
  if (!wrapper.find('#account-current-password').exists()) {
    await wrapper.get('[data-testid="account-edit-password"]').trigger('click')
    await flushPromises()
  }
  await wrapper.get('#account-current-password').setValue('Password123')
  await wrapper.get('#account-new-password').setValue('Different123')
  await wrapper.get('#account-confirm-password').setValue('Different123')
}
const deleteForm = async (wrapper: ReturnType<typeof mount>) => {
  await wrapper.get('[data-testid="open-account-delete"]').trigger('click')
  await flushPromises()
  await vi.waitFor(() => expect(wrapper.find('[data-testid="account-delete-confirmation"]').exists()).toBe(true))
  const confirmation = wrapper.get<HTMLInputElement>('[data-testid="account-delete-confirmation"]')
  confirmation.element.value = alice.username
  confirmation.element.dispatchEvent(new InputEvent('input', { inputType: 'insertText', bubbles: true }))
  await wrapper.get('[data-testid="account-delete-password"]').setValue('Password123')
  await wrapper.get('[data-testid="account-delete-dialog"]').trigger('submit')
  await flushPromises()
}

describe('Account page', () => {
  it.each(['username', 'password'] as const)('keeps a rejected %s request localized with one technical disclosure through retry', async editor => {
    const api = mocks[editor === 'username' ? 'updateUsername' : 'changePassword']
    api.mockRejectedValueOnce({ response: { status: 422, data: {
      message: 'Credential policy rejected this request',
      data: { errors: { currentPassword: 'Credential policy rejected this request' } }
    } } })
    const wrapper = await mountAccount()
    if (editor === 'username') await usernameForm(wrapper)
    else await passwordForm(wrapper)
    const submit = () => editor === 'username'
      ? wrapper.get('[data-testid="account-confirm-username"]').trigger('click')
      : wrapper.get('[data-testid="account-password-form"]').trigger('submit')
    await submit()
    await flushPromises()
    for (const language of ['en', 'zh-CN'] as const) {
      setLocale(language)
      await flushPromises()
      const alert = wrapper.get(`[data-testid="account-${editor}-error"]`)
      expect(alert.get('p').text()).toBe(i18n.global.t(`account.${editor === 'username' ? 'usernameSaveFailed' : 'passwordSaveFailed'}`))
      const details = alert.get('[data-testid="failure-technical-details"]')
      expect(details.attributes('open')).toBeUndefined()
      expect(details.text().match(/Credential policy rejected this request/g)).toHaveLength(1)
      expect(details.text()).toContain('currentPassword: Credential policy rejected this request')
      expect(details.text()).toContain('HTTP 422')
      expect(alert.find('[data-testid="failure-cause"]').exists()).toBe(false)
      expect(alert.find('[data-testid="failure-fields"]').exists()).toBe(false)
      expect(api).toHaveBeenCalledOnce()
      expect(auth.state.isLoggedIn).toBe(true)
    }
    await wrapper.get(editor === 'username' ? '#account-username-password' : '#account-current-password').setValue('CorrectedPassword123')
    expect(wrapper.find(`[data-testid="account-${editor}-error"]`).exists()).toBe(false)
    await submit()
    await flushPromises()
    expect(api).toHaveBeenCalledTimes(2)
    if (editor === 'username') expect(wrapper.get('[data-testid="account-username-value"]').text()).toBe('new-alice')
    else expect(routers.get(wrapper)!.currentRoute.value.query.reason).toBe('password-changed')
    expect(feedback.notifyError).not.toHaveBeenCalled()
  })

  it('keeps a failed profile read distinct from empty details and replaces its diagnostic on successful retry', async () => {
    mocks.getAccount.mockRejectedValueOnce({ response: { status: 503, data: {
      message: 'Profile store unavailable', data: { errors: { profile: 'Profile store unavailable' } }
    } } })
    const wrapper = await mountAccount()
    for (const language of ['en', 'zh-CN'] as const) {
      setLocale(language)
      await flushPromises()
      const alert = wrapper.get('[data-testid="account-load-error"]')
      expect(alert.get('p').text()).toBe(i18n.global.t('account.loadFailed'))
      const details = alert.get('[data-testid="failure-technical-details"]')
      expect(details.attributes('open')).toBeUndefined()
      expect(details.text().match(/Profile store unavailable/g)).toHaveLength(1)
      expect(details.text()).toContain('profile: Profile store unavailable')
      expect(wrapper.find('[data-testid="account-username-value"]').exists()).toBe(false)
      expect(wrapper.get('[data-testid="account-edit-password"]').attributes('disabled')).toBeDefined()
      expect(mocks.getAccount).toHaveBeenCalledOnce()
    }
    await wrapper.get('[data-testid="account-reload"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="account-load-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="failure-technical-details"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="account-username-value"]').text()).toBe('alice')
    expect(mocks.getAccount).toHaveBeenCalledTimes(2)
    expect(feedback.notifyError).not.toHaveBeenCalled()
  })

  it('retains failed reconciliation diagnostics after an uncertain rename and reloads without repeating the write', async () => {
    const wrapper = await mountAccount()
    await usernameForm(wrapper)
    mocks.updateUsername.mockRejectedValueOnce(new Error('Save response was lost'))
    mocks.getAccount.mockRejectedValueOnce(new Error('Profile reconciliation could not connect'))
    await wrapper.get('[data-testid="account-confirm-username"]').trigger('click')
    await flushPromises()
    for (const language of ['en', 'zh-CN'] as const) {
      setLocale(language)
      await flushPromises()
      const alert = wrapper.get('[data-testid="account-load-error"]')
      expect(alert.get('p').text()).toBe(i18n.global.t('account.usernameOutcomeUnknown'))
      expect(alert.get('[data-testid="failure-technical-details"]').text()).toContain('Profile reconciliation could not connect')
      expect(wrapper.get('[data-testid="account-save-username"]').attributes('disabled')).toBeDefined()
      expect(wrapper.get('[data-testid="account-save-username"]').attributes('aria-describedby')).toBe('account-load-error')
      expect(mocks.updateUsername).toHaveBeenCalledOnce()
    }
    mocks.getAccount.mockResolvedValueOnce({ code: 200, data: { ...profile, username: 'new-alice' } })
    await wrapper.get('[data-testid="account-reload"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="account-load-error"]').exists()).toBe(false)
    expect(wrapper.get<HTMLInputElement>('#account-username').element.value).toBe('new-alice')
    expect(auth.state.user?.username).toBe('new-alice')
    expect(mocks.updateUsername).toHaveBeenCalledOnce()
    expect(feedback.notifySuccess).not.toHaveBeenCalled()
  })

  it.each(['username', 'password'] as const)('updates a rejected %s edit when the language changes without resubmitting', async editor => {
    mocks[editor === 'username' ? 'updateUsername' : 'changePassword'].mockRejectedValueOnce(rejection(400, 'CURRENT_PASSWORD_INCORRECT'))
    const wrapper = await mountAccount()
    if (editor === 'username') {
      await usernameForm(wrapper)
      await wrapper.get('[data-testid="account-confirm-username"]').trigger('click')
    } else {
      await passwordForm(wrapper)
      await wrapper.get('[data-testid="account-password-form"]').trigger('submit')
    }
    await flushPromises()
    const error = wrapper.get(`[data-testid="account-${editor}-error"]`)
    expect(error.get('p').text()).toBe(i18n.global.t('account.currentPasswordIncorrect'))
    setLocale('zh-CN')
    await flushPromises()
    expect(wrapper.get(`[data-testid="account-${editor}-error"]`).get('p').text()).toBe(i18n.global.t('account.currentPasswordIncorrect'))
    expect(mocks[editor === 'username' ? 'updateUsername' : 'changePassword']).toHaveBeenCalledOnce()
    expect(auth.state.isLoggedIn).toBe(true)
  })

  it.each(['username', 'password'] as const)('uses the profile-load error as the single disabled-action explanation during a %s edit', async editor => {
    const wrapper = await mountAccount()
    await wrapper.get(`[data-testid="account-edit-${editor}"]`).trigger('click')
    mocks.getAccount.mockRejectedValueOnce(new Error('Profile request failed'))
    await wrapper.get('[data-testid="account-refresh-profile"]').trigger('click')
    await flushPromises()
    const save = wrapper.get(`[data-testid="account-save-${editor}"]`)
    expect(save.attributes('disabled')).toBeDefined()
    expect(save.attributes('aria-describedby')?.split(' ')).toContain('account-load-error')
    expect(wrapper.get('#account-load-error').text()).toContain(i18n.global.t('account.loadFailed'))
    expect(wrapper.text()).not.toContain(i18n.global.t('account.profileRequired'))
    setLocale('zh-CN')
    await flushPromises()
    expect(wrapper.get('#account-load-error').text()).toContain(i18n.global.t('account.loadFailed'))
    expect(wrapper.find(`[data-testid="account-${editor}-form"]`).exists()).toBe(true)
    expect(mocks.getAccount).toHaveBeenCalledTimes(2)
  })

  it('updates an open deletion rejection when the language changes while retaining the diagnostic cause', async () => {
    const cause = 'Current password is incorrect.'
    mocks.deleteAccount.mockRejectedValueOnce({ response: { status: 400, data: { message: cause } } })
    const wrapper = await mountAccount()
    await deleteForm(wrapper)
    setLocale('zh-CN')
    await flushPromises()
    const rejection = wrapper.get('[data-testid="account-delete-rejection"]')
    expect(rejection.get('p').text()).toBe(i18n.global.t('app.deleteAccountFailed'))
    expect(rejection.get('[data-testid="failure-technical-details"]').text()).toContain(cause)
    expect(mocks.deleteAccount).toHaveBeenCalledOnce()
    expect(auth.state.isLoggedIn).toBe(true)
  })

  it.each([undefined, 9])('keeps account-change capacity feedback distinct from excessive attempts with delay %s', async seconds => {
    mocks.changePassword.mockRejectedValueOnce({ response: { status: 429, data: { data: {
      reasonCode: 'AUTH_ACCOUNT_CHANGE_RATE_LIMIT_REACHED', scope: 'CAPACITY', retryAfterSeconds: seconds
    } } } })
    const wrapper = await mountAccount()
    await passwordForm(wrapper)
    await wrapper.get('[data-testid="account-password-form"]').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[data-testid="account-password-error"]').get('p').text()).toBe(seconds
      ? i18n.global.t('auth.rateLimitCapacityReached', { seconds })
      : i18n.global.t('auth.rateLimitCapacityReachedGeneric'))
    expect(auth.state.isLoggedIn).toBe(true)
  })

  it('loads authoritative details and normalizes a password-confirmed username change', async () => {
    const wrapper = await mountAccount()
    expect(wrapper.find('[data-testid="account-page"]').exists()).toBe(true)
    expect(wrapper.findAll('h1')).toHaveLength(1)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    const originalToken = auth.getToken()
    expect(mocks.getAccount).toHaveBeenCalledWith(originalToken)
    expect(wrapper.get('[data-testid="account-phone"]').text()).toBe(alice.phone)
    expect(wrapper.find('input').exists()).toBe(false)
    expect(wrapper.find('[data-testid="account-username-form"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="account-password-form"]').exists()).toBe(false)
    expect(wrapper.text()).not.toMatch(/Account ID|Joined|\b7\b|2026|accountId|createdAt/)
    expect(wrapper.find('aside').exists()).toBe(false)
    expect(wrapper.get('[data-testid="account-username-value"]').text()).toBe('alice')
    expect(wrapper.get('[data-testid="account-username-value"]').element.parentElement?.textContent?.trim()).toBe('alice')
    await wrapper.get('[data-testid="account-edit-username"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="account-save-username"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('#account-username-blocked').text()).toBe(i18n.global.t('account.usernameUnchanged'))

    await usernameForm(wrapper, '  new-alice  ')
    await wrapper.get('[data-testid="account-confirm-username-dialog"]').trigger('submit')
    await flushPromises()

    expect(mocks.updateUsername).toHaveBeenCalledWith({ username: 'new-alice', currentPassword: 'Password123', expectedUsername: 'alice' }, originalToken)
    expect(auth.state.user?.username).toBe('new-alice')
    expect(auth.getToken()).toBe(originalToken)
    expect(wrapper.find('#account-username-password').exists()).toBe(false)
    expect(wrapper.get('[data-testid="account-username-value"]').text()).toBe('new-alice')
    expect(wrapper.find('[data-testid="account-username-form"]').exists()).toBe(false)
  })

  it('validates the name before requesting a password and submits only after identity confirmation', async () => {
    const wrapper = await mountAccount()
    await wrapper.get('[data-testid="account-edit-username"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('#account-username-password').exists()).toBe(false)
    await wrapper.get('#account-username').setValue('13800138000')
    expect(wrapper.get('[data-testid="account-save-username"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="account-username-form"]').findAll('p')).toHaveLength(1)
    expect(wrapper.get('#account-username-blocked').text()).toBe(i18n.global.t('account.usernamePhoneReserved'))
    expect(wrapper.get('#account-username').attributes('aria-describedby')).toBe('account-username-blocked')
    await wrapper.get('[data-testid="account-username-form"]').trigger('submit')
    expect(wrapper.find('[data-testid="account-confirm-username-dialog"]').exists()).toBe(false)
    await wrapper.get('#account-username').setValue('approved-name')
    expect(wrapper.get('[data-testid="account-save-username"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('#account-username-blocked').exists()).toBe(false)
    expect(wrapper.get('#account-username').attributes('aria-describedby')).toBeUndefined()
    await wrapper.get('[data-testid="account-username-form"]').trigger('submit')
    await flushPromises()
    const confirmation = wrapper.get('[data-testid="account-confirm-username-dialog"]')
    expect(confirmation.attributes('role')).toBe('dialog')
    expect(confirmation.attributes('aria-modal')).toBe('true')
    expect(confirmation.element.contains(document.activeElement)).toBe(true)
    expect(wrapper.get('[data-testid="account-confirm-username"]').attributes('disabled')).toBeDefined()
    await confirmation.trigger('submit')
    expect(mocks.updateUsername).not.toHaveBeenCalled()
    await wrapper.get('#account-username-password').setValue('Password123')
    await confirmation.trigger('submit')
    await flushPromises()
    expect(mocks.updateUsername).toHaveBeenCalledOnce()
    expect(mocks.updateUsername).toHaveBeenCalledWith({ username: 'approved-name', currentPassword: 'Password123', expectedUsername: 'alice' }, auth.getToken())
  })

  it('cancels identity confirmation without writing or retaining its password, while keeping the name draft', async () => {
    const wrapper = await mountAccount()
    await usernameForm(wrapper, 'reviewed-draft')
    await wrapper.get('[data-testid="account-cancel-username-confirmation"]').trigger('click')
    await flushPromises()
    expect(mocks.updateUsername).not.toHaveBeenCalled()
    expect(wrapper.find('#account-username-password').exists()).toBe(false)
    expect(wrapper.get<HTMLInputElement>('#account-username').element.value).toBe('reviewed-draft')
    await wrapper.get('[data-testid="account-username-form"]').trigger('submit')
    await flushPromises()
    expect(wrapper.get<HTMLInputElement>('#account-username-password').element.value).toBe('')
    await wrapper.get('[data-testid="account-cancel-username-confirmation"]').trigger('click')
    await wrapper.get('[data-testid="account-cancel-username"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="account-username-form"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="account-username-value"]').text()).toBe('alice')
    expect(mocks.updateUsername).not.toHaveBeenCalled()
  })

  it('uses the candidate and CAS baseline presented in the identity confirmation', async () => {
    const wrapper = await mountAccount()
    const requestToken = auth.getToken()
    await usernameForm(wrapper, 'approved-name')
    await wrapper.get('#account-username').setValue('later-draft')
    const remoteUser = { ...alice, username: 'other-tab' }
    const newValue = JSON.stringify({ token: requestToken, user: remoteUser })
    localStorage.setItem('iot_verify_user', JSON.stringify(remoteUser))
    localStorage.setItem('iot_verify_auth_sync', newValue)
    window.dispatchEvent(new StorageEvent('storage', { key: 'iot_verify_auth_sync', storageArea: localStorage, newValue }))
    await wrapper.get('[data-testid="account-confirm-username-dialog"]').trigger('submit')
    await flushPromises()
    expect(mocks.updateUsername).toHaveBeenCalledWith({ username: 'approved-name', expectedUsername: 'alice', currentPassword: 'Password123' }, requestToken)
  })

  it('reloads a remotely changed profile when starting a new edit, but does not rebase an active draft', async () => {
    const wrapper = await mountAccount()
    const remoteUser = { ...alice, username: 'other-tab' }
    const newValue = JSON.stringify({ token: auth.getToken(), user: remoteUser })
    localStorage.setItem('iot_verify_user', JSON.stringify(remoteUser))
    localStorage.setItem('iot_verify_auth_sync', newValue)
    window.dispatchEvent(new StorageEvent('storage', { key: 'iot_verify_auth_sync', storageArea: localStorage, newValue }))
    mocks.getAccount.mockResolvedValue({ code: 200, data: { ...profile, username: 'other-tab' } })
    await wrapper.get('[data-testid="account-edit-username"]').trigger('click')
    await flushPromises()
    expect(mocks.getAccount).toHaveBeenCalledTimes(2)
    expect(wrapper.get<HTMLInputElement>('#account-username').element.value).toBe('other-tab')
    await usernameForm(wrapper, 'next-name')
    await wrapper.get('[data-testid="account-confirm-username-dialog"]').trigger('submit')
    await flushPromises()
    expect(mocks.updateUsername.mock.calls[0][0].expectedUsername).toBe('other-tab')
  })

  it('clears password values and visibility when cancelling or switching the active editor', async () => {
    const wrapper = await mountAccount()
    await passwordForm(wrapper)
    await wrapper.get('[aria-pressed="false"]').trigger('click')
    expect(wrapper.get('#account-current-password').attributes('type')).toBe('text')
    await wrapper.get('[data-testid="account-cancel-password"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="account-password-form"]').exists()).toBe(false)
    expect(mocks.changePassword).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="account-edit-password"]').trigger('click')
    await flushPromises()
    for (const id of ['account-current-password', 'account-new-password', 'account-confirm-password']) {
      expect(wrapper.get<HTMLInputElement>(`#${id}`).element.value).toBe('')
      expect(wrapper.get(`#${id}`).attributes('type')).toBe('password')
    }
    await passwordForm(wrapper)
    await wrapper.get('[data-testid="account-edit-username"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="account-password-form"]').exists()).toBe(false)
    expect(wrapper.find('#account-username-password').exists()).toBe(false)
    expect(mocks.changePassword).not.toHaveBeenCalled()
  })

  it('clears an unsubmitted identity confirmation when its account is replaced', async () => {
    const wrapper = await mountAccount()
    await usernameForm(wrapper, 'alice-private-draft')
    const bob = { userId: 8, phone: '13900139000', username: 'bob' }
    mocks.getAccount.mockResolvedValue({ code: 200, data: { ...bob, createdAt: profile.createdAt } })
    auth.login(token('bob'), bob)
    await flushPromises()
    expect(wrapper.find('[data-testid="account-confirm-username-dialog"]').exists()).toBe(false)
    expect(wrapper.find('input').exists()).toBe(false)
    expect(wrapper.get('[data-testid="account-username-value"]').text()).toBe('bob')
    expect(wrapper.get('[data-testid="account-phone"]').text()).toBe(bob.phone)
    expect(mocks.updateUsername).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="account-edit-username"]').trigger('click')
    await flushPromises()
    expect(wrapper.get<HTMLInputElement>('#account-username').element.value).toBe('bob')
    await wrapper.get('#account-username').setValue('bob-reviewed')
    await wrapper.get('[data-testid="account-username-form"]').trigger('submit')
    await flushPromises()
    expect(wrapper.get<HTMLInputElement>('#account-username-password').element.value).toBe('')
    expect(mocks.updateUsername).not.toHaveBeenCalled()
  })

  it('keeps an edited draft and its baseline when another tab changes the name', async () => {
    const wrapper = await mountAccount()
    await usernameForm(wrapper)
    const remoteUser = { ...alice, username: 'other-tab' }
    const newValue = JSON.stringify({ token: auth.getToken(), user: remoteUser })
    localStorage.setItem('iot_verify_user', JSON.stringify(remoteUser))
    localStorage.setItem('iot_verify_auth_sync', newValue)
    window.dispatchEvent(new StorageEvent('storage', {
      key: 'iot_verify_auth_sync', storageArea: localStorage,
      newValue
    }))
    expect(auth.state.user?.username).toBe('other-tab')
    mocks.updateUsername.mockRejectedValue(rejection(409, 'ACCOUNT_PROFILE_CHANGED'))
    await wrapper.get('[data-testid="account-confirm-username-dialog"]').trigger('submit')
    await flushPromises()

    expect(mocks.updateUsername.mock.calls[0][0].expectedUsername).toBe('alice')
    expect(wrapper.get<HTMLInputElement>('#account-username').element.value).toBe('new-alice')
    expect(wrapper.get('[data-testid="account-username-error"]').text()).toContain('Refresh')
    mocks.getAccount.mockResolvedValue({ code: 200, data: { ...profile, username: 'other-tab' } })
    await wrapper.get('[data-testid="account-refresh-profile"]').trigger('click')
    await flushPromises()
    await usernameForm(wrapper, 'reviewed-alice')
    await wrapper.get('[data-testid="account-confirm-username-dialog"]').trigger('submit')
    expect(mocks.updateUsername.mock.calls[1][0].expectedUsername).toBe('other-tab')
  })

  it('keeps explicit refresh reachable after a stale edit error is cleared, without automatically retrying', async () => {
    const wrapper = await mountAccount()
    await usernameForm(wrapper, 'stale-draft')
    mocks.updateUsername.mockRejectedValue(rejection(409, 'ACCOUNT_PROFILE_CHANGED'))
    await wrapper.get('[data-testid="account-confirm-username-dialog"]').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[data-testid="account-username-error"]').text()).toContain('Refresh')
    await wrapper.get('#account-username').setValue('edited-after-conflict')
    expect(wrapper.find('[data-testid="account-username-error"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="account-save-username"]').attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('[data-testid="account-refresh-profile"]')).toHaveLength(1)
    const refresh = wrapper.get('[data-testid="account-refresh-profile"]')
    expect(refresh.attributes('disabled')).toBeUndefined()
    mocks.getAccount.mockResolvedValue({ code: 200, data: { ...profile, username: 'authoritative-name' } })
    await refresh.trigger('click')
    await flushPromises()
    expect(mocks.getAccount).toHaveBeenCalledTimes(2)
    expect(mocks.updateUsername).toHaveBeenCalledOnce()
    expect(wrapper.get<HTMLInputElement>('#account-username').element.value).toBe('authoritative-name')
    expect(wrapper.find('[data-testid="account-confirm-username-dialog"]').exists()).toBe(false)
    mocks.updateUsername.mockResolvedValue({ code: 200, data: { ...profile, username: 'reviewed-retry' } })
    await usernameForm(wrapper, 'reviewed-retry')
    expect(mocks.updateUsername).toHaveBeenCalledOnce()
    await wrapper.get('[data-testid="account-confirm-username-dialog"]').trigger('submit')
    await flushPromises()
    expect(mocks.updateUsername).toHaveBeenCalledTimes(2)
    expect(mocks.updateUsername.mock.calls[1][0]).toEqual({
      username: 'reviewed-retry', expectedUsername: 'authoritative-name', currentPassword: 'Password123'
    })
  })

  it('keeps password rejection inline without ending the session', async () => {
    const wrapper = await mountAccount()
    await usernameForm(wrapper)
    mocks.updateUsername.mockRejectedValue(rejection(400, 'CURRENT_PASSWORD_INCORRECT'))
    await wrapper.get('[data-testid="account-confirm-username-dialog"]').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[data-testid="account-username-error"]').text()).toContain('current password is incorrect')
    expect(wrapper.get('#account-username-password').attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('#account-username-password').attributes('aria-describedby')).toBe('account-username-error')
    expect(auth.state.isLoggedIn).toBe(true)
    expect(routers.get(wrapper)!.currentRoute.value.path).toBe('/account')
  })

  it('associates a taken username with its field', async () => {
    const wrapper = await mountAccount()
    await usernameForm(wrapper)
    mocks.updateUsername.mockRejectedValue(rejection(409, 'USERNAME_ALREADY_EXISTS'))
    await wrapper.get('[data-testid="account-confirm-username-dialog"]').trigger('submit')
    await flushPromises()
    expect(wrapper.get('#account-username').attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('#account-username').attributes('aria-describedby')).toContain('account-username-error')
    expect(wrapper.find('#account-username-password').exists()).toBe(false)
    expect(wrapper.find('[data-testid="account-confirm-username-dialog"]').exists()).toBe(false)
  })

  it('reconciles an unconfirmed username mutation instead of reporting success', async () => {
    const wrapper = await mountAccount()
    await usernameForm(wrapper)
    mocks.updateUsername.mockRejectedValue(new Error('connection dropped after commit'))
    mocks.getAccount.mockResolvedValue({ code: 200, data: { ...profile, username: 'new-alice' } })
    await wrapper.get('[data-testid="account-confirm-username-dialog"]').trigger('submit')
    await flushPromises()
    expect(auth.state.user?.username).toBe('new-alice')
    expect(wrapper.get('[data-testid="account-username-error"]').text()).toContain('not confirmed')
    expect(wrapper.get('[data-testid="account-username-error"]').classes()).toContain('account-notice--warning')
    expect(wrapper.get('[data-testid="account-username-error"]').classes()).not.toContain('account-notice--error')
    expect(wrapper.find('[data-testid="account-username-saved"]').exists()).toBe(false)
    setLocale('zh-CN')
    await flushPromises()
    expect(wrapper.get('[data-testid="account-username-error"]').get('p').text()).toBe(i18n.global.t('account.usernameOutcomeRefreshed'))
    expect(feedback.notifySuccess).not.toHaveBeenCalled()
  })

  it('does not accept malformed or foreign profile data', async () => {
    mocks.getAccount.mockResolvedValue({ code: 200, data: { ...profile, userId: 8 } })
    const wrapper = await mountAccount()
    expect(wrapper.get('[data-testid="account-load-error"]').text()).toContain('Could not load')
    expect(wrapper.find('[data-testid="account-username-form"]').exists()).toBe(false)
    expect(auth.state.user).toEqual(alice)
  })

  it('does not let a read from an unmounted page populate its replacement', async () => {
    const firstRead = deferred<{ code: number; data: typeof profile }>()
    mocks.getAccount.mockReturnValueOnce(firstRead.promise)
    const first = await mountAccount()
    first.unmount()
    wrappers.splice(wrappers.indexOf(first), 1)
    const replacement = await mountAccount()
    firstRead.resolve({ code: 200, data: { ...profile, username: 'stale-name' } })
    await flushPromises()
    expect(replacement.get('[data-testid="account-username-value"]').text()).toBe('alice')
    expect(auth.state.user?.username).toBe('alice')
  })

  it('does not overwrite a different session with a late username response', async () => {
    const mutation = deferred<{ code: number; data: typeof profile }>()
    mocks.updateUsername.mockReturnValue(mutation.promise)
    const wrapper = await mountAccount()
    await usernameForm(wrapper)
    await wrapper.get('[data-testid="account-confirm-username-dialog"]').trigger('submit')
    mocks.getAccount.mockResolvedValue({ code: 200, data: { ...profile, userId: 8, phone: '13900139000', username: 'bob' } })
    auth.login(token('bob'), { userId: 8, phone: '13900139000', username: 'bob' })
    await flushPromises()
    mutation.resolve({ code: 200, data: { ...profile, username: 'new-alice' } })
    await flushPromises()
    expect(auth.state.user?.username).toBe('bob')
    expect(wrapper.get('[data-testid="account-phone"]').text()).toBe('13900139000')
    expect(wrapper.get('[data-testid="account-username-value"]').text()).toBe('bob')
    expect(wrapper.find('#account-username').exists()).toBe(false)
    expect(routers.get(wrapper)!.currentRoute.value.path).toBe('/account')
  })

  it('blocks ordinary navigation until a username response is observed', async () => {
    const mutation = deferred<{ code: number; data: typeof profile }>()
    mocks.updateUsername.mockReturnValue(mutation.promise)
    const wrapper = await mountAccount()
    const router = routers.get(wrapper)!
    await usernameForm(wrapper)
    await wrapper.get('[data-testid="account-confirm-username-dialog"]').trigger('submit')
    expect(wrapper.get('[data-testid="account-cancel-username-confirmation"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="account-cancel-username"]').attributes('disabled')).toBeDefined()
    await wrapper.get('.iot-dialog-overlay--session').trigger('click')
    expect(document.body.style.overflow).toBe('hidden')
    expect(wrapper.find('[data-testid="account-confirm-username-dialog"]').exists()).toBe(true)
    expect(mocks.updateUsername).toHaveBeenCalledOnce()
    await router.push(RETURN_PATH)
    expect(router.currentRoute.value.path).toBe('/account')
    mutation.resolve({ code: 200, data: { ...profile, username: 'new-alice' } })
    await flushPromises()
    expect(wrapper.get('[data-testid="account-username-value"]').text()).toBe('new-alice')
    expect(wrapper.find('[data-testid="account-username-form"]').exists()).toBe(false)
    await wrapper.get('[data-testid="account-back"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe(RETURN_PATH)
  })

  it('clears secrets and old profile immediately when its session is replaced', async () => {
    const wrapper = await mountAccount()
    await usernameForm(wrapper)
    await wrapper.get('[data-testid="account-cancel-username-confirmation"]').trigger('click')
    await passwordForm(wrapper)
    mocks.getAccount.mockResolvedValue({ code: 200, data: { userId: 8, phone: '13900139000', username: 'bob', createdAt: profile.createdAt } })
    auth.login(token('bob'), { userId: 8, phone: '13900139000', username: 'bob' })
    await flushPromises()
    expect(wrapper.text()).not.toContain(alice.phone)
    expect(wrapper.get('[data-testid="account-username-value"]').text()).toBe('bob')
    expect(wrapper.find('input').exists()).toBe(false)
    expect(wrapper.find('[data-testid="account-confirm-username-dialog"]').exists()).toBe(false)
    await wrapper.get('[data-testid="account-edit-password"]').trigger('click')
    await flushPromises()
    for (const id of ['account-current-password', 'account-new-password', 'account-confirm-password']) {
      expect(wrapper.get<HTMLInputElement>(`#${id}`).element.value).toBe('')
    }
    await routers.get(wrapper)!.push('/board')
    expect(routers.get(wrapper)!.currentRoute.value.path).toBe('/board')
  })

  it('keeps sign-out separate from deletion and pins its initiating token', async () => {
    const prepare = vi.fn().mockResolvedValue('ready')
    const wrapper = await mountAccount(prepare)
    const requestToken = auth.getToken()
    await wrapper.get('[data-testid="account-logout"]').trigger('click')
    await flushPromises()
    const dialog = wrapper.get('[data-testid="logout-dialog"]')
    expect(dialog.findAll('button')).toHaveLength(2)
    expect(dialog.find('[data-testid="open-account-delete"]').exists()).toBe(false)
    await dialog.get('[data-testid="logout-confirm"]').trigger('click')
    await flushPromises()
    expect(prepare).toHaveBeenCalledOnce()
    expect(mocks.logout).toHaveBeenCalledWith(requestToken)
    expect(mocks.deleteAccount).not.toHaveBeenCalled()
    expect(auth.state.isLoggedIn).toBe(false)
    expect(routers.get(wrapper)!.currentRoute.value.query.redirect).toBe(RETURN_PATH)
  })

  it('cannot revoke or navigate a replacement session after sign-out preparation', async () => {
    const prepared = deferred<'ready'>()
    const wrapper = await mountAccount(vi.fn().mockReturnValue(prepared.promise))
    await wrapper.get('[data-testid="account-logout"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="logout-confirm"]').trigger('click')
    auth.login(token('bob'), { userId: 8, phone: '13900139000', username: 'bob' })
    prepared.resolve('ready')
    await flushPromises()
    expect(mocks.logout).not.toHaveBeenCalled()
    expect(auth.state.user?.username).toBe('bob')
    expect(routers.get(wrapper)!.currentRoute.value.path).toBe('/account')
  })

  it('keeps a replacement session signed in after the initiating logout response arrives', async () => {
    const response = deferred<{ code: number; data: null }>()
    mocks.logout.mockReturnValue(response.promise)
    const wrapper = await mountAccount()
    const requestToken = auth.getToken()
    await wrapper.get('[data-testid="account-logout"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="logout-confirm"]').trigger('click')
    await flushPromises()
    expect(mocks.logout).toHaveBeenCalledWith(requestToken)

    const bob = { userId: 8, phone: '13900139000', username: 'bob' }
    mocks.getAccount.mockResolvedValue({ code: 200, data: { ...bob, createdAt: profile.createdAt } })
    auth.login(token('bob'), bob)
    await flushPromises()
    await passwordForm(wrapper)
    await wrapper.get('#account-current-password').setValue('BobNewSecret123')
    response.resolve({ code: 200, data: null })
    await flushPromises()

    expect(auth.state.user?.username).toBe('bob')
    expect(auth.state.isLoggedIn).toBe(true)
    expect(routers.get(wrapper)!.currentRoute.value.path).toBe('/account')
    expect(wrapper.get<HTMLInputElement>('#account-current-password').element.value).toBe('BobNewSecret123')
  })

  it('abandons sign-out when its account page unmounts during preparation', async () => {
    const prepared = deferred<'ready'>()
    const wrapper = await mountAccount(vi.fn().mockReturnValue(prepared.promise))
    await wrapper.get('[data-testid="account-logout"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="logout-confirm"]').trigger('click')
    const replace = vi.spyOn(routers.get(wrapper)!, 'replace')
    wrapper.unmount()
    wrappers.splice(wrappers.indexOf(wrapper), 1)
    prepared.resolve('ready')
    await flushPromises()

    expect(mocks.logout).not.toHaveBeenCalled()
    expect(replace).not.toHaveBeenCalled()
    expect(auth.state.isLoggedIn).toBe(true)
  })

  it('preserves a refreshed credential for the same account after sign-out preparation', async () => {
    const prepared = deferred<'ready'>()
    const wrapper = await mountAccount(vi.fn().mockReturnValue(prepared.promise))
    await wrapper.get('[data-testid="account-logout"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="logout-confirm"]').trigger('click')
    const renewedToken = token('alice-renewed')
    auth.login(renewedToken, alice)
    prepared.resolve('ready')
    await flushPromises()

    expect(mocks.logout).not.toHaveBeenCalled()
    expect(auth.getToken()).toBe(renewedToken)
    expect(auth.state.isLoggedIn).toBe(true)
    expect(routers.get(wrapper)!.currentRoute.value.path).toBe('/account')
  })

  it('does not revoke a shared replacement credential while its storage event is pending', async () => {
    const prepared = deferred<'ready'>()
    const wrapper = await mountAccount(vi.fn().mockReturnValue(prepared.promise))
    const requestToken = auth.getToken()
    await wrapper.get('[data-testid="account-logout"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="logout-confirm"]').trigger('click')
    const bob = { userId: 8, phone: '13900139000', username: 'bob' }
    const bobToken = token('bob')
    const sharedRecord = JSON.stringify({ token: bobToken, user: bob, updatedAt: Date.now() })
    localStorage.setItem('iot_verify_token', bobToken)
    localStorage.setItem('iot_verify_user', JSON.stringify(bob))
    localStorage.setItem('iot_verify_auth_sync', sharedRecord)
    prepared.resolve('ready')
    await flushPromises()

    expect(mocks.logout).not.toHaveBeenCalled()
    expect(auth.getToken()).toBe(requestToken)
    expect(localStorage.getItem('iot_verify_auth_sync')).toBe(sharedRecord)
    expect(routers.get(wrapper)!.currentRoute.value.path).toBe('/account')
  })

  it('completes explicitly confirmed local sign-out even when server revocation is unavailable', async () => {
    const wrapper = await mountAccount()
    const requestToken = auth.getToken()
    mocks.logout.mockRejectedValue(new Error('offline'))
    await wrapper.get('[data-testid="account-logout"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="logout-confirm"]').trigger('click')
    await flushPromises()

    expect(mocks.logout).toHaveBeenCalledWith(requestToken)
    expect(auth.state.isLoggedIn).toBe(false)
    expect(routers.get(wrapper)!.currentRoute.value.query.redirect).toBe(RETURN_PATH)
    expect(feedback.notifyError).not.toHaveBeenCalled()
  })

  it('deletes only through deliberate confirmation and passes the pinned token', async () => {
    const wrapper = await mountAccount()
    const requestToken = auth.getToken()
    await deleteForm(wrapper)
    expect(mocks.deleteAccount).toHaveBeenCalledWith({ confirmation: 'alice', password: 'Password123' }, requestToken)
    expect(mocks.logout).not.toHaveBeenCalled()
    expect(auth.state.isLoggedIn).toBe(false)
    expect(routers.get(wrapper)!.currentRoute.value.path).toBe('/')
  })

  it('keeps a definite deletion rejection inside the existing form until the user corrects it', async () => {
    setLocale('zh-CN')
    const cause = 'Current password is incorrect.'
    mocks.deleteAccount.mockRejectedValueOnce({ response: {
      status: 400, data: { code: 400, message: cause, data: null }
    } })
    const wrapper = await mountAccount()
    const requestToken = auth.getToken()
    await deleteForm(wrapper)
    await flushPromises()

    const dialog = wrapper.get('[data-testid="account-delete-dialog"]')
    expect(dialog.get('[data-testid="account-delete-rejection"]').text()).toContain(cause)
    expect(dialog.get<HTMLInputElement>('[data-testid="account-delete-password"]').element.value).toBe('Password123')
    expect(dialog.get<HTMLInputElement>('[data-testid="account-delete-confirmation"]').element.value).toBe('alice')
    expect(auth.getToken()).toBe(requestToken)
    expect(feedback.notifyError).not.toHaveBeenCalled()
    expect(feedback.notifySuccess).not.toHaveBeenCalled()
    await dialog.get('[data-testid="account-delete-password"]').setValue('CorrectedPassword123')
    await flushPromises()
    expect(wrapper.find('[data-testid="account-delete-rejection"]').exists()).toBe(false)
    expect(mocks.deleteAccount).toHaveBeenCalledOnce()
  })

  it('keeps a lost deletion response uncertain and directs re-login without a rejection form', async () => {
    mocks.deleteAccount.mockRejectedValueOnce({ response: {
      status: 503, data: { code: 503, message: 'The deletion response was lost.' }
    } })
    const wrapper = await mountAccount()
    await deleteForm(wrapper)
    await flushPromises()

    expect(auth.state.isLoggedIn).toBe(false)
    expect(routers.get(wrapper)!.currentRoute.value.path).toBe('/')
    expect(routers.get(wrapper)!.currentRoute.value.query.reason).toBe('account-deletion-unknown')
    expect(feedback.notifyBlocked).not.toHaveBeenCalled()
    expect(feedback.notifySuccess).not.toHaveBeenCalled()
    expect(feedback.notifyError).not.toHaveBeenCalled()
    expect(mocks.deleteAccount).toHaveBeenCalledOnce()
    expect(wrapper.find('[data-testid="account-delete-dialog"]').exists()).toBe(false)
  })

  it('discards a late deletion rejection when its initiating account has been replaced', async () => {
    let rejectDeletion!: (error: unknown) => void
    mocks.deleteAccount.mockReturnValue(new Promise((_resolve, reject) => { rejectDeletion = reject }))
    const wrapper = await mountAccount()
    await deleteForm(wrapper)
    await flushPromises()
    auth.login(token('bob'), { userId: 8, phone: '13900139000', username: 'bob' })
    await flushPromises()
    rejectDeletion({ response: { status: 400, data: { message: 'Alice password is incorrect.' } } })
    await flushPromises()

    expect(auth.state.user?.username).toBe('bob')
    expect(wrapper.find('[data-testid="account-delete-rejection"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Alice password is incorrect.')
    expect(feedback.notifyError).not.toHaveBeenCalled()
  })

  it.each(['password', 'logout', 'delete'] as const)('blocks %s when chat reconciliation failed and explains the failure persistently', async action => {
    const wrapper = await mountAccount(vi.fn().mockResolvedValue('reconciliation-failed'))
    if (action === 'password') {
      await passwordForm(wrapper)
      await wrapper.get('[data-testid="account-password-form"]').trigger('submit')
    } else if (action === 'logout') {
      await wrapper.get('[data-testid="account-logout"]').trigger('click')
      await flushPromises()
      await wrapper.get('[data-testid="logout-confirm"]').trigger('click')
    } else await deleteForm(wrapper)
    await flushPromises()
    const errorId = action === 'password' ? 'account-password-error' : action === 'logout' ? 'account-session-error' : 'account-delete-error'
    expect(wrapper.get(`[data-testid="${errorId}"]`).text()).not.toBe('')
    expect(mocks.changePassword).not.toHaveBeenCalled()
    expect(mocks.logout).not.toHaveBeenCalled()
    expect(mocks.deleteAccount).not.toHaveBeenCalled()
    expect(auth.state.isLoggedIn).toBe(true)
  })

  it('keeps a declined uncertain-chat password preparation as cancellation, without a false error', async () => {
    feedback.confirmChoice.mockResolvedValue(false)
    const wrapper = await mountAccount(vi.fn().mockResolvedValue('outcome-unknown'))
    await passwordForm(wrapper)
    await wrapper.get('[data-testid="account-password-form"]').trigger('submit')
    await flushPromises()
    expect(feedback.confirmChoice).toHaveBeenCalledExactlyOnceWith({
      title: i18n.global.t('account.passwordChatUnknownTitle'),
      message: i18n.global.t('account.passwordChatUnknownMessage'),
      confirmText: i18n.global.t('account.passwordProceed')
    })
    expect(mocks.changePassword).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="account-password-error"]').exists()).toBe(false)
    expect(auth.state.isLoggedIn).toBe(true)
  })

  it('requires valid new password and matching confirmation before submission', async () => {
    const prepare = vi.fn().mockResolvedValue('ready')
    const wrapper = await mountAccount(prepare)
    await passwordForm(wrapper)
    await wrapper.get('#account-confirm-password').setValue('different')
    expect(wrapper.get('[data-testid="account-save-password"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('#account-password-blocked').text()).toContain('do not match')
    await wrapper.get('[data-testid="account-password-form"]').trigger('submit')
    expect(prepare).not.toHaveBeenCalled()
    expect(mocks.changePassword).not.toHaveBeenCalled()
    await wrapper.get('#account-confirm-password').setValue('Different123')
    await wrapper.get('#account-new-password').setValue('密'.repeat(25))
    await wrapper.get('#account-confirm-password').setValue('密'.repeat(25))
    expect(wrapper.get('#account-new-password-hint').text()).toContain('72 UTF-8 bytes')
  })

  it('renders a password validation reason once and links the disabled action to its visible owner', async () => {
    const wrapper = await mountAccount()
    await wrapper.get('[data-testid="account-edit-password"]').trigger('click')
    const save = wrapper.get('[data-testid="account-save-password"]')
    expect(save.attributes('aria-describedby')).toBe('account-password-impact account-password-blocked')
    expect(wrapper.get('#account-password-blocked').text()).toBe(i18n.global.t('account.currentPasswordRequired'))

    await wrapper.get('#account-current-password').setValue('Password123')
    await wrapper.get('#account-new-password').setValue('short')
    const reason = i18n.global.t('auth.passwordLength')
    expect(wrapper.get('#account-new-password-hint').text()).toBe(reason)
    expect(wrapper.findAll('p').filter(paragraph => paragraph.text() === reason)).toHaveLength(1)
    expect(wrapper.find('#account-password-blocked').exists()).toBe(false)
    expect(save.attributes('aria-describedby')).toBe('account-password-impact account-new-password-hint')
    expect(save.attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="account-password-form"]').trigger('submit')
    expect(mocks.changePassword).not.toHaveBeenCalled()

    await wrapper.get('#account-new-password').setValue('Different123')
    expect(wrapper.get('#account-password-blocked').text()).toBe(i18n.global.t('auth.confirmPasswordRequired'))
    expect(save.attributes('aria-describedby')).toBe('account-password-impact account-password-blocked')
    await wrapper.get('#account-confirm-password').setValue('Different123')
    expect(wrapper.find('#account-password-blocked').exists()).toBe(false)
    expect(save.attributes('aria-describedby')).toBe('account-password-impact')
    expect(save.attributes('disabled')).toBeUndefined()
  })

  it('blocks browser Back until a password change settles and preserves the Board return address', async () => {
    const response = deferred<{ code: number; data: null }>()
    mocks.changePassword.mockReturnValue(response.promise)
    const prepare = vi.fn().mockResolvedValue('ready')
    const wrapper = await mountAccount(prepare)
    const router = routers.get(wrapper)!
    const requestToken = auth.getToken()
    await passwordForm(wrapper)
    await wrapper.get('[data-testid="account-password-form"]').trigger('submit')
    await wrapper.get('[data-testid="account-password-form"]').trigger('submit')
    expect(mocks.changePassword).toHaveBeenCalledOnce()
    expect(prepare).toHaveBeenCalledOnce()
    expect(mocks.logout).not.toHaveBeenCalled()
    expect(auth.state.isLoggedIn).toBe(true)
    expect(wrapper.get('[data-testid="account-back"]').attributes('disabled')).toBeDefined()
    router.back()
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/account')
    expect(wrapper.find('[data-testid="account-page"]').exists()).toBe(true)

    response.resolve({ code: 200, data: null })
    await flushPromises()
    expect(mocks.changePassword).toHaveBeenCalledWith({ currentPassword: 'Password123', newPassword: 'Different123' }, requestToken)
    expect(auth.state.isLoggedIn).toBe(false)
    expect(router.currentRoute.value.path).toBe('/')
    expect(router.currentRoute.value.query).toEqual({ mode: 'login', reason: 'password-changed', redirect: RETURN_PATH })
    expect(wrapper.find('#account-new-password').exists()).toBe(false)
  })

  it('signs out only its own session when the password response is lost', async () => {
    const wrapper = await mountAccount()
    await passwordForm(wrapper)
    mocks.changePassword.mockRejectedValue(new Error('response lost'))
    await wrapper.get('[data-testid="account-password-form"]').trigger('submit')
    await flushPromises()
    expect(auth.state.isLoggedIn).toBe(false)
    expect(routers.get(wrapper)!.currentRoute.value.query.reason).toBe('password-change-unknown')
  })

  it('does not sign out a replacement session after a late password response', async () => {
    const response = deferred<{ code: number; data: null }>()
    mocks.changePassword.mockReturnValue(response.promise)
    const wrapper = await mountAccount()
    const requestToken = auth.getToken()!
    await passwordForm(wrapper)
    await wrapper.get('[data-testid="account-password-form"]').trigger('submit')
    await flushPromises()
    auth.login(token('bob'), { userId: 8, phone: '13900139000', username: 'bob' })
    response.resolve({ code: 200, data: null })
    await flushPromises()
    expect(auth.state.user?.username).toBe('bob')
    expect(routers.get(wrapper)!.currentRoute.value.path).toBe('/account')
    expect(auth.isPasswordChangePending(requestToken)).toBe(false)
  })

  it('settles password-change intent after its session was cleared and the page unmounted', async () => {
    const response = deferred<{ code: number; data: null }>()
    mocks.changePassword.mockReturnValue(response.promise)
    const wrapper = await mountAccount()
    const requestToken = auth.getToken()!
    await passwordForm(wrapper)
    await wrapper.get('[data-testid="account-password-form"]').trigger('submit')
    await flushPromises()
    expect(auth.isPasswordChangePending(requestToken)).toBe(true)

    const replace = vi.spyOn(routers.get(wrapper)!, 'replace')
    auth.logoutIfTokenMatches(requestToken)
    wrapper.unmount()
    wrappers.splice(wrappers.indexOf(wrapper), 1)
    response.resolve({ code: 200, data: null })
    await flushPromises()
    expect(auth.isPasswordChangePending(requestToken)).toBe(false)
    expect(replace).not.toHaveBeenCalled()
  })
})

describe('account deletion outcome classification', () => {
  it('keeps explicit client rejections definite', () => {
    for (const status of [400, 401, 409, 429]) {
      expect(isAccountDeletionOutcomeUncertain({ response: { status } })).toBe(false)
    }
  })

  it('keeps a missing response or server failure uncertain', () => {
    expect(isAccountDeletionOutcomeUncertain(new Error('response lost'))).toBe(true)
    for (const status of [500, 503]) {
      expect(isAccountDeletionOutcomeUncertain({ response: { status } })).toBe(true)
    }
  })
})
