import { expandCreation } from './support/controlCenter'
import { apiBaseURL, createAuthenticatedUser, expect, test } from './support/auth'
import { clickUnderTooltip } from './support/tooltips'

for (const variant of [{ locale: 'en', width: 1440, height: 900 }, { locale: 'zh-CN', width: 375, height: 700 }]) {
  test(`device import formats preserve equivalent runtime settings in ${variant.locale}`, async ({ page, request }, testInfo) => {
    const auth = await createAuthenticatedUser(request, { usernamePrefix: 'importformats' })
    const headers = { Authorization: `Bearer ${auth.token}` }
    await page.addInitScript(({ auth, locale }) => {
      localStorage.setItem('iot_verify_token', auth.token)
      localStorage.setItem('iot_verify_user', JSON.stringify(auth))
      localStorage.setItem('locale', locale)
    }, { auth, locale: variant.locale })
    await page.setViewportSize(variant)
    await page.goto('/#/board')
    await expect(page.getByTestId('board-root')).toBeVisible()
    await expect(page.getByTestId('board-data-loading')).toHaveCount(0)
    const collapseName = variant.locale === 'en' ? 'Collapse System Inspector' : '收起系统检查器'
    const inspectorCollapse = page.getByTestId('system-inspector').getByRole('button', { name: collapseName, exact: true })
    if (await inspectorCollapse.isVisible()) await inspectorCollapse.click()
    const expand = page.getByTestId('control-center').getByRole('button', { name: variant.locale === 'en' ? 'Expand Control Center' : '展开控制中心', exact: true })
    if (await expand.isVisible()) await clickUnderTooltip(page, expand)
    await page.getByTestId('control-tab-devices').click()
    await expandCreation(page, 'devices')
    await page.getByTestId('device-create-mode-import').click()
    const saved: any[] = []
    for (const format of ['json', 'csv']) {
      await page.getByTestId('device-import-source-file').check()
      await page.getByTestId(`device-import-format-${format}`).click()
      await expect(page.getByTestId('device-create-mode-import')).toHaveClass(/active/)
      await expect(page.getByTestId('device-import-file')).toHaveAttribute('accept', `.${format}`)
      await expect(page.getByTestId('device-import-text')).toHaveCount(0)
      await expect(page.getByTestId(`device-import-${format === 'json' ? 'csv' : 'json'}-template`)).toHaveCount(0)
      const downloadPromise = page.waitForEvent('download')
      await page.getByTestId(`device-import-${format}-template`).click()
      const download = await downloadPromise
      expect(download.suggestedFilename()).toBe(`devices.${format}`)
      const file = await download.path()
      expect(file).toBeTruthy()
      await page.getByTestId('device-import-file').setInputFiles(file!)
      await expect(page.getByTestId('device-import-preview').locator('strong')).toHaveText('2/2')

      const label = `phone_${format}`
      const input = format === 'json' ? JSON.stringify([{ templateName: 'Mobile Phone', label, state: 'on',
        currentStateTrust: 'untrusted', currentStatePrivacy: 'private',
        variables: [{ name: 'steps', value: '23', trust: 'untrusted' }, { name: 'location', value: 'home', trust: 'trusted' }],
        privacies: [{ name: 'steps', privacy: 'private' }, { name: 'location', privacy: 'public' }] }])
        : `templateName,label,state,currentStateTrust,currentStatePrivacy,variable:steps,trust:steps,privacy:steps,variable:location,trust:location,privacy:location\nMobile Phone,${label},on,untrusted,private,23,untrusted,private,home,trusted,public`
      await page.getByTestId('device-import-source-paste').check()
      await expect(page.getByTestId('device-import-file-trigger')).toHaveCount(0)
      await expect(page.getByTestId('device-import-csv-encoding')).toHaveCount(0)
      await expect(page.getByTestId('device-import-text')).toHaveValue('')
      await expect(page.getByTestId('device-import-create')).toBeDisabled()
      await expect(page.getByTestId('device-import-text')).toHaveAttribute('placeholder', new RegExp(format.toUpperCase()))
      await page.getByTestId('device-import-text').fill(input)
      const preview = page.getByTestId('device-import-preview')
      await expect(preview).toContainText(label)
      await expect(preview.locator('strong')).toHaveText('1/1')
      await page.getByTestId('device-import-source-file').check()
      await expect(page.getByTestId('device-import-text')).toHaveCount(0)
      await expect(preview.locator('strong')).toHaveText('2/2')
      await expect(preview).toContainText('entry_motion')
      await page.getByTestId('device-import-source-paste').check()
      await expect(page.getByTestId('device-import-text')).toHaveValue(input)
      await expect(preview).toContainText(label)
      await expect(preview.locator('strong')).toHaveText('1/1')
      await page.screenshot({ path: testInfo.outputPath(`${format}-preview.png`) })
      await page.getByTestId('device-import-create').click()
      let node: any
      await expect.poll(async () => {
        const response = await request.get(`${apiBaseURL}/api/board/nodes`, { headers })
        expect(response.ok()).toBe(true)
        node = (await response.json()).data.find((entry: any) => entry.label === label)
        return Boolean(node)
      }).toBe(true)
      const nodes = await request.get(`${apiBaseURL}/api/board/nodes`, { headers })
      expect((await nodes.json()).data).toHaveLength(format === 'json' ? 1 : 2)
      saved.push({ state: node.state, currentStateTrust: node.currentStateTrust, currentStatePrivacy: node.currentStatePrivacy,
        variables: node.variables, privacies: node.privacies })
      await page.getByTestId('device-import-source-file').check()
      await page.getByTestId('device-import-file-clear').click()
      await expect(preview).toHaveCount(0)
      await expect(page.getByTestId('device-import-create')).toBeDisabled()
      await page.getByTestId('device-import-source-paste').check()
      await expect(page.getByTestId('device-import-text')).toHaveValue(input)
    }
    expect(saved[1]).toEqual(saved[0])
    expect(saved[1]).toMatchObject({ state: 'on', currentStateTrust: 'untrusted', currentStatePrivacy: 'private',
      variables: [expect.objectContaining({ name: 'steps', value: '23', trust: 'untrusted' })],
      privacies: [expect.objectContaining({ name: 'steps', privacy: 'private' })] })
    const environment = await request.get(`${apiBaseURL}/api/board/environment`, { headers })
    expect((await environment.json()).data).toContainEqual(expect.objectContaining({ name: 'location', value: 'home', trust: 'trusted', privacy: 'public' }))
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  })
}
