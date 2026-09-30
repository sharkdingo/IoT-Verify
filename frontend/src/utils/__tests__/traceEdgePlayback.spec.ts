import { describe, expect, it } from 'vitest'
import type { DeviceEdge } from '@/types/edge'
import {
  formatRuleApiSignalName,
  getTraceValueForEdge,
  buildTraceEdgePlaybackStates,
  buildTraceDevicePlaybackIndex,
  isEdgeConditionSatisfied,
  type TracePlaybackLike
} from '../traceEdgePlayback'

const edgeState = (edge: DeviceEdge, edges: DeviceEdge[], trace: TracePlaybackLike) =>
  buildTraceEdgePlaybackStates(edges, trace).get(edge)!

const apiEdge = (overrides: Partial<DeviceEdge> = {}): DeviceEdge => ({
  id: 'edge-1',
  from: 'button-1',
  to: 'alarm-1',
  fromLabel: 'Button',
  toLabel: 'Alarm',
  fromPos: { x: 0, y: 0 },
  toPos: { x: 1, y: 1 },
  fromApi: 'press',
  toApi: 'siren',
  itemType: 'api',
  relation: '',
  value: '',
  ruleId: 'rule-1',
  ruleIndex: 0,
  sourceIndex: 0,
  ...overrides
})

describe('trace device playback index', () => {
  const early = { deviceId: 'LIGHT_1', state: 'off', compromised: true }
  const later = { deviceId: 'light_1', state: 'on', compromised: false }
  const future = { deviceId: 'future_1', state: 'off', compromised: true }
  const states = [{ devices: [early] }, {}, { devices: [later] }, { devices: [future] }]

  it('carries sparse state forward, compares against the preceding step, and preserves compromise evidence', () => {
    const index = buildTraceDevicePlaybackIndex(states, 2)
    expect(index.current.get('light_1')).toBe(later)
    expect(index.previous.get('light_1')).toBe(early)
    expect(index.compromised).toEqual(new Set(['light_1']))
    expect(index.current.has('future_1')).toBe(false)
    const sparse = buildTraceDevicePlaybackIndex(states, 1)
    expect(sparse.current.get('light_1')).toBe(early)
    expect(sparse.previous.get('light_1')).toBe(early)
  })

  it('has no previous state at initialization and bounds selection to the available trace', () => {
    expect(buildTraceDevicePlaybackIndex(states, 0).previous.size).toBe(0)
    expect(buildTraceDevicePlaybackIndex(states, undefined).compromised.size).toBe(0)
    expect(buildTraceDevicePlaybackIndex(states, -1).compromised.size).toBe(0)
    const outOfRange = buildTraceDevicePlaybackIndex(states, 10)
    expect(outOfRange.current.get('future_1')).toBe(future)
    expect(outOfRange.previous.get('future_1')).toBe(future)
    expect(buildTraceDevicePlaybackIndex(undefined, 0).current.size).toBe(0)
  })

  it('indexes each historical device once instead of scanning all devices for each node', () => {
    let reads = 0
    const devices = Array.from({ length: 200 }, (_, index) => ({
      get deviceId() { reads++; return `light_${index}` }, state: 'on'
    }))
    const history = Array.from({ length: 50 }, () => ({ devices }))
    const index = buildTraceDevicePlaybackIndex(history, 49)
    expect(index.current.size).toBe(200)
    expect(index.previous.size).toBe(200)
    expect(reads).toBeLessThanOrEqual(200 * 50)
  })
})

