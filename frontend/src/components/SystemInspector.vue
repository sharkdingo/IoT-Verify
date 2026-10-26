<script setup lang="ts">
import FieldSelect from '@/components/common/FieldSelect.vue'
import HintTooltip from '@/components/common/HintTooltip.vue'
import { ref, reactive, computed, watch, onUnmounted } from 'vue'
import { COLLAPSED_PANEL_RAIL_CSS } from '@/constants/boardLayout'
import type { DeviceNode } from '../types/node'
import type { DeviceTemplate, InternalVariable, WorkingState } from '../types/device'
import type {
  EnvironmentVariableUpdateRequest,
  ModelEnvironmentVariable
} from '@/types/model'
import type { RuleForm } from '../types/rule'
import type { Specification } from '../types/spec'
import { specTemplateDetails } from '../assets/config/specTemplates'
import { useI18n } from 'vue-i18n'
import { buildSpecFormulaPreview } from '@/utils/spec'
import { describeSpecification, presentRule } from '@/utils/modelPresentation'
import { verdictVariableSourceKeys } from '@/views/board/verdictVariableSource'
import {
  canonicalNaturalChangeRate,
  naturalChangeCandidateValues,
  resolveImpactEnvironmentDefinition
} from '@/utils/device'
import {
  getTemplateVariableDefaultValue,
  templateVariableHasEnumValues,
  templateVariableUsesNumericBounds,
  validateTemplateVariableValue
} from '@/utils/deviceRuntime'
import FailureDetails from '@/components/FailureDetails.vue'
import type { ApiErrorDetails } from '@/utils/userMessage'
import { useAuth } from '@/stores/auth'
import { formatBuiltInModelToken } from '@/utils/modelTokenDisplay'
import { hasModeledStateMachine, resolveEffectiveNodeState } from '@/utils/canvas/nodeState'
import InfoTooltip from '@/components/common/InfoTooltip.vue'
import { useRovingTablist } from '@/composables/useRovingTablist'
import { notifyBlocked } from '@/utils/feedback'

const { t, te } = useI18n()
const { getToken } = useAuth()

// Props
interface Props {
  devices?: DeviceNode[]
  deviceTemplates?: DeviceTemplate[]
  environmentVariables?: ModelEnvironmentVariable[]
  rules?: RuleForm[]
  specifications?: Specification[]
  focusedDeviceId?: string | null
  focusedRuleId?: string | null
  focusedSpecId?: string | null
  collapsed?: boolean
  width?: number
  activeSection?: string
  readOnly?: boolean
  readOnlyMessage?: string
  environmentSaving?: boolean
  rulesReordering?: boolean
  /**
   * The authoritative board snapshot failed to load.
   *
   * Without this the panel renders "No devices on canvas" during a failed load -- a factual claim it
   * cannot actually verify, and one that reads identically to a genuinely empty board. The load
   * banner disambiguates it, but the empty state should not assert something it does not know.
   */
  dataUnavailable?: boolean
  dataLoading?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  devices: () => [],
  deviceTemplates: () => [],
  environmentVariables: () => [],
  rules: () => [],
  specifications: () => [],
  width: 320,
  // Vue otherwise casts an absent Boolean prop to false and treats it as controlled.
  collapsed: undefined,
  readOnly: false,
  readOnlyMessage: '',
  environmentSaving: false,
  rulesReordering: false,
  dataUnavailable: false,
  dataLoading: false
})

const emptyDataStatusKey = computed(() => props.dataUnavailable
  ? 'app.boardDataUnavailableShort'
  : props.dataLoading ? 'app.boardSnapshotLoading' : null)

const ensureWritable = (): boolean => {
  if (!props.readOnly) return true
  notifyBlocked(props.readOnlyMessage || t('app.playbackReadOnlyCloseFirst'))
  return false
}

const mutationTitle = (fallback: string): string =>
  props.readOnly ? (props.readOnlyMessage || t('app.playbackReadOnlyCloseFirst')) : fallback

// Panel state
const localCollapsed = ref(typeof window !== 'undefined' && window.innerWidth < 768)
const expandedEnvironmentVariables = ref<Set<string>>(new Set())
const panelBody = ref<HTMLElement | null>(null)
type InspectorSection = 'devices' | 'rules' | 'specs' | 'environment'

const isEnvironmentVariableExpanded = (name: string) =>
  expandedEnvironmentVariables.value.has(name)

const toggleEnvironmentVariable = (name: string) => {
  const next = new Set(expandedEnvironmentVariables.value)
  if (next.has(name)) {
    next.delete(name)
  } else {
    next.add(name)
  }
  expandedEnvironmentVariables.value = next
}

const localActiveSection = ref<InspectorSection>('devices')
const sectionSearch = reactive<Record<InspectorSection, string>>({
  devices: '', rules: '', specs: '', environment: ''
})
const debouncedSectionSearch = reactive<Record<InspectorSection, string>>({
  devices: '', rules: '', specs: '', environment: ''
})
const searchDebounceTimers: Partial<Record<InspectorSection, ReturnType<typeof setTimeout>>> = {}
for (const section of ['devices', 'rules', 'specs', 'environment'] as const) {
  watch(() => sectionSearch[section], value => {
    clearTimeout(searchDebounceTimers[section])
    searchDebounceTimers[section] = setTimeout(() => {
      debouncedSectionSearch[section] = value
    }, 200)
  })
}
onUnmounted(() => Object.values(searchDebounceTimers).forEach(clearTimeout))

const isInspectorSection = (value?: string): value is InspectorSection =>
  value === 'devices' || value === 'rules' || value === 'specs' || value === 'environment'

// Emits
const emit = defineEmits<{
  'delete-device': [deviceId: string]
  'delete-rule': [ruleId: string]
  'edit-rule': [ruleId: string]
  'move-rule': [ruleId: string, direction: 'up' | 'down']
  'delete-spec': [specId: string]
  'edit-spec': [specId: string]
  'open-rule-builder': []
  'open-control-section': [section: 'devices' | 'rules' | 'specs']
  'device-click': [deviceId: string]
  'save-environment': [variables: EnvironmentVariableUpdateRequest[], complete: (saved: boolean, failure?: ApiErrorDetails) => void]
  'update:collapsed': [value: boolean]
  'update:active-section': [value: InspectorSection]
}>()

const isCollapsed = computed({
  get: () => props.collapsed ?? localCollapsed.value,
  set: (value: boolean) => {
    localCollapsed.value = value
    emit('update:collapsed', value)
  }
})

const resolvedPanelWidth = computed(() =>
  Math.min(520, Math.max(240, Number.isFinite(props.width) ? props.width : 320)))

const panelWidth = computed(() => `${resolvedPanelWidth.value}px`)

const activeSection = computed<InspectorSection>({
  // `activeSection` is optional: when a parent controls it, the prop is authoritative;
  // when it is absent the panel owns its own selection. Without this the uncontrolled
  // case silently ignored every selection change.
  get: () => isInspectorSection(props.activeSection) ? props.activeSection : localActiveSection.value,
  set: (value: InspectorSection) => {
    localActiveSection.value = value
    emit('update:active-section', value)
  }
})

watch(activeSection, () => {
  if (panelBody.value) panelBody.value.scrollTop = 0
}, { flush: 'post' })

// Convert authoritative device nodes to display format.
const displayDevices = computed(() => {
  return props.devices
    .map(device => {
      const template = findTemplateForDevice(device)
      const hasStateMachine = hasModeledStateMachine(template?.manifest)
      const canonicalState = hasStateMachine
        ? resolveEffectiveNodeState(device.state, template?.manifest)
        : ''
      return {
        id: device.id,
        name: device.label,
        type: device.templateName || t('app.device'),
        state: template?.defaultTemplate === true
          ? formatModelToken(canonicalState)
          : canonicalState,
        canonicalState
      }
    })
})

