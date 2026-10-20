import { readFileSync } from 'node:fs'
import type { Page } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test } from './support/auth'
import { clickUnderTooltip } from './support/tooltips'
import type { RuleDto } from '../src/types/rule'
import type { Specification } from '../src/types/spec'

const fixture = JSON.parse(readFileSync(new URL('../src/testing/fixtures/device-list-encoding.json', import.meta.url), 'utf8')) as {
  csv: string
  devices: { label: string }[]
  gb18030Base64: string
}

type StoredDevice = {
  id: string; templateName: string; label: string; state: string
  currentStateTrust: string | null; currentStatePrivacy: string | null
  variables: { name: string; value: string; trust?: string }[]
  privacies: { name: string; privacy: string }[]
}

const openDevice = async (page: Page, id: string, locale: string) => {
  const inspector = page.getByTestId('system-inspector')
  const control = page.getByTestId('control-center')
  const collapseName = locale === 'en' ? /^Collapse (Control Center|System Inspector)$/ : /^收起(控制中心|系统检查器)$/
  const expandName = locale === 'en' ? /^Expand (Control Center|System Inspector)$/ : /^展开(控制中心|系统检查器)$/
  const collapseControl = control.getByRole('button', { name: collapseName, exact: true })
  if (await collapseControl.isVisible()) await clickUnderTooltip(page, collapseControl)
  const expandInspector = inspector.getByRole('button', { name: expandName, exact: true })
  if (await expandInspector.isVisible()) await clickUnderTooltip(page, expandInspector)
  await page.getByTestId('inspector-tab-devices').click()
  await inspector.locator(`[data-device-id="${id}"] .board-inventory-open`).click()
  await inspector.getByRole('button', { name: collapseName, exact: true }).click()
  await page.locator(`[data-node-id="${id}"]`).click()
  await expect(page.getByTestId('device-dialog')).toBeVisible()
}

const exportScene = async (page: Page, locale: string) => {
  const download = page.waitForEvent('download')
  const button = page.getByTestId('scene-export')
  if (await button.isVisible()) await clickUnderTooltip(page, button)
  else {
    await page.locator('.scene-actions-menu summary').click()
    await page.locator('.scene-actions-menu').getByRole('button', {
      name: locale === 'en' ? 'Export Scene' : '导出场景', exact: true
    }).click()
  }
  const file = await (await download).path()
  expect(file).toBeTruthy()
  return { path: file!, data: JSON.parse(readFileSync(file!, 'utf8')) }
}

const addReferences = async (page: Page, firstId: string, secondId: string, locale: string) => {
  const openEditor = async (section: 'rules' | 'specs') => {
    const inspectorTab = page.getByTestId(`inspector-tab-${section}`)
    if (await inspectorTab.isVisible()) {
      await inspectorTab.click()
      await clickUnderTooltip(page, page.getByTestId(section === 'rules' ? 'inspector-add-rule' : 'inspector-add-spec'))
      return
    }
    const expand = page.getByTestId('control-center').getByRole('button', {
      name: locale === 'en' ? 'Expand Control Center' : '展开控制中心', exact: true
    })
    if (await expand.isVisible()) await clickUnderTooltip(page, expand)
    await page.getByTestId(`control-tab-${section}`).click()
  }
  for (const [source, event, target, action, contentDevice, name] of [
    [firstId, 'take photo', secondId, 'upload to cloud', firstId, '导入手机发送照片'],
    [secondId, 'upload to cloud', firstId, 'take photo', '', '备用手机触发拍照'],
    [secondId, 'take photo', secondId, 'take photo', '', '保留的备用手机规则']
  ]) {
    // A narrow-screen save opens the inspector to show the created item.
    await openEditor('rules')
    await page.locator('#rule-builder-name').fill(name!)
    await page.locator('#rule-source-device').selectOption(source!)
    await page.locator('#rule-source-type').selectOption('api')
    await page.locator('#rule-source-api').selectOption(event!)
    await page.getByTestId('rule-add-source').click()
    await page.locator('#rule-target-device').selectOption(target!)
    await page.locator('#rule-target-action').selectOption(action!)
    if (contentDevice) {
      await page.getByTestId('rule-content-device').selectOption(contentDevice)
      await page.getByTestId('rule-content-name').selectOption('photo')
    }
    await page.getByTestId('rule-save').click()
    await expect(page.locator('#rule-builder-name')).toHaveValue('')
    await expect(page.getByTestId('inspector-section-rules')).toBeVisible()
  }
  for (const id of [firstId, secondId]) {
    await openEditor('specs')
    await page.getByTestId('spec-template-select').selectOption('1')
    await page.getByTestId('spec-add-condition-a').click()
    await page.getByTestId('spec-condition-device').selectOption(id)
    await page.getByTestId('spec-condition-type').selectOption('state')
    await page.getByTestId('spec-condition-relation').selectOption('=')
    await page.getByTestId('spec-condition-value').selectOption('on')
    await page.getByTestId('spec-condition-save').click()
    await page.getByTestId('spec-create').click()
    await expect(page.getByTestId('spec-create')).toBeDisabled()
    await expect(page.getByTestId('inspector-section-specs')).toBeVisible()
  }
}

