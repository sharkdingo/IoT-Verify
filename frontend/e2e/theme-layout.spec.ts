import { type Page } from '@playwright/test'
import {
  createAuthenticatedUser,
  seedBrowserSession,
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
  await seedBrowserSession(page, auth, { locale: 'en', theme: 'dark' })

  await page.goto('/#/board')
  await expect(page.locator('.iot-board')).toBeVisible({ timeout: 60_000 })
}

test.describe('public theme and layout', () => {
  test('keeps session controls reachable and focuses Cancel at narrow workspace widths', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openWorkspace(page, auth)
    for (const width of [320, 639, 640]) {
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
        await opener.press('Enter')
        await expect(menu.getByRole('group', { name: 'Appearance and account' })).toBeVisible()
        const account = page.getByTestId('nav-overflow-account')
        await expect(account).toBeInViewport()
        const menuButtons = await menu.getByRole('button').count()
        for (let index = 0; index < menuButtons; index++) {
          await page.keyboard.press('Tab')
          if (await account.evaluate(button => document.activeElement === button)) break
        }
        await expect(account).toBeFocused()
        const focusReturn = await opener.evaluateHandle(summary => {
          const details = summary.closest('details')!
          const state = { closedOnFocus: false }
          // Navigation can unmount the Board before the activation click bubbles to the menu.
          summary.addEventListener('focus', () => { state.closedOnFocus = !details.open }, { once: true })
          return state
        })
        await page.keyboard.press('Enter')
        const { closedOnFocus } = await focusReturn.jsonValue()
        await focusReturn.dispose()
        expect.soft(closedOnFocus, `Menu closed and returned focus at ${width}px`).toBe(true)
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
      await page.getByTestId('account-back').press('Enter')
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
