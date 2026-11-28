import type { APIRequestContext, Route } from '@playwright/test'
import { unwrap } from './support/apiResponse'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'
import { selectHistoryResultType } from './support/historyFilter'
import { holdHistoryReply } from './support/historyReply'

const resultEndpoints = ['/api/verify/runs', '/api/simulate/traces', '/api/fuzz/runs']

const unavailableRun = (id: number) => ({
  id, initiator: 'USER', dataAvailable: false, unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID'
})

const unavailableFuzzingRun = (id: number) => ({ ...unavailableRun(id), findings: [] })

const persistedState = async (request: APIRequestContext, auth: AuthUser) => {
  const headers = { Authorization: `Bearer ${auth.token}` }
  const [snapshot, history, runs] = await Promise.all([
    request.get(`${apiBaseURL}/api/board/snapshot`, { headers }).then(unwrap),
    request.get(`${apiBaseURL}/api/board/edits/clear-preview`, { headers }).then(unwrap),
    request.get(`${apiBaseURL}/api/verify/runs`, { headers }).then(unwrap)
  ])
  return { snapshot, history, runs }
}

test('board and result history read failures retain diagnostics once and recover without writes', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'readFeedback' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  await unwrap(await request.post(`${apiBaseURL}/api/board/layout`, {
    headers, data: { canvasPan: { x: 0, y: 0 }, canvasZoom: 0.7, panels: {
      control: { collapsed: false, width: 300, activeSection: 'templates' },
      inspector: { collapsed: false, width: 280, activeSection: 'devices' }
    } }
  }))
  const before = await persistedState(request, auth)
  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('locale', 'zh-CN')
  }, auth)
  const cause = 'Read transaction timed out'
  const field = 'The database could not return this collection'
  const failedRead = {
    status: 503, json: {
      code: 503, message: cause, data: { errors: { collection: field } }
    }
  }
  const failRead = (route: Route) => route.fulfill(failedRead)
  let failVerification = false
  let revision = 0
  const resultLists = (url: URL) => resultEndpoints.includes(url.pathname)
  const readResultLists = (route: Route) => {
    const endpoint = new URL(route.request().url()).pathname
    if (endpoint === resultEndpoints[0] && failVerification) return failRead(route)
    const data = endpoint === resultEndpoints[0]
      ? { ...unavailableRun(10), counterexampleCount: 0, counterexamples: [] }
      : endpoint === resultEndpoints[1] ? unavailableRun(20 + revision) : unavailableFuzzingRun(30 + revision)
    return route.fulfill({ json: { code: 200, data: [data] } })
  }
  await page.route('**/api/board/snapshot', failRead)
  await page.route(resultLists, readResultLists)
  try {
    await page.goto('/#/board')
    const boardFailure = page.getByTestId('board-data-load-error')
    await expect(boardFailure).toBeVisible({ timeout: 30_000 })
    await expect(boardFailure.getByTestId('failure-cause')).toHaveText(cause)
    await expect(boardFailure.getByTestId('failure-fields')).toContainText(field)
    await expect(boardFailure.getByTestId('failure-technical-details')).not.toHaveAttribute('open', '')
    await expect(page.getByTestId('canvas-map-zoom-input')).toHaveValue('70')
    await expect(page.locator('.el-message--error')).toHaveCount(0)
    await expect(page.getByTestId('scene-import')).toBeDisabled()
    expect(await persistedState(request, auth)).toEqual(before)

    await page.unroute('**/api/board/snapshot', failRead)
    const boardReload = page.waitForResponse(response =>
      response.request().method() === 'GET' && new URL(response.url()).pathname === '/api/board/snapshot'
        && response.status() === 200)
    await boardFailure.getByRole('button', { name: '重试', exact: true }).click()
    await unwrap(await boardReload)
    await expect(boardFailure).not.toBeVisible()
    await expect(page.getByTestId('scene-import')).toBeEnabled()

    await page.getByTestId('open-history-panel').click()
    await page.getByTestId('history-layer-results').click()
    const panel = page.getByTestId('trace-history-panel')
    const resultFailure = panel.getByTestId('history-results-load-error')
    const refresh = panel.getByRole('button', { name: '刷新', exact: true })
    await expect(panel.getByTestId('history-result-verification-10')).toBeVisible()
    await expect(panel.getByTestId('history-result-simulation-20')).toBeVisible()
    await expect(panel.getByTestId('history-result-fuzzing-30')).toBeVisible()
    failVerification = true
    revision += 1
    await refresh.click()
    await expect(resultFailure).toBeVisible()
    await expect(panel.getByTestId('history-result-verification-10')).toBeVisible()
    await expect(panel.getByTestId('history-result-simulation-21')).toBeVisible()
    await expect(panel.getByTestId('history-result-fuzzing-31')).toBeVisible()
    await expect(panel.getByTestId('history-result-simulation-20')).toHaveCount(0)
    await expect(panel.getByTestId('history-result-fuzzing-30')).toHaveCount(0)
    const resultTechnical = resultFailure.getByTestId('failure-technical-details')
    await expect(resultTechnical).not.toHaveAttribute('open', '')
    await resultTechnical.locator('summary').click()
    await expect(resultTechnical).toContainText(cause)
    await expect(resultTechnical).toContainText(`collection: ${field}`)
    await expect(panel.getByText('暂无历史结果', { exact: true })).not.toBeVisible()
    await expect(page.locator('.el-message--error')).toHaveCount(0)
    expect(await persistedState(request, auth)).toEqual(before)

    const late = await holdHistoryReply(page, resultEndpoints[0], failedRead)
    try {
      await Promise.all([refresh.click(), late.requested])
      await page.getByTestId('close-history-panel').click()
      await expect(panel).toBeHidden()
    } finally {
      late.release()
    }
    expect((await late.delivered()).status()).toBe(503)
    expect(await page.locator('.el-message--error').count()).toBe(0)
    await page.getByTestId('open-history-panel').click()
    await expect(resultFailure).toBeVisible()

    await page.unroute(resultLists, readResultLists)
    const recoveredReads = resultEndpoints.map(endpoint => page.waitForResponse(response =>
      response.request().method() === 'GET' && new URL(response.url()).pathname === endpoint && response.status() === 200))
    await resultFailure.getByRole('button', { name: '重试', exact: true }).click()
    for (const response of await Promise.all(recoveredReads)) expect(await unwrap(response)).toEqual([])
    await expect(resultFailure).not.toBeVisible()
    await expect(panel.getByText('暂无历史结果', { exact: true })).toBeVisible()
    const after = await persistedState(request, auth)
    expect(after).toEqual(before)
    await testInfo.attach('board-result-read-recovery', { contentType: 'application/json',
      body: Buffer.from(JSON.stringify({ userId: auth.userId, faultInjected: true, cause, field, before, after })) })
  } finally {
    await page.unroute('**/api/board/snapshot', failRead)
    await page.unroute(resultLists, readResultLists)
  }
})

