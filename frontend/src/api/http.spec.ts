// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'

import api, { isBoardMutationRequest, shouldPublishBoardInvalidation } from './http'
import { router } from '@/router'
import { useAuth } from '@/stores/auth'
import * as boardInvalidation from '@/utils/boardInvalidation'

const validToken = (signature: string) => {
  const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `header.${payload}.${signature}`
}

afterEach(() => {
  useAuth().logout()
  vi.restoreAllMocks()
})

describe('board mutation request classification', () => {
  it('publishes invalidations only for requests that can change board semantics', () => {
    expect(isBoardMutationRequest({ method: 'post', url: '/board/nodes' })).toBe(true)
    expect(isBoardMutationRequest({ method: 'post', url: '/board/scene' })).toBe(true)
    expect(isBoardMutationRequest({ method: 'post', url: 'board/scene?requestId=scene-1' })).toBe(true)
    // Undo/redo change rules and specifications, so other tabs must be invalidated too.
    expect(isBoardMutationRequest({ method: 'post', url: '/board/edits/undo' })).toBe(true)
    expect(isBoardMutationRequest({ method: 'post', url: '/board/edits/redo' })).toBe(true)
    // Reading availability changes nothing.
    expect(isBoardMutationRequest({ method: 'get', url: '/board/edits/availability' })).toBe(false)
    expect(isBoardMutationRequest({ method: 'patch', url: '/board/rules/12' })).toBe(true)
    expect(isBoardMutationRequest({ method: 'post', url: '/verify/traces/12/fix/apply' })).toBe(true)
  })

  it('does not classify read-only recommendation and validation posts as mutations', () => {
    expect(isBoardMutationRequest({ method: 'post', url: '/board/rules/recommend' })).toBe(false)
    expect(isBoardMutationRequest({ method: 'post', url: '/board/specs/recommend?requestId=req-1' })).toBe(false)
    expect(isBoardMutationRequest({ method: 'post', url: '/board/rules/check-duplicate' })).toBe(false)
    expect(isBoardMutationRequest({ method: 'get', url: '/board/nodes' })).toBe(false)
    expect(isBoardMutationRequest({ method: 'get', url: '/board/scene' })).toBe(false)
    expect(isBoardMutationRequest({ method: 'get', url: '/board/replacement-preview' })).toBe(false)
  })

  it('broadcasts a successful full-scene write for the request owner', async () => {
    const auth = useAuth()
    auth.login(validToken('scene-owner'), { userId: 7, phone: '13800138000', username: 'alice' })
    const publish = vi.spyOn(boardInvalidation, 'publishBoardInvalidation').mockImplementation(() => {})
    const adapter = async (config: any) => ({
      data: { code: 200, message: 'ok', data: {} }, status: 200, statusText: 'OK', headers: {}, config
    })

    await api.get('/board/replacement-preview', { adapter })
    expect(publish).not.toHaveBeenCalled()
    await api.post('/board/scene', { scene: {} }, { adapter })
    expect(publish).toHaveBeenCalledExactlyOnceWith(7, 'http-mutation')
  })

  it('does not invalidate other tabs for an undo that applied nothing', () => {
    const undo = { method: 'post', url: '/board/edits/undo' }
    // An applied undo changed rules/specs, so every tab must reload.
    expect(shouldPublishBoardInvalidation(undo, { data: { applied: true } })).toBe(true)
    // NOTHING_TO_APPLY is a normal idempotent outcome that changed nothing.
    expect(shouldPublishBoardInvalidation(undo, { data: { applied: false } })).toBe(false)
    // A plain mutation carries no `applied` field and must still invalidate.
    expect(shouldPublishBoardInvalidation({ method: 'post', url: '/board/rules' }, {})).toBe(true)
    // FixApplyResultDto also has `applied`, meaning "did not persist" — a different thing. Other tabs
    // still need that invalidation, so the skip is scoped to the undo endpoints.
    expect(shouldPublishBoardInvalidation(
      { method: 'post', url: '/verify/traces/9/fix/apply' },
      { data: { applied: false } }
    )).toBe(true)
  })

  it('keeps Bob logged in when Alice request receives a delayed 401', async () => {
    const auth = useAuth()
    const aliceToken = validToken('alice')
    const bobToken = validToken('bob')
    auth.login(aliceToken, { userId: 7, phone: '13800138000', username: 'alice' })
    const push = vi.spyOn(router, 'push')
    let rejectRequest!: (reason: unknown) => void
    let requestConfig: any

    const delayedRequest = api.get('/delayed-auth-check', {
      adapter: config => new Promise((_resolve, reject) => {
        requestConfig = config
        rejectRequest = reject
      })
    })
    await vi.waitFor(() => expect(requestConfig?.authTokenAtRequest).toBe(aliceToken))

    auth.login(bobToken, { userId: 8, phone: '13900139000', username: 'bob' })
    rejectRequest({
      config: requestConfig,
      response: { status: 401, config: requestConfig }
    })

    await expect(delayedRequest).rejects.toMatchObject({ response: { status: 401 } })
    expect(auth.getToken()).toBe(bobToken)
    expect(auth.getUser()?.username).toBe('bob')
    expect(push).not.toHaveBeenCalled()
  })

  it('logs out and redirects to login when a 401 names the current token', async () => {
    // The mirror of the test above. Without it, deleting the interceptor's `redirectToLogin()` call
    // left this file green: only the *negative* case was asserted.
    const auth = useAuth()
    const token = validToken('current')
    auth.login(token, { userId: 7, phone: '13800138000', username: 'alice' })
    // `redirectToLogin` is a no-op on the public login surface, and the real `/board` guard would run
    // a full board load here — so the current route is stubbed to a private path instead of navigated.
    vi.spyOn(router, 'currentRoute', 'get').mockReturnValue({
      value: { path: '/board', fullPath: '/board' }
    } as any)
    const push = vi.spyOn(router, 'push').mockResolvedValue(undefined)

    await expect(api.get('/expired-session', {
      adapter: config => Promise.reject({
        config,
        response: { status: 401, config }
      })
    })).rejects.toMatchObject({ response: { status: 401 } })

    expect(auth.isAuthenticated()).toBe(false)
    expect(auth.getToken()).toBeNull()
    await vi.waitFor(() => expect(push).toHaveBeenCalled())
    expect(push.mock.calls[0]?.[0]).toMatchObject({ query: { mode: 'login' } })
  })

  it('reports an unconfirmed password change when a background request rejects its pending credential', async () => {
    const auth = useAuth()
    const token = validToken('password-change')
    auth.login(token, { userId: 7, phone: '13800138000', username: 'alice' })
    expect(auth.beginPasswordChange(token)).toBe(true)
    vi.spyOn(router, 'currentRoute', 'get').mockReturnValue({
      value: { path: '/board', fullPath: '/board?run=verification:7&trace=15' }
    } as any)
    const push = vi.spyOn(router, 'push').mockResolvedValue(undefined)
    try {
      await expect(api.get('/chat/session/list', {
        adapter: config => Promise.reject({ config, response: { status: 401, config } })
      })).rejects.toMatchObject({ response: { status: 401 } })
      expect(auth.isAuthenticated()).toBe(false)
      await vi.waitFor(() => expect(push).toHaveBeenCalled())
      expect(push.mock.calls[0]?.[0]).toEqual({
        path: '/', query: { mode: 'login', redirect: '/board?run=verification:7&trace=15', reason: 'password-change-unknown' }
      })
    } finally {
      auth.endPasswordChange(token)
    }
  })

  it('preserves an explicit owner token for cleanup after the current account changes', async () => {
    const auth = useAuth()
    const aliceToken = validToken('alice-owner')
    const bobToken = validToken('bob-current')
    auth.login(bobToken, { userId: 8, phone: '13900139000', username: 'bob' })
    let requestConfig: any

    await api.delete('/owned-cleanup', {
      headers: { Authorization: `Bearer ${aliceToken}` },
      adapter: async config => {
        requestConfig = config
        return {
          data: { code: 200, message: 'ok', data: true },
          status: 200,
          statusText: 'OK',
          headers: {},
          config
        }
      }
    })

    expect(requestConfig.headers.get('Authorization')).toBe(`Bearer ${aliceToken}`)
    expect(requestConfig.authTokenAtRequest).toBe(aliceToken)
    expect(auth.getToken()).toBe(bobToken)
  })
})
