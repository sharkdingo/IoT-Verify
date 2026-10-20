import type {
    DeviceManifest,
    DeviceTemplate,
    InternalVariable
} from '../types/device'
import type { DeviceNode } from '../types/node'
import { REQUEST_LIMITS } from '../constants/requestLimits'
import { PRIVACY_VALUE_SET, TRUST_VALUE_SET } from './deviceRuntime'

/** Preserve Array.find's first alias match while sharing constant-time template lookups. */
export const indexDeviceTemplates = (templates: readonly DeviceTemplate[]) => {
    const index = new Map<string, DeviceTemplate>()
    for (const template of templates) {
        for (const alias of [template.name, template.manifest?.Name]) {
            const key = String(alias || '').trim().toLowerCase()
            if (key && !index.has(key)) index.set(key, template)
        }
    }
    return index
}

// --- 图标与路径 ---

const deviceIconModules = import.meta.glob('../assets/*/*.svg', {
    eager: true,
    query: '?url',
    import: 'default'
}) as Record<string, string>

/**
 * Template name to asset directory. Exported because the bundled-artwork guard must exercise this
 * mapping rather than re-implement it: a test carrying its own copy stays green when the production
 * separator changes, which is the one failure it exists to catch.
 */
export const normalizeAssetFolder = (name: string) =>
    String(name || 'Device').trim().replace(/\s+/g, '_')

const normalizeStateName = (state: string) => String(state || 'Working').trim()

const svgDataUri = (svg: string): string =>
    `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`

const escapeXml = (value: string): string =>
    value
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')

const getTemplateInitials = (name: string): string => {
    const words = String(name || 'Device')
        .replace(/[_-]+/g, ' ')
        .split(/\s+/)
        .filter(Boolean)

    const initials = words.length > 1
        ? words.slice(0, 2).map(word => word.charAt(0)).join('')
        : (words[0] || '?').slice(0, 2)

    return initials.toUpperCase()
}

export const createGeneratedDeviceIcon = (deviceType: string, state?: string): string => {
    const name = String(deviceType || 'Device').replace(/_/g, ' ')
    // SVG images are separate documents: host CSS variables cannot supply their paint.
    const accent = 'hsl(20 36% 42%)'
    const soft = 'hsl(38 28% 94%)'
    const mid = 'hsl(28 24% 70%)'
    const initials = escapeXml(getTemplateInitials(name))
    const stateLabel = escapeXml(normalizeStateName(state || '').slice(0, 14))
    const showState = stateLabel && stateLabel.toLowerCase() !== 'working'

    return svgDataUri(`
<svg width="72" height="72" viewBox="0 0 72 72" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect x="6" y="6" width="60" height="60" rx="16" fill="${soft}" stroke="${accent}" stroke-width="3"/>
  <rect x="18" y="18" width="36" height="27" rx="6" fill="white" stroke="${mid}" stroke-width="3"/>
  <path d="M25 28h22M25 35h16" stroke="${accent}" stroke-width="3" stroke-linecap="round"/>
  <circle cx="49" cy="51" r="8" fill="${accent}"/>
  <text x="36" y="59" text-anchor="middle" font-family="Inter, Arial, sans-serif" font-size="${showState ? 8 : 13}" font-weight="800" fill="hsl(30 11% 14%)">${showState ? stateLabel : initials}</text>
</svg>`)
}

