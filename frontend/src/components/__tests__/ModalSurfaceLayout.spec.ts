// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import { i18n } from '@/assets/i18n'
import ControlCenter from '../ControlCenter.vue'

afterEach(() => {
  i18n.global.locale.value = 'en'
  document.body.innerHTML = ''
})

describe('board modal surfaces', () => {
  it('keeps the specification condition actions reachable in short viewports', async () => {
    const wrapper = mount(ControlCenter, {
      props: { activeSection: 'specs', nodes: [], deviceTemplates: [] },
      global: { plugins: [i18n] }
    })

    const templateSelect = wrapper.get('[data-testid="spec-template-select"]')
    const templateOption = templateSelect.findAll('option')
      .find(option => Boolean(option.attributes('value')))
    expect(templateOption).toBeDefined()
    await templateSelect.setValue(templateOption!.attributes('value'))
    await wrapper.get('[data-testid="spec-add-condition-a"]').trigger('click')

    const overlay = wrapper.get('[data-testid="spec-condition-dialog"]')
    const dialog = overlay.get('[role="dialog"]')
    // Reachability is a property of the shared dialog layer now: `.iot-dialog` is a bounded flex column,
    // `__body` is the only part that scrolls, and `__footer` is `flex: none`, so the actions cannot be pushed
    // past the bottom of a short viewport. Asserting the composition rather than the individual utilities is
    // what keeps that guarantee from being re-derived (and mis-derived) per dialog.
    expect(overlay.classes()).toContain('iot-dialog-overlay')
    expect(dialog.classes()).toContain('iot-dialog')
    // Asserted as the shared primitive rather than the bare utility: it owns overflow together with
    // overscroll containment, the token scrollbar, and scroll-padding for revealed controls.
    const body = dialog.get('.iot-dialog__body')
    expect(body.classes()).toContain('iot-scroll-region')
    expect(dialog.find('.iot-dialog__footer').exists()).toBe(true)
    wrapper.unmount()
  })
})
