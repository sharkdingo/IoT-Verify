import type { APIRequestContext, APIResponse } from '@playwright/test'
import {
  apiBaseURL, createAuthenticatedUser,
  expect, test, type AuthUser
} from './support/auth'

const PASSWORD = 'Pass1234!!'
const unwrap = async (response: APIResponse) => {
  expect(response.ok(), await response.text()).toBeTruthy()
  const body = await response.json()
  expect(body.code).toBe(200)
  return body.data
}
const persistedState = async (request: APIRequestContext, auth: AuthUser) => {
  const headers = { Authorization: `Bearer ${auth.token}` }
  const [snapshot, history, runs] = await Promise.all([
    request.get(`${apiBaseURL}/api/board/snapshot`, { headers }).then(unwrap),
    request.get(`${apiBaseURL}/api/board/edits/clear-preview`, { headers }).then(unwrap),
    request.get(`${apiBaseURL}/api/verify/runs`, { headers }).then(unwrap)
  ])
  return { snapshot, history, runs }
}

test('an unconfirmed committed account deletion retains recovery instructions through sign-in edits', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'deleteunknown' })
  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify({ userId: auth.userId, phone: auth.phone, username: auth.username }))
    localStorage.setItem('locale', 'zh-CN')
  }, auth)
  let committed = false
  await page.route('**/api/auth/account', async route => {
    if (route.request().method() !== 'DELETE') return route.continue()
    const response = await route.fetch()
    expect(response.status()).toBe(200)
    committed = true
    await route.abort('failed')
  })
  await page.goto('/#/account')
  await expect(page.getByTestId('account-username-value')).toHaveText(auth.username)
  await page.getByTestId('open-account-delete').click()
  const dialog = page.getByTestId('account-delete-dialog')
  await dialog.getByTestId('account-delete-confirmation').pressSequentially(auth.username)
  await dialog.getByTestId('account-delete-password').fill(PASSWORD)
  await dialog.getByTestId('account-delete-confirm').click()
  await expect(page).toHaveURL(/reason=account-deletion-unknown/)
  expect(committed).toBe(true)
  expect(await page.evaluate(() => localStorage.getItem('iot_verify_token'))).toBeNull()
  const notice = page.getByTestId('auth-account-deletion-notice')
  await expect(notice).toContainText('无法确认注销请求是否已经完成')
  await page.locator('#login-panel input[autocomplete="username"]').fill(auth.username)
  await expect(notice).toBeVisible()
  await expect(page.locator('.el-message')).toHaveCount(0)
  expect((await request.post(`${apiBaseURL}/api/auth/login`, {
    data: { identifier: auth.username, password: PASSWORD }
  })).status()).toBe(401)
})

test('the actual board keeps loading distinct from an empty inventory until its snapshot arrives', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'pendingboard' })
  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify({ userId: auth.userId, phone: auth.phone, username: auth.username }))
    localStorage.setItem('locale', 'zh-CN')
  }, auth)
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/board/snapshot', async route => {
    const response = await route.fetch()
    await gate
    await route.fulfill({ response })
  })
  try {
    await page.goto('/#/board')
    const inspector = page.getByTestId('system-inspector')
    await expect(inspector.locator('.canvas-map__empty')).toHaveText('正在加载画布…')
    for (const section of ['devices', 'rules', 'specs', 'environment']) {
      await inspector.getByTestId(`inspector-tab-${section}`).click()
      await expect(inspector.locator('.board-inventory-empty p')).toHaveText('正在加载画布…')
    }
    await testInfo.attach('board-loading-inspector-and-overview.png', { body: await page.screenshot(), contentType: 'image/png' })
    release()
    await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
    await inspector.getByTestId('inspector-tab-devices').click()
    await expect(inspector.locator('.board-inventory-empty p')).toHaveText('画布上没有设备')
    await expect(inspector.locator('.canvas-map__empty')).toHaveText('画布上没有设备')
  } finally { release() }
})

