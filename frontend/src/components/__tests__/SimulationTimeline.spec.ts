// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import postcss from 'postcss'
import { createI18n } from 'vue-i18n'
import { i18n as appI18n } from '@/assets/i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SimulationTimeline from '../SimulationTimeline.vue'
import type { TraceState } from '@/types/verify'
import type { ModelSemantics } from '@/types/modelSemantics'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      app: {
        ...appI18n.global.getLocaleMessage('en').app,
        close: 'Close',
        exit: 'Exit',
        exitTimeline: 'Exit playback and return to the canvas',
        unknown: 'Unknown',
        state: 'State',
        mode: 'Mode',
        trust: 'Source label',
        privacy: 'Sensitivity label',
        ruleNumber: 'Rule {number}',
        stepChanges: 'Step changes',
        playbackControls: 'Playback controls',
        playbackViews: 'Playback terminal views',
        playbackTimeline: 'Timeline',
        playbackRunInfo: 'Run info',
        playbackNavigationHint: 'Click or drag the timeline to seek.',
        showHelpFor: 'Help for {topic}',
        completeModel: 'Complete model',
        incompleteModel: 'Incomplete model',
        playbackDeviceReadings: 'Device states and reported values',
        playbackActualEnvironment: 'Actual home environment',
        playbackUntrustedSources: 'Untrusted source labels: {labels}',
        playbackPrivateValues: 'Private-data labels: {labels}',
        hidePlaybackPanel: 'Hide playback terminal',
        playbackPreviousWindow: 'Previous group of states',
        playbackNextWindow: 'Next group of states',
        playbackStateWindow: 'States {start}–{end} / {total}',
        playbackInitialState: 'Initial',
        viewSimulationRunDetails: 'View run details',
        runScopeAndSnapshot: 'Run Scope and Submission Snapshot',
        simulationStoppedBeforeRequestedSteps: 'Only {actual} of {requested} transitions were returned',
        modelSemanticsUnavailable: 'Model semantics unavailable',
        environmentEvolutionIncluded: 'Shared environment evolution and local stutter semantics',
        labelPropagationScopeSummary: 'Labels propagate only on automation commands',
        attackExactSelectionShort: '{count} explicit points',
        attackExhaustiveSelectionShort: 'Exhaustive up to {count} points',
        attackExactSelectionDetail: 'Explicit points ({count}): {points}',
        attackExhaustiveSelectionDetail: 'Exhaustive up to {count} of {total} points',
        attackDevicePoint: 'device {id}',
        attackAutomationLinkPoint: 'rule link #{id}',
        modelRunSnapshotTitle: 'Frozen Submission Snapshot',
        modelRunSnapshotSummary: 'Captured {time}: {devices} devices, {rules} rules, {specs} specs, {variables} variables, {templates} templates',
        runBoardInputUnchanged: 'Current input matches',
        runBoardInputChanged: 'Current input changed',
        playbackBoardInputChanged: 'Current input changed; replay uses the saved run scene',
        runBoardComparisonUnavailable: 'Comparison unavailable',
        runBoardNotCompared: 'Current board not compared',
        runBoardInputUnchangedShort: 'Current input matches',
        runBoardInputChangedShort: 'Current input changed',
        runBoardComparisonUnavailableShort: 'Comparison unavailable',
        runBoardNotComparedShort: 'Current board not compared',
        traceVisualization: {
          modelTracePlayback: 'Model Trace Playback',
          modelTraceNotPrediction: 'Model execution, not a physical prediction',
          simulationAttackContext: 'Attack budget {count} of {total} ({devices} devices + {links} links)',
          simulationNoAttackContext: 'No attack behavior modeled',
          privacyPropagationNotModeled: 'Privacy propagation not modeled',
          playbackSnapshotReadOnly: 'Saved read-only run snapshot',
          modelIncompletePlayback: 'Incomplete model: {rules} rules omitted',
          initialModelState: 'Initial model state',
          rulesAppliedToReachState: 'Rules that produced this state',
          noRulesApplied: 'No automation rule drove this step',
          historicalRuleNotOnCurrentBoard: 'Historical rule is no longer on this board',
          devicesInCurrentState: 'Devices in this state',
          historicalDeviceNotOnCurrentBoard: 'Historical device is no longer on this board',
          runtimeCompromisedPoints: 'Current compromised points',
          compromisedAutomationLinks: 'Compromised automation links in this branch',
          securityLabels: 'Source and sensitivity labels',
          compromisedAutomationLinkHint: 'Command does not reach the target',
          attackScenario: 'Attack scenario',
          attackBudget: 'budget',
          privacyPropagationEnabled: 'Privacy propagation modeled',
          state: 'State {index}',
          compromisedPointCount: 'Current Compromised Points',
          attackedBang: 'Attacked',
          attacked: 'Attacked',
          compromised: 'Compromised',
          includesUntrustedSource: 'Includes untrusted source',
          includesPrivateData: 'Includes private-data label',
          untrustedLabelDetails: 'Untrusted source labels: {labels}',
          privateLabelDetails: 'Private-data labels: {labels}',
          stop: 'Stop',
          pause: 'Pause',
          play: 'Play',
          jumpToState: 'Jump to state',
          previousState: 'Previous state',
          nextState: 'Next state',
          stateLabel: 'State',
          stateDetails: 'State details',
          transitionNumber: 'State after transition {index}',
          environmentVariables: 'Environment variables',
          provenance: {
            externalInput: 'external input',
            affectedBy: 'affected by {device}',
            affectedByMultiple: 'affected by {count} devices',
            naturalEvolution: 'natural evolution {rate}'
          },
          changed: 'Changed',
          deviceBecameCompromised: 'Device became compromised',
          deviceNoLongerCompromised: 'Device is no longer compromised'
        }
      }
    }
  }
})

