// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/assets/i18n'
import PlaybackTerminal from '../PlaybackTerminal.vue'
import HintTooltip from '@/components/common/HintTooltip.vue'

const props = {
  kind: 'counterexample' as const,
  selectedStateIndex: 1,
  totalStates: 4,
  playing: true,
  stateLabel: (index: number) => `State ${index + 1}`
}
const mountNumberScene = (kind: 'simulation' | 'counterexample' | 'fuzzing', selected: number, totalStates: number) => {
  const Scene = defineComponent({
    components: { PlaybackTerminal },
    setup: () => ({ ...props, kind, totalStates, selected: ref(selected), playing: ref(true) }),
    template: `<section>
      <div id="board-playback-transport" /><div id="board-playback-timeline" /><div id="board-playback-locate" />
      <PlaybackTerminal :kind="kind" :selected-state-index="selected" :total-states="totalStates"
        :playing="playing" :state-label="stateLabel" @seek="selected = $event" @scrub-start="playing = false" />
    </section>`
  })
  return mount(Scene, { attachTo: document.body, global: { plugins: [i18n] } })
}
beforeEach(() => {
  i18n.global.locale.value = 'en'
  Element.prototype.scrollIntoView = vi.fn()
})
afterEach(() => { i18n.global.locale.value = 'zh-CN' })

