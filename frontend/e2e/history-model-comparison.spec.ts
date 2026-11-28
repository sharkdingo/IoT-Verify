import type { APIRequestContext, APIResponse, Page } from '@playwright/test'
import { unwrap } from './support/apiResponse'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'
import type { VerificationResult } from '../src/types/verify'
import type { SimulationResult, SimulationTrace } from '../src/types/simulation'
import type { FixResult } from '../src/types/fix'
import type { RuleDto } from '../src/types/rule'
import { validateSimulationResult, validateSimulationTrace, validateVerificationResult } from '../src/utils/runResponse'
import { readBoardModelFingerprints } from './support/boardSnapshot'
import { clickUnderTooltip } from './support/tooltips'
import { selectHistoryResultType } from './support/historyFilter'
import { shadowDefaultTemplate } from './support/templates'

const headers = (auth: AuthUser) => ({ Authorization: `Bearer ${auth.token}` })
const settings = { attackScenario: { mode: 'NONE' }, enablePrivacy: false }

const seedBoard = async (request: APIRequestContext, auth: AuthUser, id = 'history', specName = 'Safety requirement') => {
  const deviceId = `${id}_light`
  const label = id === 'history' ? 'Hallway light with a complete readable historical name' : `${id} hallway light`
  await unwrap(await request.post(`${apiBaseURL}/api/board/nodes`, {
    headers: headers(auth), data: { devices: [{ id: deviceId, templateName: 'Light',
      label, position: { x: 200, y: 200 },
      width: 176, height: 128, state: 'off', currentStateTrust: 'trusted', currentStatePrivacy: 'public',
      variables: [], privacies: [] }], environmentVariablePatches: [] }
  }))
  await unwrap(await request.post(`${apiBaseURL}/api/board/rules`, {
    headers: headers(auth), data: { conditions: [{ deviceName: deviceId,
      attribute: 'SwitchState', targetType: 'mode', relation: '=', value: 'off' }],
      command: { deviceName: deviceId, action: 'on' }, ruleString: 'Turn on when off' }
  }))
  await unwrap(await request.post(`${apiBaseURL}/api/board/specs`, {
    headers: headers(auth), data: { name: specName, id: `${id}_never`, templateId: '3', templateLabel: 'Never',
      aConditions: [{ id: 'never_on', side: 'a', deviceId,
        deviceLabel: label, targetType: 'mode',
        key: 'SwitchState', relation: '=', value: 'on' }], ifConditions: [], thenConditions: [] }
  }))
}

