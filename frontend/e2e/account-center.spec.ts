import { type APIRequestContext, type APIResponse, type Locator, type Page, type Response } from '@playwright/test'
import {
  apiBaseURL,
  createAuthenticatedUser,
  createTestAccountCredentials,
  expect,
  test,
  trackTestAccount,
  type AuthUser
} from './support/auth'
import { clickUnderTooltip } from './support/tooltips'
import type { AccountProfile } from '../src/types/auth'

const PASSWORD = 'Pass1234!!'
const NEW_PASSWORD = 'NewAccountPass2026!'
const headers = (token: string) => ({ Authorization: `Bearer ${token}` })
const uniqueName = () => createTestAccountCredentials({ usernamePrefix: 'renamed' }).username

const unwrap = async <T>(response: APIResponse | Response): Promise<T> => {
  expect(response.ok(), await response.text()).toBeTruthy()
  const body = await response.json()
  expect(body.code, JSON.stringify(body)).toBe(200)
  return body.data as T
}

const openWorkspace = async (page: Page, auth: AuthUser, target = '/#/board') => {
  await page.addInitScript(auth => {
    // Reload must observe changed credentials, rather than reinstall the original test session.
    if (sessionStorage.getItem('account-center-e2e-initialized')) return
    sessionStorage.setItem('account-center-e2e-initialized', 'true')
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify({
      userId: auth.userId, phone: auth.phone, username: auth.username
    }))
    localStorage.setItem('iot_verify_theme', 'light')
    localStorage.setItem('locale', 'en')
  }, auth)
  await page.goto(target)
  await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
}

