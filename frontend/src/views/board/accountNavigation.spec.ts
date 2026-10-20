// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { transpileModule, ScriptTarget } from 'typescript'
import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

const board = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const start = board.indexOf('onBeforeRouteLeave(async to => {')
const end = board.indexOf('/* =================================================================================\n * 2. Constants', start)
if (start < 0 || end < 0) throw new Error('Account navigation guard is missing')
const guardSource = transpileModule(board.slice(start, end), {
  compilerOptions: { target: ScriptTarget.ES2022 }
}).outputText

const fixture = () => {
  let guard!: (to: { name: string }) => Promise<boolean>
  const state = {
    onBeforeRouteLeave: (handler: typeof guard) => { guard = handler },
    isOpeningAccount: { value: false },
    isSceneReplacementInProgress: { value: false },
    getToken: vi.fn(() => 'alice-token'),
    boardAuthScopeEpoch: 4,
    isCurrentBoardAuthScope: vi.fn(() => true),
    isCurrentSessionToken: vi.fn(() => true),
    prepareActiveRecommendationsForLogout: vi.fn().mockResolvedValue('ready'),
    fixResultDialogRef: { value: { prepareForLogout: vi.fn().mockResolvedValue('ready') } },
    notifyBlocked: vi.fn(),
    t: (key: string) => key,
    confirmChoice: vi.fn().mockResolvedValue(false),
    boardMutationQueue: Promise.resolve(),
    waitForPendingBoardMutations: vi.fn().mockResolvedValue(undefined),
    flushPendingBoardLayout: vi.fn().mockResolvedValue(undefined),
    LAYOUT_LOGOUT_FLUSH_TIMEOUT_MS: 1500,
    verificationResult: { value: null as null | { historyPersistence: { status: string } } },
    lastSimulationResult: { value: null as null | { historyPersistence: { status: string } } },
    unsavedRunNotifications: new Set<() => void>(),
    activePlaybackKind: { value: '' },
    currentTrace: { value: null as null | { id?: number } }
  }
  const setQueue = new Function(...Object.keys(state), `${guardSource}\nreturn value => { boardMutationQueue = value }`)
    (...Object.values(state)) as (queue: Promise<void>) => void
  return { state, setQueue, leave: (name = 'account') => guard({ name }) }
}

