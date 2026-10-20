// src/types/device.ts

import type { Specification } from "./spec"

// ==== 核心 Manifest 结构 ====

export interface InternalVariable {
    Name: string
    Description?: string
    IsInside: boolean
    FalsifiableWhenCompromised: boolean
    /**
     * Only meaningful for a shared variable (IsInside=false): whether this device reads the value,
     * so its rules and specifications may use it as a condition source. Required on a shared declaration and rejected on a device-local one.
     * `false` is a device that only affects the value: it contributes the domain and its declared
     * effect but gets no read mirror or value-condition source capability. Specifications may still
     * check its source or sensitivity labels.
     *
     * Required on a shared declaration and rejected on a device-local one, so a capability can never
     * come from a missing field.
     */
    Reads?: boolean
    Trust: string           // "trusted" | "untrusted"
    Privacy: string         // "public" | "private"
    // 数值型属性
    LowerBound?: number
    UpperBound?: number
    // Required when this is a shared (IsInside=false) numeric domain.
    NaturalChangeRate?: string
    // 枚举型属性
    Values?: string[]
}

export interface Dynamic {
    VariableName: string
    // Backend Dynamic requires exactly one of Value XOR ChangeRate (both optional here).
    ChangeRate?: string
    Value?: string
}

export interface WorkingState {
    Name: string
    Dynamics?: Dynamic[]
    Description?: string
    Trust: string           // "trusted" | "untrusted"
    Privacy: string         // "public" | "private"
}

// Matches backend device-template-schema.json Transition.Trigger.
export interface DeviceTrigger {
    Attribute: string
    Relation: string
    Value: string
}

// Transition-only assignment. API actions change modeled state through EndState.
export interface DeviceAssignment {
    Attribute: string
    Value: string
}

export interface DeviceAPI {
    Name: string
    // Required; an empty string deliberately means callable from any state.
    StartState: string
    EndState: string
    // A device command/action, not a network endpoint. It changes state only.
    // backend/device-template-schema.json is authoritative: API Trigger is always null.
    Trigger?: null
    // Required choice: true exposes a one-step automation/specification trigger.
    Signal: boolean
    // Optional; false by default. Allows one selected content-sensitivity label on this action.
    AcceptsContent?: boolean
    Description?: string
}

export interface DeviceTransition {
    Name: string
    StartState?: string
    EndState?: string | null
    Trigger?: DeviceTrigger | null
    // The formal model accepts one state effect OR one assignment, never several.
    Assignments?: [] | [DeviceAssignment]
    Description?: string
}

// Matches backend DeviceTemplateDto.DeviceManifest.Content { Name, Description, Privacy }.
export interface DeviceContent {
    Name: string
    Description?: string
    Privacy: string        // "public" | "private"
}

export interface DeviceManifest {
    Name: string
    Description?: string
    Icon?: string
    Modes?: string[]
    InternalVariables?: InternalVariable[]
    // Domain/default for a shared value this template affects; does not grant read access.
    ImpactedVariables?: string[]
    InitState?: string
    WorkingStates?: WorkingState[]
    Transitions?: DeviceTransition[]
    APIs?: DeviceAPI[]
    Contents?: DeviceContent[]
}

export interface DeviceTemplate {
    id?: number      // 后端 Long；创建时无 id，响应中才有
    name: string
    manifest: DeviceManifest
    defaultTemplate?: boolean
}

// ==== 视图辅助类型 (View Models) ====

export interface BasicDeviceInfo {
    name: string
    instanceLabel: string
    description: string
    initState: string
    impactedVariables: string[]
}

export interface DeviceVariableView {
    name: string
    value: string
    trust: string
}

export interface DeviceStateView {
    name: string
    description: string
    trust: string
}

export interface DeviceApiView {
    name: string
    from: string
    to: string
    description: string
}

export interface DeviceDialogMeta {
    nodeId: string
    deviceName: string
    label: string
    manifest: DeviceManifest | null
    specs: Specification[]
}
