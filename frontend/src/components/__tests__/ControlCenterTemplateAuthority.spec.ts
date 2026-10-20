// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { i18n } from '@/assets/i18n'
import { openModalDepth } from '@/composables/useBodyScrollLock'
import ControlCenter from '../ControlCenter.vue'

const boardApiMocks = vi.hoisted(() => ({
  addDeviceTemplate: vi.fn(),
  deleteDeviceTemplate: vi.fn(),
  getDeviceTemplates: vi.fn(),
  getEnvironment: vi.fn(),
  previewDefaultTemplateReset: vi.fn(),
  previewDeviceTemplateDeletion: vi.fn(),
  resetDefaultTemplates: vi.fn()
}))

// Assert on the semantic feedback boundary, not on Element Plus option objects.
const messageMocks = vi.hoisted(() => ({
  success: vi.fn(),
  // `notifyBlocked` and `notifyInfo` are distinct boundaries: sharing one spy would let a
  // blocked-action warning silently downgrade to an informational toast and still pass.
  warning: vi.fn(),
  info: vi.fn(),
  error: vi.fn()
}))

vi.mock('@/api/board', async () => {
  const actual = await vi.importActual<typeof import('@/api/board')>('@/api/board')
  return {
    ...actual,
    default: { ...actual.default, ...boardApiMocks }
  }
})

vi.mock('@/utils/feedback', () => ({
  dismissCompletionNotifications: vi.fn(),
  notifySuccess: messageMocks.success,
  notifyBlocked: messageMocks.warning,
  notifyInfo: messageMocks.info,
  notifyError: messageMocks.error
}))

const manifest = {
  Name: 'CustomSwitch',
  Description: 'Custom switch template',
  InitState: 'off',
  Modes: ['SwitchState'],
  WorkingStates: [{ Name: 'off', Trust: 'trusted', Privacy: 'public' }],
  InternalVariables: [],
  APIs: [],
  Transitions: []
}

const template = {
  id: 9,
  name: 'CustomSwitch',
  defaultTemplate: false,
  manifest
}

const resetPreview = {
  operation: 'preview' as const,
  impactToken: 'reset-impact-token',
  canApply: true,
  editHistoryEntryCount: 3,
  templateChanges: [],
  affectedDevices: [],
  blockers: [],
  environmentChanges: [],
  currentTemplates: [template],
  environmentVariables: []
}

const deletionPreview = {
  operation: 'preview' as const,
  impactToken: 'delete-impact-token',
  canDelete: true,
  editHistoryEntryCount: 2,
  template,
  blockers: [],
  currentTemplates: [template]
}

const deletionConflict = (reasonCode: string, currentPreview: unknown) => ({
  response: {
    status: 409,
    data: { data: { reasonCode, currentPreview } }
  }
})

const mountTemplates = () => mount(ControlCenter, {
  attachTo: document.body,
  props: { activeSection: 'templates', deviceTemplates: [template] },
  global: { plugins: [i18n] }
})

