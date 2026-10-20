// @vitest-environment jsdom
import { nextTick, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useTimelineRail } from '../useTimelineRail'

afterEach(() => {
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

describe('timeline reveal', () => {
  it('scrubs against the visible state window rather than the complete sequence', () => {
    const track = document.createElement('div')
    track.style.paddingLeft = '22px'
    track.getBoundingClientRect = () => new DOMRect(100, 0, 120, 52)
    track.setPointerCapture = vi.fn()
    document.body.appendChild(track)
    const select = vi.fn()
    const rail = useTimelineRail({ totalStates: ref(1000), selectedStateIndex: ref(80),
      visibleRange: ref({ start: 80, end: 84 }), onSelectState: select, testIdPrefix: 'window-timeline' })
    track.addEventListener('pointerdown', event => rail.scrubStateFromPointer(event as PointerEvent))
    track.dispatchEvent(Object.assign(new MouseEvent('pointerdown', { clientX: 196 }), { pointerId: 1 }))
    expect(select).toHaveBeenCalledWith(84)
  })
  it.each([[124, 0], [196, 4]])('seeks near marker centres at x=%s using the rendered rail inset', (clientX, expected) => {
    const track = document.createElement('div')
    track.style.paddingInline = '22px'
    track.style.paddingLeft = '22px'
    track.getBoundingClientRect = () => new DOMRect(100, 0, 120, 52)
    track.setPointerCapture = vi.fn()
    document.body.appendChild(track)
    const select = vi.fn()
    const rail = useTimelineRail({
      totalStates: ref(5), selectedStateIndex: ref(2), onSelectState: select, testIdPrefix: 'test-timeline'
    })
    track.addEventListener('pointerdown', event => rail.scrubStateFromPointer(event as PointerEvent))
    track.dispatchEvent(Object.assign(new MouseEvent('pointerdown', { clientX }), { pointerId: 1 }))

    expect(select).toHaveBeenCalledWith(expected)
    expect(track.setPointerCapture).toHaveBeenCalledWith(1)
  })

  it.each([true, false])('respects reduced motion = %s when revealing keyboard selection', async reduced => {
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: reduced }))
    const button = document.createElement('button')
    button.dataset.testid = 'test-timeline-state-1'
    button.scrollIntoView = vi.fn()
    document.body.appendChild(button)
    const select = vi.fn()
    const rail = useTimelineRail({
      totalStates: ref(3), selectedStateIndex: ref(0), onSelectState: select, testIdPrefix: 'test-timeline'
    })
    const event = new KeyboardEvent('keydown', { key: 'ArrowRight', cancelable: true })
    rail.handleStateKeydown(event, 0)
    await nextTick()

    expect(event.defaultPrevented).toBe(true)
    expect(select).toHaveBeenCalledWith(1)
    expect(button.scrollIntoView).toHaveBeenCalledWith({
      behavior: reduced ? 'auto' : 'smooth', block: 'nearest', inline: 'center'
    })
    expect(document.activeElement).toBe(button)
  })
})
