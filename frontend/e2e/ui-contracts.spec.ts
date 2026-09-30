import { type Page, type Route } from '@playwright/test'
import { expect, test, type AuthUser } from './support/auth'

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

// Every test here is read-only with respect to account state, so they all share the
// worker-scoped account rather than each registering one (the backend caps registrations
// per hour, and per-test cleanup would delete an account the next test still needs).
const expiredToken = () => {
  const payload = Buffer.from(JSON.stringify({ exp: Math.floor(Date.now() / 1000) - 60 }))
    .toString('base64').replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `header.${payload}.signature`
}

test.describe('routing and session', () => {
  test('sends an anonymous deep link to login and returns to it after signing in', async ({ page, sharedReadOnlyAccount: auth }) => {

    await page.goto('/#/board')
    await expect(page).toHaveURL(/#\/\?mode=login&redirect=%2Fboard|#\/\?mode=login&redirect=\/board/)
    await expect(page.locator('#auth-tab-login')).toBeVisible()

    await page.fill('#login-panel input[autocomplete="username"]', auth.username)
    await page.fill('#login-panel input[type="password"]', 'Pass1234!!')
    await page.click('#login-panel button[type="submit"]')

    await expect(page.locator('.iot-board')).toBeVisible({ timeout: 60_000 })
    // The login surface must hand over a clean workspace URL: a lingering ?redirect=
    // used to re-key the route and remount the freshly mounted board.
    expect(new URL(page.url()).hash).toBe('#/board')
  })

  test('rewrites a no-hash deep link and titles each route', async ({ page, sharedReadOnlyAccount: auth }) => {
    await seedSession(page, auth)

    await page.goto('/board')
    await expect(page.locator('.iot-board')).toBeVisible({ timeout: 60_000 })
    expect(new URL(page.url()).hash).toBe('#/board')
    await expect(page).toHaveTitle('IoT-Verify')
  })

  test('routes an unknown path to a 404 page that reports the address and offers a way back', async ({ page }) => {
    await page.goto('/#/no-such-page')
    // The route carries the address that failed, so the page can show the user what it could not find
    // instead of only that something went wrong. Hence no `$` anchor: `#/404?from=/no-such-page`.
    await expect(page).toHaveURL(/#\/404\?from=%2Fno-such-page$|#\/404\?from=\/no-such-page$/)
    await expect(page).toHaveTitle('IoT-Verify · 404')
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

  test('exposes exactly one h1 on each route', async ({ page, sharedReadOnlyAccount: auth }) => {
    // Seed before the first navigation: addInitScript only affects later loads.
    await seedSession(page, auth, { iot_verify_theme: 'light' })

    await page.goto('/#/404')
    // Was `.el-result` visible with **zero** h1s — a page whose main message was not a heading at all, which
    // is the state the rebuild corrected rather than a contract to preserve. One h1 per route, including this
    // one.
    //
    // Deliberately *not* asserting `not-found-attempted` here: this navigates to `/#/404` directly, so there
    // is no failed address to report and `v-if="attemptedPath"` correctly renders nothing. My first version
    // demanded that element and failed — the assertion was wrong, not the page. The 404 test above covers the
    // populated case, where the address arrives as `?from=`.
    await expect(page.locator('#not-found-title')).toBeVisible()
    expect(await page.locator('h1').count()).toBe(1)

    await page.goto('/#/board')
    await expect(page.locator('.iot-board')).toBeVisible({ timeout: 60_000 })
    expect(await page.locator('h1').count()).toBe(1)
    await expect(page.locator('h1 .logo-left')).toBeVisible()
  })
})

test.describe('theme control', () => {
  test('cycles light, dark, and follow-system, persisting only explicit choices', async ({ page, sharedReadOnlyAccount: auth }) => {
    await openBoard(page, auth)

    // Two toggles exist in the nav bar, and only ever one is visible: the compact one on the bar, and a
    // full-size one inside the phone-only overflow group (`.nav-overflow-only`, `display: none` above 420px)
    // that exists because at 320px the assistant and Log Out were unreachable. `.visible` picks whichever the
    // current viewport actually renders, rather than asserting a single-element DOM that is not the contract.
    const toggle = page.locator('.board-nav-bar .theme-toggle:visible')
    const storedTheme = () => page.evaluate(() => window.localStorage.getItem('iot_verify_theme'))

    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')

    await toggle.click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
    expect(await storedTheme()).toBe('dark')

    // Third state: following the OS clears the stored override entirely.
    await toggle.click()
    expect(await storedTheme()).toBeNull()
    await expect(toggle).toHaveAccessibleName(/follow system/i)

    await toggle.click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
    expect(await storedTheme()).toBe('light')
  })

  test('follows the OS preference on a first visit with no stored choice', async ({ page, sharedReadOnlyAccount: auth }) => {
    await page.emulateMedia({ colorScheme: 'dark' })
    await seedSession(page, auth)

    await page.goto('/#/board')
    await expect(page.locator('.iot-board')).toBeVisible({ timeout: 60_000 })
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
    expect(await page.evaluate(() => window.localStorage.getItem('iot_verify_theme'))).toBeNull()
  })
})

test.describe('board accessibility contracts', () => {
  test('exposes run settings as named switches that report their state', async ({ page, sharedReadOnlyAccount: auth }) => {
    await openBoard(page, auth)

    await page.getByTestId('open-verification-panel').click()
    const panel = page.getByTestId('verification-panel')
    await expect(panel).toBeVisible()

    // Non-modal tool panel: it must not claim to be a dialog, because focus is not trapped.
    await expect(panel).toHaveAttribute('role', 'region')
    expect(await panel.getAttribute('aria-modal')).toBeNull()

    // The attack switch is disabled until the board models an attack effect, but it must
    // still expose its role, name, and state rather than being an anonymous <button>.
    const attack = page.getByTestId('verification-attack-toggle')
    await expect(attack).toHaveRole('switch')
    await expect(attack).toHaveAccessibleName(/compromised/i)
    await expect(attack).toHaveAttribute('aria-checked', 'false')
    await expect(attack).toBeDisabled()

    const privacy = page.getByTestId('verification-privacy-toggle')
    await expect(privacy).toHaveRole('switch')
    await expect(privacy).toHaveAccessibleName(/sensitivity-label propagation/i)
    await expect(privacy).toHaveAttribute('aria-checked', 'false')

    await privacy.click()
    await expect(privacy).toHaveAttribute('aria-checked', 'true')
    await privacy.click()
    await expect(privacy).toHaveAttribute('aria-checked', 'false')
  })

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

  test('drives both side panel tab strips from the keyboard', async ({ page, sharedReadOnlyAccount: auth }) => {
    await openBoard(page, auth)

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
  })

  test('locks background scroll while a real modal is open', async ({ page, sharedReadOnlyAccount: auth }) => {
    await openBoard(page, auth)

    const bodyOverflow = () => page.evaluate(() => document.body.style.overflow)
    expect(await bodyOverflow()).not.toBe('hidden')

    await page.getByTestId('control-tab-rules').click()
    await expect(page.getByTestId('control-section-rules')).toBeVisible()
    await page.getByTestId('open-rule-builder').click()
    const dialog = page.locator('[role="dialog"][aria-modal="true"]')
    await expect(dialog.first()).toBeVisible()
    expect(await bodyOverflow()).toBe('hidden')

    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    expect(await bodyOverflow()).not.toBe('hidden')
  })

  test('gives every board control an accessible name', async ({ page, sharedReadOnlyAccount: auth }) => {
    await openBoard(page, auth)

    const unnamed = await page.evaluate(() => {
      const describes = (element: Element) => {
        const id = element.getAttribute('aria-labelledby')
        return id ? id.split(/\s+/).some(part => document.getElementById(part)?.textContent?.trim()) : false
      }
      return [...document.querySelectorAll('button:not([disabled])')]
        .filter(button => {
          const style = getComputedStyle(button)
          if (style.display === 'none' || style.visibility === 'hidden') return false
          if (!button.getClientRects().length) return false
          const text = (button.textContent || '').replace(/\s+/g, ' ').trim()
          const iconOnly = [...button.querySelectorAll('.material-symbols-outlined, .material-icons-round')]
            .map(icon => (icon.textContent || '').trim())
          const visibleText = iconOnly.reduce((acc, icon) => acc.replace(icon, ''), text).trim()
          return !visibleText
            && !button.getAttribute('aria-label')?.trim()
            && !button.getAttribute('title')?.trim()
            && !describes(button)
        })
        .map(button => button.outerHTML.slice(0, 120))
    })

    expect(unnamed, `Unnamed buttons:\n${unnamed.join('\n')}`).toEqual([])
  })
})

test.describe('responsive boundaries', () => {
  /**
   * Complementary media queries must split at the same value. When they were written as
   * `max-width: 1023px` / `min-width: 1024px`, a fractional viewport width — normal on a
   * scaled display — matched neither rule, so the nav showed both layouts' gaps.
   */
  for (const width of [1023, 1024]) {
    test(`shows exactly one nav layout at ${width}px`, async ({ page, sharedReadOnlyAccount: auth }) => {
      await page.setViewportSize({ width, height: 800 })
      await openBoard(page, auth)

      const inlineActions = page.locator('.board-nav-bar .scene-action-btn').first()
      const overflowMenu = page.locator('.board-nav-bar .scene-actions-menu')

      if (width >= 1024) {
        await expect(inlineActions).toBeVisible()
        await expect(overflowMenu).toBeHidden()
      } else {
        await expect(inlineActions).toBeHidden()
        await expect(overflowMenu).toBeVisible()
      }
      // Either way the scene commands stay reachable, never hidden by both rules at once.
      await expect(page.getByTestId('scene-export').or(overflowMenu)).not.toHaveCount(0)
    })
  }

  test('keeps the shared dialog overlay and sm: utilities mutually exclusive at 640px', async ({ page, sharedReadOnlyAccount: auth }) => {
    await page.setViewportSize({ width: 640, height: 900 })
    await openBoard(page, auth)

    // Every dialog is now built on `.iot-dialog-overlay`, whose compact-padding rule ends at 639.98px while
    // Tailwind's `sm:` begins at 640px. At exactly 640 the roomier desktop padding must be the one that
    // applies; a `639px`/`640px` split would leave a fractional viewport matching neither.
    const padding = await page.evaluate(() => {
      const probe = document.createElement('div')
      probe.className = 'iot-dialog-overlay'
      document.body.append(probe)
      const value = getComputedStyle(probe).padding
      probe.remove()
      return value
    })
    expect(padding).toBe('16px')
  })
})

test.describe('board top chrome', () => {
  /**
   * Status surfaces that hang from the nav must start below it and let clicks through. The loading strip was
   * `top-14` (56px) under a 69px nav and took pointer events, so for as long as the snapshot loaded it covered
   * the nav's lower edge and ate clicks on the side-panel and dock headers beneath it. The alerts were
   * `top-16` (64px), also inside the nav. The nav is 3.8125rem below 640px, so both widths are measured.
   */
  const VIEWPORTS = [{ width: 1280, height: 720 }, { width: 375, height: 812 }]

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

    test(`hangs the unavailable-link alert below the nav at ${viewport.width}px`, async ({ page, sharedReadOnlyAccount: auth }) => {
      await page.setViewportSize(viewport)
      await seedSession(page, auth, { iot_verify_theme: 'light' })
      await page.goto('/#/board?run=verification:999999')
      await expect(page.getByTestId('board-deep-link-unavailable')).toBeVisible({ timeout: 60_000 })

      const { gap, catchesPointer } = await measure(page, 'board-deep-link-unavailable')
      // `--board-floating-gap` is clamp(0.75rem, 2vw, 1rem): 16px at 1280, 12px at 375.
      expect(gap).toBe(viewport.width >= 800 ? 16 : 12)
      // Only its dismiss button takes pointer events; the message itself lets clicks through.
      expect(catchesPointer).toBe(false)
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
