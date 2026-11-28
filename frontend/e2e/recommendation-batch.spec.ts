import { readFile } from 'node:fs/promises'
import path from 'node:path'
import type { APIRequestContext, Page } from '@playwright/test'
import type { DeviceManifest } from '../src/types/device'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'
import { unwrap } from './support/apiResponse'
import { openRecommendation, type RecommendationKind } from './support/recommendations'
import { clickUnderTooltip } from './support/tooltips'
import { expandCreation } from './support/controlCenter'
import { afterHistoryRender } from './support/historyReply'

test('a cross-tab edit stops a device batch (visible) even when its first response already includes that edit', async ({ page, request }) => {
  const auth = await prepare(page, request)
  const writer = await page.context().newPage()
  await writer.goto('/#/board')
  await expect(writer.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
  await recommend(page, 'device', 3)
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  let dispatched!: () => void
  const firstDispatched = new Promise<void>(resolve => { dispatched = resolve })
  let writes = 0
  await page.route('**/api/board/nodes', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    writes += 1
    if (writes === 1) {
      dispatched()
      await gate
    }
    await route.continue()
  })
  try {
    await page.getByTestId('apply-all-device-recommendations').click()
    await firstDispatched
    await page.evaluate(() => {
      const channel = new BroadcastChannel('iot-verify-board-invalidation-v1')
      ;(window as any).externalBoardInvalidationReceived = false
      channel.onmessage = () => {
        ;(window as any).externalBoardInvalidationReceived = true
        channel.close()
      }
    })
    await writer.getByTestId('control-tab-devices').click()
    await expandCreation(writer, 'devices')
    await writer.getByTestId('single-device-template').selectOption('Light')
    await writer.getByTestId('single-device-name').fill('External light')
    const externalWrite = writer.waitForResponse(response => response.url().endsWith('/api/board/nodes')
      && response.request().method() === 'POST')
    await writer.getByTestId('single-device-create').click()
    await unwrap(await externalWrite)
    await expect.poll(() => page.evaluate(() => (window as any).externalBoardInvalidationReceived)).toBe(true)
    const refreshed = page.waitForResponse(response => response.url().endsWith('/api/board/snapshot'))
    release()
    await unwrap(await refreshed)
    await expect(page.getByTestId('apply-all-device-recommendations')).toHaveCount(0)
    expect(writes).toBe(1)
    const snapshot = await readSnapshot(request, auth)
    expect(snapshot.nodes).toHaveLength(4)
    expect(snapshot.nodes.some((node: any) => node.label === 'External light')).toBe(true)
  } finally {
    release()
    await writer.close()
  }
})
const collection = { device: 'nodes', rule: 'rules', spec: 'specs' } as const
const snapshotCollection = { device: 'nodes', rule: 'rules', spec: 'specifications' } as const
const headers = (auth: AuthUser) => ({ Authorization: `Bearer ${auth.token}` })
const readSnapshot = (request: APIRequestContext, auth: AuthUser) =>
  request.get(`${apiBaseURL}/api/board/snapshot`, { headers: headers(auth) }).then(unwrap)

const prepare = async (
  page: Page, request: APIRequestContext, locale = 'en', theme = 'light',
  configureTemplate?: (manifest: DeviceManifest) => void
) => {
  const auth = await createAuthenticatedUser(request)
  const manifest: DeviceManifest = JSON.parse(await readFile(path.resolve(process.cwd(), '..',
    'backend/src/main/resources/deviceTemplate/Light.json'), 'utf8'))
  configureTemplate?.(manifest)
  const preview = await unwrap(await request.get(`${apiBaseURL}/api/board/replacement-preview`, { headers: headers(auth) }))
  await unwrap(await request.post(`${apiBaseURL}/api/board/scene`, { headers: headers(auth), data: {
    impactToken: preview.impactToken,
    scene: { schema: 'iot-verify.board-scene', version: 6,
      templates: [{ name: manifest.Name, manifest }],
      devices: ['source', 'target'].map((id, index) => ({ id, templateName: manifest.Name, label: id,
        state: 'off', position: { x: 100 + index * 220, y: 100 }, width: 176, height: 128 })),
      environmentVariables: [{ name: 'illuminance', value: '0', trust: 'untrusted', privacy: 'public' }],
      rules: [], specs: [] }
  } }))
  await page.addInitScript(({ auth, locale, theme }) => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('locale', locale)
    localStorage.setItem('iot_verify_theme', theme)
  }, { auth, locale, theme })
  await page.goto('/#/board')
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
  return auth
}

