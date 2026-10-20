import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { transpileModule, ScriptTarget } from 'typescript'
import { effectScope, nextTick, reactive, ref, shallowRef, watch, watchPostEffect } from 'vue'
import { describe, expect, it, vi } from 'vitest'

const board = readFileSync(join(__dirname, '../Board.vue'), 'utf8')

const wheelStart = board.indexOf('const onBoardWheel =')
const wheelEnd = board.indexOf('const onCanvasEnter =', wheelStart)
if (wheelStart < 0 || wheelEnd < 0) throw new Error('Canvas wheel navigation is missing')
const wheel = new Function('isCanvasNavigationLocked', 'adjustCanvasZoom', 'ZOOM_STEP',
  transpileModule(`${board.slice(wheelStart, wheelEnd)}\nreturn onBoardWheel`, {
    compilerOptions: { target: ScriptTarget.ES2022 }
  }).outputText)

describe('canvas wheel navigation', () => {
  it('renders the published minimum without synchronously measuring layout again', () => {
    const expression = board.match(/data-testid="canvas-map-zoom-input"[\s\S]*?:min="([^"]+)"/)?.[1]
    expect(expression).toBeTruthy()
    const measure = vi.fn(() => 0.15)
    const renderMinimum = new Function('getMinimumCanvasZoom', 'minimumCanvasZoomForInput',
      `return ${expression!.replace(/\bminimumCanvasZoomForInput\b/g, 'minimumCanvasZoomForInput.value')}`)
    expect(renderMinimum(measure, ref(0.15))).toBe(15)
    expect(measure).not.toHaveBeenCalled()
  })

  it.each([-100, 0, 100])('ignores zero vertical movement with horizontal delta %s', deltaX => {
    const adjust = vi.fn()
    wheel({ value: false }, adjust, 0.1)({ ctrlKey: true, deltaX, deltaY: 0 })
    expect(adjust).not.toHaveBeenCalled()
  })

  it.each([[-100, 0.1], [100, -0.1]])('keeps the existing zoom step for vertical delta %s', (deltaY, expected) => {
    const adjust = vi.fn()
    wheel({ value: false }, adjust, 0.1)({ ctrlKey: true, deltaY })
    expect(adjust).toHaveBeenCalledExactlyOnceWith(expected)
  })

  it('preserves navigation locking and ignores an ordinary wheel', () => {
    const adjust = vi.fn()
    const preventDefault = vi.fn()
    wheel({ value: true }, adjust, 0.1)({ ctrlKey: true, deltaY: -100, preventDefault })
    expect(preventDefault).toHaveBeenCalledOnce()
    wheel({ value: false }, adjust, 0.1)({ ctrlKey: false, deltaY: -100 })
    expect(adjust).not.toHaveBeenCalled()
  })
})

const start = board.indexOf('const fittedViewportForNodes =')
const end = board.indexOf('const fitNodesToCanvas =', start)
if (start < 0 || end < 0) throw new Error('Canvas fitting is missing')
const fit = new Function('getNodeBounds', 'getVisibleCanvasFrame', 'MAX_ZOOM', 'MIN_ZOOM',
  transpileModule(`${board.slice(start, end)}\nreturn fittedViewportForNodes([])`, {
    compilerOptions: { target: ScriptTarget.ES2022 }
  }).outputText)

const zoomStart = board.indexOf('const getMinimumCanvasZoom =')
const zoomEnd = board.indexOf('const setCanvasZoom =', zoomStart)
if (zoomStart < 0 || zoomEnd < 0) throw new Error('Canvas zoom bounds are missing')
const clamp = new Function('fittedViewportForNodes', 'renderedCanvasNodes', 'MIN_ZOOM', 'MAX_ZOOM',
  'getVisibleCanvasFrame = () => ({ left: 0, top: 0, width: 1280, height: 900 })',
  transpileModule(`${board.slice(zoomStart, zoomEnd)}\nreturn clampZoom`, {
    compilerOptions: { target: ScriptTarget.ES2022 }
  }).outputText)

const geometryStart = board.indexOf('const canvasContainer =')
const geometryEnd = board.indexOf('const handleCanvasMapZoomInput =', geometryStart)
if (geometryStart < 0 || geometryEnd < 0) throw new Error('Canvas render geometry capture is missing')
const geometry = new Function('ref', 'shallowRef', 'watchPostEffect', 'onMounted', 'onBeforeUnmount',
  'getVisibleCanvasFrame', 'getMinimumCanvasZoom', 'boardShellStyle', 'actionDockViewportWidth',
  'boardViewportHeight', 'MIN_ZOOM', 'ResizeObserver',
  transpileModule(`${board.slice(geometryStart, geometryEnd)}
    return { canvasContainer, minimumCanvasZoomForInput, getCanvasFrameForRendering }`, {
    compilerOptions: { target: ScriptTarget.ES2022 }
  }).outputText)

