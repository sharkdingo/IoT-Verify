// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ControlCenter from '../ControlCenter.vue'

const boardApiMocks = vi.hoisted(() => ({
  previewDefaultTemplateReset: vi.fn(),
  resetDefaultTemplates: vi.fn()
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
      previewDefaultTemplateReset: boardApiMocks.previewDefaultTemplateReset,
      resetDefaultTemplates: boardApiMocks.resetDefaultTemplates
    }
  }
})

vi.mock('element-plus', () => ({
  /*
   * `HintTooltip` imports `ElTooltip`, and a whole-module mock hides it — every case in the file then fails at
   * import time with "No 'ElTooltip' export is defined", a message that names the mock rather than the component
   * needing it. A render-slot stub suffices: nothing here asserts tooltip behaviour, only the control it wraps.
   */
  ElTooltip: { name: 'ElTooltip', template: '<slot />' },
  ElMessage: messageMocks
}))

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      app: {
        templates: 'Templates',
        defaultTemplates: 'Default templates',
        customTemplates: 'Custom templates',
        templateScopeAll: 'All templates',
        templateScopeScene: 'Used in this scene',
        templateLibrarySummary: 'Library {total} · Used {used} · Custom used {usedCustom}',
        templateUsageDevices: 'Used by {count} devices',
        templateDetails: 'Template details',
        viewTemplateDetails: 'View template details',
        initState: 'Initial state',
        transition: 'Transitions',
        modes: 'Modes',
        workingStates: 'Working states',
        variables: 'Variables',
        deviceApis: 'APIs',
        none: 'None',
        close: 'Close',
        noDescription: 'No description',
        varsShort: 'vars',
        apisShort: 'APIs',
        modelTokens: {
          lock: 'Lock device',
          mode: 'Operating mode',
          switchState: 'Switch status',
          weather: 'Weather',
          temperature: 'Temperature',
          sunny: 'Sunny',
          off: 'Disabled',
          on: 'Enabled'
        },
        resetDefaultTemplates: 'Reset defaults',
        resetDefaultTemplatesTitle: 'Reset default templates?',
        resetDefaultTemplatesMessage: 'Review the exact impact.',
        resetDefaultTemplatesImpactSummary: '{types}/{devices}/{variables}',
        defaultTemplateChangeRefresh: 'Refresh bundled definition',
        templateSemanticsChanged: 'semantics change',
        devicesUsingChangedTemplates: 'Affected devices',
        environmentVariablesWillChange: '{count} environment changes',
        environmentChangeAdded: 'Add: {item}',
        environmentChangeUpdated: 'Update: {before} -> {after}',
        environmentChangeRemoved: 'Remove: {item}',
        trusted: 'Trusted',
        untrusted: 'Untrusted',
        public: 'Public',
        private: 'Private',
        useTemplateDefaultWithValue: 'Use template default ({value})',
        resetDefaultTemplatesNotice: 'The catalog and pool update together.',
        defaultTemplateResetReverificationRequired: 'Existing verification and simulation history does not establish the reset board.',
        defaultTemplatesResetSuccessReverificationRequired: 'Reset {types}/{devices}/{variables}. Rerun verification and simulation.',
        cancel: 'Cancel',
        resetting: 'Resetting'
      }
    }
  },
  missingWarn: false,
  fallbackWarn: false
})

const manifest = {
  Name: 'Switch',
  Description: 'Switch template',
  InitState: 'off',
  Modes: ['Mode'],
  WorkingStates: [{ Name: 'off', Trust: 'trusted', Privacy: 'public' }],
  InternalVariables: [{
    Name: 'SwitchState',
    IsInside: true,
    FalsifiableWhenCompromised: false,
    Trust: 'trusted',
    Privacy: 'public',
    Values: ['on', 'off']
  }],
  APIs: [{ Name: 'lock' }],
  Transitions: []
}

afterEach(() => {
  document.body.innerHTML = ''
})

beforeEach(() => {
  vi.clearAllMocks()
})