const candidatesFor = (kind: RecommendationKind, count: number) => Array.from({ length: count }, (_, index) => {
  const action = index === 0 ? 'on' : 'off'
  if (kind === 'device') return { templateName: 'Light', suggestedLabel: `Hall light ${index + 1}`,
    initialState: 'off', reason: 'Light the hall when needed.' }
  if (kind === 'rule') return { name: `Hall automation ${index + 1}`,
    conditions: [{ deviceId: index === 2 ? 'target' : 'source', attribute: action, targetType: 'api' }],
    command: { deviceId: index === 2 ? 'source' : 'target', action } }
  return { name: `Hall safety ${index + 1}`, rationale: 'Keep the lighting state constrained.', templateId: '3',
    aConditions: [{ deviceId: index === 2 ? 'target' : 'source', targetType: 'state', key: 'state', relation: '=', value: action }],
    ifConditions: [], thenConditions: [] }
})

// Generation is deterministic; every board write, snapshot and undo uses the real backend.
const recommend = async (page: Page, kind: RecommendationKind, count: number,
  candidates: readonly unknown[] = candidatesFor(kind, count), mockSimilarity = true) => {
  const resource = kind === 'device' ? 'devices' : collection[kind]
  await page.route(`**/api/board/${resource}/recommend**`, route => route.fulfill({ json: {
    code: 200, message: 'Success', data: {
      message: 'Validated recommendation candidates.', count, requestedCount: 5, validatedCount: count,
      filteredCount: 0, filteredItems: [], adjustedCount: 0, adjustedItems: [],
      rawCandidateCount: count, inspectedCount: count, truncatedCount: 0,
      recommendations: candidates
    }
  } }))
  if (kind === 'rule' && mockSimilarity) await page.route('**/api/board/rules/check-similarity/batch', route => route.fulfill({ json: {
    code: 200, message: 'Success', data: candidatesFor('rule', count).map(() => ({
      isSimilar: false, isDuplicate: false, requiresReview: false,
      matchedRule: null, similarity: 0.1, reasonCode: 'AI_NO_SIGNIFICANT_SIMILARITY',
      reason: 'The automation has a distinct action.', message: 'No significant similarity was found.'
    }))
  } }))
  await openRecommendation(page, kind)
  await page.getByTestId(`generate-${kind}-recommendations`).click()
  await expect(page.getByTestId(`apply-all-${kind}-recommendations`)).toBeEnabled()
}

