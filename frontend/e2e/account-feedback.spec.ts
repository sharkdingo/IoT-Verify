import type { APIRequestContext, APIResponse } from '@playwright/test'
import {
  apiBaseURL, createAuthenticatedUser, createTestAccountCredentials,
  expect, test, trackTestAccount, type AuthUser
} from './support/auth'
import { clickUnderTooltip } from './support/tooltips'

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

for (const locale of ['zh-CN', 'en']) {
  test(`account access keeps one heading and reachable validation at narrow and wide widths in ${locale}`, async ({ page }) => {
    await page.addInitScript(locale => { localStorage.setItem('locale', locale) }, locale)
    for (const width of [320, 1440]) {
      await page.setViewportSize({ width, height: 720 })
      await page.goto('/#/?mode=register')
      await expect(page.locator('h1')).toHaveCount(1)
      await expect(page.locator('.model-preview, .hero-copy')).toHaveCount(0)
      const form = page.locator('#register-panel')
      await form.locator('button[type="submit"]').click()
      await expect(form.locator('input[autocomplete="tel"]')).toBeFocused()
      await expect(form.locator('#register-phone-hint')).toBeInViewport({ ratio: 1 })
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
      await page.getByRole('tab', { name: locale === 'zh-CN' ? '登录' : 'Sign in', exact: true }).click()
      await expect(page.locator('#login-panel')).toBeVisible()
      await page.getByRole('button', { name: locale === 'zh-CN' ? '返回产品概览' : 'Back to product overview', exact: true }).click()
      await expect(page.locator('h1')).toHaveCount(1)
      await expect(page.locator('.model-preview')).toBeVisible()
    }
  })
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

test('Chinese account rejections localize recovery and preserve raw causes in closed technical details', async ({ page, request }, testInfo) => {
  const existing = await createAuthenticatedUser(request, { usernamePrefix: 'feedback' })
  const beforeExisting = await persistedState(request, existing)
  const credentials = createTestAccountCredentials({ usernamePrefix: 'corrected' })
  trackTestAccount(request, credentials)
  await page.addInitScript(() => { localStorage.setItem('locale', 'zh-CN') })
  await page.goto('/#/?mode=register')
  const form = page.locator('#register-panel')
  await form.locator('input[autocomplete="tel"]').fill(existing.phone)
  await form.locator('input[autocomplete="username"]').fill(credentials.username)
  for (const input of await form.locator('input[autocomplete="new-password"]').all()) await input.fill(PASSWORD)

  for (const duplicate of ['phone', 'username'] as const) {
    if (duplicate === 'username') {
      await form.locator('input[autocomplete="tel"]').fill(credentials.phone)
      await form.locator('input[autocomplete="username"]').fill(existing.username)
    }
    const rejection = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/register'
      && response.request().method() === 'POST')
    await form.locator('button[type="submit"]').click()
    const response = await rejection
    expect(response.status()).toBe(409)
    const body = await response.json()
    expect(body.data.reasonCode).toBe(duplicate === 'phone' ? 'PHONE_ALREADY_EXISTS' : 'USERNAME_ALREADY_EXISTS')
    expect(body.data.errors[duplicate]).toBe(body.message)
    const hint = form.locator(`#register-${duplicate}-hint`)
    await expect(hint).toContainText(duplicate === 'phone' ? '此手机号已注册' : '此用户名已被使用')
    await expect(hint).toBeInViewport({ ratio: 1 })
    await expect(form.locator(`input[autocomplete="${duplicate === 'phone' ? 'tel' : 'username'}"]`)).toBeFocused()
    await expect(page.locator('.auth-request-error')).toHaveCount(0)
    const details = page.getByTestId('failure-technical-details')
    await expect(details).not.toHaveAttribute('open', '')
    await expect(details.locator('ul')).not.toBeVisible()
    await details.locator('summary').click()
    await expect(details).toContainText(`${duplicate}: ${body.message}`)
    await expect(form.locator('input[autocomplete="username"]')).toHaveValue(
      duplicate === 'phone' ? credentials.username : existing.username)
    await expect(form.locator('input[autocomplete="tel"]')).toHaveValue(
      duplicate === 'phone' ? existing.phone : credentials.phone)
    await expect(page.locator('.el-message--error')).toHaveCount(0)
    expect(await persistedState(request, existing)).toEqual(beforeExisting)
    await testInfo.attach(`registration-${duplicate}-actual.json`, { body: JSON.stringify(body), contentType: 'application/json' })
  }

  await form.locator('input[autocomplete="username"]').fill(credentials.username)
  await expect(page.locator('.auth-request-error')).toHaveCount(0)
  const accepted = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/register'
    && response.request().method() === 'POST')
  await form.locator('button[type="submit"]').click()
  expect((await accepted).status()).toBe(200)
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
  const registered = await page.evaluate(() => ({
    ...JSON.parse(localStorage.getItem('iot_verify_user') || 'null'),
    token: localStorage.getItem('iot_verify_token')
  })) as AuthUser
  expect(registered.username).toBe(credentials.username)
  const beforeDeletion = await persistedState(request, registered)
  await clickUnderTooltip(page, page.getByTestId('open-account-center'))
  await expect(page.getByTestId('account-username-value')).toHaveText(credentials.username)
  await page.getByTestId('open-account-delete').click()
  const dialog = page.getByTestId('account-delete-dialog')
  await dialog.getByTestId('account-delete-confirmation').pressSequentially(credentials.username)
  await dialog.getByTestId('account-delete-password').fill('IncorrectPassword123')
  const denied = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/account'
    && response.request().method() === 'DELETE')
  await dialog.getByTestId('account-delete-confirm').click()
  const denial = await denied
  expect(denial.status()).toBe(400)
  const denialBody = await denial.json()
  await expect(dialog.getByTestId('failure-cause')).toHaveCount(0)
  const deletionDetails = dialog.getByTestId('failure-technical-details')
  await expect(deletionDetails).not.toHaveAttribute('open', '')
  await deletionDetails.locator('summary').click()
  await expect(deletionDetails).toContainText(denialBody.message)
  await expect(dialog.getByTestId('account-delete-password')).toBeInViewport({ ratio: 1 })
  await expect(dialog.getByTestId('account-delete-password')).toHaveValue('IncorrectPassword123')
  await expect(dialog.getByTestId('account-delete-confirmation')).toHaveValue(credentials.username)
  await expect(page.locator('.el-message--error')).toHaveCount(0)
  expect(await persistedState(request, registered)).toEqual(beforeDeletion)
  await testInfo.attach('delete-rejection-actual.json', { body: JSON.stringify(denialBody), contentType: 'application/json' })
  await testInfo.attach('delete-rejection-inline.png', { body: await page.screenshot(), contentType: 'image/png' })
  await dialog.getByTestId('account-delete-password').fill(PASSWORD)
  await expect(dialog.getByTestId('account-delete-rejection')).toHaveCount(0)
  const deleted = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/account'
    && response.request().method() === 'DELETE')
  await dialog.getByTestId('account-delete-confirm').click()
  expect((await deleted).status()).toBe(200)
  await expect(page.locator('#register-panel')).toBeVisible()
  expect(await page.evaluate(() => localStorage.getItem('iot_verify_token'))).toBeNull()
  expect(await persistedState(request, existing)).toEqual(beforeExisting)
})

