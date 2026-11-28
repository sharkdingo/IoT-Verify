import { readFile } from 'node:fs/promises'
import path from 'node:path'
import type { APIRequestContext, APIResponse, Page } from '@playwright/test'
import type { DeviceManifest, DeviceTemplate } from '../src/types/device'
import type { PortableSceneFile } from '../src/types/scene'
import type { SimulationResult } from '../src/types/simulation'
import type { RuleDto } from '../src/types/rule'
import { validateSimulationTrace, validateVerificationResult } from '../src/utils/runResponse'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'
import { expectSameBoardSnapshot, readBoardModelFingerprints } from './support/boardSnapshot'
import { expandControlTool } from './support/controlCenter'
import { clickUnderTooltip } from './support/tooltips'

const examples = path.resolve(process.cwd(), '..', 'docs', 'examples')

const openBoard = async (page: Page, auth: AuthUser, theme = 'light') => {
  await page.addInitScript(({ token, user, theme }) => {
    localStorage.setItem('iot_verify_token', token)
    localStorage.setItem('iot_verify_user', JSON.stringify(user))
    localStorage.setItem('locale', 'en')
    localStorage.setItem('iot_verify_theme', theme)
  }, {
    token: auth.token,
    user: { userId: auth.userId, phone: auth.phone, username: auth.username }, theme
  })
  await page.goto('/#/board')
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
}

const importFile = async (
  page: Page,
  file: string | { name: string, mimeType: string, buffer: Buffer }
) => {
  const chooser = page.waitForEvent('filechooser')
  await clickUnderTooltip(page, page.getByTestId('scene-import'))
  await (await chooser).setFiles(file)
  const response = page.waitForResponse(reply => reply.request().method() === 'POST'
    && new URL(reply.url()).pathname === '/api/board/scene')
  const confirmation = page.getByRole('dialog', { name: 'Replace the current scene?' })
  const scene = JSON.parse(typeof file === 'string' ? await readFile(file, 'utf8') : file.buffer.toString('utf8'))
  const counts = [scene.devices.length, scene.environmentVariables.length, scene.rules.length, scene.specs.length]
  const rows = confirmation.getByTestId('scene-import-comparison').locator('tbody tr')
  await expect(rows).toHaveCount(6)
  for (const [index, count] of counts.entries()) {
    await expect(rows.nth(index).locator('td').last()).toHaveText(String(count))
  }
  await expect(rows.nth(4).locator('td').last()).toHaveText(String(scene.templates.length))
  await expect(confirmation.getByTestId('scene-import-consequence')).toBeVisible()
  await expect(confirmation.getByTestId('scene-import-template-rule-details')).toHaveJSProperty('open', false)
  await confirmation.getByRole('button', { name: /^(Replace scene|Apply changes and replace)$/ }).click()
  const reply = await response
  return { status: reply.status(), body: await reply.json() }
}

const exportFile = async (page: Page) => {
  const download = page.waitForEvent('download')
  await clickUnderTooltip(page, page.getByTestId('scene-export'))
  const filename = await (await download).path()
  expect(filename).not.toBeNull()
  return JSON.parse(await readFile(filename!, 'utf8'))
}

const readAccountScene = async (request: APIRequestContext, auth: AuthUser) => {
  const headers = { Authorization: `Bearer ${auth.token}` }
  return Promise.all(['snapshot', 'templates', 'edits/clear-preview'].map(async endpoint => {
    const response = await request.get(`${apiBaseURL}/api/board/${endpoint}`, { headers })
    expect(response.status()).toBe(200)
    return (await response.json()).data
  }))
}

const data = async (response: Pick<APIResponse, 'json' | 'ok'>) => {
  const body = await response.json()
  expect(response.ok(), JSON.stringify(body)).toBe(true)
  return body.data
}

