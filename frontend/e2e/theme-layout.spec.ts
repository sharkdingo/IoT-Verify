import { type Page } from '@playwright/test'
import {
  createAuthenticatedUser,
  createTestAccountCredentials,
  expect,
  test,
  trackTestAccount,
  type AuthUser
} from './support/auth'

type ThemeMode = 'light' | 'dark'

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

  test('user can register and reach the workspace through the UI', { tag: '@smoke' }, async ({ page, request }) => {
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
})
