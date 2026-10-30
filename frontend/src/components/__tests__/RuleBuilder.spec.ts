// @vitest-environment jsdom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { describe, expect, it, vi } from 'vitest'
import RuleBuilder from '../RuleBuilder.vue'
import type { DeviceNode } from '@/types/node'
import actualI18n from '@/assets/i18n'
import boardApi, {
  type DuplicateRuleCheckResult,
  type RuleSimilarityResult
} from '@/api/board'

vi.mock('@/api/board', () => ({
  default: {
    checkDuplicateRule: vi.fn().mockResolvedValue({ isDuplicate: false, requiresReview: false }),
    checkRuleSimilarity: vi.fn()
  }
}))

const scrollIntoView = vi.fn()
Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scrollIntoView })

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      app: {
        ...actualI18n.global.getLocaleMessage('en').app,
        api: 'API',
        actionEvent: 'Action Event',
        action: 'Action',
        variable: 'Variable',
        internalVariable: 'Internal variable',
        environmentVariable: 'Environment variable',
        specVariableSourceReportedShort: 'Device-reported value',
        modes: 'Modes',
        state: 'State',
        none: 'None',
        selectPlaceholder: 'Select',
        enterValuePlaceholder: 'Enter value',
        selectDevicePlaceholder: 'Select device',
        selectAction: 'Select action',
        type: 'Type',
        device: 'Device',
        condition: 'Condition',
        value: 'Value',
        relationEquals: 'equals',
        relationNotEquals: 'not equals',
        relationGreater: 'greater than',
        relationLess: 'less than',
        relationGreaterEqual: 'at least',
        relationLessEqual: 'at most',
        relationIn: 'in',
        relationNotIn: 'not in',
        ifTrigger: 'IF',
        sourceDevices: 'Trigger sources',
        ruleLogicHint: 'AND logic',
        showHelpFor: 'Help for {topic}',
        thenAction: 'THEN',
        targetDevice: 'Target device',
        addSource: 'Add source',
        ruleName: 'Rule name',
        resetRuleDraft: 'Reset draft',
        ruleNamePlaceholder: 'Name',
        createNewRule: 'Create rule',
        cancel: 'Cancel',
        createRule: 'Create',
        saving: 'Saving',
        saveRulesFailed: 'Failed to save rules',
        identicalRuleNotSaved: 'This rule already exists.',
        ruleSaveDuplicateHint: 'Change the trigger or target action.',
        checkingAiSimilarity: 'Checking',
        aiSimilarityCheck: 'Check similarity',
        rulePreview: 'Preview',
        anyValue: 'Any',
        and: 'AND',
        unknown: 'Unknown',
        triggers: 'triggers',
        ifThenDescription: 'IF {source} THEN {target} {action}',
        contentPayload: 'Content payload',
        contentPayloadHint: 'Optional',
        acceptsContentSensitivity: 'Accepts content sensitivity',
        noContentPayload: 'None',
        contentSourceDevice: 'Source',
        contentItem: 'Item',
        selectContentDeviceFirst: 'Select source',
        selectContent: 'Select content',
        contentItemWithSensitivity: '{name}',
        completeRequiredFields: 'Complete fields',
        completeRequiredFieldsBeforeDuplicateCheck: 'Complete fields',
          addConfiguredRuleSourceBeforeSubmit: 'Add the configured IF condition to the rule before continuing.',
          ruleSourceDeviceMissing: 'A source device referenced by this draft no longer exists. Remove the highlighted condition before saving.',
          ruleTargetDeviceMissing: 'The target device referenced by this draft no longer exists. Select another target.',
          ruleContentDeviceMissing: 'The content source device referenced by this draft no longer exists. Select another content source.',
          missingDevice: 'Device removed',
          ruleChangedDuringCheck: 'The rule draft or device list changed while it was being checked. Review the latest values before saving.',
          selectRuleTargetBeforeSubmit: 'Select the THEN target device and action.',
        completeContentPayloadFields: 'Complete payload',
        close: 'Close',
        modelTokens: {
          lock: 'Lock device',
          mode: 'Operating mode',
          switchState: 'Switch status',
          photo: 'Photo item',
          content: 'Content item',
          on: 'Enabled',
          off: 'Disabled'
        }
      }
    }
  },
  missingWarn: false,
  fallbackWarn: false
})

const nodes: DeviceNode[] = [
  { id: 'source', label: 'Hall light', templateName: 'Light', position: { x: 0, y: 0 }, state: 'idle', width: 176, height: 128 },
  { id: 'target', label: 'Bedroom light', templateName: 'Light', position: { x: 220, y: 0 }, state: 'off', width: 176, height: 128 }
]

const template = {
  name: 'Light',
  manifest: {
    Name: 'Light',
    APIs: [
      { Name: 'turn_on', Signal: true, AcceptsContent: true },
      { Name: 'turn_off', Signal: false }
    ],
    InternalVariables: [],
    Modes: [],
    WorkingStates: [],
    Contents: [{ Name: 'photo', Privacy: 'private' }]
  }
}

