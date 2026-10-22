<script setup lang="ts">
import FieldSelect from '@/components/common/FieldSelect.vue'
import InfoTooltip from '@/components/common/InfoTooltip.vue'
import { reactive, computed, watch, ref, nextTick, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuth } from '@/stores/auth'
import type { DeviceNode } from '../types/node'
import type { DeviceManifest, DeviceTemplate } from '../types/device'
import type { RuleForm, RuleSourceItemType } from '../types/rule'
import boardApi, { type DuplicateRuleCheckResult } from '../api/board'
import { duplicateRuleReasonKey, ruleSimilarityReasonKey } from '@/utils/rule'
import { REQUEST_LIMITS } from '@/constants/requestLimits'
import { formatBuiltInModelToken } from '@/utils/modelTokenDisplay'
import { validateNumericConditionValue } from '@/utils/deviceRuntime'
import type { ApiErrorDetails } from '@/utils/userMessage'
import FailureDetails from '@/components/FailureDetails.vue'
import {
  confirmChoice,
  dismissOpenConfirmation,
  notifyBlocked,
  notifySuccess
} from '@/utils/feedback'

const { t } = useI18n()

// Props
interface Props {
  active?: boolean
  readOnly?: boolean
  editRule?: RuleForm | null
  nodes: DeviceNode[]
  deviceTemplates: any[]
}

const props = withDefaults(defineProps<Props>(), {
  active: true,
  readOnly: false,
  nodes: () => [],
  deviceTemplates: () => []
})

const deviceNodes = computed(() => props.nodes || [])

const resolveDeviceNode = (ref?: string | null): DeviceNode | undefined => {
  if (!ref) return undefined
  return deviceNodes.value.find((node: DeviceNode) => node.id === ref)
}

// Emits
const emit = defineEmits<{
  'save-rule': [request: { rule: RuleForm; expected?: RuleForm; complete: (saved: boolean, failure?: ApiErrorDetails) => void }]
  'finish-edit': []
}>()

// Rule data
const ruleData = reactive<RuleForm>({
  id: '',
  name: '',
  sources: [],
  toId: '',
  toApi: '',
  contentDevice: '',
  content: ''
})

// Current source being added
const currentSource = reactive({
  fromId: '',  // Source board device id
  itemType: '' as '' | RuleSourceItemType,
  fromApi: '',  // API name, variable name, mode name; full-state conditions use state
  relation: '=',
  value: ''
})
const editingSourceIndex = ref<number | null>(null)

interface SourceOption {
  name: string
  label?: string
  type: RuleSourceItemType
  values?: string[]
  lowerBound?: number
  upperBound?: number
}

interface ContentOption {
  name: string
  privacy: 'public' | 'private'
}

const VALUE_BASED_SOURCE_TYPES = new Set<RuleSourceItemType>(['variable', 'mode', 'state'])
const isValueBasedSourceType = (type?: RuleSourceItemType | ''): type is RuleSourceItemType =>
  Boolean(type && VALUE_BASED_SOURCE_TYPES.has(type))

// 条件选项 - 使用 NuSMV 兼容的关系运算符（label 走 i18n，符号语言无关）
const relationOptions = computed(() => [
  { label: `${t('app.relationEquals')} (=)`, value: '=' },
  { label: `${t('app.relationNotEquals')} (≠)`, value: '!=' },
  { label: `${t('app.relationGreater')} (>)`, value: '>' },
  { label: `${t('app.relationLess')} (<)`, value: '<' },
  { label: `${t('app.relationGreaterEqual')} (≥)`, value: '>=' },
  { label: `${t('app.relationLessEqual')} (≤)`, value: '<=' },
  { label: `${t('app.relationIn')} (in)`, value: 'in' },
  { label: `${t('app.relationNotIn')} (not in)`, value: 'not in' }
])

const conditionRelationOptions = computed(() => {
  const enumRelations = new Set(['=', '!=', 'in', 'not in'])
  const numericRelations = new Set(['=', '!=', '>', '<', '>=', '<=', 'in', 'not in'])

  if (currentSource.itemType === 'mode' || currentSource.itemType === 'state') {
    return relationOptions.value.filter(option => enumRelations.has(option.value))
  }
  if (currentSource.itemType === 'variable' && selectedSourceOption.value?.values?.length) {
    return relationOptions.value.filter(option => enumRelations.has(option.value))
  }
  if (currentSource.itemType === 'variable') {
    return relationOptions.value.filter(option => numericRelations.has(option.value))
  }
  return relationOptions.value
})

const resolveDeviceTemplate = (templateName: string): DeviceTemplate | undefined => {
  if (!templateName) return undefined

  const normalizedName = templateName.toLowerCase().trim()

  return props.deviceTemplates.find((t: DeviceTemplate) => {
    const tplName = t.manifest?.Name || t.name || ''
    return tplName.toLowerCase().trim() === normalizedName
  })
}

const formatTemplateModelToken = (templateName: string, value: unknown) => {
  const raw = value === null || value === undefined ? '' : String(value)
  const template = resolveDeviceTemplate(templateName)
  return template?.defaultTemplate === true
    ? formatBuiltInModelToken(raw, t)
    : raw
}

const formatCurrentSourceModelToken = (value: unknown) =>
  formatTemplateModelToken(currentSourceNode.value?.templateName || '', value)

const formatRuleSourceModelToken = (source: RuleForm['sources'][number], value: unknown) =>
  formatTemplateModelToken(resolveDeviceNode(source.fromId)?.templateName || '', value)

const formatRuleSourceName = (source: RuleForm['sources'][number]) =>
  source.itemType === 'state' ? t('app.state') : formatRuleSourceModelToken(source, source.fromApi)

const formatTargetModelToken = (value: unknown) =>
  formatTemplateModelToken(resolveDeviceNode(ruleData.toId)?.templateName || '', value)

const formatContentModelToken = (value: unknown) =>
  formatTemplateModelToken(resolveDeviceNode(ruleData.contentDevice)?.templateName || '', value)

const splitStateTuple = (stateName: string) =>
  String(stateName || '').split(';').map(part => part.trim()).filter(Boolean)

const uniqueStrings = (values: string[]) =>
  Array.from(new Set(values.filter(value => value !== '')))

const getModeValues = (manifest: DeviceManifest, modeName: string): string[] => {
  const modeIndex = (manifest.Modes || []).findIndex(mode => mode === modeName)
  if (modeIndex < 0) return []

  const values = (manifest.WorkingStates || [])
    .map(state => splitStateTuple(state.Name)[modeIndex])
    .filter(Boolean)

  return uniqueStrings(values)
}

