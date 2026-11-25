import type { DeviceManifest } from '@/types/device'

type Manifest = DeviceManifest | null | undefined

const declaredStates = (manifest: Manifest): string[] =>
  (manifest?.WorkingStates || []).map(state => state.Name?.trim()).filter(Boolean)

const stateAxes = (manifest: Manifest, trim = (state: string) => state.trim()): Set<string>[] => {
  const modes = manifest?.Modes || []
  const axes = modes.map(() => new Set<string>())
  for (const state of manifest?.WorkingStates || []) {
    const parts = (state.Name ?? '').split(';').map(trim)
    if (parts.length !== modes.length) continue
    parts.forEach((part, index) => {
      if (part && part !== '_') axes[index]?.add(part)
    })
  }
  return axes
}

export const getModeStateValues = (manifest: Manifest, mode: string): string[] => {
  const index = manifest?.Modes?.indexOf(mode) ?? -1
  return index < 0 ? [] : [...(stateAxes(manifest)[index] || [])]
}

// Match Java's String.trim() without removing non-ASCII characters that belong to the name.
export const trimAuthoredDeviceState = (value: string): string =>
  value.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '')

export const isConcreteDeviceState = (manifest: Manifest, value: string): boolean => {
  const axes = stateAxes(manifest, trimAuthoredDeviceState)
  const parts = value.split(';').map(trimAuthoredDeviceState)
  return parts.length === axes.length && parts.every((part, index) => axes[index].has(part))
}

const isAuthoredState = (value: string, axes: Set<string>[]): boolean => {
  if (!value.includes(';')) return axes.filter(axis => axis.has(value)).length === 1
  const parts = value.split(';').map(part => part.trim())
  const isConcrete = (part: string) => Boolean(part && part !== '_')
  return parts.length === axes.length && parts.some(isConcrete)
    && parts.every((part, index) => !isConcrete(part) || axes[index]?.has(part))
}

// Keep valid saved compositions selectable without filling the picker with every combination.
export const getStateConditionOptions = (manifest: Manifest, selected: string[]): string[] => {
  const axes = stateAxes(manifest)
  return [...new Set([...declaredStates(manifest), ...selected.filter(value => isAuthoredState(value, axes))])]
}

/** Preserve tuple separators unless the source is known to have only one mode. */
export const splitConditionValueList = (value: unknown, targetType?: string, modeCount?: number): string[] =>
  String(value ?? '').split(targetType === 'state' && modeCount !== 1 ? /[,|]/ : /[,;|]/)
    .map(part => part.trim()).filter(Boolean)

/** Compare finite state/mode sets without changing the operand the editor or repair displays. */
export const canonicalStateModeMembership = (
  value: unknown, targetType: 'state' | 'mode', modeCount?: number
): string => {
  const operands = splitConditionValueList(value, targetType, modeCount).map(operand => {
    if (targetType !== 'state' || modeCount === undefined || modeCount < 2 || !operand.includes(';')) return operand
    const parts = operand.split(';')
    if (parts.length !== modeCount) return operand
    return parts.map(part => part.trim() || '_').join(';')
  })
  return [...new Set(operands)].sort().join(',')
}
