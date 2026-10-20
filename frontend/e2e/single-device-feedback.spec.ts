import type { APIRequestContext, APIResponse, Page, Response } from '@playwright/test'
import type { BoardSemanticSnapshot } from '../src/api/board'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'

const unwrap = async (response: APIResponse | Response) => {
  expect(response.ok(), await response.text()).toBeTruthy()
  const body = await response.json()
  expect(body.code).toBe(200)
  return body.data
}

const persistedState = async (request: APIRequestContext, auth: AuthUser) => {
  const headers = { Authorization: `Bearer ${auth.token}` }
  const [snapshot, history, runs] = await Promise.all([
    request.get(`${apiBaseURL}/api/board/snapshot`, { headers }).then(unwrap),
    request.get(`${apiBaseURL}/api/board/edits/clear-preview`, { headers }).then(unwrap),
    request.get(`${apiBaseURL}/api/verify/runs`, { headers }).then(unwrap)
  ])
  expect(Number.isSafeInteger(history.entryCount)).toBe(true)
  return { snapshot: snapshot as BoardSemanticSnapshot, history, runs }
}

const prepareBoard = async (request: APIRequestContext, auth: AuthUser) => {
  await unwrap(await request.post(`${apiBaseURL}/api/board/nodes`, {
    headers: { Authorization: `Bearer ${auth.token}` },
    data: { devices: [{ id: 'existing_device', templateName: 'Temperature Sensor', label: '已有温度计',
      position: { x: 200, y: 160 }, state: 'Working', width: 180, height: 150 }],
      environmentVariablePatches: [{ name: 'temperature', value: '20', trust: 'trusted', privacy: 'public' }] }
  }))
}

const openCreationForm = async (page: Page, auth: AuthUser, name: string) => {
  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('locale', 'zh-CN')
  }, auth)
  await page.goto('/#/board')
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
  const control = page.getByTestId('control-center')
  const expand = control.getByRole('button', { name: '展开控制中心', exact: true })
  if (await expand.isVisible()) await expand.click()
  await page.getByTestId('control-tab-devices').click()
  await page.getByTestId('device-create-mode-single').click()
  const template = page.getByTestId('single-device-template')
  await expect(template.locator('option[value="Thermostat"]')).toHaveCount(1)
  await template.selectOption('Thermostat')
  await page.getByTestId('single-device-name').fill(name)
  await page.getByTestId('single-device-runtime-toggle').click()
}

const expectOnlyCreatedDevice = (
  before: Awaited<ReturnType<typeof persistedState>>,
  after: Awaited<ReturnType<typeof persistedState>>,
  mutation: any,
  name: string,
  value: string
) => {
  expect(mutation.affectedDevices).toHaveLength(1)
  const created = mutation.affectedDevices[0]
  expect(created.label).toBe(name)
  expect(created.templateName).toBe('Thermostat')
  expect(created.variables.find((variable: { name: string }) => variable.name === 'thermostatSetpoint')?.value).toBe(value)
  expect(after.snapshot.nodes.find(node => node.id === created.id)).toEqual(created)
  expect(after.snapshot.nodes).toHaveLength(before.snapshot.nodes.length + 1)
  expect(after.snapshot.nodes.filter(node => node.id !== created.id)).toEqual(before.snapshot.nodes)
  const { nodes: _beforeNodes, ...beforeCollections } = before.snapshot
  const { nodes: _afterNodes, ...afterCollections } = after.snapshot
  expect(afterCollections).toEqual(beforeCollections)
  expect(after.runs).toEqual(before.runs)
  expect(after.history.entryCount).toBe(before.history.entryCount + 1)
  return created
}

for (const width of [1280, 375]) {
  test(`Chinese single-device integer drafts stay editable without writes at ${width}px`, async ({ page, request }, testInfo) => {
    await page.setViewportSize({ width, height: width === 375 ? 660 : 800 })
    const auth = await createAuthenticatedUser(request, { usernamePrefix: 'singleValue' })
    await prepareBoard(request, auth)
    const before = await persistedState(request, auth)
    const name = '单台恒温器'
    await openCreationForm(page, auth, name)
    let writes = 0
    page.on('request', request => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/board/nodes') writes += 1
    })
    const input = page.getByTestId('single-device-variable-thermostatSetpoint')
    const failure = page.getByTestId('single-device-value-error-thermostatSetpoint')
    const create = page.getByTestId('single-device-create')
    for (const invalid of ['1.5', '101']) {
      await input.fill(invalid)
      await expect(input).toHaveValue(invalid)
      await expect(failure).toBeInViewport({ ratio: 1 })
      await expect(failure).toContainText('整数')
      await expect(failure).toContainText('0')
      await expect(failure).toContainText('100')
      await expect(input).toHaveAttribute('aria-invalid', 'true')
      await expect(create).toBeDisabled()
      await expect(page.getByTestId('single-device-name')).toHaveValue(name)
      await expect(page.locator('.el-message--error')).toHaveCount(0)
      expect(writes).toBe(0)
      expect(await persistedState(request, auth)).toEqual(before)
    }
    await testInfo.attach('single-device-local-integer-guidance.png', { body: await page.screenshot(), contentType: 'image/png' })
    await input.fill('29')
    await expect(failure).toHaveCount(0)
    await expect(create).toBeEnabled()
    const accepted = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/board/nodes')
    await create.click()
    const mutation = await unwrap(await accepted)
    const after = await persistedState(request, auth)
    const created = expectOnlyCreatedDevice(before, after, mutation, name, '29')
    expect(writes).toBe(1)
    await expect(page.getByTestId('single-device-name')).toHaveValue('')
    await page.reload()
    await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
    expect(await persistedState(request, auth)).toEqual(after)
    expect((await persistedState(request, auth)).snapshot.nodes.find(node => node.id === created.id)
      ?.variables?.find(variable => variable.name === 'thermostatSetpoint')?.value).toBe('29')
  })
}

