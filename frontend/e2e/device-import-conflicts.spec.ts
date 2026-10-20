import { readFile, writeFile } from 'node:fs/promises'
import { apiBaseURL, createAuthenticatedUser, expect, test } from './support/auth'
import { clickUnderTooltip } from './support/tooltips'

for (const locale of ['en', 'zh-CN']) for (const width of [1440, 375]) {
  test(`edited device-list templates validate and import in ${locale} at ${width}px`, async ({ page, request }, testInfo) => {
    const auth = await createAuthenticatedUser(request, { usernamePrefix: 'importchecks' })
    const headers = { Authorization: `Bearer ${auth.token}` }
    await page.addInitScript(({ auth, locale }) => {
      localStorage.setItem('iot_verify_token', auth.token)
      localStorage.setItem('iot_verify_user', JSON.stringify(auth))
      localStorage.setItem('locale', locale)
    }, { auth, locale })
    await page.setViewportSize({ width, height: width === 375 ? 740 : 900 })
    await page.goto('/#/board')
    await expect(page.getByTestId('board-root')).toBeVisible()
    await expect(page.getByTestId('board-data-loading')).toHaveCount(0)
    const collapse = page.getByTestId('system-inspector').getByRole('button', { name: locale === 'en' ? 'Collapse System Inspector' : '收起系统检查器', exact: true })
    if (await collapse.isVisible()) await collapse.click()
    const expand = page.getByTestId('control-center').getByRole('button', { name: locale === 'en' ? 'Expand Control Center' : '展开控制中心', exact: true })
    if (await expand.isVisible()) await clickUnderTooltip(page, expand)
    await page.getByTestId('control-tab-devices').click()
    await page.getByTestId('device-create-mode-import').click()
    const nodes = async () => {
      const response = await request.get(`${apiBaseURL}/api/board/nodes`, { headers })
      expect(response.ok()).toBe(true)
      return (await response.json()).data as any[]
    }
    const shared = (value = 'home', trust = 'trusted', privacy = 'public') => ({
      templateName: 'Mobile Phone', label: 'phone', state: 'on', currentStateTrust: 'untrusted', currentStatePrivacy: 'private',
      variables: [{ name: 'steps', value: '23', trust: 'untrusted' }, { name: 'location', value, trust }],
      privacies: [{ name: 'steps', privacy: 'private' }, { name: 'location', privacy }]
    })
    const saved: any[] = []
    for (const format of ['json', 'csv']) {
      await page.getByTestId(`device-import-format-${format}`).click()
      const downloadPending = page.waitForEvent('download')
      await page.getByTestId(`device-import-${format}-template`).click()
      const download = await downloadPending
      const file = testInfo.outputPath(`filled-devices.${format}`)
      await download.saveAs(file)
      const starter = await readFile(file, 'utf8')
      const csvHeaders = starter.split(/\r?\n/)[0]!.split(',')
      const serialize = (entries: any[]) => {
        if (format === 'json') {
          const template = JSON.parse(starter)
          expect(template).toHaveLength(2)
          return JSON.stringify(entries.map((entry, index) => ({ ...template[index], ...entry })), null, 2)
        }
        const columns = [...new Set([...csvHeaders, 'variable:steps', 'trust:steps', 'privacy:steps', 'variable:location', 'trust:location', 'privacy:location'])]
        return [columns.join(','), ...entries.map(entry => columns.map(column => {
          const [kind, name] = column.split(':')
          if (kind === 'variable') return entry.variables?.find((variable: any) => variable.name === name)?.value ?? ''
          if (kind === 'trust') return entry.variables?.find((variable: any) => variable.name === name)?.trust ?? ''
          if (kind === 'privacy') return entry.privacies?.find((privacy: any) => privacy.name === name)?.privacy ?? ''
          return entry[column] ?? ''
        }).join(','))].join('\n')
      }
      const upload = async (entries: any[]) => {
        const filled = serialize(entries)
        await writeFile(file, filled, 'utf8')
        await page.getByTestId('device-import-file').setInputFiles(file)
        await expect(page.getByTestId('device-import-file-name')).toHaveText(`filled-devices.${format}`)
        await expect(page.getByTestId('device-import-text')).toHaveCount(0)
      }
      const before = await nodes()
      const valid = shared()
      for (const invalid of [
        { ...valid, state: 'not-a-state' },
        { ...valid, currentStateTrust: 'not-a-label' },
        { ...valid, currentStatePrivacy: 'not-a-label' },
        { ...valid, variables: [{ name: 'steps', value: '101' }] }
      ]) {
        await upload([invalid, { templateName: 'Light', label: 'phone' }])
        await expect(page.getByTestId('device-import-create')).toBeDisabled()
        const row = page.getByTestId('device-import-preview').locator('.has-error')
        await expect(row).toContainText('phone')
        await expect(row.getByTestId('device-import-row-source')).toHaveText(format === 'json'
          ? (locale === 'en' ? 'Entry 1' : '第 1 项') : (locale === 'en' ? 'Line 2' : '第 2 行'))
        await expect(page.getByTestId('device-import-blocked-reason')).toBeVisible()
      }
      await upload([shared(), shared('away', 'untrusted', 'private')])
      const conflicts = page.getByTestId('device-import-environment-conflicts')
      await expect(conflicts.getByRole('listitem')).toHaveCount(3)
      await expect(page.getByTestId('device-import-create')).toBeDisabled()
      await expect(page.getByTestId('device-import-blocked-reason')).toHaveCount(1)
      await conflicts.scrollIntoViewIfNeeded()
      await page.screenshot({ path: testInfo.outputPath(`${format}-conflicts.png`) })
      const switchLanguage = async (name: string) => {
        if (width === 375) {
          const menu = page.locator('.scene-actions-menu')
          await menu.locator('summary').click()
          await menu.getByRole('button', { name, exact: true }).click()
          await menu.locator('summary').click()
        } else await clickUnderTooltip(page, page.getByRole('button', { name, exact: true }))
      }
      await switchLanguage(locale === 'en' ? '切换到中文' : 'Switch to English')
      await expect(conflicts).toContainText(locale === 'en' ? '共享环境变量冲突' : 'Shared environment conflicts')
      await expect(conflicts).not.toContainText('#')
      await switchLanguage(locale === 'en' ? 'Switch to English' : '切换到中文')
      expect(await nodes()).toEqual(before)

      await upload([shared(), { templateName: 'Light', label: 'phone', state: 'off' }])
      await expect(conflicts).toHaveCount(0)
      await expect(page.getByTestId('device-import-blocked-reason')).toHaveCount(0)
      await expect(page.getByTestId('device-import-create')).toBeEnabled()
      await expect(page.getByTestId('device-import-row-name')).toHaveText(format === 'json' ? ['phone', 'phone_1'] : ['phone_2', 'phone_3'])
      const names = await page.getByTestId('device-import-row-name').allTextContents()
      await expect(page.getByTestId('device-import-preview')).toContainText(locale === 'en' ? 'will be renamed' : '将从')
      const help = page.getByTestId('device-import-help')
      await help.click()
      await expect(page.locator('.el-message--success, .el-message--info')).toHaveCount(0)
      const tooltip = page.getByRole('tooltip').filter({ hasText: format === 'json' ? 'templateName' : 'variable:' })
      await expect(tooltip).toBeVisible()
      const bounds = await tooltip.boundingBox()
      expect(bounds!.x).toBeGreaterThanOrEqual(0)
      expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width)
      await page.screenshot({ path: testInfo.outputPath(`${format}-help.png`) })
      await help.press('Escape')
      await page.getByTestId('device-import-create').click()
      await expect.poll(async () => (await nodes()).length).toBe(before.length + 2)
      const current = await nodes()
      const phone = current.find(node => node.label === names[0])
      expect(current.find(node => node.label === names[1]).state).toBe('off')
      saved.push({ state: phone.state, currentStateTrust: phone.currentStateTrust, currentStatePrivacy: phone.currentStatePrivacy,
        variables: phone.variables, privacies: phone.privacies })
      const environment = await request.get(`${apiBaseURL}/api/board/environment`, { headers })
      expect((await environment.json()).data).toContainEqual(expect.objectContaining({ name: 'location', value: 'home', trust: 'trusted', privacy: 'public' }))
      await upload([{ ...shared('away', 'untrusted', 'private'), label: `environment_${format}` }])
      await page.getByTestId('device-import-create').click()
      await expect.poll(async () => {
        const response = await request.get(`${apiBaseURL}/api/board/environment`, { headers })
        return (await response.json()).data.find((entry: any) => entry.name === 'location')
      }).toMatchObject({ value: 'away', trust: 'untrusted', privacy: 'private' })
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    }
    expect(saved[1]).toEqual(saved[0])
    expect(saved[0]).toMatchObject({ state: 'on', currentStateTrust: 'untrusted', currentStatePrivacy: 'private',
      variables: [expect.objectContaining({ name: 'steps', value: '23', trust: 'untrusted' })],
      privacies: [expect.objectContaining({ name: 'steps', privacy: 'private' })] })
  })
}
