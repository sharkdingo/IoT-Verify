// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/assets/i18n'
import TopologyShowcase from '../common/TopologyShowcase.vue'

let prefersReducedMotion: boolean
let frames: Map<number, FrameRequestCallback>
let nextFrame: number
const wrappers: ReturnType<typeof mount>[] = []

beforeEach(() => {
  vi.useFakeTimers()
  prefersReducedMotion = false
  frames = new Map()
  nextFrame = 0
  vi.stubGlobal('ResizeObserver', class {
    observe = vi.fn()
    disconnect = vi.fn()
  })
  vi.stubGlobal('requestAnimationFrame', vi.fn((callback: FrameRequestCallback) => {
    frames.set(++nextFrame, callback)
    return nextFrame
  }))
  vi.stubGlobal('cancelAnimationFrame', vi.fn((id: number) => frames.delete(id)))
  i18n.global.locale.value = 'zh-CN'
  window.matchMedia = vi.fn().mockImplementation(() => ({
    get matches() { return prefersReducedMotion },
    addEventListener: vi.fn(),
    removeEventListener: vi.fn()
  }))
})

afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  document.body.innerHTML = ''
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

const mountScene = async () => {
  const wrapper = mount(TopologyShowcase, {
    global: { plugins: [i18n], stubs: { transition: true } }
  })
  wrappers.push(wrapper)
  await nextTick()
  return wrapper
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
})
