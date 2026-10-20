import type { APIResponse } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { apiBaseURL, createAuthenticatedUser, expect, test } from './support/auth'

const unwrap = async (response: Pick<APIResponse, 'ok' | 'text' | 'json'>) => {
  expect(response.ok(), await response.text()).toBeTruthy()
  const body = await response.json()
  expect(body.code, JSON.stringify(body)).toBe(200)
  return body.data
}

const specialIds = ['slash/id', 'back\\slash', 'percent%2F', 'query?id', 'fragment#id',
  'internal space', '.', '..', 'semi;colon', 'plus+id', 'comma,id']

test('all five targeted commands preserve special identities, other objects, and undo state', async ({ request }, testInfo) => {
  test.setTimeout(120_000)
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'nodeQuery' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  const runtime = {
    state: 'off', currentStateTrust: 'trusted', currentStatePrivacy: 'public', variables: [], privacies: []
  }
  const device = (id: string, label: string) => ({
    id, templateName: 'Light', label, position: { x: 200, y: 200 }, width: 176, height: 128, ...runtime
  })
  const snapshot = () => request.get(`${apiBaseURL}/api/board/snapshot`, { headers }).then(unwrap)
  const availability = () => request.get(`${apiBaseURL}/api/board/edits/availability`, { headers }).then(unwrap)
  const undo = async (expected: unknown) => {
    expect((await unwrap(await request.post(`${apiBaseURL}/api/board/edits/undo`, { headers }))).applied).toBe(true)
    expect(await snapshot()).toEqual(expected)
  }
  await unwrap(await request.post(`${apiBaseURL}/api/board/nodes`, {
    headers, data: { devices: [device('guard', 'Untouched guard')], environmentVariablePatches: [] }
  }))
  const guard = await snapshot()

  // '.' and '..' normalize to the same model token. Each iteration deletes its target before the next admission.
  for (const [index, nodeId] of specialIds.entries()) {
    await test.step(`exact identity ${JSON.stringify(nodeId)}`, async () => {
      const options = { headers, params: { nodeId } }
      await unwrap(await request.post(`${apiBaseURL}/api/board/nodes`, {
        headers, data: { devices: [device(nodeId, `Target ${index}`)], environmentVariablePatches: [] }
      }))
      const beforeRuleCreate = await snapshot()
      if (index === 0) {
        await unwrap(await request.put(`${apiBaseURL}/api/board/nodes/layout`, {
          ...options, data: { position: { x: 240, y: 230 }, width: 190, height: 140 }
        }))
        await undo(beforeRuleCreate)
        expect((await availability()).canRedo).toBe(true)
      }
      const ruleMutation = await unwrap(await request.post(`${apiBaseURL}/api/board/rules`, {
        headers, data: {
          conditions: [{ deviceName: nodeId, attribute: 'SwitchState', targetType: 'mode', relation: '=', value: 'off' }],
          command: { deviceName: nodeId, action: 'on' }, ruleString: `Target rule ${index}`
        }
      }))
      const rule = ruleMutation.affectedItem
      expect(ruleMutation.currentItems.find((item: any) => item.id === rule.id)).toEqual(rule)
      const afterRuleCreate = await snapshot()
      expect(afterRuleCreate.rules.find((item: any) => item.id === rule.id)).toEqual(rule)
      if (index === 0) {
        expect(ruleMutation.canRedo).toBe(false)
        await undo(beforeRuleCreate)
        const recreated = await unwrap(await request.post(`${apiBaseURL}/api/board/edits/redo`, { headers }))
        expect(recreated.applied).toBe(true)
        expect(recreated.rules.find((item: any) => item.id === rule.id)).toEqual(rule)
        expect(await snapshot()).toEqual(afterRuleCreate)
      }
      const specMutation = await unwrap(await request.post(`${apiBaseURL}/api/board/specs`, {
        headers, data: {
          id: `target_spec_${index}`, templateId: '3',
          aConditions: [{ deviceId: nodeId, targetType: 'mode', key: 'SwitchState', relation: '=', value: 'on' }],
          ifConditions: [], thenConditions: []
        }
      }))
      const spec = specMutation.affectedItem
      const before = await snapshot()
      const target = before.nodes.find((node: any) => node.id === nodeId)
      expect(target).toBeDefined()
      const assertOnlyDeviceChanged = async (expectedDevice: unknown) => {
        const after = await snapshot()
        expect(after).toEqual({
          ...before, nodes: before.nodes.map((node: any) => node.id === nodeId ? expectedDevice : node)
        })
      }

      const layout = { position: { x: 250, y: 240 }, width: 190, height: 140 }
      const moved = await unwrap(await request.put(`${apiBaseURL}/api/board/nodes/layout`, { ...options, data: layout }))
      expect(moved.previousDevice).toEqual(target)
      expect(moved.currentDevice).toEqual({ ...target, ...layout })
      await assertOnlyDeviceChanged(moved.currentDevice)
      await undo(before)

      const configured = await unwrap(await request.put(`${apiBaseURL}/api/board/nodes/runtime`, {
        ...options, data: { expected: runtime, desired: { ...runtime, state: 'on' } }
      }))
      expect(configured.currentDevice).toEqual({ ...target, state: 'on' })
      await assertOnlyDeviceChanged(configured.currentDevice)
      await undo(before)

      const renamed = await unwrap(await request.patch(`${apiBaseURL}/api/board/nodes/label`, {
        ...options, data: { expectedLabel: target.label, label: `Renamed target ${index}` }
      }))
      expect(renamed.affectedDevices).toEqual([{ ...target, label: `Renamed target ${index}` }])
      const afterRename = await snapshot()
      expect(afterRename.nodes).toEqual(before.nodes.map((node: any) =>
        node.id === nodeId ? renamed.affectedDevices[0] : node))
      expect(afterRename.rules).toEqual(before.rules)
      expect(afterRename.environmentVariables).toEqual(before.environmentVariables)
      expect(afterRename.deviceTemplates).toEqual(before.deviceTemplates)
      expect(afterRename.specifications.filter((item: any) => item.id !== spec.id))
        .toEqual(before.specifications.filter((item: any) => item.id !== spec.id))
      expect(afterRename.specifications.find((item: any) => item.id === spec.id).aConditions[0])
        .toMatchObject({ deviceId: nodeId, deviceLabel: `Renamed target ${index}` })
      await undo(before)

      const journalBeforePreview = await availability()
      const preview = await unwrap(await request.get(`${apiBaseURL}/api/board/nodes/deletion-preview`, options))
      expect(preview.deletedDevice).toEqual(target)
      expect(preview.removedRules.map((item: any) => item.id)).toEqual([rule.id])
      expect(preview.removedSpecifications.map((item: any) => item.id)).toEqual([spec.id])
      expect(await snapshot()).toEqual(before)
      expect(await availability()).toEqual(journalBeforePreview)

      const deleted = await unwrap(await request.post(`${apiBaseURL}/api/board/nodes/delete`, {
        ...options, data: { impactToken: preview.impactToken }
      }))
      expect(deleted.deletedDevice).toEqual(target)
      expect(await snapshot()).toEqual(guard)
      await undo(before)
      expect((await unwrap(await request.post(`${apiBaseURL}/api/board/edits/redo`, { headers }))).applied).toBe(true)
      expect(await snapshot()).toEqual(guard)
    })
  }

  for (const operation of ['layout', 'runtime', 'label', 'deletion-preview', 'delete']) {
    const method = operation === 'deletion-preview' ? 'GET' : operation === 'label' ? 'PATCH'
      : operation === 'delete' ? 'POST' : 'PUT'
    const data = operation === 'layout' ? { position: { x: 1, y: 2 }, width: 176, height: 128 }
      : operation === 'runtime' ? { expected: runtime, desired: runtime }
        : operation === 'label' ? { expectedLabel: 'Untouched guard', label: 'Wrong target' }
          : operation === 'delete' ? { impactToken: 'invalid-token' } : undefined
    const url = `${apiBaseURL}/api/board/nodes/${operation}`
    expect((await request.fetch(url, { headers, method, data })).status()).toBe(400)
    const padded = await request.fetch(url, { headers, method, data, params: { nodeId: ' guard' } })
    expect(padded.status(), await padded.text()).toBe(422)
    expect(await snapshot()).toEqual(guard)
  }
  const rejected = await request.post(`${apiBaseURL}/api/board/nodes`, {
    headers, data: { devices: [device(' padded', 'Rejected')], environmentVariablePatches: [] }
  })
  expect(rejected.status(), await rejected.text()).toBe(400)
  expect(await snapshot()).toEqual(guard)

  await unwrap(await request.post(`${apiBaseURL}/api/board/nodes`, {
    headers, data: {
      devices: ['left', 'right', 'left,right', 'left,left'].map(id => device(id, `Device ${id}`)),
      environmentVariablePatches: []
    }
  }))
  for (const [index, nodeId] of ['left,right', 'left,left'].entries()) {
    await unwrap(await request.post(`${apiBaseURL}/api/board/rules`, {
      headers, data: {
        conditions: [{ deviceName: nodeId, attribute: 'SwitchState', targetType: 'mode', relation: '=', value: 'off' }],
        command: { deviceName: nodeId, action: 'on' }, ruleString: `Comma rule ${index}`
      }
    }))
    await unwrap(await request.post(`${apiBaseURL}/api/board/specs`, {
      headers, data: {
        id: `comma_spec_${index}`, templateId: '3',
        aConditions: [{ deviceId: nodeId, targetType: 'mode', key: 'SwitchState', relation: '=', value: 'on' }],
        ifConditions: [], thenConditions: []
      }
    }))
  }
  const duplicateEvidence: unknown[] = []
  for (const values of [['left', 'right'], ['left', 'left']]) {
    for (const operation of ['layout', 'runtime', 'label', 'deletion-preview', 'delete']) {
      await test.step(`reject repeated nodeId ${JSON.stringify(values)} for ${operation}`, async () => {
        const before = await snapshot()
        const historyBefore = await unwrap(await request.get(`${apiBaseURL}/api/board/edits/clear-preview`, { headers }))
        const target = before.nodes.find((node: any) => node.id === values.join(','))
        expect(target).toBeDefined()
        const method = operation === 'deletion-preview' ? 'GET' : operation === 'label' ? 'PATCH'
          : operation === 'delete' ? 'POST' : 'PUT'
        const deletion = operation === 'delete'
          ? await unwrap(await request.get(`${apiBaseURL}/api/board/nodes/deletion-preview`, {
            headers, params: { nodeId: target.id }
          })) : undefined
        const data = operation === 'layout' ? { position: { x: 300, y: 310 }, width: 200, height: 150 }
          : operation === 'runtime' ? {
            expected: { ...runtime, state: target.state }, desired: { ...runtime, state: 'on' }
          } : operation === 'label' ? { expectedLabel: target.label, label: 'Unexpected repeated-query rename' }
            : operation === 'delete' ? { impactToken: deletion.impactToken } : undefined
        const query = new URLSearchParams()
        values.forEach(value => query.append('nodeId', value))
        const response = await request.fetch(`${apiBaseURL}/api/board/nodes/${operation}?${query}`, { headers, method, data })
        const after = await snapshot()
        const historyAfter = await unwrap(await request.get(`${apiBaseURL}/api/board/edits/clear-preview`, { headers }))
        duplicateEvidence.push({ values, operation, status: response.status(),
          snapshotUnchanged: JSON.stringify(after) === JSON.stringify(before),
          historyUnchanged: JSON.stringify(historyAfter) === JSON.stringify(historyBefore) })
        expect.soft(response.status()).toBe(400)
        expect.soft(after).toEqual(before)
        expect.soft(historyAfter).toEqual(historyBefore)
      })
    }
  }
  await testInfo.attach('repeated-node-query-evidence', {
    contentType: 'application/json', body: Buffer.from(JSON.stringify(duplicateEvidence))
  })
})

