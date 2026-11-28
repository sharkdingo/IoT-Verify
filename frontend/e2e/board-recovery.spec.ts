import { desktopBoardViewport, openBoardWorkspace } from './support/boardWorkspace'
import { openRecommendation } from './support/recommendations'
import { afterHistoryRender, holdHistoryReply } from './support/historyReply'
import { type Page } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'

const narrow = { width: 600, height: 900 }
const layoutPath = '/api/board/layout'

const savedLayout = {
  canvasPan: { x: 73, y: 41 }, canvasZoom: 0.65,
  panels: {
    control: { collapsed: false, width: 300, activeSection: 'templates' },
    inspector: { collapsed: false, width: 280, activeSection: 'devices' }
  }
}

const canvasPose = (page: Page) => page.getByTestId('canvas-board').locator('.canvas-inner').evaluate(element => {
  const { a, e, f } = new DOMMatrixReadOnly(getComputedStyle(element).transform)
  return { canvasPan: { x: e, y: f }, canvasZoom: a }
})

const editViewport = async (page: Page) => {
  const before = await canvasPose(page)
  const box = (await page.getByTestId('canvas-board').boundingBox())!
  const x = box.x + box.width / 2
  const y = box.y + box.height * 0.75
  await page.mouse.move(x, y)
  await page.mouse.down()
  await page.mouse.move(x + 64, y - 32)
  await page.mouse.up()
  await expect.poll(() => canvasPose(page)).toMatchObject({
    canvasPan: { x: before.canvasPan.x + 64, y: before.canvasPan.y - 32 }
  })
  const panned = await canvasPose(page)
  await page.keyboard.down('Control')
  try {
    await page.mouse.wheel(120, 0)
    await afterHistoryRender(page)
    expect(await canvasPose(page), 'a horizontal wheel gesture must leave the canvas viewport alone').toEqual(panned)
    await page.mouse.wheel(0, 120)
  } finally {
    await page.keyboard.up('Control')
  }
  await expect.poll(async () => (await canvasPose(page)).canvasZoom).toBe(0.9)
  return canvasPose(page)
}

test('saved layout restoration waits for the scene and does not wait for background task summaries', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'layoutready' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  const saved = await request.post(`${apiBaseURL}/api/board/layout`, {
    headers, data: savedLayout
  })
  const seeded = await request.post(`${apiBaseURL}/api/board/nodes`, {
    headers, data: { devices: [{ id: 'layout_light', templateName: 'Light', label: 'Layout test light',
      position: { x: 120, y: 120 }, width: 176, height: 128, state: 'off' }] }
  })
  expect(saved.ok()).toBe(true)
  expect(seeded.ok(), await seeded.text()).toBe(true)
  let writes = 0
  page.on('request', outgoing => {
    if (outgoing.method() === 'POST' && new URL(outgoing.url()).pathname === layoutPath) writes += 1
  })
  await page.clock.install()
  const snapshot = await holdHistoryReply(page, '/api/board/snapshot')
  let releaseTasks!: () => void
  const taskGate = new Promise<void>(resolve => { releaseTasks = resolve })
  const tasksRead = page.waitForRequest(outgoing => new URL(outgoing.url()).pathname === '/api/fuzz/tasks')
  await page.route(url => url.pathname === '/api/fuzz/tasks', async route => {
    await taskGate
    await route.fulfill({ json: { code: 200, data: [] } })
  })
  const layoutRead = page.waitForResponse(response =>
    new URL(response.url()).pathname === layoutPath && response.request().method() === 'GET')
  const opened = openBoardWorkspace(page, auth)
  try {
    await tasksRead
    const layout = await layoutRead
    expect(layout.ok()).toBe(true)
    await layout.finished()
    const zoom = page.getByTestId('canvas-map-zoom-input')
    await expect(zoom).toHaveValue('100')

    snapshot.release()
    await expect(zoom).toHaveValue('65')
  } finally {
    snapshot.release()
    releaseTasks()
    await opened
  }

  const tasksReply = await (await tasksRead).response()
  expect(tasksReply?.ok()).toBe(true)
  await tasksReply!.finished()
  await page.clock.runFor(1000)
  await afterHistoryRender(page)
  expect(writes, 'initial layout hydration must not save').toBe(0)
  const control = page.getByTestId('control-center')
  await page.setViewportSize(narrow)
  await expect(control).toHaveClass(/is-collapsed/)
  await page.setViewportSize(desktopBoardViewport)
  await expect(control).toHaveClass(/is-expanded/)
  await expect.poll(() => canvasPose(page)).toEqual({
    canvasPan: savedLayout.canvasPan, canvasZoom: savedLayout.canvasZoom
  })
  await page.clock.runFor(1000)
  await afterHistoryRender(page)
  expect(writes, 'a resize alone must not save desktop layout').toBe(0)

  await page.setViewportSize(narrow)
  const delayed = await holdHistoryReply(page, layoutPath)
  let edited: Awaited<ReturnType<typeof canvasPose>>
  try {
    await page.reload()
    await delayed.requested
    await expect(page.getByTestId('scene-import')).toBeEnabled()
    edited = await editViewport(page)
  } finally {
    delayed.release()
  }
  expect((await delayed.delivered()).ok()).toBe(true)
  await expect.poll(() => canvasPose(page)).toEqual(edited)
  await page.clock.runFor(1000)
  await afterHistoryRender(page)
  expect(writes, 'narrow navigation must leave desktop preferences alone').toBe(0)
})

