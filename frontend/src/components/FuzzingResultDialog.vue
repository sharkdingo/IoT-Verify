<script setup lang="ts">
import { formatRunTimestamp } from '@/utils/runTimestamp'
import type { RunBoardComparison } from '@/types/modelSemantics'
import { computed, onMounted, toRef } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AvailableFuzzingRunSummary, FuzzingFinding, FuzzingFindingSummary, FuzzingRun } from '@/types/fuzzing'
import { formatTraceSpec } from '@/utils/traceView'
import { useModalAccessibility } from '@/composables/useModalAccessibility'
import { formatFuzzingElapsed, fuzzingLimitationKey } from '@/utils/fuzzingPresentation'
import HintTooltip from '@/components/common/HintTooltip.vue'
import InfoTooltip from '@/components/common/InfoTooltip.vue'

const props = defineProps<{
  visible: boolean
  run: AvailableFuzzingRunSummary | FuzzingRun | null
  loading?: boolean
  error?: string | null
  actionLocked?: boolean
  boardComparison?: RunBoardComparison
}>()

const emit = defineEmits<{
  ready: []
  close: []
  replay: [findingId: number, runId: number]
  verify: [finding: FuzzingFindingSummary | FuzzingFinding]
  verifyCurrentBoard: []
  reuseSettings: []
}>()

const { t, locale } = useI18n()

onMounted(() => emit('ready'))

const closeDialog = () => emit('close')
const { setDialogRef, handleModalKeydown } = useModalAccessibility(
  toRef(props, 'visible'),
  closeDialog,
  opener => document.querySelector<HTMLElement>(opener?.closest('[data-testid="trace-history-panel"]')
    ? '[data-testid="open-history-panel"]' : '[data-testid="open-fuzzing-panel"]')
)

const timeLimitReached = computed(() => props.run?.limitations.includes('TIME_BUDGET_EXHAUSTED') === true)
const capabilityLimitations = computed(() => props.run?.limitations.filter(code => code !== 'TIME_BUDGET_EXHAUSTED') || [])

const outcome = computed(() => {
  if (props.run?.outcome === 'FOUND_VIOLATION') {
    return {
      icon: 'warning',
      label: t('app.fuzzViolationFound'),
      detail: t('app.fuzzViolationFoundDetail'),
      className: 'board-surface-danger',
      iconClass: 'board-surface-danger board-text-danger'
    }
  }
  if (props.run?.outcome === 'BUDGET_EXHAUSTED') {
    return {
      icon: 'search_off',
      label: t(timeLimitReached.value ? 'app.fuzzTimeLimitReached' : 'app.fuzzBudgetExhausted'),
      detail: t('app.fuzzNoViolationWithinBudget'),
      className: 'board-surface-info',
      iconClass: 'board-surface-info board-text-info'
    }
  }
  return {
    icon: 'help',
    label: t('app.fuzzInconclusive'),
    detail: t('app.fuzzInconclusiveDetail'),
    className: 'board-surface-warning',
    iconClass: 'board-surface-warning board-text-warning'
  }
})

const findingTitle = (finding: FuzzingFindingSummary | FuzzingFinding) => {
  const formatted = finding.violatedSpec ? formatTraceSpec(finding.violatedSpec, t, props.run?.modelTokenSourcesByDeviceId) : ''
  return formatted
    || ('specificationLabel' in finding ? finding.specificationLabel : '')
    || finding.violatedSpecId
    || t('app.unknownSpecification')
}

const elapsedLabel = computed(() => formatFuzzingElapsed(props.run?.elapsedMs || 0, t))
const explorationModeLabel = computed(() => t(
  props.run?.explorationMode === 'PAPER_COMPATIBLE'
    ? 'app.fuzzModePaper'
    : 'app.fuzzModeBoard'
))
const explorationModeDescription = computed(() => t(
  props.run?.explorationMode === 'PAPER_COMPATIBLE'
    ? 'app.fuzzModePaperDescription'
    : 'app.fuzzModeBoardDescription'
))
const displayStep = (zeroBasedStep: number) => zeroBasedStep + 1
/* One owner in `utils/runTimestamp.ts`. This copy returned the raw value for an empty timestamp, which renders
   as a blank cell — indistinguishable from a rendering failure. */