const getDeviceApis = (templateName: string, signalOnly = false): SourceOption[] => {
  const template = resolveDeviceTemplate(templateName)

  if (template && template.manifest?.APIs) {
    return template.manifest.APIs
      .filter((api: any) => !signalOnly || api.Signal === true || api.signal === true)
      .map((api: any) => ({
        name: api.Name || api.name || '',
        type: 'api' as const
      }))
  }

  // 如果没有找到模板，返回空数组
  return []
}

// Rule variable conditions use template InternalVariables, which include both device-local variables
// and shared environment variables the device reads.
//
// An affect-only shared declaration (Reads=false) is deliberately excluded: the generator emits no
// `device.name := a_name` mirror for it, so a rule condition on it would reference a value the device
// never observes. Offering it let a user build a rule whose condition silently had no model behind it.
const getDeviceVariables = (templateName: string): SourceOption[] => {
  const template = resolveDeviceTemplate(templateName)
  const variables: SourceOption[] = []

  if (template && template.manifest?.InternalVariables) {
    template.manifest.InternalVariables.forEach((v: any) => {
      if (v.IsInside !== true && v.Reads === false) return
      if (v.Name || v.name) {
        const name = v.Name || v.name || ''
        const scope = v.IsInside === true ? t('app.internalVariable') : t('app.specVariableSourceReportedShort')
        variables.push({
          name,
          label: `${formatTemplateModelToken(templateName, name)} (${scope})`,
          type: 'variable' as const,
          values: Array.isArray(v.Values) ? v.Values.map((value: unknown) => String(value)) : undefined,
          lowerBound: typeof v.LowerBound === 'number' ? v.LowerBound : undefined,
          upperBound: typeof v.UpperBound === 'number' ? v.UpperBound : undefined
        })
      }
    })
  }

  // 如果没有找到模板，返回空数组
  return variables
}

const getDeviceModes = (templateName: string): SourceOption[] => {
  const manifest = resolveDeviceTemplate(templateName)?.manifest
  if (!manifest?.Modes?.length) return []

  return manifest.Modes.map(mode => ({
    name: mode,
    type: 'mode' as const,
    values: getModeValues(manifest, mode)
  }))
}

const getDeviceStates = (templateName: string): SourceOption[] => {
  const manifest = resolveDeviceTemplate(templateName)?.manifest
  if (!manifest?.WorkingStates?.length) return []

  return manifest.WorkingStates.map(state => ({
    name: state.Name,
    type: 'state' as const
  }))
}

const getDeviceContents = (templateName: string): ContentOption[] => {
  const manifest = resolveDeviceTemplate(templateName)?.manifest
  if (!manifest?.Contents?.length) return []

  return manifest.Contents
    .filter(content => Boolean(content?.Name))
    .map(content => ({
      name: content.Name,
      privacy: String(content.Privacy).toLowerCase() === 'private' ? 'private' : 'public'
    }))
}

const contentCapableNodes = computed(() =>
  deviceNodes.value.filter(node => getDeviceContents(node.templateName).length > 0)
)

const availableContentItems = computed(() => {
  const node = resolveDeviceNode(ruleData.contentDevice)
  return node ? getDeviceContents(node.templateName) : []
})

const availableTargetApis = computed(() => {
  if (!ruleData.toId) return []
  const targetNode = resolveDeviceNode(ruleData.toId)
  if (!targetNode) return []

  // 只返回 API 名称（字符串数组）
  const apis = getDeviceApis(targetNode.templateName)
  return apis.map((a: any) => a.name)
})

const selectedTargetAcceptsContent = computed(() => {
  const targetNode = resolveDeviceNode(ruleData.toId)
  const manifest = targetNode ? resolveDeviceTemplate(targetNode.templateName)?.manifest : null
  const api = manifest?.APIs?.find(candidate => candidate.Name === ruleData.toApi)
  return api?.AcceptsContent === true
})

const currentSourceNode = computed(() =>
  currentSource.fromId ? resolveDeviceNode(currentSource.fromId) : undefined
)

const sourceItemTypeOptions = computed(() => {
  const templateName = currentSourceNode.value?.templateName || ''
  return [
    { value: 'api' as RuleSourceItemType, label: t('app.actionEvent'), count: getDeviceApis(templateName, true).length },
    { value: 'variable' as RuleSourceItemType, label: t('app.variable'), count: getDeviceVariables(templateName).length },
    { value: 'mode' as RuleSourceItemType, label: t('app.modes'), count: getDeviceModes(templateName).length },
    { value: 'state' as RuleSourceItemType, label: t('app.state'), count: getDeviceStates(templateName).length }
  ].filter(option => option.count > 0)
})

const availableSourceItems = computed<SourceOption[]>(() => {
  if (!currentSource.fromId) return []
  const sourceNode = currentSourceNode.value
  if (!sourceNode) return []

  if (currentSource.itemType === 'api') return getDeviceApis(sourceNode.templateName, true)
  if (currentSource.itemType === 'variable') return getDeviceVariables(sourceNode.templateName)
  if (currentSource.itemType === 'mode') return getDeviceModes(sourceNode.templateName)
  if (currentSource.itemType === 'state') return getDeviceStates(sourceNode.templateName)
  return []
})

// 根据选择的类型过滤显示的项
const filteredSourceItems = computed(() => {
  if (!currentSource.itemType) return []
  return availableSourceItems.value.filter((item: SourceOption) => item.type === currentSource.itemType)
})

const selectedSourceOption = computed(() =>
  filteredSourceItems.value.find((item: SourceOption) => item.name === currentSource.fromApi)
)

const selectedSourceVariable = computed(() => currentSource.itemType === 'variable'
  ? resolveDeviceTemplate(currentSourceNode.value?.templateName || '')?.manifest?.InternalVariables
    ?.find(variable => variable.Name === currentSource.fromApi)
  : undefined)

const sourceValueFailure = computed(() => selectedSourceVariable.value && currentSource.value.trim()
  ? validateNumericConditionValue(selectedSourceVariable.value, currentSource.value, currentSource.relation, t)
  : '')

const currentValueOptions = computed(() => {
  if (currentSource.itemType === 'state') {
    return filteredSourceItems.value.map((item: SourceOption) => item.name)
  }
  return selectedSourceOption.value?.values || []
})

const isSetRelation = computed(() =>
  currentSource.relation === 'in' || currentSource.relation === 'not in'
)

