import { readFile, readdir } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import path from 'node:path'
import type { APIRequestContext, Page } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'
import { clickUnderTooltip } from './support/tooltips'
import { expandControlTool } from './support/controlCenter'

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

for (const collision of ['different definition', 'different name casing']) {
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

test('a full template library reuses a matching numbered copy when earlier suffixes are absent', async ({ page, request }) => {
  test.setTimeout(120_000)
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'sparseImportCopy' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  const incoming = JSON.parse(await readFile(path.join(examples, 'household-entry-scene.json'), 'utf8'))
  const original = incoming.templates.find((template: { name: string }) => template.name === 'Door Access Request')
  const copyName = 'Door Access Request (imported 99)'
  const createTemplate = async (name: string, manifest: Record<string, unknown>) => {
    const reply = await request.post(`${apiBaseURL}/api/board/templates`, { headers, data: { name, manifest } })
    expect(reply.status(), await reply.text()).toBe(200)
  }
  await createTemplate(original.name, { ...original.manifest, Description: 'Retained different definition' })
  await createTemplate(copyName, { ...original.manifest, Name: copyName })
  const occupancy = incoming.templates.find((template: { name: string }) => template.name === 'Occupancy Sensor')
  await createTemplate(occupancy.name, occupancy.manifest)
  const [, catalog] = await readAccountScene(request, auth)
  for (let index = catalog.length; index < 100; index++) {
    const name = `Unused request ${index}`
    await createTemplate(name, { ...original.manifest, Name: name })
  }
  const [, before] = await readAccountScene(request, auth)
  expect(before).toHaveLength(100)
  await openBoard(page, auth)
  const imported = await importFile(page, {
    name: 'reuse-numbered-copy.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(incoming))
  })
  expect(imported.status).toBe(200)
  expect(imported.body.data.createdTemplates).toHaveLength(0)
  await expect(page.getByText(/^Scene replaced:/)).toBeVisible()
  expect((await readAccountScene(request, auth))[1]).toEqual(before)
  const exported = await exportFile(page)
  expect(exported.devices.find((device: { id: string }) => device.id === 'entry_1').templateName).toBe(copyName)
  expect(exported.rules).toEqual(incoming.rules)
  expect(exported.specs).toEqual(incoming.specs)
})

test('state-variable corrections are previewed before replacement and raw contradictions leave the scene unchanged', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'stateRepair' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  await openBoard(page, auth)
  expect((await importFile(page, path.join(examples, 'household-entry-scene.json'))).status).toBe(200)
  const manifest = JSON.parse(await readFile(path.resolve(process.cwd(), '..', 'backend/src/main/resources/deviceTemplate/Car.json'), 'utf8'))
  const scene = { schema: 'iot-verify.board-scene', version: 6, templates: [{ name: 'Car', manifest }],
    devices: [{ id: 'car', templateName: 'Car', label: 'My car', state: 'away', position: { x: 0, y: 0 }, width: 176, height: 128,
      variables: [{ name: 'location', value: 'garage' }] }], environmentVariables: [], rules: [], specs: [] }
  const before = await readAccountScene(request, auth)
  const preview = await request.get(`${apiBaseURL}/api/board/replacement-preview`, { headers })
  const rejected = await request.post(`${apiBaseURL}/api/board/scene`, {
    headers, data: { impactToken: (await preview.json()).data.impactToken, scene }
  })
  expect(rejected.status()).toBe(422)
  expect(Object.keys((await rejected.json()).data.errors)).toContain('devices[0].variables[0].value')
  expect(await readAccountScene(request, auth)).toEqual(before)
  const file = { name: 'car.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(scene)) }
  await page.getByTestId('scene-import-file').setInputFiles(file)
  const dialog = page.getByRole('dialog', { name: 'Replace the current scene?' })
  await expect(dialog.getByTestId('scene-import-repairs')).toContainText('My car: change location from garage to away, as required by state away.')
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click()
  expect(await readAccountScene(request, auth)).toEqual(before)
  expect((await importFile(page, file)).status).toBe(200)
  await expect(page.getByText(/^Scene replaced:/)).toBeVisible()
  const exported = await exportFile(page)
  expect(exported.devices[0].variables[0].value).toBe('away')
})

test('equivalent security-label spellings import successfully and remain stable on export', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'labelRoundtrip' })
  await openBoard(page, auth)
  const scene = JSON.parse(await readFile(path.join(examples, 'default-away-mode-unlock-scene.json'), 'utf8'))
  scene.specs.at(-1).aConditions[0].relation = 'in'
  scene.specs.at(-1).aConditions[0].value = 'PRIVATE;public'
  const file = { name: 'labels.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(scene)) }
  expect((await importFile(page, file)).status).toBe(200)
  await expect(page.getByText(/^Scene replaced:/)).toBeVisible()
  const exported = await exportFile(page)
  expect(exported.specs.at(-1).aConditions[0].value).toBe('private, public')
  expect((await importFile(page, { ...file, buffer: Buffer.from(JSON.stringify(exported)) })).status).toBe(200)
  await expect(page.getByText(/^Scene replaced:/)).toBeVisible()
  expect(await exportFile(page)).toEqual(exported)
})