const openAccount = async (page: Page): Promise<Locator> => {
  const desktopEntry = page.getByTestId('open-account-center')
  if (await desktopEntry.isVisible()) {
    await clickUnderTooltip(page, desktopEntry)
  } else {
    await page.getByRole('button', { name: 'Scene actions', exact: true }).click()
    await page.getByTestId('nav-overflow-account').click()
  }
  const account = page.getByTestId('account-page')
  await expect(page).toHaveURL(/#\/account(?:\?|$)/)
  await expect(account).toBeVisible()
  await expect(account.getByTestId('account-phone')).toBeVisible()
  await expect(account.getByTestId('account-username-value')).toBeVisible()
  return account
}

const prepareUsername = async (page: Page, username: string, password = PASSWORD) => {
  const confirmation = page.getByTestId('account-confirm-username-dialog')
  await expect(confirmation).toHaveCount(0)
  const form = page.getByTestId('account-username-form')
  if (!await form.isVisible()) await page.getByTestId('account-edit-username').click()
  await form.getByLabel('Username', { exact: true }).fill(username)
  await form.getByTestId('account-save-username').click()
  await expect(confirmation).toBeVisible()
  await confirmation.getByLabel('Current password', { exact: true }).fill(password)
  return confirmation
}

const submitUsername = async (page: Page, username: string, password = PASSWORD) => {
  const confirmation = await prepareUsername(page, username, password)
  const response = page.waitForResponse(response => response.request().method() === 'PATCH'
    && new URL(response.url()).pathname === '/api/auth/account/username')
  await confirmation.getByTestId('account-confirm-username').click()
  return response
}

const fillPassword = async (page: Page, currentPassword = PASSWORD, newPassword = NEW_PASSWORD) => {
  const form = page.getByTestId('account-password-form')
  if (!await form.isVisible()) await page.getByTestId('account-edit-password').click()
  await form.getByLabel('Current password', { exact: true }).fill(currentPassword)
  await form.getByLabel('New password', { exact: true }).fill(newPassword)
  await form.getByLabel('Confirm Password', { exact: true }).fill(newPassword)
}

const signIn = async (page: Page, username: string, password: string) => {
  await expect(page.locator('#login-panel')).toBeVisible()
  await page.locator('#login-panel input[autocomplete="username"]').fill(username)
  await page.locator('#login-panel input[type="password"]').fill(password)
  await page.locator('#login-panel button[type="submit"]').click()
  await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
}

// Catalog row order is unspecified; DTO contents and ordered scene collections must remain intact.
const expectSceneUnchanged = (actual: Record<string, unknown>, expected: Record<string, unknown>) => {
  expect(Array.isArray(expected.deviceTemplates)).toBe(true)
  const templates = expected.deviceTemplates as unknown[]
  expect(actual).toEqual({ ...expected, deviceTemplates: expect.arrayContaining(templates) })
  expect(actual.deviceTemplates).toHaveLength(templates.length)
}

const seedScene = async (request: APIRequestContext, auth: AuthUser) => {
  await unwrap(await request.post(`${apiBaseURL}/api/board/nodes`, {
    headers: headers(auth.token), data: {
      devices: [{ id: 'account_light', templateName: 'Light', label: 'Account center light',
        position: { x: 200, y: 200 }, width: 176, height: 128, state: 'off',
        currentStateTrust: 'trusted', currentStatePrivacy: 'public', variables: [], privacies: [] }],
      environmentVariablePatches: []
    }
  }))
  return unwrap<Record<string, unknown>>(await request.get(`${apiBaseURL}/api/board/snapshot`, {
    headers: headers(auth.token)
  }))
}

const storedSession = (page: Page) => page.evaluate(() => ({
  token: localStorage.getItem('iot_verify_token'),
  user: JSON.parse(localStorage.getItem('iot_verify_user') || 'null')
}))

test('account settings start read-only and explicit editing never retains cancelled secrets or writes', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'settings' })
  await openWorkspace(page, auth)
  const reads: string[] = []
  const writes: string[] = []
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path === '/api/auth/account') reads.push(path)
    if (['PATCH', 'POST'].includes(request.method()) && path.startsWith('/api/auth/account/')) writes.push(path)
  })
  const account = await openAccount(page)
  await expect(account.getByRole('heading', { level: 1 })).toHaveCount(1)
  await expect(account.getByTestId('account-username-value')).toHaveText(auth.username)
  await expect(account.getByTestId('account-phone')).toHaveText(auth.phone)
  await expect(account.locator('input')).toHaveCount(0)
  await expect(account.locator('form')).toHaveCount(0)
  await expect(account.locator('aside')).toHaveCount(0)
  await expect(account.getByText('Account ID', { exact: true })).toHaveCount(0)
  await expect(account.getByText('Joined', { exact: true })).toHaveCount(0)
  expect(reads).toHaveLength(1)

  const candidate = uniqueName()
  await prepareUsername(page, candidate)
  await page.getByTestId('account-cancel-username-confirmation').click()
  await expect(page.getByTestId('account-confirm-username-dialog')).toHaveCount(0)
  await expect(account.locator('#account-username')).toHaveValue(candidate)
  await account.getByTestId('account-save-username').click()
  await expect(page.locator('#account-username-password')).toHaveValue('')
  await expect(page.getByTestId('account-confirm-username')).toBeDisabled()
  await page.locator('#account-username-password').fill(PASSWORD)
  await page.keyboard.press('Escape')
  await expect(page.getByTestId('account-confirm-username-dialog')).toHaveCount(0)
  await expect(account.getByTestId('account-save-username')).toBeFocused()
  await account.getByTestId('account-cancel-username').click()
  await expect(account.getByTestId('account-username-value')).toHaveText(auth.username)
  await expect(account.locator('input')).toHaveCount(0)

  await fillPassword(page)
  await account.getByRole('button', { name: 'Show passwords', exact: true }).click()
  await expect(account.locator('#account-current-password')).toHaveAttribute('type', 'text')
  await account.getByTestId('account-cancel-password').click()
  await expect(account.getByTestId('account-edit-password')).toBeFocused()
  await account.getByTestId('account-edit-password').click()
  for (const id of ['account-current-password', 'account-new-password', 'account-confirm-password']) {
    await expect(account.locator(`#${id}`)).toHaveValue('')
    await expect(account.locator(`#${id}`)).toHaveAttribute('type', 'password')
  }
  await fillPassword(page)
  await account.getByTestId('account-edit-username').click()
  await expect(account.getByTestId('account-password-form')).toHaveCount(0)
  await expect(account.locator('#account-username')).toHaveValue(auth.username)
  await expect(page.locator('#account-username-password')).toHaveCount(0)
  await account.getByTestId('account-cancel-username').click()
  await account.getByTestId('account-edit-password').click()
  for (const id of ['account-current-password', 'account-new-password', 'account-confirm-password']) {
    await expect(account.locator(`#${id}`)).toHaveValue('')
  }
  expect(reads).toHaveLength(1)
  expect(writes).toEqual([])
  expect((await storedSession(page)).token).toBe(auth.token)
})

