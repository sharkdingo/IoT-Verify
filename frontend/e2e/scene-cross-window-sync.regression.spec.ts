import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { type Page } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'

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
  expect(await page.evaluate(() => document.visibilityState)).toBe('visible')
}

test('a confirmed scene import refreshes another visible window like a targeted device edit', async ({ browser, request }, testInfo) => {
  test.setTimeout(90_000)
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'sceneSync' })
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  try {
    const importer = await context.newPage()
    const observer = await context.newPage()
    await openBoard(importer, auth)
    await openBoard(observer, auth)

    // A normal targeted write is the control for the real BroadcastChannel and snapshot path.
    const controlRefresh = importer.waitForResponse(response =>
      response.request().method() === 'GET'
      && new URL(response.url()).pathname === '/api/board/snapshot')
    await observer.getByTestId('control-tab-devices').click()
    const templateSelect = observer.getByTestId('single-device-template')
    await expect(templateSelect.locator('option[value="Light"]')).toHaveCount(1)
    await templateSelect.selectOption('Light')
    await observer.getByTestId('single-device-name').fill('Observer original lamp')
    await observer.getByTestId('single-device-create').click()
    await controlRefresh
    await expect(importer.locator('.device-node').filter({ hasText: 'Observer original lamp' })).toBeVisible()
    await expect(observer.getByTestId('board-undo')).toBeEnabled()

    const observedReads = { snapshot: 0, fingerprints: 0, undo: 0 }
    observer.on('request', req => {
      if (req.method() !== 'GET') return
      const pathname = new URL(req.url()).pathname
      if (pathname === '/api/board/snapshot') observedReads.snapshot += 1
      if (pathname === '/api/board/model-fingerprints') observedReads.fingerprints += 1
      if (pathname === '/api/board/edits/availability') observedReads.undo += 1
    })
    await observer.evaluate(() => {
      const events = { focus: 0, visibility: 0 }
      ;(window as any).__sceneSyncEvents = events
      window.addEventListener('focus', () => { events.focus += 1 })
      document.addEventListener('visibilitychange', () => { events.visibility += 1 })
    })
    const manifest = JSON.parse(await readFile(path.resolve(
      process.cwd(), '..', 'backend', 'src', 'main', 'resources', 'deviceTemplate', 'Light.json'
    ), 'utf8'))
    const scene = {
      schema: 'iot-verify.board-scene', version: 5,
      templates: [{ name: 'Light', manifest }],
      devices: [{
        id: 'imported_lamp', templateName: 'Light', label: 'Imported replacement lamp',
        position: { x: 50, y: 60 }, state: 'off', width: 176, height: 128
      }],
      environmentVariables: [{ name: 'illuminance', value: '10', trust: 'untrusted', privacy: 'public' }],
      rules: [], specs: []
    }
    await importer.getByTestId('scene-import-file').setInputFiles({
      name: 'scene-sync-regression.json', mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify(scene))
    })
    const importResponse = importer.waitForResponse(response =>
      response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/board/scene')
    await importer.getByRole('dialog', { name: 'Replace the current scene?' })
      .getByRole('button', { name: 'Replace scene' }).click()
    expect((await importResponse).ok()).toBe(true)
    await expect(importer.locator('.device-node').filter({ hasText: 'Imported replacement lamp' })).toBeVisible()

    await expect.soft(observer.locator('.device-node').filter({ hasText: 'Imported replacement lamp' }))
      .toBeVisible({ timeout: 5_000 })
    const headers = { Authorization: `Bearer ${auth.token}` }
    const [snapshot, undo, fingerprints] = await Promise.all([
      request.get(`${apiBaseURL}/api/board/snapshot`, { headers }).then(response => response.json()),
      request.get(`${apiBaseURL}/api/board/edits/availability`, { headers }).then(response => response.json()),
      request.get(`${apiBaseURL}/api/board/model-fingerprints`, { headers }).then(response => response.json())
    ])
    const lifecycle = await observer.evaluate(() => ({
      visibilityState: document.visibilityState,
      events: (window as any).__sceneSyncEvents
    }))
    await testInfo.attach('scene-sync-evidence', {
      contentType: 'application/json', body: Buffer.from(JSON.stringify({
        observedReads, lifecycle, observerNodes: await observer.locator('.device-node').allTextContents(),
        observerUndoEnabled: await observer.getByTestId('board-undo').isEnabled(),
        persistedSnapshot: snapshot.data, persistedUndo: undo.data, persistedFingerprints: fingerprints.data
      }, null, 2))
    })
    expect(lifecycle).toEqual({ visibilityState: 'visible', events: { focus: 0, visibility: 0 } })
    expect(snapshot.data.nodes.map((node: { id: string }) => node.id)).toEqual(['imported_lamp'])
    expect.soft(observedReads.snapshot).toBeGreaterThan(0)
    expect.soft(observedReads.fingerprints).toBeGreaterThan(0)
    await expect.soft(observer.getByTestId('board-undo')).toBeDisabled()
    await expect.soft(observer.locator('.device-node').filter({ hasText: 'Observer original lamp' })).toHaveCount(0)
  } finally {
    await context.close()
  }
})
