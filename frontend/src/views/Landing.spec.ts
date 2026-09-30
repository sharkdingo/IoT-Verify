// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/assets/i18n'
import Landing from './Landing.vue'
import { ElMessage } from 'element-plus'
import { useAuth } from '@/stores/auth'

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
  /*
   * `HintTooltip` imports `ElTooltip`, and this mock replaces the whole module — so omitting it made every case
   * in this file fail at import time with "No 'ElTooltip' export is defined", which names the mock rather than
   * the component that needs it. A render-slot stub is enough: nothing here asserts tooltip behaviour, only that
   * the panel below it still works.
   */
  ElTooltip: { name: 'ElTooltip', template: '<slot />' }
}))

const deferred = <T>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

const mountLanding = async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: Landing },
      { path: '/board', component: { template: '<div>board</div>' } }
    ]
  })
  await router.push('/?mode=login')
  await router.isReady()
  const wrapper = mount(Landing, {
    attachTo: document.body,
    global: {
      plugins: [router, i18n],
      stubs: { PublicHeader: true }
    }
  })
  return { router, wrapper }
}

describe('Landing authentication usability', () => {
  beforeEach(() => {
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
    useAuth().logout()
    document.body.innerHTML = ''
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
    wrapper.unmount()
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

    expect(wrapper.get('.auth-request-error').text()).toContain('登录服务当前繁忙')
    expect(wrapper.get('.auth-request-error').text()).toContain('9 秒')
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

  it('does not render autoplay video when reduced motion is requested', async () => {
    vi.mocked(window.matchMedia).mockReturnValue({
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    } as unknown as MediaQueryList)

    const { wrapper } = await mountLanding()

    expect(wrapper.find('video').exists()).toBe(false)
    wrapper.unmount()
  })

  it('loads the background video from the application origin', async () => {
    const { wrapper } = await mountLanding()

    const video = wrapper.get('video.video-bg')
    expect(video.attributes('preload')).toBe('auto')
    expect(video.get('source').attributes('src')).toBe('/videos/landing-bg.mp4')
    wrapper.unmount()
  })
})