test('a failed saved-layout read remains recoverable without replacing the saved view', { tag: '@smoke' }, async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'layoutretry' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  const saved = await request.post(`${apiBaseURL}/api/board/layout`, { headers, data: savedLayout })
  expect(saved.ok()).toBe(true)
  let layoutReply: 'unavailable' | 'incomplete' | 'real' = 'unavailable'
  await page.route('**/api/board/layout', async route => {
    if (route.request().method() !== 'GET') return route.fallback()
    if (layoutReply === 'unavailable') {
      await route.fulfill({ status: 503, contentType: 'application/json',
        body: JSON.stringify({ code: 503, message: 'Layout storage unavailable' }) })
      return
    }
    if (layoutReply === 'incomplete') {
      await route.fulfill({ json: { code: 200, data: { canvasZoom: 0.45 } } })
      return
    }
    await route.fallback()
  })
  await openBoardWorkspace(page, auth)
  await page.clock.install()
  const failure = page.getByTestId('board-layout-load-error')
  await expect(failure).toBeVisible()
  await expect(failure).toContainText('Viewport and panel changes will not be saved')
  await expect(failure.getByTestId('failure-cause')).toHaveText('Layout storage unavailable')
  await expect(page.locator('.el-message--error')).toHaveCount(0)
  const preserved = await request.get(`${apiBaseURL}/api/board/layout`, { headers })
  expect(preserved.ok()).toBe(true)
  expect((await preserved.json()).data).toMatchObject(savedLayout)

  const retry = failure.getByRole('button', { name: 'Retry', exact: true })
  layoutReply = 'incomplete'
  await retry.click()
  await expect(failure.getByTestId('failure-cause')).toContainText('incomplete authoritative result')
  await expect(page.getByTestId('canvas-map-zoom-input')).not.toHaveValue('45')

  layoutReply = 'real'
  const delayed = await holdHistoryReply(page, layoutPath)
  let writes = 0
  const writeReturned = page.waitForResponse(response => {
    const outgoing = response.request()
    return outgoing.method() === 'POST' && new URL(outgoing.url()).pathname === layoutPath
  })
  page.on('request', outgoing => {
    if (outgoing.method() === 'POST' && new URL(outgoing.url()).pathname === layoutPath) writes += 1
  })
  let edited: Awaited<ReturnType<typeof canvasPose>>
  try {
    await Promise.all([retry.click(), delayed.requested])
    edited = await editViewport(page)
    await page.clock.runFor(1000)
    await afterHistoryRender(page)
    expect(writes, 'a failed read must leave layout persistence unarmed until Retry succeeds').toBe(0)
  } finally {
    delayed.release()
  }
  expect((await delayed.delivered()).ok()).toBe(true)
  await expect(failure).toBeHidden()
  await expect(page.locator('.el-message--error')).toHaveCount(0)
  await expect.poll(() => canvasPose(page)).toEqual(edited)
  const persisted = await writeReturned
  expect(persisted.ok()).toBe(true)
  const layout = (await persisted.json()).data
  await afterHistoryRender(page)
  expect(writes).toBe(1)
  expect(layout).toMatchObject({ canvasZoom: edited.canvasZoom, panels: savedLayout.panels })
  expect(layout.canvasPan.x).toBeCloseTo(edited.canvasPan.x, 3)
  expect(layout.canvasPan.y).toBeCloseTo(edited.canvasPan.y, 3)
})

