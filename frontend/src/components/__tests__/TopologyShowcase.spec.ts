// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/assets/i18n'
import TopologyShowcase from '../common/TopologyShowcase.vue'

let prefersReducedMotion: boolean
let onMotionChange: () => void
let removeListener: ReturnType<typeof vi.fn>
let frames: Map<number, FrameRequestCallback>
let nextFrame: number
const resizeObservers: Array<{
  callback: ResizeObserverCallback
  observe: ReturnType<typeof vi.fn>
  disconnect: ReturnType<typeof vi.fn>
}> = []
const wrappers: ReturnType<typeof mount>[] = []

beforeEach(() => {
  vi.useFakeTimers()
  prefersReducedMotion = false
  frames = new Map()
  nextFrame = 0
  resizeObservers.length = 0
  vi.stubGlobal('ResizeObserver', class {
    observe = vi.fn()
    disconnect = vi.fn()
    constructor(public callback: ResizeObserverCallback) { resizeObservers.push(this) }
  })
  vi.stubGlobal('requestAnimationFrame', vi.fn((callback: FrameRequestCallback) => {
    frames.set(++nextFrame, callback)
    return nextFrame
  }))
  vi.stubGlobal('cancelAnimationFrame', vi.fn((id: number) => frames.delete(id)))
  removeListener = vi.fn()
  i18n.global.locale.value = 'zh-CN'
  window.matchMedia = vi.fn().mockImplementation(() => ({
    get matches() { return prefersReducedMotion },
    addEventListener: vi.fn((_event, listener) => { onMotionChange = listener }),
    removeEventListener: removeListener
  }))
})

afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  document.body.innerHTML = ''
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const mountScene = async (compact = false, realTransitions = false) => {
  const wrapper = mount(TopologyShowcase, {
    props: { compact }, global: { plugins: [i18n], stubs: { transition: !realTransitions } }
  })
  wrappers.push(wrapper)
  await nextTick()
  return wrapper
}

const advanceFrame = (time: number) => {
  const pending = [...frames.values()]
  frames.clear()
  pending.forEach(callback => callback(time))
}

const preparePointer = (wrapper: Awaited<ReturnType<typeof mountScene>>) => {
  const element = wrapper.get<HTMLElement>('.showcase-viewport').element
  vi.spyOn(element, 'getBoundingClientRect').mockReturnValue({
    x: 0, y: 0, left: 0, top: 0, right: 400, bottom: 300, width: 400, height: 300,
    toJSON: () => ({})
  })
  return element
}

