// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { i18n } from '@/assets/i18n'
import ControlCenter from '../ControlCenter.vue'

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
  ElTooltip: { name: 'ElTooltip', template: '<slot />' }
}))

describe('device import file selection race', () => {
  const deviceTemplates = [
    { name: 'Mobile Phone', manifest: { Name: 'Mobile Phone' } },
    { name: 'Alarm', manifest: { Name: 'Alarm' } }
  ]
  const previousPayload = JSON.stringify([{ templateName: 'Mobile Phone', label: 'previous_phone' }])
  const filePayload = JSON.stringify([{ templateName: 'Alarm', label: 'fresh_file_alarm' }])

  it('clears the stale preview while the file read is pending', async () => {
    const wrapper = mount(ControlCenter, {
      attachTo: document.body,
      props: { activeSection: 'devices', deviceTemplates },
      global: { plugins: [i18n] }
    })
    vi.useFakeTimers()
    let finishRead!: (source: string) => void
    try {
      await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
      const initial = wrapper.get<HTMLInputElement>('[data-testid="device-import-file"]')
      Object.defineProperty(initial.element, 'files', { configurable: true,
        value: [{ name: 'previous.json', size: 64, arrayBuffer: () => Promise.resolve(new TextEncoder().encode(previousPayload).buffer) }] })
      await initial.trigger('change')
      await flushPromises()
      const create = wrapper.get('[data-testid="device-import-create"]')
      expect(create.attributes('disabled')).toBeUndefined()
      expect(wrapper.get('[data-testid="device-import-preview"]').text()).toContain('previous_phone')

      const pendingRead = new Promise<string>(resolve => { finishRead = resolve })
      const input = wrapper.get<HTMLInputElement>('[data-testid="device-import-file"]')
      // jsdom has no File.arrayBuffer(); control its completion to observe the waiting window.
      Object.defineProperty(input.element, 'files', { configurable: true,
        value: [{ size: 64, arrayBuffer: () => pendingRead.then(text => new TextEncoder().encode(text).buffer) }] })
      await input.trigger('change')
      expect(wrapper.find('[data-testid="device-import-text"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="device-import-preview"]').exists()).toBe(false)
      expect(create.attributes('disabled')).toBeDefined()
      await create.trigger('click')
      expect(wrapper.emitted('create-devices')).toBeUndefined()
    } finally {
      wrapper.unmount()
      finishRead?.(filePayload)
      await flushPromises()
      vi.useRealTimers()
    }
  })

  it('still installs the file contents in one tick once read', async () => {
    const wrapper = mount(ControlCenter, {
      attachTo: document.body,
      props: { activeSection: 'devices', deviceTemplates },
      global: { plugins: [i18n] }
    })
    vi.useFakeTimers()
    try {
      await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
      const initial = wrapper.get<HTMLInputElement>('[data-testid="device-import-file"]')
      Object.defineProperty(initial.element, 'files', { configurable: true,
        value: [{ name: 'previous.json', size: 64, arrayBuffer: () => Promise.resolve(new TextEncoder().encode(previousPayload).buffer) }] })
      await initial.trigger('change')
      await flushPromises()
      expect(wrapper.get('[data-testid="device-import-create"]').attributes('disabled')).toBeUndefined()

      const input = wrapper.get<HTMLInputElement>('[data-testid="device-import-file"]')
      Object.defineProperty(input.element, 'files', { configurable: true,
        value: [{ size: 64, arrayBuffer: () => Promise.resolve(new TextEncoder().encode(filePayload).buffer) }] })
      await input.trigger('change')
      await flushPromises()
      // Do not advance the keystroke debounce after choosing a file.
      expect(wrapper.find('[data-testid="device-import-text"]').exists()).toBe(false)
      const preview = wrapper.get('[data-testid="device-import-preview"]')
      expect(preview.text()).toContain('fresh_file_alarm')
      expect(preview.text()).not.toContain('previous_phone')
      expect(preview.get('strong').text()).toBe('1/1')
      const create = wrapper.get('[data-testid="device-import-create"]')
      expect(create.attributes('disabled')).toBeUndefined()
      await create.trigger('click')
      const submission = wrapper.emitted('create-devices')![0]![0] as any
      expect(submission.items).toHaveLength(1)
      expect(submission.items[0]).toMatchObject({ template: { name: 'Alarm' }, customName: 'fresh_file_alarm' })
      submission.complete(false)
      await flushPromises()
    } finally {
      wrapper.unmount()
      vi.useRealTimers()
    }
  })
})
