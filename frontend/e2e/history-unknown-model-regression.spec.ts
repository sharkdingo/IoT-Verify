import type { APIResponse } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test } from './support/auth'

const unwrap = async (response: APIResponse) => {
  expect(response.ok(), await response.text()).toBeTruthy()
  const body = await response.json()
  expect(body.code, JSON.stringify(body)).toBe(200)
  return body.data
}

test('an unavailable current-model comparison withholds historical Fix and explains the unknown state', async ({ page, request }, testInfo) => {
  test.setTimeout(90_000)
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'historyunknown' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  const runtime = {
    state: 'off', currentStateTrust: 'trusted', currentStatePrivacy: 'public',
    variables: [], privacies: []
  }
  await unwrap(await request.post(`${apiBaseURL}/api/board/nodes`, {
    headers,
    data: { devices: [{
      id: 'unknown_light', templateName: 'Light', label: 'Historical comparison light',
      position: { x: 200, y: 200 }, width: 176, height: 128, ...runtime
    }], environmentVariablePatches: [] }
  }))
  await unwrap(await request.post(`${apiBaseURL}/api/board/rules`, {
    headers,
    data: {
      conditions: [{ deviceName: 'unknown_light', attribute: 'SwitchState',
        targetType: 'mode', relation: '=', value: 'off' }],
      command: { deviceName: 'unknown_light', action: 'on' }, ruleString: 'Turn on when off'
    }
  }))
  await unwrap(await request.post(`${apiBaseURL}/api/board/specs`, {
    headers,
    data: { name: 'Safety requirement',
      id: 'unknown_never', templateId: '3',
      aConditions: [{ deviceId: 'unknown_light', targetType: 'mode',
        key: 'SwitchState', relation: '=', value: 'on' }],
      ifConditions: [], thenConditions: []
    }
  }))
  const original = await unwrap(await request.get(`${apiBaseURL}/api/board/model-fingerprints`, { headers }))
  const verdict = await unwrap(await request.post(`${apiBaseURL}/api/verify`, {
    headers, data: { attackScenario: { mode: 'NONE' }, enablePrivacy: false }
  }))
  expect(verdict.outcome).toBe('VIOLATED')
  expect(verdict.historyPersistence.status).toBe('SAVED')
  const runId = verdict.historyPersistence.runId
  await unwrap(await request.put(`${apiBaseURL}/api/board/nodes/runtime`, {
    headers, params: { nodeId: 'unknown_light' },
    data: { expected: runtime, desired: { ...runtime, state: 'on' } }
  }))
  const changed = await unwrap(await request.get(`${apiBaseURL}/api/board/model-fingerprints`, { headers }))
  expect(changed.verification).not.toBe(original.verification)

  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('locale', 'en')
  }, auth)
  await page.goto(`/#/board?run=verification:${runId}`)
  const dialog = page.getByTestId('verification-result-dialog')
  await expect(dialog).toBeVisible()
  await expect(page.getByTestId('verification-result-stale-banner')).toBeVisible()
  await expect(page.getByTestId('verification-trace-fix')).toHaveCount(0)

  // Real saved evidence and the changed Board stay intact; only the current comparison read fails.
  let releaseComparison!: () => void
  const comparisonGate = new Promise<void>(resolve => { releaseComparison = resolve })
  await page.route('**/api/board/model-fingerprints', async route => {
    await comparisonGate
    await route.fulfill({
      status: 503, contentType: 'application/json',
      body: JSON.stringify({ code: 503, message: 'Unavailable', data: null })
    })
  })
  const failedComparison = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/board/model-fingerprints' && response.status() === 503)
  try {
    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(dialog).toBeVisible()
    const banner = dialog.getByTestId('verification-result-comparison-banner')
    await expect(banner).toBeVisible()
    await expect(banner).toContainText('Comparing the current board')
    await expect(page.getByTestId('verification-trace-fix')).toHaveCount(0)
    await expect(dialog.getByTestId('verification-trace-replay')).toBeEnabled()

    releaseComparison()
    await failedComparison
    await expect(page.getByTestId('verification-trace-fix')).toHaveCount(0)
    await expect(banner).toContainText('Current board comparison data could not be read, so model changes cannot be determined yet. Refresh and retry.')
    await expect(dialog.getByTestId('verification-trace-replay')).toBeEnabled()
  } finally {
    releaseComparison()
    await page.unrouteAll({ behavior: 'wait' })
  }

  // Reuse the real saved run with an unchanged model before testing a warm comparison race.
  await unwrap(await request.put(`${apiBaseURL}/api/board/nodes/runtime`, {
    headers, params: { nodeId: 'unknown_light' },
    data: { expected: { ...runtime, state: 'on' }, desired: runtime }
  }))
  const restored = await unwrap(await request.get(`${apiBaseURL}/api/board/model-fingerprints`, { headers }))
  expect(restored).toEqual(original)
  await page.reload({ waitUntil: 'domcontentloaded' })
  await expect(dialog).toBeVisible()
  await expect(page.getByTestId('verification-trace-fix')).toBeVisible()
  await expect(page.getByTestId('verification-result-stale-banner')).toHaveCount(0)
  await expect(dialog.getByTestId('verification-result-comparison-banner')).toHaveCount(0)

  const publisher = await page.context().newPage()
  let releaseOlder!: () => void
  const olderGate = new Promise<void>(resolve => { releaseOlder = resolve })
  const reads = { fingerprints: 0, snapshots: 0, pendingFingerprints: 0 }
  let recoverComparisons = false
  let failedComparisons = 0
  let recoveredComparisons = 0
  let comparisonStarted!: () => void
  const olderStarted = new Promise<void>(resolve => { comparisonStarted = resolve })
  try {
    await publisher.goto('/#/board')
    await expect(publisher.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
    expect(await publisher.evaluate(() => document.visibilityState)).toBe('visible')
    expect(await page.evaluate(() => document.visibilityState)).toBe('visible')
    await page.evaluate(() => {
      const events = { focus: 0, visibility: 0 }
      ;(window as any).__historyComparisonEvents = events
      window.addEventListener('focus', () => { events.focus += 1 })
      document.addEventListener('visibilitychange', () => { events.visibility += 1 })
    })
    await page.route('**/api/board/model-fingerprints', async route => {
      const comparisonIndex = ++reads.fingerprints
      reads.pendingFingerprints += 1
      try {
        if (comparisonIndex > 1 && !recoverComparisons) {
          failedComparisons += 1
          await route.fulfill({ status: 503, contentType: 'application/json',
            body: JSON.stringify({ code: 503, message: 'Unavailable', data: null }) })
          return
        }
        const response = await route.fetch()
        expect(response.ok()).toBe(true)
        expect((await response.json()).data).toEqual(original)
        if (comparisonIndex === 1) {
          comparisonStarted()
          await olderGate
        } else {
          recoveredComparisons += 1
        }
        await route.fulfill({ response })
      } finally {
        reads.pendingFingerprints -= 1
      }
    })
    await page.route('**/api/board/snapshot', async route => {
      reads.snapshots += 1
      if (reads.snapshots === 1) {
        await route.continue()
        return
      }
      await route.fulfill({ status: 503, contentType: 'application/json',
        body: JSON.stringify({ code: 503, message: 'Unavailable', data: null }) })
    })
    const invalidateFromOtherWindow = () => publisher.evaluate(userId => {
      // Exercise the real transport and subscriber; no UI edit or model change is fabricated.
      const channel = new BroadcastChannel('iot-verify-board-invalidation-v1')
      channel.postMessage({ type: 'BOARD_INVALIDATED', userId, reason: 'manual', issuedAt: Date.now() })
      channel.close()
    }, auth.userId)
    await invalidateFromOtherWindow()
    await olderStarted
    expect(reads).toEqual({ fingerprints: 1, snapshots: 1, pendingFingerprints: 1 })
    const banner = dialog.getByTestId('verification-result-comparison-banner')
    await expect(banner).toContainText('Comparing the current board')

    const failedSnapshot = page.waitForResponse(response =>
      new URL(response.url()).pathname === '/api/board/snapshot' && response.status() === 503)
    await invalidateFromOtherWindow()
    await failedSnapshot
    await expect(page.getByTestId('board-data-load-error')).toBeVisible()
    expect(reads).toEqual({ fingerprints: 1, snapshots: 2, pendingFingerprints: 1 })
    const settledOlder = page.waitForResponse(response =>
      new URL(response.url()).pathname === '/api/board/model-fingerprints' && response.status() === 200)
    releaseOlder()
    await settledOlder
    await expect.poll(() => reads.pendingFingerprints).toBe(0)
    await expect(banner).toContainText('Current board comparison data could not be read, so model changes cannot be determined yet. Refresh and retry.')
    await expect(banner).not.toContainText('Comparing the current board')
    await expect(page.getByTestId('verification-trace-fix')).toHaveCount(0)
    await expect(dialog.getByTestId('verification-trace-replay')).toBeEnabled()

    await page.getByTestId('close-verification-result').click()
    await page.getByTestId('open-history-panel').click()
    await expect(page.getByTestId('trace-history-panel')).toBeVisible()
    await page.getByTestId('history-layer-results').click()
    await page.getByTestId(`run-evidence-verification-${runId}`).locator('summary').click()
    await expect.poll(() => failedComparisons).toBeGreaterThan(0)
    await expect.poll(() => reads.pendingFingerprints).toBe(0)
    await expect(page.getByTestId('trace-history-panel').getByRole('button', { name: 'Refresh', exact: true })).toBeEnabled()
    await expect(page.getByTestId(`verification-history-model-comparison-${runId}`)).toHaveAttribute('data-state', 'UNAVAILABLE')
    await expect(page.getByTestId(`historical-fix-caveat-${runId}`)).toContainText('Current board comparison data could not be read')
    await expect(page.locator('[data-testid^="fix-verification-trace-"]')).toBeDisabled()
    await expect(page.locator('[data-testid^="view-verification-trace-"]')).toBeEnabled()
    const lifecycle = await page.evaluate(() => ({
      visibilityState: document.visibilityState, events: (window as any).__historyComparisonEvents
    }))
    expect(lifecycle).toEqual({ visibilityState: 'visible', events: { focus: 0, visibility: 0 } })
    expect(reads).toEqual({ fingerprints: 1 + failedComparisons, snapshots: 2, pendingFingerprints: 0 })
    const unavailableReads = { ...reads }

    // A later explicit refresh owns its real 200; the superseded response above never did.
    recoverComparisons = true
    const history = page.getByTestId('trace-history-panel')
    await history.getByRole('button', { name: 'Refresh', exact: true }).click()
    await expect.poll(() => recoveredComparisons).toBeGreaterThan(0)
    await expect.poll(() => reads.pendingFingerprints).toBe(0)
    await expect(page.getByTestId(`verification-history-model-comparison-${runId}`)).toHaveAttribute('data-state', 'UNCHANGED')
    await expect(history.getByRole('button', { name: 'Refresh', exact: true })).toBeEnabled()
    const evidence = page.getByTestId(`run-evidence-verification-${runId}`)
    if (await evidence.getAttribute('open') === null) await evidence.locator('summary').click()
    await expect(page.locator('[data-testid^="fix-verification-trace-"]')).toBeEnabled()
    await expect(page.locator('[data-testid^="view-verification-trace-"]')).toBeEnabled()
    expect(reads).toEqual({ fingerprints: 1 + failedComparisons + recoveredComparisons,
      snapshots: 2, pendingFingerprints: 0 })
    await testInfo.attach('warm-comparison-invalidation-evidence', {
      contentType: 'application/json', body: Buffer.from(JSON.stringify({
        unavailableReads, reads, failedComparisons, recoveredComparisons, lifecycle, runId
      }))
    })
  } finally {
    releaseOlder()
    await page.unrouteAll({ behavior: 'wait' })
    await publisher.close()
  }
})
