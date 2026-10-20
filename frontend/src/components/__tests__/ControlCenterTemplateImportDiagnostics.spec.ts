// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { i18n } from '@/assets/i18n'
import { useAuth } from '@/stores/auth'
import ControlCenter from '../ControlCenter.vue'

const api = vi.hoisted(() => ({ addDeviceTemplate: vi.fn(), getDeviceTemplates: vi.fn() }))
const feedback = vi.hoisted(() => ({
  dismissCompletionNotifications: vi.fn(),
  acknowledgeFailure: vi.fn(), notifyError: vi.fn(), notifyBlocked: vi.fn(), notifySuccess: vi.fn()
}))
vi.mock('@/api/board', async () => {
  const actual = await vi.importActual<typeof import('@/api/board')>('@/api/board')
  return { ...actual, default: { ...actual.default, ...api } }
})
vi.mock('@/utils/feedback', () => feedback)

let wrapper: ReturnType<typeof mount>
beforeEach(() => {
  vi.resetAllMocks()
  useAuth().logout()
  i18n.global.locale.value = 'zh-CN'
  wrapper = mount(ControlCenter, {
    props: { activeSection: 'templates', deviceTemplates: [] },
    global: { plugins: [i18n] }
  })
})
afterEach(() => {
  wrapper.unmount()
  useAuth().logout()
})

const selectTemplate = async (text: () => Promise<string>) => {
  const input = wrapper.get('input[type="file"][accept=".json"]')
  Object.defineProperty(input.element, 'files', { value: [{ size: 64, text }], configurable: true })
  await input.trigger('change')
  await flushPromises()
}

const importFeedback = () => wrapper.get('[data-testid="template-import-feedback"]')
const visibleCause = () => {
  const cause = importFeedback().get('[data-testid="failure-cause"]')
  expect(cause.element.closest('[data-testid="failure-technical-details"]')).toBeNull()
  return cause.text()
}