const splitRuleValueList = (value: unknown): string[] => {
  if (!hasSourceValue(value)) return []
  const delimiter = currentSource.itemType === 'state' ? /[,|]/ : /[,;|]/
  return String(value)
    .split(delimiter)
    .map(part => part.trim())
    .filter(Boolean)
}

const currentSourceValueList = computed<string[]>({
  get: () => splitRuleValueList(currentSource.value),
  set: values => {
    currentSource.value = uniqueStrings(values).join(', ')
  }
})

const currentValuePlaceholder = computed(() => {
  if (currentSource.itemType === 'mode' || currentSource.itemType === 'state') {
    return t('app.selectPlaceholder')
  }
  const bounds = selectedSourceOption.value
  // Both bounds or no range: a domain is an enum or a complete interval, never one bound
  // (`device-template-schema.json` `oneOf`). Accepting either bound and printing the missing side as
  // an infinity advertised a domain the server refuses to store — see the note in
  // `utils/deviceRuntime.ts`, which owns this rule.
  if (bounds?.lowerBound !== undefined && bounds?.upperBound !== undefined) {
    if (isSetRelation.value) return t('app.conditionIntegerSetPlaceholder', { lower: bounds.lowerBound, upper: bounds.upperBound })
    return `${t('app.enterValuePlaceholder')} (${bounds.lowerBound} - ${bounds.upperBound})`
  }
  return t('app.enterValuePlaceholder')
})

const sourceDraftBlockedReason = computed(() => {
  if (!currentSource.fromId) return t('app.selectDevice')
  if (!currentSource.itemType) return t('app.selectType')
  if (currentSource.itemType !== 'state' && !selectedSourceOption.value) {
    return t(currentSource.itemType === 'api' ? 'app.selectAction' : 'app.selectProperty')
  }
  if (isValueBasedSourceType(currentSource.itemType)) {
    if (!currentSource.relation || !currentSource.value.trim()) {
      return t(currentValueOptions.value.length ? 'app.selectValue' : 'app.enterValue')
    }
    return sourceValueFailure.value
  }
  return ''
})
const canAddSource = computed(() => !sourceDraftBlockedReason.value)
const showSourceBlockedReason = computed(() => Boolean(sourceDraftBlockedReason.value)
  && (!ruleData.sources.length || Boolean(currentSource.fromId)))

const missingSourceDeviceIds = computed(() => Array.from(new Set(
  ruleData.sources
    .map(source => source.fromId)
    .filter(sourceId => sourceId && !resolveDeviceNode(sourceId))
)))

const ruleDraftIncompleteReasonKey = computed(() => {
  if (ruleData.sources.length === 0 || currentSource.fromId) {
    return 'app.addConfiguredRuleSourceBeforeSubmit'
  }
  if (missingSourceDeviceIds.value.length > 0) {
    return 'app.ruleSourceDeviceMissing'
  }
  if (ruleData.toId && !resolveDeviceNode(ruleData.toId)) {
    return 'app.ruleTargetDeviceMissing'
  }
  if (ruleData.contentDevice && !resolveDeviceNode(ruleData.contentDevice)) {
    return 'app.ruleContentDeviceMissing'
  }
  if (!ruleData.toId || !ruleData.toApi) {
    return 'app.selectRuleTargetBeforeSubmit'
  }
  if (Boolean(ruleData.contentDevice) !== Boolean(ruleData.content)) {
    return 'app.completeContentPayloadFields'
  }
  return ''
})

const ruleDraftIncompleteReason = computed(() =>
  ruleDraftIncompleteReasonKey.value ? t(ruleDraftIncompleteReasonKey.value) : ''
)

const ruleDraftDescriptionId = computed(() => {
  if (!ruleDraftIncompleteReason.value) return undefined
  return showSourceBlockedReason.value ? 'rule-source-readiness' : 'rule-draft-readiness'
})

const isRuleDraftComplete = computed(() => !ruleDraftIncompleteReasonKey.value)

const hasSourceValue = (value: unknown) =>
  value !== null && value !== undefined && value !== ''

const formatSourceValue = (value: unknown, fallback: string, templateName = '') =>
  hasSourceValue(value) ? formatTemplateModelToken(templateName, value) : fallback

// Reset dependents before restoring an existing condition's remaining fields.
watch(() => currentSource.fromId, () => {
  currentSource.itemType = ''
  currentSource.fromApi = ''
  currentSource.relation = '='
  currentSource.value = ''
  if (sourceItemTypeOptions.value.length === 1) {
    currentSource.itemType = sourceItemTypeOptions.value[0].value
  }
}, { flush: 'sync' })

watch(deviceNodes, nodes => {
  if (currentSource.fromId && !nodes.some(node => node.id === currentSource.fromId)) {
    currentSource.fromId = ''
  }
})

watch(sourceItemTypeOptions, options => {
  if (!currentSource.fromId) return
  if (currentSource.itemType && options.some(option => option.value === currentSource.itemType)) return
  currentSource.itemType = options.length === 1 ? options[0].value : ''
})

watch(() => currentSource.itemType, (newType) => {
  currentSource.fromApi = newType === 'state' ? 'state' : ''
  currentSource.relation = '='
  currentSource.value = ''
}, { flush: 'sync' })

watch(() => currentSource.fromApi, () => {
  if (currentSource.itemType !== 'api') {
    currentSource.value = ''
  }
}, { flush: 'sync' })

watch(conditionRelationOptions, options => {
  if (!options.some(option => option.value === currentSource.relation)) {
    currentSource.relation = options[0]?.value || '='
    currentSource.value = ''
  }
})

watch(() => currentSource.relation, () => {
  if (!isSetRelation.value && splitRuleValueList(currentSource.value).length > 1) {
    currentSource.value = splitRuleValueList(currentSource.value)[0] || ''
  }
}, { flush: 'sync' })

watch(() => ruleData.contentDevice, () => {
  if (!availableContentItems.value.some(item => item.name === ruleData.content)) {
    ruleData.content = ''
  }
})

watch(() => [ruleData.toId, ruleData.toApi, ...availableTargetApis.value], () => {
  if (ruleData.toApi && !availableTargetApis.value.includes(ruleData.toApi)) {
    ruleData.toApi = ''
  }
  if (!selectedTargetAcceptsContent.value) {
    ruleData.contentDevice = ''
    ruleData.content = ''
  }
})

// Rule preview
const rulePreview = computed(() => {
  if (!isRuleDraftComplete.value) {
    return null
  }

  return {
    description: buildReadableRuleName(),
    contentDevice: resolveDeviceNode(ruleData.contentDevice),
    content: ruleData.content
  }
})

