// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { Blob as NodeBlob } from 'node:buffer'
import { join } from 'node:path'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import ControlCenter from '../ControlCenter.vue'
import actualI18n from '@/assets/i18n'
import * as feedback from '@/utils/feedback'
import encodingFixture from '@/testing/fixtures/device-list-encoding.json'

const json = JSON.stringify([{ templateName: 'Mobile Phone', label: 'phone', state: 'on',
  currentStateTrust: 'trusted', currentStatePrivacy: 'private',
  variables: [{ name: 'steps', value: '23', trust: 'untrusted' }, { name: 'location', value: 'home', trust: 'trusted' }],
  privacies: [{ name: 'steps', privacy: 'private' }, { name: 'location', privacy: 'public' }] }])
const csv = 'templateName,label,state,currentStateTrust,currentStatePrivacy,variable:steps,trust:steps,variable:location,trust:location,privacy:steps,privacy:location\nMobile Phone,phone,on,trusted,private,23,untrusted,home,trusted,private,public'
const deviceTemplates = [{ name: 'Mobile Phone', manifest: JSON.parse(readFileSync(
  join(process.cwd(), '../backend/src/main/resources/deviceTemplate/Mobile Phone.json'), 'utf8')) }]
const panel = (locale = 'en', nodes: any[] = []) => mount(ControlCenter, { props: { activeSection: 'devices', deviceTemplates, nodes }, global: { plugins: [
  createI18n({ legacy: false, locale, messages: {
    en: actualI18n.global.getLocaleMessage('en'), 'zh-CN': actualI18n.global.getLocaleMessage('zh-CN')
  } })
] } })
const selectFormat = (wrapper: ReturnType<typeof panel>, format: string) =>
  wrapper.get(`[data-testid="device-import-format-${format}"]`).trigger('click')
const paste = async (wrapper: ReturnType<typeof panel>, text: string) => {
  await wrapper.get('[data-testid="device-import-source-paste"]').setValue()
  await wrapper.get('[data-testid="device-import-text"]').setValue(text)
  await vi.advanceTimersByTimeAsync(350)
}

afterEach(() => vi.useRealTimers())

