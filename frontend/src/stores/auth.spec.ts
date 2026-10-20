// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from './auth'
import type { UserInfo } from '../types/auth'

const tokenExpiringIn = (seconds: number, signature = 'signature') => {
  const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + seconds }))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `header.${payload}.${signature}`
}

const validToken = (signature = 'signature') => tokenExpiringIn(3600, signature)

const remoteSessionEvent = (next: { token: string | null; user: UserInfo | null }) => {
  if (next.token) localStorage.setItem('iot_verify_token', next.token)
  else localStorage.removeItem('iot_verify_token')
  if (next.user) localStorage.setItem('iot_verify_user', JSON.stringify(next.user))
  else localStorage.removeItem('iot_verify_user')
  const newValue = JSON.stringify({ ...next, updatedAt: Date.now() })
  localStorage.setItem('iot_verify_auth_sync', newValue)
  return new StorageEvent('storage', {
    key: 'iot_verify_auth_sync', newValue, storageArea: localStorage
  })
}

describe('auth cross-tab synchronization', () => {
  const auth = useAuth()

  beforeEach(() => {
    auth.logout()
    localStorage.clear()
  })

  it('applies login and logout events emitted by another tab', () => {
    const token = validToken()
    const user = { userId: 7, phone: '13800138000', username: 'alice' }

    window.dispatchEvent(remoteSessionEvent({ token, user }))

    expect(auth.state.isLoggedIn).toBe(true)
    expect(auth.state.user).toEqual(user)

    window.dispatchEvent(remoteSessionEvent({ token: null, user: null }))

    expect(auth.state.isLoggedIn).toBe(false)
    expect(auth.state.token).toBeNull()
    expect(auth.state.user).toBeNull()
  })

  it('replaces Alice with Bob after a cross-tab logout/login sequence', () => {
    const alice = { userId: 7, phone: '13800138000', username: 'alice' }
    const bob = { userId: 8, phone: '13900139000', username: 'bob' }
    // Built once: `validToken` embeds a whole-second `exp`, so calling it again below could land in
    // the next second and produce a different string for the same logical token.
    const bobToken = validToken('bob')

    for (const next of [
      { token: validToken('alice'), user: alice },
      { token: null, user: null },
      { token: bobToken, user: bob }
    ]) {
      window.dispatchEvent(remoteSessionEvent(next))
    }

    expect(auth.state.isLoggedIn).toBe(true)
    expect(auth.state.token).toBe(bobToken)
    expect(auth.state.user).toEqual(bob)
  })

  it.each(['login', 'logout'])('ignores a queued remote %s after a newer local login', eventKind => {
    const alice = { userId: 7, phone: '13800138000', username: 'alice' }
    const bob = { userId: 8, phone: '13900139000', username: 'bob' }
    const queued = remoteSessionEvent(eventKind === 'login'
      ? { token: validToken('alice'), user: alice }
      : { token: null, user: null })
    const bobToken = validToken('bob')
    auth.login(bobToken, bob)

    window.dispatchEvent(queued)

    expect(auth.state.user).toEqual(bob)
    expect(auth.state.token).toBe(bobToken)
    expect(auth.state.isLoggedIn).toBe(true)
    expect(auth.isCurrentSessionToken(bobToken)).toBe(true)
  })

  it('rechecks current storage when a queued clear arrives after a newer local login', () => {
    localStorage.clear()
    const queued = new StorageEvent('storage', { key: null, storageArea: localStorage })
    const bob = { userId: 8, phone: '13900139000', username: 'bob' }
    const bobToken = validToken('bob')
    auth.login(bobToken, bob)

    window.dispatchEvent(queued)

    expect(auth.state.user).toEqual(bob)
    expect(auth.state.token).toBe(bobToken)
    expect(auth.isCurrentSessionToken(bobToken)).toBe(true)
  })

  it('ignores an older profile notification after a newer local update with the same token', () => {
    const token = validToken('alice')
    const alice = { userId: 7, phone: '13800138000', username: 'alice' }
    auth.login(token, alice)
    const queued = remoteSessionEvent({ token, user: { ...alice, username: 'older-name' } })
    const updated = { ...alice, username: 'reviewed-name' }
    expect(auth.updateUserIfTokenMatches(token, updated)).toBe(true)

    window.dispatchEvent(queued)

    expect(auth.state.user).toEqual(updated)
    expect(auth.state.token).toBe(token)
    expect(JSON.parse(localStorage.getItem('iot_verify_user')!)).toEqual(updated)
  })

  it('applies the latest remote profile notification without replacing its token', () => {
    const token = validToken('alice')
    const alice = { userId: 7, phone: '13800138000', username: 'alice' }
    auth.login(token, alice)
    const updated = { ...alice, username: 'remote-name' }

    window.dispatchEvent(remoteSessionEvent({ token, user: updated }))

    expect(auth.state.user).toEqual(updated)
    expect(auth.state.token).toBe(token)
    expect(auth.isCurrentSessionToken(token)).toBe(true)
  })

  it('clears the session when a clear notification still describes empty shared storage', () => {
    auth.login(validToken('alice'), { userId: 7, phone: '13800138000', username: 'alice' })
    localStorage.clear()

    window.dispatchEvent(new StorageEvent('storage', { key: null, storageArea: localStorage }))

    expect(auth.state.isLoggedIn).toBe(false)
    expect(auth.state.token).toBeNull()
    expect(auth.state.user).toBeNull()
  })

  it('does not let an old request token log out a newer session', () => {
    const aliceToken = validToken('alice')
    const bobToken = validToken('bob')

    auth.login(aliceToken, { userId: 7, phone: '13800138000', username: 'alice' })
    auth.login(bobToken, { userId: 8, phone: '13900139000', username: 'bob' })

    expect(auth.logoutIfTokenMatches(aliceToken)).toBe(false)
    expect(auth.state.token).toBe(bobToken)
    expect(auth.state.user?.username).toBe('bob')
    expect(auth.logoutIfTokenMatches(bobToken)).toBe(true)
    expect(auth.state.isLoggedIn).toBe(false)
  })

  it('persists and broadcasts profile changes without replacing the session', () => {
    const token = validToken('alice')
    const alice = { userId: 7, phone: '13800138000', username: 'alice' }
    auth.login(token, alice)
    const updated = { ...alice, username: 'new-alice' }

    expect(auth.updateUserIfTokenMatches(token, updated)).toBe(true)
    expect(auth.state.token).toBe(token)
    expect(auth.state.user).toEqual(updated)
    expect(JSON.parse(localStorage.getItem('iot_verify_user')!)).toEqual(updated)
    expect(JSON.parse(localStorage.getItem('iot_verify_auth_sync')!)).toMatchObject({ token, user: updated })
  })

  it('refuses profile responses from an older credential or another user', () => {
    const oldToken = validToken('old-alice')
    const currentToken = validToken('current-alice')
    const alice = { userId: 7, phone: '13800138000', username: 'alice' }
    auth.login(currentToken, alice)

    expect(auth.updateUserIfTokenMatches(oldToken, { ...alice, username: 'stale-alice' })).toBe(false)
    expect(auth.updateUserIfTokenMatches(currentToken, { userId: 8, phone: '13900139000', username: 'bob' })).toBe(false)
    expect(auth.state.user).toEqual(alice)
    expect(JSON.parse(localStorage.getItem('iot_verify_user')!)).toEqual(alice)
  })

  it.each(['replacement', null])('does not republish a credential after shared storage changed to %s before the storage event arrived', next => {
    const requestToken = validToken('alice')
    auth.login(requestToken, { userId: 7, phone: '13800138000', username: 'alice' })
    const before = localStorage.getItem('iot_verify_auth_sync')
    if (next) localStorage.setItem('iot_verify_token', validToken(next))
    else localStorage.removeItem('iot_verify_token')

    expect(auth.state.token).toBe(requestToken)
    expect(auth.isCurrentSessionToken(requestToken)).toBe(false)
    expect(auth.updateUserIfTokenMatches(requestToken, { userId: 7, phone: '13800138000', username: 'late-alice' })).toBe(false)
    expect(auth.logoutIfTokenMatches(requestToken)).toBe(false)
    expect(localStorage.getItem('iot_verify_auth_sync')).toBe(before)
  })

  it('keeps password-change intent owned by the initiating credential', () => {
    const aliceToken = validToken('alice-intent')
    const bobToken = validToken('bob-intent')
    auth.login(aliceToken, { userId: 7, phone: '13800138000', username: 'alice' })
    expect(auth.beginPasswordChange(aliceToken)).toBe(true)
    auth.login(bobToken, { userId: 8, phone: '13900139000', username: 'bob' })
    expect(auth.beginPasswordChange(aliceToken)).toBe(false)
    expect(auth.beginPasswordChange(bobToken)).toBe(true)
    auth.endPasswordChange(aliceToken)
    expect(auth.isPasswordChangePending(bobToken)).toBe(true)
    auth.endPasswordChange(bobToken)
    expect(auth.isPasswordChangePending(bobToken)).toBe(false)
  })

  it('adopts a newer shared session when the old memory token expires before its event arrives', () => {
    vi.useFakeTimers()
    try {
      auth.login(tokenExpiringIn(60, 'alice'), { userId: 7, phone: '13800138000', username: 'alice' })
      const bob = { userId: 8, phone: '13900139000', username: 'bob' }
      const bobToken = validToken('bob')
      const queued = remoteSessionEvent({ token: bobToken, user: bob })
      const sharedRecord = localStorage.getItem('iot_verify_auth_sync')

      vi.advanceTimersByTime(61_000)

      expect(auth.revalidateSession()).toBe(true)
      expect(auth.state.user).toEqual(bob)
      expect(auth.state.token).toBe(bobToken)
      expect(auth.isCurrentSessionToken(bobToken)).toBe(true)
      expect(localStorage.getItem('iot_verify_user')).toBe(JSON.stringify(bob))
      expect(localStorage.getItem('iot_verify_auth_sync')).toBe(sharedRecord)

      window.dispatchEvent(queued)
      expect(auth.state.user).toEqual(bob)
      expect(auth.state.token).toBe(bobToken)
    } finally {
      vi.useRealTimers()
    }
  })

  it('adopts a completed remote logout without republishing it when the old memory token expires', () => {
    vi.useFakeTimers()
    try {
      auth.login(tokenExpiringIn(60, 'alice'), { userId: 7, phone: '13800138000', username: 'alice' })
      const queued = remoteSessionEvent({ token: null, user: null })
      const sharedRecord = localStorage.getItem('iot_verify_auth_sync')

      vi.advanceTimersByTime(61_000)

      expect(auth.revalidateSession()).toBe(false)
      expect(auth.state.token).toBeNull()
      expect(auth.state.user).toBeNull()
      expect(localStorage.getItem('iot_verify_token')).toBeNull()
      expect(localStorage.getItem('iot_verify_user')).toBeNull()
      expect(localStorage.getItem('iot_verify_auth_sync')).toBe(sharedRecord)

      window.dispatchEvent(queued)
      expect(auth.state.isLoggedIn).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })

  it('drops a session whose token expired while the tab stayed open', () => {
    // The token must lapse *in place*: delivering an already-expired token through a storage event
    // instead exercises `applyAuthState`'s own `isLocallyUsableJwt` gate, which clears the session
    // before `revalidateSession` is ever consulted — so the test passed with the production body
    // replaced by `() => state.isLoggedIn`, proving nothing about the behaviour the router guard needs.
    vi.useFakeTimers()
    try {
      auth.login(tokenExpiringIn(60), { userId: 7, phone: '13800138000', username: 'alice' })
      expect(auth.revalidateSession()).toBe(true)
      expect(auth.state.isLoggedIn).toBe(true)

      vi.advanceTimersByTime(61_000)

      expect(auth.revalidateSession()).toBe(false)
      expect(auth.state.isLoggedIn).toBe(false)
      expect(auth.state.token).toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })
})