const getStateVariants = (state: string): string[] => {
    const cleanState = normalizeStateName(state)
    // Asset filenames cannot carry the spaces that state names do, so the bundled icons encode a
    // space as "_" ("taking photo" -> taking_photo.svg, "auto;emergency heat" ->
    // auto;emergency_heat.svg). Probing only the raw name left six bundled states showing another
    // state's artwork while their own icon sat on disk unreachable — and never a visibly missing
    // icon, because getDeviceIconUrl serves the first file in the folder before the generated
    // placeholder, so this class of bug is silent by construction.
    const underscored = cleanState.replace(/\s+/g, '_')
    // Trimmed per segment, not just at the outer edges: normalizeStateName cannot reach the inner
    // whitespace of "auto ; heat", so an untrimmed split yields "auto " -> "auto_" and probes a
    // filename no asset uses, losing the compound-state fallback entirely.
    const firstSegment = cleanState.includes(';') ? cleanState.split(';')[0].trim() : ''
    const underscoredFirstSegment = firstSegment.replace(/\s+/g, '_')
    const variants = [
        cleanState,
        underscored,
        cleanState.toLowerCase(),
        underscored.toLowerCase(),
        cleanState.charAt(0).toUpperCase() + cleanState.slice(1).toLowerCase(),
        firstSegment,
        underscoredFirstSegment,
        firstSegment.toLowerCase(),
        underscoredFirstSegment.toLowerCase(),
        'Working',
        'working',
        'On',
        'on',
        'Off',
        'off'
    ]

    return [...new Set(variants.filter(Boolean))]
}

const getBundledDeviceIconPath = (folder: string, state: string): string | null => {
    const normalizedFolder = normalizeAssetFolder(folder)

    for (const stateName of getStateVariants(state)) {
        const path = deviceIconModules[`../assets/${normalizedFolder}/${stateName}.svg`]
        if (path) return path
    }

    return null
}

const firstBundledIconByFolder = new Map<string, string>()
for (const path of Object.keys(deviceIconModules).sort((a, b) => a.localeCompare(b))) {
    const folder = path.slice('../assets/'.length, path.lastIndexOf('/'))
    if (!firstBundledIconByFolder.has(folder)) firstBundledIconByFolder.set(folder, deviceIconModules[path])
}

const getFirstBundledDeviceIconPath = (folder: string): string | null =>
    firstBundledIconByFolder.get(normalizeAssetFolder(folder)) || null

const isSafeManifestIcon = (icon: string | undefined | null): icon is string => {
    if (!icon || typeof icon !== 'string') return false
    const trimmed = icon.trim()
    if (trimmed.length === 0 || trimmed.length > 262144) return false

    return /^data:image\/(svg\+xml|png|jpe?g|webp|gif)(;[^,]+)?,/i.test(trimmed)
}

const getManifestIcon = (manifest?: DeviceManifest | null): string | null => {
    const icon = manifest?.Icon?.trim()
    return isSafeManifestIcon(icon) ? icon : null
}

export const getDeviceIconUrl = (
    deviceType: string,
    state: string = 'Working',
    manifest?: DeviceManifest | null
): string => {
    const manifestIcon = getManifestIcon(manifest)
    if (manifestIcon) return manifestIcon

    const folder = normalizeAssetFolder(deviceType)
    return getBundledDeviceIconPath(folder, state)
        || getFirstBundledDeviceIconPath(folder)
        || createGeneratedDeviceIcon(deviceType, state)
}

export const getNodeIcon = (
    node: DeviceNode,
    manifestOrState?: DeviceManifest | string | null,
    stateOverride?: string
) => {
    const manifest = typeof manifestOrState === 'object' ? manifestOrState : null
    const explicitState = typeof manifestOrState === 'string' ? manifestOrState : stateOverride
    const currentState = explicitState || node.state || manifest?.InitState || 'Working'

    return getDeviceIconUrl(node.templateName, currentState, manifest)
}

// --- 校验逻辑 ---

export const resolveImpactEnvironmentDefinition = (
    manifest: DeviceManifest | null | undefined,
    name: string
): InternalVariable | undefined => {
    const target = String(name || '').trim()
    if (!manifest || !target) return undefined
    // One array holds every shared declaration, read or affect-only, so one lookup resolves the domain.
    return manifest.InternalVariables?.find(variable =>
        variable?.Name === target && variable.IsInside !== true
    )
}

