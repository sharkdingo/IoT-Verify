// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type RouteRecordRaw } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/assets/i18n'
import Landing from './Landing.vue'
import { ElMessage } from 'element-plus'
import { useAuth } from '@/stores/auth'
import { useTheme } from '@/composables/useTheme'

const authApi = vi.hoisted(() => ({
  login: vi.fn(),
  register: vi.fn()
}))

vi.mock('@/api/auth', () => ({ authApi }))
vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    error: vi.fn()
  },
  // These tests exercise the auth controls, not the tooltip overlay.
  ElTooltip: { name: 'ElTooltip', inheritAttrs: false, template: '<slot />' }
}))

const deferred = <T>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

const mountLanding = async (path = '/?mode=login', stubHeader = true,
  boardComponent: NonNullable<RouteRecordRaw['component']> = { template: '<div>board</div>' }, historyBase = '') => {
  const router = createRouter({
    history: createMemoryHistory(historyBase),
    routes: [
      { path: '/', component: Landing },
      { path: '/board', component: boardComponent }
    ]
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(Landing, {
    attachTo: document.body,
    global: {
      plugins: [router, i18n],
      stubs: { PublicHeader: stubHeader }
    }
  })
  return { router, wrapper }
}

const observeDocumentReload = () => {
  const browserWindow = window
  const replace = vi.spyOn(browserWindow.history, 'replaceState').mockImplementation(() => undefined)
  const reload = vi.fn()
  vi.stubGlobal('window', new Proxy(browserWindow, { get: (target, property) => property === 'location'
    ? { reload } : Reflect.get(target, property, target) }))
  return { replace, reload }
}

describe('Landing authentication usability', () => {
  beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      disconnect() {}
    })
    authApi.login.mockReset()
    authApi.register.mockReset()
    vi.mocked(ElMessage.success).mockClear()
    i18n.global.locale.value = 'zh-CN'
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: vi.fn().mockReturnValue({
        matches: false,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn()
      })
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    useAuth().logout()
    document.body.innerHTML = ''
  })

  it.each(['login', 'register'] as const)('does not send another %s request while the first submission is pending', async mode => {
    const response = deferred<unknown>()
    authApi[mode].mockReturnValue(response.promise)
    const { wrapper } = await mountLanding(`/?mode=${mode}`)
    try {
      if (mode === 'register') {
        await wrapper.get('input[autocomplete="tel"]').setValue('13800138000')
        await wrapper.get('input[autocomplete="username"]').setValue('alice')
        for (const password of wrapper.findAll('input[autocomplete="new-password"]')) await password.setValue('valid-password')
      } else {
        await wrapper.get('input[autocomplete="username"]').setValue('alice')
        await wrapper.get('input[autocomplete="current-password"]').setValue('valid-password')
      }
      const form = wrapper.get(`#${mode}-panel`)
      await form.trigger('submit')
      for (const language of ['en', 'zh-CN'] as const) {
        i18n.global.locale.value = language
        await flushPromises()
        const button = form.get('button[type="submit"]')
        expect(button.text()).toContain(i18n.global.t(`auth.${mode === 'login' ? 'signingIn' : 'creatingAccount'}`))
        expect(button.attributes('aria-busy')).toBe('true')
        await form.trigger('submit')
        expect(authApi[mode]).toHaveBeenCalledOnce()
      }
      response.resolve({ code: 503, message: 'Authentication temporarily unavailable', data: null })
      await flushPromises()
      expect(form.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
      expect(wrapper.find('.auth-request-error').exists()).toBe(true)
    } finally { wrapper.unmount() }
  })

  it.each([
    ['login', 'accepted'], ['login', 'rejected'], ['register', 'accepted'], ['register', 'rejected']
  ] as const)('abandons pending %s UI on browser Back and ignores its late %s response', async (mode, outcome) => {
    let resolve!: (value: unknown) => void
    let reject!: (error: unknown) => void
    authApi[mode].mockReturnValueOnce(new Promise((done, fail) => { resolve = done; reject = fail }))
    const previousMode = mode === 'register' ? 'login' : 'register'
    const { router, wrapper } = await mountLanding(`/?mode=${previousMode}`)
    try {
      await router.push(`/?mode=${mode}&redirect=${encodeURIComponent('/board?run=verification:7&trace=15')}`)
      await flushPromises()
      await wrapper.get('input[autocomplete="username"]').setValue('alice')
      if (mode === 'register') {
        await wrapper.get('input[autocomplete="tel"]').setValue('13800138000')
        for (const input of wrapper.findAll('input[autocomplete="new-password"]')) await input.setValue('Password123')
      } else await wrapper.get('input[autocomplete="current-password"]').setValue('Password123')
      await wrapper.get(`#${mode}-panel`).trigger('submit')
      await flushPromises()
      expect(authApi[mode]).toHaveBeenCalledOnce()

      router.back()
      await flushPromises()
      expect(router.currentRoute.value.query).toEqual({ mode: previousMode })
      for (const locale of ['en', 'zh-CN'] as const) {
        i18n.global.locale.value = locale
        await flushPromises()
        expect.soft(wrapper.get('.auth-submit').attributes('disabled')).toBeUndefined()
        expect.soft(wrapper.get('.auth-submit').attributes('aria-busy')).toBeUndefined()
        expect.soft(wrapper.get('.auth-submit').text()).toBe(i18n.global.t(
          previousMode === 'login' ? 'auth.signInToConsole' : 'auth.registerNow'))
      }
      if (outcome === 'accepted') resolve({ code: 200, data: {
        token: 'abandoned-token', userId: 7, phone: '13800138000', username: 'alice'
      } })
      else reject(new Error('Disconnected authentication request'))
      await flushPromises()
      expect(useAuth().getToken()).toBeNull()
      expect(router.currentRoute.value.query).toEqual({ mode: previousMode })
      expect(wrapper.find('.auth-request-error').exists()).toBe(false)
      expect(wrapper.find('[data-testid="auth-workspace-retry"]').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it.each(['login', 'register'] as const)('keeps a new %s request busy when an abandoned request finishes', async mode => {
    const abandoned = deferred<unknown>()
    const current = deferred<unknown>()
    const previousMode = mode === 'login' ? 'register' : 'login'
    authApi[previousMode].mockReturnValueOnce(abandoned.promise)
    authApi[mode].mockReturnValueOnce(current.promise)
    const { router, wrapper } = await mountLanding(`/?mode=${mode}`)
    const submit = async (mode: 'login' | 'register') => {
      await wrapper.get('input[autocomplete="username"]').setValue('alice')
      if (mode === 'register') {
        await wrapper.get('input[autocomplete="tel"]').setValue('13800138000')
        for (const input of wrapper.findAll('input[autocomplete="new-password"]')) await input.setValue('Password123')
      } else await wrapper.get('input[autocomplete="current-password"]').setValue('Password123')
      await wrapper.get(`#${mode}-panel`).trigger('submit')
      await flushPromises()
    }
    try {
      await router.push(`/?mode=${previousMode}`)
      await flushPromises()
      await submit(previousMode)
      router.back()
      await flushPromises()
      await submit(mode)
      expect(authApi[mode]).toHaveBeenCalledOnce()
      abandoned.resolve({ code: 200, data: {
        token: 'abandoned-token', userId: 7, phone: '13800138000', username: 'alice'
      } })
      await flushPromises()
      expect(useAuth().getToken()).toBeNull()
      expect(wrapper.get('.auth-submit').attributes('aria-busy')).toBe('true')
      expect(wrapper.find('.auth-request-error').exists()).toBe(false)
      current.resolve({ code: 503, message: 'Account store is unavailable', data: null })
      await flushPromises()
      expect(wrapper.get('.auth-submit').attributes('disabled')).toBeUndefined()
      for (const locale of ['en', 'zh-CN'] as const) {
        i18n.global.locale.value = locale
        await flushPromises()
        expect(wrapper.get('.auth-request-error p').text()).toBe(i18n.global.t(
          mode === 'login' ? 'auth.loginFailed' : 'auth.registerOutcomeUnknown'))
        expect(wrapper.findAll('[role="alert"]')).toHaveLength(1)
      }
    } finally { wrapper.unmount() }
  })

  it.each(['login', 'register'] as const)('shows %s success through navigation without a duplicate toast', async mode => {
    const session = { token: 'accepted-token', userId: 7, phone: '13800138000', username: 'alice' }
    authApi[mode].mockResolvedValueOnce({ code: 200, data: session })
    const { router, wrapper } = await mountLanding()
    if (mode === 'register') {
      await router.replace('/?mode=register')
      await flushPromises()
      await wrapper.get('input[autocomplete="tel"]').setValue(session.phone)
      await wrapper.get('input[autocomplete="username"]').setValue(session.username)
      const passwords = wrapper.findAll('input[autocomplete="new-password"]')
      for (const password of passwords) await password.setValue('valid-password')
    } else {
      await wrapper.get('input[autocomplete="username"]').setValue(session.username)
      await wrapper.get('input[autocomplete="current-password"]').setValue('valid-password')
    }
    await wrapper.get(`#${mode}-panel`).trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/board')
    expect(useAuth().state.isLoggedIn).toBe(true)
    expect(ElMessage.success).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('focuses the first invalid field and exposes validation messages as alerts', async () => {
    const { wrapper } = await mountLanding()

    await wrapper.get('#login-panel').trigger('submit')

    const account = wrapper.get<HTMLInputElement>('input[autocomplete="username"]')
    expect(document.activeElement).toBe(account.element)
    expect(wrapper.findAll('[role="alert"]').map(node => node.text())).toEqual([
      '请输入手机号或用户名',
      '请输入密码'
    ])
    wrapper.unmount()
  })

  it.each(['zh-CN', 'en'] as const)('keeps %s login prompts out of the form until validation needs them', async locale => {
    i18n.global.locale.value = locale
    const { wrapper } = await mountLanding()
    expect(wrapper.find('.auth-heading p').exists()).toBe(false)
    expect(wrapper.get('#login-panel').findAll('small')).toHaveLength(0)
    for (const input of wrapper.get('#login-panel').findAll('input')) {
      expect(input.attributes('placeholder')).toBeUndefined()
      expect(input.attributes('aria-describedby')).toBeUndefined()
    }

    await wrapper.get('#login-panel').trigger('submit')
    for (const [autocomplete, errorId, message] of [
      ['username', 'login-account-hint', 'auth.accountRequired'],
      ['current-password', 'login-password-hint', 'auth.passwordRequired']
    ]) {
      expect(wrapper.get(`input[autocomplete="${autocomplete}"]`).attributes('aria-describedby')).toBe(errorId)
      expect(wrapper.get(`#${errorId}`).text()).toBe(i18n.global.t(message))
      expect(wrapper.get(`#${errorId}`).attributes('role')).toBe('alert')
    }
    await wrapper.get('input[autocomplete="username"]').setValue('alice')
    expect(wrapper.find('#login-account-hint').exists()).toBe(false)
    expect(wrapper.get('input[autocomplete="username"]').attributes('aria-describedby')).toBeUndefined()
    wrapper.unmount()
  })

  it.each(['zh-CN', 'en'] as const)('keeps %s registration requirements and shows confirmation errors only when needed', async locale => {
    i18n.global.locale.value = locale
    const { wrapper } = await mountLanding('/?mode=register')
    expect(wrapper.get('#register-panel').findAll('small')).toHaveLength(3)
    expect(wrapper.get('#register-phone-hint').text()).toBe(i18n.global.t('auth.phoneHint'))
    expect(wrapper.get('#register-username-hint').text()).toBe(i18n.global.t('auth.usernameLength'))
    expect(wrapper.get('#register-password-hint').text()).toBe(i18n.global.t('auth.passwordHint'))
    for (const [selector, labelKey] of [
      ['input[autocomplete="tel"]', 'auth.phoneNumber'],
      ['input[autocomplete="username"]', 'auth.username'],
      ['input[autocomplete="new-password"]', 'auth.password']
    ]) {
      const input = wrapper.findAll(selector)[0]!
      const labelId = input.attributes('aria-labelledby')
      expect(labelId).toBeTruthy()
      expect(wrapper.get(`#${labelId}`).text()).toBe(i18n.global.t(labelKey))
      expect(input.attributes('aria-describedby')).toBeTruthy()
    }
    expect(wrapper.find('#register-confirm-password-hint').exists()).toBe(false)
    const confirmation = wrapper.findAll('input[autocomplete="new-password"]').at(-1)!
    expect(confirmation.attributes('placeholder')).toBeUndefined()
    await wrapper.get('#register-panel').trigger('submit')
    expect(wrapper.get('#register-confirm-password-hint').text()).toBe(i18n.global.t('auth.confirmPasswordRequired'))
    expect(wrapper.get('#register-confirm-password-hint').attributes('role')).toBe('alert')
    wrapper.unmount()
  })

  it.each([
    ['password-changed', '请使用新密码重新登录'],
    ['password-change-unknown', '先尝试使用新密码登录']
  ])('explains %s separately from session expiry', async (reason, message) => {
    const { wrapper } = await mountLanding(`/?mode=login&reason=${reason}&redirect=%2Fboard`)
    expect(wrapper.get('[data-testid="auth-password-change-notice"]').text()).toContain(message)
    expect(wrapper.find('[data-testid="auth-session-expired"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows action-specific busy feedback and keeps a failed request visible for recovery', async () => {
    const pending = deferred<any>()
    authApi.login.mockReturnValueOnce(pending.promise)
    const { wrapper } = await mountLanding()

    await wrapper.get('input[autocomplete="username"]').setValue('missing-user')
    await wrapper.get('input[autocomplete="current-password"]').setValue('wrong-password')
    await wrapper.get('#login-panel').trigger('submit')

    expect(wrapper.get('.auth-panel').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('.auth-submit').text()).toContain('正在验证账号')

    pending.resolve({ code: 401, message: '账号或密码不正确', data: null })
    await flushPromises()

    const requestError = wrapper.get<HTMLElement>('.auth-request-error')
    expect(requestError.text()).toContain('账号或密码不正确')
    expect(document.activeElement).toBe(requestError.element)
    expect(wrapper.get('.auth-panel').attributes('aria-busy')).toBe('false')
    wrapper.unmount()
  })

  it.each([
    ['phone', 'PHONE_ALREADY_EXISTS', 'Phone number is already registered.', 'auth.phoneAlreadyExists', 'tel'],
    ['username', 'USERNAME_ALREADY_EXISTS', 'Username already exists.', 'account.usernameExists', 'username']
  ])('localizes a registration conflict at its editable field: %s', async (field, reasonCode, cause, messageKey, autocomplete) => {
    authApi.register.mockRejectedValueOnce({ response: {
      status: 409, data: { code: 409, message: cause, data: { reasonCode, errors: { [field]: cause } } }
    } })
    const { wrapper } = await mountLanding('/?mode=register')
    try {
      await wrapper.get('input[autocomplete="tel"]').setValue('13800138000')
      await wrapper.get('input[autocomplete="username"]').setValue('alice')
      for (const input of wrapper.findAll('input[autocomplete="new-password"]')) await input.setValue('Password123')
      await wrapper.get('#register-panel').trigger('submit')
      await flushPromises()

      expect(wrapper.find('.auth-request-error').exists()).toBe(false)
      expect(wrapper.get(`#register-${field}-hint`).text()).toBe(i18n.global.t(messageKey))
      expect(document.activeElement).toBe(wrapper.get(`input[autocomplete="${autocomplete}"]`).element)
      expect(wrapper.find('[data-testid="failure-cause"]').exists()).toBe(false)
      expect(wrapper.get('[data-testid="failure-technical-details"]').attributes('open')).toBeUndefined()
      expect(wrapper.get('[data-testid="failure-technical-details"]').text()).toContain(`${field}: ${cause}`)
      expect(wrapper.get<HTMLInputElement>('input[autocomplete="tel"]').element.value).toBe('13800138000')
      expect(wrapper.get<HTMLInputElement>('input[autocomplete="username"]').element.value).toBe('alice')
      expect(authApi.register).toHaveBeenCalledOnce()
      await wrapper.get(`input[autocomplete="${autocomplete}"]`).setValue('different-value')
      expect(wrapper.find('.auth-request-error').exists()).toBe(false)
      expect(wrapper.find('[data-testid="failure-technical-details"]').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('focuses account access without the animated overview and restores the overview on Back', async () => {
    const { wrapper } = await mountLanding()
    expect(wrapper.find('.hero-copy').exists()).toBe(false)
    expect(wrapper.findAll('h1').map(node => node.text())).toEqual([i18n.global.t('auth.login')])
    await wrapper.get('.auth-panel__back').trigger('click')
    await flushPromises()
    expect(wrapper.find('.hero-copy').exists()).toBe(true)
    expect(wrapper.findAll('h1')).toHaveLength(1)
    expect(wrapper.find('.auth-panel').exists()).toBe(false)
    wrapper.unmount()
  })

  it('rejects phone-shaped usernames before registration reaches the server', async () => {
    const { wrapper } = await mountLanding('/?mode=register')
    await wrapper.get('input[autocomplete="tel"]').setValue('13800138000')
    await wrapper.get('input[autocomplete="username"]').setValue('13900139000')
    for (const input of wrapper.findAll('input[autocomplete="new-password"]')) await input.setValue('Password123')
    await wrapper.get('#register-panel').trigger('submit')
    expect(authApi.register).not.toHaveBeenCalled()
    expect(wrapper.get('#register-username-hint').text()).toBe(i18n.global.t('account.usernamePhoneReserved'))
    expect(document.activeElement).toBe(wrapper.get('input[autocomplete="username"]').element)
    wrapper.unmount()
  })

  it('retains an unconfirmed deletion notice while the user edits their next sign-in', async () => {
    const { wrapper } = await mountLanding('/?mode=login&reason=account-deletion-unknown&redirect=%2Fboard')
    await wrapper.get('input[autocomplete="username"]').setValue('alice')
    expect(wrapper.get('[data-testid="auth-account-deletion-notice"]').text()).toContain(i18n.global.t('app.deleteAccountOutcomeUnknown'))
    expect(wrapper.find('[data-testid="auth-session-expired"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it.each(['login', 'register'] as const)
  ('keeps confirmed %s separate from a failed lazy page load and retries through the resolved document URL', async mode => {
    const accepted = { token: 'accepted-token', userId: 7, phone: '13800138000', username: 'alice' }
    authApi[mode].mockResolvedValueOnce({ code: 200, data: accepted })
    const pageCause = 'Failed to fetch dynamically imported module: workspace'
    const target = '/board?source=retry#details'
    const { router, wrapper } = await mountLanding(`/?mode=${mode}&redirect=${encodeURIComponent(target)}`,
      true, () => Promise.reject(new Error(pageCause)), '/mounted/')
    const navigationErrors = vi.fn()
    router.onError(navigationErrors)
    try {
      await wrapper.get('input[autocomplete="username"]').setValue('alice')
      if (mode === 'register') {
        await wrapper.get('input[autocomplete="tel"]').setValue('13800138000')
        for (const input of wrapper.findAll('input[autocomplete="new-password"]')) await input.setValue('Password123')
      } else {
        await wrapper.get('input[autocomplete="current-password"]').setValue('Password123')
      }
      await wrapper.get(mode === 'register' ? '#register-panel' : '#login-panel').trigger('submit')
      await flushPromises()

      expect(useAuth().state.isLoggedIn).toBe(true)
      expect(useAuth().getToken()).toBe(accepted.token)
      expect(navigationErrors).toHaveBeenCalledOnce()
      expect(wrapper.get('.auth-request-error').text()).toContain('已登录，但工作台页面未能打开。')
      expect(wrapper.find('[data-testid="failure-cause"]').exists()).toBe(false)
      const details = wrapper.get('[data-testid="failure-technical-details"]')
      expect(details.attributes('open')).toBeUndefined()
      expect(details.text()).toContain(pageCause)
      expect(wrapper.find('#login-panel').exists()).toBe(false)
      expect(wrapper.find('#register-panel').exists()).toBe(false)
      const { replace, reload } = observeDocumentReload()
      await wrapper.get('[data-testid="auth-workspace-retry"]').trigger('click')
      await flushPromises()
      expect(replace).toHaveBeenCalledWith(null, '', '/mounted/board?source=retry#details')
      expect(reload).toHaveBeenCalledOnce()
      expect(navigationErrors).toHaveBeenCalledOnce()
      expect(useAuth().getToken()).toBe(accepted.token)
      expect(localStorage.getItem('iot_verify_token')).toBe(accepted.token)
      expect(authApi.login).toHaveBeenCalledTimes(mode === 'login' ? 1 : 0)
      expect(authApi.register).toHaveBeenCalledTimes(mode === 'register' ? 1 : 0)
    } finally { vi.unstubAllGlobals(); wrapper.unmount() }
  })

  it('does not attach an old page-load failure to a replacement authenticated session', async () => {
    const accepted = { token: 'alice-token', userId: 7, phone: '13800138000', username: 'alice' }
    authApi.login.mockResolvedValueOnce({ code: 200, data: accepted })
    let rejectPage!: (error: unknown) => void
    const { router, wrapper } = await mountLanding('/?mode=login', true,
      () => new Promise((_resolve, reject) => { rejectPage = reject }))
    router.onError(() => undefined)
    try {
      await wrapper.get('input[autocomplete="username"]').setValue('alice')
      await wrapper.get('input[autocomplete="current-password"]').setValue('Password123')
      await wrapper.get('#login-panel').trigger('submit')
      await flushPromises()
      useAuth().login('bob-token', { userId: 8, phone: '13900139000', username: 'bob' })
      rejectPage(new Error('Alice page load failed.'))
      await flushPromises()

      expect(useAuth().state.user?.username).toBe('bob')
      expect(wrapper.find('.auth-request-error').exists()).toBe(false)
      expect(wrapper.find('[data-testid="auth-workspace-retry"]').exists()).toBe(false)
      expect(authApi.login).toHaveBeenCalledOnce()
    } finally { wrapper.unmount() }
  })

  it('does not reload for a replaced shared token before the storage event clears its retry surface', async () => {
    const accepted = { token: 'alice-token', userId: 7, phone: '13800138000', username: 'alice' }
    authApi.login.mockResolvedValueOnce({ code: 200, data: accepted })
    const { router, wrapper } = await mountLanding('/?mode=login', true,
      () => Promise.reject(new Error('Initial workspace load failed.')))
    router.onError(() => undefined)
    try {
      await wrapper.get('input[autocomplete="username"]').setValue('alice')
      await wrapper.get('input[autocomplete="current-password"]').setValue('Password123')
      await wrapper.get('#login-panel').trigger('submit')
      await flushPromises()
      localStorage.setItem('iot_verify_token', 'bob-token')
      expect(useAuth().getToken()).toBe('alice-token')
      const { replace, reload } = observeDocumentReload()
      await wrapper.get('[data-testid="auth-workspace-retry"]').trigger('click')
      await flushPromises()
      expect(replace).not.toHaveBeenCalled()
      expect(reload).not.toHaveBeenCalled()
      expect(localStorage.getItem('iot_verify_token')).toBe('bob-token')
      expect(authApi.login).toHaveBeenCalledOnce()
    } finally { vi.unstubAllGlobals(); wrapper.unmount() }
  })

  it.each([
    ['login', 'accepted'], ['login', 'rejected'], ['register', 'accepted'], ['register', 'rejected']
  ] as const)('keeps a replacement session and releases the old %s UI after a late %s response', async (mode, outcome) => {
    let resolve!: (value: unknown) => void
    let reject!: (error: unknown) => void
    authApi[mode].mockReturnValueOnce(new Promise((done, fail) => { resolve = done; reject = fail }))
    const { router, wrapper } = await mountLanding(`/?mode=${mode}`)
    try {
      await wrapper.get('input[autocomplete="username"]').setValue('alice')
      if (mode === 'register') {
        await wrapper.get('input[autocomplete="tel"]').setValue('13800138000')
        for (const input of wrapper.findAll('input[autocomplete="new-password"]')) await input.setValue('Password123')
      } else await wrapper.get('input[autocomplete="current-password"]').setValue('Password123')
      await wrapper.get(mode === 'register' ? '#register-panel' : '#login-panel').trigger('submit')
      await flushPromises()
      useAuth().login('bob-token', { userId: 8, phone: '13900139000', username: 'bob' })
      await flushPromises()
      expect.soft(wrapper.get('.auth-submit').attributes('disabled')).toBeUndefined()
      if (outcome === 'accepted') resolve({ code: 200, data: {
        token: 'alice-token', userId: 7, phone: '13800138000', username: 'alice'
      } })
      else reject({ response: { status: 409, data: { message: 'Alice request was rejected.' } } })
      await flushPromises()

      expect(useAuth().getToken()).toBe('bob-token')
      expect(router.currentRoute.value.path).toBe('/')
      expect(wrapper.find('.auth-request-error').exists()).toBe(false)
      expect(wrapper.find('[data-testid="auth-workspace-retry"]').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('localizes structured authentication rate-limit feedback', async () => {
    authApi.login.mockRejectedValueOnce({
      response: {
        status: 429,
        data: {
          data: {
            reasonCode: 'AUTH_LOGIN_RATE_LIMIT_REACHED',
            retryAfterSeconds: 17
          }
        }
      }
    })
    const { wrapper } = await mountLanding()

    await wrapper.get('input[autocomplete="username"]').setValue('alice')
    await wrapper.get('input[autocomplete="current-password"]').setValue('wrong-password')
    await wrapper.get('#login-panel').trigger('submit')
    await flushPromises()

    expect(wrapper.get('.auth-request-error').text()).toContain('17 秒')
    i18n.global.locale.value = 'en'
    await flushPromises()
    expect(wrapper.get('.auth-request-error').get('p').text()).toBe(i18n.global.t('auth.rateLimitReached', { seconds: 17 }))
    expect(authApi.login).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('updates existing registration validation after switching language without clearing the draft', async () => {
    const { wrapper } = await mountLanding('/?mode=register')
    try {
      await wrapper.get('input[autocomplete="tel"]').setValue('123')
      await wrapper.get('input[autocomplete="username"]').setValue('ab')
      const passwords = wrapper.findAll('input[autocomplete="new-password"]')
      await passwords[0].setValue('short')
      await passwords[1].setValue('different')
      await wrapper.get('#register-panel').trigger('submit')
      i18n.global.locale.value = 'en'
      await flushPromises()
      for (const [field, key] of [['phone', 'phoneInvalid'], ['username', 'usernameLength'], ['password', 'passwordLength'], ['confirm-password', 'passwordMismatch']]) {
        expect(wrapper.get(`#register-${field}-hint`).text()).toBe(i18n.global.t(`auth.${key}`))
      }
      expect(wrapper.get<HTMLInputElement>('input[autocomplete="username"]').element.value).toBe('ab')
      expect(authApi.register).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })

  it.each(['en', 'zh-CN'] as const)('distinguishes rejected login credentials from transport failures in %s', async locale => {
    i18n.global.locale.value = locale
    const cause = 'Account or password is incorrect'
    authApi.login.mockRejectedValueOnce({ response: { status: 401, data: { code: 401, message: cause } } })
    const { wrapper } = await mountLanding()
    try {
      await wrapper.get('input[autocomplete="username"]').setValue('alice')
      await wrapper.get('input[autocomplete="current-password"]').setValue('Password123')
      await wrapper.get('#login-panel').trigger('submit')
      await flushPromises()
      expect(wrapper.get('.auth-request-error').get('p').text()).toBe(i18n.global.t('auth.invalidCredentials'))
      authApi.login.mockRejectedValueOnce({ code: 'ERR_NETWORK', message: 'Network Error' })
      await wrapper.get('#login-panel').trigger('submit')
      await flushPromises()
      expect(wrapper.get('.auth-request-error').get('p').text()).toBe(i18n.global.t('auth.loginFailed'))
      expect(wrapper.get('[data-testid="failure-technical-details"]').text()).toContain('Network Error')
      expect(useAuth().state.isLoggedIn).toBe(false)
    } finally { wrapper.unmount() }
  })

  it.each([undefined, 503])('keeps a registration outcome unconfirmed after status %s', async status => {
    const cause = 'Registration response lost'
    authApi.register.mockRejectedValueOnce(status ? { response: { status, data: { message: cause } } } : new Error(cause))
    const { wrapper } = await mountLanding('/?mode=register')
    try {
      await wrapper.get('input[autocomplete="tel"]').setValue('13800138000')
      await wrapper.get('input[autocomplete="username"]').setValue('alice')
      for (const input of wrapper.findAll('input[autocomplete="new-password"]')) await input.setValue('Password123')
      await wrapper.get('#register-panel').trigger('submit')
      await flushPromises()
      expect(wrapper.get('.auth-request-error').get('p').text()).toBe(i18n.global.t('auth.registerOutcomeUnknown'))
      expect(wrapper.get('[data-testid="failure-technical-details"]').text()).toContain(cause)
      i18n.global.locale.value = 'en'
      await flushPromises()
      expect(wrapper.get('.auth-request-error').get('p').text()).toBe(i18n.global.t('auth.registerOutcomeUnknown'))
      expect(authApi.register).toHaveBeenCalledOnce()
      expect(wrapper.get<HTMLInputElement>('input[autocomplete="username"]').element.value).toBe('alice')
    } finally { wrapper.unmount() }
  })

  it('does not blame the account when the authentication window capacity is full', async () => {
    authApi.login.mockRejectedValueOnce({
      response: {
        status: 429,
        data: {
          data: {
            reasonCode: 'AUTH_LOGIN_RATE_LIMIT_REACHED',
            scope: 'CAPACITY',
            retryAfterSeconds: 9
          }
        }
      }
    })
    const { wrapper } = await mountLanding()

    await wrapper.get('input[autocomplete="username"]').setValue('alice')
    await wrapper.get('input[autocomplete="current-password"]').setValue('wrong-password')
    await wrapper.get('#login-panel').trigger('submit')
    await flushPromises()

    expect(wrapper.get('.auth-request-error').text()).toContain('账号服务当前繁忙')
    expect(wrapper.get('.auth-request-error').text()).toContain('9 秒')
    authApi.login.mockRejectedValueOnce({ response: { status: 429, data: {
      data: { reasonCode: 'AUTH_LOGIN_RATE_LIMIT_REACHED', scope: 'CAPACITY' }
    } } })
    await wrapper.get('#login-panel').trigger('submit')
    await flushPromises()
    expect(wrapper.get('.auth-request-error').get('p').text()).toBe(i18n.global.t('auth.rateLimitCapacityReachedGeneric'))
    wrapper.unmount()
  })

  it('provides a discoverable exit from the authentication panel', async () => {
    const { router, wrapper } = await mountLanding()

    await wrapper.get('[aria-label="返回产品概览"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({})
    expect(wrapper.get('.hero-cta').isVisible()).toBe(true)
    wrapper.unmount()
  })

  it('shows an explicitly illustrative model before opening authentication', async () => {
    const { wrapper } = await mountLanding('/')

    expect(wrapper.get('.model-preview').text()).toContain('示例模型')
    expect(wrapper.get('.model-preview').text()).toContain('预设演示，非本次验证。')
    expect(wrapper.findAll('.preview-node')).toHaveLength(3)
    expect(wrapper.find('video').exists()).toBe(false)
    await wrapper.get('.hero-cta').trigger('click')
    await flushPromises()
    expect(wrapper.get('#login-panel').isVisible()).toBe(true)
    expect(wrapper.find('.model-preview').exists()).toBe(false)
    wrapper.unmount()
  })

  it.each(['light', 'dark'] as const)('offers theme and language controls on the %s public surface', async theme => {
    useTheme().setTheme(theme)
    const { wrapper } = await mountLanding('/', false)

    expect(document.documentElement.dataset.theme).toBe(theme)
    expect(wrapper.find('.theme-toggle').exists()).toBe(true)
    expect(wrapper.find('.language-toggle').exists()).toBe(true)
    expect(wrapper.get('.public-header__brand').text()).toBe('IoT-Verify')
    expect(wrapper.get('.product-mark').attributes('aria-hidden')).toBe('true')
    wrapper.unmount()
  })

  it('keeps overview and form copy localized after switching to English', async () => {
    i18n.global.locale.value = 'en'
    const { wrapper } = await mountLanding('/')

    expect(wrapper.get('.hero-title').text()).toContain('Map the system.')
    expect(wrapper.get('.model-preview').text()).toContain('Example model')
    await wrapper.get('.hero-cta').trigger('click')
    await flushPromises()
    expect(wrapper.get('.auth-submit').text()).toContain('Enter workspace')
    wrapper.unmount()
  })
})
