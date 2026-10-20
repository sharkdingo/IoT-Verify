import type { APIRequestContext, APIResponse, Response, Route } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'

const unwrap = async (response: APIResponse | Response) => {
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

test('board and result history read failures retain diagnostics once and recover without writes', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'readFeedback' })
  const before = await persistedState(request, auth)
  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('locale', 'zh-CN')
  }, auth)
  const cause = 'Read transaction timed out'
  const field = 'The database could not return this collection'
  const failRead = async (route: Route) => {
    await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({
      code: 503, message: cause, data: { errors: { collection: field } }
    }) })
  }
  await page.route('**/api/board/snapshot', failRead)
  await page.route('**/api/verify/runs', failRead)
  try {
    await page.goto('/#/board')
    const boardFailure = page.getByTestId('board-data-load-error')
    await expect(boardFailure).toBeVisible({ timeout: 30_000 })
    await expect(boardFailure.getByTestId('failure-cause')).toHaveText(cause)
    await expect(boardFailure.getByTestId('failure-fields')).toContainText(field)
    await expect(boardFailure.getByTestId('failure-technical-details')).not.toHaveAttribute('open', '')
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
    await expect(resultFailure).toBeVisible()
    const resultTechnical = resultFailure.getByTestId('failure-technical-details')
    await expect(resultTechnical).not.toHaveAttribute('open', '')
    await resultTechnical.locator('summary').click()
    await expect(resultTechnical).toContainText(cause)
    await expect(resultTechnical).toContainText(`collection: ${field}`)
    await expect(panel.getByText('暂无历史结果', { exact: true })).not.toBeVisible()
    await expect(page.locator('.el-message--error')).toHaveCount(0)
    expect(await persistedState(request, auth)).toEqual(before)

    await page.unroute('**/api/verify/runs', failRead)
    const endpoints = ['/api/verify/runs', '/api/simulate/traces', '/api/fuzz/runs']
    const recoveredReads = endpoints.map(endpoint => page.waitForResponse(response =>
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
    await page.unroute('**/api/verify/runs', failRead)
  }
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
  }, auth)
  await page.goto('/#/board')
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })

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
    await page.getByTestId('history-layer-tasks').click()
    const history = page.getByTestId('trace-history-panel')
    const failure = history.getByTestId('history-tasks-load-error')
    await expect(failure).toBeVisible()
    await expect(failure).toHaveAttribute('role', 'alert')
    await expect(failure).toContainText('加载任务失败')
    const taskTechnical = failure.getByTestId('failure-technical-details')
    await expect(taskTechnical).not.toHaveAttribute('open', '')
    await taskTechnical.locator('summary').click()
    await expect(taskTechnical).toContainText(cause)
    await expect(taskTechnical).toContainText(`tasks: ${field}`)
    await expect(history.getByText('没有待处理任务', { exact: true })).not.toBeVisible()
    await expect(page.locator('.el-message').filter({ hasText: '加载任务失败' })).toHaveCount(0)
    expect(failedReads).toBeGreaterThan(0)
    expect(await persistedState(request, auth)).toEqual(before)

    await page.unroute('**/api/verify/tasks**', failRead)
    const recoveredReads = endpoints.map(endpoint => page.waitForResponse(response =>
      response.request().method() === 'GET' && new URL(response.url()).pathname === endpoint && response.status() === 200))
    await failure.getByRole('button', { name: '重试', exact: true }).click()
    for (const response of await Promise.all(recoveredReads)) expect(await unwrap(response)).toEqual([])
    await expect(failure).not.toBeVisible()
    await expect(history.getByText('没有待处理任务', { exact: true })).toBeVisible()
    const after = await persistedState(request, auth)
    expect(after).toEqual(before)
    await testInfo.attach('task-inbox-recovery', { contentType: 'application/json',
      body: Buffer.from(JSON.stringify({ userId: auth.userId, failedReads, cause, field, before, after })) })
  } finally {
    await page.unroute('**/api/verify/tasks**', failRead)
  }
})