export const MANIFEST_VALIDATION_MESSAGE_KEYS = {
    invalidObject: 'app.manifestValidation.invalidObject',
    missingName: 'app.manifestValidation.missingName',
    fieldMustBeArray: 'app.manifestValidation.fieldMustBeArray',
    stateMachineFieldRequired: 'app.manifestValidation.stateMachineFieldRequired',
    initStateUndefined: 'app.manifestValidation.initStateUndefined',
    workingStateInvariantUnsupported: 'app.manifestValidation.workingStateInvariantUnsupported',
    workingStateSecurityLabelsRequired: 'app.manifestValidation.workingStateSecurityLabelsRequired',
    workingStateArityMismatch: 'app.manifestValidation.workingStateArityMismatch',
    workingStateModeValueRequired: 'app.manifestValidation.workingStateModeValueRequired',
    workingStateDuplicate: 'app.manifestValidation.workingStateDuplicate',
    workingStateLabelConflict: 'app.manifestValidation.workingStateLabelConflict',
    internalVariableNameRequired: 'app.manifestValidation.internalVariableNameRequired',
    internalVariableDuplicate: 'app.manifestValidation.internalVariableDuplicate',
    internalVariableSecurityLabelsRequired: 'app.manifestValidation.internalVariableSecurityLabelsRequired',
    internalVariableFalsifiableRequired: 'app.manifestValidation.internalVariableFalsifiableRequired',
    internalVariableScopeRequired: 'app.manifestValidation.internalVariableScopeRequired',
    internalVariableDomainRequired: 'app.manifestValidation.internalVariableDomainRequired',
    numericBoundsInvalid: 'app.manifestValidation.numericBoundsInvalid',
    numericBoundsOrderInvalid: 'app.manifestValidation.numericBoundsOrderInvalid',
    sharedNumericNaturalChangeRateRequired: 'app.manifestValidation.sharedNumericNaturalChangeRateRequired',
    naturalChangeRateInvalid: 'app.manifestValidation.naturalChangeRateInvalid',
    naturalChangeRateSpanTooWide: 'app.manifestValidation.naturalChangeRateSpanTooWide',
    naturalChangeRateNumericOnly: 'app.manifestValidation.naturalChangeRateNumericOnly',
    environmentDomainNameRequired: 'app.manifestValidation.environmentDomainNameRequired',
    environmentDomainDuplicate: 'app.manifestValidation.environmentDomainDuplicate',
    environmentDomainConflictsWithVariable: 'app.manifestValidation.environmentDomainConflictsWithVariable',
    environmentDomainSecurityLabelsRequired: 'app.manifestValidation.environmentDomainSecurityLabelsRequired',
    environmentDomainValuesRequired: 'app.manifestValidation.environmentDomainValuesRequired',
    impactedVariableNameRequired: 'app.manifestValidation.impactedVariableNameRequired',
    impactedVariableDuplicate: 'app.manifestValidation.impactedVariableDuplicate',
    impactedVariableConflictsWithLocal: 'app.manifestValidation.impactedVariableConflictsWithLocal',
    impactedVariableDomainRequired: 'app.manifestValidation.impactedVariableDomainRequired',
    environmentDomainNotImpacted: 'app.manifestValidation.environmentDomainNotImpacted',
    contentNameRequired: 'app.manifestValidation.contentNameRequired',
    contentPrivacyRequired: 'app.manifestValidation.contentPrivacyRequired',
    transitionSignalUnsupported: 'app.manifestValidation.transitionSignalUnsupported',
    apiStartStateRequired: 'app.manifestValidation.apiStartStateRequired',
    apiSignalRequired: 'app.manifestValidation.apiSignalRequired',
    apiAcceptsContentBoolean: 'app.manifestValidation.apiAcceptsContentBoolean'
} as const

export type ManifestValidationCode = keyof typeof MANIFEST_VALIDATION_MESSAGE_KEYS

export interface ManifestValidationResult {
    valid: boolean
    msg?: string
    code?: ManifestValidationCode
    params?: Record<string, string | number>
}

const invalidManifest = (
    code: ManifestValidationCode,
    msg: string,
    params: Record<string, string | number> = {}
): ManifestValidationResult => ({ valid: false, code, params, msg })

