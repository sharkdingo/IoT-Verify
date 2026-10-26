// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, render, type VNode } from 'vue'

const elementPlus = vi.hoisted(() => ({
  message: Object.assign(vi.fn(), {
    success: vi.fn(), info: vi.fn(), warning: vi.fn(), error: vi.fn(), closeAll: vi.fn()
  }),
  notification: vi.fn(),
  box: { confirm: vi.fn(), alert: vi.fn(), close: vi.fn() }
}))

vi.mock('element-plus', () => ({
  /*
   * `HintTooltip` imports `ElTooltip`, and a whole-module mock hides it — every case in the file then fails at
   * import time with "No 'ElTooltip' export is defined", a message that names the mock rather than the component
   * needing it. A render-slot stub suffices: nothing here asserts tooltip behaviour, only the control it wraps.
   */
  ElTooltip: { name: 'ElTooltip', template: '<slot />' },
  ElMessage: elementPlus.message,
  ElMessageBox: elementPlus.box,
  ElNotification: elementPlus.notification
}))

import {
  acknowledge,
  acknowledgeFailure,
  confirmChoice,
  confirmDestructive,
  dismissAllNotifications,
  dismissCompletionNotifications,
  dismissOpenConfirmation,
  notifyBlocked,
  notifyAvailableResult,
  notifyError,
  notifyInfo,
  notifySuccess
} from '../feedback'
import { i18n } from '@/assets/i18n'
import { openModalDepth } from '@/composables/useBodyScrollLock'

