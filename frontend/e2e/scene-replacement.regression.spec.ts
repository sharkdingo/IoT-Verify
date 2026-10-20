import { readFile, readdir } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import path from 'node:path'
import type { Page } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'
import { clickUnderTooltip } from './support/tooltips'

const examples = path.resolve(process.cwd(), '..', 'docs', 'examples')

const openBoard = async (page: Page, auth: AuthUser) => {
  await page.addInitScript(({ token, user }) => {
    localStorage.setItem('iot_verify_token', token)
    localStorage.setItem('iot_verify_user', JSON.stringify(user))
    localStorage.setItem('locale', 'en')
    localStorage.setItem('iot_verify_theme', 'light')
  }, {
    token: auth.token,
    user: { userId: auth.userId, phone: auth.phone, username: auth.username }
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
  await expect(rows).toHaveCount(4)
  for (const [index, count] of counts.entries()) {
    await expect(rows.nth(index).locator('td').last()).toHaveText(String(count))
  }
  await expect(confirmation.getByTestId('scene-import-consequence')).toBeVisible()
  await expect(confirmation.getByTestId('scene-import-template-rule-details')).toHaveJSProperty('open', false)
  await confirmation.getByRole('button', { name: 'Replace scene', exact: true }).click()
  const reply = await response
  return { status: reply.status(), body: await reply.json() }
}

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
