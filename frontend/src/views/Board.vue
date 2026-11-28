<script lang="ts">
import { buildSpecificationSemanticKey } from '../utils/spec'
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

/** Only confirmed absent/forbidden targets, not transient reads, make a shared history link dead. */
export const shouldReportUnusableHistoryDeepLink = (error: unknown): boolean => {
  if (isPersistedHistoryDataInvalid(error)) return true
  const status = Number((error as { response?: { status?: unknown } } | null)?.response?.status)
  return status === 403 || status === 404 || status === 410
}

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
  dismissPlayback
}: {
  sceneReplacementInProgress: boolean
  dismissPlayback: () => void
}): boolean => {
  if (sceneReplacementInProgress) return false
  dismissPlayback()
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
  presentOnBoard: boolean,
  preserveApplied: () => void,
  invalidateRecommendations: () => void
) => {
  if (presentOnBoard) preserveApplied()
  else invalidateRecommendations()
}

export const invalidateFuzzingResultRequests = (
  currentRequestEpoch: number,
  invalidateHistoryDetailRequests: () => void
): number => {
  invalidateHistoryDetailRequests()
  return currentRequestEpoch + 1
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
  activeNodeIds: Pick<ReadonlySet<string>, 'has'>
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
  // Requirement names are authored metadata; a rename does not change the checked model.
  specifications: canonicalUnorderedCollection(snapshot.specifications, value => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return value
    const spec = value as Parameters<typeof buildSpecificationSemanticKey>[0] & { id?: string }
    return { id: spec.id, logic: buildSpecificationSemanticKey(spec) }
  }),
  deviceTemplates: canonicalUnorderedCollection(snapshot.deviceTemplates)
}))

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
import TemplateRuntimeFields from '@/components/TemplateRuntimeFields.vue'
import {ref, shallowRef, reactive, computed, defineAsyncComponent, nextTick, onMounted, onBeforeUnmount, watch, watchPostEffect, h, type Ref, type VNode} from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { isAxiosError } from 'axios'
import { useChatStore } from '@/stores/chat'
import { useAuth } from '@/stores/auth'
import { publishBoardInvalidation, subscribeBoardInvalidation } from '@/utils/boardInvalidation'
import { ACTION_DOCK_RAIL_PX, BOARD_FLOATING_GAP_CSS, COLLAPSED_PANEL_RAIL_PX } from '@/constants/boardLayout'
import ProductMark from '@/components/common/ProductMark.vue'
// Icons

// Types
import type {DeviceDialogMeta, DeviceTemplate} from '../types/device'
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
  TraceSummary,
  TraceState,
  TraceTriggeredRule,
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
import { compareModelSnapshot, hasRunBoardDifference } from '@/utils/modelComparison'
import { getVerificationRepairRunBlock, getVerificationRepairTraceBlock } from '@/utils/verificationRepair'
import type { ModelRunSnapshot, ModelSemantics, RunBoardComparison } from '@/types/modelSemantics'
import type {
  EnvironmentVariableUpdateRequest,
  ModelEnvironmentVariable,
  ModelPlaybackScene,
  RunInitiator
} from '@/types/model'
import { isRunInitiator } from '@/types/model'
import type { InteractiveOperationStage, TaskCancellationResult, TaskProgressStage } from '@/types/task'
import type { FixApplyResult, FixBoardApplyOutcome, VerificationRepairBlock } from '@/types/fix'
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
import { countResponseTriggerWarnings, getVerificationOutcome, groupVerificationEvidence, presentSpecificationResult } from './board/verificationResult'
import { chooseRunPresentation, foregroundRunBlock, type FuzzVerificationHandoffSource,
  type BoardWorkflowPanel, type RunPanelKind, type RunPanelView, type RunPresentation } from './board/runPresentation'
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
  type BoardNodeSnapshotOptions,
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
  buildSpecFormula,
  isSameSpecification,
  isSpecRelatedToNode,
  specificationsWithUnresolvedVariableSource
} from '../utils/spec'
import { assertRuleHasTrigger, getLinkPoints, ruleSimilarityReasonKey } from '../utils/rule'
import {
  deriveTraceContext,
  findPlaybackLoopRange,
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
import { isDefinitiveMutationRejection, isRecordMissing, readBoardWriteReceipt as readWriteReceipt, readDeletionReceipt, type BoardWriteReceipt } from '@/utils/boardWriteReceipt'
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
  type OwnedRecommendationPostOptions,
  type RecommendationRequestOwner
} from '@/utils/recommendationRequestRecovery'
import { REQUEST_LIMITS } from '@/constants/requestLimits'
import { RECOMMENDATION_RESPONSE_INCOMPLETE_CODE } from '@/utils/recommendationResponse'
import { sceneTemplatesCoveredByCatalog } from '@/utils/sceneTemplateCoverage'
import {
  createSceneCodec,
  SCENE_FILE_SCHEMA,
  SCENE_FILE_VERSION,
  type BoardSceneModel
} from './board/portableScene'
import { getTemplateUsage, normalizeTemplateLookupName, summarizeImportedTemplates, summarizeTemplateUsage } from '@/utils/templateUsage'
import { prepareSceneImport } from './board/sceneImportPreparation'
import SceneImportRepairs from '@/components/SceneImportRepairs.vue'
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
  getTemplateLocalVariables,
  getTemplateEnvironmentVariables,
  syncStateDerivedVariables,
  getTemplateWorkingStates,
  resetDeviceRuntimeDraft,
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
  type CollectionMutationResult,
  type BoardReplacementPreview,
  type BoardWorkspaceSnapshot,
  type DeviceLayout,
  type DeviceRecommendation,
  type EnvironmentVariablePatchResult,
  type EnvironmentVariableChange,
  type RecommendationAdjustmentItem,
  type RecommendationFilteredItem,
  type RuleSimilarityResult,
  type SpecificationRecommendation
} from '@/api/board'
import simulationApi from '@/api/simulation'
import fuzzingApi from '@/api/fuzzing'
import { recommendRules, type RuleRecommendation } from '@/api/rules'

// Components
import CanvasBoard from '../components/CanvasBoard.vue'
import ControlCenter from '../components/ControlCenter.vue'
import SystemInspector from '../components/SystemInspector.vue'
import LanguageToggle from '@/components/common/LanguageToggle.vue'
import ThemeToggle from '@/components/common/ThemeToggle.vue'
import InfoTooltip from '@/components/common/InfoTooltip.vue'
import BackgroundTaskSummary from '@/components/BackgroundTaskSummary.vue'
import FormalRunSettings from '@/components/FormalRunSettings.vue'
import RecommendationRequestControls from '@/components/RecommendationRequestControls.vue'
import { describeSpecification, presentRule } from '@/utils/modelPresentation'
import ToggleSwitch from '@/components/common/ToggleSwitch.vue'
import { useModalAccessibility } from '@/composables/useModalAccessibility'
import { BOARD_ITEM_EDIT_OUTCOME_UNKNOWN } from '@/composables/useUnconfirmedBoardItemEdit'
import { openModalDepth } from '@/composables/useBodyScrollLock'
import { useBoardUndo } from '@/composables/useBoardUndo'
import { useBoardModelComparison } from './board/useBoardModelComparison'
import { usePlaybackCursor } from './board/usePlaybackCursor'

const DeviceDialog = defineAsyncComponent(() => import('../components/DeviceDialog.vue'))
const SimulationTimeline = defineAsyncComponent(() => import('../components/SimulationTimeline.vue'))
const FixResultDialog = defineAsyncComponent(() => import('../components/FixResultDialog.vue'))
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

const captureBoardRunOwner = () => {
  const scope = boardAuthScopeEpoch
  const userId = currentAuthUserId.value
  return () => isCurrentBoardAuthScope(scope) && currentAuthUserId.value === userId
}

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
let wideLayoutToRestore: BoardLayoutDto | null = null

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
  isModelPlaybackActive.value ||
  isAnyRecommendationRunning() ||
  unreadFuzzNotificationCount.value > 0
)

