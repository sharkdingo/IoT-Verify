import type { DeviceEdge } from '@/types/edge'
import { normalizeNuSmvDeviceName } from './modelRequest'

export type TraceVariableLike = {
  name: string
  value?: unknown
  trust?: string
  /** See `TraceVariable.observed` in `@/types/verify`. Missing means observed. */
  observed?: boolean
}

export type TraceTrustPrivacyLike = {
  name: string
  propertyScope?: 'state' | 'variable' | 'content'
  mode?: string
  trust?: boolean | null
  privacy?: string
}

export type TraceDeviceLike = {
  deviceId?: string | null
  deviceLabel?: string | null
  state?: string | null
  mode?: string | null
  compromised?: boolean | null
  variables?: TraceVariableLike[]
  trustPrivacy?: TraceTrustPrivacyLike[]
  privacies?: TraceTrustPrivacyLike[]
}

export type TraceStateLike = {
  devices?: TraceDeviceLike[]
  envVariables?: TraceVariableLike[]
  triggeredRules?: Array<{ ruleIndex: number; ruleId?: string | null; ruleLabel?: string | null }>
  compromisedAutomationLinks?: Array<{ ruleIndex: number; ruleId?: string | null; ruleLabel?: string | null }>
}

export type TracePlaybackLike = {
  states?: TraceStateLike[]
  selectedStateIndex?: number
} | null | undefined

export const toTraceDeviceId = (nodeId: string): string =>
  normalizeNuSmvDeviceName(nodeId).toLowerCase()

export const traceDeviceMatchesId = (device: { deviceId?: string | null }, nodeId: string): boolean =>
  !!device.deviceId && device.deviceId.toLowerCase() === toTraceDeviceId(nodeId)

/** Index sparse device snapshots once, preserving the latest record and sticky compromise evidence. */
export const buildTraceDevicePlaybackIndex = <T extends TraceDeviceLike>(
  states: Array<{ devices?: T[] }> | undefined,
  selectedIndex: number | undefined
) => {
  const current = new Map<string, T>()
  const previous = new Map<string, T>()
  const compromised = new Set<string>()
  if (!states?.length) return { current, previous, compromised }
  const endIndex = Math.min(Math.max(selectedIndex || 0, 0), states.length - 1)
  const previousIndex = selectedIndex !== undefined && selectedIndex > 0
    ? Math.min(selectedIndex - 1, states.length - 1) : -1
  for (let index = endIndex; index >= 0; index--) {
    const seenInState = new Set<string>()
    for (const device of states[index]?.devices || []) {
      const id = device.deviceId?.toLowerCase()
      // The original per-state lookup selected the first record, including in malformed duplicate input.
      if (!id || seenInState.has(id)) continue
      seenInState.add(id)
      if (!current.has(id)) current.set(id, device)
      if (index <= previousIndex && !previous.has(id)) previous.set(id, device)
      if (selectedIndex !== undefined && selectedIndex >= 0 && device.compromised === true) compromised.add(id)
    }
  }
  return { current, previous, compromised }
}

export const normalizeTraceComparable = (value: unknown) =>
  String(value ?? '').trim()

export const formatRuleApiSignalName = (raw: string): string => {
  const cleaned = String(raw ?? '').replace(/[^a-zA-Z0-9_]/g, '_')
  return cleaned.trim() ? `${cleaned}_a` : String(raw ?? '')
}

const normalizeTraceVariableName = (name: string) =>
  normalizeNuSmvDeviceName(name).toLowerCase()

const traceDeviceNameCandidates = (name: string) => {
  const raw = String(name || '').trim().toLowerCase()
  const normalized = normalizeTraceVariableName(name)
  return new Set([
    raw,
    normalized
  ].filter(Boolean))
}

const traceEnvironmentNameCandidates = (name: string) => {
  const raw = String(name || '').trim().toLowerCase()
  const normalized = normalizeTraceVariableName(name)
  return new Set([
    raw,
    normalized
  ].filter(Boolean))
}

export const traceVariableMatchesName = (variable: TraceVariableLike, name: string) => {
  const variableLower = variable.name.toLowerCase()
  const normalizedVariableLower = normalizeTraceVariableName(variable.name)
  const candidates = traceDeviceNameCandidates(name)
  return candidates.has(variableLower) || candidates.has(normalizedVariableLower)
}

const traceEnvironmentVariableMatchesName = (variable: TraceVariableLike, name: string) => {
  const variableLower = variable.name.toLowerCase()
  const normalizedVariableLower = normalizeTraceVariableName(variable.name)
  const candidates = traceEnvironmentNameCandidates(name)
  return candidates.has(variableLower) || candidates.has(normalizedVariableLower)
}

const splitTraceComparableParts = (value: string) =>
  value
    .split(/[;,]/)
    .map(item => item.trim())
    .filter(Boolean)

