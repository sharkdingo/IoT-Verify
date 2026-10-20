import { defineComponent, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import InfoTooltip from '@/components/common/InfoTooltip.vue'

const ElTooltipStub = defineComponent({
  name: 'ElTooltip',
  props: {
    content: String,
    placement: String,
    trigger: [String, Array],
    popperClass: String
  },
  template: '<div data-testid="tooltip-host"><slot /></div>'
})

describe('InfoTooltip', () => {
  it('dismisses open help before allowing Escape to reach its enclosing panel', async () => {
    const closePanel = vi.fn()
    const wrapper = mount(defineComponent({
      components: { InfoTooltip },
      setup: () => ({ closePanel }),
      template: '<div @keydown.esc="closePanel"><InfoTooltip text="Model semantics" label="Show help" /></div>'
    }), { attachTo: document.body })
    try {
      const trigger = wrapper.get('button')
      await trigger.trigger('focus')
      await new Promise(resolve => setTimeout(resolve, 250))
      await nextTick()
      const popper = document.querySelector<HTMLElement>('.iot-info-tooltip-popper')!
      expect(popper.getAttribute('aria-hidden')).toBe('false')

      await trigger.trigger('keydown', { key: 'Escape', code: 'Escape' })
      expect(closePanel).not.toHaveBeenCalled()
      await nextTick()
      expect(popper.style.display).toBe('none')

      await trigger.trigger('keydown', { key: 'Escape', code: 'Escape' })
      expect(closePanel).toHaveBeenCalledOnce()
    } finally { wrapper.unmount() }
  })

  it('keeps long help behind an accessible icon trigger', () => {
    const wrapper = mount(InfoTooltip, {
      props: {
        text: 'Detailed model semantics',
        label: 'Show model semantics help',
        tone: 'danger',
        testId: 'model-help'
      },
      global: {
        stubs: { ElTooltip: ElTooltipStub }
      }
    })

    const trigger = wrapper.get('[data-testid="model-help"]')
    expect(trigger.attributes('aria-label')).toBe('Show model semantics help')
    expect(wrapper.getComponent(ElTooltipStub).props('content')).toBe('Detailed model semantics')
    expect(wrapper.text()).not.toContain('Detailed model semantics')
  })
})
