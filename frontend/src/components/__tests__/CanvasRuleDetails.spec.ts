import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import postcss from 'postcss'
import CanvasBoard from '../CanvasBoard.vue'
import { i18n } from '@/assets/i18n'
import { formatBuiltInModelToken } from '@/utils/modelTokenDisplay'
import type { DeviceNode } from '@/types/node'
import type { DeviceEdge } from '@/types/edge'
import type { RuleForm } from '@/types/rule'

const source: DeviceNode = { id: 'sensor', label: 'Hall sensor', templateName: 'Sensor',
  state: 'off', position: { x: 0, y: 120 }, width: 176, height: 128 }
const target: DeviceNode = { ...source, id: 'light', label: 'Desk light', position: { x: 400, y: 200 } }
const custom: DeviceNode = { ...source, id: 'custom', label: 'Custom source', templateName: 'Custom', position: { x: 200, y: 400 } }
const edge: DeviceEdge = { id: 'edge', from: source.id, to: target.id,
  fromLabel: source.label, toLabel: target.label, fromPos: source.position, toPos: target.position,
  fromApi: 'state', itemType: 'state', relation: '=', value: 'off', toApi: 'on', ruleId: '7', ruleIndex: 0 }
const longValue = '完整保留 / Preserve every word '.repeat(30) + 'UNIQUE_END'
const rule: RuleForm = { id: '7', sources: [
  { fromId: 'sensor', fromApi: 'state', itemType: 'state', relation: '=', value: 'off' },
  { fromId: 'custom', fromApi: 'on', itemType: 'variable', relation: '=', value: longValue }
], toId: 'light', toApi: 'on', contentDevice: 'custom', content: 'private_content' }
const mountDetails = (attrs: Record<string, string> = {}) => mount(CanvasBoard, {
  attrs,
  props: { nodes: [source, target, custom], edges: [edge], rules: [rule], pan: { x: 0, y: 0 }, zoom: 1,
    getNodeIcon: () => '', hasNodeStateMachine: () => true, getNodeEffectiveState: node => node.state,
    formatNodeModelToken: (node, value) => node.id === 'custom' ? String(value ?? '') : formatBuiltInModelToken(value, i18n.global.t)
  }, global: { plugins: [i18n] }
})
beforeEach(() => { vi.useFakeTimers() })
afterEach(() => { vi.useRealTimers(); i18n.global.locale.value = 'en' })

