import { REQUEST_LIMITS } from '@/constants/requestLimits'
import { normalizeTemplateLookupName } from '@/utils/templateUsage'
import { buildSpecDeviceRefsFromConditions, buildSpecFormula } from '@/utils/spec'
import { normalizeModelRelation, normalizeNuSmvDeviceName } from '@/utils/modelRequest'
import { MANIFEST_VALIDATION_MESSAGE_KEYS, validateManifest } from '@/utils/device'
import { canonicalizeTemplateManifest } from '@/utils/templateManifestCanonicalization'
import { NODE_HEIGHT_RANGE, NODE_POSITION_ABS_MAX, NODE_WIDTH_RANGE } from '@/utils/canvas/nodeLayout'
import { defaultSpecTemplates, specTemplateDetails } from '@/assets/config/specTemplates'
import type { DeviceNode } from '@/types/node'
import type { DeviceTemplate } from '@/types/device'
import type { RuleForm, RuleSourceItemType } from '@/types/rule'
import type { Specification, SpecCondition } from '@/types/spec'
import type { ModelEnvironmentVariable } from '@/types/model'
import type {
  PortableSceneCondition,
  PortableSceneDevice,
  PortableSceneEnvironmentVariable,
  PortableSceneFile,
  PortableSceneRule,
  PortableSceneSpecification,
  PortableSceneTemplate
} from '@/types/scene'

/** Identifies an exported board scene file and the shape its readers must expect. */
export const SCENE_FILE_SCHEMA = 'iot-verify.board-scene'
// 6: specifications require their own names; template labels cannot replace user intent.
export const SCENE_FILE_VERSION = 6

/** The board-side scene model: domain objects, before serialization to a portable file. */
export type BoardSceneModel = {
  schema: typeof SCENE_FILE_SCHEMA
  version: typeof SCENE_FILE_VERSION
  templates: DeviceTemplate[]
  devices: DeviceNode[]
  environmentVariables: ModelEnvironmentVariable[]
  rules: RuleForm[]
  specs: Specification[]
}

/** A scene device after normalization: security labels are optional until the board applies them. */
export type NormalizedSceneDevice = Omit<DeviceNode, 'currentStateTrust' | 'currentStatePrivacy'> & {
  currentStateTrust?: 'trusted' | 'untrusted'
  currentStatePrivacy?: 'public' | 'private'
}

/**
 * Portable-scene normalization, validation and canonicalization.
 *
 * An imported scene is untrusted input. Every normalizer rejects unknown fields, internal
 * fields, out-of-range numbers and duplicate identities instead of coercing them, so a
 * malformed file fails at the boundary rather than producing a half-valid board.
 *
 * Rejections are raised as `Error` with an already-translated message. The translator is
 * injected once via {@link createSceneCodec} rather than imported, keeping this module free of
 * Vue/i18n runtime state (the convention `utils/deviceRuntime.ts` follows with its `t` param).
 */
export type Translate = (key: string, params?: Record<string, unknown>) => string

const deepClone = <T,>(value: T): T => JSON.parse(JSON.stringify(value))

