import { type APIRequestContext, type Page } from '@playwright/test'
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

for (const { gesture, finish } of [
  { gesture: 'drag', finish: 'cancel' },
  { gesture: 'resize', finish: 'release' }
] as const) {
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

test('self-loop follows a held drag, persists once and survives undo at 375px in dark', async ({ page, request }) => {
  const viewport = { width: 375, height: 812 }
  const theme = 'dark'
  const zoom = 0.4
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
  await page.getByTestId('canvas-fit-mobile').click()
  await expect.poll(() => page.getByTestId('canvas-board').evaluate(canvas => {
    const frame = canvas.getBoundingClientRect()
    return [...canvas.querySelectorAll('[data-node-id]')].every(element => {
      const box = element.getBoundingClientRect()
      return box.left >= frame.left && box.top >= frame.top
        && box.right <= frame.right && box.bottom <= frame.bottom
    })
  })).toBe(true)
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

for (const viewport of [{ width: 640, height: 360 }]) {
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