const createGeometryCapture = () => {
  let frame = { left: 48, top: 69, width: 500, height: 400 }
  const panelWidth = ref(0)
  const fittedZoom = ref(0.2)
  const shell = ref({ toolbarHeight: 0 })
  const width = ref(1280)
  const height = ref(900)
  const readFrame = vi.fn(() => ({ ...frame, width: frame.width - panelWidth.value }))
  const readMinimum = vi.fn(() => fittedZoom.value)
  const mount: Array<() => void> = []
  const unmount: Array<() => void> = []
  const observe = vi.fn()
  const disconnect = vi.fn()
  let resize = () => {}
  class Observer {
    constructor(callback: () => void) { resize = callback }
    observe = observe
    disconnect = disconnect
  }
  const scope = effectScope()
  const state = scope.run(() => geometry(ref, shallowRef, watchPostEffect,
    (callback: () => void) => mount.push(callback), (callback: () => void) => unmount.push(callback),
    readFrame, readMinimum, shell, width, height, 0.4, Observer))!
  return {
    state, panelWidth, fittedZoom, shell, width, height, readFrame, readMinimum, observe, disconnect,
    setFrame: (next: typeof frame) => { frame = next },
    mount: (element: HTMLElement) => {
      state.canvasContainer.value = element
      mount.forEach(callback => callback())
    },
    resize: () => resize(),
    stop: () => { unmount.forEach(callback => callback()); scope.stop() }
  }
}

describe('canvas render geometry capture', () => {
  it('tracks node bounds, panels, window dimensions and shell insets without remeasuring on render', async () => {
    const capture = createGeometryCapture()
    try {
      await nextTick()
      expect(capture.state.minimumCanvasZoomForInput.value).toBe(0.2)
      expect(capture.state.getCanvasFrameForRendering()).toEqual({ left: 48, top: 69, width: 500, height: 400 })
      capture.readFrame.mockClear()
      capture.readMinimum.mockClear()
      for (let i = 0; i < 3; i++) capture.state.getCanvasFrameForRendering()
      await nextTick()
      expect(capture.readFrame).not.toHaveBeenCalled()
      expect(capture.readMinimum).not.toHaveBeenCalled()
      capture.fittedZoom.value = 0.15
      await nextTick()
      expect(capture.state.minimumCanvasZoomForInput.value).toBe(0.15)
      capture.panelWidth.value = 100
      await nextTick()
      expect(capture.state.getCanvasFrameForRendering().width).toBe(400)
      capture.setFrame({ left: 48, top: 69, width: 700, height: 400 })
      capture.width.value = 1440
      await nextTick()
      expect(capture.state.getCanvasFrameForRendering().width).toBe(600)
      capture.setFrame({ left: 48, top: 69, width: 700, height: 500 })
      capture.height.value = 1000
      await nextTick()
      expect(capture.state.getCanvasFrameForRendering().height).toBe(500)
      capture.setFrame({ left: 48, top: 157, width: 700, height: 412 })
      capture.shell.value = { toolbarHeight: 88 }
      await nextTick()
      expect(capture.state.getCanvasFrameForRendering().top).toBe(157)
    } finally { capture.stop() }
  })

  it('captures actual container resizes and releases observation on unmount', async () => {
    const capture = createGeometryCapture()
    let stopped = false
    try {
      await nextTick()
      const container = document.createElement('div')
      capture.mount(container)
      expect(capture.observe).toHaveBeenCalledWith(container, { box: 'border-box' })
      capture.setFrame({ left: 48, top: 69, width: 320, height: 240 })
      capture.resize()
      expect(capture.state.getCanvasFrameForRendering()).toEqual({ left: 48, top: 69, width: 320, height: 240 })
      capture.stop()
      stopped = true
      expect(capture.disconnect).toHaveBeenCalledOnce()
      capture.readFrame.mockClear()
      capture.width.value = 1600
      await nextTick()
      expect(capture.readFrame).not.toHaveBeenCalled()
    } finally { if (!stopped) capture.stop() }
  })
})