// Methods
const clearSourceDraft = () => {
  editingSourceIndex.value = null
  currentSource.fromId = ''
  currentSource.itemType = ''
  currentSource.fromApi = ''
  currentSource.relation = '='
  currentSource.value = ''
}

const editSource = (index: number) => {
  const source = ruleData.sources[index]
  if (!source) return
  Object.assign(currentSource, {
    fromId: source.fromId,
    itemType: source.itemType,
    fromApi: source.fromApi,
    relation: source.relation || '=',
    value: source.value || ''
  })
  editingSourceIndex.value = index
  nextTick(() => document.getElementById('rule-source-device')?.focus())
}

const addSource = () => {
  // 添加源设备及其条件
  if (canAddSource.value && currentSource.itemType) {
    if (editingSourceIndex.value === null && ruleData.sources.length >= REQUEST_LIMITS.ruleConditions) {
      notifyBlocked(t('app.itemLimitReached', {
        resource: t('app.ruleConditions'),
        limit: REQUEST_LIMITS.ruleConditions
      }))
      return
    }
    const source = {
      fromId: currentSource.fromId,
      fromApi: currentSource.itemType === 'state' ? 'state' : currentSource.fromApi,
      itemType: currentSource.itemType,
      relation: currentSource.relation,
      value: currentSource.value
    }
    if (editingSourceIndex.value === null) ruleData.sources.push(source)
    else ruleData.sources.splice(editingSourceIndex.value, 1, source)

    clearSourceDraft()
  }
}

const removeSource = (index: number) => {
  ruleData.sources.splice(index, 1)
  if (editingSourceIndex.value === index) clearSourceDraft()
  else if (editingSourceIndex.value !== null && index < editingSourceIndex.value) editingSourceIndex.value -= 1
}

// No toast here: `ruleDraftIncompleteReason` is already rendered inline, linked by aria-describedby,
// and disables both submit buttons, so a toast would repeat text the user can see. The one path that
// can reach this with a stale draft (validateAfterAsyncCheck) raises its own distinct message.
const validateRuleDraft = () => isRuleDraftComplete.value

const buildRuleDraft = (id = ruleData.id): RuleForm => ({
  ...ruleData,
  id,
  sources: ruleData.sources.map(source => ({ ...source })),
  contentDevice: ruleData.contentDevice || undefined,
  content: ruleData.content || undefined
})

const ruleDraftFingerprint = () => JSON.stringify({
  draft: buildRuleDraft(),
  deviceIds: deviceNodes.value.map(node => `${node.id}:${node.templateName}`).sort()
})

const validateAfterAsyncCheck = (fingerprint: string) => {
  if (fingerprint !== ruleDraftFingerprint()) {
    notifyBlocked(t('app.ruleChangedDuringCheck'))
    return false
  }
  return validateRuleDraft()
}

const ruleNameInput = ref<HTMLInputElement | null>(null)
const savingRule = ref(false)
const ruleSaveFailure = ref<(ApiErrorDetails & {
  duplicateCheck?: Pick<DuplicateRuleCheckResult, 'matchedRule'>
}) | null>(null)
const ruleSaveFailureDetails = computed(() => {
  const failure = ruleSaveFailure.value
  if (!failure?.duplicateCheck) return failure
  const match = failure.duplicateCheck
  return {
    reasonCode: failure.reasonCode, details: failure.details, fields: failure.fields,
    cause: match.matchedRule
      ? t('app.identicalRuleNotSavedWithMatch', { rule: match.matchedRule })
      : t('app.identicalRuleNotSaved')
  }
})
const ruleSaveFailureElement = ref<HTMLElement | null>(null)
let ruleDraftEpoch = 0
const editingBaseline = ref<RuleForm | null>(null)
const cloneRule = (rule: RuleForm): RuleForm => ({ ...rule, sources: rule.sources.map(source => ({ ...source })) })

watch(ruleDraftFingerprint, () => { ruleSaveFailure.value = null })
watch(ruleSaveFailure, async failure => {
  if (!failure) return
  await nextTick()
  if (ruleSaveFailure.value !== failure || !props.active || props.readOnly) return
  ruleSaveFailureElement.value?.focus({ preventScroll: true })
  ruleSaveFailureElement.value?.scrollIntoView({ block: 'nearest' })
})

const emitRuleSave = async () => {
  if (savingRule.value) return
  ruleSaveFailure.value = null
  const actionEpoch = ruleActionEpoch
  const draftEpoch = ruleDraftEpoch
  const fingerprint = ruleDraftFingerprint()
  savingRule.value = true
  const rule: RuleForm = {
    ...ruleData,
    id: editingBaseline.value?.id || 'rule_' + Date.now(),
    name: ruleData.name || buildReadableRuleName(),
    sources: ruleData.sources.map(source => ({ ...source })),
    contentDevice: ruleData.contentDevice || undefined,
    content: ruleData.content || undefined
  }
  const saved = await new Promise<boolean>(resolve => {
    emit('save-rule', { rule, expected: editingBaseline.value ? cloneRule(editingBaseline.value) : undefined, complete: (saved, failure) => {
      if (isRuleActionCurrent(actionEpoch) && fingerprint === ruleDraftFingerprint()) {
        ruleSaveFailure.value = failure || null
      }
      resolve(saved)
    } })
  })
  savingRule.value = false
  // A successful save can open the inspector and hide this panel on narrow screens.
  // It still owns its submitted draft; visibility only retires prechecks and failure feedback.
  if (saved && draftEpoch === ruleDraftEpoch && fingerprint === ruleDraftFingerprint()) {
    if (editingBaseline.value) emit('finish-edit')
    resetDraft()
    await nextTick()
    if (props.active && !props.readOnly) ruleNameInput.value?.focus()
  }
}

const checkingDuplicate = ref(false)
const checkingSimilarity = ref(false)
const ruleActionBusy = computed(() =>
  checkingDuplicate.value || checkingSimilarity.value || savingRule.value
)
let ruleActionEpoch = 0

const beginRuleAction = (): number => {
  ruleSaveFailure.value = null
  return ++ruleActionEpoch
}
const isRuleActionCurrent = (actionEpoch: number): boolean =>
  actionEpoch === ruleActionEpoch && props.active && !props.readOnly

/**
 * Offers "save anyway" and re-checks the action epoch before honouring the answer.
 *
 * The re-check is the race guard: the editor can be hidden or reset
 * while the confirmation is on screen, and a stale "yes" must not save into the new draft. All four
 * check outcomes (duplicate-check failure, overlap, similarity-check failure, similarity) need
 * exactly this, so it lives in one place — four copies meant a fix to the guard had to be applied
 * four times.
 */