test('history filtering replaces pending pages and keeps late paging failures in History', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('locale', 'en')
  }, auth)
  const explorationPath = '/api/fuzz/runs'
  const pageZero = (url: URL) => url.pathname === explorationPath && url.searchParams.get('page') === '0'
  const pageOne = (url: URL) => url.pathname === explorationPath && url.searchParams.get('page') === '1'
  let firstId = 100
  await page.route(url => url.pathname === explorationPath, route => {
    const count = Number(new URL(route.request().url()).searchParams.get('size'))
    const data = Array.from({ length: count }, (_, offset) => unavailableFuzzingRun(firstId + offset))
    return route.fulfill({ json: { code: 200, data } })
  })
  await page.goto('/#/board')
  await expect(page.getByTestId('scene-import')).toBeEnabled()
  await page.getByTestId('open-history-panel').click()
  const history = page.getByTestId('trace-history-panel')
  const run = (id: number) => history.getByTestId(`history-result-fuzzing-${id}`)
  const loadMore = history.getByTestId('load-more-fuzzing-runs')
  await expect(run(100)).toBeVisible()

  const olderFilter = await holdHistoryReply(page, pageZero, {
    status: 200, json: { code: 200, data: [unavailableFuzzingRun(90)] }
  })
  try {
    await selectHistoryResultType(page, 'fuzzing')
    await olderFilter.requested
    await selectHistoryResultType(page, 'verification')
    await expect(run(100)).toHaveCount(0)
    firstId = 110
    await selectHistoryResultType(page, 'fuzzing')
    await expect(run(110)).toBeVisible()
  } finally {
    olderFilter.release()
  }
  expect((await olderFilter.delivered()).ok()).toBe(true)
  await expect(run(110)).toBeVisible()
  await expect(run(90)).toHaveCount(0)

  const oldPage = await holdHistoryReply(page, pageOne, {
    status: 200, json: { code: 200, data: [unavailableFuzzingRun(99)] }
  })
  try {
    await Promise.all([loadMore.click(), oldPage.requested])
    await selectHistoryResultType(page, 'verification')
    await expect(run(110)).toHaveCount(0)
    firstId = 200
    await selectHistoryResultType(page, 'fuzzing')
    await expect(run(200)).toBeVisible()
  } finally {
    oldPage.release()
  }
  expect((await oldPage.delivered()).ok()).toBe(true)
  await expect(run(200)).toBeVisible()
  await expect(run(110)).toHaveCount(0)
  await expect(run(99)).toHaveCount(0)

  const failure = { status: 503, json: { code: 503, message: 'Exploration page could not be loaded' } }
  await page.route(pageOne, route => route.fulfill(failure), { times: 1 })
  await loadMore.click()
  await expect(history.getByTestId('history-results-load-error')).toBeVisible()
  await expect(run(200)).toBeVisible()
  await expect(page.locator('.el-message--error')).toHaveCount(0)

  const lateFailure = await holdHistoryReply(page, pageOne, failure)
  try {
    await Promise.all([loadMore.click(), lateFailure.requested])
    await page.getByTestId('close-history-panel').click()
    await expect(history).toBeHidden()
  } finally {
    lateFailure.release()
  }
  expect((await lateFailure.delivered()).status()).toBe(503)
  expect(await page.locator('.el-message--error').count()).toBe(0)
})

