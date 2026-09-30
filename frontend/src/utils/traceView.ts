// Pure decision helpers for the verification-trace playback surface in Board.vue.
// Extracted so the mutual-exclusion guard and trace-context derivation can be unit-tested
// without mounting the whole Board component.

import type { Trace } from '@/types/verify'
import { normalizeModelRelation, normalizeNuSmvDeviceName } from './modelRequest'

export interface PlaybackPanelState {
  simulationVisible: boolean
  recommendationPanelVisible: boolean
}

export interface PlaybackGuardResult {
  allowed: boolean
  reasonCode: 'SIMULATION_VISIBLE' | 'RECOMMENDATION_VISIBLE' | null
}

/**
 * Whether a verification trace may open the playback animation right now. A trace uses the same
 * animation surface as the simulation timeline and the recommendations panel, so it must not stack
 * on top of either. Mirrors the guards `selectAndPlayTrace` applies to the current-result path.
 */
export function canOpenTracePlayback(state: PlaybackPanelState): PlaybackGuardResult {
  if (state.simulationVisible) {
    return { allowed: false, reasonCode: 'SIMULATION_VISIBLE' }
  }
  if (state.recommendationPanelVisible) {
    return { allowed: false, reasonCode: 'RECOMMENDATION_VISIBLE' }
  }
  return { allowed: true, reasonCode: null }
}

export interface TraceContext {
  isAttack: boolean
  attackBudget: number
  enablePrivacy: boolean
}

/**
 * The verification context to label a trace with: the trace's own stored context when available
 * (backend derives isAttack/attackBudget from the request snapshot).
 */
export function deriveTraceContext(
  trace: Pick<Trace, 'isAttack' | 'attackBudget' | 'enablePrivacy'> | null | undefined
): TraceContext {
  return {
    isAttack: trace?.isAttack === true,
    attackBudget: trace?.attackBudget ?? 0,
    enablePrivacy: trace?.enablePrivacy === true
  }
}

export interface PlaybackDevice {
  deviceId: string
  deviceLabel?: string
  state?: string | null
  mode?: string | null
  compromised?: boolean
  variables?: Array<{ name: string; value: string; trust?: string | null; observed?: boolean }>
  trustPrivacy?: Array<{ name: string; propertyScope: 'state' | 'variable' | 'content'; mode?: string | null; trust?: boolean | null; privacy?: string | null }>
  privacies?: Array<{ name: string; propertyScope: 'state' | 'variable' | 'content'; mode?: string | null; trust?: boolean | null; privacy?: string | null }>
}

type PlaybackComparableDevice = {
  state?: string | null
  mode?: string | null
  compromised?: boolean | null
  variables?: Array<{ name: string; value?: unknown; trust?: string | null }>
  trustPrivacy?: Array<{ name: string; propertyScope?: 'state' | 'variable' | 'content'; mode?: string | null; trust?: boolean | null; privacy?: string | null }>
  privacies?: Array<{ name: string; propertyScope?: 'state' | 'variable' | 'content'; mode?: string | null; trust?: boolean | null; privacy?: string | null }>
}

export type PlaybackChangeKind = 'state' | 'mode' | 'variable' | 'security' | 'compromised'

export interface PlaybackDeviceChangeDetail {
  kind: PlaybackChangeKind
  name?: string
  previousValue: string
  currentValue: string
}

export interface PlaybackDeviceChange {
  deviceId: string
  deviceLabel: string
  details: PlaybackDeviceChangeDetail[]
}

export interface PlaybackEnvironmentChange {
  name: string
  previousValue: string
  currentValue: string
}

export interface PlaybackDeviceSecurityFacts {
  untrustedLabels: string[]
  privateLabels: string[]
  hasTrustLabels: boolean
  hasPrivacyLabels: boolean
}

export const normalizePlaybackDeviceId = (value: unknown): string =>
  normalizeNuSmvDeviceName(String(value ?? '').trim()).toLowerCase()

/**
 * All device identities represented anywhere in the saved model trace, independent of the selected step.
 * An absent identity must not fall back to the device's live Board state during playback.
 */
export const collectPlaybackDeviceIds = (
  states: Array<{ devices?: Array<{ deviceId?: string | null }> }> | null | undefined
): Set<string> => {
  const ids = new Set<string>()
  for (const state of states || []) {
    for (const device of state.devices || []) {
      const id = normalizePlaybackDeviceId(device.deviceId)
      if (id) ids.add(id)
    }
  }
  return ids
}