// Dock button tooltip content - one source of truth for each button's hover hint
const simulationTooltipContent = computed(() => {
  const status = isSimulating.value || isSimulationPlaybackActive.value
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
    wideLayoutToRestore = buildBoardLayoutPayload()
    if (layoutSaveTimer) {
      clearTimeout(layoutSaveTimer)
      layoutSaveTimer = null
      void saveBoardLayout()
    }
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
  } else if (wasNarrowViewport && wideLayoutToRestore) {
    applyBoardLayout(wideLayoutToRestore)
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
  '--board-status-height': isInitialBoardLoading.value ? '2.25rem' : '0px',
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
type BoardPlaybackSource =
  | { kind: 'simulation'; result: SimulationResultView }
  | { kind: 'counterexample'; trace: Trace }
  | { kind: 'fuzzing'; trace: Trace; finding: FuzzingFinding }
const activePlayback = shallowRef<(BoardPlaybackSource & { scene: ModelPlaybackScene }) | null>(null)
const activePlaybackScene = computed(() => activePlayback.value?.scene ?? null)
const activePlaybackKind = computed(() => activePlayback.value?.kind ?? null)
const isModelPlaybackActive = computed(() => activePlayback.value !== null)
const simulationPlaybackResult = computed(() => activePlayback.value?.kind === 'simulation'
  ? activePlayback.value.result : null)
const isSimulationPlaybackActive = computed(() => simulationPlaybackResult.value !== null)
const currentTrace = computed(() => activePlayback.value && activePlayback.value.kind !== 'simulation'
  ? activePlayback.value.trace : null)
const isTracePlaybackActive = computed(() => currentTrace.value !== null)
const activeFuzzingFinding = computed(() => activePlayback.value?.kind === 'fuzzing'
  ? activePlayback.value.finding : null)
const playbackCanvasPan = ref<CanvasPan>({ x: 0, y: 0 })
const playbackCanvasZoom = ref(1)
let playbackViewportFollowsFit = true
let playbackCanvasFrame: ReturnType<typeof getVisibleCanvasFrame> = null
const environmentVariables = ref<ModelEnvironmentVariable[]>([])
const environmentMutationPending = ref(false)
const unconfirmedEnvironmentPatches = new Map<string, { currentValues: string; snapshotRefreshed: boolean }>()
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
const unconfirmedBoardReplacementTokens = new Set<string>()
const unconfirmedUndoHistoryClearTokens = new Set<string>()
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
const renderedNodeBounds = computed(() => getNodeBounds(renderedCanvasNodes.value))
const allEdges = computed(() => activePlaybackScene.value ? playbackEdges.value : edges.value)
const renderedCanvasPan = computed(() => activePlaybackScene.value ? playbackCanvasPan.value : canvasPan.value)
const renderedCanvasZoom = computed(() => activePlaybackScene.value ? playbackCanvasZoom.value : canvasZoom.value)
const specifications = ref<Specification[]>([])
const editingRule = ref<RuleForm | null>(null)
const editingSpecification = ref<Specification | null>(null)

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
const hydratedBoardAuthScopeEpoch = ref<number | null>(null)
const hasHydratedBoardSnapshot = computed(() => hydratedBoardAuthScopeEpoch.value === boardAuthScopeEpoch
  && currentAuthUserId.value === boardOwnerUserId)
const failedBoardDataKeys = computed(() =>
  allBoardDataKeys.filter(key => boardDataLoadState[key] === 'error'))
const isBoardDataReady = computed(() =>
  !isAuthScopeTransitioning.value
  && allBoardDataKeys.every(key => boardDataLoadState[key] === 'ready'))
const isInitialBoardLoading = computed(() => !hasHydratedBoardSnapshot.value
  && !isBoardDataReady.value && failedBoardDataKeys.value.length === 0)

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
  || !hasHydratedBoardSnapshot.value)

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

const isRunModelComplete = (result: Pick<SimulationResult, 'modelComplete'> | null): boolean =>
  result?.modelComplete === true

const isRunModelSemanticsConsistent = (
  result: Pick<SimulationResult, 'modelSemantics' | 'isAttack' | 'attackBudget' | 'enablePrivacy'> | null
): boolean => isModelSemanticsConsistent(result?.modelSemantics, result ?? {})

const simulationPlaybackSemanticsConsistent = computed(() =>
  isRunModelSemanticsConsistent(simulationPlaybackResult.value)
)

const notifySimulationOutcome = (result: any, saved: boolean) => {
  if (compareRunToCurrentBoard(result, 'simulation') === 'SEMANTICS_CHANGED') {
    notifyBlocked(t('app.runBoardSemanticsChanged'))
    return
  }
  const stateCount = result?.states?.length || 0
  const disabledRuleCount = getSimulationDisabledRuleCount(result)
  if (!isRunModelComplete(result)) {
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

const readBoardWriteReceipt = <T,>(
  write: () => Promise<T>,
  isCurrent: () => boolean,
  { refreshOnRejection, reconcile = refreshBoardSnapshot }: {
    refreshOnRejection?: (error: unknown) => boolean
    reconcile?: () => Promise<boolean>
  } = {}
) => readWriteReceipt(write, isCurrent, { refreshOnRejection, reconcile })

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
  t(cancelling && statusKey !== 'app.taskStatusCancelled' ? 'app.taskCancelling' : statusKey)

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

const notifyVerificationOutcome = (result: VerificationResult | null, options?: { presenting?: boolean }) => {
  // Suppress the toast when the dialog is already presenting the verdict
  if (options && options.presenting) {
    return
  }
  if (!result) {
    notifyBlocked(t('household.resultUnavailable'))
    return
  }
  if (compareRunToCurrentBoard(result, 'verification') === 'SEMANTICS_CHANGED') {
    notifyBlocked(t('app.runBoardSemanticsChanged'))
    return
  }

  const counts = getGenerationWarningCounts(result)
  const verificationOutcome = getVerificationOutcome(result)
  const modelComplete = isRunModelComplete(result)
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
    const triggerWarnings = countResponseTriggerWarnings(result.specResults)
    if (triggerWarnings > 0) notifyBlocked(t('household.coverageDetail', { count: triggerWarnings }))
    else notifySuccess(t('app.verificationSatisfiedComplete'))
  } else if (verificationOutcome === 'INCONCLUSIVE') {
    notifyBlocked(t('app.verificationInconclusiveSummary'))
  } else {
    notifyBlocked(getVerificationFailureMessage(result))
  }
}

const draggingTplName = ref<string | null>(null)
const templateInstanceDialogVisible = ref(false)
const templateInstanceSaving = ref(false)
const templateInstanceUnconfirmed = ref<string | null>(null)
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

const buildTemplateInstanceRuntimeConfig = (template: DeviceTemplate): DeviceRuntimeConfig | undefined => {
  return buildDeviceRuntimeConfig(template, templateInstanceRuntime, { variableScope: 'local' })
}

const validateTemplateInstanceRuntimeConfig = (template: DeviceTemplate, runtime?: DeviceRuntimeConfig) => {
  return validateDeviceRuntimeConfig(template, runtime, t, { variableScope: 'local' })
}

let boardMutationQueue: Promise<void> = Promise.resolve()
let boardMutationAdmissionEpoch = 0
const pendingNodeLayouts = new Map<string, { layout: DeviceLayout }>()
const activeNodeLayoutInteractions = new Map<string, DeviceLayout | undefined>()
let externallyRefreshedRemovedNodeIds: ReadonlySet<string> | null = null

const replaceNodesFromServer = (
  source: DeviceNode[],
  options: BoardNodeSnapshotOptions = {}
) => {
  const incomingNodes = getVisibleDeviceNodes(source)
  const incomingIds = new Set(incomingNodes.map(node => node.id))
  // A cancelled gesture can reveal a receipt that arrived while its preview owned the node.
  for (const nodeId of activeNodeLayoutInteractions.keys()) {
    const incoming = incomingNodes.find(node => node.id === nodeId)
    activeNodeLayoutInteractions.set(nodeId, incoming && {
      position: { ...incoming.position }, width: incoming.width, height: incoming.height
    })
  }
  externallyRefreshedRemovedNodeIds = options.externalRefresh
    ? new Set(nodes.value.filter(node => node.id !== options.locallyRemovedNodeId && !incomingIds.has(node.id))
      .map(node => node.id))
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
  activeNodeLayoutInteractions.set(nodeId, undefined)
}

const handleNodeLayoutInteractionEnd = (nodeId: string) => {
  const authoritative = activeNodeLayoutInteractions.get(nodeId)
  activeNodeLayoutInteractions.delete(nodeId)
  if (authoritative && !pendingNodeLayouts.has(nodeId)) {
    applyLayoutToNode(resolveNodeRef(nodeId), authoritative)
  }
}

interface BoardMutationQueueOptions {
  admissionGuard?: () => boolean
  trackSemanticChange?: boolean
  onSemanticChange?: () => void
}

type DeviceCreationReceipt =
  | { status: 'confirmed'; devices: DeviceNode[]; environmentChanges: EnvironmentVariableChange[] }
  | { status: 'rejected'; error: unknown }
  | { status: 'unconfirmed'; error: unknown; snapshotRefreshed: boolean; devices: DeviceNode[] }

interface DeviceCreationOptions extends BoardMutationQueueOptions {
  onReceipt?: (receipt: DeviceCreationReceipt) => void
}

const getCurrentRecommendationSceneFingerprint = (
  expectedAuthScopeEpoch: number
): string | null => {
  if (!isCurrentBoardAuthScope(expectedAuthScopeEpoch) || !hasHydratedBoardSnapshot.value) return null
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

const deviceLayoutMatches = (node: DeviceLayout | undefined, layout: DeviceLayout) =>
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
const unconfirmedRenames = reactive(new Map<string, { name: string; message: string }>())
watch(() => [currentAuthUserId.value, authState.token], () => unconfirmedRenames.clear(), { flush: 'sync' })
const renameDialogData = reactive({
  node: null as DeviceNode | null,
  newName: '',
  originalLabel: ''
})
const renameNameConflict = computed(() => nodes.value.some(node =>
  node.id !== renameDialogData.node?.id
  && deviceLabelKey(node.label) === deviceLabelKey(renameDialogData.newName)))
const renameUnconfirmedMessage = computed(() => {
  const write = unconfirmedRenames.get(renameDialogData.node?.id ?? '')
  return write?.name === renameDialogData.newName.trim() ? write.message : ''
})
const renameNameBlockedReason = computed(() => {
  const name = renameDialogData.newName.trim()
  if (!name) return t('app.enterDeviceName')
  if (renameUnconfirmedMessage.value) return renameUnconfirmedMessage.value
  if (name === renameDialogData.originalLabel) return t('app.enterDifferentDeviceName')
  return renameNameConflict.value ? t('app.deviceNameAlreadyExists') : ''
})

const deleteConfirmDialogVisible = ref(false)
const deleteConfirmSubmitting = ref(false)
const deletePreviewLoading = ref(false)
const deleteConfirmReviewSnapshotKey = ref<string | null>(null)
const unconfirmedDeviceDeletions = reactive(new Map<string, string>())
watch(() => [currentAuthUserId.value, authState.token], () => unconfirmedDeviceDeletions.clear(), { flush: 'sync' })
let deletePreviewRequestEpoch = 0
let deletePreviewNodeId: string | null = null
let deleteConfirmSourceDeviceDialogNodeId: string | null = null
const emptyDeleteConfirmDialogData = (node: DeviceNode | null = null) => ({
  node,
  relatedRules: [] as string[],
  relatedSpecs: [] as string[],
  environmentChanges: [] as EnvironmentVariableChange[],
  impactToken: ''
})
const deleteConfirmDialogData = reactive(emptyDeleteConfirmDialogData())
const hasDeletionConsequences = computed(() => Boolean(
  deleteConfirmDialogData.relatedRules.length
  || deleteConfirmDialogData.relatedSpecs.length
  || deleteConfirmDialogData.environmentChanges.length
))
const deleteUnconfirmedMessage = computed(() => unconfirmedDeviceDeletions.get(deleteConfirmDialogData.impactToken))

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
  Object.assign(deleteConfirmDialogData, emptyDeleteConfirmDialogData())
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

const deviceModeCount = (deviceId: string) => {
  const node = resolveNodeRef(deviceId)
  return node ? resolveTemplateForNode(node)?.manifest.Modes?.length : undefined
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
  Math.min(MIN_ZOOM, fittedViewportForBounds(renderedNodeBounds.value, frame)?.zoom ?? MIN_ZOOM)

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
  position: () => { x: number; y: number },
  requestedLabel: string,
  runtime: DeviceRuntimeConfig | undefined,
  options: DeviceCreationOptions & { nameConflictMessage?: () => string } = {}
): Promise<DeviceCreationReceipt | null> => {
  const { nameConflictMessage, ...mutationOptions } = options
  return enqueueDeviceCreation(() => {
    const visibleDevices = getVisibleDeviceNodes()
    if (!ensureBoardDataReady(['nodes', 'templates'])
      || !ensureBoardItemCapacity('devices', visibleDevices.length, 1, REQUEST_LIMITS.devices)
      || !ensureDeviceRuntimeCapacity(runtime)) return null
    const availableName = getUniqueLabel(requestedLabel, visibleDevices)
    if (availableName !== requestedLabel) {
      notifyBlocked(nameConflictMessage?.() ?? t('app.deviceNameChangedBeforeCreate', { name: availableName }))
      return null
    }
    return [{
      id: createDeviceInstanceId(visibleDevices),
      templateName: tpl.manifest.Name,
      label: requestedLabel,
      position: position(),
      state: tpl.manifest.InitState || 'Working',
      width: DEFAULT_NODE_WIDTH,
      height: DEFAULT_NODE_HEIGHT,
      ...(runtime || {})
    }]
  }, [], mutationOptions)
}

const enqueueDeviceCreation = (
  prepare: () => DeviceNode[] | null,
  environmentVariables: ModelEnvironmentVariable[] = [],
  { onReceipt, ...mutationOptions }: DeviceCreationOptions = {}
): Promise<DeviceCreationReceipt | null> => {
  const requestToken = getToken()
  const requestAuthScopeEpoch = boardAuthScopeEpoch
  const isCreateOwnerCurrent = () => isCurrentSessionToken(requestToken) && isCurrentBoardAuthScope(requestAuthScopeEpoch)
  return enqueueBoardMutation<DeviceCreationReceipt | null>(async () => {
    if (!isCreateOwnerCurrent()) return null
    const devices = prepare()
    if (!devices?.length) return null
    const write = await readBoardWriteReceipt(() => boardApi.addNodes(devices, environmentVariables), isCreateOwnerCurrent)
    if (!write) return null
    let receipt: DeviceCreationReceipt
    if (write.status === 'confirmed') {
      const { mutation } = write
      commitSemanticScene({
        nodes: mutation.currentNodes,
        environmentVariables: mutation.environmentVariables,
        specs: mutation.currentSpecifications,
        availability: mutation
      })
      receipt = { status: 'confirmed', devices: mutation.affectedDevices, environmentChanges: mutation.environmentChanges }
    } else if (write.status === 'rejected') {
      receipt = { status: 'rejected', error: write.error }
    } else {
      const requestedIds = new Set(devices.map(device => device.id))
      const observedDevices = write.snapshotRefreshed ? nodes.value.filter(node => requestedIds.has(node.id)) : []
      receipt = { status: 'unconfirmed', snapshotRefreshed: write.snapshotRefreshed, devices: observedDevices, error: write.error }
    }
    if (receipt.status !== 'rejected') {
      const lastRequestedId = devices[devices.length - 1].id
      const lastCreated = receipt.devices.find(device => device.id === lastRequestedId)
      if (lastCreated) await focusCreatedDeviceNode(lastCreated)
    }
    if (!isCreateOwnerCurrent()) return null
    // Recommendation feedback must be available when the queue reconciles scene changes.
    onReceipt?.(receipt)
    return receipt
  }, mutationOptions).catch(error => {
    if (isPollingAbortedError(error) || error instanceof BoardMutationAdmissionCancelledError) return null
    throw error
  })
}

const unconfirmedDeviceCreationMessage = (receipt: Extract<DeviceCreationReceipt, { status: 'unconfirmed' }>) => {
  const device = receipt.devices[0]
  if (device) return t('app.deviceCreateOutcomeRefreshed', { name: device.label })
  return t(receipt.snapshotRefreshed ? 'app.deviceCreateOutcomeNotMatched' : 'app.deviceCreateOutcomeUnknownRefreshFailed')
}

const unconfirmedDeviceCreationFailure = (cause: string): ApiErrorDetails => ({
  reasonCode: 'DEVICE_CREATE_OUTCOME_UNKNOWN', cause, details: [], fields: {}
})

const openTemplateInstanceDialog = (tpl: DeviceTemplate, pos: { x: number; y: number }) => {
  if (!ensurePlaybackClosedForMutation()) return
  templateInstanceDialogData.template = tpl
  templateInstanceDialogData.position = pos
  templateInstanceDialogData.name = getUniqueLabel(tpl.manifest.Name, getVisibleDeviceNodes())
  templateInstanceUnconfirmed.value = null
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
  if (templateInstanceSaving.value || templateInstanceUnconfirmed.value) return
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
  const position = templateInstanceDialogData.position
  try {
    const receipt = await createDeviceInstanceAt(tpl, () => position, availableName, runtime)
    if (!receipt) return
    if (receipt.status === 'rejected') {
      notifyError(localizedErrorMessage(receipt.error, t('app.saveNodesFailed'), locale.value))
      return
    }
    if (receipt.status === 'unconfirmed' && receipt.devices.length === 0) {
      templateInstanceUnconfirmed.value = unconfirmedDeviceCreationMessage(receipt)
      return
    }
    templateInstanceDialogVisible.value = false
    templateInstanceDialogData.template = null
    templateInstanceDialogData.name = ''
    resetTemplateInstanceRuntime(null)
    if (receipt.status === 'unconfirmed') {
      notifyBlocked(unconfirmedDeviceCreationMessage(receipt))
      return
    }
    reportEnvironmentChanges(receipt.environmentChanges)
    if (receipt.devices[0].label !== availableName) {
      notifyBlocked(t('app.deviceNameAdjustedToAvoidConflict', { name: receipt.devices[0].label }))
    }
  } finally {
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
  if (deviceLayoutMatches(pendingNodeLayouts.get(nodeId)?.layout, layout)) return
  const pending = { layout }
  pendingNodeLayouts.set(nodeId, pending)
  const retireLayout = () => {
    if (pendingNodeLayouts.get(nodeId) === pending) pendingNodeLayouts.delete(nodeId)
  }
  const token = getToken()
  const scope = boardAuthScopeEpoch
  const isCurrent = () => isCurrentSessionToken(token) && isCurrentBoardAuthScope(scope)
  try {
    await enqueueBoardMutation(async () => {
      if (!isCurrent() || !nodes.value.some(candidate => candidate.id === nodeId)) return
      let persistedLayoutMatches = false
      const receipt = await readBoardWriteReceipt(() => boardApi.updateNodeLayout(nodeId, layout), isCurrent, {
        // Even a rejection must replace the optimistic layout with the current server board.
        refreshOnRejection: () => true,
        reconcile: () => refreshBoardSnapshot({
          keepBoardReady: true,
          adopt: snapshot => {
            retireLayout()
            persistedLayoutMatches = deviceLayoutMatches(snapshot.nodes.find(candidate => candidate.id === nodeId), layout)
            adoptBoardSnapshot(snapshot)
          }
        })
      })
      if (!receipt) return
      const latest = pendingNodeLayouts.get(nodeId)
      retireLayout()
      if (receipt.status === 'confirmed') {
        const { mutation } = receipt
        commitSemanticScene({
          nodes: mutation.currentNodes,
          availability: mutation,
          // The queue still detects semantic changes from other writers in the snapshot.
          semanticChanged: false
        })
      } else if (!latest || latest === pending) {
        if (receipt.status === 'unconfirmed') {
          notifyBlocked(t(!receipt.snapshotRefreshed ? 'app.deviceLayoutOutcomeUnknownRefreshFailed'
            : persistedLayoutMatches ? 'app.deviceLayoutOutcomeRefreshed' : 'app.deviceLayoutOutcomeNotMatched'))
        } else {
          notifyError(extractApiErrorMessage(receipt.error, t('app.saveNodesFailed')))
        }
      }
    })
  } catch (error) {
    if (!isPollingAbortedError(error)) throw error
  } finally {
    retireLayout()
  }
}

const isRuleDuplicateRejection = (error: unknown) =>
  (error as { response?: { status?: number } })?.response?.status === 409
  && errorDetails(error).reasonCode === 'DUPLICATE_RULE'

const readRuleCreationReceipt = (rule: RuleForm, isCurrent: () => boolean) =>
  readBoardWriteReceipt(() => boardApi.addRule(rule), isCurrent, {
    refreshOnRejection: isRuleDuplicateRejection
  })

const ruleCreateRecoveryMessage = async (rule: RuleForm, snapshotRefreshed: boolean, isCurrent: () => boolean) => {
  if (!snapshotRefreshed || !isCurrent()) return t('app.ruleCreateOutcomeUnknown')
  try {
    if ((await boardApi.checkDuplicateRule(rule)).isDuplicate) return t('app.ruleCreateOutcomeRefreshed')
  } catch (error) {
    console.warn('Rule presence could not be verified after refreshing the Board:', error)
  }
  return t('app.ruleCreateOutcomeUnknownAfterRefresh')
}

const focusCreatedRule = async (id?: string) => {
  try {
    await focusRuleOnCanvas(id)
  } catch (error) {
    console.warn('Failed to focus the saved rule:', error)
  }
}

const saveNewBoardItem = async <T extends { id?: string }>(options: {
  requiredKey: 'rules' | 'specs'
  prepare: () => T | null
  readReceipt: (item: T, isCurrent: () => boolean) => Promise<BoardWriteReceipt<CollectionMutationResult<T>> | null>
  commit: (mutation: CollectionMutationResult<T>) => void
  focus: (id?: string) => Promise<void>
  recoveryMessage: (item: T, refreshed: boolean, isCurrent: () => boolean) => string | Promise<string>
  isDuplicateRejection: (error: unknown) => boolean
  failureKey: string
  complete: (saved: boolean, failure?: ApiErrorDetails) => void
}) => {
  if (!ensurePlaybackClosedForMutation()) {
    options.complete(false)
    return
  }
  const requestToken = getToken()
  const requestAuthScopeEpoch = boardAuthScopeEpoch
  const isRequestCurrent = () => isCurrentSessionToken(requestToken) && isCurrentBoardAuthScope(requestAuthScopeEpoch)
  let saved = false
  let saveFailure: ApiErrorDetails | undefined
  try {
    await enqueueBoardMutation(async () => {
      if (!isRequestCurrent() || !ensurePlaybackClosedForMutation()
        || !ensureBoardDataReady(['nodes', 'templates', options.requiredKey])) return
      const item = options.prepare()
      if (!item) return
      const receipt = await options.readReceipt(item, isRequestCurrent)
      if (!receipt) return
      if (receipt.status === 'confirmed') {
        options.commit(receipt.mutation)
        saved = true
        await options.focus(receipt.mutation.affectedItem?.id)
      } else if (receipt.status === 'unconfirmed') {
        const message = options.recoveryMessage(item, receipt.snapshotRefreshed, isRequestCurrent)
        const cause = typeof message === 'string' ? message : await message
        saveFailure = { reasonCode: BOARD_ITEM_EDIT_OUTCOME_UNKNOWN, cause, details: [], fields: {} }
      } else if (options.isDuplicateRejection(receipt.error)
        || [400, 422].includes(Number((receipt.error as { response?: { status?: number } })?.response?.status))) {
        saveFailure = errorDetails(receipt.error)
      } else {
        notifyError(extractApiErrorMessage(receipt.error, t(options.failureKey)))
      }
    })
  } catch (error) {
    if (isRequestCurrent() && !isPollingAbortedError(error)) {
      notifyError(extractApiErrorMessage(error, t(options.failureKey)))
    }
  } finally {
    options.complete(saved && isRequestCurrent(), isRequestCurrent() ? saveFailure : undefined)
  }
}

const handleAddRule = (request: {
  rule: RuleForm
  complete: (saved: boolean, failure?: ApiErrorDetails) => void
}) => saveNewBoardItem<RuleForm>({
  requiredKey: 'rules', complete: request.complete,
  readReceipt: readRuleCreationReceipt, focus: focusCreatedRule,
  recoveryMessage: ruleCreateRecoveryMessage, isDuplicateRejection: isRuleDuplicateRejection,
  failureKey: 'app.saveRulesFailed',
  commit: mutation => commitSemanticScene({ rules: mutation.currentItems, availability: mutation }),
  prepare: () => {
    if (!ensureBoardItemCapacity('rules', rules.value.length, 1, REQUEST_LIMITS.rules)) return null
    const payload = request.rule
    const { sources, toId, toApi } = payload
    if (!sources || !sources.length || !toId || !toApi) {
      notifyBlocked(t('app.fillAllRuleFields'))
      return null
    }
    if (!ensureNestedItemCapacity(t('app.ruleConditions'), sources.length, REQUEST_LIMITS.ruleConditions)) return null
    if (!assertRulesHaveTriggers([payload]) || !resolveNodeRef(toId)) return null

    return {
      ...payload,
      id: 'rule_' + Date.now(),
      name: payload.name || t('app.automationRule')
    }
  }
})

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

const confirmRuleSimilarityReview = (result: RuleSimilarityResult): boolean | Promise<boolean> => {
  if (result.reasonCode === 'EXACT_MATCH') {
    notifyBlocked(t('app.duplicateRuleExists'))
    return false
  }
  if (!result.requiresReview) return true

  const reason = t(ruleSimilarityReasonKey(result.reasonCode))
  const message = result.matchedRule
    ? t('app.aiSimilarRuleMayExistWithMatch', { rule: result.matchedRule, reason })
    : t('app.aiSimilarRuleMayExist', { reason })

  return confirmChoice({
    title: t('app.aiRuleSimilarityDetected'),
    message,
    confirmText: t('app.applyAnyway')
  })
}

const confirmRecommendedRuleSimilarity = async (candidate: RuleForm): Promise<boolean> => {
  try {
    return await confirmRuleSimilarityReview(await boardApi.checkRuleSimilarity(candidate))
  } catch (error) {
    console.error('AI similarity check failed before applying recommendation:', error)
    return await confirmChoice({
      title: t('app.aiRuleSimilarityDetected'),
      message: t('app.aiSimilarityCheckFailedCanStillApply'),
      confirmText: t('app.applyAnyway')
    })
  }
}

type CheckedRuleRecommendation = { rule: RuleForm; similarity: RuleSimilarityResult }
type RecommendationWriteReceipt = 'confirmed' | undefined

// 应用推荐的规则
const applyRecommendation = async (
  rec: RuleRecommendation, index: number, preflight?: CheckedRuleRecommendation
): Promise<RecommendationWriteReceipt> => {
  const fromBatch = preflight !== undefined
  if (!fromBatch && isRecommendationBatchLocked('rule')) return
  if (!ensurePlaybackClosedForMutation()) return
  if (!ensureBoardDataReady(['nodes', 'templates', 'rules'])) return
  if (appliedRuleRecommendations.value.has(index)) {
    notifyBlocked(t('app.recommendationAlreadyApplied'))
    return
  }
  if (applyingRuleRecommendations.value.has(index) || recommendationApplyFailures.rule[index]?.outcomeUnknown) return

  const recommendationEpoch = ruleRecommendationPanel.epoch
  const requestSceneGeneration = recommendationSceneGeneration
  const applyAuthScopeEpoch = boardAuthScopeEpoch
  const requestToken = getToken()
  const isApplyAccountCurrent = () => isCurrentBoardAuthScope(applyAuthScopeEpoch) && isCurrentSessionToken(requestToken)
  const isApplyCandidateCurrent = () => isApplyAccountCurrent()
    && recommendationEpoch === ruleRecommendationPanel.epoch && requestSceneGeneration === recommendationSceneGeneration
  delete recommendationApplyFailures.rule[index]
  let keepRecommendation = false

  try {
    if (!ensureBoardItemCapacity('rules', rules.value.length, 1, REQUEST_LIMITS.rules)) return
    const newRule = preflight?.rule ?? materializeRuleRecommendation(rec, 'rule_' + Date.now())
    if (!ensureNestedItemCapacity(
      t('app.ruleConditions'), newRule.sources.length, REQUEST_LIMITS.ruleConditions
    )) return
    if (!assertRulesHaveTriggers([newRule])) {
      return
    }
    applyingRuleRecommendations.value.add(index)
    const shouldApply = preflight
      ? await confirmRuleSimilarityReview(preflight.similarity)
      : await confirmRecommendedRuleSimilarity(newRule)
    if (!shouldApply) return
    if (!isApplyCandidateCurrent() || isSceneReplacementInProgress.value) return

    return await enqueueBoardMutation<RecommendationWriteReceipt>(async () => {
      if (!isApplyCandidateCurrent()
        || !isBoardDataReady.value
        || isSceneReplacementInProgress.value) return
      const receipt = await readRuleCreationReceipt(newRule, isApplyAccountCurrent)
      if (!receipt) return
      if (receipt.status !== 'confirmed') {
        if (receipt.status === 'unconfirmed') {
          const message = await ruleCreateRecoveryMessage(newRule, receipt.snapshotRefreshed, isApplyAccountCurrent)
          if (!isApplyCandidateCurrent()) return
          keepRecommendation = true
          reportRecommendationApplyFailure('rule', index, receipt.error, message, true)
        } else if (isApplyCandidateCurrent()) {
          if (isRuleDuplicateRejection(receipt.error)) notifyBlocked(t('app.duplicateRuleExists'))
          else reportRecommendationApplyFailure('rule', index, receipt.error, t('app.failedToApplyRule'))
        }
        return
      }

      const { mutation } = receipt
      commitSemanticScene({ rules: mutation.currentItems, availability: mutation })
      keepRecommendation = true
      if (isApplyCandidateCurrent()) {
        appliedRuleRecommendations.value.add(index)
        // Focusing the inspector would hide a batch's progress on narrow screens.
        if (!fromBatch) await focusCreatedRule(mutation.affectedItem?.id)
      }
      return 'confirmed'
    }, {
      onSemanticChange: () => handleRecommendationApplySceneChange(
        keepRecommendation,
        () => preserveAppliedRecommendationAfterSceneChange('rule', index),
        () => invalidateRecommendationsForSceneChange({ notify: true })
      )
    })
  } catch (error) {
    if (!isApplyCandidateCurrent() || isPollingAbortedError(error)) return
    if (error instanceof RecommendationCandidateError) {
      notifyBlocked(t('app.recommendationInvalidFieldNoChange', { field: error.field }))
      return
    }
    reportRecommendationApplyFailure('rule', index, error, t('app.failedToApplyRule'))
  } finally {
    if (recommendationEpoch === ruleRecommendationPanel.epoch) {
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

const isDeviceRenameConflict = (error: any) => error?.response?.status === 409

const handleRenameDevice = async (
  nodeId: string,
  newLabel: string,
  expectedLabel: string
): Promise<BoardWriteReceipt<Awaited<ReturnType<typeof boardApi.renameNode>>> | null> => {
  if (!ensurePlaybackClosedForMutation() || !ensureBoardDataReady(['nodes', 'specs'])) return null
  const token = getToken()
  const scope = boardAuthScopeEpoch
  const isCurrent = () => isCurrentSessionToken(token) && isCurrentBoardAuthScope(scope)
  const receipt = await enqueueBoardMutation(async () => {
    if (!isCurrent() || !ensureBoardDataReady(['nodes', 'specs'])) return null
    const requestedLabelKey = deviceLabelKey(newLabel)
    const exists = nodes.value.some(n => deviceLabelKey(n.label) === requestedLabelKey && n.id !== nodeId)
    if (exists) {
      notifyError(t('app.nameExists'))
      return null
    }
    if (!nodes.value.some(node => node.id === nodeId)) return null

    const receipt = await readBoardWriteReceipt(
      () => boardApi.renameNode(nodeId, newLabel, expectedLabel), isCurrent, { refreshOnRejection: isDeviceRenameConflict })
    if (receipt?.status === 'confirmed') {
      const { mutation } = receipt
      commitSemanticScene({
        nodes: mutation.currentNodes,
        environmentVariables: mutation.environmentVariables,
        specs: mutation.currentSpecifications,
        availability: mutation
      })
    }
    return receipt
  })
  return isCurrent() ? receipt : null
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
  const isRuntimeConflict = (error: unknown) => errorDetails(error).reasonCode === 'DEVICE_RUNTIME_STALE'

  deviceRuntimeSaving.value = true
  try {
    await enqueueBoardMutation(async () => {
      if (!isRequestCurrent() || !ensureBoardDataReady(['nodes', 'templates'])) return
      const receipt = await readBoardWriteReceipt(() => boardApi.updateNodeRuntime(nodeId, {
        expected: expectedRuntime,
        desired: runtimeRequest
      }), isRequestCurrent, { refreshOnRejection: isRuntimeConflict })
      if (!receipt) return
      if (receipt.status === 'confirmed') {
        const { mutation } = receipt
        commitSemanticScene({
          nodes: mutation.currentNodes,
          availability: mutation,
          semanticChanged: mutation.operation === 'updated'
        })
        saved = true
        notifySuccess(mutation.operation === 'updated'
          ? t('app.instanceConfigSaved')
          : t('app.instanceConfigUnchanged'))
      } else if (receipt.status === 'unconfirmed') {
        const persisted = nodes.value.find(candidate => candidate.id === nodeId)
        const message = t(!receipt.snapshotRefreshed ? 'app.deviceRuntimeOutcomeUnknownRefreshFailed'
          : deviceRuntimeMatches(persisted, runtimeRequest, template)
            ? 'app.deviceRuntimeOutcomeRefreshed' : 'app.deviceRuntimeOutcomeNotMatched')
        if (complete) saveFailure = { reasonCode: 'DEVICE_WRITE_OUTCOME_UNKNOWN', cause: message, details: [], fields: {} }
        else notifyBlocked(message)
      } else if (isRuntimeConflict(receipt.error)) {
        notifyBlocked(t(receipt.snapshotRefreshed ? 'app.deviceRuntimeStaleRefreshed' : 'app.deviceRuntimeStaleRefreshFailed'))
      } else if (complete) {
        saveFailure = errorDetails(receipt.error)
      } else {
        notifyError(extractApiErrorMessage(receipt.error, t('app.instanceConfigSaveFailed')))
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
    const message = deleteUnconfirmedMessage.value ?? t('app.deviceDeletionPreviewChanged')
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
    notifyBlocked(message)
  },
  { flush: 'sync' }
)


const forceDeleteNode = async (
  nodeId: string,
  impactToken: string,
  reviewedSnapshotKey: string
) => {
  if (!ensureBoardDataReady(['nodes', 'environment', 'rules', 'specs'])) return null
  const token = getToken()
  const scope = boardAuthScopeEpoch
  const isCurrent = () => isCurrentSessionToken(token) && isCurrentBoardAuthScope(scope)
  return enqueueBoardMutation(async () => {
    if (!isCurrent() || !ensureBoardDataReady(['nodes', 'environment', 'rules', 'specs'])
      || reviewedSnapshotKey !== deleteConfirmReviewSnapshotKey.value
      || reviewedSnapshotKey !== currentDeletionReviewSnapshotKey()) return null

    const bundledEnvironmentNamesBeforeDelete = [...bundledBoardEnvironmentNames.value]
    const receipt = await readBoardWriteReceipt(() => boardApi.deleteNode(nodeId, impactToken), isCurrent, {
      refreshOnRejection: (error: any) => [404, 409].includes(error?.response?.status),
      reconcile: () => refreshBoardSnapshot({ adopt: snapshot => adoptBoardSnapshot(snapshot, nodeId) })
    })
    if (!receipt) return null
    if (receipt.status === 'confirmed') {
      const { mutation } = receipt
      adoptBoardSnapshot(mutation.snapshot, nodeId)
      // Suppress individual environment change notifications; the summary notification below
      // already includes the environment variable count, so separate toasts would be redundant.
      reportEnvironmentChanges(mutation.environmentChanges, bundledEnvironmentNamesBeforeDelete, true)
    }
    return {
      receipt,
      targetMissing: receipt.status !== 'confirmed' && receipt.snapshotRefreshed
        && !nodes.value.some(node => node.id === nodeId)
    }
  })
}

const deleteCurrentNodeWithConfirm = async (nodeId: string) => {
  if (deletePreviewLoading.value || deleteConfirmSubmitting.value || deleteConfirmDialogVisible.value) return
  if (!ensurePlaybackClosedForMutation()) return
  if (!ensureBoardDataReady(['templates', 'nodes', 'environment', 'rules', 'specs'])) return
  const currentNode = resolveCurrentBoardNode(nodes.value, nodeId)
  if (!currentNode) return

  deleteDialogReturnFocusNodeId = nodeId
  deleteConfirmSourceDeviceDialogNodeId = dialogVisible.value && dialogMeta.nodeId === nodeId
    ? nodeId
    : null
  const requestEpoch = ++deletePreviewRequestEpoch
  deletePreviewNodeId = nodeId
  const ownsPreview = () => requestEpoch === deletePreviewRequestEpoch && deletePreviewNodeId === nodeId
  deletePreviewLoading.value = true
  deleteConfirmReviewSnapshotKey.value = null
  Object.assign(deleteConfirmDialogData, emptyDeleteConfirmDialogData(currentNode))
  deleteConfirmDialogVisible.value = true
  try {
    await enqueueBoardMutation(async () => {
      const preview = await boardApi.previewNodeDeletion(nodeId)
      if (!ownsPreview()) return
      adoptBoardSnapshot(preview.snapshot)
      if (!ownsPreview()) return
      const { removedRules, removedSpecifications, environmentChanges } = preview
      deleteConfirmDialogData.node = preview.deletedDevice
      deleteConfirmDialogData.relatedRules = removedRules.map((rule, index) =>
        rule.name || t('app.ruleNumber', { number: index + 1 }))
      deleteConfirmDialogData.relatedSpecs = removedSpecifications.map(spec => spec.name)
      deleteConfirmDialogData.environmentChanges = environmentChanges
      deleteConfirmDialogData.impactToken = preview.impactToken.trim()
      deleteConfirmReviewSnapshotKey.value = currentDeletionReviewSnapshotKey()
    })
  } catch (error: any) {
    if (!ownsPreview()) return
    if (error?.response?.status === 404) {
      await enqueueBoardMutation(async () => {
        if (ownsPreview()) await refreshBoardSnapshot()
      }).catch(() => undefined)
      if (!ownsPreview()) return
    }
    clearDeleteConfirmDialog()
    notifyError(localizedErrorMessage(error, t('app.deviceDeletionPreviewFailed'), locale.value))
  } finally {
    if (ownsPreview()) {
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
  const nodeId = renameDialogData.node.id
  const newLabel = renameDialogData.newName.trim()
  const expectedLabel = renameDialogData.originalLabel
  try {
    const receipt = await handleRenameDevice(nodeId, newLabel, expectedLabel)
    if (!receipt || !renameDialogVisible.value || renameDialogData.node?.id !== nodeId) return
    if (receipt.status === 'confirmed') {
      unconfirmedRenames.delete(nodeId)
      renameDialogVisible.value = false
      renameDialogData.node = null
      renameDialogData.newName = ''
      renameDialogData.originalLabel = ''
      reportEnvironmentChanges(receipt.mutation.environmentChanges)
      return
    }
    const currentNode = receipt.snapshotRefreshed ? nodes.value.find(node => node.id === nodeId) : undefined
    if (currentNode) {
      renameDialogData.node = currentNode
      renameDialogData.originalLabel = currentNode.label
    }
    if (receipt.status === 'unconfirmed') {
      const message = !receipt.snapshotRefreshed ? t('app.deviceRenameOutcomeUnknownRefreshFailed')
        : !currentNode ? t('app.deviceRenameOutcomeMissing')
          : t(currentNode.label === newLabel ? 'app.deviceRenameOutcomeRefreshed' : 'app.deviceRenameOutcomeNotMatched', {
              name: currentNode.label
            })
      unconfirmedRenames.set(nodeId, { name: newLabel, message })
      if (receipt.snapshotRefreshed && !currentNode) notifyBlocked(message)
    } else if (isDeviceRenameConflict(receipt.error)) {
      if (currentNode && currentNode.label !== expectedLabel) {
        notifyBlocked(t('app.deviceRenameConflictRefreshed', { name: currentNode.label }))
      } else if (!receipt.snapshotRefreshed) {
        notifyBlocked(t('app.deviceRenameConflictRefreshFailed'))
      }
    } else {
      notifyError(extractApiErrorMessage(receipt.error, t('app.saveNodesFailed')))
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
  if (deleteConfirmSubmitting.value || deleteUnconfirmedMessage.value) return
  if (!ensurePlaybackClosedForMutation()) return
  const reviewedSnapshotKey = deleteConfirmReviewSnapshotKey.value
  if (deletePreviewLoading.value
    || !deleteConfirmDialogData.node
    || !reviewedSnapshotKey
    || !deleteConfirmDialogData.impactToken.trim()) return

  const deletion = {
    nodeId: deleteConfirmDialogData.node.id,
    nodeName: deleteConfirmDialogData.node.label,
    impactToken: deleteConfirmDialogData.impactToken,
    ruleCount: deleteConfirmDialogData.relatedRules.length,
    specCount: deleteConfirmDialogData.relatedSpecs.length,
    environmentChangeCount: deleteConfirmDialogData.environmentChanges.length
  }
  const token = getToken()
  const scope = boardAuthScopeEpoch
  const isCurrent = () => isCurrentSessionToken(token) && isCurrentBoardAuthScope(scope)
  invalidateDeletePreview()
  deleteConfirmSubmitting.value = true
  let reopenDeletionPreview = false
  try {
    const outcome = await forceDeleteNode(deletion.nodeId, deletion.impactToken, reviewedSnapshotKey)
    if (!outcome || !isCurrent()) return
    const { receipt, targetMissing } = outcome
    if (receipt.status === 'confirmed') {
      clearDeleteConfirmDialog()
      notifySuccess(t('app.deviceDeleteSuccessSummary', {
        name: deletion.nodeName,
        rules: deletion.ruleCount,
        specs: deletion.specCount,
        variables: deletion.environmentChangeCount
      }))
      return
    }

    if (receipt.status === 'unconfirmed') {
      if (targetMissing) {
        clearDeleteConfirmDialog()
        notifyBlocked(t('app.deviceDeleteOutcomeRefreshed'))
      } else {
        unconfirmedDeviceDeletions.set(deletion.impactToken, t(receipt.snapshotRefreshed
          ? 'app.deviceDeleteOutcomeUnconfirmedAfterRefresh' : 'app.deviceDeleteOutcomeUnknownRefreshFailed'))
      }
      return
    }

    const status = (receipt.error as { response?: { status?: number } })?.response?.status
    const message = extractApiErrorMessage(receipt.error, t('app.deleteDeviceFailedRetry'))
    if (status === 409) {
      clearDeleteConfirmDialog()
      reopenDeletionPreview = receipt.snapshotRefreshed && !targetMissing
      notifyBlocked(receipt.snapshotRefreshed ? message : t('app.deviceDeletionPreviewRefreshFailed'))
    } else if (status === 404 && targetMissing) {
      clearDeleteConfirmDialog()
      notifyBlocked(t('app.deviceDeletionTargetMissing'))
    } else {
      notifyError(message)
    }
  } catch (error) {
    if (!isCurrent()) return
    console.error('Failed to delete device:', error)
    notifyError(t('app.deleteDeviceFailedRetry'))
  } finally {
    deleteConfirmSubmitting.value = false
    if (reopenDeletionPreview && isCurrent()) void deleteCurrentNodeWithConfirm(deletion.nodeId)
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

const authoredSpecificationConfirmationSnapshot = (specification: Specification) => ({
  id: normalizeConfirmationText(specification.id),
  name: normalizeConfirmationText(specification.name),
  logic: buildSpecificationSemanticKey(specification)
})

type BoardItemDeleteKind = 'rule' | 'spec'
const pendingBoardItemDeletes = new Set<string>()
const unconfirmedBoardItemDeletes = reactive(new Map<string, { generation: number; snapshot: string; message: string }>())

const boardItemDeletionWarning = (kind: BoardItemDeleteKind, id: string): string | undefined => {
  const pending = unconfirmedBoardItemDeletes.get(`${kind}:${id}`)
  if (!pending || pending.generation !== boardSceneGeneration) return
  const rule = kind === 'rule' ? rules.value.find(rule => rule.id === id) : undefined
  const spec = kind === 'spec' ? specifications.value.find(spec => spec.id === id) : undefined
  const snapshot = rule ? authoredRuleConfirmationSnapshot(rule)
    : spec ? authoredSpecificationConfirmationSnapshot(spec) : undefined
  if (snapshot && pending.snapshot === JSON.stringify(snapshot)) return pending.message
}

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

const deleteBoardItem = async <T extends { id?: string }>(options: {
  kind: BoardItemDeleteKind
  id: string
  items: () => T[]
  requiredKey: 'rules' | 'specs'
  snapshotOf: (item: T) => unknown
  confirmTitle: string
  confirmMessage: (item: T) => string
  changedMessage: string
  successMessage: string
  missingAfterRecoveryMessage: string
  failedMessage: string
  remove: (item: T) => Promise<CollectionMutationResult<T>>
  commit: (mutation: CollectionMutationResult<T>) => void
}) => {
  const { kind, id, items, requiredKey, snapshotOf } = options
  if (!ensurePlaybackClosedForMutation() || !ensureBoardDataReady([requiredKey])) return
  const item = items().find(candidate => candidate.id === id)
  const pendingKey = `${kind}:${id}`
  if (!item || pendingBoardItemDeletes.has(pendingKey) || boardItemDeletionWarning(kind, id)) return

  const generation = boardSceneGeneration
  const expected = deepClone(item)
  const token = getToken()
  const scope = boardAuthScopeEpoch
  const ownsRequest = () => isCurrentSessionToken(token) && isCurrentBoardAuthScope(scope)
  const confirmationIsCurrent = () => ownsRequest() && isConfirmedBoardItemCurrent(
    generation, items(), id, expected, options.changedMessage, snapshotOf
  )

  pendingBoardItemDeletes.add(pendingKey)
  try {
    if (!await confirmDestructive({
      title: options.confirmTitle,
      message: options.confirmMessage(expected),
      confirmText: t('app.delete')
    }) || !confirmationIsCurrent()) return

    await enqueueBoardMutation(async () => {
      if (!confirmationIsCurrent() || !ensureBoardDataReady([requiredKey])) return
      const receipt = await readBoardWriteReceipt(() => options.remove(expected), ownsRequest, {
        refreshOnRejection: (error: any) => [404, 409].includes(error?.response?.status)
      })
      if (!receipt) return
      if (receipt.status === 'confirmed') {
        options.commit(receipt.mutation)
        unconfirmedBoardItemDeletes.delete(pendingKey)
        notifySuccess(options.successMessage)
        return
      }

      const itemMissing = receipt.snapshotRefreshed && !items().some(candidate => candidate.id === id)
      if (receipt.status === 'unconfirmed') {
        if (itemMissing) {
          unconfirmedBoardItemDeletes.delete(pendingKey)
          notifyBlocked(options.missingAfterRecoveryMessage)
        } else {
          const message = t(receipt.snapshotRefreshed ? 'app.boardItemDeleteStillPresent' : 'app.boardItemDeleteOutcomeUnknown')
          unconfirmedBoardItemDeletes.set(pendingKey, { generation, snapshot: JSON.stringify(snapshotOf(expected)), message })
          if (!boardItemDeletionWarning(kind, id)) notifyBlocked(message)
        }
        return
      }

      const status = (receipt.error as { response?: { status?: number } })?.response?.status
      if ((status === 404 || status === 409) && !receipt.snapshotRefreshed) {
        notifyBlocked(t('app.boardItemDeleteConflictRefreshFailed'))
      } else if (status === 409) {
        notifyBlocked(t('app.boardItemDeleteConflictRefreshed'))
      } else if (status === 404 && itemMissing) {
        notifyBlocked(t('app.boardItemDeleteTargetMissing'))
      } else {
        notifyError(localizedErrorMessage(receipt.error, options.failedMessage, locale.value))
      }
    })
  } catch (error) {
    if (!isPollingAbortedError(error)) throw error
  } finally {
    pendingBoardItemDeletes.delete(pendingKey)
  }
}

const deleteRule = (id: string) => deleteBoardItem({
  kind: 'rule', id, items: () => rules.value, requiredKey: 'rules', snapshotOf: authoredRuleConfirmationSnapshot,
  confirmTitle: t('app.deleteRuleConfirmTitle'),
  confirmMessage: rule => t('app.deleteRuleConfirmMessage', { name: rule.name || t('app.unnamedRule') }),
  changedMessage: t('app.ruleChangedBeforeDelete'), successMessage: t('app.deleteRuleSuccess'),
  missingAfterRecoveryMessage: t('app.ruleDeleteOutcomeRefreshed'), failedMessage: t('app.deleteRuleFailed'),
  remove: boardApi.removeRule,
  commit: mutation => commitSemanticScene({ rules: mutation.currentItems, availability: mutation })
})

const deleteSpecification = (id: string) => deleteBoardItem({
  kind: 'spec', id, items: () => specifications.value, requiredKey: 'specs', snapshotOf: authoredSpecificationConfirmationSnapshot,
  confirmTitle: t('app.deleteSpecConfirmTitle'),
  confirmMessage: spec => t('app.deleteSpecConfirmMessage', { name: spec.name }),
  changedMessage: t('app.specificationChangedBeforeDelete'), successMessage: t('app.deleteSpecSuccess'),
  missingAfterRecoveryMessage: t('app.specDeleteOutcomeRefreshed'), failedMessage: t('app.deleteSpecFailed'),
  remove: boardApi.removeSpec,
  commit: mutation => commitSemanticScene({ specs: mutation.currentItems, availability: mutation })
})

const ruleExecutionOrder = computed(() => rules.value.map(rule => String(rule.id || '')))
const sameRuleOrder = (left: string[], right: string[]) =>
  left.length === right.length && left.every((id, index) => id === right[index])
const unconfirmedRuleOrder = ref<string[] | null>(null)
const ruleOrderBlockedReason = computed(() => unconfirmedRuleOrder.value ? t('app.ruleOrderUnconfirmed') : '')

watch(ruleExecutionOrder, order => {
  if (unconfirmedRuleOrder.value && !sameRuleOrder(order, unconfirmedRuleOrder.value)) {
    unconfirmedRuleOrder.value = null
  }
})

const moveRule = async (ruleId: string, direction: 'up' | 'down') => {
  if (rulesReordering.value || ruleOrderBlockedReason.value || !ensurePlaybackClosedForMutation()) return
  if (!ensureBoardDataReady(['rules'])) return
  const expectedOrder = ruleExecutionOrder.value
  const currentIndex = expectedOrder.indexOf(ruleId)
  const targetIndex = direction === 'up' ? currentIndex - 1 : currentIndex + 1
  if (currentIndex < 0 || targetIndex < 0 || targetIndex >= expectedOrder.length) return

  // The arrow is relative to the order the user saw when they clicked.
  const requestedOrder = [...expectedOrder]
  requestedOrder[currentIndex] = expectedOrder[targetIndex]
  requestedOrder[targetIndex] = ruleId
  const generation = boardSceneGeneration
  const token = getToken()
  const authScope = boardAuthScopeEpoch
  const ownsRequest = () => isCurrentSessionToken(token) && isCurrentBoardAuthScope(authScope)
  const isConflict = (error: unknown) => (error as { response?: { status?: number } })?.response?.status === 409

  rulesReordering.value = true
  try {
    await enqueueBoardMutation(async () => {
      if (!ownsRequest() || !ensurePlaybackClosedForMutation() || !ensureBoardDataReady(['rules'])) return
      if (generation !== boardSceneGeneration || !sameRuleOrder(ruleExecutionOrder.value, expectedOrder)) {
        notifyBlocked(t('app.ruleOrderChanged'))
        return
      }

      const receipt = await readBoardWriteReceipt(() => boardApi.reorderRules(expectedOrder, requestedOrder),
        ownsRequest, { refreshOnRejection: isConflict })
      if (!receipt) return
      if (receipt.status === 'confirmed') {
        commitSemanticScene({ rules: receipt.mutation.rules, availability: receipt.mutation })
        // Re-cue the moved rule so a repeated press stays oriented; each press restarts its lifetime.
        focusHighlight.show('rule', ruleId)
        notifySuccess(t('app.ruleOrderUpdated'))
        return
      }
      if (receipt.status === 'unconfirmed') {
        const currentOrder = ruleExecutionOrder.value
        if (!receipt.snapshotRefreshed || sameRuleOrder(currentOrder, expectedOrder)) {
          unconfirmedRuleOrder.value = expectedOrder
        } else {
          if (sameRuleOrder(currentOrder, requestedOrder)) focusHighlight.show('rule', ruleId)
          notifyBlocked(t('app.ruleOrderOutcomeRefreshed'))
        }
      } else if (isConflict(receipt.error)) {
        notifyBlocked(t(receipt.snapshotRefreshed ? 'app.ruleOrderChanged' : 'app.ruleOrderConflictRefreshFailed'))
      } else {
        notifyError(localizedErrorMessage(receipt.error, t('app.ruleOrderUpdateFailed'), locale.value))
      }
    })
  } catch (error) {
    if (!isPollingAbortedError(error)) throw error
  } finally {
    rulesReordering.value = false
  }
}

watch(() => [authState.token, authState.user?.userId], () => {
  unconfirmedBoardItemDeletes.clear()
  unconfirmedRuleOrder.value = null
  unconfirmedEnvironmentPatches.clear()
  unconfirmedBoardReplacementTokens.clear()
  unconfirmedUndoHistoryClearTokens.clear()
}, { flush: 'sync' })

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
    layoutSaveErrorShown = false
    return true
  } catch (error) {
    const mayShowFeedback = !request.silent
      && !layoutSaveFeedbackSuppressed
      && !boardLifecycleDisposed
      && currentAuthUserId.value === boardOwnerUserId
      && isCurrentSessionToken(request.authToken)
    if (mayShowFeedback) {
      console.error('Could not confirm canvas layout save', error)
      if (!layoutSaveErrorShown) {
        layoutSaveErrorShown = true
        if (isDefinitiveMutationRejection(error)) notifyError(t('app.saveLayoutFailed'))
        else notifyBlocked(t('app.boardLayoutSaveUnconfirmed'))
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
  // The synchronous watcher also sees the wide layout being restored before the resize finishes.
  if (!layoutHydrated.value || boardLifecycleDisposed || !getToken() || isNarrowViewport() || wasNarrowViewport) return
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

const controlCenterRef = ref<InstanceType<typeof ControlCenter> | null>(null)
const openControlSection = (section: InspectorSection) => {
  if (section === 'rules') editingRule.value = null
  if (section === 'specs') editingSpecification.value = null
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
  controlCenterRef.value?.openCreation(controlSection)
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
        fromPos: fromPoint,
        toPos: toPoint,
        fromApi: source.fromApi || '',
        toApi: rule.toApi || '',
        itemType: source.itemType,
        relation: source.relation || '',
        value: source.value === null || source.value === undefined ? '' : String(source.value),
        ruleId: rule.id,
        ruleIndex
      })
    }
  }

  return result
}

const syncRuleDerivedEdges = () => {
  edges.value = generateEdgesFromRules()
}


const replaceTemplateState = (state: {
  templates: DeviceTemplate[]
  nodes: DeviceNode[]
  environmentVariables: ModelEnvironmentVariable[]
}) => {
  deviceTemplates.value = state.templates
  environmentVariables.value = state.environmentVariables
  replaceNodesFromServer(state.nodes)
  reconcileDanglingBoardFocus({ nodes: nodes.value })
  boardDataLoadState.nodes = 'ready'
  boardDataLoadState.templates = 'ready'
  boardDataLoadState.environment = 'ready'
  void refreshBoardIfEditHistoryChanged()
}

const replaceTemplateCatalog = (templates: DeviceTemplate[]) => {
  deviceTemplates.value = templates
  boardDataLoadState.templates = 'ready'
  void refreshBoardIfEditHistoryChanged()
}

const handleAuthoritativeBoardStateUnavailable = (
  keys: Array<'nodes' | 'templates' | 'environment'>
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

const adoptBoardSnapshot = (snapshot: BoardWorkspaceSnapshot, locallyRemovedNodeId?: string) => {
  adoptBoardComparisonSnapshot(() => {
    deviceTemplates.value = snapshot.deviceTemplates
    commitSemanticScene({
      nodes: snapshot.nodes,
      nodeSnapshotOptions: { externalRefresh: true, locallyRemovedNodeId },
      environmentVariables: snapshot.environmentVariables,
      rules: snapshot.rules,
      specs: snapshot.specifications,
      availability: snapshot.editHistory,
      semanticChanged: false
    })
    hydratedBoardAuthScopeEpoch.value = boardAuthScopeEpoch
    allBoardDataKeys.forEach(key => { boardDataLoadState[key] = 'ready' })
    boardSnapshotError.value = null
  })
}

const refreshBoardSnapshot = async ({
  adopt = adoptBoardSnapshot,
  keepBoardReady = false
}: {
  adopt?: (snapshot: BoardWorkspaceSnapshot) => void
  keepBoardReady?: boolean
} = {}): Promise<boolean> => {
  const authScopeEpoch = boardAuthScopeEpoch
  const ownsSnapshot = () => isCurrentBoardAuthScope(authScopeEpoch)
    && currentAuthUserId.value === boardOwnerUserId
  if (!ownsSnapshot()) return false
  boardSnapshotError.value = null
  // Layout recovery is serialized with subsequent edits. Keeping the hydrated view ready
  // lets a drag or resize already in progress reach its release or cancellation.
  if (!keepBoardReady) {
    invalidateCurrentBoardModelFingerprints()
    allBoardDataKeys.forEach(key => { boardDataLoadState[key] = 'loading' })
    templatesLoading.value = !hasHydratedBoardSnapshot.value
  }
  try {
    const snapshot = await boardApi.getSnapshot()
    if (!ownsSnapshot()) return false
    adopt(snapshot)
    return true
  } catch (error) {
    if (!ownsSnapshot()) return false
    console.error('Failed to load the canvas semantic snapshot:', error)
    if (keepBoardReady) invalidateCurrentBoardModelFingerprints()
    allBoardDataKeys.forEach(key => { boardDataLoadState[key] = 'error' })
    boardSnapshotError.value = errorDetails(error)
    return false
  } finally {
    if (ownsSnapshot()) templatesLoading.value = false
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
    .catch(error => {
      if (isPollingAbortedError(error)) return false
      throw error
    })
    .finally(() => {
      boardForegroundRefreshPromise = null
      if (boardRefreshRequestedWhileBusy && !boardLifecycleDisposed
        && document.visibilityState !== 'hidden') {
        void requestBoardSnapshotRefresh()
      }
    })
  boardForegroundRefreshPromise = refreshPromise
  return refreshPromise
}

const refreshBoardOnForeground = () => {
  if (!boardForegroundRefreshPromise) invalidateCurrentBoardModelFingerprints()
  requestBoardSnapshotRefresh({ queueIfBusy: false })
}

const boardInvalidationBinding = createScopedBoardInvalidationBinding(
  subscribeBoardInvalidation,
  message => {
    if (message.scope === 'run-history') {
      void refreshRunHistory()
      return
    }
    if (message.scope === 'tasks') {
      void loadTaskInbox({ showLoading: false })
      return
    }
    // The pending write may already return the external edit in its snapshot, leaving
    // no later refresh delta. Stop the old cohort when the external change is announced.
    if (recommendationBatch.value?.running) invalidateRecommendationsForSceneChange({ notify: true })
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
  const valueKey = (variable: Omit<ModelEnvironmentVariable, 'name'>) => [
    variable.value?.trim(), variable.trust?.trim().toLowerCase(), variable.privacy?.trim().toLowerCase()
  ]
  const orderedPatches = [...patches].sort((left, right) => left.name.localeCompare(right.name))
  const patchKey = JSON.stringify(orderedPatches.map(({ name, expected, desired }) => [
    name.trim(), valueKey(expected), valueKey(desired)
  ]))
  const currentValues = () => {
    const byName = new Map(environmentVariables.value.map(variable => [variable.name, variable]))
    return JSON.stringify(orderedPatches.map(({ name }) => valueKey(byName.get(name.trim()) ?? {})))
  }
  const unconfirmedFailure = (snapshotRefreshed: boolean): ApiErrorDetails => ({
    reasonCode: BOARD_ITEM_EDIT_OUTCOME_UNKNOWN,
    cause: t(snapshotRefreshed ? 'app.environmentSaveOutcomeRefreshed' : 'app.environmentSaveOutcomeUnknownRefreshFailed'),
    details: [], fields: {}
  })
  const isStaleRejection = (error: unknown) => errorDetails(error).reasonCode === 'ENVIRONMENT_VARIABLE_STALE'
  environmentMutationPending.value = true
  let saved = false
  let saveFailure: ApiErrorDetails | undefined
  try {
    await enqueueBoardMutation(async () => {
      if (!isRequestCurrent() || !ensurePlaybackClosedForMutation()
        || !ensureBoardDataReady(['nodes', 'templates', 'environment'])) return
      const previousRecovery = unconfirmedEnvironmentPatches.get(patchKey)
      if (previousRecovery) {
        // Undo or a later edit to these variables ends the recovery for this submission.
        if (previousRecovery.currentValues === currentValues()) {
          saveFailure = unconfirmedFailure(previousRecovery.snapshotRefreshed)
          return
        }
        unconfirmedEnvironmentPatches.delete(patchKey)
      }
      const receipt = await readBoardWriteReceipt(() => boardApi.saveEnvironment(patches), isRequestCurrent, {
        // A conflict can also mean that the device which supplied the variable was removed.
        refreshOnRejection: isStaleRejection
      })
      if (!receipt) return
      if (receipt.status !== 'confirmed') {
        if (receipt.status === 'unconfirmed') {
          unconfirmedEnvironmentPatches.set(patchKey, {
            currentValues: currentValues(), snapshotRefreshed: receipt.snapshotRefreshed
          })
          saveFailure = unconfirmedFailure(receipt.snapshotRefreshed)
        } else if (isStaleRejection(receipt.error)) {
          saveFailure = { ...errorDetails(receipt.error), cause: t(receipt.snapshotRefreshed
            ? 'app.environmentVariableStaleRefreshed' : 'app.environmentVariableStaleRefreshFailed') }
        } else {
          saveFailure = errorDetails(receipt.error)
        }
        return
      }

      const { mutation } = receipt
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
      reportEnvironmentChanges(mutation.environmentChanges.filter(change => !changedPatchNames.has(change.name)))
    })
  } catch (error) {
    if (!isPollingAbortedError(error)) throw error
  } finally {
    environmentMutationPending.value = false
    if (isRequestCurrent()) complete(saved, saveFailure)
  }
}

// Portable-scene normalization/validation/canonicalization lives in board/portableScene.ts as
// pure functions; the codec binds the translator once so rejection messages stay localized.
const {
  requireIntegerInRange,
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
  const templateCounts = summarizeTemplateUsage(getTemplateUsage(deviceTemplates.value, scene.devices))
  notifySuccess(t('app.sceneExportStarted', {
    devices: scene.devices.length,
    variables: scene.environmentVariables.length,
    rules: scene.rules.length,
    specs: scene.specs.length,
    templates: templateCounts.used,
    customTemplates: templateCounts.usedCustom
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

  const acknowledgeImportError = (title: string, content: VNode[], tone: 'error' | 'warning' = 'error') =>
    acknowledge({
      title,
      tone,
      message: h('div', { class: 'space-y-3 text-left' }, content),
      confirmText: t('app.confirm')
    })

  const renderRawDetails = () => h('details', { class: 'mt-3 text-xs', style: { color: 'var(--text-muted)' } }, [
    h('summary', { class: 'cursor-pointer font-semibold' }, t('app.technicalDetails')),
    h('div', { class: 'mt-1 whitespace-pre-wrap break-words font-mono' }, message)
  ])

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

    await acknowledgeImportError(t('app.sceneImportConflictTitle'), [
      h('p', { class: 'text-sm', style: { color: 'var(--text)' } },
        t('app.sceneImportTemplateMismatch', { name: templateName })),
      h('div', { class: 'mt-3 p-3 rounded', style: { backgroundColor: 'var(--surface-elevated)', border: '1px solid var(--border)' } }, [
        h('div', { class: 'text-sm font-semibold mb-2', style: { color: 'var(--text)' } }, t('app.suggestions')),
        h('ul', { class: 'text-sm space-y-1 list-disc list-inside', style: { color: 'var(--text-muted)' } }, [
          h('li', t('app.sceneImportSuggestion1')),
          h('li', t('app.sceneImportSuggestion2'))
        ])
      ]),
      renderRawDetails()
    ])
    return
  }

  // Handle 400 Bad Request - Scene format/reference errors
  if (status === 400 && getStructuredValidationErrors(error).length === 0) {
    // Unsupported scene version
    if (message.includes('Unsupported scene file')) {
      const versionMatch = message.match(/version (\d+).*version\s+(\d+|null)/s)
      const expectedVersion = versionMatch ? versionMatch[1] : t('app.unknown')
      const receivedVersion = versionMatch ? (versionMatch[2] === 'null' ? t('app.none') : versionMatch[2]) : t('app.unknown')

      await acknowledgeImportError(t('app.sceneImportVersionMismatch'), [
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
        renderRawDetails()
      ])
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

      await acknowledgeImportError(t('app.sceneImportIncompleteTitle'), [
        h('p', { class: 'text-sm', style: { color: 'var(--text)' } },
          t('app.sceneImportMissingCollection', { collection: fieldName })),
        h('p', { class: 'mt-2 text-sm', style: { color: 'var(--text-muted)' } },
          t('app.sceneImportMissingCollectionFix')),
        renderRawDetails()
      ])
      return
    }

    // Conflicting template snapshots
    if (message.includes('conflicting template snapshots')) {
      const templateMatch = message.match(/snapshots for '([^']+)'/)
      const templateName = templateMatch ? templateMatch[1] : t('app.unknown')

      await acknowledgeImportError(t('app.sceneImportConflictTitle'), [
        h('p', { class: 'text-sm', style: { color: 'var(--text)' } },
          t('app.sceneImportDuplicateTemplate', { name: templateName })),
        h('p', { class: 'mt-2 text-sm', style: { color: 'var(--text-muted)' } },
          t('app.sceneImportDuplicateTemplateFix')),
        renderRawDetails()
      ])
      return
    }

    // Template reference errors
    if (message.includes('Scene references device template') && message.includes('without a matching template snapshot')) {
      const templateMatch = message.match(/template\s+'([^']+)'/)
      const templateName = templateMatch ? templateMatch[1] : t('app.unknown')

      await acknowledgeImportError(t('app.sceneImportIncompleteTitle'), [
        h('p', { class: 'text-sm', style: { color: 'var(--text)' } },
          t('app.sceneImportMissingTemplateSnapshot', { name: templateName })),
        h('div', { class: 'mt-3 p-3 rounded', style: { backgroundColor: 'var(--surface-elevated)', border: '1px solid var(--border)' } }, [
          h('div', { class: 'text-sm font-semibold mb-2', style: { color: 'var(--text)' } }, t('app.suggestions')),
          h('ul', { class: 'text-sm space-y-1 list-disc list-inside', style: { color: 'var(--text-muted)' } }, [
            h('li', t('app.sceneImportSuggestion3')),
            h('li', t('app.sceneImportSuggestion4'))
          ])
        ]),
        renderRawDetails()
      ])
      return
    }

    // Unreferenced template snapshot
    if (message.includes('unreferenced template snapshot')) {
      const templateMatch = message.match(/snapshot:\s*(.+)$/)
      const templateName = templateMatch ? templateMatch[1] : t('app.unknown')

      await acknowledgeImportError(t('app.sceneImportWarningTitle'), [
        h('p', { class: 'text-sm', style: { color: 'var(--text)' } },
          t('app.sceneImportUnreferencedTemplate', { name: templateName })),
        h('p', { class: 'text-sm', style: { color: 'var(--text-muted)' } },
          t('app.sceneImportUnreferencedTemplateExplanation')),
        renderRawDetails()
      ], 'warning')
      return
    }

    // Template name mismatch
    if (message.includes('must exactly match manifest.Name')) {
      const matches = message.match(/name\s+'([^']+)'.*manifest\.Name\s+'([^']+)'/)
      const snapshotName = matches ? matches[1] : ''
      const manifestName = matches ? matches[2] : ''

      await acknowledgeImportError(t('app.sceneImportInconsistentTitle'), [
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
        renderRawDetails()
      ])
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

  const renderValidationGroup = (errors: Array<[string, string]>, titleKey: string): VNode[] =>
    errors.length > 0 ? [
      h('div', { class: 'mt-4' }, [
        h('div', { class: 'text-sm font-semibold mb-2', style: { color: 'var(--text)' } },
          t(titleKey, { count: errors.length })),
        ...errors.map(([field, reason]) => h('div', { class: 'border-l-2 border-[color:var(--danger-border)] pl-3 mb-2' }, [
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
    ] : []

  await acknowledgeImportError(t('app.sceneImportValidationTitle'), [
    h('p', { class: 'text-sm', style: { color: 'var(--text-muted)' } },
      t('app.sceneImportValidationSummary', { count: entries.length })),
    h('p', { class: 'text-sm', style: { color: 'var(--text-muted)' } },
      t('app.sceneImportValidationNextStep')),
    ...renderValidationGroup(envVarErrors, 'app.environmentVariableErrors'),
    ...renderValidationGroup(otherErrors, 'app.otherValidationErrors')
  ])
}

const canReviewBoardReplacement = (impactToken: string): boolean => {
  if (!unconfirmedBoardReplacementTokens.has(impactToken)) return true
  notifyBlocked(t('app.sceneReplacementPreviewUnconfirmed'))
  return false
}

const readBoardReplacementReceipt = async <T,>(
  impactToken: string,
  write: () => Promise<T>,
  ownsRequest: () => boolean
): Promise<BoardWriteReceipt<T> | null> => {
  const scope = boardAuthScopeEpoch
  const receipt = await readBoardWriteReceipt(write, ownsRequest, {
    refreshOnRejection: error => readBoardReplacementStalePreview(error) !== null,
    reconcile: () => runTrackedBoardMutation(
      refreshBoardSnapshot,
      getCurrentRecommendationSceneFingerprint(scope),
      () => ownsRequest() ? getCurrentRecommendationSceneFingerprint(scope) : null,
      () => {
        markVerificationResultStale()
        invalidateRecommendationsForSceneChange({ notify: true })
      }
    )
  })
  if (!receipt || receipt.status === 'confirmed') return receipt
  if (receipt.status === 'unconfirmed') {
    unconfirmedBoardReplacementTokens.add(impactToken)
    return receipt
  }

  const preview = readBoardReplacementStalePreview(receipt.error)
  if (!preview) return receipt
  notifyBlocked(t(receipt.snapshotRefreshed
    ? 'app.sceneReplacementChangedBeforeApply' : 'app.sceneReplacementChangedRefreshFailed', {
      devices: preview.deviceCount,
      variables: preview.environmentVariableCount,
      rules: preview.ruleCount,
      specs: preview.specificationCount,
      historyEntries: preview.editHistoryEntryCount
  }))
  return null
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
  scene: BoardSceneModel,
  catalog: DeviceTemplate[]
): boolean => {
  try {
    if (!sceneTemplatesCoveredByCatalog(
      scene.templates,
      catalog,
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

const commitSceneReplacement = (saved: Awaited<ReturnType<typeof boardApi.importScene>>) => {
  invalidateForFullSceneReplacement()
  commitSemanticScene({
    nodes: saved.nodes, environmentVariables: saved.environmentVariables,
    rules: saved.rules, specs: saved.specs, semanticChanged: false
  })
}

const importScene = async (
  scene: BoardSceneModel,
  admissionGuard?: () => boolean
): Promise<void> => {
  const ownsRequest = captureBoardRunOwner()
  const isAdmitted = () => ownsRequest() && (!admissionGuard || admissionGuard())
  if (!isAdmitted() || !ensurePlaybackClosedForMutation() || isSceneReplacementInProgress.value) return
  if (hasAssistantWork.value) {
    notifyBlocked(t('app.finishAssistantBeforeSceneReplacement'))
    return
  }
  isImportingScene.value = true
  try {
    await waitForPendingBoardMutations()
    if (!isAdmitted() || !ensureBoardDataReady()) return
    let replacementPreview: BoardReplacementPreview
    let importCatalog: DeviceTemplate[]
    try {
      replacementPreview = await boardApi.previewBoardReplacement()
      if (!isAdmitted() || !canReviewBoardReplacement(replacementPreview.impactToken)) return
      importCatalog = await boardApi.getDeviceTemplates()
    } catch (error: any) {
      if (!isAdmitted()) return
      notifyError(localizedErrorMessage(error, t('app.sceneReplacementPreviewFailed'), locale.value))
      return
    }
    if (!isAdmitted()) return
    const importPlan = prepareSceneImport(scene, importCatalog)
    if (importPlan.libraryLimitExceeded) {
      await acknowledgeFailure({
        title: t('app.sceneImportLibraryFullTitle'),
        message: t('app.sceneImportLibraryFull', { current: importCatalog.length, added: importPlan.addedTemplates,
          limit: REQUEST_LIMITS.templates }),
        suggestion: t('app.sceneImportLibraryFullHint')
      })
      return
    }
    scene = importPlan.scene
    const hasRepairs = importPlan.templateRenames.length > 0 || importPlan.variableCorrections.length > 0
    const templateCounts = summarizeTemplateUsage(replacementPreview.templates)
    const incomingTemplates = summarizeImportedTemplates(scene.templates, replacementPreview.templates)
    if (!await confirmDestructive({
      title: t('app.sceneImportConfirmTitle'),
      message: h('div', { class: 'scene-import-preview' }, [
        h('div', { class: 'scene-import-preview__details iot-scroll-region' }, [
          h(SceneImportRepairs, { plan: importPlan, t }),
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
              ['app.specifications', replacementPreview.specificationCount, scene.specs.length],
              ['app.sceneReferencedTemplates', templateCounts.used, incomingTemplates.total],
              ['app.sceneReferencedCustomTemplates', templateCounts.usedCustom, incomingTemplates.custom]
            ].map(([label, current, incoming]) => h('tr', [
              h('th', { scope: 'row' }, t(String(label))),
              h('td', String(current)), h('td', String(incoming))
            ])))
          ]),
          h('p', { class: 'board-text-muted text-xs', 'data-testid': 'scene-import-template-summary' },
            t('app.sceneImportTemplateSummary', { retained: templateCounts.custom, added: incomingTemplates.added })),
          h('details', { 'data-testid': 'scene-import-template-rule-details' }, [
            h('summary', t('app.sceneImportTemplateRuleHandling')),
            h('p', t('app.sceneImportTemplateNotice')),
            h('p', t('app.sceneImportRulePriorityNotice'))
          ])
        ]),
        h('p', { class: 'iot-dialog__consequence', 'data-testid': 'scene-import-consequence' },
          t('app.sceneImportConfirmMessage', { historyEntries: replacementPreview.editHistoryEntryCount }))
      ]),
      confirmText: t(hasRepairs ? 'app.sceneImportRepairConfirmAction' : 'app.sceneImportConfirmAction'),
      customClass: 'scene-replacement-confirm'
    })) return
    if (!isAdmitted()) return

    await enqueueBoardMutation(async () => {
      const receipt = await readBoardReplacementReceipt(replacementPreview.impactToken, async () => {
        const saved = await boardApi.importScene({
          impactToken: replacementPreview.impactToken,
          scene: serializeSceneFile(scene)
        })
        if (!savedBatchMatchesScene(saved, scene, importCatalog)) {
          throw new Error('Scene replacement response did not match the requested semantic scene')
        }
        return saved
      }, ownsRequest)
      if (!receipt) return
      if (receipt.status !== 'confirmed') {
        if (receipt.status === 'rejected') await showSceneImportError(receipt.error)
        else if (!receipt.snapshotRefreshed) notifyBlocked(t('app.sceneImportOutcomeUnknownRefreshFailed'))
        else notifyBlocked(t(currentBoardMatchesScene(scene)
          ? 'app.sceneImportCurrentMatchesAfterUnconfirmedResponse' : 'app.sceneImportOutcomeUnconfirmedAfterRefresh'))
        return
      }

      const { mutation: saved } = receipt
      {
        const existingTemplateNames = new Set(importCatalog.map(template =>
          normalizeTemplateLookupName(template.name || template.manifest?.Name)))
        deviceTemplates.value = [
          ...importCatalog,
          ...saved.createdTemplates.filter(template => {
            const key = normalizeTemplateLookupName(template.name || template.manifest?.Name)
            if (!key || existingTemplateNames.has(key)) return false
            existingTemplateNames.add(key)
            return true
          })
        ]
      }
      commitSceneReplacement(saved)
      await resetSceneSelectionAfterReplacement()
      if (!ownsRequest()) return
      const importMessage = t('app.sceneImportSuccess', {
        devices: nodes.value.length,
        variables: environmentVariables.value.length,
        rules: rules.value.length,
        specs: specifications.value.length,
        templates: incomingTemplates.total,
        customTemplates: incomingTemplates.custom,
        added: saved.createdTemplates.length
      })
      notifySuccess(importMessage)
    }, { admissionGuard: isAdmitted, trackSemanticChange: false })
  } catch (error) {
    if (!isPollingAbortedError(error) && !(error instanceof BoardMutationAdmissionCancelledError)) throw error
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
  const ownsRequest = captureBoardRunOwner()
  isClearingScene.value = true
  try {
    await waitForPendingBoardMutations()
    if (!ownsRequest() || !ensureBoardDataReady()) return
    let replacementPreview: BoardReplacementPreview
    try {
      replacementPreview = await boardApi.previewBoardReplacement()
    } catch (error: any) {
      if (!ownsRequest()) return
      notifyError(localizedErrorMessage(error, t('app.sceneReplacementPreviewFailed'), locale.value))
      return
    }
    if (!ownsRequest() || !canReviewBoardReplacement(replacementPreview.impactToken)) return
    const templateCounts = summarizeTemplateUsage(replacementPreview.templates)
    if (!await confirmDestructive({
      title: t('app.sceneClearConfirmTitle'),
      message: t('app.sceneClearConfirmMessage', {
        devices: replacementPreview.deviceCount,
        variables: replacementPreview.environmentVariableCount,
        rules: replacementPreview.ruleCount,
        specs: replacementPreview.specificationCount,
        templates: templateCounts.used,
        customTemplates: templateCounts.usedCustom,
        retained: templateCounts.custom,
        historyEntries: replacementPreview.editHistoryEntryCount
      }),
      confirmText: t('app.sceneClearConfirmAction'),
      customClass: 'scene-replacement-confirm'
    }) || !ownsRequest()) return

    await enqueueBoardMutation(async () => {
      const receipt = await readBoardReplacementReceipt(replacementPreview.impactToken, async () => {
        const saved = await boardApi.clearBoardScene(replacementPreview.impactToken)
        if (saved.nodes.length > 0 || saved.environmentVariables.length > 0
          || saved.rules.length > 0 || saved.specs.length > 0) {
          throw new Error('Scene clear response still contained board items')
        }
        return saved
      }, ownsRequest)
      if (!receipt) return
      if (receipt.status === 'confirmed') {
        commitSceneReplacement(receipt.mutation)
        dialogVisible.value = false
        focusHighlight.clear()
        notifySuccess(t('app.sceneClearSuccess', { retained: templateCounts.custom }))
      } else if (receipt.status === 'rejected') {
        notifyError(localizedErrorMessage(receipt.error, t('app.sceneClearFailed'), locale.value))
      } else if (!receipt.snapshotRefreshed) {
        notifyBlocked(t('app.sceneClearOutcomeUnknownRefreshFailed'))
      } else {
        const isEmpty = getVisibleDeviceNodes().length === 0
          && environmentVariables.value.length === 0
          && rules.value.length === 0
          && specifications.value.length === 0
        notifyBlocked(t(isEmpty
          ? 'app.sceneClearCurrentEmptyAfterUnconfirmedResponse' : 'app.sceneClearOutcomeUnconfirmedAfterRefresh'))
      }
    }, { admissionGuard: ownsRequest, trackSemanticChange: false })
  } catch (error) {
    if (!isPollingAbortedError(error) && !(error instanceof BoardMutationAdmissionCancelledError)) throw error
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

const retryBoardDataLoad = async () => {
  await requestBoardSnapshotRefresh({ force: true })
}

watch([nodes, rules], syncRuleDerivedEdges)

const applyLoadedBoardLayout = (layout: BoardLayoutDto | null, initialHydration: boolean) => {
  wideLayoutToRestore = layout ?? {
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
    if (visibleNodes.length > 0 && !canvasStateTouchedBeforeLayout) {
      void nextTick(() => fitNodesToCanvas(visibleNodes))
    }
  } else if (layout) {
    applyBoardLayout(layout)
  }
  layoutHydrated.value = true
  panelStateTouchedBeforeLayout = false
  canvasStateTouchedBeforeLayout = false
  if (layoutChangedBeforeHydration && !isNarrowViewport()) {
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

const CANVAS_MAP_WIDTH = 220
const CANVAS_MAP_HEIGHT = 112
const CANVAS_MAP_INSET = 8
const isCanvasMapDragging = ref(false)
let canvasMapDragRect: DOMRect | null = null
let canvasMapDragElement: HTMLElement | null = null
let canvasMapDragPointerId: number | null = null

type CanvasMapBounds = NonNullable<ReturnType<typeof getNodeBounds>> & { width: number; height: number }

const canvasMapBounds = computed<CanvasMapBounds | null>(previous => {
  const sceneBounds = renderedNodeBounds.value
  if (!sceneBounds) return null
  let { minX, minY, maxX, maxY } = sceneBounds
  const frame = canvasFrameForRendering.value
  let visibleWorldWidth = 900
  let visibleWorldHeight = 600
  const zoom = renderedCanvasZoom.value
  if (frame && zoom > 0) {
    const { x, y } = renderedCanvasPan.value
    const visibleMinX = (frame.left - x) / zoom
    const visibleMinY = (frame.top - y) / zoom
    const visibleWidth = frame.width / zoom
    const visibleHeight = frame.height / zoom
    visibleWorldWidth = Math.max(1, visibleWidth)
    visibleWorldHeight = Math.max(1, visibleHeight)
    minX = Math.min(minX, visibleMinX)
    minY = Math.min(minY, visibleMinY)
    maxX = Math.max(maxX, visibleMinX + visibleWidth)
    maxY = Math.max(maxY, visibleMinY + visibleHeight)
  }

  const contentWidth = Math.max(1, maxX - minX)
  const contentHeight = Math.max(1, maxY - minY)
  const paddingX = Math.max(260, visibleWorldWidth * 0.75, contentWidth * 0.16)
  const paddingY = Math.max(180, visibleWorldHeight * 0.75, contentHeight * 0.16)
  minX -= paddingX
  minY -= paddingY
  maxX += paddingX
  maxY += paddingY

  // Panning inside a stable projection only moves the viewport rectangle.
  if (previous?.minX === minX && previous.minY === minY && previous.maxX === maxX && previous.maxY === maxY) {
    return previous
  }
  return { minX, minY, maxX, maxY, width: maxX - minX, height: maxY - minY }
})

const canvasMapDots = computed(() => {
  const bounds = canvasMapBounds.value
  if (!bounds) return []
  return renderedCanvasNodes.value.map(node => {
    const x = CANVAS_MAP_INSET + (node.position.x - bounds.minX) / bounds.width * (CANVAS_MAP_WIDTH - CANVAS_MAP_INSET * 2)
    const y = CANVAS_MAP_INSET + (node.position.y - bounds.minY) / bounds.height * (CANVAS_MAP_HEIGHT - CANVAS_MAP_INSET * 2)
    return {
      id: node.id,
      x: Math.max(CANVAS_MAP_INSET / 2, Math.min(CANVAS_MAP_WIDTH - CANVAS_MAP_INSET - 4, x)),
      y: Math.max(CANVAS_MAP_INSET / 2, Math.min(CANVAS_MAP_HEIGHT - CANVAS_MAP_INSET - 4, y)),
      color: getCanvasMapColor(node.id)
    }
  })
})

const canvasMapLines = computed(() => {
  const dotsById = new Map(canvasMapDots.value.map(dot => [dot.id, dot]))
  return allEdges.value.flatMap(edge => {
    const from = dotsById.get(edge.from)
    const to = dotsById.get(edge.to)
    return from && to ? [{
      id: edge.id, x1: from.x + 4, y1: from.y + 4, x2: to.x + 4, y2: to.y + 4, color: from.color
    }] : []
  })
})
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
  const bounds = canvasMapBounds.value
  if (!bounds) return null
  const innerWidth = CANVAS_MAP_WIDTH - CANVAS_MAP_INSET * 2
  const innerHeight = CANVAS_MAP_HEIGHT - CANVAS_MAP_INSET * 2
  return {
    x: CANVAS_MAP_INSET + ((worldX - bounds.minX) / Math.max(1, bounds.width)) * innerWidth,
    y: CANVAS_MAP_INSET + ((worldY - bounds.minY) / Math.max(1, bounds.height)) * innerHeight
  }
}

const canvasMapPointToWorld = (mapX: number, mapY: number) => {
  const bounds = canvasMapBounds.value
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
  const bounds = canvasMapBounds.value
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
  if (!canvasMapBounds.value
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

const fittedViewportForBounds = (bounds: ReturnType<typeof getNodeBounds>, frame: ReturnType<typeof getVisibleCanvasFrame>) => {
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

const fittedViewportForNodes = (targetNodes: DeviceNode[], frame = getVisibleCanvasFrame()) =>
  fittedViewportForBounds(getNodeBounds(targetNodes), frame)

const fitNodesToCanvas = (targetNodes: DeviceNode[] = nodes.value) => {
  const viewport = fittedViewportForNodes(targetNodes)
  if (!viewport) {
    notifyInfo(t('app.noDevicesOnCanvas'))
    return
  }
  canvasZoom.value = viewport.zoom
  canvasPan.value = viewport.pan
}

const resetPlaybackViewport = () => {
  playbackViewportFollowsFit = true
  playbackCanvasFrame = null
  playbackCanvasZoom.value = 1
  playbackCanvasPan.value = { x: 0, y: 0 }
}

// Overview follows the free frame; manual navigation retains its scale and world center.
watch([
  activePlaybackScene, actionDockViewportWidth, boardViewportHeight, playbackPanelOpen,
  () => boardPanels.control.collapsed, () => boardPanels.inspector.collapsed,
  effectiveControlPanelWidth, effectiveInspectorPanelWidth, actionDockReservedWidth
], () => {
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
}, { flush: 'post' })

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
  let receipt: DeviceCreationReceipt | null = null
  let failure: ApiErrorDetails | undefined
  try {
    receipt = await createDeviceInstanceAt(data.template, getNextNodePosition, data.customName.trim(), data.runtime, {
      nameConflictMessage: () => t('app.deviceNameAlreadyExists')
    })
    if (!isRequestCurrent()) return
    if (receipt?.status === 'rejected') failure = errorDetails(receipt.error)
    if (receipt?.status === 'unconfirmed') {
      failure = unconfirmedDeviceCreationFailure(unconfirmedDeviceCreationMessage(receipt))
    }
  } finally {
    data.complete(receipt?.status === 'confirmed' && isRequestCurrent(), isRequestCurrent() ? failure : undefined)
  }
  if (receipt?.status === 'confirmed') {
    await nextTick()
    if (isRequestCurrent()) reportEnvironmentChanges(receipt.environmentChanges)
  }
}

const handleCreateDevices = async (data: {
  items: Array<{ template: DeviceTemplate, customName: string, runtime?: DeviceRuntimeConfig }>
  environmentVariables?: ModelEnvironmentVariable[]
  complete: (saved: boolean, failure?: ApiErrorDetails) => void
}) => {
  const requestToken = getToken()
  const scopeEpoch = boardAuthScopeEpoch
  const isCurrent = () => isCurrentSessionToken(requestToken) && isCurrentBoardAuthScope(scopeEpoch)
  let receipt: DeviceCreationReceipt | null = null
  let failure: ApiErrorDetails | undefined
  const items = Array.isArray(data.items) ? data.items : []
  try {
    if (!ensurePlaybackClosedForMutation() || !ensureBoardDataReady(['nodes', 'templates', 'environment'])) return
    receipt = await enqueueDeviceCreation(() => {
      if (!ensureBoardDataReady(['nodes', 'templates', 'environment'])) return null
      const occupiedNodes = [...getVisibleDeviceNodes()]
      if (items.length === 0) {
        notifyBlocked(t('app.noDevicesToCreate'))
        return null
      }
      if (!ensureBoardItemCapacity('devices', occupiedNodes.length, items.length, REQUEST_LIMITS.devices)) return null
      if (items.some(item => !item?.template?.manifest?.Name || !item.customName?.trim())) {
        notifyBlocked(t('app.deviceBatchContainsInvalidItems'))
        return null
      }
      if (items.some(item => !ensureDeviceRuntimeCapacity(item.runtime))) return null

      const createdNodes: DeviceNode[] = []
      const nameConflicts: string[] = []
      for (const item of items) {
        const requestedLabel = item.customName.trim()
        const uniqueLabel = getUniqueLabel(requestedLabel, occupiedNodes)
        if (uniqueLabel !== requestedLabel) nameConflicts.push(`${requestedLabel} -> ${uniqueLabel}`)
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
      if (nameConflicts.length) {
        notifyBlocked(t('app.deviceBatchNameConflictBlocked', { changes: nameConflicts.join(', ') }))
        return null
      }
      return createdNodes
    }, data.environmentVariables)
    if (!isCurrent()) return
    if (receipt?.status === 'unconfirmed') {
      failure = unconfirmedDeviceCreationFailure(receipt.snapshotRefreshed
        ? t('app.devicesCreateOutcomeRefreshed', { found: receipt.devices.length, count: items.length })
        : t('app.deviceCreateOutcomeUnknownRefreshFailed'))
    } else if (receipt?.status === 'rejected') {
      const fallbackMessage = data.environmentVariables?.length
        ? t('app.saveEnvironmentFailed')
        : t('app.saveNodesFailed')
      notifyError(localizedErrorMessage(receipt.error, fallbackMessage, locale.value))
    }
  } finally {
    data.complete(receipt?.status === 'confirmed' && isCurrent(), isCurrent() ? failure : undefined)
  }
  if (receipt?.status === 'confirmed') {
    await nextTick()
    if (!isCurrent()) return
    reportEnvironmentChanges(receipt.environmentChanges)
    notifySuccess(t('app.devicesAddedWithCount', { count: receipt.devices.length }))
  }
}

// Both editors retain their opened baseline; a refresh must not silently authorise overwriting newer work.
const saveBoardItemEdit = async <T extends { id?: string }>(options: {
  expected: T
  desired: T
  requiredKeys: BoardDataKey[]
  update: (expected: T, desired: T) => Promise<CollectionMutationResult<T>>
  commit: (result: CollectionMutationResult<T>) => void
  focus: (id?: string) => unknown
  complete: (saved: boolean, failure?: ApiErrorDetails) => void
}) => {
  const token = getToken()
  const authScopeEpoch = boardAuthScopeEpoch
  const ownsRequest = () => isCurrentSessionToken(token) && isCurrentBoardAuthScope(authScopeEpoch)
  const expected = deepClone(options.expected)
  const desired = deepClone(options.desired)
  let saved = false
  let failure: ApiErrorDetails | undefined
  try {
    if (!ensurePlaybackClosedForMutation()) return
    await enqueueBoardMutation(async () => {
      if (!ownsRequest() || !ensurePlaybackClosedForMutation()
        || !ensureBoardDataReady(options.requiredKeys)) return
      const receipt = await readBoardWriteReceipt(() => options.update(expected, desired), ownsRequest, {
        refreshOnRejection: (error: any) => [404, 409].includes(error?.response?.status)
      })
      if (!receipt) return
      if (receipt.status === 'confirmed') {
        const { mutation } = receipt
        options.commit(mutation)
        saved = true
        await options.focus(mutation.affectedItem.id)
      } else if (receipt.status === 'unconfirmed') {
        failure = {
          reasonCode: BOARD_ITEM_EDIT_OUTCOME_UNKNOWN,
          cause: t(receipt.snapshotRefreshed ? 'app.boardItemEditOutcomeRefreshed' : 'app.boardItemEditOutcomeUnknown'),
          details: [], fields: {}
        }
      } else {
        failure = errorDetails(receipt.error)
      }
    })
  } catch (error: unknown) {
    if (ownsRequest()) failure = errorDetails(error)
  } finally {
    options.complete(saved && ownsRequest(), ownsRequest() ? failure : undefined)
  }
}

const handleSaveRule = (request: {
  rule: RuleForm; expected?: RuleForm
  complete: (saved: boolean, failure?: ApiErrorDetails) => void
}) => request.expected ? saveBoardItemEdit({
  expected: request.expected, desired: request.rule, complete: request.complete,
  requiredKeys: ['nodes', 'templates', 'rules'], update: boardApi.updateRule,
  commit: mutation => commitSemanticScene({ rules: mutation.currentItems, availability: mutation, semanticChanged: mutation.operation !== 'unchanged' }),
  focus: focusRuleOnCanvas
}) : handleAddRule(request)

const handleSaveSpecification = (data: Parameters<typeof handleAddSpec>[0] & { expected?: Specification }) => {
  if (!data.expected) return handleAddSpec(data)
  const desired: Specification = {
    ...data.expected, name: data.name.trim(), templateId: data.templateId as SpecTemplateId,
    aConditions: data.aConditions, ifConditions: data.ifConditions, thenConditions: data.thenConditions
  }
  return saveBoardItemEdit({
    expected: data.expected, desired, complete: data.complete,
    requiredKeys: ['nodes', 'templates', 'specs'], update: boardApi.updateSpec,
    commit: mutation => commitSemanticScene({ specs: mutation.currentItems, availability: mutation,
      semanticChanged: !isSameSpecification(data.expected!, desired, deviceModeCount) }),
    focus: focusSpecInInspector
  })
}

const editRuleFromInspector = (id: string) => {
  if (!ensurePlaybackClosedForMutation() || !ensureBoardDataReady(['rules'])) return
  const rule = rules.value.find(item => item.id === id)
  if (!rule) return
  openControlSection('rules')
  editingRule.value = deepClone(rule)
}

const editSpecificationFromInspector = (id: string) => {
  if (!ensurePlaybackClosedForMutation() || !ensureBoardDataReady(['specs'])) return
  const specification = specifications.value.find(item => item.id === id)
  if (!specification) return
  openControlSection('specs')
  editingSpecification.value = deepClone(specification)
}

watch(() => [authState.token, authState.user?.userId, isSceneReplacementInProgress.value], () => {
  editingRule.value = null
  editingSpecification.value = null
}, { flush: 'sync' })

const openRuleBuilder = () => {
  if (!ensurePlaybackClosedForMutation()) return
  openControlSection('rules')
}

const isSpecificationDuplicateRejection = (error: unknown) =>
  (error as { response?: { status?: number } })?.response?.status === 409
  && errorDetails(error).reasonCode === 'DUPLICATE_SPECIFICATION'

const readSpecificationCreationReceipt = (specification: Specification, isCurrent: () => boolean) =>
  readBoardWriteReceipt(() => boardApi.addSpec(specification), isCurrent, {
    refreshOnRejection: isSpecificationDuplicateRejection
  })

const specificationCreateRecoveryMessage = (specification: Specification, snapshotRefreshed: boolean) =>
  t(!snapshotRefreshed ? 'app.specCreateOutcomeUnknown'
    : specificationExists(specification) ? 'app.specCreateOutcomeRefreshed' : 'app.specCreateOutcomeNotFound')

const handleAddSpec = (data: {
  name: string,
  templateId: string, 
  devices: Array<{deviceId: string, deviceLabel: string, selectedApis: string[]}>, 
  formula: string,
  aConditions: SpecCondition[],
  ifConditions: SpecCondition[],
  thenConditions: SpecCondition[],
  complete: (saved: boolean, failure?: ApiErrorDetails) => void
}) => saveNewBoardItem<Specification>({
  requiredKey: 'specs', complete: data.complete,
  readReceipt: readSpecificationCreationReceipt, focus: focusSpecInInspector,
  recoveryMessage: specificationCreateRecoveryMessage, isDuplicateRejection: isSpecificationDuplicateRejection,
  failureKey: 'app.saveSpecsFailed',
  commit: mutation => commitSemanticScene({ specs: mutation.currentItems, availability: mutation }),
  prepare: () => {
    if (!ensureBoardItemCapacity(
      'specifications', specifications.value.length, 1, REQUEST_LIMITS.specifications
    )) return null
    const { templateId, aConditions, ifConditions, thenConditions } = data
    if (![aConditions, ifConditions, thenConditions].every(conditions =>
      ensureNestedItemCapacity(
        t('app.specificationConditions'), conditions?.length || 0,
        REQUEST_LIMITS.specificationConditions
      ))) return null
    const specTemplate = defaultSpecTemplates.find(t => t.id === templateId)
    const templateLabel = specTemplate?.label || templateId

    const newSpec: Specification = {
      id: 'spec_' + Date.now(),
      name: data.name.trim(),
      templateId: templateId as SpecTemplateId,
      templateLabel,
      aConditions: aConditions || [],
      ifConditions: ifConditions || [],
      thenConditions: thenConditions || []
    }
    newSpec.formula = buildSpecFormula(newSpec, {
      nodes: nodes.value, modeCount: deviceModeCount
    })
    newSpec.devices = buildSpecDeviceRefsFromConditions([
      ...(aConditions || []),
      ...(ifConditions || []),
      ...(thenConditions || [])
    ], nodes.value)

    if (specificationExists(newSpec)) {
      notifyBlocked(t('app.specDuplicate'))
      return null
    }
    return newSpec
  }
})

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
  for (const panel of recommendationPanels) {
    panel.epoch += 1
    cancelRecommendationDuringTeardown(panel.requestId.value, panel.abortController.value)
    panel.abortController.value = null
  }
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
    specs: specifications.value.slice(0, 6).map(spec => ({
      name: spec.name,
      formulaPreview: buildSpecFormula(spec, {
        nodes: nodes.value, modeCount: deviceModeCount
      })
    })),
    templates: deviceTemplates.value
      .map(template => template.manifest?.Name || template.name)
      .filter(Boolean)
      .slice(0, 10)
  }
}

defineExpose({
  refreshBoardSnapshot: () => enqueueBoardMutation(refreshBoardSnapshot),
  refreshRunHistory: () => refreshRunHistory(),
  refreshAllBoardState: () => enqueueBoardMutation(refreshAllBoardState),
  getChatSuggestionContext,
  isChatInteractionLocked: () => !isBoardDataReady.value || isSceneReplacementInProgress.value || isOpeningAccount.value,
  prepareChatInteraction: () => prepareBoardChatInteraction({
    sceneReplacementInProgress: isSceneReplacementInProgress.value,
    dismissPlayback
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

const presentRunFailure = (
  kind: 'verification' | 'simulation', messageKey: string, error?: unknown, present = true
) => {
  const failure = makeRunFailure(messageKey, error)
  const target = kind === 'verification' ? verificationError : simulationError
  if (automaticRunPresentation(present) === 'foreground') {
    target.value = failure
    return
  }
  offerDeferredRunNotice({
    title: () => runFailureTitle(kind, failure),
    message: () => formatRunFailure(kind, failure).message,
    actionLabel: () => t('app.viewDetails'),
    discardLabel: () => t('app.close')
  }, () => { target.value = failure })
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
  setNodes: replaceNodesFromServer,
  setEnvironmentVariables: next => { environmentVariables.value = next },
  setRules: next => { rules.value = next },
  setSpecs: next => { specifications.value = next },
  syncRuleDerivedEdges: () => syncRuleDerivedEdges(),
  markVerificationResultStale: () => markVerificationResultStale(),
  // Declared later in setup; both are only reached from async handlers, never during setup.
  syncUndoAvailability: availability => syncBoardUndoAvailability(availability),
  clearDanglingFocus: scene => reconcileDanglingBoardFocus(scene.nodes ? { ...scene, nodes: nodes.value } : scene)
})

type VerificationResultView = VerificationResult
type SimulationResultView = SimulationResult

const compareRunToCurrentBoard = (
  result: { modelSnapshot?: ModelRunSnapshot } | null,
  kind: 'verification' | 'simulation' | 'fuzzing'
): RunBoardComparison => compareModelSnapshot(result?.modelSnapshot, currentBoardModelFingerprints.value, kind)

// ==== Rule Recommendation Logic ====
const isRecommendingRules = ref(false)
const isRecommendingDevices = ref(false)
const isRecommendingSpecs = ref(false)
const ruleRecommendations = ref<RuleRecommendation[]>([])
const ruleRecommendationMessage = ref('')
const localizedRecommendationText = (value: unknown, fallback = ''): string =>
  localizedTextOrFallback(value, fallback, locale.value)
const ruleRecommendationFilteredCount = ref(0)
const ruleRecommendationFilteredItems = ref<RecommendationFilteredItem[]>([])
const ruleRecommendationAdjustedItems = ref<RecommendationAdjustmentItem[]>([])
const ruleRecommendationRawCandidateCount = ref(0)
const ruleRecommendationInspectedCount = ref(0)
const ruleRecommendationTruncatedCount = ref(0)
const ruleRecommendationIsAppliedConfirmation = ref(false)
const appliedRuleRecommendations = ref<Set<number>>(new Set())
const applyingRuleRecommendations = ref<Set<number>>(new Set())
const ruleRecommendationRequested = ref(false)
const ruleRecommendationFilters = reactive({
  userRequirement: ''
})

// Wording rules live in `board/recommendationFilterText.ts` so they can be tested without the board.
const recommendationTextContext = computed(() => ({ t, locale: locale.value }))
const formatRecommendationFilteredType = (type: unknown): string =>
  formatFilteredType(type, recommendationTextContext.value)
const formatRecommendationFilteredItem = (item: RecommendationFilteredItem): string =>
  formatFilteredItem(item, recommendationTextContext.value)

// Device adjustments resolve display tokens against the proposed template.
type RecommendationAdjustmentContext = 'rule' | 'device' | 'spec'

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
  return undefined
}

const formatRecommendationAdjustmentToken = (
  item: RecommendationAdjustmentItem,
  context: RecommendationAdjustmentContext,
  value: unknown
): string => {
  return formatTemplateModelToken(recommendationAdjustmentTemplate(item, context), value)
}

const formatRecommendationAdjustmentValue = (
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
    .map(([key, value]) => formatRecommendationAdjustmentValue(item, context, key, value))
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

const specificationExists = (specification: Parameters<typeof buildSpecificationSemanticKey>[0]): boolean => {
  const key = buildSpecificationSemanticKey(specification, deviceModeCount)
  return specifications.value.some(spec => buildSpecificationSemanticKey(spec, deviceModeCount) === key)
}

type RecommendationPanelKind = 'rule' | 'device' | 'spec'
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
    message: outcomeUnknown ? t(error === undefined ? 'app.recommendationStopOutcomeUnknown' : 'app.recommendationResponseLostRecovering')
      : extractRecommendationErrorMessage(error, fallback),
    suggestion: t(outcomeUnknown ? 'app.recommendationUnknownNextStep' : 'app.recommendationFailureNextStep'),
    ...errorDetails(error)
  }
}

/**
 * The in-flight request identity for one recommendation panel.
 *
 * The epoch also fences writes and feedback from an obsolete candidate list.
 */
type RecommendationRequestHandle = {
  kind: RecommendationPanelKind
  requestId: Ref<string | null>
  abortController: Ref<AbortController | null>
  running: Ref<boolean>
  requested: Ref<boolean>
  epoch: number
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
  panel: RecommendationRequestHandle
  controller: AbortController | null
  showMessage: boolean
  cancellationAccepted: boolean
  postResponseReceived: boolean
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
    const { panel } = recovery
    if (panel.abortController.value === recovery.controller) {
      panel.abortController.value = null
    }
    const wasCurrent = panel.requestId.value === requestId
    panel.requestId.value = requestIdAfterTerminalSettlement(
      panel.requestId.value,
      requestId
    )
    if (wasCurrent) {
      panel.running.value = false
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
  const recovery = recommendationStopRecoveries.get(kind)
  if (recovery?.requestId === requestId) {
    // An interrupted POST can return while the provider is still unwinding.
    recovery.postResponseReceived = true
    return
  }
  recommendationRequestOwners.delete(requestId)
  recommendationOutcomeUnknownWarnings.delete(requestId)
  recordRecommendationTerminalEvidence(requestId, 'post-terminal')
  const wasCurrent = requestIdRef.value === requestId
  requestIdRef.value = requestIdAfterTerminalSettlement(requestIdRef.value, requestId)
  if (abortControllerRef.value === controller) abortControllerRef.value = null
  if (wasCurrent) panel.running.value = false
}

const markRecommendationOutcomeUnknown = (panel: RecommendationRequestHandle, error?: unknown) => {
  panel.running.value = false
  if (!recommendationFailures[panel.kind]?.outcomeUnknown) {
    reportRecommendationFailure(panel.kind, error, '', true)
  }
}

const beginUnknownRecommendationRecovery = (
  panel: RecommendationRequestHandle,
  requestId: string,
  error?: unknown
) => {
  if (panel.requestId.value !== requestId) return
  markRecommendationOutcomeUnknown(panel, error)
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
  const { kind } = panel
  const requestId = panel.requestId.value
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
    panel,
    controller: panel.abortController.value,
    showMessage: options.showMessage !== false,
    cancellationAccepted: false,
    postResponseReceived: false,
    acceptanceNotified: false,
    consecutiveStatusFailures: 0,
    retryNotBeforeMs: 0
  }
  // Stop intent retires any candidate response, even if the server cannot acknowledge it yet.
  panel.epoch += 1
  recommendationStopRecoveries.set(kind, recovery)
  recommendationProgressStage.value = 'CANCELLING'
  return recovery
}

const acceptRecommendationCancellation = (
  kind: RecommendationPanelKind,
  requestId: string
) => {
  const recovery = recommendationStopRecoveries.get(kind)
  if (!recovery || recovery.requestId !== requestId || recovery.panel.requestId.value !== requestId) return
  recovery.cancellationAccepted = true
  recovery.controller?.abort()
  if (recovery.panel.abortController.value === recovery.controller) {
    recovery.panel.abortController.value = null
  }
  if (!boardLifecycleDisposed && recovery.showMessage && !recovery.acceptanceNotified) {
    recovery.acceptanceNotified = true
    notifyInfo(t(recovery.panel.cancelledMessageKey))
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
    } else if (panel.running.value && !boardLifecycleDisposed && recommendationStopRecoveries.get(kind) === recovery) {
      notifyBlocked(t('app.recommendationStopRequestMayStillBeRunning'))
    }
  } catch (error) {
    console.error('Failed to cancel recommendation request:', error)
    if (panel.running.value && !boardLifecycleDisposed && recommendationStopRecoveries.get(kind) === recovery) {
      notifyBlocked(t('app.recommendationStopRequestMayStillBeRunning'))
    }
  } finally {
    recommendationStopRequestsInFlight.delete(kind)
    if (panel.requestId.value === requestId) void refreshRecommendationProgress(panel)
  }
}

const isAnyRecommendationRunning = () => isRecommendingRules.value || isRecommendingDevices.value || isRecommendingSpecs.value

const recommendationProgressElapsed = ref(0)
const recommendationProgressStage = ref<InteractiveOperationStage>('QUEUED')
const recommendationProgress = computed(() => ({
  elapsedSeconds: recommendationProgressElapsed.value,
  stage: recommendationProgressStage.value,
  templateCount: deviceTemplates.value.length,
  deviceCount: nodes.value.length,
  ruleCount: rules.value.length,
  specCount: specifications.value.length
}))
let recommendationProgressTimer: ReturnType<typeof setInterval> | null = null
let recommendationProgressInFlightId: string | null = null
const refreshRecommendationProgress = async (panel: RecommendationRequestHandle, force = false) => {
  const { kind } = panel
  const requestId = panel.requestId.value
  if (boardLifecycleDisposed || !requestId || recommendationProgressInFlightId === requestId) return
  const isCurrent = () => !boardLifecycleDisposed && panel.requestId.value === requestId
  const scheduledRecovery = recommendationStopRecoveries.get(kind)
  if (!force && scheduledRecovery?.requestId === requestId
    && scheduledRecovery.retryNotBeforeMs > Date.now()) return
  recommendationProgressInFlightId = requestId
  let postResponseReceivedBeforeStatusRead = false
  try {
    const recovery = recommendationStopRecoveries.get(kind)
    if (recovery?.requestId === requestId && !recovery.cancellationAccepted) {
      try {
        if (await cancelRecommendationAsOwner(requestId, recovery.ownerAuthToken) && isCurrent()) {
          acceptRecommendationCancellation(kind, requestId)
        }
      } catch {
        // The status read below remains the source of truth while cancellation is retried.
      }
    }
    if (!isCurrent()) return
    const statusOwner = getRecommendationRequestOwner(requestId)
    if (!statusOwner) throw new Error('Recommendation owner credential is unavailable')
    postResponseReceivedBeforeStatusRead = recovery?.requestId === requestId && recovery.postResponseReceived
    const status = await readRecommendationStatusAsOwner(requestId, statusOwner.authToken)
    if (!isCurrent()) return
    if (status.requestId !== requestId) throw new Error('Recommendation status does not match the requested ID')
    recommendationProgressStage.value = status.stage
    const currentRecovery = recommendationStopRecoveries.get(kind)
    if (currentRecovery?.requestId === requestId) {
      currentRecovery.consecutiveStatusFailures = 0
      currentRecovery.retryNotBeforeMs = 0
      if (status.state === 'FINISHED') finishRecommendationStopRecovery(kind, requestId)
    }
  } catch (error: any) {
    if (!isCurrent()) return
    const recovery = recommendationStopRecoveries.get(kind)
    if (recovery?.requestId === requestId) {
      // A POST rejected before admission has no status record to finish.
      if (postResponseReceivedBeforeStatusRead && !recovery.cancellationAccepted
        && error?.response?.status === 404) {
        releaseRecommendationTracking(kind, requestId, { terminalEvidence: 'post-terminal' })
        return
      }
      // A 404 can mean DELETE beat POST registration, while a transport failure says
      // nothing about server completion. Retain the owner and back off without releasing.
      const retryPlan = planRecommendationRecoveryAfterStatusFailure(
        recovery.consecutiveStatusFailures
      )
      recovery.consecutiveStatusFailures = retryPlan.consecutiveFailures
      recovery.retryNotBeforeMs = Date.now() + retryPlan.retryDelayMs
      if (retryPlan.outcomeUnknown) markRecommendationOutcomeUnknown(panel)
    }
    // Registration and ordinary completion can race with this read; the POST remains authoritative.
  } finally {
    if (recommendationProgressInFlightId === requestId) recommendationProgressInFlightId = null
  }
}

const hasPendingRecommendationInAnotherPanel = (kind: RecommendationPanelKind) => {
  const trackedKind = trackedRecommendationPanel.value?.kind
  return trackedKind !== undefined && trackedKind !== kind
}

const canGenerateRecommendations = (kind: RecommendationPanelKind): boolean => {
  if (recommendationBatch.value?.running) return false
  if (!ensureBoardDataReady()) return false
  if (isSimulationPlaybackActive.value) {
    notifyBlocked(t('app.closeCurrentSimulationFirst'))
    return false
  }
  if (isTracePlaybackActive.value) {
    notifyBlocked(t('app.closeCounterexampleFirst'))
    return false
  }
  if (hasPendingRecommendationInAnotherPanel(kind)) {
    notifyBlocked(t('app.recommendationGenerationInProgress'))
    return false
  }
  return true
}

const generateRecommendations = async <T,>({
  panel, requiredKeys, request, adopt, failureMessageKey
}: {
  panel: RecommendationRequestHandle
  requiredKeys: BoardDataKey[]
  request: (options: Required<OwnedRecommendationPostOptions>) => Promise<T>
  adopt: (response: T) => void
  failureMessageKey: string
}) => {
  if (isRecommendationRequestActive(panel.running.value, panel.requestId.value)) {
    if (panel.running.value) await stopActiveRecommendation(panel)
    else await refreshRecommendationProgress(panel, true)
    return
  }
  if (isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return
  }
  if (!ensureBoardDataReady(requiredKeys) || !canGenerateRecommendations(panel.kind)) return
  const owner = captureRecommendationRequestOwner()
  if (!owner) return

  const controller = new AbortController()
  const requestId = crypto.randomUUID()
  panel.running.value = true
  resetRecommendationResults(panel.kind)
  panel.requested.value = true
  const epoch = ++panel.epoch
  const sceneGeneration = recommendationSceneGeneration
  const isCurrent = () => !boardLifecycleDisposed && currentAuthUserId.value === owner.userId
    && panel.epoch === epoch && recommendationSceneGeneration === sceneGeneration
    && panel.requestId.value === requestId
  panel.abortController.value = controller
  panel.requestId.value = requestId
  recommendationRequestOwners.set(requestId, owner)

  let postResponseReceived = false
  try {
    const response = await request({ requestId, authToken: owner.authToken, signal: controller.signal })
    postResponseReceived = true
    if (isCurrent()) adopt(response)
  } catch (error: unknown) {
    const cancellation = error as { name?: unknown; code?: unknown } | null
    if (cancellation?.name === 'CanceledError' || cancellation?.code === 'ERR_CANCELED') return
    if (!postResponseReceived && isRecommendationPostOutcomeUnknown(error)) {
      if (!boardLifecycleDisposed) beginUnknownRecommendationRecovery(panel, requestId, isCurrent() ? error : undefined)
      return
    }
    postResponseReceived = true
    if (isCurrent()) {
      console.error(`Failed to fetch ${panel.kind} recommendations:`, error)
      reportRecommendationFailure(panel.kind, error, t(failureMessageKey))
    }
  } finally {
    if (postResponseReceived) settleRecommendationPost(panel, requestId, controller)
  }
}

const invalidateRecommendationPanel = (panel: RecommendationRequestHandle) => {
  panel.epoch += 1
  void stopActiveRecommendation(panel, { showMessage: false })
  resetRecommendationResults(panel.kind)
}

const resetRecommendationResults = (kind: RecommendationPanelKind) => {
  delete recommendationBatches[kind]
  recommendationApplyFailures[kind] = {}
  if (!recommendationFailures[kind]?.outcomeUnknown) delete recommendationFailures[kind]
  const results = recommendationResultsByKind[kind]
  results.candidates.value = []
  results.message.value = ''
  results.filteredCount.value = 0
  results.filteredItems.value = []
  results.adjustedItems.value = []
  for (const count of results.candidateCounts) count.value = 0
  results.isAppliedConfirmation.value = false
  results.applied.value.clear()
  results.applying.value.clear()
  results.requested.value = false
}

// Closing a surface is not a fresh result, so it must not clear a stale flag: `lastVerificationResult`
// and `lastSimulationResult` outlive every dialog, and reopening one must still warn that the canvas
// changed under it. Only a new run clears the flag.
function closeResultSurfaces() {
  // A pending detail must not restore a surface after workspace teardown.
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
const deviceRecommendationRequested = ref(false)
const deviceRecommendationFilters = reactive({
  userRequirement: ''
})

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
const specRecommendationRequested = ref(false)
const specRecommendationFilters = reactive({
  userRequirement: ''
})

const ruleRecommendationPanel: RecommendationRequestHandle = {
  kind: 'rule',
  requestId: ref<string | null>(null),
  abortController: ref<AbortController | null>(null),
  running: isRecommendingRules,
  requested: ruleRecommendationRequested,
  epoch: 0,
  cancelledMessageKey: 'app.ruleRecommendationCancelled'
}
const deviceRecommendationPanel: RecommendationRequestHandle = {
  kind: 'device',
  requestId: ref<string | null>(null),
  abortController: ref<AbortController | null>(null),
  running: isRecommendingDevices,
  requested: deviceRecommendationRequested,
  epoch: 0,
  cancelledMessageKey: 'app.deviceRecommendationCancelled'
}
const specRecommendationPanel: RecommendationRequestHandle = {
  kind: 'spec',
  requestId: ref<string | null>(null),
  abortController: ref<AbortController | null>(null),
  running: isRecommendingSpecs,
  requested: specRecommendationRequested,
  epoch: 0,
  cancelledMessageKey: 'app.specificationRecommendationCancelled'
}

const recommendationPanels = [ruleRecommendationPanel, deviceRecommendationPanel, specRecommendationPanel]
const trackedRecommendationPanel = computed(() => recommendationPanels.find(panel => panel.requestId.value !== null))

watch(() => trackedRecommendationPanel.value?.requestId.value, requestId => {
  if (recommendationProgressTimer !== null) {
    clearInterval(recommendationProgressTimer)
    recommendationProgressTimer = null
  }
  recommendationProgressElapsed.value = 0
  recommendationProgressStage.value = 'QUEUED'
  const panel = trackedRecommendationPanel.value
  if (!requestId || !panel) return
  const startedAt = Date.now()
  void refreshRecommendationProgress(panel)
  recommendationProgressTimer = setInterval(() => {
    if (panel.requestId.value !== requestId) return
    if (panel.running.value) recommendationProgressElapsed.value = Math.floor((Date.now() - startedAt) / 1000)
    void refreshRecommendationProgress(panel)
  }, 1000)
})

const recommendationActionLabel = (panel: RecommendationRequestHandle, stopKey: string) =>
  t(panel.running.value ? stopKey : panel.requestId.value ? 'app.recommendationCheckStatus' : 'app.generateRecommendations')

const prepareActiveRecommendationsForLogout = async (): Promise<InteractiveLogoutPreparation> => {
  const activeRequests = recommendationPanels.flatMap(panel => {
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

const fetchRuleRecommendations = () => generateRecommendations({
  panel: ruleRecommendationPanel,
  requiredKeys: ['nodes', 'templates', 'rules'],
  request: options => recommendRules(options, undefined, locale.value, ruleRecommendationFilters.userRequirement),
  failureMessageKey: 'app.failedToFetchRuleRecommendations',
  adopt: response => {
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
  }
})

const fetchDeviceRecommendations = () => generateRecommendations({
  panel: deviceRecommendationPanel,
  requiredKeys: ['nodes', 'templates'],
  request: options => boardApi.recommendRelatedDevices(options, undefined, locale.value, deviceRecommendationFilters.userRequirement),
  failureMessageKey: 'app.failedToFetchDeviceRecommendations',
  adopt: response => {
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
  }
})

const fetchSpecRecommendations = () => generateRecommendations({
  panel: specRecommendationPanel,
  requiredKeys: ['nodes', 'templates', 'rules', 'specs'],
  request: options => boardApi.recommendSpecifications(options, undefined, locale.value, specRecommendationFilters.userRequirement),
  failureMessageKey: 'app.failedToFetchSpecificationRecommendations',
  adopt: response => {
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
  }
})

const invalidateRuleRecommendations = () =>
  invalidateRecommendationPanel(ruleRecommendationPanel)

const invalidateDeviceRecommendations = () =>
  invalidateRecommendationPanel(deviceRecommendationPanel)

const invalidateSpecRecommendations = () =>
  invalidateRecommendationPanel(specRecommendationPanel)

type RecommendationBatchState = {
  kind: 'rule' | 'device' | 'spec'
  running: boolean
  applied: number
  total: number
  checkingSimilarity: boolean
  deviceOrigin?: { x: number; y: number }
}
const recommendationBatches = reactive<Partial<Record<RecommendationPanelKind, RecommendationBatchState>>>({})
const recommendationBatch = computed(() => Object.values(recommendationBatches).find(batch => batch.running) ?? null)
const isRecommendationBatchLocked = (kind: RecommendationBatchState['kind']) =>
  Boolean(recommendationBatch.value || recommendationBatches[kind])

const recommendationBatchBlockedReason = (kind: RecommendationBatchState['kind']): string => {
  const batch = recommendationBatches[kind]
  if (batch) {
    if (batch.checkingSimilarity) return t('app.checkingAiSimilarity')
    return t(batch.running ? 'app.recommendationBatchProgress'
      : batch.applied === batch.total ? 'app.recommendationBatchComplete' : 'app.recommendationBatchStopped',
    { count: batch.applied, total: batch.total })
  }
  if (recommendationBatch.value || applyingRuleRecommendations.value.size || applyingDeviceRecommendations.value.size
    || applyingSpecRecommendations.value.size) return t('app.applyingRecommendation')
  if (isAnyRecommendationRunning()) return t('app.recommendationGenerationInProgress')
  if (isSceneReplacementInProgress.value) return t('app.sceneReplacementInProgress')
  if (!isBoardDataReady.value) return t('app.loading')
  return ''
}

const applyAllRecommendations = async (kind: RecommendationBatchState['kind']) => {
  if (recommendationBatchBlockedReason(kind) || !ensurePlaybackClosedForMutation()) return
  const candidates = kind === 'rule' ? ruleRecommendations.value
    : kind === 'device' ? deviceRecommendations.value : specRecommendations.value
  const applied = kind === 'rule' ? appliedRuleRecommendations.value
    : kind === 'device' ? appliedDeviceRecommendations.value : appliedSpecRecommendations.value
  if (!candidates.length || applied.size) return
  const deviceOrigin = kind === 'device' ? getVisibleCanvasCenterWorld() ?? undefined : undefined
  if (kind === 'device' && !deviceOrigin) return
  const generation = recommendationSceneGeneration
  const authEpoch = boardAuthScopeEpoch
  const token = getToken()
  recommendationBatches[kind] = {
    kind, running: true, applied: 0, total: candidates.length, checkingSimilarity: kind === 'rule', deviceOrigin
  }
  const batch = recommendationBatches[kind]!
  const isCurrent = () => recommendationBatches[kind] === batch && generation === recommendationSceneGeneration
    && isCurrentBoardAuthScope(authEpoch) && isCurrentSessionToken(token)
  let index = 0
  try {
    let checkedRules: CheckedRuleRecommendation[] | undefined
    if (kind === 'rule') {
      const drafts = ruleRecommendations.value.map((rec, index) => materializeRuleRecommendation(rec, `rule_${index}`))
      const similarities = await boardApi.checkRuleSimilarityBatch(drafts)
      checkedRules = drafts.map((rule, index) => ({ rule, similarity: similarities[index]! }))
      batch.checkingSimilarity = false
    }
    // Snapshot recovery can retain a card. Advancing the batch requires a write receipt.
    for (; index < candidates.length && isCurrent(); index++) {
      if (recommendationApplyFailures[kind][index]?.outcomeUnknown) break
      let receipt: RecommendationWriteReceipt
      if (checkedRules) {
        receipt = await applyRecommendation(candidates[index] as RuleRecommendation, index, checkedRules[index]!)
      } else if (kind === 'device') {
        receipt = await applyDeviceRecommendation(candidates[index] as DeviceRecommendation, index, true)
      } else {
        receipt = await applySpecRecommendation(candidates[index] as SpecificationRecommendation, index, true)
      }
      if (!isCurrent() || receipt !== 'confirmed') break
      batch.applied += 1
    }
  } catch (error) {
    if (isCurrent()) reportRecommendationApplyFailure(kind, index, error,
      batch.checkingSimilarity ? t('app.recommendationBatchPreflightFailed')
        : t('app.recommendationBatchStopped', { count: batch.applied, total: batch.total }),
      !batch.checkingSimilarity && !isDefinitiveMutationRejection(error))
  } finally {
    batch.checkingSimilarity = false
    batch.running = false
    if (isCurrent()) recommendationSceneGeneration += 1
  }
}

const keepAppliedRecommendationAdjustments = (
  items: RecommendationAdjustmentItem[],
  appliedIndex: number
): RecommendationAdjustmentItem[] => items
  .filter(item => item.index === undefined || item.index === appliedIndex + 1)
  .map(item => item.index === undefined ? item : { ...item, index: 1 })

const recommendationResultsByKind = {
  rule: {
    candidates: ruleRecommendations,
    message: ruleRecommendationMessage,
    filteredCount: ruleRecommendationFilteredCount,
    filteredItems: ruleRecommendationFilteredItems,
    adjustedItems: ruleRecommendationAdjustedItems,
    candidateCounts: [ruleRecommendationRawCandidateCount, ruleRecommendationInspectedCount, ruleRecommendationTruncatedCount],
    isAppliedConfirmation: ruleRecommendationIsAppliedConfirmation,
    requested: ruleRecommendationRequested,
    applied: appliedRuleRecommendations,
    applying: applyingRuleRecommendations
  },
  device: {
    candidates: deviceRecommendations,
    message: deviceRecommendationMessage,
    filteredCount: deviceRecommendationFilteredCount,
    filteredItems: deviceRecommendationFilteredItems,
    adjustedItems: deviceRecommendationAdjustedItems,
    candidateCounts: [deviceRecommendationRawCandidateCount, deviceRecommendationInspectedCount, deviceRecommendationTruncatedCount],
    isAppliedConfirmation: deviceRecommendationIsAppliedConfirmation,
    requested: deviceRecommendationRequested,
    applied: appliedDeviceRecommendations,
    applying: applyingDeviceRecommendations
  },
  spec: {
    candidates: specRecommendations,
    message: specRecommendationMessage,
    filteredCount: specRecommendationFilteredCount,
    filteredItems: specRecommendationFilteredItems,
    adjustedItems: specRecommendationAdjustedItems,
    candidateCounts: [specRecommendationRawCandidateCount, specRecommendationInspectedCount, specRecommendationTruncatedCount],
    isAppliedConfirmation: specRecommendationIsAppliedConfirmation,
    requested: specRecommendationRequested,
    applied: appliedSpecRecommendations,
    applying: applyingSpecRecommendations
  }
}

const invalidateOtherRecommendations = (kind: RecommendationPanelKind) => {
  if (kind !== 'rule') invalidateRuleRecommendations()
  if (kind !== 'device') invalidateDeviceRecommendations()
  if (kind !== 'spec') invalidateSpecRecommendations()
}

const preserveAppliedRecommendationAfterSceneChange = (
  kind: RecommendationPanelKind,
  appliedIndex: number
) => {
  if (recommendationBatch.value?.running && recommendationBatch.value.kind === kind) {
    // Only this explicitly selected batch survives its own appends. Other board edits
    // still invalidate the generation and stop the loop, including cross-tab refreshes.
    invalidateOtherRecommendations(kind)
    return
  }
  const failure = recommendationApplyFailures[kind][appliedIndex]
  const unconfirmed = failure?.outcomeUnknown ? failure : null
  recommendationApplyFailures[kind] = unconfirmed ? { 0: unconfirmed } : {}
  const results = recommendationResultsByKind[kind]
  const { candidates } = results
  if (!candidates.value[appliedIndex]) {
    invalidateRecommendationsForSceneChange({ notify: true })
    return
  }
  const retainedCandidates = candidates.value.slice(appliedIndex, appliedIndex + 1)
  const hadOtherCandidates = candidates.value.length > 1
  recommendationSceneGeneration += 1
  invalidateOtherRecommendations(kind)
  candidates.value = retainedCandidates
  results.message.value = hadOtherCandidates && !unconfirmed ? t('app.appliedRecommendationOnlyNotice') : ''
  results.filteredCount.value = 0
  results.filteredItems.value = []
  results.adjustedItems.value = kind === 'spec' ? [] : keepAppliedRecommendationAdjustments(results.adjustedItems.value, appliedIndex)
  for (const count of results.candidateCounts) count.value = 0
  results.isAppliedConfirmation.value = !unconfirmed
  results.applied.value = new Set(unconfirmed ? [] : [0])
  results.applying.value = new Set()
}

const invalidateRecommendationsForSceneChange = ({ notify = false }: { notify?: boolean } = {}) => {
  const hadRecommendationContext = ruleRecommendationRequested.value || deviceRecommendationRequested.value || specRecommendationRequested.value || isAnyRecommendationRunning()
  recommendationSceneGeneration += 1
  // Discard obsolete candidates and advance each request epoch. stopActiveRecommendation
  // keeps the transport alive until the server acknowledges cancellation; the
  // generation/epoch checks make the UI safe even if that acknowledgement races
  // with the scene change.
  invalidateRuleRecommendations()
  invalidateDeviceRecommendations()
  invalidateSpecRecommendations()
  if (notify && hadRecommendationContext) {
    notifyInfo(t('app.recommendationsInvalidatedByBoardRefresh'))
  }
}

const invalidateForFullSceneReplacement = () => {
  boardSceneGeneration += 1
  unconfirmedRuleOrder.value = null
  unconfirmedEnvironmentPatches.clear()
  // Scene import/clear opt out of fingerprint tracking, so mark staleness explicitly here.
  markVerificationResultStale()
  invalidateRecommendationsForSceneChange()
  notifyUndoJournalCleared()
}

/** Discard the prior revision when the server confirms a Board edit-history boundary. */
const notifyUndoJournalCleared = () => {
  syncBoardUndoAvailability({ canUndo: false, canRedo: false })
}

const formatRelationForDisplay = (relation: unknown): string => {
  const raw = String(relation ?? '').trim()
  const normalized = raw.toLowerCase().replace(/_/g, ' ')
  if (normalized === 'in') return t('app.relationIn')
  if (normalized === 'not in') return t('app.relationNotIn')
  return raw
}

const recommendedSpecTemplateLabel = (templateId: unknown): string => {
  const id = String(templateId ?? '')
  const detail = specTemplateDetails.find(template => template.id === id)
  return detail?.labelKey ? t(detail.labelKey) : detail?.label || id || t('app.customSpecification')
}

// 应用规约推荐 - 将推荐的规约添加到画布
const applySpecRecommendation = async (
  recommendation: SpecificationRecommendation, index: number, fromBatch = false
): Promise<RecommendationWriteReceipt> => {
  if (!fromBatch && isRecommendationBatchLocked('spec')) return
  if (!ensurePlaybackClosedForMutation()) return
  if (!ensureBoardDataReady(['nodes', 'templates', 'specs'])) return
  if (appliedSpecRecommendations.value.has(index)) {
    notifyBlocked(t('app.recommendationAlreadyApplied'))
    return
  }
  if (applyingSpecRecommendations.value.has(index) || recommendationApplyFailures.spec[index]?.outcomeUnknown) return
  const recommendationEpoch = specRecommendationPanel.epoch
  const requestSceneGeneration = recommendationSceneGeneration
  const requestToken = getToken()
  const applyAuthScopeEpoch = boardAuthScopeEpoch
  const isApplyAccountCurrent = () => isCurrentBoardAuthScope(applyAuthScopeEpoch) && isCurrentSessionToken(requestToken)
  const isApplyCandidateCurrent = () => isApplyAccountCurrent()
    && recommendationEpoch === specRecommendationPanel.epoch && requestSceneGeneration === recommendationSceneGeneration
  delete recommendationApplyFailures.spec[index]
  let keepRecommendation = false

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
    name: recommendation.name.trim(),
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
      nodes: nodes.value, modeCount: deviceModeCount
    })
  }

  if (specificationExists(newSpec)) {
    notifyBlocked(t('app.specDuplicate'))
    return
  }

  // 获取现有规约
  applyingSpecRecommendations.value.add(index)
  try {
    if (!isApplyCandidateCurrent() || isSceneReplacementInProgress.value) return
    return await enqueueBoardMutation<RecommendationWriteReceipt>(async () => {
      if (!isApplyCandidateCurrent()
        || !isBoardDataReady.value
        || isSceneReplacementInProgress.value) return
      const receipt = await readSpecificationCreationReceipt(newSpec, isApplyAccountCurrent)
      if (!receipt) return
      if (receipt.status === 'confirmed') {
        const { mutation } = receipt
        commitSemanticScene({ specs: mutation.currentItems, availability: mutation })
        keepRecommendation = true
        if (isApplyCandidateCurrent()) {
          appliedSpecRecommendations.value.add(index)
        }
        return 'confirmed'
      }
      if (!isApplyCandidateCurrent()) return
      if (receipt.status === 'unconfirmed') {
        keepRecommendation = true
        reportRecommendationApplyFailure('spec', index, receipt.error,
          specificationCreateRecoveryMessage(newSpec, receipt.snapshotRefreshed), true)
      } else if (isSpecificationDuplicateRejection(receipt.error)) {
        notifyBlocked(t('app.specDuplicate'))
      } else {
        reportRecommendationApplyFailure('spec', index, receipt.error, t('app.failedToSaveSpecification'))
      }
    }, {
      onSemanticChange: () => handleRecommendationApplySceneChange(
        keepRecommendation,
        () => preserveAppliedRecommendationAfterSceneChange('spec', index),
        () => invalidateRecommendationsForSceneChange({ notify: true })
      )
    })
  } finally {
    if (recommendationEpoch === specRecommendationPanel.epoch) {
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
const applyDeviceRecommendation = async (
  recommendation: DeviceRecommendation, index: number, fromBatch = false
): Promise<RecommendationWriteReceipt> => {
  if (!fromBatch && isRecommendationBatchLocked('device')) return
  if (!ensurePlaybackClosedForMutation()) return
  if (appliedDeviceRecommendations.value.has(index)) {
    notifyBlocked(t('app.recommendationAlreadyApplied'))
    return
  }
  if (applyingDeviceRecommendations.value.has(index) || recommendationApplyFailures.device[index]?.outcomeUnknown) return
  const recommendationEpoch = deviceRecommendationPanel.epoch
  const requestSceneGeneration = recommendationSceneGeneration
  const requestToken = getToken()
  const applyAuthScopeEpoch = boardAuthScopeEpoch
  const isApplyAccountCurrent = () => isCurrentBoardAuthScope(applyAuthScopeEpoch) && isCurrentSessionToken(requestToken)
  delete recommendationApplyFailures.device[index]
  let keepRecommendation = false
  const isApplyCandidateCurrent = () =>
    isApplyAccountCurrent() && recommendationEpoch === deviceRecommendationPanel.epoch
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
  
  // Keep one origin for the batch: focusing each new device moves the viewport.
  const origin = fromBatch ? recommendationBatch.value?.deviceOrigin : undefined
  const center = fromBatch
    ? origin && { x: origin.x + index * (DEFAULT_NODE_WIDTH + 24), y: origin.y }
    : getVisibleCanvasCenterWorld()
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
    const receipt = await createDeviceInstanceAt(
      template,
      () => center,
      confirmedLabel,
      runtime,
      {
        admissionGuard: isRecommendationCurrent,
        onReceipt: receipt => {
          if (!isApplyCandidateCurrent()) return
          keepRecommendation = receipt.status !== 'rejected'
          if (receipt.status === 'confirmed') appliedDeviceRecommendations.value.add(index)
          else if (receipt.status === 'unconfirmed') {
            reportRecommendationApplyFailure('device', index, receipt.error, unconfirmedDeviceCreationMessage(receipt), true)
          } else {
            reportRecommendationApplyFailure('device', index, receipt.error, t('app.saveNodesFailed'))
          }
        },
        onSemanticChange: () => handleRecommendationApplySceneChange(
          keepRecommendation,
          () => preserveAppliedRecommendationAfterSceneChange('device', index),
          () => invalidateRecommendationsForSceneChange({ notify: true })
        )
      }
    )
    if (isApplyAccountCurrent() && receipt?.status === 'confirmed') {
      reportEnvironmentChanges(receipt.environmentChanges)
      return 'confirmed'
    }
  } finally {
    if (recommendationEpoch === deviceRecommendationPanel.epoch) {
      applyingDeviceRecommendations.value.delete(index)
    }
  }
}

// ==== Simulation Logic ====
const isSimulating = ref(false)
const simulationError = ref<RunFailure | null>(null)
// The adopted simulation and its freshness outlive the playback surface.
const lastSimulationResult = ref<SimulationResultView | null>(null)
// Only notification handles live here; each notice owns its local result through its action closure.
const unsavedRunNotifications = new Set<() => void>()

const offerDeferredRunNotice = (
  options: Omit<Parameters<typeof notifyAvailableResult>[0], 'onAction' | 'onClose'>,
  open: () => void
) => {
  const ownsRun = captureBoardRunOwner()
  let close!: () => void
  close = notifyAvailableResult({
    ...options,
    onAction: () => {
      if (!ownsRun()) return true
      if (openModalDepth.value > 0 || !ensureHistoricalPlaybackUiAdmission()) return false
      closeOtherWorkflowSurfaces()
      open()
      return true
    },
    onClose: () => unsavedRunNotifications.delete(close)
  })
  unsavedRunNotifications.add(close)
}

onBeforeUnmount(() => {
  unsavedRunNotifications.forEach(close => close())
  unsavedRunNotifications.clear()
})

/** Adopt a completed trajectory and its freshness together before opening playback. */
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
  verificationResult.value = result
  verificationResultStale.value = semanticSceneChangeCount !== submissionSceneChanges
  void refreshCurrentBoardModelFingerprints()
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

const isSharedFuzzingVariable = (deviceId: string, key: string): boolean => {
  const node = resolveNodeRef(deviceId)
  if (!node) return false
  return resolveTemplateForNode(node)?.manifest.InternalVariables?.some(variable =>
    variable.Name === key && variable.IsInside === false && variable.Reads === true) ?? false
}

const knownFuzzEligibleSpecifications = computed(() =>
  specifications.value.filter(spec => isKnownFuzzingSpecificationSupported(spec, isSharedFuzzingVariable)))

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
  if (isVerifying.value && !verificationPanelTracksRun.value) {
    return t('app.verificationAnotherRunInProgress')
  }
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
  if (isModelPlaybackActive.value) {
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
const fuzzingCancelRequested = ref(false)
// Selection owns the foreground only. Task polling and cancellation survive panel switches.
const activeWorkflowPanel = shallowRef<BoardWorkflowPanel | null>(null)
const showVerificationPanel = computed(() => activeWorkflowPanel.value?.kind === 'verification')
const showSimulationPanel = computed(() => activeWorkflowPanel.value?.kind === 'simulation')
const showFuzzingPanel = computed(() => activeWorkflowPanel.value?.kind === 'fuzzing')
const showHistoryPanel = computed(() => activeWorkflowPanel.value?.kind === 'history')

const tracksRun = (kind: RunPanelKind): boolean => {
  const panel = activeWorkflowPanel.value
  return panel?.kind === kind && panel.view === 'run'
}
const verificationPanelTracksRun = computed(() => tracksRun('verification'))
const simulationPanelTracksRun = computed(() => tracksRun('simulation'))
const fuzzingPanelTracksRun = computed(() => tracksRun('fuzzing'))

// A launch still needs live previews while waiting for Board saves. A frozen task uses its own summary.
const fuzzingPreviewsInUse = computed(() =>
  (isFuzzing.value && asyncFuzzingTask.value.taskId === null)
  || (showFuzzingPanel.value && (!fuzzingPanelTracksRun.value || !fuzzingWatchedTask.value)))

const showFuzzingResultDialog = ref(false)
const fuzzingResult = ref<FuzzingRun | null>(null)
const fuzzingError = ref<RunFailure | null>(null)
const fuzzingFailureDetails = computed(() => fuzzingError.value ? formatRunFailure('fuzzing', fuzzingError.value) : null)
const fuzzingErrorMessage = computed(() => fuzzingFailureDetails.value?.message || null)
const fuzzingSettingsNotice = ref<{ key: string; params?: Record<string, number> } | null>(null)
const fuzzingSettingsNoticeMessage = computed(() => fuzzingSettingsNotice.value
  ? t(fuzzingSettingsNotice.value.key, fuzzingSettingsNotice.value.params || {}) : '')
const fuzzingResultLoading = ref(false)
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
  if (!fuzzingPreviewsInUse.value
    || fuzzingForm.explorationMode !== 'PAPER_COMPATIBLE'
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
  if (!fuzzingPreviewsInUse.value
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
    fuzzingPreviewsInUse,
    () => fuzzingForm.explorationMode,
    () => fuzzingForm.pathLength,
    paperDomainSemanticKey,
    fuzzingPreviewPrerequisitesReady
  ],
  ([inUse, mode]) => {
    invalidatePaperDomainPreview()
    if (mode !== 'PAPER_COMPATIBLE' && paperDomainStaleRecoveryActive.value) {
      paperDomainStaleRecoveryActive.value = false
      fuzzingError.value = null
    }
    if (inUse
      && mode === 'PAPER_COMPATIBLE'
      && validPaperPathLength()
      && fuzzingPreviewPrerequisitesReady.value) {
      schedulePaperDomainPreview()
    }
  },
  { flush: 'sync' }
)

watch(
  [fuzzingPreviewsInUse, fuzzingWorkloadSemanticKey, fuzzingPreviewPrerequisitesReady],
  ([inUse]) => {
    invalidateFuzzingWorkloadPreview()
    if (inUse && hasValidFuzzingBudget(fuzzingForm) && fuzzingPreviewPrerequisitesReady.value) {
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

const {
  fingerprints: currentBoardModelFingerprints,
  loading: loadingCurrentBoardModelFingerprints,
  invalidate: invalidateCurrentBoardModelFingerprints,
  refresh: refreshCurrentBoardModelFingerprints,
  adoptSnapshot: adoptBoardComparisonSnapshot
} = useBoardModelComparison(
  () => ({ nodes: nodes.value, rules: rules.value, specifications: specifications.value,
    environmentVariables: environmentVariables.value, deviceTemplates: deviceTemplates.value }),
  () => boardApi.getModelFingerprints(),
  () => hasHydratedBoardSnapshot.value
)

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
  isModelPlaybackActive.value || pendingHistoryDeleteKeys.value.size > 0
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
  fuzzNotificationStorageKeyForUser(boardOwnerUserId)

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
  return isFuzzing.value && taskId ? [taskId] : []
})

const watchedTaskId = (kind: RunPanelKind): number | undefined => {
  const ids = kind === 'verification' ? watchedVerificationTaskIds
    : kind === 'simulation' ? watchedSimulationTaskIds : watchedFuzzingTaskIds
  return ids.value[0]
}

const isWatchedTaskCancelled = (kind: RunPanelKind, taskId: number | null): boolean => {
  if (taskId === null) return false
  const current = kind === 'verification' ? asyncVerificationTask.value
    : kind === 'simulation' ? asyncSimulationTask.value : asyncFuzzingTask.value
  return current.taskId === taskId && current.statusKey === 'app.taskStatusCancelled'
}

const isWatchedTaskCancellationPending = (kind: RunPanelKind): boolean => {
  const taskId = watchedTaskId(kind)
  return taskId !== undefined && pendingTaskActionKeys.value.has(taskActionKey('cancel', kind, taskId))
}
const cancellingVerificationTask = computed(() => isWatchedTaskCancellationPending('verification'))
const cancellingSimulationTask = computed(() => isWatchedTaskCancellationPending('simulation'))
const cancellingFuzzingTask = computed(() => isWatchedTaskCancellationPending('fuzzing'))

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
  const groups = [
    {
      kind: 'verification',
      labelKey: 'app.verification',
      watched: asyncVerificationTask.value,
      cancelling: cancellingVerificationTask.value,
      tasks: activeVerificationTasks.value
    },
    {
      kind: 'fuzzing',
      labelKey: 'app.fuzzSearch',
      watched: asyncFuzzingTask.value,
      cancelling: cancellingFuzzingTask.value,
      tasks: activeFuzzingTasks.value
    },
    {
      kind: 'simulation',
      labelKey: 'app.simulation',
      watched: asyncSimulationTask.value,
      cancelling: cancellingSimulationTask.value,
      tasks: activeSimulationTasks.value
    }
  ] as const
  return groups.flatMap(({ kind, labelKey, watched, cancelling, tasks }) => {
    const watchedId = watchedTaskId(kind)
    const label = t(labelKey)
    const item = (id: number, status: string, progress?: number | null) => ({
      key: `${kind}-${id}`, kind, id, label, status, progress: normalizeTaskProgress(progress)
    })
    const items = tasks.filter(task => task.id !== watchedId)
      .map(task => item(task.id, formatTaskProgressStage(task.progressStage, task.status), task.progress))
    if (watchedId) items.unshift(item(watchedId, formatWatchedTaskStatus(watched.statusKey, cancelling), watched.progress))
    return items
  })
})

const invalidateTaskInboxRequests = () => {
  taskInboxRequests.invalidate()
  taskInboxBackgroundRefreshPromise = null
  taskInboxLoadingEpoch += 1
  loadingTaskHistory.value = false
}

const upsertTaskSummary = <T extends { id?: number },>(tasks: Ref<T[]>, task: Partial<T>) => {
  if (!task.id) return
  invalidateTaskInboxRequests()
  const next = task as T
  tasks.value = tasks.value.some(item => item.id === task.id)
    ? tasks.value.map(item => item.id === task.id ? { ...item, ...next } : item)
    : [next, ...tasks.value]
}

const upsertVerificationTaskSummary = (task: Partial<VerificationTaskSummary>) =>
  upsertTaskSummary(verificationTasks, task)

const upsertSimulationTaskSummary = (task: Partial<SimulationTaskSummary>) =>
  upsertTaskSummary(simulationTasks, task)

const upsertFuzzingTaskSummary = (task: Partial<FuzzingTaskSummary>) =>
  upsertTaskSummary(fuzzingTasks, task)

const hasCancelledTaskRow = (kind: RunPanelKind, taskId: number): boolean => {
  const tasks = kind === 'verification' ? verificationTasks.value
    : kind === 'simulation' ? simulationTasks.value : fuzzingTasks.value
  return tasks.some(row => row.id === taskId && row.status === 'CANCELLED')
}

const hasVisibleCancelledTaskRow = (kind: RunPanelKind, taskId: number): boolean =>
  showHistoryPanel.value && activeHistoryLayer.value === 'tasks' && hasCancelledTaskRow(kind, taskId)

const isTaskCancellationKnown = (kind: RunPanelKind, taskId: number): boolean =>
  hasCancelledTaskRow(kind, taskId) || isWatchedTaskCancelled(kind, taskId)

const notifyObservedTaskCancellation = (kind: RunPanelKind, taskId: number | null, confirmedLocally: boolean) => {
  if (confirmedLocally || (taskId !== null && hasVisibleCancelledTaskRow(kind, taskId))) return
  notifyInfo(t(kind === 'verification' ? 'app.verificationCancelled'
    : kind === 'simulation' ? 'app.simulationCancelled' : 'app.fuzzSearchCancelled'))
}

const applyTaskCancellationStatus = (kind: RunPanelKind, taskId: number, status: TaskCancellationResult['taskStatus']) => {
  const cancelled = status === 'CANCELLED'
  if (watchedTaskId(kind) === taskId) {
    const current = kind === 'verification' ? asyncVerificationTask.value
      : kind === 'simulation' ? asyncSimulationTask.value : asyncFuzzingTask.value
    const requested = kind === 'verification' ? verificationCancelRequested
      : kind === 'simulation' ? simulationCancelRequested : fuzzingCancelRequested
    current.statusKey = taskProgressStatusKey(undefined, status)
    requested.value = cancelled
  }
  if (!cancelled) return
  const tasks = kind === 'verification' ? verificationTasks.value
    : kind === 'simulation' ? simulationTasks.value : fuzzingTasks.value
  if (!tasks.some(task => task.id === taskId)) return
  const update = { id: taskId, status: 'CANCELLED' as const, progressStage: undefined }
  if (kind === 'verification') upsertVerificationTaskSummary(update)
  else if (kind === 'simulation') upsertSimulationTaskSummary(update)
  else upsertFuzzingTaskSummary(update)
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
      if (!resolvedTask && error?.response?.status === 404) {
        untrackFuzzTask(taskId)
        return
      }
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
  const isCurrent = () => taskInboxRequests.isCurrent(token)
  const read = async <T extends { id?: number; createdAt?: string },>(
    request: Promise<TaskInboxBatch<T>>, rows: Ref<T[]>,
    reconcile?: (tasks: T[], excludedIds: number[], isCurrent: () => boolean) => Promise<void>
  ) => {
    const { tasks, excludedIds } = await request
    if (!isCurrent()) return
    rows.value = mergeTaskSummariesPreservingExcluded(rows.value, tasks, excludedIds)
    if (reconcile) await reconcile(tasks, excludedIds, isCurrent)
  }
  if (showLoading) loadingTaskHistory.value = true
  try {
    const results = await Promise.allSettled([
      read(fetchVerificationTasks(), verificationTasks),
      read(fetchSimulationTasks(), simulationTasks),
      read(fetchFuzzingTasks(), fuzzingTasks, reconcileTrackedFuzzTasks)
    ])
    if (!isCurrent()) return false
    const failure = results.find(result => result.status === 'rejected')
    if (failure) throw failure.reason
    taskInboxError.value = null
    return true
  } catch (e: any) {
    if (!isCurrent()) return false
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

const loadUnpagedRuns = async <T,>(
  source: 'verification' | 'simulation',
  requests: ReturnType<typeof createPagedRequestCoordinator>,
  read: () => Promise<T[]>,
  adopt: (runs: T[]) => void,
  showError: boolean,
  failureKey: string
): Promise<boolean> => {
  if (boardLifecycleDisposed) return false
  const token = requests.beginReplace()
  try {
    const runs = await read()
    if (!requests.isCurrent(token)) return false
    adopt(runs || [])
    historyResultErrors[source] = null
    return true
  } catch (error) {
    if (!requests.isCurrent(token)) return false
    console.error(`Failed to load ${source} run history:`, error)
    historyResultErrors[source] = errorDetails(error)
    if (showError && !isHistoryResultErrorVisible(source)) {
      notifyError(t(failureKey))
    }
    return false
  } finally {
    requests.finish(token)
  }
}

const loadVerificationRuns = (showError = true) => loadUnpagedRuns(
  'verification', verificationHistoryRequests, boardApi.getVerificationRuns,
  runs => { verificationRuns.value = retainKnownVerificationHistoryAvailability(runs) },
  showError, 'app.failedToLoadVerificationHistory'
)

const loadSimulationRuns = (showError = true) => loadUnpagedRuns(
  'simulation', simulationHistoryRequests, simulationApi.getUserSimulations,
  runs => { simulationRuns.value = retainKnownSimulationHistoryAvailability(runs) },
  showError, 'app.failedToLoadSimulationHistory'
)

const executeFuzzingRunsRequest = async (
  token: PagedRequestToken,
  page: number,
  append: boolean
): Promise<boolean> => {
  try {
    const runs = await fuzzingApi.getRuns(page, FUZZ_RUN_HISTORY_PAGE_SIZE)
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
    return false
  } finally {
    fuzzingHistoryRequests.finish(token)
  }
}

const loadFuzzingRuns = (
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

  if (!append) return executeFuzzingRunsRequest(token, page, false)

  loadingMoreFuzzingRuns.value = true
  let trackedRequest: Promise<boolean>
  trackedRequest = executeFuzzingRunsRequest(token, page, true)
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
  await loadFuzzingRuns({ page: fuzzingRunsPage.value + 1, append: true })
}

const loadHistoryResults = async (): Promise<boolean> => {
  if (boardLifecycleDisposed) return false
  const loadingEpoch = ++historyResultsLoadingEpoch
  loadingResultHistory.value = true
  try {
    const results = await Promise.all([
      loadVerificationRuns(false),
      loadSimulationRuns(false),
      loadFuzzingRuns()
    ])
    if (loadingEpoch !== historyResultsLoadingEpoch) return false
    return results.every(Boolean)
  } finally {
    if (loadingEpoch === historyResultsLoadingEpoch) loadingResultHistory.value = false
  }
}

const refreshRunHistory = async ({ compareModel = true } = {}): Promise<boolean> => {
  const [tasksLoaded, resultsLoaded, addressedHistoryChecked] = await Promise.all([
    loadTaskInbox({ showLoading: false }),
    loadHistoryResults(),
    recheckAddressedHistory(),
    compareModel ? refreshCurrentBoardModelFingerprints() : null
  ])
  return tasksLoaded && resultsLoaded && addressedHistoryChecked
}

const refreshAllBoardState = async (): Promise<boolean> => {
  const results = await Promise.all([
    refreshBoardSnapshot(),
    refreshRunHistory({ compareModel: false })
  ])
  return results.every(Boolean)
}

const refreshHistoryLayer = async (layer: HistoryLayer): Promise<boolean> => {
  const [loaded] = await Promise.all([
    layer === 'results' ? loadHistoryResults() : loadTaskInbox(),
    refreshCurrentBoardModelFingerprints()
  ])
  return loaded
}

const setHistoryLayer = async (layer: HistoryLayer) => {
  retireHistoryPanelIntent()
  const intentEpoch = historyPanelIntentEpoch
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
  retireHistoryPanelIntent()
  const intentEpoch = historyPanelIntentEpoch
  activeHistoryResultFilter.value = filter
  if (showHistoryPanel.value
    && activeHistoryLayer.value === 'results'
    && (filter === 'all' || filter === 'fuzzing')) {
    const [loaded] = await Promise.all([loadFuzzingRuns(), refreshCurrentBoardModelFingerprints()])
    if (loaded && isCurrentHistoryPanelIntent(intentEpoch, 'results', filter)) {
      clearVisibleFuzzResultNotifications()
    }
  }
}

const retireHistoryPanelIntent = (cancelPendingRun = true) => {
  historyPanelIntentEpoch += 1
  if (!cancelPendingRun || !pendingHistoryRunTarget) return
  const target = pendingHistoryRunTarget
  pendingHistoryRunTarget = null
  if (appliedDeepLinkTarget !== target) return
  historyDetailRequests.invalidate()
  if (isSameBoardRunTarget(deepLinkTarget.value, target)) clearRunDeepLink()
}

const closeWorkflowPanel = (kind?: BoardWorkflowPanel['kind']) => {
  if (kind === undefined || activeWorkflowPanel.value?.kind === kind) {
    activeWorkflowPanel.value = null
  }
}

const closeHistoryPanel = (cancelPendingRun = true) => {
  retireHistoryPanelIntent(cancelPendingRun)
  closeWorkflowPanel('history')
}

const openRunPanel = (kind: RunPanelKind, view: RunPanelView, handoff: FuzzVerificationHandoffSource | null = null) => {
  retireHistoryPanelIntent()
  activeWorkflowPanel.value = kind === 'verification' ? { kind, view, handoff } : { kind, view }
}

const setRunPanelView = (kind: RunPanelKind, view: RunPanelView) => {
  const panel = activeWorkflowPanel.value
  if (panel?.kind === kind && panel.view !== view) {
    activeWorkflowPanel.value = { ...panel, view }
  }
}

// Workflow panels share one closing path; inline authoring disclosures remain available.
const closeOtherWorkflowSurfaces = () => {
  closeResultSurfaces()
  if (!showHistoryPanel.value) closeWorkflowPanel()
}

const openHistoryPanel = async (layer: HistoryLayer) => {
  closeResultSurfaces()
  activeWorkflowPanel.value = { kind: 'history' }
  await setHistoryLayer(layer)
}

const formatRunTimestamp = (value?: string): string => {
  if (!value) return t('app.unknown')
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString(locale.value.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en-US')
}

const refreshHistoryTasks = () => {
  if (showHistoryPanel.value && activeHistoryLayer.value === 'tasks') return setHistoryLayer('tasks')
}

const refreshHistoryResults = () => {
  if (showHistoryPanel.value && activeHistoryLayer.value === 'results') return setHistoryLayer('results')
}

const deleteHistoryRun = async (target: BoardRunTarget, options: {
  confirm: () => Promise<boolean>
  remove: () => Promise<void>
  read: () => Promise<unknown>
  forget: () => void
  successKey: string
  failedKey: string
  refreshHistory?: () => Promise<boolean>
}) => {
  const pendingKey = `${target.kind === 'exploration' ? 'fuzzing' : target.kind}:${target.runId}`
  if (!beginHistoryDelete(pendingKey)) return
  const ownsBoard = captureBoardRunOwner()
  try {
    if (!ownsBoard() || !await options.confirm() || !ownsBoard()) return
    const receipt = await readDeletionReceipt(options.remove, options.read, ownsBoard)
    if (!receipt) return
    if (receipt.status !== 'confirmed' && receipt.status !== 'absent') {
      if (receipt.status === 'unconfirmed') notifyBlocked(t('app.historyDeleteOutcomeUnconfirmed'))
      else notifyError(localizedErrorMessage(receipt.error, t(options.failedKey), locale.value))
      return
    }

    options.forget()
    dismissDeletedRunSurfaces(target)
    if (receipt.status === 'absent') {
      // A lost DELETE response could not notify other tabs through the HTTP interceptor.
      publishBoardInvalidation(currentAuthUserId.value, 'manual', 'run-history')
      notifyBlocked(t('app.historyDeleteOutcomeMissing'))
    }
    const refreshed = await options.refreshHistory?.()
    if (!ownsBoard() || receipt.status !== 'confirmed') return
    if (refreshed === false) notifyBlocked(t('app.historyRunDeletedRefreshPending'))
    else notifySuccess(t(options.successKey))
  } finally {
    finishHistoryDelete(pendingKey)
  }
}

const deleteVerificationRun = (run: VerificationRunSummary) => deleteHistoryRun({ kind: 'verification', runId: run.id }, {
  confirm: () => confirmDestructive({
    title: t('app.deleteVerificationRunTitle'),
    message: t('app.deleteVerificationRunMessage', {
      time: formatRunTimestamp(run.completedAt), counterexamples: run.counterexampleCount
    }),
    confirmText: t('app.delete')
  }),
  remove: () => boardApi.deleteVerificationRun(run.id),
  read: () => boardApi.getVerificationRun(run.id),
  forget: () => {
    verificationHistoryRequests.invalidate()
    unavailableVerificationRunIds.delete(run.id)
    for (const trace of run.counterexamples) {
      unavailableVerificationTraceIds.delete(trace.id)
      verificationRepairBlocks.value.delete(trace.id)
    }
    verificationRuns.value = verificationRuns.value.filter(item => item.id !== run.id)
  },
  successKey: 'app.verificationRunDeleted',
  failedKey: 'app.failedToDeleteVerificationRun'
})

const openVerificationRun = async (
  runId: number,
  isCurrent: () => boolean,
  checkedRun?: VerificationRun
) => {
  let runDetailLoaded = false
  try {
    const run = checkedRun ?? await boardApi.getVerificationRun(runId)
    if (!isCurrent()) return
    runDetailLoaded = true
    void refreshCurrentBoardModelFingerprints()
    const traces = shouldLoadVerificationEvidence(run.counterexampleCount)
      ? await boardApi.getVerificationRunTraces(runId)
      : []
    if (!isCurrent()) return
    verificationResult.value = buildVerificationResultFromRun(run, traces)
    verificationResultStale.value = false
    closeHistoryPanel(false)
  } catch (e: any) {
    if (!isCurrent()) return
    console.error('Failed to load verification run:', e)
    if (isPersistedHistoryDataInvalid(e)) {
      // A complete-run view needs every trace, but one damaged child must not disable its
      // independently addressable siblings in the history panel.
      const traceId = persistedHistoryInvalidRecordId(e, 'verification trace')
      if (traceId !== null) markVerificationTraceUnavailable(traceId)
      else if (!runDetailLoaded) markVerificationRunUnavailable(runId)
      reportUnusableDeepLink()
      notifyBlocked(t(runDetailLoaded
        ? 'app.historyRunHasUnavailableEvidenceDetail'
        : 'app.historyItemUnavailableDetail'))
      return
    }
    reportHistoryDetailFailure(e, 'app.failedToLoadVerificationRun')
  }
}

const formalRunState = (kind: FormalRunKind) => kind === 'verification'
  ? {
      running: isVerifying, asyncActive: asyncVerificationActive, watchedTask: asyncVerificationTask,
      tracksTask: verificationPanelTracksRun, cancelRequested: verificationCancelRequested, failure: verificationError
    }
  : {
      running: isSimulating, asyncActive: asyncSimulationActive, watchedTask: asyncSimulationTask,
      tracksTask: simulationPanelTracksRun, cancelRequested: simulationCancelRequested, failure: simulationError
    }

type FormalRunState = ReturnType<typeof formalRunState>

const showFormalRunFailure = (
  kind: FormalRunKind, state: FormalRunState, error: unknown, taskId: number | null, submitted: boolean
) => {
  if (isPollingAbortedError(error)) return
  if ((error as { code?: unknown } | null)?.code === 'TASK_STATUS_GONE') {
    notifyInfo(t('app.taskNoLongerAvailable'))
  } else if (isAsyncTaskCancelledError(error)) {
    state.failure.value = null
    notifyObservedTaskCancellation(kind, taskId, state.cancelRequested.value)
  } else {
    const verification = kind === 'verification'
    if (submitted) console.error(verification ? 'Verification failed:' : 'Simulation failed:', error)
    presentRunFailure(kind, verification ? 'app.verificationFailed' : 'app.simulationFailed', error, state.tracksTask.value)
  }
}

const finishFormalRun = (kind: FormalRunKind, state: FormalRunState) => {
  setRunPanelView(kind, 'settings')
  state.running.value = false
  state.asyncActive.value = false
  state.cancelRequested.value = false
}

const watchFormalTask = async (kind: FormalRunKind, taskId: number) => {
  const verification = kind === 'verification'
  const state = formalRunState(kind)
  const { running, asyncActive, watchedTask, tracksTask, cancelRequested, failure } = state
  if (running.value) {
    if (asyncActive.value && watchedTask.value.taskId === taskId) {
      if (!verification || !tracksTask.value) openRunPanel(kind, 'run')
    } else {
      notifyInfo(t('app.taskWatchAlreadyActive'))
    }
    return
  }

  const ownsRun = captureBoardRunOwner()
  const taskSummary = (verification ? verificationTasks : simulationTasks).value.find(task => task.id === taskId)
  failure.value = null
  running.value = true
  asyncActive.value = true
  cancelRequested.value = false
  watchedTask.value = {
    taskId,
    progress: normalizeTaskProgress(taskSummary?.progress),
    statusKey: taskProgressStatusKey(taskSummary?.progressStage, taskSummary?.status)
  }
  openRunPanel(kind, 'run')
  try {
    if (verification) {
      await pollAsyncVerification(taskId)
      return
    }
    // The user can edit this Board while the simulation is watched from the inbox.
    const watchSubmissionSceneChanges = semanticSceneChangeCount
    const result = await pollAsyncSimulation(taskId)
    if (!ownsRun()) return
    const summary = summarizeSimulationResult(result)
    if (summary) {
      simulationHistoryRequests.invalidate()
      simulationRuns.value = [summary, ...simulationRuns.value.filter(item => item.id !== summary.id)]
    }
    deliverSimulationRunResult(result, watchSubmissionSceneChanges)
  } catch (error) {
    if (ownsRun()) showFormalRunFailure(kind, state, error, taskId, false)
  } finally {
    if (ownsRun()) {
      finishFormalRun(kind, state)
      await loadTaskInbox({ showLoading: false })
    }
  }
}

const watchVerificationTask = (taskId: number) => watchFormalTask('verification', taskId)
const watchSimulationTask = (taskId: number) => watchFormalTask('simulation', taskId)

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
    specificationLabel: finding.violatedSpec.name
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

const deliverCompletedFuzzingRun = (taskId: number, run: FuzzingRun) => {
  untrackFuzzTask(taskId)
  const presentation = completeRunPresentation('fuzzing')
  if (presentation === 'foreground') {
    void refreshCurrentBoardModelFingerprints()
    presentFuzzingRun(run)
    return
  }
  const notificationShown = markFuzzNotificationUnread({
    taskId, runId: run.id, kind: 'COMPLETED', initiator: run.initiator,
    outcome: run.outcome, createdAt: run.completedAt
  })
  if (!notificationShown) return
  if (run.outcome === 'BUDGET_EXHAUSTED') notifyInfo(fuzzingCompletionMessage(run))
  else notifyBlocked(fuzzingCompletionMessage(run))
  if (presentation !== 'background') {
    notifyInfo(t(presentation === 'playback'
      ? 'app.fuzzResultDeferredForPlayback' : 'app.fuzzResultDeferredForModal'))
  }
}

const watchFuzzingTask = async (taskId: number) => {
  if (isFuzzing.value) {
    if (asyncFuzzingTask.value.taskId === taskId) {
      openRunPanel('fuzzing', 'run')
    } else {
      notifyInfo(t('app.taskWatchAlreadyActive'))
    }
    return
  }

  const ownsRun = captureBoardRunOwner()
  const taskSummary = fuzzingTasks.value.find(task => task.id === taskId)
  // Keep the running request separate from the editable form. Its persisted summary is
  // the only truthful source after a refresh or after the current Board has changed.
  fuzzingWatchedTask.value = taskSummary || null
  fuzzingSettingsNotice.value = null
  asyncFuzzingTask.value = {
    taskId,
    progress: normalizeTaskProgress(taskSummary?.progress),
    statusKey: taskProgressStatusKey(taskSummary?.progressStage, taskSummary?.status)
  }
  isFuzzing.value = true
  fuzzingCancelRequested.value = false
  fuzzingError.value = null
  paperDomainStaleRecoveryActive.value = false
  trackFuzzTask(taskId)
  openRunPanel('fuzzing', 'run')
  try {
    const run = await pollAsyncFuzzing(taskId)
    if (ownsRun()) deliverCompletedFuzzingRun(taskId, run)
  } catch (error: any) {
    if (ownsRun() && !isPollingAbortedError(error)) {
      if (error?.code === 'TASK_STATUS_GONE') {
        notifyInfo(t('app.taskNoLongerAvailable'))
      } else if (isAsyncTaskCancelledError(error)) {
        untrackFuzzTask(taskId)
        fuzzingError.value = null
        notifyObservedTaskCancellation('fuzzing', taskId, fuzzingCancelRequested.value)
      } else if (isFuzzTaskRecoveryPendingError(error)) {
        fuzzingError.value = null
        fuzzingSettingsNotice.value = { key: 'app.fuzzResultRecoveryPending' }
        if (!fuzzingPanelTracksRun.value) notifyInfo(fuzzingSettingsNoticeMessage.value)
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
        if (fuzzingPanelTracksRun.value) {
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
    if (ownsRun()) {
      setRunPanelView('fuzzing', 'settings')
      isFuzzing.value = false
      fuzzingCancelRequested.value = false
      fuzzingWatchedTask.value = null
      await loadTaskInbox({ showLoading: false })
    }
  }
}

const dismissBoardTask = (kind: RunPanelKind, taskId: number) => withTaskActionLock(
  taskActionKey('dismiss', kind, taskId), async () => {
    const ownsBoard = captureBoardRunOwner()
    const api = kind === 'verification' ? boardApi : kind === 'simulation' ? simulationApi : fuzzingApi
    const receipt = await readDeletionReceipt(() => api.deleteTask(taskId), () => api.getTask(taskId), ownsBoard)
    if (!receipt) return
    if (receipt.status === 'unconfirmed') {
      notifyBlocked(t(receipt.snapshotRefreshed ? 'app.taskDismissOutcomePresent' : 'app.taskDismissOutcomeUnconfirmed'))
      return
    }
    if (receipt.status === 'rejected') {
      notifyError(extractApiErrorMessage(receipt.error, t('app.failedToDismissTask')))
      await loadTaskInbox({ showLoading: false })
      return
    }

    invalidateTaskInboxRequests()
    if (kind === 'verification') {
      verificationTasks.value = verificationTasks.value.filter(task => task.id !== taskId)
    } else if (kind === 'simulation') {
      simulationTasks.value = simulationTasks.value.filter(task => task.id !== taskId)
    } else {
      fuzzingTasks.value = fuzzingTasks.value.filter(task => task.id !== taskId)
      clearFuzzNotifications(undefined, taskId)
      untrackFuzzTask(taskId)
    }
    if (receipt.status === 'absent') {
      publishBoardInvalidation(currentAuthUserId.value, 'manual', 'tasks')
      notifyBlocked(t('app.taskDismissOutcomeMissing'))
    } else {
      notifySuccess(t('app.taskDismissed'))
    }
  }
)

const openTaskInbox = async () => {
  if (isModelPlaybackActive.value) {
    notifyBlocked(t('app.playbackReadOnlyCloseFirst'))
    return
  }
  await openHistoryPanel('tasks')
}

const miniTaskCancelLabel = (kind: 'verification' | 'fuzzing' | 'simulation') => kind === 'verification'
  ? t('app.cancelVerificationTask')
  : kind === 'fuzzing' ? t('app.cancelFuzzingTask') : t('app.cancelSimulationTask')

const ensureHistoricalPlaybackUiAdmission = (): boolean => {
  if (isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return false
  }
  if (isTracePlaybackActive.value) {
    notifyBlocked(t('app.closeCounterexampleFirst'))
    return false
  }
  if (isSimulationPlaybackActive.value) {
    notifyBlocked(t('app.closeCurrentSimulationFirst'))
    return false
  }
  if (isAnyRecommendationRunning()) {
    notifyBlocked(t('app.recommendationGenerationInProgress'))
    return false
  }
  return ensureLiveBoardEditorClosedForPlayback()
}

const revalidateHistoricalPlaybackAfterLoad = async (
  isCurrent: () => boolean,
  initialMutationEpoch: number
): Promise<boolean> => {
  const result = await revalidateHistoricalPlaybackAdmission({
    waitForPendingMutations: waitForPendingBoardMutations,
    isRequestCurrent: isCurrent,
    initialMutationEpoch,
    currentMutationEpoch: () => boardMutationAdmissionEpoch,
    recheckUiAdmission: ensureHistoricalPlaybackUiAdmission
  })
  if (!isCurrent()) return false
  if (result === 'board-changed') {
    notifyBlocked(t('app.historicalPlaybackDeferredForBoardChange'))
  }
  return result === 'admitted'
}

const playHistoricalResult = async <T,>({
  isCurrent, load, play, failureLog, failedKey, markUnavailable
}: {
  isCurrent: () => boolean
  load: () => Promise<T>
  play: (result: T) => void
  failureLog: string
  failedKey: string
  markUnavailable: (error: unknown) => string
}) => {
  if (!ensureHistoricalPlaybackUiAdmission()) return
  const initialMutationEpoch = boardMutationAdmissionEpoch
  try {
    const result = await load()
    if (!await revalidateHistoricalPlaybackAfterLoad(isCurrent, initialMutationEpoch) || !isCurrent()) return
    play(result)
  } catch (error) {
    if (!isCurrent()) return
    console.error(failureLog, error)
    if (isPersistedHistoryDataInvalid(error)) {
      const messageKey = markUnavailable(error)
      reportUnusableDeepLink()
      notifyBlocked(t(messageKey))
    } else {
      reportHistoryDetailFailure(error, failedKey)
    }
  }
}

const selectAndPlayVerificationTrace = (
  traceId: number, expectedRunId: number, isCurrent: () => boolean
) => playHistoricalResult({
  isCurrent,
  load: () => boardApi.getVerificationTrace(traceId),
  play: trace => {
    if (!verificationTraceBelongsToRun(trace, expectedRunId)) {
      reportUnusableDeepLink()
      return
    }
    if (!trace?.states?.length) {
      notifyBlocked(t('app.traceHasNoStates'))
      return
    }
    closeResultDialog()
    closeHistoryPanel(false)
    startPlayback({ kind: 'counterexample', trace }, trace.states.length - 1)
  },
  failureLog: 'Failed to load trace:',
  failedKey: 'app.failedToLoadTrace',
  markUnavailable: () => {
    markVerificationTraceUnavailable(traceId)
    return 'app.historyTraceUnavailableDetail'
  }
})

const openFixForVerificationTrace = (trace: TraceSummary) => {
  const run = verificationRuns.value.find(item => item.id === trace.verificationTaskId)
  if (!trace.dataAvailable || !run?.dataAvailable || verificationTraceRepairBlock(trace)
    || getVerificationRepairRunBlock(compareRunToCurrentBoard(run, 'verification'))) return
  closeHistoryPanel()
  openFixDialog(trace.id, trace.violatedSpecId, trace.violatedSpec.name)
}

const downloadSavedRunSmv = async (download: () => Promise<void>) => {
  try {
    await download()
  } catch (error) {
    if ((error as { response?: { status?: number } } | null)?.response?.status === 404) {
      notifyBlocked(t('app.smvModelNotAvailable'))
    } else {
      notifyError(t('app.smvDownloadFailed'))
    }
  }
}

// Verification traces share a run's model. A saved simulation is itself the trajectory.
const downloadVerificationRunSmv = (runId: number) =>
  downloadSavedRunSmv(() => boardApi.downloadRunSmvModel(runId))
const downloadSimulationTraceSmv = (traceId: number) =>
  downloadSavedRunSmv(() => simulationApi.downloadSimulationSmvModel(traceId))

const selectAndPlaySimulationTrace = (traceId: number, isCurrent: () => boolean) => playHistoricalResult({
  isCurrent,
  load: () => simulationApi.getSimulation(traceId),
  play: trace => {
    const result = simulationResultFromTrace(trace)
    closeHistoryPanel(false)
    lastSimulationResult.value = result
    // A saved result has no local submission window. Its freshness comes from model comparison.
    simulationResultStale.value = false
    openSimulationAnimationFromSavedStates(false)
  },
  failureLog: 'Failed to load simulation trace:',
  failedKey: 'app.failedToLoadSimulationRun',
  markUnavailable: () => {
    markSimulationTraceUnavailable(traceId)
    return 'app.historyItemUnavailableDetail'
  }
})

const deleteSimulationRun = (run: SimulationTraceSummary) => deleteHistoryRun({ kind: 'simulation', runId: run.id }, {
  confirm: () => confirmDestructive({
    title: t('app.deleteSimulationRunTitle'),
    message: t('app.deleteSimulationRunMessage', { time: formatRunTimestamp(run.createdAt) }),
    confirmText: t('app.delete')
  }),
  remove: () => simulationApi.deleteSimulation(run.id),
  read: () => simulationApi.getSimulation(run.id),
  forget: () => {
    simulationHistoryRequests.invalidate()
    unavailableSimulationTraceIds.delete(run.id)
    simulationRuns.value = simulationRuns.value.filter(item => item.id !== run.id)
  },
  successKey: 'app.simulationRunDeleted',
  failedKey: 'app.failedToDeleteSimulationRun'
})

const fuzzingCompletionMessage = (run: AvailableFuzzingRunSummary | FuzzingRun): string => {
  if (compareRunToCurrentBoard(run, 'fuzzing') === 'SEMANTICS_CHANGED') return t('app.runBoardSemanticsChanged')
  if (run.outcome === 'FOUND_VIOLATION') {
    return t('app.fuzzSearchCompletedWithFindings', { count: run.findings.length })
  }
  if (run.outcome === 'BUDGET_EXHAUSTED') return t('app.fuzzNoViolationWithinBudget')
  return t('app.fuzzInconclusiveDetail')
}

const openFuzzingRun = async (
  runId: number,
  isCurrent: () => boolean
) => {
  if (isModelPlaybackActive.value) {
    notifyBlocked(t('app.playbackReadOnlyCloseFirst'))
    return
  }
  const requestEpoch = ++fuzzingResultRequestEpoch
  try {
    fuzzingError.value = null
    fuzzingResult.value = null
    fuzzingResultLoading.value = true
    showFuzzingResultDialog.value = true
    // Its ready event dismisses history after the lazy dialog captures its opener.
    await nextTick()
    if (!isCurrent()) return
    const [run] = await Promise.all([
      fuzzingApi.getRun(runId),
      refreshCurrentBoardModelFingerprints()
    ])
    if (!isCurrent()) return
    presentFuzzingRun(run)
  } catch (e: any) {
    if (!isCurrent()) return
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
      reportUnusableDeepLink()
      notifyBlocked(t(findingId !== null
        ? 'app.historyRunHasUnavailableFindingDetail'
        : 'app.historyItemUnavailableDetail'))
      return
    }
    reportHistoryDetailFailure(e, 'app.failedToLoadFuzzingRun')
  } finally {
    if (requestEpoch === fuzzingResultRequestEpoch) {
      fuzzingResultLoading.value = false
      if (!isCurrent() && !fuzzingResult.value) {
        showFuzzingResultDialog.value = false
      }
    }
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

const deleteFuzzingRun = (run: FuzzingRunSummary) => deleteHistoryRun({ kind: 'exploration', runId: run.id }, {
  confirm: () => confirmDestructive({
    title: t('app.deleteFuzzingRunTitle'),
    message: t('app.deleteFuzzingRunMessage', { time: formatRunTimestamp(run.completedAt || run.createdAt) }),
    confirmText: t('app.delete')
  }),
  remove: () => fuzzingApi.deleteRun(run.id),
  read: () => fuzzingApi.getRun(run.id),
  forget: () => {
    fuzzingHistoryRequests.invalidate()
    unavailableFuzzingRunIds.delete(run.id)
    for (const finding of run.findings) {
      unavailableFuzzingFindingIds.delete(finding.id)
    }
    fuzzingRuns.value = fuzzingRuns.value.filter(item => item.id !== run.id)
    fuzzingRunsPage.value = 0
    fuzzingRunsHasMore.value = false
    clearFuzzNotifications(undefined, run.id)
  },
  successKey: 'app.fuzzingRunDeleted',
  failedKey: 'app.failedToDeleteFuzzingRun',
  refreshHistory: loadFuzzingRuns
})

const selectAndPlayFuzzingFinding = (
  findingId: number, runId: number, isCurrent: () => boolean
) => playHistoricalResult({
  isCurrent,
  load: () => fuzzingApi.getFinding(findingId),
  play: replay => {
    if (!fuzzingFindingBelongsToRun(replay.finding, runId)) {
      reportUnusableDeepLink()
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
    startPlayback({ kind: 'fuzzing', trace, finding }, finding.firstViolationStep)
  },
  failureLog: 'Failed to load fuzzing finding:',
  failedKey: 'app.failedToLoadFuzzingFinding',
  markUnavailable: error => {
    const persistedFindingId = persistedHistoryInvalidRecordId(error, 'FuzzFinding')
    if (persistedFindingId !== null) {
      markFuzzingFindingUnavailable(persistedFindingId)
      return 'app.historyFindingUnavailableDetail'
    }
    markFuzzingRunUnavailable(runId)
    return 'app.historyItemUnavailableDetail'
  }
})

const fuzzVerificationHandoff = computed(() => {
  const panel = activeWorkflowPanel.value
  const source = panel?.kind === 'verification' ? panel.handoff : null
  if (!source) return null
  const targetPresent = source.specificationId === undefined
    || specifications.value.some(spec => spec.id === source.specificationId)
  return {
    runId: source.runId,
    specificationLabel: source.specificationLabel,
    targetPresent: source.specificationId !== undefined && boardDataLoadState.specs !== 'ready' ? null : targetPresent,
    boardComparison: compareModelSnapshot(
      source.modelSnapshot, isBoardDataReady.value ? currentBoardModelFingerprints.value : null, 'fuzzing'
    )
  }
})

const fuzzVerificationHandoffNoticeKey = computed(() => {
  const handoff = fuzzVerificationHandoff.value
  if (!handoff) return null
  if (handoff.targetPresent === false) return 'app.fuzzVerificationHandoffTargetMissing'
  if (handoff.boardComparison === 'SEMANTICS_CHANGED') return 'app.runBoardSemanticsChanged'
  if (handoff.boardComparison === 'CHANGED') return 'app.fuzzVerificationHandoffScopeChanged'
  if (handoff.targetPresent === null || handoff.boardComparison === 'UNAVAILABLE') {
    if (loadingCurrentBoardModelFingerprints.value || allBoardDataKeys.some(key => boardDataLoadState[key] === 'loading')) {
      return 'app.fuzzVerificationHandoffChecking'
    }
    return handoff.targetPresent === null
      ? 'app.fuzzVerificationHandoffTargetUnavailable' : 'app.runBoardComparisonUnavailable'
  }
  return handoff.boardComparison === 'NOT_COMPARED' ? 'app.runBoardNotCompared' : null
})

const availableFuzzRunForFinding = (finding: FuzzingFindingSummary | FuzzingFinding) => {
  if ('dataAvailable' in finding && finding.dataAvailable === false) return null
  if (fuzzingResult.value?.id === finding.fuzzTaskId) return fuzzingResult.value
  const historyRun = fuzzingRuns.value.find(run => run.id === finding.fuzzTaskId)
  return historyRun?.dataAvailable ? historyRun : null
}

/**
 * Hand off from candidate evidence to the formal verifier.
 *
 * Capture the evidence before dismissing the result. The verification panel compares that saved
 * evidence with the live Board throughout the handoff.
 */
const showVerificationPanelForHandoff = (source: FuzzVerificationHandoffSource | null) => {
  dismissFuzzingResult()
  openRunPanel('verification', 'settings', source)
}

const openFormalVerificationForFuzzFinding = (finding: FuzzingFindingSummary | FuzzingFinding) => {
  const sourceRun = availableFuzzRunForFinding(finding)
  const specificationLabel = finding.violatedSpec?.name
    || ('specificationLabel' in finding ? finding.specificationLabel : '')
    || finding.violatedSpecId
  showVerificationPanelForHandoff({
    runId: finding.fuzzTaskId,
    specificationId: finding.violatedSpecId,
    specificationLabel,
    modelSnapshot: sourceRun?.modelSnapshot
  })
}

const openFormalVerificationForCurrentBoard = () => {
  const sourceRun = fuzzingResult.value
  showVerificationPanelForHandoff(sourceRun ? {
    runId: sourceRun.id,
    modelSnapshot: sourceRun.modelSnapshot
  } : null)
}

const closeVerificationPanel = () => closeWorkflowPanel('verification')

// The logo returns to the canvas without interrupting running tasks.
const resetWorkspace = () => {
  closePlayback()
  closeResultSurfaces()
  pendingHistoryRunTarget = null
  retireHistoryPanelIntent(false)
  closeWorkflowPanel()
  if (route.query.run || route.query.trace || route.query.finding) {
    void router.replace({ query: applyBoardRunTarget(route.query, null) })
  }
}

const reuseFuzzingSettings = () => {
  const run = fuzzingResult.value
  if (!run) return
  if (!canOpenTaskSettings('fuzzing')) return
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
  openRunPanel('fuzzing', 'settings')
}

// Floating panel visibility state
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
const simulationCancelRequested = ref(false)

const cancellationTaskLabel = (kind: RunPanelKind, taskId: number) =>
  `${t(kind === 'verification'
    ? 'app.verificationTaskName'
    : kind === 'fuzzing' ? 'app.fuzzTaskName' : 'app.simulationTaskName')} #${taskId}`

const notifyTaskCancellationResult = (
  kind: RunPanelKind,
  result: Pick<TaskCancellationResult, 'taskId' | 'cancellationOutcome'> & { executionMayStillBeStopping?: boolean }
) => {
  const task = cancellationTaskLabel(kind, result.taskId)
  const statusVisible = () => (tracksRun(kind) && watchedTaskId(kind) === result.taskId)
    || hasVisibleCancelledTaskRow(kind, result.taskId)
  switch (result.cancellationOutcome) {
    case 'ACCEPTED':
      if (statusVisible()) return
      notifyInfo(t(result.executionMayStillBeStopping
          ? 'app.taskCancellationAcceptedStopping'
          : 'app.taskCancellationAccepted', { task }))
      break
    case 'ALREADY_CANCELLED':
      if (statusVisible()) return
      notifyInfo(t('app.taskAlreadyCancelled', { task }))
      break
    case 'ALREADY_COMPLETED':
      if (kind === 'verification' && showResultDialog.value
        && verificationResult.value?.historyPersistence.status === 'SAVED'
        && verificationResult.value.historyPersistence.runId === result.taskId) return
      if (kind === 'simulation' && simulationPlaybackResult.value?.historyPersistence.status === 'SAVED'
        && simulationTasks.value.some(task => task.id === result.taskId
          && task.simulationTraceId === simulationPlaybackResult.value?.historyPersistence.runId)) return
      if (kind === 'fuzzing' && showFuzzingResultDialog.value && fuzzingResult.value?.id === result.taskId) return
      notifyBlocked(t('app.taskAlreadyCompleted', { task }))
      break
    case 'ALREADY_FAILED':
      notifyBlocked(t('app.taskAlreadyFailed', { task }))
      break
    default:
      notifyBlocked(t('app.taskCancellationNotAccepted', { task }))
  }
}

const closeSimulationPanel = () => closeWorkflowPanel('simulation')
const closeFuzzingPanel = () => closeWorkflowPanel('fuzzing')

// These are non-modal tool panels: the canvas behind them stays live and focus is
// deliberately not trapped, so they render as `role="region"` rather than
// `role="dialog"`. `useModalAccessibility` is reused here only for Escape-to-close and
// focus restoration. Anything that claims `role="dialog"` + `aria-modal="true"` must
// keep `trapFocus` on.
const floatingPanelAccessibility = {
  trapFocus: false,
  shouldRestoreFocus: () => !automaticRunPresentationBlock()
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

// Fix dialog 状态
const showFixDialog = ref(false)
const fixTraceId = ref<number | null>(null)
const fixViolatedSpecId = ref<string>('')
const fixViolatedSpecName = ref<string>('')

// 打开 Fix 弹窗
const openFixDialog = (traceId: number, violatedSpecId: string, name: string) => {
  if (fixResultDialogRef.value?.canOpenTrace?.(traceId) === false) {
    notifyBlocked(t('app.fixTraceSwitchBlockedByActiveSearch'))
    showFixDialog.value = true
    return
  }
  fixTraceId.value = traceId
  fixViolatedSpecId.value = violatedSpecId
  fixViolatedSpecName.value = name
  dismissCompletionNotifications()
  showFixDialog.value = true
}

const verificationRepairBlocks = ref(new Map<number, VerificationRepairBlock>())
const verificationRunRepairBlock = computed(() => getVerificationRepairRunBlock(
  verificationBoardComparison.value, loadingCurrentBoardModelFingerprints.value, verificationResultStale.value
))
const verificationRunNeedsReverify = computed(() => verificationRunRepairBlock.value === 'app.verificationResultStaleReverify'
  || verificationRunRepairBlock.value === 'app.runBoardSemanticsChanged')
const verificationTraceRepairBlock = (trace: Trace | TraceSummary) =>
  getVerificationRepairTraceBlock(trace, verificationRepairBlocks.value)

// Keep the recovery action for a blocked trace without withdrawing its siblings' actions.
const verificationNeedsReverify = computed(() => verificationRunNeedsReverify.value
  || verificationResult.value?.traces.some(trace => trace.id != null && verificationRepairBlocks.value.has(trace.id)))

const verificationResultTraceRepairBlock = (trace: Trace) => {
  if (!hasPersistedVerificationTrace(verificationResult.value, trace)) {
    return verificationResult.value?.historyPersistence.status === 'OUTCOME_UNKNOWN'
      ? 'app.verificationTracePersistenceUnknownFixUnavailable' : 'app.verificationTraceNotPersistedFixUnavailable'
  }
  return verificationTraceRepairBlock(trace)
}

const canFixVerificationResultTrace = (trace: Trace): trace is PersistedTrace => (
  verificationRunRepairBlock.value === null && verificationResultTraceRepairBlock(trace) === null
)

const openFixForVerificationResultTrace = (trace: Trace) => {
  if (!canFixVerificationResultTrace(trace)) return
  openFixDialog(trace.id, trace.violatedSpecId, trace.violatedSpec?.name || t('app.unknownSpecification'))
}

const handleFixModelSemanticsChanged = (traceId: number) => {
  if (boardLifecycleDisposed || currentAuthUserId.value !== boardOwnerUserId
    || !showFixDialog.value || fixTraceId.value !== traceId) return
  // The API rejected this saved trace. A later Board comparison cannot restore its repair eligibility.
  verificationRepairBlocks.value.set(traceId, 'app.runBoardSemanticsChanged')
  void refreshCurrentBoardModelFingerprints()
}

const presentUnconfirmedTaskCancellation = async (error: unknown, selected: boolean) => {
  const title = t('app.taskCancellationUnconfirmed')
  const suggestion = t('app.cancelFailureNextStep')
  if (automaticRunPresentation(selected) === 'foreground') {
    await acknowledgeFailure({ title, message: localizedErrorMessage(error, title, locale.value),
      suggestion, ...errorDetails(error) })
    return
  }
  console.error(title, error)
  notifyBlocked(`${title} — ${suggestion}`)
}

const presentCompletedTaskAfterCancellation = async (
  kind: RunPanelKind, taskId: number, status: SimulationTask | FuzzingTask | VerificationTask | null,
  historyIntent: number | null
) => {
  const runId = kind === 'simulation' ? (status && 'simulationTraceId' in status ? status.simulationTraceId : undefined)
    : (status && 'runId' in status ? status.runId : undefined) ?? taskId
  if (!runId || historyIntent === null || !isCurrentHistoryPanelIntent(historyIntent, 'tasks')
    || openModalDepth.value > 0 || !ensureHistoricalPlaybackUiAdmission()) {
    notifyTaskCancellationResult(kind, { taskId, cancellationOutcome: 'ALREADY_COMPLETED' })
    return
  }
  await openRunTarget({ kind: kind === 'fuzzing' ? 'exploration' : kind, runId }, true)
}

const cancelBoardTask = (kind: RunPanelKind, taskId: number) => withTaskActionLock(
  taskActionKey('cancel', kind, taskId), async () => {
    const ownsBoard = captureBoardRunOwner()
    const wasWatched = watchedTaskId(kind) === taskId
    const wasInProgress = wasWatched && tracksRun(kind)
    const inTaskInbox = () => showHistoryPanel.value && activeHistoryLayer.value === 'tasks'
    const historyIntent = inTaskInbox() ? historyPanelIntentEpoch : null
    const canPresent = () => ownsBoard() && !isTaskCancellationKnown(kind, taskId)
      && (!wasWatched || watchedTaskId(kind) === undefined || watchedTaskId(kind) === taskId)
    if (!canPresent()) return

    const api = kind === 'verification' ? boardApi : kind === 'simulation' ? simulationApi : fuzzingApi
    let receipt: TaskCancellationResult | undefined
    let error: unknown
    try {
      receipt = await api.cancelTask(taskId)
    } catch (cause) {
      error = cause
    }
    if (!canPresent()) return

    // Completed jobs are omitted from inbox reads and the watcher may have excluded this row.
    const task = !receipt || (receipt.taskStatus === 'COMPLETED' && !wasWatched && kind === 'simulation')
      ? await api.getTask(taskId).catch(() => null) : null
    if (!canPresent()) return
    const status = receipt?.taskStatus ?? task?.status
    const isWatched = watchedTaskId(kind) === taskId
    if (status && !isActiveTaskStatus(status)) applyTaskCancellationStatus(kind, taskId, status)

    // A watched terminal task already refreshes the inbox when its poll settles.
    const refresh = wasWatched && isWatched && (status === 'CANCELLED' || status === 'COMPLETED')
      ? null : loadTaskInbox({ showLoading: false })
    try {
      if (status === 'COMPLETED') {
        if (!wasWatched && !isWatched) await presentCompletedTaskAfterCancellation(kind, taskId, task, historyIntent)
      } else if (receipt) {
        notifyTaskCancellationResult(kind, receipt)
      } else if (status === 'CANCELLED' || status === 'FAILED') {
        notifyTaskCancellationResult(kind, { taskId, cancellationOutcome: status === 'CANCELLED' ? 'ALREADY_CANCELLED' : 'ALREADY_FAILED' })
      } else {
        await presentUnconfirmedTaskCancellation(error,
          (wasInProgress && isWatched && tracksRun(kind))
          || (historyIntent !== null && isCurrentHistoryPanelIntent(historyIntent, 'tasks')))
      }
    } finally {
      await refresh
    }
  }
)

const cancelTaskFromProgress = (kind: RunPanelKind) => {
  const taskId = watchedTaskId(kind)
  if (taskId !== undefined) return cancelBoardTask(kind, taskId)
}

const cancelAsyncVerification = () => cancelTaskFromProgress('verification')
const cancelAsyncSimulation = () => cancelTaskFromProgress('simulation')
const cancelAsyncFuzzing = () => cancelTaskFromProgress('fuzzing')

const applyFixOnBoard = async (
  traceId: number, write: () => Promise<FixApplyResult | null>
): Promise<FixBoardApplyOutcome> => {
  const ownsRun = captureBoardRunOwner()
  const token = getToken()
  const ownsBoard = () => ownsRun() && isCurrentSessionToken(token)
  const outcome = await enqueueBoardMutation(async () => {
    if (!ownsBoard() || verificationRepairBlocks.value.has(traceId)) return null
    try {
      const result = await write()
      if (!ownsBoard()) return null
      if (result) commitSemanticScene({ rules: result.rules, availability: result })
      return result
    } catch (error: unknown) {
      if (!ownsBoard()) return null
      const response = (error as { response?: {
        status?: number; data?: { data?: { reasonCode?: string } }
      } } | null)?.response
      if (isDefinitiveMutationRejection(error) || (response?.status === 503
        && response.data?.data?.reasonCode === 'FIX_APPLY_PREFLIGHT_UNAVAILABLE')) throw error

      console.error('Automatic fix apply response could not be confirmed:', error)
      verificationRepairBlocks.value.set(traceId, 'app.fixApplyUnconfirmedReverify')
      // Keep recovery in the write's queue slot so a waiting refresh or edit cannot overtake it.
      return { snapshotRefreshed: await refreshBoardSnapshot() }
    }
  }).catch(error => {
    if (!ownsBoard() || isPollingAbortedError(error)) return null
    throw error
  })
  if (!ownsBoard()) return null
  if (outcome && 'snapshotRefreshed' in outcome) {
    notifyBlocked(outcome.snapshotRefreshed
      ? t('app.fixApplyOutcomeUnconfirmedAfterRefresh')
      : t('app.fixApplyOutcomeUnknownRefreshFailed'))
    return 'unconfirmed'
  }
  return outcome
}

const handleFixApplied = (traceId: number) => {
  if (verificationResult.value?.traces.some(trace => trace.id === traceId)) verificationFixApplied.value = true
  else notifySuccess(t('app.fixAppliedWithSignedEvidence'))
}

const canOpenWorkflowPanel = (kind: BoardWorkflowPanel['kind']) => {
  if (isModelPlaybackActive.value) {
    notifyBlocked(t('app.playbackReadOnlyCloseFirst'))
    return false
  }
  if (isAnyRecommendationRunning()) {
    notifyBlocked(t('app.recommendationGenerationInProgress'))
    return false
  }
  if (kind === 'fuzzing' && isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return false
  }
  return true
}

const isRunKindActive = (kind: RunPanelKind) => kind === 'verification' ? isVerifying.value
  : kind === 'simulation' ? isSimulating.value : isFuzzing.value

const canOpenTaskSettings = (kind: RunPanelKind) => {
  if (isRunKindActive(kind)) {
    notifyInfo(t('app.taskSettingsBlockedByActiveRun'))
    return false
  }
  return canOpenWorkflowPanel(kind)
}

const togglePanel = (kind: RunPanelKind) => {
  if (activeWorkflowPanel.value?.kind === kind) {
    closeWorkflowPanel(kind)
    return
  }
  if (!canOpenWorkflowPanel(kind)) return

  if (kind === 'fuzzing') {
    dismissAllNotifications()
    if (!isFuzzing.value) fuzzingWatchedTask.value = null
    fuzzingSettingsNotice.value = null
  }
  dismissCompletionNotifications()
  closeResultSurfaces()
  openRunPanel(kind, isRunKindActive(kind) ? 'run' : 'settings')
}

const reopenTaskSettings = (kind: RunPanelKind) => {
  if (!canOpenTaskSettings(kind)) return
  dismissCompletionNotifications()
  closeResultSurfaces()
  if (kind === 'fuzzing') fuzzingSettingsNotice.value = null
  openRunPanel(kind, 'settings')
}

const openHistoryFromActionDock = () => {
  if (showHistoryPanel.value) {
    closeHistoryPanel()
    return
  }
  if (!canOpenWorkflowPanel('history')) return
  if (unreadFuzzNotificationCount.value > 0) {
    const layer: HistoryLayer = unreadFailedFuzzCount.value > 0 ? 'tasks' : 'results'
    if (layer === 'results') activeHistoryResultFilter.value = 'fuzzing'
    void openHistoryPanel(layer)
    return
  }
  const workInFlight = isVerifying.value || isSimulating.value || isFuzzing.value
  void openHistoryPanel(workInFlight ? 'tasks' : 'results')
}

const openSimulationAnimationFromSavedStates = (adoptRunAddress = true) => {
  const result = lastSimulationResult.value
  if (!result) {
    notifyError(t('app.playbackSnapshotUnavailable'))
    return
  }
  if (!startPlayback({ kind: 'simulation', result }, 0) || !adoptRunAddress) return
  const persistence = result.historyPersistence
  if (persistence.status === 'SAVED') {
    staleDeepLink.value = false
    void navigateToRunTarget({ kind: 'simulation', runId: persistence.runId }, 'push')
  } else clearRunDeepLink()
}

const startPlayback = (source: BoardPlaybackSource, initialStateIndex: number): boolean => {
  const scene = source.kind === 'simulation' ? source.result.playbackScene : source.trace.playbackScene
  if (!scene) {
    notifyError(t('app.playbackSnapshotUnavailable'))
    return false
  }
  void refreshCurrentBoardModelFingerprints()
  resetPlaybackViewport()
  playbackTerminalTab.value = 'changes'
  playbackPanelOpen.value = true
  boardPanels.control.collapsed = true
  boardPanels.inspector.collapsed = true
  activePlayback.value = { ...source, scene: deepClone(scene) }
  goToState(initialStateIndex)
  return true
}

const closePlayback = () => {
  if (!activePlayback.value) return false
  stopPlayback()
  activePlayback.value = null
  resetPlaybackViewport()
  return true
}

// Navigation owns its incoming address. Only dismissal clears the replay's link.
const dismissPlayback = () => {
  if (closePlayback()) clearRunDeepLink()
}
onBeforeUnmount(closePlayback)

// An arriving result cannot take ownership of a replay or a focus-trapping surface.
const automaticRunPresentationBlock = () => foregroundRunBlock(isModelPlaybackActive.value, openModalDepth.value > 0)

const automaticRunPresentation = (selected: boolean) =>
  chooseRunPresentation(selected && !historyDetailRequests.hasPendingReplace(), automaticRunPresentationBlock())

const completeRunPresentation = (kind: RunPanelKind) => {
  const selected = tracksRun(kind)
  const presentation = automaticRunPresentation(selected)
  if (selected) closeWorkflowPanel(kind)
  return presentation
}

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

const captureBoardHistoryOwner = () => {
  const ownsBoard = captureBoardRunOwner()
  const token = getToken()
  return () => ownsBoard() && isCurrentSessionToken(token)
}

const {
  canUndo: canUndoBoardEdit,
  canRedo: canRedoBoardEdit,
  isApplying: isApplyingBoardEditUndo,
  hasHistoryChanged: hasBoardUndoHistoryChanged,
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
    semanticChanged: false
  }),
  // Serializes with every other board mutation, and re-checks admission when the slot is reached:
  // `isBlocked()` is evaluated once before queueing, so without this a undo queued behind a slow
  // delete could land after playback started.
  submit: work => enqueueBoardMutation(work, {
    admissionGuard: () => !isBoardUndoBlocked()
  }),
  captureOwner: captureBoardHistoryOwner,
  reconcile: refreshBoardSnapshot,
  onApplied: result => announceAppliedBoardUndo(result),
  isIgnorableError: error => isPollingAbortedError(error)
    || error instanceof BoardMutationAdmissionCancelledError,
  isBlocked: isBoardUndoBlocked,
  report: (reasonCode, direction, error, reconciled) => {
    if (reasonCode === 'blocked') {
      notifyBlocked(t('app.boardUndoBlocked'))
      return
    }
    if (reasonCode === 'replay-blocked') {
      notifyBlocked(t('app.boardUndoReplayUnconfirmed'))
      return
    }
    if (reasonCode === 'nothing') {
      notifyInfo(direction === 'undo'
        ? t('app.boardUndoNothingToApply')
        : t('app.boardUndoRedoNothingToApply'))
      return
    }
    if (reasonCode === 'conflict') {
      const conflict = error as { response?: { data?: { data?: { reasonCode?: string } } } }
      if (conflict?.response?.data?.data?.reasonCode === 'BOARD_EDIT_HISTORY_CHANGED') {
        notifyBlocked(t(reconciled ? 'app.boardUndoHistoryChanged' : 'app.boardUndoConflictRefreshFailed'))
        return
      }
      notifyBlocked(t(reconciled
        ? 'app.boardUndoConflict'
        : 'app.boardUndoConflictRefreshFailed'))
      void offerClearUnusableUndoHistory()
      return
    }
    if (reasonCode === 'failed') {
      notifyError(localizedErrorMessage(error, t('app.boardUndoFailed'), locale.value))
      return
    }
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
const offerClearUnusableUndoHistory = async () => {
  if (clearUndoHistoryConfirmationPending) return
  const isCurrent = captureBoardHistoryOwner()
  const admissionGuard = () => isCurrent() && !isBoardUndoBlocked()
  const isConflict = (error: unknown) => (error as { response?: { status?: number } })?.response?.status === 409
  clearUndoHistoryConfirmationPending = true
  try {
    const preview = await enqueueBoardMutation(
      () => boardApi.previewBoardEditHistoryClear(),
      { admissionGuard, trackSemanticChange: false }
    )
    if (!isCurrent()) return
    if (preview.entryCount === 0) {
      // The preview has no revision and cannot pair empty history with the visible scene.
      if (canUndoBoardEdit.value || canRedoBoardEdit.value) {
        const refreshed = await enqueueBoardMutation(() => refreshBoardSnapshot(), { admissionGuard })
        if (!isCurrent()) return
        if (!refreshed || canUndoBoardEdit.value || canRedoBoardEdit.value) {
          notifyBlocked(t(refreshed ? 'app.boardUndoHistoryChanged' : 'app.boardUndoConflictRefreshFailed'))
          return
        }
      }
      notifyInfo(t('app.boardUndoHistoryAlreadyEmpty'))
      return
    }
    if (unconfirmedUndoHistoryClearTokens.has(preview.impactToken)) {
      notifyBlocked(t('app.boardUndoClearHistoryPreviewUnconfirmed'))
      return
    }
    if (!await confirmDestructive({
      title: t('app.boardUndoClearHistoryTitle'),
      message: t('app.boardUndoClearHistoryMessage', { count: preview.entryCount }),
      confirmText: t('app.boardUndoClearHistoryAction')
    })) return
    await enqueueBoardMutation(async () => {
      const receipt = await readBoardWriteReceipt(() => boardApi.clearBoardEditHistory(preview.impactToken), isCurrent, {
        refreshOnRejection: isConflict
      })
      if (!receipt) return
      if (receipt.status === 'confirmed') {
        syncBoardUndoAvailability(receipt.mutation)
        notifyInfo(t('app.boardUndoHistoryCleared'))
      } else if (receipt.status === 'unconfirmed') {
        unconfirmedUndoHistoryClearTokens.add(preview.impactToken)
        const message = !receipt.snapshotRefreshed ? 'app.boardUndoClearHistoryOutcomeUnknown'
          : canUndoBoardEdit.value || canRedoBoardEdit.value ? 'app.boardUndoClearHistoryOutcomeRefreshed'
            : 'app.boardUndoClearHistoryOutcomeEmpty'
        notifyBlocked(t(message))
      } else if (isConflict(receipt.error)) {
        notifyBlocked(t(receipt.snapshotRefreshed ? 'app.boardUndoClearHistoryStale' : 'app.boardUndoClearHistoryStaleRefreshFailed'))
      } else {
        notifyError(extractApiErrorMessage(receipt.error, t('app.boardUndoClearHistoryFailed')))
      }
    }, { admissionGuard })
  } catch (error) {
    if (!isCurrent() || isPollingAbortedError(error)
      || error instanceof BoardMutationAdmissionCancelledError) return
    notifyError(extractApiErrorMessage(error, t('app.boardUndoClearHistoryFailed')))
  } finally {
    clearUndoHistoryConfirmationPending = false
  }
}

async function refreshBoardIfEditHistoryChanged() {
  if (await hasBoardUndoHistoryChanged()) await requestBoardSnapshotRefresh({ force: true })
}

// A playback transition changes the viewport receiving pointer input.
watch([isCanvasNavigationLocked, isModelPlaybackActive], ([locked, playback], [, previousPlayback]) => {
  if (!locked && playback === previousPlayback) return
  finishCanvasPan()
  finishCanvasMapDrag()
})


const activePlaybackStates = computed<TraceState[]>(() =>
  simulationPlaybackResult.value?.states ?? currentTrace.value?.states ?? []
)
const totalStates = computed(() => activePlaybackStates.value.length)
const {
  selectedStateIndex: activePlaybackStateIndex,
  isPlaying: isPlaybackPlaying,
  seek: goToState,
  stop: stopPlayback,
  toggle: togglePlayback
} = usePlaybackCursor(totalStates)
const activePlaybackState = computed(() => activePlaybackStates.value[activePlaybackStateIndex.value])
const highlightedTrace = computed(() => activePlaybackState.value ? {
  states: activePlaybackStates.value,
  selectedStateIndex: activePlaybackStateIndex.value
} : null)

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
  dismissPlayback,
  opener => document.querySelector<HTMLElement>(opener?.closest('[data-testid="trace-history-panel"]')
    ? '[data-testid="open-history-panel"]' : `[data-testid="${playbackFocusFallbackId}"]`),
  { trapFocus: false, shouldRestoreFocus: () => !isModelPlaybackActive.value
    && !isResultSurfaceVisible.value && !isWorkflowPanelOpen.value }
)

const canvasHighlightedTrace = computed(() => {
  const trace = highlightedTrace.value
  if (!trace) return null

  // The active playback source owns all violation evidence.
  const violatedSpec = currentTrace.value?.violatedSpec
  const violationDeviceIds = [...new Set([
    ...(violatedSpec?.devices || []).map(device => device.deviceId),
    ...[violatedSpec?.aConditions, violatedSpec?.ifConditions, violatedSpec?.thenConditions]
      .flatMap(conditions => (conditions || []).map(condition => condition.deviceId))
  ].filter((deviceId): deviceId is string => !!deviceId))]

  return {
    ...trace,
    violationStateIndex: counterexampleViolationStep.value,
    violationStateIndexes: counterexampleViolationSteps.value,
    violationDeviceIds
  }
})

const activePlaybackDevices = computed(() => activePlaybackState.value?.devices || [])
const activePlaybackEnvironmentVariables = computed(() => activePlaybackState.value?.envVariables || [])
const activePlaybackCompromisedAutomationLinks = computed(() => activePlaybackState.value?.compromisedAutomationLinks || [])

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
  activePlaybackState.value?.triggeredRules || [])

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
  if (!isTracePlaybackActive.value) return false
  return LIVENESS_TEMPLATES.has(String(currentTrace.value?.violatedSpec?.templateId))
})

const playbackLoopRange = computed(() => findPlaybackLoopRange(activePlaybackStates.value))
const activePlaybackLoopRange = computed(() => {
  const range = playbackLoopRange.value
  // The explanation uses state numbers. The canvas and rail use indices.
  return range && { start: range.start + 1, end: range.end + 1 }
})

/**
 * Whether the current playback step is the loop-back state.
 *
 * When true, the PlaybackChangesPanel explains the cycle instead of describing this step like any other.
 * On a one-state cycle that displaces the generic "no observable changes" message, because there the
 * absence of changes *is* the state's meaning; on a longer cycle the step carries real changes and the
 * explanation is what stops it from reading as the path continuing.
 */
const activePlaybackIsLoopBackState = computed(() => activePlaybackState.value?.loopBack === true)

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

/** Saved results stay in history; other results need a local owner until explicitly opened or discarded. */
const notifyAutomaticPlaybackDeferred = (
  result: SimulationResultView,
  submissionSceneChanges: number,
  presentation: Exclude<RunPresentation, 'foreground'>
) => {
  if (result.historyPersistence.status === 'SAVED') {
    if (presentation !== 'background') {
      notifyInfo(t(presentation === 'playback'
        ? 'app.simulationPlaybackDeferredForReplay' : 'app.simulationPlaybackDeferredForModal'))
    }
    return
  }
  offerDeferredRunNotice({
    title: t('app.simulationTemporaryResultTitle'),
    message: t('app.simulationTemporaryResultMessage'),
    actionLabel: t('app.simulationTemporaryResultOpen'),
    discardLabel: t('app.discardLocalResult')
  }, () => {
    adoptSimulationRunResult(result, submissionSceneChanges)
    openSimulationAnimationFromSavedStates()
  })
}

const deliverSimulationRunResult = (result: SimulationResultView, submissionSceneChanges: number) => {
  const presentation = completeRunPresentation('simulation')
  if (presentation === 'foreground') {
    adoptSimulationRunResult(result, submissionSceneChanges)
    openSimulationAnimationFromSavedStates()
    return
  }
  notifySimulationOutcome(result, result.historyPersistence.status === 'SAVED')
  notifyAutomaticPlaybackDeferred(result, submissionSceneChanges, presentation)
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

/**
 * Which surfaces claim the space the map viewport occupies.
 *
 * Scoped to the viewport rectangle, not the whole map card: the zoom and fit controls in the same card
 * are the board's viewport controls and must survive every one of these, because a result panel is
 * exactly when a user zooms to look at what it points at.
 */
const isWorkflowPanelOpen = computed(() => activeWorkflowPanel.value !== null)

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

// Verification context of the trace currently being viewed, derived from the trace's own snapshot
// (backend TraceDto) rather than the live verification form.
const activeTraceContext = computed(() => {
  return deriveTraceContext(currentTrace.value)
})

const currentBoardRuleIds = computed(() => rules.value
  .map(rule => rule.id)
  .filter((id): id is string => !!id)
  .map(String))
const currentBoardDeviceIds = computed(() => nodes.value.map(node => normalizePlaybackDeviceId(node.id)))







// 选择并播放指定索引的反例路径动画
const selectAndPlayTrace = (traceIndex: number) => {
  // 互斥检查：如果模拟动画正在显示，则不允许打开反例路径动画
  if (isSimulationPlaybackActive.value) {
    notifyBlocked(t('app.closeCurrentSimulationFirst'))
    return
  }
  
  if (isAnyRecommendationRunning()) {
    notifyBlocked(t('app.recommendationGenerationInProgress'))
    return
  }
  if (!ensureLiveBoardEditorClosedForPlayback()) return
  const traces = verificationResult.value?.traces
  if (traces && traceIndex >= 0 && traceIndex < traces.length) {
    const trace = traces[traceIndex]
    if (hasPersistedVerificationTrace(verificationResult.value, trace)) {
      void openRunTarget({ kind: 'verification', runId: trace.verificationTaskId, traceId: trace.id })
      return
    }
    closeResultDialog()
    startPlayback({ kind: 'counterexample', trace }, trace.states.length - 1)
  }
}

const recheckAddressedHistory = async (): Promise<boolean> => {
  // Foreground exploration completion can show a persisted result without giving it a URL.
  const foregroundExploration = !appliedDeepLinkTarget && !deepLinkTarget.value && showFuzzingResultDialog.value
    ? fuzzingResult.value : null
  const target = foregroundExploration
    ? { kind: 'exploration' as const, runId: foregroundExploration.id }
    : appliedDeepLinkTarget
  if (!target) return true
  const ownsRun = captureBoardRunOwner()
  const ownsTarget = () => ownsRun() && (foregroundExploration
    ? !appliedDeepLinkTarget && !deepLinkTarget.value && showFuzzingResultDialog.value
      && fuzzingResult.value === foregroundExploration
    : appliedDeepLinkTarget === target && isSameBoardRunTarget(deepLinkTarget.value, target))
  if (!ownsTarget()) return true
  try {
    // History lists can be paginated, and a counterexample can be deleted independently of its run.
    if (target.kind === 'verification') {
      if (target.traceId !== undefined) {
        await boardApi.getVerificationTrace(target.traceId)
      } else {
        const run = await boardApi.getVerificationRun(target.runId)
        if (!ownsTarget()) return true
        const result = verificationResult.value
        if (result?.historyPersistence.runId === target.runId) {
          const savedIds = new Set(run.counterexamples.map(trace => trace.id))
          result.traces = result.traces.filter(trace => trace.id === undefined || savedIds.has(trace.id))
        } else if (!result && historyDetailRequests.hasPendingReplace()) {
          // The older evidence read may still include a deleted trace. Reuse the checked run.
          await applyDeepLinkTarget(target, run)
        }
      }
    } else if (target.kind === 'simulation') {
      await simulationApi.getSimulation(target.runId)
    } else {
      await fuzzingApi.getTask(target.runId)
    }
    return true
  } catch (error) {
    if (!ownsTarget()) return true
    if (!isRecordMissing(error)) return false

    if (foregroundExploration) {
      closeFuzzingResult()
      notifyBlocked(t('app.openFuzzingRunDeletedElsewhere'))
      return true
    }
    const playback = activePlaybackKind.value
    void applyDeepLinkTarget(null)
    if (target.kind === 'simulation' && lastSimulationResult.value?.historyPersistence.runId === target.runId) {
      lastSimulationResult.value = null
      simulationResultStale.value = false
    }
    clearRunDeepLink()
    if (target.kind === 'simulation') {
      notifyBlocked(t('app.openSimulationRunDeletedElsewhere'))
    } else if (target.kind === 'exploration') {
      notifyBlocked(t(playback === 'fuzzing'
        ? 'app.openFuzzingFindingRunDeletedElsewhere'
        : 'app.openFuzzingRunDeletedElsewhere'))
    } else {
      notifyBlocked(t(target.traceId !== undefined
        ? 'app.openCounterexampleRunDeletedElsewhere'
        : 'app.openRunDeletedElsewhere'))
    }
    return true
  }
}

const dismissDeletedRunSurfaces = ({ kind, runId }: BoardRunTarget) => {
  const matchesRun = (target: BoardRunTarget | null) => target?.kind === kind && target.runId === runId
  // A navigation can claim a new target before its URL has been committed.
  const ownsLink = matchesRun(deepLinkTarget.value) && (!appliedDeepLinkTarget || matchesRun(appliedDeepLinkTarget))
  if (matchesRun(pendingHistoryRunTarget)) pendingHistoryRunTarget = null

  if (kind === 'verification') {
    if (activePlaybackKind.value === 'counterexample' && currentTrace.value?.verificationTaskId === runId) closePlayback()
    if (verificationResult.value?.historyPersistence.runId === runId) closeResultDialog()
  } else if (kind === 'simulation') {
    if (simulationPlaybackResult.value?.historyPersistence.runId === runId) closePlayback()
    if (lastSimulationResult.value?.historyPersistence.runId === runId) {
      lastSimulationResult.value = null
      simulationResultStale.value = false
    }
  } else {
    if (activeFuzzingFinding.value?.fuzzTaskId === runId) closePlayback()
    if (fuzzingResult.value?.id === runId || (ownsLink && fuzzingResultLoading.value)) closeFuzzingResult()
  }
  if (ownsLink) clearRunDeepLink()
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

const playbackChangeProps = computed(() => {
  const kind = activePlaybackKind.value
  if (!kind) return null
  const violationStep = counterexampleViolationStep.value
  return {
    kind,
    changes: activePlaybackChanges.value,
    environmentChanges: activePlaybackEnvironmentChanges.value,
    triggeredRules: activePlaybackTriggeredRules.value,
    currentRuleIds: currentBoardRuleIds.value,
    animatedEdgeCount: activePlaybackAnimatedEdgeCount.value,
    stateNumber: activePlaybackStateIndex.value + 1,
    totalStates: activePlaybackStates.value.length,
    inputEvents: activeFuzzingStepInputEvents.value,
    bundledDeviceIds: bundledPlaybackDeviceIds.value,
    bundledEnvironmentNames: bundledPlaybackEnvironmentNames.value,
    violationStateNumber: violationStep === undefined ? undefined : violationStep + 1,
    isLoopBackState: activePlaybackIsLoopBackState.value,
    loopRange: activePlaybackLoopRange.value,
    isLivenessViolation: activePlaybackIsLivenessViolation.value
  }
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
    const range = playbackLoopRange.value
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

const deliverVerificationRunResult = (result: VerificationResult, submissionSceneChanges: number): RunPresentation => {
  const presentation = completeRunPresentation('verification')
  const historyStatus = result.historyPersistence.status
  const unconfirmedSave = historyStatus === 'FAILED' || historyStatus === 'OUTCOME_UNKNOWN'
  if (unconfirmedSave) {
    notifyBlocked(t(historyStatus === 'OUTCOME_UNKNOWN'
      ? 'app.verificationHistorySaveOutcomeUnknown' : 'app.verificationHistorySaveFailed'))
  }
  if (presentation === 'foreground') {
    adoptVerificationRunResult(result, submissionSceneChanges)
    notifyVerificationOutcome(result, { presenting: true })
    return presentation
  }
  notifyVerificationOutcome(result)
  if (unconfirmedSave) {
    offerDeferredRunNotice({
      title: t('app.verificationTemporaryResultTitle'),
      message: t('app.verificationTemporaryResultMessage'),
      actionLabel: t('app.openResult'),
      discardLabel: t('app.discardLocalResult')
    }, () => adoptVerificationRunResult(result, submissionSceneChanges))
  } else if (presentation !== 'background') {
    notifyInfo(t(presentation === 'playback'
      ? 'app.verificationResultDeferredForPlayback' : 'app.verificationResultDeferredForModal'))
  }
  return presentation
}

const submitBackgroundTask = async <T extends { id: number },>(
  kind: RunPanelKind, submit: () => Promise<T>, ownsRun: () => boolean
): Promise<{ taskId: number; task: T | null } | null> => {
  try {
    const task = await submit()
    return ownsRun() ? { taskId: task.id, task } : null
  } catch (error: unknown) {
    if (!ownsRun()) return null
    const failure = error as {
      code?: unknown
      response?: { status?: number; data?: { data?: { reasonCode?: unknown; taskId?: unknown; taskKind?: unknown } } }
    } | null
    const details = failure?.response?.data?.data
    const unknownDispatch = failure?.response?.status === 503 && details?.reasonCode === 'TASK_DISPATCH_OUTCOME_UNKNOWN'
    if (unknownDispatch) {
      const { taskId, taskKind } = details
      if (taskKind === (kind === 'fuzzing' ? 'fuzz' : kind)
        && typeof taskId === 'number' && Number.isSafeInteger(taskId) && taskId > 0) {
        // The ID can be checked by the normal poller. Only a task read can supply a task row.
        return { taskId, task: null }
      }
    }
    if (unknownDispatch || (isAxiosError(error) && !error.response)
      || failure?.code === RUN_RESPONSE_INCOMPLETE_CODE || failure?.code === FUZZ_RESPONSE_INCOMPLETE_CODE) {
      notifyBlocked(t('app.taskSubmissionUnconfirmed'))
      return null
    }
    throw error
  }
}

const taskProgressFromSubmission = ({ taskId, task }: {
  taskId: number
  task: Pick<VerificationTask, 'progress' | 'progressStage' | 'status'> | null
}) => ({
  taskId,
  progress: normalizeTaskProgress(task?.progress),
  statusKey: task ? taskProgressStatusKey(task.progressStage, task.status) : 'app.taskDispatchChecking'
})

const submitFormalRun = async (
  kind: FormalRunKind, isAsync: boolean, run: (ownsRun: () => boolean) => Promise<boolean>
): Promise<boolean> => {
  const state = formalRunState(kind)
  if (state.running.value) return false
  if (!isAsync && (kind === 'verification' ? synchronousSimulationRunning : synchronousVerificationRunning).value) {
    notifyBlocked(t('app.formalOperationBusy'))
    return false
  }
  if (isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return false
  }

  // Watch and submit share a slot. Claim it before waiting for any Board writes.
  const ownsRun = captureBoardRunOwner()
  state.running.value = true
  setRunPanelView(kind, 'run')
  state.asyncActive.value = isAsync
  state.cancelRequested.value = false
  if (isAsync) state.watchedTask.value = { taskId: null, progress: 0, statusKey: 'app.taskInitializing' }

  try {
    return await run(ownsRun)
  } catch (error) {
    if (ownsRun()) showFormalRunFailure(kind, state, error, state.watchedTask.value.taskId, true)
    return false
  } finally {
    if (ownsRun()) finishFormalRun(kind, state)
  }
}

const handleVerify = (): Promise<boolean> => {
  const isAsync = verificationForm.isAsync
  return submitFormalRun('verification', isAsync, async ownsRun => {
    await waitForPendingBoardMutations()
    if (!ownsRun() || !ensureBoardDataReady()) return false
    if (nodes.value.length === 0) {
      notifyBlocked(t('app.noDevicesToVerify'))
      return false
    }
    if (specifications.value.length === 0) {
      notifyBlocked(t('app.noSpecsToVerify'))
      return false
    }
    if (!assertRulesHaveTriggers(rules.value)) return false
    if (verificationAttackConfigurationError.value) {
      notifyError(verificationAttackConfigurationError.value)
      return false
    }
    if (verificationForm.attackMode === 'ANY_UP_TO_BUDGET') validateAttackBudget(verificationForm.attackBudget)

    dismissCompletionNotifications()
    verificationError.value = null
    verificationResult.value = null
    verificationResultStale.value = false
    const verifySubmissionSceneChanges = semanticSceneChangeCount
    const req = buildVerificationRequestPayload({
      attackScenario: buildRunAttackScenario(verificationForm),
      enablePrivacy: verificationForm.enablePrivacy
    })

    // Handle async or sync verification
    if (isAsync) {
      const submission = await submitBackgroundTask('verification', () => boardApi.verifyAsync(req), ownsRun)
      if (!submission || !ownsRun()) return false
      asyncVerificationTask.value = taskProgressFromSubmission(submission)
      if (submission.task) upsertVerificationTaskSummary(submission.task)
      await pollAsyncVerification(submission.taskId, verifySubmissionSceneChanges)
      return ownsRun()
    }

    const result = await boardApi.verify(req)
    if (!ownsRun()) return false
    if (deliverVerificationRunResult(result, verifySubmissionSceneChanges) !== 'foreground'
      || result.historyPersistence.status !== 'SAVED') {
      void loadVerificationRuns(false)
    }
    return true

  })
}

const runFuzzing = async (): Promise<boolean> => {
  if (isFuzzing.value) return false
  if (isSceneReplacementInProgress.value) {
    notifyBlocked(t('app.sceneReplacementInProgress'))
    return false
  }
  const ownsRun = captureBoardRunOwner()
  asyncFuzzingTask.value = { taskId: null, progress: 0, statusKey: 'app.taskInitializing' }
  isFuzzing.value = true
  setRunPanelView('fuzzing', 'run')
  fuzzingWatchedTask.value = null
  fuzzingCancelRequested.value = false
  let request: FuzzingRequest | undefined
  let submittedTaskId: number | null = null
  let submittedTaskInitiator: RunInitiator = 'USER'

  try {
    await waitForPendingBoardMutations()
    if (!ownsRun() || !ensureBoardDataReady(['templates', 'nodes', 'environment', 'rules', 'specs'])) return false
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
      ...(requestTargetSpecIds.length > 0 ? { targetSpecIds: [...requestTargetSpecIds] } : {}),
      ...(fuzzingForm.seed === null ? {} : { seed: fuzzingForm.seed })
    }
    if (fuzzingForm.explorationMode === 'PAPER_COMPATIBLE') {
      const paperDomainFingerprint = paperDomainPreview.value?.modelFingerprint
      if (!isValidFuzzPaperDomainFingerprint(paperDomainFingerprint)) {
        notifyBlocked(t('app.fuzzPaperDomainRequired'))
        return false
      }
      request = { ...requestFields, explorationMode: 'PAPER_COMPATIBLE', paperDomainFingerprint }
    } else {
      request = { ...requestFields, explorationMode: 'BOARD_SNAPSHOT' }
    }

    fuzzingError.value = null
    fuzzingSettingsNotice.value = null
    const requestToSubmit = request
    const submission = await submitBackgroundTask('fuzzing', () => fuzzingApi.startAsync(requestToSubmit), ownsRun)
    if (!submission || !ownsRun()) return false
    submittedTaskId = submission.taskId
    trackFuzzTask(submittedTaskId)
    asyncFuzzingTask.value = taskProgressFromSubmission(submission)
    if (submission.task) {
      submittedTaskInitiator = submission.task.initiator
      upsertFuzzingTaskSummary(submission.task)
      fuzzingWatchedTask.value = submission.task
    }

    const run = await pollAsyncFuzzing(submittedTaskId)
    if (!ownsRun()) return false
    deliverCompletedFuzzingRun(submittedTaskId, run)
    return true
  } catch (error: any) {
    if (!ownsRun() || isPollingAbortedError(error)) return false
    if (error?.code === 'TASK_STATUS_GONE') {
      notifyInfo(t('app.taskNoLongerAvailable'))
    } else if (isAsyncTaskCancelledError(error)) {
      if (submittedTaskId) untrackFuzzTask(submittedTaskId)
      fuzzingError.value = null
      notifyObservedTaskCancellation('fuzzing', asyncFuzzingTask.value.taskId, fuzzingCancelRequested.value)
    } else if (submittedTaskId && isFuzzTaskRecoveryPendingError(error)) {
      fuzzingError.value = null
      fuzzingSettingsNotice.value = { key: 'app.fuzzResultRecoveryPending' }
      if (!fuzzingPanelTracksRun.value) {
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
      if (!fuzzingPanelTracksRun.value) {
        notifyError(fuzzingErrorMessage.value!)
      }
    } else {
      console.error('Fuzz search failed:', error)
      const stalePaperDomain = submittedTaskId === null
        && request?.explorationMode === 'PAPER_COMPATIBLE'
        && hasApiValidationError(error, 'paperDomainFingerprint')
      if (stalePaperDomain) {
        paperDomainStaleRecoveryActive.value = true
        const boardRefreshed = await enqueueBoardMutation(refreshBoardSnapshot).catch(() => false)
        if (!ownsRun()) return false
        if (boardRefreshed) markVerificationResultStale()
        invalidatePaperDomainPreview()
        fuzzingError.value = makeRunFailure(boardRefreshed
          ? 'app.fuzzPaperDomainStale'
          : 'app.fuzzPaperDomainStaleBoardRefreshFailed', error)
        if (boardRefreshed && fuzzingPanelTracksRun.value && validPaperPathLength()) {
          schedulePaperDomainPreview()
        } else if (!fuzzingPanelTracksRun.value) {
          notifyBlocked(fuzzingErrorMessage.value!)
        }
      } else {
        fuzzingError.value = makeRunFailure('app.fuzzSearchFailed', error)
      }
      if (submittedTaskId && !fuzzingPanelTracksRun.value) {
        markFuzzNotificationUnread({
          taskId: submittedTaskId,
          kind: 'FAILED',
          initiator: submittedTaskInitiator,
          createdAt: new Date().toISOString()
        })
        notifyError(fuzzingErrorMessage.value!)
      } else if (submittedTaskId) {
        untrackFuzzTask(submittedTaskId)
      } else if (!fuzzingPanelTracksRun.value && !stalePaperDomain) {
        notifyError(fuzzingErrorMessage.value!)
      }
    }
    return false
  } finally {
    if (ownsRun()) {
      setRunPanelView('fuzzing', 'settings')
      isFuzzing.value = false
      fuzzingCancelRequested.value = false
      fuzzingWatchedTask.value = null
      await loadTaskInbox({ showLoading: false })
    }
  }
}

const runSimulation = (): Promise<boolean> => {
  const normalizedSimConfig = { ...simulationForm }
  return submitFormalRun('simulation', normalizedSimConfig.isAsync, async ownsRun => {
    await waitForPendingBoardMutations()
    if (!ownsRun() || !ensureBoardDataReady(['templates', 'nodes', 'environment', 'rules'])) return false
    if (nodes.value.length === 0) {
      notifyBlocked(t('app.noDevicesToSimulate'))
      return false
    }
    if (!assertRulesHaveTriggers(rules.value)) return false
    let requestSteps: number
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

    simulationError.value = null
    simulationResultStale.value = false
    const simulationSubmissionSceneChanges = semanticSceneChangeCount
    const req = buildSimulationRequestPayload({
      steps: requestSteps,
      attackScenario: buildRunAttackScenario(normalizedSimConfig),
      enablePrivacy: normalizedSimConfig.enablePrivacy
    })
    
    let result: SimulationResult | SavedSimulationResult

    if (normalizedSimConfig.isAsync) {
      const submission = await submitBackgroundTask('simulation', () => simulationApi.simulateAsync(req), ownsRun)
      if (!submission || !ownsRun()) return false
      asyncSimulationTask.value = taskProgressFromSubmission(submission)
      if (submission.task) upsertSimulationTaskSummary(submission.task)
      result = await pollAsyncSimulation(submission.taskId)
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
    
    const summary = 'createdAt' in result ? summarizeSimulationResult(result) : null
    if (summary) {
      simulationHistoryRequests.invalidate()
      simulationRuns.value = [summary, ...simulationRuns.value.filter(item => item.id !== summary.id)]
    }

    deliverSimulationRunResult(result, simulationSubmissionSceneChanges)
    return true

  })
}

// A status/progress fetch error is "permanent" (fail fast, don't retry to timeout) when
// it is an auth/not-found/client error — retrying will never succeed. Network blips and
// 5xx are treated as transient.
const isPermanentPollError = (error: unknown): boolean => {
  const failure = error as { code?: unknown; response?: { status?: unknown } } | null
  if (failure?.code === 'TASK_STATUS_GONE'
    || failure?.code === RUN_RESPONSE_INCOMPLETE_CODE
    || failure?.code === FUZZ_RESPONSE_INCOMPLETE_CODE) return true
  const status = failure?.response?.status
  return typeof status === 'number'
    && status >= 400
    && status < 500
    && !isTransientTaskHttpStatus(status)
}

const ensureActiveWatchedTaskPoll = (kind: RunPanelKind, taskId: number, expectedEpoch: number) => {
  throwIfPollingAborted(expectedEpoch)
  if (isWatchedTaskCancelled(kind, taskId)) {
    throw new AsyncTaskCancelledError(t('app.taskStatusCancelled'))
  }
}

const readWatchedTaskStatus = async <T,>(
  kind: RunPanelKind, taskId: number, expectedEpoch: number, read: () => Promise<T>
): Promise<T> => {
  ensureActiveWatchedTaskPoll(kind, taskId, expectedEpoch)
  try {
    return await read()
  } catch (error: unknown) {
    ensureActiveWatchedTaskPoll(kind, taskId, expectedEpoch)
    if ((error as { response?: { status?: unknown } } | null)?.response?.status !== 404) throw error

    invalidateTaskInboxRequests()
    if (kind === 'verification') {
      verificationTasks.value = verificationTasks.value.filter(item => item.id !== taskId)
    } else if (kind === 'simulation') {
      simulationTasks.value = simulationTasks.value.filter(item => item.id !== taskId)
    } else {
      fuzzingTasks.value = fuzzingTasks.value.filter(item => item.id !== taskId)
      untrackFuzzTask(taskId)
    }
    throw Object.assign(new Error(t('app.taskNoLongerAvailable')), { code: 'TASK_STATUS_GONE' })
  }
}

type WatchedTaskStatus = Pick<VerificationTask, 'status' | 'progress' | 'progressStage' | 'errorMessage'>
type WatchedTaskCompletion<T> = { result: T } | { retryAfterMs: number }

const pollWatchedTask = async <T extends WatchedTaskStatus, R,>({
  kind, taskId, read, progress, upsert, failureKey, cancelledMessage, complete, timeoutError
}: {
  kind: RunPanelKind
  taskId: number
  read: () => Promise<T>
  progress: Ref<{ progress: number; statusKey: string }>
  upsert: (task: T) => void
  failureKey: string
  cancelledMessage: (task: T) => string
  complete: (task: T, expectedEpoch: number) => Promise<WatchedTaskCompletion<R>>
  timeoutError: () => Error
}): Promise<R> => {
  const expectedEpoch = pollingEpoch
  for (let count = 0; count < ASYNC_TASK_MAX_POLLS; count++) {
    let task: T
    try {
      task = await readWatchedTaskStatus(kind, taskId, expectedEpoch, read)
    } catch (error) {
      if (isPollingAbortedError(error) || isAsyncTaskCancelledError(error)) throw error
      // A cancellation receipt can arrive between the status read and its caller.
      ensureActiveWatchedTaskPoll(kind, taskId, expectedEpoch)
      if (isPermanentPollError(error)) throw error
      await waitForNextPoll(expectedEpoch)
      continue
    }

    ensureActiveWatchedTaskPoll(kind, taskId, expectedEpoch)
    progress.value.progress = normalizeTaskProgress(task.progress)
    progress.value.statusKey = taskProgressStatusKey(task.progressStage, task.status)
    upsert(task)

    if (task.status === 'FAILED') throw new Error(task.errorMessage || t(failureKey))
    if (task.status === 'CANCELLED') throw new AsyncTaskCancelledError(cancelledMessage(task))
    if (task.status === 'COMPLETED') {
      progress.value.progress = 100
      const completion = await complete(task, expectedEpoch)
      if ('result' in completion) return completion.result
      await waitForPollingDelay(completion.retryAfterMs, expectedEpoch)
      continue
    }
    await waitForNextPoll(expectedEpoch)
  }
  throw timeoutError()
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

const pollAsyncVerification = (taskId: number, submissionSceneChanges = semanticSceneChangeCount): Promise<void> => {
  // A watcher starts from the current scene. A new submission carries its earlier scene revision.
  return pollWatchedTask<VerificationTask, void>({
    kind: 'verification',
    taskId,
    read: () => boardApi.getTask(taskId),
    progress: asyncVerificationTask,
    upsert: upsertVerificationTaskSummary,
    failureKey: 'app.verificationFailed',
    cancelledMessage: task => task.errorMessage || t('app.verificationCancelled'),
    timeoutError: () => Object.assign(new Error('Verification task status polling timed out'), { code: 'TASK_POLL_TIMEOUT' }),
    complete: async (task, expectedEpoch) => {
      let traces: Trace[]
      try {
        traces = task.outcome === 'SATISFIED'
          ? []
          : await loadCompletedTaskResult(() => boardApi.getTaskTraces(taskId), expectedEpoch)
      } catch (error) {
        if (isPollingAbortedError(error)) throw error
        upsertVerificationTaskSummary({ ...task, progress: 100 })
        await loadVerificationRuns(false)
        throwIfPollingAborted(expectedEpoch)
        throw completedTaskResultError('verification', error)
      }
      throwIfPollingAborted(expectedEpoch)
      const result = buildVerificationResultFromTask(task, traces)
      upsertVerificationTaskSummary({ ...task, progress: 100 })
      deliverVerificationRunResult(result, submissionSceneChanges)
      await loadVerificationRuns()
      return { result: undefined }
    }
  })
}

const pollAsyncSimulation = (taskId: number): Promise<SavedSimulationResult> => pollWatchedTask<SimulationTask, SavedSimulationResult>({
  kind: 'simulation',
  taskId,
  read: () => simulationApi.getTask(taskId),
  progress: asyncSimulationTask,
  upsert: upsertSimulationTaskSummary,
  failureKey: 'app.asyncSimulationFailed',
  cancelledMessage: () => t('app.simulationTaskCancelledByServer'),
  timeoutError: () => Object.assign(new Error('Simulation task status polling timed out'), { code: 'TASK_POLL_TIMEOUT' }),
  complete: async (task, expectedEpoch) => {
    const traceId = task.simulationTraceId
    if (!traceId) {
      upsertSimulationTaskSummary({ ...task, progress: 100 })
      throw new Error(t('app.taskCompletedNoTraceFound'))
    }
    let trace: Awaited<ReturnType<typeof simulationApi.getSimulation>>
    try {
      trace = await loadCompletedTaskResult(() => simulationApi.getSimulation(traceId), expectedEpoch)
    } catch (error) {
      if (isPollingAbortedError(error)) throw error
      upsertSimulationTaskSummary({ ...task, progress: 100 })
      await loadSimulationRuns(false)
      throwIfPollingAborted(expectedEpoch)
      throw completedTaskResultError('simulation', error)
    }
    throwIfPollingAborted(expectedEpoch)
    upsertSimulationTaskSummary({ ...task, progress: 100 })
    await loadSimulationRuns()
    throwIfPollingAborted(expectedEpoch)
    return { result: simulationResultFromTrace(trace) }
  }
})

const pollAsyncFuzzing = (taskId: number): Promise<FuzzingRun> => {
  let completedResultFailures = 0
  return pollWatchedTask<FuzzingTask, FuzzingRun>({
    kind: 'fuzzing',
    taskId,
    read: () => fuzzingApi.getTask(taskId),
    progress: asyncFuzzingTask,
    upsert: task => {
      fuzzingWatchedTask.value = task
      upsertFuzzingTaskSummary(task)
    },
    failureKey: 'app.fuzzSearchFailed',
    cancelledMessage: task => task.errorMessage || t('app.fuzzSearchCancelled'),
    timeoutError: () => new FuzzTaskRecoveryPendingError(),
    complete: async (task, expectedEpoch) => {
      try {
        const run = await loadFuzzRunSingleFlight(taskId, task.runId ?? task.id)
        throwIfPollingAborted(expectedEpoch)
        clearFuzzRunRecoveryState(taskId)
        upsertFuzzingRunSummary(summarizeFuzzingRun(run))
        return { result: run }
      } catch (error) {
        throwIfPollingAborted(expectedEpoch)
        if (classifyTrackedFuzzRunError(error) !== 'RETRY') {
          throw new FuzzCompletedResultUnavailableError(error)
        }
        asyncFuzzingTask.value.statusKey = 'app.fuzzResultRecoveryPending'
        const retryAfterMs = scheduleFuzzRunRecovery(taskId)
        completedResultFailures++
        if (completedResultFailures >= FUZZ_INLINE_RESULT_RECOVERY_MAX_FAILURES) {
          throw new FuzzTaskRecoveryPendingError()
        }
        return { retryAfterMs }
      }
    }
  })
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
  if (!canOpenTaskSettings('verification')) return
  dismissResultDialog()
  openRunPanel('verification', 'settings')
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

// Identity denotes a visit, even when Back or Forward returns to the same address.
let appliedDeepLinkTarget: BoardRunTarget | null = null
// Only a History selection can be cancelled by closing or changing the list.
let pendingHistoryRunTarget: BoardRunTarget | null = null

/**
 * Navigation we initiate ourselves must not be re-applied by the target watcher (a redundant
 * refetch) and must not clear a stale-link notice this navigation was made to report.
 * History openers load after writing the URL; completion already has the result.
 * Recording `appliedDeepLinkTarget` is deterministic, unlike a boolean whose lifetime
 * depends on when the watcher happens to flush.
 */
const navigateToRunTarget = async (target: BoardRunTarget | null, mode: 'push' | 'replace') => {
  appliedDeepLinkTarget = target
  if (isSameBoardRunTarget(target, deepLinkTarget.value) && !hasUnusableBoardRunParams(route.query)) return
  const query = applyBoardRunTarget(route.query, target)
  await (mode === 'push' ? router.push({ query }) : router.replace({ query }))
}

/**
 * Opening a run is a `push` so Back closes it; clearing or correcting one is a `replace` so
 * dismissing a surface does not leave a dead history entry the user must step over.
 */
const openRunTarget = async (target: BoardRunTarget, fromHistory = false) => {
  if (isSameBoardRunTarget(pendingHistoryRunTarget, target)
    || (historyDetailRequests.hasPendingReplace() && isSameBoardRunTarget(appliedDeepLinkTarget, target))) return
  const ownsBoard = captureBoardRunOwner()
  pendingHistoryRunTarget = fromHistory ? target : null
  historyDetailRequests.invalidate()
  staleDeepLink.value = false
  try {
    await navigateToRunTarget(target, 'push')
    if (!ownsBoard() || appliedDeepLinkTarget !== target
      || !isSameBoardRunTarget(deepLinkTarget.value, target)) return
    if (fromHistory && pendingHistoryRunTarget !== target) {
      // History can be closed before the navigation commits its address.
      clearRunDeepLink()
      return
    }
    await applyDeepLinkTarget(target)
  } finally {
    if (pendingHistoryRunTarget === target && !historyDetailRequests.hasPendingReplace()) pendingHistoryRunTarget = null
  }
}

/**
 * Marks "no run is open" immediately, then clears the URL. The synchronous part matters: a
 * caller may dismiss one surface and open another in the same tick (reuse exploration
 * settings), and the pending navigation must not let the sync watcher reopen what the user
 * just left.
 */
clearRunDeepLink = () => {
  historyDetailRequests.invalidate()
  void navigateToRunTarget(null, 'replace')
}


const isResultSurfaceVisible = computed(() =>
  showResultDialog.value || !!simulationError.value
  || showFuzzingResultDialog.value
)

const applyDeepLinkTarget = async (target: BoardRunTarget | null, checkedRun?: VerificationRun) => {
  if (pendingHistoryRunTarget !== target) pendingHistoryRunTarget = null
  appliedDeepLinkTarget = target
  closePlayback()
  // The URL is changing authority. Leaving A visible while B is loading makes a temporary B
  // failure look like A succeeded, and lets its child id be checked against the wrong parent.
  closeResultSurfaces()
  if (!target) return

  const ownsBoard = captureBoardRunOwner()
  const request = historyDetailRequests.beginReplace()
  const isCurrent = () => ownsBoard() && historyDetailRequests.isCurrent(request)
    && appliedDeepLinkTarget === target && isSameBoardRunTarget(deepLinkTarget.value, target)
  try {
    await loadDeepLinkTarget(target, isCurrent, checkedRun)
  } finally {
    historyDetailRequests.finish(request)
    // A recheck can replace the detail request within the same History selection.
    if (pendingHistoryRunTarget === target && !historyDetailRequests.hasPendingReplace()) pendingHistoryRunTarget = null
  }
}

const loadDeepLinkTarget = async (
  target: BoardRunTarget,
  isCurrent: () => boolean,
  checkedRun?: VerificationRun
) => {
  if (target.kind === 'verification') {
    if (target.traceId !== undefined) await selectAndPlayVerificationTrace(target.traceId, target.runId, isCurrent)
    else await openVerificationRun(target.runId, isCurrent, checkedRun)
  } else if (target.kind === 'simulation') {
    await selectAndPlaySimulationTrace(target.runId, isCurrent)
  } else if (target.findingId !== undefined) {
    await selectAndPlayFuzzingFinding(target.findingId, target.runId, isCurrent)
  } else {
    await openFuzzingRun(target.runId, isCurrent)
  }
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
    void applyDeepLinkTarget(null)
    void navigateToRunTarget(null, 'replace')
    return
  }

  const target = deepLinkTarget.value
  if (isSameBoardRunTarget(target, appliedDeepLinkTarget)) return
  staleDeepLink.value = false
  await applyDeepLinkTarget(target)
}

watch([() => route.query, isBoardDataReady], syncBoardToDeepLink, { immediate: true })

const reportUnusableDeepLink = () => {
  staleDeepLink.value = true
  void applyDeepLinkTarget(null)
  void navigateToRunTarget(null, 'replace')
}

const reportHistoryDetailFailure = (error: unknown, messageKey: string) => {
  if (shouldReportUnusableHistoryDeepLink(error)) reportUnusableDeepLink()
  else notifyError(t(messageKey))
}

const dismissStaleDeepLink = () => { staleDeepLink.value = false }

/**
 * A History row carries its own run ID, including when opening a trace or finding.
 */
const openVerificationRunFromHistory = (runId: number) =>
  openRunTarget({ kind: 'verification', runId }, true)

const openVerificationTraceFromHistory = (traceId: number, runId: number) =>
  openRunTarget({ kind: 'verification', runId, traceId }, true)

const openSimulationRunFromHistory = (runId: number) =>
  openRunTarget({ kind: 'simulation', runId }, true)

const openFuzzingRunFromHistory = (runId: number) =>
  openRunTarget({ kind: 'exploration', runId }, true)

const openFuzzingFindingFromHistory = (findingId: number, runId: number) =>
  openRunTarget({ kind: 'exploration', runId, findingId }, true)

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
  simulationPlaybackResult.value?.hasSmvModel === true
  && typeof simulationPlaybackResult.value?.historyPersistence.runId === 'number')

// The mapping lives in `board/smvUnavailableReason.ts` (a pure rule, unit-tested there); these only
// bind it to the two result refs.
const verificationSmvUnavailableReason = computed(() =>
  smvUnavailableReasonKey(verificationResult.value?.historyPersistence))

const simulationSmvUnavailableReason = computed(() =>
  smvUnavailableReasonKey(simulationPlaybackResult.value?.historyPersistence))

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
  const runId = simulationPlaybackResult.value?.historyPersistence.runId
  if (typeof runId !== 'number') return
  void downloadSimulationTraceSmv(runId)
}
const verificationSpecResultSummary = computed(() => {
  const results = (verificationResult.value?.specResults || []).map(result => {
    const interpretation = presentSpecificationResult(result, t)
    return {
      ...result,
      ...interpretation,
      presentation: result.outcome === 'SATISFIED' && interpretation.triggerWarning
        ? {
            borderClass: 'board-border-subtle',
            badgeClass: 'board-surface-warning board-text-warning',
            icon: 'report',
            label: t(result.antecedentReachability === 'UNREACHABLE'
              ? 'household.triggerUnreachable' : 'household.triggerUnknown')
          }
        : result.outcome === 'SATISFIED'
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
    inconclusive,
    triggerWarnings: countResponseTriggerWarnings(results)
  }
})
const verificationEvidenceGroups = computed(() => groupVerificationEvidence(
  verificationSpecResultSummary.value.results, verificationResult.value?.traces || []
))
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

  if (outcome === 'SATISFIED' && !isRunModelComplete(verificationResult.value)) {
    return {
      ...verificationVerdictTone('warning'),
      icon: 'report',
      title: t('app.verificationPassedWithGenerationWarnings'),
      detail: t('app.emittedSpecsPassedWithGenerationWarnings')
    }
  }

  if (outcome === 'SATISFIED' && verificationSpecResultSummary.value.triggerWarnings > 0) {
    return {
      ...verificationVerdictTone('warning'),
      icon: 'report',
      title: t('household.coverageTitle'),
      detail: t('household.coverageDetail', { count: verificationSpecResultSummary.value.triggerWarnings })
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

const verificationModelSemanticsConsistent = computed(() =>
  isRunModelSemanticsConsistent(verificationResult.value)
)

const verificationBoardComparison = computed<RunBoardComparison>(() =>
  compareRunToCurrentBoard(verificationResult.value, 'verification')
)

const simulationBoardComparison = computed<RunBoardComparison>(() =>
  compareRunToCurrentBoard(lastSimulationResult.value, 'simulation')
)

const traceBoardComparison = computed(() =>
  compareRunToCurrentBoard(currentTrace.value, activeFuzzingFinding.value ? 'fuzzing' : 'verification')
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
        v-if="isInitialBoardLoading"
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

          <TemplateRuntimeFields
            variant="template-instance"
            :template="templateInstanceDialogData.template"
            :draft="templateInstanceRuntime"
            :working-states="templateInstanceHasModes ? templateInstanceWorkingStates : []"
            :variables="templateInstanceInternalVariables"
            :format-token="formatTemplateModelToken"
          />
        </details>

        </div>

        <p v-if="templateInstanceUnconfirmed" id="template-instance-create-unconfirmed" data-testid="template-instance-create-unconfirmed"
          role="status" class="board-text-warning text-sm px-4">{{ templateInstanceUnconfirmed }}</p>
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
            :disabled="templateInstanceSaving || !!templateInstanceUnconfirmed"
            :aria-describedby="templateInstanceUnconfirmed ? 'template-instance-create-unconfirmed' : undefined"
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
      ref="controlCenterRef"
      :device-templates="deviceTemplates"
      :templates-loading="templatesLoading"
      :nodes="nodes"
      :collapsed="boardPanels.control.collapsed"
      :width="effectiveControlPanelWidth"
      :active-section="boardPanels.control.activeSection"
      :edit-rule="editingRule"
      :edit-specification="editingSpecification"
      :read-only="isModelPlaybackActive || isSceneReplacementInProgress"
      :read-only-message="isSceneReplacementInProgress ? t('app.sceneReplacementInProgress') : t('app.playbackReadOnlyCloseFirst')"
      :run-board-mutation="enqueueBoardMutation"
      :class="{ 'board-panel--interaction-read-only': isModelPlaybackActive || isSceneReplacementInProgress }"
      @create-device="handleCreateDevice"
      @create-devices="handleCreateDevices"
      @template-drag-start="handleTemplateDragStart"
      @template-drag-end="handleTemplateDragEnd"
      @save-rule="handleSaveRule"
      @add-spec="handleSaveSpecification"
      @finish-rule-edit="editingRule = null"
      @finish-spec-edit="editingSpecification = null"
      @replace-template-catalog="replaceTemplateCatalog"
      @replace-template-state="replaceTemplateState"
      @replace-board-snapshot="adoptBoardSnapshot"
      @edit-history-cleared="notifyUndoJournalCleared"
      @authoritative-state-unavailable="handleAuthoritativeBoardStateUnavailable"
      @update:collapsed="handleControlCollapsedUpdate"
      @update:active-section="handleControlActiveSectionUpdate"
      :recommendation-running="{ devices: isRecommendingDevices, rules: isRecommendingRules, specs: isRecommendingSpecs }"
    >
      <template #device-recommendations>
        <div data-testid="device-recommendation-panel" class="min-w-0 space-y-3 break-words">
          <RecommendationRequestControls
            kind="device"
            v-model:requirement="deviceRecommendationFilters.userRequirement"
            :input-disabled="isRecommendingDevices || recommendationBatch?.running"
            :action-disabled="recommendationBatch?.running || isSceneReplacementInProgress || hasPendingRecommendationInAnotherPanel('device')"
            :request-pending="Boolean(deviceRecommendationPanel.requestId.value)"
            :action-label="recommendationActionLabel(deviceRecommendationPanel, 'app.stopDeviceRecommendations')"
            :progress="isRecommendingDevices ? recommendationProgress : undefined"
            :failure="recommendationFailures.device"
            @action="fetchDeviceRecommendations"
          />
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
                :disabled="recommendationBatch?.running || isSceneReplacementInProgress"
                class="text-xs board-text-info hover:font-medium flex items-center gap-1"
              >
                <span class="material-symbols-outlined text-sm">refresh</span>
                {{ t('app.regenerateRecommendations') }}
              </button>
            </div>

            <div v-if="deviceRecommendations.length > 1" class="space-y-2">
              <button type="button" data-testid="apply-all-device-recommendations"
                class="board-panel-submit board-action-disarmed bg-[color:var(--accent-fill)] hover:bg-[color:var(--accent-fill-hover)]"
                @click="applyAllRecommendations('device')"
                :disabled="Boolean(recommendationBatchBlockedReason('device'))"
                :aria-busy="recommendationBatch?.kind === 'device' && recommendationBatch.running"
                aria-describedby="device-batch-apply-status">
                {{ t('app.applyAllRecommendations', { count: deviceRecommendations.length }) }}
              </button>
              <p id="device-batch-apply-status" role="status" class="text-xs leading-relaxed board-text-muted">
                {{ recommendationBatchBlockedReason('device') || t('app.recommendationBatchHint') }}
              </p>
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
                  :disabled="isSceneReplacementInProgress || isRecommendationBatchLocked('device') || appliedDeviceRecommendations.has(index) || applyingDeviceRecommendations.has(index) || recommendationApplyFailures.device[index]?.outcomeUnknown"
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
      </template>
      <template #rule-recommendations>
        <div data-testid="rule-recommendation-panel" class="min-w-0 space-y-3 break-words">
          <RecommendationRequestControls
            kind="rule"
            v-model:requirement="ruleRecommendationFilters.userRequirement"
            :input-disabled="isRecommendingRules || recommendationBatch?.running"
            :action-disabled="recommendationBatch?.running || isSceneReplacementInProgress || hasPendingRecommendationInAnotherPanel('rule')"
            :request-pending="Boolean(ruleRecommendationPanel.requestId.value)"
            :action-label="recommendationActionLabel(ruleRecommendationPanel, 'app.stopRuleRecommendations')"
            :progress="isRecommendingRules ? recommendationProgress : undefined"
            :failure="recommendationFailures.rule"
            @action="fetchRuleRecommendations"
          />
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
                :disabled="recommendationBatch?.running || isSceneReplacementInProgress"
                class="text-xs board-text-warning hover:font-medium flex items-center gap-1"
              >
                <span class="material-symbols-outlined text-sm">refresh</span>
                {{ t('app.regenerateRecommendations') }}
              </button>
            </div>

            <div v-if="ruleRecommendations.length > 1" class="space-y-2">
              <button type="button" data-testid="apply-all-rule-recommendations"
                class="board-panel-submit board-action-disarmed bg-[color:var(--accent-fill)] hover:bg-[color:var(--accent-fill-hover)]"
                @click="applyAllRecommendations('rule')"
                :disabled="Boolean(recommendationBatchBlockedReason('rule'))"
                :aria-busy="recommendationBatch?.kind === 'rule' && recommendationBatch.running"
                aria-describedby="rule-batch-apply-status">
                {{ t('app.applyAllRecommendations', { count: ruleRecommendations.length }) }}
              </button>
              <p id="rule-batch-apply-status" role="status" class="text-xs leading-relaxed board-text-muted">
                {{ recommendationBatchBlockedReason('rule') || t('app.recommendationBatchHint') }}
              </p>
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
                  :disabled="isSceneReplacementInProgress || isRecommendationBatchLocked('rule') || appliedRuleRecommendations.has(index) || applyingRuleRecommendations.has(index) || recommendationApplyFailures.rule[index]?.outcomeUnknown"
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
      </template>
      <template #spec-recommendations>
        <div data-testid="spec-recommendation-panel" class="min-w-0 space-y-3 break-words">
          <RecommendationRequestControls
            kind="spec"
            v-model:requirement="specRecommendationFilters.userRequirement"
            :input-disabled="isRecommendingSpecs || recommendationBatch?.running"
            :action-disabled="recommendationBatch?.running || isSceneReplacementInProgress || hasPendingRecommendationInAnotherPanel('spec')"
            :request-pending="Boolean(specRecommendationPanel.requestId.value)"
            :action-label="recommendationActionLabel(specRecommendationPanel, 'app.stopSpecificationRecommendations')"
            :progress="isRecommendingSpecs ? recommendationProgress : undefined"
            :failure="recommendationFailures.spec"
            @action="fetchSpecRecommendations"
          />
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
                :disabled="recommendationBatch?.running || isSceneReplacementInProgress"
                class="text-xs board-text-danger hover:font-medium flex items-center gap-1"
              >
                <span class="material-symbols-outlined text-sm">refresh</span>
                {{ t('app.regenerateRecommendations') }}
              </button>
            </div>

            <div v-if="specRecommendations.length > 1" class="space-y-2">
              <button type="button" data-testid="apply-all-spec-recommendations"
                class="board-panel-submit board-action-disarmed bg-[color:var(--accent-fill)] hover:bg-[color:var(--accent-fill-hover)]"
                @click="applyAllRecommendations('spec')"
                :disabled="Boolean(recommendationBatchBlockedReason('spec'))"
                :aria-busy="recommendationBatch?.kind === 'spec' && recommendationBatch.running"
                aria-describedby="spec-batch-apply-status">
                {{ t('app.applyAllRecommendations', { count: specRecommendations.length }) }}
              </button>
              <p id="spec-batch-apply-status" role="status" class="text-xs leading-relaxed board-text-muted">
                {{ recommendationBatchBlockedReason('spec') || t('app.recommendationBatchHint') }}
              </p>
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
                        {{ rec.name }}
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
                  :disabled="isSceneReplacementInProgress || isRecommendationBatchLocked('spec') || appliedSpecRecommendations.has(index) || applyingSpecRecommendations.has(index) || recommendationApplyFailures.spec[index]?.outcomeUnknown"
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
      </template>
    </ControlCenter>

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
      :rule-order-blocked-reason="ruleOrderBlockedReason"
      :deletion-warning="boardItemDeletionWarning"
      :class="{ 'board-panel--interaction-read-only': isModelPlaybackActive || isSceneReplacementInProgress }"
      @delete-device="deleteNodeFromStatus"
      @delete-rule="deleteRule"
      @edit-rule="editRuleFromInspector"
      @move-rule="moveRule"
      @delete-spec="deleteSpecification"
      @edit-spec="editSpecificationFromInspector"
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
              <g v-memo="[canvasMapLines, canvasMapDots]">
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
              </g>
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
            :aria-controls="isTracePlaybackActive ? 'trace-playback-panel' : 'simulation-playback-panel'"
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
            :data-testid="isTracePlaybackActive ? 'trace-timeline-close' : 'simulation-timeline-close'"
            :aria-label="t('app.exitPlayback')"
            @click="dismissPlayback"
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
              data-testid="empty-state-open-assistant"
              @click="toggleChat"
            >
              <span class="material-symbols-outlined text-lg" aria-hidden="true">auto_awesome</span>
              {{ t('app.aiAssistant') }}
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
            v-if="isSimulationPlaybackActive"
            class="board-tool-status"
          ></div>
          <HintTooltip :content="simulationTooltipContent">
            <button
              type="button"
              @click="togglePanel('simulation')"
              data-testid="open-simulation-panel"
              :disabled="isModelPlaybackActive || isAnyRecommendationRunning()"
              :aria-label="isSimulating ? t('app.simulationRunning') : t('app.openSimulationSettings')"
              :aria-pressed="showSimulationPanel || isSimulationPlaybackActive"
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
              @click="togglePanel('fuzzing')"
              data-testid="open-fuzzing-panel"
              :disabled="isSceneReplacementInProgress || isModelPlaybackActive || isAnyRecommendationRunning()"
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
            v-if="isTracePlaybackActive"
            class="board-tool-status"
          ></div>
          <HintTooltip :content="verificationTooltipContent">
            <button
              ref="verificationActionButtonRef"
              type="button"
              @click="togglePanel('verification')"
              data-testid="open-verification-panel"
              :disabled="isModelPlaybackActive || isAnyRecommendationRunning()"
              :aria-label="isVerifying ? t('app.verifying') : t('app.openVerificationSettings')"
              :aria-pressed="showVerificationPanel || isTracePlaybackActive"
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
        :verification-repair-blocks="verificationRepairBlocks"
        @update:active-layer="setHistoryLayer"
        @update:result-filter="setHistoryResultFilter"
        @close="closeHistoryPanel"
        @refresh-tasks="refreshHistoryTasks"
        @refresh-results="refreshHistoryResults"
        @load-more-fuzzing-runs="loadMoreFuzzingRuns"
        @watch-verification-task="watchVerificationTask"
        @watch-fuzzing-task="watchFuzzingTask"
        @watch-simulation-task="watchSimulationTask"
        @cancel-verification-task="cancelBoardTask('verification', $event)"
        @cancel-fuzzing-task="cancelBoardTask('fuzzing', $event)"
        @cancel-simulation-task="cancelBoardTask('simulation', $event)"
        @reopen-task-settings="reopenTaskSettings"
        @dismiss-verification-task="dismissBoardTask('verification', $event)"
        @dismiss-fuzzing-task="dismissBoardTask('fuzzing', $event)"
        @dismiss-simulation-task="dismissBoardTask('simulation', $event)"
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
        @view-fuzzing-finding="openFuzzingFindingFromHistory"
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
                :disabled="pendingTaskActionKeys.has(taskActionKey('cancel', task.kind, task.id)) || isWatchedTaskCancelled(task.kind, task.id)"
                @click="cancelBoardTask(task.kind, task.id)"
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
      :is-shared-variable="isSharedFuzzingVariable"
      :running="fuzzingPanelTracksRun && isFuzzing"
      :progress="asyncFuzzingTask.progress"
      :status="formatWatchedTaskStatus(asyncFuzzingTask.statusKey, cancellingFuzzingTask)"
      :task-id="asyncFuzzingTask.taskId"
      :cancelling="cancellingFuzzingTask || isWatchedTaskCancelled('fuzzing', asyncFuzzingTask.taskId)"
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
      :frozen-task="fuzzingPanelTracksRun ? fuzzingWatchedTask : null"
      :should-restore-focus="floatingPanelAccessibility.shouldRestoreFocus"
      @submit="runFuzzing"
      @cancel="cancelAsyncFuzzing"
      @close="closeFuzzingPanel"
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
        <BackgroundTaskSummary v-if="verificationPanelTracksRun && runningVerificationTask" kind="verification" :task="runningVerificationTask" />
        <template v-if="!verificationPanelTracksRun || !asyncVerificationActive">
        <section class="text-xs leading-relaxed board-text-muted" aria-labelledby="verification-scope-title">
          <h4 id="verification-scope-title" class="board-section-title">{{ t('household.scopeTitle') }}</h4>
          <p class="mt-1">{{ t('household.verificationScope') }}</p>
          <details class="mt-2">
            <summary class="cursor-pointer">{{ t('household.assumptions') }}</summary>
            <p class="mt-1">{{ t('household.modelingBoundary') }}</p>
            <p class="mt-1">{{ t('household.customizeBoundary') }}</p>
          </details>
        </section>
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
            v-if="fuzzVerificationHandoffNoticeKey"
            class="mt-2 rounded-md board-surface-warning px-2 py-1.5 font-semibold board-text-warning"
            :role="fuzzVerificationHandoff.targetPresent === false ? 'alert' : 'status'"
          >
            {{ t(fuzzVerificationHandoffNoticeKey) }}
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

        <FormalRunSettings
          kind="verification"
          v-model:privacy="verificationForm.enablePrivacy"
          v-model:async-mode="verificationForm.isAsync"
          :disabled="isVerifying"
          :privacy-required="hasPrivacySpecification"
        />

        </template>
        <!-- Async Progress (visible when async verification is running) -->
        <div v-if="verificationPanelTracksRun && asyncVerificationActive" class="space-y-1">
          <div class="flex items-center justify-between text-xs">
            <span class="board-text-success font-medium">{{ formatWatchedTaskStatus(asyncVerificationTask.statusKey, cancellingVerificationTask) }}</span>
            <div v-if="asyncVerificationTask.taskId" class="flex items-center gap-2">
              <span class="board-text-success font-semibold">{{ asyncVerificationTask.progress }}%</span>
              <HintTooltip :content="t('app.cancelVerificationTask')">
                <button
                  type="button"
                  class="board-panel-icon-action"
                  :disabled="cancellingVerificationTask || isWatchedTaskCancelled('verification', asyncVerificationTask.taskId)"
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
          @click="handleVerify"
          data-testid="run-verification"
          :disabled="isVerifying || Boolean(verificationRunBlockedReason)"
          :aria-describedby="verificationRunBlockedDescriptionId"
          class="board-panel-submit"
        >
          <template v-if="!isBoardDataReady && failedBoardDataKeys.length === 0">
            <span class="material-symbols-outlined text-sm animate-spin">sync</span>
            {{ t('app.loading') }}
          </template>
          <template v-else-if="isVerifying && verificationPanelTracksRun">
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
        <BackgroundTaskSummary v-if="simulationPanelTracksRun && runningSimulationTask" kind="simulation" :task="runningSimulationTask" />
        <template v-if="!simulationPanelTracksRun || !asyncSimulationActive">
        <p class="text-xs leading-relaxed board-text-muted">{{ t('household.simulationScope') }}</p>
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

        <FormalRunSettings
          kind="simulation"
          v-model:privacy="simulationForm.enablePrivacy"
          v-model:async-mode="simulationForm.isAsync"
          :disabled="isSimulating"
        />

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
                  :disabled="cancellingSimulationTask || isWatchedTaskCancelled('simulation', asyncSimulationTask.taskId)"
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
              role="status" class="mt-2 text-xs" :class="renameUnconfirmedMessage ? 'board-text-warning' : renameNameConflict ? 'board-text-danger' : 'board-text-muted'">{{ renameNameBlockedReason }}</p>
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
            <p v-if="deleteUnconfirmedMessage" id="device-delete-unconfirmed" data-testid="device-delete-unconfirmed"
              role="status" class="mb-3 text-sm board-text-warning">{{ deleteUnconfirmedMessage }}</p>
            <div
              v-if="deletePreviewLoading"
              class="mb-3 flex items-center gap-3 rounded-lg board-surface-info px-4 py-3 text-sm board-text-info"
              role="status"
              aria-live="polite"
            >
              <span class="h-4 w-4 shrink-0 animate-spin rounded-full border-2 board-border-progress border-t-[color:var(--accent)]" aria-hidden="true"></span>
              {{ t('app.deviceDeletionPreviewLoading') }}
            </div>

            <div v-if="hasDeletionConsequences" class="board-surface-warning rounded-lg p-4">
              <div class="flex items-start">
                <span class="material-symbols-outlined board-text-warning mr-2 mt-0.5" aria-hidden="true">info</span>
                <div class="min-w-0">
                  <p class="text-sm font-medium board-text-warning mb-1">{{ t('app.deviceDeleteConsequences') }}</p>
                  <div class="text-xs board-text-warning space-y-1">
                    <div v-if="deleteConfirmDialogData.relatedRules.length > 0">
                      • {{ t('app.relatedRulesWillBeDeleted', { count: deleteConfirmDialogData.relatedRules.length }) }}
                      <ul class="mt-1 ml-4 list-disc break-words">
                        <li v-for="(label, index) in deleteConfirmDialogData.relatedRules" :key="`delete-rule-${index}`">
                          {{ label }}
                        </li>
                      </ul>
                    </div>
                    <div v-if="deleteConfirmDialogData.relatedSpecs.length > 0">
                      • {{ t('app.relatedSpecsWillBeDeleted', { count: deleteConfirmDialogData.relatedSpecs.length }) }}
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
              :disabled="deletePreviewLoading || deleteConfirmSubmitting || !deleteConfirmDialogData.impactToken || Boolean(deleteUnconfirmedMessage)"
              :aria-describedby="deleteUnconfirmedMessage ? 'device-delete-unconfirmed' : undefined"
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
      class="iot-dialog iot-dialog--lg"
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
            v-if="verificationRunNeedsReverify"
            data-testid="verification-result-stale-banner"
            role="status"
            class="board-run-notice flex items-start gap-2 board-surface-warning board-text-warning"
          >
            <span class="material-symbols-outlined text-base" aria-hidden="true">history</span>
            <div class="min-w-0">
              <p v-if="verificationFixApplied" data-testid="verification-fix-applied" class="mb-1 font-semibold">{{ t('app.fixAppliedWithSignedEvidence') }}</p>
              <p>{{ verificationRunRepairBlock && t(verificationRunRepairBlock) }}</p>
            </div>
          </div>
          <div
            v-else-if="verificationRunRepairBlock"
            data-testid="verification-result-comparison-banner"
            role="status"
            class="board-run-notice flex items-start gap-2 board-surface-warning board-text-warning"
          >
            <span class="material-symbols-outlined text-base" aria-hidden="true">info</span>
            <span>{{ t(verificationRunRepairBlock) }}</span>
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
              <div class="board-card flex flex-wrap items-center justify-between gap-2 px-3 py-2">
                <div class="board-form-label">{{ t('app.satisfied') }}</div>
                <div class="text-lg font-semibold board-text-success">{{ verificationSpecResultSummary.satisfied }}</div>
              </div>
              <div class="board-card flex flex-wrap items-center justify-between gap-2 px-3 py-2">
                <div class="board-form-label">{{ t('app.violated') }}</div>
                <div class="text-lg font-semibold board-text-danger">{{ verificationSpecResultSummary.violated }}</div>
              </div>
              <div class="board-card flex flex-wrap items-center justify-between gap-2 px-3 py-2">
                <div class="board-form-label">{{ t('app.inconclusive') }}</div>
                <div class="text-lg font-semibold board-text-warning">{{ verificationSpecResultSummary.inconclusive }}</div>
              </div>
            </div>
          </section>


          <p v-if="verificationEvidenceShortfall > 0"
            class="board-run-notice board-surface-warning board-text-warning"
            data-testid="verification-evidence-shortfall">
            {{ t('app.someViolationsHaveNoReplayableCounterexample') }}
          </p>
          <p v-if="getVerificationOutcome(verificationResult) === 'INCONCLUSIVE' && verificationResult.traces.length"
            class="board-run-notice board-surface-warning board-text-warning">
            {{ t('app.inconclusiveEvidenceSummary', { counterexamples: verificationResult.traces.length }) }}
          </p>

          <section v-if="verificationEvidenceGroups.length" data-testid="spec-results-section" aria-labelledby="spec-results-title">
            <h4 id="spec-results-title" class="board-section-title mb-3">{{ t('app.specResults') }}</h4>
            <div class="space-y-3" data-testid="spec-results-list">
              <article v-for="(group, groupIndex) in verificationEvidenceGroups" :key="group.result?.specId || `trace-${groupIndex}`"
                :data-spec-id="group.result?.specId || group.traces[0]?.trace.violatedSpecId" class="iot-result-item">
                <div class="iot-result-item__heading">
                  <h4 class="iot-result-item__title" data-testid="spec-result-name">
                    {{ group.result?.specificationLabel || group.traces[0]?.trace.violatedSpec?.name || t('app.unknownSpecification') }}
                  </h4>
                  <span v-if="group.result" class="iot-result-item__badge" :class="group.result.presentation.badgeClass">
                    <span class="material-symbols-outlined text-sm" aria-hidden="true">{{ group.result.presentation.icon }}</span>
                    {{ group.result.presentation.label }}
                  </span>
                </div>
                <p class="board-copy mt-2 break-words" data-testid="spec-result-description">
                  {{ group.result?.description || getTraceSpecDisplayTitle(group.traces[0]?.trace) }}
                </p>
                <p v-if="group.result?.triggerDetail" class="mt-2 text-xs leading-relaxed"
                  :class="group.result.triggerWarning ? 'board-text-warning' : 'board-text-muted'"
                  data-testid="spec-result-trigger-evidence">{{ group.result.triggerDetail }}</p>
                <div v-for="({ trace, index }) in group.traces" :key="index" class="mt-3">
                  <div class="verification-trace__header">
                    <span class="text-xs board-text-muted">{{ t('app.violationNumber', { index: index + 1 }) }} · {{ t('app.statesCount', { count: trace.states.length }) }}</span>
                    <div class="verification-trace__actions">
                      <button
                        v-if="canFixVerificationResultTrace(trace)"
                        type="button"
                        data-testid="verification-trace-fix"
                        class="iot-dialog-btn iot-dialog-btn--ghost"
                        :disabled="isSimulationPlaybackActive"
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
                        @click="selectAndPlayTrace(index)"
                        :disabled="isSimulationPlaybackActive"
                        data-testid="verification-trace-replay"
                        class="iot-dialog-btn iot-dialog-btn--primary"
                      >
                        <span class="material-symbols-outlined text-xs" aria-hidden="true">play_arrow</span>
                        {{ t('app.replay') }}
                        <span v-if="isSimulationPlaybackActive" class="text-xs">({{ t('app.active') }})</span>
                      </button>
                    </div>
                  </div>
                  <!-- Why Fix is withheld. Without this the button simply vanished, which reads as a bug. -->
                  <p
                    v-if="!verificationRunNeedsReverify && verificationResultTraceRepairBlock(trace)"
                    data-testid="verification-trace-fix-unavailable"
                    class="board-run-notice mb-2 board-surface-warning board-text-warning"
                  >
                    {{ t(verificationResultTraceRepairBlock(trace)!) }}
                  </p>
                </div>
                <template v-if="group.result">
                  <details class="spec-formula-disclosure" data-testid="spec-result-formula-details">
                    <summary class="text-xs font-medium board-text-muted">
                      <span class="material-symbols-outlined spec-formula-disclosure__chevron" aria-hidden="true">chevron_right</span>
                      <span>{{ t('app.formulaPreview') }}</span>
                      <span class="rounded board-card board-card--muted px-1.5 py-0.5 font-mono" data-testid="spec-result-formula-kind">{{ group.result.formulaKind }}</span>
                    </summary>
                    <p class="mt-2 whitespace-pre-wrap break-all rounded board-card board-card--muted p-2 font-mono text-xs leading-relaxed board-text-muted" data-testid="spec-result-formula">{{ group.result.formula }}</p>
                  </details>
                  <div v-if="group.result.variableSourceLabels.length" class="mt-2 flex flex-wrap gap-1">
                    <span v-for="entry in group.result.variableSourceLabels" :key="entry.key"
                      class="rounded border px-1.5 py-0.5 text-xs font-semibold"
                      :class="entry.unresolved ? 'board-chip-danger board-text-danger border-[color:var(--danger-border)]' : 'board-border-subtle board-bg-control board-text-muted'"
                      data-testid="spec-result-variable-source">{{ entry.label }}</span>
                  </div>
                  <details v-if="group.result.specId || group.result.expression" class="verification-result-disclosure mt-2 text-xs board-text-muted">
                    <summary class="cursor-pointer font-semibold">{{ t('app.technicalDetails') }}</summary>
                    <div class="mt-1 grid gap-1 sm:grid-cols-[9rem_minmax(0,1fr)]">
                      <span class="font-medium">{{ t('app.actualCheckedExpression') }}</span>
                      <code class="break-all rounded board-bg-muted px-2 py-1 board-text-strong">{{ group.result.expression }}</code>
                      <span class="font-medium">{{ t('app.specificationTechnicalId') }}</span>
                      <code class="break-all rounded board-bg-muted px-2 py-1 board-text-strong">{{ group.result.specId }}</code>
                    </div>
                  </details>
                </template>
                <details v-else-if="group.traces[0]?.trace.violatedSpecId" class="verification-result-disclosure mt-2 text-xs board-text-muted">
                  <summary class="cursor-pointer font-semibold">{{ t('app.technicalDetails') }}</summary>
                  <div class="mt-1 grid gap-1 sm:grid-cols-[9rem_minmax(0,1fr)]">
                    <span class="font-medium">{{ t('app.specificationTechnicalId') }}</span>
                    <code class="break-all rounded board-bg-muted px-2 py-1 board-text-strong">{{ group.traces[0].trace.violatedSpecId }}</code>
                  </div>
                </details>
              </article>
            </div>
          </section>

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
                    : hasRunBoardDifference(verificationBoardComparison)
                      ? 'board-surface-warning board-text-warning'
                      : 'board-border-subtle board-bg-muted board-text-strong'"
                  data-testid="verification-board-comparison"
                  :data-state="verificationBoardComparison"
                >
                  {{ verificationBoardComparison === 'UNCHANGED'
                    ? t('app.runBoardInputUnchanged')
                    : verificationBoardComparison === 'CHANGED'
                      ? t('app.runBoardInputChanged')
                      : verificationBoardComparison === 'SEMANTICS_CHANGED'
                        ? t('app.runBoardSemanticsChanged')
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
          v-if="verificationNeedsReverify"
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
    v-if="currentTrace"
    v-show="playbackPanelOpen"
    v-model:tab="playbackTerminalTab"
    :kind="activeFuzzingFinding ? 'fuzzing' : 'counterexample'"
    :selected-state-index="activePlaybackStateIndex"
    :total-states="totalStates"
    :playing="isPlaybackPlaying"
    :panel-open="playbackPanelOpen"
    :state-label="getTraceStateAriaLabel"
    :violation-steps="counterexampleViolationSteps"
    :violation-label="traceStateViolationLabel"
    :locate-label="counterexampleViolationStep === undefined ? t('app.traceVisualization.jumpToViolationLoop') : t('app.traceVisualization.jumpToViolation')"
    :style="boardShellStyle"
    @seek="goToState"
    @toggle-play="togglePlayback"
    @scrub-start="stopPlayback"
    @locate-violation="goToState(counterexampleViolationSteps[0])"
    @hide-panel="hidePlaybackPanel"
    @keydown="handlePlaybackKeydown"
  >
    <template #help>
      <InfoTooltip :text="t('app.playbackNavigationHint')" :label="t('app.showHelpFor', { topic: activeFuzzingFinding ? t('app.fuzzFindingReplay') : t('app.traceVisualization.counterexampleTracePlayback') })" tone="neutral" test-id="counterexample-trace-help" />
    </template>
    <template v-if="activeFuzzingFinding || currentTrace.modelComplete === false || !traceModelSemanticsConsistent || traceBoardComparison === 'SEMANTICS_CHANGED'" #context>
      <p v-if="activeFuzzingFinding" class="playback-terminal__notice board-note" data-testid="fuzzing-playback-notice">{{ t('app.fuzzFindingReplayHint') }}</p>
      <p v-if="traceBoardComparison === 'SEMANTICS_CHANGED'" class="playback-terminal__notice board-surface-warning" data-testid="trace-model-version-warning">{{ t('app.runBoardSemanticsChanged') }}</p>
      <p v-if="!activeFuzzingFinding && currentTrace.modelComplete === false" class="playback-terminal__notice board-surface-warning" data-testid="trace-timeline-incomplete-warning">{{ t('app.traceVisualization.verificationModelIncompletePlayback', { rules: currentTrace.disabledRuleCount || 0, specs: currentTrace.skippedSpecCount || 0 }) }}</p>
      <p v-if="!activeFuzzingFinding && !traceModelSemanticsConsistent" class="playback-terminal__notice board-surface-warning" data-testid="trace-model-semantics-warning">{{ t('app.modelSemanticsUnavailable') }}</p>
    </template>
    <template #run>
      <div class="playback-run" data-testid="counterexample-run-info">
        <section class="playback-run__section" data-testid="counterexample-evidence">
          <h3>{{ t('app.violatedSpecification') }}</h3>
          <p class="playback-run__requirement" data-testid="trace-timeline-violated-spec">{{ getTraceSpecDisplayTitle(currentTrace) }}</p>
          <span v-if="traceStateViolationLabel(activePlaybackStateIndex)" class="playback-terminal__badge board-chip-danger board-text-danger" data-testid="trace-timeline-violation-chip">{{ traceStateViolationLabel(activePlaybackStateIndex) }}</span>
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
        :compromised-automation-links="activePlaybackCompromisedAutomationLinks"
        :current-device-ids="nodes.map(node => node.id)"
        :current-rule-ids="rules.map(rule => String(rule.id))"
        :format-device-token="formatPlaybackDeviceModelToken"
        :format-environment-token="formatPlaybackEnvironmentModelToken"
      />
    </template>
    <template #changes>
      <PlaybackChangesPanel v-if="playbackChangeProps" v-bind="playbackChangeProps" />
    </template>
  </PlaybackTerminal>

  <SimulationTimeline
    v-if="simulationPlaybackResult"
    v-show="playbackPanelOpen"
    :panel-open="playbackPanelOpen"
    :states="activePlaybackStates"
    :selected-state-index="activePlaybackStateIndex"
    :playing="isPlaybackPlaying"
    :actual-steps="simulationPlaybackResult.steps"
    :requested-steps="simulationPlaybackResult.requestedSteps"
    :model-complete="simulationPlaybackResult.modelComplete"
    :disabled-rule-count="simulationPlaybackResult.disabledRuleCount"
    :is-attack="simulationPlaybackResult.isAttack"
    :attack-budget="simulationPlaybackResult.attackBudget"
    :enable-privacy="simulationPlaybackResult.enablePrivacy"
    :model-semantics="simulationPlaybackResult.modelSemantics"
    :board-comparison="simulationBoardComparison"
    :current-rule-ids="currentBoardRuleIds"
    :current-device-ids="currentBoardDeviceIds"
    :format-device-model-token="formatPlaybackDeviceModelToken"
    :format-environment-model-token="formatPlaybackEnvironmentModelToken"
    v-model:tab="playbackTerminalTab"
    :style="boardShellStyle"
    @seek="goToState"
    @toggle-play="togglePlayback"
    @scrub-start="stopPlayback"
    @hide-panel="hidePlaybackPanel"
    @keydown="handlePlaybackKeydown"
  >
    <template #run>
      <div v-if="simulationPlaybackResult" class="playback-run" data-testid="simulation-run-info">
        <section class="playback-run__section">
          <h3>{{ t('app.runSummary') }}</h3>
          <dl class="playback-run__facts">
            <div><dt>{{ t('app.actualSimulationSteps') }}</dt><dd>{{ simulationPlaybackResult.steps }} / {{ simulationPlaybackResult.requestedSteps }} {{ t('app.requestedSimulationSteps') }}</dd></div>
            <div><dt>{{ t('app.modelCoverage') }}</dt><dd>{{ isRunModelComplete(simulationPlaybackResult) ? t('app.completeModel') : t('app.incompleteModel') }}</dd></div>
            <div><dt>{{ t('app.traceVisualization.attackScenario') }}</dt><dd>{{ !simulationPlaybackSemanticsConsistent ? t('app.modelSemanticsUnavailable') : simulationPlaybackResult.isAttack ? attackSelectionSummary(simulationPlaybackResult.modelSemantics, simulationPlaybackResult.attackBudget, 'simulation') : t('app.traceVisualization.noAttackModelShort') }}</dd></div>
            <div><dt>{{ t('app.privacy') }}</dt><dd>{{ !simulationPlaybackSemanticsConsistent ? t('app.modelSemanticsUnavailable') : simulationPlaybackResult.enablePrivacy ? t('app.traceVisualization.privacyPropagationEnabled') : t('app.traceVisualization.privacyPropagationNotModeled') }}</dd></div>
          </dl>
          <ul v-if="getGenerationIssues(simulationPlaybackResult).length" class="playback-run__issues board-text-warning" data-testid="simulation-generation-issues">
            <li v-for="(issue, idx) in getGenerationIssues(simulationPlaybackResult)" :key="`${issue.issueType}-${idx}`"><strong>{{ issue.itemLabel }}</strong><span>{{ t(generationIssueReasonKey(issue)) }}</span></li>
          </ul>
          <p v-else-if="!isRunModelComplete(simulationPlaybackResult)" class="playback-terminal__notice board-surface-warning">{{ t('app.generationIssueDetailsUnavailable') }}</p>
        </section>
        <section class="playback-run__section" data-testid="simulation-run-artifact">
          <h3>{{ t('app.simulationSmvModelArtifactTitle') }}</h3>
          <p>{{ t('app.simulationSmvModelArtifactScope', { devices: simulationPlaybackResult.modelSnapshot.deviceCount, rules: simulationPlaybackResult.modelSnapshot.ruleCount }) }}</p>
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
            <pre>{{ simulationPlaybackResult.logs.join('\n') || t('app.noLogsAvailableShort') }}</pre>
          </details>
          <details data-testid="simulation-nusmv-output">
            <summary>{{ t('app.showNusmvDiagnosticOutput') }}</summary>
            <pre>{{ simulationPlaybackResult.nusmvOutput || t('app.noLogsAvailableShort') }}</pre>
          </details>
        </section>
      </div>
    </template>
    <template #changes>
      <PlaybackChangesPanel v-if="playbackChangeProps" v-bind="playbackChangeProps" />
    </template>
  </SimulationTimeline>

  <!-- Keep the request owner mounted so hidden admission-unknown searches remain cancellable. -->
  <FixResultDialog
    ref="fixResultDialogRef"
    :visible="showFixDialog"
    :trace-id="fixTraceId || 0"
    :violated-spec-id="fixViolatedSpecId"
    :violated-spec-name="fixViolatedSpecName"
    :apply-on-board="applyFixOnBoard"
    @update:visible="showFixDialog = $event"
    @applied="handleFixApplied"
    @reverify="showFixDialog = false; reopenVerificationSettingsFromResult()"
    @model-semantics-changed="handleFixModelSemanticsChanged"
  />
</template>
