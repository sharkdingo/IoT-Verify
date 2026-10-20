import { type Locator, type Page } from '@playwright/test'
import {
  apiBaseURL,
  createAuthenticatedUser,
  createTestAccountCredentials,
  expect,
  test,
  trackTestAccount,
  type AuthUser
} from './support/auth'
import { clickUnderTooltip } from './support/tooltips'

type ThemeMode = 'light' | 'dark'

const parseRgb = (color: string) => {
  const rgbMatch = color.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/)
  if (rgbMatch) {
    return {
      r: Number(rgbMatch[1]),
      g: Number(rgbMatch[2]),
      b: Number(rgbMatch[3])
    }
  }

  const srgbMatch = color.match(/color\(srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)/)
  if (srgbMatch) {
    return {
      r: Number(srgbMatch[1]) * 255,
      g: Number(srgbMatch[2]) * 255,
      b: Number(srgbMatch[3]) * 255
    }
  }

  {
    throw new Error(`Unsupported color format: ${color}`)
  }
}

const brightnessOf = (color: string) => {
  const { r, g, b } = parseRgb(color)
  return (r * 299 + g * 587 + b * 114) / 1000
}

const computedColorOf = (
  locator: Locator,
  property: 'backgroundColor' | 'color'
) => locator.evaluate((element, cssProperty) => {
  const value = window.getComputedStyle(element)[cssProperty]
  const canvas = document.createElement('canvas')
  canvas.width = 1
  canvas.height = 1
  const context = canvas.getContext('2d', { willReadFrequently: true })
  if (!context) throw new Error('Canvas color sampling is unavailable')
  context.clearRect(0, 0, 1, 1)
  context.fillStyle = value
  context.fillRect(0, 0, 1, 1)
  const [r, g, b] = context.getImageData(0, 0, 1, 1).data
  return `rgb(${r}, ${g}, ${b})`
}, property)

const backgroundOf = (locator: Locator) => computedColorOf(locator, 'backgroundColor')

const expectDarkSurface = async (locator: Locator) => {
  await expect(locator).toBeVisible()
  expect(brightnessOf(await backgroundOf(locator))).toBeLessThan(90)
}

const expectLightSurface = async (locator: Locator) => {
  await expect(locator).toBeVisible()
  expect(brightnessOf(await backgroundOf(locator))).toBeGreaterThan(210)
}

const openPublicRoute = async (page: Page, route: string, theme: ThemeMode = 'light') => {
  await page.addInitScript((mode) => {
    window.localStorage.removeItem('token')
    window.localStorage.removeItem('user')
    window.localStorage.removeItem('iot_verify_token')
    window.localStorage.removeItem('iot_verify_user')
    window.localStorage.setItem('iot_verify_theme', mode)
  }, theme)

  await page.goto(route)
  await expect(page.locator('.public-header')).toBeVisible({ timeout: 60_000 })
}

const expectAuthHeaderActionsOnRight = async (page: Page) => {
  const actions = page.locator('.public-header__actions')
  await expect(actions).toBeVisible()

  const viewport = page.viewportSize()
  const box = await actions.boundingBox()
  expect(viewport).not.toBeNull()
  expect(box).not.toBeNull()

  if (!viewport || !box) return
  expect(box.x).toBeGreaterThan(viewport.width * 0.62)
  expect(viewport.width - (box.x + box.width)).toBeLessThan(96)
}

