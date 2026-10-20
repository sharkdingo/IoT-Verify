// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'

import { resolveAuthenticatedEntry, router } from './index'
import { loginRedirectTarget } from './loginRedirect'
import { useAuth } from '@/stores/auth'

const location = (path: string, meta: { public?: boolean } = {}) =>
  ({ path, fullPath: path, meta }) as Parameters<typeof resolveAuthenticatedEntry>[0]

describe('resolveAuthenticatedEntry', () => {
  it('sends an anonymous visitor to the login surface with a return path', () => {
    expect(resolveAuthenticatedEntry(location('/board'), false))
      .toEqual({ path: '/', query: { mode: 'login', redirect: '/board' } })
  })

  it('keeps an authenticated visitor out of the landing page', () => {
    expect(resolveAuthenticatedEntry(location('/', { public: true }), true)).toBe('/board')
  })

  it('lets an authenticated visitor reach a private route', () => {
    expect(resolveAuthenticatedEntry(location('/board'), true)).toBeUndefined()
  })

  it('protects direct account entry and preserves its complete return address for login', () => {
    const account = router.resolve('/account?returnTo=%2Fboard%3Frun%3Dverification%3A7%26trace%3D15')
    expect(account.name).toBe('account')
    expect(account.meta.public).toBeFalsy()
    expect(resolveAuthenticatedEntry(account, false))
      .toEqual({ path: '/', query: { mode: 'login', redirect: account.fullPath } })
    expect(resolveAuthenticatedEntry(account, true)).toBeUndefined()
  })

  it('keeps the newer shared account on a private route when the previous memory token expires', () => {
    const auth = useAuth()
    vi.useFakeTimers()
    try {
      const token = (seconds: number, signature: string) => {
        const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + seconds }))
          .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
        return `header.${payload}.${signature}`
      }
      auth.login(token(60, 'alice'), { userId: 7, phone: '13800138000', username: 'alice' })
      const bob = { userId: 8, phone: '13900139000', username: 'bob' }
      const bobToken = token(3600, 'bob')
      const sharedRecord = JSON.stringify({ token: bobToken, user: bob, updatedAt: Date.now() })
      // The other tab's login completed, but its storage event has not reached this guard.
      localStorage.setItem('iot_verify_token', bobToken)
      localStorage.setItem('iot_verify_user', JSON.stringify(bob))
      localStorage.setItem('iot_verify_auth_sync', sharedRecord)

      vi.advanceTimersByTime(61_000)

      const target = { ...location('/board'), fullPath: '/board?run=simulation:7' }
      expect(resolveAuthenticatedEntry(target, auth.revalidateSession())).toBeUndefined()
      expect(auth.state.user).toEqual(bob)
      expect(auth.state.token).toBe(bobToken)
      expect(auth.isCurrentSessionToken(bobToken)).toBe(true)
      expect(localStorage.getItem('iot_verify_auth_sync')).toBe(sharedRecord)
    } finally {
      auth.logout()
      localStorage.clear()
      vi.useRealTimers()
    }
  })

  it('lets anyone reach a public route that is not the landing page', () => {
    expect(resolveAuthenticatedEntry(location('/404', { public: true }), false)).toBeUndefined()
    expect(resolveAuthenticatedEntry(location('/404', { public: true }), true)).toBeUndefined()
  })
})

describe('loginRedirectTarget', () => {
  it('returns null when already on the login surface', () => {
    expect(loginRedirectTarget({ path: '/', fullPath: '/?mode=login' })).toBeNull()
  })

  it('preserves the current location as the return path', () => {
    expect(loginRedirectTarget({ path: '/board', fullPath: '/board?panel=verify' }))
      .toEqual({ path: '/', query: { mode: 'login', redirect: '/board?panel=verify' } })
  })

  it.each(['password-changed', 'password-change-unknown'] as const)('preserves the Board return path and %s explanation', reason => {
    expect(loginRedirectTarget({ path: '/board', fullPath: '/board?run=verification:7&trace=15' }, reason))
      .toEqual({ path: '/', query: { mode: 'login', redirect: '/board?run=verification:7&trace=15', reason } })
  })

  it.each(['password-changed', 'password-change-unknown'] as const)('retains the workspace when %s is detected on the account page', reason => {
    const returnTo = '/board?run=verification:7&trace=15'
    expect(loginRedirectTarget({
      path: '/account', fullPath: `/account?returnTo=${encodeURIComponent(returnTo)}`,
      query: { returnTo }
    }, reason)).toEqual({ path: '/', query: { mode: 'login', redirect: returnTo, reason } })
  })

  it('preserves an ordinary account-page expiry destination while rejecting an unsafe password return', () => {
    const current = { path: '/account', fullPath: '/account?returnTo=https://outside.example', query: { returnTo: 'https://outside.example' } }
    expect(loginRedirectTarget(current, 'session-expired'))
      .toEqual({ path: '/', query: { mode: 'login', redirect: current.fullPath, reason: 'session-expired' } })
    expect(loginRedirectTarget(current, 'password-change-unknown'))
      .toEqual({ path: '/', query: { mode: 'login', redirect: '/board', reason: 'password-change-unknown' } })
  })
})