export interface NaturalChangeRateRange {
    lower: number
    upper: number
}

const isJavaInteger = (value: number): boolean =>
    Number.isInteger(value) && value >= -2147483648 && value <= 2147483647

/** Parse exactly the rate syntax accepted by the template JSON schema. */
export const parseNaturalChangeRate = (value: unknown): NaturalChangeRateRange | null => {
    if (typeof value !== 'string'
        || !/^(-?\d+|\[\s*-?\d+\s*,\s*-?\d+\s*\])$/.test(value)) {
        return null
    }
    const parts = value.replace(/[\[\]]/g, '').split(',').map(part => Number(part.trim()))
    const lower = parts.length === 1 ? Math.min(0, parts[0]) : parts[0]
    const upper = parts.length === 1 ? Math.max(0, parts[0]) : parts[1]
    return isJavaInteger(lower) && isJavaInteger(upper) && lower <= upper
        ? { lower, upper }
        : null
}

export const canonicalNaturalChangeRate = (value: unknown): string => {
    if (value === undefined || value === null) return '0..0'
    const parsed = parseNaturalChangeRate(value)
    return parsed ? `${parsed.lower}..${parsed.upper}` : String(value)
}

const MAX_NATURAL_CHANGE_RATE_SPAN = REQUEST_LIMITS.naturalChangeRateSpan

/**
 * Exactly the per-step changes the declared interval admits, matching the generated model.
 *
 * The interval is the meaning, so nothing is added to it. An interval that excludes `0` says the
 * value *always* changes; one that includes `0` says it *may* hold. The user chooses between those
 * meanings by writing the interval they mean, and this panel must show the same set both engines
 * explore — otherwise the explanation and the verdict describe different models.
 */
export const naturalChangeDeltas = (value: unknown): number[] | null => {
    const parsed = parseNaturalChangeRate(value)
    if (!parsed) return null
    const deltas: number[] = []
    for (let delta = parsed.lower; delta <= parsed.upper; delta += 1) deltas.push(delta)
    return deltas
}

export const naturalChangeCandidateValues = (value: unknown): string => {
    const deltas = naturalChangeDeltas(value)
    if (!deltas) return String(value ?? '')
    return deltas.map(delta => (delta > 0 ? `+${delta}` : String(delta))).join(', ')
}

const validateNumericRateContract = (
    declaration: Record<string, any>,
    kind: 'InternalVariable' | 'EnvironmentDomain',
    name: string,
    numeric: boolean,
    sharedEnvironment: boolean
): ManifestValidationResult | null => {
    const hasRate = Object.prototype.hasOwnProperty.call(declaration, 'NaturalChangeRate')
    if (!numeric && hasRate) {
        return invalidManifest(
            'naturalChangeRateNumericOnly',
            `${kind} "${name}" declares NaturalChangeRate, but only numeric ranges support it`,
            { kind, name }
        )
    }
    if (numeric && sharedEnvironment && !hasRate) {
        return invalidManifest(
            'sharedNumericNaturalChangeRateRequired',
            `Shared numeric ${kind} "${name}" must explicitly define NaturalChangeRate`,
            { name }
        )
    }
    if (numeric && hasRate) {
        const parsed = parseNaturalChangeRate(declaration.NaturalChangeRate)
        if (!parsed) {
            return invalidManifest(
                'naturalChangeRateInvalid',
                `${kind} "${name}" has invalid NaturalChangeRate "${String(declaration.NaturalChangeRate)}"`,
                { kind, name, rate: String(declaration.NaturalChangeRate) }
            )
        }
        // Every value in the interval is modeled as reachable in one step, so the span is a
        // state-space cost the backend bounds. Reject it here too, or authoring would accept a
        // manifest that generation refuses.
        if (parsed.upper - parsed.lower > MAX_NATURAL_CHANGE_RATE_SPAN) {
            return invalidManifest(
                'naturalChangeRateSpanTooWide',
                `${kind} "${name}" declares NaturalChangeRate "${String(declaration.NaturalChangeRate)}", whose span exceeds the modelable maximum of ${MAX_NATURAL_CHANGE_RATE_SPAN}`,
                { kind, name, rate: String(declaration.NaturalChangeRate), max: MAX_NATURAL_CHANGE_RATE_SPAN }
            )
        }
    }
    return null
}

