import type { DeviceManifest } from './device'
import type { NodePrivacyState, NodeVariableState } from './node'
import type { RuleSourceItemType } from './rule'
import type { SpecPropertyScope, SpecTargetType, SpecTemplateId, SpecVariableSource } from './spec'

export interface PortableSceneTemplate {
  name: string
  manifest: DeviceManifest
}

export interface PortableSceneDevice {
  id: string
  templateName: string
  label: string
  position: { x: number; y: number }
  state?: string
  width: number
  height: number
  currentStateTrust?: 'trusted' | 'untrusted'
  currentStatePrivacy?: 'public' | 'private'
  variables?: NodeVariableState[]
  privacies?: NodePrivacyState[]
}

export interface PortableSceneEnvironmentVariable {
  name: string
  value: string
  trust: 'trusted' | 'untrusted'
  privacy: 'public' | 'private'
}

export interface PortableSceneRuleSource {
  fromId: string
  fromApi: string
  itemType: RuleSourceItemType
  relation?: string
  value?: string
}

export interface PortableSceneRule {
  name?: string
  sources: PortableSceneRuleSource[]
  toId: string
  toApi: string
  contentDevice?: string
  content?: string
}

export interface PortableSceneCondition {
  deviceId: string
  targetType: SpecTargetType
  key: string
  propertyScope?: SpecPropertyScope
  /** Required for a `variable` condition; a scene without it is rejected rather than defaulted. */
  variableSource?: SpecVariableSource
  relation: string
  value: string
}

export interface PortableSceneSpecification {
  name: string;
  templateId: SpecTemplateId
  aConditions: PortableSceneCondition[]
  ifConditions: PortableSceneCondition[]
  thenConditions: PortableSceneCondition[]
}

export interface PortableSceneFile {
  schema: 'iot-verify.board-scene'
  version: 6
  templates: PortableSceneTemplate[]
  devices: PortableSceneDevice[]
  environmentVariables: PortableSceneEnvironmentVariable[]
  rules: PortableSceneRule[]
  specs: PortableSceneSpecification[]
}