const shadowDefaultTemplate = async (
  request: APIRequestContext, auth: AuthUser, name: string, edit: (manifest: DeviceManifest) => void
) => {
  const headers = { Authorization: `Bearer ${auth.token}` }
  const boardUrl = `${apiBaseURL}/api/board`
  const templates: DeviceTemplate[] = await data(await request.get(`${boardUrl}/templates`, { headers }))
  const original = templates.find(template => template.name === name)!
  expect(original?.defaultTemplate).toBe(true)
  const deletion = `${boardUrl}/templates/${original.id}`
  const preview = await data(await request.get(`${deletion}/deletion-preview`, { headers }))
  await data(await request.post(`${deletion}/delete`, { headers, data: { impactToken: preview.impactToken } }))
  const custom = structuredClone(original.manifest)
  edit(custom)
  await data(await request.post(`${boardUrl}/templates`, { headers, data: { name: original.name, manifest: custom } }))
  return original.manifest
}

test('adopts device values from a committed default template reset without reloading the Board', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  const headers = { Authorization: `Bearer ${auth.token}` }
  const boardUrl = `${apiBaseURL}/api/board`
  await shadowDefaultTemplate(request, auth, 'Car', custom => {
    const location = custom.WorkingStates?.find(state => state.Name === 'away')?.Dynamics
      ?.find(dynamic => dynamic.VariableName === 'location')
    expect(location).toBeDefined()
    location!.Value = 'garage'
  })
  await data(await request.post(`${boardUrl}/nodes`, { headers, data: { devices: [{
    id: 'reset_car', label: 'Reset test car', templateName: 'Car', state: 'away',
    position: { x: 120, y: 120 }, width: 176, height: 128,
    variables: [{ name: 'location', value: 'garage', trust: 'untrusted' }]
  }] } }))

  await openBoard(page, auth)
  const expectLocation = (devices: PortableSceneFile['devices'], value: string) =>
    expect(devices.find(device => device.id === 'reset_car')).toMatchObject({ state: 'away',
      variables: expect.arrayContaining([expect.objectContaining({ name: 'location', value })]) })
  expectLocation((await exportFile(page)).devices, 'garage')
  await page.getByTestId('control-tab-templates').click()
  await expandControlTool(page, 'control-template-repository')
  const previewResponse = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/board/templates/defaults/reset-preview')
  await page.getByTestId('reset-default-templates').click()
  const resetPreview = await data(await previewResponse)
  expectLocation(resetPreview.currentNodes, 'garage')
  const runtimeChange = { field: 'VARIABLE_VALUE', itemName: 'location', previousValue: 'garage', currentValue: 'away' }
  expect(resetPreview.affectedDevices).toContainEqual(expect.objectContaining({
    deviceId: 'reset_car',
    runtimeChanges: [runtimeChange]
  }))
  await expect(page.getByTestId('default-template-reset-device-changes'))
    .toContainText('location value from "garage" to "away"')

  const resetResponse = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/board/templates/defaults/reset')
  await page.getByTestId('default-template-reset-confirm').click()
  const reset = await data(await resetResponse)
  expect(reset.affectedDevices).toEqual(resetPreview.affectedDevices)
  expectLocation(reset.currentNodes, 'away')
  await expect(page.getByText(/^Default templates reset:/)).toBeVisible()
  expectLocation((await exportFile(page)).devices, 'away')
})

test('shows the shared variable conflict before a default template reset', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  const headers = { Authorization: `Bearer ${auth.token}` }
  const boardUrl = `${apiBaseURL}/api/board`
  const bundled = await shadowDefaultTemplate(request, auth, 'Light', custom => {
    custom.InternalVariables = []
    custom.ImpactedVariables = []
    custom.WorkingStates?.forEach(state => { state.Dynamics = [] })
  })
  const shared = bundled.InternalVariables?.find(variable => variable.Name === 'illuminance')
  expect(shared).toBeDefined()
  const meter = {
    Name: 'Narrow Light Meter',
    InternalVariables: [{ ...shared, Reads: true, UpperBound: 50 }]
  }
  await data(await request.post(`${boardUrl}/templates`, { headers, data: { name: meter.Name, manifest: meter } }))
  const devices = [
    { id: 'reset_light', label: 'Reset test light', templateName: 'Light', state: 'on' },
    { id: 'reset_meter', label: 'Reset test meter', templateName: meter.Name, state: 'Working' }
  ].map((device, index) => ({ ...device, position: { x: 120 + index * 200, y: 120 }, width: 176, height: 128 }))
  await data(await request.post(`${boardUrl}/nodes`, { headers, data: { devices } }))

  await openBoard(page, auth)
  await page.getByTestId('control-tab-templates').click()
  await expandControlTool(page, 'control-template-repository')
  const response = page.waitForResponse(reply =>
    new URL(reply.url()).pathname === '/api/board/templates/defaults/reset-preview')
  await page.getByTestId('reset-default-templates').click()
  const preview = await data(await response)
  expect(preview.canApply).toBe(false)
  expect(preview.blockers).toEqual(expect.arrayContaining([
    expect.objectContaining({ reason: expect.stringContaining('illuminance') })
  ]))
  const dialog = page.getByRole('dialog', { name: 'Reset default templates?' })
  await expect(dialog.getByRole('alert').filter({ hasText: 'The current scene is incompatible' })).toBeVisible()
  await expect(dialog.getByTestId('default-template-reset-confirm')).toBeDisabled()
})

