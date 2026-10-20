import type { DeviceNode } from '../types/node'
import type {
    SpecCondition,
    Specification
} from '../types/spec'
import { specTemplateDetails } from '../assets/config/specTemplates'
import { normalizeModelRelation } from './modelRequest'

interface SpecFormulaContext {
    nodes?: DeviceNode[]
}

/* =========================================
 * 条件创建 & 模式判断
 * =======================================*/

const previewQuote = (value: unknown): string =>
    JSON.stringify(String(value ?? '?'))

const previewDevice = (condition: SpecCondition, context?: SpecFormulaContext): string => {
    const currentLabel = context?.nodes?.find(node => node.id === condition.deviceId)?.label
    const label = String(currentLabel || condition.deviceLabel || '').trim()
    return previewQuote(label || 'Unknown device')
}

/**
 * Which value a `variable` condition names is read from the condition, never inferred from the
 * manifest. Inferring it from `IsInside` is the defect this field exists to fix: every condition on
 * a shared variable rendered (and compiled) as the pool value, so the device the author picked
 * vanished from the formula and a falsified reading could not be expressed at all.
 *
 * An undecided condition renders as `<unresolved>` rather than silently picking a side; the run is
 * blocked separately, so the preview must not look like a valid formula.
 */
const previewVariableTarget = (condition: SpecCondition, context?: SpecFormulaContext): string => {
    const key = String(condition.key || '').trim()
    if (condition.variableSource === 'environment') return `Environment.${previewQuote(key)}`
    // `<device>.<key>`, matching what the generator emits and what the backend's own formula preview
    // shows. An earlier draft wrote `<device>.reported.<key>`, which named no identifier in the model and
    // put internal vocabulary in front of the user; the reading is conveyed by the badge and the
    // plain-language sentence beside this formula, not by inventing a segment inside it.
    if (condition.variableSource === 'reported') {
        return `${previewDevice(condition, context)}.${previewQuote(key)}`
    }
    return `<unresolved>.${previewQuote(key)}`
}

// A trust/privacy condition with variable scope carries no `variableSource`: the generator always
// reads the device's own label mirror for it, so there is no second question to ask.
const previewPropertyVariableTarget = (condition: SpecCondition, context?: SpecFormulaContext): string =>
    `${previewDevice(condition, context)}.${previewQuote(String(condition.key || '').trim())}`

const previewConditionTarget = (condition: SpecCondition, context?: SpecFormulaContext): string => {
    const device = previewDevice(condition, context)
    const key = previewQuote(String(condition.key || '').trim())
    switch (condition.targetType) {
        case 'state':
            return `${device}.state`
        case 'variable':
            return previewVariableTarget(condition, context)
        case 'api':
            return `actionEvent(${device}, ${key})`
        case 'trust': {
            const source = condition.propertyScope === 'state'
                ? `${device}.current ${key} state`
                : previewPropertyVariableTarget(condition, context)
            return `controlSource(${source})`
        }
        case 'privacy': {
            const source = condition.propertyScope === 'state'
                ? `${device}.current ${key} state`
                : previewPropertyVariableTarget(condition, context)
            return `sensitivity(${source})`
        }
        default:
            return `${device}.${key}`
    }
}

const previewScalar = (value: unknown): string => {
    const text = String(value ?? '').trim()
    if (/^-?\d+(?:\.\d+)?$/.test(text)) return text
    // Split from the label literals below: NuSMV booleans are uppercase and the backend's own preview
    // renders them that way, so folding all six to lowercase made the same condition display as `true`
    // here and `TRUE` there — two spellings of one formula, from the two halves of one feature.
    if (/^(?:true|false)$/i.test(text)) return text.toUpperCase()
    if (/^(?:trusted|untrusted|public|private)$/i.test(text)) return text.toLowerCase()
    return previewQuote(text)
}

const previewConditionValue = (condition: SpecCondition, relation: string): string => {
    if (relation !== 'in' && relation !== 'not in') return previewScalar(condition.value)
    const delimiter = condition.targetType === 'state' ? /[,|]/ : /[,;|]/
    const values = String(condition.value || '')
        .split(delimiter)
        .map(value => value.trim())
        .filter(Boolean)
        .map(previewScalar)
    return `{${values.join(', ')}}`
}

const conditionToFormulaTerm = (condition: SpecCondition, context?: SpecFormulaContext): string => {
    if (!condition?.deviceId || !condition.key) return ''
    const target = previewConditionTarget(condition, context)
    if (condition.targetType === 'api') return target
    const relation = normalizeModelRelation(condition.relation) || '='
    return `${target} ${relation} ${previewConditionValue(condition, relation)}`
}

const conditionGroupToFormula = (conditions: SpecCondition[] = [], context?: SpecFormulaContext): string => {
    const terms = conditions.map(condition => conditionToFormulaTerm(condition, context)).filter(Boolean)
    return terms.length > 0 ? terms.join(' AND ') : 'TRUE'
}

