<script setup lang="ts">
import HintTooltip from '@/components/common/HintTooltip.vue'
import { ref, reactive, computed, onBeforeUnmount, onMounted, useAttrs, watch } from 'vue'
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
  type DeviceRuntimeConfig
} from '@/utils/deviceRuntime'
import { buildSpecFormula, isSpecConditionVariableSourceUnresolved, specFormulaKindFromTemplate } from '@/utils/spec'
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
import { localizedErrorMessage } from '@/utils/userMessage'
import { useModalAccessibility } from '@/composables/useModalAccessibility'
import { REQUEST_LIMITS } from '@/constants/requestLimits'
import { COLLAPSED_PANEL_RAIL_CSS } from '@/constants/boardLayout'
import { formatBuiltInModelToken } from '@/utils/modelTokenDisplay'
import { notifyBlocked, notifyError, notifySuccess } from '@/utils/feedback'
import { useRovingTablist } from '@/composables/useRovingTablist'
import InfoTooltip from '@/components/common/InfoTooltip.vue'

defineOptions({ inheritAttrs: false })
const attrs = useAttrs()

// Element-Plus typings vary by version; we use an `any` alias to keep runtime behavior (e.g. `center`) without TS errors.
const { t, locale } = useI18n()

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

type MutationCompletion = (saved: boolean) => void

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
  'open-rule-builder': []
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
const creatingMultipleDevices = ref(false)
const creatingSpecification = ref(false)

const deviceCreateMode = ref<DeviceCreateMode>('single')
const batchDeviceForm = reactive({
  type: '',
  prefix: '',
  count: 3
})
const importDeviceForm = reactive({
  text: ''
})

// Debounced import text for performance optimization (avoids re-parsing on every keystroke)
const debouncedImportText = ref('')
let importTextDebounceTimer: ReturnType<typeof setTimeout> | null = null
const IMPORT_TEXT_DEBOUNCE_MS = 300

watch(() => importDeviceForm.text, (newText) => {
  if (importTextDebounceTimer) {
    clearTimeout(importTextDebounceTimer)
  }
  importTextDebounceTimer = setTimeout(() => {
    debouncedImportText.value = newText
  }, IMPORT_TEXT_DEBOUNCE_MS)
}, { immediate: true })

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
  if (importTextDebounceTimer) {
    clearTimeout(importTextDebounceTimer)
    importTextDebounceTimer = null
  }
  importDeviceForm.text = text
  debouncedImportText.value = text
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
}

const DEVICE_IMPORT_TEMPLATE_KEYS = new Set(['template', 'templatename', 'type'])
const MAX_DEVICE_IMPORT_BYTES = 4 * 1024 * 1024
const MAX_TEMPLATE_IMPORT_BYTES = 512 * 1024
const DEVICE_IMPORT_NAME_KEYS = new Set(['name', 'label', 'devicename'])
const DEVICE_IMPORT_TEMPLATE_ALIASES = ['template', 'templateName', 'type'] as const
const DEVICE_IMPORT_NAME_ALIASES = ['name', 'label', 'deviceName'] as const
const DEVICE_IMPORT_JSON_KEYS = new Set([
  'template', 'templateName', 'type',
  'name', 'label', 'deviceName',
  'state', 'currentStateTrust', 'currentStatePrivacy', 'variables', 'privacies'
])
const DEVICE_IMPORT_VARIABLE_KEYS = new Set(['name', 'value', 'trust'])
const DEVICE_IMPORT_PRIVACY_KEYS = new Set(['name', 'privacy'])

const normalizeImportKey = (value: string) =>
  normalizeName(value).toLowerCase().replace(/[\s_-]/g, '')

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

const parseCsvImportLine = (line: string): { columns: string[]; error?: string } => {
  const columns: string[] = []
  let current = ''
  let quoted = false

  for (let i = 0; i < line.length; i += 1) {
    const char = line[i]
    if (char === '"') {
      if (quoted && line[i + 1] === '"') {
        current += '"'
        i += 1
      } else {
        quoted = !quoted
      }
      continue
    }

    if (!quoted && (char === ',' || char === '\t')) {
      columns.push(current.trim())
      current = ''
      continue
    }

    current += char
  }

  if (quoted) {
    return { columns: [], error: t('app.deviceImportCsvQuoteError') }
  }

  columns.push(current.trim())
  return { columns }
}

const isDeviceImportHeader = (columns: string[]) =>
  columns.length >= 2
  && DEVICE_IMPORT_TEMPLATE_KEYS.has(normalizeImportKey(columns[0]))
  && DEVICE_IMPORT_NAME_KEYS.has(normalizeImportKey(columns[1]))

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
  template?.manifest?.Description || t('app.noDescription')

const getTemplateList = (template: any, field: string, nameField = 'Name') => {
  const value = template?.manifest?.[field]
  if (!Array.isArray(value)) return []
  return value
    .map((item: any) => typeof item === 'string' ? item : item?.[nameField])
    .filter((item: unknown): item is string => typeof item === 'string' && item.trim().length > 0)
}

const previewItems = (items: string[], limit = 5) => items.slice(0, limit)

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
    pinnedTemplatePreviewId.value = null
    return
  }
  updateTemplatePreviewPosition(event)
  pinnedTemplatePreviewId.value = key
}