test('an incomplete saved-layout receipt keeps the edited view and reports it unconfirmed', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'layoutreceipt' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  const saved = await request.post(`${apiBaseURL}/api/board/layout`, { headers, data: savedLayout })
  expect(saved.ok()).toBe(true)
  await openBoardWorkspace(page, auth)
  const zoom = page.getByTestId('canvas-map-zoom-input')
  await expect(zoom).toHaveValue('65')
  await page.clock.install()

  let writes = 0
  await page.route('**/api/board/layout', async route => {
    if (route.request().method() !== 'POST') return route.fallback()
    writes += 1
    const committed = await route.fetch()
    expect(committed.ok(), await committed.text()).toBe(true)
    await route.fulfill({ json: { code: 200, data: {} } })
  })

  await zoom.fill('95')
  await page.clock.runFor(1000)
  await expect(page.getByText('Layout save is unconfirmed. Your changes remain visible here.')).toBeVisible()
  await expect(page.getByText('Failed to save layout', { exact: true })).toHaveCount(0)
  await expect(zoom).toHaveValue('95')
  await page.clock.runFor(1000)
  expect(writes).toBe(1)

  const persisted = await request.get(`${apiBaseURL}/api/board/layout`, { headers })
  expect(persisted.ok()).toBe(true)
  expect((await persisted.json()).data).toMatchObject({ canvasZoom: 0.95 })
})

test('switching accounts isolates a pending layout read and the previous board layout save', async ({ page, request }) => {
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
  const switchAccount = (auth: AuthUser, queueSave = false) => page.evaluate(async ({ token, user, queueSave }) => {
    if (queueSave) {
      document.querySelector<HTMLButtonElement>('[data-testid="inspector-tab-rules"]')!.click()
      // Leave the edit queued when the shared session switches this event turn.
      await Promise.resolve()
    }
    localStorage.setItem('iot_verify_token', token)
    localStorage.setItem('iot_verify_user', JSON.stringify(user))
    const value = JSON.stringify({ token, user, updatedAt: Date.now() })
    localStorage.setItem('iot_verify_auth_sync', value)
    window.dispatchEvent(new StorageEvent('storage', {
      key: 'iot_verify_auth_sync', newValue: value, storageArea: localStorage
    }))
  }, { token: auth.token, user: { userId: auth.userId, phone: auth.phone, username: auth.username }, queueSave })

  const delayed = await holdHistoryReply(page, layoutPath)
  const secondPose = { canvasPan: secondLayout.canvasPan, canvasZoom: secondLayout.canvasZoom }
  try {
    await openBoardWorkspace(page, first)
    await delayed.requested
    await switchAccount(second)
    await expect.poll(() => canvasPose(page)).toEqual(secondPose)
  } finally {
    delayed.release()
  }
  expect((await (await delayed.delivered()).json()).data).toMatchObject(savedLayout)
  await expect.poll(() => canvasPose(page)).toEqual(secondPose)
  await expect(page.getByTestId('board-layout-load-error')).toHaveCount(0)

  await switchAccount(first, true)
  await expect(page.getByTestId('canvas-map-zoom-input')).toHaveValue('65')
  for (const [auth, layout] of [[first, savedLayout], [second, secondLayout]] as const) {
    const preserved = await request.get(`${apiBaseURL}${layoutPath}`, {
      headers: { Authorization: `Bearer ${auth.token}` }
    })
    expect(preserved.ok()).toBe(true)
    expect((await preserved.json()).data).toMatchObject(layout)
  }
})

