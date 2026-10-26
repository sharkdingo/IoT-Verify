<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Specification } from '@/types/spec'
import type {
  FuzzingExplorationMode,
  FuzzPaperDomainPreview,
  FuzzWorkloadPreview,
  FuzzingTaskSummary
} from '@/types/fuzzing'
import { isValidFuzzPaperDomainFingerprint } from '@/types/fuzzing'
import {
  FUZZ_ITERATIONS_MAX,
  FUZZ_ITERATIONS_MIN,
  FUZZ_PATH_LENGTH_MAX,
  FUZZ_PATH_LENGTH_MIN,
  FUZZ_POPULATION_MAX,
  FUZZ_POPULATION_MIN,
  FUZZ_TARGET_SPEC_MAX,
  getKnownFuzzingSpecificationIssue,
  type KnownFuzzingSpecificationIssue,
  isKnownFuzzingSpecificationSupported
} from '@/utils/fuzzingConfig'
import { fuzzingLimitationKey } from '@/utils/fuzzingPresentation'
import { formatBuiltInModelToken } from '@/utils/modelTokenDisplay'
import { normalizeNuSmvDeviceName } from '@/utils/modelRequest'
import HintTooltip from '@/components/common/HintTooltip.vue'
import InfoTooltip from '@/components/common/InfoTooltip.vue'
import FailureDetails from '@/components/FailureDetails.vue'
import type { ApiErrorDetails } from '@/utils/userMessage'

export type FuzzingPanelForm = {
  explorationMode: FuzzingExplorationMode
  targetSelectionMode?: 'ALL' | 'EXPLICIT'
  targetSpecIds: string[]
  maxIterations: number
  pathLength: number
  populationSize: number
  seed: number | null
}

const props = withDefaults(defineProps<{
  form: FuzzingPanelForm
  specifications: Specification[]
  running: boolean
  progress: number
  status: string
  taskId: number | null
  cancelling: boolean
  error?: string | null
  failureDetails?: (ApiErrorDetails & { suggestion?: string }) | null
  configurationError?: string
  workload?: number
  workloadLimit?: number
  workloadReady?: boolean
  workloadLoading?: boolean
  workloadError?: string | null
  /** Full preview, so the estimate can show every factor including the board's complexity multiplier. */
  workloadPreview?: FuzzWorkloadPreview | null
  paperDomainPreview?: FuzzPaperDomainPreview | null
  paperDomainLoading?: boolean
  paperDomainError?: string | null
  bundledDeviceIds?: string[]
  bundledEnvironmentNames?: string[]
  notice?: string | null
  preflightBlocked?: boolean
  preflightMessage?: string | null
  frozenTask?: FuzzingTaskSummary | null
}>(), {
  workloadReady: true,
  bundledDeviceIds: () => [],
  bundledEnvironmentNames: () => []
})

const emit = defineEmits<{
  submit: []
  cancel: []
  close: []
  refreshPaperDomain: []
  refreshWorkload: []
}>()

const { t, te, locale } = useI18n()
const formatBundledModelToken = (value: unknown) => formatBuiltInModelToken(
  value,
  key => te(key) ? t(key) : key
)
const bundledDeviceIdSet = computed(() => new Set(
  props.bundledDeviceIds.map(normalizeNuSmvDeviceName)
))
const bundledEnvironmentNameSet = computed(() => new Set(props.bundledEnvironmentNames))
const isBundledDevice = (targetId: string) => bundledDeviceIdSet.value.has(
  normalizeNuSmvDeviceName(targetId)
)
const formatDeviceStateProperty = (targetId: string, property: unknown) =>
  isBundledDevice(targetId)
    ? formatBundledModelToken(property)
    : String(property ?? '')
const formatDeviceVariableProperty = (targetId: string, property: unknown) =>
  isBundledDevice(targetId) ? formatBundledModelToken(property) : String(property ?? '')
const formatDeviceValue = (targetId: string, value: unknown) =>
  isBundledDevice(targetId) ? formatBundledModelToken(value) : String(value ?? '')
const formatEnvironmentToken = (name: string, value: unknown) =>
  bundledEnvironmentNameSet.value.has(name)
    ? formatBundledModelToken(value)
    : String(value ?? '')

const formatNumber = (value: number) => value.toLocaleString(
  locale.value.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en-US'
)

const panelRef = ref<HTMLElement | null>(null)
const previouslyFocused = typeof document !== 'undefined' && document.activeElement instanceof HTMLElement
  ? document.activeElement
  : null

const closePanel = () => emit('close')

const updateSeed = (event: Event) => {
  const input = event.target as HTMLInputElement
  props.form.seed = input.value === '' ? null : input.valueAsNumber
}

onMounted(() => {
  void nextTick(() => panelRef.value?.focus({ preventScroll: true }))
})