test('exported scenes round-trip repeatedly across accounts without template accumulation or changing references', async ({ page, browser, request }) => {
  test.setTimeout(120_000)
  const source = await createAuthenticatedUser(request, { usernamePrefix: 'roundtripSource' })
  const target = await createAuthenticatedUser(request, { usernamePrefix: 'roundtripTarget' })
  const targetContext = await browser.newContext()
  try {
    const targetPage = await targetContext.newPage()
    await openBoard(page, source)
    await openBoard(targetPage, target)
    const scene = JSON.parse(await readFile(path.join(examples, 'household-entry-scene.json'), 'utf8'))
    const requestTemplate = scene.templates.find((template: { name: string }) => template.name === 'Door Access Request')
    const retained = await request.post(`${apiBaseURL}/api/board/templates`, {
      headers: { Authorization: `Bearer ${target.token}` },
      data: { name: 'Retained Request', manifest: { ...requestTemplate.manifest, Name: 'Retained Request' } }
    })
    expect(retained.status()).toBe(200)
    const retainedTemplate = (await retained.json()).data
    await targetPage.reload()
    await expect(targetPage.getByTestId('scene-import')).toBeEnabled()
    const originalTarget = await readAccountScene(request, target)
    requestTemplate.name = ' Door Access Request '
    requestTemplate.manifest.Name = ' Door Access Request '
    const door = scene.devices.find((device: { templateName: string }) => device.templateName === 'Door')
    door.templateName = 'door'
    const initial = await importFile(page, {
      name: 'authored-scene.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(scene))
    })
    expect(initial.status).toBe(200)
    await expect(page.getByText(/^Scene replaced:/)).toBeVisible()
    const exported = await exportFile(page)
    expect(exported.templates.map((template: { name: string }) => template.name).sort())
      .toEqual(['Door', 'Door Access Request', 'Light', 'Occupancy Sensor'])
    expect(exported.devices.find((device: { id: string }) => device.id === door.id).templateName).toBe('Door')
    const originalSource = await readAccountScene(request, source)
    for (let round = 0; round < 2; round++) {
      const imported = await importFile(targetPage, {
        name: 'source-export.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(exported))
      })
      expect(imported.status).toBe(200)
      expect(imported.body.data.createdTemplates).toHaveLength(round === 0 ? 2 : 0)
      await expect(targetPage.getByText(/^Scene replaced:/)).toBeVisible()
      expect(await exportFile(targetPage)).toEqual(exported)
    }
    const afterTarget = await readAccountScene(request, target)
    expect(afterTarget[1]).toHaveLength(originalTarget[1].length + 2)
    expect(afterTarget[1].find((template: { name: string }) => template.name === 'Retained Request'))
      .toEqual(retainedTemplate)
    expect(await readAccountScene(request, source)).toEqual(originalSource)
    const returned = await importFile(page, {
      name: 'target-export.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(await exportFile(targetPage)))
    })
    expect(returned.status).toBe(200)
    expect(returned.body.data.createdTemplates).toHaveLength(0)
    await expect(page.getByText(/^Scene replaced:/)).toBeVisible()
    expect(await exportFile(page)).toEqual(exported)
    expect((await readAccountScene(request, source))[1]).toEqual(originalSource[1])
  } finally {
    await targetContext.close()
  }
})