describe('ControlCenter model token display', () => {
  it('keeps reusable templates but distinguishes usage when devices change or the scene is empty', async () => {
    const wrapper = mount(ControlCenter, {
      props: { activeSection: 'templates', nodes: [
        { id: 'one', templateName: 'customswitch' }, { id: 'two', templateName: 'CustomSwitch' }
      ], deviceTemplates: [
        { id: 1, name: 'Switch', defaultTemplate: true, manifest },
        { id: 2, name: 'CustomSwitch', defaultTemplate: false, manifest: { ...manifest, Name: 'CustomSwitch' } },
        { id: 3, name: 'Unused', defaultTemplate: false, manifest: { ...manifest, Name: 'Unused' } }
      ] },
      global: { plugins: [i18n] }
    })
    expect(wrapper.get('[data-testid="template-library-summary"]').text()).toBe('Library 3 · Used 1 · Custom used 1')
    await wrapper.get('[data-testid="template-scope-scene"]').trigger('click')
    expect(wrapper.findAll('.template-card')).toHaveLength(1)
    expect(wrapper.get('.template-card').text()).toContain('CustomSwitch')
    expect(wrapper.get('.template-card').text()).toContain('Used by 2 devices')
    await wrapper.setProps({ nodes: [] })
    expect(wrapper.get('[data-testid="template-library-summary"]').text()).toBe('Library 3 · Used 0 · Custom used 0')
    expect(wrapper.findAll('.template-card')).toHaveLength(0)
    await wrapper.get('[data-testid="template-scope-all"]').trigger('click')
    expect(wrapper.findAll('.template-card')).toHaveLength(3)
    wrapper.unmount()
  })

  it('localizes only bundled template previews and preserves custom collisions', async () => {
    const wrapper = mount(ControlCenter, {
      attachTo: document.body,
      props: {
        activeSection: 'templates',
        deviceTemplates: [
          { id: 1, name: 'Switch', defaultTemplate: true, manifest },
          {
            id: 2,
            name: 'CustomSwitch',
            defaultTemplate: false,
            manifest: { ...manifest, Name: 'CustomSwitch' }
          },
          {
            id: 3,
            name: 'UnknownSwitch',
            manifest: { ...manifest, Name: 'UnknownSwitch' }
          }
        ]
      },
      global: { plugins: [i18n] }
    })

    const cards = wrapper.findAll('.template-card')
    await cards[0].get('button').trigger('click')
    const bundledPreview = document.querySelector('[data-testid="template-preview-1"]')
    expect(bundledPreview?.textContent).toContain('Disabled')
    expect(bundledPreview?.textContent).toContain('Operating mode')
    expect(bundledPreview?.textContent).toContain('Switch status')
    expect(bundledPreview?.textContent).toContain('Lock device')

    await cards[1].get('button').trigger('click')
    const customPreview = document.querySelector('[data-testid="template-preview-2"]')
    expect(customPreview?.textContent).toContain('off')
    expect(customPreview?.textContent).toContain('Mode')
    expect(customPreview?.textContent).toContain('SwitchState')
    expect(customPreview?.textContent).toContain('lock')
    expect(customPreview?.textContent).not.toContain('Disabled')
    expect(customPreview?.textContent).not.toContain('Operating mode')

    await cards[2].get('button').trigger('click')
    const unknownPreview = document.querySelector('[data-testid="template-preview-3"]')
    expect(unknownPreview?.textContent).toContain('off')
    expect(unknownPreview?.textContent).toContain('Mode')
    expect(unknownPreview?.textContent).toContain('SwitchState')
    expect(unknownPreview?.textContent).not.toContain('Disabled')
    expect(unknownPreview?.textContent).not.toContain('Operating mode')

    wrapper.unmount()
  })

  it('localizes bundled creation overrides without changing option values', async () => {
    const wrapper = mount(ControlCenter, {
      props: {
        activeSection: 'devices',
        deviceTemplates: [
          { id: 1, name: 'Switch', defaultTemplate: true, manifest },
          {
            id: 2,
            name: 'CustomSwitch',
            defaultTemplate: false,
            manifest: { ...manifest, Name: 'CustomSwitch' }
          },
          {
            id: 3,
            name: 'NumericSwitch',
            defaultTemplate: false,
            manifest: {
              ...manifest,
              Name: 'NumericSwitch',
              InternalVariables: [{
                Name: 'threshold',
                IsInside: true,
                FalsifiableWhenCompromised: false,
                Trust: 'trusted',
                Privacy: 'public',
                LowerBound: 5,
                UpperBound: 20
              }]
            }
          }
        ]
      },
      global: { plugins: [i18n] }
    })

    const templateSelect = wrapper.get('[data-testid="single-device-template"]')
    await templateSelect.setValue('Switch')
    const bundledState = wrapper.get('[data-testid="single-device-state"] option[value="off"]')
    const bundledValue = wrapper.get('[data-testid="single-device-variable-SwitchState"] option[value="on"]')
    expect(bundledState.text()).toBe('Disabled')
    expect(bundledState.attributes('value')).toBe('off')
    expect(bundledValue.text()).toBe('Enabled')
    expect(bundledValue.attributes('value')).toBe('on')
    expect(wrapper.get('[data-testid="single-device-variable-SwitchState"] option[value=""]').text())
      .toBe('Use template default (Enabled)')
    expect(wrapper.get('span[title="Switch status"]').text()).toBe('Switch status')

    await templateSelect.setValue('CustomSwitch')
    expect(wrapper.get('[data-testid="single-device-state"] option[value="off"]').text()).toBe('off')
    expect(wrapper.get('[data-testid="single-device-variable-SwitchState"] option[value="on"]').text()).toBe('on')
    expect(wrapper.get('span[title="SwitchState"]').text()).toBe('SwitchState')

    await templateSelect.setValue('NumericSwitch')
    expect(wrapper.get<HTMLInputElement>('[data-testid="single-device-variable-threshold"]').element.placeholder)
      .toBe('Use template default (5) / 5 - 20')

    wrapper.unmount()
  })

  it('shows exact reset environment changes and warns that model evidence must be rerun', async () => {
    const preview = {
      operation: 'preview' as const,
      impactToken: 'reset-impact-token',
      canApply: true,
      templateChanges: [{
        templateName: 'Switch',
        changeType: 'REFRESH_DEFAULT' as const,
        semanticsChanged: true
      }],
      affectedDevices: [{ deviceId: 'switch-1', deviceLabel: 'Hall switch', templateName: 'Switch' }],
      blockers: [],
      environmentChanges: [
        {
          changeType: 'ADDED' as const,
          name: 'weather',
          currentValue: { name: 'weather', value: 'sunny', trust: 'trusted', privacy: 'public' },
          previousModelTokenSource: 'UNKNOWN' as const,
          currentModelTokenSource: 'BUNDLED' as const
        },
        {
          changeType: 'UPDATED' as const,
          name: 'temperature',
          previousValue: { name: 'temperature', value: '20', trust: 'trusted', privacy: 'public' },
          currentValue: { name: 'temperature', value: '22', trust: 'untrusted', privacy: 'private' },
          previousModelTokenSource: 'CUSTOM' as const,
          currentModelTokenSource: 'BUNDLED' as const
        },
        {
          changeType: 'REMOVED' as const,
          name: 'legacyReading',
          previousValue: { name: 'legacyReading', value: 'on', trust: 'trusted', privacy: 'private' },
          previousModelTokenSource: 'CUSTOM' as const,
          currentModelTokenSource: 'UNKNOWN' as const
        }
      ],
      currentTemplates: [{ id: 1, name: 'Switch', defaultTemplate: true, manifest }],
      environmentVariables: []
    }
    boardApiMocks.previewDefaultTemplateReset.mockResolvedValue(preview)
    boardApiMocks.resetDefaultTemplates.mockResolvedValue({ ...preview, operation: 'reset' })

    const wrapper = mount(ControlCenter, {
      attachTo: document.body,
      props: {
        activeSection: 'templates',
        deviceTemplates: preview.currentTemplates
      },
      global: { plugins: [i18n] }
    })

    await wrapper.get('[data-testid="reset-default-templates"]').trigger('click')
    await flushPromises()

    const changes = document.querySelector('[data-testid="default-template-reset-environment-changes"]')
    expect(changes?.textContent).toContain('Add: Weather: Sunny · Trusted · Public')
    expect(changes?.textContent).toContain('Update: temperature: 20 · Trusted · Public -> Temperature: 22 · Untrusted · Private')
    expect(changes?.textContent).toContain('Remove: legacyReading: on · Trusted · Private')
    expect(changes?.textContent).not.toContain('legacyReading: Enabled')
    expect(document.querySelector('[data-testid="default-template-reset-reverification-warning"]')?.textContent)
      .toContain('does not establish the reset board')
    // Scrollability now comes from the shared primitive, which also carries the scrollbar skin and
    // scroll-padding; `overflow-y-auto` alone was only one of the four properties that matter. It lives on
    // the dialog's body region, so the header and the footer's actions stay put while a long impact
    // preview scrolls under them.
    const body = document.querySelector('.template-reset-dialog .iot-dialog__body')
    expect(body, 'the reset dialog should have a body region').not.toBeNull()
    expect(body?.classList).toContain('iot-scroll-region')

    await wrapper.get('[data-testid="default-template-reset-confirm"]').trigger('click')
    await flushPromises()

    expect(boardApiMocks.resetDefaultTemplates).toHaveBeenCalledWith('reset-impact-token')
    expect(messageMocks.success).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining('Rerun verification and simulation')
    }))

    wrapper.unmount()
  })
})
