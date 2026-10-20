import type { DeviceTemplate, InternalVariable, WorkingState } from '@/types/device'
import type { DeviceNode } from '@/types/node'

export const TRUST_OPTIONS = ['trusted', 'untrusted'] as const
export const PRIVACY_OPTIONS = ['public', 'private'] as const

export type TrustValue = typeof TRUST_OPTIONS[number]
export type PrivacyValue = typeof PRIVACY_OPTIONS[number]

/**
 * The same vocabulary as a lookup, for boundary validators.
 *
 * Four modules had built their own copy of these Sets from the same two literals — `api/board.ts`,
 * `recommendationMaterialization.ts`, `traceStateResponse.ts` (which even reversed the privacy order), plus
 * inline array literals in `device.ts`. The options list above was already the owner for the *UI* side, so a
 * value added to the domain would have been picked up by the dropdowns and rejected by four validators.
 * Deriving the Sets from the same arrays means the domain has one definition end to end.
 */
export const TRUST_VALUE_SET: ReadonlySet<string> = new Set(TRUST_OPTIONS)
export const PRIVACY_VALUE_SET: ReadonlySet<string> = new Set(PRIVACY_OPTIONS)

export interface DeviceRuntimeConfig {
  state?: string
  currentStateTrust?: string
  currentStatePrivacy?: string
  variables?: DeviceNode['variables']
  privacies?: DeviceNode['privacies']
}

type DeviceRuntimeSource = Omit<DeviceRuntimeConfig,
  'currentStateTrust' | 'currentStatePrivacy'> & {
    currentStateTrust?: string | null
    currentStatePrivacy?: string | null
  }

export interface DeviceRuntimeDraft {
  state: string
  currentStateTrust: string
  currentStatePrivacy: string
  variables: Record<string, string>
  variableTrusts: Record<string, string>
  privacies: Record<string, string>
}

export interface BuildDeviceRuntimeConfigOptions {
  includeEmptyCollections?: boolean
  variableScope?: 'all' | 'local' | 'environment'
}

export interface ValidateDeviceRuntimeConfigOptions {
  variableScope?: 'all' | 'local' | 'environment'
}

type Translate = (key: string, params?: Record<string, unknown>) => string

const normalizeRuntimeValue = (value: unknown) => String(value ?? '').trim()

export const createDeviceRuntimeDraft = (): DeviceRuntimeDraft => ({
  state: '',
  currentStateTrust: '',
  currentStatePrivacy: '',
  variables: {},
  variableTrusts: {},
  privacies: {}
})

export const getTemplateWorkingStates = (template?: DeviceTemplate | null): WorkingState[] =>
  Array.isArray(template?.manifest?.WorkingStates)
    ? template.manifest.WorkingStates.filter(state => normalizeRuntimeValue(state?.Name))
    : []

const getTemplateInternalVariables = (template?: DeviceTemplate | null): InternalVariable[] =>
  Array.isArray(template?.manifest?.InternalVariables)
    ? template.manifest.InternalVariables.filter(variable => normalizeRuntimeValue(variable?.Name))
    : []

const isTemplateLocalVariable = (variable: InternalVariable) =>
  variable.IsInside === true

const isTemplateEnvironmentVariable = (variable: InternalVariable) =>
  variable.IsInside !== true

export const getTemplateLocalVariables = (template?: DeviceTemplate | null): InternalVariable[] =>
  getTemplateInternalVariables(template).filter(isTemplateLocalVariable)

export const getTemplateEnvironmentVariables = (template?: DeviceTemplate | null): InternalVariable[] =>
  getTemplateInternalVariables(template).filter(isTemplateEnvironmentVariable)

const getScopedTemplateVariables = (
  template: DeviceTemplate,
  scope: 'all' | 'local' | 'environment' = 'all'
) => {
  if (scope === 'local') return getTemplateLocalVariables(template)
  if (scope === 'environment') return getTemplateEnvironmentVariables(template)
  return getTemplateInternalVariables(template)
}

