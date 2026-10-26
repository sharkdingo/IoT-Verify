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
  it('lets the reader choose a stage, stops replay and shows the repaired visitor state', async () => {
    const wrapper = await mountScene()
    document.body.append(wrapper.element)
    const stages = wrapper.findAll<HTMLButtonElement>('.showcase-stages button')
    expect(stages.map(button => button.text())).toEqual(['1发现风险', '2自动修复', '3复查结果'])
    expect(stages.map(button => button.attributes('aria-pressed'))).toEqual(['true', 'false', 'false'])
    expect(wrapper.get('.showcase-model-details').attributes('open')).toBeUndefined()
    await wrapper.get('.showcase-replay').trigger('click')
    await vi.advanceTimersByTimeAsync(1400)
    stages[1]!.element.focus()
    await stages[1]!.trigger('click')
    // jsdom queues a zero-delay selectionchange when a connected button receives focus.
    await vi.advanceTimersByTimeAsync(0)
    expect(document.activeElement).toBe(stages[1]!.element)
    expect(vi.getTimerCount()).toBe(0)
    expect(wrapper.find('.showcase-playback').exists()).toBe(false)
    expect(wrapper.find('.showcase-trace').exists()).toBe(false)
    expect(wrapper.findAll('.showcase-node__state').map(node => node.text())).toEqual(['访客请求', '已开启', '已上锁'])
    expect(wrapper.find('.showcase-node--violation').exists()).toBe(false)
    expect(wrapper.findAll('.showcase-node--changed')).toHaveLength(0)
    expect(wrapper.get('[data-testid="showcase-rule-R1"] .showcase-edge').classes()).toContain('showcase-edge--active')
    expect(wrapper.find('[data-testid="showcase-rule-R2"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="showcase-rule-R3"] .showcase-edge').classes()).not.toContain('showcase-edge--active')
    expect(wrapper.get('.showcase-rule-change').text()).toContain('门灯亮')
    expect(wrapper.get('.showcase-rule-change mark').text()).toBe('移除灯亮开门联动')
    expect(wrapper.get('.showcase-rule-scope').text()).toContain('确认后应用')
    expect(wrapper.get('[data-testid="showcase-rule-definition-R2"]').text()).toContain('已移除')
    expect(wrapper.get('.showcase-node--source .showcase-node__label').text()).toBe('开门请求')
    expect(wrapper.get('.showcase-note').text()).toContain('由外部认证系统提供')
    await vi.advanceTimersByTimeAsync(10000)
    expect(stages[1]!.attributes('aria-pressed')).toBe('true')
    await stages[0]!.trigger('click')
    expect(wrapper.get('.showcase-playback__position').text()).toBe('04 / 04')
    expect(wrapper.get('.showcase-node--door .showcase-node__state').text()).toBe('已解锁')
    expect(vi.getTimerCount()).toBe(0)
  })

  it.each(['zh-CN', 'en'] as const)('compares the same three requirements without pretending to run a check (%s)', async locale => {
    i18n.global.locale.value = locale
    const wrapper = await mountScene()
    const requirements = wrapper.findAll('.showcase-requirements p').map(row => row.text())
    expect(requirements).toHaveLength(3)
    await wrapper.findAll('.showcase-stages button')[2]!.trigger('click')
    const rows = wrapper.findAll('.showcase-results tbody tr')
    expect(rows).toHaveLength(3)
    const satisfied = locale === 'en' ? 'Met' : '满足'
    const violated = locale === 'en' ? 'Violated' : '违反'
    expect(rows.map(row => row.findAll('td').map(cell => cell.text()))).toEqual([
      [violated, satisfied], [satisfied, satisfied], [satisfied, satisfied]
    ])
    expect(wrapper.findAll('.showcase-requirements p').map(row => row.text())).toEqual(requirements)
    expect(wrapper.get('.showcase-results caption').text()).toContain(locale === 'en' ? 'Preset' : '预设')
    expect(wrapper.get('.showcase-reachability').text()).toContain(locale === 'en' ? 'remain reachable' : '仍可发生')
    expect(wrapper.get('.showcase-note').text()).toContain(locale === 'en' ? 'not a live check' : '非本次验证')
    expect(wrapper.get('.showcase-node--door .showcase-node__state').text()).toBe(locale === 'en' ? 'Locked' : '已上锁')
    expect(vi.getTimerCount()).toBe(0)
  })

  it.each([
    { locale: 'zh-CN' as const, context: '屋内无人', unlocked: '已解锁', rule: '门灯亮 → 解锁入户门', spec: '原本上锁的门',
      lightRule: '访客请求 → 开启门灯', ownerRule: '已认证主人请求 → 解锁入户门',
      steps: ['初始 · 门锁上锁', '访客请求到来', '门灯亮 · 门仍锁', '灯亮触发开门'] },
    { locale: 'en' as const, context: 'nobody home', unlocked: 'Unlocked', rule: 'Porch light on → unlock the front door', spec: 'already locked door',
      lightRule: 'visitor request → turn on the porch light', ownerRule: 'Authenticated owner request → unlock the front door',
      steps: ['Initial · door locked', 'Visitor request arrives', 'Light on · door locked', 'Light triggers unlock'] }
  ])('explains the household entrance chain and preserved functions in $locale', async copy => {
    i18n.global.locale.value = copy.locale
    const wrapper = await mountScene()
    expect(wrapper.get('.showcase-context').text()).toContain(copy.context)
    expect(wrapper.get('.showcase-node--door .showcase-node__state').text()).toBe(copy.unlocked)
    const details = wrapper.findAll('.showcase-details p')
    expect(details[0].text()).toContain(copy.lightRule)
    expect(details[1].text()).toContain(copy.rule)
    expect(details[2].text()).toContain(copy.ownerRule)
    expect(details[3].text()).toContain(copy.spec)
    expect(details).toHaveLength(6)
    expect(wrapper.findAll('.showcase-trace li').map(step => step.find('span:last-child').text())).toEqual(copy.steps)
    expect(wrapper.get('.showcase-counterexample').text()).toContain(copy.locale === 'en' ? 'Risk: a visitor unlocks the door' : '风险：访客让门解锁')
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

    expect(states()).toEqual(['无请求', '已关闭', '已上锁'])
    expect(wrapper.get('.showcase-node--door img').attributes('src')).not.toBe(lockImage)
    expect(wrapper.get('.showcase-node--light img').attributes('src')).not.toBe(lightImage)
    expect(wrapper.find('.showcase-counterexample--unsafe').exists()).toBe(false)
    expect(wrapper.findAll('.showcase-signal')).toHaveLength(0)
    expect(wrapper.get('.showcase-trace [aria-current="step"]').text()).toContain('门锁上锁')
    expect(button.text()).toContain('暂停')

    await vi.advanceTimersByTimeAsync(1400)
    expect(states()).toEqual(['访客请求', '已关闭', '已上锁'])
    expect(wrapper.findAll('.showcase-node--changed').map(node => node.classes().find(name => /--(source|light|door)$/.test(name))))
      .toEqual(['showcase-node--source'])
    expect(wrapper.find('.showcase-counterexample--unsafe').exists()).toBe(false)
    expect(wrapper.findAll('.showcase-edge--active')).toHaveLength(0)

    await vi.advanceTimersByTimeAsync(1400)
    expect(states()).toEqual(['访客请求', '已开启', '已上锁'])
    expect(wrapper.findAll('.showcase-node--changed')).toHaveLength(1)
    expect(wrapper.find('.showcase-counterexample--unsafe').exists()).toBe(false)
    expect(wrapper.findAll('.showcase-edge--active')).toHaveLength(1)
    expect(wrapper.get('[data-testid="showcase-rule-R1"] .showcase-edge').classes()).toContain('showcase-edge--active')

    await vi.advanceTimersByTimeAsync(1400)
    expect(states()).toEqual(['访客请求', '已开启', '已解锁'])
    expect(wrapper.findAll('.showcase-node--changed')).toHaveLength(1)
    expect(wrapper.findAll('.showcase-edge--active')).toHaveLength(2)
    expect(wrapper.get('[data-testid="showcase-rule-R2"] .showcase-edge').classes()).toContain('showcase-edge--active')
    expect(wrapper.get('[data-testid="showcase-rule-R3"] .showcase-edge').classes()).not.toContain('showcase-edge--active')
    expect(wrapper.get('.showcase-node--door').classes()).toContain('showcase-node--violation')
    expect(wrapper.get('.showcase-counterexample--unsafe').text()).toContain('风险：访客让门解锁')
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
    expect(wrapper.get('.showcase-playback__position').text()).toBe('01 / 04')
    expect(vi.getTimerCount()).toBe(0)
    await button.trigger('click')
    await vi.advanceTimersByTimeAsync(1400)
    await button.trigger('click')
    expect(wrapper.get('.showcase-playback__position').text()).toBe('02 / 04')
    await button.trigger('click')
    await vi.advanceTimersByTimeAsync(2800)
    expect(wrapper.get('.showcase-playback__position').text()).toBe('04 / 04')
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
    expect(wrapper.get('.showcase-playback__position').text()).toBe('01 / 04')
    await next.trigger('click')
    expect(wrapper.get('.showcase-node--source .showcase-node__state').text()).toBe('访客请求')
    expect(wrapper.get('.showcase-playback__position').text()).toBe('02 / 04')
  })

  it('gives each instance its own direction marker', async () => {
    const host = mount({ components: { TopologyShowcase }, template: '<TopologyShowcase /><TopologyShowcase compact />' }, { global: { plugins: [i18n] } })
    wrappers.push(host)
    await nextTick()
    const [first, second] = host.findAllComponents(TopologyShowcase)
    const firstId = first.get('marker').attributes('id')
    const secondId = second.get('marker').attributes('id')
    expect(firstId).not.toBe(secondId)
    expect(first.get('.showcase-stage-content').attributes('id')).not.toBe(second.get('.showcase-stage-content').attributes('id'))
    for (const wrapper of [first, second]) {
      for (const button of wrapper.findAll('.showcase-stages button')) {
        expect(button.attributes('aria-controls')).toBe(wrapper.get('.showcase-stage-content').attributes('id'))
      }
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
      for (const [rule, fromId, toId, toRight] of [
        ['R1', 'source', 'light', false],
        ['R2', 'light', 'door', true],
        ['R3', 'source', 'door', false]
      ] as const) {
        const source = wrapper.get<HTMLElement>(`.showcase-node--${fromId}`).element.style
        const target = wrapper.get<HTMLElement>(`.showcase-node--${toId}`).element.style
        const sourceX = (parseFloat(source.left) + parseFloat(source.width)) / 100 * width
        const sourceY = parseFloat(source.top) / 100 * height
        const x = (parseFloat(target.left) + (toRight ? parseFloat(target.width) : 0)) / 100 * width
        const y = parseFloat(target.top) / 100 * height
        const points = wrapper.get(`[data-testid="showcase-rule-${rule}"] .showcase-edge`).attributes('d')!
          .match(/-?\d+(?:\.\d+)?/g)!.map(Number)
        expect(points[0]).toBeCloseTo(sourceX)
        expect(points[1]).toBeCloseTo(sourceY)
        expect(points.at(-1)).toBeCloseTo(x)
        expect(points.at(-2)).toBeCloseTo(y)
        expect(Math.abs(points[6]! - sourceY)).toBeLessThanOrEqual(16)
        expect(points[3]! - points[2]!).toBeCloseTo(Math.abs(points[6]! - sourceY))
        expect(Math.abs(points[10]! - points[8]!)).toBeCloseTo(Math.abs(points[6]! - sourceY))
        const label = wrapper.findAll<HTMLElement>('.showcase-edge-label').find(label => label.text() === rule)!.element.style
        expect(parseFloat(label.left)).toBeGreaterThan(sourceX)
        expect(parseFloat(label.left)).toBeLessThan(toRight ? width : x)
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
    for (const [index, activeRules] of [[], [], ['R1'], ['R1', 'R2']].entries()) {
      await wrapper.findAll('.showcase-trace button')[index].trigger('click')
      for (const rule of ['R1', 'R2', 'R3']) {
        const id = markers[activeRules.includes(rule) ? 1 : 0].attributes('id')
        expect(wrapper.get(`[data-testid="showcase-rule-${rule}"] .showcase-edge`).attributes('marker-end')).toBe(`url(#${id})`)
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
    expect(readings.findAll('span').map(reading => reading.text())).toContain('访客请求')
    expect(readings.find('.trace-device-state-enter-from').text()).toBe('访客请求')
    expect(wrapper.find('.showcase-node--source .showcase-node__artwork img').exists()).toBe(true)
    advanceFrame(34)
    advanceFrame(51)
    await nextTick()
    expect(readings.text()).toBe('访客请求')
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
    expect(wrapper.text()).toContain('非本次验证')
    expect(wrapper.get('.showcase-counterexample').text()).toContain('风险：访客让门解锁')
    await wrapper.findAll('.showcase-trace button')[0].trigger('click')
    expect(wrapper.get('.showcase-node--door .showcase-node__state').text()).toBe('已上锁')
    expect(wrapper.find('.showcase-counterexample--unsafe').exists()).toBe(false)
    await wrapper.get('.showcase-replay').trigger('click')
    await vi.advanceTimersByTimeAsync(4200)
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
    expect(wrapper.text()).toContain('not a live check')
    expect(wrapper.findAll('.showcase-node')).toHaveLength(3)
    expect(wrapper.find('.showcase-details').exists()).toBe(false)
    expect(wrapper.find('.showcase-trace').exists()).toBe(false)
    expect(wrapper.get('.showcase-context').text()).toContain('nobody home')
    expect(wrapper.get('.showcase-counterexample').text()).toContain('Risk: a visitor unlocks the door')
    expect(wrapper.get('.showcase-replay').text()).toContain('Replay example')
    await wrapper.get('[aria-label="Previous state"]').trigger('click')
    expect(wrapper.get('.showcase-node--door .showcase-node__state').text()).toBe('Locked')
    expect(wrapper.get('.showcase-playback__position').text()).toBe('03 / 04')
  })
})
