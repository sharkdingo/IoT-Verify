import { expandCreation } from './support/controlCenter'
import { openRecommendation, collapseRecommendation } from './support/recommendations'
import type { APIRequestContext, APIResponse, Locator, Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { canonicalizeTemplateManifest } from '../src/utils/templateManifestCanonicalization'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'

const unwrap = async (response: APIResponse) => {
  expect(response.ok(), await response.text()).toBeTruthy()
  const body = await response.json()
  expect(body.code).toBe(200)
  return body.data
}

const headersFor = (auth: AuthUser) => ({ Authorization: `Bearer ${auth.token}` })
const persistedState = async (request: APIRequestContext, auth: AuthUser) => {
  const [snapshot, history, runs] = await Promise.all([
    request.get(`${apiBaseURL}/api/board/snapshot`, { headers: headersFor(auth) }).then(unwrap),
    request.get(`${apiBaseURL}/api/board/edits/clear-preview`, { headers: headersFor(auth) }).then(unwrap),
    request.get(`${apiBaseURL}/api/verify/runs`, { headers: headersFor(auth) }).then(unwrap)
  ])
  return { snapshot, history, runs }
}

const prepareBoard = async (request: APIRequestContext, auth: AuthUser) => {
  const manifest = JSON.parse(await readFile(path.resolve(
    process.cwd(), '..', 'backend', 'src', 'main', 'resources', 'deviceTemplate', 'Light.json'
  ), 'utf8'))
  const scene = {
    schema: 'iot-verify.board-scene', version: 6,
    templates: [{ name: 'Light', manifest }],
    devices: [{ id: 'actionable_light', templateName: 'Light', label: '提示测试灯', state: 'off',
      position: { x: 220, y: 210 }, width: 176, height: 128 }],
    environmentVariables: [{ name: 'illuminance', value: '10', trust: 'untrusted', privacy: 'public' }],
    rules: [{ name: '开灯规则', sources: [{ fromId: 'actionable_light', fromApi: 'SwitchState',
      itemType: 'mode', relation: '=', value: 'off' }], toId: 'actionable_light', toApi: 'on' }],
    specs: [{ name: 'Safety requirement', templateId: '3', aConditions: [{ deviceId: 'actionable_light', targetType: 'mode',
      key: 'SwitchState', relation: '=', value: 'on' }], ifConditions: [], thenConditions: [] }]
  }
  const preview = await unwrap(await request.get(`${apiBaseURL}/api/board/replacement-preview`, {
    headers: headersFor(auth)
  }))
  await unwrap(await request.post(`${apiBaseURL}/api/board/scene`, {
    headers: headersFor(auth), data: { impactToken: preview.impactToken, scene }
  }))
  // Keep a non-empty journal so failed imports must preserve its entries and exact clear token.
  await unwrap(await request.patch(`${apiBaseURL}/api/board/nodes/label`, {
    headers: headersFor(auth), params: { nodeId: 'actionable_light' },
    data: { expectedLabel: '提示测试灯', label: '已有测试灯' }
  }))
  return { scene, manifest }
}

const openChineseBoard = async (page: Page, auth: AuthUser) => {
  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('locale', 'zh-CN')
  }, auth)
  await page.goto('/#/board')
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
}

const showDetails = async (surface: Locator) => {
  const details = surface.getByTestId('failure-technical-details')
  await expect(details).toBeVisible()
  await details.locator('summary').click()
  await expect(details).toHaveAttribute('open', '')
  return details
}

const dismissFailure = async (page: Page) => {
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: '确定', exact: true }).click()
  await expect(dialog).not.toBeVisible()
}

