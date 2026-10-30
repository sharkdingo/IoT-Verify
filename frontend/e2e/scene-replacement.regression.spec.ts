import { readFile } from 'node:fs/promises'
import path from 'node:path'
import type { APIRequestContext, Page } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'
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
  expect(after).toEqual(before)
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
