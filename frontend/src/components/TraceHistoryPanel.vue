<script setup lang="ts">
import { compareModelSnapshot } from '@/utils/modelComparison'
import { activeTaskProgressStage } from '@/utils/runResponse'
import type { BoardModelFingerprints, ModelRunSnapshot, ModelSemantics, RunBoardComparison } from '@/types/modelSemantics'
import { formatRunTimestamp } from '@/utils/runTimestamp'
import { computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type {
  ModelGenerationIssue,
  AvailableVerificationRunSummary,
  TraceSummary,
  VerificationRunSummary,
  VerificationTaskSummary
} from '@/types/verify'
import type {
  AvailableSimulationTraceSummary,
  SimulationTaskSummary,
  SimulationTraceSummary
} from '@/types/simulation'
import type {
  AvailableFuzzingRunSummary,
  FuzzingExplorationMode,
  FuzzingFindingSummary,
  FuzzingRunSummary,
  FuzzingTaskSummary
} from '@/types/fuzzing'
import { formatTraceSpec } from '@/utils/traceView'
import { formatFuzzingElapsed } from '@/utils/fuzzingPresentation'
import { generationIssueReasonKey } from '@/utils/generationIssue'
import RunInitiatorBadge from '@/components/RunInitiatorBadge.vue'
import HintTooltip from '@/components/common/HintTooltip.vue'
import InfoTooltip from '@/components/common/InfoTooltip.vue'
import FieldSelect from '@/components/common/FieldSelect.vue'
import FailureDetails from '@/components/FailureDetails.vue'
import { technicalErrorLines, type ApiErrorDetails } from '@/utils/userMessage'

type HistoryLayer = 'tasks' | 'results'
type ResultFilter = 'all' | 'verification' | 'fuzzing' | 'simulation'
type ResultSource = Exclude<ResultFilter, 'all'>
type TaskKind = 'verification' | 'fuzzing' | 'simulation'
type TaskStatus = 'PENDING' | 'RUNNING' | 'FAILED' | 'CANCELLED'
type TaskItem =
  | (VerificationTaskSummary & { kind: 'verification' })
  | (FuzzingTaskSummary & { kind: 'fuzzing' })
  | (SimulationTaskSummary & { kind: 'simulation' })

type ResultItem =
  | { kind: 'verification'; run: VerificationRunSummary }
  | { kind: 'fuzzing'; run: FuzzingRunSummary }
  | { kind: 'simulation'; run: SimulationTraceSummary }

const props = defineProps<{
  activeLayer: HistoryLayer
  resultFilter: ResultFilter
  verificationTasks: VerificationTaskSummary[]
  fuzzingTasks: FuzzingTaskSummary[]
  simulationTasks: SimulationTaskSummary[]
  verificationRuns: VerificationRunSummary[]
  fuzzingRuns: FuzzingRunSummary[]
  simulationRuns: SimulationTraceSummary[]
  loadingTasks: boolean
  taskError?: ApiErrorDetails | null
  loadingResults: boolean
  resultErrors?: Partial<Record<ResultSource, ApiErrorDetails | null>>
  hasMoreFuzzingRuns: boolean
  loadingMoreFuzzingRuns: boolean
  pendingTaskActionKeys?: ReadonlySet<string>
  pendingResultDeleteKeys?: ReadonlySet<string>
  actionLocked: boolean
  currentModelFingerprints?: BoardModelFingerprints | null
  modelComparisonLoading?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:activeLayer', value: HistoryLayer): void
  (e: 'update:resultFilter', value: ResultFilter): void
  (e: 'close'): void
  (e: 'refresh-tasks'): void
  (e: 'refresh-results'): void
  (e: 'load-more-fuzzing-runs'): void
  (e: 'watch-verification-task', id: number): void
  (e: 'watch-fuzzing-task', id: number): void
  (e: 'watch-simulation-task', id: number): void
  (e: 'cancel-verification-task', id: number): void
  (e: 'cancel-fuzzing-task', id: number): void
  (e: 'cancel-simulation-task', id: number): void
  (e: 'dismiss-verification-task', id: number): void
  (e: 'dismiss-fuzzing-task', id: number): void
  (e: 'dismiss-simulation-task', id: number): void
  /**
   * Open the panel that owns launching this kind of run, so a failed job is not a dead end.
   *
   * Deliberately not a one-click "retry": the board may have changed since the run, so the settings
   * must be re-validated against the current scene rather than silently re-submitted. The owning
   * panel already holds the previous configuration for the session and re-checks eligibility.
   */
  (e: 'reopen-task-settings', kind: TaskKind): void
  (e: 'open-verification-run', id: number): void
  (e: 'delete-verification-run', run: VerificationRunSummary): void
  (e: 'download-verification-run-smv', id: number): void
  (e: 'view-verification-trace', id: number): void
  (e: 'fix-verification-trace', trace: TraceSummary): void
  (e: 'view-simulation-run', id: number): void
  (e: 'delete-simulation-run', run: SimulationTraceSummary): void
  (e: 'download-simulation-trace-smv', id: number): void
  (e: 'open-fuzzing-run', id: number): void
  (e: 'delete-fuzzing-run', run: FuzzingRunSummary): void
  (e: 'view-fuzzing-finding', id: number, runId: number): void
  (e: 'verify-fuzzing-finding', finding: FuzzingFindingSummary): void
}>()

const panelRef = ref<HTMLElement | null>(null)
const bodyRef = ref<HTMLElement | null>(null)
let previousActiveElement: HTMLElement | null = null
let panelActive = false
let rememberedScrollTop = 0
let readingAnchor: { element: HTMLElement; edge: 'top' | 'bottom'; offset: number } | null = null

const rememberReadingAnchor = () => {
  const body = bodyRef.value
  if (!panelActive || !body) return
  const bounds = body.getBoundingClientRect()
  const visible = Array.from(body.querySelectorAll<HTMLElement>('.history-card :is(h4, h5, p, time, summary, button)'))
    .filter(element => {
      const rect = element.getBoundingClientRect()
      return rect.height > 0 && rect.bottom > bounds.top && rect.top < bounds.bottom
    })
  const atEnd = body.scrollTop > 0 && body.scrollHeight - body.scrollTop - body.clientHeight <= 1
  const edge = atEnd ? 'bottom' : 'top'
  const element = atEnd
    ? visible.reverse().find(element => element.getBoundingClientRect().bottom <= bounds.bottom) || visible[0]
    : visible.find(element => element.getBoundingClientRect().top >= bounds.top) || visible[0]
  readingAnchor = element ? { element, edge, offset: element.getBoundingClientRect()[edge] - bounds[edge] } : null
}

const restoreReadingAnchor = () => {
  const body = bodyRef.value
  if (!panelActive || !body || !readingAnchor || !body.contains(readingAnchor.element)) return
  // Reflow changes card heights, so the same pixel offset can point to a different run.
  body.scrollTop += readingAnchor.element.getBoundingClientRect()[readingAnchor.edge]
    - body.getBoundingClientRect()[readingAnchor.edge] - readingAnchor.offset
  rememberedScrollTop = body.scrollTop
}

const activatePanel = async () => {
  if (panelActive) return
  panelActive = true
  previousActiveElement = document.activeElement as HTMLElement | null
  await nextTick()
  if (!panelActive) return
  if (bodyRef.value) bodyRef.value.scrollTop = rememberedScrollTop
  restoreReadingAnchor()
  panelRef.value?.focus({ preventScroll: true })
}

const deactivatePanel = () => {
  panelActive = false
  if (previousActiveElement
    && previousActiveElement !== document.body
    && document.contains(previousActiveElement)) {
    previousActiveElement.focus({ preventScroll: true })
  }
  previousActiveElement = null
}

const rememberScroll = () => {
  if (panelActive && bodyRef.value) rememberedScrollTop = bodyRef.value.scrollTop
  rememberReadingAnchor()
}

watch(() => [props.activeLayer, props.resultFilter], () => {
  rememberedScrollTop = 0
  readingAnchor = null
  if (bodyRef.value) bodyRef.value.scrollTop = 0
}, { flush: 'post' })

onMounted(activatePanel)
onMounted(() => window.addEventListener('resize', restoreReadingAnchor))
onActivated(activatePanel)
onDeactivated(deactivatePanel)
onBeforeUnmount(deactivatePanel)
onBeforeUnmount(() => window.removeEventListener('resize', restoreReadingAnchor))

const { t, locale } = useI18n()

watch(locale, async () => {
  rememberReadingAnchor()
  await nextTick()
  restoreReadingAnchor()
})

const timestamp = (value?: string) => {
  if (!value) return 0
  const parsed = new Date(value).getTime()
  return Number.isNaN(parsed) ? 0 : parsed
}

/* One owner in `utils/runTimestamp.ts`. This copy returned a blank for a missing timestamp, which cannot be told
   apart from a rendering failure — the shared version says "unknown" instead. */
const formatDate = (value?: string) => formatRunTimestamp(value, locale.value, t)

const taskItems = computed<TaskItem[]>(() => [
  ...props.verificationTasks
    .filter(task => task.status !== 'COMPLETED')
    .map(task => ({ ...task, kind: 'verification' as const })),
  ...props.fuzzingTasks
    .filter(task => task.status !== 'COMPLETED')
    .map(task => ({ ...task, kind: 'fuzzing' as const })),
  ...props.simulationTasks
    .filter(task => task.status !== 'COMPLETED')
    .map(task => ({ ...task, kind: 'simulation' as const }))
].sort((a, b) => timestamp(b.createdAt) - timestamp(a.createdAt)))

const activeTasks = computed(() => taskItems.value.filter(task => isActiveStatus(task.status)))
const unresolvedTasks = computed(() => taskItems.value.filter(task => !isActiveStatus(task.status)))

const resultItems = computed<ResultItem[]>(() => {
  const all: ResultItem[] = [
    ...props.verificationRuns.map(run => ({ kind: 'verification' as const, run })),
    ...props.fuzzingRuns.map(run => ({ kind: 'fuzzing' as const, run })),
    ...props.simulationRuns.map(run => ({ kind: 'simulation' as const, run }))
  ]
  return all
    .filter(item => props.resultFilter === 'all' || item.kind === props.resultFilter)
    .sort((a, b) => {
      const left = a.kind === 'simulation' ? a.run.createdAt : (a.run.completedAt || a.run.createdAt)
      const right = b.kind === 'simulation' ? b.run.createdAt : (b.run.completedAt || b.run.createdAt)
      return timestamp(right) - timestamp(left)
    })
})

const tracesForRun = (run: VerificationRunSummary) => run.counterexamples || []
const isActiveStatus = (status?: string) => status === 'PENDING' || status === 'RUNNING'
const isResultDeletePending = (kind: ResultSource, id: number) =>
  props.pendingResultDeleteKeys?.has(`${kind}:${id}`) === true
const resultDeleteTestId = (kind: ResultSource, id: number) => {
  if (kind === 'simulation') return `delete-simulation-trace-${id}`
  return `delete-${kind === 'fuzzing' ? 'fuzzing' : 'verification'}-run-${id}`
}

/**
 * How much of an in-flight task's work is done. Only ever rendered for PENDING/RUNNING tasks, so a
 * missing value means "not reported yet" and 0 is the honest fallback — never 100, which would claim
 * completed work for a task that has not finished.
 */
const taskProgress = (task: TaskItem) => {
  const numeric = typeof task.progress === 'number' ? task.progress : 0
  return Number.isFinite(numeric) ? Math.min(100, Math.max(0, Math.round(numeric))) : 0
}

const formatStatus = (status?: string) => {
  switch (status as TaskStatus | undefined) {
    case 'PENDING': return t('app.taskStatusPending')
    case 'RUNNING': return t('app.taskStatusRunning')
    case 'FAILED': return t('app.taskStatusFailed')
    case 'CANCELLED': return t('app.taskStatusCancelled')
    default: return status || t('app.taskInitializing')
  }
}

const statusClass = (status?: string) => {
  if (status === 'FAILED') return 'board-surface-danger board-text-danger'
  return 'board-meta-chip'
}

const taskKindLabel = (kind: TaskKind) => {
  if (kind === 'verification') return t('app.verification')
  if (kind === 'fuzzing') return t('app.fuzzSearch')
  return t('app.simulation')
}

const emitWatchTask = (task: TaskItem) => {
  if (task.kind === 'verification') emit('watch-verification-task', task.id)
  else if (task.kind === 'fuzzing') emit('watch-fuzzing-task', task.id)
  else emit('watch-simulation-task', task.id)
}

const emitCancelTask = (task: TaskItem) => {
  if (task.kind === 'verification') emit('cancel-verification-task', task.id)
  else if (task.kind === 'fuzzing') emit('cancel-fuzzing-task', task.id)
  else emit('cancel-simulation-task', task.id)
}

const emitDismissTask = (task: TaskItem) => {
  if (task.kind === 'verification') emit('dismiss-verification-task', task.id)
  else if (task.kind === 'fuzzing') emit('dismiss-fuzzing-task', task.id)
  else emit('dismiss-simulation-task', task.id)
}

const taskActionPending = (action: 'cancel' | 'dismiss', task: TaskItem) =>
  props.pendingTaskActionKeys?.has(`${action}:${task.kind}:${task.id}`) === true

const traceSpecTitle = (trace: TraceSummary) => {
  if (!trace.dataAvailable) return t('app.historyItemUnavailable')
  const summary = trace.violatedSpec ? formatTraceSpec(trace.violatedSpec, t, trace.modelTokenSourcesByDeviceId) : ''
  return summary || t('app.unknownSpecification')
}

const fuzzFindingTitle = (finding: FuzzingFindingSummary, run: AvailableFuzzingRunSummary) => {
  if (finding.dataAvailable === false) return t('app.historyItemUnavailable')
  const summary = finding.violatedSpec ? formatTraceSpec(finding.violatedSpec, t, run.modelTokenSourcesByDeviceId) : ''
  return summary || finding.specificationLabel || finding.violatedSpecId || t('app.unknownSpecification')
}

const displayStep = (zeroBasedStep: number) => zeroBasedStep + 1

const fuzzingModeLabel = (mode: FuzzingExplorationMode) => t(
  mode === 'PAPER_COMPATIBLE' ? 'app.fuzzModePaper' : 'app.fuzzModeBoard'
)

const fuzzingModeDescription = (mode: FuzzingExplorationMode) => t(
  mode === 'PAPER_COMPATIBLE'
    ? 'app.fuzzModePaperDescription'
    : 'app.fuzzModeBoardDescription'
)

const comparisonFor = (snapshot: ModelRunSnapshot, kind: TaskKind) =>
  compareModelSnapshot(snapshot, props.currentModelFingerprints, kind)

const comparisonLabel = (comparison: RunBoardComparison) => comparison === 'UNAVAILABLE' && props.modelComparisonLoading
  ? t('app.historyModelComparing') : t({
  UNCHANGED: 'app.historyModelUnchanged',
  CHANGED: 'app.historyModelChanged',
  UNAVAILABLE: 'app.historyModelUnavailable',
  NOT_COMPARED: 'app.historyModelNotRecorded'
}[comparison])

const comparisonDetail = (comparison: RunBoardComparison, activeTask = false) => t({
  UNCHANGED: 'app.runBoardInputUnchanged',
  CHANGED: activeTask ? 'app.taskModelChangedDetail' : 'app.runBoardInputChanged',
  UNAVAILABLE: props.modelComparisonLoading ? 'app.runBoardComparisonLoading' : 'app.runBoardComparisonUnavailable',
  NOT_COMPARED: 'app.historyModelNotRecordedDetail'
}[comparison])

const taskGroups = computed(() => [
  { key: 'active', label: t('app.runningTasks'), items: activeTasks.value },
  { key: 'unresolved', label: t('app.tasksWithoutResults'), items: unresolvedTasks.value }
].filter(group => group.items.length))

const taskStageLabel = (task: TaskItem) => {
  const stage = activeTaskProgressStage(task.progressStage, task.status)
  return stage ? t(`app.taskProgressStage_${stage}`) : formatStatus(task.status)
}

const scopeText = (snapshot: ModelRunSnapshot, kind: TaskKind) => t(
  kind === 'simulation' ? 'app.runScopeWithoutSpecs' : 'app.runScopeCounts', {
    devices: snapshot.deviceCount, rules: snapshot.ruleCount, specs: snapshot.specificationCount
  }
)

const resultErrorEntries = computed(() => {
  const errors = props.resultErrors || {}
  const visibleSources: readonly ResultSource[] = props.resultFilter === 'all'
    ? ['verification', 'fuzzing', 'simulation']
    : [props.resultFilter]
  return visibleSources
    .filter(source => Boolean(errors[source]))
    .map(source => ({ source, error: errors[source]! }))
})
const hasStaleResults = computed(() => resultItems.value.some(item => Boolean(props.resultErrors?.[item.kind])))

const resultSourceLabel = (source: ResultSource) => {
  if (source === 'verification') return t('app.verificationRunResult')
  if (source === 'fuzzing') return t('app.fuzzRunResult')
  return t('app.simulationRunResult')
}

const resultLoadErrorKey = (source: ResultSource) => ({
  verification: 'app.failedToLoadVerificationHistory',
  fuzzing: 'app.failedToLoadFuzzingHistory',
  simulation: 'app.failedToLoadSimulationHistory'
}[source])

const emitDeleteResult = (item: ResultItem) => {
  if (item.kind === 'verification') emit('delete-verification-run', item.run)
  else if (item.kind === 'fuzzing') emit('delete-fuzzing-run', item.run)
  else emit('delete-simulation-run', item.run)
}

const generationIssuesFor = (item: AvailableVerificationRunSummary | AvailableSimulationTraceSummary | AvailableFuzzingRunSummary): ModelGenerationIssue[] =>
  'generationIssues' in item && Array.isArray(item.generationIssues) ? item.generationIssues : []

const runAssumptions = (run: Pick<AvailableVerificationRunSummary, 'isAttack' | 'attackBudget' | 'enablePrivacy'> & { modelSemantics?: ModelSemantics }): string[] => {
  const assumptions: string[] = []
  const points = run.modelSemantics?.modeledAttackPointCount
  if (!run.isAttack) {
    assumptions.push(t('app.runAssumptionNoAttack'))
  } else if (run.modelSemantics?.attackSelectionPolicy === 'EXACT_ATTACK_POINTS') {
    // The mode comes from the policy, never from `attackBudget`: the backend reports
    // `effectiveBudget() == points.size()` for an exact-points run, so inferring the mode from a
    // positive budget would label two pinned points as an exhaustive search over two.
    assumptions.push(t('app.runAssumptionAttackPoints', {
      count: typeof points === 'number' ? points : (run.attackBudget ?? '?')
    }))
  } else if (run.modelSemantics && typeof run.attackBudget === 'number' && run.attackBudget > 0) {
    assumptions.push(t('app.runAssumptionAttackBudget', {
      count: run.attackBudget,
      total: typeof points === 'number' ? points : '?'
    }))
  } else {
    assumptions.push(t('app.runAssumptionAttackEnabled'))
  }
  if (run.enablePrivacy) assumptions.push(t('app.runAssumptionPrivacy'))
  return assumptions
}

const verificationOutcomeBadge = (run: AvailableVerificationRunSummary) => {
  if (run.outcome === 'VIOLATED') {
    return {
      label: t('app.verificationFailedWithViolations', { count: run.violatedSpecCount }),
      className: 'board-surface-danger board-text-danger'
    }
  }
  if (run.outcome === 'SATISFIED' && run.modelComplete) {
    return {
      label: t('app.verificationPassed'),
      className: 'board-surface-success board-text-success'
    }
  }
  if (run.outcome === 'SATISFIED') {
    return {
      label: t('app.verificationPassedWithGenerationWarnings'),
      className: 'board-surface-warning board-text-warning'
    }
  }
  return {
    label: t('app.verificationInconclusiveSummary'),
    className: 'board-surface-warning board-text-warning'
  }
}

const fuzzingOutcomeBadge = (run: AvailableFuzzingRunSummary) => {
  if (run.outcome === 'FOUND_VIOLATION') {
    return {
      label: t('app.fuzzViolationFound'),
      className: 'board-surface-danger board-text-danger'
    }
  }
  if (run.outcome === 'BUDGET_EXHAUSTED') {
    return {
      label: t(run.limitations.includes('TIME_BUDGET_EXHAUSTED')
        ? 'app.fuzzTimeLimitReached' : 'app.fuzzBudgetExhausted'),
      className: 'board-meta-chip'
    }
  }
  return {
    label: t('app.fuzzInconclusive'),
    className: 'board-surface-warning board-text-warning'
  }
}

const resultOutcomeBadge = (item: ResultItem) => {
  if (!item.run.dataAvailable) return { label: t('app.historyItemUnavailable'), className: 'board-chip-warning board-text-warning' }
  if (item.kind === 'verification') return verificationOutcomeBadge(item.run)
  if (item.kind === 'fuzzing') return fuzzingOutcomeBadge(item.run)
  return { label: t('app.incompleteModel'), className: 'board-surface-warning board-text-warning' }
}

const resultTimestamp = (item: ResultItem) =>
  item.kind === 'simulation' ? item.run.createdAt : (item.run.completedAt || item.run.createdAt)
const resultDate = (item: ResultItem) => formatDate(resultTimestamp(item))
</script>

<template>
  <div
    ref="panelRef"
    class="board-floating-panel history-panel board-surface-panel fixed top-20 z-30 flex w-[480px] max-w-[calc(100vw-2rem)] flex-col overflow-hidden rounded-xl border shadow-2xl"
    data-testid="trace-history-panel" role="region" aria-labelledby="trace-history-title" tabindex="-1"
    @keydown.esc.stop.prevent="emit('close')"
  >
    <div class="board-tool-header">
      <div class="flex min-w-0 items-center gap-3">
        <div class="board-section-icon board-section-icon--lg">
          <span class="material-symbols-outlined text-xl board-text-muted" aria-hidden="true">history</span>
        </div>
        <div class="min-w-0">
          <h3 id="trace-history-title" class="board-panel-title">{{ t('app.runHistory') }}</h3>
        </div>
      </div>
      <HintTooltip :content="t('app.close')">
        <button type="button" data-testid="close-history-panel"
          class="board-panel-icon-action"
          :aria-label="t('app.close')" @click="emit('close')">
          <span class="material-symbols-outlined" aria-hidden="true">close</span>
        </button>
      </HintTooltip>
    </div>

    <div class="board-panel-tabs history-tabs">
      <div class="board-segmented grid grid-cols-2 gap-1 rounded-lg p-1">
        <button v-for="layer in (['tasks', 'results'] as const)" :key="layer" type="button"
          :data-testid="`history-layer-${layer}`" class="history-tab"
          :class="activeLayer === layer ? 'board-bg-elevated board-text-strong shadow-sm' : 'board-text-muted hover:board-text-strong'"
          :aria-pressed="activeLayer === layer" @click="emit('update:activeLayer', layer)">
          <span class="material-symbols-outlined text-base" aria-hidden="true">{{ layer === 'tasks' ? 'pending_actions' : 'fact_check' }}</span>
          {{ t(layer === 'tasks' ? 'app.taskStatusLayer' : 'app.historyResultsLayer') }}
          <span v-if="layer === 'tasks' && activeTasks.length" class="history-badge board-meta-chip">{{ activeTasks.length }}</span>
        </button>
      </div>
    </div>

    <div ref="bodyRef" class="iot-scroll-region board-panel-body history-body" @scroll="rememberScroll">
      <p v-if="actionLocked" class="history-notice board-surface-warning board-text-warning">
        <span class="material-symbols-outlined text-base" aria-hidden="true">lock</span>
        {{ t(pendingResultDeleteKeys?.size ? 'app.historyDeletionInProgress' : 'app.historyActionsLockedHint') }}
      </p>

      <template v-if="activeLayer === 'tasks'">
        <div class="history-toolbar">
          <p class="board-text-muted">{{ t('app.pendingTaskSummary', { active: activeTasks.length, unresolved: unresolvedTasks.length }) }}</p>
          <div class="flex shrink-0 items-center gap-4">
            <InfoTooltip :text="t('app.noPendingTasksHint')" test-id="history-tasks-help"
              :label="t('app.showHelpFor', { topic: t('app.taskStatusLayer') })" />
            <HintTooltip :content="t('app.refresh')">
              <button type="button" class="board-panel-icon-action" :aria-label="t('app.refresh')"
                :disabled="loadingTasks" @click="emit('refresh-tasks')">
                <span class="material-symbols-outlined" :class="loadingTasks ? 'animate-spin' : ''" aria-hidden="true">refresh</span>
              </button>
            </HintTooltip>
          </div>
        </div>
        <div v-if="taskError" data-testid="history-tasks-load-error"
          class="history-notice board-surface-warning board-text-warning" role="alert">
          <div class="min-w-0 flex-1 break-words">
            <p>{{ t('app.failedToLoadTasks') }}</p>
            <FailureDetails :details="technicalErrorLines(taskError)" suggestion="" />
            <p v-if="taskItems.length" class="mt-1">{{ t('app.taskStatusesMayBeStale') }}</p>
            <button type="button" class="history-text-action" :disabled="loadingTasks" @click="emit('refresh-tasks')">{{ t('app.retry') }}</button>
          </div>
        </div>
        <div v-if="loadingTasks && !taskItems.length" class="history-empty board-text-muted" role="status">
          <span class="material-symbols-outlined animate-spin text-2xl" aria-hidden="true">sync</span>
          <p>{{ t('app.loadingTasks') }}</p>
        </div>
        <div v-else-if="!taskError && taskItems.length === 0" class="history-empty board-text-muted">
          <span class="material-symbols-outlined text-2xl" aria-hidden="true">task_alt</span>
          <p class="font-semibold">{{ t('app.noPendingTasks') }}</p>
        </div>
        <template v-else>
          <section v-for="group in taskGroups" :key="group.key" class="history-group">
            <h4 class="history-group-title board-text-muted">{{ group.label }}</h4>
            <article v-for="task in group.items" :key="`${task.kind}-${task.id}`"
              :data-testid="`history-task-${task.kind}-${task.id}`" class="history-card board-card-surface">
              <div class="history-card__header">
                <h5 class="history-card__title board-text-strong">{{ taskKindLabel(task.kind) }}</h5>
                <time class="history-card__date board-text-muted" :datetime="task.createdAt">{{ formatDate(task.createdAt) }}</time>
              </div>
              <div class="history-card__badges">
                <span class="history-badge" :class="statusClass(task.status)">{{ formatStatus(task.status) }}</span>
                <RunInitiatorBadge :initiator="task.initiator" />
                <span v-if="task.kind === 'fuzzing'" :data-testid="`fuzzing-task-mode-${task.id}`"
                  class="history-badge board-meta-chip inline-flex items-center gap-3">
                  <span>{{ fuzzingModeLabel(task.explorationMode) }}</span>
                  <InfoTooltip :text="fuzzingModeDescription(task.explorationMode)"
                    :label="t('app.showHelpFor', { topic: t('app.fuzzExplorationMode') })"
                    :test-id="`fuzzing-task-mode-help-${task.id}`" />
                </span>
              </div>
              <p class="history-model-relation" :class="comparisonFor(task.modelSnapshot, task.kind) === 'CHANGED' ? 'board-text-warning' : 'board-text-muted'"
                :data-testid="`task-model-comparison-${task.kind}-${task.id}`" :data-state="comparisonFor(task.modelSnapshot, task.kind)">
                <span class="material-symbols-outlined text-base" aria-hidden="true">{{ comparisonFor(task.modelSnapshot, task.kind) === 'CHANGED' ? 'edit_note' : 'layers' }}</span>
                <span>{{ comparisonLabel(comparisonFor(task.modelSnapshot, task.kind)) }}</span>
                <InfoTooltip v-if="comparisonFor(task.modelSnapshot, task.kind) !== 'CHANGED' || !isActiveStatus(task.status)"
                  :text="comparisonDetail(comparisonFor(task.modelSnapshot, task.kind), isActiveStatus(task.status))"
                  :label="t('app.showHelpFor', { topic: comparisonLabel(comparisonFor(task.modelSnapshot, task.kind)) })"
                  :test-id="`${task.kind}-task-model-help-${task.id}`" />
              </p>
              <p v-if="comparisonFor(task.modelSnapshot, task.kind) === 'CHANGED' && isActiveStatus(task.status)" class="history-card__copy board-text-warning">
                {{ t('app.taskModelChangedDetail') }}
              </p>
              <template v-if="isActiveStatus(task.status)">
                <div class="history-progress-label board-text-muted"><span>{{ taskStageLabel(task) }}</span><span>{{ taskProgress(task) }}%</span></div>
                <div class="history-progress board-bg-control" role="progressbar" :aria-label="`${taskKindLabel(task.kind)} ${t('app.progress')}`"
                  aria-valuemin="0" aria-valuemax="100" :aria-valuenow="taskProgress(task)">
                  <div class="bg-[color:var(--accent)]" :style="{ width: `${taskProgress(task)}%` }"></div>
                </div>
              </template>
              <template v-else>
                <p class="history-card__copy board-text-muted">{{ task.status === 'CANCELLED' ? t('app.cancelledTaskNoResult') : t('app.failedTaskNoResult') }}</p>
                <p v-if="task.errorMessage" :data-testid="`task-failure-reason-${task.kind}-${task.id}`"
                  class="history-notice board-surface-danger board-text-danger">
                  <span><strong>{{ t('app.technicalDetails') }}:</strong> {{ task.errorMessage }}</span>
                </p>
              </template>
              <div class="history-card__actions">
                <template v-if="isActiveStatus(task.status)">
                  <button type="button" class="board-action-secondary" @click="emitWatchTask(task)">{{ t('app.watchTask') }}</button>
                  <button type="button" class="history-text-action board-text-muted hover:board-text-danger" :disabled="taskActionPending('cancel', task)"
                    :aria-busy="taskActionPending('cancel', task)" @click="emitCancelTask(task)">{{ t('app.cancel') }}</button>
                </template>
                <template v-else>
                  <button type="button" :data-testid="`reopen-task-settings-${task.kind}-${task.id}`" class="board-action-secondary"
                    @click="emit('reopen-task-settings', task.kind)">{{ t('app.adjustAndRunAgain') }}</button>
                  <button type="button" class="history-text-action board-text-muted hover:board-text-strong" :disabled="taskActionPending('dismiss', task)"
                    :aria-busy="taskActionPending('dismiss', task)" @click="emitDismissTask(task)">{{ t('app.dismissTask') }}</button>
                </template>
              </div>
            </article>
          </section>
        </template>
      </template>

      <template v-else>
        <div class="history-toolbar">
          <FieldSelect
            :model-value="resultFilter"
            @update:model-value="emit('update:resultFilter', $event)"
            class="history-filter-select"
            data-testid="history-result-type"
            :aria-label="t('app.historyResultType')"
          >
            <option v-for="filter in (['all', 'verification', 'fuzzing', 'simulation'] as const)" :key="filter" :value="filter">
              {{ filter === 'all' ? t('app.allResults') : taskKindLabel(filter) }}
            </option>
          </FieldSelect>
          <div class="board-segmented history-filters">
            <button v-for="filter in (['all', 'verification', 'fuzzing', 'simulation'] as const)" :key="filter" type="button"
              :data-testid="`history-result-filter-${filter}`" class="history-tab"
              :class="resultFilter === filter ? 'board-bg-elevated board-text-strong shadow-sm' : 'board-text-muted hover:board-text-strong'"
              :aria-label="filter === 'all' ? t('app.allResults') : taskKindLabel(filter)"
              :aria-pressed="resultFilter === filter" @click="emit('update:resultFilter', filter)">
              {{ filter === 'all' ? t('app.allResults') : filter === 'fuzzing' ? t('app.fuzzHistoryFilter') : taskKindLabel(filter) }}
            </button>
          </div>
          <HintTooltip :content="t('app.refresh')">
            <button type="button" class="board-panel-icon-action"
              :aria-label="t('app.refresh')" :disabled="loadingResults" @click="emit('refresh-results')">
              <span class="material-symbols-outlined" :class="loadingResults ? 'animate-spin' : ''" aria-hidden="true">refresh</span>
            </button>
          </HintTooltip>
        </div>
        <div v-if="loadingResults && !resultItems.length" class="history-empty board-text-muted" role="status">
          <span class="material-symbols-outlined animate-spin text-2xl" aria-hidden="true">sync</span><p>{{ t('app.loadingRunResults') }}</p>
        </div>
        <div v-if="!loadingResults && resultErrorEntries.length" data-testid="history-results-load-error"
          class="history-notice board-surface-warning board-text-warning" role="alert">
          <div class="min-w-0 flex-1 break-words">
            <p class="font-semibold">{{ t('app.historyResultsLoadFailed') }}</p>
            <p v-if="hasStaleResults" class="mt-1">{{ t('app.historyResultsMayBeStale') }}</p>
            <ul class="mt-2 space-y-1">
              <li v-for="entry in resultErrorEntries" :key="entry.source">
                <p>{{ t(resultLoadErrorKey(entry.source)) }}</p>
                <FailureDetails :details="technicalErrorLines(entry.error)" suggestion="" />
              </li>
            </ul>
            <button type="button" class="history-text-action" :disabled="loadingResults" @click="emit('refresh-results')">{{ t('app.retry') }}</button>
          </div>
        </div>
        <div v-if="!loadingResults && !resultErrorEntries.length && !resultItems.length" class="history-empty board-text-muted">
          <span class="material-symbols-outlined text-2xl" aria-hidden="true">fact_check</span>
          <p class="font-semibold">{{ resultFilter === 'all' ? t('app.noRunResults')
            : t('app.noFilteredRunResults', { kind: taskKindLabel(resultFilter) }) }}</p>
          <p v-if="resultFilter === 'all'">{{ t('app.noRunResultsHint') }}</p>
          <button v-else-if="verificationRuns.length || fuzzingRuns.length || simulationRuns.length" type="button"
            class="history-text-action" @click="emit('update:resultFilter', 'all')">{{ t('app.showAllResults') }}</button>
        </div>

        <div v-if="resultItems.length" class="history-group">
          <article v-for="item in resultItems" :key="`${item.kind}-${item.run.id}`"
            :data-testid="`history-result-${item.kind}-${item.run.id}`" class="history-card board-card-surface">
            <div class="history-card__header">
              <h4 class="history-card__title board-text-strong">{{ resultSourceLabel(item.kind) }}</h4>
              <time class="history-card__date board-text-muted" :datetime="resultTimestamp(item)">{{ resultDate(item) }}</time>
            </div>
            <div class="history-card__badges">
              <span v-if="item.kind !== 'simulation' || !item.run.dataAvailable || !item.run.modelComplete" class="history-badge" :class="resultOutcomeBadge(item).className">{{ resultOutcomeBadge(item).label }}</span>
              <RunInitiatorBadge :initiator="item.run.initiator" />
              <span v-if="item.run.dataAvailable && item.kind === 'fuzzing'" :data-testid="`fuzzing-history-mode-${item.run.id}`"
                class="history-badge board-meta-chip inline-flex items-center gap-3">
                <span>{{ fuzzingModeLabel(item.run.explorationMode) }}</span>
                <InfoTooltip :text="fuzzingModeDescription(item.run.explorationMode)"
                  :label="t('app.showHelpFor', { topic: t('app.fuzzExplorationMode') })"
                  :test-id="`fuzzing-history-mode-help-${item.run.id}`" />
              </span>
            </div>

            <p v-if="item.run.dataAvailable === false" class="history-card__copy board-text-muted">{{ t('app.historyItemUnavailableDetail') }}</p>
            <template v-else>
              <p class="history-model-relation" :class="comparisonFor(item.run.modelSnapshot, item.kind) === 'CHANGED' ? 'board-text-warning' : 'board-text-muted'"
                :data-testid="`${item.kind}-history-model-comparison-${item.run.id}`" :data-state="comparisonFor(item.run.modelSnapshot, item.kind)">
                <span class="material-symbols-outlined text-base" aria-hidden="true">{{ comparisonFor(item.run.modelSnapshot, item.kind) === 'CHANGED' ? 'edit_note' : 'layers' }}</span>
                <span>{{ comparisonLabel(comparisonFor(item.run.modelSnapshot, item.kind)) }}</span>
                <InfoTooltip :text="comparisonDetail(comparisonFor(item.run.modelSnapshot, item.kind))"
                  :label="t('app.showHelpFor', { topic: comparisonLabel(comparisonFor(item.run.modelSnapshot, item.kind)) })"
                  :test-id="`${item.kind}-history-model-help-${item.run.id}`" />
              </p>
              <p class="history-card__scope board-text-muted">{{ scopeText(item.run.modelSnapshot, item.kind) }}</p>
              <p v-if="item.kind === 'simulation'" class="history-card__copy board-text-muted">{{ t('app.simulationHistoryCounts', {
                requested: item.run.requestedSteps, steps: item.run.steps, states: item.run.steps + 1 }) }}</p>
              <p v-if="item.kind === 'fuzzing' && item.run.outcome === 'BUDGET_EXHAUSTED'" class="history-card__copy board-text-muted">{{ t('app.fuzzNoViolationWithinBudget') }}</p>
              <p v-if="item.kind === 'verification' && item.run.outcome === 'VIOLATED' && item.run.counterexampleCount < item.run.violatedSpecCount"
                class="history-card__copy board-text-warning">{{ t('app.someViolationsHaveNoReplayableCounterexample') }}</p>

              <div class="history-disclosures">
              <details class="history-disclosure" :data-testid="`run-details-${item.kind}-${item.run.id}`">
                <summary class="board-text-muted hover:board-text-strong">{{ t('app.historyRunDetails') }}</summary>
                <div class="history-disclosure__body">
                  <p class="board-text-muted">{{ t('app.historySnapshotCaptured', { time: formatDate(item.run.modelSnapshot.capturedAt) }) }}</p>
                  <p class="board-text-muted">{{ t('app.historyModelResources', { environments: item.run.modelSnapshot.environmentVariableCount, templates: item.run.modelSnapshot.deviceTemplateCount }) }}</p>
                  <p class="board-text-muted">{{ comparisonDetail(comparisonFor(item.run.modelSnapshot, item.kind)) }}</p>
                  <ul v-if="item.kind !== 'fuzzing'" :aria-label="t('app.runAssumptionsLabel')"
                    :data-testid="`${item.kind}-run-assumptions-${item.run.id}`" class="history-card__badges">
                    <li v-for="assumption in runAssumptions(item.run)" :key="assumption" class="history-badge board-meta-chip">{{ assumption }}</li>
                  </ul>
                  <p v-if="item.kind === 'fuzzing'" class="board-text-muted">{{ t('app.fuzzRunCounts', {
                    iterations: item.run.iterations, paths: item.run.generatedPaths, elapsed: formatFuzzingElapsed(item.run.elapsedMs, t) }) }}</p>
                  <ul v-if="generationIssuesFor(item.run).length" class="history-diagnostics board-text-warning">
                    <li v-for="(issue, index) in generationIssuesFor(item.run)" :key="`${issue.itemLabel}-${index}`">
                      <strong>{{ issue.itemLabel || t('app.unknownModelItem') }}</strong>: {{ t(generationIssueReasonKey(issue)) }}
                    </li>
                  </ul>
                </div>
              </details>

              <details v-if="item.kind === 'verification' && (tracesForRun(item.run).length || item.run.violatedSpecCount > 0 || item.run.counterexampleCount > 0)"
                class="history-disclosure" :data-testid="`run-evidence-verification-${item.run.id}`">
                <summary :class="item.run.outcome === 'VIOLATED' ? 'board-text-danger' : 'board-text-warning'">
                  {{ t('app.historyCounterexamples', { count: item.run.counterexampleCount }) }}
                </summary>
                <div class="history-disclosure__body">
                  <p v-if="item.run.outcome !== 'VIOLATED'" class="board-text-warning">{{ t('app.inconclusiveEvidenceSummary', { counterexamples: item.run.counterexampleCount }) }}</p>
                  <p v-if="tracesForRun(item.run).length" :id="`historical-fix-caveat-${item.run.id}`" :data-testid="`historical-fix-caveat-${item.run.id}`"
                    class="board-text-muted">{{ comparisonFor(item.run.modelSnapshot, 'verification') === 'UNCHANGED'
                      ? t('app.historicalFixMayFailIfBoardChanged') : comparisonDetail(comparisonFor(item.run.modelSnapshot, 'verification')) }}</p>
                  <div v-for="trace in tracesForRun(item.run)" :key="trace.id" class="history-evidence-row board-muted-surface">
                    <p class="font-semibold board-text-strong" :title="traceSpecTitle(trace)">{{ traceSpecTitle(trace) }}</p>
                    <p :class="trace.dataAvailable ? 'board-text-muted' : 'board-text-warning'">{{ trace.dataAvailable
                      ? t('app.statesCount', { count: trace.stateCount || 0 }) : t('app.historyTraceUnavailableDetail') }}</p>
                    <div class="history-evidence-actions">
                      <button type="button" :data-testid="`view-verification-trace-${trace.id}`" class="board-action-secondary"
                        :disabled="actionLocked || !trace.dataAvailable" @click="emit('view-verification-trace', trace.id)">{{ t('app.replay') }}</button>
                      <button type="button" :data-testid="`fix-verification-trace-${trace.id}`" class="history-text-action board-text-muted hover:board-text-strong"
                        :disabled="actionLocked || !trace.dataAvailable || comparisonFor(item.run.modelSnapshot, 'verification') !== 'UNCHANGED'"
                        :aria-describedby="trace.dataAvailable ? `historical-fix-caveat-${item.run.id}` : undefined"
                        @click="emit('fix-verification-trace', trace)">{{ t('app.fixRules') }}</button>
                    </div>
                  </div>
                </div>
              </details>

              <details v-if="item.kind === 'fuzzing' && item.run.outcome === 'FOUND_VIOLATION' && item.run.findings.length"
                class="history-disclosure" :data-testid="`run-evidence-fuzzing-${item.run.id}`">
                <summary class="board-text-danger">{{ t('app.fuzzFindingsCount', { count: item.run.findings.length }) }}</summary>
                <div class="history-disclosure__body">
                  <p data-testid="history-fuzz-verification-scope" class="board-text-muted">{{ t('app.fuzzFormalVerificationCurrentBoardNotice') }}</p>
                  <div v-for="finding in item.run.findings" :key="finding.id" class="history-evidence-row board-muted-surface">
                    <p class="font-semibold board-text-strong" :title="fuzzFindingTitle(finding, item.run)">{{ fuzzFindingTitle(finding, item.run) }}</p>
                    <p :class="finding.dataAvailable === false ? 'board-text-warning' : 'board-text-muted'">{{ finding.dataAvailable === false
                      ? t('app.historyFindingUnavailableDetail') : t('app.fuzzFirstViolationStep', { step: displayStep(finding.firstViolationStep) }) }}</p>
                    <div class="history-evidence-actions">
                      <button type="button" :data-testid="`view-fuzzing-finding-${finding.id}`" class="board-action-secondary"
                        :disabled="actionLocked || finding.dataAvailable === false" @click="emit('view-fuzzing-finding', finding.id, item.run.id)">{{ t('app.replay') }}</button>
                      <button type="button" :data-testid="`verify-fuzzing-finding-${finding.id}`" class="history-text-action board-text-muted hover:board-text-strong"
                        :disabled="actionLocked || finding.dataAvailable === false" @click="emit('verify-fuzzing-finding', finding)">{{ t('app.configureCurrentBoardVerification') }}</button>
                    </div>
                  </div>
                </div>
              </details>
              </div>
            </template>

            <div class="history-card__actions">
              <template v-if="item.run.dataAvailable">
                <button v-if="item.kind === 'verification'" type="button" :data-testid="`open-verification-run-${item.run.id}`" class="board-action-secondary"
                  :disabled="actionLocked" @click="emit('open-verification-run', item.run.id)">{{ t('app.openResult') }}</button>
                <button v-else-if="item.kind === 'fuzzing'" type="button" :data-testid="`open-fuzzing-run-${item.run.id}`" class="board-action-secondary"
                  :disabled="actionLocked" @click="emit('open-fuzzing-run', item.run.id)">{{ t('app.openResult') }}</button>
                <button v-else type="button" :data-testid="`replay-simulation-trace-${item.run.id}`" class="board-action-secondary"
                  :disabled="actionLocked" @click="emit('view-simulation-run', item.run.id)">
                  <span class="material-symbols-outlined text-base" aria-hidden="true">play_arrow</span>{{ t('app.replay') }}
                </button>
                <button v-if="item.kind === 'verification' && item.run.hasSmvModel" type="button" :data-testid="`download-verification-run-smv-${item.run.id}`"
                  class="history-text-action board-text-muted hover:board-text-strong" :disabled="actionLocked" @click="emit('download-verification-run-smv', item.run.id)">{{ t('app.downloadSmvModel') }}</button>
                <button v-if="item.kind === 'simulation' && item.run.hasSmvModel" type="button" :data-testid="`download-simulation-trace-smv-${item.run.id}`"
                  class="history-text-action board-text-muted hover:board-text-strong" :disabled="actionLocked" @click="emit('download-simulation-trace-smv', item.run.id)">{{ t('app.downloadSmvModel') }}</button>
              </template>
              <button type="button" :data-testid="resultDeleteTestId(item.kind, item.run.id)"
                class="board-panel-icon-action board-panel-icon-action--danger history-delete"
                :aria-label="t('app.delete')" :title="t('app.delete')" :disabled="actionLocked || isResultDeletePending(item.kind, item.run.id)"
                :aria-busy="isResultDeletePending(item.kind, item.run.id)" @click="emitDeleteResult(item)">
                <span class="material-symbols-outlined" :class="isResultDeletePending(item.kind, item.run.id) ? 'animate-spin' : ''" aria-hidden="true">{{ isResultDeletePending(item.kind, item.run.id) ? 'sync' : 'delete' }}</span>
              </button>
            </div>
          </article>
          <button v-if="resultFilter === 'fuzzing' && hasMoreFuzzingRuns" type="button" data-testid="load-more-fuzzing-runs"
            class="board-action-secondary w-full" :disabled="actionLocked || loadingMoreFuzzingRuns" @click="emit('load-more-fuzzing-runs')">
            {{ loadingMoreFuzzingRuns ? t('app.loadingMoreFuzzingResults') : t('app.loadMoreFuzzingResults') }}
          </button>
        </div>
      </template>
    </div>
  </div>
</template>