test('rename preserves the Board and token, synchronizes tabs, and rejects incorrect, occupied and stale edits', async ({ page, request }) => {
  test.setTimeout(120_000)
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'account' })
  const taken = await createAuthenticatedUser(request, { usernamePrefix: 'taken' })
  const scene = await seedScene(request, auth)
  await openWorkspace(page, auth)
  await openAccount(page)
  const observer = await page.context().newPage()
  try {
    await observer.goto('/#/board')
    await expect(observer.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
    await openAccount(observer)
    const staleDraft = uniqueName()
    await observer.getByTestId('account-edit-username').click()
    await observer.getByLabel('Username', { exact: true }).fill(staleDraft)

    expect((await submitUsername(page, uniqueName(), 'IncorrectPass2026!')).status()).toBe(400)
    await expect(page.getByTestId('account-username-error')).toContainText('current password is incorrect')
    expect((await storedSession(page)).token).toBe(auth.token)
    await page.getByTestId('account-cancel-username-confirmation').click()
    await expect(page.getByTestId('account-confirm-username-dialog')).toHaveCount(0)
    expect((await submitUsername(page, taken.username)).status()).toBe(409)
    await expect(page.getByTestId('account-username-error')).toContainText('already in use')

    const renamed = uniqueName()
    await unwrap(await submitUsername(page, renamed))
    trackTestAccount(request, { phone: auth.phone, username: renamed, password: PASSWORD })
    await expect(page.getByTestId('account-username-value')).toHaveText(renamed)
    await expect(page.getByTestId('account-username-form')).toHaveCount(0)
    await expect.poll(async () => (await storedSession(observer)).user?.username).toBe(renamed)
    await expect(observer.getByLabel('Username', { exact: true })).toHaveValue(staleDraft)
    expect((await storedSession(page)).token).toBe(auth.token)
    expect((await storedSession(observer)).token).toBe(auth.token)

    const stale = await submitUsername(observer, staleDraft)
    expect(stale.status()).toBe(409)
    expect((await stale.json()).data.reasonCode).toBe('ACCOUNT_PROFILE_CHANGED')
    await expect(observer.getByTestId('account-username-error')).toContainText('Refresh your profile')
    await expect(observer.getByTestId('account-confirm-username-dialog')).toHaveCount(0)
    await expect(observer.getByLabel('Username', { exact: true })).toHaveValue(staleDraft)
    await observer.getByLabel('Username', { exact: true }).fill(uniqueName())
    await expect(observer.getByTestId('account-username-error')).toHaveCount(0)
    await expect(observer.getByTestId('account-refresh-profile')).toHaveCount(1)
    await expect(observer.getByTestId('account-refresh-profile')).toBeEnabled()
    let refreshReads = 0
    let retryWrites = 0
    observer.on('request', request => {
      const path = new URL(request.url()).pathname
      if (request.method() === 'GET' && path === '/api/auth/account') refreshReads += 1
      if (request.method() === 'PATCH' && path === '/api/auth/account/username') retryWrites += 1
    })
    await observer.getByTestId('account-refresh-profile').click()
    await expect(observer.getByLabel('Username', { exact: true })).toHaveValue(renamed)
    expect(refreshReads).toBe(1)
    expect(retryWrites).toBe(0)
    await unwrap(await submitUsername(observer, staleDraft))
    expect(retryWrites).toBe(1)
    trackTestAccount(request, { phone: auth.phone, username: staleDraft, password: PASSWORD })
    await expect.poll(async () => (await storedSession(page)).user?.username).toBe(staleDraft)

    expect((await request.post(`${apiBaseURL}/api/auth/login`, {
      data: { identifier: auth.username, password: PASSWORD }
    })).status()).toBe(401)
    for (const identifier of [staleDraft, auth.phone]) {
      const login = await unwrap<AuthUser>(await request.post(`${apiBaseURL}/api/auth/login`, {
        data: { identifier, password: PASSWORD }
      }))
      expect(login.userId).toBe(auth.userId)
      expect(login.username).toBe(staleDraft)
    }
    expectSceneUnchanged(await unwrap<Record<string, unknown>>(await request.get(`${apiBaseURL}/api/board/snapshot`, {
      headers: headers(auth.token)
    })), scene)
    await page.getByTestId('account-back').click()
    await page.reload()
    await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
    await openAccount(page)
    await expect(page.getByTestId('account-username-value')).toHaveText(staleDraft)
    await expect(page.getByTestId('account-phone')).toHaveText(auth.phone)
  } finally {
    await observer.close()
  }
})