export const createSceneCodec = (t: Translate) => {
  const assertSceneCollectionLimit = (value: unknown[], field: string, maximum: number) => {
    if (value.length <= maximum) return
    throw new Error(t('app.sceneImportCollectionTooLarge', { field, limit: maximum }))
  }

  const normalizeSceneString = (value: unknown, field = 'value') => {
    if (value === undefined || value === null) return ''
    if (typeof value !== 'string') {
      throw new Error(t('app.sceneImportStringRequired', { field }))
    }
    return value.trim()
  }

  const rejectSceneInternalField = (row: unknown, field: string) => {
    if (row && typeof row === 'object' && Object.prototype.hasOwnProperty.call(row, field)) {
      throw new Error(t('app.sceneImportInternalField', { field }))
    }
  }

  const assertSceneAllowedFields = (row: unknown, allowedFields: readonly string[], path: string) => {
    if (!row || typeof row !== 'object' || Array.isArray(row)) return
    const allowed = new Set(allowedFields)
    const unknownField = Object.keys(row).find(field => !allowed.has(field))
    if (unknownField) {
      throw new Error(t('app.sceneImportUnknownField', {
        field: path ? `${path}.${unknownField}` : unknownField
      }))
    }
  }

  const formatIntegerRangeError = (field: string, min: number, max: number) =>
    t('app.integerBetween', { field, min, max })

  const requireIntegerInRange = (value: unknown, field: string, min: number, max: number): number => {
    if (typeof value !== 'number' || !Number.isInteger(value)) {
      throw new Error(formatIntegerRangeError(field, min, max))
    }
    if (value < min || value > max) {
      throw new Error(formatIntegerRangeError(field, min, max))
    }
    return value
  }

  const normalizeSceneNumber = (
    value: unknown,
    fallback: number,
    field: string,
    min = Number.NEGATIVE_INFINITY,
    max = Number.POSITIVE_INFINITY
  ) => {
    if (value === undefined || value === null) return fallback
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      throw new Error(t('app.sceneImportNumberRequired', { field }))
    }
    if (value < min || value > max) {
      throw new Error(t('app.numberBetween', { field, min, max }))
    }
    return value
  }

  const normalizeSpecificationName = (value: unknown, field: string): string => {
    const name = normalizeSceneString(value, field)
    if (!name) throw new Error(t('app.sceneImportMissingField', { field }))
    if (name.length > 100) throw new Error(t('app.specificationNameInvalid'))
    return name
  }

  const normalizeSceneTrust = (value: unknown, field: string): 'trusted' | 'untrusted' | null => {
    const normalized = normalizeSceneString(value, field)
    if (!normalized) return null
    if (normalized === 'trusted' || normalized === 'untrusted') return normalized
    throw new Error(t('app.sceneImportInvalidEnum', { field, value: normalized }))
  }

  const normalizeScenePrivacy = (value: unknown, field: string): 'public' | 'private' | null => {
    const normalized = normalizeSceneString(value, field)
    if (!normalized) return null
    if (normalized === 'public' || normalized === 'private') return normalized
    throw new Error(t('app.sceneImportInvalidEnum', { field, value: normalized }))
  }

  const normalizeSceneVariables = (value: unknown, field: string) => {
    if (value === undefined || value === null) return undefined
    if (!Array.isArray(value)) throw new Error(t('app.sceneImportArrayRequired', { field }))
    assertSceneCollectionLimit(value, field, REQUEST_LIMITS.deviceVariables)
    const seenNames = new Set<string>()
    const variables = value
      .map((item, index) => {
        const row = item as any
        if (!row || typeof row !== 'object' || Array.isArray(row)) {
          throw new Error(t('app.sceneImportObjectRequired', { field: `${field}[${index}]` }))
        }
        assertSceneAllowedFields(row, ['name', 'value', 'trust'], `${field}[${index}]`)
        const name = normalizeSceneString(row?.name, `${field}[${index}].name`)
        if (!name) throw new Error(t('app.sceneImportMissingField', { field: `${field}[${index}].name` }))
        if (seenNames.has(name)) {
          throw new Error(t('app.sceneImportDuplicateRuntimeEntry', { field, name }))
        }
        seenNames.add(name)
        if (!Object.prototype.hasOwnProperty.call(row, 'value')) {
          throw new Error(t('app.sceneImportMissingField', { field: `${field}[${index}].value` }))
        }
        const normalizedValue = normalizeSceneString(row?.value, `${field}[${index}].value`)
        if (!normalizedValue) {
          throw new Error(t('app.sceneImportMissingField', { field: `${field}[${index}].value` }))
        }
        return {
          name,
          value: normalizedValue,
          ...(row?.trust !== undefined && row?.trust !== null
            ? { trust: normalizeSceneTrust(row.trust, `${field}[${index}].trust`) || undefined }
            : {})
        }
      })
    return variables.length > 0 ? variables : undefined
  }

  const normalizeScenePrivacies = (value: unknown, field: string) => {
    if (value === undefined || value === null) return undefined
    if (!Array.isArray(value)) throw new Error(t('app.sceneImportArrayRequired', { field }))
    assertSceneCollectionLimit(value, field, REQUEST_LIMITS.devicePrivacies)
    const seenNames = new Set<string>()
    const privacies = value
      .map((item, index) => {
        const row = item as any
        if (!row || typeof row !== 'object' || Array.isArray(row)) {
          throw new Error(t('app.sceneImportObjectRequired', { field: `${field}[${index}]` }))
        }
        assertSceneAllowedFields(row, ['name', 'privacy'], `${field}[${index}]`)
        const name = normalizeSceneString(row?.name, `${field}[${index}].name`)
        if (!name) throw new Error(t('app.sceneImportMissingField', { field: `${field}[${index}].name` }))
        if (seenNames.has(name)) {
          throw new Error(t('app.sceneImportDuplicateRuntimeEntry', { field, name }))
        }
        seenNames.add(name)
        const privacy = normalizeScenePrivacy(row?.privacy, `${field}[${index}].privacy`)
        if (!privacy) throw new Error(t('app.sceneImportMissingField', { field: `${field}[${index}].privacy` }))
        return { name, privacy }
      })
    return privacies.length > 0 ? privacies : undefined
  }

  const normalizeSceneDevice = (value: unknown, index: number, templates: DeviceTemplate[]): NormalizedSceneDevice => {
    const row = value as any
    if (!row || typeof row !== 'object' || Array.isArray(row)) {
      throw new Error(t('app.sceneImportObjectRequired', { field: `devices[${index}]` }))
    }
    assertSceneAllowedFields(row, [
      'id', 'templateName', 'label', 'position', 'state', 'width', 'height',
      'currentStateTrust', 'currentStatePrivacy', 'variables', 'privacies'
    ], `devices[${index}]`)
    const id = normalizeSceneString(row.id, `devices[${index}].id`)
    if (typeof row.id === 'string' && (row.id !== id || /^\p{White_Space}|\p{White_Space}$/u.test(row.id))) {
      throw new Error(t('app.sceneImportIdentifierWhitespace', { field: `devices[${index}].id` }))
    }
    const templateName = normalizeSceneString(row.templateName, `devices[${index}].templateName`)
    const label = normalizeSceneString(row.label, `devices[${index}].label`)
    if (!id) throw new Error(t('app.sceneImportMissingField', { field: `devices[${index}].id` }))
    if (!templateName) throw new Error(t('app.sceneImportMissingField', { field: `devices[${index}].templateName` }))
    if (!label) throw new Error(t('app.sceneImportMissingField', { field: `devices[${index}].label` }))
    if (!row.position || typeof row.position !== 'object' || Array.isArray(row.position)) {
      throw new Error(t('app.sceneImportObjectRequired', { field: `devices[${index}].position` }))
    }
    assertSceneAllowedFields(row.position, ['x', 'y'], `devices[${index}].position`)
    if (row.position.x === undefined || row.position.x === null) {
      throw new Error(t('app.sceneImportMissingField', { field: `devices[${index}].position.x` }))
    }
    if (row.position.y === undefined || row.position.y === null) {
      throw new Error(t('app.sceneImportMissingField', { field: `devices[${index}].position.y` }))
    }
    const state = normalizeSceneString(row.state, `devices[${index}].state`)
    const variables = normalizeSceneVariables(row.variables, `devices[${index}].variables`)
    const privacies = normalizeScenePrivacies(row.privacies, `devices[${index}].privacies`)

    return {
      id,
      templateName: sceneTemplateForDevice({ templateName }, templates)?.name.trim() ?? templateName,
      label,
      position: {
        x: normalizeSceneNumber(
          row.position?.x,
          0,
          `devices[${index}].position.x`,
          -NODE_POSITION_ABS_MAX,
          NODE_POSITION_ABS_MAX
        ),
        y: normalizeSceneNumber(
          row.position?.y,
          0,
          `devices[${index}].position.y`,
          -NODE_POSITION_ABS_MAX,
          NODE_POSITION_ABS_MAX
        )
      },
      state: state || 'Working',
      width: requireIntegerInRange(
        row.width,
        `devices[${index}].width`,
        NODE_WIDTH_RANGE.min,
        NODE_WIDTH_RANGE.max
      ),
      height: requireIntegerInRange(
        row.height,
        `devices[${index}].height`,
        NODE_HEIGHT_RANGE.min,
        NODE_HEIGHT_RANGE.max
      ),
      ...(row.currentStateTrust !== undefined && row.currentStateTrust !== null
        ? { currentStateTrust: normalizeSceneTrust(row.currentStateTrust, `devices[${index}].currentStateTrust`) || undefined }
        : {}),
      ...(row.currentStatePrivacy !== undefined && row.currentStatePrivacy !== null
        ? { currentStatePrivacy: normalizeScenePrivacy(row.currentStatePrivacy, `devices[${index}].currentStatePrivacy`) || undefined }
        : {}),
      ...(variables ? { variables } : {}),
      ...(privacies ? { privacies } : {})
    }
  }

  const sceneTemplateForDevice = (
    device: Pick<DeviceNode, 'templateName'>,
    templates: DeviceTemplate[]
  ): DeviceTemplate | undefined => templates.find(template =>
    [template.name, template.manifest?.Name]
      .map(normalizeTemplateLookupName)
      .includes(normalizeTemplateLookupName(device.templateName)))

  const sceneTemplateHasStateMachine = (template?: DeviceTemplate): boolean =>
    Boolean(template?.manifest?.Modes?.length && template.manifest?.WorkingStates?.length)

  const assertSceneDeviceRuntimeShape = (
    rawDevices: unknown[],
    devices: DeviceNode[],
    templates: DeviceTemplate[]
  ) => {
    devices.forEach((device, index) => {
      const template = sceneTemplateForDevice(device, templates)
      if (!template) return
      const raw = rawDevices[index] as any
      const rawState = normalizeSceneString(raw?.state, `devices[${index}].state`)
      const hasStateMachine = sceneTemplateHasStateMachine(template)
      if (hasStateMachine && !rawState) {
        throw new Error(t('app.sceneImportStateRequiredForStatefulDevice', { index: index + 1, label: device.label }))
      }
      if (!hasStateMachine && rawState) {
        throw new Error(t('app.sceneImportStateForbiddenForStatelessDevice', { index: index + 1, label: device.label }))
      }
      if (!hasStateMachine && (raw?.currentStateTrust !== undefined || raw?.currentStatePrivacy !== undefined)) {
        throw new Error(t('app.sceneImportStateLabelsForbiddenForStatelessDevice', { index: index + 1, label: device.label }))
      }
    })
  }

  const normalizeSceneEnvironmentVariables = (value: unknown): ModelEnvironmentVariable[] => {
    if (value === undefined || value === null) return []
    if (!Array.isArray(value)) throw new Error(t('app.sceneImportArrayRequired', { field: 'environmentVariables' }))
    assertSceneCollectionLimit(value, 'environmentVariables', REQUEST_LIMITS.environmentVariables)
    const seenNames = new Set<string>()
    return value.map((item, index) => {
      const row = item as any
      if (!row || typeof row !== 'object' || Array.isArray(row)) {
        throw new Error(t('app.sceneImportObjectRequired', { field: `environmentVariables[${index}]` }))
      }
      assertSceneAllowedFields(row, ['name', 'value', 'trust', 'privacy'], `environmentVariables[${index}]`)
      const name = normalizeSceneString(row.name, `environmentVariables[${index}].name`)
      if (!name) throw new Error(t('app.sceneImportMissingField', { field: `environmentVariables[${index}].name` }))
      if (!Object.prototype.hasOwnProperty.call(row, 'value')) {
        throw new Error(t('app.sceneImportEnvironmentValueRequired', { name }))
      }
      if (seenNames.has(name)) {
        throw new Error(t('app.sceneImportDuplicateEnvironmentVariable', { name }))
      }
      seenNames.add(name)
      const trust = normalizeSceneTrust(row.trust, `environmentVariables[${index}].trust`)
      const privacy = normalizeScenePrivacy(row.privacy, `environmentVariables[${index}].privacy`)
      const normalizedValue = normalizeSceneString(row.value, `environmentVariables[${index}].value`)
      if (!normalizedValue) throw new Error(t('app.sceneImportEnvironmentValueRequired', { name }))
      if (!trust) throw new Error(t('app.sceneImportMissingField', { field: `environmentVariables[${index}].trust` }))
      if (!privacy) throw new Error(t('app.sceneImportMissingField', { field: `environmentVariables[${index}].privacy` }))
      return {
        name,
        value: normalizedValue,
        trust,
        privacy
      }
    })
  }

  const normalizeSceneRuleSourceType = (value: unknown, field = 'rules.sources.itemType'): RuleSourceItemType => {
    const normalized = normalizeSceneString(value, field).toLowerCase()
    if (normalized === 'api' || normalized === 'variable' || normalized === 'mode' || normalized === 'state') {
      return normalized
    }
    throw new Error(t('app.sceneImportInvalidEnum', { field: 'rules.sources.itemType', value: normalized || t('app.empty') }))
  }

  const normalizeSceneRules = (value: unknown): RuleForm[] => {
    if (value === undefined || value === null) return []
    if (!Array.isArray(value)) throw new Error(t('app.sceneImportArrayRequired', { field: 'rules' }))
    assertSceneCollectionLimit(value, 'rules', REQUEST_LIMITS.rules)
    return value.map((item, index) => {
      const row = item as any
      if (!row || typeof row !== 'object' || Array.isArray(row)) {
        throw new Error(t('app.sceneImportObjectRequired', { field: `rules[${index}]` }))
      }
      rejectSceneInternalField(row, 'id')
      assertSceneAllowedFields(row, ['name', 'sources', 'toId', 'toApi', 'contentDevice', 'content'], `rules[${index}]`)
      const sources = Array.isArray(row.sources) ? row.sources : []
      assertSceneCollectionLimit(sources, `rules[${index}].sources`, REQUEST_LIMITS.ruleConditions)
      if (sources.length === 0) throw new Error(t('app.sceneImportMissingField', { field: `rules[${index}].sources` }))
      const name = normalizeSceneString(row.name, `rules[${index}].name`)
      const toId = normalizeSceneString(row.toId, `rules[${index}].toId`)
      const toApi = normalizeSceneString(row.toApi, `rules[${index}].toApi`)
      if (!toId) throw new Error(t('app.sceneImportMissingField', { field: `rules[${index}].toId` }))
      if (!toApi) throw new Error(t('app.sceneImportMissingField', { field: `rules[${index}].toApi` }))
      const contentDevice = normalizeSceneString(row.contentDevice, `rules[${index}].contentDevice`)
      const content = normalizeSceneString(row.content, `rules[${index}].content`)
      return {
        ...(name ? { name } : {}),
        sources: sources.map((source: any, sourceIndex: number) => {
          if (!source || typeof source !== 'object' || Array.isArray(source)) {
            throw new Error(t('app.sceneImportObjectRequired', { field: `rules[${index}].sources[${sourceIndex}]` }))
          }
          assertSceneAllowedFields(
            source,
            ['fromId', 'fromApi', 'itemType', 'relation', 'value'],
            `rules[${index}].sources[${sourceIndex}]`
          )
          const sourceField = `rules[${index}].sources[${sourceIndex}]`
          const itemType = normalizeSceneRuleSourceType(source?.itemType, `${sourceField}.itemType`)
          const fromId = normalizeSceneString(source?.fromId, `${sourceField}.fromId`)
          const fromApi = normalizeSceneString(source?.fromApi, `${sourceField}.fromApi`)
          if (!fromId) throw new Error(t('app.sceneImportMissingField', { field: `rules[${index}].sources[${sourceIndex}].fromId` }))
          if (!fromApi) throw new Error(t('app.sceneImportMissingField', { field: `rules[${index}].sources[${sourceIndex}].fromApi` }))
          if (itemType === 'api' && (
            normalizeSceneString(source?.relation, `${sourceField}.relation`)
            || normalizeSceneString(source?.value, `${sourceField}.value`)
          )) {
            throw new Error(t('app.sceneImportUnexpectedRuleSignalValue', {
              field: `rules[${index}].sources[${sourceIndex}]`
            }))
          }
          const relation = normalizeSceneString(source?.relation, `${sourceField}.relation`)
          const conditionValue = normalizeSceneString(source?.value, `${sourceField}.value`)
          if (itemType !== 'api' && !relation) {
            throw new Error(t('app.sceneImportMissingField', { field: `rules[${index}].sources[${sourceIndex}].relation` }))
          }
          if (itemType !== 'api' && !conditionValue) {
            throw new Error(t('app.sceneImportMissingField', { field: `rules[${index}].sources[${sourceIndex}].value` }))
          }
          const normalizedRelation = itemType === 'api' ? '' : normalizeModelRelation(relation)
          if (itemType !== 'api' && !normalizedRelation) {
            throw new Error(t('app.sceneImportInvalidEnum', {
              field: `rules[${index}].sources[${sourceIndex}].relation`,
              value: relation
            }))
          }
          return {
            fromId,
            fromApi,
            itemType,
            ...(itemType === 'api' ? {} : {
              relation: normalizedRelation,
              value: conditionValue
            })
          }
        }),
        toId,
        toApi,
        ...(contentDevice ? { contentDevice } : {}),
        ...(content ? { content } : {})
      }
    })
  }

  const normalizeSceneSpecTargetType = (value: unknown, field = 'specs.conditions.targetType') => {
    const normalized = normalizeSceneString(value, field).toLowerCase()
    if (['state', 'mode', 'variable', 'api', 'trust', 'privacy'].includes(normalized)) {
      return normalized as SpecCondition['targetType']
    }
    throw new Error(t('app.sceneImportInvalidEnum', { field: 'specs.conditions.targetType', value: normalized || t('app.empty') }))
  }

  /**
   * A `variable` condition must declare which value it means; the two answers differ when a device
   * is compromised, so an imported scene that omits it is rejected rather than assigned a side.
   * The field is refused on every other condition type, matching admission.
   */
  const normalizeSceneVariableSource = (
    value: unknown,
    targetType: SpecCondition['targetType'],
    field: string
  ): SpecCondition['variableSource'] | undefined => {
    const normalized = normalizeSceneString(value, field).toLowerCase()
    if (targetType !== 'variable') {
      if (normalized) throw new Error(t('app.sceneImportUnexpectedField', { field }))
      return undefined
    }
    if (!normalized) throw new Error(t('app.sceneImportMissingField', { field }))
    if (normalized !== 'environment' && normalized !== 'reported') {
      throw new Error(t('app.sceneImportInvalidEnum', { field, value: normalized }))
    }
    return normalized
  }

  const canonicalSceneLabelValue = (targetType: string, relation: string, value: string): string => {
    if (targetType !== 'trust' && targetType !== 'privacy') return value
    const normalized = value.toLowerCase()
    return relation === 'in' || relation === 'not in'
      ? normalized.split(/[,;|]/).map(part => part.trim()).filter(Boolean).join(', ')
      : normalized
  }

  const normalizeSceneSpecConditions = (
    value: unknown,
    side: SpecCondition['side'],
    field: string,
    idPrefix: string
  ): SpecCondition[] => {
    if (value === undefined || value === null) return []
    if (!Array.isArray(value)) throw new Error(t('app.sceneImportArrayRequired', { field }))
    assertSceneCollectionLimit(value, field, REQUEST_LIMITS.specificationConditions)
    return value.map((item, index) => {
      const row = item as any
      if (!row || typeof row !== 'object' || Array.isArray(row)) {
        throw new Error(t('app.sceneImportObjectRequired', { field: `${field}[${index}]` }))
      }
      rejectSceneInternalField(row, 'id')
      rejectSceneInternalField(row, 'side')
      rejectSceneInternalField(row, 'deviceLabel')
      assertSceneAllowedFields(
        row,
        ['deviceId', 'targetType', 'key', 'propertyScope', 'variableSource', 'relation', 'value'],
        `${field}[${index}]`
      )
      const conditionField = `${field}[${index}]`
      const deviceId = normalizeSceneString(row.deviceId, `${conditionField}.deviceId`)
      const targetType = normalizeSceneSpecTargetType(row.targetType, `${conditionField}.targetType`)
      const key = normalizeSceneString(row.key, `${conditionField}.key`)
      const propertyScope = normalizeSceneString(row.propertyScope, `${conditionField}.propertyScope`).toLowerCase()
      const isPropertyCondition = targetType === 'trust' || targetType === 'privacy'
      if (!deviceId) throw new Error(t('app.sceneImportMissingField', { field: `${field}[${index}].deviceId` }))
      if (!key) throw new Error(t('app.sceneImportMissingField', { field: `${field}[${index}].key` }))
      if (isPropertyCondition && !['state', 'variable'].includes(propertyScope)) {
        throw new Error(t('app.sceneImportInvalidEnum', {
          field: `${field}[${index}].propertyScope`,
          value: propertyScope || t('app.empty')
        }))
      }
      if (!isPropertyCondition && propertyScope) {
        throw new Error(t('app.sceneImportUnexpectedField', { field: `${field}[${index}].propertyScope` }))
      }
      const variableSource = normalizeSceneVariableSource(
        row.variableSource, targetType, `${conditionField}.variableSource`)
      const relation = normalizeSceneString(row.relation, `${conditionField}.relation`)
      const conditionValue = normalizeSceneString(row.value, `${conditionField}.value`)
      if (!relation) throw new Error(t('app.sceneImportMissingField', { field: `${field}[${index}].relation` }))
      if (!conditionValue) throw new Error(t('app.sceneImportMissingField', { field: `${field}[${index}].value` }))
      const normalizedRelation = normalizeModelRelation(relation)
      if (!normalizedRelation) {
        throw new Error(t('app.sceneImportInvalidEnum', { field: `${field}[${index}].relation`, value: relation }))
      }
      return {
        id: `${idPrefix}_${index + 1}`,
        side,
        deviceId,
        deviceLabel: deviceId,
        targetType,
        key,
        ...(isPropertyCondition ? { propertyScope: propertyScope as 'state' | 'variable' } : {}),
        ...(variableSource ? { variableSource } : {}),
        relation: normalizedRelation,
        value: canonicalSceneLabelValue(targetType, normalizedRelation, conditionValue)
      }
    })
  }

  const normalizeSceneSpecs = (value: unknown): Specification[] => {
    if (value === undefined || value === null) return []
    if (!Array.isArray(value)) throw new Error(t('app.sceneImportArrayRequired', { field: 'specs' }))
    assertSceneCollectionLimit(value, 'specs', REQUEST_LIMITS.specifications)
    return value.map((item, index) => {
      const row = item as any
      if (!row || typeof row !== 'object' || Array.isArray(row)) {
        throw new Error(t('app.sceneImportObjectRequired', { field: `specs[${index}]` }))
      }
      rejectSceneInternalField(row, 'id')
      rejectSceneInternalField(row, 'templateLabel')
      rejectSceneInternalField(row, 'formula')
      rejectSceneInternalField(row, 'devices')
      assertSceneAllowedFields(
        row,
        ['name', 'templateId', 'aConditions', 'ifConditions', 'thenConditions'],
        `specs[${index}]`
      )
      const name = normalizeSpecificationName(row.name, `specs[${index}].name`)
      const templateId = normalizeSceneString(row.templateId, `specs[${index}].templateId`) as Specification['templateId']
      if (!defaultSpecTemplates.some(template => template.id === templateId)) {
        throw new Error(t('app.sceneImportInvalidEnum', { field: `specs[${index}].templateId`, value: templateId || t('app.empty') }))
      }
      const template = specTemplateDetails.find(candidate => candidate.id === templateId)
      const templateLabel = defaultSpecTemplates.find(candidate => candidate.id === templateId)?.label || templateId
      const aConditions = normalizeSceneSpecConditions(row.aConditions, 'a', `specs[${index}].aConditions`, `spec_${index + 1}_a`)
      const ifConditions = normalizeSceneSpecConditions(row.ifConditions, 'if', `specs[${index}].ifConditions`, `spec_${index + 1}_if`)
      const thenConditions = normalizeSceneSpecConditions(row.thenConditions, 'then', `specs[${index}].thenConditions`, `spec_${index + 1}_then`)
      const conditionsBySide = { a: aConditions, if: ifConditions, then: thenConditions }
      for (const side of ['a', 'if', 'then'] as const) {
        const field = `specs[${index}].${side === 'a' ? 'aConditions' : `${side}Conditions`}`
        const required = template?.requiredSides.includes(side) === true
        if (required && conditionsBySide[side].length === 0) {
          throw new Error(t('app.sceneImportMissingField', { field }))
        }
        if (!required && conditionsBySide[side].length > 0) {
          throw new Error(t('app.sceneImportUnexpectedField', { field }))
        }
      }
      return {
        id: `scene_spec_${index + 1}`,
        name,
        templateId,
        templateLabel,
        aConditions,
        ifConditions,
        thenConditions,
        devices: []
      }
    })
  }

  const normalizeSceneTemplates = (value: unknown): DeviceTemplate[] => {
    if (value === undefined || value === null) return []
    if (!Array.isArray(value)) throw new Error(t('app.sceneImportArrayRequired', { field: 'templates' }))
    assertSceneCollectionLimit(value, 'templates', REQUEST_LIMITS.templates)
    return value.map((item, index) => {
      const row = item as any
      if (!row || typeof row !== 'object' || Array.isArray(row)) {
        throw new Error(t('app.sceneImportObjectRequired', { field: `templates[${index}]` }))
      }
      rejectSceneInternalField(row, 'id')
      rejectSceneInternalField(row, 'defaultTemplate')
      assertSceneAllowedFields(row, ['name', 'manifest'], `templates[${index}]`)
      const manifest = row.manifest
      const name = normalizeSceneString(row.name, `templates[${index}].name`)
      if (!name) throw new Error(t('app.sceneImportMissingField', { field: `templates[${index}].name` }))
      if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) {
        throw new Error(t('app.sceneImportMissingField', { field: `templates[${index}].manifest` }))
      }
      const manifestName = normalizeSceneString(manifest.Name, `templates[${index}].manifest.Name`)
      if (!manifestName) {
        throw new Error(t('app.sceneImportMissingField', { field: `templates[${index}].manifest.Name` }))
      }
      if (name !== manifestName) {
        throw new Error(t('app.sceneImportTemplateNameMismatch', { name, manifestName }))
      }
      const validation = validateManifest(manifest)
      if (!validation.valid) {
        const reason = validation.code
          ? t(MANIFEST_VALIDATION_MESSAGE_KEYS[validation.code], validation.params || {})
          : validation.msg || t('app.unknownOmissionReason')
        throw new Error(t('app.sceneImportInvalidTemplateManifest', {
          name,
          reason
        }))
      }
      return {
        name,
        manifest: { ...deepClone(manifest), Name: name }
      }
    })
  }

  const assertSceneTemplateCoverage = (scene: Pick<BoardSceneModel, 'templates' | 'devices'>) => {
    const referencedNames = new Map<string, string>()
    scene.devices.forEach(device => {
      referencedNames.set(normalizeTemplateLookupName(device.templateName), device.templateName)
    })

    const snapshotByAlias = new Map<string, DeviceTemplate>()
    const matchedSnapshots = new Set<DeviceTemplate>()
    for (const template of scene.templates) {
      const aliases = [template.name, template.manifest?.Name]
        .map(normalizeTemplateLookupName)
        .filter(Boolean)
      for (const alias of aliases) {
        const previous = snapshotByAlias.get(alias)
        if (previous && previous !== template) {
          throw new Error(t('app.sceneImportDuplicateTemplateSnapshot', { name: template.name || template.manifest?.Name || alias }))
        }
        snapshotByAlias.set(alias, template)
      }
    }

    for (const [key, displayName] of referencedNames) {
      const snapshot = snapshotByAlias.get(key)
      if (!snapshot) {
        throw new Error(t('app.sceneImportMissingTemplates', { names: displayName }))
      }
      matchedSnapshots.add(snapshot)
    }

    const unreferenced = scene.templates
      .filter(template => !matchedSnapshots.has(template))
      .map(template => template.name || template.manifest?.Name)
      .filter(Boolean)
    if (unreferenced.length > 0) {
      throw new Error(t('app.sceneImportUnreferencedTemplates', { names: unreferenced.join(', ') }))
    }
  }

  const assertSceneEnvironmentCoverage = (
    scene: Pick<BoardSceneModel, 'templates' | 'devices' | 'environmentVariables'>
  ) => {
    const templatesByAlias = new Map<string, DeviceTemplate>()
    scene.templates.forEach(template => {
      for (const alias of [template.name, template.manifest?.Name]) {
        const key = normalizeTemplateLookupName(alias)
        if (key) templatesByAlias.set(key, template)
      }
    })

    const requiredNames = new Set<string>()
    scene.devices.forEach(device => {
      const template = templatesByAlias.get(normalizeTemplateLookupName(device.templateName))
      const manifest = template?.manifest
      ;(manifest?.InternalVariables || []).forEach(variable => {
        const name = normalizeSceneString(variable?.Name, 'templates[].manifest.InternalVariables[].Name')
        if (name && variable?.IsInside !== true) requiredNames.add(name)
      })
      ;(manifest?.ImpactedVariables || []).forEach(variableName => {
        const name = normalizeSceneString(variableName, 'templates[].manifest.ImpactedVariables[]')
        if (name) requiredNames.add(name)
      })
    })

    const providedNames = new Set(scene.environmentVariables.map(variable => variable.name))
    const missing = [...requiredNames].filter(name => !providedNames.has(name)).sort(compareSceneText)
    const unknown = [...providedNames].filter(name => !requiredNames.has(name)).sort(compareSceneText)
    if (missing.length > 0) {
      throw new Error(t('app.sceneImportMissingEnvironmentVariables', { names: missing.join(', ') }))
    }
    if (unknown.length > 0) {
      throw new Error(t('app.sceneImportUnknownEnvironmentVariables', { names: unknown.join(', ') }))
    }
  }

  const assertUniqueSceneDeviceIds = (devices: DeviceNode[]) => {
    const seen = new Set<string>()
    const seenModelIds = new Map<string, DeviceNode>()
    const seenLabels = new Set<string>()
    for (const device of devices) {
      if (seen.has(device.id)) {
        throw new Error(t('app.sceneImportDuplicateDevice', { id: device.id }))
      }
      seen.add(device.id)
      const modelId = normalizeNuSmvDeviceName(device.id)
      const previous = seenModelIds.get(modelId)
      if (previous) {
        throw new Error(t('app.sceneImportModelIdentityCollision', {
          first: previous.label,
          second: device.label
        }))
      }
      seenModelIds.set(modelId, device)
      const labelKey = device.label.trim().toLowerCase()
      if (seenLabels.has(labelKey)) {
        throw new Error(t('app.sceneImportDuplicateDeviceLabel', { label: device.label }))
      }
      seenLabels.add(labelKey)
    }
  }

  const assertSceneReferences = (scene: Pick<BoardSceneModel, 'devices' | 'rules' | 'specs'>) => {
    const deviceIds = new Set(scene.devices.map(device => device.id))
    scene.rules.forEach((rule, ruleIndex) => {
      rule.sources.forEach((source, sourceIndex) => {
        if (!deviceIds.has(source.fromId)) {
          throw new Error(t('app.sceneImportUnknownDeviceRef', { field: `rules[${ruleIndex}].sources[${sourceIndex}].fromId`, id: source.fromId }))
        }
      })
      if (!deviceIds.has(rule.toId)) {
        throw new Error(t('app.sceneImportUnknownDeviceRef', { field: `rules[${ruleIndex}].toId`, id: rule.toId }))
      }
      if (Boolean(rule.contentDevice) !== Boolean(rule.content)) {
        throw new Error(t('app.sceneImportContentPairRequired', { index: ruleIndex + 1 }))
      }
      if (rule.contentDevice && !deviceIds.has(rule.contentDevice)) {
        throw new Error(t('app.sceneImportUnknownDeviceRef', { field: `rules[${ruleIndex}].contentDevice`, id: rule.contentDevice }))
      }
    })

    const checkSpecCondition = (condition: SpecCondition, field: string) => {
      if (!deviceIds.has(condition.deviceId)) {
        throw new Error(t('app.sceneImportUnknownDeviceRef', { field, id: condition.deviceId }))
      }
    }
    scene.specs.forEach((spec, specIndex) => {
      spec.aConditions.forEach((condition, index) => checkSpecCondition(condition, `specs[${specIndex}].aConditions[${index}].deviceId`))
      spec.ifConditions.forEach((condition, index) => checkSpecCondition(condition, `specs[${specIndex}].ifConditions[${index}].deviceId`))
      spec.thenConditions.forEach((condition, index) => checkSpecCondition(condition, `specs[${specIndex}].thenConditions[${index}].deviceId`))
    })
  }

  const compareSceneText = (left: string, right: string) =>
    left.localeCompare(right, 'en', { numeric: true, sensitivity: 'base' })

  const canonicalPlainValue = (value: unknown): unknown => {
    if (Array.isArray(value)) {
      return value.map(item => canonicalPlainValue(item))
    }
    if (value && typeof value === 'object') {
      return Object.keys(value as Record<string, unknown>)
        .sort(compareSceneText)
        .reduce<Record<string, unknown>>((result, key) => {
          const nextValue = canonicalPlainValue((value as Record<string, unknown>)[key])
          if (nextValue !== undefined) result[key] = nextValue
          return result
        }, {})
    }
    return value
  }

  const sceneJsonKey = (value: unknown) =>
    JSON.stringify(canonicalPlainValue(value))

  const sortSceneItems = <T,>(items: T[], key: (item: T) => unknown) =>
    [...items].sort((left, right) => compareSceneText(sceneJsonKey(key(left)), sceneJsonKey(key(right))))

  const canonicalSceneDevice = (
    device: DeviceNode,
    index: number,
    templates: DeviceTemplate[]
  ): PortableSceneDevice => {
    const normalized = normalizeSceneDevice(device, index, templates)
    const variables = (normalized.variables || []).map(variable => ({
      name: variable.name,
      value: variable.value,
      ...(variable.trust ? { trust: variable.trust } : {})
    }))

    const privacies = (normalized.privacies || []).map(privacy => ({
      name: privacy.name,
      privacy: privacy.privacy
    }))

    const { state, currentStateTrust, currentStatePrivacy, ...portable } = normalized
    const hasStateMachine = sceneTemplateHasStateMachine(sceneTemplateForDevice(normalized, templates))
    return {
      ...portable,
      ...(hasStateMachine ? {
        state,
        ...(currentStateTrust ? { currentStateTrust } : {}),
        ...(currentStatePrivacy ? { currentStatePrivacy } : {})
      } : {}),
      ...(variables.length > 0 ? { variables } : {}),
      ...(privacies.length > 0 ? { privacies } : {})
    }
  }

  const canonicalSceneEnvironmentVariable = (
    variable: ModelEnvironmentVariable,
    index: number
  ): PortableSceneEnvironmentVariable => {
    const field = `environmentVariables[${index}]`
    const name = normalizeSceneString(variable.name, `${field}.name`)
    if (!name) throw new Error(t('app.sceneImportMissingField', { field: `${field}.name` }))
    const value = normalizeSceneString(variable.value, `${field}.value`)
    if (!value) throw new Error(t('app.sceneImportEnvironmentValueRequired', { name: name || t('app.empty') }))
    const trust = normalizeSceneTrust(variable.trust, `${field}.trust`)
    const privacy = normalizeScenePrivacy(variable.privacy, `${field}.privacy`)
    if (!trust) throw new Error(t('app.sceneImportMissingField', { field: `${field}.trust` }))
    if (!privacy) throw new Error(t('app.sceneImportMissingField', { field: `${field}.privacy` }))
    return {
      name,
      value,
      trust,
      privacy
    }
  }

  const canonicalSceneRule = (rule: RuleForm, ruleIndex: number): PortableSceneRule => {
    const ruleField = `rules[${ruleIndex}]`
    if (!Array.isArray(rule.sources) || rule.sources.length === 0) {
      throw new Error(t('app.sceneImportMissingField', { field: `${ruleField}.sources` }))
    }
    const sources = rule.sources.map((source, sourceIndex) => {
      const sourceField = `${ruleField}.sources[${sourceIndex}]`
      const itemType = normalizeSceneRuleSourceType(source.itemType, `${sourceField}.itemType`)
      const fromId = normalizeSceneString(source.fromId, `${sourceField}.fromId`)
      const fromApi = normalizeSceneString(source.fromApi, `${sourceField}.fromApi`)
      if (!fromId) throw new Error(t('app.sceneImportMissingField', { field: `${sourceField}.fromId` }))
      if (!fromApi) throw new Error(t('app.sceneImportMissingField', { field: `${sourceField}.fromApi` }))
      const relation = normalizeSceneString(source.relation, `${sourceField}.relation`)
      const value = normalizeSceneString(source.value, `${sourceField}.value`)
      if (itemType === 'api' && (relation || value)) {
        throw new Error(t('app.sceneImportUnexpectedRuleSignalValue', { field: sourceField }))
      }
      if (itemType !== 'api' && !relation) {
        throw new Error(t('app.sceneImportMissingField', { field: `${sourceField}.relation` }))
      }
      if (itemType !== 'api' && !value) {
        throw new Error(t('app.sceneImportMissingField', { field: `${sourceField}.value` }))
      }
      const normalizedRelation = itemType === 'api' ? '' : normalizeModelRelation(relation)
      if (itemType !== 'api' && !normalizedRelation) {
        throw new Error(t('app.sceneImportInvalidEnum', { field: `${sourceField}.relation`, value: relation }))
      }
      return {
        fromId,
        fromApi,
        itemType,
        ...(itemType === 'api' ? {} : {
          relation: normalizedRelation,
          value
        })
      }
    })

    const name = normalizeSceneString(rule.name, `${ruleField}.name`)
    const toId = normalizeSceneString(rule.toId, `${ruleField}.toId`)
    const toApi = normalizeSceneString(rule.toApi, `${ruleField}.toApi`)
    if (!toId) throw new Error(t('app.sceneImportMissingField', { field: `${ruleField}.toId` }))
    if (!toApi) throw new Error(t('app.sceneImportMissingField', { field: `${ruleField}.toApi` }))
    const contentDevice = normalizeSceneString(rule.contentDevice, `${ruleField}.contentDevice`)
    const content = normalizeSceneString(rule.content, `${ruleField}.content`)
    if (Boolean(contentDevice) !== Boolean(content)) {
      throw new Error(t('app.sceneImportContentPairRequired', { index: ruleIndex + 1 }))
    }
    return {
      ...(name ? { name } : {}),
      sources,
      toId,
      toApi,
      ...(contentDevice ? { contentDevice } : {}),
      ...(content ? { content } : {})
    }
  }

  const canonicalSceneSpecCondition = (
    condition: SpecCondition,
    field: string
  ): PortableSceneCondition => {
    const deviceId = normalizeSceneString(condition.deviceId, `${field}.deviceId`)
    const targetType = normalizeSceneSpecTargetType(condition.targetType, `${field}.targetType`)
    const key = normalizeSceneString(condition.key, `${field}.key`)
    const propertyScope = normalizeSceneString(condition.propertyScope, `${field}.propertyScope`).toLowerCase()
    const variableSource = normalizeSceneVariableSource(
      condition.variableSource, targetType, `${field}.variableSource`)
    const relation = normalizeSceneString(condition.relation, `${field}.relation`)
    const value = normalizeSceneString(condition.value, `${field}.value`)
    if (!deviceId) throw new Error(t('app.sceneImportMissingField', { field: `${field}.deviceId` }))
    if (!key) throw new Error(t('app.sceneImportMissingField', { field: `${field}.key` }))
    if (!relation) throw new Error(t('app.sceneImportMissingField', { field: `${field}.relation` }))
    if (!value) throw new Error(t('app.sceneImportMissingField', { field: `${field}.value` }))
    const isPropertyCondition = targetType === 'trust' || targetType === 'privacy'
    if (isPropertyCondition && !['state', 'variable'].includes(propertyScope)) {
      throw new Error(t('app.sceneImportInvalidEnum', { field: `${field}.propertyScope`, value: propertyScope || t('app.empty') }))
    }
    if (!isPropertyCondition && propertyScope) {
      throw new Error(t('app.sceneImportUnexpectedField', { field: `${field}.propertyScope` }))
    }
    const normalizedRelation = normalizeModelRelation(relation)
    if (!normalizedRelation) {
      throw new Error(t('app.sceneImportInvalidEnum', { field: `${field}.relation`, value: relation }))
    }
    return {
      deviceId,
      targetType,
      key,
      ...(isPropertyCondition ? { propertyScope: propertyScope as 'state' | 'variable' } : {}),
      ...(variableSource ? { variableSource } : {}),
      relation: normalizedRelation,
      value: canonicalSceneLabelValue(targetType, normalizedRelation, value)
    }
  }

  const canonicalSceneSpec = (spec: Specification, specIndex: number): PortableSceneSpecification => ({
    name: normalizeSpecificationName(spec.name, `specs[${specIndex}].name`),
    templateId: normalizeSceneString(spec.templateId, `specs[${specIndex}].templateId`) as Specification['templateId'],
    aConditions: (spec.aConditions || []).map((condition, index) =>
      canonicalSceneSpecCondition(condition, `specs[${specIndex}].aConditions[${index}]`)),
    ifConditions: (spec.ifConditions || []).map((condition, index) =>
      canonicalSceneSpecCondition(condition, `specs[${specIndex}].ifConditions[${index}]`)),
    thenConditions: (spec.thenConditions || []).map((condition, index) =>
      canonicalSceneSpecCondition(condition, `specs[${specIndex}].thenConditions[${index}]`))
  })

  const canonicalSceneTemplate = (template: DeviceTemplate, index: number): PortableSceneTemplate => {
    const name = normalizeSceneString(template.name || template.manifest?.Name, `templates[${index}].name`)
    if (!name) throw new Error(t('app.sceneImportMissingField', { field: `templates[${index}].name` }))
    return {
      name,
      manifest: canonicalizeTemplateManifest(template.manifest) as DeviceTemplate['manifest']
    }
  }

  // Import diagnostics refer to authored array indexes, so serialization must keep their order.
  const serializeSceneFile = (scene: BoardSceneModel): PortableSceneFile => ({
    schema: SCENE_FILE_SCHEMA,
    version: SCENE_FILE_VERSION,
    templates: (scene.templates || []).map(canonicalSceneTemplate),
    devices: scene.devices.map((device, index) => canonicalSceneDevice(device, index, scene.templates)),
    environmentVariables: scene.environmentVariables.map(canonicalSceneEnvironmentVariable),
    rules: scene.rules.map(canonicalSceneRule),
    specs: scene.specs.map(canonicalSceneSpec)
  })

  const canonicalizeSceneFile = (scene: BoardSceneModel): PortableSceneFile => {
    const portable = serializeSceneFile(scene)
    return {
      ...portable,
      templates: sortSceneItems(portable.templates, template => template.name),
      devices: sortSceneItems(portable.devices.map(device => ({
        ...device,
        ...(device.variables ? { variables: sortSceneItems(device.variables, variable => variable.name) } : {}),
        ...(device.privacies ? { privacies: sortSceneItems(device.privacies, privacy => privacy.name) } : {})
      })), device => device.id),
      environmentVariables: sortSceneItems(portable.environmentVariables, variable => variable.name)
    }
  }

  const normalizeSceneFile = (raw: unknown): BoardSceneModel => {
    const payload = raw as any
    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
      throw new Error(t('app.sceneImportInvalidFile'))
    }
    assertSceneAllowedFields(
      payload,
      ['schema', 'version', 'templates', 'devices', 'environmentVariables', 'rules', 'specs'],
      ''
    )
    if (payload.schema !== SCENE_FILE_SCHEMA) {
      throw new Error(t('app.sceneImportInvalidFile'))
    }
    if (payload.version !== SCENE_FILE_VERSION) {
      throw new Error(t('app.sceneImportUnsupportedVersion', {
        version: payload.version ?? t('app.empty'),
        supported: SCENE_FILE_VERSION
      }))
    }
    for (const field of ['templates', 'devices', 'environmentVariables', 'rules', 'specs']) {
      if (!Array.isArray(payload[field])) {
        throw new Error(t('app.sceneImportArrayRequired', { field }))
      }
    }
    assertSceneCollectionLimit(payload.devices, 'devices', REQUEST_LIMITS.devices)
    const templates = normalizeSceneTemplates(payload.templates)
    const devices = payload.devices.map((device: unknown, index: number) => normalizeSceneDevice(device, index, templates))
    assertUniqueSceneDeviceIds(devices)
    assertSceneDeviceRuntimeShape(payload.devices, devices, templates)
    const scene: BoardSceneModel = {
      schema: SCENE_FILE_SCHEMA,
      version: SCENE_FILE_VERSION,
      templates,
      devices,
      environmentVariables: normalizeSceneEnvironmentVariables(payload.environmentVariables),
      rules: normalizeSceneRules(payload.rules),
      specs: normalizeSceneSpecs(payload.specs)
    }
    assertSceneReferences(scene)
    assertSceneTemplateCoverage(scene)
    assertSceneEnvironmentCoverage(scene)
    const labelsByDeviceId = new Map(scene.devices.map(device => [device.id, device.label]))
    scene.specs = scene.specs.map(spec => ({
      ...spec,
      aConditions: spec.aConditions.map(condition => ({
        ...condition,
        deviceLabel: labelsByDeviceId.get(condition.deviceId) || condition.deviceId
      })),
      ifConditions: spec.ifConditions.map(condition => ({
        ...condition,
        deviceLabel: labelsByDeviceId.get(condition.deviceId) || condition.deviceId
      })),
      thenConditions: spec.thenConditions.map(condition => ({
        ...condition,
        deviceLabel: labelsByDeviceId.get(condition.deviceId) || condition.deviceId
      }))
    })).map(spec => ({
      ...spec,
      formula: buildSpecFormula(spec, {
        nodes: scene.devices
      }),
      devices: buildSpecDeviceRefsFromConditions([
        ...spec.aConditions,
        ...spec.ifConditions,
        ...spec.thenConditions
      ], scene.devices)
    }))
    return scene
  }

  return {
    requireIntegerInRange,
    assertSceneCollectionLimit,
    assertSceneTemplateCoverage,
    assertSceneEnvironmentCoverage,
    assertUniqueSceneDeviceIds,
    assertSceneReferences,
    serializeSceneFile,
    canonicalizeSceneFile,
    normalizeSceneFile
  }
}
