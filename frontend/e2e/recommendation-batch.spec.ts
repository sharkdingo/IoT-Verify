import { readFile } from 'node:fs/promises'
import path from 'node:path'
import type { APIRequestContext, APIResponse, Page } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'
import { openRecommendation, type RecommendationKind } from './support/recommendations'
import { clickUnderTooltip } from './support/tooltips'
import { expandCreation } from './support/controlCenter'

const unwrap = async (response: APIResponse) => {
  expect(response.ok(), await response.text()).toBeTruthy()
  const body = await response.json()
  expect(body.code, JSON.stringify(body)).toBe(200)
  return body.data
}

for (const background of [false]) test(`a cross-tab edit stops a device batch (${background ? 'background' : 'visible'}) even when its first response already includes that edit`, async ({ page, request }) => {
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
    // Headless tabs stay visible; exercise the browser's real hidden-tab refresh branch.
    if (background) await page.evaluate(() => {
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
      document.dispatchEvent(new Event('visibilitychange'))
    })
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
    if (background) {
      const firstWrite = page.waitForResponse(response => response.url().endsWith('/api/board/nodes')
        && response.request().method() === 'POST')
      release()
      await unwrap(await firstWrite)
      await page.waitForFunction(() => {
        const button = document.querySelector('[data-testid="apply-all-device-recommendations"]')
        return !button || button.getAttribute('aria-busy') === 'false'
      })
      const refreshed = page.waitForResponse(response => response.url().endsWith('/api/board/snapshot'))
      await page.evaluate(() => {
        delete (document as any).visibilityState
        document.dispatchEvent(new Event('visibilitychange'))
      })
      await unwrap(await refreshed)
    } else {
      const refreshed = page.waitForResponse(response => response.url().endsWith('/api/board/snapshot'))
      release()
      await unwrap(await refreshed)
    }
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

const prepare = async (page: Page, request: APIRequestContext, locale = 'en', theme = 'light') => {
  const auth = await createAuthenticatedUser(request)
  const manifest = JSON.parse(await readFile(path.resolve(process.cwd(), '..',
    'backend/src/main/resources/deviceTemplate/Light.json'), 'utf8'))
  const preview = await unwrap(await request.get(`${apiBaseURL}/api/board/replacement-preview`, { headers: headers(auth) }))
  await unwrap(await request.post(`${apiBaseURL}/api/board/scene`, { headers: headers(auth), data: {
    impactToken: preview.impactToken,
    scene: { schema: 'iot-verify.board-scene', version: 6,
      templates: [{ name: manifest.Name, manifest }],
      devices: ['source', 'target'].map((id, index) => ({ id, templateName: 'Light', label: id,
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
const recommend = async (page: Page, kind: RecommendationKind, count: number) => {
  const resource = kind === 'device' ? 'devices' : collection[kind]
  await page.route(`**/api/board/${resource}/recommend**`, route => route.fulfill({ json: {
    code: 200, message: 'Success', data: {
      message: 'Validated recommendation candidates.', count, requestedCount: 5, validatedCount: count,
      filteredCount: 0, filteredItems: [], adjustedCount: 0, adjustedItems: [],
      rawCandidateCount: count, inspectedCount: count, truncatedCount: 0,
      recommendations: candidatesFor(kind, count)
    }
  } }))
  if (kind === 'rule') await page.route('**/api/board/rules/check-similarity', route => route.fulfill({ json: {
    code: 200, message: 'Success', data: { isSimilar: false, isDuplicate: false, requiresReview: false,
      matchedRule: null, similarity: 0.1, reasonCode: 'AI_NO_SIGNIFICANT_SIMILARITY',
      reason: 'The automation has a distinct action.', message: 'No significant similarity was found.' }
  } }))
  await openRecommendation(page, kind)
  await page.getByTestId(`generate-${kind}-recommendations`).click()
  await expect(page.getByTestId(`apply-all-${kind}-recommendations`)).toBeEnabled()
}

test('cancelling another recommendation kind preserves the earlier batch receipt and disabled action', async ({ page, request }) => {
  await prepare(page, request)
  await recommend(page, 'device', 2)
  await page.getByTestId('apply-all-device-recommendations').click()
  await expect(page.locator('#device-batch-apply-status')).toContainText('All 2')
  await recommend(page, 'rule', 2)
  await page.route('**/api/board/rules/check-similarity', route => route.fulfill({
    status: 503, json: { code: 503, message: 'Similarity service unavailable' }
  }))
  await page.getByTestId('apply-all-rule-recommendations').click()
  await page.getByRole('dialog').getByRole('button', { name: 'Cancel', exact: true }).click()
  await expect(page.locator('#rule-batch-apply-status')).toContainText('0/2 confirmed applied')
  await openRecommendation(page, 'device')
  await expect(page.getByTestId('apply-all-device-recommendations')).toBeDisabled()
  await expect(page.locator('#device-batch-apply-status')).toContainText('All 2')
})

for (const kind of ['device'] as const) {
  for (const profile of [
    { name: 'desktop', locale: 'en', theme: 'light', width: 1440, height: 900 }
  ]) {
    test(`${kind}: apply all, undo and regenerate (${profile.name})`, async ({ page, request }, testInfo) => {
      test.setTimeout(120_000)
      await page.setViewportSize(profile)
      const auth = await prepare(page, request, profile.locale, profile.theme)
      const before = await readSnapshot(request, auth)
      await recommend(page, kind, 2)
      const bulk = page.getByTestId(`apply-all-${kind}-recommendations`)
      await bulk.scrollIntoViewIfNeeded()
      await page.screenshot({ path: testInfo.outputPath('ready.png') })
      await bulk.click()
      const status = page.locator(`#${kind}-batch-apply-status`)
      await expect(status).toContainText(profile.locale === 'en' ? 'All 2' : '全部 2')
      await expect(status).toBeVisible()
      await expect(bulk).toBeDisabled()
      const after = await readSnapshot(request, auth)
      expect(after[snapshotCollection[kind]]).toHaveLength(before[snapshotCollection[kind]].length + 2)
      for (const untouched of ['nodes', 'rules', 'specifications', 'environmentVariables']) {
        if (untouched !== snapshotCollection[kind]) expect(after[untouched]).toEqual(before[untouched])
      }
      if (kind === 'device') {
        const added = after.nodes.filter((node: any) => !before.nodes.some((old: any) => old.id === node.id))
        expect(Math.abs(added[1].position.x - added[0].position.x)).toBeGreaterThanOrEqual(176)
      }
      await bulk.scrollIntoViewIfNeeded()
      await page.screenshot({ path: testInfo.outputPath('applied.png') })
      // Closing a narrow overlay makes the canvas toolbar reachable without changing the scene.
      if (profile.name === 'narrow') await page.getByTestId('control-center-toggle').click()
      for (let index = 0; index < 2; index++) {
        await expect(page.getByTestId('board-undo')).toBeEnabled()
        const undone = page.waitForResponse(response => response.url().endsWith('/api/board/edits/undo'))
        await clickUnderTooltip(page, page.getByTestId('board-undo'))
        await unwrap(await undone)
        await expect.poll(async () => (await readSnapshot(request, auth))[snapshotCollection[kind]].length)
          .toBe(before[snapshotCollection[kind]].length + 1 - index)
      }
      const restored = await readSnapshot(request, auth)
      for (const field of ['nodes', 'rules', 'specifications', 'environmentVariables']) expect(restored[field]).toEqual(before[field])
      await openRecommendation(page, kind)
      await page.getByTestId(`generate-${kind}-recommendations`).click()
      await expect(bulk).toBeEnabled()
    })
  }

  test(`${kind}: stops after the second write fails and permits fresh recommendations`, async ({ page, request }, testInfo) => {
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
