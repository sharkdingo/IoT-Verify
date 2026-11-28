import { expandCreation } from './support/controlCenter'
import path from 'node:path'
import { type APIRequestContext, type Page } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, seedBrowserSession, expect, test, type AuthUser } from './support/auth'
import { clickUnderTooltip } from './support/tooltips'

/**
 * Board edit undo/redo against a real backend.
 *
 * Undo here means "reverse a persisted board edit". These tests also pin the boundaries that keep
 * it from being confused with the other reversal-shaped affordances on this screen: browser
 * Back/Forward moves between run surfaces, and a text field keeps its own undo stack.
 */

test.describe.configure({ mode: 'serial' })
test.setTimeout(180_000)

const seedSession = async (page: Page, auth: AuthUser, theme: 'light' | 'dark' = 'light') => {
  await seedBrowserSession(page, auth, { locale: 'en', theme })
}

/** Imports a scene so there are real, device-referencing rules to delete and restore. */
const openBoardWithScene = async (
  page: Page,
  request: APIRequestContext,
  auth: AuthUser,
  theme: 'light' | 'dark' = 'light'
) => {
  await seedSession(page, auth, theme)
  await page.goto('/#/board')
  await expect(page.locator('.iot-board')).toBeVisible({ timeout: 60_000 })

  const scenePath = path.resolve(
    process.cwd(), '..', 'docs', 'examples', 'default-climate-conflict-scene.json'
  )
  await page.getByTestId('scene-import-file').setInputFiles(scenePath)
  await page.getByRole('dialog', { name: 'Replace the current scene?' })
    .getByRole('button', { name: 'Replace scene' })
    .click()
  await expect.poll(async () => (await ruleIds(request, auth)).length,
    { timeout: 60_000 }).toBeGreaterThan(0)
}

const ruleIds = async (request: APIRequestContext, auth: AuthUser): Promise<number[]> => {
  const response = await request.get(`${apiBaseURL}/api/board/rules`, {
    headers: { Authorization: `Bearer ${auth.token}` }
  })
  return ((await response.json())?.data ?? []).map((rule: any) => Number(rule.id))
}

type RawBoardSnapshot = {
  nodes: Array<{ id: string; label: string }>
  environmentVariables: Array<{ name: string; value: string; trust: string; privacy: string }>
  rules: Array<{ id: number }>
  specifications: Array<{ id: string }>
  editHistory: { canUndo: boolean; canRedo: boolean; editHistoryRevision: string }
}

const boardSnapshot = async (
  request: APIRequestContext,
  auth: AuthUser
): Promise<RawBoardSnapshot> => {
  const response = await request.get(`${apiBaseURL}/api/board/snapshot`, {
    headers: { Authorization: `Bearer ${auth.token}` }
  })
  expect(response.ok()).toBe(true)
  return (await response.json()).data
}

const snapshotIdentity = (snapshot: RawBoardSnapshot) => ({
  deviceIds: snapshot.nodes.map(node => node.id).sort(),
  environmentVariables: snapshot.environmentVariables
    .map(variable => ({ ...variable }))
    .sort((left, right) => left.name.localeCompare(right.name)),
  ruleIds: snapshot.rules.map(rule => Number(rule.id)),
  specificationIds: snapshot.specifications.map(specification => specification.id)
})

const undoButton = (page: Page) => page.getByTestId('board-undo')
const redoButton = (page: Page) => page.getByTestId('board-redo')

/**
 * Reorders the rules so execution order no longer matches ascending id.
 *
 * Without this the position assertion cannot fail: the ordering query breaks ties on id, so a
 * restore that appended instead of honouring `entity_order` would still produce the original
 * sequence, and the test would stay green with the feature reverted.
 */
const reverseRuleOrder = async (request: APIRequestContext, auth: AuthUser) => {
  const current = await ruleIds(request, auth)
  const response = await request.put(`${apiBaseURL}/api/board/rules/order`, {
    headers: { Authorization: `Bearer ${auth.token}` },
    data: { expectedRuleIds: current, ruleIds: [...current].reverse() }
  })
  expect(response.ok()).toBe(true)
  return [...current].reverse()
}

/** Deletes the first rule through the inspector, confirming the destructive dialog. */
const deleteFirstRule = async (page: Page) => {
  await page.getByTestId('inspector-tab-rules').click()
  await page.getByRole('button', { name: 'Delete Rule' }).first().click()
  await page.locator('.el-message-box').getByRole('button', { name: /Delete|删除/ }).click()
}

