import type { APIRequestContext, Page } from '@playwright/test'
import { unwrap } from './support/apiResponse'
import { readFileSync } from 'node:fs'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'
import { clickUnderTooltip } from './support/tooltips'

const headers = (auth: AuthUser) => ({ Authorization: `Bearer ${auth.token}` })
const seedBoard = async (request: APIRequestContext, auth: AuthUser) => {
  await unwrap(await request.post(`${apiBaseURL}/api/board/nodes`, {
    headers: headers(auth), data: { devices: [{ id: 'task_light', templateName: 'Light',
      label: '后台任务测试灯 / Background task light', position: { x: 200, y: 200 },
      width: 176, height: 128, state: 'off', variables: [], privacies: [] }], environmentVariablePatches: [] }
  }))
  await unwrap(await request.post(`${apiBaseURL}/api/board/rules`, {
    headers: headers(auth), data: { conditions: [{ deviceName: 'task_light', attribute: 'SwitchState',
      targetType: 'mode', relation: '=', value: 'off' }], command: { deviceName: 'task_light', action: 'off' },
      ruleString: 'Keep the light off' }
  }))
  await unwrap(await request.post(`${apiBaseURL}/api/board/specs`, {
    headers: headers(auth), data: { name: 'Safety requirement', id: 'never_on', templateId: '3', templateLabel: 'Never',
      aConditions: [{ id: 'never_on_a', side: 'a', deviceId: 'task_light', targetType: 'mode',
        key: 'SwitchState', relation: '=', value: 'on' }], ifConditions: [], thenConditions: [] }
  }))
}

const openTool = async (page: Page, kind: string) => {
  const restore = page.getByTestId('restore-action-dock')
  if (await restore.isVisible()) await restore.click()
  await clickUnderTooltip(page, page.getByTestId(`open-${kind}-panel`))
}

const toggleLanguage = async (page: Page, english: boolean) => {
  const button = page.getByRole('button', { name: english ? '切换到中文' : 'Switch to English', exact: true })
  if (!await button.isVisible()) await page.locator('.scene-actions-menu > summary').click()
  await button.click()
  const menu = page.locator('.scene-actions-menu')
  if (await menu.getAttribute('open') !== null) await menu.locator('summary').click()
}

const expectReachable = async (page: Page, testId: string) => {
  const button = page.getByTestId(testId)
  await button.scrollIntoViewIfNeeded()
  await expect(button).toBeInViewport({ ratio: 1 })
  expect(await button.evaluate(element => {
    const box = element.getBoundingClientRect()
    return element.contains(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2))
  })).toBe(true)
}

const startWorkspace = async (page: Page, auth: AuthUser, locale: string, width: number) => {
  await page.setViewportSize({ width, height: width === 320 ? 500 : 1080 })
  await page.addInitScript(({ auth, locale }) => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('locale', locale)
  }, { auth, locale })
  await page.goto('/#/board')
  await expect(page.getByTestId('board-root')).toBeVisible()
  await expect(page.getByTestId('scene-import')).toBeEnabled()
}

const explorationBudget = { explorationMode: 'BOARD_SNAPSHOT', targetSpecIds: ['never_on'],
  maxIterations: 3333, pathLength: 50, populationSize: 10, seed: 17 }

test('resumes the verification named in an unconfirmed dispatch response', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  await seedBoard(request, auth)
  await startWorkspace(page, auth, 'en', 1440)
  await openTool(page, 'verification')
  await page.getByTestId('verification-mode-async').click()

  let submissions = 0
  let taskId: number | undefined
  await page.route('**/api/verify/async', async route => {
    submissions += 1
    const accepted = await unwrap<{ id: number }>(await route.fetch())
    taskId = accepted.id
    await route.fulfill({ status: 503, json: { code: 503, message: 'Task dispatch outcome is unknown',
      data: { reasonCode: 'TASK_DISPATCH_OUTCOME_UNKNOWN', taskKind: 'verification', taskId } } })
  })
  let release!: () => void
  const paused = new Promise<void>(resolve => { release = resolve })
  const checkedIds: number[] = []
  await page.route('**/api/verify/tasks/*', async route => {
    const match = /\/api\/verify\/tasks\/(\d+)$/.exec(new URL(route.request().url()).pathname)
    if (route.request().method() === 'GET' && match) {
      checkedIds.push(Number(match[1]))
      if (checkedIds.length === 1) await paused
    }
    await route.continue()
  })

  try {
    await page.getByTestId('run-verification').click()
    const panel = page.getByTestId('verification-panel')
    await expect(panel.getByText('Checking task status…')).toBeVisible()
    await expect(panel.getByTestId('run-verification')).toBeDisabled()
    await expect.poll(() => checkedIds).toEqual([taskId])
    expect(submissions).toBe(1)
  } finally {
    release()
  }

  await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 30_000 })
  await expect.poll(() => page.url()).toContain(`run=verification:${taskId}`)
  expect(checkedIds.every(id => id === taskId)).toBe(true)
  expect(submissions).toBe(1)
})

