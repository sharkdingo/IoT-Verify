import type { SelectedAttackPoint } from './attackScenario'
import type { EnvironmentValueProvenance } from './model'

export type AttackPointUnit = 'BEHAVIOR_CHANGING_DEVICE_INSTANCE_OR_AUTOMATION_LINK'

export type AttackEffect =
  | 'DECLARED_FALSIFIABLE_READING_NONDETERMINISTIC_WITHIN_DECLARED_DOMAIN'
  | 'COMMAND_TO_COMPROMISED_TARGET_IS_DROPPED'
  | 'COMMAND_ON_COMPROMISED_AUTOMATION_LINK_IS_DROPPED'

export type AttackSelectionPolicy =
  | 'NOT_MODELED'
  | 'EXACT_ATTACK_POINTS'
  | 'UP_TO_ATTACK_BUDGET_NONDETERMINISTIC'

export type TrustPropagationPolicy =
  'TARGET_UNTRUSTED_IF_ALL_TRIGGER_SOURCES_UNTRUSTED'

export type PrivacyPropagationPolicy =
  | 'TARGET_PRIVATE_IF_ANY_TRIGGER_OR_SELECTED_CONTENT_PRIVATE'
  | 'NOT_MODELED'

export type LabelPropagationScope = 'AUTOMATION_RULE_COMMANDS_ONLY'

export type EnvironmentEvolutionEffect =
  | 'DECLARED_NUMERIC_RATES_AND_DEVICE_EFFECTS_WITHIN_DOMAIN'
  | 'UNWRITTEN_DISCRETE_VALUES_NONDETERMINISTIC_WITHIN_DECLARED_DOMAIN'
  | 'DEVICE_WRITTEN_DISCRETE_VALUES_HOLD_WHEN_NO_DECLARED_EFFECT_APPLIES'

export type LocalVariableFallbackPolicy =
  'STUTTER_WHEN_NO_DECLARED_EVOLUTION'

export interface ModelSemantics {
  attackPointUnit: AttackPointUnit
  attackSelectionPolicy: AttackSelectionPolicy
  selectedAttackPoints?: SelectedAttackPoint[]
  attackEffects: AttackEffect[]
  modeledDeviceAttackPointCount: number
  modeledFalsifiableReadingDeviceCount: number
  modeledAutomationLinkAttackPointCount: number
  modeledAttackPointCount: number
  trustPropagationPolicy: TrustPropagationPolicy
  privacyPropagationPolicy: PrivacyPropagationPolicy
  labelPropagationScope: LabelPropagationScope
  environmentEvolutionEffects: EnvironmentEvolutionEffect[]
  localVariableFallbackPolicy: LocalVariableFallbackPolicy
}

/** User-facing scope frozen at submission for one verification or simulation run. */
export interface ModelRunSnapshot {
  capturedAt: string
  deviceCount: number
  ruleCount: number
  specificationCount: number
  environmentVariableCount: number
  deviceTemplateCount: number
  modelFingerprint?: string | null
  templatesFrozen: true
  environmentProvenance?: EnvironmentValueProvenance[]
}

/** Authoritative identities of the current persisted board, captured together. */
export interface BoardModelFingerprints {
  verification: string
  simulation: string
}

/** A run's captured model compared with the current persisted board. */
export type RunBoardComparison = 'UNCHANGED' | 'CHANGED' | 'UNAVAILABLE' | 'NOT_COMPARED'
