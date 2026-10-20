import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick, reactive } from 'vue'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import postcss from 'postcss'

import CanvasBoard from '@/components/CanvasBoard.vue'
import { i18n } from '@/assets/i18n'
import { getLinkPoints, getSelfLoopPath } from '@/utils/rule'
import type { DeviceNode } from '@/types/node'

afterEach(() => { vi.useRealTimers() })
const pointerEvent = (type: string, x: number, y: number) => {
  const event = new MouseEvent(type, { bubbles: true, clientX: x, clientY: y, button: 0 })
  Object.defineProperties(event, {
    pointerId: { value: 1 },
    isPrimary: { value: true }
  })
  return event
}

const nextAnimationFrame = async () => {
  await new Promise<number>(resolve => requestAnimationFrame(resolve))
  await nextTick()
}

const controlAnimationFrames = () => {
  const frames = new Map<number, FrameRequestCallback>()
  let sequence = 0
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    frames.set(++sequence, callback)
    return sequence
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
  return {
    pending: () => frames.size,
    flush: async () => {
      const callbacks = [...frames.values()]
      frames.clear()
      callbacks.forEach(callback => callback(0))
      await nextTick()
    }
  }
}

describe('CanvasBoard live edge geometry', () => {
  it('resolves sparse runtime readings with a linear number of historical device visits', async () => {
    const deviceCount = 30
    const stepCount = 24
    let deviceIdReads = 0
    const nodes = Array.from({ length: deviceCount }, (_, index) => ({
      id: `sensor-${index}`, templateName: 'Sensor', label: `Sensor ${index}`,
      state: 'on', position: { x: index * 180, y: 0 }, width: 176, height: 128
    }))
    const states = Array.from({ length: stepCount }, (_, step) => ({
      devices: nodes.map((_, index) => ({
        get deviceId() { deviceIdReads++; return `sensor_${index}` },
        state: 'on',
        variables: step === 0 || step === stepCount - 1
          ? ['temperature', 'humidity', 'battery'].map(name => ({ name, value: String(step) }))
          : []
      }))
    }))
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes, edges: [], pan: { x: 0, y: 0 }, zoom: 1,
        highlightedTrace: { states, selectedStateIndex: stepCount - 1 },
        getNodeIcon: () => '', hasNodeStateMachine: () => true,
        getNodeEffectiveState: node => node.state
      },
      global: { plugins: [i18n] }
    })
    try {
      const chips = wrapper.findAll('.device-runtime-chip')
      expect(chips).toHaveLength(deviceCount * 3)
      expect(chips.every(chip => chip.attributes('title')?.includes('0 → 23'))).toBe(true)
      expect(deviceIdReads).toBeLessThanOrEqual(deviceCount * stepCount * 4)
      deviceIdReads = 0
      await wrapper.setProps({ pan: { x: 100, y: 100 } })
      expect(deviceIdReads).toBe(0)
    } finally {
      wrapper.unmount()
    }
  })

  it('renders the incoming state without an empty phase during a real cross-fade', async () => {
    const frames = new Map<number, FrameRequestCallback>()
    let nextFrame = 0
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      frames.set(++nextFrame, callback)
      return nextFrame
    })
    vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
    const node = reactive({
      id: 'light-1', templateName: 'Light', label: 'Hall light',
      position: { x: 40, y: 50 }, state: 'off', width: 176, height: 128
    })
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [node], edges: [], pan: { x: 0, y: 0 }, zoom: 1,
        getNodeIcon: () => '', hasNodeStateMachine: () => true,
        getNodeEffectiveState: current => current.state
      },
      global: { plugins: [i18n], stubs: { transition: false } }
    })
    try {
      node.state = 'on'
      await nextTick()
      const readings = wrapper.get('.device-reading-slot')
      expect(readings.findAll('.device-state-value').map(reading => reading.text())).toContain('on')
      expect(readings.get('.trace-device-state-enter-from').text()).toBe('on')
      for (const time of [0, 17]) {
        const callbacks = [...frames.values()]
        frames.clear()
        callbacks.forEach(callback => callback(time))
      }
      await nextTick()
      expect(readings.text()).toBe('on')
    } finally {
      wrapper.unmount()
      vi.unstubAllGlobals()
    }
  })

  for (const corner of ['tl', 'tr', 'bl', 'br']) {
    it(`updates the self-loop path and hitarea during ${corner} resizing and restores them on cancel`, async () => {
      const node = reactive({
        id: 'light-1', templateName: 'Light', label: 'Hall light',
        position: { x: 40, y: 50 }, state: 'off', width: 176, height: 128
      })
      const wrapper = mount(CanvasBoard, {
        props: {
          nodes: [node], edges: [{
            id: 'self', from: node.id, to: node.id, fromLabel: node.label, toLabel: node.label,
            fromPos: node.position, toPos: node.position
          }], pan: { x: 0, y: 0 }, zoom: 1.5,
          getNodeIcon: () => '', hasNodeStateMachine: () => true,
          getNodeEffectiveState: current => current.state
        },
        global: { plugins: [i18n] }
      })
      try {
        const originalPath = getSelfLoopPath(node)
        wrapper.get(`.resize-handle.${corner}`).element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
        window.dispatchEvent(pointerEvent('pointermove', 130, 115))
        await nextTick()
        expect(wrapper.get('path.edge-base-line').attributes('d')).not.toBe(originalPath)
        expect(wrapper.get('path.edge-base-line').attributes('d')).toBe(getSelfLoopPath(node))
        expect(wrapper.get('path.edge-hitarea').attributes('d')).toBe(getSelfLoopPath(node))
        window.dispatchEvent(pointerEvent('pointercancel', 130, 115))
        await nextTick()
        expect(wrapper.get('path.edge-base-line').attributes('d')).toBe(originalPath)
        expect(node).toMatchObject({ position: { x: 40, y: 50 }, width: 176, height: 128 })
        expect(wrapper.emitted('node-moved-or-resized')).toBeUndefined()
      } finally {
        wrapper.unmount()
      }
    })
  }

  for (const zoom of [0.4, 1.5]) {
    for (const finish of ['pointerup', 'pointercancel', 'lostpointercapture', 'lock'] as const) {
      it(`moves every self-loop layer and each full edge detail before ${finish} at zoom ${zoom}`, async () => {
        const source = reactive({
          id: 'light-1', templateName: 'Light', label: 'Hall light',
          position: { x: 40, y: 50 }, state: 'off', width: 176, height: 128
        })
        const target = reactive({
          ...source, id: 'light-2', label: 'Other light', position: { x: 420, y: 220 }
        })
        const edges = [source, target].map((node, index) => ({
          id: `edge-${index}`, from: source.id, to: node.id,
          fromLabel: source.label, toLabel: node.label,
          fromPos: source.position, toPos: node.position,
          ruleId: `rule-${index}`, ruleIndex: index, toApi: 'turn on'
        }))
        const wrapper = mount(CanvasBoard, {
          props: {
            nodes: [source, target], edges, pan: { x: 350, y: 350 }, zoom,
            highlightedTrace: { selectedStateIndex: 0, states: [{
              devices: [], triggeredRules: [{ ruleId: 'rule-0', ruleIndex: 0 }]
            }] },
            getNodeIcon: () => '', hasNodeStateMachine: () => true,
            getNodeEffectiveState: node => node.state || 'off'
          },
          global: { plugins: [i18n] }
        })
        try {
          Object.defineProperty(wrapper.get('[data-testid="canvas-board"]').element, 'clientWidth', { value: 2000 })
          window.dispatchEvent(new Event('resize'))
          const nodeElement = wrapper.get('[data-node-id="light-1"]')
          const loopHitarea = wrapper.get('path.edge-hitarea')
          await loopHitarea.trigger('focus')
          await wrapper.get('line.edge-hitarea').trigger('focus')
          const originalPath = getSelfLoopPath(source)
          expect(wrapper.get('path.edge-base-line').attributes('d')).toBe(originalPath)
          nodeElement.element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
          window.dispatchEvent(pointerEvent('pointermove', 112, 108))
          await nextAnimationFrame()
          window.dispatchEvent(pointerEvent('pointermove', 140, 120))
          await nextAnimationFrame()

          const visualNode = { ...source, position: { x: 40 + 40 / zoom, y: 50 + 20 / zoom } }
          for (const selector of ['path.edge-base-line', 'path.edge-hitarea', 'path.particle-line']) {
            expect(wrapper.get(selector).attributes('d')).toBe(getSelfLoopPath(visualNode))
          }
          const points = getLinkPoints(visualNode, target)
          expect(Number(wrapper.get('line.edge-base-line').attributes('x1'))).toBe(points.fromPoint.x)
          expect(Number(wrapper.get('line.edge-base-line').attributes('y1'))).toBe(points.fromPoint.y)
          const label = wrapper.get<HTMLElement>('[data-testid="canvas-edge-details"]')
          expect(label.attributes('data-edge-id')).toBe('edge-1')
          expect(Number.parseFloat(label.element.style.left)).toBeCloseTo((points.fromPoint.x + points.toPoint.x) / 2 * zoom + 350 + 16)
          expect(Number.parseFloat(label.element.style.top)).toBeCloseTo(((points.fromPoint.y + points.toPoint.y) / 2 - 10) * zoom + 350 - 12)
          await loopHitarea.trigger('focus')
          expect(label.attributes('data-edge-id')).toBe('edge-0')
          expect(Number.parseFloat(label.element.style.left)).toBeCloseTo((visualNode.position.x + source.width / 2) * zoom + 350 + 16)
          expect(Number.parseFloat(label.element.style.top)).toBeCloseTo((visualNode.position.y - 16) * zoom + 350 - 12)
          // Rendering a preview must not persist intermediate coordinates or create undo entries.
          expect(source.position).toEqual({ x: 40, y: 50 })
          expect(wrapper.emitted('node-moved-or-resized')).toBeUndefined()

          if (finish === 'lock') await wrapper.setProps({ interactionLocked: true })
          else if (finish === 'lostpointercapture') nodeElement.element.dispatchEvent(pointerEvent(finish, 140, 120))
          else window.dispatchEvent(pointerEvent(finish, 140, 120))
          await nextTick()
          if (finish === 'pointerup') {
            expect(source.position).toEqual(visualNode.position)
            expect(wrapper.emitted('node-moved-or-resized')).toEqual([[source.id]])
          } else {
            expect(source.position).toEqual({ x: 40, y: 50 })
            expect(wrapper.get('path.edge-base-line').attributes('d')).toBe(originalPath)
            expect(wrapper.emitted('node-moved-or-resized')).toBeUndefined()
          }
          expect(wrapper.emitted('node-layout-interaction-end')).toEqual([[source.id]])
        } finally {
          wrapper.unmount()
        }
      })
    }
  }
})