const confirmSaveAnyway = async (
  title: string,
  message: string,
  actionEpoch: number
): Promise<'save-anyway' | 'cancel'> => {
  const saveAnyway = await confirmChoice({
    title,
    message,
    confirmText: t('app.saveAnyway')
  })
  return saveAnyway && isRuleActionCurrent(actionEpoch) ? 'save-anyway' : 'cancel'
}

const invalidatePendingRuleAction = () => {
  const hadPendingCheck = checkingDuplicate.value || checkingSimilarity.value
  ruleActionEpoch += 1
  ruleSaveFailure.value = null
  checkingDuplicate.value = false
  checkingSimilarity.value = false
  if (hadPendingCheck) dismissOpenConfirmation()
}

watch(
  () => props.active && !props.readOnly,
  isActive => {
    if (!isActive) invalidatePendingRuleAction()
  },
  { flush: 'sync' }
)

const runDuplicateCheck = async (
  actionEpoch: number
): Promise<'clear' | 'save-anyway' | 'cancel'> => {
  checkingDuplicate.value = true
  try {
    let result
    try {
      result = await boardApi.checkDuplicateRule(buildRuleDraft())
    } catch (error: any) {
      if (!isRuleActionCurrent(actionEpoch)) return 'cancel'
      console.error('Duplicate check failed:', error)
      return confirmSaveAnyway(
        t('app.checkDuplicate'), t('app.duplicateCheckFailedCanStillSave'), actionEpoch)
    }

    if (!isRuleActionCurrent(actionEpoch)) return 'cancel'

    if (result.isDuplicate) {
      ruleSaveFailure.value = {
        reasonCode: 'DUPLICATE_RULE', details: [], fields: {},
        duplicateCheck: { matchedRule: result.matchedRule }
      }
      return 'cancel'
    }

    if (result.requiresReview) {
      const reason = t(duplicateRuleReasonKey(result.reasonCode))
      const message = result.matchedRule
        ? t('app.overlappingRuleMayExistWithMatch', {
            rule: result.matchedRule,
            reason
          })
        : t('app.overlappingRuleMayExist', { reason })

      return confirmSaveAnyway(t('app.overlappingRuleDetected'), message, actionEpoch)
    }

    return 'clear'
  } finally {
    if (actionEpoch === ruleActionEpoch) checkingDuplicate.value = false
  }
}

const runSimilarityCheck = async (
  actionEpoch: number
): Promise<'clear' | 'save-anyway' | 'cancel'> => {
  checkingSimilarity.value = true
  try {
    let result
    try {
      result = await boardApi.checkRuleSimilarity(buildRuleDraft())
    } catch (error: any) {
      if (!isRuleActionCurrent(actionEpoch)) return 'cancel'
      console.error('AI similarity check failed:', error)
      return confirmSaveAnyway(
        t('app.aiRuleSimilarityDetected'), t('app.aiSimilarityCheckFailedCanStillSave'), actionEpoch)
    }

    if (!isRuleActionCurrent(actionEpoch)) return 'cancel'

    if (result.requiresReview) {
      const reason = t(ruleSimilarityReasonKey(result.reasonCode))
      const message = result.matchedRule
        ? t('app.aiSimilarRuleMayExistWithMatch', {
            rule: result.matchedRule,
            reason
          })
        : t('app.aiSimilarRuleMayExist', { reason })

      return confirmSaveAnyway(t('app.aiRuleSimilarityDetected'), message, actionEpoch)
    }

    return 'clear'
  } finally {
    if (actionEpoch === ruleActionEpoch) checkingSimilarity.value = false
  }
}

const handleSave = async () => {
  if (!props.active || props.readOnly || ruleActionBusy.value || !validateRuleDraft()) return

  const actionEpoch = beginRuleAction()
  const fingerprint = ruleDraftFingerprint()
  // Creation checks include the existing rule itself; targeted updates validate duplicates server-side.
  if (editingBaseline.value) {
    await emitRuleSave()
    return
  }
  const duplicateResult = await runDuplicateCheck(actionEpoch)
  if (duplicateResult === 'cancel' || !isRuleActionCurrent(actionEpoch)) return
  if (!validateAfterAsyncCheck(fingerprint)) return

  await emitRuleSave()
}

const handleCheckSimilarity = async () => {
  if (!props.active || props.readOnly || ruleActionBusy.value || !validateRuleDraft()) return

  const actionEpoch = beginRuleAction()
  const fingerprint = ruleDraftFingerprint()
  const similarityResult = await runSimilarityCheck(actionEpoch)
  if (similarityResult === 'cancel' || !isRuleActionCurrent(actionEpoch)) return
  if (!validateAfterAsyncCheck(fingerprint)) return
  if (similarityResult === 'save-anyway') {
    await emitRuleSave()
  } else {
    notifySuccess(t('app.noSimilarRulesFound'))
  }
}

const resetDraft = () => {
  ruleDraftEpoch += 1
  invalidatePendingRuleAction()
  // Reset form
  ruleData.id = ''
  ruleData.name = ''
  ruleData.sources = []
  ruleData.toId = ''
  ruleData.toApi = ''
  ruleData.contentDevice = ''
  ruleData.content = ''
  clearSourceDraft()
}

const resetRuleDraft = () => {
  if (savingRule.value || props.readOnly) return
  if (editingBaseline.value) emit('finish-edit')
  editingBaseline.value = null
  resetDraft()
}

watch(() => props.editRule, rule => {
  resetDraft()
  editingBaseline.value = rule ? cloneRule(rule) : null
  if (rule) Object.assign(ruleData, cloneRule(rule))
}, { immediate: true, flush: 'sync' })

const { state: authState } = useAuth()
watch(() => [authState.token, authState.user?.userId], () => {
  editingBaseline.value = null
  resetDraft()
}, { flush: 'sync' })
onBeforeUnmount(() => {
  ruleDraftEpoch += 1
  invalidatePendingRuleAction()
})

// Helper functions for UI
function buildReadableRuleName(): string {
  const sources = ruleData.sources.map(source => {
    const deviceLabel = resolveDeviceNode(source.fromId)?.label || t('app.unknown')
    const itemLabel = formatRuleSourceName(source)
    if (isValueBasedSourceType(source.itemType)) {
      return `${deviceLabel}.${itemLabel} ${source.relation || '='} ${formatRuleSourceModelToken(source, source.value)}`.trim()
    }
    return `${deviceLabel} ${t('app.triggers')} ${itemLabel}`
  })
  const targetLabel = resolveDeviceNode(ruleData.toId)?.label || t('app.unknown')
  return t('app.ifThenDescription', {
    source: sources.join(` ${t('app.and')} `),
    target: targetLabel,
    action: formatTargetModelToken(ruleData.toApi)
  })
}