test('a requirement rename preserves the checked model and the name frozen in history', async ({ page, request }) => {
  test.setTimeout(120_000)
  const auth = await createAuthenticatedUser(request)
  await seedBoard(request, auth)
  const baseline = await readBoardModelFingerprints(request, auth.token)
  const result = validateVerificationResult(await unwrap<VerificationResult>(await request.post(`${apiBaseURL}/api/verify`, {
    headers: headers(auth), data: settings
  })))
  expect(result.historyPersistence.status).toBe('SAVED')
  if (result.historyPersistence.status !== 'SAVED') throw new Error('Verification history was not saved')
  const runId = result.historyPersistence.runId
  expect(result.modelSnapshot.modelFingerprint).toBe(baseline.verification)
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
  expect(await readBoardModelFingerprints(request, auth.token)).toEqual(baseline)
  await page.getByTestId('board-undo').click()
  await expect(page.locator('[data-spec-id="history_never"] h4')).toHaveText('Safety requirement')
  await page.getByTestId('board-redo').click()
  await expect(page.locator('[data-spec-id="history_never"] h4')).toHaveText('Keep the hallway light off')
  expect(await readBoardModelFingerprints(request, auth.token)).toEqual(baseline)
  await page.goto(`/#/board?run=verification:${runId}`)
  const dialog = page.getByTestId('verification-result-dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.getByTestId('spec-result-name')).toHaveText('Safety requirement')
  await expect(dialog).not.toContainText('Keep the hallway light off')
  await expect(page.getByTestId('verification-result-stale-banner')).toBeHidden()
})

test('uses known edits while model comparison is unavailable during verification', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  await seedBoard(request, auth)
  const before = await readBoardModelFingerprints(request, auth.token)
  const [rule] = await unwrap<RuleDto[]>(await request.get(`${apiBaseURL}/api/board/rules`, { headers: headers(auth) }))
  await seedCompletionSession(page, auth)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.route('**/api/board/model-fingerprints', route => route.fulfill({ status: 503,
    json: { code: 503, message: 'Model comparison is temporarily unavailable' } }))
  await page.goto('/#/board')
  await expect(page.getByTestId('board-root')).toBeVisible()

  const completeDuring = async (change: () => Promise<void>) => {
    let received!: (response: APIResponse) => void
    let release!: () => void
    const realReply = new Promise<APIResponse>(resolve => { received = resolve })
    const delivered = new Promise<void>(resolve => { release = resolve })
    await page.route(/\/api\/verify$/, async route => {
      const response = await route.fetch()
      received(response)
      await delivered
      await route.fulfill({ response })
    }, { times: 1 })
    try {
      await clickUnderTooltip(page, page.getByTestId('open-verification-panel'))
      await clickUnderTooltip(page, page.getByTestId('verification-mode-sync'))
      await clickUnderTooltip(page, page.getByTestId('run-verification'))
      const saved = validateVerificationResult(await unwrap<VerificationResult>(await realReply))
      if (saved.historyPersistence.status !== 'SAVED') throw new Error('Verification history was not saved')
      expect(saved.modelSnapshot.modelFingerprint).toBe(before.verification)
      expect(saved.traces[0]?.id).toBeGreaterThan(0)
      await change()
      return saved.historyPersistence.runId
    } finally { release() }
  }

  const dialog = page.getByTestId('verification-result-dialog')
  const comparison = dialog.getByTestId('verification-result-comparison-banner')
  const stale = dialog.getByTestId('verification-result-stale-banner')
  const unavailable = 'Current board comparison data could not be read, so model changes cannot be determined yet. Refresh and retry.'

  const journalRun = await completeDuring(async () => {
    // Expose undo recovery with a rejected inverse. Preview and clear still use the real journal.
    await page.route('**/api/board/edits/undo', route => route.fulfill({ status: 409,
      json: { code: 409, message: 'This edit can no longer be applied.' } }), { times: 1 })
    await clickUnderTooltip(page, page.getByTestId('board-undo'))
    const confirmation = page.getByRole('dialog', { name: 'Clear unavailable undo history?' })
    await expect(confirmation).toBeVisible()
    await confirmation.getByRole('button', { name: 'Clear history', exact: true }).click()
    await expect(page.getByText('Undo and redo history was cleared.', { exact: true })).toBeVisible()
    await expect(page.getByTestId('board-undo')).toBeDisabled()
    expect(await readBoardModelFingerprints(request, auth.token)).toEqual(before)
  })
  await expect(page).toHaveURL(new RegExp(`run=verification:${journalRun}$`))
  await expect(comparison.getByText(unavailable, { exact: true }), 'clearing only the journal cannot mark the result stale').toBeVisible()
  await expect(stale).toHaveCount(0)
  await clickUnderTooltip(page, dialog.getByTestId('close-verification-result'))

  const semanticRun = await completeDuring(async () => {
    await page.getByTestId('inspector-tab-rules').click()
    const row = page.getByTestId('system-inspector').locator(`[data-rule-id="${rule.id}"]`)
    await clickUnderTooltip(page, row.getByRole('button', { name: 'Delete rule', exact: true }))
    await page.locator('.el-message-box').getByRole('button', { name: 'Delete', exact: true }).click()
    await expect(row).toHaveCount(0)
    expect((await readBoardModelFingerprints(request, auth.token)).verification).not.toBe(before.verification)
  })
  await expect(page).toHaveURL(new RegExp(`run=verification:${semanticRun}$`))
  await expect(stale).toContainText('This run used a model that differs from the current Board model. Verify again.')
  await expect(comparison).toHaveCount(0)
  await expect(dialog.getByTestId('verification-result-reverify')).toBeEnabled()
  await clickUnderTooltip(page, dialog.getByTestId('close-verification-result'))

  await openHistory(page)
  await page.getByTestId(`open-verification-run-${semanticRun}`).click()
  await expect(comparison.getByText(unavailable, { exact: true }), 'opening a saved run does not inherit the submission-time edit count').toBeVisible()
  await expect(stale).toHaveCount(0)
})

