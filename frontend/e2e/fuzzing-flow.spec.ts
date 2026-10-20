import { type APIRequestContext, type APIResponse, type Locator, type Page, type Response, type Route } from '@playwright/test'
import path from 'node:path'
import type { Specification } from '../src/types/spec'
import { selectHistoryResultType } from './support/historyFilter'
import {
  apiBaseURL,
  createAuthenticatedUser,
  expect,
  test,
  type AuthUser
} from './support/auth'

type FuzzTask = {
  id: number
  explorationMode: 'BOARD_SNAPSHOT' | 'PAPER_COMPATIBLE'
  status: string
  runId?: number | null
  createdAt: string
  completedAt?: string | null
}

type FuzzRun = {
  id: number
  explorationMode: 'BOARD_SNAPSHOT' | 'PAPER_COMPATIBLE'
  outcome: string
  findingCount: number
  findings: Array<{ id: number; violatedSpecId: string; violatedSpec: Specification }>
  modelSnapshot: { deviceCount: number }
  eligibleSpecifications: Record<string, Specification>
  modelTokenSourcesByDeviceId: Record<string, string>
  limitations: string[]
  eligibility: {
    eligibleSpecIds: string[]
    ineligibleSpecs: Array<{ specId: string, reasonCode: string }>
  }
}

const authHeaders = (auth: AuthUser) => ({
  Authorization: `Bearer ${auth.token}`
})

const unwrap = async <T>(response: APIResponse | Response): Promise<T> => {
  expect(response.ok(), await response.text()).toBeTruthy()
  const body = await response.json()
  expect(body.code, JSON.stringify(body)).toBe(200)
  return body.data as T
}

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

const waitForTask = async (
  request: APIRequestContext,
  auth: AuthUser,
  taskId: number
): Promise<FuzzTask> => {
  const deadline = Date.now() + 60_000
  let latest: FuzzTask | null = null

  while (Date.now() < deadline) {
    latest = await unwrap<FuzzTask>(await request.get(`${apiBaseURL}/api/fuzz/tasks/${taskId}`, {
      headers: authHeaders(auth)
    }))
    if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(latest.status)) return latest
    await new Promise(resolve => setTimeout(resolve, 250))
  }

  throw new Error(`Timed out waiting for fuzz task ${taskId}; latest=${JSON.stringify(latest)}`)
}

const waitForBoardSpecCount = async (
  request: APIRequestContext,
  auth: AuthUser,
  expectedCount: number
) => {
  const deadline = Date.now() + 30_000
  let latestCount = -1

  while (Date.now() < deadline) {
    const specs = await unwrap<unknown[]>(await request.get(`${apiBaseURL}/api/board/specs`, {
      headers: authHeaders(auth)
    }))
    latestCount = specs.length
    if (latestCount === expectedCount) return
    await new Promise(resolve => setTimeout(resolve, 250))
  }

  throw new Error(`Timed out waiting for ${expectedCount} board specs; latestCount=${latestCount}`)
}

const closeFuzzingPanelIfPresent = async (page: Page, panel: Locator) => {
  if (!(await panel.isVisible().catch(() => false))) return
  try {
    await page.getByTestId('close-fuzzing-panel').click({ timeout: 5_000 })
  } catch (error) {
    if (await panel.isVisible().catch(() => false)) throw error
  }
  await expect(panel).toBeHidden({ timeout: 20_000 })
}