test('password change rejects a wrong current password, signs out both tabs, and restores the same saved run', async ({ page, request }) => {
  test.setTimeout(120_000)
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'password' })
  const scene = await seedScene(request, auth)
  const saved = await unwrap<{ id: number, states: unknown[] }>(await request.post(`${apiBaseURL}/api/simulate/traces`, {
    headers: headers(auth.token), data: { steps: 1, enablePrivacy: false, attackScenario: { mode: 'NONE' } }
  }))
  expect(saved.id).toBeGreaterThan(0)
  expect(saved.states.length).toBeGreaterThan(0)
  const secondSession = await unwrap<AuthUser>(await request.post(`${apiBaseURL}/api/auth/login`, {
    data: { identifier: auth.phone, password: PASSWORD }
  }))
  expect(secondSession.token).not.toBe(auth.token)
  const target = `/#/board?run=simulation:${saved.id}`
  await openWorkspace(page, auth, target)
  await expect(page.getByTestId('simulation-timeline')).toBeVisible({ timeout: 30_000 })
  const observer = await page.context().newPage()
  try {
    await observer.goto('/#/board')
    await expect(observer.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
    await openAccount(page)
    await fillPassword(page, 'IncorrectPass2026!')
    const rejected = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/auth/account/password')
    await page.getByTestId('account-save-password').click()
    expect((await rejected).status()).toBe(400)
    await expect(page.getByTestId('account-password-error')).toContainText('current password is incorrect')
    expect((await storedSession(page)).token).toBe(auth.token)
    await expect(observer.getByTestId('board-root')).toBeVisible()

    await fillPassword(page)
    const changed = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/auth/account/password')
    await page.getByTestId('account-save-password').click()
    await unwrap(await changed)
    trackTestAccount(request, { phone: auth.phone, username: auth.username, password: NEW_PASSWORD })
    await expect(page).toHaveURL(/reason=password-changed/)
    await expect(page.getByTestId('auth-password-change-notice')).toContainText('all previous sessions were invalidated')
    await expect(observer.locator('#login-panel')).toBeVisible()
    expect((await storedSession(page)).token).toBeNull()
    expect((await storedSession(observer)).token).toBeNull()
    for (const token of [auth.token, secondSession.token]) {
      expect((await request.get(`${apiBaseURL}/api/auth/account`, { headers: headers(token) })).status()).toBe(401)
    }
    expect((await request.post(`${apiBaseURL}/api/auth/login`, {
      data: { identifier: auth.username, password: PASSWORD }
    })).status()).toBe(401)
    await signIn(page, auth.username, NEW_PASSWORD)
    expect(new URL(page.url()).hash).toBe(`#/board?run=simulation:${saved.id}`)
    await expect(page.getByTestId('simulation-timeline')).toBeVisible({ timeout: 30_000 })
    const token = (await storedSession(page)).token!
    expectSceneUnchanged(await unwrap<Record<string, unknown>>(await request.get(`${apiBaseURL}/api/board/snapshot`, { headers: headers(token) })), scene)
    const restored = await unwrap<{ states: unknown[] }>(await request.get(`${apiBaseURL}/api/simulate/traces/${saved.id}`, {
      headers: headers(token)
    }))
    expect(restored.states).toEqual(saved.states)
  } finally {
    await observer.close()
  }
})

