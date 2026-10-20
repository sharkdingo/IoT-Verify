<script setup lang="ts">
import FieldSelect from '@/components/common/FieldSelect.vue'
import HintTooltip from '@/components/common/HintTooltip.vue'
import RuleBuilder from '@/components/RuleBuilder.vue'
import deviceImportJsonTemplateUrl from '@/assets/examples/device-list-template.json?url&no-inline'
import deviceImportCsvTemplateUrl from '@/assets/examples/device-list-template.csv?url&no-inline'
import type { RuleForm } from '@/types/rule'
import { ref, reactive, computed, nextTick, onBeforeUnmount, onMounted, useAttrs, watch } from 'vue'
import { 
  specTemplateDetails, 
  relationOperators, 
  targetTypes 
} from '@/assets/config/specTemplates.ts'
import type { 
  SpecTemplateId, 
  SpecTemplateType, 
  SpecCondition, 
  SpecSide,
} from '@/types/spec'
import type { DeviceTemplate, InternalVariable } from '@/types/device'
import { useI18n } from 'vue-i18n'
import { getDeviceIconUrl } from '@/utils/device'
import {
  PRIVACY_OPTIONS,
  TRUST_OPTIONS,
  buildDeviceRuntimeConfig,
  createDeviceRuntimeDraft,
  findTemplateStatePrivacy,
  findTemplateStateTrust,
  getTemplateEnvironmentVariables,
  getTemplateLocalVariables,
  getTemplateVariableDefaultValue,
  templateVariableIsStateDerived,
  syncStateDerivedVariables,
  getTemplateWorkingStates,
  materializeDeviceRuntimeConfig,
  resetDeviceRuntimeDraft,
  templateVariableHasEnumValues,
  templateVariableUsesNumericBounds,
  validateDeviceRuntimeConfig,
  validateTemplateVariableValue,
  validateNumericConditionValue,
  type DeviceRuntimeConfig
} from '@/utils/deviceRuntime'
import { describeSpecification, specConditionPropertyLabel, specConditionValueLabel } from '@/utils/modelPresentation'
import { buildSpecFormula, buildSpecFormulaPreview, isSpecConditionVariableSourceUnresolved } from '@/utils/spec'
import {
  mergeSourcedEnvironmentPatches,
  type EnvironmentPatchConflict
} from '@/utils/deviceImportEnvironment'
import boardApi, {
  BOARD_RESPONSE_INCOMPLETE_CODE,
  parseDeviceTemplateDeletionPreview,
  type DefaultTemplateResetResult,
  type DeviceTemplateDeletionResult,
  type EnvironmentVariableChange
} from '@/api/board'
import type { ModelEnvironmentVariable } from '@/types/model'
import type { ModelTokenSource } from '@/types/modelToken'
import { deviceLabelKey, reserveUniqueDeviceLabel } from '@/utils/canvas/nodeCreate'
import { errorDetails, localizedErrorMessage } from '@/utils/userMessage'
import { diagnoseJsonParseError } from '@/utils/jsonParseDiagnostics'
import { DEVICE_IMPORT_TEMPLATE_ALIASES, DEVICE_IMPORT_NAME_ALIASES, parseDeviceImportCsv,
  decodeDeviceImportFile, DeviceImportEncodingError, type DeviceImportEncoding,
  type DeviceImportInputRow } from '@/utils/deviceListImport'
import { useModalAccessibility } from '@/composables/useModalAccessibility'
import { REQUEST_LIMITS } from '@/constants/requestLimits'
import { COLLAPSED_PANEL_RAIL_CSS } from '@/constants/boardLayout'
import { formatBuiltInModelToken } from '@/utils/modelTokenDisplay'
import { dismissCompletionNotifications, notifyBlocked, notifyError, notifySuccess } from '@/utils/feedback'
import { useRovingTablist } from '@/composables/useRovingTablist'
import InfoTooltip from '@/components/common/InfoTooltip.vue'
import FailureDetails from '@/components/FailureDetails.vue'
import { useAuth } from '@/stores/auth'
import type { ApiErrorDetails } from '@/utils/userMessage'

defineOptions({ inheritAttrs: false })
const attrs = useAttrs()

// Element-Plus typings vary by version; we use an `any` alias to keep runtime behavior (e.g. `center`) without TS errors.
const { t, locale } = useI18n()
const { state: authState, getToken, isCurrentSessionToken } = useAuth()

const targetTypeLabelKeys: Record<string, string> = {
  state: 'app.state',
  mode: 'app.modes',
  variable: 'app.variable',
  api: 'app.actionEvent',
  trust: 'app.trust',
  privacy: 'app.privacy'
}

const localizedTargetTypes = computed(() =>
  targetTypes
    .filter(type => specForm.templateId !== '7' || !['trust', 'privacy'].includes(type.value))
    .map(type => ({
    ...type,
    label: targetTypeLabelKeys[type.value]
      ? t(targetTypeLabelKeys[type.value])
      : type.label
  }))
)

// Props
interface Props {
  deviceTemplates?: any[]
  nodes?: any[]
  collapsed?: boolean
  width?: number
  activeSection?: string
  templatesLoading?: boolean
  readOnly?: boolean
  readOnlyMessage?: string
  runBoardMutation?: <T>(work: () => Promise<T>) => Promise<T>
}

const props = withDefaults(defineProps<Props>(), {
  deviceTemplates: () => [],
  nodes: () => [],
  width: 320,
  // Vue otherwise casts an absent Boolean prop to false and treats it as controlled.
  collapsed: undefined,
  templatesLoading: false,
  readOnly: false
})

const ensureWritable = (): boolean => {
  if (!props.readOnly) return true
  notifyBlocked(props.readOnlyMessage || t('app.playbackReadOnlyCloseFirst'))
  return false
}

const mutationTitle = (fallback: string): string =>
  props.readOnly ? (props.readOnlyMessage || t('app.playbackReadOnlyCloseFirst')) : fallback

let readOnlyEpoch = 0

const runBoardMutation = <T,>(work: () => Promise<T>): Promise<T> =>
  props.runBoardMutation ? props.runBoardMutation(work) : work()

type ControlCenterSection = 'devices' | 'templates' | 'rules' | 'specs'
type DeviceCreateMode = 'single' | 'batch' | 'import'

type DeviceCreateItem = {
  template: DeviceTemplate
  customName: string
  runtime?: DeviceRuntimeConfig
}

type MutationCompletion = (saved: boolean, failure?: ApiErrorDetails) => void

type DeviceCreateItemsPayload = {
  items: DeviceCreateItem[]
  environmentVariables?: ModelEnvironmentVariable[]
  complete: MutationCompletion
}

const emit = defineEmits<{
  'create-device': [data: DeviceCreateItem & { complete: MutationCompletion }]
  'create-devices': [data: DeviceCreateItemsPayload]
  'template-drag-start': [templateName: string]
  'template-drag-end': []
  'save-rule': [request: { rule: RuleForm; complete: MutationCompletion }]
  'add-spec': [data: {
    templateId: string,
    templateType: string,
    devices: Array<{deviceId: string, deviceLabel: string, selectedApis: string[]}>,
    formula: string,
    aConditions: SpecCondition[],
    ifConditions: SpecCondition[],
    thenConditions: SpecCondition[],
    complete: MutationCompletion
  }]
  'replace-template-catalog': [templates: DeviceTemplate[]]
  'replace-template-state': [data: {
    templates: DeviceTemplate[]
    environmentVariables: ModelEnvironmentVariable[]
  }]
  'edit-history-cleared': []
  'authoritative-state-unavailable': [keys: Array<'templates' | 'environment'>]
  'update:collapsed': [value: boolean]
  'update:active-section': [value: ControlCenterSection]
}>()

const deviceNodes = computed(() => props.nodes || [])

// Device form data
const deviceForm = reactive({
  name: '',
  type: '',
  id: 'AUTO'
})

const singleDeviceRuntime = reactive(createDeviceRuntimeDraft())
const creatingSingleDevice = ref(false)
const singleDeviceSaveFailure = ref<ApiErrorDetails | null>(null)
const singleDeviceSaveFailureElement = ref<HTMLElement | null>(null)
const singleDeviceSaveActionElement = ref<HTMLElement | null>(null)
let singleDeviceSaveVersion = 0
watch([deviceForm, singleDeviceRuntime], () => {
  singleDeviceSaveVersion += 1
  singleDeviceSaveFailure.value = null
}, { deep: true, flush: 'sync' })
const creatingMultipleDevices = ref(false)
const creatingSpecification = ref(false)

const deviceCreateMode = ref<DeviceCreateMode>('single')
watch(deviceCreateMode, () => {
  singleDeviceSaveVersion += 1
  singleDeviceSaveFailure.value = null
}, { flush: 'sync' })
const batchDeviceForm = reactive({
  type: '',
  prefix: '',
  count: 3
})
type DeviceImportFormat = 'json' | 'csv'
const deviceImportFormat = ref<DeviceImportFormat>('json')
const deviceImportSource = ref<'file' | 'paste'>('file')
const deviceImportCsvEncoding = ref<DeviceImportEncoding>('utf-8')
const deviceImportFormats = {
  json: { label: 'JSON', extension: '.json', templateUrl: deviceImportJsonTemplateUrl,
    summaryKey: 'app.deviceImportJsonSummary', helpKey: 'app.deviceImportJsonHint' },
  csv: { label: 'CSV', extension: '.csv', templateUrl: deviceImportCsvTemplateUrl,
    summaryKey: 'app.deviceImportCsvSummary', helpKey: 'app.deviceImportCsvHint' }
} as const
const selectedDeviceImportFormat = computed(() => deviceImportFormats[deviceImportFormat.value])
const deviceImportDrafts = reactive({
  json: { file: '', paste: '', fileName: '' },
  csv: { file: '', paste: '', fileName: '' }
})
const selectedDeviceImportDraft = computed(() => deviceImportDrafts[deviceImportFormat.value])
const importDeviceText = computed({
  get: () => selectedDeviceImportDraft.value[deviceImportSource.value],
  set: (text: string) => { selectedDeviceImportDraft.value[deviceImportSource.value] = text }
})
const deviceImportFileFailure = ref<ApiErrorDetails | 'encoding' | null>(null)
const deviceImportFileInput = ref<HTMLInputElement | null>(null)
let deviceImportReadVersion = 0

// Debounced import text for performance optimization (avoids re-parsing on every keystroke)
const debouncedImportText = ref('')
let importTextDebounceTimer: ReturnType<typeof setTimeout> | null = null
const IMPORT_TEXT_DEBOUNCE_MS = 300

watch([deviceImportFormat, importDeviceText, deviceImportCsvEncoding, deviceImportSource], ([format, newText, encoding, source], previous) => {
  deviceImportReadVersion += 1
  deviceImportFileFailure.value = null
  if (importTextDebounceTimer) {
    clearTimeout(importTextDebounceTimer)
    importTextDebounceTimer = null
  }
  // Restore the active source immediately; abandoned file reads cannot replace another draft.
  if (format !== previous[0] || encoding !== previous[2] || source !== previous[3]) {
    debouncedImportText.value = newText
    return
  }
  importTextDebounceTimer = setTimeout(() => {
    debouncedImportText.value = newText
  }, IMPORT_TEXT_DEBOUNCE_MS)
}, { immediate: true, flush: 'sync' })

/**
 * Replace the import text and its parsed view in one tick.
 *
 * The debounce above exists to avoid re-parsing on every keystroke, which is a property of *typing*.
 * A file selection is one discrete event, and deferring it opened a 300ms window in which the preview,
 * the validity counts, and the create button all still described the PREVIOUS content — while the
 * button's `:disabled` was already false from that content. Choosing a CSV and clicking Create inside
 * that window re-imported the earlier JSON payload instead: measured in E2E as two extra
 * `import_phone_1`/`import_alarm_1` devices where the CSV's own devices were expected.
 */
const setImportTextImmediately = (text: string) => {
  importDeviceText.value = text
  if (importTextDebounceTimer) {
    clearTimeout(importTextDebounceTimer)
    importTextDebounceTimer = null
  }
  debouncedImportText.value = text
}

const clearDeviceImportFile = () => {
  deviceImportReadVersion += 1
  deviceImportFileFailure.value = null
  selectedDeviceImportDraft.value.fileName = ''
  setImportTextImmediately('')
}

onBeforeUnmount(() => {
  if (importTextDebounceTimer) {
    clearTimeout(importTextDebounceTimer)
  }
})

const MAX_BATCH_DEVICE_COUNT = 50

const normalizeName = (value: unknown) => String(value ?? '').trim()

const existingDeviceLabels = computed(() =>
  new Set(deviceNodes.value.map((node: any) => deviceLabelKey(node.label)).filter(Boolean))
)

const singleDeviceNameConflict = computed(() => {
  const key = deviceLabelKey(deviceForm.name)
  return Boolean(key && existingDeviceLabels.value.has(key))
})

const getUniqueDeviceName = (baseName: string, reserved: Set<string>) => {
  return reserveUniqueDeviceLabel(baseName, reserved)
}

const findTemplateByName = (name: string) => {
  const target = normalizeName(name).toLowerCase()
  if (!target) return null
  return props.deviceTemplates.find((tpl: any) =>
    [tpl.name, tpl.manifest?.Name]
      .some(candidate => normalizeName(candidate).toLowerCase() === target)
  ) ?? null
}

const selectedDeviceTemplate = computed<DeviceTemplate | null>(() =>
  findTemplateByName(deviceForm.type) as DeviceTemplate | null
)

const selectedDeviceManifest = computed(() => selectedDeviceTemplate.value?.manifest ?? null)

const selectedWorkingStates = computed(() => getTemplateWorkingStates(selectedDeviceTemplate.value))
const selectedInternalVariables = computed(() => getTemplateLocalVariables(selectedDeviceTemplate.value))
const singleDeviceValueFailures = computed(() => Object.fromEntries(selectedInternalVariables.value.flatMap(variable => {
  const value = String(singleDeviceRuntime.variables[variable.Name] ?? '').trim()
  if (!value || templateVariableIsStateDerived(selectedDeviceTemplate.value, variable.Name)) return []
  const reason = validateTemplateVariableValue(variable, value, t)
  return reason ? [[variable.Name, reason]] : []
})))

const singleDeviceBlockedReason = computed(() => {
  if (props.templatesLoading) return t('app.loadingDeviceTemplates')
  if (!selectedDeviceTemplate.value) return t('app.selectDeviceTemplate')
  if (!deviceForm.name.trim()) return t('app.enterDeviceName')
  if (singleDeviceNameConflict.value) return t('app.deviceNameAlreadyExists')
  if (Object.keys(singleDeviceValueFailures.value).length) return t('app.correctDeviceInitialValues')
  return ''
})

const revealSingleDeviceValueFailure = async (event: Event, name: string) => {
  const input = event.currentTarget as HTMLInputElement
  await nextTick()
  if (document.activeElement !== input || !singleDeviceValueFailures.value[name]) return
  // Focusing a field near the panel edge can leave its newly rendered error clipped below it.
  input.closest('label')?.querySelector<HTMLElement>('[role="alert"]')?.scrollIntoView({ block: 'nearest' })
}

const selectedTemplateHasModes = computed(() => {
  const manifest = selectedDeviceManifest.value
  return Array.isArray(manifest?.Modes)
    && manifest.Modes.length > 0
    && selectedWorkingStates.value.length > 0
})

const hasSingleDeviceRuntimeFields = computed(() =>
  Boolean(selectedDeviceTemplate.value && (selectedTemplateHasModes.value || selectedInternalVariables.value.length > 0))
)

const resetSingleDeviceRuntime = () => {
  resetDeviceRuntimeDraft(singleDeviceRuntime, selectedDeviceTemplate.value)
}

watch(() => deviceForm.type, resetSingleDeviceRuntime)

const variableInputPlaceholder = (variable: InternalVariable) => {
  if (templateVariableUsesNumericBounds(variable)) {
    const lower = variable.LowerBound ?? '-∞'
    const upper = variable.UpperBound ?? '∞'
    const defaultValue = getTemplateVariableDefaultValue(variable)
    return defaultValue
      ? `${t('app.useTemplateDefaultWithValue', { value: defaultValue })} / ${lower} - ${upper}`
      : `${lower} - ${upper}`
  }
  return t('app.enterValuePlaceholder')
}

const buildRuntimeConfig = (template: DeviceTemplate, runtime = singleDeviceRuntime): DeviceRuntimeConfig | undefined => {
  return buildDeviceRuntimeConfig(template, runtime, { variableScope: 'local' })
}

type DeviceImportRow = {
  source: string
  templateName: string
  name: string
  customName?: string
  template?: any
  runtime?: DeviceRuntimeConfig
  environmentVariables?: ModelEnvironmentVariable[]
  error?: string
  warning?: string
  diagnostics?: ApiErrorDetails
}

const MAX_DEVICE_IMPORT_BYTES = 4 * 1024 * 1024
const MAX_TEMPLATE_IMPORT_BYTES = 512 * 1024
const DEVICE_IMPORT_JSON_KEYS = new Set([
  ...DEVICE_IMPORT_TEMPLATE_ALIASES, ...DEVICE_IMPORT_NAME_ALIASES,
  'state', 'currentStateTrust', 'currentStatePrivacy', 'variables', 'privacies'
])
const DEVICE_IMPORT_VARIABLE_KEYS = new Set(['name', 'value', 'trust'])
const DEVICE_IMPORT_PRIVACY_KEYS = new Set(['name', 'privacy'])

const findUnknownImportField = (value: Record<string, unknown>, allowed: Set<string>) =>
  Object.keys(value).find(key => !allowed.has(key))

const makeImportError = (source: string, error: string): DeviceImportRow => ({
  source,
  templateName: '',
  name: '',
  customName: '',
  template: null,
  error
})

const parseJsonImportString = (
  value: unknown,
  source: string,
  field: string
): { value: string; error?: string } => {
  if (value === undefined || value === null) return { value: '' }
  if (typeof value !== 'string') {
    return {
      value: '',
      error: t('app.deviceImportJsonStringRequired', { source, field })
    }
  }
  return { value: value.trim() }
}

const readJsonImportAlias = (
  item: Record<string, unknown>,
  aliases: readonly string[],
  source: string
): { value: string; error?: string } => {
  const supplied = aliases.filter(alias => Object.prototype.hasOwnProperty.call(item, alias))
  if (supplied.length > 1) {
    return {
      value: '',
      error: t('app.deviceImportAliasConflict', { source, fields: supplied.join(', ') })
    }
  }
  const field = supplied[0]
  return field ? parseJsonImportString(item[field], source, field) : { value: '' }
}

const parseRuntimeList = (
  value: unknown,
  source: string,
  kind: 'variables' | 'privacies'
): { items?: any[]; error?: string } => {
  if (value === undefined || value === null) return { items: undefined }
  if (!Array.isArray(value)) {
    return { error: t('app.deviceImportRuntimeListRequired', { source, field: kind }) }
  }
  return { items: value }
}

const parseImportedRuntime = (item: any, source: string): { runtime?: DeviceRuntimeConfig; error?: string } => {
  const runtime: DeviceRuntimeConfig = {}

  if (item.state !== undefined && item.state !== null) {
    const parsed = parseJsonImportString(item.state, source, 'state')
    if (parsed.error) return { error: parsed.error }
    runtime.state = parsed.value
    if (!runtime.state) return { error: t('app.deviceImportRuntimeScalarRequired', { source, field: 'state' }) }
  }
  if (item.currentStateTrust !== undefined && item.currentStateTrust !== null) {
    const parsed = parseJsonImportString(item.currentStateTrust, source, 'currentStateTrust')
    if (parsed.error) return { error: parsed.error }
    runtime.currentStateTrust = parsed.value.toLowerCase()
    if (!runtime.currentStateTrust) {
      return { error: t('app.deviceImportRuntimeScalarRequired', { source, field: 'currentStateTrust' }) }
    }
  }
  if (item.currentStatePrivacy !== undefined && item.currentStatePrivacy !== null) {
    const parsed = parseJsonImportString(item.currentStatePrivacy, source, 'currentStatePrivacy')
    if (parsed.error) return { error: parsed.error }
    runtime.currentStatePrivacy = parsed.value.toLowerCase()
    if (!runtime.currentStatePrivacy) {
      return { error: t('app.deviceImportRuntimeScalarRequired', { source, field: 'currentStatePrivacy' }) }
    }
  }

  const variableList = parseRuntimeList(item.variables, source, 'variables')
  if (variableList.error) return { error: variableList.error }
  if (variableList.items) {
    runtime.variables = []
    for (const [index, variable] of variableList.items.entries()) {
      if (!variable || typeof variable !== 'object' || Array.isArray(variable)) {
        return { error: t('app.deviceImportRuntimeObjectRequired', { source, field: `variables[${index}]` }) }
      }
      const unknownField = findUnknownImportField(variable, DEVICE_IMPORT_VARIABLE_KEYS)
      if (unknownField) {
        return { error: t('app.deviceImportUnknownField', {
          source,
          field: `variables[${index}].${unknownField}`
        }) }
      }
      const parsedName = parseJsonImportString(variable.name, source, `variables[${index}].name`)
      const parsedValue = parseJsonImportString(variable.value, source, `variables[${index}].value`)
      const parsedTrust = parseJsonImportString(variable.trust, source, `variables[${index}].trust`)
      if (parsedName.error || parsedValue.error || parsedTrust.error) {
        return { error: parsedName.error || parsedValue.error || parsedTrust.error }
      }
      const name = parsedName.value
      const value = parsedValue.value
      const trust = parsedTrust.value.toLowerCase()
      if (!name || !value) {
        if (deviceImportFormat.value === 'csv' && name) {
          return { error: t('app.deviceImportCsvVariableValueRequired', { name }) }
        }
        return { error: t('app.deviceImportRuntimeNameValueRequired', { source, field: `variables[${index}]` }) }
      }
      if (variable.trust !== undefined && variable.trust !== null && !trust) {
        return { error: t('app.deviceImportRuntimeScalarRequired', {
          source,
          field: `variables[${index}].trust`
        }) }
      }
      runtime.variables.push({
        name,
        value,
        ...(trust ? { trust } : {})
      })
    }
  }

  const privacyList = parseRuntimeList(item.privacies, source, 'privacies')
  if (privacyList.error) return { error: privacyList.error }
  if (privacyList.items) {
    runtime.privacies = []
    for (const [index, privacy] of privacyList.items.entries()) {
      if (!privacy || typeof privacy !== 'object' || Array.isArray(privacy)) {
        return { error: t('app.deviceImportRuntimeObjectRequired', { source, field: `privacies[${index}]` }) }
      }
      const unknownField = findUnknownImportField(privacy, DEVICE_IMPORT_PRIVACY_KEYS)
      if (unknownField) {
        return { error: t('app.deviceImportUnknownField', {
          source,
          field: `privacies[${index}].${unknownField}`
        }) }
      }
      const parsedName = parseJsonImportString(privacy.name, source, `privacies[${index}].name`)
      const parsedPrivacy = parseJsonImportString(privacy.privacy, source, `privacies[${index}].privacy`)
      if (parsedName.error || parsedPrivacy.error) {
        return { error: parsedName.error || parsedPrivacy.error }
      }
      const name = parsedName.value
      const value = parsedPrivacy.value.toLowerCase()
      if (!name || !value) {
        return { error: t('app.deviceImportRuntimeNameValueRequired', { source, field: `privacies[${index}]` }) }
      }
      runtime.privacies.push({ name, privacy: value })
    }
  }

  return Object.keys(runtime).length > 0 ? { runtime } : {}
}

const splitImportedRuntime = (
  template: DeviceTemplate,
  runtime?: DeviceRuntimeConfig
): { runtime?: DeviceRuntimeConfig; environmentVariables?: ModelEnvironmentVariable[]; error?: string } => {
  const validationError = validateDeviceRuntimeConfig(template, runtime, t, { variableScope: 'all' })
  if (validationError) return { error: validationError }
  const effectiveNodeRuntime = materializeDeviceRuntimeConfig(
    template,
    runtime,
    { variableScope: 'local' }
  )

  const environmentNames = new Set(getTemplateEnvironmentVariables(template).map(variable => variable.Name))
  const environmentByName = new Map<string, ModelEnvironmentVariable>()

  for (const variable of runtime?.variables || []) {
    if (!environmentNames.has(variable.name)) continue
    environmentByName.set(variable.name, {
      ...(environmentByName.get(variable.name) || { name: variable.name }),
      name: variable.name,
      value: variable.value,
      trust: variable.trust
    })
  }

  for (const privacy of runtime?.privacies || []) {
    if (!environmentNames.has(privacy.name)) continue
    environmentByName.set(privacy.name, {
      ...(environmentByName.get(privacy.name) || { name: privacy.name }),
      name: privacy.name,
      privacy: privacy.privacy
    })
  }

  return {
    runtime: effectiveNodeRuntime,
    environmentVariables: Array.from(environmentByName.values())
  }
}

// Template search and filter
const templateSearchQuery = ref('')
const templateFilterType = ref('all')

