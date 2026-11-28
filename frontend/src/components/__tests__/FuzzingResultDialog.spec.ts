// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { defineAsyncComponent, defineComponent, h, nextTick, ref } from 'vue'
import { describe, expect, it } from 'vitest'
import FuzzingResultDialog from '../FuzzingResultDialog.vue'
import InfoTooltip from '../common/InfoTooltip.vue'
import type { AvailableFuzzingRunSummary } from '@/types/fuzzing'
import type { Specification } from '@/types/spec'
import actualI18n from '@/assets/i18n'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: {
    en: actualI18n.global.getLocaleMessage('en'),
    'zh-CN': actualI18n.global.getLocaleMessage('zh-CN')
  }
})

const snapshot = {
  capturedAt: '2026-07-14T10:00:00',
  deviceCount: 1,
  ruleCount: 1,
  specificationCount: 1,
  environmentVariableCount: 0,
  deviceTemplateCount: 1,
  modelFingerprint: 'a'.repeat(64),
  modelSemanticsVersion: 1,
  templatesFrozen: true as const
}

const run: AvailableFuzzingRunSummary & { targetSpecIds: string[] } = {
  id: 10,
  initiator: 'AI_ASSISTANT',
  explorationMode: 'PAPER_COMPATIBLE',
  outcome: 'FOUND_VIOLATION',
  effectiveSeed: 42,
  iterations: 20,
  generatedPaths: 200,
  elapsedMs: 900,
  modelSnapshot: snapshot,
  eligibleSpecifications: {
    'spec-1': { name: 'Door remains closed', id: 'spec-1', templateId: '1', templateLabel: 'Always', aConditions: [], ifConditions: [], thenConditions: [] },
    'spec-3': { name: 'Window remains closed', id: 'spec-3', templateId: '1', templateLabel: 'Always', aConditions: [], ifConditions: [], thenConditions: [] }
  },
  modelTokenSourcesByDeviceId: {},
  eligibility: {
    eligibleSpecIds: ['spec-1', 'spec-3'],
    eligibleSpecLabels: {
      'spec-1': 'Door remains closed',
      'spec-3': 'Window remains closed'
    },
    ineligibleSpecs: [{
      specId: 'spec-2',
      specificationLabel: 'Unsupported response',
      reasonCode: 'UNSUPPORTED_TEMPLATE',
      reason: 'backend-only English diagnostic'
    }],
    requestedSpecCount: 3,
    eligibleSpecCount: 2
  },
  limitations: [
    'FINITE_TRACE_TEMPLATES_1_3_4_ONLY',
    'ATTACK_TRUST_PRIVACY_CONTENT_UNSUPPORTED',
    'FINAL_ANTECEDENT_WITHOUT_SUCCESSOR_INCONCLUSIVE',
    'PAPER_EVENT_FSM_DISTANCE_ENABLED',
    'PAPER_RANDOM_INITIAL_STATE_ENABLED',
    'PAPER_STRUCTURED_LTL_TEMPLATES_ONLY',
    'PAPER_INTEGER_NUMERIC_DOMAIN_ONLY',
    'PAPER_DISCRETE_ENVIRONMENT_DIRECT_VALUE_EXTENSION',
    'NUSMV_REMAINS_PROOF_AUTHORITY',
    'PAPER_PREDECESSOR_STATE_OUTPUTS_THREE_LEVELS_ONLY',
    'PAPER_MULTI_TARGET_PRODUCT_EXTENSION',
    'FUTURE_LIMITATION'
  ],
  maxIterations: 20,
  pathLength: 10,
  populationSize: 10,
  createdAt: '2026-07-14T10:00:00',
  completedAt: '2026-07-14T10:00:01',
  findingCount: 1,
  dataAvailable: true,
  targetSpecIds: ['spec-1', 'spec-3'],
  findings: [{
    id: 11,
    fuzzTaskId: 10,
    violatedSpecId: 'spec-1',
    violatedSpec: { name: 'Safety requirement',
      id: 'spec-1',
      templateId: '1',
      templateLabel: 'Door remains closed',
      aConditions: [],
      ifConditions: [],
      thenConditions: []
    },
    firstViolationStep: 3,
    seed: 42,
    createdAt: '2026-07-14T10:00:01',
    stateCount: 4
  }]
}