describe('PlaybackTerminal', () => {
  it.each(['simulation', 'counterexample', 'fuzzing'] as const)(
    'gives %s state and group navigation localized visual hints without changing the selected state', async kind => {
      const wrapper = mount(PlaybackTerminal, {
        props: { ...props, kind, totalStates: 100 },
        global: { plugins: [i18n], stubs: { Teleport: true } }
      })
      try {
        for (const language of ['zh-CN', 'en'] as const) {
          i18n.global.locale.value = language
          await nextTick()
          const source = wrapper.get(`[data-testid="${kind === 'simulation' ? 'simulation' : 'trace'}-timeline-scene-source"]`)
          expect(source.text()).toBe(i18n.global.t('app.playbackSavedScene'))
          expect(source.text()).not.toContain('app.')
          expect(source.attributes('tabindex')).toBe('0')
          const sourceHint = wrapper.findAllComponents(HintTooltip)
            .find(item => item.props('content') === i18n.global.t('app.playbackSavedSceneHint'))
          expect(sourceHint).toBeDefined()
          expect(sourceHint!.props('content')).not.toContain('app.')
          for (const key of ['app.traceVisualization.previousState', 'app.traceVisualization.nextState',
            'app.playbackPreviousWindow', 'app.playbackNextWindow']) {
            const label = i18n.global.t(key)
            const hint = wrapper.findAllComponents(HintTooltip).find(item => item.props('content') === label)
            expect(hint, label).toBeDefined()
            expect(hint!.get('button').attributes('aria-label')).toBe(label)
          }
          expect(wrapper.emitted('seek')).toBeUndefined()
          expect(wrapper.emitted('toggle-play')).toBeUndefined()
        }
      } finally { wrapper.unmount() }
    })

  it('keeps each view in its own scroll region without resetting the other views', async () => {
    const wrapper = mount(PlaybackTerminal, {
      props,
      slots: { changes: '<p>Change evidence</p>', details: '<p>State evidence</p>' },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    try {
      const changes = wrapper.get('#trace-playback-panel-changes').element as HTMLElement
      const details = wrapper.get('#trace-playback-panel-details').element as HTMLElement
      expect(changes.closest('.iot-scroll-region')).toBe(changes)
      expect(details.closest('.iot-scroll-region')).toBe(details)
      await wrapper.get('[data-testid="trace-timeline-changes-tab"]').trigger('click')
      changes.scrollTop = 120
      await wrapper.get('[data-testid="trace-timeline-details-tab"]').trigger('click')
      expect(details.scrollTop).toBe(0)
      details.scrollTop = 60
      await wrapper.get('[data-testid="trace-timeline-changes-tab"]').trigger('click')
      expect(changes.scrollTop).toBe(120)
      await wrapper.get('[data-testid="trace-timeline-details-tab"]').trigger('click')
      expect(details.scrollTop).toBe(60)
      expect(wrapper.emitted('seek')).toBeUndefined()
      expect(wrapper.emitted('toggle-play')).toBeUndefined()
    } finally { wrapper.unmount() }
  })

  it('changes views without seeking or pausing, and keeps transport controls visible', async () => {
    const wrapper = mount(PlaybackTerminal, {
      props,
      slots: { run: '<p>Run evidence</p>', changes: '<p>Previous → current values</p>' },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    const changes = wrapper.get('[data-testid="trace-timeline-changes-tab"]')
    await changes.trigger('click')
    expect(changes.attributes('aria-selected')).toBe('true')
    expect(wrapper.get('#trace-playback-panel-changes').isVisible()).toBe(true)
    expect(wrapper.find('[data-testid="trace-timeline-timeline-tab"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="trace-timeline-track"]').isVisible()).toBe(true)
    expect(wrapper.get('[data-testid="trace-timeline-play"]').isVisible()).toBe(true)
    expect(wrapper.get('[data-testid="trace-timeline-play"]').attributes('aria-label')).toBe('Pause')
    await wrapper.get('[data-testid="trace-timeline-run-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="trace-timeline-track"]').isVisible()).toBe(true)
    expect(wrapper.findAll('[data-testid="trace-timeline-track"]')).toHaveLength(1)
    expect(wrapper.get('#trace-playback-panel-run').isVisible()).toBe(true)
    expect(wrapper.emitted('seek')).toBeUndefined()
    expect(wrapper.emitted('toggle-play')).toBeUndefined()
    wrapper.unmount()
  })

  it('places one transport in the canvas strip and keeps it usable with the terminal hidden', async () => {
    const Scene = defineComponent({
      components: { PlaybackTerminal },
      setup: () => ({ ...props, panelOpen: ref(true) }),
      template: `<section>
        <div id="board-playback-transport" />
        <div id="board-playback-timeline" />
        <div id="board-playback-locate" />
        <button data-testid="panel-toggle" @click="panelOpen = !panelOpen">Toggle terminal</button>
        <PlaybackTerminal v-show="panelOpen" :panel-open="panelOpen" :kind="kind"
          :selected-state-index="selectedStateIndex" :total-states="totalStates"
          :playing="playing" :state-label="stateLabel" :violation-steps="[1]" />
      </section>`
    })
    const wrapper = mount(Scene, { attachTo: document.body, global: { plugins: [i18n] } })
    try {
      await nextTick()
      const terminal = wrapper.get('[data-testid="trace-timeline"]')
      const play = wrapper.get('[data-testid="trace-timeline-play"]')
      expect(play.element.closest('#board-playback-transport')).not.toBeNull()
      const locate = wrapper.get('[data-testid="trace-timeline-jump-violation"]')
      expect(locate.element.closest('#board-playback-locate')).not.toBeNull()
      expect(terminal.find('.playback-terminal__transport').exists()).toBe(false)
      expect(wrapper.findAll('[data-testid="trace-timeline-step-input"]')).toHaveLength(1)
      await wrapper.get('[data-testid="trace-timeline-changes-tab"]').trigger('click')
      await wrapper.get('[data-testid="panel-toggle"]').trigger('click')
      expect(terminal.isVisible()).toBe(false)
      expect(play.isVisible()).toBe(true)
      expect(locate.isVisible()).toBe(true)
      expect(wrapper.get('[data-testid="trace-timeline-track"]').isVisible()).toBe(true)
      expect(wrapper.get('[data-testid="trace-timeline-state-1"]').element.closest('#board-playback-timeline')).not.toBeNull()
      expect(wrapper.get('[data-testid="trace-timeline-scene-source"]').isVisible()).toBe(true)
      await wrapper.get('[data-testid="trace-timeline-state-1"]').trigger('keydown', { key: 'End' })
      expect(wrapper.getComponent(PlaybackTerminal).emitted('seek')).toEqual([[3]])
      await play.trigger('click')
      expect(wrapper.getComponent(PlaybackTerminal).emitted('toggle-play')).toEqual([[]])
      await wrapper.get('[data-testid="panel-toggle"]').trigger('click')
      expect(wrapper.get('[data-testid="trace-timeline-changes-tab"]').attributes('aria-selected')).toBe('true')
    } finally { wrapper.unmount() }
  })

  it('moves keyboard focus and tab selection together', async () => {
    const wrapper = mount(PlaybackTerminal, { attachTo: document.body, props, global: { plugins: [i18n], stubs: { Teleport: true } } })
    await wrapper.get('[data-testid="trace-timeline-changes-tab"]').trigger('keydown', { key: 'ArrowRight' })
    await nextTick()
    const changes = wrapper.get('[data-testid="trace-timeline-details-tab"]')
    expect(changes.attributes('aria-selected')).toBe('true')
    expect(changes.attributes('tabindex')).toBe('0')
    expect(document.activeElement).toBe(changes.element)
    await changes.trigger('keydown', { key: 'Home' })
    await nextTick()
    expect(wrapper.get('[data-testid="trace-timeline-changes-tab"]').attributes('aria-selected')).toBe('true')
    wrapper.unmount()
  })

  it('reveals the active tab on selection, reopening and available-width changes', async () => {
    let resize: (() => void) | undefined
    const observe = vi.fn()
    const disconnect = vi.fn()
    vi.stubGlobal('ResizeObserver', class {
      constructor(callback: ResizeObserverCallback) {
        resize ??= () => callback([], this as unknown as ResizeObserver)
      }
      observe = observe
      disconnect = disconnect
    })
    const wrapper = mount(PlaybackTerminal, { attachTo: document.body, props, global: { plugins: [i18n], stubs: { Teleport: true } } })
    try {
      await nextTick()
      expect(observe).toHaveBeenCalledWith(wrapper.get('[role="tablist"]').element)
      const details = wrapper.get('[data-testid="trace-timeline-details-tab"]')
      vi.mocked(Element.prototype.scrollIntoView).mockClear()
      await details.trigger('click')
      await nextTick()
      expect(vi.mocked(Element.prototype.scrollIntoView).mock.instances).toContain(details.element)
      vi.mocked(Element.prototype.scrollIntoView).mockClear()
      resize!()
      await nextTick()
      expect(vi.mocked(Element.prototype.scrollIntoView).mock.instances).toContain(details.element)
      await wrapper.setProps({ panelOpen: false })
      vi.mocked(Element.prototype.scrollIntoView).mockClear()
      resize!()
      await nextTick()
      expect(Element.prototype.scrollIntoView).not.toHaveBeenCalled()
      await wrapper.setProps({ panelOpen: true })
      await nextTick()
      expect(vi.mocked(Element.prototype.scrollIntoView).mock.instances).toContain(details.element)
    } finally {
      wrapper.unmount()
      vi.unstubAllGlobals()
    }
    expect(disconnect).toHaveBeenCalledTimes(2)
  })

  it('announces manual scrubbing even when the pointer selects the current state', async () => {
    const wrapper = mount(PlaybackTerminal, { props: { ...props, selectedStateIndex: 0 }, global: { plugins: [i18n], stubs: { Teleport: true } } })
    const track = wrapper.get('[data-testid="trace-timeline-track"]')
    ;(track.element as HTMLElement).setPointerCapture = vi.fn()
    await track.trigger('pointerdown', { clientX: 0, pointerId: 1 })
    expect(wrapper.emitted('scrub-start')).toEqual([[]])
    expect(wrapper.emitted('seek')).toBeUndefined()
    wrapper.unmount()
  })

  it('clamps numeric seeks to the saved state sequence', async () => {
    const wrapper = mount(PlaybackTerminal, { props, global: { plugins: [i18n], stubs: { Teleport: true } } })
    const input = wrapper.get('[data-testid="trace-timeline-step-input"]')
    await input.setValue('99')
    await input.setValue('0')
    expect(wrapper.emitted('seek')).toEqual([[3], [0]])
    wrapper.unmount()
  })

  it.each(['simulation', 'counterexample', 'fuzzing'] as const)('lets a %s state number be edited before committing the seek', async kind => {
    const wrapper = mountNumberScene(kind, 11, 120)
    try {
      await nextTick()
      const terminal = wrapper.getComponent(PlaybackTerminal)
      const input = wrapper.get('input[type="number"]')
      for (const draft of ['', '1', '10']) {
        (input.element as HTMLInputElement).value = draft
        await input.trigger('input')
        expect(terminal.props('selectedStateIndex')).toBe(11)
        expect(terminal.emitted('seek')).toBeUndefined()
        expect(terminal.props('playing')).toBe(false)
        expect((input.element as HTMLInputElement).value).toBe(draft)
        expect(wrapper.get('input[type="number"]').element).toBe(input.element)
      }
      await input.trigger('keydown', { key: 'Enter' })
      expect(terminal.props('selectedStateIndex')).toBe(9)
      expect((input.element as HTMLInputElement).value).toBe('10')
      await input.trigger('blur')
      expect(terminal.emitted('seek')).toEqual([[9]])
    } finally { wrapper.unmount() }
  })

  it('restores an empty number and normalizes an out-of-range number even when the selected state does not change', async () => {
    const wrapper = mountNumberScene('counterexample', 3, 4)
    try {
      await nextTick()
      const terminal = wrapper.getComponent(PlaybackTerminal)
      const input = wrapper.get('input[type="number"]')
      await input.setValue('99')
      expect((input.element as HTMLInputElement).value).toBe('4')
      await input.setValue('')
      expect((input.element as HTMLInputElement).value).toBe('4')
      expect(terminal.emitted('seek')).toBeUndefined()
      await wrapper.get('button[aria-label="Previous state"]').trigger('click')
      expect((input.element as HTMLInputElement).value).toBe('3')
      await input.setValue('0')
      expect((input.element as HTMLInputElement).value).toBe('1')
      expect(terminal.props('selectedStateIndex')).toBe(0)
    } finally { wrapper.unmount() }
  })

  it('keeps a large sequence bounded, numbered and reachable through group navigation and exact seeks', async () => {
    const wrapper = mount(PlaybackTerminal, { props: { ...props, totalStates: 10_000, selectedStateIndex: 9000 },
      global: { plugins: [i18n], stubs: { Teleport: true } } })
    expect(wrapper.findAll('.playback-terminal__marker').length).toBeLessThanOrEqual(32)
    expect(wrapper.get('[aria-current="step"]').text()).toBe('9001')
    expect(wrapper.get('[data-testid="trace-timeline-window-label"]').text()).toContain('10000')
    await wrapper.get('[data-testid="trace-timeline-next-window"]').trigger('click')
    expect(wrapper.emitted('seek')!.at(-1)![0]).toBeGreaterThan(9000)
    await wrapper.get('[data-testid="trace-timeline-step-input"]').setValue('10000')
    expect(wrapper.emitted('seek')!.at(-1)).toEqual([9999])
    await wrapper.setProps({ selectedStateIndex: 9999 })
    expect(wrapper.get('[data-testid="trace-timeline-state-9999"]').text()).toBe('10000')
    expect(wrapper.get('[data-testid="trace-timeline-next-window"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="trace-timeline-state-9999"]').trigger('keydown', { key: 'Home' })
    expect(wrapper.emitted('seek')!.at(-1)).toEqual([0])
    await wrapper.get('[data-testid="trace-timeline-hide-panel"]').trigger('click')
    expect(wrapper.emitted('hide-panel')).toEqual([[]])
    expect(wrapper.emitted('toggle-play')).toBeUndefined()
    wrapper.unmount()
  })

  it('marks and names every state in a violation cycle without expanding the rail markers', () => {
    const wrapper = mount(PlaybackTerminal, {
      props: { ...props, violationSteps: [2, 3], stateLabel: index => index >= 2 ? `State ${index + 1}, Violation cycle` : `State ${index + 1}` },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    expect(wrapper.findAll('[data-violation="true"]')).toHaveLength(2)
    expect(wrapper.findAll('.playback-terminal__marker-alert')).toHaveLength(2)
    expect(wrapper.get('[data-testid="trace-timeline-state-3"]').attributes('aria-label')).toContain('Violation cycle')
    expect(wrapper.get('[data-testid="trace-timeline-state-1"]').attributes('aria-current')).toBe('step')
    expect(wrapper.get('[data-testid="trace-timeline-state-1"]').attributes('data-violation')).toBe('false')
    wrapper.unmount()
  })

  it('identifies a violation in the initial state without inventing a preceding state', async () => {
    const wrapper = mount(PlaybackTerminal, {
      props: { ...props, selectedStateIndex: 0, totalStates: 1, violationSteps: [0],
        violationLabel: () => 'First candidate violation' },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    try {
      const marker = wrapper.get('[data-testid="trace-timeline-state-0"]')
      expect(wrapper.findAll('.playback-terminal__marker')).toHaveLength(1)
      expect(marker.attributes('aria-label')).toBe('State 1, Initial')
      expect(marker.attributes('data-initial')).toBe('true')
      expect(marker.attributes('data-violation')).toBe('true')
      expect(marker.attributes('aria-current')).toBe('step')
      expect(marker.get('.playback-terminal__marker-number').text()).toBe('1')
      expect(wrapper.get('[data-testid="trace-timeline-selected-violation"]').text()).toBe('First candidate violation')
      expect(wrapper.find('[data-testid="trace-timeline-previous-window"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="trace-timeline-next-window"]').exists()).toBe(false)
      expect(wrapper.find('.board-playback-rail__line').exists()).toBe(false)
      await wrapper.get('[data-testid="trace-timeline-jump-violation"]').trigger('click')
      expect(wrapper.emitted('locate-violation')).toEqual([[]])
      expect(wrapper.emitted('seek')).toBeUndefined()
    } finally { wrapper.unmount() }
  })

  it('adapts the marker window to its available width and caps it on very wide screens', async () => {
    const observers = new Map<Element, ResizeObserverCallback>()
    vi.stubGlobal('ResizeObserver', class {
      constructor(private callback: ResizeObserverCallback) {}
      observe(element: Element) { observers.set(element, this.callback) }
      disconnect() {}
    })
    const wrapper = mount(PlaybackTerminal, {
      props: { ...props, totalStates: 10_000, selectedStateIndex: 9000 },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    try {
      const viewport = wrapper.get('[data-testid="trace-timeline-scroll"]').element
      const resize = observers.get(viewport)!
      for (const [width, count] of [[4000, 32], [320, 6], [48, 1]]) {
        resize([{
          target: viewport, contentRect: new DOMRect(0, 0, width, 0),
          borderBoxSize: [], contentBoxSize: [], devicePixelContentBoxSize: []
        }], { observe() {}, unobserve() {}, disconnect() {} })
        await nextTick()
        expect(wrapper.findAll('.playback-terminal__marker')).toHaveLength(count)
        expect(wrapper.get('[aria-current="step"]').text()).toBe('9001')
      }
      expect(wrapper.emitted('seek')).toBeUndefined()
      expect(wrapper.emitted('toggle-play')).toBeUndefined()
    } finally {
      wrapper.unmount()
      vi.unstubAllGlobals()
    }
  })

  it('offers no guessed violation location when the evidence has none', () => {
    const wrapper = mount(PlaybackTerminal, { props, global: { plugins: [i18n], stubs: { Teleport: true } } })
    expect(wrapper.find('[data-testid="trace-timeline-jump-violation"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-violation="true"]')).toHaveLength(0)
    wrapper.unmount()
  })

  it('reveals the selected marker on initial open and after advancing while hidden', async () => {
    const wrapper = mount(PlaybackTerminal, {
      attachTo: document.body,
      props: { ...props, totalStates: 24, selectedStateIndex: 20 },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    try {
      const initial = wrapper.get('[data-testid="trace-timeline-state-20"]').element
      await nextTick()
      expect(vi.mocked(Element.prototype.scrollIntoView).mock.instances).toContain(initial)
      vi.mocked(Element.prototype.scrollIntoView).mockClear()
      await wrapper.setProps({ panelOpen: false, selectedStateIndex: 23 })
      await nextTick()
      expect(vi.mocked(Element.prototype.scrollIntoView).mock.instances).toContain(wrapper.get('[data-testid="trace-timeline-state-23"]').element)
      await wrapper.setProps({ panelOpen: true })
      await nextTick()
      const final = wrapper.get('[data-testid="trace-timeline-state-23"]').element
      expect(final.getAttribute('aria-current')).toBe('step')
      expect(wrapper.emitted('seek')).toBeUndefined()
      expect(wrapper.emitted('toggle-play')).toBeUndefined()
    } finally { wrapper.unmount() }
  })
})
