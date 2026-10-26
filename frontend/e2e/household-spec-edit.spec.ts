import path from 'node:path'
import { type APIRequestContext } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'
import { dismissHintTooltips } from './support/tooltips'

test.setTimeout(120_000)

const readBoard = async (request: APIRequestContext, auth: AuthUser) => {
  const response = await request.get(`${apiBaseURL}/api/board/snapshot`, {
    headers: { Authorization: `Bearer ${auth.token}` }
  })
  expect(response.ok(), await response.text()).toBe(true)
  return (await response.json()).data
}

test('revises a household requirement in place and restores it through undo and redo', async ({ page, request }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'home_spec' })
  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('locale', 'en')
    localStorage.setItem('iot_verify_theme', 'light')
  }, auth)
  await page.goto('/#/board')
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 60_000 })
  await page.getByTestId('scene-import-file').setInputFiles(path.resolve(
    process.cwd(), '..', 'docs', 'examples', 'household-entry-scene.json'))
  const imported = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/board/scene')
  await page.getByRole('dialog', { name: 'Replace the current scene?' })
    .getByRole('button', { name: 'Replace scene', exact: true }).click()
  expect((await imported).ok()).toBe(true)
  const before = await readBoard(request, auth)
  expect(before.specifications).toHaveLength(3)
  const original = before.specifications.find((spec: any) => spec.thenConditions.some(
    (condition: any) => condition.deviceId === 'light_1'))
  expect(original).toBeTruthy()

  await page.getByTestId('inspector-tab-specs').click()
  await page.getByTestId(`edit-spec-${original.id}`).click()
  const editor = page.getByTestId('spec-editor-fieldset')
  await expect(editor.getByTestId('spec-name')).toHaveValue(original.name)
  await editor.getByTestId('spec-name').fill('Light the entrance when a visitor arrives at home')
  await expect(editor.getByTestId('spec-template-select')).toHaveValue(original.templateId)
  await expect(editor.getByTestId('spec-create')).toHaveText('Save changes')
  // The first IF condition controls whether the visitor-light requirement applies at home or away.
  await editor.getByRole('button', { name: 'Edit condition 1', exact: true }).first().click()
  const dialog = page.getByTestId('spec-condition-dialog')
  await expect(dialog.getByTestId('spec-condition-device')).toHaveValue('occupancy_1')
  await expect(dialog.getByTestId('spec-condition-value')).toHaveValue('absent')
  await expect(dialog.getByTestId('spec-condition-variable-source-environment').locator('input')).toBeChecked()
  await dialog.getByTestId('spec-condition-value').selectOption('present')
  await dialog.getByTestId('spec-condition-save').click()
  await expect(dialog).toBeHidden()
  await dismissHintTooltips(page)
  const saved = page.waitForResponse(response => response.request().method() === 'PUT'
    && new URL(response.url()).pathname === `/api/board/specs/${encodeURIComponent(original.id)}`)
  await editor.getByTestId('spec-create').click()
  const response = await saved
  expect(response.ok(), await response.text()).toBe(true)
  expect(response.request().postDataJSON()).toMatchObject({
    expected: { id: original.id, name: original.name, ifConditions: original.ifConditions.map((condition: any) => ({
      deviceId: condition.deviceId, value: condition.value
    })) },
    desired: { id: original.id, name: 'Light the entrance when a visitor arrives at home', ifConditions: [{ deviceId: 'occupancy_1', value: 'present' }, { deviceId: 'entry_1', value: 'visitor' }] }
  })
  const mutation = (await response.json()).data
  expect(mutation).toMatchObject({ operation: 'updated', canUndo: true, canRedo: false })
  const after = await readBoard(request, auth)
  expect(after.specifications).toEqual(mutation.currentItems)
  expect(after.specifications.map((spec: any) => spec.id)).toEqual(before.specifications.map((spec: any) => spec.id))
  expect(after.specifications.filter((spec: any) => spec.id !== original.id))
    .toEqual(before.specifications.filter((spec: any) => spec.id !== original.id))
  const edited = after.specifications.find((spec: any) => spec.id === original.id)
  await expect(page.locator(`[data-spec-id="${original.id}"] h4`)).toHaveText(edited.name)
  expect(edited.ifConditions[0]).toMatchObject({ deviceId: 'occupancy_1', variableSource: 'environment', value: 'present' })
  expect(after.rules).toEqual(before.rules)
  expect(after.nodes).toEqual(before.nodes)
  await page.screenshot({ path: testInfo.outputPath('household-requirement-revised.png') })

  await expect(page.getByTestId('board-undo')).toBeEnabled()
  await page.getByTestId('board-undo').click()
  await expect.poll(() => readBoard(request, auth)).toEqual(before)
  await expect(page.getByTestId('board-redo')).toBeEnabled()
  await page.getByTestId('board-redo').click()
  await expect.poll(() => readBoard(request, auth)).toEqual(after)
})
