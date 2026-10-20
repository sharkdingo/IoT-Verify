// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { describe, expect, it, vi } from 'vitest'
import ControlCenter from '../ControlCenter.vue'
import actualI18n from '@/assets/i18n'

const translations = (locale: string) => createI18n({ legacy: false, locale, messages: {
  en: actualI18n.global.getLocaleMessage('en'),
  'zh-CN': actualI18n.global.getLocaleMessage('zh-CN')
} })

describe('device import and specification guidance', () => {
  it.each(['en', 'zh-CN'])('names the import input and provides a keyboard file command in %s', async locale => {
    const i18n = translations(locale)
    const wrapper = mount(ControlCenter, { attachTo: document.body,
      props: { activeSection: 'devices' }, global: { plugins: [i18n] } })
    try {
      await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
      for (const format of ['json', 'csv']) {
        await wrapper.get('[data-testid="device-import-source-file"]').setValue()
        await wrapper.get(`[data-testid="device-import-format-${format}"]`).trigger('click')
        const params = { format: format.toUpperCase(), extension: `.${format}` }
        const trigger = wrapper.get<HTMLButtonElement>('button[data-testid="device-import-file-trigger"]')
        trigger.element.focus()
        expect(document.activeElement).toBe(trigger.element)
        expect(trigger.element.tabIndex).toBe(0)
        expect(trigger.text()).toContain(i18n.global.t('app.deviceImportChooseFile', params))
        const input = wrapper.get<HTMLInputElement>('[data-testid="device-import-file"]')
        expect(input.attributes('accept')).toBe(`.${format}`)
        const choose = vi.spyOn(input.element, 'click').mockImplementation(() => {})
        await trigger.trigger('click')
        expect(choose).toHaveBeenCalledOnce()
        expect(wrapper.find('[data-testid="device-import-text"]').exists()).toBe(false)
        await wrapper.get('[data-testid="device-import-source-paste"]').setValue()
        expect(wrapper.find('[data-testid="device-import-file"]').exists()).toBe(false)
        const editor = wrapper.get<HTMLTextAreaElement>('[data-testid="device-import-text"]').element
        expect(editor.value).toBe('')
        expect(editor.placeholder).toBe(i18n.global.t('app.deviceImportPlaceholder', params))
        const link = wrapper.get(`[data-testid="device-import-${format}-template"]`)
        expect(link.attributes('download')).toBe(`devices.${format}`)
        expect(link.attributes('href')).toContain(`device-list-template.${format}`)
        expect(link.attributes('aria-label')).toBe(i18n.global.t('app.downloadDeviceImportTemplate', params))
        expect(wrapper.find(`[data-testid="device-import-${format === 'json' ? 'csv' : 'json'}-template"]`).exists()).toBe(false)
        expect(Array.from(editor.labels ?? []).map(label => label.textContent?.trim()))
          .toContain(i18n.global.t('app.deviceImportTextLabel', params))
        const description = document.getElementById(editor.getAttribute('aria-describedby') || '')
        expect(description?.textContent).toContain(i18n.global.t(format === 'json' ? 'app.deviceImportJsonSummary' : 'app.deviceImportCsvSummary'))
      }
      await wrapper.get('[data-testid="device-import-format-json"]').trigger('click')
      const editor = wrapper.get<HTMLTextAreaElement>('[data-testid="device-import-text"]').element
      vi.useFakeTimers()
      await wrapper.get('[data-testid="device-import-text"]').setValue('{')
      await vi.advanceTimersByTimeAsync(350)
      expect(editor.getAttribute('aria-invalid')).toBe('true')
      expect(document.getElementById(editor.getAttribute('aria-describedby') || ''))
        .toBe(wrapper.get('[data-testid="device-import-syntax-failure"]').element)
    } finally { vi.useRealTimers(); wrapper.unmount() }
  })

  it.each(['json', 'csv'])('imports the downloadable %s list against the bundled device templates', async format => {
    const text = readFileSync(join(process.cwd(), `src/assets/examples/device-list-template.${format}`), 'utf8')
    const deviceTemplates = ['Motion Detector', 'Light'].map(name => ({ name,
      manifest: JSON.parse(readFileSync(join(process.cwd(), `../backend/src/main/resources/deviceTemplate/${name}.json`), 'utf8'))
    }))
    const wrapper = mount(ControlCenter, { props: { activeSection: 'devices', deviceTemplates },
      global: { plugins: [translations('en')] } })
    try {
      await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
      await wrapper.get(`[data-testid="device-import-format-${format}"]`).trigger('click')
      const input = wrapper.get<HTMLInputElement>('[data-testid="device-import-file"]')
      Object.defineProperty(input.element, 'files', { configurable: true,
        value: [{ size: text.length, arrayBuffer: () => Promise.resolve(new TextEncoder().encode(text).buffer) }] })
      await input.trigger('change')
      await vi.waitFor(() => expect(wrapper.get('[data-testid="device-import-preview"] strong').text()).toBe('2/2'))
      const create = wrapper.get('[data-testid="device-import-create"]')
      expect(create.attributes('disabled')).toBeUndefined()
      await create.trigger('click')
      const submission = wrapper.emitted('create-devices')![0]![0] as any
      expect(submission.items.map((item: any) => [item.template.name, item.customName]))
        .toEqual([['Motion Detector', 'entry_motion'], ['Light', 'entry_light']])
      submission.complete(false)
    } finally { wrapper.unmount() }
  })

  it.each(['en', 'zh-CN'])('keeps import errors and automatic-name warnings readable without hover in %s', async locale => {
    const i18n = translations(locale)
    const wrapper = mount(ControlCenter, { attachTo: document.body,
      props: { activeSection: 'devices', deviceTemplates: [{ name: 'Light', manifest: { Name: 'Light' } }] },
      global: { plugins: [i18n] } })
    try {
      await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
      vi.useFakeTimers()
      await wrapper.get('[data-testid="device-import-source-paste"]').setValue()
      await wrapper.get('[data-testid="device-import-text"]').setValue(JSON.stringify([
        { templateName: 'Light', label: 'An unusually long imported device name' },
        { templateName: 'Light', label: 'An unusually long imported device name' },
        { templateName: 'An unavailable template with a long diagnostic name', label: 'Invalid device' }
      ]))
      await vi.advanceTimersByTimeAsync(350)
      const preview = wrapper.get('[data-testid="device-import-preview"]')
      for (const kind of ['error', 'warning']) {
        const row = preview.get(`.has-${kind}`)
        expect(row.get('small').text()).toBeTruthy()
        expect(row.get('small').classes()).not.toContain('truncate')
        expect(row.get('span').classes()).not.toContain('truncate')
      }
      expect(wrapper.get('[data-testid="device-import-create"]').attributes('disabled')).toBeDefined()
      const source = readFileSync(join(process.cwd(), 'src/components/ControlCenter.vue'), 'utf8')
      expect(source).toMatch(/\.device-preview-row\.has-error,\s*\.device-preview-row\.has-warning\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)/)
      expect(source).toMatch(/\.device-preview-row\.has-error small,\s*\.device-preview-row\.has-warning small\s*\{[^}]*overflow-wrap:\s*anywhere/)
    } finally { vi.useRealTimers(); wrapper.unmount() }
  })

  it.each(['en', 'zh-CN'])('warns about discarding specification conditions before a template change in %s', async locale => {
    const i18n = translations(locale)
    const wrapper = mount(ControlCenter, { attachTo: document.body,
      props: { activeSection: 'specs', nodes: [{ id: 'lamp', label: 'Lamp', templateName: 'Light' }] },
      global: { plugins: [i18n] } })
    try {
      const select = wrapper.get('[data-testid="spec-template-select"]')
      await select.setValue('1')
      expect(wrapper.find('#spec-template-change-notice').exists()).toBe(false)
      ;(wrapper.vm as any).specForm.aConditions.push({ id: 'draft-condition', deviceId: 'lamp',
        side: 'a', targetType: 'state', key: 'state', relation: '=', value: 'on' })
      await wrapper.vm.$nextTick()
      expect(wrapper.get('#spec-template-change-notice').text()).toBe(i18n.global.t('app.specTemplateChangeNotice'))
      expect(select.attributes('aria-describedby')).toBe('spec-template-change-notice')
      expect(wrapper.text().split(i18n.global.t('app.specTemplateChangeNotice'))).toHaveLength(2)
      await select.setValue('3')
      expect((wrapper.vm as any).specForm.aConditions).toEqual([])
      expect(wrapper.find('#spec-template-change-notice').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })
})
