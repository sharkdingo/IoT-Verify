import { nextTick, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'

import { openModalDepth, useBodyScrollLock } from './useBodyScrollLock'

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'textarea:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'summary',
  '[tabindex]:not([tabindex="-1"])'
].join(',')

const isActuallyFocusable = (element: HTMLElement): boolean => {
  if (element.matches(':disabled')) return false
  if (element.closest('[hidden], [inert], [aria-hidden="true"]')) return false

  const closedDetails = element.closest('details:not([open])')
  if (closedDetails) {
    const summary = Array.from(closedDetails.children)
      .find(child => child.tagName === 'SUMMARY')
    if (!summary?.contains(element)) return false
  }

  const style = getComputedStyle(element)
  const layoutBoxesAvailable = document.documentElement.getClientRects().length > 0
  return (!layoutBoxesAvailable || element.getClientRects().length > 0)
    && style.display !== 'none'
    && style.visibility !== 'hidden'
    && style.visibility !== 'collapse'
}

export const useModalAccessibility = (
  isOpen: Ref<boolean>,
  close: () => void,
  fallbackFocus?: (opener: HTMLElement | null) => HTMLElement | null,
  options: { trapFocus?: boolean; shouldRestoreFocus?: () => boolean } = {}
) => {
  const dialogRef = ref<HTMLElement | null>(null)
  let previousActiveElement: HTMLElement | null = null
  let unmounting = false

  // A focus-trapping surface is a real modal: the page behind it must not scroll.
  // Non-modal tool panels (trapFocus: false) leave the page scrollable on purpose.
  if (options.trapFocus !== false) {
    useBodyScrollLock(isOpen)
  }

  const getFocusableElements = () => {
    const dialog = dialogRef.value
    if (!dialog) return []
    return Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
      .filter(element => element.tabIndex >= 0 && isActuallyFocusable(element))
  }

  const setDialogRef = (element: unknown) => {
    dialogRef.value = element instanceof HTMLElement ? element : null
  }

  const focusInitialElement = () => {
    const dialog = dialogRef.value
    if (!dialog) return
    const focusableElements = getFocusableElements()
    const preferred = dialog.hasAttribute('autofocus')
      ? dialog : focusableElements.find(element => element.hasAttribute('autofocus'))
    ;(preferred ?? focusableElements[0] ?? dialog).focus()
  }

  const restoreFocus = () => {
    if (!previousActiveElement) return
    if (options.shouldRestoreFocus?.() === false) {
      previousActiveElement = null
      return
    }
    const focusIfAvailable = (target: HTMLElement | null | undefined) => {
      if (!target
        || target === document.body
        || !document.contains(target)
        || !isActuallyFocusable(target)) return false
      target.focus()
      return document.activeElement === target
    }
    if (!focusIfAvailable(previousActiveElement) && !focusIfAvailable(fallbackFocus?.(previousActiveElement))) {
      const returnTarget = previousActiveElement
      const focusAtClose = document.activeElement
      // A result can hide its toolbar until the parent renders the closed state.
      void nextTick(() => {
        if ((!unmounting && isOpen.value) || options.shouldRestoreFocus?.() === false) return
        if (document.activeElement !== document.body && document.activeElement !== focusAtClose) return
        if (!focusIfAvailable(returnTarget)) focusIfAvailable(fallbackFocus?.(returnTarget))
      })
    }
    previousActiveElement = null
  }

  const handleModalKeydown = (event: KeyboardEvent) => {
    // A nested trapping surface handles the event first. preventDefault propagates with the event,
    // so respecting it keeps one Escape/Tab press from also acting on an ancestor dialog.
    if (!isOpen.value || event.defaultPrevented) return

    if (event.key === 'Escape') {
      event.preventDefault()
      close()
      return
    }

    if (event.key !== 'Tab' || options.trapFocus === false) return

    const focusableElements = getFocusableElements()
    if (focusableElements.length === 0) {
      event.preventDefault()
      dialogRef.value?.focus()
      return
    }

    const firstElement = focusableElements[0]
    const lastElement = focusableElements[focusableElements.length - 1]
    const activeElement = document.activeElement as HTMLElement | null
    const activeInsideDialog = !!activeElement && !!dialogRef.value?.contains(activeElement)

    if (event.shiftKey && (!activeInsideDialog
      || activeElement === dialogRef.value || activeElement === firstElement)) {
      event.preventDefault()
      lastElement.focus()
      return
    }

    if (!event.shiftKey && activeElement === lastElement) {
      event.preventDefault()
      firstElement.focus()
    }
  }

  /**
   * Modal depth this surface itself accounts for; anything above it is a surface stacked on top,
   * which owns Escape while it is up.
   *
   * A trapping surface takes the scroll lock through `useBodyScrollLock`, whose watcher is
   * `flush: 'sync'` — so by the time this `flush: 'post'` watcher runs, the count already includes
   * this surface. Recording the value verbatim would leave the guard permanently true and this
   * dialog unable to close on Escape at all.
   */
  const ownedDepth = options.trapFocus === false ? 0 : 1
  let ownModalDepth = 0

  // Capture before the originating panel is removed by the same render that opens this surface.
  watch(isOpen, open => {
    if (open) previousActiveElement = document.activeElement as HTMLElement | null
  }, { flush: 'sync', immediate: true })

  watch(isOpen, open => {
    if (open) {
      ownModalDepth = Math.max(openModalDepth.value, ownedDepth)
      void nextTick(focusInitialElement)
    } else {
      restoreFocus()
    }
  }, { flush: 'post', immediate: true })

  /**
   * Document-level Escape fallback.
   *
   * `handleModalKeydown` is bound on the modal's own element, so it only sees the key once focus is
   * inside the dialog — and focus is moved there in a `nextTick` after a `flush: 'post'` watcher. A
   * user (or a deep link that opens the surface on load) can press Escape inside that window, and the
   * keystroke was silently dropped: the dialog stayed open with no indication why. Escape must close a
   * modal whenever it is open, not only once focus has caught up.
   *
   * Scoped to focus-trapping surfaces: the board's non-modal tool panels own their own Escape
   * behaviour and must not all close on one keypress.
   */
  const handleDocumentEscape = (event: KeyboardEvent) => {
    if (event.key !== 'Escape' || !isOpen.value || options.trapFocus === false) return
    // Already handled by the modal's own listener, which runs first when focus is inside it.
    if (event.defaultPrevented) return
    // A confirmation opened *on top* of this dialog owns the keystroke. Element Plus's MessageBox
    // closes on Escape without calling preventDefault (its focus-trap emits `release-requested`
    // instead), so the check above cannot see it — and without this the same press also ran this
    // dialog's `close`, discarding the draft the user was being asked about.
    if (openModalDepth.value > ownModalDepth) return
    const dialog = dialogRef.value
    if (dialog && dialog.contains(document.activeElement)) return
    event.preventDefault()
    close()
  }

  onMounted(() => document.addEventListener('keydown', handleDocumentEscape))
  onBeforeUnmount(() => document.removeEventListener('keydown', handleDocumentEscape))

  onBeforeUnmount(() => {
    // A v-if removal leaves its last visible prop true even after the dialog is gone.
    unmounting = true
    restoreFocus()
  })

  return {
    setDialogRef,
    handleModalKeydown
  }
}