export const findTemplateStateTrust = (
  template: DeviceTemplate | null | undefined,
  stateName: string
) => {
  const state = getTemplateWorkingStates(template).find(item => item.Name === stateName)
  return state?.Trust || ''
}

export const findTemplateStatePrivacy = (
  template: DeviceTemplate | null | undefined,
  stateName: string
) => {
  const state = getTemplateWorkingStates(template).find(item => item.Name === stateName)
  return state?.Privacy || ''
}

export const templateVariableHasEnumValues = (variable: InternalVariable) =>
  Array.isArray(variable.Values) && variable.Values.length > 0

/**
 * A numeric variable declares *both* bounds, or it is not numeric.
 *
 * These two helpers each encoded a laxer rule than the product actually has, in opposite directions: this one
 * accepted *either* bound (`||`), and `getTemplateVariableDefaultValue` below defaulted on `LowerBound` alone.
 * `device-template-schema.json` — the authoritative gate for manifests, since the template endpoint takes a raw
 * `JsonNode` — has `oneOf: [{required: [Values], not: LowerBound|UpperBound}, {required: [LowerBound,
 * UpperBound], not: Values}]`, and `BoardStorageServiceImpl.defaultValueForVariable` likewise requires both
 * before defaulting. So a single-bound variable cannot reach us.
 *
 * That made the laxity unreachable rather than harmless: the client encoded a rule the schema contradicts, and
 * anyone reading it would conclude single-bound variables are supported. If the schema ever relaxed, this would
 * have shown a default the server refuses to store.
 */
export const templateVariableUsesNumericBounds = (variable: InternalVariable) =>
  variable.LowerBound !== undefined && variable.LowerBound !== null
  && variable.UpperBound !== undefined && variable.UpperBound !== null

export const validateTemplateVariableValue = (variable: InternalVariable, value: string, t: Translate): string => {
  if (templateVariableHasEnumValues(variable)) {
    return variable.Values!.map(String).includes(value) ? '' : t('app.variableValueEnumDomain')
  }
  if (!templateVariableUsesNumericBounds(variable)) return ''
  const numericValue = Number(value)
  // The model boundary parses integer text, not JavaScript numeric expressions.
  if (!/^[+-]?\d+$/.test(value.trim()) || !Number.isInteger(numericValue)
    || numericValue < -2147483648 || numericValue > 2147483647
    || numericValue < variable.LowerBound! || numericValue > variable.UpperBound!) {
    return t('app.variableValueIntegerDomain', { lower: variable.LowerBound, upper: variable.UpperBound })
  }
  return ''
}

export const validateNumericConditionValue = (
  variable: InternalVariable, value: string, relation: string, t: Translate
): string => {
  if (!templateVariableUsesNumericBounds(variable)) return ''
  const values = relation === 'in' || relation === 'not in'
    ? value.split(/[,;|]/).map(part => part.trim()).filter(Boolean)
    : [value.trim()]
  if (!values.length || !values[0]) return t('app.enterValue')
  // Rule and specification thresholds follow the same integer domain as persisted initial values.
  return values.map(candidate => validateTemplateVariableValue(variable, candidate, t)).find(Boolean) || ''
}

/**
 * Rewrite every variable the given state constrains, in place.
 *
 * Each of the three runtime editors offers a state picker beside the variable inputs, so each could leave
 * a variable behind when the state changes, and the server would then canonicalise it to the state's value
 * behind the user's back. One function rather than three watchers, because this is the same rule three times: a `Dynamics` value belongs to being in the
 * state, so the state is the half that decides. A variable the new state says nothing about is untouched;
 * under partial coverage it is a real instance choice.
 */
export const syncStateDerivedVariables = (
  variables: Record<string, string>,
  template: DeviceTemplate | null | undefined,
  stateName: string | null | undefined
) => {
  // Locals only, like the editors that call this. A shared variable's value is pool authority, and writing
  // one into the draft record could make the unsaved-changes check dirty on a field no save can carry.
  getTemplateLocalVariables(template).forEach(variable => {
    const declared = stateDeclaredVariableValue(template, stateName, variable.Name)
    if (declared !== null) variables[variable.Name] = declared
  })
}