test('a portable round trip preserves the winning autonomous shared write in NuSMV', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'writerPriority' })
  await openBoard(page, auth)
  const templateName = 'Shared Light Control'
  const scene: PortableSceneFile = {
    schema: 'iot-verify.board-scene', version: 6,
    templates: [{
      name: templateName,
      manifest: {
        Name: templateName, Modes: ['PowerMode'], InitState: 'off', APIs: [],
        WorkingStates: ['off', 'on'].map(Name => ({ Name, Trust: 'trusted', Privacy: 'public' })),
        InternalVariables: [{
          Name: 'roomLight', IsInside: false, Reads: false, Values: ['dim', 'dark', 'bright'],
          FalsifiableWhenCompromised: false, Trust: 'trusted', Privacy: 'public'
        }],
        Transitions: [
          { Name: 'dark when off', Trigger: { Attribute: 'PowerMode', Relation: '=', Value: 'off' },
            Assignments: [{ Attribute: 'roomLight', Value: 'dark' }] },
          { Name: 'bright when on', Trigger: { Attribute: 'PowerMode', Relation: '=', Value: 'on' },
            Assignments: [{ Attribute: 'roomLight', Value: 'bright' }] }
        ]
      }
    }],
    devices: [
      { id: 'Room-10', label: 'Room 10', templateName, state: 'on', position: { x: 320, y: 50 }, width: 176, height: 128 },
      { id: 'Room-2', label: 'Room 2', templateName, state: 'off', position: { x: 60, y: 50 }, width: 176, height: 128 }
    ],
    environmentVariables: [{ name: 'roomLight', value: 'dim', trust: 'trusted', privacy: 'public' }],
    rules: [], specs: []
  }
  const importScene = (content: PortableSceneFile) => importFile(page, {
    name: 'shared-writers.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(content))
  })
  const simulateFirstStep = async () => {
    const response = await request.post(`${apiBaseURL}/api/simulate`, {
      headers: { Authorization: `Bearer ${auth.token}` },
      data: { steps: 1, attackScenario: { mode: 'NONE' } }
    })
    const { data }: { data: SimulationResult } = await response.json()
    expect(response.status()).toBe(200)
    expect(data.modelComplete).toBe(true)
    return data.states.map(state => state.envVariables?.find(value => value.name === 'roomLight')?.value)
  }

  expect((await importScene(scene)).status).toBe(200)
  expect(await simulateFirstStep()).toEqual(['dim', 'bright'])
  const exported: PortableSceneFile = await exportFile(page)
  expect(exported.devices.map(device => device.id)).toEqual(['Room-2', 'Room-10'])
  expect((await importScene(exported)).status).toBe(200)
  expect(await simulateFirstStep()).toEqual(['dim', 'bright'])
})

