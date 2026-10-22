import path from 'node:path'
import { type APIRequestContext, type Page } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'
import { dismissHintTooltips } from './support/tooltips'

test.setTimeout(180_000)

const readBoard = async (request: APIRequestContext, auth: AuthUser) => {
  const response = await request.get(`${apiBaseURL}/api/board/snapshot`, {
    headers: { Authorization: `Bearer ${auth.token}` }
  })
  expect(response.ok(), await response.text()).toBe(true)
  return (await response.json()).data
}

const check = async (page: Page, violations: number) => {
  await page.getByTestId('open-verification-panel').click()
  await page.getByTestId('verification-mode-sync').click()
  await dismissHintTooltips(page)
  const pending = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/verify', { timeout: 120_000 })
  await page.getByTestId('run-verification').click()
  const response = await pending
  expect(response.ok(), await response.text()).toBe(true)
  const result = (await response.json()).data
  expect(result.modelComplete).toBe(true)
  expect(result.specResults).toHaveLength(3)
  expect(result.specResults.map((row: any) => row.outcome)).toEqual(violations === 1
    ? ['VIOLATED', 'SATISFIED', 'SATISFIED']
    : ['SATISFIED', 'SATISFIED', 'SATISFIED'])
  expect(result.specResults.every((row: any) => row.antecedentReachability === 'REACHABLE')).toBe(true)
  await expect(page.getByTestId('verification-result-dialog')).toBeVisible()
  await expect(page.getByTestId('spec-result-trigger-evidence')).toHaveCount(3)
  return result
}

const openHouseholdScene = async (page: Page, request: APIRequestContext) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'home_demo' })
  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('locale', 'zh-CN')
    localStorage.setItem('iot_verify_theme', 'light')
  }, auth)
  await page.goto('/#/board')
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 60_000 })
  await page.getByTestId('scene-import-file').setInputFiles(path.resolve(
    process.cwd(), '..', 'docs', 'examples', 'household-entry-scene.json'))
  const imported = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/board/scene')
  await page.getByRole('dialog').getByRole('button', { name: '替换场景', exact: true }).click()
  expect((await imported).ok()).toBe(true)
  return auth
}

test('household entrance: explain a real failure, edit in place, retain owner access, and undo', async ({ page, request }, testInfo) => {
  const auth = await openHouseholdScene(page, request)
  const before = await readBoard(request, auth)
  expect(before.rules).toHaveLength(3)
  expect(before.specifications).toHaveLength(3)

  await page.getByTestId('open-ai-assistant').click()
  await page.getByTestId('household-want').fill('离家后，访客到门口可以亮灯。')
  await page.getByTestId('household-avoid').fill('访客不能通过灯光联动打开原本锁着的门。')
  await page.getByTestId('household-preserve').fill('已经通过外部认证的主人请求开门时，门仍可打开。')
  await page.getByTestId('household-draft').click()
  await expect(page.getByTestId('chat-input')).toHaveValue(/不要修改画布/)
  await page.screenshot({ path: testInfo.outputPath('01-household-requirements.png') })
  await page.getByTestId('chat-close').click()
  expect(await readBoard(request, auth)).toEqual(before)

  const baseline = await check(page, 1)
  expect(baseline.traces.length).toBeGreaterThan(0)
  await page.screenshot({ path: testInfo.outputPath('02-risk-found.png') })
  await page.getByTestId('close-verification-result').click()

  const unsafe = before.rules.find((rule: any) => rule.conditions.some((condition: any) =>
    condition.deviceName === 'light_1') && rule.command.deviceName === 'door_1')
  expect(unsafe).toBeTruthy()
  await page.getByTestId('inspector-tab-rules').click()
  await page.getByTestId(`edit-rule-${unsafe.id}`).click()
  const builder = page.getByTestId('rule-builder')
  await expect(builder.locator('#rule-target-action')).toHaveValue('unlock')
  await builder.locator('#rule-source-device').selectOption('entry_1')
  await builder.locator('#rule-source-type').selectOption('variable')
  await builder.locator('#rule-source-variable').selectOption('entryRequest')
  await builder.locator('#rule-source-condition').selectOption('=')
  await builder.locator('#rule-source-value').selectOption('authorized_owner')
  await builder.getByTestId('rule-add-source').click()
  await page.screenshot({ path: testInfo.outputPath('03-reviewed-rule-change.png') })
  const edited = page.waitForResponse(response => response.request().method() === 'PUT'
    && new URL(response.url()).pathname === `/api/board/rules/${unsafe.id}`)
  await builder.getByTestId('rule-save').click()
  expect((await edited).ok()).toBe(true)
  const after = await readBoard(request, auth)
  expect(after.rules.map((rule: any) => rule.id)).toEqual(before.rules.map((rule: any) => rule.id))
  expect(after.specifications).toEqual(before.specifications)
  expect(after.rules.filter((rule: any) => rule.id !== unsafe.id)).toEqual(before.rules.filter((rule: any) => rule.id !== unsafe.id))
  expect(after.rules.find((rule: any) => rule.id === unsafe.id).conditions).toHaveLength(2)
  const repaired = await check(page, 0)
  expect(repaired.specResults.map((row: any) => row.specId)).toEqual(baseline.specResults.map((row: any) => row.specId))
  await page.screenshot({ path: testInfo.outputPath('04-risk-fixed-functions-preserved.png') })
  await page.getByTestId('close-verification-result').click()
  await page.getByTestId('board-undo').click()
  await expect.poll(() => readBoard(request, auth)).toEqual(before)
  await check(page, 1)
  await page.getByTestId('close-verification-result').click()
  await page.getByTestId('board-redo').click()
  await expect.poll(() => readBoard(request, auth)).toEqual(after)
  await check(page, 0)
  await testInfo.attach('verified-demo-results', { contentType: 'application/json',
    body: Buffer.from(JSON.stringify({ baseline, repaired }, null, 2)) })
})