/**
 * Whether every working state declares a `Dynamics` value for this variable.
 *
 * When they all do, the variable is a function of the state: the generated `next(<device>.<var>)` has a
 * branch for each state and its `TRUE:` hold-current fallback is unreachable, so an instance value can
 * only survive step 0 before the state overwrites it. Storing one is storing a derived fact — which is
 * exactly how a device came to be saved as state `away` with `location = garage`.
 *
 * Partial coverage is different and must stay editable: in a state that declares nothing, the fallback is
 * live and the instance value genuinely carries the variable. No bundled template does this today, but a
 * custom one may, so the distinction is drawn from the manifest rather than assumed.
 */
export const templateVariableIsStateDerived = (
  template: DeviceTemplate | null | undefined,
  variableName: string
): boolean => {
  const states = template?.manifest?.WorkingStates || []
  if (states.length === 0 || !variableName) return false
  // Only a device-local variable can be the state's consequence; a shared value belongs to the pool.
  if (!getTemplateLocalVariables(template).some(variable => variable.Name === variableName)) return false
  // A Transition assignment on the same variable disqualifies it. The generator emits transition branches
  // BEFORE the state branches in the same `case`, and first match wins, so a firing transition overrides
  // what the state declares — the variable is then genuinely driven by something other than the state, and
  // calling it derived would lock an editor for a value the model really does move independently.
  const drivenByTransition = (template?.manifest?.Transitions || []).some(transition =>
    (transition?.Assignments || []).some(assignment => assignment?.Attribute === variableName))
  if (drivenByTransition) return false
  return states.every(state =>
    (state?.Dynamics || []).some(entry =>
      entry?.VariableName === variableName
      && entry?.Value !== undefined && entry?.Value !== null && String(entry.Value).trim() !== ''))
}

/**
 * The value a working state declares for a variable, or `null` when it declares none.
 *
 * A `Dynamics` entry is a standing constraint — the model emits it as a branch of
 * `next(<device>.<var>)` guarded by being in that state — so the state a device starts in already
 * fixes such a variable's initial value. Enum only: a numeric target declares a `ChangeRate`, which
 * says nothing about the current value. Mirrors `DeviceManifestModes.stateDeclaredValue`.
 */
export const stateDeclaredVariableValue = (
  template: DeviceTemplate | null | undefined,
  stateName: string | null | undefined,
  variableName: string
): string | null => {
  const wanted = String(stateName ?? '').trim()
  if (!wanted || !variableName) return null
  // The complete tuple names one state: matching a single segment of `heating;ready` would pick a sibling.
  const state = (template?.manifest?.WorkingStates || [])
    .find(candidate => String(candidate?.Name ?? '').trim() === wanted)
  const dynamic = (state?.Dynamics || []).find(entry => entry?.VariableName === variableName)
  const value = dynamic?.Value
  return value !== undefined && value !== null && String(value).trim() !== '' ? String(value).trim() : null
}

/**
 * A variable's starting value. Pass the template and the device's starting state to get the value that
 * state declares; without them this falls back to the documented template default, which is what the
 * read-only placeholders want.
 */
export const getTemplateVariableDefaultValue = (
  variable: InternalVariable,
  template?: DeviceTemplate | null,
  stateName?: string | null
): string => {
  const declared = stateDeclaredVariableValue(template, stateName, variable.Name)
  if (declared !== null) return declared
  if (templateVariableHasEnumValues(variable)) return String(variable.Values![0])
  if (templateVariableUsesNumericBounds(variable)) return String(variable.LowerBound)
  return ''
}

export const resetDeviceRuntimeDraft = (
  draft: DeviceRuntimeDraft,
  template?: DeviceTemplate | null
) => {
  const states = getTemplateWorkingStates(template)
  draft.state = template?.manifest?.InitState || states[0]?.Name || ''
  draft.currentStateTrust = ''
  draft.currentStatePrivacy = ''
  draft.variables = {}
  draft.variableTrusts = {}
  draft.privacies = {}

  getTemplateInternalVariables(template).forEach(variable => {
    const name = variable.Name
    // Seeded from the state the draft actually starts in, so the dialog cannot offer a pair the server
    // would store as a self-contradicting node (state `away` with `location = garage`).
    draft.variables[name] = getTemplateVariableDefaultValue(variable, template, draft.state)
    draft.variableTrusts[name] = ''
    draft.privacies[name] = ''
  })
}