test('Chinese scene diagnostics locate syntax and distinguish real duplicate-rule and duplicate-spec conflicts', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'actionableScene' })
  const { scene } = await prepareBoard(request, auth)
  await openChineseBoard(page, auth)
  const before = await persistedState(request, auth)
  let scenePosts = 0
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/board/scene') scenePosts += 1
  })

  await page.getByTestId('scene-import-file').setInputFiles({
    name: 'syntax.json', mimeType: 'application/json', buffer: Buffer.from('{\n "schema" "broken"}')
  })
  const syntax = page.getByRole('dialog', { name: 'JSON 语法错误', exact: true })
  await expect(syntax.getByTestId('failure-summary')).toContainText('第 2 行、第 11 列')
  await expect(syntax.getByTestId('failure-cause')).toBeVisible()
  await expect(syntax.getByTestId('failure-cause')).toContainText(/position 12|line 2 column 11/)
  expect(scenePosts).toBe(0)
  expect(await persistedState(request, auth)).toEqual(before)
  await dismissFailure(page)

  const evidence: unknown[] = []
  for (const kind of ['rule', 'spec'] as const) {
    const candidate = structuredClone(scene)
    if (kind === 'rule') candidate.rules.push(structuredClone(candidate.rules[0]!))
    else candidate.specs.push(structuredClone(candidate.specs[0]!))
    await page.getByTestId('scene-import-file').setInputFiles({
      name: `duplicate-${kind}.json`, mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(candidate))
    })
    const responsePromise = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/board/scene')
    await page.getByRole('dialog', { name: '替换当前场景？', exact: true })
      .getByRole('button', { name: '替换场景', exact: true }).click()
    const response = await responsePromise
    const body = await response.json()
    expect(response.status()).toBe(409)
    expect(body.data.reasonCode).toBe(kind === 'rule' ? 'DUPLICATE_RULE' : 'DUPLICATE_SPECIFICATION')
    const dialog = page.getByRole('dialog', { name: '场景导入冲突', exact: true })
    await expect(dialog.getByTestId('failure-summary')).toContainText(kind === 'rule' ? '完全相同的规则' : '完全相同的规约')
    await expect(dialog.getByTestId('failure-next-step')).toContainText(kind === 'rule' ? '删除重复规则' : '删除重复规约')
    expect(Object.values(body.data.errors)).toContain(body.message)
    await expect(dialog.getByTestId('failure-fields').locator('dd').filter({ hasText: body.message })).toBeVisible()
    await expect(dialog.getByTestId('failure-cause')).not.toBeVisible()
    for (const [field, reason] of Object.entries(body.data.errors)) {
      await expect(dialog.getByTestId('failure-fields').locator('dt').filter({ hasText: field })).toBeVisible()
      await expect(dialog.getByTestId('failure-fields').locator('dd').filter({ hasText: String(reason) })).toBeVisible()
    }
    const after = await persistedState(request, auth)
    expect(after).toEqual(before)
    evidence.push({ kind, status: response.status(), reasonCode: body.data.reasonCode, errors: body.data.errors,
      snapshotUnchanged: JSON.stringify(after.snapshot) === JSON.stringify(before.snapshot),
      historyUnchanged: JSON.stringify(after.history) === JSON.stringify(before.history) })
    await dismissFailure(page)
  }
  expect(scenePosts).toBe(2)
  await testInfo.attach('actual-scene-conflict-diagnostics', {
    contentType: 'application/json', body: Buffer.from(JSON.stringify(evidence))
  })
})