describe('trace edge playback', () => {
  it('lights all sources of one rule but rejects a malformed sibling index', () => {
    const first = apiEdge()
    const second = apiEdge({ id: 'edge-2', sourceIndex: 1 })
    const trace = { selectedStateIndex: 0, states: [{
      triggeredRules: [{ ruleIndex: 5, ruleId: 'rule-1' }],
      compromisedAutomationLinks: [{ ruleIndex: 5, ruleId: 'rule-1' }]
    }] }
    const states = buildTraceEdgePlaybackStates([first, second], trace)
    for (const edge of [first, second]) {
      expect(states.get(edge)).toEqual({ traceActive: true, linkCompromised: true, shouldAnimate: false })
    }
    const malformed = apiEdge({ id: 'bad-index', ruleIndex: undefined })
    const invalidStates = buildTraceEdgePlaybackStates([first, malformed, second], trace)
    expect([...invalidStates.values()].every(state => !state.traceActive && !state.linkCompromised)).toBe(true)
  })

  it('uses linear identity reads as the rule graph grows', () => {
    const count = 400
    let identityReads = 0
    const edges = Array.from({ length: count }, (_, index) => {
      const edge = apiEdge({ id: `edge-${index}`, ruleIndex: index })
      Object.defineProperty(edge, 'ruleId', { get: () => {
        identityReads++
        return `rule-${index}`
      } })
      return edge
    })
    const rules = Array.from({ length: count }, (_, index) => ({ ruleId: `rule-${index}`, ruleIndex: index }))
    const states = buildTraceEdgePlaybackStates(edges, {
      selectedStateIndex: 0, states: [{ triggeredRules: rules, compromisedAutomationLinks: rules.slice(0, 200) }]
    })
    expect(states.size).toBe(count)
    expect([...states.values()].filter(state => state.shouldAnimate)).toHaveLength(200)
    // Count operations rather than time: a per-edge graph scan violates this bound on any machine.
    expect(identityReads).toBeLessThanOrEqual(count * 6)
  })

  it('uses NuSMV API signal variables when highlighting rule edges', () => {
    const edge = apiEdge()
    const trace = {
      selectedStateIndex: 1,
      states: [
        {
          triggeredRules: [],
          devices: [
            { deviceId: 'button_1', variables: [{ name: 'press_a', value: 'FALSE' }] }
          ]
        },
        {
          triggeredRules: [{ ruleIndex: 0, ruleId: 'rule-1', ruleLabel: 'Press starts alarm' }],
          devices: [
            { deviceId: 'button_1', variables: [{ name: 'press_a', value: 'TRUE' }] }
          ]
        }
      ]
    }

    expect(getTraceValueForEdge(edge, trace, 1)).toBe('TRUE')
    expect(isEdgeConditionSatisfied(edge, trace, 1)).toBe(true)
    expect(edgeState(edge, [edge], trace).traceActive).toBe(true)
  })

  it('does not treat an API signal as active when the trace signal is false', () => {
    const edge = apiEdge()
    const trace = {
      selectedStateIndex: 1,
      states: [
        {
          triggeredRules: [],
          devices: [
            { deviceId: 'button_1', variables: [{ name: 'press_a', value: 'FALSE' }] }
          ]
        },
        {
          triggeredRules: [],
          devices: [
            { deviceId: 'button_1', variables: [{ name: 'press_a', value: 'FALSE' }] }
          ]
        }
      ]
    }

    expect(isEdgeConditionSatisfied(edge, trace, 1)).toBe(false)
    expect(edgeState(edge, [edge], trace).traceActive).toBe(false)
  })

  it('does not infer a rule firing when the backend explicitly reports no triggered rules', () => {
    const edge = apiEdge({ itemType: 'state', fromApi: 'state', relation: '=', value: 'pressed' })
    const trace = {
      selectedStateIndex: 1,
      states: [
        { triggeredRules: [], devices: [{ deviceId: 'button_1', state: 'pressed', variables: [] }] },
        { triggeredRules: [], devices: [{ deviceId: 'button_1', state: 'pressed', variables: [] }] }
      ]
    }

    expect(isEdgeConditionSatisfied(edge, trace, 0)).toBe(true)
    expect(edgeState(edge, [edge], trace).traceActive).toBe(false)
  })

  it('matches historical playback by a unique stable rule id rather than current list position', () => {
    const edge = apiEdge({ ruleIndex: 99 })
    const trace = {
      selectedStateIndex: 1,
      states: [
        { triggeredRules: [], devices: [] },
        { triggeredRules: [{ ruleIndex: 0, ruleId: 'rule-1', ruleLabel: 'Historical rule' }], devices: [] }
      ]
    }

    expect(edgeState(edge, [edge], trace).traceActive).toBe(true)
  })

  it('does not guess a current edge when historical rule ids are duplicated', () => {
    const first = apiEdge({ id: 'edge-1', ruleId: 'duplicate', ruleIndex: 0 })
    const second = apiEdge({ id: 'edge-2', ruleId: 'duplicate', ruleIndex: 1 })
    const trace = {
      selectedStateIndex: 1,
      states: [
        { triggeredRules: [], devices: [] },
        { triggeredRules: [{ ruleIndex: 1, ruleId: 'duplicate', ruleLabel: 'Second rule' }], devices: [] }
      ]
    }

    expect(edgeState(first, [first, second], trace).traceActive).toBe(false)
    expect(edgeState(second, [first, second], trace).traceActive).toBe(false)
  })

  it('does not match when only one side has a rule id, because the snapshots disagree on identity', () => {
    // The edge carries `rule-1` while the trace's rule carries none. Matching these on position would
    // be a coincidence rather than evidence — the two snapshots do not agree about what identifies a
    // rule, so neither id nor index is trustworthy across them.
    const edge = apiEdge()
    const trace = {
      selectedStateIndex: 0,
      states: [{ triggeredRules: [{ ruleIndex: 0, ruleId: null, ruleLabel: 'No id' }], devices: [] }]
    }

    expect(edgeState(edge, [edge], trace).traceActive).toBe(false)
  })

  /*
   * When BOTH sides lack an id, position is the only identity available — and here it is sound.
   *
   * `TraceTriggeredRuleDto.ruleId` is nullable ("when the submitted rule had one") and
   * `playbackScene.ts` sets the edge's `ruleId` to `undefined` for those same rules, so the two lose it
   * together. Requiring an id then meant no edge ever lit: the rail named a rule the canvas ignored,
   * and the UI blamed board drift, which was false.
   *
   * This does not reintroduce what the id-only rule forbade. That rule's recorded reason is "ambiguous
   * or id-less evidence is left unhighlighted instead of guessing from a *current list position*" — the
   * hazard is the live board, whose rules may have been reordered since the run. During playback
   * `allEdges` resolves to the frozen scene (`Board.vue:1641`), and both callers require
   * `highlightedTrace`, which only exists while replaying. So both indices index the one submitted rule
   * list, which `ModelPlaybackSceneSnapshot.copyRules` maps one-to-one and never filters.
   */
  it('matches an id-less rule by frozen list position when neither side has an id', () => {
    const edge = apiEdge({ ruleId: undefined, ruleIndex: 2 })
    const trace = {
      selectedStateIndex: 0,
      states: [{ triggeredRules: [{ ruleIndex: 2, ruleId: null, ruleLabel: 'Unnamed rule' }], devices: [] }]
    }

    expect(edgeState(edge, [edge], trace).traceActive).toBe(true)
  })

  it('does not match an id-less rule at a different frozen position', () => {
    const edge = apiEdge({ ruleId: undefined, ruleIndex: 2 })
    const trace = {
      selectedStateIndex: 0,
      states: [{ triggeredRules: [{ ruleIndex: 5, ruleId: null, ruleLabel: 'Other rule' }], devices: [] }]
    }

    expect(edgeState(edge, [edge], trace).traceActive).toBe(false)
  })

  it('animates only a delivered rule and stops a compromised link', () => {
    const edge = apiEdge({ ruleIndex: 99 })
    const other = apiEdge({ id: 'edge-2', ruleId: 'rule-2' })
    const trace = {
      selectedStateIndex: 0,
      states: [{
        triggeredRules: [
          { ruleIndex: 0, ruleId: 'rule-1', ruleLabel: 'Blocked historical rule' },
          { ruleIndex: 1, ruleId: 'rule-2', ruleLabel: 'Delivered rule' }
        ],
        compromisedAutomationLinks: [{ ruleIndex: 0, ruleId: 'rule-1', ruleLabel: 'Historical rule' }],
        devices: []
      }]
    }

    expect(edgeState(edge, [edge, other], trace).linkCompromised).toBe(true)
    expect(edgeState(other, [edge, other], trace).linkCompromised).toBe(false)
    expect(edgeState(edge, [edge, other], trace).shouldAnimate).toBe(false)
    expect(edgeState(other, [edge, other], trace).shouldAnimate).toBe(true)
  })

  it('does not imply command delivery outside playback or for an idle rule', () => {
    const edge = apiEdge()

    expect(edgeState(edge, [edge], null).shouldAnimate).toBe(false)
    expect(edgeState(edge, [edge], {
      selectedStateIndex: 0,
      states: [{ triggeredRules: [], compromisedAutomationLinks: [], devices: [] }]
    }).shouldAnimate).toBe(false)
  })

  it('reads environment-pool variables for variable rule edges', () => {
    const edge = apiEdge({
      itemType: 'variable',
      fromApi: 'temperature',
      relation: '>',
      value: '28'
    })
    const trace = {
      selectedStateIndex: 0,
      states: [
        {
          devices: [{ deviceId: 'button_1', variables: [] }],
          envVariables: [{ name: 'temperature', value: '31' }]
        }
      ]
    }

    expect(getTraceValueForEdge(edge, trace, 0)).toBe('31')
    expect(isEdgeConditionSatisfied(edge, trace, 0)).toBe(true)
  })

  it('treats a_ as part of literal environment variable names during trace playback', () => {
    const edge = apiEdge({
      itemType: 'variable',
      fromApi: 'a_temperature',
      relation: '>',
      value: '28'
    })

    expect(getTraceValueForEdge(edge, {
      selectedStateIndex: 0,
      states: [
        {
          devices: [{ deviceId: 'button_1', variables: [] }],
          envVariables: [{ name: 'a_temperature', value: '31' }]
        }
      ]
    }, 0)).toBe('31')

    expect(getTraceValueForEdge(edge, {
      selectedStateIndex: 0,
      states: [
        {
          devices: [{ deviceId: 'button_1', variables: [] }],
          envVariables: [{ name: 'temperature', value: '31' }]
        }
      ]
    }, 0)).toBeNull()
  })

  it('does not let variable edges match API signal names implicitly', () => {
    const edge = apiEdge({
      itemType: 'variable',
      fromApi: 'press',
      relation: '=',
      value: 'TRUE'
    })
    const trace = {
      selectedStateIndex: 0,
      states: [
        {
          devices: [
            { deviceId: 'button_1', variables: [{ name: 'press_a', value: 'TRUE' }] }
          ]
        }
      ]
    }

    expect(getTraceValueForEdge(edge, trace, 0)).toBeNull()
    expect(isEdgeConditionSatisfied(edge, trace, 0)).toBe(false)
  })

  it('keeps API signal naming aligned with the SMV generator', () => {
    expect(formatRuleApiSignalName('turn on')).toBe('turn_on_a')
    expect(getTraceValueForEdge(apiEdge({ fromApi: 'turn on' }), {
      selectedStateIndex: 0,
      states: [
        {
          devices: [
            { deviceId: 'button_1', variables: [{ name: 'turn_on_a', value: 'TRUE' }] }
          ]
        }
      ]
    }, 0)).toBe('TRUE')
  })
})