test.describe('board edit undo and redo', () => {
  test('renames from the keyboard without a duplicate toast and undoes at 375px in dark', async ({ page, request }) => {
    const viewport = { width: 375, height: 812 }
    const theme = 'dark'
    const auth = await createAuthenticatedUser(request)
    await page.setViewportSize(viewport)
    await openBoardWithScene(page, request, auth, theme)
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)

    const originalLabel = 'Living-room Air Conditioner'
    const renamedLabel = 'Renamed living-room air conditioner'
    const inspector = page.getByTestId('system-inspector')
    if (await inspector.getByRole('button', { name: 'Expand System Inspector', exact: true }).isVisible()) {
      await inspector.getByRole('button', { name: 'Expand System Inspector', exact: true }).click()
    }
    await page.getByTestId('inspector-tab-devices').click()
    await inspector.locator('[data-device-id="ac_1"]')
      .getByRole('button', { name: originalLabel, exact: true }).click()
    await inspector.getByRole('button', { name: 'Collapse System Inspector', exact: true }).click()

    const node = page.locator('[data-node-id="ac_1"]')
    await node.click()
    await page.getByTestId('device-dialog').getByTestId('device-rename').click()
    const renameDialog = page.getByRole('dialog', { name: 'Rename device' })
    await renameDialog.getByPlaceholder('Enter device name').fill(renamedLabel)
    await page.keyboard.press('Enter')

    await expect(renameDialog).toHaveCount(0)
    // An auto-retrying absence assertion would wait for the redundant toast to expire and pass.
    expect(await page.locator('.el-message').filter({ hasText: 'Renamed successfully' }).count()).toBe(0)
    await expect(node).toContainText(renamedLabel)
    await expect.poll(async () => (await boardSnapshot(request, auth)).nodes
      .find(candidate => candidate.id === 'ac_1')?.label).toBe(renamedLabel)
    await expect.poll(() => page.evaluate(() => {
      const focused = document.activeElement as HTMLElement | null
      if (!focused || focused === document.body) return false
      const box = focused.getBoundingClientRect()
      const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
      return box.width > 0 && box.height > 0
        && box.left >= 0 && box.top >= 0
        && box.right <= window.innerWidth && box.bottom <= window.innerHeight
        && !!hit && focused.contains(hit)
    })).toBe(true)

    await expect(undoButton(page)).toBeEnabled()
    await undoButton(page).press('Enter')
    await expect(node).toContainText(originalLabel)
    await expect.poll(async () => (await boardSnapshot(request, auth)).nodes
      .find(candidate => candidate.id === 'ac_1')?.label).toBe(originalLabel)
    await expect(redoButton(page)).toBeFocused()
    await redoButton(page).press('Enter')
    await expect(node).toContainText(renamedLabel)
    await expect(undoButton(page)).toBeFocused()
  })

  for (const kind of ['rename', 'runtime'] as const) {
    test(`${kind} recovers a persisted edit after response loss and undoes the recovered revision`, async ({ page, request }) => {
      await page.setViewportSize({ width: 1440, height: 900 })
      const auth = await createAuthenticatedUser(request)
      await openBoardWithScene(page, request, auth)
      const before = await boardSnapshot(request, auth)
      const node = page.locator('[data-node-id="ac_1"]')
      const dialog = page.getByTestId('device-dialog')
      const newLabel = 'Recovered air conditioner'
      const target = `/api/board/nodes/${kind === 'rename' ? 'label' : 'runtime'}`
      const method = kind === 'rename' ? 'PATCH' : 'PUT'
      let writes = 0
      let snapshots = 0
      await page.route(url => url.pathname === target, async route => {
        if (route.request().method() !== method) return route.continue()
        writes += 1
        const committed = await route.fetch()
        expect(committed.ok(), await committed.text()).toBe(true)
        await route.abort()
      })
      page.on('request', request => {
        if (writes > 0 && request.method() === 'GET' && request.url().endsWith('/api/board/snapshot')) snapshots += 1
      })

      await node.click()
      const recovered = page.waitForResponse(response => response.url().endsWith('/api/board/snapshot'))
      if (kind === 'rename') {
        await dialog.getByTestId('device-rename').click()
        const rename = page.getByRole('dialog', { name: 'Rename device' })
        const input = rename.getByTestId('rename-device-name')
        const confirm = rename.getByRole('button', { name: 'Confirm', exact: true })
        await input.fill(before.nodes.find(candidate => candidate.id !== 'ac_1')!.label)
        await expect(rename.getByTestId('rename-device-name-hint')).toContainText('Device name already exists')
        await input.press('Enter')
        expect(writes).toBe(0)
        await input.fill(newLabel)
        await input.press('Enter')
        await expect(rename.getByTestId('rename-device-name-hint')).toContainText('The rename is unconfirmed')
        await expect(input).toHaveValue(newLabel)
        await expect(confirm).toBeDisabled()
        await input.press('Enter')
        await expect(node).toContainText(newLabel)
        await rename.getByRole('button', { name: 'Cancel', exact: true }).click()
      } else {
        const state = dialog.getByTestId('device-runtime-state')
        const save = dialog.getByTestId('device-runtime-save')
        await state.selectOption('cool')
        await clickUnderTooltip(page, save)
        await expect(dialog.getByTestId('device-runtime-save-unconfirmed')).toContainText('The last save is unconfirmed')
        await expect(page.getByText('Instance configuration saved', { exact: true })).toHaveCount(0)
        await expect(state).toHaveValue('cool')
        await expect(save).toBeDisabled()
        await dialog.getByTestId('device-dialog-close').click()
      }
      const refreshResponse = await recovered
      const refreshed = (await refreshResponse.json()).data as RawBoardSnapshot
      const updatedDevice = refreshed.nodes.find(device => device.id === 'ac_1')
      expect(updatedDevice).toMatchObject(kind === 'rename' ? { label: newLabel } : { state: 'cool' })
      expect(writes).toBe(1)
      expect(snapshots).toBe(1)

      const undone = page.waitForResponse(response => response.url().endsWith('/api/board/edits/undo'))
      await clickUnderTooltip(page, undoButton(page))
      const response = await undone
      expect(response.request().postDataJSON()).toEqual({ expectedEditHistoryRevision: refreshed.editHistory.editHistoryRevision })
      expect((await response.json()).data).toMatchObject({ applied: true, canUndo: false, canRedo: true })
      const after = await boardSnapshot(request, auth)
      expect(after.nodes.find(device => device.id === 'ac_1')).toEqual(before.nodes.find(device => device.id === 'ac_1'))
      expect(writes).toBe(1)
    })
  }

  for (const kind of ['rule', 'spec'] as const) {
    test(`${kind} edit recovers a lost save, retains the draft, and checks the original baseline`, async ({ page, request }) => {
      await page.setViewportSize({ width: 1440, height: 1000 })
      const auth = await createAuthenticatedUser(request)
      await openBoardWithScene(page, request, auth)
      const before = await boardSnapshot(request, auth)
      const collection = kind === 'rule' ? 'rules' : 'specifications'
      const tab = kind === 'rule' ? 'rules' : 'specs'
      const itemId = before[collection][0].id
      const target = `/api/board/${tab}/${encodeURIComponent(itemId)}`
      const updatedName = `Recovered ${kind} edit`
      const currentItem = page.getByTestId('system-inspector').locator(`[data-${kind}-id="${itemId}"]`)
      let writes = 0
      let reads = 0
      await page.route(url => url.pathname === target, async route => {
        if (route.request().method() !== 'PUT') return route.continue()
        writes += 1
        if (writes !== 1) return route.continue()
        const committed = await route.fetch()
        expect(committed.ok(), await committed.text()).toBe(true)
        await route.abort()
      })
      page.on('request', outgoing => {
        if (writes > 0 && outgoing.method() === 'GET' && outgoing.url().endsWith('/api/board/snapshot')) reads += 1
      })

      await page.getByTestId(`inspector-tab-${tab}`).click()
      await page.getByTestId(`edit-${kind}-${itemId}`).click()
      const name = kind === 'rule' ? page.locator('#rule-builder-name') : page.getByTestId('spec-name')
      const save = page.getByTestId(kind === 'rule' ? 'rule-save' : 'spec-create')
      await name.fill(updatedName)
      const refreshed = page.waitForResponse(response => response.url().endsWith('/api/board/snapshot'))
      await clickUnderTooltip(page, save)
      const recovered = (await (await refreshed).json()).data as RawBoardSnapshot
      await expect(page.getByTestId(`${kind}-save-outcome-unknown`)).toContainText('The save is unconfirmed')
      await expect(page.getByTestId(`${kind}-save-failure`)).toHaveCount(0)
      await expect(currentItem).toContainText(updatedName)
      await expect(name).toHaveValue(updatedName)
      await expect(save).toBeDisabled()
      expect(writes).toBe(1)
      expect(reads).toBe(1)
      expect(recovered[collection][0]).toMatchObject(kind === 'rule' ? { ruleString: updatedName } : { name: updatedName })

      const undo = page.waitForResponse(response => response.url().endsWith('/api/board/edits/undo'))
      await clickUnderTooltip(page, undoButton(page))
      const undoResponse = await undo
      expect(undoResponse.request().postDataJSON()).toEqual({ expectedEditHistoryRevision: recovered.editHistory.editHistoryRevision })
      expect((await undoResponse.json()).data).toMatchObject({ applied: true })
      expect((await boardSnapshot(request, auth))[collection]).toEqual(before[collection])

      const redo = page.waitForResponse(response => response.url().endsWith('/api/board/edits/redo'))
      await clickUnderTooltip(page, redoButton(page))
      expect((await (await redo).json()).data).toMatchObject({ applied: true })
      await expect(currentItem).toContainText(updatedName)
      const newerDraft = `Another ${kind} draft`
      await name.fill(newerDraft)
      const conflict = page.waitForResponse(response => response.request().method() === 'PUT'
        && new URL(response.url()).pathname === target)
      await clickUnderTooltip(page, save)
      const rejected = await conflict
      expect(rejected.status()).toBe(409)
      expect((await rejected.json()).data.reasonCode).toBe(kind === 'rule' ? 'RULE_EDIT_STALE' : 'SPEC_EDIT_STALE')
      await expect(page.getByTestId(`${kind}-save-failure`)).toContainText('Your draft is preserved')
      await expect(name).toHaveValue(newerDraft)
      await expect(currentItem).toContainText(updatedName)
      expect(writes).toBe(2)
    })
  }

  test('keeps a new requirement draft after a committed create loses its receipt', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await page.setViewportSize({ width: 1440, height: 1000 })
    await openBoardWithScene(page, request, auth)
    const before = await boardSnapshot(request, auth)
    await page.getByTestId('inspector-tab-specs').click()
    await page.getByTestId('control-tab-specs').click()
    await expandCreation(page, 'specs')
    const name = page.getByTestId('spec-name')
    const save = page.getByTestId('spec-create')
    const requirement = 'Temperature reading must stay below 31'
    await name.fill(requirement)
    await page.getByTestId('spec-template-select').selectOption('3')
    await page.getByTestId('spec-add-condition-a').click()
    await page.getByTestId('spec-condition-device').selectOption('temperature_1')
    await page.getByTestId('spec-condition-type').selectOption('variable')
    await page.getByTestId('spec-condition-key').selectOption('temperature')
    await page.getByTestId('spec-condition-variable-source-reported').click()
    await page.getByTestId('spec-condition-relation').selectOption('>=')
    await page.getByTestId('spec-condition-value').fill('31')
    await page.getByTestId('spec-condition-save').click()

    let writes = 0
    let reads = 0
    await page.route('**/api/board/specs', async route => {
      if (route.request().method() !== 'POST') return route.continue()
      writes += 1
      const committed = await route.fetch()
      expect(committed.ok(), await committed.text()).toBe(true)
      await route.abort()
    })
    page.on('request', outgoing => {
      if (writes && outgoing.method() === 'GET' && outgoing.url().endsWith('/api/board/snapshot')) reads += 1
    })
    const refreshed = page.waitForResponse(response => response.url().endsWith('/api/board/snapshot'))
    await clickUnderTooltip(page, save)
    const recovered = (await (await refreshed).json()).data as RawBoardSnapshot
    expect(recovered.specifications).toHaveLength(before.specifications.length + 1)
    const created = recovered.specifications.at(-1)!
    const item = page.getByTestId('system-inspector').locator(`[data-spec-id="${created.id}"]`)
    await expect(item).toContainText(requirement)
    await expect(page.getByTestId('spec-save-outcome-unknown')).toContainText('The save is unconfirmed.')
    await expect(page.getByTestId('spec-save-failure')).toHaveCount(0)
    await expect(name).toHaveValue(requirement)
    await expect(page.getByTestId('spec-description')).toContainText('31')
    await expect(save).toBeDisabled()
    expect(writes).toBe(1)
    expect(reads).toBe(1)

    const undo = page.waitForResponse(response => response.url().endsWith('/api/board/edits/undo'))
    await clickUnderTooltip(page, undoButton(page))
    const undone = await undo
    expect(undone.request().postDataJSON()).toEqual({ expectedEditHistoryRevision: recovered.editHistory.editHistoryRevision })
    expect((await undone.json()).data).toMatchObject({ applied: true })
    await expect(item).toHaveCount(0)
    await expect(name).toHaveValue(requirement)
    expect((await boardSnapshot(request, auth)).specifications).toEqual(before.specifications)
  })

  test('keeps an Environment Pool draft and reports a stale save after a cross-tab edit', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)
    const original = (await boardSnapshot(request, auth)).environmentVariables.find(variable => variable.name === 'temperature')!
    const other = await page.context().newPage()
    try {
      await seedSession(other, auth)
      await other.goto('/#/board')
      const row = (tab: Page) => tab.getByTestId('environment-pool').locator('[data-environment-name="temperature"]')
      for (const tab of [page, other]) {
        await tab.getByTestId('inspector-tab-environment').click()
        await row(tab).getByRole('button').first().click()
      }
      const draft = row(page).getByTestId('environment-value-temperature')
      await expect(draft).toHaveValue(original.value)
      await draft.evaluate((input: HTMLInputElement) => {
        input.value = '31'
        input.dispatchEvent(new Event('input', { bubbles: true }))
      })

      const update = row(other).getByTestId('environment-value-temperature')
      const summary = row(page).getByRole('button').first()
      await update.fill('32')
      await update.press('Tab')
      await expect(summary).toContainText('32')
      await expect(draft).toHaveValue('31')

      const save = page.waitForResponse(response => response.url().endsWith('/api/board/environment')
        && response.request().method() === 'POST')
      await draft.dispatchEvent('change')
      expect((await save).status()).toBe(409)
      await expect(row(page).getByTestId('environment-value-failure-temperature')).toContainText('changed in another window')
      await expect(summary).toContainText('32')
      await expect(draft).toHaveValue('31')
      expect((await boardSnapshot(request, auth)).environmentVariables).toContainEqual({ ...original, value: '32' })
    } finally { await other.close() }
  })

  test('recovers an unconfirmed Environment Pool edit and undoes it without discarding the draft', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)
    const before = await boardSnapshot(request, auth)
    const temperature = before.environmentVariables.find(variable => variable.name === 'temperature')!
    const nextValue = '31'
    expect(temperature.value).not.toBe(nextValue)
    const row = page.getByTestId('environment-pool').locator('[data-environment-name="temperature"]')
    const summary = row.getByRole('button').first()
    await page.getByTestId('inspector-tab-environment').click()
    await summary.click()
    const draft = row.getByTestId('environment-value-temperature')
    await expect(draft).toHaveValue(temperature.value)

    let writes = 0
    let reads = 0
    await page.route('**/api/board/environment', async route => {
      if (route.request().method() !== 'POST') return route.continue()
      writes += 1
      if (writes > 1) return route.continue()
      const committed = await route.fetch()
      expect(committed.ok(), await committed.text()).toBe(true)
      await route.abort()
    })
    page.on('request', outgoing => {
      if (writes > 0 && outgoing.method() === 'GET' && outgoing.url().endsWith('/api/board/snapshot')) reads += 1
    })

    const refreshed = page.waitForResponse(response => response.url().endsWith('/api/board/snapshot'))
    await draft.fill(nextValue)
    await draft.press('Tab')
    const recovered = (await (await refreshed).json()).data as RawBoardSnapshot
    expect(recovered.environmentVariables).toContainEqual({ ...temperature, value: nextValue })
    await expect(page.getByTestId('environment-save-unconfirmed')).toContainText('The Environment Pool save is unconfirmed.')
    await expect(summary).toContainText(nextValue)
    await expect(draft).toHaveValue(nextValue)
    await expect(draft).toBeEnabled()
    await draft.dispatchEvent('change')
    await expect(page.getByText('No Environment Pool value changed:', { exact: false })).toHaveCount(0)
    expect(writes).toBe(1)
    expect(reads).toBe(1)

    const undo = page.waitForResponse(response => response.url().endsWith('/api/board/edits/undo'))
    await clickUnderTooltip(page, undoButton(page))
    const undone = await undo
    expect(undone.request().postDataJSON()).toEqual({ expectedEditHistoryRevision: recovered.editHistory.editHistoryRevision })
    expect((await undone.json()).data).toMatchObject({ applied: true })
    await expect(summary).toContainText(temperature.value)
    await expect(draft).toHaveValue(nextValue)
    expect((await boardSnapshot(request, auth)).environmentVariables).toEqual(before.environmentVariables)
    expect(writes).toBe(1)

    await draft.dispatchEvent('change')
    await expect(summary).toContainText(nextValue)
    await expect(page.getByTestId('environment-save-unconfirmed')).toHaveCount(0)
    expect(writes).toBe(2)
    expect(reads).toBe(1)
    expect((await boardSnapshot(request, auth)).environmentVariables).toContainEqual({ ...temperature, value: nextValue })
  })

  test('restores a deleted rule, redoes it, and survives a refresh', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)

    // Nothing has been edited yet, so the server reports no reversible history.
    await expect(undoButton(page)).toBeDisabled()
    await expect(redoButton(page)).toBeDisabled()

    const before = await ruleIds(request, auth)
    await deleteFirstRule(page)
    await expect.poll(async () => (await ruleIds(request, auth)).length)
      .toBe(before.length - 1)
    await expect(undoButton(page)).toBeEnabled()

    const edgesAfterDelete = await page.locator('.edge-hitarea').count()

    await undoButton(page).click()
    // The restored rule keeps its original id, so references to it stay valid.
    await expect.poll(async () => (await ruleIds(request, auth)).sort()).toEqual([...before].sort())
    await expect(redoButton(page)).toBeEnabled()

    // Canvas connection lines are derived from rules, so a restored rule must get its line back
    // without waiting for an unrelated refresh.
    await expect.poll(async () => page.locator('.edge-hitarea').count(), { timeout: 15_000 })
      .toBeGreaterThan(edgesAfterDelete)

    await redoButton(page).click()
    await expect.poll(async () => (await ruleIds(request, auth)).length)
      .toBe(before.length - 1)

    // Availability is server state, so a reload restores the same affordance.
    await page.reload()
    await expect(page.locator('.iot-board')).toBeVisible({ timeout: 60_000 })
    await expect(undoButton(page)).toBeEnabled()
    await undoButton(page).click()
    await expect.poll(async () => (await ruleIds(request, auth)).length).toBe(before.length)
  })

  for (const kind of ['rule', 'spec'] as const) {
    test(`recovers an unconfirmed ${kind} deletion and undoes it at its original position`, async ({ page, request }) => {
      await page.setViewportSize({ width: 1440, height: 1000 })
      const auth = await createAuthenticatedUser(request)
      await openBoardWithScene(page, request, auth)
      if (kind === 'rule') {
        await reverseRuleOrder(request, auth)
        await page.reload()
      }
      const before = await boardSnapshot(request, auth)
      const collection = kind === 'rule' ? 'rules' : 'specifications'
      const tab = kind === 'rule' ? 'rules' : 'specs'
      const itemLabel = kind === 'rule' ? 'rule' : 'specification'
      const itemIds = before[collection].map(item => String(item.id))
      expect(itemIds.length).toBeGreaterThan(1)
      const [itemId, ...remainingIds] = itemIds
      const target = `/api/board/${tab}/${encodeURIComponent(itemId)}`
      const attribute = `data-${kind}-id`
      const rows = page.getByTestId('system-inspector').locator(`[${attribute}]`)
      const row = page.getByTestId('system-inspector').locator(`[${attribute}="${itemId}"]`)
      const visibleIds = () => rows.evaluateAll((items, name) => items.map(item => item.getAttribute(name)), attribute)
      let writes = 0
      let reads = 0
      await page.route(url => url.pathname === target, async route => {
        if (route.request().method() !== 'DELETE') return route.continue()
        writes += 1
        const committed = await route.fetch()
        expect(committed.ok(), await committed.text()).toBe(true)
        await route.abort()
      })
      page.on('request', outgoing => {
        if (writes > 0 && outgoing.method() === 'GET' && outgoing.url().endsWith('/api/board/snapshot')) reads += 1
      })

      await page.getByTestId(`inspector-tab-${tab}`).click()
      await expect.poll(visibleIds).toEqual(itemIds)
      await clickUnderTooltip(page, row.getByRole('button', { name: new RegExp(`^Delete ${itemLabel}$`, 'i') }))
      const refreshed = page.waitForResponse(response => response.url().endsWith('/api/board/snapshot'))
      await page.locator('.el-message-box').getByRole('button', { name: 'Delete', exact: true }).click()
      const recovered = (await (await refreshed).json()).data as RawBoardSnapshot
      expect(recovered[collection]).toEqual(before[collection].slice(1))
      await expect(page.getByText(`The deletion is unconfirmed. The ${itemLabel} is no longer on the refreshed board.`)).toBeVisible()
      await expect(page.getByText(`${kind === 'rule' ? 'Rule' : 'Specification'} deleted`, { exact: true })).toHaveCount(0)
      await expect.poll(visibleIds).toEqual(remainingIds)
      expect(writes).toBe(1)
      expect(reads).toBe(1)

      const undo = page.waitForResponse(response => response.url().endsWith('/api/board/edits/undo'))
      await clickUnderTooltip(page, undoButton(page))
      const undone = await undo
      expect(undone.request().postDataJSON()).toEqual({ expectedEditHistoryRevision: recovered.editHistory.editHistoryRevision })
      expect((await undone.json()).data).toMatchObject({ applied: true })
      expect((await boardSnapshot(request, auth))[collection]).toEqual(before[collection])
      await expect.poll(visibleIds).toEqual(itemIds)
      expect(writes).toBe(1)
    })
  }

  test('rejects a stale rule deletion and blocks resubmission after an unconfirmed deletion', async ({ page, request }) => {
    await page.setViewportSize({ width: 1440, height: 1000 })
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)
    const original = (await boardSnapshot(request, auth)).rules[0]
    const target = `/api/board/rules/${original.id}`
    const row = page.getByTestId('system-inspector').locator(`[data-rule-id="${original.id}"]`)
    const deleteButton = row.getByRole('button', { name: /^Delete rule$/i })
    const confirm = page.locator('.el-message-box').getByRole('button', { name: 'Delete', exact: true })
    let writes = 0
    await page.route(url => url.pathname === target, async route => {
      if (route.request().method() !== 'DELETE') return route.continue()
      writes += 1
      if (writes === 1) return route.continue()
      await route.abort()
    })

    await page.getByTestId('inspector-tab-rules').click()
    await clickUnderTooltip(page, deleteButton)
    const changedName = 'Edited from another tab'
    const updated = await request.put(`${apiBaseURL}${target}`, {
      headers: { Authorization: `Bearer ${auth.token}` },
      data: { expected: original, desired: { ...original, ruleString: changedName } }
    })
    expect(updated.ok(), await updated.text()).toBe(true)
    const conflict = page.waitForResponse(response => response.request().method() === 'DELETE'
      && new URL(response.url()).pathname === target)
    await confirm.click()
    expect((await conflict).status()).toBe(409)
    await expect(page.getByText('The item changed while you were confirming.', { exact: false })).toBeVisible()
    await expect(row).toContainText(changedName)
    await expect(deleteButton).toBeEnabled()

    await clickUnderTooltip(page, deleteButton)
    await confirm.click()
    await expect(row.getByTestId('rule-delete-unconfirmed')).toContainText('The item is still on the refreshed board.')
    await expect(deleteButton).toBeDisabled()
    await page.getByTestId('inspector-tab-specs').click()
    await page.getByTestId('inspector-tab-rules').click()
    await expect(deleteButton).toBeDisabled()
    await expect(page.getByText('Rule deleted', { exact: true })).toHaveCount(0)
    expect(writes).toBe(2)
  })

  test('recovers a lost undo and redo receipt with the matching history revision', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)
    const original = await boardSnapshot(request, auth)
    await deleteFirstRule(page)
    await expect(undoButton(page)).toBeEnabled()
    const deleted = await boardSnapshot(request, auth)

    const writes: Array<{ direction: string; expectedEditHistoryRevision: string }> = []
    let reads = 0
    await page.route(/\/api\/board\/edits\/(undo|redo)$/, async route => {
      writes.push({ direction: new URL(route.request().url()).pathname.split('/').at(-1)!, ...route.request().postDataJSON() })
      const committed = await route.fetch()
      expect(committed.ok(), await committed.text()).toBe(true)
      await route.abort()
    })
    page.on('request', outgoing => {
      if (writes.length && outgoing.method() === 'GET' && outgoing.url().endsWith('/api/board/snapshot')) reads += 1
    })
    const undoRecovery = page.waitForResponse(response => response.url().endsWith('/api/board/snapshot'))
    await clickUnderTooltip(page, undoButton(page))
    const restored = (await (await undoRecovery).json()).data as RawBoardSnapshot
    await expect(page.getByText('The undo or redo response could not be confirmed.', { exact: false })).toBeVisible()
    await expect(page.getByText('Restored the automation rule.', { exact: true })).toHaveCount(0)
    expect(restored.rules).toEqual(original.rules)
    await expect(undoButton(page)).toBeDisabled()
    await expect(redoButton(page)).toBeEnabled()

    const redoRecovery = page.waitForResponse(response => response.url().endsWith('/api/board/snapshot'))
    await clickUnderTooltip(page, redoButton(page))
    const redone = (await (await redoRecovery).json()).data as RawBoardSnapshot
    expect(redone.rules).toEqual(deleted.rules)
    await expect(page.getByText(/^Redone:/)).toHaveCount(0)
    await expect(undoButton(page)).toBeEnabled()
    await expect(redoButton(page)).toBeDisabled()
    expect(writes).toEqual([
      { direction: 'undo', expectedEditHistoryRevision: deleted.editHistory.editHistoryRevision },
      { direction: 'redo', expectedEditHistoryRevision: restored.editHistory.editHistoryRevision }
    ])
    expect(reads).toBe(2)
  })

  test('distinguishes a rejected undo and blocks replay after losing an undo request', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)
    await deleteFirstRule(page)
    await expect(undoButton(page)).toBeEnabled()
    const before = await boardSnapshot(request, auth)
    let writes = 0
    let reads = 0
    await page.route('**/api/board/edits/undo', route => {
      writes += 1
      return writes === 1 ? route.fulfill({ status: 422, json: { code: 422, message: '撤销请求被拒绝' } }) : route.abort()
    })
    page.on('request', outgoing => {
      if (writes && outgoing.method() === 'GET' && outgoing.url().endsWith('/api/board/snapshot')) reads += 1
    })
    await clickUnderTooltip(page, undoButton(page))
    await expect(page.getByText('Undo or redo could not be applied.', { exact: true })).toBeVisible()
    expect(reads).toBe(0)

    const recovery = page.waitForResponse(response => response.url().endsWith('/api/board/snapshot'))
    await clickUnderTooltip(page, undoButton(page))
    const recovered = (await (await recovery).json()).data as RawBoardSnapshot
    expect(recovered.editHistory).toEqual(before.editHistory)
    await expect(page.getByText('The undo or redo response could not be confirmed.', { exact: false })).toBeVisible()
    await clickUnderTooltip(page, undoButton(page))
    await expect(page.getByText('The previous undo or redo is still unconfirmed.', { exact: false })).toBeVisible()
    expect(writes).toBe(2)
    expect(reads).toBe(1)
    expect(await boardSnapshot(request, auth)).toEqual(before)
  })

  test('refreshes a stale undo target before a second deliberate undo', async ({ page, context, request }) => {
    const auth = await createAuthenticatedUser(request, { usernamePrefix: 'undoTab' })
    const writer = await context.newPage()
    await seedSession(writer, auth)
    await writer.goto('/#/board')
    await expect(writer.getByTestId('scene-import')).toBeEnabled()
    const headers = { Authorization: `Bearer ${auth.token}` }
    const addLight = async (id: string, label: string, x: number) => {
      // The request context writes through the real API without the browser's invalidation broadcast.
      const response = await writer.request.post(`${apiBaseURL}/api/board/nodes`, {
        headers, data: {
          devices: [{ id, label, templateName: 'Light', state: 'off', position: { x, y: 200 }, width: 176, height: 128 }],
          environmentVariablePatches: []
        }
      })
      expect(response.ok(), await response.text()).toBe(true)
    }
    await addLight('observed_light', 'Observed light', 150)
    await seedSession(page, auth)
    await page.bringToFront()
    await page.goto('/#/board')
    const observed = page.locator('[data-node-id="observed_light"]')
    const later = page.locator('[data-node-id="later_light"]')
    await expect(observed).toContainText('Observed light')
    await expect(undoButton(page)).toBeEnabled()
    const seen = await boardSnapshot(request, auth)

    await addLight('later_light', 'Later light', 420)
    const beforeUndo = await boardSnapshot(request, auth)
    expect(await later.count()).toBe(0)
    const waitForUndo = () => page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/board/edits/undo')
    let releaseUndo!: () => void
    const heldUndo = new Promise<void>(resolve => { releaseUndo = resolve })
    await page.route('**/api/board/edits/undo', async route => {
      await heldUndo
      await route.continue()
    }, { times: 1 })
    const firstUndo = waitForUndo()
    const accountButton = page.getByTestId('open-account-center')
    try {
      await undoButton(page).press('Enter')
      await expect(undoButton(page)).toBeDisabled()
      await accountButton.focus()
      await expect(accountButton).toBeFocused()
    } finally {
      releaseUndo()
    }
    const rejected = await firstUndo
    expect(rejected.status()).toBe(409)
    expect((await rejected.json()).data.reasonCode).toBe('BOARD_EDIT_HISTORY_CHANGED')
    expect(rejected.request().postDataJSON()).toEqual({ expectedEditHistoryRevision: seen.editHistory.editHistoryRevision })
    await Promise.all([
      expect(later).toContainText('Later light'),
      expect(page.getByText('Another edit changed the undo history. The board has been refreshed. Review it and try again.',
        { exact: true })).toBeVisible()
    ])
    await expect(undoButton(page)).toBeEnabled()
    await expect(accountButton).toBeFocused()
    expect(await boardSnapshot(request, auth)).toEqual(beforeUndo)

    const secondUndo = waitForUndo()
    await undoButton(page).press('Enter')
    const applied = await secondUndo
    expect(applied.ok(), await applied.text()).toBe(true)
    expect(applied.request().postDataJSON()).toEqual({
      expectedEditHistoryRevision: beforeUndo.editHistory.editHistoryRevision
    })
    await expect(later).toHaveCount(0)
    await expect(observed).toContainText('Observed light')
    await expect(redoButton(page)).toBeEnabled()
    await expect(undoButton(page)).toBeEnabled()
    await expect(undoButton(page)).toBeFocused()
    expect((await boardSnapshot(request, auth)).nodes.map(node => node.id)).toEqual(['observed_light'])
  })

  test('recovers a committed edit and its undo revision from one snapshot', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)
    const before = await ruleIds(request, auth)
    await expect(undoButton(page)).toBeDisabled()
    const recoveryReads: string[] = []
    let editCommitted = false
    await page.route(/\/api\/board\/(snapshot|rules|edits\/availability)$/, async route => {
      if (editCommitted && route.request().method() === 'GET') {
        const endpoint = new URL(route.request().url()).pathname
        recoveryReads.push(endpoint)
        if (endpoint.endsWith('/availability')) {
          await route.fulfill({ status: 503, json: { code: 503, message: 'Availability temporarily unavailable' } })
          return
        }
      }
      await route.continue()
    })

    await page.route('**/api/board/rules/*', async route => {
      if (route.request().method() !== 'DELETE') {
        await route.continue()
        return
      }
      const response = await route.fetch()
      expect(response.ok()).toBe(true)
      editCommitted = true
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'success', data: { operation: 'deleted' } })
      })
    })

    await deleteFirstRule(page)

    await expect.poll(async () => (await ruleIds(request, auth)).length)
      .toBe(before.length - 1)
    await expect(undoButton(page)).toBeEnabled()
    expect(recoveryReads).toEqual(['/api/board/snapshot'])
    const confirmed = await boardSnapshot(request, auth)
    const pending = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/board/edits/undo')
    await undoButton(page).click()
    const reversal = await pending
    expect(reversal.ok()).toBe(true)
    expect(reversal.request().postDataJSON()).toEqual({
      expectedEditHistoryRevision: confirmed.editHistory.editHistoryRevision
    })
    await expect.poll(async () => (await ruleIds(request, auth)).length).toBe(before.length)
  })

  for (const responseLost of [false, true]) test(`undoes and redoes a device deletion with its cascades (response lost=${responseLost})`, async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)

    const beforeSnapshot = await boardSnapshot(request, auth)
    const before = snapshotIdentity(beforeSnapshot)
    await expect(page.locator('.el-message')).toHaveCount(0, { timeout: 10_000 })

    // ac_1 owns the humidity domain and participates in every rule/spec in this fixture, so this
    // one action proves the compound snapshot rather than only the device row.
    const node = page.locator('[data-node-id="ac_1"]')
    await expect(node).toBeVisible()
    await node.click()
    const details = page.getByTestId('device-dialog')
    await expect(details).toBeVisible({ timeout: 15_000 })
    await details.getByTestId('device-delete').click()
    const confirmation = page.getByRole('dialog', { name: 'Delete device' })
    await expect(confirmation).toContainText(`Related rules to delete: ${before.ruleIds.length}`)
    await expect(confirmation).toContainText(`Related specifications to delete: ${before.specificationIds.length}`)
    let deleteRequests = 0
    let recoveryReads = 0
    await page.route(url => url.pathname === '/api/board/nodes/delete', async route => {
      deleteRequests += 1
      if (!responseLost) return route.continue()
      const committed = await route.fetch()
      expect(committed.ok(), await committed.text()).toBe(true)
      await route.abort()
    })
    page.on('request', request => {
      if (deleteRequests && request.method() === 'GET' && request.url().endsWith('/api/board/snapshot')) recoveryReads += 1
    })
    const recovery = responseLost ? page.waitForResponse(response => response.url().endsWith('/api/board/snapshot')) : null
    await confirmation.getByRole('button', { name: 'Delete Device', exact: true }).click()
    const recovered = recovery ? (await (await recovery).json()).data as RawBoardSnapshot : null

    const deletedToast = page.locator('.el-message').filter({
      hasText: /Deleted .*Living-room Air Conditioner/
    })
    if (responseLost) {
      await expect(page.getByText('The deletion is unconfirmed. The device is no longer on the refreshed board.')).toBeVisible()
      expect(await deletedToast.count()).toBe(0)
      expect(await page.getByText('This device was deleted elsewhere. Related panels were closed.').count()).toBe(0)
      expect(recoveryReads).toBe(1)
      expect(recovered?.nodes.some(candidate => candidate.id === 'ac_1')).toBe(false)
      await expect(node).toHaveCount(0)
      await expect(confirmation).toHaveCount(0)
    } else {
      await expect(deletedToast).toBeVisible()
      // A deletion notice cannot intercept the Undo button underneath it.
      await expect.poll(() => deletedToast.evaluate(toast => {
        const nav = document.querySelector('.board-nav-bar')!.getBoundingClientRect()
        const text = toast.querySelector('.el-message__content')!.getBoundingClientRect()
        const hit = document.elementFromPoint(text.left + text.width / 2, text.top + text.height / 2)
        return {
          belowNav: toast.getBoundingClientRect().top >= nav.bottom,
          catchesPointer: Boolean(hit?.closest('.el-message'))
        }
      })).toEqual({ belowNav: true, catchesPointer: false })
    }
    await expect(page.locator('.el-message').filter({
      hasText: 'The device change also removed from the Environment Pool:'
    })).toHaveCount(0)
    await expect.poll(async () => (await boardSnapshot(request, auth)).nodes
      .some(candidate => candidate.id === 'ac_1')).toBe(false)
    const afterDeleteSnapshot = recovered ?? await boardSnapshot(request, auth)
    const afterDelete = snapshotIdentity(afterDeleteSnapshot)
    expect(afterDelete.ruleIds.length).toBeLessThan(before.ruleIds.length)
    expect(afterDelete.specificationIds.length).toBeLessThan(before.specificationIds.length)
    expect(afterDelete.environmentVariables.length).toBeLessThan(before.environmentVariables.length)
    await page.getByTestId('inspector-tab-environment').click()
    const humidity = page.getByTestId('environment-pool').locator('[data-environment-name="humidity"]')
    await expect(humidity).toHaveCount(0)
    await expect(undoButton(page)).toBeEnabled()

    const undo = page.waitForResponse(response => response.url().endsWith('/api/board/edits/undo'))
    await undoButton(page).click()
    expect((await undo).request().postDataJSON()).toEqual({ expectedEditHistoryRevision: afterDeleteSnapshot.editHistory.editHistoryRevision })
    await expect.poll(async () => snapshotIdentity(await boardSnapshot(request, auth)), {
      timeout: 30_000
    }).toEqual(before)
    await expect(node).toBeVisible()
    const restored = await boardSnapshot(request, auth)
    expect(restored.nodes.find(device => device.id === 'ac_1')).toEqual(beforeSnapshot.nodes.find(device => device.id === 'ac_1'))
    expect(restored.rules).toEqual(beforeSnapshot.rules)
    expect(restored.specifications).toEqual(beforeSnapshot.specifications)
    await expect(humidity.getByRole('button').first()).toContainText(beforeSnapshot.environmentVariables
      .find(variable => variable.name === 'humidity')!.value)
    await expect(redoButton(page)).toBeEnabled()

    await redoButton(page).click()
    await expect.poll(async () => snapshotIdentity(await boardSnapshot(request, auth)), {
      timeout: 30_000
    }).toEqual(afterDelete)
    expect(deleteRequests).toBe(1)
  })

  test('requires renewed review for a stale deletion and blocks reusing a preview after an unknown write', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)
    const node = page.locator('[data-node-id="ac_1"]')
    const details = page.getByTestId('device-dialog')
    const confirmation = page.getByRole('dialog', { name: 'Delete device' })
    const submit = confirmation.getByRole('button', { name: 'Delete Device', exact: true })
    const target = '/api/board/nodes/delete'
    let deleteRequests = 0
    let previews = 0
    page.on('request', request => {
      if (new URL(request.url()).pathname === '/api/board/nodes/deletion-preview') previews += 1
    })
    await page.route(url => url.pathname === target, async route => {
      deleteRequests += 1
      if (deleteRequests === 1) return route.continue()
      await route.abort()
    })
    await node.click()
    await details.getByTestId('device-delete').click()
    await expect(submit).toBeEnabled()
    expect(previews).toBe(1)

    // The direct API edit changes the server impact token before the open tab can refresh.
    const renamed = 'Updated air conditioner'
    const externalEdit = await request.patch(`${apiBaseURL}/api/board/nodes/label`, {
      headers: { Authorization: `Bearer ${auth.token}` }, params: { nodeId: 'ac_1' },
      data: { label: renamed, expectedLabel: 'Living-room Air Conditioner' }
    })
    expect(externalEdit.ok(), await externalEdit.text()).toBe(true)
    const conflict = page.waitForResponse(response => new URL(response.url()).pathname === target)
    await submit.click()
    expect((await conflict).status()).toBe(409)
    await expect(confirmation).toContainText(renamed)
    await expect(submit).toBeEnabled()
    expect(previews).toBe(2)
    expect(deleteRequests).toBe(1)

    await submit.click()
    const unconfirmed = confirmation.getByTestId('device-delete-unconfirmed')
    await expect(unconfirmed).toContainText('The device is still on the refreshed board')
    await expect(submit).toBeDisabled()
    await confirmation.getByRole('button', { name: 'Cancel', exact: true }).click()
    await details.getByTestId('device-delete').click()
    await expect(unconfirmed).toBeVisible()
    await expect(submit).toBeDisabled()
    expect(deleteRequests).toBe(2)
    expect((await boardSnapshot(request, auth)).nodes.find(device => device.id === 'ac_1')?.label).toBe(renamed)
  })

  test('reverses a rule reorder, which users read as one edit', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)

    await page.getByTestId('inspector-tab-rules').click()
    const before = await ruleIds(request, auth)
    expect(before.length).toBeGreaterThan(1)
    const [firstId, secondId, ...remainingIds] = before
    const swapped = [secondId, firstId, ...remainingIds]
    const row = page.getByTestId('system-inspector').locator(`[data-rule-id="${firstId}"]`)

    await clickUnderTooltip(page, row.getByRole('button', { name: 'Move later' }))
    await clickUnderTooltip(page, row.getByRole('button', { name: 'Move earlier' }))
    await expect.poll(async () => ruleIds(request, auth)).toEqual(before)
    await expect(undoButton(page)).toBeEnabled()

    await clickUnderTooltip(page, undoButton(page))
    await expect.poll(async () => ruleIds(request, auth)).toEqual(swapped)
    await clickUnderTooltip(page, undoButton(page))
    await expect.poll(async () => ruleIds(request, auth)).toEqual(before)

    await expect(redoButton(page)).toBeEnabled()
    await clickUnderTooltip(page, redoButton(page))
    await expect.poll(async () => ruleIds(request, auth)).toEqual(swapped)
  })

  test('recovers an unconfirmed rule reorder and undoes it with the recovered revision', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)
    await page.getByTestId('inspector-tab-rules').click()
    const before = await ruleIds(request, auth)
    expect(before.length).toBeGreaterThan(1)
    const [firstId, secondId, ...remainingIds] = before
    const afterFirstMove = [secondId, firstId, ...remainingIds]
    const inspector = page.getByTestId('system-inspector')
    const movedRule = inspector.locator(`[data-rule-id="${firstId}"]`)
    const later = movedRule.getByRole('button', { name: 'Move later' })
    const earlier = movedRule.getByRole('button', { name: 'Move earlier' })
    const displayedOrder = () => inspector.locator('[data-rule-id]').evaluateAll(rows =>
      rows.map(row => Number(row.getAttribute('data-rule-id'))))
    const writes: { expectedRuleIds: number[]; ruleIds: number[] }[] = []
    let reads = 0
    let releaseResponse!: () => void
    const heldResponse = new Promise<void>(resolve => { releaseResponse = resolve })
    await page.route('**/api/board/rules/order', async route => {
      writes.push(route.request().postDataJSON())
      if (writes.length !== 1) return route.continue()
      const committed = await route.fetch()
      expect(committed.ok(), await committed.text()).toBe(true)
      await heldResponse
      await route.abort()
    })
    page.on('request', outgoing => {
      if (writes.length > 0 && outgoing.method() === 'GET' && outgoing.url().endsWith('/api/board/snapshot')) reads += 1
    })

    const refreshed = page.waitForResponse(response => response.url().endsWith('/api/board/snapshot'))
    try {
      await clickUnderTooltip(page, later)
      await expect(later).toBeDisabled()
      await later.evaluate((button: HTMLButtonElement) => button.click())
      await expect.poll(displayedOrder).toEqual(before)
    } finally {
      releaseResponse()
    }
    const recovered = (await (await refreshed).json()).data as RawBoardSnapshot
    await expect(page.getByText('The rule priority save is unconfirmed.', { exact: false })).toBeVisible()
    await expect(page.getByText('Rule priority updated. Rerun verification.', { exact: true })).toHaveCount(0)
    await expect.poll(displayedOrder).toEqual(afterFirstMove)
    await expect(earlier).toBeEnabled()
    expect(writes).toEqual([{ expectedRuleIds: before, ruleIds: afterFirstMove }])
    expect(reads).toBe(1)

    const undo = page.waitForResponse(response => response.url().endsWith('/api/board/edits/undo'))
    await clickUnderTooltip(page, undoButton(page))
    const reversed = await undo
    expect(reversed.request().postDataJSON()).toEqual({ expectedEditHistoryRevision: recovered.editHistory.editHistoryRevision })
    expect((await reversed.json()).data).toMatchObject({ applied: true })
    await expect.poll(displayedOrder).toEqual(before)
    expect(await ruleIds(request, auth)).toEqual(before)
    expect(writes).toHaveLength(1)
  })

  test('reconciles a stale rule reorder and pauses further moves if the same order remains unconfirmed', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)
    await page.getByTestId('inspector-tab-rules').click()
    const original = await ruleIds(request, auth)
    const inspector = page.getByTestId('system-inspector')
    const row = inspector.locator(`[data-rule-id="${original[0]}"]`)
    const later = row.getByRole('button', { name: 'Move later' })
    const earlier = row.getByRole('button', { name: 'Move earlier' })
    await expect(later).toBeEnabled()
    const externalOrder = await reverseRuleOrder(request, auth)
    let writes = 0
    await page.route('**/api/board/rules/order', route => {
      writes += 1
      return writes === 1 ? route.continue() : route.abort()
    })

    const conflict = page.waitForResponse(response => response.url().endsWith('/api/board/rules/order'))
    await clickUnderTooltip(page, later)
    expect((await conflict).status()).toBe(409)
    await expect(page.getByText('Rule order changed. Review the current order', { exact: false })).toBeVisible()
    await expect(earlier).toBeEnabled()

    await clickUnderTooltip(page, earlier)
    await expect(inspector.getByTestId('rule-order-unconfirmed')).toContainText('The rule priority save is unconfirmed.')
    await expect(earlier).toBeDisabled()
    await expect(inspector.getByRole('button', { name: 'Move later' }).first()).toBeDisabled()
    await expect(row.getByRole('button', { name: 'Edit rule' })).toBeEnabled()
    expect(await inspector.locator('[data-rule-id]').evaluateAll(rows => rows.map(row => Number(row.getAttribute('data-rule-id')))))
      .toEqual(externalOrder)
    expect(await ruleIds(request, auth)).toEqual(externalOrder)

    await clickUnderTooltip(page, undoButton(page))
    await expect(inspector.getByTestId('rule-order-unconfirmed')).toHaveCount(0)
    await expect(later).toBeEnabled()
    expect(await ruleIds(request, auth)).toEqual(original)
    expect(writes).toBe(2)
  })
})