describe('illustrative topology showcase', () => {
  it.each([
    { locale: 'zh-CN' as const, context: '屋内无人', unlocked: '已解锁', rule: '解锁入户门', spec: '应保持上锁',
      lightRule: '开启门廊灯',
      steps: ['屋内无人 · 门锁上锁', '门外检测到活动', '下一状态 · 门锁解锁'] },
    { locale: 'en' as const, context: 'nobody home', unlocked: 'Unlocked', rule: 'unlock the front door', spec: 'must stay locked',
      lightRule: 'turn on the porch light',
      steps: ['Nobody home · door locked', 'Motion detected outside', 'Next state · door unlocked'] }
  ])('explains the unsafe unlock and next-state path in $locale', async copy => {
    i18n.global.locale.value = copy.locale
    const wrapper = await mountScene()
    expect(wrapper.get('.showcase-context').text()).toContain(copy.context)
    expect(wrapper.get('.showcase-node--door .showcase-node__state').text()).toBe(copy.unlocked)
    const details = wrapper.findAll('.showcase-details p')
    expect(details[0].text()).toContain(copy.lightRule)
    expect(details[1].text()).toContain(copy.rule)
    expect(details[2].text()).toContain(copy.spec)
    expect(wrapper.findAll('.showcase-trace li').map(step => step.find('span:last-child').text())).toEqual(copy.steps)
    expect(wrapper.get('.showcase-counterexample').text()).toContain(copy.locale === 'en' ? 'specification is violated' : '违反安全规约')
    expect(wrapper.findAll('.showcase-node .showcase-node__state')).toHaveLength(3)
    expect(wrapper.find('.showcase-node--camera').exists()).toBe(false)
  })

  it('replays the device readings, delivered rules and violation together without replacing focused controls', async () => {
    const wrapper = await mountScene()
    const world = wrapper.get('.showcase-world').element
    const nodes = wrapper.findAll('.showcase-node').map(node => node.element)
    const button = wrapper.get<HTMLButtonElement>('.showcase-replay')
    const states = () => wrapper.findAll('.showcase-node__state').map(node => node.text())
    const lockImage = wrapper.get('.showcase-node--door img').attributes('src')
    const lightImage = wrapper.get('.showcase-node--light img').attributes('src')
    expect(vi.getTimerCount()).toBe(0)
    document.body.append(wrapper.element)
    button.element.focus()
    await button.trigger('click')

    expect(states()).toEqual(['未检测到活动', '已关闭', '已上锁'])
    expect(wrapper.get('.showcase-node--door img').attributes('src')).not.toBe(lockImage)
    expect(wrapper.get('.showcase-node--light img').attributes('src')).not.toBe(lightImage)
    expect(wrapper.find('.showcase-counterexample--unsafe').exists()).toBe(false)
    expect(wrapper.findAll('.showcase-signal')).toHaveLength(0)
    expect(wrapper.get('.showcase-trace [aria-current="step"]').text()).toContain('门锁上锁')
    expect(button.text()).toContain('暂停')

    await vi.advanceTimersByTimeAsync(1400)
    expect(states()).toEqual(['检测到活动', '已关闭', '已上锁'])
    expect(wrapper.findAll('.showcase-node--changed').map(node => node.classes().find(name => /--(source|light|door)$/.test(name))))
      .toEqual(['showcase-node--source'])
    expect(wrapper.find('.showcase-counterexample--unsafe').exists()).toBe(false)
    expect(wrapper.findAll('.showcase-edge--active')).toHaveLength(0)

    await vi.advanceTimersByTimeAsync(1400)
    expect(states()).toEqual(['检测到活动', '已开启', '已解锁'])
    expect(wrapper.findAll('.showcase-node--changed')).toHaveLength(2)
    expect(wrapper.findAll('.showcase-edge--active')).toHaveLength(2)
    expect(wrapper.get('.showcase-node--door').classes()).toContain('showcase-node--violation')
    expect(wrapper.get('.showcase-counterexample--unsafe').text()).toContain('违反安全规约')
    expect(wrapper.get('.showcase-node--door img').attributes('src')).toBe(lockImage)
    expect(button.text()).toContain('重播示意')
    expect(vi.getTimerCount()).toBe(0)
    expect(wrapper.get('.showcase-world').element).toBe(world)
    wrapper.findAll('.showcase-node').forEach((node, index) => expect(node.element).toBe(nodes[index]))
    expect(wrapper.get('.showcase-replay').element).toBe(button.element)
    expect(document.activeElement).toBe(button.element)
  })

  it('pauses and resumes from the selected step, including repeated replay', async () => {
    const wrapper = await mountScene()
    const button = wrapper.get('.showcase-replay')
    await button.trigger('click')
    await button.trigger('click')
    await vi.advanceTimersByTimeAsync(4000)
    expect(wrapper.get('.showcase-playback__position').text()).toBe('01 / 03')
    expect(vi.getTimerCount()).toBe(0)
    await button.trigger('click')
    await vi.advanceTimersByTimeAsync(1400)
    await button.trigger('click')
    expect(wrapper.get('.showcase-playback__position').text()).toBe('02 / 03')
    await button.trigger('click')
    await vi.advanceTimersByTimeAsync(1400)
    expect(wrapper.get('.showcase-playback__position').text()).toBe('03 / 03')
    await button.trigger('click')
    expect(wrapper.get('.showcase-node--door .showcase-node__state').text()).toBe('已上锁')
    expect(vi.getTimerCount()).toBe(1)
  })

  it('stops playback when seeking and keeps previous/next controls bounded', async () => {
    const wrapper = await mountScene()
    const previous = wrapper.get<HTMLButtonElement>('[aria-label="上一个状态"]')
    const next = wrapper.get<HTMLButtonElement>('[aria-label="下一个状态"]')
    expect(next.element.disabled).toBe(true)
    await wrapper.get('.showcase-replay').trigger('click')
    expect(previous.element.disabled).toBe(true)
    await wrapper.findAll('.showcase-trace button')[2].trigger('click')
    expect(vi.getTimerCount()).toBe(0)
    await previous.trigger('click')
    expect(wrapper.get('.showcase-node--door .showcase-node__state').text()).toBe('已上锁')
    expect(wrapper.find('.showcase-node--door.showcase-node--violation').exists()).toBe(false)
    await previous.trigger('click')
    expect(previous.element.disabled).toBe(true)
    await previous.trigger('click')
    expect(wrapper.get('.showcase-playback__position').text()).toBe('01 / 03')
    await next.trigger('click')
    expect(wrapper.get('.showcase-node--source .showcase-node__state').text()).toBe('检测到活动')
    expect(wrapper.get('.showcase-playback__position').text()).toBe('02 / 03')
  })

  it('gives each instance its own direction marker', async () => {
    const host = mount({ components: { TopologyShowcase }, template: '<TopologyShowcase /><TopologyShowcase compact />' }, { global: { plugins: [i18n] } })
    wrappers.push(host)
    await nextTick()
    const [first, second] = host.findAllComponents(TopologyShowcase)
    const firstId = first.get('marker').attributes('id')
    const secondId = second.get('marker').attributes('id')
    expect(firstId).not.toBe(secondId)
    for (const wrapper of [first, second]) {
      const ids = wrapper.findAll('marker').map(marker => marker.attributes('id'))
      for (const edge of wrapper.findAll('.showcase-edge')) {
        expect(ids).toContain(edge.attributes('marker-end')?.slice(5, -1))
      }
    }
  })

  it.each([false, true])('keeps rule endpoints on the device ports after resizing (compact: %s)', async compact => {
    const wrapper = await mountScene(compact)
    const observer = resizeObservers[0]!
    expect(observer.observe).toHaveBeenCalledWith(wrapper.get('.showcase-world').element)
    for (const [width, height] of [[320, 304], [600, 360], [420, 304]]) {
      observer.callback([{ contentRect: { width, height } } as ResizeObserverEntry], observer as unknown as ResizeObserver)
      await nextTick()
      expect(wrapper.get('svg').attributes('viewBox')).toBe(`0 0 ${width} ${height}`)
      expect(wrapper.get('svg').attributes('preserveAspectRatio')).not.toBe('none')
      const source = wrapper.get<HTMLElement>('.showcase-node--source').element.style
      const sourceX = (parseFloat(source.left) + parseFloat(source.width)) / 100 * width
      const sourceY = parseFloat(source.top) / 100 * height
      for (const [index, targetId] of ['light', 'door'].entries()) {
        const target = wrapper.get<HTMLElement>(`.showcase-node--${targetId}`).element.style
        const x = parseFloat(target.left) / 100 * width
        const y = parseFloat(target.top) / 100 * height
        const points = wrapper.findAll('.showcase-edge')[index].attributes('d')!
          .match(/-?\d+(?:\.\d+)?/g)!.map(Number)
        expect(points[0]).toBeCloseTo(sourceX)
        expect(points[1]).toBeCloseTo(sourceY)
        expect(points.at(-1)).toBeCloseTo(x)
        expect(points.at(-2)).toBeCloseTo(y)
        expect(Math.abs(points[6]! - sourceY)).toBeLessThanOrEqual(16)
        expect(points[3]! - points[2]!).toBeCloseTo(Math.abs(points[6]! - sourceY))
        expect(points[10]! - points[8]!).toBeCloseTo(Math.abs(points[6]! - sourceY))
        const label = wrapper.findAll<HTMLElement>('.showcase-edge-label')[index].element.style
        expect(parseFloat(label.left)).toBeGreaterThan(sourceX)
        expect(parseFloat(label.left)).toBeLessThan(x)
        expect(parseFloat(label.left)).toBeCloseTo(points[3]!)
        expect(parseFloat(label.top)).toBeGreaterThan(Math.min(sourceY, y) + 16)
        expect(parseFloat(label.top)).toBeLessThan(Math.max(sourceY, y) - 16)
      }
      for (const marker of wrapper.findAll('marker')) {
        expect(marker.attributes('markerUnits')).toBe('userSpaceOnUse')
        expect(marker.attributes('markerWidth')).toBe('8')
        expect(marker.attributes('markerHeight')).toBe('8')
      }
    }
  })

  it('uses the untransformed world size before the first resize notification', async () => {
    const width = vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(480)
    const height = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(304)
    const bounds = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      x: 0, y: 0, left: 0, top: 0, right: 450, bottom: 290, width: 450, height: 290,
      toJSON: () => ({})
    })
    try {
      const wrapper = await mountScene()
      expect(wrapper.get('svg').attributes('viewBox')).toBe('0 0 480 304')
      expect(bounds).not.toHaveBeenCalled()
    } finally {
      width.mockRestore()
      height.mockRestore()
      bounds.mockRestore()
    }
  })

  it('retains measured geometry while hidden and releases the resize observer on unmount', async () => {
    const wrapper = await mountScene()
    const observer = resizeObservers[0]!
    const resize = async (width: number, height: number) => {
      observer.callback([{ contentRect: { width, height } } as ResizeObserverEntry], observer as unknown as ResizeObserver)
      await nextTick()
    }
    await resize(320, 304)
    const path = wrapper.get('.showcase-edge').attributes('d')
    await resize(0, 0)
    expect(wrapper.get('svg').attributes('viewBox')).toBe('0 0 320 304')
    expect(wrapper.get('.showcase-edge').attributes('d')).toBe(path)
    await resize(600, 360)
    expect(wrapper.get('.showcase-edge').attributes('d')).not.toBe(path)
    wrapper.unmount()
    wrappers.splice(wrappers.indexOf(wrapper), 1)
    expect(observer.disconnect).toHaveBeenCalledOnce()
  })

  it('matches quiet and delivered arrowheads to the selected rule state', async () => {
    const wrapper = await mountScene()
    const markers = wrapper.findAll('marker')
    expect(markers[0].get('path').classes()).not.toContain('showcase-arrow--active')
    expect(markers[1].get('path').classes()).toContain('showcase-arrow--active')
    for (const index of [0, 1, 2]) {
      await wrapper.findAll('.showcase-trace button')[index].trigger('click')
      const id = markers[index === 2 ? 1 : 0].attributes('id')
      for (const edge of wrapper.findAll('.showcase-edge')) {
        expect(edge.attributes('marker-end')).toBe(`url(#${id})`)
      }
    }
  })

  it('shows the incoming reading immediately during a real transition', async () => {
    const wrapper = await mountScene(false, true)
    await wrapper.findAll('.showcase-trace button')[0].trigger('click')
    advanceFrame(0)
    advanceFrame(17)
    await nextTick()
    await wrapper.findAll('.showcase-trace button')[1].trigger('click')
    const readings = wrapper.get('.showcase-node--source .device-reading-slot')
    expect(readings.findAll('span').map(reading => reading.text())).toContain('检测到活动')
    expect(readings.find('.trace-device-state-enter-from').text()).toBe('检测到活动')
    expect(wrapper.find('.showcase-node--source .showcase-node__artwork img').exists()).toBe(true)
    advanceFrame(34)
    advanceFrame(51)
    await nextTick()
    expect(readings.text()).toBe('检测到活动')
  })

  it('releases a pending state transition on unmount', async () => {
    const wrapper = await mountScene()
    await wrapper.get('.showcase-replay').trigger('click')
    expect(vi.getTimerCount()).toBe(1)
    wrapper.unmount()
    wrappers.splice(wrappers.indexOf(wrapper), 1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it.each([60, 120])('damps pointer tilt and stops requesting frames at %s Hz', async frequency => {
    const wrapper = await mountScene()
    const viewport = preparePointer(wrapper)
    await wrapper.get('.showcase-viewport').trigger('pointermove', {
      pointerType: 'mouse', clientX: 400, clientY: 0
    })
    expect(frames.size).toBe(1)
    advanceFrame(0)
    const firstYaw = Number.parseFloat(viewport.style.getPropertyValue('--showcase-yaw'))
    expect(firstYaw).toBeGreaterThan(0)
    expect(firstYaw).toBeLessThan(3)
    for (let index = 1; index <= frequency * 0.3; index++) advanceFrame(index * 1000 / frequency)
    const yawAt300ms = Number.parseFloat(viewport.style.getPropertyValue('--showcase-yaw'))
    expect(yawAt300ms).toBeGreaterThan(2.9)
    expect(yawAt300ms).toBeLessThan(2.925)
    for (let index = frequency * 0.3 + 1; index <= frequency; index++) advanceFrame(index * 1000 / frequency)
    expect(viewport.style.getPropertyValue('--showcase-yaw')).toBe('3.000deg')
    expect(viewport.style.getPropertyValue('--showcase-pitch')).toBe('2.000deg')
    expect(frames.size).toBe(0)

    await wrapper.get('.showcase-viewport').trigger('pointerleave')
    advanceFrame(1100)
    expect(Number.parseFloat(viewport.style.getPropertyValue('--showcase-yaw'))).toBeGreaterThan(0)
    for (let time = 1100 + 1000 / frequency; time <= 2200; time += 1000 / frequency) advanceFrame(time)
    expect(viewport.style.getPropertyValue('--showcase-yaw')).toBe('0.000deg')
    expect(frames.size).toBe(0)
  })

  it('does not tilt for touch input or reduced motion', async () => {
    const wrapper = await mountScene()
    preparePointer(wrapper)
    await wrapper.get('.showcase-viewport').trigger('pointermove', {
      pointerType: 'touch', clientX: 400, clientY: 0
    })
    expect(frames.size).toBe(0)
    prefersReducedMotion = true
    onMotionChange()
    await wrapper.get('.showcase-viewport').trigger('pointermove', {
      pointerType: 'mouse', clientX: 400, clientY: 0
    })
    expect(frames.size).toBe(0)
  })

  it('cancels an active tilt when motion is reduced or the component unmounts', async () => {
    const wrapper = await mountScene()
    const viewport = preparePointer(wrapper)
    const move = () => wrapper.get('.showcase-viewport').trigger('pointermove', {
      pointerType: 'mouse', clientX: 400, clientY: 0
    })
    await move()
    advanceFrame(0)
    expect(frames.size).toBe(1)
    prefersReducedMotion = true
    onMotionChange()
    expect(frames.size).toBe(0)
    expect(viewport.style.getPropertyValue('--showcase-yaw')).toBe('0.000deg')

    prefersReducedMotion = false
    onMotionChange()
    await move()
    expect(frames.size).toBe(1)
    wrapper.unmount()
    wrappers.splice(wrappers.indexOf(wrapper), 1)
    expect(frames.size).toBe(0)
  })

  it('keeps all steps usable without spatial animation under reduced motion', async () => {
    prefersReducedMotion = true
    const wrapper = await mountScene()
    expect(wrapper.findAll('.showcase-node')).toHaveLength(3)
    expect(wrapper.get('.showcase-world').classes()).not.toContain('showcase-world--animated')
    expect(wrapper.find('.showcase-replay').exists()).toBe(true)
    expect(wrapper.text()).toContain('不代表验证结论')
    expect(wrapper.get('.showcase-counterexample').text()).toContain('违反安全规约')
    await wrapper.findAll('.showcase-trace button')[0].trigger('click')
    expect(wrapper.get('.showcase-node--door .showcase-node__state').text()).toBe('已上锁')
    expect(wrapper.find('.showcase-counterexample--unsafe').exists()).toBe(false)
    await wrapper.get('.showcase-replay').trigger('click')
    await vi.advanceTimersByTimeAsync(2800)
    expect(wrapper.get('.showcase-node--door .showcase-node__state').text()).toBe('已解锁')
    expect(frames.size).toBe(0)
  })

  it('responds to a changed motion preference and releases the listener', async () => {
    const wrapper = await mountScene()
    prefersReducedMotion = true
    onMotionChange()
    await nextTick()
    expect(wrapper.find('.showcase-replay').exists()).toBe(true)
    expect(wrapper.get('.showcase-world').classes()).not.toContain('showcase-world--animated')

    wrapper.unmount()
    wrappers.splice(wrappers.indexOf(wrapper), 1)
    expect(removeListener).toHaveBeenCalledWith('change', onMotionChange)
  })

  it('retains the example disclaimer in compact and English layouts', async () => {
    i18n.global.locale.value = 'en'
    const wrapper = await mountScene(true)
    expect(wrapper.text()).toContain('Example model')
    expect(wrapper.text()).toContain('not a verification verdict')
    expect(wrapper.findAll('.showcase-node')).toHaveLength(3)
    expect(wrapper.find('.showcase-details').exists()).toBe(false)
    expect(wrapper.find('.showcase-trace').exists()).toBe(false)
    expect(wrapper.get('.showcase-context').text()).toContain('nobody home')
    expect(wrapper.get('.showcase-counterexample').text()).toContain('door is unlocked')
    expect(wrapper.get('.showcase-replay').text()).toContain('Replay example')
    await wrapper.get('[aria-label="Previous state"]').trigger('click')
    expect(wrapper.get('.showcase-node--door .showcase-node__state').text()).toBe('Locked')
    expect(wrapper.get('.showcase-playback__position').text()).toBe('02 / 03')
  })
})