const formatDate = (value: string) => formatRunTimestamp(value, locale.value, t)

const eligibilityReasonKeys: Record<string, string> = {
  UNSUPPORTED_TEMPLATE: 'app.fuzzEligibilityUnsupportedTemplate',
  TRUST_PRIVACY_UNSUPPORTED: 'app.fuzzEligibilityTrustPrivacyUnsupported',
  REPORTED_READING_UNSUPPORTED: 'app.fuzzEligibilityReportedReadingUnsupported',
  CONTENT_COMMAND_UNSUPPORTED: 'app.fuzzEligibilityContentCommandUnsupported',
  MODEL_INVALID: 'app.fuzzEligibilityModelInvalid',
  INVALID_SPECIFICATION: 'app.fuzzEligibilityInvalidSpecification'
}

const eligibilityReason = (reasonCode: string) =>
  t(eligibilityReasonKeys[reasonCode] || 'app.fuzzEligibilityUnknown')

const limitationText = (code: string) =>
  t(fuzzingLimitationKey(code))

const capabilityBoundaryHeading = computed(() => t(
  props.run?.explorationMode === 'PAPER_COMPATIBLE'
    ? 'app.fuzzPaperCapabilitiesAndBoundaries'
    : 'app.fuzzLimitations'
))

const findingBySpecification = computed(() => new Map(
  (props.run?.findings || []).map(finding => [finding.violatedSpecId, finding])
))

const targetResults = computed(() => (props.run?.eligibility.eligibleSpecIds || []).map(specId => ({
  specId,
  label: props.run?.eligibleSpecifications?.[specId]
    ? formatTraceSpec(props.run.eligibleSpecifications[specId], t, props.run.modelTokenSourcesByDeviceId)
    : props.run?.eligibility.eligibleSpecLabels[specId] || specId,
  finding: findingBySpecification.value.get(specId)
})))
const targetsWithoutCandidate = computed(() => targetResults.value.filter(target => !target.finding))

const requestedTargetIds = computed(() => {
  const run = props.run
  return run && 'targetSpecIds' in run ? run.targetSpecIds : []
})

const targetScopeText = computed(() => requestedTargetIds.value.length > 0
  ? t('app.fuzzReproductionSelectedTargets', { count: requestedTargetIds.value.length })
  : t('app.fuzzReproductionAllTargets', { count: props.run?.eligibility.requestedSpecCount || 0 }))
</script>

