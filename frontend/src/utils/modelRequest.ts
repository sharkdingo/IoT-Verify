import type { Specification } from '@/types/spec'
import type { VerificationRequest } from '@/types/verify'
import type { SimulationRequest } from '@/types/simulation'
import type { AttackScenario } from '@/types/attackScenario'

interface ModelRunParameters {
  attackScenario: AttackScenario
  enablePrivacy: boolean
}

export const specificationsRequirePrivacy = (specifications: Specification[]): boolean =>
  specifications.some(specification =>
    [specification.aConditions, specification.ifConditions, specification.thenConditions]
      .some(conditions => (conditions || []).some(condition =>
        String(condition?.targetType || '').trim().toLowerCase() === 'privacy')))

const NUSMV_RESERVED_WORDS = new Set([
  'module', 'var', 'ivar', 'frozenvar', 'define', 'constants', 'assign',
  'init', 'trans', 'invar', 'invars', 'spec', 'ctlspec', 'ltlspec',
  'fairness', 'compassion', 'justice', 'isa', 'fun', 'pred',
  'mirror', 'invarspec', 'compute',
  'case', 'esac', 'next', 'self',
  'true', 'false', 'boolean', 'integer', 'word', 'signed', 'unsigned',
  'process', 'array', 'of', 'mod', 'union', 'in', 'xor', 'xnor',
  'abs', 'max', 'min', 'count', 'toint', 'typeof',
  'swconst', 'wordconst', 'uwconst', 'resize', 'extend',
  'signed_word', 'unsigned_word',
  'ex', 'ax', 'ef', 'af', 'eg', 'ag',
  'e', 'f', 'o', 'g', 'h', 'x', 'y', 'z', 'w', 'a', 'u', 's', 'v', 't',
  'bu', 'ebf', 'abf', 'ebg', 'abg'
])

export const normalizeNuSmvDeviceName = (name: string): string => {
  if (!name) return name
  let normalized = name.replace(/[^a-zA-Z0-9_]/g, '_').toLowerCase()
  if (!normalized || /^_+$/.test(normalized)) normalized = 'device_0'
  if (/^\d/.test(normalized)) normalized = `_${normalized}`
  return NUSMV_RESERVED_WORDS.has(normalized) ? `_${normalized}` : normalized
}

export const normalizeModelRelation = (relation?: string | null): string | undefined => {
  if (relation === null || relation === undefined) return undefined
  const trimmed = String(relation).trim()
  if (!trimmed) return undefined

  switch (trimmed.toUpperCase()) {
    case 'EQ':
    case '==':
      return '='
    case 'NEQ':
    case '!=':
      return '!='
    case 'GT':
      return '>'
    case 'GTE':
      return '>='
    case 'LT':
      return '<'
    case 'LTE':
      return '<='
    case 'IN':
      return 'in'
    case 'NOT_IN':
    case 'NOT IN':
      return 'not in'
    default:
      return trimmed
  }
}

const buildAttackScenario = (attackScenario: AttackScenario): AttackScenario => {
  if (attackScenario.mode === 'NONE') {
    return { mode: 'NONE', budget: 0, points: [] }
  }
  if (attackScenario.mode === 'ANY_UP_TO_BUDGET') {
    return {
      mode: 'ANY_UP_TO_BUDGET',
      budget: attackScenario.budget,
      points: []
    }
  }
  return {
    mode: 'EXACT_POINTS',
    points: (attackScenario.points || []).map(point => point.kind === 'DEVICE'
      ? { kind: 'DEVICE' as const, deviceId: normalizeNuSmvDeviceName(String(point.deviceId || '')) }
      : { kind: 'AUTOMATION_LINK' as const, ruleId: point.ruleId })
  }
}

/**
 * Run parameters only. The server reads the scene from the caller's persisted board, so sending
 * devices/rules/specs/environment would be rejected by its strict request parser — and the whole
 * point is that a run describes the saved board rather than whatever this tab happens to hold.
 */
export const buildVerificationRequestPayload = (
  params: ModelRunParameters
): VerificationRequest => ({
  attackScenario: buildAttackScenario(params.attackScenario),
  enablePrivacy: params.enablePrivacy
})

export const buildSimulationRequestPayload = (
  params: ModelRunParameters & { steps: number }
): SimulationRequest => ({
  steps: params.steps,
  attackScenario: buildAttackScenario(params.attackScenario),
  enablePrivacy: params.enablePrivacy
})