const normalizeEntitySearch = (value?: string | null) =>
  String(value || '').trim().toLowerCase()

const matchesEntitySearch = (haystack: Array<unknown>, query: string) => {
  const normalizedQuery = normalizeEntitySearch(query)
  if (!normalizedQuery) return true
  return haystack.some(value => normalizeEntitySearch(String(value ?? '')).includes(normalizedQuery))
}

const normalizeLookupName = (value?: string | null) =>
  String(value || '').trim().toLowerCase()

// 创建模板索引以优化查找性能
const templatesByName = computed(() => {
  const map = new Map<string, DeviceTemplate>()
  for (const template of props.deviceTemplates) {
    const nameKey = normalizeLookupName(template.name)
    if (nameKey) map.set(nameKey, template)
    const manifestKey = normalizeLookupName(template.manifest?.Name)
    if (manifestKey && manifestKey !== nameKey) map.set(manifestKey, template)
  }
  return map
})

const findTemplateForDevice = (device: DeviceNode) => {
  const key = normalizeLookupName(device.templateName)
  return key ? (templatesByName.value.get(key) || null) : null
}

const isBundledTemplate = (template?: DeviceTemplate | null) =>
  template?.defaultTemplate === true

const formatModelToken = (value: unknown) => formatBuiltInModelToken(
  value,
  key => te(key) ? t(key) : key
)

const isBundledDevice = (device?: DeviceNode) =>
  Boolean(device && isBundledTemplate(findTemplateForDevice(device)))

/* The fifth copy of the single-bound laxity `utils/deviceRuntime.ts` already owns: this accepted
   *either* bound and filled the missing side with an infinity the schema cannot produce
   (`oneOf: [{Values}, {LowerBound, UpperBound}]`), so it advertised a shape the server refuses to
   store. Two such declarations also passed every domain-conflict check — those key off "both bounds
   present" — and surfaced as "mixed ranges" instead of a named mismatch. Use the owner's predicate. */
const getVariableRange = (variable: InternalVariable) => {
  if (templateVariableHasEnumValues(variable)) {
    return variable.Values!.join(' / ')
  }
  if (templateVariableUsesNumericBounds(variable)) {
    return `${variable.LowerBound} - ${variable.UpperBound}`
  }
  return t('app.modelControlled')
}

const uniqueNonEmpty = (values: Array<string | undefined>) =>
  Array.from(new Set(values.map(value => String(value || '').trim()).filter(Boolean)))

/* The fourth copy of this rule lived here, and it carried the same single-bound laxity the schema forbids.
   `getTemplateVariableDefaultValue` in `utils/deviceRuntime.ts` is the owner — see the note there. */
const defaultEnvironmentValue = (variable: InternalVariable) =>
  getTemplateVariableDefaultValue(variable)

const normalizeTrust = (value?: string | null) =>
  value === 'trusted' ? 'trusted' : 'untrusted'

const normalizePrivacy = (value?: string | null) =>
  value === 'private' ? 'private' : 'public'

const environmentPoolByName = computed(() => {
  const result = new Map<string, ModelEnvironmentVariable>()
  for (const variable of props.environmentVariables || []) {
    const name = String(variable?.name || '').trim()
    if (name) result.set(normalizeLookupName(name), variable)
  }
  return result
})

type EnvironmentSourceRole = 'read' | 'impact'

interface EnvironmentSource {
  deviceId: string
  label: string
  role: EnvironmentSourceRole
  effects: EnvironmentEffect[]
}

interface EnvironmentEffect {
  state: string
  value: string
  bundled: boolean
}

interface EnvironmentGroup {
  name: string
  definition: InternalVariable
  bundled: boolean
  ranges: string[]
  sources: EnvironmentSource[]
  conflicts: string[]
}

/* Aliases, not new rules: these were local re-implementations of the `utils/deviceRuntime.ts` pair
   that treated an explicit `null` bound as a domain where the owner does not. Board responses do
   serialize an absent bound as `null`, so the two answered differently for the same variable. */
const hasEnumDomain = templateVariableHasEnumValues
const hasNumericDomain = templateVariableUsesNumericBounds

const environmentDefinitionIncompatibility = (
  leftName: string,
  left: InternalVariable,
  rightName: string,
  right: InternalVariable
): string | null => {
  if (leftName !== rightName) {
    return t('app.environmentConflictName', { left: leftName, right: rightName })
  }
  if (hasEnumDomain(left) !== hasEnumDomain(right)
      || hasNumericDomain(left) !== hasNumericDomain(right)) {
    return t('app.environmentConflictType')
  }
  if (hasNumericDomain(left)
      && (left.LowerBound !== right.LowerBound || left.UpperBound !== right.UpperBound)) {
    return t('app.environmentConflictRange', {
      left: `${left.LowerBound}..${left.UpperBound}`,
      right: `${right.LowerBound}..${right.UpperBound}`
    })
  }
  if (hasEnumDomain(left)
      && JSON.stringify(left.Values) !== JSON.stringify(right.Values)) {
    return t('app.environmentConflictValues')
  }
  const leftRate = canonicalNaturalChangeRate(left.NaturalChangeRate)
  const rightRate = canonicalNaturalChangeRate(right.NaturalChangeRate)
  if (leftRate !== rightRate) {
    return t('app.environmentConflictNaturalRate', { left: leftRate, right: rightRate })
  }
  const leftTrust = normalizeTrust(left.Trust)
  const rightTrust = normalizeTrust(right.Trust)
  if (leftTrust !== rightTrust) {
    return t('app.environmentConflictTrust', { left: t(`app.${leftTrust}`), right: t(`app.${rightTrust}`) })
  }
  const leftPrivacy = normalizePrivacy(left.Privacy)
  const rightPrivacy = normalizePrivacy(right.Privacy)
  if (leftPrivacy !== rightPrivacy) {
    return t('app.environmentConflictPrivacy', {
      left: t(`app.${leftPrivacy}`),
      right: t(`app.${rightPrivacy}`)
    })
  }
  return null
}

const environmentEffectsFor = (
  template: DeviceTemplate | null | undefined,
  name: string
): EnvironmentEffect[] => (template?.manifest?.WorkingStates || [])
  .flatMap((state: WorkingState) => (state.Dynamics || [])
    .filter(dynamic => dynamic.VariableName === name)
    .map(dynamic => ({
      state: state.Name,
      value: dynamic.ChangeRate !== undefined
        ? t('app.environmentRateEffect', { rate: dynamic.ChangeRate })
        : t('app.environmentValueEffect', {
            value: isBundledTemplate(template) ? formatModelToken(dynamic.Value) : String(dynamic.Value || '')
          }),
      bundled: isBundledTemplate(template)
    })))

const environmentDefinitionFor = (
  name: string,
  template?: DeviceTemplate | null,
  preferred?: InternalVariable
): InternalVariable => {
  if (preferred) return preferred
  const sameTemplateDefinition = resolveImpactEnvironmentDefinition(template?.manifest, name)
  if (sameTemplateDefinition) return sameTemplateDefinition
  return {
    Name: name,
    IsInside: false,
    FalsifiableWhenCompromised: false,
    Trust: 'untrusted',
    Privacy: 'public'
  }
}

const addEnvironmentGroup = (
  grouped: Map<string, EnvironmentGroup>,
  name: string,
  definition: InternalVariable,
  source?: EnvironmentSource,
  bundled = false
) => {
  const key = normalizeLookupName(name)
  const existing = grouped.get(key)
  const current = existing || {
    name,
    definition,
    bundled,
    ranges: [],
    sources: [],
    conflicts: []
  }
  if (existing) {
    const mismatch = environmentDefinitionIncompatibility(
      current.name, current.definition, name, definition)
    if (mismatch && !current.conflicts.includes(mismatch)) current.conflicts.push(mismatch)
  }
  if (existing) current.bundled = current.bundled && bundled
  current.ranges.push(getVariableRange(definition))
  if (source && !current.sources.some(item => item.deviceId === source.deviceId && item.role === source.role)) {
    current.sources.push(source)
  }
  grouped.set(key, current)
}

