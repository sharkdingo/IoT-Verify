<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TraceTriggeredRule } from '@/types/verify'
import type { FuzzingInputEvent } from '@/types/fuzzing'
import type {
  PlaybackChangeKind,
  PlaybackDeviceChange,
  PlaybackDeviceChangeDetail,
  PlaybackEnvironmentChange
} from '@/utils/traceView'
import { formatBuiltInModelToken } from '@/utils/modelTokenDisplay'

const props = withDefaults(defineProps<{
  changes: PlaybackDeviceChange[]
  environmentChanges: PlaybackEnvironmentChange[]
  triggeredRules: TraceTriggeredRule[]
  animatedEdgeCount: number
  stateNumber: number
  totalStates: number
  kind: 'simulation' | 'counterexample' | 'fuzzing'
  inputEvents?: Array<FuzzingInputEvent & { targetLabel?: string }>
  /**
   * 1-based state number where the property is violated, or undefined when no single state is the fault.
   *
   * Both playback kinds supply it. It used to be exploration-only, which left a safety counterexample's
   * violating state unmarked here while the rail marker beneath said "Violation" on it. Undefined for a
   * liveness cycle, where `loopBackSentence` carries the explanation instead.
   */
  violationStateNumber?: number
  bundledDeviceIds?: string[]
  bundledEnvironmentNames?: string[]
  currentRuleIds?: string[]
  /**
   * This state closes an infinite counterexample by repeating the loop entry, so having no observable change
   * is the state's meaning rather than an absence of information. Without saying so the panel reports
   * "no observable changes" on the final step of a liveness violation, which reads as a broken animation.
   */
  isLoopBackState?: boolean
  /** 1-based state numbers of the repeating cycle, for the sentence explaining the loop. */
  loopRange?: { start: number; end: number } | null
  /**
   * The violated property is a liveness claim, so the cycle IS the violation and the sentence may say the
   * required state is never reached. False for a safety counterexample that merely ends on a cycle — NuSMV
   * reports a loop for those too, and there the fault is a single state, so that claim would be wrong.
   */
  isLivenessViolation?: boolean
}>(), {
  bundledDeviceIds: () => [],
  bundledEnvironmentNames: () => []
})

const { t, te } = useI18n()
const formatBundledModelToken = (value: unknown) => formatBuiltInModelToken(
  value,
  key => te(key) ? t(key) : key
)
const bundledDeviceIdSet = computed(() => new Set(props.bundledDeviceIds))
const bundledEnvironmentNameSet = computed(() => new Set(props.bundledEnvironmentNames))
const currentRuleIdSet = computed(() => props.currentRuleIds ? new Set(props.currentRuleIds.map(String)) : null)
const formatDeviceToken = (deviceId: string, value: unknown) =>
  bundledDeviceIdSet.value.has(deviceId)
    ? formatBundledModelToken(value)
    : String(value ?? '')
const formatDeviceProperty = (deviceId: string, property: unknown) =>
  bundledDeviceIdSet.value.has(deviceId)
    ? formatBundledModelToken(property)
    : String(property ?? '')
const formatEnvironmentToken = (name: string, value: unknown) =>
  bundledEnvironmentNameSet.value.has(name)
    ? formatBundledModelToken(value)
    : String(value ?? '')

const title = computed(() => {
  if (props.kind === 'simulation') return t('app.traceVisualization.simulationStepChanges')
  if (props.kind === 'fuzzing') return t('app.traceVisualization.fuzzingStepChanges')
  return t('app.traceVisualization.counterexampleStepChanges')
})

const isInitialState = computed(() => props.stateNumber <= 1)
const isViolationState = computed(() =>
  props.violationStateNumber !== undefined && props.violationStateNumber === props.stateNumber)
const hasObservableChanges = computed(() =>
  props.changes.length > 0 || props.environmentChanges.length > 0)

