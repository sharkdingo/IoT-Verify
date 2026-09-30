<script setup lang="ts">
import { ref, computed, watch, nextTick, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { useModalAccessibility } from '@/composables/useModalAccessibility'
import boardApi from '@/api/board'
import { FIX_RESPONSE_INCOMPLETE_CODE } from '@/utils/fixResponse'
import { generationIssueReasonKey } from '@/utils/generationIssue'
import { localizedErrorMessage, localizedTextOrFallback } from '@/utils/userMessage'
import { requestInteractiveCancellation } from '@/utils/interactiveCancellation'
import { formatModelTokenBySource } from '@/utils/modelTokenDisplay'
import { useAuth } from '@/stores/auth'
import { specTemplateDetails } from '@/assets/config/specTemplates'
import type {
  FaultLocalizationResult,
  FaultRule,
  FixApplyResult,
  FixResult,
  FixStrategyAttempt,
  FixStrategyAttemptStatus,
  FixStrategyName,
  FixSuggestion,
  ModelTokenSource,
  ParameterAdjustment,
  ParameterTarget,
  PreferredRangeSelection
} from '@/types/fix'
import type { InteractiveOperationStage } from '@/types/task'
import { confirmDestructive, notifyBlocked, notifyError, notifySuccess } from '@/utils/feedback'

const props = defineProps<{
  visible: boolean
  traceId: number
  violatedSpecId?: string
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  'applied': [result: FixApplyResult]
  'outcome-uncertain': []
}>()

const { t, locale } = useI18n()
const { getToken } = useAuth()

const faultLoading = ref(false)
const faultLoadFailed = ref(false)
const strategyLoading = ref<FixStrategyName | null>(null)
const fixSearchElapsedSeconds = ref(0)
const fixProgressStage = ref<InteractiveOperationStage>('QUEUED')
const activeFixRequestId = ref<string | null>(null)
const activeFixTraceId = ref<number | null>(null)
const activeFixAbortController = ref<AbortController | null>(null)
const activeFixAuthToken = ref<string | null>(null)
const pendingFixCancellationId = ref<string | null>(null)
const unresolvedFixRequestId = ref<string | null>(null)
const FIX_CANCELLATION_STATUS_FAILURE_LIMIT = 30
const FIX_CANCELLATION_RETRY_DELAY_MS = 50
const FIX_RECOVERY_BACKOFF_MS = 10_000
const FIX_PROGRESS_POLL_INTERVAL_MS = 1_000
// FINISHED is published just before the authoritative POST result leaves the worker. Release is
// deliberately a two-poll handshake: one poll records `observedAt`, a later poll releases only
// once this grace has elapsed — giving the in-flight POST body a full extra interval to arrive
// (and its own terminal evidence clears tracking the instant it does).
const FIX_FINISHED_POST_GRACE_MS = FIX_PROGRESS_POLL_INTERVAL_MS
let fixSearchTimer: ReturnType<typeof setInterval> | null = null
let fixProgressRefreshInFlight = false
let fixCancellationStatusFailures = 0
let fixRecoveryRetryNotBefore = 0
let fixFinishedObservation: { requestId: string, observedAt: number } | null = null
let fixOutcomeUnknownWarningRequestId: string | null = null
type FixRequestTerminalEvidence = 'post-terminal' | 'cancel-accepted' | 'status-finished'
let lastResolvedFixRequest: {
  requestId: string
  evidence: FixRequestTerminalEvidence
} | null = null
const strategyErrors = ref<Partial<Record<FixStrategyName, string>>>({})
const strategyWarnings = ref<Partial<Record<FixStrategyName, string[]>>>({})
const fixResult = ref<FixResult | null>(null)
// Which listed alternative each strategy's Apply acts on; absent means the first, the smallest change.
// A strategy's list is only ever replaced through invalidateStrategyResult, which clears its entry.
const selectedAlternativeIndex = ref<Partial<Record<FixStrategyName, number>>>({})
const faultLocalization = ref<FaultLocalizationResult | null>(null)
const faultRules = ref<FaultRule[]>([])
const selectedStrategy = ref<FixStrategyName>('parameter')
const applyingFix = ref(false)
const parameterTargetCatalog = ref<ParameterTarget[]>([])
const lastParameterRequestFingerprint = ref<string | null>(null)
// 记录本次 /fix 用的参数偏好选择，apply 时原样回传，保证后端重算复现同一建议。
const lastPreferredRangeSelections = ref<PreferredRangeSelection[] | undefined>(undefined)

// One row per adjustable threshold. A row left at the template bounds expresses no preference and is
// not sent; only a narrowed row becomes a preferred-range selection.
type PreferredRangeRow = {
  targetId: string
  lower: number | null
  upper: number | null
}

const strategyOrder: FixStrategyName[] = ['parameter', 'condition', 'remove']

const fixProgressStageLabel = computed(() => t(`app.fixProgressStage_${fixProgressStage.value}`))

const waitForFixCancellationRetry = () => new Promise<void>(resolve => {
  setTimeout(resolve, FIX_CANCELLATION_RETRY_DELAY_MS)
})

const cancelOwnedFixRequest = (
  requestId: string,
  authToken: string | null = activeFixAuthToken.value
): Promise<boolean> => authToken
  ? boardApi.cancelFixRequest(requestId, authToken)
  : Promise.reject(new Error('Automatic-fix owner credential is unavailable'))

const readOwnedFixRequestStatus = (
  requestId: string,
  authToken: string | null = activeFixAuthToken.value
) => authToken
  ? boardApi.getFixRequestStatus(requestId, authToken)
  : Promise.reject(new Error('Automatic-fix owner credential is unavailable'))

const refreshFixProgress = async (requestId: string) => {
  if (fixProgressRefreshInFlight || Date.now() < fixRecoveryRetryNotBefore) return
  fixProgressRefreshInFlight = true
  try {
    if (activeFixRequestId.value === requestId && pendingFixCancellationId.value === requestId) {
      try {
        if (await cancelOwnedFixRequest(requestId)) {
          clearActiveFixTracking(requestId, 'cancel-accepted')
          return
        }
      } catch {
        // Keep polling: the request can become cancellable after POST registration.
      }
    }
    const status = await readOwnedFixRequestStatus(requestId)
    if (activeFixRequestId.value === requestId) {
      if (pendingFixCancellationId.value === requestId) fixCancellationStatusFailures = 0
      fixProgressStage.value = status.stage
      if (status.state === 'FINISHED') {
        const recoveringUnknownOutcome = pendingFixCancellationId.value === requestId
          || unresolvedFixRequestId.value === requestId
          || activeFixAbortController.value?.signal.aborted === true
        if (recoveringUnknownOutcome) {
          clearActiveFixTracking(requestId, 'status-finished')
        } else if (fixFinishedObservation?.requestId !== requestId) {
          // The backend publishes FINISHED immediately before the POST result leaves its worker.
          // Give that authoritative response one polling interval to arrive before treating it as hung.
          fixFinishedObservation = { requestId, observedAt: Date.now() }
        } else if (Date.now() - fixFinishedObservation.observedAt >= FIX_FINISHED_POST_GRACE_MS) {
          clearActiveFixTracking(requestId, 'status-finished')
        }
      } else if (fixFinishedObservation?.requestId === requestId) {
        fixFinishedObservation = null
      }
    }
  } catch {
    if (activeFixRequestId.value === requestId && pendingFixCancellationId.value === requestId) {
      fixCancellationStatusFailures += 1
      if (fixCancellationStatusFailures >= FIX_CANCELLATION_STATUS_FAILURE_LIMIT) {
        backOffFixCancellationRecovery(requestId)
      }
    }
    // Registration and completion can race with this read; only terminal evidence releases tracking.
  } finally {
    fixProgressRefreshInFlight = false
  }
}

const strategyIcons: Record<FixStrategyName, string> = {
  parameter: 'tune',
  condition: 'checklist',
  remove: 'delete_forever'
}

const strategyLabels = computed<Record<FixStrategyName, string>>(() => ({
  parameter: t('app.fixStrategyParameter'),
  condition: t('app.fixStrategyCondition'),
  remove: t('app.fixStrategyRemove')
}))

const strategyDescriptions = computed<Record<FixStrategyName, string>>(() => ({
  parameter: t('app.fixStrategyParameterDesc'),
  condition: t('app.fixStrategyConditionDesc'),
  remove: t('app.fixStrategyRemoveDesc')
}))

const strategyOptions = computed(() => strategyOrder.map(value => ({
  value,
  label: strategyLabels.value[value],
  icon: strategyIcons[value]
})))

const fixResponseErrorMessage = (error: any, fallback: string) => {
  if (error?.response?.status === 429
    && error?.response?.data?.data?.reasonCode === 'USER_FORMAL_OPERATION_BUSY') {
    return t('app.formalOperationBusy')
  }
  return error?.code === FIX_RESPONSE_INCOMPLETE_CODE
    ? t('app.fixResponseIncomplete')
    : localizedErrorMessage(error, fallback, locale.value)
}

const fixApplyErrorMessage = (error: any) => {
  const raw = error?.response?.data?.message || error?.message
  if (typeof raw === 'string'
    && (/rules\[\d+\]\.conditions\[\d+\]\.deviceName/i.test(raw)
      || /unknown (?:condition|command|content) device/i.test(raw))) {
    return t('app.fixDeviceReferenceUnavailable')
  }
  return localizedTextOrFallback(raw, t('app.failedToApplyFix'), locale.value)
}

const displayedFixWarnings = computed(() => Array.from(new Set([
  ...(faultLocalization.value?.warnings || []),
  ...(fixResult.value?.warnings || [])
])))

const displayedSourceGenerationIssues = computed(() =>
  fixResult.value?.sourceGenerationIssues?.length
    ? fixResult.value.sourceGenerationIssues
    : faultLocalization.value?.sourceGenerationIssues || [])

const localizedFixLimitations = computed(() => {
  const messages: string[] = []
  const source = fixResult.value || faultLocalization.value
  if (source && source.sourceModelComplete === false) {
    messages.push(t('app.fixSourceModelIncompleteLimitation', {
      rules: source.sourceDisabledRuleCount,
      specs: source.sourceSkippedSpecCount
    }))
  }
  // While an option is listed, the footer states this beside the Apply it blocks (`applyBlockedReason`);
  // saying it here as well would give one fact two homes.
  if (currentSuggestion.value) return Array.from(new Set(messages))
  if (fixResult.value?.templateSnapshotComparison === 'CHANGED') {
    messages.push(t('app.fixTemplateSnapshotChangedLimitation'))
  } else if (fixResult.value?.templateSnapshotComparison === 'UNAVAILABLE') {
    // Only UNAVAILABLE means a comparison was attempted and failed, so only it can be retried.
    // NOT_CHECKED is deliberately silent: the backend skips the comparison entirely when it has
    // already refused to search an incomplete source model, and that blocker is the message above.
    // Naming an unattempted comparison would invite a retry that cannot change the outcome.
    messages.push(t('app.fixTemplateSnapshotUnavailableLimitation'))
  }
  return Array.from(new Set(messages))
})

const currentStrategyLoading = computed(() => strategyLoading.value === selectedStrategy.value)
const anotherStrategyLoading = computed(() => strategyLoading.value !== null && !currentStrategyLoading.value)
const hasAttemptResults = computed(() => (fixResult.value?.strategyAttempts?.length ?? 0) > 0)

// Static while a search runs: the loading panel in the body owns progress, and the header icon spins.
const headerStatus = computed(() => {
  if (verifiedCount.value > 0) {
    return t('app.verifiedSolutionsCount', { count: verifiedCount.value })
  }
  if (fixResult.value?.strategyAttempts?.length) {
    return noAttemptLeftToChangeTheOutcome.value ? t('app.noVerifiedOptions') : t('app.noVerifiedSolutionsYet')
  }
  return t('app.selectFixStrategyPrompt')
})

const preferredRangeRows = ref<PreferredRangeRow[]>([])

const formatModelToken = (value: unknown, source: ModelTokenSource) => {
  return formatModelTokenBySource(source, value, t)
}

/**
 * Words here, glyphs on the canvas and in the inspector — and that is not a divergence.
 *
 * An audit read three renderings of one operator as a defect. Two of them were: `SystemInspector` and
 * `CanvasBoard` keyed their maps on `EQ`/`GTE`, which nothing persists, so both fell through to the raw `>=`
 * while disagreeing on `in`. Those now share one glyph set.
 *
 * This one is different, because the output goes into a **sentence**: `parameterTargetFallback` reads
 * "temperature greater or equal 30", and `≥` mid-sentence reads worse than the words. The rule is the audience,
 * not the string — a canvas edge label has no room for a word, and prose has no use for a glyph.
 */
const formatRelation = (value: unknown) => {
  const raw = String(value ?? '').trim()
  const normalized = raw.toLowerCase().replace(/_/g, ' ')
  const labels: Record<string, string> = {
    '=': t('app.relationEquals'),
    '==': t('app.relationEquals'),
    '!=': t('app.relationNotEquals'),
    '>': t('app.relationGreater'),
    '<': t('app.relationLess'),
    '>=': t('app.relationGreaterEqual'),
    '<=': t('app.relationLessEqual'),
    in: t('app.relationIn'),
    'not in': t('app.relationNotIn')
  }
  return labels[normalized] || raw
}

const formatPreferredRangeTarget = (adjustment: ParameterTarget) => t('app.preferredRangeTargetLabel', {
  description: t('app.parameterTargetFallback', {
    attribute: formatModelToken(adjustment.attribute, adjustment.modelTokenSource),
    relation: formatRelation(adjustment.relation),
    original: formatModelToken(adjustment.originalValue, adjustment.modelTokenSource)
  })
})

const parameterAdjustmentByTargetId = computed(() => {
  const byTargetId = new Map<string, ParameterTarget>()
  parameterTargetCatalog.value.forEach(adjustment => {
    if (adjustment.targetId) byTargetId.set(adjustment.targetId, adjustment)
  })
  return byTargetId
})

/**
 * Rows paired with their target and input problem, in catalog order. Rows are only ever created from
 * the catalog. The row renders its problem beside the offending input.
 */
const preferredRangeRowViews = computed(() => preferredRangeRows.value.flatMap(row => {
  const target = parameterAdjustmentByTargetId.value.get(row.targetId)
  return target ? [{ row, target, issue: preferredRangeIssueOf(row, target) }] : []
}))

const rowNarrowsTarget = (row: PreferredRangeRow, target: ParameterTarget) =>
  row.lower !== target.lowerBound || row.upper !== target.upperBound

const narrowedPreferredRangeRows = computed(() => preferredRangeRowViews.value
  .filter(({ row, target }) => rowNarrowsTarget(row, target))
  .map(({ row }) => row))

/**
 * Keep one row per catalog target. A re-run returns the catalog again; bounds the user already typed for
 * a surviving target are kept, a new target starts at its template bounds, and a vanished target's row
 * goes (the backend reports its selection under unusedPreferredRangeSelections).
 */
const syncPreferredRangeRows = () => {
  const existing = new Map(preferredRangeRows.value.map(row => [row.targetId, row]))
  preferredRangeRows.value = parameterTargetCatalog.value
    .filter(target => target.targetId)
    .map(target => existing.get(target.targetId) ?? {
      targetId: target.targetId,
      lower: target.lowerBound,
      upper: target.upperBound
    })
}

const activePreferredRangeCount = computed(() => {
  return lastPreferredRangeSelections.value?.length ?? 0
})

// Only narrowed rows are part of the request, so only they can make a returned suggestion stale.
const parameterPreferenceFingerprint = () => JSON.stringify(narrowedPreferredRangeRows.value.map(row => ({
  targetId: row.targetId,
  lower: row.lower,
  upper: row.upper
})))

// The on-screen ranges differ from the ones the last parameter search ran with.
const parameterRangesEditedSinceLastRequest = computed(() =>
  lastParameterRequestFingerprint.value !== parameterPreferenceFingerprint())

const parameterPreferencesChanged = computed(() => {
  if (!fixResult.value?.suggestions.some(item => item.strategy === 'parameter')) return false
  return parameterRangesEditedSinceLastRequest.value
})

const suggestionIsCurrent = (suggestion: FixSuggestion) =>
  suggestion.strategy !== 'parameter' || !parameterPreferencesChanged.value

const templateSnapshotAllowsApply = computed(() =>
  fixResult.value?.templateSnapshotComparison === 'UNCHANGED')

/**
 * Why the listed option cannot be applied, or '' when nothing blocks it or no option is listed.
 *
 * A disabled submit has to say why inline, and both the disabled state and the `aria-describedby`
 * target derive from this one value so they cannot disagree. Rendered only beside an Apply button:
 * with no option there is nothing to apply, and the Limitations list owns the snapshot fact instead.
 * NOT_CHECKED only accompanies a refused incomplete-source search, which lists no option, so it
 * never reaches here. Excludes the apply request itself: the button already renders "Applying…"
 * with a spinner, and repeating that as an explanation would be duplicate feedback.
 */
const applyBlockedReason = computed(() => {
  if (!currentSuggestion.value) return ''
  if (fixResult.value?.templateSnapshotComparison === 'CHANGED') {
    return t('app.fixTemplateSnapshotChangedLimitation')
  }
  if (!templateSnapshotAllowsApply.value) return t('app.fixTemplateSnapshotUnavailableLimitation')
  // Apply and a search are one request slot; a listed option is never the one being searched for,
  // because starting a search withdraws that strategy's options.
  if (strategyLoading.value !== null && !applyingFix.value) {
    return t('app.fixApplyWaitsForSearch', { strategy: strategyLabels.value[strategyLoading.value] })
  }
  return ''
})

/** Apply is unavailable while a precondition blocks it or while the apply request is in flight. */
const applyDisabled = computed(() => Boolean(applyBlockedReason.value) || applyingFix.value)

const isBlank = (value: unknown) => value === null || value === undefined || value === ''

type PreferredRangeIssue = {
  message: string
  lowerInvalid: boolean
  upperInvalid: boolean
}

// Only a narrowed row is sent, so only a narrowed row can block the search.
const preferredRangeIssueOf = (row: PreferredRangeRow, target: ParameterTarget): PreferredRangeIssue | null => {
  if (!rowNarrowsTarget(row, target)) return null
  if (isBlank(row.lower) || isBlank(row.upper)) {
    return {
      message: t('app.preferredRangeCompleteFields'),
      lowerInvalid: isBlank(row.lower),
      upperInvalid: isBlank(row.upper)
    }
  }
  const lowerIsInteger = Number.isInteger(Number(row.lower))
  const upperIsInteger = Number.isInteger(Number(row.upper))
  if (!lowerIsInteger || !upperIsInteger) {
    return {
      message: t('app.preferredRangeIntegerBounds'),
      lowerInvalid: !lowerIsInteger,
      upperInvalid: !upperIsInteger
    }
  }
  if (Number(row.lower) > Number(row.upper)) {
    return { message: t('app.preferredRangeLowerBeforeUpper'), lowerInvalid: true, upperInvalid: true }
  }
  return null
}

const preferredRangeHasIssue = computed(() => preferredRangeRowViews.value.some(view => view.issue))

/**
 * The ids of the row messages that block a parameter search, or '' when the ranges can be sent.
 * The messages sit beside the inputs they describe; the disabled Try points at them rather than
 * repeating them in the footer.
 */
const rangeBlockedReasonIds = computed(() => selectedStrategy.value === 'parameter'
  ? preferredRangeRowViews.value
    .filter(view => view.issue)
    .map(view => `fix-range-issue-${view.row.targetId}`)
    .join(' ')
  : '')

// Callers check `preferredRangeHasIssue` first, so every narrowed row here holds two ordered integers.
const buildPreferredRangeSelections = (): PreferredRangeSelection[] | undefined => {
  const selections = narrowedPreferredRangeRows.value.map(row => ({
    targetId: row.targetId,
    lower: Number(row.lower),
    upper: Number(row.upper)
  }))
  return selections.length > 0 ? selections : undefined
}

// The backend only registers targets whose original value is an in-bounds integer.
const originalValueOf = (target: ParameterTarget) => Number(target.originalValue)

const rowKeepsOriginal = (row: PreferredRangeRow, target: ParameterTarget) =>
  row.lower === originalValueOf(target) && row.upper === originalValueOf(target)

/** Pin a threshold at its current value for the next search, or release it back to the full range. */
const toggleKeepOriginal = (row: PreferredRangeRow, target: ParameterTarget) => {
  const keep = !rowKeepsOriginal(row, target)
  row.lower = keep ? originalValueOf(target) : target.lowerBound
  row.upper = keep ? originalValueOf(target) : target.upperBound
}

// Fetch fault localization
const fetchFaultRules = async () => {
  if (!props.traceId) return

  const traceId = props.traceId
  const requestVersion = dialogRequestVersion
  faultLoading.value = true
  faultLoadFailed.value = false
  try {
    const result = await boardApi.getFaultRules(traceId)
    if (requestVersion !== dialogRequestVersion || traceId !== props.traceId || !props.visible) return
    faultLocalization.value = result
    faultRules.value = result.faultRules
  } catch (error: any) {
    if (requestVersion !== dialogRequestVersion || traceId !== props.traceId || !props.visible) return
    console.error('Failed to fetch fault rules:', error)
    faultLoadFailed.value = true
    notifyError(fixResponseErrorMessage(error, t('app.failedToLoadFaultLocalization')))
  } finally {
    if (requestVersion === dialogRequestVersion && traceId === props.traceId) {
      faultLoading.value = false
    }
  }
}

// Fetch fix suggestions
const mergeFixResult = (current: FixResult | null, incoming: FixResult): FixResult => {
  if (!current) return incoming

  const incomingStrategies = new Set([
    ...(incoming.strategyAttempts || []).map(attempt => attempt.strategy),
    ...(incoming.suggestions || []).map(suggestion => suggestion.strategy)
  ])
  const suggestions = [
    ...(current.suggestions || []).filter(suggestion => !incomingStrategies.has(suggestion.strategy)),
    ...(incoming.suggestions || [])
  ]
  const strategyAttempts = [
    ...(current.strategyAttempts || []).filter(attempt => !incomingStrategies.has(attempt.strategy)),
    ...(incoming.strategyAttempts || [])
  ]

  return {
    ...current,
    ...incoming,
    faultRules: incoming.faultRules?.length ? incoming.faultRules : current.faultRules,
    suggestions,
    strategyAttempts,
    fixable: suggestions.length > 0,
    warnings: Array.from(new Set(strategyOrder.flatMap(strategy => strategyWarnings.value[strategy] || []))),
    unusedPreferredRangeSelections: incomingStrategies.has('parameter')
      ? incoming.unusedPreferredRangeSelections
      : current.unusedPreferredRangeSelections,
    parameterTargets: incomingStrategies.has('parameter')
      ? incoming.parameterTargets
      : current.parameterTargets
  }
}

const invalidateStrategyResult = (strategy: FixStrategyName) => {
  // A new search lists new alternatives; an index into the old list would pick an arbitrary one.
  delete selectedAlternativeIndex.value[strategy]
  if (!fixResult.value) return
  const suggestions = fixResult.value.suggestions.filter(item => item.strategy !== strategy)
  fixResult.value = {
    ...fixResult.value,
    suggestions,
    strategyAttempts: fixResult.value.strategyAttempts.filter(item => item.strategy !== strategy),
    fixable: suggestions.length > 0,
    warnings: Array.from(new Set(strategyOrder.flatMap(item => strategyWarnings.value[item] || []))),
    unusedPreferredRangeSelections: strategy === 'parameter'
      ? []
      : fixResult.value.unusedPreferredRangeSelections
  }
}

// The selected strategy shows its failure inline, so a toast would state it twice; one the user has
// switched away from would otherwise fail unseen.
const reportStrategyError = (strategy: FixStrategyName, message: string) => {
  strategyErrors.value[strategy] = message
  if (selectedStrategy.value !== strategy) notifyError(message)
}

const fetchFixSuggestions = async (strategy: FixStrategyName = selectedStrategy.value) => {
  if (!props.traceId || strategyLoading.value || activeFixRequestId.value || unresolvedFixRequestId.value) return

  const authToken = getToken()
  if (!authToken) {
    reportStrategyError(strategy, t('app.fixAuthenticationRequired'))
    return
  }
  // Try is disabled while a row has an issue, and its row states why; never send unvalidated bounds.
  if (strategy === 'parameter' && preferredRangeHasIssue.value) return

  const traceId = props.traceId
  const requestVersion = dialogRequestVersion
  const requestFingerprint = strategy === 'parameter' ? parameterPreferenceFingerprint() : null
  const preferredRangeSelections = strategy === 'parameter'
    ? buildPreferredRangeSelections()
    : undefined
  delete strategyWarnings.value[strategy]
  invalidateStrategyResult(strategy)
  strategyLoading.value = strategy
  fixSearchElapsedSeconds.value = 0
  fixProgressStage.value = 'QUEUED'
  const requestId = crypto.randomUUID()
  const controller = new AbortController()
  const startedAt = Date.now()
  activeFixRequestId.value = requestId
  activeFixTraceId.value = traceId
  activeFixAbortController.value = controller
  activeFixAuthToken.value = authToken
  pendingFixCancellationId.value = null
  unresolvedFixRequestId.value = null
  fixCancellationStatusFailures = 0
  fixRecoveryRetryNotBefore = 0
  fixFinishedObservation = null
  fixOutcomeUnknownWarningRequestId = null
  lastResolvedFixRequest = null
  if (fixSearchTimer) clearInterval(fixSearchTimer)
  const requestProgressTimer = setInterval(() => {
    fixSearchElapsedSeconds.value = Math.floor((Date.now() - startedAt) / 1000)
    void refreshFixProgress(requestId)
  }, FIX_PROGRESS_POLL_INTERVAL_MS)
  fixSearchTimer = requestProgressTimer
  delete strategyErrors.value[strategy]
  let postSettledWithTerminalEvidence = false
  try {
    if (strategy === 'parameter') {
      lastPreferredRangeSelections.value = preferredRangeSelections
    }
    const result = await boardApi.fixTrace(
      traceId,
      { strategies: [strategy], preferredRangeSelections },
      { authToken, requestId, signal: controller.signal }
    )
    postSettledWithTerminalEvidence = true
    if (requestVersion !== dialogRequestVersion || traceId !== props.traceId || !props.visible) return
    strategyWarnings.value[strategy] = result.warnings || []
    if (strategy === 'parameter') {
      lastParameterRequestFingerprint.value = requestFingerprint
      parameterTargetCatalog.value = result.parameterTargets || []
      syncPreferredRangeRows()
    }
    fixResult.value = mergeFixResult(fixResult.value, result)
    if (result.faultRules?.length) faultRules.value = result.faultRules
  } catch (error: any) {
    postSettledWithTerminalEvidence = Boolean(error?.response)
      || error?.code === FIX_RESPONSE_INCOMPLETE_CODE
    if (!postSettledWithTerminalEvidence && activeFixRequestId.value === requestId) {
      beginUnknownFixRecovery(requestId)
    }
    if (requestVersion !== dialogRequestVersion || traceId !== props.traceId || !props.visible) return
    if (error?.name === 'CanceledError' || error?.code === 'ERR_CANCELED') return
    console.error('Failed to fetch fix suggestions:', error)
    reportStrategyError(strategy, fixResponseErrorMessage(error, t('app.failedToLoadFixSuggestions')))
  } finally {
    if (postSettledWithTerminalEvidence && activeFixRequestId.value === requestId) {
      clearActiveFixTracking(requestId, 'post-terminal')
    } else if (activeFixRequestId.value === requestId) {
      beginUnknownFixRecovery(requestId)
    }
    if (activeFixRequestId.value !== requestId) {
      if (activeFixRequestId.value === null
        && requestVersion === dialogRequestVersion && traceId === props.traceId
        && strategyLoading.value === strategy) {
        strategyLoading.value = null
      }
      if (activeFixAbortController.value === controller) activeFixAbortController.value = null
      if (fixSearchTimer === requestProgressTimer) {
        clearInterval(requestProgressTimer)
        fixSearchTimer = null
      }
    }
  }
}

/**
 * Why the search ranges are read-only, or '' when they are editable.
 *
 * One computed drives both the disabled state and the visible reason, so the controls and the message
 * cannot disagree. Ranges edited while a search runs would not match the request that search answers,
 * and a Reset during a search used to wipe the user's typed bounds without re-running anything.
 */
const preferenceActionBlockedReason = computed(() =>
  strategyLoading.value || activeFixRequestId.value || unresolvedFixRequestId.value
    ? t('app.fixSearchInProgress')
    : '')

/** Back to the template bounds. The next "retry" searches without preferences; nothing runs here. */
const resetPreferenceRows = () => {
  if (preferenceActionBlockedReason.value) return
  preferredRangeRows.value = []
  syncPreferredRangeRows()
}

let dialogRequestVersion = 0

// Handle dialog open
const handleOpen = () => {
  dialogRequestVersion += 1
  if (activeFixRequestId.value || unresolvedFixRequestId.value) {
    if (activeFixTraceId.value !== props.traceId) {
      notifyBlocked(t('app.fixTraceSwitchBlockedByActiveSearch'))
      emit('update:visible', false)
      return
    }
    void fetchFaultRules()
    return
  }
  fixResult.value = null
  faultLocalization.value = null
  faultRules.value = []
  faultLoadFailed.value = false
  strategyLoading.value = null
  strategyErrors.value = {}
  strategyWarnings.value = {}
  preferredRangeRows.value = []
  parameterTargetCatalog.value = []
  lastParameterRequestFingerprint.value = null
  lastPreferredRangeSelections.value = undefined
  selectedAlternativeIndex.value = {}
  selectedStrategy.value = 'parameter'
  void fetchFaultRules()
}

// Switch strategy
const switchStrategy = (strategy: FixStrategyName) => {
  selectedStrategy.value = strategy
}

const trySelectedStrategy = () => fetchFixSuggestions(selectedStrategy.value)

// Apply the exact signed suggestion after the server checks the complete formal-model snapshot.
const applyFix = async (suggestion: FixSuggestion) => {
  if (!props.traceId) return
  if (!templateSnapshotAllowsApply.value) {
    notifyBlocked(fixResult.value?.templateSnapshotComparison === 'CHANGED'
      ? t('app.fixTemplateSnapshotChangedLimitation')
      : t('app.fixTemplateSnapshotUnavailableLimitation'))
    return
  }
  if (suggestion.strategy === 'remove' && !await confirmDestructive({
    title: t('app.removeRulesFixTitle'),
    message: t('app.confirmRemoveRulesFix', { count: suggestion.removedRuleDescriptions?.length || 0 }),
    confirmText: t('app.removeRulesAndApply')
  })) return
  applyingFix.value = true
  try {
    const result = await boardApi.applyFix(
      props.traceId,
      suggestion,
      suggestion.strategy === 'parameter' ? lastPreferredRangeSelections.value : undefined
    )
    if (!result.applied || !result.verificationEvidenceReused) {
      notifyBlocked(localizedTextOrFallback(result.message, t('app.failedToApplyFix'), locale.value))
      return
    }
    emit('applied', result)
    emit('update:visible', false)
    // The parent-controlled visibility prop releases this dialog's modal depth on render.
    await nextTick()
    notifySuccess(t('app.fixAppliedWithSignedEvidence'))
  } catch (error: any) {
    console.error('Failed to apply fix:', error)
    const status = Number(error?.response?.status)
    const reasonCode = error?.response?.data?.data?.reasonCode
    const definitiveRejection = Number.isFinite(status)
      && ((status >= 400 && status < 500)
        || (status === 503 && reasonCode === 'FIX_APPLY_PREFLIGHT_UNAVAILABLE'))
    if (!definitiveRejection) {
      emit('outcome-uncertain')
      emit('update:visible', false)
      return
    }
    // Drift, stale targets, and service-unavailable preflight failures occur before the write.
    notifyError(fixApplyErrorMessage(error))
  } finally {
    applyingFix.value = false
  }
}

// The selected strategy's verified alternatives, smallest change first as the server orders them.
const currentAlternatives = computed(() =>
  (fixResult.value?.suggestions ?? []).filter(suggestion =>
    suggestion.strategy === selectedStrategy.value && suggestionIsCurrent(suggestion)))

const currentAlternativeIndex = computed(() => selectedAlternativeIndex.value[selectedStrategy.value] ?? 0)

/** The alternative Apply acts on. */
const currentSuggestion = computed(() => currentAlternatives.value[currentAlternativeIndex.value] ?? null)

const selectAlternative = (index: number) => {
  if (applyingFix.value) return
  selectedAlternativeIndex.value[selectedStrategy.value] = index
}

const preexistingViolationLabel = (templateId: string) => {
  const detail = specTemplateDetails.find(template => template.id === templateId)
  return detail?.labelKey ? t(detail.labelKey) : detail?.label || templateId
}

/**
 * Skips the backend decides before choosing a strategy, so every strategy of the trace gets the same
 * one: no fired rule can influence the violated property, or the source model is incomplete. Either
 * outcome settles the whole dialog, not just the strategy that happened to be asked.
 */
const DIALOG_WIDE_SKIP_STATUSES = new Set<FixStrategyAttemptStatus>([
  'SKIPPED_NO_FAULT_RULES',
  'SKIPPED_INCOMPLETE_SOURCE_MODEL'
])

const dialogWideSkipAttempt = computed(() =>
  fixResult.value?.strategyAttempts?.find(attempt => DIALOG_WIDE_SKIP_STATUSES.has(attempt.status)) ?? null)

// A strategy not asked yet shows the dialog-wide skip too: asking it would only return the same skip.
const currentStrategyAttempt = computed(() =>
  fixResult.value?.strategyAttempts?.find(attempt => attempt.strategy === selectedStrategy.value)
    ?? dialogWideSkipAttempt.value
)

// A choice exists only with two or more options; a single one is shown without selection chrome.
const hasAlternatives = computed(() => currentAlternatives.value.length > 1)

// Only an explicit false means the listing was cut short; the validator guarantees a boolean on VERIFIED.
const alternativesIncomplete = computed(() => currentStrategyAttempt.value?.alternativesComplete === false)

const localizedFaultLocalizationSummary = computed(() => {
  if (!faultLocalization.value) return ''
  return faultRules.value.length > 0
    ? t('app.faultLocalizationScopeCaveat')
    : t('app.faultLocalizationNoRuleCaveat')
})

const strategyAttemptReasonLabel = (status: FixStrategyAttemptStatus) => t(`app.fixAttemptReason.${status}`)

const parameterAdjustmentMakesRuleUnreachable = (adjustment: ParameterAdjustment) => {
  const relation = adjustment.relation.trim().toLowerCase()
  const newValue = Number(adjustment.newValue)
  if (!Number.isSafeInteger(newValue)) return false
  return (['>', 'gt'].includes(relation) && newValue === adjustment.upperBound)
    || (['<', 'lt'].includes(relation) && newValue === adjustment.lowerBound)
}

/**
 * Outcomes the same request reproduces exactly. The fix runs on the trace's frozen snapshot, so
 * offering a retry for these would only repeat the answer; parameter ranges are the one input
 * the user can change, which `currentAttemptSettled` accounts for.
 */
const SETTLED_ATTEMPT_STATUSES = new Set<FixStrategyAttemptStatus>([
  'NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE',
  'ALL_CANDIDATES_REJECTED',
  'SEARCH_BUDGET_EXHAUSTED',
  'SKIPPED_NO_SPEC',
  'SKIPPED_NO_PARAMETERIZABLE_VALUES',
  'SKIPPED_NO_FAULT_RULES',
  'SKIPPED_UNSUPPORTED',
  'SKIPPED_INCOMPLETE_SOURCE_MODEL'
])

// The search settled every candidate in its space: a proof that this strategy cannot repair it.
const PROOF_ATTEMPT_STATUSES = new Set<FixStrategyAttemptStatus>([
  'NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE',
  'ALL_CANDIDATES_REJECTED'
])

// Only reachable strategy/status pairs; any other pair reads the generic `fixAttemptReason` text.
// Removal never pins a counterexample step, so it cannot report NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE.
const OUTCOME_DETAIL_KEYS: Partial<Record<FixStrategyAttemptStatus, Partial<Record<FixStrategyName, string>>>> = {
  NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE: {
    parameter: 'app.fixParameterNoCandidateDetail',
    condition: 'app.fixConditionNoCandidateDetail'
  },
  ALL_CANDIDATES_REJECTED: {
    parameter: 'app.fixParameterAllRejectedDetail',
    condition: 'app.fixConditionAllRejectedDetail',
    remove: 'app.fixRemoveAllRejectedDetail'
  },
  SEARCH_BUDGET_EXHAUSTED: {
    parameter: 'app.fixParameterBudgetExhaustedDetail'
  }
}

// A parameter proof covers only the ranges it searched; a narrowed one says nothing outside them.
const attemptSearchedNarrowedRanges = (attempt: FixStrategyAttempt) =>
  attempt.strategy === 'parameter' && activePreferredRangeCount.value > 0

const attemptIsProof = (attempt: FixStrategyAttempt | null) =>
  Boolean(attempt && PROOF_ATTEMPT_STATUSES.has(attempt.status))

// A completed strategy with no verified proposal is different from a strategy
// that was never run. Keep the empty state explicit so users do not read it as
// a loading or transport failure.
// The title is the verdict; the detail below carries its consequence and what to do next.
const strategyAttemptOutcomeTitle = (attempt: FixStrategyAttempt) => {
  if (attemptIsProof(attempt)) {
    return attemptSearchedNarrowedRanges(attempt)
      ? t('app.fixParameterCannotRepairInRangesTitle')
      : t('app.fixStrategyCannotRepairTitle', { strategy: strategyLabels.value[attempt.strategy] })
  }
  if (attempt.status === 'INCONCLUSIVE') return t('app.fixStrategyInconclusiveTitle')
  if (attempt.status === 'FAILED_MODEL_GENERATION') return t('app.fixStrategyGenerationFailedTitle')
  if (attempt.status === 'FAILED_SOLVER_EXECUTION') return t('app.fixStrategySolverFailedTitle')
  if (attempt.status === 'SEARCH_BUDGET_EXHAUSTED') return t('app.fixStrategyBudgetExhaustedTitle')
  if (attempt.status === 'TIMED_OUT') return t('app.fixStrategyTimedOutTitle')
  // SKIPPED_TIMEOUT included: the time limit ran out before this strategy started, so it never ran.
  if (attempt.status.startsWith('SKIPPED_')) return t('app.fixStrategyNotRunTitle')
  // VERIFIED: the option list renders instead of this outcome, since a verified attempt always carries one.
  return t('app.verifiedSolution')
}

const strategyAttemptOutcomeDetail = (attempt: FixStrategyAttempt) => {
  const key = OUTCOME_DETAIL_KEYS[attempt.status]?.[attempt.strategy]
  return key ? t(key) : strategyAttemptReasonLabel(attempt.status)
}

/** Why a proof is final, or what a narrowed one leaves open. Empty for outcomes that are not proofs. */
const strategyAttemptOutcomeNote = (attempt: FixStrategyAttempt) => {
  if (!attemptIsProof(attempt)) return ''
  return attemptSearchedNarrowedRanges(attempt)
    ? t('app.fixProofNarrowedRangesNote')
    : t('app.fixProofCompleteNote')
}

// Retrying a settled outcome repeats it, unless the user changed the parameter ranges it was searched with.
const currentAttemptSettled = computed(() => {
  const attempt = currentStrategyAttempt.value
  if (!attempt || !SETTLED_ATTEMPT_STATUSES.has(attempt.status)) return false
  return !(attempt.strategy === 'parameter' && parameterRangesEditedSinceLastRequest.value)
})

// After a settled parameter search over the full template ranges, no narrower range can find more.
const parameterRangesSettledOverTemplate = computed(() => {
  const attempt = currentStrategyAttempt.value
  return attempt?.strategy === 'parameter'
    && attemptIsProof(attempt)
    && !attemptSearchedNarrowedRanges(attempt)
})

// A strategy with neither a result nor an error has not been tried in this dialog yet. After a
// dialog-wide skip none is worth offering: it would return the same skip.
const nextUntriedStrategy = computed(() => dialogWideSkipAttempt.value ? null : strategyOrder.find(strategy =>
  strategy !== selectedStrategy.value
  && !fixResult.value?.strategyAttempts?.some(attempt => attempt.strategy === strategy)
  && !strategyErrors.value[strategy]) ?? null)

// Skips that are facts about the frozen snapshot, as final as a proof: the strategy has nothing to
// search here, so no retry and no range can change the answer.
const INAPPLICABLE_ATTEMPT_STATUSES = new Set<FixStrategyAttemptStatus>([
  'SKIPPED_NO_PARAMETERIZABLE_VALUES',
  'SKIPPED_NO_SPEC',
  'SKIPPED_UNSUPPORTED'
])

// Nothing left in this dialog could turn up an option: a dialog-wide skip, or every strategy either
// proved it cannot repair the violation over its whole search space or does not apply to this model.
// Budget and timeout outcomes do not count; they say how far a search got, not that nothing exists.
const noAttemptLeftToChangeTheOutcome = computed(() => Boolean(dialogWideSkipAttempt.value)
  || strategyOrder.every(strategy => {
    const attempt = fixResult.value?.strategyAttempts?.find(item => item.strategy === strategy)
    if (!attempt) return false
    if (INAPPLICABLE_ATTEMPT_STATUSES.has(attempt.status)) return true
    return attemptIsProof(attempt) && !attemptSearchedNarrowedRanges(attempt)
  }))

const tryNextStrategy = () => {
  const strategy = nextUntriedStrategy.value
  if (!strategy) return
  switchStrategy(strategy)
  void fetchFixSuggestions(strategy)
}

// Verified suggestions still valid for the current search ranges, across every strategy.
const verifiedCount = computed(() =>
  (fixResult.value?.suggestions ?? []).filter(suggestionIsCurrent).length)

/**
 * The selected strategy's search state for the polite live region: the headline of whichever panel
 * the body shows, in the body's order. A search can run for minutes, and neither the loading panel
 * nor the outcome that replaces it is itself a live region, so without this a screen-reader user
 * hears neither.
 */
const searchAnnouncement = computed(() => {
  if (currentStrategyLoading.value) {
    return t('app.tryingFixStrategy', { strategy: strategyLabels.value[selectedStrategy.value] })
  }
  if (currentSuggestion.value) {
    return hasAlternatives.value
      ? t('app.fixAlternativesTitle', { count: currentAlternatives.value.length })
      : t('app.verifiedSolution')
  }
  const error = strategyErrors.value[selectedStrategy.value]
  if (error) return error
  if (selectedStrategy.value === 'parameter' && parameterPreferencesChanged.value) {
    return t('app.parameterPreferencesChanged')
  }
  return currentStrategyAttempt.value ? strategyAttemptOutcomeTitle(currentStrategyAttempt.value) : ''
})

const footerActionsRef = ref<HTMLElement | null>(null)

// When a search settles, the footer swaps Try for Apply or for nothing, and the focused button goes
// with it. Hand focus to the replacement action, or to the dialog when there is none.
watch(strategyLoading, async (loading, previous) => {
  if (loading !== null || previous === null || !props.visible) return
  await nextTick()
  const actions = footerActionsRef.value
  const dialog = actions?.closest<HTMLElement>('[role="dialog"]')
  if (!actions || !dialog) return
  const active = document.activeElement
  const focusLost = !active || active === document.body || !active.isConnected
    || (actions.contains(active) && active instanceof HTMLButtonElement && active.disabled)
  if (!focusLost) return
  const primary = actions.querySelector<HTMLButtonElement>('button:not([disabled])')
  ;(primary ?? dialog).focus()
})

const getFaultRuleReason = (rule: FaultRule) => {
  if (rule.reasonCode === 'CONFLICTING_END_STATES'
    && rule.targetEndState
    && rule.conflictingEndState) {
    return t('app.faultRuleConflictReason', {
      rule: rule.conflictingRuleString?.trim() || t('app.noDescription'),
      device: rule.targetDeviceLabel,
      first: formatModelToken(rule.targetEndState, rule.modelTokenSource),
      second: formatModelToken(rule.conflictingEndState, rule.modelTokenSource)
    })
  }
  if (rule.reasonCode === 'TRIGGERED') {
    return t('app.faultRuleTriggeredReason', {
      transition: rule.transitionNumber,
      action: formatModelToken(rule.targetActionLabel, rule.modelTokenSource),
      device: rule.targetDeviceLabel
    })
  }
  return rule.reason
}

const getConditionActionLabel = (action?: string) => {
  if (action === 'remove') return t('app.remove')
  if (action === 'add') return t('app.add')
  if (action === 'keep') return t('app.keep')
  return action || ''
}

const formatConditionAdjustment = (adjustment: NonNullable<FixSuggestion['conditionAdjustments']>[number]) => {
  const device = adjustment.deviceLabel || t('app.unknownModelItem')
  const condition = [
    `${device}.${formatModelToken(adjustment.attribute, adjustment.modelTokenSource)}`,
    formatRelation(adjustment.relation),
    adjustment.value === undefined
      ? undefined
      : formatModelToken(adjustment.value, adjustment.modelTokenSource)
  ].filter(Boolean).join(' ')
  const rule = adjustment.ruleDescription || t('app.affectedRule')
  if (adjustment.action === 'add') {
    return t('app.addConditionAdjustment', { condition, rule })
  }
  if (adjustment.action === 'remove') {
    return t('app.removeConditionAdjustment', { condition, rule })
  }
  return t('app.keepConditionAdjustment', { condition, rule })
}

const clearActiveFixTracking = (
  requestId: string,
  evidence: FixRequestTerminalEvidence
) => {
  if (activeFixRequestId.value !== requestId) return
  lastResolvedFixRequest = { requestId, evidence }
  activeFixAbortController.value?.abort()
  activeFixAbortController.value = null
  activeFixRequestId.value = null
  activeFixTraceId.value = null
  activeFixAuthToken.value = null
  pendingFixCancellationId.value = null
  if (unresolvedFixRequestId.value === requestId) unresolvedFixRequestId.value = null
  fixCancellationStatusFailures = 0
  fixRecoveryRetryNotBefore = 0
  if (fixFinishedObservation?.requestId === requestId) fixFinishedObservation = null
  if (fixOutcomeUnknownWarningRequestId === requestId) fixOutcomeUnknownWarningRequestId = null
  strategyLoading.value = null
  if (fixSearchTimer) {
    clearInterval(fixSearchTimer)
    fixSearchTimer = null
  }
}

const warnFixOutcomeUnknown = (requestId: string) => {
  if (fixOutcomeUnknownWarningRequestId === requestId) return
  fixOutcomeUnknownWarningRequestId = requestId
  notifyBlocked(t('app.fixStopRequestMayStillBeRunning'))
}

const beginUnknownFixRecovery = (requestId: string) => {
  if (activeFixRequestId.value !== requestId) return
  unresolvedFixRequestId.value = requestId
  warnFixOutcomeUnknown(requestId)
  if (pendingFixCancellationId.value !== requestId) {
    void cancelActiveFixSearch()
  }
}

const backOffFixCancellationRecovery = (requestId: string) => {
  if (activeFixRequestId.value !== requestId) return
  unresolvedFixRequestId.value = requestId
  activeFixAbortController.value?.abort()
  fixCancellationStatusFailures = 0
  fixRecoveryRetryNotBefore = Date.now() + FIX_RECOVERY_BACKOFF_MS
  warnFixOutcomeUnknown(requestId)
}

const cancelActiveFixSearch = async () => {
  const requestId = activeFixRequestId.value
  if (!requestId || pendingFixCancellationId.value === requestId) return
  pendingFixCancellationId.value = requestId
  fixCancellationStatusFailures = 0
  fixProgressStage.value = 'CANCELLING'
  try {
    const accepted = await requestInteractiveCancellation({
      cancel: () => cancelOwnedFixRequest(requestId),
      waitBeforeRetry: waitForFixCancellationRetry,
      shouldContinue: () => activeFixRequestId.value === requestId
    })
    if (accepted) {
      clearActiveFixTracking(requestId, 'cancel-accepted')
      return
    }
    warnFixOutcomeUnknown(requestId)
    await refreshFixProgress(requestId)
  } catch (error) {
    // Keep the request id and progress polling alive until its terminal state is observed.
    console.warn(`[Fix] Failed to cancel automatic-fix request ${requestId}:`, error)
    warnFixOutcomeUnknown(requestId)
    await refreshFixProgress(requestId)
  }
}

const disposeActiveFixSearch = () => {
  const requestId = activeFixRequestId.value
  if (!requestId) return
  const controller = activeFixAbortController.value
  const authToken = activeFixAuthToken.value
  pendingFixCancellationId.value = requestId
  fixProgressStage.value = 'CANCELLING'
  if (fixSearchTimer) {
    clearInterval(fixSearchTimer)
    fixSearchTimer = null
  }
  void requestInteractiveCancellation({
    cancel: () => cancelOwnedFixRequest(requestId, authToken),
    waitBeforeRetry: () => new Promise<void>(resolve => setTimeout(resolve, 100)),
    shouldContinue: () => activeFixRequestId.value === requestId,
    maxAttempts: 20
  }).then(accepted => {
    if (accepted) clearActiveFixTracking(requestId, 'cancel-accepted')
    else beginUnknownFixRecovery(requestId)
  }).catch(error => {
    console.warn(`[Fix] Failed to cancel automatic-fix request ${requestId} during teardown:`, error)
    beginUnknownFixRecovery(requestId)
  }).finally(() => {
    controller?.abort()
  })
}

const prepareForLogout = async (): Promise<'ready' | 'outcome-unknown'> => {
  const requestId = activeFixRequestId.value
  if (!requestId) return unresolvedFixRequestId.value ? 'outcome-unknown' : 'ready'
  const authToken = activeFixAuthToken.value
  pendingFixCancellationId.value = requestId
  fixProgressStage.value = 'CANCELLING'
  try {
    const accepted = await requestInteractiveCancellation({
      cancel: () => cancelOwnedFixRequest(requestId, authToken),
      waitBeforeRetry: waitForFixCancellationRetry,
      shouldContinue: () => activeFixRequestId.value === requestId,
      maxAttempts: 20
    })
    if (accepted) {
      clearActiveFixTracking(requestId, 'cancel-accepted')
      return 'ready'
    }
    if (lastResolvedFixRequest?.requestId === requestId) return 'ready'
    try {
      const status = await readOwnedFixRequestStatus(requestId, authToken)
      if (activeFixRequestId.value === requestId) fixProgressStage.value = status.stage
      if (status.state === 'FINISHED') {
        clearActiveFixTracking(requestId, 'status-finished')
        return 'ready'
      }
    } catch {
      // Missing status is not evidence that an admission-unknown request has finished.
    }
    if (lastResolvedFixRequest?.requestId === requestId) return 'ready'
    return 'outcome-unknown'
  } catch (error) {
    console.warn(`[Fix] Failed to stop automatic-fix request ${requestId} before logout:`, error)
    return 'outcome-unknown'
  }
}

// Watch visible prop. Parent-driven hides and route teardown must cancel expensive searches too.
watch(() => props.visible, (val) => {
  if (val) {
    handleOpen()
  } else {
    void cancelActiveFixSearch()
    dialogRequestVersion += 1
  }
}, { immediate: true })

onBeforeUnmount(() => {
  disposeActiveFixSearch()
  dialogRequestVersion += 1
})

const canOpenTrace = (traceId: number): boolean => {
  if (!activeFixRequestId.value && !unresolvedFixRequestId.value) return true
  return activeFixTraceId.value === traceId
}

defineExpose({ canOpenTrace, prepareForLogout })

// Close dialog
const closeDialog = () => {
  if (applyingFix.value) {
    notifyBlocked(t('app.fixApplyStillRunning'))
    return
  }
  cancelActiveFixSearch()
  dialogRequestVersion += 1
  emit('update:visible', false)
}

const isDialogOpen = computed(() => props.visible)
const { setDialogRef, handleModalKeydown } = useModalAccessibility(isDialogOpen, closeDialog)
</script>

<template>
  <!-- Fix Result Dialog - Following Verification Result Style -->
  <div
    v-if="visible"
    data-testid="fix-result-dialog"
    class="iot-dialog-overlay iot-dialog-overlay--nested"
    @click="closeDialog"
    @keydown="handleModalKeydown"
  >
    <div
      :ref="setDialogRef"
      class="iot-dialog iot-dialog--lg"
      :class="verifiedCount > 0 ? 'iot-dialog--warning' : hasAttemptResults ? 'iot-dialog--danger' : 'iot-dialog--info'"
      role="dialog"
      aria-modal="true"
      aria-labelledby="fix-result-dialog-title"
      tabindex="-1"
      @click.stop
    >
      
      <!-- Header -->
      <div
        data-testid="fix-result-header"
        class="iot-dialog__header"
      >
        <div class="iot-dialog__icon">
          <span class="material-symbols-outlined" aria-hidden="true">
            {{ strategyLoading ? 'progress_activity' : verifiedCount > 0 ? 'build' : hasAttemptResults ? 'search_off' : 'build' }}
          </span>
        </div>
        <div class="iot-dialog__heading">
          <h3 id="fix-result-dialog-title" class="iot-dialog__title">{{ t('app.fixSuggestions') }}</h3>
          <p class="iot-dialog__subtitle">{{ headerStatus }}</p>
        </div>
        <button
          type="button"
          :disabled="applyingFix"
          @click="closeDialog"
          class="iot-dialog__close"
          :aria-label="t('app.close')"
        >
          <span class="material-symbols-outlined" aria-hidden="true">close</span>
        </button>
      </div>

      <!-- Content -->
      <div
        data-testid="fix-result-scroll"
        class="iot-dialog__body iot-scroll-region iot-scroll-region--inset-end"
      >
        
        <div class="space-y-4">
          
          <!-- Violation Info Card -->
          <div class="p-5 rounded-xl bg-gradient-to-r from-[color:var(--danger-surface)] to-[color:var(--warning-surface)] border board-border-subtle">
            <div class="flex items-center gap-3">
              <div class="w-10 h-10 rounded-xl flex items-center justify-center board-chip-danger">
                <span class="material-symbols-outlined board-text-danger">warning</span>
              </div>
              <div class="flex-1">
                <span class="text-lg font-bold board-text-danger">{{ t('app.violationDetected') }}</span>
                <div class="flex items-center gap-2 mt-1">
                  <span class="text-sm board-text-danger">
                    {{ faultLoading
                      ? t('app.loadingFaultLocalization')
                      : t('app.faultRulesIdentified', { count: faultRules.length }) }}
                  </span>
                </div>
                <p v-if="localizedFaultLocalizationSummary" class="mt-2 text-xs leading-relaxed board-text-danger">
                  {{ localizedFaultLocalizationSummary }}
                </p>
                <details v-if="fixResult?.violatedSpecId || violatedSpecId" class="mt-2 text-[11px] board-text-danger">
                  <summary class="cursor-pointer font-semibold">{{ t('app.technicalDetails') }}</summary>
                  <div class="mt-1 grid gap-1 sm:grid-cols-[9rem_minmax(0,1fr)]">
                    <span class="font-medium">{{ t('app.specificationTechnicalId') }}</span>
                    <code class="break-all rounded board-chip-danger px-2 py-1 text-[11px] board-text-danger">{{ fixResult?.violatedSpecId || violatedSpecId }}</code>
                  </div>
                </details>
              </div>
            </div>
            <p class="text-sm board-text-danger mt-3 ml-13">
              {{ fixResult ? t('app.fixResultsRemainAdvisory') : t('app.fixAdvisoryBeforeRun') }}
            </p>
          </div>

          <div
            v-if="localizedFixLimitations.length || displayedFixWarnings.length || displayedSourceGenerationIssues.length"
            class="board-surface-warning rounded-xl p-4 text-sm"
          >
            <div class="mb-2 flex items-center gap-2 font-bold">
              <span class="material-symbols-outlined text-lg">warning</span>
              {{ t('app.fixLimitations') }}
            </div>
            <ul v-if="localizedFixLimitations.length || displayedSourceGenerationIssues.length" class="list-disc space-y-1 pl-5">
              <li v-for="warning in localizedFixLimitations" :key="warning">{{ warning }}</li>
              <li
                v-for="issue in displayedSourceGenerationIssues"
                :key="`${issue.issueType}:${issue.itemLabel}:${issue.reasonCode}`"
              >
                <strong>{{ issue.itemLabel }}</strong>: {{ t(generationIssueReasonKey(issue)) }}
              </li>
            </ul>
            <details v-if="displayedFixWarnings.length" class="mt-3 text-xs board-text-warning">
              <summary class="cursor-pointer font-semibold">{{ t('app.fixTechnicalDiagnostics') }}</summary>
              <ul class="mt-2 list-disc space-y-1 pl-5 font-mono text-[11px]">
                <li v-for="warning in displayedFixWarnings" :key="warning">{{ warning }}</li>
              </ul>
            </details>
          </div>

          <!-- Strategy Tabs -->
          <div class="border border-slate-200 rounded-xl overflow-hidden dark:border-slate-700">
            <div class="bg-slate-50 px-4 py-3 border-b border-slate-200 dark:border-slate-700 dark:bg-slate-800">
              <div class="flex items-center gap-2">
                <span class="material-symbols-outlined text-slate-600 dark:text-slate-300">tune</span>
                <span class="font-bold text-slate-800 dark:text-slate-100">{{ t('app.fixStrategies') }}</span>
              </div>
            </div>
            
            <div class="p-4">
              <!-- Strategy Buttons -->
              <div class="flex gap-2 mb-4">
                <button
                  v-for="option in strategyOptions"
                  :key="option.value"
                  type="button"
                  :data-testid="`fix-strategy-${option.value}`"
                  @click="switchStrategy(option.value)"
                  :aria-pressed="selectedStrategy === option.value"
                  class="flex-1 px-4 py-3 rounded-lg font-medium text-sm transition-all flex items-center justify-center gap-2"
                  :class="selectedStrategy === option.value
                    ? 'bg-[color:var(--accent-fill)] text-white shadow-md'
                    : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-800 dark:text-slate-200 dark:hover:bg-slate-700'"
                >
                  <span class="material-symbols-outlined text-lg" aria-hidden="true">
                    {{ option.icon }}
                  </span>
                  {{ option.label }}
                  <template v-if="fixResult?.suggestions.some(s => s.strategy === option.value && suggestionIsCurrent(s))">
                    <span class="material-symbols-outlined text-sm" aria-hidden="true">verified</span>
                    <span class="sr-only" data-testid="fix-strategy-verified">{{ t('app.fixStrategyHasVerifiedOption') }}</span>
                  </template>
                </button>
              </div>

              <!-- Strategy Description -->
              <!-- The attempt outcome is stated once, by the suggestion card or the outcome
                   empty state below; repeating it here also showed stale text beside the
                   "search ranges changed" notice. -->
              <div class="text-sm text-slate-500 mb-4 pl-1 dark:text-slate-300">
                {{ strategyDescriptions[selectedStrategy] }}
              </div>

              <!-- Always rendered, so a screen reader registers the region before its text changes. -->
              <p
                data-testid="fix-search-announcement"
                class="sr-only"
                role="status"
                aria-live="polite"
              >{{ searchAnnouncement }}</p>

              <div
                v-if="currentStrategyLoading"
                data-testid="fix-strategy-loading"
                class="board-surface-info mb-4 rounded-lg px-4 py-3 text-sm"
              >
                <div class="flex items-center gap-2 font-semibold">
                  <span class="material-symbols-outlined animate-spin text-lg" aria-hidden="true">progress_activity</span>
                  {{ t('app.tryingFixStrategy', { strategy: strategyLabels[selectedStrategy] }) }}
                </div>
                <p class="mt-1 text-xs font-semibold board-text-info">{{ fixProgressStageLabel }}</p>
                <p class="mt-1 text-xs board-text-info">
                  {{ t('app.fixSearchProgress', { seconds: fixSearchElapsedSeconds }) }}
                </p>
              </div>
              <!-- With an option listed, the footer says this beside the Apply it disables. -->
              <div
                v-else-if="anotherStrategyLoading && !currentSuggestion"
                id="fix-another-strategy-running"
                data-testid="fix-another-strategy-running"
                class="mb-4 rounded-lg border border-slate-200 bg-slate-50 px-4 py-3 text-xs text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300"
              >
                {{ t('app.anotherFixStrategyRunning', { strategy: strategyLabels[strategyLoading!] }) }}
              </div>

              <!-- Preferred Ranges -->
              <div
                v-if="selectedStrategy === 'parameter' && parameterTargetCatalog.length && !parameterRangesSettledOverTemplate"
                data-testid="fix-parameter-ranges"
                class="border border-slate-200 rounded-lg overflow-hidden mb-4 dark:border-slate-700"
              >
                <div class="bg-slate-50 px-3 py-2 border-b border-slate-200 flex items-center gap-2 dark:border-slate-700 dark:bg-slate-800">
                  <span class="material-symbols-outlined text-slate-600 text-lg dark:text-slate-300">speed</span>
                  <span class="font-bold text-sm text-slate-800 dark:text-slate-100">{{ t('app.parameterSearchRanges') }}</span>
                  <span
                    v-if="activePreferredRangeCount"
                    class="ml-auto px-2 py-0.5 board-chip-info text-xs rounded-full"
                  >{{ t('app.parameterSearchRangesActive', { count: activePreferredRangeCount }) }}</span>
                </div>
                <div class="p-3 space-y-3">
                  <p class="text-xs text-slate-500 dark:text-slate-400">{{ t('app.parameterSearchRangesHint') }}</p>
                  <div class="space-y-2">
                    <!-- Every row repeats the same bound and button labels, so each control is named
                         with its row's target first; otherwise a screen reader hears identical names. -->
                    <div
                      v-for="{ row, target, issue } in preferredRangeRowViews"
                      :key="row.targetId"
                      data-testid="fix-parameter-range-row"
                      class="grid grid-cols-1 sm:grid-cols-[minmax(0,1.6fr)_1fr_1fr_auto] gap-2 items-end"
                    >
                      <p class="min-w-0 text-sm text-slate-800 dark:text-slate-100">
                        <span :id="`fix-range-target-${row.targetId}`" class="block truncate font-medium" :title="formatPreferredRangeTarget(target)">{{ formatPreferredRangeTarget(target) }}</span>
                        <span class="text-xs text-slate-500 dark:text-slate-400">{{ t('app.parameterTemplateRange', { lower: target.lowerBound, upper: target.upperBound }) }}</span>
                      </p>
                      <label class="text-xs font-medium text-slate-600 dark:text-slate-300">
                        <span :id="`fix-range-lower-${row.targetId}`">{{ t('app.lowerBound') }}</span>
                        <input
                          v-model.number="row.lower"
                          type="number"
                          :aria-labelledby="`fix-range-target-${row.targetId} fix-range-lower-${row.targetId}`"
                          :aria-invalid="issue?.lowerInvalid ? 'true' : undefined"
                          :aria-describedby="issue?.lowerInvalid ? `fix-range-issue-${row.targetId}` : undefined"
                          :disabled="Boolean(preferenceActionBlockedReason)"
                          class="mt-1 w-full rounded-md border bg-white px-2 py-1.5 text-sm text-slate-800 focus:border-[color:var(--accent-border)] focus:outline-none disabled:cursor-not-allowed dark:bg-slate-950 dark:text-slate-100"
                          :class="issue?.lowerInvalid ? 'border-[color:var(--danger-border)]' : 'border-slate-300 dark:border-slate-600'"
                        />
                      </label>
                      <label class="text-xs font-medium text-slate-600 dark:text-slate-300">
                        <span :id="`fix-range-upper-${row.targetId}`">{{ t('app.upperBound') }}</span>
                        <input
                          v-model.number="row.upper"
                          type="number"
                          :aria-labelledby="`fix-range-target-${row.targetId} fix-range-upper-${row.targetId}`"
                          :aria-invalid="issue?.upperInvalid ? 'true' : undefined"
                          :aria-describedby="issue?.upperInvalid ? `fix-range-issue-${row.targetId}` : undefined"
                          :disabled="Boolean(preferenceActionBlockedReason)"
                          class="mt-1 w-full rounded-md border bg-white px-2 py-1.5 text-sm text-slate-800 focus:border-[color:var(--accent-border)] focus:outline-none disabled:cursor-not-allowed dark:bg-slate-950 dark:text-slate-100"
                          :class="issue?.upperInvalid ? 'border-[color:var(--danger-border)]' : 'border-slate-300 dark:border-slate-600'"
                        />
                      </label>
                      <button
                        type="button"
                        data-testid="fix-keep-original"
                        :aria-pressed="rowKeepsOriginal(row, target)"
                        :aria-labelledby="`fix-range-target-${row.targetId} fix-range-keep-${row.targetId}`"
                        :disabled="Boolean(preferenceActionBlockedReason)"
                        @click="toggleKeepOriginal(row, target)"
                        class="h-9 rounded-md px-3 text-sm font-medium flex items-center gap-1 transition-colors disabled:cursor-not-allowed"
                        :class="rowKeepsOriginal(row, target)
                          ? 'board-chip-info'
                          : 'board-chip-neutral hover:board-control-hover hover:board-text-strong'"
                      >
                        <span class="material-symbols-outlined text-base" aria-hidden="true">lock</span>
                        <span :id="`fix-range-keep-${row.targetId}`">{{ t('app.keepOriginalValue', { value: target.originalValue }) }}</span>
                      </button>
                      <p
                        v-if="issue"
                        :id="`fix-range-issue-${row.targetId}`"
                        data-testid="fix-range-issue"
                        role="alert"
                        class="sm:col-span-4 text-[length:var(--iot-font-min)] font-semibold board-text-danger"
                      >{{ issue.message }}</p>
                    </div>
                  </div>

                  <div
                    v-if="fixResult?.unusedPreferredRangeSelections?.length"
                    class="board-surface-warning rounded-md px-3 py-2 text-xs"
                  >
                    {{ t('app.unusedPreferencesDetail', { count: fixResult.unusedPreferredRangeSelections.length }) }}
                  </div>

                  <div v-if="narrowedPreferredRangeRows.length" class="flex flex-wrap gap-2">
                    <button
                      type="button"
                      data-testid="fix-reset-ranges"
                      :disabled="Boolean(preferenceActionBlockedReason)"
                      :aria-describedby="preferenceActionBlockedReason ? 'fix-preference-blocked' : undefined"
                      @click="resetPreferenceRows"
                      class="px-3 py-2 rounded-md board-chip-neutral hover:board-control-hover hover:board-text-strong text-sm font-medium flex items-center gap-1 transition-colors disabled:cursor-not-allowed"
                    >
                      <span class="material-symbols-outlined text-base" aria-hidden="true">restart_alt</span>
                      {{ t('app.resetParameterSearchRanges') }}
                    </button>
                  </div>
                  <p
                    v-if="preferenceActionBlockedReason"
                    id="fix-preference-blocked"
                    data-testid="fix-preference-blocked"
                    class="mt-2 text-xs text-slate-500 dark:text-slate-400"
                  >{{ preferenceActionBlockedReason }}</p>
                </div>
              </div>

              <!-- Verified alternatives: every one passed the same check, so the verdict is stated once. -->
              <div v-if="currentSuggestion">
                <div class="p-4 rounded-xl mb-4 bg-[color:var(--success-surface)] border border-[color:var(--success-border)]">
                  <div class="flex items-center gap-3">
                    <div class="w-10 h-10 rounded-xl flex items-center justify-center board-chip-success">
                      <span class="material-symbols-outlined board-text-success" aria-hidden="true">verified</span>
                    </div>
                    <div class="flex-1">
                      <span class="font-bold board-text-success">{{ t('app.verifiedSolution') }}</span>
                      <p class="text-sm board-text-success">{{ strategyAttemptReasonLabel('VERIFIED') }}</p>
                    </div>
                  </div>
                </div>

                <p
                  v-if="hasAlternatives"
                  id="fix-alternatives-title"
                  class="mb-2 text-sm font-bold text-slate-700 dark:text-slate-200"
                >{{ t('app.fixAlternativesTitle', { count: currentAlternatives.length }) }}</p>
                <div
                  :role="hasAlternatives ? 'radiogroup' : undefined"
                  :aria-labelledby="hasAlternatives ? 'fix-alternatives-title' : undefined"
                  class="space-y-3"
                >
                  <div
                    v-for="(alternative, alternativeIndex) in currentAlternatives"
                    :key="alternativeIndex"
                    :data-testid="hasAlternatives ? 'fix-alternative' : undefined"
                    :class="hasAlternatives
                      ? ['rounded-lg border-2 p-3 cursor-pointer', alternativeIndex === currentAlternativeIndex
                        ? 'border-[color:var(--accent-border)]'
                        : 'border-slate-200 dark:border-slate-700']
                      : undefined"
                    @click="hasAlternatives && selectAlternative(alternativeIndex)"
                  >
                    <label
                      v-if="hasAlternatives"
                      class="mb-3 flex items-center gap-2 text-sm font-semibold text-slate-800 cursor-pointer dark:text-slate-100"
                    >
                      <input
                        type="radio"
                        name="fix-alternative"
                        class="accent-[color:var(--accent)]"
                        :checked="alternativeIndex === currentAlternativeIndex"
                        :disabled="applyingFix"
                        @change="selectAlternative(alternativeIndex)"
                      />
                      {{ t('app.fixAlternativeLabel', { number: alternativeIndex + 1 }) }}
                    </label>
                    <div class="space-y-4">
                      <!-- Parameter Adjustments -->
                      <div v-if="alternative.parameterAdjustments?.length">
                        <div class="flex items-center gap-2 mb-2 text-sm font-bold text-slate-700 dark:text-slate-200">
                          <span class="material-symbols-outlined board-text-info">tune</span>
                          {{ t('app.parameterAdjustments') }} ({{ alternative.parameterAdjustments.length }})
                        </div>
                        <div class="space-y-2">
                          <div
                            v-for="(adj, idx) in alternative.parameterAdjustments"
                            :key="idx"
                            class="board-surface-info rounded-lg p-3"
                          >
                            <div class="flex items-center justify-between">
                              <div class="min-w-0 flex items-center gap-2">
                                <span
                                  class="max-w-[14rem] truncate px-2 py-0.5 bg-[color:var(--accent-fill)] text-white text-xs rounded font-bold"
                                  :title="formatPreferredRangeTarget(adj)"
                                >{{ formatPreferredRangeTarget(adj) }}</span>
                                <code class="min-w-0 truncate text-sm font-mono text-slate-700 dark:text-slate-200" :title="`${formatModelToken(adj.attribute, adj.modelTokenSource)} ${adj.relation}`">{{ formatModelToken(adj.attribute, adj.modelTokenSource) }} {{ adj.relation }}</code>
                              </div>
                              <span class="text-xs text-slate-500 dark:text-slate-300">{{ t('app.rangeLabel') }}: [{{ adj.lowerBound }}, {{ adj.upperBound }}]</span>
                            </div>
                            <div class="flex items-center gap-2 mt-2">
                              <span class="px-2 py-1 board-chip-danger rounded font-mono text-sm line-through">{{ adj.originalValue }}</span>
                              <span class="material-symbols-outlined text-slate-500 dark:text-slate-500">arrow_forward</span>
                              <span class="px-2 py-1 board-chip-success rounded font-mono text-sm">{{ adj.newValue }}</span>
                            </div>
                            <p
                              v-if="parameterAdjustmentMakesRuleUnreachable(adj)"
                              data-testid="fix-parameter-unreachable-warning"
                              class="mt-2 text-xs font-semibold board-text-warning"
                            >
                              {{ t('app.fixParameterMakesRuleUnreachable') }}
                            </p>
                          </div>
                        </div>
                      </div>

                      <!-- Condition Adjustments -->
                      <div v-if="alternative.conditionAdjustments?.length">
                        <div class="flex items-center gap-2 mb-2 text-sm font-bold text-slate-700 dark:text-slate-200">
                          <span class="material-symbols-outlined board-text-success">checklist</span>
                          {{ t('app.conditionAdjustments') }} ({{ alternative.conditionAdjustments.length }})
                        </div>
                        <div class="space-y-2">
                          <div
                            v-for="(adj, idx) in alternative.conditionAdjustments"
                            :key="idx"
                            class="board-surface-success rounded-lg p-3 flex items-center gap-3"
                          >
                            <div
                              class="w-8 h-8 rounded-lg flex items-center justify-center"
                              :class="adj.action === 'remove' ? 'board-chip-danger' : adj.action === 'add' ? 'board-chip-success' : 'bg-slate-100 dark:bg-slate-700'"
                            >
                              <span class="material-symbols-outlined text-sm" :class="adj.action === 'remove' ? 'board-text-danger' : adj.action === 'add' ? 'board-text-success' : 'text-slate-600 dark:text-slate-200'" aria-hidden="true">
                                {{ adj.action === 'remove' ? 'remove' : adj.action === 'add' ? 'add' : 'check' }}
                              </span>
                            </div>
                            <div class="flex-1">
                              <span class="text-sm font-medium text-slate-700 dark:text-slate-200">{{ formatConditionAdjustment(adj) }}</span>
                            </div>
                            <span
                              class="px-2 py-0.5 rounded text-xs font-medium"
                              :class="adj.action === 'remove' ? 'board-chip-danger board-text-danger' : adj.action === 'add' ? 'board-chip-success board-text-success' : 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-200'"
                            >
                              {{ getConditionActionLabel(adj.action) }}
                            </span>
                          </div>
                        </div>
                      </div>

                      <!-- Disabled Rules -->
                      <div v-if="alternative.removedRuleDescriptions?.length">
                        <div class="flex items-center gap-2 mb-2 text-sm font-bold text-slate-700 dark:text-slate-200">
                          <span class="material-symbols-outlined board-text-warning">block</span>
                          {{ t('app.rulesToRemove') }} ({{ alternative.removedRuleDescriptions.length }})
                        </div>
                        <div class="board-surface-warning rounded-lg p-3">
                          <div class="space-y-2">
                            <span
                              v-for="(description, index) in alternative.removedRuleDescriptions"
                              :key="`${index}-${description}`"
                              data-testid="fix-removed-rule"
                              class="block rounded-lg bg-[color:var(--warning-fill)] px-3 py-1 text-sm font-medium text-white"
                            >
                              {{ description }}
                            </span>
                          </div>
                        </div>
                      </div>

                      <!-- Accepted because the target holds and nothing that held broke; these were already violated.
                           Forward verification computes this per candidate, so each option states its own. -->
                      <div
                        v-if="alternative.preexistingViolations.length"
                        data-testid="fix-preexisting-violations"
                        class="board-surface-warning rounded-lg p-3"
                      >
                        <div class="flex items-start gap-2">
                          <span class="material-symbols-outlined text-lg board-text-warning" aria-hidden="true">report</span>
                          <div class="min-w-0 flex-1">
                            <p class="text-sm font-semibold">
                              {{ t('app.fixPreexistingViolationsTitle', { count: alternative.preexistingViolations.length }) }}
                            </p>
                            <p class="mt-1 text-xs">{{ t('app.fixPreexistingViolationsDetail') }}</p>
                            <ul class="mt-2 space-y-1">
                              <li
                                v-for="violation in alternative.preexistingViolations"
                                :key="violation.specId"
                                data-testid="fix-preexisting-violation"
                                class="text-xs"
                              >
                                <span class="font-semibold">{{ preexistingViolationLabel(violation.templateId) }}</span>
                                <code class="ml-2 break-all font-mono">{{ violation.formulaPreview }}</code>
                              </li>
                            </ul>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>

                <!-- Holds with a single listed option too: the list is complete only if the search said so. -->
                <p
                  v-if="alternativesIncomplete"
                  data-testid="fix-alternatives-incomplete"
                  class="mt-3 flex items-start gap-2 text-xs text-slate-500 dark:text-slate-400"
                >
                  <span class="material-symbols-outlined text-sm" aria-hidden="true">info</span>
                  {{ t('app.fixAlternativesIncomplete') }}
                </p>
              </div>

              <div v-else-if="strategyErrors[selectedStrategy]" class="board-surface-danger rounded-lg px-4 py-4">
                <div class="flex items-start gap-2">
                  <span class="material-symbols-outlined text-lg" aria-hidden="true">error</span>
                  <div>
                    <p class="font-semibold">{{ t('app.fixStrategyRequestFailed') }}</p>
                    <p class="mt-1 text-xs">{{ strategyErrors[selectedStrategy] }}</p>
                  </div>
                </div>
              </div>

              <div
                v-else-if="selectedStrategy === 'parameter' && parameterPreferencesChanged"
                data-testid="fix-parameter-preferences-stale"
                class="board-surface-warning rounded-lg px-4 py-4"
              >
                <div class="flex items-start gap-2">
                  <span class="material-symbols-outlined text-lg" aria-hidden="true">edit_note</span>
                  <div>
                    <p class="font-semibold">{{ t('app.parameterPreferencesChanged') }}</p>
                    <p class="mt-1 text-xs">{{ t('app.parameterPreferencesRequireRetry') }}</p>
                  </div>
                </div>
              </div>

              <div
                v-else-if="currentStrategyAttempt"
                data-testid="fix-attempt-outcome"
                class="text-center py-8 text-slate-500 dark:text-slate-300"
              >
                <span class="material-symbols-outlined text-4xl mb-2 block" aria-hidden="true">
                  {{ attemptIsProof(currentStrategyAttempt) ? 'search_off' : 'help' }}
                </span>
                <p class="font-semibold text-slate-700 dark:text-slate-200">{{ strategyAttemptOutcomeTitle(currentStrategyAttempt) }}</p>
                <p class="mx-auto mt-2 max-w-lg text-xs text-slate-500 dark:text-slate-400">
                  {{ strategyAttemptOutcomeDetail(currentStrategyAttempt) }}
                </p>
                <p
                  v-if="strategyAttemptOutcomeNote(currentStrategyAttempt)"
                  data-testid="fix-attempt-outcome-note"
                  class="mx-auto mt-2 max-w-lg text-xs text-slate-500 dark:text-slate-400"
                >
                  {{ strategyAttemptOutcomeNote(currentStrategyAttempt) }}
                </p>
              </div>

              <div v-else-if="!currentStrategyLoading" class="rounded-lg border border-dashed border-slate-300 bg-slate-50 px-4 py-5 text-center text-slate-600 dark:border-slate-600 dark:bg-slate-800 dark:text-slate-300">
                <span class="material-symbols-outlined mb-2 block text-3xl text-slate-400 dark:text-slate-500" aria-hidden="true">science</span>
                <p class="font-semibold">{{ t('app.fixStrategyNotTried') }}</p>
              </div>

            </div>
          </div>

          <!-- Fault Rules Section -->
          <div class="border border-slate-200 rounded-xl overflow-hidden dark:border-slate-700">
            <div class="bg-slate-50 px-4 py-3 border-b border-slate-200 dark:border-slate-700 dark:bg-slate-800">
              <div class="flex items-center gap-2">
                <span class="material-symbols-outlined text-slate-600 dark:text-slate-300">search</span>
                <span class="font-bold text-slate-800 dark:text-slate-100">{{ t('app.faultLocalization') }}</span>
                <span class="ml-auto px-2 py-0.5 board-chip-danger text-xs rounded-full">{{ t('app.rulesCount', { count: faultRules.length }) }}</span>
              </div>
            </div>
            
            <div class="p-4">
              <div v-if="faultLoading" class="flex items-center justify-center gap-2 py-8 text-sm text-slate-500 dark:text-slate-300">
                <span class="material-symbols-outlined animate-spin" aria-hidden="true">progress_activity</span>
                {{ t('app.loadingFaultLocalization') }}
              </div>
              <div v-else-if="faultLoadFailed" class="text-center py-8 board-text-danger">
                <span class="material-symbols-outlined text-3xl" aria-hidden="true">error</span>
                <p class="mt-2 text-sm font-medium">{{ t('app.failedToLoadFaultLocalization') }}</p>
              </div>
              <div v-else-if="faultRules.length === 0" class="text-center py-8 text-slate-500 dark:text-slate-400">
                <span class="material-symbols-outlined text-4xl mb-2 block">check_circle</span>
                <p>{{ t('app.noFaultRulesFound') }}</p>
                <p class="text-xs mt-1">{{ t('app.violationMayBeDeviceTransitions') }}</p>
              </div>
              
              <div v-else class="space-y-2">
                <div 
                  v-for="(rule, idx) in faultRules"
                  :key="idx"
                  class="border border-slate-200 rounded-lg p-3 hover:bg-slate-50 transition-colors dark:border-slate-700 dark:hover:bg-slate-800"
                  :class="{ 'border-[color:var(--warning-border)] board-chip-warning': rule.conflicting }"
                >
                  <div class="flex items-center justify-between mb-2">
                    <div class="flex items-center gap-2">
                      <span class="w-6 h-6 bg-[color:var(--accent-fill-hover)] text-white rounded flex items-center justify-center text-xs font-bold">{{ idx + 1 }}</span>
                      <code class="text-xs bg-slate-100 px-2 py-1 rounded font-mono dark:bg-slate-800 dark:text-slate-100">{{ rule.ruleString?.trim() || t('app.noDescription') }}</code>
                    </div>
                    <span v-if="rule.conflicting" class="px-2 py-0.5 board-chip-warning board-text-warning text-xs rounded flex items-center gap-1">
                      <span class="material-symbols-outlined text-xs">warning</span>
                      {{ t('app.conflicts') }}
                    </span>
                  </div>
                  <div class="grid grid-cols-1 gap-2 text-xs text-slate-600 sm:grid-cols-3 dark:text-slate-300">
                    <div>{{ t('app.transitionNumberLabel') }}: <span class="font-medium">{{ rule.transitionNumber }}</span></div>
                    <div>{{ t('app.device') }}: <span class="font-medium">{{ rule.targetDeviceLabel }}</span></div>
                    <div>{{ t('app.action') }}: <span data-testid="fix-fault-action" class="font-medium">{{ formatModelToken(rule.targetActionLabel, rule.modelTokenSource) }}</span></div>
                  </div>
                  <div v-if="rule.reason" class="mt-2 text-xs text-slate-500 flex items-start gap-1 dark:text-slate-400">
                    <span class="material-symbols-outlined text-xs mt-0.5">info</span>
                    {{ getFaultRuleReason(rule) }}
                  </div>
                </div>
              </div>
            </div>
          </div>

        </div>
      </div>

      <!-- Footer: dismiss on the left, the one primary action on the right.
           At most one of Apply, Try and "try another strategy" shows: Try until the strategy lists an
           option, then Apply the selected one. A settled outcome would only repeat on retry, so it
           offers the next untried strategy instead, or nothing once all were tried. Apply and Try
           used to sit at the end of the scroll body, where the strategy detail is routinely ~300px
           taller than the fold: the action landed 19px past
           the visible edge and read as a broken, half-drawn control in both themes. An action the
           user must reach to make progress does not belong behind a scroll. Matches the
           dismiss-left / primary-right footer RuleBuilderDialog already establishes. -->
      <div class="iot-dialog__footer">
        <button
          type="button"
          :disabled="applyingFix"
          @click="closeDialog"
          class="iot-dialog-btn iot-dialog-btn--ghost"
        >
          <span class="material-symbols-outlined text-sm" aria-hidden="true">close</span>
          {{ t('app.close') }}
        </button>

        <div ref="footerActionsRef" class="ml-auto flex min-w-0 flex-col items-end gap-2">
          <p
            v-if="applyBlockedReason"
            id="fix-apply-readiness"
            data-testid="fix-apply-readiness"
            role="status"
            class="max-w-xl text-right text-xs leading-5 text-slate-500 dark:text-slate-400"
          >
            {{ applyBlockedReason }}
          </p>

          <button
            v-if="currentSuggestion"
            type="button"
            data-testid="fix-apply-current"
            class="iot-dialog-btn"
            :class="currentSuggestion.strategy === 'remove'
              ? 'iot-dialog-btn--danger'
              : 'iot-dialog-btn--primary'"
            :disabled="applyDisabled"
            :aria-describedby="applyBlockedReason ? 'fix-apply-readiness' : undefined"
            @click="applyFix(currentSuggestion)"
          >
            <span v-if="!applyingFix" class="material-symbols-outlined" aria-hidden="true">check_circle</span>
            <span v-else class="iot-dialog-btn__spinner"></span>
            {{ applyingFix
              ? t('app.applying')
              : currentSuggestion.strategy === 'remove'
                ? t('app.removeRulesAndApply')
                : t('app.applyThisFix') }}
          </button>

          <button
            v-else-if="!currentAttemptSettled"
            type="button"
            data-testid="fix-try-current"
            :disabled="strategyLoading !== null || Boolean(rangeBlockedReasonIds)"
            :aria-describedby="[anotherStrategyLoading ? 'fix-another-strategy-running' : '', rangeBlockedReasonIds]
              .filter(Boolean).join(' ') || undefined"
            @click="trySelectedStrategy"
            class="iot-dialog-btn iot-dialog-btn--primary"
          >
            <span class="material-symbols-outlined" aria-hidden="true">science</span>
            {{ currentStrategyAttempt || strategyErrors[selectedStrategy]
              ? t('app.retryFixStrategy')
              : t('app.tryFixStrategy') }}
          </button>

          <button
            v-else-if="nextUntriedStrategy"
            type="button"
            data-testid="fix-try-next-strategy"
            :disabled="strategyLoading !== null"
            :aria-describedby="anotherStrategyLoading ? 'fix-another-strategy-running' : undefined"
            @click="tryNextStrategy"
            class="iot-dialog-btn iot-dialog-btn--primary"
          >
            <span class="material-symbols-outlined" aria-hidden="true">{{ strategyIcons[nextUntriedStrategy] }}</span>
            {{ t('app.fixTryNextStrategy', { strategy: strategyLabels[nextUntriedStrategy] }) }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
</style>