const environmentVariables = computed(() => {
  const grouped = new Map<string, EnvironmentGroup>()

  for (const device of props.devices) {
    const template = findTemplateForDevice(device)
    const variables = template?.manifest?.InternalVariables || []
    for (const variable of variables) {
      if (!variable?.Name || variable.IsInside === true) continue
      // Reads=false is an affect-only declaration: it supplies the domain and the device's effect
      // but no read. Claiming "reads this environment variable" for it would tell the user the
      // opposite of what the generator compiles -- the device gets no read mirror and its rules
      // cannot use the value as a condition source.
      if (variable.Reads === false) continue

      addEnvironmentGroup(grouped, variable.Name, variable, {
        deviceId: device.id,
        label: device.label,
        role: 'read',
        effects: []
      }, isBundledTemplate(template))
    }

    for (const impacted of template?.manifest?.ImpactedVariables || []) {
      const name = String(impacted || '').trim()
      if (!name) continue
      addEnvironmentGroup(grouped, name, environmentDefinitionFor(name, template), {
        deviceId: device.id,
        label: device.label,
        role: 'impact',
        effects: environmentEffectsFor(template, name)
      }, isBundledTemplate(template))
    }
  }

  for (const saved of props.environmentVariables || []) {
    const name = String(saved?.name || '').trim()
    if (!name || grouped.has(normalizeLookupName(name))) continue
    addEnvironmentGroup(grouped, name, environmentDefinitionFor(name))
  }

  return Array.from(grouped.values())
    .map(variable => {
      const ranges = uniqueNonEmpty(variable.ranges).map(range => variable.bundled
        ? range.split(' / ').map(formatModelToken).join(' / ')
        : range)
      const saved = environmentPoolByName.value.get(normalizeLookupName(variable.name))
      const authoritativeValue = typeof saved?.value === 'string' ? saved.value.trim() : ''
      const value = authoritativeValue !== ''
        ? String(saved!.value)
        : defaultEnvironmentValue(variable.definition)
      // A compare-and-set edit needs a non-blank authoritative baseline value. A variable with
      // no declared value domain materializes blank and is not verifiable, so its controls are
      // shown disabled with an explanation instead of silently discarding edits.
      const editable = authoritativeValue !== '' && variable.conflicts.length === 0
      const trust = normalizeTrust(saved?.trust || variable.definition.Trust)
      const privacy = normalizePrivacy(saved?.privacy || variable.definition.Privacy)
      return {
        ...variable,
        readers: variable.sources.filter(source => source.role === 'read'),
        writers: variable.sources.filter(source => source.role === 'impact'),
        displayName: variable.bundled ? formatModelToken(variable.name) : variable.name,
        // No "mixed ranges" fallback: two declarations that render different ranges necessarily differ
        // in domain shape or bounds, which `environmentDefinitionIncompatibility` already reports above
        // as a named conflict. The old fallback could only be reached by a single-bound declaration the
        // schema rejects, and it replaced a specific mismatch with a vague label.
        rangeLabel: variable.conflicts.length > 0
          ? t('app.conflictingDefinitions')
          : ranges[0] || t('app.modelControlled'),
        naturalChangeRateLabel: hasNumericDomain(variable.definition)
          ? (variable.definition.NaturalChangeRate
              ? t('app.environmentNumericEvolution', {
                  rate: variable.definition.NaturalChangeRate,
                  candidates: naturalChangeCandidateValues(variable.definition.NaturalChangeRate)
                })
              : t('app.environmentNaturalRateMissing'))
          // Branch on authorship exactly as the generator does: a value some device declares it
          // writes holds when no effect applies, while one nobody writes is an exogenous input the
          // verifier may move freely. Describing both as "nondeterministic" told the user the
          // opposite of what gets verified for every device-written value.
          : (variable.sources.some(source => source.role === 'impact')
              ? t('app.environmentDiscreteWrittenEvolution')
              : t('app.environmentDiscreteExogenousEvolution')),
        evolutionEffects: variable.sources.flatMap(source => source.effects.map(effect => ({
          ...effect,
          deviceId: source.deviceId,
          deviceLabel: source.label,
          stateLabel: effect.bundled ? formatModelToken(effect.state) : effect.state
        }))),
        value,
        editable,
        valueLabel: value
          ? (variable.bundled ? formatModelToken(value) : value)
          : t('app.modelControlled'),
        valueTitle: value
          ? (variable.bundled ? formatModelToken(value) : value)
          : t('app.modelControlled'),
        trust,
        privacy,
        trustLabel: t(`app.${trust}`),
        privacyLabel: t(`app.${privacy}`),
        enumValues: Array.isArray(variable.definition.Values) ? variable.definition.Values : [],
        lowerBound: variable.definition.LowerBound,
        upperBound: variable.definition.UpperBound
      }
    })
    .sort((a, b) => a.name.localeCompare(b.name))
})

watch(environmentVariables, variables => {
  const availableNames = new Set(variables.map(variable => variable.name))
  const next = new Set(
    Array.from(expandedEnvironmentVariables.value)
      .filter(name => availableNames.has(name))
  )
  if (next.size !== expandedEnvironmentVariables.value.size) {
    expandedEnvironmentVariables.value = next
  }
})

const getEnvironmentSourceTitle = (source: EnvironmentSource) =>
  `${source.role === 'impact' ? t('app.affectsEnvironment') : t('app.readsEnvironment')}: ${source.label}`

type EnvironmentVariableEdit = Partial<Record<'value' | 'trust' | 'privacy', string>>
const environmentEdits = reactive<Record<string, { value?: string, failure?: ApiErrorDetails }>>({})
let environmentEditorActive = true
onUnmounted(() => { environmentEditorActive = false })
watch(() => getToken(), () => {
  for (const name of Object.keys(environmentEdits)) delete environmentEdits[name]
})
watch(environmentVariables, variables => {
  for (const name of Object.keys(environmentEdits)) {
    const variable = variables.find(item => item.name === name)
    if (!variable || (environmentEdits[name].value !== undefined && variable.value === environmentEdits[name].value)) {
      delete environmentEdits[name]
    }
  }
})

const editEnvironmentValue = (name: string, value: string) => {
  environmentEdits[name] = { value }
}
const environmentValueFailures = computed(() => Object.fromEntries(environmentVariables.value.map(variable => {
  const edit = environmentEdits[variable.name]
  const reason = edit?.value === undefined ? '' : !edit.value.trim() ? t('app.variableValueRequired')
    : validateTemplateVariableValue(variable.definition, edit.value, t)
  return [variable.name, reason ? { cause: reason, details: [], fields: {} } : edit?.failure]
})))

// Keep the focused field's diagnostic visible without moving keyboard focus.
watch(environmentValueFailures, () => {
  const field = document.activeElement
  if (!panelBody.value?.contains(field) || !field?.matches('[aria-invalid="true"]')) return
  field.closest('label')?.querySelector<HTMLElement>('[role="alert"]')
    ?.scrollIntoView({ block: 'nearest' })
}, { flush: 'post' })