// Filtered templates based on search and type
const filteredTemplates = computed(() => {
  let templates = props.deviceTemplates

  // Filter by search query
  if (templateSearchQuery.value.trim()) {
    const query = templateSearchQuery.value.toLowerCase()
    templates = templates.filter((t: any) => {
      const name = t.manifest?.Name || t.name
      const desc = t.manifest?.Description || ''
      return name.toLowerCase().includes(query) || desc.toLowerCase().includes(query)
    })
  }

  // Filter by type (if not 'all')
  if (templateFilterType.value !== 'all') {
    templates = templates.filter((t: any) => {
      const name = t.manifest?.Name || t.name
      return name === templateFilterType.value
    })
  }

  return templates
})

const isDefaultTemplate = (template: any) => template?.defaultTemplate === true

const formatTemplateModelToken = (template: any, value: unknown) => {
  const raw = value === null || value === undefined ? '' : String(value)
  return isDefaultTemplate(template) ? formatBuiltInModelToken(raw, t) : raw
}

const defaultTemplates = computed(() =>
  filteredTemplates.value.filter((template: any) => isDefaultTemplate(template))
)

const customTemplates = computed(() =>
  filteredTemplates.value.filter((template: any) => !isDefaultTemplate(template))
)

const templateGroups = computed(() => [
  {
    key: 'default',
    label: t('app.defaultTemplates'),
    templates: defaultTemplates.value
  },
  {
    key: 'custom',
    label: t('app.customTemplates'),
    templates: customTemplates.value
  }
])

const pinnedTemplatePreviewId = ref<string | number | null>(null)
const templatePreviewRef = ref<HTMLElement | null>(null)
let templatePreviewOpener: HTMLElement | null = null
const templatePreviewPoint = reactive({ x: 24, y: 24 })
const templatePreviewViewport = reactive({ width: 1440, height: 900 })

const TEMPLATE_PREVIEW_WIDTH = 320
const TEMPLATE_PREVIEW_MAX_HEIGHT = 460
const TEMPLATE_PREVIEW_OFFSET = 16
const TEMPLATE_PREVIEW_MARGIN = 12

const getTemplateKey = (template: any) =>
  template?.id ?? template?.manifest?.Name ?? template?.name ?? ''

const getTemplateName = (template: any) =>
  template?.manifest?.Name || template?.name || t('app.unknown')

const getTemplateDescription = (template: any) =>
  template?.manifest?.Description?.trim() || ''

const getTemplateList = (template: any, field: string, nameField = 'Name') => {
  const value = template?.manifest?.[field]
  if (!Array.isArray(value)) return []
  return value
    .map((item: any) => typeof item === 'string' ? item : item?.[nameField])
    .filter((item: unknown): item is string => typeof item === 'string' && item.trim().length > 0)
}

const getTemplateInitState = (template: any) => {
  const initState = template?.manifest?.InitState
  return initState ? formatTemplateModelToken(template, initState) : t('app.none')
}

// Picking a state re-derives the variables it constrains, so this editor cannot submit a pair the
// writers refuse. Same rule as the device dialog, through the same helper.
watch(() => singleDeviceRuntime.state, state => {
  if (!state) return
  syncStateDerivedVariables(singleDeviceRuntime.variables, selectedDeviceTemplate.value, state)
})

const getTemplateTransitionCount = (template: any) =>
  Array.isArray(template?.manifest?.Transitions) ? template.manifest.Transitions.length : 0

const getTemplatePreviewSections = (template: any) => [
  { key: 'modes', label: t('app.modes'), items: getTemplateList(template, 'Modes').map(item => formatTemplateModelToken(template, item)) },
  { key: 'states', label: t('app.workingStates'), items: getTemplateList(template, 'WorkingStates').map(item => formatTemplateModelToken(template, item)) },
  { key: 'variables', label: t('app.variables'), items: getTemplateList(template, 'InternalVariables').map(item => formatTemplateModelToken(template, item)) },
  { key: 'apis', label: t('app.deviceApis'), items: getTemplateList(template, 'APIs').map(item => formatTemplateModelToken(template, item)) }
]

// Precomputed formatted values for v-for loops (optimization to avoid repeated function calls)
const formattedSelectedWorkingStates = computed(() =>
  selectedWorkingStates.value.map(state => ({
    name: state.Name,
    label: formatTemplateModelToken(selectedDeviceTemplate.value, state.Name)
  }))
)

const formattedSelectedInternalVariables = computed(() =>
  selectedInternalVariables.value.map(variable => ({
    ...variable,
    formattedName: formatTemplateModelToken(selectedDeviceTemplate.value, variable.Name),
    formattedDefaultValue: formatTemplateModelToken(
      selectedDeviceTemplate.value,
      getTemplateVariableDefaultValue(variable, selectedDeviceTemplate.value, singleDeviceRuntime.state)
    ),
    formattedValues: variable.Values
      ? variable.Values.map((val: unknown) => ({
          raw: String(val),
          formatted: formatTemplateModelToken(selectedDeviceTemplate.value, val)
        }))
      : []
  }))
)

const activeTemplatePreview = computed(() => {
  const key = pinnedTemplatePreviewId.value
  if (key === null) return null
  return props.deviceTemplates.find((template: any) => getTemplateKey(template) === key) ?? null
})

// Precomputed template preview sections (optimization)
const activeTemplatePreviewSections = computed(() => {
  const template = activeTemplatePreview.value
  if (!template) return []
  return getTemplatePreviewSections(template)
})

const templatePreviewStyle = computed(() => {
  const width = Math.min(TEMPLATE_PREVIEW_WIDTH, Math.max(240, templatePreviewViewport.width - (TEMPLATE_PREVIEW_MARGIN * 2)))
  const maxHeight = Math.min(TEMPLATE_PREVIEW_MAX_HEIGHT, Math.max(220, templatePreviewViewport.height - (TEMPLATE_PREVIEW_MARGIN * 2)))
  const roomOnRight = templatePreviewPoint.x + TEMPLATE_PREVIEW_OFFSET + width <= templatePreviewViewport.width - TEMPLATE_PREVIEW_MARGIN
  const left = roomOnRight
    ? templatePreviewPoint.x + TEMPLATE_PREVIEW_OFFSET
    : Math.max(TEMPLATE_PREVIEW_MARGIN, templatePreviewPoint.x - TEMPLATE_PREVIEW_OFFSET - width)
  const maxTop = Math.max(TEMPLATE_PREVIEW_MARGIN, templatePreviewViewport.height - maxHeight - TEMPLATE_PREVIEW_MARGIN)
  const top = Math.min(Math.max(TEMPLATE_PREVIEW_MARGIN, templatePreviewPoint.y - 10), maxTop)

  return {
    left: `${Math.round(left)}px`,
    top: `${Math.round(top)}px`,
    width: `${Math.round(width)}px`,
    maxHeight: `${Math.round(maxHeight)}px`
  }
})

const syncTemplatePreviewViewport = () => {
  if (typeof window === 'undefined') return
  templatePreviewViewport.width = window.innerWidth
  templatePreviewViewport.height = window.innerHeight
}

const updateTemplatePreviewPosition = (event?: MouseEvent | FocusEvent | KeyboardEvent) => {
  syncTemplatePreviewViewport()

  if (event && 'clientX' in event && event.clientX > 0 && event.clientY > 0) {
    templatePreviewPoint.x = event.clientX
    templatePreviewPoint.y = event.clientY
    return
  }

  const target = event?.currentTarget
  if (target instanceof HTMLElement) {
    const rect = target.getBoundingClientRect()
    templatePreviewPoint.x = rect.right
    templatePreviewPoint.y = rect.top + Math.min(rect.height / 2, 24)
  }
}

const toggleTemplatePreview = (template: any, event?: MouseEvent | KeyboardEvent) => {
  const key = getTemplateKey(template)
  if (pinnedTemplatePreviewId.value === key) {
    closeTemplatePreview()
    return
  }
  templatePreviewOpener = event?.currentTarget instanceof HTMLElement ? event.currentTarget : null
  updateTemplatePreviewPosition(event)
  pinnedTemplatePreviewId.value = key
  void nextTick(() => templatePreviewRef.value?.focus({ preventScroll: true }))
}

const closeTemplatePreview = () => {
  pinnedTemplatePreviewId.value = null
  const opener = templatePreviewOpener
  templatePreviewOpener = null
  if (!isCollapsed.value && activeSection.value === 'templates') {
    void nextTick(() => { if (opener?.isConnected) opener.focus({ preventScroll: true }) })
  }
}

const isTemplatePreviewVisible = (template: any) => {
  return pinnedTemplatePreviewId.value === getTemplateKey(template)
}

onMounted(() => {
  syncTemplatePreviewViewport()
  window.addEventListener('resize', syncTemplatePreviewViewport)
})

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('resize', syncTemplatePreviewViewport)
  }
})

// Device types - dynamically loaded from backend device templates
const deviceTypes = computed(() => {
  // Only use templates loaded from backend
  return props.deviceTemplates
    .map((tpl: any) => tpl.manifest?.Name || tpl.name)
    .filter((name: string) => name) // Remove empty names
})

const isTemplateSelectorDisabled = computed(() => props.templatesLoading || deviceTypes.value.length === 0)
const templateSelectorTitle = computed(() => {
  if (props.templatesLoading) return t('app.loadingDeviceTemplates')
  return deviceForm.type || (deviceTypes.value.length === 0 ? t('app.none') : t('app.selectDeviceTemplate'))
})

const batchDeviceCountError = computed(() => {
  const count = Number(batchDeviceForm.count)
  return Number.isInteger(count) && count >= 1 && count <= MAX_BATCH_DEVICE_COUNT
    ? ''
    : t('app.integerBetween', {
        field: t('app.count'),
        min: 1,
        max: MAX_BATCH_DEVICE_COUNT
      })
})

const batchDeviceCount = computed(() => batchDeviceCountError.value
  ? null
  : Number(batchDeviceForm.count))

const batchDevicePreview = computed(() => {
  const template = findTemplateByName(batchDeviceForm.type)
  const prefix = normalizeName(batchDeviceForm.prefix)
  const count = batchDeviceCount.value
  if (!template || !prefix || count === null) return []

  const reserved = new Set(existingDeviceLabels.value)
  return Array.from({ length: count }, (_, index) => ({
    template,
    customName: getUniqueDeviceName(`${prefix}${index + 1}`, reserved)
  }))
})

const parsedImportedDevices = computed<DeviceImportRow[]>(() => {
  const text = debouncedImportText.value.trim()
  if (!text) return []

  try {
    let inputRows: DeviceImportInputRow[]
    if (deviceImportFormat.value === 'json') {
      let parsed: unknown
      try {
        parsed = JSON.parse(debouncedImportText.value)
      } catch (error) {
        const diagnostic = diagnoseJsonParseError(debouncedImportText.value, error)
        const message = [t('app.deviceImportInvalidJson'),
          ...(diagnostic.line !== undefined && diagnostic.column !== undefined
            ? [t('app.jsonSyntaxLocation', { line: diagnostic.line, column: diagnostic.column })] : [])].join(' ')
        return [{ ...makeImportError('#1', message), diagnostics: {
          cause: diagnostic.reason, details: [diagnostic.reason], fields: {}
        } }]
      }
      inputRows = (Array.isArray(parsed) ? parsed : [parsed]).map((item, index) => ({ source: `#${index + 1}`, item }))
    } else inputRows = parseDeviceImportCsv(debouncedImportText.value, t)

    const rows: DeviceImportRow[] = inputRows.map(input => {
      const { source, item } = input
      if (input.error !== undefined) return makeImportError(source, input.error)
      if (!item || typeof item !== 'object' || Array.isArray(item)) {
        return makeImportError(source, t('app.deviceImportJsonObjectRequired'))
      }
      const record = item as Record<string, unknown>
      const unknownField = findUnknownImportField(record, DEVICE_IMPORT_JSON_KEYS)
      if (unknownField) return makeImportError(source, t('app.deviceImportUnknownField', { source, field: unknownField }))
      const templateResult = readJsonImportAlias(record, DEVICE_IMPORT_TEMPLATE_ALIASES, source)
      if (templateResult.error) return makeImportError(source, templateResult.error)
      const nameResult = readJsonImportAlias(record, DEVICE_IMPORT_NAME_ALIASES, source)
      if (nameResult.error) return makeImportError(source, nameResult.error)
      const runtimeResult = parseImportedRuntime(record, source)
      if (runtimeResult.error) return { ...makeImportError(source, runtimeResult.error),
        templateName: templateResult.value, name: nameResult.value }
      return { source, templateName: templateResult.value, name: nameResult.value, runtime: runtimeResult.runtime }
    })

    const reserved = new Set(existingDeviceLabels.value)
    return rows.map(row => {
      if (row.error) return row
      const template = findTemplateByName(row.templateName)
      const missingTemplateName = !row.templateName
      const missingName = !row.name
      const missingTemplate = !template
      const runtimeResult = template
        ? splitImportedRuntime(template as DeviceTemplate, row.runtime)
        : {}
      const runtimeError = runtimeResult.error || ''
      const error = missingTemplateName
        ? t('app.deviceImportTemplateNameMissing')
        : missingName
          ? t('app.deviceImportNameMissing')
          : missingTemplate
            ? t('app.deviceImportTemplateMissing', { template: row.templateName })
            : runtimeError
      const customName = error ? row.name : getUniqueDeviceName(row.name, reserved)
      const warning = !error && customName !== row.name
        ? t('app.deviceImportNameAutoRenamed', { from: row.name, to: customName })
        : ''

      return {
        ...row,
        template,
        customName,
        runtime: runtimeResult.runtime,
        environmentVariables: runtimeResult.environmentVariables,
        error,
        warning
      }
    })
  } catch (error: any) {
    return [makeImportError('#1', localizedErrorMessage(error, t('app.invalidJsonFile'), locale.value))]
  }
})

const deviceImportSyntaxFailure = computed(() =>
  parsedImportedDevices.value.find(item => item.diagnostics) || null
)

const validImportedDevices = computed(() =>
  parsedImportedDevices.value.filter((item: any) => !item.error && item.template && item.customName)
)

/**
 * The preview describes text the user has already replaced.
 *
 * Everything downstream of the import box — the preview list, the validity count, the create button's
 * own label — is derived from `debouncedImportText`, which trails the textarea by
 * `IMPORT_TEXT_DEBOUNCE_MS`. The create button was gated on the *count* alone, so during that window it
 * was enabled and armed with the previous content: paste or choose a second payload, click inside
 * 300ms, and the earlier one was imported instead.
 *
 * Gating here rather than at each entry point is deliberate. `setImportTextImmediately` fixes the file
 * path, but the same window is reachable by typing, by paste, and by anything added later; this makes
 * the button's enabled state mean "ready for what is currently in the box" for all of them.
 */
const importPreviewStale = computed(() =>
  debouncedImportText.value !== importDeviceText.value
)

const importedEnvironmentMerge = computed(() => mergeSourcedEnvironmentPatches(
  validImportedDevices.value.map((item: any) => ({
    source: item.source,
    patches: item.environmentVariables || []
  }))
))

const environmentConflictFieldLabel = (field: EnvironmentPatchConflict['field']) => {
  if (field === 'value') return t('app.variableValue')
  if (field === 'trust') return t('app.sourceLabel')
  return t('app.sensitivityLabel')
}

const deviceImportSourceLabel = (source: string) => t(
  deviceImportFormat.value === 'csv' ? 'app.deviceImportCsvRow' : 'app.deviceImportJsonEntry',
  { row: source.slice(1) }
)

const formatImportedEnvironmentConflict = (conflict: EnvironmentPatchConflict) =>
  t('app.deviceImportEnvironmentConflict', {
    name: conflict.name,
    field: environmentConflictFieldLabel(conflict.field),
    first: deviceImportSourceLabel(conflict.firstSource),
    second: deviceImportSourceLabel(conflict.secondSource)
  })

const importedDevicesHaveErrors = computed(() =>
  parsedImportedDevices.value.some((item: any) => item.error)
  || importedEnvironmentMerge.value.conflicts.length > 0
)

const getTemplateIconUrl = (template: DeviceTemplate): string => {
  const name = template.manifest?.Name || template.name
  const initState = template.manifest?.InitState || 'Working'
  return getDeviceIconUrl(name, initState, template.manifest)
}

// Specification form data
const specForm = reactive({
  templateId: '' as SpecTemplateId | '',
  templateType: '' as SpecTemplateType | '',
  selectedDevices: [] as Array<{deviceId: string, deviceLabel: string, selectedApis: string[]}>,
  formula: '',
  aConditions: [] as unknown as SpecCondition[],
  ifConditions: [] as unknown as SpecCondition[],
  thenConditions: [] as unknown as SpecCondition[]
})
const specSaveFailure = ref<ApiErrorDetails | null>(null)
const hasSpecificationConditions = computed(() =>
  specForm.aConditions.length + specForm.ifConditions.length + specForm.thenConditions.length > 0)
let specSaveVersion = 0
watch(specForm, () => {
  specSaveVersion += 1
  specSaveFailure.value = null
}, { deep: true, flush: 'sync' })

// Specification dialog state
const showSpecDialog = ref(false)
const editingConditionIndex = ref(-1)
const editingConditionSide = ref<SpecSide>('a')

const closeSpecDialog = () => {
  showSpecDialog.value = false
}

const {
  setDialogRef: setSpecDialogRef,
  handleModalKeydown: handleSpecDialogKeydown
} = useModalAccessibility(showSpecDialog, closeSpecDialog)

// Editing condition data
const editingConditionData = reactive<Partial<SpecCondition>>({
  id: '',
  side: 'a',
  deviceId: '',
  deviceLabel: '',
  targetType: 'state',
  key: '',
  propertyScope: undefined,
  variableSource: undefined,
  relation: '=',
  value: ''
})

// Dialog states
const showDeleteConfirmDialog = ref(false)
const templateToDelete = ref<any>(null)
const templateDeletePreview = ref<DeviceTemplateDeletionResult | null>(null)
const isLoadingTemplateDeletePreview = ref(false)
const isDeletingTemplate = ref(false)
const showResetDefaultsConfirmDialog = ref(false)
const isResettingDefaultTemplates = ref(false)
const isLoadingDefaultTemplateResetPreview = ref(false)
const defaultTemplateResetPreview = ref<DefaultTemplateResetResult | null>(null)

// Get current template details
const currentTemplateDetail = computed(() => {
  if (!specForm.templateId) return null
  return specTemplateDetails.find(t => t.id === specForm.templateId)
})

const templateMessage = (key: string | undefined, fallback: string) => key ? t(key) : fallback

// Get required sides for current template
const requiredSides = computed(() => {
  return currentTemplateDetail.value?.requiredSides || []
})

// Check if a side is required for current template
const isSideRequired = (side: SpecSide) => {
  return requiredSides.value.includes(side)
}

// Get conditions for a specific side
const getConditionsForSide = (side: SpecSide): SpecCondition[] => {
  switch (side) {
    case 'a': return specForm.aConditions
    case 'if': return specForm.ifConditions
    case 'then': return specForm.thenConditions
    default: return []
  }
}