onBeforeUnmount(() => {
  const fallback = document.querySelector<HTMLElement>('[data-testid="open-fuzzing-panel"]')
  const target = previouslyFocused?.isConnected ? previouslyFocused : fallback
  target?.focus()
})

const eligibleSpecifications = computed(() =>
  props.specifications.filter(isKnownFuzzingSpecificationSupported))
const eligibleIds = computed(() => new Set(eligibleSpecifications.value.map(spec => spec.id)))
const normalizedSelectedIds = computed(() =>
  props.form.targetSpecIds.filter(id => eligibleIds.value.has(id)))
const invalidSelectedIds = computed(() =>
  props.form.targetSpecIds.filter(id => !eligibleIds.value.has(id)))
const targetSelectionMode = computed(() => props.form.targetSelectionMode
  ?? (props.form.targetSpecIds.length > 0 ? 'EXPLICIT' : 'ALL'))
const preflightBlocked = computed(() => props.preflightBlocked === true)

const implicitAll = computed(() =>
  eligibleSpecifications.value.length > 0 &&
  eligibleSpecifications.value.length <= FUZZ_TARGET_SPEC_MAX &&
  targetSelectionMode.value === 'ALL'
)

const selectedAll = computed(() =>
  implicitAll.value || (
    eligibleSpecifications.value.length > 0 &&
    normalizedSelectedIds.value.length === eligibleSpecifications.value.length &&
    eligibleSpecifications.value.every(spec => normalizedSelectedIds.value.includes(spec.id))
  )
)

const selectedEligibleCount = computed(() =>
  implicitAll.value ? eligibleSpecifications.value.length : normalizedSelectedIds.value.length)

const explorationModeDescription = computed(() => t(
  props.form.explorationMode === 'PAPER_COMPATIBLE'
    ? 'app.fuzzModePaperDescription'
    : 'app.fuzzModeBoardDescription'
))

const paperDomainReady = computed(() =>
  props.form.explorationMode !== 'PAPER_COMPATIBLE' || (
    !props.paperDomainLoading &&
    !props.paperDomainError &&
    props.paperDomainPreview?.pathLength === props.form.pathLength &&
    isValidFuzzPaperDomainFingerprint(props.paperDomainPreview?.modelFingerprint)
  ))

const selectExplorationMode = (mode: FuzzingExplorationMode) => {
  if (!props.running) props.form.explorationMode = mode
}

const toggleAll = () => {
  if (props.running || preflightBlocked.value || implicitAll.value) return
  props.form.targetSelectionMode = 'ALL'
  props.form.targetSpecIds = []
}

const toggleSpec = (id: string, checked: boolean) => {
  if (preflightBlocked.value || !eligibleIds.value.has(id)) return
  const selected = new Set(normalizedSelectedIds.value)
  if (implicitAll.value) {
    if (checked) return
    eligibleSpecifications.value.forEach(spec => selected.add(spec.id))
    selected.delete(id)
  } else if (checked) {
    selected.add(id)
  } else {
    if (selected.size <= 1) return
    selected.delete(id)
  }
  const next = eligibleSpecifications.value.filter(spec => selected.has(spec.id)).map(spec => spec.id)
  if (next.length === eligibleSpecifications.value.length) {
    props.form.targetSelectionMode = 'ALL'
    props.form.targetSpecIds = []
  } else {
    props.form.targetSelectionMode = 'EXPLICIT'
    props.form.targetSpecIds = [...next, ...invalidSelectedIds.value]
  }
}

const resolveInvalidTargets = () => {
  if (props.running || invalidSelectedIds.value.length === 0) return
  if (normalizedSelectedIds.value.length > 0) {
    props.form.targetSelectionMode = 'EXPLICIT'
    props.form.targetSpecIds = [...normalizedSelectedIds.value]
  } else {
    props.form.targetSelectionMode = 'ALL'
    props.form.targetSpecIds = []
  }
}

const isSpecChecked = (id: string) =>
  eligibleIds.value.has(id) && (implicitAll.value || normalizedSelectedIds.value.includes(id))

const isOnlySelectedSpec = (id: string) =>
  (implicitAll.value && eligibleSpecifications.value.length === 1 && eligibleSpecifications.value[0]?.id === id) ||
  (!implicitAll.value && normalizedSelectedIds.value.length === 1 && normalizedSelectedIds.value[0] === id)

const isSpecEligible = (spec: Specification) => eligibleIds.value.has(spec.id)

const configurationErrorMessage = computed(() => {
  const targetError = invalidSelectedIds.value.length > 0
    ? t('app.fuzzTargetSelectionChanged', { count: invalidSelectedIds.value.length })
    : ''
  return props.configurationError === targetError ? '' : props.configurationError
})