/**
 * The subject of template 7's untrusted-label disjunct, matching what the generator resolves per target
 * type. A label is always emitted *per device* — there is no pool-level `trust_a_<key>` — so no arm may
 * render `Environment.`. (Emitted per device, not scoped per device: for a shared value the pool is the
 * only writer, so every declaring device carries the same label. See
 * `docs/architecture/shared-value-semantics.md` §2.):
 *   - `variable` -> `<device>."<key>"`, the device's own value label (`trust_<key>`). Reusing the VALUE
 *     target rendered an `environment` condition as `controlSource(Environment."<key>")`, naming a label
 *     the model never declares.
 *   - `mode` -> the mode's currently active state, since the generator emits `trust_<mode>_<value>`, a
 *     state-property label rather than a value label.
 *   - everything else keeps its own target, which already names the device.
 */
const untrustedLabelSource = (condition: SpecCondition, context?: SpecFormulaContext): string => {
    if (condition.targetType === 'variable') return previewPropertyVariableTarget(condition, context)
    const device = previewDevice(condition, context)
    const key = previewQuote(String(condition.key || '').trim())
    if (condition.targetType === 'mode') return `${device}.current ${key} state`
    // The generator resolves an API's untrusted source through the end state the action leads to, not the
    // event itself.
    if (condition.targetType === 'api') return `${device}.state after ${key}`
    // Admission refuses trust/privacy as template-7 A conditions — the label is what the template derives.
    // These used to fall through to a target that already returns `controlSource(...)`, so the caller wrapped
    // it twice. Plain device target keeps the preview readable if one ever leaks past admission.
    if (condition.targetType === 'trust' || condition.targetType === 'privacy') return `${device}.${key}`
    return previewConditionTarget(condition, context)
}

const conditionGroupToSafetyBody = (conditions: SpecCondition[] = [], context?: SpecFormulaContext): string => {
    const conditionTerms = conditions.map(condition => conditionToFormulaTerm(condition, context)).filter(Boolean)
    const untrustedSources = conditions.map(condition => {
        const source = untrustedLabelSource(condition, context)
        return source ? `controlSource(${source}) = untrusted` : ''
    }).filter(Boolean)
    if (conditionTerms.length === 0) return 'TRUE'
    const sourceTerm = untrustedSources.length > 1
        ? `(${untrustedSources.join(' OR ')})`
        : untrustedSources[0] || 'untrustedSource(unknown)'
    return `${conditionTerms.join(' AND ')} AND ${sourceTerm}`
}

export const buildSpecFormula = (spec: Pick<Specification,
    'templateId' | 'templateLabel' | 'aConditions' | 'ifConditions' | 'thenConditions'>,
    context?: SpecFormulaContext): string => {
    const template = specTemplateDetails.find(t => t.id === spec.templateId)
    if (!template) return spec.templateLabel || 'Unknown specification'

    const aPart = conditionGroupToFormula(spec.aConditions || [], context)
    const ifPart = conditionGroupToFormula(spec.ifConditions || [], context)
    const thenPart = conditionGroupToFormula(spec.thenConditions || [], context)

    switch (template.type) {
        case 'always':
            return `CTL AG(${aPart})`
        case 'eventually':
            return `CTL AF(${aPart})`
        case 'never':
            return `CTL AG NOT (${aPart})`
        case 'immediate':
            return `CTL AG((${ifPart}) -> AX(${thenPart}))`
        case 'response':
            return `CTL AG((${ifPart}) -> AF(${thenPart}))`
        case 'persistence':
            return `LTL G((${ifPart}) -> F G(${thenPart}))`
        case 'safety':
            return `CTL AG NOT (${conditionGroupToSafetyBody(spec.aConditions || [], context)})`
        default:
            return template.formulaPreview
    }
}

/**
 * Which temporal logic a specification is checked in, or `null` when the template is unknown.
 *
 * Template 6 (`persistence`, `G(IF -> F G(THEN))`) is the only LTL one; the other six are CTL. That
 * matches `ModelTraceToolPresenter.formulaKind`, which falls back to `"6" -> LTL` for the same reason,
 * and it is derived from the same `type` switch as `buildSpecFormula` above rather than from the string
 * it produces — reading the formula back is what made two callers disagree.
 *
 * `ControlCenter` parsed the emitted formula for a `CTLSPEC`/`LTLSPEC` prefix, which `buildSpecFormula`
 * does not emit (it writes `CTL AG(...)` / `LTL G(...)`, and NuSMV's keyword form appears only in a
 * trace's `checkedExpression`). So the chip beside the spec builder's formula preview matched neither
 * branch and read "Model" for every template, contradicting the formula printed next to it — in the one
 * place this distinction is being explained to the user. Callers that also have a raw NuSMV expression
 * should prefer parsing it and use this as the fallback.
 */
