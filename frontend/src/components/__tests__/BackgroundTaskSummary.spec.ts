// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { describe, expect, it } from 'vitest'
import actualI18n from '@/assets/i18n'
import type { SimulationTaskSummary } from '@/types/simulation'
import BackgroundTaskSummary from '../BackgroundTaskSummary.vue'

const task: SimulationTaskSummary = {
  id: 7, initiator: 'USER', status: 'RUNNING', progress: 31, requestedSteps: 3,
  createdAt: '2026-10-17T12:00:00', startedAt: '2026-10-17T12:00:01',
  isAttack: false, attackBudget: 0, enablePrivacy: false,
  modelSnapshot: { capturedAt: '2026-10-17T12:00:00', deviceCount: 2, ruleCount: 1,
    specificationCount: 1, environmentVariableCount: 0, deviceTemplateCount: 1,
    modelFingerprint: 'a'.repeat(64), templatesFrozen: true },
  modelSemantics: { attackPointUnit: 'BEHAVIOR_CHANGING_DEVICE_INSTANCE_OR_AUTOMATION_LINK',
    attackSelectionPolicy: 'NOT_MODELED', attackEffects: [], modeledDeviceAttackPointCount: 0,
    modeledFalsifiableReadingDeviceCount: 0, modeledAutomationLinkAttackPointCount: 0, modeledAttackPointCount: 0,
    trustPropagationPolicy: 'TARGET_UNTRUSTED_IF_ALL_TRIGGER_SOURCES_UNTRUSTED', privacyPropagationPolicy: 'NOT_MODELED',
    labelPropagationScope: 'AUTOMATION_RULE_COMMANDS_ONLY', environmentEvolutionEffects: [],
    localVariableFallbackPolicy: 'STUTTER_WHEN_NO_DECLARED_EVOLUTION' }
}

describe('background task submission snapshot', () => {
  it.each(['en', 'zh-CN'])('shows the submitted simulation steps and assumptions in %s', async locale => {
    const i18n = createI18n({ legacy: false, locale, messages: {
      en: actualI18n.global.getLocaleMessage('en'), 'zh-CN': actualI18n.global.getLocaleMessage('zh-CN')
    } })
    const wrapper = mount(BackgroundTaskSummary, { props: { kind: 'simulation', task }, global: { plugins: [i18n] } })
    try {
      expect(wrapper.get('[data-testid="frozen-simulation-steps"]').text()).toBe('3')
      expect(wrapper.findAll('input, select')).toHaveLength(0)
      expect(wrapper.text()).toContain(i18n.global.t('app.runAssumptionNoAttack'))
      expect(wrapper.text()).toContain(i18n.global.t('app.runAssumptionNoPrivacy'))
      i18n.global.locale.value = locale === 'en' ? 'zh-CN' : 'en'
      await wrapper.vm.$nextTick()
      expect(wrapper.get('h4').text()).toBe(i18n.global.t('app.backgroundTaskSnapshot', { id: 7 }))
      expect(wrapper.get('[data-testid="frozen-simulation-steps"]').text()).toBe('3')
    } finally { wrapper.unmount() }
  })

  it.each(['EXACT_ATTACK_POINTS', 'UP_TO_ATTACK_BUDGET_NONDETERMINISTIC'] as const)('keeps %s semantics in the read-only snapshot', policy => {
    const i18n = createI18n({ legacy: false, locale: 'en', messages: { en: actualI18n.global.getLocaleMessage('en') } })
    const wrapper = mount(BackgroundTaskSummary, { props: { kind: 'simulation', task: { ...task,
      isAttack: true, attackBudget: 2, enablePrivacy: true,
      modelSemantics: { ...task.modelSemantics, attackSelectionPolicy: policy, modeledAttackPointCount: 4 }
    } }, global: { plugins: [i18n] } })
    try {
      expect(wrapper.text()).toContain(policy === 'EXACT_ATTACK_POINTS'
        ? i18n.global.t('app.runAssumptionAttackPoints', { count: 4 })
        : i18n.global.t('app.runAssumptionAttackBudget', { count: 2, total: 4 }))
      expect(wrapper.text()).toContain(i18n.global.t('app.runAssumptionPrivacy'))
    } finally { wrapper.unmount() }
  })
})
