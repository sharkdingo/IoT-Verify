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
import type { BoardUndoResult } from '@/types/boardEdit'

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

type Harness = ReturnType<typeof useBoardUndo>

const mountUndo = (options: {
  isBlocked?: () => boolean
  seedHistory?: boolean
  applyResult?: (r: BoardUndoResult) => void
  submit?: <T>(work: () => Promise<T>) => Promise<T>
  reconcile?: () => Promise<boolean>
  onApplied?: () => void
  isIgnorableError?: (error: unknown) => boolean
  report?: Harness extends never ? never : any
} = {}) => {
  let api!: Harness
  const wrapper = mount(defineComponent({
    setup() {
      api = useBoardUndo({
        applyResult: options.applyResult ?? (() => undefined),
        submit: options.submit ?? (work => work()),
        reconcile: options.reconcile ?? (async () => true),
        onApplied: options.onApplied,
        isIgnorableError: options.isIgnorableError,
        isBlocked: options.isBlocked ?? (() => false),
        report: options.report ?? (() => undefined)
      })
      return () => h('div')
    }
  }), { attachTo: document.body })
  if (options.seedHistory) api.syncAvailability(visibleHistory)
  return { wrapper, api: api! }
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
    const { api, wrapper } = mountUndo()
    expect(api.canUndo.value).toBe(false)
    expect(api.canRedo.value).toBe(false)
    wrapper.unmount()
  })

  it('mirrors the availability a reversible mutation reported', () => {
    const { api, wrapper } = mountUndo()
    api.syncAvailability({ ...visibleHistory, canRedo: false })
    expect(api.canUndo.value).toBe(true)
    expect(api.canRedo.value).toBe(false)
    wrapper.unmount()
  })

  it('ignores a mutation that does not report availability', () => {
    const { api, wrapper } = mountUndo()
    api.syncAvailability(visibleHistory)
    // A non-reversible mutation omits both; that must not clear real history.
    api.syncAvailability({})
    expect(api.canUndo.value).toBe(true)
    expect(api.canRedo.value).toBe(true)
    wrapper.unmount()
  })

  it('ignores an availability read superseded by an ordinary mutation result', async () => {
    const { api, wrapper } = mountUndo()
    let resolveAvailability!: (value: { canUndo: boolean, canRedo: boolean }) => void
    boardApi.getBoardEditAvailability.mockImplementationOnce(() => new Promise(resolve => {
      resolveAvailability = resolve
    }))

    const staleRead = api.hasHistoryChanged()
    api.syncAvailability({ ...visibleHistory, canRedo: false })
    resolveAvailability({ canUndo: false, canRedo: false })
    expect(await staleRead).toBe(false)

    expect(api.canUndo.value).toBe(true)
    expect(api.canRedo.value).toBe(false)
    wrapper.unmount()
  })

  it('lets the latest availability refresh win when reads complete out of order', async () => {
    const { api, wrapper } = mountUndo()
    let resolveFirst!: (value: { canUndo: boolean, canRedo: boolean }) => void
    let resolveSecond!: (value: { canUndo: boolean, canRedo: boolean }) => void
    boardApi.getBoardEditAvailability
      .mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve }))
      .mockImplementationOnce(() => new Promise(resolve => { resolveSecond = resolve }))

    const first = api.hasHistoryChanged()
    const second = api.hasHistoryChanged()
    resolveSecond({ canUndo: true, canRedo: false })
    expect(await second).toBe(true)
    resolveFirst(emptyHistory)
    expect(await first).toBe(false)

    expect(api.canUndo.value).toBe(false)
    expect(api.canRedo.value).toBe(false)
    wrapper.unmount()
  })

  it('takes availability from the server even when nothing was applied', async () => {
    boardApi.applyBoardEditUndo.mockResolvedValue(
      result({ applied: false, reasonCode: 'NOTHING_TO_APPLY', canUndo: false, canRedo: false }))
    const report = vi.fn()
    const { api, wrapper } = mountUndo({ report })
    api.syncAvailability(visibleHistory)

    await api.undo()

    expect(report).toHaveBeenCalledWith('nothing', 'undo')
    // A stale local guess is corrected rather than kept.
    expect(api.canUndo.value).toBe(false)
    expect(api.canRedo.value).toBe(false)
    wrapper.unmount()
  })
})

