import { computed, onBeforeUnmount, onMounted, readonly, ref } from 'vue'

import boardApi from '@/api/board'
import { isBoardEditHistoryRevision, type BoardUndoAvailability, type BoardUndoResult } from '@/types/boardEdit'
import { readBoardWriteReceipt } from '@/utils/boardWriteReceipt'
import { resolveBoardUndoIntent, targetOwnsNativeUndo } from './boardUndoShortcut'

/**
 * Owns the board's undo/redo affordance: the keyboard accelerators, the in-flight guard, and the
 * availability the server reports.
 *
 * Deliberately holds **no history of its own**. The server journal is the authority for what is
 * reversible, and every applied result carries all four authoritative semantic collections, so
 * this never has to invert an edit locally or reconcile a snapshot stack. That is also why
 * `canUndo` starts false: until the server has told us otherwise, we do not claim an edit is
 * reversible.
 *
 * Scope boundaries this composable maintains:
 * - Undo reverses a *persisted board edit*. It does not cancel a run, close a dialog, dismiss a
 *   result, or navigate browser history — those have their own controls.
 * - Text fields, `contenteditable` editors, and active IME compositions keep native undo.
 */
export const useBoardUndo = (options: {
  /** Applies the authoritative post-operation collections to the board. */
  applyResult: (result: BoardUndoResult) => void
  /**
   * Runs the request through the caller's board-mutation queue.
   *
   * Undo is a board mutation and must serialize with the others: a delete still in flight when
   * Ctrl+Z fires would otherwise race it, and whichever response landed last would win
   * permanently — leaving the rule list and the undo affordance describing a server state that no
   * longer exists. `isApplying` only guards against a second *undo*, and the keyboard listener is
   * on `window`, so a disabled button cannot prevent this either.
   */
  submit: <T>(work: () => Promise<T>) => Promise<T>
  /** Fence queued writes and late receipts to the Board that requested them. */
  captureOwner: () => () => boolean
  /** Reconciles an unconfirmed or conflicted request inside its existing queue slot. */
  reconcile: () => Promise<boolean>
  /** Receives the applied receipt so the caller can announce which edit was reversed. */
  onApplied?: (result: BoardUndoResult) => void
  /**
   * True for a rejection that is a normal lifecycle outcome, not a failure to report — the board
   * unmounting or the auth scope changing while the request sat in the queue. Reporting those pops
   * an error toast on whatever page the user has moved to.
   */
  isIgnorableError?: (error: unknown) => boolean
  /** True while something else must not be interrupted (playback, scene replacement, a run). */
  isBlocked: () => boolean
  /** Called with a stable reason code so the caller owns all user-visible wording. */
  report: (
    reasonCode: 'blocked' | 'nothing' | 'conflict' | 'failed' | 'unconfirmed' | 'replay-blocked',
    direction: 'undo' | 'redo',
    error?: unknown,
    reconciled?: boolean
  ) => void
}) => {
  const observedHistory = ref<BoardUndoAvailability | null>(null)
  const canUndo = computed(() => observedHistory.value?.canUndo ?? false)
  const canRedo = computed(() => observedHistory.value?.canRedo ?? false)
  const isApplying = ref(false)
  const unconfirmedDirections = new Set<'undo' | 'redo'>()
  let active = true
  // A newer probe or mutation invalidates any earlier probe.
  let availabilityGeneration = 0

  /** Adopt only the history paired with the visible Board or a committed user edit. */
  const syncAvailability = ({ canUndo, canRedo, editHistoryRevision }: Partial<BoardUndoAvailability>) => {
    if (typeof canUndo !== 'boolean' || typeof canRedo !== 'boolean') return
    // Scene replacement clears history even though its receipt needs no revision.
    const hasRevision = isBoardEditHistoryRevision(editHistoryRevision)
    if (!hasRevision && (canUndo || canRedo)) return
    const previousRevision = observedHistory.value?.editHistoryRevision
    observedHistory.value = hasRevision ? { canUndo, canRedo, editHistoryRevision } : null
    if (observedHistory.value?.editHistoryRevision !== previousRevision) unconfirmedDirections.clear()
    availabilityGeneration += 1
  }

  const captureRequestOwner = () => {
    const ownsBoard = options.captureOwner()
    return () => active && ownsBoard()
  }

  const apply = async (direction: 'undo' | 'redo') => {
    if (!active || isApplying.value) return
    if (options.isBlocked()) {
      options.report('blocked', direction)
      return
    }
    const observed = observedHistory.value
    if (!observed || !(direction === 'undo' ? observed.canUndo : observed.canRedo)) return
    if (unconfirmedDirections.has(direction)) {
      options.report('replay-blocked', direction)
      return
    }

    const ownsRequest = captureRequestOwner()
    const isConflict = (error: unknown) => (error as { response?: { status?: number } })?.response?.status === 409
    isApplying.value = true
    try {
      const receipt = await options.submit(async () => {
        let abandoned = false
        const receipt = await readBoardWriteReceipt(
          () => boardApi.applyBoardEditUndo(direction, observed.editHistoryRevision).catch(error => {
            abandoned = options.isIgnorableError?.(error) ?? false
            if (!abandoned && ownsRequest()) availabilityGeneration += 1
            throw error
          }),
          () => !abandoned && ownsRequest(),
          { refreshOnRejection: isConflict, reconcile: () => options.reconcile().catch(() => false) }
        )
        if (receipt?.status === 'confirmed') {
          const { mutation } = receipt
          if (mutation.applied) options.applyResult(mutation)
          syncAvailability(mutation)
        } else if (receipt?.status === 'unconfirmed'
          && observedHistory.value?.editHistoryRevision === observed.editHistoryRevision) {
          unconfirmedDirections.add(direction)
        }
        return receipt
      })
      if (!receipt || !ownsRequest()) return
      if (receipt.status === 'confirmed') {
        if (receipt.mutation.applied) options.onApplied?.(receipt.mutation)
        else options.report('nothing', direction)
      } else {
        const reason = receipt.status === 'unconfirmed' ? 'unconfirmed' : isConflict(receipt.error) ? 'conflict' : 'failed'
        options.report(reason, direction, receipt.error, receipt.snapshotRefreshed)
      }
    } catch (error) {
      if (ownsRequest() && !options.isIgnorableError?.(error)) options.report('failed', direction, error, false)
    } finally {
      isApplying.value = false
    }
  }

  /** A standalone read can detect drift. Only a Board refresh can adopt the newer revision. */
  const hasHistoryChanged = async (): Promise<boolean> => {
    const ownsRequest = captureRequestOwner()
    if (!ownsRequest()) return false
    const generation = ++availabilityGeneration
    try {
      const availability = await boardApi.getBoardEditAvailability()
      if (!ownsRequest() || generation !== availabilityGeneration) return false
      return observedHistory.value === null
        ? availability.canUndo || availability.canRedo
        : availability.editHistoryRevision !== observedHistory.value.editHistoryRevision
    } catch {
      return false
    }
  }

  const handleKeydown = (event: KeyboardEvent) => {
    if (targetOwnsNativeUndo(event.target)) return
    const intent = resolveBoardUndoIntent(event)
    if (!intent || !(intent === 'undo' ? canUndo.value : canRedo.value)) return
    // Only claim the keystroke once we are sure it is ours, so the browser's own undo still
    // works everywhere we decline.
    event.preventDefault()
    void apply(intent)
  }

  onMounted(() => window.addEventListener('keydown', handleKeydown))
  onBeforeUnmount(() => {
    active = false
    window.removeEventListener('keydown', handleKeydown)
  })

  return {
    canUndo,
    canRedo,
    isApplying: readonly(isApplying),
    hasHistoryChanged,
    syncAvailability,
    undo: () => apply('undo'),
    redo: () => apply('redo')
  }
}