// A lookup, not a ternary: a two-way branch silently reported every issue that was not trust/privacy as an
// unsupported TEMPLATE, so a third reason would have been mislabelled rather than left unhandled.
const SPEC_INELIGIBILITY_MESSAGE_KEYS: Record<KnownFuzzingSpecificationIssue, string> = {
  UNSUPPORTED_TEMPLATE: 'app.fuzzEligibilityUnsupportedTemplate',
  TRUST_PRIVACY_UNSUPPORTED: 'app.fuzzEligibilityTrustPrivacyUnsupported',
  REPORTED_READING_UNSUPPORTED: 'app.fuzzEligibilityReportedReadingUnsupported'
}

const specIneligibilityMessageKey = (spec: Specification) => {
  const issue = getKnownFuzzingSpecificationIssue(spec)
  return issue ? SPEC_INELIGIBILITY_MESSAGE_KEYS[issue] : 'app.fuzzEligibilityUnsupportedTemplate'
}

const onSpecChange = (id: string, event: Event) => {
  toggleSpec(id, (event.target as HTMLInputElement).checked)
}

const formatDomainValues = (
  values: string[],
  stripRatePrefix = false,
  formatter: (value: unknown) => string = value => String(value ?? '')
) => values
  .map(value => stripRatePrefix && value.startsWith('rate:') ? value.slice(5) : value)
  .map(formatter)
  .join(', ')

const formatDomain = (
  values: string[],
  lower?: number | null,
  upper?: number | null,
  stripRatePrefix = false,
  formatter?: (value: unknown) => string
) => Number.isSafeInteger(lower) && Number.isSafeInteger(upper)
  ? `${lower}..${upper}`
  : formatDomainValues(values, stripRatePrefix, formatter)

const frozenModeLabel = computed(() => t(
  props.frozenTask?.explorationMode === 'PAPER_COMPATIBLE'
    ? 'app.fuzzModePaper'
    : 'app.fuzzModeBoard'
))

const frozenTargetScope = computed(() => {
  const task = props.frozenTask
  if (!task) return ''
  return task.targetSpecIds.length > 0
    ? t('app.fuzzReproductionSelectedTargets', { count: task.targetSpecIds.length })
    : t('app.fuzzReproductionAllTargets', {
      count: task.modelSnapshot.specificationCount
    })
})
</script>

