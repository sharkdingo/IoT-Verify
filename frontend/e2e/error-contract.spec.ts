import { apiBaseURL, expect, test } from './support/auth'

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