test('distinct devices can share mode names and retain repair evidence after an equivalent set edit', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  await openBoard(page, auth)
  const modeledDevices = [
    { id: 'source_lamp', name: 'Source Lamp', modes: ['Power'], states: ['on', 'off'] },
    { id: 'reserve_lamp', name: 'Reserve Lamp', modes: ['Power'], states: ['off', 'on'] },
    { id: 'shade', name: 'Entry Shade Control', modes: ['private', 'FanMode'], states: ['Case;ready', 'slow heat;_Case'] }
  ] as const
  const shadeState = 'Case;_Case'
  const ruleStates = [shadeState, 'slow heat;_']
  const excludedStates = ['Case;ready', 'slow heat;']
  const scene: PortableSceneFile = {
    schema: 'iot-verify.board-scene', version: 6,
    templates: modeledDevices.map(device => ({ name: device.name, manifest: {
      Name: device.name, Modes: [...device.modes], InitState: device.states[0],
      WorkingStates: device.states.map(Name => ({ Name, Trust: 'trusted', Privacy: 'public' })),
      APIs: device.id === 'reserve_lamp' ? [{ Name: 'switchOn', StartState: 'off', EndState: 'on', Signal: false }] : [],
      Transitions: device.id === 'shade' ? [{ Name: 'Set auxiliary state', StartState: 'Case;ready', EndState: ';_Case',
        Trigger: { Attribute: 'FanMode', Relation: '=', Value: 'ready' } }] : []
    } })),
    devices: modeledDevices.map((device, index) => ({ id: device.id, label: device.name, templateName: device.name,
      state: device.states[0], position: { x: 60 + index * 220, y: 150 }, width: 176, height: 128 })),
    environmentVariables: [],
    rules: [{ name: 'Turn on the reserve when the source is on', toId: 'reserve_lamp', toApi: 'switchOn',
      sources: [{ fromId: 'source_lamp', fromApi: 'Power', itemType: 'mode', relation: '=', value: 'on' },
        { fromId: 'shade', fromApi: 'state', itemType: 'state', relation: 'IN', value: ruleStates.join(' | ') }] }],
    specs: [{ name: 'Keep the reserve off', templateId: '3', ifConditions: [], thenConditions: [],
      aConditions: [{ deviceId: 'reserve_lamp', targetType: 'mode', key: 'Power', relation: '=', value: 'on' },
        { deviceId: 'shade', targetType: 'state', key: 'state', relation: 'NOT_IN', value: excludedStates.join(' | ') }] }]
  }
  const imported = await importFile(page, {
    name: 'cross-device-modes.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(scene))
  })
  expect(imported.status, JSON.stringify(imported.body)).toBe(200)
  await expect(page.getByText(/^Scene replaced:/)).toBeVisible()

  const exported: PortableSceneFile = await exportFile(page)
  expect(exported.rules[0]?.sources.find(source => source.fromId === 'shade')).toMatchObject({
    relation: 'in', value: ruleStates.join(' | ')
  })
  expect(exported.specs[0]?.aConditions.find(condition => condition.deviceId === 'shade')).toMatchObject({
    relation: 'not in', value: excludedStates.join(' | ')
  })

  await page.getByTestId('inspector-tab-rules').click()
  await page.getByTestId(`edit-rule-${imported.body.data.rules[0].id}`).click()
  await page.getByTestId('rule-edit-source-1').click()
  const ruleValues = page.locator('#rule-source-value')
  await expect(ruleValues).toHaveValues(ruleStates)
  await ruleValues.selectOption([shadeState])
  await ruleValues.selectOption(ruleStates)
  await expect(ruleValues).toHaveValues(ruleStates)
  await page.getByTestId('rule-add-source').click()
  await page.getByTestId('rule-reset').click()

  await page.getByTestId('inspector-tab-specs').click()
  await page.getByTestId(`edit-spec-${imported.body.data.specs[0].id}`).click()
  await page.getByRole('button', { name: 'Edit condition 2', exact: true }).click()
  const specValues = page.getByTestId('spec-condition-value')
  await expect(specValues).toHaveValues(excludedStates)
  await specValues.selectOption([excludedStates[0]!])
  await specValues.selectOption(excludedStates)
  await expect(specValues).toHaveValues(excludedStates)
  await page.getByTestId('spec-condition-save').click()
  await expect(page.getByTestId('spec-create')).toBeEnabled()
  await page.getByTestId('spec-cancel-edit').click()

  const simulationResponse = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/simulate/traces')
  await page.getByTestId('open-simulation-panel').click()
  await clickUnderTooltip(page, page.getByTestId('simulation-mode-sync'))
  await page.getByTestId('simulation-steps-input').fill('2')
  await clickUnderTooltip(page, page.getByTestId('run-simulation'))
  const simulated = await simulationResponse
  expect(simulated.ok(), await simulated.text()).toBe(true)
  const simulation = validateSimulationTrace((await simulated.json()).data)
  expect(simulation.enablePrivacy).toBe(false)
  expect(simulation.modelComplete).toBe(true)
  expect(simulation.states[0]?.devices).toEqual(expect.arrayContaining([
    expect.objectContaining({ deviceId: 'source_lamp', mode: 'Power', state: 'on' }),
    expect.objectContaining({ deviceId: 'reserve_lamp', mode: 'Power', state: 'off' }),
    expect.objectContaining({ deviceId: 'shade', mode: 'private;FanMode', state: 'Case;ready' })
  ]))
  expect(simulation.states[1]?.devices).toEqual(expect.arrayContaining([
    expect.objectContaining({ deviceId: 'shade', mode: 'private;FanMode', state: shadeState }),
    expect.objectContaining({ deviceId: 'reserve_lamp', mode: 'Power', state: 'off' })
  ]))
  expect(simulation.states[2]?.devices).toEqual(expect.arrayContaining([
    expect.objectContaining({ deviceId: 'reserve_lamp', mode: 'Power', state: 'on' })
  ]))
  await expect(page.getByTestId('simulation-timeline')).toBeVisible()
  await clickUnderTooltip(page, page.getByTestId('simulation-timeline-state-1'))
  await page.getByTestId('simulation-timeline-details-tab').click()
  const shadeReadings = page.getByTestId('playback-state-devices').getByRole('article').filter({ hasText: 'Entry Shade Control' })
  await expect(shadeReadings.locator('dt')).toHaveText(['private', 'FanMode'])
  await expect(shadeReadings.locator('dd')).toHaveText(['Case', '_Case'])
  await page.getByTestId('simulation-timeline-close').click()

  const verificationResponse = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/verify')
  await page.getByTestId('open-verification-panel').click()
  await clickUnderTooltip(page, page.getByTestId('verification-mode-sync'))
  await clickUnderTooltip(page, page.getByTestId('run-verification'))
  const verified = await verificationResponse
  expect(verified.ok(), await verified.text()).toBe(true)
  const verification = validateVerificationResult((await verified.json()).data)
  expect(verification.enablePrivacy).toBe(false)
  expect(verification.modelComplete).toBe(true)
  expect(verification.outcome).toBe('VIOLATED')
  const traceId = verification.traces[0]?.id
  expect(traceId).toBeGreaterThan(0)
  const repairPath = `/api/verify/traces/${traceId}/fix`
  await page.getByTestId('verification-trace-fix').click()
  await page.getByTestId('fix-strategy-remove').click()
  const searched = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === repairPath)
  await page.getByTestId('fix-try-current').click()
  const proposal = await searched
  expect(proposal.ok(), await proposal.text()).toBe(true)
  const signed = (await proposal.json()).data.suggestions[0]?.suggestionToken
  expect(signed).toEqual(expect.any(String))
  const apply = page.getByTestId('fix-apply-current')
  await expect(apply).toBeEnabled()

  const headers = { Authorization: `Bearer ${auth.token}` }
  const beforeEdit = await readBoardModelFingerprints(request, auth.token)
  expect(beforeEdit.modelSemanticsVersion).toBe(verification.modelSnapshot.modelSemanticsVersion)
  expect(beforeEdit.verification).toBe(verification.modelSnapshot.modelFingerprint)
  const original: RuleDto = imported.body.data.rules[0]
  const revisedName = 'Turn on the reserve after the specified shade states'
  const equivalentStates = ['slow heat;', shadeState]
  const desired: RuleDto = { ...original, ruleString: revisedName, conditions: original.conditions.map(condition =>
    condition.deviceName === 'shade' ? { ...condition, value: equivalentStates.join(' | ') } : condition) }
  const edited = await request.put(`${apiBaseURL}/api/board/rules/${original.id}`, { headers, data: { expected: original, desired } })
  expect(edited.ok(), await edited.text()).toBe(true)
  expect((await edited.json()).data.affectedItem.ruleString).toBe(revisedName)
  expect(await readBoardModelFingerprints(request, auth.token)).toEqual(beforeEdit)

  const refreshed = page.waitForResponse(response => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/board/model-fingerprints')
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  expect((await refreshed).ok()).toBe(true)
  await expect(page.getByTestId('verification-result-stale-banner')).toHaveCount(0)
  await expect(page.getByTestId('verification-trace-fix')).toBeVisible()

  await apply.click()
  const applied = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `${repairPath}/apply`)
  await page.locator('.el-message-box').getByRole('button', { name: 'Remove Rules and Apply', exact: true }).click()
  const appliedResult = await applied
  expect(appliedResult.request().postDataJSON().suggestionToken).toBe(signed)
  expect(appliedResult.ok(), await appliedResult.text()).toBe(true)
  expect((await appliedResult.json()).data).toMatchObject({ applied: true, verificationEvidenceReused: true, rules: [] })
})