test('the scene API canonicalizes padded template names consistently on initial and repeated imports', async ({ request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'rawRoundtrip' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  const scene = JSON.parse(await readFile(path.join(examples, 'household-entry-scene.json'), 'utf8'))
  const snapshot = scene.templates.find((template: { name: string }) => template.name === 'Door Access Request')
  snapshot.name = ' Door Access Request '
  snapshot.manifest.Name = ' Door Access Request '
  for (let attempt = 0; attempt < 2; attempt++) {
    const preview = await request.get(`${apiBaseURL}/api/board/replacement-preview`, { headers })
    expect(preview.status()).toBe(200)
    const response = await request.post(`${apiBaseURL}/api/board/scene`, {
      headers, data: { impactToken: (await preview.json()).data.impactToken, scene }
    })
    expect(response.status(), await response.text()).toBe(200)
    expect((await response.json()).data.createdTemplates).toHaveLength(attempt === 0 ? 2 : 0)
  }
  const [, templates] = await readAccountScene(request, auth)
  expect(templates.filter((template: { name: string }) => template.name === 'Door Access Request')).toHaveLength(1)
  expect(templates.find((template: { name: string }) => template.name === 'Door Access Request').manifest.Name)
    .toBe('Door Access Request')
})

test('all shipped scenes replace one another on an existing account in both directions', async ({ page, request }, testInfo) => {
  test.setTimeout(600_000)
  const files = (await readdir(examples)).filter(name => name.endsWith('.json')).sort()
  const first = ['elderly-care-comprehensive-scene.json', 'dynamic-home-repair-scene.json']
  for (const name of first) expect(files).toContain(name)
  const forward = [...first, ...files.filter(name => !first.includes(name))]
  const order = [...forward, ...[...forward].reverse()]
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'sceneReplace' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  const evidence: unknown[] = []
  const pageErrors: string[] = []
  page.on('pageerror', error => pageErrors.push(error.message))
  await openBoard(page, auth)
  try {
    for (const name of order) {
      const filename = path.join(examples, name)
      const bytes = await readFile(filename)
      const scene = JSON.parse(bytes.toString('utf8'))
      const imported = await importFile(page, filename)
      expect(imported.status, JSON.stringify({ name, ...imported })).toBe(200)
      expect(imported.body.code).toBe(200)
      await expect(page.locator('.device-node')).toHaveCount(scene.devices.length)
      await page.reload()
      await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
      await expect(page.locator('.device-node')).toHaveCount(scene.devices.length)
      const response = await request.get(`${apiBaseURL}/api/board/snapshot`, { headers })
      expect(response.status()).toBe(200)
      const snapshot = (await response.json()).data
      expect(snapshot.nodes.map((node: { id: string }) => node.id).sort())
        .toEqual(scene.devices.map((device: { id: string }) => device.id).sort())
      expect([...snapshot.nodes].sort((a, b) => a.id.localeCompare(b.id)))
        .toEqual([...imported.body.data.nodes].sort((a, b) => a.id.localeCompare(b.id)))
      expect(snapshot.rules).toHaveLength(scene.rules.length)
      expect(snapshot.rules).toEqual(imported.body.data.rules)
      expect(snapshot.specifications).toHaveLength(scene.specs.length)
      expect(snapshot.specifications).toEqual(imported.body.data.specs)
      const environment = (entries: Array<{ name: string, value: unknown, trust: string, privacy: string }>) =>
        entries.map(entry => ({ ...entry, value: String(entry.value) }))
          .sort((a, b) => a.name.localeCompare(b.name))
      expect(environment(snapshot.environmentVariables)).toEqual(environment(scene.environmentVariables))
      await expect(page.getByTestId('board-undo')).toBeDisabled()
      evidence.push({ name, sha256: createHash('sha256').update(bytes).digest('hex'),
        status: imported.status, devices: snapshot.nodes.length, rules: snapshot.rules.length,
        specifications: snapshot.specifications.length, environmentVariables: snapshot.environmentVariables })
    }
    expect(pageErrors).toEqual([])
  } finally {
    await testInfo.attach('continuous-scene-imports', {
      contentType: 'application/json', body: Buffer.from(JSON.stringify({ userId: auth.userId, files, evidence, pageErrors }, null, 2))
    })
  }
})

test('scene template usage survives import, export and clear without deleting the reusable library', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'templateScope' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  await openBoard(page, auth)
  await expandControlTool(page, 'control-template-repository')
  await expect(page.getByTestId('template-library-summary')).toContainText('In use 0 (0 custom)')
  const file = path.join(examples, 'household-entry-scene-zh.json')
  expect((await importFile(page, file)).status).toBe(200)
  await expect(page.getByTestId('template-library-summary')).toContainText('In use 4 (2 custom)')
  await page.getByTestId('template-scope-scene').click()
  await expect(page.locator('.template-card')).toHaveCount(4)
  await expect(page.getByTestId('template-group-custom').locator('.template-card')).toHaveCount(2)

  const download = page.waitForEvent('download')
  await clickUnderTooltip(page, page.getByTestId('scene-export'))
  const downloadPath = await (await download).path()
  expect(downloadPath).not.toBeNull()
  const exported = JSON.parse(await readFile(downloadPath!, 'utf8'))
  expect(exported.templates).toHaveLength(4)
  await expect(page.getByText(/4 templates \(2 custom\).*Only templates used/)).toBeVisible()
  await clickUnderTooltip(page, page.getByTestId('scene-clear'))
  const dialog = page.getByRole('dialog', { name: 'Clear Scene', exact: true })
  await expect(dialog).toContainText('Templates in use: 4 → 0 (custom: 2 → 0)')
  await expect(dialog).toContainText('including 2 custom templates')
  await expect(page.locator('.el-message--success, .el-message--info')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('template-clear-preview.png') })
  await dialog.getByRole('button', { name: 'Clear', exact: true }).click()
  await expect(page.locator('.device-node')).toHaveCount(0)
  await expect(page.getByTestId('template-library-summary')).toContainText('In use 0 (0 custom)')
  await expect(page.locator('.template-card')).toHaveCount(0)
  await expect(page.getByText('No templates used in this scene', { exact: true })).toBeVisible()
  const emptyDownload = page.waitForEvent('download')
  await clickUnderTooltip(page, page.getByTestId('scene-export'))
  const emptyPath = await (await emptyDownload).path()
  expect(emptyPath).not.toBeNull()
  expect(JSON.parse(await readFile(emptyPath!, 'utf8')).templates).toEqual([])
  await page.getByTestId('template-scope-all').click()
  await expect(page.getByTestId('template-group-custom').locator('.template-card')).toHaveCount(2)
  const preview = await request.get(`${apiBaseURL}/api/board/replacement-preview`, { headers })
  const state = (await preview.json()).data
  expect(state.deviceCount).toBe(0)
  expect(state.templates.filter((template: { defaultTemplate: boolean }) => !template.defaultTemplate)).toHaveLength(2)
  expect(state.templates.every((template: { deviceCount: number }) => template.deviceCount === 0)).toBe(true)
  await expect(page.locator('.el-message')).toHaveCount(0)
  await page.getByTestId('template-group-custom').scrollIntoViewIfNeeded()
  await page.screenshot({ path: testInfo.outputPath('template-library-empty-scene.png') })
  expect((await importFile(page, file)).status).toBe(200)
  await expect(page.getByTestId('template-group-custom').locator('.template-card')).toHaveCount(2)
  await expect(page.getByTestId('template-library-summary')).toContainText('In use 4 (2 custom)')
})

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
  expect(after).toEqual(before)
  await expect(page.getByTestId('board-undo')).toBeEnabled()
  await expect(page.locator('.device-node').filter({ hasText: 'Retained care lamp' })).toBeVisible()
  await testInfo.attach('rejected-scene-replacement', {
    contentType: 'application/json', body: Buffer.from(JSON.stringify({ userId: auth.userId, rejected, before, after }, null, 2))
  })
})

