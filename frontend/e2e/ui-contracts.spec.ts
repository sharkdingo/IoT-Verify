import { type APIRequestContext, type Page, type Route } from '@playwright/test'
import { createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'
import { dismissHintTooltips } from './support/tooltips'

/**
 * Covers the routing, session, and accessibility contracts that unit tests can only
 * assert structurally: real hash-history navigation, real `localStorage` session state,
 * real focus movement, and real computed scroll state.
 */

const seedSession = async (page: Page, auth: AuthUser, extra: Record<string, string> = {}) => {
  await page.addInitScript(({ token, user, extraEntries }) => {
    window.localStorage.setItem('iot_verify_token', token)
    window.localStorage.setItem('iot_verify_user', JSON.stringify(user))
    window.localStorage.setItem('locale', 'en')
    for (const [key, value] of Object.entries(extraEntries)) {
      window.localStorage.setItem(key, value)
    }
  }, {
    token: auth.token,
    user: { userId: auth.userId, phone: auth.phone, username: auth.username },
    extraEntries: extra
  })
}

const openBoard = async (page: Page, auth: AuthUser) => {
  await seedSession(page, auth, { iot_verify_theme: 'light' })
  await page.goto('/#/board')
  await expect(page.locator('.iot-board')).toBeVisible({ timeout: 60_000 })
}

const openLayoutTestBoard = async (page: Page, request: APIRequestContext) => {
  // Layout mutations need an isolated account and hydrated panels before interaction.
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'panelUi' })
  const layoutReady = page.waitForResponse(response =>
    response.request().method() === 'GET' && new URL(response.url()).pathname === '/api/board/layout'
  )
  await openBoard(page, auth)
  expect((await layoutReady).ok()).toBe(true)
  await expect(page.getByTestId('control-center')).toHaveClass(/is-expanded/)
  await expect(page.getByTestId('system-inspector')).toHaveClass(/is-expanded/)
}

// Every test here is read-only with respect to account state, so they all share the
// worker-scoped account rather than each registering one (the backend caps registrations
// per hour, and per-test cleanup would delete an account the next test still needs).
const expiredToken = () => {
  const payload = Buffer.from(JSON.stringify({ exp: Math.floor(Date.now() / 1000) - 60 }))
    .toString('base64').replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `header.${payload}.signature`
}

