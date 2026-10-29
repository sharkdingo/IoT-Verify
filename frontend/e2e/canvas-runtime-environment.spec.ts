import { type APIRequestContext, type Page } from '@playwright/test'
import type { BoardEditHistoryClearPreview, BoardSemanticSnapshot } from '../src/api/board'
import { expectPlaybackControlSpacing } from './support/playback'
import {
  apiBaseURL,
  createAuthenticatedUser,
  expect,
  test,
  type AuthUser
} from './support/auth'

const authHeaders = (auth: AuthUser) => ({
  Authorization: `Bearer ${auth.token}`
})

const unwrap = async <T>(response: Awaited<ReturnType<APIRequestContext['get']>>): Promise<T> => {
  expect(response.ok(), await response.text()).toBeTruthy()
  const body = await response.json()
  expect(body.code, JSON.stringify(body)).toBe(200)
  return body.data as T
}

// SVG attributes retain model precision; CSS transforms round fitted mobile zoom values.
const readCanvasZoom = (element: Element) => Number(element.closest('.canvas')!
  .querySelector('[data-testid="canvas-edge-world"]')!.getAttribute('transform')!
  .match(/^scale\(([^)]+)\)$/)![1])

const openWorkspace = async (page: Page, auth: AuthUser, theme: 'light' | 'dark' = 'light') => {
  await page.addInitScript(({ token, user, theme }) => {
    window.localStorage.setItem('iot_verify_token', token)
    window.localStorage.setItem('iot_verify_user', JSON.stringify(user))
    window.localStorage.setItem('iot_verify_theme', theme)
    window.localStorage.setItem('locale', 'en')
  }, {
    token: auth.token,
    theme,
    user: {
      userId: auth.userId,
      phone: auth.phone,
      username: auth.username
    }
  })

  await page.goto('/#/board')
  await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
}

const addNodes = async (
  request: APIRequestContext,
  auth: AuthUser,
  devices: Record<string, unknown>[],
  environmentVariablePatches: Record<string, unknown>[] = []
) => unwrap(await request.post(`${apiBaseURL}/api/board/nodes`, {
  headers: authHeaders(auth),
  data: { devices, environmentVariablePatches }
}))

for (const theme of ['light', 'dark'] as const) {
  for (const width of [375, 1280]) {
    test(`long playback keeps navigation and evidence readable at ${width}px in ${theme}`, async ({ page, request }, testInfo) => {
      await page.setViewportSize({ width, height: 900 })
      const auth = await createAuthenticatedUser(request)
      await addNodes(request, auth, [{
        id: 'long_light', templateName: 'Light', label: 'Study light', state: 'off',
        position: { x: 160, y: 180 }, width: 180, height: 140
      }, {
        id: 'long_sensor', templateName: 'Temperature Sensor', label: 'Hall sensor',
        position: { x: 480, y: 180 }, width: 180, height: 140
      }])
      await unwrap(await request.post(`${apiBaseURL}/api/board/rules`, {
        headers: authHeaders(auth), data: {
          ruleString: 'Turn on the study light',
          conditions: [{ deviceName: 'long_light', targetType: 'state', attribute: 'state', relation: '=', value: 'off' }],
          command: { deviceName: 'long_light', action: 'on' }
        }
      }))
      await openWorkspace(page, auth, theme)
      const restoreDock = page.getByTestId('restore-action-dock')
      if (await restoreDock.isVisible()) await restoreDock.click()
      await page.getByTestId('open-simulation-panel').click()
      await page.getByTestId('simulation-steps-input').fill('100')
      await page.getByTestId('run-simulation').click()
      const timeline = page.getByTestId('simulation-timeline')
      await expect(timeline).toBeVisible({ timeout: 60_000 })
      const input = page.getByTestId('simulation-timeline-step-input')
      await expect(input).toHaveAttribute('max', '101')
      const replayStatus = await page.getByTestId('open-simulation-panel').evaluate(button => {
        const wrapper = button.closest('.board-tool-wrapper')!
        const status = wrapper.querySelector('.board-tool-status')
        if (!status) return null
        const bounds = wrapper.getBoundingClientRect()
        const paint = status.getBoundingClientRect()
        const style = getComputedStyle(status)
        return {
          contained: paint.left >= bounds.left && paint.right <= bounds.right
            && paint.top >= bounds.top && paint.bottom <= bounds.bottom,
          animation: style.animationName,
          pointerEvents: style.pointerEvents
        }
      })
      expect(replayStatus).toEqual({ contained: true, animation: 'none', pointerEvents: 'none' })
      await expectPlaybackControlSpacing(page, 'simulation-timeline')
      const track = page.getByTestId('simulation-timeline-track')
      expect(await track.getByRole('button').count()).toBeLessThanOrEqual(32)
      await track.locator('[aria-current="step"]').press('End')
      await expect(timeline).toHaveAttribute('data-selected-state-index', '100')
      await expect(page.getByTestId('simulation-timeline-state-100')).toBeFocused()
      await expect(page.getByTestId('simulation-timeline-window-label')).toContainText('101 / 101')
      await page.getByTestId('simulation-timeline-previous-window').click()
      const previousIndex = Number(await timeline.getAttribute('data-selected-state-index'))
      expect(previousIndex).toBeLessThan(100)
      await page.getByTestId('simulation-timeline-next-window').click()
      expect(Number(await timeline.getAttribute('data-selected-state-index'))).toBeGreaterThan(previousIndex)
      await track.locator('[aria-current="step"]').press('Home')
      await expect(timeline).toHaveAttribute('data-selected-state-index', '0')
      await input.fill('97')
      await expect(timeline).toHaveAttribute('data-selected-state-index', '0')
      await input.press('Enter')
      await expect(page.getByTestId('simulation-timeline-state-96')).toHaveAttribute('aria-current', 'step')
      expect(await track.getByRole('button').count()).toBeLessThanOrEqual(32)
      await expect(page.getByTestId('simulation-timeline-scroll')).toBeVisible()
      await expect(page.getByTestId('simulation-timeline-triggered-rules')).toHaveCount(0)
      const timelineHeight = await timeline.evaluate(element => element.getBoundingClientRect().height)
      const canvasHeight = await page.locator('.canvas-container').evaluate(element => element.clientHeight)
      const canvasTransform = await page.locator('.canvas-inner').evaluate(element => getComputedStyle(element).transform)
      await input.fill('2')
      await page.getByTestId('simulation-timeline-changes-tab').click()
      await expect(page.getByTestId('playback-change-devices')).toContainText('Study light')
      await expect(page.getByTestId('playback-change-automation')).toContainText('Turn on the study light')
      expect(await timeline.evaluate(element => element.getBoundingClientRect().height)).toBe(timelineHeight)
      expect(await page.locator('.canvas-container').evaluate(element => element.clientHeight)).toBe(canvasHeight)
      expect(await page.locator('.canvas-inner').evaluate(element => getComputedStyle(element).transform)).toBe(canvasTransform)
      await page.getByTestId('simulation-timeline-details-tab').click()
      await expect(page.getByTestId('playback-state-devices')).toContainText('Hall sensor')
      await expect(page.getByTestId('playback-state-environment')).toContainText(/temperature/i)
      await expect(page.getByTestId('playback-state-details').getByText('Actual checked expression')).toHaveCount(0)
      await expect(page.getByTestId('simulation-timeline-hide-panel')).toBeVisible()
      await expectPlaybackControlSpacing(page, 'simulation-timeline')
      expect(await timeline.evaluate(element => element.getBoundingClientRect().height)).toBe(timelineHeight)
      expect(await page.locator('.canvas-container').evaluate(element => element.clientHeight)).toBe(canvasHeight)
      expect(await page.locator('.canvas-inner').evaluate(element => getComputedStyle(element).transform)).toBe(canvasTransform)
      await page.screenshot({ path: testInfo.outputPath(`playback-state-evidence-${theme}-${width}.png`) })
      await input.fill('1')
      await page.getByTestId('simulation-timeline-play').click()
      const runUrl = page.url()
      await page.getByTestId('simulation-timeline-hide-panel').click()
      await expect(timeline).toBeHidden()
      await expect(page.getByTestId('playback-panel-toggle')).toBeFocused()
      await expect(page.getByTestId('simulation-timeline-play')).toHaveAttribute('aria-pressed', 'true')
      await expect.poll(async () => Number(await timeline.getAttribute('data-selected-state-index'))).toBeGreaterThan(0)
      expect(page.url()).toBe(runUrl)
      await page.getByTestId('playback-panel-toggle').click()
      await expect(page.getByTestId('simulation-timeline-details-tab')).toBeFocused()
      await expect(page.getByTestId('simulation-timeline-details-tab')).toHaveAttribute('aria-selected', 'true')
      await expect(page.getByTestId('simulation-timeline-play')).toHaveAttribute('aria-pressed', 'true')
      await page.getByTestId('simulation-timeline-play').click()
      await input.fill('1')
      await page.getByTestId('simulation-timeline-run-tab').click()
      await expectPlaybackControlSpacing(page, 'simulation-timeline')
      await page.setViewportSize({ width: 1440, height: 900 })
      await expect.poll(() => track.getByRole('button').count()).toBeGreaterThan(3)
      const wideMarkerCount = await track.getByRole('button').count()
      await page.setViewportSize({ width: 320, height: 812 })
      await page.getByTestId('simulation-timeline-hide-panel').click()
      await expect.poll(() => track.getByRole('button').count(), {
        message: 'Resizing a long path must reduce the marker window to the available corridor.'
      }).toBeLessThan(wideMarkerCount)
      await expect.poll(() => page.getByTestId('playback-panel-toggle').evaluate(button => {
        const box = button.getBoundingClientRect()
        const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
        return hit === button || button.contains(hit)
      }), { message: 'Timeline markers must not intercept Restore on a narrow screen.' }).toBe(true)
      await page.getByTestId('playback-panel-toggle').click()
      await expect(timeline).toBeVisible()
      await expect(timeline).toHaveAttribute('data-selected-state-index', '0')
      await page.getByTestId('simulation-timeline-close').click()
      await expect(timeline).toHaveCount(0)
    })
  }
}