test('uses server membership equality in the editor, recommendations and lost-save recovery', async ({ page, request }) => {
  const auth = await prepare(page, request)
  await unwrap(await request.post(`${apiBaseURL}/api/board/nodes`, { headers: headers(auth), data: {
    devices: [{ id: 'temperature_sensor', templateName: 'Temperature Sensor', label: 'Hall temperature',
      position: { x: 100, y: 250 }, width: 176, height: 128, state: 'Working' }], environmentVariablePatches: []
  } }))
  const specificationsUrl = `${apiBaseURL}/api/board/specs`
  const specCondition = { deviceId: 'temperature_sensor', targetType: 'variable',
    variableSource: 'environment', key: 'temperature', relation: 'in', value: '20,25' }
  const specification = { id: 'temperature_membership', name: 'Saved temperature membership', templateId: '3',
    aConditions: [specCondition], ifConditions: [], thenConditions: [] }
  const saved = await unwrap(await request.post(specificationsUrl, { headers: headers(auth), data: specification }))
  const duplicate = await request.post(specificationsUrl, {
    headers: headers(auth),
    data: { ...specification, id: 'reordered_temperature_membership', name: 'Reordered temperature requirement',
      aConditions: [{ ...specCondition, value: '25,20' }] }
  })
  expect(duplicate.status(), await duplicate.text()).toBe(409)
  expect((await duplicate.json()).data.reasonCode).toBe('DUPLICATE_SPECIFICATION')
  expect((await readSnapshot(request, auth)).specifications).toEqual(saved.currentItems)

  const membership = { deviceName: 'temperature_sensor', attribute: 'temperature', targetType: 'variable', relation: 'in', value: '20,25' }
  await unwrap(await request.post(`${apiBaseURL}/api/board/rules`, { headers: headers(auth), data: {
    conditions: [membership], command: { deviceName: 'target', action: 'on' }, ruleString: 'Saved membership rule'
  } }))
  await page.reload()
  await page.getByTestId('control-tab-rules').click()
  await expandCreation(page, 'rules')
  await page.locator('#rule-builder-name').fill('Reversed membership')
  await page.locator('#rule-source-device').selectOption('temperature_sensor')
  await page.locator('#rule-source-type').selectOption('variable')
  await page.locator('#rule-source-variable').selectOption('temperature')
  await page.locator('#rule-source-condition').selectOption('in')
  await page.locator('#rule-source-value').fill('25,20')
  await page.getByTestId('rule-add-source').click()
  await page.locator('#rule-target-device').selectOption('target')
  await page.locator('#rule-target-action').selectOption('on')

  let writes = 0
  await page.route('**/api/board/rules', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    writes += 1
    const dto = route.request().postDataJSON()
    expect(dto.conditions[0].value).toBe('25,20')
    dto.conditions[0].value = '20,25'
    await unwrap(await route.fetch({ postData: dto }))
    await route.abort()
  })
  const save = page.getByTestId('rule-save')
  await save.click()
  await expect(page.getByTestId('rule-save-failure')).toContainText('Saved membership rule')
  expect(writes).toBe(0)
  await page.locator('#rule-target-action').selectOption('off')
  await save.click()
  await expect(page.getByTestId('rule-save-outcome-unknown')).toContainText('server found an equivalent rule')
  await expect(save).toBeDisabled()
  const targetAction = page.locator('#rule-target-action')
  await targetAction.selectOption('on')
  await expect(page.getByTestId('rule-save-outcome-unknown')).not.toBeVisible()
  await targetAction.selectOption('off')
  await expect(page.getByTestId('rule-save-outcome-unknown')).toBeVisible()
  await expect(save).toBeDisabled()
  expect(writes).toBe(1)
  const recovered = await readSnapshot(request, auth)
  expect(recovered.rules).toHaveLength(2)

  await recommend(page, 'rule', 2, ['25,20', '20,25'].map((value, index) => ({
    name: `Equivalent recommendation ${index + 1}`, conditions: [{ ...membership, deviceId: 'temperature_sensor', value }],
    command: { deviceId: 'target', action: 'on' }
  })), false)
  const addCandidate = page.getByTestId('rule-recommendation-panel').getByRole('button', { name: /Add This Rule/ }).first()
  await expect(addCandidate).toBeEnabled()
  const single = page.waitForResponse(response => response.url().endsWith('/api/board/rules/check-similarity'))
  await clickUnderTooltip(page, addCandidate)
  expect(await unwrap(await single)).toMatchObject({ reasonCode: 'EXACT_MATCH' })
  const batch = page.waitForResponse(response => response.url().endsWith('/api/board/rules/check-similarity/batch'))
  const applyAll = page.getByTestId('apply-all-rule-recommendations')
  await applyAll.click()
  expect(await unwrap(await batch)).toMatchObject([{ reasonCode: 'EXACT_MATCH' }, { reasonCode: 'EXACT_MATCH' }])
  await expect(applyAll).toHaveAttribute('aria-busy', 'false')
  await expect(page.locator('#rule-batch-apply-status')).toContainText('0/2 confirmed applied')
  expect(writes).toBe(1)

  const undone = page.waitForResponse(response => response.url().endsWith('/api/board/edits/undo'))
  await clickUnderTooltip(page, page.getByTestId('board-undo'))
  const response = await undone
  expect(response.request().postDataJSON()).toEqual({ expectedEditHistoryRevision: recovered.editHistory.editHistoryRevision })
  expect(await unwrap(response)).toMatchObject({ applied: true })
  expect((await readSnapshot(request, auth)).rules).toEqual(recovered.rules.slice(0, 1))
  expect(writes).toBe(1)
})