test('a lost rename response reads the actual committed name instead of claiming the save failed or succeeded', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'lostname' })
  await openWorkspace(page, auth)
  await openAccount(page)
  const renamed = uniqueName()
  let committedStatus = 0
  let writes = 0
  // Keep interception active while the app immediately reads back after the aborted write.
  await page.route('**/api/auth/account/username', async route => {
    writes += 1
    const response = await route.fetch()
    committedStatus = response.status()
    if (committedStatus === 200) trackTestAccount(request, { phone: auth.phone, username: renamed, password: PASSWORD })
    await route.abort('failed')
  })
  const confirmation = await prepareUsername(page, renamed)
  await confirmation.getByTestId('account-confirm-username').click()
  await expect(page.getByTestId('account-username-error')).toContainText('Current account details were reloaded from the server')
  expect(committedStatus).toBe(200)
  expect(writes).toBe(1)
  await expect(page.getByLabel('Username', { exact: true })).toHaveValue(renamed)
  await expect(page.getByTestId('account-username-saved')).toHaveCount(0)
  expect((await storedSession(page)).token).toBe(auth.token)
  expect((await storedSession(page)).user.username).toBe(renamed)
  const actual = await unwrap<AccountProfile>(await request.get(`${apiBaseURL}/api/auth/account`, { headers: headers(auth.token) }))
  expect(actual.username).toBe(renamed)
})

test('a lost password response clears the session and requests sign-in confirmation after the backend really committed', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'lostpassword' })
  await openWorkspace(page, auth)
  await openAccount(page)
  await fillPassword(page)
  let committedStatus = 0
  let writes = 0
  await page.route('**/api/auth/account/password', async route => {
    writes += 1
    const response = await route.fetch()
    committedStatus = response.status()
    if (committedStatus === 200) trackTestAccount(request, { phone: auth.phone, username: auth.username, password: NEW_PASSWORD })
    await route.abort('failed')
  })
  await page.getByTestId('account-save-password').click()
  await expect(page).toHaveURL(/reason=password-change-unknown/)
  await expect(page.getByTestId('auth-password-change-notice')).toContainText('could not be confirmed')
  expect(committedStatus).toBe(200)
  expect(writes).toBe(1)
  expect((await storedSession(page)).token).toBeNull()
  expect((await request.get(`${apiBaseURL}/api/auth/account`, { headers: headers(auth.token) })).status()).toBe(401)
  await signIn(page, auth.username, NEW_PASSWORD)
  await openAccount(page)
  await expect(page.getByTestId('account-username-value')).toHaveText(auth.username)
})

test('a background 401 before password response delivery preserves the unknown result and cannot disturb a newer account', async ({ page, request }) => {
  test.setTimeout(120_000)
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'passwordrace' })
  const other = await createAuthenticatedUser(request, { usernamePrefix: 'newsession' })
  await seedScene(request, auth)
  const saved = await unwrap<{ id: number }>(await request.post(`${apiBaseURL}/api/simulate/traces`, {
    headers: headers(auth.token), data: { steps: 1, enablePrivacy: false, attackScenario: { mode: 'NONE' } }
  }))
  expect(saved.id).toBeGreaterThan(0)
  await openWorkspace(page, auth, `/#/board?run=simulation:${saved.id}`)
  await expect(page.getByTestId('simulation-timeline')).toBeVisible({ timeout: 30_000 })
  await openAccount(page)
  await fillPassword(page)

  let holdBackgroundReads = false
  let releaseReads!: () => void
  let releasePasswordResponse!: () => void
  let markCommitted!: (status: number) => void
  const readsReleased = new Promise<void>(resolve => { releaseReads = resolve })
  const passwordResponseReleased = new Promise<void>(resolve => { releasePasswordResponse = resolve })
  const committed = new Promise<number>(resolve => { markCommitted = resolve })
  await page.route('**/api/**', async route => {
    if (holdBackgroundReads && route.request().method() === 'GET') await readsReleased
    await route.fallback()
  })
  await page.route('**/api/auth/account/password', async route => {
    // The preflight has finished. Pin subsequent background reads before allowing the real commit.
    holdBackgroundReads = true
    const response = await route.fetch()
    if (response.status() === 200) trackTestAccount(request, {
      phone: auth.phone, username: auth.username, password: NEW_PASSWORD
    })
    markCommitted(response.status())
    await passwordResponseReleased
    await route.fulfill({ response })
  }, { times: 1 })

  try {
    await page.getByTestId('account-save-password').click()
    expect(await committed).toBe(200)
    const rejectedPoll = page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === '/api/chat/sessions'
      && response.request().headers().authorization === `Bearer ${auth.token}`
      && response.status() === 401)
    // App's real focus listener calls getSessionList through the shared Axios authentication handler.
    await page.evaluate(() => window.dispatchEvent(new Event('focus')))
    releaseReads()
    expect((await rejectedPoll).status()).toBe(401)
    await expect(page).toHaveURL(/reason=password-change-unknown/)
    await expect(page.getByTestId('auth-password-change-notice')).toContainText('could not be confirmed')
    const hash = new URL(page.url()).hash
    const loginQuery = new URLSearchParams(hash.slice(hash.indexOf('?') + 1))
    expect(loginQuery.get('redirect')).toBe(`/board?run=simulation:${saved.id}`)
    expect((await storedSession(page)).token).toBeNull()

    await signIn(page, other.username, PASSWORD)
    // The other account cannot open the original account's run; its Board rejects that deep link.
    await expect(page).toHaveURL(/#\/board$/)
    const newerSession = await storedSession(page)
    expect(newerSession.user.userId).toBe(other.userId)
    const newerURL = page.url()
    const delivered = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/auth/account/password')
    releasePasswordResponse()
    const lateResponse = await delivered
    expect(lateResponse.status()).toBe(200)
    await lateResponse.finished()
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => resolve())))
    expect(page.url()).toBe(newerURL)
    expect(await storedSession(page)).toEqual(newerSession)
    await expect(page.getByTestId('auth-password-change-notice')).toHaveCount(0)

    const changedAccount = await unwrap<AuthUser>(await request.post(`${apiBaseURL}/api/auth/login`, {
      data: { identifier: auth.phone, password: NEW_PASSWORD }
    }))
    expect(changedAccount.userId).toBe(auth.userId)
  } finally {
    releaseReads()
    releasePasswordResponse()
  }
})

