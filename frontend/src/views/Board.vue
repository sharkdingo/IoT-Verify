<script lang="ts">
export type BoardResultRetryOptions<T> = {
  load: () => Promise<T>
  shouldRetry: (error: unknown) => boolean
  waitBeforeRetry: (failedAttempt: number) => Promise<void>
  maxAttempts: number
}

export const loadBoardResultWithRetry = async <T,>({
  load,
  shouldRetry,
  waitBeforeRetry,
  maxAttempts
}: BoardResultRetryOptions<T>): Promise<T> => {
  const attempts = Math.max(1, Math.floor(maxAttempts))
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      return await load()
    } catch (error) {
      if (attempt >= attempts || !shouldRetry(error)) throw error
      await waitBeforeRetry(attempt)
    }
  }
  throw new Error('Completed result recovery exhausted')
}

export const createLatestBoardRequestGuard = () => {
  let epoch = 0
  return {
    begin: () => ++epoch,
    invalidate: () => { epoch += 1 },
    isCurrent: (requestEpoch: number) => requestEpoch === epoch
  }
}

/**
 * Historical playback is evidence from a completed run. Its node presentation must never read
 * the live template catalog, because a later template edit can otherwise change old evidence.
 */
export const shouldResolveLiveTemplateForPresentation = (historicalPlaybackActive: boolean): boolean =>
  !historicalPlaybackActive

/** A persisted-history detail cannot be safely retried when the server rejects its frozen model. */
export const isPersistedHistoryDataInvalid = (error: unknown): boolean =>
  (error as { response?: { data?: { data?: { reasonCode?: unknown } } } })
    ?.response?.data?.data?.reasonCode === 'PERSISTED_SEMANTIC_DATA_INVALID'

/** Returns an affected persisted record only when the backend identified one unambiguously. */
export const persistedHistoryInvalidRecordId = (
  error: unknown,
  recordType: string
): number | null => {
  const data = (error as {
    response?: { data?: { data?: { reasonCode?: unknown; recordType?: unknown; recordId?: unknown } } }
  })?.response?.data?.data
  if (data?.reasonCode !== 'PERSISTED_SEMANTIC_DATA_INVALID' || data.recordType !== recordType) {
    return null
  }
  const recordId = Number(data.recordId)
  return Number.isSafeInteger(recordId) && recordId > 0 ? recordId : null
}

export const shouldClearUnusableHistoryDeepLink = (
  loadingFromDeepLink: boolean,
  error: unknown
): boolean => loadingFromDeepLink && isPersistedHistoryDataInvalid(error)

/** Only confirmed absent/forbidden targets, not transient reads, make a shared history link dead. */
export const shouldReportUnusableHistoryDeepLink = (
  loadingFromDeepLink: boolean,
  error: unknown
): boolean => {
  if (!loadingFromDeepLink) return false
  if (isPersistedHistoryDataInvalid(error)) return true
  const status = Number((error as { response?: { status?: unknown } } | null)?.response?.status)
  return status === 403 || status === 404 || status === 410
}

/** A trace address is meaningful only under the verification run named by its URL. */
export const verificationRunContainsTrace = (
  run: { traces?: Array<{ id?: number }> } | null | undefined,
  traceId: number
): boolean => Number.isSafeInteger(traceId) && traceId > 0
  && (run?.traces || []).some(trace => trace.id === traceId)

/** A leaf replay response must belong to the run encoded in a shared history URL. */
export const verificationTraceBelongsToRun = (
  trace: { verificationTaskId?: number } | null | undefined,
  runId: number
): boolean => Number.isSafeInteger(runId) && runId > 0 && trace?.verificationTaskId === runId

/** A fuzz finding is addressed under its owning completed exploration run when deep-linked. */
export const fuzzingFindingBelongsToRun = (
  finding: { fuzzTaskId?: number } | null | undefined,
  runId: number
): boolean => Number.isSafeInteger(runId) && runId > 0 && finding?.fuzzTaskId === runId

/** A run can remain inconclusive while retaining evidence parsed before another result failed. */
export const shouldLoadVerificationEvidence = (counterexampleCount: unknown): boolean =>
  Number.isSafeInteger(counterexampleCount) && Number(counterexampleCount) > 0

/**
 * A summary endpoint deliberately avoids decoding every large frozen artifact. Once an on-demand
 * read proves one artifact is corrupt, keep that fact for this mounted Board session so a list
 * refresh cannot turn the same known-bad item back into a misleading replay button.
 */
export const retainSessionUnavailableHistoryItems = <T extends { id: number }>(
  items: readonly T[],
  unavailableIds: ReadonlySet<number>,
  toUnavailable: (item: T) => T
): T[] => items.map(item => unavailableIds.has(item.id) ? toUnavailable(item) : item)

export class BoardMutationAdmissionCancelledError extends Error {
  constructor() {
    super('Board mutation admission was cancelled')
    this.name = 'BoardMutationAdmissionCancelledError'
  }
}

export const runAdmittedBoardMutation = <T,>(
  work: () => Promise<T>,
  admissionGuard?: () => boolean
): Promise<T> => {
  if (admissionGuard && !admissionGuard()) {
    return Promise.reject(new BoardMutationAdmissionCancelledError())
  }
  return work()
}

export type HistoricalPlaybackAdmissionResult =
  | 'admitted'
  | 'request-stale'
  | 'board-changed'
  | 'ui-blocked'

export const revalidateHistoricalPlaybackAdmission = async ({
  waitForPendingMutations,
  isRequestCurrent,
  initialMutationEpoch,
  currentMutationEpoch,
  recheckUiAdmission
}: {
  waitForPendingMutations: () => Promise<void>
  isRequestCurrent: () => boolean
  initialMutationEpoch: number
  currentMutationEpoch: () => number
  recheckUiAdmission: () => boolean
}): Promise<HistoricalPlaybackAdmissionResult> => {
  if (!isRequestCurrent()) return 'request-stale'
  await waitForPendingMutations()
  if (!isRequestCurrent()) return 'request-stale'
  if (currentMutationEpoch() !== initialMutationEpoch) return 'board-changed'
  return recheckUiAdmission() ? 'admitted' : 'ui-blocked'
}

export const prepareBoardChatInteraction = ({
  sceneReplacementInProgress,
  tracePlaybackVisible,
  simulationPlaybackVisible,
  closeTracePlayback,
  closeSimulationPlayback
}: {
  sceneReplacementInProgress: boolean
  tracePlaybackVisible: boolean
  simulationPlaybackVisible: boolean
  closeTracePlayback: () => void
  closeSimulationPlayback: () => void
}): boolean => {
  if (sceneReplacementInProgress) return false
  if (tracePlaybackVisible) closeTracePlayback()
  if (simulationPlaybackVisible) closeSimulationPlayback()
  return true
}

export const runTrackedBoardMutation = async <T,>(
  work: () => Promise<T>,
  previousSceneFingerprint: string | null,
  currentSceneFingerprint: () => string | null,
  onSceneChanged: () => void
): Promise<T> => {
  try {
    return await work()
  } finally {
    if (previousSceneFingerprint !== null) {
      const currentFingerprint = currentSceneFingerprint()
      if (currentFingerprint !== null && currentFingerprint !== previousSceneFingerprint) {
        onSceneChanged()
      }
    }
  }
}

export const handleRecommendationApplySceneChange = (
  confirmedApplied: boolean,
  preserveApplied: () => void,
  invalidateRecommendations: () => void
) => {
  if (confirmedApplied) preserveApplied()
  else invalidateRecommendations()
}

export const invalidateFuzzingResultRequests = (
  currentRequestEpoch: number,
  invalidateHistoryDetailRequests: () => void
): number => {
  invalidateHistoryDetailRequests()
  return currentRequestEpoch + 1
}

/**
 * Confirms a history deletion, invalidating in-flight detail requests only once the user has
 * agreed. Returns whether to proceed: cancelling is an ordinary outcome, not an exception, so
 * callers branch on the result instead of catching.
 */
export const confirmHistoryDeletion = async (
  requestConfirmation: () => Promise<boolean>,
  invalidateDetailRequests: () => void
): Promise<boolean> => {
  if (!await requestConfirmation()) return false
  invalidateDetailRequests()
  return true
}

export const createScopedBoardInvalidationBinding = <Message,>(
  subscribe: (
    userId: number | null | undefined,
    listener: (message: Message) => void
  ) => () => void,
  listener: (message: Message) => void
) => {
  let unsubscribe: () => void = () => undefined
  return {
    bind(userId: number | null | undefined) {
      unsubscribe()
      unsubscribe = subscribe(userId, listener)
    },
    dispose() {
      unsubscribe()
      unsubscribe = () => undefined
    }
  }
}

export const collectBundledEnvironmentNames = (
  providers: readonly { bundled: boolean; names: readonly string[] }[]
): string[] => {
  const environmentProviders = new Map<string, { bundled: boolean; seen: boolean }>()
  providers.forEach(provider => {
    provider.names.forEach(name => {
      const current = environmentProviders.get(name) || { bundled: true, seen: false }
      current.bundled = current.bundled && provider.bundled
      current.seen = true
      environmentProviders.set(name, current)
    })
  })
  return [...environmentProviders.entries()]
    .filter(([, provider]) => provider.seen && provider.bundled)
    .map(([name]) => name)
}

export const shouldRedirectNarrowPanelFocus = (
  scrimVisible: boolean,
  target: EventTarget | null,
  openPanel: HTMLElement | null,
  scrim: HTMLElement | null
): boolean => {
  if (!scrimVisible || !(target instanceof HTMLElement) || !openPanel) return false
  return !openPanel.contains(target)
    && !scrim?.contains(target)
    && !target.closest('.board-nav-bar')
    && !target.closest('[aria-modal="true"]')
    && !target.closest('[data-testid^="template-preview-"]')
}

type NarrowPanelName = 'control' | 'inspector'

export const focusCollapsedNarrowPanelToggle = (
  panel: NarrowPanelName,
  root: ParentNode = document
): boolean => {
  const testId = panel === 'control' ? 'control-center' : 'system-inspector'
  const toggle = root.querySelector<HTMLElement>(
    `[data-testid="${testId}"].is-collapsed button`
  )
  toggle?.focus()
  return Boolean(toggle)
}

export type ReconciledBoardNode = {
  id: string
  position: { x: number; y: number }
  width: number
  height: number
}

export type ReconciledBoardNodeLayout = Pick<ReconciledBoardNode, 'position' | 'width' | 'height'>

/**
 * Merge a server snapshot without detaching nodes that an in-flight pointer interaction owns.
 * Pending local layouts also win over older targeted-mutation snapshots from the server.
 */
export const reconcileBoardNodeSnapshot = <T extends ReconciledBoardNode>(
  currentNodes: T[],
  incomingNodes: readonly T[],
  pendingLayouts: ReadonlyMap<string, { layout: ReconciledBoardNodeLayout }>,
  activeNodeIds: ReadonlySet<string>
): T[] => {
  const currentById = new Map(currentNodes.map(node => [node.id, node]))
  let changed = currentNodes.length !== incomingNodes.length
  const reconciled = incomingNodes.map((incoming, index) => {
    const current = currentById.get(incoming.id)
    if (current !== currentNodes[index]) changed = true
    if (!current) {
      return { ...incoming, position: { ...incoming.position } }
    }

    const preservedLayout = activeNodeIds.has(incoming.id)
      ? {
          position: { ...current.position },
          width: current.width,
          height: current.height
        }
      : pendingLayouts.get(incoming.id)?.layout

    const next = {
      ...incoming,
      ...(preservedLayout || {}),
      position: { ...(preservedLayout || incoming).position }
    }
    // Layout responses include every node. Replacing equal runtime collections would fire
    // synchronous model watchers for each node, repeatedly serializing the entire board.
    for (const key of Object.keys(next) as Array<keyof T>) {
      const value = next[key]
      if (current[key] === value) continue
      if (value !== null && typeof value === 'object'
        && JSON.stringify(current[key]) === JSON.stringify(value)) continue
      current[key] = value
      changed = true
    }
    return current
  })
  return changed ? reconciled : currentNodes
}

export const clampFloatingMenuPosition = (
  position: { x: number; y: number },
  menuSize: { width: number; height: number },
  viewportSize: { width: number; height: number },
  margin = 8
) => ({
  x: Math.max(margin, Math.min(position.x, viewportSize.width - menuSize.width - margin)),
  y: Math.max(margin, Math.min(position.y, viewportSize.height - menuSize.height - margin))
})

export const resolveCurrentBoardNode = <T extends { id: string }>(
  nodes: readonly T[],
  nodeId: string | null | undefined
): T | null => nodeId ? nodes.find(node => node.id === nodeId) || null : null

export type DeletionReviewCloseReason = 'board-changed' | 'cancelled' | 'submitted'

export const resolveDeletionReviewDeviceDialogRestore = <T extends { id: string }>(
  reason: DeletionReviewCloseReason,
  nodes: readonly T[],
  sourceDeviceDialogNodeId: string | null,
  reviewedNodeId: string | null | undefined
): T | null => {
  if (reason !== 'board-changed'
    || !sourceDeviceDialogNodeId
    || sourceDeviceDialogNodeId !== reviewedNodeId) return null
  return resolveCurrentBoardNode(nodes, sourceDeviceDialogNodeId)
}

export type DeviceDialogCloseController = {
  prepareClose: () => Promise<boolean>
}

export const continueAfterDeviceDialogApproval = async (
  controller: DeviceDialogCloseController | null,
  transition: () => void
): Promise<boolean> => {
  if (!controller || !await controller.prepareClose()) return false
  transition()
  return true
}

export type RenameDialogSnapshot<T extends { id: string; label: string }> = {
  node: T
  newName: string
  originalLabel: string
}

export const reconcileRenameDialogSnapshot = <T extends { id: string; label: string }>(
  nodes: readonly T[],
  draft: RenameDialogSnapshot<T>
): RenameDialogSnapshot<T> | null => {
  const currentNode = resolveCurrentBoardNode(nodes, draft.node.id)
  if (!currentNode) return null
  const hasUnsavedName = draft.newName !== draft.originalLabel
  return {
    node: currentNode,
    newName: hasUnsavedName ? draft.newName : currentNode.label,
    originalLabel: hasUnsavedName ? draft.originalLabel : currentNode.label
  }
}

export const hasFrozenBundledTokenSource = (
  value: { modelTokenSource?: string | null }
): boolean => value.modelTokenSource === 'BUNDLED'

export type ScenarioRecommendationCountField =
  | 'minDevices'
  | 'minRules'
  | 'minSpecs'
  | 'maxDevices'
  | 'maxRules'
  | 'maxSpecs'

export type ScenarioRecommendationTargetRequest = Record<ScenarioRecommendationCountField, number> & {
  language: string
  userRequirement: string
}

export const buildScenarioRecommendationTargetRequest = (
  input: Record<ScenarioRecommendationCountField, unknown> & {
    language: string
    userRequirement: string
  },
  validateCount: (value: unknown, field: ScenarioRecommendationCountField) => number,
  rangeError: (field: 'devices' | 'rules' | 'specifications') => Error
): ScenarioRecommendationTargetRequest => {
  const request: ScenarioRecommendationTargetRequest = {
    minDevices: validateCount(input.minDevices, 'minDevices'),
    minRules: validateCount(input.minRules, 'minRules'),
    minSpecs: validateCount(input.minSpecs, 'minSpecs'),
    maxDevices: validateCount(input.maxDevices, 'maxDevices'),
    maxRules: validateCount(input.maxRules, 'maxRules'),
    maxSpecs: validateCount(input.maxSpecs, 'maxSpecs'),
    language: input.language,
    userRequirement: input.userRequirement
  }
  if (request.minDevices > request.maxDevices) throw rangeError('devices')
  if (request.minRules > request.maxRules) throw rangeError('rules')
  if (request.minSpecs > request.maxSpecs) throw rangeError('specifications')
  return request
}

export const canonicalBoardSnapshotValue = (value: unknown): unknown => {
  if (Array.isArray(value)) return value.map(canonicalBoardSnapshotValue)
  if (value && typeof value === 'object') {
    const source = value as Record<string, unknown>
    return Object.fromEntries(Object.keys(source)
      .sort()
      .map(key => [key, canonicalBoardSnapshotValue(source[key])]))
  }
  return value
}

export interface RecommendationSceneSnapshotLike {
  nodes: readonly unknown[]
  environmentVariables: readonly unknown[]
  rules: readonly unknown[]
  specifications: readonly unknown[]
  deviceTemplates: readonly unknown[]
}

const canonicalUnorderedCollection = (
  values: readonly unknown[],
  project: (value: unknown) => unknown = value => value
): unknown[] => values
  .map(value => canonicalBoardSnapshotValue(project(value)))
  .sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right)))

const recommendationNodeValue = (value: unknown): unknown => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return value
  const {
    position: _position,
    width: _width,
    height: _height,
    variables,
    privacies,
    ...semanticNode
  } = value as Record<string, unknown>
  return {
    ...semanticNode,
    ...(Array.isArray(variables)
      ? { variables: canonicalUnorderedCollection(variables) }
      : {}),
    ...(Array.isArray(privacies)
      ? { privacies: canonicalUnorderedCollection(privacies) }
      : {})
  }
}

export const recommendationSceneFingerprint = (
  snapshot: RecommendationSceneSnapshotLike
): string => JSON.stringify(canonicalBoardSnapshotValue({
  nodes: canonicalUnorderedCollection(snapshot.nodes, recommendationNodeValue),
  environmentVariables: canonicalUnorderedCollection(snapshot.environmentVariables),
  // Rule order is execution order and therefore remains part of the fingerprint.
  rules: snapshot.rules.map(canonicalBoardSnapshotValue),
  specifications: canonicalUnorderedCollection(snapshot.specifications),
  deviceTemplates: canonicalUnorderedCollection(snapshot.deviceTemplates)
}))

export const hasRecommendationSceneChanged = (
  current: RecommendationSceneSnapshotLike | null,
  incoming: RecommendationSceneSnapshotLike
): boolean => current !== null
  && recommendationSceneFingerprint(current) !== recommendationSceneFingerprint(incoming)

export type ConfirmedBoardItemStatus = 'current' | 'scene-changed' | 'item-changed'

export const getConfirmedBoardItemStatus = <T extends { id?: string }>(
  expectedGeneration: number,
  currentGeneration: number,
  replacementInProgress: boolean,
  collection: readonly T[],
  itemId: string,
  expectedItem: T,
  snapshotOf: (item: T) => unknown = item => item
): ConfirmedBoardItemStatus => {
  if (replacementInProgress || expectedGeneration !== currentGeneration) return 'scene-changed'
  const currentItem = collection.find(item => item.id === itemId)
  const canonical = (value: unknown) => JSON.stringify(canonicalBoardSnapshotValue(value))
  return currentItem && canonical(snapshotOf(currentItem)) === canonical(snapshotOf(expectedItem))
    ? 'current'
    : 'item-changed'
}

export type FormalRunKind = 'verification' | 'simulation'
export type FormalRunReadinessIssue =
  | 'NO_DEVICES'
  | 'NO_SPECIFICATIONS'
  | 'RULE_TRIGGER_REQUIRED'
  | 'SPEC_VARIABLE_SOURCE_REQUIRED'
  | 'INVALID_SIMULATION_STEPS'

export const formalRunReadinessIssue = (
  kind: FormalRunKind,
  input: {
    deviceCount: number
    specificationCount: number
    rulesHaveTriggers: boolean
    simulationStepsValid: boolean
    specVariableSourcesResolved: boolean
  }
): FormalRunReadinessIssue | null => {
  if (input.deviceCount <= 0) return 'NO_DEVICES'
  if (kind === 'verification' && input.specificationCount <= 0) return 'NO_SPECIFICATIONS'
  if (!input.rulesHaveTriggers) return 'RULE_TRIGGER_REQUIRED'
  // A stored condition that never said which value it means is unresolved, not defaultable: the
  // request would be refused at admission, and either guess changes what the specification asserts.
  if (!input.specVariableSourcesResolved) return 'SPEC_VARIABLE_SOURCE_REQUIRED'
  if (kind === 'simulation' && !input.simulationStepsValid) return 'INVALID_SIMULATION_STEPS'
  return null
}

</script>

<script setup lang="ts">
/* =================================================================================
 * 1. Imports & Setup
 * ================================================================================= */
import FieldSelect from '@/components/common/FieldSelect.vue'
import {ref, shallowRef, reactive, computed, defineAsyncComponent, nextTick, onMounted, onBeforeUnmount, watch, watchPostEffect, h, type Ref} from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useChatStore } from '@/stores/chat'
import { useAuth } from '@/stores/auth'
import { subscribeBoardInvalidation } from '@/utils/boardInvalidation'
import { ACTION_DOCK_RAIL_PX, BOARD_FLOATING_GAP_CSS, COLLAPSED_PANEL_RAIL_PX } from '@/constants/boardLayout'
import ProductMark from '@/components/common/ProductMark.vue'
// Icons

// Types
import type {DeviceDialogMeta, DeviceTemplate, InternalVariable} from '../types/device'
import type { BoardLayoutDto, CanvasPan } from '../types/canvas'
import type { BoardUndoResult } from '@/types/boardEdit'
import type { DeviceNode } from '../types/node'
import type { DeviceEdge } from '../types/edge'
import type { RuleForm, RuleSourceItemType } from '../types/rule'
import type { SpecCondition, Specification, SpecTemplateId } from '../types/spec'
import type {
  ModelGenerationIssue,
  PersistedTrace,
  Trace,
  TraceDevice,
  TraceSummary,
  TraceState,
  TraceTriggeredRule,
  TraceVariable,
  VerificationResult,
  VerificationRun,
  VerificationRunSummary,
  VerificationTask,
  VerificationTaskSummary
} from '@/types/verify'
import type { SimulationResult, SimulationTask, SimulationTaskSummary, SimulationTraceSummary } from '@/types/simulation'
import { simulationResultFromTrace, summarizeSimulationResult, type SavedSimulationResult } from './board/simulationResult'
import type { AttackScenario, AttackScenarioMode } from '@/types/attackScenario'
import type {
  AvailableFuzzingRunSummary,
  FuzzingExplorationMode,
  FuzzingFinding,
  FuzzingFindingSummary,
  FuzzingInputEvent,
  FuzzPaperDomainPreview,
  FuzzWorkloadPreview,
  FuzzingRequest,
  FuzzingRun,
  FuzzingRunSummary,
  FuzzingTask,
  FuzzingTaskSummary
} from '@/types/fuzzing'
import { isValidFuzzPaperDomainFingerprint } from '@/types/fuzzing'
import { compareModelSnapshot } from '@/utils/modelComparison'
import type { BoardModelFingerprints, ModelRunSnapshot, ModelSemantics, RunBoardComparison } from '@/types/modelSemantics'
import type {
  EnvironmentVariableUpdateRequest,
  ModelEnvironmentVariable,
  ModelPlaybackScene,
  RunInitiator
} from '@/types/model'
import { isRunInitiator } from '@/types/model'
import type { InteractiveOperationStage, TaskCancellationResult, TaskProgressStage } from '@/types/task'
import type { FixApplyResult } from '@/types/fix'
import type { PortableSceneFile } from '@/types/scene'
// Panel types removed

// Utils
import { getNodeIcon as resolveNodeIcon, indexDeviceTemplates } from '../utils/device'
import {
  acknowledge,
  acknowledgeFailure,
  confirmChoice,
  confirmDestructive,
  dismissAllNotifications,
  dismissCompletionNotifications,
  notifyBlocked,
  notifyAvailableResult,
  notifyError,
  notifyInfo,
  notifySuccess
} from '@/utils/feedback'
import { getVerificationOutcome, presentSpecificationResult } from './board/verificationResult'
import {
  applyBoardRunTarget,
  hasUnusableBoardRunParams,
  isSameBoardRunTarget,
  parseBoardRunTarget,
  type BoardRunTarget
} from './board/runDeepLink'
import {
  createBoardSemanticCommit,
  reconcileBoardFocus,
  type BoardSemanticScene
} from './board/semanticCommit'
import { createFocusHighlight } from './board/focusHighlight'
import { smvUnavailableReasonKey } from './board/smvUnavailableReason'
import { buildPlaybackEdges } from './board/playbackScene'
import {
  formatRecommendationFilteredItem as formatFilteredItem,
  formatRecommendationFilteredType as formatFilteredType
} from './board/recommendationFilterText'
import {
  formatSceneValidationCoordinate,
  getStructuredValidationErrors,
  readBoardReplacementStalePreview
} from './board/sceneImportDiagnostics'
import { createDeviceInstanceId, deviceLabelKey, getUniqueLabel } from '../utils/canvas/nodeCreate'
import { screenToWorld } from '../utils/canvas/geometry'
import {
  buildSpecDeviceRefsFromConditions,
  buildSpecificationSemanticKey,
  buildSpecFormula,
  isSameSpecification,
  isSpecRelatedToNode,
  specificationsWithUnresolvedVariableSource
} from '../utils/spec'
import { assertRuleHasTrigger, getLinkPoints, ruleSimilarityReasonKey } from '../utils/rule'
import {
  deriveTraceContext,
  formatTraceSpec,
  normalizePlaybackDeviceId,
  playbackDeviceChangeDetails,
  playbackEnvironmentChangeDetails,
  type PlaybackDeviceChange,
  type PlaybackEnvironmentChange
} from '@/utils/traceView'
import { buildTraceEdgePlaybackStates } from '@/utils/traceEdgePlayback'
import {
  buildSimulationRequestPayload,
  buildVerificationRequestPayload,
  normalizeModelRelation,
  specificationsRequirePrivacy
} from '@/utils/modelRequest'
import { isModelSemanticsConsistent } from '@/utils/modelSemantics'
import {
  ATTACK_POINT_HARD_MAX,
  analyzeBoardAttackSurface,
  getAttackScenarioIssue,
  getAttackSelectionIssue,
  selectedAttackPoints
} from './board/attackSurface'
import { recommendedReadingKey } from './board/recommendedReadingSuffix'
import { errorDetails, technicalErrorLines, localizedErrorMessage, localizedTextOrFallback, type ApiErrorDetails } from '@/utils/userMessage'
import { resolveImpactEnvironmentDefinition } from '@/utils/device'
import { diagnoseJsonParseError } from '@/utils/jsonParseDiagnostics'
import FailureDetails from '@/components/FailureDetails.vue'
import { requestInteractiveCancellation } from '@/utils/interactiveCancellation'
import {
  isRecommendationPostOutcomeUnknown,
  isRecommendationRequestActive,
  planRecommendationRecoveryAfterStatusFailure,
  prepareOwnedRecommendationForLogout,
  refreshRecommendationOwnerCredential,
  requestIdAfterTerminalSettlement,
  type RecommendationRequestOwner
} from '@/utils/recommendationRequestRecovery'
import { REQUEST_LIMITS } from '@/constants/requestLimits'
import { RECOMMENDATION_RESPONSE_INCOMPLETE_CODE } from '@/utils/recommendationResponse'
import { sceneTemplatesCoveredByCatalog } from '@/utils/sceneTemplateCoverage'
import {
  createSceneCodec,
  normalizeTemplateLookupName,
  SCENE_FILE_SCHEMA,
  SCENE_FILE_VERSION,
  type BoardSceneModel
} from './board/portableScene'
import {
  FUZZ_RESPONSE_INCOMPLETE_CODE,
  getFuzzActiveTaskLimit,
  getFuzzStoredTaskLimit
} from '@/utils/fuzzingResponse'
import {
  FUZZ_INLINE_RESULT_RECOVERY_MAX_FAILURES,
  classifyTrackedFuzzRunError,
  fuzzNotificationStorageKeyForUser,
  fuzzRunRetryDelayMs,
  isTransientTaskHttpStatus
} from './board/fuzzingRecovery'
import {
  createPagedRequestCoordinator,
  type PagedRequestToken
} from '@/utils/pagedRequestCoordinator'
import { generationIssueReasonKey } from '@/utils/generationIssue'
import {
  hasValidFuzzingBudget,
  isFuzzingPreviewCurrent,
  FUZZ_PATH_LENGTH_MAX,
  FUZZ_DEFAULT_MAX_ITERATIONS,
  FUZZ_DEFAULT_PATH_LENGTH,
  FUZZ_DEFAULT_POPULATION_SIZE,
  getFuzzingConfigurationIssue,
  isKnownFuzzingSpecificationSupported
} from '@/utils/fuzzingConfig'
import {
  RecommendationCandidateError,
  materializeRuleRecommendation,
  materializeSpecificationRecommendationConditions
} from '@/utils/recommendationMaterialization'
import {
  RUN_RESPONSE_INCOMPLETE_CODE,
  activeTaskProgressStage,
  hasPersistedVerificationTrace,
  validateCompletedVerificationTask
} from '@/utils/runResponse'
import {
  PRIVACY_OPTIONS,
  TRUST_OPTIONS,
  buildDeviceRuntimeConfig,
  createDeviceRuntimeDraft,
  deviceRuntimeConfigsEqual,
  findTemplateStatePrivacy,
  findTemplateStateTrust,
  getTemplateLocalVariables,
  getTemplateEnvironmentVariables,
  getTemplateVariableDefaultValue,
  templateVariableIsStateDerived,
  syncStateDerivedVariables,
  getTemplateWorkingStates,
  resetDeviceRuntimeDraft,
  templateVariableHasEnumValues,
  templateVariableUsesNumericBounds,
  validateTemplateVariableValue,
  validateDeviceRuntimeConfig,
  type DeviceRuntimeConfig
} from '@/utils/deviceRuntime'
import { getNodeAccentColor } from '@/utils/canvas/nodePalette'
import { hasModeledStateMachine, resolveEffectiveNodeState } from '@/utils/canvas/nodeState'
import {
  formatBuiltInModelToken,
  formatModelTokenBySource,
  formatModelTokenForTemplate
} from '@/utils/modelTokenDisplay'
import type { ModelTokenSource } from '@/types/modelToken'

// Config
import { defaultSpecTemplates, specTemplateDetails } from '../assets/config/specTemplates'

// API
import boardApi, {
  BOARD_RESPONSE_INCOMPLETE_CODE,
  type BoardReplacementPreview,
  type DeviceLayout,
  type DeviceRecommendation,
  type EnvironmentVariablePatchResult,
  type EnvironmentVariableChange,
  type RecommendationAdjustmentItem,
  type RecommendationFilteredItem,
  type ScenarioRecommendationResponse,
  type SpecificationRecommendation
} from '@/api/board'
import simulationApi from '@/api/simulation'
import fuzzingApi from '@/api/fuzzing'
import rulesApi, { type RuleRecommendation } from '@/api/rules'

// Components
import CanvasBoard from '../components/CanvasBoard.vue'
import ControlCenter from '../components/ControlCenter.vue'
import SystemInspector from '../components/SystemInspector.vue'
import LanguageToggle from '@/components/common/LanguageToggle.vue'
import ThemeToggle from '@/components/common/ThemeToggle.vue'
import InfoTooltip from '@/components/common/InfoTooltip.vue'
import BackgroundTaskSummary from '@/components/BackgroundTaskSummary.vue'
import { describeSpecification, presentRule } from '@/utils/modelPresentation'
import ToggleSwitch from '@/components/common/ToggleSwitch.vue'
import ScenarioObjectiveIssues from '@/components/ScenarioObjectiveIssues.vue'
import { useModalAccessibility } from '@/composables/useModalAccessibility'
import { openModalDepth } from '@/composables/useBodyScrollLock'
import { useBoardUndo } from '@/composables/useBoardUndo'

const DeviceDialog = defineAsyncComponent(() => import('../components/DeviceDialog.vue'))
const SimulationTimeline = defineAsyncComponent(() => import('../components/SimulationTimeline.vue'))
const FixResultDialog = defineAsyncComponent(() => import('../components/FixResultDialog.vue'))
const RecommendationProgressStatus = defineAsyncComponent(
  () => import('../components/RecommendationProgressStatus.vue')
)
const PlaybackChangesPanel = defineAsyncComponent(() => import('../components/PlaybackChangesPanel.vue'))
const PlaybackTerminal = defineAsyncComponent(() => import('../components/PlaybackTerminal.vue'))
const PlaybackStateDetails = defineAsyncComponent(() => import('../components/PlaybackStateDetails.vue'))
const FuzzingPanel = defineAsyncComponent(() => import('../components/FuzzingPanel.vue'))
const FuzzingResultDialog = defineAsyncComponent(() => import('../components/FuzzingResultDialog.vue'))
const TraceHistoryPanel = defineAsyncComponent(() => import('../components/TraceHistoryPanel.vue'))

const { t, te, locale } = useI18n()
const router = useRouter()
const route = useRoute()
const chatStore = useChatStore()
const { toggleChat } = chatStore
const hasAssistantWork = computed(() => chatStore.state.streaming
  || chatStore.state.activeCount > 0
  || chatStore.state.reconciliationRequired)
const assistantButtonLabel = computed(() => {
  if (chatStore.state.reconciliationRequired) {
    return t('app.chat.reconciliationPendingStatus', {
      active: chatStore.state.activeCount,
      unread: chatStore.state.unreadCount
    })
  }
  if (chatStore.state.activeCount > 0) {
    return chatStore.state.unreadCount > 0
      ? t('app.chat.runningAndUnreadResults', {
          active: chatStore.state.activeCount,
          unread: chatStore.state.unreadCount
        })
      : t('app.chat.runningSessions', { count: chatStore.state.activeCount })
  }
  return chatStore.state.unreadCount > 0
    ? t('app.chat.unreadResults', { count: chatStore.state.unreadCount })
    : t('app.aiAssistant')
})
const { state: authState, getToken, isCurrentSessionToken } = useAuth()

/**
 * Set once the deep-link layer is initialised. The result-surface closers are declared
 * before it, so they clear the URL through this hook instead of forcing a large reorder.
 */
let clearRunDeepLink: () => void = () => {}

const isOpeningAccount = ref(false)
type InteractiveLogoutPreparation = 'ready' | 'outcome-unknown'
const fixResultDialogRef = ref<{
  canOpenTrace?: (traceId: number) => boolean
  prepareForLogout?: () => Promise<InteractiveLogoutPreparation>
} | null>(null)
const currentUser = computed(() => authState.user)
const currentAuthUserId = computed(() => currentUser.value?.userId ?? null)
// Teardown can flush after shared auth changes, so retain the account that mounted this board.
const boardOwnerUserId = currentAuthUserId.value
const isAuthScopeTransitioning = ref(false)
let boardAuthScopeEpoch = 0
const isCurrentBoardAuthScope = (epoch: number) =>
  !boardLifecycleDisposed && epoch === boardAuthScopeEpoch

type FuzzUnreadNotification = {
  taskId: number
  kind: 'COMPLETED' | 'FAILED' | 'UNAVAILABLE'
  runId?: number
  outcome?: string
  createdAt: string
  initiator?: RunInitiator
}

const unreadFuzzNotifications = ref<FuzzUnreadNotification[]>([])
const trackedFuzzTaskIds = ref<number[]>([])
const unreadFuzzNotificationCount = computed(() => unreadFuzzNotifications.value.length)
const unreadFailedFuzzCount = computed(() =>
  unreadFuzzNotifications.value.filter(item => item.kind === 'FAILED').length)

const openAccount = () => {
  if (isOpeningAccount.value) return
  void router.push({ name: 'account', query: { returnTo: route.fullPath } })
}

onBeforeRouteLeave(async to => {
  if (to.name !== 'account') return true
  if (isOpeningAccount.value) return false
  if (isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return false
  }
  const requestToken = getToken()
  const requestAuthScopeEpoch = boardAuthScopeEpoch
  const ownsNavigation = () => requestToken !== null
    && isCurrentBoardAuthScope(requestAuthScopeEpoch) && isCurrentSessionToken(requestToken)
  if (!ownsNavigation()) return false
  isOpeningAccount.value = true
  try {
    let recommendations: InteractiveLogoutPreparation = 'outcome-unknown'
    let fixSearch: InteractiveLogoutPreparation = 'outcome-unknown'
    try {
      const preparation = await Promise.all([
        prepareActiveRecommendationsForLogout(),
        fixResultDialogRef.value?.prepareForLogout?.() ?? Promise.resolve('ready' as const)
      ])
      recommendations = preparation[0]
      fixSearch = preparation[1]
    } catch (error) {
      console.warn('Could not confirm local interactive work stopped before account navigation', error)
    }
    if (!ownsNavigation()) return false
    if (recommendations === 'outcome-unknown' || fixSearch === 'outcome-unknown') {
      const proceed = await confirmChoice({
        title: t('account.workspaceStopUnknownTitle'),
        message: t('account.workspaceStopUnknownMessage'),
        confirmText: t('account.openPage')
      })
      if (!ownsNavigation() || !proceed) return false
    }
    // Node geometry and semantic edits share this queue; the layout flush only owns view state.
    let settledQueue: Promise<void>
    do {
      settledQueue = boardMutationQueue
      await waitForPendingBoardMutations()
      if (!ownsNavigation()) return false
      try {
        await flushPendingBoardLayout({ silent: true, timeoutMs: LAYOUT_LOGOUT_FLUSH_TIMEOUT_MS })
      } catch {
        // A failed view-layout save does not prevent account access; semantic writes were awaited.
      }
      if (!ownsNavigation()) return false
    } while (settledQueue !== boardMutationQueue)
    const hasLocalResult = Boolean(verificationResult.value
      && verificationResult.value.historyPersistence.status !== 'SAVED')
      || Boolean(lastSimulationResult.value
        && lastSimulationResult.value.historyPersistence.status !== 'SAVED')
      || unsavedRunNotifications.size > 0
      || (activePlaybackKind.value === 'counterexample' && Boolean(currentTrace.value)
        && currentTrace.value?.id === undefined)
    if (hasLocalResult) {
      const proceed = await confirmChoice({
        title: t('account.localResultTitle'),
        message: t('account.localResultMessage'),
        confirmText: t('account.leaveWorkspace')
      })
      if (!ownsNavigation() || !proceed) return false
    }
    // Existing assistant work can reconcile another write while the discard decision is open.
    while (settledQueue !== boardMutationQueue) {
      settledQueue = boardMutationQueue
      await waitForPendingBoardMutations()
      if (!ownsNavigation()) return false
    }
    return ownsNavigation()
  } finally {
    isOpeningAccount.value = false
  }
})

/* =================================================================================
 * 2. Constants & Configuration
 * ================================================================================= */

// Panel constants removed

const NODE_GRID_COLS = 4
const DEFAULT_NODE_WIDTH = 176
const DEFAULT_NODE_HEIGHT = 128
const NODE_SPACING_X = 220
const NODE_SPACING_Y = 164

const MIN_ZOOM = 0.4
const MAX_ZOOM = 2
const ZOOM_STEP = 0.1
const LAYOUT_SAVE_DEBOUNCE_MS = 700
const LAYOUT_LOGOUT_FLUSH_TIMEOUT_MS = 1_500
const DEFAULT_CONTROL_PANEL_WIDTH = 320
const DEFAULT_INSPECTOR_PANEL_WIDTH = 320

/* Both collapsed-rail and dock-rail widths now live in `constants/boardLayout.ts`, because the panels
   themselves are the other consumer and a comment in three files is not an owner. */

const ASYNC_TASK_POLL_INTERVAL_MS = 1000
const ASYNC_TASK_MAX_POLLS = 600
const TASK_INBOX_REFRESH_INTERVAL_MS = 5000
const AI_RECOMMENDATION_REQUIREMENT_MAX_LENGTH = 2000
let pollingEpoch = 0
let boardLifecycleDisposed = false

const formatBoardEnvironmentModelToken = (
  name: string,
  value: unknown,
  bundledNames: readonly string[] = bundledBoardEnvironmentNames.value
): string => bundledNames.includes(name)
  ? formatBundledModelToken(value)
  : String(value ?? '')

const formatEnvironmentSnapshot = (
  variable: ModelEnvironmentVariable | null | undefined,
  bundledNames: readonly string[] = bundledBoardEnvironmentNames.value
): string => {
  if (!variable) return ''
  const displayName = formatBoardEnvironmentModelToken(variable.name, variable.name, bundledNames)
  const labels = [
    formatBoardEnvironmentModelToken(variable.name, variable.value, bundledNames),
    variable.trust ? t(`app.${variable.trust}`) : '',
    variable.privacy ? t(`app.${variable.privacy}`) : ''
  ].filter(Boolean)
  return labels.length > 0 ? `${displayName}: ${labels.join(' · ')}` : displayName
}

const formatEnvironmentChange = (change: EnvironmentVariableChange): string => {
  if (change.changeType === 'ADDED') {
    return t('app.environmentChangeAdded', { item: formatEnvironmentSnapshot(change.currentValue) })
  }
  if (change.changeType === 'UPDATED') {
    return t('app.environmentChangeUpdated', {
      before: formatEnvironmentSnapshot(change.previousValue),
      after: formatEnvironmentSnapshot(change.currentValue)
    })
  }
  return t('app.environmentChangeRemoved', { item: formatEnvironmentSnapshot(change.previousValue) })
}

const reportEnvironmentChanges = (
  changes: EnvironmentVariableChange[] | null | undefined,
  bundledNames: readonly string[] = bundledBoardEnvironmentNames.value,
  silent = false
) => {
  if (silent) return
  const values = Array.isArray(changes) ? changes : []
  const added = values.filter(change => change.changeType === 'ADDED')
    .map(change => formatEnvironmentSnapshot(change.currentValue, bundledNames))
  const updated = values.filter(change => change.changeType === 'UPDATED')
    .map(change => `${formatEnvironmentSnapshot(change.previousValue, bundledNames)} -> ${formatEnvironmentSnapshot(change.currentValue, bundledNames)}`)
  const removed = values.filter(change => change.changeType === 'REMOVED')
    .map(change => formatEnvironmentSnapshot(change.previousValue, bundledNames))
  if (added.length > 0) notifyInfo(t('app.environmentPoolAddedByDeviceChange', { items: added.join(', ') }))
  if (updated.length > 0) notifyInfo(t('app.environmentPoolUpdatedByDeviceChange', { items: updated.join(', ') }))
  if (removed.length > 0) notifyInfo(t('app.environmentPoolRemovedByDeviceChange', { items: removed.join(', ') }))
}

const MAX_SCENE_IMPORT_BYTES = REQUEST_LIMITS.sceneBytes

const ensureBoardItemCapacity = (
  resource: 'devices' | 'rules' | 'specifications',
  currentCount: number,
  additionalCount: number,
  maximum: number
) => {
  if (currentCount + additionalCount <= maximum) return true
  notifyBlocked(t('app.boardCapacityReached', {
    resource: t(`app.${resource}`),
    limit: maximum
  }))
  return false
}

const ensureNestedItemCapacity = (resource: string, count: number, maximum: number) => {
  if (count <= maximum) return true
  notifyBlocked(t('app.itemLimitReached', { resource, limit: maximum }))
  return false
}

const ensureDeviceRuntimeCapacity = (runtime?: DeviceRuntimeConfig) =>
  ensureNestedItemCapacity(
    t('app.deviceVariables'), runtime?.variables?.length || 0, REQUEST_LIMITS.deviceVariables
  ) && ensureNestedItemCapacity(
    t('app.devicePrivacies'), runtime?.privacies?.length || 0, REQUEST_LIMITS.devicePrivacies
  )


type ScenarioRecommendationResult = {
  message: string
  count: number
  requestedCount: number
  validatedCount: number
  filteredCount: number
  filteredItems: RecommendationFilteredItem[]
  adjustedCount: number
  adjustedItems: RecommendationAdjustmentItem[]
  rawCandidateCount: number
  inspectedCount: number
  truncatedCount: number
  scenarioName: string
  rationale: string
  objectiveTargets: ScenarioRecommendationResponse['objectiveTargets']
  objectiveStatus: ScenarioRecommendationResponse['objectiveStatus']
  objectiveIssues: ScenarioRecommendationResponse['objectiveIssues']
  verificationReady: boolean
  readinessIssues: ScenarioRecommendationResponse['readinessIssues']
  semanticWarnings: ScenarioRecommendationResponse['semanticWarnings']
  scene: BoardSceneModel | null
}

class PollingAbortedError extends Error {
  constructor() {
    super('Polling aborted')
    this.name = 'PollingAbortedError'
  }
}

class AsyncTaskCancelledError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'AsyncTaskCancelledError'
  }
}

class FuzzTaskRecoveryPendingError extends Error {
  constructor() {
    super('Fuzz task or completed result is awaiting recovery')
    this.name = 'FuzzTaskRecoveryPendingError'
  }
}

class FuzzCompletedResultUnavailableError extends Error {
  constructor(readonly originalError: unknown) {
    super('Completed exploration result could not be read')
    this.name = 'FuzzCompletedResultUnavailableError'
  }
}

class CompletedTaskResultUnavailableError extends Error {
  readonly kind: 'verification' | 'simulation'

  constructor(kind: 'verification' | 'simulation', readonly originalError: unknown) {
    super('Completed task result could not be read')
    this.name = 'CompletedTaskResultUnavailableError'
    this.kind = kind
  }
}

const throwIfPollingAborted = (expectedEpoch = pollingEpoch) => {
  if (boardLifecycleDisposed || expectedEpoch !== pollingEpoch) {
    throw new PollingAbortedError()
  }
}

const isPollingAbortedError = (error: unknown): boolean =>
  error instanceof PollingAbortedError

const isAsyncTaskCancelledError = (error: unknown): boolean =>
  error instanceof AsyncTaskCancelledError

const isFuzzTaskRecoveryPendingError = (error: unknown): boolean =>
  error instanceof FuzzTaskRecoveryPendingError

const isFuzzCompletedResultUnavailableError = (error: unknown): boolean =>
  error instanceof FuzzCompletedResultUnavailableError

const isCompletedTaskResultUnavailableError = (
  error: unknown
): error is CompletedTaskResultUnavailableError =>
  error instanceof CompletedTaskResultUnavailableError

const waitForNextPoll = async (expectedEpoch = pollingEpoch) => {
  await new Promise(resolve => setTimeout(resolve, ASYNC_TASK_POLL_INTERVAL_MS))
  throwIfPollingAborted(expectedEpoch)
}

const waitForPollingDelay = async (delayMs: number, expectedEpoch = pollingEpoch) => {
  await new Promise(resolve => setTimeout(resolve, delayMs))
  throwIfPollingAborted(expectedEpoch)
}

/* =================================================================================
 * 3. State Definitions
 * ================================================================================= */

// --- Canvas State ---
const canvasZoom = ref(1)
const isCanvasHovered = ref(false)
const canvasPan = ref<CanvasPan>({ x: 0, y: 0 })

let isPanning = false
let canvasPanPointerId: number | null = null
let canvasPanTarget: HTMLElement | null = null
let panStart = { x: 0, y: 0 }
let panOrigin = { x: 0, y: 0 }
let layoutSaveTimer: ReturnType<typeof setTimeout> | null = null
let layoutSaveFeedbackSuppressed = false
let persistedWideLayout: BoardLayoutDto | null = null

type ControlCenterSection = 'devices' | 'templates' | 'rules' | 'specs'
type InspectorSection = 'devices' | 'rules' | 'specs' | 'environment'

const isNarrowViewport = () =>
  typeof window !== 'undefined'
  && (window.innerWidth < 1024 || window.innerHeight < 600)

let wasNarrowViewport = isNarrowViewport()

const isControlCenterSection = (value?: string): value is ControlCenterSection =>
  value === 'devices' || value === 'templates' || value === 'rules' || value === 'specs'

const isInspectorSection = (value?: string): value is InspectorSection =>
  value === 'devices' || value === 'rules' || value === 'specs' || value === 'environment'

const clampPanelWidth = (value: unknown, fallback: number): number => {
  const width = typeof value === 'number' ? value : fallback
  if (!Number.isFinite(width)) return fallback
  return Math.min(520, Math.max(240, Math.round(width)))
}

const boardPanels = reactive({
  control: {
    collapsed: isNarrowViewport(),
    width: DEFAULT_CONTROL_PANEL_WIDTH,
    activeSection: 'templates' as ControlCenterSection
  },
  inspector: {
    collapsed: isNarrowViewport(),
    width: DEFAULT_INSPECTOR_PANEL_WIDTH,
    activeSection: 'devices' as InspectorSection
  }
})

type ActionDockMode = 'expanded' | 'compact' | 'packed'

const actionDockViewportWidth = ref(typeof window !== 'undefined' ? window.innerWidth : 1440)
const boardViewportHeight = ref(typeof window !== 'undefined' ? window.innerHeight : 900)
const playbackPanelOpen = ref(true)
const playbackTerminalTab = ref<'changes' | 'details' | 'run'>('changes')
const isNarrowBoardLayout = computed(() =>
  actionDockViewportWidth.value < 1024 || boardViewportHeight.value < 600
)
const actionDockPreferredMode = ref<ActionDockMode>('expanded')

/**
 * The modes this viewport can actually show, widest first.
 *
 * One source for a rule that was previously written four times in four shapes: two hardcoded cycle arrays
 * picked by a `>= 1280` check, a separate clamp restating `< 1280`, a `restoreActionDockFromPacked` that
 * re-derived the same answer, and a `>= 1280` ternary inline in the launcher's `aria-label`. They agreed by
 * coincidence, and a width rule added to one would have had to be remembered in the other three.
 *
 * Everything else about the dock's mode is now derived from this list: the effective mode is the preferred one
 * if the viewport allows it and the widest available otherwise; the toggle advances through it; and restoring
 * from packed returns to the widest.
 */
const availableActionDockModes = computed<ActionDockMode[]>(() =>
  // The labelled rail needs 1280px: below that the labels do not fit beside two side panels. The icon rail
  // fits at every width, so it stays selectable even on a phone — a user who chose it keeps it.
  actionDockViewportWidth.value < 1280 ? ['compact', 'packed'] : ['expanded', 'compact', 'packed'])

const actionDockMode = computed<ActionDockMode>(() => {
  const available = availableActionDockModes.value
  if (available.includes(actionDockPreferredMode.value)) return actionDockPreferredMode.value
  // Below 720px a rail of any width leaves no usable canvas, so an unavailable preference falls back to the
  // launcher rather than to the widest option. This is the one place the two thresholds differ, and losing
  // that distinction would have opened the icon rail by default on a phone.
  return actionDockViewportWidth.value < 720 ? 'packed' : available[0]
})

const isActionDockPackedMode = computed(() => actionDockMode.value === 'packed')
const nextActionDockPreferredMode = computed<ActionDockMode>(() => {
  const available = availableActionDockModes.value
  const index = available.indexOf(actionDockMode.value)
  return available[(index + 1) % available.length]
})
const ACTION_DOCK_MODE_AFFORDANCES = {
  expanded: { icon: 'chevron_left', label: 'app.actionDockSwitchExpanded' },
  compact: { icon: 'chevron_right', label: 'app.actionDockSwitchCompact' },
  packed: { icon: 'toolbar', label: 'app.actionDockSwitchPacked' }
} as const
const actionDockToggleIcon = computed(() =>
  ACTION_DOCK_MODE_AFFORDANCES[nextActionDockPreferredMode.value].icon)
const actionDockToggleLabel = computed(() =>
  t(ACTION_DOCK_MODE_AFFORDANCES[nextActionDockPreferredMode.value].label))
/** Packed mode's launcher restores the widest mode, so it names that mode rather than re-deriving a width. */
const actionDockRestoreLabel = computed(() =>
  t(ACTION_DOCK_MODE_AFFORDANCES[availableActionDockModes.value[0]].label))
const cycleActionDockMode = () => {
  actionDockPreferredMode.value = nextActionDockPreferredMode.value
}
const restoreActionDockFromPacked = () => {
  actionDockPreferredMode.value = availableActionDockModes.value[0]
}
const hasActionDockActivity = computed(() =>
  isSimulating.value ||
  isFuzzing.value ||
  isVerifying.value ||
  simulationAnimationState.value.visible ||
  traceAnimationState.value.visible ||
  isRecommendingScenario.value ||
  isAnyRecommendationRunning() ||
  unreadFuzzNotificationCount.value > 0
)

// Dock button tooltip content - one source of truth for each button's hover hint
const simulationTooltipContent = computed(() => {
  const status = isSimulating.value || simulationAnimationState.value.visible
    ? t('app.simulationRunning')
    : t('app.openSimulationSettings')
  return `${status}\n${t('app.outcomeSimulation')}`
})
const fuzzingTooltipContent = computed(() => {
  const status = isSceneReplacementInProgress.value
    ? t('app.sceneReplacementInProgress')
    : isFuzzing.value
      ? t('app.fuzzRunning')
      : t('app.openFuzzSettings')
  return `${status}\n${t('app.outcomeExploration')}`
})
const verificationTooltipContent = computed(() => {
  const status = isVerifying.value
    ? t('app.verifying')
    : t('app.openVerificationSettings')
  return `${status}\n${t('app.outcomeVerification')}`
})
const scenarioTooltipContent = computed(() => t('app.openScenarioRecommendations'))
const ruleTooltipContent = computed(() => t('app.openRuleRecommendations'))
const deviceTooltipContent = computed(() => t('app.openDeviceRecommendations'))
const specTooltipContent = computed(() => t('app.openSpecificationRecommendations'))
/*
 * The rail width table owns the paint width; the reserved width adds the gap the dock is inset by, which
 * the fit math needs and the paint does not.
 */
/**
 * The gap between the inspector's edge and the dock, in px.
 *
 * One owner, because the fit math has to subtract the same gap the dock is positioned by. It previously
 * added `8`/`16` of its own on top of a separately guessed reserved width, so the corridor it believed in
 * differed from the corridor the dock occupied by up to 12px.
 */
const actionDockGapPx = computed(() => actionDockViewportWidth.value < 640 ? 8 : 14)
const actionDockRailPx = computed(() => ACTION_DOCK_RAIL_PX[actionDockMode.value])
const actionDockRailWidth = computed(() => `${actionDockRailPx.value}px`)
const actionDockReservedWidth = computed(() => actionDockRailPx.value + actionDockGapPx.value)
const widePanelWidthLimit = computed(() => {
  if (isNarrowBoardLayout.value) return 520
  const viewportWidth = actionDockViewportWidth.value
  const actionRailWidth = actionDockReservedWidth.value
  const minimumCanvasCorridor = viewportWidth < 1280 ? 220 : 280
  const reservedGaps = viewportWidth < 1280 ? 56 : 64
  return Math.min(520, Math.max(
    240,
    Math.floor((viewportWidth - actionRailWidth - minimumCanvasCorridor - reservedGaps) / 2)
  ))
})
const effectiveControlPanelWidth = computed(() =>
  Math.min(boardPanels.control.width, widePanelWidthLimit.value))
const effectiveInspectorPanelWidth = computed(() =>
  Math.min(boardPanels.inspector.width, widePanelWidthLimit.value))
const actionDockRightInset = computed(() => {
  const inspectorWidth = boardPanels.inspector.collapsed ? COLLAPSED_PANEL_RAIL_PX : effectiveInspectorPanelWidth.value
  return inspectorWidth + actionDockGapPx.value
})
const actionDockStyle = computed(() => ({
  '--board-action-dock-right': `${actionDockRightInset.value}px`,
  '--board-action-dock-width': actionDockRailWidth.value
}))

const updateActionDockViewport = () => {
  if (typeof window === 'undefined') return
  const narrow = isNarrowViewport()
  if (!wasNarrowViewport && narrow && layoutHydrated.value) {
    if (layoutSaveTimer) {
      clearTimeout(layoutSaveTimer)
      layoutSaveTimer = null
    }
    persistedWideLayout = buildBoardLayoutPayload()
    void saveBoardLayout({ silent: true })
  }
  actionDockViewportWidth.value = window.innerWidth
  boardViewportHeight.value = window.innerHeight
  if (narrow) {
    applyViewportPanelConstraints()
    if (!wasNarrowViewport && layoutHydrated.value) {
      const visibleNodes = getVisibleDeviceNodes()
      if (visibleNodes.length > 0) {
        void nextTick(() => fitNodesToCanvas(visibleNodes))
      }
    }
  } else if (wasNarrowViewport && persistedWideLayout) {
    applyBoardLayout(persistedWideLayout)
  }
  wasNarrowViewport = narrow
}

const layoutHydrated = ref(false)
const boardLayoutLoadError = ref<ApiErrorDetails | null>(null)
const loadingBoardLayout = ref(false)
let layoutSaveErrorShown = false
let panelStateTouchedBeforeLayout = false
let canvasStateTouchedBeforeLayout = false

/*
 * The variables the fixed overlays are positioned by, injected where they can actually be seen.
 *
 * `--board-floating-gap` is declared on `.iot-board`, but the two timeline hosts are **siblings** of it, not
 * descendants — they are `position: fixed` on purpose, above every panel. So inside them `var(--board-floating-gap)`
 * was unresolved, `calc()` became invalid at computed-value time, and both `left` and `right` fell back to `auto`.
 * A fixed box with `left: auto; right: auto` shrink-wraps its content at its static position, which is x=0: the
 * trace overlay sat flush against the left edge of the screen with the whole right side empty.
 *
 * Measured: `left` computed to `0px` against a declared `calc(56px + 16px)`, and the host's width was **identical
 * at 2556px and 1440px** (859.859px) — the tell that it was sizing to content rather than to the corridor. On a
 * 101-state trace the shrink-wrap grew the host to 3258px, putting the play button at x=2086 and off-screen at a
 * laptop viewport: the playback controls became unreachable.
 *
 * `e91a109` removed the `var(…, 1rem)` fallbacks that had been hiding this, on the premise that the gap is
 * declared at `:root`. It is not. Restoring the fallback would only hide it again — a fixed element positioned by
 * variables it cannot see is the actual defect, so the variables are injected onto it instead.
 */
const playbackToolbarStacked = computed(() => {
  const width = isNarrowBoardLayout.value
    ? actionDockViewportWidth.value - 32
    : actionDockViewportWidth.value
      - (boardPanels.control.collapsed ? COLLAPSED_PANEL_RAIL_PX : effectiveControlPanelWidth.value)
      - (boardPanels.inspector.collapsed ? COLLAPSED_PANEL_RAIL_PX : effectiveInspectorPanelWidth.value)
  return width < 640
})

const boardShellStyle = computed(() => ({
  '--board-control-width': `${boardPanels.control.collapsed ? COLLAPSED_PANEL_RAIL_PX : effectiveControlPanelWidth.value}px`,
  '--board-inspector-width': `${boardPanels.inspector.collapsed ? COLLAPSED_PANEL_RAIL_PX : effectiveInspectorPanelWidth.value}px`,
  '--board-action-rail-width': actionDockRailWidth.value,
  '--board-floating-gap': BOARD_FLOATING_GAP_CSS,
  '--board-status-height': !isBoardDataReady.value && failedBoardDataKeys.value.length === 0 ? '2.25rem' : '0px',
  '--board-playback-height': isModelPlaybackActive.value && playbackPanelOpen.value
    ? boardViewportHeight.value < 600 ? 'clamp(7rem, 36dvh, 14rem)'
      : 'clamp(14rem, 32dvh, 20rem)'
    : '0px',
  '--board-playback-toolbar-height': isModelPlaybackActive.value ? (playbackToolbarStacked.value ? '128px' : '88px') : '0px'
}))

const applyViewportPanelConstraints = () => {
  if (!isNarrowViewport()) return
  boardPanels.control.collapsed = true
  boardPanels.inspector.collapsed = true
}

const closeNarrowSidePanels = () => {
  boardPanels.control.collapsed = true
  boardPanels.inspector.collapsed = true
}

const openNarrowPanelName = computed<NarrowPanelName | null>(() => {
  if (!isNarrowBoardLayout.value) return null
  if (!boardPanels.control.collapsed) return 'control'
  if (!boardPanels.inspector.collapsed) return 'inspector'
  return null
})
const showNarrowPanelScrim = computed(() => openNarrowPanelName.value !== null)

const boardPanelScrimRef = ref<HTMLButtonElement | null>(null)
const getOpenNarrowPanel = () => document.querySelector<HTMLElement>(
  !boardPanels.control.collapsed
    ? '[data-testid="control-center"].is-expanded'
    : '[data-testid="system-inspector"].is-expanded'
)

const focusSidePanelTab = (panel: HTMLElement | null) => {
  panel?.querySelector<HTMLElement>('[role="tab"][aria-selected="true"]')?.focus({ preventScroll: true })
}

const handleBoardFocusIn = (event: FocusEvent) => {
  const panel = getOpenNarrowPanel()
  if (shouldRedirectNarrowPanelFocus(
    showNarrowPanelScrim.value,
    event.target,
    panel,
    boardPanelScrimRef.value
  )) {
    focusSidePanelTab(panel)
  }
}

watch(openNarrowPanelName, (open, previous) => {
  void nextTick(() => {
    if (open) {
      const panel = getOpenNarrowPanel()
      if (shouldRedirectNarrowPanelFocus(
        true,
        document.activeElement,
        panel,
        boardPanelScrimRef.value
      )) {
        focusSidePanelTab(panel)
      }
      return
    }

    if (previous && isNarrowBoardLayout.value) {
      focusCollapsedNarrowPanelToggle(previous)
    }
  })
})

watch(actionDockViewportWidth, applyViewportPanelConstraints, { immediate: true })

// --- Core Data State ---
const deviceTemplates = ref<DeviceTemplate[]>([])
const templatesLoading = ref(false)
const nodes = ref<DeviceNode[]>([])
const activePlaybackScene = ref<ModelPlaybackScene | null>(null)
const playbackCanvasPan = ref<CanvasPan>({ x: 0, y: 0 })
const playbackCanvasZoom = ref(1)
let playbackViewportFollowsFit = true
let playbackCanvasFrame: ReturnType<typeof getVisibleCanvasFrame> = null
const environmentVariables = ref<ModelEnvironmentVariable[]>([])
const environmentMutationPending = ref(false)
const edges = ref<DeviceEdge[]>([])
const rules = ref<RuleForm[]>([])  // 独立存储规则列表
const rulesReordering = ref(false)
/*
 * "Show me where that is" — a one-shot cue owned by `board/focusHighlight.ts`, not a selection.
 *
 * These three used to be independently written refs, mutually exclusive only because each setter remembered
 * to null the other two, and cleared only by whoever happened to think of it. Five exits did not, so a
 * device kept an infinitely pulsing halo indefinitely. The controller expires the cue on a timer, which
 * makes a missed exit cost a second of highlight instead of a permanent one. The refs below are now
 * *derived* — nothing assigns them except the controller's `onChange`.
 */
const focusedNodeId = ref<string | null>(null)
const focusedRuleId = ref<string | null>(null)
const focusedSpecId = ref<string | null>(null)
const focusHighlight = createFocusHighlight({
  onChange: target => {
    focusedNodeId.value = target?.kind === 'node' ? target.id : null
    focusedRuleId.value = target?.kind === 'rule' ? target.id : null
    focusedSpecId.value = target?.kind === 'spec' ? target.id : null
  },
  setTimer: (callback, delayMs) => window.setTimeout(callback, delayMs),
  clearTimer: handle => window.clearTimeout(handle)
})
onBeforeUnmount(() => focusHighlight.dispose())
const reconcileDanglingBoardFocus = (
  scene: Pick<BoardSemanticScene, 'nodes' | 'rules' | 'specs'>
) => {
  // Deleting the focused item is the one case that must not wait for the timer: the cue would keep painting
  // an id that no longer exists. `reconcileBoardFocus` stays the owner of "does this still exist".
  focusHighlight.reconcile(target => {
    const survivor = reconcileBoardFocus({
      nodeId: target.kind === 'node' ? target.id : null,
      ruleId: target.kind === 'rule' ? target.id : null,
      specId: target.kind === 'spec' ? target.id : null
    }, scene)
    return Boolean(survivor.nodeId ?? survivor.ruleId ?? survivor.specId)
  })
}
const sceneImportInputRef = ref<HTMLInputElement | null>(null)
const sceneActionsMenuRef = ref<HTMLDetailsElement | null>(null)
const sceneActionsMenuOpen = ref(false)
const closeSceneActionsMenu = (restoreFocus = false) => {
  const menu = sceneActionsMenuRef.value
  if (!menu) return
  menu.removeAttribute('open')
  sceneActionsMenuOpen.value = false
  if (restoreFocus) {
    menu.querySelector<HTMLElement>('summary')?.focus()
  }
}
const handleSceneActionsMenuToggle = () => {
  sceneActionsMenuOpen.value = sceneActionsMenuRef.value?.open === true
}
const isImportingScene = ref(false)
const isClearingScene = ref(false)
const isSceneReplacementInProgress = computed(() => isImportingScene.value || isClearingScene.value)
// Destructive confirmations are fenced only by explicit full-scene replacement.
// Recommendations use a separate generation because every semantic mutation
// makes their model context stale, while an unrelated edit need not cancel an
// exact rule/specification deletion confirmation.
let boardSceneGeneration = 0
let recommendationSceneGeneration = 0

const deepClone = <T,>(value: T): T =>
  JSON.parse(JSON.stringify(value))

const getVisibleDeviceNodes = (source: DeviceNode[] = nodes.value): DeviceNode[] =>
  [...source]

const cloneVisibleDeviceNodes = (): DeviceNode[] =>
  deepClone(getVisibleDeviceNodes())

// 画布只展示由用户规则派生的可见连线；模板内部变量保留在 manifest 中，不再生成用户可见节点/边。
const playbackEdges = computed(() => activePlaybackScene.value
  ? buildPlaybackEdges(activePlaybackScene.value)
  : [])
const renderedCanvasNodes = computed(() => activePlaybackScene.value?.nodes || nodes.value)
const allEdges = computed(() => activePlaybackScene.value ? playbackEdges.value : edges.value)
const renderedCanvasPan = computed(() => activePlaybackScene.value ? playbackCanvasPan.value : canvasPan.value)
const renderedCanvasZoom = computed(() => activePlaybackScene.value ? playbackCanvasZoom.value : canvasZoom.value)
const specifications = ref<Specification[]>([])

type BoardDataKey = 'templates' | 'nodes' | 'environment' | 'rules' | 'specs'
type BoardDataLoadState = 'loading' | 'ready' | 'error'

const boardDataLoadState = reactive<Record<BoardDataKey, BoardDataLoadState>>({
  templates: 'loading',
  nodes: 'loading',
  environment: 'loading',
  rules: 'loading',
  specs: 'loading'
})
const allBoardDataKeys: BoardDataKey[] = ['templates', 'nodes', 'environment', 'rules', 'specs']
const boardSnapshotError = ref<ApiErrorDetails | null>(null)
// A failed refresh must not erase ownership of the last accepted snapshot;
// otherwise the next retry could accept a changed scene without fencing stale work.
let hydratedBoardAuthScopeEpoch: number | null = null
const failedBoardDataKeys = computed(() =>
  allBoardDataKeys.filter(key => boardDataLoadState[key] === 'error'))
const isBoardDataReady = computed(() =>
  !isAuthScopeTransitioning.value
  && allBoardDataKeys.every(key => boardDataLoadState[key] === 'ready'))

// A scene replacement must fence every canvas mutation, including pan/zoom and
// keyboard movement.  Waiting for the authoritative snapshot also prevents a
// failed initial load from leaving a locally movable, non-persisted canvas.
const isCanvasInteractionLocked = computed(() =>
  isModelPlaybackActive.value
  || isSceneReplacementInProgress.value
  || isAuthScopeTransitioning.value
  || boardDataLoadState.nodes !== 'ready')

// Read-only playback locks semantic/layout edits, but it must remain navigable. Playback owns a
// separate viewport, so panning or zooming an old scene cannot alter the live board layout.
const isCanvasNavigationLocked = computed(() =>
  isSceneReplacementInProgress.value
  || isAuthScopeTransitioning.value
  || boardDataLoadState.nodes !== 'ready')

const boardDataKeyLabel = (key: BoardDataKey): string => t(`app.boardDataKey_${key}`)

const ensureBoardDataReady = (keys: BoardDataKey[] = allBoardDataKeys): boolean => {
  const unavailable = keys.filter(key => boardDataLoadState[key] !== 'ready')
  if (unavailable.length === 0) return true
  notifyError(t('app.boardDataEditBlocked', {
    collections: unavailable.map(boardDataKeyLabel).join(', ')
  }))
  return false
}

// 创建节点索引以优化查找性能
const nodesById = computed(() => {
  const map = new Map<string, DeviceNode>()
  for (const node of nodes.value) {
    map.set(node.id, node)
  }
  return map
})

const resolveNodeRef = (refValue?: string | null): DeviceNode | undefined => {
  if (!refValue) return undefined
  return nodesById.value.get(refValue)
}

const assertRulesHaveTriggers = (candidateRules: RuleForm[]): boolean => {
  try {
    candidateRules.forEach((rule, index) => assertRuleHasTrigger(rule, index))
    return true
  } catch (error: any) {
    notifyBlocked(t('app.ruleTriggerSourceRequired'))
    return false
  }
}

const getGenerationIssues = (result: Pick<VerificationResult, 'generationIssues'> | null): ModelGenerationIssue[] =>
  result?.generationIssues ?? []

const getGenerationWarningCounts = (
  result: Pick<VerificationResult, 'disabledRuleCount' | 'skippedSpecCount'> | null
) => {
  const disabledRuleCount = result?.disabledRuleCount ?? 0
  const skippedSpecCount = result?.skippedSpecCount ?? 0
  return { disabledRuleCount, skippedSpecCount, total: disabledRuleCount + skippedSpecCount }
}

const getSimulationDisabledRuleCount = (result: SimulationResult | null): number =>
  result?.disabledRuleCount ?? 0

const isSimulationModelComplete = (result: SimulationResult | null): boolean =>
  result?.modelComplete === true

const getSimulationActualStepCount = (result: SimulationResult | null): number => result?.steps ?? 0

const getSimulationRequestedStepCount = (result: SimulationResult | null): number => result?.requestedSteps ?? 0

const isSimulationModelSemanticsConsistent = (result: any): boolean =>
  isModelSemanticsConsistent(result?.modelSemantics, {
    isAttack: result?.isAttack,
    attackBudget: result?.attackBudget,
    enablePrivacy: result?.enablePrivacy
  })

const notifySimulationOutcome = (result: any, saved: boolean) => {
  const stateCount = result?.states?.length || 0
  const disabledRuleCount = getSimulationDisabledRuleCount(result)
  if (!isSimulationModelComplete(result)) {
    notifyBlocked(t('app.simulationCompletedWithDisabledRules', {
        states: stateCount,
        rules: disabledRuleCount,
        saved: saved ? t('app.savedToHistorySuffix') : ''
      }))
    return
  }
  notifySuccess(saved
      ? t('app.simulationTaskCompletedSaved', { count: stateCount })
      : t('app.simulationCompletedWithStates', { count: stateCount }))
}

const extractApiErrorMessage = (error: any, fallback: string): string => {
  if (error?.code === BOARD_RESPONSE_INCOMPLETE_CODE) {
    return t('app.boardMutationResponseIncomplete')
  }
  if (error?.code === RUN_RESPONSE_INCOMPLETE_CODE) {
    return t('app.runResponseIncomplete')
  }
  if (error?.code === FUZZ_RESPONSE_INCOMPLETE_CODE) {
    return t('app.fuzzResponseIncomplete')
  }
  return localizedErrorMessage(error, fallback, locale.value)
}

const hasApiValidationError = (error: any, field: string): boolean => {
  const status = Number(error?.response?.status || 0)
  const fieldError = error?.response?.data?.data?.errors?.[field]
  return (status === 400 || status === 422)
    && typeof fieldError === 'string'
    && fieldError.trim().length > 0
}

const fuzzTaskQuotaMessage = (error: any): string | null => {
  const quota = getFuzzActiveTaskLimit(error)
  if (quota) {
    if (quota.activeTaskCount === undefined || quota.maxActiveTasksPerUser === undefined) {
      return t('app.fuzzActiveTaskLimitGeneric')
    }
    return t('app.fuzzActiveTaskLimitReached', {
      active: quota.activeTaskCount,
      limit: quota.maxActiveTasksPerUser
    })
  }
  const storedQuota = getFuzzStoredTaskLimit(error)
  if (!storedQuota) return null
  if (storedQuota.storedTaskCount === undefined
    || storedQuota.maxStoredTasksPerUser === undefined) {
    return t('app.fuzzStoredTaskLimitGeneric')
  }
  return t('app.fuzzStoredTaskLimitReached', {
    stored: storedQuota.storedTaskCount,
    limit: storedQuota.maxStoredTasksPerUser
  })
}

const asyncTaskQuotaMessage = (
  error: any,
  kind: 'verification' | 'simulation'
): string | null => {
  if (Number(error?.response?.status) !== 429) return null
  const data = error?.response?.data?.data
  const prefix = kind.toUpperCase()
  if (data?.reasonCode !== `${prefix}_ACTIVE_TASK_LIMIT_REACHED`
    && data?.reasonCode !== `${prefix}_STORED_TASK_LIMIT_REACHED`) return null
  const count = Number(data.taskCount)
  const limit = Number(data.maxTasksPerUser)
  const detailed = Number.isInteger(count) && Number.isInteger(limit)
  if (data.quotaType === 'ACTIVE') {
    return detailed
      ? t('app.asyncTaskActiveLimitReached', { count, limit })
      : t('app.asyncTaskActiveLimitGeneric')
  }
  return detailed
    ? t('app.asyncTaskStoredLimitReached', { count, limit })
    : t('app.asyncTaskStoredLimitGeneric')
}

const formalOperationBusyMessage = (error: any): string | null => {
  if (Number(error?.response?.status) !== 429) return null
  if (error?.response?.data?.data?.reasonCode !== 'USER_FORMAL_OPERATION_BUSY') return null
  return t('app.formalOperationBusy')
}

const extractRecommendationErrorMessage = (error: any, fallback: string): string => {
  if (error?.code === RECOMMENDATION_RESPONSE_INCOMPLETE_CODE) {
    return t('app.recommendationResponseIncomplete')
  }
  if (error instanceof RecommendationCandidateError) {
    return t('app.recommendationInvalidFieldNoChange', { field: error.field })
  }
  return localizedErrorMessage(error, fallback, locale.value)
}

const isDefinitiveMutationRejection = (error: any): boolean => {
  const status = Number(error?.response?.status || 0)
  return status >= 400 && status < 500
}

type AsyncTaskStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

const taskProgressStatusKey = (
  stage?: TaskProgressStage | null,
  status?: AsyncTaskStatus | string
): string => {
  const activeStage = activeTaskProgressStage(stage, status)
  if (activeStage) return `app.taskProgressStage_${activeStage}`
  switch (status) {
    case 'PENDING':
      return 'app.taskStatusPending'
    case 'RUNNING':
      return 'app.taskStatusRunning'
    case 'COMPLETED':
      return 'app.taskStatusCompleted'
    case 'FAILED':
      return 'app.taskStatusFailed'
    case 'CANCELLED':
      return 'app.taskStatusCancelled'
    default:
      return 'app.taskInitializing'
  }
}

const formatTaskProgressStage = (
  stage?: TaskProgressStage | null,
  status?: AsyncTaskStatus | string
): string => t(taskProgressStatusKey(stage, status))

const formatWatchedTaskStatus = (statusKey: string, cancelling: boolean): string =>
  t(cancelling ? 'app.taskCancelling' : statusKey)

const buildVerificationResultFromTask = (task: VerificationTask, traces: Trace[] = []): VerificationResult => {
  const completedTask = validateCompletedVerificationTask(task)
  return {
    outcome: completedTask.outcome,
    traces,
    specResults: completedTask.specResults,
    checkLogs: completedTask.checkLogs,
    disabledRuleCount: completedTask.disabledRuleCount,
    skippedSpecCount: completedTask.skippedSpecCount,
    generationIssues: completedTask.generationIssues,
    isAttack: completedTask.isAttack,
    attackBudget: completedTask.attackBudget,
    enablePrivacy: completedTask.enablePrivacy,
    modelSemantics: completedTask.modelSemantics,
    modelSnapshot: completedTask.modelSnapshot,
    historyPersistence: { status: 'SAVED', runId: completedTask.id },
    modelComplete: completedTask.modelComplete,
    nusmvOutput: completedTask.nusmvOutput,
    // Same reason as in buildVerificationResultFromRun: a completed async task *is* the run, and its
    // response carries the flag the download button is gated on.
    hasSmvModel: completedTask.hasSmvModel
  }
}

const buildVerificationResultFromRun = (run: VerificationRun, traces: Trace[] = []): VerificationResult => ({
  outcome: run.outcome,
  traces,
  specResults: run.specResults,
  checkLogs: run.checkLogs,
  disabledRuleCount: run.disabledRuleCount,
  skippedSpecCount: run.skippedSpecCount,
  generationIssues: run.generationIssues,
  isAttack: run.isAttack,
  attackBudget: run.attackBudget,
  enablePrivacy: run.enablePrivacy,
  modelSemantics: run.modelSemantics,
  modelSnapshot: run.modelSnapshot,
  historyPersistence: { status: 'SAVED', runId: run.id },
  modelComplete: run.modelComplete,
  nusmvOutput: run.nusmvOutput,
  // Carried through, or the result dialog's SMV download cannot render for a run reopened from
  // history: the backend sends the flag and dropping it here made the feature look absent.
  hasSmvModel: run.hasSmvModel
})

const isVerificationModelComplete = (
  result: any,
  _outcome = getVerificationOutcome(result)
): boolean => result?.modelComplete === true

const getSpecResultDisplayTitle = (spec: Specification | undefined, index: number): string => {
  const detail = specTemplateDetails.find(template => template.id === spec?.templateId)
  if (detail?.labelKey) return t(detail.labelKey)
  return spec?.templateLabel || detail?.label || t('app.specificationNumber', { number: index + 1 })
}

const getTraceSpecDisplayTitle = (trace: (Pick<Trace, 'violatedSpecId' | 'violatedSpec'> & Partial<Pick<Trace, 'states'>>) | null | undefined): string => {
  if (!trace) return t('app.unknown')
  if (trace.violatedSpec) {
    const summary = formatTraceSpec(trace.violatedSpec, t, Object.fromEntries(
      (trace.states?.[0]?.devices || []).map(device => [device.deviceId, device.modelTokenSource])
    ))
    if (summary) return summary
  }
  return t('app.unknownSpecification')
}

const countVerificationFailures = (result: Pick<VerificationResult, 'specResults' | 'traces'> | null): number => {
  const failedSpecs = (result?.specResults || [])
    .filter(specResult => specResult.outcome === 'VIOLATED').length
  const traceCount = result?.traces?.length || 0
  return Math.max(failedSpecs, traceCount)
}

const getVerificationFailureMessage = (result: VerificationResult | null): string => {
  if (getVerificationOutcome(result) === 'INCONCLUSIVE') {
    return t('app.verificationInconclusiveSummary')
  }
  const failureCount = countVerificationFailures(result)
  return failureCount > 0
    ? t('app.verificationFailedWithViolations', { count: failureCount })
    : t('app.verificationFailedNoSpecResults')
}

const notifyVerificationOutcome = (result: any, options?: { presenting?: boolean }) => {
  // Suppress the toast when the dialog is already presenting the verdict
  if (options && options.presenting) {
    return
  }

  const counts = getGenerationWarningCounts(result)
  const verificationOutcome = getVerificationOutcome(result)
  const modelComplete = isVerificationModelComplete(result, verificationOutcome)
  if (!modelComplete) {
    if (counts.total === 0) {
      const message = verificationOutcome === 'SATISFIED'
        ? t('app.verificationSatisfiedButIncomplete')
        : verificationOutcome === 'INCONCLUSIVE'
          ? t('app.verificationInconclusiveSummary')
          : getVerificationFailureMessage(result)
      notifyBlocked(message)
      return
    }
    const outcome = verificationOutcome === 'SATISFIED'
      ? t('app.verificationPassed')
      : getVerificationFailureMessage(result)
    notifyBlocked(t('app.generationWarningSummary', {
        outcome,
        total: counts.total,
        disabledRuleCount: counts.disabledRuleCount,
        skippedSpecCount: counts.skippedSpecCount
      }))
    return
  }

  if (verificationOutcome === 'SATISFIED') {
    notifySuccess(t('app.verificationSatisfiedComplete'))
  } else if (verificationOutcome === 'INCONCLUSIVE') {
    notifyBlocked(t('app.verificationInconclusiveSummary'))
  } else {
    notifyBlocked(getVerificationFailureMessage(result))
  }
}

const draggingTplName = ref<string | null>(null)
const templateInstanceDialogVisible = ref(false)
const templateInstanceSaving = ref(false)
const templateInstanceDialogData = reactive({
  template: null as DeviceTemplate | null,
  name: '',
  position: { x: 0, y: 0 }
})

const templateInstanceRuntime = reactive(createDeviceRuntimeDraft())

// Picking a state re-derives the variables it constrains, so this editor cannot submit a pair the
// writers refuse. Same rule as the device dialog, through the same helper.
watch(() => templateInstanceRuntime.state, state => {
  if (!state) return
  syncStateDerivedVariables(templateInstanceRuntime.variables, templateInstanceDialogData.template, state)
})

const templateInstanceWorkingStates = computed(() =>
  getTemplateWorkingStates(templateInstanceDialogData.template)
)

const templateInstanceInternalVariables = computed(() =>
  getTemplateLocalVariables(templateInstanceDialogData.template)
)

const templateInstanceHasModes = computed(() => {
  const manifest = templateInstanceDialogData.template?.manifest
  return Array.isArray(manifest?.Modes)
    && manifest.Modes.length > 0
    && templateInstanceWorkingStates.value.length > 0
})

const templateInstanceHasRuntimeFields = computed(() =>
  Boolean(templateInstanceDialogData.template && (templateInstanceHasModes.value || templateInstanceInternalVariables.value.length > 0))
)

const getTemplateRequiredEnvironmentNames = (template?: DeviceTemplate | null): string[] => {
  if (!template) return []
  const names = new Set<string>()
  getTemplateEnvironmentVariables(template).forEach(variable => {
    const name = String(variable.Name || '').trim()
    if (name) names.add(name)
  })
  ;(template.manifest.ImpactedVariables || []).forEach(rawName => {
    const name = String(rawName || '').trim()
    if (name) names.add(name)
  })
  return Array.from(names).sort((left, right) => left.localeCompare(right))
}

const getMissingTemplateEnvironmentNames = (template?: DeviceTemplate | null): string[] => {
  const existing = new Set(environmentVariables.value.map(variable => variable.name))
  return getTemplateRequiredEnvironmentNames(template).filter(name => !existing.has(name))
}

const templateInstanceEnvironmentAdditions = computed(() =>
  getMissingTemplateEnvironmentNames(templateInstanceDialogData.template)
)

const recommendedDeviceEnvironmentAdditions = (recommendation: DeviceRecommendation): string[] =>
  getMissingTemplateEnvironmentNames(findTemplateByAnyName(recommendation.templateName))

const resetTemplateInstanceRuntime = (template = templateInstanceDialogData.template) => {
  resetDeviceRuntimeDraft(templateInstanceRuntime, template)
}

watch(() => templateInstanceDialogData.template, template => {
  resetTemplateInstanceRuntime(template)
})

const templateVariableInputPlaceholder = (variable: InternalVariable) => {
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

const buildTemplateInstanceRuntimeConfig = (template: DeviceTemplate): DeviceRuntimeConfig | undefined => {
  return buildDeviceRuntimeConfig(template, templateInstanceRuntime, { variableScope: 'local' })
}

const validateTemplateInstanceRuntimeConfig = (template: DeviceTemplate, runtime?: DeviceRuntimeConfig) => {
  return validateDeviceRuntimeConfig(template, runtime, t, { variableScope: 'local' })
}

let boardMutationQueue: Promise<void> = Promise.resolve()
let boardMutationAdmissionEpoch = 0
let nodeLayoutMutationVersion = 0
const pendingNodeLayouts = new Map<string, { version: number; layout: DeviceLayout }>()
const activeNodeLayoutInteractions = new Set<string>()
let externallyRefreshedRemovedNodeIds: ReadonlySet<string> | null = null

const replaceNodesFromServer = (
  source: DeviceNode[],
  options: { externalRefresh?: boolean } = {}
) => {
  const incomingNodes = getVisibleDeviceNodes(source)
  const incomingIds = new Set(incomingNodes.map(node => node.id))
  externallyRefreshedRemovedNodeIds = options.externalRefresh
    ? new Set(nodes.value.filter(node => !incomingIds.has(node.id)).map(node => node.id))
    : null
  try {
    nodes.value = reconcileBoardNodeSnapshot(
      nodes.value,
      incomingNodes,
      pendingNodeLayouts,
      activeNodeLayoutInteractions
    )
  } finally {
    externallyRefreshedRemovedNodeIds = null
  }
  for (const nodeId of pendingNodeLayouts.keys()) {
    if (!incomingIds.has(nodeId)) pendingNodeLayouts.delete(nodeId)
  }
}

const handleNodeLayoutInteractionStart = (nodeId: string) => {
  activeNodeLayoutInteractions.add(nodeId)
}

const handleNodeLayoutInteractionEnd = (nodeId: string) => {
  activeNodeLayoutInteractions.delete(nodeId)
}

interface BoardMutationQueueOptions {
  admissionGuard?: () => boolean
  trackSemanticChange?: boolean
  onSemanticChange?: () => void
}

interface CreateDeviceInstanceOptions extends BoardMutationQueueOptions {
  onConfirmedCreate?: () => void
  onFailure?: (error: unknown, refreshed: boolean) => void
}

const getCurrentRecommendationSceneFingerprint = (
  expectedAuthScopeEpoch: number
): string | null => {
  if (!isCurrentBoardAuthScope(expectedAuthScopeEpoch)
    || hydratedBoardAuthScopeEpoch !== expectedAuthScopeEpoch) return null
  return recommendationSceneFingerprint({
    deviceTemplates: deviceTemplates.value,
    nodes: nodes.value,
    environmentVariables: environmentVariables.value,
    rules: rules.value,
    specifications: specifications.value
  })
}

const enqueueBoardMutation = async <T,>(
  work: () => Promise<T>,
  {
    admissionGuard,
    trackSemanticChange = true,
    onSemanticChange
  }: BoardMutationQueueOptions = {}
): Promise<T> => {
  boardMutationAdmissionEpoch += 1
  const authScopeEpoch = boardAuthScopeEpoch
  const guardedWork = () => {
    if (!isCurrentBoardAuthScope(authScopeEpoch)) {
      return Promise.reject(new PollingAbortedError())
    }
    return runAdmittedBoardMutation(() => {
      const previousSceneFingerprint = trackSemanticChange
        ? getCurrentRecommendationSceneFingerprint(authScopeEpoch)
        : null
      return runTrackedBoardMutation(
        work,
        previousSceneFingerprint,
        () => trackSemanticChange
          ? getCurrentRecommendationSceneFingerprint(authScopeEpoch)
          : null,
        () => {
          // The verified model no longer matches the board, regardless of how this mutation
          // chooses to treat recommendations. Mark staleness here so no onSemanticChange
          // override can leave a stale verdict claiming to describe the current canvas.
          markVerificationResultStale()
          if (onSemanticChange) onSemanticChange()
          else invalidateRecommendationsForSceneChange({ notify: true })
        }
      )
    }, admissionGuard)
  }
  const next = boardMutationQueue.then(guardedWork, guardedWork)
  boardMutationQueue = next.then(() => undefined, () => undefined)
  return next
}

const waitForPendingBoardMutations = async () => {
  await boardMutationQueue
}

const applyLayoutToNode = (node: DeviceNode | undefined, layout: DeviceLayout) => {
  if (!node) return
  node.position = { ...layout.position }
  node.width = layout.width
  node.height = layout.height
}

const deviceLayoutMatches = (node: DeviceNode | undefined, layout: DeviceLayout) =>
  !!node
  && node.position.x === layout.position.x
  && node.position.y === layout.position.y
  && node.width === layout.width
  && node.height === layout.height

const deviceRuntimeMatches = (
  node: DeviceNode | undefined,
  runtime: DeviceRuntimeConfig,
  template: DeviceTemplate
) => {
  if (!node) return false
  return deviceRuntimeConfigsEqual(template, node, runtime, {
    includeEmptyCollections: true,
    variableScope: 'local'
  })
}

const deviceRuntimeSnapshot = (node: DeviceNode): DeviceRuntimeConfig => ({
  state: node.state,
  ...(node.currentStateTrust != null ? { currentStateTrust: node.currentStateTrust } : {}),
  ...(node.currentStatePrivacy != null ? { currentStatePrivacy: node.currentStatePrivacy } : {}),
  variables: deepClone(node.variables || []),
  privacies: deepClone(node.privacies || [])
})

// --- UI State ---
const dialogVisible = ref(false)
const deviceDialogRef = ref<DeviceDialogCloseController | null>(null)
let deviceDialogReturnFocusNodeId: string | null = null
let renameDialogReturnFocusNodeId: string | null = null
let deleteDialogReturnFocusNodeId: string | null = null
const dialogMeta = reactive<DeviceDialogMeta>({
  nodeId: '',
  deviceName: '',
  label: '',
  manifest: null,
  specs: []
})
const deviceRuntimeSaving = ref(false)

// Custom dialog states
const renameDialogVisible = ref(false)
const renameDialogSubmitting = ref(false)
const renameDialogData = reactive({
  node: null as DeviceNode | null,
  newName: '',
  originalLabel: ''
})
const renameNameConflict = computed(() => nodes.value.some(node =>
  node.id !== renameDialogData.node?.id
  && deviceLabelKey(node.label) === deviceLabelKey(renameDialogData.newName)))
const renameNameBlockedReason = computed(() => {
  const name = renameDialogData.newName.trim()
  if (!name) return t('app.enterDeviceName')
  if (name === renameDialogData.originalLabel) return t('app.enterDifferentDeviceName')
  return renameNameConflict.value ? t('app.deviceNameAlreadyExists') : ''
})

const deleteConfirmDialogVisible = ref(false)
const deleteConfirmSubmitting = ref(false)
const deletePreviewLoading = ref(false)
const deleteConfirmReviewSnapshotKey = ref<string | null>(null)
let deletePreviewRequestEpoch = 0
let deletePreviewNodeId: string | null = null
let deleteConfirmSourceDeviceDialogNodeId: string | null = null
const deleteConfirmDialogData = reactive({
  node: null as DeviceNode | null,
  hasRelations: false,
  relationCount: {
    rules: 0,
    specs: 0
  },
  relatedRules: [] as string[],
  relatedSpecs: [] as string[],
  environmentChanges: [] as EnvironmentVariableChange[],
  impactToken: ''
})

const currentDeletionReviewSnapshotKey = () => {
  const targetId = deleteConfirmDialogData.node?.id
  const targetNode = resolveCurrentBoardNode(nodes.value, targetId)
  const impactedEnvironmentNames = new Set(
    deleteConfirmDialogData.environmentChanges.map(change => change.name)
  )
  const isEnvironmentProviderTemplate = (template: DeviceTemplate) =>
    getTemplateEnvironmentVariables(template)
      .some(variable => impactedEnvironmentNames.has(variable.Name))
  const environmentProviderNodes = nodes.value
    .filter(node => node.id !== targetId && deviceTemplates.value.some(template =>
      isEnvironmentProviderTemplate(template) && templateMatchesName(template, node.templateName)))
    .map(node => ({ id: node.id, templateName: node.templateName }))
    .sort((left, right) => left.id.localeCompare(right.id))
  const relevantTemplates = deviceTemplates.value.filter(template =>
    templateMatchesName(template, targetNode?.templateName)
    || environmentProviderNodes.some(node => templateMatchesName(template, node.templateName))
  )
  const relatedRules = targetId
    ? rules.value.filter(rule => rule.toId === targetId
      || rule.contentDevice === targetId
      || rule.sources.some(source => source.fromId === targetId))
    : []
  const relatedSpecifications = targetId
    ? specifications.value.filter(specification => isSpecRelatedToNode(specification, targetId))
    : []

  return JSON.stringify(canonicalBoardSnapshotValue({
    targetNode,
    relatedRules,
    relatedSpecifications,
    environmentChanges: deleteConfirmDialogData.environmentChanges,
    environmentVariables: environmentVariables.value
      .filter(variable => impactedEnvironmentNames.has(variable.name))
      .sort((left, right) => left.name.localeCompare(right.name)),
    environmentProviderNodes,
    relevantTemplates: [...relevantTemplates]
      .sort((left, right) => `${left.name}:${left.id ?? ''}`.localeCompare(`${right.name}:${right.id ?? ''}`))
  }))
}

const invalidateDeletePreview = () => {
  deletePreviewRequestEpoch += 1
  deletePreviewNodeId = null
  deletePreviewLoading.value = false
}

const clearDeleteConfirmDialog = () => {
  invalidateDeletePreview()
  deleteConfirmSourceDeviceDialogNodeId = null
  deleteConfirmReviewSnapshotKey.value = null
  deleteConfirmDialogVisible.value = false
  deleteConfirmDialogData.node = null
  deleteConfirmDialogData.hasRelations = false
  deleteConfirmDialogData.relationCount = { rules: 0, specs: 0 }
  deleteConfirmDialogData.relatedRules = []
  deleteConfirmDialogData.relatedSpecs = []
  deleteConfirmDialogData.environmentChanges = []
  deleteConfirmDialogData.impactToken = ''
}

/* =================================================================================
 * 4. Helper Functions (Styles & Calculation)
 * ================================================================================= */

// getCardWidth removed


const templateMatchesName = (template: DeviceTemplate, name: unknown): boolean => {
  const target = normalizeTemplateLookupName(name)
  if (!target) return false
  return [template.name, template.manifest?.Name]
    .some(candidate => normalizeTemplateLookupName(candidate) === target)
}

const templatesByName = computed(() => indexDeviceTemplates(deviceTemplates.value))

const findTemplateByAnyName = (name: unknown): DeviceTemplate | undefined =>
  templatesByName.value.get(normalizeTemplateLookupName(name))

const resolveTemplateForNode = (node: DeviceNode): DeviceTemplate | null => {
  return findTemplateByAnyName(node.templateName) || null
}

const resolvePresentationTemplateForNode = (node: DeviceNode): DeviceTemplate | null =>
  shouldResolveLiveTemplateForPresentation(activePlaybackScene.value !== null)
    ? resolveTemplateForNode(node)
    : null

const isBundledDeviceTemplate = (template?: DeviceTemplate | null): boolean =>
  template?.defaultTemplate === true

const formatBundledModelToken = (value: unknown): string => formatBuiltInModelToken(
  value,
  key => te(key) ? t(key) : key
)

const formatNodeModelToken = (node: DeviceNode, value: unknown): string =>
  isBundledDeviceTemplate(resolvePresentationTemplateForNode(node))
    ? formatBundledModelToken(value)
    : String(value ?? '')

const formatTemplateModelToken = (template: DeviceTemplate | null | undefined, value: unknown): string =>
  formatModelTokenForTemplate(template, value, key => te(key) ? t(key) : key)

const formatRecommendedDeviceModelToken = (recommendation: DeviceRecommendation, value: unknown): string =>
  formatTemplateModelToken(findTemplateByAnyName(recommendation.templateName), value)

const formatRecommendedDeviceEnvironmentAdditions = (recommendation: DeviceRecommendation): string =>
  recommendedDeviceEnvironmentAdditions(recommendation)
    .map(name => formatRecommendedDeviceModelToken(recommendation, name))
    .join(', ')

const getTemplateEnvironmentNames = (template?: DeviceTemplate | null): string[] => {
  const manifest = template?.manifest
  if (!manifest) return []
  const names = new Set<string>()
  ;(manifest.InternalVariables || []).forEach(variable => {
    if (variable.IsInside !== true && variable.Name?.trim()) names.add(variable.Name.trim())
  })
  ;(manifest.ImpactedVariables || []).forEach(name => {
    if (name?.trim()) names.add(name.trim())
  })
  return [...names]
}

type ModelTokenDevice = {
  templateName?: string | null
  modelTokenSource?: ModelTokenSource | null
}

const getBundledEnvironmentNames = (devices: readonly ModelTokenDevice[]): string[] => {
  return collectBundledEnvironmentNames(devices.map(device => {
    const template = findTemplateByAnyName(device.templateName)
    return {
      bundled: isBundledDeviceTemplate(template),
      names: getTemplateEnvironmentNames(template)
    }
  }))
}

const formatPlaybackModelToken = (
  source: ModelTokenSource | null | undefined,
  value: unknown
): string => formatModelTokenBySource(
    source || 'UNKNOWN',
    value,
    key => te(key) ? t(key) : key
  )

const formatPlaybackDeviceModelToken = (device: ModelTokenDevice, value: unknown): string =>
  formatPlaybackModelToken(device.modelTokenSource, value)

const bundledBoardDeviceIds = computed(() => nodes.value
  .filter(node => isBundledDeviceTemplate(resolveTemplateForNode(node)))
  .map(node => node.id))

const bundledBoardEnvironmentNames = computed(() => getBundledEnvironmentNames(nodes.value))

const hasNodeStateMachine = (node: DeviceNode): boolean => {
  return hasModeledStateMachine(resolvePresentationTemplateForNode(node)?.manifest)
}

const getNodeEffectiveState = (node: DeviceNode): string => {
  const manifest = resolvePresentationTemplateForNode(node)?.manifest
  return resolveEffectiveNodeState(node.state, manifest, t('app.unknown'))
}

const getBoardNodeIcon = (node: DeviceNode, stateOverride?: string): string => {
  const template = resolvePresentationTemplateForNode(node)
  return resolveNodeIcon(node, template?.manifest || stateOverride || null, stateOverride)
}

/* =================================================================================
 * 5. Canvas Interaction (Zoom & Pan)
 * ================================================================================= */

const getMinimumCanvasZoom = (frame = getVisibleCanvasFrame()) =>
  Math.min(MIN_ZOOM, fittedViewportForNodes(renderedCanvasNodes.value, frame)?.zoom ?? MIN_ZOOM)

const clampZoom = (value: number, frame = getVisibleCanvasFrame()) =>
  Math.min(MAX_ZOOM, Math.max(getMinimumCanvasZoom(frame), value))

const setCanvasZoom = (value: number, options: { preserveCenter?: boolean } = {}) => {
  if (isCanvasNavigationLocked.value) return
  // One live frame keeps the bounds, old world center and new pan consistent without repeated layout reads.
  const frame = getVisibleCanvasFrame()
  const nextZoom = clampZoom(value, frame)
  if (!Number.isFinite(nextZoom)) return
  if (Math.abs(nextZoom - renderedCanvasZoom.value) < 0.001) return
  if (!activePlaybackScene.value && !layoutHydrated.value) canvasStateTouchedBeforeLayout = true

  const center = options.preserveCenter ? getVisibleCanvasCenterWorld(frame) : null
  if (activePlaybackScene.value) {
    playbackViewportFollowsFit = false
    playbackCanvasZoom.value = nextZoom
  }
  else canvasZoom.value = nextZoom
  if (center) {
    panCanvasToWorldCenter(center.x, center.y, frame)
  }
}

const adjustCanvasZoom = (delta: number) => {
  if (isCanvasNavigationLocked.value) return
  setCanvasZoom(renderedCanvasZoom.value + delta, { preserveCenter: true })
}

const canvasZoomPercent = computed(() => Number((renderedCanvasZoom.value * 100).toFixed(2)))

const canvasContainer = ref<HTMLElement | null>(null)
const canvasFrameForRendering = shallowRef<ReturnType<typeof getVisibleCanvasFrame>>(null)
const minimumCanvasZoomForInput = ref(MIN_ZOOM)
let canvasGeometryObserver: ResizeObserver | undefined
const syncCanvasGeometryForRendering = () => {
  const frame = getVisibleCanvasFrame()
  canvasFrameForRendering.value = frame
  minimumCanvasZoomForInput.value = getMinimumCanvasZoom(frame)
}
const getCanvasFrameForRendering = () => canvasFrameForRendering.value

// Read frame geometry after its layout inputs change. Render-time reads forced every pan/zoom
// update to synchronously lay out nodes even though the frame and input constraint were unchanged.
watchPostEffect(() => {
  void boardShellStyle.value
  void actionDockViewportWidth.value
  void boardViewportHeight.value
  syncCanvasGeometryForRendering()
})
onMounted(() => {
  if (canvasContainer.value && typeof ResizeObserver !== 'undefined') {
    canvasGeometryObserver = new ResizeObserver(syncCanvasGeometryForRendering)
    canvasGeometryObserver.observe(canvasContainer.value, { box: 'border-box' })
  }
})
onBeforeUnmount(() => canvasGeometryObserver?.disconnect())

const handleCanvasMapZoomInput = (event: Event) => {
  const input = event.target as HTMLInputElement | null
  if (isCanvasNavigationLocked.value) {
    if (input) input.value = String(canvasZoomPercent.value)
    return
  }
  const value = Number(input?.value)
  if (!Number.isFinite(value)) {
    if (input) input.value = String(canvasZoomPercent.value)
    return
  }
  setCanvasZoom(value / 100, { preserveCenter: true })
}

const onBoardWheel = (e: WheelEvent) => {
  if (isCanvasNavigationLocked.value) {
    if (e.ctrlKey) e.preventDefault()
    return
  }
  if (e.ctrlKey && e.deltaY !== 0) {
    if (e.deltaY > 0) {
      adjustCanvasZoom(-ZOOM_STEP)
    } else {
      adjustCanvasZoom(ZOOM_STEP)
    }
  }
}

const onCanvasEnter = () => (isCanvasHovered.value = true)
const onCanvasLeave = () => (isCanvasHovered.value = false)

const onGlobalKeydown = (e: KeyboardEvent) => {
  const target = e.target as HTMLElement | null
  const isEditableTarget = target instanceof HTMLInputElement
    || target instanceof HTMLTextAreaElement
    || target instanceof HTMLSelectElement
    || Boolean(target?.isContentEditable)

  if (!e.defaultPrevented && !isCanvasNavigationLocked.value
    && isCanvasHovered.value && !isEditableTarget && (e.ctrlKey || e.metaKey)) {
    if (['=', '+', '-', '0'].includes(e.key)) {
      e.preventDefault()
      if (e.key === '=' || e.key === '+') {
        adjustCanvasZoom(ZOOM_STEP)
      } else if (e.key === '-') {
        adjustCanvasZoom(-ZOOM_STEP)
      } else if (e.key === '0') {
        setCanvasZoom(1, { preserveCenter: true })
      }
    }
  }
}

const onCanvasPointerDown = (e: PointerEvent) => {
  if (isCanvasNavigationLocked.value) return
  if (e.button !== 0 || e.isPrimary === false || canvasPanPointerId !== null) return
  e.preventDefault()
  isPanning = true
  canvasPanPointerId = e.pointerId
  panStart = { x: e.clientX, y: e.clientY }
  panOrigin = { x: renderedCanvasPan.value.x, y: renderedCanvasPan.value.y }

  const target = e.currentTarget as HTMLElement
  canvasPanTarget = target
  try { target.setPointerCapture?.(e.pointerId) } catch {}
  target.addEventListener('lostpointercapture', onCanvasPointerLost)

  window.addEventListener('pointermove', onCanvasPointerMove)
  window.addEventListener('pointerup', onCanvasPointerUp)
  window.addEventListener('pointercancel', onCanvasPointerCancel)
}

const onCanvasPointerMove = (e: PointerEvent) => {
  if (!isPanning || e.pointerId !== canvasPanPointerId) return
  if (isCanvasNavigationLocked.value) {
    finishCanvasPan(e.pointerId)
    return
  }
  const dx = e.clientX - panStart.x
  const dy = e.clientY - panStart.y
  if (!activePlaybackScene.value && !layoutHydrated.value) canvasStateTouchedBeforeLayout = true
  const nextPan = {
    x: panOrigin.x + dx,
    y: panOrigin.y + dy
  }
  if (activePlaybackScene.value) {
    playbackViewportFollowsFit = false
    playbackCanvasPan.value = nextPan
  }
  else canvasPan.value = nextPan
}

const finishCanvasPan = (pointerId: number | null = canvasPanPointerId) => {
  if (pointerId !== null && pointerId !== canvasPanPointerId) return
  const target = canvasPanTarget
  const activePointerId = canvasPanPointerId
  isPanning = false
  canvasPanPointerId = null
  canvasPanTarget = null
  target?.removeEventListener('lostpointercapture', onCanvasPointerLost)
  if (target && activePointerId !== null) {
    try { target.releasePointerCapture?.(activePointerId) } catch {}
  }
  window.removeEventListener('pointermove', onCanvasPointerMove)
  window.removeEventListener('pointerup', onCanvasPointerUp)
  window.removeEventListener('pointercancel', onCanvasPointerCancel)
}

const onCanvasPointerUp = (e: PointerEvent) => {
  finishCanvasPan(e.pointerId)
}

const onCanvasPointerCancel = (e: PointerEvent) => {
  finishCanvasPan(e.pointerId)
}

const onCanvasPointerLost = (e: PointerEvent) => {
  finishCanvasPan(e.pointerId)
}

// Panel interaction removed

/* =================================================================================
 * 7. Node / Edge / Spec Management
 * ================================================================================= */


const createDeviceInstanceAt = async (
  tpl: DeviceTemplate,
  pos: { x: number; y: number },
  customName?: string,
  runtime?: DeviceRuntimeConfig,
  options: CreateDeviceInstanceOptions = {}
) => {
  const { onConfirmedCreate, onFailure, ...mutationOptions } = options
  const requestToken = getToken()
  const requestAuthScopeEpoch = boardAuthScopeEpoch
  const isCreateOwnerCurrent = () => isCurrentSessionToken(requestToken) && isCurrentBoardAuthScope(requestAuthScopeEpoch)
  let createConfirmed = false
  const notifyConfirmedCreate = () => {
    if (createConfirmed) return
    createConfirmed = true
    onConfirmedCreate?.()
  }
  if (!ensureBoardDataReady(['nodes', 'templates'])) {
    throw new Error(t('app.boardDataLoadFailed'))
  }
  if (!ensureDeviceRuntimeCapacity(runtime)) {
    throw new Error('Device runtime capacity reached')
  }
  return enqueueBoardMutation(async () => {
    if (!ensureBoardDataReady(['nodes', 'templates'])) {
      throw new Error(t('app.boardDataLoadFailed'))
    }
    if (!ensureBoardItemCapacity(
      'devices', getVisibleDeviceNodes().length, 1, REQUEST_LIMITS.devices
    )) {
      throw new Error('Board device capacity reached')
    }
    const baseName = customName?.trim() || tpl.manifest.Name
    const uniqueLabel = getUniqueLabel(baseName, getVisibleDeviceNodes())
    if (uniqueLabel !== baseName) {
      notifyBlocked(t('app.deviceNameChangedBeforeCreate', { name: uniqueLabel }))
      throw new Error('Device name changed before queued creation')
    }
    const node: DeviceNode = {
      id: createDeviceInstanceId(getVisibleDeviceNodes()),
      templateName: tpl.manifest.Name,
      label: uniqueLabel,
      position: pos,
      state: tpl.manifest.InitState || 'Working',
      width: DEFAULT_NODE_WIDTH,
      height: DEFAULT_NODE_HEIGHT,
      ...(runtime || {})
    }
    try {
      const mutation = await boardApi.addNodes([node])
      if (!isCreateOwnerCurrent()) throw new PollingAbortedError()
      commitSemanticScene({
        nodes: mutation.currentNodes,
        environmentVariables: mutation.environmentVariables,
        specs: mutation.currentSpecifications,
        availability: mutation
      })
      const created = mutation.affectedDevices[0]
      notifyConfirmedCreate()
      await focusCreatedDeviceNode(created)
      if (!isCreateOwnerCurrent()) throw new PollingAbortedError()
      return { device: created, responseConfirmed: true, environmentChanges: mutation.environmentChanges }
    } catch (error: any) {
      if (!isCreateOwnerCurrent()) throw new PollingAbortedError()
      let refreshed = false
      if (!isDefinitiveMutationRejection(error)) {
        const [nodesRefreshed, environmentRefreshed] = await Promise.all([
          refreshDevices(),
          refreshEnvironmentVariables()
        ])
        if (!isCreateOwnerCurrent()) throw new PollingAbortedError()
        await reloadUndoAvailability()
        if (!isCreateOwnerCurrent()) throw new PollingAbortedError()
        refreshed = nodesRefreshed && environmentRefreshed
        const created = nodes.value.find(candidate => candidate.id === node.id)
        if (nodesRefreshed && environmentRefreshed && created) {
          notifyConfirmedCreate()
          await focusCreatedDeviceNode(created)
          if (!isCreateOwnerCurrent()) throw new PollingAbortedError()
          return { device: created, responseConfirmed: false, environmentChanges: [] }
        }
      }
      if (onFailure) onFailure(error, refreshed)
      else notifyError(localizedErrorMessage(error, t('app.saveNodesFailed'), locale.value))
      throw error
    }
  }, mutationOptions)
}

const openTemplateInstanceDialog = (tpl: DeviceTemplate, pos: { x: number; y: number }) => {
  if (!ensurePlaybackClosedForMutation()) return
  templateInstanceDialogData.template = tpl
  templateInstanceDialogData.position = pos
  templateInstanceDialogData.name = getUniqueLabel(tpl.manifest.Name, getVisibleDeviceNodes())
  resetTemplateInstanceRuntime(tpl)
  templateInstanceDialogVisible.value = true
}

const cancelTemplateInstanceCreate = () => {
  if (templateInstanceSaving.value) return
  templateInstanceDialogVisible.value = false
  templateInstanceDialogData.template = null
  templateInstanceDialogData.name = ''
  resetTemplateInstanceRuntime(null)
}

const {
  setDialogRef: setTemplateInstanceDialogRef,
  handleModalKeydown: handleTemplateInstanceDialogKeydown
} = useModalAccessibility(
  templateInstanceDialogVisible,
  cancelTemplateInstanceCreate,
  () => document.querySelector<HTMLElement>('[data-testid="control-center"] button')
)

const confirmTemplateInstanceCreate = async () => {
  if (!ensurePlaybackClosedForMutation()) return
  const tpl = templateInstanceDialogData.template
  const name = templateInstanceDialogData.name.trim()
  if (!tpl) return
  if (!name) {
    notifyBlocked(t('app.enterDeviceName'))
    return
  }
  const availableName = getUniqueLabel(name, getVisibleDeviceNodes())
  if (availableName !== name) {
    templateInstanceDialogData.name = availableName
    notifyBlocked(t('app.deviceNameAdjustedToAvoidConflict', { name: availableName }))
    return
  }

  const runtime = buildTemplateInstanceRuntimeConfig(tpl)
  const runtimeError = validateTemplateInstanceRuntimeConfig(tpl, runtime)
  if (runtimeError) {
    notifyBlocked(runtimeError)
    return
  }

  templateInstanceSaving.value = true
  try {
    const outcome = await createDeviceInstanceAt(tpl, templateInstanceDialogData.position, availableName, runtime)
    const created = outcome.device
    templateInstanceDialogVisible.value = false
    templateInstanceDialogData.template = null
    templateInstanceDialogData.name = ''
    resetTemplateInstanceRuntime(null)
    reportEnvironmentChanges(outcome.environmentChanges)
    if (!outcome.responseConfirmed) {
      notifyBlocked(t('app.deviceCreateOutcomeRefreshed', { name: created.label }))
    }
    if (created.label !== availableName) {
      notifyBlocked(t('app.deviceNameAdjustedToAvoidConflict', { name: created.label }))
    }
  } catch { /* 已回滚并提示 */ }
  finally {
    templateInstanceSaving.value = false
  }
}

const handleTemplateDragStart = (templateName: string) => {
  draggingTplName.value = templateName
}

const handleTemplateDragEnd = () => {
  draggingTplName.value = null
}

const onCanvasDragOver = (e: DragEvent) => {
  if (!e.dataTransfer) return
  if (isCanvasInteractionLocked.value) {
    e.dataTransfer.dropEffect = 'none'
    return
  }
  const hasTemplateData = draggingTplName.value
    || Array.from(e.dataTransfer.types || []).includes('application/x-iot-template')
  e.dataTransfer.dropEffect = hasTemplateData ? 'copy' : 'none'
}

const onCanvasDrop = async (e: DragEvent) => {
  if (isCanvasInteractionLocked.value) return
  if (!ensurePlaybackClosedForMutation()) return
  const droppedTemplateName = draggingTplName.value
    || e.dataTransfer?.getData('application/x-iot-template')
    || e.dataTransfer?.getData('text/plain')
  if (!droppedTemplateName) return
  const tpl = findTemplateByAnyName(droppedTemplateName)
  if (!tpl) return

  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  const Sx = e.clientX - rect.left
  const Sy = e.clientY - rect.top

  const { x, y } = screenToWorld(Sx, Sy, canvasPan.value, canvasZoom.value)

  openTemplateInstanceDialog(tpl, { x, y })
  draggingTplName.value = null
}

const handleNodeMovedOrResized = async (nodeId: string) => {
  if (!ensurePlaybackClosedForMutation()) return
  if (!ensureBoardDataReady(['nodes'])) return
  const node = nodes.value.find(candidate => candidate.id === nodeId)
  if (!node) return
  const layout: DeviceLayout = {
    position: { x: node.position.x, y: node.position.y },
    width: Math.round(node.width),
    height: Math.round(node.height)
  }
  applyLayoutToNode(node, layout)
  const version = ++nodeLayoutMutationVersion
  pendingNodeLayouts.set(nodeId, { version, layout })
  await enqueueBoardMutation(async () => {
    try {
      const mutation = await boardApi.updateNodeLayout(nodeId, layout)
      commitSemanticScene({
        nodes: mutation.currentNodes,
        availability: mutation,
        // Canvas coordinates never reach the SMV model (`buildDevices` omits them), so moving or
        // resizing a node must not invalidate a verification verdict. The backend still reports
        // `operation: "updated"` because a layout row did change, which is why this cannot be
        // derived from the mutation's operation field.
        semanticChanged: false
      })
      const pending = pendingNodeLayouts.get(nodeId)
      if (pending?.version === version) {
        pendingNodeLayouts.delete(nodeId)
      }
    } catch (error: any) {
      const pending = pendingNodeLayouts.get(nodeId)
      if (pending?.version !== version) return
      pendingNodeLayouts.delete(nodeId)
      const refreshed = await refreshDevices()
      await reloadUndoAvailability()
      if (!isDefinitiveMutationRejection(error)
        && refreshed
        && deviceLayoutMatches(nodes.value.find(candidate => candidate.id === nodeId), layout)) {
        notifyBlocked(t('app.deviceLayoutOutcomeRefreshed'))
      } else {
        notifyError(extractApiErrorMessage(error, t('app.saveNodesFailed')))
      }
    }
  })
  // edges 由 rules 动态生成，不需要单独保存
}

const handleAddRule = async (request: {
  rule: RuleForm
  complete: (saved: boolean, failure?: ApiErrorDetails) => void
}) => {
  if (!ensurePlaybackClosedForMutation()) {
    request.complete(false)
    return
  }
  const requestToken = getToken()
  const requestAuthScopeEpoch = boardAuthScopeEpoch
  let saved = false
  let outcomeRefreshed = false
  let attemptedRule: RuleForm | null = null
  let saveFailure: ApiErrorDetails | undefined
  try {
    await enqueueBoardMutation(async () => {
      try {
        const payload = request.rule
        if (!ensureBoardDataReady(['nodes', 'templates', 'rules'])) return
        if (!ensureBoardItemCapacity('rules', rules.value.length, 1, REQUEST_LIMITS.rules)) return
        const { sources, toId, toApi } = payload
        if (!sources || !sources.length || !toId || !toApi) {
          notifyBlocked(t('app.fillAllRuleFields'))
          return
        }
        if (!ensureNestedItemCapacity(
          t('app.ruleConditions'), sources.length, REQUEST_LIMITS.ruleConditions
        )) return
        if (!assertRulesHaveTriggers([payload])) return
        if (!resolveNodeRef(toId)) return

        const newRule: RuleForm = {
          ...payload,
          id: 'rule_' + Date.now(),
          name: payload.name || t('app.automationRule')
        }
        attemptedRule = newRule
        const mutation = await boardApi.addRule(JSON.parse(JSON.stringify(newRule)))
        commitSemanticScene({ rules: mutation.currentItems, availability: mutation })
        if (mutation.affectedItem?.id) {
          await focusRuleOnCanvas(mutation.affectedItem.id)
        }
        saved = true
      } catch (error: any) {
        console.error('addRule error', error)
        if (Number(error?.response?.status) === 409
          && error?.response?.data?.data?.reasonCode === 'DUPLICATE_RULE') {
          if (!isCurrentSessionToken(requestToken) || !isCurrentBoardAuthScope(requestAuthScopeEpoch)) return
          await refreshRules(requestToken)
          if (!isCurrentSessionToken(requestToken) || !isCurrentBoardAuthScope(requestAuthScopeEpoch)) return
          await reloadUndoAvailability()
          if (!isCurrentSessionToken(requestToken) || !isCurrentBoardAuthScope(requestAuthScopeEpoch)) return
          saveFailure = errorDetails(error)
          return
        }
        if (isDefinitiveMutationRejection(error) && [400, 422].includes(Number(error?.response?.status))) {
          if (!isCurrentSessionToken(requestToken) || !isCurrentBoardAuthScope(requestAuthScopeEpoch)) return
          saveFailure = errorDetails(error)
          return
        }
        if (!isDefinitiveMutationRejection(error) && attemptedRule) {
          const refreshed = await refreshRules()
          await reloadUndoAvailability()
          if (refreshed && ruleExists(attemptedRule)) {
            outcomeRefreshed = true
            saved = true
            return
          }
        }
        notifyError(extractApiErrorMessage(error, t('app.saveRulesFailed')))
      }
    })
  } finally {
    request.complete(saved, saveFailure)
    if (outcomeRefreshed) {
      await nextTick()
      notifyBlocked(t('app.ruleCreateOutcomeRefreshed'))
    }
  }
}

const ruleRecommendationTargetTypes = new Set<RuleSourceItemType>(['api', 'variable', 'mode', 'state'])
const valueBasedRuleRecommendationTargetTypes = new Set<RuleSourceItemType>(['variable', 'mode', 'state'])

const normalizeRuleRecommendationTargetType = (targetType?: string): RuleSourceItemType | undefined => {
  const normalized = String(targetType || '').trim().toLowerCase()
  return ruleRecommendationTargetTypes.has(normalized as RuleSourceItemType)
    ? normalized as RuleSourceItemType
    : undefined
}

const isValueBasedRuleRecommendationCondition = (targetType?: string) => {
  const normalized = normalizeRuleRecommendationTargetType(targetType)
  return normalized ? valueBasedRuleRecommendationTargetTypes.has(normalized) : false
}

const formatRecommendedRuleDevice = (deviceId?: string, label?: string): string => {
  const nodeId = String(deviceId || '').trim()
  const currentLabel = nodeId ? resolveNodeRef(nodeId)?.label : ''
  if (currentLabel) return currentLabel
  const displayLabel = String(label || '').trim()
  return displayLabel || t('app.unknownModelItem')
}

const formatRecommendedNodeModelToken = (deviceId: unknown, value: unknown): string => {
  const node = resolveNodeRef(String(deviceId || '').trim())
  return node ? formatNodeModelToken(node, value) : String(value ?? '')
}

const formatRecommendedRuleConditionAttribute = (
  condition: RuleRecommendation['conditions'][number]
): string => formatRecommendedNodeModelToken(condition.deviceId, condition.attribute)

const formatRecommendedRuleConditionValue = (
  condition: RuleRecommendation['conditions'][number]
): string => formatRecommendedNodeModelToken(condition.deviceId, condition.value)

const formatRecommendedRuleCommandAction = (command: RuleRecommendation['command']): string =>
  formatRecommendedNodeModelToken(command.deviceId, command.action)

const formatRecommendedRuleCommandContent = (command: RuleRecommendation['command']): string =>
  formatRecommendedNodeModelToken(command.contentDevice, command.content)

const formatRecommendedSpecConditionTarget = (condition: any): string => {
  const device = formatRecommendedRuleDevice(condition?.deviceId, condition?.deviceLabel)
  const targetType = String(condition?.targetType || '').trim().toLowerCase()
  const key = String(condition?.key || '').trim()
  const displayKey = formatRecommendedNodeModelToken(condition?.deviceId, key)
  if (targetType === 'trust' || targetType === 'privacy') {
    const property = condition?.propertyScope === 'state'
      ? t('app.currentModeStateProperty', { mode: displayKey })
      : displayKey
    const dimension = targetType === 'trust' ? t('app.sourceLabel') : t('app.sensitivityLabel')
    return `${device} · ${property} · ${dimension}`
  }
  if (key) {
    // Names the reading the card will persist on Apply, in the same words the condition rows and verdict
    // badges use. Rule extracted to `board/recommendedReadingSuffix.ts` so it is unit-testable.
    const readingKey = recommendedReadingKey(targetType, condition?.variableSource)
    return readingKey ? `${device}.${displayKey} · ${t(readingKey)}` : `${device}.${displayKey}`
  }
  return device
}

const formatRecommendedSpecConditionValue = (condition: any): string =>
  formatRecommendedNodeModelToken(condition?.deviceId, condition?.value)

const formatRecommendedRuleConditionDevice = (condition: RuleRecommendation['conditions'][number]): string =>
  formatRecommendedRuleDevice(condition.deviceId, condition.deviceLabel || condition.deviceName)

const formatRecommendedRuleCommandDevice = (command: RuleRecommendation['command']): string =>
  formatRecommendedRuleDevice(command.deviceId, command.deviceLabel || command.deviceName)

const formatRecommendedRuleContentDevice = (command: RuleRecommendation['command']): string =>
  formatRecommendedRuleDevice(command.contentDevice, command.contentDeviceLabel)

const recommendationPresentationContext = () => ({
  t,
  resolveDevice: (id: string) => resolveNodeRef(id),
  formatToken: formatRecommendedNodeModelToken,
  modeCount: (id: string) => {
    const node = resolveNodeRef(id)
    return node ? resolvePresentationTemplateForNode(node)?.manifest.Modes?.length || 0 : 0
  }
})

const presentedRuleRecommendations = computed(() => ruleRecommendations.value.map(rec =>
  presentRule(materializeRuleRecommendation(rec, 'preview'), recommendationPresentationContext())))
const presentedSpecRecommendations = computed(() => specRecommendations.value.map(rec =>
  describeSpecification({ ...rec, templateId: rec.templateId as SpecTemplateId }, recommendationPresentationContext())))

const normalizeRuleDuplicateText = (value: unknown): string => {
  if (value === null || value === undefined) return ''
  return String(value).trim()
}

const buildRuleDuplicateKey = (rule: RuleForm): string => {
  const sources = (rule.sources || [])
    .map(source => {
      const itemType = normalizeRuleRecommendationTargetType(source.itemType)
      const isValueBased = itemType ? valueBasedRuleRecommendationTargetTypes.has(itemType) : false
      return {
        fromId: normalizeRuleDuplicateText(source.fromId),
        fromApi: itemType === 'state' ? 'state' : normalizeRuleDuplicateText(source.fromApi),
        itemType: itemType || '',
        // A missing relation is invalid input, not an implicit equality. Keep it
        // distinguishable here so local duplicate detection cannot change semantics.
        relation: isValueBased
          ? (normalizeModelRelation(source.relation) || normalizeRuleDuplicateText(source.relation))
          : '',
        value: isValueBased ? normalizeRuleDuplicateText(source.value) : ''
      }
    })
    .sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b)))

  return JSON.stringify({
    sources,
    command: {
      toId: normalizeRuleDuplicateText(rule.toId),
      toApi: normalizeRuleDuplicateText(rule.toApi),
      contentDevice: normalizeRuleDuplicateText(rule.contentDevice),
      content: normalizeRuleDuplicateText(rule.content)
    }
  })
}

const ruleExists = (candidate: RuleForm): boolean => {
  const candidateKey = buildRuleDuplicateKey(candidate)
  return rules.value.some(rule => buildRuleDuplicateKey(rule) === candidateKey)
}

const confirmRecommendedRuleSimilarity = async (candidate: RuleForm): Promise<boolean> => {
  try {
    const result = await boardApi.checkRuleSimilarity(candidate)
    if (!result.requiresReview) return true

    const reason = t(ruleSimilarityReasonKey(result.reasonCode))
    const message = result.matchedRule
      ? t('app.aiSimilarRuleMayExistWithMatch', {
          rule: result.matchedRule,
          reason
        })
      : t('app.aiSimilarRuleMayExist', { reason })

    return await confirmChoice({
      title: t('app.aiRuleSimilarityDetected'),
      message,
      confirmText: t('app.applyAnyway')
    })
  } catch (error) {
    console.error('AI similarity check failed before applying recommendation:', error)
    return await confirmChoice({
      title: t('app.aiRuleSimilarityDetected'),
      message: t('app.aiSimilarityCheckFailedCanStillApply'),
      confirmText: t('app.applyAnyway')
    })
  }
}

// 应用推荐的规则
const applyRecommendation = async (rec: RuleRecommendation, index: number) => {
  if (!ensurePlaybackClosedForMutation()) return
  if (!ensureBoardDataReady(['nodes', 'templates', 'rules'])) return
  if (appliedRuleRecommendations.value.has(index)) {
    notifyBlocked(t('app.recommendationAlreadyApplied'))
    return
  }
  if (applyingRuleRecommendations.value.has(index) || recommendationApplyFailures.rule[index]?.outcomeUnknown) return

  const recommendationEpoch = ruleRecommendationRequestEpoch
  const requestSceneGeneration = recommendationSceneGeneration
  const applyAuthScopeEpoch = boardAuthScopeEpoch
  const requestToken = getToken()
  const isApplyAccountCurrent = () => isCurrentBoardAuthScope(applyAuthScopeEpoch) && isCurrentSessionToken(requestToken)
  const isApplyCandidateCurrent = () => isApplyAccountCurrent()
    && recommendationEpoch === ruleRecommendationRequestEpoch && requestSceneGeneration === recommendationSceneGeneration
  delete recommendationApplyFailures.rule[index]
  let recommendationConfirmedApplied = false

  let attemptedRule: RuleForm | null = null
  const reportFailure = async (error: any) => {
    if (!isApplyAccountCurrent()) return
    console.error('applyRecommendation error', error)
    let refreshed = false
    if (!isDefinitiveMutationRejection(error) && attemptedRule) {
      refreshed = await refreshRules(requestToken)
      if (!isApplyAccountCurrent()) return
      await reloadUndoAvailability()
      if (!isApplyAccountCurrent()) return
      if (refreshed && ruleExists(attemptedRule)) {
        recommendationConfirmedApplied = true
        if (isApplyCandidateCurrent()) {
          appliedRuleRecommendations.value.add(index)
          notifyBlocked(t('app.ruleCreateOutcomeRefreshed'))
        }
        return
      }
    }
    if (!isApplyCandidateCurrent()) return
    if (error?.response?.data?.data?.reasonCode === 'DUPLICATE_RULE') {
      notifyBlocked(t('app.duplicateRuleExists'))
      return
    }
    const outcomeUnknown = !isDefinitiveMutationRejection(error)
    reportRecommendationApplyFailure('rule', index, error, t(outcomeUnknown
      ? refreshed ? 'app.recommendationApplyOutcomeRefreshed' : 'app.recommendationApplyOutcomeUnknown'
      : 'app.failedToApplyRule'), outcomeUnknown)
  }
  try {
    if (!ensureBoardItemCapacity('rules', rules.value.length, 1, REQUEST_LIMITS.rules)) return
    const newRule = materializeRuleRecommendation(rec, 'rule_' + Date.now())
    if (!ensureNestedItemCapacity(
      t('app.ruleConditions'), newRule.sources.length, REQUEST_LIMITS.ruleConditions
    )) return
    attemptedRule = newRule

    if (!assertRulesHaveTriggers([newRule])) {
      return
    }
    if (ruleExists(newRule)) {
      notifyBlocked(t('app.duplicateRuleExists'))
      return
    }
    applyingRuleRecommendations.value.add(index)
    const shouldApply = await confirmRecommendedRuleSimilarity(newRule)
    if (!shouldApply) return
    if (recommendationEpoch !== ruleRecommendationRequestEpoch
      || requestSceneGeneration !== recommendationSceneGeneration
      || isSceneReplacementInProgress.value) return

    await enqueueBoardMutation(async () => {
      if (recommendationEpoch !== ruleRecommendationRequestEpoch
        || requestSceneGeneration !== recommendationSceneGeneration
        || !isBoardDataReady.value
        || isSceneReplacementInProgress.value) return
      try {
        const mutation = await boardApi.addRule(JSON.parse(JSON.stringify(newRule)))
        if (!isApplyAccountCurrent()) return
        commitSemanticScene({ rules: mutation.currentItems, availability: mutation })
        recommendationConfirmedApplied = true
        const createdRule = mutation.affectedItem
        if (createdRule?.id) {
          await focusRuleOnCanvas(createdRule.id)
        }
        if (isApplyCandidateCurrent()) {
          appliedRuleRecommendations.value.add(index)
        }
      } catch (error: any) {
        await reportFailure(error)
      }
    }, {
      onSemanticChange: () => handleRecommendationApplySceneChange(
        recommendationConfirmedApplied,
        () => preserveAppliedRecommendationAfterSceneChange('rule', index),
        () => invalidateRecommendationsForSceneChange({ notify: true })
      )
    })
  } catch (error: any) {
    if (error instanceof RecommendationCandidateError) {
      notifyBlocked(t('app.recommendationInvalidFieldNoChange', { field: error.field }))
      return
    }
    await reportFailure(error)
  } finally {
    if (recommendationEpoch === ruleRecommendationRequestEpoch) {
      applyingRuleRecommendations.value.delete(index)
    }
  }
}


/* =================================================================================
 * 8. Context Menu & Deletion
 * ================================================================================= */

const getDeviceNodeElement = (nodeId: string | null | undefined) => {
  if (!nodeId) return null
  const escaped = typeof CSS !== 'undefined' && typeof CSS.escape === 'function'
    ? CSS.escape(nodeId)
    : nodeId.replace(/["\\]/g, '\\$&')
  return document.querySelector<HTMLElement>(`[data-node-id="${escaped}"]`)
}

const getDeleteDialogFallbackFocus = () =>
  getDeviceNodeElement(deleteDialogReturnFocusNodeId)
  ?? document.querySelector<HTMLElement>('[data-testid="control-tab-devices"]')
  ?? document.querySelector<HTMLElement>('[data-testid="control-center"] button:not([disabled])')
  ?? document.querySelector<HTMLElement>('.board-nav-bar button:not([disabled])')

const onDeviceListClick = (deviceId: string, options: { focus?: boolean; ensureReadable?: boolean } = {}) => {
  if (isModelPlaybackActive.value) {
    notifyInfo(t('app.playbackDeviceDetailsUseTimeline'))
    return
  }
  const node = nodes.value.find(n => n.id === deviceId)
  if (!node) return
  const shouldFocus = options.focus ?? true

  if (shouldFocus) {
    focusDeviceNodeOnCanvas(node, { ensureReadable: options.ensureReadable })
  }

  bindDeviceDialogNode(node)
  deviceDialogReturnFocusNodeId = node.id
  dialogVisible.value = true
}

const bindDeviceDialogNode = (node: DeviceNode) => {
  const tpl = resolveTemplateForNode(node)
  const manifest = tpl?.manifest || null
  dialogMeta.nodeId = node.id
  dialogMeta.label = node.label
  dialogMeta.deviceName = manifest?.Name || tpl?.manifest?.Name || node.templateName
  dialogMeta.manifest = manifest
  dialogMeta.specs = specifications.value.filter(spec =>
    isSpecRelatedToNode(spec, node.id)
  )
}

const clearDeviceDialogMeta = () => {
  dialogMeta.nodeId = ''
  dialogMeta.label = ''
  dialogMeta.deviceName = ''
  dialogMeta.manifest = null
  dialogMeta.specs = []
}

const handleDeviceDialogVisibility = (visible: boolean) => {
  dialogVisible.value = visible
  if (!visible) {
    if (deletePreviewNodeId === dialogMeta.nodeId) invalidateDeletePreview()
    const nodeId = deviceDialogReturnFocusNodeId
    void nextTick(() => getDeviceNodeElement(nodeId)?.focus({ preventScroll: true }))
  }
}

const focusDeviceFromInspector = (deviceId: string) => {
  if (isSceneReplacementInProgress.value) return
  const node = nodes.value.find(n => n.id === deviceId)
  if (!node) return
  focusDeviceNodeOnCanvas(node, { ensureReadable: true })
}

// 右键菜单状态
const contextMenu = ref({
  visible: false,
  x: 0,
  y: 0,
  node: null as DeviceNode | null
})
const contextMenuRef = ref<HTMLElement | null>(null)
let contextMenuReturnFocus: HTMLElement | null = null

const contextMenuItems = () => Array.from(
  contextMenuRef.value?.querySelectorAll<HTMLElement>('[role="menuitem"]:not([disabled])') || []
)

const onNodeContext = (node: DeviceNode, position: { x: number; y: number }) => {
  contextMenuReturnFocus = getDeviceNodeElement(node.id)
  contextMenu.value = {
    visible: true,
    x: position.x,
    y: position.y,
    node
  }
  void nextTick(() => {
    const menu = contextMenuRef.value
    if (!menu || !contextMenu.value.visible) return
    const rect = menu.getBoundingClientRect()
    const clamped = clampFloatingMenuPosition(
      position,
      { width: rect.width, height: rect.height },
      { width: window.innerWidth, height: window.innerHeight }
    )
    contextMenu.value.x = clamped.x
    contextMenu.value.y = clamped.y
    contextMenuItems()[0]?.focus({ preventScroll: true })
  })
}

const openNodeFromCanvas = (node: DeviceNode) => {
  onDeviceListClick(node.id, { focus: false })
}

const closeContextMenu = (restoreFocus = true) => {
  const returnFocus = contextMenuReturnFocus
  contextMenu.value.visible = false
  contextMenu.value.node = null
  contextMenuReturnFocus = null
  if (restoreFocus && returnFocus?.isConnected) {
    void nextTick(() => returnFocus.focus({ preventScroll: true }))
  }
}

const handleContextMenuKeydown = (event: KeyboardEvent) => {
  const items = contextMenuItems()
  const currentIndex = items.indexOf(document.activeElement as HTMLElement)
  let nextIndex: number | null = null
  if (event.key === 'Escape') {
    event.preventDefault()
    closeContextMenu()
    return
  }
  if (event.key === 'ArrowDown') nextIndex = currentIndex < 0 ? 0 : (currentIndex + 1) % items.length
  if (event.key === 'ArrowUp') nextIndex = currentIndex < 0 ? items.length - 1 : (currentIndex - 1 + items.length) % items.length
  if (event.key === 'Home') nextIndex = 0
  if (event.key === 'End') nextIndex = items.length - 1
  if (event.key === 'Tab') {
    event.preventDefault()
    closeContextMenu()
    return
  }
  if (nextIndex !== null && items[nextIndex]) {
    event.preventDefault()
    items[nextIndex].focus({ preventScroll: true })
  }
}

const openRenameDialog = (node: DeviceNode) => {
  if (deleteConfirmSubmitting.value) return
  if (deleteConfirmDialogVisible.value || deletePreviewLoading.value) {
    clearDeleteConfirmDialog()
  }
  renameDialogReturnFocusNodeId = node.id
  renameDialogData.node = node
  renameDialogData.newName = node.label
  renameDialogData.originalLabel = node.label
  renameDialogVisible.value = true
}

// 右键菜单操作
const renameDevice = () => {
  if (!contextMenu.value.node) return
  const node = contextMenu.value.node
  closeContextMenu(false)
  openRenameDialog(node)
}

const handleDialogRename = async () => {
  const nodeId = dialogMeta.nodeId
  if (!nodeId) return
  await continueAfterDeviceDialogApproval(deviceDialogRef.value, () => {
    if (!dialogVisible.value || dialogMeta.nodeId !== nodeId) return
    const node = nodes.value.find(candidate => candidate.id === nodeId)
    if (!node) return
    dialogVisible.value = false
    openRenameDialog(node)
  })
}

const deleteDevice = () => {
  if (!contextMenu.value.node) return
  const nodeId = contextMenu.value.node.id
  closeContextMenu()
  void deleteCurrentNodeWithConfirm(nodeId)
}

const handleRenameDevice = async (
  nodeId: string,
  newLabel: string,
  expectedLabel: string
): Promise<false | { responseConfirmed: boolean; environmentChanges: EnvironmentVariableChange[] }> => {
  if (!ensurePlaybackClosedForMutation()) return false
  if (!ensureBoardDataReady(['nodes', 'specs'])) return false
  return enqueueBoardMutation(async () => {
    const requestedLabelKey = deviceLabelKey(newLabel)
    const exists = nodes.value.some(n => deviceLabelKey(n.label) === requestedLabelKey && n.id !== nodeId)
    if (exists) {
      notifyError(t('app.nameExists'))
      return false
    }
    if (!nodes.value.some(node => node.id === nodeId)) return false

    try {
      const mutation = await boardApi.renameNode(nodeId, newLabel, expectedLabel)
      commitSemanticScene({
        nodes: mutation.currentNodes,
        environmentVariables: mutation.environmentVariables,
        specs: mutation.currentSpecifications,
        availability: mutation
      })
      return { responseConfirmed: true, environmentChanges: mutation.environmentChanges }
    } catch (error: any) {
      if (error?.response?.status === 409) {
        const [nodesRefreshed, specsRefreshed, environmentRefreshed] = await Promise.all([
          refreshDevices(),
          refreshSpecifications(),
          refreshEnvironmentVariables()
        ])
        await reloadUndoAvailability()
        const currentNode = nodes.value.find(candidate => candidate.id === nodeId)
        if (nodesRefreshed && specsRefreshed && environmentRefreshed && currentNode) {
          if (currentNode.label === newLabel) {
            return { responseConfirmed: false, environmentChanges: [] }
          }
          if (renameDialogVisible.value && renameDialogData.node?.id === nodeId) {
            renameDialogData.node = currentNode
            renameDialogData.originalLabel = currentNode.label
          }
          notifyBlocked(t('app.deviceRenameConflictRefreshed', { name: currentNode.label }))
        } else {
          notifyBlocked(t('app.deviceRenameConflictRefreshFailed'))
        }
        return false
      }
      if (!isDefinitiveMutationRejection(error)) {
        const [nodesRefreshed, specsRefreshed, environmentRefreshed] = await Promise.all([
          refreshDevices(),
          refreshSpecifications(),
          refreshEnvironmentVariables()
        ])
        await reloadUndoAvailability()
        const renamed = nodes.value.find(candidate => candidate.id === nodeId && candidate.label === newLabel)
        if (nodesRefreshed && specsRefreshed && environmentRefreshed && renamed) {
          return { responseConfirmed: false, environmentChanges: [] }
        }
      }
      notifyError(extractApiErrorMessage(error, t('app.saveNodesFailed')))
      return false
    }
  })
}

const handleDeviceRuntimeSave = async (
  nodeId: string, runtime: DeviceRuntimeConfig,
  complete?: (saved: boolean, failure?: ApiErrorDetails) => void
) => {
  if (!ensurePlaybackClosedForMutation()) return
  if (!ensureBoardDataReady(['nodes', 'templates'])) return
  if (deviceRuntimeSaving.value) return
  const node = nodes.value.find(n => n.id === nodeId)
  if (!node) return

  const template = resolveTemplateForNode(node)
  if (!template) {
    notifyError(t('app.loadTemplatesFailed'))
    return
  }

  const validationMessage = validateDeviceRuntimeConfig(template, runtime, t, { variableScope: 'local' })
  if (validationMessage) {
    if (complete) complete(false, { cause: validationMessage, details: [], fields: {} })
    else notifyBlocked(validationMessage)
    return
  }
  const requestToken = getToken()
  const requestAuthScopeEpoch = boardAuthScopeEpoch
  const isRequestCurrent = () => isCurrentSessionToken(requestToken) && isCurrentBoardAuthScope(requestAuthScopeEpoch)
  let saved = false
  let saveFailure: ApiErrorDetails | undefined

  const runtimeRequest: DeviceRuntimeConfig = deepClone(runtime)
  const expectedRuntime = deviceRuntimeSnapshot(node)

  deviceRuntimeSaving.value = true
  try {
    await enqueueBoardMutation(async () => {
      try {
        const mutation = await boardApi.updateNodeRuntime(nodeId, {
          expected: expectedRuntime,
          desired: runtimeRequest
        })
        if (!isRequestCurrent()) return
        commitSemanticScene({
          nodes: mutation.currentNodes,
          availability: mutation,
          semanticChanged: mutation.operation === 'updated'
        })
        saved = true
        notifySuccess(mutation.operation === 'updated'
          ? t('app.instanceConfigSaved')
          : t('app.instanceConfigUnchanged'))
      } catch (error: any) {
        console.error('Failed to save device instance configuration', error)
        if (!isRequestCurrent()) return
        if (error?.response?.data?.data?.reasonCode === 'DEVICE_RUNTIME_STALE') {
          const refreshed = await refreshDevices()
          if (!isRequestCurrent()) return
          await reloadUndoAvailability()
          if (!isRequestCurrent()) return
          if (refreshed) {
            const persisted = nodes.value.find(candidate => candidate.id === nodeId)
            notifyBlocked(deviceRuntimeMatches(persisted, runtimeRequest, template)
              ? t('app.deviceRuntimeOutcomeRefreshed')
              : t('app.deviceRuntimeStaleRefreshed'))
          } else {
            notifyBlocked(t('app.deviceRuntimeStaleRefreshFailed'))
          }
          return
        }
        if (!isDefinitiveMutationRejection(error)) {
          const nodesRefreshed = await refreshDevices()
          if (!isRequestCurrent()) return
          await reloadUndoAvailability()
          if (!isRequestCurrent()) return
          const persisted = nodes.value.find(candidate => candidate.id === nodeId)
          if (nodesRefreshed && deviceRuntimeMatches(persisted, runtimeRequest, template)) {
            notifyBlocked(t('app.deviceRuntimeOutcomeRefreshed'))
            return
          }
          notifyBlocked(t(nodesRefreshed
            ? 'app.deviceRuntimeOutcomeNotMatched' : 'app.deviceRuntimeOutcomeUnknownRefreshFailed'))
          return
        }
        if (isDefinitiveMutationRejection(error) && complete) saveFailure = errorDetails(error)
        else notifyError(extractApiErrorMessage(error, t('app.instanceConfigSaveFailed')))
      }
    })
  } finally {
    deviceRuntimeSaving.value = false
    if (isRequestCurrent()) complete?.(saved, saveFailure)
  }
}

const viewDeviceDetails = () => {
  if (!contextMenu.value.node) return
  const nodeId = contextMenu.value.node.id
  closeContextMenu()
  onDeviceListClick(nodeId, { focus: false })
}

function cancelRename() {
  if (renameDialogSubmitting.value) return
  renameDialogVisible.value = false
  renameDialogData.node = null
  renameDialogData.newName = ''
  renameDialogData.originalLabel = ''
}

const reconcileRenameDialogWithBoard = () => {
  if (!renameDialogVisible.value || !renameDialogData.node || renameDialogSubmitting.value) return
  const reconciled = reconcileRenameDialogSnapshot(nodes.value, {
    node: renameDialogData.node,
    newName: renameDialogData.newName,
    originalLabel: renameDialogData.originalLabel
  })
  if (!reconciled) {
    cancelRename()
    return
  }
  renameDialogData.node = reconciled.node
  renameDialogData.newName = reconciled.newName
  renameDialogData.originalLabel = reconciled.originalLabel
}

const focusVisibleBoardControl = () => {
  const controls = [
    document.querySelector<HTMLElement>('[data-testid="control-tab-devices"]'),
    document.querySelector<HTMLElement>('[data-testid="scene-import"]'),
    document.querySelector<HTMLElement>('.board-nav-bar button:not([disabled])')
  ]
  const target = controls.find(control => control
    && !control.hasAttribute('disabled')
    && control.getClientRects().length > 0)
  target?.focus({ preventScroll: true })
}

watch(
  [nodes, edges, rules, specifications, environmentVariables, deviceTemplates],
  () => {
    const staleSurfaceNodeIds = new Set<string>()
    if (dialogVisible.value) {
      const currentDialogNode = resolveCurrentBoardNode(nodes.value, dialogMeta.nodeId)
      if (currentDialogNode) {
        bindDeviceDialogNode(currentDialogNode)
      } else {
        staleSurfaceNodeIds.add(dialogMeta.nodeId)
        dialogVisible.value = false
        clearDeviceDialogMeta()
      }
    }

    if (contextMenu.value.visible) {
      const currentContextNode = resolveCurrentBoardNode(nodes.value, contextMenu.value.node?.id)
      if (currentContextNode) {
        contextMenu.value.node = currentContextNode
      } else {
        if (contextMenu.value.node?.id) staleSurfaceNodeIds.add(contextMenu.value.node.id)
        closeContextMenu(false)
      }
    }

    if (deletePreviewNodeId && !resolveCurrentBoardNode(nodes.value, deletePreviewNodeId)) {
      staleSurfaceNodeIds.add(deletePreviewNodeId)
      clearDeleteConfirmDialog()
    }

    if (deleteConfirmDialogVisible.value && deleteConfirmDialogData.node) {
      const currentDeleteNode = resolveCurrentBoardNode(nodes.value, deleteConfirmDialogData.node.id)
      if (currentDeleteNode) {
        deleteConfirmDialogData.node = currentDeleteNode
      } else {
        staleSurfaceNodeIds.add(deleteConfirmDialogData.node.id)
        clearDeleteConfirmDialog()
      }
    }

    if (renameDialogVisible.value && renameDialogData.node
      && !resolveCurrentBoardNode(nodes.value, renameDialogData.node.id)) {
      staleSurfaceNodeIds.add(renameDialogData.node.id)
    }
    reconcileRenameDialogWithBoard()

    const removedDeviceId = [...staleSurfaceNodeIds]
      .find(nodeId => externallyRefreshedRemovedNodeIds?.has(nodeId))
    if (removedDeviceId) {
      notifyBlocked(t('app.deviceRemovedExternally'))
      void nextTick(focusVisibleBoardControl)
    }
  },
  { flush: 'sync' }
)

watch(
  () => deleteConfirmDialogVisible.value
    && deleteConfirmReviewSnapshotKey.value
    && !deleteConfirmSubmitting.value
    ? currentDeletionReviewSnapshotKey()
    : null,
  currentSnapshotKey => {
    const reviewedSnapshotKey = deleteConfirmReviewSnapshotKey.value
    if (!currentSnapshotKey || !reviewedSnapshotKey || currentSnapshotKey === reviewedSnapshotKey) return
    const restoreNode = resolveDeletionReviewDeviceDialogRestore(
      'board-changed',
      nodes.value,
      deleteConfirmSourceDeviceDialogNodeId,
      deleteConfirmDialogData.node?.id
    )
    clearDeleteConfirmDialog()
    if (restoreNode) {
      bindDeviceDialogNode(restoreNode)
      deviceDialogReturnFocusNodeId = restoreNode.id
      dialogVisible.value = true
    }
    notifyBlocked(t('app.deviceDeletionPreviewChanged'))
  },
  { flush: 'sync' }
)


type DeviceDeletionOutcome = {
  responseConfirmed: boolean
  stalePreview?: boolean
}

const forceDeleteNode = async (
  nodeId: string,
  impactToken: string
): Promise<DeviceDeletionOutcome | null> => {
  if (!ensureBoardDataReady(['nodes', 'environment', 'rules', 'specs'])) return null
  return enqueueBoardMutation(async () => {
    const bundledEnvironmentNamesBeforeDelete = [...bundledBoardEnvironmentNames.value]
    try {
      const mutation = await boardApi.deleteNode(nodeId, impactToken)
      commitSemanticScene({
        nodes: mutation.currentNodes,
        environmentVariables: mutation.environmentVariables,
        rules: mutation.currentRules,
        specs: mutation.currentSpecifications,
        availability: mutation
      })
      // Suppress individual environment change notifications; the summary notification below
      // already includes the environment variable count, so separate toasts would be redundant.
      reportEnvironmentChanges(mutation.environmentChanges, bundledEnvironmentNamesBeforeDelete, true)
      return { responseConfirmed: true }
    } catch (error: any) {
      console.error('Failed to delete device', error)
      const message = extractApiErrorMessage(error, t('app.deleteDeviceFailedRetry'))
      if (error?.response?.status === 409) {
        await Promise.all([refreshDevices(), refreshEnvironmentVariables(), refreshRules(), refreshSpecifications()])
        await reloadUndoAvailability()
        notifyBlocked(message)
        return { responseConfirmed: false, stalePreview: true }
      } else if (error?.response?.status === 404) {
        const refreshed = await refreshSceneForReconciliation()
        if (refreshed) markVerificationResultStale()
        if (refreshed && !nodes.value.some(node => node.id === nodeId)) {
          notifyBlocked(t('app.deviceDeleteOutcomeRefreshed'))
          return { responseConfirmed: false }
        }
        notifyError(message)
      } else if (!isDefinitiveMutationRejection(error)) {
        const refreshed = await refreshSceneForReconciliation()
        if (refreshed) markVerificationResultStale()
        if (!refreshed) {
          notifyBlocked(t('app.deviceDeleteOutcomeUnknownRefreshFailed'))
        } else if (!nodes.value.some(node => node.id === nodeId)) {
          notifyBlocked(t('app.deviceDeleteOutcomeRefreshed'))
          return { responseConfirmed: false }
        } else {
          notifyBlocked(t('app.deviceDeleteOutcomeUnconfirmedAfterRefresh'))
        }
      } else {
        notifyError(message)
      }
      return null
    }
  })
}

const deleteCurrentNodeWithConfirm = async (nodeId: string) => {
  if (deletePreviewLoading.value || deleteConfirmSubmitting.value || deleteConfirmDialogVisible.value) return
  if (!ensurePlaybackClosedForMutation()) return
  if (!ensureBoardDataReady(['nodes', 'environment', 'rules', 'specs'])) return
  const currentNode = resolveCurrentBoardNode(nodes.value, nodeId)
  if (!currentNode) return

  deleteDialogReturnFocusNodeId = nodeId
  deleteConfirmSourceDeviceDialogNodeId = dialogVisible.value && dialogMeta.nodeId === nodeId
    ? nodeId
    : null
  const requestEpoch = ++deletePreviewRequestEpoch
  deletePreviewNodeId = nodeId
  deletePreviewLoading.value = true
  deleteConfirmReviewSnapshotKey.value = null
  deleteConfirmDialogData.node = currentNode
  deleteConfirmDialogData.hasRelations = false
  deleteConfirmDialogData.relationCount = { rules: 0, specs: 0 }
  deleteConfirmDialogData.relatedRules = []
  deleteConfirmDialogData.relatedSpecs = []
  deleteConfirmDialogData.environmentChanges = []
  deleteConfirmDialogData.impactToken = ''
  deleteConfirmDialogVisible.value = true
  try {
    const preview = await enqueueBoardMutation(async () => {
      const response = await boardApi.previewNodeDeletion(nodeId)
      if (requestEpoch !== deletePreviewRequestEpoch
        || deletePreviewNodeId !== nodeId
        || !resolveCurrentBoardNode(nodes.value, nodeId)) return null

      replaceNodesFromServer(response.currentNodes)
      environmentVariables.value = response.environmentVariables
      rules.value = response.currentRules
      specifications.value = response.currentSpecifications
      syncRuleDerivedEdges()
      return response
    })
    if (!preview) return
    if (requestEpoch !== deletePreviewRequestEpoch
      || deletePreviewNodeId !== nodeId
      || !resolveCurrentBoardNode(nodes.value, nodeId)) return
    const impactToken = preview.impactToken?.trim()
    if (!impactToken) throw new Error(t('app.deviceDeletionPreviewFailed'))
    const relatedRules = preview.removedRules
    const relatedSpecs = preview.removedSpecifications
    const environmentChanges = preview.environmentChanges
    deleteConfirmDialogData.node = preview.deletedDevice
    deleteConfirmDialogData.hasRelations = relatedRules.length > 0
      || relatedSpecs.length > 0
      || environmentChanges.length > 0
    deleteConfirmDialogData.relationCount = {
      rules: relatedRules.length,
      specs: relatedSpecs.length
    }
    deleteConfirmDialogData.relatedRules = relatedRules.map((rule, index) =>
      rule.name || t('app.ruleNumber', { number: index + 1 }))
    deleteConfirmDialogData.relatedSpecs = relatedSpecs.map((spec, index) =>
      getSpecResultDisplayTitle(spec, index))
    deleteConfirmDialogData.environmentChanges = environmentChanges
    deleteConfirmDialogData.impactToken = impactToken
    deleteConfirmReviewSnapshotKey.value = currentDeletionReviewSnapshotKey()
  } catch (error: any) {
    if (requestEpoch !== deletePreviewRequestEpoch || deletePreviewNodeId !== nodeId) return
    if (error?.response?.status === 404) {
      const refreshed = await enqueueBoardMutation(refreshSceneForReconciliation).catch(() => false)
      if (refreshed && !resolveCurrentBoardNode(nodes.value, nodeId)) {
        clearDeleteConfirmDialog()
        notifyBlocked(t('app.deviceDeleteOutcomeRefreshed'))
        return
      }
      if (requestEpoch !== deletePreviewRequestEpoch || deletePreviewNodeId !== nodeId) return
    }
    clearDeleteConfirmDialog()
    notifyError(localizedErrorMessage(error, t('app.deviceDeletionPreviewFailed'), locale.value))
  } finally {
    if (requestEpoch === deletePreviewRequestEpoch && deletePreviewNodeId === nodeId) {
      deletePreviewLoading.value = false
      deletePreviewNodeId = null
    }
  }
}

const handleDialogDelete = () => {
  if (!dialogMeta.nodeId) return
  void deleteCurrentNodeWithConfirm(dialogMeta.nodeId)
}

// Custom dialog handlers
const confirmRename = async () => {
  if (renameDialogSubmitting.value
    || !renameDialogData.node
    || renameNameBlockedReason.value) return

  renameDialogSubmitting.value = true
  try {
    const newLabel = renameDialogData.newName.trim()
    const saved = await handleRenameDevice(
      renameDialogData.node.id,
      newLabel,
      renameDialogData.originalLabel
    )
    if (!saved) return
    renameDialogVisible.value = false
    renameDialogData.node = null
    renameDialogData.newName = ''
    renameDialogData.originalLabel = ''
    reportEnvironmentChanges(saved.environmentChanges)
    if (!saved.responseConfirmed) {
      notifyBlocked(t('app.deviceRenameOutcomeRefreshed', { name: newLabel }))
    }
  } finally {
    renameDialogSubmitting.value = false
    reconcileRenameDialogWithBoard()
  }
}

const isRenameDialogOpen = computed(() => renameDialogVisible.value)
const {
  setDialogRef: setRenameDialogRef,
  handleModalKeydown: handleRenameDialogKeydown
} = useModalAccessibility(
  isRenameDialogOpen,
  cancelRename,
  () => getDeviceNodeElement(renameDialogReturnFocusNodeId)
)

const confirmDelete = async () => {
  if (deleteConfirmSubmitting.value) return
  if (!ensurePlaybackClosedForMutation()) return
  if (deletePreviewLoading.value
    || !deleteConfirmDialogData.node
    || !deleteConfirmDialogData.impactToken.trim()) return

  const deletion = {
    nodeId: deleteConfirmDialogData.node.id,
    nodeName: deleteConfirmDialogData.node.label,
    impactToken: deleteConfirmDialogData.impactToken,
    ruleCount: deleteConfirmDialogData.relationCount.rules,
    specCount: deleteConfirmDialogData.relationCount.specs,
    environmentChangeCount: deleteConfirmDialogData.environmentChanges.length
  }
  invalidateDeletePreview()
  deleteConfirmSubmitting.value = true
  let reopenDeletionPreview = false
  try {
    const outcome = await forceDeleteNode(
      deletion.nodeId,
      deletion.impactToken
    )
    if (!outcome) return
    if (outcome.stalePreview) {
      clearDeleteConfirmDialog()
      reopenDeletionPreview = true
      return
    }
    if (outcome.responseConfirmed) {
      notifySuccess(t('app.deviceDeleteSuccessSummary', {
        name: deletion.nodeName,
        rules: deletion.ruleCount,
        specs: deletion.specCount,
        variables: deletion.environmentChangeCount
      }))
    }
    // 如果设备详情对话框是打开的，也要关闭它
    if (dialogVisible.value) {
      dialogVisible.value = false
    }
    clearDeleteConfirmDialog()
  } catch (error) {
    console.error('Failed to delete device:', error)
    notifyError(t('app.deleteDeviceFailedRetry'))
  } finally {
    deleteConfirmSubmitting.value = false
    if (reopenDeletionPreview) void deleteCurrentNodeWithConfirm(deletion.nodeId)
  }
}

const cancelDelete = () => {
  if (deleteConfirmSubmitting.value) return
  clearDeleteConfirmDialog()
}

const isDeleteConfirmDialogOpen = computed(() => deleteConfirmDialogVisible.value)
const {
  setDialogRef: setDeleteConfirmDialogRef,
  handleModalKeydown: handleDeleteConfirmDialogKeydown
} = useModalAccessibility(
  isDeleteConfirmDialogOpen,
  cancelDelete,
  getDeleteDialogFallbackFocus
)

const deleteNodeFromStatus = (nodeId: string) => deleteCurrentNodeWithConfirm(nodeId)

const normalizeConfirmationText = (value: unknown) => String(value ?? '').trim()

// Delete confirmation must track the fields the backend treats as authored
// semantics. Labels, formulas, timestamps, and device display caches are rebuilt
// by the server and must not turn an otherwise safe confirmation into a false
// stale warning after a refresh.
const authoredRuleConfirmationSnapshot = (rule: RuleForm) => ({
  id: normalizeConfirmationText(rule.id),
  name: normalizeConfirmationText(rule.name),
  sources: (rule.sources || []).map(source => ({
    fromId: normalizeConfirmationText(source.fromId),
    fromApi: normalizeConfirmationText(source.fromApi),
    itemType: normalizeConfirmationText(source.itemType).toLowerCase(),
    relation: normalizeConfirmationText(source.relation),
    value: normalizeConfirmationText(source.value)
  })).sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right))),
  toId: normalizeConfirmationText(rule.toId),
  toApi: normalizeConfirmationText(rule.toApi),
  contentDevice: normalizeConfirmationText(rule.contentDevice),
  content: normalizeConfirmationText(rule.content)
})

const authoredSpecificationConditionSnapshot = (condition: Specification['aConditions'][number]) => ({
  deviceId: normalizeConfirmationText(condition.deviceId),
  targetType: normalizeConfirmationText(condition.targetType).toLowerCase(),
  key: normalizeConfirmationText(condition.key),
  propertyScope: normalizeConfirmationText(condition.propertyScope).toLowerCase(),
  relation: normalizeConfirmationText(condition.relation),
  value: normalizeConfirmationText(condition.value)
})

const sortConfirmationConditions = <T,>(conditions: T[]) =>
  conditions.sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right)))

const authoredSpecificationConfirmationSnapshot = (specification: Specification) => ({
  id: normalizeConfirmationText(specification.id),
  templateId: normalizeConfirmationText(specification.templateId),
  aConditions: sortConfirmationConditions(
    (specification.aConditions || []).map(authoredSpecificationConditionSnapshot)
  ),
  ifConditions: sortConfirmationConditions(
    (specification.ifConditions || []).map(authoredSpecificationConditionSnapshot)
  ),
  thenConditions: sortConfirmationConditions(
    (specification.thenConditions || []).map(authoredSpecificationConditionSnapshot)
  )
})

const pendingBoardItemDeletes = new Set<string>()
const beginBoardItemDelete = (key: string) => {
  if (pendingBoardItemDeletes.has(key)) return false
  pendingBoardItemDeletes.add(key)
  return true
}
const finishBoardItemDelete = (key: string) => pendingBoardItemDeletes.delete(key)

// A confirmation dialog can stay open while a scene replacement or an external
// refresh changes the board.  Re-check both the scene generation and the exact
// item snapshot before sending a targeted delete, otherwise a reused id could
// delete an unrelated item from the newer scene.
const isConfirmedBoardItemCurrent = <T extends { id?: string }>(
  expectedGeneration: number,
  collection: T[],
  itemId: string,
  expectedItem: T,
  changedMessage: string,
  snapshotOf: (item: T) => unknown = item => item
): boolean => {
  const status = getConfirmedBoardItemStatus(
    expectedGeneration,
    boardSceneGeneration,
    isSceneReplacementInProgress.value,
    collection,
    itemId,
    expectedItem,
    snapshotOf
  )
  if (status === 'scene-changed') {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return false
  }
  if (status === 'item-changed') {
    notifyBlocked(changedMessage)
    return false
  }
  return true
}

/**
 * 删除规则（edges 由 rules 动态生成）
 */
const deleteRule = async (ruleId: string) => {
  if (!ensurePlaybackClosedForMutation()) return
  if (!ensureBoardDataReady(['rules'])) return
  const ruleToDelete = rules.value.find(r => r.id === ruleId)
  if (!ruleToDelete) return
  const pendingKey = `rule:${ruleId}`
  if (!beginBoardItemDelete(pendingKey)) return
  const confirmationSceneGeneration = boardSceneGeneration
  const confirmedRuleSnapshot = deepClone(ruleToDelete)

  try {
    if (!await confirmDestructive({
      title: t('app.deleteRuleConfirmTitle'),
      message: t('app.deleteRuleConfirmMessage', { name: ruleToDelete.name || t('app.unnamedRule') }),
      confirmText: t('app.delete')
    })) return

    const snapshotOf = authoredRuleConfirmationSnapshot
    if (!isConfirmedBoardItemCurrent(
      confirmationSceneGeneration,
      rules.value,
      ruleId,
      confirmedRuleSnapshot,
      t('app.ruleChangedBeforeDelete'),
      snapshotOf
    )) return

    await enqueueBoardMutation(async () => {
      if (!isConfirmedBoardItemCurrent(
        confirmationSceneGeneration,
        rules.value,
        ruleId,
        confirmedRuleSnapshot,
        t('app.ruleChangedBeforeDelete'),
        snapshotOf
      )) return
      try {
        const mutation = await boardApi.removeRule(confirmedRuleSnapshot)
        commitSemanticScene({ rules: mutation.currentItems, availability: mutation })
        notifySuccess(t('app.deleteRuleSuccess'))
      } catch (error: any) {
        console.error('Failed to delete rule', error)
        const refreshed = await refreshRules()
        await reloadUndoAvailability()
        if (refreshed && !rules.value.some(rule => rule.id === ruleId)) {
          notifyBlocked(t('app.ruleDeleteOutcomeRefreshed'))
          return
        }
        notifyError(localizedErrorMessage(error, t('app.deleteRuleFailed'), locale.value))
      }
    })
  } finally {
    finishBoardItemDelete(pendingKey)
  }
}

const moveRule = async (ruleId: string, direction: 'up' | 'down') => {
  if (!ensurePlaybackClosedForMutation() || rulesReordering.value) return
  if (!ensureBoardDataReady(['rules'])) return
  rulesReordering.value = true
  try {
    await enqueueBoardMutation(async () => {
      const currentIndex = rules.value.findIndex(rule => rule.id === ruleId)
      const targetIndex = direction === 'up' ? currentIndex - 1 : currentIndex + 1
      if (currentIndex < 0 || targetIndex < 0 || targetIndex >= rules.value.length) return

      const reordered = [...rules.value]
      const expectedOrder = rules.value.map(rule => String(rule.id || ''))
      const movedRule = reordered[currentIndex]
      reordered[currentIndex] = reordered[targetIndex]
      reordered[targetIndex] = movedRule
      const requestedOrder = reordered.map(rule => String(rule.id || ''))
      try {
        const mutation = await boardApi.reorderRules(expectedOrder, requestedOrder)
        commitSemanticScene({ rules: mutation.rules, availability: mutation })
        // Re-cue the moved rule so a repeated press stays oriented; each press restarts its lifetime.
        focusHighlight.show('rule', ruleId)
        notifySuccess(t('app.ruleOrderUpdated'))
      } catch (error: any) {
        console.error('Failed to save rule execution order', error)
        const refreshed = await refreshRules()
        await reloadUndoAvailability()
        const currentOrder = rules.value.map(rule => String(rule.id || ''))
        if (!isDefinitiveMutationRejection(error)
          && refreshed && currentOrder.length === requestedOrder.length
          && currentOrder.every((id, index) => id === requestedOrder[index])) {
          focusHighlight.show('rule', ruleId)
          notifyBlocked(t('app.ruleOrderOutcomeRefreshed'))
        } else {
          notifyError(extractApiErrorMessage(error, t('app.ruleOrderUpdateFailed')))
        }
      }
    })
  } finally {
    rulesReordering.value = false
  }
}

const deleteSpecification = async (specId: string) => {
  if (!ensurePlaybackClosedForMutation()) return
  if (!ensureBoardDataReady(['specs'])) return
  const specToDelete = specifications.value.find(s => s.id === specId)
  if (!specToDelete) return
  const pendingKey = `spec:${specId}`
  if (!beginBoardItemDelete(pendingKey)) return
  const confirmationSceneGeneration = boardSceneGeneration
  const confirmedSpecificationSnapshot = deepClone(specToDelete)

  try {
    if (!await confirmDestructive({
      title: t('app.deleteSpecConfirmTitle'),
      message: t('app.deleteSpecConfirmMessage', {
        name: getSpecResultDisplayTitle(specToDelete, 0) || t('app.unnamedSpecification')
      }),
      confirmText: t('app.delete')
    })) return

    const snapshotOf = authoredSpecificationConfirmationSnapshot
    if (!isConfirmedBoardItemCurrent(
      confirmationSceneGeneration,
      specifications.value,
      specId,
      confirmedSpecificationSnapshot,
      t('app.specificationChangedBeforeDelete'),
      snapshotOf
    )) return

    await enqueueBoardMutation(async () => {
      if (!isConfirmedBoardItemCurrent(
        confirmationSceneGeneration,
        specifications.value,
        specId,
        confirmedSpecificationSnapshot,
        t('app.specificationChangedBeforeDelete'),
        snapshotOf
      )) return
      try {
        const mutation = await boardApi.removeSpec(confirmedSpecificationSnapshot)
        commitSemanticScene({ specs: mutation.currentItems, availability: mutation })
        notifySuccess(t('app.deleteSpecSuccess'))
      } catch (error: any) {
        console.error('Failed to delete specification', error)
        const refreshed = await refreshSpecifications()
        await reloadUndoAvailability()
        if (refreshed && !specifications.value.some(spec => spec.id === specId)) {
          notifyBlocked(t('app.specDeleteOutcomeRefreshed'))
          return
        }
        notifyError(localizedErrorMessage(error, t('app.deleteSpecFailed'), locale.value))
      }
    })
  } finally {
    finishBoardItemDelete(pendingKey)
  }
}

/* =================================================================================
 * 9. API Interactions (Save)
 * ================================================================================= */

const buildBoardLayoutPayload = (): BoardLayoutDto => {
  return {
    canvasPan: { x: canvasPan.value.x, y: canvasPan.value.y },
    canvasZoom: canvasZoom.value,
    panels: {
      control: {
        collapsed: boardPanels.control.collapsed,
        width: boardPanels.control.width,
        activeSection: boardPanels.control.activeSection
      },
      inspector: {
        collapsed: boardPanels.inspector.collapsed,
        width: boardPanels.inspector.width,
        activeSection: boardPanels.inspector.activeSection
      }
    }
  }
}

const applyBoardLayout = (layout?: BoardLayoutDto | null) => {
  if (!layout) return

  if (layout.canvasPan && !canvasStateTouchedBeforeLayout) {
    canvasPan.value = {
      x: Number.isFinite(layout.canvasPan.x) ? layout.canvasPan.x : 0,
      y: Number.isFinite(layout.canvasPan.y) ? layout.canvasPan.y : 0
    }
  }
  if (typeof layout.canvasZoom === 'number' && !canvasStateTouchedBeforeLayout) {
    canvasZoom.value = Number.isFinite(layout.canvasZoom) && layout.canvasZoom > 0
      ? Math.min(MAX_ZOOM, layout.canvasZoom)
      : 1
  }

  const shouldApplyPanelLayout = !panelStateTouchedBeforeLayout
  const control = layout.panels?.control
  if (control && shouldApplyPanelLayout) {
    boardPanels.control.collapsed = Boolean(control.collapsed)
    boardPanels.control.width = clampPanelWidth(control.width, DEFAULT_CONTROL_PANEL_WIDTH)
    boardPanels.control.activeSection = isControlCenterSection(control.activeSection)
      ? control.activeSection
      : 'devices'
  }

  const inspector = layout.panels?.inspector
  if (inspector && shouldApplyPanelLayout) {
    boardPanels.inspector.collapsed = Boolean(inspector.collapsed)
    boardPanels.inspector.width = clampPanelWidth(inspector.width, DEFAULT_INSPECTOR_PANEL_WIDTH)
    boardPanels.inspector.activeSection = isInspectorSection(inspector.activeSection)
      ? inspector.activeSection
      : 'devices'
  }

  applyViewportPanelConstraints()
}

type QueuedBoardLayoutSave = {
  payload: BoardLayoutDto
  authToken: string
  silent: boolean
  resolve: Array<(saved: boolean) => void>
}

let pendingBoardLayoutSave: QueuedBoardLayoutSave | null = null
let boardLayoutSaveDrainRunning = false
let boardLayoutSaveIdleResolvers: Array<() => void> = []

const persistBoardLayout = async (request: QueuedBoardLayoutSave): Promise<boolean> => {
  if (currentAuthUserId.value !== boardOwnerUserId || !isCurrentSessionToken(request.authToken)) return false
  try {
    await boardApi.saveLayout(request.payload, request.authToken)
    if (currentAuthUserId.value !== boardOwnerUserId || !isCurrentSessionToken(request.authToken)) return false
    persistedWideLayout = request.payload
    layoutSaveErrorShown = false
    return true
  } catch (e) {
    const mayShowFeedback = !request.silent
      && !layoutSaveFeedbackSuppressed
      && !boardLifecycleDisposed
      && currentAuthUserId.value === boardOwnerUserId
      && isCurrentSessionToken(request.authToken)
    if (mayShowFeedback) {
      console.error('Failed to save canvas layout', e)
      if (!layoutSaveErrorShown) {
        layoutSaveErrorShown = true
        notifyError(t('app.saveLayoutFailed'))
      }
    }
    return false
  }
}

const drainBoardLayoutSaveQueue = async () => {
  if (boardLayoutSaveDrainRunning) return
  boardLayoutSaveDrainRunning = true
  try {
    while (pendingBoardLayoutSave) {
      const request = pendingBoardLayoutSave
      pendingBoardLayoutSave = null
      const saved = await persistBoardLayout(request)
      request.resolve.forEach(resolve => resolve(saved))
    }
  } finally {
    boardLayoutSaveDrainRunning = false
    const idleResolvers = boardLayoutSaveIdleResolvers
    boardLayoutSaveIdleResolvers = []
    idleResolvers.forEach(resolve => resolve())
  }
}

const waitForBoardLayoutSaveIdle = (): Promise<void> => {
  if (!boardLayoutSaveDrainRunning && !pendingBoardLayoutSave) return Promise.resolve()
  return new Promise(resolve => boardLayoutSaveIdleResolvers.push(resolve))
}

const saveBoardLayout = (options: { silent?: boolean } = {}): Promise<boolean> => {
  const requestToken = getToken()
  if (!requestToken || currentAuthUserId.value !== boardOwnerUserId
    || !isCurrentSessionToken(requestToken)) return Promise.resolve(false)
  const payload = buildBoardLayoutPayload()
  return new Promise(resolve => {
    if (pendingBoardLayoutSave && pendingBoardLayoutSave.authToken !== requestToken) {
      pendingBoardLayoutSave.resolve.forEach(finish => finish(false))
      pendingBoardLayoutSave = null
    }
    if (pendingBoardLayoutSave) {
      pendingBoardLayoutSave.payload = payload
      pendingBoardLayoutSave.silent = options.silent === true
      pendingBoardLayoutSave.resolve.push(resolve)
    } else {
      pendingBoardLayoutSave = {
        payload,
        authToken: requestToken,
        silent: options.silent === true,
        resolve: [resolve]
      }
    }
    void drainBoardLayoutSaveQueue()
  })
}

const flushPendingBoardLayout = async (options: {
  silent?: boolean
  timeoutMs?: number
} = {}): Promise<boolean> => {
  const hasPendingSave = layoutSaveTimer !== null
  if (layoutSaveTimer) {
    clearTimeout(layoutSaveTimer)
    layoutSaveTimer = null
  }

  const flush = async () => {
    let saved = true
    if (hasPendingSave && layoutHydrated.value && getToken() && !isNarrowViewport()) {
      saved = await saveBoardLayout({ silent: options.silent })
    }
    await waitForBoardLayoutSaveIdle()
    return saved
  }

  if (!options.timeoutMs || options.timeoutMs <= 0) return flush()

  let timeout: ReturnType<typeof setTimeout> | null = null
  try {
    return await Promise.race([
      flush(),
      new Promise<boolean>(resolve => {
        timeout = setTimeout(() => resolve(false), options.timeoutMs)
      })
    ])
  } finally {
    if (timeout) clearTimeout(timeout)
  }
}

const scheduleBoardLayoutSave = () => {
  if (!layoutHydrated.value || boardLifecycleDisposed || !getToken() || isNarrowViewport()) return
  if (layoutSaveTimer) {
    clearTimeout(layoutSaveTimer)
  }
  layoutSaveTimer = setTimeout(() => {
    layoutSaveTimer = null
    void saveBoardLayout()
  }, LAYOUT_SAVE_DEBOUNCE_MS)
}

watch(
  () => [
    canvasPan.value.x,
    canvasPan.value.y,
    canvasZoom.value,
    boardPanels.control.collapsed,
    boardPanels.control.width,
    boardPanels.control.activeSection,
    boardPanels.inspector.collapsed,
    boardPanels.inspector.width,
    boardPanels.inspector.activeSection
  ],
  // Restoring the initial layout happens before layoutHydrated; observe that boundary synchronously.
  scheduleBoardLayoutSave,
  { flush: 'sync' }
)

const openControlSection = (section: InspectorSection) => {
  const controlSection: ControlCenterSection = section === 'rules'
    ? 'rules'
    : section === 'specs'
      ? 'specs'
      : 'devices'

  if (!layoutHydrated.value) {
    panelStateTouchedBeforeLayout = true
  }
  boardPanels.control.collapsed = false
  if (isNarrowViewport()) boardPanels.inspector.collapsed = true
  boardPanels.control.activeSection = controlSection
}

const handleControlCollapsedUpdate = (value: boolean) => {
  if (!layoutHydrated.value) {
    panelStateTouchedBeforeLayout = true
  }
  boardPanels.control.collapsed = value
  if (!value && isNarrowViewport()) boardPanels.inspector.collapsed = true
  if (!value) void nextTick(() => focusSidePanelTab(document.querySelector('[data-testid="control-center"]')))
}

const handleControlActiveSectionUpdate = (value: ControlCenterSection) => {
  if (!layoutHydrated.value) {
    panelStateTouchedBeforeLayout = true
  }
  boardPanels.control.activeSection = value
}

const handleInspectorCollapsedUpdate = (value: boolean) => {
  if (!layoutHydrated.value) {
    panelStateTouchedBeforeLayout = true
  }
  boardPanels.inspector.collapsed = value
  if (!value && isNarrowViewport()) boardPanels.control.collapsed = true
  if (!value) void nextTick(() => focusSidePanelTab(document.querySelector('[data-testid="system-inspector"]')))
}

const handleInspectorActiveSectionUpdate = (value: InspectorSection) => {
  if (!layoutHydrated.value) {
    panelStateTouchedBeforeLayout = true
  }
  boardPanels.inspector.activeSection = value
}

// 从 rules 动态生成 edges（不单独存储到服务器）
const generateEdgesFromRules = (): DeviceEdge[] => {
  const result: DeviceEdge[] = []

  for (const [ruleIndex, rule] of rules.value.entries()) {
    if (!rule.sources || !rule.toId) continue

    const toNode = resolveNodeRef(rule.toId)
    if (!toNode) continue
    
    for (const [sourceIndex, source] of rule.sources.entries()) {
      const fromId = source.fromId
      if (!fromId) continue
      
      const fromNode = resolveNodeRef(fromId)
      if (!fromNode) continue
      
      const { fromPoint, toPoint } = getLinkPoints(fromNode, toNode)
      
      result.push({
        id: `edge_${rule.id}_${sourceIndex}_${fromId}`,
        from: fromNode.id,
        to: toNode.id,
        fromLabel: fromNode.label,
        toLabel: toNode.label,
        fromPos: fromPoint,
        toPos: toPoint,
        fromApi: source.fromApi || '',
        toApi: rule.toApi || '',
        itemType: source.itemType,
        relation: source.relation || '',
        value: source.value === null || source.value === undefined ? '' : String(source.value),
        ruleId: rule.id,
        ruleIndex,
        sourceIndex
      })
    }
  }

  return result
}

const syncRuleDerivedEdges = () => {
  edges.value = generateEdgesFromRules()
}


const refreshDeviceTemplates = async (): Promise<boolean> => {
  templatesLoading.value = true
  boardDataLoadState.templates = 'loading'
  try {
    // A catalog read is side-effect free. Restoring bundled defaults is an explicit,
    // previewed mutation in ControlCenter.
    const res = await boardApi.getDeviceTemplates()
    deviceTemplates.value = res || []
    boardDataLoadState.templates = 'ready'
    return true
  } catch (e) {
    console.error('Failed to load device templates:', e)
    boardDataLoadState.templates = 'error'
    return false
  } finally {
    templatesLoading.value = false
  }
}

const replaceTemplateState = (state: {
  templates: DeviceTemplate[]
  environmentVariables: ModelEnvironmentVariable[]
}) => {
  deviceTemplates.value = state.templates
  environmentVariables.value = state.environmentVariables
  boardDataLoadState.templates = 'ready'
  boardDataLoadState.environment = 'ready'
  void reloadUndoAvailability()
}

const replaceTemplateCatalog = (templates: DeviceTemplate[]) => {
  deviceTemplates.value = templates
  boardDataLoadState.templates = 'ready'
  void reloadUndoAvailability()
}

const handleAuthoritativeBoardStateUnavailable = (
  keys: Array<'templates' | 'environment'>
) => {
  const affectedKeys = new Set(keys)
  affectedKeys.forEach(key => { boardDataLoadState[key] = 'error' })
  templatesLoading.value = false
  invalidateCurrentBoardModelFingerprints()
  invalidateRecommendationsForSceneChange({ notify: true })
}



/* =================================================================================
 * 10. Lifecycle & Watchers
 * ================================================================================= */

// 1. 定义刷新设备的函数
const refreshDevices = async (): Promise<boolean> => {
  boardDataLoadState.nodes = 'loading'
  try {
    const loadedNodes = await boardApi.getNodes()
    replaceNodesFromServer(loadedNodes)
    reconcileDanglingBoardFocus({ nodes: nodes.value })
    syncRuleDerivedEdges()
    boardDataLoadState.nodes = 'ready'
    return true
  } catch(e) {
    console.error('Failed to load devices', e)
    boardDataLoadState.nodes = 'error'
    return false
  }
}

const refreshEnvironmentVariables = async (): Promise<boolean> => {
  boardDataLoadState.environment = 'loading'
  try {
    environmentVariables.value = await boardApi.getEnvironment()
    boardDataLoadState.environment = 'ready'
    return true
  } catch (e) {
    console.error('Failed to load the environment variable pool', e)
    boardDataLoadState.environment = 'error'
    return false
  }
}

const refreshBoardSnapshot = async (): Promise<boolean> => {
  const authScopeEpoch = boardAuthScopeEpoch
  boardSnapshotError.value = null
  allBoardDataKeys.forEach(key => { boardDataLoadState[key] = 'loading' })
  templatesLoading.value = true
  try {
    const snapshot = await boardApi.getSnapshot()
    if (!isCurrentBoardAuthScope(authScopeEpoch)) return false
    deviceTemplates.value = snapshot.deviceTemplates
    replaceNodesFromServer(snapshot.nodes, { externalRefresh: true })
    environmentVariables.value = snapshot.environmentVariables
    rules.value = snapshot.rules
    specifications.value = snapshot.specifications
    reconcileDanglingBoardFocus({
      nodes: nodes.value,
      rules: snapshot.rules,
      specs: snapshot.specifications
    })
    syncRuleDerivedEdges()
    hydratedBoardAuthScopeEpoch = authScopeEpoch
    allBoardDataKeys.forEach(key => { boardDataLoadState[key] = 'ready' })
    boardSnapshotError.value = null
    void refreshCurrentBoardModelFingerprints()
    // Undo history is server state and some commands reset or advance it (scene replacement,
    // automatic fixes, another tab's work). A wholesale reload must re-read it too, or the button
    // would keep offering an undo the journal no longer has. Called explicitly rather than left to
    // readiness transitions, so availability does not depend on a loading-state flicker.
    void loadBoardUndoAvailability()
    return true
  } catch (error) {
    if (!isCurrentBoardAuthScope(authScopeEpoch)) return false
    console.error('Failed to load the canvas semantic snapshot:', error)
    allBoardDataKeys.forEach(key => { boardDataLoadState[key] = 'error' })
    boardSnapshotError.value = errorDetails(error)
    return false
  } finally {
    if (isCurrentBoardAuthScope(authScopeEpoch)) templatesLoading.value = false
  }
}

let boardForegroundRefreshPromise: Promise<boolean> | null = null
let boardRefreshRequestedWhileBusy = false

interface BoardSnapshotRefreshOptions {
  force?: boolean
  queueIfBusy?: boolean
}

const requestBoardSnapshotRefresh = ({
  force = false,
  queueIfBusy = true
}: BoardSnapshotRefreshOptions = {}): Promise<boolean> => {
  if (boardLifecycleDisposed) return Promise.resolve(false)
  if (!force && document.visibilityState === 'hidden') {
    if (queueIfBusy) boardRefreshRequestedWhileBusy = true
    return Promise.resolve(false)
  }
  if (boardForegroundRefreshPromise) {
    if (queueIfBusy) boardRefreshRequestedWhileBusy = true
    return boardForegroundRefreshPromise
  }
  // Starting a refresh consumes any invalidation deferred while the tab was hidden.
  // New invalidations can request one follow-up; duplicate lifecycle events only reuse it.
  boardRefreshRequestedWhileBusy = false
  const refreshPromise = enqueueBoardMutation(refreshBoardSnapshot)
  boardForegroundRefreshPromise = refreshPromise
  void refreshPromise.finally(() => {
    if (boardForegroundRefreshPromise === refreshPromise) {
      boardForegroundRefreshPromise = null
    }
    if (boardRefreshRequestedWhileBusy && !boardLifecycleDisposed
      && document.visibilityState !== 'hidden') {
      boardRefreshRequestedWhileBusy = false
      requestBoardSnapshotRefresh()
    }
  })
  return refreshPromise
}

const refreshBoardOnForeground = () => {
  if (!boardForegroundRefreshPromise) invalidateCurrentBoardModelFingerprints()
  requestBoardSnapshotRefresh({ queueIfBusy: false })
}

const boardInvalidationBinding = createScopedBoardInvalidationBinding(
  subscribeBoardInvalidation,
  () => {
    invalidateCurrentBoardModelFingerprints()
    requestBoardSnapshotRefresh()
  }
)
boardInvalidationBinding.bind(currentAuthUserId.value)

const environmentPatchFieldLabel = (field: EnvironmentVariablePatchResult['suppliedFields'][number]) => {
  if (field === 'value') return t('app.variableValue')
  if (field === 'trust') return t('app.sourceLabel')
  return t('app.sensitivityLabel')
}

const formatEnvironmentPatchResults = (results: EnvironmentVariablePatchResult[]) =>
  results.map(result => {
    const fields = result.changedFields.length > 0 ? result.changedFields : result.suppliedFields
    return `${formatBoardEnvironmentModelToken(result.name, result.name)} (${fields.map(environmentPatchFieldLabel).join(', ')})`
  }).join('; ')

const saveEnvironmentVariables = async (
  patches: EnvironmentVariableUpdateRequest[],
  complete: (saved: boolean, failure?: ApiErrorDetails) => void
) => {
  if (!ensurePlaybackClosedForMutation()) return
  if (!ensureBoardDataReady(['nodes', 'templates', 'environment'])) return
  const requestToken = getToken()
  const requestAuthScopeEpoch = boardAuthScopeEpoch
  const isRequestCurrent = () => isCurrentSessionToken(requestToken) && isCurrentBoardAuthScope(requestAuthScopeEpoch)
  for (const [index, patch] of patches.entries()) {
    if (patch.desired.value === undefined) continue
    const definition = nodes.value.map(node => resolveImpactEnvironmentDefinition(resolveTemplateForNode(node)?.manifest, patch.name))
      .find(variable => variable !== undefined)
    const reason = !patch.desired.value.trim() ? t('app.variableValueRequired')
      : definition ? validateTemplateVariableValue(definition, patch.desired.value, t) : ''
    if (reason) {
      complete(false, { details: [], fields: { [`environmentVariables[${index}].desired.value`]: reason } })
      return
    }
  }
  if (environmentMutationPending.value) {
    notifyInfo(t('app.environmentSaveInProgress'))
    return
  }
  environmentMutationPending.value = true
  let saved = false
  let saveFailure: ApiErrorDetails | undefined
  try {
    await enqueueBoardMutation(async () => {
      try {
        const mutation = await boardApi.saveEnvironment(patches)
        if (!isRequestCurrent()) return
        commitSemanticScene({
          environmentVariables: mutation.environmentVariables,
          availability: mutation,
          semanticChanged: mutation.operation === 'updated'
        })
        saved = true
        const changedPatches = mutation.patchResults.filter(result => result.changedFields.length > 0)
        if (changedPatches.length === 0) {
          notifyInfo(t('app.environmentPatchUnchanged', {
            items: formatEnvironmentPatchResults(mutation.patchResults)
          }))
        }
        const changedPatchNames = new Set(changedPatches.map(result => result.name))
        reportEnvironmentChanges(mutation.environmentChanges.filter(
          change => !changedPatchNames.has(change.name)
        ))
      } catch (e: any) {
        console.error('Failed to save the environment variable pool', e)
        if (!isRequestCurrent()) return
        if (e?.response?.data?.data?.reasonCode === 'ENVIRONMENT_VARIABLE_STALE') {
          // A stale CAS can also mean that another tab removed a device or
          // template which sourced this variable. Reconcile the full semantic
          // snapshot so the inspector cannot keep ghost entries.
          const refreshed = await refreshBoardSnapshot()
          if (!isRequestCurrent()) return
          await reloadUndoAvailability()
          if (!isRequestCurrent()) return
          if (refreshed) markVerificationResultStale()
          notifyBlocked(refreshed
            ? t('app.environmentVariableStaleRefreshed')
            : t('app.environmentVariableStaleRefreshFailed'))
        } else if (!isDefinitiveMutationRejection(e)) {
          const refreshed = await refreshBoardSnapshot()
          if (!isRequestCurrent()) return
          await reloadUndoAvailability()
          if (!isRequestCurrent()) return
          if (refreshed) markVerificationResultStale()
          notifyBlocked(refreshed
            ? t('app.environmentSaveOutcomeRefreshed')
            : t('app.environmentSaveOutcomeUnknownRefreshFailed'))
        } else {
          saveFailure = errorDetails(e)
        }
      }
    })
  } finally {
    environmentMutationPending.value = false
    if (isRequestCurrent()) complete(saved, saveFailure)
  }
}

// Portable-scene normalization/validation/canonicalization lives in board/portableScene.ts as
// pure functions; the codec binds the translator once so rejection messages stay localized.
const {
  requireIntegerInRange,
  optionalIntegerInRange,
  assertSceneTemplateCoverage,
  assertSceneEnvironmentCoverage,
  assertUniqueSceneDeviceIds,
  assertSceneReferences,
  serializeSceneFile,
  canonicalizeSceneFile,
  normalizeSceneFile
} = createSceneCodec(t)

const getReferencedSceneTemplates = (devices: DeviceNode[]) => {
  const names = new Set(devices.map(device => normalizeTemplateLookupName(device.templateName)).filter(Boolean))
  return deviceTemplates.value
    .filter(template => names.has(normalizeTemplateLookupName(template.name)) || names.has(normalizeTemplateLookupName(template.manifest?.Name)))
    .map(template => ({
      name: template.name || template.manifest.Name,
      manifest: deepClone(template.manifest)
    }))
}

const buildSceneExport = (): PortableSceneFile => {
  const devices = cloneVisibleDeviceNodes()
  const rulesForExport: RuleForm[] = deepClone(rules.value).map(rule => {
    const { id: _id, ...portableRule } = rule
    return portableRule
  })
  const sceneModel: BoardSceneModel = {
    schema: SCENE_FILE_SCHEMA,
    version: SCENE_FILE_VERSION,
    templates: getReferencedSceneTemplates(devices),
    devices,
    environmentVariables: deepClone(environmentVariables.value),
    rules: rulesForExport,
    specs: deepClone(specifications.value)
  }
  assertUniqueSceneDeviceIds(sceneModel.devices)
  assertSceneReferences(sceneModel)
  assertSceneTemplateCoverage(sceneModel)
  assertSceneEnvironmentCoverage(sceneModel)
  const portable = canonicalizeSceneFile(sceneModel)
  normalizeSceneFile(portable)
  return portable
}

const downloadJsonFile = (filename: string, payload: string | PortableSceneFile) => {
  const serialized = typeof payload === 'string' ? payload : (JSON.stringify(payload, null, 2) ?? 'null')
  const blob = new Blob([serialized], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

const exportScene = () => {
  if (!ensureBoardDataReady()) return
  let scene: PortableSceneFile
  try {
    scene = buildSceneExport()
  } catch (error) {
    notifyError(getSceneErrorMessage(error))
    return
  }
  const serialized = JSON.stringify(scene, null, 2)
  const exportBytes = new Blob([serialized], { type: 'application/json;charset=utf-8' }).size
  if (exportBytes > MAX_SCENE_IMPORT_BYTES) {
    notifyError(t('app.sceneExportTooLarge', { size: '64 MiB' }))
    return
  }
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-')
  downloadJsonFile(`iot-verify-scene-${timestamp}.json`, serialized)
  notifySuccess(t('app.sceneExportStarted', {
    devices: scene.devices.length,
    variables: scene.environmentVariables.length,
    rules: scene.rules.length,
    specs: scene.specs.length
  }))
}

const triggerSceneImport = () => {
  if (isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return
  }
  if (sceneImportInputRef.value) {
    sceneImportInputRef.value.value = ''
    sceneImportInputRef.value.click()
  }
}

const getSceneErrorMessage = (error: any) => {
  const rawMessage = String(error?.response?.data?.message || error?.message || '')
  const message = localizedErrorMessage(error, t('app.sceneImportFailed'), locale.value)

  // If there's no response, return the generic message
  if (!error?.response) return message

  // For structured validation errors with multiple fields, return the raw message
  // as it will be handled by showSceneImportError
  const errors = error?.response?.data?.data?.errors
  if (errors && typeof errors === 'object' && Object.keys(errors).length > 0) {
    return rawMessage || message
  }

  // For single field errors, try to extract and format the field path
  const field = rawMessage.match(/\b(?:templates|devices|nodes|environmentVariables|rules|specs)\[\d+](?:\.[A-Za-z0-9_]+)*/)?.[0]
  if (!field) return rawMessage || message
  return `${formatSceneValidationCoordinate(field, t)}: ${t('app.sceneImportValidationItemInvalid')}`
}

const showSceneImportError = async (error: any) => {
  const status = error?.response?.status
  const message = error?.response?.data?.message || error?.message || ''

  // A conflict's reason code owns its meaning; HTTP 409 also covers duplicate rules and specs.
  if (status === 409) {
    const data = error?.response?.data?.data
    const reasonCode = data?.reasonCode
    if (reasonCode !== 'SCENE_TEMPLATE_MISMATCH' || typeof data?.templateName !== 'string') {
      const duplicateRule = reasonCode === 'DUPLICATE_RULE'
      const duplicateSpec = reasonCode === 'DUPLICATE_SPECIFICATION'
      await acknowledgeFailure({
        title: t('app.sceneImportConflictTitle'),
        message: t(duplicateRule ? 'app.sceneImportDuplicateRule'
          : duplicateSpec ? 'app.sceneImportDuplicateSpecification' : 'app.sceneImportConflictGeneral'),
        suggestion: t(duplicateRule ? 'app.sceneImportDuplicateRuleHint'
          : duplicateSpec ? 'app.sceneImportDuplicateSpecificationHint' : 'app.sceneImportConflictGeneralHint'),
        ...errorDetails(error)
      })
      return
    }
    const templateName = data.templateName

    await acknowledge({
      title: t('app.sceneImportConflictTitle'),
      tone: 'error',
      message: h('div', { class: 'space-y-3 text-left' }, [
        h('p', { class: 'text-sm', style: { color: 'var(--text)' } },
          t('app.sceneImportTemplateMismatch', { name: templateName })),
        h('div', { class: 'mt-3 p-3 rounded', style: { backgroundColor: 'var(--surface-elevated)', border: '1px solid var(--border)' } }, [
          h('div', { class: 'text-sm font-semibold mb-2', style: { color: 'var(--text)' } }, t('app.suggestions')),
          h('ul', { class: 'text-sm space-y-1 list-disc list-inside', style: { color: 'var(--text-muted)' } }, [
            h('li', t('app.sceneImportSuggestion1')),
            h('li', t('app.sceneImportSuggestion2'))
          ])
        ]),
        h('details', { class: 'mt-3 text-xs', style: { color: 'var(--text-muted)' } }, [
          h('summary', { class: 'cursor-pointer font-semibold' }, t('app.technicalDetails')),
          h('div', { class: 'mt-1 whitespace-pre-wrap break-words font-mono' }, message)
        ])
      ]),
      confirmText: t('app.confirm')
    })
    return
  }

  // Handle 400 Bad Request - Scene format/reference errors
  if (status === 400 && getStructuredValidationErrors(error).length === 0) {
    // Unsupported scene version
    if (message.includes('Unsupported scene file')) {
      const versionMatch = message.match(/version (\d+).*version\s+(\d+|null)/s)
      const expectedVersion = versionMatch ? versionMatch[1] : t('app.unknown')
      const receivedVersion = versionMatch ? (versionMatch[2] === 'null' ? t('app.none') : versionMatch[2]) : t('app.unknown')

      await acknowledge({
        title: t('app.sceneImportVersionMismatch'),
        tone: 'error',
        message: h('div', { class: 'space-y-3 text-left' }, [
          h('p', { class: 'text-sm', style: { color: 'var(--text)' } },
            t('app.sceneImportVersionMismatchDesc')),
          h('div', { class: 'mt-2 p-3 rounded', style: { backgroundColor: 'var(--surface-elevated)', border: '1px solid var(--border)' } }, [
            h('div', { class: 'text-sm space-y-1', style: { color: 'var(--text-muted)' } }, [
              h('div', [h('span', { class: 'font-semibold' }, t('app.expectedVersion') + ': '), expectedVersion]),
              h('div', [h('span', { class: 'font-semibold' }, t('app.receivedVersion') + ': '), receivedVersion])
            ])
          ]),
          h('p', { class: 'mt-3 text-sm', style: { color: 'var(--text-muted)' } },
            t('app.sceneImportVersionMismatchSolution')),
          h('details', { class: 'mt-3 text-xs', style: { color: 'var(--text-muted)' } }, [
            h('summary', { class: 'cursor-pointer font-semibold' }, t('app.technicalDetails')),
            h('div', { class: 'mt-1 whitespace-pre-wrap break-words font-mono' }, message)
          ])
        ]),
        confirmText: t('app.confirm')
      })
      return
    }

    // Missing required collection
    if (message.includes('scene is missing its') || message.includes('scene is missing')) {
      const fieldMatch = message.match(/missing its (\w+)|missing (\w+)/)
      const field = fieldMatch ? (fieldMatch[1] || fieldMatch[2]) : t('app.unknown')
      const fieldName = field === 'devices' ? t('app.devices') :
                        field === 'templates' ? t('app.templates') :
                        field === 'environmentVariables' ? t('app.environmentVariables') :
                        field === 'rules' ? t('app.rules') :
                        field === 'specs' ? t('app.specifications') : field

      await acknowledge({
        title: t('app.sceneImportIncompleteTitle'),
        tone: 'error',
        message: h('div', { class: 'space-y-3 text-left' }, [
          h('p', { class: 'text-sm', style: { color: 'var(--text)' } },
            t('app.sceneImportMissingCollection', { collection: fieldName })),
          h('p', { class: 'mt-2 text-sm', style: { color: 'var(--text-muted)' } },
            t('app.sceneImportMissingCollectionFix')),
          h('details', { class: 'mt-3 text-xs', style: { color: 'var(--text-muted)' } }, [
            h('summary', { class: 'cursor-pointer font-semibold' }, t('app.technicalDetails')),
            h('div', { class: 'mt-1 whitespace-pre-wrap break-words font-mono' }, message)
          ])
        ]),
        confirmText: t('app.confirm')
      })
      return
    }

    // Conflicting template snapshots
    if (message.includes('conflicting template snapshots')) {
      const templateMatch = message.match(/snapshots for '([^']+)'/)
      const templateName = templateMatch ? templateMatch[1] : t('app.unknown')

      await acknowledge({
        title: t('app.sceneImportConflictTitle'),
        tone: 'error',
        message: h('div', { class: 'space-y-3 text-left' }, [
          h('p', { class: 'text-sm', style: { color: 'var(--text)' } },
            t('app.sceneImportDuplicateTemplate', { name: templateName })),
          h('p', { class: 'mt-2 text-sm', style: { color: 'var(--text-muted)' } },
            t('app.sceneImportDuplicateTemplateFix')),
          h('details', { class: 'mt-3 text-xs', style: { color: 'var(--text-muted)' } }, [
            h('summary', { class: 'cursor-pointer font-semibold' }, t('app.technicalDetails')),
            h('div', { class: 'mt-1 whitespace-pre-wrap break-words font-mono' }, message)
          ])
        ]),
        confirmText: t('app.confirm')
      })
      return
    }

    // Template reference errors
    if (message.includes('Scene references device template') && message.includes('without a matching template snapshot')) {
      const templateMatch = message.match(/template\s+'([^']+)'/)
      const templateName = templateMatch ? templateMatch[1] : t('app.unknown')

      await acknowledge({
        title: t('app.sceneImportIncompleteTitle'),
        tone: 'error',
        message: h('div', { class: 'space-y-3 text-left' }, [
          h('p', { class: 'text-sm', style: { color: 'var(--text)' } },
            t('app.sceneImportMissingTemplateSnapshot', { name: templateName })),
          h('div', { class: 'mt-3 p-3 rounded', style: { backgroundColor: 'var(--surface-elevated)', border: '1px solid var(--border)' } }, [
            h('div', { class: 'text-sm font-semibold mb-2', style: { color: 'var(--text)' } }, t('app.suggestions')),
            h('ul', { class: 'text-sm space-y-1 list-disc list-inside', style: { color: 'var(--text-muted)' } }, [
              h('li', t('app.sceneImportSuggestion3')),
              h('li', t('app.sceneImportSuggestion4'))
            ])
          ]),
          h('details', { class: 'mt-3 text-xs', style: { color: 'var(--text-muted)' } }, [
            h('summary', { class: 'cursor-pointer font-semibold' }, t('app.technicalDetails')),
            h('div', { class: 'mt-1 whitespace-pre-wrap break-words font-mono' }, message)
          ])
        ]),
        confirmText: t('app.confirm')
      })
      return
    }

    // Unreferenced template snapshot
    if (message.includes('unreferenced template snapshot')) {
      const templateMatch = message.match(/snapshot:\s*(.+)$/)
      const templateName = templateMatch ? templateMatch[1] : t('app.unknown')

      await acknowledge({
        title: t('app.sceneImportWarningTitle'),
        tone: 'warning',
        message: h('div', { class: 'space-y-3 text-left' }, [
          h('p', { class: 'text-sm', style: { color: 'var(--text)' } },
            t('app.sceneImportUnreferencedTemplate', { name: templateName })),
          h('p', { class: 'text-sm', style: { color: 'var(--text-muted)' } },
            t('app.sceneImportUnreferencedTemplateExplanation')),
          h('details', { class: 'mt-3 text-xs', style: { color: 'var(--text-muted)' } }, [
            h('summary', { class: 'cursor-pointer font-semibold' }, t('app.technicalDetails')),
            h('div', { class: 'mt-1 whitespace-pre-wrap break-words font-mono' }, message)
          ])
        ]),
        confirmText: t('app.confirm')
      })
      return
    }

    // Template name mismatch
    if (message.includes('must exactly match manifest.Name')) {
      const matches = message.match(/name\s+'([^']+)'.*manifest\.Name\s+'([^']+)'/)
      const snapshotName = matches ? matches[1] : ''
      const manifestName = matches ? matches[2] : ''

      await acknowledge({
        title: t('app.sceneImportInconsistentTitle'),
        tone: 'error',
        message: h('div', { class: 'space-y-3 text-left' }, [
          h('p', { class: 'text-sm', style: { color: 'var(--text)' } },
            t('app.sceneImportTemplateNameMismatchTitle')),
          h('div', { class: 'mt-2 p-3 rounded', style: { backgroundColor: 'var(--surface-elevated)', border: '1px solid var(--border)' } }, [
            h('div', { class: 'text-xs font-mono space-y-1', style: { color: 'var(--text-muted)' } }, [
              h('div', [h('span', { class: 'font-semibold' }, 'template.name: '), snapshotName]),
              h('div', [h('span', { class: 'font-semibold' }, 'manifest.Name: '), manifestName])
            ])
          ]),
          h('p', { class: 'mt-3 text-sm', style: { color: 'var(--text-muted)' } },
            t('app.sceneImportTemplateNameMismatchFix')),
          h('details', { class: 'mt-3 text-xs', style: { color: 'var(--text-muted)' } }, [
            h('summary', { class: 'cursor-pointer font-semibold' }, t('app.technicalDetails')),
            h('div', { class: 'mt-1 whitespace-pre-wrap break-words font-mono' }, message)
          ])
        ]),
        confirmText: t('app.confirm')
      })
      return
    }
  }

  // Handle 422 Validation errors with structured field errors
  const entries = getStructuredValidationErrors(error)
  if (entries.length === 0) {
    await acknowledgeFailure({
      title: t('app.sceneImportValidationTitle'),
      message: localizedErrorMessage(error, t('app.sceneImportFailed'), locale.value),
      suggestion: t('app.sceneImportValidationNextStep'),
      ...errorDetails(error)
    })
    return
  }

  // Group validation errors by type
  const envVarErrors = entries.filter(([field]) => field.startsWith('environmentVariables'))
  const otherErrors = entries.filter(([field]) => !field.startsWith('environmentVariables'))

  await acknowledge({
    title: t('app.sceneImportValidationTitle'),
    tone: 'error',
    message: h('div', { class: 'space-y-3 text-left' }, [
        h('p', { class: 'text-sm', style: { color: 'var(--text-muted)' } },
          t('app.sceneImportValidationSummary', { count: entries.length })),
        h('p', { class: 'text-sm', style: { color: 'var(--text-muted)' } },
          t('app.sceneImportValidationNextStep')),

        // Environment variable errors section
        ...(envVarErrors.length > 0 ? [
          h('div', { class: 'mt-4' }, [
            h('div', { class: 'text-sm font-semibold mb-2', style: { color: 'var(--text)' } },
              t('app.environmentVariableErrors', { count: envVarErrors.length })),
            ...envVarErrors.map(([field, reason]) => h('div', { class: 'border-l-2 border-[color:var(--danger-border)] pl-3 mb-2' }, [
              h('div', { class: 'text-sm font-semibold', style: { color: 'var(--text)' } },
                formatSceneValidationCoordinate(field, t)),
              h('div', { class: 'mt-0.5 text-sm', style: { color: 'var(--text-muted)' } },
                reason),
              h('details', { class: 'mt-1 text-xs', style: { color: 'var(--text-muted)' } }, [
                h('summary', { class: 'cursor-pointer font-semibold' }, t('app.technicalDetails')),
                h('code', { class: 'mt-1 block break-all text-xs', style: { color: 'var(--text-muted)' } }, field)
              ])
            ]))
          ])
        ] : []),

        // Other errors section
        ...(otherErrors.length > 0 ? [
          h('div', { class: 'mt-4' }, [
            h('div', { class: 'text-sm font-semibold mb-2', style: { color: 'var(--text)' } },
              t('app.otherValidationErrors', { count: otherErrors.length })),
            ...otherErrors.map(([field, reason]) => h('div', { class: 'border-l-2 border-[color:var(--danger-border)] pl-3 mb-2' }, [
              h('div', { class: 'text-sm font-semibold', style: { color: 'var(--text)' } },
                formatSceneValidationCoordinate(field, t)),
              h('div', { class: 'mt-0.5 text-sm', style: { color: 'var(--text-muted)' } },
                reason),
              h('details', { class: 'mt-1 text-xs', style: { color: 'var(--text-muted)' } }, [
                h('summary', { class: 'cursor-pointer font-semibold' }, t('app.technicalDetails')),
                h('code', { class: 'mt-1 block break-all text-xs', style: { color: 'var(--text-muted)' } }, field)
              ])
            ]))
          ])
        ] : [])
    ]),
    confirmText: t('app.confirm')
  })
}

const refreshSceneForReconciliation = async (): Promise<boolean> => {
  // Reconciliation follows an unknown mutation outcome.  Read the semantic board through the
  // atomic snapshot endpoint so templates, nodes, rules, specs and environment cannot come from
  // different writes made by another tab while this recovery decision is being made.
  return refreshBoardSnapshot()
}

const reportBoardReplacementDrift = async (error: any): Promise<boolean> => {
  const preview = readBoardReplacementStalePreview(error)
  if (!preview) return false
  const previousSceneFingerprint = getCurrentRecommendationSceneFingerprint(boardAuthScopeEpoch)
  const refreshed = await refreshSceneForReconciliation()
  const currentSceneFingerprint = refreshed
    ? getCurrentRecommendationSceneFingerprint(boardAuthScopeEpoch)
    : null
  if (refreshed && (previousSceneFingerprint === null
    || currentSceneFingerprint === null
    || previousSceneFingerprint !== currentSceneFingerprint)) {
    markVerificationResultStale()
    invalidateRecommendationsForSceneChange({ notify: true })
  }
  notifyBlocked(t(
    refreshed ? 'app.sceneReplacementChangedBeforeApply' : 'app.sceneReplacementChangedRefreshFailed',
    {
      devices: preview.deviceCount,
      variables: preview.environmentVariableCount,
      rules: preview.ruleCount,
      specs: preview.specificationCount,
      historyEntries: preview.editHistoryEntryCount
    }
  ))
  return true
}

const currentBoardMatchesScene = (scene: BoardSceneModel): boolean => {
  try {
    return JSON.stringify(buildSceneExport()) === JSON.stringify(canonicalizeSceneFile(scene))
  } catch {
    return false
  }
}

const savedBatchMatchesScene = (
  saved: Awaited<ReturnType<typeof boardApi.importScene>>,
  scene: BoardSceneModel
): boolean => {
  try {
    if (!sceneTemplatesCoveredByCatalog(
      scene.templates,
      deviceTemplates.value,
      saved.createdTemplates
    )) {
      return false
    }
    const returnedScene: BoardSceneModel = {
      schema: SCENE_FILE_SCHEMA,
      version: SCENE_FILE_VERSION,
      templates: scene.templates,
      devices: getVisibleDeviceNodes(saved.nodes),
      environmentVariables: saved.environmentVariables,
      rules: saved.rules,
      specs: saved.specs
    }
    return JSON.stringify(canonicalizeSceneFile(returnedScene))
      === JSON.stringify(canonicalizeSceneFile(scene))
  } catch {
    return false
  }
}

const resetSceneSelectionAfterReplacement = async () => {
  dialogVisible.value = false
  focusHighlight.clear()
  await nextTick()
  if (getVisibleDeviceNodes().length > 0) {
    fitNodesToCanvas(getVisibleDeviceNodes())
  }
}

const importScene = async (
  scene: BoardSceneModel,
  admissionGuard?: () => boolean
): Promise<boolean> => {
  const isAdmitted = () => !admissionGuard || admissionGuard()
  if (!isAdmitted()) return false
  if (!ensurePlaybackClosedForMutation()) return false
  if (isSceneReplacementInProgress.value) return false
  if (hasAssistantWork.value) {
    notifyBlocked(t('app.finishAssistantBeforeSceneReplacement'))
    return false
  }
  isImportingScene.value = true
  try {
    await waitForPendingBoardMutations()
    if (!isAdmitted()) return false
    if (!ensureBoardDataReady()) return false
    let replacementPreview: BoardReplacementPreview
    try {
      replacementPreview = await boardApi.previewBoardReplacement()
    } catch (error: any) {
      if (!isAdmitted()) return false
      notifyError(localizedErrorMessage(error, t('app.sceneReplacementPreviewFailed'), locale.value))
      return false
    }
    if (!isAdmitted()) return false
    if (!await confirmDestructive({
      title: t('app.sceneImportConfirmTitle'),
      message: h('div', { class: 'scene-import-preview' }, [
        h('table', { 'data-testid': 'scene-import-comparison' }, [
          h('thead', [h('tr', [
            h('th', { scope: 'col' }, t('app.sceneContents')),
            h('th', { scope: 'col' }, t('app.currentScene')),
            h('th', { scope: 'col' }, t('app.sceneAfterImport'))
          ])]),
          h('tbody', [
            ['app.devices', replacementPreview.deviceCount, scene.devices.length],
            ['app.environmentVariables', replacementPreview.environmentVariableCount, scene.environmentVariables.length],
            ['app.rules', replacementPreview.ruleCount, scene.rules.length],
            ['app.specifications', replacementPreview.specificationCount, scene.specs.length]
          ].map(([label, current, incoming]) => h('tr', [
            h('th', { scope: 'row' }, t(String(label))),
            h('td', String(current)), h('td', String(incoming))
          ])))
        ]),
        h('p', { class: 'iot-dialog__consequence', 'data-testid': 'scene-import-consequence' },
          t('app.sceneImportConfirmMessage', { historyEntries: replacementPreview.editHistoryEntryCount })),
        h('details', { 'data-testid': 'scene-import-template-rule-details' }, [
          h('summary', t('app.sceneImportTemplateRuleHandling')),
          h('p', t('app.sceneImportTemplateNotice')),
          h('p', t('app.sceneImportRulePriorityNotice'))
        ])
      ]),
      confirmText: t('app.sceneImportConfirmAction'),
      customClass: 'scene-replacement-confirm'
    })) return false
    if (!isAdmitted()) return false

    return await enqueueBoardMutation(async () => {
      let saved: Awaited<ReturnType<typeof boardApi.importScene>>
      try {
        saved = await boardApi.importScene({
          impactToken: replacementPreview.impactToken,
          scene: serializeSceneFile(scene)
        })
        if (!savedBatchMatchesScene(saved, scene)) {
          throw new Error('Scene replacement response did not match the requested semantic scene')
        }
      } catch (error: any) {
        console.error('Failed to import scene', error)
        if (await reportBoardReplacementDrift(error)) return false
        const status = Number(error?.response?.status || 0)
        if (status >= 400 && status < 500) {
          await showSceneImportError(error)
          return false
        }

        const refreshed = await refreshSceneForReconciliation()
        if (refreshed) markVerificationResultStale()
        if (!refreshed) {
          notifyBlocked(t('app.sceneImportOutcomeUnknownRefreshFailed'))
          return false
        }
        if (currentBoardMatchesScene(scene)) {
          invalidateForFullSceneReplacement()
          await resetSceneSelectionAfterReplacement()
          notifyBlocked(t('app.sceneImportCurrentMatchesAfterUnconfirmedResponse'))
          return true
        }
        notifyBlocked(t('app.sceneImportOutcomeUnconfirmedAfterRefresh'))
        return false
      }

      invalidateForFullSceneReplacement()
      replaceNodesFromServer(saved.nodes)
      environmentVariables.value = saved.environmentVariables
      rules.value = saved.rules
      specifications.value = saved.specs
      if (saved.createdTemplates.length > 0) {
        const existingTemplateNames = new Set(deviceTemplates.value.map(template =>
          normalizeTemplateLookupName(template.name || template.manifest?.Name)))
        deviceTemplates.value = [
          ...deviceTemplates.value,
          ...saved.createdTemplates.filter(template => {
            const key = normalizeTemplateLookupName(template.name || template.manifest?.Name)
            if (!key || existingTemplateNames.has(key)) return false
            existingTemplateNames.add(key)
            return true
          })
        ]
      }
      syncRuleDerivedEdges()
      await resetSceneSelectionAfterReplacement()
      const importMessage = saved.createdTemplates.length > 0
        ? t('app.sceneImportSuccessWithTemplates', {
            devices: nodes.value.length,
            variables: environmentVariables.value.length,
            rules: rules.value.length,
            specs: specifications.value.length,
            templates: saved.createdTemplates.length
          })
        : t('app.sceneImportSuccess', {
            devices: nodes.value.length,
            variables: environmentVariables.value.length,
            rules: rules.value.length,
            specs: specifications.value.length
          })
      notifySuccess(importMessage)
      return true
    }, { admissionGuard, trackSemanticChange: false })
  } catch (error) {
    if (error instanceof BoardMutationAdmissionCancelledError) return false
    throw error
  } finally {
    isImportingScene.value = false
  }
}

const clearScene = async () => {
  if (!ensurePlaybackClosedForMutation()) return
  if (isClearingScene.value || isImportingScene.value) return
  if (hasAssistantWork.value) {
    notifyBlocked(t('app.finishAssistantBeforeSceneReplacement'))
    return
  }
  isClearingScene.value = true
  try {
    await waitForPendingBoardMutations()
    if (!ensureBoardDataReady()) return
    let replacementPreview: BoardReplacementPreview
    try {
      replacementPreview = await boardApi.previewBoardReplacement()
    } catch (error: any) {
      notifyError(localizedErrorMessage(error, t('app.sceneReplacementPreviewFailed'), locale.value))
      return
    }
    if (!await confirmDestructive({
      title: t('app.sceneClearConfirmTitle'),
      message: t('app.sceneClearConfirmMessage', {
        devices: replacementPreview.deviceCount,
        variables: replacementPreview.environmentVariableCount,
        rules: replacementPreview.ruleCount,
        specs: replacementPreview.specificationCount,
        historyEntries: replacementPreview.editHistoryEntryCount
      }),
      confirmText: t('app.sceneClearConfirmAction'),
      customClass: 'scene-replacement-confirm'
    })) return

    await enqueueBoardMutation(async () => {
      try {
        const saved = await boardApi.clearBoardScene(replacementPreview.impactToken)
        if (saved.nodes.length > 0 || saved.environmentVariables.length > 0
          || saved.rules.length > 0 || saved.specs.length > 0) {
          throw new Error('Scene clear response still contained board items')
        }
        invalidateForFullSceneReplacement()
        replaceNodesFromServer(saved.nodes)
        environmentVariables.value = saved.environmentVariables
        rules.value = saved.rules
        specifications.value = saved.specs
        syncRuleDerivedEdges()
        dialogVisible.value = false
        focusHighlight.clear()
        notifySuccess(t('app.sceneClearSuccess'))
      } catch (error: any) {
        console.error('Failed to clear scene', error)
        if (await reportBoardReplacementDrift(error)) return
        const status = Number(error?.response?.status || 0)
        if (status >= 400 && status < 500) {
          const message = localizedErrorMessage(error, t('app.sceneClearFailed'), locale.value)
          notifyError(message)
          return
        }
        const refreshed = await refreshSceneForReconciliation()
        if (refreshed) markVerificationResultStale()
        if (!refreshed) {
          notifyBlocked(t('app.sceneClearOutcomeUnknownRefreshFailed'))
          return
        }
        const isEmpty = getVisibleDeviceNodes().length === 0
          && environmentVariables.value.length === 0
          && rules.value.length === 0
          && specifications.value.length === 0
        if (isEmpty) {
          invalidateForFullSceneReplacement()
          await resetSceneSelectionAfterReplacement()
          notifyBlocked(t('app.sceneClearCurrentEmptyAfterUnconfirmedResponse'))
        } else {
          notifyBlocked(t('app.sceneClearOutcomeUnconfirmedAfterRefresh'))
        }
      }
    }, { trackSemanticChange: false })
  } finally {
    isClearingScene.value = false
  }
}

let sceneImportSelectionVersion = 0
const handleSceneImportFile = async (event: Event) => {
  const input = event.target as HTMLInputElement | null
  const file = input?.files?.[0]
  if (!file) return
  const selectionVersion = ++sceneImportSelectionVersion
  const requestToken = getToken()
  const authScopeEpoch = boardAuthScopeEpoch
  const ownsSelection = () => selectionVersion === sceneImportSelectionVersion
    && isCurrentSessionToken(requestToken) && isCurrentBoardAuthScope(authScopeEpoch)
  try {
    if (isSceneReplacementInProgress.value) {
      notifyBlocked(t('app.sceneReplacementInProgress'))
      return
    }
    if (file.size > MAX_SCENE_IMPORT_BYTES) {
      notifyError(t('app.importFileTooLarge', { size: '64 MiB' }))
      return
    }
    let text: string
    try {
      text = await file.text()
    } catch (error) {
      if (!ownsSelection()) return
      console.error('Failed to read scene file', error)
      await acknowledgeFailure({
        title: t('app.sceneFileReadFailed'),
        message: t('app.sceneFileReadFailed'),
        suggestion: t('app.sceneFileReadNextStep'),
        ...errorDetails(error)
      })
      return
    }
    if (!ownsSelection()) return
    let raw: unknown
    try {
      raw = JSON.parse(text)
    } catch (error) {
      console.error('Failed to parse scene JSON', error)
      const diagnostic = diagnoseJsonParseError(text, error)
      await acknowledgeFailure({
        title: t('app.jsonSyntaxInvalidTitle'),
        message: [t('app.jsonSyntaxInvalidMessage'),
          ...(diagnostic.line !== undefined && diagnostic.column !== undefined
            ? [t('app.jsonSyntaxLocation', { line: diagnostic.line, column: diagnostic.column })] : [])].join(' '),
        suggestion: t('app.jsonSyntaxCorrectionHint'),
        cause: diagnostic.reason,
        details: [diagnostic.reason]
      })
      return
    }
    let scene: BoardSceneModel
    try {
      scene = normalizeSceneFile(raw)
    } catch (error) {
      console.error('Scene file validation failed', error)
      await acknowledgeFailure({
        title: t('app.sceneImportValidationTitle'),
        message: localizedErrorMessage(error, t('app.sceneImportFailed'), locale.value),
        suggestion: t('app.sceneImportValidationNextStep'),
        ...errorDetails(error)
      })
      return
    }
    await importScene(scene, ownsSelection)
  } finally {
    if (input && ownsSelection()) input.value = ''
  }
}

// 2.定义刷新规则的函数（edges 由 rules 动态生成）
const refreshRules = async (expectedToken: string | null = getToken()): Promise<boolean> => {
  const authScopeEpoch = boardAuthScopeEpoch
  if (!isCurrentSessionToken(expectedToken) || !isCurrentBoardAuthScope(authScopeEpoch)) return false
  boardDataLoadState.rules = 'loading'
  try {
    // 只获取规则列表
    const rulesData = await boardApi.getRules()
    if (!isCurrentSessionToken(expectedToken) || !isCurrentBoardAuthScope(authScopeEpoch)) return false
    rules.value = rulesData
    reconcileDanglingBoardFocus({ rules: rulesData })
    // 动态生成 edges
    syncRuleDerivedEdges()
    boardDataLoadState.rules = 'ready'
    return true
  } catch (e) {
    if (!isCurrentSessionToken(expectedToken) || !isCurrentBoardAuthScope(authScopeEpoch)) return false
    console.error('Failed to load rules', e)
    boardDataLoadState.rules = 'error'
    return false
  }
}

// 3.定义刷新规约的函数
const refreshSpecifications = async (expectedToken: string | null = getToken()): Promise<boolean> => {
  const authScopeEpoch = boardAuthScopeEpoch
  if (!isCurrentSessionToken(expectedToken) || !isCurrentBoardAuthScope(authScopeEpoch)) return false
  boardDataLoadState.specs = 'loading'
  try {
    const specsData = await boardApi.getSpecs()
    if (!isCurrentSessionToken(expectedToken) || !isCurrentBoardAuthScope(authScopeEpoch)) return false
    specifications.value = specsData
    reconcileDanglingBoardFocus({ specs: specsData })
    boardDataLoadState.specs = 'ready'
    return true
  } catch(e) {
    if (!isCurrentSessionToken(expectedToken) || !isCurrentBoardAuthScope(authScopeEpoch)) return false
    console.error('Failed to load specifications', e)
    boardDataLoadState.specs = 'error'
    return false
  }
}

const retryBoardDataLoad = async () => {
  await requestBoardSnapshotRefresh({ force: true })
}

watch([nodes, rules], syncRuleDerivedEdges)

const applyLoadedBoardLayout = (layout: BoardLayoutDto | null, initialHydration: boolean) => {
  persistedWideLayout = layout ?? {
    canvasPan: { x: 0, y: 0 },
    canvasZoom: 1,
    panels: {
      control: {
        collapsed: false,
        width: DEFAULT_CONTROL_PANEL_WIDTH,
        activeSection: 'templates'
      },
      inspector: {
        collapsed: false,
        width: DEFAULT_INSPECTOR_PANEL_WIDTH,
        activeSection: 'devices'
      }
    }
  }
  const layoutChangedBeforeHydration = initialHydration
    && (panelStateTouchedBeforeLayout || canvasStateTouchedBeforeLayout)
  if (isNarrowViewport()) {
    if (!panelStateTouchedBeforeLayout) applyViewportPanelConstraints()
    const visibleNodes = getVisibleDeviceNodes()
    if (visibleNodes.length > 0) {
      void nextTick(() => fitNodesToCanvas(visibleNodes))
    }
  } else if (layout) {
    applyBoardLayout(layout)
  }
  layoutHydrated.value = true
  panelStateTouchedBeforeLayout = false
  canvasStateTouchedBeforeLayout = false
  if (layoutChangedBeforeHydration && !isNarrowViewport()) {
    persistedWideLayout = buildBoardLayoutPayload()
    void saveBoardLayout({ silent: true })
  }
}

const loadBoardLayout = async (
  initialHydration = false,
  snapshotReady: Promise<boolean> | null = boardForegroundRefreshPromise
): Promise<boolean> => {
  const authScopeEpoch = boardAuthScopeEpoch
  if (!isCurrentBoardAuthScope(authScopeEpoch) || loadingBoardLayout.value) return false
  loadingBoardLayout.value = true
  try {
    // Narrow-screen fitting needs the scene, but the task inbox does not own layout readiness.
    const [layout] = await Promise.all([boardApi.getLayout(), snapshotReady])
    if (!isCurrentBoardAuthScope(authScopeEpoch)) return false
    applyLoadedBoardLayout(layout, initialHydration)
    boardLayoutLoadError.value = null
    return true
  } catch (error) {
    if (!isCurrentBoardAuthScope(authScopeEpoch)) return false
    console.error('Failed to load canvas layout:', error)
    boardLayoutLoadError.value = errorDetails(error)
    return false
  } finally {
    if (isCurrentBoardAuthScope(authScopeEpoch)) loadingBoardLayout.value = false
  }
}

const loadBoardAuthScope = async (
  expectedAuthScopeEpoch: number,
  options: { hydrateNotifications?: boolean; initialLayout?: boolean } = {}
) => {
  if (currentAuthUserId.value === null || !isCurrentBoardAuthScope(expectedAuthScopeEpoch)) return
  if (options.hydrateNotifications) hydrateFuzzNotificationState()
  const snapshotReady = requestBoardSnapshotRefresh({ force: true })
  await Promise.all([
    snapshotReady,
    loadTaskInbox({ showLoading: false }),
    loadBoardLayout(options.initialLayout === true, snapshotReady)
  ])
}

onMounted(async () => {
  updateActionDockViewport()
  window.addEventListener('resize', updateActionDockViewport)
  window.addEventListener('keydown', onGlobalKeydown)
  window.addEventListener('focus', refreshBoardOnForeground)
  document.addEventListener('visibilitychange', refreshBoardOnForeground)
  taskInboxRefreshTimer = setInterval(() => {
    if (activeBackgroundTaskCount.value > 0
      || trackedFuzzTaskIds.value.length > 0
      || showHistoryPanel.value) {
      void refreshTaskInboxInBackground()
    }
  }, TASK_INBOX_REFRESH_INTERVAL_MS)
  const authScopeEpoch = boardAuthScopeEpoch
  await loadBoardAuthScope(authScopeEpoch, {
    hydrateNotifications: true,
    initialLayout: true
  })
})


const getCanvasMapColor = (nodeId: string): string => {
  return getNodeAccentColor(nodeId)
}

const getCanvasMapSize = (): string => {
  // All nodes use the same size for consistency
  return 'size-2'
}

const CANVAS_MAP_WIDTH = 220
const CANVAS_MAP_HEIGHT = 112
const CANVAS_MAP_INSET = 8
const isCanvasMapDragging = ref(false)
let canvasMapDragRect: DOMRect | null = null
let canvasMapDragElement: HTMLElement | null = null
let canvasMapDragPointerId: number | null = null

// Canvas map calculations
const canvasMapData = computed(() => {
  const visibleNodes = renderedCanvasNodes.value

  if (visibleNodes.length === 0) {
    return {
      dots: [],
      lines: [],
      bounds: null as null | { minX: number; minY: number; maxX: number; maxY: number; width: number; height: number }
    }
  }

  // Calculate canvas bounds
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity

  visibleNodes.forEach(node => {
    const x = node.position.x
    const y = node.position.y
    const width = node.width || DEFAULT_NODE_WIDTH
    const height = node.height || DEFAULT_NODE_HEIGHT

    minX = Math.min(minX, x)
    minY = Math.min(minY, y)
    maxX = Math.max(maxX, x + width)
    maxY = Math.max(maxY, y + height)
  })

  const frame = canvasFrameForRendering.value
  let visibleWorldWidth = 900
  let visibleWorldHeight = 600
  if (frame && renderedCanvasZoom.value > 0) {
    const visibleMinX = (frame.left - renderedCanvasPan.value.x) / renderedCanvasZoom.value
    const visibleMinY = (frame.top - renderedCanvasPan.value.y) / renderedCanvasZoom.value
    const visibleMaxX = (frame.left + frame.width - renderedCanvasPan.value.x) / renderedCanvasZoom.value
    const visibleMaxY = (frame.top + frame.height - renderedCanvasPan.value.y) / renderedCanvasZoom.value
    visibleWorldWidth = Math.max(1, visibleMaxX - visibleMinX)
    visibleWorldHeight = Math.max(1, visibleMaxY - visibleMinY)
    minX = Math.min(minX, visibleMinX)
    minY = Math.min(minY, visibleMinY)
    maxX = Math.max(maxX, visibleMaxX)
    maxY = Math.max(maxY, visibleMaxY)
  }

  const contentWidth = Math.max(1, maxX - minX)
  const contentHeight = Math.max(1, maxY - minY)
  const paddingX = Math.max(260, visibleWorldWidth * 0.75, contentWidth * 0.16)
  const paddingY = Math.max(180, visibleWorldHeight * 0.75, contentHeight * 0.16)
  minX -= paddingX
  minY -= paddingY
  maxX += paddingX
  maxY += paddingY

  const canvasWidth = maxX - minX
  const canvasHeight = maxY - minY

  // Convert node positions to mini map coordinates
  const dots = visibleNodes.map((node) => {
    const nodeX = canvasWidth > 0
      ? CANVAS_MAP_INSET + ((node.position.x - minX) / canvasWidth) * (CANVAS_MAP_WIDTH - CANVAS_MAP_INSET * 2)
      : CANVAS_MAP_WIDTH / 2
    const nodeY = canvasHeight > 0
      ? CANVAS_MAP_INSET + ((node.position.y - minY) / canvasHeight) * (CANVAS_MAP_HEIGHT - CANVAS_MAP_INSET * 2)
      : CANVAS_MAP_HEIGHT / 2

    return {
      id: node.id,
      x: Math.max(CANVAS_MAP_INSET / 2, Math.min(CANVAS_MAP_WIDTH - CANVAS_MAP_INSET - 4, nodeX)),
      y: Math.max(CANVAS_MAP_INSET / 2, Math.min(CANVAS_MAP_HEIGHT - CANVAS_MAP_INSET - 4, nodeY)),
      size: getCanvasMapSize(),
      color: getCanvasMapColor(node.id)
    }
  })

  // Create node lookup map for easy access
  const nodeMap = new Map(dots.map(dot => [dot.id, dot]))

  // 预计算双向边对，避免 O(edges²) 复杂度
  const bidirectionalPairs = new Set<string>()
  for (const edge of allEdges.value) {
    const key = [edge.from, edge.to].sort().join('→')
    bidirectionalPairs.add(key)
  }

  // Generate lines for rule-derived visible edges.
  const lines = allEdges.value.flatMap((edge) => {
    const fromDot = nodeMap.get(edge.from)
    const toDot = nodeMap.get(edge.to)

    if (!fromDot || !toDot) return []

    // Check if bidirectional using pre-computed Set
    const pairKey = [edge.from, edge.to].sort().join('→')
    const reversePairKey = [edge.to, edge.from].sort().join('→')
    const isBidirectional = pairKey === reversePairKey ? false : bidirectionalPairs.has(pairKey)

    let offsetY = 0

    if (isBidirectional) {
      // For bidirectional edges, offset vertically
      const [node1, node2] = [edge.from, edge.to].sort()
      const isFirstDirection = (edge.from === node1 && edge.to === node2)
      offsetY = isFirstDirection ? -8 : 8 // Offset above/below
    }

    return [{
      id: edge.id,
      fromId: edge.from,
      x1: fromDot.x + 4, // Center of dot (assuming 8px diameter)
      y1: fromDot.y + 4 + offsetY,
      x2: toDot.x + 4,
      y2: toDot.y + 4 + offsetY,
      color: getCanvasMapColor(edge.from),
      isBidirectional
    }]
  })

  return {
    dots,
    lines,
    bounds: { minX, minY, maxX, maxY, width: canvasWidth, height: canvasHeight }
  }
})

const canvasMapDots = computed(() => canvasMapData.value.dots)
const canvasMapLines = computed(() => canvasMapData.value.lines)
const canvasMapViewBox = computed(() => `0 0 ${CANVAS_MAP_WIDTH} ${CANVAS_MAP_HEIGHT}`)

const getNodeBounds = (targetNodes: DeviceNode[] = nodes.value) => {
  if (targetNodes.length === 0) return null
  return targetNodes.reduce((bounds, node) => {
    const x = node.position.x
    const y = node.position.y
    const width = node.width || DEFAULT_NODE_WIDTH
    const height = node.height || DEFAULT_NODE_HEIGHT
    return {
      minX: Math.min(bounds.minX, x),
      minY: Math.min(bounds.minY, y),
      maxX: Math.max(bounds.maxX, x + width),
      maxY: Math.max(bounds.maxY, y + height)
    }
  }, {
    minX: Infinity,
    minY: Infinity,
    maxX: -Infinity,
    maxY: -Infinity
  })
}

const getCanvasTopInset = () => {
  const canvas = document.querySelector('.canvas') as HTMLElement | null
  if (!canvas) return 64
  const style = getComputedStyle(canvas)
  return Number.parseFloat(style.paddingTop || '0') || 0
}

const getVisibleCanvasFrame = () => {
  const canvasEl = document.querySelector('.canvas-container')
  if (!canvasEl) return null
  const rect = canvasEl.getBoundingClientRect()
  // Narrow panels overlay the stage; navigation keeps its underlying rail frame.
  const leftInset = isNarrowBoardLayout.value || boardPanels.control.collapsed
    ? COLLAPSED_PANEL_RAIL_PX : effectiveControlPanelWidth.value
  // The dock reservation includes its gap.
  const rightInset = (isNarrowBoardLayout.value || boardPanels.inspector.collapsed
    ? COLLAPSED_PANEL_RAIL_PX : effectiveInspectorPanelWidth.value)
    + actionDockReservedWidth.value
  const topInset = getCanvasTopInset()
  // The dock has its own layout space; the canvas rectangle already excludes it.
  const bottomInset = 24
  const availableWidth = rect.width - leftInset - rightInset
  const availableHeight = rect.height - topInset - bottomInset
  if (availableWidth <= 0 || availableHeight <= 0) return null
  return {
    left: leftInset,
    top: topInset,
    width: availableWidth,
    height: availableHeight
  }
}

const getVisibleCanvasCenterWorld = (frame = getVisibleCanvasFrame()) => {
  if (!frame || renderedCanvasZoom.value <= 0) return null
  return {
    x: (frame.left + frame.width / 2 - renderedCanvasPan.value.x) / renderedCanvasZoom.value,
    y: (frame.top + frame.height / 2 - renderedCanvasPan.value.y) / renderedCanvasZoom.value
  }
}

const clampCanvasMapValue = (value: number, min: number, max: number) =>
  Math.min(max, Math.max(min, value))

const worldToCanvasMapPoint = (worldX: number, worldY: number) => {
  const bounds = canvasMapData.value.bounds
  if (!bounds) return null
  const innerWidth = CANVAS_MAP_WIDTH - CANVAS_MAP_INSET * 2
  const innerHeight = CANVAS_MAP_HEIGHT - CANVAS_MAP_INSET * 2
  return {
    x: CANVAS_MAP_INSET + ((worldX - bounds.minX) / Math.max(1, bounds.width)) * innerWidth,
    y: CANVAS_MAP_INSET + ((worldY - bounds.minY) / Math.max(1, bounds.height)) * innerHeight
  }
}

const canvasMapPointToWorld = (mapX: number, mapY: number) => {
  const bounds = canvasMapData.value.bounds
  if (!bounds) return null
  const innerWidth = CANVAS_MAP_WIDTH - CANVAS_MAP_INSET * 2
  const innerHeight = CANVAS_MAP_HEIGHT - CANVAS_MAP_INSET * 2
  const normalizedX = (clampCanvasMapValue(mapX, CANVAS_MAP_INSET, CANVAS_MAP_WIDTH - CANVAS_MAP_INSET) - CANVAS_MAP_INSET) / innerWidth
  const normalizedY = (clampCanvasMapValue(mapY, CANVAS_MAP_INSET, CANVAS_MAP_HEIGHT - CANVAS_MAP_INSET) - CANVAS_MAP_INSET) / innerHeight
  return {
    x: bounds.minX + normalizedX * bounds.width,
    y: bounds.minY + normalizedY * bounds.height
  }
}

const canvasMapPointFromEvent = (event: PointerEvent, rect?: DOMRect | null) => {
  const currentTarget = event.currentTarget
  const rectToUse = rect ?? (currentTarget instanceof HTMLElement ? currentTarget.getBoundingClientRect() : null)
  if (!rectToUse || rectToUse.width <= 0 || rectToUse.height <= 0) return null
  return {
    x: ((event.clientX - rectToUse.left) / rectToUse.width) * CANVAS_MAP_WIDTH,
    y: ((event.clientY - rectToUse.top) / rectToUse.height) * CANVAS_MAP_HEIGHT
  }
}

const panCanvasToWorldCenter = (worldX: number, worldY: number, frame = getVisibleCanvasFrame()) => {
  if (!frame) return
  if (!activePlaybackScene.value && !layoutHydrated.value) canvasStateTouchedBeforeLayout = true
  const nextPan = {
    x: frame.left + frame.width / 2 - worldX * renderedCanvasZoom.value,
    y: frame.top + frame.height / 2 - worldY * renderedCanvasZoom.value
  }
  if (activePlaybackScene.value) {
    playbackViewportFollowsFit = false
    playbackCanvasPan.value = nextPan
  }
  else canvasPan.value = nextPan
}

const focusDeviceNodeOnCanvas = (
  node: DeviceNode,
  options: { ensureReadable?: boolean } = {}
) => {
  const width = node.width || DEFAULT_NODE_WIDTH
  const height = node.height || DEFAULT_NODE_HEIGHT

  if (options.ensureReadable) {
    const isCompactOnScreen = width * canvasZoom.value < 92 || height * canvasZoom.value < 74
    if (isCompactOnScreen) {
      canvasZoom.value = Math.min(MAX_ZOOM, Math.max(canvasZoom.value, 1))
    }
  }

  panCanvasToWorldCenter(
    node.position.x + width / 2,
    node.position.y + height / 2
  )
  focusHighlight.show('node', node.id)
  void nextTick(() => {
    const escaped = typeof CSS !== 'undefined' && typeof CSS.escape === 'function'
      ? CSS.escape(node.id)
      : node.id.replace(/["\\]/g, '\\$&')
    document.querySelector<HTMLElement>(`[data-node-id="${escaped}"]`)?.focus({ preventScroll: true })
  })
}

const focusCreatedDeviceNode = async (node?: DeviceNode | null) => {
  if (!node) return
  boardPanels.inspector.activeSection = 'devices'
  await nextTick()
  focusDeviceNodeOnCanvas(node, { ensureReadable: true })
}

const focusRuleOnCanvas = async (ruleId?: string | null) => {
  if (!ruleId) return
  if (!layoutHydrated.value) canvasStateTouchedBeforeLayout = true
  focusHighlight.show('rule', ruleId)
  if (!layoutHydrated.value) panelStateTouchedBeforeLayout = true
  boardPanels.inspector.collapsed = false
  if (isNarrowViewport()) boardPanels.control.collapsed = true
  boardPanels.inspector.activeSection = 'rules'

  const relatedEdges = edges.value.filter(edge => edge.ruleId === ruleId)
  const relatedNodeIds = new Set<string>()
  for (const edge of relatedEdges) {
    relatedNodeIds.add(edge.from)
    relatedNodeIds.add(edge.to)
  }
  const relatedNodes = getVisibleDeviceNodes().filter(node => relatedNodeIds.has(node.id))
  if (relatedNodes.length > 0) {
    fitNodesToCanvas(relatedNodes)
  }

  await nextTick()
  const escaped = typeof CSS !== 'undefined' && typeof CSS.escape === 'function'
    ? CSS.escape(ruleId)
    : ruleId.replace(/["\\]/g, '\\$&')
  const inspectorCard = document.querySelector<HTMLElement>(
    `[data-testid="system-inspector"].is-expanded [data-rule-id="${escaped}"]`
  )
  const canvasEdge = document.querySelector<HTMLElement>(
    `.edge-hitarea[data-rule-id="${escaped}"]`
  )
  ;(inspectorCard || canvasEdge)?.focus({ preventScroll: true })
}

const focusSpecInInspector = async (specId?: string | null) => {
  if (!specId) return
  focusHighlight.show('spec', specId)
  if (!layoutHydrated.value) panelStateTouchedBeforeLayout = true
  boardPanels.inspector.collapsed = false
  if (isNarrowViewport()) boardPanels.control.collapsed = true
  boardPanels.inspector.activeSection = 'specs'
  await nextTick()
  const escaped = typeof CSS !== 'undefined' && typeof CSS.escape === 'function'
    ? CSS.escape(specId)
    : specId.replace(/["\\]/g, '\\$&')
  document.querySelector<HTMLElement>(
    `[data-testid="system-inspector"].is-expanded [data-spec-id="${escaped}"]`
  )?.focus({ preventScroll: true })
}

const canvasMapViewportRect = computed(() => {
  const bounds = canvasMapData.value.bounds
  const frame = canvasFrameForRendering.value
  if (!bounds || !frame || renderedCanvasZoom.value <= 0) return null

  const visibleMinX = (frame.left - renderedCanvasPan.value.x) / renderedCanvasZoom.value
  const visibleMinY = (frame.top - renderedCanvasPan.value.y) / renderedCanvasZoom.value
  const visibleMaxX = (frame.left + frame.width - renderedCanvasPan.value.x) / renderedCanvasZoom.value
  const visibleMaxY = (frame.top + frame.height - renderedCanvasPan.value.y) / renderedCanvasZoom.value
  const topLeft = worldToCanvasMapPoint(visibleMinX, visibleMinY)
  const bottomRight = worldToCanvasMapPoint(visibleMaxX, visibleMaxY)
  if (!topLeft || !bottomRight) return null

  const minX = clampCanvasMapValue(Math.min(topLeft.x, bottomRight.x), CANVAS_MAP_INSET, CANVAS_MAP_WIDTH - CANVAS_MAP_INSET)
  const minY = clampCanvasMapValue(Math.min(topLeft.y, bottomRight.y), CANVAS_MAP_INSET, CANVAS_MAP_HEIGHT - CANVAS_MAP_INSET)
  const maxX = clampCanvasMapValue(Math.max(topLeft.x, bottomRight.x), CANVAS_MAP_INSET, CANVAS_MAP_WIDTH - CANVAS_MAP_INSET)
  const maxY = clampCanvasMapValue(Math.max(topLeft.y, bottomRight.y), CANVAS_MAP_INSET, CANVAS_MAP_HEIGHT - CANVAS_MAP_INSET)

  return {
    x: minX,
    y: minY,
    width: Math.max(4, maxX - minX),
    height: Math.max(4, maxY - minY)
  }
})

const navigateCanvasMap = (event: PointerEvent, rect?: DOMRect | null) => {
  if (isCanvasNavigationLocked.value) return
  const point = canvasMapPointFromEvent(event, rect)
  if (!point) return
  const world = canvasMapPointToWorld(point.x, point.y)
  if (!world) return
  panCanvasToWorldCenter(world.x, world.y)
}

const onCanvasMapPointerDown = (event: PointerEvent) => {
  if (isCanvasNavigationLocked.value) return
  if (!canvasMapData.value.bounds
    || event.button !== 0
    || event.isPrimary === false
    || canvasMapDragPointerId !== null) return
  event.preventDefault()
  isCanvasMapDragging.value = true

  const target = event.currentTarget as HTMLElement
  canvasMapDragElement = target
  canvasMapDragRect = target.getBoundingClientRect()
  canvasMapDragPointerId = event.pointerId
  navigateCanvasMap(event, canvasMapDragRect)
  try { target.setPointerCapture(event.pointerId) } catch {}
  target.addEventListener('lostpointercapture', onCanvasMapPointerLost)
  window.addEventListener('pointermove', onCanvasMapPointerMove)
  window.addEventListener('pointerup', onCanvasMapPointerUp)
  window.addEventListener('pointercancel', onCanvasMapPointerCancel)
}

const onCanvasMapPointerMove = (event: PointerEvent) => {
  if (!isCanvasMapDragging.value || event.pointerId !== canvasMapDragPointerId) return
  if (isCanvasNavigationLocked.value) {
    finishCanvasMapDrag(event.pointerId)
    return
  }
  navigateCanvasMap(event, canvasMapDragRect)
}

const finishCanvasMapDrag = (pointerId: number | null = canvasMapDragPointerId) => {
  if (pointerId !== null && pointerId !== canvasMapDragPointerId) return
  const target = canvasMapDragElement
  const activePointerId = canvasMapDragPointerId
  isCanvasMapDragging.value = false
  canvasMapDragElement = null
  canvasMapDragRect = null
  canvasMapDragPointerId = null
  target?.removeEventListener('lostpointercapture', onCanvasMapPointerLost)
  if (target && activePointerId !== null) {
    try { target.releasePointerCapture(activePointerId) } catch {}
  }
  window.removeEventListener('pointermove', onCanvasMapPointerMove)
  window.removeEventListener('pointerup', onCanvasMapPointerUp)
  window.removeEventListener('pointercancel', onCanvasMapPointerCancel)
}

const onCanvasMapPointerUp = (event: PointerEvent) => {
  finishCanvasMapDrag(event.pointerId)
}

const onCanvasMapPointerCancel = (event: PointerEvent) => {
  finishCanvasMapDrag(event.pointerId)
}

const onCanvasMapPointerLost = (event: PointerEvent) => {
  finishCanvasMapDrag(event.pointerId)
}

const fittedViewportForNodes = (targetNodes: DeviceNode[], frame = getVisibleCanvasFrame()) => {
  const bounds = getNodeBounds(targetNodes)
  if (!bounds || !frame) return null

  const paddingX = Math.min(72, frame.width / 8)
  const paddingY = Math.min(72, frame.height / 8)
  const contentWidth = Math.max(1, bounds.maxX - bounds.minX)
  const contentHeight = Math.max(1, bounds.maxY - bounds.minY)
  const zoom = Math.min(
    MAX_ZOOM,
    (frame.width - paddingX * 2) / contentWidth,
    (frame.height - paddingY * 2) / contentHeight
  )
  if (!Number.isFinite(zoom) || zoom <= 0) return null
  const centerX = (bounds.minX + bounds.maxX) / 2
  const centerY = (bounds.minY + bounds.maxY) / 2
  return {
    zoom,
    pan: {
      x: frame.left + frame.width / 2 - centerX * zoom,
      y: frame.top + frame.height / 2 - centerY * zoom
    }
  }
}

const fitNodesToCanvas = (targetNodes: DeviceNode[] = nodes.value) => {
  const viewport = fittedViewportForNodes(targetNodes)
  if (!viewport) {
    notifyInfo(t('app.noDevicesOnCanvas'))
    return
  }
  canvasZoom.value = viewport.zoom
  canvasPan.value = viewport.pan
}

const activatePlaybackScene = (scene: ModelPlaybackScene) => {
  playbackViewportFollowsFit = true
  playbackCanvasFrame = null
  activePlaybackScene.value = deepClone(scene)
}

// Overview follows the free frame; manual navigation retains its scale and world center.
watch([
  activePlaybackScene, actionDockViewportWidth, boardViewportHeight, playbackPanelOpen,
  () => boardPanels.control.collapsed, () => boardPanels.inspector.collapsed,
  effectiveControlPanelWidth, effectiveInspectorPanelWidth, actionDockReservedWidth
], () => {
  if (!activePlaybackScene.value) return
  void nextTick(() => {
    const scene = activePlaybackScene.value
    if (!scene) return
    const frame = getVisibleCanvasFrame()
    if (!frame) return
    if (!playbackViewportFollowsFit && playbackCanvasFrame) {
      playbackCanvasPan.value = {
        x: playbackCanvasPan.value.x + frame.left + frame.width / 2
          - playbackCanvasFrame.left - playbackCanvasFrame.width / 2,
        y: playbackCanvasPan.value.y + frame.top + frame.height / 2
          - playbackCanvasFrame.top - playbackCanvasFrame.height / 2
      }
    } else {
      const viewport = fittedViewportForNodes(scene.nodes)
      if (!viewport) return
      playbackCanvasZoom.value = viewport.zoom
      playbackCanvasPan.value = viewport.pan
    }
    playbackCanvasFrame = frame
  })
})

const deactivatePlaybackScene = () => {
  activePlaybackScene.value = null
  playbackViewportFollowsFit = true
  playbackCanvasFrame = null
  playbackCanvasZoom.value = 1
  playbackCanvasPan.value = { x: 0, y: 0 }
}

const fitToContent = () => {
  if (isCanvasNavigationLocked.value) return
  const viewport = fittedViewportForNodes(renderedCanvasNodes.value)
  if (!viewport) {
    notifyInfo(t('app.noDevicesOnCanvas'))
    return
  }
  if (activePlaybackScene.value) {
    playbackViewportFollowsFit = true
    playbackCanvasFrame = getVisibleCanvasFrame()
    playbackCanvasZoom.value = viewport.zoom
    playbackCanvasPan.value = viewport.pan
    return
  }
  if (!layoutHydrated.value) canvasStateTouchedBeforeLayout = true
  canvasZoom.value = viewport.zoom
  canvasPan.value = viewport.pan
}

const handleCreateDevice = async (data: {
  template: DeviceTemplate
  customName: string
  runtime?: DeviceRuntimeConfig
  complete: (saved: boolean, failure?: ApiErrorDetails) => void
}) => {
  if (!ensurePlaybackClosedForMutation()) {
    data.complete(false)
    return
  }
  if (!ensureBoardDataReady(['nodes', 'templates'])) {
    data.complete(false)
    return
  }
  if (!ensureBoardItemCapacity('devices', getVisibleDeviceNodes().length, 1, REQUEST_LIMITS.devices)) {
    data.complete(false)
    return
  }
  const requestToken = getToken()
  const requestAuthScopeEpoch = boardAuthScopeEpoch
  const isRequestCurrent = () => isCurrentSessionToken(requestToken) && isCurrentBoardAuthScope(requestAuthScopeEpoch)
  return enqueueBoardMutation(async () => {
    let saved = false
    let saveFailure: ApiErrorDetails | undefined
    let requestedNode: DeviceNode | null = null
    let environmentChanges: EnvironmentVariableChange[] = []
    let refreshedDeviceLabel: string | null = null
    try {
      const { template, customName, runtime } = data
      if (!ensureDeviceRuntimeCapacity(runtime)) return
      const requestedLabel = customName.trim()
      const uniqueLabel = getUniqueLabel(requestedLabel, getVisibleDeviceNodes())
      if (uniqueLabel !== requestedLabel) {
        notifyBlocked(t('app.deviceNameAlreadyExists'))
        return
      }
      const node: DeviceNode = {
        id: createDeviceInstanceId(getVisibleDeviceNodes()),
        templateName: template.manifest.Name,
        label: uniqueLabel,
        position: getNextNodePosition(),
        state: template.manifest.InitState || 'Working',
        width: DEFAULT_NODE_WIDTH,
        height: DEFAULT_NODE_HEIGHT,
        ...(runtime || {})
      }
      requestedNode = node
      const mutation = await boardApi.addNodes([node])
      if (!isRequestCurrent()) return
      commitSemanticScene({
        nodes: mutation.currentNodes,
        environmentVariables: mutation.environmentVariables,
        specs: mutation.currentSpecifications,
        availability: mutation
      })
      environmentChanges = mutation.environmentChanges
      const created = mutation.affectedDevices[0]
      await focusCreatedDeviceNode(created)
      if (!isRequestCurrent()) return
      saved = true
    } catch (error: any) {
      if (!isRequestCurrent()) return
      if (!isDefinitiveMutationRejection(error) && requestedNode) {
        const [nodesRefreshed, environmentRefreshed] = await Promise.all([
          refreshDevices(),
          refreshEnvironmentVariables()
        ])
        if (!isRequestCurrent()) return
        await reloadUndoAvailability()
        if (!isRequestCurrent()) return
        const created = nodes.value.find(candidate => candidate.id === requestedNode?.id)
        if (nodesRefreshed && environmentRefreshed && created) {
          await focusCreatedDeviceNode(created)
          if (!isRequestCurrent()) return
          refreshedDeviceLabel = created.label
          saved = true
          return
        }
        notifyBlocked(t(nodesRefreshed && environmentRefreshed
          ? 'app.deviceCreateOutcomeNotMatched' : 'app.deviceCreateOutcomeUnknownRefreshFailed'))
        return
      }
      saveFailure = errorDetails(error)
    } finally {
      data.complete(saved, saveFailure)
      if (saved && isRequestCurrent()) {
        await nextTick()
        if (!isRequestCurrent()) return
        reportEnvironmentChanges(environmentChanges)
        if (refreshedDeviceLabel !== null) {
          notifyBlocked(t('app.deviceCreateOutcomeRefreshed', { name: refreshedDeviceLabel }))
        }
      }
    }
  }).catch(error => {
    if (!isPollingAbortedError(error)) throw error
    data.complete(false)
  })
}

const handleCreateDevices = async (data: {
  items: Array<{ template: DeviceTemplate, customName: string, runtime?: DeviceRuntimeConfig }>
  environmentVariables?: ModelEnvironmentVariable[]
  complete: (saved: boolean) => void
}) => {
  if (!ensurePlaybackClosedForMutation()) {
    data.complete(false)
    return
  }
  if (!ensureBoardDataReady(['nodes', 'templates', 'environment'])) {
    data.complete(false)
    return
  }
  return enqueueBoardMutation(async () => {
    let savedSuccessfully = false
    let outcomeRefreshed = false
    let environmentChanges: EnvironmentVariableChange[] = []
    const items = Array.isArray(data.items) ? data.items : []

    if (items.length === 0) {
      notifyBlocked(t('app.noDevicesToCreate'))
      data.complete(false)
      return
    }
    if (!ensureBoardItemCapacity(
      'devices', getVisibleDeviceNodes().length, items.length, REQUEST_LIMITS.devices
    )) {
      data.complete(false)
      return
    }
    if (items.some(item => !item?.template?.manifest?.Name || !item.customName?.trim())) {
      notifyBlocked(t('app.deviceBatchContainsInvalidItems'))
      data.complete(false)
      return
    }
    if (items.some(item => !ensureDeviceRuntimeCapacity(item.runtime))) {
      data.complete(false)
      return
    }

    const createdNodes: DeviceNode[] = []
    const occupiedNodes = [...getVisibleDeviceNodes()]
    const nameConflicts: string[] = []

    for (const item of items) {
      const requestedLabel = item.customName.trim()
      const uniqueLabel = getUniqueLabel(requestedLabel, occupiedNodes)
      if (uniqueLabel !== requestedLabel) {
        nameConflicts.push(`${requestedLabel} -> ${uniqueLabel}`)
      }
      const node: DeviceNode = {
        id: createDeviceInstanceId(occupiedNodes),
        templateName: item.template.manifest.Name,
        label: uniqueLabel,
        position: getNextNodePosition(occupiedNodes),
        state: item.template.manifest.InitState || 'Working',
        width: DEFAULT_NODE_WIDTH,
        height: DEFAULT_NODE_HEIGHT,
        ...(item.runtime || {})
      }
      occupiedNodes.push(node)
      createdNodes.push(node)
    }

    if (nameConflicts.length > 0) {
      notifyBlocked(t('app.deviceBatchNameConflictBlocked', {
        changes: nameConflicts.join(', ')
      }))
      data.complete(false)
      return
    }

    try {
      const mutation = await boardApi.addNodes(createdNodes, data.environmentVariables || [])
      commitSemanticScene({
        nodes: mutation.currentNodes,
        environmentVariables: mutation.environmentVariables,
        specs: mutation.currentSpecifications,
        availability: mutation
      })
      environmentChanges = mutation.environmentChanges
      const lastCreated = mutation.affectedDevices[mutation.affectedDevices.length - 1]
      await focusCreatedDeviceNode(lastCreated)
      savedSuccessfully = true
    } catch (error: any) {
      console.error('Failed to batch-create devices or save environment variables', error)
      if (!isDefinitiveMutationRejection(error)) {
        const [nodesRefreshed, environmentRefreshed] = await Promise.all([
          refreshDevices(),
          refreshEnvironmentVariables()
        ])
        await reloadUndoAvailability()
        const allPresent = createdNodes.length > 0
          && createdNodes.every(created => nodes.value.some(candidate => candidate.id === created.id))
        if (nodesRefreshed && environmentRefreshed && allPresent) {
          const lastCreated = nodes.value.find(candidate => candidate.id === createdNodes[createdNodes.length - 1].id)
          if (lastCreated) await focusCreatedDeviceNode(lastCreated)
          outcomeRefreshed = true
          savedSuccessfully = true
          return
        }
      }
      const fallbackMessage = data.environmentVariables?.length
        ? t('app.saveEnvironmentFailed')
        : t('app.saveNodesFailed')
      notifyError(localizedErrorMessage(error, fallbackMessage, locale.value))
    } finally {
      data.complete(savedSuccessfully)
      if (savedSuccessfully) {
        await nextTick()
        reportEnvironmentChanges(environmentChanges)
        if (outcomeRefreshed) {
          notifyBlocked(t('app.devicesCreateOutcomeRefreshed', { count: createdNodes.length }))
        } else {
          notifySuccess(t('app.devicesAddedWithCount', { count: createdNodes.length }))
        }
      }
    }
  })
}

const openRuleBuilder = () => {
  if (!ensurePlaybackClosedForMutation()) return
  openControlSection('rules')
}

const handleAddSpec = async (data: { 
  templateId: string, 
  devices: Array<{deviceId: string, deviceLabel: string, selectedApis: string[]}>, 
  formula: string,
  aConditions: SpecCondition[],
  ifConditions: SpecCondition[],
  thenConditions: SpecCondition[],
  complete: (saved: boolean, failure?: ApiErrorDetails) => void
}) => {
  if (!ensurePlaybackClosedForMutation()) {
    data.complete(false)
    return
  }
  const requestToken = getToken()
  const requestAuthScopeEpoch = boardAuthScopeEpoch
  let saved = false
  let outcomeRefreshed = false
  let attemptedSpec: Specification | null = null
  let saveFailure: ApiErrorDetails | undefined
  try {
    await enqueueBoardMutation(async () => {
      try {
        if (!ensureBoardDataReady(['nodes', 'templates', 'specs'])) return
        if (!ensureBoardItemCapacity(
          'specifications', specifications.value.length, 1, REQUEST_LIMITS.specifications
        )) return
        const { templateId, aConditions, ifConditions, thenConditions } = data
        if (![aConditions, ifConditions, thenConditions].every(conditions =>
          ensureNestedItemCapacity(
            t('app.specificationConditions'), conditions?.length || 0,
            REQUEST_LIMITS.specificationConditions
          ))) return
        const specTemplate = defaultSpecTemplates.find(t => t.id === templateId)
        const templateLabel = specTemplate?.label || templateId

        const newSpec: Specification = {
          id: 'spec_' + Date.now(),
          templateId: templateId as any,
          templateLabel,
          aConditions: aConditions || [],
          ifConditions: ifConditions || [],
          thenConditions: thenConditions || []
        }
        attemptedSpec = newSpec
        newSpec.formula = buildSpecFormula(newSpec, {
          nodes: nodes.value
        })
        newSpec.devices = buildSpecDeviceRefsFromConditions([
          ...(aConditions || []),
          ...(ifConditions || []),
          ...(thenConditions || [])
        ], nodes.value)

        if (specifications.value.some(spec => isSameSpecification(spec, newSpec))) {
          notifyBlocked(t('app.specDuplicate'))
          return
        }

        const mutation = await boardApi.addSpec(newSpec)
        commitSemanticScene({ specs: mutation.currentItems, availability: mutation })
        const createdSpec = mutation.affectedItem
        await focusSpecInInspector(createdSpec?.id)
        saved = true
      } catch (error: any) {
        console.error('[Board] Failed to add specification:', error)
        if (Number(error?.response?.status) === 409
          && error?.response?.data?.data?.reasonCode === 'DUPLICATE_SPECIFICATION') {
          if (!isCurrentSessionToken(requestToken) || !isCurrentBoardAuthScope(requestAuthScopeEpoch)) return
          await refreshSpecifications(requestToken)
          if (!isCurrentSessionToken(requestToken) || !isCurrentBoardAuthScope(requestAuthScopeEpoch)) return
          await reloadUndoAvailability()
          if (!isCurrentSessionToken(requestToken) || !isCurrentBoardAuthScope(requestAuthScopeEpoch)) return
          saveFailure = errorDetails(error)
          return
        }
        if (isDefinitiveMutationRejection(error) && [400, 422].includes(Number(error?.response?.status))) {
          if (!isCurrentSessionToken(requestToken) || !isCurrentBoardAuthScope(requestAuthScopeEpoch)) return
          saveFailure = errorDetails(error)
          return
        }
        if (!isDefinitiveMutationRejection(error) && attemptedSpec) {
          const refreshed = await refreshSpecifications()
          await reloadUndoAvailability()
          if (refreshed && specifications.value.some(spec => isSameSpecification(spec, attemptedSpec!))) {
            outcomeRefreshed = true
            saved = true
            return
          }
        }
        notifyError(extractApiErrorMessage(error, t('app.saveSpecsFailed')))
      }
    })
  } finally {
    data.complete(saved, saveFailure)
    if (outcomeRefreshed) {
      await nextTick()
      notifyBlocked(t('app.specCreateOutcomeRefreshed'))
    }
  }
}

const getNextNodePosition = (occupiedNodes: DeviceNode[] = nodes.value): { x: number; y: number } => {
  // 将节点放置在画布网格中央附近，确保无重叠
  const count = occupiedNodes.length

  // 基础节点尺寸（用于碰撞检测）
  const nodeWidth = DEFAULT_NODE_WIDTH
  const nodeHeight = DEFAULT_NODE_HEIGHT
  const minSpacing = 20 // 最小间距

  // 计算网格位置（以中心为原点）
  const cols = NODE_GRID_COLS
  const col = count % cols
  const row = Math.floor(count / cols)

  // 中心偏移：让第一个节点在中心，后面围绕中心排列
  const offsetCol = col - Math.floor(cols / 2)
  const offsetRow = row

  // 计算屏幕坐标
  const screenCenterX = window.innerWidth / 2
  const screenCenterY = window.innerHeight / 2

  // 应用偏移
  let screenX = screenCenterX + offsetCol * NODE_SPACING_X
  let screenY = screenCenterY + offsetRow * NODE_SPACING_Y

  // 碰撞检测和位置调整
  let attempts = 0
  const maxAttempts = 50

  while (attempts < maxAttempts) {
    // 转换到世界坐标
    const { x: worldX, y: worldY } = screenToWorld(screenX, screenY, canvasPan.value, canvasZoom.value)

    // 检查与其他节点的重叠
    const hasOverlap = occupiedNodes.some(node => {
      const dx = Math.abs(node.position.x - worldX)
      const dy = Math.abs(node.position.y - worldY)
      const minDistanceX = (node.width + nodeWidth) / 2 + minSpacing
      const minDistanceY = (node.height + nodeHeight) / 2 + minSpacing

      return dx < minDistanceX && dy < minDistanceY
    })

    if (!hasOverlap) {
      // 找到合适位置
      return { x: worldX, y: worldY }
    }

    // 位置被占用，向外扩展查找
    attempts++
    const angle = (attempts * 137.5) * (Math.PI / 180) // 黄金角
    const radius = Math.sqrt(attempts) * Math.max(NODE_SPACING_X, NODE_SPACING_Y) / 2

    screenX = screenCenterX + Math.cos(angle) * radius
    screenY = screenCenterY + Math.sin(angle) * radius
  }

  // 如果找不到合适位置，使用随机偏移
  const randomAngle = Math.random() * 2 * Math.PI
  const randomRadius = 100 + Math.random() * 200
  screenX = screenCenterX + Math.cos(randomAngle) * randomRadius
  screenY = screenCenterY + Math.sin(randomAngle) * randomRadius

  return screenToWorld(screenX, screenY, canvasPan.value, canvasZoom.value)
}

const cancelRecommendationDuringTeardown = (
  requestId: string | null,
  controller: AbortController | null
) => {
  if (!requestId) {
    controller?.abort()
    return
  }
  const ownerAuthToken = getRecommendationRequestOwner(requestId)?.authToken
  if (!ownerAuthToken) {
    console.warn(`Recommendation owner credential is unavailable during teardown (${requestId})`)
    controller?.abort()
    return
  }
  void requestInteractiveCancellation({
    cancel: () => cancelRecommendationAsOwner(requestId, ownerAuthToken),
    waitBeforeRetry: () => new Promise<void>(resolve => setTimeout(resolve, 100)),
    maxAttempts: 20
  }).catch(error => {
    console.warn(`Failed to confirm recommendation cancellation during teardown (${requestId}):`, error)
  }).finally(() => {
    controller?.abort()
  })
}

onBeforeUnmount(() => {
  boardLifecycleDisposed = true
  layoutSaveFeedbackSuppressed = true
  pollingEpoch += 1
  stopTraceAnimation()
  ruleRecommendationRequestEpoch += 1
  cancelRecommendationDuringTeardown(
    ruleRecommendationRequestId.value,
    ruleRecommendationAbortController.value
  )
  ruleRecommendationAbortController.value = null
  deviceRecommendationRequestEpoch += 1
  cancelRecommendationDuringTeardown(
    deviceRecommendationRequestId.value,
    deviceRecommendationAbortController.value
  )
  deviceRecommendationAbortController.value = null
  specRecommendationRequestEpoch += 1
  cancelRecommendationDuringTeardown(
    specRecommendationRequestId.value,
    specRecommendationAbortController.value
  )
  specRecommendationAbortController.value = null
  scenarioRecommendationRequestEpoch += 1
  cancelRecommendationDuringTeardown(
    scenarioRecommendationRequestId.value,
    scenarioRecommendationAbortController.value
  )
  scenarioRecommendationAbortController.value = null
  if (recommendationProgressTimer) {
    clearInterval(recommendationProgressTimer)
    recommendationProgressTimer = null
  }
  if (taskInboxRefreshTimer) {
    clearInterval(taskInboxRefreshTimer)
    taskInboxRefreshTimer = null
  }
  void flushPendingBoardLayout({ silent: true })
  window.removeEventListener('keydown', onGlobalKeydown)
  window.removeEventListener('resize', updateActionDockViewport)
  window.removeEventListener('focus', refreshBoardOnForeground)
  document.removeEventListener('visibilitychange', refreshBoardOnForeground)
  boardInvalidationBinding.dispose()
  finishCanvasPan()
  finishCanvasMapDrag()
  activeNodeLayoutInteractions.clear()
})

// Assistant device/environment/rule/spec edits go through journal-recording write paths, so they change undo
// availability. Partial collection refreshes must re-read it just as the full snapshot reader does.
//
// Called through a late-bound reference because `useBoardUndo` is set up further down this file and
// `const` does not hoist.
let reloadUndoAvailability: () => Promise<void> = async () => undefined

const refreshDevicesFromChat = async () => enqueueBoardMutation(async () => {
  const ok = await refreshDevices()
  await reloadUndoAvailability()
  return ok
})
const refreshEnvironmentFromChat = async () => enqueueBoardMutation(async () => {
  const ok = await refreshEnvironmentVariables()
  await reloadUndoAvailability()
  return ok
})
const refreshRulesFromChat = async () => enqueueBoardMutation(async () => {
  const ok = await refreshRules()
  await reloadUndoAvailability()
  return ok
})
const refreshSpecificationsFromChat = async () => enqueueBoardMutation(async () => {
  const ok = await refreshSpecifications()
  await reloadUndoAvailability()
  return ok
})
const refreshTemplatesFromChat = async () => enqueueBoardMutation(async () => {
  const ok = await refreshDeviceTemplates()
  await reloadUndoAvailability()
  return ok
})
const refreshRunHistoryFromChat = async () => refreshRunHistory()
const refreshAllBoardStateFromChat = async () => enqueueBoardMutation(() => refreshAllBoardState())

const getChatSuggestionContext = () => {
  const visibleNodes = getVisibleDeviceNodes()
  const labelsById = new Map(visibleNodes.map(node => [node.id, node.label]))
  const displayDevice = (deviceId?: string) =>
    (deviceId ? labelsById.get(deviceId) : undefined) || t('app.unknownModelItem')

  return {
    deviceCount: visibleNodes.length,
    ruleCount: rules.value.length,
    specCount: specifications.value.length,
    templateCount: deviceTemplates.value.length,
    devices: visibleNodes.slice(0, 8).map(node => ({
      label: node.label,
      templateName: node.templateName
    })),
    rules: rules.value.slice(0, 6).map((rule, index) => ({
      name: rule.name?.trim() || t('app.ruleNumber', { number: index + 1 }),
      description: `${(rule.sources || []).map(source => displayDevice(source.fromId)).join(' + ')} -> ${displayDevice(rule.toId)}.${rule.toApi || t('app.unknownModelItem')}`
    })),
    specs: specifications.value.slice(0, 6).map((spec, index) => ({
      name: getSpecResultDisplayTitle(spec, index),
      formulaPreview: buildSpecFormula(spec, {
        nodes: nodes.value
      })
    })),
    templates: deviceTemplates.value
      .map(template => template.manifest?.Name || template.name)
      .filter(Boolean)
      .slice(0, 10)
  }
}

defineExpose({
  refreshDevices: refreshDevicesFromChat,
  refreshEnvironmentVariables: refreshEnvironmentFromChat,
  refreshRules: refreshRulesFromChat,
  refreshSpecifications: refreshSpecificationsFromChat,
  refreshDeviceTemplates: refreshTemplatesFromChat,
  refreshRunHistory: refreshRunHistoryFromChat,
  refreshAllBoardState: refreshAllBoardStateFromChat,
  getChatSuggestionContext,
  isChatInteractionLocked: () => !isBoardDataReady.value || isSceneReplacementInProgress.value || isOpeningAccount.value,
  prepareChatInteraction: () => prepareBoardChatInteraction({
    sceneReplacementInProgress: isSceneReplacementInProgress.value,
    tracePlaybackVisible: traceAnimationState.value.visible,
    simulationPlaybackVisible: simulationAnimationState.value.visible,
    closeTracePlayback: closeTraceAnimation,
    closeSimulationPlayback: closeSimulationTimeline
  })
})

// ==== Verification Logic ====
const isVerifying = ref(false)
const verificationResult = ref<VerificationResultView | null>(null)
type RunFailure = { key: string; error?: unknown; diagnostics: ApiErrorDetails }
const makeRunFailure = (key: string, error?: unknown): RunFailure => {
  const completedResult = isCompletedTaskResultUnavailableError(error) || isFuzzCompletedResultUnavailableError(error)
  const originalError = completedResult ? (error as CompletedTaskResultUnavailableError | FuzzCompletedResultUnavailableError).originalError : error
  const code = (originalError as any)?.code
  return {
    key: completedResult ? 'app.completedTaskResultUnavailable'
      : code === RUN_RESPONSE_INCOMPLETE_CODE ? 'app.runResponseIncomplete'
      : code === FUZZ_RESPONSE_INCOMPLETE_CODE ? 'app.fuzzResponseIncomplete'
      : code === 'TASK_POLL_TIMEOUT' ? key === 'app.verificationFailed' ? 'app.verificationTimeout' : 'app.simulationTimeout'
      : key,
    error: originalError,
    diagnostics: errorDetails(originalError)
  }
}
const runFailureTitle = (kind: 'verification' | 'simulation' | 'fuzzing', failure: RunFailure) => t(
  failure.key === 'app.completedTaskResultUnavailable'
    ? kind === 'verification' ? 'app.failedToLoadVerificationRun'
      : kind === 'simulation' ? 'app.failedToLoadSimulationRun' : 'app.failedToLoadFuzzingRun'
    : failure.key === 'app.verificationTimeout' || failure.key === 'app.simulationTimeout'
      ? 'app.taskStatusUnavailable'
      : kind === 'verification' ? 'app.verificationFailed' : kind === 'simulation' ? 'app.simulationFailed' : 'app.fuzzSearchFailed'
)
const formatRunFailure = (kind: 'verification' | 'simulation' | 'fuzzing', failure: RunFailure) => {
  const completedResult = failure.key === 'app.completedTaskResultUnavailable'
  const trackingInterrupted = failure.key === 'app.verificationTimeout' || failure.key === 'app.simulationTimeout'
  const status = (failure.error as any)?.response?.status
  const message = (kind === 'fuzzing' ? fuzzTaskQuotaMessage(failure.error)
    : formalOperationBusyMessage(failure.error) || asyncTaskQuotaMessage(failure.error, kind)) || t(failure.key)
  return {
    message,
    suggestion: t(completedResult ? 'app.completedTaskResultNextStep'
      : trackingInterrupted ? 'app.taskStatusNextStep'
      : status === 400 || status === 422 ? 'app.runValidationNextStep' : 'app.runFailureNextStep'),
    details: technicalErrorLines(failure.diagnostics), fields: {}
  }
}
const verificationError = ref<RunFailure | null>(null)
const runFailureDiagnostics = computed(() => ({
  verification: verificationError.value ? formatRunFailure('verification', verificationError.value) : null,
  simulation: simulationError.value ? formatRunFailure('simulation', simulationError.value) : null
}))
// A displayed verdict describes the model that was verified. Any semantic board change
// (applying a fix, editing rules/specs/devices from the inspector or chat) makes it stale,
// so the counterexample actions must stop claiming to describe the current board.
const verificationResultStale = ref(false)
const verificationFixApplied = ref(false)
watch(verificationResult, () => { verificationFixApplied.value = false }, { flush: 'sync' })
// Simulation conclusions become stale after a semantic edit too. Replay remains available because
// it uses the run's frozen visual scene; stale only means the conclusion is not about the live board.
const simulationResultStale = ref(false)

const presentRunFailure = (kind: 'verification' | 'simulation', messageKey: string, error?: unknown) => {
  const failure = makeRunFailure(messageKey, error)
  if (!isModelPlaybackActive.value) {
    if (kind === 'verification') verificationError.value = failure
    else simulationError.value = failure
    return
  }
  const ownerAuthScopeEpoch = boardAuthScopeEpoch
  const ownerUserId = currentAuthUserId.value
  let close!: () => void
  close = notifyAvailableResult({
    title: () => runFailureTitle(kind, failure),
    message: () => formatRunFailure(kind, failure).message,
    actionLabel: () => t('app.viewDetails'),
    discardLabel: () => t('app.close'),
    onAction: () => {
      if (!isCurrentBoardAuthScope(ownerAuthScopeEpoch) || currentAuthUserId.value !== ownerUserId) return true
      if (!ensureHistoricalPlaybackUiAdmission()) return false
      closeOtherWorkflowSurfaces()
      if (kind === 'verification') verificationError.value = failure
      else simulationError.value = failure
      return true
    },
    onClose: () => unsavedRunNotifications.delete(close)
  })
  unsavedRunNotifications.add(close)
}

/*
 * Semantic scene changes since load, counted.
 *
 * The flags below can only mark a result that already exists, and a run in flight has none:
 * `handleVerify` and `handleSimulate` both null their result ref before submitting. So a board edit
 * made *during* a run marked nothing, and the completion path then set the stale flag to `false`
 * unconditionally — the verdict arrived presenting itself as describing the canvas the user was now
 * looking at, offering a Fix computed against a scene that no longer existed. A run captures this
 * counter at submission and compares it on arrival.
 *
 * Not `boardMutationAdmissionEpoch`, which counts *admitted mutations* rather than semantic changes:
 * it advances for the undo-history preview and clear, which touch only the journal. Those would have
 * marked a perfectly current verdict stale. This increments from the same callback that owns the
 * staleness rule, so the two can never disagree about what counts as a semantic change.
 */
let semanticSceneChangeCount = 0

// Called from the single semantic-scene-change hook in the board mutation queue, so every
// mutation path (fix apply, chat tool, inspector edit) is covered by one rule.
const markVerificationResultStale = () => {
  verificationFixApplied.value = false
  semanticSceneChangeCount += 1
  if (verificationResult.value) verificationResultStale.value = true
  // The run survives hiding the terminal, so staleness must follow that run.
  if (lastSimulationResult.value) simulationResultStale.value = true
}

/**
 * Single owner of everything a semantic device/rule/specification mutation owes the board.
 *
 * Every semantic mutation calls this instead of hand-assembling the same four follow-ups, which
 * is what previously let rule reorder skip undo availability and undo skip the canvas edges.
 * See `board/semanticCommit.ts` for the ordering guarantee.
 */
const commitSemanticScene = createBoardSemanticCommit({
  setNodes: next => { replaceNodesFromServer(next) },
  setEnvironmentVariables: next => { environmentVariables.value = next },
  setRules: next => { rules.value = next },
  setSpecs: next => { specifications.value = next },
  syncRuleDerivedEdges: () => syncRuleDerivedEdges(),
  markVerificationResultStale: () => markVerificationResultStale(),
  // Declared later in setup; both are only reached from async handlers, never during setup.
  syncUndoAvailability: availability => syncBoardUndoAvailability(availability),
  clearDanglingFocus: scene => reconcileDanglingBoardFocus(scene)
})

type VerificationResultView = VerificationResult
type SimulationResultView = SimulationResult

const compareRunToCurrentBoard = (
  result: { modelSnapshot?: ModelRunSnapshot } | null,
  kind: 'verification' | 'simulation'
): RunBoardComparison => compareModelSnapshot(result?.modelSnapshot, currentBoardModelFingerprints.value, kind)

// ==== Rule Recommendation Logic ====
const isRecommendingRules = ref(false)
const isRecommendingDevices = ref(false)
const isRecommendingSpecs = ref(false)
const isRecommendingScenario = ref(false)
const ruleRecommendations = ref<RuleRecommendation[]>([])
const ruleRecommendationMessage = ref('')
const localizedRecommendationText = (value: unknown, fallback = ''): string =>
  localizedTextOrFallback(value, fallback, locale.value)
const formatScenarioObjectiveIssue = (
  issue: ScenarioRecommendationResponse['objectiveIssues'][number]
): string => localizedRecommendationText(
  issue.message,
  t(`app.scenarioObjectiveIssues.${issue.code}`)
)
const ruleRecommendationFilteredCount = ref(0)
const ruleRecommendationFilteredItems = ref<RecommendationFilteredItem[]>([])
const ruleRecommendationAdjustedItems = ref<RecommendationAdjustmentItem[]>([])
const ruleRecommendationRawCandidateCount = ref(0)
const ruleRecommendationInspectedCount = ref(0)
const ruleRecommendationTruncatedCount = ref(0)
const ruleRecommendationIsAppliedConfirmation = ref(false)
const appliedRuleRecommendations = ref<Set<number>>(new Set())
const applyingRuleRecommendations = ref<Set<number>>(new Set())
const showRecommendationPanel = ref(false)
const ruleRecommendationRequested = ref(false)
const ruleRecommendationFilters = reactive({
  maxRecommendations: 5,
  userRequirement: ''
})
const ruleRecommendationAbortController = ref<AbortController | null>(null)
const ruleRecommendationRequestId = ref<string | null>(null)
let ruleRecommendationRequestEpoch = 0

const validateRecommendationCount = (value: unknown, field = t('app.maxRecommendationsField')): number =>
  optionalIntegerInRange(value, field, 5, 1, 10)

// Wording rules live in `board/recommendationFilterText.ts` so they can be tested without the board.
const recommendationTextContext = computed(() => ({ t, locale: locale.value }))
const formatRecommendationFilteredType = (type: unknown): string =>
  formatFilteredType(type, recommendationTextContext.value)
const formatRecommendationFilteredItem = (item: RecommendationFilteredItem): string =>
  formatFilteredItem(item, recommendationTextContext.value)

/*
 * `'spec'` joined this union when the specification panel started rendering its adjusted items.
 *
 * The union used to enumerate exactly the three panels that displayed them, which made it a type-level record of
 * the gap rather than of a design: spec never read the field, so a server-completed value was discarded silently.
 * `'spec'` behaves like `'rule'` here — only `'device'` and `'scenario'` branch, to resolve a label back to its
 * template for model-token formatting.
 */
type RecommendationAdjustmentContext = 'rule' | 'device' | 'scenario' | 'spec'

const recommendationAdjustmentTemplate = (
  item: RecommendationAdjustmentItem,
  context: RecommendationAdjustmentContext
): DeviceTemplate | undefined => {
  if (String(item.type).toLowerCase() !== 'device') return undefined
  const label = item.label?.trim()
  if (!label) return undefined
  if (context === 'device') {
    const recommendation = deviceRecommendations.value.find(candidate => candidate.suggestedLabel === label)
    return findTemplateByAnyName(recommendation?.templateName)
  }
  if (context === 'scenario') {
    const device = recommendedScenarioScene.value?.devices.find(candidate => candidate.label === label)
    return findTemplateByAnyName(device?.templateName)
  }
  return undefined
}

const formatRecommendationAdjustmentToken = (
  item: RecommendationAdjustmentItem,
  context: RecommendationAdjustmentContext,
  value: unknown
): string => {
  if (context === 'scenario' && String(item.type).toLowerCase() === 'environment') {
    const name = item.label?.trim() || ''
    return formatScenarioEnvironmentModelToken(name, value)
  }
  return formatTemplateModelToken(recommendationAdjustmentTemplate(item, context), value)
}

const formatScenarioAdjustmentValue = (
  item: RecommendationAdjustmentItem,
  context: RecommendationAdjustmentContext,
  key: string,
  value: unknown
): string | null => {
  const formatToken = (token: unknown) => formatRecommendationAdjustmentToken(item, context, token)
  if (key === 'suggestedLabel') return t('app.scenarioDefaultSuggestedLabel', { value })
  if (key === 'state') return t('app.scenarioDefaultInitialState', { value: formatToken(value) })
  if (key === 'currentStateTrust') return t('app.scenarioDefaultStateTrust', { value: t(`app.${value}`) })
  if (key === 'currentStatePrivacy') return t('app.scenarioDefaultStatePrivacy', { value: t(`app.${value}`) })
  if (key === 'value') return t('app.scenarioDefaultEnvironmentValue', { value: formatToken(value) })
  if (key === 'trust') return t('app.scenarioDefaultEnvironmentTrust', { value: t(`app.${value}`) })
  if (key === 'privacy') return t('app.scenarioDefaultEnvironmentPrivacy', { value: t(`app.${value}`) })
  if (key.startsWith('variables.') && key.endsWith('.trust')) {
    const variable = key.slice('variables.'.length, -'.trust'.length)
    return t('app.scenarioDefaultVariableTrust', { variable: formatToken(variable), value: t(`app.${value}`) })
  }
  if (key.startsWith('variables.') && key.endsWith('.value')) {
    const variable = key.slice('variables.'.length, -'.value'.length)
    return t('app.scenarioDefaultVariableValue', {
      variable: formatToken(variable),
      value: formatToken(value)
    })
  }
  if (key.startsWith('privacies.') && key.endsWith('.privacy')) {
    const variable = key.slice('privacies.'.length, -'.privacy'.length)
    return t('app.scenarioDefaultVariablePrivacy', { variable: formatToken(variable), value: t(`app.${value}`) })
  }
  return null
}

const formatRecommendationAdjustmentItem = (
  item: RecommendationAdjustmentItem,
  context: RecommendationAdjustmentContext
): string => {
  const reason = localizedRecommendationText(
    item.reason,
    t('app.recommendationAdjustedUnknownReason')
  )
  const rawLabel = item.label?.trim()
  const label = rawLabel
    ? formatRecommendationAdjustmentToken(item, context, rawLabel)
    : formatRecommendationFilteredType(item.type)
  const values = Object.entries(item.appliedValues || {})
    .map(([key, value]) => formatScenarioAdjustmentValue(item, context, key, value))
    .filter((value): value is string => Boolean(value))
  const hasLayoutDefaults = Object.keys(item.appliedValues || {})
    .some(key => key === 'position' || key.startsWith('position.') || key === 'width' || key === 'height')
  if (hasLayoutDefaults) values.push(t('app.scenarioDefaultCanvasLayout'))
  return t('app.recommendationAdjustedReason', {
    label,
    reason,
    values: values.length > 0 ? t('app.recommendationAdjustedValues', { values: values.join('；') }) : ''
  })
}

const specificationExists = (recommendation: any): boolean => {
  const key = buildSpecificationSemanticKey(recommendation)
  return specifications.value.some(spec => buildSpecificationSemanticKey(spec) === key)
}

type RecommendationPanelKind = 'rule' | 'device' | 'spec' | 'scenario'
const recommendationFailures = reactive<Partial<Record<RecommendationPanelKind,
  ApiErrorDetails & { message: string; suggestion: string; outcomeUnknown: boolean }>>>({})
const recommendationApplyFailures = reactive<Record<'rule' | 'device' | 'spec', Record<number,
  ApiErrorDetails & { message: string; suggestion: string; outcomeUnknown: boolean }>>>({ rule: {}, device: {}, spec: {} })

const reportRecommendationApplyFailure = (
  kind: 'rule' | 'device' | 'spec', index: number, error: unknown, message: string, outcomeUnknown = false
) => {
  recommendationApplyFailures[kind][index] = {
    ...errorDetails(error), message, outcomeUnknown,
    suggestion: outcomeUnknown ? '' : t('app.recommendationApplyRejectedHint')
  }
}

const reportRecommendationFailure = (
  kind: RecommendationPanelKind,
  error: unknown,
  fallback: string,
  outcomeUnknown = false
) => {
  recommendationFailures[kind] = {
    outcomeUnknown,
    message: outcomeUnknown ? t('app.recommendationResponseLostRecovering')
      : extractRecommendationErrorMessage(error, fallback),
    suggestion: t(outcomeUnknown ? 'app.recommendationUnknownNextStep' : 'app.recommendationFailureNextStep'),
    ...errorDetails(error)
  }
}

/**
 * The in-flight request identity for one recommendation panel.
 *
 * Every step of the ownership / cancellation / recovery machinery needs the same four things,
 * and they always belong to the same panel. Bundling them per panel means a call site names
 * the panel once instead of re-threading four arguments that must not be mixed between panels.
 */
type RecommendationRequestHandle = {
  kind: RecommendationPanelKind
  requestId: Ref<string | null>
  abortController: Ref<AbortController | null>
  running: Ref<boolean>
  cancelledMessageKey: string
}

const recommendationStopRequestsInFlight = new Set<RecommendationPanelKind>()
const recommendationRequestOwners = new Map<string, RecommendationRequestOwner>()
const recommendationOutcomeUnknownWarnings = new Set<string>()
type RecommendationTerminalEvidence = 'post-terminal' | 'status-finished'
const recommendationTerminalEvidence = new Map<string, RecommendationTerminalEvidence>()
const RECOMMENDATION_STOP_RETRY_DELAY_MS = 50
type RecommendationStopRecovery = {
  requestId: string
  ownerUserId: number | null
  ownerAuthToken: string
  requestIdRef: Ref<string | null>
  abortControllerRef: Ref<AbortController | null>
  controller: AbortController | null
  setRunning: (running: boolean) => void
  cancelledMessageKey: string
  showMessage: boolean
  cancellationAccepted: boolean
  acceptanceNotified: boolean
  consecutiveStatusFailures: number
  retryNotBeforeMs: number
}
const recommendationStopRecoveries = new Map<RecommendationPanelKind, RecommendationStopRecovery>()

const hasRecommendationTerminalEvidence = (
  requestId: string
): boolean => recommendationTerminalEvidence.has(requestId)

const recordRecommendationTerminalEvidence = (
  requestId: string,
  evidence: RecommendationTerminalEvidence
) => {
  recommendationTerminalEvidence.set(requestId, evidence)
  if (recommendationTerminalEvidence.size > 64) {
    const oldestRequestId = recommendationTerminalEvidence.keys().next().value
    if (oldestRequestId) recommendationTerminalEvidence.delete(oldestRequestId)
  }
}

const captureRecommendationRequestOwner = (): RecommendationRequestOwner | null => {
  const authToken = getToken()
  if (!authToken) {
    notifyError(t('app.recommendationAuthenticationRequired'))
    return null
  }
  return { userId: currentAuthUserId.value, authToken }
}

const getRecommendationRequestOwner = (requestId: string): RecommendationRequestOwner | null => {
  for (const recovery of recommendationStopRecoveries.values()) {
    if (recovery.requestId === requestId && recovery.ownerAuthToken) {
      return { userId: recovery.ownerUserId, authToken: recovery.ownerAuthToken }
    }
  }
  return recommendationRequestOwners.get(requestId) ?? null
}

watch(
  () => [currentAuthUserId.value, authState.token] as const,
  ([userId, authToken]) => {
    if (userId === null || !authToken) return
    recommendationRequestOwners.forEach((owner, requestId) => {
      recommendationRequestOwners.set(
        requestId,
        refreshRecommendationOwnerCredential(owner, userId, authToken)
      )
    })
    recommendationStopRecoveries.forEach(recovery => {
      const refreshed = refreshRecommendationOwnerCredential(
        { userId: recovery.ownerUserId, authToken: recovery.ownerAuthToken },
        userId,
        authToken
      )
      recovery.ownerAuthToken = refreshed.authToken
    })
  },
  { flush: 'post' }
)

const waitForRecommendationCancellationRetry = () => new Promise<void>(resolve => {
  setTimeout(resolve, RECOMMENDATION_STOP_RETRY_DELAY_MS)
})

const cancelRecommendationAsOwner = (
  requestId: string,
  ownerAuthToken: string
): Promise<boolean> => boardApi.cancelRecommendation(requestId, ownerAuthToken)

const readRecommendationStatusAsOwner = (
  requestId: string,
  ownerAuthToken: string
) => boardApi.getRecommendationStatus(requestId, ownerAuthToken)

const releaseRecommendationTracking = (
  kind: RecommendationPanelKind,
  requestId: string,
  options: {
    terminalEvidence?: RecommendationTerminalEvidence
  } = {}
) => {
  const recovery = recommendationStopRecoveries.get(kind)
  if (recovery?.requestId === requestId) {
    recommendationStopRecoveries.delete(kind)
    recovery.controller?.abort()
    if (recovery.abortControllerRef.value === recovery.controller) {
      recovery.abortControllerRef.value = null
    }
    const wasCurrent = recovery.requestIdRef.value === requestId
    recovery.requestIdRef.value = requestIdAfterTerminalSettlement(
      recovery.requestIdRef.value,
      requestId
    )
    if (wasCurrent) {
      recovery.setRunning(false)
      const failure = recommendationFailures[kind]
      if (failure?.outcomeUnknown) {
        failure.outcomeUnknown = false
        failure.message = t('app.recommendationEndedWithoutResult')
        failure.suggestion = t('app.recommendationFailureNextStep')
      }
    }
  }
  recommendationRequestOwners.delete(requestId)
  recommendationOutcomeUnknownWarnings.delete(requestId)
  if (recommendationProgressRequestId.value === requestId) {
    recommendationProgressRequestId.value = null
  }
  if (options.terminalEvidence) {
    recordRecommendationTerminalEvidence(requestId, options.terminalEvidence)
  }
}

const settleRecommendationPost = (
  panel: RecommendationRequestHandle,
  requestId: string,
  controller: AbortController
) => {
  const { kind, requestId: requestIdRef, abortController: abortControllerRef } = panel
  const setRunning = (running: boolean) => { panel.running.value = running }
  const recovery = recommendationStopRecoveries.get(kind)
  if (recovery?.requestId === requestId) {
    releaseRecommendationTracking(kind, requestId, { terminalEvidence: 'post-terminal' })
    return
  }
  recommendationRequestOwners.delete(requestId)
  recommendationOutcomeUnknownWarnings.delete(requestId)
  recordRecommendationTerminalEvidence(requestId, 'post-terminal')
  const wasCurrent = requestIdRef.value === requestId
  requestIdRef.value = requestIdAfterTerminalSettlement(requestIdRef.value, requestId)
  if (abortControllerRef.value === controller) abortControllerRef.value = null
  if (recommendationProgressRequestId.value === requestId) {
    recommendationProgressRequestId.value = null
  }
  if (wasCurrent) setRunning(false)
}

const beginUnknownRecommendationRecovery = (
  panel: RecommendationRequestHandle,
  requestId: string,
  error?: unknown
) => {
  if (panel.requestId.value !== requestId) return
  if (error !== undefined) reportRecommendationFailure(panel.kind, error, '', true)
  if (!recommendationOutcomeUnknownWarnings.has(requestId)) {
    recommendationOutcomeUnknownWarnings.add(requestId)
    notifyBlocked(t('app.recommendationResponseLostRecovering'))
  }
  void stopActiveRecommendation(panel, { showMessage: false })
}

const ensureRecommendationStopRecovery = (
  panel: RecommendationRequestHandle,
  options: { showMessage?: boolean } = {}
): RecommendationStopRecovery | null => {
  const { kind, requestId: requestIdRef, abortController: abortControllerRef } = panel
  const setRunning = (running: boolean) => { panel.running.value = running }
  const cancelledMessageKey = panel.cancelledMessageKey
  const requestId = requestIdRef.value
  if (!requestId) return null
  const existing = recommendationStopRecoveries.get(kind)
  if (existing?.requestId === requestId) {
    if (options.showMessage !== false) existing.showMessage = true
    return existing
  }
  const owner = getRecommendationRequestOwner(requestId)
  if (!owner) return null
  const recovery: RecommendationStopRecovery = {
    requestId,
    ownerUserId: owner.userId,
    ownerAuthToken: owner.authToken,
    requestIdRef,
    abortControllerRef,
    controller: abortControllerRef.value,
    setRunning,
    cancelledMessageKey,
    showMessage: options.showMessage !== false,
    cancellationAccepted: false,
    acceptanceNotified: false,
    consecutiveStatusFailures: 0,
    retryNotBeforeMs: 0
  }
  recommendationStopRecoveries.set(kind, recovery)
  recommendationProgressStage.value = 'CANCELLING'
  setRunning(true)
  return recovery
}

const acceptRecommendationCancellation = (
  kind: RecommendationPanelKind,
  requestId: string
) => {
  const recovery = recommendationStopRecoveries.get(kind)
  if (!recovery || recovery.requestId !== requestId) return
  recovery.cancellationAccepted = true
  recovery.controller?.abort()
  if (recovery.abortControllerRef.value === recovery.controller) {
    recovery.abortControllerRef.value = null
  }
  if (recovery.showMessage && !recovery.acceptanceNotified) {
    recovery.acceptanceNotified = true
    notifyInfo(t(recovery.cancelledMessageKey))
  }
}

const finishRecommendationStopRecovery = (
  kind: RecommendationPanelKind,
  requestId: string
) => {
  const recovery = recommendationStopRecoveries.get(kind)
  if (!recovery || recovery.requestId !== requestId) return
  releaseRecommendationTracking(kind, requestId, { terminalEvidence: 'status-finished' })
}

const stopActiveRecommendation = async (
  panel: RecommendationRequestHandle,
  options: { showMessage?: boolean } = {}
) => {
  const { kind, requestId: requestIdRef, abortController: abortControllerRef } = panel
  const setRunning = (running: boolean) => { panel.running.value = running }
  if (recommendationStopRequestsInFlight.has(kind)) return
  recommendationStopRequestsInFlight.add(kind)
  const requestId = requestIdRef.value
  const controller = abortControllerRef.value
  if (!requestId) {
    controller?.abort()
    if (abortControllerRef.value === controller) abortControllerRef.value = null
    setRunning(false)
    recommendationStopRequestsInFlight.delete(kind)
    return
  }
  const recovery = ensureRecommendationStopRecovery(panel, options)
  if (!recovery) {
    recommendationStopRequestsInFlight.delete(kind)
    notifyBlocked(t('app.recommendationStopRequestMayStillBeRunning'))
    return
  }
  // Keep the POST transport alive until cancellation is accepted. Aborting it first can let
  // the DELETE beat server-side registration while the provider call continues unobserved.
  try {
    const cancellationAccepted = await requestInteractiveCancellation({
      cancel: () => cancelRecommendationAsOwner(requestId, recovery.ownerAuthToken),
      waitBeforeRetry: waitForRecommendationCancellationRetry,
      shouldContinue: () => recommendationStopRecoveries.get(kind)?.requestId === requestId
    })
    if (cancellationAccepted) {
      acceptRecommendationCancellation(kind, requestId)
    } else {
      notifyBlocked(t('app.recommendationStopRequestMayStillBeRunning'))
    }
  } catch (error) {
    console.error('Failed to cancel recommendation request:', error)
    notifyBlocked(t('app.recommendationStopRequestMayStillBeRunning'))
  } finally {
    recommendationStopRequestsInFlight.delete(kind)
    void refreshRecommendationProgress(kind)
  }
}

const getRunningRecommendationKind = (): RecommendationPanelKind | null => {
  if (isRecommendingScenario.value) return 'scenario'
  if (isRecommendingRules.value) return 'rule'
  if (isRecommendingDevices.value) return 'device'
  if (isRecommendingSpecs.value) return 'spec'
  return null
}

const isAnyRecommendationRunning = (): boolean => getRunningRecommendationKind() !== null

const recommendationProgressElapsed = ref(0)
const recommendationProgressStage = ref<InteractiveOperationStage>('QUEUED')
const recommendationProgressRequestId = ref<string | null>(null)
let recommendationProgressTimer: ReturnType<typeof setInterval> | null = null
let recommendationProgressRefreshInFlight = false
const refreshRecommendationProgress = async (kind: RecommendationPanelKind) => {
  if (recommendationProgressRefreshInFlight) return
  const requestId = recommendationProgressRequestId.value
  if (!requestId) return
  const scheduledRecovery = recommendationStopRecoveries.get(kind)
  if (scheduledRecovery?.requestId === requestId
    && scheduledRecovery.retryNotBeforeMs > Date.now()) return
  recommendationProgressRefreshInFlight = true
  try {
    const recovery = recommendationStopRecoveries.get(kind)
    if (recovery?.requestId === requestId && !recovery.cancellationAccepted) {
      try {
        if (await cancelRecommendationAsOwner(requestId, recovery.ownerAuthToken)) {
          acceptRecommendationCancellation(kind, requestId)
        }
      } catch {
        // The status read below remains the source of truth while cancellation is retried.
      }
    }
    const statusOwner = getRecommendationRequestOwner(requestId)
    if (!statusOwner) throw new Error('Recommendation owner credential is unavailable')
    const status = await readRecommendationStatusAsOwner(requestId, statusOwner.authToken)
    if (getRunningRecommendationKind() === kind && recommendationProgressRequestId.value === requestId) {
      recommendationProgressStage.value = status.stage
      const currentRecovery = recommendationStopRecoveries.get(kind)
      if (currentRecovery?.requestId === requestId) {
        currentRecovery.consecutiveStatusFailures = 0
        currentRecovery.retryNotBeforeMs = 0
        if (status.state === 'FINISHED') finishRecommendationStopRecovery(kind, requestId)
      }
    }
  } catch (error: any) {
    const recovery = recommendationStopRecoveries.get(kind)
    if (recovery?.requestId === requestId) {
      // A 404 can mean DELETE beat POST registration, while a transport failure says
      // nothing about server completion. Retain the owner and back off without releasing.
      const retryPlan = planRecommendationRecoveryAfterStatusFailure(
        recovery.consecutiveStatusFailures
      )
      recovery.consecutiveStatusFailures = retryPlan.consecutiveFailures
      recovery.retryNotBeforeMs = Date.now() + retryPlan.retryDelayMs
    }
    // Registration and ordinary completion can race with this read; the POST remains authoritative.
  } finally {
    recommendationProgressRefreshInFlight = false
  }
}
watch(
  () => [getRunningRecommendationKind(), recommendationProgressRequestId.value] as const,
  ([kind, requestId]) => {
    if (recommendationProgressTimer) {
      clearInterval(recommendationProgressTimer)
      recommendationProgressTimer = null
    }
    recommendationProgressElapsed.value = 0
    recommendationProgressStage.value = 'QUEUED'
    if (!kind || !requestId) return
    const startedAt = Date.now()
    void refreshRecommendationProgress(kind)
    recommendationProgressTimer = setInterval(() => {
      recommendationProgressElapsed.value = Math.floor((Date.now() - startedAt) / 1000)
      void refreshRecommendationProgress(kind)
    }, 1000)
  }
)

const isAnyRecommendationPanelVisible = (): boolean =>
  showRecommendationPanel.value ||
  showDeviceRecommendationPanel.value ||
  showSpecRecommendationPanel.value ||
  showScenarioRecommendationPanel.value

const isRecommendationRunningForAnother = (kind: RecommendationPanelKind): boolean => {
  const runningKind = getRunningRecommendationKind()
  return runningKind !== null && runningKind !== kind
}

const canOpenRecommendationPanel = (kind: RecommendationPanelKind): boolean => {
  if (!ensureBoardDataReady()) return false
  if (isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return false
  }
  if (simulationAnimationState.value.visible) {
    notifyBlocked(t('app.closeCurrentSimulationFirst'))
    return false
  }
  if (traceAnimationState.value.visible) {
    notifyBlocked(t('app.closeCounterexampleFirst'))
    return false
  }
  if (isRecommendationRunningForAnother(kind)) {
    notifyBlocked(t('app.recommendationGenerationInProgress'))
    return false
  }
  return true
}

const resetRuleRecommendationResults = () => {
  recommendationApplyFailures.rule = {}
  delete recommendationFailures.rule
  ruleRecommendations.value = []
  ruleRecommendationMessage.value = ''
  ruleRecommendationFilteredCount.value = 0
  ruleRecommendationFilteredItems.value = []
  ruleRecommendationAdjustedItems.value = []
  ruleRecommendationRawCandidateCount.value = 0
  ruleRecommendationInspectedCount.value = 0
  ruleRecommendationTruncatedCount.value = 0
  ruleRecommendationIsAppliedConfirmation.value = false
  appliedRuleRecommendations.value.clear()
  applyingRuleRecommendations.value.clear()
  ruleRecommendationRequested.value = false
}

const resetDeviceRecommendationResults = () => {
  recommendationApplyFailures.device = {}
  delete recommendationFailures.device
  deviceRecommendations.value = []
  deviceRecommendationMessage.value = ''
  deviceRecommendationFilteredCount.value = 0
  deviceRecommendationFilteredItems.value = []
  deviceRecommendationAdjustedItems.value = []
  deviceRecommendationRawCandidateCount.value = 0
  deviceRecommendationInspectedCount.value = 0
  deviceRecommendationTruncatedCount.value = 0
  deviceRecommendationIsAppliedConfirmation.value = false
  appliedDeviceRecommendations.value.clear()
  applyingDeviceRecommendations.value.clear()
  deviceRecommendationRequested.value = false
}

const resetSpecRecommendationResults = () => {
  recommendationApplyFailures.spec = {}
  delete recommendationFailures.spec
  specRecommendations.value = []
  specRecommendationMessage.value = ''
  specRecommendationFilteredCount.value = 0
  specRecommendationFilteredItems.value = []
  specRecommendationRawCandidateCount.value = 0
  specRecommendationInspectedCount.value = 0
  specRecommendationTruncatedCount.value = 0
  specRecommendationAdjustedItems.value = []
  specRecommendationIsAppliedConfirmation.value = false
  appliedSpecRecommendations.value.clear()
  applyingSpecRecommendations.value.clear()
  specRecommendationRequested.value = false
}

const resetScenarioRecommendationResults = () => {
  delete recommendationFailures.scenario
  scenarioRecommendationResult.value = null
  scenarioRecommendationMessage.value = ''
  scenarioRecommendationRequested.value = false
}

// Closing a surface is not a fresh result, so it must not clear a stale flag: `lastVerificationResult`
// and `lastSimulationResult` outlive every dialog, and reopening one must still warn that the canvas
// changed under it. Only a new run clears the flag.
function closeResultSurfaces() {
  // Every in-flight run-detail load has to be invalidated, not just the fuzzing epoch: the only
  // staleness guard in `openVerificationRun` and `selectAndPlaySimulationTrace` is
  // `historyDetailRequests.isCurrent`, which stays true for the newest request — so a load resolving
  // after this ran would repopulate the surface it just cleared. Same defect that let a dismissed
  // verification dialog reopen itself.
  historyDetailRequests.invalidate()
  fuzzingResultRequestEpoch += 1
  verificationResult.value = null
  verificationError.value = null
  simulationError.value = null
  fuzzingResult.value = null
  fuzzingError.value = null
  fuzzingResultLoading.value = false
  showFuzzingResultDialog.value = false
}

/**
 * Recommendation panels are mutually exclusive with each other and with the run panels.
 * One table-driven opener keeps that invariant in a single place: the previous
 * copy-per-panel version had already drifted (the scenario panel closed *itself*, which
 * threw away the state it had just reset).
 *
 * Accessors are lazy because the per-panel close handlers are declared further down.
 */
const recommendationPanels: Record<RecommendationPanelKind, {
  isVisible: () => boolean
  show: () => void
  close: () => void
  resetResults: () => void
}> = {
  rule: {
    isVisible: () => showRecommendationPanel.value,
    show: () => { showRecommendationPanel.value = true },
    close: () => closeRecommendationPanel(),
    resetResults: () => resetRuleRecommendationResults()
  },
  device: {
    isVisible: () => showDeviceRecommendationPanel.value,
    show: () => { showDeviceRecommendationPanel.value = true },
    close: () => closeDeviceRecommendationPanel(),
    resetResults: () => resetDeviceRecommendationResults()
  },
  spec: {
    isVisible: () => showSpecRecommendationPanel.value,
    show: () => { showSpecRecommendationPanel.value = true },
    close: () => closeSpecRecommendationPanel(),
    resetResults: () => resetSpecRecommendationResults()
  },
  scenario: {
    isVisible: () => showScenarioRecommendationPanel.value,
    show: () => { showScenarioRecommendationPanel.value = true },
    close: () => closeScenarioRecommendationPanel(),
    resetResults: () => resetScenarioRecommendationResults()
  }
}

const openRecommendationPanel = (kind: RecommendationPanelKind): boolean => {
  const panel = recommendationPanels[kind]
  if (panel.isVisible()) return true
  if (!canOpenRecommendationPanel(kind)) return false

  closeResultSurfaces()
  closeHistoryPanel()
  showSimulationPanel.value = false
  showVerificationPanel.value = false
  showFuzzingPanel.value = false
  for (const otherKind of Object.keys(recommendationPanels) as RecommendationPanelKind[]) {
    if (otherKind !== kind) recommendationPanels[otherKind].close()
  }

  panel.resetResults()
  panel.show()
  return true
}

const openRuleRecommendationPanel = () => openRecommendationPanel('rule')
const openDeviceRecommendationPanel = () => openRecommendationPanel('device')
const openSpecRecommendationPanel = () => openRecommendationPanel('spec')
const openScenarioRecommendationPanel = () => openRecommendationPanel('scenario')

const fetchRuleRecommendations = async () => {
  if (isRecommendationRequestActive(isRecommendingRules.value, ruleRecommendationRequestId.value)) {
    ruleRecommendationRequestEpoch += 1
    await stopActiveRecommendation(ruleRecommendationPanel)
    return
  }
  if (isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return
  }
  if (!ensureBoardDataReady(['nodes', 'templates', 'rules'])) return

  if (showRecommendationPanel.value) {
    if (!canOpenRecommendationPanel('rule')) return
  } else if (!openRuleRecommendationPanel()) {
    return
  }

  const requestOwner = captureRecommendationRequestOwner()
  if (!requestOwner) return

  isRecommendingRules.value = true
  recommendationApplyFailures.rule = {}
  ruleRecommendationRequested.value = true
  delete recommendationFailures.rule
  ruleRecommendations.value = []
  ruleRecommendationMessage.value = ''
  ruleRecommendationFilteredCount.value = 0
  ruleRecommendationFilteredItems.value = []
  ruleRecommendationAdjustedItems.value = []
  ruleRecommendationRawCandidateCount.value = 0
  ruleRecommendationInspectedCount.value = 0
  ruleRecommendationTruncatedCount.value = 0
  ruleRecommendationIsAppliedConfirmation.value = false
  appliedRuleRecommendations.value.clear()
  applyingRuleRecommendations.value.clear()
  const requestEpoch = ++ruleRecommendationRequestEpoch
  const requestSceneGeneration = recommendationSceneGeneration
  const controller = new AbortController()
  const requestId = crypto.randomUUID()
  ruleRecommendationAbortController.value = controller
  ruleRecommendationRequestId.value = requestId
  recommendationRequestOwners.set(requestId, requestOwner)
  recommendationProgressRequestId.value = requestId
  let postTerminal = false
  let requestDispatched = false
  try {
    const validatedMaxRecommendations = validateRecommendationCount(ruleRecommendationFilters.maxRecommendations)
    requestDispatched = true
    const response = await rulesApi.recommendRules(
      {
        requestId,
        authToken: requestOwner.authToken,
        signal: controller.signal
      },
      validatedMaxRecommendations,
      locale.value,
      ruleRecommendationFilters.userRequirement
    )
    postTerminal = true
    if (requestEpoch !== ruleRecommendationRequestEpoch
      || requestSceneGeneration !== recommendationSceneGeneration) return
    ruleRecommendations.value = response.recommendations
    ruleRecommendationMessage.value = localizedRecommendationText(
      response.message,
      t('app.recommendationsFound', { count: response.count })
    )
    ruleRecommendationFilteredCount.value = response.filteredCount
    ruleRecommendationFilteredItems.value = response.filteredItems
    ruleRecommendationAdjustedItems.value = response.adjustedItems
    ruleRecommendationRawCandidateCount.value = response.rawCandidateCount
    ruleRecommendationInspectedCount.value = response.inspectedCount
    ruleRecommendationTruncatedCount.value = response.truncatedCount
  } catch (error: any) {
    // 如果是取消请求，不显示错误
    if (error.name === 'CanceledError' || error.code === 'ERR_CANCELED') {
      return
    }
    if (requestDispatched && !postTerminal && isRecommendationPostOutcomeUnknown(error)) {
      beginUnknownRecommendationRecovery(ruleRecommendationPanel, requestId,
        requestEpoch === ruleRecommendationRequestEpoch && requestSceneGeneration === recommendationSceneGeneration ? error : undefined)
      return
    }
    postTerminal = true
    if (requestEpoch !== ruleRecommendationRequestEpoch
      || requestSceneGeneration !== recommendationSceneGeneration) return
    console.error('Failed to fetch rule recommendations:', error)
    reportRecommendationFailure('rule', error, t('app.failedToFetchRuleRecommendations'))
  } finally {
    if (postTerminal) {
      settleRecommendationPost(ruleRecommendationPanel, requestId, controller)
    }
  }
}

// 关闭推荐面板
const closeRecommendationPanel = () => {
  ruleRecommendationRequestEpoch += 1
  void stopActiveRecommendation(ruleRecommendationPanel, { showMessage: false })
  showRecommendationPanel.value = false
  resetRuleRecommendationResults()
}

// ==== Device Recommendation Logic ====
const deviceRecommendations = ref<DeviceRecommendation[]>([])
const deviceRecommendationMessage = ref('')
const deviceRecommendationFilteredCount = ref(0)
const deviceRecommendationFilteredItems = ref<RecommendationFilteredItem[]>([])
const deviceRecommendationAdjustedItems = ref<RecommendationAdjustmentItem[]>([])
const deviceRecommendationRawCandidateCount = ref(0)
const deviceRecommendationInspectedCount = ref(0)
const deviceRecommendationTruncatedCount = ref(0)
const deviceRecommendationIsAppliedConfirmation = ref(false)
const appliedDeviceRecommendations = ref<Set<number>>(new Set())
const applyingDeviceRecommendations = ref<Set<number>>(new Set())
const showDeviceRecommendationPanel = ref(false)
const deviceRecommendationAbortController = ref<AbortController | null>(null)
const deviceRecommendationRequestId = ref<string | null>(null)
const deviceRecommendationRequested = ref(false)
const deviceRecommendationFilters = reactive({
  maxRecommendations: 5,
  userRequirement: ''
})
let deviceRecommendationRequestEpoch = 0

// ==== Specification Recommendation Logic ====
const specRecommendations = ref<SpecificationRecommendation[]>([])
const specRecommendationMessage = ref('')
const specRecommendationFilteredCount = ref(0)
const specRecommendationFilteredItems = ref<RecommendationFilteredItem[]>([])
/*
 * The specification recommender's adjusted items, which used to be discarded.
 *
 * Rule and device both track and render these; scenario does too. Spec did not — it never read the field, so a
 * server-completed value arrived, validated, and vanished. That matters because `BoardStorageController:535`
 * passes `requireAdjustments=false` for specs alone (rule and device pass `true`), which is precisely the case
 * where the recommender *may* adjust silently. The user would then apply values the system completed for them,
 * without the "review before applying" notice the other three panels show.
 */
const specRecommendationAdjustedItems = ref<RecommendationAdjustmentItem[]>([])
const specRecommendationRawCandidateCount = ref(0)
const specRecommendationInspectedCount = ref(0)
const specRecommendationTruncatedCount = ref(0)
const specRecommendationIsAppliedConfirmation = ref(false)
const appliedSpecRecommendations = ref<Set<number>>(new Set())
const applyingSpecRecommendations = ref<Set<number>>(new Set())
const showSpecRecommendationPanel = ref(false)
const specRecommendationAbortController = ref<AbortController | null>(null)
const specRecommendationRequestId = ref<string | null>(null)
const specRecommendationRequested = ref(false)
const specRecommendationFilters = reactive({
  maxRecommendations: 5,
  userRequirement: ''
})
let specRecommendationRequestEpoch = 0

// ==== Coupled Scenario Recommendation Logic ====
const showScenarioRecommendationPanel = ref(false)
const scenarioRecommendationAbortController = ref<AbortController | null>(null)
const scenarioRecommendationRequestId = ref<string | null>(null)
const scenarioRecommendationRequested = ref(false)

/**
 * One handle per recommendation panel, so the shared ownership / cancellation / recovery
 * machinery is told which panel to act on instead of receiving its refs one by one.
 */
const ruleRecommendationPanel: RecommendationRequestHandle = {
  kind: 'rule',
  requestId: ruleRecommendationRequestId,
  abortController: ruleRecommendationAbortController,
  running: isRecommendingRules,
  cancelledMessageKey: 'app.ruleRecommendationCancelled'
}
const deviceRecommendationPanel: RecommendationRequestHandle = {
  kind: 'device',
  requestId: deviceRecommendationRequestId,
  abortController: deviceRecommendationAbortController,
  running: isRecommendingDevices,
  cancelledMessageKey: 'app.deviceRecommendationCancelled'
}
const specRecommendationPanel: RecommendationRequestHandle = {
  kind: 'spec',
  requestId: specRecommendationRequestId,
  abortController: specRecommendationAbortController,
  running: isRecommendingSpecs,
  cancelledMessageKey: 'app.specificationRecommendationCancelled'
}
const scenarioRecommendationPanel: RecommendationRequestHandle = {
  kind: 'scenario',
  requestId: scenarioRecommendationRequestId,
  abortController: scenarioRecommendationAbortController,
  running: isRecommendingScenario,
  cancelledMessageKey: 'app.scenarioRecommendationCancelled'
}
const scenarioRecommendationMessage = ref('')
const scenarioRecommendationResult = ref<ScenarioRecommendationResult | null>(null)
const scenarioRecommendationFilters = reactive({
  minDevices: 2,
  minRules: 1,
  minSpecs: 1,
  maxDevices: 6,
  maxRules: 5,
  maxSpecs: 5,
  userRequirement: ''
})
let scenarioRecommendationRequestEpoch = 0
let scenarioRecommendationCriteriaVersion = 0
const scenarioRecommendationInput = computed(() => {
  const invalidFields: ScenarioRecommendationCountField[] = []
  const countLabels: Record<ScenarioRecommendationCountField, string> = {
    minDevices: t('app.minDevicesField'), minRules: t('app.minRulesField'), minSpecs: t('app.minSpecsField'),
    maxDevices: t('app.maxDevicesField'), maxRules: t('app.maxRulesField'), maxSpecs: t('app.maxSpecsField')
  }
  const rangeLabels = {
    devices: t('app.devicesTool'), rules: t('app.rulesTool'), specifications: t('app.specificationsTool')
  }
  const rangeFields: Record<keyof typeof rangeLabels, ScenarioRecommendationCountField[]> = {
    devices: ['minDevices', 'maxDevices'], rules: ['minRules', 'maxRules'], specifications: ['minSpecs', 'maxSpecs']
  }
  try {
    const request = buildScenarioRecommendationTargetRequest(
      { ...scenarioRecommendationFilters, language: locale.value },
      (value, field) => {
        try {
          return requireIntegerInRange(value, countLabels[field], 1, 10)
        } catch (error) {
          invalidFields.push(field)
          throw error
        }
      },
      field => {
        invalidFields.push(...rangeFields[field])
        return new Error(t('app.scenarioMinimumExceedsMaximum', { field: rangeLabels[field] }))
      }
    )
    return { request, error: '', invalidFields }
  } catch (error) {
    return { request: null, error: (error as Error).message, invalidFields }
  }
})
const recommendedScenarioScene = computed(() => scenarioRecommendationResult.value?.scene || null)

const prepareActiveRecommendationsForLogout = async (): Promise<InteractiveLogoutPreparation> => {
  const activeRequests = [
    ruleRecommendationPanel,
    deviceRecommendationPanel,
    specRecommendationPanel,
    scenarioRecommendationPanel
  ].flatMap(panel => {
    const requestId = panel.requestId.value
    const owner = requestId ? getRecommendationRequestOwner(requestId) : null
    return requestId ? [{ panel, requestId, owner }] : []
  })
  if (activeRequests.length === 0) return 'ready'

  const outcomes = await Promise.all(activeRequests.map(async entry => {
    if (!entry.owner) return 'outcome-unknown' as const
    ensureRecommendationStopRecovery(entry.panel, { showMessage: false })
    return prepareOwnedRecommendationForLogout({
      requestId: entry.requestId,
      authToken: entry.owner.authToken,
      cancel: cancelRecommendationAsOwner,
      readStatus: readRecommendationStatusAsOwner,
      waitBeforeRetry: waitForRecommendationCancellationRetry,
      shouldContinue: () => entry.panel.requestId.value === entry.requestId,
      hasTerminalEvidence: () => hasRecommendationTerminalEvidence(entry.requestId),
      onCancellationAccepted: () => acceptRecommendationCancellation(entry.panel.kind, entry.requestId),
      onStatusFinished: () => finishRecommendationStopRecovery(entry.panel.kind, entry.requestId),
      maxAttempts: 20
    })
  }))
  return outcomes.every(outcome => outcome === 'ready') ? 'ready' : 'outcome-unknown'
}

watch(
  () => [
    scenarioRecommendationFilters.minDevices,
    scenarioRecommendationFilters.minRules,
    scenarioRecommendationFilters.minSpecs,
    scenarioRecommendationFilters.maxDevices,
    scenarioRecommendationFilters.maxRules,
    scenarioRecommendationFilters.maxSpecs,
    scenarioRecommendationFilters.userRequirement
  ],
  () => {
    scenarioRecommendationCriteriaVersion += 1
    resetScenarioRecommendationResults()
  },
  { flush: 'sync' }
)

const fetchDeviceRecommendations = async () => {
  if (isRecommendationRequestActive(isRecommendingDevices.value, deviceRecommendationRequestId.value)) {
    deviceRecommendationRequestEpoch += 1
    await stopActiveRecommendation(deviceRecommendationPanel)
    return
  }
  if (isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return
  }
  if (!ensureBoardDataReady(['nodes', 'templates'])) return

  if (showDeviceRecommendationPanel.value) {
    if (!canOpenRecommendationPanel('device')) return
  } else if (!openDeviceRecommendationPanel()) {
    return
  }

  const requestOwner = captureRecommendationRequestOwner()
  if (!requestOwner) return
  
  isRecommendingDevices.value = true
  recommendationApplyFailures.device = {}
  deviceRecommendationRequested.value = true
  delete recommendationFailures.device
  deviceRecommendations.value = []
  deviceRecommendationMessage.value = ''
  deviceRecommendationFilteredCount.value = 0
  deviceRecommendationFilteredItems.value = []
  deviceRecommendationAdjustedItems.value = []
  deviceRecommendationRawCandidateCount.value = 0
  deviceRecommendationInspectedCount.value = 0
  deviceRecommendationTruncatedCount.value = 0
  deviceRecommendationIsAppliedConfirmation.value = false
  appliedDeviceRecommendations.value.clear()
  applyingDeviceRecommendations.value.clear()
  const requestEpoch = ++deviceRecommendationRequestEpoch
  const requestSceneGeneration = recommendationSceneGeneration
  const controller = new AbortController()
  const requestId = crypto.randomUUID()
  deviceRecommendationAbortController.value = controller
  deviceRecommendationRequestId.value = requestId
  recommendationRequestOwners.set(requestId, requestOwner)
  recommendationProgressRequestId.value = requestId
  let postTerminal = false
  let requestDispatched = false

  try {
    const validatedMaxRecommendations = validateRecommendationCount(deviceRecommendationFilters.maxRecommendations)
    requestDispatched = true
    const response = await boardApi.recommendRelatedDevices(
      {
        requestId,
        authToken: requestOwner.authToken,
        signal: controller.signal
      },
      validatedMaxRecommendations,
      locale.value,
      deviceRecommendationFilters.userRequirement
    )
    postTerminal = true
    if (requestEpoch !== deviceRecommendationRequestEpoch
      || requestSceneGeneration !== recommendationSceneGeneration) return
    deviceRecommendations.value = response.recommendations
    deviceRecommendationMessage.value = localizedRecommendationText(
      response.message,
      t('app.recommendationsFound', { count: response.count })
    )
    deviceRecommendationFilteredCount.value = response.filteredCount
    deviceRecommendationFilteredItems.value = response.filteredItems
    deviceRecommendationAdjustedItems.value = response.adjustedItems
    deviceRecommendationRawCandidateCount.value = response.rawCandidateCount
    deviceRecommendationInspectedCount.value = response.inspectedCount
    deviceRecommendationTruncatedCount.value = response.truncatedCount
  } catch (error: any) {
    if (error.name === 'CanceledError' || error.code === 'ERR_CANCELED') {
      return
    }
    if (requestDispatched && !postTerminal && isRecommendationPostOutcomeUnknown(error)) {
      beginUnknownRecommendationRecovery(deviceRecommendationPanel, requestId,
        requestEpoch === deviceRecommendationRequestEpoch && requestSceneGeneration === recommendationSceneGeneration ? error : undefined)
      return
    }
    postTerminal = true
    if (requestEpoch !== deviceRecommendationRequestEpoch
      || requestSceneGeneration !== recommendationSceneGeneration) return
    console.error('Failed to fetch device recommendations:', error)
    reportRecommendationFailure('device', error, t('app.failedToFetchDeviceRecommendations'))
  } finally {
    if (postTerminal) {
      settleRecommendationPost(deviceRecommendationPanel, requestId, controller)
    }
  }
}

// 关闭设备推荐面板
const closeDeviceRecommendationPanel = () => {
  deviceRecommendationRequestEpoch += 1
  void stopActiveRecommendation(deviceRecommendationPanel, { showMessage: false })
  showDeviceRecommendationPanel.value = false
  resetDeviceRecommendationResults()
}

// 获取规约推荐
const fetchSpecRecommendations = async () => {
  if (isRecommendationRequestActive(isRecommendingSpecs.value, specRecommendationRequestId.value)) {
    specRecommendationRequestEpoch += 1
    await stopActiveRecommendation(specRecommendationPanel)
    return
  }
  if (isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return
  }
  if (!ensureBoardDataReady(['nodes', 'templates', 'rules', 'specs'])) return

  if (showSpecRecommendationPanel.value) {
    if (!canOpenRecommendationPanel('spec')) return
  } else if (!openSpecRecommendationPanel()) {
    return
  }

  const requestOwner = captureRecommendationRequestOwner()
  if (!requestOwner) return

  isRecommendingSpecs.value = true
  recommendationApplyFailures.spec = {}
  specRecommendationRequested.value = true
  delete recommendationFailures.spec
  specRecommendations.value = []
  specRecommendationMessage.value = ''
  specRecommendationFilteredCount.value = 0
  specRecommendationFilteredItems.value = []
  specRecommendationRawCandidateCount.value = 0
  specRecommendationInspectedCount.value = 0
  specRecommendationTruncatedCount.value = 0
  specRecommendationAdjustedItems.value = []
  specRecommendationIsAppliedConfirmation.value = false
  appliedSpecRecommendations.value.clear()
  applyingSpecRecommendations.value.clear()
  const requestEpoch = ++specRecommendationRequestEpoch
  const requestSceneGeneration = recommendationSceneGeneration
  const controller = new AbortController()
  const requestId = crypto.randomUUID()
  specRecommendationAbortController.value = controller
  specRecommendationRequestId.value = requestId
  recommendationRequestOwners.set(requestId, requestOwner)
  recommendationProgressRequestId.value = requestId
  let postTerminal = false
  let requestDispatched = false

  try {
    const validatedMaxRecommendations = validateRecommendationCount(specRecommendationFilters.maxRecommendations)
    requestDispatched = true
    const response = await boardApi.recommendSpecifications(
      {
        requestId,
        authToken: requestOwner.authToken,
        signal: controller.signal
      },
      validatedMaxRecommendations,
      locale.value,
      specRecommendationFilters.userRequirement
    )
    postTerminal = true
    if (requestEpoch !== specRecommendationRequestEpoch
      || requestSceneGeneration !== recommendationSceneGeneration) return
    specRecommendations.value = response.recommendations
    specRecommendationMessage.value = localizedRecommendationText(
      response.message,
      t('app.recommendationsFound', { count: response.count })
    )
    specRecommendationFilteredCount.value = response.filteredCount
    specRecommendationFilteredItems.value = response.filteredItems
    specRecommendationRawCandidateCount.value = response.rawCandidateCount
    specRecommendationInspectedCount.value = response.inspectedCount
    specRecommendationTruncatedCount.value = response.truncatedCount
    specRecommendationAdjustedItems.value = response.adjustedItems || []
  } catch (error: any) {
    if (error.name === 'CanceledError' || error.code === 'ERR_CANCELED') {
      return
    }
    if (requestDispatched && !postTerminal && isRecommendationPostOutcomeUnknown(error)) {
      beginUnknownRecommendationRecovery(specRecommendationPanel, requestId,
        requestEpoch === specRecommendationRequestEpoch && requestSceneGeneration === recommendationSceneGeneration ? error : undefined)
      return
    }
    postTerminal = true
    if (requestEpoch !== specRecommendationRequestEpoch
      || requestSceneGeneration !== recommendationSceneGeneration) return
    console.error('Failed to fetch specification recommendations:', error)
    reportRecommendationFailure('spec', error, t('app.failedToFetchSpecificationRecommendations'))
  } finally {
    if (postTerminal) {
      settleRecommendationPost(specRecommendationPanel, requestId, controller)
    }
  }
}

// 关闭规约推荐面板
const closeSpecRecommendationPanel = () => {
  specRecommendationRequestEpoch += 1
  void stopActiveRecommendation(specRecommendationPanel, { showMessage: false })
  showSpecRecommendationPanel.value = false
  resetSpecRecommendationResults()
}

const fetchScenarioRecommendation = async () => {
  if (isRecommendationRequestActive(isRecommendingScenario.value, scenarioRecommendationRequestId.value)) {
    scenarioRecommendationRequestEpoch += 1
    await stopActiveRecommendation(scenarioRecommendationPanel)
    return
  }
  if (isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return
  }
  if (!ensureBoardDataReady()) return

  if (showScenarioRecommendationPanel.value) {
    if (!canOpenRecommendationPanel('scenario')) return
  } else if (!openScenarioRecommendationPanel()) {
    return
  }

  const request = scenarioRecommendationInput.value.request
  if (!request) return
  const requestOwner = captureRecommendationRequestOwner()
  if (!requestOwner) return

  isRecommendingScenario.value = true
  scenarioRecommendationRequested.value = true
  delete recommendationFailures.scenario
  scenarioRecommendationResult.value = null
  scenarioRecommendationMessage.value = ''
  const requestEpoch = ++scenarioRecommendationRequestEpoch
  const requestSceneGeneration = recommendationSceneGeneration
  const requestCriteriaVersion = scenarioRecommendationCriteriaVersion
  const controller = new AbortController()
  const requestId = crypto.randomUUID()
  scenarioRecommendationAbortController.value = controller
  scenarioRecommendationRequestId.value = requestId
  recommendationRequestOwners.set(requestId, requestOwner)
  recommendationProgressRequestId.value = requestId
  let postTerminal = false

  try {
    const response = await boardApi.recommendScenario(request, {
      requestId,
      authToken: requestOwner.authToken,
      signal: controller.signal
    })
    postTerminal = true
    if (requestEpoch !== scenarioRecommendationRequestEpoch
      || requestCriteriaVersion !== scenarioRecommendationCriteriaVersion
      || requestSceneGeneration !== recommendationSceneGeneration) return

    const rawScene = response.scene
    const scene = rawScene && Array.isArray(rawScene.devices) && rawScene.devices.length > 0
      ? normalizeSceneFile(rawScene)
      : null
    scenarioRecommendationResult.value = {
      message: localizedRecommendationText(
        response.message,
        t('app.recommendationsFound', { count: response.count })
      ),
      count: response.count,
      requestedCount: response.requestedCount,
      validatedCount: response.validatedCount,
      filteredCount: response.filteredCount,
      filteredItems: response.filteredItems,
      adjustedCount: response.adjustedCount,
      adjustedItems: response.adjustedItems,
      rawCandidateCount: response.rawCandidateCount,
      inspectedCount: response.inspectedCount,
      truncatedCount: response.truncatedCount,
      scenarioName: response.scenarioName,
      rationale: response.rationale,
      objectiveTargets: response.objectiveTargets,
      objectiveStatus: response.objectiveStatus,
      objectiveIssues: response.objectiveIssues,
      verificationReady: response.verificationReady,
      readinessIssues: response.readinessIssues,
      semanticWarnings: response.semanticWarnings,
      scene
    }
    scenarioRecommendationMessage.value = localizedRecommendationText(
      response.message,
      t('app.recommendationsFound', { count: response.count })
    )
  } catch (error: any) {
    if (error.name === 'CanceledError' || error.code === 'ERR_CANCELED') {
      return
    }
    if (!postTerminal && isRecommendationPostOutcomeUnknown(error)) {
      beginUnknownRecommendationRecovery(scenarioRecommendationPanel, requestId,
        requestEpoch === scenarioRecommendationRequestEpoch && requestSceneGeneration === recommendationSceneGeneration ? error : undefined)
      return
    }
    postTerminal = true
    if (requestEpoch !== scenarioRecommendationRequestEpoch
      || requestSceneGeneration !== recommendationSceneGeneration) return
    console.error('Failed to fetch scenario recommendation:', error)
    reportRecommendationFailure('scenario', error, t('app.failedToFetchScenarioRecommendation'))
  } finally {
    if (postTerminal) {
      settleRecommendationPost(scenarioRecommendationPanel, requestId, controller)
    }
  }
}

const closeScenarioRecommendationPanel = () => {
  scenarioRecommendationRequestEpoch += 1
  void stopActiveRecommendation(scenarioRecommendationPanel, { showMessage: false })
  showScenarioRecommendationPanel.value = false
  resetScenarioRecommendationResults()
}

const keepAppliedRecommendationAdjustments = (
  items: RecommendationAdjustmentItem[],
  appliedIndex: number
): RecommendationAdjustmentItem[] => items
  .filter(item => item.index === undefined || item.index === appliedIndex + 1)
  .map(item => item.index === undefined ? item : { ...item, index: 1 })

const preserveAppliedRecommendationAfterSceneChange = (
  kind: 'rule' | 'device' | 'spec',
  appliedIndex: number
) => {
  recommendationApplyFailures[kind] = {}
  if (kind === 'rule') {
    const appliedRecommendation = ruleRecommendations.value[appliedIndex]
    if (!appliedRecommendation) {
      invalidateRecommendationsForSceneChange({ notify: true })
      return
    }
    const hadOtherCandidates = ruleRecommendations.value.length > 1
    recommendationSceneGeneration += 1
    closeDeviceRecommendationPanel()
    closeSpecRecommendationPanel()
    closeScenarioRecommendationPanel()
    ruleRecommendations.value = [appliedRecommendation]
    ruleRecommendationMessage.value = hadOtherCandidates ? t('app.appliedRecommendationOnlyNotice') : ''
    ruleRecommendationFilteredCount.value = 0
    ruleRecommendationFilteredItems.value = []
    ruleRecommendationAdjustedItems.value = keepAppliedRecommendationAdjustments(
      ruleRecommendationAdjustedItems.value,
      appliedIndex
    )
    ruleRecommendationRawCandidateCount.value = 0
    ruleRecommendationInspectedCount.value = 0
    ruleRecommendationTruncatedCount.value = 0
    ruleRecommendationIsAppliedConfirmation.value = true
    appliedRuleRecommendations.value = new Set([0])
    applyingRuleRecommendations.value = new Set()
    return
  }
  if (kind === 'device') {
    const appliedRecommendation = deviceRecommendations.value[appliedIndex]
    if (!appliedRecommendation) {
      invalidateRecommendationsForSceneChange({ notify: true })
      return
    }
    const hadOtherCandidates = deviceRecommendations.value.length > 1
    recommendationSceneGeneration += 1
    closeRecommendationPanel()
    closeSpecRecommendationPanel()
    closeScenarioRecommendationPanel()
    deviceRecommendations.value = [appliedRecommendation]
    deviceRecommendationMessage.value = hadOtherCandidates ? t('app.appliedRecommendationOnlyNotice') : ''
    deviceRecommendationFilteredCount.value = 0
    deviceRecommendationFilteredItems.value = []
    deviceRecommendationAdjustedItems.value = keepAppliedRecommendationAdjustments(
      deviceRecommendationAdjustedItems.value,
      appliedIndex
    )
    deviceRecommendationRawCandidateCount.value = 0
    deviceRecommendationInspectedCount.value = 0
    deviceRecommendationTruncatedCount.value = 0
    deviceRecommendationIsAppliedConfirmation.value = true
    appliedDeviceRecommendations.value = new Set([0])
    applyingDeviceRecommendations.value = new Set()
    return
  }

  const appliedRecommendation = specRecommendations.value[appliedIndex]
  if (!appliedRecommendation) {
    invalidateRecommendationsForSceneChange({ notify: true })
    return
  }
  const hadOtherCandidates = specRecommendations.value.length > 1
  recommendationSceneGeneration += 1
  closeRecommendationPanel()
  closeDeviceRecommendationPanel()
  closeScenarioRecommendationPanel()
  specRecommendations.value = [appliedRecommendation]
  specRecommendationMessage.value = hadOtherCandidates ? t('app.appliedRecommendationOnlyNotice') : ''
  specRecommendationFilteredCount.value = 0
  specRecommendationFilteredItems.value = []
  specRecommendationRawCandidateCount.value = 0
  specRecommendationInspectedCount.value = 0
  specRecommendationTruncatedCount.value = 0
  specRecommendationAdjustedItems.value = []
  specRecommendationIsAppliedConfirmation.value = true
  appliedSpecRecommendations.value = new Set([0])
  applyingSpecRecommendations.value = new Set()
}

const invalidateRecommendationsForSceneChange = ({ notify = false }: { notify?: boolean } = {}) => {
  const hadRecommendationContext = isAnyRecommendationPanelVisible() || isAnyRecommendationRunning()
  recommendationSceneGeneration += 1
  // Close every panel and advance each request epoch. stopActiveRecommendation
  // keeps the transport alive until the server acknowledges cancellation; the
  // generation/epoch checks make the UI safe even if that acknowledgement races
  // with the scene change.
  closeRecommendationPanel()
  closeDeviceRecommendationPanel()
  closeSpecRecommendationPanel()
  closeScenarioRecommendationPanel()
  if (notify && hadRecommendationContext) {
    notifyInfo(t('app.recommendationsInvalidatedByBoardRefresh'))
  }
}

const invalidateForFullSceneReplacement = () => {
  boardSceneGeneration += 1
  // Scene import/clear opt out of fingerprint tracking, so mark staleness explicitly here.
  markVerificationResultStale()
  invalidateRecommendationsForSceneChange()
  notifyUndoJournalCleared()
}

/**
 * Whole-scene replacement/clear can also replace template snapshots, so the server drops the
 * journal. The journal is authoritative; re-read it so the UI cannot offer an unreachable undo.
 */
const notifyUndoJournalCleared = () => {
  // The server discarded the whole journal, so nothing is reversible — applied locally at once
  // because the re-read below is fire-and-forget, and until it lands the button would still offer an
  // undo that can only answer "nothing to undo".
  syncBoardUndoAvailability({ canUndo: false, canRedo: false })
  void loadBoardUndoAvailability()
}

const applyRecommendedScenario = async () => {
  if (!ensurePlaybackClosedForMutation()) return
  const scene = recommendedScenarioScene.value
  if (!scene) {
    notifyBlocked(t('app.noScenarioToApply'))
    return
  }
  const requestEpoch = scenarioRecommendationRequestEpoch
  const sceneGeneration = recommendationSceneGeneration
  const isRecommendationCurrent = () =>
    requestEpoch === scenarioRecommendationRequestEpoch
    && sceneGeneration === recommendationSceneGeneration
    && recommendedScenarioScene.value === scene
  const imported = await importScene(scene, isRecommendationCurrent)
  if (imported) {
    closeScenarioRecommendationPanel()
  }
}

const exportRecommendedScenario = () => {
  const scene = recommendedScenarioScene.value
  if (!scene) {
    notifyBlocked(t('app.noScenarioToApply'))
    return
  }
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-')
  const portableScene = canonicalizeSceneFile(scene)
  downloadJsonFile(`iot-verify-ai-scenario-${timestamp}.json`, portableScene)
  notifySuccess(t('app.sceneExportStarted', {
    devices: scene.devices.length,
    variables: scene.environmentVariables.length,
    rules: scene.rules.length,
    specs: scene.specs.length
  }))
}

const formatScenarioDeviceLabel = (deviceId: string): string => {
  const device = recommendedScenarioScene.value?.devices.find(candidate => candidate.id === deviceId)
  return device?.label || t('app.unknownModelItem')
}

const scenarioDeviceById = (deviceId: string): DeviceNode | undefined =>
  recommendedScenarioScene.value?.devices.find(candidate => candidate.id === deviceId)

const formatScenarioDeviceModelToken = (device: DeviceNode, value: unknown): string =>
  formatTemplateModelToken(findTemplateByAnyName(device.templateName), value)

const formatScenarioRuleModelToken = (deviceId: string, value: unknown): string => {
  const device = scenarioDeviceById(deviceId)
  return device ? formatScenarioDeviceModelToken(device, value) : String(value ?? '')
}

const scenarioBundledEnvironmentNames = computed(() =>
  getBundledEnvironmentNames(recommendedScenarioScene.value?.devices || [])
)

const formatScenarioEnvironmentModelToken = (name: string, value: unknown): string =>
  scenarioBundledEnvironmentNames.value.includes(name)
    ? formatBundledModelToken(value)
    : String(value ?? '')

const scenarioDeviceTemplate = (device: DeviceNode): DeviceTemplate | undefined =>
  recommendedScenarioScene.value?.templates.find(candidate => {
    const candidateName = candidate.manifest?.Name || candidate.name
    return candidateName?.trim().toLocaleLowerCase() === device.templateName.trim().toLocaleLowerCase()
  })

const scenarioDeviceHasStateMachine = (device: DeviceNode): boolean => {
  const template = scenarioDeviceTemplate(device)
  return Boolean(template?.manifest?.Modes?.length && template.manifest.WorkingStates?.length)
}

const scenarioDeviceStateTrust = (device: DeviceNode): string =>
  device.currentStateTrust
  || findTemplateStateTrust(scenarioDeviceTemplate(device), device.state || '')
  || 'trusted'

const scenarioDeviceStatePrivacy = (device: DeviceNode): string =>
  device.currentStatePrivacy
  || findTemplateStatePrivacy(scenarioDeviceTemplate(device), device.state || '')
  || 'public'

const scenarioDeviceVariableTrust = (
  device: DeviceNode,
  variable: NonNullable<DeviceNode['variables']>[number]
): string => variable.trust
  || getTemplateLocalVariables(scenarioDeviceTemplate(device))
    .find(candidate => candidate.Name === variable.name)?.Trust
  || 'trusted'

const formatRelationForDisplay = (relation: unknown): string => {
  const raw = String(relation ?? '').trim()
  const normalized = raw.toLowerCase().replace(/_/g, ' ')
  if (normalized === 'in') return t('app.relationIn')
  if (normalized === 'not in') return t('app.relationNotIn')
  return raw
}

const formatScenarioRuleSource = (source: RuleForm['sources'][number]): string => {
  const device = formatScenarioDeviceLabel(source.fromId)
  const attribute = formatScenarioRuleModelToken(source.fromId, source.fromApi)
  if (source.itemType === 'api') return `${device}.${attribute}`
  const value = formatScenarioRuleModelToken(source.fromId, source.value)
  return `${device}.${attribute} ${formatRelationForDisplay(source.relation || '=')} ${value}`.trim()
}

const formatScenarioRuleAction = (rule: RuleForm): string => {
  const action = `${formatScenarioDeviceLabel(rule.toId)}.${formatScenarioRuleModelToken(rule.toId, rule.toApi)}`
  if (!rule.contentDevice || !rule.content) return action
  return `${action} · ${t('app.copyFrom')} ${formatScenarioDeviceLabel(rule.contentDevice)}.${formatScenarioRuleModelToken(rule.contentDevice, rule.content)}`
}

const formatScenarioSpecFormula = (spec: Specification): string =>
  buildSpecFormula(spec, {
    nodes: recommendedScenarioScene.value?.devices || []
  })

const recommendedSpecTemplateLabel = (templateId: unknown): string => {
  const id = String(templateId ?? '')
  const detail = specTemplateDetails.find(template => template.id === id)
  return detail?.labelKey ? t(detail.labelKey) : detail?.label || id || t('app.customSpecification')
}

// 应用规约推荐 - 将推荐的规约添加到画布
const applySpecRecommendation = async (recommendation: SpecificationRecommendation, index: number) => {
  if (!ensurePlaybackClosedForMutation()) return
  if (!ensureBoardDataReady(['nodes', 'templates', 'specs'])) return
  if (appliedSpecRecommendations.value.has(index)) {
    notifyBlocked(t('app.recommendationAlreadyApplied'))
    return
  }
  if (applyingSpecRecommendations.value.has(index) || recommendationApplyFailures.spec[index]?.outcomeUnknown) return
  const recommendationEpoch = specRecommendationRequestEpoch
  const requestSceneGeneration = recommendationSceneGeneration
  const requestToken = getToken()
  const applyAuthScopeEpoch = boardAuthScopeEpoch
  const isApplyAccountCurrent = () => isCurrentBoardAuthScope(applyAuthScopeEpoch) && isCurrentSessionToken(requestToken)
  const isApplyCandidateCurrent = () => isApplyAccountCurrent()
    && recommendationEpoch === specRecommendationRequestEpoch && requestSceneGeneration === recommendationSceneGeneration
  delete recommendationApplyFailures.spec[index]
  let recommendationConfirmedApplied = false

  // Reject an invalid recommendation before issuing the targeted create request.
  const rawTemplateId = recommendation.templateId
  if (typeof rawTemplateId !== 'string' || !/^[1-7]$/.test(rawTemplateId)) {
    notifyBlocked(t('app.invalidRecommendedTemplateId', { templateId: rawTemplateId ?? '' }))
    return
  }
  const templateId = rawTemplateId as SpecTemplateId
  if (!ensureBoardItemCapacity(
    'specifications', specifications.value.length, 1, REQUEST_LIMITS.specifications
  )) return
  const conditionIdPrefix = `rec_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  let aConditions: SpecCondition[]
  let ifConditions: SpecCondition[]
  let thenConditions: SpecCondition[]
  try {
    aConditions = materializeSpecificationRecommendationConditions(
      recommendation.aConditions, 'a', index => `${conditionIdPrefix}_a_${index}`)
    ifConditions = materializeSpecificationRecommendationConditions(
      recommendation.ifConditions, 'if', index => `${conditionIdPrefix}_if_${index}`)
    thenConditions = materializeSpecificationRecommendationConditions(
      recommendation.thenConditions, 'then', index => `${conditionIdPrefix}_then_${index}`)
    if (![aConditions, ifConditions, thenConditions].every(conditions =>
      ensureNestedItemCapacity(
        t('app.specificationConditions'), conditions.length,
        REQUEST_LIMITS.specificationConditions
      ))) return
  } catch (error) {
    const field = error instanceof RecommendationCandidateError ? error.field : t('app.unknownModelItem')
    notifyBlocked(t('app.recommendationInvalidFieldNoChange', { field }))
    return
  }

  // 构建规约数据
  const templateLabel = recommendedSpecTemplateLabel(templateId)
  const newSpec = {
    id: 'spec_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9),
    templateId,
    templateLabel,
    aConditions,
    ifConditions,
    thenConditions,
    devices: buildSpecDeviceRefsFromConditions([...aConditions, ...ifConditions, ...thenConditions], nodes.value),
    formula: buildSpecFormula({
      templateId,
      templateLabel,
      aConditions,
      ifConditions,
      thenConditions
    }, {
      nodes: nodes.value
    })
  }

  if (specificationExists(newSpec)) {
    notifyBlocked(t('app.specDuplicate'))
    return
  }

  // 获取现有规约
  applyingSpecRecommendations.value.add(index)
  try {
    if (requestSceneGeneration !== recommendationSceneGeneration
      || isSceneReplacementInProgress.value) return
    await enqueueBoardMutation(async () => {
      if (recommendationEpoch !== specRecommendationRequestEpoch
        || requestSceneGeneration !== recommendationSceneGeneration
        || !isBoardDataReady.value
        || isSceneReplacementInProgress.value) return
      try {
        const mutation = await boardApi.addSpec(newSpec)
        if (!isApplyAccountCurrent()) return
        commitSemanticScene({ specs: mutation.currentItems, availability: mutation })
        recommendationConfirmedApplied = true
        if (isApplyCandidateCurrent()) {
          appliedSpecRecommendations.value.add(index)
        }
      } catch (error: any) {
        if (!isApplyAccountCurrent()) return
        console.error('Failed to save specification:', error)
        let refreshed = false
        if (!isDefinitiveMutationRejection(error)) {
          refreshed = await refreshSpecifications(requestToken)
          if (!isApplyAccountCurrent()) return
          await reloadUndoAvailability()
          if (!isApplyAccountCurrent()) return
          if (refreshed && specifications.value.some(spec => isSameSpecification(spec, newSpec))) {
            recommendationConfirmedApplied = true
            if (isApplyCandidateCurrent()) {
              appliedSpecRecommendations.value.add(index)
              notifyBlocked(t('app.specCreateOutcomeRefreshed'))
            }
            return
          }
        }
        if (!isApplyCandidateCurrent()) return
        if (error?.response?.data?.data?.reasonCode === 'DUPLICATE_SPECIFICATION') {
          notifyBlocked(t('app.specDuplicate'))
          return
        }
        const outcomeUnknown = !isDefinitiveMutationRejection(error)
        reportRecommendationApplyFailure('spec', index, error, t(outcomeUnknown
          ? refreshed ? 'app.recommendationApplyOutcomeRefreshed' : 'app.recommendationApplyOutcomeUnknown'
          : 'app.failedToSaveSpecification'), outcomeUnknown)
      }
    }, {
      onSemanticChange: () => handleRecommendationApplySceneChange(
        recommendationConfirmedApplied,
        () => preserveAppliedRecommendationAfterSceneChange('spec', index),
        () => invalidateRecommendationsForSceneChange({ notify: true })
      )
    })
  } finally {
    if (recommendationEpoch === specRecommendationRequestEpoch) {
      applyingSpecRecommendations.value.delete(index)
    }
  }
}

const recommendedDeviceLabel = (recommendation: DeviceRecommendation): string | null =>
  typeof recommendation?.suggestedLabel === 'string' && recommendation.suggestedLabel.trim()
    ? recommendation.suggestedLabel.trim()
    : null

type RecommendedDeviceRuntimeResult = {
  runtime?: DeviceRuntimeConfig
  error?: string
}

const buildRecommendedDeviceRuntime = (recommendation: DeviceRecommendation): RecommendedDeviceRuntimeResult => {
  const runtime: DeviceRuntimeConfig = {}
  const invalidField = (field: string): RecommendedDeviceRuntimeResult => ({
    error: t('app.deviceRecommendationInvalidRuntime', { field })
  })
  const hasField = (field: string) => Object.prototype.hasOwnProperty.call(recommendation || {}, field)

  let state = ''
  if (hasField('initialState')) {
    if (typeof recommendation.initialState !== 'string' || !recommendation.initialState.trim()) {
      return invalidField('initialState')
    }
    state = recommendation.initialState.trim()
  }
  if (state) {
    runtime.state = state
  }
  let trust = ''
  if (hasField('currentStateTrust')) {
    if (typeof recommendation.currentStateTrust !== 'string') return invalidField('currentStateTrust')
    trust = recommendation.currentStateTrust.trim()
    if (!TRUST_OPTIONS.includes(trust as any)) return invalidField('currentStateTrust')
  }
  if (trust) {
    runtime.currentStateTrust = trust as DeviceRuntimeConfig['currentStateTrust']
  }
  let statePrivacy = ''
  if (hasField('currentStatePrivacy')) {
    if (typeof recommendation.currentStatePrivacy !== 'string') return invalidField('currentStatePrivacy')
    statePrivacy = recommendation.currentStatePrivacy.trim()
    if (!PRIVACY_OPTIONS.includes(statePrivacy as any)) return invalidField('currentStatePrivacy')
  }
  if (statePrivacy) {
    runtime.currentStatePrivacy = statePrivacy as DeviceRuntimeConfig['currentStatePrivacy']
  }

  if (hasField('initialVariables') && !Array.isArray(recommendation.initialVariables)) {
    return invalidField('initialVariables')
  }
  const variables: NonNullable<DeviceRuntimeConfig['variables']> = []
  const variableNames = new Set<string>()
  for (const [index, variable] of (recommendation?.initialVariables || []).entries()) {
    if (!variable || typeof variable !== 'object' || Array.isArray(variable)) {
      return invalidField(`initialVariables[${index}]`)
    }
    if (typeof variable.name !== 'string' || typeof variable.value !== 'string') {
      return invalidField(`initialVariables[${index}]`)
    }
    const name = variable.name.trim()
    const value = variable.value.trim()
    if (!name || !value || variableNames.has(name)) return invalidField(`initialVariables[${index}]`)
    variableNames.add(name)
    let variableTrust = ''
    if (Object.prototype.hasOwnProperty.call(variable, 'trust')) {
      if (typeof variable.trust !== 'string') return invalidField(`initialVariables[${index}].trust`)
      variableTrust = variable.trust.trim()
      if (!TRUST_OPTIONS.includes(variableTrust as any)) return invalidField(`initialVariables[${index}].trust`)
    }
    variables.push({
      name,
      value,
      ...(variableTrust ? { trust: variableTrust as any } : {})
    })
  }
  if (variables.length) {
    runtime.variables = variables
  }

  if (hasField('initialPrivacies') && !Array.isArray(recommendation.initialPrivacies)) {
    return invalidField('initialPrivacies')
  }
  const privacies: NonNullable<DeviceRuntimeConfig['privacies']> = []
  const privacyNames = new Set<string>()
  for (const [index, privacy] of (recommendation?.initialPrivacies || []).entries()) {
    if (!privacy || typeof privacy !== 'object' || Array.isArray(privacy)) {
      return invalidField(`initialPrivacies[${index}]`)
    }
    if (typeof privacy.name !== 'string' || typeof privacy.privacy !== 'string') {
      return invalidField(`initialPrivacies[${index}]`)
    }
    const name = privacy.name.trim()
    const value = privacy.privacy.trim()
    if (!name || privacyNames.has(name) || !PRIVACY_OPTIONS.includes(value as any)) {
      return invalidField(`initialPrivacies[${index}]`)
    }
    privacyNames.add(name)
    privacies.push({ name, privacy: value as any })
  }
  if (privacies.length) {
    runtime.privacies = privacies
  }

  return { runtime: Object.keys(runtime).length > 0 ? runtime : undefined }
}

// 应用设备推荐 - 将推荐的设备添加到画布
const applyDeviceRecommendation = async (recommendation: DeviceRecommendation, index: number) => {
  if (!ensurePlaybackClosedForMutation()) return
  if (appliedDeviceRecommendations.value.has(index)) {
    notifyBlocked(t('app.recommendationAlreadyApplied'))
    return
  }
  if (applyingDeviceRecommendations.value.has(index) || recommendationApplyFailures.device[index]?.outcomeUnknown) return
  const recommendationEpoch = deviceRecommendationRequestEpoch
  const requestSceneGeneration = recommendationSceneGeneration
  const requestToken = getToken()
  const applyAuthScopeEpoch = boardAuthScopeEpoch
  const isApplyAccountCurrent = () => isCurrentBoardAuthScope(applyAuthScopeEpoch) && isCurrentSessionToken(requestToken)
  delete recommendationApplyFailures.device[index]
  let recommendationConfirmedApplied = false
  const isApplyCandidateCurrent = () =>
    isApplyAccountCurrent() && recommendationEpoch === deviceRecommendationRequestEpoch
    && requestSceneGeneration === recommendationSceneGeneration
  const isRecommendationCurrent = () => isApplyCandidateCurrent()
    && isBoardDataReady.value
    && !isSceneReplacementInProgress.value

  const templateName = typeof recommendation.templateName === 'string'
    ? recommendation.templateName.trim()
    : ''
  if (!templateName) {
    notifyError(t('app.recommendationInvalidFieldNoChange', { field: 'templateName' }))
    return
  }

  const template = findTemplateByAnyName(templateName)
  
  if (!template) {
    notifyError(t('app.templateNotFoundWithName', { name: templateName }))
    return
  }
  
  const center = getVisibleCanvasCenterWorld()
  if (!center) return

  const label = recommendedDeviceLabel(recommendation)
  if (!label) {
    notifyError(t('app.recommendationInvalidFieldNoChange', { field: 'suggestedLabel' }))
    return
  }
  const runtimeResult = buildRecommendedDeviceRuntime(recommendation)
  if (runtimeResult.error) {
    notifyBlocked(runtimeResult.error)
    return
  }
  const runtime = runtimeResult.runtime
  const runtimeError = validateDeviceRuntimeConfig(template, runtime, t, { variableScope: 'local' })
  if (runtimeError) {
    notifyBlocked(runtimeError)
    return
  }
  const availableLabel = getUniqueLabel(label, getVisibleDeviceNodes())
  let confirmedLabel = label
  if (availableLabel !== label) {
    if (!await confirmChoice({
      title: t('app.deviceRecommendationNameConflictTitle'),
      message: t('app.deviceRecommendationNameConflictConfirm', { from: label, to: availableLabel })
    })) return
    confirmedLabel = availableLabel
  }
  if (!isRecommendationCurrent()) return
  
  applyingDeviceRecommendations.value.add(index)
  try {
    const outcome = await createDeviceInstanceAt(
      template,
      center,
      confirmedLabel,
      runtime,
      {
        admissionGuard: isRecommendationCurrent,
        onConfirmedCreate: () => { recommendationConfirmedApplied = true },
        onFailure: (error, refreshed) => {
          if (!isApplyCandidateCurrent()) return
          const outcomeUnknown = !isDefinitiveMutationRejection(error)
          reportRecommendationApplyFailure('device', index, error, t(outcomeUnknown
            ? refreshed ? 'app.recommendationApplyOutcomeRefreshed' : 'app.recommendationApplyOutcomeUnknown'
            : 'app.saveNodesFailed'), outcomeUnknown)
        },
        onSemanticChange: () => handleRecommendationApplySceneChange(
          recommendationConfirmedApplied,
          () => preserveAppliedRecommendationAfterSceneChange('device', index),
          () => invalidateRecommendationsForSceneChange({ notify: true })
        )
      }
    )
    if (!isApplyAccountCurrent()) return
    if (isApplyCandidateCurrent()) {
      appliedDeviceRecommendations.value.add(index)
    }
    reportEnvironmentChanges(outcome.environmentChanges)
    if (!outcome.responseConfirmed && isApplyCandidateCurrent()) {
      notifyBlocked(t('app.deviceCreateOutcomeRefreshed', { name: outcome.device.label }))
    }
  } catch {
    // The creating helper reports the failure to this candidate's owner.
  } finally {
    if (recommendationEpoch === deviceRecommendationRequestEpoch) {
      applyingDeviceRecommendations.value.delete(index)
    }
  }
}

// ==== Simulation Logic ====
const isSimulating = ref(false)
const simulationError = ref<RunFailure | null>(null)
// Result of the last successful simulation, kept so its logs / NuSMV diagnostics stay reachable while
// the timeline is open. The result dialog only auto-opens on error; on success we go straight to the
// timeline (by design) and let the user open the logs on demand via openSimulationLogs().
const lastSimulationResult = ref<SimulationResultView | null>(null)
// Only notification handles live here; each notice owns its local result through its action closure.
const unsavedRunNotifications = new Set<() => void>()
onBeforeUnmount(() => {
  unsavedRunNotifications.forEach(close => close())
  unsavedRunNotifications.clear()
})

/**
 * Make a completed run *the* current trajectory: the one every simulation surface describes.
 *
 * The pairing is the point. `lastSimulationResult` is the manifest the replay bar and the details dialog
 * read, and the stale flag qualifies that same manifest, so the two must be written together or the
 * banner ends up describing a different run than the header — which is what happened when each async
 * path assigned them inline. Taking the submission's scene-change count as an argument keeps the
 * comparison at the call site, where the run's own baseline is known.
 *
 * Callers must only adopt a run they are about to show or that no surface will show; a run arriving
 * behind an open replay is deferred, not adopted.
 */
const adoptSimulationRunResult = (
  result: SimulationResultView,
  submissionSceneChanges: number
) => {
  lastSimulationResult.value = result
  simulationResultStale.value = semanticSceneChangeCount !== submissionSceneChanges
}

/** Adopt an arriving verdict and its saved run together, without refetching the result just received. */
const adoptVerificationRunResult = (result: VerificationResult, submissionSceneChanges: number) => {
  // A history read started earlier must not replace this verdict after its URL has changed.
  historyDetailRequests.invalidate()
  deepLinkLoadRequests.invalidate()
  verificationResult.value = result
  verificationResultStale.value = semanticSceneChangeCount !== submissionSceneChanges
  if (result.historyPersistence.status === 'SAVED') {
    staleDeepLink.value = false
    void navigateToRunTarget({ kind: 'verification', runId: result.historyPersistence.runId }, 'push')
  } else {
    clearRunDeepLink()
  }
}

// Simulation form state (moved from ControlCenter)
interface AttackRunForm {
  isAttack: boolean
  attackMode: AttackScenarioMode
  attackBudget: number
  selectedAttackPointKeys: string[]
}

const simulationForm = reactive<AttackRunForm & {
  steps: number
  enablePrivacy: boolean
  isAsync: boolean
  saveToHistory: boolean
}>({
  steps: 10,
  isAttack: false,
  attackMode: 'NONE',
  attackBudget: 1,
  selectedAttackPointKeys: [],
  enablePrivacy: false,
  isAsync: true,
  saveToHistory: true
})

const SIMULATION_STEPS_MIN = 1
const SIMULATION_STEPS_MAX = 100

const normalizeSimulationStepsControlValue = (value: unknown): number => {
  const numeric = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(numeric)) return 10
  return Math.min(SIMULATION_STEPS_MAX, Math.max(SIMULATION_STEPS_MIN, Math.round(numeric)))
}

const setSimulationSteps = (value: unknown) => {
  simulationForm.steps = normalizeSimulationStepsControlValue(value)
}

const adjustSimulationSteps = (delta: number) => {
  setSimulationSteps(normalizeSimulationStepsControlValue(simulationForm.steps) + delta)
}

const commitSimulationStepsInput = (event: Event) => {
  const input = event.currentTarget as HTMLInputElement
  setSimulationSteps(input.value)
  input.value = String(simulationForm.steps)
}

// Verification form state (similar to simulation)
const verificationForm = reactive<AttackRunForm & {
  enablePrivacy: boolean
  isAsync: boolean
}>({
  isAttack: false,
  attackMode: 'NONE',
  attackBudget: 1,
  selectedAttackPointKeys: [],
  enablePrivacy: false,
  isAsync: true
})

// The bounded counterexample explorer is intentionally background-only. The first-level controls describe the
// search budget; population/seed remain advanced inputs so the formal run controls
// stay visually distinct from heuristic exploration.
const fuzzingForm = reactive<{
  explorationMode: FuzzingExplorationMode
  targetSelectionMode: 'ALL' | 'EXPLICIT'
  targetSpecIds: string[]
  maxIterations: number
  pathLength: number
  populationSize: number
  seed: number | null
}>({
  explorationMode: 'BOARD_SNAPSHOT',
  targetSelectionMode: 'ALL',
  targetSpecIds: [],
  // 200 x 20 x 10 = 40,000, matching FuzzRequestDto's default. At 500 this product was 100,000, which the
  // away-mode example scene refused outright and the rest only barely cleared — the shipped default could
  // not run the scene the demo guide is written around. Named constants so the form and the reset path
  // cannot drift apart from each other.
  maxIterations: FUZZ_DEFAULT_MAX_ITERATIONS,
  pathLength: FUZZ_DEFAULT_PATH_LENGTH,
  populationSize: FUZZ_DEFAULT_POPULATION_SIZE,
  seed: null
})
const fuzzingWatchedTask = ref<FuzzingTaskSummary | null>(null)

const knownFuzzEligibleSpecifications = computed(() =>
  specifications.value.filter(isKnownFuzzingSpecificationSupported))

const normalizedFuzzTargetSpecIds = computed(() => {
  const eligibleIds = new Set(knownFuzzEligibleSpecifications.value.map(spec => spec.id))
  return fuzzingForm.targetSpecIds.filter(id => eligibleIds.has(id))
})

const invalidFuzzTargetSpecIds = computed(() => {
  const eligibleIds = new Set(knownFuzzEligibleSpecifications.value.map(spec => spec.id))
  return fuzzingForm.targetSelectionMode === 'EXPLICIT'
    ? fuzzingForm.targetSpecIds.filter(id => !eligibleIds.has(id))
    : []
})

const availableFuzzTargetCount = computed(() => knownFuzzEligibleSpecifications.value.length)
const fuzzingPreviewPrerequisitesReady = computed(() =>
  isBoardDataReady.value
  && nodes.value.length > 0
  && availableFuzzTargetCount.value > 0)

const fuzzingContentCommandUnsupported = computed(() => rules.value.some(rule =>
  Boolean(rule.contentDevice?.trim()) || Boolean(rule.content?.trim())
))

const fuzzingLocalConfigurationError = computed(() => {
  if (invalidFuzzTargetSpecIds.value.length > 0) {
    return t('app.fuzzTargetSelectionChanged', { count: invalidFuzzTargetSpecIds.value.length })
  }
  const issue = getFuzzingConfigurationIssue({
    ...fuzzingForm,
    targetSpecIds: normalizedFuzzTargetSpecIds.value
  }, availableFuzzTargetCount.value)
  if (!issue) return ''
  if (issue.code === 'INVALID_INTEGER_FIELD') {
    const labels = {
      maxIterations: t('app.fuzzMaxIterations'),
      pathLength: t('app.fuzzPathLength'),
      populationSize: t('app.fuzzPopulationSize'),
      seed: t('app.fuzzSeed')
    }
    return t('app.fuzzIntegerFieldRange', {
      field: labels[issue.field],
      minimum: issue.minimum.toLocaleString(),
      maximum: issue.maximum.toLocaleString()
    })
  }
  if (issue.code === 'TARGET_SELECTION_REQUIRED') {
    return t('app.fuzzTargetSelectionRequired', {
      count: issue.availableSpecCount,
      limit: issue.limit
    })
  }
  if (issue.code === 'TOO_MANY_TARGETS') {
    return t('app.fuzzTooManyTargetSpecifications', { limit: issue.limit })
  }
  return ''
})

const ATTACK_BUDGET_HARD_MAX = ATTACK_POINT_HARD_MAX
const boardAttackSurface = computed(() => analyzeBoardAttackSurface(
  nodes.value,
  rules.value,
  resolveTemplateForNode
))
const attackBudgetMax = computed(() => Math.min(
  ATTACK_BUDGET_HARD_MAX,
  boardAttackSurface.value.totalPointCount
))
const attackSurfacePointCount = computed(() => boardAttackSurface.value.totalPointCount)
const attackBudgetIsCapped = computed(() => attackSurfacePointCount.value > ATTACK_BUDGET_HARD_MAX)

const hasModeledAttackEffect = computed(() => boardAttackSurface.value.totalPointCount > 0)

const attackConfigurationError = (
  form: AttackRunForm,
  allowExhaustive: boolean
): string => {
  const issue = getAttackScenarioIssue(
    form.attackMode,
    form.attackBudget,
    form.selectedAttackPointKeys,
    boardAttackSurface.value,
    allowExhaustive
  )
  if (issue === 'NO_MODELED_EFFECT') return t('app.attackNoModeledEffect')
  if (issue === 'INVALID_BUDGET') {
    return t('app.attackBudgetSelectionInvalid', {
      selected: String(form.attackBudget),
      limit: attackBudgetMax.value
    })
  }
  if (issue === 'EXPLICIT_POINTS_REQUIRED') return t('app.attackExplicitPointsRequired')
  if (issue === 'TOO_MANY_EXPLICIT_POINTS') {
    return t('app.attackExplicitPointsTooMany', { limit: ATTACK_POINT_HARD_MAX })
  }
  if (issue === 'UNAVAILABLE_EXPLICIT_POINT') return t('app.attackExplicitPointUnavailable')
  if (issue === 'EXHAUSTIVE_NOT_ALLOWED') return t('app.simulationAttackMustBeExplicit')
  return ''
}

const verificationAttackConfigurationError = computed(() =>
  attackConfigurationError(verificationForm, true))
const simulationAttackConfigurationError = computed(() =>
  attackConfigurationError(simulationForm, false))

const setVerificationAttackEnabled = (enabled: boolean) => {
  verificationForm.isAttack = enabled
  verificationForm.attackMode = enabled ? 'ANY_UP_TO_BUDGET' : 'NONE'
}

const setSimulationAttackEnabled = (enabled: boolean) => {
  simulationForm.isAttack = enabled
  simulationForm.attackMode = enabled ? 'EXACT_POINTS' : 'NONE'
}

const setAttackMode = (form: AttackRunForm, mode: AttackScenarioMode) => {
  form.attackMode = mode
  form.isAttack = mode !== 'NONE'
}

const toggleAttackPoint = (form: AttackRunForm, key: string) => {
  const selected = new Set(form.selectedAttackPointKeys)
  if (selected.has(key)) selected.delete(key)
  else selected.add(key)
  form.selectedAttackPointKeys = Array.from(selected)
}

const buildRunAttackScenario = (form: AttackRunForm): AttackScenario => {
  if (form.attackMode === 'NONE') {
    return { mode: 'NONE', budget: 0, points: [] }
  }
  if (form.attackMode === 'ANY_UP_TO_BUDGET') {
    return { mode: 'ANY_UP_TO_BUDGET', budget: form.attackBudget, points: [] }
  }
  return {
    mode: 'EXACT_POINTS',
    points: selectedAttackPoints(boardAttackSurface.value, form.selectedAttackPointKeys)
  }
}

watch(boardAttackSurface, surface => {
  const availableKeys = new Set(surface.points.filter(point => point.selectable).map(point => point.key))
  verificationForm.selectedAttackPointKeys = verificationForm.selectedAttackPointKeys
    .filter(key => availableKeys.has(key))
  simulationForm.selectedAttackPointKeys = simulationForm.selectedAttackPointKeys
    .filter(key => availableKeys.has(key))
})

const boardRunBlockedReason = computed(() => {
  if (isSceneReplacementInProgress.value) return t('app.sceneReplacementInProgress')
  if (failedBoardDataKeys.value.length > 0) return t('app.boardDataLoadFailed')
  if (!isBoardDataReady.value) return t('app.loading')
  return ''
})

const specVariableSourcesResolved = computed(() =>
  specificationsWithUnresolvedVariableSource(specifications.value).length === 0)

const rulesHaveValidTriggers = computed(() => {
  try {
    rules.value.forEach((rule, index) => assertRuleHasTrigger(rule, index))
    return true
  } catch {
    return false
  }
})

const formalRunIssueMessage = (
  kind: FormalRunKind,
  issue: FormalRunReadinessIssue | null
): string => {
  if (issue === 'NO_DEVICES') {
    return t(kind === 'verification' ? 'app.noDevicesToVerify' : 'app.noDevicesToSimulate')
  }
  if (issue === 'NO_SPECIFICATIONS') return t('app.noSpecsToVerify')
  if (issue === 'RULE_TRIGGER_REQUIRED') return t('app.ruleTriggerSourceRequired')
  if (issue === 'SPEC_VARIABLE_SOURCE_REQUIRED') return t('app.specVariableSourceUnresolvedBlocked')
  if (issue === 'INVALID_SIMULATION_STEPS') {
    return t('app.integerBetween', {
      field: t('app.simulationSteps'),
      min: SIMULATION_STEPS_MIN,
      max: SIMULATION_STEPS_MAX
    })
  }
  return ''
}

const verificationReadinessIssue = computed(() => formalRunReadinessIssue('verification', {
  deviceCount: nodes.value.length,
  specificationCount: specifications.value.length,
  rulesHaveTriggers: rulesHaveValidTriggers.value,
  simulationStepsValid: true,
  specVariableSourcesResolved: specVariableSourcesResolved.value
}))

const simulationReadinessIssue = computed(() => formalRunReadinessIssue('simulation', {
  deviceCount: nodes.value.length,
  specificationCount: specifications.value.length,
  rulesHaveTriggers: rulesHaveValidTriggers.value,
  simulationStepsValid: Number.isInteger(simulationForm.steps)
    && simulationForm.steps >= SIMULATION_STEPS_MIN
    && simulationForm.steps <= SIMULATION_STEPS_MAX,
  specVariableSourcesResolved: specVariableSourcesResolved.value
}))

const verificationRunBlockedReason = computed(() => {
  if (!verificationForm.isAsync && synchronousSimulationRunning.value) {
    return t('app.formalOperationBusy')
  }
  return boardRunBlockedReason.value
    || formalRunIssueMessage('verification', verificationReadinessIssue.value)
    || verificationAttackConfigurationError.value
})

const simulationRunBlockedReason = computed(() => {
  if (!simulationForm.isAsync && synchronousVerificationRunning.value) {
    return t('app.formalOperationBusy')
  }
  if (traceAnimationState.value.visible || simulationAnimationState.value.visible) {
    return t('app.playbackMustCloseBeforeSimulation')
  }
  return boardRunBlockedReason.value
    || formalRunIssueMessage('simulation', simulationReadinessIssue.value)
    || simulationAttackConfigurationError.value
})

const verificationRunBlockedDescriptionId = computed(() => {
  if (!verificationRunBlockedReason.value) return undefined
  if (verificationRunBlockedReason.value === verificationAttackConfigurationError.value) {
    if (!hasModeledAttackEffect.value) return 'verification-attack-unavailable'
    if (verificationForm.isAttack) return verificationForm.attackMode === 'ANY_UP_TO_BUDGET'
      ? 'verification-attack-budget-invalid' : 'verification-attack-points-invalid'
  }
  return 'verification-run-blocked-reason'
})

const simulationRunBlockedDescriptionId = computed(() => {
  if (!simulationRunBlockedReason.value) return undefined
  if (simulationRunBlockedReason.value === simulationAttackConfigurationError.value) {
    if (!hasModeledAttackEffect.value) return 'simulation-attack-unavailable'
    if (simulationForm.isAttack) return 'simulation-attack-points-invalid'
  }
  return 'simulation-run-blocked-reason'
})

const hasPrivacySpecification = computed(() =>
  specificationsRequirePrivacy(specifications.value))

watch(hasPrivacySpecification, required => {
  if (required) verificationForm.enablePrivacy = true
}, { immediate: true })

const validateAttackBudget = (value: unknown) => {
  const issue = getAttackSelectionIssue(true, value, attackBudgetMax.value)
  if (issue === 'NO_MODELED_EFFECT') throw new Error(t('app.attackNoModeledEffect'))
  if (issue === 'INVALID_BUDGET') {
    throw new Error(t('app.attackBudgetSelectionInvalid', {
      selected: String(value),
      limit: attackBudgetMax.value
    }))
  }
  return value as number
}

const validateSimulationSteps = (value: unknown) =>
  requireIntegerInRange(value, t('app.simulationSteps'), 1, 100)

// 异步验证任务状态
const asyncVerificationTask = ref<{
  taskId: number | null
  progress: number
  statusKey: string
}>({
  taskId: null,
  progress: 0,
  statusKey: 'app.taskInitializing'
})
const asyncVerificationActive = ref(false)
const cancellingVerificationTask = ref(false)
const verificationCancelRequested = ref(false)

const asyncFuzzingTask = ref<{
  taskId: number | null
  progress: number
  statusKey: string
}>({
  taskId: null,
  progress: 0,
  statusKey: 'app.taskInitializing'
})
const isFuzzing = ref(false)
const asyncFuzzingActive = ref(false)
const cancellingFuzzingTask = ref(false)
const fuzzingCancelRequested = ref(false)
const showFuzzingPanel = ref(false)
const showFuzzingResultDialog = ref(false)
const fuzzingResult = ref<FuzzingRun | null>(null)
const fuzzingError = ref<RunFailure | null>(null)
const fuzzingFailureDetails = computed(() => fuzzingError.value ? formatRunFailure('fuzzing', fuzzingError.value) : null)
const fuzzingErrorMessage = computed(() => fuzzingFailureDetails.value?.message || null)
const fuzzingSettingsNotice = ref<{ key: string; params?: Record<string, number> } | null>(null)
const fuzzingSettingsNoticeMessage = computed(() => fuzzingSettingsNotice.value
  ? t(fuzzingSettingsNotice.value.key, fuzzingSettingsNotice.value.params || {}) : '')
const fuzzingResultLoading = ref(false)
const activeFuzzingFinding = ref<FuzzingFinding | null>(null)
let fuzzingResultRequestEpoch = 0
const fuzzingWorkloadPreview = ref<FuzzWorkloadPreview | null>(null)
const fuzzingWorkloadPreviewLoading = ref(false)
const fuzzingWorkloadPreviewError = ref<string | null>(null)
const fuzzingWorkloadPreviewSemanticKey = ref<string | null>(null)
let fuzzingWorkloadPreviewEpoch = 0
let fuzzingWorkloadPreviewTimer: ReturnType<typeof setTimeout> | null = null
const paperDomainPreview = ref<FuzzPaperDomainPreview | null>(null)
const paperDomainPreviewLoading = ref(false)
const paperDomainPreviewError = ref<string | null>(null)
const paperDomainPreviewSemanticKey = ref<string | null>(null)
const paperDomainStaleRecoveryActive = ref(false)
let paperDomainPreviewEpoch = 0
let paperDomainPreviewTimer: ReturnType<typeof setTimeout> | null = null

const paperDomainSemanticKey = computed(() => JSON.stringify({
  deviceTemplates: deviceTemplates.value,
  devices: nodes.value.map(device => ({
    id: device.id,
    templateName: device.templateName,
    label: device.label,
    state: device.state,
    currentStateTrust: device.currentStateTrust,
    currentStatePrivacy: device.currentStatePrivacy,
    variables: device.variables,
    privacies: device.privacies
  })),
  environmentVariables: environmentVariables.value,
  rules: rules.value,
  specifications: specifications.value
}))

// explorationMode belongs in the key because model complexity depends on it: switching modes changes
// the estimate, so a preview taken in the other mode must not be reused as current.
const fuzzingWorkloadSemanticKey = computed(() => JSON.stringify({
  board: paperDomainSemanticKey.value,
  maxIterations: fuzzingForm.maxIterations,
  pathLength: fuzzingForm.pathLength,
  populationSize: fuzzingForm.populationSize,
  explorationMode: fuzzingForm.explorationMode
}))


// A preview is only shown when it still describes this board and this budget; the freshness rule
// and its tests live in `utils/fuzzingConfig.ts`.
const fuzzingWorkloadReady = computed(() => isFuzzingPreviewCurrent(
  {
    preview: fuzzingWorkloadPreview.value,
    loading: fuzzingWorkloadPreviewLoading.value,
    error: fuzzingWorkloadPreviewError.value,
    previewSemanticKey: fuzzingWorkloadPreviewSemanticKey.value
  },
  fuzzingForm,
  fuzzingWorkloadSemanticKey.value
))

const fuzzingWorkload = computed(() => fuzzingWorkloadReady.value
  ? fuzzingWorkloadPreview.value?.estimatedWorkload
  : undefined)

const fuzzingWorkloadLimit = computed(() => fuzzingWorkloadReady.value
  ? fuzzingWorkloadPreview.value?.workloadLimit
  : undefined)

const validPaperPathLength = () => Number.isInteger(fuzzingForm.pathLength)
  && fuzzingForm.pathLength >= 1
  && fuzzingForm.pathLength <= FUZZ_PATH_LENGTH_MAX

const invalidatePaperDomainPreview = (clearError = true) => {
  if (paperDomainPreviewTimer) {
    clearTimeout(paperDomainPreviewTimer)
    paperDomainPreviewTimer = null
  }
  paperDomainPreviewEpoch += 1
  paperDomainPreview.value = null
  paperDomainPreviewSemanticKey.value = null
  paperDomainPreviewLoading.value = false
  if (clearError) paperDomainPreviewError.value = null
}

const paperDomainReady = computed(() => {
  if (fuzzingForm.explorationMode !== 'PAPER_COMPATIBLE') return true
  const preview = paperDomainPreview.value
  return !!preview
    && !paperDomainPreviewLoading.value
    && !paperDomainPreviewError.value
    && preview.pathLength === fuzzingForm.pathLength
    && paperDomainPreviewSemanticKey.value === paperDomainSemanticKey.value
    && isValidFuzzPaperDomainFingerprint(preview.modelFingerprint)
})

const paperDomainConfigurationError = computed(() =>
  fuzzingForm.explorationMode === 'PAPER_COMPATIBLE'
    && fuzzingPreviewPrerequisitesReady.value
    && !paperDomainReady.value
    ? t('app.fuzzPaperDomainRequired')
    : '')

const fuzzingWorkloadConfigurationError = computed(() => {
  const preview = fuzzingWorkloadReady.value ? fuzzingWorkloadPreview.value : null
  if (!preview?.accepted) {
    if (!preview) return ''
    // The board's own complexity multiplier is named, because without it the numbers on screen cannot
    // explain the rejection: the three visible knobs multiply to a fraction of the reported workload.
    // `maxAcceptedIterations === 0` means no iteration count fits, so the remedy is a different field.
    const detail = {
      workload: preview.estimatedWorkload.toLocaleString(),
      limit: preview.workloadLimit.toLocaleString(),
      iterations: preview.maxIterations.toLocaleString(),
      path: preview.pathLength.toLocaleString(),
      population: preview.populationSize.toLocaleString(),
      complexity: Math.max(1, preview.modelComplexityUnits).toLocaleString(),
      maxIterations: preview.maxAcceptedIterations.toLocaleString()
    }
    return preview.maxAcceptedIterations > 0
      ? t('app.fuzzWorkloadExceeded', detail)
      : t('app.fuzzWorkloadExceededFloor', detail)
  }
  return ''
})

const effectiveFuzzingConfigurationError = computed(() =>
  fuzzingLocalConfigurationError.value
  || fuzzingWorkloadConfigurationError.value
  || paperDomainConfigurationError.value)

const refreshPaperDomainPreview = async () => {
  if (paperDomainPreviewTimer) {
    clearTimeout(paperDomainPreviewTimer)
    paperDomainPreviewTimer = null
  }
  if (fuzzingForm.explorationMode !== 'PAPER_COMPATIBLE'
    || !validPaperPathLength()
    || !fuzzingPreviewPrerequisitesReady.value) {
    invalidatePaperDomainPreview()
    return
  }
  const requestedPathLength = fuzzingForm.pathLength
  const requestedSemanticKey = paperDomainSemanticKey.value
  const requestEpoch = ++paperDomainPreviewEpoch
  paperDomainPreview.value = null
  paperDomainPreviewSemanticKey.value = null
  paperDomainPreviewLoading.value = true
  paperDomainPreviewError.value = null
  try {
    const preview = await fuzzingApi.previewPaperDomain(requestedPathLength)
    if (requestEpoch !== paperDomainPreviewEpoch
      || fuzzingForm.explorationMode !== 'PAPER_COMPATIBLE'
      || fuzzingForm.pathLength !== requestedPathLength
      || paperDomainSemanticKey.value !== requestedSemanticKey) return
    paperDomainPreview.value = preview
    paperDomainPreviewSemanticKey.value = requestedSemanticKey
    if (paperDomainStaleRecoveryActive.value) {
      paperDomainStaleRecoveryActive.value = false
      fuzzingError.value = null
    }
  } catch (error: any) {
    if (requestEpoch !== paperDomainPreviewEpoch) return
    paperDomainPreview.value = null
    paperDomainPreviewSemanticKey.value = null
    paperDomainPreviewError.value = extractApiErrorMessage(
      error,
      t('app.fuzzPaperDomainPreviewFailed')
    )
  } finally {
    if (requestEpoch === paperDomainPreviewEpoch) paperDomainPreviewLoading.value = false
  }
}

const schedulePaperDomainPreview = () => {
  if (paperDomainPreviewTimer) clearTimeout(paperDomainPreviewTimer)
  paperDomainPreviewTimer = setTimeout(() => {
    paperDomainPreviewTimer = null
    void refreshPaperDomainPreview()
  }, 250)
}

const invalidateFuzzingWorkloadPreview = (clearError = true) => {
  if (fuzzingWorkloadPreviewTimer) {
    clearTimeout(fuzzingWorkloadPreviewTimer)
    fuzzingWorkloadPreviewTimer = null
  }
  fuzzingWorkloadPreviewEpoch += 1
  fuzzingWorkloadPreview.value = null
  fuzzingWorkloadPreviewSemanticKey.value = null
  fuzzingWorkloadPreviewLoading.value = false
  if (clearError) fuzzingWorkloadPreviewError.value = null
}

const refreshFuzzingWorkloadPreview = async () => {
  if (fuzzingWorkloadPreviewTimer) {
    clearTimeout(fuzzingWorkloadPreviewTimer)
    fuzzingWorkloadPreviewTimer = null
  }
  if (!showFuzzingPanel.value
    || !hasValidFuzzingBudget(fuzzingForm)
    || !fuzzingPreviewPrerequisitesReady.value) {
    invalidateFuzzingWorkloadPreview()
    return
  }
  const request = {
    maxIterations: fuzzingForm.maxIterations,
    pathLength: fuzzingForm.pathLength,
    populationSize: fuzzingForm.populationSize,
    explorationMode: fuzzingForm.explorationMode
  }
  const requestedSemanticKey = fuzzingWorkloadSemanticKey.value
  const requestEpoch = ++fuzzingWorkloadPreviewEpoch
  fuzzingWorkloadPreview.value = null
  fuzzingWorkloadPreviewSemanticKey.value = null
  fuzzingWorkloadPreviewLoading.value = true
  fuzzingWorkloadPreviewError.value = null
  try {
    const preview = await fuzzingApi.previewWorkload(request)
    if (requestEpoch !== fuzzingWorkloadPreviewEpoch
      || fuzzingWorkloadSemanticKey.value !== requestedSemanticKey) return
    fuzzingWorkloadPreview.value = preview
    fuzzingWorkloadPreviewSemanticKey.value = requestedSemanticKey
  } catch (error: any) {
    if (requestEpoch !== fuzzingWorkloadPreviewEpoch) return
    fuzzingWorkloadPreview.value = null
    fuzzingWorkloadPreviewSemanticKey.value = null
    fuzzingWorkloadPreviewError.value = extractApiErrorMessage(
      error,
      t('app.fuzzWorkloadPreviewFailed')
    )
  } finally {
    if (requestEpoch === fuzzingWorkloadPreviewEpoch) {
      fuzzingWorkloadPreviewLoading.value = false
    }
  }
}

const scheduleFuzzingWorkloadPreview = () => {
  if (fuzzingWorkloadPreviewTimer) clearTimeout(fuzzingWorkloadPreviewTimer)
  fuzzingWorkloadPreviewTimer = setTimeout(() => {
    fuzzingWorkloadPreviewTimer = null
    void refreshFuzzingWorkloadPreview()
  }, 250)
}

watch(
  [
    showFuzzingPanel,
    () => fuzzingForm.explorationMode,
    () => fuzzingForm.pathLength,
    paperDomainSemanticKey,
    fuzzingPreviewPrerequisitesReady
  ],
  ([visible, mode]) => {
    invalidatePaperDomainPreview()
    if (mode !== 'PAPER_COMPATIBLE' && paperDomainStaleRecoveryActive.value) {
      paperDomainStaleRecoveryActive.value = false
      fuzzingError.value = null
    }
    if (visible
      && mode === 'PAPER_COMPATIBLE'
      && validPaperPathLength()
      && fuzzingPreviewPrerequisitesReady.value) {
      schedulePaperDomainPreview()
    }
  },
  { flush: 'sync' }
)

watch(
  [showFuzzingPanel, fuzzingWorkloadSemanticKey, fuzzingPreviewPrerequisitesReady],
  ([visible]) => {
    invalidateFuzzingWorkloadPreview()
    if (visible && hasValidFuzzingBudget(fuzzingForm) && fuzzingPreviewPrerequisitesReady.value) {
      scheduleFuzzingWorkloadPreview()
    }
  },
  { flush: 'sync' }
)

onBeforeUnmount(() => {
  invalidatePaperDomainPreview()
  invalidateFuzzingWorkloadPreview()
  taskInboxRequests.invalidate()
  verificationHistoryRequests.invalidate()
  simulationHistoryRequests.invalidate()
  fuzzingHistoryRequests.invalidate()
  historyDetailRequests.invalidate()
})

const currentBoardModelFingerprints = ref<BoardModelFingerprints | null>(null)
const loadingCurrentBoardModelFingerprints = ref(false)
let boardModelRevision = 0
const fingerprintRequestGuard = createLatestBoardRequestGuard()

// Geometry and display names can change during canvas work without changing the model.
const boardModelSource = computed(() => JSON.stringify({
  devices: nodes.value.map(node => ({
    id: node.id,
    templateName: node.templateName,
    state: node.state,
    currentStateTrust: node.currentStateTrust,
    currentStatePrivacy: node.currentStatePrivacy,
    variables: node.variables,
    privacies: node.privacies
  })),
  rules: rules.value,
  specifications: specifications.value,
  environmentVariables: environmentVariables.value,
  deviceTemplates: deviceTemplates.value
}))

const invalidateCurrentBoardModelFingerprints = () => {
  boardModelRevision += 1
  currentBoardModelFingerprints.value = null
  fingerprintRequestGuard.invalidate()
  loadingCurrentBoardModelFingerprints.value = false
}

watch(boardModelSource, invalidateCurrentBoardModelFingerprints, { flush: 'sync' })
watch(boardModelSource, () => {
  // Snapshot adoption already starts a read for this revision before post-flush watchers run.
  if (!loadingCurrentBoardModelFingerprints.value) void refreshCurrentBoardModelFingerprints()
}, { flush: 'post' })

const refreshCurrentBoardModelFingerprints = async (
  expectedModelRevision = boardModelRevision
): Promise<BoardModelFingerprints | null> => {
  const requestEpoch = fingerprintRequestGuard.begin()
  currentBoardModelFingerprints.value = null
  loadingCurrentBoardModelFingerprints.value = true
  try {
    const fingerprints = await boardApi.getModelFingerprints()
    if (!boardLifecycleDisposed && fingerprintRequestGuard.isCurrent(requestEpoch)
      && expectedModelRevision === boardModelRevision) {
      currentBoardModelFingerprints.value = fingerprints
    }
    return fingerprints
  } catch {
    if (!boardLifecycleDisposed && fingerprintRequestGuard.isCurrent(requestEpoch)
      && expectedModelRevision === boardModelRevision) {
      currentBoardModelFingerprints.value = null
    }
    return null
  } finally {
    if (!boardLifecycleDisposed && fingerprintRequestGuard.isCurrent(requestEpoch)) {
      loadingCurrentBoardModelFingerprints.value = false
    }
  }
}

const fuzzRunHasBoardDrift = (run: AvailableFuzzingRunSummary | FuzzingRun): boolean => {
  return compareModelSnapshot(run.modelSnapshot, currentBoardModelFingerprints.value, 'fuzzing') !== 'UNCHANGED'
}

const fuzzingResultBoardDrifted = computed(() => {
  const run = fuzzingResult.value
  return run ? fuzzRunHasBoardDrift(run) : false
})

const fuzzingBoardComparison = computed(() => compareModelSnapshot(
  fuzzingResult.value?.modelSnapshot, currentBoardModelFingerprints.value, 'fuzzing'
))

type HistoryLayer = 'tasks' | 'results'
type HistoryResultFilter = 'all' | 'verification' | 'fuzzing' | 'simulation'
type HistoryResultSource = Exclude<HistoryResultFilter, 'all'>

const verificationTasks = ref<VerificationTaskSummary[]>([])
const fuzzingTasks = ref<FuzzingTaskSummary[]>([])
const simulationTasks = ref<SimulationTaskSummary[]>([])
const verificationRuns = ref<VerificationRunSummary[]>([])
const fuzzingRuns = ref<FuzzingRunSummary[]>([])
const simulationRuns = ref<SimulationTraceSummary[]>([])
const unavailableVerificationRunIds = new Set<number>()
const unavailableVerificationTraceIds = new Set<number>()
const unavailableSimulationTraceIds = new Set<number>()
const unavailableFuzzingRunIds = new Set<number>()
const unavailableFuzzingFindingIds = new Set<number>()

const unavailableTraceSummary = (trace: TraceSummary): TraceSummary => ({
  id: trace.id,
  verificationTaskId: trace.verificationTaskId,
  violatedSpecId: trace.violatedSpecId,
  createdAt: trace.createdAt,
  dataAvailable: false,
  unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID'
})

const unavailableVerificationRunSummary = (run: VerificationRunSummary): VerificationRunSummary => ({
  id: run.id,
  initiator: run.initiator,
  createdAt: run.createdAt,
  startedAt: run.startedAt,
  completedAt: run.completedAt,
  processingTimeMs: run.processingTimeMs,
  counterexampleCount: run.counterexampleCount,
  counterexamples: [],
  dataAvailable: false,
  unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID'
})

const unavailableSimulationTraceSummary = (
  trace: SimulationTraceSummary
): SimulationTraceSummary => ({
  id: trace.id,
  initiator: trace.initiator,
  createdAt: trace.createdAt,
  dataAvailable: false,
  unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID'
})

const unavailableFuzzingRunSummary = (run: FuzzingRunSummary): FuzzingRunSummary => ({
  id: run.id,
  initiator: run.initiator,
  explorationMode: run.explorationMode,
  createdAt: run.createdAt,
  completedAt: run.completedAt,
  findingCount: run.findingCount,
  findings: [],
  dataAvailable: false,
  unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID'
})

const unavailableFuzzingFindingSummary = (
  finding: FuzzingFindingSummary
): FuzzingFindingSummary => ({
  ...finding,
  dataAvailable: false,
  unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID'
})

const retainKnownVerificationHistoryAvailability = (runs: VerificationRunSummary[]) =>
  retainSessionUnavailableHistoryItems(
    runs,
    unavailableVerificationRunIds,
    unavailableVerificationRunSummary
  ).map(run => ({
    ...run,
    counterexamples: retainSessionUnavailableHistoryItems(
      run.counterexamples,
      unavailableVerificationTraceIds,
      unavailableTraceSummary
    )
  }))

const retainKnownSimulationHistoryAvailability = (runs: SimulationTraceSummary[]) =>
  retainSessionUnavailableHistoryItems(
    runs,
    unavailableSimulationTraceIds,
    unavailableSimulationTraceSummary
  )

const retainKnownFuzzingHistoryAvailability = (runs: FuzzingRunSummary[]) =>
  retainSessionUnavailableHistoryItems(
    runs,
    unavailableFuzzingRunIds,
    unavailableFuzzingRunSummary
  ).map(run => ({
    ...run,
    findings: retainSessionUnavailableHistoryItems(
      run.findings,
      unavailableFuzzingFindingIds,
      unavailableFuzzingFindingSummary
    )
  }))

const markVerificationTraceUnavailable = (traceId: number) => {
  unavailableVerificationTraceIds.add(traceId)
  verificationRuns.value = retainKnownVerificationHistoryAvailability(verificationRuns.value)
}

const markVerificationRunUnavailable = (runId: number) => {
  unavailableVerificationRunIds.add(runId)
  verificationRuns.value = retainKnownVerificationHistoryAvailability(verificationRuns.value)
}

const markSimulationTraceUnavailable = (traceId: number) => {
  unavailableSimulationTraceIds.add(traceId)
  simulationRuns.value = retainKnownSimulationHistoryAvailability(simulationRuns.value)
}

const markFuzzingRunUnavailable = (runId: number) => {
  unavailableFuzzingRunIds.add(runId)
  fuzzingRuns.value = retainKnownFuzzingHistoryAvailability(fuzzingRuns.value)
}

const markFuzzingFindingUnavailable = (findingId: number) => {
  unavailableFuzzingFindingIds.add(findingId)
  fuzzingRuns.value = retainKnownFuzzingHistoryAvailability(fuzzingRuns.value)
}
const FUZZ_TASK_INBOX_PAGE_SIZE = 100
const FUZZ_RUN_HISTORY_PAGE_SIZE = 25
const fuzzingRunsPage = ref(0)
const fuzzingRunsHasMore = ref(false)
const loadingMoreFuzzingRuns = ref(false)
const taskInboxRequests = createPagedRequestCoordinator()
const verificationHistoryRequests = createPagedRequestCoordinator()
const simulationHistoryRequests = createPagedRequestCoordinator()
const fuzzingHistoryRequests = createPagedRequestCoordinator()
const historyDetailRequests = createPagedRequestCoordinator()
let historyPanelIntentEpoch = 0
let fuzzingHistoryAppendPromise: Promise<boolean> | null = null
const showHistoryPanel = ref(false)
const activeHistoryLayer = ref<HistoryLayer>('results')
const activeHistoryResultFilter = ref<HistoryResultFilter>('all')
const loadingTaskHistory = ref(false)
const taskInboxError = ref<ApiErrorDetails | null>(null)
const loadingResultHistory = ref(false)
const pendingTaskActionKeys = ref<Set<string>>(new Set())
const pendingHistoryDeleteKeys = ref<Set<string>>(new Set())
const historyResultErrors = reactive<Record<HistoryResultSource, ApiErrorDetails | null>>({
  verification: null,
  fuzzing: null,
  simulation: null
})
let taskInboxRefreshTimer: ReturnType<typeof setInterval> | null = null
let taskInboxBackgroundRefreshPromise: Promise<boolean> | null = null
let taskInboxLoadingEpoch = 0
let historyResultsLoadingEpoch = 0
const fuzzRunRecoveryAttempts = new Map<number, number>()
const fuzzRunRecoveryNextAttemptAt = new Map<number, number>()
const fuzzRunRecoveryRequests = new Map<number, Promise<FuzzingRun>>()

const historyActionLocked = computed(() =>
  traceAnimationState.value.visible ||
  simulationAnimationState.value.visible ||
  isAnimationLocked.value ||
  pendingHistoryDeleteKeys.value.size > 0
)

const isCurrentHistoryPanelIntent = (
  epoch: number,
  layer: HistoryLayer,
  filter?: HistoryResultFilter
) => !boardLifecycleDisposed
  && epoch === historyPanelIntentEpoch
  && showHistoryPanel.value
  && activeHistoryLayer.value === layer
  && (filter === undefined || activeHistoryResultFilter.value === filter)

const taskActionKey = (
  action: 'cancel' | 'dismiss',
  kind: 'verification' | 'fuzzing' | 'simulation',
  taskId: number
) => `${action}:${kind}:${taskId}`

const withTaskActionLock = async (
  key: string,
  action: () => Promise<void>
): Promise<void> => {
  if (pendingTaskActionKeys.value.has(key)) return
  pendingTaskActionKeys.value = new Set(pendingTaskActionKeys.value).add(key)
  try {
    await action()
  } finally {
    const next = new Set(pendingTaskActionKeys.value)
    next.delete(key)
    pendingTaskActionKeys.value = next
  }
}

const beginHistoryDelete = (key: string): boolean => {
  if (pendingHistoryDeleteKeys.value.has(key)) return false
  pendingHistoryDeleteKeys.value = new Set(pendingHistoryDeleteKeys.value).add(key)
  return true
}

const finishHistoryDelete = (key: string) => {
  const next = new Set(pendingHistoryDeleteKeys.value)
  next.delete(key)
  pendingHistoryDeleteKeys.value = next
}

const isActiveTaskStatus = (status?: string) => status === 'PENDING' || status === 'RUNNING'

const normalizeTaskProgress = (value?: number | null, fallback = 0): number => {
  const numeric = typeof value === 'number' ? value : fallback
  if (!Number.isFinite(numeric)) return fallback
  return Math.min(100, Math.max(0, Math.round(numeric)))
}

const taskSummaryTimestamp = (value?: string) => {
  if (!value) return 0
  const parsed = new Date(value).getTime()
  return Number.isNaN(parsed) ? 0 : parsed
}

const fuzzNotificationStorageKey = () =>
  fuzzNotificationStorageKeyForUser(currentUser.value?.userId)

const persistFuzzNotificationState = () => {
  try {
    localStorage.setItem(fuzzNotificationStorageKey(), JSON.stringify({
      unread: unreadFuzzNotifications.value.slice(0, 100),
      trackedTaskIds: trackedFuzzTaskIds.value.slice(0, 100)
    }))
  } catch {
    // Notification persistence is best-effort; task/run history remains authoritative.
  }
}

const clearFuzzRunRecoveryState = (taskId: number) => {
  fuzzRunRecoveryAttempts.delete(taskId)
  fuzzRunRecoveryNextAttemptAt.delete(taskId)
}

const scheduleFuzzRunRecovery = (taskId: number): number => {
  const attempt = fuzzRunRecoveryAttempts.get(taskId) ?? 0
  const delay = fuzzRunRetryDelayMs(attempt)
  fuzzRunRecoveryAttempts.set(taskId, attempt + 1)
  fuzzRunRecoveryNextAttemptAt.set(taskId, Date.now() + delay)
  return delay
}

const canAttemptFuzzRunRecovery = (taskId: number) =>
  Date.now() >= (fuzzRunRecoveryNextAttemptAt.get(taskId) ?? 0)

const loadFuzzRunSingleFlight = (taskId: number, runId = taskId): Promise<FuzzingRun> => {
  const existing = fuzzRunRecoveryRequests.get(taskId)
  if (existing) return existing
  const request = fuzzingApi.getRun(runId)
  fuzzRunRecoveryRequests.set(taskId, request)
  void request.finally(() => {
    if (fuzzRunRecoveryRequests.get(taskId) === request) {
      fuzzRunRecoveryRequests.delete(taskId)
    }
  }).catch(() => undefined)
  return request
}

const hydrateFuzzNotificationState = () => {
  try {
    const parsed = JSON.parse(localStorage.getItem(fuzzNotificationStorageKey()) || '{}')
    const unread = Array.isArray(parsed.unread) ? parsed.unread : []
    unreadFuzzNotifications.value = unread.filter((item: any) =>
      Number.isSafeInteger(item?.taskId)
      && item.taskId > 0
      && (item.kind === 'COMPLETED' || item.kind === 'FAILED' || item.kind === 'UNAVAILABLE')
      && typeof item.createdAt === 'string')
      .map((item: FuzzUnreadNotification) => ({
        ...item,
        initiator: isRunInitiator(item.initiator) ? item.initiator : 'UNKNOWN'
      }))
      .slice(0, 100)
    trackedFuzzTaskIds.value = (Array.isArray(parsed.trackedTaskIds) ? parsed.trackedTaskIds : [])
      .filter((id: unknown) => Number.isSafeInteger(id) && Number(id) > 0)
      .map(Number)
      .filter((id: number, index: number, values: number[]) => values.indexOf(id) === index)
      .slice(0, 100)
  } catch {
    unreadFuzzNotifications.value = []
    trackedFuzzTaskIds.value = []
  }
}

const trackFuzzTask = (taskId: number) => {
  if (!trackedFuzzTaskIds.value.includes(taskId)) {
    clearFuzzRunRecoveryState(taskId)
    trackedFuzzTaskIds.value = [taskId, ...trackedFuzzTaskIds.value].slice(0, 100)
    persistFuzzNotificationState()
  }
}

const untrackFuzzTask = (taskId: number) => {
  clearFuzzRunRecoveryState(taskId)
  if (!trackedFuzzTaskIds.value.includes(taskId)) return
  trackedFuzzTaskIds.value = trackedFuzzTaskIds.value.filter(id => id !== taskId)
  persistFuzzNotificationState()
}

const markFuzzNotificationUnread = (notification: FuzzUnreadNotification): boolean => {
  if (notification.kind === 'COMPLETED'
    && notification.runId
    && showFuzzingResultDialog.value
    && fuzzingResult.value?.id === notification.runId) {
    unreadFuzzNotifications.value = unreadFuzzNotifications.value
      .filter(item => item.taskId !== notification.taskId)
    untrackFuzzTask(notification.taskId)
    persistFuzzNotificationState()
    return false
  }
  unreadFuzzNotifications.value = [
    notification,
    ...unreadFuzzNotifications.value.filter(item => item.taskId !== notification.taskId)
  ].slice(0, 100)
  untrackFuzzTask(notification.taskId)
  persistFuzzNotificationState()
  return true
}

const clearFuzzNotifications = (kind?: FuzzUnreadNotification['kind'], taskId?: number) => {
  unreadFuzzNotifications.value = unreadFuzzNotifications.value.filter(item => {
    if (taskId !== undefined && item.taskId !== taskId && item.runId !== taskId) return true
    if (kind && item.kind !== kind) return true
    return false
  })
  persistFuzzNotificationState()
}

const clearVisibleFuzzResultNotifications = () => {
  const visibleRunIds = new Set(fuzzingRuns.value.map(run => run.id))
  unreadFuzzNotifications.value = unreadFuzzNotifications.value.filter(notification =>
    !(['COMPLETED', 'UNAVAILABLE'].includes(notification.kind)
      && visibleRunIds.has(notification.runId ?? notification.taskId)))
  persistFuzzNotificationState()
}

const withUnreadFuzzUnavailablePlaceholders = (runs: FuzzingRunSummary[]): FuzzingRunSummary[] => {
  const knownIds = new Set(runs.map(run => run.id))
  const placeholders: FuzzingRunSummary[] = unreadFuzzNotifications.value
    .filter(notification => notification.kind === 'UNAVAILABLE' && !knownIds.has(notification.taskId))
    .map(notification => ({
      id: notification.taskId,
      initiator: notification.initiator ?? 'UNKNOWN',
      createdAt: notification.createdAt,
      completedAt: notification.createdAt,
      findingCount: 0,
      findings: [],
      dataAvailable: false,
      unavailableReasonCode: 'RESULT_UNAVAILABLE'
    }))
  return [...placeholders, ...runs]
}

const mergeTaskSummariesPreservingExcluded = <T extends { id?: number; createdAt?: string }>(
  current: T[],
  incoming: T[],
  excludedIds: number[]
): T[] => {
  if (excludedIds.length === 0) return incoming
  const excluded = new Set(excludedIds)
  const preserved = current.filter(task => task.id !== undefined && excluded.has(task.id))
  const merged = [
    ...preserved,
    ...incoming.filter(task => task.id === undefined || !excluded.has(task.id))
  ]
  return merged.sort((a, b) => taskSummaryTimestamp(b.createdAt) - taskSummaryTimestamp(a.createdAt))
}

const watchedVerificationTaskIds = computed(() => {
  const taskId = asyncVerificationTask.value.taskId
  return isVerifying.value && asyncVerificationActive.value && taskId ? [taskId] : []
})

const watchedSimulationTaskIds = computed(() => {
  const taskId = asyncSimulationTask.value.taskId
  return isSimulating.value && asyncSimulationActive.value && taskId ? [taskId] : []
})

const watchedFuzzingTaskIds = computed(() => {
  const taskId = asyncFuzzingTask.value.taskId
  return isFuzzing.value && asyncFuzzingActive.value && taskId ? [taskId] : []
})

const activeVerificationTasks = computed(() =>
  verificationTasks.value.filter(task => isActiveTaskStatus(task.status))
)

const activeSimulationTasks = computed(() =>
  simulationTasks.value.filter(task => isActiveTaskStatus(task.status))
)

const activeFuzzingTasks = computed(() =>
  fuzzingTasks.value.filter(task => isActiveTaskStatus(task.status))
)

const activeBackgroundTaskCount = computed(() =>
  activeVerificationTasks.value.length + activeSimulationTasks.value.length + activeFuzzingTasks.value.length
)

const runningVerificationTask = computed(() => isVerifying.value && asyncVerificationActive.value
  ? verificationTasks.value.find(task => task.id === asyncVerificationTask.value.taskId) : undefined)
const runningSimulationTask = computed(() => isSimulating.value && asyncSimulationActive.value
  ? simulationTasks.value.find(task => task.id === asyncSimulationTask.value.taskId) : undefined)

const miniTaskItems = computed(() => {
  const items: Array<{
    key: string
    kind: 'verification' | 'fuzzing' | 'simulation'
    id: number
    label: string
    status: string
    progress: number
  }> = []

  const currentVerificationId = asyncVerificationTask.value.taskId
  if (isVerifying.value && asyncVerificationActive.value && currentVerificationId) {
    items.push({
      key: `verification-${currentVerificationId}`,
      kind: 'verification',
      id: currentVerificationId,
      label: t('app.verification'),
      status: formatWatchedTaskStatus(asyncVerificationTask.value.statusKey, cancellingVerificationTask.value),
      progress: normalizeTaskProgress(asyncVerificationTask.value.progress)
    })
  }
  for (const task of activeVerificationTasks.value) {
    if (task.id === currentVerificationId) continue
    items.push({
      key: `verification-${task.id}`,
      kind: 'verification',
      id: task.id,
      label: t('app.verification'),
      status: formatTaskProgressStage(task.progressStage, task.status),
      progress: normalizeTaskProgress(task.progress)
    })
  }

  const currentFuzzingId = asyncFuzzingTask.value.taskId
  if (isFuzzing.value && asyncFuzzingActive.value && currentFuzzingId) {
    items.push({
      key: `fuzzing-${currentFuzzingId}`,
      kind: 'fuzzing',
      id: currentFuzzingId,
      label: t('app.fuzzSearch'),
      status: formatWatchedTaskStatus(asyncFuzzingTask.value.statusKey, cancellingFuzzingTask.value),
      progress: normalizeTaskProgress(asyncFuzzingTask.value.progress)
    })
  }
  for (const task of activeFuzzingTasks.value) {
    if (task.id === currentFuzzingId) continue
    items.push({
      key: `fuzzing-${task.id}`,
      kind: 'fuzzing',
      id: task.id,
      label: t('app.fuzzSearch'),
      status: formatTaskProgressStage(task.progressStage, task.status),
      progress: normalizeTaskProgress(task.progress)
    })
  }

  const currentSimulationId = asyncSimulationTask.value.taskId
  if (isSimulating.value && asyncSimulationActive.value && currentSimulationId) {
    items.push({
      key: `simulation-${currentSimulationId}`,
      kind: 'simulation',
      id: currentSimulationId,
      label: t('app.simulation'),
      status: formatWatchedTaskStatus(asyncSimulationTask.value.statusKey, cancellingSimulationTask.value),
      progress: normalizeTaskProgress(asyncSimulationTask.value.progress)
    })
  }
  for (const task of activeSimulationTasks.value) {
    if (task.id === currentSimulationId) continue
    items.push({
      key: `simulation-${task.id}`,
      kind: 'simulation',
      id: task.id,
      label: t('app.simulation'),
      status: formatTaskProgressStage(task.progressStage, task.status),
      progress: normalizeTaskProgress(task.progress)
    })
  }

  return items
})

const invalidateTaskInboxRequests = () => {
  taskInboxRequests.invalidate()
  taskInboxBackgroundRefreshPromise = null
  taskInboxLoadingEpoch += 1
  loadingTaskHistory.value = false
}

const upsertVerificationTaskSummary = (task: Partial<VerificationTaskSummary> & { id?: number }) => {
  if (!task.id) return
  invalidateTaskInboxRequests()
  const existing = verificationTasks.value.findIndex(item => item.id === task.id)
  const next = task as VerificationTaskSummary
  verificationTasks.value = existing >= 0
    ? verificationTasks.value.map(item => item.id === task.id ? { ...item, ...next } : item)
    : [next, ...verificationTasks.value]
}

const upsertSimulationTaskSummary = (task: Partial<SimulationTaskSummary> & { id?: number }) => {
  if (!task.id) return
  invalidateTaskInboxRequests()
  const existing = simulationTasks.value.findIndex(item => item.id === task.id)
  const next = task as SimulationTaskSummary
  simulationTasks.value = existing >= 0
    ? simulationTasks.value.map(item => item.id === task.id ? { ...item, ...next } : item)
    : [next, ...simulationTasks.value]
}

const upsertFuzzingTaskSummary = (task: Partial<FuzzingTaskSummary> & { id?: number }) => {
  if (!task.id) return
  invalidateTaskInboxRequests()
  const existing = fuzzingTasks.value.findIndex(item => item.id === task.id)
  const next = task as FuzzingTaskSummary
  fuzzingTasks.value = existing >= 0
    ? fuzzingTasks.value.map(item => item.id === task.id ? { ...item, ...next } : item)
    : [next, ...fuzzingTasks.value]
}

type TaskInboxBatch<T> = {
  tasks: T[]
  excludedIds: number[]
}

const fetchVerificationTasks = async (): Promise<TaskInboxBatch<VerificationTaskSummary>> => {
  const excludedIds = [...watchedVerificationTaskIds.value]
  const tasks = await boardApi.getTasks(excludedIds)
  return { tasks: tasks || [], excludedIds }
}

const fetchSimulationTasks = async (): Promise<TaskInboxBatch<SimulationTaskSummary>> => {
  const excludedIds = [...watchedSimulationTaskIds.value]
  const tasks = await simulationApi.getTasks(excludedIds)
  return { tasks: tasks || [], excludedIds }
}

const reconcileTrackedFuzzTasks = async (
  tasks: FuzzingTaskSummary[],
  excludedIds: number[],
  isCurrent: () => boolean
) => {
  if (!isCurrent()) return
  const unavailableTaskIds: number[] = []
  tasks.filter(task => isActiveTaskStatus(task.status)).forEach(task => trackFuzzTask(task.id))
  const excluded = new Set(excludedIds)
  const byId = new Map(tasks.map(task => [task.id, task]))
  const completedCandidates: Array<{ taskId: number; task?: FuzzingTaskSummary }> = []

  for (const taskId of [...trackedFuzzTaskIds.value]) {
    if (excluded.has(taskId)) continue
    const task = byId.get(taskId)
    if (task && isActiveTaskStatus(task.status)) continue
    if (task?.status === 'CANCELLED') {
      untrackFuzzTask(taskId)
      continue
    }
    if (task?.status === 'FAILED') {
      markFuzzNotificationUnread({
        taskId,
        kind: 'FAILED',
        initiator: task.initiator,
        createdAt: task.completedAt || task.createdAt
      })
      continue
    }
    if (canAttemptFuzzRunRecovery(taskId)) completedCandidates.push({ taskId, task })
  }

  // A bounded batch avoids turning a restored list of task IDs into a request burst.
  await Promise.all(completedCandidates.slice(0, 4).map(async ({ taskId, task }) => {
    let resolvedTask = task
    try {
      // Completed tasks are omitted from the inbox, but an active task can also be
      // absent because the inbox is paged. Resolve status before treating absence as completion.
      if (!resolvedTask) {
        resolvedTask = await fuzzingApi.getTask(taskId)
        if (!isCurrent()) return
      }
      if (!isCurrent() || isActiveTaskStatus(resolvedTask.status)) return
      if (resolvedTask.status === 'CANCELLED') {
        untrackFuzzTask(taskId)
        return
      }
      if (resolvedTask.status === 'FAILED') {
        markFuzzNotificationUnread({
          taskId,
          kind: 'FAILED',
          initiator: resolvedTask.initiator,
          createdAt: resolvedTask.completedAt || resolvedTask.createdAt
        })
        return
      }
      const run = await loadFuzzRunSingleFlight(taskId, resolvedTask.runId ?? taskId)
      if (!isCurrent()) return
      clearFuzzRunRecoveryState(taskId)
      markFuzzNotificationUnread({
        taskId,
        runId: run.id,
        kind: 'COMPLETED',
        initiator: run.initiator,
        outcome: run.outcome,
        createdAt: run.completedAt
      })
    } catch (error: any) {
      if (!isCurrent()) return
      if (classifyTrackedFuzzRunError(error) === 'RETRY') {
        scheduleFuzzRunRecovery(taskId)
        return
      }
      const createdAt = resolvedTask?.completedAt
        || resolvedTask?.createdAt
        || new Date().toISOString()
      upsertFuzzingRunSummary({
        id: taskId,
        initiator: resolvedTask?.initiator ?? 'UNKNOWN',
        explorationMode: resolvedTask?.explorationMode,
        createdAt,
        completedAt: resolvedTask?.completedAt,
        findingCount: 0,
        findings: [],
        dataAvailable: false,
        unavailableReasonCode: 'RESULT_UNAVAILABLE'
      })
      markFuzzNotificationUnread({
        taskId,
        runId: taskId,
        kind: 'UNAVAILABLE',
        initiator: resolvedTask?.initiator ?? 'UNKNOWN',
        createdAt
      })
      unavailableTaskIds.push(taskId)
    }
  }))
  if (isCurrent() && unavailableTaskIds.length > 0) {
    notifyError(t('app.fuzzTrackedRunsUnavailable', { count: unavailableTaskIds.length }))
  }
}

const fetchFuzzingTasks = async (): Promise<TaskInboxBatch<FuzzingTaskSummary>> => {
  const excludedIds = [...watchedFuzzingTaskIds.value]
  const tasks = await fuzzingApi.getTasks(excludedIds, 0, FUZZ_TASK_INBOX_PAGE_SIZE)
  return { tasks: tasks || [], excludedIds }
}

// Only a current, applied read can acknowledge unread task failures.
const loadTaskInbox = async (
  options: { showLoading?: boolean } = {}
): Promise<boolean> => {
  if (boardLifecycleDisposed) return false
  const showLoading = options.showLoading ?? true
  const token = taskInboxRequests.beginReplace()
  const loadingEpoch = ++taskInboxLoadingEpoch
  if (showLoading) loadingTaskHistory.value = true
  try {
    const [verification, simulation, fuzzing] = await Promise.all([
      fetchVerificationTasks(),
      fetchSimulationTasks(),
      fetchFuzzingTasks()
    ])
    if (!taskInboxRequests.isCurrent(token)) return false
    await reconcileTrackedFuzzTasks(
      fuzzing.tasks,
      fuzzing.excludedIds,
      () => taskInboxRequests.isCurrent(token)
    )
    if (!taskInboxRequests.isCurrent(token)) return false
    verificationTasks.value = mergeTaskSummariesPreservingExcluded(
      verificationTasks.value,
      verification.tasks,
      verification.excludedIds
    )
    simulationTasks.value = mergeTaskSummariesPreservingExcluded(
      simulationTasks.value,
      simulation.tasks,
      simulation.excludedIds
    )
    fuzzingTasks.value = mergeTaskSummariesPreservingExcluded(
      fuzzingTasks.value,
      fuzzing.tasks,
      fuzzing.excludedIds
    )
    taskInboxError.value = null
    return true
  } catch (e: any) {
    if (!taskInboxRequests.isCurrent(token)) return false
    console.error('Failed to load async tasks:', e)
    taskInboxError.value = errorDetails(e)
    return false
  } finally {
    if (loadingEpoch === taskInboxLoadingEpoch) loadingTaskHistory.value = false
    taskInboxRequests.finish(token)
  }
}

const refreshTaskInboxInBackground = (): Promise<boolean> => {
  if (boardLifecycleDisposed) return Promise.resolve(false)
  if (taskInboxBackgroundRefreshPromise) return taskInboxBackgroundRefreshPromise
  const refresh = loadTaskInbox({ showLoading: false })
  taskInboxBackgroundRefreshPromise = refresh
  void refresh.finally(() => {
    if (taskInboxBackgroundRefreshPromise === refresh) {
      taskInboxBackgroundRefreshPromise = null
    }
  })
  return refresh
}

const isHistoryResultErrorVisible = (source: HistoryResultSource): boolean =>
  showHistoryPanel.value && activeHistoryLayer.value === 'results'
  && (activeHistoryResultFilter.value === 'all' || activeHistoryResultFilter.value === source)

const loadVerificationRuns = async (showError = true): Promise<boolean> => {
  if (boardLifecycleDisposed) return false
  const token = verificationHistoryRequests.beginReplace()
  try {
    const runs = await boardApi.getVerificationRuns()
    if (!verificationHistoryRequests.isCurrent(token)) return false
    verificationRuns.value = retainKnownVerificationHistoryAvailability(runs || [])
    historyResultErrors.verification = null
    return true
  } catch (e: any) {
    if (!verificationHistoryRequests.isCurrent(token)) return false
    console.error('Failed to load verification run history:', e)
    historyResultErrors.verification = errorDetails(e)
    if (showError && !isHistoryResultErrorVisible('verification')) {
      notifyError(t('app.failedToLoadVerificationHistory'))
    }
    return false
  } finally {
    verificationHistoryRequests.finish(token)
  }
}

const loadSimulationRuns = async (showError = true): Promise<boolean> => {
  if (boardLifecycleDisposed) return false
  const token = simulationHistoryRequests.beginReplace()
  try {
    const runs = await simulationApi.getUserSimulations()
    if (!simulationHistoryRequests.isCurrent(token)) return false
    simulationRuns.value = retainKnownSimulationHistoryAvailability(runs || [])
    historyResultErrors.simulation = null
    return true
  } catch (e: any) {
    if (!simulationHistoryRequests.isCurrent(token)) return false
    console.error('Failed to load simulation run history:', e)
    historyResultErrors.simulation = errorDetails(e)
    if (showError && !isHistoryResultErrorVisible('simulation')) {
      notifyError(t('app.failedToLoadSimulationHistory'))
    }
    return false
  } finally {
    simulationHistoryRequests.finish(token)
  }
}

const executeFuzzingRunsRequest = async (
  token: PagedRequestToken,
  showError = true,
  page = 0,
  append = false
): Promise<boolean> => {
  try {
    const [runs] = await Promise.all([
      fuzzingApi.getRuns(page, FUZZ_RUN_HISTORY_PAGE_SIZE),
      refreshCurrentBoardModelFingerprints()
    ])
    if (!fuzzingHistoryRequests.isCurrent(token)) return false
    const retainedRuns = retainKnownFuzzingHistoryAvailability(runs)
    if (append) {
      const existingIds = new Set(fuzzingRuns.value.map(run => run.id))
      fuzzingRuns.value = [
        ...fuzzingRuns.value,
        ...retainedRuns.filter(run => !existingIds.has(run.id))
      ]
    } else {
      fuzzingRuns.value = retainKnownFuzzingHistoryAvailability(
        withUnreadFuzzUnavailablePlaceholders(retainedRuns)
      )
    }
    historyResultErrors.fuzzing = null
    fuzzingRunsPage.value = page
    fuzzingRunsHasMore.value = runs.length === FUZZ_RUN_HISTORY_PAGE_SIZE
    return true
  } catch (e: any) {
    if (!fuzzingHistoryRequests.isCurrent(token)) return false
    console.error('Failed to load fuzzing run history:', e)
    historyResultErrors.fuzzing = errorDetails(e)
    if (showError && !isHistoryResultErrorVisible('fuzzing')) {
      notifyError(t('app.failedToLoadFuzzingHistory'))
    }
    return false
  } finally {
    fuzzingHistoryRequests.finish(token)
  }
}

const loadFuzzingRuns = (
  showError = true,
  options: { page?: number; append?: boolean } = {}
): Promise<boolean> => {
  if (boardLifecycleDisposed) return Promise.resolve(false)
  const page = options.page ?? 0
  const append = options.append === true
  if (append && fuzzingHistoryAppendPromise) return fuzzingHistoryAppendPromise

  const token = append
    ? fuzzingHistoryRequests.beginAppend()
    : fuzzingHistoryRequests.beginReplace()
  if (!token) return Promise.resolve(false)

  if (!append) return executeFuzzingRunsRequest(token, showError, page, false)

  loadingMoreFuzzingRuns.value = true
  let trackedRequest: Promise<boolean>
  trackedRequest = executeFuzzingRunsRequest(token, showError, page, true)
    .finally(() => {
      if (fuzzingHistoryAppendPromise === trackedRequest) {
        fuzzingHistoryAppendPromise = null
        loadingMoreFuzzingRuns.value = false
      }
    })
  fuzzingHistoryAppendPromise = trackedRequest
  return trackedRequest
}

const loadMoreFuzzingRuns = async () => {
  if (boardLifecycleDisposed || loadingMoreFuzzingRuns.value || !fuzzingRunsHasMore.value) return
  await loadFuzzingRuns(true, { page: fuzzingRunsPage.value + 1, append: true })
}

const loadHistoryResults = async (): Promise<boolean> => {
  if (boardLifecycleDisposed) return false
  const loadingEpoch = ++historyResultsLoadingEpoch
  loadingResultHistory.value = true
  try {
    const results = await Promise.all([
      loadVerificationRuns(false),
      loadSimulationRuns(false),
      loadFuzzingRuns(false)
    ])
    if (loadingEpoch !== historyResultsLoadingEpoch) return false
    return results.every(Boolean)
  } finally {
    if (loadingEpoch === historyResultsLoadingEpoch) loadingResultHistory.value = false
  }
}

const refreshRunHistory = async (): Promise<boolean> => {
  const results = await Promise.all([
    loadTaskInbox({ showLoading: false }),
    loadHistoryResults()
  ])
  /*
   * An authoritative history reload can reveal that the run on screen is gone, and the open surface has to
   * follow. Two paths reach this without going through `deleteVerificationRun`:
   *
   *  - the assistant's `DeleteVerificationRunTool`, which emits `REFRESH_DATA run_history` — the list
   *    reloaded and the dialog kept rendering the deleted run
   *  - another tab deleting the run, whose invalidation lands here
   *
   * Reconciling *after* a successful load, and only then: a failed reload leaves the lists as they were,
   * and treating that as "the run is gone" would close a surface over a transport error.
   */
  if (results.every(Boolean)) reconcileOpenRunAgainstHistory()
  return results.every(Boolean)
}

const refreshAllBoardState = async (): Promise<boolean> => {
  const results = await Promise.all([
    refreshSceneForReconciliation(),
    refreshRunHistory()
  ])
  return results.every(Boolean)
}

const refreshHistoryLayer = async (layer: HistoryLayer = activeHistoryLayer.value): Promise<boolean> => {
  historyDetailRequests.invalidate()
  // Result loading already refreshes fingerprints alongside the fuzzing history request.
  if (layer === 'results') return loadHistoryResults()
  const [loaded] = await Promise.all([
    loadTaskInbox(),
    refreshCurrentBoardModelFingerprints()
  ])
  return loaded
}

const setHistoryLayer = async (layer: HistoryLayer) => {
  const intentEpoch = ++historyPanelIntentEpoch
  historyDetailRequests.invalidate()
  activeHistoryLayer.value = layer
  const loaded = await refreshHistoryLayer(layer)
  if (!loaded || !isCurrentHistoryPanelIntent(intentEpoch, layer)) return
  if (layer === 'results') {
    if (activeHistoryResultFilter.value === 'all' || activeHistoryResultFilter.value === 'fuzzing') {
      clearVisibleFuzzResultNotifications()
    }
  } else {
    clearFuzzNotifications('FAILED')
  }
}

const setHistoryResultFilter = async (filter: HistoryResultFilter) => {
  const intentEpoch = ++historyPanelIntentEpoch
  historyDetailRequests.invalidate()
  activeHistoryResultFilter.value = filter
  if (showHistoryPanel.value
    && activeHistoryLayer.value === 'results'
    && (filter === 'all' || filter === 'fuzzing')) {
    const loaded = await loadFuzzingRuns(true)
    if (loaded && isCurrentHistoryPanelIntent(intentEpoch, 'results', filter)) {
      clearVisibleFuzzResultNotifications()
    }
  }
}

const closeHistoryPanel = (invalidatePendingDetail = true) => {
  historyPanelIntentEpoch += 1
  if (invalidatePendingDetail) historyDetailRequests.invalidate()
  showHistoryPanel.value = false
}

/**
 * Clear every workflow surface except the one about to open.
 *
 * The board shows one workflow surface at a time, so each opener has to close the others first. That was
 * written out as the same eight lines at two call sites (`toggleHistoryPanel` and `openTaskInbox`) — byte for
 * byte, including the ordering — so a seventh surface would have had to be remembered in both. The set is the
 * same one `isWorkflowPanelOpen` reports on; keeping the closing side as a copy while the reading side had an
 * owner is how the two drift apart.
 */
const closeOtherWorkflowSurfaces = () => {
  closeResultSurfaces()
  showSimulationPanel.value = false
  showVerificationPanel.value = false
  showFuzzingPanel.value = false
  closeRecommendationPanel()
  closeDeviceRecommendationPanel()
  closeSpecRecommendationPanel()
  closeScenarioRecommendationPanel()
}

const toggleHistoryPanel = async (layer: HistoryLayer = activeHistoryLayer.value) => {
  if (showHistoryPanel.value && activeHistoryLayer.value === layer) {
    closeHistoryPanel()
    return
  }
  if (isModelPlaybackActive.value) {
    notifyBlocked(t('app.playbackReadOnlyCloseFirst'))
    return
  }
  if (isAnyRecommendationRunning()) {
    notifyBlocked(t('app.recommendationGenerationInProgress'))
    return
  }

  closeOtherWorkflowSurfaces()

  const intentEpoch = ++historyPanelIntentEpoch
  historyDetailRequests.invalidate()
  activeHistoryLayer.value = layer
  showHistoryPanel.value = true
  const loaded = await refreshHistoryLayer(layer)
  if (!loaded || !isCurrentHistoryPanelIntent(intentEpoch, layer)) return
  if (layer === 'results') {
    if (activeHistoryResultFilter.value === 'all' || activeHistoryResultFilter.value === 'fuzzing') {
      clearVisibleFuzzResultNotifications()
    }
  } else {
    clearFuzzNotifications('FAILED')
  }
}

const formatRunTimestamp = (value?: string): string => {
  if (!value) return t('app.unknown')
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString(locale.value.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en-US')
}

const refreshHistoryTasks = () => {
  historyDetailRequests.invalidate()
  return loadTaskInbox()
}

const refreshHistoryResults = () => {
  historyDetailRequests.invalidate()
  return loadHistoryResults()
}

const deleteVerificationRun = async (run: VerificationRunSummary) => {
  const runId = run.id
  const pendingKey = `verification:${runId}`
  if (!beginHistoryDelete(pendingKey)) return
  try {
    if (!await confirmHistoryDeletion(
      () => confirmDestructive({
        title: t('app.deleteVerificationRunTitle'),
        message: t('app.deleteVerificationRunMessage', {
          time: formatRunTimestamp(run.completedAt),
          counterexamples: run.counterexampleCount
        }),
        confirmText: t('app.delete')
      }),
      historyDetailRequests.invalidate
    )) return
    await boardApi.deleteVerificationRun(runId)
    if (boardLifecycleDisposed) return
    verificationHistoryRequests.invalidate()
    unavailableVerificationRunIds.delete(runId)
    for (const trace of run.counterexamples) {
      unavailableVerificationTraceIds.delete(trace.id)
    }
    verificationRuns.value = verificationRuns.value.filter(item => item.id !== runId)
    // The deleted run may be the one on screen. Nothing used to close it, so the dialog kept showing a
    // run that no longer existed, with its model download still enabled — and clicking it answered
    // "SMV model not available (may be a record saved before model persistence was enabled)", blaming a
    // historical data limitation for a deletion the user had just performed. Measured end to end.
    // Surfaces derived from this run go too: `savedTraces` and any replay of its counterexamples are
    // evidence of a run that is gone.
    dismissRunSurfacesForDeletedVerificationRun(runId)
    notifySuccess(t('app.verificationRunDeleted'))
  } catch (e: any) {
    if (boardLifecycleDisposed) return
    console.error('Failed to delete verification run:', e)
    const refreshed = await loadVerificationRuns(false)
    if (boardLifecycleDisposed) return
    if (refreshed && !verificationRuns.value.some(item => item.id === runId)) {
      notifyBlocked(t('app.verificationRunDeleteOutcomeRefreshed'))
      return
    }
    notifyError(localizedErrorMessage(e, t('app.failedToDeleteVerificationRun'), locale.value))
  } finally {
    finishHistoryDelete(pendingKey)
  }
}

const openVerificationRun = async (
  runId: number,
  deepLinkLoad?: DeepLinkLoadContext
): Promise<boolean> => {
  const requestToken = historyDetailRequests.beginReplace()
  let runDetailLoaded = false
  try {
    const run = await boardApi.getVerificationRun(runId)
    if (!historyDetailRequests.isCurrent(requestToken) || boardLifecycleDisposed) return false
    runDetailLoaded = true
    void refreshCurrentBoardModelFingerprints()
    const traces = shouldLoadVerificationEvidence(run.counterexampleCount)
      ? await boardApi.getVerificationRunTraces(runId)
      : []
    if (!historyDetailRequests.isCurrent(requestToken) || boardLifecycleDisposed) return false
    verificationResult.value = buildVerificationResultFromRun(run, traces)
    verificationResultStale.value = false
    closeHistoryPanel(false)
    return true
  } catch (e: any) {
    if (!historyDetailRequests.isCurrent(requestToken) || boardLifecycleDisposed) return false
    console.error('Failed to load verification run:', e)
    if (isPersistedHistoryDataInvalid(e)) {
      // A complete-run view needs every trace, but one damaged child must not disable its
      // independently addressable siblings in the history panel.
      const traceId = persistedHistoryInvalidRecordId(e, 'verification trace')
      if (traceId !== null) markVerificationTraceUnavailable(traceId)
      else if (!runDetailLoaded) markVerificationRunUnavailable(runId)
      if (shouldClearUnusableHistoryDeepLink(isCurrentDeepLinkLoad(deepLinkLoad), e)) {
        reportUnusableDeepLink(deepLinkLoad)
      }
      notifyBlocked(t(runDetailLoaded
        ? 'app.historyRunHasUnavailableEvidenceDetail'
        : 'app.historyItemUnavailableDetail'))
      return false
    }
    if (shouldReportUnusableHistoryDeepLink(isCurrentDeepLinkLoad(deepLinkLoad), e)) {
      reportUnusableDeepLink(deepLinkLoad)
    }
    else notifyError(t('app.failedToLoadVerificationRun'))
    return false
  } finally {
    historyDetailRequests.finish(requestToken)
  }
}

const watchVerificationTask = async (taskId: number) => {
  if (isVerifying.value) {
    if (asyncVerificationTask.value.taskId === taskId) {
      showVerificationPanel.value = true
    } else {
      notifyInfo(t('app.taskWatchAlreadyActive'))
    }
    closeHistoryPanel()
    return
  }
  const taskSummary = verificationTasks.value.find(task => task.id === taskId)
  verificationError.value = null
  isVerifying.value = true
  asyncVerificationActive.value = true
  verificationCancelRequested.value = false
  cancellingVerificationTask.value = false
  asyncVerificationTask.value = {
    taskId,
    progress: normalizeTaskProgress(taskSummary?.progress),
    statusKey: taskProgressStatusKey(taskSummary?.progressStage, taskSummary?.status)
  }
  closeHistoryPanel()
  showVerificationPanel.value = true
  try {
    await pollAsyncVerification(taskId, { presentResult: true })
  } catch (error: any) {
    if (!isPollingAbortedError(error)) {
      if (isAsyncTaskCancelledError(error)) {
        verificationError.value = null
        if (!verificationCancelRequested.value) notifyInfo(t('app.verificationCancelled'))
      } else {
        presentRunFailure('verification', 'app.verificationFailed', error)
      }
    }
  } finally {
    isVerifying.value = false
    asyncVerificationActive.value = false
    cancellingVerificationTask.value = false
    verificationCancelRequested.value = false
    if (!boardLifecycleDisposed) {
      await loadTaskInbox({ showLoading: false })
    }
  }
}

const watchSimulationTask = async (taskId: number) => {
  if (isSimulating.value) {
    if (asyncSimulationTask.value.taskId === taskId) {
      showSimulationPanel.value = true
    } else {
      notifyInfo(t('app.taskWatchAlreadyActive'))
    }
    closeHistoryPanel()
    return
  }
  const taskSummary = simulationTasks.value.find(task => task.id === taskId)
  simulationError.value = null
  isSimulating.value = true
  asyncSimulationActive.value = true
  simulationCancelRequested.value = false
  cancellingSimulationTask.value = false
  asyncSimulationTask.value = {
    taskId,
    progress: normalizeTaskProgress(taskSummary?.progress),
    statusKey: taskProgressStatusKey(taskSummary?.progressStage, taskSummary?.status)
  }
  closeHistoryPanel()
  showSimulationPanel.value = true
  /*
   * Same exposure as a run started from the panel, and reached more often: watching a task from the
   * inbox is exactly when a user carries on editing. `handleSimulate` documents the mechanism.
   */
  const watchSubmissionSceneChanges = semanticSceneChangeCount
  try {
    const result = await pollAsyncSimulation(taskId)
    const summary = summarizeSimulationResult(result)
    if (summary) {
      simulationHistoryRequests.invalidate()
      simulationRuns.value = [summary, ...simulationRuns.value.filter(item => item.id !== summary.id)]
    }
    /*
     * Adopted only when this run is the one about to be shown, which is why the write sits below the
     * deferral branches rather than straight after the poll.
     *
     * `lastSimulationResult` owns the replay context and its derived states. Adopting an arriving run
     * would replace the visible trajectory, while the canvas still owns the opened run's frozen scene.
     * Playback admission permits replaying history while an async run is pending, so completion must
     * remain in history until that replay is closed.
     */
    if (traceAnimationState.value.visible || simulationAnimationState.value.visible) {
      notifySimulationOutcome(result, true)
      notifyAutomaticPlaybackDeferredForReplay(result, watchSubmissionSceneChanges)
      return
    }
    if (isLiveBoardEditorVisible.value) {
      notifySimulationOutcome(result, true)
      notifyAutomaticPlaybackDeferred()
      return
    }
    adoptSimulationRunResult(result, watchSubmissionSceneChanges)
    openSimulationAnimationFromSavedStates()
  } catch (error: any) {
    if (!isPollingAbortedError(error)) {
      if (isAsyncTaskCancelledError(error)) {
        simulationError.value = null
        if (!simulationCancelRequested.value) notifyInfo(t('app.simulationCancelled'))
      } else {
        presentRunFailure('simulation', 'app.simulationFailed', error)
      }
    }
  } finally {
    isSimulating.value = false
    asyncSimulationActive.value = false
    cancellingSimulationTask.value = false
    simulationCancelRequested.value = false
    if (!boardLifecycleDisposed) {
      await loadTaskInbox({ showLoading: false })
    }
  }
}

const upsertFuzzingRunSummary = (run: FuzzingRunSummary) => {
  fuzzingHistoryRequests.invalidate()
  const retainedRun = retainKnownFuzzingHistoryAvailability([run])[0]
  const existingIndex = fuzzingRuns.value.findIndex(item => item.id === retainedRun.id)
  if (existingIndex >= 0) {
    fuzzingRuns.value = fuzzingRuns.value.map(item => item.id === retainedRun.id ? retainedRun : item)
    return
  }
  const previousCount = fuzzingRuns.value.length
  fuzzingRuns.value = [retainedRun, ...fuzzingRuns.value].slice(0, FUZZ_RUN_HISTORY_PAGE_SIZE)
  fuzzingRunsPage.value = 0
  fuzzingRunsHasMore.value = fuzzingRunsHasMore.value || previousCount >= FUZZ_RUN_HISTORY_PAGE_SIZE
}

const summarizeFuzzingRun = (run: FuzzingRun): AvailableFuzzingRunSummary => ({
  ...run,
  dataAvailable: true,
  findings: run.findings.map(finding => ({
    id: finding.id,
    fuzzTaskId: finding.fuzzTaskId,
    violatedSpecId: finding.violatedSpecId,
    violatedSpec: finding.violatedSpec,
    specificationLabel: finding.violatedSpec.templateLabel
      || finding.violatedSpec.formula
      || finding.violatedSpecId,
    firstViolationStep: finding.firstViolationStep,
    seed: finding.seed,
    createdAt: finding.createdAt,
    stateCount: finding.states.length
  }))
})

const presentFuzzingRun = (run: FuzzingRun) => {
  // Transient notices must not cover the result's title or primary actions.
  dismissAllNotifications()
  const summary = summarizeFuzzingRun(run)
  upsertFuzzingRunSummary(summary)
  fuzzingError.value = null
  fuzzingResult.value = run
  showFuzzingResultDialog.value = true
  clearFuzzNotifications(undefined, run.id)
}

const watchFuzzingTask = async (taskId: number) => {
  if (isFuzzing.value) {
    if (asyncFuzzingTask.value.taskId === taskId) {
      showFuzzingPanel.value = true
    } else {
      notifyInfo(t('app.taskWatchAlreadyActive'))
    }
    closeHistoryPanel()
    return
  }

  const taskSummary = fuzzingTasks.value.find(task => task.id === taskId)
  // Keep the running request separate from the editable form. Its persisted summary is
  // the only truthful source after a refresh or after the current Board has changed.
  fuzzingWatchedTask.value = taskSummary || null
  fuzzingSettingsNotice.value = null
  isFuzzing.value = true
  asyncFuzzingActive.value = true
  fuzzingCancelRequested.value = false
  cancellingFuzzingTask.value = false
  fuzzingError.value = null
  paperDomainStaleRecoveryActive.value = false
  asyncFuzzingTask.value = {
    taskId,
    progress: normalizeTaskProgress(taskSummary?.progress),
    statusKey: taskProgressStatusKey(taskSummary?.progressStage, taskSummary?.status)
  }
  trackFuzzTask(taskId)
  closeHistoryPanel()
  showFuzzingPanel.value = true
  try {
    const run = await pollAsyncFuzzing(taskId)
    untrackFuzzTask(taskId)
    showFuzzingPanel.value = false
    // A result modal would trap focus away from the active replay; retain the run in the task inbox instead.
    if (isModelPlaybackActive.value) {
      const notificationShown = markFuzzNotificationUnread({
        taskId,
        runId: run.id,
        kind: 'COMPLETED',
        initiator: run.initiator,
        outcome: run.outcome,
        createdAt: run.completedAt
      })
      // The outcome first, then why it is not on screen — the same order `handleFuzzing` uses, so a
      // deferral does not read as a quieter kind of result. Its severity comes from
      // `fuzzingCompletionMessage`: candidate findings are not an informational aside, and
      // `BUDGET_EXHAUSTED` must never be dressed up as a clean run.
      if (notificationShown) {
        if (run.outcome === 'BUDGET_EXHAUSTED') notifyInfo(fuzzingCompletionMessage(run))
        else notifyBlocked(fuzzingCompletionMessage(run))
        notifyInfo(t('app.fuzzResultDeferredForPlayback'))
      }
      return
    }
    void refreshCurrentBoardModelFingerprints()
    presentFuzzingRun(run)
  } catch (error: any) {
    if (!isPollingAbortedError(error)) {
      if (isAsyncTaskCancelledError(error)) {
        untrackFuzzTask(taskId)
        fuzzingError.value = null
        if (!fuzzingCancelRequested.value) notifyInfo(t('app.fuzzSearchCancelled'))
      } else if (isFuzzTaskRecoveryPendingError(error)) {
        fuzzingError.value = null
        fuzzingSettingsNotice.value = { key: 'app.fuzzResultRecoveryPending' }
        if (!showFuzzingPanel.value) notifyInfo(fuzzingSettingsNoticeMessage.value)
      } else if (isFuzzCompletedResultUnavailableError(error)) {
        fuzzingError.value = makeRunFailure('app.failedToLoadFuzzingRun', error)
        markFuzzNotificationUnread({
          taskId,
          runId: taskId,
          kind: 'UNAVAILABLE',
          initiator: taskSummary?.initiator ?? 'UNKNOWN',
          createdAt: new Date().toISOString()
        })
      } else {
        console.error('Fuzz task watch failed:', error)
        fuzzingError.value = makeRunFailure('app.fuzzSearchFailed', error)
        if (showFuzzingPanel.value) {
          untrackFuzzTask(taskId)
        } else {
          markFuzzNotificationUnread({
            taskId,
            kind: 'FAILED',
            initiator: taskSummary?.initiator ?? 'UNKNOWN',
            createdAt: new Date().toISOString()
          })
          notifyError(fuzzingErrorMessage.value!)
        }
      }
    }
  } finally {
    isFuzzing.value = false
    asyncFuzzingActive.value = false
    cancellingFuzzingTask.value = false
    fuzzingCancelRequested.value = false
    fuzzingWatchedTask.value = null
    if (!boardLifecycleDisposed) {
      await loadTaskInbox({ showLoading: false })
    }
  }
}

const cancelVerificationTaskFromInbox = (taskId: number) => withTaskActionLock(
  taskActionKey('cancel', 'verification', taskId),
  async () => {
    try {
      if (asyncVerificationTask.value.taskId === taskId) {
        await cancelAsyncVerification()
      } else {
        const result = await boardApi.cancelTask(taskId)
        if (boardLifecycleDisposed) return
        notifyTaskCancellationResult('verification', result)
      }
    } catch (error) {
      if (boardLifecycleDisposed) return
      console.error('Failed to cancel verification task from inbox:', error)
      notifyError(t('app.failedToCancelVerificationTask'))
    } finally {
      if (!boardLifecycleDisposed) {
        await loadTaskInbox({ showLoading: false })
      }
    }
  }
)

const cancelSimulationTaskFromInbox = (taskId: number) => withTaskActionLock(
  taskActionKey('cancel', 'simulation', taskId),
  async () => {
    try {
      if (asyncSimulationTask.value.taskId === taskId) {
        await cancelAsyncSimulation()
      } else {
        const result = await simulationApi.cancelTask(taskId)
        if (boardLifecycleDisposed) return
        notifyTaskCancellationResult('simulation', result)
      }
    } catch (error) {
      if (boardLifecycleDisposed) return
      console.error('Failed to cancel simulation task from inbox:', error)
      notifyError(t('app.failedToCancelSimulationTask'))
    } finally {
      if (!boardLifecycleDisposed) {
        await loadTaskInbox({ showLoading: false })
      }
    }
  }
)

const cancelFuzzingTaskFromInbox = (taskId: number) => withTaskActionLock(
  taskActionKey('cancel', 'fuzzing', taskId),
  async () => {
    try {
      if (asyncFuzzingTask.value.taskId === taskId) {
        await cancelAsyncFuzzing()
      } else {
        const result = await fuzzingApi.cancelTask(taskId)
        if (boardLifecycleDisposed) return
        notifyTaskCancellationResult('fuzzing', result)
      }
    } catch (error) {
      if (boardLifecycleDisposed) return
      console.error('Failed to cancel fuzzing task from inbox:', error)
      notifyError(t('app.failedToCancelFuzzingTask'))
    } finally {
      if (!boardLifecycleDisposed) {
        await loadTaskInbox({ showLoading: false })
      }
    }
  }
)

const dismissVerificationTask = (taskId: number) => withTaskActionLock(
  taskActionKey('dismiss', 'verification', taskId),
  async () => {
    try {
      await boardApi.deleteTask(taskId)
      if (boardLifecycleDisposed) return
      invalidateTaskInboxRequests()
      verificationTasks.value = verificationTasks.value.filter(task => task.id !== taskId)
      notifySuccess(t('app.taskDismissed'))
    } catch (e: any) {
      if (boardLifecycleDisposed) return
      notifyError(extractApiErrorMessage(e, t('app.failedToDismissTask')))
      await loadTaskInbox({ showLoading: false })
    }
  }
)

const dismissSimulationTask = (taskId: number) => withTaskActionLock(
  taskActionKey('dismiss', 'simulation', taskId),
  async () => {
    try {
      await simulationApi.deleteTask(taskId)
      if (boardLifecycleDisposed) return
      invalidateTaskInboxRequests()
      simulationTasks.value = simulationTasks.value.filter(task => task.id !== taskId)
      notifySuccess(t('app.taskDismissed'))
    } catch (e: any) {
      if (boardLifecycleDisposed) return
      notifyError(extractApiErrorMessage(e, t('app.failedToDismissTask')))
      await loadTaskInbox({ showLoading: false })
    }
  }
)

const dismissFuzzingTask = (taskId: number) => withTaskActionLock(
  taskActionKey('dismiss', 'fuzzing', taskId),
  async () => {
    try {
      await fuzzingApi.deleteTask(taskId)
      if (boardLifecycleDisposed) return
      invalidateTaskInboxRequests()
      fuzzingTasks.value = fuzzingTasks.value.filter(task => task.id !== taskId)
      clearFuzzNotifications(undefined, taskId)
      untrackFuzzTask(taskId)
      notifySuccess(t('app.taskDismissed'))
    } catch (e: any) {
      if (boardLifecycleDisposed) return
      console.error('Failed to dismiss fuzzing task:', e)
      notifyError(t('app.failedToDismissTask'))
      await loadTaskInbox({ showLoading: false })
    }
  }
)

const openTaskInbox = async () => {
  if (isModelPlaybackActive.value) {
    notifyBlocked(t('app.playbackReadOnlyCloseFirst'))
    return
  }
  const intentEpoch = ++historyPanelIntentEpoch
  historyDetailRequests.invalidate()
  closeOtherWorkflowSurfaces()
  activeHistoryLayer.value = 'tasks'
  showHistoryPanel.value = true
  const loaded = await loadTaskInbox()
  if (loaded && isCurrentHistoryPanelIntent(intentEpoch, 'tasks')) {
    clearFuzzNotifications('FAILED')
  }
}

const miniTaskCancelLabel = (kind: 'verification' | 'fuzzing' | 'simulation') => kind === 'verification'
  ? t('app.cancelVerificationTask')
  : kind === 'fuzzing' ? t('app.cancelFuzzingTask') : t('app.cancelSimulationTask')

const cancelMiniTask = async (kind: 'verification' | 'fuzzing' | 'simulation', taskId: number) => {
  if (kind === 'verification') {
    await cancelVerificationTaskFromInbox(taskId)
  } else if (kind === 'fuzzing') {
    await cancelFuzzingTaskFromInbox(taskId)
  } else {
    await cancelSimulationTaskFromInbox(taskId)
  }
}

const ensureHistoricalPlaybackUiAdmission = (): boolean => {
  if (isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return false
  }
  if (traceAnimationState.value.visible) {
    notifyBlocked(t('app.closeCounterexampleFirst'))
    return false
  }
  if (simulationAnimationState.value.visible) {
    notifyBlocked(t('app.closeCurrentSimulationFirst'))
    return false
  }
  if (isAnyRecommendationPanelVisible()) {
    notifyBlocked(t('app.closeRecommendationPanelsFirst'))
    return false
  }
  return ensureLiveBoardEditorClosedForPlayback()
}

const revalidateHistoricalPlaybackAfterLoad = async (
  requestToken: PagedRequestToken,
  initialMutationEpoch: number
): Promise<boolean> => {
  const result = await revalidateHistoricalPlaybackAdmission({
    waitForPendingMutations: waitForPendingBoardMutations,
    isRequestCurrent: () => historyDetailRequests.isCurrent(requestToken) && !boardLifecycleDisposed,
    initialMutationEpoch,
    currentMutationEpoch: () => boardMutationAdmissionEpoch,
    recheckUiAdmission: ensureHistoricalPlaybackUiAdmission
  })
  if (result === 'board-changed') {
    notifyBlocked(t('app.historicalPlaybackDeferredForBoardChange'))
  }
  return result === 'admitted'
}

const selectAndPlayVerificationTrace = async (
  traceId: number,
  deepLinkLoad?: DeepLinkLoadContext,
  expectedRunId?: number
) => {
  if (!ensureHistoricalPlaybackUiAdmission()) return
  const initialMutationEpoch = boardMutationAdmissionEpoch
  const requestToken = historyDetailRequests.beginReplace()
  try {
    const trace = await boardApi.getVerificationTrace(traceId)
    if (!await revalidateHistoricalPlaybackAfterLoad(requestToken, initialMutationEpoch)) return
    if (expectedRunId !== undefined && !verificationTraceBelongsToRun(trace, expectedRunId)) {
      if (isCurrentDeepLinkLoad(deepLinkLoad)) reportUnusableDeepLink(deepLinkLoad)
      else notifyError(t('app.failedToLoadTrace'))
      return
    }
    if (!trace?.states?.length) {
      notifyBlocked(t('app.traceHasNoStates'))
      return
    }
    closeResultDialog()
    closeHistoryPanel(false)
    activeFuzzingFinding.value = null
    savedTraces.value = [trace]
    // Selects the violating state and writes both selection refs. A second `highlightedTrace` write here
    // used to reset it to state 0, contradicting the timeline.
    openTraceAnimationAt(0)
  } catch (e: any) {
    if (!historyDetailRequests.isCurrent(requestToken) || boardLifecycleDisposed) return
    console.error('Failed to load trace:', e)
    if (isPersistedHistoryDataInvalid(e)) {
      markVerificationTraceUnavailable(traceId)
      if (shouldClearUnusableHistoryDeepLink(isCurrentDeepLinkLoad(deepLinkLoad), e)) {
        reportUnusableDeepLink(deepLinkLoad)
      }
      notifyBlocked(t('app.historyTraceUnavailableDetail'))
      return
    }
    if (shouldReportUnusableHistoryDeepLink(isCurrentDeepLinkLoad(deepLinkLoad), e)) {
      reportUnusableDeepLink(deepLinkLoad)
    }
    else notifyError(t('app.failedToLoadTrace'))
  } finally {
    historyDetailRequests.finish(requestToken)
  }
}

const openFixForVerificationTrace = (trace: TraceSummary) => {
  const run = verificationRuns.value.find(item => item.id === trace.verificationTaskId)
  if (!trace.dataAvailable || !run?.dataAvailable
    || compareModelSnapshot(run.modelSnapshot, currentBoardModelFingerprints.value, 'verification') !== 'UNCHANGED') return
  if (!trace.violatedSpecId) {
    notifyBlocked(t('app.traceMissingViolatedSpec'))
    return
  }
  closeHistoryPanel()
  openFixDialog(trace.id, trace.violatedSpecId)
}

/**
 * Download the SMV model for a verification run.
 *
 * All counterexamples from one run share the same model, and a run where every spec holds has
 * no counterexample to key the download on — so the run-keyed endpoint makes the model of a
 * *passing* run downloadable, which is the case where confirming what was proved matters most.
 */
const downloadVerificationRunSmv = async (runId: number) => {
  try {
    await boardApi.downloadRunSmvModel(runId)
  } catch (error) {
    const status = (error as any)?.response?.status
    if (status === 404) {
      notifyBlocked(t('app.smvModelNotAvailable'))
    } else {
      notifyError(t('app.smvDownloadFailed'))
    }
  }
}

/**
 * Download the SMV model a simulation trajectory ran.
 *
 * Call sites pass `historyPersistence.runId`, which looks like a mismatch against the `traceId`
 * parameter and is not: a simulation trajectory *is* its own run, so the backend sets
 * `historyPersistence.runId` to the saved trace id, and `validateSimulationShape` rejects a response
 * where the two disagree. Verification is the case where they differ — a run owns many traces — which
 * is why only that side has a separate run-keyed endpoint.
 *
 * A 404 means the record or model is unavailable, including deletion after the list was loaded.
 */
const downloadSimulationTraceSmv = async (traceId: number) => {
  try {
    await simulationApi.downloadSimulationSmvModel(traceId)
  } catch (error) {
    const status = (error as any)?.response?.status
    if (status === 404) {
      notifyBlocked(t('app.smvModelNotAvailable'))
    } else {
      notifyError(t('app.smvDownloadFailed'))
    }
  }
}

const selectAndPlaySimulationTrace = async (
  traceId: number,
  deepLinkLoad?: DeepLinkLoadContext
) => {
  if (!ensureHistoricalPlaybackUiAdmission()) return
  const initialMutationEpoch = boardMutationAdmissionEpoch
  const requestToken = historyDetailRequests.beginReplace()
  try {
    const trace = await simulationApi.getSimulation(traceId)
    if (!await revalidateHistoricalPlaybackAfterLoad(requestToken, initialMutationEpoch)) return

    const result = simulationResultFromTrace(trace)

    closeHistoryPanel(false)
    lastSimulationResult.value = result
    // Staleness belongs to the run being shown. This is a freshly loaded historical run, so it cannot
    // have been invalidated by an edit made against an earlier one — without this, whether a historical
    // run showed the re-run banner depended on unrelated history. `openVerificationRun` clears its flag
    // for the same reason. The two run paths reach the same conclusion by comparison instead
    // (`adoptSimulationRunResult`): a run just executed is stale only if the scene moved while it ran,
    // whereas a stored trajectory has no such window. This is not written through that helper because
    // the comparison it performs is exactly what does not apply here.
    simulationResultStale.value = false
    openSimulationAnimationFromSavedStates()
  } catch (e: any) {
    if (!historyDetailRequests.isCurrent(requestToken) || boardLifecycleDisposed) return
    console.error('Failed to load simulation trace:', e)
    if (isPersistedHistoryDataInvalid(e)) {
      markSimulationTraceUnavailable(traceId)
      if (shouldClearUnusableHistoryDeepLink(isCurrentDeepLinkLoad(deepLinkLoad), e)) {
        reportUnusableDeepLink(deepLinkLoad)
      }
      notifyBlocked(t('app.historyItemUnavailableDetail'))
      return
    }
    if (shouldReportUnusableHistoryDeepLink(isCurrentDeepLinkLoad(deepLinkLoad), e)) {
      reportUnusableDeepLink(deepLinkLoad)
    }
    else notifyError(t('app.failedToLoadSimulationRun'))
  } finally {
    historyDetailRequests.finish(requestToken)
  }
}

const deleteSimulationRun = async (run: SimulationTraceSummary) => {
  const traceId = run.id
  const pendingKey = `simulation:${traceId}`
  if (!beginHistoryDelete(pendingKey)) return
  try {
    if (!await confirmHistoryDeletion(
      () => confirmDestructive({
        title: t('app.deleteSimulationRunTitle'),
        message: t('app.deleteSimulationRunMessage', { time: formatRunTimestamp(run.createdAt) }),
        confirmText: t('app.delete')
      }),
      historyDetailRequests.invalidate
    )) return
    await simulationApi.deleteSimulation(traceId)
    if (boardLifecycleDisposed) return
    // No surface teardown here, unlike `deleteVerificationRun`: reaching this line means the history panel
    // is open, and the history panel button is disabled while `isModelPlaybackActive`, so the run
    // being deleted cannot be the one on screen. The reachable paths — the assistant's
    // `DeleteSimulationTraceTool` and another tab — arrive as a history reload and are handled by
    // `reconcileOpenSimulationRunAgainstHistory`.
    simulationHistoryRequests.invalidate()
    unavailableSimulationTraceIds.delete(traceId)
    simulationRuns.value = simulationRuns.value.filter(t => t.id !== traceId)
    notifySuccess(t('app.simulationRunDeleted'))
  } catch (e: any) {
    if (boardLifecycleDisposed) return
    console.error('Failed to delete simulation trace:', e)
    const refreshed = await loadSimulationRuns(false)
    if (boardLifecycleDisposed) return
    if (refreshed && !simulationRuns.value.some(item => item.id === traceId)) {
      notifyBlocked(t('app.simulationDeleteOutcomeRefreshed'))
      return
    }
    notifyError(localizedErrorMessage(e, t('app.failedToDeleteSimulationRun'), locale.value))
  } finally {
    finishHistoryDelete(pendingKey)
  }
}

const fuzzingCompletionMessage = (run: AvailableFuzzingRunSummary | FuzzingRun): string => {
  if (run.outcome === 'FOUND_VIOLATION') {
    return t('app.fuzzSearchCompletedWithFindings', { count: run.findings.length })
  }
  if (run.outcome === 'BUDGET_EXHAUSTED') return t('app.fuzzNoViolationWithinBudget')
  return t('app.fuzzInconclusiveDetail')
}

const openFuzzingRun = async (
  runId: number,
  deepLinkLoad?: DeepLinkLoadContext
): Promise<boolean> => {
  if (isModelPlaybackActive.value) {
    notifyBlocked(t('app.playbackReadOnlyCloseFirst'))
    return false
  }
  const requestEpoch = ++fuzzingResultRequestEpoch
  const requestToken = historyDetailRequests.beginReplace()
  try {
    fuzzingError.value = null
    fuzzingResult.value = null
    fuzzingResultLoading.value = true
    showFuzzingResultDialog.value = true
    // Its ready event dismisses history after the lazy dialog captures its opener.
    await nextTick()
    if (requestEpoch !== fuzzingResultRequestEpoch
      || !historyDetailRequests.isCurrent(requestToken)
      || boardLifecycleDisposed) return false
    const [run] = await Promise.all([
      fuzzingApi.getRun(runId),
      refreshCurrentBoardModelFingerprints()
    ])
    if (requestEpoch !== fuzzingResultRequestEpoch
      || !historyDetailRequests.isCurrent(requestToken)
      || boardLifecycleDisposed) return false
    presentFuzzingRun(run)
    return true
  } catch (e: any) {
    if (requestEpoch !== fuzzingResultRequestEpoch
      || !historyDetailRequests.isCurrent(requestToken)
      || boardLifecycleDisposed) return false
    console.error('Failed to load fuzzing run:', e)
    showFuzzingResultDialog.value = false
    fuzzingError.value = null
    if (isPersistedHistoryDataInvalid(e)) {
      const findingId = persistedHistoryInvalidRecordId(e, 'FuzzFinding')
      if (findingId !== null) {
        markFuzzingFindingUnavailable(findingId)
      } else {
        markFuzzingRunUnavailable(runId)
      }
      if (shouldClearUnusableHistoryDeepLink(isCurrentDeepLinkLoad(deepLinkLoad), e)) {
        reportUnusableDeepLink(deepLinkLoad)
      }
      notifyBlocked(t(findingId !== null
        ? 'app.historyRunHasUnavailableFindingDetail'
        : 'app.historyItemUnavailableDetail'))
      return false
    }
    if (shouldReportUnusableHistoryDeepLink(isCurrentDeepLinkLoad(deepLinkLoad), e)) {
      reportUnusableDeepLink(deepLinkLoad)
    }
    else notifyError(t('app.failedToLoadFuzzingRun'))
    return false
  } finally {
    if (requestEpoch === fuzzingResultRequestEpoch) {
      fuzzingResultLoading.value = false
      if (!historyDetailRequests.isCurrent(requestToken) && !fuzzingResult.value) {
        showFuzzingResultDialog.value = false
      }
    }
    historyDetailRequests.finish(requestToken)
  }
}

/**
 * Hides the exploration result surface without touching the URL, so it can serve as an
 * internal transition (opening a finding replay). `dismissFuzzingResult` is the user-facing
 * close that also clears the deep link.
 */
const closeFuzzingResult = () => {
  fuzzingResultRequestEpoch = invalidateFuzzingResultRequests(
    fuzzingResultRequestEpoch,
    historyDetailRequests.invalidate
  )
  showFuzzingResultDialog.value = false
  fuzzingResult.value = null
  fuzzingError.value = null
  fuzzingResultLoading.value = false
}

const dismissFuzzingResult = () => {
  closeFuzzingResult()
  clearRunDeepLink()
}

const deleteFuzzingRun = async (run: FuzzingRunSummary) => {
  const pendingKey = `fuzzing:${run.id}`
  if (!beginHistoryDelete(pendingKey)) return
  try {
    if (!await confirmHistoryDeletion(
      () => confirmDestructive({
        title: t('app.deleteFuzzingRunTitle'),
        message: t('app.deleteFuzzingRunMessage', { time: formatRunTimestamp(run.completedAt || run.createdAt) }),
        confirmText: t('app.delete')
      }),
      historyDetailRequests.invalidate
    )) return
    await fuzzingApi.deleteRun(run.id)
    if (boardLifecycleDisposed) return
    fuzzingHistoryRequests.invalidate()
    unavailableFuzzingRunIds.delete(run.id)
    for (const finding of run.findings) {
      unavailableFuzzingFindingIds.delete(finding.id)
    }
    fuzzingRuns.value = fuzzingRuns.value.filter(item => item.id !== run.id)
    fuzzingRunsPage.value = 0
    fuzzingRunsHasMore.value = false
    const refreshed = await loadFuzzingRuns(false)
    if (boardLifecycleDisposed) return
    // `dismissFuzzingResult`, not `closeFuzzingResult`: this is a user-facing close, so the
    // `?run=exploration:<id>` deep link goes with it. `closeFuzzingResult` documents itself as leaving
    // the URL alone — it exists for internal transitions such as opening a finding replay — so using it
    // here left the deleted run's id in the URL, where the deep-link watcher would reload it and answer
    // with the unusable-link banner. The verification path already draws this distinction
    // (`dismissRunSurfacesForDeletedVerificationRun` calls `dismissResultDialog` for the same reason).
    if (fuzzingResult.value?.id === run.id) dismissFuzzingResult()
    if (!refreshed) {
      notifyBlocked(t('app.fuzzingRunDeletedRefreshPending'))
      return
    }
    notifySuccess(t('app.fuzzingRunDeleted'))
  } catch (e: any) {
    if (boardLifecycleDisposed) return
    console.error('Failed to delete fuzzing run:', e)
    const refreshed = await loadFuzzingRuns(false)
    if (boardLifecycleDisposed) return
    if (refreshed && !fuzzingRuns.value.some(item => item.id === run.id)) {
      notifyBlocked(t('app.fuzzingDeleteOutcomeRefreshed'))
      return
    }
    notifyError(t('app.failedToDeleteFuzzingRun'))
  } finally {
    finishHistoryDelete(pendingKey)
  }
}

const selectAndPlayFuzzingFinding = async (
  findingId: number,
  runId: number,
  deepLinkLoad?: DeepLinkLoadContext
) => {
  if (!ensureHistoricalPlaybackUiAdmission()) return
  const initialMutationEpoch = boardMutationAdmissionEpoch
  const requestToken = historyDetailRequests.beginReplace()
  try {
    const replay = await fuzzingApi.getFinding(findingId)
    if (!await revalidateHistoricalPlaybackAfterLoad(requestToken, initialMutationEpoch)) return
    if (!fuzzingFindingBelongsToRun(replay.finding, runId)) {
      if (isCurrentDeepLinkLoad(deepLinkLoad)) reportUnusableDeepLink(deepLinkLoad)
      else notifyError(t('app.failedToLoadFuzzingFinding'))
      return
    }
    const finding = replay.finding
    if (!finding.states.length) {
      notifyBlocked(t('app.fuzzFindingHasNoStates'))
      return
    }

    const trace: Trace = {
      violatedSpecId: finding.violatedSpecId,
      violatedSpec: finding.violatedSpec,
      checkedExpression: '',
      modelComplete: false,
      disabledRuleCount: 0,
      skippedSpecCount: 0,
      generationIssues: [],
      states: finding.states,
      modelSnapshot: replay.modelSnapshot,
      playbackScene: replay.playbackScene,
      createdAt: finding.createdAt
    }

    closeHistoryPanel(false)
    closeFuzzingResult()
    activeFuzzingFinding.value = finding
    savedTraces.value = [trace]
    openTraceAnimationAt(0)
    // Open on the step the finding names, and let `goToState` write both selection refs. Previously this
    // forced state 0 while `openTraceAnimationAt` had selected the final state, so the changes tab and
    // the timeline disagreed and neither pointed at the violation.
    //
    // For a well-formed finding this lands where `openTraceAnimationAt` already did: the backend truncates
    // the finding's path at the violation (`FuzzEngine` keeps `states.subList(0, violationStep + 1)`), and
    // both the write path (`FuzzServiceImpl.validateEngineResult`) and the read path
    // (`FuzzMapper`, "stored trace is not truncated at the first violation") reject a finding where
    // `firstViolationStep != states.size() - 1`. `docs/api/fuzzing.md` documents it as always
    // `states.length - 1`. This used to claim the opposite — that an exploration path "can continue past
    // the violation" — which would make the two calls disagree by design. Kept as an explicit seek anyway,
    // because the step is the finding's own authoritative field and this surface should honour it rather
    // than re-derive it from the array length.
    if (typeof finding.firstViolationStep === 'number') {
      goToState(finding.firstViolationStep)
    }
  } catch (e: any) {
    if (!historyDetailRequests.isCurrent(requestToken) || boardLifecycleDisposed) return
    console.error('Failed to load fuzzing finding:', e)
    if (runId && isPersistedHistoryDataInvalid(e)) {
      const persistedFindingId = persistedHistoryInvalidRecordId(e, 'FuzzFinding')
      if (persistedFindingId !== null) {
        markFuzzingFindingUnavailable(persistedFindingId)
      } else {
        markFuzzingRunUnavailable(runId)
      }
      if (shouldClearUnusableHistoryDeepLink(isCurrentDeepLinkLoad(deepLinkLoad), e)) {
        reportUnusableDeepLink(deepLinkLoad)
      }
      notifyBlocked(t(persistedFindingId !== null
        ? 'app.historyFindingUnavailableDetail'
        : 'app.historyItemUnavailableDetail'))
      return
    }
    if (shouldReportUnusableHistoryDeepLink(isCurrentDeepLinkLoad(deepLinkLoad), e)) {
      reportUnusableDeepLink(deepLinkLoad)
    } else {
      notifyError(t('app.failedToLoadFuzzingFinding'))
    }
  } finally {
    historyDetailRequests.finish(requestToken)
  }
}

type FuzzVerificationHandoff = {
  runId: number
  specificationId?: string
  specificationLabel?: string
  targetPresent: boolean
  boardDrifted: boolean
}

const fuzzVerificationHandoff = ref<FuzzVerificationHandoff | null>(null)

const availableFuzzRunForFinding = (finding: FuzzingFindingSummary | FuzzingFinding) => {
  if ('dataAvailable' in finding && finding.dataAvailable === false) return null
  if (fuzzingResult.value?.id === finding.fuzzTaskId) return fuzzingResult.value
  const historyRun = fuzzingRuns.value.find(run => run.id === finding.fuzzTaskId)
  return historyRun?.dataAvailable ? historyRun : null
}

/**
 * Hand off from candidate evidence to the formal verifier.
 *
 * The two openers (from a finding, and for the current board) differ only in the handoff they build; the five
 * lines that actually perform the handoff were identical in both. Callers set `fuzzVerificationHandoff` and
 * then call this, so the surfaces a handoff has to leave behind are decided once.
 */
const showVerificationPanelForHandoff = () => {
  closeHistoryPanel()
  dismissFuzzingResult()
  showSimulationPanel.value = false
  showFuzzingPanel.value = false
  showVerificationPanel.value = true
}

const openFormalVerificationForFuzzFinding = (finding: FuzzingFindingSummary | FuzzingFinding) => {
  const sourceRun = availableFuzzRunForFinding(finding)
  const specificationLabel = finding.violatedSpec?.templateLabel
    || finding.violatedSpec?.formula
    || ('specificationLabel' in finding ? finding.specificationLabel : '')
    || finding.violatedSpecId
  fuzzVerificationHandoff.value = {
    runId: finding.fuzzTaskId,
    specificationId: finding.violatedSpecId,
    specificationLabel,
    targetPresent: specifications.value.some(spec => spec.id === finding.violatedSpecId),
    boardDrifted: sourceRun ? fuzzRunHasBoardDrift(sourceRun) : true
  }
  showVerificationPanelForHandoff()
}

const openFormalVerificationForCurrentBoard = () => {
  const sourceRun = fuzzingResult.value
  fuzzVerificationHandoff.value = sourceRun ? {
    runId: sourceRun.id,
    targetPresent: true,
    boardDrifted: fuzzingResultBoardDrifted.value
  } : null
  showVerificationPanelForHandoff()
}

const closeVerificationPanel = () => {
  showVerificationPanel.value = false
  fuzzVerificationHandoff.value = null
}

/**
 * Reset the workspace to a clean state.
 *
 * The logo button lives in the board header regardless of what's open or which deep-link the URL holds,
 * and it's visually interactive (cursor, hover fade). Users who click it expect *something*, not a
 * no-op. Pushing to `/board` when already there does nothing — Vue Router skips same-route navigation,
 * the component doesn't remount, overlays stay open, and the URL's deep-link state persists.
 *
 * This gives the click a purpose: close every floating overlay and clear the deep-link target, leaving
 * a clean board. It's an escape hatch — "get me back to just the canvas" — which is exactly what "click
 * the logo to go home" means in a single-page workspace app where the board *is* home.
 */
const resetWorkspace = () => {
  // Close playback overlays (they block other interactions)
  if (traceAnimationState.value.visible) closeTraceAnimation()
  if (simulationAnimationState.value.visible) closeSimulationTimeline()
  // Close floating tool panels
  if (showVerificationPanel.value) closeVerificationPanel()
  if (showSimulationPanel.value) closeSimulationPanel()
  // Clear deep-link target (so reopening a panel starts fresh rather than resuming the linked run)
  if (route.query.run || route.query.trace || route.query.finding) {
    void router.replace({ query: applyBoardRunTarget(route.query, null) })
  }
}

const reuseFuzzingSettings = () => {
  const run = fuzzingResult.value
  if (!run) return
  const eligibleCurrentIds = new Set(knownFuzzEligibleSpecifications.value.map(spec => spec.id))
  const usedFrozenAllTargets = run.targetSpecIds.length === 0
  const historicalTargetIds = usedFrozenAllTargets
    ? [...new Set([
        ...run.eligibility.eligibleSpecIds,
        ...run.eligibility.ineligibleSpecs.map(issue => issue.specId)
      ])]
    : [...run.targetSpecIds]
  const unavailableTargetCount = historicalTargetIds.filter(id => !eligibleCurrentIds.has(id)).length
  fuzzingForm.explorationMode = run.explorationMode
  fuzzingForm.maxIterations = run.maxIterations
  fuzzingForm.pathLength = run.pathLength
  fuzzingForm.populationSize = run.populationSize
  fuzzingForm.seed = run.effectiveSeed
  fuzzingForm.targetSelectionMode = 'EXPLICIT'
  // Keep unavailable explicit IDs visible so the user, not filtering code, chooses a replacement scope.
  fuzzingForm.targetSpecIds = historicalTargetIds
  fuzzingSettingsNotice.value = usedFrozenAllTargets
    ? { key: 'app.fuzzSettingsReusedAllTargets' }
    : unavailableTargetCount > 0
      ? { key: 'app.fuzzSettingsReusedWithMissingTargets', params: { count: unavailableTargetCount } }
      : { key: 'app.fuzzSettingsReused' }
  dismissFuzzingResult()
  closeHistoryPanel()
  showSimulationPanel.value = false
  showVerificationPanel.value = false
  showFuzzingPanel.value = true
}

// Floating panel visibility state
const showVerificationPanel = ref(false)
const verificationActionButtonRef = ref<HTMLButtonElement | null>(null)

// 异步模拟任务状态
const asyncSimulationTask = ref<{
  taskId: number | null
  progress: number
  statusKey: string
}>({
  taskId: null,
  progress: 0,
  statusKey: 'app.taskInitializing'
})
const asyncSimulationActive = ref(false)
const synchronousVerificationRunning = computed(() =>
  isVerifying.value && !asyncVerificationActive.value)
const synchronousSimulationRunning = computed(() =>
  isSimulating.value && !asyncSimulationActive.value)
const cancellingSimulationTask = ref(false)
const simulationCancelRequested = ref(false)

const notifyTaskCancellationResult = (
  kind: 'verification' | 'fuzzing' | 'simulation',
  result: TaskCancellationResult
) => {
  const task = t(kind === 'verification'
    ? 'app.verificationTaskName'
    : kind === 'fuzzing' ? 'app.fuzzTaskName' : 'app.simulationTaskName')
  const statusVisible = (showHistoryPanel.value && activeHistoryLayer.value === 'tasks')
    || (kind === 'verification' ? showVerificationPanel.value
      : kind === 'fuzzing' ? showFuzzingPanel.value : showSimulationPanel.value)
  switch (result.cancellationOutcome) {
    case 'ACCEPTED':
      if (statusVisible) return
      notifyInfo(t(result.executionMayStillBeStopping
          ? 'app.taskCancellationAcceptedStopping'
          : 'app.taskCancellationAccepted', { task }))
      break
    case 'ALREADY_CANCELLED':
      if (statusVisible) return
      notifyInfo(t('app.taskAlreadyCancelled', { task }))
      break
    case 'ALREADY_COMPLETED':
      if (kind === 'verification' && showResultDialog.value
        && verificationResult.value?.historyPersistence.status === 'SAVED'
        && verificationResult.value.historyPersistence.runId === result.taskId) return
      if (kind === 'simulation' && simulationAnimationState.value.visible
        && lastSimulationResult.value?.historyPersistence.status === 'SAVED'
        && simulationTasks.value.some(task => task.id === result.taskId
          && task.simulationTraceId === lastSimulationResult.value?.historyPersistence.runId)) return
      notifyBlocked(t('app.taskAlreadyCompleted', { task }))
      break
    case 'ALREADY_FAILED':
      notifyBlocked(t('app.taskAlreadyFailed', { task }))
      break
    default:
      notifyBlocked(t('app.taskCancellationNotAccepted', { task }))
  }
}

// Floating panel visibility state
const showSimulationPanel = ref(false)
const closeSimulationPanel = () => {
  showSimulationPanel.value = false
}

// These are non-modal tool panels: the canvas behind them stays live and focus is
// deliberately not trapped, so they render as `role="region"` rather than
// `role="dialog"`. `useModalAccessibility` is reused here only for Escape-to-close and
// focus restoration. Anything that claims `role="dialog"` + `aria-modal="true"` must
// keep `trapFocus` on.
const floatingPanelAccessibility = { trapFocus: false } as const
const recommendationPanelAccessibility = {
  trapFocus: false,
  shouldRestoreFocus: () => !isAnyRecommendationPanelVisible()
} as const
const {
  setDialogRef: setVerificationPanelRef,
  handleModalKeydown: handleVerificationPanelKeydown
} = useModalAccessibility(
  showVerificationPanel,
  closeVerificationPanel,
  () => verificationActionButtonRef.value,
  floatingPanelAccessibility
)
const {
  setDialogRef: setSimulationPanelRef,
  handleModalKeydown: handleSimulationPanelKeydown
} = useModalAccessibility(
  showSimulationPanel,
  closeSimulationPanel,
  () => document.querySelector<HTMLElement>('[data-testid="open-simulation-panel"]'),
  floatingPanelAccessibility
)
const {
  setDialogRef: setRuleRecommendationPanelRef,
  handleModalKeydown: handleRuleRecommendationPanelKeydown
} = useModalAccessibility(
  showRecommendationPanel,
  closeRecommendationPanel,
  () => document.querySelector<HTMLElement>('[data-testid="open-rule-recommendations"]'),
  recommendationPanelAccessibility
)
const {
  setDialogRef: setDeviceRecommendationPanelRef,
  handleModalKeydown: handleDeviceRecommendationPanelKeydown
} = useModalAccessibility(
  showDeviceRecommendationPanel,
  closeDeviceRecommendationPanel,
  () => document.querySelector<HTMLElement>('[data-testid="open-device-recommendations"]'),
  recommendationPanelAccessibility
)
const {
  setDialogRef: setSpecRecommendationPanelRef,
  handleModalKeydown: handleSpecRecommendationPanelKeydown
} = useModalAccessibility(
  showSpecRecommendationPanel,
  closeSpecRecommendationPanel,
  () => document.querySelector<HTMLElement>('[data-testid="open-spec-recommendations"]'),
  recommendationPanelAccessibility
)
const {
  setDialogRef: setScenarioRecommendationPanelRef,
  handleModalKeydown: handleScenarioRecommendationPanelKeydown
} = useModalAccessibility(
  showScenarioRecommendationPanel,
  closeScenarioRecommendationPanel,
  () => document.querySelector<HTMLElement>('[data-testid="open-scenario-recommendations"]'),
  recommendationPanelAccessibility
)

// Fix dialog 状态
const showFixDialog = ref(false)
const fixTraceId = ref<number | null>(null)
const fixViolatedSpecId = ref<string>('')

// 打开 Fix 弹窗
const openFixDialog = (traceId: number, violatedSpecId: string) => {
  if (fixResultDialogRef.value?.canOpenTrace?.(traceId) === false) {
    notifyBlocked(t('app.fixTraceSwitchBlockedByActiveSearch'))
    showFixDialog.value = true
    return
  }
  fixTraceId.value = traceId
  fixViolatedSpecId.value = violatedSpecId
  dismissCompletionNotifications()
  showFixDialog.value = true
}

const verificationFixBlockedReason = computed(() => {
  if (verificationResultStale.value || verificationBoardComparison.value === 'CHANGED') {
    return t('app.verificationResultStaleReverify')
  }
  if (verificationBoardComparison.value === 'UNCHANGED') return null
  if (verificationBoardComparison.value === 'UNAVAILABLE') {
    return t(loadingCurrentBoardModelFingerprints.value
      ? 'app.runBoardComparisonLoading' : 'app.runBoardComparisonUnavailable')
  }
  return t('app.runBoardNotCompared')
})

const canFixVerificationResultTrace = (trace: Trace): trace is PersistedTrace => (
  verificationFixBlockedReason.value === null
  && hasPersistedVerificationTrace(verificationResult.value, trace)
)

const openFixForVerificationResultTrace = (trace: Trace) => {
  if (!canFixVerificationResultTrace(trace)) return
  openFixDialog(trace.id, trace.violatedSpecId)
}

const cancelAsyncVerification = async () => {
  const taskId = asyncVerificationTask.value.taskId
  if (!taskId || cancellingVerificationTask.value) return

  verificationCancelRequested.value = true
  cancellingVerificationTask.value = true
  try {
    const result = await boardApi.cancelTask(taskId)
    if (boardLifecycleDisposed) return
    notifyTaskCancellationResult('verification', result)
    if (asyncVerificationTask.value.taskId === taskId) {
      asyncVerificationTask.value.statusKey = taskProgressStatusKey(undefined, result.taskStatus)
    }
    if (result.cancellationAccepted || result.cancellationOutcome === 'ALREADY_CANCELLED') {
      verificationCancelRequested.value = true
    } else {
      verificationCancelRequested.value = false
    }
  } catch (error: any) {
    if (boardLifecycleDisposed) return
    verificationCancelRequested.value = false
    const msg = localizedErrorMessage(error, t('app.failedToCancelVerificationTask'), locale.value)
    await acknowledgeFailure({ title: t('app.failedToCancelVerificationTask'), message: msg,
      suggestion: t('app.cancelFailureNextStep'), ...errorDetails(error) })
  } finally {
    cancellingVerificationTask.value = false
  }
}

const cancelAsyncSimulation = async () => {
  const taskId = asyncSimulationTask.value.taskId
  if (!taskId || cancellingSimulationTask.value) return

  simulationCancelRequested.value = true
  cancellingSimulationTask.value = true
  try {
    const result = await simulationApi.cancelTask(taskId)
    if (boardLifecycleDisposed) return
    notifyTaskCancellationResult('simulation', result)
    if (asyncSimulationTask.value.taskId === taskId) {
      asyncSimulationTask.value.statusKey = taskProgressStatusKey(undefined, result.taskStatus)
    }
    if (result.cancellationAccepted || result.cancellationOutcome === 'ALREADY_CANCELLED') {
      simulationCancelRequested.value = true
    } else {
      simulationCancelRequested.value = false
    }
  } catch (error: any) {
    if (boardLifecycleDisposed) return
    simulationCancelRequested.value = false
    const msg = localizedErrorMessage(error, t('app.failedToCancelSimulationTask'), locale.value)
    await acknowledgeFailure({ title: t('app.failedToCancelSimulationTask'), message: msg,
      suggestion: t('app.cancelFailureNextStep'), ...errorDetails(error) })
  } finally {
    cancellingSimulationTask.value = false
  }
}

const cancelAsyncFuzzing = async () => {
  const taskId = asyncFuzzingTask.value.taskId
  if (!taskId || cancellingFuzzingTask.value) return

  fuzzingCancelRequested.value = true
  cancellingFuzzingTask.value = true
  try {
    const result = await fuzzingApi.cancelTask(taskId)
    if (boardLifecycleDisposed) return
    notifyTaskCancellationResult('fuzzing', result)
    if (asyncFuzzingTask.value.taskId === taskId) {
      asyncFuzzingTask.value.statusKey = taskProgressStatusKey(undefined, result.taskStatus)
    }
    fuzzingCancelRequested.value = result.cancellationAccepted
      || result.cancellationOutcome === 'ALREADY_CANCELLED'
  } catch (error: any) {
    if (boardLifecycleDisposed) return
    fuzzingCancelRequested.value = false
    console.error('Failed to cancel fuzzing task:', error)
    await acknowledgeFailure({ title: t('app.failedToCancelFuzzingTask'),
      message: localizedErrorMessage(error, t('app.failedToCancelFuzzingTask'), locale.value),
      suggestion: t('app.cancelFailureNextStep'), ...errorDetails(error) })
  } finally {
    cancellingFuzzingTask.value = false
  }
}

// Fix 应用后的回调
let pendingFixRefreshPromise: Promise<boolean> | null = null

const handleFixApplied = (result: FixApplyResult) => {
  const resultAtApply = verificationResult.value
  const authScopeEpoch = boardAuthScopeEpoch
  // A fix is one user action even when it edits/removes several rules. The server journals the
  // complete ordered rule collection, so availability comes from the committed response.
  const refreshPromise = enqueueBoardMutation(async () => {
    commitSemanticScene({ rules: result.rules, availability: result })
    return true
  })
  pendingFixRefreshPromise = refreshPromise
  const clearPendingRefresh = () => {
    if (pendingFixRefreshPromise === refreshPromise) {
      pendingFixRefreshPromise = null
    }
  }
  void refreshPromise.then(() => {
    clearPendingRefresh()
    if (!isCurrentBoardAuthScope(authScopeEpoch)) return
    // The old verdict remains open; show its apply receipt inline instead of covering its header.
    if (showResultDialog.value && verificationResult.value === resultAtApply) verificationFixApplied.value = true
    else notifySuccess(t('app.fixAppliedWithSignedEvidence'))
  }, clearPendingRefresh)
}

const handleFixOutcomeUncertain = async () => {
  const refreshPromise = enqueueBoardMutation(() => refreshRules())
  pendingFixRefreshPromise = refreshPromise
  try {
    const refreshed = await refreshPromise
    await reloadUndoAvailability()
    notifyBlocked(refreshed
      ? t('app.fixApplyOutcomeUnconfirmedAfterRefresh')
      : t('app.fixApplyOutcomeUnknownRefreshFailed'))
  } finally {
    if (pendingFixRefreshPromise === refreshPromise) {
      pendingFixRefreshPromise = null
    }
  }
}

const waitForPendingFixRefresh = async () => {
  if (pendingFixRefreshPromise) {
    await pendingFixRefreshPromise
  }
}

// 面板互斥切换函数
const togglePanel = (panel: 'simulation' | 'fuzzing' | 'verification') => {
  if (isModelPlaybackActive.value) {
    notifyBlocked(t('app.playbackReadOnlyCloseFirst'))
    return
  }
  if (isAnyRecommendationRunning()) {
    notifyBlocked(t('app.recommendationGenerationInProgress'))
    return
  }

  dismissCompletionNotifications()
  closeResultSurfaces()
  closeHistoryPanel()
  closeRecommendationPanel()
  closeDeviceRecommendationPanel()
  closeSpecRecommendationPanel()
  closeScenarioRecommendationPanel()
  
  if (panel === 'simulation') {
    if (showSimulationPanel.value) {
      showSimulationPanel.value = false
    } else {
      showSimulationPanel.value = true
      showFuzzingPanel.value = false
      showVerificationPanel.value = false
    }
  } else if (panel === 'fuzzing') {
    if (showFuzzingPanel.value) {
      showFuzzingPanel.value = false
    } else {
      showFuzzingPanel.value = true
      showSimulationPanel.value = false
      showVerificationPanel.value = false
    }
  } else {
    if (showVerificationPanel.value) {
      showVerificationPanel.value = false
    } else {
      showVerificationPanel.value = true
      showSimulationPanel.value = false
      showFuzzingPanel.value = false
    }
  }
}

const openSimulationFromActionDock = () => {
  togglePanel('simulation')
}

const openVerificationFromActionDock = () => {
  fuzzVerificationHandoff.value = null
  togglePanel('verification')
}

const openFuzzingFromActionDock = () => {
  if (isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return
  }
  // Dismiss earlier scene/task notices before placing a tool panel beneath them.
  dismissAllNotifications()
  if (!isFuzzing.value) fuzzingWatchedTask.value = null
  fuzzingSettingsNotice.value = null
  togglePanel('fuzzing')
}

/**
 * Route a failed run in Task Status back to the panel that owns launching it.
 *
 * Reuses the action-dock openers so the panel's own guards still apply (playback lock, scene
 * replacement, a running recommendation) and so `togglePanel` performs its usual transition,
 * including closing the history panel. Not a re-submit: the board may have changed since the run,
 * so the user confirms the settings against the current scene.
 */
const reopenTaskSettings = (kind: 'verification' | 'fuzzing' | 'simulation') => {
  if (kind === 'fuzzing') openFuzzingFromActionDock()
  else if (kind === 'simulation') openSimulationFromActionDock()
  else openVerificationFromActionDock()
}

const openHistoryFromActionDock = () => {
  if (unreadFuzzNotificationCount.value > 0) {
    const layer: HistoryLayer = unreadFailedFuzzCount.value > 0 ? 'tasks' : 'results'
    if (layer === 'results') activeHistoryResultFilter.value = 'fuzzing'
    void toggleHistoryPanel(layer)
    return
  }
  // Land on the layer that has something to show. "Task Status" tracks work still in flight, so
  // with nothing running it renders "No tasks need attention" -- which reads as "no results" to
  // someone who just finished a run and came here to read the verdict. Reviewing a real VIOLATED
  // run this way, the completed result was one unvisited tab away and the empty inbox was all the
  // user saw. When work is genuinely active the inbox is still the right landing place.
  const workInFlight = isVerifying.value || isSimulating.value || isFuzzing.value
  void toggleHistoryPanel(workInFlight ? 'tasks' : 'results')
}

const openRuleRecommendationsFromActionDock = () => {
  openRuleRecommendationPanel()
}

const openDeviceRecommendationsFromActionDock = () => {
  openDeviceRecommendationPanel()
}

const openSpecRecommendationsFromActionDock = () => {
  openSpecRecommendationPanel()
}

const openScenarioRecommendationsFromActionDock = () => {
  openScenarioRecommendationPanel()
}

// 模拟时间轴动画状态
const simulationAnimationState = ref({
  visible: false
})

/**
 * Open the adopted run's playback at its first state, so the timeline and canvas rewind together.
 */
const openSimulationAnimationFromSavedStates = () => {
  const scene = lastSimulationResult.value?.playbackScene
  if (!scene) {
    notifyError(t('app.playbackSnapshotUnavailable'))
    return
  }
  activatePlaybackScene(scene)
  playbackPanelOpen.value = true
  simulationAnimationState.value = { visible: true }
  highlightedTrace.value = {
    states: savedSimulationStates.value,
    selectedStateIndex: 0
  }
  // The same reasoning as counterexample replay: this is a watch-the-canvas mode, so the two authoring panels
  // give the animation its room back. Applied here as a sibling rather than left for the next reader to notice —
  // a focus rule that holds for one playback surface and not the other is worse than none.
  focusCanvasForReplay()
  const persistence = lastSimulationResult.value?.historyPersistence
  if (persistence?.status === 'SAVED') {
    staleDeepLink.value = false
    void navigateToRunTarget({ kind: 'simulation', runId: persistence.runId }, 'push')
  } else clearRunDeepLink()
}

// Replay states and context belong to the same adopted run, including while its surface is hidden.
const savedSimulationStates = computed(() => lastSimulationResult.value?.states ?? [])

// 反例路径高亮状态
type HighlightedPlayback = {
  states: TraceState[]
  selectedStateIndex: number
  violatedSpec?: Trace['violatedSpec']
}
const highlightedTrace = ref<HighlightedPlayback | null>(null)

// 反例路径动画控制状态
const traceAnimationState = ref({
  visible: false,
  selectedTraceIndex: 0,
  selectedStateIndex: 0,
  isPlaying: false
})

/**
 * Open the counterexample playback surface on one trace, paused at the step that violates the
 * property.
 *
 * Every entry point (verification result dialog, history panel, fuzz finding) starts playback the
 * same way and differs only in which trace index it selects, so the landing step is expressed once
 * -- a caller cannot forget to set `selectedStateIndex` or leave `isPlaying` set.
 *
 * It used to open at index 0. That is the initial state: by definition nothing has changed yet and
 * there is no previous step to compare, so the surface opened on the one step that cannot explain
 * the failure. On a real 27-state counterexample, reviewing both ends of the trace was unambiguous:
 * the final step shows "Temperature 25 -> 26" in red with the changed value flagged, while step 1
 * "would only show an unchanged initial state and require navigating through 26 steps".
 *
 * A counterexample is evidence for a specific claim -- this property can be violated -- so it opens
 * where that claim is demonstrated. The full path stays one click away on the step timeline, which
 * is how a user works backwards from the violation to its cause.
 */
const openTraceAnimationAt = (selectedTraceIndex: number) => {
  const trace = savedTraces.value[selectedTraceIndex]
  const scene = trace?.playbackScene
  if (!scene) {
    notifyError(t('app.playbackSnapshotUnavailable'))
    return
  }
  activatePlaybackScene(scene)
  playbackPanelOpen.value = true
  // Opens on the last state, which is where the evidence is under either shape: for a safety template
  // NuSMV emits the violating state last (`LAST_STATE_VIOLATION_TEMPLATES`), and for a liveness template
  // the last state is the one that closes the cycle, so the panel's loop explanation is the first thing
  // the user reads. Guard the empty case: a malformed trace with no states must not produce a negative
  // index.
  const lastStateIndex = Math.max(0, (trace.states?.length ?? 1) - 1)
  traceAnimationState.value = {
    visible: true,
    selectedTraceIndex,
    selectedStateIndex: lastStateIndex,
    isPlaying: false
  }
  // `highlightedTrace` must move with it: it feeds the changes panel, and this entry point left it at its
  // previous position — so the changes panel read "State 1 / 27" while the timeline read 27/27, and it described
  // the changes of a step the user was not looking at. Two independent reviews caught the contradiction and
  // could not tell which panel to believe; neither could a user.
  //
  // `goToState` already writes both, so it is the single owner of "which step is selected" rather than a
  // second place that has to remember. It runs after the state above is installed, since it clamps to
  // `totalStates`.
  goToState(lastStateIndex)
  focusCanvasForReplay()
}

/** Collapse authoring panels so the frozen scene owns the canvas during replay. */
const focusCanvasForReplay = () => {
  boardPanels.control.collapsed = true
  boardPanels.inspector.collapsed = true

  // Then settle the model into the space it was just given, or the view looks evacuated rather than composed.
  //
  // Collapsing alone left the model pressed to one side — measured gaps of 181px left against 347px right, in a
  // stage that had just grown by 528px. The eye reads that as a layout that lost its panels, not as a focused
  // view. `getVisibleCanvasFrame` already subtracts the rails, the action dock and the timeline, so fitting
  // *after* the collapse centres the model in the true stage rather than in the raw canvas rectangle.
  //
  // Deferred to the next frame because the collapse is reactive: the CSS custom properties driving the rail
  // widths have not been applied yet in this tick, so fitting now would centre against the pre-collapse frame.
  //
  // Inlined rather than calling `fitToContent`, for one reason: that function reports "no devices on canvas" when
  // the board is empty. Correct for a button the user pressed, wrong for an automatic adjustment — a replay of a
  // scene whose devices were since deleted would open with an error toast blaming the user for nothing.
  void nextTick(() => {
    const viewport = fittedViewportForNodes(renderedCanvasNodes.value)
    if (!viewport) return
    if (activePlaybackScene.value) {
      playbackViewportFollowsFit = true
      playbackCanvasFrame = getVisibleCanvasFrame()
      playbackCanvasZoom.value = viewport.zoom
      playbackCanvasPan.value = viewport.pan
      return
    }
    canvasZoom.value = viewport.zoom
    canvasPan.value = viewport.pan
  })
}

// 独立保存的 traces 数据（用于对话框关闭后）
const savedTraces = ref<Trace[]>([])

// Playback is a read-only view over a persisted runtime snapshot. Derive the lock from
// the visible playback surfaces so no entry point can forget to acquire or release it.
const isAnimationLocked = computed(() =>
  traceAnimationState.value.visible || simulationAnimationState.value.visible
)

/**
 * The run manifest the simulation replay bar requires, for the window in which that bar is mounted.
 *
 * The bar's `modelSemantics` prop is non-optional because an absent manifest is not a reachable state:
 * `SimulationResult.modelSemantics` is required, `requireAttackContext` rejects a response whose manifest
 * disagrees with its run context, and `openSimulationAnimationFromSavedStates` refuses to open without a
 * loaded result. `lastSimulationResult` is a nullable ref for the periods when no run has been opened, and
 * a `v-if` on a sibling flag cannot narrow it, so the assertion goes here rather than being spread across
 * the template as `?.`.
 *
 * The cast narrows an invariant the checker cannot see; it deliberately does *not* substitute a fallback
 * manifest. A synthesized one would let the bar describe attack and privacy semantics no run declared —
 * the failure mode the required prop exists to prevent, reintroduced one layer down. Should the invariant
 * ever break, the component reads the prop null-safely and `isModelSemanticsConsistent` returns false for
 * an absent manifest, so it degrades to its "semantics unavailable" warning instead of asserting anything.
 * That warning is the failsafe for this narrowing, which is why it stays despite being unreachable today.
 */
const simulationPlaybackSemantics = computed(
  () => lastSimulationResult.value?.modelSemantics as ModelSemantics
)
const isModelPlaybackActive = isAnimationLocked

const hidePlaybackPanel = () => {
  playbackPanelOpen.value = false
  void nextTick(() => document.querySelector<HTMLButtonElement>('[data-testid="playback-panel-toggle"]')?.focus())
}

const togglePlaybackPanel = () => {
  if (playbackPanelOpen.value) {
    hidePlaybackPanel()
    return
  }
  playbackPanelOpen.value = true
  void nextTick(() => document.querySelector<HTMLButtonElement>('.playback-terminal__tabs [aria-selected="true"]')?.focus())
}

/* ===== Board edit undo/redo =====
 * Reverses one persisted user action: device edits, Environment Pool edits, rule/spec edits,
 * rule reorder, or automatic-fix apply. Deliberately separate
 * from every other "go back" affordance on this screen: browser Back/Forward and deep links move
 * between run surfaces, dialog close/dismiss hides a surface, and run cancellation stops a job —
 * none of those touch the edit journal, and undo does not touch them.
 *
 * The server journal is the authority, so this holds no local snapshot stack; each result carries
 * the authoritative collections, which are applied here in one place.
 * Registered after the playback refs exist, matching the pattern used just below. */
// A modal covering the board also blocks undo. The accelerator is on `window` and the modal's own
// buttons own no native undo, so without this Ctrl+Z pressed inside an open dialog silently mutated
// the board behind it while the dialog kept showing a draft built from the pre-undo collections.
const isBoardUndoBlocked = () => isModelPlaybackActive.value
  || isSceneReplacementInProgress.value
  || !isBoardDataReady.value
  || openModalDepth.value > 0
  || activeNodeLayoutInteractions.size > 0

let offerClearUnusableUndoHistory: () => Promise<void> = async () => undefined

const {
  canUndo: canUndoBoardEdit,
  canRedo: canRedoBoardEdit,
  isApplying: isApplyingBoardEditUndo,
  loadAvailability: loadBoardUndoAvailability,
  syncAvailability: syncBoardUndoAvailability,
  undo: undoBoardEdit,
  redo: redoBoardEdit
} = useBoardUndo({
  // An undo *is* a semantic board mutation, so it owes the same follow-ups as any other and goes
  // through the same owner. `canUndo`/`canRedo` come from the response, not from a local guess.
  applyResult: result => commitSemanticScene({
    nodes: result.nodes,
    environmentVariables: result.environmentVariables,
    rules: result.rules,
    specs: result.specs,
    availability: result
  }),
  // Serializes with every other board mutation, and re-checks admission when the slot is reached:
  // `isBlocked()` is evaluated once before queueing, so without this a undo queued behind a slow
  // delete could land after playback started.
  submit: work => enqueueBoardMutation(work, {
    admissionGuard: () => !isBoardUndoBlocked(),
    trackSemanticChange: false
  }),
  // A rejected response does not prove the mutation failed before commit. Reconcile in the same
  // queue so no later board mutation can race the authoritative refresh.
  reconcile: () => enqueueBoardMutation(refreshBoardSnapshot),
  // An undo *is* a semantic scene change, so it owes recommendation invalidation too.
  // `commitSemanticScene` owns staleness but not this — the mutation queue's own
  // `onSemanticChange` is skipped by `trackSemanticChange: false`.
  onApplied: result => {
    invalidateRecommendationsForSceneChange({ notify: true })
    announceAppliedBoardUndo(result)
  },
  isIgnorableError: error => isPollingAbortedError(error)
    || error instanceof BoardMutationAdmissionCancelledError,
  isBlocked: isBoardUndoBlocked,
  report: (reasonCode, direction, error, reconciled) => {
    if (reasonCode === 'blocked') {
      notifyBlocked(t('app.boardUndoBlocked'))
      return
    }
    if (reasonCode === 'nothing') {
      notifyInfo(direction === 'undo'
        ? t('app.boardUndoNothingToApply')
        : t('app.boardUndoRedoNothingToApply'))
      return
    }
    if (reasonCode === 'conflict') {
      notifyBlocked(t(reconciled
        ? 'app.boardUndoConflict'
        : 'app.boardUndoConflictRefreshFailed'))
      void offerClearUnusableUndoHistory()
      return
    }
    console.error('Board edit undo failed:', error)
    notifyBlocked(t(reconciled
      ? 'app.boardUndoOutcomeRefreshed'
      : 'app.boardUndoOutcomeUnknownRefreshFailed'))
  }
})

const applyBoardHistoryFromControl = async (direction: 'undo' | 'redo', event: MouseEvent) => {
  const source = event.currentTarget as HTMLButtonElement
  await (direction === 'undo' ? undoBoardEdit() : redoBoardEdit())
  await nextTick()
  // Disabling the clicked control drops browser focus; do not move it if the user has moved on.
  if (!source.isConnected || (document.activeElement !== document.body && document.activeElement !== source)) return
  const target = source.disabled
    ? document.querySelector<HTMLButtonElement>(`[data-testid="board-${direction === 'undo' ? 'redo' : 'undo'}"]`)
    : source
  if (target && !target.disabled) target.focus({ preventScroll: true })
}

/** Name the reversed edit; rules and specifications may be outside the visible canvas. */
const announceAppliedBoardUndo = (result: BoardUndoResult) => {
  const entity = result.entityType
  const operation = result.originalOperation
  if (!entity || !operation) return
  // Composed from two small vocabularies rather than one message per pair: six entities times three operations
  // times two directions would be 36 strings per locale for a toast, and the composition carries the same
  // meaning. `REDONE` re-applies the original edit, so it reads in the original's terms; an undo reads as its
  // inverse — a creation becomes a removal, a deletion becomes a restoration, an update becomes a revert.
  const redone = result.reasonCode === 'REDONE'
  const action = redone
    ? t(`app.boardEditOperation_${operation}`)
    : t(`app.boardEditInverse_${operation}`)
  dismissCompletionNotifications()
  notifyInfo(t(redone ? 'app.boardUndoRedoApplied' : 'app.boardUndoApplied', {
    action,
    entity: t(`app.boardEditEntity_${entity}`)
  }))
}

let clearUndoHistoryConfirmationPending = false
offerClearUnusableUndoHistory = async () => {
  if (clearUndoHistoryConfirmationPending) return
  clearUndoHistoryConfirmationPending = true
  try {
    const preview = await enqueueBoardMutation(
      () => boardApi.previewBoardEditHistoryClear(),
      {
        admissionGuard: () => !isBoardUndoBlocked(),
        trackSemanticChange: false
      }
    )
    if (preview.entryCount === 0) {
      syncBoardUndoAvailability(preview)
      notifyInfo(t('app.boardUndoHistoryAlreadyEmpty'))
      return
    }
    if (!await confirmDestructive({
      title: t('app.boardUndoClearHistoryTitle'),
      message: t('app.boardUndoClearHistoryMessage', { count: preview.entryCount }),
      confirmText: t('app.boardUndoClearHistoryAction')
    })) return
    const availability = await enqueueBoardMutation(
      () => boardApi.clearBoardEditHistory(preview.impactToken),
      {
        admissionGuard: () => !isBoardUndoBlocked(),
        trackSemanticChange: false
      }
    )
    syncBoardUndoAvailability(availability)
    notifyInfo(t('app.boardUndoHistoryCleared'))
  } catch (error) {
    if (isPollingAbortedError(error)
      || error instanceof BoardMutationAdmissionCancelledError) return
    await loadBoardUndoAvailability()
    if ((error as { response?: { status?: number } })?.response?.status === 409) {
      notifyBlocked(t('app.boardUndoClearHistoryStale'))
      return
    }
    notifyError(extractApiErrorMessage(error, t('app.boardUndoClearHistoryFailed')))
  } finally {
    clearUndoHistoryConfirmationPending = false
  }
}

// Bind the late reference the assistant refresh helpers above call, now that it exists.
reloadUndoAvailability = loadBoardUndoAvailability

// Register this after playback refs exist. A watcher evaluates its computed source
// immediately while collecting dependencies, so registering it earlier would hit the
// temporal-dead-zone of the playback state during setup.
watch(isCanvasInteractionLocked, locked => {
  if (!locked) return
  finishCanvasPan()
  finishCanvasMapDrag()
})


const activePlaybackKind = computed<'simulation' | 'counterexample' | 'fuzzing' | null>(() => {
  if (simulationAnimationState.value.visible) return 'simulation'
  if (traceAnimationState.value.visible && activeFuzzingFinding.value) return 'fuzzing'
  if (traceAnimationState.value.visible) return 'counterexample'
  return null
})

let playbackFocusFallbackId = 'open-verification-panel'
watch(activePlaybackKind, kind => {
  if (!kind) return
  playbackFocusFallbackId = kind === 'simulation' ? 'open-simulation-panel'
    : kind === 'fuzzing' ? 'open-fuzzing-panel' : 'open-verification-panel'
}, { flush: 'sync' })

const {
  setDialogRef: setPlaybackToolbarRef,
  handleModalKeydown: handlePlaybackKeydown
} = useModalAccessibility(
  isModelPlaybackActive,
  () => traceAnimationState.value.visible ? closeTraceAnimation() : closeSimulationTimeline(),
  opener => document.querySelector<HTMLElement>(opener?.closest('[data-testid="trace-history-panel"]')
    ? '[data-testid="open-history-panel"]' : `[data-testid="${playbackFocusFallbackId}"]`),
  { trapFocus: false, shouldRestoreFocus: () => !isModelPlaybackActive.value
    && !isResultSurfaceVisible.value && !isWorkflowPanelOpen.value }
)

/**
 * Wrapped trace passed to the canvas, with violation information gated by playback kind.
 *
 * Prevents counterexample violation emphasis from leaking into simulation replay:
 * `savedTraces` has three writers and no reset, and `currentTrace` prefers it
 * unconditionally — so a counterexample opened earlier stays selected while a
 * simulation replays through the same `highlightedTrace`, and the canvas would
 * outline that counterexample's devices on a run that violated nothing.
 */
const canvasHighlightedTrace = computed(() => {
  if (!highlightedTrace.value) return null

  const isViolationPlayback =
    activePlaybackKind.value === 'counterexample' ||
    activePlaybackKind.value === 'fuzzing'

  // Derived from the violated specification's own conditions. It previously read
  // `violatedSpec.boundDeviceIds`, a field `Specification` does not have, so this was always `[]` and
  // the canvas fell back to emphasising *every* device in the state — the scoping this computed exists
  // to provide never took effect. `devices` is the accumulated per-spec reference list when present;
  // the conditions are the authority behind it, so both are read and de-duplicated.
  const violationDeviceIds = isViolationPlayback
    ? [...new Set([
        ...(currentTrace.value?.violatedSpec?.devices || []).map(device => device.deviceId),
        ...[
          currentTrace.value?.violatedSpec?.aConditions,
          currentTrace.value?.violatedSpec?.ifConditions,
          currentTrace.value?.violatedSpec?.thenConditions
        ].flatMap(conditions => (conditions || []).map(condition => condition.deviceId))
      ].filter((deviceId): deviceId is string => !!deviceId))]
    : []

  return {
    ...highlightedTrace.value,
    violationStateIndex: isViolationPlayback
      ? counterexampleViolationStep.value
      : undefined,
    violationStateIndexes: isViolationPlayback
      ? counterexampleViolationSteps.value
      : [],
    violationDeviceIds
  }
})

type ActivePlaybackState = {
  devices?: TraceDevice[]
  envVariables?: TraceVariable[]
  triggeredRules?: TraceTriggeredRule[]
  compromisedAutomationLinks?: TraceTriggeredRule[]
  loopStart?: boolean
  loopBack?: boolean
}

const activePlaybackStates = computed<ActivePlaybackState[]>(() => {
  if (!isModelPlaybackActive.value || !highlightedTrace.value?.states) return []
  return highlightedTrace.value.states
})

const activePlaybackDevices = computed(() =>
  activePlaybackStates.value[activePlaybackStateIndex.value]?.devices || [])

const activePlaybackEnvironmentVariables = computed(() =>
  activePlaybackStates.value[activePlaybackStateIndex.value]?.envVariables || [])

const bundledPlaybackDeviceIds = computed(() => activePlaybackDevices.value
  .filter(hasFrozenBundledTokenSource)
  .map(device => device.deviceId))

const bundledPlaybackEnvironmentNames = computed(() => activePlaybackEnvironmentVariables.value
  .filter(hasFrozenBundledTokenSource)
  .map(variable => variable.name))

const formatPlaybackEnvironmentModelToken = (name: string, value: unknown): string =>
  bundledPlaybackEnvironmentNames.value.includes(name)
    ? formatBundledModelToken(value)
    : String(value ?? '')

const activePlaybackStateIndex = computed(() => {
  const selected = Number(highlightedTrace.value?.selectedStateIndex ?? 0)
  const lastIndex = Math.max(activePlaybackStates.value.length - 1, 0)
  return Math.min(Math.max(Number.isFinite(selected) ? Math.trunc(selected) : 0, 0), lastIndex)
})

const activePlaybackChanges = computed<PlaybackDeviceChange[]>(() => {
  if (!activePlaybackKind.value || activePlaybackStateIndex.value <= 0) return []
  const currentState = activePlaybackStates.value[activePlaybackStateIndex.value]
  const previousState = activePlaybackStates.value[activePlaybackStateIndex.value - 1]
  if (!currentState?.devices || !previousState?.devices) return []

  return currentState.devices
    .map(device => {
      const previous = previousState.devices?.find(candidate =>
        normalizePlaybackDeviceId(candidate.deviceId) === normalizePlaybackDeviceId(device.deviceId)
      )
      return playbackDeviceChangeDetails(device, previous)
    })
    .filter((change): change is PlaybackDeviceChange => change !== null)
})

const activePlaybackEnvironmentChanges = computed<PlaybackEnvironmentChange[]>(() => {
  if (!activePlaybackKind.value || activePlaybackStateIndex.value <= 0) return []
  const currentState = activePlaybackStates.value[activePlaybackStateIndex.value]
  const previousState = activePlaybackStates.value[activePlaybackStateIndex.value - 1]
  return playbackEnvironmentChangeDetails(currentState?.envVariables, previousState?.envVariables)
})

const activePlaybackTriggeredRules = computed<TraceTriggeredRule[]>(() =>
  activePlaybackStates.value[activePlaybackStateIndex.value]?.triggeredRules || [])

const activeFuzzingStepInputEvents = computed<Array<FuzzingInputEvent & { targetLabel?: string }>>(() => {
  const finding = activeFuzzingFinding.value
  if (!finding) return []
  const state = activePlaybackStates.value[activePlaybackStateIndex.value]
  return finding.inputEvents
    .filter(event => event.step === activePlaybackStateIndex.value)
    .map(event => {
      if (event.kind !== 'DEVICE_VARIABLE' && event.kind !== 'DEVICE_STATE') return event
      const device = state?.devices?.find(candidate =>
        normalizePlaybackDeviceId(candidate.deviceId) === normalizePlaybackDeviceId(event.targetId))
      return { ...event, targetLabel: device?.deviceLabel || event.targetId }
    })
})

const activePlaybackAnimatedEdgeCount = computed(() => {
  let animated = 0
  for (const state of buildTraceEdgePlaybackStates(allEdges.value, highlightedTrace.value).values()) {
    if (state.shouldAnimate) animated++
  }
  return animated
})

// ============================================================================
// Loop handling for infinite counterexamples (liveness violations)
// ============================================================================

/**
 * Liveness properties (templateId 2, 5, 6) are refuted by infinite paths (lasso counterexamples).
 *
 * Template semantics (from docs/architecture/spec-templates.md):
 * - Template 2 (Eventually/AF): A eventually holds on all paths
 * - Template 5 (Response/AG(IF->AF(THEN))): After IF, THEN eventually holds
 * - Template 6 (Persistence/G(IF->FG(THEN))): After IF, THEN holds persistently
 *
 * Safety properties (1, 3, 4, 7) are refuted by finite paths, so a loop marker there
 * means NuSMV terminated with a cycle but the fault is still a single state.
 */
const LIVENESS_TEMPLATES = new Set(['2', '5', '6'])

/**
 * Whether the current trace is a liveness violation (infinite counterexample).
 *
 * Used to determine the wording of the loop-back explanation: for liveness, the cycle
 * *is* the violation (the required state is never reached); for safety with a loop,
 * the fault is a single state and the loop is incidental.
 */
const activePlaybackIsLivenessViolation = computed(() => {
  const trace = highlightedTrace.value
  if (!trace?.violatedSpec?.templateId) return false
  return LIVENESS_TEMPLATES.has(String(trace.violatedSpec.templateId))
})

/**
 * 1-based state numbers of the repeating cycle [start, end], or null if no loop.
 *
 * NuSMV marks lasso counterexamples with "-- Loop starts here" before the loop-entry state, then
 * terminates by re-printing that state. The backend parser sets `loopStart: true` on the entry and
 * `loopBack: true` on the final repeat. Whether that repeat *looks* like a repeat depends on the cycle
 * length — a one-state cycle carries no variable lines and freezes, a longer one carries the deltas back
 * to the entry and looks like an ordinary step — so the range is what lets the panel name the cycle
 * either way; see `TraceStateDto#loopBack`.
 *
 * Shown in the PlaybackChangesPanel on the loop-back step, where the shipped wording names this range
 * and says the cycle is the violation (`app.traceLoopExplanation`).
 */
const activePlaybackLoopRange = computed<{ start: number; end: number } | null>(() => {
  const states = activePlaybackStates.value
  if (states.length === 0) return null

  /*
   * The LAST marked state, not the first, because NuSMV can print `-- Loop starts here` more than once
   * in one counterexample and the parser resolves that by keeping the last one: `SmvTraceParser`
   * overwrites `loopStartState` on every marker, and `loopBack` is paired with whatever it holds at the
   * end. `SmvTraceParserTest.parseCounterexample_usesTheLastMarkerWhenNuSmvPrintsSeveral` pins a
   * five-state trace where states 3 and 4 both carry `loopStart` and state 5 carries `loopBack`.
   *
   * `findIndex` disagreed with that: it named states 3–5 where the cycle is 4–5, so the changes panel said
   * "State 5 loops back to state 3" — a wrong statement about formal evidence, in the one place a reader
   * goes to find out why the final step shows nothing moving.
   *
   * A reverse scan rather than `findLastIndex`, which needs `lib: ES2023`; this app targets ES2020, and
   * raising the whole lib for one call is a build-config change out of proportion to it.
   */
  let loopStartIndex = -1
  for (let i = states.length - 1; i >= 0; i--) {
    if (states[i]?.loopStart === true) { loopStartIndex = i; break }
  }
  if (loopStartIndex === -1) return null

  /*
   * `findIndex` is correct here, unlike above: `SmvTraceParser.markLoopBackState` sets `loopBack` on the
   * last state only, and on at most one state per trace, so there is no last-vs-first ambiguity to
   * resolve. Verified against that method rather than assumed from the marker's symmetry with
   * `loopStart`, which *can* appear several times.
   */
  const loopBackIndex = states.findIndex(s => s.loopBack === true)
  if (loopBackIndex === -1) return null

  // Convert to 1-based state numbers for display
  return {
    start: loopStartIndex + 1,
    end: loopBackIndex + 1
  }
})

/**
 * Whether the current playback step is the loop-back state.
 *
 * When true, the PlaybackChangesPanel explains the cycle instead of describing this step like any other.
 * On a one-state cycle that displaces the generic "no observable changes" message, because there the
 * absence of changes *is* the state's meaning; on a longer cycle the step carries real changes and the
 * explanation is what stops it from reading as the path continuing.
 */
const activePlaybackIsLoopBackState = computed(() => {
  const currentState = activePlaybackStates.value[activePlaybackStateIndex.value]
  return currentState?.loopBack === true
})

const resetPlaybackTerminal = () => {
  playbackTerminalTab.value = 'changes'
}

watch(activePlaybackKind, resetPlaybackTerminal)

const isLiveBoardEditorVisible = computed(() =>
  templateInstanceDialogVisible.value ||
  dialogVisible.value ||
  renameDialogVisible.value ||
  deleteConfirmDialogVisible.value ||
  showFixDialog.value
)

const ensureLiveBoardEditorClosedForPlayback = (): boolean => {
  if (hasAssistantWork.value) {
    notifyBlocked(t('app.finishAssistantBeforePlayback'))
    return false
  }
  if (!isLiveBoardEditorVisible.value) return true
  notifyBlocked(t('app.closeLiveEditorBeforePlayback'))
  return false
}

const notifyAutomaticPlaybackDeferred = () => {
  notifyInfo(t('app.simulationPlaybackDeferredForEditor'))
}

/** Saved results stay in history; other results need a local owner until explicitly opened or discarded. */
const notifyAutomaticPlaybackDeferredForReplay = (
  result: SimulationResultView,
  submissionSceneChanges: number
) => {
  if (result.historyPersistence.status === 'SAVED') {
    notifyInfo(t('app.simulationPlaybackDeferredForReplay'))
    return
  }
  const ownerAuthScopeEpoch = boardAuthScopeEpoch
  const ownerUserId = currentAuthUserId.value
  let close!: () => void
  close = notifyAvailableResult({
    title: t('app.simulationTemporaryResultTitle'),
    message: t('app.simulationTemporaryResultMessage'),
    actionLabel: t('app.simulationTemporaryResultOpen'),
    discardLabel: t('app.discardLocalResult'),
    onAction: () => {
      if (!isCurrentBoardAuthScope(ownerAuthScopeEpoch) || currentAuthUserId.value !== ownerUserId) return true
      if (!ensureHistoricalPlaybackUiAdmission()) return false
      closeOtherWorkflowSurfaces()
      adoptSimulationRunResult(result, submissionSceneChanges)
      openSimulationAnimationFromSavedStates()
      return true
    },
    onClose: () => unsavedRunNotifications.delete(close)
  })
  unsavedRunNotifications.add(close)
}

const ensurePlaybackClosedForMutation = (): boolean => {
  if (isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return false
  }
  if (!isModelPlaybackActive.value) return true
  notifyBlocked(t('app.playbackReadOnlyCloseFirst'))
  return false
}

let playInterval: ReturnType<typeof setInterval> | null = null

/**
 * Which surfaces claim the space the map viewport occupies.
 *
 * Scoped to the viewport rectangle, not the whole map card: the zoom and fit controls in the same card
 * are the board's viewport controls and must survive every one of these, because a result panel is
 * exactly when a user zooms to look at what it points at.
 */
/**
 * Is one of the board's workflow panels open?
 *
 * The five members (verification, simulation, exploration, run history, and the recommendation group) were
 * enumerated at two call sites, one of which also spelled out the four recommendation flags that
 * `isAnyRecommendationPanelVisible()` already owns, and the two timeline flags that `isAnimationLocked`
 * owns. Adding a sixth panel therefore meant remembering up to three lists. Naming the set once means a new
 * panel joins it in one place.
 */
const isWorkflowPanelOpen = computed(() =>
  showVerificationPanel.value ||
  showSimulationPanel.value ||
  showFuzzingPanel.value ||
  showHistoryPanel.value ||
  isAnyRecommendationPanelVisible()
)

/*
 * There is no `isCanvasMapHidden…` predicate any more, because the collision it guarded against cannot
 * happen. The map lives in the inspector's overview dock, and the floating panels are positioned with a
 * `right` inset that clears the inspector and the action rail — measured at 1440x900 the panel spans
 * x=660..948 against an inspector at 1120..1440, and at 1100x800 it is 336..692 against 780..1100. Neither
 * touches the inspector, let alone the map card inside it. At narrow widths the inspector collapses to a
 * 56px rail and the map is not rendered at all.
 *
 * Hiding it anyway cost the user the zoom field, the zoom buttons and fit-to-content — the board's only
 * pointer viewport controls — for the entire time any of eleven surfaces was open. Narrowing that to hide
 * only the map rectangle, and renaming the card while it was hidden, were both treatments for a symptom of
 * this rule rather than for the rule itself.
 */

// 当前选中的 trace
const currentTrace = computed(() => {
  // 优先使用 savedTraces
  if (savedTraces.value.length > 0) {
    return savedTraces.value[traceAnimationState.value.selectedTraceIndex] || null
  }
  return verificationResult.value?.traces?.[traceAnimationState.value.selectedTraceIndex] || null
})

// 所有状态数量
const totalStates = computed(() => {
  return currentTrace.value?.states?.length || 0
})

// Verification context of the trace currently being viewed, derived from the trace's own snapshot
// (backend TraceDto) rather than the live verification form.
const activeTraceContext = computed(() => {
  return deriveTraceContext(currentTrace.value)
})

const currentTraceState = computed(() => {
  const trace = currentTrace.value
  if (!trace?.states) return null
  return trace.states[traceAnimationState.value.selectedStateIndex] || null
})


const currentTraceCompromisedAutomationLinks = computed(() => currentTraceState.value?.compromisedAutomationLinks || [])
const currentBoardRuleIds = computed(() => rules.value
  .map(rule => rule.id)
  .filter((id): id is string => !!id)
  .map(String))
const currentBoardDeviceIds = computed(() => nodes.value.map(node => normalizePlaybackDeviceId(node.id)))







// 选择并播放指定索引的反例路径动画
const selectAndPlayTrace = (traceIndex: number) => {
  // 互斥检查：如果模拟动画正在显示，则不允许打开反例路径动画
  if (simulationAnimationState.value.visible) {
    notifyBlocked(t('app.closeCurrentSimulationFirst'))
    return
  }
  
  if (isAnyRecommendationPanelVisible()) {
    notifyBlocked(t('app.closeRecommendationPanelsFirst'))
    return
  }
  if (!ensureLiveBoardEditorClosedForPlayback()) return
  const traces = verificationResult.value?.traces
  if (traces && traceIndex >= 0 && traceIndex < traces.length) {
    const trace = traces[traceIndex]
    if (hasPersistedVerificationTrace(verificationResult.value, trace)) {
      void openVerificationTraceFromHistory(trace.id)
      return
    }
    resetPlaybackTerminal()
    activeFuzzingFinding.value = null
    // 保存 traces 数据到独立变量
    savedTraces.value = [...traces]
    
    // 关闭验证结果对话框
    closeResultDialog()
    
    // `openTraceAnimationAt` selects the violating state and writes both selection refs through
    // `goToState`. A second write to `highlightedTrace` here used to force it back to state 0 — the
    // stale intent of an older design where a trace opened at its beginning — which is what made the
    // changes panel describe step 1 while the timeline sat on step 27.
    openTraceAnimationAt(traceIndex)
  }
}

// 关闭反例路径动画
/**
 * User dismissal clears the replay address. Navigation teardown preserves the incoming address
 * so Back/Forward can load the selected result without a competing URL rewrite.
 */
const closeTraceAnimation = (clearDeepLink = true) => {
  stopTraceAnimation()
  traceAnimationState.value.visible = false
  highlightedTrace.value = null
  activeFuzzingFinding.value = null
  deactivatePlaybackScene()
  resetPlaybackTerminal()
  if (clearDeepLink) clearRunDeepLink()
}

/**
 * Close any open result surface whose run an authoritative history reload no longer lists.
 *
 * Called after a successful reload; dispatch every run kind here so none misses out-of-band deletion.
 */
const reconcileOpenRunAgainstHistory = () => {
  reconcileOpenVerificationRunAgainstHistory()
  reconcileOpenFuzzingRunAgainstHistory()
  reconcileOpenSimulationRunAgainstHistory()
}

/**
 * Only absence from a populated authoritative history counts as deletion; an empty list may be scoped/loading.
 * Replay clears the dialog ref, so its persisted trace supplies the owning run id as a fallback.
 */
const reconcileOpenVerificationRunAgainstHistory = () => {
  const openRunId = verificationResult.value?.historyPersistence?.runId
    ?? currentTrace.value?.verificationTaskId
  if (typeof openRunId !== 'number') return
  if (verificationRuns.value.length === 0) return
  if (verificationRuns.value.some(run => run.id === openRunId)) return
  // currentTrace also resolves from an open dialog; only activePlaybackKind proves replay is visible.
  // Capture the surface before teardown to choose accurate wording.
  const wasReplaying = activePlaybackKind.value === 'counterexample'
    && currentTrace.value?.verificationTaskId === openRunId
  dismissRunSurfacesForDeletedVerificationRun(openRunId)
  notifyBlocked(t(wasReplaying
    ? 'app.openCounterexampleRunDeletedElsewhere'
    : 'app.openRunDeletedElsewhere'))
}

/**
 * Reconcile exploration after authoritative history reload, with the same persisted-id/non-empty guards.
 */
const reconcileOpenFuzzingRunAgainstHistory = () => {
  // Opening a finding clears the dialog ref; replay evidence retains the owning run id.
  const openRunId = fuzzingResult.value?.id ?? activeFuzzingFinding.value?.fuzzTaskId
  if (typeof openRunId !== 'number') return
  if (fuzzingRuns.value.length === 0) return
  if (fuzzingRuns.value.some(run => run.id === openRunId)) return
  // A reload replaces exploration history with page 0; absence from a partial list proves nothing.
  if (fuzzingRunsHasMore.value) return
  // The finding ref can survive another replay; choose the visible surface before clearing state.
  const wasReplaying = activePlaybackKind.value === 'fuzzing'
    && activeFuzzingFinding.value?.fuzzTaskId === openRunId
  if (wasReplaying) {
    // Clears the deep link too, so the URL sync cannot reopen the deleted finding.
    closeTraceAnimation()
    savedTraces.value = []
  }
  // Clears the deep link with the surface, so the URL sync cannot reopen the deleted run.
  dismissFuzzingResult()
  // Exploration produces candidate findings, so its notice must not promise a formal conclusion.
  notifyBlocked(t(wasReplaying
    ? 'app.openFuzzingFindingRunDeletedElsewhere'
    : 'app.openFuzzingRunDeletedElsewhere'))
}

/**
 * lastSimulationResult outlives its surfaces, so check visibility before announcing deletion.
 * historyPersistence.runId is the saved trajectory id used by simulationRuns; empty history proves nothing.
 */
const reconcileOpenSimulationRunAgainstHistory = () => {
  if (!simulationAnimationState.value.visible) return
  const openRunId = lastSimulationResult.value?.historyPersistence?.runId
  if (typeof openRunId !== 'number') return
  if (simulationRuns.value.length === 0) return
  if (simulationRuns.value.some(run => run.id === openRunId)) return
  dismissSimulationSurfacesForDeletedRun()
  notifyBlocked(t('app.openSimulationRunDeletedElsewhere'))
}

/**
 * Tear down every surface showing the open simulation trajectory.
 *
 * No id argument: unlike verification, only one trajectory is loaded at a time — `lastSimulationResult`
 * is the single owner and the playback controls, terminal and canvas highlight all read it — so the
 * caller has already established that *this* run is the deleted one.
 *
 * Branched so `run=simulation:<id>` is cleared exactly once, by whichever closer owns the addressed
 * surface: for `run=simulation:<id>` the bar *is* the addressed surface and `closeSimulationTimeline`
 * already clears the link, so the dialog then takes the internal-transition closer. With no bar up,
 * deletion passes its known run identity to the error closer so a newer URL target survives.
 * `lastSimulationResult` is cleared after
 * both, because the bar's props read through it.
 */
const dismissSimulationSurfacesForDeletedRun = () => {
  if (simulationAnimationState.value.visible) {
    closeSimulationTimeline()
    closeSimulationResultDialog()
  } else {
    dismissSimulationResultDialog(lastSimulationResult.value?.historyPersistence.runId)
  }
  lastSimulationResult.value = null
  // Not the "closing a surface must not clear the stale flag" case: that rule exists because
  // `lastSimulationResult` survives a close and reopening it must still warn that the canvas moved. Here
  // the run it described is being dropped, so the flag is cleared with its owner rather than left
  // asserting staleness about nothing.
  simulationResultStale.value = false
}

/**
 * Tear down every surface still showing a verification run the user has just deleted.
 *
 * Deleting a run removed it from the history list and nothing else, so a result dialog or a
 * counterexample replay opened from it kept rendering evidence for a record that no longer existed.
 * Measured: with the dialog open, the download button stayed enabled and answered "SMV model not
 * available (may be a record saved before model persistence was enabled)" — a historical-data excuse for
 * a deletion one click old.
 *
 * Matched on the run id rather than by closing unconditionally: deleting run 7 must not shut a dialog
 * showing run 9. The trace surfaces are matched through `verificationTaskId`, which is the run every
 * persisted counterexample carries.
 */
const dismissRunSurfacesForDeletedVerificationRun = (runId: number) => {
  const openRunId = verificationResult.value?.historyPersistence?.runId
  const playingTraceRunId = currentTrace.value?.verificationTaskId

  if (playingTraceRunId === runId) {
    // Closes the replay and clears the deep link, so a reload cannot reopen the deleted run.
    closeTraceAnimation()
    savedTraces.value = []
  }

  if (openRunId === runId) {
    // `dismissResultDialog`, not `closeResultDialog`: this is a user-visible close, so the `?run=` deep
    // link has to go with it — otherwise the URL sync would reopen the run and fail to load it.
    dismissResultDialog()
  }
}

// 选择违规规约
// 跳转到指定状态
const goToState = (index: number) => {
  stopTraceAnimation()
  const lastIndex = Math.max(totalStates.value - 1, 0)
  traceAnimationState.value.selectedStateIndex = Math.min(Math.max(index, 0), lastIndex)
  const trace = currentTrace.value
  if (trace) {
    highlightedTrace.value = {
      ...trace,
      selectedStateIndex: traceAnimationState.value.selectedStateIndex
    }
  }
}

/**
 * Which step of the counterexample is the violation.
 *
 * A verification counterexample had no violation marker at all. The `★` on the rail marks the *selected*
 * state — it is the cursor — and the `!` came only from `activeFuzzingFinding.firstViolationStep`, which
 * exploration sets and NuSMV never does. Two independent reviews read the cursor as the verdict: "the
 * final state is also marked only by a star, without a clear 'violation' label". For the one screen whose
 * entire purpose is to show *how* a design fails, that is the central fact left unstated.
 *
 * It does not need a new backend field, because the trace's structure already answers it. Verification
 * emits the **positive** specification — `SmvGenerator.buildSmvContent` hard-codes a null
 * `ParameterizationConfig`, and only that null forks to `specBuilder.build`; the negated form
 * (`buildNegated`) is reached solely through the fix/parameterization strategies, which read it as a
 * satisfiability bit and parse no trace from it. So what a client sees is NuSMV's counterexample to
 * `AG(...)`, which is a path *ending* at the state that breaks it.
 *
 * Templates 1, 3, 4 and 7 therefore all put the violation on the last state. Measured on NuSMV 2.7.1
 * across 21 falsifying models in two independent passes (5 + 16, deterministic non-response,
 * nondeterministic response, bounded step counter, branching successors, enumerated device states, the
 * submodule shape the generator emits, trigger-in-initial-state, and trigger-at-loop-entry): for
 * template 4 the trigger sits at index n and the violating successor at n+1, and n+1 is always the final
 * printed state — the trace never stops at the trigger. Template 4 was
 * excluded here on the reasoning that its witness ends where the trigger holds, which describes the
 * *negated* form (`EF(a & EX(!b))`); for a violating model that formula is **true**, so NuSMV prints no
 * trace for it at all, and no user ever sees one. Two things independently agree that n+1 is the
 * violation: `FuzzModel.evaluate` returns `step + 1` for template 4, and
 * `docs/architecture/fuzzing-flow.md` §"Supported finite semantics" states "State `n+1` where `IF` held
 * at `n` and `THEN` is false at `n+1`". Excluding it left 13 of the 42 specs in the shipped scenes — the
 * most common template, and the one the acceptance demo violates under attack mode — replaying with no
 * violation marked anywhere.
 *
 * Template 2 stays out: it negates to `EG(!A)`, whose witness is an infinite path on which A never holds,
 * so its fault is a cycle and it belongs to `LIVENESS_TEMPLATES` below.
 *
 * One shape to know when reading a trace by eye: when the violating successor differs from the trigger in
 * no variable (a frozen actuator, a self-loop), NuSMV's delta encoding prints its header with zero value
 * lines. That reads as truncation and is not — `SmvTraceParser.materializeCompleteState` merges it
 * forward, and `show_traces -v 1` confirms the full valuation is there. 8 of the 16 measured traces had
 * that shape.
 */
const LAST_STATE_VIOLATION_TEMPLATES = new Set(['1', '3', '4', '7'])

/**
 * Loop range [start, end] for the current counterexample trace, or null if no loop.
 *
 * Indices are **0-based** here, unlike `activePlaybackLoopRange`, because these feed
 * `counterexampleViolationSteps` — array positions for canvas emphasis and the rail's cycle rings —
 * rather than the state numbers a sentence shows the user. Both resolve the entry from the *last*
 * `loopStart` marker, matching `SmvTraceParser`, which overwrites `loopStartState` on every marker it
 * sees; a trace can carry several. Reading the first would emphasise a state the cycle does not contain.
 */
const counterexampleLoopRange = computed<{ start: number; end: number } | null>(() => {
  const trace = currentTrace.value
  if (!trace?.states) return null

  // Reverse scan rather than `findLastIndex`, which needs `lib: ES2023`; this app targets ES2020.
  let start = -1
  for (let index = trace.states.length - 1; index >= 0; index--) {
    const state = trace.states[index]
    if (state?.loopStart === true) {
      start = index
      break
    }
  }
  if (start === -1) return null

  // Find the loop-back state
  const end = trace.states.findIndex(state => state?.loopBack === true)
  if (end === -1) return null

  return { start, end }
})

/**
 * Single violation step index for safety counterexamples, or undefined for liveness.
 * Used to mark one step on the rail and emphasize devices on the canvas.
 */
const counterexampleViolationStep = computed<number | undefined>(() => {
  // An exploration finding reports its own step; that is authoritative and takes precedence.
  if (activeFuzzingFinding.value?.firstViolationStep !== undefined) {
    return activeFuzzingFinding.value.firstViolationStep
  }
  const trace = currentTrace.value
  const templateId = trace?.violatedSpec?.templateId
  if (!trace || !templateId) return undefined

  // Liveness templates do not mark a single violation step; the entire cycle is the fault.
  if (LIVENESS_TEMPLATES.has(String(templateId))) {
    return undefined
  }

  // Safety templates: the last state is the violation step.
  if (LAST_STATE_VIOLATION_TEMPLATES.has(String(templateId))) {
    const count = trace.states?.length || 0
    // Template 4 needs two states to *be* a violation — a trigger and the successor that fails to
    // respond — so a single-state trace claiming it is inconsistent evidence, not a violation at state 0.
    // Marking state 0 there would name the trigger as the fault, which is the error the exclusion this
    // set used to carry was guarding against. Templates 1/3/7 have no such floor: an initial state can
    // break `AG(p)` on its own.
    const floor = String(templateId) === '4' ? 2 : 1
    return count >= floor ? count - 1 : undefined
  }

  return undefined
})

/**
 * The same answer as a 1-based state number, for the playback panel's badge.
 *
 * That badge previously read `activeFuzzingFinding` directly, so it appeared for an exploration finding and
 * never for a verification counterexample — while the rail marker directly beneath the panel said
 * "Violation" on that very state. Stepping through a safety counterexample, the one panel whose job is to
 * explain what happens *at this state* was the only surface silent about it.
 *
 * Reading the computed above rather than the finding keeps one owner for the question. `undefined` for a
 * liveness cycle is correct here too: there the cycle is the violation, and `loopBackSentence` says so
 * instead of a badge naming a single state.
 *
 * Gated on the playback kind for the same reason `canvasHighlightedTrace` is: this changes panel is shared with
 * simulation replay, and neither `savedTraces` nor `activeFuzzingFinding` is cleared when one starts, so an
 * ungated read would badge a state of a run that violated nothing. The component's old
 * `kind === 'fuzzing'` test was doing this incidentally; making the badge kind-agnostic there means the gate
 * has to be stated here, where the playback kind is known.
 */
const activeViolationStateNumber = computed(() => {
  const kind = activePlaybackKind.value
  if (kind !== 'counterexample' && kind !== 'fuzzing') return undefined
  return counterexampleViolationStep.value === undefined ? undefined : counterexampleViolationStep.value + 1
})

/**
 * All violation step indices for liveness counterexamples (the entire cycle), or
 * an array containing the single violation step for safety templates.
 * Used by the canvas to emphasize devices across multiple states.
 */
const counterexampleViolationSteps = computed<number[]>(() => {
  const trace = currentTrace.value
  const templateId = trace?.violatedSpec?.templateId
  if (!trace || !templateId) return []

  // Exploration finding: single step
  if (activeFuzzingFinding.value?.firstViolationStep !== undefined) {
    return [activeFuzzingFinding.value.firstViolationStep]
  }

  // Liveness templates: mark every step in the cycle
  if (LIVENESS_TEMPLATES.has(String(templateId))) {
    const range = counterexampleLoopRange.value
    if (!range) return []

    // Return all indices from start to end (inclusive)
    const steps: number[] = []
    for (let i = range.start; i <= range.end; i++) {
      steps.push(i)
    }
    return steps
  }

  // Safety templates: single last state. Read from the step computed rather than recomputing it, so the
  // canvas emphasis and the rail marker cannot disagree — they had two copies of the last-state rule, and
  // template 4's minimum-length floor would have had to be added to both.
  if (LAST_STATE_VIOLATION_TEMPLATES.has(String(templateId))) {
    const step = counterexampleViolationStep.value
    return step === undefined ? [] : [step]
  }

  return []
})

/**
 * The violation word this rail step carries, or `null` for an ordinary step.
 *
 * Reads `counterexampleViolationSteps`, the same set the canvas emphasis reads, because a liveness
 * counterexample has no single failing step: templates 2/5/6 are refuted by an infinite lasso path, so
 * `counterexampleViolationStep` is `undefined` for them by design and the rail — which tested only that
 * singular step — marked nothing at all. The canvas lit up every device in the cycle and the changes panel
 * explained the loop, while the one surface whose job is to show *where* in the path the failure lives
 * showed only the cursor star. That is the same silence template 4 had, in the branch that fix did not
 * reach. Measured on NuSMV 2.7.1 with the generator's own template-5 shape, `AG(motion -> AF(light))`
 * over a non-responding model: a 6-state counterexample whose cycle is states 5–6, so two steps to mark
 * and none marked. Five of the 42 specs in the shipped scenes are template 5, including the away-mode
 * unlock scene this file's emphasis test already cites as a measured liveness counterexample.
 *
 * The cycle gets its own word rather than repeating "Violation" on each of its states, which would read
 * as several separate faults instead of one cycle that is the fault.
 */
const traceStateViolationLabel = (index: number): string | null => {
  if (counterexampleViolationStep.value === index) {
    return activeFuzzingFinding.value ? t('app.fuzzFirstViolation') : t('app.traceViolationHere')
  }
  return counterexampleViolationSteps.value.includes(index) ? t('app.traceViolationCycle') : null
}

const getTraceStateAriaLabel = (index: number) => {
  const base = `${t('app.traceVisualization.state', { index: index + 1 })} (${index + 1}/${totalStates.value})`
  // The badge, marker hint and accessible name share the violation wording.
  const label = traceStateViolationLabel(index)
  return label ? `${base}, ${label}` : base
}

// 播放/停止动画
const toggleTraceAnimation = () => {
  if (traceAnimationState.value.isPlaying) {
    stopTraceAnimation()
  } else {
    startTraceAnimation()
  }
}

const startTraceAnimation = () => {
  if (traceAnimationState.value.isPlaying) return
  if (totalStates.value <= 1) return

  const trace = currentTrace.value
  if (!trace) return

  if (traceAnimationState.value.selectedStateIndex >= totalStates.value - 1) {
    goToState(0)
  }
  
  traceAnimationState.value.isPlaying = true
  playInterval = setInterval(() => {
    const activeTrace = currentTrace.value
    if (!activeTrace) {
      stopTraceAnimation()
      return
    }
    if (traceAnimationState.value.selectedStateIndex < totalStates.value - 1) {
      traceAnimationState.value.selectedStateIndex++
      highlightedTrace.value = {
        ...activeTrace,
        selectedStateIndex: traceAnimationState.value.selectedStateIndex
      }
      if (traceAnimationState.value.selectedStateIndex >= totalStates.value - 1) {
        stopTraceAnimation()
      }
    } else {
      // 到达最后一个状态时停止播放，不循环
      stopTraceAnimation()
    }
  }, 1500)
}

const stopTraceAnimation = () => {
  traceAnimationState.value.isPlaying = false
  if (playInterval) {
    clearInterval(playInterval)
    playInterval = null
  }
}


// `formattedSpec` lived here to feed the deleted "Violated Specification" card. The header states the
// specification via `getTraceSpecDisplayTitle`, which reads the same trace snapshot, so this second
// formatter had no remaining reader — `vue-tsc` said so, which is the check earning its place.

// 高亮反例路径
const handleHighlightTrace = (trace: HighlightedPlayback | null) => {
  if (trace && trace.states) {
    highlightedTrace.value = {
      states: trace.states,
      selectedStateIndex: trace.selectedStateIndex
    }
  }
}

// 清除高亮
// ==== Simulation Timeline Animation Logic ====

// 关闭模拟时间轴动画
const closeSimulationTimeline = (clearDeepLink = true) => {
  simulationAnimationState.value.visible = false
  highlightedTrace.value = null
  deactivatePlaybackScene()
  resetPlaybackTerminal()
  // For `run=simulation:<id>` the timeline *is* the addressed surface, so the URL must stop naming
  // it. Otherwise a refresh or a shared link reopens the playback the user deliberately closed, and
  // the board re-enters read-only playback mode.
  if (clearDeepLink) clearRunDeepLink()
}

const handleVerify = async (): Promise<boolean> => {
  if (isVerifying.value) return false
  const requestAuthScopeEpoch = boardAuthScopeEpoch
  const ownerUserId = currentAuthUserId.value
  const ownsRun = () => isCurrentBoardAuthScope(requestAuthScopeEpoch)
    && currentAuthUserId.value === ownerUserId
  if (!verificationForm.isAsync && synchronousSimulationRunning.value) {
    notifyBlocked(t('app.formalOperationBusy'))
    return false
  }
  if (isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return false
  }
  await waitForPendingFixRefresh()
  await waitForPendingBoardMutations()
  if (!ownsRun()) return false
  if (!ensureBoardDataReady()) return false
  if (nodes.value.length === 0) {
    notifyBlocked(t('app.noDevicesToVerify'))
    return false
  }
  if (specifications.value.length === 0) {
    notifyBlocked(t('app.noSpecsToVerify'))
    return false
  }
  if (!assertRulesHaveTriggers(rules.value)) {
    return false
  }
  if (verificationAttackConfigurationError.value) {
    notifyError(verificationAttackConfigurationError.value)
    return false
  }
  if (verificationForm.attackMode === 'ANY_UP_TO_BUDGET') {
    validateAttackBudget(verificationForm.attackBudget)
  }

  dismissCompletionNotifications()
  isVerifying.value = true
  asyncVerificationActive.value = false
  verificationCancelRequested.value = false
  cancellingVerificationTask.value = false
  verificationError.value = null
  verificationResult.value = null
  verificationResultStale.value = false
  // The sync path awaits `boardApi.verify` here, so it has the same exposure the async path documents
  // at `pollAsyncVerification`: a semantic board change while the request is open leaves the verdict
  // describing the submitted scene.
  const verifySubmissionSceneChanges = semanticSceneChangeCount

  try {
    const req = buildVerificationRequestPayload({
      attackScenario: buildRunAttackScenario(verificationForm),
      enablePrivacy: verificationForm.enablePrivacy
    })

    // Handle async or sync verification
    if (verificationForm.isAsync) {
      // Async verification. IMPORTANT: await the polling promise so the outer `finally`
      // (which sets isVerifying=false) only runs after polling truly ends — otherwise
      // the progress UI vanishes immediately and the button re-enables mid-run,
      // letting the user launch duplicate tasks.
      asyncVerificationActive.value = true
      asyncVerificationTask.value = { taskId: null, progress: 0, statusKey: 'app.taskInitializing' }

      const submittedTask = await boardApi.verifyAsync(req)
      if (!ownsRun()) return false
      const taskId = submittedTask.id
      asyncVerificationTask.value.taskId = taskId
      asyncVerificationTask.value.progress = submittedTask.progress ?? 0
      asyncVerificationTask.value.statusKey = taskProgressStatusKey(
        submittedTask.progressStage, submittedTask.status)
      upsertVerificationTaskSummary(submittedTask)

      await pollAsyncVerification(taskId)
      return true
    }

    // Sync verification (original logic)
    const presented = await boardApi.verify(req)
    if (!ownsRun()) return false
    // Same window as the async path (see `pollAsyncVerification`): this await is long enough for the user
    // to open a replay, and `closeResultDialog` nulls `verificationResult` when they do, so assigning it
    // here would raise the modal dialog over a running trace.
    if (isModelPlaybackActive.value) {
      notifyVerificationOutcome(presented)
      if (['FAILED', 'OUTCOME_UNKNOWN'].includes(presented.historyPersistence.status)) {
        notifyBlocked(presented.historyPersistence.status === 'OUTCOME_UNKNOWN'
            ? t('app.verificationHistorySaveOutcomeUnknown')
            : t('app.verificationHistorySaveFailed'))
        let close!: () => void
        close = notifyAvailableResult({
          title: t('app.verificationTemporaryResultTitle'),
          message: t('app.verificationTemporaryResultMessage'),
          actionLabel: t('app.openResult'),
          discardLabel: t('app.discardLocalResult'),
          onAction: () => {
            if (!ownsRun()) return true
            if (!ensureHistoricalPlaybackUiAdmission()) return false
            closeOtherWorkflowSurfaces()
            adoptVerificationRunResult(presented, verifySubmissionSceneChanges)
            return true
          },
          onClose: () => unsavedRunNotifications.delete(close)
        })
        unsavedRunNotifications.add(close)
      } else {
        notifyInfo(t('app.verificationResultDeferredForPlayback'))
      }
      // Refresh confirmed saved runs and reconcile an uncertain save without claiming the local result exists there.
      void loadVerificationRuns(false)
      return true
    }
    adoptVerificationRunResult(presented, verifySubmissionSceneChanges)
    if (['FAILED', 'OUTCOME_UNKNOWN'].includes(presented.historyPersistence.status)) {
      notifyBlocked(presented.historyPersistence.status === 'OUTCOME_UNKNOWN'
          ? t('app.verificationHistorySaveOutcomeUnknown')
          : t('app.verificationHistorySaveFailed'))
      void loadVerificationRuns(false)
    }
    notifyVerificationOutcome(verificationResult.value, { presenting: true })
    return true

  } catch (error: any) {
    if (!ownsRun()) return false
    if (isPollingAbortedError(error)) {
      return false
    }
    if (isAsyncTaskCancelledError(error)) {
      verificationError.value = null
      if (!verificationCancelRequested.value) notifyInfo(t('app.verificationCancelled'))
    } else {
      console.error('Verification failed:', error)
      presentRunFailure('verification', 'app.verificationFailed', error)
    }
    return false
  } finally {
    isVerifying.value = false
    asyncVerificationActive.value = false
    cancellingVerificationTask.value = false
    verificationCancelRequested.value = false
  }
}

const runVerification = async () => {
  const completed = await handleVerify()
  if (completed && !verificationForm.isAsync) {
    closeVerificationPanel()
  }
}

const runFuzzing = async (): Promise<boolean> => {
  if (isFuzzing.value) return false
  if (isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return false
  }
  await waitForPendingFixRefresh()
  await waitForPendingBoardMutations()
  if (!ensureBoardDataReady(['templates', 'nodes', 'environment', 'rules', 'specs'])) return false
  if (nodes.value.length === 0) {
    notifyBlocked(t('app.noDevicesToFuzz'))
    return false
  }
  if (specifications.value.length === 0) {
    notifyBlocked(t('app.noSpecsToFuzz'))
    return false
  }
  if (!assertRulesHaveTriggers(rules.value)) return false

  if (!fuzzingWorkloadReady.value) {
    if (!fuzzingWorkloadPreviewLoading.value && hasValidFuzzingBudget(fuzzingForm)) {
      scheduleFuzzingWorkloadPreview()
    }
    notifyBlocked(fuzzingWorkloadPreviewError.value || t('app.fuzzWorkloadRequired'))
    return false
  }

  if (fuzzingContentCommandUnsupported.value) {
    notifyBlocked(t('app.fuzzContentCommandPreflightBlocked'))
    return false
  }

  const eligibleSpecIds = knownFuzzEligibleSpecifications.value.map(spec => spec.id)
  if (eligibleSpecIds.length === 0) {
    notifyBlocked(t('app.noEligibleSpecsToFuzz'))
    return false
  }
  if (effectiveFuzzingConfigurationError.value) {
    notifyBlocked(effectiveFuzzingConfigurationError.value)
    return false
  }
  const requestTargetSpecIds = fuzzingForm.targetSelectionMode === 'EXPLICIT'
    ? normalizedFuzzTargetSpecIds.value
    : (eligibleSpecIds.length === specifications.value.length ? [] : eligibleSpecIds)
  const requestFields = {
    maxIterations: fuzzingForm.maxIterations,
    pathLength: fuzzingForm.pathLength,
    populationSize: fuzzingForm.populationSize,
    ...(requestTargetSpecIds.length > 0
      ? { targetSpecIds: [...requestTargetSpecIds] }
      : {}),
    ...(fuzzingForm.seed === null ? {} : { seed: fuzzingForm.seed })
  }
  const paperDomainFingerprint = paperDomainPreview.value?.modelFingerprint
  let request: FuzzingRequest
  if (fuzzingForm.explorationMode === 'PAPER_COMPATIBLE') {
    if (!isValidFuzzPaperDomainFingerprint(paperDomainFingerprint)) {
      notifyBlocked(t('app.fuzzPaperDomainRequired'))
      return false
    }
    request = {
      ...requestFields,
      explorationMode: 'PAPER_COMPATIBLE',
      paperDomainFingerprint
    }
  } else {
    request = {
      ...requestFields,
      explorationMode: 'BOARD_SNAPSHOT'
    }
  }

  isFuzzing.value = true
  fuzzingWatchedTask.value = null
  asyncFuzzingActive.value = true
  cancellingFuzzingTask.value = false
  fuzzingCancelRequested.value = false
  fuzzingError.value = null
  fuzzingSettingsNotice.value = null
  asyncFuzzingTask.value = { taskId: null, progress: 0, statusKey: 'app.taskInitializing' }
  let submittedTaskId: number | null = null
  let submittedTaskInitiator: RunInitiator = 'USER'

  try {
    const submittedTask = await fuzzingApi.startAsync(request)
    submittedTaskId = submittedTask.id
    submittedTaskInitiator = submittedTask.initiator
    trackFuzzTask(submittedTask.id)
    asyncFuzzingTask.value = {
      taskId: submittedTask.id,
      progress: normalizeTaskProgress(submittedTask.progress),
      statusKey: taskProgressStatusKey(submittedTask.progressStage, submittedTask.status)
    }
    upsertFuzzingTaskSummary(submittedTask)
    fuzzingWatchedTask.value = submittedTask

    const run = await pollAsyncFuzzing(submittedTask.id)
    untrackFuzzTask(submittedTask.id)
    const shouldPresent = showFuzzingPanel.value
    showFuzzingPanel.value = false
    if (shouldPresent) {
      void refreshCurrentBoardModelFingerprints()
      presentFuzzingRun(run)
    } else {
      const notificationShown = markFuzzNotificationUnread({
        taskId: submittedTask.id,
        runId: run.id,
        kind: 'COMPLETED',
        initiator: run.initiator,
        outcome: run.outcome,
        createdAt: run.completedAt
      })
      if (notificationShown && run.outcome === 'BUDGET_EXHAUSTED') {
        notifyInfo(fuzzingCompletionMessage(run))
      } else if (notificationShown) {
        notifyBlocked(fuzzingCompletionMessage(run))
      }
    }
    return true
  } catch (error: any) {
    if (isPollingAbortedError(error)) return false
    if (isAsyncTaskCancelledError(error)) {
      if (submittedTaskId) untrackFuzzTask(submittedTaskId)
      fuzzingError.value = null
      if (!fuzzingCancelRequested.value) notifyInfo(t('app.fuzzSearchCancelled'))
    } else if (submittedTaskId && isFuzzTaskRecoveryPendingError(error)) {
      fuzzingError.value = null
      fuzzingSettingsNotice.value = { key: 'app.fuzzResultRecoveryPending' }
      if (!showFuzzingPanel.value) {
        notifyInfo(fuzzingSettingsNoticeMessage.value)
      }
    } else if (submittedTaskId && isFuzzCompletedResultUnavailableError(error)) {
      fuzzingError.value = makeRunFailure('app.failedToLoadFuzzingRun', error)
      markFuzzNotificationUnread({
        taskId: submittedTaskId,
        runId: submittedTaskId,
        kind: 'UNAVAILABLE',
        initiator: submittedTaskInitiator,
        createdAt: new Date().toISOString()
      })
      if (!showFuzzingPanel.value) {
        notifyError(fuzzingErrorMessage.value!)
      }
    } else {
      console.error('Fuzz search failed:', error)
      const stalePaperDomain = submittedTaskId === null
        && request.explorationMode === 'PAPER_COMPATIBLE'
        && hasApiValidationError(error, 'paperDomainFingerprint')
      if (stalePaperDomain) {
        paperDomainStaleRecoveryActive.value = true
        const boardRefreshed = await refreshSceneForReconciliation()
        if (boardRefreshed) markVerificationResultStale()
        invalidatePaperDomainPreview()
        fuzzingError.value = makeRunFailure(boardRefreshed
          ? 'app.fuzzPaperDomainStale'
          : 'app.fuzzPaperDomainStaleBoardRefreshFailed', error)
        if (boardRefreshed && showFuzzingPanel.value && validPaperPathLength()) {
          schedulePaperDomainPreview()
        } else if (!showFuzzingPanel.value) {
          notifyBlocked(fuzzingErrorMessage.value!)
        }
      } else {
        fuzzingError.value = makeRunFailure('app.fuzzSearchFailed', error)
      }
      if (submittedTaskId && !showFuzzingPanel.value) {
        markFuzzNotificationUnread({
          taskId: submittedTaskId,
          kind: 'FAILED',
          initiator: submittedTaskInitiator,
          createdAt: new Date().toISOString()
        })
        notifyError(fuzzingErrorMessage.value!)
      } else if (submittedTaskId) {
        untrackFuzzTask(submittedTaskId)
      } else if (!showFuzzingPanel.value && !stalePaperDomain) {
        notifyError(fuzzingErrorMessage.value!)
      }
    }
    return false
  } finally {
    isFuzzing.value = false
    asyncFuzzingActive.value = false
    cancellingFuzzingTask.value = false
    fuzzingCancelRequested.value = false
    fuzzingWatchedTask.value = null
    if (!boardLifecycleDisposed) {
      await loadTaskInbox({ showLoading: false })
    }
  }
}

// Run simulation with proper panel handling
const runSimulation = async () => {
  await handleSimulate({ ...simulationForm })
}

const handleSimulate = async (simConfig: {
  steps: number
  isAttack: boolean
  attackMode: AttackScenarioMode
  attackBudget: number
  selectedAttackPointKeys: string[]
  enablePrivacy: boolean
  isAsync: boolean
  saveToHistory?: boolean
}): Promise<boolean> => {
  if (isSimulating.value) return false
  const requestAuthScopeEpoch = boardAuthScopeEpoch
  const ownerUserId = currentAuthUserId.value
  const ownsRun = () => isCurrentBoardAuthScope(requestAuthScopeEpoch)
    && currentAuthUserId.value === ownerUserId
  if (!simConfig.isAsync && synchronousVerificationRunning.value) {
    notifyBlocked(t('app.formalOperationBusy'))
    return false
  }
  if (isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return false
  }
  await waitForPendingFixRefresh()
  await waitForPendingBoardMutations()
  if (!ownsRun()) return false
  if (!ensureBoardDataReady(['templates', 'nodes', 'environment', 'rules'])) return false
  if (nodes.value.length === 0) {
    notifyBlocked(t('app.noDevicesToSimulate'))
    return false
  }
  if (!assertRulesHaveTriggers(rules.value)) {
    return false
  }
  const normalizedSimConfig = { ...simConfig }
  let requestSteps = 10
  try {
    requestSteps = validateSimulationSteps(normalizedSimConfig.steps)
  } catch (error: any) {
    notifyError(error?.message || t('app.simulationFailed'))
    return false
  }
  const simulationScenarioError = attackConfigurationError(normalizedSimConfig, false)
  if (simulationScenarioError) {
    notifyError(simulationScenarioError)
    return false
  }

  isSimulating.value = true
  asyncSimulationActive.value = false
  simulationCancelRequested.value = false
  cancellingSimulationTask.value = false
  simulationError.value = null
  simulationResultStale.value = false
  /*
   * See `pollAsyncVerification` for the reasoning; a simulation trajectory has the same exposure. An
   * async run awaits `pollAsyncSimulation` here, so a semantic change in between produces a trajectory
   * of the submitted scene which the shared completion path below then presented as describing the
   * current canvas. Captured for both paths because the sync path also awaits a request.
   */
  const simulationSubmissionSceneChanges = semanticSceneChangeCount

  // 重置异步任务状态
  if (normalizedSimConfig.isAsync) {
    asyncSimulationActive.value = true
    asyncSimulationTask.value = { taskId: null, progress: 0, statusKey: 'app.taskInitializing' }
  }

  try {
    const req = buildSimulationRequestPayload({
      steps: requestSteps,
      attackScenario: buildRunAttackScenario(normalizedSimConfig),
      enablePrivacy: normalizedSimConfig.enablePrivacy
    })
    
    let result: SimulationResult | SavedSimulationResult

    if (normalizedSimConfig.isAsync) {
      // 异步模拟：创建任务并轮询进度
      const submittedTask = await simulationApi.simulateAsync(req)
      const taskId = submittedTask.id
      asyncSimulationTask.value.taskId = taskId
      asyncSimulationTask.value.progress = submittedTask.progress ?? 0
      asyncSimulationTask.value.statusKey = taskProgressStatusKey(
        submittedTask.progressStage, submittedTask.status)
      upsertSimulationTaskSummary(submittedTask)

      // 轮询任务进度
      result = await pollAsyncSimulation(taskId)
    } else {
      // 同步模拟
      if (normalizedSimConfig.saveToHistory) {
        const trace = await simulationApi.simulateAndSave(req)
        result = simulationResultFromTrace(trace)
      } else {
        result = await simulationApi.simulate(req)
      }
    }

    if (!ownsRun()) return false
    if (['FAILED', 'OUTCOME_UNKNOWN'].includes(result.historyPersistence?.status)) {
      notifyBlocked(result.historyPersistence.status === 'OUTCOME_UNKNOWN'
          ? t('app.simulationHistorySaveOutcomeUnknown')
          : t('app.simulationHistorySaveFailed'))
      void loadSimulationRuns(false)
    }
    
    // Keep the full result so its logs / NuSMV diagnostics remain reachable from the timeline via
    // openSimulationLogs(); the success path opens the timeline (below), not the result dialog.
    const summary = 'createdAt' in result ? summarizeSimulationResult(result) : null
    if (summary) {
      simulationHistoryRequests.invalidate()
      simulationRuns.value = [summary, ...simulationRuns.value.filter(item => item.id !== summary.id)]
    }

    // 直接打开时间轴动画，不显示结果对话框
    // The visible replay owns the trajectory and frozen scene, regardless of how the new run was executed.
    if (traceAnimationState.value.visible || simulationAnimationState.value.visible) {
      notifySimulationOutcome(result, result.historyPersistence.status === 'SAVED')
      notifyAutomaticPlaybackDeferredForReplay(result, simulationSubmissionSceneChanges)
      return true
    }
    if (normalizedSimConfig.isAsync) {
      if (!showSimulationPanel.value || isLiveBoardEditorVisible.value) {
        adoptSimulationRunResult(result, simulationSubmissionSceneChanges)
        notifySimulationOutcome(result, true)
        if (isLiveBoardEditorVisible.value) notifyAutomaticPlaybackDeferred()
        return true
      }

      adoptSimulationRunResult(result, simulationSubmissionSceneChanges)
      showSimulationPanel.value = false
      openSimulationAnimationFromSavedStates()
      return true
    }

    // Adopting the run also selects its states before playback opens.
    adoptSimulationRunResult(result, simulationSubmissionSceneChanges)

    // 关闭模拟配置面板
    showSimulationPanel.value = false

    // 打开模拟时间轴动画
    openSimulationAnimationFromSavedStates()
    return true

  } catch (error: any) {
    if (!ownsRun()) return false
    if (isPollingAbortedError(error)) {
      return false
    }
    if (isAsyncTaskCancelledError(error)) {
      simulationError.value = null
      if (!simulationCancelRequested.value) notifyInfo(t('app.simulationCancelled'))
    } else {
      console.error('Simulation failed:', error)
      presentRunFailure('simulation', 'app.simulationFailed', error)
    }
    return false
  } finally {
    isSimulating.value = false
    asyncSimulationActive.value = false
    cancellingSimulationTask.value = false
    simulationCancelRequested.value = false
  }
}

// A status/progress fetch error is "permanent" (fail fast, don't retry to timeout) when
// it is an auth/not-found/client error — retrying will never succeed. Network blips and
// 5xx are treated as transient.
const isPermanentPollError = (error: any): boolean => {
  if (error?.code === RUN_RESPONSE_INCOMPLETE_CODE
    || error?.code === FUZZ_RESPONSE_INCOMPLETE_CODE) return true
  const status = error?.response?.status
  return typeof status === 'number'
    && status >= 400
    && status < 500
    && !isTransientTaskHttpStatus(status)
}

const completedTaskResultError = (
  kind: 'verification' | 'simulation',
  error: unknown
) => new CompletedTaskResultUnavailableError(kind, error)

const loadCompletedTaskResult = async <T,>(
  load: () => Promise<T>,
  expectedPollingEpoch: number
): Promise<T> =>
  loadBoardResultWithRetry({
    load,
    shouldRetry: error => !isPermanentPollError(error),
    waitBeforeRetry: failedAttempt => waitForPollingDelay(
      fuzzRunRetryDelayMs(failedAttempt - 1),
      expectedPollingEpoch
    ),
    maxAttempts: FUZZ_INLINE_RESULT_RECOVERY_MAX_FAILURES
  })

// 轮询异步验证任务：await 到终态/超时/永久错误为止，供 handleVerify await。
// 用 while + await sleep（而非 setInterval + async 回调）：串行执行，天然无重入——
// 若某次状态查询超过 1s 也不会并发发起下一轮、不会重复 toast 或旧响应覆盖新进度。
const pollAsyncVerification = async (
  taskId: number,
  options: { presentResult?: boolean } = {}
): Promise<void> => {
  let pollCount = 0
  const expectedPollingEpoch = pollingEpoch
  /*
   * The board can be edited while this run is in flight, and the arriving verdict describes the model
   * that was frozen at submission — not the canvas the user is now looking at.
   *
   * The staleness hook in the mutation queue cannot cover this: it flags `verificationResult`, which is
   * null for the whole duration of an async run (`handleVerify` clears it before submitting), so a
   * mid-run edit marks nothing and the completion path then sets `verificationResultStale = false`
   * unconditionally. The result presented itself as describing the current board while the Fix action it
   * offered was computed against a scene that no longer exists.
   *
   * `semanticSceneChangeCount` is incremented by the same staleness hook, so "what counts as a change"
   * cannot drift between the two. Comparing it across the run is what a `verificationResult` watcher
   * structurally cannot do.
   */
  const submissionSceneChanges = semanticSceneChangeCount

  while (pollCount < ASYNC_TASK_MAX_POLLS) {
    throwIfPollingAborted(expectedPollingEpoch)
    let task: VerificationTask
    try {
      task = await boardApi.getTask(taskId)
      throwIfPollingAborted(expectedPollingEpoch)
      asyncVerificationTask.value.progress = normalizeTaskProgress(task.progress)
      asyncVerificationTask.value.statusKey = taskProgressStatusKey(task.progressStage, task.status)
      upsertVerificationTaskSummary(task)
    } catch (e: any) {
      if (isPollingAbortedError(e)) {
        throw e
      }
      // Permanent errors (401/403/404/…) fail fast; transient errors retry.
      if (isPermanentPollError(e)) {
        throw e
      }
      await waitForNextPoll(expectedPollingEpoch)
      pollCount++
      continue
    }

    // Terminal-state handling outside the try so its logic isn't swallowed by the catch.
    if (task.status === 'COMPLETED') {
      asyncVerificationTask.value.progress = 100
      let traces: Trace[] = []
      try {
        traces = task.outcome === 'SATISFIED'
          ? []
          : await loadCompletedTaskResult(
            () => boardApi.getTaskTraces(taskId),
            expectedPollingEpoch
          )
      } catch (error) {
        if (isPollingAbortedError(error)) throw error
        upsertVerificationTaskSummary({ ...task, progress: 100 })
        await loadVerificationRuns(false)
        throw completedTaskResultError('verification', error)
      }
      throwIfPollingAborted(expectedPollingEpoch)
      const result = buildVerificationResultFromTask(task, traces)
      upsertVerificationTaskSummary({ ...task, progress: 100 })
      /*
       * A replay on screen outranks presenting a new verdict, and here that is not merely a matter of
       * stacking: opening a counterexample calls `closeResultDialog`, so `verificationResult` is null for
       * the whole time the bar animates. Re-assigning it on arrival therefore made the `aria-modal` result
       * dialog spring up over the trace the user was watching, taking the focus trap and the scroll lock
       * from a non-modal bar that kept running underneath.
       *
       * Reachable the same way as the simulation and exploration cases: playback admission does not
       * consider `isVerifying`, so watching a task from the inbox and then replaying something from history
       * is ordinary use. The verdict is not lost — it is announced as a toast (the branch a closed panel
       * already takes) and saved to run history by the `loadVerificationRuns` below.
       */
      // Conditioned on presentation, not on playback alone: a background run whose panel is closed was
      // never going to open a dialog, so telling that user "the dialog did not open because of the replay"
      // would name a cause the client knows is false.
      const wouldPresent = options.presentResult || showVerificationPanel.value
      const deferForPlayback = wouldPresent && isModelPlaybackActive.value
      if (wouldPresent && !deferForPlayback) {
        adoptVerificationRunResult(result, submissionSceneChanges)
        notifyVerificationOutcome(verificationResult.value, { presenting: true })
        showVerificationPanel.value = false
      } else {
        notifyVerificationOutcome(result)
        // The panel must still close, or a deferred run leaves the progress panel up behind the replay as
        // though it were still working. And say why nothing opened: the outcome toast above does not
        // explain the absence of the dialog the user was waiting for.
        if (deferForPlayback) {
          showVerificationPanel.value = false
          notifyInfo(t('app.verificationResultDeferredForPlayback'))
        }
      }
      await loadVerificationRuns()
      return
    } else if (task.status === 'FAILED') {
      throw new Error(task.errorMessage || t('app.verificationFailed'))
    } else if (task.status === 'CANCELLED') {
      throw new AsyncTaskCancelledError(task.errorMessage || t('app.verificationCancelled'))
    }

    // 仍在 PENDING/RUNNING，等待后继续
    await waitForNextPoll(expectedPollingEpoch)
    pollCount++
  }

  throw Object.assign(new Error('Verification task status polling timed out'), { code: 'TASK_POLL_TIMEOUT' })
}

// 轮询异步模拟任务
const pollAsyncSimulation = async (taskId: number): Promise<SavedSimulationResult> => {
  let pollCount = 0
  const expectedPollingEpoch = pollingEpoch

  while (pollCount < ASYNC_TASK_MAX_POLLS) {
    throwIfPollingAborted(expectedPollingEpoch)
    let task: SimulationTask
    try {
      // 获取任务进度 + 状态（瞬时网络错误容忍：进入 catch 后继续轮询）
      task = await simulationApi.getTask(taskId)
      throwIfPollingAborted(expectedPollingEpoch)
      asyncSimulationTask.value.progress = normalizeTaskProgress(task.progress)
      asyncSimulationTask.value.statusKey = taskProgressStatusKey(task.progressStage, task.status)
      upsertSimulationTaskSummary(task)
    } catch (error: any) {
      if (isPollingAbortedError(error)) {
        throw error
      }
      // Permanent errors (401/403/404/task-not-found) fail fast; only transient
      // errors (network blips, 5xx) retry until the poll ceiling.
      if (isPermanentPollError(error)) {
        throw error
      }
      console.error('Poll error (transient, will retry):', error)
      await waitForNextPoll(expectedPollingEpoch)
      pollCount++
      continue
    }

    // 终态处理放在 try 之外：FAILED/CANCELLED 必须立即抛出并中止轮询，
    // 不能被上面的瞬时错误 catch 吞掉（否则会一直轮询到超时才报通用错误）。
    if (task.status === 'COMPLETED') {
      asyncSimulationTask.value.progress = 100
      if (task.simulationTraceId) {
        let trace: Awaited<ReturnType<typeof simulationApi.getSimulation>>
        try {
          trace = await loadCompletedTaskResult(
            () => simulationApi.getSimulation(task.simulationTraceId as number),
            expectedPollingEpoch
          )
        } catch (error) {
          if (isPollingAbortedError(error)) throw error
          upsertSimulationTaskSummary({ ...task, progress: 100 })
          await loadSimulationRuns(false)
          throw completedTaskResultError('simulation', error)
        }
        throwIfPollingAborted(expectedPollingEpoch)
        upsertSimulationTaskSummary({ ...task, progress: 100 })
        await loadSimulationRuns()
        return simulationResultFromTrace(trace)
      }
      upsertSimulationTaskSummary({ ...task, progress: 100 })
      throw new Error(t('app.taskCompletedNoTraceFound'))
    } else if (task.status === 'FAILED') {
      throw new Error(task.errorMessage || t('app.asyncSimulationFailed'))
    } else if (task.status === 'CANCELLED') {
      throw new AsyncTaskCancelledError(t('app.simulationTaskCancelledByServer'))
    }

    // 仍在 PENDING/RUNNING，等待后继续
    await waitForNextPoll(expectedPollingEpoch)
    pollCount++
  }

  // 超出最大轮询次数
  throw Object.assign(new Error('Simulation task status polling timed out'), { code: 'TASK_POLL_TIMEOUT' })
}

const pollAsyncFuzzing = async (taskId: number): Promise<FuzzingRun> => {
  let pollCount = 0
  let completedResultFailures = 0
  const expectedPollingEpoch = pollingEpoch

  while (pollCount < ASYNC_TASK_MAX_POLLS) {
    throwIfPollingAborted(expectedPollingEpoch)
    let task: FuzzingTask
    try {
      task = await fuzzingApi.getTask(taskId)
      throwIfPollingAborted(expectedPollingEpoch)
      asyncFuzzingTask.value.progress = normalizeTaskProgress(task.progress)
      asyncFuzzingTask.value.statusKey = taskProgressStatusKey(task.progressStage, task.status)
      upsertFuzzingTaskSummary(task)
    } catch (error: any) {
      if (isPollingAbortedError(error)) throw error
      if (isPermanentPollError(error)) throw error
      await waitForNextPoll(expectedPollingEpoch)
      pollCount++
      continue
    }

    if (task.status === 'COMPLETED') {
      asyncFuzzingTask.value.progress = 100
      try {
        const run = await loadFuzzRunSingleFlight(taskId, task.runId ?? task.id)
        clearFuzzRunRecoveryState(taskId)
        upsertFuzzingRunSummary(summarizeFuzzingRun(run))
        return run
      } catch (error: any) {
        if (classifyTrackedFuzzRunError(error) !== 'RETRY') {
          throw new FuzzCompletedResultUnavailableError(error)
        }
        asyncFuzzingTask.value.statusKey = 'app.fuzzResultRecoveryPending'
        const retryDelay = scheduleFuzzRunRecovery(taskId)
        completedResultFailures++
        if (completedResultFailures >= FUZZ_INLINE_RESULT_RECOVERY_MAX_FAILURES) {
          throw new FuzzTaskRecoveryPendingError()
        }
        await waitForPollingDelay(retryDelay, expectedPollingEpoch)
        pollCount++
        continue
      }
    }
    if (task.status === 'FAILED') {
      throw new Error(task.errorMessage || t('app.fuzzSearchFailed'))
    }
    if (task.status === 'CANCELLED' || fuzzingCancelRequested.value) {
      throw new AsyncTaskCancelledError(task.errorMessage || t('app.fuzzSearchCancelled'))
    }

    await waitForNextPoll(expectedPollingEpoch)
    pollCount++
  }

  throw new FuzzTaskRecoveryPendingError()
}

// ==== Results Dialog ====
const showResultDialog = computed(() => !!verificationResult.value || !!verificationError.value)
/**
 * Closes the verification result surface. Also used as an internal transition (opening a
 * counterexample replay hides the dialog), so it must not touch the URL — otherwise replaying
 * a trace would strip the very params describing it. `dismissResultDialog` is the user-facing
 * close that clears the deep link.
 */
const closeResultDialog = () => {
  verificationResult.value = null
  verificationResultStale.value = false
  verificationError.value = null
}

const dismissResultDialog = () => {
  const target = deepLinkTarget.value
  const dismissesDeepLink = !isModelPlaybackActive.value
    && target?.kind === 'verification'
    && target.traceId === undefined
    && verificationResult.value?.historyPersistence.runId === target.runId
  // Closing the displayed result cancels its load; a newer URL target keeps its own request.
  if (dismissesDeepLink || !target) historyDetailRequests.invalidate()
  closeResultDialog()
  if (dismissesDeepLink) clearRunDeepLink()
}

const reopenVerificationSettingsFromResult = () => {
  if (isModelPlaybackActive.value || isAnyRecommendationRunning()) {
    openVerificationFromActionDock()
    return
  }
  dismissResultDialog()
  // The dock toggles visibility; this action must always open settings after leaving the old run.
  showVerificationPanel.value = false
  openVerificationFromActionDock()
}
const {
  setDialogRef: setVerificationResultDialogRef,
  handleModalKeydown: handleVerificationResultDialogKeydown
} = useModalAccessibility(
  showResultDialog,
  dismissResultDialog,
  opener => document.querySelector<HTMLElement>(opener?.closest('[data-testid="trace-history-panel"]')
    ? '[data-testid="open-history-panel"]' : '[data-testid="open-verification-panel"]')
)
const isSimulationResultDialogOpen = computed(() => !!simulationError.value)
const closeSimulationResultDialog = () => {
  simulationError.value = null
}

const dismissSimulationResultDialog = (deletedRunId?: number) => {
  // This surface presents errors only; deletion teardown supplies the run identity it owns.
  const dismissesDeepLink = !isModelPlaybackActive.value
    && deepLinkTarget.value?.kind === 'simulation'
    && deepLinkTarget.value.runId === deletedRunId
  closeSimulationResultDialog()
  if (dismissesDeepLink) clearRunDeepLink()
}
const {
  setDialogRef: setSimulationResultDialogRef,
  handleModalKeydown: handleSimulationResultDialogKeydown
} = useModalAccessibility(
  isSimulationResultDialogOpen,
  dismissSimulationResultDialog,
  () => document.querySelector<HTMLElement>('[data-testid="run-simulation"]:not(:disabled)')
    ?? document.querySelector<HTMLElement>('[data-testid="open-simulation-panel"]')
)

// The owning run is addressed by URL even when replay was opened directly from history.
const openOwningVerificationRun = (runId: number) => {
  void openRunTarget({ kind: 'verification', runId })
}

/* ===== Deep-linkable run surfaces =====
 * The URL owns which saved run is open. History openers load through it; run completion
 * adopts the result already received and its URL together, so refresh can use the same loader.
 * Back/forward and pasted links use that loader too. Panel layout and canvas transform stay out
 * of the URL on purpose — they are already persisted server-side per user.
 * Contract: docs/guides/frontend-ui-conventions.md */
const deepLinkTarget = computed(() => parseBoardRunTarget(route.query))
const staleDeepLink = ref(false)

/** The target currently reflected on screen, so the watcher can skip redundant loads. */
let appliedDeepLinkTarget: BoardRunTarget | null = null
const deepLinkLoadRequests = createLatestBoardRequestGuard()
type DeepLinkLoadContext = { requestEpoch: number }
const isCurrentDeepLinkLoad = (context: DeepLinkLoadContext | undefined): boolean =>
  context !== undefined && deepLinkLoadRequests.isCurrent(context.requestEpoch)

/**
 * Navigation we initiate ourselves must not be re-applied by the target watcher (a redundant
 * refetch) and must not clear a stale-link notice this navigation was made to report.
 * History openers load after writing the URL; completion already has the result.
 * Recording `appliedDeepLinkTarget` is deterministic, unlike a boolean whose lifetime
 * depends on when the watcher happens to flush.
 */
const navigateToRunTarget = async (target: BoardRunTarget | null, mode: 'push' | 'replace') => {
  if (isSameBoardRunTarget(target, deepLinkTarget.value)) return
  appliedDeepLinkTarget = target
  const query = applyBoardRunTarget(route.query, target)
  await (mode === 'push' ? router.push({ query }) : router.replace({ query }))
}

/**
 * Opening a run is a `push` so Back closes it; clearing or correcting one is a `replace` so
 * dismissing a surface does not leave a dead history entry the user must step over.
 */
const openRunTarget = async (target: BoardRunTarget) => {
  staleDeepLink.value = false
  await navigateToRunTarget(target, 'push')
  await applyDeepLinkTarget(target)
}

const clearRunTarget = () => navigateToRunTarget(null, 'replace')

/**
 * Marks "no run is open" immediately, then clears the URL. The synchronous part matters: a
 * caller may dismiss one surface and open another in the same tick (reuse exploration
 * settings), and the pending navigation must not let the sync watcher reopen what the user
 * just left.
 */
clearRunDeepLink = () => {
  appliedDeepLinkTarget = null
  void clearRunTarget()
}


const isResultSurfaceVisible = computed(() =>
  showResultDialog.value || !!simulationError.value
  || showFuzzingResultDialog.value
)

// The incoming URL already owns navigation; ending the old replay must not rewrite it.
const closePlaybackForNavigation = () => {
  if (traceAnimationState.value.visible) closeTraceAnimation(false)
  if (simulationAnimationState.value.visible) closeSimulationTimeline(false)
}

/**
 * Applies the URL target to the board. Loaders already guard against races and stale
 * responses, so this only decides *what* to open and reports an unusable link once.
 */
const applyDeepLinkTarget = async (target: BoardRunTarget | null) => {
  const deepLinkLoad: DeepLinkLoadContext = { requestEpoch: deepLinkLoadRequests.begin() }
  appliedDeepLinkTarget = target
  closePlaybackForNavigation()
  // The URL is changing authority. Leaving A visible while B is loading makes a temporary B
  // failure look like A succeeded, and lets its child id be checked against the wrong parent.
  closeResultSurfaces()
  if (!target) {
    return
  }

  await loadDeepLinkTarget(target, deepLinkLoad)
}

const loadDeepLinkTarget = async (
  target: BoardRunTarget,
  deepLinkLoad: DeepLinkLoadContext
) => {
  if (target.kind === 'verification') {
    if (target.traceId !== undefined) {
      await selectAndPlayVerificationTrace(target.traceId, deepLinkLoad, target.runId)
      return
    }
    const loaded = await openVerificationRun(target.runId, deepLinkLoad)
    if (!loaded || !isCurrentDeepLinkLoad(deepLinkLoad)) return
    return
  }
  if (target.kind === 'simulation') {
    await selectAndPlaySimulationTrace(target.runId, deepLinkLoad)
    return
  }
  if (target.findingId !== undefined) {
    await selectAndPlayFuzzingFinding(target.findingId, target.runId, deepLinkLoad)
    return
  }
  await openFuzzingRun(target.runId, deepLinkLoad)
}

/**
 * Reconciles the board with the URL. Runs on every URL change we did not initiate
 * (Back/Forward, a link pasted into a live tab) and once the snapshot becomes ready, because
 * the run loaders need board data and a cold load arrives before it exists. Watching readiness
 * also covers an account switch, which reloads the snapshot underneath an unchanged URL.
 */
const syncBoardToDeepLink = async () => {
  if (!isBoardDataReady.value) return

  // Checked before the no-change short-circuit: malformed params parse to `null`, which
  // compares equal to "nothing open", so the link would otherwise be silently ignored. A
  // syntactically dead link needs no explanation beyond a clean board — only a well-formed
  // link to a run we cannot load warrants the banner.
  if (hasUnusableBoardRunParams(route.query)) {
    deepLinkLoadRequests.invalidate()
    appliedDeepLinkTarget = null
    closePlaybackForNavigation()
    closeResultSurfaces()
    // Stripped directly: `navigateToRunTarget(null)` would see an already-`null` target and
    // decide there is nothing to do, leaving the dead params in the URL.
    void router.replace({ query: applyBoardRunTarget(route.query, null) })
    return
  }

  const target = deepLinkTarget.value
  if (isSameBoardRunTarget(target, appliedDeepLinkTarget)) return
  staleDeepLink.value = false
  await applyDeepLinkTarget(target)
}

watch([() => route.query, isBoardDataReady], syncBoardToDeepLink, { immediate: true })

/**
 * A link can be malformed, or name a run that was deleted or belongs to another account.
 * Both degrade to the plain board with one persistent, dismissible explanation — never a
 * fabricated empty result — and the dead params are stripped so a refresh stays clean.
 */
const reportUnusableDeepLink = (deepLinkLoad?: DeepLinkLoadContext) => {
  if (deepLinkLoad && !isCurrentDeepLinkLoad(deepLinkLoad)) return
  staleDeepLink.value = true
  deepLinkLoadRequests.invalidate()
  appliedDeepLinkTarget = null
  closePlaybackForNavigation()
  closeResultSurfaces()
  // Strip directly rather than via `navigateToRunTarget`, which no-ops when the parsed
  // target is already `null` (a well-formed link whose run failed to load, or dead params).
  void router.replace({ query: applyBoardRunTarget(route.query, null) })
}

const dismissStaleDeepLink = () => { staleDeepLink.value = false }

/**
 * Run-artifact entry points navigate and apply the URL target through one loader, so a
 * history click, result-dialog replay, refresh, and pasted link cannot diverge.
 *
 * A verification trace is addressed by its own run, which the panel knows from the row it
 * was clicked on. `runIdForOpenTrace` resolves it from already-loaded state so opening a
 * trace never needs a separate lookup.
 */
const runIdForOpenTrace = (traceId: number): number | null => {
  const openRunId = verificationResult.value?.historyPersistence?.runId
  if (openRunId && verificationResult.value?.traces?.some(trace => trace.id === traceId)) {
    return openRunId
  }
  for (const run of verificationRuns.value) {
    if (run.counterexamples?.some(trace => trace.id === traceId)) return run.id
  }
  return null
}

const openVerificationRunFromHistory = (runId: number) =>
  openRunTarget({ kind: 'verification', runId })

const openVerificationTraceFromHistory = (traceId: number) => {
  const runId = runIdForOpenTrace(traceId)
  // Without an owning run the trace is not addressable; fall back to the direct load rather
  // than writing a URL that cannot be reopened.
  if (runId === null) return selectAndPlayVerificationTrace(traceId)
  return openRunTarget({ kind: 'verification', runId, traceId })
}

const openSimulationRunFromHistory = (runId: number) =>
  openRunTarget({ kind: 'simulation', runId })

const openFuzzingRunFromHistory = (runId: number) =>
  openRunTarget({ kind: 'exploration', runId })

const openFuzzingFinding = (findingId: number, runId: number) =>
  openRunTarget({ kind: 'exploration', runId, findingId })
const showCanvasEmptyState = computed(() =>
  isBoardDataReady.value
  && nodes.value.length === 0
  && !isSceneReplacementInProgress.value
  && !isModelPlaybackActive.value
  && !isResultSurfaceVisible.value
  && !isWorkflowPanelOpen.value
)
const verificationGenerationWarningCounts = computed(() => getGenerationWarningCounts(verificationResult.value))
const verificationGenerationIssues = computed(() => getGenerationIssues(verificationResult.value))
const verificationCheckLogs = computed(() => verificationResult.value?.checkLogs || [])

/**
 * Whether this run's checked model can actually be downloaded.
 *
 * Two independent conditions, and the control is disabled-with-reason rather than hidden when either
 * fails: a preview-only run is not addressable (no persisted run id), and a run persisted before the
 * model was stored holds none. Gating on the id alone offered a download that 404s; hiding the button
 * instead of disabling it is what made the whole feature look absent.
 */
const verificationRunSmvAvailable = computed(() =>
  verificationResult.value?.hasSmvModel === true
  && typeof verificationResult.value?.historyPersistence?.runId === 'number')

/** The same two conditions for a simulation trajectory; see `verificationRunSmvAvailable`. */
const simulationRunSmvAvailable = computed(() =>
  lastSimulationResult.value?.hasSmvModel === true
  && typeof lastSimulationResult.value?.historyPersistence?.runId === 'number')

// The mapping lives in `board/smvUnavailableReason.ts` (a pure rule, unit-tested there); these only
// bind it to the two result refs.
const verificationSmvUnavailableReason = computed(() =>
  smvUnavailableReasonKey(verificationResult.value?.historyPersistence))

const simulationSmvUnavailableReason = computed(() =>
  smvUnavailableReasonKey(lastSimulationResult.value?.historyPersistence))

/*
 * The two artifact buttons read their id here rather than asserting it non-null in the template.
 *
 * `historyPersistence!.runId!` typechecked only because the button carries `:disabled` bound to the
 * matching availability computed — the assertion's safety lived in a different attribute, so removing
 * or renaming the guard would leave a silent `undefined` in the request path. These read the ref and
 * return, which is checkable on its own.
 */
const downloadCurrentVerificationRunSmv = () => {
  const runId = verificationResult.value?.historyPersistence?.runId
  if (typeof runId !== 'number') return
  void downloadVerificationRunSmv(runId)
}

const downloadCurrentSimulationRunSmv = () => {
  const runId = lastSimulationResult.value?.historyPersistence?.runId
  if (typeof runId !== 'number') return
  void downloadSimulationTraceSmv(runId)
}
const verificationSpecResultSummary = computed(() => {
  const results = (verificationResult.value?.specResults || []).map((result, index) => {
    return {
      ...result,
      ...presentSpecificationResult(result, t),
      displayTitle: t('app.specificationNumber', { number: index + 1 }),
      presentation: result.outcome === 'SATISFIED'
        ? {
            borderClass: 'board-border-subtle',
            badgeClass: 'board-surface-success board-text-success',
            icon: 'check_circle',
            label: t('app.specSatisfied')
          }
        : result.outcome === 'VIOLATED'
          ? {
              borderClass: 'board-border-subtle',
              badgeClass: 'board-surface-danger board-text-danger',
              icon: 'error',
              label: t('app.specViolated')
            }
          : {
              borderClass: 'board-border-subtle',
              badgeClass: 'board-surface-warning board-text-warning',
              icon: 'help',
              label: t('app.specInconclusive')
            }
    }
  })
  const satisfied = results.filter(result => result.outcome === 'SATISFIED').length
  const violated = results.filter(result => result.outcome === 'VIOLATED').length
  const inconclusive = results.filter(result => result.outcome === 'INCONCLUSIVE').length
  return {
    results,
    total: results.length,
    satisfied,
    violated,
    inconclusive
  }
})
/**
 * The headline violation count, taking whichever source claims more.
 *
 * `Math.max` looks like defensive padding and is not. A fresh run cannot make traces exceed violated
 * specifications — `VerificationServiceImpl` builds at most one trace per violated spec, inside the
 * `!passed` branch — so on that path the max always returns `violated` and the second operand is inert.
 * It earns its place on the *history* path: `buildVerificationResultFromRun` takes traces as an argument
 * and reads `specResults` off the stored run row, so a row whose per-specification results are missing or
 * empty yields `violated: 0` beside counterexamples the user can see and replay. Reporting "no
 * violations" there would contradict the evidence on screen.
 *
 * The reverse direction is the shortfall below, which is a real and expected state rather than a
 * data-loss symptom: NuSMV can refute a specification without returning a counterexample this parser can
 * replay.
 */
const verificationViolationCount = computed(() =>
  Math.max(verificationSpecResultSummary.value.violated, verificationResult.value?.traces?.length || 0)
)

/**
 * How many violated specifications produced no replayable counterexample.
 *
 * The two numbers come from independent sources — the backend counts `specResults` with
 * `outcome == VIOLATED`, while a trace exists only where NuSMV returned a *parseable* counterexample —
 * so they can legitimately disagree, and the product already names that state
 * (`someViolationsHaveNoReplayableCounterexample`). Run history rendered it; this dialog did not, and
 * this dialog is where a user lands the instant a run finishes. "Violated: 2" beside one counterexample,
 * or beside none at all, reads either as the tool having lost the evidence or as one violation not being
 * real.
 *
 * Scoped to a VIOLATED verdict on purpose: an INCONCLUSIVE run also has fewer traces than specifications
 * and has its own notice for exactly that, so counting it here would state the same thing twice in
 * different words.
 */
const verificationEvidenceShortfall = computed(() => {
  if (getVerificationOutcome(verificationResult.value) !== 'VIOLATED') return 0
  const violated = verificationSpecResultSummary.value.violated
  const replayable = verificationResult.value?.traces?.length || 0
  return Math.max(0, violated - replayable)
})
const verificationUnsafeDetail = computed(() =>
  verificationViolationCount.value > 0
    ? t('app.foundViolations', { count: verificationViolationCount.value })
    : t('app.verificationResultUnreliable')
)
/*
 * The verdict decision table: one outcome, one tone, one wording.
 *
 * The five class fields this used to return (`dialogToneClass`, `iconBgClass`, `iconTextClass`, `cardClass`,
 * `titleClass`, `detailClass`) were the same tone spelled six ways, repeated per branch — 20 strings encoding
 * four decisions, where a future edit could plausibly change five of six and leave a dialog whose header
 * disagreed with its verdict card. Deriving them from the tone name makes that state unrepresentable.
 */
const verificationVerdictTone = (tone: 'warning' | 'success' | 'danger') => ({
  dialogToneClass: `iot-dialog--${tone}`,
  iconBgClass: `board-chip-${tone}`,
  iconTextClass: `board-text-${tone}`,
  cardClass: `board-surface-${tone}`,
  titleClass: `board-text-${tone}`,
  detailClass: `board-text-${tone}`
})

const verificationResultStatus = computed(() => {
  if (verificationError.value) {
    return {
      ...verificationVerdictTone('danger'),
      icon: 'error',
      title: runFailureTitle('verification', verificationError.value),
      detail: ''
    }
  }
  const outcome = getVerificationOutcome(verificationResult.value)
  if (outcome === 'INCONCLUSIVE') {
    return {
      ...verificationVerdictTone('warning'),
      icon: 'help',
      title: t('app.verificationInconclusive'),
      detail: t('app.verificationInconclusiveDetail')
    }
  }

  if (outcome === 'SATISFIED' && !isVerificationModelComplete(verificationResult.value, outcome)) {
    return {
      ...verificationVerdictTone('warning'),
      icon: 'report',
      title: t('app.verificationPassedWithGenerationWarnings'),
      detail: t('app.emittedSpecsPassedWithGenerationWarnings')
    }
  }

  if (outcome === 'SATISFIED') {
    return {
      ...verificationVerdictTone('success'),
      icon: 'verified',
      title: t('app.checkedSpecificationsSatisfied'),
      detail: t('app.allSpecsPassedVerification')
    }
  }

  return {
    ...verificationVerdictTone('danger'),
    icon: 'warning',
    title: t('app.specificationViolationFound'),
    detail: verificationUnsafeDetail.value
  }
})

const verificationModelSemanticsConsistent = computed(() => isModelSemanticsConsistent(
  verificationResult.value?.modelSemantics,
  {
    isAttack: verificationResult.value?.isAttack,
    attackBudget: verificationResult.value?.attackBudget,
    enablePrivacy: verificationResult.value?.enablePrivacy
  }
))

const verificationBoardComparison = computed<RunBoardComparison>(() =>
  compareRunToCurrentBoard(verificationResult.value, 'verification')
)

const simulationBoardComparison = computed<RunBoardComparison>(() =>
  compareRunToCurrentBoard(lastSimulationResult.value, 'simulation')
)

// A late run may set the conservative flag after comparison already matched, so watch both.
watch([verificationBoardComparison, simulationBoardComparison, verificationResultStale, simulationResultStale], ([verification, simulation]) => {
  if (verification === 'UNCHANGED' || verification === 'CHANGED') {
    verificationResultStale.value = verification === 'CHANGED'
  }
  if (simulation === 'UNCHANGED' || simulation === 'CHANGED') {
    simulationResultStale.value = simulation === 'CHANGED'
  }
})

const traceModelSemanticsConsistent = computed(() => isModelSemanticsConsistent(
  currentTrace.value?.modelSemantics,
  activeTraceContext.value
))

const attackPointDisplay = (semantics: ModelSemantics | null | undefined): string =>
  (semantics?.selectedAttackPoints || [])
    .map(point => point.displayLabel?.trim() || (point.kind === 'DEVICE'
      ? t('app.attackDevicePoint', { id: point.deviceId })
      : t('app.attackAutomationLinkPoint', { id: point.ruleId })))
    .join(', ')

const attackSelectionSummary = (
  semantics: ModelSemantics | null | undefined,
  attackBudget: number | null | undefined,
  kind: 'verification' | 'simulation'
): string => {
  if (semantics?.attackSelectionPolicy === 'EXACT_ATTACK_POINTS') {
    return t('app.attackExactSelectionDetail', {
      count: semantics.selectedAttackPoints?.length ?? 0,
      points: attackPointDisplay(semantics)
    })
  }
  return t(kind === 'simulation' ? 'app.simulationAttackBudgetScope' : 'app.attackExhaustiveSelectionDetail', {
    count: attackBudget ?? 0,
    total: semantics?.modeledAttackPointCount ?? 0
  })
}

</script>

<template>
  <div
    :class="[
      'iot-board',
      {
        'is-narrow-layout': isNarrowBoardLayout,
        'has-narrow-panel-open': showNarrowPanelScrim,
        'has-control-panel-open': isNarrowBoardLayout && !boardPanels.control.collapsed,
        'has-inspector-panel-open': isNarrowBoardLayout && !boardPanels.inspector.collapsed,
      }
    ]"
    data-testid="board-root"
    :aria-busy="!isBoardDataReady || isOpeningAccount"
    :inert="isOpeningAccount"
    :style="boardShellStyle"
    @focusin="handleBoardFocusIn"
  >
    <!-- Navigation Bar - 与首页风格一致 -->
    <nav class="board-nav-bar" :aria-label="t('app.title')" data-toast-anchor>
      <div class="nav-content">
        <h1 class="board-title" :aria-label="t('app.title')">
          <button
            type="button"
            class="logo-left"
            :aria-label="t('app.resetWorkspace')"
            @click="resetWorkspace"
          >
            <ProductMark />
            <span class="logo-wordmark">IoT-Verify</span>
            <span class="logo-short" aria-hidden="true">IoT</span>
          </button>
        </h1>

        <div class="nav-actions">
          <!-- Board edit undo/redo. Availability comes from the server journal, so these are
               disabled until it reports reversible history rather than after any local action. -->
          <HintTooltip :content="t('app.boardUndo')">
            <button
              type="button"
              class="nav-action-btn board-edit-history-btn"
              data-testid="board-undo"
              :aria-label="t('app.boardUndo')"
              :disabled="!canUndoBoardEdit || isApplyingBoardEditUndo"
              @click="applyBoardHistoryFromControl('undo', $event)"
            >
              <span class="material-symbols-outlined" aria-hidden="true">undo</span>
            </button>
          </HintTooltip>
          <HintTooltip :content="t('app.boardRedo')">
            <button
              type="button"
              class="nav-action-btn board-edit-history-btn"
              data-testid="board-redo"
              :aria-label="t('app.boardRedo')"
              :disabled="!canRedoBoardEdit || isApplyingBoardEditUndo"
              @click="applyBoardHistoryFromControl('redo', $event)"
            >
              <span class="material-symbols-outlined" aria-hidden="true">redo</span>
            </button>
          </HintTooltip>
          <ThemeToggle compact />
          <LanguageToggle compact />
          <input
            ref="sceneImportInputRef"
            data-testid="scene-import-file"
            class="hidden"
            type="file"
            accept="application/json,.json"
            :disabled="isSceneReplacementInProgress || !isBoardDataReady"
            @change="handleSceneImportFile"
          />
          <HintTooltip :content="t('app.sceneImport')">
            <button
              type="button"
              class="nav-action-btn scene-action-btn"
              data-testid="scene-import"
              :aria-label="t('app.sceneImport')"
               :disabled="isSceneReplacementInProgress || !isBoardDataReady"
              @click="triggerSceneImport"
            >
              <span class="material-symbols-outlined" aria-hidden="true">upload_file</span>
              <span>{{ t('app.sceneImport') }}</span>
            </button>
          </HintTooltip>
          <HintTooltip :content="t('app.sceneExport')">
            <button
              type="button"
              class="nav-action-btn scene-action-btn"
              data-testid="scene-export"
              :aria-label="t('app.sceneExport')"
               :disabled="isSceneReplacementInProgress || !isBoardDataReady"
              @click="exportScene"
            >
              <span class="material-symbols-outlined" aria-hidden="true">download</span>
              <span>{{ t('app.sceneExport') }}</span>
            </button>
          </HintTooltip>
          <HintTooltip :content="t('app.sceneClear')">
            <button
              type="button"
              class="nav-action-btn scene-action-btn scene-clear-btn"
              data-testid="scene-clear"
              :aria-label="t('app.sceneClear')"
               :disabled="isSceneReplacementInProgress || !isBoardDataReady"
              @click="clearScene"
            >
              <span class="material-symbols-outlined" aria-hidden="true">delete_sweep</span>
              <span>{{ t('app.sceneClear') }}</span>
            </button>
          </HintTooltip>
          <details
            ref="sceneActionsMenuRef"
            class="scene-actions-menu"
            @toggle="handleSceneActionsMenuToggle"
            @keydown.esc.stop.prevent="closeSceneActionsMenu(true)"
          >
            <summary
              class="scene-actions-menu__trigger"
              role="button"
              :aria-label="t('app.sceneActions')"
              :title="t('app.sceneActions')"
              aria-controls="scene-actions-command-group"
              :aria-expanded="sceneActionsMenuOpen"
            >
              <span class="material-symbols-outlined" aria-hidden="true">more_horiz</span>
            </summary>
            <div
              id="scene-actions-command-group"
              class="scene-actions-menu__popover"
              role="group"
              :aria-label="t('app.sceneActions')"
            >
              <button
                type="button"
                 :disabled="isSceneReplacementInProgress || !isBoardDataReady"
                @click="closeSceneActionsMenu(); triggerSceneImport()"
              >
                <span class="material-symbols-outlined" aria-hidden="true">upload_file</span>
                <span>{{ t('app.sceneImport') }}</span>
              </button>
              <button
                type="button"
                 :disabled="isSceneReplacementInProgress || !isBoardDataReady"
                @click="closeSceneActionsMenu(); exportScene()"
              >
                <span class="material-symbols-outlined" aria-hidden="true">download</span>
                <span>{{ t('app.sceneExport') }}</span>
              </button>
              <button
                type="button"
                class="scene-actions-menu__danger"
                 :disabled="isSceneReplacementInProgress || !isBoardDataReady"
                @click="closeSceneActionsMenu(); clearScene()"
              >
                <span class="material-symbols-outlined" aria-hidden="true">delete_sweep</span>
                <span>{{ t('app.sceneClear') }}</span>
              </button>

              <!-- Session chrome is CSS-gated by .nav-overflow-only below 640px so the
                   wordmark and account control cannot clip the last actions. Theme, language,
                   the account entry move into this
                   overflow while Undo/Redo/Scene/AI — the actions used while working — stay on the
                   bar. Every control keeps a 44px target; none is shrunk below the tap minimum. -->
              <div class="scene-actions-menu__section nav-overflow-only" role="group" :aria-label="t('app.navPreferences')">
                <ThemeToggle />
                <LanguageToggle />
                <button type="button" data-testid="nav-overflow-account" @click="closeSceneActionsMenu(true); openAccount()">
                  <span class="material-symbols-outlined" aria-hidden="true">manage_accounts</span>
                  <span>{{ t('account.title') }}</span>
                </button>
              </div>
            </div>
          </details>
          <button
            type="button"
            class="nav-action-btn ai-assistant-btn"
            :class="{
              'has-active-status': chatStore.state.activeCount > 0,
              'has-unread-status': chatStore.state.unreadCount > 0,
              'has-sync-status': chatStore.state.reconciliationRequired
            }"
            data-testid="open-ai-assistant"
            :aria-label="assistantButtonLabel"
            @click="toggleChat"
          >
            <span class="material-symbols-outlined">smart_toy</span>
            <span>{{ t('app.aiAssistant') }}</span>
            <span
              v-if="chatStore.state.reconciliationRequired"
              class="ai-assistant-status is-sync-pending"
              data-testid="ai-assistant-sync-pending"
              aria-hidden="true"
            >!</span>
            <span
              v-if="chatStore.state.activeCount > 0"
              class="ai-assistant-status is-running"
              data-testid="ai-assistant-running"
              aria-hidden="true"
            >{{ chatStore.state.activeCount > 9 ? '9+' : chatStore.state.activeCount }}</span>
            <span
              v-if="chatStore.state.unreadCount > 0"
              class="ai-assistant-status is-unread"
              data-testid="ai-assistant-unread"
              aria-hidden="true"
            >{{ chatStore.state.unreadCount > 9 ? '9+' : chatStore.state.unreadCount }}</span>
          </button>
          <HintTooltip :content="t('account.title')">
            <button type="button" class="nav-action-btn nav-account-btn" data-testid="open-account-center"
              :aria-label="t('account.title')" :disabled="isOpeningAccount" @click="openAccount">
              <span class="material-symbols-outlined" aria-hidden="true">manage_accounts</span>
            </button>
          </HintTooltip>
        </div>
      </div>
    </nav>

    <div
      v-if="!isBoardDataReady || boardLayoutLoadError || staleDeepLink"
      class="pointer-events-none fixed inset-x-0 top-[var(--board-nav-height)] z-[var(--z-board-alert)] flex flex-col items-center"
      data-testid="board-status-stack"
      data-toast-anchor
    >
      <div
        v-if="!isBoardDataReady && failedBoardDataKeys.length === 0"
        class="board-surface-info board-text-info flex h-9 w-full items-center justify-center gap-2 border-x-0 border-t-0 text-xs font-semibold"
        role="status"
        aria-live="polite"
        data-testid="board-data-loading"
      >
        <span class="material-symbols-outlined board-text-progress animate-spin text-base" aria-hidden="true">progress_activity</span>
        {{ t('app.boardSnapshotLoading') }}
      </div>

      <div
        v-if="failedBoardDataKeys.length > 0"
        class="mt-[var(--board-floating-gap)] flex w-[min(92vw,720px)] items-center gap-3 rounded-md board-surface-danger px-4 py-3 text-sm board-text-danger shadow-lg"
        role="alert"
        data-testid="board-data-load-error"
      >
        <span class="material-symbols-outlined shrink-0" aria-hidden="true">sync_problem</span>
        <div class="min-w-0 flex-1 break-words">
          <p>{{ t('app.boardDataLoadFailedWithCollections', {
            collections: failedBoardDataKeys.map(boardDataKeyLabel).join(', ')
          }) }}</p>
          <FailureDetails v-if="boardSnapshotError" v-bind="boardSnapshotError" suggestion="" />
        </div>
        <button
          type="button"
          class="pointer-events-auto inline-flex shrink-0 items-center gap-1 rounded-md px-2.5 py-1.5 font-semibold hover:board-chip-danger dark:hover:bg-[color:var(--danger-surface)]"
          @click="retryBoardDataLoad"
        >
          <span class="material-symbols-outlined text-base" aria-hidden="true">refresh</span>
          {{ t('app.retry') }}
        </button>
      </div>

      <div
        v-if="boardLayoutLoadError"
        class="mt-[var(--board-floating-gap)] flex w-[min(92vw,720px)] items-center gap-3 rounded-md board-surface-warning px-4 py-3 text-sm board-text-warning shadow-lg"
        role="alert"
        data-testid="board-layout-load-error"
      >
        <span class="material-symbols-outlined shrink-0" aria-hidden="true">sync_problem</span>
        <div class="min-w-0 flex-1 break-words">
          <p>{{ t('app.loadLayoutFailed') }}</p>
          <FailureDetails v-bind="boardLayoutLoadError" suggestion="" class="pointer-events-auto" />
        </div>
        <button
          type="button"
          class="pointer-events-auto inline-flex shrink-0 items-center gap-1 rounded-md px-2.5 py-1.5 font-semibold hover:board-chip-warning"
          :disabled="loadingBoardLayout"
          :aria-busy="loadingBoardLayout"
          @click="loadBoardLayout(true)"
        >
          <span class="material-symbols-outlined text-base" aria-hidden="true">refresh</span>
          {{ t('app.retry') }}
        </button>
      </div>

      <!-- A shared link naming a run that is gone or not ours: persistent and dismissible,
           because a toast disappears before the user can read why the board looks empty. -->
      <div
        v-if="staleDeepLink"
        class="mt-[var(--board-floating-gap)] flex w-[min(92vw,720px)] items-center gap-3 rounded-md board-surface-warning px-4 py-3 text-sm board-text-warning shadow-lg"
        role="alert"
        data-testid="board-deep-link-unavailable"
      >
        <span class="material-symbols-outlined shrink-0" aria-hidden="true">link_off</span>
        <span class="min-w-0 flex-1 break-words">{{ t('app.deepLinkUnavailable') }}</span>
        <HintTooltip :content="t('app.deepLinkUnavailableDismiss')">
          <button
            type="button"
            class="pointer-events-auto inline-flex shrink-0 items-center justify-center rounded-md p-1.5 hover:board-chip-warning dark:hover:bg-[color:var(--warning-surface)]"
            :aria-label="t('app.deepLinkUnavailableDismiss')"
            data-testid="dismiss-deep-link-unavailable"
            @click="dismissStaleDeepLink"
          >
            <span class="material-symbols-outlined text-base" aria-hidden="true">close</span>
          </button>
        </HintTooltip>
      </div>
    </div>

    <div
      v-if="templateInstanceDialogVisible"
      class="iot-dialog-overlay"
      @click="cancelTemplateInstanceCreate"
      @keydown="handleTemplateInstanceDialogKeydown"
    >
      <div
        :ref="setTemplateInstanceDialogRef"
        class="iot-dialog iot-dialog--md"
        data-testid="template-instance-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="template-instance-title"
        tabindex="-1"
        @click.stop
      >
        <div class="iot-dialog__header">
          <div class="iot-dialog__icon">
            <span class="material-symbols-outlined" aria-hidden="true">add_location_alt</span>
          </div>
          <div class="iot-dialog__heading">
            <h3 id="template-instance-title" class="iot-dialog__title">
              {{ t('app.createDeviceFromTemplate') }}
            </h3>
            <p class="iot-dialog__subtitle">
              {{ t('app.createDeviceFromTemplateHint', { template: templateInstanceDialogData.template?.manifest.Name || t('app.unknown') }) }}
            </p>
          </div>
        </div>

        <div class="iot-dialog__body iot-scroll-region">
        <label class="board-form-label block">
          {{ t('app.deviceName') }}
        </label>
        <input
          v-model="templateInstanceDialogData.name"
          data-testid="template-instance-name"
          class="board-field mt-1"
          :placeholder="t('app.deviceNamePlaceholder')"
          :disabled="templateInstanceSaving"
          @keydown.enter.prevent="confirmTemplateInstanceCreate"
        />

        <div
          v-if="templateInstanceEnvironmentAdditions.length > 0"
          data-testid="template-instance-environment-preview"
          class="mt-3 flex items-start gap-2 rounded-lg board-surface-info px-3 py-2 text-xs leading-relaxed board-text-info"
        >
          <span class="material-symbols-outlined mt-0.5 text-sm" aria-hidden="true">water_drop</span>
          <span>{{ t('app.deviceCreationEnvironmentAdditionsPreview', { names: templateInstanceEnvironmentAdditions.join(', ') }) }}</span>
        </div>

        <details
          v-if="templateInstanceHasRuntimeFields"
          data-testid="template-instance-runtime"
          class="mt-4 rounded-xl board-surface-warning p-3 shadow-sm dark:bg-[color:var(--warning)]/10"
        >
          <summary
            data-testid="template-instance-runtime-toggle"
            class="board-form-label flex cursor-pointer select-none items-center justify-between gap-2"
          >
            <span class="inline-flex min-w-0 items-center gap-1.5">
              <span class="material-symbols-outlined text-sm board-text-warning" aria-hidden="true">tune</span>
              {{ t('app.advancedInitialValuesOverrides') }}
            </span>
            <span class="material-symbols-outlined text-sm board-text-muted" aria-hidden="true">expand_more</span>
          </summary>

          <p class="mt-2 text-xs leading-relaxed board-text-muted">
            {{ t('app.initialValuesHint') }}
          </p>

          <div v-if="templateInstanceHasModes" class="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-3">
            <label class="min-w-0">
              <span class="mb-1 board-form-label block">{{ t('app.initialState') }}</span>
              <FieldSelect
                v-model="templateInstanceRuntime.state"
                data-testid="template-instance-state"
              >
                <option v-for="state in templateInstanceWorkingStates" :key="state.Name" :value="state.Name">{{ formatTemplateModelToken(templateInstanceDialogData.template, state.Name) }}</option>
              </FieldSelect>
            </label>

            <label class="min-w-0">
              <span class="mb-1 board-form-label block">{{ t('app.stateTrust') }}</span>
              <FieldSelect
                v-model="templateInstanceRuntime.currentStateTrust"
                data-testid="template-instance-state-trust"
              >
                <option value="">{{ t('app.useTemplateDefaultWithValue', { value: t(`app.${findTemplateStateTrust(templateInstanceDialogData.template, templateInstanceRuntime.state) || 'trusted'}`) }) }}</option>
                <option v-for="trust in TRUST_OPTIONS" :key="trust" :value="trust">{{ t(`app.${trust}`) }}</option>
              </FieldSelect>
            </label>

            <label class="min-w-0">
              <span class="mb-1 board-form-label block">{{ t('app.statePrivacy') }}</span>
              <FieldSelect
                v-model="templateInstanceRuntime.currentStatePrivacy"
                data-testid="template-instance-state-privacy"
              >
                <option value="">{{ t('app.useTemplateDefaultWithValue', { value: t(`app.${findTemplateStatePrivacy(templateInstanceDialogData.template, templateInstanceRuntime.state) || 'public'}`) }) }}</option>
                <option v-for="privacy in PRIVACY_OPTIONS" :key="privacy" :value="privacy">{{ t(`app.${privacy}`) }}</option>
              </FieldSelect>
            </label>
          </div>

          <div v-if="templateInstanceInternalVariables.length > 0" class="mt-3 space-y-2">
            <div
              v-for="variable in templateInstanceInternalVariables"
              :key="variable.Name"
              class="rounded-lg border board-border-subtle board-bg-elevated p-2"
            >
              <div class="mb-2 flex flex-wrap items-start justify-between gap-x-3 gap-y-1">
                <span class="max-w-full break-words text-sm font-semibold board-text-strong" :title="formatTemplateModelToken(templateInstanceDialogData.template, variable.Name)">{{ formatTemplateModelToken(templateInstanceDialogData.template, variable.Name) }}</span>
                <span v-if="templateVariableUsesNumericBounds(variable)" class="max-w-full break-words text-[length:var(--iot-font-min)] font-semibold board-text-muted">
                  {{ templateVariableInputPlaceholder(variable) }}
                </span>
              </div>

              <div class="grid grid-cols-[minmax(0,1fr)_5.8rem_5.8rem] gap-2 max-[520px]:grid-cols-1">
                <label class="min-w-0">
                  <span class="mb-1 board-form-label block">{{ t('app.variableValue') }}</span>
                  <!-- The state's consequence, not an instance choice: see
                       `templateVariableIsStateDerived`. -->
                  <div
                    v-if="templateVariableIsStateDerived(templateInstanceDialogData.template, variable.Name)"
                    :data-testid="`template-instance-variable-derived-${variable.Name}`"
                    class="flex min-w-0 items-center gap-2 rounded-lg border border-dashed board-border-subtle board-bg-muted px-2 py-1.5"
                  >
                    <span class="min-w-0 break-words text-xs font-medium board-text-strong">
                      {{ formatTemplateModelToken(templateInstanceDialogData.template, templateInstanceRuntime.variables[variable.Name]) }}
                    </span>
                    <span class="shrink-0 text-[length:var(--iot-font-min)] board-text-muted">{{ t('app.variableFollowsState') }}</span>
                  </div>
                  <FieldSelect
                    v-else-if="templateVariableHasEnumValues(variable)"
                    v-model="templateInstanceRuntime.variables[variable.Name]"
                    :data-testid="`template-instance-variable-${variable.Name}`"
                  >
                    <option value="">{{ t('app.useTemplateDefaultWithValue', { value: formatTemplateModelToken(templateInstanceDialogData.template, getTemplateVariableDefaultValue(variable, templateInstanceDialogData.template, templateInstanceRuntime.state)) }) }}</option>
                    <option v-for="value in variable.Values" :key="value" :value="String(value)">{{ formatTemplateModelToken(templateInstanceDialogData.template, value) }}</option>
                  </FieldSelect>
                  <input
                    v-else
                    v-model="templateInstanceRuntime.variables[variable.Name]"
                    :data-testid="`template-instance-variable-${variable.Name}`"
                    class="board-field"
                    :placeholder="templateVariableInputPlaceholder(variable)"
                    type="text"
                  />
                </label>

                <label class="min-w-0">
                  <span class="mb-1 board-form-label block">{{ t('app.variableTrust') }}</span>
                  <FieldSelect
                    v-model="templateInstanceRuntime.variableTrusts[variable.Name]"
                    :data-testid="`template-instance-variable-trust-${variable.Name}`"
                  >
                    <option value="">{{ t('app.useTemplateDefaultWithValue', { value: t(`app.${variable.Trust || 'trusted'}`) }) }}</option>
                    <option v-for="trust in TRUST_OPTIONS" :key="trust" :value="trust">{{ t(`app.${trust}`) }}</option>
                  </FieldSelect>
                </label>

                <label class="min-w-0">
                  <span class="mb-1 board-form-label block">{{ t('app.privacy') }}</span>
                  <FieldSelect
                    v-model="templateInstanceRuntime.privacies[variable.Name]"
                    :data-testid="`template-instance-privacy-${variable.Name}`"
                  >
                    <option value="">{{ t('app.useTemplateDefaultWithValue', { value: t(`app.${variable.Privacy || 'public'}`) }) }}</option>
                    <option v-for="privacy in PRIVACY_OPTIONS" :key="privacy" :value="privacy">{{ t(`app.${privacy}`) }}</option>
                  </FieldSelect>
                </label>
              </div>
            </div>
          </div>
        </details>

        </div>

        <div class="iot-dialog__footer">
          <button
            type="button"
            class="iot-dialog-btn iot-dialog-btn--ghost"
            :disabled="templateInstanceSaving"
            @click="cancelTemplateInstanceCreate"
          >
            {{ t('app.cancel') }}
          </button>
          <button
            type="button"
            data-testid="template-instance-confirm"
            class="iot-dialog-btn iot-dialog-btn--primary"
            :disabled="templateInstanceSaving"
            @click="confirmTemplateInstanceCreate"
          >
            <span v-if="templateInstanceSaving" class="iot-dialog-btn__spinner" aria-hidden="true"></span>
            <span v-else class="material-symbols-outlined" aria-hidden="true">add</span>
            {{ t('app.createDevice') }}
          </button>
        </div>
      </div>
    </div>

    <!-- Left Sidebar - Control Center -->
    <ControlCenter
      :device-templates="deviceTemplates"
      :templates-loading="templatesLoading"
      :nodes="nodes"
      :collapsed="boardPanels.control.collapsed"
      :width="effectiveControlPanelWidth"
      :active-section="boardPanels.control.activeSection"
      :read-only="isModelPlaybackActive || isSceneReplacementInProgress"
      :read-only-message="isSceneReplacementInProgress ? t('app.sceneReplacementInProgress') : t('app.playbackReadOnlyCloseFirst')"
      :run-board-mutation="enqueueBoardMutation"
      :class="{ 'board-panel--interaction-read-only': isModelPlaybackActive || isSceneReplacementInProgress }"
      @create-device="handleCreateDevice"
      @create-devices="handleCreateDevices"
      @template-drag-start="handleTemplateDragStart"
      @template-drag-end="handleTemplateDragEnd"
      @save-rule="handleAddRule"
      @add-spec="handleAddSpec"
      @replace-template-catalog="replaceTemplateCatalog"
      @replace-template-state="replaceTemplateState"
      @edit-history-cleared="notifyUndoJournalCleared"
      @authoritative-state-unavailable="handleAuthoritativeBoardStateUnavailable"
      @update:collapsed="handleControlCollapsedUpdate"
      @update:active-section="handleControlActiveSectionUpdate"
    />

    <!-- Right Sidebar - System Inspector -->
    <SystemInspector
      :devices="nodes"
      :device-templates="deviceTemplates"
      :environment-variables="environmentVariables"
      :rules="rules"
      :specifications="specifications"
      :focused-device-id="focusedNodeId"
      :focused-rule-id="focusedRuleId"
      :focused-spec-id="focusedSpecId"
      :collapsed="boardPanels.inspector.collapsed"
      :width="effectiveInspectorPanelWidth"
      :active-section="boardPanels.inspector.activeSection"
      :data-unavailable="failedBoardDataKeys.length > 0"
      :data-loading="!isBoardDataReady"
      :read-only="isModelPlaybackActive || isSceneReplacementInProgress"
      :read-only-message="isSceneReplacementInProgress ? t('app.sceneReplacementInProgress') : t('app.playbackReadOnlyCloseFirst')"
      :rules-reordering="rulesReordering"
      :class="{ 'board-panel--interaction-read-only': isModelPlaybackActive || isSceneReplacementInProgress }"
      @delete-device="deleteNodeFromStatus"
      @delete-rule="deleteRule"
      @move-rule="moveRule"
      @delete-spec="deleteSpecification"
      @device-click="focusDeviceFromInspector"
      @open-rule-builder="openRuleBuilder"
      @open-control-section="openControlSection"
      @save-environment="saveEnvironmentVariables"
      :environment-saving="environmentMutationPending"
      @update:collapsed="handleInspectorCollapsedUpdate"
      @update:active-section="handleInspectorActiveSectionUpdate"
    >
      <template #overview>
        <!-- Keep navigation available while reviewing a result. -->
        <details
          data-testid="canvas-map"
          class="canvas-map"
          :open="boardViewportHeight >= 600"
        >
          <summary class="canvas-map__header" data-testid="canvas-map-toggle" :aria-label="t('app.canvasMap')">
            <span class="canvas-map__title">{{ t('app.canvasMapShort') }}</span>
            <span class="material-symbols-outlined canvas-map__chevron" aria-hidden="true">expand_more</span>
          </summary>
          <div class="canvas-map__zoom-controls" role="group" :aria-label="t('app.zoomLevel')" data-testid="canvas-map-zoom-controls">
            <HintTooltip :content="t('app.zoomOut')">
              <button
                type="button"
                class="board-panel-icon-action canvas-map__tool"
                data-testid="canvas-map-zoom-out"
                :aria-label="t('app.zoomOut')"
                :disabled="isCanvasNavigationLocked"
                @click="adjustCanvasZoom(-ZOOM_STEP)"
              >
                <span class="material-symbols-outlined text-sm" aria-hidden="true">remove</span>
              </button>
            </HintTooltip>
            <label class="canvas-map__zoom-input-wrap" :title="t('app.zoomLevel')">
              <input
                class="canvas-map__zoom-input"
                data-testid="canvas-map-zoom-input"
                type="number"
                :min="Number((minimumCanvasZoomForInput * 100).toFixed(2))"
                :max="Math.round(MAX_ZOOM * 100)"
                step="any"
                :value="canvasZoomPercent"
                :aria-label="t('app.zoomLevel')"
                :disabled="isCanvasNavigationLocked"
                @input="handleCanvasMapZoomInput"
                @change="handleCanvasMapZoomInput"
                @keydown.stop
              />
              <span aria-hidden="true">%</span>
            </label>
            <HintTooltip :content="t('app.zoomIn')">
              <button
                type="button"
                class="board-panel-icon-action canvas-map__tool"
                data-testid="canvas-map-zoom-in"
                :aria-label="t('app.zoomIn')"
                :disabled="isCanvasNavigationLocked"
                @click="adjustCanvasZoom(ZOOM_STEP)"
              >
                <span class="material-symbols-outlined text-sm" aria-hidden="true">add</span>
              </button>
            </HintTooltip>
            <HintTooltip :content="t('app.fitToContent')">
              <button
                type="button"
                class="board-panel-icon-action canvas-map__tool"
                data-testid="canvas-map-fit"
                :aria-label="t('app.fitToContent')"
                :disabled="isCanvasNavigationLocked"
                @click="fitToContent"
              >
                <span class="material-symbols-outlined text-sm" aria-hidden="true">fit_screen</span>
              </button>
            </HintTooltip>
          </div>

          <div
            class="canvas-map__viewport relative overflow-hidden cursor-crosshair select-none"
            data-testid="canvas-map-viewport"
            @pointerdown="onCanvasMapPointerDown"
          >
            <svg
              class="absolute inset-0 w-full h-full"
              :viewBox="canvasMapViewBox"
              preserveAspectRatio="none"
            >
              <line
                v-for="line in canvasMapLines"
                :key="line.id"
                :x1="line.x1"
                :y1="line.y1"
                :x2="line.x2"
                :y2="line.y2"
                :stroke="line.color"
                stroke-width="2"
                stroke-opacity="0.8"
                stroke-linecap="round"
              />
              <circle
                v-for="dot in canvasMapDots"
                :key="dot.id"
                :cx="dot.x + 4"
                :cy="dot.y + 4"
                r="3.6"
                :fill="dot.color"
                stroke="var(--surface-elevated)"
                stroke-width="1"
              />
              <rect
                v-if="canvasMapViewportRect"
                data-testid="canvas-map-viewport-rect"
                class="canvas-map__viewport-rect"
                :x="canvasMapViewportRect.x"
                :y="canvasMapViewportRect.y"
                :width="canvasMapViewportRect.width"
                :height="canvasMapViewportRect.height"
                rx="2"
              />
            </svg>

            <!-- An empty map cannot establish an empty board until its snapshot is available. -->
            <div v-if="canvasMapDots.length === 0" class="canvas-map__empty absolute inset-0 flex items-center justify-center">
              {{ failedBoardDataKeys.length > 0
                ? t('app.boardDataUnavailableShort')
                : t(isBoardDataReady ? 'app.noDevicesOnCanvas' : 'app.boardSnapshotLoading') }}
            </div>
          </div>
        </details>
      </template>
    </SystemInspector>

    <button
      v-if="showNarrowPanelScrim"
      ref="boardPanelScrimRef"
      type="button"
      class="board-panel-scrim"
      data-testid="board-panel-scrim"
      :aria-label="t('app.close')"
      @click="closeNarrowSidePanels"
    ></button>

    <div
      class="contents"
      data-testid="board-narrow-background"
      :inert="showNarrowPanelScrim ? true : undefined"
      :aria-hidden="showNarrowPanelScrim ? 'true' : undefined"
    >
    <HintTooltip :content="t('app.fitToContent')">
      <button
        type="button"
        class="canvas-fit-mobile"
        data-testid="canvas-fit-mobile"
        :aria-label="t('app.fitToContent')"
        :disabled="isCanvasNavigationLocked"
        @click="fitToContent"
      >
        <span class="material-symbols-outlined" aria-hidden="true">fit_screen</span>
      </button>
    </HintTooltip>

    <!-- Canvas Area -->
    <div
      v-if="isModelPlaybackActive"
      class="board-playback-bar"
      :class="{ 'is-stacked': playbackToolbarStacked, 'has-hidden-panel': !playbackPanelOpen }"
      data-testid="playback-toolbar"
      :ref="setPlaybackToolbarRef"
      tabindex="-1"
      autofocus
      @keydown="handlePlaybackKeydown"
      role="toolbar"
      :aria-label="t('app.playbackControls')"
    >
      <span class="board-playback-bar__title">{{ activePlaybackKind === 'simulation'
        ? t('app.traceVisualization.modelTracePlayback')
        : activePlaybackKind === 'fuzzing'
          ? t('app.fuzzFindingReplay')
          : t('app.traceVisualization.counterexampleTracePlayback') }}</span>
      <div id="board-playback-transport" class="board-playback-bar__transport" />
      <div id="board-playback-timeline" class="board-playback-bar__timeline" />
      <div class="board-playback-bar__actions">
        <div id="board-playback-locate" class="board-playback-bar__locate" />
        <HintTooltip v-if="!playbackPanelOpen" :content="t('app.showPlaybackPanel')">
          <button
            type="button"
            class="board-playback-bar__toggle"
            data-testid="playback-panel-toggle"
            :aria-expanded="playbackPanelOpen"
            :aria-controls="traceAnimationState.visible ? 'trace-playback-panel' : 'simulation-playback-panel'"
            :aria-label="t('app.showPlaybackPanel')"
            @click="togglePlaybackPanel"
          >
            <span class="material-symbols-outlined" aria-hidden="true">bottom_panel_open</span>
          </button>
        </HintTooltip>
        <HintTooltip :content="t('app.exitPlayback')">
          <button
            type="button"
            class="board-playback-bar__exit"
            :data-testid="traceAnimationState.visible ? 'trace-timeline-close' : 'simulation-timeline-close'"
            :aria-label="t('app.exitPlayback')"
            @click="traceAnimationState.visible ? closeTraceAnimation() : closeSimulationTimeline()"
          >
            <span class="material-symbols-outlined" aria-hidden="true">stop_circle</span>
            <span>{{ t('app.exitPlayback') }}</span>
          </button>
        </HintTooltip>
      </div>
    </div>
    <div ref="canvasContainer" class="canvas-container" @wheel.ctrl.prevent="onBoardWheel">
      <!-- Canvas Board -->
      <CanvasBoard
          :nodes="renderedCanvasNodes"
          :edges="allEdges"
          :rules="activePlaybackScene ? activePlaybackScene.rules : rules"
          :get-visible-frame="getCanvasFrameForRendering"
          :device-templates="deviceTemplates"
          :pan="renderedCanvasPan"
          :zoom="renderedCanvasZoom"
          :get-node-icon="getBoardNodeIcon"
          :has-node-state-machine="hasNodeStateMachine"
          :get-node-effective-state="getNodeEffectiveState"
          :format-node-model-token="formatNodeModelToken"
          :format-playback-model-token="formatPlaybackModelToken"
          :highlighted-trace="canvasHighlightedTrace"
          :focused-node-id="focusedNodeId"
          :focused-rule-id="focusedRuleId"
          :interaction-locked="isCanvasInteractionLocked || isApplyingBoardEditUndo"
          @canvas-pointerdown="onCanvasPointerDown"
          @canvas-dragover="onCanvasDragOver"
          @canvas-drop="onCanvasDrop"
          @canvas-enter="onCanvasEnter"
          @canvas-leave="onCanvasLeave"
          @node-context="onNodeContext"
          @node-open="openNodeFromCanvas"
          @node-delete="deleteNodeFromStatus"
          @node-layout-interaction-start="handleNodeLayoutInteractionStart"
          @node-layout-interaction-end="handleNodeLayoutInteractionEnd"
          @node-moved-or-resized="handleNodeMovedOrResized"
      />

      <section
        v-if="showCanvasEmptyState"
        class="canvas-empty-state iot-scroll-region pointer-events-none absolute inset-0 z-10 flex items-center justify-center px-4 sm:px-8"
        data-testid="canvas-empty-state"
        aria-labelledby="canvas-empty-state-title"
      >
        <div
          class="canvas-empty-state__card"
          :class="draggingTplName ? 'pointer-events-none' : 'pointer-events-auto'"
        >
          <span class="canvas-empty-state__icon" aria-hidden="true">
            <span class="material-symbols-outlined">account_tree</span>
          </span>
          <h2 id="canvas-empty-state-title" class="canvas-empty-state__title">
            {{ t('app.emptyCanvasTitle') }}
          </h2>
          <div class="mt-5 flex flex-wrap items-center justify-center gap-2">
            <button
              type="button"
              class="board-action-inline"
              data-testid="empty-state-add-device"
              @click="openControlSection('devices')"
            >
              <span class="material-symbols-outlined text-lg" aria-hidden="true">add</span>
                {{ t('app.createDevice') }}
            </button>
            <button
              type="button"
              class="board-action-inline-subtle"
              data-testid="empty-state-generate-scenario"
              @click="openScenarioRecommendationPanel"
            >
              <span class="material-symbols-outlined text-lg" aria-hidden="true">auto_awesome</span>
              {{ t('app.openScenarioRecommendations') }}
            </button>
            <button
              type="button"
              class="board-action-inline-subtle"
              data-testid="empty-state-import-scene"
              @click="triggerSceneImport"
            >
              <span class="material-symbols-outlined text-lg" aria-hidden="true">upload_file</span>
              {{ t('app.emptyCanvasImportScene') }}
            </button>
          </div>
        </div>
      </section>



      <div
        v-if="draggingTplName"
        class="canvas-drop-overlay pointer-events-none absolute inset-4 z-20 flex items-center justify-center"
        data-testid="template-drop-overlay"
      >
        <span class="canvas-drop-overlay__label">
          {{ t('app.releaseTemplateToCreateDevice') }}
        </span>
      </div>

    </div>

    <!-- Responsive Action Dock - anchored left of System Inspector -->
    <div
      v-show="!isResultSurfaceVisible"
      class="board-floating-actions board-action-dock"
      :class="[
        `board-action-dock--${actionDockMode}`,
        {
          'has-activity': hasActionDockActivity
        }
      ]"
      :style="actionDockStyle"
      role="toolbar"
      :aria-hidden="isResultSurfaceVisible"
      :aria-label="t('app.boardTools')"
    >
      <button
        v-if="isActionDockPackedMode"
        type="button"
        class="board-action-dock__launcher"
        data-testid="restore-action-dock"
        :aria-label="actionDockRestoreLabel"
        @click="restoreActionDockFromPacked"
      >
        <span class="material-symbols-outlined" aria-hidden="true">toolbar</span>
        <span v-if="hasActionDockActivity" class="board-action-dock__activity-dot" aria-hidden="true"></span>
      </button>

      <div
        v-if="!isActionDockPackedMode"
        class="board-action-dock__panel"
      >
        <div
          class="board-action-dock__header"
        >
          <span class="board-action-dock__title">{{ t('app.boardTools') }}</span>
          <button
            type="button"
            class="board-action-dock__toggle"
            data-testid="toggle-action-dock"
            :aria-label="actionDockToggleLabel"
            :aria-expanded="actionDockMode === 'expanded'"
            @click="cycleActionDockMode"
          >
            <span class="material-symbols-outlined" aria-hidden="true">
              {{ actionDockToggleIcon }}
            </span>
          </button>
        </div>

        <div class="board-tool-group" data-testid="run-tool-group" role="group" :aria-label="t('app.runTools')">
          <span class="board-tool-group-label">{{ t('app.runTools') }}</span>

        <div class="board-tool-wrapper group">
          <div
            v-if="simulationAnimationState.visible"
            class="board-tool-status"
          ></div>
          <HintTooltip :content="simulationTooltipContent">
            <button
              type="button"
              @click="openSimulationFromActionDock"
              data-testid="open-simulation-panel"
              :disabled="traceAnimationState.visible || simulationAnimationState.visible || isAnimationLocked || isAnyRecommendationRunning()"
              :aria-label="isSimulating ? t('app.simulationRunning') : t('app.openSimulationSettings')"
              :aria-pressed="showSimulationPanel || simulationAnimationState.visible"
              class="board-tool-button transition-colors"
            >
              <span v-if="isSimulating" class="material-symbols-outlined animate-spin" aria-hidden="true">sync</span>
              <span v-else class="material-symbols-outlined" aria-hidden="true">play_circle</span>
              <span class="board-tool-label">{{ t('app.simulationTitle') }}</span>
            </button>
          </HintTooltip>
        </div>

        <div class="board-tool-wrapper group">
          <div v-if="isFuzzing" class="board-tool-status"></div>
          <HintTooltip :content="fuzzingTooltipContent">
            <button
              type="button"
              @click="openFuzzingFromActionDock"
              data-testid="open-fuzzing-panel"
              :disabled="isSceneReplacementInProgress || traceAnimationState.visible || simulationAnimationState.visible || isAnimationLocked || isAnyRecommendationRunning()"
              :aria-label="isSceneReplacementInProgress
                ? t('app.sceneReplacementInProgress')
                : isFuzzing ? t('app.fuzzRunning') : t('app.openFuzzSettings')"
              :aria-pressed="showFuzzingPanel"
              class="board-tool-button transition-colors"
            >
              <span v-if="isFuzzing" class="material-symbols-outlined animate-spin" aria-hidden="true">sync</span>
              <span v-else class="material-symbols-outlined" aria-hidden="true">radar</span>
              <!-- Short form: the rail truncated "Counterexample Search" to "Counterex...". The full
                   name remains in this button's aria-label and tooltip. -->
              <span class="board-tool-label">{{ t('app.fuzzSearchShort') }}</span>
            </button>
          </HintTooltip>
        </div>

        <div class="board-tool-wrapper group">
          <div
            v-if="traceAnimationState.visible"
            class="board-tool-status"
          ></div>
          <HintTooltip :content="verificationTooltipContent">
            <button
              ref="verificationActionButtonRef"
              type="button"
              @click="openVerificationFromActionDock"
              data-testid="open-verification-panel"
              :disabled="traceAnimationState.visible || simulationAnimationState.visible || isAnimationLocked || isAnyRecommendationRunning()"
              :aria-label="isVerifying ? t('app.verifying') : t('app.openVerificationSettings')"
              :aria-pressed="showVerificationPanel || traceAnimationState.visible"
              class="board-tool-button transition-colors"
            >
              <span v-if="isVerifying" class="material-symbols-outlined animate-spin" aria-hidden="true">sync</span>
              <span v-else class="material-symbols-outlined" aria-hidden="true">fact_check</span>
              <span class="board-tool-label">{{ t('app.verification') }}</span>
            </button>
          </HintTooltip>
        </div>

      </div>

      <div class="board-tool-separator" aria-hidden="true"></div>

      <div class="board-tool-group" data-testid="review-tool-group" role="group" :aria-label="t('app.reviewTools')">
        <span class="board-tool-group-label">{{ t('app.reviewTools') }}</span>

        <div class="board-tool-wrapper group">
          <HintTooltip :content="isModelPlaybackActive ? t('app.playbackReadOnlyCloseFirst') : t('app.openRunHistory')">
            <button
              type="button"
              @click="openHistoryFromActionDock"
              data-testid="open-history-panel"
              :disabled="isModelPlaybackActive || isAnyRecommendationRunning()"
              :aria-label="isModelPlaybackActive
                ? t('app.playbackReadOnlyCloseFirst')
                : unreadFuzzNotificationCount > 0
                  ? t('app.fuzzUnreadUpdates', { count: unreadFuzzNotificationCount })
                  : t('app.openRunHistory')"
              :aria-pressed="showHistoryPanel"
              class="board-tool-button transition-colors"
            >
              <span class="material-symbols-outlined" aria-hidden="true">history</span>
              <span class="board-tool-label">{{ t('app.runHistory') }}</span>
              <span
                v-if="unreadFuzzNotificationCount > 0"
                class="absolute right-0.5 top-0.5 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-[color:var(--danger-fill)] px-1 text-[length:var(--iot-font-min)] font-semibold text-white"
                data-testid="fuzz-unread-badge"
                aria-hidden="true"
              >{{ unreadFuzzNotificationCount > 99 ? '99+' : unreadFuzzNotificationCount }}</span>
            </button>
          </HintTooltip>
        </div>
      </div>

      <div class="board-tool-separator" aria-hidden="true"></div>

      <div class="board-tool-group" data-testid="ai-tool-group" role="group" :aria-label="t('app.aiTools')">
        <span class="board-tool-group-label">{{ t('app.aiTools') }}</span>

        <div class="board-tool-wrapper group">
          <div
            v-if="isRecommendingScenario"
            class="board-tool-status"
          ></div>
          <HintTooltip :content="scenarioTooltipContent">
            <button
              type="button"
              @click="openScenarioRecommendationsFromActionDock"
              data-testid="open-scenario-recommendations"
              :disabled="isSceneReplacementInProgress || traceAnimationState.visible || simulationAnimationState.visible || isAnimationLocked || isRecommendationRunningForAnother('scenario')"
              :aria-label="t('app.openScenarioRecommendations')"
              :aria-pressed="showScenarioRecommendationPanel || isRecommendingScenario"
              class="board-tool-button transition-colors"
            >
              <span v-if="isRecommendingScenario" class="material-symbols-outlined animate-spin" aria-hidden="true">sync</span>
              <span v-else class="material-symbols-outlined" aria-hidden="true">account_tree</span>
              <span class="board-tool-label">{{ t('app.scenarioTool') }}</span>
            </button>
          </HintTooltip>
        </div>

        <div class="board-tool-wrapper group">
          <div
            v-if="isRecommendingRules"
            class="board-tool-status board-tool-status--warning"
          ></div>
          <HintTooltip :content="ruleTooltipContent">
            <button
              type="button"
              @click="openRuleRecommendationsFromActionDock"
              data-testid="open-rule-recommendations"
              :disabled="isSceneReplacementInProgress || traceAnimationState.visible || simulationAnimationState.visible || isAnimationLocked || isRecommendationRunningForAnother('rule')"
              :aria-label="t('app.openRuleRecommendations')"
              :aria-pressed="showRecommendationPanel || isRecommendingRules"
              class="board-tool-button transition-colors"
            >
              <span v-if="isRecommendingRules" class="material-symbols-outlined animate-spin" aria-hidden="true">sync</span>
              <span v-else class="material-symbols-outlined" aria-hidden="true">rule_settings</span>
              <span class="board-tool-label">{{ t('app.rulesTool') }}</span>
            </button>
          </HintTooltip>
        </div>

        <div class="board-tool-wrapper group">
          <div
            v-if="isRecommendingDevices"
            class="board-tool-status"
          ></div>
          <HintTooltip :content="deviceTooltipContent">
            <button
              type="button"
              @click="openDeviceRecommendationsFromActionDock"
              data-testid="open-device-recommendations"
              :disabled="isSceneReplacementInProgress || traceAnimationState.visible || simulationAnimationState.visible || isAnimationLocked || isRecommendationRunningForAnother('device')"
              :aria-label="t('app.openDeviceRecommendations')"
              :aria-pressed="showDeviceRecommendationPanel || isRecommendingDevices"
              class="board-tool-button transition-colors"
            >
              <span v-if="isRecommendingDevices" class="material-symbols-outlined animate-spin" aria-hidden="true">sync</span>
              <span v-else class="material-symbols-outlined" aria-hidden="true">devices_other</span>
              <span class="board-tool-label">{{ t('app.devicesTool') }}</span>
            </button>
          </HintTooltip>
        </div>

        <div class="board-tool-wrapper group">
          <div
            v-if="isRecommendingSpecs"
            class="board-tool-status board-tool-status--danger"
          ></div>
          <HintTooltip :content="specTooltipContent">
            <button
              type="button"
              @click="openSpecRecommendationsFromActionDock"
              data-testid="open-spec-recommendations"
              :disabled="isSceneReplacementInProgress || traceAnimationState.visible || simulationAnimationState.visible || isAnimationLocked || isRecommendationRunningForAnother('spec')"
              :aria-label="t('app.openSpecificationRecommendations')"
              :aria-pressed="showSpecRecommendationPanel || isRecommendingSpecs"
              class="board-tool-button transition-colors"
            >
              <span v-if="isRecommendingSpecs" class="material-symbols-outlined animate-spin" aria-hidden="true">sync</span>
              <span v-else class="material-symbols-outlined" aria-hidden="true">playlist_add_check</span>
              <span class="board-tool-label">{{ t('app.specificationsTool') }}</span>
            </button>
          </HintTooltip>
        </div>
        </div>
      </div>
    </div>

    <KeepAlive :key="currentAuthUserId ?? undefined">
      <TraceHistoryPanel
        v-if="showHistoryPanel"
        :active-layer="activeHistoryLayer"
        :result-filter="activeHistoryResultFilter"
        :verification-tasks="verificationTasks"
        :fuzzing-tasks="fuzzingTasks"
        :simulation-tasks="simulationTasks"
        :verification-runs="verificationRuns"
        :fuzzing-runs="fuzzingRuns"
        :simulation-runs="simulationRuns"
        :loading-tasks="loadingTaskHistory"
        :task-error="taskInboxError"
        :loading-results="loadingResultHistory"
        :result-errors="historyResultErrors"
        :has-more-fuzzing-runs="fuzzingRunsHasMore"
        :loading-more-fuzzing-runs="loadingMoreFuzzingRuns"
        :pending-task-action-keys="pendingTaskActionKeys"
        :pending-result-delete-keys="pendingHistoryDeleteKeys"
        :action-locked="historyActionLocked"
        :current-model-fingerprints="currentBoardModelFingerprints"
        :model-comparison-loading="loadingCurrentBoardModelFingerprints"
        @update:active-layer="setHistoryLayer"
        @update:result-filter="setHistoryResultFilter"
        @close="closeHistoryPanel"
        @refresh-tasks="refreshHistoryTasks"
        @refresh-results="refreshHistoryResults"
        @load-more-fuzzing-runs="loadMoreFuzzingRuns"
        @watch-verification-task="watchVerificationTask"
        @watch-fuzzing-task="watchFuzzingTask"
        @watch-simulation-task="watchSimulationTask"
        @cancel-verification-task="cancelVerificationTaskFromInbox"
        @cancel-fuzzing-task="cancelFuzzingTaskFromInbox"
        @cancel-simulation-task="cancelSimulationTaskFromInbox"
        @reopen-task-settings="reopenTaskSettings"
        @dismiss-verification-task="dismissVerificationTask"
        @dismiss-fuzzing-task="dismissFuzzingTask"
        @dismiss-simulation-task="dismissSimulationTask"
        @open-verification-run="openVerificationRunFromHistory"
        @delete-verification-run="deleteVerificationRun"
        @download-verification-run-smv="downloadVerificationRunSmv"
        @view-verification-trace="openVerificationTraceFromHistory"
        @fix-verification-trace="openFixForVerificationTrace"
        @view-simulation-run="openSimulationRunFromHistory"
        @delete-simulation-run="deleteSimulationRun"
        @download-simulation-trace-smv="downloadSimulationTraceSmv"
        @open-fuzzing-run="openFuzzingRunFromHistory"
        @delete-fuzzing-run="deleteFuzzingRun"
        @view-fuzzing-finding="openFuzzingFinding"
        @verify-fuzzing-finding="openFormalVerificationForFuzzFinding"
      />
    </KeepAlive>

    <div
      v-if="miniTaskItems.length > 0 && !isWorkflowPanelOpen"
      data-testid="mini-task-indicator"
      class="board-mini-tasks fixed z-40 w-[360px] max-w-[calc(100vw-2rem)] rounded-xl border shadow-2xl"
    >
      <div class="flex items-center justify-between border-b board-border-subtle px-3 py-2">
        <div class="flex items-center gap-2">
          <span class="material-symbols-outlined board-text-info text-lg">pending_actions</span>
          <span class="text-xs font-semibold board-text-strong">
            {{ t('app.backgroundTasks') }}
          </span>
        </div>
        <HintTooltip :content="isModelPlaybackActive ? t('app.playbackReadOnlyCloseFirst') : t('app.taskInbox')">
          <button
            type="button"
            class="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-semibold board-text-info hover:board-chip-info"
            :disabled="isModelPlaybackActive"
            @click="openTaskInbox"
          >
            <span class="material-symbols-outlined text-sm">inbox</span>
            {{ t('app.taskInbox') }}
          </button>
        </HintTooltip>
      </div>
      <div class="space-y-2 p-3">
        <div
          v-for="task in miniTaskItems.slice(0, 3)"
          :key="task.key"
          class="board-card-surface rounded-lg border p-2"
        >
          <div class="flex items-center justify-between gap-2">
            <div class="min-w-0">
              <div class="truncate text-xs font-semibold board-text-strong">
                {{ task.label }}
              </div>
              <div class="truncate text-[11px] board-text-muted">
                {{ task.status }}
              </div>
            </div>
            <HintTooltip :content="miniTaskCancelLabel(task.kind)">
              <button
                type="button"
                class="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-md board-text-muted hover:board-chip-danger hover:board-text-danger"
                :aria-label="miniTaskCancelLabel(task.kind)"
                @click="cancelMiniTask(task.kind, task.id)"
              >
                <span class="material-symbols-outlined text-sm" aria-hidden="true">cancel</span>
              </button>
            </HintTooltip>
          </div>
          <div
            class="mt-2 h-1.5 overflow-hidden rounded-full board-bg-control"
            role="progressbar"
            :aria-label="`${task.label}: ${task.status}`"
            aria-valuemin="0"
            aria-valuemax="100"
            :aria-valuenow="task.progress"
          >
            <div
              class="h-full rounded-full bg-[color:var(--accent)] transition-all"
              :style="{ width: `${task.progress}%` }"
            ></div>
          </div>
        </div>
        <button
          v-if="miniTaskItems.length > 3"
          type="button"
          class="w-full rounded-md px-2 py-1 text-xs font-semibold board-text-info hover:board-chip-info"
          @click="openTaskInbox"
        >
          {{ t('app.viewMoreTasks', { count: miniTaskItems.length - 3 }) }}
        </button>
      </div>
    </div>

    <FuzzingPanel
      v-if="showFuzzingPanel"
      :form="fuzzingForm"
      :specifications="specifications"
      :running="isFuzzing"
      :progress="asyncFuzzingTask.progress"
      :status="formatWatchedTaskStatus(asyncFuzzingTask.statusKey, cancellingFuzzingTask)"
      :task-id="asyncFuzzingTask.taskId"
      :cancelling="cancellingFuzzingTask"
      :error="fuzzingErrorMessage"
      :failure-details="fuzzingFailureDetails"
      :configuration-error="effectiveFuzzingConfigurationError"
      :workload="fuzzingWorkload"
      :workload-limit="fuzzingWorkloadLimit"
      :workload-preview="fuzzingWorkloadReady ? fuzzingWorkloadPreview : null"
      :workload-ready="fuzzingWorkloadReady"
      :workload-loading="fuzzingWorkloadPreviewLoading"
      :workload-error="fuzzingWorkloadPreviewError"
      :paper-domain-preview="paperDomainPreview"
      :bundled-device-ids="bundledBoardDeviceIds"
      :bundled-environment-names="bundledBoardEnvironmentNames"
      :paper-domain-loading="paperDomainPreviewLoading"
      :paper-domain-error="paperDomainPreviewError"
      :notice="fuzzingSettingsNoticeMessage"
      :preflight-blocked="fuzzingContentCommandUnsupported"
      :preflight-message="fuzzingContentCommandUnsupported ? t('app.fuzzContentCommandPreflightBlocked') : null"
      :frozen-task="fuzzingWatchedTask"
      @submit="runFuzzing"
      @cancel="cancelAsyncFuzzing"
      @close="showFuzzingPanel = false"
      @refresh-paper-domain="refreshPaperDomainPreview"
      @refresh-workload="refreshFuzzingWorkloadPreview"
    />

    <!-- Verification Panel -->
    <div 
      v-if="showVerificationPanel"
      :ref="setVerificationPanelRef"
      data-testid="verification-panel"
      class="board-floating-panel board-run-panel board-surface-panel fixed top-20 z-30 max-w-[calc(100vw-2rem)] rounded-2xl shadow-2xl border overflow-hidden"
      role="region"
      aria-labelledby="verification-panel-title"
      tabindex="-1"
      autofocus
      @keydown="handleVerificationPanelKeydown"
    >
      <div class="relative overflow-hidden">
        <div class="board-panel-banner absolute inset-0"></div>
        <div class="board-tool-header relative">
          <div class="flex items-center gap-3">
            <div class="board-section-icon board-section-icon--lg">
              <span class="material-symbols-outlined text-xl">verified_user</span>
            </div>
            <div>
              <h3 id="verification-panel-title" class="board-panel-title">{{ t('app.verification') }}</h3>
            </div>
          </div>
          <HintTooltip :content="t('app.close')">
            <button
              type="button"
              @click="closeVerificationPanel"
              data-testid="close-verification-panel"
              :aria-label="t('app.close')"
              class="board-panel-close"
            >
              <span class="material-symbols-outlined" aria-hidden="true">close</span>
            </button>
          </HintTooltip>
        </div>
      </div>
      <!-- Verification Options -->
      <div class="board-run-panel__body iot-scroll-region p-3 space-y-3">
        <BackgroundTaskSummary v-if="runningVerificationTask" kind="verification" :task="runningVerificationTask" />
        <template v-if="!isVerifying || !asyncVerificationActive">
        <section
          v-if="fuzzVerificationHandoff"
          data-testid="fuzz-verification-handoff"
          class="rounded-lg board-surface-info px-3 py-2.5 text-xs leading-4 board-text-info"
          aria-labelledby="fuzz-verification-handoff-title"
        >
          <h4 id="fuzz-verification-handoff-title" class="board-section-title board-text-info">
            {{ t('app.fuzzVerificationHandoffTitle', { run: fuzzVerificationHandoff.runId }) }}
          </h4>
          <p v-if="fuzzVerificationHandoff.specificationLabel" class="mt-1 break-words font-semibold">
            {{ t('app.fuzzVerificationHandoffTarget', { specification: fuzzVerificationHandoff.specificationLabel }) }}
          </p>
          <p class="mt-1">{{ t('app.fuzzVerificationHandoffCurrentBoard') }}</p>
          <p
            v-if="!fuzzVerificationHandoff.targetPresent"
            class="mt-2 rounded-md board-surface-warning px-2 py-1.5 font-semibold board-text-warning"
            role="alert"
          >
            {{ t('app.fuzzVerificationHandoffTargetMissing') }}
          </p>
          <p
          v-else-if="fuzzVerificationHandoff.boardDrifted"
            class="mt-2 rounded-md board-surface-warning px-2 py-1.5 font-semibold board-text-warning"
          >
            {{ t('app.fuzzVerificationHandoffScopeChanged') }}
          </p>
        </section>

        <!-- Attack Mode -->
        <div class="board-card p-3 rounded-xl">
          <div class="flex items-center justify-between gap-3">
            <div class="flex min-w-0 items-center gap-3">
            <div class="w-8 h-8 shrink-0 board-chip-danger rounded-lg flex items-center justify-center">
              <span class="material-symbols-outlined board-text-danger text-lg">warning</span>
            </div>
            <label class="board-form-label">
              {{ t('app.attackMode') }}
            </label>
            </div>
            <ToggleSwitch
              :checked="verificationForm.isAttack"
              :label="t('app.attackMode')"
              tone="adversarial"
              test-id="verification-attack-toggle"
              :disabled="isVerifying || (!verificationForm.isAttack && !hasModeledAttackEffect)"
              :describedby-id="!hasModeledAttackEffect ? 'verification-attack-unavailable' : undefined"
              @change="setVerificationAttackEnabled"
            />
          </div>
          <p
            v-if="!hasModeledAttackEffect"
            id="verification-attack-unavailable"
            data-testid="verification-attack-unavailable"
            class="mt-2 text-xs leading-4 board-text-warning"
          >
            {{ t('app.attackNoModeledEffect') }}
          </p>
        </div>

        <div v-if="verificationForm.isAttack && hasModeledAttackEffect" class="space-y-3 rounded-lg board-surface-danger p-3">
          <div class="grid grid-cols-2 gap-2" role="group" :aria-label="t('app.attackSelectionMode')">
            <button
              type="button"
              data-testid="verification-attack-mode-exhaustive"
              class="board-run-mode board-run-mode--adversarial"
              :aria-pressed="verificationForm.attackMode === 'ANY_UP_TO_BUDGET'"
              :disabled="isVerifying"
              @click="setAttackMode(verificationForm, 'ANY_UP_TO_BUDGET')"
            >
              {{ t('app.attackModeExhaustive') }}
            </button>
            <button
              type="button"
              data-testid="verification-attack-mode-exact"
              class="board-run-mode board-run-mode--adversarial"
              :aria-pressed="verificationForm.attackMode === 'EXACT_POINTS'"
              :disabled="isVerifying"
              @click="setAttackMode(verificationForm, 'EXACT_POINTS')"
            >
              {{ t('app.attackModeExact') }}
            </button>
          </div>

          <p class="text-xs leading-4 board-text-danger">
            {{ verificationForm.attackMode === 'ANY_UP_TO_BUDGET'
              ? t('app.verificationAttackExhaustiveHint')
              : t('app.verificationAttackExactHint') }}
          </p>

          <div v-if="verificationForm.attackMode === 'EXACT_POINTS'" class="space-y-1.5" data-testid="verification-attack-points"
            role="group" :aria-label="t('app.attackModeExact')"
            :aria-describedby="verificationAttackConfigurationError ? 'verification-attack-points-invalid' : undefined">
            <label
              v-for="point in boardAttackSurface.points"
              :key="point.key"
              class="board-form-label board-attack-point"
            >
              <input
                type="checkbox"
                :checked="verificationForm.selectedAttackPointKeys.includes(point.key)"
                :disabled="isVerifying || !point.selectable"
                :data-testid="`verification-attack-point-${point.key}`"
                @change="toggleAttackPoint(verificationForm, point.key)"
              />
              <span class="material-symbols-outlined text-base board-text-danger" aria-hidden="true">
                {{ point.kind === 'DEVICE' ? 'memory' : 'conversion_path' }}
              </span>
              <span class="min-w-0 flex-1 break-words">{{ point.label }}</span>
              <span class="shrink-0 board-form-label">
                {{ point.kind === 'DEVICE' ? t('app.device') : t('app.automationLink') }}
              </span>
            </label>
          </div>

          <!-- Attack budget (exhaustive verification only) -->
          <div v-if="verificationForm.attackMode === 'ANY_UP_TO_BUDGET'">
          <div class="mb-2 flex items-center justify-between gap-2">
            <label for="verification-attack-budget" class="board-form-label min-w-0">
              {{ t('app.attackBudgetLabel') }}:
              <span class="board-text-danger">{{ verificationForm.attackBudget }} / {{ attackBudgetMax }}</span>
            </label>
            <InfoTooltip
              :text="t('app.verificationAttackBudgetHint', { limit: attackBudgetMax, surface: attackSurfacePointCount })"
              :label="t('app.showHelpFor', { topic: t('app.attackBudgetLabel') })"
              placement="left"
              tone="danger"
              test-id="verification-attack-budget-help"
            />
          </div>
          <input
            id="verification-attack-budget"
            v-model.number="verificationForm.attackBudget"
            data-testid="verification-attack-budget"
            :disabled="isVerifying"
            type="range"
            min="1"
            :max="attackBudgetMax"
            :aria-invalid="Boolean(verificationAttackConfigurationError)"
            :aria-describedby="verificationAttackConfigurationError ? 'verification-attack-budget-invalid' : undefined"
            class="board-run-range board-run-range--adversarial w-full"
          />
          <div class="flex justify-between text-[length:var(--iot-font-min)] board-text-danger mt-1">
            <span>1</span>
            <span>{{ attackBudgetMax }}</span>
          </div>
          <p v-if="attackBudgetIsCapped" class="mt-1 text-xs font-semibold leading-4 board-text-warning" data-testid="verification-attack-budget-cap">
            {{ t('app.attackBudgetCappedHint', { surface: attackSurfacePointCount, limit: attackBudgetMax }) }}
          </p>
          <p v-if="verificationAttackConfigurationError" id="verification-attack-budget-invalid" class="mt-1 text-xs font-semibold leading-4 board-text-danger" data-testid="verification-attack-budget-invalid">
            {{ verificationAttackConfigurationError }}
          </p>
          </div>
          <p v-else-if="verificationAttackConfigurationError" id="verification-attack-points-invalid" class="text-xs font-semibold leading-4 board-text-danger" data-testid="verification-attack-points-invalid">
            {{ verificationAttackConfigurationError }}
          </p>
        </div>

        <!-- Privacy Analysis -->
        <div class="board-card p-3 rounded-xl">
          <div class="flex items-center justify-between gap-3">
            <div class="flex min-w-0 items-center gap-3">
            <div class="w-8 h-8 shrink-0 board-chip-info rounded-lg flex items-center justify-center">
              <span class="material-symbols-outlined board-text-info text-lg">security</span>
            </div>
            <label class="board-form-label">
              {{ t('app.privacyAnalysis') }}
            </label>
            <InfoTooltip
              :text="hasPrivacySpecification ? t('app.privacyModelRequiredHint') : t('app.privacyModelHint')"
              :label="t('app.showHelpFor', { topic: t('app.privacyAnalysis') })"
              placement="left"
              tone="sensitivity"
              test-id="verification-privacy-help"
            />
            </div>
            <ToggleSwitch
              :checked="verificationForm.enablePrivacy"
              :label="t('app.privacyAnalysis')"
              tone="sensitivity"
              test-id="verification-privacy-toggle"
              :disabled="isVerifying || hasPrivacySpecification"
              :describedby-id="hasPrivacySpecification ? 'verification-privacy-required' : undefined"
              @change="value => verificationForm.enablePrivacy = value"
            />
          </div>
          <p v-if="hasPrivacySpecification" id="verification-privacy-required" class="mt-2 text-xs font-semibold leading-4 board-text-info" data-testid="verification-privacy-required">
            {{ t('app.privacyModelRequiredStatus') }}
          </p>
        </div>

        <!-- Run Mode -->
        <div class="board-card p-3 rounded-xl">
          <div class="flex items-center gap-3 mb-2">
            <div class="w-8 h-8 shrink-0 board-chip-info rounded-lg flex items-center justify-center">
              <span class="material-symbols-outlined board-text-info text-lg">schedule</span>
            </div>
            <label class="board-form-label">
              {{ t('app.runMode') }}
            </label>
          </div>
          <div class="grid grid-cols-2 gap-1 rounded-lg board-bg-control p-1">
            <button
              type="button"
              :disabled="isVerifying"
              @click="verificationForm.isAsync = false"
              data-testid="verification-mode-sync"
              :aria-pressed="!verificationForm.isAsync"
              class="board-run-mode"
            >
              {{ t('app.waitForResult') }}
            </button>
            <button
              type="button"
              :disabled="isVerifying"
              @click="verificationForm.isAsync = true"
              data-testid="verification-mode-async"
              :aria-pressed="verificationForm.isAsync"
              class="board-run-mode"
            >
              {{ t('app.backgroundTask') }}
            </button>
          </div>
          <p v-if="verificationForm.isAsync" class="mt-2 text-xs leading-5 board-text-muted">
            {{ t('app.asyncVerificationModeHint') }}
          </p>
        </div>

        </template>
        <!-- Async Progress (visible when async verification is running) -->
        <div v-if="isVerifying && asyncVerificationActive" class="space-y-1">
          <div class="flex items-center justify-between text-xs">
            <span class="board-text-success font-medium">{{ formatWatchedTaskStatus(asyncVerificationTask.statusKey, cancellingVerificationTask) }}</span>
            <div v-if="asyncVerificationTask.taskId" class="flex items-center gap-2">
              <span class="board-text-success font-semibold">{{ asyncVerificationTask.progress }}%</span>
              <HintTooltip :content="t('app.cancelVerificationTask')">
                <button
                  type="button"
                  class="board-panel-icon-action"
                  :disabled="cancellingVerificationTask"
                  :aria-label="t('app.cancelVerificationTask')"
                  @click="cancelAsyncVerification"
                >
                  <span class="material-symbols-outlined text-sm" aria-hidden="true">{{ cancellingVerificationTask ? 'hourglass_empty' : 'cancel' }}</span>
                </button>
              </HintTooltip>
            </div>
          </div>
          <div class="w-full h-2 board-chip-success rounded-full overflow-hidden">
            <div
              class="h-full bg-[color:var(--success)] transition-all duration-500 ease-out"
              :class="{ 'animate-pulse': !asyncVerificationTask.taskId }"
              :style="{ width: asyncVerificationTask.taskId ? `${asyncVerificationTask.progress}%` : '35%' }"
            />
          </div>
        </div>

      </div>
      <div class="board-run-panel__footer">
        <!-- Run Verification Button -->
        <button
          @click="runVerification"
          data-testid="run-verification"
          :disabled="isVerifying || Boolean(verificationRunBlockedReason)"
          :aria-describedby="verificationRunBlockedDescriptionId"
          class="board-panel-submit"
        >
          <template v-if="!isBoardDataReady && failedBoardDataKeys.length === 0">
            <span class="material-symbols-outlined text-sm animate-spin">sync</span>
            {{ t('app.loading') }}
          </template>
          <template v-else-if="isVerifying">
            <span class="material-symbols-outlined text-sm animate-spin">sync</span>
            {{ t('app.verifying') }}
          </template>
          <template v-else>
            <span class="material-symbols-outlined text-sm">play_arrow</span>
            {{ verificationForm.isAsync ? t('app.createVerificationTask') : t('app.runVerificationNow') }}
          </template>
        </button>
        <p
          v-if="verificationRunBlockedDescriptionId === 'verification-run-blocked-reason'"
          id="verification-run-blocked-reason"
          data-testid="verification-run-blocked-reason"
          class="text-xs leading-5 board-text-warning"
          role="status"
        >
          {{ verificationRunBlockedReason }}
        </p>
      </div>
    </div>

    <!-- Scenario Recommendation Panel -->
    <div
      v-if="showScenarioRecommendationPanel"
      :ref="setScenarioRecommendationPanelRef"
      data-testid="scenario-recommendation-panel"
      class="board-floating-panel board-recommendation-panel board-surface-panel fixed top-20 z-30 w-[28rem] max-w-[calc(100vw-2rem)] rounded-2xl shadow-2xl border overflow-hidden"
      role="region"
      aria-labelledby="scenario-recommendation-panel-title"
      tabindex="-1"
      autofocus
      @keydown="handleScenarioRecommendationPanelKeydown"
    >
      <div class="relative overflow-hidden">
        <div class="board-panel-banner absolute inset-0"></div>
        <div class="board-tool-header relative">
          <div class="flex items-center gap-3">
            <div class="board-section-icon board-section-icon--lg">
              <span class="material-symbols-outlined text-xl">account_tree</span>
            </div>
            <div>
              <h3 id="scenario-recommendation-panel-title" class="board-panel-title">{{ t('app.scenarioRecommendations') }}</h3>
            </div>
          </div>
          <HintTooltip :content="t('app.close')">
            <button
              type="button"
              @click="closeScenarioRecommendationPanel"
              data-testid="close-scenario-recommendations"
              :aria-label="t('app.close')"
              class="board-panel-close"
            >
              <span class="material-symbols-outlined" aria-hidden="true">close</span>
            </button>
          </HintTooltip>
        </div>
      </div>

      <div class="board-recommendation-panel__body iot-scroll-region p-3 space-y-3">
        <div class="board-card grid grid-cols-1 gap-2 rounded-lg border board-border-subtle p-2 sm:grid-cols-3">
          <fieldset class="min-w-0">
            <legend class="text-xs font-semibold board-text-strong">{{ t('app.devicesTool') }}</legend>
            <div class="mt-1 grid grid-cols-2 gap-1">
              <label class="board-form-label min-w-0">
                {{ t('app.scenarioMinimum') }}
                <input
                  v-model.number="scenarioRecommendationFilters.minDevices"
                  :disabled="isRecommendingScenario"
                  data-testid="scenario-min-devices"
                  :aria-invalid="scenarioRecommendationInput.invalidFields.includes('minDevices') || undefined"
                  :aria-describedby="scenarioRecommendationInput.invalidFields.includes('minDevices') ? 'scenario-count-error' : undefined"
                  type="number"
                  min="1"
                  max="10"
                  class="board-field mt-0.5"
                />
              </label>
              <label class="board-form-label min-w-0">
                {{ t('app.scenarioMaximum') }}
                <input
                  v-model.number="scenarioRecommendationFilters.maxDevices"
                  :disabled="isRecommendingScenario"
                  data-testid="scenario-max-devices"
                  :aria-invalid="scenarioRecommendationInput.invalidFields.includes('maxDevices') || undefined"
                  :aria-describedby="scenarioRecommendationInput.invalidFields.includes('maxDevices') ? 'scenario-count-error' : undefined"
                  type="number"
                  min="1"
                  max="10"
                  class="board-field mt-0.5"
                />
              </label>
            </div>
          </fieldset>
          <fieldset class="min-w-0">
            <legend class="text-xs font-semibold board-text-strong">{{ t('app.rulesTool') }}</legend>
            <div class="mt-1 grid grid-cols-2 gap-1">
              <label class="board-form-label min-w-0">
                {{ t('app.scenarioMinimum') }}
                <input
                  v-model.number="scenarioRecommendationFilters.minRules"
                  :disabled="isRecommendingScenario"
                  data-testid="scenario-min-rules"
                  :aria-invalid="scenarioRecommendationInput.invalidFields.includes('minRules') || undefined"
                  :aria-describedby="scenarioRecommendationInput.invalidFields.includes('minRules') ? 'scenario-count-error' : undefined"
                  type="number"
                  min="1"
                  max="10"
                  class="board-field mt-0.5"
                />
              </label>
              <label class="board-form-label min-w-0">
                {{ t('app.scenarioMaximum') }}
                <input
                  v-model.number="scenarioRecommendationFilters.maxRules"
                  :disabled="isRecommendingScenario"
                  data-testid="scenario-max-rules"
                  :aria-invalid="scenarioRecommendationInput.invalidFields.includes('maxRules') || undefined"
                  :aria-describedby="scenarioRecommendationInput.invalidFields.includes('maxRules') ? 'scenario-count-error' : undefined"
                  type="number"
                  min="1"
                  max="10"
                  class="board-field mt-0.5"
                />
              </label>
            </div>
          </fieldset>
          <fieldset class="min-w-0">
            <legend class="text-xs font-semibold board-text-strong">{{ t('app.specificationsTool') }}</legend>
            <div class="mt-1 grid grid-cols-2 gap-1">
              <label class="board-form-label min-w-0">
                {{ t('app.scenarioMinimum') }}
                <input
                  v-model.number="scenarioRecommendationFilters.minSpecs"
                  :disabled="isRecommendingScenario"
                  data-testid="scenario-min-specs"
                  :aria-invalid="scenarioRecommendationInput.invalidFields.includes('minSpecs') || undefined"
                  :aria-describedby="scenarioRecommendationInput.invalidFields.includes('minSpecs') ? 'scenario-count-error' : undefined"
                  type="number"
                  min="1"
                  max="10"
                  class="board-field mt-0.5"
                />
              </label>
              <label class="board-form-label min-w-0">
                {{ t('app.scenarioMaximum') }}
                <input
                  v-model.number="scenarioRecommendationFilters.maxSpecs"
                  :disabled="isRecommendingScenario"
                  data-testid="scenario-max-specs"
                  :aria-invalid="scenarioRecommendationInput.invalidFields.includes('maxSpecs') || undefined"
                  :aria-describedby="scenarioRecommendationInput.invalidFields.includes('maxSpecs') ? 'scenario-count-error' : undefined"
                  type="number"
                  min="1"
                  max="10"
                  class="board-field mt-0.5"
                />
              </label>
            </div>
          </fieldset>
        </div>

        <p v-if="scenarioRecommendationInput.error" id="scenario-count-error" data-testid="scenario-count-error"
          role="status" class="text-xs board-text-danger">{{ scenarioRecommendationInput.error }}</p>

        <div class="board-form-label board-card rounded-lg border board-border-subtle p-2 block">
          <div class="flex items-center justify-between gap-3">
            <label for="scenario-recommendation-requirement" class="board-form-label">{{ t('app.recommendationScenario') }}</label>
            <InfoTooltip :text="t('app.scenarioRecommendationBasisHint')" :label="t('app.showHelpFor', { topic: t('app.recommendationScenario') })" test-id="scenario-recommendation-help" />
          </div>
          <textarea
            id="scenario-recommendation-requirement"
            v-model.trim="scenarioRecommendationFilters.userRequirement"
            :disabled="isRecommendingScenario"
            rows="3"
            :maxlength="AI_RECOMMENDATION_REQUIREMENT_MAX_LENGTH"
            :placeholder="t('app.scenarioRecommendationPlaceholder')"
            class="board-field mt-1 resize-none leading-relaxed"
          ></textarea>
        </div>

        <button
          type="button"
          data-testid="generate-scenario-recommendation"
          :aria-describedby="scenarioRecommendationInput.error ? 'scenario-count-error' : undefined"
          @click="fetchScenarioRecommendation"
          :disabled="isSceneReplacementInProgress || isRecommendationRunningForAnother('scenario') || (!isRecommendingScenario && Boolean(scenarioRecommendationInput.error))"
          :class="[
            'board-panel-submit board-action-disarmed',
                isRecommendingScenario ? 'bg-[color:var(--danger-fill)] hover:bg-[color:var(--danger-fill-hover)]' : 'bg-[color:var(--accent-fill)] hover:bg-[color:var(--accent-fill-hover)]'
          ]"
        >
          <span class="material-symbols-outlined text-base">
            {{ isRecommendingScenario ? 'stop' : 'auto_awesome' }}
          </span>
          {{ isRecommendingScenario ? t('app.stopScenarioRecommendation') : t('app.generateScenarioRecommendation') }}
        </button>

        <RecommendationProgressStatus
          v-if="isRecommendingScenario"
          :elapsed-seconds="recommendationProgressElapsed"
          :stage="recommendationProgressStage"
          :template-count="deviceTemplates.length"
          :device-count="nodes.length"
          :rule-count="rules.length"
          :spec-count="specifications.length"
        />

        <div v-if="recommendationFailures.scenario" data-testid="scenario-recommendation-error"
          role="alert" class="rounded-lg board-surface-danger px-3 py-2 text-xs leading-5 board-text-danger">
          <p class="font-semibold">{{ recommendationFailures.scenario.message }}</p>
          <FailureDetails v-bind="recommendationFailures.scenario" />
        </div>

        <div
          v-if="scenarioRecommendationResult?.filteredCount && scenarioRecommendationResult.filteredCount > 0 && !isRecommendingScenario"
          class="rounded-lg border board-border-subtle board-bg-muted px-3 py-2 text-xs font-medium board-text-muted"
        >
          <p>{{ t('app.recommendationFilteredNotice', { count: scenarioRecommendationResult.filteredCount }) }}</p>
          <ul
            v-if="scenarioRecommendationResult.filteredItems?.length"
            class="iot-scroll-region mt-1 max-h-32 list-disc space-y-0.5 pl-4 pr-1 font-normal leading-relaxed"
          >
            <li
              v-for="(item, index) in scenarioRecommendationResult.filteredItems"
              :key="`${item.type || 'item'}-${item.index || index}-${item.reasonCode || item.reason}`"
            >
              {{ formatRecommendationFilteredItem(item) }}
            </li>
          </ul>
          <p v-else class="mt-1 font-normal board-text-muted">
            {{ t('app.recommendationFilteredNoDetails') }}
          </p>
        </div>
        <div
          v-if="scenarioRecommendationResult?.adjustedCount && scenarioRecommendationResult.adjustedCount > 0 && !isRecommendingScenario"
          data-testid="scenario-adjusted-items"
          class="rounded-lg board-surface-warning px-3 py-2 text-xs font-medium board-text-warning"
        >
          <p>{{ t('app.recommendationAdjustedNotice', { count: scenarioRecommendationResult.adjustedCount }) }}</p>
          <ul class="iot-scroll-region mt-1 max-h-36 list-disc space-y-0.5 pl-4 pr-1 font-normal leading-relaxed">
            <li
              v-for="(item, index) in scenarioRecommendationResult.adjustedItems || []"
              :key="`${item.type || 'item'}-${item.index || index}-${item.reasonCode || item.reason}`"
            >
              {{ formatRecommendationAdjustmentItem(item, 'scenario') }}
            </li>
          </ul>
        </div>

        <div v-if="!isRecommendingScenario && scenarioRecommendationRequested && !recommendedScenarioScene && !recommendationFailures.scenario" class="flex flex-col items-center justify-center py-10">
          <div class="w-16 h-16 board-bg-control rounded-full flex items-center justify-center mb-3">
            <span class="material-symbols-outlined board-text-muted text-3xl">account_tree</span>
          </div>
          <p class="board-text-muted text-sm font-medium mt-2">{{ t('app.noRecommendationsAvailable') }}</p>
          <p class="board-text-muted text-xs mt-1 text-center px-4">{{ t('app.recommendationEmptyGuidance') }}</p>
          <ScenarioObjectiveIssues
            v-if="scenarioRecommendationResult"
            :status="scenarioRecommendationResult.objectiveStatus"
            :issues="scenarioRecommendationResult.objectiveIssues"
            :title="t('app.scenarioObjectiveIncompleteTitle')"
            :format-issue="formatScenarioObjectiveIssue"
          />
        </div>

        <div v-else-if="!isRecommendingScenario && scenarioRecommendationRequested && recommendedScenarioScene" class="space-y-3">
          <div class="board-card board-card--raised rounded-xl border board-border-subtle p-3">
            <div class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <h4 class="board-section-title truncate">
                  {{ localizedRecommendationText(scenarioRecommendationResult?.scenarioName, t('app.scenarioRecommendations')) }}
                </h4>
                <p v-if="scenarioRecommendationResult?.rationale" class="mt-1 text-xs leading-relaxed board-text-muted">
                  {{ localizedRecommendationText(scenarioRecommendationResult.rationale, t('app.recommendedBasedOnCurrentDevices')) }}
                </p>
              </div>
              <div class="shrink-0 rounded-full board-chip-info px-2 py-1 text-xs font-semibold board-text-info">
                {{ t('app.scenarioSummaryCount', { count: scenarioRecommendationResult?.count || 0 }) }}
              </div>
            </div>

            <div class="mt-3 grid grid-cols-4 gap-2 text-center">
              <div class="rounded-lg board-bg-muted px-2 py-2">
                <div class="board-metric board-text-strong">{{ recommendedScenarioScene.devices.length }}</div>
                <div class="text-xs board-text-muted">{{ t('app.devicesTool') }}</div>
              </div>
              <div class="rounded-lg board-bg-muted px-2 py-2">
                <div class="board-metric board-text-strong">{{ recommendedScenarioScene.environmentVariables.length }}</div>
                <div class="text-xs board-text-muted">{{ t('app.environmentPool') }}</div>
              </div>
              <div class="rounded-lg board-bg-muted px-2 py-2">
                <div class="board-metric board-text-strong">{{ recommendedScenarioScene.rules.length }}</div>
                <div class="text-xs board-text-muted">{{ t('app.rulesTool') }}</div>
              </div>
              <div class="rounded-lg board-bg-muted px-2 py-2">
                <div class="board-metric board-text-strong">{{ recommendedScenarioScene.specs.length }}</div>
                <div class="text-xs board-text-muted">{{ t('app.specificationsTool') }}</div>
              </div>
            </div>

            <ScenarioObjectiveIssues
              v-if="scenarioRecommendationResult"
              :status="scenarioRecommendationResult.objectiveStatus"
              :issues="scenarioRecommendationResult.objectiveIssues"
              :title="t('app.scenarioObjectiveIncompleteTitle')"
              :format-issue="formatScenarioObjectiveIssue"
            />

            <p data-testid="scenario-recommendations-unverified" class="mt-3 text-xs board-text-muted">{{ t('app.recommendationsNotVerified') }}</p>
            <div
              class="mt-3 flex items-start gap-2 rounded-lg border px-2.5 py-2 text-xs"
              :class="scenarioRecommendationResult?.verificationReady
                ? 'board-surface-info board-text-info'
                : 'board-surface-warning board-text-warning'"
            >
              <span class="material-symbols-outlined text-base" aria-hidden="true">
                {{ scenarioRecommendationResult?.verificationReady ? 'rule' : 'info' }}
              </span>
              <div>
                <div class="font-semibold">
                  {{ scenarioRecommendationResult?.verificationReady
                    ? t('app.scenarioVerificationReady')
                    : t('app.scenarioVerificationNotReady') }}
                </div>
                <ul v-if="scenarioRecommendationResult?.readinessIssues.length" class="mt-1 list-disc pl-4">
                  <li v-for="issue in scenarioRecommendationResult.readinessIssues" :key="issue.code">
                    {{ t(`app.scenarioReadiness.${issue.code}`) }}
                  </li>
                </ul>
              </div>
            </div>
            <div
              v-if="scenarioRecommendationResult?.semanticWarnings.length"
              data-testid="scenario-semantic-warnings"
              class="mt-2 flex items-start gap-2 rounded-lg board-surface-warning px-2.5 py-2 text-xs board-text-warning"
            >
              <span class="material-symbols-outlined text-base" aria-hidden="true">warning</span>
              <div>
                <div class="font-semibold">{{ t('app.scenarioSemanticWarningsTitle') }}</div>
                <ul class="mt-1 list-disc space-y-0.5 pl-4">
                  <li v-for="warning in scenarioRecommendationResult.semanticWarnings" :key="warning.code">
                    {{ localizedRecommendationText(
                      warning.message,
                      t(`app.scenarioSemanticWarnings.${warning.code}`)
                    ) }}
                  </li>
                </ul>
              </div>
            </div>
          </div>

          <details class="board-card rounded-xl border board-border-subtle p-3 text-xs">
            <summary class="flex cursor-pointer items-center gap-1 font-semibold board-text-info">
              <span class="material-symbols-outlined text-sm">visibility</span>
              {{ t('app.viewScenarioDetails') }}
            </summary>
            <div class="mt-3 space-y-3 board-text-muted">
              <div>
                <div class="mb-1 font-semibold board-text-strong">{{ t('app.deviceList') }}</div>
                <div class="space-y-1">
                  <div
                    v-for="device in recommendedScenarioScene.devices"
                    :key="device.id"
                    class="rounded board-bg-muted px-2 py-1.5"
                  >
                    <div class="font-medium board-text-strong">{{ device.label }} · {{ device.templateName }}</div>
                    <div v-if="scenarioDeviceHasStateMachine(device)" class="mt-0.5 text-xs board-text-muted">
                      {{ t('app.scenarioDeviceRuntime', {
                        state: formatScenarioDeviceModelToken(device, device.state),
                        trust: t(`app.${scenarioDeviceStateTrust(device)}`),
                        privacy: t(`app.${scenarioDeviceStatePrivacy(device)}`)
                      }) }}
                    </div>
                    <div v-else class="mt-0.5 text-xs board-text-muted">
                      {{ t('app.scenarioStatelessDeviceRuntime') }}
                    </div>
                    <div v-if="device.variables?.length" class="mt-0.5 text-xs board-text-muted">
                      {{ t('app.scenarioLocalVariables', {
                        values: device.variables.map(variable => `${formatScenarioDeviceModelToken(device, variable.name)}=${formatScenarioDeviceModelToken(device, variable.value)} (${t(`app.${scenarioDeviceVariableTrust(device, variable)}`)})`).join(t('app.listSeparator'))
                      }) }}
                    </div>
                    <div v-if="device.privacies?.length" class="mt-0.5 text-xs board-text-muted">
                      {{ t('app.scenarioLocalSensitivities', {
                        values: device.privacies.map(item => `${formatScenarioDeviceModelToken(device, item.name)}=${t(`app.${item.privacy}`)}`).join(t('app.listSeparator'))
                      }) }}
                    </div>
                  </div>
                </div>
              </div>
              <div v-if="recommendedScenarioScene.environmentVariables.length">
                <div class="mb-1 font-semibold board-text-strong">{{ t('app.environmentPool') }}</div>
                <ul class="space-y-1">
                  <li
                    v-for="variable in recommendedScenarioScene.environmentVariables"
                    :key="variable.name"
                    class="rounded board-bg-muted px-2 py-1"
                  >
                    {{ t('app.scenarioEnvironmentRuntime', {
                      name: formatScenarioEnvironmentModelToken(variable.name, variable.name),
                      value: variable.value == null
                        ? t('app.empty')
                        : formatScenarioEnvironmentModelToken(variable.name, variable.value),
                      trust: t(`app.${variable.trust}`),
                      privacy: t(`app.${variable.privacy}`)
                    }) }}
                  </li>
                </ul>
              </div>
              <div v-if="recommendedScenarioScene.rules.length">
                <div class="mb-1 font-semibold board-text-strong">{{ t('app.globalRules') }}</div>
                <div class="mb-2 flex items-start gap-1.5 rounded board-surface-warning px-2 py-1.5 text-xs leading-4 board-text-warning">
                  <span class="material-symbols-outlined text-sm" aria-hidden="true">low_priority</span>
                  <span>{{ t('app.ruleExecutionOrderHint') }}</span>
                </div>
                <ul class="space-y-1">
                  <li v-for="(rule, index) in recommendedScenarioScene.rules" :key="index" class="rounded board-bg-muted px-2 py-1.5">
                    <div class="flex items-center gap-1.5 font-medium board-text-strong">
                      <span class="rounded board-chip-warning px-1 py-0.5 text-xs font-semibold board-text-warning">#{{ index + 1 }}</span>
                      <span>{{ rule.name || t('app.ruleNumber', { number: index + 1 }) }}</span>
                    </div>
                    <div class="mt-0.5 text-xs board-text-muted">
                      {{ t('app.scenarioRuleSemantics', {
                        triggers: rule.sources.map(formatScenarioRuleSource).join(` ${t('app.and')} `),
                        action: formatScenarioRuleAction(rule)
                      }) }}
                    </div>
                  </li>
                </ul>
              </div>
              <div v-if="recommendedScenarioScene.specs.length">
                <div class="mb-1 font-semibold board-text-strong">{{ t('app.specificationsTool') }}</div>
                <ul class="space-y-1">
                  <li v-for="(spec, index) in recommendedScenarioScene.specs" :key="spec.id" class="rounded board-bg-muted px-2 py-1.5">
                    <div class="font-medium board-text-strong">{{ getSpecResultDisplayTitle(spec, index) }}</div>
                    <div class="mt-0.5 break-all font-mono text-xs board-text-muted">
                      {{ t('app.formulaPreview') }}: {{ formatScenarioSpecFormula(spec) }}
                    </div>
                  </li>
                </ul>
              </div>
            </div>
          </details>

          <div class="grid grid-cols-2 gap-2">
            <button
              type="button"
              data-testid="export-recommended-scenario"
              class="board-card flex items-center justify-center gap-2 rounded-lg px-3 py-2 text-sm font-semibold board-text-info transition hover:board-chip-info"
              @click="exportRecommendedScenario"
            >
              <span class="material-symbols-outlined text-base">download</span>
              {{ t('app.exportScenarioJson') }}
            </button>
            <button
              type="button"
              data-testid="apply-recommended-scenario"
              class="flex items-center justify-center gap-2 rounded-lg bg-[color:var(--accent-fill)] px-3 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-[color:var(--accent-fill-hover)]"
              :disabled="isSceneReplacementInProgress"
              @click="applyRecommendedScenario"
            >
              <span class="material-symbols-outlined text-base">playlist_add_check</span>
              {{ t('app.replaceCurrentScene') }}
            </button>
          </div>

          <p class="px-1 text-xs leading-relaxed board-text-muted">
            {{ t('app.applyScenarioHint') }}
          </p>
        </div>
        <details
          v-if="scenarioRecommendationResult && !isRecommendingScenario"
          data-testid="scenario-candidate-accounting"
          class="board-card rounded-lg border board-border-subtle px-3 py-2 text-xs leading-relaxed board-text-muted"
        >
          <summary class="cursor-pointer py-2 font-semibold">{{ t('app.recommendationGenerationDetails') }}</summary>
          <p v-if="scenarioRecommendationMessage" class="mt-1">{{ scenarioRecommendationMessage }}</p>
          {{ t('app.recommendationCandidateSummary', {
            raw: scenarioRecommendationResult.rawCandidateCount,
            inspected: scenarioRecommendationResult.inspectedCount,
            kept: scenarioRecommendationResult.validatedCount,
            filtered: scenarioRecommendationResult.filteredCount,
            truncated: scenarioRecommendationResult.truncatedCount
          }) }}
        </details>
      </div>
    </div>

    <!-- Rule Recommendation Panel -->
    <div 
      v-if="showRecommendationPanel"
      :ref="setRuleRecommendationPanelRef"
      data-testid="rule-recommendation-panel"
      class="board-floating-panel board-recommendation-panel board-surface-panel fixed top-20 z-30 w-96 max-w-[calc(100vw-2rem)] rounded-2xl shadow-2xl border overflow-hidden"
      role="region"
      aria-labelledby="rule-recommendation-panel-title"
      tabindex="-1"
      autofocus
      @keydown="handleRuleRecommendationPanelKeydown"
    >
      <div class="relative overflow-hidden">
        <div class="board-panel-banner absolute inset-0"></div>
        <div class="board-tool-header relative">
          <div class="flex items-center gap-3">
            <div class="board-section-icon board-section-icon--lg">
              <span class="material-symbols-outlined text-xl">auto_awesome</span>
            </div>
            <div>
              <h3 id="rule-recommendation-panel-title" class="board-panel-title">{{ t('app.ruleRecommendations') }}</h3>
            </div>
          </div>
          <HintTooltip :content="t('app.close')">
            <button 
              type="button"
              @click="closeRecommendationPanel"
              data-testid="close-rule-recommendations"
              :aria-label="t('app.close')"
              class="board-panel-close"
            >
              <span class="material-symbols-outlined" aria-hidden="true">close</span>
            </button>
          </HintTooltip>
        </div>
      </div>

      <!-- Recommendation Content -->
      <div class="board-recommendation-panel__body iot-scroll-region p-3 space-y-3">
        <div class="rounded-lg border board-border-subtle board-bg-elevated p-2">
          <label class="board-form-label">
            {{ t('app.count') }}
            <input
              v-model.number="ruleRecommendationFilters.maxRecommendations"
              :disabled="isRecommendingRules"
              type="number"
              min="1"
              max="10"
              class="board-field mt-1"
            />
          </label>
        </div>

        <div class="board-form-label rounded-lg border board-border-subtle board-bg-elevated p-2 block">
          <div class="flex items-center justify-between gap-3">
            <label for="rule-recommendation-requirement" class="board-form-label">{{ t('app.recommendationScenario') }}</label>
            <InfoTooltip :text="t('app.recommendationBasisHint')" :label="t('app.showHelpFor', { topic: t('app.recommendationScenario') })" test-id="rule-recommendation-help" />
          </div>
          <textarea
            id="rule-recommendation-requirement"
            v-model.trim="ruleRecommendationFilters.userRequirement"
            :disabled="isRecommendingRules"
            rows="3"
            :maxlength="AI_RECOMMENDATION_REQUIREMENT_MAX_LENGTH"
            :placeholder="t('app.recommendationScenarioPlaceholder')"
            class="board-field mt-1 resize-none leading-relaxed"
          ></textarea>
        </div>

        <button
          type="button"
          data-testid="generate-rule-recommendations"
          @click="fetchRuleRecommendations"
          :disabled="isSceneReplacementInProgress || isRecommendationRunningForAnother('rule')"
          :class="[
            'board-panel-submit board-action-disarmed',
            isRecommendingRules ? 'bg-[color:var(--danger-fill)] hover:bg-[color:var(--danger-fill-hover)]' : 'bg-[color:var(--accent-fill)] hover:bg-[color:var(--accent-fill-hover)]'
          ]"
        >
          <span class="material-symbols-outlined text-base">
            {{ isRecommendingRules ? 'stop' : 'auto_awesome' }}
          </span>
          {{ isRecommendingRules ? t('app.stopRuleRecommendations') : t('app.generateRecommendations') }}
        </button>

        <RecommendationProgressStatus
          v-if="isRecommendingRules"
          :elapsed-seconds="recommendationProgressElapsed"
          :stage="recommendationProgressStage"
          :template-count="deviceTemplates.length"
          :device-count="nodes.length"
          :rule-count="rules.length"
          :spec-count="specifications.length"
        />

        <div v-if="recommendationFailures.rule" data-testid="rule-recommendation-error"
          role="alert" class="rounded-lg board-surface-danger px-3 py-2 text-xs leading-5 board-text-danger">
          <p class="font-semibold">{{ recommendationFailures.rule.message }}</p>
          <FailureDetails v-bind="recommendationFailures.rule" />
        </div>
        <p v-if="ruleRecommendationMessage && !isRecommendingRules && ruleRecommendationIsAppliedConfirmation" class="text-xs board-text-muted">
          {{ ruleRecommendationMessage }}
        </p>
        <div
          v-if="ruleRecommendationFilteredCount > 0 && !isRecommendingRules"
          class="rounded-lg border board-border-subtle board-bg-muted px-3 py-2 text-xs font-medium board-text-muted"
        >
          <p>{{ t('app.recommendationFilteredNotice', { count: ruleRecommendationFilteredCount }) }}</p>
          <ul
            v-if="ruleRecommendationFilteredItems.length"
            class="iot-scroll-region mt-1 max-h-32 list-disc space-y-0.5 pl-4 pr-1 font-normal leading-relaxed"
          >
            <li
              v-for="(item, index) in ruleRecommendationFilteredItems"
              :key="`${item.type || 'item'}-${item.index || index}-${item.reasonCode || item.reason}`"
            >
              {{ formatRecommendationFilteredItem(item) }}
            </li>
          </ul>
          <p v-else class="mt-1 font-normal board-text-muted">
            {{ t('app.recommendationFilteredNoDetails') }}
          </p>
        </div>
        <div
          v-if="ruleRecommendationAdjustedItems.length > 0 && !isRecommendingRules"
          data-testid="rule-adjusted-items"
          class="rounded-lg board-surface-warning px-3 py-2 text-xs font-medium board-text-warning"
        >
          <p>{{ t('app.recommendationAdjustedNotice', { count: ruleRecommendationAdjustedItems.length }) }}</p>
          <ul class="iot-scroll-region mt-1 max-h-36 list-disc space-y-0.5 pl-4 pr-1 font-normal leading-relaxed">
            <li
              v-for="(item, index) in ruleRecommendationAdjustedItems"
              :key="`${item.type || 'item'}-${item.index || index}-${item.reasonCode || item.reason}`"
            >
              {{ formatRecommendationAdjustmentItem(item, 'rule') }}
            </li>
          </ul>
        </div>

        <div v-if="!isRecommendingRules && ruleRecommendationRequested && ruleRecommendations.length === 0 && !recommendationFailures.rule" class="flex flex-col items-center justify-center py-10">
          <div class="w-16 h-16 board-bg-control rounded-full flex items-center justify-center mb-3">
            <span class="material-symbols-outlined board-text-muted text-3xl">psychology</span>
          </div>
          <p class="board-text-muted text-sm font-medium mt-2">{{ t('app.noRecommendationsAvailable') }}</p>
          <p class="board-text-muted text-xs mt-1 text-center px-4">{{ t('app.recommendationEmptyGuidance') }}</p>
        </div>

        <p v-if="!isRecommendingRules && ruleRecommendations.length" data-testid="rule-recommendations-unverified" class="text-xs board-text-muted">
          {{ t('app.recommendationsNotVerified') }}
        </p>

        <!-- Recommendations List -->
        <div v-if="!isRecommendingRules && ruleRecommendations.length && !recommendationFailures.rule" class="space-y-3">
          <!-- Header with count -->
          <div class="flex items-center justify-between px-1">
            <span class="text-xs font-medium board-text-muted">{{ t('app.recommendationsFound', { count: ruleRecommendations.length }) }}</span>
            <button 
              @click="fetchRuleRecommendations"
              :disabled="isSceneReplacementInProgress"
              class="text-xs board-text-warning hover:font-medium flex items-center gap-1"
            >
              <span class="material-symbols-outlined text-sm">refresh</span>
              {{ t('app.regenerateRecommendations') }}
            </button>
          </div>

          <div 
            v-for="(rec, index) in ruleRecommendations" 
            :key="index"
            class="board-card board-card--raised rounded-xl border board-border-subtle hover:transition-all overflow-hidden group"
          >
            <!-- Card Header -->
            <div class="p-3 pb-2">
              <div class="flex items-start justify-between gap-2">
                <div class="flex min-w-0 items-center gap-2">
                  <!-- Rule Icon -->
                  <div class="board-section-icon board-section-icon--lg">
                    <span class="material-symbols-outlined">smart_toy</span>
                  </div>
                  <div class="min-w-0">
                    <h4 class="board-section-title break-words">{{ rec.name }}</h4>
                  </div>
                </div>
              </div>
            </div>

            <!-- Reason -->
            <div class="px-3 pb-2">
              <p class="board-copy break-words board-text-strong" data-testid="rule-recommendation-description">{{ presentedRuleRecommendations[index].description }}</p>
              <p v-if="presentedRuleRecommendations[index].contentSource" class="mt-1 text-xs board-text-muted">{{ t('app.copyFrom') }} {{ presentedRuleRecommendations[index].contentSource }}</p>
              <details v-if="localizedRecommendationText(rec.reason)" class="mt-2 text-xs board-text-muted">
                <summary class="cursor-pointer py-2 font-semibold">{{ t('app.recommendationRationale') }}</summary>
                <p class="break-words">{{ localizedRecommendationText(rec.reason) }}</p>
              </details>
            </div>

            <!-- Details -->
            <div class="px-3 pb-2">
              <details class="text-xs">
                <summary class="flex cursor-pointer items-center gap-1 font-medium board-text-warning hover:board-text-strong">
                  <span class="material-symbols-outlined text-sm">info</span>
                  {{ t('app.viewDetails') }}
                </summary>
                <div class="mt-2 space-y-2 rounded-lg board-bg-muted p-2 board-text-strong">
                  <div v-if="rec.conditions && rec.conditions.length">
                    <div class="mb-1 font-semibold board-text-warning">{{ t('app.trigger') }}:</div>
                    <ul class="space-y-1">
                      <li v-for="(cond, condIndex) in rec.conditions" :key="condIndex" class="text-xs">
                        <span class="board-card font-mono rounded px-1 py-0.5">
                          {{ formatRecommendedRuleConditionDevice(cond) }}.{{ formatRecommendedRuleConditionAttribute(cond) }}
                        </span>
                        <template v-if="isValueBasedRuleRecommendationCondition(cond.targetType)">
                          <span class="mx-1">{{ formatRelationForDisplay(cond.relation) }}</span>
                          <span class="board-card font-mono rounded px-1 py-0.5">{{ formatRecommendedRuleConditionValue(cond) }}</span>
                        </template>
                        <span v-else class="ml-1 board-text-muted">{{ t('app.apiSignalFires') }}</span>
                      </li>
                    </ul>
                  </div>
                  <div v-if="rec.command">
                    <div class="mb-1 font-semibold board-text-warning">{{ t('app.action') }}:</div>
                    <div class="text-xs">
                      <span class="board-card font-mono rounded px-1 py-0.5">
                        {{ formatRecommendedRuleCommandDevice(rec.command) }}.{{ formatRecommendedRuleCommandAction(rec.command) }}
                      </span>
                      <span v-if="rec.command.contentDevice && rec.command.content" class="ml-2 board-text-muted">
                        ({{ t('app.copyFrom') }} {{ formatRecommendedRuleContentDevice(rec.command) }}<template v-if="rec.command.content">.{{ formatRecommendedRuleCommandContent(rec.command) }}<template v-if="rec.command.contentPrivacy"> ({{ t(`app.${rec.command.contentPrivacy}`) }})</template></template>)
                      </span>
                    </div>
                  </div>
                </div>
              </details>
            </div>

            <!-- Action Button -->
            <div class="px-3 pb-3">
              <div v-if="recommendationApplyFailures.rule[index]" :data-testid="`rule-recommendation-apply-failure-${index}`" role="alert" class="mb-2 text-sm">
                <p class="font-semibold board-text-danger">{{ recommendationApplyFailures.rule[index].message }}</p>
                <FailureDetails v-bind="recommendationApplyFailures.rule[index]" />
              </div>
              <button 
                @click="applyRecommendation(rec, index)"
                :disabled="isSceneReplacementInProgress || appliedRuleRecommendations.has(index) || applyingRuleRecommendations.has(index) || recommendationApplyFailures.rule[index]?.outcomeUnknown"
                :aria-busy="applyingRuleRecommendations.has(index)"
                :class="[
                  'w-full py-2 px-4 text-white text-sm font-medium rounded-lg transition-colors flex items-center justify-center gap-2',
                  appliedRuleRecommendations.has(index)
                    ? 'bg-[color:var(--success-fill)] cursor-default'
                    : applyingRuleRecommendations.has(index)
                      ? 'bg-[color:var(--accent-fill-disabled)] cursor-wait'
                      : 'bg-[color:var(--accent-fill)] hover:bg-[color:var(--accent-fill-hover)]'
                ]"
              >
                <span
                  class="material-symbols-outlined text-sm"
                  :class="{ 'animate-spin': applyingRuleRecommendations.has(index) }"
                >
                  {{ appliedRuleRecommendations.has(index) ? 'check' : applyingRuleRecommendations.has(index) ? 'progress_activity' : 'add' }}
                </span>
                {{ appliedRuleRecommendations.has(index)
                  ? t('app.addedToBoard')
                  : applyingRuleRecommendations.has(index)
                    ? t('app.applyingRecommendation')
                    : t('app.applyThisRule') }}
              </button>
            </div>
          </div>
        </div>
        <details
          v-if="ruleRecommendationMessage && !isRecommendingRules && !ruleRecommendationIsAppliedConfirmation"
          data-testid="rule-candidate-accounting"
          class="board-card rounded-lg border board-border-subtle px-3 py-2 text-xs leading-relaxed board-text-muted"
        >
          <summary class="cursor-pointer py-2 font-semibold">{{ t('app.recommendationGenerationDetails') }}</summary>
          <p class="mt-1">{{ ruleRecommendationMessage }}</p>
          {{ t('app.recommendationCandidateSummary', {
            raw: ruleRecommendationRawCandidateCount,
            inspected: ruleRecommendationInspectedCount,
            kept: ruleRecommendations.length,
            filtered: ruleRecommendationFilteredCount,
            truncated: ruleRecommendationTruncatedCount
          }) }}
        </details>
      </div>
    </div>

    <!-- Device Recommendation Panel -->
    <div
      v-if="showDeviceRecommendationPanel"
      :ref="setDeviceRecommendationPanelRef"
      data-testid="device-recommendation-panel"
      class="board-floating-panel board-recommendation-panel board-surface-panel fixed top-20 z-30 w-96 max-w-[calc(100vw-2rem)] rounded-2xl shadow-2xl border overflow-hidden"
      role="region"
      aria-labelledby="device-recommendation-panel-title"
      tabindex="-1"
      autofocus
      @keydown="handleDeviceRecommendationPanelKeydown"
    >
      <div class="relative overflow-hidden">
        <div class="board-panel-banner absolute inset-0"></div>
        <div class="board-tool-header relative">
          <div class="flex items-center gap-3">
            <div class="board-section-icon board-section-icon--lg">
              <span class="material-symbols-outlined text-xl">devices</span>
            </div>
            <div>
              <h3 id="device-recommendation-panel-title" class="board-panel-title">{{ t('app.deviceRecommendations') }}</h3>
            </div>
          </div>
          <HintTooltip :content="t('app.close')">
            <button 
              type="button"
              @click="closeDeviceRecommendationPanel"
              data-testid="close-device-recommendations"
              :aria-label="t('app.close')"
              class="board-panel-close"
            >
              <span class="material-symbols-outlined" aria-hidden="true">close</span>
            </button>
          </HintTooltip>
        </div>
      </div>

      <!-- Recommendation Content -->
      <div class="board-recommendation-panel__body iot-scroll-region p-3 space-y-3">
        <div class="rounded-lg border board-border-subtle board-bg-elevated p-2">
          <label class="board-form-label">
            {{ t('app.count') }}
            <input
              v-model.number="deviceRecommendationFilters.maxRecommendations"
              :disabled="isRecommendingDevices"
              type="number"
              min="1"
              max="10"
              class="board-field mt-1"
            />
          </label>
        </div>

        <div class="board-form-label rounded-lg border board-border-subtle board-bg-elevated p-2 block">
          <div class="flex items-center justify-between gap-3">
            <label for="device-recommendation-requirement" class="board-form-label">{{ t('app.recommendationScenario') }}</label>
            <InfoTooltip :text="t('app.recommendationBasisHint')" :label="t('app.showHelpFor', { topic: t('app.recommendationScenario') })" test-id="device-recommendation-help" />
          </div>
          <textarea
            id="device-recommendation-requirement"
            v-model.trim="deviceRecommendationFilters.userRequirement"
            :disabled="isRecommendingDevices"
            rows="3"
            :maxlength="AI_RECOMMENDATION_REQUIREMENT_MAX_LENGTH"
            :placeholder="t('app.recommendationScenarioPlaceholder')"
            class="board-field mt-1 resize-none leading-relaxed"
          ></textarea>
        </div>

        <button
          type="button"
          data-testid="generate-device-recommendations"
          @click="fetchDeviceRecommendations"
          :disabled="isSceneReplacementInProgress || isRecommendationRunningForAnother('device')"
          :class="[
            'board-panel-submit board-action-disarmed',
            isRecommendingDevices ? 'bg-[color:var(--danger-fill)] hover:bg-[color:var(--danger-fill-hover)]' : 'bg-[color:var(--accent-fill)] hover:bg-[color:var(--accent-fill-hover)]'
          ]"
        >
          <span class="material-symbols-outlined text-base">
            {{ isRecommendingDevices ? 'stop' : 'auto_awesome' }}
          </span>
          {{ isRecommendingDevices ? t('app.stopDeviceRecommendations') : t('app.generateRecommendations') }}
        </button>

        <RecommendationProgressStatus
          v-if="isRecommendingDevices"
          :elapsed-seconds="recommendationProgressElapsed"
          :stage="recommendationProgressStage"
          :template-count="deviceTemplates.length"
          :device-count="nodes.length"
          :rule-count="rules.length"
          :spec-count="specifications.length"
        />

        <div v-if="recommendationFailures.device" data-testid="device-recommendation-error"
          role="alert" class="rounded-lg board-surface-danger px-3 py-2 text-xs leading-5 board-text-danger">
          <p class="font-semibold">{{ recommendationFailures.device.message }}</p>
          <FailureDetails v-bind="recommendationFailures.device" />
        </div>
        <p v-if="deviceRecommendationMessage && !isRecommendingDevices && deviceRecommendationIsAppliedConfirmation" class="text-xs board-text-muted">
          {{ deviceRecommendationMessage }}
        </p>
        <div
          v-if="deviceRecommendationFilteredCount > 0 && !isRecommendingDevices"
          class="rounded-lg border board-border-subtle board-bg-muted px-3 py-2 text-xs font-medium board-text-muted"
        >
          <p>{{ t('app.recommendationFilteredNotice', { count: deviceRecommendationFilteredCount }) }}</p>
          <ul
            v-if="deviceRecommendationFilteredItems.length"
            class="iot-scroll-region mt-1 max-h-32 list-disc space-y-0.5 pl-4 pr-1 font-normal leading-relaxed"
          >
            <li
              v-for="(item, index) in deviceRecommendationFilteredItems"
              :key="`${item.type || 'item'}-${item.index || index}-${item.reasonCode || item.reason}`"
            >
              {{ formatRecommendationFilteredItem(item) }}
            </li>
          </ul>
          <p v-else class="mt-1 font-normal board-text-muted">
            {{ t('app.recommendationFilteredNoDetails') }}
          </p>
        </div>
        <div
          v-if="deviceRecommendationAdjustedItems.length > 0 && !isRecommendingDevices"
          data-testid="device-adjusted-items"
          class="rounded-lg board-surface-warning px-3 py-2 text-xs font-medium board-text-warning"
        >
          <p>{{ t('app.deviceRecommendationAdjustedNotice', { count: deviceRecommendationAdjustedItems.length }) }}</p>
          <ul class="iot-scroll-region mt-1 max-h-36 list-disc space-y-0.5 pl-4 pr-1 font-normal leading-relaxed">
            <li
              v-for="(item, index) in deviceRecommendationAdjustedItems"
              :key="`${item.type || 'device'}-${item.index || index}-${item.reasonCode || item.reason}`"
            >
              {{ formatRecommendationAdjustmentItem(item, 'device') }}
            </li>
          </ul>
        </div>

        <div v-if="!isRecommendingDevices && deviceRecommendationRequested && deviceRecommendations.length === 0 && !recommendationFailures.device" class="flex flex-col items-center justify-center py-10">
          <div class="w-16 h-16 board-bg-control rounded-full flex items-center justify-center mb-3">
            <span class="material-symbols-outlined board-text-muted text-3xl">devices</span>
          </div>
          <p class="board-text-muted text-sm font-medium mt-2">{{ t('app.noRecommendationsAvailable') }}</p>
          <p class="board-text-muted text-xs mt-1 text-center px-4">{{ t('app.recommendationEmptyGuidance') }}</p>
        </div>

        <p v-if="!isRecommendingDevices && deviceRecommendations.length" data-testid="device-recommendations-unverified" class="text-xs board-text-muted">
          {{ t('app.recommendationsNotVerified') }}
        </p>

        <!-- Recommendations List -->
        <div v-if="!isRecommendingDevices && deviceRecommendations.length && !recommendationFailures.device" class="space-y-3">
          <!-- Header with count -->
          <div class="flex items-center justify-between px-1">
            <span class="text-xs font-medium board-text-muted">{{ t('app.devicesRecommended', { count: deviceRecommendations.length }) }}</span>
            <button 
              @click="fetchDeviceRecommendations"
              :disabled="isSceneReplacementInProgress"
              class="text-xs board-text-info hover:font-medium flex items-center gap-1"
            >
              <span class="material-symbols-outlined text-sm">refresh</span>
              {{ t('app.regenerateRecommendations') }}
            </button>
          </div>

          <div
            v-for="(rec, index) in deviceRecommendations"
            :key="index"
            class="board-card board-card--raised rounded-xl border board-border-subtle hover:transition-all overflow-hidden group"
          >
            <!-- Card Header -->
            <div class="p-3 pb-2">
              <div class="flex items-start justify-between gap-2">
                <div class="flex min-w-0 items-center gap-2">
                  <!-- Device Icon -->
                  <div class="board-section-icon board-section-icon--lg">
                    <span class="material-symbols-outlined">device_hub</span>
                  </div>
                  <div class="min-w-0">
                    <h4 class="board-section-title truncate" :title="rec.suggestedLabel || rec.templateName">
                      {{ rec.suggestedLabel || rec.templateName }}
                    </h4>
                    <p class="text-xs font-medium board-text-info truncate" :title="rec.templateName">{{ rec.templateName }}</p>
                    <p v-if="rec.description?.trim()" class="text-xs board-text-muted break-words">{{ rec.description }}</p>
                  </div>
                </div>
              </div>
            </div>

            <!-- Reason -->
            <div class="px-3 pb-2">
              <div v-if="rec.intendedUse || rec.suggestedPlacement || rec.initialState || rec.currentStateTrust || rec.currentStatePrivacy" class="mb-2 flex flex-wrap gap-1">
                <span v-if="rec.intendedUse" class="rounded-full board-chip-info px-2 py-1 text-xs font-medium board-text-info">
                  {{ t('app.deviceRecommendationIntendedUse', { value: localizedRecommendationText(rec.intendedUse, t('app.recommendedBasedOnCurrentDevices')) }) }}
                </span>
                <span v-if="rec.suggestedPlacement" class="rounded-full board-bg-muted px-2 py-1 text-xs font-medium board-text-muted">
                  {{ t('app.deviceRecommendationSuggestedPlacement', { value: localizedRecommendationText(rec.suggestedPlacement, t('app.recommendedBasedOnCurrentDevices')) }) }}
                </span>
                <span v-if="rec.initialState" class="rounded-full board-bg-muted px-2 py-1 text-xs font-medium board-text-muted">
                  {{ t('app.deviceRecommendationInitialState', { value: formatRecommendedDeviceModelToken(rec, rec.initialState) }) }}
                </span>
                <span v-if="rec.currentStateTrust" class="rounded-full board-chip-success px-2 py-1 text-xs font-medium board-text-success">
                  {{ t('app.deviceRecommendationStateTrust', { value: t(`app.${rec.currentStateTrust}`) }) }}
                </span>
                <span v-if="rec.currentStatePrivacy" class="rounded-full board-chip-info px-2 py-1 text-xs font-medium board-text-info">
                  {{ t('app.deviceRecommendationStatePrivacy', { value: t(`app.${rec.currentStatePrivacy}`) }) }}
                </span>
              </div>
              <p v-if="rec.intendedUse || rec.suggestedPlacement" class="mb-2 text-xs leading-relaxed board-text-muted">
                {{ t('app.deviceRecommendationContextAdvisory') }}
              </p>
              <p
                v-if="recommendedDeviceEnvironmentAdditions(rec).length > 0"
                class="mb-2 rounded-lg board-surface-info px-2 py-1.5 text-xs leading-relaxed board-text-info"
              >
                {{ t('app.deviceCreationEnvironmentAdditionsPreview', { names: formatRecommendedDeviceEnvironmentAdditions(rec) }) }}
              </p>
              <div v-if="rec.initialVariables?.length || rec.initialPrivacies?.length" class="mb-2 space-y-1 text-xs board-text-muted">
                <div v-for="variable in rec.initialVariables || []" :key="`value-${variable.name}`" class="break-words">
                  {{ t('app.deviceRecommendationInitialVariable', {
                    name: formatRecommendedDeviceModelToken(rec, variable.name),
                    value: formatRecommendedDeviceModelToken(rec, variable.value),
                    trust: variable.trust ? t(`app.${variable.trust}`) : t('app.useTemplateDefault')
                  }) }}
                </div>
                <div v-for="privacy in rec.initialPrivacies || []" :key="`privacy-${privacy.name}`" class="break-words">
                  {{ t('app.deviceRecommendationInitialPrivacy', {
                    name: formatRecommendedDeviceModelToken(rec, privacy.name),
                    privacy: t(`app.${privacy.privacy}`)
                  }) }}
                </div>
              </div>
              <p class="text-xs board-text-muted break-words">{{ localizedRecommendationText(rec.reason, t('app.recommendedBasedOnCurrentDevices')) }}</p>
            </div>

            <!-- Action Button -->
            <div class="px-3 pb-3">
              <div v-if="recommendationApplyFailures.device[index]" :data-testid="`device-recommendation-apply-failure-${index}`" role="alert" class="mb-2 text-sm">
                <p class="font-semibold board-text-danger">{{ recommendationApplyFailures.device[index].message }}</p>
                <FailureDetails v-bind="recommendationApplyFailures.device[index]" />
              </div>
              <button 
                @click="applyDeviceRecommendation(rec, index)"
                :disabled="isSceneReplacementInProgress || appliedDeviceRecommendations.has(index) || applyingDeviceRecommendations.has(index) || recommendationApplyFailures.device[index]?.outcomeUnknown"
                :aria-busy="applyingDeviceRecommendations.has(index)"
                :class="[
                  'w-full py-2 px-4 text-white text-sm font-medium rounded-lg transition-colors flex items-center justify-center gap-2',
                  appliedDeviceRecommendations.has(index)
                    ? 'bg-[color:var(--success-fill)] cursor-default'
                    : applyingDeviceRecommendations.has(index)
                      ? 'bg-[color:var(--accent-fill-disabled)] cursor-wait'
                      : 'bg-[color:var(--accent-fill)] hover:bg-[color:var(--accent-fill-hover)]'
                ]"
              >
                <span
                  class="material-symbols-outlined text-sm"
                  :class="{ 'animate-spin': applyingDeviceRecommendations.has(index) }"
                >
                  {{ appliedDeviceRecommendations.has(index) ? 'check' : applyingDeviceRecommendations.has(index) ? 'progress_activity' : 'add' }}
                </span>
                {{ appliedDeviceRecommendations.has(index)
                  ? t('app.addedToBoard')
                  : applyingDeviceRecommendations.has(index)
                    ? t('app.applyingRecommendation')
                    : t('app.addThisDevice') }}
              </button>
            </div>
          </div>
        </div>
        <details
          v-if="deviceRecommendationMessage && !isRecommendingDevices && !deviceRecommendationIsAppliedConfirmation"
          data-testid="device-candidate-accounting"
          class="board-card rounded-lg border board-border-subtle px-3 py-2 text-xs leading-relaxed board-text-muted"
        >
          <summary class="cursor-pointer py-2 font-semibold">{{ t('app.recommendationGenerationDetails') }}</summary>
          <p class="mt-1">{{ deviceRecommendationMessage }}</p>
          {{ t('app.recommendationCandidateSummary', {
            raw: deviceRecommendationRawCandidateCount,
            inspected: deviceRecommendationInspectedCount,
            kept: deviceRecommendations.length,
            filtered: deviceRecommendationFilteredCount,
            truncated: deviceRecommendationTruncatedCount
          }) }}
        </details>
      </div>
    </div>

    <!-- Specification Recommendation Panel -->
    <div 
      v-if="showSpecRecommendationPanel"
      :ref="setSpecRecommendationPanelRef"
      data-testid="spec-recommendation-panel"
      class="board-floating-panel board-recommendation-panel board-surface-panel fixed top-20 z-30 w-96 max-w-[calc(100vw-2rem)] rounded-2xl shadow-2xl border overflow-hidden"
      role="region"
      aria-labelledby="spec-recommendation-panel-title"
      tabindex="-1"
      autofocus
      @keydown="handleSpecRecommendationPanelKeydown"
    >
      <div class="relative overflow-hidden">
        <div class="board-panel-banner absolute inset-0"></div>
        <div class="board-tool-header relative">
          <div class="flex items-center gap-3">
            <div class="board-section-icon board-section-icon--lg">
              <span class="material-symbols-outlined text-xl">policy</span>
            </div>
            <div>
              <h3 id="spec-recommendation-panel-title" class="board-panel-title">{{ t('app.specificationRecommendations') }}</h3>
            </div>
          </div>
          <HintTooltip :content="t('app.close')">
            <button 
              type="button"
              @click="closeSpecRecommendationPanel"
              data-testid="close-spec-recommendations"
              :aria-label="t('app.close')"
              class="board-panel-close"
            >
              <span class="material-symbols-outlined" aria-hidden="true">close</span>
            </button>
          </HintTooltip>
        </div>
      </div>

      <!-- Recommendation Content -->
      <div class="board-recommendation-panel__body iot-scroll-region p-3 space-y-3">
        <div class="rounded-lg border board-border-subtle board-bg-elevated p-2">
          <label class="board-form-label">
            {{ t('app.count') }}
            <input
              v-model.number="specRecommendationFilters.maxRecommendations"
              :disabled="isRecommendingSpecs"
              type="number"
              min="1"
              max="10"
              class="board-field mt-1"
            />
          </label>
        </div>

        <div class="board-form-label rounded-lg border board-border-subtle board-bg-elevated p-2 block">
          <div class="flex items-center justify-between gap-3">
            <label for="spec-recommendation-requirement" class="board-form-label">{{ t('app.recommendationScenario') }}</label>
            <InfoTooltip :text="t('app.recommendationBasisHint')" :label="t('app.showHelpFor', { topic: t('app.recommendationScenario') })" test-id="spec-recommendation-help" />
          </div>
          <textarea
            id="spec-recommendation-requirement"
            v-model.trim="specRecommendationFilters.userRequirement"
            :disabled="isRecommendingSpecs"
            rows="3"
            :maxlength="AI_RECOMMENDATION_REQUIREMENT_MAX_LENGTH"
            :placeholder="t('app.recommendationScenarioPlaceholder')"
            class="board-field mt-1 resize-none leading-relaxed"
          ></textarea>
        </div>

        <button
          type="button"
          data-testid="generate-spec-recommendations"
          @click="fetchSpecRecommendations"
          :disabled="isSceneReplacementInProgress || isRecommendationRunningForAnother('spec')"
          :class="[
            'board-panel-submit board-action-disarmed',
            isRecommendingSpecs ? 'bg-[color:var(--danger-fill)] hover:bg-[color:var(--danger-fill-hover)]' : 'bg-[color:var(--accent-fill)] hover:bg-[color:var(--accent-fill-hover)]'
          ]"
        >
          <span class="material-symbols-outlined text-base">
            {{ isRecommendingSpecs ? 'stop' : 'auto_awesome' }}
          </span>
          {{ isRecommendingSpecs ? t('app.stopSpecificationRecommendations') : t('app.generateRecommendations') }}
        </button>

        <RecommendationProgressStatus
          v-if="isRecommendingSpecs"
          :elapsed-seconds="recommendationProgressElapsed"
          :stage="recommendationProgressStage"
          :template-count="deviceTemplates.length"
          :device-count="nodes.length"
          :rule-count="rules.length"
          :spec-count="specifications.length"
        />

        <div v-if="recommendationFailures.spec" data-testid="spec-recommendation-error"
          role="alert" class="rounded-lg board-surface-danger px-3 py-2 text-xs leading-5 board-text-danger">
          <p class="font-semibold">{{ recommendationFailures.spec.message }}</p>
          <FailureDetails v-bind="recommendationFailures.spec" />
        </div>
        <p v-if="specRecommendationMessage && !isRecommendingSpecs && specRecommendationIsAppliedConfirmation" class="text-xs board-text-muted">
          {{ specRecommendationMessage }}
        </p>
        <!--
          The server-completed values, which this panel used to discard.
          Rule, device and scenario all show this notice; spec did not read the field at all. It is the panel that
          most needs it: `BoardStorageController:535` passes `requireAdjustments=false` for specifications alone, so
          this is exactly the case where the recommender may adjust a candidate silently. Applying a value the
          system completed for you, without being told it did, is the outcome the other three panels prevent.
        -->
        <div
          v-if="specRecommendationAdjustedItems.length > 0 && !isRecommendingSpecs"
          data-testid="spec-adjusted-items"
          class="rounded-lg board-surface-warning px-3 py-2 text-xs font-medium board-text-warning"
        >
          <p>{{ t('app.recommendationAdjustedNotice', { count: specRecommendationAdjustedItems.length }) }}</p>
          <ul class="iot-scroll-region mt-1 max-h-36 list-disc space-y-0.5 pl-4 pr-1 font-normal leading-relaxed">
            <li
              v-for="(item, index) in specRecommendationAdjustedItems"
              :key="`${item.type || 'item'}-${item.index || index}-${item.reasonCode || item.reason}`"
            >
              {{ formatRecommendationAdjustmentItem(item, 'spec') }}
            </li>
          </ul>
        </div>
        <div
          v-if="specRecommendationFilteredCount > 0 && !isRecommendingSpecs"
          class="rounded-lg border board-border-subtle board-bg-muted px-3 py-2 text-xs font-medium board-text-muted"
        >
          <p>{{ t('app.recommendationFilteredNotice', { count: specRecommendationFilteredCount }) }}</p>
          <ul
            v-if="specRecommendationFilteredItems.length"
            class="iot-scroll-region mt-1 max-h-32 list-disc space-y-0.5 pl-4 pr-1 font-normal leading-relaxed"
          >
            <li
              v-for="(item, index) in specRecommendationFilteredItems"
              :key="`${item.type || 'item'}-${item.index || index}-${item.reasonCode || item.reason}`"
            >
              {{ formatRecommendationFilteredItem(item) }}
            </li>
          </ul>
          <p v-else class="mt-1 font-normal board-text-muted">
            {{ t('app.recommendationFilteredNoDetails') }}
          </p>
        </div>

        <div v-if="!isRecommendingSpecs && specRecommendationRequested && specRecommendations.length === 0 && !recommendationFailures.spec" class="flex flex-col items-center justify-center py-10">
          <div class="w-16 h-16 board-bg-control rounded-full flex items-center justify-center mb-3">
            <span class="material-symbols-outlined board-text-muted text-3xl">policy</span>
          </div>
          <p class="board-text-muted text-sm font-medium mt-2">{{ t('app.noRecommendationsAvailable') }}</p>
          <p class="board-text-muted text-xs mt-1 text-center px-4">{{ t('app.recommendationEmptyGuidance') }}</p>
        </div>

        <p v-if="!isRecommendingSpecs && specRecommendations.length" data-testid="spec-recommendations-unverified" class="text-xs board-text-muted">
          {{ t('app.recommendationsNotVerified') }}
        </p>

        <!-- Recommendations List -->
        <div v-if="!isRecommendingSpecs && specRecommendations.length && !recommendationFailures.spec" class="space-y-3">
          <!-- Header with count -->
          <div class="flex items-center justify-between px-1">
            <span class="text-xs font-medium board-text-muted">{{ t('app.specificationsRecommended', { count: specRecommendations.length }) }}</span>
            <button 
              @click="fetchSpecRecommendations"
              :disabled="isSceneReplacementInProgress"
              class="text-xs board-text-danger hover:font-medium flex items-center gap-1"
            >
              <span class="material-symbols-outlined text-sm">refresh</span>
              {{ t('app.regenerateRecommendations') }}
            </button>
          </div>

          <div 
            v-for="(rec, index) in specRecommendations" 
            :key="index"
            class="board-card board-card--raised rounded-xl border board-border-subtle hover:transition-all overflow-hidden group"
          >
            <!-- Card Header -->
            <div class="p-3 pb-2">
              <div class="flex items-start justify-between gap-2">
                <div class="flex min-w-0 items-center gap-2">
                  <!-- Specification Icon -->
                  <div class="board-section-icon board-section-icon--lg">
                    <span class="material-symbols-outlined">policy</span>
                  </div>
                  <div class="min-w-0">
                    <h4 class="board-section-title break-words">
                      {{ recommendedSpecTemplateLabel(rec.templateId) }}
                    </h4>
                  </div>
                </div>
              </div>
            </div>

            <div class="px-3 pb-2">
              <p class="board-copy break-words board-text-strong" data-testid="spec-recommendation-description">{{ presentedSpecRecommendations[index] }}</p>
              <details v-if="localizedRecommendationText(rec.rationale)" class="mt-2 text-xs board-text-muted">
                <summary class="cursor-pointer py-2 font-semibold">{{ t('app.recommendationRationale') }}</summary>
                <p class="break-words">{{ localizedRecommendationText(rec.rationale) }}</p>
              </details>
            </div>

            <!-- Details -->
            <div class="px-3 pb-2">
              <details class="text-xs">
                <summary class="flex cursor-pointer items-center gap-1 font-medium board-text-danger hover:board-text-strong">
                  <span class="material-symbols-outlined text-sm">info</span>
                  {{ t('app.viewDetails') }}
                </summary>
                <div class="mt-2 space-y-2 rounded-lg board-bg-muted p-2 board-text-strong">
                  <div v-if="rec.aConditions && rec.aConditions.length">
                    <div class="mb-1 font-semibold board-text-danger">{{ t('app.alwaysConditions') }}:</div>
                    <ul class="space-y-1">
                      <li v-for="(cond, condIndex) in rec.aConditions" :key="condIndex" class="text-xs">
                        <span class="board-card font-mono rounded px-1 py-0.5">
                          {{ formatRecommendedSpecConditionTarget(cond) }}
                        </span>
                        <span class="mx-1">{{ formatRelationForDisplay(cond.relation) }}</span>
                        <span class="board-card font-mono rounded px-1 py-0.5">{{ formatRecommendedSpecConditionValue(cond) }}</span>
                      </li>
                    </ul>
                  </div>
                  <div v-if="rec.ifConditions && rec.ifConditions.length">
                    <div class="mb-1 font-semibold board-text-danger">{{ t('app.ifConditions') }}:</div>
                    <ul class="space-y-1">
                      <li v-for="(cond, condIndex) in rec.ifConditions" :key="condIndex" class="text-xs">
                        <span class="board-card font-mono rounded px-1 py-0.5">
                          {{ formatRecommendedSpecConditionTarget(cond) }}
                        </span>
                        <span class="mx-1">{{ formatRelationForDisplay(cond.relation) }}</span>
                        <span class="board-card font-mono rounded px-1 py-0.5">{{ formatRecommendedSpecConditionValue(cond) }}</span>
                      </li>
                    </ul>
                  </div>
                  <div v-if="rec.thenConditions && rec.thenConditions.length">
                    <div class="mb-1 font-semibold board-text-danger">{{ t('app.thenConditions') }}:</div>
                    <ul class="space-y-1">
                      <li v-for="(cond, condIndex) in rec.thenConditions" :key="condIndex" class="text-xs">
                        <span class="board-card font-mono rounded px-1 py-0.5">
                          {{ formatRecommendedSpecConditionTarget(cond) }}
                        </span>
                        <span class="mx-1">{{ formatRelationForDisplay(cond.relation) }}</span>
                        <span class="board-card font-mono rounded px-1 py-0.5">{{ formatRecommendedSpecConditionValue(cond) }}</span>
                      </li>
                    </ul>
                  </div>
                </div>
              </details>
            </div>

            <!-- Action Button -->
            <div class="px-3 pb-3">
              <div v-if="recommendationApplyFailures.spec[index]" :data-testid="`spec-recommendation-apply-failure-${index}`" role="alert" class="mb-2 text-sm">
                <p class="font-semibold board-text-danger">{{ recommendationApplyFailures.spec[index].message }}</p>
                <FailureDetails v-bind="recommendationApplyFailures.spec[index]" />
              </div>
              <button 
                @click="applySpecRecommendation(rec, index)"
                :disabled="isSceneReplacementInProgress || appliedSpecRecommendations.has(index) || applyingSpecRecommendations.has(index) || recommendationApplyFailures.spec[index]?.outcomeUnknown"
                :aria-busy="applyingSpecRecommendations.has(index)"
                :class="[
                  'w-full py-2 px-4 text-white text-sm font-medium rounded-lg transition-colors flex items-center justify-center gap-2',
                  appliedSpecRecommendations.has(index)
                    ? 'bg-[color:var(--success-fill)] cursor-default'
                    : applyingSpecRecommendations.has(index)
                      ? 'bg-[color:var(--accent-fill-disabled)] cursor-wait'
                      : 'bg-[color:var(--accent-fill)] hover:bg-[color:var(--accent-fill-hover)]'
                ]"
              >
                <span
                  class="material-symbols-outlined text-sm"
                  :class="{ 'animate-spin': applyingSpecRecommendations.has(index) }"
                >
                  {{ appliedSpecRecommendations.has(index) ? 'check' : applyingSpecRecommendations.has(index) ? 'progress_activity' : 'add' }}
                </span>
                {{ appliedSpecRecommendations.has(index)
                  ? t('app.addedToBoard')
                  : applyingSpecRecommendations.has(index)
                    ? t('app.applyingRecommendation')
                    : t('app.addThisSpecification') }}
              </button>
            </div>
          </div>
        </div>
        <details
          v-if="specRecommendationMessage && !isRecommendingSpecs && !specRecommendationIsAppliedConfirmation"
          data-testid="spec-candidate-accounting"
          class="board-card rounded-lg border board-border-subtle px-3 py-2 text-xs leading-relaxed board-text-muted"
        >
          <summary class="cursor-pointer py-2 font-semibold">{{ t('app.recommendationGenerationDetails') }}</summary>
          <p class="mt-1">{{ specRecommendationMessage }}</p>
          {{ t('app.recommendationCandidateSummary', {
            raw: specRecommendationRawCandidateCount,
            inspected: specRecommendationInspectedCount,
            kept: specRecommendations.length,
            filtered: specRecommendationFilteredCount,
            truncated: specRecommendationTruncatedCount
          }) }}
        </details>
      </div>
    </div>

    <!-- Simulation Panel (Appears when clicking simulation button) -->
    <div 
      v-if="showSimulationPanel"
      :ref="setSimulationPanelRef"
      data-testid="simulation-panel"
      class="board-floating-panel board-run-panel board-surface-panel fixed top-20 z-30 max-w-[calc(100vw-2rem)] rounded-2xl shadow-2xl border overflow-hidden"
      role="region"
      aria-labelledby="simulation-panel-title"
      tabindex="-1"
      autofocus
      @keydown="handleSimulationPanelKeydown"
    >
      <div class="relative overflow-hidden">
        <div class="board-panel-banner absolute inset-0"></div>
        <div class="board-tool-header relative">
          <div class="flex items-center gap-3">
            <div class="board-section-icon board-section-icon--lg">
              <span class="material-symbols-outlined text-xl">play_circle</span>
            </div>
            <div>
              <h3 id="simulation-panel-title" class="board-panel-title">{{ t('app.simulationTitle') }}</h3>
            </div>
          </div>
          <HintTooltip :content="t('app.close')">
            <button 
              type="button"
              @click="closeSimulationPanel"
              data-testid="close-simulation-panel"
              :aria-label="t('app.close')"
              class="board-panel-close"
            >
              <span class="material-symbols-outlined" aria-hidden="true">close</span>
            </button>
          </HintTooltip>
        </div>
      </div>
      <!-- Simulation Content -->
      <div class="board-run-panel__body iot-scroll-region p-3 space-y-3">
        <BackgroundTaskSummary v-if="runningSimulationTask" kind="simulation" :task="runningSimulationTask" />
        <template v-if="!isSimulating || !asyncSimulationActive">
        <!-- Steps -->
        <div class="board-card p-3 rounded-xl">
          <div class="mb-2 flex items-center justify-between gap-3">
            <div class="flex min-w-0 items-center gap-3">
              <label for="simulation-steps-input" class="board-form-label">{{ t('app.simulationSteps') }}</label>
              <InfoTooltip :text="t('app.simulationStepsHint')"
                :label="t('app.showHelpFor', { topic: t('app.simulationSteps') })"
                test-id="simulation-steps-help" />
            </div>
            <input
              id="simulation-steps-input"
              v-model.number="simulationForm.steps"
              data-testid="simulation-steps-input"
              :disabled="isSimulating"
              type="number"
              :min="SIMULATION_STEPS_MIN"
              :max="SIMULATION_STEPS_MAX"
              step="1"
              inputmode="numeric"
              class="board-field w-20 text-center"
              @change="commitSimulationStepsInput"
              @blur="commitSimulationStepsInput"
              @keydown.enter.prevent="commitSimulationStepsInput"
            />
          </div>
          <div class="flex items-center gap-3">
            <HintTooltip :content="t('app.decreaseSimulationSteps')">
              <button
                type="button"
                data-testid="simulation-steps-decrease"
                class="board-panel-icon-action"
                :disabled="isSimulating || normalizeSimulationStepsControlValue(simulationForm.steps) <= SIMULATION_STEPS_MIN"
                :aria-label="t('app.decreaseSimulationSteps')"
                @click="adjustSimulationSteps(-1)"
              >
                <span class="material-symbols-outlined text-lg" aria-hidden="true">remove</span>
              </button>
            </HintTooltip>
            <input
              v-model.number="simulationForm.steps"
              data-testid="simulation-steps-range"
              :aria-label="t('app.simulationSteps')"
              :disabled="isSimulating"
              type="range"
              :min="SIMULATION_STEPS_MIN"
              :max="SIMULATION_STEPS_MAX"
              step="1"
              class="board-run-range flex-1"
            />
            <HintTooltip :content="t('app.increaseSimulationSteps')">
              <button
                type="button"
                data-testid="simulation-steps-increase"
                class="board-panel-icon-action"
                :disabled="isSimulating || normalizeSimulationStepsControlValue(simulationForm.steps) >= SIMULATION_STEPS_MAX"
                :aria-label="t('app.increaseSimulationSteps')"
                @click="adjustSimulationSteps(1)"
              >
                <span class="material-symbols-outlined text-lg" aria-hidden="true">add</span>
              </button>
            </HintTooltip>
          </div>
        </div>

        <!-- Attack Mode -->
        <div class="board-card p-3 rounded-xl">
          <div class="flex items-center justify-between gap-3">
            <div class="flex items-center gap-3">
              <div class="w-8 h-8 shrink-0 board-chip-danger rounded-lg flex items-center justify-center">
                <span class="material-symbols-outlined board-text-danger text-lg">warning</span>
              </div>
              <label class="board-form-label">
                {{ t('app.attackMode') }}
              </label>
            </div>
            <ToggleSwitch
              :checked="simulationForm.isAttack"
              :label="t('app.attackMode')"
              tone="adversarial"
              test-id="simulation-attack-toggle"
              :disabled="isSimulating || (!simulationForm.isAttack && !hasModeledAttackEffect)"
              :describedby-id="!hasModeledAttackEffect ? 'simulation-attack-unavailable' : undefined"
              @change="setSimulationAttackEnabled"
            />
          </div>
          <p
            v-if="!hasModeledAttackEffect"
            id="simulation-attack-unavailable"
            data-testid="simulation-attack-unavailable"
            class="mt-2 text-xs leading-4 board-text-warning"
          >
            {{ t('app.attackNoModeledEffect') }}
          </p>
        </div>

        <div v-if="simulationForm.isAttack && hasModeledAttackEffect" class="space-y-2 rounded-lg board-surface-danger p-3">
          <p class="text-xs leading-4 board-text-danger">{{ t('app.simulationAttackExactHint') }}</p>
          <div class="space-y-1.5" data-testid="simulation-attack-points"
            role="group" :aria-label="t('app.attackModeExact')"
            :aria-describedby="simulationAttackConfigurationError ? 'simulation-attack-points-invalid' : undefined">
            <label
              v-for="point in boardAttackSurface.points"
              :key="point.key"
              class="board-form-label board-attack-point"
            >
              <input
                type="checkbox"
                :checked="simulationForm.selectedAttackPointKeys.includes(point.key)"
                :disabled="isSimulating || !point.selectable"
                :data-testid="`simulation-attack-point-${point.key}`"
                @change="toggleAttackPoint(simulationForm, point.key)"
              />
              <span class="material-symbols-outlined text-base board-text-danger" aria-hidden="true">
                {{ point.kind === 'DEVICE' ? 'memory' : 'conversion_path' }}
              </span>
              <span class="min-w-0 flex-1 break-words">{{ point.label }}</span>
              <span class="shrink-0 board-form-label">
                {{ point.kind === 'DEVICE' ? t('app.device') : t('app.automationLink') }}
              </span>
            </label>
          </div>
          <p v-if="simulationAttackConfigurationError" id="simulation-attack-points-invalid" class="text-xs font-semibold leading-4 board-text-danger" data-testid="simulation-attack-points-invalid">
            {{ simulationAttackConfigurationError }}
          </p>
        </div>

        <!-- Privacy Analysis -->
        <div class="board-card p-3 rounded-xl">
          <div class="flex items-center justify-between gap-3">
            <div class="flex min-w-0 items-center gap-3">
              <div class="w-8 h-8 shrink-0 board-chip-info rounded-lg flex items-center justify-center">
                <span class="material-symbols-outlined board-text-info text-lg">security</span>
              </div>
              <label class="board-form-label">
                {{ t('app.privacyAnalysis') }}
              </label>
              <InfoTooltip
                :text="t('app.privacyModelHint')"
                :label="t('app.showHelpFor', { topic: t('app.privacyAnalysis') })"
                placement="left"
                tone="sensitivity"
                test-id="simulation-privacy-help"
              />
            </div>
            <ToggleSwitch
              :checked="simulationForm.enablePrivacy"
              :label="t('app.privacyAnalysis')"
              tone="sensitivity"
              test-id="simulation-privacy-toggle"
              :disabled="isSimulating"
              @change="value => simulationForm.enablePrivacy = value"
            />
          </div>
        </div>

        <!-- Run Mode -->
        <div class="board-card p-3 rounded-xl">
          <div class="flex items-center gap-3 mb-2">
            <div class="w-8 h-8 shrink-0 board-chip-info rounded-lg flex items-center justify-center">
              <span class="material-symbols-outlined board-text-info text-lg">schedule</span>
            </div>
            <label class="board-form-label">
              {{ t('app.runMode') }}
            </label>
          </div>
          <div class="grid grid-cols-2 gap-1 rounded-lg board-bg-control p-1">
            <button
              type="button"
              :disabled="isSimulating"
              @click="simulationForm.isAsync = false"
              data-testid="simulation-mode-sync"
              :aria-pressed="!simulationForm.isAsync"
              class="board-run-mode"
            >
              {{ t('app.waitForResult') }}
            </button>
            <button
              type="button"
              :disabled="isSimulating"
              @click="simulationForm.isAsync = true"
              data-testid="simulation-mode-async"
              :aria-pressed="simulationForm.isAsync"
              class="board-run-mode"
            >
              {{ t('app.backgroundTask') }}
            </button>
          </div>
          <p v-if="simulationForm.isAsync" class="mt-2 text-xs leading-5 board-text-muted">
            {{ t('app.asyncSimulationModeHint') }}
          </p>
        </div>

        <!-- Save History -->
        <div v-if="!simulationForm.isAsync" class="board-card p-3 rounded-xl">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-3">
              <div class="w-8 h-8 shrink-0 board-chip-info rounded-lg flex items-center justify-center">
                <span class="material-symbols-outlined board-text-info text-lg">history</span>
              </div>
              <label class="board-form-label">
                {{ t('app.saveToHistory') }}
              </label>
            </div>
            <ToggleSwitch
              :checked="simulationForm.saveToHistory"
              :label="t('app.saveToHistory')"
              test-id="simulation-save-history"
              :disabled="isSimulating"
              @change="value => simulationForm.saveToHistory = value"
            />
          </div>
        </div>

        </template>
        <!-- Async Progress (visible when async simulation is running) -->
        <div v-if="isSimulating && asyncSimulationActive" class="space-y-1">
          <div class="flex items-center justify-between text-xs">
            <span class="board-text-info font-medium">{{ t('app.progress') }}</span>
            <div v-if="asyncSimulationTask.taskId" class="flex items-center gap-2">
              <span class="board-text-info">{{ asyncSimulationTask.progress }}%</span>
              <HintTooltip :content="t('app.cancelSimulationTask')">
                <button
                  type="button"
                  class="board-panel-icon-action"
                  :disabled="cancellingSimulationTask"
                  :aria-label="t('app.cancelSimulationTask')"
                  @click="cancelAsyncSimulation"
                >
                  <span class="material-symbols-outlined text-sm" aria-hidden="true">{{ cancellingSimulationTask ? 'hourglass_empty' : 'cancel' }}</span>
                </button>
              </HintTooltip>
            </div>
          </div>
          <div class="w-full h-2 board-chip-info rounded-full overflow-hidden">
            <div 
              class="h-full bg-[color:var(--success)] transition-all duration-300"
              :class="{ 'animate-pulse': !asyncSimulationTask.taskId }"
              :style="{ width: asyncSimulationTask.taskId ? `${asyncSimulationTask.progress}%` : '35%' }"
            ></div>
          </div>
          <div class="text-xs board-text-info text-center">{{ formatWatchedTaskStatus(asyncSimulationTask.statusKey, cancellingSimulationTask) }}</div>
        </div>

      </div>
      <div class="board-run-panel__footer">
        <!-- Simulate Button -->
        <button
          @click="runSimulation"
          data-testid="run-simulation"
          :disabled="isSimulating || Boolean(simulationRunBlockedReason)"
          :aria-describedby="simulationRunBlockedDescriptionId"
          class="board-panel-submit"
        >
          <template v-if="!isBoardDataReady && failedBoardDataKeys.length === 0">
            <span class="material-symbols-outlined text-sm animate-spin">sync</span>
            {{ t('app.loading') }}
          </template>
          <template v-else-if="isSimulating">
            <span class="material-symbols-outlined text-sm animate-spin">sync</span>
            {{ simulationForm.isAsync ? t('app.runningAsync') : t('app.running') }}
          </template>
          <template v-else>
            <span class="material-symbols-outlined text-sm">play_arrow</span>
            {{ simulationForm.isAsync ? t('app.createSimulationTask') : t('app.runSimulationNow') }}
          </template>
        </button>
        <p
          v-if="simulationRunBlockedDescriptionId === 'simulation-run-blocked-reason'"
          id="simulation-run-blocked-reason"
          data-testid="simulation-run-blocked-reason"
          class="text-xs leading-5 board-text-warning"
          role="status"
        >
          {{ simulationRunBlockedReason }}
        </p>
      </div>
    </div>

    <!-- Floating panels -->
    <div>

    </div>

    <DeviceDialog
        v-if="dialogVisible"
        ref="deviceDialogRef"
        :visible="dialogVisible"
        :device-name="dialogMeta.deviceName"
        :label="dialogMeta.label"
        :node-id="dialogMeta.nodeId"
        :manifest="dialogMeta.manifest"
        :nodes="nodes"
        :device-templates="deviceTemplates"
        :specs="dialogMeta.specs"
        :runtime-saving="deviceRuntimeSaving"
        :delete-loading="deletePreviewLoading && deletePreviewNodeId === dialogMeta.nodeId"
        :suspended="deleteConfirmDialogVisible"
        @update:visible="handleDeviceDialogVisibility"
        @rename="handleDialogRename"
        @delete="handleDialogDelete"
        @save-runtime="handleDeviceRuntimeSave"
    />

    <!-- Context Menu for Node Right Click -->
    <div
      v-if="contextMenu.visible"
      ref="contextMenuRef"
      class="board-context-menu fixed z-50 border shadow-lg py-2 min-w-48"
      :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
      role="menu"
      :aria-label="t('app.deviceContextMenuLabel', { name: contextMenu.node?.label || '' })"
      @click.stop
      @keydown="handleContextMenuKeydown"
    >
      <div class="board-context-menu__title px-3 py-2 text-xs font-semibold border-b">
        {{ contextMenu.node?.label }}
      </div>
      <button
        @click="renameDevice"
        class="board-context-menu__item w-full px-3 py-2 text-left text-sm flex items-center gap-2"
        role="menuitem"
      >
        <span class="material-icons-round text-base" aria-hidden="true">edit</span>
        {{ t('app.rename') }}
      </button>
      <button
        @click="viewDeviceDetails"
        class="board-context-menu__item w-full px-3 py-2 text-left text-sm flex items-center gap-2"
        role="menuitem"
      >
        <span class="material-icons-round text-base" aria-hidden="true">visibility</span>
        {{ t('app.viewDetails') }}
      </button>
      <div class="board-context-menu__divider border-t my-1" role="separator"></div>
      <button
        @click="deleteDevice"
        class="board-context-menu__item board-context-menu__item--danger w-full px-3 py-2 text-left text-sm flex items-center gap-2"
        role="menuitem"
      >
        <span class="material-icons-round text-base" aria-hidden="true">delete</span>
        {{ t('app.deleteDevice') }}
      </button>
    </div>

    <!-- Click outside to close context menu -->
    <div
      v-if="contextMenu.visible"
      class="fixed inset-0 z-40"
      aria-hidden="true"
      @click="closeContextMenu()"
      @contextmenu.prevent="closeContextMenu()"
    ></div>
    </div>


    <!-- Custom Rename Dialog -->
    <Teleport to="body">
      <div
        v-if="renameDialogVisible"
        class="iot-dialog-overlay"
        @click.self="cancelRename"
        @keydown="handleRenameDialogKeydown"
      >
        <div
          :ref="setRenameDialogRef"
          class="iot-dialog iot-dialog--sm"
          role="dialog"
          aria-modal="true"
          aria-labelledby="rename-device-dialog-title"
          tabindex="-1"
          @click.stop
        >
          <div class="iot-dialog__header">
            <div class="iot-dialog__icon">
              <span class="material-symbols-outlined" aria-hidden="true">edit</span>
            </div>
            <div class="iot-dialog__heading">
              <h3 id="rename-device-dialog-title" class="iot-dialog__title">{{ t('app.renameDevice') }}</h3>
            </div>
          </div>
          <div class="iot-dialog__body">
            <label for="rename-device-name" class="board-form-label mb-2 block">{{ t('app.deviceName') }}</label>
            <input
              id="rename-device-name"
              data-testid="rename-device-name"
              v-model="renameDialogData.newName"
              @keyup.enter="confirmRename"
              :disabled="renameDialogSubmitting"
              :aria-invalid="renameNameConflict ? 'true' : undefined"
              :aria-describedby="renameNameBlockedReason ? 'rename-device-name-hint' : undefined"
              class="board-field"
              :placeholder="t('app.enterDeviceName')"
            />
            <p v-if="renameNameBlockedReason" id="rename-device-name-hint" data-testid="rename-device-name-hint"
              role="status" class="mt-2 text-xs" :class="renameNameConflict ? 'board-text-danger' : 'board-text-muted'">{{ renameNameBlockedReason }}</p>
          </div>
          <div class="iot-dialog__footer">
            <button
              @click="cancelRename"
              :disabled="renameDialogSubmitting"
              class="iot-dialog-btn iot-dialog-btn--ghost"
            >
              {{ t('app.cancel') }}
            </button>
            <button
              @click="confirmRename"
              :disabled="renameDialogSubmitting || Boolean(renameNameBlockedReason)"
              :aria-describedby="renameNameBlockedReason ? 'rename-device-name-hint' : undefined"
              :aria-busy="renameDialogSubmitting"
              class="iot-dialog-btn iot-dialog-btn--primary"
            >
              <span v-if="renameDialogSubmitting" class="iot-dialog-btn__spinner" aria-hidden="true"></span>
              {{ renameDialogSubmitting ? t('app.saving') : t('app.confirm') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- Custom Delete Confirmation Dialog -->
    <Teleport to="body">
      <div
        v-if="deleteConfirmDialogVisible"
        class="iot-dialog-overlay"
        @click.self="cancelDelete"
        @keydown="handleDeleteConfirmDialogKeydown"
      >
        <div
          :ref="setDeleteConfirmDialogRef"
          class="iot-dialog iot-dialog--md iot-dialog--danger"
          role="dialog"
          aria-modal="true"
          aria-labelledby="delete-device-dialog-title"
          aria-describedby="delete-device-dialog-description"
          :aria-busy="deletePreviewLoading || deleteConfirmSubmitting"
          tabindex="-1"
          @click.stop
        >
          <div class="iot-dialog__header">
            <div class="iot-dialog__icon">
              <span class="material-symbols-outlined" aria-hidden="true">delete</span>
            </div>
            <div class="iot-dialog__heading">
              <h3 id="delete-device-dialog-title" class="iot-dialog__title">{{ t('app.deleteDeviceTitle') }}</h3>
              <p
                id="delete-device-dialog-description"
                class="iot-dialog__subtitle break-words"
                :title="deleteConfirmDialogData.node?.label || ''"
              >
                {{ t('app.deleteDeviceConfirmMessage', { name: deleteConfirmDialogData.node?.label || '' }) }}
              </p>
            </div>
          </div>

          <div class="iot-dialog__body iot-scroll-region">
            <div
              v-if="deletePreviewLoading"
              class="mb-3 flex items-center gap-3 rounded-lg board-surface-info px-4 py-3 text-sm board-text-info"
              role="status"
              aria-live="polite"
            >
              <span class="h-4 w-4 shrink-0 animate-spin rounded-full border-2 board-border-progress border-t-[color:var(--accent)]" aria-hidden="true"></span>
              {{ t('app.deviceDeletionPreviewLoading') }}
            </div>

            <div v-if="deleteConfirmDialogData.hasRelations" class="board-surface-warning rounded-lg p-4">
              <div class="flex items-start">
                <span class="material-symbols-outlined board-text-warning mr-2 mt-0.5" aria-hidden="true">info</span>
                <div class="min-w-0">
                  <p class="text-sm font-medium board-text-warning mb-1">{{ t('app.deviceDeleteConsequences') }}</p>
                  <div class="text-xs board-text-warning space-y-1">
                    <div v-if="deleteConfirmDialogData.relationCount.rules > 0">
                      • {{ t('app.relatedRulesWillBeDeleted', { count: deleteConfirmDialogData.relationCount.rules }) }}
                      <ul class="mt-1 ml-4 list-disc break-words">
                        <li v-for="(label, index) in deleteConfirmDialogData.relatedRules" :key="`delete-rule-${index}`">
                          {{ label }}
                        </li>
                      </ul>
                    </div>
                    <div v-if="deleteConfirmDialogData.relationCount.specs > 0">
                      • {{ t('app.relatedSpecsWillBeDeleted', { count: deleteConfirmDialogData.relationCount.specs }) }}
                      <ul class="mt-1 ml-4 list-disc break-words">
                        <li v-for="(label, index) in deleteConfirmDialogData.relatedSpecs" :key="`delete-spec-${index}`">
                          {{ label }}
                        </li>
                      </ul>
                    </div>
                    <div v-if="deleteConfirmDialogData.environmentChanges.length > 0">
                      • {{ t('app.environmentVariablesWillChange', { count: deleteConfirmDialogData.environmentChanges.length }) }}
                      <ul class="mt-1 ml-4 list-disc break-words">
                        <li
                          v-for="change in deleteConfirmDialogData.environmentChanges"
                          :key="`delete-environment-${change.changeType}-${change.name}`"
                        >
                          {{ formatEnvironmentChange(change) }}
                        </li>
                      </ul>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div class="iot-dialog__footer">
            <button
              type="button"
              @click="cancelDelete"
              :disabled="deleteConfirmSubmitting"
              class="iot-dialog-btn iot-dialog-btn--ghost"
            >
              {{ t('app.cancel') }}
            </button>
            <button
              type="button"
              @click="confirmDelete"
              :disabled="deletePreviewLoading || deleteConfirmSubmitting || !deleteConfirmDialogData.impactToken"
              :aria-busy="deleteConfirmSubmitting"
              class="iot-dialog-btn iot-dialog-btn--danger"
            >
              <span v-if="deleteConfirmSubmitting" class="iot-dialog-btn__spinner" aria-hidden="true"></span>
              {{ deleteConfirmSubmitting ? t('app.deleting') : t('app.deleteDevice') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>

  <FuzzingResultDialog
    v-if="showFuzzingResultDialog"
    :visible="showFuzzingResultDialog"
    :run="fuzzingResult"
    :loading="fuzzingResultLoading"
    :error="fuzzingErrorMessage"
    :action-locked="historyActionLocked"
    :board-comparison="fuzzingBoardComparison"
    @ready="closeHistoryPanel(false)"
    @close="dismissFuzzingResult"
    @replay="openFuzzingFinding"
    @verify="openFormalVerificationForFuzzFinding"
    @verify-current-board="openFormalVerificationForCurrentBoard"
    @reuse-settings="reuseFuzzingSettings"
  />

  <div v-if="simulationError" data-testid="simulation-result-dialog" class="iot-dialog-overlay"
    @click="dismissSimulationResultDialog()" @keydown="handleSimulationResultDialogKeydown">
    <div :ref="setSimulationResultDialogRef" class="iot-dialog iot-dialog--md iot-dialog--danger"
      role="dialog" aria-modal="true" aria-labelledby="simulation-result-dialog-title" tabindex="-1" @click.stop>
      <header class="iot-dialog__header">
        <div class="iot-dialog__icon"><span class="material-symbols-outlined" aria-hidden="true">error</span></div>
        <div class="iot-dialog__heading"><h3 id="simulation-result-dialog-title" class="iot-dialog__title">{{ runFailureTitle('simulation', simulationError) }}</h3></div>
        <button type="button" data-testid="close-simulation-result" class="iot-dialog__close" :aria-label="t('app.close')" @click="dismissSimulationResultDialog()">
          <span class="material-symbols-outlined" aria-hidden="true">close</span>
        </button>
      </header>
      <div class="iot-dialog__body iot-scroll-region">
        <div class="board-run-notice board-surface-danger">
          <p v-if="runFailureDiagnostics.simulation?.message !== runFailureTitle('simulation', simulationError)">{{ runFailureDiagnostics.simulation?.message }}</p>
          <FailureDetails v-if="runFailureDiagnostics.simulation" v-bind="runFailureDiagnostics.simulation" />
        </div>
      </div>
      <footer class="iot-dialog__footer"><button type="button" class="iot-dialog-btn iot-dialog-btn--ghost" @click="dismissSimulationResultDialog()">{{ t('app.close') }}</button></footer>
    </div>
  </div>

  <!-- Verification Result Dialog -->
  <div
    v-if="showResultDialog"
    data-testid="verification-result-dialog"
    class="iot-dialog-overlay"
    @click="dismissResultDialog"
    @keydown="handleVerificationResultDialogKeydown"
  >
    <div
      :ref="setVerificationResultDialogRef"
      class="iot-dialog iot-dialog--md"
      :class="verificationResultStatus.dialogToneClass"
      role="dialog"
      aria-modal="true"
      aria-labelledby="verification-result-dialog-title"
      tabindex="-1"
      autofocus
      @click.stop
    >
      <!-- Header. The verdict is carried by the dialog's tone (the icon tile and the consequence rules read
           it), not by tinting the whole header band: a full-bleed coloured header made a satisfied result and
           a violated one look like two different products. -->
      <div data-testid="verification-result-header" class="iot-dialog__header">
        <div class="iot-dialog__icon">
          <span class="material-symbols-outlined" aria-hidden="true">
            {{ verificationResultStatus.icon }}
          </span>
        </div>
        <div class="iot-dialog__heading">
          <h3 id="verification-result-dialog-title" class="iot-dialog__title">{{ verificationError ? verificationResultStatus.title : t('app.verificationResult') }}</h3>
          <p v-if="verificationResultStatus.detail" class="iot-dialog__subtitle">{{ verificationResultStatus.detail }}</p>
        </div>
        <HintTooltip :content="t('app.close')">
          <button
            type="button"
            data-testid="close-verification-result"
            @click="dismissResultDialog"
            :aria-label="t('app.close')"
            class="iot-dialog__close"
          >
            <span class="material-symbols-outlined" aria-hidden="true">close</span>
          </button>
        </HintTooltip>
      </div>

      <div data-testid="verification-result-scroll" class="iot-dialog__body iot-scroll-region">
        <!--
          `board-surface-danger`, not `board-chip-danger border board-border-subtle`. The chip roles
          declare `border: 0` on purpose (they are badges), and `board.css` is unlayered while Tailwind's
          `.border` sits in `@layer utilities` — so unlayered wins and the border utility did nothing.
          Measured: this markup rendered `border-top-width: 0px`, while `board-surface-danger` renders
          0.667px from the role's own border token. An edgeless tint reads as a background wash rather
          than a bounded error notice.
        -->
        <div v-if="verificationError" class="mb-4 board-run-notice board-surface-danger">
          <p v-if="runFailureDiagnostics.verification?.message !== verificationResultStatus.title" class="font-medium board-text-danger">{{ runFailureDiagnostics.verification?.message }}</p>
          <FailureDetails v-if="runFailureDiagnostics.verification" v-bind="runFailureDiagnostics.verification" />
        </div>

        <div v-else-if="verificationResult" class="space-y-4">
          <!-- Stale Warning -->
          <div
            v-if="verificationResultStale"
            data-testid="verification-result-stale-banner"
            role="status"
            class="board-run-notice flex items-start gap-2 board-surface-warning board-text-warning"
          >
            <span class="material-symbols-outlined text-base" aria-hidden="true">history</span>
            <div class="min-w-0">
              <p v-if="verificationFixApplied" data-testid="verification-fix-applied" class="mb-1 font-semibold">{{ t('app.fixAppliedWithSignedEvidence') }}</p>
              <p>{{ t('app.verificationResultStaleReverify') }}</p>
            </div>
          </div>
          <div
            v-else-if="verificationFixBlockedReason"
            data-testid="verification-result-comparison-banner"
            role="status"
            class="board-run-notice flex items-start gap-2 board-surface-warning board-text-warning"
          >
            <span class="material-symbols-outlined text-base" aria-hidden="true">info</span>
            <span>{{ verificationFixBlockedReason }}</span>
          </div>

          <!--
            The count grid. Its heading is `sr-only`: the dialog title already says "Verification
            Result" two lines above, and the three column labels name what the numbers are, so a
            visible "Verification Summary" between them was a third statement of the same thing. The
            heading stays in the accessibility tree because `aria-labelledby` needs a target.
          -->
          <section aria-labelledby="verification-summary-title">
            <h4 id="verification-summary-title" class="sr-only">{{ t('app.verificationSummary') }}</h4>
            <div class="verification-summary grid grid-cols-3 gap-px overflow-hidden rounded-lg border board-border-subtle board-bg-control">
              <div class="board-card p-4">
                <div class="board-form-label">{{ t('app.satisfied') }}</div>
                <div class="mt-1 text-2xl font-semibold board-text-success">{{ verificationSpecResultSummary.satisfied }}</div>
              </div>
              <div class="board-card p-4">
                <div class="board-form-label">{{ t('app.violated') }}</div>
                <div class="mt-1 text-2xl font-semibold board-text-danger">{{ verificationSpecResultSummary.violated }}</div>
              </div>
              <div class="board-card p-4">
                <div class="board-form-label">{{ t('app.inconclusive') }}</div>
                <div class="mt-1 text-2xl font-semibold board-text-warning">{{ verificationSpecResultSummary.inconclusive }}</div>
              </div>
            </div>
          </section>


          <!--
            Counterexamples, promoted above the per-specification verdicts: this is the evidence the
            reader acts on, and the verdict list is the reference behind it.

            One list, not two. This section and a second, near-identical one below the run context both
            rendered `verificationResult.traces`, so every violation appeared twice with two different
            replay handlers — and the promoted copy passed the *array index* to
            `selectAndPlayVerificationTrace`, which takes a trace id, so its "view" button fetched
            trace 0, 1, 2… The surviving list keeps `selectAndPlayTrace(index)`, which indexes
            `verificationResult.traces` directly, and the `data-testid`s the E2E flow addresses.
          -->
          <!--
            Outside the counterexample section, not inside it. That section is
            `v-if="traces?.length"`, so with no parseable counterexample it does not render at all — and
            that is precisely the case where the summary grid's violation count needs accounting for.
            A notice placed inside would vanish exactly when it is needed.
          -->
          <p
            v-if="verificationEvidenceShortfall > 0"
            class="board-run-notice board-surface-warning board-text-warning"
            data-testid="verification-evidence-shortfall"
          >
            {{ t('app.someViolationsHaveNoReplayableCounterexample') }}
          </p>

          <section v-if="verificationResult?.traces?.length" aria-labelledby="violations-title">
            <h4 id="violations-title" class="board-section-title mb-2">
              {{ getVerificationOutcome(verificationResult) === 'VIOLATED'
                ? t('app.violationsTitle')
                : t('app.inconclusiveEvidenceTitle') }} ({{ verificationResult.traces.length }})
            </h4>
            <p
              v-if="getVerificationOutcome(verificationResult) === 'INCONCLUSIVE'"
              class="board-run-notice mb-2 board-surface-warning board-text-warning"
            >
              {{ t('app.inconclusiveEvidenceSummary', { counterexamples: verificationResult.traces.length }) }}
            </p>
            <div class="space-y-2">
              <div v-for="(trace, i) in verificationResult.traces" :key="i" class="border board-border-subtle rounded-lg p-3">
                <div class="verification-trace__header">
                  <div
                    class="text-xs font-semibold"
                    :class="getVerificationOutcome(verificationResult) === 'VIOLATED'
                      ? 'board-text-danger'
                      : 'board-text-warning'"
                  >{{ t('app.violationNumber', { index: Number(i) + 1 }) }}</div>
                  <div class="verification-trace__actions">
                    <button
                      v-if="canFixVerificationResultTrace(trace)"
                      type="button"
                      data-testid="verification-trace-fix"
                      class="iot-dialog-btn iot-dialog-btn--ghost"
                      :disabled="simulationAnimationState.visible"
                      @click="openFixForVerificationResultTrace(trace)"
                    >
                      <!-- aria-hidden, or the ligature text joins the accessible name: this button
                           announced as "build Fix Rules" and its sibling as "play_arrow View", which
                           is what a name-based query (or a screen reader) actually receives. -->
                      <span class="material-symbols-outlined text-xs" aria-hidden="true">build</span>
                      {{ t('app.fixRules') }}
                    </button>
                    <button
                      type="button"
                      @click="selectAndPlayTrace(Number(i))"
                      :disabled="simulationAnimationState.visible"
                      data-testid="verification-trace-replay"
                      class="iot-dialog-btn iot-dialog-btn--primary"
                    >
                      <span class="material-symbols-outlined text-xs" aria-hidden="true">play_arrow</span>
                      {{ t('app.replay') }}
                      <span v-if="simulationAnimationState.visible" class="text-xs">({{ t('app.active') }})</span>
                    </button>
                  </div>
                </div>
                <!-- Why Fix is withheld. Without this the button simply vanished, which reads as a bug. -->
                <p
                  v-if="!canFixVerificationResultTrace(trace) && !verificationResultStale"
                  data-testid="verification-trace-fix-unavailable"
                  class="board-run-notice mb-2 board-surface-warning board-text-warning"
                >
                  {{ !hasPersistedVerificationTrace(verificationResult, trace)
                    ? t(verificationResult.historyPersistence.status === 'OUTCOME_UNKNOWN'
                      ? 'app.verificationTracePersistenceUnknownFixUnavailable'
                      : 'app.verificationTraceNotPersistedFixUnavailable')
                    : verificationFixBlockedReason }}
                </p>
                <div class="text-xs board-text-muted">
                  <span class="font-medium">{{ getTraceSpecDisplayTitle(trace) }}</span>
                  <span class="board-text-muted"> · {{ t('app.statesCount', { count: trace.states?.length || 0 }) }}</span>
                </div>
                <details v-if="trace.violatedSpecId" class="verification-result-disclosure mt-1 text-xs board-text-muted">
                  <summary class="cursor-pointer font-semibold">{{ t('app.technicalDetails') }}</summary>
                  <div class="mt-1 grid gap-1 sm:grid-cols-[9rem_minmax(0,1fr)]">
                    <span class="font-medium">{{ t('app.specificationTechnicalId') }}</span>
                    <code class="break-all rounded board-bg-muted px-2 py-1 text-xs board-text-strong">{{ trace.violatedSpecId }}</code>
                  </div>
                </details>
              </div>
            </div>
          </section>

          <details
            v-if="verificationSpecResultSummary.total > 0"
            class="verification-result-disclosure group border-t board-border-subtle pt-3"
            open
            data-testid="spec-results-section"
          >
            <summary class="flex cursor-pointer list-none items-center justify-between text-sm font-semibold board-text-strong hover:board-text-strong">
              <span class="inline-flex items-center gap-2">
                <span class="material-symbols-outlined text-lg board-text-muted" aria-hidden="true">rule</span>
                {{ t('app.specResults') }} ({{ verificationSpecResultSummary.total }})
              </span>
              <span class="material-symbols-outlined transition-transform group-open:rotate-180" aria-hidden="true">expand_more</span>
            </summary>
            <div class="iot-scroll-region mt-3 space-y-2 max-h-72 pr-1" data-testid="spec-results-list">
              <article
                v-for="(result, index) in verificationSpecResultSummary.results"
                :key="`${result.specId}-${index}`"
                :data-spec-id="result.specId"
                class="board-card rounded-lg border p-3"
                :class="result.presentation.borderClass"
              >
                <div class="flex flex-wrap items-center justify-between gap-2 text-xs">
                  <span class="font-semibold board-text-muted">{{ t('app.specificationNumber', { number: Number(index) + 1 }) }}</span>
                  <span class="inline-flex items-center gap-1 rounded-full border px-2 py-0.5 font-semibold" :class="result.presentation.badgeClass">
                    <span class="material-symbols-outlined text-sm" aria-hidden="true">{{ result.presentation.icon }}</span>
                    {{ result.presentation.label }}
                  </span>
                </div>
                <h4 class="board-copy my-2 break-words" data-testid="spec-result-description">
                  {{ result.description || result.displayTitle }}
                </h4>
                <p v-if="!result.description" class="mb-2 text-xs board-text-muted" data-testid="spec-result-snapshot-unavailable">{{ t('app.specificationSnapshotUnavailable') }}</p>
                <details class="spec-formula-disclosure" data-testid="spec-result-formula-details">
                  <summary class="text-xs font-medium board-text-muted">
                    <span class="material-symbols-outlined spec-formula-disclosure__chevron" aria-hidden="true">chevron_right</span>
                    <span>{{ t('app.formulaPreview') }}</span>
                    <span class="rounded board-card board-card--muted px-1.5 py-0.5 font-mono" data-testid="spec-result-formula-kind">{{ result.formulaKind }}</span>
                  </summary>
                  <p class="mt-2 whitespace-pre-wrap break-all rounded board-card board-card--muted p-2 font-mono text-xs leading-relaxed board-text-muted" data-testid="spec-result-formula">{{ result.formula }}</p>
                </details>
                <div v-if="result.variableSourceLabels.length" class="mt-2 flex flex-wrap gap-1">
                  <span v-for="entry in result.variableSourceLabels" :key="entry.key"
                    class="rounded border px-1.5 py-0.5 text-xs font-semibold"
                    :class="entry.unresolved ? 'board-chip-danger board-text-danger border-[color:var(--danger-border)]' : 'board-border-subtle board-bg-control board-text-muted'"
                    data-testid="spec-result-variable-source">{{ entry.label }}</span>
                </div>
                <details v-if="result.specId || result.expression" class="verification-result-disclosure mt-2 text-xs board-text-muted">
                  <summary class="cursor-pointer font-semibold">{{ t('app.technicalDetails') }}</summary>
                  <div class="mt-1 grid gap-1 sm:grid-cols-[9rem_minmax(0,1fr)]">
                    <span class="font-medium">{{ t('app.actualCheckedExpression') }}</span>
                    <code class="break-all rounded board-bg-muted px-2 py-1 board-text-strong">{{ result.expression }}</code>
                    <span class="font-medium">{{ t('app.specificationTechnicalId') }}</span>
                    <code class="break-all rounded board-bg-muted px-2 py-1 board-text-strong">{{ result.specId }}</code>
                  </div>
                </details>
              </article>
            </div>
          </details>

          <!--
            The checked model, as a named scene-level artifact.

            It belongs to the *run*, not to any counterexample: one model is checked, and every
            counterexample the run produced came out of that same model. It used to sit in the
            counterexample-details dialog, which implied one model per counterexample and put a
            scene-level artifact behind a per-evidence surface. It was also a footer
            `--secondary` button, i.e. styled as an afterthought next to Close, which is how users
            failed to find it at all.

            Stated with what the model covers, so the reader knows what they are downloading before
            they click, and disabled-with-reason rather than hidden when the run stores no model —
            a control that silently vanishes reads as a missing feature.
          -->
          <section
            v-if="verificationResult.modelSnapshot"
            aria-labelledby="verification-run-artifact-title"
            data-testid="verification-run-artifact"
          >
            <h4 id="verification-run-artifact-title" class="board-section-title mb-2">
              {{ t('app.runArtifact') }}
            </h4>
            <div class="flex flex-wrap items-center justify-between gap-3 rounded-lg border board-border-subtle board-bg-muted p-4">
              <div class="verification-run-artifact__copy">
                <p class="text-xs font-semibold board-text-strong">{{ t('app.smvModelArtifactTitle') }}</p>
                <p class="mt-0.5 text-xs leading-5 board-text-muted">
                  {{ t('app.smvModelArtifactScope', {
                    devices: verificationResult.modelSnapshot.deviceCount,
                    rules: verificationResult.modelSnapshot.ruleCount,
                    specs: verificationResult.modelSnapshot.specificationCount
                  }) }}
                </p>
                <p
                  v-if="!verificationRunSmvAvailable"
                  class="mt-1 text-xs leading-5 board-text-warning"
                  data-testid="verification-result-smv-unavailable"
                >
                  {{ t(verificationSmvUnavailableReason) }}
                </p>
              </div>
              <button
                type="button"
                class="iot-dialog-btn iot-dialog-btn--primary shrink-0"
                data-testid="verification-result-download-smv"
                :disabled="!verificationRunSmvAvailable"
                @click="downloadCurrentVerificationRunSmv()"
              >
                <span class="material-symbols-outlined" aria-hidden="true">download</span>
                {{ t('app.downloadSmvModel') }}
              </button>
            </div>
          </section>

          <!-- Run Context (Collapsible, merged) -->
          <details class="verification-result-disclosure group border-t board-border-subtle pt-3" data-testid="run-context-section">
            <summary class="flex cursor-pointer list-none items-center justify-between text-sm font-semibold board-text-strong hover:board-text-strong">
              <span class="inline-flex items-center gap-2">
                <span class="material-symbols-outlined text-lg board-text-muted" aria-hidden="true">inventory_2</span>
                {{ t('app.runContext') }}
              </span>
              <span class="material-symbols-outlined transition-transform group-open:rotate-180" aria-hidden="true">expand_more</span>
            </summary>
            <div class="mt-3 space-y-3">
              <!-- Model Snapshot -->
              <div class="board-bg-muted border board-border-subtle rounded-lg p-3">
                <h5 class="board-section-title mb-2">{{ t('app.modelRunSnapshotTitle') }}</h5>
                <p class="text-xs board-text-muted mb-2">
                  {{ t('app.modelRunSnapshotSummary', {
                    time: formatRunTimestamp(verificationResult.modelSnapshot.capturedAt),
                    devices: verificationResult.modelSnapshot.deviceCount,
                    rules: verificationResult.modelSnapshot.ruleCount,
                    specs: verificationResult.modelSnapshot.specificationCount,
                    variables: verificationResult.modelSnapshot.environmentVariableCount,
                    templates: verificationResult.modelSnapshot.deviceTemplateCount
                  }) }}
                </p>
                <div
                  class="mt-2 rounded-md border px-3 py-2 text-xs font-semibold leading-5"
                  :class="verificationBoardComparison === 'UNCHANGED'
                    ? 'board-border-subtle board-chip-success board-text-success'
                    : verificationBoardComparison === 'CHANGED'
                      ? 'board-surface-warning board-text-warning'
                      : 'board-border-subtle board-bg-muted board-text-strong'"
                  data-testid="verification-board-comparison"
                >
                  {{ verificationBoardComparison === 'UNCHANGED'
                    ? t('app.runBoardInputUnchanged')
                    : verificationBoardComparison === 'CHANGED'
                      ? t('app.runBoardInputChanged')
                      : verificationBoardComparison === 'UNAVAILABLE'
                        ? t(loadingCurrentBoardModelFingerprints
                          ? 'app.runBoardComparisonLoading' : 'app.runBoardComparisonUnavailable')
                        : t('app.runBoardNotCompared') }}
                </div>
              </div>

              <!-- Model Assumptions -->
              <div class="board-bg-muted border board-border-subtle rounded-lg p-3">
                <h5 class="board-section-title mb-2">{{ t('app.modelAssumptions') }}</h5>
                <div
                  v-if="!verificationModelSemanticsConsistent"
                  class="mb-2 rounded board-surface-warning px-2 py-1.5 text-xs font-semibold board-text-warning"
                >
                  {{ t('app.modelSemanticsUnavailable') }}
                </div>
                <div class="space-y-2 text-xs leading-5 board-text-muted">
                  <div v-if="verificationModelSemanticsConsistent" class="flex items-start gap-2">
                    <span class="material-symbols-outlined text-base board-text-info">landscape</span>
                    <span>{{ t('app.environmentEvolutionIncluded') }}</span>
                  </div>
                  <div v-if="verificationModelSemanticsConsistent" class="flex items-start gap-2">
                    <span class="material-symbols-outlined text-base board-text-success">verified_user</span>
                    <span>{{ t('app.trustPropagationIncluded') }}</span>
                  </div>
                  <div v-if="verificationModelSemanticsConsistent" class="flex items-start gap-2">
                    <span class="material-symbols-outlined text-base board-text-info">sync_alt</span>
                    <span>{{ t('app.labelPropagationScopeSummary') }}</span>
                  </div>
                  <div v-if="verificationModelSemanticsConsistent" class="flex items-start gap-2">
                    <span class="material-symbols-outlined text-base" :class="verificationResult.isAttack ? 'board-text-danger' : 'board-text-muted'">security</span>
                    <span>
                      {{ verificationResult.isAttack
                        ? attackSelectionSummary(verificationResult.modelSemantics, verificationResult.attackBudget, 'verification')
                        : t('app.verificationNoAttackCoverage') }}
                    </span>
                  </div>
                  <div v-if="verificationModelSemanticsConsistent" class="flex items-start gap-2">
                    <span class="material-symbols-outlined text-base" :class="verificationResult.enablePrivacy ? 'board-text-info' : 'board-text-muted'">shield_lock</span>
                    <span>
                      {{ verificationResult.enablePrivacy
                        ? t('app.privacyPropagationIncluded')
                        : t('app.privacyPropagationNotIncluded') }}
                    </span>
                  </div>
                </div>
              </div>

              <!--
                The solver's own words, restored.
                The dialog-consolidation pass deleted this disclosure and a later sweep deleted its
                now-orphaned label as dead code, which together removed the ONLY place a NuSMV message can
                reach a user. `nusmvOutput` is still captured by the executor, persisted on the run,
                mapped through every DTO and required by the client contract validator — it was carried the
                whole way and rendered nowhere.

                What that costs is not cosmetic. NuSMV reports conditions the parser does not model, and at
                least one of them is an explicit trust warning: a model whose fair-states set is empty
                prints "This might make results of model checking not trustable" and then answers every
                specification `true` — measured directly against NuSMV 2.7.1 on a hand-built deadlocking
                model. Today's generator emits total transition relations (every `case` carries a `TRUE:`
                default, no `TRANS`/`FAIRNESS` sections), so that state is not reachable through the
                product *right now*; this surface is what makes it visible if that ever changes, and it is
                the only channel for any other solver diagnostic.

                Placed in the run context because raw solver output describes the run, not one
                counterexample. Kept behind a disclosure because it is a technical detail, not a verdict.
              -->
              <details
                v-if="verificationResult.nusmvOutput"
                class="verification-result-disclosure board-bg-muted border board-border-subtle rounded-lg p-3"
                data-testid="verification-nusmv-output"
              >
                <summary class="cursor-pointer text-xs font-semibold board-text-strong hover:board-text-strong">
                  {{ t('app.showNusmvDiagnosticOutput') }}
                </summary>
                <div class="iot-scroll-region mt-2 max-h-44 rounded-lg board-bg-muted p-3">
                  <pre class="whitespace-pre-wrap font-mono text-xs leading-5 board-text-strong">{{ verificationResult.nusmvOutput }}</pre>
                </div>
              </details>
            </div>
          </details>

          <div v-if="verificationGenerationWarningCounts.total > 0" class="p-4 rounded-xl board-surface-warning board-text-warning">
            <div class="flex items-start gap-3">
              <span class="material-symbols-outlined board-text-warning">report</span>
              <div>
                <div class="text-sm font-semibold">{{ t('app.generationWarnings') }}</div>
                <p class="text-sm mt-1">
                  {{ t('app.disabledRulesSkippedSpecs', { rules: verificationGenerationWarningCounts.disabledRuleCount, specs: verificationGenerationWarningCounts.skippedSpecCount }) }}
                </p>
                <ul v-if="verificationGenerationIssues.length > 0" class="mt-3 space-y-2">
                  <li
                    v-for="(issue, index) in verificationGenerationIssues"
                    :key="`${issue.issueType}-${issue.itemLabel}-${index}`"
                    class="border-l-2 board-border-subtle pl-3"
                  >
                    <div class="text-xs font-semibold board-text-warning">{{ issue.itemLabel }}</div>
                    <div class="mt-0.5 text-xs leading-5 board-text-warning">{{ t(generationIssueReasonKey(issue)) }}</div>
                  </li>
                </ul>
                <p v-else class="mt-2 text-xs board-text-warning">
                  {{ t('app.generationIssueDetailsUnavailable') }}
                </p>
              </div>
            </div>
          </div>

          <details
            v-if="verificationCheckLogs.length > 0"
            :open="getVerificationOutcome(verificationResult) === 'INCONCLUSIVE' || !verificationResult.modelComplete"
            data-testid="verification-check-logs"
            class="p-4 rounded-xl board-bg-muted border board-border-subtle"
          >
            <summary class="board-section-title cursor-pointer">{{ t('app.executionLogs') }}</summary>
            <ol class="iot-scroll-region mt-2 max-h-44 space-y-0.5">
              <li
                v-for="(log, index) in verificationCheckLogs"
                :key="index"
                class="font-mono text-xs leading-5 board-text-strong break-words"
              >
                {{ log }}
              </li>
            </ol>
          </details>
        </div>

      </div>

      <footer v-if="verificationResult" class="iot-dialog__footer">
        <button
          type="button"
          class="iot-dialog-btn iot-dialog-btn--ghost"
          @click="dismissResultDialog"
        >
          {{ t('app.close') }}
        </button>
        <button
          v-if="verificationResultStale"
          type="button"
          data-testid="verification-result-reverify"
          class="iot-dialog-btn iot-dialog-btn--primary"
          @click="reopenVerificationSettingsFromResult"
        >
          <span class="material-symbols-outlined" aria-hidden="true">fact_check</span>
          {{ t('app.configureCurrentBoardVerification') }}
        </button>
      </footer>
    </div>
  </div>

  <PlaybackTerminal
    v-if="traceAnimationState.visible && currentTrace"
    v-show="playbackPanelOpen"
    v-model:tab="playbackTerminalTab"
    :kind="activeFuzzingFinding ? 'fuzzing' : 'counterexample'"
    :selected-state-index="traceAnimationState.selectedStateIndex"
    :total-states="totalStates"
    :playing="traceAnimationState.isPlaying"
    :panel-open="playbackPanelOpen"
    :state-label="getTraceStateAriaLabel"
    :violation-steps="counterexampleViolationSteps"
    :violation-label="traceStateViolationLabel"
    :locate-label="counterexampleViolationStep === undefined ? t('app.traceVisualization.jumpToViolationLoop') : t('app.traceVisualization.jumpToViolation')"
    :style="boardShellStyle"
    @seek="goToState"
    @toggle-play="toggleTraceAnimation"
    @scrub-start="stopTraceAnimation"
    @locate-violation="goToState(counterexampleViolationSteps[0])"
    @hide-panel="hidePlaybackPanel"
    @keydown="handlePlaybackKeydown"
  >
    <template #help>
      <InfoTooltip :text="t('app.playbackNavigationHint')" :label="t('app.showHelpFor', { topic: activeFuzzingFinding ? t('app.fuzzFindingReplay') : t('app.traceVisualization.counterexampleTracePlayback') })" tone="neutral" test-id="counterexample-trace-help" />
    </template>
    <template v-if="activeFuzzingFinding || currentTrace.modelComplete === false || !traceModelSemanticsConsistent" #context>
      <p v-if="activeFuzzingFinding" class="playback-terminal__notice board-note" data-testid="fuzzing-playback-notice">{{ t('app.fuzzFindingReplayHint') }}</p>
      <p v-if="!activeFuzzingFinding && currentTrace.modelComplete === false" class="playback-terminal__notice board-surface-warning" data-testid="trace-timeline-incomplete-warning">{{ t('app.traceVisualization.verificationModelIncompletePlayback', { rules: currentTrace.disabledRuleCount || 0, specs: currentTrace.skippedSpecCount || 0 }) }}</p>
      <p v-if="!activeFuzzingFinding && !traceModelSemanticsConsistent" class="playback-terminal__notice board-surface-warning" data-testid="trace-model-semantics-warning">{{ t('app.modelSemanticsUnavailable') }}</p>
    </template>
    <template #run>
      <div class="playback-run" data-testid="counterexample-run-info">
        <section class="playback-run__section" data-testid="counterexample-evidence">
          <h3>{{ t('app.violatedSpecification') }}</h3>
          <p class="playback-run__requirement" data-testid="trace-timeline-violated-spec">{{ getTraceSpecDisplayTitle(currentTrace) }}</p>
          <span v-if="traceStateViolationLabel(traceAnimationState.selectedStateIndex)" class="playback-terminal__badge board-chip-danger board-text-danger" data-testid="trace-timeline-violation-chip">{{ traceStateViolationLabel(traceAnimationState.selectedStateIndex) }}</span>
          <details v-if="!activeFuzzingFinding" data-testid="counterexample-checked-expression">
            <summary>{{ t('app.checkedExpression') }}</summary>
            <pre>{{ currentTrace.checkedExpression || t('app.notAvailable') }}</pre>
          </details>
        </section>
        <section v-if="!activeFuzzingFinding" class="playback-run__section" data-testid="counterexample-run-context">
          <h3>{{ t('app.runScopeAndSnapshot') }}</h3>
          <dl class="playback-run__facts">
            <div><dt>{{ t('app.modelCoverage') }}</dt><dd>{{ currentTrace.modelComplete ? t('app.completeModel') : t('app.incompleteModel') }}</dd></div>
            <div><dt>{{ t('app.traceVisualization.attackScenario') }}</dt><dd>{{ !traceModelSemanticsConsistent ? t('app.modelSemanticsUnavailable') : currentTrace.isAttack ? attackSelectionSummary(currentTrace.modelSemantics, currentTrace.attackBudget, 'verification') : t('app.traceVisualization.noAttackModelShort') }}</dd></div>
            <div><dt>{{ t('app.privacy') }}</dt><dd>{{ !traceModelSemanticsConsistent ? t('app.modelSemanticsUnavailable') : currentTrace.enablePrivacy ? t('app.traceVisualization.privacyPropagationEnabled') : t('app.traceVisualization.privacyPropagationNotModeled') }}</dd></div>
            <div><dt>{{ t('app.smvModelArtifactTitle') }}</dt><dd>{{ t('app.smvModelArtifactScope', { devices: currentTrace.modelSnapshot.deviceCount, rules: currentTrace.modelSnapshot.ruleCount, specs: currentTrace.modelSnapshot.specificationCount }) }}</dd></div>
            <div v-if="currentTrace.createdAt"><dt>{{ t('app.traceCreatedAt') }}</dt><dd>{{ formatRunTimestamp(currentTrace.createdAt) }}</dd></div>
          </dl>
          <ul v-if="currentTrace.generationIssues.length" class="playback-run__issues board-text-warning" data-testid="counterexample-generation-issues">
            <li v-for="(issue, idx) in currentTrace.generationIssues" :key="`${issue.issueType}-${idx}`"><strong>{{ issue.itemLabel }}</strong><span>{{ t(generationIssueReasonKey(issue)) }}</span></li>
          </ul>
          <p v-else-if="!currentTrace.modelComplete" class="playback-terminal__notice board-surface-warning">{{ t('app.generationIssueDetailsUnavailable') }}</p>
          <div class="playback-terminal__run-actions">
            <button v-if="currentTrace.verificationTaskId" type="button" data-testid="counterexample-open-owning-run" @click="openOwningVerificationRun(currentTrace.verificationTaskId)">
              <span class="material-symbols-outlined" aria-hidden="true">fact_check</span>{{ t('app.counterexampleOwningRun') }}
            </button>
          </div>
        </section>
      </div>
    </template>
    <template #details>
      <PlaybackStateDetails
        :devices="activePlaybackDevices"
        :environment-variables="activePlaybackEnvironmentVariables"
        :compromised-automation-links="currentTraceCompromisedAutomationLinks"
        :current-device-ids="nodes.map(node => node.id)"
        :current-rule-ids="rules.map(rule => String(rule.id))"
        :format-device-token="formatPlaybackDeviceModelToken"
        :format-environment-token="formatPlaybackEnvironmentModelToken"
      />
    </template>
    <template #changes>
      <PlaybackChangesPanel
        v-if="activePlaybackKind"
        :changes="activePlaybackChanges"
        :environment-changes="activePlaybackEnvironmentChanges"
        :triggered-rules="activePlaybackTriggeredRules"
        :current-rule-ids="currentBoardRuleIds"
        :animated-edge-count="activePlaybackAnimatedEdgeCount"
        :state-number="activePlaybackStateIndex + 1"
        :total-states="activePlaybackStates.length"
        :kind="activePlaybackKind"
        :input-events="activeFuzzingStepInputEvents"
        :bundled-device-ids="bundledPlaybackDeviceIds"
        :bundled-environment-names="bundledPlaybackEnvironmentNames"
        :violation-state-number="activeViolationStateNumber"
        :is-loop-back-state="activePlaybackIsLoopBackState"
        :loop-range="activePlaybackLoopRange"
        :is-liveness-violation="activePlaybackIsLivenessViolation"
      />
    </template>
  </PlaybackTerminal>

  <!-- Simulation Timeline 组件

       `v-if` stays on `simulationAnimationState.visible` alone. That flag is the single authority for "the
       simulation replay is active" — 33 sites read it, including the board edit lock (`isAnimationLocked`),
       the playback-kind resolution behind `run=`, the keyboard handlers and the panel disabling — so adding
       `&& lastSimulationResult` here would make the *rendered* surface a second, subtly different notion of
       the same thing: the lock could be held with nothing on screen to explain why.

       The required `modelSemantics` is narrowed at the prop instead, by `simulationPlaybackSemantics`. -->
  <SimulationTimeline
    v-if="simulationAnimationState.visible"
    v-show="playbackPanelOpen"
    :visible="simulationAnimationState.visible"
    :panel-open="playbackPanelOpen"
    :states="savedSimulationStates"
    :actual-steps="lastSimulationResult?.steps"
    :requested-steps="lastSimulationResult?.requestedSteps"
    :model-complete="lastSimulationResult?.modelComplete"
    :disabled-rule-count="lastSimulationResult?.disabledRuleCount"
    :is-attack="lastSimulationResult?.isAttack"
    :attack-budget="lastSimulationResult?.attackBudget"
    :enable-privacy="lastSimulationResult?.enablePrivacy"
    :model-semantics="simulationPlaybackSemantics"
    :model-snapshot="lastSimulationResult?.modelSnapshot"
    :board-comparison="simulationBoardComparison"
    :current-rule-ids="currentBoardRuleIds"
    :current-device-ids="currentBoardDeviceIds"
    :format-device-model-token="formatPlaybackDeviceModelToken"
    :format-environment-model-token="formatPlaybackEnvironmentModelToken"
    v-model:tab="playbackTerminalTab"
    :style="boardShellStyle"
    @highlight-state="handleHighlightTrace"
    @hide-panel="hidePlaybackPanel"
    @keydown="handlePlaybackKeydown"
  >
    <template #run>
      <div v-if="lastSimulationResult" class="playback-run" data-testid="simulation-run-info">
        <section class="playback-run__section">
          <h3>{{ t('app.runSummary') }}</h3>
          <dl class="playback-run__facts">
            <div><dt>{{ t('app.actualSimulationSteps') }}</dt><dd>{{ getSimulationActualStepCount(lastSimulationResult) }} / {{ getSimulationRequestedStepCount(lastSimulationResult) }} {{ t('app.requestedSimulationSteps') }}</dd></div>
            <div><dt>{{ t('app.modelCoverage') }}</dt><dd>{{ isSimulationModelComplete(lastSimulationResult) ? t('app.completeModel') : t('app.incompleteModel') }}</dd></div>
            <div><dt>{{ t('app.traceVisualization.attackScenario') }}</dt><dd>{{ !isSimulationModelSemanticsConsistent(lastSimulationResult) ? t('app.modelSemanticsUnavailable') : lastSimulationResult.isAttack ? attackSelectionSummary(lastSimulationResult.modelSemantics, lastSimulationResult.attackBudget, 'simulation') : t('app.traceVisualization.noAttackModelShort') }}</dd></div>
            <div><dt>{{ t('app.privacy') }}</dt><dd>{{ !isSimulationModelSemanticsConsistent(lastSimulationResult) ? t('app.modelSemanticsUnavailable') : lastSimulationResult.enablePrivacy ? t('app.traceVisualization.privacyPropagationEnabled') : t('app.traceVisualization.privacyPropagationNotModeled') }}</dd></div>
          </dl>
          <ul v-if="getGenerationIssues(lastSimulationResult).length" class="playback-run__issues board-text-warning" data-testid="simulation-generation-issues">
            <li v-for="(issue, idx) in getGenerationIssues(lastSimulationResult)" :key="`${issue.issueType}-${idx}`"><strong>{{ issue.itemLabel }}</strong><span>{{ t(generationIssueReasonKey(issue)) }}</span></li>
          </ul>
          <p v-else-if="!isSimulationModelComplete(lastSimulationResult)" class="playback-terminal__notice board-surface-warning">{{ t('app.generationIssueDetailsUnavailable') }}</p>
        </section>
        <section class="playback-run__section" data-testid="simulation-run-artifact">
          <h3>{{ t('app.simulationSmvModelArtifactTitle') }}</h3>
          <p>{{ t('app.simulationSmvModelArtifactScope', { devices: lastSimulationResult.modelSnapshot.deviceCount, rules: lastSimulationResult.modelSnapshot.ruleCount }) }}</p>
          <p v-if="!simulationRunSmvAvailable" class="board-text-warning" data-testid="simulation-result-smv-unavailable">{{ t(simulationSmvUnavailableReason) }}</p>
          <div class="playback-terminal__run-actions">
            <button type="button" class="playback-run__download" data-testid="simulation-result-download-smv" :disabled="!simulationRunSmvAvailable" @click="downloadCurrentSimulationRunSmv()">
              <span class="material-symbols-outlined" aria-hidden="true">download</span>{{ t('app.downloadSmvModel') }}
            </button>
          </div>
        </section>
        <section class="playback-run__section playback-run__diagnostics">
          <details data-testid="simulation-execution-logs">
            <summary>{{ t('app.executionLogs') }}</summary>
            <pre>{{ lastSimulationResult.logs.join('\n') || t('app.noLogsAvailableShort') }}</pre>
          </details>
          <details data-testid="simulation-nusmv-output">
            <summary>{{ t('app.showNusmvDiagnosticOutput') }}</summary>
            <pre>{{ lastSimulationResult.nusmvOutput || t('app.noLogsAvailableShort') }}</pre>
          </details>
        </section>
      </div>
    </template>
    <template #changes>
      <PlaybackChangesPanel
        v-if="activePlaybackKind"
        :changes="activePlaybackChanges"
        :environment-changes="activePlaybackEnvironmentChanges"
        :triggered-rules="activePlaybackTriggeredRules"
        :current-rule-ids="currentBoardRuleIds"
        :animated-edge-count="activePlaybackAnimatedEdgeCount"
        :state-number="activePlaybackStateIndex + 1"
        :total-states="activePlaybackStates.length"
        :kind="activePlaybackKind"
        :input-events="activeFuzzingStepInputEvents"
        :bundled-device-ids="bundledPlaybackDeviceIds"
        :bundled-environment-names="bundledPlaybackEnvironmentNames"
        :violation-state-number="activeViolationStateNumber"
        :is-loop-back-state="activePlaybackIsLoopBackState"
        :loop-range="activePlaybackLoopRange"
        :is-liveness-violation="activePlaybackIsLivenessViolation"
      />
    </template>
  </SimulationTimeline>

  <!-- Keep the request owner mounted so hidden admission-unknown searches remain cancellable. -->
  <FixResultDialog
    ref="fixResultDialogRef"
    :visible="showFixDialog"
    :trace-id="fixTraceId || 0"
    :violated-spec-id="fixViolatedSpecId"
    @update:visible="showFixDialog = $event"
    @applied="handleFixApplied"
    @outcome-uncertain="handleFixOutcomeUncertain"
  />
</template>