test('a late recommendation response after an unconfirmed stop cannot restore candidates', async ({ page, request }) => {
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
    await route.fulfill({ json: { code: 200, message: 'success', data: {
      message: 'Late recommendations', count: 1, requestedCount: 5, validatedCount: 1,
      filteredCount: 0, filteredItems: [], adjustedCount: 0, adjustedItems: [],
      rawCandidateCount: 1, inspectedCount: 1, truncatedCount: 0,
      recommendations: [{ name: 'Late rule', reason: 'Delayed result',
        conditions: [{ deviceId: 'source', attribute: 'on', targetType: 'api' }],
        command: { deviceId: 'target', action: 'off' } }]
    } } }).catch(() => undefined)
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
      if (cancelRequested) {
        await route.fulfill({
          status: 404,
          contentType: 'application/json',
          body: JSON.stringify({ code: 404, message: 'request registration not visible yet', data: null })
        })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'success',
          data: {
            requestId,
            state: 'RUNNING',
            stage: 'REQUESTING_MODEL',
            elapsedMs: 1000
          }
        })
      })
      return
    }
    await route.fallback()
  })

  try {
    await openBoardWorkspace(page, auth)
    await openRecommendation(page, 'rule')
    const action = page.getByTestId('generate-rule-recommendations')
    await action.click()
    await recommendationStarted
    await expect(action).toContainText('Stop', { timeout: 5_000 })

    await action.click()
    await expect.poll(() => cancelRequested).toBe(true)
    await expect(action).toContainText('Check request status', { timeout: 15_000 })
    await expect.poll(() => postCancelStatusReads).toBeGreaterThanOrEqual(5)
    const lateResponse = page.waitForResponse(response => response.url().endsWith('/api/board/rules/recommend') && response.ok())
    releaseRecommendation()
    await lateResponse
    await expect(action).toContainText('Generate', { timeout: 10_000 })
    await expect(page.getByTestId('rule-recommendation-description')).toHaveCount(0)
    await expect(page.getByText('Rule recommendation stop requested', { exact: true })).toHaveCount(0)
  } finally {
    releaseRecommendation()
  }
})