for (const kind of ['device', 'rule', 'spec'] as const) {
  for (const profile of [
    { name: 'desktop', locale: 'en', theme: 'light', width: 1440, height: 900 }
  ]) {
    test(`${kind}: apply all, undo and regenerate (${profile.name})`, async ({ page, request }, testInfo) => {
      test.setTimeout(120_000)
      await page.setViewportSize(profile)
      const auth = await prepare(page, request, profile.locale, profile.theme)
      const before = await readSnapshot(request, auth)
      await recommend(page, kind, 3)
      const endpoint = `/api/board/${collection[kind]}`
      const posts: any[] = []
      let release!: () => void
      const firstWrite = new Promise<void>(resolve => { release = resolve })
      await page.route(`**${endpoint}`, async route => {
        if (route.request().method() !== 'POST') return route.continue()
        posts.push(route.request().postDataJSON())
        if (posts.length === 1) await firstWrite
        await route.continue()
      })
      const ruleRequests: string[] = []
      let preflightRules: unknown
      const devicesTab = page.getByTestId('inspector-tab-devices')
      if (kind === 'rule') {
        await devicesTab.click()
        page.on('request', request => {
          if (request.method() !== 'POST') return
          const pathname = new URL(request.url()).pathname
          if (!/^\/api\/board\/rules(?:\/check-similarity(?:\/batch)?)?$/.test(pathname)) return
          ruleRequests.push(pathname)
          if (pathname.endsWith('/batch')) preflightRules = request.postDataJSON().rules
        })
      }
      const bulk = page.getByTestId(`apply-all-${kind}-recommendations`)
      await bulk.scrollIntoViewIfNeeded()
      await page.screenshot({ path: testInfo.outputPath('ready.png') })
      const status = page.locator(`#${kind}-batch-apply-status`)
      try {
        await bulk.click()
        await expect.poll(() => posts.length).toBe(1)
        await expect(bulk).toBeDisabled()
        await expect(bulk).toHaveAttribute('aria-busy', 'true')
        await bulk.dispatchEvent('click')
        await afterHistoryRender(page)
        await expect(bulk).toHaveAttribute('aria-busy', 'true')
        expect(posts).toHaveLength(1)
      } finally { release() }
      await expect(status).toContainText(profile.locale === 'en' ? 'All 3' : '全部 3')
      await expect(status).toBeVisible()
      await expect(bulk).toBeDisabled()
      await expect(page.getByRole('button', { name: /Added to board/ })).toHaveCount(3)
      await bulk.dispatchEvent('click')
      await afterHistoryRender(page)
      expect(posts).toHaveLength(3)
      const after = await readSnapshot(request, auth)
      expect(after[snapshotCollection[kind]]).toHaveLength(before[snapshotCollection[kind]].length + 3)
      if (kind === 'rule') {
        expect(ruleRequests).toEqual(['/api/board/rules/check-similarity/batch', endpoint, endpoint, endpoint])
        const ordered = ['on', 'off', 'off'].map((action, index) => ({
          conditions: [{ deviceName: index === 2 ? 'target' : 'source', attribute: action }],
          command: { deviceName: index === 2 ? 'source' : 'target', action }
        }))
        expect(preflightRules).toMatchObject(ordered.map(rule => ({ id: null, ...rule })))
        expect(posts).toMatchObject(ordered)
        await expect(devicesTab).toHaveAttribute('aria-selected', 'true')
      } else if (kind === 'device') {
        expect(posts.map(post => post.devices[0].label)).toEqual(['Hall light 1', 'Hall light 2', 'Hall light 3'])
      } else {
        expect(posts.map(post => post.name)).toEqual(['Hall safety 1', 'Hall safety 2', 'Hall safety 3'])
      }
      for (const untouched of ['nodes', 'rules', 'specifications', 'environmentVariables']) {
        if (untouched !== snapshotCollection[kind]) expect(after[untouched]).toEqual(before[untouched])
      }
      if (kind === 'device') {
        const added = after.nodes.filter((node: any) => !before.nodes.some((old: any) => old.id === node.id))
        added.sort((left: any, right: any) => left.label.localeCompare(right.label))
        expect(added[1].position.x - added[0].position.x).toBeGreaterThanOrEqual(176)
        expect(added[2].position.x - added[1].position.x).toBeGreaterThanOrEqual(176)
      }
      await bulk.scrollIntoViewIfNeeded()
      await page.screenshot({ path: testInfo.outputPath('applied.png') })
      await page.getByTestId(`generate-${kind}-recommendations`).click()
      await expect(bulk).toBeEnabled()
      // Closing a narrow overlay makes the canvas toolbar reachable without changing the scene.
      if (profile.name === 'narrow') await page.getByTestId('control-center-toggle').click()
      for (let index = 0; index < 3; index++) {
        await expect(page.getByTestId('board-undo')).toBeEnabled()
        const undone = page.waitForResponse(response => response.url().endsWith('/api/board/edits/undo'))
        await clickUnderTooltip(page, page.getByTestId('board-undo'))
        await unwrap(await undone)
        await expect.poll(async () => (await readSnapshot(request, auth))[snapshotCollection[kind]].length)
          .toBe(before[snapshotCollection[kind]].length + 2 - index)
      }
      const restored = await readSnapshot(request, auth)
      for (const field of ['nodes', 'rules', 'specifications', 'environmentVariables']) expect(restored[field]).toEqual(before[field])
      await openRecommendation(page, kind)
      await page.getByTestId(`generate-${kind}-recommendations`).click()
      await expect(bulk).toBeEnabled()
    })
  }

  if (kind !== 'spec') test(`${kind}: stops after the second write fails and permits fresh recommendations`, async ({ page, request }, testInfo) => {
    const auth = await prepare(page, request)
    const before = await readSnapshot(request, auth)
    await recommend(page, kind, 3)
    let writes = 0
    await page.route(`**/api/board/${collection[kind]}`, async route => {
      if (route.request().method() !== 'POST') return route.continue()
      writes += 1
      if (writes !== 2) return route.continue()
      await route.fulfill({ status: 422, json: { code: 422, message: 'Candidate rejected',
        data: { errors: { candidate: 'This candidate cannot be added.' } } } })
    })
    const bulk = page.getByTestId(`apply-all-${kind}-recommendations`)
    await bulk.click()
    await expect(page.locator(`#${kind}-batch-apply-status`)).toContainText('1/3')
    await expect(bulk).toHaveAttribute('aria-busy', 'false')
    await expect(bulk).toBeDisabled()
    await expect(page.getByTestId(`${kind}-recommendation-apply-failure-1`)).toContainText('This candidate cannot be added.')
    expect(writes).toBe(2)
    const after = await readSnapshot(request, auth)
    expect(after[snapshotCollection[kind]]).toHaveLength(before[snapshotCollection[kind]].length + 1)
    await bulk.scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath('partial.png') })
    await page.getByTestId(`generate-${kind}-recommendations`).click()
    await expect(bulk).toBeEnabled()
    expect(writes).toBe(2)
  })
}