/*
 * Several helpers below are module-private rather than exported, and that is a demotion, not a deletion.
 *
 * A sweep for "exports with no importer" flagged them, but each is used *inside* this file — `traceValueEquals`
 * five times, `compareTraceValue` twice. So they were never dead code; only the `export` keyword was surplus, and
 * an export with no reader invites the next person to build a second caller on a helper that was never meant to be
 * part of the surface. The distinction matters: deleting them would have removed live logic.
 *
 * `getTraceEdgeEvaluationIndex` in this same file *was* genuinely dead and is gone — the note at its old position
 * records why it was not a missing extraction.
 */
const traceValueEquals = (actualText: string, expectedText: string): boolean => {
  const actualLower = actualText.toLowerCase()
  const expectedLower = expectedText.toLowerCase()
  if (actualLower === expectedLower) return true
  return splitTraceComparableParts(actualText)
    .some(part => part.toLowerCase() === expectedLower)
}

const compareTraceValue = (actual: unknown, relation: string | undefined, expected: unknown): boolean => {
  const actualText = normalizeTraceComparable(actual)
  const expectedText = normalizeTraceComparable(expected)
  const normalizedRelation = relation || '='
  const actualNumber = Number(actualText)
  const expectedNumber = Number(expectedText)
  const bothNumeric = Number.isFinite(actualNumber) && Number.isFinite(expectedNumber)
  const expectedSet = expectedText
    .split(',')
    .map(item => item.trim())
    .filter(Boolean)

  switch (normalizedRelation) {
    case '=':
    case '==':
    case 'EQ':
      return traceValueEquals(actualText, expectedText)
    case '!=':
    case 'NEQ':
      return !traceValueEquals(actualText, expectedText)
    case '>':
    case 'GT':
      return bothNumeric && actualNumber > expectedNumber
    case '>=':
    case 'GTE':
      return bothNumeric && actualNumber >= expectedNumber
    case '<':
    case 'LT':
      return bothNumeric && actualNumber < expectedNumber
    case '<=':
    case 'LTE':
      return bothNumeric && actualNumber <= expectedNumber
    case 'in':
      return expectedSet.some(item => item.toLowerCase() === actualText.toLowerCase())
    case 'not in':
    case 'not_in':
      return !expectedSet.some(item => item.toLowerCase() === actualText.toLowerCase())
    default:
      return traceValueEquals(actualText, expectedText)
  }
}

export const findTraceVariableAtOrBefore = (
  trace: TracePlaybackLike,
  nodeId: string,
  variableName: string,
  endIndex: number
): TraceVariableLike | null => {
  if (!trace?.states?.length) return null
  const boundedIndex = Math.min(Math.max(endIndex, 0), trace.states.length - 1)

  for (let i = boundedIndex; i >= 0; i--) {
    const state = trace.states[i]
    if (!state?.devices) continue
    const device = state.devices.find(d => traceDeviceMatchesId(d, nodeId))
    const variable = device?.variables?.find(v => traceVariableMatchesName(v, variableName))
    if (variable) return variable
  }
  return null
}

const findTraceEnvironmentVariableAtOrBefore = (
  trace: TracePlaybackLike,
  variableName: string,
  endIndex: number
): TraceVariableLike | null => {
  if (!trace?.states?.length) return null
  const boundedIndex = Math.min(Math.max(endIndex, 0), trace.states.length - 1)

  for (let i = boundedIndex; i >= 0; i--) {
    const state = trace.states[i]
    if (!state?.envVariables) continue
    const variable = state.envVariables.find(v => traceEnvironmentVariableMatchesName(v, variableName))
    if (variable) return variable
  }
  return null
}

const getLatestTraceDeviceForNodeAtOrBefore = (
  trace: TracePlaybackLike,
  nodeId: string,
  endIndex: number
): TraceDeviceLike | null => {
  if (!trace?.states?.length) return null
  const boundedIndex = Math.min(Math.max(endIndex, 0), trace.states.length - 1)
  for (let i = boundedIndex; i >= 0; i--) {
    const state = trace.states[i]
    if (!state?.devices) continue
    const device = state.devices.find(d => traceDeviceMatchesId(d, nodeId))
    if (device) return device
  }
  return null
}

const getLatestTraceStateAtOrBefore = (
  trace: TracePlaybackLike,
  nodeId: string,
  endIndex: number
): string | null =>
  getLatestTraceDeviceForNodeAtOrBefore(trace, nodeId, endIndex)?.state || null