test('Chinese template import keeps parser location and real definition rejection beside file selection', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'actionableTemplate' })
  const { manifest } = await prepareBoard(request, auth)
  await openChineseBoard(page, auth)
  await page.getByTestId('control-tab-templates').click()
  await expandCreation(page, 'templates')
  const input = page.getByTestId('control-section-templates').locator('input[type="file"][accept=".json"]')
  const picker = page.getByTestId('template-import-open')
  await expect(picker).toBeInViewport({ ratio: 1 })
  await picker.click({ trial: true })
  const before = await persistedState(request, auth)
  let templatePosts = 0
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/board/templates') templatePosts += 1
  })
  const chooser = page.waitForEvent('filechooser')
  await picker.focus()
  await picker.press('Enter')
  await (await chooser).setFiles({ name: 'syntax-template.json', mimeType: 'application/json',
    buffer: Buffer.from('{\n "Name" "Broken"}') })
  const syntax = page.getByTestId('template-import-feedback')
  await expect(syntax.getByTestId('failure-summary')).toContainText('第 2 行、第 9 列')
  await expect(syntax.getByTestId('failure-summary')).toBeInViewport({ ratio: 1 })
  await expect(syntax.getByTestId('failure-cause')).toBeVisible()
  await expect(syntax.getByTestId('failure-cause')).toBeInViewport({ ratio: 1 })
  await expect(syntax.getByTestId('failure-cause')).toContainText(/position 10|line 2 column 9/)
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await expect(input).toBeEnabled()
  await expect(picker).toBeInViewport({ ratio: 1 })
  await picker.click({ trial: true })
  await testInfo.attach('template-syntax-inline-diagnostics', {
    contentType: 'image/png', body: await page.screenshot({ fullPage: true })
  })
  expect(templatePosts).toBe(0)

  const responsePromise = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/board/templates')
  await input.setInputFiles({ name: 'invalid-definition.json', mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify({ ...manifest, Name: 'Actionable Schema', Modes: false })) })
  const response = await responsePromise
  const body = await response.json()
  expect(response.status()).toBe(400)
  expect(Object.keys(body.data.errors).length).toBeGreaterThan(0)
  const failure = page.getByTestId('template-import-feedback')
  await expect(failure).toContainText('设备模板定义不合法')
  await expect(failure.getByTestId('failure-summary')).not.toContainText('JSON 文件无效')
  await expect(failure.getByTestId('failure-summary')).toBeInViewport({ ratio: 1 })
  expect(Object.values(body.data.errors)).toContain(body.message)
  await expect(failure.getByTestId('failure-fields').locator('dd').filter({ hasText: body.message })).toBeVisible()
  await expect(failure.getByTestId('failure-fields').locator('dd').filter({ hasText: body.message })).toBeInViewport({ ratio: 1 })
  await expect(failure.getByTestId('failure-cause')).not.toBeVisible()
  for (const [field, reason] of Object.entries(body.data.errors)) {
    await expect(failure.getByTestId('failure-fields').locator('dt').filter({ hasText: field })).toBeVisible()
    await expect(failure.getByTestId('failure-fields').locator('dd').filter({ hasText: String(reason) })).toBeVisible()
  }
  expect(await persistedState(request, auth)).toEqual(before)
  expect(templatePosts).toBe(1)
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await expect(input).toBeEnabled()
  await expect(picker).toBeInViewport({ ratio: 1 })
  await picker.click({ trial: true })
  await testInfo.attach('template-definition-inline-diagnostics', {
    contentType: 'image/png', body: await page.screenshot({ fullPage: true })
  })
  await testInfo.attach('actual-template-schema-diagnostics', {
    contentType: 'application/json', body: Buffer.from(JSON.stringify({ status: response.status(),
      message: body.message, errors: body.data.errors, snapshotAndHistoryUnchanged: true }))
  })
})