const RUNTIME_HINT_TEMPLATE = 'Runtime Hint Light'
const configureRuntimeHintTemplate = (manifest: DeviceManifest) => {
  manifest.Name = RUNTIME_HINT_TEMPLATE
  const local = { IsInside: true, FalsifiableWhenCompromised: false, Trust: 'trusted', Privacy: 'public' }
  manifest.InternalVariables = [
    ...(manifest.InternalVariables ?? []),
    { ...local, Name: 'brightness', LowerBound: 5, UpperBound: 30 },
    { ...local, Name: 'operating_band', Values: ['standard', 'economy'] }
  ]
  manifest.WorkingStates = manifest.WorkingStates?.map(state => state.Name === 'on'
    ? { ...state, Dynamics: [...(state.Dynamics ?? []), { VariableName: 'operating_band', Value: 'economy' }] }
    : state)
}

for (const entry of ['manager', 'template drop'] as const) {
  test(`${entry}: recovers a device creation after response loss`, async ({ page, request }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    const auth = await prepare(page, request, 'en', 'light',
      entry === 'template drop' ? configureRuntimeHintTemplate : undefined)
    const before = await readSnapshot(request, auth)
    const label = 'Recovered light'
    let writes = 0
    let snapshotReads = 0
    page.on('request', request => {
      if (request.method() === 'GET' && request.url().endsWith('/api/board/snapshot')) snapshotReads += 1
    })
    await page.route('**/api/board/nodes', async route => {
      if (route.request().method() !== 'POST') return route.continue()
      writes += 1
      await unwrap(await route.fetch())
      await route.abort()
    })
    const reconciliation = page.waitForResponse(response => response.url().endsWith('/api/board/snapshot'))
    if (entry === 'manager') {
      await page.getByTestId('control-tab-devices').click()
      await expandCreation(page, 'devices')
      await page.getByTestId('single-device-template').selectOption('Light')
      const name = page.getByTestId('single-device-name')
      const create = page.getByTestId('single-device-create')
      await name.fill(label)
      await clickUnderTooltip(page, create)
      await expect(page.getByTestId('single-device-save-unconfirmed'))
        .toHaveText(`The save outcome is unconfirmed. After refresh, device “${label}” is on the board.`)
      expect(await page.locator('.el-message--error').count()).toBe(0)
      await expect(name).toHaveValue(label)
      await expect(create).toBeDisabled()
    } else {
      const dataTransfer = await page.evaluateHandle(templateName => {
        const transfer = new DataTransfer()
        transfer.setData('application/x-iot-template', templateName)
        return transfer
      }, RUNTIME_HINT_TEMPLATE)
      await page.getByTestId('canvas-board').dispatchEvent('drop', { dataTransfer, clientX: 650, clientY: 400 })
      await page.getByTestId('template-instance-runtime-toggle').click()
      const runtime = page.getByTestId('template-instance-runtime')
      const brightness = runtime.getByTestId('template-instance-variable-brightness')
      const numericHint = 'Use template default (5) / 5 - 30'
      await brightness.fill('')
      await expect(brightness).toHaveAttribute('placeholder', numericHint)
      await expect(runtime.getByText(numericHint, { exact: true })).toBeVisible()

      const state = runtime.getByTestId('template-instance-state')
      const band = runtime.getByTestId('template-instance-variable-operating_band')
      const templateDefault = band.locator('option[value=""]')
      await state.selectOption('off')
      await expect(templateDefault).toHaveText('Use template default (standard)')
      await state.selectOption('on')
      await expect(templateDefault).toHaveText('Use template default (economy)')
      await band.selectOption('')
      await page.getByTestId('template-instance-name').fill(label)
      await page.getByTestId('template-instance-confirm').click()
      await expect(page.getByTestId('template-instance-dialog')).toHaveCount(0)
      await expect(page.getByText(/The save outcome is unconfirmed/)).toBeVisible()
    }

    const snapshot = await unwrap(await reconciliation)
    const recovered = snapshot.nodes.find((node: { label: string }) => node.label === label)
    expect(recovered).toBeDefined()
    const recoveredNode = page.locator(`[data-node-id="${recovered.id}"]`)
    await expect(recoveredNode).toBeVisible()
    if (entry === 'manager') await expect(recoveredNode).toBeFocused()
    expect(snapshot.nodes).toHaveLength(before.nodes.length + 1)
    expect(snapshotReads).toBe(1)

    const undone = page.waitForResponse(response => response.url().endsWith('/api/board/edits/undo'))
    await clickUnderTooltip(page, page.getByTestId('board-undo'))
    const response = await undone
    expect(response.request().postDataJSON()).toEqual({ expectedEditHistoryRevision: snapshot.editHistory.editHistoryRevision })
    expect(await unwrap(response)).toMatchObject({ applied: true })
    expect((await readSnapshot(request, auth)).nodes).toEqual(before.nodes)
    expect(writes).toBe(1)
  })
}

