import type {
  DeviceRecommendation,
  RuleRecommendation,
  SpecificationRecommendation
} from '@/types/recommendation'
import type { RuleForm, RuleSourceItemType } from '@/types/rule'
import type { SpecCondition } from '@/types/spec'
import { normalizeModelRelation } from '@/utils/modelRequest'
import { PRIVACY_VALUE_SET, TRUST_VALUE_SET } from '@/utils/deviceRuntime'

const RULE_TARGET_TYPES = new Set<RuleSourceItemType>(['api', 'variable', 'mode', 'state'])
const VALUE_RULE_TARGET_TYPES = new Set<RuleSourceItemType>(['variable', 'mode', 'state'])
const SPEC_TARGET_TYPES = new Set(['api', 'variable', 'mode', 'state', 'trust', 'privacy'])
const RELATIONS = new Set(['=', '!=', '>', '>=', '<', '<=', 'in', 'not in'])
const ENUM_RELATIONS = new Set(['=', '!=', 'in', 'not in'])

export class RecommendationCandidateError extends Error {
  constructor(readonly field: string) {
    super(`Invalid recommendation field: ${field}`)
    this.name = 'RecommendationCandidateError'
  }
}

const record = (value: unknown, field: string): Record<string, any> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new RecommendationCandidateError(field)
  }
  return value as Record<string, any>
}

const text = (value: unknown, field: string): string => {
  if (typeof value !== 'string' || !value.trim()) {
    throw new RecommendationCandidateError(field)
  }
  return value.trim()
}

const optionalText = (value: unknown, field: string): string | undefined => {
  if (value === null || value === undefined) return undefined
  return text(value, field)
}

const relation = (value: unknown, field: string): string => {
  const normalized = normalizeModelRelation(text(value, field))
  if (!normalized || !RELATIONS.has(normalized)) {
    throw new RecommendationCandidateError(field)
  }
  return normalized
}

export const materializeRuleRecommendation = (
  recommendation: RuleRecommendation,
  temporaryId: string
): RuleForm => {
  const candidate = record(recommendation, 'rule')
  if (!Array.isArray(candidate.conditions) || candidate.conditions.length === 0) {
    throw new RecommendationCandidateError('conditions')
  }

  const sources = candidate.conditions.map((raw: unknown, index: number) => {
    const condition = record(raw, `conditions[${index + 1}]`)
    const targetType = text(condition.targetType, `conditions[${index + 1}].targetType`).toLowerCase()
    if (!RULE_TARGET_TYPES.has(targetType as RuleSourceItemType)) {
      throw new RecommendationCandidateError(`conditions[${index + 1}].targetType`)
    }
    const itemType = targetType as RuleSourceItemType
    const source = {
      fromId: text(condition.deviceId, `conditions[${index + 1}].deviceId`),
      fromApi: text(condition.attribute, `conditions[${index + 1}].attribute`),
      itemType
    } as RuleForm['sources'][number]

    if (VALUE_RULE_TARGET_TYPES.has(itemType)) {
      source.relation = relation(condition.relation, `conditions[${index + 1}].relation`)
      source.value = text(condition.value, `conditions[${index + 1}].value`)
      if ((itemType === 'state' || itemType === 'mode') && !ENUM_RELATIONS.has(source.relation)) {
        throw new RecommendationCandidateError(`conditions[${index + 1}].relation`)
      }
    } else if ((condition.relation !== null && condition.relation !== undefined)
        || (condition.value !== null && condition.value !== undefined)) {
      throw new RecommendationCandidateError(`conditions[${index + 1}].relation/value`)
    }
    return source
  })

  const command = record(candidate.command, 'command')
  const contentDevice = optionalText(command.contentDevice, 'command.contentDevice')
  const content = optionalText(command.content, 'command.content')
  if (Boolean(contentDevice) !== Boolean(content)) {
    throw new RecommendationCandidateError('command.contentDevice/content')
  }

  return {
    id: temporaryId,
    name: text(candidate.name, 'name'),
    sources,
    toId: text(command.deviceId, 'command.deviceId'),
    toApi: text(command.action, 'command.action'),
    ...(contentDevice && content ? { contentDevice, content } : {})
  }
}

export const validateRuleRecommendationCandidate = (value: unknown, index: number): void => {
  materializeRuleRecommendation(value as RuleRecommendation, `recommendation-${index + 1}`)
}