const localizedTemplate = {
  name: 'Switch',
  defaultTemplate: true,
  manifest: {
    Name: 'Switch',
    APIs: [{ Name: 'lock', Signal: true, AcceptsContent: true }],
    InternalVariables: [{ Name: 'SwitchState', IsInside: true, Values: ['on', 'off'] }],
    Modes: ['Mode'],
    WorkingStates: [{ Name: 'off' }],
    Contents: [{ Name: 'photo', Privacy: 'private' as const }]
  }
}

const customCollisionTemplate = {
  ...localizedTemplate,
  name: 'CustomSwitch',
  defaultTemplate: false,
  manifest: {
    ...localizedTemplate.manifest,
    Name: 'CustomSwitch'
  }
}

const localizedNodes: DeviceNode[] = [
  { id: 'localized-source', label: 'Built-in source', templateName: 'Switch', position: { x: 0, y: 0 }, state: 'off', width: 176, height: 128 },
  { id: 'localized-target', label: 'Built-in target', templateName: 'Switch', position: { x: 220, y: 0 }, state: 'off', width: 176, height: 128 }
]

const customCollisionNodes: DeviceNode[] = [
  { id: 'custom-source', label: 'Custom source', templateName: 'CustomSwitch', position: { x: 0, y: 0 }, state: 'off', width: 176, height: 128 },
  { id: 'custom-target', label: 'Custom target', templateName: 'CustomSwitch', position: { x: 220, y: 0 }, state: 'off', width: 176, height: 128 }
]

const completeRuleDraft = async (wrapper: VueWrapper) => {
  await wrapper.get('#rule-source-device').setValue('source')
  await wrapper.get('#rule-source-type').setValue('api')
  await wrapper.get('#rule-source-api').setValue('turn_on')
  await wrapper.get('[data-testid="rule-add-source"]').trigger('click')
  await wrapper.get('#rule-target-device').setValue('target')
  await wrapper.get('#rule-target-action').setValue('turn_off')
}