// Generate unique ID for condition
const generateConditionId = () => {
  return `cond-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
}

// Open condition dialog for adding/editing
const openConditionDialog = (side: SpecSide, index: number = -1) => {
  if (!ensureWritable()) return
  if (creatingSpecification.value) return
  if (index < 0 && getConditionsForSide(side).length >= REQUEST_LIMITS.specificationConditions) {
    notifyBlocked(t('app.itemLimitReached', {
      resource: t('app.specificationConditions'),
      limit: REQUEST_LIMITS.specificationConditions
    }))
    return
  }
  editingConditionSide.value = side
  editingConditionIndex.value = index
  
  if (index >= 0) {
    // Edit existing condition
    const conditions = getConditionsForSide(side)
    const condition = conditions[index]
    Object.assign(editingConditionData, { propertyScope: undefined, variableSource: undefined }, condition)
  } else {
    // Add new condition - reset to defaults
    Object.assign(editingConditionData, {
      id: generateConditionId(),
      side,
      deviceId: '',
      deviceLabel: '',
      targetType: 'state',
      key: '',
      propertyScope: undefined,
      variableSource: undefined,
      relation: '=',
      value: ''
    })
  }
  showSpecDialog.value = true
}

/**
 * Why the condition cannot be saved yet, or `null` when it is valid. Drives both the submit
 * button's disabled state and the inline message, so the two can never disagree — and so the
 * reason appears next to the form instead of in a toast the user must read before it fades.
 */
const specConditionBlockedReason = computed<string | null>(() => {
  const draft = editingConditionData
  if (!draft.deviceId) return t('app.selectDevice')
  if (isSpecConditionDeviceMissing(draft.deviceId)) return t('app.specConditionDeviceMissing', { count: 1 })
  if (!draft.targetType) return t('app.selectType')
  if (draft.targetType !== 'state' && !draft.key?.trim()) return t('app.selectProperty')
  if ((draft.targetType === 'trust' || draft.targetType === 'privacy')
    && !['state', 'variable'].includes(draft.propertyScope || '')) {
    return t('app.selectProperty')
  }
  // Never defaulted: the two readings differ when a device is compromised, so presenting either as
  // the author's intent is the defect this choice exists to fix.
  if (draft.targetType === 'variable'
    && draft.variableSource !== 'environment' && draft.variableSource !== 'reported') {
    return t('app.specVariableSourceRequired')
  }
  // An API condition only records that the API was called, so it carries no value.
  if (draft.targetType !== 'api' && !draft.value?.trim()) {
    return t(conditionValueOptions.value.length ? 'app.selectValue' : 'app.enterValue')
  }
  if (draft.targetType === 'variable' && selectedConditionVariable.value) {
    return validateNumericConditionValue(selectedConditionVariable.value, draft.value || '', draft.relation || '=', t) || null
  }
  return null
})

// Save condition from dialog
const saveCondition = () => {
  if (!ensureWritable()) return
  // Reported inline by `specConditionBlockedReason`, which also disables the submit button.
  if (specConditionBlockedReason.value) return
  const deviceId = editingConditionData.deviceId
  if (!deviceId) return

  // key 不能为空，否则后端验证会失败。
  // 对于 full-state 条件，key 固定为 state；mode/variable/api/trust/privacy 都必须选择具体属性。
  // A blank key is already reported inline above, so there is no toast here.
  const keyValue = editingConditionData.targetType === 'state'
    ? 'state'
    : (editingConditionData.key || '').trim()

  const device = deviceNodes.value.find(n => n.id === editingConditionData.deviceId)

  // API 类型使用 'TRUE' 作为默认 value（表示 API 被调用）
  // 因为后端 @NotBlank 要求 value 不能为空
  const finalValue = editingConditionData.targetType === 'api'
    ? 'TRUE'
    : (editingConditionData.value || '')

  const condition: SpecCondition = {
    id: editingConditionData.id || generateConditionId(),
    side: editingConditionSide.value,
    deviceId,
    deviceLabel: device?.label || deviceId,
    targetType: editingConditionData.targetType || 'state',
    key: keyValue,
    ...((editingConditionData.targetType === 'trust' || editingConditionData.targetType === 'privacy')
      ? { propertyScope: editingConditionData.propertyScope as 'state' | 'variable' }
      : {}),
    // Present only on a variable condition, and only because the author picked it — the blocked
    // reason above refuses to save without one.
    ...(editingConditionData.targetType === 'variable'
      ? { variableSource: editingConditionData.variableSource }
      : {}),
    relation: editingConditionData.relation || '=',
    value: finalValue
  }
  
  switch (editingConditionSide.value) {
    case 'a':
      if (editingConditionIndex.value >= 0) {
        specForm.aConditions[editingConditionIndex.value] = condition
      } else {
        specForm.aConditions.push(condition)
      }
      break
    case 'if':
      if (editingConditionIndex.value >= 0) {
        specForm.ifConditions[editingConditionIndex.value] = condition
      } else {
        specForm.ifConditions.push(condition)
      }
      break
    case 'then':
      if (editingConditionIndex.value >= 0) {
        specForm.thenConditions[editingConditionIndex.value] = condition
      } else {
        specForm.thenConditions.push(condition)
      }
      break
  }
  
  showSpecDialog.value = false
  updateFormula()
}

// Remove condition
const removeCondition = (side: SpecSide, index: number) => {
  if (!ensureWritable()) return
  switch (side) {
    case 'a':
      specForm.aConditions.splice(index, 1)
      break
    case 'if':
      specForm.ifConditions.splice(index, 1)
      break
    case 'then':
      specForm.thenConditions.splice(index, 1)
      break
  }
  updateFormula()
}

// Get device display name
const getDeviceLabel = (deviceId: string) => {
  const device = deviceNodes.value.find(n => n.id === deviceId)
  // A deleted device and a device with no label are different problems, and only the first is the
  // user's to fix, so they must not render identically.
  if (!device) return t('app.deletedModelItem')
  return device.label || t('app.unknownModelItem')
}

// Get device template
const getDeviceTemplate = (deviceId: string) => {
  const device = deviceNodes.value.find(n => n.id === deviceId)
  if (!device) return null

  const target = normalizeName(device.templateName).toLowerCase()
  const template = props.deviceTemplates.find(t =>
    [t.name, t.manifest?.Name]
      .some(candidate => normalizeName(candidate).toLowerCase() === target)
  )

  return template || null
}

const getDeviceManifestForCondition = (deviceId: string) => {
  return getDeviceTemplate(deviceId)?.manifest || null
}

// Get available keys based on target type for a device
const encodePropertySelection = (propertyScope: 'state' | 'variable', key: string) =>
  JSON.stringify([propertyScope, key])

const decodePropertySelection = (value: string): { propertyScope: 'state' | 'variable'; key: string } | null => {
  try {
    const parsed = JSON.parse(value)
    if (Array.isArray(parsed) && ['state', 'variable'].includes(parsed[0]) && typeof parsed[1] === 'string') {
      return { propertyScope: parsed[0], key: parsed[1] }
    }
  } catch {
    // Ordinary condition keys are not encoded.
  }
  return null
}

/**
 * A `variable` condition asks one of two different questions, and the author must say which. The
 * shared-pool answer ("did this happen in the home") and the device's own answer ("what this device
 * reported") only diverge when the device is compromised — which is exactly the case a
 * specification exists to catch — so neither is preselected for a shared variable.
 *
 * A device-local variable has no value in the home at all, so only the device's own answer exists
 * and it is selected automatically: there is nothing to choose between.
 */
const editingConditionVariableIsDeviceLocal = computed(() => {
  if (editingConditionData.targetType !== 'variable' || !editingConditionData.key) return false
  const manifest = getDeviceManifestForCondition(editingConditionData.deviceId || '')
  const variable = Array.isArray(manifest?.InternalVariables)
    ? manifest.InternalVariables.find((item: any) => item?.Name === editingConditionData.key)
    : null
  return variable?.IsInside === true
})

/**
 * Whether the selected reading can actually diverge in a run that models compromise.
 *
 * The general help text explains that the two readings differ "once a device is compromised", which is
 * true but leaves the user to answer the only question that decides their choice — can *this* device
 * falsify *this* reading? That is a declared manifest fact, already the predicate behind the attack-surface
 * count, and already one property access away on the variable resolved above. Without it the choice looks
 * arbitrary exactly when it is inert, and unremarkable exactly when it matters.
 */
const editingConditionVariableIsFalsifiable = computed(() => {
  if (editingConditionData.targetType !== 'variable' || !editingConditionData.key) return false
  const manifest = getDeviceManifestForCondition(editingConditionData.deviceId || '')
  const variable = Array.isArray(manifest?.InternalVariables)
    ? manifest.InternalVariables.find((item: any) => item?.Name === editingConditionData.key)
    : null
  return variable?.FalsifiableWhenCompromised === true
})

const editingConditionVariableSourceOptions = computed<Array<{ value: 'environment' | 'reported', label: string }>>(() => {
  if (editingConditionData.targetType !== 'variable' || !editingConditionData.key) return []
  const reported = {
    value: 'reported' as const,
    label: t('app.specVariableSourceReported', { device: getDeviceLabel(editingConditionData.deviceId || '') })
  }
  if (editingConditionVariableIsDeviceLocal.value) return [reported]
  return [{ value: 'environment' as const, label: t('app.specVariableSourceEnvironment') }, reported]
})

// Only the no-alternative case is auto-filled. A stale pick is cleared rather than carried over,
// so switching to a device-local variable cannot leave `environment` selected — the backend refuses
// that combination, and the author never chose it for this variable.
watch(editingConditionVariableSourceOptions, options => {
  if (options.length === 1) {
    editingConditionData.variableSource = options[0].value
    return
  }
  if (!options.some(option => option.value === editingConditionData.variableSource)) {
    editingConditionData.variableSource = undefined
  }
}, { immediate: true })

const conditionKeySelection = computed({
  get: () => {
    if ((editingConditionData.targetType === 'trust' || editingConditionData.targetType === 'privacy')
      && editingConditionData.propertyScope && editingConditionData.key) {
      return encodePropertySelection(editingConditionData.propertyScope, editingConditionData.key)
    }
    return editingConditionData.key || ''
  },
  set: (value: string) => {
    if (editingConditionData.targetType === 'trust' || editingConditionData.targetType === 'privacy') {
      const parsed = decodePropertySelection(value)
      editingConditionData.propertyScope = parsed?.propertyScope
      editingConditionData.key = parsed?.key || ''
      return
    }
    editingConditionData.propertyScope = undefined
    editingConditionData.key = value
  }
})

const getAvailableKeys = (deviceId: string, targetType: string): Array<{label: string, value: string}> => {
  const template = getDeviceTemplate(deviceId)
  if (!template || !template.manifest) return []

  const keys: Array<{label: string, value: string}> = []

  const variableScopeLabel = (variable: any) =>
    variable?.IsInside === true ? t('app.internalVariable') : t('app.environmentVariable')

  // Template InternalVariables includes both device-local and shared environment variables.
  // An affect-only shared declaration (Reads=false) is excluded: the generator emits no
  // `device.name := a_name` mirror for it, so a specification condition on it would compare a value
  // this device never observes. The backend refuses it at persist time and again before generation;
  // offering it here would only let the user build something that gets rejected later.
  if (targetType === 'variable' && template.manifest.InternalVariables) {
    template.manifest.InternalVariables.forEach((v: any) => {
      if (v.IsInside !== true && v.Reads === false) return
      keys.push({ label: `${formatTemplateModelToken(template, v.Name)} (${variableScopeLabel(v)})`, value: v.Name })
    })
  }

  if (targetType === 'state' && template.manifest.WorkingStates) {
    template.manifest.WorkingStates.forEach((s: any) => {
      keys.push({ label: formatTemplateModelToken(template, s.Name), value: s.Name })
    })
  }

  if (targetType === 'mode' && template.manifest.Modes) {
    template.manifest.Modes.forEach((mode: string) => {
      keys.push({ label: formatTemplateModelToken(template, mode), value: mode })
    })
  }

  if (targetType === 'api' && template.manifest.APIs) {
    template.manifest.APIs
      .filter((api: any) => {
        const isSignal = api.Signal === true || api.signal === true
        if (!isSignal) return false
        if (specForm.templateId !== '7') return true
        return Boolean(String(api.EndState ?? api.endState ?? '').trim())
      })
      .forEach((api: any) => {
      keys.push({ label: formatTemplateModelToken(template, api.Name), value: api.Name })
    })
  }

  // Trust/privacy state targets refer to the currently active state in a mode.
  // Generated mode_state names remain an internal NuSMV detail.
  if (targetType === 'trust' || targetType === 'privacy') {
    const seenPropertyKeys = new Set<string>()
    const addPropertyKey = (label: string, propertyScope: 'state' | 'variable', key: string) => {
      const value = encodePropertySelection(propertyScope, key)
      if (!value || seenPropertyKeys.has(value)) return
      seenPropertyKeys.add(value)
      keys.push({ label, value })
    }
    const modes = Array.isArray(template.manifest.Modes) ? template.manifest.Modes : []
    modes.forEach((mode: string) => addPropertyKey(
      modes.length === 1
        ? t('app.currentStateProperty')
        : t('app.currentModeStateProperty', { mode: formatTemplateModelToken(template, mode) }),
      'state',
      mode
    ))
    if (template.manifest.InternalVariables) {
      template.manifest.InternalVariables.forEach((v: any) => {
        addPropertyKey(`${formatTemplateModelToken(template, v.Name)} (${variableScopeLabel(v)})`, 'variable', v.Name)
      })
    }
  }

  return keys
}

const specificationPresentationContext = () => ({
  t,
  resolveDevice: (id: string) => deviceNodes.value.find(node => node.id === id),
  formatToken: (id: string, value: unknown) => formatTemplateModelToken(getDeviceTemplate(id), value),
  modeCount: (id: string) => getDeviceManifestForCondition(id)?.Modes?.length || 0
})

const formatConditionPropertyLabel = (condition: Pick<SpecCondition, 'deviceId' | 'targetType' | 'key' | 'propertyScope'>) => {
  const property = specConditionPropertyLabel(condition, specificationPresentationContext())
  return condition.targetType === 'trust' || condition.targetType === 'privacy'
    ? `${property} ${t(targetTypeLabelKeys[condition.targetType])}`
    : property
}

/**
 * A short badge naming which value a stored variable condition asks about, so a saved row states it
 * rather than leaving the reader to guess. An older condition with no recorded choice reads as
 * unresolved; the run is blocked until it is re-edited.
 */
const formatConditionVariableSourceLabel = (
  condition: Pick<SpecCondition, 'targetType' | 'variableSource' | 'deviceId'>
): string | null => {
  if (condition.targetType !== 'variable') return null
  if (condition.variableSource === 'environment') return t('app.specVariableSourceEnvironmentShort')
  if (condition.variableSource === 'reported') return t('app.specVariableSourceReportedShort')
  return t('app.specVariableSourceUnresolvedShort')
}

// Computed available keys for current editing condition
const availableKeys = computed(() => {
  if (!editingConditionData.deviceId) return []
  return getAvailableKeys(editingConditionData.deviceId, editingConditionData.targetType || 'state')
})

// Handle target type change to reset related fields
const handleTargetTypeChange = () => {
  editingConditionData.key = ''
  editingConditionData.propertyScope = undefined
  // Cleared alongside its siblings rather than relying on the options watcher collapsing to []. The
  // watcher does currently clear it, but a reset function that resets two of three per-target fields
  // invites the next reader to assume the third is intentionally sticky.
  editingConditionData.variableSource = undefined
  editingConditionData.value = ''
  // Reset relation to default based on new type
  if (editingConditionData.targetType === 'state') {
    editingConditionData.relation = '='
  } else {
    editingConditionData.relation = '='
  }
}

// Check if relation and value fields should be shown
// Show for Variable and State. Hidden for API type.
// Also, ensure it's shown if key is not selected (handled by disabled state), but here we specifically want Value for State.
const showRelationAndValue = computed(() => {
  // Always show relation/value for State type
  if (editingConditionData.targetType === 'state') return true
  // Hide for API type
  return editingConditionData.targetType !== 'api'
})

const enumRelationValues = ['=', '!=', 'in', 'not in']
const isSelectedSpecVariableEnum = () => {
  if (editingConditionData.targetType !== 'variable' || !editingConditionData.deviceId || !editingConditionData.key) {
    return false
  }
  const manifest = getDeviceManifestForCondition(editingConditionData.deviceId)
  const variable = Array.isArray(manifest?.InternalVariables)
    ? manifest.InternalVariables.find((item: any) => item?.Name === editingConditionData.key)
    : null
  return Array.isArray(variable?.Values) && variable.Values.length > 0
}

// Preserve canonical operator values while localizing the user-facing set-membership labels.
const getRelationLabel = (relation: string) => {
  if (relation === 'in') return t('app.relationIn')
  if (relation === 'not in') return t('app.relationNotIn')
  return relationOperators.find(item => item.value === relation)?.label || relation
}

const localizedRelationOperators = computed(() => relationOperators.map(operator => ({
  ...operator,
  label: getRelationLabel(operator.value)
})))

// Filter relation operators based on target type
const filteredRelationOperators = computed(() => {
  const operators = localizedRelationOperators.value
  if (specForm.templateId === '7'
    && (editingConditionData.targetType === 'state' || editingConditionData.targetType === 'mode')) {
    return operators.filter(op => op.value === '=')
  }
  if (editingConditionData.targetType === 'state') {
    return operators.filter(op => enumRelationValues.includes(op.value))
  }
  if (editingConditionData.targetType === 'mode') {
    return operators.filter(op => enumRelationValues.includes(op.value))
  }
  if (editingConditionData.targetType === 'variable' && isSelectedSpecVariableEnum()) {
    return operators.filter(op => enumRelationValues.includes(op.value))
  }
  // trust/privacy are enum-valued — only equality / set membership make sense.
  // Ordering comparisons (> >= < <=) would generate meaningless NuSMV conditions.
  if (editingConditionData.targetType === 'trust' || editingConditionData.targetType === 'privacy') {
    return operators.filter(op => enumRelationValues.includes(op.value))
  }
  return operators
})

// Computed available states for the selected device (for equality and set-membership selection)
const availableStates = computed(() => {
  if (!editingConditionData.deviceId) return []
  const manifest = getDeviceManifestForCondition(editingConditionData.deviceId)
  if (!manifest || !manifest.WorkingStates) return []
  return manifest.WorkingStates.map((s: any) => s.Name)
})

const getModeValuesFromManifest = (manifest: any, modeName: string): string[] => {
  if (!manifest || !Array.isArray(manifest.Modes) || !Array.isArray(manifest.WorkingStates)) return []
  const modeIndex = manifest.Modes.findIndex((mode: string) => mode === modeName)
  if (modeIndex < 0) return []

  const values = new Set<string>()
  manifest.WorkingStates.forEach((state: any) => {
    const rawName = state?.Name
    if (typeof rawName !== 'string' || rawName.trim() === '') return
    const parts = rawName.split(';')
    if (manifest.Modes.length === 1) {
      values.add(rawName.trim())
    } else if (parts.length === manifest.Modes.length) {
      const value = parts[modeIndex]?.trim()
      if (value) values.add(value)
    }
  })
  return Array.from(values)
}

const availableModeValues = computed(() => {
  if (!editingConditionData.deviceId || !editingConditionData.key) return []
  const manifest = getDeviceManifestForCondition(editingConditionData.deviceId)
  return getModeValuesFromManifest(manifest, editingConditionData.key)
})

const selectedConditionVariable = computed<InternalVariable | undefined>(() => {
  if (!editingConditionData.deviceId || !editingConditionData.key) return undefined
  const template = getDeviceTemplate(editingConditionData.deviceId)
  const variables = template?.manifest?.InternalVariables
  return variables?.find((variable: InternalVariable) => variable.Name === editingConditionData.key)
})

const availableVariableValues = computed(() => selectedConditionVariable.value?.Values?.map(String) || [])

const conditionValuePlaceholder = computed(() => {
  const variable = selectedConditionVariable.value
  if (editingConditionData.targetType !== 'variable' || !variable || !templateVariableUsesNumericBounds(variable)) {
    return t('app.enterValuePlaceholder')
  }
  return isSpecSetRelation.value
    ? t('app.conditionIntegerSetPlaceholder', { lower: variable.LowerBound, upper: variable.UpperBound })
    : `${t('app.enterValuePlaceholder')} (${variable.LowerBound} - ${variable.UpperBound})`
})

const conditionValueFailure = computed(() => editingConditionData.targetType === 'variable'
  && selectedConditionVariable.value && editingConditionData.value?.trim()
  ? validateNumericConditionValue(selectedConditionVariable.value, editingConditionData.value, editingConditionData.relation || '=', t)
  : '')

// Allowed values for trust/privacy conditions — must match the backend enum domains
// (SmvDeviceModuleBuilder: trust ∈ {untrusted, trusted}, privacy ∈ {private, public}).
const trustPrivacyValues = computed<string[]>(() => {
  if (editingConditionData.targetType === 'trust') return ['trusted', 'untrusted']
  if (editingConditionData.targetType === 'privacy') return ['public', 'private']
  return []
})

const conditionValueOptions = computed<string[]>(() => {
  if (editingConditionData.targetType === 'state') return availableStates.value
  if (editingConditionData.targetType === 'mode') return availableModeValues.value
  if (editingConditionData.targetType === 'variable') return availableVariableValues.value
  return trustPrivacyValues.value
})

const isSpecSetRelation = computed(() =>
  editingConditionData.relation === 'in' || editingConditionData.relation === 'not in'
)

const splitConditionValueList = (value: unknown): string[] => {
  if (value === null || value === undefined || value === '') return []
  const delimiter = editingConditionData.targetType === 'state' ? /[,|]/ : /[,;|]/
  return String(value)
    .split(delimiter)
    .map(part => part.trim())
    .filter(Boolean)
}

const editingConditionValueList = computed<string[]>({
  get: () => splitConditionValueList(editingConditionData.value),
  set: values => {
    editingConditionData.value = Array.from(new Set(values)).join(', ')
  }
})

watch(filteredRelationOperators, options => {
  if (!options.some(option => option.value === editingConditionData.relation)) {
    editingConditionData.relation = options[0]?.value || '='
    editingConditionData.value = ''
  }
})

watch(() => editingConditionData.relation, () => {
  if (!isSpecSetRelation.value && splitConditionValueList(editingConditionData.value).length > 1) {
    editingConditionData.value = splitConditionValueList(editingConditionData.value)[0] || ''
  }
})

const hasConditionValue = (value: unknown) =>
  value !== null && value !== undefined && value !== ''

const formatConditionValue = (condition: SpecCondition) =>
  hasConditionValue(condition.value)
    ? specConditionValueLabel(condition, specificationPresentationContext())
    : '*'

const formatEditingConditionValue = (value: unknown) =>
  specConditionValueLabel({ ...editingConditionData, value } as SpecCondition, specificationPresentationContext())

const specFormulaPreview = computed(() => buildSpecFormulaPreview({
  templateId: specForm.templateId as SpecTemplateId,
  templateLabel: currentTemplateDetail.value?.label || '',
  aConditions: specForm.aConditions,
  ifConditions: specForm.ifConditions,
  thenConditions: specForm.thenConditions
}, { nodes: props.nodes }))

const updateFormula = () => {
  if (!currentTemplateDetail.value) {
    specForm.formula = ''
    return
  }

  specForm.formula = buildSpecFormula({
    templateId: specForm.templateId as SpecTemplateId,
    templateLabel: currentTemplateDetail.value.label,
    aConditions: specForm.aConditions,
    ifConditions: specForm.ifConditions,
    thenConditions: specForm.thenConditions
  }, {
    nodes: props.nodes
  })
}

const naturalLanguageRule = computed(() => describeSpecification({
  templateId: specForm.templateId as SpecTemplateId,
  aConditions: specForm.aConditions,
  ifConditions: specForm.ifConditions,
  thenConditions: specForm.thenConditions
}, specificationPresentationContext()))

// Precomputed formatted condition data for v-for loops (optimization)
const formattedAConditions = computed(() =>
  specForm.aConditions.map(condition => ({
    ...condition,
    deviceLabel: getDeviceLabel(condition.deviceId),
    propertyLabel: formatConditionPropertyLabel(condition),
    formattedValue: formatConditionValue(condition),
    variableSourceLabel: formatConditionVariableSourceLabel(condition),
    relationLabel: getRelationLabel(condition.relation || '='),
    isDeviceMissing: isSpecConditionDeviceMissing(condition.deviceId),
    isVariableSourceUnresolved: isSpecConditionVariableSourceUnresolved(condition)
  }))
)

const formattedIfConditions = computed(() =>
  specForm.ifConditions.map(condition => ({
    ...condition,
    deviceLabel: getDeviceLabel(condition.deviceId),
    propertyLabel: formatConditionPropertyLabel(condition),
    formattedValue: formatConditionValue(condition),
    variableSourceLabel: formatConditionVariableSourceLabel(condition),
    relationLabel: getRelationLabel(condition.relation || '='),
    isDeviceMissing: isSpecConditionDeviceMissing(condition.deviceId),
    isVariableSourceUnresolved: isSpecConditionVariableSourceUnresolved(condition)
  }))
)

const formattedThenConditions = computed(() =>
  specForm.thenConditions.map(condition => ({
    ...condition,
    deviceLabel: getDeviceLabel(condition.deviceId),
    propertyLabel: formatConditionPropertyLabel(condition),
    formattedValue: formatConditionValue(condition),
    variableSourceLabel: formatConditionVariableSourceLabel(condition),
    relationLabel: getRelationLabel(condition.relation || '='),
    isDeviceMissing: isSpecConditionDeviceMissing(condition.deviceId),
    isVariableSourceUnresolved: isSpecConditionVariableSourceUnresolved(condition)
  }))
)

// Handle template selection
const handleTemplateChange = () => {
  if (!ensureWritable()) return
  const template = currentTemplateDetail.value
  if (template) {
    specForm.templateType = template.type
    // Clear conditions when template changes
    specForm.aConditions = []
    specForm.ifConditions = []
    specForm.thenConditions = []
    updateFormula()
  }
}

/**
 * Why the specification cannot be created yet, or `null` when it is complete. Drives both the
 * create button's disabled state and the inline message beneath it, so the requirement is
 * visible while the user works instead of flashing past in a toast.
 */
/**
 * Draft conditions hold `deviceId` references captured when the condition was saved. A device
 * deleted afterwards — from the canvas, another tab, the assistant, or an undo — leaves the row
 * pointing at nothing. Without this the Create button stayed enabled, the backend refused the
 * request, and the user got an opaque toast naming no row.
 */
const specConditionsMissingDevices = computed(() => Array.from(new Set(
  [...specForm.aConditions, ...specForm.ifConditions, ...specForm.thenConditions]
    .map(condition => condition.deviceId)
    .filter(deviceId => deviceId && !deviceNodes.value.some(node => node.id === deviceId))
)))

const isSpecConditionDeviceMissing = (deviceId: string) =>
  Boolean(deviceId) && !deviceNodes.value.some(node => node.id === deviceId)

const specificationBlockedReason = computed<string | null>(() => {
  if (!specForm.templateId) return t('app.selectSpecTemplate')
  const template = currentTemplateDetail.value
  if (!template) return t('app.selectSpecTemplate')
  for (const side of template.requiredSides) {
    if (getConditionsForSide(side).length === 0) {
      return t('app.addConditionForSide', { side: side.toUpperCase() })
    }
  }
  if (specConditionsMissingDevices.value.length > 0) {
    return t('app.specConditionDeviceMissing', {
      count: specConditionsMissingDevices.value.length
    })
  }
  return null
})

// Validate specification before creation
const validateSpecification = () => {
  // Reported inline by `specificationBlockedReason`, which also disables the create button.
  return specificationBlockedReason.value === null
}

// Create specification
const createSpecification = async () => {
  if (!ensureWritable()) return
  if (creatingSpecification.value || !validateSpecification()) return
  specSaveFailure.value = null
  const submittedVersion = specSaveVersion
  const ownerToken = getToken()

  const cloneConditions = (conditions: SpecCondition[]) =>
    conditions.map(condition => ({ ...condition }))
  const submittedForm = {
    templateId: specForm.templateId,
    templateType: specForm.templateType,
    devices: specForm.selectedDevices.map(device => ({
      ...device,
      selectedApis: [...device.selectedApis]
    })),
    formula: specForm.formula,
    aConditions: cloneConditions(specForm.aConditions),
    ifConditions: cloneConditions(specForm.ifConditions),
    thenConditions: cloneConditions(specForm.thenConditions)
  }
  creatingSpecification.value = true
  const saved = await new Promise<boolean>(resolve => {
    emit('add-spec', {
      ...submittedForm,
      complete: (saved, failure) => {
        if (!saved && failure && submittedVersion === specSaveVersion
          && !controlCenterDisposed && isCurrentSessionToken(ownerToken)) specSaveFailure.value = failure
        resolve(saved)
      }
    })
  })
  creatingSpecification.value = false
  if (saved && !controlCenterDisposed && isCurrentSessionToken(ownerToken)) resetSpecForm()
}

// Reset specification form
const resetSpecForm = () => {
  specForm.templateId = ''
  specForm.templateType = ''
  specForm.selectedDevices = []
  specForm.formula = ''
  specForm.aConditions = []
  specForm.ifConditions = []
  specForm.thenConditions = []
}

const handleCreateDevice = async () => {
  if (!ensureWritable()) return
  if (creatingSingleDevice.value) return
  if (singleDeviceBlockedReason.value) return

  // Find the selected template from backend templates
  let template = props.deviceTemplates.find((tpl: any) => 
    (tpl.manifest?.Name || tpl.name) === deviceForm.type
  )
  
  if (!template) {
    // Try lowercase match
    template = props.deviceTemplates.find((tpl: any) => 
      (tpl.manifest?.Name || tpl.name)?.toLowerCase() === deviceForm.type.toLowerCase()
    )
  }

  if (!template) {
    notifyError(props.templatesLoading
        ? t('app.loadingDeviceTemplates')
        : t('app.templateNotFoundWithName', { name: deviceForm.type || t('app.unknown') }))
    return
  }

  singleDeviceSaveFailure.value = null
  const submittedVersion = singleDeviceSaveVersion
  const ownerToken = getToken()
  const ownsSubmittedDraft = () => submittedVersion === singleDeviceSaveVersion
    && !controlCenterDisposed && isCurrentSessionToken(ownerToken)
  creatingSingleDevice.value = true
  const saved = await new Promise<boolean>(resolve => {
    emit('create-device', {
      template,
      customName: deviceForm.name,
      runtime: buildRuntimeConfig(template as DeviceTemplate),
      complete: (saved, failure) => {
        if (!saved && failure && ownsSubmittedDraft()) singleDeviceSaveFailure.value = failure
        resolve(saved)
      }
    })
  })
  if (!controlCenterDisposed && isCurrentSessionToken(ownerToken)) creatingSingleDevice.value = false
  const failure = singleDeviceSaveFailure.value
  if (!saved && failure && ownsSubmittedDraft()) {
    await nextTick()
    if (ownsSubmittedDraft() && singleDeviceSaveFailure.value === failure) {
      singleDeviceSaveFailureElement.value?.focus({ preventScroll: true })
      singleDeviceSaveActionElement.value?.scrollIntoView({ block: 'nearest' })
    }
  }
  if (saved && ownsSubmittedDraft()) {
    deviceForm.name = ''
    resetSingleDeviceRuntime()
  }
}

const handleCreateBatchDevices = async () => {
  if (!ensureWritable()) return
  if (creatingMultipleDevices.value) return
  // The count error is shown inline, and an empty preview (missing template, blank prefix, or
  // an invalid count) already disables the submit button, so nothing needs announcing here.
  if (batchDeviceCountError.value) return

  const items = batchDevicePreview.value
  if (items.length === 0) return

  creatingMultipleDevices.value = true
  await new Promise<boolean>(resolve => emit('create-devices', { items, complete: resolve }))
  creatingMultipleDevices.value = false
}

const handleCreateImportedDevices = async () => {
  if (!ensureWritable()) return
  if (creatingMultipleDevices.value) return
  /*
   * These conditions also drive the button's `:disabled`, and every state they describe is already
   * listed above it — so to the *user* there is nothing left to announce, which is why this stays a
   * console warning rather than a toast (`frontend/CLAUDE.md` forbids a toast over an inline
   * explanation).
   *
   * What the naming buys is diagnosability. The binding and this guard evaluate at different moments —
   * the binding gates the button, the guard runs when the click lands — so anything that changes the
   * preview in between (a debounce landing, a template-catalogue refresh, a background reconcile) lets
   * the guard see a state the button did not. Returning silently then is indistinguishable from a click
   * that worked. `Full CI` has failed two consecutive nights on exactly that shape: an E2E CSV import
   * asserts the button enabled, clicks it, and times out waiting for nodes that never appear, with
   * nothing anywhere saying why — and the same test passes locally. A named reason in the console is
   * captured by a Playwright trace.
   *
   * `importPreviewStale` is checked first for the reason recorded at its definition: without it this
   * would create whatever the *previous* text parsed to. The button is disabled then, but the guard
   * cannot rely on that alone — it is what makes the invariant hold for any caller.
   */
  const importBlockedBy = importPreviewStale.value ? 'preview is stale'
    : !importDeviceText.value.trim() ? 'import text is empty'
      : importedEnvironmentMerge.value.conflicts.length > 0 ? 'environment patch conflicts'
        : importedDevicesHaveErrors.value ? 'per-row parse errors'
          : validImportedDevices.value.length === 0 ? 'no valid devices parsed'
            : null
  if (importBlockedBy) {
    // Diagnostic only: each of these states is already explained inline above the button. This exists so
    // that "the click did nothing" leaves a named reason in the console and in a Playwright trace.
    console.warn(`[device import] create ignored: ${importBlockedBy}`)
    return
  }

  creatingMultipleDevices.value = true
  await new Promise<boolean>(resolve => {
    emit('create-devices', {
      items: validImportedDevices.value.map((item: any) => ({
        template: item.template,
        customName: item.customName,
        runtime: item.runtime
      })),
      environmentVariables: importedEnvironmentMerge.value.merged,
      complete: resolve
    })
  })
  creatingMultipleDevices.value = false
}

const handleDeviceImportFile = async (event: Event) => {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file || deviceImportSource.value !== 'file') return
  if (!ensureWritable()) {
    target.value = ''
    return
  }
  const ownerToken = getToken()
  const admittedReadOnlyEpoch = readOnlyEpoch
  const admittedFormat = deviceImportFormat.value
  const admittedEncoding = admittedFormat === 'csv' ? deviceImportCsvEncoding.value : 'utf-8'
  let requestVersion = ++deviceImportReadVersion
  deviceImportFileFailure.value = null
  const ownsCurrentSelection = () => !controlCenterDisposed && requestVersion === deviceImportReadVersion
    && admittedFormat === deviceImportFormat.value
    && deviceImportSource.value === 'file'
    && admittedReadOnlyEpoch === readOnlyEpoch && isCurrentSessionToken(ownerToken)
  try {
    if (file.size > MAX_DEVICE_IMPORT_BYTES) {
      notifyError(t('app.importFileTooLarge', { size: '4 MiB' }))
      return
    }
    // Clear before the first await so Import cannot submit the previously selected file.
    clearDeviceImportFile()
    requestVersion = ++deviceImportReadVersion
    const buffer = await file.arrayBuffer()
    if (ownsCurrentSelection()) {
      setImportTextImmediately(decodeDeviceImportFile(buffer, admittedEncoding))
      selectedDeviceImportDraft.value.fileName = file.name
    }
  } catch (error) {
    console.error('Failed to read device import file:', error)
    if (ownsCurrentSelection()) deviceImportFileFailure.value = error instanceof DeviceImportEncodingError ? 'encoding' : errorDetails(error)
  } finally {
    target.value = ''
  }
}

const handleTemplateDragStart = (template: any, event: DragEvent) => {
  if (!ensureWritable()) {
    event.preventDefault()
    return
  }
  const templateName = getTemplateName(template)
  if (!templateName) return
  closeTemplatePreview()
  event.dataTransfer?.setData('application/x-iot-template', templateName)
  event.dataTransfer?.setData('text/plain', templateName)
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'copy'
  }
  emit('template-drag-start', templateName)
}

const handleTemplateDragEnd = () => {
  emit('template-drag-end')
}

// Panel state
const localCollapsed = ref(typeof window !== 'undefined' && window.innerWidth < 768)
const localActiveSection = ref<ControlCenterSection>('templates')
const ruleSectionExpanded = ref(true)

const isControlCenterSection = (value: string | undefined): value is ControlCenterSection =>
  value === 'devices' || value === 'templates' || value === 'rules' || value === 'specs'

const isCollapsed = computed({
  get: () => props.collapsed ?? localCollapsed.value,
  set: (value: boolean) => {
    localCollapsed.value = value
    emit('update:collapsed', value)
  }
})

const activeSection = computed<ControlCenterSection>({
  // `activeSection` is optional: when a parent controls it, the prop is authoritative;
  // when it is absent the panel owns its own selection. Without this the uncontrolled
  // case silently ignored every selection change.
  get: () => isControlCenterSection(props.activeSection) ? props.activeSection : localActiveSection.value,
  set: (value: ControlCenterSection) => {
    localActiveSection.value = value
    emit('update:active-section', value)
  }
})

watch(() => [activeSection.value, isCollapsed.value], () => {
  if (activeSection.value !== 'templates' || isCollapsed.value) closeTemplatePreview()
  if (activeSection.value !== 'devices' || isCollapsed.value) {
    singleDeviceSaveVersion += 1
    singleDeviceSaveFailure.value = null
  }
  if (activeSection.value !== 'specs' || isCollapsed.value) {
    specSaveVersion += 1
    specSaveFailure.value = null
  }
}, { flush: 'sync' })

const controlTabs = computed(() => [
  { id: 'templates' as const, label: t('app.templates') },
  { id: 'devices' as const, label: t('app.devicesTool') },
  { id: 'rules' as const, label: t('app.rulesTool') },
  { id: 'specs' as const, label: t('app.specificationsShort') }
])

const { handleTablistKeydown: handleControlTabKeydown } = useRovingTablist<ControlCenterSection>({
  tabIds: () => controlTabs.value.map(tab => tab.id),
  select: id => { activeSection.value = id },
  tabElementId: id => `control-tab-${id}`
})

const panelWidth = computed(() => {
  const width = Number.isFinite(props.width) ? props.width : 320
  return `${Math.min(520, Math.max(240, width))}px`
})

// Toggle panel collapse
const togglePanel = () => {
  isCollapsed.value = !isCollapsed.value
}

// Component mounted


const createDevice = () => {
  handleCreateDevice()
}

const isDefinitiveTemplateMutationRejection = (error: any): boolean => {
  const status = Number(error?.response?.status || 0)
  return status >= 400 && status < 500
}

const templateMutationErrorMessage = (error: any, fallback: string): string => {
  if (error?.code === BOARD_RESPONSE_INCOMPLETE_CODE) {
    return t('app.boardMutationResponseIncomplete')
  }
  return localizedErrorMessage(error, fallback, locale.value)
}

const refreshTemplateCatalogForReconciliation = async (
  ownsCurrentSession: () => boolean = () => true
): Promise<DeviceTemplate[] | null> => {
  try {
    const current = await boardApi.getDeviceTemplates()
    if (!ownsCurrentSession()) return null
    emit('replace-template-catalog', current)
    return current
  } catch (error) {
    console.error('Failed to reconcile device type catalog:', error)
    return null
  }
}

const templateImportFile = ref<HTMLInputElement | null>(null)
const templateImportFeedback = ref<(ApiErrorDetails & {
  title: string
  message: string
  suggestion: string
  confirmedTemplateId?: number
}) | null>(null)
let templateImportVersion = 0
let controlCenterDisposed = false

watch(() => props.deviceTemplates, current => {
  const confirmedId = templateImportFeedback.value?.confirmedTemplateId
  if (confirmedId !== undefined && current.some(template => template.id === confirmedId)) {
    templateImportFeedback.value = null
  }
})

watch(() => [authState.token, authState.user?.userId], () => {
  templateImportVersion += 1
  deviceImportReadVersion += 1
  specSaveVersion += 1
  singleDeviceSaveVersion += 1
  singleDeviceSaveFailure.value = null
  creatingSingleDevice.value = false
  templateImportFeedback.value = null
  deviceImportFileFailure.value = null
  specSaveFailure.value = null
}, { flush: 'sync' })

onBeforeUnmount(() => {
  controlCenterDisposed = true
  singleDeviceSaveVersion += 1
  templateImportVersion += 1
})

const templateDeletionConflictReasonCodes = new Set([
  'TEMPLATE_DELETION_PREVIEW_STALE',
  'TEMPLATE_DELETION_BLOCKED'
])

const readTemplateDeletionConflictPreview = (
  error: any,
  expectedTemplateId: number
): { conflictPayload: boolean, preview: DeviceTemplateDeletionResult | null } => {
  if (Number(error?.response?.status) !== 409) {
    return { conflictPayload: false, preview: null }
  }
  const data = error?.response?.data?.data
  if (!data || typeof data !== 'object' || Array.isArray(data)) {
    return { conflictPayload: true, preview: null }
  }
  const reasonCode = typeof data.reasonCode === 'string' ? data.reasonCode : ''
  const hasCurrentPreview = Object.prototype.hasOwnProperty.call(data, 'currentPreview')
  const recognizedReason = templateDeletionConflictReasonCodes.has(reasonCode)
  if (!recognizedReason || !hasCurrentPreview) {
    return { conflictPayload: true, preview: null }
  }
  try {
    const preview = parseDeviceTemplateDeletionPreview(data.currentPreview, expectedTemplateId)
    if (reasonCode === 'TEMPLATE_DELETION_BLOCKED' && preview.canDelete) {
      return { conflictPayload: true, preview: null }
    }
    return { conflictPayload: true, preview }
  } catch (contractError) {
    console.error('Rejected malformed device type deletion conflict preview:', contractError)
    return { conflictPayload: true, preview: null }
  }
}

const handleImportTemplate = async (event: Event) => {
  const target = event.target as HTMLInputElement
  if (!ensureWritable()) {
    target.value = ''
    return
  }
  const admittedReadOnlyEpoch = readOnlyEpoch
  const file = target.files?.[0]
  if (!file) return
  const requestVersion = ++templateImportVersion
  const ownerToken = getToken()
  const ownsCurrentSession = () => !controlCenterDisposed && isCurrentSessionToken(ownerToken)
  const ownsCurrentSelection = () => ownsCurrentSession() && requestVersion === templateImportVersion
  templateImportFeedback.value = null

  if (file.size > MAX_TEMPLATE_IMPORT_BYTES) {
    notifyError(t('app.importFileTooLarge', { size: '512 KiB' }))
    target.value = ''
    return
  }

  let manifest: DeviceTemplate['manifest']
  let requestedName: string
  let source: string
  try {
    source = await file.text()
  } catch (error: unknown) {
    if (ownsCurrentSelection()) templateImportFeedback.value = {
      title: t('app.templateFileReadFailedTitle'),
      message: t('app.templateFileReadFailedMessage'),
      suggestion: t('app.templateFileReadCorrectionHint'),
      ...errorDetails(error)
    }
    target.value = ''
    return
  }
  if (!ownsCurrentSelection()) {
    target.value = ''
    return
  }
  try {
    manifest = JSON.parse(source)
  } catch (error: unknown) {
    const diagnostic = diagnoseJsonParseError(source, error)
    templateImportFeedback.value = {
      title: t('app.jsonSyntaxInvalidTitle'),
      message: t('app.jsonSyntaxInvalidMessage') + (diagnostic.line !== undefined
        ? `\n${t('app.jsonSyntaxLocation', { line: diagnostic.line, column: diagnostic.column })}` : ''),
      suggestion: t('app.jsonSyntaxCorrectionHint'),
      cause: diagnostic.reason,
      details: diagnostic.reason ? [diagnostic.reason] : [],
      fields: {}
    }
    target.value = ''
    return
  }
  requestedName = typeof manifest?.Name === 'string' ? manifest.Name.trim() : ''

  // The scene can enter playback/replacement while the file is being parsed.
  // Re-check immediately before starting the network mutation so a late file
  // read cannot write after the UI became read-only.
  if (!ownsCurrentSelection() || admittedReadOnlyEpoch !== readOnlyEpoch || !ensureWritable()) {
    target.value = ''
    return
  }

  await runBoardMutation(async () => {
    if (!ownsCurrentSelection() || admittedReadOnlyEpoch !== readOnlyEpoch || !ensureWritable()) return
    let confirmedTemplate: DeviceTemplate
    try {
      confirmedTemplate = await boardApi.addDeviceTemplate({ name: requestedName, manifest })
    } catch (error: any) {
      if (!ownsCurrentSession()) return
      if (!isDefinitiveTemplateMutationRejection(error)) {
        const current = await refreshTemplateCatalogForReconciliation(ownsCurrentSession)
        if (!ownsCurrentSession()) return
        if (!current) {
          emit('authoritative-state-unavailable', ['templates'])
          notifyBlocked(t('app.templateMutationOutcomeUnknownRefreshFailed'))
        } else if (current.some(template =>
          String(template.name || template.manifest?.Name || '').trim().toLocaleLowerCase()
            === requestedName.toLocaleLowerCase())) {
          notifyBlocked(t('app.templateImportOutcomeRefreshed', { name: requestedName }))
        } else {
          notifyBlocked(t('app.templateImportOutcomeUnconfirmedAfterRefresh'))
        }
      } else {
        const definitionInvalid = error?.response?.status === 400 || error?.response?.status === 422
        if (ownsCurrentSelection()) templateImportFeedback.value = {
          title: t(definitionInvalid ? 'app.templateDefinitionInvalidTitle' : 'app.templateImportRejectedTitle'),
          message: t(definitionInvalid ? 'app.templateDefinitionInvalidMessage' : 'app.templateImportRejectedMessage'),
          suggestion: t(definitionInvalid ? 'app.templateDefinitionCorrectionHint' : 'app.templateImportRejectedHint'),
          ...errorDetails(error)
        }
      }
      return
    }

    if (!ownsCurrentSession()) return
    try {
      const current = await boardApi.getDeviceTemplates()
      if (!ownsCurrentSession()) return
      emit('replace-template-catalog', current)
      if (ownsCurrentSelection()) templateImportFeedback.value = null
      notifySuccess(t('app.templateImportedSuccessfully'))
    } catch (error: unknown) {
      if (!ownsCurrentSession()) return
      emit('authoritative-state-unavailable', ['templates'])
      if (ownsCurrentSelection()) templateImportFeedback.value = {
        title: t('app.templateCatalogRefreshFailedTitle'),
        message: t('app.templateCatalogRefreshFailedMessage'),
        suggestion: '',
        confirmedTemplateId: confirmedTemplate.id,
        ...errorDetails(error)
      }
    }
  })

  // 清空 input 以便重新选择同一文件
  target.value = ''
}

const downloadTemplateSchema = async () => {
  try {
    const schema = await boardApi.getDeviceTemplateSchema()
    const blob = new Blob([JSON.stringify(schema, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const linkElement = document.createElement('a')
    linkElement.href = url
    linkElement.download = 'device-template-schema.json'
    linkElement.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    console.error('Failed to download template schema:', error)
    notifyError(t('app.downloadSchemaFailed'))
  }
}

const closeTemplateDeleteConfirm = (force = false) => {
  if (isDeletingTemplate.value && !force) return
  showDeleteConfirmDialog.value = false
  templateToDelete.value = null
  templateDeletePreview.value = null
}

const openDeleteConfirm = async (template: any) => {
  if (!ensureWritable()) return
  if (isLoadingTemplateDeletePreview.value) return
  const admittedReadOnlyEpoch = readOnlyEpoch
  closeTemplatePreview()
  const templateId = Number(template?.id)
  if (!Number.isSafeInteger(templateId) || templateId <= 0) {
    notifyError(t('app.invalidTemplateId'))
    return
  }
  isLoadingTemplateDeletePreview.value = true
  try {
    const preview = await boardApi.previewDeviceTemplateDeletion(templateId)
    if (props.readOnly || admittedReadOnlyEpoch !== readOnlyEpoch) return
    templateToDelete.value = preview.template
    templateDeletePreview.value = preview
    showDeleteConfirmDialog.value = true
  } catch (error: any) {
    console.error('Failed to preview template deletion:', error)
    notifyError(templateMutationErrorMessage(error, t('app.templateDeletePreviewFailed')))
  } finally {
    isLoadingTemplateDeletePreview.value = false
  }
}

const closeResetDefaultsConfirm = (force = false) => {
  if (isResettingDefaultTemplates.value && !force) return
  showResetDefaultsConfirmDialog.value = false
  defaultTemplateResetPreview.value = null
}

const {
  setDialogRef: setTemplateDeleteDialogRef,
  handleModalKeydown: handleTemplateDeleteDialogKeydown
} = useModalAccessibility(showDeleteConfirmDialog, closeTemplateDeleteConfirm)

const {
  setDialogRef: setResetDefaultsDialogRef,
  handleModalKeydown: handleResetDefaultsDialogKeydown
} = useModalAccessibility(showResetDefaultsConfirmDialog, closeResetDefaultsConfirm)

const openResetDefaultsConfirm = async () => {
  if (!ensureWritable()) return
  if (isLoadingDefaultTemplateResetPreview.value) return
  const admittedReadOnlyEpoch = readOnlyEpoch
  closeTemplatePreview()
  isLoadingDefaultTemplateResetPreview.value = true
  try {
    const preview = await boardApi.previewDefaultTemplateReset()
    if (props.readOnly || admittedReadOnlyEpoch !== readOnlyEpoch) return
    defaultTemplateResetPreview.value = preview
    showResetDefaultsConfirmDialog.value = true
  } catch (error: any) {
    console.error('Failed to preview default template reset:', error)
    notifyError(templateMutationErrorMessage(error, t('app.defaultTemplateResetPreviewFailed')))
  } finally {
    isLoadingDefaultTemplateResetPreview.value = false
  }
}

const confirmResetDefaultTemplates = async () => {
  if (!ensureWritable()) return
  if (isResettingDefaultTemplates.value) return
  const preview = defaultTemplateResetPreview.value
  if (!preview?.impactToken || !preview.canApply) return
  isResettingDefaultTemplates.value = true
  try {
    await runBoardMutation(async () => {
      try {
        const result = await boardApi.resetDefaultTemplates(preview.impactToken)
        emit('replace-template-state', {
          templates: result.currentTemplates,
          environmentVariables: result.environmentVariables
        })
        emit('edit-history-cleared')
        const successMessageKey = defaultTemplateResetChangesBoardModel(result)
          ? 'app.defaultTemplatesResetSuccessReverificationRequired'
          : 'app.defaultTemplatesResetSuccess'
        closeResetDefaultsConfirm(true)
        notifySuccess(t(successMessageKey, {
            types: result.templateChanges.length,
            devices: result.affectedDevices.length,
            variables: result.environmentChanges.length
          }))
      } catch (error: any) {
        console.error('Failed to reset default templates:', error)
        if (!isDefinitiveTemplateMutationRejection(error)) {
          try {
            const [templates, environmentVariables] = await Promise.all([
              boardApi.getDeviceTemplates(),
              boardApi.getEnvironment()
            ])
            emit('replace-template-state', { templates, environmentVariables })
            closeResetDefaultsConfirm(true)
            notifyBlocked(t('app.templateResetOutcomeRefreshed'))
          } catch (refreshError) {
            console.error('Failed to reconcile default template reset:', refreshError)
            emit('authoritative-state-unavailable', ['templates', 'environment'])
            notifyBlocked(t('app.templateMutationOutcomeUnknownRefreshFailed'))
          }
        } else if (Number(error?.response?.status) === 409) {
          try {
            defaultTemplateResetPreview.value = await boardApi.previewDefaultTemplateReset()
            notifyBlocked(t('app.defaultTemplateResetPreviewChanged'))
          } catch (previewError) {
            console.error('Failed to refresh default template reset preview:', previewError)
            notifyError(t('app.defaultTemplateResetPreviewFailed'))
          }
        } else {
          const errorMessage = templateMutationErrorMessage(error, t('app.unknownError'))
          notifyError(t('app.resetDefaultTemplatesFailedWithReason', { reason: errorMessage }))
        }
      }
    })
  } finally {
    isResettingDefaultTemplates.value = false
  }
}

const defaultTemplateResetChangeLabel = (changeType: string): string => {
  const keyByType: Record<string, string> = {
    RESTORE_MISSING: 'app.defaultTemplateChangeRestoreMissing',
    REFRESH_DEFAULT: 'app.defaultTemplateChangeRefresh',
    REPLACE_CUSTOM_NAME_COLLISION: 'app.defaultTemplateChangeReplaceCustom',
    REMOVE_OBSOLETE_DEFAULT: 'app.defaultTemplateChangeRemoveObsolete'
  }
  return t(keyByType[changeType] || 'app.defaultTemplateChangeRefresh')
}

const defaultTemplateResetBlockerReason = (reasonCode: string): string => {
  const keyByCode: Record<string, string> = {
    DEVICE_INSTANCE_INCOMPATIBLE: 'app.defaultTemplateBlockerDevice',
    AUTOMATION_RULE_INCOMPATIBLE: 'app.defaultTemplateBlockerRule',
    SPECIFICATION_INCOMPATIBLE: 'app.defaultTemplateBlockerSpecification',
    ENVIRONMENT_POOL_INCOMPATIBLE: 'app.defaultTemplateBlockerEnvironment',
    BOARD_MODEL_INCOMPATIBLE: 'app.defaultTemplateBlockerBoard'
  }
  return t(keyByCode[reasonCode] || 'app.defaultTemplateBlockerBoard')
}

const defaultTemplateResetChangesBoardModel = (result: DefaultTemplateResetResult): boolean =>
  result.affectedDevices.length > 0 || result.environmentChanges.length > 0

const formatDefaultTemplateResetEnvironmentSnapshot = (
  value: ModelEnvironmentVariable | null | undefined,
  fallbackName: string,
  source: ModelTokenSource | undefined
): string => {
  const formatToken = (token: unknown) => source === 'BUNDLED'
    ? formatBuiltInModelToken(token, t)
    : String(token ?? '')
  const name = formatToken(value?.name?.trim() || fallbackName)
  const details = [
    formatToken(value?.value),
    value?.trust ? t(`app.${value.trust}`) : '',
    value?.privacy ? t(`app.${value.privacy}`) : ''
  ].filter(detail => detail !== null && detail !== undefined && String(detail).trim() !== '')
  return details.length > 0 ? `${name}: ${details.join(' · ')}` : name
}

const formatDefaultTemplateResetEnvironmentChange = (change: EnvironmentVariableChange): string => {
  if (change.changeType === 'ADDED') {
    return t('app.environmentChangeAdded', {
      item: formatDefaultTemplateResetEnvironmentSnapshot(
        change.currentValue,
        change.name,
        change.currentModelTokenSource
      )
    })
  }
  if (change.changeType === 'UPDATED') {
    return t('app.environmentChangeUpdated', {
      before: formatDefaultTemplateResetEnvironmentSnapshot(
        change.previousValue,
        change.name,
        change.previousModelTokenSource
      ),
      after: formatDefaultTemplateResetEnvironmentSnapshot(
        change.currentValue,
        change.name,
        change.currentModelTokenSource
      )
    })
  }
  return t('app.environmentChangeRemoved', {
    item: formatDefaultTemplateResetEnvironmentSnapshot(
      change.previousValue,
      change.name,
      change.previousModelTokenSource
    )
  })
}

const confirmDeleteTemplate = async () => {
  if (!ensureWritable()) return
  if (!templateToDelete.value || !templateDeletePreview.value || isDeletingTemplate.value) return

  const templateId = Number(templateToDelete.value.id)
  const templateName = String(templateToDelete.value.name || templateToDelete.value.manifest?.Name || '').trim()
  if (!Number.isSafeInteger(templateId) || templateId <= 0) {
    notifyError(t('app.invalidTemplateId'))
    return
  }

  if (!templateDeletePreview.value.canDelete) {
    notifyBlocked(t('app.templateDeleteBlocked'))
    return
  }

  isDeletingTemplate.value = true
  try {
    await runBoardMutation(async () => {
    try {
      const result = await boardApi.deleteDeviceTemplate(
        templateId,
        templateDeletePreview.value!.impactToken
      )
      emit('replace-template-catalog', result.currentTemplates)
      emit('edit-history-cleared')
      closeTemplateDeleteConfirm(true)
      notifySuccess(t('app.templateDeleted', { name: result.deletedTemplate?.name || templateName }))
    } catch (error: any) {
      console.error('Failed to delete template:', error)
      const conflict = readTemplateDeletionConflictPreview(error, templateId)
      if (conflict.preview) {
        templateDeletePreview.value = conflict.preview
        templateToDelete.value = conflict.preview.template
        notifyBlocked(t('app.templateDeletePreviewChanged'))
        return
      }
      if (conflict.conflictPayload || !isDefinitiveTemplateMutationRejection(error)) {
        const current = await refreshTemplateCatalogForReconciliation()
        if (!current) {
          emit('authoritative-state-unavailable', ['templates'])
          if (conflict.conflictPayload) closeTemplateDeleteConfirm(true)
          notifyBlocked(t('app.templateMutationOutcomeUnknownRefreshFailed'))
        } else if (!current.some(template => Number(template.id) === templateId)) {
          closeTemplateDeleteConfirm(true)
          notifyBlocked(t('app.templateDeleteOutcomeRefreshed', { name: templateName }))
        } else if (conflict.conflictPayload) {
          const errorMessage = t('app.boardMutationResponseIncomplete')
          closeTemplateDeleteConfirm(true)
          notifyError(t('app.deleteFailedWithReason', { reason: errorMessage }))
        } else {
          notifyBlocked(t('app.templateDeleteOutcomeUnconfirmedAfterRefresh'))
        }
      } else {
        const errorMessage = templateMutationErrorMessage(error, t('app.unknownError'))
        notifyError(t('app.deleteFailedWithReason', { reason: errorMessage }))
      }
    }
    })
  } finally {
    isDeletingTemplate.value = false
  }
}

// Enhanced error handling utility with concise messages
const exportTemplate = (template: any) => {
  try {
    closeTemplatePreview()
    const dataStr = JSON.stringify(template.manifest, null, 2)
    const dataUri = 'data:application/json;charset=utf-8,'+ encodeURIComponent(dataStr)

    const exportFileDefaultName = `${template.manifest.Name}_template.json`

    const linkElement = document.createElement('a')
    linkElement.setAttribute('href', dataUri)
    linkElement.setAttribute('download', exportFileDefaultName)
    linkElement.click()

    notifySuccess(t('app.templateDownloadStarted'))
  } catch (error) {
    console.error('Failed to export template:', error)
    notifyError(t('app.exportFailed'))
  }
}

// Playback and scene replacement can begin from another Board surface while a
// Control Center dialog is open. Close local editors before the read-only
// render, and let in-flight server mutations finish their own reconciliation.
// The async preview handlers above also re-check the flag so a late response
// cannot reopen a confirmation surface after it was closed.
watch(() => props.readOnly, readOnly => {
  if (!readOnly) return
  readOnlyEpoch += 1
  templateImportVersion += 1
  deviceImportReadVersion += 1
  specSaveVersion += 1
  singleDeviceSaveVersion += 1
  singleDeviceSaveFailure.value = null
  templateImportFeedback.value = null
  deviceImportFileFailure.value = null
  specSaveFailure.value = null
  closeSpecDialog()
  if (!isDeletingTemplate.value) closeTemplateDeleteConfirm(true)
  if (!isResettingDefaultTemplates.value) closeResetDefaultsConfirm(true)
}, { flush: 'sync' })

</script>

<template>
  <aside
    v-bind="attrs"
    data-testid="control-center"
    class="absolute left-0 top-0 bottom-0 modern-panel board-side-panel z-40 flex flex-col overflow-hidden border-r"
    :class="isCollapsed ? 'is-collapsed' : 'is-expanded'"
    :style="{ width: isCollapsed ? COLLAPSED_PANEL_RAIL_CSS : panelWidth, '--board-panel-expanded-width': panelWidth }"
  >
    <div
      class="board-panel-header border-b"
    >
      <div class="board-panel-heading">
        <div v-if="!isCollapsed" class="board-panel-heading__identity">
          <div class="board-panel-icon" aria-hidden="true">
            <span class="material-symbols-outlined">dashboard</span>
          </div>
          <div class="min-w-0">
            <h2 class="board-panel-title">{{ t('app.controlCenter') }}</h2>
          </div>
        </div>
        <HintTooltip :content="t(isCollapsed ? 'app.expandPanel' : 'app.collapsePanel', { panel: t('app.controlCenter') })">
          <span class="inline-flex shrink-0">
            <button
              type="button"
              @click="togglePanel"
              class="board-panel-toggle"
              data-testid="control-center-toggle"
              :aria-label="t(isCollapsed ? 'app.expandPanel' : 'app.collapsePanel', { panel: t('app.controlCenter') })"
              :aria-expanded="!isCollapsed"
            >
              <span class="material-symbols-outlined" aria-hidden="true">{{ isCollapsed ? 'dock_to_right' : 'dock_to_left' }}</span>
            </button>
          </span>
        </HintTooltip>
      </div>
    </div>

    <!-- Section tabs (only when expanded) -->
    <Transition name="board-panel-content" @before-leave="element => element.setAttribute('inert', '')">
    <div v-if="!isCollapsed" class="board-panel-tabs border-b">
      <div
        class="board-segmented board-section-tabs"
        role="tablist"
        :aria-label="t('app.boardTools')"
      >
        <button
            v-for="tab in controlTabs"
            :key="tab.id"
            :id="`control-tab-${tab.id}`"
            type="button"
            role="tab"
            :data-testid="`control-tab-${tab.id}`"
            :aria-selected="activeSection === tab.id"
            :aria-controls="activeSection === tab.id ? `control-section-${tab.id}` : undefined"
            :tabindex="activeSection === tab.id ? 0 : -1"
            @click="activeSection = tab.id"
            @keydown="handleControlTabKeydown($event, tab.id)"
            class="board-section-tab board-section-tab--stacked"
          >
            <span class="board-section-tab__label">{{ tab.label }}</span>
          </button>
      </div>
    </div>

    </Transition>
    <Transition name="board-panel-content"
      @before-enter="element => element.removeAttribute('inert')"
      @before-leave="element => element.setAttribute('inert', '')">
    <div
      v-show="!isCollapsed"
      class="board-panel-body flex-1 iot-scroll-region p-3"
    >
      <!-- Devices -->
      <div
        v-if="activeSection === 'devices'"
        id="control-section-devices"
        role="tabpanel"
        aria-labelledby="control-tab-devices"
        data-testid="control-section-devices"
      >
        <details class="group mb-3 board-panel-section overflow-hidden" open>
        <summary class="board-section-heading list-none select-none">
          <div class="board-section-heading__identity">
            <div class="board-section-icon board-section-icon--lg">
              <span aria-hidden="true" class="material-symbols-outlined text-white text-lg">add_circle</span>
            </div>
            <div class="board-section-heading__copy">
              <span class="board-section-title">{{ t('app.createDevice') }}</span>
            </div>
          </div>
          <span aria-hidden="true" class="board-section-heading__chevron material-symbols-outlined board-text-muted transition-transform group-open:rotate-180 text-lg">expand_more</span>
        </summary>

        <div class="px-3 pb-4 space-y-3 board-bg-muted pt-2">
          <div class="control-mode-tabs">
            <button
              type="button"
              data-testid="device-create-mode-single"
              :class="{ active: deviceCreateMode === 'single' }"
              @click="deviceCreateMode = 'single'"
            >
              {{ t('app.singleDevice') }}
            </button>
            <button
              type="button"
              data-testid="device-create-mode-batch"
              :class="{ active: deviceCreateMode === 'batch' }"
              @click="deviceCreateMode = 'batch'"
            >
              {{ t('app.batchDevices') }}
            </button>
            <button
              type="button"
              data-testid="device-create-mode-import"
              :class="{ active: deviceCreateMode === 'import' }"
              @click="deviceCreateMode = 'import'"
            >
              {{ t('app.importDevices') }}
            </button>
          </div>

          <fieldset
            v-if="deviceCreateMode === 'single'"
            :disabled="props.readOnly || creatingSingleDevice"
            data-testid="single-device-fieldset"
            class="m-0 min-w-0 space-y-3 border-0 p-0"
          >
            <div class="relative">
              <label for="single-device-template" class="board-form-label mb-1 block">{{ t('app.deviceTemplateLabel') }}</label>
              <div class="relative">
                <FieldSelect
                  id="single-device-template"
                  v-model="deviceForm.type"
                  data-testid="single-device-template"
                  :disabled="isTemplateSelectorDisabled"
                  :title="templateSelectorTitle"
                >
                  <option v-if="props.templatesLoading" value="">{{ t('app.loadingDeviceTemplates') }}</option>
                  <option v-else-if="deviceTypes.length === 0" value="">{{ t('app.none') }}</option>
                  <option v-else value="" disabled hidden>{{ t('app.selectDeviceTemplate') }}</option>
                  <option v-for="type in deviceTypes" :key="type" :value="type">{{ type }}</option>
                </FieldSelect>
              </div>
            </div>

            <div class="relative">
              <label for="single-device-name" class="board-form-label mb-1 block">{{ t('app.deviceName') }}</label>
              <div class="relative">
                <input
                  id="single-device-name"
                  v-model="deviceForm.name"
                  data-testid="single-device-name"
                  class="board-field"
                  :placeholder="t('app.deviceNamePlaceholder')"
                  :title="deviceForm.name || t('app.deviceNamePlaceholder')"
                  :aria-invalid="singleDeviceNameConflict ? 'true' : undefined"
                  :aria-describedby="singleDeviceNameConflict ? 'single-device-name-conflict' : undefined"
                  type="text"
                />
              </div>
              <p
                v-if="singleDeviceNameConflict"
                id="single-device-name-conflict"
                role="alert"
                class="mt-1 text-xs font-semibold board-text-danger"
                data-testid="single-device-name-conflict"
              >
                {{ t('app.deviceNameAlreadyExists') }}
              </p>
            </div>

            <details
              v-if="hasSingleDeviceRuntimeFields"
              data-testid="single-device-runtime"
              class="device-runtime-box rounded-xl border border-[color:var(--accent-border)] board-bg-elevated p-3 shadow-sm"
            >
              <summary
                data-testid="single-device-runtime-toggle"
                class="board-form-label flex cursor-pointer select-none items-center justify-between gap-2"
              >
                <span class="inline-flex min-w-0 flex-1 items-center gap-1.5">
                  <span class="material-symbols-outlined shrink-0 text-sm board-text-accent" aria-hidden="true">tune</span>
                  <span class="min-w-0 break-words">{{ t('app.advancedInitialValuesOverrides') }}</span>
                </span>
                <span class="material-symbols-outlined shrink-0 text-sm board-text-muted transition-transform group-open:rotate-180" aria-hidden="true">expand_more</span>
              </summary>
              <p class="mt-2 text-xs leading-relaxed board-text-muted">
                {{ t('app.initialValuesHint') }}
              </p>

              <div v-if="selectedTemplateHasModes" class="board-field-grid mt-3">
                <label class="min-w-0">
                  <span class="board-form-label mb-1 block">{{ t('app.initialState') }}</span>
                  <FieldSelect
                    v-model="singleDeviceRuntime.state"
                    data-testid="single-device-state"
                  >
                    <option v-for="state in formattedSelectedWorkingStates" :key="state.name" :value="state.name">{{ state.label }}</option>
                  </FieldSelect>
                </label>

                <label class="min-w-0">
                  <span class="board-form-label mb-1 block">{{ t('app.stateTrust') }}</span>
                  <FieldSelect
                    v-model="singleDeviceRuntime.currentStateTrust"
                    data-testid="single-device-state-trust"
                  >
                    <option value="">{{ t('app.useTemplateDefaultWithValue', { value: t(`app.${findTemplateStateTrust(selectedDeviceTemplate, singleDeviceRuntime.state) || 'trusted'}`) }) }}</option>
                    <option v-for="trust in TRUST_OPTIONS" :key="trust" :value="trust">{{ t(`app.${trust}`) }}</option>
                  </FieldSelect>
                </label>

                <label class="min-w-0">
                  <span class="board-form-label mb-1 block">{{ t('app.statePrivacy') }}</span>
                  <FieldSelect
                    v-model="singleDeviceRuntime.currentStatePrivacy"
                    data-testid="single-device-state-privacy"
                  >
                    <option value="">{{ t('app.useTemplateDefaultWithValue', { value: t(`app.${findTemplateStatePrivacy(selectedDeviceTemplate, singleDeviceRuntime.state) || 'public'}`) }) }}</option>
                    <option v-for="privacy in PRIVACY_OPTIONS" :key="privacy" :value="privacy">{{ t(`app.${privacy}`) }}</option>
                  </FieldSelect>
                </label>
              </div>

              <div v-if="formattedSelectedInternalVariables.length > 0" class="mt-3 space-y-2">
                <div
                  v-for="variable in formattedSelectedInternalVariables"
                  :key="variable.Name"
                  class="rounded-lg border board-border-subtle board-bg-muted p-2"
                >
                  <div class="mb-2 flex flex-wrap items-start justify-between gap-x-3 gap-y-1">
                    <span class="max-w-full break-words text-xs font-semibold board-text-strong" :title="variable.formattedName">{{ variable.formattedName }}</span>
                    <span v-if="templateVariableUsesNumericBounds(variable)" class="max-w-full break-words text-xs font-semibold board-text-muted">
                      {{ variableInputPlaceholder(variable) }}
                    </span>
                  </div>

                  <div class="board-field-grid">
                    <label class="min-w-0">
                      <span class="board-form-label mb-1 block">{{ t('app.variableValue') }}</span>
                      <!-- A variable every state constrains is the state's consequence, not an instance
                           choice: see `templateVariableIsStateDerived`. Editing it here would be discarded
                           one step into the model, and the writers refuse a pair that disagrees. -->
                      <div
                        v-if="templateVariableIsStateDerived(selectedDeviceTemplate, variable.Name)"
                        :data-testid="`single-device-variable-derived-${variable.Name}`"
                        class="flex min-w-0 items-center gap-2 rounded-lg border border-dashed board-border-subtle board-bg-muted px-2 py-1.5"
                      >
                        <span class="min-w-0 break-words text-xs font-medium board-text-strong">
                          {{ formatTemplateModelToken(selectedDeviceTemplate, singleDeviceRuntime.variables[variable.Name]) }}
                        </span>
                        <span class="shrink-0 text-xs board-text-muted">{{ t('app.variableFollowsState') }}</span>
                      </div>
                      <FieldSelect
                        v-else-if="templateVariableHasEnumValues(variable)"
                        v-model="singleDeviceRuntime.variables[variable.Name]"
                        :data-testid="`single-device-variable-${variable.Name}`"
                        :aria-invalid="singleDeviceValueFailures[variable.Name] ? 'true' : undefined"
                        :aria-describedby="singleDeviceValueFailures[variable.Name] ? `single-device-value-error-${encodeURIComponent(variable.Name)}` : undefined"
                      >
                        <option value="">{{ t('app.useTemplateDefaultWithValue', { value: variable.formattedDefaultValue }) }}</option>
                        <option v-for="val in variable.formattedValues" :key="val.raw" :value="val.raw">{{ val.formatted }}</option>
                      </FieldSelect>
                      <input
                        v-else
                        v-model="singleDeviceRuntime.variables[variable.Name]"
                        @input="revealSingleDeviceValueFailure($event, variable.Name)"
                        :data-testid="`single-device-variable-${variable.Name}`"
                        :aria-invalid="singleDeviceValueFailures[variable.Name] ? 'true' : undefined"
                        :aria-describedby="singleDeviceValueFailures[variable.Name] ? `single-device-value-error-${encodeURIComponent(variable.Name)}` : undefined"
                        class="board-field"
                        :placeholder="variableInputPlaceholder(variable)"
                        type="text"
                      />
                      <span v-if="singleDeviceValueFailures[variable.Name]"
                        :id="`single-device-value-error-${encodeURIComponent(variable.Name)}`"
                        :data-testid="`single-device-value-error-${variable.Name}`" role="alert"
                        class="mt-1 block text-sm board-text-danger">
                        {{ singleDeviceValueFailures[variable.Name] }}
                      </span>
                    </label>

                    <label class="min-w-0">
                      <span class="board-form-label mb-1 block">{{ t('app.variableTrust') }}</span>
                      <FieldSelect
                        v-model="singleDeviceRuntime.variableTrusts[variable.Name]"
                        :data-testid="`single-device-variable-trust-${variable.Name}`"
                      >
                        <option value="">{{ t('app.useTemplateDefaultWithValue', { value: t(`app.${variable.Trust || 'trusted'}`) }) }}</option>
                        <option v-for="trust in TRUST_OPTIONS" :key="trust" :value="trust">{{ t(`app.${trust}`) }}</option>
                      </FieldSelect>
                    </label>

                    <label class="min-w-0">
                      <span class="board-form-label mb-1 block">{{ t('app.privacy') }}</span>
                      <FieldSelect
                        v-model="singleDeviceRuntime.privacies[variable.Name]"
                        :data-testid="`single-device-privacy-${variable.Name}`"
                      >
                        <option value="">{{ t('app.useTemplateDefaultWithValue', { value: t(`app.${variable.Privacy || 'public'}`) }) }}</option>
                        <option v-for="privacy in PRIVACY_OPTIONS" :key="privacy" :value="privacy">{{ t(`app.${privacy}`) }}</option>
                      </FieldSelect>
                    </label>
                  </div>
                </div>
              </div>
            </details>

            <div ref="singleDeviceSaveActionElement" class="space-y-3">
              <div v-if="singleDeviceSaveFailure" ref="singleDeviceSaveFailureElement" data-testid="single-device-save-failure" role="alert" tabindex="-1" class="text-sm board-text-danger">
                <p class="font-semibold">{{ t('app.saveNodesFailed') }}</p>
                <FailureDetails v-bind="singleDeviceSaveFailure" suggestion="" />
              </div>
              <p v-if="singleDeviceBlockedReason && !singleDeviceNameConflict" id="single-device-readiness"
                data-testid="single-device-readiness" role="status" class="text-xs board-text-muted">{{ singleDeviceBlockedReason }}</p>
              <button
                @click="createDevice()"
                data-testid="single-device-create"
                :disabled="creatingSingleDevice || Boolean(singleDeviceBlockedReason)"
                :aria-describedby="singleDeviceNameConflict ? 'single-device-name-conflict'
                  : singleDeviceBlockedReason ? 'single-device-readiness' : undefined"
                class="board-panel-submit board-action-disarmed"
              >
                <span class="material-symbols-outlined text-sm" aria-hidden="true">add_location</span>
                {{ creatingSingleDevice ? t('app.saving') : t('app.createDevice') }}
              </button>
            </div>
          </fieldset>

          <fieldset
            v-else-if="deviceCreateMode === 'batch'"
            :disabled="props.readOnly || creatingMultipleDevices"
            data-testid="batch-device-fieldset"
            class="m-0 min-w-0 space-y-3 border-0 p-0"
          >
            <div class="relative">
              <label for="batch-device-template" class="board-form-label mb-1 block">{{ t('app.deviceTemplateLabel') }}</label>
              <FieldSelect
                id="batch-device-template"
                v-model="batchDeviceForm.type"
                data-testid="batch-device-template"
                :disabled="isTemplateSelectorDisabled"
              >
                <option v-if="props.templatesLoading" value="">{{ t('app.loadingDeviceTemplates') }}</option>
                <option v-else-if="deviceTypes.length === 0" value="">{{ t('app.none') }}</option>
                <option v-else value="" disabled hidden>{{ t('app.selectDeviceTemplate') }}</option>
                <option v-for="type in deviceTypes" :key="type" :value="type">{{ type }}</option>
              </FieldSelect>
            </div>

            <div class="board-field-grid">
              <div>
                <label for="batch-device-prefix" class="board-form-label mb-1 block">{{ t('app.deviceNamePrefix') }}</label>
                <input
                  id="batch-device-prefix"
                  v-model="batchDeviceForm.prefix"
                  data-testid="batch-device-prefix"
                  class="board-field"
                  :placeholder="t('app.devicePrefixPlaceholder')"
                  type="text"
                />
              </div>
              <div>
                <label for="batch-device-count" class="board-form-label mb-1 block">{{ t('app.count') }}</label>
                <input
                  id="batch-device-count"
                  v-model.number="batchDeviceForm.count"
                  data-testid="batch-device-count"
                  class="board-field"
                  type="number"
                  min="1"
                  :max="MAX_BATCH_DEVICE_COUNT"
                  :aria-invalid="batchDeviceCountError ? 'true' : undefined"
                  :aria-describedby="batchDeviceCountError ? 'batch-device-count-error' : undefined"
                />
                <p
                  v-if="batchDeviceCountError"
                  id="batch-device-count-error"
                  role="alert"
                  class="mt-1 text-xs font-semibold leading-4 board-text-danger"
                >
                  {{ batchDeviceCountError }}
                </p>
              </div>
            </div>

            <div class="device-preview-box">
              <div class="device-preview-box__header">
                <span>{{ t('app.deviceBatchPreview') }}</span>
                <strong>{{ batchDevicePreview.length }}</strong>
              </div>
              <div v-if="batchDevicePreview.length > 0" class="device-preview-list">
                <div v-for="item in batchDevicePreview.slice(0, 8)" :key="item.customName" class="device-preview-row">
                  <span class="truncate">{{ item.customName }}</span>
                  <small class="truncate">{{ getTemplateName(item.template) }}</small>
                </div>
                <p v-if="batchDevicePreview.length > 8" class="device-preview-more">
                  +{{ batchDevicePreview.length - 8 }}
                </p>
              </div>
              <p v-else class="device-preview-empty">{{ t('app.configureBatchDevicesFirst') }}</p>
            </div>

            <button
              @click="handleCreateBatchDevices"
              data-testid="batch-device-create"
              :disabled="Boolean(batchDeviceCountError) || batchDevicePreview.length === 0 || creatingMultipleDevices"
              class="board-panel-submit board-action-disarmed"
            >
              <span class="material-symbols-outlined text-sm">playlist_add</span>
              {{ creatingMultipleDevices ? t('app.saving') : t('app.createDevicesWithCount', { count: batchDevicePreview.length }) }}
            </button>
          </fieldset>

          <fieldset
            v-else
            :disabled="props.readOnly || creatingMultipleDevices"
            data-testid="import-device-fieldset"
            @focusin="dismissCompletionNotifications"
            class="m-0 min-w-0 space-y-3 border-0 p-0"
          >
            <div role="group" :aria-label="t('app.deviceImportFormat')" class="control-mode-tabs control-mode-tabs--formats">
              <button v-for="(format, key) in deviceImportFormats" :key="key" type="button"
                :data-testid="`device-import-format-${key}`" :aria-pressed="deviceImportFormat === key"
                :class="{ active: deviceImportFormat === key }"
                @click="deviceImportFormat = key">
                {{ t('app.deviceImportOption', { format: format.label }) }}
              </button>
            </div>
            <div v-if="deviceImportSyntaxFailure" id="device-import-syntax-failure" data-testid="device-import-syntax-failure" role="alert"
              class="rounded-lg board-surface-danger px-3 py-2 text-sm board-text-danger">
              <p class="font-semibold">{{ deviceImportSyntaxFailure.error }}</p>
              <FailureDetails suggestion="" v-bind="deviceImportSyntaxFailure.diagnostics" />
            </div>
            <div v-else class="device-import-help">
              <span id="device-import-format-summary" class="min-w-0 flex-1 break-words">
                {{ t(selectedDeviceImportFormat.summaryKey) }}
              </span>
              <InfoTooltip :text="t(selectedDeviceImportFormat.helpKey)"
                :label="t('app.deviceImportHelpTitle', { format: selectedDeviceImportFormat.label })"
                placement="bottom-end" test-id="device-import-help" />
            </div>
            <a :href="selectedDeviceImportFormat.templateUrl" :download="`devices.${deviceImportFormat}`"
              :data-testid="`device-import-${deviceImportFormat}-template`" class="board-action-secondary"
              :aria-label="t('app.downloadDeviceImportTemplate', { format: selectedDeviceImportFormat.label })">
              <span class="material-symbols-outlined text-sm" aria-hidden="true">download</span>
              {{ t('app.deviceImportTemplateFormat', { format: selectedDeviceImportFormat.label }) }}
            </a>
            <fieldset class="min-w-0 space-y-2 border-0 p-0">
              <legend class="board-form-label mb-2">{{ t('app.deviceImportSource') }}</legend>
              <div class="grid grid-cols-2 gap-2">
                <label v-for="source in ['file', 'paste'] as const" :key="source"
                  class="flex min-w-0 cursor-pointer items-center gap-2 rounded-lg border board-border-subtle px-3 py-2 text-sm"
                  :class="deviceImportSource === source ? 'board-surface-info' : 'board-card'">
                  <input v-model="deviceImportSource" type="radio" name="device-import-source" :value="source"
                    :data-testid="`device-import-source-${source}`" class="shrink-0 accent-primary" />
                  <span>{{ t(source === 'file' ? 'app.deviceImportSourceFile' : 'app.deviceImportSourcePaste') }}</span>
                </label>
              </div>
            </fieldset>
            <template v-if="deviceImportSource === 'file'">
              <div v-if="deviceImportFormat === 'csv'" class="space-y-1">
                <div class="flex items-center gap-2">
                  <label for="device-import-csv-encoding" class="board-form-label">{{ t('app.deviceImportFileEncoding') }}</label>
                  <InfoTooltip :text="t('app.deviceImportEncodingHint')" :label="t('app.deviceImportEncodingHelp')"
                    placement="bottom-end" test-id="device-import-encoding-help" />
                </div>
                <FieldSelect id="device-import-csv-encoding" v-model="deviceImportCsvEncoding" data-testid="device-import-csv-encoding">
                  <option value="utf-8">UTF-8</option>
                  <option value="gb18030">GB18030 / GBK</option>
                </FieldSelect>
              </div>
              <div class="flex flex-wrap items-center gap-2">
                <button type="button" class="board-file-trigger" data-testid="device-import-file-trigger"
                  @click="deviceImportFileInput?.click()">
                  <span class="material-symbols-outlined text-xs" aria-hidden="true">upload_file</span>
                  {{ t('app.deviceImportChooseFile', { format: selectedDeviceImportFormat.label }) }}
                </button>
                <input :key="deviceImportFormat" ref="deviceImportFileInput" data-testid="device-import-file" type="file"
                  :accept="selectedDeviceImportFormat.extension" class="hidden" @change="handleDeviceImportFile">
              </div>
              <div v-if="selectedDeviceImportDraft.fileName" class="flex min-w-0 items-start gap-2 rounded-lg border board-border-subtle p-3">
                <span data-testid="device-import-file-name" class="min-w-0 flex-1 break-words text-sm">{{ selectedDeviceImportDraft.fileName }}</span>
                <button type="button" data-testid="device-import-file-clear" class="board-action-inline-subtle shrink-0"
                  @click="clearDeviceImportFile">{{ t('app.deviceImportClearFile') }}</button>
              </div>
            </template>
            <template v-else>
              <label for="device-import-text" class="board-form-label block">
                {{ t('app.deviceImportTextLabel', { format: selectedDeviceImportFormat.label }) }}
              </label>
              <textarea id="device-import-text" v-model="importDeviceText" data-testid="device-import-text"
                :aria-invalid="deviceImportSyntaxFailure ? 'true' : undefined"
                :aria-describedby="deviceImportSyntaxFailure ? 'device-import-syntax-failure' : 'device-import-format-summary'"
                class="board-field min-h-32 resize-y font-mono leading-relaxed"
                :placeholder="t('app.deviceImportPlaceholder', { format: selectedDeviceImportFormat.label })"
              ></textarea>
            </template>

            <div v-if="deviceImportFileFailure" data-testid="device-import-file-failure" role="alert"
              class="rounded-lg board-surface-danger px-3 py-3 text-sm board-text-danger">
              <p class="font-semibold">{{ t(deviceImportFileFailure === 'encoding' ? 'app.deviceImportEncodingFailed' : 'app.deviceImportFileReadFailedTitle') }}</p>
              <p class="mt-1">{{ t(deviceImportFileFailure === 'encoding'
                ? (deviceImportFormat === 'csv' ? 'app.deviceImportCsvEncodingRetry' : 'app.deviceImportJsonEncodingRetry')
                : 'app.deviceImportFileReadFailedMessage') }}</p>
              <FailureDetails v-if="deviceImportFileFailure !== 'encoding'" :suggestion="t('app.deviceImportFileReadFailedHint')" v-bind="deviceImportFileFailure" />
            </div>

            <div v-if="parsedImportedDevices.length > 0 && !deviceImportSyntaxFailure" data-testid="device-import-preview" class="device-preview-box">
              <div class="device-preview-box__header">
                <span>{{ t('app.parsedDevices') }}</span>
                <strong>{{ validImportedDevices.length }}/{{ parsedImportedDevices.length }}</strong>
              </div>
              <div class="device-preview-list device-preview-list--scroll">
                <div
                  v-for="item in parsedImportedDevices"
                  :key="`${item.source}-${item.templateName}-${item.name}`"
                  class="device-preview-row"
                  :class="{ 'has-error': item.error, 'has-warning': item.warning && !item.error }"
                >
                  <span v-if="item.error || item.warning || importedEnvironmentMerge.conflicts.length > 0"
                    class="device-import-row-source" data-testid="device-import-row-source">{{ deviceImportSourceLabel(item.source) }}</span>
                  <span data-testid="device-import-row-name" :class="{ truncate: !item.error && !item.warning }">{{ item.customName || item.name || t('app.unnamed') }}</span>
                  <small :class="{ truncate: !item.error && !item.warning }" :title="!item.error && !item.warning ? item.templateName : undefined">
                    {{ item.error || item.warning || item.templateName }}
                  </small>
                </div>
              </div>
            </div>

            <div
              v-if="importedEnvironmentMerge.conflicts.length > 0"
              data-testid="device-import-environment-conflicts"
              role="alert"
              class="space-y-1 rounded-lg board-surface-danger px-3 py-2 text-xs font-semibold leading-4 board-text-danger"
            >
              <p>{{ t('app.deviceImportEnvironmentConflicts') }}</p>
              <ul class="m-0 list-disc space-y-1 pl-4 break-words">
                <li v-for="(conflict, index) in importedEnvironmentMerge.conflicts" :key="`${conflict.name}-${conflict.field}-${index}`">
                  {{ formatImportedEnvironmentConflict(conflict) }}
                </li>
              </ul>
            </div>

            <p v-if="importedDevicesHaveErrors && !deviceImportSyntaxFailure" id="device-import-blocked-reason"
              data-testid="device-import-blocked-reason" class="board-caption board-text-danger">
              {{ t('app.deviceImportResolveErrors') }}
            </p>
            <button
              @click="handleCreateImportedDevices"
              data-testid="device-import-create"
              :disabled="importPreviewStale || validImportedDevices.length === 0 || importedDevicesHaveErrors || importedEnvironmentMerge.conflicts.length > 0 || creatingMultipleDevices"
              :aria-describedby="importedDevicesHaveErrors && !deviceImportSyntaxFailure ? 'device-import-blocked-reason' : undefined"
              class="board-panel-submit board-action-disarmed"
            >
              <span class="material-symbols-outlined text-sm">library_add</span>
              {{ creatingMultipleDevices ? t('app.saving') : t('app.importDevicesWithCount', { count: validImportedDevices.length }) }}
            </button>
          </fieldset>
        </div>
      </details>
      </div>

      <!-- Templates -->
      <div
        v-if="activeSection === 'templates'"
        id="control-section-templates"
        role="tabpanel"
        aria-labelledby="control-tab-templates"
        data-testid="control-section-templates"
        class="space-y-3"
      >
        <details data-testid="control-template-create" class="group board-panel-section overflow-hidden" open>
          <summary class="board-section-heading list-none select-none">
            <div class="board-section-heading__identity">
              <div class="board-section-icon board-section-icon--lg">
                <span class="material-symbols-outlined board-text-muted text-lg">add_box</span>
              </div>
              <div class="board-section-heading__copy">
                <span class="board-section-title">{{ t('app.createTemplate') }}</span>
              </div>
            </div>
            <span aria-hidden="true" class="board-section-heading__chevron material-symbols-outlined board-text-muted transition-transform group-open:rotate-180 text-lg">expand_more</span>
          </summary>

          <div class="px-3 pb-4 board-bg-muted pt-2 space-y-3">
            <!-- Role chips remove borders, so the drop target owns its dashed border. -->
            <div class="relative overflow-hidden rounded-lg border-2 border-dashed border-[color:var(--accent-border)] bg-[color:var(--surface-muted)] p-3 transition-all hover:border-[color:var(--accent)] hover:shadow-md">
              <input ref="templateImportFile" data-testid="template-import-file" type="file" accept=".json"
                class="hidden" :disabled="props.readOnly" @change="handleImportTemplate">
              <button type="button" data-testid="template-import-open"
                class="group block w-full rounded-md p-2 text-left"
                :class="props.readOnly ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'"
                :disabled="props.readOnly"
                @click="templateImportFile?.click()">
                <span class="flex items-center gap-3">
                  <span class="board-section-icon">
                    <span class="material-symbols-outlined board-text-muted text-base" aria-hidden="true">upload_file</span>
                  </span>
                  <span class="board-section-title">{{ t('app.importJsonTemplate') }}</span>
                </span>
              </button>
              <div class="mt-2 flex flex-wrap items-center gap-4">
                <button type="button" class="board-action-secondary" @click="downloadTemplateSchema">
                  <span class="material-symbols-outlined text-xs" aria-hidden="true">download</span>
                  {{ t('app.downloadTemplateSchema') }}
                </button>
                <InfoTooltip :text="t('app.deviceTemplateSchemaHint')"
                  :label="t('app.showHelpFor', { topic: t('app.createTemplate') })"
                  test-id="template-format-help" />
              </div>
            </div>
            <div v-if="templateImportFeedback" data-testid="template-import-feedback" role="alert"
              class="rounded-lg board-surface-danger px-3 py-3 text-sm board-text-danger">
              <p class="font-semibold">{{ templateImportFeedback.title }}</p>
              <p class="mt-1 whitespace-pre-wrap" data-testid="failure-summary">{{ templateImportFeedback.message }}</p>
              <FailureDetails v-bind="templateImportFeedback" />
            </div>
          </div>
        </details>

        <details data-testid="control-template-repository" class="group board-panel-section overflow-hidden" open>
          <summary class="board-section-heading list-none select-none">
            <div class="board-section-heading__identity">
              <div class="board-section-icon board-section-icon--lg">
                <span class="material-symbols-outlined board-text-muted text-lg">inventory_2</span>
              </div>
              <div class="board-section-heading__copy">
                <span class="board-section-title">{{ t('app.templateRepository') }}</span>
              </div>
              <InfoTooltip :text="t('app.dragTemplateToCanvasHint')"
                :label="t('app.showHelpFor', { topic: t('app.templateRepository') })"
                test-id="template-repository-help" />
            </div>
            <span aria-hidden="true" class="board-section-heading__chevron material-symbols-outlined board-text-muted transition-transform group-open:rotate-180 text-lg">expand_more</span>
          </summary>

          <div class="px-3 pb-4 board-bg-muted pt-2 space-y-3">
            <div class="relative">
              <span aria-hidden="true" class="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 material-symbols-outlined board-text-muted text-sm">search</span>
              <input
                v-model="templateSearchQuery"
                class="board-field board-field--clearable pl-8 pr-12"
                :placeholder="t('app.searchTemplates')"
                :aria-label="t('app.searchTemplates')"
                type="search"
              />
              <HintTooltip v-if="templateSearchQuery" :content="t('app.clearSearch')">
                <button
                  type="button"
                  :aria-label="t('app.clearSearch')"
                  @click="templateSearchQuery = ''"
                  class="board-field-clear"
                >
                  <span aria-hidden="true" class="material-symbols-outlined text-sm">close</span>
                </button>
              </HintTooltip>
            </div>

            <div class="flex flex-wrap items-center justify-between gap-2 px-1">
              <div class="flex items-center gap-1.5">
                <HintTooltip :content="mutationTitle(t('app.resetDefaultTemplates'))">
                  <button
                    type="button"
                    data-testid="reset-default-templates"
                    class="board-action-secondary"
                    :disabled="props.readOnly || props.templatesLoading || isLoadingDefaultTemplateResetPreview"
                    @click="openResetDefaultsConfirm"
                  >
                    <span
                      v-if="isLoadingDefaultTemplateResetPreview"
                      class="template-reset-dialog__spinner"
                      aria-hidden="true"
                    ></span>
                    <span v-else class="material-symbols-outlined text-xs" aria-hidden="true">restart_alt</span>
                    <span class="whitespace-normal text-left">{{ t('app.resetDefaultTemplates') }}</span>
                  </button>
                </HintTooltip>
                <!-- A count is not a status. `board-chip-warning` here was read by a review as a queue of
                     things needing attention when it is only how many templates are listed;
                     `board-chip-neutral` exists so a number does not have to borrow a role's meaning. -->
                <span class="text-xs font-semibold board-chip-neutral px-2 py-0.5 rounded-full">
                  {{ filteredTemplates.length }}
                </span>
              </div>
            </div>

            <div
              v-if="props.templatesLoading"
              class="rounded-xl border-dashed board-surface-warning px-3 py-6 text-center text-xs board-text-warning"
            >
              <span class="material-symbols-outlined mb-2 block animate-spin text-2xl">sync</span>
              <p class="font-semibold">{{ t('app.loadingDeviceTemplates') }}</p>
            </div>

            <div v-else-if="filteredTemplates.length > 0" class="space-y-2.5">
              <!--
                A group opens when it has something to show.

                `open` was unconditional, so on a dense board "Custom Templates 0" rendered expanded while holding
                nothing — a heading, a border and vertical space spent on an empty set, in the panel where space is
                scarcest. Measured on a 12-device board: 6 of 7 detail sections expanded at once, one of them empty.

                An empty group is still listed rather than hidden: its absence would leave a user wondering where
                custom templates go, and "Custom Templates 0" is a truthful answer to that question. Collapsed is
                the honest middle — the fact stays available, the space does not.
              -->
              <details
                v-for="group in templateGroups"
                :key="group.key"
                class="template-group rounded-lg border shadow-sm"
                :data-testid="`template-group-${group.key}`"
                :open="group.templates.length > 0"
              >
                <summary class="template-group__summary flex cursor-pointer select-none items-center justify-between gap-2 px-2.5 py-2 transition-colors">
                  <div class="flex min-w-0 items-center gap-2">
                    <span class="template-group__chevron material-symbols-outlined text-sm transition-transform">expand_more</span>
                    <span class="template-group__label board-form-label truncate" :title="group.label">{{ group.label }}</span>
                  </div>
                  <span class="template-group__count rounded-full px-2 py-0.5 text-xs font-semibold">{{ group.templates.length }}</span>
                </summary>

                <!-- A narrow column may shrink below the preferred card width. -->
                <div
                  v-if="group.templates.length > 0"
                  class="template-group__grid grid grid-cols-[repeat(auto-fill,minmax(min(100%,11rem),1fr))] gap-2 px-2.5 pb-2.5"
                >
                  <div
                    v-for="template in group.templates"
                    :key="template.id"
                    class="template-card board-inventory-card relative"
                    :class="{ 'template-card--active': isTemplatePreviewVisible(template) }"
                    :draggable="!props.readOnly"
                    :title="getTemplateName(template)"
                    @click.stop="toggleTemplatePreview(template, $event)"
                    @dragstart.stop="handleTemplateDragStart(template, $event)"
                    @dragend="handleTemplateDragEnd"
                  >
                    <button
                      type="button"
                      class="relative block w-full border-0 bg-transparent p-0 text-left"
                      :aria-label="`${t('app.viewTemplateDetails')}: ${getTemplateName(template)}`"
                      @click.stop="toggleTemplatePreview(template, $event)"
                    >
                      <div class="flex items-start gap-2">
                        <div class="template-card__icon w-7 h-7 rounded flex items-center justify-center transition-all shadow-sm overflow-hidden flex-shrink-0">
                          <img
                            :src="getTemplateIconUrl(template)"
                            alt=""
                            aria-hidden="true"
                            class="h-full w-full object-contain iot-device-artwork"
                          />
                        </div>
                        <div class="min-w-0 flex-1">
                          <div class="flex min-w-0 items-start gap-1">
                            <!--
                              h3, not h4. The nearest heading above this is the panel's own h2 ("Control Center"), so
                              h4 skipped a level: measured on a 12-device board, the outline read
                              `h1 → h2 控制中心 → h4 Air Conditioner`. A screen-reader user stepping that outline is
                              told an h3 exists and hunts for a section that was never there.
                              The group label above ("Default Templates") is a <span> inside a <summary> and carries no
                              level, so h3 is the correct rung for the card titles themselves rather than a new heading.
                            -->
                            <h3 class="template-card__title board-inventory-name min-w-0 flex-1 transition-colors truncate" :title="getTemplateName(template)">
                              {{ getTemplateName(template) }}
                            </h3>
                            <span class="template-card__drag-cue material-symbols-outlined" aria-hidden="true">drag_indicator</span>
                          </div>
                          <div class="template-card__stats text-xs mt-0.5 flex items-center gap-1.5">
                            <span class="template-card__pill px-1.5 py-0.5 rounded">{{ template.manifest.InternalVariables?.length || 0 }} {{ t('app.varsShort') }}</span>
                            <span class="template-card__pill px-1.5 py-0.5 rounded">{{ template.manifest.APIs?.length || 0 }} {{ t('app.apisShort') }}</span>
                          </div>
                        </div>
                      </div>
                    </button>

                    <div class="template-card__actions mt-0.5 pt-0.5 border-t flex justify-end gap-1">
                      <HintTooltip :content="`${t('app.export')}: ${getTemplateName(template)}`">
                        <button
                          type="button"
                          @click.stop="exportTemplate(template)"
                          @dragstart.stop.prevent
                          class="template-card__action board-panel-icon-action"
                          :aria-label="`${t('app.export')}: ${getTemplateName(template)}`"
                        >
                          <span class="material-symbols-outlined text-xs" aria-hidden="true">download</span>
                        </button>
                      </HintTooltip>
                      <HintTooltip :content="mutationTitle(`${t('app.deleteTemplate')}: ${getTemplateName(template)}`)">
                        <button
                          type="button"
                          @click.stop="openDeleteConfirm(template)"
                          @dragstart.stop.prevent
                          :disabled="props.readOnly || isLoadingTemplateDeletePreview || isDeletingTemplate"
                          class="template-card__action template-card__action--danger board-panel-icon-action board-panel-icon-action--danger"
                          :aria-label="`${t('app.deleteTemplate')}: ${getTemplateName(template)}`"
                        >
                          <span class="material-symbols-outlined text-xs" aria-hidden="true">delete</span>
                        </button>
                      </HintTooltip>
                    </div>
                  </div>
                </div>

                <div v-else class="template-group__empty mx-2.5 mb-2.5 rounded-lg border border-dashed px-3 py-2 text-center text-xs">
                  {{ group.key === 'default' ? t('app.noDefaultTemplates') : t('app.noCustomTemplates') }}
                </div>
              </details>
            </div>

            <div v-else class="relative overflow-hidden text-center py-8 border-2 border-dashed board-border-subtle rounded-xl board-bg-muted">
              <div class="absolute top-0 left-0 w-full h-1 bg-gradient-to-r from-[color:var(--warning)] via-[color:var(--warning)] to-[color:var(--warning)]"></div>
              <div class="relative">
                <div class="w-14 h-14 mx-auto board-chip-warning rounded-full flex items-center justify-center mb-3 shadow-inner">
                  <span class="material-symbols-outlined board-text-warning text-2xl">inventory_2</span>
                </div>
                <p class="text-xs board-text-muted mb-1 font-semibold">
                  {{ templateSearchQuery ? t('app.noMatchingTemplates') : t('app.noTemplatesYet') }}
                </p>
                <p class="text-xs board-text-muted">
                  {{ templateSearchQuery ? t('app.tryDifferentSearchTerm') : t('app.importJsonTemplateHint') }}
                </p>
                <button
                  v-if="templateSearchQuery"
                  @click="templateSearchQuery = ''"
                  class="mt-3 px-4 py-1.5 text-xs font-semibold board-text-warning board-chip-warning hover:board-chip-warning rounded-lg transition-colors"
                >
                  {{ t('app.clearSearch') }}
                </button>
              </div>
            </div>
          </div>
        </details>
      </div>

      <!-- Rules -->
      <div
        v-show="activeSection === 'rules'"
        id="control-section-rules"
        role="tabpanel"
        aria-labelledby="control-tab-rules"
        data-testid="control-section-rules"
      >
        <details class="group mb-3 board-panel-section overflow-hidden" open
          data-testid="rule-creation-card"
          @toggle="ruleSectionExpanded = ($event.target as HTMLDetailsElement).open">
        <summary class="board-section-heading list-none select-none">
          <div class="board-section-heading__identity">
            <div class="board-section-icon board-section-icon--lg">
              <span class="material-symbols-outlined text-white text-lg">function</span>
            </div>
            <div class="board-section-heading__copy">
              <span class="board-section-title">{{ t('app.createRule') }}</span>
            </div>
          </div>
          <span aria-hidden="true" class="board-section-heading__chevron material-symbols-outlined board-text-muted transition-transform group-open:rotate-180 text-lg">expand_more</span>
        </summary>

        <div class="px-3 pb-4 board-bg-muted pt-2 grid grid-cols-1 gap-3">
          <RuleBuilder
            :nodes="deviceNodes"
            :device-templates="deviceTemplates"
            :active="activeSection === 'rules' && !isCollapsed && ruleSectionExpanded"
            :read-only="props.readOnly"
            @save-rule="emit('save-rule', $event)"
          />
        </div>
      </details>
      </div>

      <!-- Specs -->
      <div
        v-if="activeSection === 'specs'"
        id="control-section-specs"
        role="tabpanel"
        aria-labelledby="control-tab-specs"
        data-testid="control-section-specs"
      >
        <details class="group mb-3 board-panel-section overflow-hidden" open>
        <summary class="board-section-heading list-none select-none">
          <div class="board-section-heading__identity">
            <div class="board-section-icon board-section-icon--lg">
              <span class="material-symbols-outlined text-white text-lg">verified</span>
            </div>
            <div class="board-section-heading__copy">
              <span class="board-section-title">{{ t('app.createSpecification') }}</span>
            </div>
          </div>
          <span aria-hidden="true" class="board-section-heading__chevron material-symbols-outlined board-text-muted transition-transform group-open:rotate-180 text-lg">expand_more</span>
        </summary>

        <div class="px-3 pb-4 board-bg-muted pt-2 space-y-3">
          <!-- Specification Creation -->
          <fieldset
            data-testid="spec-editor-fieldset"
            :disabled="props.readOnly || creatingSpecification"
            :aria-busy="creatingSpecification"
            class="m-0 min-w-0 space-y-3 border-0 p-0"
          >
            <!-- Step 1: Select Template -->
            <div>
              <label for="spec-template-select" class="board-form-label mb-2 block">{{ t('app.selectTemplate') }}</label>
              <FieldSelect
                id="spec-template-select"
                v-model="specForm.templateId"
                data-testid="spec-template-select"
                :aria-describedby="hasSpecificationConditions ? 'spec-template-change-notice' : undefined"
                @change="handleTemplateChange"
              >
                <option value="" disabled hidden>{{ t('app.selectSpecificationTemplate') }}</option>
                <option
                  v-for="template in specTemplateDetails"
                  :key="template.id"
                  :value="template.id"
                  class="truncate"
                >
                  {{ templateMessage(template.labelKey, template.label) }}
                </option>
              </FieldSelect>
              <p v-if="hasSpecificationConditions" id="spec-template-change-notice" class="mt-1.5 text-xs leading-5 board-text-warning">
                {{ t('app.specTemplateChangeNotice') }}
              </p>
              <p v-if="currentTemplateDetail" class="text-xs board-text-muted mt-1.5 px-1">
                <span>
                  {{ templateMessage(currentTemplateDetail.descriptionKey, currentTemplateDetail.description) }}
                </span>
              </p>
            </div>

            <!-- Step 2: Add Conditions based on template requirements -->
            <div v-if="specForm.templateId" class="space-y-2">
              <label class="board-form-label block">{{ t('app.configureConditions') }}</label>

              <!-- A Conditions (Always/Forall) -->
              <div v-if="isSideRequired('a')" class="relative overflow-hidden rounded-lg board-card p-2.5">
                <div class="relative flex items-center justify-between mb-2">
                  <div class="flex items-center gap-2">
                    <span class="w-6 h-6 bg-[color:var(--accent-fill)] rounded-md flex items-center justify-center">
                      <svg class="w-4 h-4 text-white" fill="currentColor" viewBox="0 0 24 24">
                        <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/>
                      </svg>
                    </span>
                    <span class="board-form-label">{{ t('app.aConditions') }}</span>
                  </div>
                  <button
                    @click="openConditionDialog('a')"
                    data-testid="spec-add-condition-a"
                    class="board-action-inline"
                  >
                    <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4"/>
                    </svg>
                    {{ t('app.add') }}
                  </button>
                </div>
                <div class="space-y-1.5 max-h-36 iot-scroll-region pr-1">
                  <div
                    v-for="(condition, index) in formattedAConditions"
                    :key="condition.id"
                    class="flex items-center justify-between board-bg-elevated rounded-md px-2.5 py-1.5 border border-[color:var(--border)] shadow-sm hover:shadow-md transition-all"
                  >
                    <div class="flex items-center gap-2 overflow-hidden flex-1">
                      <div class="w-6 h-6 board-meta-chip rounded-md flex items-center justify-center flex-shrink-0">
                        <svg class="w-3.5 h-3.5 board-text-muted" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 3v2m6-2v2M9 19v2m6-2v2M5 9H3m2 6H3m18-6h-2m2 6h-2M7 19h10a2 2 0 002-2V7a2 2 0 00-2-2H7a2 2 0 00-2 2v10a2 2 0 002 2zM9 9h6v6H9V9z"/>
                        </svg>
                      </div>
                      <div class="flex items-center gap-1 overflow-hidden flex-1 min-w-0">
                        <span
                          class="text-xs font-medium truncate min-w-0"
                          :class="condition.isDeviceMissing
 ? 'board-text-danger line-through'
 : 'board-text-strong'"
                          :title="condition.deviceLabel"
                        >
                          {{ condition.deviceLabel }}
                        </span>
                        <span class="board-text-muted flex-shrink-0">·</span>
                        <span class="text-xs board-text-muted font-medium truncate flex-shrink-0" :title="condition.propertyLabel">{{ condition.propertyLabel }}</span>
                        <!-- Which of the two variable questions this row asks. A row with no
                             recorded choice is marked unresolved rather than shown as either. -->
                        <span
                          v-if="condition.targetType === 'variable'"
                          class="text-xs px-1 py-0.5 rounded flex-shrink-0 border"
                          :class="condition.isVariableSourceUnresolved
                            ? 'board-chip-danger board-text-danger border-[color:var(--danger-border)]'
                            : 'board-text-muted board-bg-control board-border-subtle'"
                          :title="condition.variableSourceLabel || ''"
                          data-testid="spec-condition-row-variable-source"
                        >{{ condition.variableSourceLabel }}</span>
                        <span class="text-xs board-text-muted board-bg-control px-1 py-0.5 rounded flex-shrink-0">
                          {{ condition.relationLabel }}
                        </span>
                        <span class="text-xs board-card board-text-muted px-1 py-0.5 rounded truncate max-w-[60px] flex-shrink-0" :title="condition.formattedValue">
                          {{ condition.formattedValue }}
                        </span>
                      </div>
                    </div>
                    <div class="flex gap-1 ml-2 flex-shrink-0">
                      <HintTooltip :content="t('app.edit')">
                        <button
                          type="button"
                          @click="openConditionDialog('a', index)"
                          class="p-1 board-text-muted hover:board-text-strong hover:board-control-hover rounded transition-colors"
                          :aria-label="t('app.editConditionNumbered', { number: index + 1 })"
                        >
                          <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"/>
                          </svg>
                        </button>
                      </HintTooltip>
                      <HintTooltip :content="t('app.delete')">
                        <button
                          type="button"
                          @click="removeCondition('a', index)"
                          class="p-1 board-text-muted hover:board-text-danger hover:board-chip-danger rounded transition-colors"
                          :aria-label="t('app.removeConditionNumbered', { number: index + 1 })"
                        >
                          <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/>
                          </svg>
                        </button>
                      </HintTooltip>
                    </div>
                  </div>
                  <div v-if="specForm.aConditions.length === 0" class="text-center py-2 text-xs board-text-muted italic board-bg-elevated rounded border border-dashed border-[color:var(--border)]">
                    {{ t('app.noConditionsAdded') }}
                  </div>
                </div>
              </div>

              <!-- IF Conditions (Antecedent) -->
              <div v-if="isSideRequired('if')" class="relative overflow-hidden rounded-lg board-card p-2.5">
                <div class="relative flex items-center justify-between mb-2">
                  <div class="flex items-center gap-2">
                    <span class="w-6 h-6 bg-[color:var(--accent-fill)] rounded-md flex items-center justify-center">
                      <svg class="w-4 h-4 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z"/>
                      </svg>
                    </span>
                    <span class="board-form-label">{{ t('app.ifConditions') }}</span>
                  </div>
                  <button
                    @click="openConditionDialog('if')"
                    data-testid="spec-add-condition-if"
                    class="board-action-inline"
                  >
                    <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4"/>
                    </svg>
                    {{ t('app.add') }}
                  </button>
                </div>
                <div class="space-y-1.5 max-h-36 iot-scroll-region pr-1">
                  <div
                    v-for="(condition, index) in formattedIfConditions"
                    :key="condition.id"
                    class="flex items-center justify-between board-bg-elevated rounded-md px-2.5 py-1.5 border border-[color:var(--border)] shadow-sm hover:shadow-md transition-all"
                  >
                    <div class="flex items-center gap-2 overflow-hidden flex-1">
                      <div class="w-6 h-6 board-meta-chip rounded-md flex items-center justify-center flex-shrink-0">
                        <svg class="w-3.5 h-3.5 board-text-muted" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 3v2m6-2v2M9 19v2m6-2v2M5 9H3m2 6H3m18-6h-2m2 6h-2M7 19h10a2 2 0 002-2V7a2 2 0 00-2-2H7a2 2 0 00-2 2v10a2 2 0 002 2zM9 9h6v6H9V9z"/>
                        </svg>
                      </div>
                      <div class="flex items-center gap-1 overflow-hidden flex-1 min-w-0">
                        <span
                          class="text-xs font-medium truncate min-w-0"
                          :class="condition.isDeviceMissing
 ? 'board-text-danger line-through'
 : 'board-text-strong'"
                          :title="condition.deviceLabel"
                        >
                          {{ condition.deviceLabel }}
                        </span>
                        <span class="board-text-muted flex-shrink-0">·</span>
                        <span class="text-xs board-text-muted font-medium truncate flex-shrink-0" :title="condition.propertyLabel">{{ condition.propertyLabel }}</span>
                        <!-- Which of the two variable questions this row asks. A row with no
                             recorded choice is marked unresolved rather than shown as either. -->
                        <span
                          v-if="condition.targetType === 'variable'"
                          class="text-xs px-1 py-0.5 rounded flex-shrink-0 border"
                          :class="condition.isVariableSourceUnresolved
                            ? 'board-chip-danger board-text-danger border-[color:var(--danger-border)]'
                            : 'board-text-muted board-bg-control board-border-subtle'"
                          :title="condition.variableSourceLabel || ''"
                          data-testid="spec-condition-row-variable-source"
                        >{{ condition.variableSourceLabel }}</span>
                        <span class="text-xs board-text-muted board-bg-control px-1 py-0.5 rounded flex-shrink-0">
                          {{ condition.relationLabel }}
                        </span>
                        <span class="text-xs board-card board-text-muted px-1 py-0.5 rounded truncate max-w-[60px] flex-shrink-0" :title="condition.formattedValue">
                          {{ condition.formattedValue }}
                        </span>
                      </div>
                    </div>
                    <div class="flex gap-1 ml-2 flex-shrink-0">
                      <HintTooltip :content="t('app.edit')">
                        <button
                          type="button"
                          @click="openConditionDialog('if', index)"
                          class="p-1 board-text-muted hover:board-text-strong hover:board-control-hover rounded transition-colors"
                          :aria-label="t('app.editConditionNumbered', { number: index + 1 })"
                        >
                          <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"/>
                          </svg>
                        </button>
                      </HintTooltip>
                      <HintTooltip :content="t('app.delete')">
                        <button
                          type="button"
                          @click="removeCondition('if', index)"
                          class="p-1 board-text-muted hover:board-text-danger hover:board-chip-danger rounded transition-colors"
                          :aria-label="t('app.removeConditionNumbered', { number: index + 1 })"
                        >
                          <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/>
                          </svg>
                        </button>
                      </HintTooltip>
                    </div>
                  </div>
                  <div v-if="specForm.ifConditions.length === 0" class="text-center py-2 text-xs board-text-muted italic board-bg-elevated rounded border border-dashed border-[color:var(--border)]">
                    {{ t('app.noConditionsAdded') }}
                  </div>
                </div>
              </div>

              <!-- THEN Conditions (Consequent) -->
              <div v-if="isSideRequired('then')" class="relative overflow-hidden rounded-lg board-card p-2.5">
                <div class="relative flex items-center justify-between mb-2">
                  <div class="flex items-center gap-2">
                    <span class="w-6 h-6 bg-[color:var(--accent-fill)] rounded-md flex items-center justify-center">
                      <svg class="w-4 h-4 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 8l4 4m0 0l-4 4m4-4H3"/>
                      </svg>
                    </span>
                    <span class="board-form-label">{{ t('app.thenConditions') }}</span>
                  </div>
                  <button
                    @click="openConditionDialog('then')"
                    data-testid="spec-add-condition-then"
                    class="board-action-inline"
                  >
                    <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4"/>
                    </svg>
                    {{ t('app.add') }}
                  </button>
                </div>
                <div class="space-y-1.5 max-h-36 iot-scroll-region pr-1">
                  <div
                    v-for="(condition, index) in formattedThenConditions"
                    :key="condition.id"
                    class="flex items-center justify-between board-bg-elevated rounded-md px-2.5 py-1.5 border border-[color:var(--border)] shadow-sm hover:shadow-md transition-all"
                  >
                    <div class="flex items-center gap-2 overflow-hidden flex-1">
                      <div class="w-6 h-6 board-meta-chip rounded-md flex items-center justify-center flex-shrink-0">
                        <svg class="w-3.5 h-3.5 board-text-muted" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 3v2m6-2v2M9 19v2m6-2v2M5 9H3m2 6H3m18-6h-2m2 6h-2M7 19h10a2 2 0 002-2V7a2 2 0 00-2-2H7a2 2 0 00-2 2v10a2 2 0 002 2zM9 9h6v6H9V9z"/>
                        </svg>
                      </div>
                      <div class="flex items-center gap-1 overflow-hidden flex-1 min-w-0">
                        <span
                          class="text-xs font-medium truncate min-w-0"
                          :class="condition.isDeviceMissing
 ? 'board-text-danger line-through'
 : 'board-text-strong'"
                          :title="condition.deviceLabel"
                        >
                          {{ condition.deviceLabel }}
                        </span>
                        <span class="board-text-muted flex-shrink-0">·</span>
                        <span class="text-xs board-text-muted font-medium truncate flex-shrink-0" :title="condition.propertyLabel">{{ condition.propertyLabel }}</span>
                        <!-- Which of the two variable questions this row asks. A row with no
                             recorded choice is marked unresolved rather than shown as either. -->
                        <span
                          v-if="condition.targetType === 'variable'"
                          class="text-xs px-1 py-0.5 rounded flex-shrink-0 border"
                          :class="condition.isVariableSourceUnresolved
                            ? 'board-chip-danger board-text-danger border-[color:var(--danger-border)]'
                            : 'board-text-muted board-bg-control board-border-subtle'"
                          :title="condition.variableSourceLabel || ''"
                          data-testid="spec-condition-row-variable-source"
                        >{{ condition.variableSourceLabel }}</span>
                        <span class="text-xs board-text-muted board-bg-control px-1 py-0.5 rounded flex-shrink-0">
                          {{ condition.relationLabel }}
                        </span>
                        <span class="text-xs board-card board-text-muted px-1 py-0.5 rounded truncate max-w-[60px] flex-shrink-0" :title="condition.formattedValue">
                          {{ condition.formattedValue }}
                        </span>
                      </div>
                    </div>
                    <div class="flex gap-1 ml-2 flex-shrink-0">
                      <HintTooltip :content="t('app.edit')">
                        <button
                          type="button"
                          @click="openConditionDialog('then', index)"
                          class="p-1 board-text-muted hover:board-text-strong hover:board-control-hover rounded transition-colors"
                          :aria-label="t('app.editConditionNumbered', { number: index + 1 })"
                        >
                          <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"/>
                          </svg>
                        </button>
                      </HintTooltip>
                      <HintTooltip :content="t('app.delete')">
                        <button
                          type="button"
                          @click="removeCondition('then', index)"
                          class="p-1 board-text-muted hover:board-text-danger hover:board-chip-danger rounded transition-colors"
                          :aria-label="t('app.removeConditionNumbered', { number: index + 1 })"
                        >
                          <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/>
                          </svg>
                        </button>
                      </HintTooltip>
                    </div>
                  </div>
                  <div v-if="specForm.thenConditions.length === 0" class="text-center py-2 text-xs board-text-muted italic board-bg-elevated rounded border border-dashed border-[color:var(--border)]">
                    {{ t('app.noConditionsAdded') }}
                  </div>
                </div>
              </div>
            </div>

            <div v-if="specSaveFailure" data-testid="spec-save-failure" role="alert"
              class="rounded-lg board-surface-danger px-3 py-3 text-sm board-text-danger">
              <p class="font-semibold">{{ t(specSaveFailure.reasonCode === 'DUPLICATE_SPECIFICATION'
                ? 'app.specDuplicate' : 'app.saveSpecsFailed') }}</p>
              <FailureDetails :suggestion="specSaveFailure.reasonCode === 'DUPLICATE_SPECIFICATION'
                ? t('app.specSaveDuplicateHint') : ''" v-bind="specSaveFailure" />
            </div>

            <!-- Step 3: Generated Specification Description -->
            <div v-if="!specificationBlockedReason" class="relative overflow-hidden rounded-lg board-bg-elevated border border-[color:var(--border)] p-3 shadow-sm">
              <div class="relative">
                <div class="flex items-center gap-2 mb-2">
                  <span class="w-6 h-6 board-meta-chip rounded-md flex items-center justify-center">
                    <svg class="w-4 h-4 board-text-muted" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 5h12M9 3v2m1.048 9.5A18.022 18.022 0 016.412 9m6.088 9h7M11 21l5-10 5 10M12.751 5C11.783 10.77 8.07 15.61 3 18.129"/>
                    </svg>
                  </span>
                  <span class="board-form-label">{{ t('app.specificationDescription') }}</span>
                </div>
                <div class="text-xs board-text-strong leading-relaxed pl-8" data-testid="spec-description">
                  {{ naturalLanguageRule }}
                </div>
                <details data-testid="spec-formula-details" class="mt-2 border-t board-border-subtle pt-2">
                  <summary class="cursor-pointer py-3">
                    <span class="board-form-label">{{ t('app.formulaPreview') }}</span>
                    <span
                      class="board-form-label ml-2 px-1.5 py-0.5 board-bg-control rounded"
                      data-testid="spec-formula-kind"
                    >{{ specFormulaPreview.kind || t('app.modelFormulaKind') }}</span>
                  </summary>
                  <!-- `iot-scroll-region-x` rather than raw `overflow-x-auto`: the primitive owns the token
                       scrollbar and overscroll containment, which a bare overflow does not. Type at the
                       product floor, since a formula is the thing a user is trying to read here. -->
                  <code class="iot-scroll-region-x block whitespace-nowrap text-xs board-bg-control board-text-strong px-2 py-1 rounded font-mono" data-testid="spec-formula-expression">
                    {{ specFormulaPreview.expression }}
                  </code>
                </details>
              </div>
            </div>

            <!-- Create Button -->
            <p
              v-if="specificationBlockedReason"
              id="spec-create-blocked-reason"
              role="status"
              class="mb-2 text-xs font-semibold leading-4 board-text-danger"
              data-testid="spec-create-blocked-reason"
            >
              {{ specificationBlockedReason }}
            </p>
            <button
              @click="createSpecification"
              data-testid="spec-create"
              :disabled="Boolean(specificationBlockedReason) || creatingSpecification"
              :aria-describedby="specificationBlockedReason ? 'spec-create-blocked-reason' : undefined"
              class="board-panel-submit board-action-disarmed"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"/>
              </svg>
              {{ creatingSpecification ? t('app.saving') : t('app.createSpecification') }}
            </button>
          </fieldset>
        </div>
      </details>
      </div>


    </div>

    </Transition>
  </aside>

  <!-- Specification Condition Dialog -->
  <div
    v-if="showSpecDialog"
    data-testid="spec-condition-dialog"
    class="iot-dialog-overlay"
    @click="closeSpecDialog"
    @keydown="handleSpecDialogKeydown"
  >
    <div
      :ref="setSpecDialogRef"
      class="iot-dialog iot-dialog--md"
      role="dialog"
      aria-modal="true"
      aria-labelledby="spec-condition-dialog-title"
      tabindex="-1"
      @click.stop
    >
      <!-- Header -->
      <div class="iot-dialog__header">
        <span class="iot-dialog__icon" aria-hidden="true">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"/>
          </svg>
        </span>
        <div class="iot-dialog__heading">
          <h3 id="spec-condition-dialog-title" class="iot-dialog__title">
            {{ editingConditionIndex >= 0 ? t('app.editCondition') : t('app.addConditionTitle') }}
          </h3>
        </div>
        <button
          type="button"
          :aria-label="t('app.close')"
          @click="closeSpecDialog"
          class="iot-dialog__close"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/>
          </svg>
        </button>
      </div>

      <!-- Content Body -->
      <div class="iot-dialog__body iot-scroll-region space-y-6">
        <!-- Device Selection -->
        <div class="space-y-2">
          <div class="flex items-center gap-2">
            <label for="spec-condition-device" class="board-form-label">{{ t('app.device') }}</label>
          </div>
          <div class="relative w-full">
            <FieldSelect
              id="spec-condition-device"
              v-model="editingConditionData.deviceId"
              data-testid="spec-condition-device"
              @change="editingConditionData.key = ''"
              :disabled="deviceNodes.length === 0"
            >
              <option v-if="deviceNodes.length === 0" value="">{{ t('app.none') }}</option>
              <option v-else value="" hidden>{{ t('app.selectDevicePlaceholder') }}</option>
              <option
                v-for="device in deviceNodes"
                :key="device.id"
                :value="device.id"
              >
                {{ device.label }}
              </option>
            </FieldSelect>
          </div>
        </div>

        <!-- Type -->
        <div class="space-y-2" v-if="editingConditionData.deviceId">
          <div class="flex items-center gap-2">
            <svg class="w-5 h-5 board-text-strong" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 7h.01M7 3h5c.512 0 1.024.195 1.414.586l7 7a2 2 0 010 2.828l-7 7a2 2 0 01-2.828 0l-7-7A2 2 0 013 12V7a4 4 0 014-4z"/>
            </svg>
            <label for="spec-condition-type" class="board-form-label">{{ t('app.type') }}</label>
          </div>
          <div class="relative w-full">
            <FieldSelect
              id="spec-condition-type"
              v-model="editingConditionData.targetType"
              data-testid="spec-condition-type"
              @change="handleTargetTypeChange"
            >
              <option v-if="localizedTargetTypes.length === 0" value="">{{ t('app.none') }}</option>
              <option v-else value="" hidden>{{ t('app.selectType') }}</option>
              <option v-for="type in localizedTargetTypes" :key="type.value" :value="type.value">
                {{ type.label }}
              </option>
            </FieldSelect>
          </div>
        </div>

        <!-- Property -->
        <div class="space-y-2" v-if="editingConditionData.targetType !== 'state' && editingConditionData.deviceId">
          <div class="flex items-center gap-2">
            <svg class="w-5 h-5 board-text-strong" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z"/>
            </svg>
            <label for="spec-condition-key" class="board-form-label">{{ t('app.property') }}</label>
          </div>
          <div class="relative w-full">
            <FieldSelect
              id="spec-condition-key"
              v-model="conditionKeySelection"
              data-testid="spec-condition-key"
              :disabled="availableKeys.length === 0"
            >
              <option v-if="availableKeys.length === 0" value="">{{ t('app.none') }}</option>
              <option v-else value="" hidden>{{ t('app.selectProperty') }}</option>
              <option
                v-for="key in availableKeys"
                :key="key.value"
                :value="key.value"
              >
                {{ key.label }}
              </option>
            </FieldSelect>
          </div>
        </div>

        <!--
          Which value the condition means. Radios rather than a dropdown so both questions are
          visible at once: they read almost identically until a device is falsifying its readings,
          which is the case the author needs to see before choosing.
        -->
        <fieldset
          class="space-y-2"
          v-if="editingConditionVariableSourceOptions.length > 0"
          data-testid="spec-condition-variable-source"
        >
          <legend class="flex items-center gap-2">
            <svg class="w-5 h-5 board-text-strong" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"/>
            </svg>
            <span class="board-section-title">{{ t('app.specVariableSourceTitle') }}</span>
          </legend>
          <label
            v-for="option in editingConditionVariableSourceOptions"
            :key="option.value"
            class="flex items-start gap-2 w-full board-bg-elevated border-2 rounded-lg px-3 py-2.5 text-sm board-text-strong cursor-pointer"
            :class="editingConditionData.variableSource === option.value
              ? 'border-[color:var(--accent-border)]'
              : 'border-[color:var(--border-strong)]'"
            :data-testid="`spec-condition-variable-source-${option.value}`"
          >
            <!-- `name` is what makes these ONE radio group. Without it the browser treats each input as
                 its own group, so arrow keys do not move between the two options and a screen reader
                 announces two independent controls instead of "1 of 2". -->
            <input
              type="radio"
              name="spec-condition-variable-source"
              class="mt-0.5 flex-shrink-0"
              :value="option.value"
              :checked="editingConditionData.variableSource === option.value"
              @change="editingConditionData.variableSource = option.value"
            />
            <span class="min-w-0">{{ option.label }}</span>
          </label>
          <p class="text-xs board-text-muted" data-testid="spec-condition-variable-source-help">
            {{ editingConditionVariableSourceOptions.length === 1
              ? t('app.specVariableSourceDeviceLocalHelp')
              : editingConditionVariableIsFalsifiable
                ? t('app.specVariableSourceFalsifiableHint')
                : t('app.specVariableSourceNotFalsifiableHint') }}
          </p>
        </fieldset>

        <!-- Condition Details -->
        <div class="space-y-2" v-if="editingConditionData.deviceId && showRelationAndValue">
          <div class="flex items-center gap-2">
            <svg class="w-5 h-5 board-text-strong" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6V4m0 2a2 2 0 100 4m0-4a2 2 0 110 4m-6 8a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4m6 6v10m6-2a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4"/>
            </svg>
            <span class="board-section-title">{{ t('app.conditionDetails') }}</span>
          </div>
          <div class="board-condition-value-grid">
            <!-- Operator -->
            <div class="min-w-0">
              <label for="spec-condition-relation" class="board-form-label mb-1 block">{{ t('app.conditionOperator') }}</label>
              <FieldSelect
                id="spec-condition-relation"
                v-model="editingConditionData.relation"
                data-testid="spec-condition-relation"
              >
                <option v-for="op in filteredRelationOperators" :key="op.value" :value="op.value">
                  {{ op.label }}
                </option>
              </FieldSelect>
            </div>
            <!-- Value -->
            <div class="min-w-0">
              <label for="spec-condition-value" class="board-form-label mb-1 block">{{ t('app.value') }}</label>
              <FieldSelect
                id="spec-condition-value"
                v-if="conditionValueOptions.length > 0 && isSpecSetRelation"
                v-model="editingConditionValueList"
                data-testid="spec-condition-value"
                multiple
                size="4"
                class="min-h-[7.5rem]"
              >
                <option v-for="val in conditionValueOptions" :key="val" :value="val">
                  {{ formatEditingConditionValue(val) }}
                </option>
              </FieldSelect>
              <FieldSelect
                id="spec-condition-value"
                v-else-if="conditionValueOptions.length > 0"
                v-model="editingConditionData.value"
                data-testid="spec-condition-value"
              >
                <option value="" hidden>{{ t('app.selectValue') }}</option>
                <option v-for="val in conditionValueOptions" :key="val" :value="val">
                  {{ formatEditingConditionValue(val) }}
                </option>
              </FieldSelect>
              <input
                id="spec-condition-value"
                v-else
                v-model="editingConditionData.value"
                data-testid="spec-condition-value"
                class="board-field"
                :placeholder="conditionValuePlaceholder"
                :aria-invalid="conditionValueFailure ? 'true' : undefined"
                :aria-describedby="conditionValueFailure ? 'spec-condition-blocked-reason' : undefined"
              />
            </div>
          </div>
        </div>

        <!-- Preview -->
        <div v-if="!specConditionBlockedReason" class="space-y-2">
          <div class="flex items-center gap-2">
            <svg class="w-5 h-5 board-text-strong" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"/>
            </svg>
            <span class="board-form-label">{{ t('app.preview') }}</span>
          </div>
          <div data-testid="spec-condition-preview" class="font-mono text-xs board-bg-control rounded-lg px-3 py-2.5 border border-[color:var(--border-strong)] board-text-strong break-all w-full">
            <span v-if="!editingConditionData.deviceId" class="board-text-muted">{{ t('app.selectDevicePlaceholder') }}</span>
            <span v-else class="board-text-danger font-semibold">{{ getDeviceLabel(editingConditionData.deviceId) }}</span>
            <template v-if="editingConditionData.targetType !== 'state' && editingConditionData.key">
              <span class="board-text-muted">.</span>
              <span class="board-text-danger font-semibold">{{ formatConditionPropertyLabel(editingConditionData as SpecCondition) }}</span>
            </template>
            <!-- Without this the preview reads the same for both questions, which is what let an
                 author believe they had pinned the device's own reading. -->
            <template v-if="editingConditionData.targetType === 'variable' && editingConditionData.key">
              <span
                class="ml-1 board-text-muted"
                data-testid="spec-condition-preview-variable-source"
              >({{ formatConditionVariableSourceLabel(editingConditionData as SpecCondition) }})</span>
            </template>
            <template v-if="editingConditionData.deviceId && showRelationAndValue">
              <span class="board-text-muted mx-1">{{ getRelationLabel(editingConditionData.relation || '=') }}</span>
              <span class="board-text-strong">"{{ formatEditingConditionValue(editingConditionData.value) }}"</span>
            </template>
          </div>
        </div>
      </div>

      <!-- Footer Actions -->
      <div class="iot-dialog__footer flex-wrap">
        <p
          v-if="specConditionBlockedReason"
          id="spec-condition-blocked-reason"
          role="status"
          class="w-full text-xs font-semibold board-text-danger"
          data-testid="spec-condition-blocked-reason"
        >
          {{ specConditionBlockedReason }}
        </p>
        <button
          @click="closeSpecDialog"
          class="iot-dialog-btn iot-dialog-btn--ghost"
        >
          {{ t('app.cancel') }}
        </button>
        <button
          @click="saveCondition"
          data-testid="spec-condition-save"
          class="iot-dialog-btn iot-dialog-btn--primary"
          :disabled="props.readOnly || Boolean(specConditionBlockedReason)"
          :aria-describedby="specConditionBlockedReason ? 'spec-condition-blocked-reason' : undefined"
        >
          <svg class="w-4 h-4" :fill="editingConditionIndex >= 0 ? 'currentColor' : 'none'" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" :d="editingConditionIndex >= 0 ? 'M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z' : 'M12 4v16m8-8H4'"/>
          </svg>
          {{ editingConditionIndex >= 0 ? t('app.update') : t('app.add') }}
        </button>
      </div>
    </div>
  </div>

  <Teleport to="body">
    <div
      v-if="activeTemplatePreview"
      ref="templatePreviewRef"
      class="template-preview iot-scroll-region"
      role="region"
      aria-labelledby="template-preview-title"
      tabindex="-1"
      :style="templatePreviewStyle"
      :data-testid="`template-preview-${getTemplateKey(activeTemplatePreview)}`"
      @click.stop
      @keydown.esc.stop.prevent="closeTemplatePreview"
    >
      <div class="template-preview__header">
        <div class="min-w-0">
          <p class="template-preview__eyebrow">{{ t('app.templateDetails') }}</p>
          <h5 id="template-preview-title" class="template-preview__title break-words">{{ getTemplateName(activeTemplatePreview) }}</h5>
        </div>
        <HintTooltip :content="t('app.close')">
          <button
            type="button"
            class="template-preview__close"
            :aria-label="t('app.close')"
            @click.stop="closeTemplatePreview"
          >
            <span class="material-symbols-outlined text-sm">close</span>
          </button>
        </HintTooltip>
      </div>

      <p v-if="getTemplateDescription(activeTemplatePreview)" class="template-preview__description" :title="getTemplateDescription(activeTemplatePreview)">
        {{ getTemplateDescription(activeTemplatePreview) }}
      </p>

      <div class="template-preview__meta">
        <div>
          <span>{{ t('app.initState') }}</span>
          <strong :title="getTemplateInitState(activeTemplatePreview)">{{ getTemplateInitState(activeTemplatePreview) }}</strong>
        </div>
        <div>
          <span>{{ t('app.transition') }}</span>
          <strong>{{ getTemplateTransitionCount(activeTemplatePreview) }}</strong>
        </div>
      </div>

      <div class="template-preview__sections">
        <div
          v-for="section in activeTemplatePreviewSections"
          :key="section.key"
          :data-testid="`template-preview-section-${section.key}`"
          class="template-preview__section"
        >
          <span class="template-preview__section-label">{{ section.label }}</span>
          <div class="template-preview__chips">
            <span v-if="section.items.length === 0" class="template-preview__empty">{{ t('app.none') }}</span>
            <template v-else>
              <span
                v-for="item in section.items"
                :key="`${section.key}-${item}`"
                class="template-preview__chip"
                :title="item"
              >
                {{ item }}
              </span>
            </template>
          </div>
        </div>
      </div>
    </div>
  </Teleport>

  <!-- Delete Confirmation Dialog -->
  <div
    v-if="showDeleteConfirmDialog"
    class="iot-dialog-overlay"
    @click="closeTemplateDeleteConfirm()"
    @keydown="handleTemplateDeleteDialogKeydown"
  >
    <div
      :ref="setTemplateDeleteDialogRef"
      class="iot-dialog iot-dialog--sm iot-dialog--danger"
      data-testid="template-delete-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="delete-template-dialog-title"
      tabindex="-1"
      @click.stop
    >
      <!-- The preview owns whether deletion is allowed and which consequences apply. -->
      <div class="iot-dialog__header">
        <div class="iot-dialog__icon">
          <span class="material-symbols-outlined" aria-hidden="true">delete</span>
        </div>
        <div class="iot-dialog__heading">
          <h3 id="delete-template-dialog-title" class="iot-dialog__title">
            {{ t('app.deleteTemplate') }}
          </h3>
          <p v-if="templateToDelete" class="iot-dialog__subtitle break-words">{{ getTemplateName(templateToDelete) }}</p>
        </div>
      </div>

      <div class="iot-dialog__body iot-scroll-region">
      <div
        v-if="templateDeletePreview && !templateDeletePreview.canDelete"
        class="rounded-lg board-surface-warning p-3 text-left"
      >
        <p class="text-sm font-semibold board-text-warning">{{ t('app.templateDeleteBlocked') }}</p>
        <p class="mt-1 text-xs leading-5 board-text-warning">{{ t('app.templateDeleteBlockedDetail') }}</p>
        <ul class="mt-2 space-y-1.5">
          <li
            v-for="blocker in templateDeletePreview.blockers"
            :key="blocker.itemId"
            class="flex items-center gap-2 rounded border border-[color:var(--warning-border)] board-bg-elevated px-2 py-1.5 text-xs board-text-strong"
          >
            <span class="material-symbols-outlined text-sm board-text-warning" aria-hidden="true">devices</span>
            <span class="min-w-0 break-words">{{ blocker.itemLabel }}</span>
          </li>
        </ul>
      </div>

      <p v-else-if="templateDeletePreview" class="iot-dialog__consequence">
        {{ t('app.templateDeleteNoReferences', {
          historyEntries: templateDeletePreview.editHistoryEntryCount
        }) }}
      </p>
      </div>

      <div class="iot-dialog__footer">
        <button
          :disabled="isDeletingTemplate"
          @click="closeTemplateDeleteConfirm()"
          class="iot-dialog-btn iot-dialog-btn--ghost"
        >
          {{ t('app.cancel') }}
        </button>
        <button
          @click="confirmDeleteTemplate"
          :disabled="props.readOnly || isDeletingTemplate || !templateDeletePreview?.canDelete"
          data-testid="template-delete-confirm"
          class="iot-dialog-btn iot-dialog-btn--danger"
        >
          <span class="material-symbols-outlined" aria-hidden="true">delete</span>
          {{ isDeletingTemplate ? t('app.deleting') : t('app.deleteTemplate') }}
        </button>
      </div>
    </div>
  </div>

  <!-- Reset Default Templates Confirmation Dialog -->
  <div
    v-if="showResetDefaultsConfirmDialog"
    class="iot-dialog-overlay"
    @click="closeResetDefaultsConfirm()"
    @keydown="handleResetDefaultsDialogKeydown"
  >
    <div
      :ref="setResetDefaultsDialogRef"
      class="iot-dialog iot-dialog--md iot-dialog--warning template-reset-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="reset-default-templates-title"
      tabindex="-1"
      @click.stop
    >
      <div class="iot-dialog__header">
        <div class="iot-dialog__icon">
          <span class="material-symbols-outlined" aria-hidden="true">restart_alt</span>
        </div>
        <div class="iot-dialog__heading">
          <h3 id="reset-default-templates-title" class="iot-dialog__title">
            {{ t('app.resetDefaultTemplatesTitle') }}
          </h3>
          <p class="iot-dialog__subtitle">
            {{ t('app.resetDefaultTemplatesMessage') }}
          </p>
        </div>
      </div>

      <div class="iot-dialog__body iot-scroll-region">
      <template v-if="defaultTemplateResetPreview">
        <div class="template-reset-dialog__notice rounded-lg border px-3 py-2 text-xs leading-relaxed">
          {{ t('app.resetDefaultTemplatesImpactSummary', {
            types: defaultTemplateResetPreview.templateChanges.length,
            devices: defaultTemplateResetPreview.affectedDevices.length,
            variables: defaultTemplateResetPreview.environmentChanges.length
          }) }}
        </div>

        <div class="mt-3 max-h-44 iot-scroll-region border-y board-border-subtle py-1">
          <div
            v-for="change in defaultTemplateResetPreview.templateChanges"
            :key="`${change.changeType}:${change.templateName}`"
            class="flex items-start justify-between gap-3 py-1.5 text-xs"
          >
            <span class="min-w-0 break-words font-semibold">{{ change.templateName }}</span>
            <span class="shrink-0 text-right board-text-muted">
              {{ defaultTemplateResetChangeLabel(change.changeType) }}
              <span v-if="change.semanticsChanged"> · {{ t('app.templateSemanticsChanged') }}</span>
            </span>
          </div>
        </div>

        <div v-if="defaultTemplateResetPreview.affectedDevices.length" class="mt-3 text-xs">
          <div class="font-semibold">{{ t('app.devicesUsingChangedTemplates') }}</div>
          <div class="mt-1 max-h-20 iot-scroll-region board-text-muted">
            <div
              v-for="device in defaultTemplateResetPreview.affectedDevices"
              :key="device.deviceId"
              class="break-words py-0.5"
            >
              {{ device.deviceLabel }} · {{ device.templateName }}
            </div>
          </div>
        </div>

        <div
          v-if="defaultTemplateResetPreview.environmentChanges.length"
          data-testid="default-template-reset-environment-changes"
          class="mt-3 text-xs"
        >
          <div class="font-semibold">{{ t('app.environmentVariablesWillChange', {
            count: defaultTemplateResetPreview.environmentChanges.length
          }) }}</div>
          <ul class="mt-1 max-h-28 list-disc space-y-1 iot-scroll-region pl-5 board-text-muted">
            <li
              v-for="change in defaultTemplateResetPreview.environmentChanges"
              :key="`${change.changeType}:${change.name}`"
              class="break-words"
            >
              {{ formatDefaultTemplateResetEnvironmentChange(change) }}
            </li>
          </ul>
        </div>

        <div
          v-if="defaultTemplateResetPreview.blockers.length"
          class="mt-3 border-l-2 border-[color:var(--danger-border)] pl-3 text-xs board-text-danger"
          role="alert"
        >
          <div class="font-semibold">{{ t('app.defaultTemplateResetBlocked') }}</div>
          <div
            v-for="(blocker, index) in defaultTemplateResetPreview.blockers"
            :key="`${blocker.itemLabel}:${index}`"
            class="mt-1 break-words"
          >
            <div>
              <strong>{{ blocker.itemLabel }}</strong>: {{ defaultTemplateResetBlockerReason(blocker.reasonCode) }}
            </div>
            <details class="mt-1 text-xs board-text-danger">
              <summary class="cursor-pointer font-semibold">{{ t('app.technicalDetails') }}</summary>
              <code class="mt-1 block whitespace-pre-wrap break-words rounded board-chip-danger px-2 py-1">{{ blocker.reason }}</code>
            </details>
          </div>
        </div>

        <p class="iot-dialog__consequence">
          {{ t('app.resetDefaultTemplatesNotice', {
            historyEntries: defaultTemplateResetPreview.editHistoryEntryCount
          }) }}
        </p>
        <p
          v-if="defaultTemplateResetChangesBoardModel(defaultTemplateResetPreview)"
          data-testid="default-template-reset-reverification-warning"
          class="mt-2 rounded-lg board-surface-warning px-3 py-2 text-xs font-semibold leading-relaxed board-text-warning"
          role="alert"
        >
          {{ t('app.defaultTemplateResetReverificationRequired') }}
        </p>
      </template>

      </div>

      <div class="iot-dialog__footer">
        <button
          type="button"
          class="iot-dialog-btn iot-dialog-btn--ghost"
          data-testid="default-template-reset-cancel"
          :disabled="isResettingDefaultTemplates"
          @click="closeResetDefaultsConfirm()"
        >
          {{ t('app.cancel') }}
        </button>
        <button
          type="button"
          class="iot-dialog-btn iot-dialog-btn--primary"
          data-testid="default-template-reset-confirm"
          :disabled="props.readOnly || isResettingDefaultTemplates || !defaultTemplateResetPreview?.canApply"
          @click="confirmResetDefaultTemplates"
        >
          <span v-if="isResettingDefaultTemplates" class="iot-dialog-btn__spinner" aria-hidden="true"></span>
          <span v-else class="material-symbols-outlined" aria-hidden="true">restart_alt</span>
          {{ isResettingDefaultTemplates ? t('app.resetting') : t('app.resetDefaultTemplates') }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* Material Symbols font */
.material-symbols-outlined {
  font-family: 'Material Symbols Outlined';
  font-variation-settings: 'FILL' 0, 'wght' 400, 'GRAD' 0, 'opsz' 24;
}

/* Details/Summary styling */
details > summary {
  list-style: none;
}

details > summary::-webkit-details-marker {
  display: none;
}

.control-mode-tabs {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--iot-space-2xs);
  border: 1px solid var(--board-border);
  border-radius: var(--iot-radius-well);
  background: var(--board-control-bg);
  padding: var(--iot-space-2xs);
}

.control-mode-tabs button {
  min-width: 0;
  border: 0;
  border-radius: var(--iot-radius-action);
  padding: var(--iot-space-sm) var(--iot-space-2xs);
  color: var(--board-text-muted);
  font-size: var(--iot-font-label);
  font-weight: 600;
  line-height: 1.4;
  transition: background-color var(--iot-motion-fast), color var(--iot-motion-fast);
  min-height: var(--iot-target-min);
}

.control-mode-tabs--formats {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.control-mode-tabs button:hover:not(.active) {
  background: var(--surface-control-hover);
  color: var(--text);
}

.control-mode-tabs button.active {
  background: var(--surface-elevated);
  color: var(--accent);
  box-shadow: var(--shadow-raised);
}

.device-preview-box {
  border: 1px solid var(--board-border);
  border-radius: var(--iot-radius-well);
  background: color-mix(in srgb, var(--board-card-bg) 88%, transparent);
  padding: 0.65rem;
}

.device-import-help {
  position: relative;
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 0.45rem;
  border: 1px solid color-mix(in srgb, var(--iot-color-accent) 24%, var(--board-border));
  border-radius: var(--iot-radius-well);
  background: color-mix(in srgb, var(--board-control-bg) 86%, var(--iot-color-accent) 8%);
  color: var(--board-text-muted);
  padding: 0.55rem 0.65rem;
  font-size: var(--iot-font-caption);
  font-weight: 600;
  line-height: 1.2;
}

.device-preview-box__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  color: var(--board-text-muted);
  font-size: var(--iot-font-label);
  font-weight: 600;
}

.device-preview-box__header strong {
  border-radius: var(--iot-radius-pill);
  background: color-mix(in srgb, var(--accent) 14%, var(--board-control-bg));
  color: var(--board-text);
  padding: 0.1rem 0.45rem;
}

.device-preview-list {
  display: grid;
  gap: 0.35rem;
  margin-top: 0.5rem;
}

.device-preview-list--scroll {
  max-height: 18rem;
  overflow-y: auto;
  padding-right: 0.15rem;
}

.device-preview-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.9fr);
  gap: 0.45rem;
  align-items: center;
  border: 1px solid color-mix(in srgb, var(--board-border) 70%, transparent);
  border-radius: var(--iot-radius-action);
  background: var(--board-control-bg);
  padding: 0.4rem 0.5rem;
}

.device-preview-row span {
  min-width: 0;
  color: var(--board-text);
  font-size: var(--iot-font-control);
  font-weight: 600;
}

.device-preview-row small {
  min-width: 0;
  color: var(--board-text-muted);
  font-size: var(--iot-font-caption);
  font-weight: 600;
  text-align: right;
}

.device-preview-row .device-import-row-source {
  grid-column: 1 / -1;
  color: var(--board-text-muted);
  font-size: var(--iot-font-caption);
  font-weight: 500;
}

.device-preview-row.has-error {
  border-color: color-mix(in srgb, var(--danger) 38%, var(--board-border));
  background: color-mix(in srgb, var(--danger) 9%, var(--board-card-bg));
}

.device-preview-row.has-error small {
  color: var(--danger);
}

.device-preview-row.has-warning {
  border-color: color-mix(in srgb, var(--warning) 42%, var(--board-border));
  background: color-mix(in srgb, var(--warning) 11%, var(--board-card-bg));
}

.device-preview-row.has-warning small {
  color: var(--warning);
}

.device-preview-row.has-error,
.device-preview-row.has-warning {
  grid-template-columns: minmax(0, 1fr);
  overflow-wrap: anywhere;
}

.device-preview-row.has-error small,
.device-preview-row.has-warning small {
  overflow-wrap: anywhere;
  text-align: left;
}

.device-preview-more,
.device-preview-empty {
  margin-top: 0.45rem;
  color: var(--board-text-muted);
  font-size: var(--iot-font-caption);
  font-weight: 600;
  text-align: center;
}

.device-runtime-box {
  background: color-mix(in srgb, var(--board-card-bg) 88%, transparent);
  border-color: color-mix(in srgb, var(--accent) 26%, var(--board-border));
  color: var(--board-text);
}

.template-group[open] .template-group__chevron {
  transform: rotate(180deg);
}

.template-group {
  position: relative;
  overflow: visible;
  background: color-mix(in srgb, var(--board-card-bg) 84%, transparent);
  border-color: var(--board-border);
}

.template-group__summary {
  color: var(--board-text);
  border-radius: var(--iot-radius-action);
}

.template-group__summary:hover {
  background: var(--surface-control-hover);
}

.template-group__summary:hover .template-group__label,
.template-group__summary:hover .template-group__chevron {
  color: var(--text);
}

.template-group__label,
.template-group__chevron {
  color: var(--board-text-muted);
}

.template-group__count {
  background: var(--surface-control);
  color: var(--text-muted);
}

.template-group__grid {
  overflow: visible;
}

.template-group__empty {
  color: var(--board-text-muted);
  background: color-mix(in srgb, var(--board-card-bg) 78%, transparent);
  border-color: var(--board-border);
}

.template-reset-dialog {
  background: var(--board-panel-bg);
  border-color: var(--board-border);
  color: var(--board-text);
}

.template-reset-dialog__notice {
  background: color-mix(in srgb, var(--warning) 8%, var(--board-panel-bg));
  border-color: color-mix(in srgb, var(--warning) 28%, var(--board-border));
  color: var(--board-text);
}

.template-reset-dialog__spinner {
  width: 0.9rem;
  height: 0.9rem;
  border: 2px solid color-mix(in srgb, var(--text-on-fill) 38%, transparent);
  border-top-color: var(--text-on-fill);
  border-radius: var(--iot-radius-pill);
  animation: template-reset-spin 0.8s linear infinite;
}

@keyframes template-reset-spin {
  to { transform: rotate(360deg); }
}

.template-card {
  z-index: 0;
  min-height: 5.15rem;
  cursor: pointer;
}

.template-card[draggable="true"] {
  user-select: none;
}

.template-card[draggable="true"]:active {
  cursor: grabbing;
}

/* The inner button receives keyboard focus; the surrounding card only owns hover and selection. */
.template-card > button:focus-visible {
  outline: 2px solid var(--accent-border);
  outline-offset: 3px;
  border-radius: var(--iot-radius-control);
}

.template-card__icon {
  background: var(--surface-control);
}

.template-card__title {
  font-size: var(--iot-font-control);
  font-weight: 600;
  color: var(--board-text);
}

.template-card__stats {
  color: var(--board-text-muted);
}

.template-card__drag-cue {
  flex: 0 0 auto;
  color: var(--board-text-muted);
  font-size: 0.9rem;
  line-height: 1;
  opacity: 0.72;
}

.template-card__pill {
  background: var(--board-control-bg);
  color: var(--board-text-muted);
}

.template-card__actions {
  border-color: var(--board-border);
}

.template-preview {
  --template-preview-card-bg: var(--board-card-bg);
  --template-preview-control-bg: var(--board-control-bg);
  --template-preview-border: var(--board-border);
  --template-preview-text: var(--board-text);
  --template-preview-muted: var(--board-text-muted);

  position: fixed;
  z-index: var(--z-board-panel-above-alert);
  box-sizing: border-box;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 0.75rem;
  border: 1px solid var(--template-preview-border);
  border-radius: var(--iot-radius-well);
  background: color-mix(in srgb, var(--template-preview-card-bg) 96%, transparent);
  color: var(--template-preview-text);
  backdrop-filter: blur(16px);
  box-shadow: var(--shadow-elevated);
}

.template-preview__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
}

.template-preview__eyebrow {
  margin: 0;
  color: var(--template-preview-muted);
  font-size: var(--iot-font-caption);
  font-weight: 600;
}

.template-preview__title {
  margin: 0.1rem 0 0;
  color: var(--template-preview-text);
  font-size: var(--iot-font-base);
  font-weight: 600;
}

.template-preview__close {
  display: inline-flex;
  width: 1.55rem;
  height: 1.55rem;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--template-preview-border);
  border-radius: var(--iot-radius-action);
  background: var(--template-preview-control-bg);
  color: var(--template-preview-muted);
  /* Declared per-element, unlike the board and dialog rules, because this popover is
     `<Teleport to="body">`: it is inside neither `.iot-board`, `.board-timeline-host`, nor
     `.iot-dialog-overlay`, so the only ancestor a scoped rule could use is `body` — the blanket
     selector this codebase deliberately avoids. */
  cursor: pointer;
}

.template-preview__close:hover {
  color: var(--template-preview-text);
}

.template-preview__description {
  display: -webkit-box;
  margin: 0.55rem 0 0;
  overflow: hidden;
  color: var(--template-preview-muted);
  font-size: var(--iot-font-caption);
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.template-preview__meta {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.75fr);
  gap: 0.45rem;
  margin-top: 0.65rem;
}

.template-preview__meta > div {
  min-width: 0;
  border: 1px solid var(--template-preview-border);
  border-radius: var(--iot-radius-action);
  background: var(--template-preview-control-bg);
  padding: 0.45rem;
}

.template-preview__meta span,
.template-preview__section-label {
  display: block;
  color: var(--template-preview-muted);
  font-size: var(--iot-font-label);
  font-weight: 600;
}

.template-preview__meta strong {
  display: block;
  min-width: 0;
  margin-top: 0.15rem;
  overflow: hidden;
  color: var(--template-preview-text);
  font-size: var(--iot-font-control);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.template-preview__sections {
  display: grid;
  gap: 0.5rem;
  margin-top: 0.65rem;
}

.template-preview__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.3rem;
  margin-top: 0.25rem;
}

.template-preview__chip,
.template-preview__empty {
  max-width: 100%;
  overflow-wrap: anywhere;
  border-radius: var(--iot-radius-pill);
  padding: 0.18rem 0.45rem;
  background: var(--template-preview-control-bg);
  color: var(--template-preview-text);
  font-size: var(--iot-font-caption);
  white-space: normal;
}

.template-preview__empty {
  color: var(--template-preview-muted);
}

/* Input focus animation */
input:focus,
select:focus {
  animation: focus-ring 0.3s ease-out;
}

@keyframes focus-ring {
  0% {
    box-shadow: 0 0 0 0 color-mix(in srgb, var(--text-muted) 40%, transparent);
  }
  100% {
    box-shadow: 0 0 0 4px color-mix(in srgb, var(--text-muted) 10%, transparent);
  }
}

/* Button hover effects */
button:not(:disabled):hover {
  transform: translateY(-1px);
}

button:not(:disabled):active {
  transform: translateY(0);
}

/* Card hover effects */
.group:hover .group-hover\:scale-105 {
  transform: scale(1.05);
}

/* Fade in animation */
@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.fade-in {
  animation: fadeIn 0.3s ease-out;
}

/* Backdrop blur support */
@supports (-webkit-backdrop-filter: blur(20px)) {
  .backdrop-blur-sm {
    -webkit-backdrop-filter: blur(20px);
    backdrop-filter: blur(20px);
  }
}
</style>