for (const locale of ['en', 'zh-CN']) for (const format of ['json', 'utf-8', 'gb18030']) {
  test(`imported ${format} devices retain references and runtime through edits, deletion, undo and scene round trip in ${locale}`, async ({ page, request }, testInfo) => {
    test.setTimeout(120_000)
    const auth = await createAuthenticatedUser(request, { usernamePrefix: 'importlife' })
    const headers = { Authorization: `Bearer ${auth.token}` }
    const snapshot = async () => {
      const response = await request.get(`${apiBaseURL}/api/board/snapshot`, { headers })
      expect(response.ok(), await response.text()).toBe(true)
      const body = await response.json()
      expect(body.code).toBe(200)
      return {
        nodes: (body.data.nodes as StoredDevice[]).map(({ id, templateName, label, state, currentStateTrust,
          currentStatePrivacy, variables, privacies }) => ({ id, templateName, label, state, currentStateTrust,
          currentStatePrivacy, variables, privacies })).sort((a, b) => a.id.localeCompare(b.id)),
        environment: body.data.environmentVariables.map(({ name, value, trust, privacy }: Record<string, string>) =>
          ({ name, value, trust, privacy })).sort((a: { name: string }, b: { name: string }) => a.name.localeCompare(b.name)),
        rules: body.data.rules as RuleDto[],
        specs: body.data.specifications as Specification[]
      }
    }
    const reload = async () => {
      await page.reload()
      await expect(page.getByTestId('board-data-loading')).toHaveCount(0)
      await expect(page.getByTestId('scene-import-file')).toBeEnabled()
    }
    const undo = async (expected: Awaited<ReturnType<typeof snapshot>>) => {
      await expect(page.getByTestId('board-undo')).toBeEnabled()
      await clickUnderTooltip(page, page.getByTestId('board-undo'))
      await expect.poll(snapshot).toEqual(expected)
    }
    const redo = async (expected: Awaited<ReturnType<typeof snapshot>>) => {
      await expect(page.getByTestId('board-redo')).toBeEnabled()
      await clickUnderTooltip(page, page.getByTestId('board-redo'))
      await expect.poll(snapshot).toEqual(expected)
    }
    const saveRuntime = async () => {
      const response = page.waitForResponse(reply => reply.request().method() === 'PUT'
        && new URL(reply.url()).pathname === '/api/board/nodes/runtime')
      await page.getByTestId('device-runtime-save').click()
      expect((await response).ok()).toBe(true)
      await expect(page.getByTestId('device-runtime-save')).toBeEnabled()
      await page.getByTestId('device-dialog-close').click()
      await expect(page.getByTestId('device-dialog')).toHaveCount(0)
    }
    await page.addInitScript(({ auth, locale }) => {
      localStorage.setItem('iot_verify_token', auth.token)
      localStorage.setItem('iot_verify_user', JSON.stringify(auth))
      localStorage.setItem('locale', locale)
    }, { auth, locale })
    await page.setViewportSize({ width: locale === 'en' ? 1440 : 375, height: 900 })
    await page.goto('/#/board')
    await expect(page.getByTestId('scene-import-file')).toBeEnabled()
    const empty = await snapshot()
    const collapseInspector = page.getByTestId('system-inspector').getByRole('button', {
      name: locale === 'en' ? 'Collapse System Inspector' : '收起系统检查器', exact: true
    })
    if (await collapseInspector.isVisible()) await clickUnderTooltip(page, collapseInspector)
    const expandControl = page.getByTestId('control-center').getByRole('button', {
      name: locale === 'en' ? 'Expand Control Center' : '展开控制中心', exact: true
    })
    if (await expandControl.isVisible()) await clickUnderTooltip(page, expandControl)
    await page.getByTestId('control-tab-devices').click()
    await page.getByTestId('device-create-mode-import').click()
    await page.getByTestId(`device-import-format-${format === 'json' ? 'json' : 'csv'}`).click()
    if (format !== 'json') await page.getByTestId('device-import-csv-encoding').selectOption(format)
    await page.getByTestId('device-import-file').setInputFiles({
      name: format === 'json' ? 'devices.json' : 'devices.csv',
      mimeType: format === 'json' ? 'application/json' : 'text/csv',
      buffer: format === 'gb18030' ? Buffer.from(fixture.gb18030Base64, 'base64')
        : Buffer.from('\uFEFF' + (format === 'json' ? JSON.stringify(fixture.devices) : fixture.csv), 'utf8')
    })
    await expect(page.getByTestId('device-import-preview').locator('strong')).toHaveText('2/2')
    await page.getByTestId('device-import-create').click()
    await expect.poll(async () => (await snapshot()).nodes.length).toBe(2)
    let imported = await snapshot()
    const first = imported.nodes.find(node => node.label === fixture.devices[0]!.label)!
    const second = imported.nodes.find(node => node.label === fixture.devices[1]!.label)!
    expect(first).toMatchObject({ state: 'on', currentStateTrust: 'untrusted', currentStatePrivacy: 'private',
      variables: [expect.objectContaining({ name: 'steps', value: '0', trust: 'trusted' })],
      privacies: [expect.objectContaining({ name: 'steps', privacy: 'public' })] })
    expect(second).toMatchObject({ state: 'on', currentStateTrust: null, currentStatePrivacy: null })
    expect(imported.environment).toContainEqual({ name: 'location', value: 'home', trust: 'trusted', privacy: 'private' })
    await undo(empty)
    await reload()
    await redo(imported)
    await addReferences(page, first.id, second.id, locale)
    imported = await snapshot()
    expect(imported.rules).toHaveLength(3)
    expect(imported.rules[0]).toMatchObject({ conditions: [{ deviceName: first.id }],
      command: { deviceName: second.id, contentDevice: first.id, content: 'photo' } })
    expect(imported.rules[1]!.command.deviceName).toBe(first.id)
    expect(imported.specs).toHaveLength(2)
    expect(imported.specs.map(spec => spec.aConditions[0]!.deviceId)).toEqual([first.id, second.id])

    await openDevice(page, first.id, locale)
    await expect(page.getByTestId('device-runtime-variable-steps')).toHaveValue('0')
    await page.getByTestId('device-runtime-advanced-security').locator('summary').click()
    await expect(page.getByTestId('device-runtime-state-trust')).toHaveValue('untrusted')
    await expect(page.getByTestId('device-runtime-state-privacy')).toHaveValue('private')
    await expect(page.getByTestId('device-runtime-variable-trust-steps')).toHaveValue('trusted')
    await expect(page.getByTestId('device-runtime-privacy-steps')).toHaveValue('public')
    await page.getByTestId('device-runtime-variable-steps').fill('31')
    await saveRuntime()
    const valueEdited = structuredClone(imported)
    valueEdited.nodes.find(node => node.id === first.id)!.variables.find(variable => variable.name === 'steps')!.value = '31'
    await expect.poll(snapshot).toEqual(valueEdited)
    await undo(imported)
    await redo(valueEdited)
    await reload()
    await expect.poll(snapshot).toEqual(valueEdited)
    await openDevice(page, first.id, locale)
    await expect(page.getByTestId('device-runtime-variable-steps')).toHaveValue('31')
    await page.getByTestId('device-runtime-advanced-security').locator('summary').click()
    await page.getByTestId('device-runtime-state').selectOption('taking photo')
    await page.getByTestId('device-runtime-variable-steps').fill('0')
    await page.getByTestId('device-runtime-state-trust').selectOption('trusted')
    await page.getByTestId('device-runtime-state-privacy').selectOption('public')
    await page.getByTestId('device-runtime-variable-trust-steps').selectOption('untrusted')
    await page.getByTestId('device-runtime-privacy-steps').selectOption('private')
    await saveRuntime()
    const tagsEdited = structuredClone(imported)
    const tagged = tagsEdited.nodes.find(node => node.id === first.id)!
    Object.assign(tagged, { state: 'taking photo', currentStateTrust: 'trusted', currentStatePrivacy: 'public' })
    tagged.variables.find(variable => variable.name === 'steps')!.trust = 'untrusted'
    tagged.privacies.find(privacy => privacy.name === 'steps')!.privacy = 'private'
    await expect.poll(snapshot).toEqual(tagsEdited)
    await undo(valueEdited)
    await redo(tagsEdited)

    await openDevice(page, first.id, locale)
    await page.getByTestId('device-rename').click()
    const name = '二楼, "主手机" / # % + 已编辑'
    await page.getByTestId('rename-device-name').fill(name)
    await page.getByTestId('rename-device-name').press('Enter')
    await expect(page.getByTestId('rename-device-name')).toHaveCount(0)
    const renamed = structuredClone(tagsEdited)
    renamed.nodes.find(node => node.id === first.id)!.label = name
    for (const spec of renamed.specs) {
      for (const condition of [...spec.aConditions, ...spec.ifConditions, ...spec.thenConditions]) {
        if (condition.deviceId === first.id) condition.deviceLabel = name
      }
      for (const device of spec.devices || []) if (device.deviceId === first.id) device.deviceLabel = name
      const quotedLabel = (label: string) => label.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
      if (spec.formula) spec.formula = spec.formula.replaceAll(quotedLabel(first.label), quotedLabel(name))
    }
    await expect.poll(snapshot).toEqual(renamed)
    await undo(tagsEdited)
    await redo(renamed)
    await reload()
    await expect.poll(snapshot).toEqual(renamed)

    const edgeCount = await page.locator('.edge-hitarea').count()
    expect(edgeCount).toBeGreaterThan(0)
    await openDevice(page, first.id, locale)
    await page.getByTestId('device-delete').click()
    const deletion = page.getByRole('dialog', { name: locale === 'en' ? 'Delete Device' : '确认删除设备', exact: true })
    await expect(deletion).toContainText(locale === 'en' ? 'Related rules to delete: 2' : '2 条关联规则将被删除')
    await expect(deletion).toContainText(locale === 'en' ? 'Related specifications to delete: 1' : '1 条关联规约将被删除')
    await expect(deletion).not.toContainText('保留的备用手机规则')
    await expect(page.getByTestId('device-dialog')).toHaveCount(0)
    await expect(deletion.locator('..')).toHaveCSS('opacity', '1')
    await expect(deletion).toHaveCSS('opacity', '1')
    await page.screenshot({ path: testInfo.outputPath('delete-referenced-device.png') })
    await deletion.getByRole('button', { name: locale === 'en' ? 'Delete Device' : '删除设备', exact: true }).click()
    const deleted = structuredClone(renamed)
    deleted.nodes = deleted.nodes.filter(node => node.id !== first.id)
    deleted.rules = deleted.rules.slice(2)
    deleted.specs = deleted.specs.slice(1)
    await expect.poll(snapshot).toEqual(deleted)
    await expect.poll(() => page.locator('.edge-hitarea').count()).toBeLessThan(edgeCount)
    await undo(renamed)
    await expect(page.locator('.edge-hitarea')).toHaveCount(edgeCount)
    await redo(deleted)
    await reload()
    await undo(renamed)

    const exported = await exportScene(page, locale)
    const portable = exported.data.devices.find((node: StoredDevice) => node.id === first.id)
    expect(portable).toMatchObject(renamed.nodes.find(node => node.id === first.id)!)
    const inherited = exported.data.devices.find((node: StoredDevice) => node.id === second.id)
    expect(inherited).not.toHaveProperty('currentStateTrust')
    expect(inherited).not.toHaveProperty('currentStatePrivacy')
    expect(exported.data.environmentVariables).toEqual(renamed.environment)
    await openDevice(page, first.id, locale)
    await page.getByTestId('device-runtime-variable-steps').fill('47')
    await saveRuntime()
    await page.getByTestId('scene-import-file').setInputFiles(exported.path)
    const confirmation = page.getByRole('dialog', { name: locale === 'en' ? 'Replace the current scene?' : '替换当前场景？' })
    await expect(confirmation.getByTestId('scene-import-comparison')).toBeVisible()
    await expect(confirmation.locator('..')).toHaveCSS('opacity', '1')
    await expect(confirmation).toHaveCSS('opacity', '1')
    await page.screenshot({ path: testInfo.outputPath('scene-roundtrip-confirm.png') })
    const replacement = page.waitForResponse(reply => reply.request().method() === 'POST'
      && new URL(reply.url()).pathname === '/api/board/scene')
    await confirmation.getByRole('button', { name: locale === 'en' ? 'Replace scene' : '替换场景', exact: true }).click()
    expect((await replacement).ok()).toBe(true)
    // Storage IDs and creation times are regenerated; portable references and order stay identical.
    await expect.poll(async () => (await snapshot()).nodes).toEqual(renamed.nodes)
    const afterSceneImport = await snapshot()
    expect(afterSceneImport.environment).toEqual(renamed.environment)
    expect(afterSceneImport.rules).toMatchObject(renamed.rules.map(({ conditions, command, ruleString }) => ({
      conditions, command, ruleString
    })))
    expect(afterSceneImport.specs).toMatchObject(renamed.specs.map(({ id: _id, aConditions, ifConditions, thenConditions, ...spec }) => ({
      ...spec,
      aConditions: aConditions.map(({ id: _id, ...condition }) => condition),
      ifConditions: ifConditions.map(({ id: _id, ...condition }) => condition),
      thenConditions: thenConditions.map(({ id: _id, ...condition }) => condition)
    })))
    await expect(page.getByTestId('board-undo')).toBeDisabled()
    await expect(page.getByTestId('board-redo')).toBeDisabled()
    await reload()
    await expect.poll(snapshot).toEqual(afterSceneImport)
    await openDevice(page, first.id, locale)
    await expect(page.getByTestId('device-runtime-state')).toHaveValue('taking photo')
    await expect(page.getByTestId('device-runtime-variable-steps')).toHaveValue('0')
    await page.getByTestId('device-runtime-advanced-security').locator('summary').click()
    await expect(page.getByTestId('device-runtime-state-trust')).toHaveValue('trusted')
    await expect(page.getByTestId('device-runtime-state-privacy')).toHaveValue('public')
    await expect(page.getByTestId('device-runtime-variable-trust-steps')).toHaveValue('untrusted')
    await expect(page.getByTestId('device-runtime-privacy-steps')).toHaveValue('private')
    await page.getByTestId('device-runtime-state-trust').scrollIntoViewIfNeeded()
    await expect(page.getByTestId('device-dialog').locator('..')).toHaveCSS('opacity', '1')
    await expect(page.getByTestId('device-dialog')).toHaveCSS('opacity', '1')
    await page.screenshot({ path: testInfo.outputPath('restored-device.png') })
    await page.getByTestId('device-dialog-close').click()
    const reExported = await exportScene(page, locale)
    expect(reExported.data).toEqual(exported.data)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
}
