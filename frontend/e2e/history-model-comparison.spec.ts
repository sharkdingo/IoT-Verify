import type { APIRequestContext, APIResponse, Page } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'
import type { BoardModelFingerprints } from '../src/types/modelSemantics'
import type { VerificationResult } from '../src/types/verify'
import type { SimulationResult, SimulationTrace } from '../src/types/simulation'
import { validateSimulationResult, validateSimulationTrace, validateVerificationResult } from '../src/utils/runResponse'
import { clickUnderTooltip } from './support/tooltips'
import { selectHistoryResultType } from './support/historyFilter'

const unwrap = async <T>(response: APIResponse): Promise<T> => {
  expect(response.ok(), await response.text()).toBeTruthy()
  const body = await response.json()
  expect(body.code, JSON.stringify(body)).toBe(200)
  return body.data as T
}

const headers = (auth: AuthUser) => ({ Authorization: `Bearer ${auth.token}` })
const settings = { attackScenario: { mode: 'NONE' }, enablePrivacy: false }

const seedBoard = async (request: APIRequestContext, auth: AuthUser) => {
  await unwrap(await request.post(`${apiBaseURL}/api/board/nodes`, {
    headers: headers(auth), data: { devices: [{ id: 'history_light', templateName: 'Light',
      label: 'Hallway light with a complete readable historical name', position: { x: 200, y: 200 },
      width: 176, height: 128, state: 'off', currentStateTrust: 'trusted', currentStatePrivacy: 'public',
      variables: [], privacies: [] }], environmentVariablePatches: [] }
  }))
  await unwrap(await request.post(`${apiBaseURL}/api/board/rules`, {
    headers: headers(auth), data: { conditions: [{ deviceName: 'history_light',
      attribute: 'SwitchState', targetType: 'mode', relation: '=', value: 'off' }],
      command: { deviceName: 'history_light', action: 'on' }, ruleString: 'Turn on when off' }
  }))
  await unwrap(await request.post(`${apiBaseURL}/api/board/specs`, {
    headers: headers(auth), data: { name: 'Safety requirement', id: 'history_never', templateId: '3', templateLabel: 'Never',
      aConditions: [{ id: 'never_on', side: 'a', deviceId: 'history_light',
        deviceLabel: 'Hallway light with a complete readable historical name', targetType: 'mode',
        key: 'SwitchState', relation: '=', value: 'on' }], ifConditions: [], thenConditions: [] }
  }))
}

const currentModels = async (request: APIRequestContext, auth: AuthUser) =>
  unwrap<BoardModelFingerprints>(await request.get(`${apiBaseURL}/api/board/model-fingerprints`, {
    headers: headers(auth)
  }))

test('a requirement rename preserves the checked model and the name frozen in history', async ({ page, request }) => {
  test.setTimeout(120_000)
  const auth = await createAuthenticatedUser(request)
  await seedBoard(request, auth)
  const baseline = await currentModels(request, auth)
  const result = validateVerificationResult(await unwrap<VerificationResult>(await request.post(`${apiBaseURL}/api/verify`, {
    headers: headers(auth), data: settings
  })))
  expect(result.historyPersistence.status).toBe('SAVED')
  if (result.historyPersistence.status !== 'SAVED') throw new Error('Verification history was not saved')
  const runId = result.historyPersistence.runId
  expect(result.specResults[0]?.specificationSnapshot?.name).toBe('Safety requirement')
  await seedCompletionSession(page, auth)
  await page.goto('/#/board')
  await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
  await page.getByTestId('inspector-tab-specs').click()
  await page.getByTestId('edit-spec-history_never').click()
  const name = page.getByTestId('spec-name')
  await expect(name).toHaveValue('Safety requirement')
  await name.fill('   ')
  await expect(page.getByTestId('spec-create')).toBeDisabled()
  await name.fill('  Keep the hallway light off  ')
  const saved = page.waitForResponse(response => response.request().method() === 'PUT'
    && new URL(response.url()).pathname === '/api/board/specs/history_never')
  await clickUnderTooltip(page, page.getByTestId('spec-create'))
  expect((await saved).ok()).toBe(true)
  await expect(page.locator('[data-spec-id="history_never"] h4')).toHaveText('Keep the hallway light off')
  expect(await currentModels(request, auth)).toEqual(baseline)
  await page.getByTestId('board-undo').click()
  await expect(page.locator('[data-spec-id="history_never"] h4')).toHaveText('Safety requirement')
  await page.getByTestId('board-redo').click()
  await expect(page.locator('[data-spec-id="history_never"] h4')).toHaveText('Keep the hallway light off')
  expect(await currentModels(request, auth)).toEqual(baseline)
  await page.goto(`/#/board?run=verification:${runId}`)
  const dialog = page.getByTestId('verification-result-dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.getByTestId('spec-result-name')).toHaveText('Safety requirement')
  await expect(dialog).not.toContainText('Keep the hallway light off')
  await expect(page.getByTestId('verification-result-stale-banner')).toBeHidden()
})

const openHistory = async (page: Page) => {
  await expect(page.locator('[data-testid="open-history-panel"]:visible, [data-testid="restore-action-dock"]:visible').first()).toBeVisible()
  const restore = page.getByTestId('restore-action-dock')
  if (await restore.isVisible()) await restore.click()
  await clickUnderTooltip(page, page.getByTestId('open-history-panel'))
  await expect(page.getByTestId('trace-history-panel')).toBeVisible()
  await expect(page.getByTestId('history-layer-results')).toHaveAttribute('aria-pressed', 'true')
}

const seedCompletionSession = async (page: Page, auth: AuthUser) => {
  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('iot_verify_theme', 'light')
    localStorage.setItem('locale', 'en')
  }, auth)
}