test('fullwidth usernames and ASCII phone numbers retain separate owners through rename and reclaim', async ({ request }) => {
  const phoneOwner = await createAuthenticatedUser(request, { usernamePrefix: 'phoneowner', phonePrefix: '138' })
  const fullwidthName = phoneOwner.phone.replace(/[0-9]/g, digit => String.fromCharCode(digit.charCodeAt(0) + 0xFEE0))
  const credentials = { ...createTestAccountCredentials({ usernamePrefix: 'nameowner' }), username: fullwidthName }
  trackTestAccount(request, credentials)
  const nameOwner = await unwrap<AuthUser>(await request.post(`${apiBaseURL}/api/auth/register`, { data: credentials }))
  expect(nameOwner.userId).not.toBe(phoneOwner.userId)

  const login = async (identifier: string) => unwrap<AuthUser>(await request.post(`${apiBaseURL}/api/auth/login`, {
    data: { identifier, password: PASSWORD }
  }))
  expect((await login(fullwidthName)).userId).toBe(nameOwner.userId)
  expect((await login(phoneOwner.phone)).userId).toBe(phoneOwner.userId)

  const relinquishedName = uniqueName()
  await unwrap(await request.patch(`${apiBaseURL}/api/auth/account/username`, {
    headers: headers(nameOwner.token),
    data: { username: relinquishedName, expectedUsername: fullwidthName, currentPassword: PASSWORD }
  }))
  trackTestAccount(request, { phone: nameOwner.phone, username: relinquishedName, password: PASSWORD })
  const nextOwner = await createAuthenticatedUser(request, { usernamePrefix: 'nextowner' })
  await unwrap(await request.patch(`${apiBaseURL}/api/auth/account/username`, {
    headers: headers(nextOwner.token),
    data: { username: fullwidthName, expectedUsername: nextOwner.username, currentPassword: PASSWORD }
  }))
  trackTestAccount(request, { phone: nextOwner.phone, username: fullwidthName, password: PASSWORD })
  expect((await login(fullwidthName)).userId).toBe(nextOwner.userId)
  expect((await login(phoneOwner.phone)).userId).toBe(phoneOwner.userId)
})

