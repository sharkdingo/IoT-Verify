import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { apiBaseURL, createAuthenticatedUser, expect, test } from './support/auth'
import { clickUnderTooltip } from './support/tooltips'
import { expectHeaderAligned } from './support/headerAlignment'
import { expectFooterUsable } from './support/footerLayout'

for (const variant of [
  { locale: 'en', width: 1280, height: 800 },
  { locale: 'zh-CN', width: 375, height: 500 },
  { locale: 'zh-CN', width: 320, height: 500 },
  { locale: 'en', width: 320, height: 500 }
]) {
  test(`editors and account dialogs keep feedback and headers consistent in ${variant.locale} at ${variant.width}px`, async ({ page, request }, testInfo) => {
    const chinese = variant.locale === 'zh-CN'
    const copy = chinese ? {
      expand: /^展开(控制中心|系统检查器)$/, collapse: /^收起(控制中心|系统检查器)$/, name: '设备名称', template: '设备模板', rename: '重命名设备',
      confirm: '确定', duplicate: '设备名称已存在', comparison: '比较方式', cancel: '取消',
      discard: '放弃修改', discardTitle: '放弃未保存的设备配置？'
    } : {
      expand: /^Expand (Control Center|System Inspector)$/, collapse: /^Collapse (Control Center|System Inspector)$/, name: 'Device Name', template: 'Device Template', rename: 'Rename Device',
      confirm: 'Confirm', duplicate: 'Device name already exists', comparison: 'Comparison', cancel: 'Cancel',
      discard: 'Discard changes', discardTitle: 'Discard unsaved device configuration?'
    }
    const auth = await createAuthenticatedUser(request, { usernamePrefix: 'editorcopy' })
    const headers = { Authorization: `Bearer ${auth.token}` }
    const scene = JSON.parse(await readFile(path.resolve(process.cwd(), '..', 'docs/examples/default-climate-conflict-scene.json'), 'utf8'))
    const phone = JSON.parse(await readFile(path.resolve(process.cwd(), '..', 'backend/src/main/resources/deviceTemplate/Mobile Phone.json'), 'utf8'))
    scene.templates.push({ name: phone.Name, manifest: phone })
    scene.environmentVariables.push({ name: 'location', value: 'home', trust: 'untrusted', privacy: 'private' })
    scene.devices.push({ id: 'editor_phone', label: 'Imported phone', templateName: phone.Name,
      position: { x: 500, y: 300 }, width: 176, height: 128, state: 'on',
      variables: [{ name: 'steps', value: '0', trust: 'untrusted' }] })
    const preview = await request.get(`${apiBaseURL}/api/board/replacement-preview`, { headers })
    expect(preview.ok()).toBe(true)
    const replacement = await request.post(`${apiBaseURL}/api/board/scene`, { headers,
      data: { scene, impactToken: (await preview.json()).data.impactToken } })
    expect(replacement.ok(), await replacement.text()).toBe(true)
    await page.addInitScript(({ auth, locale }) => {
      localStorage.setItem('iot_verify_token', auth.token)
      localStorage.setItem('iot_verify_user', JSON.stringify(auth))
      localStorage.setItem('locale', locale)
    }, { auth, locale: variant.locale })
    await page.setViewportSize(variant)
    await page.goto('/#/board')
    await expect(page.getByTestId('board-root')).toBeVisible()
    await expect(page.getByTestId('board-data-loading')).toHaveCount(0)
    const control = page.getByTestId('control-center')
    const inspector = page.getByTestId('system-inspector')
    const openEditor = async (section: string) => {
      const collapseInspector = inspector.getByRole('button', { name: copy.collapse, exact: true })
      if (await collapseInspector.isVisible()) await collapseInspector.click()
      const expand = control.getByRole('button', { name: copy.expand, exact: true })
      if (await expand.isVisible()) await clickUnderTooltip(page, expand)
      await page.getByTestId(`control-tab-${section}`).click()
    }
    const openDevice = async (id = 'ac_1', label = 'Living-room Air Conditioner') => {
      const collapseControl = control.getByRole('button', { name: copy.collapse, exact: true })
      if (await collapseControl.isVisible()) await collapseControl.click()
      const expand = inspector.getByRole('button', { name: copy.expand, exact: true })
      if (await expand.isVisible()) await clickUnderTooltip(page, expand)
      await page.getByTestId('inspector-tab-devices').click()
      await inspector.locator(`[data-device-id="${id}"]`).getByRole('button', { name: label, exact: true }).click()
      await inspector.getByRole('button', { name: copy.collapse, exact: true }).click()
      await page.locator(`[data-node-id="${id}"]`).click()
      await expect(page.getByTestId('device-dialog')).toBeVisible()
    }
    const expectFits = async (surface: ReturnType<typeof page.locator>) => {
      const bounds = await surface.boundingBox()
      expect(bounds).not.toBeNull()
      expect(bounds!.x).toBeGreaterThanOrEqual(0)
      expect(bounds!.y).toBeGreaterThanOrEqual(0)
      expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(variant.width)
      expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(variant.height)
      expect(await surface.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    }
    const expectVariableHeading = async (input: ReturnType<typeof page.locator>) => {
      await input.scrollIntoViewIfNeeded()
      const layout = await input.evaluate(element => {
        const heading = element.closest('label')!.parentElement!.previousElementSibling!
        const name = heading.querySelector<HTMLElement>('[title]')!
        const a = name.getBoundingClientRect()
        const b = heading.lastElementChild!.getBoundingClientRect()
        return { nameWidth: a.width, fullName: name.scrollWidth <= name.clientWidth + 1,
          fits: heading.scrollWidth <= heading.clientWidth + 1,
          overlaps: Math.min(a.right, b.right) > Math.max(a.left, b.left) + 1
            && Math.min(a.bottom, b.bottom) > Math.max(a.top, b.top) + 1 }
      })
      expect.soft(layout.nameWidth, 'variable name must remain visible').toBeGreaterThan(0)
      expect.soft(layout.fullName, 'variable name must not be squeezed by the range hint').toBe(true)
      expect.soft(layout.fits, 'variable heading must fit its card').toBe(true)
      expect.soft(layout.overlaps, 'variable name and range hint must not overlap').toBe(false)
    }
    const expectConditionActions = async () => {
      const footer = page.getByTestId('spec-condition-dialog').locator('.iot-dialog__footer')
      const layout = await footer.evaluate(element => {
        const reason = element.querySelector('[role="status"]')!.getBoundingClientRect()
        const buttons = [...element.querySelectorAll('button')].map(button => button.getBoundingClientRect())
        return { separated: reason.bottom + 4 <= Math.min(...buttons.map(rect => rect.top)),
          sameRow: Math.abs(buttons[0]!.top - buttons[1]!.top) <= 1,
          heights: buttons.map(rect => rect.height) }
      })
      expect.soft(layout.separated, 'validation feedback needs its own row above the actions').toBe(true)
      expect.soft(layout.sameRow, 'cancel and confirm must remain together').toBe(true)
      for (const height of layout.heights) expect.soft(height, 'short action labels must stay on one line').toBeCloseTo(44, 0)
    }

    await openEditor('templates')
    await page.getByTestId('reset-default-templates').click()
    const resetDefaults = page.getByRole('dialog').filter({ has: page.getByTestId('default-template-reset-confirm') })
    await expectFooterUsable(resetDefaults.locator('.iot-dialog__footer'))
    for (const button of await resetDefaults.locator('.iot-dialog__footer button').all()) {
      expect.soft(await button.evaluate(element => element.getBoundingClientRect().height),
        'reset confirmation actions should not split short labels across lines').toBeCloseTo(44, 0)
    }
    await page.getByTestId('default-template-reset-cancel').click()
    const phoneCard = page.locator('.template-card[title="Mobile Phone"]').first()
    const dataTransfer = await page.evaluateHandle(() => new DataTransfer())
    await phoneCard.dispatchEvent('dragstart', { dataTransfer })
    const canvas = page.getByTestId('canvas-board')
    const canvasBox = (await canvas.boundingBox())!
    const drop = { dataTransfer, clientX: canvasBox.x + canvasBox.width / 2, clientY: canvasBox.y + canvasBox.height / 2 }
    await canvas.dispatchEvent('dragover', drop)
    await canvas.dispatchEvent('drop', drop)
    await expectHeaderAligned(page.getByTestId('template-instance-dialog').locator('.iot-dialog__header'))
    await expectFooterUsable(page.getByTestId('template-instance-dialog').locator('.iot-dialog__footer'))
    await page.getByTestId('template-instance-runtime-toggle').click()
    await expectVariableHeading(page.getByTestId('template-instance-variable-steps'))
    await page.getByTestId('template-instance-dialog').getByRole('button', { name: copy.cancel, exact: true }).click()

    await openDevice('editor_phone', 'Imported phone')
    await expectHeaderAligned(page.getByTestId('device-dialog').locator('.iot-dialog__header'))
    const deviceFooter = page.getByTestId('device-dialog').locator('.iot-dialog__footer')
    await expectFooterUsable(deviceFooter)
    await expect.soft(deviceFooter.locator('button').last(), 'device details finish with Close')
      .toHaveAttribute('data-testid', 'device-dialog-footer-close')
    await page.getByTestId('device-delete').click()
    const deleteDevice = page.getByRole('dialog', { name: chinese ? '确认删除设备' : 'Delete Device', exact: true })
    await expectFooterUsable(deleteDevice.locator('.iot-dialog__footer'))
    await expect(deleteDevice.locator('.iot-dialog-btn--danger')).toBeEnabled()
    await deleteDevice.getByRole('button', { name: copy.cancel, exact: true }).click()
    await expect(page.getByTestId('device-dialog')).toBeVisible()
    await expect(page.getByTestId('device-dialog')).toHaveCSS('opacity', '1')
    await expect(page.getByTestId('device-dialog')).toHaveCSS('transform', 'none')
    const runtime = page.getByTestId('device-instance-runtime')
    await runtime.evaluate(element => {
      const body = element.closest('.iot-dialog__body')!
      const label = element.querySelector('[data-testid="device-runtime-state"]')!.closest('label')!
      body.scrollTop += label.getBoundingClientRect().top - body.getBoundingClientRect().top - 8
    })
    await expect.poll(() => runtime.evaluate(element => {
      const header = element.querySelector('.device-runtime-header')!.getBoundingClientRect()
      const label = element.querySelector('[data-testid="device-runtime-state"]')!.closest('label')!.getBoundingClientRect()
      return header.bottom <= label.top
    }), { message: 'The instance heading and Save action must not cover the initial-state field while scrolling.' }).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('device-runtime-scroll.png') })
    await expectVariableHeading(page.getByTestId('device-runtime-variable-steps'))
    await page.screenshot({ path: testInfo.outputPath('device-variable-heading.png') })
    await page.getByTestId('device-dialog-close').click()

    await openDevice()
    await page.getByTestId('device-rename').click()
    const rename = page.getByRole('dialog', { name: copy.rename, exact: true })
    await expectHeaderAligned(rename.locator('.iot-dialog__header'))
    await expectFooterUsable(rename.locator('.iot-dialog__footer'))
    const name = rename.getByRole('textbox', { name: copy.name, exact: true })
    await expect(name).toHaveValue('Living-room Air Conditioner')
    await name.fill('Living-room Temperature Sensor')
    await expect(name).toHaveAttribute('aria-invalid', 'true')
    await expect(rename.getByTestId('rename-device-name-hint')).toHaveText(copy.duplicate)
    await expect(rename.getByRole('button', { name: copy.confirm, exact: true })).toBeDisabled()
    await expectFits(rename)
    await name.fill('Reviewed AC')
    const renamed = page.waitForResponse(response => response.request().method() === 'PATCH'
      && new URL(response.url()).pathname === '/api/board/nodes/label')
    await name.press('Enter')
    expect((await renamed).ok()).toBe(true)
    await expect(rename).toHaveCount(0)
    await page.locator('[data-node-id="ac_1"]').click()
    await expect(page.getByTestId('device-delete')).toHaveCSS('height', '44px')
    const state = page.getByTestId('device-runtime-state')
    await state.selectOption('off')
    await page.getByTestId('device-dialog-close').click()
    const discard = page.getByRole('dialog', { name: copy.discardTitle, exact: true })
    await expectFits(discard)
    await expectFooterUsable(discard.locator('.el-message-box__btns'))
    const close = discard.getByRole('button', { name: chinese ? '关闭此对话框' : 'Close this dialog', exact: true })
    await expect(close).toBeVisible()
    expect(await close.evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThanOrEqual(44)
    expect(await close.locator('svg').evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThan(0)
    await discard.getByRole('button', { name: copy.cancel, exact: true }).click()
    await expect(state).toHaveValue('off')
    await page.getByTestId('device-dialog-close').click()
    await page.getByRole('dialog', { name: copy.discardTitle, exact: true }).getByRole('button', { name: copy.discard, exact: true }).click()
    await expect(page.getByTestId('device-dialog')).toHaveCount(0)

    await openEditor('devices')
    await control.getByRole('textbox', { name: copy.name, exact: true }).fill('Extra AC')
    await expect(page.getByTestId('single-device-create')).toBeDisabled()
    await control.getByLabel(copy.template, { exact: true }).selectOption('Mobile Phone')
    await page.getByTestId('single-device-runtime-toggle').click()
    await expectVariableHeading(page.getByTestId('single-device-variable-steps'))
    await control.getByLabel(copy.template, { exact: true }).selectOption('Air Conditioner')
    const created = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/board/nodes')
    await page.getByTestId('single-device-create').click()
    expect((await created).ok()).toBe(true)
    const current = await request.get(`${apiBaseURL}/api/board/snapshot`, { headers })
    expect(current.ok()).toBe(true)
    const extra = (await current.json()).data.nodes.find((node: { label: string }) => node.label === 'Extra AC')
    expect(extra).toBeDefined()
    await openEditor('rules')
    const ruleNameGap = await page.locator('#rule-builder-name').evaluate(input =>
      input.getBoundingClientRect().top - input.previousElementSibling!.getBoundingClientRect().bottom)
    expect.soft(ruleNameGap, 'rule name uses the same label spacing as other rule fields').toBeGreaterThanOrEqual(5.5)
    await expect(page.locator('#rule-builder-name')).toHaveAttribute('placeholder', chinese ? '留空自动生成' : 'Leave blank to generate a name')
    await page.locator('#rule-source-device').selectOption('temperature_1')
    await page.locator('#rule-source-type').selectOption('variable')
    await page.locator('#rule-source-variable').selectOption('temperature')
    await expect(control.getByLabel(copy.comparison, { exact: true })).toBeVisible()
    await page.locator('#rule-source-value').fill('1.5')
    await expect(page.locator('#rule-source-value')).toHaveAttribute('aria-invalid', 'true')
    await expect(page.getByTestId('rule-add-source')).toBeDisabled()
    await page.locator('#rule-source-condition').selectOption('in')
    await expect(page.locator('#rule-source-value')).toHaveAttribute('placeholder', /逗号|commas/)
    await page.locator('#rule-source-value').fill('0, 100')
    await page.getByTestId('rule-add-source').click()
    await page.locator('#rule-target-device').selectOption(extra.id)
    await page.locator('#rule-target-action').selectOption('off')
    await expect(page.getByTestId('rule-save')).toBeEnabled()
    expect(await control.getByTestId('rule-builder').evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    const ruled = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/board/rules')
    await page.getByTestId('rule-save').click()
    expect((await ruled).ok()).toBe(true)

    await openEditor('specs')
    await page.getByTestId('spec-template-select').selectOption('3')
    await page.getByTestId('spec-add-condition-a').click()
    const condition = page.getByTestId('spec-condition-dialog')
    await expectHeaderAligned(condition.locator('.iot-dialog__header'))
    await expectFooterUsable(condition.locator('.iot-dialog__footer'))
    await page.getByTestId('spec-condition-device').selectOption('temperature_1')
    await page.getByTestId('spec-condition-type').selectOption('variable')
    await page.getByTestId('spec-condition-key').selectOption('temperature')
    await expectConditionActions()
    await page.getByTestId('spec-condition-variable-source-environment').click()
    await page.getByTestId('spec-condition-value').fill('101')
    await expect(page.getByTestId('spec-condition-value')).toHaveAttribute('aria-invalid', 'true')
    await expect(page.getByTestId('spec-condition-save')).toBeDisabled()
    await expectFits(condition)
    await expectConditionActions()
    await page.screenshot({ path: testInfo.outputPath('condition-validation.png') })
    await page.getByTestId('spec-condition-relation').selectOption('in')
    await expect(page.getByTestId('spec-condition-value')).toHaveAttribute('placeholder', /逗号|commas/)
    await page.getByTestId('spec-condition-value').fill('0,100')
    await page.getByTestId('spec-condition-save').click()
    await expect(condition).toHaveCount(0)
    const specified = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/board/specs')
    await page.getByTestId('spec-create').click()
    expect((await specified).ok()).toBe(true)

    const accountEntry = page.getByTestId('open-account-center')
    if (await accountEntry.isVisible()) {
      await clickUnderTooltip(page, accountEntry)
    } else {
      await page.getByRole('button', { name: chinese ? '场景操作' : 'Scene actions', exact: true }).click()
      await page.getByTestId('nav-overflow-account').click()
    }
    await expect(page.getByTestId('account-page')).toBeVisible()
    await page.getByTestId('open-account-delete').click()
    await expectHeaderAligned(page.getByTestId('account-delete-dialog').locator('.iot-dialog__header'))
    const accountFooter = page.getByTestId('account-delete-dialog').locator('.iot-dialog__footer')
    await expectFooterUsable(accountFooter)
    for (const button of await accountFooter.locator('button').all()) {
      expect.soft(await button.evaluate(element => element.getBoundingClientRect().height),
        'account confirmation labels should fit on one line').toBeCloseTo(44, 0)
    }
    await page.getByTestId('account-delete-cancel').click()
    await page.getByTestId('account-logout').click()
    await expectHeaderAligned(page.getByTestId('logout-dialog').locator('.iot-dialog__header'))
    await expectFooterUsable(page.getByTestId('logout-dialog').locator('.iot-dialog__footer'))
    await page.getByTestId('logout-cancel').click()
    await page.getByTestId('account-back').click()
    await expect(page.getByTestId('board-root')).toBeVisible()
  })
}