export const isPlaybackDeviceAttacked = (device: PlaybackDevice): boolean =>
  device.compromised === true

const splitPlaybackParts = (value: string | null | undefined): string[] =>
  String(value || '')
    .split(';')
    .map(part => part.trim())
    .filter(Boolean)

const isActiveStateProperty = (
  entry: { name: string; propertyScope: 'state' | 'variable' | 'content'; mode?: string | null },
  device: PlaybackDevice
): boolean => {
  if (entry.propertyScope !== 'state') return true
  const modes = splitPlaybackParts(device.mode)
  const states = splitPlaybackParts(device.state)
  const modeIndex = entry.mode
    ? modes.findIndex(mode => mode.toLowerCase() === entry.mode?.toLowerCase())
    : -1
  if (modeIndex >= 0) return states[modeIndex]?.toLowerCase() === entry.name.toLowerCase()
  return states.some(state => state.toLowerCase() === entry.name.toLowerCase())
}

const securityPropertyLabel = (entry: { name: string; propertyScope: string; mode?: string | null }): string =>
  entry.propertyScope === 'state' && entry.mode ? `${entry.mode}: ${entry.name}` : entry.name

/**
 * User-facing security facts for one saved trace snapshot. State-level labels are
 * limited to the active mode/state; variable and content labels remain visible.
 */
export const playbackDeviceSecurityFacts = (device: PlaybackDevice): PlaybackDeviceSecurityFacts => {
  const trustLabels = new Map<string, boolean>()
  const privacyLabels = new Map<string, string>()

  ;(device.variables || []).forEach(variable => {
    const trust = variable.trust?.trim().toLowerCase()
    if (trust === 'trusted' || trust === 'untrusted') {
      trustLabels.set(variable.name, trust === 'trusted')
    }
  })

  ;(device.trustPrivacy || []).forEach(entry => {
    if (entry.trust === null || entry.trust === undefined) return
    if (!isActiveStateProperty(entry, device)) return
    trustLabels.set(securityPropertyLabel(entry), entry.trust)
  })

  ;(device.privacies || []).forEach(entry => {
    const privacy = entry.privacy?.trim().toLowerCase()
    if (privacy !== 'public' && privacy !== 'private') return
    if (!isActiveStateProperty(entry, device)) return
    privacyLabels.set(securityPropertyLabel(entry), privacy)
  })

  return {
    untrustedLabels: [...trustLabels.entries()]
      .filter(([, trusted]) => !trusted)
      .map(([name]) => name),
    privateLabels: [...privacyLabels.entries()]
      .filter(([, privacy]) => privacy === 'private')
      .map(([name]) => name),
    hasTrustLabels: trustLabels.size > 0,
    hasPrivacyLabels: privacyLabels.size > 0
  }
}

export const formatPlaybackSecurityLabel = (
  label: string,
  formatToken: (value: string) => string
): string => {
  const stateProperty = /^([^:]+):\s*(.+)$/.exec(label)
  return stateProperty
    ? `${formatToken(stateProperty[1])}: ${formatToken(stateProperty[2])}`
    : formatToken(label)
}

const playbackValue = (value: unknown): string => {
  if (value === null || value === undefined || String(value).trim() === '') return 'N/A'
  return String(value)
}

export const playbackDeviceSummaryParts = (
  device: PlaybackDevice,
  formatToken: (value: string) => string = value => value
): string[] => {
  const parts: string[] = []
  if (device.state?.trim()) parts.push(formatToken(device.state.trim()))
  if (device.mode?.trim() && device.mode.trim() !== device.state?.trim()) {
    parts.push(formatToken(device.mode.trim()))
  }
  // Through `playbackValue`, so an absent value reads `N/A` here exactly as it does in the step's
  // change list. The backend's parser creates a variable row before its value is parsed, so a
  // missing value is reachable — and rendering it as `name=` shows unknown data as blank while the
  // other surface for the same step says `N/A`.
  // An unobserved row is omitted rather than shown as `N/A`: `N/A` claims the reading was expected and
  // went missing, whereas this device never had one to report. See `TraceVariable.observed`.
  ;(device.variables || [])
    .filter(variable => variable.observed !== false)
    .forEach(variable => parts.push(
      `${formatToken(variable.name)}=${formatToken(playbackValue(variable.value))}`
    ))
  return parts
}