for (const width of [1280, 375]) {
  test(`Chinese single-device creation retains real server field rejection and saves the corrected draft at ${width}px`, async ({ page, request }, testInfo) => {
    await page.setViewportSize({ width, height: width === 375 ? 660 : 720 })
    const auth = await createAuthenticatedUser(request, { usernamePrefix: 'singleReject' })
    await prepareBoard(request, auth)
    const before = await persistedState(request, auth)
    const name = '后端校验恒温器'
    await openCreationForm(page, auth, name)
    const input = page.getByTestId('single-device-variable-thermostatSetpoint')
    const create = page.getByTestId('single-device-create')
    await input.fill('29')
    let writes = 0
    let injected = false
    page.on('request', request => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/board/nodes') writes += 1
    })
    // Change one outgoing value, then let the real backend validate it and return its own error.
    await page.route('**/api/board/nodes', async route => {
      if (injected || route.request().method() !== 'POST') { await route.continue(); return }
      const payload = route.request().postDataJSON()
      expect(payload.devices).toHaveLength(1)
      const variable = payload.devices[0].variables.find((item: { name: string }) => item.name === 'thermostatSetpoint')
      expect(variable.value).toBe('29')
      variable.value = '101'
      injected = true
      await route.continue({ postData: JSON.stringify(payload) })
    })
    try {
      const rejected = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/board/nodes')
      await create.click()
      const response = await rejected
      expect(response.status()).toBe(422)
      const body = await response.json()
      expect(body.code).toBe(422)
      expect(Object.keys(body.data.errors).length).toBeGreaterThan(0)
      await testInfo.attach('request-value-injection-real-422.json', { contentType: 'application/json',
        body: JSON.stringify({ faultInjected: true, originalValue: '29', sentValue: '101', response: body }) })
      const failure = page.getByTestId('single-device-save-failure')
      await expect(failure).toBeVisible()
      await expect(failure).toBeFocused()
      await expect(failure.getByTestId('failure-fields')).toBeInViewport({ ratio: 1 })
      await expect(create).toHaveCount(1)
      await expect(create).toBeInViewport({ ratio: 1 })
      await expect.poll(() => create.evaluate(element => {
        const box = element.getBoundingClientRect()
        const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
        return hit === element || element.contains(hit)
      }), { message: 'The creation button must remain reachable beside its rejection details.' }).toBe(true)
      const duplicateCause = Object.entries(body.data.errors).some(([field, reason]) =>
        body.message === reason || body.message === `${field}: ${reason}`)
      if (duplicateCause) await expect(failure.getByTestId('failure-cause')).toHaveCount(0)
      else await expect(failure.getByTestId('failure-cause')).toHaveText(body.message)
      for (const [field, reason] of Object.entries(body.data.errors)) {
        await expect(failure.getByTestId('failure-fields').locator('dt').filter({ hasText: field })).toBeVisible()
        await expect(failure.getByTestId('failure-fields').locator('dd').filter({ hasText: String(reason) })).toBeVisible()
      }
      await expect(page.locator('.el-message--error')).toHaveCount(0)
      await expect(input).toHaveValue('29')
      await expect(page.getByTestId('single-device-name')).toHaveValue(name)
      expect(writes).toBe(1)
      expect(await persistedState(request, auth)).toEqual(before)
      await testInfo.attach('single-device-server-field-guidance.png', { body: await page.screenshot(), contentType: 'image/png' })
      await input.fill('28')
      await expect(failure).toHaveCount(0)
      const accepted = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/board/nodes')
      await create.click()
      const mutation = await unwrap(await accepted)
      const after = await persistedState(request, auth)
      expectOnlyCreatedDevice(before, after, mutation, name, '28')
      expect(writes).toBe(2)
    } finally { await page.unroute('**/api/board/nodes') }
  })
}
