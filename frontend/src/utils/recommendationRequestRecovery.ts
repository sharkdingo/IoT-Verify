import type { InteractiveOperationStatus } from '@/types/task'
import { requestInteractiveCancellation } from '@/utils/interactiveCancellation'

const RESPONSE_RECEIVED = Symbol('recommendation-response-received')

type ResponseAwareError = Error & {
  [RESPONSE_RECEIVED]?: true
  response?: unknown
}

export type RecommendationRequestOwner = {
  userId: number | null
  authToken: string
}

export type OwnedRecommendationPostOptions = {
  authToken: string
  requestId?: string
  signal?: AbortSignal
}

export const markRecommendationResponseReceived = (error: unknown): ResponseAwareError => {
  const marked = error instanceof Error ? error as ResponseAwareError : new Error(String(error)) as ResponseAwareError
  marked[RESPONSE_RECEIVED] = true
  return marked
}

/** A missing response leaves admission unknown; a received HTTP response is terminal evidence. */
export const isRecommendationPostOutcomeUnknown = (error: unknown): boolean => {
  if (!error || typeof error !== 'object') return true
  const responseAware = error as ResponseAwareError
  return responseAware[RESPONSE_RECEIVED] !== true && responseAware.response == null
}

export const isRecommendationRequestActive = (
  running: boolean,
  requestId: string | null
): boolean => running || requestId !== null

export const requestIdAfterTerminalSettlement = (
  currentRequestId: string | null,
  settledRequestId: string
): string | null => currentRequestId === settledRequestId ? null : currentRequestId

const RECOMMENDATION_RECOVERY_FAST_FAILURES = 5
const RECOMMENDATION_RECOVERY_FAST_RETRY_MS = 1_000
const RECOMMENDATION_RECOVERY_SLOW_RETRY_MS = 5_000

export type RecommendationRecoveryFailurePlan = {
  consecutiveFailures: number
  retryDelayMs: number
  outcomeUnknown: boolean
}

/** Status transport failures are never evidence that the server-side request ended. */
export const planRecommendationRecoveryAfterStatusFailure = (
  previousConsecutiveFailures: number
): RecommendationRecoveryFailurePlan => {
  const normalizedFailures = Number.isSafeInteger(previousConsecutiveFailures)
    ? Math.max(0, previousConsecutiveFailures)
    : 0
  const consecutiveFailures = Math.min(Number.MAX_SAFE_INTEGER, normalizedFailures + 1)
  const outcomeUnknown = consecutiveFailures >= RECOMMENDATION_RECOVERY_FAST_FAILURES
  return {
    consecutiveFailures,
    retryDelayMs: consecutiveFailures <= RECOMMENDATION_RECOVERY_FAST_FAILURES
      ? RECOMMENDATION_RECOVERY_FAST_RETRY_MS
      : RECOMMENDATION_RECOVERY_SLOW_RETRY_MS,
    outcomeUnknown
  }
}

export const refreshRecommendationOwnerCredential = (
  owner: RecommendationRequestOwner,
  currentUserId: number | null,
  currentAuthToken: string | null
): RecommendationRequestOwner => owner.userId === currentUserId && currentAuthToken
  ? { userId: currentUserId, authToken: currentAuthToken }
  : owner

export type RecommendationLogoutPreparation = 'ready' | 'outcome-unknown'

export type OwnedRecommendationLogoutOptions = {
  requestId: string
  authToken: string
  cancel: (requestId: string, authToken: string) => Promise<boolean>
  readStatus: (requestId: string, authToken: string) => Promise<InteractiveOperationStatus>
  waitBeforeRetry: (failedAttempt: number) => Promise<void>
  shouldContinue: () => boolean
  hasTerminalEvidence: () => boolean
  onCancellationAccepted?: () => void
  onStatusFinished?: () => void
  maxAttempts?: number
}

export const prepareOwnedRecommendationForLogout = async ({
  requestId,
  authToken,
  cancel,
  readStatus,
  waitBeforeRetry,
  shouldContinue,
  hasTerminalEvidence,
  onCancellationAccepted,
  onStatusFinished,
  maxAttempts = 20
}: OwnedRecommendationLogoutOptions): Promise<RecommendationLogoutPreparation> => {
  try {
    const accepted = await requestInteractiveCancellation({
      cancel: () => cancel(requestId, authToken),
      waitBeforeRetry,
      shouldContinue: () => shouldContinue() && !hasTerminalEvidence(),
      maxAttempts
    })
    if (accepted) {
      onCancellationAccepted?.()
      return 'ready'
    }
  } catch {
    // A status read can still establish terminal state after cancellation transport loss.
  }

  if (hasTerminalEvidence()) return 'ready'
  try {
    const status = await readStatus(requestId, authToken)
    if (status.state === 'FINISHED') {
      onStatusFinished?.()
      return 'ready'
    }
  } catch {
    // Missing status is not evidence that an admission-unknown request finished.
  }
  return hasTerminalEvidence() ? 'ready' : 'outcome-unknown'
}