describe('CanvasBoard drag scheduling and presentation', () => {
  const node = () => reactive({
    id: 'light-1', templateName: 'Light', label: 'Hall light', state: 'off',
    position: { x: 40, y: 50 }, width: 176, height: 128,
    variables: [{ name: 'temperature', value: '21' }]
  })

  it('updates zoom-dependent content without rewriting unchanged node styles', async () => {
    const source = node()
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [source], edges: [], pan: { x: 0, y: 0 }, zoom: 0.5,
        getNodeIcon: () => '', getNodeEffectiveState: current => current.state,
        hasNodeStateMachine: () => true
      }, global: { plugins: [i18n] }
    })
    const rendered = wrapper.get<HTMLElement>('[data-node-id="light-1"]')
    const content = rendered.get<HTMLElement>('.device-node-content')
    const nodeStyleWrites = vi.spyOn(rendered.element.style, 'setProperty')
    const contentStyleWrites = vi.spyOn(content.element.style, 'setProperty')
    try {
      await wrapper.setProps({ zoom: 0.6 })
      expect(rendered.classes()).toContain('device-node--condensed')
      expect(content.element.style.getPropertyValue('--node-ui-scale')).toBe('0.6')
      expect(nodeStyleWrites).not.toHaveBeenCalled()
      expect(rendered.element.style.left).toBe('40px')
      expect(rendered.element.style.width).toBe('176px')
      await wrapper.setProps({ zoom: 1.1 })
      contentStyleWrites.mockClear()
      await wrapper.setProps({ zoom: 1.2 })
      expect(nodeStyleWrites).not.toHaveBeenCalled()
      expect(contentStyleWrites).not.toHaveBeenCalled()
      expect(content.element.style.getPropertyValue('--node-ui-scale')).toBe('1')
      expect(Number.parseFloat(rendered.get<HTMLElement>('.resize-handle.br').element.style.width) * 1.2)
        .toBeCloseTo(44)
    } finally {
      nodeStyleWrites.mockRestore()
      contentStyleWrites.mockRestore()
      wrapper.unmount()
    }
  })

  it('moves and scales the grid with non-inherited background properties', async () => {
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [], edges: [], pan: { x: 70, y: -30 }, zoom: 0.4,
        getNodeIcon: () => '', getNodeEffectiveState: current => current.state,
        hasNodeStateMachine: () => true
      }, global: { plugins: [i18n] }
    })
    try {
      const canvas = wrapper.get<HTMLElement>('[data-testid="canvas-board"]')
      const expectGrid = (size: number, x: number, y: number) => {
        expect(canvas.element.style.backgroundSize).toBe(`${size}px ${size}px`)
        const position = canvas.element.style.backgroundPosition.split(' ').map(Number.parseFloat)
        expect(position[0]).toBeCloseTo(x)
        expect(position[1]).toBeCloseTo(y)
        expect(canvas.attributes('style')).not.toContain('--canvas-grid-')
      }
      expectGrid(12.8, 6, 8.4)
      await wrapper.setProps({ pan: { x: -9, y: 17 }, zoom: 0.25 })
      expectGrid(8, 7, 1)
      await wrapper.setProps({ zoom: 2 })
      expectGrid(64, 55, 17)
    } finally { wrapper.unmount() }
  })

  it.each([0.2, 0.5, 1, 1.25, 2])('keeps resize geometry in direct properties without inherited zoom variables at zoom %s', zoom => {
    const source = node()
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [source], edges: [], pan: { x: 0, y: 0 }, zoom,
        getNodeIcon: () => '', getNodeEffectiveState: current => current.state,
        hasNodeStateMachine: () => true
      }, global: { plugins: [i18n] }
    })
    try {
      const rendered = wrapper.get<HTMLElement>('[data-node-id="light-1"]')
      const content = rendered.get<HTMLElement>('.device-node-content')
      expect(content.element.style.getPropertyValue('--node-ui-scale')).toBe(String(Math.min(zoom, 1)))
      expect(content.element.style.getPropertyValue('--node-name-lines')).toBe(source.height * zoom >= 60 ? '3' : '2')
      for (const ancestor of [rendered, wrapper.get<HTMLElement>('.canvas-inner')]) {
        expect(ancestor.element.style.getPropertyValue('--canvas-zoom')).toBe('')
        expect(ancestor.element.style.getPropertyValue('--node-ui-scale')).toBe('')
        expect(ancestor.element.style.getPropertyValue('--node-name-lines')).toBe('')
        expect(ancestor.attributes('style')).not.toContain('--resize-')
      }
      const handles = rendered.findAll<HTMLElement>('.resize-handle')
      expect(handles).toHaveLength(Math.min(source.width, source.height) * zoom >= 88 ? 4 : 1)
      const inward = Math.min(22, Math.min(source.width, source.height) * zoom * 0.35)
      for (const handle of handles) {
        expect(Number.parseFloat(handle.element.style.width) * zoom).toBeCloseTo(44)
        expect(Number.parseFloat(handle.element.style.height) * zoom).toBeCloseTo(44)
        for (const margin of ['marginTop', 'marginRight', 'marginBottom', 'marginLeft'] as const) {
          expect(Number.parseFloat(handle.element.style[margin]) * zoom).toBeCloseTo(-(44 - inward))
        }
        expect(handle.attributes('style')).not.toContain('--resize-')
      }
    } finally { wrapper.unmount() }
  })

  it('measures the edge viewport on resize without reading layout during pan and zoom', async () => {
    let resize = () => {}
    const observe = vi.fn()
    const disconnect = vi.fn()
    vi.stubGlobal('ResizeObserver', class {
      constructor(callback: () => void) { resize = callback }
      observe = observe
      disconnect = disconnect
    })
    const width = vi.spyOn(Element.prototype, 'clientWidth', 'get').mockReturnValue(1200)
    const height = vi.spyOn(Element.prototype, 'clientHeight', 'get').mockReturnValue(800)
    const style = vi.spyOn(window, 'getComputedStyle')
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [], edges: [], pan: { x: 0, y: 0 }, zoom: 1,
        getNodeIcon: () => '', getNodeEffectiveState: current => current.state,
        hasNodeStateMachine: () => true
      }, global: { plugins: [i18n] }
    })
    let unmounted = false
    try {
      expect(observe).toHaveBeenCalledWith(wrapper.get('.canvas').element)
      width.mockClear()
      height.mockClear()
      style.mockClear()
      await wrapper.setProps({ pan: { x: 30, y: -20 }, zoom: 0.5 })
      expect(width).not.toHaveBeenCalled()
      expect(height).not.toHaveBeenCalled()
      expect(style).not.toHaveBeenCalled()
      resize()
      window.dispatchEvent(new Event('resize'))
      expect(width).toHaveBeenCalledTimes(2)
      expect(height).toHaveBeenCalledTimes(2)
      expect(style).toHaveBeenCalledTimes(2)
      wrapper.unmount()
      unmounted = true
      expect(disconnect).toHaveBeenCalledOnce()
      width.mockClear()
      window.dispatchEvent(new Event('resize'))
      expect(width).not.toHaveBeenCalled()
    } finally {
      if (!unmounted) wrapper.unmount()
      width.mockRestore()
      height.mockRestore()
      style.mockRestore()
      vi.unstubAllGlobals()
    }
  })

  it('reuses model wording and icons across zoom, size, focus and lock changes while tracking reactive callback inputs', async () => {
    const source = node()
    const display = reactive({ icon: '/off.svg', prefix: '' })
    const getState = vi.fn((current: DeviceNode) => current.state)
    const getIcon = vi.fn(() => display.icon)
    const formatToken = vi.fn((_node: DeviceNode, value: unknown) => `${display.prefix}${String(value)}`)
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [source], edges: [], pan: { x: 0, y: 0 }, zoom: 1,
        getNodeIcon: getIcon, getNodeEffectiveState: getState,
        hasNodeStateMachine: () => true, formatNodeModelToken: formatToken
      }, global: { plugins: [i18n] }
    })
    try {
      const rendered = wrapper.get<HTMLElement>('[data-node-id="light-1"]')
      getState.mockClear()
      getIcon.mockClear()
      formatToken.mockClear()
      await wrapper.setProps({ zoom: 0.5 })
      expect(rendered.classes()).toContain('device-node--compact')
      await wrapper.setProps({ focusedNodeId: source.id, interactionLocked: true })
      expect(rendered.classes()).toContain('node-focused')
      expect(rendered.attributes('aria-disabled')).toBe('true')
      source.width = 480
      source.height = 300
      await nextTick()
      expect(rendered.classes()).toContain('device-node--expanded')
      expect(rendered.element.style.width).toBe('480px')
      expect(rendered.findAll('.resize-handle')).toHaveLength(0)
      expect(getState).not.toHaveBeenCalled()
      expect(getIcon).not.toHaveBeenCalled()
      expect(formatToken).not.toHaveBeenCalled()

      source.state = 'on'
      source.variables[0].value = '32'
      await nextTick()
      expect(rendered.get('.device-state-value').text()).toBe('on')
      expect(rendered.get('.device-runtime-chip__value').text()).toBe('32')
      display.icon = '/on.svg'
      await nextTick()
      expect(rendered.get('img').attributes('src')).toBe('/on.svg')
      display.prefix = 'display:'
      await nextTick()
      expect(rendered.get('.device-state-value').text()).toBe('display:on')
      expect(rendered.get('.device-runtime-chip__value').text()).toBe('display:32')
      expect(rendered.attributes('aria-label')).toContain('display:on')
      expect(getState).toHaveBeenCalled()
      expect(getIcon).toHaveBeenCalled()
      expect(formatToken).toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })

  it('paints the latest sample once per frame without recomputing presentation and flushes a quick release', async () => {
    const frames = controlAnimationFrames()
    const source = node()
    const getState = vi.fn((current: DeviceNode) => current.state)
    const getIcon = vi.fn(() => '/light.svg')
    const formatToken = vi.fn((_node: DeviceNode, value: unknown) => String(value))
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [source], edges: [], pan: { x: 0, y: 0 }, zoom: 0.4,
        getNodeIcon: getIcon, getNodeEffectiveState: getState,
        hasNodeStateMachine: () => true, formatNodeModelToken: formatToken
      }, global: { plugins: [i18n] }
    })
    try {
      const rendered = wrapper.get<HTMLElement>('[data-node-id="light-1"]')
      rendered.element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
      await nextTick()
      getState.mockClear()
      getIcon.mockClear()
      formatToken.mockClear()
      for (const x of [112, 124, 140]) window.dispatchEvent(pointerEvent('pointermove', x, 120))
      await nextTick()
      expect(frames.pending()).toBe(1)
      expect(rendered.element.style.left).toBe('40px')
      expect(source.position).toEqual({ x: 40, y: 50 })
      await frames.flush()
      expect(rendered.element.style.left).toBe('140px')
      expect(rendered.element.style.top).toBe('100px')
      expect(getState).not.toHaveBeenCalled()
      expect(getIcon).not.toHaveBeenCalled()
      expect(formatToken).not.toHaveBeenCalled()
      expect(wrapper.emitted('node-moved-or-resized')).toBeUndefined()

      window.dispatchEvent(pointerEvent('pointermove', 180, 128))
      window.dispatchEvent(pointerEvent('pointermove', 200, 132))
      expect(frames.pending()).toBe(1)
      window.dispatchEvent(pointerEvent('pointerup', 200, 132))
      await nextTick()
      expect(source.position).toEqual({ x: 290, y: 130 })
      expect(rendered.element.style.left).toBe('290px')
      expect(wrapper.emitted('node-moved-or-resized')).toEqual([[source.id]])
      expect(wrapper.emitted('node-layout-interaction-end')).toEqual([[source.id]])
      expect(frames.pending()).toBe(0)
      await frames.flush()
      expect(source.position).toEqual({ x: 290, y: 130 })
    } finally {
      wrapper.unmount()
      vi.unstubAllGlobals()
    }
  })

  it.each([0.5, 1, 1.5].flatMap(zoom => [true, false].map(moved => [zoom, moved] as const)))(
    'commits release coordinates at zoom %s with a preceding move: %s', async (zoom, moved) => {
      const frames = controlAnimationFrames()
      const source = node()
      const wrapper = mount(CanvasBoard, {
        props: {
          nodes: [source], edges: [], pan: { x: 0, y: 0 }, zoom,
          getNodeIcon: () => '', getNodeEffectiveState: current => current.state,
          hasNodeStateMachine: () => true
        }, global: { plugins: [i18n] }
      })
      try {
        const rendered = wrapper.get<HTMLElement>('[data-node-id="light-1"]')
        rendered.element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
        if (moved) {
          window.dispatchEvent(pointerEvent('pointermove', 120, 110))
          await frames.flush()
        }
        expect(source.position).toEqual({ x: 40, y: 50 })
        expect(wrapper.emitted('node-moved-or-resized')).toBeUndefined()
        window.dispatchEvent(pointerEvent('pointerup', 160, 140))
        await nextTick()
        expect(source.position).toEqual({ x: 40 + 60 / zoom, y: 50 + 40 / zoom })
        expect(Number.parseFloat(rendered.element.style.left)).toBeCloseTo(source.position.x)
        expect(Number.parseFloat(rendered.element.style.top)).toBeCloseTo(source.position.y)
        expect(wrapper.emitted('node-moved-or-resized')).toEqual([[source.id]])
        expect(wrapper.emitted('node-open')).toBeUndefined()
        expect(wrapper.emitted('node-layout-interaction-end')).toEqual([[source.id]])
        expect(frames.pending()).toBe(0)
        await frames.flush()
        expect(source.position).toEqual({ x: 40 + 60 / zoom, y: 50 + 40 / zoom })
      } finally {
        wrapper.unmount()
        vi.unstubAllGlobals()
      }
    }
  )

  it('keeps a release below the drag threshold as a click', async () => {
    const frames = controlAnimationFrames()
    const source = node()
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [source], edges: [], pan: { x: 0, y: 0 }, zoom: 1,
        getNodeIcon: () => '', getNodeEffectiveState: current => current.state,
        hasNodeStateMachine: () => true
      }, global: { plugins: [i18n] }
    })
    try {
      wrapper.get('[data-node-id="light-1"]').element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
      window.dispatchEvent(pointerEvent('pointerup', 103, 102))
      expect(source.position).toEqual({ x: 40, y: 50 })
      expect(wrapper.emitted('node-open')).toEqual([[source]])
      expect(wrapper.emitted('node-moved-or-resized')).toBeUndefined()
      expect(frames.pending()).toBe(0)
    } finally {
      wrapper.unmount()
      vi.unstubAllGlobals()
    }
  })

  it.each([
    { zoom: 0.5, corner: 'br', x: 40, y: 50, width: 276, height: 188 },
    { zoom: 1, corner: 'tl', x: 90, y: 80, width: 126, height: 98 },
    { zoom: 1, corner: 'tr', x: 40, y: 80, width: 226, height: 98 },
    { zoom: 1, corner: 'bl', x: 90, y: 50, width: 126, height: 158 },
    { zoom: 1, corner: 'br', x: 40, y: 50, width: 226, height: 158 },
    { zoom: 1.5, corner: 'br', x: 40, y: 50, width: 209, height: 148 }
  ])('commits release coordinates for the $corner resize handle at zoom $zoom', async item => {
    const source = node()
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [source], edges: [{
          id: 'self', from: source.id, to: source.id, fromLabel: source.label, toLabel: source.label,
          fromPos: source.position, toPos: source.position
        }], pan: { x: 0, y: 0 }, zoom: item.zoom,
        getNodeIcon: () => '', getNodeEffectiveState: current => current.state,
        hasNodeStateMachine: () => true
      }, global: { plugins: [i18n] }
    })
    try {
      wrapper.get(`.resize-handle.${item.corner}`).element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
      window.dispatchEvent(pointerEvent('pointermove', 120, 110))
      await nextTick()
      expect(wrapper.emitted('node-moved-or-resized')).toBeUndefined()
      window.dispatchEvent(pointerEvent('pointerup', 150, 130))
      await nextTick()
      expect(source).toMatchObject({
        position: { x: item.x, y: item.y }, width: item.width, height: item.height
      })
      expect(wrapper.get('path.edge-base-line').attributes('d')).toBe(getSelfLoopPath(source))
      expect(wrapper.get('path.edge-hitarea').attributes('d')).toBe(getSelfLoopPath(source))
      expect(wrapper.emitted('node-moved-or-resized')).toEqual([[source.id]])
      expect(wrapper.emitted('node-layout-interaction-end')).toEqual([[source.id]])
    } finally { wrapper.unmount() }
  })

  it('does not reinterpret a stationary resize release after zoom changes', async () => {
    const source = node()
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [source], edges: [], pan: { x: 0, y: 0 }, zoom: 0.5,
        getNodeIcon: () => '', getNodeEffectiveState: current => current.state,
        hasNodeStateMachine: () => true
      }, global: { plugins: [i18n] }
    })
    try {
      wrapper.get('.resize-handle.br').element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
      window.dispatchEvent(pointerEvent('pointermove', 120, 110))
      expect(source).toMatchObject({ width: 216, height: 148 })
      await wrapper.setProps({ zoom: 2 })
      window.dispatchEvent(pointerEvent('pointerup', 120, 110))
      expect(source).toMatchObject({ position: { x: 40, y: 50 }, width: 216, height: 148 })
      expect(wrapper.emitted('node-moved-or-resized')).toEqual([[source.id]])
    } finally { wrapper.unmount() }
  })

  it.each(['queued', 'painted'])('keeps the %s pointer sample in the zoom at which it was received', async phase => {
    const frames = controlAnimationFrames()
    const source = node()
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [source], edges: [], pan: { x: 0, y: 0 }, zoom: 0.4,
        getNodeIcon: () => '/light.svg', getNodeEffectiveState: current => current.state,
        hasNodeStateMachine: () => true
      }, global: { plugins: [i18n] }
    })
    try {
      wrapper.get('[data-node-id="light-1"]').element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
      window.dispatchEvent(pointerEvent('pointermove', 140, 120))
      if (phase === 'painted') await frames.flush()
      await wrapper.setProps({ zoom: 2 })
      await frames.flush()
      const rendered = wrapper.get<HTMLElement>('[data-node-id="light-1"]')
      expect(rendered.element.style.left).toBe('140px')
      expect(rendered.element.style.top).toBe('100px')
      window.dispatchEvent(pointerEvent('pointerup', 140, 120))
      expect(source.position).toEqual({ x: 140, y: 100 })
    } finally {
      wrapper.unmount()
      vi.unstubAllGlobals()
    }
  })

  it.each((['pointercancel', 'lostpointercapture', 'lock', 'unmount'] as const)
    .flatMap(finish => ['queued', 'painted'].map(phase => [finish, phase] as const)))(
    'discards the %s interaction with a %s preview without saving or leaving a frame behind', async (finish, phase) => {
      const frames = controlAnimationFrames()
      const source = node()
      const wrapper = mount(CanvasBoard, {
        props: {
          nodes: [source], edges: [], pan: { x: 0, y: 0 }, zoom: 1,
          getNodeIcon: () => '/light.svg', getNodeEffectiveState: current => current.state,
          hasNodeStateMachine: () => true
        }, global: { plugins: [i18n] }
      })
      let unmounted = false
      try {
        const rendered = wrapper.get<HTMLElement>('[data-node-id="light-1"]')
        rendered.element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
        window.dispatchEvent(pointerEvent('pointermove', 140, 120))
        if (phase === 'painted') {
          await frames.flush()
          expect(rendered.element.style.left).toBe('80px')
          expect(rendered.element.style.top).toBe('70px')
          window.dispatchEvent(pointerEvent('pointermove', 160, 140))
        }
        expect(frames.pending()).toBe(1)
        if (finish === 'lock') await wrapper.setProps({ interactionLocked: true })
        else if (finish === 'unmount') { wrapper.unmount(); unmounted = true }
        else if (finish === 'lostpointercapture') rendered.element.dispatchEvent(pointerEvent(finish, 140, 120))
        else window.dispatchEvent(pointerEvent(finish, 140, 120))
        expect(frames.pending()).toBe(0)
        await frames.flush()
        expect(source.position).toEqual({ x: 40, y: 50 })
        if (!unmounted) {
          expect(rendered.element.style.left).toBe('40px')
          expect(rendered.element.style.top).toBe('50px')
        }
        expect(wrapper.emitted('node-moved-or-resized')).toBeUndefined()
        expect(wrapper.emitted('node-layout-interaction-end')).toEqual([[source.id]])
      } finally {
        if (!unmounted) wrapper.unmount()
        vi.unstubAllGlobals()
      }
    }
  )

  it.each(['drag', 'resize'] as const)('rejects context menus while a %s pointer owns the layout', async gesture => {
    const frames = controlAnimationFrames()
    const source = node()
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [source], edges: [], pan: { x: 0, y: 0 }, zoom: 1,
        getNodeIcon: () => '', getNodeEffectiveState: current => current.state,
        hasNodeStateMachine: () => true
      }, global: { plugins: [i18n] }
    })
    try {
      const rendered = wrapper.get('[data-node-id="light-1"]')
      const target = gesture === 'drag' ? rendered : rendered.get('.resize-handle.br')
      target.element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
      window.dispatchEvent(pointerEvent('pointermove', 130, 115))
      await frames.flush()
      await rendered.trigger('contextmenu', { button: 2, clientX: 130, clientY: 115 })
      expect(wrapper.emitted('node-context')).toBeUndefined()
      window.dispatchEvent(pointerEvent('pointercancel', 130, 115))
      await nextTick()
      expect(source).toMatchObject({ position: { x: 40, y: 50 }, width: 176, height: 128 })
      expect(wrapper.emitted('node-moved-or-resized')).toBeUndefined()
      await rendered.trigger('contextmenu', { button: 2, clientX: 130, clientY: 115 })
      expect(wrapper.emitted('node-context')).toEqual([[source, { x: 130, y: 115 }]])
    } finally {
      wrapper.unmount()
      vi.unstubAllGlobals()
    }
  })

  it.each((['drag', 'resize'] as const).flatMap(gesture =>
    (['pointerup', 'pointercancel'] as const).flatMap(finish => [
      { key: 'ArrowRight' },
      { key: 'ArrowDown', ctrlKey: true },
      { key: 'Delete' },
      { key: 'Backspace' },
      { key: 'Enter' },
      { key: ' ' },
      { key: 'ContextMenu' },
      { key: 'F10', shiftKey: true }
    ].map(command => ({ gesture, finish, ...command })))))(
    'keeps $key from interrupting a held $gesture before $finish', async ({ gesture, finish, ...command }) => {
      const frames = controlAnimationFrames()
      const source = node()
      const other = node()
      other.id = 'light-2'
      other.position = { x: 300, y: 80 }
      const wrapper = mount(CanvasBoard, {
        props: {
          nodes: [source, other], edges: [], pan: { x: 0, y: 0 }, zoom: 1,
          getNodeIcon: () => '', getNodeEffectiveState: current => current.state,
          hasNodeStateMachine: () => true
        }, global: { plugins: [i18n] }
      })
      const geometry = (current: DeviceNode) => ({
        x: current.position.x, y: current.position.y, width: current.width, height: current.height
      })
      try {
        const rendered = wrapper.get<HTMLElement>('[data-node-id="light-1"]')
        const target = gesture === 'drag' ? rendered : rendered.get('.resize-handle.br')
        target.element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
        window.dispatchEvent(pointerEvent('pointermove', 130, 115))
        await frames.flush()
        const preview = geometry(source)
        const untouched = geometry(other)
        expect(rendered.element.style.left).toBe(gesture === 'drag' ? '70px' : '40px')
        expect(preview.width).toBe(gesture === 'resize' ? 206 : 176)
        for (const id of [source.id, other.id]) {
          const event = new KeyboardEvent('keydown', { ...command, bubbles: true, cancelable: true })
          wrapper.get(`[data-node-id="${id}"]`).element.dispatchEvent(event)
          await nextTick()
          expect(event.defaultPrevented).toBe(true)
          expect(geometry(source)).toEqual(preview)
          expect(geometry(other)).toEqual(untouched)
          for (const name of ['node-moved-or-resized', 'node-open', 'node-delete', 'node-context']) {
            expect(wrapper.emitted(name)).toBeUndefined()
          }
        }
        window.dispatchEvent(pointerEvent(finish, 130, 115))
        await nextTick()
        expect(geometry(source)).toEqual(finish === 'pointercancel'
          ? { x: 40, y: 50, width: 176, height: 128 }
          : gesture === 'drag'
            ? { x: 70, y: 65, width: 176, height: 128 }
            : { x: 40, y: 50, width: 206, height: 143 })
        expect(wrapper.emitted('node-moved-or-resized') || []).toEqual(finish === 'pointerup' ? [[source.id]] : [])
        expect(wrapper.emitted('node-layout-interaction-end')).toEqual([[source.id]])
        const released = geometry(source)
        await rendered.trigger('keydown', { key: 'ArrowRight' })
        expect(geometry(source)).toEqual({ ...released, x: released.x + 10 })
        expect(wrapper.emitted('node-moved-or-resized')).toHaveLength(finish === 'pointerup' ? 2 : 1)
      } finally {
        wrapper.unmount()
        vi.unstubAllGlobals()
      }
    }
  )

  it('updates memoized content for runtime edits, locale, density, focus and read-only changes', async () => {
    const source = node()
    const originalLocale = i18n.global.locale.value
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [source], edges: [], pan: { x: 0, y: 0 }, zoom: 1,
        getNodeIcon: current => `/${current.state}.svg`,
        getNodeEffectiveState: current => current.state, hasNodeStateMachine: () => true
      }, global: { plugins: [i18n] }
    })
    try {
      const rendered = wrapper.get<HTMLElement>('[data-node-id="light-1"]')
      source.label = 'Updated light'
      source.state = 'on'
      source.variables[0].value = '32'
      await nextTick()
      expect(rendered.get('.device-label').text()).toBe('Updated light')
      expect(rendered.get('.device-state-value').text()).toBe('on')
      expect(rendered.get('img').attributes('src')).toBe('/on.svg')
      expect(rendered.get('.device-runtime-chip__value').text()).toBe('32')
      expect(rendered.attributes('aria-label')).toContain('Updated light')
      i18n.global.locale.value = 'en'
      await nextTick()
      const englishLabel = rendered.attributes('aria-label')
      i18n.global.locale.value = 'zh-CN'
      await nextTick()
      expect(rendered.attributes('aria-label')).not.toBe(englishLabel)
      expect(rendered.attributes('aria-label')).toContain(i18n.global.t('app.state'))
      await wrapper.setProps({ zoom: 0.4, focusedNodeId: source.id, interactionLocked: true })
      expect(rendered.classes()).toContain('device-node--compact')
      expect(rendered.classes()).toContain('node-focused')
      expect(rendered.attributes('aria-disabled')).toBe('true')
      expect(rendered.findAll('.resize-handle')).toHaveLength(0)
      await wrapper.setProps({ interactionLocked: false, getNodeIcon: () => '/updated.svg' })
      expect(rendered.attributes('aria-disabled')).toBeUndefined()
      expect(rendered.get('img').attributes('src')).toBe('/updated.svg')
      expect(rendered.findAll('.resize-handle')).toHaveLength(1)
    } finally {
      wrapper.unmount()
      i18n.global.locale.value = originalLocale
    }
  })
})

