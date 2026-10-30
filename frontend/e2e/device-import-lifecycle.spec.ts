import { expandCreation } from './support/controlCenter'
import { readFileSync } from 'node:fs'
import { apiBaseURL, createAuthenticatedUser, expect, test } from './support/auth'
import { clickUnderTooltip } from './support/tooltips'

const fixture = JSON.parse(readFileSync(new URL('../src/testing/fixtures/device-list-encoding.json', import.meta.url), 'utf8')) as {
  devices: { label: string }[]
  gb18030Base64: string
}

test('imports GB18030 devices through the narrow Board and retains their decoded state after reload', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'importlife' })
  const snapshot = async () => {
    const response = await request.get(`${apiBaseURL}/api/board/snapshot`, {
      headers: { Authorization: `Bearer ${auth.token}` }
    })
    expect(response.ok(), await response.text()).toBe(true)
    const body = await response.json()
    expect(body.code).toBe(200)
    return body.data
  }

  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('locale', 'zh-CN')
  }, auth)
  await page.setViewportSize({ width: 375, height: 900 })
  await page.goto('/#/board')
  await expect(page.getByTestId('scene-import-file')).toBeEnabled()
  const collapseInspector = page.getByTestId('system-inspector').getByRole('button', {
    name: '收起系统检查器', exact: true
  })
  if (await collapseInspector.isVisible()) await clickUnderTooltip(page, collapseInspector)
  const expandControl = page.getByTestId('control-center').getByRole('button', {
    name: '展开控制中心', exact: true
  })
  if (await expandControl.isVisible()) await clickUnderTooltip(page, expandControl)
  await page.getByTestId('control-tab-devices').click()
  await expandCreation(page, 'devices')
  await page.getByTestId('device-create-mode-import').click()
  await page.getByTestId('device-import-format-csv').click()
  await page.getByTestId('device-import-csv-encoding').selectOption('gb18030')
  await page.getByTestId('device-import-file').setInputFiles({
    name: 'devices.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from(fixture.gb18030Base64, 'base64')
  })
  const preview = page.getByTestId('device-import-preview')
  await expect(preview.locator('strong')).toHaveText('2/2')
  for (const device of fixture.devices) await expect(preview).toContainText(device.label)
  await page.getByTestId('device-import-create').click()
  await expect.poll(async () => (await snapshot()).nodes.length).toBe(2)
  const imported = await snapshot()
  const first = imported.nodes.find((node: { label: string }) => node.label === fixture.devices[0]!.label)
  const second = imported.nodes.find((node: { label: string }) => node.label === fixture.devices[1]!.label)
  expect(first).toMatchObject({ state: 'on', currentStateTrust: 'untrusted', currentStatePrivacy: 'private',
    variables: [expect.objectContaining({ name: 'steps', value: '0', trust: 'trusted' })],
    privacies: [expect.objectContaining({ name: 'steps', privacy: 'public' })] })
  expect(second).toMatchObject({ state: 'on', currentStateTrust: null, currentStatePrivacy: null })
  expect(imported.environmentVariables).toContainEqual({
    name: 'location', value: 'home', trust: 'trusted', privacy: 'private'
  })
  await page.reload()
  await expect(page.getByTestId('board-data-loading')).toHaveCount(0)
  await expect(page.getByTestId('scene-import-file')).toBeEnabled()
  const restored = await snapshot()
  expect(restored.nodes).toEqual(imported.nodes)
  expect(restored.environmentVariables).toEqual(imported.environmentVariables)
  await expect(page.locator(`[data-node-id="${first.id}"]`)).toContainText(first.label)
  await expect(page.locator(`[data-node-id="${second.id}"]`)).toContainText(second.label)
})
