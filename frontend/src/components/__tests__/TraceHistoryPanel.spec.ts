// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { KeepAlive, h, ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { describe, expect, it, vi } from 'vitest'
import TraceHistoryPanel from '../TraceHistoryPanel.vue'
import type { AvailableVerificationRunSummary, TraceSummary, VerificationRunSummary, VerificationTaskSummary } from '@/types/verify'
import type { AvailableSimulationTraceSummary, SimulationTaskSummary, SimulationTraceSummary } from '@/types/simulation'
import type { FuzzingRunSummary, FuzzingTaskSummary } from '@/types/fuzzing'
import type { ModelRunSnapshot } from '@/types/modelSemantics'
import type { Specification } from '@/types/spec'
import actualI18n from '@/assets/i18n'
import InfoTooltip from '@/components/common/InfoTooltip.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      household: actualI18n.global.getLocaleMessage('en').household,
      app: {
        ...actualI18n.global.getLocaleMessage('en').app,
        runHistory: 'Run History',
        runInitiatedByAssistant: 'Started by AI assistant',
        runInitiatorUnknown: 'Source unavailable',
        // Real copy, so assertions read the sentence the user sees rather than a raw key: with
        // these absent, `t()` echoed the key and the tests passed on untranslated output.
        runAssumptionsLabel: 'Run assumptions',
        runAssumptionNoAttack: 'No attack modeled',
        runAssumptionAttackBudget: 'Up to {count} of {total} compromised',
        runAssumptionAttackPoints: '{count} chosen attack points',
        runAssumptionPrivacy: 'Sensitivity propagation tracked',
        historicalFixMayFailIfBoardChanged:
          'This fix was found for the board as it was; it may no longer apply.',
        // Real copy for the same reason as the block above: without it, `t()` echoes the key and intlify
        // logs a missing-key warning that reads exactly like a product defect. It is not — both locales
        // carry this key — but the warning was reported as one, which is the cost of a partial fixture.
        adjustAndRunAgain: 'Adjust settings',
        close: 'Close',
        taskStatusLayer: 'Task Status',
        historyResultsLayer: 'History Results',
        pendingTaskSummary: '{active} active, {unresolved} without a result',
        refresh: 'Refresh',
        loadingTasks: 'Loading tasks',
        noPendingTasks: 'No tasks need attention',
        noPendingTasksHint: 'Completed runs move to history.',
        runningTasks: 'Running Tasks',
        tasksWithoutResults: 'No Result Produced',
        verification: 'Verification',
        fuzzSearch: 'Counterexample Search',
        simulation: 'Simulation',
        taskStatusRunning: 'Running',
        taskStatusFailed: 'Failed',
        taskStatusCancelled: 'Cancelled',
        taskInitializing: 'Initializing',
        progress: 'Progress',
        watchTask: 'Watch',
        cancel: 'Cancel',
        failedTaskNoResult: 'The task failed without a result.',
        cancelledTaskNoResult: 'The task was cancelled without a result.',
        technicalDetails: 'Technical Details',
        dismissTask: 'Dismiss',
        allResults: 'All',
        loadingRunResults: 'Loading history results',
        loadMoreFuzzingResults: 'Load more counterexample results',
        loadingMoreFuzzingResults: 'Loading more counterexample results',
        noRunResults: 'No history results',
        noRunResultsHint: 'Completed results appear here.',
        verificationRunResult: 'Verification Result',
        simulationRunResult: 'Simulation Result',
        fuzzRunResult: 'Counterexample search result',
        fuzzModeBoard: 'Board snapshot',
        fuzzModePaper: 'Random state and events',
        fuzzModeBoardDescription: 'Starts from the frozen Board initial state.',
        fuzzModePaperDescription: 'Starts from a legal random state and searches reproducible inputs; it is not a proof.',
        fuzzViolationFound: 'Candidate violation found',
        fuzzBudgetExhausted: 'Search budget exhausted',
        fuzzInconclusive: 'Search inconclusive',
        fuzzRunCounts: '{iterations} iterations, {paths} paths, {elapsed}',
        fuzzBoardScopeChanged: 'Current Board scope changed.',
        fuzzNoViolationWithinBudget: 'No violation was found within this budget. This is not a safety proof.',
        fuzzFindingsCount: '{count} candidate findings',
        fuzzFirstViolationStep: 'First violation: step {step}',
        verificationPassed: 'Checked specifications passed',
        verificationPassedWithGenerationWarnings: 'Checked specifications satisfied; model incomplete',
        verificationFailedWithViolations: 'Found {count} specification violation(s)',
        verificationInconclusiveSummary: 'Verification inconclusive',
        inconclusiveEvidenceSummary: '{counterexamples} saved counterexample(s) are partial evidence, not a complete verdict.',
        incompleteModel: 'Incomplete model',
        runScopeCounts: '{devices} devices, {rules} rules, {specs} specs',
        simulationHistoryCounts: 'Requested {requested}, produced {steps}, {states} states',
        openResult: 'Open Result',
        delete: 'Delete',
        violationEvidenceSummary: '{violations} violations, {counterexamples} counterexamples',
        someViolationsHaveNoReplayableCounterexample: 'Some violations have no replayable counterexample.',
        unknownSpecification: 'Unknown specification',
        statesCount: '{count} states',
        replay: 'Replay',
        fix: 'Fix',
        fixRules: 'Fix rules',
        unknownModelItem: 'Unknown item',
        unknownOmissionReason: 'Unknown reason',
        generationIssueSpecUnknownDevice: 'The referenced device is unavailable.',
        historyActionsLockedHint: 'Close playback first.',
        historyItemUnavailable: 'History item unavailable',
        historyItemUnavailableDetail: 'This saved result is damaged and cannot be opened.',
        historyTraceUnavailableDetail: 'This counterexample is damaged and cannot be replayed.',
        historyFindingUnavailableDetail: 'This candidate trace is damaged and cannot be used.',
        retry: 'Retry'
      }
    }
  }
})

const snapshot = (specificationCount: number) => ({
  capturedAt: '2026-07-13T10:00:00',
  deviceCount: 4,
  ruleCount: 3,
  specificationCount,
  environmentVariableCount: 0,
  deviceTemplateCount: 4,
  modelFingerprint: 'a'.repeat(64),
  templatesFrozen: true as const
})

const semantics = {
  attackPointUnit: 'BEHAVIOR_CHANGING_DEVICE_INSTANCE_OR_AUTOMATION_LINK' as const,
  attackSelectionPolicy: 'NOT_MODELED' as const,
  attackEffects: [],
  modeledDeviceAttackPointCount: 0,
  modeledFalsifiableReadingDeviceCount: 0,
  modeledAutomationLinkAttackPointCount: 0,
  modeledAttackPointCount: 0,
  trustPropagationPolicy: 'TARGET_UNTRUSTED_IF_ALL_TRIGGER_SOURCES_UNTRUSTED' as const,
  privacyPropagationPolicy: 'NOT_MODELED' as const,
  labelPropagationScope: 'AUTOMATION_RULE_COMMANDS_ONLY' as const,
  environmentEvolutionEffects: [],
  localVariableFallbackPolicy: 'STUTTER_WHEN_NO_DECLARED_EVOLUTION' as const
}

const baseProps = {
  resultFilter: 'all' as const,
  verificationTasks: [] as VerificationTaskSummary[],
  fuzzingTasks: [] as FuzzingTaskSummary[],
  simulationTasks: [] as SimulationTaskSummary[],
  verificationRuns: [] as VerificationRunSummary[],
  fuzzingRuns: [] as FuzzingRunSummary[],
  simulationRuns: [] as SimulationTraceSummary[],
  loadingTasks: false,
  loadingResults: false,
  hasMoreFuzzingRuns: false,
  loadingMoreFuzzingRuns: false,
  actionLocked: false
}

