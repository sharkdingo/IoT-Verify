// @vitest-environment jsdom
import { defineComponent, h } from 'vue'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const boardApi = vi.hoisted(() => ({
  applyBoardEditUndo: vi.fn(),
  getBoardEditAvailability: vi.fn()
}))
vi.mock('@/api/board', () => ({ default: boardApi }))

import { useBoardUndo } from '../useBoardUndo'
import type { BoardUndoAvailability, BoardUndoResult } from '@/types/boardEdit'

const result = (over: Partial<BoardUndoResult> = {}): BoardUndoResult => ({
  applied: true,
  reasonCode: 'UNDONE',
  nodes: [],
  environmentVariables: [],
  rules: [],
  specs: [],
  canUndo: false,
  canRedo: true,
  editHistoryRevision: '10:0:1',
  ...over
})

const visibleHistory = { canUndo: true, canRedo: true, editHistoryRevision: '10:1:2' }
const emptyHistory = { canUndo: false, canRedo: false, editHistoryRevision: '0:0:0' }

const deferred = <T>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(complete => { resolve = complete })
  return { promise, resolve }
}

const mountUndo = ({ seedHistory, ...options }: Partial<Parameters<typeof useBoardUndo>[0]>
  & { seedHistory?: boolean } = {}) => {
  let api!: ReturnType<typeof useBoardUndo>
  const wrapper = mount(defineComponent({
    setup() {
      api = useBoardUndo({
        applyResult: () => undefined,
        submit: work => work(),
        reconcile: async () => true,
        captureOwner: () => () => true,
        isBlocked: () => false,
        report: () => undefined,
        ...options
      })
      return () => h('div')
    }
  }), { attachTo: document.body })
  if (seedHistory) api.syncAvailability(visibleHistory)
  return { wrapper, api }
}

beforeEach(() => {
  vi.resetAllMocks()
})

enableAutoUnmount(afterEach)

afterEach(() => {
  document.body.innerHTML = ''
})

describe('availability', () => {
  it('claims nothing is reversible until the server says so', () => {
    const { api } = mountUndo()
    expect(api.canUndo.value).toBe(false)
    expect(api.canRedo.value).toBe(false)
  })

  it('mirrors the availability a reversible mutation reported', () => {
    const { api } = mountUndo()
    api.syncAvailability({ ...visibleHistory, canRedo: false })
    expect(api.canUndo.value).toBe(true)
    expect(api.canRedo.value).toBe(false)
  })

  it('ignores a mutation that does not report availability', () => {
    const { api } = mountUndo()
    api.syncAvailability(visibleHistory)
    // A non-reversible mutation omits both; that must not clear real history.
    api.syncAvailability({})
    expect(api.canUndo.value).toBe(true)
    expect(api.canRedo.value).toBe(true)
  })

  it('ignores an availability read superseded by an ordinary mutation result', async () => {
    const { api } = mountUndo()
    const availability = deferred<BoardUndoAvailability>()
    boardApi.getBoardEditAvailability.mockReturnValueOnce(availability.promise)

    const staleRead = api.hasHistoryChanged()
    api.syncAvailability({ ...visibleHistory, canRedo: false })
    availability.resolve(emptyHistory)
    expect(await staleRead).toBe(false)

    expect(api.canUndo.value).toBe(true)
    expect(api.canRedo.value).toBe(false)
  })

  it('lets the latest availability refresh win when reads complete out of order', async () => {
    const { api } = mountUndo()
    const stale = deferred<BoardUndoAvailability>()
    const latest = deferred<BoardUndoAvailability>()
    boardApi.getBoardEditAvailability.mockReturnValueOnce(stale.promise).mockReturnValueOnce(latest.promise)

    const first = api.hasHistoryChanged()
    const second = api.hasHistoryChanged()
    latest.resolve(visibleHistory)
    expect(await second).toBe(true)
    stale.resolve({ ...visibleHistory, editHistoryRevision: '9:1:1' })
    expect(await first).toBe(false)

    expect(api.canUndo.value).toBe(false)
    expect(api.canRedo.value).toBe(false)
  })

  it('takes availability from the server even when nothing was applied', async () => {
    boardApi.applyBoardEditUndo.mockResolvedValue(
      result({ applied: false, reasonCode: 'NOTHING_TO_APPLY', canUndo: false, canRedo: false }))
    const report = vi.fn()
    const { api } = mountUndo({ report })
    api.syncAvailability(visibleHistory)

    await api.undo()

    expect(report).toHaveBeenCalledWith('nothing', 'undo')
    // A stale local guess is corrected rather than kept.
    expect(api.canUndo.value).toBe(false)
    expect(api.canRedo.value).toBe(false)
  })
})