/**
 * A manifest `isModelSemanticsConsistent` accepts, which every mount needs.
 *
 * The prop used to be optional, so most mounts here omitted it — and each of those rendered the
 * "semantics unavailable" warning while claiming to test an ordinary simulation. That state is not
 * reachable in the product: `SimulationResult.modelSemantics` is required, `requireAttackContext`
 * rejects a response whose manifest disagrees with the run context, and the bar's only opener needs a
 * loaded result. Tests that describe an impossible run cannot fail for the reason they claim.
 *
 * Every field is load-bearing for that predicate. This is the **no-attack** shape, which the predicate
 * treats as its own case: `attackSelectionPolicy` must be `NOT_MODELED`, `attackEffects` must be *empty*,
 * and no attack point may be selected — copying the attack-run manifest from the budget test below and
 * zeroing its counts produces `false`, not a consistent no-attack run. The attack tests supply their own
 * manifest inline for that reason.
 */
const consistentSemantics: ModelSemantics = {
  attackPointUnit: 'BEHAVIOR_CHANGING_DEVICE_INSTANCE_OR_AUTOMATION_LINK',
  attackSelectionPolicy: 'NOT_MODELED',
  attackEffects: [],
  modeledDeviceAttackPointCount: 0,
  modeledFalsifiableReadingDeviceCount: 0,
  modeledAutomationLinkAttackPointCount: 0,
  modeledAttackPointCount: 0,
  trustPropagationPolicy: 'TARGET_UNTRUSTED_IF_ALL_TRIGGER_SOURCES_UNTRUSTED',
  privacyPropagationPolicy: 'NOT_MODELED',
  labelPropagationScope: 'AUTOMATION_RULE_COMMANDS_ONLY',
  environmentEvolutionEffects: [
    'DECLARED_NUMERIC_RATES_AND_DEVICE_EFFECTS_WITHIN_DOMAIN',
    'UNWRITTEN_DISCRETE_VALUES_NONDETERMINISTIC_WITHIN_DECLARED_DOMAIN',
    'DEVICE_WRITTEN_DISCRETE_VALUES_HOLD_WHEN_NO_DECLARED_EFFECT_APPLIES'
  ],
  localVariableFallbackPolicy: 'STUTTER_WHEN_NO_DECLARED_EVOLUTION'
}

const states = (prefix: string): TraceState[] => [
  {
    stateIndex: 1,
    triggeredRules: [],
    compromisedAutomationLinks: [],
    devices: [{ deviceId: `${prefix}_sensor`, deviceLabel: `${prefix} sensor`, templateName: 'Sensor', modelTokenSource: 'UNKNOWN', state: 'idle', variables: [] }]
  },
  {
    stateIndex: 2,
    triggeredRules: [{ ruleIndex: 0, ruleId: 'rule-1', ruleLabel: `${prefix} activation` }],
    compromisedAutomationLinks: [],
    devices: [{ deviceId: `${prefix}_sensor`, deviceLabel: `${prefix} sensor`, templateName: 'Sensor', modelTokenSource: 'UNKNOWN', state: 'active', variables: [] }]
  },
  {
    stateIndex: 3,
    triggeredRules: [],
    compromisedAutomationLinks: [],
    devices: [{ deviceId: `${prefix}_sensor`, deviceLabel: `${prefix} sensor`, templateName: 'Sensor', modelTokenSource: 'UNKNOWN', state: 'done', variables: [] }]
  }
]