<template>
  <section
    ref="panelRef"
    data-testid="fuzzing-panel"
    class="board-floating-panel fuzzing-panel board-surface-panel fixed top-20 z-30 flex w-[30rem] max-w-[calc(100vw-2rem)] flex-col overflow-hidden rounded-lg border shadow-2xl"
    role="region"
    aria-labelledby="fuzzing-panel-title"
    tabindex="-1"
    @keydown.esc.stop.prevent="closePanel"
  >
    <header class="board-tool-header">
      <div class="flex min-w-0 items-center gap-3">
        <div class="board-section-icon board-section-icon--lg">
          <span class="material-symbols-outlined" aria-hidden="true">radar</span>
        </div>
        <div class="flex min-w-0 items-center gap-3">
          <h3 id="fuzzing-panel-title" class="board-panel-title">{{ t('app.fuzzSearch') }}</h3>
          <InfoTooltip :text="t('app.fuzzSearchSubtitle')"
            :label="t('app.showHelpFor', { topic: t('app.fuzzSearch') })" test-id="fuzz-intro-help" />
        </div>
      </div>
      <HintTooltip :content="t('app.close')">
        <button
          type="button"
          data-testid="close-fuzzing-panel"
          class="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-md board-text-muted hover:board-card--muted"
          :aria-label="t('app.close')"
          @click="closePanel"
        >
          <span class="material-symbols-outlined" aria-hidden="true">close</span>
        </button>
      </HintTooltip>
    </header>

    <div class="iot-scroll-region min-h-0 flex-1 space-y-3 p-4">
      <section
        v-if="frozenTask"
        data-testid="fuzz-frozen-task-summary"
        class="rounded-lg border board-border-subtle board-card--muted px-3 py-3 text-xs leading-4 board-text-strong"
        aria-labelledby="fuzz-frozen-task-title"
      >
        <h4 id="fuzz-frozen-task-title" class="board-section-title">
          {{ t('app.fuzzWatchingFrozenTask', { id: frozenTask.id }) }}
        </h4>
        <p class="mt-1 board-text-strong">{{ t('app.fuzzWatchingFrozenTaskDetail') }}</p>
        <dl class="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 sm:grid-cols-3">
          <div>
            <dt class="board-text-muted">{{ t('app.fuzzExplorationMode') }}</dt>
            <dd class="mt-0.5 font-semibold board-text-strong">{{ frozenModeLabel }}</dd>
          </div>
          <div>
            <dt class="board-text-muted">{{ t('app.fuzzMaxIterations') }}</dt>
            <dd class="mt-0.5 font-semibold board-text-strong">{{ frozenTask.maxIterations }}</dd>
          </div>
          <div>
            <dt class="board-text-muted">{{ t('app.fuzzPathLength') }}</dt>
            <dd class="mt-0.5 font-semibold board-text-strong">{{ frozenTask.pathLength }}</dd>
          </div>
          <div>
            <dt class="board-text-muted">{{ t('app.fuzzPopulationSize') }}</dt>
            <dd class="mt-0.5 font-semibold board-text-strong">{{ frozenTask.populationSize }}</dd>
          </div>
          <div>
            <dt class="board-text-muted">{{ t('app.fuzzSeed') }}</dt>
            <dd class="mt-0.5 break-all font-mono font-semibold board-text-strong">
              {{ frozenTask.seed ?? t('app.fuzzSeedAuto') }}
            </dd>
          </div>
          <div>
            <dt class="board-text-muted">{{ t('app.fuzzTargetSpecifications') }}</dt>
            <dd class="mt-0.5 font-semibold board-text-strong">{{ frozenTargetScope }}</dd>
          </div>
        </dl>
        <p class="mt-2 text-xs leading-4 board-text-muted">
          {{ t('app.fuzzFrozenTaskSnapshotSummary', {
            devices: frozenTask.modelSnapshot.deviceCount,
            rules: frozenTask.modelSnapshot.ruleCount,
            specs: frozenTask.modelSnapshot.specificationCount,
            variables: frozenTask.modelSnapshot.environmentVariableCount,
            templates: frozenTask.modelSnapshot.deviceTemplateCount
          }) }}
        </p>
      </section>

      <fieldset v-if="!frozenTask" class="min-w-0" :disabled="running">
        <legend class="board-form-label">
          {{ t('app.fuzzExplorationMode') }}
        </legend>
        <div
          class="mt-1 grid grid-cols-2 gap-1 rounded-lg board-bg-control p-1"
          role="radiogroup"
          :aria-label="t('app.fuzzExplorationMode')"
          aria-describedby="fuzz-exploration-mode-help"
        >
          <label
            class="flex min-h-11 min-w-0 cursor-pointer items-center justify-center rounded-md px-2 py-1.5 text-center text-sm font-semibold leading-5 transition-colors focus-within:ring-2 focus-within:ring-[color:var(--accent)] focus-within:ring-offset-1"
            :class="form.explorationMode === 'BOARD_SNAPSHOT'
              ? 'board-bg-elevated board-text-muted shadow-sm'
              : 'board-text-muted hover:board-text-strong'"
          >
            <input
              v-model="form.explorationMode"
              data-testid="fuzz-mode-board"
              class="sr-only"
              type="radio"
              name="fuzz-exploration-mode"
              value="BOARD_SNAPSHOT"
              :disabled="running"
              :aria-label="t('app.fuzzModeBoard')"
              aria-describedby="fuzz-exploration-mode-help"
              @change="selectExplorationMode('BOARD_SNAPSHOT')"
            />
            <span class="min-w-0 whitespace-normal break-words">{{ t('app.fuzzModeBoard') }}</span>
          </label>
          <label
            class="flex min-h-11 min-w-0 cursor-pointer items-center justify-center rounded-md px-2 py-1.5 text-center text-sm font-semibold leading-5 transition-colors focus-within:ring-2 focus-within:ring-[color:var(--accent)] focus-within:ring-offset-1"
            :class="form.explorationMode === 'PAPER_COMPATIBLE'
              ? 'board-bg-elevated board-text-muted shadow-sm'
              : 'board-text-muted hover:board-text-strong'"
          >
            <input
              v-model="form.explorationMode"
              data-testid="fuzz-mode-paper"
              class="sr-only"
              type="radio"
              name="fuzz-exploration-mode"
              value="PAPER_COMPATIBLE"
              :disabled="running"
              :aria-label="t('app.fuzzModePaper')"
              aria-describedby="fuzz-exploration-mode-help"
              @change="selectExplorationMode('PAPER_COMPATIBLE')"
            />
            <span class="min-w-0 whitespace-normal break-words">{{ t('app.fuzzModePaper') }}</span>
          </label>
        </div>
        <p id="fuzz-exploration-mode-help" class="mt-1.5 break-words text-xs leading-4 board-text-muted">
          {{ explorationModeDescription }}
        </p>
      </fieldset>


      <div v-if="!frozenTask" class="rounded-lg border board-border-subtle board-bg-elevated p-3">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div class="flex min-w-0 items-center gap-3">
            <span class="board-form-label">{{ t('app.fuzzTargetSpecifications') }}</span>
            <InfoTooltip
              :text="t('app.fuzzTargetSpecificationsHint')"
              :label="t('app.showHelpFor', { topic: t('app.fuzzTargetSpecifications') })"
              test-id="fuzz-targets-help"
            />
          </div>
          <!--
            No `disabled:opacity-50` here. On already-muted text it lands at 2:1 (light) / 2.68:1 (dark):
            muted is the de-emphasised role and sits deliberately close to the contrast floor, so halving it
            goes under — and the disabled state is where the label most needs to be readable, since it is what
            explains why the action is unavailable. The cursor and the `disabled` attribute already carry
            "not available" without spending contrast to say it twice.
          -->
          <button
            v-if="eligibleSpecifications.length <= FUZZ_TARGET_SPEC_MAX"
            type="button"
            class="board-action-secondary"
            :disabled="running || eligibleSpecifications.length === 0 || implicitAll"
            @click="toggleAll"
          >
            {{ selectedAll ? t('app.allSpecificationsSelected') : t('app.selectAll') }}
          </button>
        </div>
        <p
          v-if="preflightBlocked"
          data-testid="fuzz-eligibility-blocked"
          class="mt-1 rounded-md board-surface-warning px-2 py-1.5 text-xs font-semibold leading-4 board-text-warning"
          role="alert"
        >
          {{ preflightMessage || t('app.fuzzEligibilityPreflightBlocked') }}
        </p>
        <p v-else class="mt-1 text-xs font-semibold board-text-muted" data-testid="fuzz-eligibility-summary">
          {{ t('app.fuzzEligibilitySummary', {
            eligible: eligibleSpecifications.length,
            total: specifications.length,
            selected: selectedEligibleCount
          }) }}
        </p>
        <div
          v-if="invalidSelectedIds.length > 0"
          data-testid="fuzz-invalid-target-selection"
          class="mt-2 rounded-md board-surface-warning px-2.5 py-2 text-xs leading-4 board-text-warning"
          role="alert"
        >
          <p>{{ t('app.fuzzTargetSelectionChanged', { count: invalidSelectedIds.length }) }}</p>
          <button
            type="button"
            class="board-action-secondary mt-1.5"
            :disabled="running"
            @click="resolveInvalidTargets"
          >
            {{ normalizedSelectedIds.length > 0
              ? t('app.fuzzRemoveUnavailableTargets')
              : t('app.fuzzSelectCurrentTargets') }}
          </button>
        </div>
        <div v-if="specifications.length === 0" class="mt-3 rounded-md board-surface-warning px-2.5 py-2 text-xs board-text-warning">
          {{ t('app.noSpecsToFuzz') }}
        </div>
        <div v-else-if="eligibleSpecifications.length === 0" class="mt-3 rounded-md board-surface-warning px-2.5 py-2 text-xs board-text-warning" role="alert">
          {{ t('app.noEligibleSpecsToFuzz') }}
        </div>
        <div v-if="specifications.length > 0" class="mt-3 space-y-1.5">
          <label
            v-for="spec in specifications"
            :key="spec.id"
            class="board-form-label flex min-h-11 items-start gap-2 rounded-md border px-2 py-1.5"
            :class="isSpecEligible(spec)
              ? 'cursor-pointer board-border-subtle hover:board-border-subtle hover:board-card--muted'
              : 'cursor-not-allowed board-border-subtle board-bg-muted board-text-muted'"
          >
            <input
              type="checkbox"
              class="mt-0.5 accent-[color:var(--accent)]"
              :checked="isSpecChecked(spec.id)"
              :disabled="running || preflightBlocked || !isSpecEligible(spec) || isOnlySelectedSpec(spec.id)"
              :title="isOnlySelectedSpec(spec.id) ? t('app.fuzzAtLeastOneTarget') : undefined"
              @change="onSpecChange(spec.id, $event)"
            />
            <span class="min-w-0 flex-1 leading-4" :class="isSpecEligible(spec) ? 'board-text-strong' : 'board-text-muted'">
              <span class="block">{{ spec.name }}</span>
              <span v-if="!isSpecEligible(spec)" class="mt-0.5 block text-xs leading-4 board-text-muted">
                {{ t(specIneligibilityMessageKey(spec)) }}
              </span>
            </span>
            <span
              v-if="!isSpecEligible(spec)"
              class="shrink-0 rounded board-bg-control px-1.5 py-0.5 text-xs font-semibold board-text-muted"
            >
              {{ t('app.fuzzFormalOnly') }}
            </span>
          </label>
        </div>
      </div>

      <div v-if="!frozenTask" class="grid grid-cols-1 gap-2 min-[480px]:grid-cols-2">
        <label class="board-form-label board-card rounded-lg border board-border-subtle p-2">
          {{ t('app.fuzzMaxIterations') }}
          <input
            v-model.number="form.maxIterations"
            data-testid="fuzz-max-iterations"
            type="number"
            min="1"
            :max="FUZZ_ITERATIONS_MAX"
            step="1"
            :disabled="running"
            class="board-field mt-1"
          />
          <span class="mt-1 block text-xs font-normal board-text-muted">{{ FUZZ_ITERATIONS_MIN }}-{{ FUZZ_ITERATIONS_MAX }}</span>
        </label>
        <label class="board-form-label board-card rounded-lg border board-border-subtle p-2">
          {{ t('app.fuzzPathLength') }}
          <input
            v-model.number="form.pathLength"
            data-testid="fuzz-path-length"
            type="number"
            min="1"
            :max="FUZZ_PATH_LENGTH_MAX"
            step="1"
            :disabled="running"
            class="board-field mt-1"
          />
          <span class="mt-1 block text-xs font-normal board-text-muted">{{ FUZZ_PATH_LENGTH_MIN }}-{{ FUZZ_PATH_LENGTH_MAX }}</span>
        </label>
      </div>

      <section
        v-if="!frozenTask && form.explorationMode === 'PAPER_COMPATIBLE' && eligibleSpecifications.length > 0"
        data-testid="paper-domain-preview"
        class="rounded-lg board-note px-3 py-2.5"
        aria-labelledby="paper-domain-preview-title"
      >
        <div class="flex items-start justify-between gap-3">
          <div class="min-w-0">
            <h4 id="paper-domain-preview-title" class="board-section-title">{{ t('app.fuzzPaperDomainTitle') }}</h4>
          </div>
          <HintTooltip :content="t('app.refresh')">
            <button
              type="button"
              class="board-action-secondary h-11 w-11 shrink-0 px-0"
              :disabled="running || paperDomainLoading"
              :aria-label="t('app.refresh')"
              @click="emit('refreshPaperDomain')"
            >
              <span class="material-symbols-outlined text-base" :class="paperDomainLoading ? 'animate-spin' : ''" aria-hidden="true">refresh</span>
            </button>
          </HintTooltip>
        </div>

        <div v-if="paperDomainLoading" class="mt-2 flex items-center gap-2 text-xs board-text-muted" role="status">
          <span class="material-symbols-outlined animate-spin text-sm" aria-hidden="true">sync</span>{{ t('app.fuzzPaperDomainLoading') }}
        </div>
        <div v-else-if="paperDomainError" class="board-surface-danger mt-2 flex items-start justify-between gap-2 rounded-md px-2.5 py-2 text-xs leading-4 board-text-danger" role="alert">
          <span>{{ paperDomainError }}</span>
          <button
            type="button"
            data-testid="refresh-paper-domain"
            class="board-action-secondary shrink-0"
            :disabled="running || paperDomainLoading"
            @click="emit('refreshPaperDomain')"
          >
            {{ t('app.retry') }}
          </button>
        </div>
        <template v-else-if="paperDomainPreview">
          <details data-testid="paper-domain-choices" class="mt-2 border-t board-border-subtle pt-2">
            <summary class="flex min-h-11 cursor-pointer items-center text-xs font-semibold board-text-muted">{{ t('app.fuzzPaperInspectDomains') }}</summary>
            <p class="mt-2 text-xs font-semibold leading-4 board-text-muted">
              {{ t('app.fuzzPaperDomainSummary', {
                devices: paperDomainPreview.deviceDomains.length,
                locals: paperDomainPreview.localVariableDomains.length,
                environments: paperDomainPreview.environmentDomains.length,
                length: paperDomainPreview.pathLength
              }) }}
            </p>
            <div class="mt-2 space-y-2 text-xs leading-4 board-text-muted">
              <div v-if="paperDomainPreview.deviceDomains.length">
                <p class="font-semibold">{{ t('app.fuzzPaperDeviceStateDomains') }}</p>
                <ul class="mt-1 space-y-1">
                  <li v-for="domain in paperDomainPreview.deviceDomains" :key="`${domain.targetId}-${domain.property}`" class="break-words">
                    <span class="font-semibold">{{ domain.label }}.{{ formatDeviceStateProperty(domain.targetId, domain.property) }}</span>:
                    <span class="font-mono">{{ formatDomain(
                      domain.legalValues, domain.lowerBound, domain.upperBound, false,
                      value => formatDeviceValue(domain.targetId, value)
                    ) }}</span>
                  </li>
                </ul>
              </div>
              <div
                v-if="paperDomainPreview.localVariableDomains.length"
                data-testid="paper-local-variable-domains"
              >
                <p class="font-semibold">{{ t('app.fuzzPaperLocalVariableDomains') }}</p>
                <ul class="mt-1 space-y-1">
                  <li v-for="domain in paperDomainPreview.localVariableDomains" :key="`${domain.targetId}-${domain.property}`" class="break-words">
                    <span class="font-semibold">{{ domain.label }}.{{ formatDeviceVariableProperty(domain.targetId, domain.property) }}</span>:
                    <span class="font-mono">{{ formatDomain(
                      domain.legalValues, domain.lowerBound, domain.upperBound, false,
                      value => formatDeviceValue(domain.targetId, value)
                    ) }}</span>
                  </li>
                </ul>
              </div>
              <div v-if="paperDomainPreview.environmentDomains.length">
                <p class="font-semibold">{{ t('app.fuzzPaperEnvironmentDomains') }}</p>
                <ul class="mt-1 space-y-1.5">
                  <li v-for="domain in paperDomainPreview.environmentDomains" :key="domain.name" class="break-words">
                    <span class="font-semibold">{{ formatEnvironmentToken(domain.name, domain.name) }}</span>:
                    {{ t('app.fuzzPaperInitialValues') }} <span class="font-mono">{{ formatDomain(
                      domain.initialValues, domain.initialLowerBound, domain.initialUpperBound, false,
                      value => formatEnvironmentToken(domain.name, value)
                    ) }}</span>;
                    {{ domain.eventValueKind === 'CHANGE_RATE' ? t('app.fuzzPaperRateEvents') : t('app.fuzzPaperDirectValueEvents') }}
                    <span class="font-mono">{{ formatDomain(
                      domain.eventValues,
                      domain.eventRateLowerBound,
                      domain.eventRateUpperBound,
                      domain.eventValueKind === 'CHANGE_RATE',
                      value => formatEnvironmentToken(domain.name, value)
                    ) }}</span>
                  </li>
                </ul>
              </div>
            </div>
          </details>
          <details data-testid="paper-domain-boundaries" class="mt-2 border-t board-border-subtle pt-2">
            <summary class="flex min-h-11 cursor-pointer items-center text-xs font-semibold board-text-muted">{{ t('app.fuzzPaperCoverageAndBoundaries') }}</summary>
            <p class="mt-1 text-xs leading-4 board-text-muted">{{ t('app.fuzzPaperUnimplementedScope') }}</p>
            <ul class="mt-1 list-disc space-y-1 pl-4 text-xs leading-4 board-text-muted">
              <li v-for="code in paperDomainPreview.paperSemanticsCodes" :key="code">{{ t(fuzzingLimitationKey(code)) }}</li>
            </ul>
          </details>

        </template>
      </section>


      <!-- Forced open on a configuration error as well as while running: the workload rejection names
           "candidate paths per iteration" as a remedy, and that field lives in here. Telling a blocked user
           to lower a control they cannot see is a dead end. -->
      <details
        v-if="!frozenTask"
        class="rounded-lg border board-border-subtle board-bg-muted px-3 py-2"
        :open="running || configurationErrorMessage ? true : undefined"
      >
        <summary class="flex min-h-11 cursor-pointer items-center justify-between text-xs font-semibold board-text-strong">
          <span class="inline-flex items-center gap-1.5"><span class="material-symbols-outlined text-sm board-text-progress" aria-hidden="true">tune</span>{{ t('app.fuzzAdvancedSettings') }}</span>
          <span class="material-symbols-outlined text-sm board-text-muted" aria-hidden="true">expand_more</span>
        </summary>
        <div class="mt-2 grid grid-cols-1 gap-3 min-[480px]:grid-cols-2">
          <label class="board-form-label">
            {{ t('app.fuzzPopulationSize') }}
              <input
              v-model.number="form.populationSize"
              data-testid="fuzz-population-size"
              type="number"
              min="1"
              :max="FUZZ_POPULATION_MAX"
              step="1"
              :disabled="running"
              class="board-field mt-1"
              />
              <span class="mt-1 block text-xs font-normal board-text-muted">{{ FUZZ_POPULATION_MIN }}-{{ FUZZ_POPULATION_MAX }} {{ t('app.fuzzPathsPerIteration') }}</span>
          </label>
          <div class="min-w-0">
            <div class="flex items-center gap-3">
              <label for="fuzz-seed" class="board-form-label">{{ t('app.fuzzSeed') }}</label>
              <InfoTooltip :text="t('app.fuzzSeedHint')"
                :label="t('app.showHelpFor', { topic: t('app.fuzzSeed') })" test-id="fuzz-seed-help" />
            </div>
            <input
              id="fuzz-seed"
              :value="form.seed ?? ''"
              @input="updateSeed"
              data-testid="fuzz-seed"
              type="number"
              min="0"
              max="9007199254740991"
              step="1"
              :placeholder="t('app.fuzzSeedAuto')"
              :disabled="running"
              class="board-field mt-1"
            />
          </div>
        </div>
        <p id="fuzz-budget-help" class="mt-2 text-xs leading-4 board-text-muted">{{ t('app.fuzzBudgetProductHint') }}</p>
        <p v-if="workloadPreview && !workloadLoading && !workloadError" data-testid="fuzz-model-complexity" class="mt-1 text-xs leading-4 board-text-muted">
          {{ t('app.fuzzModelComplexity', { complexity: formatNumber(Math.max(1, workloadPreview.modelComplexityUnits)) }) }}
        </p>
      </details>

      <div
        v-if="!frozenTask && configurationErrorMessage"
        id="fuzz-configuration-error"
        class="rounded-lg board-surface-warning px-3 py-2 text-xs leading-5 board-text-warning"
        role="alert"
      >
        {{ configurationErrorMessage }}
      </div>

      <div v-if="error" class="board-surface-danger rounded-lg px-3 py-2 text-xs leading-5 board-text-danger" role="alert">
        {{ error }}
        <FailureDetails v-if="failureDetails" v-bind="failureDetails" :message="error" :suggestion="failureDetails.suggestion || t('app.runFailureNextStep')" />
      </div>

      <div v-if="notice" class="rounded-lg board-surface-info px-3 py-2 text-xs leading-5 board-text-info" role="status">
        {{ notice }}
      </div>

      <div v-if="running" class="rounded-lg border board-border-subtle board-card--muted px-3 py-2" aria-live="polite" aria-atomic="true">
        <div class="flex items-start justify-between gap-2 text-xs font-semibold board-text-strong">
          <span class="min-w-0 break-words">{{ status }}</span>
          <span class="inline-flex shrink-0 items-center gap-1.5">
            {{ progress }}%
            <InfoTooltip :text="t('app.fuzzProgressNotCoverage')"
              :label="t('app.showHelpFor', { topic: t('app.fuzzBudgetProgress') })" test-id="fuzz-progress-help" />
          </span>
        </div>
        <div
          class="mt-1.5 h-2 overflow-hidden rounded-full board-card--muted"
          role="progressbar"
          :aria-label="t('app.fuzzBudgetProgress')"
          aria-valuemin="0"
          aria-valuemax="100"
          :aria-valuenow="progress"
        >
          <div class="board-progress-fill h-full rounded-full transition-all" :style="{ width: `${progress}%` }"></div>
        </div>
        <div class="mt-1 flex flex-wrap items-center justify-between gap-2 text-xs board-text-muted">
          <span>{{ taskId ? t('app.fuzzTaskId', { id: taskId }) : t('app.taskInitializing') }}</span>
          <button
            v-if="taskId"
            type="button"
            class="board-action-secondary"
            :disabled="cancelling"
            @click="emit('cancel')"
          >
            <span class="material-symbols-outlined text-sm" aria-hidden="true">cancel</span>{{ t('app.cancel') }}
          </button>
        </div>
      </div>
    </div>

    <footer v-if="!frozenTask" class="board-run-panel__footer">
      <div class="space-y-2">
        <p v-if="configurationErrorMessage" class="text-xs leading-4 board-text-warning">
          {{ t('app.fuzzConfigurationBlocked') }}
        </p>

        <div
          v-if="workloadLoading"
          class="flex items-center gap-2 text-xs leading-4 board-text-muted"
          role="status"
        >
          <span class="material-symbols-outlined animate-spin text-sm" aria-hidden="true">progress_activity</span>
          <span>{{ t('app.fuzzWorkloadLoading') }}</span>
        </div>

        <div
          v-else-if="workloadError"
          class="flex items-start justify-between gap-2 rounded-lg board-surface-warning px-3 py-2 text-xs leading-5 board-text-warning"
          role="alert"
        >
          <span>{{ workloadError }}</span>
          <button
            type="button"
            class="min-h-11 shrink-0 px-2 font-semibold board-text-warning underline underline-offset-2"
            data-testid="refresh-fuzzing-workload"
            @click="emit('refreshWorkload')"
          >
            {{ t('app.retry') }}
          </button>
        </div>

        <!-- A rejected estimate belongs to the configuration warning, not the normal workload summary. -->
        <p
          v-else-if="!configurationError && workloadPreview && Number.isFinite(workload)"
          class="text-xs leading-4 board-text-muted"
        >
          {{ t('app.fuzzWorkloadPreview', {
            workload: formatNumber(Number(workload)),
            limit: formatNumber(Number(workloadLimit || 0))
          }) }}
        </p>

        <button
          type="button"
          data-testid="run-fuzzing"
          class="board-panel-submit min-h-11 px-3"
          :aria-describedby="configurationErrorMessage ? 'fuzz-configuration-error' : undefined"
          :disabled="running || preflightBlocked || eligibleSpecifications.length === 0 || invalidSelectedIds.length > 0 || Boolean(configurationError) || workloadReady === false || !paperDomainReady"
          @click="emit('submit')"
        >
          <span class="material-symbols-outlined text-base" :class="running ? 'animate-spin' : ''" aria-hidden="true">{{ running ? 'sync' : 'radar' }}</span>
          {{ running ? t('app.fuzzRunning') : t('app.startFuzzSearch') }}
        </button>
      </div>
    </footer>
  </section>
</template>