test('a historical modeling version stays replayable and cannot repair the current board', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  await seedBoard(request, auth)
  const result = validateVerificationResult(await unwrap<VerificationResult>(await request.post(`${apiBaseURL}/api/verify`, {
    headers: headers(auth), data: settings
  })))
  if (result.historyPersistence.status !== 'SAVED') throw new Error('Verification history was not saved')
  const runId = result.historyPersistence.runId
  const traceId = result.traces[0]?.id
  expect(traceId).toBeGreaterThan(0)
  expect(result.modelSnapshot.modelFingerprint).toBe((await readBoardModelFingerprints(request, auth.token)).verification)

  // The saved run and replay come from the real backend. Change only the modeling version delivered to the client.
  const previousSemanticsVersion = result.modelSnapshot.modelSemanticsVersion - 1
  await page.route(/\/api\/verify\/(?:runs(?:\/\d+(?:\/traces)?)?|traces\/\d+)$/, async route => {
    const endpoint = new URL(route.request().url()).pathname
    const runList = endpoint === '/api/verify/runs'
    if (!runList && endpoint !== `/api/verify/runs/${runId}` && endpoint !== `/api/verify/runs/${runId}/traces`
      && endpoint !== `/api/verify/traces/${traceId}`) {
      await route.continue()
      return
    }
    const response = await route.fetch()
    const body = await response.json()
    const priorVersion = (entry: any) => entry?.modelSnapshot
      ? { ...entry, modelSnapshot: { ...entry.modelSnapshot, modelSemanticsVersion: previousSemanticsVersion } } : entry
    body.data = Array.isArray(body.data)
      ? body.data.map(entry => !runList || entry.id === runId ? priorVersion(entry) : entry) : priorVersion(body.data)
    await route.fulfill({ response, json: body })
  })
  await seedCompletionSession(page, auth)
  await page.goto(`/#/board?run=verification:${runId}`)
  const dialog = page.getByTestId('verification-result-dialog')
  await expect(dialog.getByTestId('verification-result-stale-banner')).toContainText('modeling version')
  await expect(dialog.getByTestId('verification-trace-fix')).toHaveCount(0)
  await expect(dialog.getByTestId('verification-result-reverify')).toBeEnabled()
  await dialog.getByTestId('verification-trace-replay').click()
  await expect(page.getByTestId('trace-model-version-warning')).toContainText('modeling version')
  await page.getByTestId('trace-timeline-close').click()

  await openHistory(page)
  await expect(page.getByTestId(`verification-history-model-comparison-${runId}`)).toHaveAttribute('data-state', 'SEMANTICS_CHANGED')
  await page.getByTestId(`run-evidence-verification-${runId}`).locator('summary').click()
  await expect(page.getByTestId(`fix-verification-trace-${traceId}`)).toBeDisabled()
  await expect(page.getByTestId(`view-verification-trace-${traceId}`)).toBeEnabled()

  await page.getByTestId(`open-verification-run-${runId}`).click()
  await dialog.getByTestId('verification-result-reverify').click()
  await clickUnderTooltip(page, page.getByTestId('verification-mode-sync'))
  await clickUnderTooltip(page, page.getByTestId('run-verification'))
  await expect(dialog.getByTestId('verification-trace-fix')).toBeVisible()
  await expect(dialog.getByTestId('verification-result-reverify')).toHaveCount(0)
})