describe('SimulationTimeline', () => {
  it('keeps the playback heading in the timeline scroll flow', () => {
    const sheet = postcss.parse(readFileSync(join(process.cwd(), 'src/styles/board.css'), 'utf8'))
    const positionRules: string[] = []
    sheet.walkRules(rule => {
      if (rule.parent?.type !== 'root') return
      const positions = rule.nodes.filter(node => node.type === 'decl' && node.prop === 'position')
      if (positions.length) positionRules.push(`${rule.selector} { ${positions.join(';')} }`)
    })
    const style = document.createElement('style')
    style.textContent = positionRules.join('\n')
    document.head.appendChild(style)
    const wrapper = mount(SimulationTimeline, {
      attachTo: document.body,
      props: { visible: true, modelSemantics: consistentSemantics, states: states('playback') },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    try {
      // A heading taller than the capped scrollport must scroll away from the seek controls.
      expect(getComputedStyle(wrapper.get('header').element).position).not.toMatch(/^(sticky|fixed|absolute)$/)
    } finally {
      wrapper.unmount()
      style.remove()
    }
  })

  beforeEach(() => {
    Element.prototype.scrollIntoView = vi.fn()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('keeps playing when the terminal is hidden without clearing the selected state', async () => {
    vi.useFakeTimers()
    const wrapper = mount(SimulationTimeline, {
      props: { visible: true, states: states('hidden'), modelSemantics: consistentSemantics },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    await wrapper.get('[data-testid="simulation-timeline-play"]').trigger('click')
    await wrapper.setProps({ panelOpen: false })
    expect(wrapper.emitted('highlight-state')?.some(([state]) => state === null)).not.toBe(true)
    await vi.advanceTimersByTimeAsync(1500)
    expect(wrapper.get('[data-testid="simulation-timeline"]').attributes('data-selected-state-index')).toBe('1')
    expect(wrapper.get('[data-testid="simulation-timeline-play"]').attributes('aria-label')).toBe('Pause')
    wrapper.unmount()
  })

  it('pauses while editing a state number and restores an unfinished entry on blur', async () => {
    vi.useFakeTimers()
    const wrapper = mount(SimulationTimeline, {
      props: { visible: true, states: states('draft'), modelSemantics: consistentSemantics },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    try {
      await wrapper.get('[data-testid="simulation-timeline-play"]').trigger('click')
      const input = wrapper.get('[data-testid="simulation-timeline-step-input"]')
      ;(input.element as HTMLInputElement).value = ''
      await input.trigger('input')
      expect(wrapper.get('[data-testid="simulation-timeline-play"]').attributes('aria-label')).toBe('Play')
      await vi.advanceTimersByTimeAsync(3000)
      expect(wrapper.get('[data-testid="simulation-timeline"]').attributes('data-selected-state-index')).toBe('0')
      await wrapper.get('[data-testid="simulation-timeline-step-input"]').trigger('blur')
      expect((wrapper.get('[data-testid="simulation-timeline-step-input"]').element as HTMLInputElement).value).toBe('1')
    } finally { wrapper.unmount() }
  })

  it.each(['en', 'zh-CN'] as const)('keeps saved-scene drift guidance across replay views in %s', async locale => {
    const previousLocale = appI18n.global.locale.value
    appI18n.global.locale.value = locale
    const wrapper = mount(SimulationTimeline, {
      props: { visible: true, states: states('history'), modelSemantics: consistentSemantics, boardComparison: 'CHANGED' },
      global: { plugins: [appI18n], stubs: { Teleport: true } }
    })
    try {
      for (const view of ['changes', 'details', 'run']) {
        await wrapper.get(`[data-testid="simulation-timeline-${view}-tab"]`).trigger('click')
        const warning = wrapper.get('[data-testid="simulation-board-drift-warning"]')
        expect(warning.isVisible()).toBe(true)
        expect(warning.text()).toMatch(locale === 'en' ? /replay uses the scene saved for this run/i : /回放.*本次运行保存的场景/)
        expect(warning.text()).not.toMatch(/rerun|重新运行/i)
      }
      await wrapper.setProps({ boardComparison: 'UNCHANGED' })
      expect(wrapper.find('[data-testid="simulation-board-drift-warning"]').exists()).toBe(false)
    } finally { wrapper.unmount(); appI18n.global.locale.value = previousLocale }
  })

  it.each(['marker', 'keyboard', 'next', 'number'])('pauses autoplay when seeking with %s', async method => {
    vi.useFakeTimers()
    const wrapper = mount(SimulationTimeline, {
      props: { visible: true, states: states('seek'), modelSemantics: consistentSemantics },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    await wrapper.get('[data-testid="simulation-timeline-play"]').trigger('click')
    expect(wrapper.get('[data-testid="simulation-timeline-play"]').attributes('aria-label')).toBe('Pause')
    if (method === 'marker') {
      await wrapper.get('[data-testid="simulation-timeline-state-1"]').trigger('click')
    } else if (method === 'keyboard') {
      await wrapper.get('[data-testid="simulation-timeline-state-0"]').trigger('keydown', { key: 'ArrowRight' })
    } else if (method === 'next') {
      await wrapper.get('button[aria-label="Next state"]').trigger('click')
    } else {
      await wrapper.get('[data-testid="simulation-timeline-step-input"]').setValue('2')
    }

    expect(wrapper.get('[data-testid="simulation-timeline-play"]').attributes('aria-label')).toBe('Play')
    await vi.advanceTimersByTimeAsync(3000)
    expect(wrapper.get('[data-testid="simulation-timeline"]').attributes('data-selected-state-index')).toBe('1')
    wrapper.unmount()
  })

  it('resets playback focus to the first state whenever it opens', async () => {
    const wrapper = mount(SimulationTimeline, {
      props: {
        visible: true,
        modelSemantics: consistentSemantics,
        states: states('first')
      },
      global: {
        plugins: [i18n], stubs: { Teleport: true }
      }
    })

    await wrapper.get('[data-testid="simulation-timeline-state-2"]').trigger('click')
    expect(wrapper.get('[data-testid="simulation-timeline"]').attributes('data-selected-state-index')).toBe('2')

    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ states: states('second'), visible: true })

    expect(wrapper.get('[data-testid="simulation-timeline"]').attributes('data-selected-state-index')).toBe('0')
    const highlightEvents = wrapper.emitted('highlight-state') || []
    expect(highlightEvents.at(-1)?.[0]).toMatchObject({ selectedStateIndex: 0 })
  })

  it('keeps playback and the selected state while switching terminal tabs', async () => {
    vi.useFakeTimers()
    const wrapper = mount(SimulationTimeline, {
      props: { visible: true, modelSemantics: consistentSemantics, states: states('tabs') },
      slots: { changes: '<p data-testid="test-changes">Before and after values</p>' },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    await wrapper.get('[data-testid="simulation-timeline-play"]').trigger('click')
    await wrapper.get('[data-testid="simulation-timeline-changes-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="test-changes"]').isVisible()).toBe(true)
    expect(wrapper.get('[data-testid="simulation-timeline-play"]').attributes('aria-label')).toBe('Pause')
    await vi.advanceTimersByTimeAsync(1500)
    expect(wrapper.get('[data-testid="simulation-timeline"]').attributes('data-selected-state-index')).toBe('1')
    await wrapper.get('[data-testid="simulation-timeline-changes-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="simulation-timeline-state-1"]').attributes('aria-current')).toBe('step')
    expect(wrapper.emitted('highlight-state')?.some(event => event[0] === null)).toBe(false)
    wrapper.unmount()
  })

  it('does not warn about run semantics for a manifest the product actually produces', () => {
    /*
     * The counterpart to `modelSemantics` being required rather than optional.
     *
     * With it optional, most mounts in this file omitted it, so `isModelSemanticsConsistent` returned false
     * and every one of them rendered the "semantics unavailable" warning below the header — a state the
     * product cannot reach, since `requireAttackContext` rejects such a response before it can be shown. The
     * tests still passed, because none of them asserted on the warning: they described an impossible run and
     * checked something else. This one asserts the warning's *absence*, so a fixture that silently stops
     * satisfying the predicate fails here instead of quietly restoring that condition everywhere.
     */
    const wrapper = mount(SimulationTimeline, {
      props: { visible: true, modelSemantics: consistentSemantics, states: states('semantics') },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    expect(wrapper.text(), 'a consistent manifest is not a reason to warn')
      .not.toContain('Model semantics unavailable')
    // Paired with a positive assertion so the check cannot pass by rendering nothing at all.
    expect(wrapper.text()).toContain('Model Trace Playback')
  })

  it('resets to the first state when a same-length run replaces the visible run', async () => {
    const wrapper = mount(SimulationTimeline, {
      props: { visible: true, modelSemantics: consistentSemantics, states: states('first') },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    await wrapper.get('[data-testid="simulation-timeline-state-2"]').trigger('click')
    await wrapper.setProps({ states: states('replacement') })

    expect(wrapper.get('[data-testid="simulation-timeline"]').attributes('data-selected-state-index')).toBe('0')
    expect((wrapper.emitted('highlight-state') || []).at(-1)?.[0]).toMatchObject({
      selectedStateIndex: 0,
      states: expect.arrayContaining([
        expect.objectContaining({ devices: expect.arrayContaining([
          expect.objectContaining({ deviceId: 'replacement_sensor' })
        ]) })
      ])
    })
  })

  it('returns the control to Play as soon as the final state is displayed', async () => {
    vi.useFakeTimers()
    const wrapper = mount(SimulationTimeline, {
      props: { visible: true, modelSemantics: consistentSemantics, states: states('playback') },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    await wrapper.get('[data-testid="simulation-timeline-play"]').trigger('click')
    await vi.advanceTimersByTimeAsync(3_000)

    expect(wrapper.get('[data-testid="simulation-timeline"]').attributes('data-selected-state-index')).toBe('2')
    expect(wrapper.get('[data-testid="simulation-timeline-play"]').attributes('aria-label')).toBe('Play')
  })

  it('renders run information as a terminal view without opening another surface', async () => {
    const wrapper = mount(SimulationTimeline, {
      props: { visible: true, modelSemantics: consistentSemantics, states: states('details') },
      slots: { run: '<p data-testid="run-content">Saved run diagnostics</p>' },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    await wrapper.get('[data-testid="simulation-timeline-run-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="run-content"]').isVisible()).toBe(true)
    expect(wrapper.get('[data-testid="simulation-timeline-track"]').isVisible()).toBe(true)
    expect(wrapper.emitted('highlight-state')).toBeUndefined()
  })

  it('keeps model coverage in run information while incomplete-model warnings stay visible', async () => {
    const wrapper = mount(SimulationTimeline, {
      props: { visible: true, modelComplete: undefined, modelSemantics: consistentSemantics, states: states('coverage') },
      slots: { run: '<p data-testid="coverage-run-content">Model generation diagnostics</p>' },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    expect(wrapper.find('button[aria-label="Complete model"]').exists()).toBe(false)
    expect(wrapper.find('button[aria-label="Incomplete model"]').exists()).toBe(false)
    await wrapper.setProps({ modelComplete: true })
    expect(wrapper.find('button[aria-label="Complete model"]').exists()).toBe(false)
    await wrapper.get('[data-testid="simulation-timeline-run-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="coverage-run-content"]').isVisible()).toBe(true)
    await wrapper.setProps({ modelComplete: false })
    expect(wrapper.find('button[aria-label="Incomplete model"]').exists()).toBe(false)
    await wrapper.get('[data-testid="simulation-timeline-changes-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="simulation-timeline-incomplete-warning"]').text()).toContain('rules omitted')
    wrapper.unmount()
  })

  it('keeps a shortened simulation horizon visible during playback', () => {
    const wrapper = mount(SimulationTimeline, {
      props: {
        visible: true,
        modelSemantics: consistentSemantics,
        states: states('short'),
        actualSteps: 2,
        requestedSteps: 6
      },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    expect(wrapper.get('[data-testid="simulation-timeline-short-horizon-warning"]').text())
      .toContain('2 of 6')
  })

  it('shows compromised-point count only from runtime global variables', () => {
    const wrapper = mount(SimulationTimeline, {
      props: {
        visible: true,
        modelSemantics: consistentSemantics,
        states: [
          {
            stateIndex: 1,
            triggeredRules: [],
            compromisedAutomationLinks: [],
            devices: [{ deviceId: 'sensor_1', deviceLabel: 'Sensor', templateName: 'Sensor', modelTokenSource: 'UNKNOWN', variables: [] }],
            envVariables: [{ name: 'attackBudget', value: '9', modelTokenSource: 'UNKNOWN' }],
            globalVariables: [{ name: 'compromisedPointCount', value: '4', modelTokenSource: 'UNKNOWN' }]
          }
        ]
      },
      global: {
        plugins: [i18n], stubs: { Teleport: true }
      }
    })

    expect(wrapper.get('[data-testid="simulation-runtime-attack"]').attributes('aria-label')).toBe('Current compromised points: 4')
    expect(wrapper.text()).not.toContain('Current compromised points: 9')
  })

  it('keeps the help concise instead of repeating the run scope', () => {
    const wrapper = mount(SimulationTimeline, {
      props: {
        visible: true,
        states: states('snapshot'),
        isAttack: true,
        attackBudget: 2,
        enablePrivacy: false,
        modelSemantics: {
          attackPointUnit: 'BEHAVIOR_CHANGING_DEVICE_INSTANCE_OR_AUTOMATION_LINK',
          attackSelectionPolicy: 'UP_TO_ATTACK_BUDGET_NONDETERMINISTIC',
          attackEffects: [
            'DECLARED_FALSIFIABLE_READING_NONDETERMINISTIC_WITHIN_DECLARED_DOMAIN',
            'COMMAND_TO_COMPROMISED_TARGET_IS_DROPPED',
            'COMMAND_ON_COMPROMISED_AUTOMATION_LINK_IS_DROPPED'
          ],
          modeledDeviceAttackPointCount: 3,
          modeledFalsifiableReadingDeviceCount: 1,
          modeledAutomationLinkAttackPointCount: 2,
          modeledAttackPointCount: 5,
          trustPropagationPolicy: 'TARGET_UNTRUSTED_IF_ALL_TRIGGER_SOURCES_UNTRUSTED',
          privacyPropagationPolicy: 'NOT_MODELED',
          labelPropagationScope: 'AUTOMATION_RULE_COMMANDS_ONLY',
          environmentEvolutionEffects: [
            'DECLARED_NUMERIC_RATES_AND_DEVICE_EFFECTS_WITHIN_DOMAIN',
            'UNWRITTEN_DISCRETE_VALUES_NONDETERMINISTIC_WITHIN_DECLARED_DOMAIN',
    'DEVICE_WRITTEN_DISCRETE_VALUES_HOLD_WHEN_NO_DECLARED_EFFECT_APPLIES'
          ],
          localVariableFallbackPolicy: 'STUTTER_WHEN_NO_DECLARED_EVOLUTION'
        }
      },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    // Asserted on the help affordance's `text` prop, not on rendered output: these facts moved from a
    // standing disclosure into an `InfoTooltip`, and `ElTooltip` renders its content only once opened,
    // so `wrapper.text()` cannot see prose that is genuinely reachable.
    const helpText = wrapper.findComponent({ name: 'InfoTooltip' }).props('text') as string
    expect(helpText).toContain('Click or drag the timeline')
    expect(helpText).not.toContain('Exhaustive up to')
    expect(helpText).not.toContain('Labels propagate only')
    expect(helpText).not.toContain('Model semantics unavailable')
  })

  it('does not infer attack mechanisms when persisted model semantics are unavailable', () => {
    const wrapper = mount(SimulationTimeline, {
      props: {
        visible: true,
        modelSemantics: consistentSemantics,
        states: states('legacy'),
        isAttack: true,
        attackBudget: 2,
        enablePrivacy: true
      },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    expect(wrapper.text()).toContain('Model semantics unavailable')
    expect(wrapper.text()).not.toContain('Attack budget 2 of')
    expect(wrapper.text()).not.toContain('Privacy propagation modeled')
    expect(wrapper.find('[data-testid="simulation-timeline-track"]').exists()).toBe(true)
  })

  it('does not treat an environment variable named attackBudget as attack count', () => {
    const wrapper = mount(SimulationTimeline, {
      props: {
        visible: true,
        modelSemantics: consistentSemantics,
        states: [
          {
            stateIndex: 1,
            triggeredRules: [],
            compromisedAutomationLinks: [],
            devices: [{ deviceId: 'sensor_1', deviceLabel: 'Sensor', templateName: 'Sensor', modelTokenSource: 'UNKNOWN', variables: [] }],
            envVariables: [{ name: 'attackBudget', value: '9', modelTokenSource: 'UNKNOWN' }]
          }
        ]
      },
      global: {
        plugins: [i18n], stubs: { Teleport: true }
      }
    })

    expect(wrapper.text()).toContain('attackBudget')
    expect(wrapper.text()).not.toContain('Current compromised points: 9')
  })

  it('does not render an invalid historical compromised-point count as NaN', () => {
    const wrapper = mount(SimulationTimeline, {
      props: {
        visible: true,
        modelSemantics: consistentSemantics,
        states: [{
          stateIndex: 1,
          triggeredRules: [],
          compromisedAutomationLinks: [],
          devices: [],
          globalVariables: [{ name: 'compromisedPointCount', value: 'unknown', modelTokenSource: 'UNKNOWN' }]
        }]
      },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    expect(wrapper.text()).not.toContain('NaN')
    expect(wrapper.text()).not.toContain('Current compromised points:')
  })

  it('shows model scope, completeness, and exact triggered-rule history', async () => {
    const wrapper = mount(SimulationTimeline, {
      props: {
        visible: true,
        modelSemantics: consistentSemantics,
        states: states('history'),
        modelComplete: false,
        disabledRuleCount: 2,
        modelSnapshot: {
          capturedAt: '2026-07-12T09:30:00',
          deviceCount: 3,
          ruleCount: 2,
          specificationCount: 0,
          environmentVariableCount: 1,
          deviceTemplateCount: 1,
          templatesFrozen: true
        },
        boardComparison: 'CHANGED',
        currentRuleIds: []
      },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    expect(wrapper.text()).not.toContain('Model execution, not a physical prediction')
    expect(wrapper.get('[data-testid="simulation-timeline-incomplete-warning"]').text())
      .toContain('2 rules omitted')
    // The frozen-snapshot counts are deliberately NOT here any more: the run-details dialog owns
    // them, and repeating them on an overlay whose subject is the canvas behind it cost permanent
    // height for a fact a reader needs once. What must survive is the help affordance itself —
    // its accessible name, since `ElTooltip` renders the prose only once opened.
    expect(wrapper.get('[data-testid="simulation-timeline-snapshot-notice"]')
      .attributes('aria-label')).toContain('Playback controls')
    expect(wrapper.text(), 'the counts must not return to this surface')
      .not.toContain('3 devices, 2 rules, 0 specs')
    // This surface warns only when the board HAS drifted; the full four-way verdict (including
    // "unchanged") belongs to the run-details dialog, which carries `simulation-board-comparison`.
    // A timeline that also announced "unchanged" would spend permanent height saying nothing happened.
    expect(wrapper.get('[data-testid="simulation-board-drift-warning"]').text())
      .toContain('Current input changed')

    await wrapper.get('[data-testid="simulation-timeline-state-1"]').trigger('click')
    expect(wrapper.find('[data-testid="simulation-timeline-triggered-rules"]').exists()).toBe(false)
    const highlighted = wrapper.emitted('highlight-state')!.at(-1)![0] as { selectedStateIndex: number; states: TraceState[] }
    expect(highlighted.selectedStateIndex).toBe(1)
    expect(highlighted.states[1].triggeredRules).toEqual(expect.arrayContaining([expect.objectContaining({ ruleLabel: 'history activation' })]))
  })

  it('keeps historical devices visible and marks state changes in the saved snapshot', async () => {
    const wrapper = mount(SimulationTimeline, {
      props: {
        visible: true,
        modelSemantics: consistentSemantics,
        currentDeviceIds: ['current_sensor'],
        states: [
          {
            stateIndex: 1,
            triggeredRules: [],
            compromisedAutomationLinks: [],
            devices: [
              { deviceId: 'current_sensor', deviceLabel: 'Current sensor', templateName: 'Sensor', modelTokenSource: 'UNKNOWN', state: 'idle', variables: [] },
              { deviceId: 'removed_lock', deviceLabel: 'Removed lock', templateName: 'Lock', modelTokenSource: 'UNKNOWN', state: 'locked', variables: [] }
            ]
          },
          {
            stateIndex: 2,
            triggeredRules: [],
            compromisedAutomationLinks: [],
            devices: [
              { deviceId: 'current_sensor', deviceLabel: 'Current sensor', templateName: 'Sensor', modelTokenSource: 'UNKNOWN', state: 'active', variables: [] },
              {
                deviceId: 'removed_lock',
                deviceLabel: 'Removed lock',
                templateName: 'Lock',
                modelTokenSource: 'UNKNOWN',
                state: 'unlocked',
                compromised: true,
                variables: []
              }
            ]
          }
        ]
      },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    // The help explains navigation; the full saved state stays in State details.
    expect(wrapper.findComponent({ name: 'InfoTooltip' }).props('text') as string)
      .toContain('Click or drag the timeline')

    await wrapper.get('[data-testid="simulation-timeline-state-1"]').trigger('click')
    const devices = wrapper.get('[data-testid="playback-state-devices"]')
    expect(devices.text()).toContain('Current sensor')
    expect(devices.text()).toContain('active')
    expect(devices.text()).toContain('Removed lock')
    expect(devices.text()).toContain('unlocked')
    expect(devices.text()).not.toContain('idle -> active')
    expect(wrapper.get('[data-testid="playback-state-security"]').text()).toContain('Compromised')
    expect(devices.text()).toContain('Historical device is no longer on this board')
  })

  it('shows trust and privacy facts from the selected historical state', () => {
    const wrapper = mount(SimulationTimeline, {
      props: {
        visible: true,
        modelSemantics: consistentSemantics,
        states: [{
          stateIndex: 1,
          triggeredRules: [],
          compromisedAutomationLinks: [],
          devices: [{
            deviceId: 'camera_1',
            deviceLabel: 'Hall camera',
            templateName: 'Camera',
            modelTokenSource: 'UNKNOWN',
            mode: 'CameraMode',
            state: 'recording',
            variables: [{ name: 'motion', value: 'TRUE', trust: 'untrusted', modelTokenSource: 'UNKNOWN' }],
            trustPrivacy: [{ name: 'recording', propertyScope: 'state', mode: 'CameraMode', trust: true }],
            privacies: [{ name: 'video', propertyScope: 'content', privacy: 'private' }]
          }]
        }]
      },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    const security = wrapper.get('[data-testid="playback-state-security"]')
    expect(security.text()).toContain('Untrusted source labels: motion')
    expect(security.text()).toContain('Private-data labels: video')
  })

  it('formats bundled playback tokens at the display boundary without mutating saved states', () => {
    const savedStates: TraceState[] = [{
      stateIndex: 1,
      triggeredRules: [],
      compromisedAutomationLinks: [],
      devices: [{
        deviceId: 'camera_1',
        deviceLabel: 'Hall camera',
        templateName: 'Camera',
        modelTokenSource: 'BUNDLED',
        mode: 'MachineState',
        state: 'on',
        variables: [{ name: 'weather', value: 'sunny', modelTokenSource: 'BUNDLED' }],
        trustPrivacy: [{ name: 'on', propertyScope: 'state', mode: 'MachineState', trust: false }],
        privacies: [{ name: 'photo', propertyScope: 'content', privacy: 'private' }]
      }],
      envVariables: [{ name: 'weather', value: 'sunny', modelTokenSource: 'BUNDLED' }]
    }]
    const canonicalSnapshot = structuredClone(savedStates)
    const translations: Record<string, string> = {
      MachineState: '设备状态',
      on: '开启',
      weather: '天气',
      sunny: '晴朗',
      photo: '照片'
    }
    const formatToken = (_device: TraceState['devices'][number], value: unknown) =>
      translations[String(value)] || String(value ?? '')
    const formatEnvironmentToken = (_name: string, value: unknown) =>
      translations[String(value)] || String(value ?? '')

    const wrapper = mount(SimulationTimeline, {
      props: {
        visible: true,
        modelSemantics: consistentSemantics,
        states: savedStates,
        currentDeviceIds: ['camera_1'],
        formatDeviceModelToken: formatToken,
        formatEnvironmentModelToken: formatEnvironmentToken
      },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    const devices = wrapper.get('[data-testid="playback-state-devices"]')
    expect(devices.text()).toContain('开启')
    expect(devices.text()).toContain('设备状态')
    expect(devices.text()).toContain('天气晴朗')
    expect(wrapper.get('[data-testid="playback-state-security"]').text()).toContain('设备状态: 开启')
    expect(wrapper.get('[data-testid="playback-state-security"]').text()).toContain('照片')
    expect(wrapper.get('[data-testid="playback-state-environment"]').text()).toContain('天气晴朗')
    expect(savedStates).toEqual(canonicalSnapshot)
  })

  it('names a compromised automation link without exposing its generated index', () => {
    const wrapper = mount(SimulationTimeline, {
      props: {
        visible: true,
        modelSemantics: consistentSemantics,
        currentRuleIds: ['rule-1'],
        states: [{
          stateIndex: 1,
          triggeredRules: [],
          compromisedAutomationLinks: [{ ruleIndex: 0, ruleId: 'rule-1', ruleLabel: 'Hall motion turns on light' }],
          devices: []
        }]
      },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    const links = wrapper.get('[data-testid="playback-state-compromised-links"]')
    expect(links.text()).toContain('Hall motion turns on light')
    expect(links.text()).not.toContain('iot_verify_automation_link_compromised_0')
  })

  describe('a changed shared value names the rule that permitted the change', () => {
    // A value that moved with no stated cause is the case a user cannot act on: they cannot tell
    // whether their own rule did it or the model allowed it. So every combination the backend can
    // report must produce a cause, not just the ones that were easy to render first.
    const provenanceStates = (name: string, from: string, to: string): TraceState[] => [
      {
        stateIndex: 1,
        triggeredRules: [],
        compromisedAutomationLinks: [],
        devices: [],
        envVariables: [{ name, value: from, modelTokenSource: 'BUNDLED' }]
      },
      {
        stateIndex: 2,
        triggeredRules: [],
        compromisedAutomationLinks: [],
        devices: [],
        envVariables: [{ name, value: to, modelTokenSource: 'BUNDLED' }]
      }
    ]

    const titleForChangedValue = async (
      name: string,
      from: string,
      to: string,
      provenance: Record<string, unknown>
    ) => {
      const wrapper = mount(SimulationTimeline, {
        props: {
          visible: true,
          modelSemantics: consistentSemantics,
          states: provenanceStates(name, from, to),
          modelSnapshot: {
            capturedAt: '2026-08-01T10:00:00',
            deviceCount: 1,
            ruleCount: 0,
            specificationCount: 0,
            environmentVariableCount: 1,
            deviceTemplateCount: 1,
            templatesFrozen: true,
            environmentProvenance: [provenance]
          } as never
        },
        global: { plugins: [i18n], stubs: { Teleport: true } }
      })
      // Index 1 is the second state -- the one that changed. Index 0 has no predecessor, so it
      // deliberately carries no cause annotation.
      await wrapper.get('[data-testid="simulation-timeline-state-1"]').trigger('click')
      return wrapper.get('[data-testid="playback-state-environment"]').html()
    }

    it('reports a device when exactly one device declares it writes the value', async () => {
      const html = await titleForChangedValue('illuminance', 'dim', 'bright', {
        name: 'illuminance',
        type: 'DISCRETE_ENUM',
        values: ['dim', 'bright'],
        authorship: 'DEVICE_CONTROLLED',
        semantics: 'EXACT',
        writers: [{ deviceVarName: 'light_1', templateName: 'Light', templateSource: 'BUNDLED' }],
        readers: [],
        evolutionSummary: 'Controlled by device light_1 (Light).'
      })
      expect(html).toContain('affected by light_1')
    })

    it('names the abstraction when nobody writes a discrete value', async () => {
      const html = await titleForChangedValue('weather', 'sunny', 'rainy', {
        name: 'weather',
        type: 'DISCRETE_ENUM',
        values: ['sunny', 'rainy'],
        authorship: 'EXOGENOUS',
        semantics: 'ABSTRACTION',
        writers: [],
        readers: [{ deviceVarName: 'weather_1' }],
        evolutionSummary: 'External input.'
      })
      // The user must be able to tell this apart from a change their scene caused.
      expect(html).toContain('external input')
    })

    it('cites the declared interval when nobody writes a numeric value', async () => {
      // This is the most common shared value in the product -- every bundled sensor declares one --
      // and it fell through every branch, so the trace showed a bare "20 -> 21" with no cause.
      const html = await titleForChangedValue('temperature', '20', '21', {
        name: 'temperature',
        type: 'NUMERIC',
        lowerBound: 0,
        upperBound: 100,
        naturalChangeRate: '[-1, 1]',
        authorship: 'EXOGENOUS',
        semantics: 'EXACT',
        writers: [],
        readers: [{ deviceVarName: 'temp_1' }],
        evolutionSummary: 'External input; natural evolution [-1, 1].'
      })
      expect(html).toContain('natural evolution [-1, 1]')
      // Exact semantics must not be presented as the disclosed over-approximation.
      expect(html).not.toContain('external input')
    })

    it('reports the writer count when several devices write the value', async () => {
      const html = await titleForChangedValue('airQuality', 'poor', 'good', {
        name: 'airQuality',
        type: 'DISCRETE_ENUM',
        values: ['poor', 'good'],
        authorship: 'COMPOSED',
        semantics: 'EXACT',
        writers: [
          { deviceVarName: 'purifier_1', templateName: 'Air Purifier', templateSource: 'BUNDLED' },
          { deviceVarName: 'fan_1', templateName: 'Range Hood', templateSource: 'BUNDLED' }
        ],
        readers: [],
        evolutionSummary: 'Affected by 2 devices: purifier_1, fan_1.'
      })
      expect(html).toContain('affected by 2 devices')
    })
  })

  it('keeps navigation separate from execution records and full state values', async () => {
    const wrapper = mount(SimulationTimeline, {
      attachTo: document.body,
      props: { visible: true, modelSemantics: consistentSemantics, states: states('playback') },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    // Past the initial state: there is no rule that produced state 0, so the row renders from step 1 on.
    await wrapper.get('[data-testid="simulation-timeline-state-1"]').trigger('click')

    expect(wrapper.find('[data-testid="simulation-timeline-triggered-rules"]').exists()).toBe(false)

    const values = wrapper.find('[data-testid="playback-state-details"]')
    expect(values.exists(), 'the value tables should still be present').toBe(true)
    expect(values.isVisible(), 'full state values belong to a separate terminal view').toBe(false)
    await wrapper.get('[data-testid="simulation-timeline-details-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="simulation-timeline-details-tab"]').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('[data-testid="playback-state-details"]').isVisible()).toBe(true)
    wrapper.unmount()
  })
})