describe('applying', () => {
  it('submits the history revision visible when the user requested undo', async () => {
    const queued = deferred<void>()
    const { api } = mountUndo({ submit: async work => {
      await queued.promise
      return work()
    } })
    const observed = { canUndo: true, canRedo: false, editHistoryRevision: '12:2:0' }
    api.syncAvailability(observed)
    boardApi.applyBoardEditUndo.mockResolvedValueOnce(result())
    const pending = api.undo()
    api.syncAvailability({ ...observed, editHistoryRevision: '13:3:0' })
    queued.resolve()
    await pending
    expect(boardApi.applyBoardEditUndo).toHaveBeenCalledWith('undo', observed.editHistoryRevision)
  })

  it('hands the authoritative collections to the caller', async () => {
    const applied = result({ rules: [{ id: '4', sources: [], toId: 'lamp', toApi: 'on' }], canUndo: true })
    boardApi.applyBoardEditUndo.mockResolvedValue(applied)
    const applyResult = vi.fn()
    const { api } = mountUndo({ applyResult, seedHistory: true })

    await api.undo()

    expect(boardApi.applyBoardEditUndo).toHaveBeenCalledWith('undo', visibleHistory.editHistoryRevision)
    expect(applyResult).toHaveBeenCalledWith(applied)
    expect(api.canUndo.value).toBe(true)
  })

  it('refuses while playback or a scene operation owns the board', async () => {
    const report = vi.fn()
    const { api } = mountUndo({ isBlocked: () => true, report })

    await api.undo()

    expect(boardApi.applyBoardEditUndo).not.toHaveBeenCalled()
    expect(report).toHaveBeenCalledWith('blocked', 'undo')
  })

  it('reports a version conflict distinctly from a transport failure', async () => {
    const report = vi.fn()
    const { api } = mountUndo({ report, seedHistory: true })

    boardApi.applyBoardEditUndo.mockRejectedValueOnce({ response: { status: 409 } })
    await api.undo()
    expect(report.mock.calls[0][0]).toBe('conflict')

    boardApi.applyBoardEditUndo.mockRejectedValueOnce(new Error('offline'))
    await api.undo()
    expect(report.mock.calls[1][0]).toBe('unconfirmed')
  })

  it('reconciles after a conflict and mirrors the retained journal entry', async () => {
    // A conflict is a rejected write, but it also says the local board may be stale. The server
    // deliberately retains the entry, so availability remains true after reconciliation.
    const report = vi.fn()
    const reconcile = vi.fn(async () => {
      api.syncAvailability({ ...visibleHistory, editHistoryRevision: '11:2:0', canRedo: false })
      return true
    })
    const { api } = mountUndo({ report, reconcile, seedHistory: true })

    boardApi.applyBoardEditUndo.mockRejectedValueOnce({ response: { status: 409 } })
    await api.undo()

    expect(reconcile).toHaveBeenCalledTimes(1)
    expect(report).toHaveBeenCalledWith('conflict', 'undo', expect.anything(), true)
    expect(boardApi.getBoardEditAvailability).not.toHaveBeenCalled()
    expect(api.canUndo.value).toBe(true)
  })

  it('reconciles an unconfirmed failure before reporting it', async () => {
    const report = vi.fn()
    const reconcile = vi.fn(async () => true)
    const { api } = mountUndo({ report, reconcile, seedHistory: true })
    const failure = new Error('response contract rejected')
    boardApi.applyBoardEditUndo.mockRejectedValue(failure)

    await api.redo()

    expect(reconcile).toHaveBeenCalledTimes(1)
    expect(report).toHaveBeenCalledWith('unconfirmed', 'redo', failure, true)
    expect(api.canRedo.value).toBe(true)
  })

  it('reports a definitive rejection without fetching a recovery snapshot', async () => {
    const report = vi.fn()
    const reconcile = vi.fn(async () => true)
    const { api } = mountUndo({ report, reconcile, seedHistory: true })
    const rejection = { response: { status: 422 } }
    boardApi.applyBoardEditUndo.mockRejectedValue(rejection)

    await api.undo()

    expect(reconcile).not.toHaveBeenCalled()
    expect(report).toHaveBeenCalledWith('failed', 'undo', rejection, false)
  })

  it('does not replay the same unconfirmed direction until it observes a newer history revision', async () => {
    const report = vi.fn()
    const reconcile = vi.fn(async () => true)
    const { api } = mountUndo({ report, reconcile, seedHistory: true })
    const failure = new Error('lost undo receipt')
    boardApi.applyBoardEditUndo.mockRejectedValueOnce(failure).mockResolvedValue(result())

    await api.undo()
    await api.undo()
    expect(boardApi.applyBoardEditUndo).toHaveBeenCalledOnce()
    expect(reconcile).toHaveBeenCalledOnce()
    expect(api.canUndo.value).toBe(true)
    expect(report).toHaveBeenLastCalledWith('replay-blocked', 'undo')

    api.syncAvailability({ ...visibleHistory, editHistoryRevision: '11:2:0' })
    await api.undo()
    expect(boardApi.applyBoardEditUndo).toHaveBeenLastCalledWith('undo', '11:2:0')
  })

  it('discards a confirmed receipt when the owning Board changed while it was in flight', async () => {
    let isCurrent = true
    const receipt = deferred<BoardUndoResult>()
    boardApi.applyBoardEditUndo.mockReturnValue(receipt.promise)
    const applyResult = vi.fn()
    const onApplied = vi.fn()
    const { api } = mountUndo({ seedHistory: true, applyResult, onApplied, captureOwner: () => () => isCurrent })
    const undo = api.undo()
    isCurrent = false
    receipt.resolve(result())
    await undo

    expect(applyResult).not.toHaveBeenCalled()
    expect(onApplied).not.toHaveBeenCalled()
    expect(api.canUndo.value).toBe(true)
  })

  it('finishes recovering an unconfirmed undo before dispatching the next queued edit', async () => {
    const recovery = deferred<boolean>()
    const reconcile = vi.fn(() => recovery.promise)
    const nextEdit = vi.fn(async () => undefined)
    let tail = Promise.resolve()
    const submit = <T,>(work: () => Promise<T>) => {
      const next = tail.then(work)
      tail = next.then(() => undefined, () => undefined)
      return next
    }
    boardApi.applyBoardEditUndo.mockRejectedValueOnce(new Error('response lost'))
    const { api } = mountUndo({ submit, reconcile, seedHistory: true })
    const undo = api.undo()
    const subsequent = submit(nextEdit)
    try {
      await vi.waitFor(() => expect(reconcile).toHaveBeenCalledOnce())
      expect(nextEdit).not.toHaveBeenCalled()
    } finally {
      recovery.resolve(true)
      await Promise.all([undo, subsequent])
    }
    expect(nextEdit).toHaveBeenCalledOnce()
  })

  it('ignores an availability read that a later mutation has already superseded', async () => {
    // An availability probe runs outside the undo queue and can settle after a receipt is adopted.
    const { api } = mountUndo({ seedHistory: true })
    const availability = deferred<BoardUndoAvailability>()
    boardApi.getBoardEditAvailability.mockReturnValueOnce(availability.promise)

    const pending = api.hasHistoryChanged()
    boardApi.applyBoardEditUndo.mockResolvedValueOnce(result({ canUndo: false, canRedo: true }))
    await api.undo()
    expect(api.canUndo.value).toBe(false)

    availability.resolve(visibleHistory)
    expect(await pending).toBe(false)

    expect(api.canUndo.value).toBe(false)
  })

  it('invalidates availability reads started before an unconfirmed mutation', async () => {
    const { api } = mountUndo({ reconcile: async () => true, seedHistory: true })
    const availability = deferred<BoardUndoAvailability>()
    boardApi.getBoardEditAvailability.mockReturnValueOnce(availability.promise)

    const staleRead = api.hasHistoryChanged()
    boardApi.applyBoardEditUndo.mockRejectedValueOnce(new Error('response lost'))
    await api.undo()
    expect(api.canRedo.value).toBe(true)

    availability.resolve({ ...visibleHistory, editHistoryRevision: '9:1:1' })
    expect(await staleRead).toBe(false)

    expect(api.canUndo.value).toBe(true)
    expect(api.canRedo.value).toBe(true)
  })

  it('runs one request at a time so a held shortcut cannot stack undos', async () => {
    const receipt = deferred<BoardUndoResult>()
    boardApi.applyBoardEditUndo.mockReturnValue(receipt.promise)
    const { api } = mountUndo({ seedHistory: true })

    const first = api.undo()
    await api.undo()
    expect(boardApi.applyBoardEditUndo).toHaveBeenCalledTimes(1)

    receipt.resolve(result())
    await first
    expect(api.isApplying.value).toBe(false)
  })
})