const sortedPlaybackFacts = (device: PlaybackComparableDevice | null | undefined) => {
  if (!device) return null
  const variables = [...(device.variables || [])]
    .map(variable => [variable.name, variable.value, variable.trust || ''])
    .sort((left, right) => String(left[0]).localeCompare(String(right[0])))
  const securityFacts = new Map<string, {
    propertyScope: string
    mode: string
    name: string
    trust: boolean | null
    privacy: string
  }>()
  ;[...(device.trustPrivacy || []), ...(device.privacies || [])].forEach(entry => {
    const propertyScope = entry.propertyScope || ''
    const mode = entry.mode || ''
    const key = `${propertyScope}:${mode}:${entry.name}`
    const fact = securityFacts.get(key) || {
      propertyScope,
      mode,
      name: entry.name,
      trust: null,
      privacy: ''
    }
    if (entry.trust !== null && entry.trust !== undefined) fact.trust = entry.trust
    if (entry.privacy) fact.privacy = entry.privacy
    securityFacts.set(key, fact)
  })
  const trustPrivacy = [...securityFacts.values()]
    .map(entry => [entry.propertyScope, entry.mode, entry.name, entry.trust, entry.privacy])
    .sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right)))
  return {
    state: device.state || '',
    mode: device.mode || '',
    compromised: device.compromised === true,
    variables,
    trustPrivacy
  }
}

export const playbackDeviceChanged = (
  current: PlaybackComparableDevice,
  previous: PlaybackComparableDevice | null | undefined
): boolean => previous != null
  && JSON.stringify(sortedPlaybackFacts(current)) !== JSON.stringify(sortedPlaybackFacts(previous))

const playbackBooleanValue = (value: boolean): string => value ? 'true' : 'false'

type PlaybackSecurityFact = { name: string; value: string }

const playbackSecurityFacts = (device: PlaybackDevice): Map<string, PlaybackSecurityFact> => {
  const facts = new Map<string, PlaybackSecurityFact>()

  const addFact = (key: string, name: string, value: string) => {
    const previous = facts.get(key)
    facts.set(key, {
      name,
      value: previous ? `${previous.value}; ${value}` : value
    })
  }

  ;(device.variables || []).forEach(variable => {
    const trust = variable.trust?.trim().toLowerCase()
    if (trust === 'trusted' || trust === 'untrusted') {
      addFact(`variable:${variable.name}:trust`, variable.name, `trust=${trust}`)
    }
  })

  const addEntry = (entry: NonNullable<PlaybackDevice['trustPrivacy']>[number]) => {
    const key = `${entry.propertyScope}:${entry.mode || ''}:${entry.name}`
    const values: string[] = []
    if (entry.trust !== null && entry.trust !== undefined) {
      values.push(`trust=${entry.trust ? 'trusted' : 'untrusted'}`)
    }
    const privacy = entry.privacy?.trim().toLowerCase()
    if (privacy === 'public' || privacy === 'private') {
      values.push(`privacy=${privacy}`)
    }
    if (values.length > 0) {
      addFact(`entry:${key}`, entry.name, values.join('; '))
    }
  }

  ;(device.trustPrivacy || []).forEach(addEntry)
  ;(device.privacies || []).forEach(addEntry)
  return facts
}

/**
 * Builds user-facing change facts for the transition into one saved model state.
 * The result deliberately contains labels and values only; callers should never need
 * to expose the generated NuSMV device identifier to explain a transition.
 */