for (const theme of ['light', 'dark'] as const) {
  test(`device cards keep names and readings readable across shapes and zoom in ${theme}`, async ({ context, request }, testInfo) => {
    const auth = await createAuthenticatedUser(request)
    const cases = [
      { width: 600, height: 350, zoom: 0.2, label: 'Air Purifier' },
      { width: 176, height: 128, zoom: 1, label: 'Living room temperature sensor' },
      { width: 176, height: 128, zoom: 1.5, label: 'Study room temperature sensor' },
      { width: 100, height: 72, zoom: 1, label: '走廊灯' },
      { width: 80, height: 60, zoom: 1, label: '台灯' },
      { width: 424, height: 318, zoom: 0.2, label: 'Bed Occupancy Sensor' },
      { width: 176, height: 112, zoom: 0.4, label: 'Emergency Alarm' },
      // At 56×41 screen pixels, keep readable prefixes and the full name in the tooltip and ARIA label.
      { width: 176, height: 128, zoom: 0.32, label: 'Front Door Lock', clampedName: true },
      { width: 176, height: 128, zoom: 0.32, label: 'Electric Window', clampedName: true },
      { width: 80, height: 60, zoom: 0.4, label: 'Lamp', mobile: true },
      { width: 80, height: 60, zoom: 0.2, label: 'Study lamp', micro: true },
      { width: 80, height: 220, zoom: 0.2, label: 'Narrow overview light', micro: true },
      // At 84×12 screen pixels, artwork and a full-name tooltip remain readable.
      { width: 420, height: 60, zoom: 0.2, label: 'Entry light', micro: true },
      { width: 800, height: 120, zoom: 0.5, label: 'Wide hallway light' },
      { width: 800, height: 80, zoom: 0.5, label: 'Wide entry hallway light' },
      { width: 120, height: 650, zoom: 1, label: 'Tall hallway light' },
      { width: 2000, height: 2000, zoom: 0.25, label: 'Bedroom water heater', readings: true },
      { width: 2000, height: 2000, zoom: 0.5, label: 'Large bedroom water heater', readings: true }
    ]
    const devices = cases.map((item, index) => ({
      id: `legible_${index}`, templateName: item.readings ? 'Water Heater' : 'Light',
      label: item.label, width: item.width, height: item.height,
      position: { x: 100 + index * 2600, y: 140 }, state: 'off',
      ...(item.readings ? {
        variables: [{ name: 'waterTemperature', value: '26', trust: 'trusted' }],
        privacies: [{ name: 'waterTemperature', privacy: 'private' }]
      } : {})
    }))
    await addNodes(request, auth, devices)
    for (const [index, item] of cases.entries()) {
      // A fresh page cannot autosave the previous case over the next seeded layout.
      const page = await context.newPage()
      try {
        const caseAuth = item.mobile ? await createAuthenticatedUser(request) : auth
        const source = item.mobile
          ? { ...devices[index], position: { x: -item.width / 2, y: -item.height / 2 } }
          : devices[index]
        if (item.mobile) await addNodes(request, caseAuth, [source])
        const viewport = item.mobile ? { width: 375, height: 812 } : { width: 1280, height: 900 }
        await page.setViewportSize(viewport)
        await unwrap(await request.post(`${apiBaseURL}/api/board/layout`, {
          headers: authHeaders(caseAuth), data: {
            canvasZoom: item.zoom,
            canvasPan: {
              x: viewport.width / 2 - (source.position.x + item.width / 2) * item.zoom,
              y: (viewport.height + 69) / 2 - (source.position.y + item.height / 2) * item.zoom
            },
            panels: {
              control: { collapsed: true, width: 320, activeSection: 'templates' },
              inspector: { collapsed: true, width: 320, activeSection: 'devices' }
            }
          }
        }))
        await openWorkspace(page, caseAuth, theme)
        const node = page.locator(`[data-node-id="legible_${index}"]`)
        await expect(node).toBeVisible({ timeout: 30_000 })
        await expect(node).toHaveAttribute('aria-label', new RegExp(item.label))
        await expect(node).toHaveAttribute('title', new RegExp(item.label))
        if (item.mobile) {
          // Phones open a fitted overview. Set the inspection scale through its visible control.
          const inspector = page.getByTestId('system-inspector')
          await inspector.getByRole('button', { name: 'Expand System Inspector', exact: true }).click()
          await page.getByTestId('canvas-map-zoom-input').fill(String(item.zoom * 100))
          await inspector.getByRole('button', { name: 'Collapse System Inspector', exact: true }).click()
        }
        await expect.poll(() => node.evaluate(readCanvasZoom)).toBe(item.zoom)
        const name = node.locator('.device-label')
        if (item.micro) {
          await expect(name).toBeHidden()
          await expect(node.locator('.device-img')).toBeVisible()
        } else {
          await expect(name).toBeVisible()
          if (item.clampedName) {
            await expect(name).toHaveText(item.label)
            await expect(name).toHaveAttribute('title', item.label)
            await expect(name).toHaveCSS('text-overflow', 'ellipsis')
            await expect(name).toHaveCSS('-webkit-line-clamp', '2')
          } else {
            const textFits = await name.evaluate(label =>
              label.scrollWidth <= label.clientWidth + 1 && label.scrollHeight <= label.clientHeight + 1)
            expect(textFits, `${item.label} should use the space in its ${item.width}×${item.height} card at ${item.zoom}`).toBe(true)
          }
        }
        if (item.readings) {
          await expect(node.locator('.device-runtime-chip__value')).toContainText('26')
          await expect(node.locator('.device-node-trust')).toHaveCount(2)
          const content = await node.locator('.device-node-content').boundingBox()
          const card = await node.boundingBox()
          expect(content!.width).toBeLessThanOrEqual(480.5)
          expect(Math.abs(content!.x + content!.width / 2 - card!.x - card!.width / 2)).toBeLessThan(1)
          if (item.zoom === 0.5) {
            await page.screenshot({ path: testInfo.outputPath(`large-device-${theme}.png`) })
          }
        }
        const geometry = await node.evaluate(element => {
          const outer = element.getBoundingClientRect()
          const scale = new DOMMatrixReadOnly(element.closest<HTMLElement>('.canvas-inner')!.style.transform).a
          const rect = (selector: string) => element.querySelector(selector)!.getBoundingClientRect()
          const label = rect('.device-label')
          const icon = rect('.device-img')
          const inside = (box: DOMRect) => box.width === 0 || box.height === 0
            || (box.left >= outer.left - 0.5 && box.right <= outer.right + 0.5
              && box.top >= outer.top - 0.5 && box.bottom <= outer.bottom + 0.5)
          const text = [...element.querySelectorAll('.device-label, .device-state-value, .device-runtime-chip, .device-node-trust')]
            .filter(part => part.getBoundingClientRect().width > 0 && part.getBoundingClientRect().height > 0)
          return {
            contained: [label, icon, ...text.map(part => part.getBoundingClientRect())].every(inside),
            separate: icon.width === 0 || label.width === 0 || icon.right <= label.left + 0.5
              || label.right <= icon.left + 0.5 || icon.bottom <= label.top + 0.5 || label.bottom <= icon.top + 0.5,
            readable: text.every(part => Number.parseFloat(getComputedStyle(part).fontSize) * scale >= 10.99),
            readingsFit: text.filter(part => part.classList.contains('device-runtime-chip')
              || part.classList.contains('device-node-trust')).every(part => part.scrollWidth <= part.clientWidth + 1)
          }
        })
        expect(geometry, `${item.label}, ${theme}, zoom ${item.zoom}`).toEqual({
          contained: true, separate: true, readable: true, readingsFit: true
        })
      } finally {
        await page.close()
      }
    }
  })
}