describe('applying', () => {
  it('submits the history revision visible when the user requested undo', async () => {
    let release!: () => void
    const queued = new Promise<void>(resolve => { release = resolve })
    const { api, wrapper } = mountUndo({ submit: async work => {
      await queued
      return work()
    } })
    const observed = { canUndo: true, canRedo: false, editHistoryRevision: '12:2:0' }
    api.syncAvailability(observed)
    boardApi.applyBoardEditUndo.mockResolvedValueOnce(result())
    const pending = api.undo()
    api.syncAvailability({ ...observed, editHistoryRevision: '13:3:0' })
    release()
    await pending
    expect(boardApi.applyBoardEditUndo).toHaveBeenCalledWith('undo', observed.editHistoryRevision)
    wrapper.unmount()
  })

  it('hands the authoritative collections to the caller', async () => {
    const applied = result({ rules: [{ id: '4' } as any], canUndo: true })
    boardApi.applyBoardEditUndo.mockResolvedValue(applied)
    const applyResult = vi.fn()
    const { api, wrapper } = mountUndo({ applyResult, seedHistory: true })

    await api.undo()

    expect(boardApi.applyBoardEditUndo).toHaveBeenCalledWith('undo', visibleHistory.editHistoryRevision)
    expect(applyResult).toHaveBeenCalledWith(applied)
    expect(api.canUndo.value).toBe(true)
    wrapper.unmount()
  })

  it('refuses while playback or a scene operation owns the board', async () => {
    const report = vi.fn()
    const { api, wrapper } = mountUndo({ isBlocked: () => true, report })

    await api.undo()

    expect(boardApi.applyBoardEditUndo).not.toHaveBeenCalled()
    expect(report).toHaveBeenCalledWith('blocked', 'undo')
    wrapper.unmount()
  })

  it('reports a version conflict distinctly from a transport failure', async () => {
    const report = vi.fn()
    const { api, wrapper } = mountUndo({ report, seedHistory: true })

    boardApi.applyBoardEditUndo.mockRejectedValueOnce({ response: { status: 409 } })
    await api.undo()
    expect(report.mock.calls[0][0]).toBe('conflict')

    boardApi.applyBoardEditUndo.mockRejectedValueOnce(new Error('offline'))
    await api.undo()
    expect(report.mock.calls[1][0]).toBe('failed')
    wrapper.unmount()
  })

  it('reconciles after a conflict and mirrors the retained journal entry', async () => {
    // A conflict is a rejected write, but it also says the local board may be stale. The server
    // deliberately retains the entry, so availability remains true after reconciliation.
    const report = vi.fn()
    let api!: Harness
    const reconcile = vi.fn(async () => {
      api.syncAvailability({ ...visibleHistory, editHistoryRevision: '11:2:0', canRedo: false })
      return true
    })
    const { api: harness, wrapper } = mountUndo({ report, reconcile, seedHistory: true })
    api = harness

    boardApi.applyBoardEditUndo.mockRejectedValueOnce({ response: { status: 409 } })
    await api.undo()

    expect(reconcile).toHaveBeenCalledTimes(1)
    expect(report).toHaveBeenCalledWith('conflict', 'undo', expect.anything(), true)
    expect(boardApi.getBoardEditAvailability).not.toHaveBeenCalled()
    expect(api.canUndo.value).toBe(true)
    wrapper.unmount()
  })

  it('reconciles an unconfirmed failure before reporting it', async () => {
    const report = vi.fn()
    const reconcile = vi.fn(async () => true)
    const { api, wrapper } = mountUndo({ report, reconcile, seedHistory: true })
    const failure = new Error('response contract rejected')
    boardApi.applyBoardEditUndo.mockRejectedValue(failure)

    await api.redo()

    expect(reconcile).toHaveBeenCalledTimes(1)
    expect(report).toHaveBeenCalledWith('failed', 'redo', failure, true)
    expect(api.canRedo.value).toBe(true)
    wrapper.unmount()
  })

  it('ignores an availability read that a later mutation has already superseded', async () => {
    // `loadAvailability` runs outside the mutation queue, so one started before an undo could
    // otherwise land after it and restore the pre-undo availability.
    const { api, wrapper } = mountUndo({ seedHistory: true })
    let resolveAvailability!: (value: { canUndo: boolean, canRedo: boolean }) => void
    boardApi.getBoardEditAvailability.mockImplementationOnce(() => new Promise(resolve => {
      resolveAvailability = resolve
    }))

    const pending = api.hasHistoryChanged()
    boardApi.applyBoardEditUndo.mockResolvedValueOnce(result({ canUndo: false, canRedo: true }))
    await api.undo()
    expect(api.canUndo.value).toBe(false)

    resolveAvailability({ canUndo: true, canRedo: false })
    expect(await pending).toBe(false)

    expect(api.canUndo.value).toBe(false)
    wrapper.unmount()
  })

  it('invalidates availability reads started before an unconfirmed mutation', async () => {
    const { api, wrapper } = mountUndo({ reconcile: async () => true, seedHistory: true })
    let resolveStale!: (value: { canUndo: boolean, canRedo: boolean }) => void
    boardApi.getBoardEditAvailability
      .mockImplementationOnce(() => new Promise(resolve => { resolveStale = resolve }))
      .mockResolvedValueOnce({ canUndo: false, canRedo: true })

    const staleRead = api.hasHistoryChanged()
    boardApi.applyBoardEditUndo.mockRejectedValueOnce(new Error('response lost'))
    await api.undo()
    expect(api.canRedo.value).toBe(true)

    resolveStale({ canUndo: true, canRedo: false })
    expect(await staleRead).toBe(false)

    expect(api.canUndo.value).toBe(true)
    expect(api.canRedo.value).toBe(true)
    wrapper.unmount()
  })

  it('runs one request at a time so a held shortcut cannot stack undos', async () => {
    let release!: () => void
    boardApi.applyBoardEditUndo.mockImplementation(() => new Promise(resolve => {
      release = () => resolve(result())
    }))
    const { api, wrapper } = mountUndo({ seedHistory: true })

    const first = api.undo()
    await api.undo()
    expect(boardApi.applyBoardEditUndo).toHaveBeenCalledTimes(1)

    release()
    await first
    expect(api.isApplying.value).toBe(false)
    wrapper.unmount()
  })
})

