import path from 'node:path'
import { type APIRequestContext, type Page } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, seedBrowserSession, expect, test, type AuthUser } from './support/auth'
import { afterHistoryRender, holdHistoryReply } from './support/historyReply'
import { expectPlaybackControlSpacing } from './support/playback'
import type { Trace, VerificationResult } from '../src/types/verify'
import { validateVerificationResult, validateVerificationTraceList } from '../src/utils/runResponse'
import { unwrap } from './support/apiResponse'

/**
 * Verifies the board's URL contract against a real backend: which state is deep-linkable,
 * that refresh and Back/Forward restore the same surface, and that a dead link degrades to
 * the plain board instead of a fabricated empty result.
 *
 * Rules under test: docs/guides/frontend-ui-conventions.md
 */

test.describe.configure({ mode: 'serial' })
test.setTimeout(180_000)

const openBoard = async (page: Page, auth: AuthUser, target = '/#/board') => {
  await seedBrowserSession(page, auth, { locale: 'en', theme: 'light' })
  await page.goto(target)
  await expect(page.locator('.iot-board')).toBeVisible({ timeout: 60_000 })
}

/** The query string of the current hash route, e.g. `run=verification:12`. */
const boardQuery = (page: Page) => new URL(page.url()).hash.split('?')[1] ?? ''

/**
 * Imports a violating scene and runs a verification saved to history, so the
 * tests have a real persisted run id to address.
 */
const seedVerificationRun = async (
  page: Page,
  request: APIRequestContext,
  auth: AuthUser,
  isAsync = false
): Promise<number> => {
  const scenePath = path.resolve(
    process.cwd(), '..', 'docs', 'examples', 'default-climate-conflict-scene.json'
  )
  await page.getByTestId('scene-import-file').setInputFiles(scenePath)
  await page.getByRole('dialog', { name: 'Replace the current scene?' })
    .getByRole('button', { name: 'Replace scene' })
    .click()
  await expect.poll(async () => {
    const response = await request.get(`${apiBaseURL}/api/board/rules`, {
      headers: { Authorization: `Bearer ${auth.token}` }
    })
    return (await response.json())?.data?.length ?? 0
  }, { timeout: 60_000 }).toBeGreaterThan(0)

  const verifyResponse = page.waitForResponse(response =>
    response.request().method() === 'POST'
      && new URL(response.url()).pathname === (isAsync ? '/api/verify/async' : '/api/verify'))
  await page.getByTestId('open-verification-panel').click()
  await page.getByTestId(isAsync ? 'verification-mode-async' : 'verification-mode-sync').click()
  await page.getByTestId('run-verification').click()
  const body = await (await verifyResponse).json()
  await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 90_000 })

  const runId = isAsync ? body?.data?.id : body?.data?.historyPersistence?.runId
  expect(runId, JSON.stringify(body?.data?.historyPersistence)).toBeTruthy()
  return runId as number
}

const replaceBoardWithEmptyScene = async (request: APIRequestContext, auth: AuthUser) => {
  const headers = { Authorization: `Bearer ${auth.token}` }
  const previewResponse = await request.get(`${apiBaseURL}/api/board/replacement-preview`, { headers })
  expect(previewResponse.ok(), await previewResponse.text()).toBeTruthy()
  const preview = await previewResponse.json()
  expect(preview?.data?.impactToken, JSON.stringify(preview)).toBeTruthy()

  const replaceResponse = await request.post(`${apiBaseURL}/api/board/batch`, {
    headers,
    data: {
      impactToken: preview.data.impactToken,
      nodes: [],
      environmentVariables: [],
      rules: [],
      specs: [],
      templateSnapshots: []
    }
  })
  expect(replaceResponse.ok(), await replaceResponse.text()).toBeTruthy()
}