test('retires the rejected repair source when the current fingerprint still matches', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  await seedBoard(request, auth)
  const result = validateVerificationResult(await unwrap<VerificationResult>(await request.post(`${apiBaseURL}/api/verify`, {
    headers: headers(auth), data: settings
  })))
  if (result.historyPersistence.status !== 'SAVED') throw new Error('Verification history was not saved')
  const { runId } = result.historyPersistence
  const traceId = result.traces[0]?.id
  expect(traceId).toBeGreaterThan(0)
  expect(result.modelSnapshot.modelFingerprint).toBe((await readBoardModelFingerprints(request, auth.token)).verification)

  await page.route(`**/api/verify/traces/${traceId}/fault-rules`, route => route.fulfill({ status: 409,
    json: { code: 409, message: 'Verify the current board again.', data: { reasonCode: 'MODEL_SEMANTICS_CHANGED' } } }))
  await seedCompletionSession(page, auth)
  await page.goto(`/#/board?run=verification:${runId}`)
  const dialog = page.getByTestId('verification-result-dialog')
  await dialog.getByTestId('verification-trace-fix').click()
  const repair = page.getByTestId('fix-result-dialog')
  await expect(repair.getByTestId('fix-reverify-current-board')).toBeVisible()
  await repair.getByTestId('fix-result-header').getByRole('button', { name: 'Close', exact: true }).click()

  await expect(dialog.getByTestId('verification-trace-fix-unavailable')).toContainText('modeling version')
  await expect(dialog.getByTestId('verification-trace-fix')).toHaveCount(0)
  await expect(dialog.getByTestId('verification-result-reverify')).toBeEnabled()
  await dialog.getByTestId('close-verification-result').click()

  await openHistory(page)
  await expect(page.getByTestId(`verification-history-model-comparison-${runId}`)).toHaveAttribute('data-state', 'UNCHANGED')
  await page.getByTestId(`run-evidence-verification-${runId}`).locator('summary').click()
  await expect(page.getByTestId(`fix-verification-trace-${traceId}`)).toBeDisabled()
  await expect(page.getByTestId(`view-verification-trace-${traceId}`)).toBeEnabled()
})