describe('template import failure diagnostics', () => {
  it('keeps JSON syntax location and raw cause beside file selection without blocking correction', async () => {
    const source = '{\n "Name" "Broken"}'
    let cause: unknown
    try { JSON.parse(source) } catch (error) { cause = error }
    await selectTemplate(async () => source)
    expect(api.addDeviceTemplate).not.toHaveBeenCalled()
    expect(importFeedback().text()).toContain(i18n.global.t('app.jsonSyntaxInvalidTitle'))
    expect(importFeedback().text()).toContain(i18n.global.t('app.jsonSyntaxLocation', { line: 2, column: 9 }))
    expect(visibleCause()).toContain((cause as SyntaxError).message)
    expect(wrapper.get('input[type="file"][accept=".json"]').attributes('disabled')).toBeUndefined()
    expect(feedback.acknowledgeFailure).not.toHaveBeenCalled()
    expect(feedback.notifyError).not.toHaveBeenCalled()
  })

  it('reports valid JSON schema rejection as an invalid definition with actual fields and reason', async () => {
    const reason = "Template 'Broken' does not match backend/device-template-schema.json: $.Modes must be array"
    api.addDeviceTemplate.mockRejectedValue({ response: {
      status: 400, data: { message: reason, data: { errors: { 'manifest.Modes': 'must be array' } } }
    } })
    await selectTemplate(async () => '{"Name":"Broken","Modes":false}')
    expect(importFeedback().text()).toContain(i18n.global.t('app.templateDefinitionInvalidTitle'))
    expect(importFeedback().text()).toContain(i18n.global.t('app.templateDefinitionInvalidMessage'))
    expect(importFeedback().text()).toContain('manifest.Modes')
    expect(importFeedback().text()).toContain('must be array')
    expect(visibleCause()).toContain(reason)
    expect(importFeedback().get('dt').element.closest('[data-testid="failure-technical-details"]')).toBeNull()
    expect(feedback.acknowledgeFailure).not.toHaveBeenCalled()
    expect(feedback.notifyError).not.toHaveBeenCalled()
    expect(api.getDeviceTemplates).not.toHaveBeenCalled()
  })

  it('keeps a message-only semantic rejection available without calling it invalid JSON', async () => {
    const reason = "Template 'Broken': observable API 'read' has no corresponding variable"
    api.addDeviceTemplate.mockRejectedValue({ response: { status: 400, data: { message: reason } } })
    await selectTemplate(async () => '{"Name":"Broken"}')
    expect(importFeedback().text()).toContain(i18n.global.t('app.templateDefinitionInvalidTitle'))
    expect(visibleCause()).toContain(reason)
    expect(feedback.acknowledgeFailure).not.toHaveBeenCalled()
    expect(feedback.notifyError).not.toHaveBeenCalled()
  })

  it('does not classify a non-string manifest name as a JSON parser failure', async () => {
    api.addDeviceTemplate.mockRejectedValue({ response: {
      status: 400, data: { message: 'name: must not be blank', data: { errors: { name: 'must not be blank' } } }
    } })
    await selectTemplate(async () => '{"Name":{"toString":null}}')
    expect(api.addDeviceTemplate).toHaveBeenCalledOnce()
    expect(importFeedback().text()).toContain(i18n.global.t('app.templateDefinitionInvalidTitle'))
    expect(importFeedback().text()).toContain('must not be blank')
    expect(feedback.acknowledgeFailure).not.toHaveBeenCalled()
  })

  it('distinguishes a file-read failure from JSON syntax and does not submit', async () => {
    await selectTemplate(async () => { throw new DOMException('File cannot be read', 'NotReadableError') })
    expect(importFeedback().text()).toContain(i18n.global.t('app.templateFileReadFailedTitle'))
    expect(visibleCause()).toContain('File cannot be read')
    expect(feedback.acknowledgeFailure).not.toHaveBeenCalled()
    expect(api.addDeviceTemplate).not.toHaveBeenCalled()
  })

  it('keeps transport failure as an unknown outcome and reconciles the catalog', async () => {
    api.addDeviceTemplate.mockRejectedValue(new TypeError('Failed to fetch'))
    api.getDeviceTemplates.mockResolvedValue([])
    await selectTemplate(async () => '{"Name":"New"}')
    expect(api.getDeviceTemplates).toHaveBeenCalledOnce()
    expect(feedback.acknowledgeFailure).not.toHaveBeenCalled()
    expect(feedback.notifyBlocked).toHaveBeenCalledWith(i18n.global.t('app.templateImportOutcomeUnconfirmedAfterRefresh'))
  })

  it('clears the previous failure immediately when a new file is selected and permits the same file again', async () => {
    await selectTemplate(async () => '{')
    expect(importFeedback().attributes('role')).toBe('alert')
    let finishRead!: (source: string) => void
    await selectTemplate(() => new Promise(resolve => { finishRead = resolve }))
    expect(wrapper.find('[data-testid="template-import-feedback"]').exists()).toBe(false)
    finishRead('{')
    await flushPromises()
    expect(importFeedback().attributes('role')).toBe('alert')
    expect(wrapper.get<HTMLInputElement>('input[type="file"][accept=".json"]').element.value).toBe('')
    expect(feedback.acknowledgeFailure).not.toHaveBeenCalled()
  })

  it('does not publish a late file-read failure after playback begins or the account changes', async () => {
    let rejectRead!: (error: unknown) => void
    await selectTemplate(() => new Promise((_resolve, reject) => { rejectRead = reject }))
    await wrapper.setProps({ readOnly: true })
    rejectRead(new Error('Old playback read failure'))
    await flushPromises()
    expect(wrapper.find('[data-testid="template-import-feedback"]').exists()).toBe(false)
    expect(feedback.acknowledgeFailure).not.toHaveBeenCalled()

    await wrapper.setProps({ readOnly: false })
    await selectTemplate(() => new Promise((_resolve, reject) => { rejectRead = reject }))
    useAuth().login('next-session', { userId: 2 } as any)
    rejectRead(new Error('Previous account read failure'))
    await flushPromises()
    expect(wrapper.find('[data-testid="template-import-feedback"]').exists()).toBe(false)
    expect(feedback.acknowledgeFailure).not.toHaveBeenCalled()
    expect(api.addDeviceTemplate).not.toHaveBeenCalled()
  })

  it('does not submit file contents that finish reading after an account switch', async () => {
    let finishRead!: (source: string) => void
    await selectTemplate(() => new Promise(resolve => { finishRead = resolve }))
    useAuth().login('next-session', { userId: 2 } as any)
    finishRead('{"Name":"Previous account"}')
    await flushPromises()
    expect(api.addDeviceTemplate).not.toHaveBeenCalled()
    expect(wrapper.emitted('replace-template-catalog')).toBeUndefined()
  })
})