const playbackStart = board.indexOf('const activatePlaybackScene =')
const playbackEnd = board.indexOf('const handleCreateDevice =', playbackStart)
if (playbackStart < 0 || playbackEnd < 0) throw new Error('Playback viewport controls are missing')
const modeStart = board.indexOf('let playbackViewportFollowsFit =')
const modeEnd = board.indexOf('const environmentVariables =', modeStart)
const navigationStart = board.indexOf('const setCanvasZoom =')
const navigationEnd = board.indexOf('const canvasZoomPercent =', navigationStart)
const centerStart = board.indexOf('const getVisibleCanvasCenterWorld =')
const centerEnd = board.indexOf('const clampCanvasMapValue =', centerStart)
const panStart = board.indexOf('const panCanvasToWorldCenter =')
const panEnd = board.indexOf('const focusDeviceNodeOnCanvas =', panStart)
const zoomNavigation = new Function('ref', 'getVisibleCanvasFrame', 'getNodeBounds', 'playback',
  transpileModule(`
    const isCanvasNavigationLocked = ref(false)
    const layoutHydrated = ref(true)
    const activePlaybackScene = { value: playback ? {} : null }
    const canvasZoom = ref(1)
    const canvasPan = ref({ x: 100, y: 50 })
    const playbackCanvasZoom = ref(1)
    const playbackCanvasPan = ref({ x: 100, y: 50 })
    let playbackViewportFollowsFit = true
    let canvasStateTouchedBeforeLayout = false
    const renderedCanvasZoom = playback ? playbackCanvasZoom : canvasZoom
    const renderedCanvasPan = playback ? playbackCanvasPan : canvasPan
    const renderedCanvasNodes = { value: [] }
    const MIN_ZOOM = 0.4
    const MAX_ZOOM = 2
    ${board.slice(zoomStart, zoomEnd)}
    ${board.slice(centerStart, centerEnd)}
    ${board.slice(navigationStart, navigationEnd)}
    ${board.slice(panStart, panEnd)}
    ${board.slice(start, end)}
    return { setCanvasZoom, zoom: renderedCanvasZoom, pan: renderedCanvasPan, isCanvasNavigationLocked }
  `, { compilerOptions: { target: ScriptTarget.ES2022 } }).outputText)

describe('canvas zoom frame ownership', () => {
  it.each([false, true])('measures one fresh frame per command, in playback: %s', playback => {
    let frame = { left: 40, top: 64, width: 800, height: 600 }
    let bounds = { minX: 0, minY: 0, maxX: 2000, maxY: 1200 }
    const readFrame = vi.fn(() => frame)
    const readBounds = vi.fn(() => bounds)
    const control = zoomNavigation(ref, readFrame, readBounds, playback)
    for (const [nextFrame, requested, expected] of [
      [frame, 1.5, 1.5],
      [{ left: 48, top: 100, width: 1000, height: 700 }, 1.2, 1.2],
      [{ left: 48, top: 100, width: 1600, height: 900 }, 0.01, 0.0756]
    ] as const) {
      frame = nextFrame
      if (requested === 0.01) bounds = { ...bounds, maxX: 10000, maxY: 10000 }
      const center = {
        x: (frame.left + frame.width / 2 - control.pan.value.x) / control.zoom.value,
        y: (frame.top + frame.height / 2 - control.pan.value.y) / control.zoom.value
      }
      readFrame.mockClear()
      readBounds.mockClear()
      control.setCanvasZoom(requested, { preserveCenter: true })
      expect(readFrame).toHaveBeenCalledOnce()
      expect(readBounds).toHaveBeenCalledOnce()
      expect(control.zoom.value).toBeCloseTo(expected, 10)
      expect(control.pan.value.x).toBeCloseTo(frame.left + frame.width / 2 - center.x * expected, 8)
      expect(control.pan.value.y).toBeCloseTo(frame.top + frame.height / 2 - center.y * expected, 8)
    }
    control.isCanvasNavigationLocked.value = true
    readFrame.mockClear()
    control.setCanvasZoom(1)
    expect(readFrame).not.toHaveBeenCalled()
    expect(control.zoom.value).toBeCloseTo(0.0756, 10)
  })
})

const playbackControls = new Function('activePlaybackScene', 'deepClone', 'nextTick',
  'fittedViewportForNodes', 'playbackCanvasZoom', 'playbackCanvasPan', 'watch',
  'actionDockViewportWidth', 'boardViewportHeight', 'playbackPanelOpen', 'playbackTerminalTab',
  'boardPanels', 'effectiveControlPanelWidth', 'effectiveInspectorPanelWidth', 'actionDockReservedWidth',
  'getVisibleCanvasFrame',
  transpileModule(`
    const isCanvasNavigationLocked = { value: false }
    const layoutHydrated = { value: true }
    const renderedCanvasZoom = playbackCanvasZoom
    const renderedCanvasPan = playbackCanvasPan
    const renderedCanvasNodes = { get value() { return activePlaybackScene.value?.nodes ?? [] } }
    const canvasZoom = { value: 1 }
    const canvasPan = { value: { x: 0, y: 0 } }
    const clampZoom = value => value
    ${board.slice(modeStart, modeEnd)}
    ${board.slice(centerStart, centerEnd)}
    ${board.slice(navigationStart, navigationEnd)}
    ${board.slice(panStart, panEnd)}
    ${board.slice(playbackStart, playbackEnd)}
    return { activatePlaybackScene, deactivatePlaybackScene, setCanvasZoom, panCanvasToWorldCenter, fitToContent }
  `, {
    compilerOptions: { target: ScriptTarget.ES2022 }
  }).outputText)

