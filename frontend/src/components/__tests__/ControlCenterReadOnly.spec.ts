// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { i18n } from '@/assets/i18n'
import ControlCenter from '../ControlCenter.vue'
import HintTooltip from '@/components/common/HintTooltip.vue'

const boardApiMocks = vi.hoisted(() => ({
  previewDeviceTemplateDeletion: vi.fn(),
  previewDefaultTemplateReset: vi.fn()
}))

const messageMocks = vi.hoisted(() => ({
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn()
}))

vi.mock('@/api/board', async () => {
  const actual = await vi.importActual<typeof import('@/api/board')>('@/api/board')
  return {
    ...actual,
    default: {
      ...actual.default,
      previewDeviceTemplateDeletion: boardApiMocks.previewDeviceTemplateDeletion,
      previewDefaultTemplateReset: boardApiMocks.previewDefaultTemplateReset
    }
  }
})

// A render-slot `ElTooltip` stub: `HintTooltip` imports it, and a whole-module mock otherwise
// hides it, failing every case at import time with a message that names the mock rather than
// the component needing it.
vi.mock('element-plus', () => ({
  ElMessage: messageMocks,
  ElTooltip: { name: 'ElTooltip', template: '<slot />' }
}))

const manifest = {
  Name: 'Switch',
  Description: 'Switch template',
  InitState: 'off',
  Modes: ['Mode'],
  WorkingStates: [{ Name: 'off', Trust: 'trusted', Privacy: 'public' }],
  InternalVariables: [],
  APIs: [{ Name: 'turn_on' }],
  Transitions: []
}

const template = {
  id: 1,
  name: 'Switch',
  defaultTemplate: true,
  manifest
}

const deletionPreview = {
  operation: 'preview' as const,
  impactToken: 'delete-impact-token',
  canDelete: true,
  template,
  blockers: [],
  currentTemplates: [template]
}

const resetPreview = {
  operation: 'preview' as const,
  impactToken: 'reset-impact-token',
  canApply: true,
  templateChanges: [],
  affectedDevices: [],
  blockers: [],
  environmentChanges: [],
  currentTemplates: [template],
  environmentVariables: []
}

beforeEach(() => {
  vi.clearAllMocks()
  i18n.global.locale.value = 'en'
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('ControlCenter read-only mode', () => {
  it('keeps template inspection available while disabling every template mutation entry', async () => {
    const wrapper = mount(ControlCenter, {
      attachTo: document.body,
      props: {
        activeSection: 'templates',
        deviceTemplates: [template],
        readOnly: true,
        readOnlyMessage: 'Close playback first.'
      },
      global: { plugins: [i18n] }
    })

    const card = wrapper.get('.template-card')
    expect(card.attributes('draggable')).toBe('false')
    expect(wrapper.get('input[type="file"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="reset-default-templates"]').attributes('disabled')).toBeDefined()

    const previewButton = card.findAll('button')[0]
    const exportButton = card.findAll('button')[1]
    const deleteButton = card.findAll('button')[2]
    expect(previewButton.attributes('disabled')).toBeUndefined()
    expect(exportButton.attributes('disabled')).toBeUndefined()
    expect(deleteButton.attributes('disabled')).toBeDefined()
    /*
     * The blocked reason moved from a native `title` to the wrapping `HintTooltip`.
     *
     * A native `title` renders as a grey OS tooltip — about a second of delay, no theme awareness, nothing at all
     * on touch. What matters here is unchanged: a disabled destructive control still states why, and this reads
     * that reason from the surface the user now actually sees it on.
     */
    expect(wrapper.findAllComponents(HintTooltip).map(tip => tip.props('content')))
      .toContain('Close playback first.')

    await previewButton.trigger('click')
    expect(document.querySelector('[data-testid="template-preview-1"]')).not.toBeNull()
    expect(boardApiMocks.previewDeviceTemplateDeletion).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('closes an open specification condition editor when the Board becomes read-only', async () => {
    const wrapper = mount(ControlCenter, {
      attachTo: document.body,
      props: { activeSection: 'specs' },
      global: { plugins: [i18n] }
    })

    await wrapper.get('[data-testid="spec-name"]').setValue('Keep the light on')

    await wrapper.get('[data-testid="spec-template-select"]').setValue('1')
    await wrapper.get('[data-testid="spec-add-condition-a"]').trigger('click')
    expect(wrapper.find('[data-testid="spec-condition-dialog"]').exists()).toBe(true)

    await wrapper.setProps({ readOnly: true })
    await flushPromises()

    expect(wrapper.find('[data-testid="spec-condition-dialog"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="spec-editor-fieldset"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get<HTMLButtonElement>('[data-testid="spec-create"]').element.matches(':disabled')).toBe(true)

    wrapper.unmount()
  })

  it('closes template confirmations and ignores previews that finish after read-only starts', async () => {
    boardApiMocks.previewDefaultTemplateReset.mockResolvedValue(resetPreview)
    let resolveDeletion!: (value: typeof deletionPreview) => void
    boardApiMocks.previewDeviceTemplateDeletion.mockReturnValueOnce(new Promise(resolve => {
      resolveDeletion = resolve
    }))

    const wrapper = mount(ControlCenter, {
      attachTo: document.body,
      props: { activeSection: 'templates', deviceTemplates: [template] },
      global: { plugins: [i18n] }
    })

    await wrapper.get('[data-testid="reset-default-templates"]').trigger('click')
    await flushPromises()
    expect(document.querySelector('.template-reset-dialog')).not.toBeNull()

    await wrapper.setProps({ readOnly: true })
    expect(document.querySelector('.template-reset-dialog')).toBeNull()

    await wrapper.setProps({ readOnly: false })
    await wrapper.get('.template-card .template-card__action--danger').trigger('click')
    await wrapper.setProps({ readOnly: true })
    await wrapper.setProps({ readOnly: false })
    resolveDeletion(deletionPreview)
    await flushPromises()

    expect(boardApiMocks.previewDeviceTemplateDeletion).toHaveBeenCalledWith(1)
    expect(document.querySelector('[data-testid="template-delete-dialog"]')).toBeNull()

    wrapper.unmount()
  })
})