test('reconciles a lost or concurrent task dismissal without retrying the delete', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  await seedBoard(request, auth)
  const taskIds: number[] = []
  for (let index = 0; index < 3; index++) {
    const task = await unwrap(await request.post(`${apiBaseURL}/api/fuzz/async`, {
      headers: headers(auth), data: explorationBudget
    }))
    const cancelled = await unwrap(await request.post(`${apiBaseURL}/api/fuzz/tasks/${task.id}/cancel`, { headers: headers(auth) }))
    expect(cancelled.taskStatus).toBe('CANCELLED')
    taskIds.push(task.id)
  }

  const taskRow = (tab: Page, id: number) => tab.getByTestId(`history-task-fuzzing-${id}`)
  const observer = await page.context().newPage()
  try {
    for (const tab of [page, observer]) {
      await startWorkspace(tab, auth, 'en', 1440)
      await openTool(tab, 'history')
      await tab.getByTestId('history-layer-tasks').click()
      for (const id of taskIds) await expect(taskRow(tab, id)).toBeVisible()
    }

    const [lost, concurrent, unaffected] = taskIds
    await page.route(`**/api/fuzz/tasks/${lost}`, async route => {
      if (route.request().method() !== 'DELETE') return route.continue()
      const committed = await route.fetch()
      expect(committed.ok(), await committed.text()).toBe(true)
      await route.abort()
    })

    let writes = 0
    let checks = 0
    let listReads = 0
    page.on('request', outgoing => {
      const path = new URL(outgoing.url()).pathname
      if (/^\/api\/(verify|simulate|fuzz)\/tasks$/.test(path)) listReads += 1
      if (path !== `/api/fuzz/tasks/${lost}` && path !== `/api/fuzz/tasks/${concurrent}`) return
      if (outgoing.method() === 'DELETE') writes += 1
      if (outgoing.method() === 'GET') checks += 1
    })

    for (const id of [lost, concurrent]) {
      if (id === concurrent) {
        const removed = await request.delete(`${apiBaseURL}/api/fuzz/tasks/${id}`, { headers: headers(auth) })
        expect(removed.ok(), await removed.text()).toBe(true)
      }
      const checked = page.waitForResponse(response => response.request().method() === 'GET'
        && new URL(response.url()).pathname === `/api/fuzz/tasks/${id}`)
      await taskRow(page, id).getByRole('button', { name: 'Dismiss', exact: true }).click()
      expect((await checked).status()).toBe(404)
      await expect(page.getByText('Dismissal is unconfirmed. This task record is no longer on the server.').last()).toBeVisible()
      await expect(taskRow(page, id)).toHaveCount(0)
      await expect(taskRow(observer, id)).toHaveCount(0)
    }

    await expect(taskRow(page, unaffected)).toBeVisible()
    await expect(taskRow(observer, unaffected)).toBeVisible()
    expect(writes).toBe(2)
    expect(checks).toBe(2)
    expect(listReads).toBe(0)
  } finally {
    await observer.close()
  }
})

