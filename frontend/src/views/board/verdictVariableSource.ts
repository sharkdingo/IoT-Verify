import type { SpecCondition, Specification } from '@/types/spec'

/**
 * Which reading(s) a verdict answered about, as i18n keys.
 *
 * A verification result row is titled by its specification *template* ("Never", "Always"), so two
 * specifications asking different questions about the same key — the case the `variableSource`
 * distinction exists for — render as identical rows that can carry opposite verdicts. The only other
 * difference on screen is one token inside a monospace formula, which is not an explanation.
 *
 * Returns keys rather than text so the caller owns translation, and returns them in first-seen order,
 * de-duplicated. A specification that mixes both readings yields both: that is precisely when the
 * distinction matters most, so collapsing it would hide the point. A condition that never chose maps
 * to the unresolved key rather than being dropped, because a verdict about an unanswered question is
 * something the user needs to see.
 */
export const verdictVariableSourceKeys = (spec: Pick<Specification,
    'aConditions' | 'ifConditions' | 'thenConditions'> | undefined | null): string[] => {
    if (!spec) return []
    const conditions: (SpecCondition | undefined | null)[] = [
        ...(spec.aConditions || []),
        ...(spec.ifConditions || []),
        ...(spec.thenConditions || [])
    ]
    const keys: string[] = []
    for (const condition of conditions) {
        if (!condition || condition.targetType !== 'variable') continue
        const key = condition.variableSource === 'environment'
            ? 'app.specVariableSourceEnvironmentShort'
            : condition.variableSource === 'reported'
                ? 'app.specVariableSourceReportedShort'
                : 'app.specVariableSourceUnresolvedShort'
        if (!keys.includes(key)) keys.push(key)
    }
    return keys
}