describe('FuzzingResultDialog', () => {
  it('leads with frozen requirement names instead of template formulas', () => {
    const wrapper = mount(FuzzingResultDialog, { props: { visible: true, run }, global: { plugins: [i18n] } })
    try {
      expect(wrapper.get('[data-testid="fuzz-finding-name"]').text()).toBe('Safety requirement')
      expect(wrapper.get('[aria-labelledby="fuzz-target-results-title"] .iot-result-item__title').text()).toBe('Window remains closed')
    } finally { wrapper.unmount() }
  })

  it('retains a history opener through a cold lazy mount and conditional close', async () => {
    let resolveDialog!: (component: typeof FuzzingResultDialog) => void
    const LazyDialog = defineAsyncComponent(() => new Promise<typeof FuzzingResultDialog>(resolve => { resolveDialog = resolve }))
    const Parent = defineComponent({
      setup() {
        const visible = ref(false)
        const history = ref(true)
        return () => h('main', [
          h('div', { 'aria-hidden': visible.value ? 'true' : undefined }, [
            h('button', { 'data-testid': 'open-history-panel' }, 'History')
          ]),
          history.value ? h('section', { 'data-testid': 'trace-history-panel' }, [
            h('button', { 'data-testid': 'open-result', onClick: () => { visible.value = true } }, 'Open result')
          ]) : null,
          visible.value ? h(LazyDialog, { visible: true, run,
            onReady: () => { history.value = false }, onClose: () => { visible.value = false } }) : null
        ])
      }
    })
    const wrapper = mount(Parent, { attachTo: document.body, global: { plugins: [i18n] } })
    try {
      ;(wrapper.get('[data-testid="open-result"]').element as HTMLElement).focus()
      await wrapper.get('[data-testid="open-result"]').trigger('click')
      expect(wrapper.find('[data-testid="trace-history-panel"]').exists()).toBe(true)
      expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
      resolveDialog(FuzzingResultDialog)
      await flushPromises()
      expect(wrapper.find('[data-testid="trace-history-panel"]').exists()).toBe(false)
      expect(document.activeElement).toBe(wrapper.get('[role="dialog"]').element)
      await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
      await flushPromises()
      expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
      expect(document.activeElement).toBe(wrapper.get('[data-testid="open-history-panel"]').element)
    } finally { wrapper.unmount() }
  })

  it.each([0, 120, 499, 999])('does not round a %sms run down to zero seconds', elapsedMs => {
    const wrapper = mount(FuzzingResultDialog, {
      props: { visible: true, run: { ...run, elapsedMs } }, global: { plugins: [i18n] }
    })
    try {
      const summary = wrapper.get('[aria-labelledby="fuzz-run-summary-title"]')
      expect(summary.text()).toContain('Less than 1 second')
      expect(summary.text()).not.toContain('0 seconds')
    } finally { wrapper.unmount() }
  })
  it('lists each target once and removes the second target list when every target has a candidate', async () => {
    const wrapper = mount(FuzzingResultDialog, {
      props: { visible: true, run }, global: { plugins: [i18n] }
    })
    try {
      const targets = wrapper.get('[aria-labelledby="fuzz-target-results-title"]')
      expect(targets.findAll('li')).toHaveLength(1)
      expect(targets.text()).toContain('Window remains closed')
      expect(targets.text()).not.toContain('Door remains closed')
      expect(targets.findAll('li .iot-result-item__title')).toHaveLength(1)
      await wrapper.setProps({ run: {
        ...run, eligibility: { ...run.eligibility, eligibleSpecIds: ['spec-1'] }
      } })
      expect(wrapper.find('[aria-labelledby="fuzz-target-results-title"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="verify-fuzzing-finding-11"]').exists()).toBe(true)
    } finally { wrapper.unmount() }
  })

  it('shows a time-limited search warning outside optional boundaries for either outcome', async () => {
    const wrapper = mount(FuzzingResultDialog, {
      props: { visible: true, run: { ...run, limitations: ['TIME_BUDGET_EXHAUSTED'] } },
      global: { plugins: [i18n] }
    })
    try {
      const warning = () => wrapper.get('[data-testid="fuzz-time-limit-warning"]')
      expect(warning().attributes('role')).toBe('alert')
      expect(warning().element.closest('details')).toBeNull()
      expect(warning().text()).toContain('planned search was not executed')
      expect(wrapper.find('[data-testid="fuzz-limitations-details"]').exists()).toBe(false)
      await wrapper.setProps({ run: { ...run, outcome: 'BUDGET_EXHAUSTED',
        findings: [], findingCount: 0, limitations: ['TIME_BUDGET_EXHAUSTED'] } })
      expect(warning().attributes('role')).toBe('alert')
      expect(wrapper.text()).toContain('Search time limit reached')
      expect(wrapper.text()).not.toContain('Search budget exhausted')
      expect(wrapper.text()).toContain('Specification satisfaction remains undetermined.')
    } finally { wrapper.unmount() }
  })

  it('prioritizes evidence and keeps configuration optional without hiding scope warnings or reuse', async () => {
    const wrapper = mount(FuzzingResultDialog, {
      props: { visible: true, run, boardComparison: 'CHANGED' }, global: { plugins: [i18n] }
    })
    try {
      const findings = wrapper.get('[aria-labelledby="fuzz-findings-title"]')
      const targets = wrapper.get('[aria-labelledby="fuzz-target-results-title"]')
      const summary = wrapper.get('[aria-labelledby="fuzz-run-summary-title"]')
      for (const evidence of [findings, targets]) {
        expect(evidence.element.compareDocumentPosition(summary.element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      }
      for (const id of ['fuzz-reproduction-details', 'fuzz-snapshot-details', 'fuzz-limitations-details']) {
        expect(wrapper.get<HTMLDetailsElement>(`[data-testid="${id}"]`).element.open).toBe(false)
      }
      const warning = wrapper.get('[data-testid="fuzzing-board-drift-warning"]')
      expect(warning.element.closest('details')).toBeNull()
      expect(warning.element.compareDocumentPosition(findings.element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect(summary.text()).not.toContain('Effective seed')
      expect(wrapper.findAll('dt').filter(item => item.text() === 'Effective seed')).toHaveLength(1)
      expect(findings.get('article p').classes()).not.toContain('truncate')
      expect(targets.get('li p').classes()).not.toContain('truncate')
      const reuse = wrapper.get('[data-testid="reuse-fuzzing-settings"]')
      expect(reuse.element.closest('details')).toBeNull()
      await reuse.trigger('click')
      expect(wrapper.emitted('reuseSettings')).toHaveLength(1)
      await wrapper.get('[data-testid="fuzz-reproduction-details"] summary').trigger('click')
      expect(wrapper.get<HTMLDetailsElement>('[data-testid="fuzz-reproduction-details"]').element.open).toBe(true)
    } finally { wrapper.unmount() }
  })

  it('distinguishes missing comparison evidence, a failed comparison, and a confirmed match', async () => {
    const wrapper = mount(FuzzingResultDialog, {
      props: { visible: true, run }, global: { plugins: [i18n] }
    })
    const warning = () => wrapper.get('[data-testid="fuzzing-board-drift-warning"]')
    expect(warning().attributes('data-state')).toBe('NOT_COMPARED')
    expect(warning().text()).toContain('no model comparison evidence')
    await wrapper.setProps({ boardComparison: 'UNAVAILABLE' })
    expect(warning().attributes('data-state')).toBe('UNAVAILABLE')
    expect(warning().text()).toContain('could not be read')
    expect(warning().text()).not.toContain('differs from the current Board model')
    await wrapper.setProps({ boardComparison: 'SEMANTICS_CHANGED' })
    expect(warning().attributes('data-state')).toBe('SEMANTICS_CHANGED')
    expect(warning().text()).toContain('modeling version')
    await wrapper.setProps({ boardComparison: 'UNCHANGED' })
    expect(wrapper.find('[data-testid="fuzzing-board-drift-warning"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('The current board model matches this run.')
    wrapper.unmount()
  })
  it('distinguishes same-template targets with frozen conditions and localizes only bundled tokens', async () => {
    const heating: Specification = { name: 'Safety requirement', id: 'spec-1', templateId: '3', templateLabel: 'Never',
      aConditions: [{ id: 'c1', side: 'a', deviceId: 'ac_1', deviceLabel: '测试空调',
        targetType: 'mode', key: 'HvacMode', relation: '=', value: 'heat' }],
      ifConditions: [], thenConditions: [] }
    const cooling: Specification = { ...heating, id: 'spec-3',
      aConditions: [{ ...heating.aConditions[0], id: 'c2', value: 'cool' }] }
    const frozenRun: AvailableFuzzingRunSummary = { ...run,
      modelSnapshot: { ...snapshot, specificationCount: 2 },
      eligibleSpecifications: { 'spec-1': heating, 'spec-3': cooling },
      modelTokenSourcesByDeviceId: { ac_1: 'BUNDLED' },
      eligibility: { eligibleSpecIds: ['spec-1', 'spec-3'], eligibleSpecLabels: { 'spec-1': 'Never', 'spec-3': 'Never' },
        ineligibleSpecs: [], requestedSpecCount: 2, eligibleSpecCount: 2 },
      findings: [{ ...run.findings[0], violatedSpec: heating }] }
    const previousLocale = i18n.global.locale.value
    i18n.global.locale.value = 'zh-CN'
    const wrapper = mount(FuzzingResultDialog, {
      props: { visible: true, run: frozenRun }, global: { plugins: [i18n] }
    })
    try {
      const targets = wrapper.get('[aria-labelledby="fuzz-target-results-title"]')
      expect(wrapper.get('[aria-labelledby="fuzz-findings-title"]').text()).toContain('要求永不成立：测试空调.空调模式 = "制热"')
      expect(targets.text()).not.toContain('制热')
      expect(targets.text()).toContain('要求永不成立：测试空调.空调模式 = "制冷"')
      expect(wrapper.text()).not.toContain('HvacMode')
      i18n.global.locale.value = 'en'
      await nextTick()
      expect(targets.text()).toContain('Must never hold: 测试空调.HVAC mode = "Cool"')
      expect(wrapper.get('[aria-labelledby="fuzz-findings-title"]').text()).toContain('HVAC mode = "Heat"')
      await wrapper.setProps({ run: { ...frozenRun, modelTokenSourcesByDeviceId: { ac_1: 'CUSTOM' } } })
      expect(targets.text()).toContain('测试空调.HvacMode = "cool"')
      expect(wrapper.get('[aria-labelledby="fuzz-findings-title"]').text()).toContain('HvacMode = "heat"')
    } finally {
      wrapper.unmount()
      i18n.global.locale.value = previousLocale
    }
  })

  it('offers replay and formal verification without exposing automatic fix', async () => {
    const wrapper = mount(FuzzingResultDialog, {
      props: { visible: true, run, boardComparison: 'CHANGED' },
      global: { plugins: [i18n] }
    })

    expect(wrapper.text()).toContain('Candidate counterexample found')
    expect(wrapper.text()).toContain('Less than 1 second')
    expect(wrapper.text()).not.toContain('1s')
    // The surface and its border come from the shared dialog layer's theme tokens. This used to pin
    // `dark:border-slate-700` and `dark:bg-slate-900` — two hue ramps — immediately above a comment
    // explaining why hue ramps must not be pinned.
    expect(wrapper.get('[role="dialog"]').classes()).toEqual(expect.arrayContaining([
      'iot-dialog',
      'iot-dialog--lg'
    ]))
    expect(wrapper.get('[data-testid="fuzzing-result-mode"]').classes()).not.toContain('board-surface-warning')
    expect(wrapper.text()).toContain('7/14/2026')
    expect(wrapper.get('[data-testid="fuzzing-result-mode"]').text()).toContain('Random state and events')
    const modeHelp = wrapper.getComponent(InfoTooltip).props('text')
    expect(modeHelp).toContain('random legal state')
    expect(modeHelp).toContain('rather than the Board’s initial state')
    expect(wrapper.text()).not.toContain('random legal state')
    expect(wrapper.text()).toContain('outside the finite-trace forms supported by the current explorer')
    expect(wrapper.text()).toContain('The search checks only states within the configured path length; it does not replace unbounded temporal verification.')
    expect(wrapper.text()).toContain('Random-state mode capabilities and boundaries')
    expect(wrapper.text()).toContain('Candidate ranking considers the current specification state')
    expect(wrapper.text()).toContain('the remaining steps toward a violation, and how closely the next-step conditions are satisfied')
    expect(wrapper.text()).toContain('Each candidate uses a legal initial state derived from its random seed')
    expect(wrapper.text()).toContain('supports only the Always, Never, and Immediate response structured temporal specification forms')
    expect(wrapper.text()).not.toMatch(/paper subset|Event quadruple|monitor FSM|GetPrevConditions|BFS|NuSMV|HAFuzz/i)
    expect(wrapper.text()).toContain('Numeric ranges use integers; decimal values are not supported.')
    expect(wrapper.text()).toContain('A discrete environment variable without a change-rate range may be set directly by a search input.')
    expect(wrapper.text()).toContain('Formal verification provides a proof or formal counterexample within the model; exploration provides candidate paths.')
    expect(wrapper.text()).toContain('Automation trigger conditions are traced back by up to three levels')
    expect(wrapper.text()).toContain('an additional search limitation whose details are temporarily unavailable')
    expect(wrapper.text()).toContain('First violation: step 4 · seed 42')
    expect(wrapper.text()).toContain('Reproduction settings')
    expect(wrapper.text()).toContain('Explicit targets: 2')
    expect(wrapper.text()).toContain('has not been formally verified. Replay it to investigate, or verify the current model.')
    expect(wrapper.get('[aria-labelledby="fuzz-target-results-title"]').text()).toContain('Window remains closed')
    expect(wrapper.find('[aria-labelledby="fuzz-findings-title"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Formal verification checks every specification on the current board, without loading historical scenes or search inputs.')
    expect(wrapper.text()).toContain('Submitted at')
    expect(wrapper.get('[data-testid="fuzzing-board-drift-warning"]').text()).toContain('differs from the current Board model')
    expect(wrapper.text()).not.toContain('backend-only English diagnostic')
    expect(wrapper.find('[data-testid="fuzz-eligibility-technical-spec-2"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('FINITE_TRACE_TEMPLATES_1_3_4_ONLY')
    expect(wrapper.findAll('button').some(button => /fix rules|automatic fix|^fix$/i.test(button.text()))).toBe(false)

    await wrapper.get('[data-testid="replay-fuzzing-finding-11"]').trigger('click')
    const configureVerification = wrapper.get('[data-testid="verify-fuzzing-finding-11"]')
    expect(configureVerification.text()).toContain('Configure current board verification')
    await configureVerification.trigger('click')
    await wrapper.get('[data-testid="reuse-fuzzing-settings"]').trigger('click')
    expect(wrapper.emitted('replay')).toEqual([[11, 10]])
    expect(wrapper.emitted('verify')?.[0]?.[0]).toMatchObject({ id: 11, violatedSpecId: 'spec-1' })
    expect(wrapper.emitted('reuseSettings')).toHaveLength(1)
  })

  it('offers current-Board verification when the budget finds no candidate', async () => {
    const wrapper = mount(FuzzingResultDialog, {
      props: {
        visible: true,
        run: {
          ...run,
          outcome: 'BUDGET_EXHAUSTED',
          findings: [],
          findingCount: 0
        }
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.text()).toContain('No candidate counterexample was found within this budget. Specification satisfaction remains undetermined.')
    expect(wrapper.find('[aria-labelledby="fuzz-findings-title"]').exists()).toBe(false)
    const formalNotice = wrapper.get('[data-testid="fuzz-formal-verification-current-board-notice"]')
    expect(formalNotice.text()).toContain('Formal verification checks every specification on the current board, without loading historical scenes or search inputs.')
    // Still must not read as success: this notice is not a safety result.
    expect(formalNotice.attributes('class')).not.toMatch(/emerald|green|board-surface-success/)
    const configureVerification = wrapper.get('[data-testid="verify-current-board-without-finding"]')
    expect(configureVerification.text()).toContain('Configure current board verification')
    await configureVerification.trigger('click')
    expect(wrapper.emitted('verifyCurrentBoard')).toHaveLength(1)
  })

  it('uses the frozen specification label when a lightweight finding omits violatedSpec', () => {
    const wrapper = mount(FuzzingResultDialog, {
      props: {
        visible: true,
        run: {
          ...run,
          findings: [{
            ...run.findings[0],
            violatedSpec: undefined,
            specificationLabel: 'Frozen door safety label'
          }]
        }
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.text()).toContain('Frozen door safety label')
    expect(wrapper.text()).not.toContain('Unidentified specification')
  })

  it('traps focus, closes with Escape, restores focus, and ignores backdrop clicks', async () => {
    const opener = document.createElement('button')
    opener.dataset.testid = 'open-fuzzing-panel'
    document.body.appendChild(opener)
    opener.focus()

    const wrapper = mount(FuzzingResultDialog, {
      attachTo: document.body,
      props: { visible: false, run },
      global: { plugins: [i18n] }
    })

    await wrapper.setProps({ visible: true })
    await new Promise(resolve => setTimeout(resolve, 0))
    const close = wrapper.get('[data-testid="close-fuzzing-result"]')
    expect(document.activeElement).toBe(wrapper.get('[role="dialog"]').element)

    await wrapper.get('[data-testid="fuzzing-result-dialog"]').trigger('click')
    expect(wrapper.emitted('close')).toBeUndefined()

    await close.trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toHaveLength(1)
    await wrapper.setProps({ visible: false })
    expect(document.activeElement).toBe(opener)

    wrapper.unmount()
    opener.remove()
  })

  /**
   * `dataAvailable === false` marks a finding whose own detail load the server rejected as corrupt.
   * `TraceHistoryPanel` disables both of its actions for exactly this, and this dialog binds the same
   * run object — so omitting the check left a finding greyed out in history still fully armed here.
   * Replay merely re-failed; "verify formally" was worse, because that handoff is seeded from the
   * summary without re-fetching the finding, so it proceeded on evidence already known bad.
   */
  it('disarms both actions for a finding whose evidence the server rejected', async () => {
    const unavailable = {
      ...run,
      findings: [{ ...run.findings[0], dataAvailable: false }]
    }

    const wrapper = mount(FuzzingResultDialog, {
      props: { visible: true, run: unavailable as typeof run },
      global: { plugins: [i18n] }
    })

    const findingId = run.findings[0].id
    expect(wrapper.get(`[data-testid="replay-fuzzing-finding-${findingId}"]`).attributes('disabled'))
      .toBeDefined()
    expect(wrapper.get(`[data-testid="verify-fuzzing-finding-${findingId}"]`).attributes('disabled'))
      .toBeDefined()

    expect(wrapper.get(`[data-testid="fuzz-finding-unavailable-${findingId}"]`).text()).toContain('incomplete')

    // A usable finding keeps both, or the guard above would be indistinguishable from disabling always.
    const usable = mount(FuzzingResultDialog, {
      props: { visible: true, run },
      global: { plugins: [i18n] }
    })
    expect(usable.get(`[data-testid="replay-fuzzing-finding-${findingId}"]`).attributes('disabled'))
      .toBeUndefined()
    expect(usable.get(`[data-testid="verify-fuzzing-finding-${findingId}"]`).attributes('disabled'))
      .toBeUndefined()
  })
})