export const buildDeviceRuntimeConfig = (
  template: DeviceTemplate,
  runtime: DeviceRuntimeDraft,
  options: BuildDeviceRuntimeConfigOptions = {}
): DeviceRuntimeConfig | undefined => {
  const config: DeviceRuntimeConfig = {}
  const hasModes = Array.isArray(template.manifest?.Modes)
    && template.manifest.Modes.length > 0
    && getTemplateWorkingStates(template).length > 0

  if (hasModes && normalizeRuntimeValue(runtime.state)) {
    config.state = normalizeRuntimeValue(runtime.state)
    const requestedTrust = normalizeRuntimeValue(runtime.currentStateTrust).toLowerCase()
    const requestedPrivacy = normalizeRuntimeValue(runtime.currentStatePrivacy).toLowerCase()
    if (requestedTrust) config.currentStateTrust = requestedTrust
    if (requestedPrivacy) config.currentStatePrivacy = requestedPrivacy
  }

  const scopedVariables = getScopedTemplateVariables(template, options.variableScope)

  const variables = scopedVariables
    .map(variable => {
      const name = variable.Name
      const value = normalizeRuntimeValue(runtime.variables[name])
      if (!value) return null
      const requestedTrust = normalizeRuntimeValue(runtime.variableTrusts[name]).toLowerCase()
      return { name, value, ...(requestedTrust ? { trust: requestedTrust } : {}) }
    })
    .filter((item): item is { name: string, value: string, trust?: string } => Boolean(item))

  if (variables.length > 0 || (options.includeEmptyCollections && scopedVariables.length > 0)) {
    config.variables = variables
  }

  const privacies = scopedVariables
    .map(variable => {
      const name = variable.Name
      const requestedPrivacy = normalizeRuntimeValue(runtime.privacies[name]).toLowerCase()
      return requestedPrivacy ? { name, privacy: requestedPrivacy } : null
    })
    .filter((item): item is { name: string, privacy: string } => Boolean(item))

  if (privacies.length > 0 || (options.includeEmptyCollections && scopedVariables.length > 0)) {
    config.privacies = privacies
  }

  return Object.keys(config).length > 0 ? config : undefined
}

export const materializeDeviceRuntimeConfig = (
  template: DeviceTemplate,
  runtime?: DeviceRuntimeSource,
  options: BuildDeviceRuntimeConfigOptions = {}
): DeviceRuntimeConfig | undefined => {
  const draft = createDeviceRuntimeDraft()
  resetDeviceRuntimeDraft(draft, template)

  if (runtime?.state !== undefined) draft.state = normalizeRuntimeValue(runtime.state)
  if (runtime?.currentStateTrust !== undefined) {
    draft.currentStateTrust = normalizeRuntimeValue(runtime.currentStateTrust).toLowerCase()
  }
  if (runtime?.currentStatePrivacy !== undefined) {
    draft.currentStatePrivacy = normalizeRuntimeValue(runtime.currentStatePrivacy).toLowerCase()
  }
  for (const variable of runtime?.variables || []) {
    draft.variables[variable.name] = normalizeRuntimeValue(variable.value)
    if (variable.trust !== undefined) {
      draft.variableTrusts[variable.name] = normalizeRuntimeValue(variable.trust).toLowerCase()
    }
  }
  for (const privacy of runtime?.privacies || []) {
    draft.privacies[privacy.name] = normalizeRuntimeValue(privacy.privacy).toLowerCase()
  }

  return buildDeviceRuntimeConfig(template, draft, options)
}