describe('confirmed template import followed by a failed catalog read', () => {
  const created = { id: 9, name: 'New', defaultTemplate: false,
    manifest: { Name: 'New', Description: 'Test type', InitState: 'off',
      Modes: ['SwitchState'], WorkingStates: [{ Name: 'off', Trust: 'trusted', Privacy: 'public' }],
      InternalVariables: [], APIs: [], Transitions: [] } }

  it('does not call a confirmed import rejected when the following catalog read is unauthorized', async () => {
    api.addDeviceTemplate.mockResolvedValue(created)
    api.getDeviceTemplates.mockRejectedValue({ response: { status: 401,
      data: { message: 'Authentication required' } } })
    await selectTemplate(async () => JSON.stringify(created.manifest))
    expect(api.addDeviceTemplate).toHaveBeenCalledOnce()
    expect(api.getDeviceTemplates).toHaveBeenCalledOnce()
    expect(importFeedback().text()).not.toContain(i18n.global.t('app.templateImportRejectedMessage'))
    expect(importFeedback().text()).toContain(i18n.global.t('app.templateCatalogRefreshFailedTitle'))
    expect(importFeedback().text()).toContain(i18n.global.t('app.templateCatalogRefreshFailedMessage'))
    expect(visibleCause()).toContain('Authentication required')
    expect(feedback.acknowledgeFailure).not.toHaveBeenCalled()
    expect(wrapper.emitted('authoritative-state-unavailable')).toEqual([[['templates']]])
    expect(wrapper.emitted('replace-template-catalog')).toBeUndefined()
    expect(feedback.notifySuccess).not.toHaveBeenCalled()
  })

  it('does not call the confirmed import unknown when only the catalog read fails', async () => {
    api.addDeviceTemplate.mockResolvedValue(created)
    api.getDeviceTemplates.mockRejectedValue({ response: { status: 503,
      data: { message: 'Catalog read service is unavailable',
        data: { errors: { catalog: 'Latest catalog could not be read' } } } } })
    await selectTemplate(async () => JSON.stringify(created.manifest))
    expect(api.addDeviceTemplate).toHaveBeenCalledOnce()
    expect(api.getDeviceTemplates).toHaveBeenCalledOnce()
    expect(feedback.notifyBlocked).not.toHaveBeenCalledWith(i18n.global.t('app.templateMutationOutcomeUnknownRefreshFailed'))
    expect(importFeedback().text()).toContain(i18n.global.t('app.templateCatalogRefreshFailedTitle'))
    expect(importFeedback().text()).toContain('catalog')
    expect(importFeedback().text()).toContain('Latest catalog could not be read')
    expect(visibleCause()).toContain('Catalog read service is unavailable')
    expect(feedback.acknowledgeFailure).not.toHaveBeenCalled()
    expect(wrapper.emitted('authoritative-state-unavailable')).toEqual([[['templates']]])
    expect(wrapper.emitted('replace-template-catalog')).toBeUndefined()
    expect(feedback.notifySuccess).not.toHaveBeenCalled()
  })

  it('clears the catalog-read failure when the Board retry publishes the confirmed template', async () => {
    api.addDeviceTemplate.mockResolvedValue(created)
    api.getDeviceTemplates.mockRejectedValue(new Error('Catalog unavailable'))
    await selectTemplate(async () => JSON.stringify(created.manifest))
    expect(importFeedback().attributes('role')).toBe('alert')
    await wrapper.setProps({ deviceTemplates: [{ ...created, id: 10 }] })
    expect(importFeedback().attributes('role')).toBe('alert')
    await wrapper.setProps({ deviceTemplates: [] })
    expect(importFeedback().attributes('role')).toBe('alert')
    await wrapper.setProps({ deviceTemplates: [created] })
    expect(wrapper.find('[data-testid="template-import-feedback"]').exists()).toBe(false)
    expect(api.addDeviceTemplate).toHaveBeenCalledOnce()
    expect(api.getDeviceTemplates).toHaveBeenCalledOnce()
  })

  it.each(['confirmed', 'unknown'] as const)('does not publish a %s import catalog read into a later account', async outcome => {
    if (outcome === 'confirmed') api.addDeviceTemplate.mockResolvedValue(created)
    else api.addDeviceTemplate.mockRejectedValue(new TypeError('Response lost'))
    let finishCatalog!: (templates: typeof created[]) => void
    api.getDeviceTemplates.mockReturnValue(new Promise(resolve => { finishCatalog = resolve }))
    await selectTemplate(async () => JSON.stringify(created.manifest))
    expect(api.getDeviceTemplates).toHaveBeenCalledOnce()
    useAuth().login('next-session', { userId: 2 } as any)
    finishCatalog([created])
    await flushPromises()
    expect(wrapper.emitted('replace-template-catalog')).toBeUndefined()
    expect(wrapper.emitted('authoritative-state-unavailable')).toBeUndefined()
    expect(wrapper.find('[data-testid="template-import-feedback"]').exists()).toBe(false)
    expect(feedback.notifySuccess).not.toHaveBeenCalled()
    expect(feedback.notifyBlocked).not.toHaveBeenCalled()
    expect(api.addDeviceTemplate).toHaveBeenCalledOnce()
  })

  it('publishes the current catalog and reports success after both confirmed requests succeed', async () => {
    api.addDeviceTemplate.mockResolvedValue(created)
    api.getDeviceTemplates.mockResolvedValue([created])
    await selectTemplate(async () => JSON.stringify(created.manifest))
    expect(api.addDeviceTemplate).toHaveBeenCalledOnce()
    expect(api.getDeviceTemplates).toHaveBeenCalledOnce()
    expect(wrapper.emitted('replace-template-catalog')).toEqual([[[created]]])
    expect(wrapper.emitted('authoritative-state-unavailable')).toBeUndefined()
    expect(feedback.acknowledgeFailure).not.toHaveBeenCalled()
    expect(feedback.notifySuccess).toHaveBeenCalledWith(i18n.global.t('app.templateImportedSuccessfully'))
    expect(wrapper.find('[data-testid="template-import-feedback"]').exists()).toBe(false)
  })
})