test('overview nodes resize directly without changing canvas zoom and retain their saved size', async ({ context, request }) => {
  const auth = await createAuthenticatedUser(request)
  const shapes = [{ width: 80, height: 60 }, { width: 80, height: 220 },
    { width: 420, height: 60 }, { width: 176, height: 128 }]
  const devices = shapes.map((shape, index) => ({ ...shape, id: `overview_resize_${index}`,
    templateName: 'Light', label: `Overview light ${index + 1}`, state: 'off',
    position: { x: index * 1200, y: 0 } }))
  await addNodes(request, auth, devices)
  for (const device of devices) {
    const page = await context.newPage()
    try {
      await page.setViewportSize({ width: 1280, height: 900 })
      await unwrap(await request.post(`${apiBaseURL}/api/board/layout`, {
        headers: authHeaders(auth), data: { canvasZoom: 0.2,
          canvasPan: { x: 640 - (device.position.x + device.width / 2) * 0.2,
            y: 480 - device.height * 0.1 },
          panels: { control: { collapsed: true, width: 320, activeSection: 'templates' },
            inspector: { collapsed: true, width: 320, activeSection: 'devices' } } }
      }))
      await openWorkspace(page, auth)
      const node = page.locator(`[data-node-id="${device.id}"]`)
      await expect(node).toBeVisible({ timeout: 30_000 })
      const grip = node.locator('.resize-handle.br')
      await expect(grip).toHaveCount(1)
      await node.hover()
      const target = (await grip.boundingBox())!
      expect(target.width).toBeCloseTo(44, 0)
      expect(target.height).toBeCloseTo(44, 0)
      await page.mouse.move(target.x + target.width / 2, target.y + target.height / 2)
      await page.mouse.down()
      await page.mouse.move(target.x + target.width / 2 + 30, target.y + target.height / 2 + 20, { steps: 6 })
      await page.mouse.up()
      const expected = { width: device.width + 150, height: device.height + 100 }
      await expect.poll(async () => {
        const saved = await unwrap<Array<{ id: string; width: number; height: number }>>(
          await request.get(`${apiBaseURL}/api/board/nodes`, { headers: authHeaders(auth) }))
        const item = saved.find(item => item.id === device.id)!
        return { width: item.width, height: item.height }
      }).toEqual(expected)
      await expect.poll(() => node.evaluate(readCanvasZoom)).toBe(0.2)
      await page.reload()
      await expect(node).toBeVisible({ timeout: 30_000 })
      expect(await node.evaluate(element => ({ width: (element as HTMLElement).style.width,
        height: (element as HTMLElement).style.height })))
        .toEqual({ width: `${expected.width}px`, height: `${expected.height}px` })
      await expect.poll(() => node.evaluate(readCanvasZoom)).toBe(0.2)
    } finally { await page.close() }
  }
})