const closeTemplatePreview = () => {
  pinnedTemplatePreviewId.value = null
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
    const rows: DeviceImportRow[] = []
    if (text.startsWith('[') || text.startsWith('{')) {
      let parsed: unknown
      try {
        parsed = JSON.parse(text)
      } catch {
        return [makeImportError('#1', t('app.deviceImportInvalidJson'))]
      }

      const items = Array.isArray(parsed) ? parsed : [parsed]
      items.forEach((item: any, index: number) => {
        const source = `#${index + 1}`
        if (!item || typeof item !== 'object' || Array.isArray(item)) {
          rows.push(makeImportError(source, t('app.deviceImportJsonObjectRequired')))
          return
        }
        const unknownField = findUnknownImportField(item, DEVICE_IMPORT_JSON_KEYS)
        if (unknownField) {
          rows.push(makeImportError(source, t('app.deviceImportUnknownField', { source, field: unknownField })))
          return
        }
        const templateResult = readJsonImportAlias(item, DEVICE_IMPORT_TEMPLATE_ALIASES, source)
        if (templateResult.error) {
          rows.push(makeImportError(source, templateResult.error))
          return
        }
        const nameResult = readJsonImportAlias(item, DEVICE_IMPORT_NAME_ALIASES, source)
        if (nameResult.error) {
          rows.push(makeImportError(source, nameResult.error))
          return
        }
        const runtimeResult = parseImportedRuntime(item, source)
        if (runtimeResult.error) {
          rows.push(makeImportError(source, runtimeResult.error))
          return
        }
        rows.push({
          source,
          templateName: templateResult.value,
          name: nameResult.value,
          runtime: runtimeResult.runtime
        })
      })
    } else {
      text.split(/\r?\n/).forEach((line, index) => {
        const trimmed = line.trim()
        if (!trimmed) return
        const parsedLine = parseCsvImportLine(trimmed)
        if (parsedLine.error) {
          rows.push(makeImportError(`#${index + 1}`, parsedLine.error))
          return
        }
        const columns = parsedLine.columns
        if (rows.length === 0 && isDeviceImportHeader(columns)) {
          return
        }
        if (columns.length > 2 && columns.slice(2).some(column => column.trim())) {
          rows.push(makeImportError(`#${index + 1}`, t('app.deviceImportCsvColumnCount')))
          return
        }
        const [templateName, name] = columns
        rows.push({ source: `#${index + 1}`, templateName, name })
      })
    }

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
  debouncedImportText.value !== importDeviceForm.text
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

const formatImportedEnvironmentConflict = (conflict: EnvironmentPatchConflict) =>
  t('app.deviceImportEnvironmentConflict', {
    name: conflict.name,
    field: environmentConflictFieldLabel(conflict.field),
    first: conflict.firstSource,
    second: conflict.secondSource
  })

const importedDevicesHaveErrors = computed(() =>
  parsedImportedDevices.value.some((item: any) => item.error)
  || importedEnvironmentMerge.value.conflicts.length > 0
)

const deviceImportPlaceholder = computed(() => {
  const jsonExample = JSON.stringify([{
    template: 'Motion Detector',
    name: 'entry_motion',
    variables: [{ name: 'motion', value: 'active', trust: 'trusted' }],
    privacies: [{ name: 'motion', privacy: 'private' }]
  }])
  return `${jsonExample}\n${t('app.deviceImportCsvExamplePrefix')}\nAir Conditioner,living_ac`
})

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
  if (draft.targetType !== 'api' && !draft.value?.trim()) return t('app.enterValue')
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

const formatConditionPropertyLabel = (condition: Pick<SpecCondition, 'deviceId' | 'targetType' | 'key' | 'propertyScope'>) => {
  const template = getDeviceTemplate(condition.deviceId)
  if (condition.targetType === 'state') return t('app.state')
  if ((condition.targetType === 'trust' || condition.targetType === 'privacy')
    && condition.propertyScope === 'state') {
    const modes = getDeviceManifestForCondition(condition.deviceId)?.Modes || []
    return modes.length === 1
      ? t('app.currentStateProperty')
      : t('app.currentModeStateProperty', { mode: formatTemplateModelToken(template, condition.key) })
  }
  return condition.key ? formatTemplateModelToken(template, condition.key) : t('app.value')
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

const availableVariableValues = computed(() => {
  if (!editingConditionData.deviceId || !editingConditionData.key) return []
  const template = getDeviceTemplate(editingConditionData.deviceId)
  const variables = template?.manifest?.InternalVariables
  if (!Array.isArray(variables)) return []
  const variable = variables.find((v: any) => v?.Name === editingConditionData.key)
  return Array.isArray(variable?.Values) ? variable.Values.map((value: unknown) => String(value)) : []
})

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

const formatConditionValue = (value: unknown, deviceId?: string) =>
  hasConditionValue(value)
    ? formatTemplateModelToken(getDeviceTemplate(deviceId || ''), value)
    : '*'

const formatEditingConditionModelToken = (value: unknown) =>
  formatTemplateModelToken(getDeviceTemplate(editingConditionData.deviceId || ''), value)

/*
 * Derived from the template, not from the formula text this same component builds.
 *
 * It used to look for a `CTLSPEC`/`LTLSPEC` prefix, which `buildSpecFormula` never writes — it emits
 * `CTL AG(...)` / `LTL G(...)`, and NuSMV's keyword form appears only in a trace's `checkedExpression`.
 * So this chip fell through to the generic "Model" label for all seven templates, sitting immediately
 * beside a formula preview that begins with the very word it failed to read.
 */
const specFormulaKind = computed(
  () => specFormulaKindFromTemplate(specForm.templateId) ?? t('app.modelFormulaKind')
)

// Update model formula preview based on conditions
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

// Generate natural language specification description
const naturalLanguageRule = computed(() => {
  if (!currentTemplateDetail.value || specForm.templateType === '') {
    return t('app.specPreviewConfigureConditions')
  }

  const template = currentTemplateDetail.value
  const type = template.type

  const conditionSubject = (condition: SpecCondition): string => {
    const deviceName = condition.deviceLabel || condition.deviceId || t('app.device')
    const keyName = formatConditionPropertyLabel(condition)
    switch (condition.targetType) {
      case 'variable':
        // The plain-language sentence must name the same question the formula compiles, otherwise a
        // reader checking one against the other cannot tell which one their specification is.
        if (condition.variableSource === 'environment') {
          return t('app.specPreviewVariableEnvironmentSubject', { key: keyName })
        }
        if (condition.variableSource === 'reported') {
          return t('app.specPreviewVariableReportedSubject', { key: keyName, device: deviceName })
        }
        return t('app.specPreviewVariableSourceUnresolvedSubject', { key: keyName, device: deviceName })
      case 'mode':
        return t('app.specPreviewModeSubject', { key: keyName, device: deviceName })
      case 'state':
        return t('app.specPreviewStateSubject', { device: deviceName })
      case 'api':
        return t('app.specPreviewApiSubject', { key: keyName, device: deviceName })
      case 'trust':
        return t('app.specPreviewTrustSubject', { key: keyName, device: deviceName })
      case 'privacy':
        return t('app.specPreviewPrivacySubject', { key: keyName, device: deviceName })
      default:
        return t('app.specPreviewPropertySubject', { key: keyName, device: deviceName })
    }
  }

  // Helper to format conditions in natural language
  const formatConditions = (conditions: SpecCondition[]): string => {
    if (conditions.length === 0) return ''

    return conditions.map(c => {
      const relationText = getRelationLabel(c.relation || '=')
      const valueText = hasConditionValue(c.value) ? ` ${relationText} "${formatConditionValue(c.value, c.deviceId)}"` : ''
      return `${conditionSubject(c)}${valueText}`
    }).join(` ${t('app.specPreviewAnd')} `)
  }

  const aConditions = formatConditions(specForm.aConditions)
  const ifConditions = formatConditions(specForm.ifConditions)
  const thenConditions = formatConditions(specForm.thenConditions)

  // Generate natural language based on template type
  switch (type) {
    case 'always':
      if (aConditions) {
        return t('app.specPreviewAlways', { conditions: aConditions })
      }
      return t('app.specPreviewConfigureACondition')

    case 'eventually':
      if (aConditions) {
        return t('app.specPreviewEventually', { conditions: aConditions })
      }
      return t('app.specPreviewConfigureACondition')

    case 'never':
      if (aConditions) {
        return t('app.specPreviewNever', { conditions: aConditions })
      }
      return t('app.specPreviewConfigureACondition')

    case 'immediate':
      if (ifConditions && thenConditions) {
        return t('app.specPreviewImmediate', { ifConditions, thenConditions })
      } else if (ifConditions) {
        return t('app.specPreviewImmediatePartial', { ifConditions })
      }
      return t('app.specPreviewConfigureIfThen')

    case 'response':
      if (ifConditions && thenConditions) {
        return t('app.specPreviewResponse', { ifConditions, thenConditions })
      } else if (ifConditions) {
        return t('app.specPreviewResponsePartial', { ifConditions })
      }
      return t('app.specPreviewConfigureIfThen')

    case 'persistence':
      if (ifConditions && thenConditions) {
        return t('app.specPreviewPersistence', { ifConditions, thenConditions })
      } else if (ifConditions) {
        return t('app.specPreviewPersistencePartial', { ifConditions })
      }
      return t('app.specPreviewConfigureIfThen')

    case 'safety':
      if (aConditions) {
        return t('app.specPreviewSafety', { conditions: aConditions })
      }
      return t('app.specPreviewConfigureACondition')

    default:
      return t('app.specPreviewConfigureConditions')
  }
})

// Precomputed formatted condition data for v-for loops (optimization)
const formattedAConditions = computed(() =>
  specForm.aConditions.map(condition => ({
    ...condition,
    deviceLabel: getDeviceLabel(condition.deviceId),
    propertyLabel: formatConditionPropertyLabel(condition),
    formattedValue: formatConditionValue(condition.value, condition.deviceId),
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
    formattedValue: formatConditionValue(condition.value, condition.deviceId),
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
    formattedValue: formatConditionValue(condition.value, condition.deviceId),
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
      complete: resolve
    })
  })
  creatingSpecification.value = false
  if (saved) resetSpecForm()
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
  // Name problems are reported inline next to the field, and the submit button is disabled
  // while either holds, so there is nothing left to announce here.
  if (!deviceForm.name.trim() || singleDeviceNameConflict.value) return

  if (!deviceForm.type) {
    notifyBlocked(t('app.selectDeviceTemplate'))
    return
  }

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

  // Emit device creation event with template.
  // 成功提示由父组件在保存成功后弹出：emit 不会 await 父组件的异步保存，
  // 若在此提前报成功，父组件保存失败并回滚时会同时出现「Device added」和保存失败提示。
  creatingSingleDevice.value = true
  const saved = await new Promise<boolean>(resolve => {
    emit('create-device', {
      template,
      customName: deviceForm.name,
      runtime: buildRuntimeConfig(template as DeviceTemplate),
      complete: resolve
    })
  })
  creatingSingleDevice.value = false
  if (saved) {
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
    : !importDeviceForm.text.trim() ? 'import text is empty'
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
  if (!file) return
  if (!ensureWritable()) {
    target.value = ''
    return
  }
  try {
    if (file.size > MAX_DEVICE_IMPORT_BYTES) {
      notifyError(t('app.importFileTooLarge', { size: '4 MiB' }))
      return
    }
    /*
     * Invalidate the preview BEFORE the first await, not after it. Placed after the size check, which is
     * synchronous: a rejected oversized file then leaves whatever the user had pasted before intact.
     *
     * `setImportTextImmediately` below closes the debounce window, but it can only run once
     * `await file.text()` resolves — and reading a file is asynchronous. In that gap the preview, the
     * parsed counts and the create button all still describe the PREVIOUS content, and the button is
     * still enabled from it. A click landing there imports the old payload.
     *
     * Measured from the CI artifact for "imports devices from pasted JSON and selected CSV", which
     * failed two consecutive nights: the JSON payload was imported a second time and neither CSV device
     * appeared, while the snapshot taken at the timeout shows the CSV did land — just after the click
     * had already read the JSON state. Both payloads parse to exactly two devices, so neither
     * `toBeEnabled()` nor the button's own label could tell them apart.
     *
     * Everything above this line is synchronous, so the clear lands inside the `change` dispatch: no
     * text means no parsed devices, means a disabled button, and a caller waiting on the button waits
     * through the empty window rather than observing the previous payload's enabled state.
     */
    setImportTextImmediately('')
    // Immediately, not debounced: see `setImportTextImmediately`. Deferring a file's contents left the
    // create button enabled and describing the file the user had just replaced.
    setImportTextImmediately(await file.text())
  } catch (error) {
    console.error('Failed to read device import file:', error)
    notifyError(t('app.invalidJsonFile'))
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

// Mirrors SystemInspector's tab strip so both side panels expose the same tablist
// semantics and keyboard model.
/*
 * One active-tab treatment, not four.
 *
 * Each tab used to carry its own `activeClass` — orange, purple, blue, red — so "this tab is selected" was
 * said in four different colours. The state is identical in every case; only the section differs, and the
 * label already says which section. That is the "do not spend a new hue on a new category" rule in
 * `frontend-ui-conventions.md` §4, and the per-tab property was the mechanism that made it easy to break.
 *
 * `--accent-fill` is the fill half of the accent role, so white ink on it is legible in both themes
 * (`--accent` alone measures 2.54:1 in dark). Matches SystemInspector's strip exactly, which is the point:
 * the two side panels should not look like two products.
 */
const controlTabs = computed(() => [
  { id: 'templates' as const, label: t('app.templates'), icon: 'inventory_2' },
  { id: 'devices' as const, label: t('app.devices'), icon: 'devices' },
  { id: 'rules' as const, label: t('app.rules'), icon: 'rule' },
  { id: 'specs' as const, label: t('app.specifications'), icon: 'verified' }
])

/** The selected-tab treatment, shared by every tab in the strip. */
const CONTROL_TAB_ACTIVE_CLASS = 'bg-[color:var(--accent-fill)] text-white shadow-sm'

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

const refreshTemplateCatalogForReconciliation = async (): Promise<DeviceTemplate[] | null> => {
  try {
    const current = await boardApi.getDeviceTemplates()
    emit('replace-template-catalog', current)
    return current
  } catch (error) {
    console.error('Failed to reconcile device type catalog:', error)
    return null
  }
}

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

  if (file.size > MAX_TEMPLATE_IMPORT_BYTES) {
    notifyError(t('app.importFileTooLarge', { size: '512 KiB' }))
    target.value = ''
    return
  }

  let manifest: DeviceTemplate['manifest']
  let requestedName: string
  try {
    const text = await file.text()
    manifest = JSON.parse(text)
    requestedName = String(manifest?.Name || '').trim()
  } catch (error: any) {
    const message = templateMutationErrorMessage(error, t('app.invalidJsonFile'))
    notifyError(message)
    target.value = ''
    return
  }

  // The scene can enter playback/replacement while the file is being parsed.
  // Re-check immediately before starting the network mutation so a late file
  // read cannot write after the UI became read-only.
  if (admittedReadOnlyEpoch !== readOnlyEpoch || !ensureWritable()) {
    target.value = ''
    return
  }

  await runBoardMutation(async () => {
    try {
      await boardApi.addDeviceTemplate({ name: requestedName, manifest })
      const current = await boardApi.getDeviceTemplates()
      emit('replace-template-catalog', current)
      notifySuccess(t('app.templateImportedSuccessfully'))
    } catch (error: any) {
      if (!isDefinitiveTemplateMutationRejection(error)) {
        const current = await refreshTemplateCatalogForReconciliation()
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
        const message = templateMutationErrorMessage(error, t('app.invalidJsonFile'))
        notifyError(message)
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

const openRuleBuilder = () => {
  if (!ensureWritable()) return
  emit('open-rule-builder')
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
  closeSpecDialog()
  if (!isDeletingTemplate.value) closeTemplateDeleteConfirm(true)
  if (!isResettingDefaultTemplates.value) closeResetDefaultsConfirm(true)
}, { flush: 'sync' })

</script>

<template>
  <!-- Collapsed width comes from COLLAPSED_PANEL_RAIL_CSS — see the note in SystemInspector, and the
       rationale on the constant itself. -->
  <aside
    v-bind="attrs"
    data-testid="control-center"
    class="absolute left-0 top-0 bottom-0 modern-panel board-side-panel z-40 flex flex-col overflow-hidden border-r border-white/20 shadow-xl transition-all duration-300 ease-in-out"
    :class="isCollapsed ? 'is-collapsed' : 'is-expanded'"
    :style="{ width: isCollapsed ? COLLAPSED_PANEL_RAIL_CSS : panelWidth }"
  >
    <!-- 顶部标题区域 -->
    <div
      class="board-panel-header relative overflow-hidden border-b"
      :class="isCollapsed ? 'p-2.5' : 'p-4'"
    >
      <div v-if="!isCollapsed" class="relative flex items-center justify-between">
        <div class="flex items-center gap-3">
          <div class="w-10 h-10 bg-slate-100 rounded-xl flex items-center justify-center">
            <span class="material-symbols-outlined text-slate-600 text-xl">dashboard</span>
          </div>
          <div>
            <h2 class="board-panel-title text-sm font-bold tracking-wide">{{ t('app.controlCenter') }}</h2>
            <p class="board-panel-subtitle text-xs">{{ t('app.deviceManagement') }}</p>
          </div>
        </div>
        <HintTooltip :content="t('app.collapse')">
          <button
            type="button"
            @click="togglePanel"
            class="h-11 w-11 shrink-0 board-chip-neutral hover:board-control-hover rounded-lg flex items-center justify-center transition-all hover:scale-105"
            :aria-label="t('app.collapse')"
          >
            <span class="material-symbols-outlined text-slate-600 text-base transition-transform duration-200" aria-hidden="true">dock_to_left</span>
          </button>
        </HintTooltip>
      </div>
      <div v-else class="flex items-center justify-center">
        <HintTooltip :content="t('app.expand')">
          <button
            type="button"
            @click="togglePanel"
            class="h-11 w-11 shrink-0 board-chip-neutral hover:board-control-hover rounded-xl flex items-center justify-center transition-all hover:scale-105"
            :aria-label="t('app.expand')"
          >
            <span class="material-symbols-outlined text-slate-600 text-base" aria-hidden="true">dock_to_right</span>
          </button>
        </HintTooltip>
      </div>
    </div>

    <!-- Section tabs (only when expanded) -->
    <div v-if="!isCollapsed" class="board-panel-tabs px-4 py-3 border-b">
      <div
        class="board-segmented grid grid-cols-4 gap-2 p-1 rounded-xl border shadow-sm"
        role="tablist"
        :aria-label="t('app.boardTools')"
      >
        <!-- No tooltip: the tab prints `tab.label` itself, so a hint repeating it is the duplicate-feedback
             case the project's own rule forbids. -->
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
            :class="[
   'min-w-0 min-h-11 py-2.5 rounded-lg text-xs font-semibold uppercase tracking-wider transition-all duration-200 flex flex-col items-center gap-1',
   activeSection === tab.id
   ? CONTROL_TAB_ACTIVE_CLASS
   : 'board-text-muted hover:board-control-hover hover:board-text-strong'
   ]"
          >
            <span class="material-symbols-outlined text-sm" aria-hidden="true">{{ tab.icon }}</span>
            <span class="w-full truncate px-0.5 text-center text-[length:var(--iot-font-min)]">{{ tab.label }}</span>
          </button>
      </div>
    </div>

    <div
      v-if="!isCollapsed"
      class="board-panel-body flex-1 iot-scroll-region transition-all duration-300 max-h-[calc(100vh-140px)] p-2"
    >
      <!-- Devices -->
      <div
        v-if="activeSection === 'devices'"
        id="control-section-devices"
        role="tabpanel"
        aria-labelledby="control-tab-devices"
        data-testid="control-section-devices"
      >
        <details class="group mb-3 rounded-xl bg-white shadow-sm border border-slate-200 overflow-hidden" open>
        <summary class="flex items-center justify-between p-4 cursor-pointer hover:board-chip-accent transition-all list-none select-none">
          <div class="flex items-center gap-3">
            <div class="w-10 h-10 bg-[color:var(--accent-fill)] rounded-xl flex items-center justify-center">
              <span aria-hidden="true" class="material-symbols-outlined text-white text-lg">add_circle</span>
            </div>
            <div>
              <span class="text-sm font-bold text-slate-800">{{ t('app.deviceManager') }}</span>
              <p class="text-xs text-slate-500">{{ t('app.addAndManageDevices') }}</p>
            </div>
          </div>
          <span class="material-symbols-outlined text-slate-500 transition-transform group-open:rotate-180 text-lg">expand_more</span>
        </summary>

        <div class="px-3 pb-4 space-y-3 bg-slate-50/50 pt-2">
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
              <label class="block text-[length:var(--iot-font-min)] font-bold text-slate-500 mb-1 uppercase tracking-wide">{{ t('app.type') }}</label>
              <div class="relative">
                <span class="absolute left-2.5 top-1/2 -translate-y-1/2 material-symbols-outlined text-slate-500 text-xs">devices</span>
                <select
                  v-model="deviceForm.type"
                  data-testid="single-device-template"
                  class="w-full min-h-11 bg-white border-2 border-slate-200 rounded-lg px-8 py-2 text-xs text-slate-700 focus:border-[color:var(--accent-border)] focus:ring-2 focus:ring-[color:var(--accent-border)] transition-all appearance-none shadow-sm"
                  :class="isTemplateSelectorDisabled ? 'cursor-not-allowed opacity-70' : 'cursor-pointer'"
                  :disabled="isTemplateSelectorDisabled"
                  :title="templateSelectorTitle"
                >
                  <option v-if="props.templatesLoading" value="">{{ t('app.loadingDeviceTemplates') }}</option>
                  <option v-else-if="deviceTypes.length === 0" value="">{{ t('app.none') }}</option>
                  <option v-else value="" disabled hidden>{{ t('app.selectDeviceTemplate') }}</option>
                  <option v-for="type in deviceTypes" :key="type" :value="type">{{ type }}</option>
                </select>
              </div>
            </div>

            <div class="relative">
              <label class="block text-[length:var(--iot-font-min)] font-bold text-slate-500 mb-1 uppercase tracking-wide">{{ t('app.deviceName') }}</label>
              <div class="relative">
                <span class="absolute left-2.5 top-1/2 -translate-y-1/2 material-symbols-outlined text-slate-500 text-xs">badge</span>
                <input
                  v-model="deviceForm.name"
                  data-testid="single-device-name"
                  class="w-full min-h-11 bg-white border-2 rounded-lg px-8 py-2 text-xs text-slate-700 focus:ring-2 placeholder:text-slate-400 transition-all shadow-sm"
                  :class="singleDeviceNameConflict
 ? 'border-[color:var(--danger-border)] focus:border-[color:var(--accent-border)] focus:ring-[color:var(--accent-border)]'
 : 'border-slate-200 focus:border-[color:var(--accent-border)] focus:ring-[color:var(--accent-border)]'"
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
                class="mt-1 text-[length:var(--iot-font-min)] font-semibold board-text-danger"
                data-testid="single-device-name-conflict"
              >
                {{ t('app.deviceNameAlreadyExists') }}
              </p>
            </div>

            <details
              v-if="hasSingleDeviceRuntimeFields"
              data-testid="single-device-runtime"
              class="device-runtime-box rounded-xl border border-[color:var(--accent-border)] bg-white/80 p-3 shadow-sm"
            >
              <summary
                data-testid="single-device-runtime-toggle"
                class="flex cursor-pointer select-none items-center justify-between gap-2 text-[11px] font-bold text-slate-600"
              >
                <span class="inline-flex items-center gap-1.5">
                  <span class="material-symbols-outlined text-sm board-text-accent" aria-hidden="true">tune</span>
                  {{ t('app.advancedInitialValuesOverrides') }}
                </span>
                <span class="material-symbols-outlined text-sm text-slate-400 transition-transform group-open:rotate-180" aria-hidden="true">expand_more</span>
              </summary>
              <p class="mt-2 text-[length:var(--iot-font-min)] leading-relaxed text-slate-500">
                {{ t('app.initialValuesHint') }}
              </p>

              <div v-if="selectedTemplateHasModes" class="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-3">
                <label class="min-w-0">
                  <span class="mb-1 block text-[length:var(--iot-font-min)] font-bold uppercase tracking-wide text-slate-500">{{ t('app.initialState') }}</span>
                  <select
                    v-model="singleDeviceRuntime.state"
                    data-testid="single-device-state"
                    class="w-full rounded-lg border-2 border-slate-200 bg-white px-2 py-2 text-xs text-slate-700 shadow-sm transition-all focus:border-[color:var(--accent-border)] focus:ring-2 focus:ring-[color:var(--accent-border)]"
                  >
                    <option v-for="state in formattedSelectedWorkingStates" :key="state.name" :value="state.name">{{ state.label }}</option>
                  </select>
                </label>

                <label class="min-w-0">
                  <span class="mb-1 block text-[length:var(--iot-font-min)] font-bold uppercase tracking-wide text-slate-500">{{ t('app.stateTrust') }}</span>
                  <select
                    v-model="singleDeviceRuntime.currentStateTrust"
                    data-testid="single-device-state-trust"
                    class="w-full rounded-lg border-2 border-slate-200 bg-white px-2 py-2 text-xs text-slate-700 shadow-sm transition-all focus:border-[color:var(--accent-border)] focus:ring-2 focus:ring-[color:var(--accent-border)]"
                  >
                    <option value="">{{ t('app.useTemplateDefaultWithValue', { value: t(`app.${findTemplateStateTrust(selectedDeviceTemplate, singleDeviceRuntime.state) || 'trusted'}`) }) }}</option>
                    <option v-for="trust in TRUST_OPTIONS" :key="trust" :value="trust">{{ t(`app.${trust}`) }}</option>
                  </select>
                </label>

                <label class="min-w-0">
                  <span class="mb-1 block text-[length:var(--iot-font-min)] font-bold uppercase tracking-wide text-slate-500">{{ t('app.statePrivacy') }}</span>
                  <select
                    v-model="singleDeviceRuntime.currentStatePrivacy"
                    data-testid="single-device-state-privacy"
                    class="w-full rounded-lg border-2 border-slate-200 bg-white px-2 py-2 text-xs text-slate-700 shadow-sm transition-all focus:border-[color:var(--accent-border)] focus:ring-2 focus:ring-[color:var(--accent-border)]"
                  >
                    <option value="">{{ t('app.useTemplateDefaultWithValue', { value: t(`app.${findTemplateStatePrivacy(selectedDeviceTemplate, singleDeviceRuntime.state) || 'public'}`) }) }}</option>
                    <option v-for="privacy in PRIVACY_OPTIONS" :key="privacy" :value="privacy">{{ t(`app.${privacy}`) }}</option>
                  </select>
                </label>
              </div>

              <div v-if="formattedSelectedInternalVariables.length > 0" class="mt-3 space-y-2">
                <div
                  v-for="variable in formattedSelectedInternalVariables"
                  :key="variable.Name"
                  class="rounded-lg border border-slate-200 bg-slate-50/80 p-2"
                >
                  <div class="mb-2 flex items-center justify-between gap-2">
                    <span class="truncate text-[11px] font-bold text-slate-700" :title="variable.formattedName">{{ variable.formattedName }}</span>
                    <span v-if="templateVariableUsesNumericBounds(variable)" class="text-[length:var(--iot-font-min)] font-semibold text-slate-500">
                      {{ variableInputPlaceholder(variable) }}
                    </span>
                  </div>

                  <div class="grid grid-cols-[minmax(0,1fr)_5.8rem_5.8rem] gap-2">
                    <label class="min-w-0">
                      <span class="mb-1 block text-[length:var(--iot-font-min)] font-bold uppercase text-slate-500">{{ t('app.variableValue') }}</span>
                      <!-- A variable every state constrains is the state's consequence, not an instance
                           choice: see `templateVariableIsStateDerived`. Editing it here would be discarded
                           one step into the model, and the writers refuse a pair that disagrees. -->
                      <div
                        v-if="templateVariableIsStateDerived(selectedDeviceTemplate, variable.Name)"
                        :data-testid="`single-device-variable-derived-${variable.Name}`"
                        class="flex min-w-0 items-center gap-2 rounded-lg border border-dashed border-slate-200 bg-slate-50 px-2 py-1.5"
                      >
                        <span class="min-w-0 break-words text-xs font-medium text-slate-700">
                          {{ formatTemplateModelToken(selectedDeviceTemplate, singleDeviceRuntime.variables[variable.Name]) }}
                        </span>
                        <span class="shrink-0 text-[length:var(--iot-font-min)] text-slate-500">{{ t('app.variableFollowsState') }}</span>
                      </div>
                      <select
                        v-else-if="templateVariableHasEnumValues(variable)"
                        v-model="singleDeviceRuntime.variables[variable.Name]"
                        :data-testid="`single-device-variable-${variable.Name}`"
                        class="w-full min-w-0 rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-xs text-slate-700"
                      >
                        <option value="">{{ t('app.useTemplateDefaultWithValue', { value: variable.formattedDefaultValue }) }}</option>
                        <option v-for="val in variable.formattedValues" :key="val.raw" :value="val.raw">{{ val.formatted }}</option>
                      </select>
                      <input
                        v-else
                        v-model="singleDeviceRuntime.variables[variable.Name]"
                        :data-testid="`single-device-variable-${variable.Name}`"
                        class="w-full min-w-0 rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-xs text-slate-700 placeholder:text-slate-400"
                        :placeholder="variableInputPlaceholder(variable)"
                        type="text"
                      />
                    </label>

                    <label class="min-w-0">
                      <span class="mb-1 block text-[length:var(--iot-font-min)] font-bold uppercase text-slate-500">{{ t('app.variableTrust') }}</span>
                      <select
                        v-model="singleDeviceRuntime.variableTrusts[variable.Name]"
                        :data-testid="`single-device-variable-trust-${variable.Name}`"
                        class="w-full min-w-0 rounded-lg border border-slate-200 bg-white px-1.5 py-1.5 text-[11px] text-slate-700"
                      >
                        <option value="">{{ t('app.useTemplateDefaultWithValue', { value: t(`app.${variable.Trust || 'trusted'}`) }) }}</option>
                        <option v-for="trust in TRUST_OPTIONS" :key="trust" :value="trust">{{ t(`app.${trust}`) }}</option>
                      </select>
                    </label>

                    <label class="min-w-0">
                      <span class="mb-1 block text-[length:var(--iot-font-min)] font-bold uppercase text-slate-500">{{ t('app.privacy') }}</span>
                      <select
                        v-model="singleDeviceRuntime.privacies[variable.Name]"
                        :data-testid="`single-device-privacy-${variable.Name}`"
                        class="w-full min-w-0 rounded-lg border border-slate-200 bg-white px-1.5 py-1.5 text-[11px] text-slate-700"
                      >
                        <option value="">{{ t('app.useTemplateDefaultWithValue', { value: t(`app.${variable.Privacy || 'public'}`) }) }}</option>
                        <option v-for="privacy in PRIVACY_OPTIONS" :key="privacy" :value="privacy">{{ t(`app.${privacy}`) }}</option>
                      </select>
                    </label>
                  </div>
                </div>
              </div>
            </details>

            <button
              @click="createDevice()"
              data-testid="single-device-create"
              :disabled="isTemplateSelectorDisabled || creatingSingleDevice || !deviceForm.name.trim() || singleDeviceNameConflict"
              class="w-full min-h-11 py-2.5 bg-[color:var(--accent-fill)] hover:bg-[color:var(--accent-fill-hover)] disabled:bg-[color:var(--accent-fill)] disabled:cursor-not-allowed disabled:hover:scale-100 text-white rounded-lg text-xs font-bold uppercase tracking-wider transition-all shadow-md hover:shadow-lg hover:scale-[1.02] active:scale-[0.98] flex items-center justify-center gap-1.5"
            >
              <span class="material-symbols-outlined text-sm">add_location</span>
              {{ creatingSingleDevice ? t('app.saving') : `${t('app.add')} ${t('app.dropNode')}` }}
            </button>
          </fieldset>

          <fieldset
            v-else-if="deviceCreateMode === 'batch'"
            :disabled="props.readOnly || creatingMultipleDevices"
            data-testid="batch-device-fieldset"
            class="m-0 min-w-0 space-y-3 border-0 p-0"
          >
            <div class="relative">
              <label class="block text-[length:var(--iot-font-min)] font-bold text-slate-500 mb-1 uppercase tracking-wide">{{ t('app.type') }}</label>
              <select
                v-model="batchDeviceForm.type"
                data-testid="batch-device-template"
                class="w-full bg-white border-2 border-slate-200 rounded-lg px-3 py-2 text-xs text-slate-700 focus:border-[color:var(--accent-border)] focus:ring-2 focus:ring-[color:var(--accent-border)] transition-all appearance-none shadow-sm"
                :disabled="isTemplateSelectorDisabled"
              >
                <option v-if="props.templatesLoading" value="">{{ t('app.loadingDeviceTemplates') }}</option>
                <option v-else-if="deviceTypes.length === 0" value="">{{ t('app.none') }}</option>
                <option v-else value="" disabled hidden>{{ t('app.selectDeviceTemplate') }}</option>
                <option v-for="type in deviceTypes" :key="type" :value="type">{{ type }}</option>
              </select>
            </div>

            <div class="grid grid-cols-[minmax(0,1fr)_5.5rem] gap-2">
              <div>
                <label class="block text-[length:var(--iot-font-min)] font-bold text-slate-500 mb-1 uppercase tracking-wide">{{ t('app.deviceNamePrefix') }}</label>
                <input
                  v-model="batchDeviceForm.prefix"
                  data-testid="batch-device-prefix"
                  class="w-full bg-white border-2 border-slate-200 rounded-lg px-3 py-2 text-xs text-slate-700 focus:border-[color:var(--accent-border)] focus:ring-2 focus:ring-[color:var(--accent-border)] placeholder:text-slate-400 transition-all shadow-sm"
                  :placeholder="t('app.devicePrefixPlaceholder')"
                  type="text"
                />
              </div>
              <div>
                <label class="block text-[length:var(--iot-font-min)] font-bold text-slate-500 mb-1 uppercase tracking-wide">{{ t('app.count') }}</label>
                <input
                  v-model.number="batchDeviceForm.count"
                  data-testid="batch-device-count"
                  class="w-full bg-white border-2 border-slate-200 rounded-lg px-2 py-2 text-xs text-slate-700 focus:border-[color:var(--accent-border)] focus:ring-2 focus:ring-[color:var(--accent-border)] transition-all shadow-sm"
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
                  class="mt-1 text-[length:var(--iot-font-min)] font-semibold leading-4 board-text-danger"
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
              class="w-full py-2.5 bg-[color:var(--accent-fill)] hover:bg-[color:var(--accent-fill-hover)] disabled:bg-[color:var(--accent-fill)] disabled:cursor-not-allowed disabled:hover:scale-100 text-white rounded-lg text-xs font-bold uppercase tracking-wider transition-all shadow-md hover:shadow-lg hover:scale-[1.02] active:scale-[0.98] flex items-center justify-center gap-1.5"
            >
              <span class="material-symbols-outlined text-sm">playlist_add</span>
              {{ creatingMultipleDevices ? t('app.saving') : t('app.createDevicesWithCount', { count: batchDevicePreview.length }) }}
            </button>
          </fieldset>

          <fieldset
            v-else
            :disabled="props.readOnly || creatingMultipleDevices"
            data-testid="import-device-fieldset"
            class="m-0 min-w-0 space-y-3 border-0 p-0"
          >
            <div class="device-import-help">
              <span class="material-symbols-outlined text-sm" aria-hidden="true">info</span>
              <span class="min-w-0 flex-1 truncate" :title="t('app.deviceImportShortHint')">
                {{ t('app.deviceImportShortHint') }}
              </span>
              <InfoTooltip
                :text="t('app.deviceImportHint')"
                :label="t('app.deviceImportHelpTitle')"
                placement="bottom-end"
                test-id="device-import-help"
              />
            </div>
            <div class="flex items-center justify-between gap-2">
              <!-- `hover:board-chip-accent` was a Tailwind variant applied to a hand-written class, which
                   Tailwind cannot generate — the hover had no effect at all. `board-file-trigger` owns both
                   states in CSS instead. -->
              <label class="board-file-trigger inline-flex cursor-pointer items-center gap-1.5 rounded-md px-2.5 py-1.5 text-[length:var(--iot-font-min)] font-bold transition-colors">
                <input data-testid="device-import-file" type="file" accept=".json,.csv,.txt" class="hidden" @change="handleDeviceImportFile">
                <span class="material-symbols-outlined text-xs">upload_file</span>
                {{ t('app.chooseFile') }}
              </label>
              <span class="text-[length:var(--iot-font-min)] font-semibold text-slate-500">{{ t('app.jsonOrCsv') }}</span>
            </div>
            <textarea
              v-model="importDeviceForm.text"
              data-testid="device-import-text"
              class="min-h-32 w-full resize-y rounded-lg border-2 border-slate-200 bg-white px-3 py-2 font-mono text-[11px] leading-relaxed text-slate-700 shadow-sm transition-all placeholder:text-slate-400 focus:border-[color:var(--accent-border)] focus:ring-2 focus:ring-[color:var(--accent-border)]"
              :placeholder="deviceImportPlaceholder"
            ></textarea>

            <div v-if="parsedImportedDevices.length > 0" data-testid="device-import-preview" class="device-preview-box">
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
                  <span class="truncate">{{ item.customName || item.name || t('app.unnamed') }}</span>
                  <small class="truncate" :title="item.error || item.warning || item.templateName">
                    {{ item.error || item.warning || item.templateName }}
                  </small>
                </div>
              </div>
            </div>

            <div
              v-if="importedEnvironmentMerge.conflicts.length > 0"
              data-testid="device-import-environment-conflicts"
              class="space-y-1 rounded-lg board-surface-danger px-3 py-2 text-[length:var(--iot-font-min)] font-semibold leading-4 board-text-danger"
            >
              <div v-for="(conflict, index) in importedEnvironmentMerge.conflicts" :key="`${conflict.name}-${conflict.field}-${index}`">
                {{ formatImportedEnvironmentConflict(conflict) }}
              </div>
            </div>

            <button
              @click="handleCreateImportedDevices"
              data-testid="device-import-create"
              :disabled="importPreviewStale || validImportedDevices.length === 0 || importedDevicesHaveErrors || importedEnvironmentMerge.conflicts.length > 0 || creatingMultipleDevices"
              class="w-full py-2.5 bg-[color:var(--accent-fill)] hover:bg-[color:var(--accent-fill-hover)] disabled:bg-[color:var(--accent-fill)] disabled:cursor-not-allowed disabled:hover:scale-100 text-white rounded-lg text-xs font-bold uppercase tracking-wider transition-all shadow-md hover:shadow-lg hover:scale-[1.02] active:scale-[0.98] flex items-center justify-center gap-1.5"
            >
              <span class="material-symbols-outlined text-sm">library_add</span>
              {{ creatingMultipleDevices ? t('app.saving') : t('app.createDevicesWithCount', { count: validImportedDevices.length }) }}
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
        <details data-testid="control-template-create" class="group rounded-xl bg-white shadow-sm border border-slate-200 overflow-hidden" open>
          <summary class="flex items-center justify-between p-4 cursor-pointer hover:board-chip-warning transition-all list-none select-none">
            <div class="flex items-center gap-3">
              <div class="w-10 h-10 bg-[color:var(--warning-fill)] rounded-xl flex items-center justify-center">
                <span class="material-symbols-outlined text-white text-lg">add_box</span>
              </div>
              <div>
                <span class="text-sm font-bold text-slate-800">{{ t('app.createTemplate') }}</span>
                <p class="text-xs text-slate-500">{{ t('app.createTemplateSubtitleShort') }}</p>
              </div>
            </div>
            <span class="material-symbols-outlined text-slate-500 transition-transform group-open:rotate-180 text-lg">expand_more</span>
          </summary>

          <div class="px-3 pb-4 bg-slate-50/50 pt-2 space-y-3">
            <!--
              The tint comes from the role token directly, not from `board-chip-warning`. A chip role
              declares `border: 0`, and `board.css` is unlayered while Tailwind's border utilities are in
              `@layer utilities` — so the chip silenced this dropzone's `border-2 border-dashed` entirely
              (measured 0px; the same markup without the chip renders 2px dashed). `board-surface-warning`
              is not the fix either: it brings a *solid* 1px border, which is the opposite of the dashed
              affordance a drop target wants.
            -->
            <div class="relative overflow-hidden rounded-lg border-2 border-dashed border-[color:var(--warning-border)] bg-[color:var(--warning-surface)] transition-all hover:border-[color:var(--warning-fill)] hover:shadow-md">
              <label
                class="group block"
                :class="props.readOnly ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'"
                :title="mutationTitle(t('app.importJsonTemplate'))"
              >
                <input type="file" accept=".json" class="hidden" :disabled="props.readOnly" @change="handleImportTemplate">
                <div class="p-3 flex items-center gap-3">
                  <div class="w-9 h-9 bg-[color:var(--warning-fill)] rounded-lg flex items-center justify-center flex-shrink-0 group-hover:bg-[color:var(--warning-fill-hover)] transition-colors">
                    <span class="material-symbols-outlined text-white text-base">upload_file</span>
                  </div>
                  <div class="min-w-0 flex-1">
                    <div class="text-xs font-bold board-text-warning">{{ t('app.importJsonTemplate') }}</div>
                    <!-- A sentence wraps; it does not truncate. This hint was cut at 26% of its length, breaking off
                     mid-word — a name's prefix still identifies it, but a fragment of guidance identifies nothing.
                     Clamped to three lines so it cannot push the panel out of shape. -->
                <p class="text-[length:var(--iot-font-min)] board-text-warning line-clamp-3" :title="t('app.deviceTemplateSchemaHint')">
                      {{ t('app.deviceTemplateSchemaHint') }}
                    </p>
                  </div>
                </div>
              </label>
              <HintTooltip :content="t('app.downloadTemplateSchema')">
                <button
                  type="button"
   class="mx-3 mb-3 min-h-11 inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-[length:var(--iot-font-min)] font-bold board-text-warning transition-colors hover:board-chip-warning"
                  @click="downloadTemplateSchema"
                >
                  <span class="material-symbols-outlined text-xs">download</span>
                  {{ t('app.downloadTemplateSchema') }}
                </button>
              </HintTooltip>
            </div>
          </div>
        </details>

        <details data-testid="control-template-repository" class="group rounded-xl bg-white shadow-sm border border-slate-200 overflow-hidden" open>
          <summary class="flex items-center justify-between p-4 cursor-pointer hover:board-chip-warning transition-all list-none select-none">
            <div class="flex items-center gap-3">
              <div class="w-10 h-10 bg-[color:var(--warning-fill)] rounded-xl flex items-center justify-center">
                <span class="material-symbols-outlined text-white text-lg">inventory_2</span>
              </div>
              <div>
                <span class="text-sm font-bold text-slate-800">{{ t('app.templateRepository') }}</span>
                <p class="text-xs text-slate-500">{{ t('app.templateRepositoryHint') }}</p>
              </div>
            </div>
            <span class="material-symbols-outlined text-slate-500 transition-transform group-open:rotate-180 text-lg">expand_more</span>
          </summary>

          <div class="px-3 pb-4 bg-slate-50/50 pt-2 space-y-3">
            <div class="rounded-lg board-surface-warning px-3 py-2 text-[length:var(--iot-font-min)] font-semibold leading-relaxed board-text-warning">
              {{ t('app.dragTemplateToCanvasHint') }}
            </div>

            <div class="relative">
              <span aria-hidden="true" class="absolute left-2.5 top-1/2 -translate-y-1/2 material-symbols-outlined text-slate-400 text-xs">search</span>
              <input
                v-model="templateSearchQuery"
                class="w-full min-h-11 bg-white border-2 border-slate-200 rounded-lg px-8 py-2 text-xs text-slate-700 focus:border-[color:var(--accent-border)] focus:ring-2 focus:ring-[color:var(--accent-border)] placeholder:text-slate-400 transition-all shadow-sm"
                :placeholder="t('app.searchTemplates')"
                :aria-label="t('app.searchTemplates')"
                type="text"
              />
              <HintTooltip :content="t('app.clearSearch')">
                <button
                  v-if="templateSearchQuery"
                  type="button"
                  :aria-label="t('app.clearSearch')"
                  @click="templateSearchQuery = ''"
                  class="absolute right-2 top-1/2 -translate-y-1/2 text-slate-500 hover:text-slate-600 transition-colors"
                >
                  <span aria-hidden="true" class="material-symbols-outlined text-xs">close</span>
                </button>
              </HintTooltip>
            </div>

            <div class="flex items-center justify-between px-1">
              <div class="flex items-center gap-1.5">
                <span class="material-symbols-outlined text-slate-500 text-xs">folder_open</span>
                <span class="text-[length:var(--iot-font-min)] font-bold text-slate-500 uppercase tracking-wide">{{ t('app.templates') }}</span>
              </div>
              <div class="flex items-center gap-1.5">
                <HintTooltip :content="mutationTitle(t('app.resetDefaultTemplates'))">
                  <button
                    type="button"
                    data-testid="reset-default-templates"
   class="inline-flex min-h-11 items-center gap-1 rounded-full px-2 py-0.5 text-[length:var(--iot-font-min)] font-bold board-text-warning transition-colors hover:board-chip-warning disabled:cursor-not-allowed disabled:opacity-60"
                    :disabled="props.readOnly || props.templatesLoading || isLoadingDefaultTemplateResetPreview"
                    @click="openResetDefaultsConfirm"
                  >
                    <span
                      v-if="isLoadingDefaultTemplateResetPreview"
                      class="template-reset-dialog__spinner"
                      aria-hidden="true"
                    ></span>
                    <span v-else class="material-symbols-outlined text-xs" aria-hidden="true">restart_alt</span>
                    <span class="truncate">{{ t('app.resetDefaultTemplatesShort') }}</span>
                  </button>
                </HintTooltip>
                <!-- A count is not a status. `board-chip-warning` here was read by a review as a queue of
                     things needing attention when it is only how many templates are listed;
                     `board-chip-neutral` exists so a number does not have to borrow a role's meaning. -->
                <span class="text-[length:var(--iot-font-min)] font-bold board-chip-neutral px-2 py-0.5 rounded-full">
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
              <p class="mt-1 text-[length:var(--iot-font-min)] board-text-warning">{{ t('app.preparingDefaultTemplates') }}</p>
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
                    <span class="template-group__label truncate text-[length:var(--iot-font-min)] font-bold uppercase tracking-wide" :title="group.label">{{ group.label }}</span>
                  </div>
                  <span class="template-group__count rounded-full px-2 py-0.5 text-[length:var(--iot-font-min)] font-bold">{{ group.templates.length }}</span>
                </summary>

                <!-- Columns follow the available width, not a fixed count. `grid-cols-2` in a 320px panel gave
                     each template title 51px regardless of the name, and one default template needs 217px.
                     11rem is the floor at which the longest bundled name stops truncating, so a narrow panel
                     drops to one column rather than printing two unreadable ones. -->
                <div
                  v-if="group.templates.length > 0"
                  class="template-group__grid grid grid-cols-[repeat(auto-fill,minmax(11rem,1fr))] gap-2 px-2.5 pb-2.5"
                >
                  <div
                    v-for="template in group.templates"
                    :key="template.id"
                    class="template-card relative rounded-lg p-2 border transition-all duration-200"
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
                            class="h-full w-full object-contain"
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
                            <h3 class="template-card__title min-w-0 flex-1 text-xs font-bold transition-colors truncate" :title="getTemplateName(template)">
                              {{ getTemplateName(template) }}
                            </h3>
                            <span class="template-card__drag-cue material-symbols-outlined" aria-hidden="true">drag_indicator</span>
                          </div>
                          <div class="template-card__stats text-[length:var(--iot-font-min)] mt-0.5 flex items-center gap-1.5">
                            <span class="template-card__pill px-1.5 py-0.5 rounded">{{ template.manifest.InternalVariables?.length || 0 }} {{ t('app.varsShort') }}</span>
                            <span class="template-card__pill px-1.5 py-0.5 rounded">{{ template.manifest.APIs?.length || 0 }} {{ t('app.apisShort') }}</span>
                          </div>
                        </div>
                      </div>
                    </button>

                    <div class="template-card__actions mt-0.5 pt-0.5 border-t flex justify-end gap-1">
                      <HintTooltip :content="t('app.export')">
                        <button
                          type="button"
                          @click.stop="exportTemplate(template)"
                          @dragstart.stop.prevent
                          class="template-card__action cursor-pointer p-1 rounded transition-colors"
                          :aria-label="t('app.export')"
                        >
                          <span class="material-symbols-outlined text-xs" aria-hidden="true">download</span>
                        </button>
                      </HintTooltip>
                      <HintTooltip :content="mutationTitle(t('app.delete'))">
                        <button
                          type="button"
                          @click.stop="openDeleteConfirm(template)"
                          @dragstart.stop.prevent
                          :disabled="props.readOnly || isLoadingTemplateDeletePreview || isDeletingTemplate"
                          class="template-card__action template-card__action--danger cursor-pointer p-1 rounded transition-colors disabled:cursor-not-allowed disabled:opacity-50"
                          :aria-label="t('app.delete')"
                        >
                          <span class="material-symbols-outlined text-xs" aria-hidden="true">delete</span>
                        </button>
                      </HintTooltip>
                    </div>
                  </div>
                </div>

                <div v-else class="template-group__empty mx-2.5 mb-2.5 rounded-lg border border-dashed px-3 py-2 text-center text-[length:var(--iot-font-min)]">
                  {{ group.key === 'default' ? t('app.noDefaultTemplates') : t('app.noCustomTemplates') }}
                </div>
              </details>
            </div>

            <div v-else class="relative overflow-hidden text-center py-8 border-2 border-dashed border-slate-200 rounded-xl bg-slate-50/50">
              <div class="absolute top-0 left-0 w-full h-1 bg-gradient-to-r from-[color:var(--warning)] via-[color:var(--warning)] to-[color:var(--warning)]"></div>
              <div class="relative">
                <div class="w-14 h-14 mx-auto board-chip-warning rounded-full flex items-center justify-center mb-3 shadow-inner">
                  <span class="material-symbols-outlined board-text-warning text-2xl">inventory_2</span>
                </div>
                <p class="text-xs text-slate-600 mb-1 font-semibold">
                  {{ templateSearchQuery ? t('app.noMatchingTemplates') : t('app.noTemplatesYet') }}
                </p>
                <p class="text-[length:var(--iot-font-min)] text-slate-500">
                  {{ templateSearchQuery ? t('app.tryDifferentSearchTerm') : t('app.importJsonTemplateHint') }}
                </p>
                <button
                  v-if="templateSearchQuery"
                  @click="templateSearchQuery = ''"
                  class="mt-3 px-4 py-1.5 text-[length:var(--iot-font-min)] font-semibold board-text-warning board-chip-warning hover:board-chip-warning rounded-lg transition-colors"
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
        v-if="activeSection === 'rules'"
        id="control-section-rules"
        role="tabpanel"
        aria-labelledby="control-tab-rules"
        data-testid="control-section-rules"
      >
        <details class="group mb-3 rounded-xl bg-white shadow-sm border border-slate-200 overflow-hidden" open>
        <summary class="flex items-center justify-between p-4 cursor-pointer hover:board-chip-info transition-all list-none select-none">
          <div class="flex items-center gap-3">
            <div class="w-10 h-10 bg-[color:var(--accent-fill)] rounded-xl flex items-center justify-center">
              <span class="material-symbols-outlined text-white text-lg">function</span>
            </div>
            <div>
              <span class="text-sm font-bold text-slate-800">{{ t('app.iftttRule') }}</span>
              <p class="text-xs text-slate-500">{{ t('app.createConditionalLogic') }}</p>
            </div>
          </div>
          <span class="material-symbols-outlined text-slate-500 transition-transform group-open:rotate-180 text-lg">expand_more</span>
        </summary>

        <div class="px-3 pb-4 bg-slate-50/50 pt-2 grid grid-cols-1 gap-3">
          <!-- Rule Creation Block -->
          <HintTooltip :content="mutationTitle(t('app.createRule'))">
            <button
              type="button"
              data-testid="open-rule-builder"
              :disabled="props.readOnly"
              class="relative w-full overflow-hidden border-0 text-left group cursor-pointer rounded-xl bg-[color:var(--accent-fill)] hover:bg-[color:var(--accent-fill-hover)] transition-all hover:shadow-lg hover:-translate-y-0.5 focus-visible:outline focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--accent-border)]"
              @click="openRuleBuilder"
            >
              <div class="relative p-3 flex items-center gap-3">
                <div class="w-10 h-10 bg-[color:var(--accent-fill)] rounded-lg flex items-center justify-center">
                  <span aria-hidden="true" class="material-symbols-outlined text-black text-lg">add_circle</span>
                </div>
                <div class="flex-1">
                  <span class="text-sm font-bold text-white block">{{ t('app.createRule') }}</span>
                  <!--
                    This card's ground is an accent *fill*, so its subtitle needs the ink that belongs on a
                    fill. `board-text-info` is the accent-family *text* colour, meant for a neutral page
                    ground: accent-on-accent measured **1.04:1** in light theme, making the subtitle of the
                    most prominent action on the panel very nearly invisible.

                    `/90` rather than `/85`: /85 measures 4.19, under AA. Chosen by measurement after picking
                    the wrong one by eye first.
                  -->
                  <span class="text-xs text-white/90">{{ t('app.ifThenLogic') }}</span>
                </div>
                <div class="w-7 h-7 bg-[color:var(--accent-fill)] rounded-lg flex items-center justify-center">
                  <span class="material-symbols-outlined text-white text-sm">arrow_forward</span>
                </div>
              </div>
            </button>
          </HintTooltip>
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
        <details class="group mb-3 rounded-xl bg-white shadow-sm border border-slate-200 overflow-hidden" open>
        <summary class="flex items-center justify-between p-4 cursor-pointer hover:board-chip-danger transition-all list-none select-none">
          <div class="flex items-center gap-3">
            <div class="w-10 h-10 bg-[color:var(--danger-fill)] rounded-xl flex items-center justify-center">
              <span class="material-symbols-outlined text-white text-lg">verified</span>
            </div>
            <div>
              <span class="text-sm font-bold text-slate-800">{{ t('app.specifications') }}</span>
              <p class="text-xs text-slate-500">{{ t('app.ltlVerificationRules') }}</p>
            </div>
          </div>
          <span class="material-symbols-outlined text-slate-500 transition-transform group-open:rotate-180 text-lg">expand_more</span>
        </summary>

        <div class="px-3 pb-4 bg-slate-50/50 pt-2 space-y-3">
          <!-- Specification Creation -->
          <fieldset
            data-testid="spec-editor-fieldset"
            :disabled="props.readOnly || creatingSpecification"
            :aria-busy="creatingSpecification"
            class="m-0 min-w-0 space-y-3 border-0 p-0"
          >
            <!-- Step 1: Select Template -->
            <div>
              <label class="block text-[length:var(--iot-font-min)] font-bold text-slate-600 uppercase tracking-wide mb-2">{{ t('app.selectTemplate') }}</label>
              <select
                v-model="specForm.templateId"
                data-testid="spec-template-select"
                @change="handleTemplateChange"
                class="w-full min-h-11 bg-white border-2 border-slate-200 rounded-lg px-3 py-2 text-xs text-slate-700 focus:border-[color:var(--accent-border)] focus:ring-2 focus:ring-[color:var(--accent-border)] transition-all shadow-sm appearance-none cursor-pointer"
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
              </select>
              <p v-if="currentTemplateDetail" class="text-[length:var(--iot-font-min)] text-slate-500 mt-1.5 px-1">
                <span class="line-clamp-2">
                  {{ templateMessage(currentTemplateDetail.descriptionKey, currentTemplateDetail.description) }}
                </span>
              </p>
            </div>

            <!-- Step 2: Add Conditions based on template requirements -->
            <div v-if="specForm.templateId" class="space-y-2">
              <label class="block text-[length:var(--iot-font-min)] font-bold text-slate-600 uppercase tracking-wide">{{ t('app.configureConditions') }}</label>

              <!-- A Conditions (Always/Forall) -->
              <div v-if="isSideRequired('a')" class="relative overflow-hidden rounded-lg board-surface-danger p-2.5">
                <div class="relative flex items-center justify-between mb-2">
                  <div class="flex items-center gap-2">
                    <span class="w-6 h-6 bg-[color:var(--danger-fill)] rounded-md flex items-center justify-center">
                      <svg class="w-4 h-4 text-white" fill="currentColor" viewBox="0 0 24 24">
                        <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/>
                      </svg>
                    </span>
                    <span class="text-[length:var(--iot-font-min)] font-bold board-text-danger uppercase tracking-wide">{{ t('app.aConditions') }}</span>
                  </div>
                  <button
                    @click="openConditionDialog('a')"
                    data-testid="spec-add-condition-a"
                    class="px-2.5 py-1 bg-[color:var(--danger-fill)] text-white rounded-md text-[length:var(--iot-font-min)] font-bold uppercase tracking-wide hover:bg-[color:var(--danger-fill-hover)] transition-all shadow-sm flex items-center gap-1"
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
                    class="flex items-center justify-between bg-white rounded-md px-2.5 py-1.5 border border-[color:var(--danger-border)] shadow-sm hover:shadow-md transition-all"
                  >
                    <div class="flex items-center gap-2 overflow-hidden flex-1">
                      <div class="w-6 h-6 board-chip-danger rounded-md flex items-center justify-center flex-shrink-0">
                        <svg class="w-3.5 h-3.5 board-text-danger" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 3v2m6-2v2M9 19v2m6-2v2M5 9H3m2 6H3m18-6h-2m2 6h-2M7 19h10a2 2 0 002-2V7a2 2 0 00-2-2H7a2 2 0 00-2 2v10a2 2 0 002 2zM9 9h6v6H9V9z"/>
                        </svg>
                      </div>
                      <div class="flex items-center gap-1 overflow-hidden flex-1 min-w-0">
                        <span
                          class="text-[length:var(--iot-font-min)] font-medium truncate min-w-0"
                          :class="condition.isDeviceMissing
 ? 'board-text-danger line-through'
 : 'text-slate-700'"
                          :title="condition.deviceLabel"
                        >
                          {{ condition.deviceLabel }}
                        </span>
                        <span class="text-slate-500 flex-shrink-0">·</span>
                        <span class="text-[length:var(--iot-font-min)] board-text-danger font-medium truncate flex-shrink-0" :title="condition.propertyLabel">{{ condition.propertyLabel }}</span>
                        <!-- Which of the two variable questions this row asks. A row with no
                             recorded choice is marked unresolved rather than shown as either. -->
                        <span
                          v-if="condition.targetType === 'variable'"
                          class="text-[length:var(--iot-font-min)] px-1 py-0.5 rounded flex-shrink-0 border"
                          :class="condition.isVariableSourceUnresolved
                            ? 'board-chip-danger board-text-danger border-[color:var(--danger-border)]'
                            : 'text-slate-600 bg-slate-100 border-slate-200'"
                          :title="condition.variableSourceLabel || ''"
                          data-testid="spec-condition-row-variable-source"
                        >{{ condition.variableSourceLabel }}</span>
                        <span class="text-[length:var(--iot-font-min)] text-slate-500 bg-slate-100 px-1 py-0.5 rounded flex-shrink-0">
                          {{ condition.relationLabel }}
                        </span>
                        <span class="text-[length:var(--iot-font-min)] board-surface-danger board-text-danger px-1 py-0.5 rounded truncate max-w-[60px] flex-shrink-0" :title="condition.formattedValue">
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
                          class="p-1 text-slate-500 hover:board-text-danger hover:board-chip-danger rounded transition-colors"
                          :aria-label="t('app.removeConditionNumbered', { number: index + 1 })"
                        >
                          <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/>
                          </svg>
                        </button>
                      </HintTooltip>
                    </div>
                  </div>
                  <div v-if="specForm.aConditions.length === 0" class="text-center py-2 text-[length:var(--iot-font-min)] text-slate-500 italic bg-white/50 rounded border border-dashed border-[color:var(--danger-border)]">
                    {{ t('app.noConditionsAdded') }}
                  </div>
                </div>
              </div>

              <!-- IF Conditions (Antecedent) -->
              <div v-if="isSideRequired('if')" class="relative overflow-hidden rounded-lg board-surface-danger p-2.5">
                <div class="relative flex items-center justify-between mb-2">
                  <div class="flex items-center gap-2">
                    <span class="w-6 h-6 bg-[color:var(--danger-fill)] rounded-md flex items-center justify-center">
                      <svg class="w-4 h-4 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z"/>
                      </svg>
                    </span>
                    <span class="text-[length:var(--iot-font-min)] font-bold board-text-danger uppercase tracking-wide">{{ t('app.ifConditions') }}</span>
                  </div>
                  <button
                    @click="openConditionDialog('if')"
                    data-testid="spec-add-condition-if"
                    class="px-2.5 py-1 bg-[color:var(--danger-fill)] text-white rounded-md text-[length:var(--iot-font-min)] font-bold uppercase tracking-wide hover:bg-[color:var(--danger-fill-hover)] transition-all shadow-sm flex items-center gap-1"
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
                    class="flex items-center justify-between bg-white rounded-md px-2.5 py-1.5 border border-[color:var(--danger-border)] shadow-sm hover:shadow-md transition-all"
                  >
                    <div class="flex items-center gap-2 overflow-hidden flex-1">
                      <div class="w-6 h-6 board-chip-danger rounded-md flex items-center justify-center flex-shrink-0">
                        <svg class="w-3.5 h-3.5 board-text-danger" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 3v2m6-2v2M9 19v2m6-2v2M5 9H3m2 6H3m18-6h-2m2 6h-2M7 19h10a2 2 0 002-2V7a2 2 0 00-2-2H7a2 2 0 00-2 2v10a2 2 0 002 2zM9 9h6v6H9V9z"/>
                        </svg>
                      </div>
                      <div class="flex items-center gap-1 overflow-hidden flex-1 min-w-0">
                        <span
                          class="text-[length:var(--iot-font-min)] font-medium truncate min-w-0"
                          :class="condition.isDeviceMissing
 ? 'board-text-danger line-through'
 : 'text-slate-700'"
                          :title="condition.deviceLabel"
                        >
                          {{ condition.deviceLabel }}
                        </span>
                        <span class="text-slate-500 flex-shrink-0">·</span>
                        <span class="text-[length:var(--iot-font-min)] board-text-danger font-medium truncate flex-shrink-0" :title="condition.propertyLabel">{{ condition.propertyLabel }}</span>
                        <!-- Which of the two variable questions this row asks. A row with no
                             recorded choice is marked unresolved rather than shown as either. -->
                        <span
                          v-if="condition.targetType === 'variable'"
                          class="text-[length:var(--iot-font-min)] px-1 py-0.5 rounded flex-shrink-0 border"
                          :class="condition.isVariableSourceUnresolved
                            ? 'board-chip-danger board-text-danger border-[color:var(--danger-border)]'
                            : 'text-slate-600 bg-slate-100 border-slate-200'"
                          :title="condition.variableSourceLabel || ''"
                          data-testid="spec-condition-row-variable-source"
                        >{{ condition.variableSourceLabel }}</span>
                        <span class="text-[length:var(--iot-font-min)] text-slate-500 bg-slate-100 px-1 py-0.5 rounded flex-shrink-0">
                          {{ condition.relationLabel }}
                        </span>
                        <span class="text-[length:var(--iot-font-min)] board-surface-danger board-text-danger px-1 py-0.5 rounded truncate max-w-[60px] flex-shrink-0" :title="condition.formattedValue">
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
                          class="p-1 text-slate-500 hover:board-text-danger hover:board-chip-danger rounded transition-colors"
                          :aria-label="t('app.removeConditionNumbered', { number: index + 1 })"
                        >
                          <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/>
                          </svg>
                        </button>
                      </HintTooltip>
                    </div>
                  </div>
                  <div v-if="specForm.ifConditions.length === 0" class="text-center py-2 text-[length:var(--iot-font-min)] text-slate-500 italic bg-white/50 rounded border border-dashed border-[color:var(--danger-border)]">
                    {{ t('app.noConditionsAdded') }}
                  </div>
                </div>
              </div>

              <!-- THEN Conditions (Consequent) -->
              <div v-if="isSideRequired('then')" class="relative overflow-hidden rounded-lg board-surface-warning p-2.5">
                <div class="relative flex items-center justify-between mb-2">
                  <div class="flex items-center gap-2">
                    <span class="w-6 h-6 bg-[color:var(--warning-fill)] rounded-md flex items-center justify-center">
                      <svg class="w-4 h-4 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 8l4 4m0 0l-4 4m4-4H3"/>
                      </svg>
                    </span>
                    <span class="text-[length:var(--iot-font-min)] font-bold board-text-warning uppercase tracking-wide">{{ t('app.thenConditions') }}</span>
                  </div>
                  <button
                    @click="openConditionDialog('then')"
                    data-testid="spec-add-condition-then"
                    class="px-2.5 py-1 bg-[color:var(--warning-fill)] text-white rounded-md text-[length:var(--iot-font-min)] font-bold uppercase tracking-wide hover:bg-[color:var(--warning-fill-hover)] transition-all shadow-sm flex items-center gap-1"
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
                    class="flex items-center justify-between bg-white rounded-md px-2.5 py-1.5 border border-[color:var(--warning-border)] shadow-sm hover:shadow-md transition-all"
                  >
                    <div class="flex items-center gap-2 overflow-hidden flex-1">
                      <div class="w-6 h-6 board-chip-warning rounded-md flex items-center justify-center flex-shrink-0">
                        <svg class="w-3.5 h-3.5 board-text-warning" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 3v2m6-2v2M9 19v2m6-2v2M5 9H3m2 6H3m18-6h-2m2 6h-2M7 19h10a2 2 0 002-2V7a2 2 0 00-2-2H7a2 2 0 00-2 2v10a2 2 0 002 2zM9 9h6v6H9V9z"/>
                        </svg>
                      </div>
                      <div class="flex items-center gap-1 overflow-hidden flex-1 min-w-0">
                        <span
                          class="text-[length:var(--iot-font-min)] font-medium truncate min-w-0"
                          :class="condition.isDeviceMissing
 ? 'board-text-danger line-through'
 : 'text-slate-700'"
                          :title="condition.deviceLabel"
                        >
                          {{ condition.deviceLabel }}
                        </span>
                        <span class="text-slate-500 flex-shrink-0">·</span>
                        <span class="text-[length:var(--iot-font-min)] board-text-warning font-medium truncate flex-shrink-0" :title="condition.propertyLabel">{{ condition.propertyLabel }}</span>
                        <!-- Which of the two variable questions this row asks. A row with no
                             recorded choice is marked unresolved rather than shown as either. -->
                        <span
                          v-if="condition.targetType === 'variable'"
                          class="text-[length:var(--iot-font-min)] px-1 py-0.5 rounded flex-shrink-0 border"
                          :class="condition.isVariableSourceUnresolved
                            ? 'board-chip-danger board-text-danger border-[color:var(--danger-border)]'
                            : 'text-slate-600 bg-slate-100 border-slate-200'"
                          :title="condition.variableSourceLabel || ''"
                          data-testid="spec-condition-row-variable-source"
                        >{{ condition.variableSourceLabel }}</span>
                        <span class="text-[length:var(--iot-font-min)] text-slate-500 bg-slate-100 px-1 py-0.5 rounded flex-shrink-0">
                          {{ condition.relationLabel }}
                        </span>
                        <span class="text-[length:var(--iot-font-min)] board-surface-warning board-text-warning px-1 py-0.5 rounded truncate max-w-[60px] flex-shrink-0" :title="condition.formattedValue">
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
                          class="p-1 text-slate-500 hover:board-text-warning hover:board-chip-warning rounded transition-colors"
                          :aria-label="t('app.removeConditionNumbered', { number: index + 1 })"
                        >
                          <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/>
                          </svg>
                        </button>
                      </HintTooltip>
                    </div>
                  </div>
                  <div v-if="specForm.thenConditions.length === 0" class="text-center py-2 text-[length:var(--iot-font-min)] text-slate-500 italic bg-white/50 rounded border border-dashed border-[color:var(--warning-border)]">
                    {{ t('app.noConditionsAdded') }}
                  </div>
                </div>
              </div>
            </div>

            <!-- Step 3: Generated Specification Description -->
            <div v-if="specForm.templateId" class="relative overflow-hidden rounded-lg bg-white border border-[color:var(--danger-border)] p-3 shadow-sm">
              <div class="relative">
                <div class="flex items-center gap-2 mb-2">
                  <span class="w-6 h-6 board-chip-danger rounded-md flex items-center justify-center">
                    <svg class="w-4 h-4 board-text-danger" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 5h12M9 3v2m1.048 9.5A18.022 18.022 0 016.412 9m6.088 9h7M11 21l5-10 5 10M12.751 5C11.783 10.77 8.07 15.61 3 18.129"/>
                    </svg>
                  </span>
                  <span class="text-[length:var(--iot-font-min)] font-bold board-text-danger uppercase tracking-wide">{{ t('app.specificationDescription') }}</span>
                </div>
                <div class="text-xs text-slate-700 leading-relaxed pl-8">
                  {{ naturalLanguageRule }}
                </div>
                <div class="mt-2 pt-2 border-t border-slate-200 flex items-center gap-2">
                  <span class="text-[length:var(--iot-font-min)] font-bold text-slate-500 uppercase tracking-wide">{{ t('app.formulaPreview') }}</span>
                  <span
                    class="px-1.5 py-0.5 bg-slate-100 rounded text-[length:var(--iot-font-min)] font-bold text-slate-600 uppercase"
                    data-testid="spec-formula-kind"
                  >{{ specFormulaKind }}</span>
                  <!-- `iot-scroll-region-x` rather than raw `overflow-x-auto`: the primitive owns the token
                       scrollbar and overscroll containment, which a bare overflow does not. Type at the
                       product floor, since a formula is the thing a user is trying to read here. -->
                  <code class="iot-scroll-region-x flex-1 text-[length:var(--iot-font-min)] bg-slate-100 text-slate-700 px-2 py-1 rounded font-mono">
                    {{ specForm.formula }}
                  </code>
                </div>
              </div>
            </div>

            <!-- Create Button -->
            <p
              v-if="specificationBlockedReason"
              id="spec-create-blocked-reason"
              role="status"
              class="mb-2 text-[length:var(--iot-font-min)] font-semibold leading-4 board-text-danger"
              data-testid="spec-create-blocked-reason"
            >
              {{ specificationBlockedReason }}
            </p>
            <button
              @click="createSpecification"
              data-testid="spec-create"
              :disabled="Boolean(specificationBlockedReason) || creatingSpecification"
              :aria-describedby="specificationBlockedReason ? 'spec-create-blocked-reason' : undefined"
              class="w-full min-h-11 py-2.5 bg-[color:var(--danger-fill)] hover:bg-[color:var(--danger-fill-hover)] disabled:bg-slate-300 text-white rounded-lg text-xs font-bold uppercase tracking-wider transition-all shadow-md hover:shadow-lg hover:scale-[1.02] active:scale-[0.98] disabled:hover:scale-100 flex items-center justify-center gap-1.5 disabled:cursor-not-allowed"
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
      class="iot-dialog iot-dialog--md control-center-dialog-surface"
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
          <p class="iot-dialog__subtitle">{{ t('app.configureSpecification') }}</p>
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
            <span class="text-sm font-bold text-black">{{ t('app.deviceSelection') }}</span>
          </div>
          <div class="relative w-full">
            <select
              v-model="editingConditionData.deviceId"
              data-testid="spec-condition-device"
              @change="editingConditionData.key = ''"
              class="w-full bg-white border-2 border-slate-300 rounded-lg px-3 py-2.5 text-sm text-black focus:border-[color:var(--accent-border)] focus:outline-none appearance-none cursor-pointer"
              :class="deviceNodes.length === 0 ? 'cursor-not-allowed opacity-70' : 'cursor-pointer'"
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
            </select>
          </div>
        </div>

        <!-- Type -->
        <div class="space-y-2" v-if="editingConditionData.deviceId">
          <div class="flex items-center gap-2">
            <svg class="w-5 h-5 text-black" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 7h.01M7 3h5c.512 0 1.024.195 1.414.586l7 7a2 2 0 010 2.828l-7 7a2 2 0 01-2.828 0l-7-7A2 2 0 013 12V7a4 4 0 014-4z"/>
            </svg>
            <span class="text-sm font-bold text-black">{{ t('app.type') }}</span>
          </div>
          <div class="relative w-full">
            <select
              v-model="editingConditionData.targetType"
              data-testid="spec-condition-type"
              @change="handleTargetTypeChange"
              class="w-full bg-white border-2 border-slate-300 rounded-lg px-3 py-2.5 text-sm text-black focus:border-[color:var(--accent-border)] focus:outline-none appearance-none cursor-pointer"
            >
              <option v-if="localizedTargetTypes.length === 0" value="">{{ t('app.none') }}</option>
              <option v-else value="" hidden>{{ t('app.type') }}</option>
              <option v-for="type in localizedTargetTypes" :key="type.value" :value="type.value">
                {{ type.label }}
              </option>
            </select>
          </div>
        </div>

        <!-- Property -->
        <div class="space-y-2" v-if="editingConditionData.targetType !== 'state' && editingConditionData.deviceId">
          <div class="flex items-center gap-2">
            <svg class="w-5 h-5 text-black" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z"/>
            </svg>
            <span class="text-sm font-bold text-black">{{ t('app.property') }}</span>
          </div>
          <div class="relative w-full">
            <select
              v-model="conditionKeySelection"
              data-testid="spec-condition-key"
              class="w-full bg-white border-2 border-slate-300 rounded-lg px-3 py-2.5 text-sm text-black focus:border-[color:var(--accent-border)] focus:outline-none appearance-none cursor-pointer"
              :class="availableKeys.length === 0 ? 'cursor-not-allowed opacity-70' : 'cursor-pointer'"
              :disabled="availableKeys.length === 0"
            >
              <option v-if="availableKeys.length === 0" value="">{{ t('app.none') }}</option>
              <option v-else value="" hidden>{{ t('app.property') }}</option>
              <option
                v-for="key in availableKeys"
                :key="key.value"
                :value="key.value"
              >
                {{ key.label }}
              </option>
            </select>
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
            <svg class="w-5 h-5 text-black" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"/>
            </svg>
            <span class="text-sm font-bold text-black">{{ t('app.specVariableSourceTitle') }}</span>
          </legend>
          <label
            v-for="option in editingConditionVariableSourceOptions"
            :key="option.value"
            class="flex items-start gap-2 w-full bg-white border-2 rounded-lg px-3 py-2.5 text-sm text-black cursor-pointer"
            :class="editingConditionData.variableSource === option.value
              ? 'border-[color:var(--accent-border)]'
              : 'border-slate-300'"
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
          <p class="text-xs text-slate-600" data-testid="spec-condition-variable-source-help">
            {{ editingConditionVariableSourceOptions.length === 1
              ? t('app.specVariableSourceDeviceLocalHelp')
              : t('app.specVariableSourceHelp') }}
          </p>
          <!-- The general help says the two readings differ "once a device is compromised". Whether THIS
               device can falsify THIS reading is the fact that decides the choice, it is declared in the
               manifest, and without it the choice looks arbitrary exactly when it is inert. -->
          <p
            v-if="editingConditionVariableSourceOptions.length > 1"
            class="text-xs text-slate-600"
            data-testid="spec-condition-variable-source-falsifiable"
          >
            {{ editingConditionVariableIsFalsifiable
              ? t('app.specVariableSourceFalsifiableHint')
              : t('app.specVariableSourceNotFalsifiableHint') }}
          </p>
        </fieldset>

        <!-- Condition Details -->
        <div class="space-y-2" v-if="showRelationAndValue">
          <div class="flex items-center gap-2">
            <svg class="w-5 h-5 text-black" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6V4m0 2a2 2 0 100 4m0-4a2 2 0 110 4m-6 8a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4m6 6v10m6-2a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4"/>
            </svg>
            <span class="text-sm font-bold text-black">{{ t('app.conditionDetails') }}</span>
          </div>
          <div class="flex gap-2 w-full">
            <!-- Operator -->
            <div class="relative w-1/4">
              <select
                v-model="editingConditionData.relation"
                data-testid="spec-condition-relation"
                class="w-full bg-white border-2 border-slate-300 rounded-lg px-2 py-2.5 text-sm text-center font-bold text-black focus:border-[color:var(--accent-border)] focus:outline-none appearance-none cursor-pointer"
              >
                <option v-for="op in filteredRelationOperators" :key="op.value" :value="op.value">
                  {{ op.label }}
                </option>
              </select>
            </div>
            <!-- Value -->
            <div class="relative w-3/4">
              <select
                v-if="conditionValueOptions.length > 0 && isSpecSetRelation"
                v-model="editingConditionValueList"
                data-testid="spec-condition-value"
                multiple
                size="4"
                class="w-full min-h-[7.5rem] bg-white border-2 border-slate-300 rounded-lg px-3 py-2.5 text-sm text-black focus:border-[color:var(--accent-border)] focus:outline-none cursor-pointer"
              >
                <option v-for="val in conditionValueOptions" :key="val" :value="val">
                  {{ formatEditingConditionModelToken(val) }}
                </option>
              </select>
              <select
                v-else-if="conditionValueOptions.length > 0"
                v-model="editingConditionData.value"
                data-testid="spec-condition-value"
                class="w-full bg-white border-2 border-slate-300 rounded-lg px-3 py-2.5 text-sm text-black focus:border-[color:var(--accent-border)] focus:outline-none appearance-none cursor-pointer"
              >
                <option value="" hidden>{{ t('app.value') }}</option>
                <option v-for="val in conditionValueOptions" :key="val" :value="val">
                  {{ formatEditingConditionModelToken(val) }}
                </option>
              </select>
              <input
                v-else
                v-model="editingConditionData.value"
                data-testid="spec-condition-value"
                class="w-full bg-white border-2 border-slate-300 rounded-lg px-3 py-2.5 text-sm text-black placeholder:text-slate-400 focus:border-[color:var(--accent-border)] focus:outline-none"
                :placeholder="t('app.enterValuePlaceholder')"
              />
            </div>
          </div>
        </div>

        <!-- Preview -->
        <div class="space-y-2">
          <div class="flex items-center gap-2">
            <svg class="w-5 h-5 text-black" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"/>
            </svg>
            <span class="text-xs font-bold uppercase text-black tracking-wider">{{ t('app.preview') }}</span>
          </div>
          <div class="font-mono text-xs bg-slate-100 rounded-lg px-3 py-2.5 border border-slate-300 text-black break-all w-full">
            <span class="board-text-danger font-bold">{{ getDeviceLabel(editingConditionData.deviceId || t('app.device')) }}</span>
            <template v-if="editingConditionData.targetType !== 'state' && editingConditionData.key">
              <span class="text-slate-500">.</span>
              <span class="board-text-danger font-bold">{{ formatEditingConditionModelToken(editingConditionData.key) }}</span>
            </template>
            <!-- Without this the preview reads the same for both questions, which is what let an
                 author believe they had pinned the device's own reading. -->
            <template v-if="editingConditionData.targetType === 'variable' && editingConditionData.key">
              <span
                class="ml-1 text-slate-600"
                data-testid="spec-condition-preview-variable-source"
              >({{ formatConditionVariableSourceLabel(editingConditionData as SpecCondition) }})</span>
            </template>
            <template v-if="showRelationAndValue">
              <span class="text-slate-500 mx-1">{{ getRelationLabel(editingConditionData.relation || '=') }}</span>
              <span class="text-black">"{{ editingConditionData.value ? formatEditingConditionModelToken(editingConditionData.value) : t('app.value') }}"</span>
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
          class="mr-auto text-xs font-semibold board-text-danger"
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
      class="template-preview"
      :style="templatePreviewStyle"
      :data-testid="`template-preview-${getTemplateKey(activeTemplatePreview)}`"
      @click.stop
    >
      <div class="template-preview__header">
        <div class="min-w-0">
          <p class="template-preview__eyebrow">{{ t('app.templateDetails') }}</p>
          <h5 class="template-preview__title truncate" :title="getTemplateName(activeTemplatePreview)">{{ getTemplateName(activeTemplatePreview) }}</h5>
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

      <p class="template-preview__description" :title="getTemplateDescription(activeTemplatePreview)">
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
          class="template-preview__section"
        >
          <span class="template-preview__section-label">{{ section.label }}</span>
          <div class="template-preview__chips">
            <span v-if="section.items.length === 0" class="template-preview__empty">{{ t('app.none') }}</span>
            <template v-else>
              <span
                v-for="item in previewItems(section.items)"
                :key="`${section.key}-${item}`"
                class="template-preview__chip"
                :title="item"
              >
                {{ item }}
              </span>
              <span v-if="section.items.length > previewItems(section.items).length" class="template-preview__chip">
                +{{ section.items.length - previewItems(section.items).length }}
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
      <!-- The tone tile carries the warning. This used to be a full-bleed red banner with a 64px circle
           inside it, which shouted louder than the account-deletion dialog for a reversible catalog edit. -->
      <div class="iot-dialog__header">
        <div class="iot-dialog__icon">
          <span class="material-symbols-outlined" aria-hidden="true">delete</span>
        </div>
        <div class="iot-dialog__heading">
          <h3 id="delete-template-dialog-title" class="iot-dialog__title">
            {{ templateToDelete ? templateToDelete.manifest.Name : t('app.youAreAboutToDelete') }}
          </h3>
          <p class="iot-dialog__subtitle">{{ t('app.actionCannotBeUndone') }}</p>
        </div>
      </div>

      <div class="iot-dialog__body iot-scroll-region">
      <div
        v-if="templateDeletePreview && !templateDeletePreview.canDelete"
        class="rounded-lg board-surface-warning p-3 text-left"
      >
        <p class="text-sm font-bold board-text-warning">{{ t('app.templateDeleteBlocked') }}</p>
        <p class="mt-1 text-xs leading-5 board-text-warning">{{ t('app.templateDeleteBlockedDetail') }}</p>
        <ul class="mt-2 space-y-1.5">
          <li
            v-for="blocker in templateDeletePreview.blockers"
            :key="blocker.itemId"
            class="flex items-center gap-2 rounded border border-[color:var(--warning-border)] bg-white px-2 py-1.5 text-xs text-slate-700"
          >
            <span class="material-symbols-outlined text-sm board-text-warning" aria-hidden="true">devices</span>
            <span class="min-w-0 truncate" :title="blocker.itemLabel">{{ blocker.itemLabel }}</span>
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

        <div class="mt-3 max-h-44 iot-scroll-region border-y border-slate-200 py-1 dark:border-slate-700">
          <div
            v-for="change in defaultTemplateResetPreview.templateChanges"
            :key="`${change.changeType}:${change.templateName}`"
            class="flex items-start justify-between gap-3 py-1.5 text-xs"
          >
            <span class="min-w-0 break-words font-semibold">{{ change.templateName }}</span>
            <span class="shrink-0 text-right text-slate-500 dark:text-slate-400">
              {{ defaultTemplateResetChangeLabel(change.changeType) }}
              <span v-if="change.semanticsChanged"> · {{ t('app.templateSemanticsChanged') }}</span>
            </span>
          </div>
        </div>

        <div v-if="defaultTemplateResetPreview.affectedDevices.length" class="mt-3 text-xs">
          <div class="font-bold">{{ t('app.devicesUsingChangedTemplates') }}</div>
          <div class="mt-1 max-h-20 iot-scroll-region text-slate-600 dark:text-slate-300">
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
          <div class="font-bold">{{ t('app.environmentVariablesWillChange', {
            count: defaultTemplateResetPreview.environmentChanges.length
          }) }}</div>
          <ul class="mt-1 max-h-28 list-disc space-y-1 iot-scroll-region pl-5 text-slate-600 dark:text-slate-300">
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
          <div class="font-bold">{{ t('app.defaultTemplateResetBlocked') }}</div>
          <div
            v-for="(blocker, index) in defaultTemplateResetPreview.blockers"
            :key="`${blocker.itemLabel}:${index}`"
            class="mt-1 break-words"
          >
            <div>
              <strong>{{ blocker.itemLabel }}</strong>: {{ defaultTemplateResetBlockerReason(blocker.reasonCode) }}
            </div>
            <details class="mt-1 text-[11px] board-text-danger">
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
/* Modern panel effect */
.modern-panel {
  background: var(--board-panel-bg, linear-gradient(180deg, rgba(255, 255, 255, 0.95) 0%, rgba(248, 250, 252, 0.95) 100%));
  backdrop-filter: blur(20px);
  border: 1px solid var(--board-border);
}

/* Glass panel effect */
.glass-panel {
  background: var(--board-panel-bg);
  backdrop-filter: blur(16px);
  border: 1px solid var(--board-border);
}

/* Custom scrollbar */
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
}

.custom-scrollbar::-webkit-scrollbar-track {
  background: rgba(0, 0, 0, 0.02);
  border-radius: var(--iot-radius-well);
}

.custom-scrollbar::-webkit-scrollbar-thumb {
  background: #cbd5e1;
  border-radius: var(--iot-radius-well);
}

.custom-scrollbar::-webkit-scrollbar-thumb:hover {
  background: #94a3b8;
}

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
  gap: 0.35rem;
  border: 1px solid var(--board-border);
  border-radius: var(--iot-radius-well);
  background: var(--board-control-bg);
  padding: 0.25rem;
}

.control-mode-tabs button {
  min-width: 0;
  border: 0;
  border-radius: var(--iot-radius-action);
  padding: 0.45rem 0.35rem;
  color: var(--board-text-muted);
  font-size: var(--iot-font-min);
  font-weight: 800;
  line-height: 1.1;
  transition: background 0.18s ease, color 0.18s ease, box-shadow 0.18s ease;
  /* 44px floor: these measured 26px, and a mode switch is primary navigation. */
  min-height: 2.75rem;
}

.control-mode-tabs button.active {
  /*
   * `--accent-fill`, not a raw purple. `var(--accent)` under white ink measures **3.96:1** — under AA, and the kind
   * of near-miss nobody sees by looking. It was also a hue this component used nowhere else, so "the selected
   * mode" was said in a colour that meant nothing in particular.
   *
   * The fill half specifically: `--accent` alone is tuned as *text* and lightens in dark theme, where white
   * ink on it drops to 2.54:1.
   */
  background: var(--accent-fill);
  color: #ffffff;
  box-shadow: 0 8px 18px color-mix(in srgb, var(--accent-fill) 22%, transparent);
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
  font-size: var(--iot-font-min);
  font-weight: 700;
  line-height: 1.2;
}

.device-preview-box__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  color: var(--board-text-muted);
  font-size: var(--iot-font-min);
  font-weight: 800;
  letter-spacing: 0.03em;
  text-transform: uppercase;
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
  font-size: 0.7rem;
  font-weight: 800;
}

.device-preview-row small {
  min-width: 0;
  color: var(--board-text-muted);
  font-size: var(--iot-font-min);
  font-weight: 700;
  text-align: right;
}

.device-preview-row.has-error {
  border-color: color-mix(in srgb, var(--danger) 38%, var(--board-border));
  background: color-mix(in srgb, var(--danger) 9%, var(--board-card-bg));
}

.device-preview-row.has-error small {
  color: #dc2626;
}

.device-preview-row.has-warning {
  border-color: color-mix(in srgb, var(--warning) 42%, var(--board-border));
  background: color-mix(in srgb, var(--warning) 11%, var(--board-card-bg));
}

.device-preview-row.has-warning small {
  color: color-mix(in srgb, #d97706 88%, var(--board-text));
}

.device-preview-more,
.device-preview-empty {
  margin-top: 0.45rem;
  color: var(--board-text-muted);
  font-size: var(--iot-font-min);
  font-weight: 700;
  text-align: center;
}

.device-runtime-box {
  background: color-mix(in srgb, var(--board-card-bg) 88%, transparent);
  border-color: color-mix(in srgb, var(--accent) 26%, var(--board-border));
  color: var(--board-text);
}

/*
 * This box neutralises the Tailwind slate utilities its markup still carries, so its text follows the theme.
 * `span` is deliberately broad — but it also matched the one span that asks for a *role* colour, and because
 * this rule is scoped (`[data-v-…]`) it outranked the global `.iot-board .board-text-accent`, so the accent
 * icon rendered `rgb(148,163,184)` instead of the accent. Measured with CDP matched-styles; a global rule
 * could not have won this, and appending an identical rule last did not either.
 */
.device-runtime-box summary,
.device-runtime-box p,
.device-runtime-box span:not(.board-text-accent) {
  color: inherit;
}

.device-runtime-box input,
.device-runtime-box select {
  background: var(--board-card-bg);
  border-color: var(--board-border);
  color: var(--board-text);
}

.device-runtime-box input::placeholder {
  color: var(--board-text-muted);
}

.device-runtime-box .text-slate-400,
.device-runtime-box .text-slate-500,
.device-runtime-box .text-slate-600 {
  color: var(--board-text-muted);
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
  background: color-mix(in srgb, var(--warning) 13%, var(--board-card-bg));
}

.template-group__label,
.template-group__chevron {
  color: var(--board-text-muted);
}

.template-group__count {
  background: color-mix(in srgb, var(--warning) 16%, var(--board-control-bg));
  color: var(--board-text);
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

/*
 * Single-level token references, not three-deep fallback chains.
 *
 * These read `var(--board-panel-bg)`. Both fallbacks are unreachable:
 * `--board-*` is declared at `:root` in `board.css` (hoisted there precisely so teleported surfaces can
 * reach it), so the guard never fires — measured, all seven of these tokens have three `:root` definitions
 * in the shipped bundle, one per theme. What the chain did instead was put a light-theme hex in front of a
 * reader trying to learn what a dark panel is painted with, implying the dark theme might degrade to a white
 * card. That is the "silent fallback" the root CLAUDE.md rules out: robustness for a case that cannot occur,
 * paid for in comprehension.
 */
/* The card shell (background, border, radius, max-height) comes from `.iot-dialog`; what stays here is the
   board ink context the body content below is written against. */
.control-center-dialog-surface {
  color: var(--board-text);
}

.control-center-dialog-surface :is(input, select, textarea) {
  background: var(--board-card-bg) !important;
  border-color: var(--board-border) !important;
  color: var(--board-text) !important;
}

/* These controls declare `focus:outline-none focus:border-[color:var(--accent-border)]`, i.e. the border colour is meant
   to be the focus cue — but the `!important` border-colour above wins, leaving keyboard users with
   no indicator at all. An outline is a different property, so it cannot be overridden the same way. */
.control-center-dialog-surface :is(input, select, textarea):focus-visible {
  outline: 2px solid color-mix(in srgb, var(--iot-color-accent) 85%, transparent);
  outline-offset: 1px;
}

.control-center-dialog-surface :is(.bg-white, .bg-slate-50, .bg-slate-100, .bg-slate-200) {
  background-color: var(--board-card-bg) !important;
}

.control-center-dialog-surface .board-chip-danger {
  background-color: color-mix(in srgb, var(--danger) 12%, var(--board-card-bg)) !important;
}

.control-center-dialog-surface .board-chip-warning {
  background-color: color-mix(in srgb, var(--warning) 12%, var(--board-card-bg)) !important;
}

.control-center-dialog-surface :is(.text-black, .text-slate-700, .text-slate-800, .text-slate-900) {
  color: var(--board-text) !important;
}

.control-center-dialog-surface :is(.text-slate-400, .text-slate-500, .text-slate-600) {
  color: var(--board-text-muted) !important;
}

.control-center-dialog-surface :is(.border-slate-100, .border-slate-200, .border-slate-300, .border-slate-400) {
  border-color: var(--board-border) !important;
}

.template-reset-dialog__notice {
  background: color-mix(in srgb, var(--warning) 8%, var(--board-panel-bg));
  border-color: color-mix(in srgb, var(--warning) 28%, var(--board-border));
  color: var(--board-text);
}

.template-reset-dialog__spinner {
  width: 0.9rem;
  height: 0.9rem;
  border: 2px solid rgba(255, 255, 255, 0.38);
  border-top-color: #ffffff;
  border-radius: var(--iot-radius-pill);
  animation: template-reset-spin 0.8s linear infinite;
}

@keyframes template-reset-spin {
  to { transform: rotate(360deg); }
}

.template-card {
  z-index: 0;
  min-height: 5.15rem;
  background: var(--board-card-bg);
  border-color: var(--board-border);
  color: var(--board-text);
  cursor: pointer;
}

.template-card[draggable="true"] {
  user-select: none;
}

.template-card[draggable="true"]:active {
  cursor: grabbing;
}

.template-card:hover,
.template-card:focus-visible,
.template-card--active {
  z-index: 30;
  /* Tokens, not hex: `var(--warning)` and `#e2e8f0` are a light-theme amber and a light border, so on a dark card
     they painted a light-theme treatment. The role tokens resolve per theme. */
  border-color: color-mix(in srgb, var(--warning) 58%, var(--board-border));
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.16);
}

/*
 * Focus gets a real indicator, and one that is not the same as hover.
 *
 * The card shared hover's styling and then set `outline: none`, so tabbing through the template list produced
 * a lift and a shadow but no ring — measured **1.07:1**, i.e. no discernible indicator at all. Two problems
 * in one: a keyboard user could not see where they were, and could not tell "focused" from "hovered" even
 * when they could. The list is 45 default templates long, so this is where losing the cursor costs most.
 *
 * The ring goes on the **inner button**, because that is what actually receives focus: the card is a `div`
 * wrapping a transparent full-width `button`. Styling `.template-card:focus-visible` looked right and did
 * nothing in dark theme — it passed in light only because the browser's own default outline happened to be
 * visible there, which is the kind of accidental pass that makes a fix look complete when it is not.
 */
.template-card > button:focus-visible {
  outline: 2px solid var(--accent-border);
  outline-offset: 3px;
  border-radius: var(--iot-radius-control);
}

.template-card__icon {
  background: color-mix(in srgb, var(--warning) 13%, var(--board-control-bg));
}

.template-card:hover .template-card__icon,
.template-card--active .template-card__icon {
  background: color-mix(in srgb, var(--warning) 22%, var(--board-control-bg));
}

.template-card__title {
  color: var(--board-text);
}

.template-card:hover .template-card__title,
.template-card--active .template-card__title {
  color: color-mix(in srgb, var(--warning) 78%, var(--board-text));
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

.template-card:hover .template-card__drag-cue,
.template-card--active .template-card__drag-cue {
  color: color-mix(in srgb, var(--warning) 85%, var(--board-text));
  opacity: 1;
}

.template-card__pill {
  background: var(--board-control-bg);
  color: var(--board-text-muted);
}

.template-card__actions {
  border-color: var(--board-border);
}

.template-card__action {
  display: inline-flex;
  min-width: 2rem;
  min-height: 2rem;
  align-items: center;
  justify-content: center;
  color: var(--board-text-muted);
}

@media (pointer: coarse) {
  .template-card__action {
    min-width: 2.75rem;
    min-height: 2.75rem;
  }
}

.template-card__action:hover {
  color: color-mix(in srgb, var(--warning) 82%, var(--board-text));
  background: color-mix(in srgb, var(--warning) 15%, var(--board-control-bg));
}

.template-card__action--danger:hover {
  color: var(--danger);
  background: color-mix(in srgb, var(--danger) 14%, var(--board-control-bg));
}

.template-preview {
  --template-preview-card-bg: var(--board-card-bg);
  --template-preview-control-bg: var(--board-control-bg);
  --template-preview-border: var(--board-border);
  --template-preview-text: var(--board-text);
  --template-preview-muted: var(--board-text-muted);

  position: fixed;
  z-index: 45;
  box-sizing: border-box;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 0.75rem;
  border: 1px solid var(--template-preview-border);
  border-radius: var(--iot-radius-well);
  background: color-mix(in srgb, var(--template-preview-card-bg) 96%, transparent);
  color: var(--template-preview-text);
  backdrop-filter: blur(16px);
  box-shadow: 0 22px 55px rgba(15, 23, 42, 0.24);
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
  font-size: var(--iot-font-min);
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.template-preview__title {
  margin: 0.1rem 0 0;
  color: var(--template-preview-text);
  font-size: 0.82rem;
  font-weight: 800;
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
  font-size: 0.7rem;
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
  font-size: var(--iot-font-min);
  font-weight: 800;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.template-preview__meta strong {
  display: block;
  min-width: 0;
  margin-top: 0.15rem;
  overflow: hidden;
  color: var(--template-preview-text);
  font-size: 0.72rem;
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
  overflow: hidden;
  border-radius: var(--iot-radius-pill);
  padding: 0.18rem 0.45rem;
  background: var(--template-preview-control-bg);
  color: var(--template-preview-text);
  font-size: var(--iot-font-min);
  text-overflow: ellipsis;
  white-space: nowrap;
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
    box-shadow: 0 0 0 0 rgba(100, 116, 139, 0.4);
  }
  100% {
    box-shadow: 0 0 0 4px rgba(100, 116, 139, 0.1);
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