test('portable scene import and export retain a URI-special node identity used by the UI rename and deletion', async ({ page, request }) => {
  test.setTimeout(90_000)
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'nodeQueryUi' })
  const headers = { Authorization: `Bearer ${auth.token}` }
  const nodeId = 'uri/%?#\\ +;node'
  const manifest = JSON.parse(await readFile(path.resolve(
    process.cwd(), '..', 'backend', 'src', 'main', 'resources', 'deviceTemplate', 'Light.json'
  ), 'utf8'))
  const scene = {
    schema: 'iot-verify.board-scene', version: 5, templates: [{ name: 'Light', manifest }],
    devices: [{ id: nodeId, templateName: 'Light', label: 'URI special light', state: 'off',
      position: { x: 200, y: 200 }, width: 176, height: 128 }],
    environmentVariables: [{ name: 'illuminance', value: '10', trust: 'untrusted', privacy: 'public' }],
    rules: [], specs: []
  }
  await page.addInitScript(auth => {
    localStorage.setItem('iot_verify_token', auth.token)
    localStorage.setItem('iot_verify_user', JSON.stringify(auth))
    localStorage.setItem('locale', 'en')
  }, auth)
  await page.goto('/#/board')
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
  await page.getByTestId('scene-import-file').setInputFiles({
    name: 'node-query-scene.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(scene))
  })
  const imported = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/board/scene')
  await page.getByRole('dialog', { name: 'Replace the current scene?' })
    .getByRole('button', { name: 'Replace scene' }).click()
  expect((await imported).ok()).toBe(true)
  const node = page.locator('.device-node').filter({ hasText: 'URI special light' })
  await expect(node).toBeVisible()
  expect(await node.getAttribute('data-node-id')).toBe(nodeId)
  await node.click()
  await page.getByTestId('device-dialog').getByTestId('device-rename').click()
  const rename = page.getByRole('dialog', { name: 'Rename device' })
  await rename.getByPlaceholder('Enter device name').fill('URI renamed light')
  const renamed = page.waitForResponse(response => response.request().method() === 'PATCH'
    && new URL(response.url()).pathname === '/api/board/nodes/label'
    && new URL(response.url()).searchParams.get('nodeId') === nodeId)
  await rename.getByPlaceholder('Enter device name').press('Enter')
  expect((await renamed).ok()).toBe(true)
  const persisted = await unwrap(await request.get(`${apiBaseURL}/api/board/nodes`, { headers }))
  expect(persisted.map((item: any) => ({ id: item.id, label: item.label })))
    .toEqual([{ id: nodeId, label: 'URI renamed light' }])
  await page.getByTestId('board-undo').click()
  await expect(node).toContainText('URI special light')
  const downloadPromise = page.waitForEvent('download')
  await page.getByTestId('scene-export').click()
  const download = await downloadPromise
  const stream = await download.createReadStream()
  const chunks: Buffer[] = []
  for await (const chunk of stream) chunks.push(Buffer.from(chunk))
  const exported = JSON.parse(Buffer.concat(chunks).toString('utf8'))
  expect(exported.devices[0].id).toBe(nodeId)
  expect(exported.devices[0].label).toBe('URI special light')
  await node.click()
  const previewResponse = page.waitForResponse(response => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/board/nodes/deletion-preview'
    && new URL(response.url()).searchParams.get('nodeId') === nodeId)
  await page.getByTestId('device-dialog').getByTestId('device-delete').click()
  const preview = await unwrap(await previewResponse)
  expect(preview.deletedDevice.id).toBe(nodeId)
  const deletedResponse = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/board/nodes/delete'
    && new URL(response.url()).searchParams.get('nodeId') === nodeId)
  await page.getByRole('dialog', { name: 'Delete device' })
    .getByRole('button', { name: 'Delete Device', exact: true }).click()
  const deleted = await unwrap(await deletedResponse)
  expect(deleted.impactToken).toBe(preview.impactToken)
  expect(await unwrap(await request.get(`${apiBaseURL}/api/board/nodes`, { headers }))).toEqual([])
  await page.getByTestId('board-undo').click()
  await expect(node).toBeVisible()
  expect((await unwrap(await request.get(`${apiBaseURL}/api/board/nodes`, { headers })))[0].id).toBe(nodeId)
})
