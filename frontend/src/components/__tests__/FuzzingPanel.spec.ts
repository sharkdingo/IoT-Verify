// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { describe, expect, it } from 'vitest'
import FuzzingPanel from '../FuzzingPanel.vue'
import InfoTooltip from '../common/InfoTooltip.vue'
import type { Specification } from '@/types/spec'
import type { FuzzPaperDomainPreview } from '@/types/fuzzing'
import { i18n as appI18n } from '@/assets/i18n'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      app: {
        fuzzSearch: 'Counterexample Search',
        fuzzSearchSubtitle: 'Search for candidate paths',
        runFailureNextStep: 'Inspect the run history and task status before retrying.',
        fuzzBudgetProductHint: 'Total work = iteration budget × path length × candidate paths × board complexity.',
        fuzzExplorationMode: 'Exploration initial state',
        fuzzModeBoard: 'Board snapshot',
        fuzzModePaper: 'Random state and events',
        fuzzModeBoardDescription: 'Starts from the frozen Board initial state.',
        fuzzModePaperDescription: 'Starts from a legal random state and searches reproducible inputs; it is not a proof.',
        fuzzPaperDomainTitle: 'Random-state and event range',
        fuzzPaperDomainLoading: 'Loading input ranges',
        fuzzPaperDomainSummary: '{devices} device initial-state items, {locals} local items, {environments} environment items, positions 1 through {length}',
        fuzzPaperInspectDomains: 'Inspect initial-state and input choices',
        fuzzPaperDeviceStateDomains: 'Device initial-state inputs',
        fuzzPaperLocalVariableDomains: 'Device local-variable domains',
        fuzzPaperEnvironmentDomains: 'Environment domains',
        fuzzPaperInitialValues: 'initial values',
        fuzzPaperRateEvents: 'rate inputs',
        fuzzPaperDirectValueEvents: 'direct inputs',
        fuzzPaperCoverageAndBoundaries: 'Capabilities and boundaries',
        fuzzPaperUnimplementedScope: 'This mode supports structured temporal specifications and integer ranges; formal verification is still required.',
        fuzzLimitationPaperRandomInitialStateEnabled: 'Random legal initial states are enabled.',
        fuzzLimitationPaperPredecessorOutputsThreeLevels: 'Automation conditions are traced back by up to three levels.',
        fuzzLimitationUnknown: 'Unknown boundary.',
        refresh: 'Refresh',
        fuzzTargetSpecifications: 'Target specifications',
        fuzzTargetSpecificationsHint: 'Use formal verification for unsupported specifications.',
        showHelpFor: 'Help for {topic}',
        fuzzEligibilitySummary: '{eligible} of {total} eligible, {selected} selected',
        fuzzEligibilityUnsupportedTemplate: 'This template is not supported by the finite explorer.',
        fuzzEligibilityTrustPrivacyUnsupported: 'Trust and privacy propagation require formal verification.',
        fuzzTargetSelectionChanged: '{count} selected target(s) are no longer available.',
        fuzzRemoveUnavailableTargets: 'Remove unavailable targets',
        fuzzSelectCurrentTargets: 'Select current targets',
        fuzzFormalOnly: 'Formal only',
        noEligibleSpecsToFuzz: 'No eligible specifications.',
        selectAll: 'Select all',
        clearSelection: 'Clear selection',
        allSpecificationsSelected: 'All specifications selected',
        fuzzAtLeastOneTarget: 'Keep at least one target specification selected.',
        noSpecsToFuzz: 'Add a specification first.',
        unknownSpecification: 'Unknown specification',
        traceConditionAnd: ' AND ',
        traceSpecAlways: 'Always: {condition}',
        fuzzMaxIterations: 'Iteration budget',
        fuzzPathLength: 'Path length (states)',
        fuzzAdvancedSettings: 'Advanced settings',
        fuzzPopulationSize: 'Candidate paths per iteration',
        fuzzPathsPerIteration: 'paths/iteration',
        fuzzSeed: 'Random seed',
        fuzzSeedAuto: 'Generated automatically',
        fuzzSeedHint: 'A fixed model, settings, and seed reproduce inputs and order; time limits can affect completion.',
        taskInitializing: 'Initializing',
        fuzzTaskId: 'Task #{id}',
        fuzzWorkloadPreview: 'Workload {workload} / {limit}',
        fuzzModelComplexity: 'Board complexity: {complexity}',
        fuzzWorkloadLoading: 'Confirming available budget',
        fuzzConfigurationBlocked: 'Adjust the parameters before starting.',
        retry: 'Retry',
        fuzzBudgetProgress: 'Budget progress',
        fuzzProgressNotCoverage: 'Not coverage.',
        fuzzWatchingFrozenTask: 'Viewing frozen task #{id}',
        fuzzWatchingFrozenTaskDetail: 'These values came from submission.',
        fuzzFrozenTaskSnapshotSummary: '{devices} devices, {rules} rules, {specs} specifications, {variables} variables, {templates} templates',
        fuzzReproductionSelectedTargets: '{count} selected target(s)',
        fuzzReproductionAllTargets: 'All {count} captured targets',
        cancel: 'Cancel',
        close: 'Close',
        fuzzRunning: 'Searching',
        startFuzzSearch: 'Start background search'
      }
    }
  }
})