const updateEnvironmentVariable = (
  name: string,
  patch: EnvironmentVariableEdit
) => {
  if (!ensureWritable()) return
  const displayed = environmentVariables.value.find(variable => variable.name === name)
  if (!displayed?.editable) return
  if (patch.value !== undefined) {
    editEnvironmentValue(name, patch.value)
    const reason = !patch.value.trim() ? t('app.variableValueRequired')
      : validateTemplateVariableValue(displayed.definition, patch.value, t)
    if (reason) {
      return
    }
  }
  const saved = environmentPoolByName.value.get(normalizeLookupName(name))
  const authoritativeValue = typeof saved?.value === 'string' ? saved.value.trim() : ''
  if (!authoritativeValue) return
  // The displayed effective labels combine the authoritative row with the same template fallbacks
  // the backend materializes. Reuse them so the CAS baseline cannot contradict the visible value.
  const expected: EnvironmentVariableUpdateRequest['expected'] = {
    value: authoritativeValue,
    trust: displayed.trust,
    privacy: displayed.privacy
  }
  const desired: EnvironmentVariableUpdateRequest['desired'] = {}
  for (const field of ['value', 'trust', 'privacy'] as const) {
    const next = patch[field]
    if (Object.prototype.hasOwnProperty.call(patch, field) && typeof next === 'string') {
      desired[field] = next
    }
  }
  environmentEdits[name] = { value: environmentEdits[name]?.value }
  const edit = environmentEdits[name]
  const requestToken = getToken()
  emit('save-environment', [{ name, expected, desired }], (saved, failure) => {
    if (!environmentEditorActive || getToken() !== requestToken || environmentEdits[name] !== edit) return
    if (saved && (patch.value !== undefined || edit.value === undefined)) delete environmentEdits[name]
    else if (failure) edit.failure = failure
  })
}

const eventValue = (event: Event) =>
  (event.target as HTMLInputElement | HTMLSelectElement | null)?.value || ''

const formatEnvironmentValue = (variable: EnvironmentGroup, value: string) =>
  variable.bundled ? formatModelToken(value) : value

// 创建设备索引以优化查找性能
const devicesById = computed(() => {
  const map = new Map<string, DeviceNode>()
  for (const device of props.devices) {
    map.set(device.id, device)
  }
  return map
})

const resolveDevice = (ref?: string) =>
  ref ? devicesById.value.get(ref) : undefined

const modelPresentationContext = () => ({
  t,
  resolveDevice,
  formatToken: (id: string, value: unknown) => isBundledDevice(resolveDevice(id))
    ? formatModelToken(value) : String(value ?? ''),
  modeCount: (id: string) => {
    const device = resolveDevice(id)
    return device ? findTemplateForDevice(device)?.manifest.Modes?.length || 0 : 0
  }
})

// Convert real rules to display format
const displayRules = computed(() => {
  return props.rules.map((rule, index) => {
    const targetNode = resolveDevice(rule.toId)
    
    const presentation = presentRule(rule, modelPresentationContext())

    return {
      originalId: rule.id, // 保留原始id用于删除操作
      executionOrder: index + 1,
      isFirst: index === 0,
      isLast: index === props.rules.length - 1,
      id: rule.id ? rule.id.replace('rule_', '') : 'unknown',
      name: rule.name || t('app.ruleFrom', { source: (rule.id ? rule.id.replace('rule_', '') : '').split('_')[1] || t('app.unknown') }),
      ...presentation,
      searchText: [
        rule.id,
        rule.name,
        presentation.conditions.join(' '),
        targetNode?.id,
        targetNode?.label,
        rule.toApi,
        rule.contentDevice,
        resolveDevice(rule.contentDevice)?.label,
        rule.content
      ].join(' ')
    }
  })
})

// Convert real specifications to display format
const displaySpecs = computed(() => {
  return props.specifications.map(spec => {
    const template = specTemplateDetails.find(candidate => candidate.id === spec.templateId)
    const specType = template?.labelKey
      ? t(template.labelKey)
      : spec.templateLabel || template?.label || t('app.unknown')

    const preview = buildSpecFormulaPreview(spec, { nodes: props.devices })

    return {
      id: spec.id,
      type: spec.name,
      formula: preview.expression,
      formulaKind: preview.kind,
      description: describeSpecification(spec, modelPresentationContext()),
      // The formula alone renders the two readings as one differing token in a monospace string, and an
      // unrecorded one as a literal `<unresolved>`. This is the surface where a user scans their whole
      // specification set, so it names the reading in the same words the editor and verdict rows use.
      variableSourceLabels: verdictVariableSourceKeys(spec)
        .map(key => ({ key, label: t(key), unresolved: key === 'app.specVariableSourceUnresolvedShort' })),
      searchText: [
        spec.id,
        spec.name,
        spec.templateId,
        spec.templateLabel,
        specType,
        ...(spec.devices || []).flatMap(device => [device.deviceId, device.deviceLabel, ...(device.selectedApis || [])]),
        ...(spec.aConditions || []).flatMap(condition => [condition.deviceId, condition.deviceLabel, condition.targetType, condition.propertyScope, condition.variableSource, condition.key, condition.relation, condition.value]),
        ...(spec.ifConditions || []).flatMap(condition => [condition.deviceId, condition.deviceLabel, condition.targetType, condition.propertyScope, condition.variableSource, condition.key, condition.relation, condition.value]),
        ...(spec.thenConditions || []).flatMap(condition => [condition.deviceId, condition.deviceLabel, condition.targetType, condition.propertyScope, condition.variableSource, condition.key, condition.relation, condition.value])
      ].join(' ')
    }
  })
})

const filteredDevices = computed(() =>
  displayDevices.value.filter(device =>
    matchesEntitySearch(
      [device.id, device.name, device.type, device.state, device.canonicalState],
      debouncedSectionSearch.devices
    )
  )
)

const filteredRules = computed(() =>
  displayRules.value.filter(rule =>
    matchesEntitySearch([rule.id, rule.name, rule.description, rule.searchText], debouncedSectionSearch.rules)
  )
)

const filteredSpecs = computed(() =>
  displaySpecs.value.filter(spec =>
    matchesEntitySearch([spec.id, spec.description, spec.formulaKind, spec.formula, spec.searchText], debouncedSectionSearch.specs)
  )
)

const filteredEnvironmentVariables = computed(() =>
  environmentVariables.value.filter(variable => matchesEntitySearch([
    variable.name, variable.displayName, variable.rangeLabel, variable.valueLabel,
    variable.trustLabel, variable.privacyLabel, ...variable.sources.map(source => source.label)
  ], debouncedSectionSearch.environment))
)

// Tab totals do not need the descriptions and formulas of inactive inventories.
const sectionTotals = computed(() => ({
  devices: props.devices.length,
  rules: props.rules.length,
  specs: props.specifications.length,
  environment: environmentVariables.value.length
}))

const filteredSectionCount = computed(() => {
  switch (activeSection.value) {
    case 'devices': return filteredDevices.value.length
    case 'rules': return filteredRules.value.length
    case 'specs': return filteredSpecs.value.length
    case 'environment': return filteredEnvironmentVariables.value.length
  }
})

const clearSectionSearch = (section: InspectorSection) => {
  sectionSearch[section] = ''
}

const inspectorTabs = computed(() => [
  {
    id: 'devices' as const,
    label: t('app.devicesTool'),
    count: sectionTotals.value.devices
  },
  {
    id: 'rules' as const,
    label: t('app.rulesTool'),
    count: sectionTotals.value.rules
  },
  {
    id: 'specs' as const,
    label: t('app.specificationsShort'),
    count: sectionTotals.value.specs
  },
  {
    id: 'environment' as const,
    label: t('app.environmentTool'),
    count: sectionTotals.value.environment
  }
])

const { handleTablistKeydown: handleInspectorTabKeydown } = useRovingTablist<InspectorSection>({
  tabIds: () => inspectorTabs.value.map(tab => tab.id),
  select: id => { activeSection.value = id },
  tabElementId: id => `inspector-tab-${id}`
})

// Methods
const handleDeleteDevice = (deviceId: string) => {
  if (!ensureWritable()) return
  emit('delete-device', deviceId)
}

const handleDeleteRule = (ruleId: string) => {
  if (!ensureWritable()) return
  emit('delete-rule', ruleId)
}

const handleMoveRule = (ruleId: string, direction: 'up' | 'down') => {
  if (!ensureWritable() || props.rulesReordering || sectionSearch.rules) return
  emit('move-rule', ruleId, direction)
}