test('confirmed template import distinguishes a failed catalog refresh from a rejected or unknown write', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'actionableRead' })
  const { manifest } = await prepareBoard(request, auth)
  await openChineseBoard(page, auth)
  await page.getByTestId('control-tab-templates').click()
  await expandCreation(page, 'templates')
  const input = page.getByTestId('control-section-templates').locator('input[type="file"][accept=".json"]')
  const before = await persistedState(request, auth)
  const reason = 'Injected catalog read failure after a confirmed template import'
  let confirmedPost = false
  let postCount = 0
  let injectedGetCount = 0
  await page.route('**/api/board/templates', async route => {
    if (route.request().method() === 'POST') {
      postCount += 1
      const actual = await route.fetch()
      confirmedPost = actual.status() === 200
      await route.fulfill({ response: actual })
      return
    }
    if (route.request().method() === 'GET' && confirmedPost) {
      injectedGetCount += 1
      await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({
        code: 503, message: reason, data: { errors: { catalog: 'Latest catalog could not be read' } }
      }) })
      return
    }
    await route.continue()
  })
  try {
    const responsePromise = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/board/templates')
    await input.setInputFiles({ name: 'confirmed-template.json', mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify({ ...manifest, Name: 'ActionableConfirmed' })) })
    const response = await responsePromise
    const body = await response.json()
    expect(response.status()).toBe(200)
    expect(body.code).toBe(200)
    const failure = page.getByTestId('template-import-feedback')
    await expect(failure.getByTestId('failure-summary')).toContainText('服务器已确认模板导入成功')
    await expect(failure.getByTestId('failure-summary')).toBeInViewport({ ratio: 1 })
    await expect(failure.getByTestId('failure-summary')).not.toContainText(/拒绝|未知/)
    await expect(failure.getByTestId('failure-cause')).toBeVisible()
    await expect(failure.getByTestId('failure-cause')).toHaveText(reason)
    await expect(failure.getByTestId('failure-cause')).toBeInViewport({ ratio: 1 })
    await expect(failure.getByTestId('failure-fields').locator('dt')).toContainText('catalog')
    await expect(failure.getByTestId('failure-fields').locator('dd')).toContainText('Latest catalog could not be read')
    await expect(page.getByRole('dialog')).not.toBeVisible()
    const retry = page.getByTestId('board-data-load-error').getByRole('button', { name: '重试', exact: true })
    await expect(retry).toBeVisible()
    await expect(retry).toBeEnabled()
    await expect(retry).toBeInViewport({ ratio: 1 })
    await testInfo.attach('confirmed-template-read-failure-with-accessible-retry', {
      contentType: 'image/png', body: await page.screenshot({ fullPage: true })
    })
    const after = await persistedState(request, auth)
    expect(after.snapshot.deviceTemplates).toHaveLength(before.snapshot.deviceTemplates.length + 1)
    expect(after.snapshot.deviceTemplates.filter((template: { id: number }) => template.id === body.data.id))
      .toEqual([body.data])
    expect(after.snapshot.deviceTemplates.filter((template: { id: number }) => template.id !== body.data.id))
      .toEqual(before.snapshot.deviceTemplates)
    expect(after).toEqual({ ...before, snapshot: { ...before.snapshot,
      deviceTemplates: after.snapshot.deviceTemplates } })
    expect(postCount).toBe(1)
    expect(injectedGetCount).toBe(1)
    await testInfo.attach('confirmed-template-post-failed-catalog-read', {
      contentType: 'application/json', body: Buffer.from(JSON.stringify({
        write: { source: 'Actual backend POST forwarded unchanged', status: response.status(), template: body.data },
        read: { source: 'Playwright GET-only 503 injection after confirmed POST', reason },
        postCount, injectedGetCount, otherCollectionsAndHistoryAndRunsUnchanged: true
      }))
    })
    const snapshotRead = page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === '/api/board/snapshot')
    await retry.click()
    expect((await snapshotRead).status()).toBe(200)
    await expect(page.getByTestId('board-data-load-error')).not.toBeVisible()
    await expect(page.getByTestId('template-import-feedback')).not.toBeVisible()
    await page.getByTestId('control-tab-templates').click()
    await expandCreation(page, 'templates')
    await expect(page.getByTestId('control-template-repository')).toContainText('ActionableConfirmed')
    expect(await persistedState(request, auth)).toEqual(after)
    expect(postCount).toBe(1)
    expect(injectedGetCount).toBe(1)
  } finally {
    await page.unrouteAll({ behavior: 'wait' })
  }
})