test('shares lost cancellation feedback across the task list and progress and adopts available task lists', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  await seedBoard(request, auth)
  const task = await unwrap(await request.post(`${apiBaseURL}/api/verify/async`, {
    headers: headers(auth), data: { attackScenario: { mode: 'NONE' }, enablePrivacy: false }
  }))
  const taskUrl = `${apiBaseURL}/api/verify/tasks/${task.id}`
  let completed: any
  await expect.poll(async () => {
    completed = await unwrap(await request.get(taskUrl, { headers: headers(auth) }))
    return completed.status
  }).toBe('COMPLETED')
  const active = { ...task, status: 'RUNNING', progress: 31, progressStage: 'EXECUTING_MODEL_CHECKER', startedAt: completed.startedAt }
  let cancelPosts = 0
  let detailReads = 0
  await page.route(`**/api/verify/tasks/${task.id}/cancel`, route => {
    cancelPosts += 1
    return route.abort('failed')
  })
  await page.route(`**/api/verify/tasks/${task.id}`, route => {
    detailReads += 1
    return cancelPosts === 3 ? route.continue() : route.fulfill({ json: { code: 200, message: 'Success', data: active } })
  })
  await page.route(/\/api\/verify\/tasks(?:\?.*)?$/, route => cancelPosts === 3 ? route.continue()
    : route.fulfill({ json: { code: 200, message: 'Success', data: [active] } }))
  await page.route(/\/api\/simulate\/tasks(?:\?.*)?$/, route => route.fulfill({
    status: 503, json: { code: 503, message: 'Simulation task read unavailable' }
  }))

  await startWorkspace(page, auth, 'en', 1440)
  await openTool(page, 'history')
  await page.getByTestId('history-layer-tasks').click()
  const row = page.getByTestId(`history-task-verification-${task.id}`)
  await expect(page.getByTestId('history-tasks-load-error')).toBeVisible()
  await expect(row).toContainText('31%')
  const initialDetailReads = detailReads
  const unknown = page.getByRole('dialog').filter({ hasText: 'Task cancellation unconfirmed' })
  await row.getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect(unknown).toBeVisible()
  expect(detailReads).toBeGreaterThan(initialDetailReads)
  await unknown.getByRole('button', { name: 'Confirm', exact: true }).click()
  await row.getByRole('button', { name: 'Watch', exact: true }).click()

  const progress = page.getByTestId('verification-panel')
  const cancel = progress.getByRole('button', { name: 'Cancel verification task', exact: true })
  await cancel.click()
  await expect(unknown).toBeVisible()
  await unknown.getByRole('button', { name: 'Confirm', exact: true }).click()
  await expect(cancel).toBeEnabled()
  await cancel.click()
  await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 15_000 })
  await expect(unknown).toHaveCount(0)
  expect(cancelPosts).toBe(3)
})