describe('keyboard scope', () => {
  const press = (target: HTMLElement, init: Partial<KeyboardEventInit> & { key: string }) => {
    const event = new KeyboardEvent('keydown', { ...init, bubbles: true, cancelable: true })
    target.dispatchEvent(event)
    return event
  }

  it('does not request an undo for an empty visible history', () => {
    mountUndo()
    expect(press(document.body, { key: 'z', ctrlKey: true }).defaultPrevented).toBe(false)
    expect(boardApi.applyBoardEditUndo).not.toHaveBeenCalled()
  })

  it('applies the accelerators from an ordinary board surface', async () => {
    boardApi.applyBoardEditUndo.mockResolvedValue(result())
    const { api } = mountUndo({ seedHistory: true })
    const surface = document.createElement('div')
    document.body.append(surface)

    const undoEvent = press(surface, { key: 'z', ctrlKey: true })
    expect(undoEvent.defaultPrevented).toBe(true)
    await vi.waitFor(() =>
      expect(boardApi.applyBoardEditUndo).toHaveBeenCalledWith('undo', visibleHistory.editHistoryRevision))
    await vi.waitFor(() => expect(api.isApplying.value).toBe(false))

    press(surface, { key: 'z', ctrlKey: true, shiftKey: true })
    await vi.waitFor(() =>
      expect(boardApi.applyBoardEditUndo).toHaveBeenCalledWith('redo', result().editHistoryRevision))
  })

  it('leaves Ctrl+Z inside a text field to the field itself', () => {
    mountUndo({ seedHistory: true })
    const input = document.createElement('input')
    document.body.append(input)

    const event = press(input, { key: 'z', ctrlKey: true })

    // Not prevented and not dispatched: the browser's own per-field undo must still run.
    expect(event.defaultPrevented).toBe(false)
    expect(boardApi.applyBoardEditUndo).not.toHaveBeenCalled()
  })

  it('recognizes changed history but adopts it only with the Board snapshot', async () => {
    const { api } = mountUndo({ seedHistory: true })
    boardApi.getBoardEditAvailability.mockResolvedValue(emptyHistory)
    expect(await api.hasHistoryChanged()).toBe(true)
    expect(api.canUndo.value).toBe(true)

    api.syncAvailability(emptyHistory)
    expect(api.canUndo.value).toBe(false)
    expect(api.canRedo.value).toBe(false)
    expect(await api.hasHistoryChanged()).toBe(false)
  })

  it('leaves the affordance untouched when availability cannot be read', async () => {
    const { api } = mountUndo()
    api.syncAvailability({ ...visibleHistory, canRedo: false })

    boardApi.getBoardEditAvailability.mockRejectedValue(new Error('offline'))
    await api.hasHistoryChanged()

    // Failing to read must not invent availability in either direction.
    expect(api.canUndo.value).toBe(true)
  })

  it('stops listening once the board unmounts', () => {
    const { wrapper } = mountUndo({ seedHistory: true })
    wrapper.unmount()

    press(document.body, { key: 'z', ctrlKey: true })

    expect(boardApi.applyBoardEditUndo).not.toHaveBeenCalled()
  })

  it('serializes the request through the board mutation queue', async () => {
    // An undo that bypassed the queue would race an in-flight delete, and whichever response
    // landed last would win permanently. `isApplying` cannot catch that: it only guards a second
    // undo. So the request must reach the API *through* submit, never around it.
    const gate = deferred<void>()
    // Counted by hand rather than with `vi.fn`: Vitest's `Mock` type carries a single non-generic
    // call signature, so wrapping a generic function collapses its type parameter to `unknown`.
    let submitCalls = 0
    const submit = async <T,>(work: () => Promise<T>): Promise<T> => {
      submitCalls += 1
      await gate.promise
      return work()
    }
    boardApi.applyBoardEditUndo.mockResolvedValue(result())

    const { api } = mountUndo({ submit, seedHistory: true })
    const pending = api.undo()

    // While the queue holds the slot, nothing may have reached the server.
    expect(submitCalls).toBe(1)
    expect(boardApi.applyBoardEditUndo).not.toHaveBeenCalled()

    gate.resolve()
    await pending

    expect(boardApi.applyBoardEditUndo).toHaveBeenCalledWith('undo', visibleHistory.editHistoryRevision)
  })

  it('announces an applied undo with its receipt', async () => {
    const onApplied = vi.fn()
    const receipt = result()
    boardApi.applyBoardEditUndo.mockResolvedValue(receipt)

    const { api } = mountUndo({ onApplied, seedHistory: true })
    await api.undo()

    expect(onApplied).toHaveBeenCalledExactlyOnceWith(receipt)
  })

  it('stays silent when the request was abandoned rather than failed', async () => {
    // The board unmounting or the auth scope changing rejects a queued request. Reporting that pops
    // an error toast on whatever page the user moved to.
    const abandoned = new Error('board went away')
    const report = vi.fn()
    boardApi.applyBoardEditUndo.mockRejectedValue(abandoned)

    const { api } = mountUndo({
      report,
      seedHistory: true,
      isIgnorableError: error => error === abandoned
    })
    await api.undo()

    expect(report).not.toHaveBeenCalled()
  })

  it('still reports a genuine transport failure', async () => {
    const report = vi.fn()
    const reconcile = vi.fn(async () => false)
    boardApi.applyBoardEditUndo.mockRejectedValue(new Error('offline'))

    const { api } = mountUndo({
      report,
      reconcile,
      seedHistory: true,
      isIgnorableError: () => false
    })
    await api.undo()

    expect(report).toHaveBeenCalledWith('unconfirmed', 'undo', expect.anything(), false)
  })
})