test('template management exports its imported definition, blocks referenced deletion, and resets with the declared history boundary', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'templateWorkflow' })
  const { manifest } = await prepareBoard(request, auth)
  await openChineseBoard(page, auth)
  await page.getByTestId('control-tab-templates').click()
  await expandCreation(page, 'templates')
  const custom = { ...manifest, Name: 'WorkflowCustom' }
  await page.getByTestId('template-import-file').setInputFiles({ name: 'workflow-template.json',
    mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(custom)) })
  const repository = page.getByTestId('control-template-repository')
  await expect(repository.getByRole('heading', { name: 'WorkflowCustom', exact: true })).toBeVisible()
  const download = page.waitForEvent('download')
  await repository.getByRole('button', { name: '导出: WorkflowCustom', exact: true }).click()
  const exportedPath = await (await download).path()
  expect(exportedPath).not.toBeNull()
  expect(JSON.parse(await readFile(exportedPath!, 'utf8'))).toEqual(canonicalizeTemplateManifest(custom))

  await repository.getByRole('button', { name: '删除模板: Light', exact: true }).click()
  const deletion = page.getByTestId('template-delete-dialog')
  await expect(deletion.getByRole('heading')).toHaveText('删除模板')
  await expect(deletion).toContainText('已有测试灯')
  await expect(deletion.getByTestId('template-delete-confirm')).toBeDisabled()
  await deletion.getByRole('button', { name: '取消', exact: true }).click()
  const before = await persistedState(request, auth)
  await repository.getByRole('button', { name: '删除模板: WorkflowCustom', exact: true }).click()
  await expect(deletion.locator('.iot-dialog__consequence')).toContainText('不可撤销')
  const deletionReply = page.waitForResponse(reply => reply.request().method() === 'POST'
    && /\/api\/board\/templates\/\d+\/delete$/.test(new URL(reply.url()).pathname))
  await deletion.getByTestId('template-delete-confirm').click()
  await unwrap(await deletionReply)
  await expect(deletion).toBeHidden()
  await expect(repository.getByRole('heading', { name: 'WorkflowCustom', exact: true })).toHaveCount(0)
  const afterDeletion = await persistedState(request, auth)
  for (const collection of ['nodes', 'rules', 'specifications', 'environmentVariables']) {
    expect(afterDeletion.snapshot[collection]).toEqual(before.snapshot[collection])
  }
  await expect(page.getByTestId('board-undo')).toBeDisabled()

  await unwrap(await request.patch(`${apiBaseURL}/api/board/nodes/label`, {
    headers: headersFor(auth), params: { nodeId: 'actionable_light' },
    data: { expectedLabel: '已有测试灯', label: '重置后保留的灯' }
  }))
  const beforeReset = await persistedState(request, auth)
  await page.reload()
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
  await page.getByTestId('control-tab-templates').click()
  await expandCreation(page, 'templates')
  await page.getByTestId('reset-default-templates').click()
  const reset = page.getByRole('dialog', { name: '重置默认模板？' })
  await expect(reset.locator('.iot-dialog__consequence')).toContainText('1 条撤销/重做记录')
  await expect(reset).not.toContainText('项模板变化')
  await page.screenshot({ path: testInfo.outputPath('template-reset-impact.png') })
  const resetReply = page.waitForResponse(reply => reply.request().method() === 'POST'
    && new URL(reply.url()).pathname === '/api/board/templates/defaults/reset')
  await reset.getByTestId('default-template-reset-confirm').click()
  const committed = await unwrap(await resetReply)
  await expect(reset).toBeHidden()
  const afterReset = await persistedState(request, auth)
  expect(afterReset.snapshot.nodes[0].label).toBe('重置后保留的灯')
  expect(afterReset.snapshot.rules).toEqual(beforeReset.snapshot.rules)
  expect(afterReset.snapshot.specifications).toEqual(beforeReset.snapshot.specifications)
  expect(afterReset.snapshot.environmentVariables).toEqual(committed.environmentVariables)
  await expect(page.getByTestId('board-undo')).toBeDisabled()
})

