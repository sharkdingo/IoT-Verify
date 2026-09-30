import { describe, it, expect } from 'vitest'
import {
  canOpenTracePlayback,
  deriveTraceContext,
  formatTraceSpec,
  collectPlaybackDeviceIds,
  isPlaybackDeviceAttacked,
  playbackDeviceChanged,
  playbackDeviceChangeDetails,
  playbackEnvironmentChangeDetails,
  playbackDeviceSecurityFacts,
  playbackDeviceSummaryParts,
  formatPlaybackSecurityLabel
} from '../traceView'

describe('canOpenTracePlayback', () => {
  it('allows playback when no conflicting panel is open', () => {
    const r = canOpenTracePlayback({ simulationVisible: false, recommendationPanelVisible: false })
    expect(r.allowed).toBe(true)
    expect(r.reasonCode).toBeNull()
  })

  it('blocks playback while the simulation timeline is visible', () => {
    const r = canOpenTracePlayback({ simulationVisible: true, recommendationPanelVisible: false })
    expect(r.allowed).toBe(false)
    expect(r.reasonCode).toBe('SIMULATION_VISIBLE')
  })

  it('blocks playback while the recommendations panel is visible', () => {
    const r = canOpenTracePlayback({ simulationVisible: false, recommendationPanelVisible: true })
    expect(r.allowed).toBe(false)
    expect(r.reasonCode).toBe('RECOMMENDATION_VISIBLE')
  })

  it('prefers the simulation message when both are open', () => {
    const r = canOpenTracePlayback({ simulationVisible: true, recommendationPanelVisible: true })
    expect(r.allowed).toBe(false)
    expect(r.reasonCode).toBe('SIMULATION_VISIBLE')
  })

  it('formats all specification template shapes for trace feedback', () => {
    const condition = {
      deviceId: 'door_1',
      deviceLabel: 'Front Door',
      targetType: 'variable',
      variableSource: 'environment',
      key: 'contact',
      relation: 'NOT_IN',
      value: 'closed,locked'
    }

    expect(formatTraceSpec(JSON.stringify({
      templateId: '1',
      templateLabel: 'Always',
      aConditions: [condition]
    }))).toBe('Always: the actual contact in the home not in "closed,locked"')

    // A trust or privacy condition is about a label on a target, not the target's own value.
    // Rendering it bare put the label value where a state value belongs, so demo specification 6 read
    // `Front Door.LockState = "private"` — impossible, since LockState's values are locked/unlocked.
    // `utils/spec.ts` already wrapped these correctly; this renderer did not, so the two disagreed and
    // the user had no way to tell which described their property.
    expect(formatTraceSpec(JSON.stringify({
      templateId: '1',
      templateLabel: 'Always',
      aConditions: [{
        deviceLabel: 'Front Door', targetType: 'privacy', key: 'LockState',
        relation: 'EQ', value: 'private'
      }]
    }))).toBe('Always: sensitivity(Front Door.LockState) = "private"')

    expect(formatTraceSpec(JSON.stringify({
      templateId: '1',
      templateLabel: 'Always',
      aConditions: [{
        deviceLabel: 'Front Door', targetType: 'trust', key: 'LockState',
        relation: 'EQ', value: 'untrusted'
      }]
    }))).toBe('Always: controlSource(Front Door.LockState) = "untrusted"')

    expect(formatTraceSpec(JSON.stringify({
      templateId: '5',
      templateLabel: 'Response',
      ifConditions: [{ ...condition, relation: 'EQ', value: 'open' }],
      thenConditions: [{ ...condition, key: 'alarm', relation: 'GTE', value: '1' }]
    }))).toBe('Response: IF the actual contact in the home = "open", '
      + 'THEN eventually the actual alarm in the home >= "1"')

    expect(formatTraceSpec(JSON.stringify({
      templateId: '7',
      templateLabel: 'Safety',
      aConditions: [condition]
    }))).toBe('Safety: the actual contact in the home not in "closed,locked" '
      + 'must not hold with an untrusted control-source label')
  })

  it('distinguishes the two variable questions a counterexample can be about', () => {
    // A verdict is unreadable without knowing which value it answered: the two diverge exactly when
    // the device is falsifying its readings, which is what most counterexamples are about.
    const spec = (variableSource?: string) => formatTraceSpec(JSON.stringify({
      templateId: '1',
      templateLabel: 'Always',
      aConditions: [{
        deviceLabel: 'Front Door',
        targetType: 'variable',
        ...(variableSource ? { variableSource } : {}),
        key: 'contact',
        relation: 'EQ',
        value: 'closed'
      }]
    }))

    expect(spec('environment')).toBe('Always: the actual contact in the home = "closed"')
    expect(spec('reported')).toBe('Always: the contact Front Door reports = "closed"')
    expect(spec('environment')).not.toBe(spec('reported'))
    // A stored condition with no recorded source is marked, not shown as either answer.
    expect(spec()).toBe('Always: Front Door contact (which value it means was not recorded) = "closed"')
  })

  it('uses localized trace wording instead of hard-coded English', () => {
    const translate = (key: string, params: Record<string, unknown> = {}) => {
      const messages: Record<string, string> = {
        'app.traceConditionAnd': ' 且 ',
        'app.traceSpecNever': '不得发生：{condition}',
        'app.relationNotIn': '不属于',
        'app.unknownSpecification': '未知规约',
        'app.unknownDevice': '未知设备',
        'app.state': '状态',
        'app.value': '值'
      }
      return (messages[key] || key).replace(/\{(\w+)\}/g, (_match, name) => String(params[name] ?? ''))
    }

    expect(formatTraceSpec({
      templateId: '3',
      aConditions: [{
        deviceLabel: '玄关相机',
        key: 'state',
        relation: 'NOT_IN',
        value: 'off'
      }]
    }, translate)).toBe('不得发生：玄关相机.state 不属于 "off"')
  })
})

