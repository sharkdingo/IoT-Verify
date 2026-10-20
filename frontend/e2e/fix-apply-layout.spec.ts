import path from 'node:path'
import { apiBaseURL, createAuthenticatedUser, expect, test } from './support/auth'
import { expectFooterUsable } from './support/footerLayout'
import { clickUnderTooltip, dismissHintTooltips } from './support/tooltips'

for (const locale of ['zh-CN', 'en']) {
  for (const width of [320, 1920]) {
    test(`generates, confirms and reverses a repair in ${locale} at ${width}px`, async ({ page, request }, testInfo) => {
      test.setTimeout(180_000)
      const auth = await createAuthenticatedUser(request, { usernamePrefix: 'fixLayout' })
      const headers = { Authorization: `Bearer ${auth.token}` }
      const chinese = locale === 'zh-CN'
      const receipts = page.locator(':is(.el-message--success, .el-message--info):not(.el-message-fade-leave-active)')
      await page.setViewportSize({ width, height: width === 320 ? 500 : 1080 })
      await page.addInitScript(({ auth, locale, width }) => {
        localStorage.setItem('iot_verify_token', auth.token)
        localStorage.setItem('iot_verify_user', JSON.stringify(auth))
        localStorage.setItem('locale', locale)
        localStorage.setItem('iot_verify_theme', width === 320 ? 'dark' : 'light')
      }, { auth, locale, width })
      await page.goto('/#/board')
      await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
      await page.getByTestId('scene-import-file').setInputFiles(path.resolve(
        process.cwd(), '..', 'docs/examples/default-fire-evacuation-scene.json'
      ))
      const replace = page.getByRole('dialog')
      await replace.getByRole('button', { name: chinese ? '替换场景' : 'Replace scene', exact: true }).click()
      const snapshot = async () => {
        const response = await request.get(`${apiBaseURL}/api/board/snapshot`, { headers })
        expect(response.ok(), await response.text()).toBe(true)
        const { nodes, environmentVariables, rules, specifications } = (await response.json()).data
        return { nodes, environmentVariables, rules, specifications }
      }
      await expect.poll(async () => (await snapshot()).rules.length).toBe(3)
      const before = await snapshot()
      const openVerification = async () => {
        const restore = page.getByTestId('restore-action-dock')
        if (await restore.isVisible()) await restore.click()
        await clickUnderTooltip(page, page.getByTestId('open-verification-panel'))
        await page.getByTestId('verification-mode-sync').click()
      }
      await openVerification()
      const verified = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/verify')
      await page.getByTestId('run-verification').click()
      const verificationResponse = await verified
      expect(verificationResponse.ok(), await verificationResponse.text()).toBe(true)
      expect((await verificationResponse.json()).data.outcome).toBe('VIOLATED')
      await page.getByTestId('verification-trace-fix').first().click()
      const fix = page.getByTestId('fix-result-dialog')
      await expect(fix.getByRole('dialog')).toBeFocused()
      await dismissHintTooltips(page)
      const help = fix.getByTestId('fix-scope-help')
      await help.press('Space')
      const openHelp = page.locator('.iot-info-tooltip-popper:not(.iot-hint-tooltip-popper):visible')
      await expect(openHelp).toBeVisible()
      await expect(openHelp).toBeInViewport()
      await page.keyboard.press('Escape')
      await expect(openHelp).toBeHidden()
      await expect(fix).toBeVisible()
      await expect(help).toBeFocused()
      await fix.getByTestId('fix-strategy-remove').press('Enter')
      const generated = page.waitForResponse(response => response.request().method() === 'POST'
        && /\/api\/verify\/traces\/\d+\/fix$/.test(new URL(response.url()).pathname))
      await fix.getByTestId('fix-try-current').press('Enter')
      const generatedResponse = await generated
      expect(generatedResponse.ok(), await generatedResponse.text()).toBe(true)
      const result = (await generatedResponse.json()).data
      expect(result.strategyAttempts).toEqual([expect.objectContaining({ strategy: 'remove', status: 'VERIFIED' })])
      const suggestion = result.suggestions.find((item: { strategy: string }) => item.strategy === 'remove')
      expect(suggestion.removedRuleDescriptions).toHaveLength(1)
      await expect(fix.getByTestId('fix-removed-rule')).toHaveText(suggestion.removedRuleDescriptions)
      await expect(fix.getByTestId('fix-apply-current')).toBeEnabled()
      await expect(fix.getByTestId('fix-apply-current')).toBeFocused()
      await fix.getByTestId('fix-removed-rule').first().scrollIntoViewIfNeeded()
      await expect(fix.getByTestId('fix-removed-rule').first()).toBeInViewport()
      await dismissHintTooltips(page)
      expect.soft(await receipts.count(), 'old receipts must not cover repair details').toBe(0)
      await expectFooterUsable(fix.locator('.iot-dialog__footer'))
      expect(await fix.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
      await page.screenshot({ path: testInfo.outputPath('verified-repair.png') })

      const apply = fix.getByTestId('fix-apply-current')
      await apply.press('Enter')
      const confirmation = page.locator('.el-message-box.fix-removal-confirm:visible')
      await expect(confirmation).toBeFocused()
      await page.keyboard.press('Enter')
      await expect(confirmation).toBeVisible()
      await page.keyboard.press('Shift+Tab')
      expect(await confirmation.evaluate(element => element.contains(document.activeElement))).toBe(true)
      expect.soft(await receipts.count(), 'old receipts must not cover confirmation').toBe(0)
      await expectFooterUsable(confirmation.locator('.el-message-box__btns'))
      await expect(confirmation.locator('li')).toHaveText(suggestion.removedRuleDescriptions)
      await page.screenshot({ path: testInfo.outputPath('removal-confirmation.png') })
      await page.keyboard.press('Escape')
      await expect(confirmation).toBeHidden()
      await expect(fix).toBeVisible()
      await expect(apply).toBeFocused()
      expect(await snapshot()).toEqual(before)
      await expect(apply).toBeEnabled()

      await apply.press('Enter')
      const applied = page.waitForResponse(response => response.request().method() === 'POST'
        && /\/api\/verify\/traces\/\d+\/fix\/apply$/.test(new URL(response.url()).pathname))
      await confirmation.getByRole('button', { name: chinese ? '移除规则并应用' : 'Remove Rules and Apply', exact: true }).press('Enter')
      const appliedResponse = await applied
      expect(appliedResponse.ok(), await appliedResponse.text()).toBe(true)
      expect((await appliedResponse.json()).data).toMatchObject({ applied: true, verificationEvidenceReused: true })
      await expect(fix).toBeHidden()
      const verification = page.getByTestId('verification-result-dialog')
      await expect(verification.getByRole('dialog')).toBeFocused()
      await expect(verification.getByTestId('verification-fix-applied')).toBeVisible()
      await dismissHintTooltips(page)
      expect(await receipts.count(), 'the inline receipt must not be repeated over the result header').toBe(0)
      await page.screenshot({ path: testInfo.outputPath('repair-applied-focus.png') })
      await expect.poll(async () => (await snapshot()).rules.length).toBe(2)
      const after = await snapshot()
      expect(after.nodes).toEqual(before.nodes)
      expect(after.specifications).toEqual(before.specifications)
      expect(after.environmentVariables).toEqual(before.environmentVariables)
      await page.keyboard.press('Escape')
      await expect(page.getByTestId('open-verification-panel')).toBeFocused()

      await expect(page.getByTestId('board-undo')).toBeEnabled()
      await page.getByTestId('board-undo').press('Enter')
      await expect.poll(snapshot).toEqual(before)
      await expect(page.getByTestId('board-redo')).toBeFocused()
      expect.soft(await receipts.count(), 'undo supersedes earlier receipts').toBe(1)
      await expect(page.getByTestId('board-redo')).toBeEnabled()
      await page.getByTestId('board-redo').press('Enter')
      await expect.poll(snapshot).toEqual(after)
      await expect(page.getByTestId('board-undo')).toBeFocused()
      expect.soft(await receipts.count(), 'redo supersedes earlier receipts').toBe(1)
      await expect(page.getByTestId('board-redo')).toBeDisabled()

      const stableControl = page.locator('.logo-left')
      await stableControl.focus()
      await page.keyboard.press('Control+z')
      await expect.poll(snapshot).toEqual(before)
      await expect(stableControl).toBeFocused()
      await page.keyboard.press('Control+Shift+z')
      await expect.poll(snapshot).toEqual(after)
      await expect(stableControl).toBeFocused()

      await openVerification()
      const reverified = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/verify')
      await page.getByTestId('run-verification').click()
      const reverifiedResponse = await reverified
      expect(reverifiedResponse.ok(), await reverifiedResponse.text()).toBe(true)
      expect((await reverifiedResponse.json()).data).toMatchObject({ outcome: 'SATISFIED', modelComplete: true })
      expect.soft(await receipts.count(), 'completed results must remain readable').toBe(0)
      await page.screenshot({ path: testInfo.outputPath('verified-after-redo.png') })
    })
  }
}
