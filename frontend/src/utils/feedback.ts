import type { VNode } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { i18n } from '@/assets/i18n'
import { openModalDepth, registerModalSurface } from '@/composables/useBodyScrollLock'

/**
 * The one place user feedback is produced.
 *
 * Call sites state the *intent* ("this destructive action needs confirming"), not the
 * widget, so wording shape, icon, button order, and focus behaviour stay uniform and can be
 * changed in one edit. See docs/guides/frontend-ui-conventions.md for which mechanism
 * belongs to which situation — in particular, a success whose result is already visible on
 * screen gets no toast at all.
 */

const t = (key: string, named?: Record<string, unknown>) =>
  named ? i18n.global.t(key, named) : i18n.global.t(key)

/** Toast durations: long enough to read, short enough not to sit over the canvas. */
const DURATION = { short: 2600, normal: 3600, long: 5200 } as const

/** Element Plus's own gap: its default first offset, and the space it puts between stacked toasts. */
const TOAST_GAP = 16

/**
 * Where a toast's top edge goes: just below the top chrome the user can currently reach.
 *
 * Element Plus pins the first toast 16px from the viewport top, which is inside the board's fixed nav,
 * and a centred toast up to 26rem wide covers the undo and redo buttons there -- exactly when the user
 * wants them, right after the action the toast reports. Top chrome opts in with `data-toast-anchor`:
 * the headers, and the board's persistent alerts, which a transient toast must not hide. The lowest
 * one wins. Under an open modal that chrome is inert behind the scrim, while the dialog is centred and
 * can reach near the top, so the toast keeps the viewport-top slot rather than dropping onto the dialog.
 */
const toastOffset = (): number => {
  if (openModalDepth.value > 0) return TOAST_GAP
  let anchorBottom = 0
  for (const anchor of document.querySelectorAll('[data-toast-anchor]')) {
    anchorBottom = Math.max(anchorBottom, anchor.getBoundingClientRect().bottom)
  }
  return anchorBottom + TOAST_GAP
}

export const notifySuccess = (message: string) => {
  ElMessage.success({ message, duration: DURATION.short, offset: toastOffset() })
}

export const notifyInfo = (message: string) => {
  ElMessage.info({ message, duration: DURATION.short, offset: toastOffset() })
}

/**
 * The action was refused because of current UI/session state (playback open, another run
 * in flight). Tells the user what to close, so it is a warning rather than an error.
 */
export const notifyBlocked = (message: string) => {
  ElMessage.warning({ message, duration: DURATION.normal, offset: toastOffset() })
}

export const notifyError = (message: string) => {
  // Background reconciliation and a user-triggered refresh can report the same failure together.
  // Keep the repeat count without stacking identical errors over the working surface.
  ElMessage.error({ message, duration: DURATION.long, grouping: true, offset: toastOffset() })
}

/**
 * Clears every open toast. Used when the board changes context wholesale (scene replacement,
 * playback teardown) and older toasts would describe a state that no longer exists.
 */
export const dismissAllNotifications = () => {
  ElMessage.closeAll()
}

type ConfirmOptions = {
  title: string
  /**
   * Plain text, or a VNode when the surface needs structure (per-field diagnostics with the
   * raw cause tucked into a "Technical Details" disclosure rather than shown inline).
   */
  message: string | VNode
  /** Defaults to a generic "Confirm"; name the action instead where possible. */
  confirmText?: string
  cancelText?: string
  /** Only for surfaces that need extra layout (the full-scene replacement preview). */
  customClass?: string
}

/**
 * Element Plus teleports MessageBox to <body>, so it sits above every app surface via the
 * `--z-message-box` layer. `lockScroll: false` because the board shell is a fixed
 * `100vh; overflow: hidden` surface — Element Plus's scrollbar compensation would otherwise
 * shift the whole fixed layout.
 */
const BASE_BOX = { appendTo: 'body', lockScroll: false } as const

/**
 * A decision that removes or overwrites something. Red confirm button.
 *
 * Not every question is one of these, which is why `confirmChoice` exists beside it. This was the only
 * confirm helper, so seventeen call sites shared a danger button — including "apply this AI suggestion
 * anyway", "save a duplicate rule", and logging out with an unknown chat outcome. None of those destroys
 * anything, and a red button on all of them is how a real deletion stops standing out from a question.
 *
 * Resolves `true` on confirm and `false` on cancel/dismiss — cancelling is a normal
 * outcome, never an exception the caller has to catch.
 */
