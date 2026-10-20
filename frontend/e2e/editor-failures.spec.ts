import type { APIResponse, Locator, Page, Response } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { apiBaseURL, createAuthenticatedUser, expect, test } from './support/auth'

const unwrap = async (response: APIResponse | Response) => {
  expect(response.ok(), await response.text()).toBeTruthy()
  const body = await response.json()
  expect(body.code).toBe(200)
  return body.data
}

const addModeCondition = async (page: Page) => {
  await page.getByTestId('spec-add-condition-a').click()
  await page.getByTestId('spec-condition-device').selectOption('editor_lamp')
  await page.getByTestId('spec-condition-type').selectOption('mode')
  await page.getByTestId('spec-condition-key').selectOption('SwitchState')
  await page.getByTestId('spec-condition-relation').selectOption('=')
  await page.getByTestId('spec-condition-value').selectOption('off')
  await page.getByTestId('spec-condition-save').click()
  await expect(page.getByTestId('spec-condition-dialog')).toBeHidden()
}

const expectExactFields = async (surface: Locator, body: any) => {
  if (Object.entries(body.data.errors).some(([field, reason]) =>
    body.message === reason || body.message === `${field}: ${reason}`)) {
    await expect(surface.getByTestId('failure-cause')).toBeHidden()
  } else {
    await expect(surface.getByTestId('failure-cause')).toHaveText(body.message)
  }
  for (const [field, reason] of Object.entries(body.data.errors)) {
    await expect(surface.getByTestId('failure-fields').locator('dt').filter({ hasText: field })).toBeVisible()
    await expect(surface.getByTestId('failure-fields').locator('dd').filter({ hasText: String(reason) })).toBeVisible()
  }
}

test('narrow rule creation clears its saved draft after the inspector takes focus', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'narrowRule' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  const manifest = JSON.parse(await readFile(path.resolve(
    process.cwd(), '..', 'backend', 'src', 'main', 'resources', 'deviceTemplate', 'Light.json'
  ), 'utf8'))
  const preview = await unwrap(await request.get(`${apiBaseURL}/api/board/replacement-preview`, { headers }))
  await unwrap(await request.post(`${apiBaseURL}/api/board/scene`, { headers, data: {
    impactToken: preview.impactToken,
    scene: { schema: 'iot-verify.board-scene', version: 5,
      templates: [{ name: manifest.Name, manifest }],
      devices: [{ id: 'narrow_lamp', label: 'Hall lamp', templateName: manifest.Name, state: 'off',
        position: { x: 220, y: 210 }, width: 176, height: 128 }],
      environmentVariables: [{ name: 'illuminance', value: '10', trust: 'untrusted', privacy: 'public' }],
      rules: [], specs: [] }
  } }))
  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('locale', 'en')
  }, auth)
  await page.setViewportSize({ width: 375, height: 900 })
  await page.goto('/#/board')
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
  const control = page.getByTestId('control-center')
  await control.getByRole('button', { name: 'Expand Control Center', exact: true }).click()
  await page.getByTestId('control-tab-rules').click()
  await page.locator('#rule-builder-name').fill('Hall automation')
  await page.locator('#rule-source-device').selectOption('narrow_lamp')
  await page.locator('#rule-source-type').selectOption('api')
  await page.locator('#rule-source-api').selectOption('on')
  await page.getByTestId('rule-add-source').click()
  await page.locator('#rule-target-device').selectOption('narrow_lamp')
  await page.locator('#rule-target-action').selectOption('off')
  const response = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/board/rules')
  await page.getByTestId('rule-save').click()
  await unwrap(await response)
  await expect(page.getByTestId('inspector-tab-rules')).toBeVisible()
  await expect(control.locator('.board-panel-body')).toBeHidden()
  await page.getByTestId('system-inspector').getByRole('button', { name: 'Collapse System Inspector', exact: true }).click()
  await control.getByRole('button', { name: 'Expand Control Center', exact: true }).click()
  await expect(page.locator('#rule-builder-name')).toHaveValue('')
  await expect(page.locator('#rule-builder-name')).toBeEditable()
  await expect(page.locator('#rule-target-device')).toHaveValue('')
  await expect(page.getByTestId('rule-save')).toBeDisabled()
  const rules = await unwrap(await request.get(`${apiBaseURL}/api/board/rules`, { headers }))
  expect(rules).toHaveLength(1)
  expect(rules[0].ruleString).toBe('Hall automation')
})