test.describe('routing and session', () => {
  test('sends an anonymous deep link to login and returns to it after signing in', { tag: '@smoke' }, async ({ page, sharedReadOnlyAccount: auth }) => {

    await page.goto('/#/board')
    await expect(page).toHaveURL(/#\/\?mode=login&redirect=%2Fboard|#\/\?mode=login&redirect=\/board/)
    await expect(page.locator('#auth-tab-login')).toBeVisible()

    await page.fill('#login-panel input[autocomplete="username"]', auth.username)
    await page.fill('#login-panel input[type="password"]', 'Pass1234!!')
    await page.click('#login-panel button[type="submit"]')

    await expect(page.locator('.iot-board')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1)
    // The login surface must hand over a clean workspace URL: a lingering ?redirect=
    // used to re-key the route and remount the freshly mounted board.
    expect(new URL(page.url()).hash).toBe('#/board')
  })

  test('routes an unknown path to a 404 page that reports the address and offers a way back', async ({ page }) => {
    await page.goto('/#/no-such-page')
    // The route carries the address that failed, so the page can show the user what it could not find
    // instead of only that something went wrong. Hence no `$` anchor: `#/404?from=/no-such-page`.
    await expect(page).toHaveURL(/#\/404\?from=%2Fno-such-page$|#\/404\?from=\/no-such-page$/)
    await expect(page).toHaveTitle('IoT-Verify · 404')
    await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1)
    await expect(page.getByTestId('not-found-attempted')).toContainText('/no-such-page')

    // Element Plus's `.el-result` is gone: the page was rebuilt on the product's own tokens so it can follow
    // the theme and keep the shared header, which the Element Plus default could do neither of.
    await expect(page.locator('.el-result')).toHaveCount(0)

    await page.getByTestId('not-found-home').click()
    await expect(page).toHaveURL(/#\/$/)
    await expect(page.locator('#landing-title')).toBeVisible()
  })

  test('refuses a private route when the stored token has already expired', async ({ page }) => {
    await page.addInitScript(token => {
      window.localStorage.setItem('iot_verify_token', token)
      window.localStorage.setItem('iot_verify_user', JSON.stringify({
        userId: 1, phone: '13800138000', username: 'expired'
      }))
      window.localStorage.setItem('locale', 'en')
    }, expiredToken())

    await page.goto('/#/board')
    await expect(page.locator('#auth-tab-login')).toBeVisible()
    await expect(page.locator('.iot-board')).toHaveCount(0)
    expect(await page.evaluate(() => window.localStorage.getItem('iot_verify_token'))).toBeNull()
  })

})

test.describe('board accessibility contracts', () => {

  test('keeps focus escapable from a non-modal panel and closes it with Escape', async ({ page, sharedReadOnlyAccount: auth }) => {
    await openBoard(page, auth)

    await page.getByTestId('open-verification-panel').click()
    const panel = page.getByTestId('verification-panel')
    await expect(panel).toBeVisible()

    await page.keyboard.press('Escape')
    await expect(panel).toBeHidden()
    // Focus returns to the control that opened the panel.
    await expect(page.getByTestId('open-verification-panel')).toBeFocused()
  })

  test('retracts wrapped panel hints while preserving an unrelated draft focus', async ({ page, request }) => {
    await openLayoutTestBoard(page, request)
    await page.mouse.move(4, 4)
    const hint = page.locator('.iot-hint-tooltip-popper:not([aria-hidden="true"])')
    for (const panel of ['control-center', 'system-inspector']) {
      const toggle = page.getByTestId(`${panel}-toggle`)
      await toggle.focus()
      await expect(toggle).toBeFocused()
      await expect(hint).toBeVisible()
      await dismissHintTooltips(page)
      await expect(hint).toHaveCount(0)
      await expect(toggle).not.toBeFocused()
    }

    const draft = page.getByTestId('inspector-search-devices')
    await draft.fill('Keep my draft')
    await dismissHintTooltips(page)
    await expect(draft).toBeFocused()
    await expect(draft).toHaveValue('Keep my draft')
  })

  test('drives both side panel tab strips from the keyboard', async ({ page, request }) => {
    await openLayoutTestBoard(page, request)

    const controlTemplates = page.getByTestId('control-tab-templates')
    await controlTemplates.click()
    await expect(controlTemplates).toHaveAttribute('aria-selected', 'true')

    await controlTemplates.press('ArrowRight')
    const controlDevices = page.getByTestId('control-tab-devices')
    await expect(controlDevices).toHaveAttribute('aria-selected', 'true')
    await expect(controlDevices).toBeFocused()
    await expect(page.getByTestId('control-section-devices')).toHaveAttribute('role', 'tabpanel')

    await controlDevices.press('End')
    await expect(page.getByTestId('control-tab-specs')).toHaveAttribute('aria-selected', 'true')

    const inspectorDevices = page.getByTestId('inspector-tab-devices')
    await inspectorDevices.click()
    await inspectorDevices.press('ArrowRight')
    const inspectorRules = page.getByTestId('inspector-tab-rules')
    await expect(inspectorRules).toHaveAttribute('aria-selected', 'true')
    await expect(inspectorRules).toBeFocused()
    await inspectorRules.press('End')
    const inspectorEnvironment = page.getByTestId('inspector-tab-environment')
    await expect(inspectorEnvironment).toHaveAttribute('aria-selected', 'true')
    await expect(inspectorEnvironment).toBeFocused()
    await expect(page.getByTestId('inspector-section-environment')).toHaveAttribute('role', 'tabpanel')
    await inspectorEnvironment.press('ArrowRight')
    await expect(inspectorDevices).toHaveAttribute('aria-selected', 'true')
    await expect(inspectorDevices).toBeFocused()
  })
})

test.describe('board top chrome', () => {
  /**
   * Status surfaces that hang from the nav must start below it and let clicks through. The loading strip was
   * `top-14` (56px) under a 69px nav and took pointer events, so for as long as the snapshot loaded it covered
   * the nav's lower edge and ate clicks on the side-panel and dock headers beneath it. The alerts were
   * `top-16` (64px), also inside the nav. The nav is 3.8125rem below 640px, so both widths are measured.
   */
  const VIEWPORTS = [{ width: 375, height: 812 }]

  const measure = (page: Page, testId: string) => page.getByTestId(testId).evaluate(surface => {
    const nav = document.querySelector('.board-nav-bar')!.getBoundingClientRect()
    const box = surface.getBoundingClientRect()
    const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
    return { gap: Math.round(box.top - nav.bottom), catchesPointer: Boolean(hit && surface.contains(hit)) }
  })

  for (const viewport of VIEWPORTS) {
    test(`keeps the loading strip under the nav and click-through at ${viewport.width}px`, async ({ page, sharedReadOnlyAccount: auth }) => {
      await page.setViewportSize(viewport)
      // Hold the snapshot so the strip stays up long enough to measure, then let it finish.
      let release!: () => Promise<void>
      await page.route(/\/api\/board\/snapshot(\?|$)/, route => {
        release = () => route.continue()
      })
      await openBoard(page, auth)
      await expect(page.getByTestId('board-data-loading')).toBeVisible()

      expect(await measure(page, 'board-data-loading')).toEqual({ gap: 0, catchesPointer: false })
      await release()
      await expect(page.getByTestId('board-data-loading')).toHaveCount(0, { timeout: 60_000 })
    })

    test(`keeps an unavailable run and failed refresh readable with keyboard Retry at ${viewport.width}px`, async ({ page, sharedReadOnlyAccount: auth }) => {
      await page.setViewportSize(viewport)
      const theme = viewport.width === 375 ? 'dark' : 'light'
      await seedSession(page, auth, { iot_verify_theme: theme })
      await page.route('**/api/verify/runs/999999', route => route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ code: 404, message: 'Verification run not found', data: null })
      }))
      await page.goto('/#/board?run=verification:999999')
      const unavailableRun = page.getByTestId('board-deep-link-unavailable')
      await expect(unavailableRun).toBeVisible({ timeout: 60_000 })
      await expect(page.getByTestId('scene-import')).toBeEnabled()
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme)

      const refreshState: { held: Route | null } = { held: null }
      let holdNextRefresh = true
      let unavailable = true
      await page.route(/\/api\/board\/snapshot(\?|$)/, async route => {
        if (holdNextRefresh) {
          holdNextRefresh = false
          refreshState.held = route
          return
        }
        if (unavailable) {
          await route.fulfill({
            status: 500,
            contentType: 'application/json',
            body: JSON.stringify({ code: 500, message: 'Snapshot unavailable', data: null })
          })
          return
        }
        await route.continue()
      })

      const expectSeparatedFromRunAlert = async (upperTestId: string) => {
        await expect.poll(() => page.evaluate(testId => {
          const upper = document.querySelector(`[data-testid="${testId}"]`)!.getBoundingClientRect()
          const lower = document.querySelector('[data-testid="board-deep-link-unavailable"]')!.getBoundingClientRect()
          const nav = document.querySelector('.board-nav-bar')!.getBoundingClientRect()
          return {
            belowNav: upper.top >= nav.bottom,
            separated: upper.bottom < lower.top,
            insideViewport: upper.left >= 0 && lower.left >= 0
              && upper.right <= window.innerWidth && lower.right <= window.innerWidth
              && lower.bottom <= window.innerHeight
          }
        }, upperTestId)).toEqual({ belowNav: true, separated: true, insideViewport: true })
      }

      try {
        // Foreground refresh is a read, so the shared account's Board remains untouched.
        await page.evaluate(() => window.dispatchEvent(new Event('focus')))
        await expect(page.getByTestId('board-data-loading')).toBeVisible()
        await expect(unavailableRun).toBeVisible()
        await expectSeparatedFromRunAlert('board-data-loading')
        await expect.poll(() => refreshState.held !== null).toBe(true)
        const refresh = refreshState.held!
        refreshState.held = null
        await refresh.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ code: 500, message: 'Snapshot unavailable', data: null })
        })

        const loadError = page.getByTestId('board-data-load-error')
        await expect(loadError).toBeVisible()
        await expect(unavailableRun).toBeVisible()
        await expectSeparatedFromRunAlert('board-data-load-error')
        const retry = loadError.getByRole('button', { name: /Retry/ })
        expect(await retry.evaluate(button => {
          const box = button.getBoundingClientRect()
          const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
          return !!hit && button.contains(hit)
        })).toBe(true)
        await retry.focus()
        await expect(retry).toBeFocused()
        unavailable = false
        await page.keyboard.press('Enter')
        await expect(loadError).toHaveCount(0, { timeout: 60_000 })
        await expect(page.getByTestId('scene-import')).toBeEnabled()
        await expect(unavailableRun).toBeVisible()
        await page.getByTestId('dismiss-deep-link-unavailable').click()
        await expect(unavailableRun).toHaveCount(0)
      } finally {
        if (refreshState.held) await refreshState.held.continue()
      }
    })
  }
})