beforeEach(() => {
  vi.clearAllMocks()
  // Backend free text is only shown when it matches the active locale; pin it so the
  // locale-matching behaviour is what is under test, not the default.
  i18n.global.locale.value = 'en'
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('toast severities', () => {
  it('maps each intent to one severity and always sets a duration', () => {
    notifySuccess('saved')
    notifyInfo('noted')
    notifyBlocked('close playback first')
    notifyError('request failed')

    for (const [call, message] of [
      [elementPlus.message.success, 'saved'],
      [elementPlus.message.info, 'noted'],
      [elementPlus.message.warning, 'close playback first'],
      [elementPlus.message.error, 'request failed']
    ] as const) {
      expect(call).toHaveBeenCalledTimes(1)
      const options = call.mock.calls[0][0] as { message: string, duration: number }
      expect(options.message).toBe(message)
      expect(options.duration).toBeGreaterThan(0)
    }
  })

  it('gives an error longer on screen than a success', () => {
    notifySuccess('saved')
    notifyError('failed')
    const success = elementPlus.message.success.mock.calls[0][0] as { duration: number }
    const failure = elementPlus.message.error.mock.calls[0][0] as { duration: number }
    expect(failure.duration).toBeGreaterThan(success.duration)
  })

  it('groups identical errors instead of stacking duplicate toasts', () => {
    notifyError('history failed')

    expect(elementPlus.message.error).toHaveBeenCalledWith(expect.objectContaining({
      message: 'history failed',
      grouping: true
    }))
  })

  it('clears every open toast when the board changes context wholesale', () => {
    dismissAllNotifications()
    expect(elementPlus.message.closeAll).toHaveBeenCalledOnce()
  })

  it('retires completion notices while preserving errors and warnings for a new form interaction', () => {
    dismissCompletionNotifications()
    expect(elementPlus.message.closeAll.mock.calls).toEqual([['success'], ['info']])
  })
})

describe('available result notifications', () => {
  it('does not repeat a generic failure heading as its summary', () => {
    elementPlus.notification.mockReturnValue({ close: vi.fn() })
    notifyAvailableResult({ title: 'Verification failed', message: 'Verification failed',
      actionLabel: 'View details', discardLabel: 'Close', onAction: vi.fn(), onClose: vi.fn() })
    const options = elementPlus.notification.mock.calls[0][0] as Record<string, any>
    const root = document.createElement('div')
    render(options.message as VNode, root)
    try {
      expect(root.querySelector('h2')?.textContent).toBe('Verification failed')
      expect(root.querySelector('p')).toBeNull()
    } finally { render(null, root) }
  })

  it('updates a retained failure notice and its actions when the language changes', async () => {
    const close = vi.fn()
    elementPlus.notification.mockReturnValue({ close })
    const t = (key: string) => i18n.global.t(key)
    const onAction = vi.fn().mockReturnValue(true)
    notifyAvailableResult({ title: () => t('app.verificationFailed'), message: () => t('app.runResponseIncomplete'),
      actionLabel: () => t('app.viewDetails'), discardLabel: () => t('app.close'), onAction, onClose: vi.fn() })
    const options = elementPlus.notification.mock.calls[0][0] as Record<string, any>
    const root = document.createElement('div')
    render(options.message as VNode, root)
    try {
      for (const locale of ['en', 'zh-CN'] as const) {
        i18n.global.locale.value = locale
        await nextTick()
        expect(root.querySelector('h2')?.textContent).toBe(t('app.verificationFailed'))
        expect(root.querySelector('p')?.textContent).toBe(t('app.runResponseIncomplete'))
        expect(root.querySelector('[data-testid="available-result-open"]')?.textContent).toBe(t('app.viewDetails'))
        expect(root.querySelector('[data-testid="available-result-discard"]')?.textContent).toBe(t('app.close'))
      }
      root.querySelector<HTMLButtonElement>('[data-testid="available-result-open"]')!.click()
      expect(onAction).toHaveBeenCalledOnce()
      expect(close).toHaveBeenCalledOnce()
      expect(elementPlus.notification).toHaveBeenCalledOnce()
    } finally { render(null, root) }
  })

  const createNotice = () => {
    const close = vi.fn()
    elementPlus.notification.mockReturnValue({ close })
    const onAction = vi.fn().mockReturnValue(false)
    const onClose = vi.fn()
    const dismiss = notifyAvailableResult({
      title: 'Unsaved result', message: 'Discarding deletes it.', actionLabel: 'Open replay',
      discardLabel: 'Discard result', onAction, onClose
    })
    const options = elementPlus.notification.mock.calls[0][0] as Record<string, any>
    const root = document.createElement('div')
    render(options.message as VNode, root)
    return { close, onAction, onClose, dismiss, options, root }
  }

  it('survives transient toast cleanup and retains a blocked result until explicit opening', () => {
    const notice = createNotice()
    try {
      expect(notice.options).toMatchObject({ duration: 0, showClose: false, position: 'bottom-right' })
      dismissAllNotifications()
      expect(notice.close).not.toHaveBeenCalled()
      const open = notice.root.querySelector<HTMLButtonElement>('[data-testid="available-result-open"]')!
      open.click()
      expect(notice.onAction).toHaveBeenCalledOnce()
      expect(notice.close).not.toHaveBeenCalled()
      notice.onAction.mockReturnValue(true)
      open.click()
      expect(notice.close).toHaveBeenCalledOnce()
    } finally { render(null, notice.root) }
  })

  it('provides an explicit discard control and an owning-view cleanup handle', () => {
    const notice = createNotice()
    try {
      notice.root.querySelector<HTMLButtonElement>('[data-testid="available-result-discard"]')!.click()
      expect(notice.close).toHaveBeenCalledOnce()
      expect(notice.onAction).not.toHaveBeenCalled()
      notice.dismiss()
      expect(notice.close).toHaveBeenCalledTimes(2)
      notice.options.onClose()
      expect(notice.onClose).toHaveBeenCalledOnce()
    } finally { render(null, notice.root) }
  })
})

describe('toast placement', () => {
  /*
   * Element Plus pins the first toast 16px from the viewport top, inside the board's fixed nav, where a
   * centred toast covered undo and redo right after the action it reported. jsdom lays nothing out, so each
   * case states the header geometry it depends on.
   */
  const addAnchor = (bottom: number) => {
    const header = document.createElement('nav')
    header.setAttribute('data-toast-anchor', '')
    vi.spyOn(header, 'getBoundingClientRect').mockReturnValue({ bottom } as DOMRect)
    document.body.append(header)
  }

  const offsets = () => {
    notifySuccess('saved')
    notifyInfo('noted')
    notifyBlocked('close playback first')
    notifyError('request failed')
    return [
      elementPlus.message.success,
      elementPlus.message.info,
      elementPlus.message.warning,
      elementPlus.message.error
    ].map(call => (call.mock.calls[0][0] as { offset?: number }).offset)
  }

  afterEach(() => {
    document.body.replaceChildren()
  })

  it('puts every severity below the fixed header, not over it', () => {
    // 69px is the board nav at its desktop height.
    addAnchor(69)
    expect(offsets()).toEqual([85, 85, 85, 85])
  })

  it('goes below a persistent alert under the header, whichever comes first in the DOM', () => {
    // The board's load-failure alert hangs below the nav; a toast over it would hide the Retry reason.
    addAnchor(133)
    addAnchor(69)
    expect(offsets()).toEqual([149, 149, 149, 149])
  })

  it('keeps the viewport-top slot on a page without a header', () => {
    expect(offsets()).toEqual([16, 16, 16, 16])
  })

  it('ignores a header that has scrolled out of view', () => {
    // The public header is `position: absolute`, so a scrolled landing page puts its bottom above the viewport.
    addAnchor(-40)
    expect(offsets()).toEqual([16, 16, 16, 16])
  })

  it('stays at the viewport top while a modal covers the header', async () => {
    // The header is inert behind the scrim, and a centred dialog can reach near the top, so dropping the toast
    // below the header would put it over the dialog instead.
    addAnchor(69)
    let settle!: () => void
    elementPlus.box.confirm.mockImplementationOnce(() => new Promise<void>(resolve => {
      settle = resolve
    }))
    const pending = confirmChoice({ title: 'Proceed', message: 'Sure?' })
    try {
      expect(offsets()).toEqual([16, 16, 16, 16])
    } finally {
      // Settle even on failure: a leaked modal count would redden every modal-depth case after this one.
      settle()
      await pending
    }
  })
})

describe('confirmDestructive', () => {
  it('retires earlier completion notices before opening the confirmation, preserving warnings and errors', async () => {
    elementPlus.box.confirm.mockImplementationOnce(async () => {
      expect(elementPlus.message.closeAll.mock.calls).toEqual([['success'], ['info']])
    })
    await expect(confirmDestructive({ title: 'Clear scene', message: 'Review contents' })).resolves.toBe(true)
  })

  it('reports confirmation as true and cancellation as false, never throwing', async () => {
    elementPlus.box.confirm.mockResolvedValueOnce('confirm')
    await expect(confirmDestructive({ title: 'Delete', message: 'Sure?' })).resolves.toBe(true)

    elementPlus.box.confirm.mockRejectedValueOnce('cancel')
    await expect(confirmDestructive({ title: 'Delete', message: 'Sure?' })).resolves.toBe(false)

    // A dismissal (Escape / backdrop) is also an ordinary "no".
    elementPlus.box.confirm.mockRejectedValueOnce('close')
    await expect(confirmDestructive({ title: 'Delete', message: 'Sure?' })).resolves.toBe(false)
  })

  it('marks the confirm button as dangerous without automatically focusing it', async () => {
    elementPlus.box.confirm.mockResolvedValueOnce('confirm')
    await confirmDestructive({ title: 'Delete rule', message: 'Sure?', confirmText: 'Delete' })

    const [message, title, options] = elementPlus.box.confirm.mock.calls[0] as [
      string, string, Record<string, unknown>
    ]
    expect(message).toBe('Sure?')
    expect(title).toBe('Delete rule')
    expect(options.confirmButtonText).toBe('Delete')
    expect(options.autofocus).toBe(false)
    expect(options.cancelButtonText).toBeTruthy()
    expect(options.confirmButtonClass).toContain('danger')
  })

  it('does not lock scroll, because the board shell is a fixed non-scrolling surface', async () => {
    elementPlus.box.confirm.mockResolvedValueOnce('confirm')
    await confirmDestructive({ title: 'Delete', message: 'Sure?' })
    const options = elementPlus.box.confirm.mock.calls[0][2] as Record<string, unknown>
    expect(options.lockScroll).toBe(false)
    expect(options.appendTo).toBe('body')
  })
})

describe('confirmChoice', () => {
  /*
   * The non-destructive half. It exists because every confirmation used to be `confirmDestructive`, so a red
   * button appeared on "apply this AI suggestion anyway" and on "delete this device" alike — which left the
   * danger colour meaning nothing at the one moment it has to mean something.
   */
  it('asks the same question without a danger button', async () => {
    elementPlus.box.confirm.mockResolvedValueOnce('confirm')
    await expect(confirmChoice({
      title: 'Similar rule exists',
      message: 'Save anyway?',
      confirmText: 'Save anyway'
    })).resolves.toBe(true)

    const [message, title, options] = elementPlus.box.confirm.mock.calls[0] as [
      string, string, Record<string, unknown>
    ]
    expect(message).toBe('Save anyway?')
    expect(title).toBe('Similar rule exists')
    expect(options.confirmButtonText).toBe('Save anyway')
    expect(options.cancelButtonText).toBeTruthy()
    expect(options.confirmButtonClass).toBeUndefined()
    // Same surface contract as the destructive variant: teleported, and no scroll lock.
    expect(options.lockScroll).toBe(false)
    expect(options.appendTo).toBe('body')
  })

  it('treats cancelling as an ordinary outcome', async () => {
    elementPlus.box.confirm.mockRejectedValueOnce('cancel')
    await expect(confirmChoice({ title: 'Proceed', message: 'Sure?' })).resolves.toBe(false)
  })

  it('counts as a modal surface, so the board accelerators stay blocked behind it', async () => {
    // Same leak as the destructive path: without this, the board's window-level Ctrl+Z reached the surface
    // behind an open confirmation and undid an edit the user could not see.
    expect(openModalDepth.value).toBe(0)
    let settle!: () => void
    elementPlus.box.confirm.mockImplementationOnce(() => new Promise<void>(resolve => {
      settle = resolve
    }))
    const pending = confirmChoice({ title: 'Proceed', message: 'Sure?' })
    expect(openModalDepth.value).toBe(1)
    settle()
    await pending
    expect(openModalDepth.value).toBe(0)
  })
})

describe('modal depth registration', () => {
  // A MessageBox is modal to the user but takes no scroll lock (the board shell is a fixed 100vh
  // surface), so it does not register through `useModalAccessibility` like a `role="dialog"` panel.
  // Without counting here, the board's window-level Ctrl+Z stayed unblocked and undid an edit on the
  // surface behind an open confirmation.
  it('counts an open confirmation as a modal surface and releases it on settle', async () => {
    expect(openModalDepth.value).toBe(0)

    let settle!: () => void
    elementPlus.box.confirm.mockImplementationOnce(() => new Promise<void>(resolve => {
      settle = resolve
    }))
    const pending = confirmDestructive({ title: 'Delete', message: 'Sure?' })
    expect(openModalDepth.value).toBe(1)

    settle()
    await pending
    expect(openModalDepth.value).toBe(0)
  })

  it('releases the count when the confirmation is cancelled or fails', async () => {
    elementPlus.box.confirm.mockRejectedValueOnce('cancel')
    await confirmDestructive({ title: 'Delete', message: 'Sure?' })
    expect(openModalDepth.value).toBe(0)

    elementPlus.box.confirm.mockRejectedValueOnce(new Error('boom'))
    await confirmDestructive({ title: 'Delete', message: 'Sure?' })
    expect(openModalDepth.value).toBe(0)
  })

  it('releases the count when a confirmation is dismissed programmatically', async () => {
    // `ElMessageBox.close()` closes the surface without settling its promise, so the helper's own
    // `finally` never runs. Without an explicit release the count leaked and the board's Ctrl+Z — which
    // reads this depth — stayed blocked for the rest of the session while the toolbar button still
    // looked enabled. Reachable when a rule dialog closes underneath its own "save anyway" prompt.
    elementPlus.box.confirm.mockImplementationOnce(() => new Promise<void>(() => {
      // never settles, matching Element Plus's programmatic close
    }))
    void confirmDestructive({ title: 'Delete', message: 'Sure?' })
    expect(openModalDepth.value).toBe(1)

    dismissOpenConfirmation()

    expect(elementPlus.box.close).toHaveBeenCalled()
    expect(openModalDepth.value).toBe(0)
  })

  it('counts an acknowledgement too', async () => {
    let settle!: () => void
    elementPlus.box.alert.mockImplementationOnce(() => new Promise<void>(resolve => {
      settle = resolve
    }))
    const pending = acknowledge({ title: 'Heads up', message: 'Done' })
    expect(openModalDepth.value).toBe(1)

    settle()
    await pending
    expect(openModalDepth.value).toBe(0)
  })
})

describe('acknowledge', () => {
  it('resolves even when dismissed, since there is nothing further to decide', async () => {
    elementPlus.box.alert.mockRejectedValueOnce('close')
    await expect(acknowledge({ title: 'Invalid scene', message: 'Details' })).resolves.toBeUndefined()
  })

  it('carries its own tone for a diagnostic report', async () => {
    elementPlus.box.alert.mockResolvedValueOnce('confirm')
    await acknowledge({ title: 'Invalid scene', message: 'Details', tone: 'error' })
    const options = elementPlus.box.alert.mock.calls[0][2] as Record<string, unknown>
    expect(options.type).toBe('error')
  })
})

describe('persistent actionable failures', () => {
  it('keeps a generic apply failure title without repeating it in the body', async () => {
    elementPlus.box.alert.mockResolvedValueOnce('confirm')
    await acknowledgeFailure({ title: 'Failed to apply fix', message: 'Failed to apply fix',
      suggestion: 'Review the current model.', details: ['HTTP 400'] })
    const [content, title] = elementPlus.box.alert.mock.calls[0] as [VNode, string]
    const root = document.createElement('div')
    try {
      render(content, root)
      expect(title).toBe('Failed to apply fix')
      expect(root.querySelector('[data-testid="failure-summary"]')).toBeNull()
      expect(root.querySelector('[data-testid="failure-next-step"]')?.textContent).toContain('Review the current model.')
      expect(root.querySelector('details')?.textContent).toContain('HTTP 400')
    } finally { render(null, root) }
  })

  it('keeps a summary, next step and raw diagnostics readable until acknowledged without interpreting HTML', async () => {
    let settle!: () => void
    elementPlus.box.alert.mockImplementationOnce(() => new Promise<void>(resolve => { settle = resolve }))
    const pending = acknowledgeFailure({
      title: 'Import failed', message: 'The scene could not be read.',
      cause: 'Unexpected token <script> at position 12',
      suggestion: 'Correct the indicated field and import again.',
      details: ['Unexpected token <script> at position 12'],
      fields: { '<img src=x onerror=alert(1)>': 'Expected a rule condition' }
    })
    const [content, title, options] = elementPlus.box.alert.mock.calls[0] as [VNode, string, Record<string, unknown>]
    const root = document.createElement('div')
    try {
      render(content, root)
      expect(openModalDepth.value).toBe(1)
      expect(title).toBe('Import failed')
      expect(options).toMatchObject({ type: 'error', lockScroll: false, appendTo: 'body' })
      expect(root.querySelector('[data-testid="failure-summary"]')?.textContent).toBe('The scene could not be read.')
      expect(root.querySelector('[data-testid="failure-next-step"]')?.textContent).toContain('Correct the indicated field and import again.')
      expect(root.querySelector('details')).toBeNull()
      expect(root.querySelector('[data-testid="failure-cause"]')?.textContent).toBe('Unexpected token <script> at position 12')
      expect(root.querySelector('[data-testid="failure-fields"]')?.textContent).toContain('<img src=x onerror=alert(1)>')
      expect(root.querySelector('[data-testid="failure-fields"]')?.textContent).toContain('Expected a rule condition')
      expect(root.querySelector('dt')?.closest('details')).toBeNull()
      expect(root.querySelector('script, img')).toBeNull()
    } finally {
      render(null, root)
      settle()
      await pending
    }
    expect(openModalDepth.value).toBe(0)
    expect(elementPlus.message.error).not.toHaveBeenCalled()
  })

  it('allows dismissal without retrying the failed action or leaking modal depth', async () => {
    elementPlus.box.alert.mockRejectedValueOnce('close')
    await expect(acknowledgeFailure({
      title: 'Request failed', message: 'Result unavailable', suggestion: 'Check history before trying again'
    })).resolves.toBeUndefined()
    expect(openModalDepth.value).toBe(0)
    expect(elementPlus.box.confirm).not.toHaveBeenCalled()
  })
})

describe('dismissOpenConfirmation', () => {
  it('closes a confirmation whose owning surface went away', () => {
    dismissOpenConfirmation()
    expect(elementPlus.box.close).toHaveBeenCalledOnce()
  })
})