describe('RuleBuilder action semantics', () => {
  it.each(['Existing hall rule', null])('keeps duplicate-check feedback localized without repeating the headline: %s', async matchedRule => {
    const previousLocale = actualI18n.global.locale.value
    actualI18n.global.locale.value = 'zh-CN'
    vi.mocked(boardApi.checkDuplicateRule).mockResolvedValueOnce({
      isDuplicate: true, requiresReview: false, matchedRule, similarity: 1,
      matchType: 'EXACT', reasonCode: 'EXACT_MATCH', reason: 'Technical duplicate reason', message: 'Duplicate'
    })
    const checkCount = vi.mocked(boardApi.checkDuplicateRule).mock.calls.length
    const wrapper = mount(RuleBuilder, { props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [actualI18n] } })
    try {
      await completeRuleDraft(wrapper)
      await wrapper.get('[data-testid="rule-save"]').trigger('click')
      await flushPromises()
      for (const locale of ['zh-CN', 'en'] as const) {
        actualI18n.global.locale.value = locale
        await flushPromises()
        const failure = wrapper.get('[data-testid="rule-save-failure"]')
        expect(failure.get('p').text()).toBe(actualI18n.global.t('app.identicalRuleNotSaved'))
        if (matchedRule) {
          expect(failure.get('[data-testid="failure-cause"]').text()).toContain(matchedRule)
          expect(failure.get('[data-testid="failure-cause"]').text()).toBe(actualI18n.global.t('app.identicalRuleNotSavedWithMatch', { rule: matchedRule }))
        } else {
          expect(failure.find('[data-testid="failure-cause"]').exists()).toBe(false)
        }
      }
      expect(vi.mocked(boardApi.checkDuplicateRule).mock.calls.length).toBe(checkCount + 1)
      expect(wrapper.emitted('save-rule')).toBeUndefined()
      expect(wrapper.get('#rule-target-action').element).toHaveProperty('value', 'turn_off')
    } finally { wrapper.unmount(); actualI18n.global.locale.value = previousLocale }
  })

  it.each(['add', 'clear'])('keeps a second condition draft explicit before save: %s', async action => {
    const wrapper = mount(RuleBuilder, { props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [i18n] } })
    try {
      await completeRuleDraft(wrapper)
      await wrapper.get('#rule-source-device').setValue('target')
      await wrapper.get('#rule-source-type').setValue('api')
      await wrapper.get('#rule-source-api').setValue('turn_on')
      const actions = wrapper.get('[data-testid="rule-clear-source"]').element.parentElement!
      expect(actions.classList.contains('grid-cols-1')).toBe(true)
      expect(actions.classList.contains('sm:grid-cols-2')).toBe(true)
      expect(wrapper.get('[data-testid="rule-save"]').attributes('disabled')).toBeDefined()
      expect(wrapper.get('[data-testid="rule-check-duplicate"]').attributes('disabled')).toBeDefined()
      expect(wrapper.get('[data-testid="rule-draft-readiness"]').text())
        .toBe(i18n.global.t('app.addConfiguredRuleSourceBeforeSubmit'))
      const checks = vi.mocked(boardApi.checkDuplicateRule).mock.calls.length
      await (wrapper.vm as any).handleSave()
      expect(boardApi.checkDuplicateRule).toHaveBeenCalledTimes(checks)
      expect(wrapper.emitted('save-rule')).toBeUndefined()
      await wrapper.get(`[data-testid="${action === 'add' ? 'rule-add-source' : 'rule-clear-source'}"]`).trigger('click')
      expect(wrapper.get('#rule-target-action').element).toHaveProperty('value', 'turn_off')
      expect(wrapper.get('[data-testid="rule-save"]').attributes('disabled')).toBeUndefined()
      await wrapper.get('[data-testid="rule-save"]').trigger('click')
      await flushPromises()
      const request = wrapper.emitted('save-rule')![0]![0] as any
      expect(request.rule.sources.map((source: any) => source.fromId))
        .toEqual(action === 'add' ? ['source', 'target'] : ['source'])
      request.complete(true)
      await flushPromises()
    } finally { wrapper.unmount() }
  })

  it('links an incomplete extra condition to its existing hint without repeating it', async () => {
    const wrapper = mount(RuleBuilder, { props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [i18n] } })
    try {
      await completeRuleDraft(wrapper)
      await wrapper.get('#rule-source-device').setValue('target')
      const save = wrapper.get('[data-testid="rule-save"]')
      expect(save.attributes('disabled')).toBeDefined()
      expect(save.attributes('aria-describedby')).toBe('rule-source-readiness')
      expect(wrapper.find('[data-testid="rule-draft-readiness"]').exists()).toBe(false)
      await wrapper.get('[data-testid="rule-clear-source"]').trigger('click')
      expect(save.attributes('disabled')).toBeUndefined()
      expect(wrapper.findAll('ol > li')).toHaveLength(1)
    } finally { wrapper.unmount() }
  })

  it('keeps invalid numeric conditions beside their value and accepts legal integer sets', async () => {
    const numericTemplate = { ...template, manifest: { ...template.manifest,
      InternalVariables: [{ Name: 'temperature', IsInside: true, LowerBound: 0, UpperBound: 40 }] } }
    const wrapper = mount(RuleBuilder, { props: { active: true, nodes, deviceTemplates: [numericTemplate] },
      global: { plugins: [i18n] } })
    try {
      await wrapper.get('#rule-source-device').setValue('source')
      await wrapper.get('#rule-source-type').setValue('variable')
      await wrapper.get('#rule-source-variable').setValue('temperature')
      for (const value of ['abc', '1.5', '-1', '41']) {
        await wrapper.get('#rule-source-value').setValue(value)
        expect(wrapper.get('[data-testid="rule-add-source"]').attributes('disabled'), value).toBeDefined()
        expect(wrapper.get('#rule-source-value').attributes('aria-invalid')).toBe('true')
        expect(wrapper.get('[data-testid="rule-source-readiness"]').text()).toBe(i18n.global.t('app.variableValueIntegerDomain', { lower: 0, upper: 40 }))
        await (wrapper.vm as any).addSource()
        expect((wrapper.vm as any).ruleData.sources).toHaveLength(0)
      }
      await wrapper.get('#rule-source-condition').setValue('in')
      await wrapper.get('#rule-source-value').setValue('0, 41')
      expect(wrapper.get('[data-testid="rule-add-source"]').attributes('disabled')).toBeDefined()
      await wrapper.get('#rule-source-value').setValue(' ,;| ')
      expect(wrapper.get('[data-testid="rule-add-source"]').attributes('disabled')).toBeDefined()
      await wrapper.get('#rule-source-value').setValue('0; +01 |40')
      expect(wrapper.get('#rule-source-value').attributes('aria-invalid')).toBeUndefined()
      expect(wrapper.get('[data-testid="rule-add-source"]').attributes('disabled')).toBeUndefined()
      await wrapper.get('[data-testid="rule-add-source"]').trigger('click')
      expect((wrapper.vm as any).ruleData.sources).toHaveLength(1)
    } finally { wrapper.unmount() }
  })
  it('names shared condition values as device reports and previews only a complete rule', async () => {
    const readableTemplate = { ...template, manifest: { ...template.manifest,
      InternalVariables: [{ Name: 'temperature', IsInside: false, Reads: true, LowerBound: 0, UpperBound: 40,
        Trust: 'trusted', Privacy: 'public', FalsifiableWhenCompromised: true, NaturalChangeRate: '0' }] } }
    const wrapper = mount(RuleBuilder, { props: { active: true, nodes, deviceTemplates: [readableTemplate as never] },
      global: { plugins: [i18n] } })
    try {
      await wrapper.get('#rule-source-device').setValue('source')
      await wrapper.get('#rule-source-type').setValue('variable')
      const option = wrapper.get('#rule-source-variable option[value="temperature"]')
      expect(option.text()).toContain('Device-reported value')
      expect(option.text()).not.toContain('Environment variable')
      await wrapper.get('#rule-source-variable').setValue('temperature')
      await wrapper.get('#rule-source-value').setValue('25')
      await wrapper.get('[data-testid="rule-add-source"]').trigger('click')
      await wrapper.get('#rule-target-device').setValue('target')
      expect(wrapper.find('[data-testid="rule-preview"]').exists()).toBe(false)
      await wrapper.get('#rule-target-action').setValue('turn_on')
      expect(wrapper.get('[data-testid="rule-preview"]').text()).toContain('turn_on')
      await wrapper.get('#rule-target-action').setValue('')
      expect(wrapper.find('[data-testid="rule-preview"]').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('clears a confirmed save while leaving the editor available for the next rule', async () => {
    const wrapper = mount(RuleBuilder, {
      props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [i18n] }
    })
    try {
      await wrapper.get('#rule-builder-name').setValue('Hall automation')
      await completeRuleDraft(wrapper)
      await wrapper.get('[data-testid="rule-save"]').trigger('click')
      await flushPromises()
      const request = wrapper.emitted('save-rule')?.[0]?.[0] as any
      expect(request.rule.name).toBe('Hall automation')
      request.complete(true)
      await flushPromises()
      expect(wrapper.get('[data-testid="rule-builder"]').isVisible()).toBe(true)
      expect(wrapper.get('#rule-builder-name').element).toHaveProperty('value', '')
      expect(wrapper.get('#rule-target-device').element).toHaveProperty('value', '')
      expect(wrapper.get('[data-testid="rule-save"]').element).toHaveProperty('disabled', true)
    } finally {
      wrapper.unmount()
    }
  })

  it.each(['active', 'readOnly'] as const)('retires a pending duplicate check when %s changes', async prop => {
    let resolveCheck!: (value: DuplicateRuleCheckResult) => void
    vi.mocked(boardApi.checkDuplicateRule).mockReturnValueOnce(new Promise(resolve => { resolveCheck = resolve }))
    const wrapper = mount(RuleBuilder, {
      props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [i18n] }
    })
    try {
      await completeRuleDraft(wrapper)
      await wrapper.get('[data-testid="rule-save"]').trigger('click')
      await wrapper.setProps(prop === 'active' ? { active: false } : { readOnly: true })
      resolveCheck({ isDuplicate: false, requiresReview: false, reasonCode: 'NO_EXISTING_RULES' } as DuplicateRuleCheckResult)
      await flushPromises()
      expect(wrapper.emitted('save-rule')).toBeUndefined()
      expect(wrapper.get('#rule-target-action').element).toHaveProperty('value', 'turn_off')
      if (prop === 'readOnly') expect(wrapper.get('fieldset').element).toHaveProperty('disabled', true)
    } finally {
      wrapper.unmount()
    }
  })

  it('clears a confirmed save after the responsive layout hides the editor', async () => {
    const wrapper = mount(RuleBuilder, {
      props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [i18n] }
    })
    try {
      await wrapper.get('#rule-builder-name').setValue('Hall automation')
      await completeRuleDraft(wrapper)
      await wrapper.get('[data-testid="rule-save"]').trigger('click')
      await flushPromises()
      const request = wrapper.emitted('save-rule')?.[0]?.[0] as any
      await wrapper.setProps({ active: false })
      request.complete(true)
      await flushPromises()
      await wrapper.setProps({ active: true })
      expect(wrapper.get('#rule-builder-name').element).toHaveProperty('value', '')
      expect(wrapper.get('#rule-target-device').element).toHaveProperty('value', '')
      expect(wrapper.get('[data-testid="rule-save"]').element).toHaveProperty('disabled', true)
    } finally {
      wrapper.unmount()
    }
  })

  it('keeps a conclusive duplicate precheck inline without an acknowledgement dialog', async () => {
    const acknowledgement = vi.spyOn(ElMessageBox, 'alert').mockRejectedValue(new Error('Unexpected acknowledgement dialog'))
    vi.mocked(boardApi.checkDuplicateRule).mockResolvedValueOnce({ isDuplicate: true, requiresReview: false,
      reasonCode: 'EXACT_MATCH', matchedRule: 'Existing hall automation', similarity: 1, matchType: 'EXACT',
      reason: 'Identical conditions and action', message: 'Identical rule' })
    const wrapper = mount(RuleBuilder, {
      props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [i18n] }
    })
    await completeRuleDraft(wrapper)
    await wrapper.get('[data-testid="rule-save"]').trigger('click')
    await flushPromises()
    expect(acknowledgement).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="rule-save-failure"]').text()).toContain('Existing hall automation')
    expect(wrapper.get('[data-testid="rule-save-failure"] [data-testid="failure-cause"]').isVisible()).toBe(true)
    expect(wrapper.emitted('save-rule')).toBeUndefined()
    expect(wrapper.get('#rule-target-action').element).toHaveProperty('value', 'turn_off')
    await wrapper.get('#rule-builder-name').setValue('Edited automation')
    expect(wrapper.find('[data-testid="rule-save-failure"]').exists()).toBe(false)
    wrapper.unmount()
    acknowledgement.mockRestore()
  })
  it('keeps duplicate rejection details in the draft until it is edited', async () => {
    scrollIntoView.mockClear()
    const wrapper = mount(RuleBuilder, {
      attachTo: document.body,
      props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [i18n] }
    })
    await completeRuleDraft(wrapper)
    await wrapper.get('[data-testid="rule-save"]').trigger('click')
    await flushPromises()
    const request = wrapper.emitted('save-rule')?.[0]?.[0] as any
    request.complete(false, { reasonCode: 'DUPLICATE_RULE', details: ['The rule duplicates an existing automation'],
      fields: { 'rules[1]': 'Change the trigger or action' } })
    await flushPromises()
    expect(wrapper.get('[data-testid="rule-save-failure"]').text()).toContain('Change the trigger or action')
    expect(wrapper.get('[data-testid="rule-save-failure"]').text()).toContain('rules[1]')
    expect(wrapper.get('[data-testid="rule-save-failure"]').text()).toContain('This rule already exists.')
    expect(wrapper.get('[data-testid="rule-save-failure"] [data-testid="failure-next-step"]').text()).toContain('Change the trigger or target action.')
    const failure = wrapper.get('[data-testid="rule-save-failure"]').element
    expect(document.activeElement).toBe(failure)
    expect(scrollIntoView).toHaveBeenCalledExactlyOnceWith({ block: 'nearest' })
    expect(scrollIntoView.mock.contexts[0]).toBe(failure)
    expect(wrapper.get('#rule-target-action').element).toHaveProperty('value', 'turn_off')
    await wrapper.get('#rule-builder-name').setValue('Revised rule')
    expect(wrapper.find('[data-testid="rule-save-failure"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows a field rejection as a correction request instead of a duplicate claim', async () => {
    const wrapper = mount(RuleBuilder, {
      props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [i18n] }
    })
    await completeRuleDraft(wrapper)
    await wrapper.get('[data-testid="rule-save"]').trigger('click')
    await flushPromises()
    const request = wrapper.emitted('save-rule')?.[0]?.[0] as any
    request.complete(false, { details: ['Value outside the allowed range'],
      fields: { 'rules[0].conditions[0].value': 'Value must be within [0, 100]' } })
    await flushPromises()
    const failure = wrapper.get('[data-testid="rule-save-failure"]')
    expect(failure.text()).toContain('Failed to save rules')
    expect(failure.find('[data-testid="failure-next-step"]').exists()).toBe(false)
    expect(failure.text()).toContain('rules[0].conditions[0].value')
    expect(failure.text()).toContain('Value must be within [0, 100]')
    expect(failure.text()).not.toContain('This rule already exists.')
    expect(wrapper.get('#rule-target-action').element).toHaveProperty('value', 'turn_off')
    wrapper.unmount()
  })

  it.each([false, true])('does not apply an old save completion (%s) after leaving and reopening the form', async (saved) => {
    const wrapper = mount(RuleBuilder, {
      props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [i18n] }
    })
    await completeRuleDraft(wrapper)
    await wrapper.get('[data-testid="rule-save"]').trigger('click')
    await flushPromises()
    const request = wrapper.emitted('save-rule')?.[0]?.[0] as any
    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    request.complete(saved, saved ? undefined : { details: ['Old rejection'], fields: { 'rules[1]': 'Old duplicate' } })
    await flushPromises()
    expect(wrapper.find('[data-testid="rule-save-failure"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('localizes bundled model tokens for display while submitting canonical rule values', async () => {
    const wrapper = mount(RuleBuilder, {
      props: { active: true, nodes: localizedNodes, deviceTemplates: [localizedTemplate] },
      global: { plugins: [i18n] }
    })

    await wrapper.get('#rule-source-device').setValue('localized-source')

    await wrapper.get('#rule-source-type').setValue('mode')
    const modeOption = wrapper.get('#rule-source-mode').get('option[value="Mode"]')
    expect(modeOption.text()).toBe('Operating mode')
    expect(modeOption.attributes('value')).toBe('Mode')
    await wrapper.get('#rule-source-mode').setValue('Mode')
    expect(wrapper.get('#rule-source-value').get('option[value="off"]').text()).toBe('Disabled')

    await wrapper.get('#rule-source-type').setValue('state')
    expect(wrapper.get('#rule-source-value').get('option[value="off"]').text()).toBe('Disabled')

    await wrapper.get('#rule-source-type').setValue('variable')
    const variableOption = wrapper.get('#rule-source-variable').get('option[value="SwitchState"]')
    expect(variableOption.text()).toBe('Switch status (Internal variable)')
    expect(variableOption.attributes('value')).toBe('SwitchState')
    await wrapper.get('#rule-source-variable').setValue('SwitchState')
    const valueOption = wrapper.get('#rule-source-value').get('option[value="on"]')
    expect(valueOption.text()).toBe('Enabled')
    expect(valueOption.attributes('value')).toBe('on')
    await wrapper.get('#rule-source-value').setValue('on')
    await wrapper.get('[data-testid="rule-add-source"]').trigger('click')

    await wrapper.get('#rule-target-device').setValue('localized-target')
    const actionOption = wrapper.get('#rule-target-action').get('option[value="lock"]')
    expect(actionOption.text()).toBe('Lock device')
    expect(actionOption.attributes('value')).toBe('lock')
    await wrapper.get('#rule-target-action').setValue('lock')
    await wrapper.get('[data-testid="rule-content-device"]').setValue('localized-source')
    const contentOption = wrapper.get('[data-testid="rule-content-name"] option[value="photo"]')
    expect(contentOption.text()).toBe('Photo item')
    expect(contentOption.attributes('value')).toBe('photo')
    await wrapper.get('[data-testid="rule-content-name"]').setValue('photo')
    expect(wrapper.text()).toContain('Built-in source.Photo item')
    await wrapper.get('[data-testid="rule-save"]').trigger('click')
    await flushPromises()

    const saveRequest = wrapper.emitted('save-rule')?.[0]?.[0] as any
    expect(saveRequest.rule.sources[0]).toMatchObject({
      fromApi: 'SwitchState',
      value: 'on'
    })
    expect(saveRequest.rule.toApi).toBe('lock')
    expect(saveRequest.rule.contentDevice).toBe('localized-source')
    expect(saveRequest.rule.content).toBe('photo')
    saveRequest.complete(true)
  })

  it('keeps colliding custom-template tokens unchanged in every rule selector', async () => {
    const wrapper = mount(RuleBuilder, {
      props: { active: true, nodes: customCollisionNodes, deviceTemplates: [customCollisionTemplate] },
      global: { plugins: [i18n] }
    })

    await wrapper.get('#rule-source-device').setValue('custom-source')
    await wrapper.get('#rule-source-type').setValue('mode')
    expect(wrapper.get('#rule-source-mode').get('option[value="Mode"]').text()).toBe('Mode')
    await wrapper.get('#rule-source-mode').setValue('Mode')
    expect(wrapper.get('#rule-source-value').get('option[value="off"]').text()).toBe('off')

    await wrapper.get('#rule-source-type').setValue('variable')
    expect(wrapper.get('#rule-source-variable').get('option[value="SwitchState"]').text())
      .toBe('SwitchState (Internal variable)')
    await wrapper.get('#rule-source-variable').setValue('SwitchState')
    expect(wrapper.get('#rule-source-value').get('option[value="on"]').text()).toBe('on')

    await wrapper.get('#rule-target-device').setValue('custom-target')
    expect(wrapper.get('#rule-target-action').get('option[value="lock"]').text()).toBe('lock')
    await wrapper.get('#rule-target-action').setValue('lock')
    await wrapper.get('[data-testid="rule-content-device"]').setValue('custom-source')
    const contentOption = wrapper.get('[data-testid="rule-content-name"] option[value="photo"]')
    expect(contentOption.text()).toBe('photo')
    expect(contentOption.attributes('value')).toBe('photo')
  })

  // The value placeholder had its own single-bound rule: it accepted *either* bound and printed the
  // missing side as an infinity, so it advertised `(5 - ∞)` for a domain `device-template-schema.json`
  // cannot express (`oneOf: [{Values}, {LowerBound, UpperBound}]`). Nothing asserted the placeholder,
  // which is how the divergence from `templateVariableUsesNumericBounds` stayed invisible.
  it('shows a value range only when the variable declares both bounds', async () => {
    const boundedTemplate = {
      name: 'Sensors',
      manifest: {
        Name: 'Sensors',
        APIs: [],
        InternalVariables: [
          { Name: 'temperature', IsInside: true, LowerBound: 15, UpperBound: 35 },
          { Name: 'pressure', IsInside: true, LowerBound: 5 }
        ],
        Modes: [],
        WorkingStates: [],
        Contents: []
      }
    }
    const sensorNodes: DeviceNode[] = [
      { id: 'probe', label: 'Probe', templateName: 'Sensors', position: { x: 0, y: 0 }, state: 'idle', width: 176, height: 128 },
      { id: 'sink', label: 'Sink', templateName: 'Sensors', position: { x: 220, y: 0 }, state: 'idle', width: 176, height: 128 }
    ]
    const wrapper = mount(RuleBuilder, {
      props: { active: true, nodes: sensorNodes, deviceTemplates: [boundedTemplate as never] },
      global: { plugins: [i18n] }
    })

    await wrapper.get('#rule-source-device').setValue('probe')
    await wrapper.get('#rule-source-type').setValue('variable')

    await wrapper.get('#rule-source-variable').setValue('temperature')
    expect(wrapper.get('#rule-source-value').attributes('placeholder')).toBe('Enter value (15 - 35)')

    // A half-declared domain is not a range: no suffix, and never an infinity.
    await wrapper.get('#rule-source-variable').setValue('pressure')
    const halfBounded = wrapper.get('#rule-source-value').attributes('placeholder')
    expect(halfBounded).toBe('Enter value')
    expect(halfBounded).not.toContain('∞')
  })

  it('offers only observable action events as IF sources while keeping all actions available for THEN', async () => {
    const wrapper = mount(RuleBuilder, {
      props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [i18n] }
    })

    await wrapper.get('#rule-source-device').setValue('source')
    expect(wrapper.get('#rule-source-type').text()).toContain('Action Event')
    await wrapper.get('#rule-source-type').setValue('api')
    const eventValues = wrapper.get('#rule-source-api').findAll('option').map(option => option.attributes('value'))
    expect(eventValues).toContain('turn_on')
    expect(eventValues).not.toContain('turn_off')

    await wrapper.get('#rule-target-device').setValue('target')
    const actionValues = wrapper.get('#rule-target-action').findAll('option').map(option => option.attributes('value'))
    expect(actionValues).toContain('turn_on')
    expect(actionValues).toContain('turn_off')
  })

  it('offers content sensitivity only for an action that declares it can carry content', async () => {
    const wrapper = mount(RuleBuilder, {
      props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [i18n] }
    })

    await wrapper.get('#rule-target-device').setValue('target')
    await wrapper.get('#rule-target-action').setValue('turn_off')
    expect(wrapper.find('#rule-content-device').exists()).toBe(false)

    await wrapper.get('#rule-target-action').setValue('turn_on')
    expect(wrapper.find('#rule-content-device').exists()).toBe(true)
  })

  it('keeps rule actions disabled until the configured IF condition is explicitly added', async () => {
    const wrapper = mount(RuleBuilder, {
      props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [i18n] }
    })

    const saveButton = wrapper.get('[data-testid="rule-save"]')
    const similarityButton = wrapper.get('[data-testid="rule-check-duplicate"]')
    expect((saveButton.element as HTMLButtonElement).disabled).toBe(true)
    expect((similarityButton.element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.get('[data-testid="rule-source-readiness"]').text()).toBe(i18n.global.t('app.selectDevice'))
    expect(wrapper.find('[data-testid="rule-draft-readiness"]').exists()).toBe(false)
    expect(saveButton.attributes('aria-describedby')).toBe('rule-source-readiness')

    await wrapper.get('#rule-source-device').setValue('source')
    await wrapper.get('#rule-source-type').setValue('api')
    await wrapper.get('#rule-source-api').setValue('turn_on')
    await wrapper.get('#rule-target-device').setValue('target')
    await wrapper.get('#rule-target-action').setValue('turn_off')

    expect((saveButton.element as HTMLButtonElement).disabled).toBe(true)
    expect((similarityButton.element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.get('[data-testid="rule-draft-readiness"]').text())
      .toBe('Add the configured IF condition to the rule before continuing.')

    await wrapper.get('[data-testid="rule-add-source"]').trigger('click')

    expect((saveButton.element as HTMLButtonElement).disabled).toBe(false)
    expect((similarityButton.element as HTMLButtonElement).disabled).toBe(false)
    expect(wrapper.find('[data-testid="rule-draft-readiness"]').exists()).toBe(false)
  })

  it('explains missing THEN and partial content payload fields before enabling rule actions', async () => {
    const wrapper = mount(RuleBuilder, {
      props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [i18n] }
    })

    await wrapper.get('#rule-source-device').setValue('source')
    await wrapper.get('#rule-source-type').setValue('api')
    await wrapper.get('#rule-source-api').setValue('turn_on')
    await wrapper.get('[data-testid="rule-add-source"]').trigger('click')

    const saveButton = wrapper.get('[data-testid="rule-save"]')
    expect((saveButton.element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.get('[data-testid="rule-draft-readiness"]').text())
      .toBe('Select the THEN target device and action.')

    await wrapper.get('#rule-target-device').setValue('target')
    await wrapper.get('#rule-target-action').setValue('turn_on')
    await wrapper.get('[data-testid="rule-content-device"]').setValue('source')

    expect((saveButton.element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.get('[data-testid="rule-draft-readiness"]').text()).toBe('Complete payload')

    await wrapper.get('[data-testid="rule-content-name"]').setValue('photo')

    expect((saveButton.element as HTMLButtonElement).disabled).toBe(false)
    expect(wrapper.find('[data-testid="rule-draft-readiness"]').exists()).toBe(false)
  })

  it('blocks a stale draft when another Board refresh removes a referenced device', async () => {
    const wrapper = mount(RuleBuilder, {
      props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [i18n] }
    })

    await wrapper.get('#rule-source-device').setValue('source')
    await wrapper.get('#rule-source-type').setValue('api')
    await wrapper.get('#rule-source-api').setValue('turn_on')
    await wrapper.get('[data-testid="rule-add-source"]').trigger('click')
    await wrapper.get('#rule-target-device').setValue('target')
    await wrapper.get('#rule-target-action').setValue('turn_off')
    expect((wrapper.get('[data-testid="rule-save"]').element as HTMLButtonElement).disabled).toBe(false)

    await wrapper.setProps({ nodes: nodes.filter(node => node.id !== 'source') })

    expect((wrapper.get('[data-testid="rule-save"]').element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.get('[data-testid="rule-draft-readiness"]').text())
      .toContain('source device referenced by this draft no longer exists')
    expect(wrapper.text()).toContain('Device removed')
    expect(wrapper.emitted('save')).toBeUndefined()
  })

  it('revalidates the draft after an async duplicate check returns', async () => {
    let resolveCheck!: (value: DuplicateRuleCheckResult) => void
    vi.mocked(boardApi.checkDuplicateRule).mockReturnValueOnce(new Promise(resolve => {
      resolveCheck = resolve
    }))
    const wrapper = mount(RuleBuilder, {
      props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [i18n] }
    })

    await wrapper.get('#rule-source-device').setValue('source')
    await wrapper.get('#rule-source-type').setValue('api')
    await wrapper.get('#rule-source-api').setValue('turn_on')
    await wrapper.get('[data-testid="rule-add-source"]').trigger('click')
    await wrapper.get('#rule-target-device').setValue('target')
    await wrapper.get('#rule-target-action').setValue('turn_off')
    const savePromise = wrapper.get('[data-testid="rule-save"]').trigger('click')
    await wrapper.setProps({ nodes: nodes.filter(node => node.id !== 'source') })
    resolveCheck({
      isDuplicate: false,
      requiresReview: false,
      similarity: 0,
      matchType: 'NONE',
      reasonCode: 'NO_EXISTING_RULES',
      reason: 'No matching rule',
      message: 'No matching rule'
    })
    await savePromise
    await flushPromises()

    expect(wrapper.emitted('save-rule')).toBeUndefined()
    expect(wrapper.get('[data-testid="rule-draft-readiness"]').text())
      .toContain('source device referenced by this draft no longer exists')
  })

  it('does not save when the draft resets during a duplicate check', async () => {
    let resolveCheck!: (value: DuplicateRuleCheckResult) => void
    vi.mocked(boardApi.checkDuplicateRule).mockReturnValueOnce(new Promise(resolve => {
      resolveCheck = resolve
    }))
    const wrapper = mount(RuleBuilder, {
      props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [i18n] }
    })
    await completeRuleDraft(wrapper)

    await wrapper.get('[data-testid="rule-save"]').trigger('click')
    await wrapper.get('[data-testid="rule-reset"]').trigger('click')
    resolveCheck({
      isDuplicate: false,
      requiresReview: false,
      similarity: 0,
      matchType: 'NONE',
      reasonCode: 'NO_EXISTING_RULES',
      reason: 'No matching rule',
      message: 'No matching rule'
    })
    await flushPromises()

    expect(wrapper.get('#rule-builder-name').element).toHaveProperty('value', '')
    expect(wrapper.emitted('save-rule')).toBeUndefined()
  })

  it('does not report or save a similarity result after the draft resets', async () => {
    const successMessage = vi.spyOn(ElMessage, 'success')
    let resolveCheck!: (value: RuleSimilarityResult) => void
    vi.mocked(boardApi.checkRuleSimilarity).mockReturnValueOnce(new Promise(resolve => {
      resolveCheck = resolve
    }))
    const wrapper = mount(RuleBuilder, {
      props: { active: true, nodes, deviceTemplates: [template] },
      global: { plugins: [i18n] }
    })
    await completeRuleDraft(wrapper)

    await wrapper.get('[data-testid="rule-check-duplicate"]').trigger('click')
    await wrapper.get('[data-testid="rule-reset"]').trigger('click')
    resolveCheck({
      isSimilar: false,
      isDuplicate: false,
      requiresReview: false,
      similarity: 0,
      reasonCode: 'NO_EXISTING_RULES',
      reason: 'No matching rule',
      message: 'No matching rule'
    })
    await flushPromises()

    expect(wrapper.get('#rule-builder-name').element).toHaveProperty('value', '')
    expect(wrapper.emitted('save-rule')).toBeUndefined()
    expect(successMessage).not.toHaveBeenCalled()
    successMessage.mockRestore()
  })
})