/**
 * What to say on the state that closes the path into a cycle.
 *
 * Two different facts, so two sentences. For a liveness property the repetition *is* the violation, so the
 * sentence says the required state is never reached and that the cycle is the fault. For a safety property
 * NuSMV may still report a loop — measured on both a CTL `AX` and an LTL `G(p)` counterexample — but there
 * the fault is a single state, so the sentence stays factual about the repetition and says the violation is
 * that marked state rather than the loop.
 *
 * Both sentences were previously mechanical ("State 3 loops back to state 2"), which made the two branches
 * differ only in arithmetic and left this branch conveying nothing it exists to convey. Rendering coverage
 * lives in `PlaybackChangesPanel.spec.ts`; the pre-existing source-read guard could only see the element
 * ordering, never the wording.
 */
const loopBackSentence = computed(() => {
  const range = props.loopRange
  if (!range) return t('app.traceLoopRepeats')
  return props.isLivenessViolation
    ? t('app.traceLoopExplanation', { start: range.start, end: range.end })
    : t('app.traceLoopRepeatsDetail', { start: range.start })
})
const playbackSummary = computed(() => {
  if (isInitialState.value) {
    return t('app.traceVisualization.playbackInitialStateSummary')
  }
  const counts = {
    devices: props.changes.length,
    environment: props.environmentChanges.length,
    rules: props.triggeredRules.length
  }
  return props.triggeredRules.length > 0
    ? t('app.traceVisualization.playbackChangesSummaryWithRules', counts)
    : t('app.traceVisualization.playbackChangesSummaryWithoutRules', counts)
})

const ruleLabel = (rule: TraceTriggeredRule, index: number): string =>
  rule.ruleLabel?.trim() || t('app.ruleNumber', { number: index + 1 })

const inputEventKindLabel = (event: FuzzingInputEvent) => {
  if (event.kind === 'DEVICE_STATE') return t('app.traceVisualization.fuzzDeviceStateInput')
  if (event.kind === 'DEVICE_VARIABLE') return t('app.traceVisualization.fuzzDeviceInput')
  if (event.kind === 'ENVIRONMENT_RATE') return t('app.traceVisualization.fuzzEnvironmentRateInput')
  return t('app.traceVisualization.fuzzEnvironmentInput')
}

const inputEventSourceLabel = (event: FuzzingInputEvent) => {
  if (event.source === 'RANDOM_INITIAL_STATE') return t('app.traceVisualization.fuzzRandomInitialSource')
  if (event.source === 'SEED_EVENT') return t('app.traceVisualization.fuzzSeedEventSource')
  return t('app.traceVisualization.fuzzModelChoiceSource')
}

const inputEventValue = (event: FuzzingInputEvent) => {
  const value = event.kind === 'ENVIRONMENT_RATE' && event.value.startsWith('rate:')
    ? event.value.slice(5)
    : event.value
  return event.kind === 'DEVICE_STATE' || event.kind === 'DEVICE_VARIABLE'
    ? formatDeviceToken(event.targetId, value)
    : formatEnvironmentToken(event.property, value)
}

const inputEventProperty = (event: FuzzingInputEvent) =>
  event.kind === 'DEVICE_STATE' || event.kind === 'DEVICE_VARIABLE'
    ? formatDeviceProperty(event.targetId, event.property)
    : formatEnvironmentToken(event.property, event.property)

const detailLabel = (detail: PlaybackDeviceChangeDetail, deviceId: string): string => {
  const labels: Record<PlaybackChangeKind, string> = {
    state: detail.name ? formatDeviceProperty(deviceId, detail.name) : t('app.state'),
    mode: t('app.mode'),
    variable: detail.name ? formatDeviceToken(deviceId, detail.name) : t('app.variableValue'),
    security: t('app.traceVisualization.securityLabels'),
    compromised: t('app.traceVisualization.compromiseStatus')
  }
  return labels[detail.kind]
}