test('Chinese editors retain real concurrent duplicate diagnostics and reconcile only the affected collections', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'editorConflict' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  const snapshot = () => request.get(`${apiBaseURL}/api/board/snapshot`, { headers }).then(unwrap)
  const history = () => request.get(`${apiBaseURL}/api/board/edits/clear-preview`, { headers }).then(unwrap)
  const manifest = JSON.parse(await readFile(path.resolve(
    process.cwd(), '..', 'backend', 'src', 'main', 'resources', 'deviceTemplate', 'Light.json'
  ), 'utf8'))
  manifest.Name = 'EditorLamp'
  const preview = await unwrap(await request.get(`${apiBaseURL}/api/board/replacement-preview`, { headers }))
  await unwrap(await request.post(`${apiBaseURL}/api/board/scene`, { headers, data: {
    impactToken: preview.impactToken,
    scene: { schema: 'iot-verify.board-scene', version: 5,
      templates: [{ name: manifest.Name, manifest }],
      devices: [{ id: 'editor_lamp', label: 'English Lamp', templateName: manifest.Name, state: 'off',
        position: { x: 220, y: 210 }, width: 176, height: 128 }],
      environmentVariables: [{ name: 'illuminance', value: '10', trust: 'untrusted', privacy: 'public' }],
      rules: [], specs: [] }
  } }))
  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('locale', 'zh-CN')
  }, auth)
  await page.goto('/#/board')
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
  const evidence: unknown[] = []

  for (const kind of ['rule', 'spec'] as const) {
    const collection = kind === 'rule' ? 'rules' : 'specifications'
    const endpoint = kind === 'rule' ? 'rules' : 'specs'
    await page.getByTestId(`inspector-tab-${endpoint}`).click()
    await page.getByTestId(`control-tab-${endpoint}`).click()
    if (kind === 'rule') {
      await page.locator('#rule-builder-name').fill('English draft')
      await page.locator('#rule-source-device').selectOption('editor_lamp')
      await page.locator('#rule-source-type').selectOption('api')
      await page.locator('#rule-source-api').selectOption('on')
      await page.getByTestId('rule-add-source').click()
      await page.locator('#rule-target-device').selectOption('editor_lamp')
      await page.locator('#rule-target-action').selectOption('on')
    } else {
      await page.getByTestId('spec-template-select').selectOption('3')
      await addModeCondition(page)
    }

    let payload: any = null
    let release!: () => void
    const gate = new Promise<void>(resolve => { release = resolve })
    let heldPosts = 0
    const routeURL = `**/api/board/${endpoint}`
    // Delay only this editor's first POST. A real second API caller wins the race after pre-check;
    // the editor POST and all response bodies still come from the real backend unchanged.
    await page.route(routeURL, async route => {
      if (route.request().method() !== 'POST' || heldPosts > 0) return route.continue()
      heldPosts += 1
      payload = route.request().postDataJSON()
      await gate
      await route.continue()
    })
    try {
      const rejection = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === `/api/board/${endpoint}`)
      await page.getByTestId(kind === 'rule' ? 'rule-save' : 'spec-create').click()
      await expect.poll(() => payload !== null, { message: 'Editor POST must actually be pending' }).toBe(true)
      const external = await unwrap(await request.post(`${apiBaseURL}/api/board/${endpoint}`, { headers,
        data: kind === 'rule' ? { ...payload, ruleString: 'Existing English automation' }
          : { ...payload, id: 'external_spec' }
      }))
      const externalId = String(external.affectedItem.id)
      const before = await snapshot()
      const historyBefore = await history()
      release()
      const response = await rejection
      const body = await response.json()
      const rejectedSnapshot = await snapshot()
      const rejectedHistory = await history()
      await testInfo.attach(`${kind}-actual-duplicate-rejection`, { contentType: 'application/json',
        body: Buffer.from(JSON.stringify({ status: response.status(), body, before, rejectedSnapshot,
          historyBefore, rejectedHistory, race: 'Real API write while the real editor POST is held' })) })
      expect(response.status()).toBe(409)
      expect(body.data.reasonCode).toBe(kind === 'rule' ? 'DUPLICATE_RULE' : 'DUPLICATE_SPECIFICATION')
      expect(body.message).not.toMatch(/[\u3400-\u9fff]/)
      const failure = page.getByTestId(`${kind}-save-failure`)
      await expect(failure).toBeVisible()
      await expectExactFields(failure, body)
      await expect(failure.getByTestId('failure-next-step')).toContainText(kind === 'rule' ? '目标动作' : '检查条件')
      await expect(failure.getByTestId('failure-fields')).toBeInViewport({ ratio: 1 })
      await expect(failure.getByTestId('failure-next-step')).toBeInViewport({ ratio: 1 })
      await testInfo.attach(`${kind}-editor-duplicate-guidance`, {
        contentType: 'image/png', body: await page.screenshot()
      })
      await expect(page.getByTestId('system-inspector').locator(`[data-${kind}-id="${externalId}"]`)).toHaveCount(1)
      await expect(page.getByTestId('board-undo')).toBeEnabled()
      expect(rejectedSnapshot).toEqual(before)
      expect(rejectedHistory).toEqual(historyBefore)
      if (kind === 'rule') {
        await expect(page.getByTestId('rule-builder')).toBeVisible()
        await expect(page.locator('#rule-builder-name')).toHaveValue('English draft')
        await expect(page.locator('#rule-target-action')).toHaveValue('on')
        await page.locator('#rule-target-action').selectOption('off')
      } else {
        await expect(page.getByTestId('spec-template-select')).toHaveValue('3')
        await expect(page.getByTestId('spec-description')).toContainText('English Lamp')
        await page.getByTestId('control-section-specs').getByRole('button', { name: '编辑条件 1', exact: true }).click()
        await page.getByTestId('spec-condition-value').selectOption('on')
        await page.getByTestId('spec-condition-save').click()
      }
      await expect(failure).toBeHidden()
      const success = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === `/api/board/${endpoint}`)
      await page.getByTestId(kind === 'rule' ? 'rule-save' : 'spec-create').click()
      const saved = await unwrap(await success)
      const after = await snapshot()
      expect(after[collection]).toHaveLength(before[collection].length + 1)
      expect(after[collection].slice(0, before[collection].length)).toEqual(before[collection])
      expect(after[collection].at(-1)).toEqual(saved.affectedItem)
      expect(after).toEqual({ ...before, [collection]: after[collection] })
      expect(heldPosts).toBe(1)
      evidence.push({ kind, race: 'Real API write between completed pre-check and delayed real editor POST',
        rejection: { status: response.status(), message: body.message, ...body.data },
        before, after, rejectedWriteAndHistoryUnchanged: true, onlySpecifiedCollectionAppended: true })
    } finally {
      release()
      await page.unrouteAll({ behavior: 'wait' })
    }
  }
  await testInfo.attach('actual-concurrent-editor-conflicts', { contentType: 'application/json',
    body: Buffer.from(JSON.stringify(evidence)) })
})