describe('keyboard scope', () => {
  const press = (target: HTMLElement, init: Partial<KeyboardEventInit> & { key: string }) => {
    const event = new KeyboardEvent('keydown', { ...init, bubbles: true, cancelable: true })
    target.dispatchEvent(event)
    return event
  }

  it('does not request an undo for an empty visible history', () => {
    const { wrapper } = mountUndo()
    expect(press(document.body, { key: 'z', ctrlKey: true }).defaultPrevented).toBe(false)
    expect(boardApi.applyBoardEditUndo).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('applies the accelerators from an ordinary board surface', async () => {
    boardApi.applyBoardEditUndo.mockResolvedValue(result())
    const { api, wrapper } = mountUndo({ seedHistory: true })
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
    wrapper.unmount()
  })

  it('leaves Ctrl+Z inside a text field to the field itself', () => {
    const { wrapper } = mountUndo()
    const input = document.createElement('input')
    document.body.append(input)

    const event = press(input, { key: 'z', ctrlKey: true })

    // Not prevented and not dispatched: the browser's own per-field undo must still run.
    expect(event.defaultPrevented).toBe(false)
    expect(boardApi.applyBoardEditUndo).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('recognizes changed history but adopts it only with the Board snapshot', async () => {
    const { api, wrapper } = mountUndo({ seedHistory: true })
    boardApi.getBoardEditAvailability.mockResolvedValue(emptyHistory)
    expect(await api.hasHistoryChanged()).toBe(true)
    expect(api.canUndo.value).toBe(true)

    api.syncAvailability(emptyHistory)
    expect(api.canUndo.value).toBe(false)
    expect(api.canRedo.value).toBe(false)
    expect(await api.hasHistoryChanged()).toBe(false)
    wrapper.unmount()
  })

  it('leaves the affordance untouched when availability cannot be read', async () => {
    const { api, wrapper } = mountUndo()
    api.syncAvailability({ ...visibleHistory, canRedo: false })

    boardApi.getBoardEditAvailability.mockRejectedValue(new Error('offline'))
    await api.hasHistoryChanged()

    // Failing to read must not invent availability in either direction.
    expect(api.canUndo.value).toBe(true)
    wrapper.unmount()
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
    let released: (() => void) | null = null
    const gate = new Promise<void>(resolve => { released = resolve })
    // Counted by hand rather than with `vi.fn`: Vitest's `Mock` type carries a single non-generic
    // call signature, so wrapping a generic function collapses its type parameter to `unknown`.
    let submitCalls = 0
    const submit = async <T,>(work: () => Promise<T>): Promise<T> => {
      submitCalls += 1
      await gate
      return work()
    }
    boardApi.applyBoardEditUndo.mockResolvedValue(result())

    const { api, wrapper } = mountUndo({ submit, seedHistory: true })
    const pending = api.undo()

    // While the queue holds the slot, nothing may have reached the server.
    expect(submitCalls).toBe(1)
    expect(boardApi.applyBoardEditUndo).not.toHaveBeenCalled()

    released!()
    await pending

    expect(boardApi.applyBoardEditUndo).toHaveBeenCalledWith('undo', visibleHistory.editHistoryRevision)
    wrapper.unmount()
  })

  it('runs the applied follow-ups, so recommendations cannot outlive the scene', async () => {
    // Undo is a semantic scene change. The mutation queue's own scene-change hook is deliberately
    // skipped (the commit path owns verification staleness), so this is the only thing that
    // invalidates recommendations built on the pre-undo scene.
    const onApplied = vi.fn()
    boardApi.applyBoardEditUndo.mockResolvedValue(result())

    const { api, wrapper } = mountUndo({ onApplied, seedHistory: true })
    await api.undo()

    expect(onApplied).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('stays silent when the request was abandoned rather than failed', async () => {
    // The board unmounting or the auth scope changing rejects a queued request. Reporting that pops
    // an error toast on whatever page the user moved to.
    const abandoned = new Error('board went away')
    const report = vi.fn()
    boardApi.applyBoardEditUndo.mockRejectedValue(abandoned)

    const { api, wrapper } = mountUndo({
      report,
      seedHistory: true,
      isIgnorableError: error => error === abandoned
    })
    await api.undo()

    expect(report).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('still reports a genuine transport failure', async () => {
    const report = vi.fn()
    const reconcile = vi.fn(async () => false)
    boardApi.applyBoardEditUndo.mockRejectedValue(new Error('offline'))

    const { api, wrapper } = mountUndo({
      report,
      reconcile,
      seedHistory: true,
      isIgnorableError: () => false
    })
    await api.undo()

    expect(report).toHaveBeenCalledWith('failed', 'undo', expect.anything(), false)
    wrapper.unmount()
  })
})