const openReplayDuringPendingRun = async (page: Page, simulation: SimulationTrace) => {
  await page.getByTestId('open-history-panel').click()
  await expect(page.getByTestId('history-layer-tasks')).toHaveAttribute('aria-pressed', 'true')
  await page.getByTestId('history-layer-results').click()
  await page.getByTestId(`replay-simulation-trace-${simulation.id}`).click()
  await expect(page.getByTestId('simulation-timeline')).toBeVisible()
  await expect(page.getByTestId('simulation-timeline-step-input')).toHaveAttribute('max', String(simulation.states.length))
  await clickUnderTooltip(page, page.getByTestId('simulation-timeline-state-1'))
  await expect(page.getByTestId('simulation-timeline')).toHaveAttribute('data-selected-state-index', '1')
  await expect(page).toHaveURL(new RegExp(`run=simulation:${simulation.id}`))
}

const deferCompletionResponse = async (page: Page, endpoint: string, result: unknown) => {
  let started!: () => void
  let release!: () => void
  const requestStarted = new Promise<void>(resolve => { started = resolve })
  const released = new Promise<void>(resolve => { release = resolve })
  await page.route(`**${endpoint}`, async route => {
    if (route.request().method() !== 'POST') {
      await route.continue()
      return
    }
    started()
    await released
    await route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'Success', data: result }) })
  })
  return { requestStarted, release }
}