const formatValue = (value: string, kind: PlaybackChangeKind, deviceId: string): string => {
  if (kind === 'compromised') {
    return value === 'true'
      ? t('app.traceVisualization.compromised')
      : t('app.traceVisualization.notCompromised')
  }
  if (kind !== 'security') return formatDeviceToken(deviceId, value)
  return [
    ['untrusted', t('app.untrusted')],
    ['trusted', t('app.trusted')],
    ['private', t('app.private')],
    ['public', t('app.public')],
    ['trust=', `${t('app.trust')}=`],
    ['privacy=', `${t('app.privacy')}=`]
  ].reduce((result, [source, target]) => result.split(source).join(target), value)
}
</script>

<template>
  <section class="playback-changes" data-testid="playback-changes-panel" :aria-label="title">
    <div class="playback-changes__summary">
      <p :data-testid="isInitialState ? 'playback-change-initial-state' : undefined" aria-live="polite" aria-atomic="true">{{ playbackSummary }}</p>
      <span v-if="isViolationState" class="board-chip-danger board-text-danger playback-terminal__badge" data-testid="playback-violation-badge">
        {{ kind === 'fuzzing' ? t('app.fuzzFirstViolation') : t('app.traceViolationHere') }}
      </span>
    </div>
        <article
          v-if="kind === 'fuzzing'"
          class="playback-changes__card playback-changes__inputs"
          data-testid="playback-change-fuzz-inputs"
        >
          <div class="flex items-center gap-1.5 font-semibold board-text-muted">
            <span class="material-symbols-outlined text-sm board-text-muted" aria-hidden="true">input</span>
            <span>{{ t('app.traceVisualization.fuzzInputsInThisStep') }}</span>
          </div>
          <ul v-if="inputEvents?.length" class="mt-1.5 space-y-1">
            <li
              v-for="(event, index) in inputEvents"
              :key="`${event.kind}-${event.targetId}-${event.property}-${index}`"
              class="playback-changes__event"
            >
              <span class="mr-1 inline-flex rounded board-meta-chip px-1.5 py-0.5 font-semibold board-text-muted">{{ inputEventSourceLabel(event) }}</span>
              <span class="font-semibold">{{ inputEventKindLabel(event) }}</span>
              <span class="px-1 board-text-muted" aria-hidden="true">·</span>
              <span>{{ event.targetLabel || event.targetId }}.{{ inputEventProperty(event) }}</span>
              <span class="px-1 font-semibold board-text-muted" aria-hidden="true">=</span>
              <span class="break-all font-mono font-semibold">{{ inputEventValue(event) }}</span>
            </li>
          </ul>
          <p v-else class="mt-1 text-[length:var(--iot-font-caption)] leading-4 board-text-muted">
            {{ t('app.traceVisualization.noFuzzInputInThisStep') }}
          </p>
        </article>

        <div
          v-if="kind === 'fuzzing' && !isInitialState"
          class="playback-changes__group-label"
        >
          {{ t('app.traceVisualization.fuzzObservedModelChanges') }}
        </div>

    <div>

        <!-- Ordered before the generic empty state on purpose: on a loop-back step "no observable changes" is
             the symptom, and the repetition is the explanation the viewer needs. -->
        <div
          v-if="!isInitialState && isLoopBackState"
          class="playback-changes__empty board-surface-warning"
          data-testid="playback-change-loop-back"
        >
          {{ loopBackSentence }}
        </div>

        <div
          v-else-if="!isInitialState && !hasObservableChanges"
          class="playback-changes__empty"
          data-testid="playback-change-empty"
        >
          {{ t('app.traceVisualization.playbackNoObservableChanges') }}
        </div>

      <section v-if="changes.length" class="playback-evidence-group" data-testid="playback-change-devices">
        <h3 class="playback-evidence-group__heading"><span class="material-symbols-outlined" aria-hidden="true">devices</span>{{ t('app.playbackDeviceChanges') }}<span>{{ changes.length }}</span></h3>
        <div class="playback-changes__grid">
        <article
          v-for="change in changes"
          :key="change.deviceId"
          class="playback-changes__card"
          :data-testid="`playback-change-device-${change.deviceId}`"
        >
          <div class="flex items-center justify-between gap-2">
            <div class="flex min-w-0 items-center gap-1.5 font-semibold board-text-muted">
              <span class="material-symbols-outlined text-sm board-text-muted" aria-hidden="true">devices</span>
              <span class="break-words board-text-strong">{{ change.deviceLabel || t('app.unknown') }}</span>
            </div>
            <span class="shrink-0 text-[length:var(--iot-font-caption)] font-semibold board-text-muted">
              {{ change.details.length }} {{ t('app.traceVisualization.changeCountSuffix') }}
            </span>
          </div>
          <ul class="mt-1.5 space-y-1">
            <li
              v-for="(detail, index) in change.details"
              :key="`${detail.kind}-${detail.name || ''}-${index}`"
              class="playback-changes__row"
              :data-change-kind="detail.kind"
            >
              <span class="font-semibold board-text-muted" :title="detailLabel(detail, change.deviceId)">{{ detailLabel(detail, change.deviceId) }}</span>
              <span class="min-w-0 break-words font-mono board-text-strong">
                <span class="board-text-muted">{{ formatValue(detail.previousValue, detail.kind, change.deviceId) }}</span>
                <span class="px-1 font-sans font-semibold board-text-muted" aria-hidden="true">→</span>
                <span class="font-semibold board-text-accent">{{ formatValue(detail.currentValue, detail.kind, change.deviceId) }}</span>
              </span>
            </li>
          </ul>
        </article>
        </div>
      </section>

        <article
          v-if="environmentChanges.length > 0"
          class="playback-evidence-group"
          data-testid="playback-change-environment"
        >
          <h3 class="playback-evidence-group__heading"><span class="material-symbols-outlined" aria-hidden="true">public</span>{{ t('app.traceVisualization.environmentChanges') }}<span>{{ environmentChanges.length }}</span></h3>
          <ul class="mt-1.5 space-y-1">
            <li
              v-for="change in environmentChanges"
              :key="change.name"
              class="playback-changes__row"
            >
              <span class="font-semibold board-text-muted" :title="formatEnvironmentToken(change.name, change.name)">{{ formatEnvironmentToken(change.name, change.name) }}</span>
              <span class="min-w-0 break-words font-mono board-text-strong">
                <span class="board-text-muted">{{ formatEnvironmentToken(change.name, change.previousValue) }}</span>
                <span class="px-1 font-sans font-semibold board-text-accent" aria-hidden="true">→</span>
                <span class="font-semibold board-text-accent">{{ formatEnvironmentToken(change.name, change.currentValue) }}</span>
              </span>
            </li>
          </ul>
        </article>

        <article
          v-if="triggeredRules.length > 0"
          class="playback-evidence-group"
          data-testid="playback-change-automation"
        >
          <h3 class="playback-evidence-group__heading"><span class="material-symbols-outlined" aria-hidden="true">account_tree</span>{{ t('app.traceVisualization.automationInThisStep') }}<span>{{ triggeredRules.length }}</span></h3>
          <ul class="playback-evidence__list">
            <li
              v-for="(rule, index) in triggeredRules"
              :key="rule.ruleId || `${rule.ruleLabel}-${index}`"
              class="board-text-strong text-[length:var(--iot-font-caption)]"
            >
              {{ ruleLabel(rule, index) }}
              <span v-if="currentRuleIdSet && rule.ruleId && !currentRuleIdSet.has(String(rule.ruleId))" class="playback-evidence__scope">
                {{ t('app.traceVisualization.historicalRuleNotOnCurrentBoard') }}
              </span>
            </li>
          </ul>
          <p v-if="animatedEdgeCount > 0" class="mt-1.5 text-[length:var(--iot-font-caption)] leading-4 board-text-muted">
            {{ t('app.traceVisualization.playbackAnimatedEdges') }}
          </p>
        </article>

    </div>
  </section>
</template>