export const validateManifest = (obj: any): ManifestValidationResult => {
    if (!obj || typeof obj !== 'object') {
        return invalidManifest('invalidObject', 'Invalid JSON object')
    }

    if (!obj.Name) return invalidManifest('missingName', 'Missing field "Name"')

    for (const field of ['Modes', 'InternalVariables', 'ImpactedVariables', 'WorkingStates', 'Transitions', 'APIs', 'Contents']) {
        if (obj[field] !== undefined && !Array.isArray(obj[field])) {
            return invalidManifest('fieldMustBeArray', `"${field}" must be an array`, { field })
        }
    }

    const hasModes = Array.isArray(obj.Modes) && obj.Modes.length > 0
    const hasInitState = typeof obj.InitState === 'string' && obj.InitState.trim() !== ''
    const hasWorkingStates = Array.isArray(obj.WorkingStates) && obj.WorkingStates.length > 0
    const validTrust = (value: unknown) => TRUST_VALUE_SET.has(String(value || '').trim().toLowerCase())
    const validPrivacy = (value: unknown) => PRIVACY_VALUE_SET.has(String(value || '').trim().toLowerCase())

    if (hasModes || hasInitState || hasWorkingStates) {
        if (!hasModes) return invalidManifest(
            'stateMachineFieldRequired',
            'Mode-based templates must contain non-empty "Modes"',
            { field: 'Modes' }
        )
        if (!hasInitState) return invalidManifest(
            'stateMachineFieldRequired',
            'Mode-based templates must contain "InitState"',
            { field: 'InitState' }
        )
        if (!hasWorkingStates) return invalidManifest(
            'stateMachineFieldRequired',
            'Mode-based templates must contain non-empty "WorkingStates"',
            { field: 'WorkingStates' }
        )
    }

    if (hasInitState && hasWorkingStates) {
        const initialState = obj.InitState.trim()
        const stateNames = obj.WorkingStates.map((s: any) => String(s?.Name || '').trim())
        if (!stateNames.includes(initialState)) {
            return invalidManifest(
                'initStateUndefined',
                `InitState "${obj.InitState}" is not defined in WorkingStates`,
                { state: obj.InitState }
            )
        }
    }

    if (hasModes && hasWorkingStates) {
        const normalizeStateComponent = (value: unknown) =>
            String(value || '').trim().replace(/ /g, '').toLowerCase()
        const fullStates = new Map<string, string>()
        const components = new Map<string, { fullState: string; trust: string; privacy: string }>()
        for (const state of obj.WorkingStates) {
            const rawState = String(state?.Name || '').trim()
            if (Object.prototype.hasOwnProperty.call(state || {}, 'Invariant')) {
                return invalidManifest(
                    'workingStateInvariantUnsupported',
                    `WorkingState "${rawState}" uses unsupported Invariant; define behavior with structured Dynamics, Transitions, rules, or specifications`,
                    { state: rawState }
                )
            }
            if (!validTrust(state?.Trust) || !validPrivacy(state?.Privacy)) {
                return invalidManifest(
                    'workingStateSecurityLabelsRequired',
                    `WorkingState "${rawState}" must define Trust as trusted/untrusted and Privacy as public/private`,
                    { state: rawState }
                )
            }
            const segments = rawState.split(';')
            if (segments.length !== obj.Modes.length) {
                return invalidManifest(
                    'workingStateArityMismatch',
                    `WorkingState "${rawState}" must contain one semicolon-separated value for each mode`,
                    { state: rawState }
                )
            }
            const normalizedSegments = segments.map(normalizeStateComponent)
            const missingModeIndex = normalizedSegments.findIndex(segment => !segment || segment === '_')
            if (missingModeIndex >= 0) {
                return invalidManifest(
                    'workingStateModeValueRequired',
                    `WorkingState "${rawState}" must define a concrete value for mode "${obj.Modes[missingModeIndex]}"`,
                    { state: rawState, mode: obj.Modes[missingModeIndex] }
                )
            }
            const fullStateKey = normalizedSegments.join(';')
            const previousFullState = fullStates.get(fullStateKey)
            if (previousFullState) {
                return invalidManifest(
                    'workingStateDuplicate',
                    `WorkingStates "${previousFullState}" and "${rawState}" are duplicates after model normalization`,
                    { previousState: previousFullState, state: rawState }
                )
            }
            fullStates.set(fullStateKey, rawState)

            const trust = String(state.Trust).trim().toLowerCase()
            const privacy = String(state.Privacy).trim().toLowerCase()
            for (let index = 0; index < obj.Modes.length; index += 1) {
                const componentKey = `${normalizeStateComponent(obj.Modes[index])}\u0000${normalizedSegments[index]}`
                const previous = components.get(componentKey)
                if (previous && (previous.trust !== trust || previous.privacy !== privacy)) {
                    return invalidManifest(
                        'workingStateLabelConflict',
                        `WorkingStates "${previous.fullState}" and "${rawState}" assign conflicting Trust/Privacy labels to ${obj.Modes[index]}="${segments[index].trim()}"`,
                        {
                            previousState: previous.fullState,
                            state: rawState,
                            mode: obj.Modes[index],
                            value: segments[index].trim()
                        }
                    )
                }
                components.set(componentKey, { fullState: rawState, trust, privacy })
            }
        }
    }

    const normalizedName = (value: unknown) => String(value || '').trim().toLowerCase()
    const internalNames = new Map<string, any>()
    for (const variable of obj.InternalVariables || []) {
        const name = normalizedName(variable?.Name)
        if (!name) {
            return invalidManifest(
                'internalVariableNameRequired',
                'Every InternalVariables item must contain Name'
            )
        }
        if (internalNames.has(name)) {
            return invalidManifest(
                'internalVariableDuplicate',
                `Duplicate InternalVariable "${variable.Name}"`,
                { name: variable.Name }
            )
        }
        if (!validTrust(variable.Trust) || !validPrivacy(variable.Privacy)) {
            return invalidManifest(
                'internalVariableSecurityLabelsRequired',
                `InternalVariable "${variable.Name}" must define Trust as trusted/untrusted and Privacy as public/private`,
                { name: variable.Name }
            )
        }
        if (typeof variable.FalsifiableWhenCompromised !== 'boolean') {
            return invalidManifest(
                'internalVariableFalsifiableRequired',
                `InternalVariable "${variable.Name}" must define FalsifiableWhenCompromised`,
                { name: variable.Name }
            )
        }
        if (typeof variable.IsInside !== 'boolean') {
            return invalidManifest(
                'internalVariableScopeRequired',
                `InternalVariable "${variable.Name}" must explicitly define IsInside as true (device-local) or false (shared environment)`,
                { name: variable.Name }
            )
        }
        const hasValues = Array.isArray(variable.Values) && variable.Values.length > 0
        const hasLowerField = Object.prototype.hasOwnProperty.call(variable, 'LowerBound')
        const hasUpperField = Object.prototype.hasOwnProperty.call(variable, 'UpperBound')
        if ((hasLowerField && !isJavaInteger(variable.LowerBound))
            || (hasUpperField && !isJavaInteger(variable.UpperBound))) {
            return invalidManifest(
                'numericBoundsInvalid',
                `InternalVariable "${variable.Name}" bounds must be 32-bit integers`,
                { kind: 'InternalVariable', name: variable.Name }
            )
        }
        const hasLower = isJavaInteger(variable.LowerBound)
        const hasUpper = isJavaInteger(variable.UpperBound)
        if (hasValues === (hasLower && hasUpper) || hasLower !== hasUpper) {
            return invalidManifest(
                'internalVariableDomainRequired',
                `InternalVariable "${variable.Name}" must explicitly define Values or LowerBound+UpperBound`,
                { name: variable.Name }
            )
        }
        if (hasLower && hasUpper && variable.LowerBound > variable.UpperBound) {
            return invalidManifest(
                'numericBoundsOrderInvalid',
                `InternalVariable "${variable.Name}" has LowerBound greater than UpperBound`,
                { kind: 'InternalVariable', name: variable.Name }
            )
        }
        const rateIssue = validateNumericRateContract(
            variable,
            'InternalVariable',
            variable.Name,
            hasLower && hasUpper,
            variable.IsInside === false
        )
        if (rateIssue) return rateIssue
        internalNames.set(name, variable)
    }

    const domainNames = new Map<string, any>()
    const impactedNames = new Set<string>()
    for (const rawName of obj.ImpactedVariables || []) {
        const name = normalizedName(rawName)
        if (!name) {
            return invalidManifest(
                'impactedVariableNameRequired',
                'ImpactedVariables cannot contain an empty name'
            )
        }
        if (impactedNames.has(name)) {
            return invalidManifest(
                'impactedVariableDuplicate',
                `Duplicate ImpactedVariable "${rawName}"`,
                { name: rawName }
            )
        }
        impactedNames.add(name)
        const variable = internalNames.get(name)
        if (variable?.IsInside === true) {
            return invalidManifest(
                'impactedVariableConflictsWithLocal',
                `ImpactedVariable "${rawName}" conflicts with a device-local InternalVariable`,
                { name: rawName }
            )
        }
        if (!variable && !domainNames.has(name)) {
            return invalidManifest(
                'impactedVariableDomainRequired',
                `ImpactedVariable "${rawName}" needs a domain in this manifest`,
                { name: rawName }
            )
        }
    }
    for (const [name, domain] of domainNames) {
        if (!impactedNames.has(name)) {
            return invalidManifest(
                'environmentDomainNotImpacted',
                `EnvironmentDomain "${domain.Name}" is not listed in ImpactedVariables`,
                { name: domain.Name }
            )
        }
    }

    for (const content of obj.Contents || []) {
        const name = String(content?.Name || '').trim()
        if (!name) {
            return invalidManifest('contentNameRequired', 'Every Contents item must contain Name')
        }
        if (!validPrivacy(content?.Privacy)) {
            return invalidManifest(
                'contentPrivacyRequired',
                `Content "${name}" must define Privacy as public/private`,
                { name }
            )
        }
    }

    for (const transition of obj.Transitions || []) {
        const name = String(transition?.Name || '').trim() || '<unnamed>'
        if (Object.prototype.hasOwnProperty.call(transition || {}, 'Signal')) {
            return invalidManifest(
                'transitionSignalUnsupported',
                `Transition "${name}" uses unsupported Signal; event pulses are available only on state-changing APIs with Signal=true`,
                { name }
            )
        }
    }

    for (const api of obj.APIs || []) {
        const name = String(api?.Name || '').trim() || '<unnamed>'
        if (typeof api?.StartState !== 'string') {
            return invalidManifest(
                'apiStartStateRequired',
                `API "${name}" must explicitly define StartState (use an empty string for any state)`,
                { name }
            )
        }
        if (typeof api?.Signal !== 'boolean') {
            return invalidManifest(
                'apiSignalRequired',
                `API "${name}" must explicitly define boolean Signal (true = observable automation trigger; false = command only)`,
                { name }
            )
        }
        if (api?.AcceptsContent !== undefined && typeof api.AcceptsContent !== 'boolean') {
            return invalidManifest(
                'apiAcceptsContentBoolean',
                `API "${name}" AcceptsContent must be boolean when provided`,
                { name }
            )
        }
    }

    return { valid: true }
}
