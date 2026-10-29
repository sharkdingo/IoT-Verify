import type { DeviceNode } from '@/types/node'
import type { RuleDto, RuleForm } from '@/types/rule'
import type { SpecCondition, Specification } from '@/types/spec'
import { specTemplateDetails } from '@/assets/config/specTemplates'
import { normalizeModelRelation } from './modelRequest'
import { splitSpecConditionValues } from './specConditionDomain'

export interface ModelPresentationContext {
  t: (key: string, values?: Record<string, string | number>) => string
  resolveDevice: (id: string) => Pick<DeviceNode, 'label'> | undefined
  // Callers own bundled/custom and live/frozen token provenance.
  formatToken: (deviceId: string, value: unknown) => string
  modeCount?: (deviceId: string) => number
}

export const formatRelationGlyph = (relation?: string): string => {
  const canonical = normalizeModelRelation(relation) ?? relation ?? ''
  return ({ '!=': '≠', '>=': '≥', '<=': '≤', in: '∈', 'not in': '∉' } as Record<string, string>)[canonical]
    || canonical
}

export const presentRule = (rule: RuleForm | RuleDto, context: ModelPresentationContext) => {
  const { t, resolveDevice, formatToken } = context
  const sources = 'sources' in rule ? rule.sources : rule.conditions.map(condition => ({
    fromId: condition.deviceName,
    fromApi: condition.targetType === 'state' ? 'state' : condition.attribute,
    itemType: condition.targetType,
    relation: condition.relation,
    value: condition.value
  }))
  const command = 'sources' in rule
    ? { deviceName: rule.toId, action: rule.toApi, contentDevice: rule.contentDevice, content: rule.content }
    : rule.command
  const conditions = sources.map(source => {
    const device = resolveDevice(source.fromId)?.label || source.fromId || t('app.unknown')
    const property = formatToken(source.fromId, source.fromApi)
    if (source.itemType === 'api') return `${device} ${t('app.triggers')} ${property}`
    const comparison = source.relation && source.value != null && String(source.value).trim() !== ''
      ? ` ${formatRelationGlyph(source.relation)} ${formatToken(source.fromId, source.value)}`
      : ''
    return `${device} ${property}${comparison}`
  })
  const target = resolveDevice(command.deviceName)?.label || command.deviceName || t('app.unknown')
  const action = formatToken(command.deviceName, command.action) || t('app.notAvailableShort')
  const contentSource = command.contentDevice && command.content
    ? `${resolveDevice(command.contentDevice)?.label || command.contentDevice} ${formatToken(command.contentDevice, command.content)}`
    : ''
  const description = t('app.ifThenDescription', {
    source: conditions.join(` ${t('app.and')} `), target, action
  })
  return { conditions, target, action, contentSource, description }
}

export const specConditionPropertyLabel = (condition: Pick<SpecCondition,
  'deviceId' | 'targetType' | 'key' | 'propertyScope'>, context: ModelPresentationContext): string => {
  const { t, formatToken } = context
  if (condition.targetType === 'state') return t('app.state')
  if ((condition.targetType === 'trust' || condition.targetType === 'privacy') && condition.propertyScope === 'state') {
    return context.modeCount?.(condition.deviceId) === 1
      ? t('app.currentStateProperty')
      : t('app.currentModeStateProperty', { mode: formatToken(condition.deviceId, condition.key) })
  }
  return condition.key ? formatToken(condition.deviceId, condition.key) : t('app.value')
}

export const specConditionValueLabel = (condition: Pick<SpecCondition,
  'deviceId' | 'targetType' | 'value' | 'relation'>, context: ModelPresentationContext): string => {
  const formatValue = (value: unknown) => {
    const text = String(value ?? '').trim()
    if (condition.targetType === 'api' && /^(TRUE|FALSE)$/i.test(text)) {
      return context.t(text.toUpperCase() === 'TRUE' ? 'app.apiEventOccurred' : 'app.apiEventNotOccurred')
    }
    // Security labels belong to the condition type, including on custom templates.
    if ((condition.targetType === 'privacy' && (text === 'private' || text === 'public'))
      || (condition.targetType === 'trust' && (text === 'trusted' || text === 'untrusted'))) {
      return context.t(`app.${text}`)
    }
    return context.formatToken(condition.deviceId, value)
  }
  const relation = normalizeModelRelation(condition.relation)
  return relation === 'in' || relation === 'not in'
    ? splitSpecConditionValues(condition.value, condition.targetType).map(formatValue).filter(Boolean).join(', ')
    : formatValue(condition.value)
}

export const describeSpecification = (spec: Pick<Specification,
  'templateId' | 'aConditions' | 'ifConditions' | 'thenConditions'>, context: ModelPresentationContext): string => {
  const { t, resolveDevice } = context
  const template = specTemplateDetails.find(candidate => candidate.id === spec.templateId)
  if (!template) return t('app.specPreviewConfigureConditions')

  const describeCondition = (condition: SpecCondition): string => {
    const device = resolveDevice(condition.deviceId)?.label || condition.deviceLabel || condition.deviceId || t('app.device')
    const key = specConditionPropertyLabel(condition, context)
    const subjectKey = condition.targetType === 'variable'
      ? condition.variableSource === 'environment' ? 'VariableEnvironment'
        : condition.variableSource === 'reported' ? 'VariableReported' : 'VariableSourceUnresolved'
      : ({ mode: 'Mode', state: 'State', api: 'Api', trust: 'Trust', privacy: 'Privacy' })[condition.targetType]
    const subject = t(`app.specPreview${subjectKey}Subject`, { device, key })
    const relation = normalizeModelRelation(condition.relation) || '='
    const value = specConditionValueLabel(condition, context)
    return `${subject} ${formatRelationGlyph(relation)} “${value}”`
  }
  const describeGroup = (conditions: SpecCondition[] = []) => conditions.map(describeCondition).join(` ${t('app.specPreviewAnd')} `)
  const conditions = describeGroup(spec.aConditions)
  const ifConditions = describeGroup(spec.ifConditions)
  const thenConditions = describeGroup(spec.thenConditions)
  const templateKey = template.type[0].toUpperCase() + template.type.slice(1)
  if (template.requiredSides.includes('a')) {
    return conditions ? t(`app.specPreview${templateKey}`, { conditions }) : t('app.specPreviewConfigureACondition')
  }
  if (!ifConditions) return t('app.specPreviewConfigureIfThen')
  return t(`app.specPreview${templateKey}${thenConditions ? '' : 'Partial'}`, { ifConditions, thenConditions })
}