describe('CanvasBoard device context actions', () => {
  it('keeps a compromised device marker outside the clipped card content', async () => {
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [{
          id: 'light-1', templateName: 'Light', label: 'Hall light',
          position: { x: 40, y: 50 }, state: 'off', width: 176, height: 128
        }],
        edges: [], pan: { x: 0, y: 0 }, zoom: 1,
        highlightedTrace: { selectedStateIndex: 0, states: [{
          devices: [{ deviceId: 'light_1', state: 'off', compromised: true }]
        }] },
        getNodeIcon: () => '', hasNodeStateMachine: () => true,
        getNodeEffectiveState: node => node.state || 'off'
      },
      global: { plugins: [i18n] }
    })
    try {
      const card = wrapper.get('[data-node-id="light-1"]')
      expect(card.classes()).toContain('device-attacked')
      expect(card.get('.attack-indicator').element.parentElement).toBe(card.element)
      expect(card.get('.device-node-content').find('.attack-indicator').exists()).toBe(false)
      await wrapper.setProps({ highlightedTrace: null })
      expect(card.find('.attack-indicator').exists()).toBe(false)
    } finally {
      wrapper.unmount()
    }
  })

  it('opens the custom device menu on mouse right-click', async () => {
    const node = {
      id: 'light-1',
      templateName: 'Light',
      label: 'Hall light',
      position: { x: 40, y: 50 },
      state: 'off',
      width: 176,
      height: 128
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [node],
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 1,
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'Working'
      },
      global: { plugins: [i18n] }
    })

    await wrapper.get('[data-node-id="light-1"]').trigger('contextmenu', {
      clientX: 240,
      clientY: 180
    })

    expect(wrapper.emitted('node-context')).toEqual([[node, { x: 240, y: 180 }]])
    expect(wrapper.get('.device-label').attributes('title')).toBe('Hall light')

    await wrapper.get('[data-node-id="light-1"]').trigger('keydown', {
      key: 'F10',
      shiftKey: true
    })
    expect(wrapper.emitted('node-context')).toHaveLength(2)
    const keyboardPayload = wrapper.emitted('node-context')?.[1]
    expect(keyboardPayload?.[0]).toStrictEqual(node)
    expect(keyboardPayload?.[1]).toEqual({ x: expect.any(Number), y: expect.any(Number) })
    wrapper.unmount()
  })

  it('keeps rule labels hidden until the edge is hovered or keyboard-focused', async () => {
    const source = {
      id: 'motion-1',
      templateName: 'Motion Detector',
      label: 'Hall motion',
      position: { x: 40, y: 50 },
      state: 'idle',
      width: 176,
      height: 128
    }
    const target = {
      id: 'camera-1',
      templateName: 'Camera',
      label: 'Hall camera',
      position: { x: 420, y: 220 },
      state: 'on',
      width: 176,
      height: 128
    }
    const edge = {
      id: 'rule-1-source-0',
      from: source.id,
      to: target.id,
      fromLabel: source.label,
      toLabel: target.label,
      fromPos: source.position,
      toPos: target.position,
      fromApi: 'state',
      toApi: 'take photo',
      itemType: 'variable' as const,
      relation: 'in',
      value: 'active',
      ruleId: 'rule-1'
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [source, target],
        edges: [edge],
        pan: { x: 0, y: 0 },
        zoom: 1,
        focusedRuleId: 'rule-1',
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'Working',
        formatNodeModelToken: (_node, value) => ({
          state: '状态',
          active: '活动',
          'take photo': '拍照'
        }[String(value)] || String(value ?? ''))
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.find('.edge-label').exists()).toBe(false)

    const hitarea = wrapper.get('.edge-hitarea')
    vi.useFakeTimers()
    await hitarea.trigger('pointerenter')
    expect(wrapper.find('.edge-label').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(400)
    expect(wrapper.find('.edge-label').exists()).toBe(true)

    await hitarea.trigger('pointerleave')
    await vi.advanceTimersByTimeAsync(120)
    expect(wrapper.find('.edge-label').exists()).toBe(false)

    await hitarea.trigger('focus')
    expect(wrapper.find('.edge-label').exists()).toBe(true)
    expect(wrapper.get('.edge-label').text()).toContain('Hall motion 状态 ∈ 活动')
    expect(wrapper.get('.edge-label__command p').text()).toContain('Hall camera拍照')
    expect(edge).toMatchObject({ fromApi: 'state', relation: 'in', value: 'active', toApi: 'take photo' })

    wrapper.unmount()
  })

  it('localizes each playback delta on the changed device while the triggered edge carries command flow', () => {
    const source = {
      id: 'motion-1',
      templateName: 'Motion Detector',
      label: 'Hall motion',
      position: { x: 40, y: 50 },
      state: 'idle',
      width: 176,
      height: 128
    }
    const target = {
      id: 'light-1',
      templateName: 'Light',
      label: 'Living-room Temperature Sensor',
      position: { x: 420, y: 220 },
      state: 'off',
      width: 176,
      height: 128
    }
    const edge = {
      id: 'rule-1-source-0',
      from: source.id,
      to: target.id,
      fromLabel: source.label,
      toLabel: target.label,
      fromPos: source.position,
      toPos: target.position,
      fromApi: 'motion',
      toApi: 'turn on',
      itemType: 'api' as const,
      ruleId: 'rule-1',
      ruleIndex: 0
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [source, target],
        edges: [edge],
        pan: { x: 0, y: 0 },
        zoom: 1,
        highlightedTrace: {
          selectedStateIndex: 1,
          states: [
            {
              stateIndex: 1,
              triggeredRules: [],
              compromisedAutomationLinks: [],
              devices: [
                { deviceId: 'motion_1', deviceLabel: source.label, state: 'idle', variables: [] },
                { deviceId: 'light_1', deviceLabel: target.label, state: 'off', variables: [{ name: 'brightness', value: '0' }] }
              ]
            },
            {
              stateIndex: 2,
              triggeredRules: [{ ruleIndex: 0, ruleId: 'rule-1', ruleLabel: 'Motion turns on light' }],
              compromisedAutomationLinks: [],
              devices: [
                { deviceId: 'motion_1', deviceLabel: source.label, state: 'active', variables: [] },
                { deviceId: 'light_1', deviceLabel: target.label, state: 'on', variables: [{ name: 'brightness', value: '100' }] }
              ]
            }
          ]
        },
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'Working'
      },
      global: { plugins: [i18n] }
    })

    const changedTarget = wrapper.get('[data-node-id="light-1"]')
    expect(changedTarget.classes()).toContain('trace-changed')
    expect(changedTarget.get('.device-runtime-chip--changed').text()).toContain('100')
    expect(changedTarget.find('[data-testid="trace-change-badge"]').exists()).toBe(false)
    // Compact chips preserve the current value; the popover and title hold the full transition.
    expect(changedTarget.find('.device-runtime-chip__previous').exists()).toBe(false)
    expect(wrapper.findAll('.edge-line--active').length).toBeGreaterThan(0)
    expect(wrapper.find('.particle-line').exists()).toBe(true)

    wrapper.unmount()
  })

  it('restarts delivered-rule edge flow for each model transition', async () => {
    const source = {
      id: 'motion-1',
      templateName: 'Motion Detector',
      label: 'Hall motion',
      position: { x: 40, y: 50 },
      state: 'idle',
      width: 176,
      height: 128
    }
    const target = {
      id: 'light-1',
      templateName: 'Light',
      label: 'Hall light',
      position: { x: 420, y: 220 },
      state: 'off',
      width: 176,
      height: 128
    }
    const edge = {
      id: 'rule-1-source-0',
      from: source.id,
      to: target.id,
      fromLabel: source.label,
      toLabel: target.label,
      fromPos: source.position,
      toPos: target.position,
      fromApi: 'motion',
      toApi: 'turn on',
      itemType: 'variable' as const,
      relation: '=',
      value: 'active',
      ruleId: 'rule-1',
      ruleIndex: 0
    }
    const states = [
      { triggeredRules: [], compromisedAutomationLinks: [], devices: [] },
      {
        triggeredRules: [{ ruleIndex: 0, ruleId: 'rule-1', ruleLabel: 'Motion turns on light' }],
        compromisedAutomationLinks: [],
        devices: []
      },
      {
        triggeredRules: [{ ruleIndex: 0, ruleId: 'rule-1', ruleLabel: 'Motion turns on light' }],
        compromisedAutomationLinks: [],
        devices: []
      }
    ]
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [source, target],
        edges: [edge],
        pan: { x: 0, y: 0 },
        zoom: 1,
        highlightedTrace: { states, selectedStateIndex: 1 },
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'Working'
      },
      global: { plugins: [i18n] }
    })

    const firstFlow = wrapper.get('.particle-line').element
    expect(firstFlow.getAttribute('data-playback-state')).toBe('1')
    expect(wrapper.html()).toContain('repeatCount="1"')
    expect(wrapper.html()).not.toContain('repeatCount="indefinite"')

    await wrapper.setProps({ highlightedTrace: { states, selectedStateIndex: 2 } })
    await wrapper.vm.$nextTick()

    const secondFlow = wrapper.get('.particle-line').element
    expect(secondFlow.getAttribute('data-playback-state')).toBe('2')
    expect(secondFlow).not.toBe(firstFlow)
    wrapper.unmount()
  })

  it('replays every semantic device change and renders the new visual state immediately', async () => {
    vi.useFakeTimers()
    const node = {
      id: 'camera-1',
      templateName: 'Camera',
      label: 'Hall camera',
      position: { x: 80, y: 80 },
      state: 'off',
      width: 176,
      height: 128
    }
    const states = [
      {
        devices: [{
          deviceId: 'camera_1',
          deviceLabel: node.label,
          state: 'off',
          variables: [],
          trustPrivacy: [{ name: 'off', propertyScope: 'state' as const, trust: true }]
        }]
      },
      {
        devices: [{
          deviceId: 'camera_1',
          deviceLabel: node.label,
          state: 'off',
          variables: [],
          trustPrivacy: [{ name: 'off', propertyScope: 'state' as const, trust: false }]
        }]
      },
      {
        devices: [{
          deviceId: 'camera_1',
          deviceLabel: node.label,
          state: 'on',
          variables: [],
          trustPrivacy: [{ name: 'on', propertyScope: 'state' as const, trust: false }]
        }]
      }
    ]
    const getNodeIcon = vi.fn((_node: typeof node, state?: string) => `/${state}.svg`)
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [node],
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 1,
        highlightedTrace: { states, selectedStateIndex: 0 },
        getNodeIcon,
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'Working'
      },
      global: { plugins: [i18n] }
    })

    try {
      await wrapper.setProps({ highlightedTrace: { states, selectedStateIndex: 1 } })
      await Promise.resolve()
      await wrapper.vm.$nextTick()
      await wrapper.vm.$nextTick()
      const changedNode = wrapper.get('[data-node-id="camera-1"]')
      expect(changedNode.classes()).toContain('trace-changed')
      expect(changedNode.classes()).toContain('trace-change-pulse')

      await wrapper.setProps({ highlightedTrace: { states, selectedStateIndex: 2 } })
      await Promise.resolve()
      await wrapper.vm.$nextTick()
      await wrapper.vm.$nextTick()
      expect(changedNode.classes()).toContain('trace-change-pulse')
      expect(changedNode.get('.device-state-value').text()).toBe('on')
      expect(changedNode.get('.device-img').attributes('src')).toBe('/on.svg')

      await wrapper.setProps({ highlightedTrace: null })
      await wrapper.vm.$nextTick()
      expect(changedNode.classes()).not.toContain('trace-change-pulse')
    } finally {
      wrapper.unmount()
      vi.useRealTimers()
    }
  })

  it('localizes playback security labels without changing canonical trace facts', () => {
    const node = {
      id: 'camera-1',
      templateName: 'Camera',
      label: 'Hall camera',
      position: { x: 80, y: 80 },
      state: 'off',
      width: 176,
      height: 128
    }
    const highlightedTrace = {
      selectedStateIndex: 0,
      states: [{
        devices: [{
          deviceId: 'camera_1',
          deviceLabel: node.label,
          templateName: 'Camera',
          modelTokenSource: 'BUNDLED' as const,
          mode: 'MachineState',
          state: 'on',
          variables: [],
          trustPrivacy: [{ name: 'on', propertyScope: 'state' as const, mode: 'MachineState', trust: false }],
          privacies: [{ name: 'photo', propertyScope: 'content' as const, privacy: 'private' }]
        }]
      }]
    }
    const canonicalSnapshot = structuredClone(highlightedTrace)
    const labels: Record<string, string> = {
      MachineState: '设备状态',
      on: '开启',
      photo: '照片'
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [node],
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 1,
        highlightedTrace,
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'Working',
        formatPlaybackModelToken: (source, value) => source === 'BUNDLED'
          ? (labels[String(value)] || String(value ?? ''))
          : String(value ?? '')
      },
      global: { plugins: [i18n] }
    })

    const titles = wrapper.findAll('.device-node-trust').map(badge => badge.attributes('title'))
    expect(titles.some(title => title?.includes('设备状态: 开启'))).toBe(true)
    expect(titles.some(title => title?.includes('照片'))).toBe(true)
    expect(titles.join(' ')).not.toContain('MachineState: on')
    expect(highlightedTrace).toEqual(canonicalSnapshot)
    wrapper.unmount()
  })

  it('shows effective template security labels and identifies instance overrides', async () => {
    const previousLocale = i18n.global.locale.value
    i18n.global.locale.value = 'en'
    const node = {
      id: 'sensor-1',
      templateName: 'Private Sensor',
      label: 'Hall sensor',
      position: { x: 80, y: 80 },
      state: 'active',
      width: 176,
      height: 128
    }
    const template = {
      name: 'Private Sensor',
      manifest: {
        Name: 'Private Sensor',
        Modes: ['Power'],
        InitState: 'active',
        WorkingStates: [{ Name: 'active', Trust: 'untrusted', Privacy: 'private' }],
        InternalVariables: [{
          Name: 'reading',
          IsInside: true,
          FalsifiableWhenCompromised: true,
          Trust: 'untrusted',
          Privacy: 'private',
          Values: ['idle', 'active']
        }]
      }
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [node],
        edges: [],
        deviceTemplates: [template],
        pan: { x: 0, y: 0 },
        zoom: 1,
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'active'
      },
      global: { plugins: [i18n] }
    })

    try {
      const inheritedTitles = wrapper.findAll('.device-node-trust').map(badge => badge.attributes('title')).join(' ')
      expect(inheritedTitles).toContain('Current state (template default)')
      expect(inheritedTitles).toContain('reading (template default)')
      expect(inheritedTitles).toContain('propagation analysis')

      await wrapper.setProps({
        nodes: [{
          ...node,
          currentStateTrust: 'trusted',
          currentStatePrivacy: 'public',
          variables: [{ name: 'reading', value: 'active', trust: 'trusted' }],
          privacies: [{ name: 'reading', privacy: 'public' }]
        }]
      })
      const badges = wrapper.findAll('.device-node-trust')
      expect(badges).toHaveLength(1)
      expect(badges[0].text()).toContain('Shown sources trusted')
      expect(badges[0].attributes('title')).toContain('instance override')
    } finally {
      wrapper.unmount()
      i18n.global.locale.value = previousLocale
    }
  })

  it('formats playback state and variables only from their frozen token sources', () => {
    const nodes = [
      { id: 'bundled-1', label: 'Bundled state', templateName: 'Current bundled', position: { x: 0, y: 0 }, state: 'current', width: 176, height: 128 },
      { id: 'custom-1', label: 'Custom state', templateName: 'Current bundled', position: { x: 200, y: 0 }, state: 'current', width: 176, height: 128 },
      { id: 'unknown-1', label: 'Unknown state', templateName: 'Current bundled', position: { x: 400, y: 0 }, state: 'current', width: 176, height: 128 }
    ]
    const liveFormatter = vi.fn((_node, value) => `current:${String(value ?? '')}`)
    const playbackFormatter = vi.fn((source, value) => source === 'BUNDLED'
      ? `history:${String(value ?? '')}`
      : String(value ?? ''))
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes,
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 1,
        highlightedTrace: {
          selectedStateIndex: 0,
          states: [{
            devices: [
              {
                deviceId: 'bundled_1',
                state: 'off',
                mode: 'MachineState',
                modelTokenSource: 'BUNDLED' as const,
                variables: [{ name: 'workingState', value: 'on', trust: 'untrusted', modelTokenSource: 'BUNDLED' as const }]
              },
              {
                deviceId: 'custom_1',
                state: 'off',
                mode: 'MachineState',
                modelTokenSource: 'CUSTOM' as const,
                variables: [{ name: 'workingState', value: 'on', trust: 'untrusted', modelTokenSource: 'CUSTOM' as const }]
              },
              {
                deviceId: 'unknown_1',
                state: 'off',
                mode: 'MachineState',
                modelTokenSource: 'UNKNOWN' as const,
                variables: [{ name: 'workingState', value: 'on', modelTokenSource: 'UNKNOWN' as const }]
              }
            ]
          }]
        },
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: () => 'current',
        formatNodeModelToken: liveFormatter,
        formatPlaybackModelToken: playbackFormatter
      },
      global: { plugins: [i18n] }
    })

    const bundled = wrapper.get('[data-node-id="bundled-1"]')
    expect(bundled.get('.device-state-value').text()).toBe('history:off')
    expect(bundled.get('.device-runtime-chip__label').text()).toBe('history:workingState')
    expect(bundled.get('.device-runtime-chip__value').text()).toBe('history:on')
    expect(bundled.get('.device-node-trust--trust').attributes('title')).toContain('history:workingState')

    const custom = wrapper.get('[data-node-id="custom-1"]')
    expect(custom.get('.device-state-value').text()).toBe('off')
    expect(custom.get('.device-runtime-chip__label').text()).toBe('workingState')
    expect(custom.get('.device-runtime-chip__value').text()).toBe('on')
    expect(custom.get('.device-node-trust--trust').attributes('title')).toContain('workingState')
    expect(custom.get('.device-node-trust--trust').attributes('title')).not.toContain('history:workingState')

    const unknown = wrapper.get('[data-node-id="unknown-1"]')
    expect(unknown.get('.device-state-value').text()).toBe('off')
    expect(unknown.get('.device-runtime-chip__label').text()).toBe('workingState')
    expect(unknown.get('.device-runtime-chip__value').text()).toBe('on')
    expect(liveFormatter).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  // The canvas strip is where the contradiction was visible: the demo scene drew "illuminance 0" on the
  // Porch Light beside an environment strip reading 20, because an affect-only shared declaration is
  // declared-unconstrained and NuSMV prints an arbitrary domain member. The backend now sends the row
  // with `observed: false` and an empty value, and the chip must disappear rather than render blank —
  // `getNodeRuntimeBadges` has a second disjunct that resurrects any merely-empty value.
  it('drops an unobserved trace variable from the node strip and keeps observed ones', () => {
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [{
          id: 'light-1', label: 'Porch Light', templateName: 'Light',
          position: { x: 0, y: 0 }, state: 'on', width: 176, height: 128
        }],
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 1,
        highlightedTrace: {
          selectedStateIndex: 0,
          states: [{
            devices: [{
              deviceId: 'light_1',
              state: 'on',
              modelTokenSource: 'BUNDLED' as const,
              variables: [
                {
                  name: 'illuminance', value: '', trust: 'untrusted',
                  observed: false, modelTokenSource: 'BUNDLED' as const
                },
                {
                  name: 'brightness', value: '80', trust: 'trusted',
                  modelTokenSource: 'BUNDLED' as const
                }
              ]
            }]
          }]
        },
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: () => 'on'
      },
      global: { plugins: [i18n] }
    })

    const node = wrapper.get('[data-node-id="light-1"]')
    const labels = node.findAll('.device-runtime-chip__label').map(chip => chip.text())
    expect(labels).toEqual(['brightness'])
    expect(node.text()).not.toContain('illuminance')
    wrapper.unmount()
  })

  it('uses historical state-machine evidence instead of the current template', () => {
    const recordedStateful = {
      id: 'was-stateful-1',
      templateName: 'Now stateless',
      label: 'Was stateful',
      position: { x: 20, y: 30 },
      state: 'current-placeholder',
      width: 176,
      height: 128
    }
    const recordedStateless = {
      id: 'was-stateless-1',
      templateName: 'Now stateful',
      label: 'Was stateless',
      position: { x: 220, y: 30 },
      state: 'current-state',
      width: 176,
      height: 128
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [recordedStateful, recordedStateless],
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 1,
        highlightedTrace: {
          selectedStateIndex: 0,
          states: [{
            devices: [
              {
                deviceId: 'was_stateful_1',
                state: 'historic-state',
                mode: 'HistoricMode',
                modelTokenSource: 'CUSTOM' as const,
                variables: []
              },
              {
                deviceId: 'was_stateless_1',
                modelTokenSource: 'BUNDLED' as const,
                variables: []
              }
            ]
          }]
        },
        getNodeIcon: () => '',
        hasNodeStateMachine: node => node.id === recordedStateless.id,
        getNodeEffectiveState: node => node.state || 'Working',
        formatPlaybackModelToken: (_source, value) => String(value ?? '')
      },
      global: { plugins: [i18n] }
    })

    const stateful = wrapper.get('[data-node-id="was-stateful-1"]')
    expect(stateful.get('.device-state').classes()).toContain('state-defined')
    expect(stateful.get('.device-state-value').text()).toBe('historic-state')

    const stateless = wrapper.get('[data-node-id="was-stateless-1"]')
    expect(stateless.get('.device-state').classes()).toContain('state-stateless')
    expect(stateless.get('.device-state-value').text()).toBe(i18n.global.t('app.noStateMachine'))
    wrapper.unmount()
  })

  it('shows a localized stateless label instead of the persistence fallback state', () => {
    const node = {
      id: 'sensor-1',
      templateName: 'Temperature Sensor',
      label: 'Hall sensor',
      position: { x: 20, y: 30 },
      state: 'Working',
      width: 176,
      height: 128
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [node],
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 1,
        getNodeIcon: () => '',
        hasNodeStateMachine: () => false,
        getNodeEffectiveState: () => 'Working'
      },
      global: { plugins: [i18n] }
    })

    const rendered = wrapper.get('[data-node-id="sensor-1"]')
    expect(rendered.get('.device-state').classes()).toContain('state-stateless')
    expect(rendered.get('.device-state-value').text()).toBe(i18n.global.t('app.noStateMachine'))
    expect(rendered.attributes('title')).not.toContain('Working')
    wrapper.unmount()
  })

  it('uses compact, condensed, and expanded tiers and supports keyboard resizing', async () => {
    const node = {
      id: 'light-1',
      templateName: 'Light',
      label: 'Living-room Temperature Sensor',
      position: { x: 20, y: 30 },
      state: 'off',
      width: 176,
      height: 128
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [node],
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 1,
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'off'
      },
      global: { plugins: [i18n] }
    })
    const rendered = wrapper.get('[data-node-id="light-1"]')

    expect(rendered.classes()).toContain('device-node--expanded')
    expect(rendered.findAll('.resize-handle')).toHaveLength(4)
    await wrapper.setProps({ zoom: 0.7 })
    expect(rendered.classes()).toContain('device-node--condensed')
    expect(rendered.findAll('.resize-handle')).toHaveLength(4)
    await wrapper.setProps({ zoom: 0.5 })
    expect(rendered.classes()).toContain('device-node--compact')
    expect(rendered.findAll('.resize-handle')).toHaveLength(1)
    expect(rendered.get('.device-label').text()).toBe('Living-room Temperature Sensor')
    expect(rendered.get('.device-node-content').attributes('style')).toContain('--node-ui-scale: 0.5')

    await rendered.trigger('keydown', { key: 'ArrowRight', ctrlKey: true })
    await rendered.trigger('keydown', { key: 'ArrowDown', ctrlKey: true, shiftKey: true })
    expect(node.width).toBe(186)
    expect(node.height).toBe(129)
    expect(wrapper.emitted('node-moved-or-resized')).toEqual([['light-1'], ['light-1']])

    await wrapper.setProps({ interactionLocked: true })
    await rendered.trigger('keydown', { key: 'ArrowRight', ctrlKey: true })
    expect(node.width).toBe(186)
    expect(wrapper.emitted('node-moved-or-resized')).toEqual([['light-1'], ['light-1']])
    wrapper.unmount()
  })

  it.each([{ width: 80, height: 60 }, { width: 80, height: 220 }, { width: 420, height: 60 }, { width: 176, height: 128 }])(
    'keeps a usable pointer resize handle on small screen footprints ($width × $height)', async dimensions => {
    // None of these shapes can accommodate four grips at overview zoom; one must remain usable.
    const node = {
      id: 'small-light-1',
      templateName: 'Light',
      label: 'Small light',
      position: { x: 20, y: 30 },
      state: 'off',
      ...dimensions
    }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [node],
        edges: [],
        pan: { x: 0, y: 0 },
        zoom: 0.2,
        getNodeIcon: () => '',
        hasNodeStateMachine: () => true,
        getNodeEffectiveState: currentNode => currentNode.state || 'off'
      },
      global: { plugins: [i18n] }
    })
    const rendered = wrapper.get('[data-node-id="small-light-1"]')

    // One handle (br) to grow the node, not four (which would crowd a 32×24 screen footprint).
    expect(rendered.findAll('.resize-handle')).toHaveLength(1)
    expect(rendered.find('.resize-handle.br').exists()).toBe(true)
    expect(rendered.find('.resize-handle.tl').exists()).toBe(false)

    // Keyboard resize still works (unchanged).
    await rendered.trigger('keydown', { key: 'ArrowRight', ctrlKey: true })
    expect(node.width).toBe(dimensions.width + 10)
    expect(wrapper.emitted('node-moved-or-resized')).toEqual([['small-light-1']])

    const handle = rendered.get('.resize-handle.br')
    handle.element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
    window.dispatchEvent(pointerEvent('pointermove', 130, 120))
    window.dispatchEvent(pointerEvent('pointerup', 130, 120))
    await nextTick()
    expect(node.width).toBe(dimensions.width + 160)
    expect(node.height).toBe(dimensions.height + 100)
    expect(wrapper.emitted('node-moved-or-resized')).toEqual([['small-light-1'], ['small-light-1']])

    await wrapper.setProps({ zoom: 1 })
    expect(rendered.find('.resize-handle.br').exists()).toBe(true)
    wrapper.unmount()
  })

})