describe('canvas overview fitting', () => {
  it.each([
    { left: 48, top: 69, width: 452, height: 945 },
    { left: 48, top: 69, width: 222, height: 600 },
    { left: 320, top: 69, width: 188, height: 80 }
  ])('fits every content boundary inside the available frame %j', frame => {
    const bounds = { minX: -600, minY: -200, maxX: 1400, maxY: 900 }
    const viewport = fit(() => bounds, () => frame, 2, 0.4)
    expect(viewport.zoom).toBeGreaterThan(0)
    expect(viewport.zoom).toBeLessThan(0.4)
    expect(bounds.minX * viewport.zoom + viewport.pan.x).toBeGreaterThanOrEqual(frame.left)
    expect(bounds.maxX * viewport.zoom + viewport.pan.x).toBeLessThanOrEqual(frame.left + frame.width)
    expect(bounds.minY * viewport.zoom + viewport.pan.y).toBeGreaterThanOrEqual(frame.top)
    expect(bounds.maxY * viewport.zoom + viewport.pan.y).toBeLessThanOrEqual(frame.top + frame.height)
    const occupiedWidth = (bounds.maxX - bounds.minX) * viewport.zoom / frame.width
    const occupiedHeight = (bounds.maxY - bounds.minY) * viewport.zoom / frame.height
    expect(Math.max(occupiedWidth, occupiedHeight)).toBeGreaterThanOrEqual(0.75)
  })

  it('lets manual zoom return to the fitted overview after zooming in', () => {
    const clampZoom = clamp(() => ({ zoom: 0.16 }), { value: [] }, 0.4, 2)
    expect(clampZoom(0.26)).toBe(0.26)
    expect(clampZoom(0.16)).toBe(0.16)
    expect(clampZoom(0.01)).toBe(0.16)
    expect(clampZoom(3)).toBe(2)
  })

  it('keeps the ordinary zoom floor when the scene fits and returns no viewport for missing geometry', () => {
    expect(clamp(() => ({ zoom: 1.2 }), { value: [] }, 0.4, 2)(0.1)).toBe(0.4)
    expect(fit(() => null, () => null, 2, 0.4)).toBeNull()
  })

  it.each([
    { width: 1440, height: 360 },
    { width: 640, height: 900 }
  ])('refits the frozen playback when the viewport becomes $width x $height', async viewport => {
    const scene = ref<{ nodes: { id: string }[] } | null>(null)
    const width = ref(640)
    const height = ref(360)
    const zoom = ref(1)
    const pan = ref({ x: 0, y: 0 })
    const scope = effectScope()
    try {
      const controls = scope.run(() => playbackControls(scene, structuredClone, nextTick,
        (nodes: { id: string }[]) => {
          expect(nodes).toEqual([{ id: 'frozen-device' }])
          return { zoom: width.value / 1000, pan: { x: width.value / 2, y: height.value / 2 } }
        }, zoom, pan, watch, width, height, ref(true), ref('timeline'),
        reactive({ control: { collapsed: true }, inspector: { collapsed: true } }), ref(320), ref(320), ref(72),
        () => ({ left: 0, top: 0, width: width.value, height: height.value })))!
      controls.activatePlaybackScene({ nodes: [{ id: 'frozen-device' }] })
      await nextTick()
      await nextTick()
      expect(pan.value).toEqual({ x: 320, y: 180 })

      width.value = viewport.width
      height.value = viewport.height
      await nextTick()
      await nextTick()
      expect(zoom.value).toBe(viewport.width / 1000)
      expect(pan.value).toEqual({ x: viewport.width / 2, y: viewport.height / 2 })

      controls.deactivatePlaybackScene()
      width.value = 1280
      height.value = 720
      await nextTick()
      await nextTick()
      expect(zoom.value).toBe(1)
      expect(pan.value).toEqual({ x: 0, y: 0 })
    } finally {
      scope.stop()
    }
  })

  it('refits when sidebars collapse or resize and when the tool dock changes width', async () => {
    const scene = ref<{ nodes: { id: string }[] } | null>(null)
    const panels = reactive({ control: { collapsed: true }, inspector: { collapsed: true } })
    const controlWidth = ref(320)
    const inspectorWidth = ref(320)
    const dockWidth = ref(72)
    const terminalTab = ref('timeline')
    const fitted = vi.fn(() => ({ zoom: 0.5, pan: { x: 250, y: 150 } }))
    const scope = effectScope()
    try {
      const controls = scope.run(() => playbackControls(scene, structuredClone, nextTick,
        fitted, ref(1), ref({ x: 0, y: 0 }), watch, ref(1024), ref(768), ref(true), terminalTab,
        panels, controlWidth, inspectorWidth, dockWidth,
        () => ({ left: 0, top: 0, width: 1024, height: 768 })))!
      controls.activatePlaybackScene({ nodes: [{ id: 'frozen-device' }] })
      await nextTick()
      await nextTick()
      expect(fitted).toHaveBeenCalledTimes(1)
      for (const change of [
        () => { panels.control.collapsed = false },
        () => { panels.inspector.collapsed = false },
        () => { controlWidth.value = 360 },
        () => { inspectorWidth.value = 360 },
        () => { dockWidth.value = 140 }
      ]) {
        fitted.mockClear()
        change()
        await nextTick()
        await nextTick()
        expect(fitted).toHaveBeenCalledOnce()
        expect(fitted).toHaveBeenCalledWith([{ id: 'frozen-device' }])
      }
      fitted.mockClear()
      for (const tab of ['changes', 'details', 'timeline']) {
        terminalTab.value = tab
        await nextTick()
        await nextTick()
        expect(fitted).not.toHaveBeenCalled()
      }
    } finally { scope.stop() }
  })

  it.each(['zoom', 'pan'])('preserves manual %s and the viewed world center through frame changes', async navigation => {
    const scene = ref<{ nodes: { id: string }[] } | null>(null)
    const width = ref(1180)
    const height = ref(871)
    const panelOpen = ref(true)
    const terminalTab = ref('timeline')
    const panels = reactive({ control: { collapsed: true }, inspector: { collapsed: false } })
    const zoom = ref(1)
    const pan = ref({ x: 0, y: 0 })
    const frame = () => ({
      left: 56, top: 64,
      width: width.value - 56 - (panels.inspector.collapsed ? 56 : 320) - 72,
      height: height.value - 64 - (panelOpen.value ? 280 : 0) - 24
    })
    const fitted = vi.fn(() => ({ zoom: 0.2, pan: { x: 250, y: 150 } }))
    const center = () => ({
      x: (frame().left + frame().width / 2 - pan.value.x) / zoom.value,
      y: (frame().top + frame().height / 2 - pan.value.y) / zoom.value
    })
    const scope = effectScope()
    try {
      const controls = scope.run(() => playbackControls(scene, structuredClone, nextTick,
        fitted, zoom, pan, watch, width, height, panelOpen, terminalTab, panels, ref(320), ref(320), ref(72), frame))!
      controls.activatePlaybackScene({ nodes: [{ id: 'frozen-device' }] })
      await nextTick()
      await nextTick()
      if (navigation === 'zoom') controls.setCanvasZoom(0.6, { preserveCenter: true })
      else controls.panCanvasToWorldCenter(123, 456)
      const viewedCenter = center()
      const manualZoom = zoom.value
      fitted.mockClear()

      for (const change of [
        () => { panels.inspector.collapsed = true },
        () => { terminalTab.value = 'changes' },
        () => { panelOpen.value = false },
        () => { width.value = 640; height.value = 500 },
        () => { panelOpen.value = true }
      ]) {
        change()
        await nextTick()
        await nextTick()
        expect(zoom.value).toBe(manualZoom)
        expect(center().x).toBeCloseTo(viewedCenter.x)
        expect(center().y).toBeCloseTo(viewedCenter.y)
        expect(fitted).not.toHaveBeenCalled()
      }

      controls.fitToContent()
      expect(zoom.value).toBe(0.2)
      fitted.mockClear()
      panels.inspector.collapsed = false
      await nextTick()
      await nextTick()
      expect(fitted).toHaveBeenCalledOnce()

      controls.setCanvasZoom(0.8)
      controls.activatePlaybackScene({ nodes: [{ id: 'another-frozen-device' }] })
      await nextTick()
      await nextTick()
      expect(zoom.value).toBe(0.2)
      expect(pan.value).toEqual({ x: 250, y: 150 })
    } finally { scope.stop() }
  })
})