export const confirmDestructive = async (options: ConfirmOptions): Promise<boolean> => {
  // Counts as an open modal for the duration. A MessageBox is modal to the user but takes no scroll
  // lock, so without this a `window`-level accelerator (the board's Ctrl+Z) still reached the surface
  // behind the confirmation and mutated it.
  const releaseModalSurface = trackModalSurface()
  try {
    await ElMessageBox.confirm(options.message, options.title, {
      ...BASE_BOX,
      type: 'warning',
      confirmButtonText: options.confirmText ?? t('app.confirm'),
      cancelButtonText: options.cancelText ?? t('app.cancel'),
      confirmButtonClass: 'el-button--danger',
      ...(options.customClass ? { customClass: options.customClass } : {})
    })
    return true
  } catch (error) {
    // A dismissal rejects with 'cancel'/'close'. Anything else is a real failure, and treating it as
    // a deliberate cancel silently is safe for the user but hides the cause from the next debugger.
    if (error !== 'cancel' && error !== 'close') {
      console.error('Confirmation dialog failed:', error)
    }
    return false
  } finally {
    releaseModalSurface()
  }
}

/**
 * A decision that proceeds with something rather than destroying it: continue past a warning, apply a
 * suggestion, leave with work in an unknown state. Accent confirm button, same shape and behaviour as
 * `confirmDestructive` otherwise.
 *
 * Name the action in `confirmText` — "Apply anyway" tells the user what the button does; "Confirm" makes
 * them re-read the message to find out.
 */
export const confirmChoice = async (options: ConfirmOptions): Promise<boolean> => {
  const releaseModalSurface = trackModalSurface()
  try {
    await ElMessageBox.confirm(options.message, options.title, {
      ...BASE_BOX,
      type: 'info',
      confirmButtonText: options.confirmText ?? t('app.confirm'),
      cancelButtonText: options.cancelText ?? t('app.cancel'),
      ...(options.customClass ? { customClass: options.customClass } : {})
    })
    return true
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      console.error('Confirmation dialog failed:', error)
    }
    return false
  } finally {
    releaseModalSurface()
  }
}

/**
 * Releases still held by confirmations that have not settled.
 *
 * `ElMessageBox.close()` closes the surface *without* resolving or rejecting its promise: Element
 * Plus's `doClose` only emits `action` when one was already set, which a programmatic close never
 * does. So the `finally` in the helpers below never runs for a dismissed confirmation, and the
 * modal-surface count it holds would leak — permanently blocking the board's Ctrl+Z, which reads that
 * count. Tracking the releases here lets `dismissOpenConfirmation` settle them itself.
 */
const outstandingModalReleases = new Set<() => void>()

const trackModalSurface = (): (() => void) => {
  const release = registerModalSurface()
  const settle = () => {
    outstandingModalReleases.delete(settle)
    release()
  }
  outstandingModalReleases.add(settle)
  return settle
}

/**
 * Dismisses whatever confirmation is on screen. Needed when the surface that raised it goes
 * away underneath it (its owning dialog closes), which would otherwise leave a confirmation
 * asking about state the user can no longer see.
 *
 * The pending promise is left unsettled by Element Plus, so the caller's `await` never resumes; what
 * this does guarantee is that the surface is gone and its modal-surface registration is released.
 */
export const dismissOpenConfirmation = () => {
  ElMessageBox.close()
  for (const release of [...outstandingModalReleases]) release()
}

/** Acknowledgement of something the user cannot act on further. */
export const acknowledge = async (
  options: Omit<ConfirmOptions, 'cancelText'> & { tone?: 'warning' | 'error' }
): Promise<void> => {
  const releaseModalSurface = trackModalSurface()
  try {
    await ElMessageBox.alert(options.message, options.title, {
      ...BASE_BOX,
      type: options.tone ?? 'warning',
      confirmButtonText: options.confirmText ?? t('app.confirm')
    })
  } catch {
    // Dismissing an acknowledgement is equivalent to confirming it.
  } finally {
    releaseModalSurface()
  }
}
