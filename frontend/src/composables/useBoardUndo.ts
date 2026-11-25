import { computed, onBeforeUnmount, onMounted, readonly, ref } from 'vue'

import boardApi from '@/api/board'
import { isBoardEditHistoryRevision, type BoardUndoAvailability, type BoardUndoResult } from '@/types/boardEdit'
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
  /** Reconciles an unconfirmed or conflicted request through the same mutation queue. */
  reconcile: () => Promise<boolean>
  /**
   * Follow-ups the applied result owes beyond the collections themselves.
   *
   * Undo is a semantic scene change, so recommendations built on the pre-undo scene are stale. The
   * mutation queue's own scene-change hook cannot do this: undo passes `trackSemanticChange: false`
   * because the commit path already owns verification staleness.
   *
   * Receives the result so the caller can say *what* was reversed. The server sends `entityType` and
   * `originalOperation` on every applied undo and nothing read them, so a successful undo changed the
   * board silently — fine when the affected object is on screen, and not fine when it is a rule or a
   * specification the user cannot currently see.
   */
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
    reasonCode: 'blocked' | 'nothing' | 'conflict' | 'failed',
    direction: 'undo' | 'redo',
    error?: unknown,
    reconciled?: boolean
  ) => void
}) => {
  const observedHistory = ref<BoardUndoAvailability | null>(null)
  const canUndo = computed(() => observedHistory.value?.canUndo ?? false)
  const canRedo = computed(() => observedHistory.value?.canRedo ?? false)
  const isApplying = ref(false)
  // A read started before an applied edit must not trigger a refresh based on an older observation.
  let availabilityEpoch = 0
  // Latest-started read wins among reads from the same mutation epoch.
  let availabilityReadSequence = 0

  /** Adopt only the history paired with the visible Board or a committed user edit. */
  const syncAvailability = ({ canUndo, canRedo, editHistoryRevision }: Partial<BoardUndoAvailability>) => {
    if (typeof canUndo !== 'boolean' || typeof canRedo !== 'boolean') return
    if (!isBoardEditHistoryRevision(editHistoryRevision)) {
      // Scene replacement clears history even though its receipt needs no revision.
      if (canUndo || canRedo) return
      observedHistory.value = null
    } else {
      observedHistory.value = { canUndo, canRedo, editHistoryRevision }
    }
    availabilityEpoch += 1
  }

  const apply = async (direction: 'undo' | 'redo') => {
    if (isApplying.value) return
    if (options.isBlocked()) {
      options.report('blocked', direction)
      return
    }
    const observed = observedHistory.value
    if (!observed || !(direction === 'undo' ? observed.canUndo : observed.canRedo)) return

    isApplying.value = true
    try {
      await options.submit(async () => {
        const result = await boardApi.applyBoardEditUndo(direction, observed.editHistoryRevision)
        if (result.applied) options.applyResult(result)
        syncAvailability(result)
        if (result.applied) options.onApplied?.(result)
        else options.report('nothing', direction)
      })
    } catch (error) {
      // A queued request can be rejected because the board went away, not because the undo failed.
      if (options.isIgnorableError?.(error)) return
      // The request may have committed despite the rejected response. Invalidate availability reads
      // started before it just as a confirmed mutation would.
      availabilityEpoch += 1
      const status = (error as { response?: { status?: number } })?.response?.status
      // A 409 proves this request made no write, but also proves the local board may be stale. Every
      // other failure is less certain: the server may have committed before the response was lost or
      // rejected by the client contract parser. Reconcile both through the mutation queue before
      // describing the outcome, and never claim the board was unchanged when it is unknown.
      const reconciled = await options.reconcile().catch(() => false)
      options.report(status === 409 ? 'conflict' : 'failed', direction, error, reconciled)
    } finally {
      isApplying.value = false
    }
  }

  /** A standalone read can detect drift. Only a Board refresh can adopt the newer revision. */
  const hasHistoryChanged = async (): Promise<boolean> => {
    const epoch = availabilityEpoch
    const readSequence = ++availabilityReadSequence
    try {
      const availability = await boardApi.getBoardEditAvailability()
      // A mutation landed while this was in flight, or a later refresh was already requested.
      if (epoch !== availabilityEpoch || readSequence !== availabilityReadSequence) return false
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
  onBeforeUnmount(() => window.removeEventListener('keydown', handleKeydown))

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
