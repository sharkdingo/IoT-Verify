import { describe, expect, it } from 'vitest'
import type { DeviceTemplate } from '@/types/device'
import {
  buildDeviceRuntimeConfig,
  createDeviceRuntimeDraft,
  deviceRuntimeConfigsEqual,
  findTemplateStatePrivacy,
  findTemplateStateTrust,
  getTemplateVariableDefaultValue,
  materializeDeviceRuntimeConfig,
  resetDeviceRuntimeDraft,
  stateDeclaredVariableValue,
  syncStateDerivedVariables,
  templateVariableIsStateDerived,
  templateVariableUsesNumericBounds,
  validateDeviceRuntimeConfig
} from '../deviceRuntime'

const t = (key: string, params?: Record<string, unknown>) =>
  params ? `${key}:${JSON.stringify(params)}` : key

const thermostatTemplate: DeviceTemplate = {
  id: 1,
  name: 'Thermostat',
  manifest: {
    Name: 'Thermostat',
    Description: 'HVAC controller',
    Modes: ['ThermostatMode'],
    InitState: 'auto',
    InternalVariables: [
      {
        Name: 'temperature',
        IsInside: false,
        FalsifiableWhenCompromised: true,
        LowerBound: 0,
        UpperBound: 50,
        Trust: 'trusted',
        Privacy: 'private'
      },
      {
        Name: 'presence',
        IsInside: true,
        FalsifiableWhenCompromised: true,
        Values: ['home', 'away'],
        Trust: 'trusted',
        Privacy: 'public'
      }
    ],
    ImpactedVariables: [],
    WorkingStates: [
      { Name: 'auto', Description: '', Trust: 'trusted', Privacy: 'public', Dynamics: [] },
      { Name: 'cool', Description: '', Trust: 'untrusted', Privacy: 'private', Dynamics: [] }
    ],
    APIs: [],
    Contents: []
  }
}

const statelessSensorTemplate: DeviceTemplate = {
  id: 2,
  name: 'Smoke Sensor',
  manifest: {
    Name: 'Smoke Sensor',
    Description: 'Stateless smoke sensor',
    Modes: [],
    InitState: '',
    InternalVariables: [
      {
        Name: 'smoke',
        IsInside: false,
        FalsifiableWhenCompromised: true,
        Values: ['clear', 'detected'],
        Trust: 'trusted',
        Privacy: 'public'
      }
    ],
    ImpactedVariables: [],
    WorkingStates: [],
    APIs: [],
    Contents: []
  }
}