describe('independent JSON and CSV device imports', () => {
  it('keeps file and pasted input mutually exclusive and restores only the selected source', async () => {
    vi.useFakeTimers()
    const wrapper = panel()
    try {
      await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
      expect(wrapper.find('[data-testid="device-import-text"]').exists()).toBe(false)
      const file = new NodeBlob([json])
      Object.defineProperty(file, 'name', { value: '客厅设备.json' })
      const input = wrapper.get<HTMLInputElement>('[data-testid="device-import-file"]')
      Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
      await input.trigger('change')
      await flushPromises()
      expect(wrapper.get('[data-testid="device-import-file-name"]').text()).toContain('客厅设备.json')
      await wrapper.get('[data-testid="device-import-source-paste"]').setValue()
      expect(wrapper.find('[data-testid="device-import-file-trigger"]').exists()).toBe(false)
      expect(wrapper.get<HTMLTextAreaElement>('[data-testid="device-import-text"]').element.value).toBe('')
      expect(wrapper.get('[data-testid="device-import-create"]').attributes('disabled')).toBeDefined()
      await paste(wrapper, json.replace('"phone"', '"pasted_phone"'))
      await wrapper.get('[data-testid="device-import-source-file"]').setValue()
      expect(wrapper.get('[data-testid="device-import-preview"]').text()).not.toContain('pasted_phone')
      await wrapper.get('[data-testid="device-import-create"]').trigger('click')
      const submission = wrapper.emitted('create-devices')![0]![0] as any
      expect(submission.items[0].customName).toBe('phone')
      submission.complete(false)
      await flushPromises()
      await wrapper.get('[data-testid="device-import-file-clear"]').trigger('click')
      expect(wrapper.find('[data-testid="device-import-preview"]').exists()).toBe(false)
      expect(wrapper.get('[data-testid="device-import-create"]').attributes('disabled')).toBeDefined()
      await wrapper.get('[data-testid="device-import-source-paste"]').setValue()
      expect(wrapper.get('[data-testid="device-import-preview"]').text()).toContain('pasted_phone')
    } finally { wrapper.unmount() }
  })

  it('invalidates a file read when switching sources away and back', async () => {
    const wrapper = panel()
    let resolve!: (buffer: ArrayBuffer) => void
    const pending = new Promise<ArrayBuffer>(done => { resolve = done })
    try {
      await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
      const input = wrapper.get<HTMLInputElement>('[data-testid="device-import-file"]')
      Object.defineProperty(input.element, 'files', { configurable: true,
        value: [{ name: 'late.json', size: 64, arrayBuffer: () => pending }] })
      await input.trigger('change')
      await wrapper.get('[data-testid="device-import-source-paste"]').setValue()
      await wrapper.get('[data-testid="device-import-source-file"]').setValue()
      resolve(new TextEncoder().encode(json).buffer)
      await flushPromises()
      expect(wrapper.find('[data-testid="device-import-preview"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="device-import-file-name"]').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('rejects mismatched CSV encoding before a corrupted Chinese name can be imported', async () => {
    const wrapper = panel()
    try {
      await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
      await selectFormat(wrapper, 'csv')
      const file = new NodeBlob([Buffer.from('templateName,label\r\nMobile Phone,'), Buffer.from('bfcdccfc', 'hex')])
      const input = wrapper.get<HTMLInputElement>('[data-testid="device-import-file"]')
      Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
      await input.trigger('change')
      await flushPromises()
      expect(wrapper.get('[data-testid="device-import-file-failure"]').text()).toContain('encoding')
      expect(wrapper.get('[data-testid="device-import-create"]').attributes('disabled')).toBeDefined()
      expect(wrapper.find('[data-testid="device-import-text"]').exists()).toBe(false)
      expect(wrapper.emitted('create-devices')).toBeUndefined()
    } finally { wrapper.unmount() }
  })

  it('imports UTF-8, GB18030 and JSON with identical Chinese names, zero values and optional blanks', async () => {
    const submissions: any[] = []
    for (const variant of ['json', 'utf-8', 'gb18030']) {
      const wrapper = panel()
      try {
        await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
        const csv = variant !== 'json'
        await selectFormat(wrapper, csv ? 'csv' : 'json')
        if (csv) await wrapper.get('[data-testid="device-import-csv-encoding"]').setValue(variant)
        const file = new NodeBlob([variant === 'gb18030' ? Buffer.from(encodingFixture.gb18030Base64, 'base64')
          : variant === 'utf-8' ? '\uFEFF' + encodingFixture.csv : JSON.stringify(encodingFixture.devices)])
        const input = wrapper.get<HTMLInputElement>('[data-testid="device-import-file"]')
        Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
        await input.trigger('change')
        await flushPromises()
        expect(wrapper.find('[data-testid="device-import-file-failure"]').exists()).toBe(false)
        expect(wrapper.get('[data-testid="device-import-preview"] strong').text()).toBe('2/2')
        await wrapper.get('[data-testid="device-import-create"]').trigger('click')
        const submission = wrapper.emitted('create-devices')![0]![0] as any
        submissions.push({ items: submission.items, environmentVariables: submission.environmentVariables })
        expect(submission.items[0].customName).toBe(encodingFixture.devices[0].label)
        expect(submission.items[0].runtime.variables).toContainEqual({ name: 'steps', value: '0', trust: 'trusted' })
        submission.complete(false)
      } finally { wrapper.unmount() }
    }
    expect(submissions[1]).toEqual(submissions[0])
    expect(submissions[2]).toEqual(submissions[0])
  })

  it('applies CSV encoding changes only to the next selected file and allows retry after a decode failure', async () => {
    const wrapper = panel()
    try {
      await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
      await selectFormat(wrapper, 'csv')
      const file = new NodeBlob([Buffer.from(encodingFixture.gb18030Base64, 'base64')])
      const input = wrapper.get<HTMLInputElement>('[data-testid="device-import-file"]')
      Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
      await input.trigger('change')
      await flushPromises()
      expect(wrapper.find('[data-testid="device-import-file-failure"]').exists()).toBe(true)
      await wrapper.get('[data-testid="device-import-csv-encoding"]').setValue('gb18030')
      expect(wrapper.find('[data-testid="device-import-file-failure"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="device-import-text"]').exists()).toBe(false)
      await input.trigger('change')
      await flushPromises()
      expect(wrapper.get('[data-testid="device-import-preview"] strong').text()).toBe('2/2')
      await wrapper.get('[data-testid="device-import-csv-encoding"]').setValue('utf-8')
      expect(wrapper.get('[data-testid="device-import-preview"] strong').text()).toBe('2/2')
      expect(wrapper.get('[data-testid="device-import-create"]').attributes('disabled')).toBeUndefined()
    } finally { wrapper.unmount() }
  })

  it('invalidates a pending read when CSV encoding changes away and back', async () => {
    const wrapper = panel()
    let resolve!: (buffer: ArrayBuffer) => void
    const pending = new Promise<ArrayBuffer>(done => { resolve = done })
    try {
      await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
      await selectFormat(wrapper, 'csv')
      const input = wrapper.get<HTMLInputElement>('[data-testid="device-import-file"]')
      Object.defineProperty(input.element, 'files', { configurable: true, value: [{ size: 64, arrayBuffer: () => pending }] })
      await input.trigger('change')
      await wrapper.get('[data-testid="device-import-csv-encoding"]').setValue('gb18030')
      await wrapper.get('[data-testid="device-import-csv-encoding"]').setValue('utf-8')
      resolve(new TextEncoder().encode(encodingFixture.csv).buffer)
      await flushPromises()
      expect(wrapper.find('[data-testid="device-import-text"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="device-import-preview"]').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('retires completion notices when the user returns to the importer', async () => {
    const dismiss = vi.spyOn(feedback, 'dismissCompletionNotifications').mockImplementation(() => {})
    const wrapper = panel()
    try {
      await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
      await wrapper.get('[data-testid="device-import-file-trigger"]').trigger('focusin')
      expect(dismiss).toHaveBeenCalledOnce()
    } finally { wrapper.unmount(); dismiss.mockRestore() }
  })

  it('restores each draft, its validation and preview without importing or converting it', async () => {
    vi.useFakeTimers()
    const wrapper = panel()
    try {
      await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
      await paste(wrapper, '{')
      expect(wrapper.find('[data-testid="device-import-syntax-failure"]').exists()).toBe(true)
      await selectFormat(wrapper, 'csv')
      expect(wrapper.get<HTMLTextAreaElement>('[data-testid="device-import-text"]').element.value).toBe('')
      expect(wrapper.find('[data-testid="device-import-syntax-failure"]').exists()).toBe(false)
      await paste(wrapper, csv)
      expect(wrapper.get('[data-testid="device-import-preview"] strong').text()).toBe('1/1')
      await selectFormat(wrapper, 'json')
      expect(wrapper.get<HTMLTextAreaElement>('[data-testid="device-import-text"]').element.value).toBe('{')
      expect(wrapper.find('[data-testid="device-import-syntax-failure"]').exists()).toBe(true)
      await paste(wrapper, json)
      await selectFormat(wrapper, 'csv')
      expect(wrapper.get<HTMLTextAreaElement>('[data-testid="device-import-text"]').element.value).toBe(csv)
      expect(wrapper.get('[data-testid="device-import-create"]').attributes('disabled')).toBeUndefined()
      expect(wrapper.emitted('create-devices')).toBeUndefined()
    } finally { wrapper.unmount() }
  })

  it('produces identical device and Environment Pool writes from equivalent JSON and CSV', async () => {
    vi.useFakeTimers()
    const submissions: any[] = []
    for (const [format, text] of [['json', json], ['csv', csv]]) {
      const wrapper = panel()
      try {
        await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
        await selectFormat(wrapper, format)
        await paste(wrapper, text)
        const create = wrapper.get('[data-testid="device-import-create"]')
        expect(create.attributes('disabled')).toBeUndefined()
        await create.trigger('click')
        const submission = wrapper.emitted('create-devices')![0]![0] as any
        submissions.push({ items: submission.items, environmentVariables: submission.environmentVariables })
        submission.complete(false)
      } finally { wrapper.unmount() }
    }
    expect(submissions[1]).toEqual(submissions[0])
    expect(submissions[1].items[0].runtime).toMatchObject({ state: 'on', currentStateTrust: 'trusted',
      currentStatePrivacy: 'private', variables: [expect.objectContaining({ name: 'steps', value: '23', trust: 'untrusted' })] })
    expect(submissions[1].environmentVariables).toContainEqual(expect.objectContaining({ name: 'location', value: 'home', trust: 'trusted', privacy: 'public' }))
  })

  it.each([['json', csv], ['csv', json]])('does not auto-detect pasted data in the %s option', async (format, text) => {
    vi.useFakeTimers()
    const wrapper = panel()
    try {
      await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
      await selectFormat(wrapper, format)
      await paste(wrapper, text)
      expect(wrapper.get('[data-testid="device-import-create"]').attributes('disabled')).toBeDefined()
      expect(wrapper.emitted('create-devices')).toBeUndefined()
    } finally { wrapper.unmount() }
  })

  it('reports the same runtime validation error in both formats', async () => {
    vi.useFakeTimers()
    const errors: string[] = []
    for (const [format, text] of [['json', json.replace('"on"', '"not-a-state"')], ['csv', csv.replace(',on,', ',not-a-state,')]]) {
      const wrapper = panel()
      try {
        await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
        await selectFormat(wrapper, format)
        await paste(wrapper, text)
        expect(wrapper.get('[data-testid="device-import-create"]').attributes('disabled')).toBeDefined()
        errors.push(wrapper.get('[data-testid="device-import-preview"] .has-error small').text())
        expect(wrapper.emitted('create-devices')).toBeUndefined()
      } finally { wrapper.unmount() }
    }
    expect(errors[0]).toContain('not-a-state')
    expect(errors[1]).toBe(errors[0])
  })

  it.each(['en', 'zh-CN'])('locates field errors and blocks the entire batch in %s', async locale => {
    vi.useFakeTimers()
    for (const format of ['json', 'csv']) {
      const wrapper = panel(locale)
      try {
        await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
        await selectFormat(wrapper, format)
        await paste(wrapper, format === 'json'
          ? JSON.stringify([{ templateName: 'Mobile Phone', label: 'bedroom_phone', variables: [{ name: 'steps', value: '' }] },
            { templateName: 'Mobile Phone', label: 'valid_phone' }])
          : 'templateName,label,variable:steps,trust:steps\nMobile Phone,bedroom_phone,,trusted\nMobile Phone,valid_phone,,')
        const row = wrapper.get('[data-testid="device-import-preview"] .has-error')
        expect(row.text()).toContain('bedroom_phone')
        if (format === 'csv') expect(row.text()).toContain('variable:steps')
        expect(row.get('[data-testid="device-import-row-source"]').text())
          .toBe(format === 'json' ? (locale === 'en' ? 'Entry 1' : '第 1 项') : (locale === 'en' ? 'Line 2' : '第 2 行'))
        const reason = wrapper.get('[data-testid="device-import-blocked-reason"]')
        expect(reason.text()).toContain(locale === 'en' ? 'entire batch' : '整批')
        const submit = wrapper.get('[data-testid="device-import-create"]')
        expect(submit.attributes('aria-describedby')).toContain(reason.attributes('id'))
        expect(submit.attributes('disabled')).toBeDefined()
        await submit.trigger('click')
        expect(wrapper.emitted('create-devices')).toBeUndefined()
      } finally { wrapper.unmount() }
    }
  })

  it.each(['en', 'zh-CN'])('locates shared value and label conflicts once and restores a valid batch in %s', async locale => {
    vi.useFakeTimers()
    for (const format of ['json', 'csv']) {
      const wrapper = panel(locale, [{ label: 'phone' }])
      try {
        await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
        await selectFormat(wrapper, format)
        const makeInput = (conflict: boolean) => format === 'json'
          ? JSON.stringify([['home', 'trusted', 'public'], conflict ? ['away', 'untrusted', 'private'] : ['home', 'trusted', 'public']].map(([value, trust, privacy]) => ({
            templateName: 'Mobile Phone', label: 'phone', variables: [{ name: 'location', value, trust }], privacies: [{ name: 'location', privacy }]
          })))
          : `templateName,label,variable:location,trust:location,privacy:location\nMobile Phone,phone,home,trusted,public\nMobile Phone,phone,${conflict ? 'away,untrusted,private' : 'home,trusted,public'}`
        await paste(wrapper, makeInput(true))
        const conflicts = wrapper.get('[data-testid="device-import-environment-conflicts"]')
        expect(conflicts.attributes('role')).toBe('alert')
        expect(conflicts.findAll('li')).toHaveLength(3)
        expect(conflicts.text()).toContain(format === 'json' ? (locale === 'en' ? 'Entry 1' : '第 1 项') : (locale === 'en' ? 'Line 2' : '第 2 行'))
        expect(conflicts.text()).not.toContain('#')
        expect(wrapper.findAll('[data-testid="device-import-blocked-reason"]')).toHaveLength(1)
        expect(wrapper.get('[data-testid="device-import-create"]').attributes('disabled')).toBeDefined()
        await paste(wrapper, makeInput(false))
        expect(wrapper.find('[data-testid="device-import-environment-conflicts"]').exists()).toBe(false)
        expect(wrapper.find('[data-testid="device-import-blocked-reason"]').exists()).toBe(false)
        expect(wrapper.findAll('[data-testid="device-import-row-name"]').map(row => row.text())).toEqual(['phone_1', 'phone_2'])
        await wrapper.get('[data-testid="device-import-create"]').trigger('click')
        const submission = wrapper.emitted('create-devices')![0]![0] as any
        expect(submission.items.map((item: any) => item.customName)).toEqual(['phone_1', 'phone_2'])
        expect(submission.environmentVariables).toEqual([{ name: 'location', value: 'home', trust: 'trusted', privacy: 'public' }])
        submission.complete(false)
      } finally { wrapper.unmount() }
    }
  })

  it.each(['resolve', 'reject'])('ignores a late file %s after switching away and back with empty drafts', async outcome => {
    const wrapper = panel()
    let resolve!: (text: string) => void
    let reject!: (error: Error) => void
    const pending = new Promise<string>((done, fail) => { resolve = done; reject = fail })
    try {
      await wrapper.get('[data-testid="device-create-mode-import"]').trigger('click')
      const input = wrapper.get<HTMLInputElement>('[data-testid="device-import-file"]')
      Object.defineProperty(input.element, 'files', { configurable: true, value: [{ size: 64, arrayBuffer: () => pending.then(text => new TextEncoder().encode(text).buffer) }] })
      await input.trigger('change')
      await selectFormat(wrapper, 'csv')
      await selectFormat(wrapper, 'json')
      if (outcome === 'resolve') resolve(json)
      else reject(new Error('abandoned file read'))
      await flushPromises()
      expect(wrapper.find('[data-testid="device-import-text"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="device-import-preview"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="device-import-file-failure"]').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })
})