describe('Board account-page navigation', () => {
  it('leaves unrelated navigation and background tasks outside account settlement', async () => {
    const { state, leave } = fixture()
    await expect(leave('landing')).resolves.toBe(true)
    expect(state.prepareActiveRecommendationsForLogout).not.toHaveBeenCalled()
    expect(state.waitForPendingBoardMutations).not.toHaveBeenCalled()
    expect(state.flushPendingBoardLayout).not.toHaveBeenCalled()
  })

  it('preserves a scene replacement confirmation and rejects a superseded session at admission', async () => {
    const replacement = fixture()
    replacement.state.isSceneReplacementInProgress.value = true
    await expect(replacement.leave()).resolves.toBe(false)
    expect(replacement.state.prepareActiveRecommendationsForLogout).not.toHaveBeenCalled()
    const switched = fixture()
    switched.state.isCurrentSessionToken.mockReturnValue(false)
    await expect(switched.leave()).resolves.toBe(false)
    expect(switched.state.prepareActiveRecommendationsForLogout).not.toHaveBeenCalled()
  })

  it('waits for already admitted semantic and node writes before flushing view layout', async () => {
    const { state, leave } = fixture()
    let settle!: () => void
    state.waitForPendingBoardMutations.mockReturnValue(new Promise<void>(resolve => { settle = resolve }))
    let finished = false
    const navigation = leave().then(result => { finished = true; return result })
    await flushPromises()
    expect(state.isOpeningAccount.value).toBe(true)
    expect(finished).toBe(false)
    expect(state.flushPendingBoardLayout).not.toHaveBeenCalled()
    settle()
    await expect(navigation).resolves.toBe(true)
    expect(state.flushPendingBoardLayout).toHaveBeenCalledWith({ silent: true, timeoutMs: 1500 })
    expect(state.isOpeningAccount.value).toBe(false)
  })

  it('drains a last write appended while the view-layout flush was pending', async () => {
    const { state, leave, setQueue } = fixture()
    let settle!: () => void
    state.flushPendingBoardLayout.mockImplementationOnce(() => new Promise<void>(resolve => { settle = resolve }))
    const navigation = leave()
    await flushPromises()
    setQueue(Promise.resolve())
    settle()
    await expect(navigation).resolves.toBe(true)
    expect(state.waitForPendingBoardMutations).toHaveBeenCalledTimes(2)
  })

  it('keeps account access available after the bounded layout save rejects', async () => {
    const { state, leave } = fixture()
    state.flushPendingBoardLayout.mockRejectedValue(new Error('layout timeout'))
    await expect(leave()).resolves.toBe(true)
    expect(state.isOpeningAccount.value).toBe(false)
  })

  it.each(['verification', 'simulation', 'notification', 'immediate counterexample'])(
    'requires a discard decision for an unconfirmed local %s result', async kind => {
      const { state, leave } = fixture()
      if (kind === 'verification') state.verificationResult.value = { historyPersistence: { status: 'OUTCOME_UNKNOWN' } }
      if (kind === 'simulation') state.lastSimulationResult.value = { historyPersistence: { status: 'NOT_REQUESTED' } }
      if (kind === 'notification') state.unsavedRunNotifications.add(() => undefined)
      if (kind === 'immediate counterexample') {
        state.activePlaybackKind.value = 'counterexample'
        state.currentTrace.value = {}
      }
      await expect(leave()).resolves.toBe(false)
      expect(state.confirmChoice).toHaveBeenCalledWith({
        title: 'account.localResultTitle', message: 'account.localResultMessage',
        confirmText: 'account.leaveWorkspace'
      })
      state.confirmChoice.mockResolvedValue(true)
      await expect(leave()).resolves.toBe(true)
    }
  )

  it('does not confuse a persisted finding replay without a trace id with an unsaved counterexample', async () => {
    const { state, leave } = fixture()
    state.activePlaybackKind.value = 'fuzzing'
    state.currentTrace.value = {}
    state.verificationResult.value = { historyPersistence: { status: 'SAVED' } }
    state.lastSimulationResult.value = { historyPersistence: { status: 'SAVED' } }
    await expect(leave()).resolves.toBe(true)
    expect(state.confirmChoice).not.toHaveBeenCalled()
  })

  it('requires an explicit decision when local interactive work has an unknown stop outcome', async () => {
    const { state, leave } = fixture()
    state.prepareActiveRecommendationsForLogout.mockResolvedValue('outcome-unknown')
    await expect(leave()).resolves.toBe(false)
    expect(state.waitForPendingBoardMutations).not.toHaveBeenCalled()
    expect(state.confirmChoice).toHaveBeenCalledWith({
      title: 'account.workspaceStopUnknownTitle', message: 'account.workspaceStopUnknownMessage',
      confirmText: 'account.openPage'
    })
    state.confirmChoice.mockResolvedValue(true)
    await expect(leave()).resolves.toBe(true)
  })

  it('retains an unknown stop decision when the preparation request itself fails', async () => {
    const { state, leave } = fixture()
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    try {
      state.fixResultDialogRef.value.prepareForLogout.mockRejectedValue(new Error('network failed'))
      await expect(leave()).resolves.toBe(false)
      expect(state.confirmChoice).toHaveBeenCalledWith({
        title: 'account.workspaceStopUnknownTitle', message: 'account.workspaceStopUnknownMessage',
        confirmText: 'account.openPage'
      })
      expect(state.isOpeningAccount.value).toBe(false)
    } finally {
      warning.mockRestore()
    }
  })

  it('waits for a write accepted while the local-result decision was open', async () => {
    const { state, leave, setQueue } = fixture()
    state.unsavedRunNotifications.add(() => undefined)
    let decide!: (value: boolean) => void
    state.confirmChoice.mockReturnValue(new Promise<boolean>(resolve => { decide = resolve }))
    const navigation = leave()
    await flushPromises()
    setQueue(Promise.resolve())
    decide(true)
    await expect(navigation).resolves.toBe(true)
    expect(state.waitForPendingBoardMutations).toHaveBeenCalledTimes(2)
  })

  it('abandons the old account navigation after its session changes during settlement', async () => {
    const { state, leave } = fixture()
    let settle!: (value: string) => void
    state.prepareActiveRecommendationsForLogout.mockReturnValue(new Promise(resolve => { settle = resolve }))
    const navigation = leave()
    await flushPromises()
    state.isCurrentSessionToken.mockReturnValue(false)
    settle('ready')
    await expect(navigation).resolves.toBe(false)
    expect(state.flushPendingBoardLayout).not.toHaveBeenCalled()
    expect(state.isOpeningAccount.value).toBe(false)
  })

  it('does not enter the old account page after its session changes while layout saving waits', async () => {
    const { state, leave } = fixture()
    state.unsavedRunNotifications.add(() => undefined)
    let settle!: () => void
    state.flushPendingBoardLayout.mockReturnValue(new Promise<void>(resolve => { settle = resolve }))
    const navigation = leave()
    await flushPromises()
    expect(state.flushPendingBoardLayout).toHaveBeenCalledOnce()
    state.isCurrentSessionToken.mockReturnValue(false)
    settle()

    await expect(navigation).resolves.toBe(false)
    expect(state.confirmChoice).not.toHaveBeenCalled()
    expect(state.isOpeningAccount.value).toBe(false)
  })
})
