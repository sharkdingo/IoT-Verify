import { randomUUID } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import type { APIRequestContext } from '@playwright/test'
import {
  apiBaseURL,
  createAuthenticatedUser,
  expect,
  test
} from './support/auth'

const liveAiEnabled = process.env.RUN_LIVE_AI_E2E === '1'

const bilingualRuleScenes = [
  {
    id: 'api-event', templates: ['Light', 'Light'],
    condition: { attribute: 'on', targetType: 'api' }, action: 'off',
    en: 'When Source receives the on API event, turn Target off. Use an API signal condition without relation or value.',
    zh: '当 Source 收到 on API 事件时，关闭 Target。使用 API 信号条件，不填写 relation 和 value。'
  },
  {
    id: 'numeric-condition', templates: ['Temperature Sensor', 'Air Conditioner'],
    condition: { attribute: 'temperature', targetType: 'variable', relation: '>=', value: '30' }, action: 'cool',
    en: 'When Source temperature is at least 30, set Target to cool. Use one variable condition with relation >= and string value 30.',
    zh: '当 Source 的温度至少为 30 时，将 Target 设为 cool。仅使用一个变量条件，关系为 >=，字符串值为 30。'
  },
  {
    id: 'enum-condition', templates: ['Smoke Sensor', 'Alarm'],
    condition: { attribute: 'smoke', targetType: 'variable', relation: '=', value: 'detected' }, action: 'siren',
    en: 'When Source smoke equals detected, activate the siren API on Target. Use one variable condition.',
    zh: '当 Source 的 smoke 等于 detected 时，调用 Target 的 siren API。仅使用一个变量条件。'
  },
  {
    id: 'working-state', templates: ['Door', 'Light'],
    condition: { attribute: 'state', targetType: 'state', relation: '=', value: 'unlocked' }, action: 'on',
    en: 'When Source working state is unlocked, turn Target on. Use targetType state and attribute state, not an API or mode condition.',
    zh: '当 Source 的工作状态为 unlocked 时，打开 Target。条件 targetType 为 state、attribute 为 state，不使用 API 或模式条件。'
  },
  {
    id: 'mode-condition', templates: ['Door', 'Alarm'],
    condition: { attribute: 'LockState', targetType: 'mode', relation: '=', value: 'unlocked' }, action: 'strobe',
    en: 'When Source LockState mode equals unlocked, activate Target strobe. Use targetType mode and attribute LockState, not a working-state or API condition.',
    zh: '当 Source 的 LockState 模式等于 unlocked 时，调用 Target 的 strobe。条件 targetType 为 mode、attribute 为 LockState，不使用工作状态或 API 条件。'
  },
  {
    id: 'content-transfer', templates: ['Mobile Phone', 'Email'],
    condition: { attribute: 'location', targetType: 'variable', relation: '=', value: 'away' }, action: 'send mail',
    contentDevice: 'live_source', content: 'photo',
    en: 'When Source location equals away, send mail on Target with the private photo content from Source. Set contentDevice to live_source and content to photo.',
    zh: '当 Source 的 location 等于 away 时，通过 Target 发送邮件，携带 Source 的私有 photo 内容。contentDevice 为 live_source，content 为 photo。'
  }
]

const readBoardCollection = async (
  request: APIRequestContext,
  headers: Record<string, string>,
  path: 'nodes' | 'rules' | 'specs'
): Promise<unknown[]> => {
  const response = await request.get(`${apiBaseURL}/api/board/${path}`, { headers })
  expect(response.ok(), await response.text()).toBeTruthy()
  const body = await response.json()
  expect(body.code, JSON.stringify(body)).toBe(200)
  expect(Array.isArray(body.data), JSON.stringify(body)).toBeTruthy()
  return body.data
}

