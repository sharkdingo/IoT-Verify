// @vitest-environment jsdom
import { mount, type ComponentMountingOptions } from '@vue/test-utils'
import { effectScope, nextTick, ref, watch } from 'vue'
import { createI18n } from 'vue-i18n'
import { i18n as appI18n } from '@/assets/i18n'
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest'
import SimulationTimeline from '../SimulationTimeline.vue'
import { usePlaybackCursor } from '@/views/board/usePlaybackCursor'
import type { TraceState } from '@/types/verify'
import type { ModelSemantics } from '@/types/modelSemantics'
import { formatModelTokenBySource } from '@/utils/modelTokenDisplay'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: {
    en: appI18n.global.getLocaleMessage('en'),
    'zh-CN': appI18n.global.getLocaleMessage('zh-CN')
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

type TimelineMountOptions = Omit<ComponentMountingOptions<typeof SimulationTimeline>, 'props'> & {
  props: Omit<InstanceType<typeof SimulationTimeline>['$props'], 'selectedStateIndex' | 'playing'>
}

const mountTimeline = (options: TimelineMountOptions) => {
  const scope = effectScope()
  const cursor = scope.run(() => usePlaybackCursor(ref(options.props.states.length)))!
  onTestFinished(() => scope.stop())
  const wrapper = mount(SimulationTimeline, {
    ...options,
    props: {
      ...options.props,
      selectedStateIndex: cursor.selectedStateIndex.value,
      playing: cursor.isPlaying.value,
      onSeek: cursor.seek,
      onTogglePlay: cursor.toggle,
      onScrubStart: cursor.stop
    }
  })
  scope.run(() => watch([cursor.selectedStateIndex, cursor.isPlaying], ([selectedStateIndex, playing]) => {
    void wrapper.setProps({ selectedStateIndex, playing })
  }))
  return wrapper
}

describe('SimulationTimeline', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'en'
    Element.prototype.scrollIntoView = vi.fn()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders the Board-selected step and sends seek intent without selecting a step locally', async () => {
    const wrapper = mount(SimulationTimeline, {
      props: { states: states('controlled'), modelSemantics: consistentSemantics,
        selectedStateIndex: 1, playing: false },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    try {
      const timeline = wrapper.get('[data-testid="simulation-timeline"]')
      expect(timeline.attributes('data-selected-state-index')).toBe('1')
      await wrapper.get('button[aria-label="Next state"]').trigger('click')
      expect(wrapper.emitted('seek')).toEqual([[2]])
      expect(timeline.attributes('data-selected-state-index')).toBe('1')
    } finally { wrapper.unmount() }
  })

  it('keeps playing when the terminal is hidden without clearing the selected state', async () => {
    vi.useFakeTimers()
    const wrapper = mountTimeline({
      props: { states: states('hidden'), modelSemantics: consistentSemantics },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    await wrapper.get('[data-testid="simulation-timeline-play"]').trigger('click')
    await wrapper.setProps({ panelOpen: false })
    await vi.advanceTimersByTimeAsync(1500)
    expect(wrapper.get('[data-testid="simulation-timeline"]').attributes('data-selected-state-index')).toBe('1')
    expect(wrapper.get('[data-testid="simulation-timeline-play"]').attributes('aria-label')).toBe('Pause')
    wrapper.unmount()
  })

  it('pauses while editing a state number and restores an unfinished entry on blur', async () => {
    vi.useFakeTimers()
    const wrapper = mountTimeline({
      props: { states: states('draft'), modelSemantics: consistentSemantics },
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

  it('keeps saved-scene drift guidance across replay views', async () => {
    const wrapper = mountTimeline({
      props: { states: states('history'), modelSemantics: consistentSemantics, boardComparison: 'CHANGED' },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    try {
      for (const view of ['changes', 'details', 'run']) {
        await wrapper.get(`[data-testid="simulation-timeline-${view}-tab"]`).trigger('click')
        const warning = wrapper.get('[data-testid="simulation-board-drift-warning"]')
        expect(warning.isVisible()).toBe(true)
        expect(warning.text()).toMatch(/replay uses the scene saved for this run/i)
        expect(warning.text()).not.toMatch(/rerun|重新运行/i)
      }
      await wrapper.setProps({ boardComparison: 'SEMANTICS_CHANGED' })
      expect(wrapper.get('[data-testid="simulation-board-drift-warning"]').text()).toMatch(/modeling version/)
      i18n.global.locale.value = 'zh-CN'
      await nextTick()
      expect(wrapper.get('[data-testid="simulation-board-drift-warning"]').text()).toMatch(/建模版本/)
      await wrapper.setProps({ boardComparison: 'CHANGED' })
      expect(wrapper.get('[data-testid="simulation-board-drift-warning"]').text()).toMatch(/回放.*本次运行保存的场景/)
      await wrapper.setProps({ boardComparison: 'UNCHANGED' })
      expect(wrapper.find('[data-testid="simulation-board-drift-warning"]').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it.each(['marker', 'keyboard', 'next', 'number'])('pauses autoplay when seeking with %s', async method => {
    vi.useFakeTimers()
    const wrapper = mountTimeline({
      props: { states: states('seek'), modelSemantics: consistentSemantics },
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

  it('keeps playback and the selected state while switching terminal tabs', async () => {
    vi.useFakeTimers()
    const wrapper = mountTimeline({
      props: { modelSemantics: consistentSemantics, states: states('tabs') },
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
    const wrapper = mountTimeline({
      props: { modelSemantics: consistentSemantics, states: states('semantics') },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    expect(wrapper.text(), 'a consistent manifest is not a reason to warn')
      .not.toContain('Attack and label-propagation scope cannot be confirmed')
    // Paired with a positive assertion so the check cannot pass by rendering nothing at all.
    expect(wrapper.text()).toContain('Model Trace Playback')
  })

  it('returns the control to Play as soon as the final state is displayed', async () => {
    vi.useFakeTimers()
    const wrapper = mountTimeline({
      props: { modelSemantics: consistentSemantics, states: states('playback') },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    await wrapper.get('[data-testid="simulation-timeline-play"]').trigger('click')
    await vi.advanceTimersByTimeAsync(3_000)

    expect(wrapper.get('[data-testid="simulation-timeline"]').attributes('data-selected-state-index')).toBe('2')
    expect(wrapper.get('[data-testid="simulation-timeline-play"]').attributes('aria-label')).toBe('Play')
  })

  it('renders run information as a terminal view without opening another surface', async () => {
    const wrapper = mountTimeline({
      props: { modelSemantics: consistentSemantics, states: states('details') },
      slots: { run: '<p data-testid="run-content">Saved run diagnostics</p>' },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    await wrapper.get('[data-testid="simulation-timeline-run-tab"]').trigger('click')
    expect(wrapper.get('[data-testid="run-content"]').isVisible()).toBe(true)
    expect(wrapper.get('[data-testid="simulation-timeline-track"]').isVisible()).toBe(true)
  })

  it('keeps model coverage in run information while incomplete-model warnings stay visible', async () => {
    const wrapper = mountTimeline({
      props: { modelComplete: undefined, modelSemantics: consistentSemantics, states: states('coverage') },
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
    expect(wrapper.get('[data-testid="simulation-timeline-incomplete-warning"]').text()).toContain('does not represent the complete board')
    wrapper.unmount()
  })

  it('keeps a shortened simulation horizon visible during playback', () => {
    const wrapper = mountTimeline({
      props: {
        modelSemantics: consistentSemantics,
        states: states('short'),
        actualSteps: 2,
        requestedSteps: 6
      },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    expect(wrapper.get('[data-testid="simulation-timeline-short-horizon-warning"]').text())
      .toContain('Only 2 steps were parsed out of 6 requested')
  })

  it('shows compromised-point count only from runtime global variables', () => {
    const wrapper = mountTimeline({
      props: {
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

  it('does not infer attack mechanisms when persisted model semantics are unavailable', () => {
    const wrapper = mountTimeline({
      props: {
        modelSemantics: consistentSemantics,
        states: states('legacy'),
        isAttack: true,
        attackBudget: 2,
        enablePrivacy: true
      },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    expect(wrapper.text()).toContain('Attack and label-propagation scope cannot be confirmed')
    expect(wrapper.text()).not.toMatch(/(?:attack|compromise) budget\s*2/i)
    expect(wrapper.text()).not.toContain('Sensitivity-label propagation modeled')
    expect(wrapper.find('[data-testid="simulation-runtime-attack"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="simulation-timeline-track"]').exists()).toBe(true)
  })

  it('does not treat an environment variable named attackBudget as attack count', () => {
    const wrapper = mountTimeline({
      props: {
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
    expect(wrapper.find('[data-testid="simulation-runtime-attack"]').exists()).toBe(false)
  })

  it('does not render an invalid historical compromised-point count as NaN', () => {
    const wrapper = mountTimeline({
      props: {
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
    expect(wrapper.find('[data-testid="simulation-runtime-attack"]').exists()).toBe(false)
  })

  it('shows model scope, completeness, and exact triggered-rule history', async () => {
    const wrapper = mountTimeline({
      props: {
        modelSemantics: consistentSemantics,
        states: states('history'),
        modelComplete: false,
        disabledRuleCount: 2,
        boardComparison: 'CHANGED',
        currentRuleIds: []
      },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    expect(wrapper.text()).not.toContain('Model execution, not a physical prediction')
    expect(wrapper.get('[data-testid="simulation-timeline-incomplete-warning"]').text())
      .toContain('2 rule(s) were omitted')
    // The frozen-snapshot counts are deliberately NOT here any more: the run-details dialog owns
    // them, and repeating them on an overlay whose subject is the canvas behind it cost permanent
    // height for a fact a reader needs once. What must survive is the help affordance itself —
    // its accessible name, since `ElTooltip` renders the prose only once opened.
    expect(wrapper.get('[data-testid="simulation-timeline-snapshot-notice"]')
      .attributes('aria-label')).toContain('Playback controls')
    expect(wrapper.text(), 'the counts must not return to this surface')
      .not.toMatch(/\d+ devices, \d+ rules, \d+ (?:specs|specifications)/)
    // This surface warns only when the board HAS drifted; the full four-way verdict (including
    // "unchanged") belongs to the run-details dialog, which carries `simulation-board-comparison`.
    // A timeline that also announced "unchanged" would spend permanent height saying nothing happened.
    expect(wrapper.get('[data-testid="simulation-board-drift-warning"]').text())
      .toContain('model differs from the current Board model')

    await wrapper.get('[data-testid="simulation-timeline-state-1"]').trigger('click')
    expect(wrapper.find('[data-testid="simulation-timeline-triggered-rules"]').exists()).toBe(false)
    expect(wrapper.emitted('seek')).toEqual([[1]])
  })

  it('keeps historical devices visible and marks state changes in the saved snapshot', async () => {
    const wrapper = mountTimeline({
      props: {
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
    expect(devices.text()).toContain('Device no longer on the current board')
  })

  it('shows trust and privacy facts from the selected historical state', () => {
    const wrapper = mountTimeline({
      props: {
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
    expect(security.text()).toContain('Untrusted sources: motion')
    expect(security.text()).toContain('Sensitive data: video')
  })

  it('formats bundled playback tokens at the display boundary without mutating saved states', () => {
    i18n.global.locale.value = 'zh-CN'
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
    const wrapper = mountTimeline({
      props: {
        modelSemantics: consistentSemantics,
        states: savedStates,
        currentDeviceIds: ['camera_1'],
        formatDeviceModelToken: (device, value) => formatModelTokenBySource(device.modelTokenSource, value, i18n.global.t),
        formatEnvironmentModelToken: (_name, value) => formatModelTokenBySource('BUNDLED', value, i18n.global.t)
      },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    const devices = wrapper.get('[data-testid="playback-state-devices"]')
    expect(devices.findAll('dt, dd').map(row => row.text())).toEqual(['设备状态', '开启', '天气', '晴'])
    expect(wrapper.get('[data-testid="playback-state-security"]').text()).toContain('设备状态: 开启')
    expect(wrapper.get('[data-testid="playback-state-security"]').text()).toContain('照片')
    const environment = wrapper.get('[data-testid="playback-state-environment"]')
    expect(environment.findAll('dt, dd').map(row => row.text())).toEqual(['天气', '晴'])
    expect(savedStates).toEqual(canonicalSnapshot)
  })

  it('names a compromised automation link without exposing its generated index', () => {
    const wrapper = mountTimeline({
      props: {
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

  it('shows the selected environment value without repeating the change in state details', async () => {
    const wrapper = mountTimeline({
      props: {
        modelSemantics: consistentSemantics,
        states: ['23', '0'].map((value, index): TraceState => ({
          stateIndex: index + 1,
          triggeredRules: [],
          compromisedAutomationLinks: [],
          devices: [],
          envVariables: [{ name: 'time', value, modelTokenSource: 'BUNDLED' }]
        }))
      },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })

    await wrapper.get('[data-testid="simulation-timeline-state-1"]').trigger('click')
    await wrapper.get('[data-testid="simulation-timeline-details-tab"]').trigger('click')

    const environment = wrapper.get('[data-testid="playback-state-environment"]')
    expect(environment.isVisible()).toBe(true)
    expect(environment.get('dt').text()).toBe('time')
    expect(environment.get('dd').text()).toBe('0')
    expect(environment.find('[title]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps navigation separate from execution records and full state values', async () => {
    const wrapper = mountTimeline({
      attachTo: document.body,
      props: { modelSemantics: consistentSemantics, states: states('playback') },
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
