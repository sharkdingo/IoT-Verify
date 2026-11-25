import type { DeviceManifest, InternalVariable } from '@/types/device'
import type { SpecCondition, SpecPropertyScope } from '@/types/spec'
import { PRIVACY_OPTIONS, TRUST_OPTIONS, templateVariableHasEnumValues, validateNumericConditionValue } from './deviceRuntime'
import { normalizeModelRelation } from './modelRequest'
import { getModeStateValues, getStateConditionOptions, splitConditionValueList } from './stateConditionDomain'

type Condition = Partial<SpecCondition>
type Manifest = DeviceManifest | null | undefined
type Translate = (key: string, params?: Record<string, unknown>) => string

export interface SpecConditionProperty {
  key: string
  propertyScope?: SpecPropertyScope
}

const ENUM_RELATIONS = ['=', '!=', 'in', 'not in']

export const getSpecConditionVariable = (condition: Condition, manifest: Manifest): InternalVariable | undefined =>
  manifest?.InternalVariables?.find(variable => variable.Name === condition.key)

/** Canonical property references; labels and select-value encoding belong to the view. */
export const getSpecConditionProperties = (
  targetType: string | undefined, manifest: Manifest, templateId: string
): SpecConditionProperty[] => {
  if (!manifest) return []
  switch (targetType) {
    case 'variable':
      // Affect-only shared declarations have labels but no observable value-condition source.
      return (manifest.InternalVariables || [])
        .filter(variable => variable.IsInside === true || variable.Reads !== false)
        .map(variable => ({ key: variable.Name }))
    case 'state':
      return (manifest.WorkingStates || []).map(state => ({ key: state.Name }))
    case 'mode':
      return (manifest.Modes || []).map(key => ({ key }))
    case 'api':
      return (manifest.APIs || []).filter(api => {
        const signal = api.Signal === true || ('signal' in api && api.signal === true)
        const endState = api.EndState ?? ('endState' in api ? api.endState : undefined) ?? ''
        return signal && (templateId !== '7' || Boolean(String(endState).trim()))
      }).map(api => ({ key: api.Name }))
    case 'trust':
    case 'privacy': {
      const properties: SpecConditionProperty[] = [
        ...(manifest.Modes || []).map(key => ({ key, propertyScope: 'state' as const })),
        ...(manifest.InternalVariables || []).map(variable => ({ key: variable.Name, propertyScope: 'variable' as const }))
      ]
      return Array.from(new Map(properties.map(property => [JSON.stringify(property), property])).values())
    }
    default:
      return []
  }
}

export const isSpecConditionRelationSupported = (
  condition: Condition, manifest: Manifest, templateId: string, relation: string
): boolean => {
  if (templateId === '7' && ['state', 'mode', 'api'].includes(condition.targetType || '')) return relation === '='
  const variable = getSpecConditionVariable(condition, manifest)
  const isEnum = ['state', 'mode', 'api', 'trust', 'privacy'].includes(condition.targetType || '')
    || (condition.targetType === 'variable' && variable !== undefined && templateVariableHasEnumValues(variable))
  return !isEnum || ENUM_RELATIONS.includes(relation)
}

export const getSpecConditionValues = (
  condition: Condition, manifest: Manifest, templateId: string, original?: Condition
): string[] => {
  switch (condition.targetType) {
    case 'api': return templateId === '7' ? ['TRUE'] : ['TRUE', 'FALSE']
    case 'state': {
      const retained = original?.targetType === 'state' && original.deviceId === condition.deviceId
        ? conditionValues(original, manifest) : []
      return getStateConditionOptions(manifest, [...retained, ...conditionValues(condition, manifest)])
    }
    case 'mode': return getModeStateValues(manifest, condition.key || '')
    case 'variable': return getSpecConditionVariable(condition, manifest)?.Values?.map(String) || []
    case 'trust': return [...TRUST_OPTIONS]
    case 'privacy': return [...PRIVACY_OPTIONS]
    default: return []
  }
}

const conditionValues = (condition: Condition, manifest: Manifest): string[] => {
  const relation = normalizeModelRelation(condition.relation)
  const value = String(condition.value ?? '').trim()
  return relation === 'in' || relation === 'not in'
    ? splitConditionValueList(value, condition.targetType, manifest?.Modes?.length) : [value]
}

/** Explicit inputs keep condition and whole-spec validation independent of editor state. */
export const validateSpecConditionDomain = (
  condition: Condition, manifest: Manifest, templateId: string, t: Translate
): string | null => {
  const relation = normalizeModelRelation(condition.relation) || ''
  const value = String(condition.value ?? '').trim()
  const values = conditionValues(condition, manifest)
  if (condition.targetType === 'api') {
    if (!ENUM_RELATIONS.includes(relation) || values.length === 0
      || values.some(item => !['TRUE', 'FALSE'].includes(item.toUpperCase()))) {
      return t('app.specApiBooleanConditionInvalid')
    }
    if (templateId === '7' && (relation !== '=' || value.toUpperCase() !== 'TRUE')) {
      return t('app.specSafetyApiConditionRequired')
    }
  }
  if (!isSpecConditionRelationSupported(condition, manifest, templateId, relation)) {
    return t('app.specConditionRelationUnavailable')
  }
  if (condition.targetType === 'variable'
    && condition.variableSource !== 'environment' && condition.variableSource !== 'reported') {
    return t('app.specVariableSourceRequired')
  }
  // Until a template is available, the server remains the authoritative domain validator.
  if (!manifest) return null
  if (condition.targetType !== 'state'
    && !getSpecConditionProperties(condition.targetType, manifest, templateId).some(property =>
      property.key === condition.key
      && (!property.propertyScope || property.propertyScope === condition.propertyScope))) {
    return t('app.specConditionPropertyUnavailable')
  }
  const variable = condition.targetType === 'variable' ? getSpecConditionVariable(condition, manifest) : undefined
  if (variable?.IsInside === true && condition.variableSource === 'environment') {
    return t('app.specVariableSourceDeviceLocalHelp')
  }
  if (condition.targetType === 'variable' && !variable?.Values?.length) {
    return variable
      ? validateNumericConditionValue(variable, value, relation, t) || null
      : t('app.specConditionPropertyUnavailable')
  }
  const allowedValues = getSpecConditionValues(condition, manifest, templateId)
  if (values.length === 0 || values.some(item => !allowedValues.includes(
    condition.targetType === 'api' ? item.toUpperCase() : item))) {
    return t('app.specConditionValueUnavailable')
  }
  return null
}