test('a queued native authentication event cannot replace a newer UI login or its account details', async ({ page, request }) => {
  const alice = await createAuthenticatedUser(request, { usernamePrefix: 'queuedalice' })
  const bob = await createAuthenticatedUser(request, { usernamePrefix: 'queuedbob' })
  await openWorkspace(page, alice)
  const popup = page.waitForEvent('popup')
  await page.evaluate(() => window.open('/#/?mode=login', 'queued-account-login'))
  const receiver = await popup
  try {
    await expect(receiver.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
    await page.evaluate(() => {
      localStorage.removeItem('iot_verify_token')
      localStorage.removeItem('iot_verify_user')
      localStorage.setItem('iot_verify_auth_sync', JSON.stringify({ token: null, user: null, updatedAt: Date.now() }))
    })
    await expect(receiver.locator('#login-panel')).toBeVisible()
    await receiver.evaluate(({ alice, bobId }) => {
      const probe = { injected: false, events: [] as Array<{ trusted: boolean, payloadUserId: number, currentUserId: number }> }
      ;(window as any).__queuedAuthProbe = probe
      window.addEventListener('storage', event => {
        if (event.key !== 'iot_verify_auth_sync' || !event.newValue) return
        const payload = JSON.parse(event.newValue)
        if (payload.user?.userId !== alice.userId) return
        probe.events.push({
          trusted: event.isTrusted,
          payloadUserId: payload.user.userId,
          currentUserId: JSON.parse(localStorage.getItem('iot_verify_user') || 'null')?.userId
        })
      })
      const originalSetItem = Storage.prototype.setItem
      Storage.prototype.setItem = function (key: string, value: string) {
        if (key === 'iot_verify_token' && !probe.injected) {
          // Queue a real remote snapshot before this UI login finishes its local storage commit.
          // Its native event is delivered after this task, when the receiver already owns Bob.
          probe.injected = true
          const writer = window.opener as Window
          const user = { userId: alice.userId, phone: alice.phone, username: alice.username }
          writer.localStorage.setItem('iot_verify_token', alice.token)
          writer.localStorage.setItem('iot_verify_user', JSON.stringify(user))
          writer.localStorage.setItem('iot_verify_auth_sync', JSON.stringify({ token: alice.token, user, updatedAt: Date.now() }))
        }
        originalSetItem.call(this, key, value)
      }
      ;(window as any).__restoreQueuedAuthProbe = () => { Storage.prototype.setItem = originalSetItem }
      ;(window as any).__queuedAuthExpectedUserId = bobId
    }, { alice, bobId: bob.userId })

    await signIn(receiver, bob.username, PASSWORD)
    await expect.poll(() => receiver.evaluate(() => {
      const probe = (window as any).__queuedAuthProbe
      return probe.events.some((event: any) => event.trusted
        && event.currentUserId === (window as any).__queuedAuthExpectedUserId)
    })).toBe(true)
    const session = await storedSession(receiver)
    expect(session.user.userId).toBe(bob.userId)
    const profileResponse = receiver.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === '/api/auth/account')
    const dialog = await openAccount(receiver)
    const response = await profileResponse
    const profile = await unwrap<AccountProfile>(response)
    expect(profile.userId).toBe(bob.userId)
    expect(response.request().headers().authorization).toBe(`Bearer ${session.token}`)
    await expect(dialog.getByTestId('account-username-value')).toHaveText(bob.username)
    await expect(dialog.getByTestId('account-phone')).toHaveText(bob.phone)

    await prepareUsername(receiver, uniqueName())
    await receiver.getByTestId('account-cancel-username-confirmation').click()
    await expect(receiver.getByTestId('account-confirm-username-dialog')).toHaveCount(0)
    await fillPassword(receiver)
    await receiver.getByTestId('account-back').click()
    const reopened = await openAccount(receiver)
    await expect(reopened.locator('input')).toHaveCount(0)
    await expect(reopened.getByTestId('account-username-value')).toHaveText(bob.username)
    await reopened.getByTestId('account-edit-password').click()
    for (const id of ['account-current-password', 'account-new-password', 'account-confirm-password']) {
      await expect(reopened.locator(`#${id}`)).toHaveValue('')
    }
    await reopened.getByTestId('account-cancel-password').click()
    const confirmation = await prepareUsername(receiver, uniqueName(), '')
    await expect(confirmation.locator('#account-username-password')).toHaveValue('')
    expect(await storedSession(receiver)).toEqual(session)
  } finally {
    if (!receiver.isClosed()) {
      await receiver.evaluate(() => (window as any).__restoreQueuedAuthProbe?.())
      await receiver.close()
    }
  }
})

