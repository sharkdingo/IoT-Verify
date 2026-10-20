import { type Locator } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test } from './support/auth'

for (const theme of ['light', 'dark'] as const) {
  for (const { width, height, panelWidth } of [
    { width: 1440, height: 900, panelWidth: 320 },
    { width: 1440, height: 900, panelWidth: 240 },
    { width: 320, height: 900, panelWidth: 320 },
    { width: 375, height: 500, panelWidth: 320 }
  ]) {
    test(`inspector inventories stay compact and usable at ${width}x${height}, panel ${panelWidth} in ${theme}`, async ({ page, request }, testInfo) => {
      await page.setViewportSize({ width, height })
      const auth = await createAuthenticatedUser(request, { usernamePrefix: 'inventory' })
      const headers = { Authorization: `Bearer ${auth.token}` }
      const write = async (path: string, data: unknown) => {
        const response = await request.post(`${apiBaseURL}/api/board/${path}`, { headers, data })
        expect(response.ok(), await response.text()).toBe(true)
        const body = await response.json()
        expect(body.code, JSON.stringify(body)).toBe(200)
      }
      await write('nodes', { devices: [
        { id: 'inventory_sensor', templateName: 'Temperature Sensor',
          label: 'Hall temperature sensor with an extended readable display name',
          position: { x: 280, y: 200 }, width: 180, height: 140 },
        { id: 'inventory_light', templateName: 'Light', label: 'Study light', state: 'off',
          position: { x: 500, y: 200 }, width: 180, height: 140 }
      ], environmentVariablePatches: [{ name: 'temperature', value: '20', trust: 'trusted', privacy: 'public' }] })
      for (const [index, value] of ['25', '30'].entries()) {
        await write('rules', {
          ruleString: `Keep the study light on when the hallway temperature is below limit ${value}`,
          conditions: [
            { deviceName: 'inventory_sensor', targetType: 'variable', attribute: 'temperature', relation: '<', value },
            ...(index ? [] : [{ deviceName: 'inventory_sensor', targetType: 'variable', attribute: 'temperature', relation: '>=', value: '0' }])
          ],
          command: { deviceName: 'inventory_light', action: index ? 'off' : 'on' }
        })
      }
      await write('specs', { id: 'inventory_spec', templateId: '1',
        aConditions: [{ deviceId: 'inventory_sensor', targetType: 'variable', variableSource: 'environment',
          key: 'temperature', relation: '<=', value: '100' }], ifConditions: [], thenConditions: [] })
      if (panelWidth === 240) {
        await write('layout', { canvasPan: { x: 0, y: 0 }, canvasZoom: 1, panels: {
          control: { collapsed: false, width: panelWidth, activeSection: 'templates' },
          inspector: { collapsed: false, width: panelWidth, activeSection: 'devices' }
        } })
      }
      await page.addInitScript(({ auth, theme, locale }) => {
        localStorage.setItem('iot_verify_token', auth.token)
        localStorage.setItem('iot_verify_user', JSON.stringify(auth))
        localStorage.setItem('iot_verify_theme', theme)
        localStorage.setItem('locale', locale)
      }, { auth, theme, locale: width < 640 ? 'zh-CN' : 'en' })
      const layoutReady = page.waitForResponse(response =>
        response.request().method() === 'GET' && new URL(response.url()).pathname === '/api/board/layout')
      await page.goto('/#/board')
      expect((await layoutReady).ok()).toBe(true)
      const inspector = page.getByTestId('system-inspector')
      await expect(page.getByTestId('board-root')).toBeVisible()
      const expand = inspector.locator('.board-panel-toggle[aria-expanded="false"]')
      if (await expand.isVisible()) await expand.click()
      await page.evaluate(() => document.fonts.ready.then(() => undefined))
      await expect.poll(() => inspector.evaluate(element => element.getAnimations({ subtree: true })
        .every(animation => animation.playState !== 'running'
          || !Number.isFinite(animation.effect!.getComputedTiming().iterations))),
      { message: 'measure the panel after its entrance animation' }).toBe(true)
      const dock = page.getByTestId('inspector-overview-dock')
      const map = page.getByTestId('canvas-map')
      const body = inspector.locator('.board-panel-body')
      await expect(dock).toBeVisible()
      await expect(page.getByTestId('canvas-map-toggle')).toHaveAttribute('aria-label', width < 640 ? '画布概览' : 'Canvas overview')
      await expect(map).toHaveJSProperty('open', height >= 600)
      expect(await map.evaluate(element => element.closest('.board-panel-body'))).toBeNull()
      const dockTop = (await dock.boundingBox())!.y
      expect((await dock.boundingBox())!.y + (await dock.boundingBox())!.height)
        .toBeCloseTo((await inspector.boundingBox())!.y + (await inspector.boundingBox())!.height, 0)
      const initialBodyHeight = await body.evaluate(element => element.clientHeight)
      const assertMapSpace = async () => {
        const heading = (await page.getByTestId('canvas-map-toggle').boundingBox())!
        const tools = (await page.getByTestId('canvas-map-zoom-controls').boundingBox())!
        const viewport = (await page.getByTestId('canvas-map-viewport').boundingBox())!
        if (panelWidth === 240) {
          expect(tools.y, 'minimum-width panels put zoom controls below the heading')
            .toBeCloseTo(heading.y + heading.height, 0)
        } else {
          expect(tools.y, 'title and zoom controls share one row').toBeCloseTo(heading.y, 0)
          expect(heading.x + heading.width).toBeLessThanOrEqual(tools.x)
        }
        const toggle = page.getByTestId('canvas-map-toggle')
        const title = (await toggle.locator('.canvas-map__title').boundingBox())!
        const chevron = (await toggle.locator('.canvas-map__chevron').boundingBox())!
        expect(title.x - heading.x, 'the title has space inside its hover background').toBeGreaterThanOrEqual(6)
        expect(heading.x + heading.width - chevron.x - chevron.width).toBeGreaterThanOrEqual(6)
        expect(title.x + title.width).toBeLessThanOrEqual(chevron.x)
        const restingFace = await toggle.evaluate(element => getComputedStyle(element, '::before').backgroundColor)
        await toggle.hover()
        await expect.poll(() => toggle.evaluate(element => getComputedStyle(element, '::before').backgroundColor))
          .not.toBe(restingFace)
        const face = await toggle.evaluate(element => {
          const box = element.getBoundingClientRect()
          const style = getComputedStyle(element, '::before')
          return { height: box.height - parseFloat(style.top) - parseFloat(style.bottom),
            cornerHit: element.contains(document.elementFromPoint(box.x + 2, box.y + 2)) }
        })
        expect(face.height, 'the heading uses the same quiet face as the zoom controls').toBe(28)
        expect(heading.height).toBeGreaterThanOrEqual(44)
        expect(face.cornerHit, 'the quiet heading keeps the entire target clickable').toBe(true)
        expect(viewport.y).toBeGreaterThanOrEqual(tools.y + tools.height)
        expect(viewport.height, 'the working map dominates its controls').toBeGreaterThanOrEqual(112)
        expect(viewport.height).toBeGreaterThan(heading.height * 2.4)
        expect(await dock.evaluate(element => element.scrollHeight <= element.clientHeight + 1), 'the entire overview fits').toBe(true)
        for (const button of await page.getByTestId('canvas-map-zoom-controls').getByRole('button').all()) {
          const size = await button.boundingBox()
          // Translating rails can leave subpixel subtraction noise in DOMRect dimensions.
          expect(Number(size!.width.toFixed(2))).toBeGreaterThanOrEqual(44)
          expect(Number(size!.height.toFixed(2))).toBeGreaterThanOrEqual(44)
          expect(await button.evaluate(element => {
            const box = element.getBoundingClientRect()
            return element.contains(document.elementFromPoint(box.x + 2, box.y + 2))
          }), 'quiet controls retain their full clickable target').toBe(true)
        }
        await expect(page.getByTestId('canvas-map-fit')).toBeEnabled()
        const canvas = page.locator('.canvas-inner')
        const transform = await canvas.evaluate(element => getComputedStyle(element).transform)
        await page.getByTestId('canvas-map-viewport').click({ position: {
          x: viewport.width / 4, y: viewport.height / 4
        } })
        await expect.poll(() => canvas.evaluate(element => getComputedStyle(element).transform),
          { message: 'map navigation moves the canvas while the inspector is open' }).not.toBe(transform)
      }
      if (height >= 600) await assertMapSpace()
      await page.getByTestId('canvas-map-toggle').focus()
      await page.getByTestId('canvas-map-toggle').press('Enter')
      await expect(map).toHaveJSProperty('open', height < 600)
      if (height >= 600) {
        await expect.poll(() => body.evaluate(element => element.clientHeight)).toBeGreaterThan(initialBodyHeight)
      } else {
        await expect.poll(() => body.evaluate(element => element.clientHeight)).toBeLessThan(initialBodyHeight)
        await assertMapSpace()
      }
      await page.getByTestId('canvas-map-toggle').press('Enter')
      await expect(map).toHaveJSProperty('open', height >= 600)
      await expect(inspector.getByRole('tab')).toHaveCount(4)
      const tabRows = await inspector.getByRole('tab').evaluateAll(elements =>
        elements.map(element => Math.round(element.getBoundingClientRect().top)))
      expect(new Set(tabRows).size, 'the four inspector tabs share one row').toBe(1)
      if (width === 1440) {
        const controlRows = await page.getByTestId('control-center').getByRole('tab').evaluateAll(elements =>
          elements.map(element => Math.round(element.getBoundingClientRect().top)))
        expect(controlRows).toHaveLength(4)
        expect(new Set(controlRows).size, 'the four control tabs share one row').toBe(1)
        for (const label of await page.getByTestId('control-center').locator('.board-section-tab__label').all()) {
          expect(await label.evaluate(element => element.clientHeight <= parseFloat(getComputedStyle(element).lineHeight) + 1),
            'default panel labels fit without breaking words').toBe(true)
        }
        const repository = page.getByTestId('control-center').getByTestId('control-template-repository')
        const format = page.getByTestId('control-template-create').getByRole('button', { name: 'Download format definition', exact: true })
        await format.scrollIntoViewIfNeeded()
        expect(await format.evaluate(element => {
          const box = element.getBoundingClientRect()
          return document.elementFromPoint(box.right - 2, box.top + box.height / 2)?.closest('button') === element
        }), 'format help must not cover the download action').toBe(true)
        await repository.getByTestId('reset-default-templates').scrollIntoViewIfNeeded()
        const controls = await repository.evaluate(element => {
          const box = element.getBoundingClientRect()
          return [...element.querySelectorAll('[data-testid="reset-default-templates"], .board-chip-neutral')]
            .map(control => {
              const bounds = control.getBoundingClientRect()
              return { leftInset: bounds.left - box.left, rightInset: box.right - bounds.right }
            })
        })
        expect(controls).toHaveLength(2)
        for (const control of controls) {
          expect(control.leftInset, 'template actions stay inside their card').toBeGreaterThanOrEqual(0)
          expect(control.rightInset, 'template actions and count are not clipped').toBeGreaterThanOrEqual(0)
        }
      }
      for (const label of await inspector.locator('.board-section-tab__label').all()) {
        expect(await label.evaluate(element => element.scrollWidth <= element.clientWidth + 1), 'tab names stay readable').toBe(true)
      }
      for (const panel of width === 1440 ? [page.getByTestId('control-center'), inspector] : [inspector]) {
        for (const tab of await panel.getByRole('tab').all()) {
          const box = (await tab.boundingBox())!
          const label = (await tab.locator('.board-section-tab__label').boundingBox())!
          expect(box.width).toBeGreaterThanOrEqual(44)
          expect(box.height).toBeGreaterThanOrEqual(44)
          expect(label.x - box.x, 'tab text stays inset from its selected or hover face').toBeGreaterThanOrEqual(4)
          expect(box.x + box.width - label.x - label.width).toBeGreaterThanOrEqual(4)
          await expect(tab.locator('.board-section-tab__label')).toHaveCSS('font-size', '13px')
        }
      }
      await expect(page.getByTestId('environment-pool')).toHaveCount(0)
      const device = inspector.locator('[data-device-id="inventory_sensor"]')
      await expect(device).toContainText('extended readable display name')
      const compactAction = async (button: Locator) => {
        await button.scrollIntoViewIfNeeded()
        const metrics = await button.evaluate(element => {
          const box = element.getBoundingClientRect()
          const face = getComputedStyle(element, '::before')
          return { width: box.width, height: box.height, faceWidth: box.width - 2 * parseFloat(face.left),
            cornerHit: element.contains(document.elementFromPoint(box.x + 2, box.y + 2)) }
        })
        expect(metrics.width).toBeGreaterThanOrEqual(44)
        expect(metrics.height).toBeGreaterThanOrEqual(44)
        expect(metrics.faceWidth).toBeLessThanOrEqual(30)
        expect(metrics.cornerHit, 'the quiet face keeps the entire target clickable').toBe(true)
      }
      await compactAction(device.locator('.board-panel-icon-action'))
      await page.mouse.move(0, 0)
      await expect.poll(() => device.evaluate(element => !element.matches(':hover')
        && element.getAnimations().every(animation => animation.playState !== 'running')),
      { message: 'sample the resting card after moving the pointer away' }).toBe(true)
      const restBorder = await device.evaluate(element => getComputedStyle(element).borderColor)
      await device.hover()
      await expect.poll(() => device.evaluate(element => getComputedStyle(element).borderColor)).not.toBe(restBorder)
      await page.mouse.move(0, 0)
      await page.screenshot({ path: testInfo.outputPath('devices.png') })

      await page.getByTestId('inspector-tab-rules').click()
      await expect(inspector.getByRole('tabpanel').locator('h3')).toBeInViewport({ ratio: 1 })
      expect((await inspector.getByRole('tabpanel').boundingBox())!.y -
        (await inspector.locator('.board-panel-tabs').boundingBox())!.y -
        (await inspector.locator('.board-panel-tabs').boundingBox())!.height).toBeLessThan(17)
      const rules = inspector.locator('[data-rule-id]')
      await expect(rules).toHaveCount(2)
      await expect(rules.first().locator('h4')).toContainText('limit 25')
      expect(await rules.first().locator('h4').evaluate(element => element.scrollHeight <= element.clientHeight + 1)).toBe(true)
      await expect(rules.first().locator('dl')).toContainText('25')
      await expect(rules.first().getByTestId('inspector-rule-conditions').locator('li')).toHaveCount(2)
      await expect(rules.first().getByTestId('inspector-rule-command')).toContainText('Study light')
      await compactAction(rules.first().locator('.board-panel-icon-action').last())
      expect((await dock.boundingBox())!.y).toBeCloseTo(dockTop, 0)
      const orderingBox = (await rules.first().getByRole('group').boundingBox())!
      const deleteBox = (await rules.first().locator('.board-panel-icon-action--danger').boundingBox())!
      expect(orderingBox.x + orderingBox.width).toBeLessThan(deleteBox.x)
      const unavailable = rules.first().locator('.board-panel-icon-action').first()
      await expect(unavailable).toBeDisabled()
      await expect(unavailable).toHaveCSS('opacity', '1')
      const disabledFace = await unavailable.evaluate(element => getComputedStyle(element, '::before').backgroundColor)
      await unavailable.hover()
      await expect.poll(() => unavailable.evaluate(element => getComputedStyle(element, '::before').backgroundColor)).toBe(disabledFace)
      await page.mouse.move(0, 0)
      await page.screenshot({ path: testInfo.outputPath('rules.png') })

      await page.getByTestId('inspector-tab-specs').click()
      const spec = inspector.locator('[data-spec-id="inventory_spec"]')
      await expect(spec.getByTestId('inspector-spec-description')).toContainText('100')
      await spec.getByTestId('inspector-spec-formula-details').locator('summary').click()
      await expect(spec.getByTestId('inspector-spec-formula')).toBeVisible()
      await compactAction(spec.locator('.board-panel-icon-action'))
      await page.screenshot({ path: testInfo.outputPath('specs.png') })

      await page.getByTestId('inspector-tab-environment').click()
      const pool = page.getByTestId('environment-pool')
      await expect(pool).toBeVisible()
      await expect(pool.locator('[data-environment-name]')).toHaveCount(2)
      await pool.locator('[data-environment-name="temperature"] .board-environment-summary').click()
      const field = page.getByTestId('environment-value-temperature')
      await expect(field).toHaveValue('20')
      await field.fill('101')
      await expect(page.getByTestId('environment-value-failure-temperature')).toBeVisible()
      await expect(field).toHaveAttribute('aria-invalid', 'true')
      await page.screenshot({ path: testInfo.outputPath('environment-error.png') })
      await field.fill('20')
      await expect(page.getByTestId('environment-value-failure-temperature')).toHaveCount(0)
      await pool.locator('[data-environment-name="temperature"] .board-environment-summary').click()
      await page.screenshot({ path: testInfo.outputPath('environment.png') })
      if (width === 1440) {
        await expect.poll(async () => {
          const response = await request.get(`${apiBaseURL}/api/board/layout`, { headers })
          const body = await response.json()
          return body.data?.panels?.inspector?.activeSection
        }).toBe('environment')
        await page.reload()
        if (await expand.isVisible()) await expand.click()
        await expect(page.getByTestId('inspector-tab-environment')).toHaveAttribute('aria-selected', 'true')
      } else {
        const response = await request.get(`${apiBaseURL}/api/board/layout`, { headers })
        const body = await response.json()
        expect(body.data?.panels?.inspector?.activeSection).toBe('devices')
        await expect(page.getByTestId('inspector-tab-environment')).toHaveAttribute('aria-selected', 'true')
      }
      expect(await inspector.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
      expect(await page.locator('html').evaluate(element => element.scrollWidth <= innerWidth)).toBe(true)
    })
  }
}