test('device JSON diagnostics preserve pasted and file inputs while allowing correction in place', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'actionableDevice' })
  await prepareBoard(request, auth)
  await openChineseBoard(page, auth)
  await page.getByTestId('control-tab-devices').click()
  await expandCreation(page, 'devices')
  await page.getByTestId('device-create-mode-import').click()
  const before = await persistedState(request, auth)
  const source = '\n\n [\n {"template" "Alarm"}\n ]'
  const actualCause = await page.evaluate(source => {
    try { JSON.parse(source) } catch (error) { return (error as SyntaxError).message }
    throw new Error('The diagnostic fixture must be invalid JSON')
  }, source)
  let nodePosts = 0
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/board/nodes') nodePosts += 1
  })
  const input = page.getByTestId('device-import-text')
  const file = page.getByTestId('device-import-file')
  const preview = page.getByTestId('device-import-preview')
  const syntaxFailure = page.getByTestId('device-import-syntax-failure')
  for (const entry of ['paste', 'file'] as const) {
    await page.getByTestId(`device-import-source-${entry}`).check()
    if (entry === 'paste') await input.fill(source)
    else await file.setInputFiles({ name: 'devices.json', mimeType: 'application/json', buffer: Buffer.from(source) })
    await expect(syntaxFailure).toContainText('第 4 行、第 14 列')
    await expect(syntaxFailure.getByTestId('failure-cause')).toBeVisible()
    await expect(syntaxFailure.getByTestId('failure-cause')).toHaveText(actualCause)
    await expect(syntaxFailure.getByTestId('failure-cause')).toBeInViewport({ ratio: 1 })
    await expect(syntaxFailure.getByTestId('failure-next-step')).not.toBeVisible()
    await expect(preview).not.toBeVisible()
    if (entry === 'paste') {
      await expect(file).toHaveCount(0)
      await expect(input).toHaveValue(source)
      await input.scrollIntoViewIfNeeded()
      await expect(input).toBeInViewport({ ratio: 1 })
    } else {
      await expect(file).toBeEnabled()
      await expect(input).toHaveCount(0)
      await expect(page.getByTestId('device-import-file-name')).toHaveText('devices.json')
    }
    await expect(page.getByTestId('device-import-create')).toBeDisabled()
    await expect(page.getByRole('dialog')).not.toBeVisible()
    expect(nodePosts).toBe(0)
    expect(await persistedState(request, auth)).toEqual(before)
  }
  await testInfo.attach('device-json-inline-diagnostics', {
    contentType: 'image/png', body: await page.screenshot({ fullPage: true })
  })
  const corrected = JSON.stringify([{ templateName: 'Light', label: '修正设备' }])
  await file.setInputFiles({ name: 'devices.json', mimeType: 'application/json', buffer: Buffer.from(corrected) })
  await expect(input).toHaveCount(0)
  await expect(page.getByTestId('device-import-file-name')).toHaveText('devices.json')
  await expect(preview).toContainText('修正设备')
  await expect(syntaxFailure).not.toBeVisible()
  await expect(page.getByTestId('device-import-create')).toBeEnabled()
  expect(nodePosts).toBe(0)
  expect(await persistedState(request, auth)).toEqual(before)
  await testInfo.attach('device-json-correction-without-submission', {
    contentType: 'application/json', body: Buffer.from(JSON.stringify({ entries: ['paste', 'file'],
      actualCause, nodePosts, snapshotAndHistoryAndRunsUnchanged: true, correctedFileCanBeSubmitted: true }))
  })
})

