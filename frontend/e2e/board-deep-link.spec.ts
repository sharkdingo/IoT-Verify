import path from 'node:path'
import { type APIRequestContext, type Page } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'
import { expectPlaybackControlSpacing } from './support/playback'

/**
 * Verifies the board's URL contract against a real backend: which state is deep-linkable,
 * that refresh and Back/Forward restore the same surface, and that a dead link degrades to
 * the plain board instead of a fabricated empty result.
 *
 * Rules under test: docs/guides/frontend-ui-conventions.md
 */

test.describe.configure({ mode: 'serial' })
test.setTimeout(180_000)

const seedSession = async (page: Page, auth: AuthUser) => {
  await page.addInitScript(({ token, user }) => {
    window.localStorage.setItem('iot_verify_token', token)
    window.localStorage.setItem('iot_verify_user', JSON.stringify(user))
    window.localStorage.setItem('locale', 'en')
    window.localStorage.setItem('iot_verify_theme', 'light')
  }, {
    token: auth.token,
    user: { userId: auth.userId, phone: auth.phone, username: auth.username }
  })
}

const openBoard = async (page: Page, auth: AuthUser, target = '/#/board') => {
  await seedSession(page, auth)
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
    const runId = await seedVerificationRun(page, request, auth)

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
  })

  test('restores a fresh asynchronous verdict and opens its own counterexample after refresh', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoard(page, auth)
    const runId = await seedVerificationRun(page, request, auth, true)
    await expect.poll(() => boardQuery(page)).toContain(`run=verification:${runId}`)
    await page.reload()
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })
    await page.getByTestId('verification-trace-replay').first().click()
    await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 60_000 })
    expect(boardQuery(page)).toContain(`run=verification:${runId}`)
    expect(boardQuery(page)).toContain('trace=')
    const replayURL = page.url()
    await page.reload()
    await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 60_000 })
    expect(page.url()).toBe(replayURL)
    await page.getByTestId('trace-timeline-close').click()
    await expect(page.getByTestId('trace-timeline')).toBeHidden()
    expect(boardQuery(page)).not.toContain('run=')
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

  test('explains a link naming a run this account cannot open', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoard(page, auth, '/#/board?run=verification:999999')

    const banner = page.getByTestId('board-deep-link-unavailable')
    await expect(banner).toBeVisible({ timeout: 60_000 })
    // Persistent and dismissible, and never a fabricated empty verdict.
    await expect(banner).toHaveAttribute('role', 'alert')
    await expect(page.getByTestId('verification-result-dialog')).toHaveCount(0)
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
