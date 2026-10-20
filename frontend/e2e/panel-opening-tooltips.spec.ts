import { expect, test } from './support/auth'

for (const locale of ['zh-CN', 'en']) {
  for (const width of [320, 1920]) {
    test(`opening tool panels does not show an unsolicited close hint in ${locale} at ${width}px`, async ({ page, sharedReadOnlyAccount: auth }, testInfo) => {
      await page.setViewportSize({ width, height: width === 320 ? 500 : 1080 })
      await page.addInitScript(({ auth, locale }) => {
        localStorage.setItem('iot_verify_token', auth.token)
        localStorage.setItem('iot_verify_user', JSON.stringify(auth))
        localStorage.setItem('locale', locale)
      }, { auth, locale })
      await page.goto('/#/board')
      await expect(page.getByTestId('board-root')).toBeVisible()
      await expect(page.getByTestId('scene-import')).toBeEnabled()
      for (const kind of ['simulation', 'verification', 'fuzzing', 'history']) {
        const restore = page.getByTestId('restore-action-dock')
        if (await restore.isVisible()) await restore.click()
        await page.getByTestId(`open-${kind}-panel`).click()
        const panel = page.getByTestId(kind === 'history' ? 'trace-history-panel' : `${kind}-panel`)
        const close = page.getByTestId(kind === 'history' ? 'close-history-panel' : `close-${kind}-panel`)
        await expect(panel).toBeFocused()
        await page.mouse.move(4, 4)
        const closeHint = page.locator('.iot-hint-tooltip-popper:not([aria-hidden="true"])')
          .filter({ hasText: new RegExp(`^${locale === 'en' ? 'Close' : '关闭'}$`) })
        await expect(closeHint).toHaveCount(0)
        await page.screenshot({ path: testInfo.outputPath(`${kind}-opened.png`) })
        await page.keyboard.press('Tab')
        if (kind === 'fuzzing') {
          await expect(page.getByTestId('fuzz-intro-help')).toBeFocused()
          await page.keyboard.press('Tab')
        }
        await expect(close).toBeFocused()
        await expect(closeHint).toBeVisible()
        await page.keyboard.press('Escape')
        await expect(panel).toBeHidden()
      }
    })
  }
}