test('confirmed login survives an injected lazy workspace load failure without repeating authentication', async ({ page, request }, testInfo) => {
  const credentials = await createAuthenticatedUser(request, { usernamePrefix: 'route' })
  const authenticationRequests: string[] = []
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && ['/api/auth/login', '/api/auth/register'].includes(path)) authenticationRequests.push(path)
  })
  await page.addInitScript(() => { localStorage.setItem('locale', 'zh-CN') })
  await page.route('**/assets/Board-*.js', route => route.abort('failed'))
  const target = '/board?feedback=retry#model'
  await page.goto(`/#/?mode=login&redirect=${encodeURIComponent(target)}`)
  const form = page.locator('#login-panel')
  await form.locator('input[autocomplete="username"]').fill(credentials.username)
  await form.locator('input[autocomplete="current-password"]').fill(PASSWORD)
  const authentication = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/login')
  await form.locator('button[type="submit"]').click()
  const response = await authentication
  expect(response.status()).toBe(200)
  const authenticated = (await response.json()).data as AuthUser
  const failure = page.locator('.auth-request-error')
  await expect(failure).toContainText('已登录，但工作台页面未能打开。')
  await expect(failure.locator('p').first()).toBeInViewport({ ratio: 1 })
  await expect(failure.getByTestId('failure-cause')).toHaveCount(0)
  const details = failure.getByTestId('failure-technical-details')
  await expect(details).not.toHaveAttribute('open', '')
  await expect(page.getByTestId('auth-workspace-retry')).toBeInViewport({ ratio: 1 })
  await expect(page.locator('#login-panel, #register-panel')).toHaveCount(0)
  expect(await page.evaluate(() => localStorage.getItem('iot_verify_token'))).toBe(authenticated.token)
  const before = await persistedState(request, authenticated)
  await testInfo.attach('injected-page-load-failure.png', { body: await page.screenshot(), contentType: 'image/png' })
  await details.locator('summary').click()
  await expect(details).toContainText('Failed to fetch dynamically imported module')
  await page.unroute('**/assets/Board-*.js')
  await page.getByTestId('auth-workspace-retry').click()
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
  await expect(page).toHaveURL(/#\/board\?feedback=retry#model$/)
  expect(authenticationRequests).toEqual(['/api/auth/login'])
  expect(await persistedState(request, authenticated)).toEqual(before)
})

for (const outcome of ['accepted', 'rejected'] as const) {
  test(`a late real ${outcome} sign-in response cannot replace another tab's authenticated session`, async ({ page, context, request }) => {
    const alice = await createAuthenticatedUser(request, { usernamePrefix: 'latealice' })
    const bob = await createAuthenticatedUser(request, { usernamePrefix: 'latebob' })
    const beforeAlice = await persistedState(request, alice)
    let release!: () => void
    let fetched!: () => void
    const gate = new Promise<void>(resolve => { release = resolve })
    const serverResponded = new Promise<void>(resolve => { fetched = resolve })
    await page.route('**/api/auth/login', async route => {
      const response = await route.fetch()
      fetched()
      await gate
      await route.fulfill({ response })
    })
    await page.addInitScript(() => { localStorage.setItem('locale', 'zh-CN') })
    await page.goto('/#/?mode=login')
    const oldResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/login')
    await page.locator('#login-panel input[autocomplete="username"]').fill(alice.username)
    await page.locator('#login-panel input[autocomplete="current-password"]').fill(
      outcome === 'accepted' ? PASSWORD : 'IncorrectPassword123')
    await page.locator('#login-panel button[type="submit"]').click()
    await serverResponded
    const peer = await context.newPage()
    try {
      await peer.goto('/#/?mode=login')
      await peer.locator('#login-panel input[autocomplete="username"]').fill(bob.username)
      await peer.locator('#login-panel input[autocomplete="current-password"]').fill(PASSWORD)
      await peer.locator('#login-panel button[type="submit"]').click()
      await expect(peer.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
      const bobToken = await peer.evaluate(() => localStorage.getItem('iot_verify_token'))
      await expect.poll(() => page.evaluate(() => localStorage.getItem('iot_verify_token'))).toBe(bobToken)
      await expect(page.locator('#login-panel button[type="submit"]')).toBeEnabled()
      release()
      expect((await oldResponse).status()).toBe(outcome === 'accepted' ? 200 : 401)
      await expect(page.locator('.auth-request-error')).toHaveCount(0)
      await expect(page.getByTestId('auth-workspace-retry')).toHaveCount(0)
      await expect(page).toHaveURL(/#\/\?mode=login$/)
      expect(await page.evaluate(() => localStorage.getItem('iot_verify_token'))).toBe(bobToken)
      expect((await unwrap(await request.get(`${apiBaseURL}/api/auth/account`, {
        headers: { Authorization: `Bearer ${bobToken}` }
      }))).userId).toBe(bob.userId)
      expect(await persistedState(request, alice)).toEqual(beforeAlice)
    } finally {
      release()
      await peer.close()
    }
  })
}