for (const gesture of ['drag', 'resize'] as const) {
  for (const finish of ['release', 'cancel'] as const) {
    test(`held ${gesture} rejects node shortcuts and history edits before ${finish}`, async ({ page, request }) => {
      const auth = await createAuthenticatedUser(request)
      const source = { id: 'owned_light', templateName: 'Light', label: 'Owned light', state: 'off',
        position: { x: 300, y: 200 }, width: 176, height: 128 }
      await addNodes(request, auth, [source])
      await unwrap(await request.post(`${apiBaseURL}/api/board/layout`, {
        headers: authHeaders(auth), data: { canvasZoom: 1, canvasPan: { x: 0, y: 0 },
          panels: { control: { collapsed: true, width: 320, activeSection: 'templates' },
            inspector: { collapsed: true, width: 320, activeSection: 'devices' } } }
      }))
      await page.setViewportSize({ width: 1280, height: 900 })
      await openWorkspace(page, auth)
      const node = page.locator(`[data-node-id="${source.id}"]`)
      await expect(node).toBeVisible({ timeout: 30_000 })
      const readNodes = async () => unwrap<Array<typeof source>>(await request.get(`${apiBaseURL}/api/board/nodes`, {
        headers: authHeaders(auth)
      }))
      const originalNodes = await readNodes()
      const originalHistory = await unwrap(await request.get(`${apiBaseURL}/api/board/edits/availability`, {
        headers: authHeaders(auth)
      }))
      const writes: string[] = []
      page.on('request', request => {
        const path = new URL(request.url()).pathname
        if (request.method() !== 'GET' && (/\/board\/nodes(?:\/|$)/.test(path)
          || /\/board\/edits\/(?:undo|redo)$/.test(path))) writes.push(path)
      })
      await node.focus()
      await node.hover()
      const target = gesture === 'drag' ? node : node.locator('.resize-handle.br')
      const pointer = await target.evaluateHandle(element => {
        const observed = { id: 0 }
        element.addEventListener('pointerdown', event => { observed.id = (event as PointerEvent).pointerId }, { once: true })
        return observed
      })
      let held = false
      let releaseUndoRequest: (() => void) | undefined
      let pendingUndoResponse: ReturnType<Page['waitForResponse']> | undefined
      try {
        const box = (await target.boundingBox())!
        const start = { x: box.x + box.width / 2, y: box.y + box.height / 2 }
        await page.mouse.move(start.x, start.y)
        await page.mouse.down()
        held = true
        await page.mouse.move(start.x + 30, start.y + 15)
        await expect.poll(() => node.evaluate(element => ({ left: (element as HTMLElement).style.left,
          width: (element as HTMLElement).style.width })))
          .toEqual({ left: gesture === 'drag' ? '330px' : '300px', width: gesture === 'resize' ? '206px' : '176px' })
        for (const command of ['ArrowRight', 'Control+ArrowDown', 'Delete', 'Enter', 'Shift+F10',
          'Control+z', 'Control+Shift+z', 'Control+y']) await page.keyboard.press(command)
        await page.mouse.down({ button: 'right' })
        await page.mouse.up({ button: 'right' })
        // Flush the event turn and Vue updates before checking that no command dispatched a mutation.
        await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => resolve())))
        expect(writes).toEqual([])
        expect(await readNodes()).toEqual(originalNodes)
        expect(await unwrap(await request.get(`${apiBaseURL}/api/board/edits/availability`, {
          headers: authHeaders(auth)
        }))).toEqual(originalHistory)
        await expect(page.getByTestId('device-dialog')).toHaveCount(0)
        await expect(page.locator('.board-context-menu')).toHaveCount(0)
        if (finish === 'cancel') {
          // Exercise component cancellation with the actual captured pointer id; this is a synthetic cancel.
          await target.dispatchEvent('pointercancel', { pointerId: await pointer.evaluate(value => value.id) })
        }
        await page.mouse.up()
        held = false
        const released = finish === 'cancel' ? source : { ...source,
          position: gesture === 'drag' ? { x: 330, y: 215 } : source.position,
          width: gesture === 'resize' ? 206 : source.width,
          height: gesture === 'resize' ? 143 : source.height }
        const savedLayout = async () => {
          const saved = (await readNodes()).find(item => item.id === source.id)!
          return { position: saved.position, width: saved.width, height: saved.height }
        }
        await expect.poll(savedLayout).toEqual({ position: released.position, width: released.width, height: released.height })
        expect(writes).toHaveLength(finish === 'release' ? 1 : 0)
        // A new keyboard edit and its undo must work once the pointer relinquishes ownership.
        await node.press('ArrowRight')
        await expect.poll(savedLayout).toEqual({ position: { ...released.position, x: released.position.x + 10 },
          width: released.width, height: released.height })
        let undoIntercepted = false
        const undoGate = new Promise<void>(resolve => { releaseUndoRequest = resolve })
        await page.route('**/api/board/edits/undo', async route => {
          undoIntercepted = true
          await undoGate
          await route.continue()
        })
        await node.press('Control+z')
        await expect.poll(() => undoIntercepted).toBe(true)
        pendingUndoResponse = page.waitForResponse(response => response.url().endsWith('/api/board/edits/undo')
          && response.request().method() === 'POST')
        // Delay the actual undo request, preserving its real backend result. A pending undo must
        // also prevent a new gesture from starting against the scene it is about to replace.
        await expect(node).toHaveAttribute('aria-disabled', 'true')
        await expect(node.locator('.resize-handle')).toHaveCount(0)
        const pendingWrites = [...writes]
        const pendingPosition = await node.evaluate(element => ({ left: (element as HTMLElement).style.left,
          top: (element as HTMLElement).style.top }))
        await node.press('ArrowRight')
        const pendingBox = (await node.boundingBox())!
        await page.mouse.move(pendingBox.x + pendingBox.width / 2, pendingBox.y + pendingBox.height / 2)
        await page.mouse.down()
        held = true
        await page.mouse.move(pendingBox.x + pendingBox.width / 2 + 30, pendingBox.y + pendingBox.height / 2 + 15)
        await page.mouse.up()
        held = false
        await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => resolve())))
        expect(writes).toEqual(pendingWrites)
        expect(await node.evaluate(element => ({ left: (element as HTMLElement).style.left,
          top: (element as HTMLElement).style.top }))).toEqual(pendingPosition)
        releaseUndoRequest!()
        expect((await pendingUndoResponse).ok()).toBe(true)
        await expect.poll(savedLayout).toEqual({ position: released.position, width: released.width, height: released.height })
        await expect(node).not.toHaveAttribute('aria-disabled', 'true')
        await page.reload()
        await expect(node).toBeVisible()
        await expect.poll(() => node.evaluate(element => ({ left: (element as HTMLElement).style.left,
          top: (element as HTMLElement).style.top, width: (element as HTMLElement).style.width,
          height: (element as HTMLElement).style.height })))
          .toEqual({ left: `${released.position.x}px`, top: `${released.position.y}px`,
            width: `${released.width}px`, height: `${released.height}px` })
      } finally {
        if (held) await page.mouse.up()
        releaseUndoRequest?.()
        if (pendingUndoResponse) await pendingUndoResponse
        await pointer.dispose()
      }
    })
  }
}

for (const { viewport, theme, zoom } of [
  { viewport: { width: 1280, height: 720 }, theme: 'light', zoom: 1.25 },
  { viewport: { width: 375, height: 812 }, theme: 'dark', zoom: 0.4 }
] as const) {
  test(`self-loop follows a held drag, persists once and survives undo at ${viewport.width}px in ${theme}`, async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await page.setViewportSize(viewport)
    const source = {
      id: 'loop_light', templateName: 'Light', label: 'Loop light',
      position: { x: 100, y: 140 }, state: 'off', width: 176, height: 128
    }
    await addNodes(request, auth, [source, { ...source,
      id: 'other_light', label: 'Other light', position: { x: 420, y: 200 }
    }])
    for (const targetId of ['loop_light', 'other_light']) {
      await unwrap(await request.post(`${apiBaseURL}/api/board/rules`, {
        headers: authHeaders(auth), data: {
          ruleString: `Light to ${targetId}`,
          conditions: [{ deviceName: source.id, targetType: 'state', attribute: 'state', relation: '=', value: 'off' }],
          command: { deviceName: targetId, action: 'on' }
        }
      }))
    }
    await unwrap(await request.post(`${apiBaseURL}/api/board/layout`, {
      headers: authHeaders(auth), data: {
        canvasPan: { x: 20, y: 30 }, canvasZoom: zoom,
        panels: {
          control: { collapsed: true, width: 320, activeSection: 'templates' },
          inspector: { collapsed: true, width: 320, activeSection: 'devices' }
        }
      }
    }))
    await openWorkspace(page, auth, theme)
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    const node = page.locator('[data-node-id="loop_light"]')
    if (viewport.width < 720) {
      await page.getByTestId('canvas-fit-mobile').click()
      await expect.poll(() => page.getByTestId('canvas-board').evaluate(canvas => {
        const frame = canvas.getBoundingClientRect()
        return [...canvas.querySelectorAll('[data-node-id]')].every(element => {
          const box = element.getBoundingClientRect()
          return box.left >= frame.left && box.top >= frame.top
            && box.right <= frame.right && box.bottom <= frame.bottom
        })
      })).toBe(true)
    }
    const renderedZoom = await node.evaluate(readCanvasZoom)
    expect(renderedZoom).toBeGreaterThan(0)
    const loop = page.locator('path.edge-base-line')
    const hitarea = page.locator('path.edge-hitarea')
    await expect(loop).toBeVisible()
    await hitarea.focus()
    await expect(page.locator('.edge-label')).toBeVisible()
    // At overview zoom the focused rule card can cover the node's drag target.
    await hitarea.press('Escape')
    await expect(page.getByTestId('canvas-edge-details')).toBeHidden()
    const originalPath = await loop.getAttribute('d')
    const numbers = (path: string) => path.match(/-?\d+(?:\.\d+)?(?:e[+-]?\d+)?/gi)!.map(Number)
    const initialCoordinates = numbers(originalPath!)
    const rulesBefore = await unwrap<any[]>(await request.get(`${apiBaseURL}/api/board/rules`, { headers: authHeaders(auth) }))
    const savedPosition = async () => {
      const nodes = await unwrap<Array<typeof source>>(await request.get(`${apiBaseURL}/api/board/nodes`, { headers: authHeaders(auth) }))
      return nodes.find(node => node.id === source.id)!.position
    }
    let layoutWrites = 0
    page.on('request', outgoing => {
      const url = new URL(outgoing.url())
      if (outgoing.method() === 'PUT' && url.pathname === '/api/board/nodes/layout'
        && url.searchParams.get('nodeId') === 'loop_light') layoutWrites++
    })
    const box = await node.boundingBox()
    expect(box).not.toBeNull()
    // Integer pointer coordinates preserve the requested delta in browser events.
    const start = { x: Math.round(box!.x + box!.width / 2), y: Math.round(box!.y + box!.height / 2) }
    await page.mouse.move(start.x, start.y)
    await page.mouse.down()
    try {
      for (const [dx, dy] of [[12, 8], [24, 16]]) {
        await page.mouse.move(start.x + dx, start.y + dy)
        await expect.poll(async () => numbers((await loop.getAttribute('d'))!).map(value => Math.round(value * 1e6))).toEqual(
          initialCoordinates.map((value, index) => Math.round((value + (index % 2 === 0 ? dx : dy) / renderedZoom) * 1e6))
        )
        expect(await hitarea.getAttribute('d')).toBe(await loop.getAttribute('d'))
      }
      // Node dragging takes focus; re-focus the edge to reveal its label at the preview position.
      await hitarea.focus()
      const detail = page.getByTestId('canvas-edge-details')
      await expect(detail).toBeVisible()
      const detailBox = await detail.boundingBox()
      const canvasBox = await page.getByTestId('canvas-board').boundingBox()
      expect(detailBox).not.toBeNull()
      expect(canvasBox).not.toBeNull()
      expect(detailBox!.x).toBeGreaterThanOrEqual(canvasBox!.x)
      expect(detailBox!.y).toBeGreaterThanOrEqual(canvasBox!.y)
      expect(detailBox!.x + detailBox!.width).toBeLessThanOrEqual(canvasBox!.x + canvasBox!.width)
      expect(detailBox!.y + detailBox!.height).toBeLessThanOrEqual(canvasBox!.y + canvasBox!.height)
      expect(await savedPosition()).toEqual(source.position)
      expect(layoutWrites).toBe(0)
    } finally {
      await page.mouse.up()
    }
    await expect.poll(savedPosition).toEqual({ x: source.position.x + 24 / renderedZoom, y: source.position.y + 16 / renderedZoom })
    expect(layoutWrites).toBe(1)
    expect(await unwrap(await request.get(`${apiBaseURL}/api/board/rules`, { headers: authHeaders(auth) }))).toEqual(rulesBefore)
    const finalPath = await loop.getAttribute('d')
    await page.reload()
    await expect(loop).toHaveAttribute('d', finalPath!)
    await page.getByTestId('board-undo').click()
    await expect.poll(savedPosition).toEqual(source.position)
    await expect(loop).toHaveAttribute('d', originalPath!)
  })
}

