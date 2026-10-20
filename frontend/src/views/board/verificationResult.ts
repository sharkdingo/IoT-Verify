import type { SpecResult, VerificationOutcome, VerificationResult } from '@/types/verify'
import { describeSpecification, type ModelPresentationContext } from '@/utils/modelPresentation'
import { formatModelTokenBySource } from '@/utils/modelTokenDisplay'
import { verdictVariableSourceKeys } from './verdictVariableSource'

export const presentSpecificationResult = (result: SpecResult, t: ModelPresentationContext['t']) => {
  const snapshot = result.specificationSnapshot
  const prefix = `${result.formulaKind} `
  return {
    description: snapshot ? describeSpecification(snapshot, {
      t,
      resolveDevice: id => snapshot.devices[id],
      formatToken: (id, value) => formatModelTokenBySource(snapshot.devices[id]?.modelTokenSource ?? 'UNKNOWN', value, t),
      modeCount: id => snapshot.devices[id]?.modeCount ?? 0
    }) : null,
    formula: result.formulaPreview.startsWith(prefix) ? result.formulaPreview.slice(prefix.length) : result.formulaPreview,
    variableSourceLabels: verdictVariableSourceKeys(snapshot).map(key => ({
      key, label: t(key), unresolved: key === 'app.specVariableSourceUnresolvedShort'
    }))
  }
}

export const getVerificationOutcome = (
  result: Pick<VerificationResult, 'outcome'> | null | undefined
): VerificationOutcome => result?.outcome ?? 'INCONCLUSIVE'