describe('TraceHistoryPanel two-layer semantics', () => {
  it.each(['en', 'zh-CN'] as const)('keeps a passing summary neutral until trigger evidence is opened in %s', locale => {
    const translations = createI18n({ legacy: false, locale, messages: {
      en: actualI18n.global.getLocaleMessage('en'), 'zh-CN': actualI18n.global.getLocaleMessage('zh-CN')
    } })
    const wrapper = mount(TraceHistoryPanel, {
      props: { ...baseProps, activeLayer: 'results', verificationRuns: [runWithSnapshot(31, snapshot(1))] },
      global: { plugins: [translations] }
    })
    try {
      const row = wrapper.get('[data-testid="history-result-verification-31"]')
      expect(row.text()).toContain(locale === 'en' ? 'Checked formulas satisfied' : '已检查公式成立')
      expect(row.text()).toContain(locale === 'en' ? 'Open the result to review response triggers' : '打开结果核对响应触发条件')
      expect(row.find('.board-surface-success').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('keeps existing run details mounted while refreshing their summaries', async () => {
    const wrapper = mount(TraceHistoryPanel, {
      props: { ...baseProps, activeLayer: 'results', verificationRuns: [runWithSnapshot(31, snapshot(1))] },
      global: { plugins: [i18n] }
    })
    try {
      const details = wrapper.get<HTMLDetailsElement>('[data-testid="run-details-verification-31"]')
      details.element.open = true
      await wrapper.setProps({ loadingResults: true })
      expect(wrapper.find('[data-testid="run-details-verification-31"]').exists()).toBe(true)
      await wrapper.setProps({ loadingResults: false })
      expect(wrapper.get('[data-testid="run-details-verification-31"]').element).toBe(details.element)
      expect(details.element.open).toBe(true)
    } finally { wrapper.unmount() }
  })

  it('restores cached scroll and focus when returning from a result', async () => {
    const open = ref(true)
    const refreshing = ref(false)
    const opener = document.createElement('button')
    document.body.append(opener)
    opener.focus()
    const wrapper = mount({ setup: () => () => h(KeepAlive, null, {
      default: () => open.value ? h(TraceHistoryPanel, { ...baseProps, activeLayer: 'results',
        loadingResults: refreshing.value, verificationRuns: [runWithSnapshot(31, snapshot(1))] }) : null
    }) }, { attachTo: document.body, global: { plugins: [i18n] } })
    try {
      await flushPromises()
      const details = wrapper.get<HTMLDetailsElement>('[data-testid="run-details-verification-31"]')
      details.element.open = true
      const body = wrapper.get<HTMLElement>('.history-body')
      body.element.scrollTop = 160
      await body.trigger('scroll')
      open.value = false
      await flushPromises()
      expect(document.activeElement).toBe(opener)
      refreshing.value = true
      open.value = true
      await flushPromises()
      expect(wrapper.get('[data-testid="run-details-verification-31"]').element).toBe(details.element)
      expect(details.element.open).toBe(true)
      expect(body.element.scrollTop).toBe(160)
      expect(document.activeElement).toBe(wrapper.get('[data-testid="trace-history-panel"]').element)
    } finally { wrapper.unmount(); opener.remove() }
  })

  it('starts a different history list at the top without resetting on translation', async () => {
    const wrapper = mount(TraceHistoryPanel, {
      props: { ...baseProps, activeLayer: 'results' }, global: { plugins: [actualI18n] }
    })
    const previousLocale = actualI18n.global.locale.value
    try {
      const body = wrapper.get<HTMLElement>('.history-body').element
      body.scrollTop = 160
      await wrapper.setProps({ resultFilter: 'simulation' })
      expect(body.scrollTop).toBe(0)
      body.scrollTop = 80
      actualI18n.global.locale.value = previousLocale === 'en' ? 'zh-CN' : 'en'
      await flushPromises()
      expect(body.scrollTop).toBe(80)
      await wrapper.setProps({ activeLayer: 'tasks' })
      expect(body.scrollTop).toBe(0)
    } finally { wrapper.unmount(); actualI18n.global.locale.value = previousLocale }
  })

  it('anchors visible history content when the viewport reflows', async () => {
    const wrapper = mount(TraceHistoryPanel, {
      props: { ...baseProps, activeLayer: 'results', verificationRuns: [runWithSnapshot(31, snapshot(1))] },
      global: { plugins: [i18n] }
    })
    try {
      await flushPromises()
      const body = wrapper.get<HTMLElement>('.history-body')
      const line = wrapper.get('.history-card__scope').element
      vi.spyOn(body.element, 'getBoundingClientRect').mockReturnValue({ top: 100, bottom: 300 } as DOMRect)
      const bounds = vi.spyOn(line, 'getBoundingClientRect')
        .mockReturnValue({ top: 140, bottom: 160, height: 20 } as DOMRect)
      body.element.scrollTop = 100
      await body.trigger('scroll')
      bounds.mockReturnValue({ top: 240, bottom: 260, height: 20 } as DOMRect)
      window.dispatchEvent(new Event('resize'))
      expect(body.element.scrollTop).toBe(200)
    } finally { wrapper.unmount() }
  })

  it('names deletion rather than playback while a pending deletion locks history actions', async () => {
    const previousLocale = actualI18n.global.locale.value
    const wrapper = mount(TraceHistoryPanel, {
      props: { ...baseProps, activeLayer: 'results', actionLocked: true,
        pendingResultDeleteKeys: new Set(['verification:31']) },
      global: { plugins: [actualI18n] }
    })
    try {
      for (const language of ['zh-CN', 'en'] as const) {
        actualI18n.global.locale.value = language
        await flushPromises()
        expect(wrapper.get('.history-notice').text()).toContain(actualI18n.global.t('app.historyDeletionInProgress'))
        expect(wrapper.get('.history-notice').text()).not.toContain('app.')
        expect(wrapper.get('.history-notice').text()).not.toContain(actualI18n.global.t('app.historyActionsLockedHint'))
      }
      await wrapper.setProps({ pendingResultDeleteKeys: new Set() })
      expect(wrapper.get('.history-notice').text()).toContain(actualI18n.global.t('app.historyActionsLockedHint'))
      await wrapper.setProps({ actionLocked: false })
      expect(wrapper.find('.history-notice').exists()).toBe(false)
    } finally { wrapper.unmount(); actualI18n.global.locale.value = previousLocale }
  })

  it('warns about retained rows only when their own source failed to refresh', async () => {
    const wrapper = mount(TraceHistoryPanel, {
      props: { ...baseProps, activeLayer: 'results', verificationRuns: [runWithSnapshot(31, snapshot(1))],
        resultErrors: { simulation: { details: ['HTTP 503'], fields: {} } } },
      global: { plugins: [actualI18n] }
    })
    try {
      const staleCopy = actualI18n.global.t('app.historyResultsMayBeStale')
      expect(wrapper.get('[role="alert"]').text()).not.toContain(staleCopy)
      await wrapper.setProps({ resultErrors: { verification: { details: ['HTTP 503'], fields: {} } } })
      expect(wrapper.get('[role="alert"]').text()).toContain(staleCopy)
      await wrapper.setProps({ verificationRuns: [] })
      expect(wrapper.get('[role="alert"]').text()).not.toContain(staleCopy)
      expect(wrapper.find('.history-empty').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it.each(['tasks', 'verification', 'simulation', 'fuzzing'] as const)(
    'keeps %s read failures localized and diagnostics collapsed when the language changes', async source => {
      const previousLocale = actualI18n.global.locale.value
      actualI18n.global.locale.value = 'zh-CN'
      const diagnostic = { cause: 'History storage unavailable', details: ['History storage unavailable', 'HTTP 503'],
        fields: { storage: 'Reconnect the history store' } }
      const wrapper = mount(TraceHistoryPanel, {
        props: { ...baseProps, activeLayer: source === 'tasks' ? 'tasks' : 'results',
          ...(source === 'tasks' ? { taskError: diagnostic } : { resultFilter: source, resultErrors: { [source]: diagnostic } }) },
        global: { plugins: [actualI18n] }
      })
      const key = source === 'tasks' ? 'app.failedToLoadTasks' : {
        verification: 'app.failedToLoadVerificationHistory', simulation: 'app.failedToLoadSimulationHistory',
        fuzzing: 'app.failedToLoadFuzzingHistory'
      }[source]
      try {
        for (const language of ['zh-CN', 'en'] as const) {
          actualI18n.global.locale.value = language
          await flushPromises()
          const alert = wrapper.get('[role="alert"]')
          expect(alert.text()).toContain(actualI18n.global.t(key))
          const disclosure = alert.get('[data-testid="failure-technical-details"]')
          expect(disclosure.attributes('open')).toBeUndefined()
          expect(disclosure.text().match(/History storage unavailable/g)).toHaveLength(1)
          expect(disclosure.text()).toContain('storage: Reconnect the history store')
          expect(disclosure.text()).toContain('HTTP 503')
          expect(alert.find('[data-testid="failure-cause"]').exists()).toBe(false)
          expect(alert.find('[data-testid="failure-fields"]').exists()).toBe(false)
          expect(wrapper.find('.history-empty').exists()).toBe(false)
          expect(wrapper.emitted()).toEqual({})
        }
        await wrapper.get('[role="alert"] button').trigger('click')
        expect(wrapper.emitted(source === 'tasks' ? 'refresh-tasks' : 'refresh-results')).toEqual([[]])
      } finally { wrapper.unmount(); actualI18n.global.locale.value = previousLocale }
    })

  it('distinguishes a pending model comparison from an unavailable one across language changes', async () => {
    const previousLocale = actualI18n.global.locale.value
    actualI18n.global.locale.value = 'zh-CN'
    const wrapper = mount(TraceHistoryPanel, {
      props: { ...baseProps, activeLayer: 'tasks', modelComparisonLoading: true, verificationTasks: [{
        id: 1, initiator: 'USER', status: 'PENDING', progress: 0, createdAt: '2026-07-13T10:00:00', modelSnapshot: snapshot(1)
      }] },
      global: { plugins: [actualI18n] }
    })
    try {
      const comparison = wrapper.get('[data-testid="task-model-comparison-verification-1"]')
      expect(comparison.text()).toContain(actualI18n.global.t('app.historyModelComparing'))
      actualI18n.global.locale.value = 'en'
      await flushPromises()
      expect(comparison.text()).toContain(actualI18n.global.t('app.historyModelComparing'))
      await wrapper.setProps({ modelComparisonLoading: false })
      expect(comparison.text()).toContain(actualI18n.global.t('app.historyModelUnavailable'))
      await wrapper.setProps({ currentModelFingerprints: { verification: 'a'.repeat(64), simulation: 'b'.repeat(64) } })
      expect(comparison.text()).toContain(actualI18n.global.t('app.historyModelUnchanged'))
      expect(wrapper.emitted()).toEqual({})
    } finally { wrapper.unmount(); actualI18n.global.locale.value = previousLocale }
  })

  it('keeps exploration outcomes, duration and contextual help consistent', async () => {
    const locale = 'en'
    const translations = createI18n({ legacy: false, locale, messages: {
      en: actualI18n.global.getLocaleMessage('en'), 'zh-CN': actualI18n.global.getLocaleMessage('zh-CN')
    } })
    const run: FuzzingRunSummary = {
      id: 55, initiator: 'USER', dataAvailable: true, explorationMode: 'BOARD_SNAPSHOT',
      outcome: 'BUDGET_EXHAUSTED', effectiveSeed: 1, iterations: 2, generatedPaths: 4, elapsedMs: 5000,
      modelSnapshot: snapshot(1), eligibility: { eligibleSpecIds: ['s1'], eligibleSpecLabels: { s1: 'Never' },
        ineligibleSpecs: [], requestedSpecCount: 1, eligibleSpecCount: 1 }, eligibleSpecifications: {}, modelTokenSourcesByDeviceId: {},
      limitations: ['TIME_BUDGET_EXHAUSTED'], maxIterations: 100, pathLength: 6, populationSize: 2,
      createdAt: '2026-07-13T11:00:00', completedAt: '2026-07-13T11:00:05', findingCount: 0, findings: []
    }
    const wrapper = mount(TraceHistoryPanel, { attachTo: document.body,
      props: { ...baseProps, activeLayer: 'results', fuzzingRuns: [run] },
      global: { plugins: [translations] } })
    try {
      const badge = () => wrapper.get('[data-testid="history-result-fuzzing-55"] .history-badge')
      expect(badge().text()).toBe(translations.global.t('app.fuzzTimeLimitReached'))
      await wrapper.setProps({ fuzzingRuns: [{ ...run, limitations: [] }] })
      expect(badge().text()).toBe(translations.global.t('app.fuzzBudgetExhausted'))
      await wrapper.setProps({ fuzzingRuns: [{ ...run, outcome: 'FOUND_VIOLATION', findingCount: 1,
        findings: [{ id: 551, fuzzTaskId: 55, violatedSpecId: 's1', specificationLabel: 'Never',
          firstViolationStep: 1, seed: 1, stateCount: 2, createdAt: '2026-07-13T11:00:05' }] }] })
      expect(badge().text()).toBe(translations.global.t('app.fuzzViolationFound'))
      await wrapper.setProps({ fuzzingRuns: [{ ...run, elapsedMs: 120 }] })
      expect(wrapper.text()).toContain(translations.global.t('app.fuzzElapsedUnderSecond'))
      for (const [id, text] of [
        ['fuzzing-history-mode-help-55', translations.global.t('app.fuzzModeBoardDescription')],
        ['fuzzing-history-model-help-55', translations.global.t('app.runBoardComparisonUnavailable')]
      ]) {
        const help = wrapper.get<HTMLButtonElement>(`[data-testid="${id}"]`)
        help.element.focus()
        expect(document.activeElement).toBe(help.element)
        await help.trigger('click')
        await vi.waitFor(() => {
          const visible = [...document.querySelectorAll<HTMLElement>('.iot-info-tooltip-popper')]
            .filter(element => element.getAttribute('aria-hidden') !== 'true' && element.style.display !== 'none')
          expect(visible.some(element => element.textContent?.includes(text))).toBe(true)
        })
        await help.trigger('keydown', { key: 'Escape' })
        expect(wrapper.emitted('close')).toBeUndefined()
      }
    } finally { wrapper.unmount() }
  })

  it('keeps empty results specific to the selected filter when language changes', async () => {
    const locale = 'zh-CN'
    const previousLocale = actualI18n.global.locale.value
    actualI18n.global.locale.value = locale
    const wrapper = mount(TraceHistoryPanel, {
      props: { ...baseProps, activeLayer: 'results', resultFilter: 'fuzzing' },
      global: { plugins: [actualI18n] }
    })
    try {
      expect(wrapper.get('.history-empty .font-semibold').text()).toBe(actualI18n.global.t('app.noFilteredRunResults', {
        kind: actualI18n.global.t('app.fuzzSearch')
      }))
      actualI18n.global.locale.value = 'en'
      await flushPromises()
      expect(wrapper.get('.history-empty .font-semibold').text()).toBe(actualI18n.global.t('app.noFilteredRunResults', {
        kind: actualI18n.global.t('app.fuzzSearch')
      }))
      await wrapper.setProps({ resultFilter: 'all' })
      expect(wrapper.get('.history-empty .font-semibold').text()).toBe(actualI18n.global.t('app.noRunResults'))
      await wrapper.setProps({ loadingResults: true })
      expect(wrapper.get('.history-empty p').text()).toBe(actualI18n.global.t('app.loadingRunResults'))
      expect(wrapper.text()).not.toContain(actualI18n.global.t('app.noRunResults'))
      await wrapper.setProps({ loadingResults: false })
      expect(wrapper.get('.history-empty .font-semibold').text()).toBe(actualI18n.global.t('app.noRunResults'))
    } finally { wrapper.unmount(); actualI18n.global.locale.value = previousLocale }
  })

  it('offers all results when another run type has saved history', async () => {
    const wrapper = mount(TraceHistoryPanel, {
      props: { ...baseProps, activeLayer: 'results', resultFilter: 'fuzzing', verificationRuns: [{
        id: 99, initiator: 'UNKNOWN', createdAt: '2026-07-13T10:00:00',
        counterexampleCount: 0, counterexamples: [], dataAvailable: false,
        unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID'
      }] },
      global: { plugins: [actualI18n] }
    })
    try {
      const action = wrapper.get('.history-empty button')
      expect(action.text()).toBe(actualI18n.global.t('app.showAllResults'))
      await action.trigger('click')
      expect(wrapper.emitted('update:resultFilter')).toEqual([['all']])
      await wrapper.setProps({ resultFilter: 'all' })
      expect(wrapper.find('.history-empty').exists()).toBe(false)
      expect(wrapper.find('[data-testid="history-result-verification-99"]').exists()).toBe(true)
      await wrapper.setProps({ verificationRuns: [], resultFilter: 'fuzzing' })
      expect(wrapper.find('.history-empty button').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })
  it('offers retry after an empty inbox read failed without claiming there are no pending tasks', async () => {
    const wrapper = mount(TraceHistoryPanel, {
      props: { ...baseProps, activeLayer: 'tasks', taskError: {
        cause: 'Request timed out', details: [], fields: {}
      } },
      global: { plugins: [i18n] }
    })
    const alert = wrapper.get('[data-testid="history-tasks-load-error"]')
    expect(alert.attributes('role')).toBe('alert')
    expect(alert.text()).toContain('Request timed out')
    expect(wrapper.text()).not.toContain('No tasks need attention')
    await alert.get('button').trigger('click')
    expect(wrapper.emitted('refresh-tasks')).toEqual([[]])
    await wrapper.setProps({ loadingTasks: true })
    expect(wrapper.get('[data-testid="history-tasks-load-error"] button').attributes('disabled')).toBeDefined()
    await wrapper.setProps({ loadingTasks: false, taskError: null })
    expect(wrapper.find('[data-testid="history-tasks-load-error"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('No tasks need attention')
  })

  it('keeps known tasks visible with an unrefreshed warning until recovery supplies current rows', async () => {
    const wrapper = mount(TraceHistoryPanel, {
      props: { ...baseProps, activeLayer: 'tasks', taskError: {
        cause: 'Request timed out', details: [], fields: {}
      },
        simulationTasks: [{ id: 7, initiator: 'USER', status: 'RUNNING', steps: 2,
          isAttack: false, attackBudget: 0, progress: 10, createdAt: '2026-08-02T10:00:00',
          modelSnapshot: snapshot(0) }] },
      global: { plugins: [i18n] }
    })
    expect(wrapper.get('[data-testid="history-task-simulation-7"]').text()).toContain('Running')
    expect(wrapper.get('[data-testid="history-tasks-load-error"]').text()).toContain('could be out of date')
    await wrapper.setProps({ taskError: null, simulationTasks: [] })
    expect(wrapper.find('[data-testid="history-tasks-load-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="history-task-simulation-7"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('No tasks need attention')
  })

  it('shows an English cause and field diagnostics under Chinese failure copy without claiming an empty inbox', () => {
    const chinese = createI18n({ legacy: false, locale: 'zh-CN', messages: {
      'zh-CN': actualI18n.global.getLocaleMessage('zh-CN')
    } })
    const wrapper = mount(TraceHistoryPanel, {
      props: { ...baseProps, activeLayer: 'tasks', taskError: {
        cause: 'Task storage could not be read',
        details: ['HTTP 503'], fields: { tasks: 'Database query timed out' }
      } },
      global: { plugins: [chinese] }
    })
    const alert = wrapper.get('[data-testid="history-tasks-load-error"]')
    expect(alert.text()).toContain('加载任务失败')
    expect(alert.get('[data-testid="failure-technical-details"]').text()).toContain('Task storage could not be read')
    expect(alert.get('[data-testid="failure-technical-details"]').text()).toContain('tasks: Database query timed out')
    expect(alert.get('details').attributes('open')).toBeUndefined()
    expect(wrapper.text()).not.toContain(actualI18n.global.getLocaleMessage('zh-CN').app.noPendingTasks)
  })

  it('renders frozen conditions and token provenance in both exploration and formal history', () => {
    const spec: Specification = { name: 'Safety requirement', id: 'spec-1', templateId: '3', templateLabel: 'Never',
      aConditions: [{ id: 'c1', side: 'a', deviceId: 'ac_1', deviceLabel: '历史空调',
        targetType: 'mode', key: 'HvacMode', relation: '=', value: 'heat' }],
      ifConditions: [], thenConditions: [] }
    const verificationRun: VerificationRunSummary = { ...runWithSnapshot(31, snapshot(1)),
      outcome: 'VIOLATED', violatedSpecCount: 1, counterexampleCount: 1,
      counterexamples: [{ id: 310, verificationTaskId: 31, violatedSpecId: spec.id, violatedSpec: spec,
        stateCount: 2, createdAt: '2026-07-13T11:00:01', dataAvailable: true,
        modelTokenSourcesByDeviceId: { ac_1: 'BUNDLED' } }] }
    const fuzzRun: FuzzingRunSummary = { id: 40, initiator: 'USER', explorationMode: 'BOARD_SNAPSHOT',
      outcome: 'FOUND_VIOLATION', effectiveSeed: 42, iterations: 1, generatedPaths: 1, elapsedMs: 10,
      modelSnapshot: { ...snapshot(1), deviceCount: 1, deviceTemplateCount: 1, modelFingerprint: 'a'.repeat(64) },
      eligibility: { eligibleSpecIds: [spec.id], eligibleSpecLabels: { [spec.id]: 'Never' },
        ineligibleSpecs: [], requestedSpecCount: 1, eligibleSpecCount: 1 },
      eligibleSpecifications: { [spec.id]: spec }, modelTokenSourcesByDeviceId: { ac_1: 'BUNDLED' },
      limitations: [], maxIterations: 10, pathLength: 2, populationSize: 1,
      createdAt: '2026-07-13T11:00:00', completedAt: '2026-07-13T11:00:01', findingCount: 1, dataAvailable: true,
      findings: [{ id: 401, fuzzTaskId: 40, violatedSpecId: spec.id, violatedSpec: spec, specificationLabel: 'Never',
        firstViolationStep: 1, seed: 42, stateCount: 2, createdAt: '2026-07-13T11:00:01' }] }
    const previousLocale = actualI18n.global.locale.value
    actualI18n.global.locale.value = 'zh-CN'
    const wrapper = mount(TraceHistoryPanel, { props: { ...baseProps, activeLayer: 'results',
      verificationRuns: [verificationRun], fuzzingRuns: [fuzzRun] }, global: { plugins: [actualI18n] } })
    const titles = wrapper.findAll('p[title]').map(item => item.attributes('title'))
    expect(titles.filter(title => title === '要求永不成立：历史空调.空调模式 = "制热"')).toHaveLength(2)
    expect(wrapper.text()).not.toContain('HvacMode')
    wrapper.unmount()
    actualI18n.global.locale.value = previousLocale
  })

  it('focuses its labelled region without triggering a close hint and closes on Escape', async () => {
    const wrapper = mount(TraceHistoryPanel, {
      attachTo: document.body,
      props: { ...baseProps, activeLayer: 'tasks' },
      global: { plugins: [i18n] }
    })

    const panel = wrapper.get('[data-testid="trace-history-panel"]')
    // Non-modal tool panel: the canvas behind it stays interactive, so it is a region
    // rather than a dialog.
    expect(panel.attributes('role')).toBe('region')
    expect(panel.attributes('aria-modal')).toBeUndefined()
    expect(panel.attributes('aria-labelledby')).toBe('trace-history-title')
    expect(wrapper.get('[data-testid="history-layer-tasks"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-testid="history-layer-results"]').attributes('aria-pressed')).toBe('false')
    await wrapper.vm.$nextTick()
    expect(document.activeElement).toBe(panel.element)

    await panel.trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('keeps simulation omissions visible without a redundant complete-model badge', () => {
    const normal: AvailableSimulationTraceSummary = { id: 91, initiator: 'USER', dataAvailable: true,
      modelComplete: true, disabledRuleCount: 0, generationIssues: [], requestedSteps: 2, steps: 2,
      isAttack: false, attackBudget: 0, enablePrivacy: false, createdAt: '2026-07-13T10:00:00',
      modelSnapshot: { ...snapshot(0), ruleCount: 0 } }
    const incomplete: AvailableSimulationTraceSummary = { ...normal, id: 92, modelComplete: false,
      disabledRuleCount: 1, modelSnapshot: { ...snapshot(0), ruleCount: 1 }, generationIssues: [{
        issueType: 'RULE_DISABLED', itemLabel: 'Missing device', reasonCode: 'RULE_UNRESOLVABLE_TRIGGER_CONDITION',
        reason: 'The trigger references an unavailable device.'
      }] }
    const wrapper = mount(TraceHistoryPanel, { props: { ...baseProps, activeLayer: 'results', simulationRuns: [normal, incomplete] },
      global: { plugins: [i18n] } })
    try {
      const complete = wrapper.get('[data-testid="history-result-simulation-91"]')
      expect(complete.text()).not.toContain('All rules included')
      expect(complete.text()).not.toContain('Incomplete model')
      expect(complete.text()).toContain('Requested 2, produced 2, 3 states')
      expect(complete.get('[data-testid="replay-simulation-trace-91"]').attributes('disabled')).toBeUndefined()
      expect(wrapper.get('[data-testid="history-result-simulation-92"] .history-badge').text()).toBe('Incomplete model')
    } finally { wrapper.unmount() }
  })

  it('exposes selected result filters to assistive technology', async () => {
    const wrapper = mount(TraceHistoryPanel, {
      props: { ...baseProps, activeLayer: 'results', resultFilter: 'fuzzing' },
      global: { plugins: [i18n] }
    })

    expect(wrapper.get('[data-testid="history-layer-results"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-testid="history-result-filter-fuzzing"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-testid="history-result-filter-all"]').attributes('aria-pressed')).toBe('false')
    const compactFilter = wrapper.get<HTMLSelectElement>('[data-testid="history-result-type"]')
    expect(compactFilter.attributes('aria-label')).toBe('Run type')
    expect(compactFilter.element.value).toBe('fuzzing')
    await compactFilter.setValue('simulation')
    expect(wrapper.emitted('update:resultFilter')?.at(-1)).toEqual(['simulation'])

    await wrapper.setProps({ resultFilter: 'all' })
    expect(compactFilter.element.value).toBe('all')
    expect(wrapper.get('[data-testid="history-result-filter-fuzzing"]').attributes('aria-pressed')).toBe('false')
    expect(wrapper.get('[data-testid="history-result-filter-all"]').attributes('aria-pressed')).toBe('true')
  })

  it('keeps completed work out of the task-status layer', () => {
    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'tasks',
        verificationTasks: [
          {
            id: 1,
            initiator: 'AI_ASSISTANT',
            status: 'RUNNING',
            createdAt: '2026-07-13T10:00:00',
            startedAt: '2026-07-13T10:00:01',
            progress: 40,
            isAttack: false,
            attackBudget: 0,
            enablePrivacy: false,
            modelSemantics: semantics,
            modelSnapshot: snapshot(1)
          },
          {
            id: 2,
            initiator: 'USER',
            status: 'COMPLETED',
            createdAt: '2026-07-13T09:00:00',
            startedAt: '2026-07-13T09:00:01',
            completedAt: '2026-07-13T09:00:02',
            progress: 100,
            isAttack: false,
            attackBudget: 0,
            enablePrivacy: false,
            modelSemantics: semantics,
            modelSnapshot: snapshot(1),
            outcome: 'SATISFIED',
            modelComplete: true,
            violatedSpecCount: 0,
            disabledRuleCount: 0,
            skippedSpecCount: 0,
            generationIssues: []
          }
        ] as VerificationTaskSummary[],
        simulationTasks: [{
          id: 3,
          initiator: 'UNKNOWN',
          status: 'FAILED',
          createdAt: '2026-07-13T08:00:00',
          completedAt: '2026-07-13T08:00:02',
          progress: 100,
          errorMessage: 'NuSMV could not start',
          requestedSteps: 5,
          isAttack: false,
          attackBudget: 0,
          enablePrivacy: false,
          modelSemantics: semantics,
          modelSnapshot: snapshot(0)
        }] as SimulationTaskSummary[],
        pendingTaskActionKeys: new Set([
          'cancel:verification:1',
          'dismiss:simulation:3'
        ])
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.text()).toContain('1 active, 1 without a result')
    expect(wrapper.text()).toContain('The task failed without a result.')
    // The reported cause must be visible, not folded into a collapsed disclosure. It previously sat
    // inside `<details>`; `wrapper.text()` includes collapsed content, so this file asserted the
    // message was "shown" while a real browser showed only the summary — two reviews of an actual
    // failed run reported the cause as missing. Assert on the element that renders it.
    const failureReason = wrapper.get('[data-testid="task-failure-reason-simulation-3"]')
    expect(failureReason.text()).toContain('NuSMV could not start')
    expect(wrapper.find('details').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Checked specifications passed')
    expect(wrapper.findAll('button').some(button => button.text().includes('Dismiss'))).toBe(true)
    const cancelButton = wrapper.findAll('button').find(button => button.text().includes('Cancel'))!
    const dismissButton = wrapper.findAll('button').find(button => button.text().includes('Dismiss'))!
    expect(cancelButton.attributes('disabled')).toBeDefined()
    expect(cancelButton.attributes('aria-busy')).toBe('true')
    expect(dismissButton.attributes('disabled')).toBeDefined()
    expect(dismissButton.attributes('aria-busy')).toBe('true')
    const progressbar = wrapper.get('[role="progressbar"]')
    expect(progressbar.attributes('aria-label')).toBe('Verification Progress')
    expect(progressbar.attributes('aria-valuenow')).toBe('40')
    expect(wrapper.text()).toContain('7/13/2026')
    expect(wrapper.text()).toContain('Started by AI assistant')
    expect(wrapper.text()).toContain('Source unavailable')
  })

  // A failed run must offer a way forward. Dismiss only hides it, so without this the user's only
  // options were to hide the failure or reconstruct the settings from memory.
  it('offers a route back to the launching panel for a failed run', async () => {
    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'tasks',
        fuzzingTasks: [{
          id: 12,
          initiator: 'USER' as const,
          status: 'FAILED' as const,
          progress: 40,
          createdAt: '2026-08-02T10:00:00',
          startedAt: '2026-08-02T10:00:01',
          completedAt: '2026-08-02T10:00:09',
          errorMessage: 'The counterexample search stopped before the task completed',
          explorationMode: 'BOARD_SNAPSHOT' as const,
          modelSnapshot: snapshot(1),
          maxIterations: 40,
          pathLength: 6,
          populationSize: 4,
          targetSpecIds: []
        }]
      },
      global: { plugins: [i18n] }
    })

    // The cause is stated outright rather than folded away.
    expect(wrapper.get('[data-testid="task-failure-reason-fuzzing-12"]').text())
      .toContain('stopped before the task completed')

    await wrapper.get('[data-testid="reopen-task-settings-fuzzing-12"]').trigger('click')
    // Emits the kind, not the stale request: the owning panel re-validates against the current board.
    expect(wrapper.emitted('reopen-task-settings')).toEqual([['fuzzing']])
  })

  // A running task that has not reported progress yet must read 0, not full. The bar is rendered
  // only for PENDING/RUNNING tasks, so there is no honest reading of "complete" available here.
  it('reports zero progress for a running task that has not reported any yet', () => {
    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'tasks',
        verificationTasks: [
          {
            id: 9,
            initiator: 'USER' as const,
            status: 'RUNNING' as const,
            createdAt: '2026-08-02T10:00:00',
            startedAt: '2026-08-02T10:00:01',
            isAttack: false,
            attackBudget: 0,
            enablePrivacy: false,
            modelSemantics: semantics,
            modelSnapshot: snapshot(1)
          }
        ]
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBe('0')
    expect(wrapper.text()).not.toContain('100%')
  })

  it('distinguishes what each passing run actually covered, and warns before a historical fix', () => {
    // Two green runs with identical counts used to render identical rows: attack modeling off and
    // exhaustive compromise up to a budget are very different safety claims.
    const base = {
      initiator: 'USER' as const,
      createdAt: '2026-07-13T10:00:00',
      startedAt: '2026-07-13T10:00:00',
      completedAt: '2026-07-13T10:00:02',
      modelSnapshot: snapshot(2),
      outcome: 'SATISFIED' as const,
      modelComplete: true,
      violatedSpecCount: 0,
      counterexampleCount: 0,
      disabledRuleCount: 0,
      skippedSpecCount: 0,
      generationIssues: [],
      dataAvailable: true as const,
      counterexamples: []
    }
    const noAttack: VerificationRunSummary = {
      ...base, id: 11, isAttack: false, attackBudget: 0, enablePrivacy: false,
      modelSemantics: semantics
    }
    const budgeted: VerificationRunSummary = {
      ...base, id: 12, isAttack: true, attackBudget: 2, enablePrivacy: true,
      modelSemantics: {
        ...semantics,
        attackSelectionPolicy: 'UP_TO_ATTACK_BUDGET_NONDETERMINISTIC',
        modeledAttackPointCount: 5
      }
    }
    // An exact-points run arrives with attackBudget == points.size(), so only the policy
    // distinguishes "these two points" from "any two of five".
    const pinned: VerificationRunSummary = {
      ...base, id: 13, isAttack: true, attackBudget: 2, enablePrivacy: false,
      modelSemantics: {
        ...semantics,
        attackSelectionPolicy: 'EXACT_ATTACK_POINTS',
        modeledAttackPointCount: 2
      }
    }

    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'results',
        verificationRuns: [noAttack, budgeted, pinned]
      },
      global: { plugins: [i18n] }
    })

    const plain = wrapper.get('[data-testid="verification-run-assumptions-11"]')
    expect(plain.text()).toContain('No attack modeled')
    expect(plain.attributes('aria-label')).toBe('Run assumptions')

    const attacked = wrapper.get('[data-testid="verification-run-assumptions-12"]')
    expect(attacked.text()).toContain('Up to 2 of 5 compromised')
    expect(attacked.text()).toContain('Sensitivity propagation tracked')

    const exact = wrapper.get('[data-testid="verification-run-assumptions-13"]')
    expect(exact.text()).toContain('2 chosen attack points')
    // Must not claim an exhaustive search the run never performed.
    expect(exact.text()).not.toContain('Up to')

    // The rows must no longer read identically.
    expect(new Set([plain.text(), attacked.text(), exact.text()]).size).toBe(3)
    wrapper.unmount()
  })

  const runWithSnapshot = (
    id: number,
    modelSnapshot: ModelRunSnapshot
  ): AvailableVerificationRunSummary => ({
    id,
    initiator: 'USER',
    createdAt: '2026-07-13T10:00:00',
    startedAt: '2026-07-13T10:00:00',
    completedAt: '2026-07-13T10:00:02',
    isAttack: false,
    attackBudget: 0,
    enablePrivacy: false,
    modelSemantics: semantics,
    modelSnapshot,
    outcome: 'SATISFIED',
    modelComplete: true,
    violatedSpecCount: 0,
    counterexampleCount: 0,
    disabledRuleCount: 0,
    skippedSpecCount: 0,
    generationIssues: [],
    dataAvailable: true,
    counterexamples: []
  })

  const currentModels = { verification: 'a'.repeat(64), simulation: 'a'.repeat(64) }

  it('warns during execution without turning a changed model into cancellation', () => {
    const task: VerificationTaskSummary = { id: 61, initiator: 'USER', status: 'RUNNING',
      createdAt: '2026-07-13T10:00:00', progress: 25, progressStage: 'EXECUTING_MODEL_CHECKER',
      isAttack: false, attackBudget: 0, enablePrivacy: false, modelSemantics: semantics,
      modelSnapshot: { ...snapshot(2), modelFingerprint: 'b'.repeat(64) } }
    const wrapper = mount(TraceHistoryPanel, {
      props: { ...baseProps, activeLayer: 'tasks', verificationTasks: [task], currentModelFingerprints: currentModels },
      global: { plugins: [i18n] }
    })
    expect(wrapper.get('[data-testid="task-model-comparison-verification-61"]').attributes('data-state')).toBe('CHANGED')
    expect(wrapper.text()).toContain('continues with its submitted snapshot')
    expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBe('25')
    expect(wrapper.text()).toContain('Running')
    wrapper.unmount()
  })

  it.each([
    ['UNCHANGED', 'a'.repeat(64), currentModels, false, 'it may no longer apply'],
    ['CHANGED', 'b'.repeat(64), currentModels, false, 'Results apply to the run snapshot'],
    ['UNAVAILABLE', 'a'.repeat(64), null, false, 'could not be read'],
    ['UNAVAILABLE', 'a'.repeat(64), null, true, 'Comparing the current board'],
    ['NOT_COMPARED', null, currentModels, false, 'lacks model comparison data']
  ] as const)('allows historical Fix only with an %s match and keeps replay available', async (comparison, fingerprint, current, loading, detail) => {
    const run: VerificationRunSummary = {
      id: 30,
      initiator: 'USER',
      createdAt: '2026-07-13T10:00:00',
      startedAt: '2026-07-13T10:00:00',
      completedAt: '2026-07-13T10:00:02',
      isAttack: false,
      attackBudget: 0,
      enablePrivacy: false,
      modelSemantics: semantics,
      modelSnapshot: { ...snapshot(2), modelFingerprint: fingerprint },
      outcome: 'VIOLATED',
      modelComplete: true,
      violatedSpecCount: 1,
      counterexampleCount: 1,
      disabledRuleCount: 0,
      skippedSpecCount: 0,
      generationIssues: [],
      dataAvailable: true,
      counterexamples: []
    }
    run.counterexamples = [{
      id: 41,
      verificationTaskId: 30,
      violatedSpecId: 'spec_1',
      stateCount: 3,
      createdAt: '2026-07-13T10:00:02',
      dataAvailable: true
    } as TraceSummary]

    const wrapper = mount(TraceHistoryPanel, {
      props: { ...baseProps, activeLayer: 'results', verificationRuns: [run],
        currentModelFingerprints: current, modelComparisonLoading: loading },
      global: { plugins: [i18n] }
    })

    const caveat = wrapper.get('[data-testid="historical-fix-caveat-30"]')
    expect(caveat.text()).toContain(detail)
    const fix = wrapper.get('[data-testid="fix-verification-trace-41"]')
    expect(fix.attributes('aria-describedby')).toBe('historical-fix-caveat-30')
    expect((fix.element as HTMLButtonElement).disabled).toBe(comparison !== 'UNCHANGED')
    expect((wrapper.get('[data-testid="view-verification-trace-41"]').element as HTMLButtonElement).disabled).toBe(false)
    await fix.trigger('click')
    expect(wrapper.emitted('fix-verification-trace')?.length ?? 0).toBe(comparison === 'UNCHANGED' ? 1 : 0)
    wrapper.unmount()
  })

  it('groups counterexamples under one verification result and distinguishes violations from replayable evidence', () => {
    const run: VerificationRunSummary = {
      id: 11,
      initiator: 'USER',
      createdAt: '2026-07-13T10:00:00',
      startedAt: '2026-07-13T10:00:00',
      completedAt: '2026-07-13T10:00:02',
      isAttack: false,
      attackBudget: 0,
      enablePrivacy: false,
      modelSemantics: semantics,
      modelSnapshot: snapshot(2),
      outcome: 'VIOLATED',
      modelComplete: true,
      violatedSpecCount: 2,
      counterexampleCount: 1,
      disabledRuleCount: 0,
      skippedSpecCount: 0,
      generationIssues: [],
      dataAvailable: true,
      counterexamples: []
    }
    const trace = {
      id: 21,
      verificationTaskId: 11,
      violatedSpecId: 'spec_1',
      stateCount: 1,
      createdAt: '2026-07-13T10:00:02',
      dataAvailable: true
    } as TraceSummary
    run.counterexamples = [trace]

    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'results',
        verificationRuns: [run]
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.text()).toContain('Found 2 specification violation(s)')
    expect(wrapper.text()).toContain('1 counterexample')
    expect(wrapper.text()).toContain('Some violations have no replayable counterexample.')
    expect(wrapper.findAll('button').some(button => button.text() === 'Replay')).toBe(true)
    expect(wrapper.findAll('button').some(button => button.text() === 'Fix rules')).toBe(true)
  })

  /**
   * The zero-counterexample case, which is where the explanation is most needed and was unreachable.
   *
   * The sibling test above covers 2 violations / 1 counterexample. At 2 / **0** the whole evidence block
   * was gated on the trace list being non-empty, so the run showed its violation count with nothing
   * saying why none could be replayed — while the sentence written for exactly that
   * (`counterexampleCount < violatedSpecCount`, true at zero) sat inside the hidden block.
   *
   * Reachable from the backend: `VerificationServiceImpl` logs "violated (no counterexample)" when
   * NuSMV returns none, and skips the trace when the parsed state list comes back empty — both count the
   * specification as violated.
   */
  it('explains a violated run that produced no replayable counterexample', () => {
    const run: VerificationRunSummary = {
      id: 15,
      initiator: 'USER',
      createdAt: '2026-07-13T10:00:00',
      startedAt: '2026-07-13T10:00:00',
      completedAt: '2026-07-13T10:00:02',
      isAttack: false,
      attackBudget: 0,
      enablePrivacy: false,
      modelSemantics: semantics,
      modelSnapshot: snapshot(2),
      outcome: 'VIOLATED',
      modelComplete: true,
      violatedSpecCount: 2,
      counterexampleCount: 0,
      disabledRuleCount: 0,
      skippedSpecCount: 0,
      generationIssues: [],
      dataAvailable: true,
      counterexamples: []
    }

    const wrapper = mount(TraceHistoryPanel, {
      props: { ...baseProps, activeLayer: 'results', verificationRuns: [run] },
      global: { plugins: [i18n] }
    })

    expect(wrapper.text(), 'the violation count must still be stated')
      .toContain('Found 2 specification violation(s)')
    expect(wrapper.text()).toContain('0 counterexamples')
    expect(wrapper.text(), 'and the reason nothing is replayable must be stated too')
      .toContain('Some violations have no replayable counterexample.')
    expect(
      wrapper.findAll('button').some(button => button.text() === 'Replay'),
      'with no trace there is nothing to replay'
    ).toBe(false)
  })

  it('keeps replayable counterexamples visible when the overall verification is inconclusive', () => {
    const run: VerificationRunSummary = {
      id: 14,
      initiator: 'USER',
      createdAt: '2026-07-13T10:00:00',
      startedAt: '2026-07-13T10:00:00',
      completedAt: '2026-07-13T10:00:02',
      isAttack: false,
      attackBudget: 0,
      enablePrivacy: false,
      modelSemantics: semantics,
      modelSnapshot: snapshot(2),
      outcome: 'INCONCLUSIVE',
      modelComplete: false,
      violatedSpecCount: 1,
      counterexampleCount: 1,
      disabledRuleCount: 0,
      skippedSpecCount: 1,
      generationIssues: [{
        issueType: 'SPECIFICATION_SKIPPED',
        itemLabel: 'Missing device',
        reasonCode: 'SPEC_UNKNOWN_DEVICE',
        reason: 'The referenced device is unavailable.'
      }],
      dataAvailable: true,
      counterexamples: [{
        id: 22,
        verificationTaskId: 14,
        violatedSpecId: 'spec_1',
        stateCount: 2,
        createdAt: '2026-07-13T10:00:02',
        dataAvailable: true
      } as TraceSummary]
    }

    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'results',
        verificationRuns: [run]
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.text()).toContain('Verification inconclusive')
    expect(wrapper.text()).toContain('1 saved counterexample(s) are partial evidence')
    expect(wrapper.find('[data-testid="view-verification-trace-22"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="fix-verification-trace-22"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('1 violations, 1 counterexamples')
  })

  it('shows the selected exploration mode on active fuzz tasks', () => {
    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'tasks',
        fuzzingTasks: [{
          id: 7,
          initiator: 'AI_ASSISTANT',
          explorationMode: 'PAPER_COMPATIBLE',
          status: 'RUNNING',
          progress: 25,
          createdAt: '2026-07-13T10:00:00',
          modelSnapshot: snapshot(1),
          maxIterations: 500,
          pathLength: 20,
          populationSize: 10,
          targetSpecIds: ['spec-1']
        }]
      },
      global: { plugins: [i18n] }
    })

    const badge = wrapper.get('[data-testid="fuzzing-task-mode-7"]')
    expect(badge.text()).toContain('Random state and events')
    expect(badge.getComponent(InfoTooltip).props('text')).toContain('legal random state')
    expect(badge.attributes('title')).toBeUndefined()
  })

  it('renders localized omission copy instead of the backend technical diagnostic', () => {
    const run: VerificationRunSummary = {
      id: 12,
      initiator: 'USER',
      createdAt: '2026-07-13T10:00:00',
      startedAt: '2026-07-13T10:00:01',
      completedAt: '2026-07-13T10:00:02',
      isAttack: false,
      attackBudget: 0,
      enablePrivacy: false,
      modelSemantics: semantics,
      modelSnapshot: snapshot(1),
      outcome: 'INCONCLUSIVE',
      modelComplete: false,
      violatedSpecCount: 0,
      counterexampleCount: 0,
      disabledRuleCount: 0,
      skippedSpecCount: 1,
      generationIssues: [{
        issueType: 'SPECIFICATION_SKIPPED',
        itemLabel: 'Keep camera off',
        reasonCode: 'SPEC_UNKNOWN_DEVICE',
        reason: "device 'camera_7' not found in deviceSmvMap"
      }],
      dataAvailable: true,
      counterexamples: []
    }

    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'results',
        verificationRuns: [run]
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.text()).toContain('The referenced device is unavailable.')
    expect(wrapper.text()).not.toContain('deviceSmvMap')
  })

  it('keeps an unavailable history item visible but exposes only deletion', () => {
    const unavailableRun: VerificationRunSummary = {
      id: 99,
      initiator: 'UNKNOWN',
      createdAt: '2026-07-13T10:00:00',
      completedAt: '2026-07-13T10:00:02',
      counterexampleCount: 0,
      counterexamples: [],
      dataAvailable: false,
      unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID'
    }

    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'results',
        verificationRuns: [unavailableRun]
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.text()).toContain('History item unavailable')
    expect(wrapper.text()).toContain('This saved result is damaged and cannot be opened.')
    expect(wrapper.get('[data-testid="delete-verification-run-99"]').attributes('aria-label')).toBe('Delete')
    expect(wrapper.findAll('button').some(button => button.text() === 'Open Result')).toBe(false)
    expect(wrapper.findAll('button').some(button => button.text() === 'Replay')).toBe(false)
  })

  it('disables a result delete action while that exact deletion is pending', async () => {
    const unavailableRun: VerificationRunSummary = {
      id: 99,
      initiator: 'USER',
      createdAt: '2026-07-13T10:00:00',
      completedAt: '2026-07-13T10:00:02',
      counterexampleCount: 0,
      counterexamples: [],
      dataAvailable: false,
      unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID'
    }
    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'results',
        verificationRuns: [unavailableRun],
        pendingResultDeleteKeys: new Set(['verification:99'])
      },
      global: { plugins: [i18n] }
    })

    const deleteButton = wrapper.get('[data-testid="delete-verification-run-99"]')
    expect(deleteButton.attributes('disabled')).toBeDefined()
    expect(deleteButton.attributes('aria-busy')).toBe('true')
    await deleteButton.trigger('click')
    expect(wrapper.emitted('delete-verification-run')).toBeUndefined()
  })

  it('keeps an unavailable fuzz run deletable without exposing result or finding actions', () => {
    const unavailableRun: FuzzingRunSummary = {
      id: 100,
      initiator: 'AI_ASSISTANT',
      createdAt: '2026-07-13T10:00:00',
      completedAt: '2026-07-13T10:00:02',
      findings: [],
      dataAvailable: false,
      unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID'
    }

    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'results',
        resultFilter: 'fuzzing',
        fuzzingRuns: [unavailableRun]
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.text()).toContain('History item unavailable')
    expect(wrapper.get('[data-testid="delete-fuzzing-run-100"]').attributes('aria-label')).toBe('Delete')
    expect(wrapper.findAll('button').some(button => button.text() === 'Open Result')).toBe(false)
    expect(wrapper.findAll('button').some(button => button.text() === 'Replay')).toBe(false)
    expect(wrapper.findAll('button').some(button => button.text() === 'Verify formally')).toBe(false)
  })

  it('treats fuzz findings as metadata-only summaries and validates evidence on action', async () => {
    const run: FuzzingRunSummary = {
      eligibleSpecifications: {},
      modelTokenSourcesByDeviceId: {},
      id: 40,
      initiator: 'USER',
      explorationMode: 'BOARD_SNAPSHOT',
      outcome: 'FOUND_VIOLATION',
      effectiveSeed: 42,
      iterations: 1,
      generatedPaths: 1,
      elapsedMs: 10,
      modelSnapshot: snapshot(1),
      eligibility: {
        eligibleSpecIds: ['spec-1'],
        eligibleSpecLabels: { 'spec-1': 'Frozen door safety label' },
        ineligibleSpecs: [],
        requestedSpecCount: 1,
        eligibleSpecCount: 1
      },
      limitations: [],
      maxIterations: 10,
      pathLength: 2,
      populationSize: 1,
      createdAt: '2026-07-13T11:00:00',
      completedAt: '2026-07-13T11:00:01',
      findingCount: 1,
      findings: [{
        id: 401,
        fuzzTaskId: 40,
        violatedSpecId: 'spec-1',
        specificationLabel: 'Frozen door safety label',
        firstViolationStep: 0,
        seed: 42,
        createdAt: '2026-07-13T11:00:01',
        stateCount: 1
      }],
      dataAvailable: true
    }

    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'results',
        resultFilter: 'fuzzing',
        fuzzingRuns: [run]
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.text()).toContain('Frozen door safety label')
    const replay = wrapper.get('[data-testid="view-fuzzing-finding-401"]')
    const verifyButton = wrapper.get('[data-testid="verify-fuzzing-finding-401"]')
    expect(verifyButton.text()).toBe('Configure current board verification')
    expect(wrapper.get('[data-testid="history-fuzz-verification-scope"]').text())
      .toContain('every specification on the current board')
    expect(wrapper.get('[data-testid="history-result-fuzzing-40"] time').attributes('datetime')).toBe(run.completedAt)
    expect(replay.attributes('disabled')).toBeUndefined()
    expect(verifyButton.attributes('disabled')).toBeUndefined()
    await replay.trigger('click')
    await verifyButton.trigger('click')
    expect(wrapper.emitted('view-fuzzing-finding')).toEqual([[401, 40]])
    expect(wrapper.emitted('verify-fuzzing-finding')).toEqual([[run.findings[0]]])
  })

  it('keeps an intact fuzz finding usable when a sibling finding is unavailable', async () => {
    const unavailableFinding = {
      id: 402,
      fuzzTaskId: 40,
      violatedSpecId: 'spec-1',
      specificationLabel: 'Damaged candidate',
      firstViolationStep: 0,
      seed: 42,
      createdAt: '2026-07-13T11:00:01',
      stateCount: 1,
      dataAvailable: false as const,
      unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID'
    }
    const availableFinding = {
      ...unavailableFinding,
      id: 403,
      specificationLabel: 'Intact candidate',
      dataAvailable: true as const,
      unavailableReasonCode: undefined
    }
    const run: FuzzingRunSummary = {
      eligibleSpecifications: {},
      modelTokenSourcesByDeviceId: {},
      id: 40,
      initiator: 'USER',
      explorationMode: 'BOARD_SNAPSHOT',
      outcome: 'FOUND_VIOLATION',
      effectiveSeed: 42,
      iterations: 1,
      generatedPaths: 1,
      elapsedMs: 10,
      modelSnapshot: snapshot(1),
      eligibility: {
        eligibleSpecIds: ['spec-1'],
        eligibleSpecLabels: { 'spec-1': 'Frozen door safety label' },
        ineligibleSpecs: [],
        requestedSpecCount: 1,
        eligibleSpecCount: 1
      },
      limitations: [],
      maxIterations: 10,
      pathLength: 2,
      populationSize: 1,
      createdAt: '2026-07-13T11:00:00',
      completedAt: '2026-07-13T11:00:01',
      findingCount: 2,
      findings: [unavailableFinding, availableFinding],
      dataAvailable: true
    }

    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'results',
        resultFilter: 'fuzzing',
        fuzzingRuns: [run]
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.text()).toContain('This candidate trace is damaged and cannot be used.')
    expect(wrapper.get('[data-testid="view-fuzzing-finding-402"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="verify-fuzzing-finding-402"]').attributes('disabled')).toBeDefined()
    const replay = wrapper.get('[data-testid="view-fuzzing-finding-403"]')
    const verifyButton = wrapper.get('[data-testid="verify-fuzzing-finding-403"]')
    expect(replay.attributes('disabled')).toBeUndefined()
    expect(verifyButton.attributes('disabled')).toBeUndefined()
    await replay.trigger('click')
    await verifyButton.trigger('click')
    expect(wrapper.emitted('view-fuzzing-finding')).toEqual([[403, 40]])
    expect(wrapper.emitted('verify-fuzzing-finding')).toEqual([[availableFinding]])
  })

  it('presents budget exhaustion as a neutral heuristic result, not a proof or fix target', () => {
    const run: FuzzingRunSummary = {
      eligibleSpecifications: {},
      modelTokenSourcesByDeviceId: {},
      id: 41,
      initiator: 'USER',
      explorationMode: 'BOARD_SNAPSHOT',
      outcome: 'BUDGET_EXHAUSTED',
      effectiveSeed: 42,
      iterations: 500,
      generatedPaths: 5000,
      elapsedMs: 1200,
      modelSnapshot: snapshot(1),
      eligibility: {
        eligibleSpecIds: ['spec-1'],
        eligibleSpecLabels: { 'spec-1': 'Door remains closed' },
        ineligibleSpecs: [],
        requestedSpecCount: 1,
        eligibleSpecCount: 1
      },
      limitations: ['Finite heuristic search.'],
      maxIterations: 500,
      pathLength: 20,
      populationSize: 10,
      createdAt: '2026-07-13T11:00:00',
      completedAt: '2026-07-13T11:00:02',
      findingCount: 0,
      findings: [],
      dataAvailable: true
    }

    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'results',
        resultFilter: 'fuzzing',
        currentModelFingerprints: null,
        fuzzingRuns: [run]
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.text()).toContain('Search budget exhausted')
    expect(wrapper.get('[data-testid="fuzzing-history-mode-41"]').text()).toContain('Board snapshot')
    expect(wrapper.text()).toContain('This is not a safety proof.')
    expect(wrapper.get('[data-testid="fuzzing-history-model-comparison-41"]').attributes('data-state')).toBe('UNAVAILABLE')
    expect(wrapper.findAll('button').some(button => button.text() === 'Fix rules')).toBe(false)
    expect(wrapper.findAll('button').some(button => button.text() === 'Verify formally')).toBe(false)
  })

  it('detects semantic drift when counts match but model fingerprints differ', () => {
    const run: FuzzingRunSummary = {
      eligibleSpecifications: {},
      modelTokenSourcesByDeviceId: {},
      id: 42,
      initiator: 'USER',
      explorationMode: 'BOARD_SNAPSHOT',
      outcome: 'BUDGET_EXHAUSTED',
      effectiveSeed: 42,
      iterations: 10,
      generatedPaths: 20,
      elapsedMs: 100,
      modelSnapshot: { ...snapshot(1), modelFingerprint: 'a'.repeat(64) },
      eligibility: {
        eligibleSpecIds: ['spec-1'],
        eligibleSpecLabels: { 'spec-1': 'Door remains closed' },
        ineligibleSpecs: [],
        requestedSpecCount: 1,
        eligibleSpecCount: 1
      },
      limitations: [],
      maxIterations: 10,
      pathLength: 5,
      populationSize: 2,
      createdAt: '2026-07-13T11:00:00',
      completedAt: '2026-07-13T11:00:01',
      findingCount: 0,
      findings: [],
      dataAvailable: true
    }

    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'results',
        resultFilter: 'fuzzing',
        currentModelFingerprints: { verification: 'b'.repeat(64), simulation: 'a'.repeat(64) },
        fuzzingRuns: [run]
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.get('[data-testid="fuzzing-history-model-comparison-42"]').attributes('data-state'))
      .toBe('CHANGED')
  })

  it('does not claim a fingerprinted run is unchanged when the current fingerprint is unavailable', () => {
    const run: FuzzingRunSummary = {
      eligibleSpecifications: {},
      modelTokenSourcesByDeviceId: {},
      id: 43,
      initiator: 'USER',
      explorationMode: 'BOARD_SNAPSHOT',
      outcome: 'BUDGET_EXHAUSTED',
      effectiveSeed: 43,
      iterations: 10,
      generatedPaths: 20,
      elapsedMs: 100,
      modelSnapshot: { ...snapshot(1), modelFingerprint: 'a'.repeat(64) },
      eligibility: {
        eligibleSpecIds: ['spec-1'],
        eligibleSpecLabels: { 'spec-1': 'Door remains closed' },
        ineligibleSpecs: [],
        requestedSpecCount: 1,
        eligibleSpecCount: 1
      },
      limitations: [],
      maxIterations: 10,
      pathLength: 5,
      populationSize: 2,
      createdAt: '2026-07-13T11:00:00',
      completedAt: '2026-07-13T11:00:01',
      findingCount: 0,
      findings: [],
      dataAvailable: true
    }

    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'results',
        resultFilter: 'fuzzing',
        currentModelFingerprints: null,
        fuzzingRuns: [run]
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.get('[data-testid="fuzzing-history-model-comparison-43"]').attributes('data-state'))
      .toBe('UNAVAILABLE')
  })

  it('offers an explicit next page only for fuzzing history', async () => {
    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'results',
        resultFilter: 'fuzzing',
        hasMoreFuzzingRuns: true,
        fuzzingRuns: [{
          id: 101,
          initiator: 'UNKNOWN',
          createdAt: '2026-07-13T10:00:00',
          findings: [],
          dataAvailable: false,
          unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID'
        }]
      },
      global: { plugins: [i18n] }
    })

    const loadMore = wrapper.get('[data-testid="load-more-fuzzing-runs"]')
    expect(loadMore.text()).toContain('Load more counterexample results')
    await loadMore.trigger('click')
    expect(wrapper.emitted('load-more-fuzzing-runs')).toHaveLength(1)

    await wrapper.setProps({ resultFilter: 'all' })
    expect(wrapper.find('[data-testid="load-more-fuzzing-runs"]').exists()).toBe(false)
  })

  it('keeps a source load failure visible instead of presenting an empty history', async () => {
    const wrapper = mount(TraceHistoryPanel, {
      props: {
        ...baseProps,
        activeLayer: 'results',
        resultFilter: 'fuzzing',
        resultErrors: { fuzzing: { cause: 'Request timed out', details: [], fields: {} } }
      },
      global: { plugins: [i18n] }
    })

    const alert = wrapper.get('[data-testid="history-results-load-error"]')
    expect(alert.text()).toContain('Request timed out')
    expect(wrapper.text()).not.toContain('No history results')
    await alert.get('button').trigger('click')
    expect(wrapper.emitted('refresh-results')).toHaveLength(1)
    wrapper.unmount()
  })

  it('keeps declared result diagnostics in a Chinese notice and clears them on recovery', async () => {
    const previousLocale = actualI18n.global.locale.value
    actualI18n.global.locale.value = 'zh-CN'
    const wrapper = mount(TraceHistoryPanel, {
      props: { ...baseProps, activeLayer: 'results', resultFilter: 'fuzzing',
        resultErrors: { fuzzing: { cause: 'History storage unavailable',
          details: ['History storage unavailable', 'HTTP 503'], fields: { storage: 'Reconnect the history store' } } } },
      global: { plugins: [actualI18n] }
    })
    try {
      const alert = wrapper.get('[data-testid="history-results-load-error"]')
      expect(alert.text()).toContain(actualI18n.global.t('app.historyResultsLoadFailed'))
      expect(alert.get('[data-testid="failure-technical-details"]').text()).toContain('History storage unavailable')
      expect(alert.get('[data-testid="failure-technical-details"]').text()).toContain('storage: Reconnect the history store')
      expect(alert.get('[data-testid="failure-technical-details"]').attributes('open')).toBeUndefined()
      expect(wrapper.text()).not.toContain(actualI18n.global.t('app.noRunResults'))
      await alert.get('button').trigger('click')
      expect(wrapper.emitted('refresh-results')).toHaveLength(1)
      await wrapper.setProps({ resultErrors: {} })
      expect(wrapper.find('[data-testid="history-results-load-error"]').exists()).toBe(false)
      expect(wrapper.text()).toContain(actualI18n.global.t('app.noFilteredRunResults', {
        kind: actualI18n.global.t('app.fuzzSearch')
      }))
    } finally { wrapper.unmount(); actualI18n.global.locale.value = previousLocale }
  })
})