// Each lifecycle journey switches language and viewport before checking recovery.
for (const locale of ['en']) {
  for (const width of [320]) {
    test(`background search cancellation and failure recovery in ${locale} at ${width}px`, async ({ page, request }, testInfo) => {
      test.setTimeout(120_000)
      const auth = await createAuthenticatedUser(request)
      await seedBoard(request, auth)
      const cancelledIds: number[] = []
      for (let index = 0; index < 3; index++) {
        const task = await unwrap(await request.post(`${apiBaseURL}/api/fuzz/async`, {
          headers: headers(auth), data: explorationBudget
        }))
        const cancelled = await unwrap(await request.post(`${apiBaseURL}/api/fuzz/tasks/${task.id}/cancel`, { headers: headers(auth) }))
        expect(cancelled.taskStatus).toBe('CANCELLED')
        cancelledIds.push(task.id)
      }
      await startWorkspace(page, auth, locale, width)
      await openTool(page, 'fuzzing')
      const panel = page.getByTestId('fuzzing-panel')
      const advanced = panel.locator('details').filter({ has: page.getByTestId('fuzz-population-size') })
      await page.getByTestId('fuzz-max-iterations').fill('3333')
      await page.getByTestId('fuzz-path-length').fill('50')
      await advanced.locator('summary').click()
      await page.getByTestId('fuzz-population-size').fill('10')
      await page.getByTestId('fuzz-seed').fill('17')
      await expect(page.getByTestId('run-fuzzing')).toBeEnabled()
      const submitted = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/fuzz/async')
      await page.getByTestId('run-fuzzing').click()
      const task = await unwrap(await submitted)
      await expect(panel.getByRole('button', { name: locale === 'en' ? 'Cancel' : '取消', exact: true })).toBeVisible()
      await page.getByTestId('close-fuzzing-panel').click()
      await expect(page.getByTestId('mini-task-indicator')).toBeVisible()
      await openTool(page, 'history')
      await expect(page.getByTestId('history-layer-tasks')).toHaveAttribute('aria-pressed', 'true')
      const history = page.getByTestId('trace-history-panel')
      const current = page.getByTestId(`history-task-fuzzing-${task.id}`)
      await expect(current.getByRole('progressbar')).toBeVisible()
      await page.screenshot({ path: testInfo.outputPath('running-tasks.png') })
      await expect(page.getByTestId('mini-task-indicator'), 'a duplicate task overlay must not cover the task list').toBeHidden()
      const cancelled = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === `/api/fuzz/tasks/${task.id}/cancel`)
      await current.getByRole('button', { name: locale === 'en' ? 'Cancel' : '取消', exact: true }).click()
      const cancellation = await unwrap(await cancelled)
      expect(cancellation.cancellationOutcome).toBe('ACCEPTED')
      await expect(current).toContainText(locale === 'en' ? 'Cancelled' : '已取消')
      await expect(current.getByRole('progressbar')).toHaveCount(0)
      await expect(page.getByTestId('open-fuzzing-panel')).toHaveAttribute('aria-label',
        locale === 'en' ? 'Open counterexample exploration settings' : '打开反例探索设置')
      await expect(page.locator('.el-message').filter({ hasText: /cancelled|取消/i }), 'visible task status needs no duplicate toast').toHaveCount(0)
      await expectReachable(page, 'close-history-panel')
      const persisted = await unwrap(await request.get(`${apiBaseURL}/api/fuzz/tasks/${task.id}`, { headers: headers(auth) }))
      expect(persisted.status).toBe('CANCELLED')

      // Execution failures are injected; submission, cancellation, retry and completion use the real backend.
      const failedId = cancelledIds[0]!
      const diagnostic = 'Worker connection closed while reading the frozen model. '
        + 'The execution lease expired before the result could be saved. '
        + `Context: ${'model_snapshot_'.repeat(16)}`
      await page.route('**/api/fuzz/tasks?**', async route => {
        const response = await route.fetch()
        const body = await response.json()
        body.data = body.data.map((row: any) => row.id === failedId
          ? { ...row, status: 'FAILED', errorMessage: diagnostic } : row)
        await route.fulfill({ response, json: body })
      })
      await history.getByRole('button', { name: locale === 'en' ? 'Refresh' : '刷新', exact: true }).click()
      const failed = page.getByTestId(`history-task-fuzzing-${failedId}`)
      await expect(failed).toContainText(locale === 'en' ? 'Failed' : '失败')
      const technical = failed.getByTestId(`task-failure-reason-fuzzing-${failedId}`)
      await expect(technical).toContainText(diagnostic)
      expect((await failed.innerText()).split('Worker connection closed').length - 1).toBe(1)
      const actionId = `reopen-task-settings-fuzzing-${failedId}`
      await expectReachable(page, actionId)
      const body = history.locator('.history-body')
      expect(await body.evaluate(element => element.scrollTop)).toBeGreaterThan(0)
      await toggleLanguage(page, locale === 'en')
      await expect(technical).toContainText(diagnostic)
      await expect(failed).toBeInViewport()
      await page.setViewportSize({ width: width === 320 ? 1920 : 320, height: width === 320 ? 1080 : 500 })
      await expect(failed).toBeInViewport()
      await expectReachable(page, actionId)
      await expectReachable(page, 'close-history-panel')
      expect(await history.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
      await page.screenshot({ path: testInfo.outputPath('failed-task-reading-context.png') })
      let submissions = 0
      page.on('request', request => {
        if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/fuzz/async') submissions++
      })
      await page.getByTestId(actionId).click()
      await expect(panel).toBeVisible()
      expect(submissions, 'adjusting settings must not execute a retry').toBe(0)
      await page.getByTestId('fuzz-max-iterations').fill('1')
      await page.getByTestId('fuzz-path-length').fill('2')
      if (await advanced.getAttribute('open') === null) await advanced.locator('summary').click()
      await page.getByTestId('fuzz-population-size').fill('1')
      await expect(page.getByTestId('run-fuzzing')).toBeEnabled()
      const retried = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/fuzz/async')
      await page.getByTestId('run-fuzzing').click()
      const retryTask = await unwrap(await retried)
      const result = page.getByTestId('fuzzing-result-dialog')
      await expect(result).toBeVisible()
      expect(submissions).toBe(1)
      const completed = await unwrap(await request.get(`${apiBaseURL}/api/fuzz/tasks/${retryTask.id}`, { headers: headers(auth) }))
      expect(completed.status).toBe('COMPLETED')
      expect(completed.runId).toBeGreaterThan(0)
      await page.getByTestId('close-fuzzing-result').click()
      await openTool(page, 'history')
      await page.getByTestId('history-layer-results').click()
      await page.getByTestId(`open-fuzzing-run-${completed.runId}`).click()
      await expect(result).toBeVisible()
    })
  }
}

