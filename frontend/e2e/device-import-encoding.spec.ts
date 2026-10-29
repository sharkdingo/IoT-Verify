import { expandCreation } from './support/controlCenter'
import { readFileSync } from 'node:fs'
import { apiBaseURL, createAuthenticatedUser, expect, test } from './support/auth'
import { clickUnderTooltip } from './support/tooltips'

const fixture = JSON.parse(readFileSync(new URL('../src/testing/fixtures/device-list-encoding.json', import.meta.url), 'utf8')) as {
  csv: string
  gb18030Base64: string
  devices: { label: string }[]
}

const locale = 'zh-CN'
const width = 375
test(`device-list encoding and quoted Chinese values match JSON in ${locale} at ${width}px`, async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'csvencoding' })
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
  await expandCreation(page, 'devices')
  await page.getByTestId('device-create-mode-import').click()
  await page.getByTestId('device-import-format-csv').click()
  const fileInput = page.getByTestId('device-import-file')
  const failure = page.getByTestId('device-import-file-failure')
  const create = page.getByTestId('device-import-create')
  const encoding = page.getByTestId('device-import-csv-encoding')
  const gbFile = { name: 'devices.csv', mimeType: 'text/csv', buffer: Buffer.from(fixture.gb18030Base64, 'base64') }
  await fileInput.setInputFiles(gbFile)
  await expect(failure).toContainText(locale === 'en' ? 'encoding' : '编码')
  await expect(create).toBeDisabled()
  await expect(page.getByTestId('device-import-text')).toHaveCount(0)
  await expect(page.getByTestId('device-import-file-name')).toHaveCount(0)
  const initial = await request.get(`${apiBaseURL}/api/board/nodes`, { headers })
  expect((await initial.json()).data).toEqual([])
  await failure.scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('encoding-error.png') })
  await encoding.selectOption('gb18030')
  await expect(page.getByTestId('device-import-text')).toHaveCount(0)
  await expect(page.getByTestId('device-import-file-name')).toHaveCount(0)
  await expect(failure).toHaveCount(0)

  const snapshots: any[][] = []
  for (const [index, variant] of ['gb18030', 'json', 'utf-8'].entries()) {
    await page.getByTestId(`device-import-format-${variant === 'json' ? 'json' : 'csv'}`).click()
    if (variant === 'utf-8') await encoding.selectOption('utf-8')
    if (variant === 'json') await expect(encoding).toHaveCount(0)
    await fileInput.setInputFiles(variant === 'gb18030' ? gbFile : {
      name: variant === 'json' ? 'devices.json' : 'devices.csv',
      mimeType: variant === 'json' ? 'application/json' : 'text/csv',
      buffer: Buffer.from('\uFEFF' + (variant === 'json' ? JSON.stringify(fixture.devices) : fixture.csv), 'utf8')
    })
    await expect(failure).toHaveCount(0)
    await expect(page.getByTestId('device-import-preview').locator('strong')).toHaveText('2/2')
    await expect(create).toBeEnabled()
    await create.click()
    await expect.poll(async () => {
      const response = await request.get(`${apiBaseURL}/api/board/nodes`, { headers })
      return (await response.json()).data.length
    }).toBe((index + 1) * 2)
    const response = await request.get(`${apiBaseURL}/api/board/nodes`, { headers })
    const nodes = (await response.json()).data as any[]
    snapshots.push(fixture.devices.map(device => {
      const label = device.label + (index === 0 ? '' : `_${index}`)
      const node = nodes.find(entry => entry.label === label)
      expect(node, `Stored label must preserve Chinese, commas, quotes and line breaks: ${label}`).toBeDefined()
      return { templateName: node.templateName, state: node.state, currentStateTrust: node.currentStateTrust,
        currentStatePrivacy: node.currentStatePrivacy, variables: node.variables, privacies: node.privacies }
    }))
  }
  expect(snapshots[1]).toEqual(snapshots[0])
  expect(snapshots[2]).toEqual(snapshots[0])
  expect(snapshots[0]![0]).toMatchObject({ state: 'on', currentStateTrust: 'untrusted', currentStatePrivacy: 'private',
    variables: [expect.objectContaining({ name: 'steps', value: '0', trust: 'trusted' })],
    privacies: [expect.objectContaining({ name: 'steps', privacy: 'public' })] })
  // Blank cells retain template inheritance rather than storing explicit tag overrides.
  expect(snapshots[0]![1]).toMatchObject({ state: 'on', currentStateTrust: null, currentStatePrivacy: null })
  const environment = await request.get(`${apiBaseURL}/api/board/environment`, { headers })
  expect((await environment.json()).data).toContainEqual(expect.objectContaining({ name: 'location', value: 'home', trust: 'trusted', privacy: 'private' }))
  const help = page.getByTestId('device-import-encoding-help')
  await help.click()
  const tooltip = page.getByRole('tooltip').filter({ hasText: 'GB18030' })
  await expect(tooltip).toBeVisible()
  const bounds = await tooltip.boundingBox()
  expect(bounds!.x).toBeGreaterThanOrEqual(0)
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width)
  await page.screenshot({ path: testInfo.outputPath('encoding-help.png') })
  await help.press('Escape')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})