test.describe('board run deep links', () => {
  test('deep-links a verification run and survives refresh, Back, and Forward', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoard(page, auth)
    const checkedResponse = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/verify')
    const runId = await seedVerificationRun(page, request, auth)
    const checkedEnvelope = await (await checkedResponse).json() as { data: VerificationResult }
    const checked = validateVerificationResult(checkedEnvelope.data)
    expect(checked.specResults).toHaveLength(4)
    expect(checked.specResults.filter(row => row.outcome === 'VIOLATED')).toHaveLength(2)
    expect(checked.traces).toHaveLength(2)
    const dialog = page.getByTestId('verification-result-dialog')
    const shortfall = dialog.getByTestId('verification-evidence-shortfall')
    await expect(shortfall, 'all violated specifications produced evidence').toHaveCount(0)

    // Fresh completion owns the same persisted artifact as a history opener.
    await expect.poll(() => boardQuery(page)).toContain(`run=verification:${runId}`)
    await page.reload()
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })
    expect(boardQuery(page)).toContain(`run=verification:${runId}`)

    // Closing the restored result clears its owned URL.
    await page.getByTestId('close-verification-result').click()
    await expect(page.getByTestId('verification-result-dialog')).toBeHidden()
    expect(boardQuery(page)).not.toContain('run=')

    // A pasted link reopens the same run from a cold load.
    await page.goto(`/#/board?run=verification:${runId}`)
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })

    // Refresh keeps it open: the URL, not component state, is the authority.
    await page.reload()
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })
    expect(boardQuery(page)).toContain(`run=verification:${runId}`)

    // Opening from history is a push, so Back closes and Forward reopens.
    await page.goto('/#/board')
    await expect(page.locator('.iot-board')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('verification-result-dialog')).toHaveCount(0)

    await page.getByTestId('open-history-panel').click()
    // Completed runs live in the History Results layer, not the active-task layer.
    await page.getByTestId('history-layer-results').click()
    await page.getByTestId(`open-verification-run-${runId}`).click()
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })
    expect(boardQuery(page)).toContain(`run=verification:${runId}`)

    await page.goBack()
    await expect(page.getByTestId('verification-result-dialog')).toHaveCount(0)
    await page.goForward()
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })

    // Preserve the authenticated native result and exercise the valid response states where parsing falls short.
    const showResult = async (data: VerificationResult) => {
      await dialog.getByTestId('close-verification-result').click()
      const response = { ...checkedEnvelope, data: validateVerificationResult(data) }
      await page.route(/\/api\/verify$/, route => route.fulfill({ json: response }), { times: 1 })
      await page.getByTestId('open-verification-panel').click()
      await page.getByTestId('verification-mode-sync').click()
      await page.getByTestId('run-verification').click()
      await expect(dialog).toBeVisible()
      await expect(dialog.getByTestId('verification-trace-replay')).toHaveCount(data.traces.length)
    }

    for (const count of [1, 0]) {
      await showResult({ ...checked, traces: checked.traces.slice(0, count) })
      await expect(shortfall, `violated verdict with ${count} replayable traces`).toHaveText(
        'Some violations have no replayable trace. Open the result to view the checked expressions and logs.'
      )
    }

    const unresolved = checked.specResults.findIndex(row => row.outcome === 'SATISFIED')
    expect(unresolved).toBeGreaterThanOrEqual(0)
    await showResult({
      ...checked, outcome: 'INCONCLUSIVE', modelComplete: false,
      traces: [{ ...checked.traces[0]!, modelComplete: false }],
      specResults: checked.specResults.map((row, index) => index === unresolved ? { ...row, outcome: 'INCONCLUSIVE' } : row)
    })
    await expect(dialog.getByText('1 parsed counterexamples are available as partial evidence.', { exact: false }))
      .toBeVisible()
    await expect(shortfall, 'mixed inconclusive verdict has its own evidence notice').toHaveCount(0)
  })

  test('closing History cancels the pending saved result and removes only its link', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoard(page, auth, '/#/board?review=retained')
    const runId = await seedVerificationRun(page, request, auth)
    const resultDialog = page.getByTestId('verification-result-dialog')
    await page.getByTestId('close-verification-result').click()
    await expect(resultDialog).toHaveCount(0)
    await expect.poll(() => new URLSearchParams(boardQuery(page)).get('run')).toBeNull()

    const history = page.getByTestId('trace-history-panel')
    await page.getByTestId('open-history-panel').click()
    await page.getByTestId('history-layer-results').click()
    const openResultId = `open-verification-run-${runId}`
    const openResult = page.getByTestId(openResultId)
    const detailPath = `/api/verify/runs/${runId}`
    let reads = 0
    page.on('request', read => {
      if (read.method() === 'GET' && new URL(read.url()).pathname === detailPath) reads += 1
    })

    // Dispatch both controls in one turn so History closes before the route commits.
    await expect(openResult).toBeEnabled()
    const committed = page.waitForEvent('framenavigated', frame => frame === page.mainFrame()
      && new URLSearchParams(new URL(frame.url()).hash.split('?')[1]).get('run') === `verification:${runId}`)
    await page.evaluate(id => {
      document.querySelector<HTMLButtonElement>(`[data-testid="${id}"]`)!.click()
      document.querySelector<HTMLButtonElement>('[data-testid="close-history-panel"]')!.click()
    }, openResultId)
    await committed
    await expect(history).toBeHidden()
    await expect.poll(() => boardQuery(page)).toBe('review=retained')
    expect(reads).toBe(0)

    await page.getByTestId('open-history-panel').click()
    await page.getByTestId('history-layer-results').click()
    const evidence = await holdHistoryReply(page, `${detailPath}/traces`)

    try {
      await Promise.all([openResult.click(), evidence.requested])
      await expect.poll(() => new URLSearchParams(boardQuery(page)).get('run')).toBe(`verification:${runId}`)
      await page.getByTestId('close-history-panel').click()
      await expect(history).toBeHidden()
      await expect.poll(() => boardQuery(page)).toBe('review=retained')
    } finally {
      evidence.release()
    }

    const detail = await evidence.delivered()
    expect(detail.ok(), await detail.text()).toBe(true)
    await expect(resultDialog).toHaveCount(0)
    await page.reload()
    await expect(page.getByTestId('scene-import')).toBeEnabled()
    expect(boardQuery(page)).toBe('review=retained')
    await expect(resultDialog).toHaveCount(0)

    await page.getByTestId('open-history-panel').click()
    await page.getByTestId('history-layer-results').click()
    await openResult.click()
    await expect(resultDialog).toBeVisible({ timeout: 60_000 })
    expect(new URLSearchParams(boardQuery(page)).get('run')).toBe(`verification:${runId}`)
  })

  test('keeps the selected replay when a superseded History response arrives', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoard(page, auth, '/#/board?review=retained')
    const runId = await seedVerificationRun(page, request, auth)
    await page.getByTestId('close-verification-result').click()
    await page.getByTestId('open-history-panel').click()
    await page.getByTestId('history-layer-results').click()
    const evidence = page.getByTestId(`run-evidence-verification-${runId}`)
    await evidence.locator('summary').click()
    const trace = evidence.getByTestId(/^view-verification-trace-/).first()
    const traceId = (await trace.getAttribute('data-testid'))?.split('-').at(-1)
    const openRun = page.getByTestId(`open-verification-run-${runId}`)
    const detailPath = `/api/verify/runs/${runId}`
    const isRunRead = (read: { url(): string; method(): string }) =>
      read.method() === 'GET' && new URL(read.url()).pathname === detailPath
    let reads = 0
    page.on('request', read => { if (isRunRead(read)) reads += 1 })
    const heldEvidence = await holdHistoryReply(page, `${detailPath}/traces`)

    const replay = page.getByTestId('trace-timeline')
    try {
      await Promise.all([heldEvidence.requested, openRun.click()])
      await openRun.click()
      await afterHistoryRender(page)
      expect(reads, 'opening the same History row twice shares its pending read').toBe(1)
      await trace.click()
      await expect(replay).toBeVisible()
      expect(new URLSearchParams(boardQuery(page)).get('trace')).toBe(traceId)
    } finally {
      heldEvidence.release()
    }

    const response = await heldEvidence.delivered()
    expect(response.ok(), await response.text()).toBe(true)
    await expect(replay).toBeVisible()
    await expect(page.getByTestId('verification-result-dialog')).toHaveCount(0)

    await page.getByTestId('trace-timeline-close').click()
    await expect(replay).toBeHidden()
    await expect.poll(() => boardQuery(page)).toBe('review=retained')
    await expect(page.getByTestId('verification-result-dialog')).toHaveCount(0)

    const another = await request.post(`${apiBaseURL}/api/verify`, {
      headers: { Authorization: `Bearer ${auth.token}` },
      data: { attackScenario: { mode: 'NONE' }, enablePrivacy: false }
    })
    expect(another.ok(), await another.text()).toBe(true)
    const nextRunId: number = (await another.json()).data.historyPersistence.runId
    expect(nextRunId).toBeGreaterThan(0)
    expect(nextRunId).not.toBe(runId)
    await page.getByTestId('open-history-panel').click()
    await page.getByTestId('history-layer-results').click()
    const previous = await holdHistoryReply(page, `${detailPath}/traces`)
    const result = page.getByTestId('verification-result-dialog')
    try {
      await Promise.all([previous.requested, openRun.click()])
      await page.getByTestId(`open-verification-run-${nextRunId}`).click()
      await expect(result).toBeVisible()
      await expect.poll(() => new URLSearchParams(boardQuery(page)).get('run')).toBe(`verification:${nextRunId}`)
    } finally {
      previous.release()
    }
    expect((await previous.delivered()).ok()).toBe(true)
    await result.getByTestId('verification-trace-replay').first().click()
    await expect(replay).toBeVisible()
    // Replay uses the displayed result's run ID, not just the address we left in the browser.
    expect(new URLSearchParams(boardQuery(page)).get('run')).toBe(`verification:${nextRunId}`)

    const mismatchedTrace = page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === `/api/verify/traces/${traceId}`)
    await page.goto(`/#/board?review=retained&run=verification:${nextRunId}&trace=${traceId}`)
    const mismatchedResponse = await mismatchedTrace
    expect(mismatchedResponse.ok(), await mismatchedResponse.text()).toBe(true)
    expect((await mismatchedResponse.json()).data.verificationTaskId).toBe(runId)
    await expect(page.getByTestId('board-deep-link-unavailable')).toBeVisible()
    await expect(replay).toBeHidden()
    await expect.poll(() => boardQuery(page)).toBe('review=retained')
  })

  test('restores a fresh asynchronous verdict and opens its own counterexample after refresh', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoard(page, auth)
    const runId = await seedVerificationRun(page, request, auth, true)
    const runAddress = `verification:${runId}`
    const selectedAddress = () => {
      const query = new URLSearchParams(boardQuery(page))
      return [query.get('run'), query.get('trace')]
    }
    await expect.poll(() => boardQuery(page)).toContain(`run=${runAddress}`)
    const traceList = page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === `/api/verify/runs/${runId}/traces`)
    await page.reload()
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })
    const traces = validateVerificationTraceList(await unwrap(await traceList), runId)
    expect(traces).toHaveLength(2)
    const [firstTrace, secondTrace] = traces
    expect(firstTrace.id).not.toBe(secondTrace.id)
    const replayButtons = page.getByTestId('verification-trace-replay')
    await expect(replayButtons).toHaveCount(2)
    await replayButtons.first().click()
    await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 60_000 })
    await expect.poll(selectedAddress).toEqual([runAddress, String(firstTrace.id)])
    const replayURL = page.url()
    await page.reload()
    await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 60_000 })
    expect(page.url()).toBe(replayURL)
    await page.getByTestId('trace-timeline-close').click()
    await expect(page.getByTestId('trace-timeline')).toBeHidden()
    expect(boardQuery(page)).not.toContain('run=')

    await page.goto(`/#/board?run=${runAddress}`)
    await replayButtons.nth(1).click()
    await expect.poll(selectedAddress).toEqual([runAddress, String(secondTrace.id)])
    await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 60_000 })
  })

  for (const receipt of ['confirmed', 'lost', 'already absent'] as const) test(`deleting the last saved verification in another tab closes its replay (${receipt})`, async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoard(page, auth)
    const runId = await seedVerificationRun(page, request, auth)
    await page.getByTestId('close-verification-result').click()
    await page.getByTestId('open-history-panel').click()
    await page.getByTestId('history-layer-results').click()
    const deleteRun = page.getByTestId(`delete-verification-run-${runId}`)
    await expect(deleteRun).toBeVisible()

    const observer = await page.context().newPage()
    try {
      await openBoard(observer, auth, `/#/board?review=retained&run=verification:${runId}`)
      await expect(observer.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 30_000 })
      await observer.getByTestId('verification-trace-replay').first().click()
      const replay = observer.getByTestId('trace-timeline')
      await expect(replay).toBeVisible()
      await expect.poll(() => new URLSearchParams(boardQuery(observer)).get('trace')).toMatch(/^\d+$/)
      expect(await observer.evaluate(() => document.visibilityState)).toBe('visible')

      let snapshotReads = 0
      observer.on('request', read => {
        if (read.method() === 'GET' && new URL(read.url()).pathname === '/api/board/snapshot') {
          snapshotReads += 1
        }
      })
      const runPath = `/api/verify/runs/${runId}`
      let writes = 0
      let checks = 0
      page.on('request', outgoing => {
        if (new URL(outgoing.url()).pathname !== runPath) return
        if (outgoing.method() === 'DELETE') writes += 1
        if (outgoing.method() === 'GET') checks += 1
      })
      if (receipt === 'lost') await page.route(`**${runPath}`, async route => {
        if (route.request().method() !== 'DELETE') return route.continue()
        const committed = await route.fetch()
        expect(committed.ok(), await committed.text()).toBe(true)
        await route.abort()
      })
      await deleteRun.click()
      if (receipt === 'already absent') {
        const otherDelete = await request.delete(`${apiBaseURL}${runPath}`, {
          headers: { Authorization: `Bearer ${auth.token}` }
        })
        expect(otherDelete.ok(), await otherDelete.text()).toBe(true)
      }
      const deletionSettled = page.waitForResponse(response =>
        response.request().method() === (receipt === 'confirmed' ? 'DELETE' : 'GET')
          && new URL(response.url()).pathname === runPath)
      await page.getByRole('dialog', { name: 'Delete Verification Result' })
        .getByRole('button', { name: 'Delete', exact: true }).click()
      expect((await deletionSettled).status()).toBe(receipt === 'confirmed' ? 200 : 404)
      await expect(deleteRun).toHaveCount(0)
      if (receipt !== 'confirmed') {
        await expect(page.getByText('Deletion is unconfirmed. This saved result is no longer on the server.')).toBeVisible()
      }

      await expect(replay).toBeHidden({ timeout: 15_000 })
      await expect.poll(() => boardQuery(observer)).toBe('review=retained')
      expect(snapshotReads).toBe(0)
      expect(writes).toBe(1)
      expect(checks).toBe(receipt === 'confirmed' ? 0 : 1)
    } finally {
      await observer.close()
    }
  })

  test('degrades malformed deep links to the plain board without inventing a result', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)

    for (const query of [
      'run=verification:abc',
      'run=nonsense:1',
      'run=verification:0',
      'trace=5',
      'run=exploration:3&trace=9',
      'run=simulation:2&finding=4'
    ]) {
      await openBoard(page, auth, `/#/board?${query}`)
      await expect(page.getByTestId('verification-result-dialog')).toHaveCount(0)
      // Dead params are stripped so a refresh stays clean.
      await expect.poll(() => boardQuery(page), { message: query }).not.toContain('run=')
      await expect.poll(() => boardQuery(page), { message: query }).not.toContain('trace=')
      await expect.poll(() => boardQuery(page), { message: query }).not.toContain('finding=')
    }
  })

  test('keeps the new account result when the old account receives a late 404', async ({ page, request }) => {
    const previous = await createAuthenticatedUser(request)
    const current = await createAuthenticatedUser(request)
    const other = await page.context().newPage()
    let runId: number
    try {
      await openBoard(other, current)
      runId = await seedVerificationRun(other, request, current)
    } finally {
      await other.close()
    }
    const held = await holdHistoryReply(page, `/api/verify/runs/${runId}`)
    const query = `review=retained&run=verification:${runId}`
    const result = page.getByTestId('verification-result-dialog')
    const banner = page.getByTestId('board-deep-link-unavailable')
    try {
      await openBoard(page, previous, `/#/board?${query}`)
      await held.requested
      await page.evaluate(({ token, userId, phone, username }) => {
        const user = { userId, phone, username }
        localStorage.setItem('iot_verify_token', token)
        localStorage.setItem('iot_verify_user', JSON.stringify(user))
        const value = JSON.stringify({ token, user, updatedAt: Date.now() })
        localStorage.setItem('iot_verify_auth_sync', value)
        window.dispatchEvent(new StorageEvent('storage', {
          key: 'iot_verify_auth_sync', newValue: value, storageArea: localStorage
        }))
      }, current)
      await expect(result).toBeVisible({ timeout: 30_000 })
    } finally {
      held.release()
    }
    expect((await held.delivered()).status()).toBe(404)
    await expect(result).toBeVisible()
    await expect(banner).toHaveCount(0)
    expect(boardQuery(page)).toBe(query)

    await page.getByTestId('close-verification-result').click()
    await expect.poll(() => boardQuery(page)).toBe('review=retained')
    await page.goto('/#/board?run=verification:999999')
    await expect(banner).toBeVisible({ timeout: 60_000 })
    // Persistent and dismissible, and never a fabricated empty verdict.
    await expect(banner).toHaveAttribute('role', 'alert')
    await expect(result).toHaveCount(0)
    expect(boardQuery(page)).not.toContain('run=')

    await page.getByTestId('dismiss-deep-link-unavailable').click()
    await expect(banner).toHaveCount(0)
    // The board stays fully usable after a dead link.
    await page.getByTestId('open-verification-panel').click()
    await expect(page.getByTestId('verification-panel')).toBeVisible()
  })

  test('deep-links a counterexample trace under its owning run', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoard(page, auth)
    const runId = await seedVerificationRun(page, request, auth)

    // The trace id comes from the run's own persisted counterexamples, which is what a
    // shared link would reference.
    const tracesResponse = await request.get(`${apiBaseURL}/api/verify/runs/${runId}/traces`, {
      headers: { Authorization: `Bearer ${auth.token}` }
    })
    const traces = (await tracesResponse.json())?.data ?? []
    const traceId = traces[0]?.id
    expect(traceId, JSON.stringify(traces).slice(0, 200)).toBeTruthy()

    // The current board is deliberately replaced after the run completes. Replay must use the
    // persisted run snapshot, then return to this new empty board when the user closes it.
    await replaceBoardWithEmptyScene(request, auth)

    await page.goto(`/#/board?run=verification:${runId}&trace=${traceId}`)
    await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 90_000 })
    expect(boardQuery(page)).toContain(`trace=${traceId}`)
    await expect(page.locator('[data-node-id="temperature_1"]')).toBeVisible()
    await expect(page.locator('[data-node-id="ac_1"]')).toBeVisible()
    await expect(page.getByTestId('canvas-board')).toContainText('Living-room Temperature Sensor')

    const replayURL = page.url()
    await page.getByTestId('open-account-center').click()
    await expect(page.getByTestId('account-page')).toBeVisible()
    const accountURL = page.url()
    const accountHash = new URL(accountURL).hash
    expect(new URLSearchParams(accountHash.slice(accountHash.indexOf('?') + 1)).get('returnTo'))
      .toBe(`/board?run=verification:${runId}&trace=${traceId}`)
    await page.goBack()
    await expect(page).toHaveURL(replayURL)
    await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 90_000 })
    await page.goForward()
    await expect(page).toHaveURL(accountURL)
    await expect(page.getByTestId('account-page')).toBeVisible()
    await page.getByTestId('account-back').click()
    await expect(page).toHaveURL(replayURL)
    await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 90_000 })

    // Refresh restores the same replay, not just the run.
    await page.reload()
    await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 90_000 })
    expect(boardQuery(page)).toContain(`run=verification:${runId}`)
    expect(boardQuery(page)).toContain(`trace=${traceId}`)

    const timeline = page.getByTestId('trace-timeline')
    // A recheck belongs to the visit that started it, even when the exact URL opens again.
    const recheck = await holdHistoryReply(page, `/api/verify/traces/${traceId}`, {
      status: 404, json: { code: 404, message: 'Verification trace not found' }
    })
    try {
      await page.evaluate(userId => {
        const channel = new BroadcastChannel('iot-verify-board-invalidation-v1')
        channel.postMessage({
          type: 'RUN_HISTORY_INVALIDATED', userId, reason: 'http-mutation', issuedAt: Date.now()
        })
        channel.close()
      }, auth.userId)
      await recheck.requested
      await page.getByTestId('trace-timeline-close').click()
      await expect(timeline).toBeHidden()
      await expect.poll(() => boardQuery(page)).toBe('')
      await page.goto(replayURL)
      await expect(timeline).toBeVisible()
    } finally {
      recheck.release()
    }
    expect((await recheck.delivered()).status()).toBe(404)
    await expect(timeline).toBeVisible()
    await expect(page).toHaveURL(replayURL)

    await expectPlaybackControlSpacing(page, 'trace-timeline')
    const beforeHideHeight = await page.locator('.canvas-container').evaluate(element => element.clientHeight)
    const terminalHeight = await timeline.evaluate(element => element.clientHeight)
    const canvasTransform = await page.locator('.canvas-inner').evaluate(element => getComputedStyle(element).transform)
    await page.getByTestId('trace-timeline-step-input').fill('1')
    await page.getByTestId('trace-timeline-step-input').press('Enter')
    await expect(page.getByTestId('trace-timeline-state-0')).toHaveCSS('width', '24px')
    await expect.poll(() => page.getByTestId('trace-timeline-jump-violation').evaluate(button => {
      const box = button.getBoundingClientRect()
      const hit = document.elementFromPoint(box.right - 1, box.top + box.height / 2)
      return hit === button || button.contains(hit)
    }), { message: 'The violation locator must receive pointer input.' }).toBe(true)
    await page.getByTestId('trace-timeline-changes-tab').click()
    await expect(timeline.getByTestId('playback-changes-panel')).toBeVisible()
    await expect(page.getByTestId('trace-timeline-play')).toBeVisible()
    await expect(page.getByTestId('playback-toolbar').getByTestId('trace-timeline-play')).toBeVisible()
    await expect(timeline.locator('.playback-terminal__transport')).toHaveCount(0)
    const contentHeight = await timeline.getByRole('tabpanel').filter({ visible: true }).evaluate(element => element.clientHeight)
    expect(contentHeight).toBeGreaterThanOrEqual(180)
    expect(await timeline.evaluate(element => element.clientHeight)).toBe(terminalHeight)
    expect(await page.locator('.canvas-container').evaluate(element => element.clientHeight)).toBe(beforeHideHeight)
    expect(await page.locator('.canvas-inner').evaluate(element => getComputedStyle(element).transform)).toBe(canvasTransform)
    const initialState = await timeline.getAttribute('data-selected-state-index')
    await page.getByTestId('trace-timeline-details-tab').click()
    await expect(page.getByTestId('playback-state-details')).toBeVisible()
    await expect(page.getByTestId('playback-state-devices')).toContainText('Living-room Temperature Sensor')
    await expect(page.getByTestId('counterexample-trace-help')).toHaveCSS('width', '20px')
    await expect(timeline).toHaveAttribute('data-selected-state-index', initialState!)
    expect(await timeline.evaluate(element => element.clientHeight)).toBe(terminalHeight)
    expect(await page.locator('.canvas-container').evaluate(element => element.clientHeight)).toBe(beforeHideHeight)
    expect(await page.locator('.canvas-inner').evaluate(element => getComputedStyle(element).transform)).toBe(canvasTransform)
    const originalViewport = page.viewportSize()!
    await page.setViewportSize({ width: 375, height: 812 })
    await expect.poll(() => timeline.getByRole('tablist').evaluate(list => {
      const frame = list.getBoundingClientRect()
      return [...list.querySelectorAll('[role="tab"][aria-selected="true"]')].every(tab => {
        const box = tab.getBoundingClientRect()
        return box.left >= frame.left && box.right <= frame.right
      })
    }), { message: 'The terminal must keep the selected tab visible at a standard phone width.' }).toBe(true)
    await page.setViewportSize({ width: 320, height: 812 })
    await expect.poll(() => page.getByTestId('trace-timeline-details-tab').evaluate(tab => {
      const box = tab.getBoundingClientRect()
      const frame = tab.parentElement!.getBoundingClientRect()
      return box.left >= frame.left && box.right <= frame.right
    }), { message: 'Resizing must reveal the selected terminal tab without requiring a second click.' }).toBe(true)
    expect(await timeline.locator('.playback-terminal__header').evaluate(element => element.getBoundingClientRect().height)).toBe(44)
    await expectPlaybackControlSpacing(page, 'trace-timeline')
    await page.setViewportSize(originalViewport)
    await page.getByTestId('trace-timeline-changes-tab').click()
    await page.getByTestId('trace-timeline-play').click()
    await page.getByTestId('trace-timeline-hide-panel').click()
    await expect(timeline).toBeHidden()
    await expect(page.getByTestId('playback-panel-toggle')).toBeFocused()
    await expect(page.getByTestId('trace-timeline-close')).toBeVisible()
    await expect.poll(async () => Number(await timeline.getAttribute('data-selected-state-index'))).toBeGreaterThan(0)
    expect(await page.locator('.canvas-container').evaluate(element => element.clientHeight)).toBeGreaterThan(beforeHideHeight)
    expect(boardQuery(page)).toContain(`trace=${traceId}`)
    await expect(page.locator('[data-node-id="ac_1"]')).toBeDisabled()
    // Playback controls remain available above the canvas while all terminal content is hidden.
    await expect(page.getByTestId('trace-timeline-play')).toBeVisible()
    await page.getByTestId('trace-timeline-step-input').fill('1')
    await page.getByTestId('trace-timeline-step-input').press('Enter')
    await expect(timeline).toHaveAttribute('data-selected-state-index', '0')
    await page.getByTestId('playback-toolbar').getByRole('button', { name: 'Next state', exact: true }).click()
    await expect(timeline).toHaveAttribute('data-selected-state-index', '1')
    await expect(timeline).toBeHidden()
    await page.getByTestId('playback-panel-toggle').click()
    await expect(timeline).toBeVisible()
    await expect(page.getByTestId('trace-timeline-changes-tab')).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByTestId('trace-timeline-changes-tab')).toBeFocused()
    if (await page.getByTestId('trace-timeline-play').getAttribute('aria-label') === 'Pause') {
      await page.getByTestId('trace-timeline-play').click()
    }
    const selected = await timeline.getAttribute('data-selected-state-index')
    await page.getByTestId('trace-timeline-hide-panel').click()
    await page.getByTestId('playback-panel-toggle').click()
    await expect(timeline).toHaveAttribute('data-selected-state-index', selected!)
    await page.getByTestId('trace-timeline-run-tab').click()
    await expectPlaybackControlSpacing(page, 'trace-timeline')
    await expect(page.getByTestId('trace-timeline-track')).toBeVisible()
    await expect(page.getByTestId(`trace-timeline-state-${selected}`)).toHaveAttribute('aria-current', 'step')

    // Closing the replay leaves the artifact entirely, so the whole deep link goes with it.
    // Leaving `run=` behind would let the sync reopen the result surface the user just left.
    await page.getByTestId('trace-timeline-close').click()
    await expect(page.getByTestId('trace-timeline')).toBeHidden()
    expect(boardQuery(page)).not.toContain('trace=')
    expect(boardQuery(page)).not.toContain('run=')
    await expect(page.getByTestId('verification-result-dialog')).toHaveCount(0)
    await expect(page.locator('[data-node-id="temperature_1"]')).toHaveCount(0)
    await expect(page.locator('[data-node-id="ac_1"]')).toHaveCount(0)
  })

  test('switching and exiting playback retires the previous scene and autoplay', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoard(page, auth, '/#/board?review=retained')
    const runId = await seedVerificationRun(page, request, auth)
    await page.getByTestId('close-verification-result').click()
    const headers = { Authorization: `Bearer ${auth.token}` }
    const traces = await request.get(`${apiBaseURL}/api/verify/runs/${runId}/traces`, { headers })
    expect(traces.ok(), await traces.text()).toBe(true)
    const trace = ((await traces.json()).data as Trace[])
      .find(candidate => candidate.violatedSpec?.templateId === '4')
    if (!trace) throw new Error('Expected the saved immediate-response counterexample')
    const violationIndex = trace.states.length - 1
    expect(violationIndex).toBeGreaterThan(0)

    await replaceBoardWithEmptyScene(request, auth)
    const addLight = async (id: string) => {
      const response = await request.post(`${apiBaseURL}/api/board/nodes`, {
        headers, data: { devices: [{ id, label: id, templateName: 'Light', position: { x: 200, y: 200 },
          width: 176, height: 128, state: 'off', variables: [], privacies: [] }], environmentVariablePatches: [] }
      })
      expect(response.ok(), await response.text()).toBe(true)
    }
    await addLight('simulation_light')
    // Sharing a subject ID exercises ring cleanup when switching playback.
    await addLight('ac_1')
    const simulationResponse = await request.post(`${apiBaseURL}/api/simulate/traces`, {
      headers, data: { attackScenario: { mode: 'NONE' }, enablePrivacy: false, steps: Math.max(3, violationIndex) }
    })
    expect(simulationResponse.ok(), await simulationResponse.text()).toBe(true)
    const simulation = (await simulationResponse.json()).data
    await addLight('live_light')
    await page.reload()
    const zoom = page.getByTestId('canvas-map-zoom-input')
    await expect(page.locator('[data-node-id="live_light"]')).toBeEnabled()
    await zoom.fill('80')
    await expect(zoom).toHaveValue('80')
    const canvas = page.locator('.canvas-inner')
    const liveTransform = await canvas.evaluate(element => getComputedStyle(element).transform)
    await page.clock.install()

    await page.goto(`/#/board?review=retained&run=verification:${runId}&trace=${trace.id}`)
    const formal = page.getByTestId('trace-timeline-play')
    const frozenTraceNode = page.locator('[data-node-id="temperature_1"]')
    await expect(frozenTraceNode).toBeDisabled()
    const rings = page.locator('.device-at-violation')
    const badge = page.getByTestId('playback-violation-badge')
    const formalTimeline = page.getByTestId('trace-timeline')
    await expect(formalTimeline).toHaveAttribute('data-selected-state-index', String(violationIndex))
    await expect(frozenTraceNode).toHaveClass(/device-at-violation/)
    await expect(page.locator('[data-node-id="ac_1"]')).toHaveClass(/device-at-violation/)
    await expect(badge).toHaveText('Violation')
    await page.getByTestId('trace-timeline-state-0').click()
    await expect(formalTimeline).toHaveAttribute('data-selected-state-index', '0')
    await expect(rings, 'moving away clears the formal violation rings').toHaveCount(0)
    await expect(badge).toHaveCount(0)
    await page.getByTestId('trace-timeline-jump-violation').click()
    await expect(formalTimeline).toHaveAttribute('data-selected-state-index', String(violationIndex))
    await expect(rings).toHaveCount(2)
    await expect(badge).toHaveText('Violation')
    await formal.click()
    await expect(formal).toHaveAccessibleName('Pause')

    await page.goto(`/#/board?review=retained&run=simulation:${simulation.id}`)
    const timeline = page.getByTestId('simulation-timeline')
    await expect(timeline).toBeVisible()
    await expect(frozenTraceNode).toHaveCount(0)
    await expect(page.locator('[data-node-id="simulation_light"]')).toBeDisabled()
    await expect(page.locator('[data-node-id="live_light"]')).toHaveCount(0)
    await page.clock.runFor(1600)
    await expect(timeline).toHaveAttribute('data-selected-state-index', '0')
    await expect(page.getByTestId('simulation-timeline-play')).toHaveAccessibleName('Play')
    await page.getByTestId(`simulation-timeline-state-${violationIndex}`).click()
    await expect(timeline).toHaveAttribute('data-selected-state-index', String(violationIndex))
    await expect(page.getByTestId('playback-changes-panel')).toBeVisible()
    await expect(badge, 'simulation cannot inherit the prior result badge').toHaveCount(0)
    await expect(rings, 'simulation cannot inherit the prior result rings').toHaveCount(0)

    await page.goBack()
    await expect(frozenTraceNode).toBeDisabled()
    await expect(formal).toHaveAccessibleName('Play')
    await expect(badge).toHaveText('Violation')
    await page.goForward()
    await expect(timeline).toBeVisible()
    await page.getByTestId('simulation-timeline-play').click()
    await expect(page.getByTestId('simulation-timeline-play')).toHaveAccessibleName('Pause')
    await page.getByTestId('simulation-timeline-hide-panel').click()
    await page.getByTestId('simulation-timeline-close').click()

    await expect(page.getByTestId('playback-toolbar')).toHaveCount(0)
    await expect(rings).toHaveCount(0)
    await expect(badge).toHaveCount(0)
    await expect(page.locator('[data-node-id="live_light"]')).toBeEnabled()
    await expect(page.getByTestId('scene-import')).toBeEnabled()
    await expect(canvas).toHaveCSS('transform', liveTransform)
    await expect.poll(() => boardQuery(page)).toBe('review=retained')
  })

  test('restores a deep-linked run at a narrow width and from the keyboard', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await page.setViewportSize({ width: 800, height: 720 })
    await openBoard(page, auth)
    const runId = await seedVerificationRun(page, request, auth)

    await page.goto(`/#/board?run=verification:${runId}`)
    const dialog = page.getByTestId('verification-result-dialog')
    await expect(dialog).toBeVisible({ timeout: 60_000 })
    // A deep-linked modal must be a real modal at every width (the testid is on the
    // overlay; the focus-trapped surface inside it carries the dialog semantics).
    await expect(dialog.locator('[role="dialog"]')).toHaveAttribute('aria-modal', 'true')

    // Escape is enough to leave, and it clears the deep link rather than stranding the URL.
    await page.keyboard.press('Escape')
    await expect(dialog).toBeHidden()
    expect(boardQuery(page)).not.toContain('run=')
  })
})