export const getTraceValueForEdge = (
  edge: DeviceEdge,
  trace: TracePlaybackLike,
  stateIndex = trace?.selectedStateIndex || 0
): string | null => {
  const traceDevice = getLatestTraceDeviceForNodeAtOrBefore(trace, edge.from, stateIndex)
  if (edge.itemType === 'state') {
    return traceDevice?.state || getLatestTraceStateAtOrBefore(trace, edge.from, stateIndex)
  }
  if (edge.itemType === 'mode') {
    if (edge.fromApi) {
      const modeVariable = findTraceVariableAtOrBefore(trace, edge.from, edge.fromApi, stateIndex)
      if (modeVariable) return normalizeTraceComparable(modeVariable.value)
    }
    return traceDevice?.mode || traceDevice?.state || getLatestTraceStateAtOrBefore(trace, edge.from, stateIndex)
  }
  if (edge.fromApi && edge.itemType === 'variable') {
    const variable = findTraceVariableAtOrBefore(trace, edge.from, edge.fromApi, stateIndex)
    if (variable) return normalizeTraceComparable(variable.value)

    const environmentVariable = findTraceEnvironmentVariableAtOrBefore(trace, edge.fromApi, stateIndex)
    return environmentVariable ? normalizeTraceComparable(environmentVariable.value) : null
  }
  if (edge.fromApi && edge.itemType === 'api') {
    const directSignal = findTraceVariableAtOrBefore(trace, edge.from, edge.fromApi, stateIndex)
    if (directSignal) return normalizeTraceComparable(directSignal.value)
    const generatedSignal = findTraceVariableAtOrBefore(
      trace,
      edge.from,
      formatRuleApiSignalName(edge.fromApi),
      stateIndex
    )
    if (generatedSignal) return normalizeTraceComparable(generatedSignal.value)
  }
  return null
}

const hasValue = (value?: string | null) =>
  value !== null && value !== undefined && String(value).trim() !== ''

export const isEdgeConditionSatisfied = (
  edge: DeviceEdge,
  trace: TracePlaybackLike,
  stateIndex = trace?.selectedStateIndex || 0
): boolean => {
  if (!trace?.states || trace.selectedStateIndex === undefined || trace.selectedStateIndex < 0) return false
  const actual = getTraceValueForEdge(edge, trace, stateIndex)
  if (actual === null || actual === undefined) return false
  if (!hasValue(edge.value) && edge.itemType === 'api') {
    return traceValueEquals(normalizeTraceComparable(actual), 'TRUE')
  }
  return compareTraceValue(actual, edge.relation || '=', edge.value)
}

/*
 * `getTraceEdgeEvaluationIndex` used to sit here, exported and never called.
 *
 * It is not a missing extraction: `CanvasBoard`'s two "previous state" sites return **null** at index 0, while
 * this returned 0 — a different rule, so folding them together would have changed behaviour rather than removed
 * duplication. Deleted as dead code.
 */

type FrozenRuleIdentity = {
  ruleIndex: number
  ruleId?: string | null
}

const indexFrozenRules = (rules: FrozenRuleIdentity[] | undefined) => {
  const ids = new Set<string>()
  const positions = new Set<number>()
  for (const rule of rules || []) {
    if (rule.ruleId == null) positions.add(rule.ruleIndex)
    else ids.add(String(rule.ruleId))
  }
  return { ids, positions }
}

export type TraceEdgePlaybackState = {
  traceActive: boolean
  linkCompromised: boolean
  shouldAnimate: boolean
}

/** Resolve all connections in O(E + T + C), including multi-source and ambiguous rule identities. */
export const buildTraceEdgePlaybackStates = (
  allEdges: DeviceEdge[],
  trace: TracePlaybackLike
): Map<DeviceEdge, TraceEdgePlaybackState> => {
  const selectedIndex = trace?.selectedStateIndex
  const state = selectedIndex !== undefined && selectedIndex >= 0 ? trace?.states?.[selectedIndex] : undefined
  const triggered = indexFrozenRules(state?.triggeredRules)
  const compromised = indexFrozenRules(state?.compromisedAutomationLinks)
  const ruleIndexes = new Map<string, number | null>()
  for (const edge of allEdges) {
    if (edge.ruleId == null) continue
    const id = String(edge.ruleId)
    const index = Number.isSafeInteger(edge.ruleIndex) ? edge.ruleIndex as number : null
    if (!ruleIndexes.has(id)) ruleIndexes.set(id, index)
    else if (ruleIndexes.get(id) !== index) ruleIndexes.set(id, null)
  }

  const result = new Map<DeviceEdge, TraceEdgePlaybackState>()
  for (const edge of allEdges) {
    const validIndex = Number.isSafeInteger(edge.ruleIndex)
    const id = edge.ruleId == null ? null : String(edge.ruleId)
    // A stable id must denote one rule position; several sources at that position are legitimate.
    // Id-less matching requires both sides to lack an id and address the same frozen rule list.
    const uniqueId = Boolean(id && ruleIndexes.get(id) === edge.ruleIndex)
    const traceActive = validIndex && (id === null
      ? triggered.positions.has(edge.ruleIndex as number)
      : uniqueId && triggered.ids.has(id))
    const linkCompromised = validIndex && (id === null
      ? compromised.positions.has(edge.ruleIndex as number)
      : uniqueId && compromised.ids.has(id))
    result.set(edge, { traceActive, linkCompromised, shouldAnimate: traceActive && !linkCompromised })
  }
  return result
}