const getSourceTypeLabel = (type?: RuleSourceItemType) => {
  if (type === 'api') return t('app.actionEvent')
  if (type === 'variable') return t('app.variable')
  if (type === 'mode') return t('app.modes')
  if (type === 'state') return t('app.state')
  return t('app.actionEvent')
}

const sourceShowsRelationValue = (type?: RuleSourceItemType) =>
  isValueBasedSourceType(type)
</script>

<template>
  <div data-testid="rule-builder" class="rule-builder min-w-0">
    <fieldset :disabled="readOnly || savingRule" :aria-busy="savingRule" data-testid="rule-editor-fieldset"
      class="m-0 min-w-0 space-y-4 border-0 p-0">
      <!-- Rule Name Input -->
      <div class="space-y-1.5">
        <label for="rule-builder-name" class="board-form-label block">{{ t('app.ruleName') }}</label>
        <input
          id="rule-builder-name"
          ref="ruleNameInput"
          v-model="ruleData.name"
          type="text"
          :placeholder="t('app.ruleNamePlaceholder')"
          class="board-field"
        />
      </div>

      <!-- IF (Trigger) Section -->
      <section class="rule-builder-step space-y-3">
        <div class="flex items-center gap-3">
          <h3 class="board-section-title">{{ t('app.sourceDevices') }}</h3>
          <InfoTooltip
            :text="t('app.ruleLogicHint')"
            :label="t('app.showHelpFor', { topic: t('app.sourceDevices') })"
            test-id="rule-conditions-help"
          />
        </div>

        <!-- Add Source Form -->
        <div class="rule-builder-grid">
          <!-- 设备选择 -->
          <div class="space-y-1.5">
            <label for="rule-source-device" class="board-form-label block">{{ t('app.device') }}</label>
            <div class="min-w-0">
              <FieldSelect
                id="rule-source-device"
                v-model="currentSource.fromId"
                :disabled="deviceNodes.length === 0"
              >
                <option v-if="deviceNodes.length === 0" value="">{{ t('app.none') }}</option>
                <option v-else value="" disabled hidden>{{ t('app.selectPlaceholder') }}</option>
                <option v-for="node in deviceNodes" :key="node.id" :value="node.id">
                  {{ node.label }}
                </option>
              </FieldSelect>
            </div>
          </div>

          <!-- 类型选择 (API / Variable / Mode / State) -->
          <div class="space-y-1.5">
            <label for="rule-source-type" class="board-form-label block">{{ t('app.type') }}</label>
            <div class="min-w-0">
              <FieldSelect
                id="rule-source-type"
                v-model="currentSource.itemType"
                :disabled="!currentSource.fromId || sourceItemTypeOptions.length === 0"
              >
                <option v-if="!currentSource.fromId && deviceNodes.length > 0" value="">{{ t('app.selectPlaceholder') }}</option>
                <option v-else-if="sourceItemTypeOptions.length === 0" value="">{{ t('app.none') }}</option>
                <option v-else value="" disabled hidden>{{ t('app.selectPlaceholder') }}</option>
                <option
                  v-for="option in sourceItemTypeOptions"
                  :key="option.value"
                  :value="option.value"
                >
                  {{ option.label }}
                </option>
              </FieldSelect>
            </div>
          </div>

          <!-- Observable action-event selection. Only Signal=true APIs can trigger a rule. -->
          <div class="space-y-1.5" v-if="currentSource.itemType === 'api'">
            <label for="rule-source-api" class="board-form-label block">{{ t('app.actionEvent') }}</label>
            <div class="min-w-0">
              <FieldSelect
                id="rule-source-api"
                v-model="currentSource.fromApi"
                :disabled="filteredSourceItems.length === 0"
              >
                <option v-if="filteredSourceItems.length === 0" value="">{{ t('app.none') }}</option>
                <option v-else value="" disabled hidden>{{ t('app.selectPlaceholder') }}</option>
                <option v-for="item in filteredSourceItems" :key="item.name" :value="item.name">
                  {{ item.label || formatCurrentSourceModelToken(item.name) }}
                </option>
              </FieldSelect>
            </div>
          </div>

          <!-- Variable选择 - 仅选择 Variable 类型时显示 -->
          <div class="space-y-1.5" v-if="currentSource.itemType === 'variable'">
            <label for="rule-source-variable" class="board-form-label block">{{ t('app.variable') }}</label>
            <div class="min-w-0">
              <FieldSelect
                id="rule-source-variable"
                v-model="currentSource.fromApi"
                :disabled="filteredSourceItems.length === 0"
              >
                <option v-if="filteredSourceItems.length === 0" value="">{{ t('app.none') }}</option>
                <option v-else value="" disabled hidden>{{ t('app.selectPlaceholder') }}</option>
                <option v-for="item in filteredSourceItems" :key="item.name" :value="item.name">
                  {{ item.label || formatCurrentSourceModelToken(item.name) }}
                </option>
              </FieldSelect>
            </div>
          </div>

          <!-- Mode 选择 - 仅选择 Mode 类型时显示 -->
          <div class="space-y-1.5" v-if="currentSource.itemType === 'mode'">
            <label for="rule-source-mode" class="board-form-label block">{{ t('app.modes') }}</label>
            <div class="min-w-0">
              <FieldSelect
                id="rule-source-mode"
                v-model="currentSource.fromApi"
                :disabled="filteredSourceItems.length === 0"
              >
                <option v-if="filteredSourceItems.length === 0" value="">{{ t('app.none') }}</option>
                <option v-else value="" disabled hidden>{{ t('app.selectPlaceholder') }}</option>
                <option v-for="item in filteredSourceItems" :key="item.name" :value="item.name">
                  {{ formatCurrentSourceModelToken(item.name) }}
                </option>
              </FieldSelect>
            </div>
          </div>

          <!-- 条件选择 - 值条件类型显示 -->
          <div class="space-y-1.5" v-if="sourceShowsRelationValue(currentSource.itemType || undefined)">
            <label for="rule-source-condition" class="board-form-label block">{{ t('app.conditionOperator') }}</label>
            <div class="min-w-0">
              <FieldSelect
                id="rule-source-condition"
                v-model="currentSource.relation"
                :disabled="currentSource.itemType !== 'state' && !currentSource.fromApi"
              >
                <option v-for="rel in conditionRelationOptions" :key="rel.value" :value="rel.value">
                  {{ rel.label }}
                </option>
              </FieldSelect>
            </div>
          </div>

          <!-- 值输入 - 值条件类型显示 -->
          <div class="space-y-1.5" v-if="sourceShowsRelationValue(currentSource.itemType || undefined)">
            <label for="rule-source-value" class="board-form-label block">{{ t('app.value') }}</label>
            <div v-if="currentValueOptions.length > 0 && isSetRelation" class="min-w-0">
              <FieldSelect
                id="rule-source-value"
                v-model="currentSourceValueList"
                multiple
                size="4" class="min-h-[7.5rem]"
                :disabled="!currentSource.relation"
              >
                <option v-for="value in currentValueOptions" :key="value" :value="value">
                  {{ formatCurrentSourceModelToken(value) }}
                </option>
              </FieldSelect>
            </div>
            <div v-else-if="currentValueOptions.length > 0" class="min-w-0">
              <FieldSelect
                id="rule-source-value"
                v-model="currentSource.value"
                :disabled="!currentSource.relation"
              >
                <option value="" disabled hidden>{{ t('app.selectValue') }}</option>
                <option v-for="value in currentValueOptions" :key="value" :value="value">
                  {{ formatCurrentSourceModelToken(value) }}
                </option>
              </FieldSelect>
            </div>
            <input
              v-else
              id="rule-source-value"
              v-model="currentSource.value"
              type="text"
              :placeholder="currentValuePlaceholder"
              class="board-field"
              :aria-invalid="sourceValueFailure ? 'true' : undefined"
              :aria-describedby="sourceValueFailure ? 'rule-source-readiness' : undefined"
              :disabled="!currentSource.relation"
              @keydown.enter.prevent="addSource"
            />
          </div>
        </div>

        <p v-if="showSourceBlockedReason" id="rule-source-readiness" data-testid="rule-source-readiness"
          role="status" class="text-xs" :class="sourceValueFailure ? 'board-text-danger' : 'board-text-muted'">{{ sourceDraftBlockedReason }}</p>
        <div class="grid grid-cols-1 gap-2" :class="currentSource.fromId ? 'sm:grid-cols-2' : undefined">
          <button
            v-if="currentSource.fromId"
            type="button"
            @click="clearSourceDraft"
            data-testid="rule-clear-source"
            class="board-action-secondary min-w-0"
          >{{ t(editingSourceIndex !== null ? 'app.cancelEditing' : 'app.clearRuleSourceDraft') }}</button>
          <button
            type="button"
            @click="addSource"
            data-testid="rule-add-source"
            class="board-action-secondary w-full min-w-0"
            :disabled="!canAddSource"
            :aria-describedby="showSourceBlockedReason ? 'rule-source-readiness' : undefined"
          >
            <span class="material-icons-round text-lg" aria-hidden="true">{{ editingSourceIndex !== null ? 'check' : 'add_circle_outline' }}</span>
            {{ t(editingSourceIndex !== null ? 'app.saveChanges' : 'app.addSource') }}
          </button>
        </div>

        <ol v-if="ruleData.sources.length > 0" class="space-y-2" :aria-label="t('app.sourceDevices')">
          <li v-for="(source, index) in ruleData.sources" :key="index">
            <p v-if="index > 0" class="mb-2 text-center text-xs font-semibold board-text-muted">{{ t('app.and') }}</p>
            <div class="rule-builder-condition board-bg-elevated border board-border-subtle rounded-lg"
              :class="{ 'board-chip-danger': !resolveDeviceNode(source.fromId) }">
              <div class="min-w-0 space-y-1 px-3 py-2">
                <p class="break-words text-xs font-semibold board-text-strong">
                  {{ resolveDeviceNode(source.fromId)?.label || source.fromId }}
                  <span v-if="!resolveDeviceNode(source.fromId)" class="board-text-danger"> · {{ t('app.missingDevice') }}</span>
                </p>
                <p class="break-words text-xs leading-5 board-text-muted">
                  {{ formatRuleSourceName(source) }}
                  <template v-if="sourceShowsRelationValue(source.itemType)">
                    {{ source.relation || '=' }} {{ formatSourceValue(source.value, `(${t('app.anyValue')})`, resolveDeviceNode(source.fromId)?.templateName) }}
                  </template>
                  <span v-else class="board-text-info"> · {{ getSourceTypeLabel(source.itemType) }}</span>
                </p>
              </div>
              <button type="button" @click="editSource(index)" class="rule-builder-remove board-text-muted hover:board-control-hover"
                :data-testid="`rule-edit-source-${index}`" :aria-label="t('app.editConditionNumbered', { number: index + 1 })">
                <span class="material-icons-round text-lg" aria-hidden="true">edit</span>
              </button>
              <button type="button" @click="removeSource(index)" class="rule-builder-remove board-text-muted hover:board-text-danger hover:board-control-hover"
                :aria-label="t('app.remove')">
                <span class="material-icons-round text-lg" aria-hidden="true">close</span>
              </button>
            </div>
          </li>
        </ol>
      </section>


      <!-- THEN (Action) Section -->
      <section class="rule-builder-step space-y-3">
        <h3 class="board-section-title">
          {{ t('app.thenAction') }}
        </h3>

        <div class="rule-builder-target-grid">
          <div class="space-y-1.5">
            <label for="rule-target-device" class="board-form-label block">{{ t('app.device') }}</label>
            <div class="min-w-0">
              <FieldSelect
                id="rule-target-device"
                v-model="ruleData.toId"
                :disabled="deviceNodes.length === 0"
              >
                <option v-if="deviceNodes.length === 0" value="">{{ t('app.none') }}</option>
                <option v-else value="" disabled hidden>{{ t('app.selectDevicePlaceholder') }}</option>
                <option v-for="node in deviceNodes" :key="node.id" :value="node.id">
                  {{ node.label }}
                </option>
              </FieldSelect>
            </div>
          </div>

          <div class="space-y-1.5">
            <label for="rule-target-action" class="board-form-label block">{{ t('app.action') }}</label>
            <div class="min-w-0">
              <FieldSelect
                id="rule-target-action"
                v-model="ruleData.toApi"
                :disabled="!ruleData.toId || availableTargetApis.length === 0"
              >
                <option v-if="!ruleData.toId && deviceNodes.length > 0" value="" disabled hidden>{{ t('app.selectAction') }}</option>
                <option v-else-if="availableTargetApis.length === 0" value="">{{ t('app.none') }}</option>
                <option v-else value="" disabled hidden>{{ t('app.selectAction') }}</option>
                <option v-for="api in availableTargetApis" :key="api" :value="api">
                  {{ formatTargetModelToken(api) }}
                </option>
              </FieldSelect>
            </div>
          </div>
        </div>

        <div
          v-if="selectedTargetAcceptsContent && (contentCapableNodes.length > 0 || Boolean(ruleData.contentDevice))"
          class="rounded-lg border board-border-subtle board-bg-elevated p-3"
        >
          <div class="mb-3 flex items-start gap-2">
            <span class="material-icons-round text-sm board-text-success" aria-hidden="true">inventory_2</span>
            <div class="flex min-w-0 items-center gap-3">
              <p class="board-form-label">{{ t('app.contentPayload') }}</p>
              <InfoTooltip
                :text="t('app.contentPayloadHint')"
                :label="t('app.showHelpFor', { topic: t('app.contentPayload') })"
                test-id="rule-content-help"
              />
            </div>
          </div>

          <div class="rule-builder-target-grid">
            <div class="space-y-1.5">
              <label for="rule-content-device" class="board-form-label block">
                {{ t('app.contentSourceDevice') }}
              </label>
              <div class="min-w-0">
                <FieldSelect
                  id="rule-content-device"
                  v-model="ruleData.contentDevice"
                  data-testid="rule-content-device"
                >
                  <option value="">{{ t('app.noContentPayload') }}</option>
                  <option v-for="node in contentCapableNodes" :key="node.id" :value="node.id">
                    {{ node.label }}
                  </option>
                </FieldSelect>
              </div>
            </div>

            <div class="space-y-1.5">
              <label for="rule-content-name" class="board-form-label block">
                {{ t('app.contentItem') }}
              </label>
              <div class="min-w-0">
                <FieldSelect
                  id="rule-content-name"
                  v-model="ruleData.content"
                  data-testid="rule-content-name"
                  :disabled="!ruleData.contentDevice || availableContentItems.length === 0"
                >
                  <option v-if="!ruleData.contentDevice" value="">{{ t('app.selectContentDeviceFirst') }}</option>
                  <option v-else-if="availableContentItems.length === 0" value="">{{ t('app.none') }}</option>
                  <option v-else value="" disabled hidden>{{ t('app.selectContent') }}</option>
                  <option v-for="content in availableContentItems" :key="content.name" :value="content.name">
                    {{ t('app.contentItemWithSensitivity', { name: formatContentModelToken(content.name), privacy: t(`app.${content.privacy}`) }) }}
                  </option>
                </FieldSelect>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section v-if="rulePreview" class="rounded-lg board-bg-elevated border board-border-subtle p-3" data-testid="rule-preview">
        <h3 class="board-form-label mb-2">{{ t('app.rulePreview') }}</h3>
        <p class="break-words text-xs leading-6 board-text-strong">{{ rulePreview.description }}</p>
        <p v-if="rulePreview.contentDevice && rulePreview.content" class="mt-2 break-words text-xs leading-5 board-text-muted">
          {{ t('app.copyFrom') }} {{ rulePreview.contentDevice.label }}.{{ formatContentModelToken(rulePreview.content) }}
        </p>
      </section>

      <div class="rule-builder-actions space-y-3">
        <div v-if="ruleSaveFailureDetails" ref="ruleSaveFailureElement" data-testid="rule-save-failure"
          role="alert" tabindex="-1" class="text-left text-xs">
          <p class="font-semibold board-text-danger">{{ t(ruleSaveFailureDetails.reasonCode === 'DUPLICATE_RULE'
            ? 'app.identicalRuleNotSaved' : 'app.saveRulesFailed') }}</p>
          <FailureDetails v-bind="ruleSaveFailureDetails" :message="t(ruleSaveFailureDetails.reasonCode === 'DUPLICATE_RULE' ? 'app.identicalRuleNotSaved' : 'app.saveRulesFailed')" :suggestion="ruleSaveFailureDetails.reasonCode === 'DUPLICATE_RULE'
            ? t('app.ruleSaveDuplicateHint') : ruleSaveFailureDetails.reasonCode === 'RULE_EDIT_STALE' ? t('app.editConflictRecoveryHint') : ''" />
        </div>
        <p v-if="ruleDraftIncompleteReason && ruleDraftDescriptionId === 'rule-draft-readiness'" id="rule-draft-readiness" data-testid="rule-draft-readiness"
          role="status" class="text-xs leading-5 board-text-muted">{{ ruleDraftIncompleteReason }}</p>
        <div class="grid gap-2" :class="editingBaseline ? 'grid-cols-1' : 'grid-cols-2'">
          <button type="button" @click="resetRuleDraft" data-testid="rule-reset"
            class="board-action-secondary min-w-0">{{ t(editingBaseline ? 'app.cancelEditing' : 'app.resetRuleDraft') }}</button>
          <button v-if="!editingBaseline" type="button" @click="handleCheckSimilarity" data-testid="rule-check-duplicate"
            :disabled="!isRuleDraftComplete || ruleActionBusy"
            :aria-describedby="ruleDraftDescriptionId"
            class="board-action-secondary min-w-0">
            {{ checkingSimilarity ? t('app.checkingAiSimilarity') : t('app.aiSimilarityCheck') }}
          </button>
        </div>
        <button type="button" @click="handleSave" data-testid="rule-save"
          :disabled="!isRuleDraftComplete || ruleActionBusy"
          :aria-describedby="ruleDraftDescriptionId"
          class="board-panel-submit board-action-disarmed">
          <span class="material-symbols-outlined text-lg" aria-hidden="true">{{ editingBaseline ? 'save' : 'add' }}</span>
          {{ savingRule ? t('app.saving') : t(editingBaseline ? 'app.saveChanges' : 'app.createRule') }}
        </button>
      </div>
    </fieldset>
  </div>
</template>

<style scoped>
.rule-builder-step + .rule-builder-step,
.rule-builder-actions {
  border-top: 1px solid var(--border);
  padding-top: var(--iot-space-lg);
}

.rule-builder-grid,
.rule-builder-target-grid {
  display: grid;
  align-items: end;
  gap: var(--iot-space-md);
  grid-template-columns: minmax(0, 1fr);
}

.rule-builder-condition {
  display: grid;
  grid-template-columns: minmax(0, 1fr) repeat(2, var(--iot-target-min));
  align-items: center;
  overflow-wrap: anywhere;
}

.rule-builder-remove {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: var(--iot-target-min);
  border-radius: var(--iot-radius-action);
}

.rule-builder-remove:focus-visible {
  outline: 2px solid var(--accent-border);
  outline-offset: 2px;
}

</style>
