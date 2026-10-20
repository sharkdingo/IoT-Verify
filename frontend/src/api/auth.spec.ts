import { beforeEach, describe, expect, it, vi } from 'vitest'
import { authApi } from './auth'

const http = vi.hoisted(() => ({ post: vi.fn(), get: vi.fn(), patch: vi.fn(), delete: vi.fn() }))
vi.mock('./http', () => ({ default: http }))

describe('logout credential', () => {
  beforeEach(() => {
    http.post.mockReset()
    http.get.mockReset()
    http.patch.mockReset()
    http.delete.mockReset()
  })

  it('accepts complete authenticated login and registration responses', async () => {
    const envelope = { code: 200, data: { userId: 7, phone: '13800138000', username: 'alice', token: 'accepted-token' } }
    http.post.mockResolvedValue({ data: envelope })
    await expect(authApi.login({ identifier: 'alice', password: 'Password123' })).resolves.toEqual(envelope)
    await expect(authApi.register({ phone: '13800138000', username: 'alice', password: 'Password123' })).resolves.toEqual(envelope)
  })

  it.each([
    null,
    {},
    { userId: 7, phone: '13800138000', username: 'alice', token: '' },
    { userId: 7, phone: '13800138000', username: 'alice', token: ' accepted-token ' },
    { userId: '7', phone: '13800138000', username: 'alice', token: 'accepted-token' },
    { userId: 7, phone: 'invalid-phone', username: 'alice', token: 'accepted-token' },
    { userId: 7, phone: '13800138000', username: ' alice ', token: 'accepted-token' },
    { userId: 7, phone: '13800138000', username: '13800138000', token: 'accepted-token' }
  ])('rejects unusable success data before either authentication flow can adopt it: %j', async data => {
    http.post.mockResolvedValue({ data: { code: 200, data } })
    await expect(authApi.login({ identifier: 'alice', password: 'Password123' })).rejects.toThrow('Authentication response is incomplete')
    await expect(authApi.register({ phone: '13800138000', username: 'alice', password: 'Password123' })).rejects.toThrow('Authentication response is incomplete')
  })

  it('rejects missing success envelopes while preserving declared rejection codes', async () => {
    http.post.mockResolvedValueOnce({ data: null })
    await expect(authApi.login({ identifier: 'alice', password: 'Password123' })).rejects.toThrow('Authentication response is incomplete')
    const rejection = { code: 401, data: null, message: 'Invalid credentials' }
    http.post.mockResolvedValueOnce({ data: rejection })
    await expect(authApi.login({ identifier: 'alice', password: 'Password123' })).resolves.toEqual(rejection)
  })

  it('uses the initiating credential instead of the interceptor current session', async () => {
    const envelope = { code: 200, data: null }
    http.post.mockResolvedValue({ data: envelope })
    await expect(authApi.logout('alice-token')).resolves.toEqual(envelope)
    expect(http.post).toHaveBeenCalledWith('/auth/logout', undefined, {
      headers: { Authorization: 'Bearer alice-token' }
    })
  })

  it('pins permanent deletion to the account that supplied the password and confirmation', async () => {
    const envelope = { code: 200, data: null }
    const data = { password: 'Password123', confirmation: 'DELETE' }
    http.delete.mockResolvedValue({ data: envelope })

    await expect(authApi.deleteAccount(data, 'alice-token')).resolves.toEqual(envelope)

    expect(http.delete).toHaveBeenCalledWith('/auth/account', {
      data,
      headers: { Authorization: 'Bearer alice-token' }
    })
  })

  it('pins account reads and mutations to the initiating session', async () => {
    const envelope = { code: 200, data: { userId: 7, phone: '13800138000', username: 'alice', createdAt: '2026-10-01T08:00:00+08:00' } }
    http.get.mockResolvedValue({ data: envelope })
    http.patch.mockResolvedValue({ data: envelope })
    const passwordEnvelope = { code: 200, data: null }
    http.post.mockResolvedValue({ data: passwordEnvelope })
    const headers = { Authorization: 'Bearer alice-token' }
    const username = { username: 'alice-new', currentPassword: 'Password123', expectedUsername: 'alice' }
    const password = { currentPassword: 'Password123', newPassword: 'Different123' }

    await expect(authApi.getAccount('alice-token')).resolves.toEqual(envelope)
    await expect(authApi.updateUsername(username, 'alice-token')).resolves.toEqual(envelope)
    await expect(authApi.changePassword(password, 'alice-token')).resolves.toEqual(passwordEnvelope)

    expect(http.get).toHaveBeenCalledWith('/auth/account', { headers })
    expect(http.patch).toHaveBeenCalledWith('/auth/account/username', username, { headers })
    expect(http.post).toHaveBeenCalledWith('/auth/account/password', password, { headers })
  })

  it.each([
    { userId: 7, phone: '13800138000', username: 'alice', createdAt: '2026-10-01T08:00:00' },
    { userId: 7, phone: '13800138000', username: 'alice', createdAt: 'invalid-time' },
    { userId: 7, phone: '13800138000', username: ' alice ', createdAt: '2026-10-01T08:00:00Z' },
    { userId: 7, phone: '13800138000', username: '13800138000', createdAt: '2026-10-01T08:00:00Z' },
    { userId: '7', phone: '13800138000', username: 'alice', createdAt: '2026-10-01T08:00:00Z' },
    null
  ])('rejects malformed profile responses in both read and mutation boundaries: %j', async data => {
    http.get.mockResolvedValue({ data: { code: 200, data } })
    http.patch.mockResolvedValue({ data: { code: 200, data } })
    await expect(authApi.getAccount('alice-token')).rejects.toThrow('Account response is incomplete')
    await expect(authApi.updateUsername({ username: 'alice', currentPassword: 'Password123', expectedUsername: 'old-alice' }, 'alice-token'))
      .rejects.toThrow('Account response is incomplete')
  })
})