describe('deriveTraceContext', () => {
  it('uses the trace own context when isAttack is set', () => {
    const ctx = deriveTraceContext({ isAttack: true, attackBudget: 12 })
    expect(ctx).toEqual({ isAttack: true, attackBudget: 12, enablePrivacy: false })
  })

  it('reflects a non-attack trace from its own snapshot', () => {
    const ctx = deriveTraceContext({ isAttack: false, attackBudget: 0 })
    expect(ctx).toEqual({ isAttack: false, attackBudget: 0, enablePrivacy: false })
  })

  it('fails closed to non-attack defaults when context is missing', () => {
    const ctx = deriveTraceContext({ isAttack: undefined, attackBudget: undefined })
    expect(ctx).toEqual({ isAttack: false, attackBudget: 0, enablePrivacy: false })
  })

  it('uses non-attack defaults when trace is null', () => {
    const ctx = deriveTraceContext(null)
    expect(ctx).toEqual({ isAttack: false, attackBudget: 0, enablePrivacy: false })
  })

  it('defaults attackBudget to 0 when an attack trace omits it', () => {
    const ctx = deriveTraceContext({ isAttack: true, attackBudget: undefined })
    expect(ctx).toEqual({ isAttack: true, attackBudget: 0, enablePrivacy: false })
  })

  it('uses the trace privacy-model context instead of the current form', () => {
    const ctx = deriveTraceContext({ isAttack: false, attackBudget: 0, enablePrivacy: true })
    expect(ctx.enablePrivacy).toBe(true)
  })
})