// These faults exercise presentation of terminal failures; they make no external AI or NuSMV call.
test('injected terminal recommendation and verification failures retain Chinese guidance and raw details', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'actionableFault' })
  await prepareBoard(request, auth)
  await openChineseBoard(page, auth)
  const before = await persistedState(request, auth)
  const intercepted: string[] = []
  const reason = 'Injected terminal dependency failure: diagnostic retained'
  const faults: Record<string, string> = {
    '/api/board/rules/recommend': 'rule', '/api/board/devices/recommend': 'device',
    '/api/board/specs/recommend': 'spec', '/api/verify': 'verification'
  }
  await page.route('**/api/**', async route => {
    const endpoint = new URL(route.request().url()).pathname
    if (route.request().method() !== 'POST' || !faults[endpoint]) return route.continue()
    intercepted.push(endpoint)
    await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({
      code: 503, message: reason, data: { reasonCode: 'INJECTED_TERMINAL_FAILURE',
        errors: { 'request.context': 'Injected field diagnostic' } }
    }) })
  })
  try {
    for (const kind of ['rule', 'device', 'spec'] as const) {
      await openRecommendation(page, kind)
      const panel = page.getByTestId(`${kind}-recommendation-panel`)
      await panel.getByTestId(`generate-${kind}-recommendations`).click()
      const failure = panel.getByTestId(`${kind}-recommendation-error`)
      await expect(failure).toBeVisible()
      await expect(failure.getByTestId('failure-cause')).toBeVisible()
      await expect(failure.getByTestId('failure-cause')).toHaveText(reason)
      await expect(failure.getByTestId('failure-fields').locator('dt')).toContainText('request.context')
      await expect((await showDetails(failure)).locator('li').filter({ hasText: 'HTTP 503' })).toBeVisible()
      await expect(panel.getByText('暂无推荐', { exact: true })).not.toBeVisible()
      expect(await persistedState(request, auth)).toEqual(before)
      await expect(failure).toBeVisible()
      await collapseRecommendation(page, kind)
    }
    await page.getByTestId('open-verification-panel').click()
    await page.getByTestId('verification-mode-sync').click()
    await page.getByTestId('run-verification').click()
    const result = page.getByTestId('verification-result-dialog')
    await expect(result).toBeVisible()
    await expect(result.getByRole('heading', { name: '验证失败', exact: true })).toBeVisible()
    await expect(result.locator('.iot-dialog__subtitle')).toHaveCount(0)
    await expect(result.getByRole('dialog')).toHaveClass(/iot-dialog--danger/)
    await expect(result.getByTestId('failure-cause')).toHaveCount(0)
    await expect(result.getByTestId('failure-fields')).toHaveCount(0)
    await expect(result.getByTestId('failure-next-step')).toContainText('请查看技术详情')
    const details = await showDetails(result)
    await expect(details.locator('li').filter({ hasText: reason })).toHaveText(reason)
    await expect(details.locator('li').filter({ hasText: 'request.context: Injected field diagnostic' }))
      .toHaveText('request.context: Injected field diagnostic')
    await expect(details.locator('li').filter({ hasText: 'HTTP 503' })).toBeVisible()
    expect(await persistedState(request, auth)).toEqual(before)
    expect(intercepted).toEqual(Object.keys(faults))
    await testInfo.attach('injected-terminal-failure-diagnostics', { contentType: 'application/json',
      body: Buffer.from(JSON.stringify({ faultSource: 'Playwright route injection; no external AI or NuSMV call',
        intercepted, snapshotAndHistoryAndRunsUnchanged: true })) })
  } finally {
    await page.unrouteAll({ behavior: 'wait' })
  }
})

test('recommendation review stays readable in the control center at short and wide sizes', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'recommendReview' })
  await prepareBoard(request, auth)
  await openChineseBoard(page, auth)
  await page.route('**/api/board/**/recommend**', route => route.fulfill({
    status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503,
      message: 'Injected recommendation review failure', data: { errors: Object.fromEntries(
        Array.from({ length: 12 }, (_, index) => [`candidate[${index}].condition`, 'Retained condition diagnostic for review.'])) }
    })
  }))
  for (const kind of ['rule', 'device', 'spec'] as const) {
    await page.setViewportSize({ width: 1920, height: 1080 })
    await expect(page.getByTestId('board-root')).not.toHaveClass(/is-narrow-layout/)
    await openRecommendation(page, kind)
    const panel = page.getByTestId(`${kind}-recommendation-panel`)
    await panel.getByTestId(`generate-${kind}-recommendations`).click()
    await expect(panel.getByTestId(`${kind}-recommendation-error`)).toBeVisible()
    for (const size of [{ width: 1920, height: 1080 }, { width: 375, height: 480 }]) {
      await page.setViewportSize(size)
      // Resize delivery can precede the Board listener that collapses/restores its panels.
      if (size.width < 1024) await expect(page.getByTestId('board-root')).toHaveClass(/is-narrow-layout/)
      else await expect(page.getByTestId('board-root')).not.toHaveClass(/is-narrow-layout/)
      await openRecommendation(page, kind)
      expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
      expect(await panel.evaluate(element => getComputedStyle(element).overflowY)).toBe('visible')
      const lastError = panel.getByTestId('failure-fields').locator('dd').last()
      await lastError.scrollIntoViewIfNeeded()
      await expect(lastError).toBeInViewport({ ratio: 1 })
    }
    await testInfo.attach(`${kind}-short-review`, { contentType: 'image/png', body: await page.screenshot() })
    await collapseRecommendation(page, kind)
  }
})
