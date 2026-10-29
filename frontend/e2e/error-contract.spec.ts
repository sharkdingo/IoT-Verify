import {
  apiBaseURL,
  createAuthenticatedUser,
  expect,
  test
} from './support/auth'

test('missing a required fix request id returns a structured client error', { tag: '@smoke' }, async ({ request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'errorapi' })
  const response = await request.post(`${apiBaseURL}/api/verify/traces/1/fix`, {
    headers: { Authorization: `Bearer ${auth.token}` },
    data: { strategies: ['remove'] }
  })

  expect(response.status()).toBe(400)
  const body = await response.json()
  expect(body.code).toBe(400)
  expect(body.message).toContain('requestId')
})

test('logout and permanent account deletion reject unauthenticated requests', { tag: '@smoke' }, async ({ request }) => {
  const logoutResponse = await request.post(`${apiBaseURL}/api/auth/logout`)
  expect(logoutResponse.status()).toBe(401)
  expect((await logoutResponse.json()).code).toBe(401)

  const deleteResponse = await request.delete(`${apiBaseURL}/api/auth/account`, {
    data: { password: 'not-used', confirmation: 'not-used' }
  })
  expect(deleteResponse.status()).toBe(401)
  expect((await deleteResponse.json()).code).toBe(401)
})

test('a missing chat session remains a synchronous JSON error for SSE clients', { tag: '@smoke' }, async ({ request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'sseerror' })
  const response = await request.post(`${apiBaseURL}/api/chat/completions`, {
    headers: {
      Authorization: `Bearer ${auth.token}`,
      Accept: 'text/event-stream'
    },
    data: {
      sessionId: 'missing-session',
      turnId: 'missing-session-turn',
      content: 'hello'
    }
  })

  expect(response.status()).toBe(404)
  expect(response.headers()['content-type']).toContain('application/json')
  const body = await response.json()
  expect(body.code).toBe(404)
  expect(body.message).toContain('missing-session')
})