test('rule recommendation aborts only after an accepted stop and waits for its worker', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'rulestop' })
  let releasePost!: () => void
  const postRelease = new Promise<void>(resolve => { releasePost = resolve })
  let acknowledgeStop!: () => void
  const stopAcknowledgement = new Promise<void>(resolve => { acknowledgeStop = resolve })
  let accepted = false
  let finished = false
  const posts: Array<{ requestId: string; authorization: string }> = []
  const controls: Array<{ requestId: string; authorization: string; method: string; accepted: boolean }> = []
  const failures: Array<{ accepted: boolean; error: string | undefined }> = []

  page.on('requestfailed', post => {
    if (post.url().endsWith('/api/board/rules/recommend') && post.postDataJSON().requestId === posts[0]?.requestId) {
      failures.push({ accepted, error: post.failure()?.errorText })
    }
  })
  await page.route('**/api/board/rules/recommend', async route => {
    const post = route.request()
    posts.push({ requestId: post.postDataJSON().requestId, authorization: post.headers().authorization })
    if (posts.length === 1) await postRelease
    await route.fulfill({ status: 503, json: { code: 503, message: 'AI recommendation was cancelled.', data: null } })
      .catch(() => undefined) // The first POST can already have been aborted by the browser.
  })
  await page.route('**/api/board/recommendations/**', async route => {
    const control = route.request()
    const requestId = decodeURIComponent(new URL(control.url()).pathname.split('/').pop()!)
    const method = control.method()
    controls.push({ requestId, authorization: control.headers().authorization, method, accepted })
    if (method === 'DELETE') {
      await stopAcknowledgement
      accepted = true
      await route.fulfill({ json: { code: 200, message: 'success', data: true } })
      return
    }
    await route.fulfill({ json: { code: 200, message: 'success', data: {
      requestId, state: finished ? 'FINISHED' : 'RUNNING',
      stage: accepted ? 'CANCELLING' : 'REQUESTING_MODEL', elapsedMs: 1000
    } } })
  })

  try {
    await openBoardWorkspace(page, auth)
    await openRecommendation(page, 'rule')
    const action = page.getByTestId('generate-rule-recommendations')
    await action.click()
    await expect.poll(() => posts).toEqual([{ requestId: expect.stringMatching(/.+/), authorization: `Bearer ${auth.token}` }])
    await action.click()
    await expect.poll(() => controls.some(control => control.method === 'DELETE')).toBe(true)
    await expect(action).toContainText('Stop')
    expect(failures, 'the rule POST must stay pending while DELETE awaits acknowledgement').toEqual([])

    acknowledgeStop()
    await expect.poll(() => failures, { message: 'the accepted stop must abort the pending rule POST' })
      .toEqual([{ accepted: true, error: 'net::ERR_ABORTED' }])
    await expect.poll(() => controls.filter(control => control.method === 'GET' && control.accepted).length)
      .toBeGreaterThanOrEqual(2)
    await expect(action).toContainText('Stop')

    finished = true
    await expect(action).toContainText('Generate', { timeout: 10_000 })
    for (const control of controls) expect(control).toMatchObject(posts[0])
    await action.click()
    await expect.poll(() => posts).toHaveLength(2)
    expect(posts[1].requestId).not.toBe(posts[0].requestId)
    expect(posts[1].authorization).toBe(posts[0].authorization)
  } finally {
    releasePost()
    acknowledgeStop()
  }
})

test('device recommendation stays stopping when the POST returns before its worker exits', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'devicestop' })
  let releasePost!: () => void
  const postRelease = new Promise<void>(resolve => { releasePost = resolve })
  let acknowledgeStop!: () => void
  const stopAcknowledgement = new Promise<void>(resolve => { acknowledgeStop = resolve })
  let stopRequested = false
  let allowAcknowledgement = false
  let finished = false
  let stoppingReads = 0
  let deviceRequestId: string | null = null
  const controlRequestIds = new Set<string>()

  await page.route('**/api/board/devices/recommend?**', async route => {
    deviceRequestId = new URL(route.request().url()).searchParams.get('requestId')
    await postRelease
    await route.fulfill({ status: 503, json: { code: 503, message: 'AI recommendation was cancelled.', data: null } })
  })
  await page.route('**/api/board/recommendations/**', async route => {
    const requestId = decodeURIComponent(new URL(route.request().url()).pathname.split('/').pop()!)
    controlRequestIds.add(requestId)
    if (route.request().method() === 'DELETE') {
      stopRequested = true
      await stopAcknowledgement
      await route.fulfill({ json: { code: 200, message: 'success', data: true } })
      return
    }
    if (allowAcknowledgement && !finished) stoppingReads += 1
    await route.fulfill({ json: { code: 200, message: 'success', data: {
      requestId, state: finished ? 'FINISHED' : 'RUNNING',
      stage: stopRequested ? 'CANCELLING' : 'REQUESTING_MODEL', elapsedMs: 1000
    } } })
  })

  try {
    await openBoardWorkspace(page, auth)
    await openRecommendation(page, 'device')
    const action = page.getByTestId('generate-device-recommendations')
    await action.click()
    await expect.poll(() => deviceRequestId).toBeTruthy()
    await action.click()
    await expect.poll(() => stopRequested).toBe(true)
    const postReturned = page.waitForResponse(response =>
      new URL(response.url()).pathname === '/api/board/devices/recommend' && response.status() === 503
    )
    releasePost()
    await postReturned
    allowAcknowledgement = true
    acknowledgeStop()
    await expect.poll(() => stoppingReads).toBeGreaterThan(0)
    await expect(action).toContainText('Stop')
    expect([...controlRequestIds]).toEqual([deviceRequestId])

    finished = true
    await expect(action).toContainText('Generate', { timeout: 10_000 })
    await expect(page.getByTestId('device-recommendation-error')).toHaveCount(0)
  } finally {
    releasePost()
    acknowledgeStop()
  }
})