test.describe('live AI full-stack audit', () => {
  test.skip(!liveAiEnabled, 'Set RUN_LIVE_AI_E2E=1 to call the configured external model endpoint.')
  test.setTimeout(360_000)

  for (const scene of bilingualRuleScenes) {
    for (const language of ['en', 'zh-CN'] as const) {
      test(`bilingual rule flow: ${scene.id} (${language})`, async ({ page, request }, testInfo) => {
        const auth = await createAuthenticatedUser(request, { usernamePrefix: 'liverounds' })
        const headers = { Authorization: `Bearer ${auth.token}` }
        const readData = async (endpoint: string) => {
          const response = await request.get(`${apiBaseURL}/api/board/${endpoint}`, { headers })
          expect(response.ok(), await response.text()).toBeTruthy()
          const body = await response.json()
          expect(body.code, JSON.stringify(body)).toBe(200)
          return body.data
        }
        const manifests = await Promise.all(scene.templates.map(name => readFile(path.resolve(process.cwd(), '..',
          'backend', 'src', 'main', 'resources', 'deviceTemplate', `${name}.json`), 'utf8').then(JSON.parse)))
        const environment = new Map<string, object>()
        for (const manifest of manifests) {
          for (const variable of manifest.InternalVariables ?? []) {
            if (variable.IsInside) continue
            environment.set(variable.Name, { name: variable.Name,
              value: String(variable.Values?.[0] ?? variable.LowerBound), trust: variable.Trust, privacy: variable.Privacy })
          }
        }
        const preview = await readData('replacement-preview')
        const seeded = await request.post(`${apiBaseURL}/api/board/scene`, { headers, data: {
          impactToken: preview.impactToken,
          scene: { schema: 'iot-verify.board-scene', version: 5,
            templates: [...new Map(manifests.map(manifest => [manifest.Name, { name: manifest.Name, manifest }])).values()],
            devices: manifests.map((manifest, index) => ({ id: index === 0 ? 'live_source' : 'live_target',
              templateName: manifest.Name, label: index === 0 ? 'Source' : 'Target', state: manifest.InitState,
              position: { x: 220 + index * 220, y: 210 }, width: 176, height: 128 })),
            environmentVariables: [...environment.values()], rules: [], specs: [] }
        } })
        expect(seeded.ok(), await seeded.text()).toBeTruthy()
        expect((await seeded.json()).code).toBe(200)
        await page.addInitScript(auth => {
          localStorage.setItem('iot_verify_token', auth.token)
          localStorage.setItem('iot_verify_user', JSON.stringify(auth))
          localStorage.setItem('locale', 'en')
          localStorage.setItem('iot_verify_theme', 'light')
        }, auth)
        await page.goto('/#/board')
        await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 60_000 })
        if (language === 'zh-CN') await page.getByRole('button', { name: '切换到中文', exact: true }).click()
        await expect(page.locator('html')).toHaveAttribute('lang', language)
        const before = await readData('snapshot')
        await page.getByTestId('open-rule-recommendations').click()
        const panel = page.getByTestId('rule-recommendation-panel')
        await panel.locator('input[type="number"]').fill('1')
        await panel.locator('textarea').fill(language === 'en'
          ? `Recommend exactly one rule. ${scene.en} Use only the existing device IDs and capabilities.`
          : `只推荐一条规则。${scene.zh}只使用现有设备 ID 和能力。`)
        const generationResponse = page.waitForResponse(response => response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/api/board/rules/recommend', { timeout: 240_000 })
        await panel.getByTestId('generate-rule-recommendations').click()
        const generation = await generationResponse
        const body = await generation.json()
        const input = generation.request().postDataJSON()
        await testInfo.attach('configured-provider-result', { contentType: 'application/json',
          body: Buffer.from(JSON.stringify({ scenario: scene.id, language, userId: auth.userId,
            requestId: input.requestId, httpStatus: generation.status(), body })) })
        expect(input.language).toBe(language)
        expect(generation.ok(), JSON.stringify(body)).toBeTruthy()
        expect(body.code, JSON.stringify(body)).toBe(200)
        expect(body.data.recommendations, JSON.stringify(body)).toHaveLength(1)
        expect(body.data.count).toBe(1)
        expect(body.data.validatedCount).toBe(1)
        expect(body.data.inspectedCount).toBe(body.data.validatedCount + body.data.filteredCount)
        const candidate = body.data.recommendations[0]
        expect(candidate.conditions).toHaveLength(1)
        expect(candidate.conditions[0]).toMatchObject({ deviceId: 'live_source', ...scene.condition })
        if (scene.condition.targetType === 'api') {
          expect(candidate.conditions[0].relation).toBeUndefined()
          expect(candidate.conditions[0].value).toBeUndefined()
        }
        expect(candidate.command).toMatchObject({ deviceId: 'live_target', action: scene.action })
        expect(candidate.command.contentDevice ?? null).toBe(scene.contentDevice ?? null)
        expect(candidate.command.content ?? null).toBe(scene.content ?? null)
        if (scene.content) expect(candidate.command.contentPrivacy).toBe('private')
        for (const text of [candidate.name, candidate.reason]) {
          expect(text).toEqual(expect.any(String))
          expect(/\p{Script=Han}/u.test(text)).toBe(language === 'zh-CN')
        }
        await expect(panel.getByTestId('rule-recommendation-description')).toContainText('Source')
        await expect(panel.getByTestId('rule-recommendation-description')).toContainText('Target')
        await expect(panel.getByTestId('rule-recommendations-unverified')).toBeVisible()
        const details = panel.locator('details').filter({ has: page.locator('summary').filter({
          hasText: /^\s*(?:info\s+)?(?:View Details|查看详情)\s*$/ }) })
        await details.locator('summary').click()
        await expect(details).toHaveAttribute('open', '')
        await expect(details).toContainText('Source')
        await expect(details).toContainText('Target')
        const rationale = panel.locator('details').filter({ has: page.locator('summary').filter({
          hasText: /^(?:Why it is suggested|推荐依据)$/ }) })
        await rationale.locator('summary').click()
        await expect(rationale.locator('p')).toHaveText(candidate.reason)
        expect(await readData('snapshot')).toEqual(before)
        const saved = page.waitForResponse(response => response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/api/board/rules')
        await panel.getByRole('button', { name: /^(?:add\s+)?(?:Add This Rule|添加此规则)$/ }).click()
        const write = await saved
        const writeBody = await write.json()
        expect(write.ok(), JSON.stringify(writeBody)).toBeTruthy()
        expect(writeBody.code, JSON.stringify(writeBody)).toBe(200)
        await expect(panel.getByRole('button', { name: /^(?:check\s+)?(?:Added to board|已添加到画布)$/i })).toBeDisabled()
        const after = await readData('snapshot')
        expect(after.rules).toHaveLength(1)
        const added = after.rules[0]
        expect(added.conditions).toEqual([{ deviceName: 'live_source', ...scene.condition }])
        expect(added.command).toMatchObject({ deviceName: 'live_target', action: scene.action })
        expect(added.command.contentDevice ?? null).toBe(scene.contentDevice ?? null)
        expect(added.command.content ?? null).toBe(scene.content ?? null)
        expect(after).toEqual({ ...before, rules: [added] })
        await page.screenshot({ path: testInfo.outputPath('rule-flow.png'), fullPage: true })
        const undoResponse = page.waitForResponse(response => response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/api/board/edits/undo')
        await page.getByTestId('board-undo').click()
        const undo = await undoResponse
        const undoBody = await undo.json()
        expect(undo.ok(), JSON.stringify(undoBody)).toBeTruthy()
        expect(undoBody.data.applied).toBe(true)
        expect(await readData('snapshot')).toEqual(before)
        await testInfo.attach('applied-and-undone', { contentType: 'application/json', body: Buffer.from(JSON.stringify({
          scenario: scene.id, language, requestId: input.requestId, writeStatus: write.status(), undoStatus: undo.status(), added
        })) })
      })
    }
  }

  test('real rule and specification candidates apply through the UI without redundant success notices', async ({ page, request }, testInfo) => {
    test.setTimeout(540_000)
    // The shared account fixture deletes this dedicated account in its finally block.
    const auth = await createAuthenticatedUser(request, { usernamePrefix: 'livecandidate' })
    const headers = { Authorization: `Bearer ${auth.token}` }
    const readData = async (endpoint: string) => {
      const response = await request.get(`${apiBaseURL}/api/board/${endpoint}`, { headers })
      expect(response.ok(), await response.text()).toBeTruthy()
      const body = await response.json()
      expect(body.code, JSON.stringify(body)).toBe(200)
      return body.data
    }
    const manifest = JSON.parse(await readFile(path.resolve(process.cwd(), '..', 'backend', 'src', 'main',
      'resources', 'deviceTemplate', 'Light.json'), 'utf8'))
    const preview = await readData('replacement-preview')
    const seeded = await request.post(`${apiBaseURL}/api/board/scene`, { headers, data: { impactToken: preview.impactToken,
      scene: { schema: 'iot-verify.board-scene', version: 5, templates: [{ name: manifest.Name, manifest }],
        devices: ['a', 'b'].map((suffix, index) => ({ id: `live_lamp_${suffix}`, templateName: manifest.Name,
          label: `Hall light ${suffix.toUpperCase()}`, state: 'off', position: { x: 220 + index * 220, y: 210 }, width: 176, height: 128 })),
        environmentVariables: [{ name: 'illuminance', value: '10', trust: 'untrusted', privacy: 'public' }], rules: [], specs: [] }
    } })
    expect(seeded.ok(), await seeded.text()).toBeTruthy()
    expect((await seeded.json()).code).toBe(200)
    await page.addInitScript(auth => {
      localStorage.setItem('iot_verify_token', auth.token)
      localStorage.setItem('iot_verify_user', JSON.stringify(auth))
      localStorage.setItem('locale', 'en')
      localStorage.setItem('iot_verify_theme', 'light')
    }, auth)
    await page.goto('/#/board')
    await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 60_000 })
    const applied: string[] = []
    for (const kind of ['rule', 'spec'] as const) {
      const collection = kind === 'rule' ? 'rules' : 'specifications'
      const endpoint = kind === 'rule' ? 'rules' : 'specs'
      const before = await readData('snapshot')
      await page.getByTestId(`open-${kind}-recommendations`).click()
      const panel = page.getByTestId(`${kind}-recommendation-panel`)
      await panel.locator('input[type="number"]').fill('1')
      await panel.locator('textarea').fill(kind === 'rule'
        ? 'Recommend exactly one automation: when Hall light A receives the on API event, turn Hall light B off. Use only these existing device IDs and template APIs.'
        : 'Recommend exactly one Never safety specification (template 3): Hall light A must never be in the on working state. Use only the existing device ID and working-state condition.')
      const generated = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === `/api/board/${endpoint}/recommend`, { timeout: 240_000 })
      await panel.getByTestId(`generate-${kind}-recommendations`).click()
      const generation = await generated
      const body = await generation.json()
      await testInfo.attach(`${kind}-configured-provider-result`, { contentType: 'application/json', body: Buffer.from(JSON.stringify({
        httpStatus: generation.status(), requestId: generation.request().postDataJSON().requestId, body
      })) })
      expect.soft(generation.ok(), JSON.stringify(body)).toBeTruthy()
      expect.soft(body.code, JSON.stringify(body)).toBe(200)
      expect.soft(body.data?.recommendations, JSON.stringify(body)).toHaveLength(1)
      if (!generation.ok() || body.code !== 200 || body.data?.recommendations?.length !== 1) {
        await panel.getByTestId(`close-${kind}-recommendations`).click()
        continue
      }
      const candidate = body.data.recommendations[0]
      if (kind === 'rule') {
        expect(candidate.conditions).toEqual([expect.objectContaining({
          deviceId: 'live_lamp_a', attribute: 'on', targetType: 'api'
        })])
        expect(candidate.conditions[0].relation).toBeUndefined()
        expect(candidate.conditions[0].value).toBeUndefined()
        expect(candidate.command).toMatchObject({ deviceId: 'live_lamp_b', action: 'off' })
        expect(candidate.command.contentDevice ?? null).toBeNull()
        expect(candidate.command.content ?? null).toBeNull()
      } else {
        expect(candidate.templateId).toBe('3')
        expect(candidate.aConditions).toEqual([expect.objectContaining({
          deviceId: 'live_lamp_a', targetType: 'state', key: 'state', relation: '=', value: 'on'
        })])
        expect(candidate.ifConditions).toEqual([])
        expect(candidate.thenConditions).toEqual([])
      }
      const description = panel.getByTestId(`${kind}-recommendation-description`)
      await expect(description).toBeVisible()
      await expect(description).toContainText('Hall light A')
      if (kind === 'rule') await expect(description).toContainText('Hall light B')
      await expect(panel.getByTestId(`${kind}-recommendations-unverified`)).toBeVisible()
      await expect(panel.getByTestId(`${kind}-candidate-accounting`)).not.toHaveAttribute('open')
      const details = panel.locator('details').filter({
        has: page.locator('summary').filter({ hasText: /^\s*(?:info\s+)?View Details\s*$/i })
      })
      await details.locator('summary').click()
      await expect(details).toHaveAttribute('open', '')
      await expect(details).toContainText('Hall light A')
      if (kind === 'rule') await expect(details).toContainText('Hall light B')
      const rationale = panel.locator('details').filter({
        has: page.locator('summary').filter({ hasText: /^Why it is suggested$/i })
      })
      await rationale.locator('summary').click()
      await expect(rationale.locator('p')).toBeVisible()
      expect(await readData('snapshot')).toEqual(before)
      const saved = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === `/api/board/${endpoint}`)
      await panel.getByRole('button', { name: kind === 'rule'
        ? /^(?:add\s+)?Add This Rule$/ : /^(?:add\s+)?Add This Specification$/ }).click()
      const write = await saved
      const writeBody = await write.json()
      const after = await readData('snapshot')
      const availability = await readData('edits/availability')
      await testInfo.attach(`${kind}-candidate-write`, { contentType: 'application/json', body: Buffer.from(JSON.stringify({
        before, after, availability, write: { httpStatus: write.status(), body: writeBody }
      })) })
      expect(write.ok(), JSON.stringify(writeBody)).toBeTruthy()
      expect(writeBody.code, JSON.stringify(writeBody)).toBe(200)
      await expect(panel.getByRole('button', { name: /^(?:check\s+)?Added to board$/i })).toBeDisabled()
      await expect(panel.getByText('Applied to the canvas. Other recommendations are now out of date; generate again.', { exact: true })).toHaveCount(0)
      await expect(panel.getByText('Other candidates were closed because the board changed. Generate again.', { exact: true })).toHaveCount(0)
      await expect(page.locator('.el-message--success')).toHaveCount(0)
      const screenshot = testInfo.outputPath(`${kind}-candidate-added.png`)
      await page.screenshot({ path: screenshot, fullPage: true })
      await testInfo.attach(`${kind}-candidate-added`, { path: screenshot, contentType: 'image/png' })
      expect(after[collection]).toHaveLength(before[collection].length + 1)
      const added = after[collection].at(-1)
      if (kind === 'rule') {
        expect(added.id).toEqual(expect.any(Number))
        expect(added.createdAt).toEqual(expect.any(String))
        expect(added.conditions).toEqual([{
          deviceName: 'live_lamp_a', attribute: 'on', targetType: 'api'
        }])
        expect(added.command).toMatchObject({ deviceName: 'live_lamp_b', action: 'off' })
        expect(added.command.contentDevice ?? null).toBeNull()
        expect(added.command.content ?? null).toBeNull()
      } else {
        expect(added.id).toEqual(expect.any(String))
        expect(added.templateId).toBe('3')
        expect(added.aConditions).toEqual([expect.objectContaining({
          side: 'a', deviceId: 'live_lamp_a', targetType: 'state', key: 'state', relation: '=', value: 'on'
        })])
        expect(added.ifConditions).toEqual([])
        expect(added.thenConditions).toEqual([])
      }
      expect(after).toEqual({ ...before, [collection]: [...before[collection], added] })
      expect(availability.canUndo).toBe(true)
      const undoResponse = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/board/edits/undo')
      await page.getByTestId('board-undo').click()
      const undo = await undoResponse
      const undoBody = await undo.json()
      const restored = await readData('snapshot')
      await testInfo.attach(`${kind}-candidate-undo`, { contentType: 'application/json', body: Buffer.from(JSON.stringify({
        undo: { httpStatus: undo.status(), body: undoBody }, restored
      })) })
      expect(undo.ok(), JSON.stringify(undoBody)).toBeTruthy()
      expect(undoBody.data.applied).toBe(true)
      expect(restored).toEqual(before)
      applied.push(kind)
      await expect(page.getByTestId('board-undo')).toBeDisabled()
    }
    expect(applied).toEqual(['rule', 'spec'])
  })

  test('returns a validated scene recommendation and streams a tool-backed board answer', async ({ request }) => {
    const auth = await createAuthenticatedUser(request, { usernamePrefix: 'liveai' })
    const headers = { Authorization: `Bearer ${auth.token}` }

    const recommendationResponse = await request.post(
      `${apiBaseURL}/api/board/scenario/recommend?requestId=${randomUUID()}`,
      {
        headers,
        timeout: 240_000,
        data: {
          minDevices: 2,
          minRules: 1,
          minSpecs: 1,
          maxDevices: 3,
          maxRules: 2,
          maxSpecs: 2,
          language: 'en',
          userRequirement: 'Create a compact hallway safety scene with a motion sensor, a light, and one safety specification.'
        }
      }
    )
    expect(recommendationResponse.ok(), await recommendationResponse.text()).toBeTruthy()
    const recommendationBody = await recommendationResponse.json()
    expect(recommendationBody.code, JSON.stringify(recommendationBody)).toBe(200)
    expect(recommendationBody.data.scene).toMatchObject({
      schema: 'iot-verify.board-scene',
      version: 5
    })
    expect(Array.isArray(recommendationBody.data.scene.devices)).toBeTruthy()
    expect(Array.isArray(recommendationBody.data.scene.rules)).toBeTruthy()
    expect(Array.isArray(recommendationBody.data.scene.specs)).toBeTruthy()
    const sceneCounts = {
      devices: recommendationBody.data.scene.devices.length,
      rules: recommendationBody.data.scene.rules.length,
      specs: recommendationBody.data.scene.specs.length
    }
    expect(sceneCounts.devices + sceneCounts.rules + sceneCounts.specs).toBeGreaterThan(0)
    expect(recommendationBody.data.objectiveTargets).toEqual({
      minDevices: 2,
      minRules: 1,
      minSpecs: 1
    })
    expect(Array.isArray(recommendationBody.data.objectiveIssues)).toBeTruthy()
    const expectedObjectiveIssueCodes = [
      ...(sceneCounts.devices < 2
        ? [sceneCounts.devices === 0 ? 'NO_DEVICES' : 'INSUFFICIENT_DEVICES']
        : []),
      ...(sceneCounts.rules < 1 ? ['NO_AUTOMATION_RULES'] : []),
      ...(sceneCounts.specs < 1 ? ['NO_SPECIFICATIONS'] : [])
    ]
    expect(recommendationBody.data.objectiveStatus).toBe(
      expectedObjectiveIssueCodes.length === 0 ? 'COMPLETE' : 'PARTIAL'
    )
    expect(recommendationBody.data.objectiveIssues.map((issue: any) => issue.code)).toEqual(
      expectedObjectiveIssueCodes
    )
    expect(recommendationBody.data.rawCandidateCount).toBeGreaterThanOrEqual(
      recommendationBody.data.inspectedCount)

    const sessionResponse = await request.post(`${apiBaseURL}/api/chat/sessions`, {
      headers,
      data: null
    })
    expect(sessionResponse.ok(), await sessionResponse.text()).toBeTruthy()
    const sessionBody = await sessionResponse.json()
    const sessionId = sessionBody.data.id as string

    const turnId = randomUUID()
    const streamResponse = await request.post(`${apiBaseURL}/api/chat/completions`, {
      headers: {
        ...headers,
        Accept: 'text/event-stream',
        'Content-Type': 'application/json'
      },
      timeout: 240_000,
      data: {
        sessionId,
        turnId,
        content: 'Inspect my current board and report its device, rule, and specification counts. Do not change anything.'
      }
    })
    expect(streamResponse.ok(), await streamResponse.text()).toBeTruthy()
    expect(streamResponse.headers()['content-type']).toContain('text/event-stream')
    const streamText = await streamResponse.text()
    expect(streamText).toContain('CONTEXT_READY')
    expect(streamText).toContain('PLANNING')
    expect(streamText).toContain('TOOL_EXECUTION')
    expect(streamText).toContain('WRITING_RESPONSE')
    const streamFrames = streamText
      .split(/\r?\n/)
      .map(line => line.match(/^\s*data:\s*(.+)\s*$/)?.[1])
      .filter((data): data is string => Boolean(data))
      .map(data => JSON.parse(data))
    expect(streamFrames.some(frame =>
      frame.progress?.stage === 'PLANNING' &&
      Number.isSafeInteger(frame.progress?.round) &&
      frame.progress.round >= 1)).toBeTruthy()
    const boardResults = streamFrames.filter(frame =>
      frame.progress?.stage === 'TOOL_RESULT' &&
      frame.progress?.toolName === 'board_overview' &&
      frame.progress?.outcome === 'USABLE')
    expect(boardResults.length).toBeGreaterThanOrEqual(1)
    const terminalFrames = streamFrames.filter(frame => frame.terminal)
    expect(terminalFrames).toHaveLength(1)
    expect(terminalFrames[0].terminal).toEqual({
      turnId,
      executionStatus: 'COMPLETED'
    })
    expect(streamFrames.at(-1)).toEqual(terminalFrames[0])
    const assistantReply = streamFrames
      .map(frame => typeof frame.content === 'string' ? frame.content : '')
      .join('')
      .trim()
    expect(assistantReply).not.toBe('')

    const historyResponse = await request.get(
      `${apiBaseURL}/api/chat/sessions/${encodeURIComponent(sessionId)}/messages`,
      { headers }
    )
    expect(historyResponse.ok(), await historyResponse.text()).toBeTruthy()
    const historyBody = await historyResponse.json()
    expect(historyBody.code, JSON.stringify(historyBody)).toBe(200)
    expect(Array.isArray(historyBody.data?.messages), JSON.stringify(historyBody)).toBeTruthy()
    expect(historyBody.data.messages.some((message: any) =>
      message.role === 'user' && message.turnId === turnId)).toBeTruthy()
    const terminalHistory = historyBody.data.messages.find((message: any) =>
      message.role === 'assistant' && message.turnId === turnId)
    expect(terminalHistory).toMatchObject({ executionStatus: 'COMPLETED' })
    expect(String(terminalHistory.content || '').trim()).not.toBe('')
    expect(terminalHistory.executionTrace.some((progress: any) =>
      progress.stage === 'TOOL_RESULT' &&
      progress.toolName === 'board_overview' &&
      progress.outcome === 'USABLE')).toBeTruthy()

    expect(await readBoardCollection(request, headers, 'nodes')).toHaveLength(0)
    expect(await readBoardCollection(request, headers, 'rules')).toHaveLength(0)
    expect(await readBoardCollection(request, headers, 'specs')).toHaveLength(0)
  })

  test('an assistant rule creation is journalled and undoable', async ({ page, request }, testInfo) => {
    test.setTimeout(360_000)
    const auth = await createAuthenticatedUser(request, { usernamePrefix: 'liveundo' })
    const H = { Authorization: `Bearer ${auth.token}` }
    const readSnapshot = async () => {
      const response = await request.get(`${apiBaseURL}/api/board/snapshot`, { headers: H })
      expect(response.ok(), await response.text()).toBeTruthy()
      const body = await response.json()
      expect(body.code, JSON.stringify(body)).toBe(200)
      for (const collection of ['nodes', 'rules', 'specifications', 'environmentVariables', 'deviceTemplates']) {
        expect(Array.isArray(body.data?.[collection]), collection).toBeTruthy()
      }
      return body.data
    }

    await page.addInitScript(({ token, user }) => {
      window.localStorage.setItem('iot_verify_token', token)
      window.localStorage.setItem('iot_verify_user', JSON.stringify(user))
      window.localStorage.setItem('locale', 'en')
      window.localStorage.setItem('iot_verify_theme', 'light')
    }, { token: auth.token, user: { userId: auth.userId, phone: auth.phone, username: auth.username } })
    await page.goto('/#/board')
    await expect(page.locator('.iot-board')).toBeVisible({ timeout: 60_000 })

    // The UI confirms the portable scene replacement; the server owns its conversion.
    await page.getByTestId('scene-import-file').setInputFiles(path.resolve(
      process.cwd(), '..', 'docs', 'examples', 'default-climate-conflict-scene.json'))
    await page.getByRole('dialog').getByRole('button', { name: /Replace scene/ }).click()
    await expect(page.getByTestId('board-undo')).toBeDisabled({ timeout: 60_000 })
    await expect.poll(async () => (await readSnapshot()).rules.length, { timeout: 60_000 }).toBeGreaterThan(0)
    const before = await readSnapshot()
    for (const rule of before.rules) {
      expect(rule).toMatchObject({ id: expect.any(Number), createdAt: expect.any(String) })
    }

    // Real model, real tool path.
    const session = await request.post(`${apiBaseURL}/api/chat/sessions`, { headers: H, data: null })
    expect(session.ok(), await session.text()).toBeTruthy()
    const sessionBody = await session.json()
    expect(sessionBody.code, JSON.stringify(sessionBody)).toBe(200)
    const sid = sessionBody.data.id
    const turnId = randomUUID()
    const stream = await request.post(`${apiBaseURL}/api/chat/completions`, {
      headers: { ...H, Accept: 'text/event-stream', 'Content-Type': 'application/json' },
      timeout: 240_000,
      data: {
        sessionId: sid,
        turnId,
        content: 'Add one new automation rule to my board using the manage_rule tool. Any sensible rule using the existing devices is fine. Do not delete or modify anything.'
      }
    })
    expect.soft(stream.ok(), await stream.text()).toBeTruthy()
    expect.soft(stream.headers()['content-type']).toContain('text/event-stream')
    const streamFrames = (await stream.text())
      .split(/\r?\n/)
      .map(line => line.match(/^\s*data:\s*(.+)\s*$/)?.[1])
      .filter((data): data is string => Boolean(data))
      .map(data => JSON.parse(data))
    expect.soft(streamFrames.filter(frame => frame.error)).toHaveLength(0)
    const usableRuleWrite = streamFrames.some(frame => frame.progress?.stage === 'TOOL_RESULT'
      && frame.progress?.toolName === 'manage_rule' && frame.progress?.outcome === 'USABLE')
    expect.soft(usableRuleWrite).toBeTruthy()
    const terminalFrames = streamFrames.filter(frame => frame.terminal)
    const terminal = terminalFrames.length === 1 ? terminalFrames[0].terminal : null
    expect.soft(terminalFrames).toHaveLength(1)
    expect.soft(terminal).toEqual({ turnId, executionStatus: 'COMPLETED' })
    expect.soft(streamFrames.at(-1)).toEqual(terminalFrames[0])
    // The backend must tell the workspace to reload the collection it just changed.
    expect.soft(streamFrames.some(frame => frame.command?.type === 'REFRESH_DATA'
      && frame.command?.payload?.target === 'rule_list')).toBeTruthy()

    // Read authoritative effects even when a later provider request made this turn partial.
    const afterResponse = await request.get(`${apiBaseURL}/api/board/snapshot`, { headers: H })
    const afterBody = await afterResponse.json()
    const availability = await request.get(`${apiBaseURL}/api/board/edits/availability`, { headers: H })
    const availabilityBody = await availability.json()
    const historyResponse = await request.get(
      `${apiBaseURL}/api/chat/sessions/${encodeURIComponent(sid)}/messages`, { headers: H }
    )
    const historyBody = await historyResponse.json()
    await testInfo.attach('assistant-rule-write-state', {
      contentType: 'application/json', body: Buffer.from(JSON.stringify({
        before, stream: { httpStatus: stream.status(), frames: streamFrames },
        after: { httpStatus: afterResponse.status(), body: afterBody },
        availability: { httpStatus: availability.status(), body: availabilityBody },
        history: { httpStatus: historyResponse.status(), body: historyBody }
      }))
    })
    expect(terminalFrames).toHaveLength(1)
    expect(terminal).toMatchObject({ turnId })
    expect(streamFrames.at(-1)).toEqual(terminalFrames[0])
    expect(afterResponse.ok(), await afterResponse.text()).toBeTruthy()
    expect(afterBody.code, JSON.stringify(afterBody)).toBe(200)
    expect(availability.ok(), await availability.text()).toBeTruthy()
    expect(availabilityBody.code, JSON.stringify(availabilityBody)).toBe(200)
    expect(historyResponse.ok(), await historyResponse.text()).toBeTruthy()
    expect(historyBody.code, JSON.stringify(historyBody)).toBe(200)
    const turnHistory = historyBody.data.messages.filter((message: any) => message.turnId === turnId)
    expect(turnHistory.filter((message: any) => message.role === 'user')).toHaveLength(1)
    const terminalHistory = turnHistory.filter((message: any) => message.role === 'assistant')
    expect(terminalHistory).toHaveLength(1)
    expect(terminalHistory[0]).toMatchObject({ sessionId: sid, turnId, executionStatus: terminal.executionStatus })
    expect(String(terminalHistory[0].content || '').trim()).not.toBe('')

    // The contract under test: an assistant edit goes through the journal-recording write path.
    if (usableRuleWrite) {
      expect(terminalHistory[0].executionTrace.some((progress: any) => progress.stage === 'TOOL_RESULT'
        && progress.toolName === 'manage_rule' && progress.outcome === 'USABLE')).toBeTruthy()
      const after = afterBody.data
      expect(after.rules).toHaveLength(before.rules.length + 1)
      const added = after.rules.at(-1)
      expect(Number.isSafeInteger(added.id)).toBe(true)
      expect(added.id).toBeGreaterThan(0)
      expect(added.createdAt).toEqual(expect.any(String))
      expect(added.createdAt).not.toBe('')
      expect(before.rules.map((rule: any) => rule.id)).not.toContain(added.id)
      // Full raw snapshots retain rule IDs/timestamps, original order, and every other collection.
      expect(after).toEqual({ ...before, rules: [...before.rules, added] })
      expect(availabilityBody.data.canUndo).toBe(true)

      const undo = await request.post(`${apiBaseURL}/api/board/edits/undo`, { headers: H })
      const undoBody = await undo.json()
      const restoredResponse = await request.get(`${apiBaseURL}/api/board/snapshot`, { headers: H })
      const restoredBody = await restoredResponse.json()
      await testInfo.attach('assistant-rule-undo-state', {
        contentType: 'application/json', body: Buffer.from(JSON.stringify({
          before, undo: { httpStatus: undo.status(), body: undoBody },
          restored: { httpStatus: restoredResponse.status(), body: restoredBody }
        }))
      })
      expect(undo.ok(), await undo.text()).toBeTruthy()
      expect(undoBody.code, JSON.stringify(undoBody)).toBe(200)
      expect(undoBody.data.applied).toBe(true)
      expect(undoBody.data.rules).toEqual(before.rules)
      expect(restoredResponse.ok(), await restoredResponse.text()).toBeTruthy()
      expect(restoredBody.code, JSON.stringify(restoredBody)).toBe(200)
      expect(restoredBody.data).toEqual(before)
    }
  })

  test('a natural assistant verification and confirmed repair preserves the passing requirements', async ({ page, request }, testInfo) => {
    test.setTimeout(540_000)
    const auth = await createAuthenticatedUser(request, { usernamePrefix: 'liverepair' })
    const headers = { Authorization: `Bearer ${auth.token}` }
    const readData = async (endpoint: string) => {
      const response = await request.get(`${apiBaseURL}${endpoint}`, { headers })
      expect(response.ok(), await response.text()).toBeTruthy()
      const body = await response.json()
      expect(body.code, JSON.stringify(body)).toBe(200)
      return body.data
    }
    let sessionId: string
    const turn = async (content: string | null, status = 'COMPLETED') => {
      const received = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/chat/completions', { timeout: 240_000 })
      if (content === null) {
        await page.getByTestId('chat-protected-confirmation')
          .getByRole('button', { name: '确定', exact: true }).click()
      } else {
        await page.getByTestId('chat-input').fill(content)
        await page.getByTestId('chat-send').click()
      }
      const stream = await received
      expect(stream.ok()).toBeTruthy()
      expect(stream.headers()['content-type']).toContain('text/event-stream')
      const submitted = stream.request().postDataJSON()
      sessionId = submitted.sessionId
      // Chromium need not retain a consumed fetch-SSE body. Durable history owns the terminal evidence.
      await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 240_000 })
      const history = await readData(`/api/chat/sessions/${sessionId}/messages?limit=100`)
      const assistant = history.messages.filter((message: any) => message.role === 'assistant'
        && message.turnId === submitted.turnId)
      expect(assistant).toHaveLength(1)
      expect(assistant[0].executionStatus).toBe(status)
      expect(assistant[0].content.trim()).not.toBe('')
      return assistant[0]
    }
    const hasTool = (message: any, names: string[], outcome = 'USABLE') => message.executionTrace.some(
      (step: any) => step.stage === 'TOOL_RESULT' && names.includes(step.toolName) && step.outcome === outcome)
    const latestRun = async () => {
      const runs = await readData('/api/verify/runs')
      const latest = [...runs].filter(run => run.dataAvailable).sort((a, b) => b.id - a.id)[0]
      expect(latest).toBeTruthy()
      return await readData(`/api/verify/runs/${latest.id}`)
    }
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
    expect((await imported).ok()).toBeTruthy()
    const before = await readData('/api/board/snapshot')
    try {
      await page.getByTestId('open-ai-assistant').click()
      const initial = await turn('请对当前画布做一次正式验证：关闭攻击模型和隐私检查。说明哪些要求满足、哪些违反、每项触发条件是否可达，以及反例说明的问题。不要修改任何设备、规则或规约。')
      expect(hasTool(initial, ['verify_model', 'verify_model_async'])).toBeTruthy()
      const baseline = await latestRun()
      expect(baseline.modelComplete).toBe(true)
      expect(baseline.specResults.filter((spec: any) => spec.outcome === 'SATISFIED')).toHaveLength(2)
      expect(baseline.specResults.filter((spec: any) => spec.outcome === 'VIOLATED')).toHaveLength(1)
      expect(baseline.isAttack).toBe(false)
      expect(baseline.enablePrivacy).toBe(false)
      expect(baseline.specResults.every((spec: any) => spec.antecedentReachability === 'REACHABLE')).toBe(true)
      const originalTrace = (await readData('/api/verify/traces'))
        .find((trace: any) => trace.verificationTaskId === baseline.id)
      expect(originalTrace).toBeTruthy()
      expect(originalTrace.verificationTaskId).toBe(baseline.id)
      const repair = await turn('请针对刚才的问题查找能够通过正式验证的修复方案，保留目前已经满足的全部要求，不修改规约。把可用方案和它们的影响告诉我，暂时不要应用。')
      expect(hasTool(repair, ['fix_violation'])).toBeTruthy()
      expect(await readData('/api/board/snapshot')).toEqual(before)
      const preview = await turn('我选择只移除“灯亮就开门”的危险便利规则这个方案，保留访客亮灯和主人请求开门的规则。请申请它的应用预览，列出将改变的内容，等我确认后再执行。不要改动规约。', 'AWAITING_CONFIRMATION')
      expect(hasTool(preview, ['apply_fix'], 'CONFIRMATION_REQUIRED')).toBeTruthy()
      await expect(page.getByTestId('chat-protected-confirmation')).toBeVisible()
      await expect(page.getByTestId('chat-protected-confirmation').locator('strong')).toHaveText('请确认：应用修复建议')
      const unsafe = before.rules.find((rule: any) => rule.command.deviceName === 'door_1'
        && rule.conditions.some((condition: any) => condition.deviceName === 'light_1'))
      expect(unsafe).toBeTruthy()
      await expect(page.getByTestId('chat-confirmation-target')).toHaveText(unsafe.ruleString)
      await page.screenshot({ path: testInfo.outputPath('live-assistant-repair-preview.png') })
      expect(await readData('/api/board/snapshot')).toEqual(before)
      const applied = await turn(null)
      expect(hasTool(applied, ['apply_fix'])).toBeTruthy()
      await expect(page.getByTestId('chat-protected-confirmation')).toHaveCount(0)
      const after = await readData('/api/board/snapshot')
      expect(after).toEqual({ ...before, rules: before.rules.filter((rule: any) => rule.id !== unsafe.id) })
      expect((await readData('/api/board/edits/availability')).canUndo).toBe(true)
      const finalReply = await turn('请重新正式验证当前画布，攻击和隐私检查仍关闭。说明全部要求现在的结论、触发条件是否可达和任何剩余问题。不要修改任何内容。')
      expect(hasTool(finalReply, ['verify_model', 'verify_model_async'])).toBeTruthy()
      const finalRun = await latestRun()
      expect(finalRun.id).not.toBe(baseline.id)
      expect(finalRun.modelComplete).toBe(true)
      expect(finalRun.isAttack).toBe(false)
      expect(finalRun.enablePrivacy).toBe(false)
      expect(finalRun.specResults).toHaveLength(3)
      expect(finalRun.specResults.every((spec: any) => spec.outcome === 'SATISFIED')).toBe(true)
      expect(finalRun.specResults.every((spec: any) => spec.antecedentReachability === 'REACHABLE')).toBe(true)
      const requirements = (run: any) => {
        expect(run.specResults).toHaveLength(3)
        expect(new Set(run.specResults.map((spec: any) => spec.specId)).size).toBe(3)
        return run.specResults.map((spec: any) => {
          expect(spec).toMatchObject({ specId: expect.any(String), expression: expect.any(String),
            formulaKind: 'CTL', formulaPreview: expect.any(String) })
          for (const key of ['specId', 'expression', 'formulaPreview']) expect(spec[key].trim()).not.toBe('')
          return { specId: spec.specId, expression: spec.expression,
            formulaKind: spec.formulaKind, formulaPreview: spec.formulaPreview }
        }).sort((a: any, b: any) => a.specId.localeCompare(b.specId))
      }
      expect(requirements(finalRun)).toEqual(requirements(baseline))
      const history = await readData(`/api/chat/sessions/${sessionId!}/messages?limit=100`)
      expect(history.messages.filter((message: any) => message.role === 'user')).toHaveLength(5)
      expect(history.messages.filter((message: any) => message.role === 'assistant')).toHaveLength(5)
      await page.getByTestId('chat-close').click()
      await expect(page.getByTestId('board-undo')).toBeEnabled()
      await page.getByTestId('inspector-tab-rules').click()
      await expect(page.getByTestId('inspector-section-rules').locator('article[data-rule-id]')).toHaveCount(2)
      await page.getByTestId('open-ai-assistant').click()
      await expect(page.getByTestId('chat-panel').locator('.user-row')).toHaveCount(5)
      await page.getByTestId('chat-close').click()
      await page.goto(`/#/board?run=verification:${baseline.id}&trace=${originalTrace.id}`)
      await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 60_000 })
      await page.reload()
      await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 60_000 })
      const step = page.getByTestId('trace-timeline-step-input')
      const stateCount = Number(await step.getAttribute('max'))
      expect(stateCount).toBeGreaterThan(1)
      await expect(step).toHaveValue(String(stateCount))
      await expect(page.getByTestId('trace-timeline-play')).toHaveAttribute('aria-pressed', 'false')
      await step.fill('1')
      await expect(step).toHaveValue('1')
      await page.getByTestId('trace-timeline-play').click()
      await expect(step).toHaveValue(String(stateCount), { timeout: 30_000 })
      await expect(page.getByTestId('trace-timeline-play')).toHaveAttribute('aria-pressed', 'false')
      await page.getByTestId('trace-timeline-close').click()
      await expect(page.getByTestId('trace-timeline')).toBeHidden()
      expect((await readData('/api/verify/traces')).find((trace: any) => trace.id === originalTrace.id)).toEqual(originalTrace)
      expect(await readData('/api/board/snapshot')).toEqual(after)
      await expect(page.getByTestId('open-ai-assistant')).toBeEnabled({ timeout: 60_000 })
      await page.getByTestId('open-ai-assistant').click()
      const historyToggle = page.getByTestId('chat-sidebar-toggle')
      if (await historyToggle.getAttribute('aria-expanded') !== 'true') await historyToggle.click()
      await page.getByTestId(`chat-session-${sessionId!}`).click()
      await expect(page.getByTestId('chat-panel').locator('.user-row')).toHaveCount(5)
      await expect(page.getByTestId('chat-panel').locator('.ai-row')).toHaveCount(5)
      expect(await readData(`/api/chat/sessions/${sessionId!}/messages?limit=100`)).toEqual(history)
    } finally {
      if (await page.getByTestId('chat-stop').isVisible().catch(() => false)) {
        await page.getByTestId('chat-stop').click()
      }
    }
  })
})
