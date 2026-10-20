import { defineComponent, h, nextTick, ref, type VNode } from 'vue'
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useModalAccessibility } from '../useModalAccessibility'
import { registerModalSurface } from '../useBodyScrollLock'

const mountedWrappers: Array<ReturnType<typeof mount>> = []

afterEach(() => {
  mountedWrappers.splice(0).forEach(wrapper => wrapper.unmount())
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

beforeEach(() => {
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockImplementation(function (this: HTMLElement) {
    return [this.getBoundingClientRect()] as unknown as DOMRectList
  })
})

const mountPanel = (
  trapFocus: boolean,
  shouldRestoreFocus?: () => boolean,
  renderControls?: () => VNode[],
  fallbackTarget?: HTMLElement
) => {
  const opener = document.createElement('button')
  opener.textContent = 'Open'
  document.body.append(opener)
  opener.focus()

  const Panel = defineComponent({
    setup() {
      const open = ref(true)
      const close = () => { open.value = false }
      const { setDialogRef, handleModalKeydown } = useModalAccessibility(
        open,
        close,
        () => fallbackTarget ?? opener,
        { trapFocus, shouldRestoreFocus }
      )
      return () => open.value
        ? h('section', {
            ref: setDialogRef,
            tabindex: -1,
            onKeydown: handleModalKeydown
          }, renderControls?.() ?? [
            h('button', { 'data-testid': 'first' }, 'First'),
            h('button', { 'data-testid': 'last' }, 'Last')
          ])
        : null
    }
  })

  const wrapper = mount(Panel, { attachTo: document.body })
  mountedWrappers.push(wrapper)
  return { opener, wrapper }
}

describe('useModalAccessibility', () => {
  it('can restore a labelled surface with a negative tab index without adding it to the Tab order', async () => {
    const parent = document.createElement('section')
    parent.tabIndex = -1
    parent.setAttribute('role', 'dialog')
    document.body.append(parent)
    const { opener, wrapper } = mountPanel(true, undefined, () => [
      h('button', { tabindex: -1, 'data-testid': 'not-tabbable' }, 'Programmatic only'),
      h('button', { 'data-testid': 'tabbable' }, 'Action')
    ], parent)
    await nextTick()
    expect(document.activeElement).toBe(wrapper.get('[data-testid="tabbable"]').element)
    opener.remove()
    wrapper.get('section').element.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await nextTick()
    expect(document.activeElement).toBe(parent)
  })

  it('captures the opener before its source is removed and supplies it to the fallback', async () => {
    const opener = document.createElement('button')
    document.body.append(opener)
    opener.focus()
    const open = ref(false)
    const fallback = vi.fn(() => null)
    const Panel = defineComponent({
      setup() {
        const { setDialogRef, handleModalKeydown } = useModalAccessibility(open,
          () => { open.value = false }, fallback)
        return () => open.value ? h('section', { ref: setDialogRef, tabindex: -1,
          autofocus: true, onKeydown: handleModalKeydown }) : null
      }
    })
    const wrapper = mount(Panel, { attachTo: document.body })
    mountedWrappers.push(wrapper)
    open.value = true
    opener.remove()
    await nextTick()
    wrapper.get('section').element.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await nextTick()
    expect(fallback).toHaveBeenCalledWith(opener)
  })

  it('honors an available autofocus control without changing the tab order', async () => {
    const { wrapper } = mountPanel(true, undefined, () => [
      h('button', { 'data-testid': 'first' }, 'Other action'),
      h('button', { disabled: true, autofocus: true }, 'Unavailable'),
      h('button', { autofocus: true, 'data-testid': 'cancel' }, 'Cancel')
    ])
    await nextTick()
    expect(document.activeElement).toBe(wrapper.get('[data-testid="cancel"]').element)
    const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })
    wrapper.get('section').element.dispatchEvent(tab)
    expect(tab.defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(wrapper.get('[data-testid="first"]').element)
  })

  it('keeps reverse Tab inside a modal when its container initially has focus', async () => {
    const { wrapper } = mountPanel(true)
    wrapper.get('section').element.setAttribute('autofocus', '')
    await nextTick()
    expect(document.activeElement).toBe(wrapper.get('section').element)

    const tab = new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true })
    wrapper.get('section').element.dispatchEvent(tab)
    expect(tab.defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(wrapper.get('[data-testid="last"]').element)
  })

  it('focuses and restores a non-modal panel without trapping Tab', async () => {
    const { opener, wrapper } = mountPanel(false)
    wrapper.get('section').element.setAttribute('autofocus', '')
    await nextTick()
    expect(document.activeElement).toBe(wrapper.get('section').element)

    ;(wrapper.get('[data-testid="last"]').element as HTMLElement).focus()
    const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })
    wrapper.get('section').element.dispatchEvent(tab)
    expect(tab.defaultPrevented).toBe(false)

    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    wrapper.get('section').element.dispatchEvent(escape)
    await nextTick()
    expect(wrapper.find('section').exists()).toBe(false)
    expect(document.activeElement).toBe(opener)
  })

  it('honors autofocus on a result container without focusing its close hint', async () => {
    const { wrapper } = mountPanel(true)
    wrapper.get('section').element.setAttribute('autofocus', '')
    await nextTick()
    expect(document.activeElement).toBe(wrapper.get('section').element)
  })

  it('keeps the existing modal focus trap enabled by default behavior', async () => {
    const { wrapper } = mountPanel(true)
    await nextTick()
    const last = wrapper.get('[data-testid="last"]')
    ;(last.element as HTMLElement).focus()
    const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })
    wrapper.get('section').element.dispatchEvent(tab)
    expect(tab.defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(wrapper.get('[data-testid="first"]').element)
  })

  it('skips hidden, inert, and closed-details controls for initial focus', async () => {
    const { wrapper } = mountPanel(true, undefined, () => [
      h('button', { hidden: true, 'data-testid': 'hidden-first' }, 'Hidden'),
      h('div', { inert: '', 'data-testid': 'inert-region' }, [
        h('button', { 'data-testid': 'inert-button' }, 'Inert')
      ]),
      h('details', {}, [
        h('summary', { tabindex: -1 }, 'Closed'),
        h('button', { 'data-testid': 'closed-details-button' }, 'Closed content')
      ]),
      h('button', { 'data-testid': 'visible-first' }, 'Visible')
    ])

    await nextTick()

    expect(document.activeElement).toBe(wrapper.get('[data-testid="visible-first"]').element)
  })

  it('wraps from the last visible control when later controls are hidden', async () => {
    const { wrapper } = mountPanel(true, undefined, () => [
      h('button', { 'data-testid': 'visible-first' }, 'First'),
      h('button', { 'data-testid': 'visible-last' }, 'Last'),
      h('button', { style: 'display: none', 'data-testid': 'hidden-last' }, 'Hidden'),
      h('div', { inert: '' }, [h('button', { 'data-testid': 'inert-last' }, 'Inert')])
    ])
    await nextTick()
    const visibleLast = wrapper.get('[data-testid="visible-last"]')
    ;(visibleLast.element as HTMLElement).focus()

    const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })
    wrapper.get('section').element.dispatchEvent(tab)

    expect(tab.defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(wrapper.get('[data-testid="visible-first"]').element)
  })

  it('does not steal focus back while another related panel is taking over', async () => {
    const { opener, wrapper } = mountPanel(false, () => false)
    await nextTick()
    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    wrapper.get('section').element.dispatchEvent(escape)
    await nextTick()

    expect(wrapper.find('section').exists()).toBe(false)
    expect(document.activeElement).not.toBe(opener)
  })

  it('falls back when the original opener becomes hidden while the panel is open', async () => {
    const fallback = document.createElement('button')
    fallback.textContent = 'Fallback'
    document.body.append(fallback)
    const { opener, wrapper } = mountPanel(false, undefined, undefined, fallback)
    await nextTick()
    opener.hidden = true

    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    wrapper.get('section').element.dispatchEvent(escape)
    await nextTick()

    expect(document.activeElement).toBe(fallback)
  })

  it.each([false, true])('retries a fallback after rendering without stealing a new focus target: %s', async focusMoved => {
    const fallback = document.createElement('button')
    fallback.hidden = true
    document.body.append(fallback)
    const { opener, wrapper } = mountPanel(true, undefined, undefined, fallback)
    await nextTick()
    opener.disabled = true
    wrapper.get('section').element.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await nextTick()

    fallback.hidden = false
    const nextContext = document.createElement('button')
    document.body.append(nextContext)
    if (focusMoved) nextContext.focus()
    await nextTick()
    expect(document.activeElement).toBe(focusMoved ? nextContext : fallback)
  })

  it('restores focus after conditional unmount when the opener becomes available next frame', async () => {
    const fallback = document.createElement('button')
    fallback.hidden = true
    document.body.append(fallback)
    const { opener, wrapper } = mountPanel(true, undefined, undefined, fallback)
    await nextTick()
    opener.disabled = true
    void nextTick(() => { fallback.hidden = false })
    wrapper.unmount()
    mountedWrappers.splice(mountedWrappers.indexOf(wrapper), 1)
    await nextTick()
    expect(document.activeElement).toBe(fallback)
  })

  it('closes a trapping modal on Escape even before focus has reached it', async () => {
    // The element-bound handler only sees the key once focus is inside the dialog, and focus moves
    // there in a nextTick after a post-flush watcher. A deep link that opens the surface on load —
    // or a fast user — presses Escape inside that window, and the keystroke used to be dropped
    // silently, leaving the dialog open with no indication why.
    const { wrapper } = mountPanel(true)
    // Deliberately no `await nextTick()`: focus is still outside the dialog here.
    document.body.focus()

    document.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await nextTick()

    expect(wrapper.find('section').exists()).toBe(false)
  })

  it('leaves Escape to a non-modal panel rather than closing it from the document', async () => {
    // The board's floating tool panels are non-modal on purpose and own their own Escape behaviour;
    // one keypress must not close all of them.
    const { wrapper } = mountPanel(false)
    document.body.focus()

    document.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await nextTick()

    expect(wrapper.find('section').exists()).toBe(true)
  })

  it('leaves Escape to a confirmation stacked on top of a trapping modal', async () => {
    // An Element Plus MessageBox closes on Escape without calling preventDefault (its focus-trap
    // emits `release-requested` instead), so the defaultPrevented check cannot see it. Without the
    // depth comparison the same press also ran this dialog's close, discarding the draft the
    // confirmation was asking the user about.
    const { wrapper } = mountPanel(true)
    await nextTick()
    const release = registerModalSurface()
    // Move focus out of the dialog, as it is while a MessageBox on top of it holds focus — that is
    // the case the document fallback exists for, and the one the depth guard has to decline. `blur()`
    // rather than `document.body.focus()`, which jsdom ignores, leaving focus on the dialog's own
    // button so the `contains` early-return would mask the guard entirely.
    ;(document.activeElement as HTMLElement | null)?.blur()

    document.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await nextTick()
    expect(wrapper.find('section').exists()).toBe(true)

    // Once the confirmation is gone, Escape belongs to this dialog again. Focus is inside the dialog
    // by now, so the document fallback defers to the element-bound handler — which is what actually
    // closes it, and what this asserts through.
    release()
    wrapper.get('section').element.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await nextTick()
    expect(wrapper.find('section').exists()).toBe(false)
  })

  it('closes only the innermost nested dialog on one bubbled Escape', async () => {
    const NestedDialogs = defineComponent({
      setup() {
        const outerOpen = ref(true)
        const innerOpen = ref(true)
        const outer = useModalAccessibility(outerOpen, () => { outerOpen.value = false })
        const inner = useModalAccessibility(innerOpen, () => { innerOpen.value = false })
        return () => outerOpen.value
          ? h('section', {
              ref: outer.setDialogRef,
              tabindex: -1,
              'data-testid': 'outer-dialog',
              onKeydown: outer.handleModalKeydown
            }, innerOpen.value
              ? [h('section', {
                  ref: inner.setDialogRef,
                  tabindex: -1,
                  'data-testid': 'inner-dialog',
                  onKeydown: inner.handleModalKeydown
                }, [h('button', 'Close inner')])]
              : [h('button', 'Outer remains')])
          : null
      }
    })
    const wrapper = mount(NestedDialogs, { attachTo: document.body })
    mountedWrappers.push(wrapper)
    await nextTick()

    wrapper.get('[data-testid="inner-dialog"]').element.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await nextTick()

    expect(wrapper.find('[data-testid="inner-dialog"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="outer-dialog"]').exists()).toBe(true)
  })
})