for (const [kind, closeBeforeCompletion] of [
  ['verification', false], ['simulation', false], ['verification', true]
] as const) {
  const expectation = closeBeforeCompletion ? 'keeps its real result in history after closing progress' : 'opens its progress and real result'
  test(`watching an existing ${kind} task ${expectation}`, async ({ page, request }, testInfo) => {
    const auth = await createAuthenticatedUser(request)
    await seedBoard(request, auth)
    const domain = kind === 'verification' ? 'verify' : 'simulate'
    const task = await unwrap(await request.post(`${apiBaseURL}/api/${domain}/async`, {
      headers: headers(auth), data: { attackScenario: { mode: 'NONE' }, enablePrivacy: false, ...(kind === 'simulation' ? { steps: 3 } : {}) }
    }))
    let completed: any
    await expect.poll(async () => {
      completed = await unwrap(await request.get(`${apiBaseURL}/api/${domain}/tasks/${task.id}`, { headers: headers(auth) }))
      return completed.status
    }).toBe('COMPLETED')
    let release = false
    const active = { ...task, status: 'RUNNING', progress: 31,
      startedAt: completed.startedAt,
      progressStage: kind === 'verification' ? 'EXECUTING_MODEL_CHECKER' : 'RUNNING_SIMULATION' }
    // Hold the observed phase to inspect resumed progress; the eventual result is the real saved run.
    await page.route(`**/api/${domain}/tasks**`, async route => {
      const url = new URL(route.request().url())
      if (route.request().method() !== 'GET') { await route.continue(); return }
      if (!release && (url.pathname === `/api/${domain}/tasks` || url.pathname === `/api/${domain}/tasks/${task.id}`)) {
        await route.fulfill({ json: { code: 200, message: 'Success', data: url.pathname.endsWith(`/${task.id}`) ? active : [active] } })
      } else await route.continue()
    })
    await startWorkspace(page, auth, 'en', 320)
    await openTool(page, 'history')
    await page.getByTestId('history-layer-tasks').click()
    await page.getByTestId(`history-task-${kind}-${task.id}`).getByRole('button', { name: 'Watch', exact: true }).click()
    const progress = page.getByTestId(`${kind}-panel`)
    await expect(progress, 'watching a task must open its progress panel').toBeVisible()
    await expect(progress).toContainText('31%')
    await expect(page.getByTestId('mini-task-indicator')).toBeHidden()
    await expect(progress.getByTestId(`${kind}-frozen-task-summary`)).toBeVisible()
    await expect(page.getByTestId(`${kind}-mode-sync`)).toHaveCount(0)
    if (kind === 'simulation') await expect(progress.getByTestId('frozen-simulation-steps')).toHaveText('3')
    await toggleLanguage(page, true)
    await expect(progress.getByTestId(`${kind}-frozen-task-summary`)).toContainText(`任务 #${task.id}`)
    await expect(progress).toContainText('31%')
    await page.screenshot({ path: testInfo.outputPath('watched-task-progress.png') })
    if (closeBeforeCompletion) {
      const urlBeforeClose = page.url()
      await page.getByTestId(`close-${kind}-panel`).click()
      await expect(progress).toBeHidden()
      const background = page.getByTestId('mini-task-indicator')
      await expect(background).toBeVisible()
      release = true
      await expect(background).toBeHidden()
      await expect(page.getByTestId('verification-result-dialog')).toHaveCount(0)
      expect(page.url()).toBe(urlBeforeClose)

      await openTool(page, 'history')
      await page.getByTestId('history-layer-results').click()
      await page.getByTestId(`open-verification-run-${task.id}`).click()
      await expect(page.getByTestId('verification-result-dialog')).toBeVisible()
      return
    }
    release = true
    if (kind === 'verification') await expect(page.getByTestId('verification-result-dialog')).toBeVisible()
    else await expect(page.getByTestId('simulation-timeline-host')).toBeVisible()
  })
}

