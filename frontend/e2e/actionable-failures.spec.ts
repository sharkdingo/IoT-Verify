import { expandCreation } from './support/controlCenter'
import { openRecommendation, collapseRecommendation } from './support/recommendations'
import type { APIRequestContext, APIResponse, Locator, Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
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
