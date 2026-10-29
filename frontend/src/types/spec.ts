/* ==================== 规约相关类型 ==================== */

export type SpecSide = 'a' | 'if' | 'then'
export type SpecTargetType = 'state' | 'mode' | 'variable' | 'api' | 'trust' | 'privacy'
export type SpecPropertyScope = 'state' | 'variable'
/**
 * Which of two different questions a `variable` condition asks. There is no default: the two
 * answers diverge exactly when a device is compromised, so picking one for the author is what
 * previously let a specification read SATISFIED against a falsified reading.
 *
 * - `environment` — the shared pool value, i.e. what actually happened in the home. No device
 *   participates in the formula. Only valid for a shared variable (manifest `IsInside` not true),
 *   and the selected device must read it (`Reads` not false), matching backend admission.
 * - `reported` — what this device said. Device-level, and the only meaningful answer for a
 *   device-local variable (`IsInside: true`), which has no pool value at all.
 */
export type SpecVariableSource = 'environment' | 'reported'
export type SpecTemplateId = '1' | '2' | '3' | '4' | '5' | '6' | '7'

export interface SpecCondition {
    id: string
    side: SpecSide
    deviceId: string
    deviceLabel: string
    targetType: SpecTargetType
    key: string
    propertyScope?: SpecPropertyScope
    /** Required whenever `targetType` is `variable`; absent means the author has not decided yet. */
    variableSource?: SpecVariableSource
    relation: string
    value: string
}

export interface SpecTemplate {
    id: SpecTemplateId
    label: string
    labelKey?: string
}

/**
 * 规约模板详细配置
 */
export type SpecTemplateType = 
    | 'always'      // A holds forever - 只有a条件
    | 'eventually'  // A will happen later - 只有a条件
    | 'never'       // A never happens - 只有a条件
    | 'immediate'   // A → AX B (next state) - if + then
    | 'response'    // A → ◇B (eventually) - if + then
    | 'persistence' // A -> eventually always B - if + then
    | 'safety'      // untrusted → ¬A - only a-conditions

export interface SpecTemplateDetail extends SpecTemplate {
    type: SpecTemplateType
    description: string
    descriptionKey?: string
    requiredSides: SpecSide[]  // 需要配置的条件位置
    formulaPreview: string     // Template preview text; actual CTL/LTL is rebuilt from templateId + conditions
}

export interface Specification {
    id: string
    name: string
    templateId: SpecTemplateId
    templateLabel: string
    aConditions: SpecCondition[]
    ifConditions: SpecCondition[]
    thenConditions: SpecCondition[]
    formula?: string // Display-only formula preview/cache; verification rebuilds CTL/LTL from templateId + conditions
    devices?: Array<{deviceId: string, deviceLabel: string, selectedApis: string[]}> // Multi-device support
}

// NOTE: the runtime option lists `relationOperators` and `targetTypes` (and their
// derived types) live in `@/assets/config/specTemplates.ts`, which is what components
// import. Duplicate copies previously here were unused and have been removed to avoid
// two sources drifting apart.
