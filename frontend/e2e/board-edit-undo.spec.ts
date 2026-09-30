import path from 'node:path'
import { type APIRequestContext, type Page } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'

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
  await page.addInitScript(({ token, user, theme }) => {
    window.localStorage.setItem('iot_verify_token', token)
    window.localStorage.setItem('iot_verify_user', JSON.stringify(user))
    window.localStorage.setItem('locale', 'en')
    window.localStorage.setItem('iot_verify_theme', theme)
  }, {
    token: auth.token,
    user: { userId: auth.userId, phone: auth.phone, username: auth.username },
    theme
  })
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
  await page.getByRole('dialog', { name: 'Confirm Full Scene Replacement' })
    .getByRole('button', { name: 'Replace in full' })
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
  for (const { viewport, theme } of [
    { viewport: { width: 1280, height: 720 }, theme: 'light' },
    { viewport: { width: 375, height: 812 }, theme: 'dark' }
  ] as const) {
    test(`renames from the keyboard without a duplicate toast and undoes at ${viewport.width}px in ${theme}`, async ({ page, request }) => {
      const auth = await createAuthenticatedUser(request)
      await page.setViewportSize(viewport)
      await openBoardWithScene(page, request, auth, theme)
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme)

      const originalLabel = 'Living-room Air Conditioner'
      const renamedLabel = 'Renamed living-room air conditioner'
      const inspector = page.getByTestId('system-inspector')
      if (await inspector.getByRole('button', { name: 'Expand', exact: true }).isVisible()) {
        await inspector.getByRole('button', { name: 'Expand', exact: true }).click()
      }
      await page.getByTestId('inspector-tab-devices').click()
      await inspector.locator('[data-device-id="ac_1"]')
        .getByRole('button', { name: originalLabel, exact: true }).click()
      await inspector.getByRole('button', { name: 'Collapse', exact: true }).click()

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
      await undoButton(page).click()
      await expect(node).toContainText(originalLabel)
      await expect.poll(async () => (await boardSnapshot(request, auth)).nodes
        .find(candidate => candidate.id === 'ac_1')?.label).toBe(originalLabel)
      await expect(redoButton(page)).toBeEnabled()
    })
  }

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

  test('restores a deleted rule to its original execution position', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)

    // Execution order is model semantics: the lower rule wins when guards overlap. Restoring the
    // content but not the position hands back a board that verifies differently.
    // Reverse first, so the expected sequence is descending by id and an append-at-the-end restore
    // produces a visibly different order rather than accidentally the right one.
    const before = await reverseRuleOrder(request, auth)
    expect(before.length).toBeGreaterThan(1)
    await expect.poll(async () => ruleIds(request, auth), { timeout: 30_000 }).toEqual(before)

    // Delete the first rule: the survivor keeps order 1, so a restore that reuses the survivor count
    // would land on 1 too and let the id tiebreak decide the ordering instead of the user.
    await deleteFirstRule(page)
    await expect.poll(async () => (await ruleIds(request, auth)).length).toBe(before.length - 1)
    await expect(undoButton(page)).toBeEnabled()

    await undoButton(page).click()
    // Order-sensitive: the exact sequence must come back, not merely the same set of ids.
    await expect.poll(async () => ruleIds(request, auth), { timeout: 30_000 }).toEqual(before)
  })

  test('reconciles undo availability when a committed edit response is incomplete', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)
    const before = await ruleIds(request, auth)

    await page.route('**/api/board/rules/*', async route => {
      if (route.request().method() !== 'DELETE') {
        await route.continue()
        return
      }
      const response = await route.fetch()
      expect(response.ok()).toBe(true)
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
    await undoButton(page).click()
    await expect.poll(async () => (await ruleIds(request, auth)).length).toBe(before.length)
  })

  test('undoes and redoes a device deletion with its cascades and environment state', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)

    const before = snapshotIdentity(await boardSnapshot(request, auth))
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
    await confirmation.getByRole('button', { name: 'Delete Device', exact: true }).click()

    const deletedToast = page.locator('.el-message').filter({
      hasText: /Deleted .*Living-room Air Conditioner/
    })
    await expect(deletedToast).toBeVisible()
    // The toast reports the very edit undo reverses, so it must neither sit over the nav nor catch a click.
    // The original defect was the second one: its text paragraph kept pointer events and swallowed the
    // click on board-undo. Polled because the toast slides in from above its resting place.
    await expect.poll(() => deletedToast.evaluate(toast => {
      const nav = document.querySelector('.board-nav-bar')!.getBoundingClientRect()
      const text = toast.querySelector('.el-message__content')!.getBoundingClientRect()
      const hit = document.elementFromPoint(text.left + text.width / 2, text.top + text.height / 2)
      return {
        belowNav: toast.getBoundingClientRect().top >= nav.bottom,
        catchesPointer: Boolean(hit?.closest('.el-message'))
      }
    })).toEqual({ belowNav: true, catchesPointer: false })
    await expect(page.locator('.el-message').filter({
      hasText: 'The device change also removed from the Environment Pool:'
    })).toHaveCount(0)
    await expect.poll(async () => (await boardSnapshot(request, auth)).nodes
      .some(candidate => candidate.id === 'ac_1')).toBe(false)
    const afterDelete = snapshotIdentity(await boardSnapshot(request, auth))
    expect(afterDelete.ruleIds.length).toBeLessThan(before.ruleIds.length)
    expect(afterDelete.specificationIds.length).toBeLessThan(before.specificationIds.length)
    expect(afterDelete.environmentVariables.length).toBeLessThan(before.environmentVariables.length)
    await expect(undoButton(page)).toBeEnabled()

    await undoButton(page).click()
    await expect.poll(async () => snapshotIdentity(await boardSnapshot(request, auth)), {
      timeout: 30_000
    }).toEqual(before)
    await expect(redoButton(page)).toBeEnabled()

    await redoButton(page).click()
    await expect.poll(async () => snapshotIdentity(await boardSnapshot(request, auth)), {
      timeout: 30_000
    }).toEqual(afterDelete)
  })

  test('drops the undo affordance when a scene replacement clears the journal', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)

    await deleteFirstRule(page)
    await expect(undoButton(page)).toBeEnabled()

    // Replacing the scene rewrites every collection, so no per-record inverse can reach a legal
    // board and the server clears the journal. The button must follow that server state rather
    // than keep offering an undo that would report "nothing to undo".
    const scenePath = path.resolve(
      process.cwd(), '..', 'docs', 'examples', 'default-fire-evacuation-scene.json'
    )
    await page.getByTestId('scene-import-file').setInputFiles(scenePath)
    const confirmation = page.getByRole('dialog', { name: 'Confirm Full Scene Replacement' })
    await expect(confirmation).toContainText('1 undo/redo history')
    await confirmation
      .getByRole('button', { name: 'Replace in full' })
      .click()

    await expect(undoButton(page)).toBeDisabled({ timeout: 60_000 })
    await expect(redoButton(page)).toBeDisabled()
  })

  test('reverses a rule reorder, which users read as one edit', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)

    await page.getByTestId('inspector-tab-rules').click()
    const before = await ruleIds(request, auth)
    expect(before.length).toBeGreaterThan(1)

    // Reorder is reached through an explicit up/down button, so one press is one reversible edit —
    // exactly what a user expects Ctrl+Z to take back.
    await page.getByRole('button', { name: 'Move later' }).first().click()
    await expect.poll(async () => ruleIds(request, auth)).not.toEqual(before)
    await expect(undoButton(page)).toBeEnabled()

    await undoButton(page).click()
    await expect.poll(async () => ruleIds(request, auth)).toEqual(before)

    await expect(redoButton(page)).toBeEnabled()
    await redoButton(page).click()
    await expect.poll(async () => ruleIds(request, auth)).not.toEqual(before)
  })

  test('keeps pressing undo past the end harmless', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)

    const before = await ruleIds(request, auth)
    await deleteFirstRule(page)
    await expect(undoButton(page)).toBeEnabled()

    await undoButton(page).click()
    await expect.poll(async () => (await ruleIds(request, auth)).length).toBe(before.length)

    // Repeating an exhausted undo is idempotent: no error, no second restore, no duplicate rule.
    await expect(undoButton(page)).toBeDisabled()
    await page.keyboard.press('Control+z')
    await page.keyboard.press('Control+z')
    await expect.poll(async () => (await ruleIds(request, auth)).sort()).toEqual([...before].sort())
  })

  test('invalidates redo once a new edit happens after an undo', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)

    const before = await ruleIds(request, auth)
    await deleteFirstRule(page)
    await undoButton(page).click()
    await expect.poll(async () => (await ruleIds(request, auth)).length).toBe(before.length)
    await expect(redoButton(page)).toBeEnabled()

    // A new edit makes the undone branch unreachable — redoing it would overwrite this edit.
    await deleteFirstRule(page)
    await expect(redoButton(page)).toBeDisabled()
    const afterNewEdit = await ruleIds(request, auth)

    await page.keyboard.press('Control+Shift+z')
    await expect.poll(async () => (await ruleIds(request, auth)).sort())
      .toEqual([...afterNewEdit].sort())
  })
})

test.describe('undo boundaries', () => {
  test('does not hijack Ctrl+Z inside a text field', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await openBoardWithScene(page, request, auth)

    const before = await ruleIds(request, auth)
    await deleteFirstRule(page)
    await expect(undoButton(page)).toBeEnabled()

    // Typing in a board input and pressing Ctrl+Z must undo the typing, not the rule deletion.
    await page.getByTestId('control-tab-devices').click()
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