export const deviceRuntimeConfigsEqual = (
  template: DeviceTemplate,
  left: DeviceRuntimeSource | undefined,
  right: DeviceRuntimeSource | undefined,
  options: BuildDeviceRuntimeConfigOptions = {}
) => JSON.stringify(materializeDeviceRuntimeConfig(template, left, options))
  === JSON.stringify(materializeDeviceRuntimeConfig(template, right, options))

export const validateDeviceRuntimeConfig = (
  template: DeviceTemplate,
  runtime: DeviceRuntimeConfig | undefined,
  t: Translate,
  options: ValidateDeviceRuntimeConfigOptions = {}
) => {
  if (!runtime) return ''

  const states = getTemplateWorkingStates(template).map(state => state.Name)
  const hasModes = Array.isArray(template.manifest?.Modes)
    && template.manifest.Modes.length > 0
    && states.length > 0
  if (runtime.state && hasModes && states.length > 0 && !states.includes(runtime.state)) {
    return t('app.deviceImportInvalidState', { state: runtime.state })
  }
  if (runtime.state && !hasModes && runtime.state.toLowerCase() !== 'working') {
    return t('app.deviceImportStateWithoutModes')
  }
  if (!hasModes && runtime.currentStateTrust) {
    return t('app.deviceImportStateTrustWithoutModes')
  }
  if (!hasModes && runtime.currentStatePrivacy) {
    return t('app.deviceImportStatePrivacyWithoutModes')
  }
  if (runtime.currentStateTrust && !TRUST_OPTIONS.includes(runtime.currentStateTrust as TrustValue)) {
    return t('app.deviceImportInvalidTrust', { value: runtime.currentStateTrust })
  }
  if (runtime.currentStatePrivacy && !PRIVACY_OPTIONS.includes(runtime.currentStatePrivacy as PrivacyValue)) {
    return t('app.deviceImportInvalidPrivacy', { value: runtime.currentStatePrivacy })
  }

  const variableMap = new Map(getScopedTemplateVariables(template, options.variableScope).map(variable => [variable.Name, variable]))
  const allVariableMap = new Map(getTemplateInternalVariables(template).map(variable => [variable.Name, variable]))
  const seenVariables = new Set<string>()
  for (const variable of runtime.variables || []) {
    const manifestVariable = variableMap.get(variable.name)
    if (seenVariables.has(variable.name)) {
      return t('app.deviceImportDuplicateVariable', { name: variable.name })
    }
    seenVariables.add(variable.name)
    if (!manifestVariable) {
      const unscopedVariable = allVariableMap.get(variable.name)
      if (options.variableScope === 'local' && unscopedVariable && isTemplateEnvironmentVariable(unscopedVariable)) {
        return t('app.deviceImportEnvironmentVariableNotDeviceRuntime', { name: variable.name })
      }
      return t('app.deviceImportInvalidVariable', { name: variable.name })
    }
    if (variable.trust && !TRUST_OPTIONS.includes(variable.trust as TrustValue)) {
      return t('app.deviceImportInvalidTrust', { value: variable.trust })
    }
    const valueFailure = validateTemplateVariableValue(manifestVariable, String(variable.value), t)
    if (valueFailure) return `${variable.name}: ${valueFailure}`
  }

  const seenPrivacies = new Set<string>()
  for (const privacy of runtime.privacies || []) {
    if (seenPrivacies.has(privacy.name)) {
      return t('app.deviceImportDuplicatePrivacy', { name: privacy.name })
    }
    seenPrivacies.add(privacy.name)
    if (!variableMap.has(privacy.name)) {
      const unscopedVariable = allVariableMap.get(privacy.name)
      if (options.variableScope === 'local' && unscopedVariable && isTemplateEnvironmentVariable(unscopedVariable)) {
        return t('app.deviceImportEnvironmentVariableNotDeviceRuntime', { name: privacy.name })
      }
      return t('app.deviceImportInvalidPrivacyTarget', { name: privacy.name })
    }
    if (!PRIVACY_OPTIONS.includes(privacy.privacy as PrivacyValue)) {
      return t('app.deviceImportInvalidPrivacy', { value: privacy.privacy })
    }
  }

  return ''
}