describe('complete canvas rule details', () => {
  it('shows every condition, action and label source without ellipsis and reacts to locale changes', async () => {
    i18n.global.locale.value = 'en'
    const wrapper = mountDetails()
    const before = JSON.stringify({ source, target, custom, edge, rule })
    try {
      await wrapper.get('.edge-hitarea').trigger('focus')
      const detail = wrapper.get('[data-testid="canvas-edge-details"]')
      expect(detail.attributes('role')).toBe('tooltip')
      expect(wrapper.get('.edge-hitarea').attributes('aria-describedby')).toBe('canvas-edge-details')
      expect(detail.findAll('li').map(row => row.text())).toEqual(['Hall sensor State = Off', `Custom source on = ${longValue}`])
      expect(detail.text()).toContain('When all conditions hold')
      expect(detail.get('.edge-label__command strong').text()).toBe('On')
      expect(detail.text()).toContain('Custom source private_content')
      expect(detail.text()).not.toContain('…')
      expect(Number.parseFloat((detail.element as HTMLElement).style.maxHeight)).toBeLessThanOrEqual(360)
      i18n.global.locale.value = 'zh-CN'
      await nextTick()
      expect(detail.text()).toContain('同时满足以下条件')
      expect(detail.findAll('li')[0].text()).toBe('Hall sensor 状态 = 关闭')
      expect(detail.findAll('li')[1].text()).toBe(`Custom source on = ${longValue}`)
      expect(detail.get('.edge-label__command strong').text()).toBe('开启')
      expect(detail.text()).toContain('内容标签来源')
      expect(wrapper.get('.edge-hitarea').attributes('aria-label')).toContain('则向 Desk light 发送“开启”命令')
      expect(JSON.stringify({ source, target, custom, edge, rule })).toBe(before)
      await detail.trigger('pointerdown')
      await detail.trigger('wheel')
      expect(wrapper.emitted('canvas-pointerdown')).toBeUndefined()
      expect(wrapper.emitted('canvas-wheel')).toBeUndefined()
    } finally { wrapper.unmount() }
  })

  it('keeps the hover card open while crossing to read it, closes on departure and supports Escape', async () => {
    vi.useFakeTimers()
    const wrapper = mountDetails()
    try {
      const hitarea = wrapper.get('.edge-hitarea')
      await hitarea.trigger('pointerenter')
      await vi.advanceTimersByTimeAsync(400)
      await hitarea.trigger('pointerleave')
      await wrapper.get('[data-testid="canvas-edge-details"]').trigger('pointerenter')
      await vi.advanceTimersByTimeAsync(200)
      expect(wrapper.find('[data-testid="canvas-edge-details"]').exists()).toBe(true)
      await wrapper.get('[data-testid="canvas-edge-details"]').trigger('pointerleave')
      await vi.advanceTimersByTimeAsync(200)
      expect(wrapper.find('[data-testid="canvas-edge-details"]').exists()).toBe(false)
      await hitarea.trigger('focus')
      expect(wrapper.find('[data-testid="canvas-edge-details"]').exists()).toBe(true)
      await hitarea.trigger('keydown', { key: 'Escape' })
      expect(wrapper.find('[data-testid="canvas-edge-details"]').exists()).toBe(false)
      expect(hitarea.attributes('aria-describedby')).toBeUndefined()
      await hitarea.trigger('blur')
      await hitarea.trigger('focus')
      expect(wrapper.find('[data-testid="canvas-edge-details"]').exists()).toBe(true)
    } finally { wrapper.unmount() }
  })

  it('fits long details inside a short narrow canvas at every zoom and after panning offscreen', async () => {
    const wrapper = mountDetails({ style: 'padding-top:64px' })
    try {
      const canvas = wrapper.get<HTMLElement>('[data-testid="canvas-board"]').element
      Object.defineProperties(canvas, { clientWidth: { value: 200 }, clientHeight: { value: 240 } })
      await wrapper.get('.edge-hitarea').trigger('focus')
      const detail = wrapper.get<HTMLElement>('[data-testid="canvas-edge-details"]').element
      Object.defineProperty(detail, 'offsetHeight', { value: 800 })
      window.dispatchEvent(new Event('resize'))
      await nextTick()
      for (const zoom of [0.25, 1, 2.5]) {
        await wrapper.setProps({ zoom, pan: { x: 800, y: -600 } })
        const width = Number.parseFloat(detail.style.width)
        const maxHeight = Number.parseFloat(detail.style.maxHeight)
        expect(width).toBe(176)
        expect(maxHeight).toBe(152)
        expect(Number.parseFloat(detail.style.left)).toBeGreaterThanOrEqual(12)
        expect(Number.parseFloat(detail.style.left) + width).toBeLessThanOrEqual(188)
        expect(Number.parseFloat(detail.style.top)).toBeGreaterThanOrEqual(76)
        expect(Number.parseFloat(detail.style.top) + maxHeight).toBeLessThanOrEqual(228)
        expect(detail.textContent).toContain('UNIQUE_END')
      }
      expect(detail.classList.contains('iot-scroll-region')).toBe(true)
    } finally { wrapper.unmount() }
  })

  it('does not keep later hints stuck when the rule disappears while its card is hovered', async () => {
    vi.useFakeTimers()
    const wrapper = mountDetails()
    try {
      await wrapper.get('.edge-hitarea').trigger('pointerenter')
      await vi.advanceTimersByTimeAsync(400)
      await wrapper.get('[data-testid="canvas-edge-details"]').trigger('pointerenter')
      await wrapper.setProps({ edges: [] })
      expect(wrapper.find('[data-testid="canvas-edge-details"]').exists()).toBe(false)
      await wrapper.setProps({ edges: [{ ...edge, id: 'replacement' }] })
      await wrapper.get('.edge-hitarea').trigger('pointerenter')
      await vi.advanceTimersByTimeAsync(400)
      expect(wrapper.find('[data-testid="canvas-edge-details"]').exists()).toBe(true)
      await wrapper.get('.edge-hitarea').trigger('pointerleave')
      await vi.advanceTimersByTimeAsync(200)
      expect(wrapper.find('[data-testid="canvas-edge-details"]').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('places details to the right of the pointer without following jitter and flips left at a panel boundary', async () => {
    const wrapper = mountDetails()
    try {
      await wrapper.setProps({ getVisibleFrame: () => ({ left: 180, top: 64, width: 700, height: 580 }) })
      const hitarea = wrapper.get('.edge-hitarea')
      await hitarea.trigger('pointerenter', { clientX: 320, clientY: 180 })
      await vi.advanceTimersByTimeAsync(400)
      const detail = wrapper.get<HTMLElement>('[data-testid="canvas-edge-details"]')
      Object.defineProperty(detail.element, 'offsetHeight', { value: 200 })
      window.dispatchEvent(new Event('resize'))
      await nextTick()
      expect(detail.element.style.left).toBe('336px')
      expect(detail.element.style.top).toBe('168px')
      await hitarea.trigger('pointermove', { clientX: 330, clientY: 190 })
      expect(detail.element.style.left).toBe('336px')
      await hitarea.trigger('keydown', { key: 'Escape' })
      await hitarea.trigger('pointerenter', { clientX: 600, clientY: 180 })
      await vi.advanceTimersByTimeAsync(400)
      expect(wrapper.get<HTMLElement>('[data-testid="canvas-edge-details"]').element.style.left).toBe('264px')
      await hitarea.trigger('keydown', { key: 'Escape' })
      await hitarea.trigger('pointerenter', { clientX: 820, clientY: 600 })
      await vi.advanceTimersByTimeAsync(400)
      const flipped = wrapper.get<HTMLElement>('[data-testid="canvas-edge-details"]')
      Object.defineProperty(flipped.element, 'offsetHeight', { value: 200 })
      window.dispatchEvent(new Event('resize'))
      await nextTick()
      expect(flipped.element.style.left).toBe('484px')
      expect(Number.parseFloat(flipped.element.style.left) + 320).toBeLessThan(820)
      expect(Number.parseFloat(flipped.element.style.top) + 200).toBeLessThanOrEqual(632)
    } finally { wrapper.unmount() }
  })

  it('waits for deliberate hover, cancels crossings and gives each new edge its own delay', async () => {
    const wrapper = mountDetails()
    try {
      await wrapper.setProps({ edges: [edge, { ...edge, id: 'second' }] })
      const [first, second] = wrapper.findAll('.edge-hitarea')
      await first.trigger('pointerenter')
      expect(wrapper.find('[data-testid="canvas-edge-details"]').exists()).toBe(false)
      await vi.advanceTimersByTimeAsync(399)
      expect(wrapper.find('[data-testid="canvas-edge-details"]').exists()).toBe(false)
      await vi.advanceTimersByTimeAsync(1)
      expect(wrapper.get('[data-testid="canvas-edge-details"]').attributes('data-edge-id')).toBe('edge')
      await first.trigger('pointerleave')
      await vi.advanceTimersByTimeAsync(120)
      await first.trigger('pointerenter')
      await vi.advanceTimersByTimeAsync(200)
      await first.trigger('pointerleave')
      await second.trigger('pointerenter')
      await vi.advanceTimersByTimeAsync(200)
      expect(wrapper.find('[data-testid="canvas-edge-details"]').exists()).toBe(false)
      await vi.advanceTimersByTimeAsync(200)
      expect(wrapper.get('[data-testid="canvas-edge-details"]').attributes('data-edge-id')).toBe('second')
      await second.trigger('pointerleave')
      await vi.advanceTimersByTimeAsync(120)
      await first.trigger('pointerenter')
      await first.trigger('pointerleave')
      await vi.advanceTimersByTimeAsync(600)
      expect(wrapper.find('[data-testid="canvas-edge-details"]').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('tracks the pointer while waiting and keeps the card stable once visible', async () => {
    const wrapper = mountDetails()
    try {
      await wrapper.setProps({ getVisibleFrame: () => ({ left: 0, top: 0, width: 1000, height: 800 }) })
      const hitarea = wrapper.get('.edge-hitarea')
      await hitarea.trigger('pointerenter', { clientX: 100, clientY: 180 })
      await vi.advanceTimersByTimeAsync(200)
      await hitarea.trigger('pointermove', { clientX: 200, clientY: 200 })
      await vi.advanceTimersByTimeAsync(200)
      const detail = wrapper.get<HTMLElement>('[data-testid="canvas-edge-details"]')
      expect(detail.element.style.left).toBe('216px')
      expect(detail.element.style.top).toBe('188px')
      await hitarea.trigger('pointermove', { clientX: 210, clientY: 210 })
      expect(detail.element.style.left).toBe('216px')
      expect(detail.element.style.top).toBe('188px')
    } finally { wrapper.unmount() }
  })

  it('cancels pending hints on deletion, Escape, focus changes and unmount', async () => {
    const wrapper = mountDetails()
    try {
      await wrapper.get('.edge-hitarea').trigger('pointerenter')
      await vi.advanceTimersByTimeAsync(200)
      await wrapper.setProps({ edges: [] })
      await wrapper.setProps({ edges: [edge] })
      await vi.advanceTimersByTimeAsync(400)
      expect(wrapper.find('[data-testid="canvas-edge-details"]').exists()).toBe(false)
      const hitarea = wrapper.get('.edge-hitarea')
      await hitarea.trigger('pointerenter')
      const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
      hitarea.element.dispatchEvent(escape)
      expect(escape.defaultPrevented).toBe(false)
      await vi.advanceTimersByTimeAsync(400)
      expect(wrapper.find('[data-testid="canvas-edge-details"]').exists()).toBe(false)
      await wrapper.setProps({ edges: [edge, { ...edge, id: 'second' }] })
      await wrapper.findAll('.edge-hitarea')[1].trigger('pointerenter')
      await hitarea.trigger('focus')
      expect(wrapper.get('[data-testid="canvas-edge-details"]').attributes('data-edge-id')).toBe('edge')
      await vi.advanceTimersByTimeAsync(400)
      expect(wrapper.get('[data-testid="canvas-edge-details"]').attributes('data-edge-id')).toBe('edge')
      await hitarea.trigger('blur')
      await hitarea.trigger('pointerenter')
      wrapper.unmount()
      expect(vi.getTimerCount()).toBe(0)
    } finally { if (wrapper.exists()) wrapper.unmount() }
  })

  it('reads all frozen rule conditions by snapshot order even without a persisted rule id', async () => {
    const wrapper = mountDetails()
    try {
      await wrapper.setProps({ edges: [{ ...edge, ruleId: undefined }], rules: [{
        id: null,
        conditions: [
          { deviceName: 'sensor', attribute: 'old_mode', targetType: 'state', relation: '=', value: 'off' },
          { deviceName: 'custom', attribute: 'frozen_reading', targetType: 'variable', relation: '>', value: '99' }
        ], command: { deviceName: 'light', action: 'on', contentDevice: 'custom', content: 'frozen_content' }
      }] })
      await wrapper.get('.edge-hitarea').trigger('focus')
      const detail = wrapper.get('[data-testid="canvas-edge-details"]')
      expect(detail.findAll('li')).toHaveLength(2)
      expect(detail.text()).toContain('frozen_reading > 99')
      expect(detail.text()).toContain('frozen_content')
      expect(detail.text()).not.toContain('UNIQUE_END')
    } finally { wrapper.unmount() }
  })

  it('localizes playback details from frozen token sources and leaves custom tokens unchanged', async () => {
    i18n.global.locale.value = 'en'
    const wrapper = mountDetails()
    const liveFormatter = vi.fn((_node: DeviceNode, value: unknown) => `wrong-live:${String(value ?? '')}`)
    try {
      await wrapper.setProps({
        formatNodeModelToken: liveFormatter,
        formatPlaybackModelToken: (source, value) => source === 'BUNDLED'
          ? formatBuiltInModelToken(value, i18n.global.t) : String(value ?? ''),
        highlightedTrace: { selectedStateIndex: 0, states: [{ devices: [
          { deviceId: 'sensor', state: 'off', mode: 'MachineState', modelTokenSource: 'BUNDLED' },
          { deviceId: 'light', state: 'off', mode: 'MachineState', modelTokenSource: 'BUNDLED' },
          { deviceId: 'custom', state: 'off', mode: 'MachineState', modelTokenSource: 'CUSTOM' }
        ] }] },
        rules: [{ id: null, conditions: [
          { deviceName: 'sensor', attribute: 'state', targetType: 'state', relation: '=', value: 'off' },
          { deviceName: 'custom', attribute: 'on', targetType: 'variable', relation: '=', value: 'off' }
        ], command: { deviceName: 'light', action: 'on', contentDevice: 'custom', content: 'on' } }]
      })
      await wrapper.get('.edge-hitarea').trigger('focus')
      const detail = wrapper.get('[data-testid="canvas-edge-details"]')
      expect(detail.findAll('li').map(row => row.text())).toEqual(['Hall sensor State = Off', 'Custom source on = off'])
      expect(detail.get('.edge-label__command strong').text()).toBe('On')
      expect(detail.text()).toContain('Custom source on')
      i18n.global.locale.value = 'zh-CN'
      await nextTick()
      expect(detail.findAll('li').map(row => row.text())).toEqual(['Hall sensor 状态 = 关闭', 'Custom source on = off'])
      expect(detail.get('.edge-label__command strong').text()).toBe('开启')
      expect(liveFormatter).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })

  it('does not associate an unindexed edge with an unrelated rule just because both lack ids', async () => {
    i18n.global.locale.value = 'en'
    const wrapper = mountDetails()
    try {
      await wrapper.setProps({ edges: [{ ...edge, ruleId: undefined, ruleIndex: undefined }], rules: [{ ...rule, id: undefined }] })
      await wrapper.get('.edge-hitarea').trigger('focus')
      const detail = wrapper.get('[data-testid="canvas-edge-details"]')
      expect(detail.findAll('li').map(row => row.text())).toEqual(['Hall sensor State = Off'])
      expect(detail.text()).not.toContain('UNIQUE_END')
      expect(detail.get('.edge-label__command strong').text()).toBe('On')
    } finally { wrapper.unmount() }
  })

  it('wraps selectable content on shared surfaces without a line clamp or a fixed text budget', () => {
    const source = readFileSync(join(__dirname, '../CanvasBoard.vue'), 'utf8')
    const css = postcss.parse(/<style scoped>([\s\S]*?)<\/style>/.exec(source)![1])
    const declarations: Record<string, string> = {}
    css.walkRules('.edge-label', rule => rule.walkDecls(declaration => { declarations[declaration.prop] = declaration.value }))
    expect(declarations['overflow-wrap']).toBe('anywhere')
    expect(declarations['user-select']).toBe('text')
    expect(declarations.background).toBe('var(--surface-elevated)')
    expect(declarations['text-overflow']).toBeUndefined()
    expect(declarations['-webkit-line-clamp']).toBeUndefined()
    expect(source).not.toContain('truncateCanvasTextToWidth')
  })
})