for (const collision of ['different definition']) {
  test(`a ${collision} conflict rolls back and offers a confirmed reusable copy without overwriting templates`, async ({ page, request }, testInfo) => {
    const auth = await createAuthenticatedUser(request, { usernamePrefix: 'templateConflict' })
    const headers = { Authorization: `Bearer ${auth.token}` }
    const theme = collision === 'different definition' ? 'light' : 'dark'
    await openBoard(page, auth, theme)
    expect((await importFile(page, path.join(examples, 'default-climate-conflict-scene.json'))).status).toBe(200)
    const incoming = JSON.parse(await readFile(path.join(examples, 'household-entry-scene.json'), 'utf8'))
    const manifest = incoming.templates.find((template: { name: string }) => template.name === 'Door Access Request').manifest
    const targetName = collision === 'different name casing' ? 'DOOR ACCESS REQUEST' : 'Door Access Request'
    expect((await request.post(`${apiBaseURL}/api/board/templates`, {
      headers, data: { name: targetName, manifest: { ...manifest, Name: targetName,
        Description: collision === 'different definition' ? 'Target account definition' : manifest.Description } }
    })).status()).toBe(200)
    const current = (await readAccountScene(request, auth))[0].nodes[0]
    expect((await request.patch(`${apiBaseURL}/api/board/nodes/label?nodeId=${encodeURIComponent(current.id)}`, {
      headers, data: { expectedLabel: current.label, label: 'Retained scene device' }
    })).status()).toBe(200)
    const before = await readAccountScene(request, auth)
    expect(before[2].canUndo).toBe(true)
    // Resolve a new dependency before encountering the conflicting existing one, exercising DB rollback.
    incoming.templates.unshift({ name: 'Rollback Probe', manifest: { ...manifest, Name: 'Rollback Probe' } })
    incoming.devices.unshift({ ...incoming.devices.find((device: { templateName: string }) => device.templateName === 'Door Access Request'),
      id: 'rollback_probe', label: 'Rollback probe', templateName: 'Rollback Probe' })
    const preview = await request.get(`${apiBaseURL}/api/board/replacement-preview`, { headers })
    const rejected = await request.post(`${apiBaseURL}/api/board/scene`, {
      headers, data: { impactToken: (await preview.json()).data.impactToken, scene: incoming }
    })
    expect(rejected.status()).toBe(409)
    expect((await rejected.json()).data.reasonCode).toBe('SCENE_TEMPLATE_MISMATCH')
    expect(await readAccountScene(request, auth)).toEqual(before)
    const file = { name: 'conflicting-scene.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(incoming)) }
    for (const viewport of [{ width: 320, height: 480 }, { width: 1280, height: 800 }]) {
      await page.setViewportSize(viewport)
      await page.getByTestId('scene-import-file').setInputFiles(file)
      const dialog = page.getByRole('dialog', { name: 'Replace the current scene?' })
      await expect(dialog.getByTestId('scene-import-repairs')).toContainText('Door Access Request → Door Access Request (imported)')
      const confirm = dialog.getByRole('button', { name: 'Apply changes and replace' })
      await expect(confirm).toBeInViewport()
      await expect(dialog.getByTestId('scene-import-consequence')).toBeInViewport({ ratio: 1 })
      expect(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
      await page.screenshot({ path: testInfo.outputPath(`keep-both-${theme}-${viewport.width}.png`) })
      const cancel = dialog.getByRole('button', { name: 'Cancel', exact: true })
      await cancel.focus()
      await page.keyboard.press('Tab')
      await expect(confirm).toBeFocused()
      await page.keyboard.press('Shift+Tab')
      await expect(cancel).toBeFocused()
      await page.keyboard.press('Enter')
      await expect(dialog).toBeHidden()
      expect(await readAccountScene(request, auth)).toEqual(before)
    }
    const imported = await importFile(page, file)
    expect(imported.status).toBe(200)
    expect(imported.body.data.createdTemplates).toHaveLength(3)
    await expect(page.getByText(/^Scene replaced:/)).toBeVisible()
    const firstExport = await exportFile(page)
    const after = await readAccountScene(request, auth)
    expect(after[1].find((template: { name: string }) => template.name === targetName))
      .toEqual(before[1].find((template: { name: string }) => template.name === targetName))
    expect(after[1]).toHaveLength(before[1].length + 3)
    const repeated = await importFile(page, file)
    expect(repeated.status).toBe(200)
    expect(repeated.body.data.createdTemplates).toHaveLength(0)
    await expect(page.getByText(/^Scene replaced:/)).toBeVisible()
    expect(await exportFile(page)).toEqual(firstExport)
    expect((await readAccountScene(request, auth))[1]).toEqual(after[1])
  })
}

test('a rejected incoming environment preserves the complete previous scene and its journal', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'sceneReject' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  await openBoard(page, auth)
  expect((await importFile(page, path.join(examples, 'elderly-care-comprehensive-scene.json'))).status).toBe(200)
  const renamed = await request.patch(`${apiBaseURL}/api/board/nodes/label?nodeId=smart_lamp`, {
    headers, data: { expectedLabel: 'Bedside Lamp', label: 'Retained care lamp' }
  })
  expect(renamed.status()).toBe(200)
  const readState = async () => {
    const responses = await Promise.all([
      request.get(`${apiBaseURL}/api/board/snapshot`, { headers }),
      request.get(`${apiBaseURL}/api/board/edits/availability`, { headers })
    ])
    for (const response of responses) expect(response.status()).toBe(200)
    return Promise.all(responses.map(async response => (await response.json()).data))
  }
  const before = await readState()
  expect(before[1].canUndo).toBe(true)
  const scene = JSON.parse(await readFile(path.join(examples, 'dynamic-home-repair-scene.json'), 'utf8'))
  const index = scene.environmentVariables.findIndex((entry: { name: string }) => entry.name === 'motion')
  expect(index).toBeGreaterThanOrEqual(0)
  scene.environmentVariables[index].value = 'active'
  const rejected = await importFile(page, {
    name: 'invalid-dynamic-home.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(scene))
  })
  expect(rejected.status).toBe(422)
  expect(Object.keys(rejected.body.data.errors)).toContain(`environmentVariables[${index}].value`)
  await expect(page.getByText('Scene validation failed', { exact: true })).toBeVisible()
  await expect(page.getByText(/The scene replacement did not change the current board/)).toBeVisible()
  await page.getByRole('dialog', { name: 'Scene validation failed' })
    .getByRole('button', { name: 'Confirm', exact: true }).click()
  await page.reload()
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
  const after = await readState()
  expectSameBoardSnapshot(after[0], before[0])
  expect(after[1]).toEqual(before[1])
  await expect(page.getByTestId('board-undo')).toBeEnabled()
  await expect(page.locator('.device-node').filter({ hasText: 'Retained care lamp' })).toBeVisible()
  await testInfo.attach('rejected-scene-replacement', {
    contentType: 'application/json', body: Buffer.from(JSON.stringify({ userId: auth.userId, rejected, before, after }, null, 2))
  })
})

test('a template-library change invalidates a pending clear without clearing the scene', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'templateStale' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  await openBoard(page, auth)
  const file = path.join(examples, 'household-entry-scene-zh.json')
  expect((await importFile(page, file)).status).toBe(200)
  await clickUnderTooltip(page, page.getByTestId('scene-clear'))
  const dialog = page.getByRole('dialog', { name: 'Clear Scene', exact: true })
  await expect(dialog).toContainText('including 2 custom templates')
  const incoming = JSON.parse(await readFile(file, 'utf8'))
  const template = incoming.templates.find((item: { name: string }) => item.name === 'Door Access Request')
  const created = await request.post(`${apiBaseURL}/api/board/templates`, {
    headers, data: { name: 'Another Request', manifest: { ...template.manifest, Name: 'Another Request' } }
  })
  expect(created.status()).toBe(200)
  const response = page.waitForResponse(reply => reply.request().method() === 'POST'
    && new URL(reply.url()).pathname === '/api/board/batch')
  await dialog.getByRole('button', { name: 'Clear', exact: true }).click()
  expect((await response).status()).toBe(409)
  await expect(page.getByText(/template library or edit history changed after confirmation/)).toBeVisible()
  await expect(page.locator('.device-node')).toHaveCount(4)
  await expect(page.getByTestId('template-group-custom').locator('.template-card')).toHaveCount(3)
})
