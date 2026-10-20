import { createAuthenticatedUser, expect, test } from './support/auth'
import { clickUnderTooltip } from './support/tooltips'
import { selectHistoryResultType } from './support/historyFilter'

for (const width of [1280, 375]) {
  test(`loading feedback leaves side-panel headings visible at ${width}px`, async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request, { usernamePrefix: 'loadinglayout' })
    await page.addInitScript(auth => {
      localStorage.setItem('iot_verify_token', auth.token)
      localStorage.setItem('iot_verify_user', JSON.stringify(auth))
      localStorage.setItem('locale', 'en')
    }, auth)
    await page.setViewportSize({ width, height: 720 })
    let release!: () => void
    const gate = new Promise<void>(resolve => { release = resolve })
    await page.route('**/api/board/snapshot', async route => {
      const response = await route.fetch()
      await gate
      await route.fulfill({ response })
    })
    try {
      await page.goto('/#/board')
      const loading = page.getByTestId('board-data-loading')
      await expect(loading).toBeVisible()
      const inspector = page.getByTestId('system-inspector')
      if (width < 768) await clickUnderTooltip(page, inspector.getByRole('button', { name: 'Expand System Inspector', exact: true }))
      const heading = inspector.getByRole('heading', { name: 'System Inspector', exact: true })
      await expect(heading).toBeVisible()
      const loadingBounds = await loading.boundingBox()
      const headingBounds = await heading.boundingBox()
      expect(loadingBounds).not.toBeNull()
      expect(headingBounds).not.toBeNull()
      expect(headingBounds!.y).toBeGreaterThanOrEqual(loadingBounds!.y + loadingBounds!.height)
      release()
      await expect(loading).toHaveCount(0)
      await expect(inspector.locator('.board-inventory-empty p')).toHaveText('No devices on canvas')
    } finally { release() }
  })
}

for (const width of [320, 375]) {
  test(`keeps history help and empty results in the active language at ${width}px`, async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request, { usernamePrefix: 'copylocale' })
    await page.addInitScript(auth => {
      localStorage.setItem('iot_verify_token', auth.token)
      localStorage.setItem('iot_verify_user', JSON.stringify(auth))
      localStorage.setItem('locale', 'zh-CN')
    }, auth)
    await page.setViewportSize({ width, height: 720 })
    await page.goto('/#/board')
    await expect(page.getByTestId('board-root')).toBeVisible()
    await expect(page.getByTestId('board-data-loading')).toHaveCount(0)
    const restoreTools = async () => {
      const restore = page.getByTestId('restore-action-dock')
      if (await restore.isVisible()) await restore.click()
    }
    const switchLanguage = async (name: string) => {
      const menu = page.locator('.scene-actions-menu')
      await menu.locator('summary').click()
      await menu.getByRole('button', { name, exact: true }).click()
      await menu.locator('summary').click()
    }
    await restoreTools()
    await clickUnderTooltip(page, page.getByTestId('open-history-panel'))
    const history = page.getByTestId('trace-history-panel')
    await page.getByTestId('history-layer-tasks').click()
    await expect(history.locator('.history-empty p')).toHaveText('没有待处理任务')
    const help = page.getByTestId('history-tasks-help')
    await help.focus()
    const tooltip = page.locator('.iot-info-tooltip-popper:not(.iot-hint-tooltip-popper):not([aria-hidden="true"]) .iot-tooltip-content')
    await expect(tooltip).toContainText('完成的运行会自动移到“历史结果”')
    const expectTooltipFits = async () => {
      const bounds = await tooltip.boundingBox()
      expect(bounds).not.toBeNull()
      expect(bounds!.x).toBeGreaterThanOrEqual(0)
      expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width)
    }
    await expectTooltipFits()
    await switchLanguage('Switch to English')
    await expect(help).toHaveAttribute('aria-label', 'Show help for Background Tasks')
    await help.click()
    await expect(tooltip).toContainText('Completed runs move to History Results automatically.')
    await expectTooltipFits()
    await history.getByRole('heading', { name: 'Run History', exact: true }).click()
    await page.getByTestId('history-layer-results').click()
    await selectHistoryResultType(page, 'fuzzing')
    await expect(history.locator('.history-empty p')).toHaveText('No history results for Counterexample Exploration')
    await switchLanguage('切换到中文')
    await expect(history.locator('.history-empty p')).toHaveText('暂无反例探索历史结果')
    expect(await history.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    await page.getByTestId('close-history-panel').click()
    await restoreTools()
    await clickUnderTooltip(page, page.getByTestId('open-fuzzing-panel'))
    await expect(page.getByTestId('fuzzing-panel').getByRole('heading', { name: '反例探索', exact: true })).toBeVisible()
    await switchLanguage('Switch to English')
    await expect(page.getByTestId('fuzzing-panel').getByRole('heading', { name: 'Counterexample Exploration', exact: true })).toBeVisible()
  })
}