export const playbackDeviceChangeDetails = (
  current: PlaybackDevice,
  previous: PlaybackDevice | null | undefined
): PlaybackDeviceChange | null => {
  if (!previous || !playbackDeviceChanged(current, previous)) return null

  const details: PlaybackDeviceChangeDetail[] = []
  if ((previous.state || '') !== (current.state || '')) {
    details.push({
      kind: 'state',
      previousValue: playbackValue(previous.state),
      currentValue: playbackValue(current.state)
    })
  }
  // `mode` holds state-machine NAMES, not values — `SmvTraceParser` sets `state` to the mode *values*
  // (joined with `;` for a multi-mode device) and `mode` to the matching names. The name set comes from
  // the frozen manifest, so within one trace this row is effectively unreachable: any real mode change
  // moves `state`, which the row above already reports and the canvas pill's title already shows.
  //
  // Kept rather than deleted because it is the honest comparison for the field, and an audit read the
  // canvas as "silently dropping mode changes" on the assumption that mode carried values. It does not.
  if ((previous.mode || '') !== (current.mode || '')) {
    details.push({
      kind: 'mode',
      previousValue: playbackValue(previous.mode),
      currentValue: playbackValue(current.mode)
    })
  }

  // `observed !== false`, for the reason recorded above `playbackDeviceSummaryParts` and applied by
  // `computeNodeRuntimeBadges` on the canvas: a device that never had a reading to report is omitted,
  // not rendered as `N/A`, because `N/A` claims a reading was expected and went missing.
  //
  // This diff was the one reader of the three that did not filter, and the flag can flip between
  // states — the parser creates a row before its value is parsed, and `mergeVariables` only re-applies
  // observability when a value arrives. So a reading that never existed surfaced here as
  // `illuminance: 20 -> N/A`, a change row for a change that did not happen, while the canvas
  // correctly drew nothing and the pool strip showed the true value.
  const observedVariables = (device: PlaybackDevice) =>
    (device.variables || []).filter(variable => variable.observed !== false)

  const previousVariables = new Map(observedVariables(previous).map(variable => [variable.name, variable.value]))
  const currentVariables = new Map(observedVariables(current).map(variable => [variable.name, variable.value]))
  const variableNames = new Set([...previousVariables.keys(), ...currentVariables.keys()])
  variableNames.forEach(name => {
    const before = playbackValue(previousVariables.get(name))
    const after = playbackValue(currentVariables.get(name))
    if (before !== after) {
      details.push({ kind: 'variable', name, previousValue: before, currentValue: after })
    }
  })

  if ((previous.compromised === true) !== (current.compromised === true)) {
    details.push({
      kind: 'compromised',
      previousValue: playbackBooleanValue(previous.compromised === true),
      currentValue: playbackBooleanValue(current.compromised === true)
    })
  }

  const previousSecurity = playbackSecurityFacts(previous)
  const currentSecurity = playbackSecurityFacts(current)
  const securityKeys = new Set([...previousSecurity.keys(), ...currentSecurity.keys()])
  securityKeys.forEach(key => {
    const before = previousSecurity.get(key)
    const after = currentSecurity.get(key)
    if ((before?.value || '') !== (after?.value || '')) {
      details.push({
        kind: 'security',
        name: after?.name || before?.name,
        previousValue: playbackValue(before?.value),
        currentValue: playbackValue(after?.value)
      })
    }
  })

  return details.length > 0
      ? {
        deviceId: current.deviceId,
        deviceLabel: current.deviceLabel?.trim() || '',
        details
      }
    : null
}

/** User-facing environment deltas for the transition into one saved model state. */
export const playbackEnvironmentChangeDetails = (
  current: Array<{ name: string; value?: unknown }> | null | undefined,
  previous: Array<{ name: string; value?: unknown }> | null | undefined
): PlaybackEnvironmentChange[] => {
  if (!previous) return []

  const previousValues = new Map(previous.map(variable => [variable.name, variable.value]))
  const currentValues = new Map((current || []).map(variable => [variable.name, variable.value]))
  const names = new Set([...previousValues.keys(), ...currentValues.keys()])

  return [...names]
    .map(name => ({
      name,
      previousValue: playbackValue(previousValues.get(name)),
      currentValue: playbackValue(currentValues.get(name))
    }))
    .filter(change => change.previousValue !== change.currentValue)
}

const hasConditionValue = (value: unknown) =>
  value !== null && value !== undefined && String(value).trim() !== ''

const quoteConditionValue = (value: unknown) =>
  hasConditionValue(value) ? `"${value}"` : ''

type TraceTranslator = (key: string, params?: Record<string, unknown>) => string

const translateTraceText = (
  translate: TraceTranslator | undefined,
  key: string,
  params: Record<string, unknown>,
  fallback: string
): string => translate ? translate(key, params) : fallback

const formatTraceRelation = (relation?: string, translate?: TraceTranslator): string => {
  const normalized = normalizeModelRelation(relation) || '='
  const relations: Record<string, string> = {
    '=': '=',
    '!=': '!=',
    '>': '>',
    '<': '<',
    '>=': '>=',
    '<=': '<=',
    in: 'in',
    'not in': 'not in'
  }
  if (normalized === 'in') return translate ? translate('app.relationIn') : relations[normalized]
  if (normalized === 'not in') return translate ? translate('app.relationNotIn') : relations[normalized]
  return relations[normalized] || normalized
}

const formatVariableTarget = (
  condition: any,
  device: string,
  key: string,
  translate?: TraceTranslator
): string => {
  const source = String(condition?.variableSource || '').trim().toLowerCase()
  if (source === 'environment') {
    return translateTraceText(translate, 'app.traceVariableInHome', { key },
      `the actual ${key} in the home`)
  }
  if (source === 'reported') {
    return translateTraceText(translate, 'app.traceVariableReported', { device, key },
      `the ${key} ${device} reports`)
  }
  return translateTraceText(translate, 'app.traceVariableSourceUnresolved', { device, key },
    `${device} ${key} (which value it means was not recorded)`)
}