test.describe('undo boundaries', () => {
  test('recovers an unconfirmed history clear without changing the Board', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)
    const headers = { Authorization: `Bearer ${auth.token}` }
    const nodeId = 'ac_1'
    const renamed = await request.patch(`${apiBaseURL}/api/board/nodes/label?nodeId=${nodeId}`, {
      headers, data: { expectedLabel: 'Living-room Air Conditioner', label: 'Kept after clear' }
    })
    expect(renamed.ok(), await renamed.text()).toBe(true)
    await page.reload()
    const before = await boardSnapshot(request, auth)
    await expect(undoButton(page)).toBeEnabled()

    // The unreachable inverse is a test fixture. The clear, snapshot, and revision use the real server.
    await page.route('**/api/board/edits/undo', route => route.fulfill({ status: 409,
      json: { code: 409, message: 'This edit can no longer be applied.' } }))
    let writes = 0
    let reads = 0
    await page.route('**/api/board/edits/clear', async route => {
      writes += 1
      const committed = await route.fetch()
      expect(committed.ok(), await committed.text()).toBe(true)
      await route.abort()
    })
    page.on('request', outgoing => {
      if (writes && outgoing.method() === 'GET' && outgoing.url().endsWith('/api/board/snapshot')) reads += 1
    })

    await clickUnderTooltip(page, undoButton(page))
    const confirmation = page.getByRole('dialog', { name: 'Clear unavailable undo history?' })
    await expect(confirmation).toContainText('1 undo and redo entries')
    const recovery = page.waitForResponse(response => response.url().endsWith('/api/board/snapshot'))
    await confirmation.getByRole('button', { name: 'Clear history', exact: true }).click()
    const recovered = (await (await recovery).json()).data as RawBoardSnapshot
    await expect(page.getByText('Clearing history is unconfirmed. The refreshed Board has no undo or redo entries.')).toBeVisible()
    await expect(page.getByText('Undo and redo history was cleared.', { exact: true })).toHaveCount(0)
    await expect(undoButton(page)).toBeDisabled()
    await expect(redoButton(page)).toBeDisabled()
    await expect(page.locator(`[data-node-id="${nodeId}"]`)).toContainText('Kept after clear')
    expect(recovered).toEqual({ ...before, editHistory: { canUndo: false, canRedo: false, editHistoryRevision: '0:0:0' } })
    expect(writes).toBe(1)
    expect(reads).toBe(1)
  })

  test('preserves concurrent edits and blocks replay of the same unconfirmed history clear', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)
    const headers = { Authorization: `Bearer ${auth.token}` }
    const rename = async (expectedLabel: string, label: string) => {
      const updated = await request.patch(`${apiBaseURL}/api/board/nodes/label?nodeId=ac_1`, {
        headers, data: { expectedLabel, label }
      })
      expect(updated.ok(), await updated.text()).toBe(true)
    }
    await rename('Living-room Air Conditioner', 'First edit')
    await page.reload()
    await expect(undoButton(page)).toBeEnabled()

    // Simulate the unusable inverse that opens the recovery dialog, then exercise the real clear API.
    let simulateUnusableEntry = true
    await page.route('**/api/board/edits/undo', route => simulateUnusableEntry ? route.fulfill({ status: 409,
      json: { code: 409, message: 'This edit can no longer be applied.' } }) : route.continue())
    let writes = 0
    await page.route('**/api/board/edits/clear', route => ++writes === 1 ? route.continue() : route.abort())
    let lostReceiptReads = 0
    page.on('request', outgoing => {
      if (writes === 2 && outgoing.method() === 'GET' && outgoing.url().endsWith('/api/board/snapshot')) {
        lostReceiptReads += 1
      }
    })
    const dialog = page.getByRole('dialog', { name: 'Clear unavailable undo history?' })
    const confirm = dialog.getByRole('button', { name: 'Clear history', exact: true })
    const node = page.locator('[data-node-id="ac_1"]')
    await clickUnderTooltip(page, undoButton(page))
    await expect(dialog).toContainText('1 undo and redo entries')
    await rename('First edit', 'Concurrent edit')

    const conflict = page.waitForResponse(response => response.url().endsWith('/api/board/edits/clear'))
    await confirm.click()
    expect((await conflict).status()).toBe(409)
    await expect(page.getByText('Undo or redo history changed during confirmation', { exact: false })).toBeVisible()
    await expect(node).toContainText('Concurrent edit')
    await clickUnderTooltip(page, undoButton(page))
    await expect(dialog).toContainText('2 undo and redo entries')

    const refresh = page.waitForResponse(response => response.url().endsWith('/api/board/snapshot'))
    await confirm.click()
    const recovered = (await (await refresh).json()).data as RawBoardSnapshot
    await expect(page.getByText('Clearing history is unconfirmed. The refreshed Board still has undo or redo entries.',
      { exact: false })).toBeVisible()
    await expect(page.getByText('Undo and redo history was cleared.', { exact: true })).toHaveCount(0)
    await expect(page.getByText('Undo history could not be cleared. Try again later.', { exact: true })).toHaveCount(0)
    expect(lostReceiptReads).toBe(1)
    await clickUnderTooltip(page, undoButton(page))
    await expect(page.getByText('The previous clear is still unconfirmed.', { exact: false })).toBeVisible()
    await expect(dialog).toHaveCount(0)
    expect(writes).toBe(2)

    simulateUnusableEntry = false
    const undone = page.waitForResponse(response => response.url().endsWith('/api/board/edits/undo'))
    await clickUnderTooltip(page, undoButton(page))
    const response = await undone
    expect(response.request().postDataJSON()).toEqual({ expectedEditHistoryRevision: recovered.editHistory.editHistoryRevision })
    expect((await response.json()).data).toMatchObject({ applied: true })
    await expect(node).toContainText('First edit')
  })

  test('does not hijack Ctrl+Z inside a text field', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)

    const before = await ruleIds(request, auth)
    await deleteFirstRule(page)
    await expect(undoButton(page)).toBeEnabled()

    // Typing in a board input and pressing Ctrl+Z must undo the typing, not the rule deletion.
    await page.getByTestId('control-tab-devices').click()
    await expandCreation(page, 'devices')
    const nameField = page.getByTestId('single-device-name')
    await nameField.click()
    await nameField.fill('undo-scope-probe')
    await page.keyboard.press('Control+z')

    await expect.poll(async () => (await ruleIds(request, auth)).length)
      .toBe(before.length - 1)
    await expect(undoButton(page)).toBeEnabled()
  })

  test('browser Back does not reverse a board edit', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)

    const before = await ruleIds(request, auth)
    await deleteFirstRule(page)
    await expect.poll(async () => (await ruleIds(request, auth)).length)
      .toBe(before.length - 1)
    const afterDelete = await ruleIds(request, auth)

    // Back/Forward is run-surface navigation (a `?run=` deep link), a different axis from the
    // edit journal. Going back must not reverse the deletion.
    await page.goto('/#/board?run=verification:999999')
    await expect(page.getByTestId('board-deep-link-unavailable')).toBeVisible({ timeout: 60_000 })
    await page.goBack()
    await expect(page.locator('.iot-board')).toBeVisible({ timeout: 60_000 })

    await expect.poll(async () => (await ruleIds(request, auth)).sort())
      .toEqual([...afterDelete].sort())
    await expect(undoButton(page)).toBeEnabled()
  })
})