test('opening canvas node details preserves the viewport while inspector focus remains explicit', async ({ page, request }, testInfo) => {
  const auth = await createAuthenticatedUser(request)

  const seedNode = {
    id: 'temp_sensor_1',
    templateName: 'Temperature Sensor',
    label: 'Temp Sensor',
    position: { x: 1100, y: 540 },
    state: 'Working',
    width: 220,
    height: 180
  }
  const minimumNode = {
    id: 'minimum_sensor_1',
    templateName: 'Temperature Sensor',
    label: 'Minimum Sensor',
    position: { x: 760, y: 320 },
    state: 'Working',
    width: 80,
    height: 60
  }

  await addNodes(request, auth, [seedNode, minimumNode], [
    { name: 'temperature', value: '31', trust: 'trusted', privacy: 'public' }
  ])

  const layoutResponse = await request.post(`${apiBaseURL}/api/board/layout`, {
    headers: authHeaders(auth),
    data: {
      canvasPan: { x: 0, y: 0 },
      canvasZoom: 0.4,
      panels: {
        control: { collapsed: false, width: 320, activeSection: 'templates' },
        inspector: { collapsed: false, width: 320, activeSection: 'devices' }
      }
    }
  })
  expect(layoutResponse.ok(), await layoutResponse.text()).toBeTruthy()

  await openWorkspace(page, auth)

  const node = page.locator('[data-node-id="temp_sensor_1"]')
  await expect(node).toBeVisible({ timeout: 30_000 })
  await expect(node).toHaveClass(/device-node--compact/)
  await expect(node.locator('.resize-handle')).toHaveCount(1)
  const zoomInput = page.getByTestId('canvas-map-zoom-input')
  await expect(zoomInput).toHaveValue('40')
  const initialCanvasTransform = await page.locator('.canvas-inner').evaluate(element =>
    getComputedStyle(element).transform
  )
  const minimumRenderedNode = page.locator('[data-node-id="minimum_sensor_1"]')
  await expect(minimumRenderedNode).toBeVisible()
  const lowZoomHitTarget = await minimumRenderedNode.evaluate(element => {
    const rect = element.getBoundingClientRect()
    const target = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2)
    return {
      nodeId: target?.closest<HTMLElement>('[data-node-id]')?.dataset.nodeId || null,
      resizeHandleCount: element.querySelectorAll('.resize-handle').length
    }
  })
  // Minimum-sized node keeps 1 handle (br) at low zoom so it can be resized. Before the fix, the 52px
  // screen threshold locked it with 0 handles, forcing keyboard-only resize (Ctrl+arrows, undiscoverable).
  expect(lowZoomHitTarget).toEqual({ nodeId: minimumNode.id, resizeHandleCount: 1 })

  await node.click({ button: 'right' })
  await expect(page.getByTestId('device-dialog')).toHaveCount(0)
  const contextMenu = page.locator('.board-context-menu')
  await expect(contextMenu).toBeVisible()
  await contextMenu.getByRole('menuitem', { name: 'View Details' }).click()
  await expect(page.locator('.board-context-menu')).toHaveCount(0)
  const contextDialog = page.getByTestId('device-dialog')
  await expect(contextDialog).toBeVisible()
  await expect(zoomInput).toHaveValue('40')
  await expect(node).toHaveClass(/device-node--compact/)
  await expect.poll(() => page.locator('.canvas-inner').evaluate(element =>
    getComputedStyle(element).transform
  )).toBe(initialCanvasTransform)
  await contextDialog.getByLabel('Close', { exact: true }).click()
  await expect(contextDialog).toHaveCount(0)

  const environmentPool = page.getByTestId('environment-pool')
  await page.getByTestId('inspector-tab-environment').click()
  await expect(environmentPool).toBeVisible()
  await expect(environmentPool).toContainText('Temperature')
  await environmentPool.locator('article').filter({ hasText: 'Temperature' }).getByRole('button').first().click()
  await expect(environmentPool).not.toContainText('a_temperature')
  await expect(page.getByTestId('environment-value-temperature')).toHaveValue('31')

  await node.click()
  await expect(page.getByTestId('device-dialog')).toBeVisible()
  await expect(zoomInput).toHaveValue('40')
  await expect(node).toHaveClass(/device-node--compact/)
  await expect.poll(() => page.locator('.canvas-inner').evaluate(element =>
    getComputedStyle(element).transform
  )).toBe(initialCanvasTransform)
  await expect(page.getByTestId('device-dialog-stateless')).toContainText('This device has no state machine.')
  await expect(page.getByTestId('device-dialog-stateless')).toContainText('Its variables still participate')
  await expect(page.getByTestId('device-dialog-states')).toHaveCount(0)
  await page.getByTestId('device-variables-help').focus()
  const variableHelp = page.locator('.iot-info-tooltip-popper:not([aria-hidden="true"])')
  await expect(variableHelp).toBeVisible()
  await expect(variableHelp).toContainText('Environment Pool')
  await page.getByTestId('device-dialog-close').focus()
  await expect(variableHelp).toBeHidden()
  await page.screenshot({ path: testInfo.outputPath('stateless-device-details.png') })
  await expect(page.getByTestId('device-dialog')).toContainText('Environment variable')
  await expect(page.getByTestId('device-dialog')).not.toContainText('Working, Off')
  await expect(page.getByTestId('device-instance-runtime')).toHaveCount(0)
  await expect(page.getByTestId('environment-value-temperature')).toHaveValue('31')
  await page.getByTestId('device-dialog').getByLabel('Close', { exact: true }).click()

  await expect.poll(async () => {
    const response = await request.get(`${apiBaseURL}/api/board/layout`, {
      headers: authHeaders(auth)
    })
    if (!response.ok()) return null
    const persistedLayout = await unwrap<{
      canvasPan: { x: number; y: number }
      canvasZoom: number
    }>(response)
    return {
      canvasZoom: persistedLayout.canvasZoom,
      canvasPan: persistedLayout.canvasPan
    }
  }).toEqual({ canvasZoom: 0.4, canvasPan: { x: 0, y: 0 } })

  await page.getByTestId('inspector-tab-devices').click()
  const inspectorDevice = page.locator('[data-testid="system-inspector"] [data-device-id="temp_sensor_1"]')
  await inspectorDevice.getByRole('button', { name: 'Temp Sensor' }).click()
  await expect(zoomInput).toHaveValue('100')
  await expect(node).toHaveClass(/device-node--expanded/)
  await expect(node).toHaveClass(/node-focused/)
})