test('an unregistered recommendation keeps its original id while verification remains available', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'specpostloss' })
  const postedIds: string[] = []
  const controlRequests: Array<{ requestId: string; auth: string }> = []
  let missingStatusReads = 0
  let stopAttempts = 0
  let finished = false

  await page.route('**/api/board/specs/recommend', async route => {
    postedIds.push(route.request().postDataJSON().requestId)
    if (postedIds.length === 1) {
      await route.abort('failed')
      return
    }
    await route.fulfill({ status: 503, json: { code: 503, message: 'test complete', data: null } })
  })
  await page.route('**/api/board/recommendations/**', async route => {
    const requestId = decodeURIComponent(new URL(route.request().url()).pathname.split('/').pop()!)
    controlRequests.push({ requestId, auth: route.request().headers().authorization })
    if (route.request().method() === 'DELETE') {
      stopAttempts += 1
      await route.fulfill({ json: { code: 200, message: 'success', data: false } })
      return
    }
    if (!finished) {
      missingStatusReads += 1
      await route.fulfill({ status: 404, json: { code: 404, message: 'not registered yet', data: null } })
      return
    }
    await route.fulfill({ json: { code: 200, message: 'success', data: {
      requestId, state: 'FINISHED', stage: 'CANCELLING', elapsedMs: 1000
    } } })
  })

  await openBoardWorkspace(page, auth)
  await openRecommendation(page, 'spec')
  const action = page.getByTestId('generate-spec-recommendations')
  const failure = page.getByTestId('spec-recommendation-error')
  await action.click()
  await expect(failure).toContainText(/confirm|recover/i)
  await expect(action).toContainText('Check request status')
  await expect(page.getByTestId('spec-recommendation-panel').getByText('AI recommendation in progress')).toHaveCount(0)
  const verification = page.getByTestId('open-verification-panel')
  await expect(verification).toBeEnabled()
  await verification.click()
  await expect(verification).toHaveAttribute('aria-pressed', 'true')
  await verification.click()
  await openRecommendation(page, 'device')
  await expect(page.getByTestId('generate-device-recommendations')).toBeDisabled()
  await openRecommendation(page, 'spec')
  await expect.poll(() => missingStatusReads, { timeout: 15_000 }).toBeGreaterThanOrEqual(6)
  const previousStopAttempts = stopAttempts
  await action.click()
  await expect.poll(() => stopAttempts, { timeout: 8_000 }).toBeGreaterThan(previousStopAttempts)
  await expect(action).toContainText('Check request status')
  await expect(verification).toBeEnabled()
  await expect(page.getByText('Specification recommendation stop requested', { exact: true })).toHaveCount(0)
  expect(postedIds).toHaveLength(1)
  expect(controlRequests.every(item =>
    item.requestId === postedIds[0] && item.auth === `Bearer ${auth.token}`
  )).toBe(true)

  finished = true
  await expect(failure).toContainText('The server request has ended', { timeout: 10_000 })
  await expect(action).toContainText('Generate')
  await action.click()
  await expect.poll(() => postedIds.length).toBe(2)
  expect(postedIds[1]).not.toBe(postedIds[0])
})