for (const mode of ['login', 'register'] as const) {
  test(`confirmed ${mode} survives an injected lazy workspace load failure without repeating authentication`, async ({ page, request }, testInfo) => {
    const registration = mode === 'register' ? createTestAccountCredentials({ usernamePrefix: 'route' }) : null
    const credentials = registration ?? await createAuthenticatedUser(request, { usernamePrefix: 'route' })
    if (registration) trackTestAccount(request, registration)
    const authenticationRequests: string[] = []
    page.on('request', request => {
      const path = new URL(request.url()).pathname
      if (request.method() === 'POST' && ['/api/auth/login', '/api/auth/register'].includes(path)) authenticationRequests.push(path)
    })
    await page.addInitScript(() => { localStorage.setItem('locale', 'zh-CN') })
    await page.route('**/assets/Board-*.js', route => route.abort('failed'))
    const target = '/board?feedback=retry#model'
    await page.goto(`/#/?mode=${mode}&redirect=${encodeURIComponent(target)}`)
    const form = page.locator(mode === 'login' ? '#login-panel' : '#register-panel')
    await form.locator('input[autocomplete="username"]').fill(credentials.username)
    if (mode === 'register') {
      await form.locator('input[autocomplete="tel"]').fill(credentials.phone)
      for (const input of await form.locator('input[autocomplete="new-password"]').all()) await input.fill(PASSWORD)
    } else await form.locator('input[autocomplete="current-password"]').fill(PASSWORD)
    const authentication = page.waitForResponse(response => new URL(response.url()).pathname === `/api/auth/${mode}`)
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
    expect(authenticationRequests).toEqual([`/api/auth/${mode}`])
    expect(await persistedState(request, authenticated)).toEqual(before)
  })
}