for (const kind of ['rule', 'device', 'spec'] as const) {
  test(`${kind}: recovers a saved item after response loss and stops before the next write`, async ({ page, request }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    const auth = await prepare(page, request)
    const field = snapshotCollection[kind]
    const before = await readSnapshot(request, auth)
    await recommend(page, kind, 3)
    let writes = 0
    let snapshotReads = 0
    let duplicateChecks = 0
    page.on('request', request => {
      if (request.method() === 'GET' && request.url().endsWith('/api/board/snapshot')) snapshotReads += 1
      if (request.method() === 'POST' && request.url().endsWith('/api/board/rules/check-duplicate')) duplicateChecks += 1
    })
    await page.route(`**/api/board/${collection[kind]}`, async route => {
      if (route.request().method() !== 'POST') return route.continue()
      writes += 1
      if (writes !== 2) return route.continue()
      await unwrap(await route.fetch())
      await route.abort()
    })
    const reconciliation = page.waitForResponse(response => response.url().endsWith('/api/board/snapshot'))
    const bulk = page.getByTestId(`apply-all-${kind}-recommendations`)
    await bulk.click()
    const snapshot = await unwrap(await reconciliation)
    await expect(page.locator(`#${kind}-batch-apply-status`)).toContainText('1/3 confirmed applied')
    await expect(bulk).toHaveAttribute('aria-busy', 'false')
    expect(writes).toBe(2)
    expect(snapshotReads).toBe(1)
    expect(snapshot[field]).toHaveLength(before[field].length + 2)

    if (kind === 'rule') {
      expect(duplicateChecks).toBe(1)
      await expect(page.getByTestId('rule-recommendation-apply-failure-1')).toContainText('server found an equivalent rule')
      await expect(page.getByRole('button', { name: /Added to board/ })).toHaveCount(1)
      expect(snapshot.rules).toMatchObject(['on', 'off'].map(action => ({
        conditions: [{ deviceName: 'source', attribute: action }], command: { deviceName: 'target', action }
      })))
    } else if (kind === 'device') {
      const recovered = snapshot.nodes.find((node: { label: string }) => node.label === 'Hall light 2')
      expect(recovered).toBeDefined()
      await expect(page.locator(`[data-node-id="${recovered.id}"]`)).toBeVisible()
      await expect(page.getByTestId('device-recommendation-apply-failure-1')).toContainText('The save outcome is unconfirmed')
      await expect(page.getByRole('button', { name: /Added to board/ })).toHaveCount(1)
    } else {
      const recovered = snapshot.specifications.find((spec: { name: string }) => spec.name === 'Hall safety 2')
      expect(recovered).toBeDefined()
      await expect(page.getByTestId('spec-recommendation-apply-failure-1')).toContainText('The save is unconfirmed.')
      await expect(page.getByRole('button', { name: /Added to board/ })).toHaveCount(1)
      const inspector = page.getByTestId('system-inspector-toggle')
      if (await inspector.getAttribute('aria-expanded') === 'false') await inspector.click()
      await page.getByTestId('inspector-tab-specs').click()
      await expect(page.locator(`[data-spec-id="${recovered.id}"]`)).toContainText('Hall safety 2')
      await expect(page.locator(`[data-spec-id="${recovered.id}"]`)).toBeVisible()
    }

    let revision = snapshot.editHistory.editHistoryRevision
    for (let remaining = 1; remaining >= 0; remaining--) {
      await expect(page.getByTestId('board-undo')).toBeEnabled()
      const undone = page.waitForResponse(response => response.url().endsWith('/api/board/edits/undo'))
      await clickUnderTooltip(page, page.getByTestId('board-undo'))
      const response = await undone
      expect(response.request().postDataJSON()).toEqual({ expectedEditHistoryRevision: revision })
      expect(await unwrap(response)).toMatchObject({ applied: true })
      const afterUndo = await readSnapshot(request, auth)
      expect(afterUndo[field]).toHaveLength(before[field].length + remaining)
      revision = afterUndo.editHistory.editHistoryRevision
    }
  })
}
