import { type Page } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'

const openWorkspace = async (page: Page, auth: AuthUser) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.addInitScript(({ token, user }) => {
    window.localStorage.setItem('iot_verify_token', token)
    window.localStorage.setItem('iot_verify_user', JSON.stringify(user))
    window.localStorage.setItem('iot_verify_theme', 'light')
    window.localStorage.setItem('locale', 'en')
  }, {
    token: auth.token,
    user: {
      userId: auth.userId,
      phone: auth.phone,
      username: auth.username
    }
  })
  await page.goto('/#/board')
  await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
}

const savedLayout = {
  canvasPan: { x: 73, y: 41 }, canvasZoom: 0.65,
  panels: {
    control: { collapsed: false, width: 300, activeSection: 'templates' },
    inspector: { collapsed: false, width: 280, activeSection: 'devices' }
  }
}

test('saved layout restoration does not wait for background task summaries', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'layoutready' })
  const saved = await request.post(`${apiBaseURL}/api/board/layout`, {
    headers: { Authorization: `Bearer ${auth.token}` }, data: savedLayout
  })
  expect(saved.ok()).toBe(true)
  let releaseTasks!: () => void
  const taskRelease = new Promise<void>(resolve => { releaseTasks = resolve })
  let markTasksRead!: () => void
  const tasksRead = new Promise<void>(resolve => { markTasksRead = resolve })
  await page.route(url => url.pathname === '/api/fuzz/tasks', async route => {
    const response = await route.fetch()
    markTasksRead()
    await taskRelease
    await route.fulfill({ response })
  })
  try {
    await openWorkspace(page, auth)
    await tasksRead
    await expect(page.getByTestId('canvas-map-zoom-input')).toHaveValue('65')
  } finally {
    releaseTasks()
  }
})

test('a failed saved-layout read remains recoverable without replacing the saved view', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'layoutretry' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  const saved = await request.post(`${apiBaseURL}/api/board/layout`, { headers, data: savedLayout })
  expect(saved.ok()).toBe(true)
  let rejectLayout = true
  await page.route('**/api/board/layout', async route => {
    if (route.request().method() === 'GET' && rejectLayout) {
      await route.fulfill({ status: 503, contentType: 'application/json',
        body: JSON.stringify({ code: 503, message: 'Layout storage unavailable' }) })
      return
    }
    await route.fallback()
  })
  await openWorkspace(page, auth)
  const failure = page.getByTestId('board-layout-load-error')
  await expect(failure).toBeVisible()
  await expect(failure).toContainText('Viewport and panel changes will not be saved')
  const preserved = await request.get(`${apiBaseURL}/api/board/layout`, { headers })
  expect(preserved.ok()).toBe(true)
  expect((await preserved.json()).data).toMatchObject(savedLayout)

  rejectLayout = false
  await failure.getByRole('button', { name: 'Retry', exact: true }).click()
  await expect(failure).toBeHidden()
  await expect(page.getByTestId('canvas-map-zoom-input')).toHaveValue('65')
})

test('switching accounts does not flush the previous board layout into the new account', async ({ page, request }) => {
  const first = await createAuthenticatedUser(request, { usernamePrefix: 'layoutfirst' })
  const second = await createAuthenticatedUser(request, { usernamePrefix: 'layoutsecond' })
  const secondLayout = {
    canvasPan: { x: -128, y: 57 }, canvasZoom: 1.15,
    panels: {
      control: { collapsed: false, width: 260, activeSection: 'templates' },
      inspector: { collapsed: false, width: 300, activeSection: 'devices' }
    }
  }
  for (const [auth, layout] of [[first, savedLayout], [second, secondLayout]] as const) {
    const saved = await request.post(`${apiBaseURL}/api/board/layout`, {
      headers: { Authorization: `Bearer ${auth.token}` }, data: layout
    })
    expect(saved.ok()).toBe(true)
  }
  await openWorkspace(page, first)
  await expect(page.getByTestId('canvas-map-zoom-input')).toHaveValue('65')
  await page.evaluate(async ({ token, user }) => {
    document.querySelector<HTMLButtonElement>('[data-testid="inspector-tab-rules"]')!.click()
    // Give the edit its pending save before changing the shared session in this same event turn.
    await Promise.resolve()
    localStorage.setItem('iot_verify_token', token)
    localStorage.setItem('iot_verify_user', JSON.stringify(user))
    const value = JSON.stringify({ token, user, updatedAt: Date.now() })
    localStorage.setItem('iot_verify_auth_sync', value)
    window.dispatchEvent(new StorageEvent('storage', {
      key: 'iot_verify_auth_sync', newValue: value, storageArea: localStorage
    }))
  }, { token: second.token, user: { userId: second.userId, phone: second.phone, username: second.username } })
  await expect(page.getByTestId('canvas-map-zoom-input')).toHaveValue('115')
  const preserved = await request.get(`${apiBaseURL}/api/board/layout`, {
    headers: { Authorization: `Bearer ${second.token}` }
  })
  expect(preserved.ok()).toBe(true)
  expect((await preserved.json()).data).toMatchObject(secondLayout)
})

test('a rejected recommendation stop converges when status becomes terminal', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'recommendstop' })
  let releaseRecommendation!: () => void
  const recommendationRelease = new Promise<void>(resolve => { releaseRecommendation = resolve })
  let markRecommendationStarted!: () => void
  const recommendationStarted = new Promise<void>(resolve => { markRecommendationStarted = resolve })
  let cancelRequested = false
  let postCancelStatusReads = 0

  await page.route('**/api/board/rules/recommend', async route => {
    markRecommendationStarted()
    await recommendationRelease
    await route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ code: 503, message: 'released test request', data: null })
    }).catch(() => undefined)
  })
  await page.route('**/api/board/recommendations/**', async route => {
    const method = route.request().method()
    const requestId = decodeURIComponent(new URL(route.request().url()).pathname.split('/').pop() || '')
    if (method === 'DELETE') {
      cancelRequested = true
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'success', data: false })
      })
      return
    }
    if (method === 'GET') {
      if (cancelRequested) postCancelStatusReads += 1
      if (cancelRequested && postCancelStatusReads === 1) {
        await route.fulfill({
          status: 404,
          contentType: 'application/json',
          body: JSON.stringify({ code: 404, message: 'request registration not visible yet', data: null })
        })
        return
      }
      const finished = cancelRequested && postCancelStatusReads >= 3
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'success',
          data: {
            requestId,
            state: finished ? 'FINISHED' : 'RUNNING',
            stage: finished ? 'CANCELLING' : 'REQUESTING_MODEL',
            elapsedMs: 1000
          }
        })
      })
      return
    }
    await route.fallback()
  })

  try {
    await openWorkspace(page, auth)
    await page.getByTestId('open-rule-recommendations').click()
    const action = page.getByTestId('generate-rule-recommendations')
    await action.click()
    await recommendationStarted
    await expect(action).toContainText('Stop', { timeout: 5_000 })

    await action.click()
    await expect.poll(() => cancelRequested).toBe(true)
    await expect(action).toContainText('Generate', { timeout: 10_000 })
    await expect.poll(() => postCancelStatusReads).toBeGreaterThanOrEqual(3)
  } finally {
    releaseRecommendation()
  }
})
