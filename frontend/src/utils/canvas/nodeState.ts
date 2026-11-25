import type { DeviceManifest } from '@/types/device'
import { isConcreteDeviceState, trimAuthoredDeviceState } from '@/utils/stateConditionDomain'

type StateManifest = Pick<DeviceManifest, 'Modes' | 'InitState' | 'WorkingStates'>

const stateText = (value: unknown): string => trimAuthoredDeviceState(String(value ?? ''))

const normalizedWorkingStates = (manifest?: StateManifest | null): string[] =>
  Array.isArray(manifest?.WorkingStates)
    ? manifest.WorkingStates
        .map(state => stateText(state?.Name))
        .filter(Boolean)
    : []

export const hasModeledStateMachine = (manifest?: StateManifest | null): boolean =>
  Boolean(
    Array.isArray(manifest?.Modes) && manifest.Modes.length > 0
    && normalizedWorkingStates(manifest).length > 0
  )

export const resolveEffectiveNodeState = (
  configuredState: unknown,
  manifest?: StateManifest | null,
  fallback = ''
): string => {
  const configured = stateText(configuredState)
  const initial = stateText(manifest?.InitState)
  const legalStates = normalizedWorkingStates(manifest)

  if (!hasModeledStateMachine(manifest)) return configured || initial || fallback
  if (isConcreteDeviceState(manifest, configured)) return configured
  if (initial && legalStates.includes(initial)) return initial
  return legalStates[0] || fallback
}