for (const change of ['presentation', 'semantics'] as const) {
  test(`template ${change} change uses one repair decision in results, history and apply`, async ({ page, request }) => {
    test.setTimeout(120_000)
    const auth = await createAuthenticatedUser(request)
    const authorization = headers(auth)
    await shadowDefaultTemplate(request, auth, 'Light', manifest => {
      if (change === 'presentation') {
        manifest.Description = 'Original display description'
        manifest.Icon = `data:image/svg+xml,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1"><path d="M0 0h1v1H0z"/></svg>')}`
      } else {
        manifest.InitState = 'off'
      }
    })
    await seedBoard(request, auth)
    const result = validateVerificationResult(await unwrap<VerificationResult>(await request.post(`${apiBaseURL}/api/verify`, {
      headers: authorization, data: settings
    })))
    if (result.historyPersistence.status !== 'SAVED' || !result.traces[0]?.id) {
      throw new Error('A saved counterexample is required')
    }
    const { runId } = result.historyPersistence
    const traceId = result.traces[0].id
    const fixUrl = `${apiBaseURL}/api/verify/traces/${traceId}/fix`
    const search = async (requestId: string) => unwrap<FixResult>(await request.post(fixUrl, {
      headers: authorization, params: { requestId }, data: { strategies: ['remove'] }
    }))
    const originalProposal = (await search('before-template-reset')).suggestions.find(item => item.strategy === 'remove')
    if (!originalProposal?.suggestionToken) throw new Error('A signed rule removal is required')
    const resetUrl = `${apiBaseURL}/api/board/templates/defaults`
    const preview = await unwrap<{ canApply: boolean; impactToken: string }>(await request.get(`${resetUrl}/reset-preview`, {
      headers: authorization
    }))
    expect(preview.canApply).toBe(true)
    await unwrap(await request.post(`${resetUrl}/reset`, { headers: authorization, data: { impactToken: preview.impactToken } }))
    const matches = change === 'presentation'
    expect((await readBoardModelFingerprints(request, auth.token)).verification === result.modelSnapshot.modelFingerprint).toBe(matches)
    expect((await search('after-template-reset')).templateSnapshotComparison).toBe(matches ? 'UNCHANGED' : 'CHANGED')

    await seedCompletionSession(page, auth)
    await page.goto(`/#/board?run=verification:${runId}`)
    const dialog = page.getByTestId('verification-result-dialog')
    const repair = page.getByTestId('fix-result-dialog')
    if (matches) {
      await dialog.getByTestId('verification-trace-fix').click()
      await repair.getByTestId('fix-result-header').getByRole('button', { name: 'Close', exact: true }).click()
    } else {
      await expect(dialog.getByTestId('verification-result-stale-banner')).toContainText('Verify again')
      await expect(dialog.getByTestId('verification-trace-fix')).toHaveCount(0)
      await expect(dialog.getByTestId('verification-result-reverify')).toBeEnabled()
    }
    await dialog.getByTestId('close-verification-result').click()
    await openHistory(page)
    await page.getByTestId(`run-evidence-verification-${runId}`).locator('summary').click()
    const historyFix = page.getByTestId(`fix-verification-trace-${traceId}`)
    if (matches) {
      await historyFix.click()
      await repair.getByTestId('fix-strategy-remove').click()
      await repair.getByTestId('fix-try-current').click()
      await expect(repair.getByTestId('fix-apply-current')).toBeEnabled()
    } else {
      await expect(historyFix).toBeDisabled()
      await expect(historyFix).toHaveAccessibleDescription(/verify again/i)
    }

    const readRules = async () => unwrap<RuleDto[]>(await request.get(`${apiBaseURL}/api/board/rules`, { headers: authorization }))
    const rulesBeforeApply = await readRules()
    expect(rulesBeforeApply).toHaveLength(1)
    const apply = await request.post(`${fixUrl}/apply`, { headers: authorization, data: {
      strategy: 'remove', suggestion: originalProposal, suggestionToken: originalProposal.suggestionToken
    } })
    if (matches) {
      expect(await unwrap<{ applied: boolean }>(apply)).toMatchObject({ applied: true })
      expect(await readRules()).toHaveLength(0)
    } else {
      expect(apply.status()).toBe(400)
      expect((await apply.json()).message).toContain('Please re-run verification')
      expect(await readRules()).toEqual(rulesBeforeApply)
    }
  })
}

