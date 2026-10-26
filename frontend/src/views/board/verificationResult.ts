import type { SpecResult, Trace, VerificationOutcome, VerificationResult } from '@/types/verify'
import { describeSpecification, type ModelPresentationContext } from '@/utils/modelPresentation'
import { formatModelTokenBySource } from '@/utils/modelTokenDisplay'
import { verdictVariableSourceKeys } from './verdictVariableSource'

const hasResponseTriggerWarning = (result: SpecResult) =>
  result.antecedentReachability === 'UNREACHABLE' || result.antecedentReachability === 'INCONCLUSIVE'

export const countResponseTriggerWarnings = (results: readonly SpecResult[]) =>
  results.filter(hasResponseTriggerWarning).length

export const groupVerificationEvidence = <
  R extends Pick<SpecResult, 'specId' | 'outcome' | 'antecedentReachability'>,
  T extends Pick<Trace, 'violatedSpecId'>
>(results: readonly R[], traces: readonly T[]) => {
  type Group = { result: R | null; traces: Array<{ trace: T; index: number }> }
  const groups: Group[] = results.map(result => ({ result, traces: [] }))
  const bySpecId = new Map(groups.map(group => [group.result!.specId, group]))
  traces.forEach((trace, index) => {
    const group = bySpecId.get(trace.violatedSpecId)
    // A historical run can retain a trace without its per-spec result. Never hide that evidence.
    if (group) group.traces.push({ trace, index })
    else groups.push({ result: null, traces: [{ trace, index }] })
  })
  const priority = ({ result, traces }: Group) => !result || result.outcome === 'VIOLATED' || traces.length ? 0
    : result.outcome === 'INCONCLUSIVE' || result.antecedentReachability === 'UNREACHABLE'
      || result.antecedentReachability === 'INCONCLUSIVE' ? 1 : 2
  return groups.sort((a, b) => priority(a) - priority(b))
}

export const presentSpecificationResult = (result: SpecResult, t: ModelPresentationContext['t']) => {
  const snapshot = result.specificationSnapshot
  const prefix = `${result.formulaKind} `
  const reachability = result.antecedentReachability
  return {
    triggerWarning: hasResponseTriggerWarning(result),
    triggerDetail: reachability === 'UNREACHABLE' ? t('household.triggerUnreachableDetail')
      : reachability === 'INCONCLUSIVE' ? t('household.triggerUnknownDetail')
      : reachability === 'REACHABLE' ? t('household.triggerReachable') : null,
    description: describeSpecification(snapshot, {
      t,
      resolveDevice: id => snapshot.devices[id],
      formatToken: (id, value) => formatModelTokenBySource(snapshot.devices[id]?.modelTokenSource ?? 'UNKNOWN', value, t),
      modeCount: id => snapshot.devices[id]?.modeCount ?? 0
    }),
    formula: result.formulaPreview.startsWith(prefix) ? result.formulaPreview.slice(prefix.length) : result.formulaPreview,
    variableSourceLabels: verdictVariableSourceKeys(snapshot).map(key => ({
      key, label: t(key), unresolved: key === 'app.specVariableSourceUnresolvedShort'
    }))
  }
}

export const getVerificationOutcome = (
  result: Pick<VerificationResult, 'outcome'> | null | undefined
): VerificationOutcome => result?.outcome ?? 'INCONCLUSIVE'