test('live AI household: review intent, apply only the requested guard, then verify preserved functions', async ({ page, request }, testInfo) => {
  test.skip(process.env.RUN_LIVE_AI_E2E !== '1', 'Requires the configured external AI endpoint.')
  test.setTimeout(540_000)
  const auth = await openHouseholdScene(page, request)
  const before = await readBoard(request, auth)
  const readData = async (endpoint: string) => {
    const response = await request.get(`${apiBaseURL}${endpoint}`, {
      headers: { Authorization: `Bearer ${auth.token}` }
    })
    expect(response.ok(), await response.text()).toBe(true)
    return (await response.json()).data
  }
  const send = async (content?: string) => {
    const pending = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/chat/completions', { timeout: 240_000 })
    if (content) await page.getByTestId('chat-input').fill(content)
    await page.getByTestId('chat-send').click()
    const response = await pending
    expect(response.ok()).toBe(true)
    const submitted = response.request().postDataJSON()
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 240_000 })
    const history = await readData(`/api/chat/sessions/${submitted.sessionId}/messages?limit=100`)
    const reply = history.messages.find((message: any) => message.role === 'assistant' && message.turnId === submitted.turnId)
    await testInfo.attach(`assistant-turn-${submitted.turnId}`, { contentType: 'application/json',
      body: Buffer.from(JSON.stringify(reply, null, 2)) })
    expect(reply.executionStatus).toBe('COMPLETED')
    expect(reply.content.trim()).not.toBe('')
    return reply
  }
  await page.getByTestId('open-ai-assistant').click()
  try {
    await page.getByTestId('household-want').fill('离家时访客到门口可以亮灯。')
    await page.getByTestId('household-avoid').fill('访客不能通过灯的联动打开原本锁闭的门。')
    await page.getByTestId('household-preserve').fill('外部认证已通过的主人请求开门时，门仍须正常打开；不要求你实现身份认证。')
    await page.getByTestId('household-draft').click()
    const plan = await send()
    expect(await readBoard(request, auth)).toEqual(before)
    expect(plan.content).toMatch(/访客/)
    expect(plan.content).toMatch(/主人/)
    await page.screenshot({ path: testInfo.outputPath('05-live-ai-requirement-review.png') })
    const applied = await send('确认这个具体修改：仅编辑“灯亮就开门”的规则，在保留原条件和开门动作的基础上，追加入口请求 entryRequest = authorized_owner 的条件。保持其他规则、设备和全部检查要求不变。保存后立即正式验证当前画布，不启用攻击和隐私模型，说明风险是否消除、访客亮灯和主人开门是否仍然保留，以及这些触发条件能否发生。')
    expect(applied.executionTrace.some((step: any) => step.stage === 'TOOL_RESULT'
      && step.toolName === 'manage_rule' && step.outcome === 'USABLE')).toBe(true)
    expect(applied.executionTrace.some((step: any) => step.stage === 'TOOL_RESULT'
      && ['verify_model', 'verify_model_async'].includes(step.toolName) && step.outcome === 'USABLE')).toBe(true)
    const after = await readBoard(request, auth)
    const unsafe = before.rules.find((rule: any) => rule.conditions.some((condition: any) => condition.deviceName === 'light_1')
      && rule.command.deviceName === 'door_1')
    expect(after.specifications).toEqual(before.specifications)
    expect(after.nodes).toEqual(before.nodes)
    expect(after.rules.map((rule: any) => rule.id)).toEqual(before.rules.map((rule: any) => rule.id))
    expect(after.rules.filter((rule: any) => rule.id !== unsafe.id)).toEqual(before.rules.filter((rule: any) => rule.id !== unsafe.id))
    const edited = after.rules.find((rule: any) => rule.id === unsafe.id)
    expect(edited.command).toEqual(unsafe.command)
    expect(edited.conditions).toHaveLength(2)
    expect(edited.conditions).toEqual(expect.arrayContaining(unsafe.conditions))
    expect(edited.conditions).toContainEqual({ deviceName: 'entry_1', attribute: 'entryRequest',
      targetType: 'variable', relation: '=', value: 'authorized_owner' })
    const runs = await readData('/api/verify/runs')
    const run = await readData(`/api/verify/runs/${[...runs].sort((a, b) => b.id - a.id)[0].id}`)
    expect(run.specResults).toHaveLength(3)
    expect(run.specResults.every((spec: any) => spec.outcome === 'SATISFIED'
      && spec.antecedentReachability === 'REACHABLE')).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('06-live-ai-verified-outcome.png') })
    await testInfo.attach('live-ai-verified-run', { contentType: 'application/json', body: Buffer.from(JSON.stringify(run, null, 2)) })
  } finally {
    if (await page.getByTestId('chat-stop').isVisible().catch(() => false)) await page.getByTestId('chat-stop').click()
  }
})