test('an unconfirmed apply blocks its trace on both surfaces while the model still matches', async ({ page, request }) => {
  test.setTimeout(120_000)
  const auth = await createAuthenticatedUser(request)
  await seedBoard(request, auth)
  await seedBoard(request, auth, 'second', 'Second safety requirement')
  const baseline = await readBoardModelFingerprints(request, auth.token)
  const result = validateVerificationResult(await unwrap<VerificationResult>(await request.post(`${apiBaseURL}/api/verify`, {
    headers: headers(auth), data: settings
  })))
  if (result.historyPersistence.status !== 'SAVED') throw new Error('Verification history was not saved')
  const { runId } = result.historyPersistence
  const first = result.traces.find(trace => trace.violatedSpecId === 'history_never')
  const second = result.traces.find(trace => trace.violatedSpecId === 'second_never')
  if (!first?.id || !second?.id) throw new Error('Both saved counterexamples are required')

  await seedCompletionSession(page, auth)
  await page.goto(`/#/board?run=verification:${runId}`)
  const dialog = page.getByTestId('verification-result-dialog')
  const firstRow = dialog.locator('[data-spec-id="history_never"]')
  const secondRow = dialog.locator('[data-spec-id="second_never"]')
  await firstRow.getByTestId('verification-trace-fix').click()
  const repair = page.getByTestId('fix-result-dialog')
  await repair.getByTestId('fix-strategy-remove').click()
  await repair.getByTestId('fix-try-current').click()
  const apply = repair.getByTestId('fix-apply-current')
  await expect(apply).toBeEnabled({ timeout: 60_000 })
  let applyRequests = 0
  await page.route(`**/api/verify/traces/${first.id}/fix/apply`, async route => {
    applyRequests += 1
    await route.abort('failed')
  })
  await apply.click()
  await page.getByLabel('Confirm rule removal').getByRole('button', { name: 'Remove Rules and Apply' }).click()
  await expect(repair).toBeHidden()
  expect(applyRequests).toBe(1)
  expect(await readBoardModelFingerprints(request, auth.token)).toEqual(baseline)
  await expect(dialog.getByTestId('verification-fix-applied')).toHaveCount(0)
  await expect(dialog.getByTestId('verification-result-stale-banner')).toHaveCount(0)
  await expect(firstRow.getByTestId('verification-trace-fix')).toHaveCount(0)
  await expect(firstRow.getByTestId('verification-trace-fix-unavailable')).toContainText('could not be confirmed')
  await expect(dialog.getByTestId('verification-result-reverify')).toBeEnabled()
  const secondResultFix = secondRow.getByTestId('verification-trace-fix')
  await expect(secondResultFix).toBeEnabled()
  await secondResultFix.click()
  await expect(repair).toBeVisible()
  const closeRepair = repair.getByTestId('fix-result-header').getByRole('button', { name: 'Close', exact: true })
  await closeRepair.click()

  await dialog.getByTestId('close-verification-result').click()
  await openHistory(page)
  await expect(page.getByTestId(`verification-history-model-comparison-${runId}`)).toHaveAttribute('data-state', 'UNCHANGED')
  await page.getByTestId(`run-evidence-verification-${runId}`).locator('summary').click()
  const firstFix = page.getByTestId(`fix-verification-trace-${first.id}`)
  const secondFix = page.getByTestId(`fix-verification-trace-${second.id}`)
  await expect(firstFix).toBeDisabled()
  await expect(firstFix).toHaveAccessibleDescription(/could not be confirmed/i)
  await secondFix.click()
  await expect(repair).toBeVisible()
  await closeRepair.click()

  await seedBoard(request, auth, 'changed', 'New safety requirement')
  await page.reload()
  await openHistory(page)
  await page.getByTestId(`run-evidence-verification-${runId}`).locator('summary').click()
  await expect(firstFix).toBeDisabled()
  await expect(secondFix).toBeDisabled()
  await expect(secondFix).toHaveAccessibleDescription(/verify again/i)
  await page.getByTestId(`open-verification-run-${runId}`).click()
  await expect(dialog.getByTestId('verification-trace-fix')).toHaveCount(0)
  await expect(dialog.getByTestId('verification-result-stale-banner')).toContainText('Verify again')
  await expect(dialog.getByTestId('verification-result-reverify')).toBeEnabled()
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
  const oneStepResult = validateSimulationResult(await unwrap<SimulationResult>(await request.post(`${apiBaseURL}/api/simulate`, {
    headers: headers(auth), data: { ...settings, steps: 1 }
  })))
  // Reuse the real one-step result as a short response to a longer request.
  const unsaved = validateSimulationResult({ ...oneStepResult, requestedSteps: 3 })
  expect(unsaved.steps).toBeLessThan(unsaved.requestedSteps)
  expect(unsaved.historyPersistence.status).toBe('NOT_REQUESTED')
  expect(unsaved.historyPersistence.runId ?? null).toBeNull()
  expect(unsaved.hasSmvModel).toBe(true)
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
      await page.getByTestId('simulation-steps-input').fill(String(result.requestedSteps))
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
        // Opening Run History exits the active observation surface before starting the replay.
        await expect(page.getByText(`Observation saved: ${result.states.length} states`, { exact: true })).toBeVisible()
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
        await page.getByTestId('simulation-timeline-run-tab').click()
        await expect(page.getByTestId('simulation-run-info')
          .getByText(`${result.steps} / ${result.requestedSteps} Requested Steps`, { exact: true })).toBeVisible()
        const artifact = page.getByTestId('simulation-run-artifact')
        await expect(artifact.getByRole('button', { name: 'Download SMV Model', exact: true })).toBeVisible()
        await expect(artifact.getByRole('button', { name: 'Download SMV Model', exact: true })).toBeDisabled()
        await expect(artifact.getByTestId('simulation-result-smv-unavailable')).toContainText('This run was not saved to history')
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