describe('playback device facts', () => {
  const idle = {
    deviceId: 'sensor_1',
    state: 'idle',
    compromised: false,
    variables: [
      { name: 'temperature', value: '20' }
    ]
  }

  it('keeps user-facing state, values, and compromise status separate', () => {
    expect(playbackDeviceSummaryParts(idle)).toEqual(['idle', 'temperature=20'])
    expect(isPlaybackDeviceAttacked(idle)).toBe(false)
  })

  // An affect-only shared declaration (`Reads: false`) reaches the client as `observed: false` with an
  // empty value, because the model never constrained it and NuSMV printed an arbitrary domain member.
  // It must vanish from the summary rather than read `illuminance=N/A`: `N/A` claims a reading was
  // expected and went missing, while this device never took one. The real value is on the environment
  // strip. Omitting the flag must keep the old meaning, so a persisted trace still renders.
  it('omits an unobserved variable from the summary and keeps observed ones', () => {
    expect(playbackDeviceSummaryParts({
      deviceId: 'light_1',
      state: 'on',
      variables: [
        { name: 'illuminance', value: '', observed: false },
        { name: 'temperature', value: '20' }
      ]
    })).toEqual(['on', 'temperature=20'])
  })

  it('formats playback summaries and structured security labels without mutating trace data', () => {
    const device = {
      deviceId: 'camera_1',
      state: 'on',
      mode: 'MachineState',
      variables: [{ name: 'weather', value: 'sunny' }]
    }
    const snapshot = structuredClone(device)
    const labels: Record<string, string> = {
      on: '开启',
      MachineState: '设备状态',
      weather: '天气',
      sunny: '晴朗'
    }
    const format = (value: string) => labels[value] || value

    expect(playbackDeviceSummaryParts(device, format))
      .toEqual(['开启', '设备状态', '天气=晴朗'])
    expect(formatPlaybackSecurityLabel('MachineState: on', format)).toBe('设备状态: 开启')
    expect(device).toEqual(snapshot)
  })

  it('distinguishes current Board devices that were not represented in the saved trace', () => {
    const states = [
      { devices: [{ deviceId: 'sensor_1' }] },
      { devices: [{ deviceId: 'LIGHT_1' }] }
    ]
    expect(collectPlaybackDeviceIds(states)).toEqual(new Set(['sensor_1', 'light_1']))
    expect(collectPlaybackDeviceIds(undefined)).toEqual(new Set())
  })

  it('detects runtime attack and state changes from consecutive snapshots', () => {
    const attacked = {
      ...idle,
      state: 'active',
      compromised: true,
      variables: [
        { name: 'temperature', value: '24' }
      ]
    }
    expect(isPlaybackDeviceAttacked(attacked)).toBe(true)
    expect(playbackDeviceChanged(attacked, idle)).toBe(true)
    expect(playbackDeviceChanged(attacked, attacked)).toBe(false)
  })

  it('compares merged trust and privacy facts without requiring an identity field', () => {
    const previous = {
      state: 'recording',
      trustPrivacy: [{ name: 'recording', propertyScope: 'state' as const, trust: true }],
      privacies: [{ name: 'recording', propertyScope: 'state' as const, privacy: 'public' }]
    }
    const equivalent = {
      state: 'recording',
      trustPrivacy: [{
        name: 'recording',
        propertyScope: 'state' as const,
        trust: true,
        privacy: 'public'
      }]
    }
    const privateRecording = {
      ...previous,
      privacies: [{ name: 'recording', propertyScope: 'state' as const, privacy: 'private' }]
    }

    expect(playbackDeviceChanged(equivalent, previous)).toBe(false)
    expect(playbackDeviceChanged(privateRecording, previous)).toBe(true)
  })

  it('builds independent before/after facts without exposing the model id as a label', () => {
    const changed = playbackDeviceChangeDetails({
      deviceId: 'generated_camera_1',
      deviceLabel: 'Hall camera',
      state: 'recording',
      variables: [{ name: 'motion', value: 'TRUE' }]
    }, {
      deviceId: 'generated_camera_1',
      deviceLabel: 'Hall camera',
      state: 'idle',
      variables: [{ name: 'motion', value: 'FALSE' }]
    })

    expect(changed).toMatchObject({
      deviceId: 'generated_camera_1',
      deviceLabel: 'Hall camera',
      details: expect.arrayContaining([
        expect.objectContaining({ kind: 'state', previousValue: 'idle', currentValue: 'recording' }),
        expect.objectContaining({ kind: 'variable', name: 'motion', previousValue: 'FALSE', currentValue: 'TRUE' })
      ])
    })
  })

  it('reports environment changes independently from device changes', () => {
    expect(playbackEnvironmentChangeDetails([
      { name: 'temperature', value: '24' },
      { name: 'motion', value: 'inactive' }
    ], [
      { name: 'temperature', value: '20' },
      { name: 'motion', value: 'inactive' }
    ])).toEqual([
      { name: 'temperature', previousValue: '20', currentValue: '24' }
    ])
    expect(playbackEnvironmentChangeDetails([{ name: 'temperature', value: '20' }], null)).toEqual([])
  })

  it('reports variable/content labels and only the active state security labels', () => {
    expect(playbackDeviceSecurityFacts({
      deviceId: 'camera_1',
      mode: 'CameraMode',
      state: 'recording',
      variables: [
        { name: 'motion', value: 'TRUE', trust: 'untrusted' },
        { name: 'battery', value: '80', trust: 'trusted' }
      ],
      trustPrivacy: [
        { name: 'idle', propertyScope: 'state', mode: 'CameraMode', trust: false },
        { name: 'recording', propertyScope: 'state', mode: 'CameraMode', trust: true }
      ],
      privacies: [
        { name: 'idle', propertyScope: 'state', mode: 'CameraMode', privacy: 'private' },
        { name: 'recording', propertyScope: 'state', mode: 'CameraMode', privacy: 'public' },
        { name: 'video', propertyScope: 'content', privacy: 'private' }
      ]
    })).toEqual({
      untrustedLabels: ['motion'],
      privateLabels: ['video'],
      hasTrustLabels: true,
      hasPrivacyLabels: true
    })
  })

  it('does not claim security labels exist when privacy modeling returned none', () => {
    expect(playbackDeviceSecurityFacts(idle)).toEqual({
      untrustedLabels: [],
      privateLabels: [],
      hasTrustLabels: false,
      hasPrivacyLabels: false
    })
  })
})
