import { type APIRequestContext, type Locator, type Page, type Route } from '@playwright/test'
import { createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'
import { clickUnderTooltip, dismissHintTooltips } from './support/tooltips'
import { collapseChatSidebar } from './support/chatSidebar'

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

for (const { width, theme } of [{ width: 1440, theme: 'light' }, { width: 375, theme: 'dark' }, { width: 320, theme: 'light' }]) {
  test(`keeps auxiliary controls readable and reachable at ${width}px in ${theme}`, async ({ page, sharedReadOnlyAccount: auth }) => {
    await page.setViewportSize({ width, height: 900 })
    await seedSession(page, auth, { iot_verify_theme: theme })
    await page.goto('/#/board')
    const inspector = page.getByTestId('system-inspector')
    await expect(inspector).toBeVisible({ timeout: 60_000 })
    const control = page.getByTestId('control-center')
    const collapseControl = control.getByRole('button', { name: 'Collapse Control Center', exact: true })
    if (await collapseControl.isVisible()) await collapseControl.click()
    const expandInspector = inspector.getByRole('button', { name: 'Expand System Inspector', exact: true })
    if (await expandInspector.isVisible()) await expandInspector.click()
    await page.getByTestId('inspector-tab-devices').click()
    const search = page.getByTestId('inspector-search-devices')
    await search.fill('test')
    const clear = inspector.getByRole('button', { name: 'Clear search', exact: true })
    await expect.poll(() => clear.evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThanOrEqual(44)
    await search.press('Tab')
    await expect(clear).toBeFocused()
    await expect(clear).toHaveCSS('outline-width', '2px')
    await page.keyboard.press('Enter')
    await expect(search).toHaveValue('')
    await inspector.getByRole('button', { name: 'Collapse System Inspector', exact: true }).click()
    const restoreDock = page.getByTestId('restore-action-dock')
    if (await restoreDock.isVisible()) await restoreDock.click()
    await clickUnderTooltip(page, page.getByTestId('open-simulation-panel'))
    const steps = page.getByTestId('simulation-steps-input')
    await steps.fill(await steps.getAttribute('min') ?? '1')
    await steps.press('Tab')
    const decrease = page.getByTestId('simulation-steps-decrease')
    await expect(decrease).toBeDisabled()
    await expect(decrease).toHaveCSS('opacity', '1')
    await expect.poll(() => decrease.evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44)
    const disabledBackground = await decrease.evaluate(element => {
      const probe = document.createElement('span')
      probe.style.color = 'var(--surface-muted)'
      document.body.append(probe)
      const color = getComputedStyle(probe).color
      probe.remove()
      return color
    })
    await expect(decrease).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)')
    await expect.poll(() => decrease.evaluate(element => getComputedStyle(element, '::before').backgroundColor)).toBe(disabledBackground)
    await decrease.hover()
    await expect.poll(() => decrease.evaluate(element => getComputedStyle(element, '::before').backgroundColor)).toBe(disabledBackground)
    await page.getByTestId('simulation-steps-increase').focus()
    await page.keyboard.press('Enter')
    await expect(decrease).toBeEnabled()
    const unavailableSwitch = page.getByTestId('simulation-attack-toggle')
    await expect(unavailableSwitch).toBeDisabled()
    await expect(unavailableSwitch).toHaveCSS('opacity', '1')
    await expect.poll(() => unavailableSwitch.evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44)
    const switchControl = page.getByTestId('simulation-privacy-toggle')
    await expect(switchControl).toBeEnabled()
    await expect.poll(() => switchControl.evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44)
    await switchControl.focus()
    await expect(switchControl).toBeFocused()
    await expect(switchControl).toHaveCSS('outline-width', '2px')
    const switchState = await switchControl.getAttribute('aria-checked')
    await page.keyboard.press('Space')
    await expect(switchControl).toHaveAttribute('aria-checked', switchState === 'true' ? 'false' : 'true')
    await page.getByTestId('close-simulation-panel').click()
    await page.goto('/#/account')
    await expect(page.getByTestId('account-username-value')).toBeVisible()
    await page.getByTestId('account-edit-password').click()
    const password = page.locator('#account-current-password')
    await expect(password).toHaveCSS('font-size', '14px')
    await expect(page.locator('label[for="account-current-password"]')).toHaveCSS('font-size', '13px')
    await password.focus()
    await expect(password).toHaveCSS('outline-width', '2px')
    await expect(page.getByTestId('account-save-password')).toBeDisabled()
    await expect(page.getByTestId('account-save-password')).toHaveCSS('opacity', '1')
    const newPassword = page.locator('#account-new-password')
    await newPassword.fill('short')
    await expect(newPassword).toHaveAttribute('aria-invalid', 'true')
    await expect.poll(() => newPassword.evaluate(element => {
      const probe = document.createElement('span')
      probe.style.color = 'var(--danger-border)'
      document.body.append(probe)
      const expected = getComputedStyle(probe).color
      probe.remove()
      return getComputedStyle(element).borderTopColor === expected
    })).toBe(true)
    await expect(page.locator('#account-new-password-hint')).toHaveCSS('font-size', '12px')
    await password.fill('Password123')
    await expect(page.locator('#account-password-blocked')).toHaveCount(0)
    await expect(page.getByTestId('account-save-password')).toHaveAttribute('aria-describedby', 'account-password-impact account-new-password-hint')
    expect(await page.getByTestId('account-page').evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    await page.getByTestId('account-cancel-password').click()
  })
}

test('uses consistent Chinese creation wording on a narrow control center', async ({ page, sharedReadOnlyAccount: auth }) => {
  await page.setViewportSize({ width: 320, height: 900 })
  await seedSession(page, auth, { locale: 'zh-CN', iot_verify_theme: 'dark' })
  await page.goto('/#/board')
  const inspector = page.getByTestId('system-inspector')
  const collapseInspector = inspector.getByRole('button', { name: '收起系统检查器', exact: true })
  if (await collapseInspector.isVisible()) await collapseInspector.click()
  const control = page.getByTestId('control-center')
  await expect(control).toBeVisible({ timeout: 60_000 })
  const expand = control.getByRole('button', { name: '展开控制中心', exact: true })
  if (await expand.isVisible()) await expand.click()
  await page.getByTestId('control-tab-templates').click()
  const format = page.getByTestId('control-template-create').getByRole('button', { name: '下载格式定义', exact: true })
  const formatHelp = page.getByTestId('template-format-help')
  await format.scrollIntoViewIfNeeded()
  await formatHelp.focus()
  await expect(page.getByRole('tooltip')).toContainText('JSON Schema')
  expect(await format.evaluate(element => {
    const box = element.getBoundingClientRect()
    return [0.5, 2, 8].every(inset =>
      document.elementFromPoint(box.right - inset, box.top + box.height / 2)?.closest('button') === element)
  }), 'template format help leaves the whole download button clickable').toBe(true)
  await formatHelp.press('Tab')
  for (const [section, heading] of [['devices', '创建设备'], ['rules', '创建规则'], ['specs', '创建规约']]) {
    await page.getByTestId(`control-tab-${section}`).click()
    const panel = page.getByTestId(`control-section-${section}`)
    await expect(panel.locator('.board-section-title').first()).toHaveText(heading)
    expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
  }
})

test('updates the selected field label when its option text changes without changing the value', async ({ page, request }) => {
  await openLayoutTestBoard(page, request)
  const control = page.getByTestId('control-center')
  const expand = control.getByRole('button', { name: 'Expand Control Center', exact: true })
  if (await expand.isVisible()) await expand.click()
  await page.getByTestId('control-tab-devices').click()
  const template = page.getByTestId('single-device-template')
  await expect(template).toBeEnabled()
  await template.selectOption('Light')
  await page.getByTestId('single-device-runtime-toggle').click()
  const trust = page.getByTestId('single-device-state-trust')
  const label = trust.locator('selectedcontent')
  await expect(trust).toHaveValue('')
  await expect(label).toHaveText('Use template default (Trusted)')

  await clickUnderTooltip(page, page.getByRole('button', { name: '切换到中文', exact: true }))
  await expect(trust.locator('option:checked')).toHaveText('使用模板默认值（可信）')
  await expect(label).toHaveText('使用模板默认值（可信）')
  await expect(trust).toHaveValue('')

  await clickUnderTooltip(page, page.getByRole('button', { name: 'Switch to English', exact: true }))
  await expect(label).toHaveText('Use template default (Trusted)')
  await expect(trust).toHaveValue('')
})

for (const { theme, palette, width } of [
  { theme: 'light', palette: 'copper', width: 1440 },
  { theme: 'dark', palette: 'copper', width: 1440 },
  { theme: 'light', palette: 'monochrome', width: 375 },
  { theme: 'dark', palette: 'monochrome', width: 375 }
]) {
  test(`keeps workspace form typography consistent at ${width}px in ${palette} ${theme}`, async ({ page, sharedReadOnlyAccount: auth }) => {
    await page.setViewportSize({ width, height: 900 })
    await seedSession(page, auth, { iot_verify_theme: theme, iot_verify_palette: palette })
    await page.goto('/#/board')
    const control = page.getByTestId('control-center')
    const inspector = page.getByTestId('system-inspector')
    await expect(control).toBeVisible({ timeout: 60_000 })
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    await expect(page.locator('html')).toHaveAttribute('data-palette', palette)

    const exportSceneAndCheckMessage = async () => {
      const menu = page.locator('details.scene-actions-menu')
      if (width < 640) await menu.locator('summary').click()
      const exportButton = width < 640 ? menu.getByRole('button', { name: 'Export Scene', exact: true })
        : page.getByTestId('scene-export')
      const download = page.waitForEvent('download')
      await clickUnderTooltip(page, exportButton)
      await download
      const message = page.locator('.el-message__content').filter({ hasText: /Scene JSON download requested/ })
      await expect(message).toBeVisible()
      await expect(message).toHaveCSS('font-size', '14px')
      await expect(message).toHaveCSS('font-weight', '400')
      await expect(message).toHaveCSS('line-height', '22.4px')
      const toast = message.locator('..')
      await expect(toast).toHaveCSS('pointer-events', 'none')
      await expect(toast).toHaveCSS('border-radius', '14px')
      await expect(toast).toHaveCSS('padding', '8px 14px')
      // The transition starts on the next frame; visibility alone can precede its resting position.
      await expect.poll(() => toast.evaluate(element => {
        const box = element.getBoundingClientRect()
        const nav = document.querySelector('.board-nav-bar')!.getBoundingClientRect()
        return { insideViewport: box.left >= 16 && box.right <= window.innerWidth - 16,
          centered: Math.abs(box.left + box.width / 2 - window.innerWidth / 2) < 0.5,
          belowNav: box.top >= nav.bottom, textFits: element.scrollWidth <= element.clientWidth }
      })).toEqual({ insideViewport: true, centered: true, belowNav: true, textFits: true })
      if (width < 640) await expect(menu.locator('summary')).toHaveAttribute('aria-expanded', 'false')
      await expect(toast).toHaveCount(0, { timeout: 10_000 })
    }

    // Feedback must look the same before and after opening lazy-loaded assistant controls.
    await exportSceneAndCheckMessage()
    const expectField = async (field: Locator) => {
      await expect(field).toBeVisible()
      await expect(field).toHaveCSS('font-size', '14px')
      await expect(field).toHaveCSS('font-weight', '400')
      await expect(field).toHaveCSS('box-shadow', 'none')
      await expect.poll(() => field.evaluate(element => {
        const box = element.getBoundingClientRect()
        const style = getComputedStyle(element)
        return box.height >= 44 && parseFloat(style.borderTopWidth) > 0
          && parseFloat(style.borderTopWidth) <= 1
      })).toBe(true)
    }
    const expectAction = async (action: Locator) => {
      await expect(action).toHaveCSS('font-size', '14px')
      await expect(action).toHaveCSS('font-weight', '600')
      await expect(action).toHaveCSS('text-transform', 'none')
      await expect.poll(() => action.evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44)
    }

    if (width < 768) {
      const collapse = inspector.getByRole('button', { name: 'Collapse System Inspector', exact: true })
      if (await collapse.isVisible()) await collapse.click()
    } else {
      const expand = inspector.getByRole('button', { name: 'Expand System Inspector', exact: true })
      if (await expand.isVisible()) await expand.click()
      await page.getByTestId('inspector-tab-devices').click()
      await expectField(page.getByTestId('inspector-search-devices'))
      await expect(inspector.getByRole('heading', { level: 2 })).toHaveCSS('font-size', '16px')
    }
    const expand = control.getByRole('button', { name: 'Expand Control Center', exact: true })
    if (await expand.isVisible()) await expand.click()
    await expect(control.getByRole('heading', { level: 2 })).toHaveCSS('font-size', '16px')

    for (const section of ['templates', 'devices', 'rules', 'specs'] as const) {
      await page.getByTestId(`control-tab-${section}`).click()
      const panel = page.getByTestId(`control-section-${section}`)
      await expect(panel).toBeVisible()
      await expect(panel.locator('.board-section-title').first()).toHaveCSS('font-size', '14px')
      await expect(panel.locator('.board-section-title').first()).toHaveText({
        templates: 'Import template', devices: 'Create device', rules: 'Create rule', specs: 'Create specification'
      }[section]!)
      if (section === 'templates') {
        await expectField(panel.getByRole('searchbox', { name: /^Search templates/ }))
      } else {
        for (const label of await panel.locator('label').all()) {
          if (await label.isVisible()) await expect(label).toHaveCSS('font-size', '13px')
        }
        const field = section === 'devices' ? panel.getByTestId('single-device-name')
          : section === 'rules' ? panel.locator('#rule-builder-name') : panel.getByTestId('spec-template-select')
        await expectField(field)
        if (section === 'devices') {
          await expectField(panel.getByTestId('single-device-template'))
          await expectAction(panel.getByTestId('single-device-create'))
          await expect(panel.getByTestId('single-device-create')).toHaveAccessibleName('Create device')
          await field.focus()
          await expect(field).toHaveCSS('outline-style', 'solid')
          await expect(field).toHaveCSS('outline-width', '2px')
        } else if (section === 'rules') {
          await expectField(panel.locator('#rule-target-device'))
          await expectAction(panel.getByTestId('rule-reset'))
          await expectAction(panel.getByTestId('rule-save'))
        } else {
          await expectAction(panel.getByTestId('spec-create'))
        }
      }
      expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    }

    if (width < 768) await control.getByRole('button', { name: 'Collapse Control Center', exact: true }).click()
    const restoreDock = page.getByTestId('restore-action-dock')
    if (await restoreDock.isVisible()) await restoreDock.click()
    await page.getByTestId('open-fuzzing-panel').hover()
    const hint = page.locator('.iot-info-tooltip-popper:not([aria-hidden="true"])')
    await expect(hint).toHaveCount(1)
    await expect(hint).toHaveCSS('font-size', '13px')
    await expect(hint).toHaveCSS('font-weight', '400')
    await expect(hint).toHaveCSS('border-radius', '8px')
    expect(await hint.evaluate(element => getComputedStyle(element).backgroundColor)).not.toMatch(/rgba\(0, 0, 0, 0\)|transparent/)
    await dismissHintTooltips(page)
    await page.getByTestId('open-fuzzing-panel').focus()
    await page.keyboard.press('Enter')
    const fuzzing = page.getByTestId('fuzzing-panel')
    await expect(fuzzing.getByRole('heading', { level: 3 })).toHaveCSS('font-size', '16px')
    await expectField(fuzzing.getByTestId('fuzz-max-iterations'))
    await expectField(fuzzing.getByTestId('fuzz-path-length'))
    await expectAction(fuzzing.getByTestId('run-fuzzing'))
    expect(await fuzzing.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    await clickUnderTooltip(page, page.getByTestId('close-fuzzing-panel'))

    for (const kind of ['scenario', 'rule', 'device', 'spec']) {
      await clickUnderTooltip(page, page.getByTestId(`open-${kind}-recommendations`))
      const panel = page.getByTestId(`${kind}-recommendation-panel`)
      await expect(panel).toBeVisible()
      await expect(panel.getByRole('heading', { level: 3 })).toHaveCSS('font-size', '16px')
      await expectField(panel.locator('input[type="number"]').first())
      await expect(panel.locator('label').first()).toHaveCSS('font-size', '13px')
      const generateId = kind === 'scenario' ? 'generate-scenario-recommendation' : `generate-${kind}-recommendations`
      await expectAction(panel.getByTestId(generateId))
      expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
      await dismissHintTooltips(page)
      await panel.getByTestId(`${kind}-recommendation-help`).focus()
      const explanation = page.locator('.iot-info-tooltip-popper:not([aria-hidden="true"])')
      await expect(explanation).toHaveCount(1)
      await expect(explanation).toBeVisible()
      expect(await explanation.evaluate(element => {
        const rect = element.getBoundingClientRect()
        return element.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2))
      })).toBe(true)
      await panel.getByTestId(`${kind}-recommendation-help`).press('Tab')
      await expect(panel.locator('textarea')).toBeFocused()
      await clickUnderTooltip(page, page.getByTestId(`close-${kind}-recommendations`))
    }

    await clickUnderTooltip(page, page.locator('.ai-assistant-btn'))
    const chat = page.getByTestId('chat-panel')
    await expect(chat).toBeVisible()
    await expect(chat.locator('.header-title')).toHaveCSS('font-size', '16px')
    await expect(chat.locator('.header-title')).toHaveCSS('font-weight', '600')
    await expect(chat.getByTestId('chat-input')).toHaveCSS('font-size', '14px')
    await chat.getByTestId('chat-sidebar-toggle').click()
    await expectAction(chat.getByRole('button', { name: 'New Chat', exact: true }))
    await collapseChatSidebar(page)
    await page.getByTestId('chat-close').click()
    await expect(chat).toBeHidden()
    await dismissHintTooltips(page)

    await exportSceneAndCheckMessage()

    if (width < 640) {
      await page.locator('details.scene-actions-menu summary').click()
      await page.getByTestId('nav-overflow-account').click()
    } else {
      await clickUnderTooltip(page, page.getByTestId('open-account-center'))
    }
    await page.getByTestId('open-account-delete').click()
    const accountDialog = page.getByTestId('account-delete-dialog')
    await expect(accountDialog).toBeVisible()
    await expect(accountDialog.locator('.iot-dialog__title')).toHaveCSS('font-size', '16px')
    await expect(accountDialog.locator('.iot-dialog__title')).toHaveCSS('font-weight', '600')
    await expectField(accountDialog.getByTestId('account-delete-password'))
    await expectAction(accountDialog.getByTestId('account-delete-cancel'))
    await accountDialog.getByTestId('account-delete-cancel').click()
  })
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
  test('chooses light, dark, and follow-system, persisting only explicit choices', async ({ page, sharedReadOnlyAccount: auth }) => {
    await openBoard(page, auth)

    // Select the preference control rendered by the current viewport; its overflow counterpart is hidden.
    const toggle = page.locator('.board-nav-bar .theme-toggle:visible')
    const storedTheme = () => page.evaluate(() => window.localStorage.getItem('iot_verify_theme'))

    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')

    await toggle.click()
    const appearance = page.getByRole('region', { name: 'Appearance' })
    await expect(appearance).toBeVisible()
    await expect(appearance.locator('legend').first()).toHaveCSS('font-size', '13px')
    await expect(appearance.locator('[data-theme-mode="light"]')).toHaveCSS('font-size', '14px')
    await expect(appearance.locator('[data-theme-mode="light"]')).toHaveCSS('font-weight', '600')
    await appearance.locator('[data-theme-mode="dark"]').click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
    expect(await storedTheme()).toBe('dark')

    // Third state: following the OS clears the stored override entirely.
    await appearance.locator('[data-theme-mode="system"]').click()
    expect(await storedTheme()).toBeNull()
    await expect(toggle).toHaveAccessibleName(/Appearance: System/i)

    await appearance.locator('[data-theme-mode="light"]').click()
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

  test('keeps rule creation inline without locking the canvas or discarding drafts', async ({ page, request }) => {
    await openLayoutTestBoard(page, request)

    const bodyOverflow = () => page.evaluate(() => document.body.style.overflow)
    expect(await bodyOverflow()).not.toBe('hidden')

    await page.getByTestId('control-tab-rules').click()
    await expect(page.getByTestId('control-section-rules')).toBeVisible()
    const editor = page.getByTestId('control-section-rules').getByTestId('rule-builder')
    await expect(editor).toBeVisible()
    await editor.getByTestId('rule-conditions-help').focus()
    const help = page.locator('.iot-info-tooltip-popper:not([aria-hidden="true"])')
    await expect(help).toBeVisible()
    await expect(help).toContainText('All conditions must hold together')
    await editor.locator('#rule-builder-name').fill('Inline draft')
    const dialog = page.locator('[role="dialog"][aria-modal="true"]')
    await expect(dialog).toHaveCount(0)
    expect(await bodyOverflow()).not.toBe('hidden')
    await page.getByTestId('control-tab-devices').click()
    await expect(editor).toBeHidden()
    await page.getByTestId('control-tab-rules').click()
    await expect(editor.locator('#rule-builder-name')).toHaveValue('Inline draft')
    const toggle = page.getByTestId('control-center').getByRole('button', { name: 'Collapse Control Center', exact: true })
    await toggle.click()
    await expect(editor).toBeHidden()
    await page.getByTestId('control-center').getByRole('button', { name: 'Expand Control Center', exact: true }).click()
    await expect(editor.locator('#rule-builder-name')).toHaveValue('Inline draft')
    await expect(editor.locator('#rule-builder-name')).toBeEditable()
    await editor.getByTestId('rule-reset').click()
    await expect(editor.locator('#rule-builder-name')).toHaveValue('')
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