export const materializeSpecificationRecommendationConditions = (
  conditions: unknown,
  side: 'a' | 'if' | 'then',
  createId: (index: number) => string
): SpecCondition[] => {
  if (!Array.isArray(conditions)) {
    throw new RecommendationCandidateError(`${side}Conditions`)
  }
  return conditions.map((raw, index) => {
    const prefix = `${side}Conditions[${index + 1}]`
    const condition = record(raw, prefix)
    const targetType = text(condition.targetType, `${prefix}.targetType`).toLowerCase()
    if (!SPEC_TARGET_TYPES.has(targetType)) {
      throw new RecommendationCandidateError(`${prefix}.targetType`)
    }
    const normalizedRelation = relation(condition.relation, `${prefix}.relation`)
    const propertyScope = optionalText(condition.propertyScope, `${prefix}.propertyScope`)?.toLowerCase()
    if ((targetType === 'trust' || targetType === 'privacy')
        && propertyScope !== 'state' && propertyScope !== 'variable') {
      throw new RecommendationCandidateError(`${prefix}.propertyScope`)
    }
    if (targetType !== 'trust' && targetType !== 'privacy' && propertyScope !== undefined) {
      throw new RecommendationCandidateError(`${prefix}.propertyScope`)
    }

    // A recommended variable condition must state which value it means. Defaulting it here would
    // put words in the model's mouth about a distinction that only matters under compromise, so an
    // incomplete candidate is rejected as malformed instead.
    const variableSource = optionalText(condition.variableSource, `${prefix}.variableSource`)?.toLowerCase()
    if (targetType === 'variable') {
      if (variableSource !== 'environment' && variableSource !== 'reported') {
        throw new RecommendationCandidateError(`${prefix}.variableSource`)
      }
    } else if (variableSource !== undefined) {
      throw new RecommendationCandidateError(`${prefix}.variableSource`)
    }

    const deviceId = text(condition.deviceId, `${prefix}.deviceId`)
    const deviceLabel = optionalText(condition.deviceLabel, `${prefix}.deviceLabel`) || deviceId
    return {
      id: createId(index),
      side,
      deviceId,
      deviceLabel,
      targetType: targetType as SpecCondition['targetType'],
      key: text(condition.key, `${prefix}.key`),
      ...((targetType === 'trust' || targetType === 'privacy')
        ? { propertyScope: propertyScope as 'state' | 'variable' }
        : {}),
      ...(targetType === 'variable'
        ? { variableSource: variableSource as SpecCondition['variableSource'] }
        : {}),
      relation: normalizedRelation,
      value: text(condition.value, `${prefix}.value`)
    }
  })
}

export const validateSpecificationRecommendationCandidate = (value: unknown, index: number): void => {
  const prefix = `recommendations[${index + 1}]`
  const candidate = record(value, prefix) as unknown as SpecificationRecommendation
  if (text(candidate.name, `${prefix}.name`).length > 100) throw new RecommendationCandidateError(`${prefix}.name`)
  text(candidate.rationale, `${prefix}.rationale`)
  if (typeof candidate.templateId !== 'string' || !/^[1-7]$/.test(candidate.templateId)) {
    throw new RecommendationCandidateError(`${prefix}.templateId`)
  }
  materializeSpecificationRecommendationConditions(
    candidate.aConditions, 'a', conditionIndex => `${prefix}-a-${conditionIndex}`)
  materializeSpecificationRecommendationConditions(
    candidate.ifConditions, 'if', conditionIndex => `${prefix}-if-${conditionIndex}`)
  materializeSpecificationRecommendationConditions(
    candidate.thenConditions, 'then', conditionIndex => `${prefix}-then-${conditionIndex}`)
}

export const validateDeviceRecommendationCandidate = (value: unknown, index: number): void => {
  const prefix = `recommendations[${index + 1}]`
  const candidate = record(value, prefix) as unknown as DeviceRecommendation
  text(candidate.templateName, `${prefix}.templateName`)
  text(candidate.suggestedLabel, `${prefix}.suggestedLabel`)

  for (const field of ['intendedUse', 'suggestedPlacement', 'description', 'reason', 'initialState'] as const) {
    if (Object.prototype.hasOwnProperty.call(candidate, field)) {
      text(candidate[field], `${prefix}.${field}`)
    }
  }
  if (Object.prototype.hasOwnProperty.call(candidate, 'currentStateTrust')
      && (typeof candidate.currentStateTrust !== 'string'
        || !TRUST_VALUE_SET.has(candidate.currentStateTrust))) {
    throw new RecommendationCandidateError(`${prefix}.currentStateTrust`)
  }
  if (Object.prototype.hasOwnProperty.call(candidate, 'currentStatePrivacy')
      && (typeof candidate.currentStatePrivacy !== 'string'
        || !PRIVACY_VALUE_SET.has(candidate.currentStatePrivacy))) {
    throw new RecommendationCandidateError(`${prefix}.currentStatePrivacy`)
  }

  if (Object.prototype.hasOwnProperty.call(candidate, 'initialVariables')) {
    if (!Array.isArray(candidate.initialVariables)) {
      throw new RecommendationCandidateError(`${prefix}.initialVariables`)
    }
    const names = new Set<string>()
    candidate.initialVariables.forEach((raw, variableIndex) => {
      const itemPrefix = `${prefix}.initialVariables[${variableIndex + 1}]`
      const variable = record(raw, itemPrefix)
      const name = text(variable.name, `${itemPrefix}.name`)
      text(variable.value, `${itemPrefix}.value`)
      if (names.has(name)) throw new RecommendationCandidateError(`${itemPrefix}.name`)
      names.add(name)
      if (Object.prototype.hasOwnProperty.call(variable, 'trust')
          && (typeof variable.trust !== 'string' || !TRUST_VALUE_SET.has(variable.trust))) {
        throw new RecommendationCandidateError(`${itemPrefix}.trust`)
      }
    })
  }

  if (Object.prototype.hasOwnProperty.call(candidate, 'initialPrivacies')) {
    if (!Array.isArray(candidate.initialPrivacies)) {
      throw new RecommendationCandidateError(`${prefix}.initialPrivacies`)
    }
    const names = new Set<string>()
    candidate.initialPrivacies.forEach((raw, privacyIndex) => {
      const itemPrefix = `${prefix}.initialPrivacies[${privacyIndex + 1}]`
      const privacy = record(raw, itemPrefix)
      const name = text(privacy.name, `${itemPrefix}.name`)
      if (names.has(name)) throw new RecommendationCandidateError(`${itemPrefix}.name`)
      names.add(name)
      if (typeof privacy.privacy !== 'string' || !PRIVACY_VALUE_SET.has(privacy.privacy)) {
        throw new RecommendationCandidateError(`${itemPrefix}.privacy`)
      }
    })
  }
}