export const specFormulaKindFromTemplate = (templateId?: string | null): 'CTL' | 'LTL' | null => {
    const template = specTemplateDetails.find(t => t.id === templateId)
    if (!template) return null
    return template.type === 'persistence' ? 'LTL' : 'CTL'
}

/** Keep the language badge separate from the expression without changing saved formula previews. */
export const buildSpecFormulaPreview = (spec: Parameters<typeof buildSpecFormula>[0], context?: SpecFormulaContext) => {
    const kind = specFormulaKindFromTemplate(spec.templateId)
    const formula = buildSpecFormula(spec, context)
    return { kind, expression: kind && formula.startsWith(`${kind} `) ? formula.slice(kind.length + 1) : formula }
}

const normalizeSpecificationSetValue = (
    value: unknown,
    relation: string,
    targetType: string
): string => {
    const normalized = String(value ?? '').trim()
    if (targetType === 'api') return normalized.toUpperCase()
    if (relation !== 'in' && relation !== 'not in') return normalized
    const delimiter = targetType === 'state' ? /[,|]/ : /[,;|]/
    return normalized.split(delimiter).map(part => part.trim()).filter(Boolean).sort().join(',')
}

const specificationConditionKeys = (conditions: SpecCondition[] = []): string[] =>
    conditions.map(condition => {
        const targetType = String(condition.targetType || '').trim().toLowerCase()
        const relation = normalizeModelRelation(condition.relation) || String(condition.relation || '').trim()
        return JSON.stringify({
            deviceId: String(condition.deviceId || '').trim(),
            targetType,
            propertyScope: String(condition.propertyScope || '').trim().toLowerCase(),
            // Part of the identity: the same key with the other source is a different question, so
            // two such specifications must not compare as the same one.
            variableSource: String(condition.variableSource || '').trim().toLowerCase(),
            key: String(condition.key || '').trim(),
            relation,
            value: normalizeSpecificationSetValue(condition.value, relation, targetType)
        })
    }).sort()

export const buildSpecificationSemanticKey = (specification: Pick<
    Specification,
    'templateId' | 'aConditions' | 'ifConditions' | 'thenConditions'
>): string => JSON.stringify({
    templateId: String(specification.templateId ?? '').trim(),
    aConditions: specificationConditionKeys(specification.aConditions),
    ifConditions: specificationConditionKeys(specification.ifConditions),
    thenConditions: specificationConditionKeys(specification.thenConditions)
})

export const isSameSpecification = (a: Specification, b: Specification): boolean =>
    buildSpecificationSemanticKey(a) === buildSpecificationSemanticKey(b)

/**
 * A stored `variable` condition that never recorded which value it means. The backend rejects it,
 * and no client-side default is honest, so it stays unresolved: displays mark it and the run is
 * blocked until the author decides.
 */
export const isSpecConditionVariableSourceUnresolved = (condition: SpecCondition): boolean =>
    condition?.targetType === 'variable'
    && condition.variableSource !== 'environment'
    && condition.variableSource !== 'reported'

export const specificationsWithUnresolvedVariableSource = (
    specifications: Specification[]
): Specification[] =>
    (specifications || []).filter(specification =>
        [specification.aConditions, specification.ifConditions, specification.thenConditions]
            .some(conditions => (conditions || []).some(isSpecConditionVariableSourceUnresolved)))

export const isSpecRelatedToNode = (spec: Specification, nodeId: string) => {
    // 检查规约选择的设备
    if (spec.devices && spec.devices.some(d => d.deviceId === nodeId)) return true
    
    // 检查条件中是否包含该设备
    const check = (list: SpecCondition[]) => list && list.length > 0 && list.some(c => c.deviceId === nodeId)
    return check(spec.aConditions) || check(spec.ifConditions) || check(spec.thenConditions)
}

/**
 * Collapse a specification's conditions into its distinct device references.
 *
 * A specification stores one entry per referenced device, accumulating the `api` keys that its
 * conditions select. `lookupNodes` supplies labels for readability only; the `deviceId` is the
 * authoritative identity, so an unknown node degrades to showing the id rather than dropping
 * the reference.
 */
export function buildSpecDeviceRefsFromConditions(
    conditions: SpecCondition[],
    lookupNodes: DeviceNode[]
) {
    const byDevice = new Map<string, { deviceId: string; deviceLabel: string; selectedApis: string[] }>()
    conditions.forEach(condition => {
        if (!condition.deviceId) return
        const existing = byDevice.get(condition.deviceId)
        if (existing) {
            if (condition.targetType === 'api' && condition.key && !existing.selectedApis.includes(condition.key)) {
                existing.selectedApis.push(condition.key)
            }
            return
        }
        const node = lookupNodes.find(candidate => candidate.id === condition.deviceId)
        byDevice.set(condition.deviceId, {
            deviceId: condition.deviceId,
            deviceLabel: condition.deviceLabel || node?.label || condition.deviceId,
            selectedApis: condition.targetType === 'api' && condition.key ? [condition.key] : []
        })
    })
    return Array.from(byDevice.values())
}