const formatCondition = (condition: any, translate?: TraceTranslator): string => {
  const device = condition?.deviceLabel || condition?.deviceId
    || (translate ? translate('app.unknownDevice') : 'device')
  const key = condition?.targetType === 'state'
    ? (translate ? translate('app.state') : 'state')
    : condition?.key || condition?.attribute || (translate ? translate('app.value') : 'value')
  const relation = formatTraceRelation(condition?.relation, translate)
  const value = quoteConditionValue(condition?.value)
  // A trust or privacy condition is about a *label* on a target, not the target's own value, and
  // `utils/spec.ts` renders it that way (`sensitivity(...)`, `controlSource(...) = untrusted`). This
  // renderer branched only on `state` and dropped the wrapper, so the label value landed in the slot a
  // state value belongs in: specification 6 of the demo scene read `Front Door.LockState = "private"` —
  // domain-impossible, since LockState's own values are locked/unlocked. A user comparing that against
  // the correct formula preview cannot tell which one is their actual property.
  // A variable condition answers one of two different questions, and a verdict is unreadable
  // without knowing which: `environment` is the value in the home (no device participates),
  // `reported` is what this device said. They diverge exactly when the device is compromised, which
  // is the case a counterexample is most often about, so the distinction is rendered rather than
  // collapsed into `device.key`. A condition that never recorded a source is marked unresolved
  // instead of being shown as either one.
  const target = condition?.targetType === 'variable'
    ? formatVariableTarget(condition, device, key, translate)
    : `${device}.${key}`
  if (condition?.targetType === 'privacy') {
    return value ? `sensitivity(${target}) ${relation} ${value}` : `sensitivity(${target})`
  }
  if (condition?.targetType === 'trust') {
    return value ? `controlSource(${target}) ${relation} ${value}` : `controlSource(${target})`
  }
  return value
    ? `${target} ${relation} ${value}`
    : target
}

const joinConditions = (conditions?: any[], translate?: TraceTranslator): string =>
  Array.isArray(conditions) && conditions.length > 0
    ? conditions.map(condition => formatCondition(condition, translate))
      .join(translate ? translate('app.traceConditionAnd') : ' AND ')
    : ''

export const formatTraceSpec = (
  specValue: string | Record<string, any>,
  translate?: TraceTranslator
): string => {
  try {
    const spec = typeof specValue === 'string' ? JSON.parse(specValue) : specValue
    const label = spec.templateLabel || (translate ? translate('app.unknownSpecification') : 'Specification')
    const aPart = joinConditions(spec.aConditions, translate)
    const ifPart = joinConditions(spec.ifConditions, translate)
    const thenPart = joinConditions(spec.thenConditions, translate)

    switch (String(spec.templateId || '')) {
      case '1':
        return aPart ? translateTraceText(translate, 'app.traceSpecAlways', { condition: aPart }, `Always: ${aPart}`) : label
      case '2':
        return aPart ? translateTraceText(translate, 'app.traceSpecEventually', { condition: aPart }, `Eventually: ${aPart}`) : label
      case '3':
        return aPart ? translateTraceText(translate, 'app.traceSpecNever', { condition: aPart }, `Never: ${aPart}`) : label
      case '4':
        return ifPart && thenPart
          ? translateTraceText(translate, 'app.traceSpecImmediate', { ifPart, thenPart }, `Immediately: IF ${ifPart}, THEN ${thenPart}`)
          : label
      case '5':
        return ifPart && thenPart
          ? translateTraceText(translate, 'app.traceSpecResponse', { ifPart, thenPart }, `Response: IF ${ifPart}, THEN eventually ${thenPart}`)
          : label
      case '6':
        return ifPart && thenPart
          ? translateTraceText(translate, 'app.traceSpecPersistence', { ifPart, thenPart }, `Persistence: IF ${ifPart}, THEN ${thenPart} remains true`)
          : label
      case '7':
        return aPart
          ? translateTraceText(translate, 'app.traceSpecSafety', { condition: aPart }, `Safety: ${aPart} must not hold with an untrusted control-source label`)
          : label
      default:
        return label
    }
  } catch {
    return typeof specValue === 'string'
      ? specValue
      : (translate ? translate('app.unknownSpecification') : 'Specification')
  }
}