for (const kind of ['verification', 'simulation'] as const) {
  for (const locale of ['en']) {
    for (const width of [320]) {
      test(`real ${kind} cancellation retry and completion race in ${locale} at ${width}px`, async ({ page, request }, testInfo) => {
        test.setTimeout(90_000)
        page.setDefaultTimeout(5_000)
        const auth = await createAuthenticatedUser(request)
        const scene = JSON.parse(readFileSync('../docs/examples/elderly-care-comprehensive-scene.json', 'utf8'))
        const preview = await unwrap(await request.get(`${apiBaseURL}/api/board/replacement-preview`, { headers: headers(auth) }))
        await unwrap(await request.post(`${apiBaseURL}/api/board/scene`, {
          headers: headers(auth), data: { impactToken: preview.impactToken, scene }
        }))
        await startWorkspace(page, auth, locale, width)
        await openTool(page, kind)
        await page.getByTestId(`${kind}-mode-async`).click()
        if (kind === 'simulation') await page.getByTestId('simulation-steps-input').fill('100')
        const domain = kind === 'verification' ? 'verify' : 'simulate'
        const observations: any[] = []
        let taskId = 0
        // Delay delivery of the genuine acceptance only until the worker has actually started.
        await page.route(`**/api/${domain}/async`, async route => {
          const response = await route.fetch()
          const body = await response.json()
          taskId = body.data.id
          let running: any
          await expect.poll(async () => {
            running = await unwrap(await request.get(`${apiBaseURL}/api/${domain}/tasks/${taskId}`, { headers: headers(auth) }))
            return running.status
          }, { intervals: [10, 20, 50], timeout: 10_000 }).toBe('RUNNING')
          observations.push({ event: 'worker-started', id: taskId, status: running.status, stage: running.progressStage })
          await route.fulfill({ response })
        })
        let cancelRequests = 0
        let race = false
        const resultId = kind === 'verification' ? 'verification-result-dialog' : 'simulation-timeline-host'
        await page.route(`**/api/${domain}/tasks/*/cancel`, async route => {
          cancelRequests++
          const current = await unwrap(await request.get(`${apiBaseURL}/api/${domain}/tasks/${taskId}`, { headers: headers(auth) }))
          observations.push({ event: 'cancel-clicked', id: taskId, status: current.status, stage: current.progressStage })
          expect(current.status, 'cancel must be clicked while the real worker is running').toBe('RUNNING')
          if (cancelRequests === 1) {
            // Only the first cancellation transport fails; task lifecycle and subsequent responses stay real.
            await route.fulfill({ status: 503, json: { code: 503, message: 'Cancellation gateway unavailable' } })
          } else if (race) {
            await expect.poll(async () => (await unwrap(await request.get(`${apiBaseURL}/api/${domain}/tasks/${taskId}`, {
              headers: headers(auth)
            }))).status, { timeout: 30_000 }).toBe('COMPLETED')
            await expect(page.getByTestId(resultId)).toBeVisible({ timeout: 15_000 })
            await route.continue()
          } else await route.continue()
        })
        const run = page.getByTestId(`run-${kind}`)
        const panel = page.getByTestId(`${kind}-panel`)
        const cancel = panel.getByRole('button', {
          name: locale === 'en' ? `Cancel ${kind === 'verification' ? 'verification' : 'observation'} task` : kind === 'verification' ? '取消验证任务' : '取消观察任务', exact: true
        })
        await run.click()
        await cancel.click()
        const failure = page.getByRole('dialog').filter({ has: page.getByTestId('failure-next-step') })
        await expect(failure).toBeVisible()
        await failure.getByRole('button', { name: locale === 'en' ? 'Confirm' : '确定', exact: true }).click({ timeout: 5_000 })
        await expect(cancel).toBeEnabled()
        const cancelledResponse = page.waitForResponse(response => response.request().method() === 'POST'
          && new URL(response.url()).pathname === `/api/${domain}/tasks/${taskId}/cancel`)
        await cancel.click()
        const cancelled = await unwrap(await cancelledResponse)
        expect(cancelled.cancellationOutcome).toBe('ACCEPTED')
        await expect(run).toBeEnabled()
        expect((await unwrap(await request.get(`${apiBaseURL}/api/${domain}/tasks/${taskId}`, { headers: headers(auth) }))).status).toBe('CANCELLED')
        await expect(page.locator('.el-message').filter({ hasText: /cancelled|取消/i })).toHaveCount(0)
        await page.getByTestId(`close-${kind}-panel`).click()
        await openTool(page, 'history')
        await page.getByTestId('history-layer-tasks').click()
        const cancelledTask = page.getByTestId(`history-task-${kind}-${taskId}`)
        await expect(cancelledTask).toContainText(locale === 'en' ? 'Cancelled' : '已取消')
        await toggleLanguage(page, locale === 'en')
        await expect(cancelledTask).toContainText(locale === 'en' ? '已取消' : 'Cancelled')
        await page.setViewportSize({ width: width === 320 ? 1920 : 320, height: width === 320 ? 1080 : 500 })
        await expectReachable(page, 'close-history-panel')
        await page.screenshot({ path: testInfo.outputPath('real-cancelled-after-retry.png') })
        await toggleLanguage(page, locale !== 'en')
        await page.getByTestId('close-history-panel').click()
        await openTool(page, kind)
        race = true
        await run.click()
        // The route holds this response until the real worker and its result surface settle.
        const completedCancellation = page.waitForResponse(response => response.request().method() === 'POST'
          && new URL(response.url()).pathname === `/api/${domain}/tasks/${taskId}/cancel`, { timeout: 60_000 })
        await cancel.click()
        const completed = await unwrap(await completedCancellation)
        expect(completed.cancellationOutcome).toBe('ALREADY_COMPLETED')
        const persisted = await unwrap(await request.get(`${apiBaseURL}/api/${domain}/tasks/${taskId}`, { headers: headers(auth) }))
        expect(persisted.status).toBe('COMPLETED')
        const runId = kind === 'verification' ? persisted.id : persisted.simulationTraceId
        expect(runId).toBeGreaterThan(0)
        await expect.poll(() => new URLSearchParams(new URL(page.url()).hash.split('?')[1]).get('run')).toBe(`${kind}:${runId}`)
        await expect(page.getByTestId(resultId)).toBeVisible()
        await page.mouse.move(4, 4)
        await expect(page.locator('.iot-hint-tooltip-popper:visible')).toHaveCount(0)
        await expect(page.locator('.el-message')).toHaveCount(0)
        if (kind === 'verification') {
          await expect(page.getByTestId(resultId).getByRole('dialog')).toBeFocused()
          await expect(page.locator('.iot-hint-tooltip-popper:not([aria-hidden="true"])')
            .filter({ hasText: new RegExp(`^${locale === 'en' ? 'Close' : '关闭'}$`) })).toHaveCount(0)
        }
        await expectReachable(page, kind === 'verification' ? 'close-verification-result' : 'simulation-timeline-close')
        await page.screenshot({ path: testInfo.outputPath('completed-during-cancellation.png') })
        await page.reload()
        await expect(page.getByTestId(resultId)).toBeVisible()
        await expect.poll(() => new URLSearchParams(new URL(page.url()).hash.split('?')[1]).get('run')).toBe(`${kind}:${runId}`)
        await page.goBack()
        await expect(page.getByTestId(resultId)).toBeHidden()
        await page.goForward()
        await expect(page.getByTestId(resultId)).toBeVisible()
        await testInfo.attach('real-task-observations', { body: JSON.stringify(observations, null, 2), contentType: 'application/json' })
      })
    }
  }
}