test('an initial workspace route failure remains readable and reloads the resolved document after recovery', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'bootstrap' })
  const before = await persistedState(request, auth)
  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify({ userId: auth.userId, phone: auth.phone, username: auth.username }))
    localStorage.setItem('locale', 'zh-CN')
  }, auth)
  let authenticationRequests = 0
  page.on('request', request => {
    if (request.method() === 'POST' && ['/api/auth/login', '/api/auth/register'].includes(new URL(request.url()).pathname)) authenticationRequests += 1
  })
  await page.route('**/assets/Board-*.js', route => route.abort('failed'))
  await page.goto('/#/board?feedback=bootstrap#model')
  const failure = page.getByTestId('app-error-fallback')
  await expect(failure).toHaveAttribute('role', 'alert')
  await expect(failure).toContainText('页面暂时无法显示')
  await expect(failure.getByTestId('failure-cause')).toHaveCount(0)
  const details = failure.getByTestId('failure-technical-details')
  await expect(details).not.toHaveAttribute('open', '')
  const reload = failure.getByRole('button', { name: '重新加载页面', exact: true })
  await expect(reload).toBeInViewport({ ratio: 1 })
  await expect(failure.getByRole('button')).toHaveCount(1)
  expect(await page.evaluate(() => localStorage.getItem('iot_verify_token'))).toBe(auth.token)
  expect(await persistedState(request, auth)).toEqual(before)
  await testInfo.attach('injected-initial-route-failure.png', { body: await page.screenshot(), contentType: 'image/png' })
  await details.locator('summary').click()
  await expect(details).toContainText('Failed to fetch dynamically imported module')
  await page.unroute('**/assets/Board-*.js')
  await reload.click()
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
  await expect(page).toHaveURL(/#\/board\?feedback=bootstrap#model$/)
  expect(await page.evaluate(() => localStorage.getItem('iot_verify_token'))).toBe(auth.token)
  expect(authenticationRequests).toBe(0)
  expect(await persistedState(request, auth)).toEqual(before)
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

test('a real chat session retains one retryable history failure surface after an injected read rejection', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'history' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  const session = await unwrap(await request.post(`${apiBaseURL}/api/chat/sessions`, { headers }))
  const historyURL = `${apiBaseURL}/api/chat/sessions/${session.id}/messages`
  const beforeHistory = await unwrap(await request.get(historyURL, { headers }))
  const beforeBoard = await persistedState(request, auth)
  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify({ userId: auth.userId, phone: auth.phone, username: auth.username }))
    localStorage.setItem('locale', 'zh-CN')
  }, auth)
  let reads = 0
  const cause = 'History storage is temporarily unavailable.'
  await page.route(`**/api/chat/sessions/${session.id}/messages**`, async route => {
    reads += 1
    if (reads === 1) await route.fulfill({ status: 503, contentType: 'application/json',
      body: JSON.stringify({ code: 503, message: cause, data: null }) })
    else await route.continue()
  })
  let sentTurns = 0
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/chat/completions') sentTurns += 1
  })
  await page.goto('/#/board')
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
  await page.getByTestId('open-ai-assistant').click()
  await page.getByTestId('chat-sidebar-toggle').click()
  await page.getByTestId(`chat-session-${session.id}`).click()
  const failure = page.getByTestId('chat-history-retry')
  await expect(failure).toContainText('所选会话未能加载。')
  await expect(failure.getByTestId('failure-cause')).toHaveText(cause)
  await expect(failure.getByTestId('failure-cause')).toBeInViewport({ ratio: 1 })
  await expect(page.getByTestId('chat-input')).toBeDisabled()
  await expect(page.locator('.el-message--error')).toHaveCount(0)
  expect(reads).toBe(1)
  expect(await unwrap(await request.get(historyURL, { headers }))).toEqual(beforeHistory)
  expect(await persistedState(request, auth)).toEqual(beforeBoard)
  await testInfo.attach('injected-history-read-failure.png', { body: await page.screenshot(), contentType: 'image/png' })
  await failure.getByRole('button', { name: '重试', exact: true }).click()
  await expect(failure).toHaveCount(0)
  await expect(page.getByTestId('chat-input')).toBeEnabled()
  expect(reads).toBe(2)
  expect(sentTurns).toBe(0)
  expect(await unwrap(await request.get(historyURL, { headers }))).toEqual(beforeHistory)
  expect(await persistedState(request, auth)).toEqual(beforeBoard)
})
