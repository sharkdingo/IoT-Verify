// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import FieldSelect from '../FieldSelect.vue'

describe('FieldSelect', () => {
  it('keeps the native field as the labelled, controlled event target', async () => {
    const onChange = vi.fn()
    const wrapper = mount(FieldSelect, {
      props: { modelValue: 'first' },
      attrs: {
        id: 'device-choice',
        'data-testid': 'device-choice',
        'aria-describedby': 'device-help',
        class: 'device-field',
        onChange
      },
      slots: { default: '<option value="first">First device</option><option value="second">Second device</option>' }
    })

    const field = wrapper.get('select')
    expect(wrapper.element).toBe(field.element)
    expect(field.attributes('id')).toBe('device-choice')
    expect(field.attributes('data-testid')).toBe('device-choice')
    expect(field.attributes('aria-describedby')).toBe('device-help')
    expect(field.classes()).toContain('device-field')
    expect(field.element.value).toBe('first')

    await field.setValue('second')
    expect(wrapper.emitted('update:modelValue')).toEqual([['second']])
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange.mock.calls[0]?.[0].target).toBe(field.element)

    await wrapper.setProps({ modelValue: 'second' })
    await wrapper.setProps({ modelValue: 'first' })
    expect(field.element.value).toBe('first')
    expect(wrapper.emitted('update:modelValue')).toEqual([['second']])
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('preserves an enabled empty value for inheriting the template default', async () => {
    const wrapper = mount(FieldSelect, {
      props: { modelValue: 'explicit' },
      slots: { default: '<option value="">Use template default</option><option value="explicit">Explicit value</option>' }
    })

    await wrapper.get('select').setValue('')
    expect(wrapper.get('option').element.disabled).toBe(false)
    expect(wrapper.emitted('update:modelValue')).toEqual([['']])
    await wrapper.setProps({ modelValue: '' })
    expect(wrapper.get('select').element.value).toBe('')
  })

  it('reconciles authoritative value bindings and forwards the original change event', async () => {
    const onChange = vi.fn()
    const wrapper = mount(FieldSelect, {
      props: { value: 'untrusted' },
      attrs: { onChange },
      slots: { default: '<option value="untrusted">Untrusted</option><option value="trusted">Trusted</option><option value="unknown">Unknown</option>' }
    })

    const field = wrapper.get('select')
    expect(field.element.value).toBe('untrusted')
    await field.setValue('trusted')
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange.mock.calls[0]?.[0].target.value).toBe('trusted')
    await wrapper.setProps({ value: 'unknown' })
    expect(field.element.value).toBe('unknown')
  })

  it('keeps set membership as a native listbox with an array model', async () => {
    const wrapper = mount(FieldSelect, {
      props: { modelValue: ['on'], multiple: true },
      attrs: { size: 4, disabled: true },
      slots: { default: '<option value="on">On</option><option value="off">Off</option><option value="idle">Idle</option>' }
    })

    const field = wrapper.get('select')
    expect(field.element.type).toBe('select-multiple')
    expect(field.element.size).toBe(4)
    expect(field.element.disabled).toBe(true)
    expect(wrapper.find('button').exists()).toBe(false)
    expect(wrapper.find('selectedcontent').exists()).toBe(false)
    expect(Array.from(field.element.selectedOptions, option => option.value)).toEqual(['on'])

    await wrapper.setProps({ disabled: false })
    await field.setValue(['off', 'idle'])
    expect(wrapper.emitted('update:modelValue')).toEqual([[['off', 'idle']]])
    await wrapper.setProps({ modelValue: ['off', 'idle'] })
    expect(Array.from(field.element.selectedOptions, option => option.value)).toEqual(['off', 'idle'])
  })
})