<template>
  <div
    v-if="visible"
    class="iot-dialog-overlay"
    data-testid="fuzzing-result-dialog"
    @keydown="handleModalKeydown"
  >
    <section
      :ref="setDialogRef"
      class="iot-dialog iot-dialog--lg"
      role="dialog"
      aria-modal="true"
      aria-labelledby="fuzzing-result-title"
      aria-describedby="fuzzing-result-description"
      tabindex="-1"
      autofocus
    >
      <header class="iot-dialog__header">
        <div class="iot-dialog__icon">
          <span class="material-symbols-outlined" aria-hidden="true">radar</span>
        </div>
        <div class="iot-dialog__heading">
          <h3 id="fuzzing-result-title" class="iot-dialog__title">{{ t('app.fuzzRunResult') }}</h3>
          <p id="fuzzing-result-description" class="iot-dialog__subtitle">{{ t('app.fuzzResultHeuristicNotice') }}</p>
        </div>
        <HintTooltip :content="t('app.close')">
          <button
            type="button"
            data-testid="close-fuzzing-result"
            class="iot-dialog__close"
            :aria-label="t('app.close')"
            @click="closeDialog"
          >
            <span class="material-symbols-outlined" aria-hidden="true">close</span>
          </button>
        </HintTooltip>
      </header>

      <div
        data-testid="fuzzing-result-scroll"
        class="iot-dialog__body iot-scroll-region iot-scroll-region--inset-end space-y-4"
      >
        <div v-if="loading" class="flex flex-col items-center justify-center py-16 board-text-muted">
          <span class="material-symbols-outlined animate-spin text-4xl board-text-progress" aria-hidden="true">sync</span>
          <p class="mt-3 text-sm">{{ t('app.loadingFuzzRun') }}</p>
        </div>

        <div v-else-if="error" class="board-surface-danger rounded-lg p-4 text-sm leading-6" role="alert">
          {{ error }}
        </div>

        <template v-else-if="run">
          <div class="rounded-lg border p-4" :class="outcome.className">
            <div class="flex items-start gap-3">
              <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg" :class="outcome.iconClass" aria-hidden="true">
                <span class="material-symbols-outlined text-xl">{{ outcome.icon }}</span>
              </span>
              <div class="min-w-0">
                <div class="text-sm font-semibold">{{ outcome.label }}</div>
                <p class="mt-1 text-xs leading-5 opacity-90">{{ outcome.detail }}</p>
              </div>
            </div>
          </div>

          <p
            v-if="boardComparison !== 'UNCHANGED'"
            class="board-run-notice board-surface-warning board-text-warning"
            data-testid="fuzzing-board-drift-warning"
            :data-state="boardComparison || 'NOT_COMPARED'"
          >
            {{ t(boardComparison === 'CHANGED' ? 'app.fuzzBoardScopeChanged'
              : boardComparison === 'UNAVAILABLE' ? 'app.runBoardComparisonUnavailable' : 'app.runBoardNotCompared') }}
          </p>
          <p v-else class="board-caption leading-5 board-text-muted">{{ t('app.fuzzBoardScopeCurrent') }}</p>

          <section data-testid="fuzzing-result-mode" class="flex flex-wrap items-center gap-3" :aria-label="t('app.fuzzExplorationMode')">
            <span class="board-form-label">{{ t('app.fuzzExplorationMode') }}</span>
            <span class="board-caption board-text-strong">{{ explorationModeLabel }}</span>
            <InfoTooltip
              :text="explorationModeDescription"
              :label="t('app.showHelpFor', { topic: t('app.fuzzExplorationMode') })"
              test-id="fuzz-result-mode-help"
            />
          </section>

          <p v-if="timeLimitReached" data-testid="fuzz-time-limit-warning"
            class="board-surface-warning rounded-lg px-3 py-2 text-xs leading-5 board-text-warning" role="alert">
            {{ t('app.fuzzLimitationTimeBudgetExhausted') }}
          </p>

          <div
            v-if="run.eligibility.ineligibleSpecs.length > 0"
            class="board-surface-warning rounded-lg px-3 py-2 text-xs leading-5"
          >
            <div class="font-semibold">{{ t('app.fuzzIneligibleSpecifications', { count: run.eligibility.ineligibleSpecs.length }) }}</div>
            <ul class="mt-1 list-disc space-y-1 pl-4">
              <li v-for="(issue, index) in run.eligibility.ineligibleSpecs" :key="`${issue.specId || index}-${issue.reasonCode}`">
                <span>{{ issue.specificationLabel || issue.specId || t('app.unknownSpecification') }}: {{ eligibilityReason(issue.reasonCode) }}</span>
              </li>
            </ul>
          </div>

          <p
            data-testid="fuzz-formal-verification-current-board-notice"
            class="board-note rounded-lg px-3 py-2 text-xs leading-5"
          >
            {{ t('app.fuzzFormalVerificationCurrentBoardNotice') }}
          </p>

          <section v-if="run.findings.length > 0" aria-labelledby="fuzz-findings-title">
            <div class="flex items-center justify-between gap-3">
              <h4 id="fuzz-findings-title" class="board-section-title">{{ t('app.fuzzFindings') }}</h4>
              <span class="rounded-full board-bg-control px-2 py-0.5 text-xs font-semibold board-text-muted">{{ run.findings.length }}</span>
            </div>

            <div class="mt-2 divide-y divide-[color:var(--border)] overflow-hidden rounded-lg border board-border-subtle">
              <article v-for="finding in run.findings" :key="finding.id" class="board-bg-elevated p-3">
                <div class="flex flex-col items-start gap-3 sm:flex-row sm:justify-between">
                  <div class="min-w-0 w-full sm:flex-1">
                    <p class="break-words text-xs font-semibold board-text-strong">{{ findingTitle(finding) }}</p>
                    <p v-if="'dataAvailable' in finding && finding.dataAvailable === false" class="board-caption mt-1 board-text-warning" :data-testid="`fuzz-finding-unavailable-${finding.id}`">
                      {{ t('app.historyFindingUnavailableDetail') }}
                    </p>
                    <p v-else class="board-caption mt-1 board-text-muted">{{ t('app.fuzzFindingMeta', { step: displayStep(finding.firstViolationStep), seed: finding.seed }) }}</p>
                  </div>
                  <!-- `dataAvailable === false` marks a finding whose own detail load was rejected as
                       corrupt. `TraceHistoryPanel` disables both actions for exactly this, and this
                       dialog binds the same run object, so omitting the check left a finding that is
                       greyed out in history still fully armed here. Replay merely re-failed; "verify
                       formally" was worse, because it seeds a verification handoff from the summary
                       without re-fetching the finding, so it proceeded on evidence the server had
                       already rejected. -->
                  <div class="flex max-w-full flex-wrap gap-1.5 sm:shrink-0">
                    <button
                      type="button"
                      :data-testid="`replay-fuzzing-finding-${finding.id}`"
                      class="board-action-inline"
                      :disabled="actionLocked || ('dataAvailable' in finding && finding.dataAvailable === false)"
                      @click="emit('replay', finding.id, run.id)"
                    >
                      <span class="material-symbols-outlined text-sm" aria-hidden="true">play_arrow</span>{{ t('app.replay') }}
                    </button>
                    <button
                      type="button"
                      :data-testid="`verify-fuzzing-finding-${finding.id}`"
                      class="board-action-secondary"
                      :disabled="actionLocked || ('dataAvailable' in finding && finding.dataAvailable === false)"
                      @click="emit('verify', finding)"
                    >
                      <span class="material-symbols-outlined text-sm" aria-hidden="true">fact_check</span>{{ t('app.configureCurrentBoardVerification') }}
                    </button>
                  </div>
                </div>
              </article>
            </div>
          </section>

          <section v-if="targetsWithoutCandidate.length > 0" aria-labelledby="fuzz-target-results-title">
            <div class="flex items-center justify-between gap-3">
              <h4 id="fuzz-target-results-title" class="board-section-title">{{ t('app.fuzzTargetsWithoutCandidate') }}</h4>
              <span class="text-xs board-text-muted">{{ t('app.fuzzTargetResultsSummary', { found: run.findings.length, total: targetResults.length }) }}</span>
            </div>
            <ul class="mt-2 divide-y divide-[color:var(--border)] overflow-hidden rounded-lg border board-border-subtle">
              <li v-for="target in targetsWithoutCandidate" :key="target.specId" class="flex items-start gap-2 board-bg-elevated px-3 py-2.5">
                <span class="material-symbols-outlined mt-0.5 text-base board-text-info" aria-hidden="true">search_off</span>
                <div class="min-w-0 flex-1">
                  <p class="break-words text-xs font-semibold board-text-strong">
                    {{ target.label }}
                  </p>
                </div>
              </li>
            </ul>
          </section>

          <section aria-labelledby="fuzz-run-summary-title">
            <h4 id="fuzz-run-summary-title" class="board-section-title">{{ t('app.runSummary') }}</h4>
            <div class="mt-2 grid grid-cols-1 gap-px overflow-hidden rounded-lg border board-border-subtle board-bg-control sm:grid-cols-3">
              <div class="min-w-0 board-bg-elevated p-2 sm:p-3">
                <div class="board-form-label break-words">{{ t('app.fuzzIterations') }}</div>
                <div class="mt-1 break-words board-metric board-text-strong">{{ run.iterations }}</div>
              </div>
              <div class="min-w-0 board-bg-elevated p-2 sm:p-3">
                <div class="board-form-label break-words">{{ t('app.fuzzGeneratedPaths') }}</div>
                <div class="mt-1 break-words board-metric board-text-strong">{{ run.generatedPaths }}</div>
              </div>
              <div class="min-w-0 board-bg-elevated p-2 sm:p-3">
                <div class="board-form-label break-words">{{ t('app.fuzzElapsed') }}</div>
                <div class="mt-1 break-words board-metric board-text-strong">{{ elapsedLabel }}</div>
              </div>
            </div>
          </section>

          <section class="rounded-lg border board-border-subtle board-bg-elevated px-3 py-2" aria-labelledby="fuzz-reproduction-title">
            <div class="flex flex-col items-start gap-2 sm:flex-row sm:justify-between sm:gap-4">
              <details data-testid="fuzz-reproduction-details" class="min-w-0 w-full sm:flex-1">
                <summary id="fuzz-reproduction-title" class="board-section-title cursor-pointer py-3">{{ t('app.fuzzReproductionSettings') }}</summary>
                <p class="board-caption mt-1 leading-5 board-text-muted">{{ t('app.fuzzReproductionHint') }}</p>
                <dl class="mt-2 grid grid-cols-2 gap-x-4 gap-y-2 text-xs sm:grid-cols-3">
                  <div>
                    <dt class="board-text-muted">{{ t('app.fuzzEffectiveSeed') }}</dt>
                    <dd class="break-all font-mono font-semibold board-text-strong">{{ run.effectiveSeed }}</dd>
                  </div>
                  <div>
                    <dt class="board-text-muted">{{ t('app.fuzzMaxIterations') }}</dt>
                    <dd class="font-semibold board-text-strong">{{ run.maxIterations }}</dd>
                  </div>
                  <div>
                    <dt class="board-text-muted">{{ t('app.fuzzPathLength') }}</dt>
                    <dd class="font-semibold board-text-strong">{{ run.pathLength }}</dd>
                  </div>
                  <div>
                    <dt class="board-text-muted">{{ t('app.fuzzPopulationSize') }}</dt>
                    <dd class="font-semibold board-text-strong">{{ run.populationSize }}</dd>
                  </div>
                  <div class="col-span-2">
                    <dt class="board-text-muted">{{ t('app.fuzzTargetSpecifications') }}</dt>
                    <dd class="font-semibold board-text-strong">{{ targetScopeText }}</dd>
                  </div>
                </dl>
              </details>
              <button
                type="button"
                data-testid="reuse-fuzzing-settings"
                class="board-action-inline-subtle shrink-0"
                :disabled="actionLocked"
                @click="emit('reuseSettings')"
              >
                <span class="material-symbols-outlined text-sm" aria-hidden="true">settings_backup_restore</span>{{ t('app.fuzzReuseSettings') }}
              </button>
            </div>
          </section>

          <details data-testid="fuzz-snapshot-details" class="rounded-lg border board-border-subtle px-3 py-2">
            <summary id="fuzz-snapshot-title" class="board-section-title cursor-pointer py-3">{{ t('app.runScopeAndSnapshot') }}</summary>
            <p class="board-caption mt-1 leading-5 board-text-muted">
              {{ t('app.modelRunSnapshotSummary', {
                time: formatDate(run.modelSnapshot.capturedAt),
                devices: run.modelSnapshot.deviceCount,
                rules: run.modelSnapshot.ruleCount,
                specs: run.modelSnapshot.specificationCount,
                variables: run.modelSnapshot.environmentVariableCount,
                templates: run.modelSnapshot.deviceTemplateCount
              }) }}
            </p>
          </details>

          <details
            v-if="capabilityLimitations.length > 0"
            data-testid="fuzz-limitations-details"
            class="rounded-lg border board-border-subtle px-3 py-2 text-xs leading-5 board-text-strong"
          >
            <summary class="board-section-title cursor-pointer py-3">{{ capabilityBoundaryHeading }}</summary>
            <ul class="mt-1 list-disc space-y-1 pl-4">
              <li v-for="(limitation, index) in capabilityLimitations" :key="index">{{ limitationText(limitation) }}</li>
            </ul>
          </details>

        </template>
      </div>

      <!-- Footer: the one whole-dialog action, outside the scroll region.
           `Verify the current board` was the last child of the scrolling body, which measured 384px
           below the fold with 458px of content hidden — both themes reported it as unreachable and
           read the per-section controls as the dialog's only actions. Per-finding Replay/Verify stay
           in the list, where they belong to their row; this one applies to the whole result. -->
      <div
        v-if="run && run.findings.length === 0"
        class="iot-dialog__footer"
      >
        <button
          type="button"
          data-testid="verify-current-board-without-finding"
          class="iot-dialog-btn iot-dialog-btn--primary"
          :disabled="actionLocked"
          @click="emit('verifyCurrentBoard')"
        >
          <span class="material-symbols-outlined text-base" aria-hidden="true">fact_check</span>{{ t('app.configureCurrentBoardVerification') }}
        </button>
      </div>
    </section>
  </div>
</template>