test('a synchronous simulation preserves the history replay and keeps an unsaved result explicitly available', async ({ page, request }) => {
  test.setTimeout(120_000)
  const auth = await createAuthenticatedUser(request)
  await seedBoard(request, auth)
  const historical = validateSimulationTrace(await unwrap<SimulationTrace>(await request.post(`${apiBaseURL}/api/simulate/traces`, {
    headers: headers(auth), data: { ...settings, steps: 3 }
  })))
  const saved = validateSimulationTrace(await unwrap<SimulationTrace>(await request.post(`${apiBaseURL}/api/simulate/traces`, {
    headers: headers(auth), data: { ...settings, steps: 1 }
  })))
  const unsaved = validateSimulationResult(await unwrap<SimulationResult>(await request.post(`${apiBaseURL}/api/simulate`, {
    headers: headers(auth), data: { ...settings, steps: 1 }
  })))
  expect(unsaved.historyPersistence.status).toBe('NOT_REQUESTED')
  expect(historical.states.length).toBeGreaterThan(unsaved.states.length)
  await seedCompletionSession(page, auth)
  for (const result of [saved, unsaved]) {
    const isSaved = result.historyPersistence.status === 'SAVED'
    const endpoint = isSaved ? '/api/simulate/traces' : '/api/simulate'
    const gate = await deferCompletionResponse(page, endpoint, result)
    try {
      await page.goto('/#/board')
      await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
      await page.getByTestId('open-simulation-panel').click()
      await clickUnderTooltip(page, page.getByTestId('simulation-mode-sync'))
      await page.getByTestId('simulation-steps-input').fill('1')
      const saveSwitch = page.getByTestId('simulation-save-history')
      if ((await saveSwitch.getAttribute('aria-checked') === 'true') !== isSaved) {
        await clickUnderTooltip(page, saveSwitch)
      }
      await clickUnderTooltip(page, page.getByTestId('run-simulation'))
      await gate.requestStarted
      await openReplayDuringPendingRun(page, historical)
      gate.release()
      const notice = page.locator('.iot-result-notification')
      if (isSaved) {
        await expect(page.getByText(/another trajectory is being replayed/)).toBeVisible()
        await expect(notice).toHaveCount(0)
      } else {
        await expect(notice).toContainText('Observation result available')
        await expect(notice).toContainText('no confirmed history save')
        await page.setViewportSize({ width: 375, height: 812 })
        expect(await notice.evaluate(element => {
          const box = element.getBoundingClientRect()
          return box.left >= 0 && box.right <= window.innerWidth && element.scrollWidth <= element.clientWidth
        })).toBe(true)
        await notice.getByTestId('available-result-open').click()
        await expect(notice).toBeVisible()
        await page.setViewportSize({ width: 1280, height: 900 })
      }
      await expect(page.getByTestId('simulation-timeline-step-input')).toHaveAttribute('max', String(historical.states.length))
      await expect(page.getByTestId('simulation-timeline')).toHaveAttribute('data-selected-state-index', '1')
      await expect(page).toHaveURL(new RegExp(`run=simulation:${historical.id}`))
      await page.getByTestId('simulation-timeline-close').click()
      if (!isSaved) {
        await notice.getByTestId('available-result-open').click()
        await expect(notice).toHaveCount(0)
        await expect(page.getByTestId('simulation-timeline-step-input')).toHaveAttribute('max', String(result.states.length))
        await expect(page.getByTestId('simulation-timeline')).toHaveAttribute('data-selected-state-index', '0')
        await expect(page).not.toHaveURL(/run=/)
        await page.getByTestId('simulation-timeline-close').click()
      }
    } finally {
      gate.release()
      await page.unrouteAll({ behavior: 'wait' })
    }
  }
})