test('device info renders multi-mode API state tuples as readable mode changes', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)

  await addNodes(request, auth, [{
      id: 'thermostat_tuple_api',
      templateName: 'Thermostat',
      label: 'Living Thermostat',
      position: { x: 960, y: 460 },
      state: 'auto;auto',
      width: 220,
      height: 180
    }])

  await openWorkspace(page, auth)

  const node = page.locator('[data-node-id="thermostat_tuple_api"]')
  await expect(node).toBeVisible({ timeout: 30_000 })
  await node.press('Enter')

  const apiSection = page.getByTestId('device-dialog-apis')
  await expect(apiSection).toBeVisible()
  await expect(apiSection).toContainText('Any state')
  await expect(apiSection).toContainText('Thermostat fan mode: Auto')
  await expect(apiSection).toContainText('Thermostat mode: Cool')
  await expect(apiSection).not.toContainText('auto;')
  await expect(apiSection).not.toContainText(';cool')
})

test('environment pool keeps one shared value for multiple readers before simulation', { tag: '@smoke' }, async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)

  await addNodes(request, auth, [
      {
        id: 'temp_sensor_a',
        templateName: 'Temperature Sensor',
        label: 'Temp Sensor A',
        position: { x: 980, y: 300 },
        state: 'Working',
        width: 180,
        height: 150
      },
      {
        id: 'temp_sensor_b',
        templateName: 'Temperature Sensor',
        label: 'Temp Sensor B',
        position: { x: 1230, y: 300 },
        state: 'Working',
        width: 180,
        height: 150
      }
    ], [
      { name: 'temperature', value: '28', trust: 'trusted', privacy: 'public' }
    ])

  await openWorkspace(page, auth)

  const environmentPool = page.getByTestId('environment-pool')
  await page.getByTestId('inspector-tab-environment').click()
  await expect(environmentPool).toBeVisible()
  await environmentPool.locator('article').filter({ hasText: 'Temperature' }).getByRole('button').first().click()
  await expect(environmentPool).not.toContainText('a_temperature')
  await expect(page.getByTestId('environment-value-temperature')).toHaveValue('28')
  await expect(environmentPool).not.toContainText('Conflicting values')

  await page.getByTestId('open-simulation-panel').click()
  await expect(page.getByTestId('simulation-panel')).toBeVisible()
  await page.getByTestId('run-simulation').click()
  await expect(page.getByTestId('simulation-timeline')).toBeVisible({ timeout: 60_000 })
})

for (const viewport of [
  { width: 1280, height: 800, theme: 'light' as const },
  { width: 375, height: 660, theme: 'dark' as const }
]) {
  test(`invalid environment and local integer drafts stay inline without writes at ${viewport.width}px`, async ({ page, request }, testInfo) => {
    await page.setViewportSize(viewport)
    const auth = await createAuthenticatedUser(request, { usernamePrefix: 'valueFeedback' })
    await addNodes(request, auth, [{
      id: 'feedback_thermostat', templateName: 'Thermostat', label: 'Feedback Thermostat',
      position: { x: 240, y: 220 }, state: 'auto;auto', width: 180, height: 150
    }, {
      id: 'feedback_other', templateName: 'Temperature Sensor', label: 'Other reader',
      position: { x: 440, y: 220 }, state: 'Working', width: 180, height: 150
    }], [{ name: 'temperature', value: '20', trust: 'trusted', privacy: 'public' }])
    const snapshot = () => request.get(`${apiBaseURL}/api/board/snapshot`, {
      headers: authHeaders(auth)
    }).then(response => unwrap<BoardSemanticSnapshot>(response))
    const history = () => request.get(`${apiBaseURL}/api/board/edits/clear-preview`, {
      headers: authHeaders(auth)
    }).then(response => unwrap<BoardEditHistoryClearPreview>(response))
    const before = await snapshot()
    const journalBefore = await history()
    expect(Number.isSafeInteger(journalBefore.entryCount)).toBe(true)
    await openWorkspace(page, auth, viewport.theme)
    const writes = { environment: 0, runtime: 0 }
    page.on('request', request => {
      const pathname = new URL(request.url()).pathname
      if (request.method() === 'POST' && pathname === '/api/board/environment') writes.environment++
      if (request.method() === 'PUT' && pathname === '/api/board/nodes/runtime') writes.runtime++
    })
    const inspector = page.getByTestId('system-inspector')
    const expand = inspector.getByRole('button', { name: 'Expand System Inspector', exact: true })
    if (await expand.isVisible()) await expand.click()
    await page.getByTestId('inspector-tab-environment').click()
    await page.getByTestId('environment-pool').locator('article').filter({ hasText: 'Temperature' })
      .getByRole('button').first().click()
    const input = page.getByTestId('environment-value-temperature')
    const error = page.getByTestId('environment-value-failure-temperature')
    for (const invalid of ['', '101', '1.5']) {
      await input.fill(invalid)
      await expect(error).toBeInViewport({ ratio: 1 })
      if (invalid === '1.5') await page.screenshot({ path: testInfo.outputPath('environment-inline-error.png') })
      await input.press('Tab')
      await expect(error).toBeVisible()
      await expect(input).toHaveValue(invalid)
      await expect(input).toHaveAttribute('aria-invalid', 'true')
      await expect(page.locator('.el-message-box')).toHaveCount(0)
      expect(await snapshot()).toEqual(before)
      expect((await history()).entryCount).toBe(journalBefore.entryCount)
      expect(writes).toEqual({ environment: 0, runtime: 0 })
    }
    const environmentSaved = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/board/environment')
    await input.fill('21')
    await input.press('Tab')
    expect((await environmentSaved).ok()).toBeTruthy()
    await expect(error).toHaveCount(0)
    const afterEnvironment = await snapshot()
    expect(afterEnvironment.nodes).toEqual(before.nodes)
    expect(afterEnvironment.rules).toEqual(before.rules)
    expect(afterEnvironment.specifications).toEqual(before.specifications)
    expect(afterEnvironment.environmentVariables.find(value => value.name === 'temperature')?.value).toBe('21')

    await inspector.getByRole('button', { name: 'Collapse System Inspector', exact: true }).click()
    await page.locator('[data-node-id="feedback_thermostat"]').press('Enter')
    await expect(page.getByTestId('device-dialog')).toBeVisible()
    const local = page.getByTestId('device-runtime-variable-thermostatSetpoint')
    const save = page.getByTestId('device-runtime-save')
    const runtimeHeader = page.getByTestId('device-instance-runtime').locator('.device-runtime-header')
    await local.fill('1.5')
    await expect(save).toBeDisabled()
    await expect(page.getByTestId('device-runtime-blocked-reason')).toContainText(/integer/i)
    await expect(local).toHaveValue('1.5')
    expect(writes).toEqual({ environment: 1, runtime: 0 })
    expect(await snapshot()).toEqual(afterEnvironment)
    expect((await history()).entryCount).toBe(journalBefore.entryCount + 1)
    // The action and its reason scroll with the runtime fields in the dialog body.
    await runtimeHeader.scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath('runtime-inline-error.png') })
    await expect(save).toBeInViewport({ ratio: 1 })
    const blockedReason = page.getByTestId('device-runtime-blocked-reason')
    await expect(blockedReason).toBeInViewport({ ratio: 1 })
    await expect.poll(() => blockedReason.evaluate(element => {
      const box = element.getBoundingClientRect()
      const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
      return hit === element || element.contains(hit) ? 'readable' : hit?.outerHTML.slice(0, 300)
    }), { message: 'The correction reason must remain unobstructed.' }).toBe('readable')
    const runtimeSaved = page.waitForResponse(response => response.request().method() === 'PUT'
      && new URL(response.url()).pathname === '/api/board/nodes/runtime')
    await local.fill('29')
    await expect(save).toBeEnabled()
    await runtimeHeader.scrollIntoViewIfNeeded()
    await expect(save).toBeInViewport({ ratio: 1 })
    await save.click()
    expect((await runtimeSaved).ok()).toBeTruthy()
    const afterRuntime = await snapshot()
    expect(afterRuntime.nodes.find(node => node.id === 'feedback_thermostat')?.variables
      ?.find(variable => variable.name === 'thermostatSetpoint')?.value).toBe('29')
    expect(afterRuntime.nodes.filter(node => node.id !== 'feedback_thermostat'))
      .toEqual(before.nodes.filter(node => node.id !== 'feedback_thermostat'))
    expect(afterRuntime.environmentVariables).toEqual(afterEnvironment.environmentVariables)
    expect(afterRuntime.rules).toEqual(before.rules)
    expect(afterRuntime.specifications).toEqual(before.specifications)
    expect((await history()).entryCount).toBe(journalBefore.entryCount + 2)
    expect(writes).toEqual({ environment: 1, runtime: 1 })
  })
}