const handleAddRule = () => {
  if (!ensureWritable()) return
  emit('open-control-section', 'rules')
  emit('open-rule-builder')
}

const handleAddDevice = () => {
  if (!ensureWritable()) return
  emit('open-control-section', 'devices')
}

const handleAddSpec = () => {
  if (!ensureWritable()) return
  emit('open-control-section', 'specs')
}

const handleDeviceClick = (deviceId: string) => {
  emit('device-click', deviceId)
}

const handleDeleteSpec = (specId: string) => {
  if (!ensureWritable()) return
  emit('delete-spec', specId)
}

const togglePanel = () => {
  isCollapsed.value = !isCollapsed.value
}

const isFullTextClipped = (target: HTMLElement) => {
  const horizontalOverflow = target.scrollWidth - target.clientWidth > 1
  const verticalOverflow = target.scrollHeight - target.clientHeight > 1
  return horizontalOverflow || verticalOverflow
}

const syncFullTextTitle = (event: PointerEvent | FocusEvent) => {
  if (typeof window === 'undefined') return
  const root = event.currentTarget as HTMLElement | null
  const target = (event.target as HTMLElement | null)?.closest<HTMLElement>('[data-full-text]')
  if (!root || !target || !root.contains(target)) return

  const text = target.dataset.fullText?.trim()
  if (!text || !isFullTextClipped(target)) {
    target.removeAttribute('title')
    return
  }

  target.setAttribute('title', text)
}
</script>

