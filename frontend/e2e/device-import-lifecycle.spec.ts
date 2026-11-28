import { expandCreation } from './support/controlCenter'
import { readFileSync } from 'node:fs'
import { apiBaseURL, createAuthenticatedUser, expect, test } from './support/auth'
import { clickUnderTooltip } from './support/tooltips'

const fixture = JSON.parse(readFileSync(new URL('../src/testing/fixtures/device-list-encoding.json', import.meta.url), 'utf8')) as {
  devices: { label: string }[]
  gb18030Base64: string
}

for (const mode of ['batch', 'csv'] as const) test(`${mode}: recovers a device group after a lost response and undoes it as one edit`, async ({ page, request }) => {
  const csv = mode === 'csv'
  const labels = csv ? fixture.devices.map(device => device.label)
    : ['Batch_phone_1', 'Batch_phone_2']
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

  const before = await snapshot()
  await page.addInitScript(({ auth, locale }) => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('locale', locale)
  }, { auth, locale: csv ? 'zh-CN' : 'en' })
  await page.setViewportSize({ width: csv ? 375 : 1440, height: 900 })
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
  const modeButton = page.getByTestId(`device-create-mode-${mode === 'batch' ? 'batch' : 'import'}`)
  await modeButton.click()
  if (mode === 'batch') {
    await page.getByTestId('batch-device-template').selectOption('Mobile Phone')
    await page.getByTestId('batch-device-prefix').fill('Batch_phone_')
    await page.getByTestId('batch-device-count').fill('2')
    for (const label of labels) await expect(page.getByTestId('batch-device-fieldset')).toContainText(label)
  } else {
    await page.getByTestId('device-import-format-csv').click()
    await page.getByTestId('device-import-csv-encoding').selectOption('gb18030')
    await page.getByTestId('device-import-file').setInputFiles({
      name: 'devices.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from(fixture.gb18030Base64, 'base64')
    })
    const preview = page.getByTestId('device-import-preview')
    await expect(preview.locator('strong')).toHaveText('2/2')
    for (const label of labels) await expect(preview).toContainText(label)
  }

  let createCount = 0
  const recoveryReads: string[] = []
  const boardReads = new Set(['/api/board/snapshot', '/api/board/nodes',
    '/api/board/environment', '/api/board/edits/availability'])
  page.on('request', networkRequest => {
    const endpoint = new URL(networkRequest.url()).pathname
    if (createCount > 0 && networkRequest.method() === 'GET' && boardReads.has(endpoint)) {
      recoveryReads.push(endpoint)
    }
  })
  await page.route('**/api/board/nodes', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    createCount += 1
    const committed = await route.fetch()
    expect(committed.ok(), await committed.text()).toBe(true)
    await route.abort()
  })
  const create = page.getByTestId(mode === 'batch' ? 'batch-device-create' : 'device-import-create')
  const refreshed = page.waitForResponse(response => response.url().endsWith('/api/board/snapshot'))
  await clickUnderTooltip(page, create)
  const recoveryResponse = await refreshed
  expect(recoveryResponse.ok()).toBe(true)
  const recovered = (await recoveryResponse.json()).data
  const warning = page.getByTestId('device-group-save-unconfirmed')
  await expect(warning).toContainText(csv ? '保存未确认' : 'The save outcome is unconfirmed')
  await expect(warning).toContainText('2/2')
  expect(await page.getByText(csv ? '已添加 2 个设备' : '2 device(s) added', { exact: true }).count()).toBe(0)
  expect(recovered.nodes).toHaveLength(before.nodes.length + 2)
  const recoveredNodes = labels.map(label => recovered.nodes.find((node: { label: string }) => node.label === label))
  for (const node of recoveredNodes) {
    expect(node).toBeDefined()
    await expect(page.locator(`[data-node-id="${node.id}"]`)).toContainText(node.label)
  }
  const lastNode = page.locator(`[data-node-id="${recoveredNodes.at(-1).id}"]`)
  if (csv) await expect(lastNode).toHaveClass(/node-focused/)
  else await expect(lastNode).toBeFocused()
  if (csv) {
    const environmentNotices = page.locator('.el-message--info').filter({ hasText: /^环境变量池已(?:新增|更新|移除)：/ })
    expect(await environmentNotices.count()).toBe(0)
  }
  await page.getByTestId('device-create-mode-single').click()
  await modeButton.click()
  await expect(warning).toBeVisible()
  await expect(create).toBeDisabled()
  expect(createCount).toBe(1)
  expect(recoveryReads).toEqual(['/api/board/snapshot'])
  if (csv) {
    const [first, second] = recoveredNodes
    expect(first).toMatchObject({ state: 'on', currentStateTrust: 'untrusted', currentStatePrivacy: 'private',
      variables: [expect.objectContaining({ name: 'steps', value: '0', trust: 'trusted' })],
      privacies: [expect.objectContaining({ name: 'steps', privacy: 'public' })] })
    expect(second).toMatchObject({ state: 'on', currentStateTrust: null, currentStatePrivacy: null })
    expect(recovered.environmentVariables).toContainEqual({
      name: 'location', value: 'home', trust: 'trusted', privacy: 'private'
    })
    await clickUnderTooltip(page, page.getByTestId('control-center-toggle'))
    const inspectorToggle = page.getByTestId('system-inspector-toggle')
    if (await inspectorToggle.getAttribute('aria-expanded') === 'false') await clickUnderTooltip(page, inspectorToggle)
    await page.getByTestId('inspector-tab-environment').click()
    await expect(page.getByTestId('environment-pool').locator('[data-environment-name="location"]')).toBeVisible()
  }

  const undo = page.waitForResponse(response => response.url().endsWith('/api/board/edits/undo'))
  await clickUnderTooltip(page, page.getByTestId('board-undo'))
  const undoResponse = await undo
  expect(undoResponse.request().postDataJSON()).toEqual({
    expectedEditHistoryRevision: recovered.editHistory.editHistoryRevision
  })
  expect((await undoResponse.json()).data).toMatchObject({ applied: true, canUndo: false, canRedo: true })
  const undone = await snapshot()
  expect(undone.nodes).toEqual(before.nodes)
  expect(undone.environmentVariables).toEqual(before.environmentVariables)
  expect(createCount).toBe(1)

  if (csv) {
    await expect(page.getByTestId('environment-pool').locator('[data-environment-name="location"]')).toHaveCount(0)
    const redo = page.waitForResponse(response => response.url().endsWith('/api/board/edits/redo'))
    await clickUnderTooltip(page, page.getByTestId('board-redo'))
    expect((await (await redo).json()).data).toMatchObject({ applied: true })
    await page.reload()
    await expect(page.getByTestId('scene-import-file')).toBeEnabled()
    const restored = await snapshot()
    expect(restored.nodes).toEqual(recovered.nodes)
    expect(restored.environmentVariables).toEqual(recovered.environmentVariables)
    for (const node of recoveredNodes) await expect(page.locator(`[data-node-id="${node.id}"]`)).toContainText(node.label)
  }
})