describe('device runtime authority helpers', () => {
  it('keeps template labels as fallback instead of instance overrides', () => {
    const draft = createDeviceRuntimeDraft()

    resetDeviceRuntimeDraft(draft, thermostatTemplate)

    expect(draft.state).toBe('auto')
    expect(draft.currentStateTrust).toBe('')
    expect(draft.currentStatePrivacy).toBe('')
    expect(draft.variableTrusts.temperature).toBe('')
    expect(draft.privacies.temperature).toBe('')
    expect(findTemplateStateTrust(thermostatTemplate, 'auto')).toBe('trusted')
    expect(findTemplateStatePrivacy(thermostatTemplate, 'auto')).toBe('public')
    expect(draft.variables.temperature).toBe('0')
    expect(draft.variables.presence).toBe('home')
    expect(getTemplateVariableDefaultValue(thermostatTemplate.manifest.InternalVariables![1])).toBe('home')
  })

  it('builds one canonical runtime config for manual and drag-created devices', () => {
    const draft = createDeviceRuntimeDraft()
    resetDeviceRuntimeDraft(draft, thermostatTemplate)
    draft.state = 'cool'
    draft.currentStateTrust = 'untrusted'
    draft.currentStatePrivacy = 'private'
    draft.variables.temperature = '27'
    draft.variableTrusts.temperature = 'untrusted'
    draft.privacies.temperature = 'private'
    draft.variables.presence = 'home'

    expect(buildDeviceRuntimeConfig(thermostatTemplate, draft)).toEqual({
      state: 'cool',
      currentStateTrust: 'untrusted',
      currentStatePrivacy: 'private',
      variables: [
        { name: 'temperature', value: '27', trust: 'untrusted' },
        { name: 'presence', value: 'home' }
      ],
      privacies: [
        { name: 'temperature', privacy: 'private' }
      ]
    })
  })

  it('materializes omitted local runtime while preserving supplied overrides', () => {
    expect(materializeDeviceRuntimeConfig(thermostatTemplate, {
      state: 'cool',
      variables: [{ name: 'presence', value: 'away', trust: 'untrusted' }]
    }, { variableScope: 'local' })).toEqual({
      state: 'cool',
      variables: [{ name: 'presence', value: 'away', trust: 'untrusted' }]
    })
  })

  it('uses the same effective local defaults when runtime is omitted or explicitly empty', () => {
    const expected = {
      state: 'auto',
      variables: [{ name: 'presence', value: 'home' }]
    }

    expect(materializeDeviceRuntimeConfig(thermostatTemplate, undefined, { variableScope: 'local' })).toEqual(expected)
    expect(materializeDeviceRuntimeConfig(thermostatTemplate, {
      variables: [],
      privacies: []
    }, { variableScope: 'local' })).toEqual(expected)
  })

  it('compares runtime snapshots by effective values rather than nullable transport fields', () => {
    expect(deviceRuntimeConfigsEqual(thermostatTemplate, {
      state: 'auto',
      currentStateTrust: null,
      variables: [{ name: 'presence', value: 'home', trust: null }],
      privacies: []
    }, {
      state: 'auto',
      variables: [{ name: 'presence', value: 'home' }]
    }, { variableScope: 'local', includeEmptyCollections: true })).toBe(true)

    expect(deviceRuntimeConfigsEqual(thermostatTemplate, {
      state: 'auto',
      variables: [{ name: 'presence', value: 'home' }]
    }, {
      state: 'auto',
      variables: [{ name: 'presence', value: 'away' }]
    }, { variableScope: 'local', includeEmptyCollections: true })).toBe(false)
  })

  it('can preserve empty runtime collections when editing an existing device', () => {
    const draft = createDeviceRuntimeDraft()
    resetDeviceRuntimeDraft(draft, thermostatTemplate)
    draft.variables.temperature = ''
    draft.variables.presence = ''

    expect(buildDeviceRuntimeConfig(thermostatTemplate, draft, { includeEmptyCollections: true })).toEqual({
      state: 'auto',
      variables: [],
      privacies: []
    })
  })

  it('keeps scenario-level environment variables out of device instance runtime forms', () => {
    const draft = createDeviceRuntimeDraft()
    resetDeviceRuntimeDraft(draft, thermostatTemplate)
    draft.variables.temperature = '27'
    draft.variableTrusts.temperature = 'untrusted'
    draft.privacies.temperature = 'public'
    draft.variables.presence = 'home'

    expect(buildDeviceRuntimeConfig(thermostatTemplate, draft, { variableScope: 'local' })).toEqual({
      state: 'auto',
      variables: [
        { name: 'presence', value: 'home' }
      ]
    })

    const validation = validateDeviceRuntimeConfig(thermostatTemplate, {
      variables: [{ name: 'temperature', value: '27', trust: 'trusted' }]
    }, t, { variableScope: 'local' })
    expect(validation).toContain('app.deviceImportEnvironmentVariableNotDeviceRuntime')
  })

  it('rejects runtime overrides that the backend model boundary would reject', () => {
    const invalidEnum = validateDeviceRuntimeConfig(thermostatTemplate, {
      variables: [{ name: 'presence', value: 'office', trust: 'trusted' }]
    }, t)
    const invalidRange = validateDeviceRuntimeConfig(thermostatTemplate, {
      variables: [{ name: 'temperature', value: '99', trust: 'trusted' }]
    }, t)
    const invalidTrust = validateDeviceRuntimeConfig(thermostatTemplate, {
      currentStateTrust: 'maybe'
    }, t)
    const invalidPrivacy = validateDeviceRuntimeConfig(thermostatTemplate, {
      currentStatePrivacy: 'secret'
    }, t)

    expect(invalidEnum).toContain('app.variableValueEnumDomain')
    expect(invalidRange).toContain('app.variableValueIntegerDomain')
    expect(invalidTrust).toContain('app.deviceImportInvalidTrust')
    expect(invalidPrivacy).toContain('app.deviceImportInvalidPrivacy')
  })

  it.each(['1.5', '1e1', '1.0'])('rejects non-integer text %s before a runtime write', value => {
    expect(validateDeviceRuntimeConfig(thermostatTemplate, {
      variables: [{ name: 'temperature', value }]
    }, t)).toContain('app.variableValueIntegerDomain')
  })

  it('accepts signed decimal integers without requiring runtime default overrides', () => {
    expect(validateDeviceRuntimeConfig(thermostatTemplate, {
      variables: [{ name: 'temperature', value: '+01' }]
    }, t)).toBe('')
    const draft = createDeviceRuntimeDraft()
    resetDeviceRuntimeDraft(draft, thermostatTemplate)
    draft.variables.temperature = ''
    expect(buildDeviceRuntimeConfig(thermostatTemplate, draft)?.variables)
      .not.toContainEqual(expect.objectContaining({ name: 'temperature' }))
  })

  it('requires both bounds before treating a variable as numeric', () => {
    /*
     * `device-template-schema.json` admits exactly two shapes for an InternalVariable — `Values` with neither
     * bound, or *both* bounds with no `Values` (`oneOf`) — and `BoardStorageServiceImpl.defaultValueForVariable`
     * agrees, requiring both before it will default. This helper accepted *either* bound, which encoded a rule
     * the product does not have: a single-bound variable would have been shown a default the server refuses to
     * store, and `DeviceDialog`'s range check would have validated against a half-declared domain.
     *
     * Unreachable through the API, which is why nothing caught it. Pinned so the client stops documenting a
     * shape the schema forbids.
     */
    const base = {
      Name: 'temperature',
      IsInside: true,
      FalsifiableWhenCompromised: true,
      Trust: 'trusted',
      Privacy: 'public'
    } as const

    expect(templateVariableUsesNumericBounds({ ...base, LowerBound: 0, UpperBound: 40 })).toBe(true)
    expect(templateVariableUsesNumericBounds({ ...base, LowerBound: 0 } as any)).toBe(false)
    expect(templateVariableUsesNumericBounds({ ...base, UpperBound: 40 } as any)).toBe(false)

    // The default follows the same rule, rather than defaulting off a lone LowerBound.
    expect(getTemplateVariableDefaultValue({ ...base, LowerBound: 5, UpperBound: 40 })).toBe('5')
    expect(getTemplateVariableDefaultValue({ ...base, LowerBound: 5 } as any)).toBe('')
  })

  it('does not treat API null numeric bounds as a numeric range for enum variables', () => {
    expect(templateVariableUsesNumericBounds({
      Name: 'location',
      IsInside: true,
      FalsifiableWhenCompromised: true,
      Trust: 'trusted',
      Privacy: 'public',
      Values: ['home', 'away'],
      LowerBound: null as any,
      UpperBound: null as any
    })).toBe(false)

    expect(validateDeviceRuntimeConfig(thermostatTemplate, {
      variables: [{ name: 'presence', value: 'home', trust: 'trusted' }]
    }, t)).toBe('')
  })

  it('rejects state trust overrides for no-mode templates', () => {
    const result = validateDeviceRuntimeConfig(statelessSensorTemplate, {
      currentStateTrust: 'trusted'
    }, t)

    expect(result).toBe('app.deviceImportStateTrustWithoutModes')
  })

  it('rejects state privacy overrides for no-mode templates', () => {
    const result = validateDeviceRuntimeConfig(statelessSensorTemplate, {
      currentStatePrivacy: 'private'
    }, t)

    expect(result).toBe('app.deviceImportStatePrivacyWithoutModes')
  })

  it('rejects non-placeholder state overrides for no-mode templates', () => {
    const result = validateDeviceRuntimeConfig(statelessSensorTemplate, {
      state: 'clear'
    }, t)

    expect(result).toBe('app.deviceImportStateWithoutModes')
    expect(validateDeviceRuntimeConfig(statelessSensorTemplate, { state: 'Working' }, t)).toBe('')
  })

  it('rejects duplicate runtime overrides before save', () => {
    const duplicateVariable = validateDeviceRuntimeConfig(thermostatTemplate, {
      variables: [
        { name: 'presence', value: 'home', trust: 'trusted' },
        { name: 'presence', value: 'away', trust: 'trusted' }
      ]
    }, t)
    const duplicatePrivacy = validateDeviceRuntimeConfig(thermostatTemplate, {
      privacies: [
        { name: 'temperature', privacy: 'private' },
        { name: 'temperature', privacy: 'public' }
      ]
    }, t)

    expect(duplicateVariable).toContain('app.deviceImportDuplicateVariable')
    expect(duplicatePrivacy).toContain('app.deviceImportDuplicatePrivacy')
  })

  it('rejects mode names in variable runtime overrides', () => {
    const result = validateDeviceRuntimeConfig(thermostatTemplate, {
      variables: [{ name: 'ThermostatMode', value: 'cool', trust: 'trusted' }]
    }, t)

    expect(result).toContain('app.deviceImportInvalidVariable')
  })

  it('uses working-state trust when a state changes', () => {
    expect(findTemplateStateTrust(thermostatTemplate, 'cool')).toBe('untrusted')
    expect(findTemplateStateTrust(thermostatTemplate, 'missing')).toBe('')
    expect(findTemplateStatePrivacy(thermostatTemplate, 'cool')).toBe('private')
    expect(findTemplateStatePrivacy(thermostatTemplate, 'missing')).toBe('')
  })

  it('preserves explicit invalid labels so validation rejects them', () => {
    const draft = createDeviceRuntimeDraft()
    resetDeviceRuntimeDraft(draft, thermostatTemplate)
    draft.currentStateTrust = 'maybe'
    draft.variableTrusts.presence = 'unknown'

    const config = buildDeviceRuntimeConfig(thermostatTemplate, draft)

    expect(config?.currentStateTrust).toBe('maybe')
    expect(config?.variables?.find(variable => variable.name === 'presence')?.trust).toBe('unknown')
    expect(validateDeviceRuntimeConfig(thermostatTemplate, config, t)).toContain('app.deviceImportInvalidTrust')
  })

  /**
   * The draft must not prefill a pair the server would store as a self-contradicting node.
   *
   * `Car` is the reported shape: `InitState: away`, whose WorkingState declares `location := away`, while
   * `Values[0]` is `garage`. Seeding the two independently is what produced `init(CarLocation) := away`
   * beside `init(location) := garage` in the generated model. The Thermostat fixture above cannot catch
   * this — all its WorkingStates have empty `Dynamics`, so the derivation never engages.
   */
  const carTemplate: DeviceTemplate = {
    id: 2,
    name: 'Car',
    manifest: {
      Name: 'Car',
      Description: '',
      Modes: ['CarLocation'],
      InitState: 'away',
      InternalVariables: [
        {
          Name: 'location',
          IsInside: true,
          FalsifiableWhenCompromised: true,
          Values: ['garage', 'away'],
          Trust: 'untrusted',
          Privacy: 'private'
        }
      ],
      ImpactedVariables: [],
      WorkingStates: [
        { Name: 'garage', Description: '', Trust: 'untrusted', Privacy: 'private', Dynamics: [{ VariableName: 'location', Value: 'garage' }] },
        { Name: 'away', Description: '', Trust: 'untrusted', Privacy: 'private', Dynamics: [{ VariableName: 'location', Value: 'away' }] }
      ],
      APIs: []
    }
  } as never

  it('seeds a local variable from the state the draft starts in, not the first enum literal', () => {
    const draft = createDeviceRuntimeDraft()

    resetDeviceRuntimeDraft(draft, carTemplate)

    expect(draft.state).toBe('away')
    expect(draft.variables.location, 'the draft offered a state and value that contradict').toBe('away')
    // The old rule, still correct where the starting state declares nothing for the variable.
    expect(getTemplateVariableDefaultValue(carTemplate.manifest.InternalVariables![0])).toBe('garage')
  })

  /**
   * FULL coverage is what makes a variable derived rather than an instance choice: the generated
   * `next(<device>.<var>)` then has a branch for every state and its hold-current fallback is unreachable,
   * so a value entered per instance survives only step 0. PARTIAL coverage must stay editable — in a state
   * that declares nothing the fallback is live and the value really is the user's. No bundled template is
   * partial, so this pins the distinction for custom ones.
   */
  it('treats a variable as state-derived only when every state constrains it', () => {
    expect(templateVariableIsStateDerived(carTemplate, 'location')).toBe(true)
    // The Thermostat's states declare empty Dynamics, so `presence` remains the user's to set.
    expect(templateVariableIsStateDerived(thermostatTemplate, 'presence')).toBe(false)
    expect(templateVariableIsStateDerived(carTemplate, 'missing')).toBe(false)
    // A stateless sensor has no state to derive from.
    expect(templateVariableIsStateDerived(statelessSensorTemplate, 'smoke')).toBe(false)

    const partial = {
      ...carTemplate,
      manifest: {
        ...carTemplate.manifest,
        WorkingStates: [
          carTemplate.manifest.WorkingStates![0],
          { ...carTemplate.manifest.WorkingStates![1], Dynamics: [] }
        ]
      }
    } as never
    expect(templateVariableIsStateDerived(partial, 'location'), 'partial coverage must stay editable').toBe(false)

    // A Transition assignment on the same variable means the state does NOT determine it: the generator
    // emits transition branches ahead of the state branches in one `case`, so a firing transition wins.
    // Locking the editor there would refuse a value the model legitimately produces.
    const withTransition = {
      ...carTemplate,
      manifest: {
        ...carTemplate.manifest,
        Transitions: [{
          Name: 'towed',
          StartState: 'garage',
          Trigger: { Attribute: 'CarLocation', Relation: '=', Value: 'garage' },
          Assignments: [{ Attribute: 'location', Value: 'away' }]
        }]
      }
    } as never
    expect(templateVariableIsStateDerived(withTransition, 'location'),
      "a transition-driven variable is not the state's consequence").toBe(false)
  })

  it('syncs only the variables the chosen state constrains', () => {
    const variables: Record<string, string> = { location: 'garage', odometer: '42' }

    syncStateDerivedVariables(variables, carTemplate, 'away')

    expect(variables.location).toBe('away')
    // `away` says nothing about the odometer, so an explicit instance value survives.
    expect(variables.odometer).toBe('42')
  })

  it('reads the value a named state declares, and nothing when it declares none', () => {
    expect(stateDeclaredVariableValue(carTemplate, 'garage', 'location')).toBe('garage')
    expect(stateDeclaredVariableValue(carTemplate, 'away', 'location')).toBe('away')
    expect(stateDeclaredVariableValue(carTemplate, 'away', 'missing')).toBeNull()
    expect(stateDeclaredVariableValue(carTemplate, 'nosuchstate', 'location')).toBeNull()
    // The Thermostat's states declare empty Dynamics, so they constrain nothing.
    expect(stateDeclaredVariableValue(thermostatTemplate, 'auto', 'presence')).toBeNull()
  })
})