// This journey checks the same reading context after switching language and viewport.
for (const locale of ['en']) {
  for (const width of [320]) {
    test(`history navigation keeps its reading context in ${locale} at ${width}px`, async ({ page, request }, testInfo) => {
      test.setTimeout(120_000)
      const auth = await createAuthenticatedUser(request)
      await seedBoard(request, auth)
      const verificationIds: number[] = []
      const simulationIds: number[] = []
      for (let index = 0; index < 4; index++) {
        const verification = await unwrap<VerificationResult>(await request.post(`${apiBaseURL}/api/verify`, {
          headers: headers(auth), data: settings
        }))
        verificationIds.push(verification.historyPersistence.runId!)
        const simulation = await unwrap<SimulationTrace>(await request.post(`${apiBaseURL}/api/simulate/traces`, {
          headers: headers(auth), data: { ...settings, steps: 3 }
        }))
        simulationIds.push(simulation.id!)
      }
      const exploration = await unwrap<{ id: number }>(await request.post(`${apiBaseURL}/api/fuzz/async`, {
        headers: headers(auth), data: { explorationMode: 'BOARD_SNAPSHOT', targetSpecIds: ['history_never'],
          maxIterations: 1, pathLength: 2, populationSize: 1, seed: 17 }
      }))
      let explorationRunId = 0
      await expect.poll(async () => {
        const task = await unwrap<{ status: string; runId?: number }>(await request.get(
          `${apiBaseURL}/api/fuzz/tasks/${exploration.id}`, { headers: headers(auth) }
        ))
        explorationRunId = task.runId || 0
        return task.status
      }).toBe('COMPLETED')
      expect(explorationRunId).toBeGreaterThan(0)
      await page.addInitScript(({ auth, locale }) => {
        localStorage.setItem('iot_verify_token', auth.token)
        localStorage.setItem('iot_verify_user', JSON.stringify(auth))
        localStorage.setItem('locale', locale)
      }, { auth, locale })
      await page.setViewportSize({ width, height: width === 320 ? 500 : 1080 })
      await page.goto('/#/board')
      await expect(page.getByTestId('board-root')).toBeVisible()
      await expect(page.getByTestId('board-data-loading')).toHaveCount(0)
      await openHistory(page)
      const history = page.getByTestId('trace-history-panel')
      const body = history.locator('.history-body')
      await selectHistoryResultType(page, 'verification')
      await expect(history.locator('[data-testid^="history-result-verification-"]')).toHaveCount(4)
      const runId = verificationIds[0]!
      const details = page.getByTestId(`run-details-verification-${runId}`)
      const evidence = page.getByTestId(`run-evidence-verification-${runId}`)
      await details.locator('summary').click()
      await evidence.locator('summary').click()
      const opener = page.getByTestId(`open-verification-run-${runId}`)
      await opener.scrollIntoViewIfNeeded()
      const scrollBefore = await body.evaluate(element => element.scrollTop)
      expect(scrollBefore).toBeGreaterThan(0)
      await opener.click()
      await expect(page.getByTestId('verification-result-dialog')).toBeVisible()
      await page.getByTestId('close-verification-result').click()
      await openHistory(page)
      await expect(history.getByTestId('history-result-type')).toHaveValue('verification')
      await expect(details, 'returning from a result must retain the expanded run details').toHaveAttribute('open', '')
      await expect(evidence).toHaveAttribute('open', '')
      await expect.poll(() => body.evaluate(element => element.scrollTop)).toBeCloseTo(scrollBefore, 0)
      await expect(opener).toBeInViewport({ ratio: 1 })

      const toggleLanguage = async () => {
        const english = await history.getByRole('heading', { name: 'Run History', exact: true }).count() > 0
        const label = english ? '切换到中文' : 'Switch to English'
        const button = page.getByRole('button', { name: label, exact: true })
        if (!await button.isVisible()) await page.locator('.scene-actions-menu > summary').click()
        await button.click()
        const menu = page.locator('.scene-actions-menu')
        if (await menu.getAttribute('open') !== null) await menu.locator('summary').click()
      }
      await toggleLanguage()
      await expect(details).toHaveAttribute('open', '')
      await expect(history.getByTestId('history-result-type')).toHaveValue('verification')
      await expect(page.getByTestId(`history-result-verification-${runId}`)).toBeInViewport()
      await page.setViewportSize({ width: width === 320 ? 1920 : 320, height: width === 320 ? 1080 : 500 })
      await expect(details).toHaveAttribute('open', '')
      await expect(history.getByTestId('history-result-type')).toHaveValue('verification')
      await expect(page.getByTestId(`history-result-verification-${runId}`)).toBeInViewport()
      await opener.scrollIntoViewIfNeeded()
      expect(await opener.evaluate(element => {
        const box = element.getBoundingClientRect()
        return element.contains(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2))
      })).toBe(true)
      await page.screenshot({ path: testInfo.outputPath('history-reading-context.png') })
      await selectHistoryResultType(page, 'simulation')
      await expect(history.locator('[data-testid^="history-result-simulation-"]')).toHaveCount(4)
      await expect.poll(() => body.evaluate(element => element.scrollTop)).toBe(0)
      await page.getByTestId(`replay-simulation-trace-${simulationIds[0]}`).click()
      await expect(page.getByTestId('simulation-timeline-host')).toBeVisible()
      await page.getByTestId('simulation-timeline-close').click()
      await openHistory(page)
      await expect(history.getByTestId('history-result-type')).toHaveValue('simulation')
      await selectHistoryResultType(page, 'fuzzing')
      await expect(history.locator('[data-testid^="history-result-fuzzing-"]')).toHaveCount(1)
      await page.getByTestId(`open-fuzzing-run-${explorationRunId}`).click()
      await expect(page.getByTestId('fuzzing-result-dialog')).toBeVisible()
      await page.getByTestId('close-fuzzing-result').click()
      await openHistory(page)
      await expect(history.getByTestId('history-result-type')).toHaveValue('fuzzing')
      await selectHistoryResultType(page, 'all')
      await expect(history.locator('.history-card')).toHaveCount(9)
      await expect.poll(() => body.evaluate(element => element.scrollTop)).toBe(0)
    })
  }
}