test('browser Back cannot discard the result of an in-flight account mutation', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'accountback' })
  await openWorkspace(page, auth)
  await openAccount(page)
  const accountURL = page.url()
  const renamed = uniqueName()
  let releaseResponse!: () => void
  let markCommitted!: (response: APIResponse) => void
  const responseReleased = new Promise<void>(resolve => { releaseResponse = resolve })
  const committed = new Promise<APIResponse>(resolve => { markCommitted = resolve })
  await page.route('**/api/auth/account/username', async route => {
    const response = await route.fetch()
    if (response.status() === 200) trackTestAccount(request, { phone: auth.phone, username: renamed, password: PASSWORD })
    markCommitted(response)
    await responseReleased
    await route.fulfill({ response })
  })
  try {
    const responseDelivered = submitUsername(page, renamed)
    await unwrap(await committed)
    await expect(page.getByTestId('account-back')).toBeDisabled()
    await page.evaluate(() => history.back())
    await expect(page).toHaveURL(accountURL)
    await expect(page.getByTestId('account-page')).toBeVisible()
    releaseResponse()
    await unwrap(await responseDelivered)
    await expect(page.getByTestId('account-username-form')).toHaveCount(0)
    await expect(page.getByTestId('account-username-value')).toHaveText(renamed)
    await page.goBack()
    await expect(page.getByTestId('board-root')).toBeVisible()
    await page.goForward()
    await expect(page.getByTestId('account-page')).toBeVisible()
    await expect(page.getByTestId('account-username-value')).toHaveText(renamed)
    await expect(page.getByTestId('account-page').locator('input')).toHaveCount(0)
    const confirmation = await prepareUsername(page, uniqueName(), '')
    await expect(confirmation.locator('#account-username-password')).toHaveValue('')
  } finally {
    releaseResponse()
  }
})

test('a real account switch replaces account details and clears the previous private drafts', async ({ page, request }) => {
  const alice = await createAuthenticatedUser(request, { usernamePrefix: 'accountalice' })
  const bob = await createAuthenticatedUser(request, { usernamePrefix: 'accountbob' })
  await openWorkspace(page, alice)
  await openAccount(page)
  await prepareUsername(page, uniqueName())
  await page.getByTestId('account-cancel-username-confirmation').click()
  await expect(page.getByTestId('account-confirm-username-dialog')).toHaveCount(0)
  await fillPassword(page)
  const accountURL = page.url()
  const changer = await page.context().newPage()
  try {
    await changer.goto('/#/board')
    await expect(changer.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
    await changer.evaluate(bob => {
      const user = { userId: bob.userId, username: bob.username, phone: bob.phone }
      localStorage.setItem('iot_verify_token', bob.token)
      localStorage.setItem('iot_verify_user', JSON.stringify(user))
      localStorage.setItem('iot_verify_auth_sync', JSON.stringify({ token: bob.token, user, updatedAt: Date.now() }))
    }, bob)
    const account = page.getByTestId('account-page')
    await expect(account).toBeVisible()
    await expect(account.getByTestId('account-phone')).toHaveText(bob.phone)
    await expect(account.getByTestId('account-username-value')).toHaveText(bob.username)
    await expect(page).toHaveURL(accountURL)
    expect((await storedSession(page)).user.userId).toBe(bob.userId)
    expect((await storedSession(page)).token).toBe(bob.token)
    await expect(page.getByTestId('account-confirm-username-dialog')).toHaveCount(0)
    await expect(account.locator('input')).toHaveCount(0)
    await account.getByTestId('account-edit-password').click()
    for (const id of ['account-current-password', 'account-new-password', 'account-confirm-password']) {
      await expect(account.locator(`#${id}`)).toHaveValue('')
    }
    await account.getByTestId('account-cancel-password').click()
    const confirmation = await prepareUsername(page, uniqueName(), '')
    await expect(confirmation.locator('#account-username-password')).toHaveValue('')
  } finally {
    await changer.close()
  }
})