test('Chinese editors retain real server range rejections without losing a locally valid draft', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'editorRange' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  const snapshot = () => request.get(`${apiBaseURL}/api/board/snapshot`, { headers }).then(unwrap)
  const history = () => request.get(`${apiBaseURL}/api/board/edits/clear-preview`, { headers }).then(unwrap)
  const manifests = await Promise.all(['Temperature Sensor', 'Light'].map(async name =>
    JSON.parse(await readFile(path.resolve(process.cwd(), '..', 'backend', 'src', 'main', 'resources',
      'deviceTemplate', `${name}.json`), 'utf8'))))
  const preview = await unwrap(await request.get(`${apiBaseURL}/api/board/replacement-preview`, { headers }))
  await unwrap(await request.post(`${apiBaseURL}/api/board/scene`, { headers, data: {
    impactToken: preview.impactToken,
    scene: { schema: 'iot-verify.board-scene', version: 5,
      templates: manifests.map(manifest => ({ name: manifest.Name, manifest })),
      devices: [
        { id: 'numeric_temp', label: 'English Thermometer', templateName: 'Temperature Sensor',
          position: { x: 100, y: 210 }, width: 176, height: 128 },
        { id: 'numeric_lamp', label: 'English Lamp', templateName: 'Light', state: 'off',
          position: { x: 330, y: 210 }, width: 176, height: 128 }
      ],
      environmentVariables: [
        { name: 'temperature', value: '20', trust: 'untrusted', privacy: 'public' },
        { name: 'illuminance', value: '10', trust: 'untrusted', privacy: 'public' }
      ], rules: [], specs: [] }
  } }))
  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('locale', 'zh-CN')
  }, auth)
  await page.goto('/#/board')
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })

  for (const kind of ['rule', 'spec'] as const) {
    const collection = kind === 'rule' ? 'rules' : 'specifications'
    const endpoint = kind === 'rule' ? 'rules' : 'specs'
    await page.getByTestId(`inspector-tab-${endpoint}`).click()
    await page.getByTestId(`control-tab-${endpoint}`).click()
    if (kind === 'rule') {
      await page.locator('#rule-builder-name').fill('English numeric draft')
      await page.locator('#rule-source-device').selectOption('numeric_temp')
      await page.locator('#rule-source-type').selectOption('variable')
      await page.locator('#rule-source-variable').selectOption('temperature')
      await page.locator('#rule-source-condition').selectOption('=')
      await page.locator('#rule-source-value').fill('20')
      await page.getByTestId('rule-add-source').click()
      await page.locator('#rule-target-device').selectOption('numeric_lamp')
      await page.locator('#rule-target-action').selectOption('on')
    } else {
      await page.getByTestId('spec-template-select').selectOption('3')
      await page.getByTestId('spec-add-condition-a').click()
      await page.getByTestId('spec-condition-device').selectOption('numeric_temp')
      await page.getByTestId('spec-condition-type').selectOption('variable')
      await page.getByTestId('spec-condition-key').selectOption('temperature')
      await page.getByTestId('spec-condition-variable-source-reported').click()
      await page.getByTestId('spec-condition-relation').selectOption('=')
      await page.getByTestId('spec-condition-value').fill('20')
      await page.getByTestId('spec-condition-save').click()
    }
    const before = await snapshot()
    const historyBefore = await history()
    const routePattern = `**/api/board/${endpoint}`
    let injected = false
    // Preserve local validation while exercising the real server's rejection at the request boundary.
    await page.route(routePattern, async route => {
      if (injected || route.request().method() !== 'POST') { await route.continue(); return }
      const payload = route.request().postDataJSON()
      const conditions = kind === 'rule' ? payload.conditions : payload.aConditions
      expect(conditions).toHaveLength(1)
      expect(conditions[0].value).toBe('20')
      conditions[0].value = '101'
      injected = true
      await route.continue({ postData: JSON.stringify(payload) })
    })
    const rejection = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === `/api/board/${endpoint}`)
    await page.getByTestId(kind === 'rule' ? 'rule-save' : 'spec-create').click()
    const response = await rejection
    await page.unroute(routePattern)
    expect(injected).toBe(true)
    const body = await response.json()
    const rejectedSnapshot = await snapshot()
    const rejectedHistory = await history()
    await testInfo.attach(`${kind}-actual-out-of-range-rejection`, { contentType: 'application/json',
      body: Buffer.from(JSON.stringify({ status: response.status(), body, before, rejectedSnapshot,
        historyBefore, rejectedHistory, originalValue: '20', sentValue: '101',
        operation: 'Submit a locally valid condition and inject an out-of-range request value' })) })
    expect(response.status()).toBe(422)
    expect(body.data.reasonCode).not.toBe(kind === 'rule' ? 'DUPLICATE_RULE' : 'DUPLICATE_SPECIFICATION')
    expect(Object.keys(body.data.errors).some(field => field.endsWith('.value'))).toBe(true)
    expect(Object.values(body.data.errors).join(' ')).toMatch(/0.*100/)
    const failure = page.getByTestId(`${kind}-save-failure`)
    await expect(failure).toBeVisible()
    await expect(failure).toContainText(kind === 'rule' ? '保存规则失败' : '保存规约失败')
    await expectExactFields(failure, body)
    await expect(failure.getByTestId('failure-next-step')).toHaveCount(0)
    await expect(failure.getByTestId('failure-fields')).toBeInViewport({ ratio: 1 })
    await testInfo.attach(`${kind}-numeric-editor-guidance`, {
      contentType: 'image/png', body: await page.screenshot()
    })
    expect(rejectedSnapshot).toEqual(before)
    expect(rejectedHistory).toEqual(historyBefore)
    if (kind === 'rule') {
      await expect(page.locator('#rule-builder-name')).toHaveValue('English numeric draft')
      await expect(page.locator('#rule-target-action')).toHaveValue('on')
      await expect(page.getByTestId('rule-builder')).toContainText('20')
      await page.getByTestId('rule-builder').getByRole('button', { name: '移除', exact: true }).click()
      await page.locator('#rule-source-device').selectOption('numeric_temp')
      await page.locator('#rule-source-type').selectOption('variable')
      await page.locator('#rule-source-variable').selectOption('temperature')
      await page.locator('#rule-source-condition').selectOption('=')
      await page.locator('#rule-source-value').fill('1')
      await page.getByTestId('rule-add-source').click()
    } else {
      await expect(page.getByTestId('spec-template-select')).toHaveValue('3')
      await expect(page.getByTestId('spec-description')).toContainText('20')
      await page.getByTestId('control-section-specs').getByRole('button', { name: '编辑条件 1', exact: true }).click()
      await page.getByTestId('spec-condition-value').fill('1')
      await page.getByTestId('spec-condition-save').click()
    }
    await expect(failure).toBeHidden()
    const success = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === `/api/board/${endpoint}`)
    await page.getByTestId(kind === 'rule' ? 'rule-save' : 'spec-create').click()
    const saved = await unwrap(await success)
    const after = await snapshot()
    expect(after[collection]).toHaveLength(before[collection].length + 1)
    expect(after[collection].slice(0, before[collection].length)).toEqual(before[collection])
    expect(after[collection].at(-1)).toEqual(saved.affectedItem)
    expect(after).toEqual({ ...before, [collection]: after[collection] })
    await testInfo.attach(`${kind}-corrected-numeric-write`, { contentType: 'application/json',
      body: Buffer.from(JSON.stringify({ before, after, history: await history(),
        operation: 'Edit the retained temperature condition to 1, then save without request injection' })) })
  }
})