test('scene confirmation compares both scopes and keeps consequences and actions reachable on short narrow screens', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'scenePreview' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  const current = JSON.parse(await readFile(path.join(examples, 'dynamic-home-repair-scene.json'), 'utf8'))
  const incomingFile = path.join(examples, 'elderly-care-comprehensive-scene.json')
  const incoming = JSON.parse(await readFile(incomingFile, 'utf8'))
  const previewReply = await request.get(`${apiBaseURL}/api/board/replacement-preview`, { headers })
  expect(previewReply.status()).toBe(200)
  const seeded = await request.post(`${apiBaseURL}/api/board/scene`, {
    headers, data: { impactToken: (await previewReply.json()).data.impactToken, scene: current }
  })
  expect(seeded.status()).toBe(200)
  await openBoard(page, auth)
  for (const viewport of [{ width: 320, height: 480 }, { width: 1280, height: 800 }]) {
    await page.setViewportSize(viewport)
    await page.getByTestId('scene-import-file').setInputFiles(incomingFile)
    const dialog = page.getByRole('dialog', { name: 'Replace the current scene?' })
    const rows = dialog.getByTestId('scene-import-comparison').locator('tbody tr')
    for (const [index, collection] of ['devices', 'environmentVariables', 'rules', 'specs'].entries()) {
      await expect(rows.nth(index).locator('td').first()).toHaveText(String(current[collection].length))
      await expect(rows.nth(index).locator('td').last()).toHaveText(String(incoming[collection].length))
    }
    await expect(dialog.getByTestId('scene-import-consequence')).toBeVisible()
    const details = dialog.getByTestId('scene-import-template-rule-details')
    await expect(details).toHaveJSProperty('open', false)
    expect(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    const confirm = dialog.getByRole('button', { name: 'Replace scene', exact: true })
    await expect(confirm).toBeInViewport({ ratio: 1 })
    await details.locator('summary').press('Enter')
    await expect(details).toHaveJSProperty('open', true)
    await expect(details).toContainText('first matching rule wins')
    await expect(confirm).toBeInViewport({ ratio: 1 })
    await page.screenshot({ path: testInfo.outputPath(`scene-import-${viewport.width}.png`) })
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).click()
  }
  const snapshot = await request.get(`${apiBaseURL}/api/board/snapshot`, { headers })
  expect((await snapshot.json()).data.nodes.map((node: { id: string }) => node.id).sort())
    .toEqual(current.devices.map((device: { id: string }) => device.id).sort())
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
