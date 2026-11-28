import type { DeviceEdge } from '@/types/edge'
import { normalizeNuSmvDeviceName } from './modelRequest'

export type TraceVariableLike = {
  name: string
  value?: unknown
  trust?: string | null
  /** See `TraceVariable.observed` in `@/types/verify`. Missing means observed. */
  observed?: boolean
}

export type TraceTrustPrivacyLike = {
  name: string
  propertyScope?: 'state' | 'variable' | 'content'
  mode?: string | null
  trust?: boolean | null
  privacy?: string | null
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

/** Index sparse snapshots and readings once, preserving first matches and sticky compromise evidence. */
export const buildTraceDevicePlaybackIndex = <T extends TraceDeviceLike>(
  states: Array<{ devices?: T[] }> | undefined,
  selectedIndex: number | undefined
) => {
  const current = new Map<string, T>()
  const previous = new Map<string, T>()
  const compromised = new Set<string>()
  type Variable = NonNullable<T['variables']>[number]
  type Reading = { variable: Variable; order: number }
  const currentVariables = new Map<string, Map<string, Reading>>()
  const previousVariables = new Map<string, Map<string, Reading>>()
  let readingOrder = 0
  const findVariable = (index: Map<string, Map<string, Reading>>, nodeId: string, name: string) => {
    const readings = index.get(toTraceDeviceId(nodeId))
    let match: Reading | undefined
    for (const candidate of traceDeviceNameCandidates(name)) {
      const reading = readings?.get(candidate)
      if (reading && (!match || reading.order < match.order)) match = reading
    }
    return match?.variable ?? null
  }
  const result = {
    current, previous, compromised,
    getCurrentVariable: (nodeId: string, name: string) => findVariable(currentVariables, nodeId, name),
    getPreviousVariable: (nodeId: string, name: string) => findVariable(previousVariables, nodeId, name)
  }
  if (!states?.length) return result
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
      let currentReadings = currentVariables.get(id)
      if (!currentReadings) currentVariables.set(id, currentReadings = new Map())
      let previousReadings = previousVariables.get(id)
      if (!previousReadings) previousVariables.set(id, previousReadings = new Map())
      for (const variable of device.variables || []) {
        const reading = { variable: variable as Variable, order: readingOrder++ }
        // Alias matches must select the newest state and then its first variable,
        // even when the raw and sanitized names address different records.
        for (const key of [variable.name.toLowerCase(), normalizeTraceVariableName(variable.name)]) {
          if (!currentReadings.has(key)) currentReadings.set(key, reading)
          if (index <= previousIndex && !previousReadings.has(key)) previousReadings.set(key, reading)
        }
      }
    }
  }
  return result
}

export const normalizeTraceComparable = (value: unknown) =>
  String(value ?? '').trim()

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

export const traceVariableMatchesName = (variable: TraceVariableLike, name: string) => {
  const variableLower = variable.name.toLowerCase()
  const normalizedVariableLower = normalizeTraceVariableName(variable.name)
  const candidates = traceDeviceNameCandidates(name)
  return candidates.has(variableLower) || candidates.has(normalizedVariableLower)
}

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