beforeEach(() => {
  vi.resetAllMocks()
  i18n.global.locale.value = 'en'
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('ControlCenter template authority recovery', () => {
  it('focuses template details, switches its focus return target, and closes with Escape', async () => {
    const wrapper = mountTemplates()
    try {
      await wrapper.setProps({ deviceTemplates: [template, { ...template, id: 10, name: 'OtherSwitch',
        manifest: { ...manifest, Name: 'OtherSwitch' } }] })
      for (const [name, id] of [['CustomSwitch', 9], ['OtherSwitch', 10]] as const) {
        const opener = wrapper.get(`button[aria-label="${i18n.global.t('app.viewTemplateDetails')}: ${name}"]`)
        ;(opener.element as HTMLElement).focus()
        await opener.trigger('click')
        await flushPromises()
        expect(document.activeElement).toBe(document.querySelector(`[data-testid="template-preview-${id}"]`))
      }
      const preview = document.querySelector('[data-testid="template-preview-10"]')!
      preview.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
      await flushPromises()
      expect(document.querySelector('[data-testid="template-preview-10"]')).toBeNull()
      expect(document.activeElement).toBe(wrapper.get(`button[aria-label="${i18n.global.t('app.viewTemplateDetails')}: OtherSwitch"]`).element)
    } finally { wrapper.unmount() }
  })

  it('closes template details when switching editors without taking focus from the new tab', async () => {
    const wrapper = mountTemplates()
    try {
      await wrapper.get(`button[aria-label="${i18n.global.t('app.viewTemplateDetails')}: CustomSwitch"]`).trigger('click')
      const devices = wrapper.get('#control-tab-devices')
      ;(devices.element as HTMLElement).focus()
      await wrapper.setProps({ activeSection: 'devices' })
      await flushPromises()
      expect(document.querySelector('[data-testid="template-preview-9"]')).toBeNull()
      expect(document.activeElement).toBe(devices.element)
    } finally { wrapper.unmount() }
  })

  it('offers a keyboard-operable template importer and makes loading no claim about preparing defaults', async () => {
    const wrapper = mountTemplates()
    try {
      const input = wrapper.get<HTMLInputElement>('[data-testid="template-import-file"]').element
      const chooser = vi.spyOn(input, 'click').mockImplementation(() => {})
      const action = wrapper.get('[data-testid="template-import-open"]')
      expect(action.element.tagName).toBe('BUTTON')
      await action.trigger('click')
      expect(chooser).toHaveBeenCalledOnce()
      await wrapper.setProps({ readOnly: true })
      expect(action.attributes('disabled')).toBeDefined()
      await wrapper.setProps({ templatesLoading: true })
      expect(wrapper.text()).toContain(i18n.global.t('app.loadingDeviceTemplates'))
      expect(wrapper.text()).not.toMatch(/preparing.*default/i)
    } finally { wrapper.unmount() }
  })

  it('names the template action and shows blockers without an irrelevant deletion consequence', async () => {
    const label = 'Referenced device with a long and distinctive name'
    boardApiMocks.previewDeviceTemplateDeletion.mockResolvedValue({ ...deletionPreview,
      canDelete: false, blockers: [{ itemId: 'device-1', itemLabel: label }] })
    const wrapper = mountTemplates()
    try {
      expect(wrapper.get('.template-card__action--danger').attributes('aria-label')).toContain('CustomSwitch')
      await wrapper.get('.template-card__action--danger').trigger('click')
      await flushPromises()
      const dialog = wrapper.get('[data-testid="template-delete-dialog"]')
      expect(dialog.get('h3').text()).toBe(i18n.global.t('app.deleteTemplate'))
      expect(dialog.find('.iot-dialog__consequence').exists()).toBe(false)
      expect(dialog.get('li').text()).toContain(label)
      expect(dialog.get('li span:last-child').classes()).not.toContain('truncate')
      expect(dialog.get('[data-testid="template-delete-confirm"]').attributes('disabled')).toBeDefined()
    } finally { wrapper.unmount() }
  })

  it('hides empty template descriptions and follows the current catalog when an author removes one', async () => {
    const wrapper = mountTemplates()
    try {
      await wrapper.get(`button[aria-label="${i18n.global.t('app.viewTemplateDetails')}: CustomSwitch"]`).trigger('click')
      const preview = () => document.querySelector('[data-testid="template-preview-9"]')!
      expect(preview().textContent).toContain('Custom switch template')
      await wrapper.setProps({ deviceTemplates: [{ ...template, manifest: { ...manifest, Description: ' ' } }] })
      expect(preview().querySelector('.template-preview__description')).toBeNull()
      expect(preview().textContent).not.toContain('Custom switch template')
      expect(preview().textContent).not.toContain(i18n.global.t('app.noDescription'))
    } finally { wrapper.unmount() }
  })

  it.each(['en', 'zh-CN'] as const)('shows every item in each template detail section in %s', async locale => {
    i18n.global.locale.value = locale
    const names = Array.from({ length: 7 }, (_, index) => `Long custom entry ${index + 1}`)
    const entries = names.map(Name => ({ Name }))
    const wrapper = mountTemplates()
    try {
      await wrapper.setProps({ deviceTemplates: [{ ...template, manifest: { ...manifest,
        Modes: names, WorkingStates: entries, InternalVariables: entries, APIs: entries
      } }] })
      await wrapper.get(`button[aria-label="${i18n.global.t('app.viewTemplateDetails')}: CustomSwitch"]`).trigger('click')
      const preview = document.querySelector('[data-testid="template-preview-9"]')!
      for (const section of ['modes', 'states', 'variables', 'apis']) {
        const chips = preview.querySelectorAll(`[data-testid="template-preview-section-${section}"] .template-preview__chip`)
        expect(Array.from(chips).map(chip => chip.textContent?.trim()), section).toEqual(names)
      }
    } finally { wrapper.unmount() }
  })

  it.each([
    ['reset', 'confirmed'],
    ['reset', 'reconciled'],
    ['delete', 'confirmed'],
    ['delete', 'reconciled'],
    ['delete', 'malformed conflict with failed refresh'],
    ['delete', 'malformed conflict with existing template']
  ] as const)('releases the %s confirmation before reporting %s', async (action, outcome) => {
    boardApiMocks.previewDefaultTemplateReset.mockResolvedValue(resetPreview)
    boardApiMocks.previewDeviceTemplateDeletion.mockResolvedValue(deletionPreview)
    boardApiMocks.getEnvironment.mockResolvedValue([])
    const mutation = action === 'reset'
      ? boardApiMocks.resetDefaultTemplates
      : boardApiMocks.deleteDeviceTemplate
    if (outcome === 'confirmed') {
      mutation.mockResolvedValue(action === 'reset'
        ? { ...resetPreview, operation: 'reset' }
        : { ...deletionPreview, operation: 'deleted', deletedTemplate: template, currentTemplates: [] })
    } else {
      mutation.mockRejectedValue(outcome.startsWith('malformed conflict')
        ? deletionConflict('TEMPLATE_DELETION_PREVIEW_STALE', null)
        : new Error('response lost'))
      if (outcome === 'malformed conflict with failed refresh') {
        boardApiMocks.getDeviceTemplates.mockRejectedValue(new Error('refresh failed'))
      } else {
        boardApiMocks.getDeviceTemplates.mockResolvedValue(
          action === 'reset' || outcome === 'malformed conflict with existing template' ? [template] : []
        )
      }
    }
    const feedback = outcome === 'confirmed' ? messageMocks.success
      : outcome === 'malformed conflict with existing template' ? messageMocks.error
        : messageMocks.warning
    const reportedDepths: number[] = []
    feedback.mockImplementation(() => reportedDepths.push(openModalDepth.value))
    const wrapper = mountTemplates()

    try {
      await wrapper.get(action === 'reset'
        ? '[data-testid="reset-default-templates"]' : '.template-card__action--danger').trigger('click')
      await flushPromises()
      expect(openModalDepth.value).toBe(1)
      await wrapper.get(action === 'reset'
        ? '[data-testid="default-template-reset-confirm"]'
        : '[data-testid="template-delete-confirm"]').trigger('click')
      await flushPromises()

      expect(reportedDepths).toEqual([0])
      expect(wrapper.find(action === 'reset'
        ? '.template-reset-dialog' : '[data-testid="template-delete-dialog"]').exists()).toBe(false)
      if (outcome === 'confirmed') {
        expect(messageMocks.warning).not.toHaveBeenCalled()
        expect(messageMocks.error).not.toHaveBeenCalled()
      } else {
        expect(messageMocks.success).not.toHaveBeenCalled()
        expect(wrapper.emitted('edit-history-cleared')).toBeUndefined()
      }
      if (outcome === 'reconciled') {
        expect(messageMocks.warning).toHaveBeenCalledWith(action === 'reset'
          ? i18n.global.t('app.templateResetOutcomeRefreshed')
          : i18n.global.t('app.templateDeleteOutcomeRefreshed', { name: template.name }))
      } else if (outcome === 'malformed conflict with failed refresh') {
        expect(messageMocks.warning).toHaveBeenCalledWith(
          i18n.global.t('app.templateMutationOutcomeUnknownRefreshFailed'))
        expect(wrapper.emitted('authoritative-state-unavailable')).toEqual([[['templates']]])
      }
    } finally {
      wrapper.unmount()
    }
  })

  it('shows the exact undo-history impact before template reset and deletion', async () => {
    boardApiMocks.previewDefaultTemplateReset.mockResolvedValue(resetPreview)
    boardApiMocks.previewDeviceTemplateDeletion.mockResolvedValue(deletionPreview)
    const wrapper = mountTemplates()

    await wrapper.get('[data-testid="reset-default-templates"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('.template-reset-dialog').text()).toContain('3 undo/redo entries')
    await wrapper.get('[data-testid="default-template-reset-cancel"]').trigger('click')

    await wrapper.get('.template-card__action--danger').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="template-delete-dialog"]').text()).toContain('2 undo/redo entries')
    wrapper.unmount()
  })

  it('reports the history boundary only after a confirmed template mutation succeeds', async () => {
    boardApiMocks.previewDeviceTemplateDeletion.mockResolvedValue(deletionPreview)
    boardApiMocks.deleteDeviceTemplate.mockResolvedValue({
      ...deletionPreview,
      operation: 'deleted',
      deletedTemplate: template,
      currentTemplates: []
    })
    const wrapper = mountTemplates()

    await wrapper.get('.template-card__action--danger').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('edit-history-cleared')).toBeUndefined()
    await wrapper.get('[data-testid="template-delete-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('edit-history-cleared')).toEqual([[]])
    wrapper.unmount()
  })

  it('marks the template catalog unavailable when import outcome and refresh are both unknown', async () => {
    boardApiMocks.addDeviceTemplate.mockRejectedValue(new Error('response lost'))
    boardApiMocks.getDeviceTemplates.mockRejectedValue(new Error('refresh failed'))
    const wrapper = mountTemplates()
    const input = wrapper.get<HTMLInputElement>('input[type="file"]')
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [{ size: 128, text: async () => JSON.stringify(manifest) }]
    })

    await input.trigger('change')
    await flushPromises()

    expect(wrapper.emitted('authoritative-state-unavailable')).toEqual([[['templates']]])
    wrapper.unmount()
  })

  it('marks templates and environment unavailable when reset reconciliation fails', async () => {
    boardApiMocks.previewDefaultTemplateReset.mockResolvedValue(resetPreview)
    boardApiMocks.resetDefaultTemplates.mockRejectedValue(new Error('response lost'))
    boardApiMocks.getDeviceTemplates.mockRejectedValue(new Error('template refresh failed'))
    boardApiMocks.getEnvironment.mockRejectedValue(new Error('environment refresh failed'))
    const wrapper = mountTemplates()

    await wrapper.get('[data-testid="reset-default-templates"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="default-template-reset-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('authoritative-state-unavailable'))
      .toEqual([[['templates', 'environment']]])
    expect(wrapper.find('.template-reset-dialog').exists()).toBe(true)
    expect(messageMocks.success).not.toHaveBeenCalled()
    expect(messageMocks.warning).toHaveBeenCalledWith(
      i18n.global.t('app.templateMutationOutcomeUnknownRefreshFailed'))
    wrapper.unmount()
  })

  it('marks the template catalog unavailable when deletion outcome and refresh are both unknown', async () => {
    boardApiMocks.previewDeviceTemplateDeletion.mockResolvedValue(deletionPreview)
    boardApiMocks.deleteDeviceTemplate.mockRejectedValue(new Error('response lost'))
    boardApiMocks.getDeviceTemplates.mockRejectedValue(new Error('refresh failed'))
    const wrapper = mountTemplates()

    await wrapper.get('.template-card__action--danger').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="template-delete-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('authoritative-state-unavailable')).toEqual([[['templates']]])
    expect(wrapper.find('[data-testid="template-delete-dialog"]').exists()).toBe(true)
    expect(messageMocks.success).not.toHaveBeenCalled()
    expect(messageMocks.warning).toHaveBeenCalledWith(
      i18n.global.t('app.templateMutationOutcomeUnknownRefreshFailed'))
    wrapper.unmount()
  })

  it('adopts a validated blocked deletion preview returned by a known conflict', async () => {
    const blockedPreview = {
      ...deletionPreview,
      impactToken: 'current-delete-impact-token',
      canDelete: false,
      blockers: [{
        reasonCode: 'DEVICE_INSTANCE_USES_TEMPLATE',
        itemId: 'hall_switch',
        itemLabel: 'Hall switch',
        reason: 'The device still uses this type.'
      }]
    }
    boardApiMocks.previewDeviceTemplateDeletion.mockResolvedValue(deletionPreview)
    boardApiMocks.deleteDeviceTemplate.mockRejectedValue(deletionConflict(
      'TEMPLATE_DELETION_BLOCKED',
      blockedPreview
    ))
    const wrapper = mountTemplates()

    await wrapper.get('.template-card__action--danger').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="template-delete-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="template-delete-dialog"]').text()).toContain('Hall switch')
    expect(wrapper.get('[data-testid="template-delete-confirm"]').attributes('disabled')).toBeDefined()
    expect(boardApiMocks.getDeviceTemplates).not.toHaveBeenCalled()
    expect(messageMocks.warning)
      .toHaveBeenCalledWith(i18n.global.t('app.templateDeletePreviewChanged'))
    wrapper.unmount()
  })

  it('rejects a malformed conflict preview, refreshes authority, and closes the stale confirmation', async () => {
    boardApiMocks.previewDeviceTemplateDeletion.mockResolvedValue(deletionPreview)
    boardApiMocks.deleteDeviceTemplate.mockRejectedValue(deletionConflict(
      'TEMPLATE_DELETION_PREVIEW_STALE',
      { ...deletionPreview, operation: 'deleted', currentTemplates: [] }
    ))
    boardApiMocks.getDeviceTemplates.mockResolvedValue([template])
    const wrapper = mountTemplates()

    await wrapper.get('.template-card__action--danger').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="template-delete-confirm"]').trigger('click')
    await flushPromises()

    expect(boardApiMocks.getDeviceTemplates).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('replace-template-catalog')).toEqual([[[template]]])
    expect(wrapper.find('[data-testid="template-delete-dialog"]').exists()).toBe(false)
    expect(messageMocks.warning)
      .not.toHaveBeenCalledWith(i18n.global.t('app.templateDeletePreviewChanged'))
    expect(messageMocks.error).toHaveBeenCalledWith(
      i18n.global.t('app.deleteFailedWithReason', {
        reason: i18n.global.t('app.boardMutationResponseIncomplete')
      })
    )
    wrapper.unmount()
  })

  it('does not trust a deletion preview attached to an unknown 409 reason', async () => {
    boardApiMocks.previewDeviceTemplateDeletion.mockResolvedValue(deletionPreview)
    boardApiMocks.deleteDeviceTemplate.mockRejectedValue(deletionConflict(
      'UNRELATED_CONFLICT',
      deletionPreview
    ))
    boardApiMocks.getDeviceTemplates.mockResolvedValue([template])
    const wrapper = mountTemplates()

    await wrapper.get('.template-card__action--danger').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="template-delete-confirm"]').trigger('click')
    await flushPromises()

    expect(boardApiMocks.getDeviceTemplates).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="template-delete-dialog"]').exists()).toBe(false)
    expect(messageMocks.warning)
      .not.toHaveBeenCalledWith(i18n.global.t('app.templateDeletePreviewChanged'))
    wrapper.unmount()
  })

  it('refreshes authority when a template-deletion 409 omits its conflict data', async () => {
    boardApiMocks.previewDeviceTemplateDeletion.mockResolvedValue(deletionPreview)
    boardApiMocks.deleteDeviceTemplate.mockRejectedValue({
      response: { status: 409, data: { data: null } }
    })
    boardApiMocks.getDeviceTemplates.mockResolvedValue([template])
    const wrapper = mountTemplates()

    await wrapper.get('.template-card__action--danger').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="template-delete-confirm"]').trigger('click')
    await flushPromises()

    expect(boardApiMocks.getDeviceTemplates).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="template-delete-dialog"]').exists()).toBe(false)
    // Assert *which* error, so a wrong or generic message cannot pass.
    expect(messageMocks.error).toHaveBeenCalledWith(
      i18n.global.t('app.deleteFailedWithReason', {
        reason: i18n.global.t('app.boardMutationResponseIncomplete')
      })
    )
    wrapper.unmount()
  })
})