describe('CanvasBoard edge legibility across zoom', () => {
  it.each([0, 52])('keeps HTML and SVG origins aligned with a %spx playback toolbar', toolbarHeight => {
    const css = postcss.parse(readFileSync(join(__dirname, '../../styles/board.css'), 'utf8'))
    const style = document.createElement('style')
    css.walkRules(rule => {
      if (['.iot-board .canvas', '.iot-board .canvas-inner', '.iot-board .edge-layer'].includes(rule.selector)) {
        style.textContent += rule.toString()
          .replace('var(--board-nav-height)', '69px')
          .replace('var(--board-playback-toolbar-height)', `${toolbarHeight}px`)
      }
    })
    document.head.append(style)
    const host = document.createElement('div')
    host.className = 'iot-board'
    document.body.append(host)
    const wrapper = mount(CanvasBoard, { attachTo: host,
      props: { nodes: [], edges: [], pan: { x: 70, y: -30 }, zoom: 0.4,
        getNodeIcon: () => '', hasNodeStateMachine: () => true, getNodeEffectiveState: () => 'off' },
      global: { plugins: [i18n] }
    })
    try {
      expect(getComputedStyle(wrapper.get('.canvas').element).paddingTop).toBe(`calc(${69 + toolbarHeight}px)`)
      for (const selector of ['.canvas-inner', '.edge-layer']) {
        const computed = getComputedStyle(wrapper.get(selector).element)
        expect(computed.position, `${selector} must ignore normal-flow padding`).toBe('absolute')
        // jsdom preserves inset shorthand rather than expanding it into top/left.
        expect(computed.top || computed.inset).toMatch(/^0(px)?$/)
        expect(computed.left || computed.inset).toMatch(/^0(px)?$/)
        expect(computed.willChange).toBe('transform')
      }
    } finally {
      wrapper.unmount()
      host.remove()
      style.remove()
    }
  })

  it.each([0.25, 1, 2.5])('keeps both reverse connections on rendered node boundaries during a drag at zoom %s', async zoom => {
    const source = reactive({ id: 'source', templateName: 'Light', label: 'Source',
      position: { x: 40, y: 50 }, state: 'off', width: 176, height: 128 })
    const target = { ...source, id: 'target', position: { x: 400, y: 350 }, width: 130, height: 93 }
    const wrapper = mount(CanvasBoard, {
      props: { nodes: [source, target], pan: { x: 70, y: -30 }, zoom,
        getNodeIcon: () => '', hasNodeStateMachine: () => true, getNodeEffectiveState: () => 'off',
        edges: [[source, target], [target, source]].map(([from, to], index) => ({
          id: `edge-${index}`, from: from.id, to: to.id, fromLabel: from.label, toLabel: to.label,
          fromPos: from.position, toPos: to.position
        })) }, global: { plugins: [i18n] }
    })
    const expectRenderedAnchors = () => {
      const lines = wrapper.findAll('line.edge-base-line')
      expect(lines).toHaveLength(2)
      for (const [index, line] of lines.entries()) {
        for (const [end, id] of (index === 0 ? [[1, source.id], [2, target.id]] : [[1, target.id], [2, source.id]]) as [number, string][]) {
          const box = wrapper.get<HTMLElement>(`[data-node-id="${id}"]`).element.style
          const left = Number.parseFloat(box.left), top = Number.parseFloat(box.top)
          const right = left + Number.parseFloat(box.width), bottom = top + Number.parseFloat(box.height)
          const x = Number(line.attributes(`x${end}`)), y = Number(line.attributes(`y${end}`))
          expect(x).toBeGreaterThanOrEqual(left - 1e-6)
          expect(x).toBeLessThanOrEqual(right + 1e-6)
          expect(y).toBeGreaterThanOrEqual(top - 1e-6)
          expect(y).toBeLessThanOrEqual(bottom + 1e-6)
          expect(Math.min(Math.abs(x - left), Math.abs(x - right), Math.abs(y - top), Math.abs(y - bottom))).toBeLessThan(1e-6)
        }
      }
      expect(lines[0].attributes('x1')).not.toBe(lines[1].attributes('x2'))
    }
    try {
      expectRenderedAnchors()
      wrapper.get('[data-node-id="source"]').element.dispatchEvent(pointerEvent('pointerdown', 100, 100))
      window.dispatchEvent(pointerEvent('pointermove', 140, 120))
      await nextAnimationFrame()
      expectRenderedAnchors()
      expect(source.position).toEqual({ x: 40, y: 50 })
      window.dispatchEvent(pointerEvent('pointercancel', 140, 120))
      await nextTick()
      expectRenderedAnchors()
    } finally { wrapper.unmount() }
  })

  it('passes empty HTML space through to edges while keeping node pointer targets', () => {
    const css = postcss.parse(readFileSync(join(__dirname, '../../styles/board.css'), 'utf8'))
    const pointerEvents: Record<string, string> = {}
    css.walkRules(rule => rule.walkDecls('pointer-events', decl => {
      pointerEvents[rule.selector] = decl.value
    }))
    expect(pointerEvents['.iot-board .canvas-inner']).toBe('none')
    expect(pointerEvents['.iot-board .device-node']).toBe('auto')
  })

  it.each([0.25, 1, 2.5])('keeps direction markers and hover labels at screen size at zoom %s', async zoom => {
    const source = { id: 'source', templateName: 'Light', label: 'Source light', position: { x: 50, y: 50 }, state: 'off', width: 176, height: 128 }
    const target = { ...source, id: 'target', label: 'Target light', position: { x: 400, y: 200 } }
    const wrapper = mount(CanvasBoard, {
      props: {
        nodes: [source, target], edges: [{ id: 'edge', from: source.id, to: target.id,
          fromLabel: source.label, toLabel: target.label, fromPos: source.position, toPos: target.position,
          fromApi: 'off', toApi: 'on', ruleId: 'rule' }], pan: { x: 0, y: 0 }, zoom,
        getNodeIcon: () => '', hasNodeStateMachine: () => true, getNodeEffectiveState: node => node.state
      }, global: { plugins: [i18n] }
    })
    try {
      const layer = wrapper.get<SVGElement>('.edge-layer')
      expect(layer.element.closest('.canvas-inner')).toBeNull()
      const world = layer.get('[data-testid="canvas-edge-world"]')
      expect(layer.element.style.transform).toBe('translate(0px, 0px)')
      expect(world.attributes('transform')).toBe(`scale(${zoom})`)
      const marker = wrapper.findAll('marker')[0]
      expect(marker.attributes('markerUnits')).toBe('userSpaceOnUse')
      expect(marker.attributes('viewBox')).toBe('0 0 10 10')
      const physicalWidth = Number(marker.attributes('markerWidth')) * zoom
      expect(physicalWidth).toBeGreaterThanOrEqual(6)
      expect(physicalWidth).toBeLessThanOrEqual(12)
      await wrapper.get('.edge-hitarea').trigger('focus')
      const label = wrapper.get<HTMLElement>('[data-testid="canvas-edge-details"]')
      expect(label.element.closest('svg')).toBeNull()
      const width = Number.parseFloat(label.element.style.width)
      expect(width).toBe(320)
      expect(wrapper.get('.edge-hitarea').attributes('aria-label')).toContain('Source light')
      const nextZoom = zoom === 2.5 ? 0.25 : 2.5
      await wrapper.setProps({ zoom: nextZoom, pan: { x: 80, y: -50 } })
      expect(layer.element.style.transform).toBe('translate(80px, -50px)')
      expect(world.attributes('transform')).toBe(`scale(${nextZoom})`)
      expect(wrapper.get<HTMLElement>('.canvas-inner').element.style.transform).toBe(`translate(80px, -50px) scale(${nextZoom})`)
      expect(Number(marker.attributes('markerWidth')) * nextZoom).toBeCloseTo(physicalWidth)
      expect(Number.parseFloat(label.element.style.width)).toBe(width)
    } finally { wrapper.unmount() }
  })
})