for (const viewport of [{ width: 640, height: 360 }, { width: 375, height: 500 }]) {
  test(`simulation state controls remain reachable in a ${viewport.width}x${viewport.height} viewport`, async ({ page, request }) => {
    await page.setViewportSize(viewport)
    const auth = await createAuthenticatedUser(request)
    await addNodes(request, auth, [{
      id: 'short_view_ac', templateName: 'Air Conditioner', label: 'Short View AC',
      position: { x: 180, y: 160 }, state: 'auto', width: 180, height: 140
    }])
    await openWorkspace(page, auth)
    await page.getByTestId('restore-action-dock').click()
    await page.getByTestId('open-simulation-panel').click()
    await page.getByTestId('run-simulation').click()

    const timeline = page.getByTestId('simulation-timeline')
    await expect(timeline).toBeVisible({ timeout: 60_000 })
    const controls = page.getByTestId('playback-toolbar')
    for (const panelId of ['system-inspector', 'control-center']) {
      const panel = page.getByTestId(panelId)
      const expand = panel.getByRole('button', { name: panelId === 'control-center' ? 'Expand Control Center' : 'Expand System Inspector', exact: true })
      await expect.poll(() => expand.evaluate(button => {
        const box = button.getBoundingClientRect()
        const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
        return hit === button || button.contains(hit)
      }), { message: 'Opening a sidebar must not hit Exit playback or another transport control.' }).toBe(true)
      await expand.click()
      await expect(panel.getByRole('button', { name: panelId === 'control-center' ? 'Collapse Control Center' : 'Collapse System Inspector', exact: true })).toBeVisible()
      await expect(timeline).toHaveAttribute('data-selected-state-index', '0')
      await panel.getByRole('button', { name: panelId === 'control-center' ? 'Collapse Control Center' : 'Collapse System Inspector', exact: true }).click()
    }
    const fitMobile = page.getByTestId('canvas-fit-mobile')
    await expect(fitMobile).toBeEnabled()
    await expect.poll(() => fitMobile.evaluate(button => {
      const box = button.getBoundingClientRect()
      const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
      return hit === button || button.contains(hit)
    }), { message: 'The playback strip must not intercept the mobile fit control.' }).toBe(true)
    await fitMobile.click()
    await expect(timeline).toHaveAttribute('data-selected-state-index', '0')
    expect(await timeline.getByRole('tabpanel').evaluate(element => element.clientHeight)).toBeGreaterThanOrEqual(80)
    await expect(timeline.locator('.playback-terminal__transport')).toHaveCount(0)
    const controlsWidth = await controls.evaluate(element => ({ client: element.clientWidth, scroll: element.scrollWidth }))
    expect(controlsWidth.scroll).toBeLessThanOrEqual(controlsWidth.client)
    const next = controls.getByRole('button', { name: 'Next state', exact: true })
    await expect(next).toBeEnabled()
    await next.scrollIntoViewIfNeeded()
    await expect.poll(() => next.evaluate(button => {
      const rect = button.getBoundingClientRect()
      const target = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2)
      return target === button || button.contains(target)
    }), { message: 'The state button must receive pointer input in the persistent canvas strip.' }).toBe(true)
    await next.click()
    await expect(timeline).toHaveAttribute('data-selected-state-index', '1')
    await controls.getByRole('button', { name: 'Previous state', exact: true }).click()
    await expect(timeline).toHaveAttribute('data-selected-state-index', '0')
    await controls.getByTestId('simulation-timeline-step-input').fill('3')
    await expect(timeline).toHaveAttribute('data-selected-state-index', '0')
    await controls.getByTestId('simulation-timeline-step-input').press('Enter')
    await expect(timeline).toHaveAttribute('data-selected-state-index', '2')
    const terminalHeight = await timeline.evaluate(element => element.clientHeight)
    await page.getByTestId('simulation-timeline-changes-tab').click()
    await expect(timeline.getByTestId('playback-changes-panel')).toBeVisible()
    expect(await timeline.evaluate(element => element.clientHeight)).toBe(terminalHeight)
    await page.getByTestId('simulation-timeline-details-tab').click()
    await expect(page.getByTestId('playback-state-details')).toBeVisible()
    await expect(timeline).toHaveAttribute('data-selected-state-index', '2')
    expect(await timeline.evaluate(element => element.clientHeight)).toBe(terminalHeight)
    const detailsPanel = timeline.getByRole('tabpanel')
    await detailsPanel.hover()
    await page.mouse.wheel(0, 500)
    await expect.poll(() => detailsPanel.evaluate(element => element.scrollTop)).toBeGreaterThan(0)
    const detailsScrollTop = await detailsPanel.evaluate(element => element.scrollTop)
    await page.getByTestId('simulation-timeline-changes-tab').click()
    expect(await timeline.getByRole('tabpanel').evaluate(element => element.scrollTop)).toBe(0)
    await page.getByTestId('simulation-timeline-details-tab').click()
    expect(await timeline.getByRole('tabpanel').evaluate(element => element.scrollTop)).toBe(detailsScrollTop)
    await page.getByTestId('simulation-timeline-changes-tab').click()
    await page.getByTestId('simulation-timeline-hide-panel').click()
    await expect(timeline).toBeHidden()
    await expect(page.getByTestId('simulation-timeline-close')).toBeVisible()
    await expect(controls.getByTestId('simulation-timeline-play')).toBeVisible()
    await controls.getByTestId('simulation-timeline-step-input').fill('2')
    await expect(timeline).toHaveAttribute('data-selected-state-index', '2')
    await controls.getByTestId('simulation-timeline-step-input').press('Enter')
    await expect(timeline).toHaveAttribute('data-selected-state-index', '1')
    await next.click()
    await expect(timeline).toHaveAttribute('data-selected-state-index', '2')
    await expect(timeline).toBeHidden()
    await page.getByTestId('playback-panel-toggle').click()
    await expect(timeline).toHaveAttribute('data-selected-state-index', '2')
    await expect(page.getByTestId('simulation-timeline-changes-tab')).toHaveAttribute('aria-selected', 'true')
    await page.getByTestId('simulation-timeline-step-input').fill('1')
    await expect(timeline).toHaveAttribute('data-selected-state-index', '2')
    await page.getByTestId('simulation-timeline-step-input').press('Enter')
    await expect(timeline).toHaveAttribute('data-selected-state-index', '0')
    await page.getByTestId('simulation-timeline-play').click()
    await page.getByTestId('simulation-timeline-hide-panel').click()
    await expect.poll(async () => Number(await timeline.getAttribute('data-selected-state-index'))).toBeGreaterThan(0)
    await expect(page.getByTestId('open-simulation-panel')).toBeDisabled()
    await page.getByTestId('simulation-timeline-close').click()
    await expect(timeline).not.toBeVisible()
    await expect(page.getByTestId('open-simulation-panel')).toBeEnabled()
  })

  test(`simulation playback stays in the visible canvas after resizing from ${viewport.width}x${viewport.height}`, async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await addNodes(request, auth, [{
      id: 'resize_ac_a', templateName: 'Air Conditioner', label: 'Resize AC A',
      position: { x: 180, y: 160 }, state: 'auto', width: 180, height: 140
    }, {
      id: 'resize_ac_b', templateName: 'Air Conditioner', label: 'Resize AC B',
      position: { x: 800, y: 400 }, state: 'auto', width: 180, height: 140
    }])
    const liveViewport = { canvasPan: { x: 35, y: 25 }, canvasZoom: 1.05 }
    await unwrap(await request.post(`${apiBaseURL}/api/board/layout`, {
      headers: authHeaders(auth), data: {
        ...liveViewport,
        panels: {
          control: { collapsed: false, width: 320, activeSection: 'templates' },
          inspector: { collapsed: false, width: 320, activeSection: 'devices' }
        }
      }
    }))
    await page.setViewportSize({ width: 1440, height: 900 })
    await openWorkspace(page, auth)
    await expect(page.getByTestId('canvas-map-zoom-input')).toHaveValue('105')
    await page.setViewportSize(viewport)
    await page.getByTestId('restore-action-dock').click()
    await page.getByTestId('open-simulation-panel').click()
    await page.getByTestId('run-simulation').click()
    const timeline = page.getByTestId('simulation-timeline')
    await expect(timeline).toBeVisible({ timeout: 60_000 })

    const expectPlaybackFits = async () => {
      await expect.poll(() => page.getByTestId('canvas-board').evaluate(canvas => {
        const left = document.querySelector('[data-testid="control-center"]')!.getBoundingClientRect().right
        const right = document.querySelector('.board-action-dock')!.getBoundingClientRect().left
        const bottom = document.querySelector('.board-timeline-host')!.getBoundingClientRect().top
        const top = document.querySelector('[data-testid="playback-toolbar"]')!.getBoundingClientRect().bottom
        const nodes = [...canvas.querySelectorAll('[data-node-id]')]
        return nodes.length === 2 && nodes.every(node => {
          const box = node.getBoundingClientRect()
          return box.left >= left && box.right <= right && box.top >= top && box.bottom <= bottom
        })
      }), { message: 'Resizing must refit frozen nodes into the space between the sidebars, dock and timeline.' }).toBe(true)
      await expect.poll(() => page.locator('.device-label').evaluateAll(labels => {
        const scale = new DOMMatrixReadOnly(getComputedStyle(document.querySelector('.canvas-inner')!).transform).a
        return labels.every(label => Number((Number.parseFloat(getComputedStyle(label).fontSize) * scale).toFixed(2)) >= 11)
      }), { message: 'Device names must retain the readable screen minimum in a fitted overview.' }).toBe(true)
      await expect.poll(() => page.getByTestId('playback-toolbar').evaluate(strip => {
        const frame = strip.getBoundingClientRect()
        const buttons = [...strip.querySelectorAll('button')].map(button => button.getBoundingClientRect())
        return buttons.every((box, index) => box.left >= frame.left && box.right <= frame.right
          && box.top >= frame.top && box.bottom <= frame.bottom
          && buttons.slice(index + 1).every(other => box.right <= other.left || other.right <= box.left
            || box.bottom <= other.top || other.bottom <= box.top))
      }), { message: 'Every transport button must fit without overlapping its neighbor, including with expanded sidebars.' }).toBe(true)
    }
    for (const size of [{ width: 1024, height: 768 }, { width: 1440, height: 900 }, viewport, { width: 1440, height: 900 }]) {
      await page.setViewportSize(size)
      await expectPlaybackFits()
    }
    const inspector = page.getByTestId('system-inspector')
    const collapse = inspector.getByRole('button', { name: 'Collapse System Inspector', exact: true })
    await expect(collapse).toBeEnabled()
    await collapse.click()
    await expectPlaybackFits()
    await inspector.getByRole('button', { name: 'Expand System Inspector', exact: true }).click()
    await expectPlaybackFits()
    await expect(page.getByTestId('inspector-add-device')).toBeDisabled()
    await expect(page.getByTestId('canvas-map-fit')).toBeEnabled()
    await page.getByTestId('canvas-map-zoom-input').fill('80')
    await expect(page.getByTestId('canvas-map-zoom-input')).toHaveValue('80')
    const playbackScale = () => page.locator('.canvas-inner').evaluate(element =>
      new DOMMatrixReadOnly(getComputedStyle(element).transform).a)
    await collapse.click()
    await expect.poll(playbackScale, { message: 'Closing the inspector must retain the manually chosen playback scale.' }).toBe(0.8)
    await page.getByTestId('simulation-timeline-hide-panel').click()
    await expect(timeline).toBeHidden()
    await expect.poll(playbackScale, { message: 'Hiding terminal content must retain the manually chosen playback scale.' }).toBe(0.8)
    await page.setViewportSize({ width: 1024, height: 768 })
    await expect.poll(playbackScale).toBe(0.8)
    await page.getByTestId('playback-panel-toggle').click()
    await expect(timeline).toBeVisible()
    await expect.poll(playbackScale).toBe(0.8)
    await inspector.getByRole('button', { name: 'Expand System Inspector', exact: true }).click()
    await expect(page.getByTestId('canvas-map-zoom-input')).toHaveValue('80')
    await page.getByTestId('canvas-map-fit').click()
    await expectPlaybackFits()
    await page.getByTestId('simulation-timeline-close').click()
    await expect(timeline).not.toBeVisible()
    await expect(page.getByTestId('canvas-map-zoom-input')).toHaveValue('105')
    const saved = await unwrap<Record<string, unknown>>(await request.get(`${apiBaseURL}/api/board/layout`, {
      headers: authHeaders(auth)
    }))
    expect(saved).toMatchObject(liveViewport)
  })
}