const openWorkspace = async (page: Page, auth: AuthUser) => {
  await page.addInitScript(({ token, user }) => {
    window.localStorage.setItem('iot_verify_token', token)
    window.localStorage.setItem('iot_verify_user', JSON.stringify(user))
    window.localStorage.setItem('iot_verify_theme', 'dark')
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
  await expect(page.locator('.iot-board')).toBeVisible({ timeout: 60_000 })
}

test.describe('public theme and layout', () => {
  test('keeps session controls reachable and focuses Cancel at narrow workspace widths', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openWorkspace(page, auth)
    for (const width of [320, 390, 414, 453, 499, 500, 639, 640, 720]) {
      await page.setViewportSize({ width, height: 844 })
      const menu = page.locator('details.scene-actions-menu')
      const opener = width < 640 ? menu.locator('summary') : page.getByTestId('open-account-center')
      await expect(opener).toBeInViewport()
      const box = await opener.boundingBox()
      expect(box!.x).toBeGreaterThanOrEqual(0)
      expect(box!.x + box!.width).toBeLessThanOrEqual(width)
      expect(box!.width).toBeGreaterThanOrEqual(44)
      expect(box!.height).toBeGreaterThanOrEqual(44)
      if (width < 640) {
        await opener.click()
        await expect(menu.getByRole('group', { name: 'Appearance and account' })).toBeVisible()
        await expect(page.getByTestId('nav-overflow-account')).toBeInViewport()
        await page.getByTestId('nav-overflow-account').click()
      } else {
        await opener.click()
      }
      await expect(page.getByTestId('account-page')).toBeVisible()
      const logout = page.getByTestId('account-logout')
      await logout.click()
      const dialog = page.getByRole('dialog', { name: /退出登录|Log Out/ })
      const cancel = dialog.getByRole('button', { name: /取消|Cancel/, exact: true })
      await expect(dialog.locator('.iot-dialog__title')).toHaveCSS('font-size', '16px')
      await expect(cancel).toHaveCSS('font-size', '14px')
      await expect(cancel).toHaveCSS('font-weight', '600')
      await expect.poll(() => cancel.evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44)
      await expect(cancel).toBeFocused()
      await cancel.press('Escape')
      await expect(dialog).toBeHidden()
      await expect(logout).toBeFocused()
      await page.getByTestId('account-back').click()
      await expect(page.getByTestId('board-root')).toBeVisible()
    }
  })

  test('register validation blocks invalid input before any network request', async ({ page }) => {
    await openPublicRoute(page, '/#/?mode=register', 'light')

    let registerCalls = 0
    await page.route('**/api/auth/register', async (route) => {
      registerCalls += 1
      await route.abort()
    })

    await page.locator('.auth-submit').click()

    await expect(page.locator('.auth-form small')).toHaveCount(4)
    expect(registerCalls).toBe(0)
  })

  test('user can register and reach the workspace through the UI', async ({ page, request }) => {
    const credentials = createTestAccountCredentials({ usernamePrefix: 'ui' })
    const { phone, username, password } = credentials
    trackTestAccount(request, credentials)

    await openPublicRoute(page, '/#/?mode=register', 'light')
    const registerInputs = page.locator('.auth-form input')
    await registerInputs.nth(0).fill(phone)
    await registerInputs.nth(1).fill(username)
    await registerInputs.nth(2).fill(password)
    await registerInputs.nth(3).fill(password)
    await page.locator('.auth-submit').click()

    await expect(page.locator('.iot-board')).toBeVisible({ timeout: 60_000 })
    const token = await page.evaluate(() => window.localStorage.getItem('iot_verify_token'))
    expect(token).toBeTruthy()
  })

  test('landing keeps only the meaningful public controls', async ({ page }) => {
    await openPublicRoute(page, '/', 'dark')

    await expect(page.locator('.public-header')).toBeVisible()
    await expect(page.locator('.language-toggle')).toHaveCount(1)
    await expect(page.locator('.theme-toggle')).toHaveCount(1)
    await expect(page.locator('.global-chat-wrapper')).toHaveCount(0)
    await expect(page.locator('.hero-title')).toBeVisible()
    await expect(page.locator('.auth-panel')).toBeHidden()
  })

  test('landing login state follows the workspace light theme', async ({ page }) => {
    await openPublicRoute(page, '/#/?mode=login', 'light')

    await expectAuthHeaderActionsOnRight(page)
    await expect(page.locator('#login-title')).toBeVisible()
    await expect(page.locator('.hero-title')).toHaveCount(0)
    await expect(page.locator('.auth-panel')).toBeVisible()
    await expectLightSurface(page.locator('.auth-panel'))
    await expect(page.locator('.global-chat-wrapper')).toHaveCount(0)
  })

  test('login dark theme keeps the integrated auth card readable', async ({ page }) => {
    await openPublicRoute(page, '/#/?mode=login', 'dark')

    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
    await expectAuthHeaderActionsOnRight(page)
    await expectDarkSurface(page.locator('.auth-panel'))
    await expect(page.locator('.auth-form input').first()).toBeVisible()
  })

  test('login mobile layout keeps header controls and form usable', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await openPublicRoute(page, '/#/?mode=login', 'dark')

    const actions = page.locator('.public-header__actions')
    const formPanel = page.locator('.auth-panel')
    await expect(actions).toBeVisible()
    await expect(formPanel).toBeVisible()
    await expect(actions.getByRole('button')).toHaveCount(2)
    await expect(actions.locator('.theme-toggle')).toBeVisible()
    await expect(actions.locator('.language-toggle')).toBeVisible()

    const actionsBox = await actions.boundingBox()
    const formBox = await formPanel.boundingBox()
    expect(actionsBox).not.toBeNull()
    expect(formBox).not.toBeNull()

    if (!actionsBox || !formBox) return
    expect(actionsBox.x).toBeGreaterThan(190)
    expect(actionsBox.x + actionsBox.width).toBeLessThanOrEqual(376)
    const headerBox = await page.locator('.public-header').boundingBox()
    expect(headerBox).not.toBeNull()
    if (!headerBox) return
    expect(formBox.y).toBeGreaterThan(headerBox.y + headerBox.height)
    expect(formBox.y + formBox.height).toBeLessThanOrEqual(844)
  })

  test('register dark theme uses the same auth layout system', async ({ page }) => {
    await openPublicRoute(page, '/#/?mode=register', 'dark')

    await expectAuthHeaderActionsOnRight(page)
    await expectDarkSurface(page.locator('.auth-panel'))
    await expect(page.locator('.auth-form input')).toHaveCount(4)
  })

  test('workspace dark theme keeps floating panel surfaces coherent', async ({ page, request }) => {
    test.setTimeout(120_000)
    const auth = await createAuthenticatedUser(request)
    const addNodeResponse = await request.post(`${apiBaseURL}/api/board/nodes`, {
      headers: { Authorization: `Bearer ${auth.token}` },
      data: {
        devices: [{
          id: 'dark_theme_thermostat',
          templateName: 'Thermostat',
          label: 'Dark Theme Thermostat',
          position: { x: 920, y: 420 },
          state: 'auto;auto',
          width: 220,
          height: 180
        }],
        environmentVariablePatches: []
      }
    })
    expect(addNodeResponse.ok(), await addNodeResponse.text()).toBeTruthy()
    await openWorkspace(page, auth)

    await expectDarkSurface(page.locator('.board-nav-bar'))
    await expectDarkSurface(page.locator('.modern-panel'))
    await expectDarkSurface(page.locator('.glass-panel'))
    await expectDarkSurface(page.locator('.modern-panel input:not(.hidden)').first())
    await expectDarkSurface(page.locator('.canvas-map'))
    await expectDarkSurface(page.locator('.canvas-map__viewport'))

    const deviceNode = page.locator('[data-node-id="dark_theme_thermostat"]')
    await expectDarkSurface(deviceNode)
    const deviceLabel = deviceNode.locator('.device-label')
    await expect(deviceLabel).toBeVisible()
    const nodeBrightness = brightnessOf(await backgroundOf(deviceNode))
    const labelBrightness = brightnessOf(await computedColorOf(deviceLabel, 'color'))
    expect(labelBrightness - nodeBrightness).toBeGreaterThan(100)

    await deviceNode.press('Enter')
    await expectDarkSurface(page.getByTestId('device-dialog'))
    await expect(page.getByTestId('device-dialog').locator('.iot-dialog__title')).toHaveCSS('font-size', '16px')
    const runtimeSave = page.getByTestId('device-runtime-save')
    await expect(runtimeSave).toHaveCSS('font-size', '14px')
    await expect(runtimeSave).toHaveCSS('font-weight', '600')
    await expect.poll(() => runtimeSave.evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44)
    const sectionTitles = page.getByTestId('device-dialog').locator('.board-section-title')
    expect(await sectionTitles.count()).toBeGreaterThan(0)
    for (const title of await sectionTitles.all()) {
      await expect(title).toHaveCSS('font-size', '14px')
      await expect(title).toHaveCSS('font-weight', '600')
    }
    const tableHeaders = page.getByTestId('device-dialog').getByRole('columnheader')
    expect(await tableHeaders.count()).toBeGreaterThan(0)
    for (const header of await tableHeaders.all()) {
      await expect(header).toHaveCSS('display', 'table-cell')
    }
    const hoverRow = page.getByTestId('device-dialog').locator('tbody tr').nth(2)
    await hoverRow.hover()
    await expectDarkSurface(hoverRow)
    const runtimePanel = page.locator('.device-runtime-panel')
    await expectDarkSurface(runtimePanel)
    await expectDarkSurface(runtimePanel.locator('select').first())
    const securitySummary = runtimePanel.locator('.device-runtime-security > summary')
    await expect(securitySummary).toBeVisible()
    const panelBrightness = brightnessOf(await backgroundOf(runtimePanel))
    const summaryBrightness = brightnessOf(await computedColorOf(securitySummary, 'color'))
    expect(summaryBrightness - panelBrightness).toBeGreaterThan(90)
    await page.getByTestId('device-dialog').getByLabel('Close', { exact: true }).click()

    await page.locator('[data-testid="open-simulation-panel"]').click()

    const panel = page.locator('[data-testid="simulation-panel"]')
    await expectDarkSurface(panel)
    await expectDarkSurface(panel.locator(':scope > .p-3'))
    // Was `:scope > .p-3 > .bg-white` — a hardcoded light-theme utility used as the handle for "the card
    // inside the panel body". That class is exactly what the role-token migration removed, so the assertion
    // depended on the defect it was checking for. `board-card` is the role class that owns a card surface in
    // either theme, which is the thing this test is actually about.
    await expectDarkSurface(panel.locator(':scope > .p-3 .board-card').first())

    // `force: true` here was the original instance of this mistake: the popper left over from the
    // adjacent open-settings control still receives the click, so the panel does not close and the
    // failure surfaces later. See support/tooltips.ts.
    await clickUnderTooltip(page, page.locator('[data-testid="close-simulation-panel"]'))
    await expect(panel).toBeHidden()
    await page.locator('[data-testid="open-history-panel"]').click()
    const historyPanel = page.locator('[data-testid="trace-history-panel"]')
    await expectDarkSurface(historyPanel)
    await expectDarkSurface(historyPanel.locator(':scope > .board-tool-header'))
    await expectDarkSurface(historyPanel.locator(':scope > .history-tabs'))
    await expectDarkSurface(historyPanel.locator(':scope > .board-panel-body'))

    await page.keyboard.press('Escape')
    await page.locator('.ai-assistant-btn').click({ force: true })
    await expect(page.locator('.chat-panel')).toBeVisible({ timeout: 15_000 })
  })

  test('workspace action dock keeps every AI tool reachable in a short landscape viewport', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await page.setViewportSize({ width: 844, height: 390 })
    await openWorkspace(page, auth)

    const panel = page.locator('.board-action-dock__panel')
    await expect(panel).toBeVisible()
    const panelMetrics = await panel.evaluate(element => {
      const rect = element.getBoundingClientRect()
      const style = getComputedStyle(element)
      return {
        top: rect.top,
        bottom: rect.bottom,
        viewportHeight: window.innerHeight,
        overflowY: style.overflowY,
        scrollHeight: element.scrollHeight,
        clientHeight: element.clientHeight
      }
    })
    expect(panelMetrics.top).toBeGreaterThanOrEqual(0)
    expect(panelMetrics.bottom).toBeLessThanOrEqual(panelMetrics.viewportHeight)
    expect(panelMetrics.overflowY).toBe('auto')

    for (const testId of [
      'open-rule-recommendations',
      'open-device-recommendations',
      'open-spec-recommendations'
    ]) {
      const tool = page.getByTestId(testId)
      await tool.scrollIntoViewIfNeeded()
      await expect(tool).toBeVisible()

      const panelBox = await panel.boundingBox()
      const toolBox = await tool.boundingBox()
      expect(panelBox).not.toBeNull()
      expect(toolBox).not.toBeNull()
      expect(toolBox!.y).toBeGreaterThanOrEqual(panelBox!.y - 1)
      expect(toolBox!.y + toolBox!.height).toBeLessThanOrEqual(panelBox!.y + panelBox!.height + 1)
    }
  })

  test('workspace keeps account actions reachable at mid width and low height', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await page.setViewportSize({ width: 720, height: 720 })
    await openWorkspace(page, auth)

    const accountButton = page.getByTestId('open-account-center')
    const sceneActions = page.locator('details.scene-actions-menu')
    await expect(accountButton).toBeVisible()
    await expect(sceneActions).toBeVisible()
    await expect(page.getByTestId('scene-import')).toBeHidden()

    const accountBox = await accountButton.boundingBox()
    expect(accountBox).not.toBeNull()
    expect(accountBox!.x).toBeGreaterThanOrEqual(0)
    expect(accountBox!.x + accountBox!.width).toBeLessThanOrEqual(720)

    await accountButton.click()
    await expect(page.getByTestId('account-page')).toBeVisible()
    await page.getByTestId('open-account-delete').click()
    const accountDialog = page.locator('.account-delete-dialog')
    await expect(accountDialog).toBeVisible()

    await page.setViewportSize({ width: 720, height: 360 })
    const dialogMetrics = await accountDialog.evaluate(element => {
      const rect = element.getBoundingClientRect()
      return {
        top: rect.top,
        bottom: rect.bottom,
        viewportHeight: window.innerHeight
      }
    })
    expect(dialogMetrics.top).toBeGreaterThanOrEqual(0)
    expect(dialogMetrics.bottom).toBeLessThanOrEqual(dialogMetrics.viewportHeight)

    /*
     * The card itself is `overflow: hidden` and its `__body` is the only scrolling part — so the header and the
     * footer's actions stay on screen while a long form scrolls under them. This used to assert `overflow-y:
     * auto` on the card, which scrolled the *whole* dialog including its buttons; measuring the card now reads
     * `hidden` and would look like a regression while the reachability guarantee is strictly better.
     *
     * What matters is the guarantee, so assert that: the body is the scroller, and the confirm button is
     * on screen at 360px tall without being scrolled to.
     */
    const bodyMetrics = await accountDialog.locator('.iot-dialog__body').evaluate(element => ({
      overflowY: getComputedStyle(element).overflowY,
      scrollHeight: element.scrollHeight,
      clientHeight: element.clientHeight
    }))
    expect(bodyMetrics.overflowY).toBe('auto')
    expect(bodyMetrics.scrollHeight).toBeGreaterThan(bodyMetrics.clientHeight)

    const confirmButton = accountDialog.locator('button[type="submit"]')
    await expect(confirmButton).toBeInViewport()
    await expect(confirmButton).toBeVisible()
  })
})