test.describe('bounded counterexample exploration', () => {
  test('imports a scene, finds and replays a candidate, and remains usable on mobile', async ({ page, request }, testInfo) => {
    test.setTimeout(180_000)
    page.setDefaultTimeout(20_000)
    const auth = await createAuthenticatedUser(request)

    await test.step('complete the exploration journey', async () => {
      await openWorkspace(page, auth)

      const scenePath = path.resolve(process.cwd(), '..', 'docs', 'examples', 'default-climate-conflict-scene.json')
      await page.getByTestId('scene-import-file').setInputFiles(scenePath)
      await page.getByRole('dialog', { name: 'Replace the current scene?' })
        .getByRole('button', { name: 'Replace scene' })
        .click()
      await waitForBoardSpecCount(request, auth, 4)

      const initialWorkloadPreviewPromise = page.waitForResponse(response =>
        response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/api/fuzz/workload/preview')
      await page.getByTestId('open-fuzzing-panel').click()
      const panel = page.getByTestId('fuzzing-panel')
      await expect(panel).toBeVisible()
      const initialWorkloadPreview = await unwrap<{
        modelComplexityUnits: number
        estimatedWorkload: number
        workloadLimit: number
        accepted: boolean
      }>(await initialWorkloadPreviewPromise)
      expect(initialWorkloadPreview.modelComplexityUnits).toBeGreaterThan(0)
      expect(initialWorkloadPreview.estimatedWorkload).toBeLessThanOrEqual(
        initialWorkloadPreview.workloadLimit
      )
      expect(initialWorkloadPreview.accepted).toBe(true)
      const targets = panel.locator('input[type="checkbox"]')
      await expect(targets).toHaveCount(4)
      for (let index = 0; index < 2; index += 1) {
        await expect(targets.nth(index)).toBeChecked()
      }
      for (let index = 2; index < 4; index += 1) {
        await expect(targets.nth(index)).not.toBeChecked()
        await expect(targets.nth(index)).toBeDisabled()
      }

      await page.getByTestId('fuzz-max-iterations').fill('60')
      await page.getByTestId('fuzz-path-length').fill('8')
      await panel.locator('summary').filter({ hasText: 'Advanced settings' }).click()
      const configuredWorkloadPreviewPromise = page.waitForResponse(response => {
        if (response.request().method() !== 'POST'
          || new URL(response.url()).pathname !== '/api/fuzz/workload/preview') return false
        const body = response.request().postDataJSON()
        return body?.maxIterations === 60 && body?.pathLength === 8 && body?.populationSize === 4
      })
      await page.getByTestId('fuzz-population-size').fill('4')
      await page.getByTestId('fuzz-seed').fill('17')
      const configuredWorkloadPreview = await unwrap<{
        estimatedWorkload: number
        workloadLimit: number
        accepted: boolean
      }>(await configuredWorkloadPreviewPromise)
      expect(configuredWorkloadPreview.accepted).toBe(true)
      expect(configuredWorkloadPreview.estimatedWorkload)
        .toBeLessThanOrEqual(configuredWorkloadPreview.workloadLimit)

      // Hold only the page-side poll until the user has explicitly moved the run to the background.
      let releaseUiTaskPolling = () => {}
      let uiTaskPollingReleased = false
      const uiTaskPollingGate = new Promise<void>(resolve => {
        releaseUiTaskPolling = () => {
          uiTaskPollingReleased = true
          resolve()
        }
      })
      await page.route('**/api/fuzz/tasks/**', async route => {
        const requestUrl = new URL(route.request().url())
        const isTaskPoll = route.request().method() === 'GET'
          && /^\/api\/fuzz\/tasks\/\d+(?:\/progress)?$/.test(requestUrl.pathname)
        if (isTaskPoll && !uiTaskPollingReleased) await uiTaskPollingGate
        await route.continue()
      })

      const acceptedResponsePromise = page.waitForResponse(response =>
        response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/api/fuzz/async')
      await page.getByTestId('run-fuzzing').click()
      const acceptedResponse = await acceptedResponsePromise
      try {
        await closeFuzzingPanelIfPresent(page, panel)
      } finally {
        releaseUiTaskPolling()
      }
      expect(acceptedResponse.request().postDataJSON()).toMatchObject({
        explorationMode: 'BOARD_SNAPSHOT',
        maxIterations: 60,
        pathLength: 8,
        populationSize: 4,
        seed: 17
      })
      expect(acceptedResponse.request().postDataJSON().targetSpecIds).toHaveLength(2)
      const accepted = await unwrap<FuzzTask>(acceptedResponse)
      expect(accepted.explorationMode).toBe('BOARD_SNAPSHOT')

      const task = await waitForTask(request, auth, accepted.id)
      expect(task.status).toBe('COMPLETED')
      expect(task.completedAt).toEqual(expect.any(String))
      expect(Date.parse(task.completedAt!)).toBeGreaterThanOrEqual(Date.parse(task.createdAt))
      const runId = task.runId || task.id
      const run = await unwrap<FuzzRun>(await request.get(`${apiBaseURL}/api/fuzz/runs/${runId}`, {
        headers: authHeaders(auth)
      }))
      expect(run.explorationMode).toBe('BOARD_SNAPSHOT')
      expect(run.outcome).toBe('FOUND_VIOLATION')
      expect(run.findingCount).toBeGreaterThan(0)
      expect(run.findings).toHaveLength(run.findingCount)
      expect(run.eligibility.eligibleSpecIds).toHaveLength(2)
      expect(run.eligibility.ineligibleSpecs).toHaveLength(0)
      expect(Object.keys(run.eligibleSpecifications).sort()).toEqual([...run.eligibility.eligibleSpecIds].sort())
      expect(Object.keys(run.modelTokenSourcesByDeviceId)).toHaveLength(run.modelSnapshot.deviceCount)
      const runSummaries = await unwrap<Array<FuzzRun & { dataAvailable: boolean }>>(await request.get(
        `${apiBaseURL}/api/fuzz/runs`, { headers: authHeaders(auth) }
      ))
      const frozenSummary = runSummaries.find(summary => summary.id === runId)!
      expect(frozenSummary.dataAvailable).toBe(true)
      expect(frozenSummary.eligibleSpecifications).toEqual(run.eligibleSpecifications)
      expect(frozenSummary.modelTokenSourcesByDeviceId).toEqual(run.modelTokenSourcesByDeviceId)
      for (const finding of frozenSummary.findings) {
        expect(finding.violatedSpec).toEqual(run.eligibleSpecifications[finding.violatedSpecId])
        expect(finding).not.toHaveProperty('states')
      }

      const resultDialog = page.getByTestId('fuzzing-result-dialog')
      await expect(resultDialog).toBeHidden()
      await expect(page.getByTestId('fuzz-unread-badge')).toHaveText('1', { timeout: 60_000 })

      await page.getByTestId('open-history-panel').click()
      await expect(page.getByTestId(`open-fuzzing-run-${runId}`)).toBeVisible({ timeout: 30_000 })
      await expect(page.getByTestId('fuzz-unread-badge')).toHaveCount(0)
      await page.getByTestId(`open-fuzzing-run-${runId}`).click()
      await expect(resultDialog).toBeVisible()
      await expect(page.getByTestId('fuzzing-result-dialog').getByRole('dialog')).toBeFocused()
      await expect(resultDialog).toContainText('candidate')
      await expect(resultDialog).toContainText('Reproduction settings')
      const remainingTargets = run.eligibility.eligibleSpecIds.length - run.findings.length
      await expect(resultDialog.locator('[aria-labelledby="fuzz-target-results-title"]'))
        .toHaveCount(remainingTargets > 0 ? 1 : 0)
      await expect(resultDialog.locator('[aria-labelledby="fuzz-target-results-title"] li')).toHaveCount(remainingTargets)
      await expect(resultDialog).toContainText('Formal verification checks every specification on the current board')
      const settings = resultDialog.getByTestId('fuzz-reproduction-details')
      expect(await settings.evaluate(element => (element as HTMLDetailsElement).open)).toBe(false)
      const evidence = resultDialog.locator('[aria-labelledby="fuzz-findings-title"]')
      expect(await evidence.evaluate(element => element.getBoundingClientRect().top))
        .toBeLessThan(await settings.evaluate(element => element.getBoundingClientRect().top))
      await expect(resultDialog.getByTestId('reuse-fuzzing-settings')).toBeVisible()
      await page.screenshot({ path: testInfo.outputPath('exploration-evidence-first.png') })
      await page.setViewportSize({ width: 320, height: 600 })
      expect(await resultDialog.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
      const settingsTitle = settings.locator('summary')
      const reuseSettings = resultDialog.getByTestId('reuse-fuzzing-settings')
      expect(await settingsTitle.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
      expect(await settingsTitle.evaluate(element => element.getBoundingClientRect().bottom))
        .toBeLessThanOrEqual(await reuseSettings.evaluate(element => element.getBoundingClientRect().top))
      const metrics = resultDialog.locator('.board-metric')
      await expect(metrics).toHaveCount(3)
      const measurements = await metrics.evaluateAll(elements => elements.map(element => ({
        text: element.textContent, scrollWidth: element.scrollWidth, clientWidth: element.clientWidth
      })))
      await testInfo.attach('exploration-320-metrics', {
        body: Buffer.from(JSON.stringify(measurements)), contentType: 'application/json'
      })
      for (const metric of await metrics.all()) {
        await expect(metric).toBeVisible()
        expect(await metric.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
      }
      for (const title of await evidence.locator('article p').all()) {
        expect(await title.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
      }
      for (const candidate of await evidence.locator('article').all()) {
        const bounds = await candidate.evaluate(element => {
          const row = element.getBoundingClientRect()
          return Array.from(element.querySelectorAll('button')).map(button => {
            const action = button.getBoundingClientRect()
            return { text: button.textContent, left: action.left, right: action.right,
              rowLeft: row.left, rowRight: row.right }
          })
        })
        await testInfo.attach('exploration-320-actions', {
          body: Buffer.from(JSON.stringify(bounds)), contentType: 'application/json'
        })
        for (const action of bounds) {
          expect(action.left).toBeGreaterThanOrEqual(action.rowLeft)
          expect(action.right).toBeLessThanOrEqual(action.rowRight)
        }
      }
      await page.screenshot({ path: testInfo.outputPath('exploration-mobile.png') })
      await evidence.locator('article').first().scrollIntoViewIfNeeded()
      await page.screenshot({ path: testInfo.outputPath('exploration-mobile-actions.png') })
      await metrics.first().scrollIntoViewIfNeeded()
      await page.screenshot({ path: testInfo.outputPath('exploration-mobile-metrics.png') })
      await page.setViewportSize({ width: 1440, height: 900 })
      await expect(resultDialog.getByText('Fix', { exact: true })).toHaveCount(0)
      await page.waitForTimeout(500)
      await expect(page.locator('.el-message').filter({ hasText: 'Counterexample search completed' })).toHaveCount(0)
      await page.getByTestId('reuse-fuzzing-settings').click()
      await expect(panel).toBeVisible()
      await expect(page.getByTestId('fuzz-max-iterations')).toHaveValue('60')
      await expect(page.getByTestId('fuzz-path-length')).toHaveValue('8')
      await expect(page.getByTestId('fuzz-population-size')).toHaveValue('4')
      await expect(page.getByTestId('fuzz-seed')).toHaveValue('17')
      await expect(page.getByTestId('run-fuzzing')).toContainText('Start background search')
      await page.getByTestId('close-fuzzing-panel').click()
      await page.getByTestId('open-history-panel').click()
      const findingId = run.findings[0]!.id
      const historicalEvidence = page.getByTestId(`run-evidence-fuzzing-${runId}`)
      await historicalEvidence.locator('summary').click()
      await expect(historicalEvidence.getByTestId('history-fuzz-verification-scope'))
        .toContainText('every specification on the current board')
      await expect(historicalEvidence.getByTestId(`verify-fuzzing-finding-${findingId}`)).toHaveText('Configure current board verification')
      await page.screenshot({ path: testInfo.outputPath('history-candidate-scope.png') })
      await page.getByTestId(`open-fuzzing-run-${runId}`).click()
      await expect(resultDialog).toBeVisible()
      await page.getByTestId(`verify-fuzzing-finding-${findingId}`).click()
      const verificationPanel = page.getByTestId('verification-panel')
      await expect(verificationPanel).toBeVisible()
      await expect(page.getByTestId('fuzz-verification-handoff')).toContainText(`search run #${runId}`)
      await expect(page.getByTestId('fuzz-verification-handoff')).toContainText('complete specification set on the current Board')
      await expect(page.getByTestId('verification-panel')).toBeFocused()
      await page.getByTestId('verification-mode-sync').click()
      const verificationResponsePromise = page.waitForResponse(response =>
        response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/api/verify')
      await page.getByTestId('run-verification').click()
      const verification = await unwrap<{
        outcome: string
        modelComplete: boolean
        skippedSpecCount: number
        specResults: Array<{ outcome: string }>
        traces: Array<{ id: number }>
        historyPersistence: { status: string, runId?: number | null }
      }>(await verificationResponsePromise)
      expect(verification.outcome).toBe('VIOLATED')
      expect(verification.modelComplete).toBe(true)
      expect(verification.skippedSpecCount).toBe(0)
      expect(verification.specResults).toHaveLength(4)
      expect(verification.specResults.filter(result => result.outcome === 'VIOLATED')).toHaveLength(2)
      expect(verification.traces).toHaveLength(2)
      expect(verification.historyPersistence.status).toBe('SAVED')
      expect(verification.historyPersistence.runId).toEqual(expect.any(Number))
      const verificationRun = await unwrap<{
        outcome: string
        counterexampleCount: number
      }>(await request.get(
        `${apiBaseURL}/api/verify/runs/${verification.historyPersistence.runId}`,
        { headers: authHeaders(auth) }
      ))
      expect(verificationRun.outcome).toBe('VIOLATED')
      expect(verificationRun.counterexampleCount).toBe(2)
      const formalSummaries = await unwrap<Array<{ id: number; counterexamples: Array<{
        dataAvailable: boolean; modelTokenSourcesByDeviceId: Record<string, string>
      }> }>>(await request.get(`${apiBaseURL}/api/verify/runs`, { headers: authHeaders(auth) }))
      const formalSummary = formalSummaries.find(summary => summary.id === verification.historyPersistence.runId)!
      expect(formalSummary.counterexamples).toHaveLength(2)
      for (const trace of formalSummary.counterexamples) {
        expect(trace.dataAvailable).toBe(true)
        expect(Object.keys(trace.modelTokenSourcesByDeviceId)).toHaveLength(run.modelSnapshot.deviceCount)
        expect(trace).not.toHaveProperty('states')
      }
      const persistedTraces = await unwrap<Array<{ id: number }>>(await request.get(
        `${apiBaseURL}/api/verify/runs/${verification.historyPersistence.runId}/traces`,
        { headers: authHeaders(auth) }
      ))
      expect(persistedTraces).toHaveLength(2)
      await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })
      await page.getByTestId('close-verification-result').click()

      await page.getByTestId('open-history-panel').click()
      await selectHistoryResultType(page, 'verification')
      await expect(page.getByTestId(`open-verification-run-${verification.historyPersistence.runId}`)).toBeVisible()
      await expect(page.getByTestId('trace-history-panel').locator('[data-testid^="view-verification-trace-"]')).toHaveCount(2)
      await selectHistoryResultType(page, 'fuzzing')
      await page.getByTestId(`open-fuzzing-run-${runId}`).click()
      let independentFindingReadCount = 0
      let replayRunDetailReadCount = 0
      page.on('request', request => {
        if (request.method() === 'GET'
            && new URL(request.url()).pathname === `/api/fuzz/findings/${findingId}`) {
          independentFindingReadCount += 1
        }
        if (request.method() === 'GET'
            && new URL(request.url()).pathname === `/api/fuzz/runs/${runId}`) {
          replayRunDetailReadCount += 1
        }
      })
      const replayFindingResponsePromise = page.waitForResponse(response =>
        response.request().method() === 'GET'
          && new URL(response.url()).pathname === `/api/fuzz/findings/${findingId}`)
      await page.getByTestId(`replay-fuzzing-finding-${findingId}`).click()
      const replayFindingResponse = await replayFindingResponsePromise
      expect(replayFindingResponse.ok()).toBe(true)
      await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 30_000 })
      expect(independentFindingReadCount).toBe(1)
      expect(replayRunDetailReadCount).toBe(0)
      await expect(page.getByTestId('fuzzing-playback-notice')).toBeVisible()
      await expect(page.getByTestId('trace-timeline-incomplete-warning')).toHaveCount(0)
      await page.getByTestId('trace-timeline-run-tab').click()
      await expect(page.getByTestId('fuzzing-playback-notice')).toBeVisible()
      await page.getByTestId('trace-timeline-details-tab').click()
      await expect(page.getByTestId('fuzzing-playback-notice')).toBeVisible()
      await page.getByTestId('trace-timeline-changes-tab').click()
      await expect(page.getByTestId('trace-timeline').getByTestId('playback-changes-panel')).toBeVisible()
      await expect(page.getByTestId('playback-change-fuzz-inputs')).toBeVisible()
      const findingReplayUrl = page.url()
      await page.getByTestId('open-account-center').click()
      await expect(page.getByTestId('account-page')).toBeVisible()
      const accountHash = new URL(page.url()).hash
      expect(new URLSearchParams(accountHash.slice(accountHash.indexOf('?') + 1)).get('returnTo'))
        .toBe(`/board?run=exploration:${runId}&finding=${findingId}`)
      await page.getByTestId('account-back').click()
      await expect(page).toHaveURL(findingReplayUrl)
      await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 30_000 })
      const findingState = await page.getByTestId('trace-timeline').getAttribute('data-selected-state-index')
      await page.getByTestId('trace-timeline-hide-panel').click()
      await expect(page.getByTestId('trace-timeline')).toBeHidden()
      await expect(page.getByTestId('trace-timeline-close')).toBeVisible()
      expect(page.url()).toBe(findingReplayUrl)
      await page.getByTestId('playback-panel-toggle').click()
      await expect(page.getByTestId('trace-timeline')).toHaveAttribute('data-selected-state-index', findingState!)
      await page.getByTestId('trace-timeline-close').click()

      const historyPanel = page.getByTestId('trace-history-panel')
      if (await historyPanel.isVisible()) {
        await page.getByTestId('close-history-panel').click()
      }
      await page.getByTestId('open-fuzzing-panel').click()
      await expect(panel).toBeVisible()
      const paperPreviewResponsePromise = page.waitForResponse(response =>
        response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/api/fuzz/paper-domain/preview')
      await page.getByTestId('fuzz-mode-paper').check({ force: true })
      await expect(page.getByTestId('fuzz-mode-paper')).toBeChecked()
      const paperPreviewResponse = await paperPreviewResponsePromise
      const paperPreview = await unwrap<{
        pathLength: number
        modelFingerprint: string
        initializationPolicy: string
        deviceDomains: unknown[]
        localVariableDomains: unknown[]
        environmentDomains: unknown[]
      }>(paperPreviewResponse)
      expect(paperPreview.initializationPolicy).toBe('RANDOM_LEGAL_PER_SEED')
      expect(paperPreview.modelFingerprint).toMatch(/^[0-9a-f]{64}$/)
      expect(paperPreview.deviceDomains.length).toBeGreaterThan(0)
      expect(Array.isArray(paperPreview.localVariableDomains)).toBe(true)
      await expect(page.getByTestId('paper-domain-preview')).toContainText('Initial states and input ranges')
      await expect(page.getByTestId('paper-domain-choices')).toHaveJSProperty('open', false)
      await expect(page.getByTestId('paper-domain-boundaries')).toHaveJSProperty('open', false)
      await page.getByTestId('fuzz-max-iterations').fill('2')
      const refreshedPaperPreviewPromise = page.waitForResponse(response =>
        response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/api/fuzz/paper-domain/preview'
          && response.request().postDataJSON()?.pathLength === 4)
      await page.getByTestId('fuzz-path-length').fill('4')
      const refreshedPaperPreview = await unwrap<{
        pathLength: number
        modelFingerprint: string
        localVariableDomains: unknown[]
      }>(await refreshedPaperPreviewPromise)
      expect(refreshedPaperPreview.pathLength).toBe(4)
      expect(refreshedPaperPreview.modelFingerprint).toMatch(/^[0-9a-f]{64}$/)
      expect(Array.isArray(refreshedPaperPreview.localVariableDomains)).toBe(true)
      await panel.locator('summary').filter({ hasText: 'Advanced settings' }).click()
      await page.getByTestId('fuzz-population-size').fill('2')
      await page.getByTestId('fuzz-seed').fill('23')

      const environmentResponse = await request.get(`${apiBaseURL}/api/board/environment`, {
        headers: authHeaders(auth)
      })
      const environment = await unwrap<Array<{
        name: string
        value: string | null
        trust: string
        privacy: string
      }>>(environmentResponse)
      const temperature = environment.find(variable => variable.name === 'temperature')
      expect(temperature).toBeTruthy()
      const changedEnvironment = await request.post(`${apiBaseURL}/api/board/environment`, {
        headers: authHeaders(auth),
        data: [{
          name: 'temperature',
          expected: {
            value: temperature!.value,
            trust: temperature!.trust,
            privacy: temperature!.privacy
          },
          desired: { value: '31' }
        }]
      })
      await unwrap(changedEnvironment)
      const staleSubmissionPromise = page.waitForResponse(response =>
        response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/api/fuzz/async'
          && response.status() === 422)
      const recoveredPreviewPromise = page.waitForResponse(response =>
        response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/api/fuzz/paper-domain/preview'
          && response.request().postDataJSON()?.pathLength === 4)
      await page.getByTestId('run-fuzzing').click()
      const staleSubmission = await staleSubmissionPromise
      const staleBody = await staleSubmission.json()
      expect(staleBody.code).toBe(422)
      expect(staleBody.data?.errors?.paperDomainFingerprint).toBeTruthy()
      await expect(panel.getByText('The Board changed before submission', { exact: false }))
        .toBeVisible()
      const recoveredPaperPreview = await unwrap<{
        modelFingerprint: string
      }>(await recoveredPreviewPromise)
      await expect(panel.getByText('The Board changed before submission', { exact: false }))
        .toHaveCount(0)
      await expect(page.getByTestId('run-fuzzing')).toBeEnabled()

      const paperAcceptedResponsePromise = page.waitForResponse(response =>
        response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/api/fuzz/async')
      await page.getByTestId('run-fuzzing').click()
      const paperAcceptedResponse = await paperAcceptedResponsePromise
      await closeFuzzingPanelIfPresent(page, panel)
      const paperRequest = paperAcceptedResponse.request().postDataJSON()
      expect(paperRequest).toMatchObject({
        explorationMode: 'PAPER_COMPATIBLE',
        maxIterations: 2,
        pathLength: 4,
        populationSize: 2,
        seed: 23
      })
      expect(paperRequest.paperDomainFingerprint).toBe(recoveredPaperPreview.modelFingerprint)
      const paperAccepted = await unwrap<FuzzTask>(paperAcceptedResponse)
      expect(paperAccepted.explorationMode).toBe('PAPER_COMPATIBLE')
      const paperTask = await waitForTask(request, auth, paperAccepted.id)
      expect(paperTask.status).toBe('COMPLETED')
      expect(paperTask.completedAt).toEqual(expect.any(String))
      expect(Date.parse(paperTask.completedAt!)).toBeGreaterThanOrEqual(Date.parse(paperTask.createdAt))
      const paperRun = await unwrap<FuzzRun>(await request.get(
        `${apiBaseURL}/api/fuzz/runs/${paperTask.runId || paperTask.id}`,
        { headers: authHeaders(auth) }
      ))
      expect(paperRun.explorationMode).toBe('PAPER_COMPATIBLE')
      expect(paperRun.outcome).toBe('FOUND_VIOLATION')
      expect(paperRun.findingCount).toBeGreaterThan(0)
      expect(paperRun.findings).toHaveLength(paperRun.findingCount)
      expect(paperRun.limitations).toContain('PAPER_EVENT_FSM_DISTANCE_ENABLED')
      expect(paperRun.limitations).toContain('PAPER_RANDOM_INITIAL_STATE_ENABLED')
      expect(paperRun.limitations).toContain('PAPER_PREDECESSOR_STATE_OUTPUTS_THREE_LEVELS_ONLY')
      expect(paperRun.limitations).toContain('PAPER_MULTI_TARGET_PRODUCT_EXTENSION')

      await page.setViewportSize({ width: 390, height: 844 })
      await page.reload()
      await expect(page.getByTestId('canvas-fit-mobile')).toBeVisible()
      await expect.poll(async () => page.locator('.device-node').evaluateAll(nodes =>
        nodes.filter(node => {
          const rect = node.getBoundingClientRect()
          return rect.right > 0 && rect.bottom > 0
            && rect.left < window.innerWidth && rect.top < window.innerHeight
        }).length
      )).toBeGreaterThan(0)
      const mobileTargets = page.locator([
        '.board-nav-bar .logo-left:visible',
        '.board-nav-bar .nav-actions > button:visible',
        '.board-nav-bar .nav-actions > details > summary:visible',
        '[data-testid="control-center"] button:visible',
        '[data-testid="system-inspector"] button:visible',
        '[data-testid="canvas-fit-mobile"]:visible'
      ].join(', '))
      for (let index = 0; index < await mobileTargets.count(); index++) {
        const box = await mobileTargets.nth(index).boundingBox()
        expect(box).not.toBeNull()
        expect(box!.width).toBeGreaterThanOrEqual(44)
        expect(box!.height).toBeGreaterThanOrEqual(44)
      }
      const sceneActionsMenu = page.locator('details.scene-actions-menu')
      const sceneActionsTrigger = sceneActionsMenu.locator('summary')
      await sceneActionsTrigger.click()
      await expect(sceneActionsMenu).toHaveAttribute('open', '')
      await sceneActionsTrigger.press('Escape')
      await expect(sceneActionsMenu).not.toHaveAttribute('open', '')
      await expect(sceneActionsTrigger).toBeFocused()
      await page.getByTestId('restore-action-dock').click()
      await page.getByTestId('open-fuzzing-panel').click()
      await expect(panel).toBeVisible()
      const panelBox = await panel.boundingBox()
      expect(panelBox).not.toBeNull()
      expect(panelBox!.x).toBeGreaterThanOrEqual(0)
      expect(panelBox!.x + panelBox!.width).toBeLessThanOrEqual(390)
      expect(panelBox!.y).toBeGreaterThanOrEqual(0)
      expect(panelBox!.y + panelBox!.height).toBeLessThanOrEqual(844)
      expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    })
  })

  test('keeps transient narrow fitting out of the saved wide layout', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openWorkspace(page, auth)

    const zoomInput = page.getByTestId('canvas-map-zoom-input')
    await expect(zoomInput).toBeVisible()
    const widePanelState = await page.evaluate(() => ({
      controlCollapsed: document.querySelector('[data-testid="control-center"]')
        ?.classList.contains('is-collapsed') === true,
      inspectorCollapsed: document.querySelector('[data-testid="system-inspector"]')
        ?.classList.contains('is-collapsed') === true
    }))
    const savedLayouts: Array<{ canvasZoom?: number }> = []
    const captureLayoutSave = async (route: Route) => {
      const requestUrl = new URL(route.request().url())
      if (route.request().method() === 'POST' && requestUrl.pathname === '/api/board/layout') {
        savedLayouts.push(route.request().postDataJSON())
      }
      await route.continue()
    }
    await page.route('**/api/board/layout', captureLayoutSave)

    try {
      await zoomInput.fill('85')
      await page.setViewportSize({ width: 390, height: 844 })
      await expect(page.getByTestId('canvas-fit-mobile')).toBeVisible()
      await expect.poll(() => savedLayouts.length).toBeGreaterThan(0)
      await page.waitForTimeout(850)

      expect(savedLayouts.every(layout => layout.canvasZoom === 0.85)).toBe(true)

      await page.setViewportSize({ width: 1440, height: 900 })
      await expect.poll(() => page.evaluate(() => ({
        controlCollapsed: document.querySelector('[data-testid="control-center"]')
          ?.classList.contains('is-collapsed') === true,
        inspectorCollapsed: document.querySelector('[data-testid="system-inspector"]')
          ?.classList.contains('is-collapsed') === true
      }))).toEqual(widePanelState)
      await expect(zoomInput).toHaveValue('85')
    } finally {
      await page.unroute('**/api/board/layout', captureLayoutSave)
    }
  })

  test('does not let a stalled layout save block logout', async ({ page, request }) => {
    test.setTimeout(60_000)
    const auth = await createAuthenticatedUser(request)
    let releaseLayoutSave = () => {}

    try {
      await openWorkspace(page, auth)

      let markLayoutSaveStarted!: () => void
      const layoutSaveStarted = new Promise<void>(resolve => { markLayoutSaveStarted = resolve })
      const layoutSaveGate = new Promise<void>(resolve => { releaseLayoutSave = resolve })
      const delayLayoutSave = async (route: Route) => {
        const requestUrl = new URL(route.request().url())
        if (route.request().method() !== 'POST' || requestUrl.pathname !== '/api/board/layout') {
          await route.continue()
          return
        }
        markLayoutSaveStarted()
        await layoutSaveGate
        try {
          await route.continue()
        } catch {
          // Navigation may cancel the best-effort layout request after logout proceeds.
        }
      }
      await page.route('**/api/board/layout', delayLayoutSave)

      await page.getByTestId('control-center').locator('button').first().click()
      const startedAt = Date.now()
      await page.getByTestId('open-account-center').click()
      await layoutSaveStarted
      await expect(page.getByTestId('account-page')).toBeVisible()
      await page.getByTestId('account-logout').click()
      const dialog = page.getByRole('dialog', { name: 'Log Out' })
      await expect(dialog).toBeVisible()

      const logoutRequest = page.waitForRequest(request =>
        request.method() === 'POST' && new URL(request.url()).pathname === '/api/auth/logout')
      await dialog.getByRole('button', { name: 'Log Out' }).click()
      await logoutRequest
      expect(Date.now() - startedAt).toBeLessThan(4_000)
      await expect(page).toHaveURL(/#\/?\?mode=login/, { timeout: 10_000 })

      releaseLayoutSave()
      await page.unroute('**/api/board/layout', delayLayoutSave)
    } finally {
      releaseLayoutSave()
    }
  })

  test('keeps unread search updates when a delayed history view is closed', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    let releaseHistory = () => {}

    try {
      await page.addInitScript(({ userId }) => {
        window.localStorage.setItem(`iot_verify_fuzz_notifications_${userId}`, JSON.stringify({
          unread: [{
            taskId: 9_999_999,
            runId: 9_999_999,
            kind: 'COMPLETED',
            createdAt: new Date().toISOString()
          }],
          trackedTaskIds: []
        }))
      }, { userId: auth.userId })
      await openWorkspace(page, auth)
      await expect(page.getByTestId('fuzz-unread-badge')).toHaveText('1')

      let markHistoryStarted!: () => void
      let markHistoryFinished!: () => void
      const historyGate = new Promise<void>(resolve => { releaseHistory = resolve })
      const historyStarted = new Promise<void>(resolve => { markHistoryStarted = resolve })
      const historyFinished = new Promise<void>(resolve => { markHistoryFinished = resolve })
      const delayHistory = async (route: Route) => {
        const requestUrl = new URL(route.request().url())
        if (route.request().method() !== 'GET' || requestUrl.pathname !== '/api/fuzz/runs') {
          await route.continue()
          return
        }
        const response = await route.fetch()
        markHistoryStarted()
        await historyGate
        try {
          await route.fulfill({ response })
        } finally {
          markHistoryFinished()
        }
      }
      await page.route('**/api/fuzz/runs*', delayHistory)

      await page.getByTestId('open-history-panel').click()
      await historyStarted
      await page.getByTestId('close-history-panel').click()
      releaseHistory()
      await historyFinished
      await page.unroute('**/api/fuzz/runs*', delayHistory)

      await expect(page.getByTestId('trace-history-panel')).toBeHidden()
      await expect(page.getByTestId('fuzz-unread-badge')).toHaveText('1')
    } finally {
      releaseHistory()
    }
  })
})