<template>
  <aside
    data-testid="system-inspector"
    class="absolute right-0 top-0 bottom-0 glass-panel board-side-panel z-40 flex flex-col overflow-hidden border-l"
    :class="isCollapsed ? 'is-collapsed' : 'is-expanded'"
    :style="{ width: isCollapsed ? COLLAPSED_PANEL_RAIL_CSS : panelWidth, '--board-panel-expanded-width': panelWidth }"
    @pointerover="syncFullTextTitle"
    @focusin="syncFullTextTitle"
  >
    <div
      class="board-panel-header border-b"
    >
      <div class="board-panel-heading">
        <HintTooltip :content="t(isCollapsed ? 'app.expandPanel' : 'app.collapsePanel', { panel: t('app.systemInspector') })">
          <span class="inline-flex shrink-0">
            <button
              type="button"
              @click="togglePanel"
              class="board-panel-toggle"
              data-testid="system-inspector-toggle"
              :aria-label="t(isCollapsed ? 'app.expandPanel' : 'app.collapsePanel', { panel: t('app.systemInspector') })"
              :aria-expanded="!isCollapsed"
            >
              <span class="material-symbols-outlined" aria-hidden="true">{{ isCollapsed ? 'dock_to_left' : 'dock_to_right' }}</span>
            </button>
          </span>
        </HintTooltip>
        <div v-if="!isCollapsed" class="board-panel-heading__identity">
          <div class="board-panel-icon" aria-hidden="true">
            <span class="material-symbols-outlined">fact_check</span>
          </div>
          <div class="min-w-0">
            <h2 class="board-panel-title" :data-full-text="t('app.systemInspector')">{{ t('app.systemInspector') }}</h2>
          </div>
        </div>
      </div>
    </div>

    <Transition name="board-panel-content" @before-leave="element => element.setAttribute('inert', '')">
    <div
      v-if="!isCollapsed"
      class="board-panel-tabs border-b"
    >
      <div
        class="board-segmented board-section-tabs"
        role="tablist"
        :aria-label="t('app.currentBoardContent')"
      >
        <button
          v-for="tab in inspectorTabs"
          :key="tab.id"
          :id="`inspector-tab-${tab.id}`"
          type="button"
          role="tab"
          :data-testid="`inspector-tab-${tab.id}`"
          :aria-selected="activeSection === tab.id"
          :aria-label="tab.id === 'environment' ? t('app.environmentVariables') : tab.label"
          :aria-controls="activeSection === tab.id ? `inspector-panel-${tab.id}` : undefined"
          :tabindex="activeSection === tab.id ? 0 : -1"
          @click="activeSection = tab.id"
          @keydown="handleInspectorTabKeydown($event, tab.id)"
          class="board-section-tab board-section-tab--stacked"
        >
          <span class="board-section-tab__label" :data-full-text="tab.label">{{ tab.label }}</span>
          <span class="board-section-tab__count">
            {{ emptyDataStatusKey && tab.count === 0 ? '—' : tab.count }}
          </span>
        </button>
      </div>
    </div>

    </Transition>
    <Transition name="board-panel-content" @before-leave="element => element.setAttribute('inert', '')">
    <div v-if="!isCollapsed" ref="panelBody" class="board-panel-body flex-1 iot-scroll-region p-3 space-y-3">
      <section v-if="activeSection === 'environment'" id="inspector-panel-environment" role="tabpanel"
        aria-labelledby="inspector-tab-environment" data-testid="inspector-section-environment">
        <section data-testid="environment-pool" aria-labelledby="environment-pool-title">
          <div class="board-inventory-toolbar">
            <h3 id="environment-pool-title" class="board-section-title">{{ t('app.environmentVariables') }}</h3>
            <span class="board-caption board-text-muted board-inventory-count">{{ emptyDataStatusKey && !sectionTotals.environment ? '—' : `${filteredSectionCount}/${sectionTotals.environment}` }}</span>
            <span class="board-inventory-help">
              <InfoTooltip :text="t('app.environmentPoolHint')" :label="t('app.showHelpFor', { topic: t('app.environmentVariables') })"
                placement="left" test-id="environment-pool-help" />
            </span>
          </div>
          <p v-if="environmentVariables.length" class="board-caption board-text-muted mb-2">{{ t('app.environmentInspectorHint') }}</p>
        <label class="board-inventory-search relative block">
          <span class="material-symbols-outlined pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-sm board-text-muted" aria-hidden="true">search</span>
          <input v-model="sectionSearch.environment" data-testid="inspector-search-environment"
            type="search" class="board-field board-field--clearable pl-8 pr-12"
            :placeholder="t('app.searchEnvironmentVariables')" :aria-label="t('app.searchEnvironmentVariables')" />
          <HintTooltip v-if="sectionSearch.environment" :content="t('app.clearSearch')">
            <button type="button" class="board-field-clear" :aria-label="t('app.clearSearch')" @click="clearSectionSearch('environment')">
              <span class="material-symbols-outlined text-sm" aria-hidden="true">close</span>
            </button>
          </HintTooltip>
        </label>
          <div class="board-inventory-list">
            <article v-for="variable in filteredEnvironmentVariables" :key="variable.name"
              :data-environment-name="variable.name" class="board-inventory-card">
              <button type="button" class="board-inventory-open board-environment-summary"
                :aria-expanded="isEnvironmentVariableExpanded(variable.name)"
                :aria-controls="isEnvironmentVariableExpanded(variable.name) ? `environment-details-${encodeURIComponent(variable.name)}` : undefined"
                @click="toggleEnvironmentVariable(variable.name)">
                <span class="board-environment-summary__identity">
                  <span class="board-section-title board-inventory-name" :data-full-text="variable.displayName">{{ variable.displayName }}</span>
                  <span class="board-caption board-text-muted" :data-full-text="variable.rangeLabel">{{ t('app.range') }}: {{ variable.rangeLabel }}</span>
                </span>
                <span class="board-environment-summary__value">
                  <span class="board-caption board-text-muted">{{ t('app.modelInitialValue') }}</span>
                  <span class="board-section-title" :data-full-text="variable.valueLabel">{{ variable.valueLabel }}</span>
                </span>
                <span class="material-symbols-outlined board-inventory-chevron" aria-hidden="true">{{ isEnvironmentVariableExpanded(variable.name) ? 'expand_less' : 'expand_more' }}</span>
              </button>
              <div class="board-environment-labels board-caption board-text-muted">
                <span>{{ variable.trustLabel }} · {{ variable.privacyLabel }}</span>
                <span>{{ t('app.environmentParticipants', { readers: variable.readers.length, writers: variable.writers.length }) }}</span>
              </div>
              <div v-if="isEnvironmentVariableExpanded(variable.name)" :id="`environment-details-${encodeURIComponent(variable.name)}`"
                class="board-environment-details">
                <label class="min-w-0 block">
                  <span class="board-form-label block">{{ t('app.modelInitialValue') }}</span>
                  <FieldSelect
                    v-if="variable.enumValues.length > 0"
                    :data-testid="`environment-value-${variable.name}`"
                    :value="environmentEdits[variable.name]?.value ?? variable.value"
                    :aria-label="`${variable.displayName} ${t('app.modelInitialValue')}`"
                    :aria-invalid="environmentValueFailures[variable.name] ? 'true' : undefined"
                    :aria-describedby="environmentValueFailures[variable.name] ? `environment-value-failure-${encodeURIComponent(variable.name)}` : undefined"
                    :disabled="props.readOnly || props.environmentSaving || !variable.editable"
                    :aria-busy="props.environmentSaving ? 'true' : undefined"
                    class="mt-1"
                    @change="updateEnvironmentVariable(variable.name, { value: eventValue($event) })"
                  >
                    <option v-for="option in variable.enumValues" :key="option" :value="option">
                      {{ formatEnvironmentValue(variable, option) }}
                    </option>
                  </FieldSelect>
                  <input
                    v-else
                    :data-testid="`environment-value-${variable.name}`"
                    :type="variable.lowerBound !== undefined || variable.upperBound !== undefined ? 'number' : 'text'"
                    :min="variable.lowerBound"
                    :max="variable.upperBound"
                    :value="environmentEdits[variable.name]?.value ?? variable.value"
                    :title="variable.valueTitle"
                    :aria-label="`${variable.displayName} ${t('app.modelInitialValue')}`"
                    :aria-invalid="environmentValueFailures[variable.name] ? 'true' : undefined"
                    :aria-describedby="environmentValueFailures[variable.name] ? `environment-value-failure-${encodeURIComponent(variable.name)}` : undefined"
                    :disabled="props.readOnly || props.environmentSaving || !variable.editable"
                    :aria-busy="props.environmentSaving ? 'true' : undefined"
                    class="board-field mt-1"
                    @input="editEnvironmentValue(variable.name, eventValue($event))"
                    @change="updateEnvironmentVariable(variable.name, { value: eventValue($event) })"
                  />
                  <div
                    v-if="environmentValueFailures[variable.name]"
                    :id="`environment-value-failure-${encodeURIComponent(variable.name)}`"
                    :data-testid="`environment-value-failure-${variable.name}`"
                    role="alert"
                    class="mt-1 text-sm board-text-danger"
                  >
                    <FailureDetails v-bind="environmentValueFailures[variable.name]" suggestion="" />
                  </div>
                  <p
                    v-if="variable.conflicts.length > 0"
                    :data-testid="`environment-conflict-${variable.name}`"
                    class="mt-1 text-xs leading-4 board-text-danger"
                  >
                    {{ t('app.environmentDefinitionConflict', { reasons: variable.conflicts.join('; ') }) }}
                  </p>
                  <p
                    v-else-if="!variable.editable"
                    :data-testid="`environment-not-editable-${variable.name}`"
                    class="board-caption mt-1 leading-4 board-text-muted"
                  >
                    {{ t('app.environmentValueNotEditable') }}
                  </p>
                </label>
                <div v-if="variable.sources.length" class="board-environment-sources">
                  <div v-for="group in [{ title: t('app.environmentReaders'), items: variable.readers }, { title: t('app.environmentWriters'), items: variable.writers }]"
                    :key="group.title" v-show="group.items.length">
                    <h4 class="board-form-label">{{ group.title }}</h4>
                    <div class="board-source-links">
                      <HintTooltip v-for="source in group.items" :key="`${source.deviceId}:${source.role}`" :content="getEnvironmentSourceTitle(source)">
                        <button type="button" class="board-source-link" @click="handleDeviceClick(source.deviceId)">
                          <span>{{ source.label }}</span>
                          <span class="material-symbols-outlined" aria-hidden="true">north_east</span>
                        </button>
                      </HintTooltip>
                    </div>
                  </div>
                </div>
                <details class="board-inventory-disclosure" :data-testid="`environment-evolution-${variable.name}`">
                  <summary class="board-caption board-text-muted">{{ t('app.modelEvolution') }}</summary>
                  <div class="board-caption board-text-muted board-inventory-disclosure__body">
                    <p><span v-if="variable.lowerBound != null && variable.upperBound != null">{{ t('app.naturalChangeRate') }}: </span>{{ variable.naturalChangeRateLabel }}</p>
                    <ul v-if="variable.evolutionEffects.length" class="space-y-1">
                      <li v-for="effect in variable.evolutionEffects" :key="`${effect.deviceId}:${effect.state}:${effect.value}`">
                        {{ effect.deviceLabel }} · {{ effect.stateLabel }}: {{ effect.value }}
                      </li>
                    </ul>
                    <p v-else>{{ t('app.environmentNoDeviceEffects') }}</p>
                    <p>{{ t('app.environmentEvolutionHint') }}</p>
                  </div>
                </details>
                <details class="board-inventory-disclosure">
                  <summary class="board-caption board-text-muted">{{ t('app.advancedTrustPrivacyOverrides') }}</summary>
                  <p class="board-caption mt-1 leading-4 board-text-muted">{{ t('app.environmentTrustOverrideHint') }}</p>
                  <div class="board-field-grid mt-1.5">
                  <label class="min-w-0">
                    <span class="board-form-label block">{{ t('app.trust') }}</span>
                    <FieldSelect
                      :data-testid="`environment-trust-${variable.name}`"
                      :value="variable.trust"
                      :aria-label="`${variable.displayName} ${t('app.trust')}`"
                      :disabled="props.readOnly || props.environmentSaving || !variable.editable"
                      :aria-busy="props.environmentSaving ? 'true' : undefined" class="mt-1"
                      @change="updateEnvironmentVariable(variable.name, { trust: eventValue($event) })"
                    >
                      <option value="trusted">{{ t('app.trusted') }}</option>
                      <option value="untrusted">{{ t('app.untrusted') }}</option>
                    </FieldSelect>
                  </label>
                  <label class="min-w-0">
                    <span class="board-form-label block">{{ t('app.privacy') }}</span>
                    <FieldSelect
                      :data-testid="`environment-privacy-${variable.name}`"
                      :value="variable.privacy"
                      :aria-label="`${variable.displayName} ${t('app.privacy')}`"
                      :disabled="props.readOnly || props.environmentSaving || !variable.editable"
                      :aria-busy="props.environmentSaving ? 'true' : undefined" class="mt-1"
                      @change="updateEnvironmentVariable(variable.name, { privacy: eventValue($event) })"
                    >
                      <option value="public">{{ t('app.public') }}</option>
                      <option value="private">{{ t('app.private') }}</option>
                    </FieldSelect>
                  </label>
                  </div>
                </details>
              </div>
            </article>
          </div>
        <div v-if="environmentVariables.length === 0" class="board-inventory-empty">
          <p>{{ t(emptyDataStatusKey || 'app.noEnvironmentVariables') }}</p>
        </div>
        <div v-else-if="filteredEnvironmentVariables.length === 0" class="board-inventory-empty">
          <p>{{ t('app.noMatchingEnvironmentVariables') }}</p>
          <p class="board-caption">{{ t('app.tryDifferentSearchTerm') }}</p>
        </div>
        </section>
      </section>

      <section v-if="activeSection === 'devices'" id="inspector-panel-devices" role="tabpanel"
        aria-labelledby="inspector-tab-devices" data-testid="inspector-section-devices">
        <div class="board-inventory-toolbar">
          <h3 class="board-section-title">{{ t('app.devicesTool') }}</h3>
          <span class="board-caption board-text-muted board-inventory-count">{{ emptyDataStatusKey && !sectionTotals.devices ? '—' : `${filteredSectionCount}/${sectionTotals.devices}` }}</span>
          <HintTooltip :content="mutationTitle(t('app.createDevice'))">
            <button type="button" data-testid="inspector-add-device"
              class="board-panel-icon-action" :aria-label="t('app.createDevice')"
              :disabled="props.readOnly" @click="handleAddDevice">
              <span class="material-symbols-outlined" aria-hidden="true">add</span>
            </button>
          </HintTooltip>
        </div>
        <label class="board-inventory-search relative block">
          <span class="material-symbols-outlined pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-sm board-text-muted" aria-hidden="true">search</span>
          <input v-model="sectionSearch.devices" data-testid="inspector-search-devices"
            type="search" class="board-field board-field--clearable pl-8 pr-12"
            :placeholder="t('app.searchDevice')" :aria-label="t('app.searchDevice')" />
          <HintTooltip v-if="sectionSearch.devices" :content="t('app.clearSearch')">
            <button type="button" class="board-field-clear" :aria-label="t('app.clearSearch')" @click="clearSectionSearch('devices')">
              <span class="material-symbols-outlined text-sm" aria-hidden="true">close</span>
            </button>
          </HintTooltip>
        </label>
        <div class="board-inventory-list">
          <article v-for="device in filteredDevices" :key="device.id" :data-device-id="device.id"
            class="board-inventory-card board-device-row" :class="{ 'is-focused': device.id === props.focusedDeviceId }">
            <button type="button" class="board-inventory-open board-device-identity" :aria-label="device.name" @click="handleDeviceClick(device.id)">
              <span class="board-inventory-symbol material-symbols-outlined" aria-hidden="true">devices_other</span>
              <span class="min-w-0">
                <span class="board-section-title board-inventory-name block" :data-full-text="device.name">{{ device.name }}</span>
                <span class="board-device-meta board-caption board-text-muted">
                  <span :data-full-text="device.type">{{ device.type }}</span>
                  <span v-if="device.state" class="board-inventory-chip" :data-full-text="device.state">{{ device.state }}</span>
                </span>
              </span>
            </button>
            <HintTooltip :content="mutationTitle(t('app.removeDevice'))">
              <button type="button" class="board-panel-icon-action board-panel-icon-action--danger" :disabled="props.readOnly"
                :aria-label="t('app.removeDevice')" @click.stop="handleDeleteDevice(device.id)">
                <span class="material-symbols-outlined" aria-hidden="true">delete</span>
              </button>
            </HintTooltip>
          </article>
        </div>
        <div v-if="displayDevices.length === 0" class="board-inventory-empty">
          <p>{{ t(emptyDataStatusKey || 'app.noDevicesOnCanvas') }}</p>
          <button type="button" class="board-action-inline-subtle" :disabled="props.readOnly" @click="handleAddDevice">{{ t('app.createDevice') }}</button>
        </div>
        <div v-else-if="filteredDevices.length === 0" class="board-inventory-empty">
          <p>{{ t('app.noMatchingDevices') }}</p>
          <p class="board-caption">{{ t('app.tryDifferentSearchTerm') }}</p>
        </div>
      </section>

      <section v-if="activeSection === 'rules'" id="inspector-panel-rules" role="tabpanel"
        aria-labelledby="inspector-tab-rules" data-testid="inspector-section-rules">
        <div class="board-inventory-toolbar">
          <h3 class="board-section-title">{{ t('app.rulesTool') }}</h3>
          <span class="board-caption board-text-muted board-inventory-count">{{ emptyDataStatusKey && !sectionTotals.rules ? '—' : `${filteredSectionCount}/${sectionTotals.rules}` }}</span>
          <HintTooltip :content="mutationTitle(t('app.createRule'))">
            <button type="button" data-testid="inspector-add-rule"
              class="board-panel-icon-action" :aria-label="t('app.createRule')"
              :disabled="props.readOnly" @click="handleAddRule">
              <span class="material-symbols-outlined" aria-hidden="true">add</span>
            </button>
          </HintTooltip>
        </div>
        <label class="board-inventory-search relative block">
          <span class="material-symbols-outlined pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-sm board-text-muted" aria-hidden="true">search</span>
          <input v-model="sectionSearch.rules" data-testid="inspector-search-rules"
            type="search" class="board-field board-field--clearable pl-8 pr-12"
            :placeholder="t('app.searchRules')" :aria-label="t('app.searchRules')" />
          <HintTooltip v-if="sectionSearch.rules" :content="t('app.clearSearch')">
            <button type="button" class="board-field-clear" :aria-label="t('app.clearSearch')" @click="clearSectionSearch('rules')">
              <span class="material-symbols-outlined text-sm" aria-hidden="true">close</span>
            </button>
          </HintTooltip>
        </label>
        <p v-if="displayRules.length > 1" data-testid="rule-execution-order-hint" class="board-caption board-text-muted mb-2">{{ t('app.ruleExecutionOrderHint') }}</p>
        <p v-if="sectionSearch.rules && displayRules.length > 1" id="inspector-rule-order-blocked" class="board-caption board-text-muted mb-2">{{ t('app.ruleOrderSearchDisabled') }}</p>
        <div class="board-inventory-list">
          <article v-for="rule in filteredRules" :key="rule.originalId" :data-rule-id="rule.originalId" tabindex="-1"
            class="board-inventory-card" :class="{ 'is-focused': rule.originalId === props.focusedRuleId }">
            <div class="board-inventory-heading">
              <span class="board-inventory-order board-caption board-text-muted" :title="t('app.ruleExecutionOrder')">#{{ rule.executionOrder }}</span>
              <h4 class="board-section-title board-inventory-name">{{ rule.name }}</h4>
            </div>
            <dl class="board-rule-flow board-copy">
              <div class="board-rule-step">
                <dt class="board-form-label">{{ t(rule.conditions.length > 1 ? 'app.ruleAllConditions' : 'app.sourceDevices') }}</dt>
                <dd>
                  <ul class="board-rule-conditions" data-testid="inspector-rule-conditions">
                    <li v-for="(condition, index) in rule.conditions" :key="index">{{ condition }}</li>
                  </ul>
                </dd>
              </div>
              <div class="board-rule-step board-rule-step--command">
                <dt class="board-form-label">{{ t('app.thenAction') }}</dt>
                <dd data-testid="inspector-rule-command" class="board-rule-command">
                  <p class="board-rule-target">{{ rule.target }}</p>
                  <p class="board-rule-command-action">{{ rule.action }}</p>
                </dd>
              </div>
              <div v-if="rule.contentSource" class="board-rule-step">
                <dt class="board-form-label board-text-muted">{{ t('app.contentSourceDevice') }}</dt>
                <dd data-testid="inspector-rule-content-source">{{ rule.contentSource }}</dd>
              </div>
            </dl>
            <div class="board-inventory-footer board-rule-actions">
              <HintTooltip :content="mutationTitle(t('app.editRule'))">
                <button type="button" class="board-panel-icon-action" :data-testid="`edit-rule-${rule.originalId}`"
                  :disabled="props.readOnly" :aria-label="t('app.editRule')"
                  @click.stop="ensureWritable() && rule.originalId && emit('edit-rule', rule.originalId)">
                  <span class="material-symbols-outlined" aria-hidden="true">edit</span>
                </button>
              </HintTooltip>
              <div class="board-rule-order-actions" role="group" :aria-label="t('app.ruleExecutionOrder')">
                <HintTooltip :content="mutationTitle(sectionSearch.rules ? t('app.ruleOrderSearchDisabled') : t('app.moveRuleEarlier'))">
                  <button type="button" class="board-panel-icon-action" :aria-label="t('app.moveRuleEarlier')"
                    :disabled="props.readOnly || props.rulesReordering || !!sectionSearch.rules || rule.isFirst"
                    :aria-describedby="sectionSearch.rules ? 'inspector-rule-order-blocked' : undefined"
                    @click.stop="rule.originalId && handleMoveRule(rule.originalId, 'up')">
                    <span class="material-symbols-outlined" aria-hidden="true">arrow_upward</span>
                  </button>
                </HintTooltip>
                <HintTooltip :content="mutationTitle(sectionSearch.rules ? t('app.ruleOrderSearchDisabled') : t('app.moveRuleLater'))">
                  <button type="button" class="board-panel-icon-action" :aria-label="t('app.moveRuleLater')"
                    :disabled="props.readOnly || props.rulesReordering || !!sectionSearch.rules || rule.isLast"
                    :aria-describedby="sectionSearch.rules ? 'inspector-rule-order-blocked' : undefined"
                    @click.stop="rule.originalId && handleMoveRule(rule.originalId, 'down')">
                    <span class="material-symbols-outlined" aria-hidden="true">arrow_downward</span>
                  </button>
                </HintTooltip>
              </div>
              <HintTooltip :content="mutationTitle(t('app.deleteRule'))">
                <button type="button" class="board-panel-icon-action board-panel-icon-action--danger" :disabled="props.readOnly"
                  :aria-label="t('app.deleteRule')" @click.stop="rule.originalId && handleDeleteRule(rule.originalId)">
                  <span class="material-symbols-outlined" aria-hidden="true">delete</span>
                </button>
              </HintTooltip>
            </div>
          </article>
        </div>
        <div v-if="displayRules.length === 0" class="board-inventory-empty">
          <p>{{ t(emptyDataStatusKey || 'app.noRulesActive') }}</p>
          <button type="button" class="board-action-inline-subtle" :disabled="props.readOnly" @click="handleAddRule">{{ t('app.createRule') }}</button>
        </div>
        <div v-else-if="filteredRules.length === 0" class="board-inventory-empty">
          <p>{{ t('app.noMatchingRules') }}</p>
          <p class="board-caption">{{ t('app.tryDifferentSearchTerm') }}</p>
        </div>
      </section>

      <section v-if="activeSection === 'specs'" id="inspector-panel-specs" role="tabpanel"
        aria-labelledby="inspector-tab-specs" data-testid="inspector-section-specs">
        <div class="board-inventory-toolbar">
          <h3 class="board-section-title">{{ t('app.specificationsTool') }}</h3>
          <span class="board-caption board-text-muted board-inventory-count">{{ emptyDataStatusKey && !sectionTotals.specs ? '—' : `${filteredSectionCount}/${sectionTotals.specs}` }}</span>
          <HintTooltip :content="mutationTitle(t('app.createSpecification'))">
            <button type="button" data-testid="inspector-add-spec"
              class="board-panel-icon-action" :aria-label="t('app.createSpecification')"
              :disabled="props.readOnly" @click="handleAddSpec">
              <span class="material-symbols-outlined" aria-hidden="true">add</span>
            </button>
          </HintTooltip>
        </div>
        <p class="board-caption board-text-muted mb-2">{{ t('household.requirementsMeaning') }}</p>
        <label class="board-inventory-search relative block">
          <span class="material-symbols-outlined pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-sm board-text-muted" aria-hidden="true">search</span>
          <input v-model="sectionSearch.specs" data-testid="inspector-search-specs"
            type="search" class="board-field board-field--clearable pl-8 pr-12"
            :placeholder="t('app.searchSpecifications')" :aria-label="t('app.searchSpecifications')" />
          <HintTooltip v-if="sectionSearch.specs" :content="t('app.clearSearch')">
            <button type="button" class="board-field-clear" :aria-label="t('app.clearSearch')" @click="clearSectionSearch('specs')">
              <span class="material-symbols-outlined text-sm" aria-hidden="true">close</span>
            </button>
          </HintTooltip>
        </label>
        <div class="board-inventory-list">
          <article v-for="spec in filteredSpecs" :key="spec.id" :data-spec-id="spec.id" tabindex="-1"
            class="board-inventory-card" :class="{ 'is-focused': spec.id === props.focusedSpecId }">
            <div class="board-inventory-heading">
              <span class="board-inventory-symbol material-symbols-outlined" aria-hidden="true">policy</span>
              <h4 class="board-section-title board-inventory-name">{{ spec.type }}</h4>
            </div>
            <dl class="board-spec-description board-copy">
              <dt class="board-form-label">{{ t('app.specificationDescription') }}</dt>
              <dd data-testid="inspector-spec-description">{{ spec.description }}</dd>
            </dl>
            <div v-if="spec.variableSourceLabels.length" class="mt-2 flex flex-wrap gap-1">
              <span v-for="entry in spec.variableSourceLabels" :key="entry.key" class="board-inventory-chip board-caption"
                :class="{ 'board-chip-danger': entry.unresolved }" data-testid="inspector-spec-variable-source">{{ entry.label }}</span>
            </div>
            <div class="board-inventory-footer">
              <details class="spec-formula-disclosure" data-testid="inspector-spec-formula-details">
                <summary class="board-caption board-text-muted">
                  <span class="material-symbols-outlined spec-formula-disclosure__chevron" aria-hidden="true">chevron_right</span>
                  <span>{{ t('app.formulaPreview') }}</span>
                  <span v-if="spec.formulaKind" class="board-inventory-chip font-mono" data-testid="inspector-spec-formula-kind">{{ spec.formulaKind }}</span>
                </summary>
                <p class="board-caption board-formula-preview font-mono board-text-muted" :data-full-text="spec.formula" data-testid="inspector-spec-formula">{{ spec.formula }}</p>
              </details>
              <HintTooltip :content="mutationTitle(t('app.deleteSpecification'))">
                <button type="button" class="board-panel-icon-action board-panel-icon-action--danger" :disabled="props.readOnly"
                  :aria-label="t('app.deleteSpecification')" @click="handleDeleteSpec(spec.id)">
                  <span class="material-symbols-outlined" aria-hidden="true">delete</span>
                </button>
              </HintTooltip>
              <HintTooltip :content="mutationTitle(t('app.editSpecification'))">
                <button type="button" class="board-panel-icon-action" :data-testid="`edit-spec-${spec.id}`"
                  :disabled="props.readOnly" :aria-label="t('app.editSpecification')"
                  @click.stop="ensureWritable() && emit('edit-spec', spec.id)">
                  <span class="material-symbols-outlined" aria-hidden="true">edit</span>
                </button>
              </HintTooltip>
            </div>
          </article>
        </div>
        <div v-if="displaySpecs.length === 0" class="board-inventory-empty">
          <p>{{ t(emptyDataStatusKey || 'app.noSpecificationsVerified') }}</p>
          <button type="button" class="board-action-inline-subtle" :disabled="props.readOnly" @click="handleAddSpec">{{ t('app.createSpecification') }}</button>
        </div>
        <div v-else-if="filteredSpecs.length === 0" class="board-inventory-empty">
          <p>{{ t('app.noMatchingSpecifications') }}</p>
          <p class="board-caption">{{ t('app.tryDifferentSearchTerm') }}</p>
        </div>
      </section>
    </div>
    </Transition>
    <Transition name="board-panel-content" @before-leave="element => element.setAttribute('inert', '')">
      <div v-if="!isCollapsed && $slots.overview" class="board-panel-footer iot-scroll-region" data-testid="inspector-overview-dock">
        <slot name="overview" />
      </div>
    </Transition>
  </aside>

</template>

<style scoped>

[data-full-text] {
  position: relative;
}

[data-full-text]:hover,
[data-full-text]:focus-visible {
  z-index: 60;
}

/* Material Symbols font */
.material-symbols-outlined {
  font-family: 'Material Symbols Outlined';
}
</style>