const specification: Specification = {
  id: 'spec-1',
  templateId: '1',
  templateLabel: 'Door remains closed',
  aConditions: [],
  ifConditions: [],
  thenConditions: []
}

describe('FuzzingPanel', () => {
  it.each(['en', 'zh-CN'])('explains stale targets once and selects only explorable targets in %s', async locale => {
    const translations = createI18n({ legacy: false, locale, messages: {
      en: appI18n.global.getLocaleMessage('en'),
      'zh-CN': appI18n.global.getLocaleMessage('zh-CN')
    } })
    const message = translations.global.t('app.fuzzTargetSelectionChanged', { count: 1 })
    const form = { explorationMode: 'BOARD_SNAPSHOT' as const, targetSelectionMode: 'EXPLICIT' as 'ALL' | 'EXPLICIT',
      targetSpecIds: ['deleted-spec'], maxIterations: 200, pathLength: 20, populationSize: 10, seed: null }
    const wrapper = mount(FuzzingPanel, {
      props: { form, specifications: [specification, { ...specification, id: 'unsupported', templateId: '2' }],
        running: false, progress: 0, status: '', taskId: null, cancelling: false, configurationError: message },
      global: { plugins: [translations] }
    })
    try {
      expect(wrapper.text().split(message)).toHaveLength(2)
      expect(wrapper.get('details').attributes('open')).toBeUndefined()
      const resolve = wrapper.get('[data-testid="fuzz-invalid-target-selection"] button')
      expect(resolve.text()).toBe(translations.global.t('app.fuzzSelectCurrentTargets'))
      await resolve.trigger('click')
      expect(form.targetSelectionMode).toBe('ALL')
      const targets = wrapper.findAll('input[type="checkbox"]')
      expect((targets[0].element as HTMLInputElement).checked).toBe(true)
      expect((targets[1].element as HTMLInputElement).checked).toBe(false)
      expect(targets[1].attributes('disabled')).toBeDefined()
      await wrapper.setProps({ configurationError: '' })
      expect(wrapper.get('[data-testid="run-fuzzing"]').attributes('disabled')).toBeUndefined()
    } finally { wrapper.unmount() }
  })

  it('shows the run failure once while retaining separate technical evidence', () => {
    const message = 'The exploration task failed.'
    const wrapper = mount(FuzzingPanel, {
      props: {
        form: { explorationMode: 'BOARD_SNAPSHOT', targetSpecIds: [], maxIterations: 200,
          pathLength: 20, populationSize: 10, seed: null },
        specifications: [specification], running: false, progress: 0, status: '',
        taskId: null, cancelling: false, error: message,
        failureDetails: { cause: message, details: [message, 'HTTP 500'], fields: {} }
      },
      global: { plugins: [i18n] }
    })
    try {
      expect(wrapper.text().split(message)).toHaveLength(2)
      expect(wrapper.find('[data-testid="failure-cause"]').exists()).toBe(false)
      expect(wrapper.get('[data-testid="failure-technical-details"]').text()).toContain('HTTP 500')
    } finally { wrapper.unmount() }
  })

  it('blocks submission while the backend workload check is pending and allows retry', async () => {
    const wrapper = mount(FuzzingPanel, {
      props: {
        form: {
          explorationMode: 'BOARD_SNAPSHOT',
          targetSpecIds: [],
          maxIterations: 500,
          pathLength: 20,
          populationSize: 10,
          seed: null
        },
        specifications: [specification],
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false,
        workloadReady: false,
        workloadLoading: true
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.get('[data-testid="run-fuzzing"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Confirming available budget')

    await wrapper.setProps({ workloadLoading: false, workloadError: 'Budget check failed' })
    expect(wrapper.text()).toContain('Budget check failed')
    await wrapper.get('[data-testid="refresh-fuzzing-workload"]').trigger('click')
    expect(wrapper.emitted('refreshWorkload')).toHaveLength(1)
  })

  it('returns a cleared restored seed to automatic generation', async () => {
    const form = {
      explorationMode: 'BOARD_SNAPSHOT' as const,
      targetSelectionMode: 'ALL' as 'ALL' | 'EXPLICIT',
      targetSpecIds: [],
      maxIterations: 500,
      pathLength: 20,
      populationSize: 10,
      seed: 42 as number | null
    }
    const wrapper = mount(FuzzingPanel, {
      props: {
        form,
        specifications: [specification],
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false
      },
      global: { plugins: [i18n] }
    })

    const seed = wrapper.get('[data-testid="fuzz-seed"]')
    expect((seed.element as HTMLInputElement).value).toBe('42')
    await seed.setValue('')
    expect(form.seed).toBeNull()
  })

  it('exposes backend-aligned limits and blocks an invalid combined workload', async () => {
    const wrapper = mount(FuzzingPanel, {
      props: {
        form: {
          explorationMode: 'BOARD_SNAPSHOT',
          targetSpecIds: [],
          maxIterations: 5001,
          pathLength: 50,
          populationSize: 50,
          seed: null
        },
        specifications: [specification],
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false,
        configurationError: 'Combined workload exceeds the limit.'
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.get('[data-testid="fuzz-max-iterations"]').attributes('max')).toBe('5000')
    expect(wrapper.get('[data-testid="fuzz-path-length"]').attributes('max')).toBe('50')
    expect(wrapper.get('[data-testid="fuzz-population-size"]').attributes('max')).toBe('50')
    expect(wrapper.text().split('Combined workload exceeds the limit.')).toHaveLength(2)
    expect(wrapper.get('[data-testid="run-fuzzing"]').attributes('aria-describedby')).toBe('fuzz-configuration-error')
    expect(wrapper.get('#fuzz-configuration-error').text()).toBe('Combined workload exceeds the limit.')
    // The rejection names "candidate paths per iteration" as a remedy, and that input lives inside the
    // Advanced settings disclosure. A blocked user must be able to see the control they are told to change.
    expect(wrapper.get('details').attributes('open')).toBeDefined()
    expect(wrapper.get('[data-testid="run-fuzzing"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('input[type="checkbox"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('input[type="checkbox"]').attributes('title')).toBe('Keep at least one target specification selected.')

    await wrapper.get('[data-testid="run-fuzzing"]').trigger('click')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('keeps workload diagnostics in advanced settings and hides a rejected estimate', async () => {
    const preview = {
      maxIterations: 500,
      pathLength: 20,
      populationSize: 10,
      explorationMode: 'BOARD_SNAPSHOT' as const,
      modelComplexityUnits: 151,
      estimatedWorkload: 15_100_000,
      workloadLimit: 30_000_000,
      accepted: true,
      maxAcceptedIterations: 993
    }
    const wrapper = mount(FuzzingPanel, {
      props: {
        form: {
          explorationMode: 'BOARD_SNAPSHOT',
          targetSpecIds: [],
          maxIterations: 500,
          pathLength: 20,
          populationSize: 10,
          seed: null
        },
        specifications: [specification],
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false,
        workload: preview.estimatedWorkload,
        workloadLimit: preview.workloadLimit,
        workloadPreview: preview
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.get('details [data-testid="fuzz-model-complexity"]').text()).toContain('151')
    expect(wrapper.find('details #fuzz-budget-help').exists()).toBe(true)
    const seedHelp = wrapper.findAllComponents(InfoTooltip).find(help => help.props('testId') === 'fuzz-seed-help')
    expect(seedHelp?.props('text')).toContain('time limits can affect completion')
    expect(wrapper.text()).not.toContain('time limits can affect completion')
    expect(wrapper.text()).toContain('Workload 15,100,000 / 30,000,000')
    await wrapper.setProps({ configurationError: 'Workload is above the limit.' })
    expect(wrapper.text()).toContain('Workload is above the limit.')
    expect(wrapper.text()).not.toContain('Workload 15,100,000 / 30,000,000')
  })

  it('does not offer a misleading select-all action above the target limit', () => {
    const specifications = Array.from({ length: 101 }, (_, index) => ({
      ...specification,
      id: `spec-${index + 1}`
    }))
    const wrapper = mount(FuzzingPanel, {
      props: {
        form: {
          explorationMode: 'BOARD_SNAPSHOT',
          targetSpecIds: [],
          maxIterations: 500,
          pathLength: 20,
          populationSize: 10,
          seed: null
        },
        specifications,
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false,
        configurationError: 'Explicitly select at most 100 specifications.'
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.findAll('button').some(button => button.text() === 'Select all')).toBe(false)
    expect(wrapper.text()).toContain('Explicitly select at most 100 specifications.')
    expect(wrapper.get('[data-testid="run-fuzzing"]').attributes('disabled')).toBeDefined()
  })

  it('treats an empty target list as implicit all for bounded boards', async () => {
    const specifications = [1, 2, 3].map(index => ({
      ...specification,
      id: `spec-${index}`
    }))
    const form = {
      explorationMode: 'BOARD_SNAPSHOT' as const,
      targetSelectionMode: 'ALL' as 'ALL' | 'EXPLICIT',
      targetSpecIds: [],
      maxIterations: 500,
      pathLength: 20,
      populationSize: 10,
      seed: null
    }
    const wrapper = mount(FuzzingPanel, {
      props: {
        form,
        specifications,
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.findAll('input[type="checkbox"]').every(input =>
      (input.element as HTMLInputElement).checked
    )).toBe(true)
    const allSelected = wrapper.findAll('button').find(button => button.text() === 'All specifications selected')
    expect(allSelected).toBeDefined()
    expect(allSelected!.attributes('disabled')).toBeDefined()

    await wrapper.findAll('input[type="checkbox"]')[0].setValue(false)
    expect(form.targetSpecIds).toEqual(['spec-2', 'spec-3'])
    expect(form.targetSelectionMode).toBe('EXPLICIT')
    expect((wrapper.findAll('input[type="checkbox"]')[0].element as HTMLInputElement).checked).toBe(false)
    expect((wrapper.findAll('input[type="checkbox"]')[1].element as HTMLInputElement).checked).toBe(true)

    const selectAll = wrapper.findAll('button').find(button => button.text() === 'Select all')
    expect(selectAll).toBeDefined()
    await selectAll!.trigger('click')
    expect(form.targetSpecIds).toEqual([])
    expect(form.targetSelectionMode).toBe('ALL')
  })

  it('blocks a stale explicit target until the user resolves the changed scope', async () => {
    const form = {
      explorationMode: 'BOARD_SNAPSHOT' as const,
      targetSelectionMode: 'EXPLICIT' as const,
      targetSpecIds: ['deleted-spec'],
      maxIterations: 500,
      pathLength: 20,
      populationSize: 10,
      seed: null
    }
    const wrapper = mount(FuzzingPanel, {
      props: {
        form,
        specifications: [specification],
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.get('[data-testid="fuzz-invalid-target-selection"]').text())
      .toContain('1 selected target(s) are no longer available.')
    expect(wrapper.get('[data-testid="run-fuzzing"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="fuzz-invalid-target-selection"] button').trigger('click')
    expect(form.targetSpecIds).toEqual([])
    expect(form.targetSelectionMode).toBe('ALL')
    expect(wrapper.find('[data-testid="fuzz-invalid-target-selection"]').exists()).toBe(false)
  })

  it('does not allow an explicit selection to become empty', async () => {
    const form = {
      explorationMode: 'BOARD_SNAPSHOT' as const,
      targetSpecIds: ['spec-1'],
      maxIterations: 500,
      pathLength: 20,
      populationSize: 10,
      seed: null
    }
    const wrapper = mount(FuzzingPanel, {
      props: {
        form,
        specifications: [specification],
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false
      },
      global: { plugins: [i18n] }
    })

    const checkbox = wrapper.get('input[type="checkbox"]')
    expect(checkbox.attributes('disabled')).toBeDefined()
    expect(checkbox.attributes('title')).toBe('Keep at least one target specification selected.')
    await checkbox.setValue(false)
    expect(form.targetSpecIds).toEqual(['spec-1'])
  })

  it('marks known unsupported templates as formal-only and excludes them from selection', () => {
    const unsupported = { ...specification, id: 'spec-2', templateId: '2' as const }
    const wrapper = mount(FuzzingPanel, {
      props: {
        form: {
          explorationMode: 'BOARD_SNAPSHOT',
          targetSpecIds: [],
          maxIterations: 500,
          pathLength: 20,
          populationSize: 10,
          seed: null
        },
        specifications: [specification, unsupported],
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false
      },
      global: { plugins: [i18n] }
    })

    const checkboxes = wrapper.findAll('input[type="checkbox"]')
    const targetHelp = wrapper.findAllComponents(InfoTooltip).find(help => help.props('testId') === 'fuzz-targets-help')!
    expect(targetHelp.props('text')).toBe('Use formal verification for unsupported specifications.')
    expect(wrapper.get('[data-testid="fuzz-targets-help"]').attributes('aria-label')).toBe('Help for Target specifications')
    expect(wrapper.text()).not.toContain('Use formal verification for unsupported specifications.')
    expect((checkboxes[0].element as HTMLInputElement).checked).toBe(true)
    expect((checkboxes[1].element as HTMLInputElement).checked).toBe(false)
    expect(checkboxes[1].attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Formal only')
    expect(wrapper.get('[data-testid="fuzz-eligibility-summary"]').text()).toContain('1 of 2 eligible, 1 selected')
  })

  it('marks typed trust and privacy specifications as formal-only before submission', () => {
    const trustSpecification: Specification = {
      ...specification,
      id: 'spec-trust',
      aConditions: [{
        id: 'condition-trust',
        side: 'a',
        deviceId: 'door-lock',
        deviceLabel: 'Door lock',
        targetType: 'trust',
        key: 'PowerMode',
        propertyScope: 'state',
        relation: '=',
        value: 'trusted'
      }]
    }
    const wrapper = mount(FuzzingPanel, {
      props: {
        form: {
          explorationMode: 'BOARD_SNAPSHOT',
          targetSpecIds: [],
          maxIterations: 500,
          pathLength: 20,
          populationSize: 10,
          seed: null
        },
        specifications: [specification, trustSpecification],
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false
      },
      global: { plugins: [i18n] }
    })

    const checkboxes = wrapper.findAll('input[type="checkbox"]')
    expect((checkboxes[0].element as HTMLInputElement).checked).toBe(true)
    expect((checkboxes[1].element as HTMLInputElement).checked).toBe(false)
    expect(checkboxes[1].attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Trust and privacy propagation require formal verification.')
    expect(wrapper.get('[data-testid="fuzz-eligibility-summary"]').text()).toContain('1 of 2 eligible')
  })

  it('keeps every rejection reason visible when no specification can be explored', () => {
    const wrapper = mount(FuzzingPanel, {
      props: {
        form: {
          explorationMode: 'BOARD_SNAPSHOT',
          targetSpecIds: [],
          maxIterations: 500,
          pathLength: 20,
          populationSize: 10,
          seed: null
        },
        specifications: [{ ...specification, templateId: '5' }],
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.text()).toContain('No eligible specifications.')
    expect(wrapper.text()).toContain('This template is not supported by the finite explorer.')
    expect(wrapper.text()).toContain('Formal only')
    expect(wrapper.get('input[type="checkbox"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="run-fuzzing"]').attributes('disabled')).toBeDefined()
  })

  it('does not present random-input preflight as a service failure before a target is eligible', () => {
    const wrapper = mount(FuzzingPanel, {
      props: {
        form: {
          explorationMode: 'PAPER_COMPATIBLE',
          targetSpecIds: [],
          maxIterations: 500,
          pathLength: 20,
          populationSize: 10,
          seed: null
        },
        specifications: [],
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false,
        workloadReady: false
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.text()).toContain('Add a specification first.')
    expect(wrapper.find('[data-testid="paper-domain-preview"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="run-fuzzing"]').attributes('disabled')).toBeDefined()
  })

  it('labels running progress as budget consumption rather than coverage', () => {
    const wrapper = mount(FuzzingPanel, {
      props: {
        form: {
          explorationMode: 'BOARD_SNAPSHOT',
          targetSpecIds: [],
          maxIterations: 500,
          pathLength: 20,
          populationSize: 10,
          seed: null
        },
        specifications: [specification],
        running: true,
        progress: 37,
        status: 'Searching',
        taskId: 12,
        cancelling: false
      },
      global: { plugins: [i18n] }
    })

    const progressbar = wrapper.get('[role="progressbar"]')
    expect(progressbar.attributes('aria-label')).toBe('Budget progress')
    expect(progressbar.attributes('aria-valuenow')).toBe('37')
    expect(wrapper.findAllComponents(InfoTooltip).find(help => help.props('testId') === 'fuzz-progress-help')?.props('text')).toBe('Not coverage.')
    expect(wrapper.findAll('p').some(line => line.text() === 'Not coverage.')).toBe(false)
  })

  it('uses an accessible segmented mode control and locks it while running', async () => {
    const form = {
      explorationMode: 'BOARD_SNAPSHOT' as 'BOARD_SNAPSHOT' | 'PAPER_COMPATIBLE',
      targetSpecIds: [],
      maxIterations: 500,
      pathLength: 20,
      populationSize: 10,
      seed: null
    }
    const wrapper = mount(FuzzingPanel, {
      props: {
        form,
        specifications: [specification],
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false
      },
      global: { plugins: [i18n] }
    })

    const group = wrapper.get('[role="radiogroup"]')
    const boardMode = wrapper.get('[data-testid="fuzz-mode-board"]')
    const paperMode = wrapper.get('[data-testid="fuzz-mode-paper"]')
    expect(group.attributes('aria-describedby')).toBe('fuzz-exploration-mode-help')
    expect((boardMode.element as HTMLInputElement).checked).toBe(true)
    expect(wrapper.text()).toContain('Starts from the frozen Board initial state.')

    await paperMode.setValue(true)
    expect(form.explorationMode).toBe('PAPER_COMPATIBLE')
    expect(wrapper.text()).toContain('Starts from a legal random state and searches reproducible inputs')
    expect(wrapper.get('[data-testid="run-fuzzing"]').attributes('disabled')).toBeDefined()

    await wrapper.setProps({ running: true })
    expect(boardMode.attributes('disabled')).toBeDefined()
    expect(paperMode.attributes('disabled')).toBeDefined()
  })

  it('shows the authoritative paper domains and exposes a refresh command', async () => {
    const wrapper = mount(FuzzingPanel, {
      props: {
        form: {
          explorationMode: 'PAPER_COMPATIBLE',
          targetSpecIds: [],
          maxIterations: 500,
          pathLength: 20,
          populationSize: 10,
          seed: null
        },
        specifications: [specification],
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false,
        paperDomainPreview: {
          pathLength: 20,
          modelFingerprint: 'a'.repeat(64),
          initializationPolicy: 'RANDOM_LEGAL_PER_SEED',
          paperSemanticsCodes: [
            'PAPER_RANDOM_INITIAL_STATE_ENABLED',
            'PAPER_PREDECESSOR_STATE_OUTPUTS_THREE_LEVELS_ONLY'
          ],
          deviceDomains: [{
            targetId: 'door-1',
            label: 'Front door',
            property: 'workingState',
            legalValues: ['open', 'closed']
          }],
          localVariableDomains: [{
            targetId: 'door-1',
            label: 'Front door',
            property: 'battery',
            legalValues: [],
            lowerBound: 0,
            upperBound: 2000
          }],
          environmentDomains: [{
            name: 'temperature',
            targetId: 'environment',
            property: 'temperature',
            eventValueKind: 'CHANGE_RATE',
            initialValues: [],
            eventValues: [],
            initialLowerBound: -2000,
            initialUpperBound: 2000,
            eventRateLowerBound: -1,
            eventRateUpperBound: 1
          }]
        }
      },
      global: { plugins: [i18n] }
    })

    const preview = wrapper.get('[data-testid="paper-domain-preview"]')
    expect(wrapper.get('[data-testid="fuzz-path-length"]').element.compareDocumentPosition(preview.element)
      & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(preview.get<HTMLDetailsElement>('[data-testid="paper-domain-choices"]').element.open).toBe(false)
    expect(preview.get<HTMLDetailsElement>('[data-testid="paper-domain-boundaries"]').element.open).toBe(false)
    expect(preview.get('[data-testid="paper-domain-boundaries"]').text()).toContain('structured temporal specifications')
    expect(preview.text()).not.toContain('Each candidate starts')
    expect(wrapper.get('[data-testid="paper-domain-preview"]').text()).toContain('1 device initial-state items')
    expect(wrapper.get('[data-testid="paper-local-variable-domains"]').text()).toContain('Front door.battery')
    expect(wrapper.get('[data-testid="paper-local-variable-domains"]').text()).toContain('0..2000')
    expect(wrapper.get('[data-testid="paper-domain-preview"]').text()).toContain('-2000..2000')
    expect(wrapper.get('[data-testid="paper-domain-preview"]').text()).toContain('-1..1')
    expect(wrapper.get('[data-testid="paper-domain-preview"]').text()).toContain('Automation conditions are traced back by up to three levels.')
    expect(wrapper.get('[data-testid="paper-domain-preview"]').text()).not.toMatch(/paper|FSM|BFS|GetPrevConditions|MEDIC|NuSMV|HAFuzz/i)
    expect(wrapper.get('[data-testid="paper-domain-preview"]').text()).not.toContain('rate:-1')
    expect(wrapper.get('[data-testid="run-fuzzing"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('[aria-label="Refresh"]').trigger('click')
    expect(wrapper.emitted('refreshPaperDomain')).toHaveLength(1)
    await wrapper.setProps({
      paperDomainPreview: {
        ...wrapper.props('paperDomainPreview')!,
        modelFingerprint: 'A'.repeat(64)
      }
    })
    expect(wrapper.get('[data-testid="run-fuzzing"]').attributes('disabled')).toBeDefined()
  })

  it('offers an explicit retry when the random-state input preview fails', async () => {
    const wrapper = mount(FuzzingPanel, {
      props: {
        form: {
          explorationMode: 'PAPER_COMPATIBLE',
          targetSpecIds: [],
          maxIterations: 500,
          pathLength: 20,
          populationSize: 10,
          seed: null
        },
        specifications: [specification],
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false,
        paperDomainError: 'Input ranges are temporarily unavailable.'
      },
      global: { plugins: [i18n] }
    })

    expect(wrapper.text()).toContain('Input ranges are temporarily unavailable.')
    await wrapper.get('[data-testid="refresh-paper-domain"]').trigger('click')
    expect(wrapper.emitted('refreshPaperDomain')).toHaveLength(1)
  })

  it('localizes bundled domain tokens without rewriting the authoritative preview', () => {
    appI18n.global.locale.value = 'zh-CN'
    const paperDomainPreview: FuzzPaperDomainPreview = {
      pathLength: 20,
      modelFingerprint: 'b'.repeat(64),
      initializationPolicy: 'RANDOM_LEGAL_PER_SEED',
      paperSemanticsCodes: ['PAPER_RANDOM_INITIAL_STATE_ENABLED'],
      deviceDomains: [{
        targetId: 'door_550e8400_e29b_41d4_a716_446655440000',
        label: '前门',
        property: 'workingState',
        legalValues: ['off', 'locked']
      }],
      localVariableDomains: [{
        targetId: 'door_550e8400_e29b_41d4_a716_446655440000',
        label: '前门',
        property: 'customMetric',
        legalValues: ['ecoBoost']
      }],
      environmentDomains: [{
        name: 'temperature',
        targetId: 'environment',
        property: 'temperature',
        eventValueKind: 'DIRECT_VALUE_EXTENSION',
        initialValues: ['20'],
        eventValues: ['21']
      }]
    }
    const canonicalSnapshot = JSON.parse(JSON.stringify(paperDomainPreview))
    const wrapper = mount(FuzzingPanel, {
      props: {
        form: {
          explorationMode: 'PAPER_COMPATIBLE',
          targetSpecIds: [],
          maxIterations: 500,
          pathLength: 20,
          populationSize: 10,
          seed: null
        },
        specifications: [specification],
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false,
        paperDomainPreview,
        bundledDeviceIds: ['door-550e8400-e29b-41d4-a716-446655440000'],
        bundledEnvironmentNames: ['temperature']
      },
      global: { plugins: [appI18n] }
    })

    const preview = wrapper.get('[data-testid="paper-domain-preview"]').text()
    expect(preview).toContain('前门.工作状态')
    expect(preview).toContain('关闭, 已锁定')
    expect(preview).toContain('温度')
    expect(preview).toContain('customMetric')
    expect(preview).toContain('ecoBoost')
    expect(paperDomainPreview).toEqual(canonicalSnapshot)
  })

  it('does not localize custom domain identifiers that collide with bundled tokens', () => {
    appI18n.global.locale.value = 'zh-CN'
    const wrapper = mount(FuzzingPanel, {
      props: {
        form: {
          explorationMode: 'PAPER_COMPATIBLE',
          targetSpecIds: [],
          maxIterations: 500,
          pathLength: 20,
          populationSize: 10,
          seed: null
        },
        specifications: [specification],
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false,
        paperDomainPreview: {
          pathLength: 20,
          modelFingerprint: 'c'.repeat(64),
          initializationPolicy: 'RANDOM_LEGAL_PER_SEED',
          paperSemanticsCodes: ['PAPER_RANDOM_INITIAL_STATE_ENABLED'],
          deviceDomains: [],
          localVariableDomains: [{
            targetId: 'custom-1',
            label: '自定义设备',
            property: 'workingState',
            legalValues: ['off', 'active']
          }],
          environmentDomains: [{
            name: 'weather',
            targetId: 'environment',
            property: 'weather',
            eventValueKind: 'DIRECT_VALUE_EXTENSION',
            initialValues: ['off'],
            eventValues: ['active']
          }]
        }
      },
      global: { plugins: [appI18n] }
    })

    const preview = wrapper.get('[data-testid="paper-domain-preview"]').text()
    expect(preview).toContain('自定义设备.workingState: off, active')
    expect(preview).toContain('weather')
    expect(preview).not.toContain('自定义设备.工作状态')
  })

  it('keeps a custom device-state property raw even when its name is a bundled token', () => {
    appI18n.global.locale.value = 'zh-CN'
    const wrapper = mount(FuzzingPanel, {
      props: {
        form: {
          explorationMode: 'PAPER_COMPATIBLE',
          targetSpecIds: [],
          maxIterations: 500,
          pathLength: 20,
          populationSize: 10,
          seed: null
        },
        specifications: [specification],
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false,
        paperDomainPreview: {
          pathLength: 20,
          modelFingerprint: 'e'.repeat(64),
          initializationPolicy: 'RANDOM_LEGAL_PER_SEED',
          paperSemanticsCodes: ['PAPER_RANDOM_INITIAL_STATE_ENABLED'],
          deviceDomains: [{
            targetId: 'custom-1',
            label: '自定义设备',
            property: 'workingState',
            legalValues: ['off', 'active']
          }],
          localVariableDomains: [],
          environmentDomains: []
        }
      },
      global: { plugins: [appI18n] }
    })

    const preview = wrapper.get('[data-testid="paper-domain-preview"]').text()
    expect(preview).toContain('自定义设备.workingState')
    expect(preview).not.toContain('自定义设备.工作状态')
  })

  it('shows a running task from its frozen summary instead of the current editable form', () => {
    const wrapper = mount(FuzzingPanel, {
      props: {
        form: {
          explorationMode: 'BOARD_SNAPSHOT',
          targetSpecIds: [],
          maxIterations: 500,
          pathLength: 20,
          populationSize: 10,
          seed: null
        },
        specifications: [specification],
        running: true,
        progress: 25,
        status: 'Running',
        taskId: 73,
        cancelling: false,
        frozenTask: {
          id: 73,
          initiator: 'USER',
          explorationMode: 'PAPER_COMPATIBLE',
          status: 'RUNNING',
          progress: 25,
          createdAt: '2026-07-15T10:00:00',
          modelSnapshot: {
            capturedAt: '2026-07-15T10:00:00',
            modelFingerprint: 'd'.repeat(64),
            deviceCount: 4,
            ruleCount: 3,
            specificationCount: 2,
            environmentVariableCount: 1,
            deviceTemplateCount: 2,
            templatesFrozen: true
          },
          maxIterations: 777,
          pathLength: 11,
          populationSize: 7,
          seed: 99,
          targetSpecIds: ['historical-spec']
        }
      },
      global: { plugins: [i18n] }
    })

    const summary = wrapper.get('[data-testid="fuzz-frozen-task-summary"]')
    expect(summary.text()).toContain('Viewing frozen task #73')
    expect(summary.text()).toContain('Random state and events')
    expect(summary.text()).toContain('777')
    expect(summary.text()).toContain('1 selected target')
    expect(wrapper.find('[data-testid="fuzz-mode-board"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="run-fuzzing"]').exists()).toBe(false)
  })

  it('focuses the panel without triggering a close hint, closes with Escape, and restores the opener', async () => {
    const opener = document.createElement('button')
    document.body.appendChild(opener)
    opener.focus()
    const wrapper = mount(FuzzingPanel, {
      attachTo: document.body,
      props: {
        form: {
          explorationMode: 'BOARD_SNAPSHOT',
          targetSpecIds: [],
          maxIterations: 500,
          pathLength: 20,
          populationSize: 10,
          seed: null
        },
        specifications: [specification],
        running: false,
        progress: 0,
        status: 'Initializing',
        taskId: null,
        cancelling: false
      },
      global: { plugins: [i18n] }
    })
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(document.activeElement).toBe(wrapper.get('[data-testid="fuzzing-panel"]').element)
    await wrapper.get('[data-testid="fuzzing-panel"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
    expect(document.activeElement).toBe(opener)
    opener.remove()
  })
})