test('task inbox retains read diagnostics and retries real empty lists without changing the board or history', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'taskFeedback' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  const endpoints = ['/api/verify/tasks', '/api/simulate/tasks', '/api/fuzz/tasks']
  for (const endpoint of endpoints) {
    expect(await unwrap(await request.get(`${apiBaseURL}${endpoint}`, { headers }))).toEqual([])
  }
  const before = await persistedState(request, auth)
  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('locale', 'zh-CN')
    const notificationKey = `iot_verify_fuzz_notifications_${auth.userId}`
    if (localStorage.getItem(notificationKey)) return
    const createdAt = new Date().toISOString()
    localStorage.setItem(notificationKey, JSON.stringify({
      unread: [
        { taskId: 9_999_997, kind: 'UNAVAILABLE', createdAt },
        { taskId: 9_999_998, kind: 'COMPLETED', createdAt },
        { taskId: 9_999_999, kind: 'FAILED', createdAt }
      ]
    }))
  }, auth)
  await page.goto('/#/board')
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
  const unread = page.getByTestId('fuzz-unread-badge')
  await expect(unread).toHaveText('3')

  const cause = 'Task storage could not be read'
  const field = 'Database query timed out'
  let failedReads = 0
  const failRead = async (route: Route) => {
    if (route.request().method() !== 'GET' || new URL(route.request().url()).pathname !== '/api/verify/tasks') {
      await route.continue()
      return
    }
    failedReads += 1
    await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({
      code: 503, message: cause, data: { errors: { tasks: field } }
    }) })
  }
  await page.route('**/api/verify/tasks**', failRead)
  try {
    await page.getByTestId('open-history-panel').click()
    await expect(page.getByTestId('history-layer-tasks')).toHaveAttribute('aria-pressed', 'true')
    const history = page.getByTestId('trace-history-panel')
    const failure = history.getByTestId('history-tasks-load-error')
    await expect(failure).toBeVisible()
    await expect(unread).toHaveText('3')
    await expect(failure).toHaveAttribute('role', 'alert')
    await expect(failure).toContainText('加载任务失败')
    const taskTechnical = failure.getByTestId('failure-technical-details')
    await expect(taskTechnical).not.toHaveAttribute('open', '')
    await taskTechnical.locator('summary').click()
    await expect(taskTechnical).toContainText(cause)
    await expect(taskTechnical).toContainText('HTTP 503')
    await expect(taskTechnical).toContainText(`tasks: ${field}`)
    await expect(history.getByText('没有待处理任务', { exact: true })).not.toBeVisible()
    await expect(page.locator('.el-message').filter({ hasText: '加载任务失败' })).toHaveCount(0)
    expect(failedReads).toBeGreaterThan(0)
    expect(await persistedState(request, auth)).toEqual(before)

    await page.getByTestId('history-layer-results').click()
    await expect(history.getByTestId('history-result-fuzzing-9999997')).toBeVisible()
    await expect(unread, 'Results only acknowledges the visible exploration entry').toHaveText('2')
    await page.getByTestId('history-layer-tasks').click()
    await expect(failure).toBeVisible()

    await page.unroute('**/api/verify/tasks**', failRead)
    const recoveredReads = endpoints.map(endpoint => page.waitForResponse(response =>
      response.request().method() === 'GET' && new URL(response.url()).pathname === endpoint && response.status() === 200))
    await failure.getByRole('button', { name: '重试', exact: true }).click()
    for (const response of await Promise.all(recoveredReads)) expect(await unwrap(response)).toEqual([])
    await expect(failure).not.toBeVisible()
    await expect(history.getByText('没有待处理任务', { exact: true })).toBeVisible()
    await expect(unread).toHaveText('1')
    await page.getByTestId('close-history-panel').click()
    await page.reload()
    await expect(unread).toHaveText('1')
    await page.getByTestId('open-history-panel').click()
    await expect(page.getByTestId('history-layer-results')).toHaveAttribute('aria-pressed', 'true')
    const after = await persistedState(request, auth)
    expect(after).toEqual(before)
    await testInfo.attach('task-inbox-recovery', { contentType: 'application/json',
      body: Buffer.from(JSON.stringify({ userId: auth.userId, failedReads, cause, field, before, after })) })
  } finally {
    await page.unroute('**/api/verify/tasks**', failRead)
  }
})
