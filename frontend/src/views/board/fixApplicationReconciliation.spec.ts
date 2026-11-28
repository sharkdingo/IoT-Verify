import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createBoardSemanticCommit, reconcileBoardFocus, type BoardFocusState } from './semanticCommit'
import { isDefinitiveMutationRejection } from '@/utils/boardWriteReceipt'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const start = board.indexOf('<script setup')
const source = ts.createSourceFile('Board.ts', board.slice(board.indexOf('>', start) + 1,
  board.indexOf('</script>', start)), ts.ScriptTarget.Latest, true)
const bind = (name: string, ports: Record<string, unknown>) => {
  const statement = source.statements.find(node => ts.isVariableStatement(node)
    && node.declarationList.declarations.some(item => item.name.getText(source) === name))!
  const javascript = ts.transpileModule(statement.getText(source), {
    compilerOptions: { target: ts.ScriptTarget.ES2022 }
  }).outputText
  return new Function('ports', `with (ports) { ${javascript}; return ${name} }`)(ports)
}

const signedReceipt = (rules: any[]) => ({ applied: true, verificationEvidenceReused: true,
  rules, canUndo: true, canRedo: false })

const boardQueue = () => {
  let tail = Promise.resolve()
  return <T>(work: () => Promise<T>): Promise<T> => {
    const queued = tail.then(work)
    tail = queued.then(() => undefined, () => undefined)
    return queued
  }
}

const currentSession = (active = ref(true)) => ({
  captureBoardRunOwner: () => () => active.value,
  getToken: () => 'the original session',
  isCurrentSessionToken: (expected: string) => active.value && expected === 'the original session',
  verificationRepairBlocks: ref(new Map()),
  isDefinitiveMutationRejection,
  isPollingAbortedError: () => false
})

describe('automatic fix result reconciliation', () => {
  it.each([false, true])('adopts the authoritative rules and journal without another read (all removed=%s)', async allRemoved => {
    const rules = ref<any[]>([{ id: 'r1' }, { id: 'r2' }])
    const focus = ref<BoardFocusState>({ nodeId: null, ruleId: 'r2', specId: null })
    const availability = ref({ canUndo: false, canRedo: true })
    const stale = ref(false)
    const edges = ref<string[]>([])
    const syncBoardUndoAvailability = vi.fn(value => { availability.value = value })
    const commitSemanticScene = createBoardSemanticCommit({
      setNodes: vi.fn(), setEnvironmentVariables: vi.fn(), setSpecs: vi.fn(),
      setRules: next => { rules.value = next },
      syncRuleDerivedEdges: () => { edges.value = rules.value.map(rule => rule.id) },
      clearDanglingFocus: scene => { focus.value = reconcileBoardFocus(focus.value, scene) },
      syncUndoAvailability: syncBoardUndoAvailability,
      markVerificationResultStale: () => { stale.value = true }
    })
    const ports = { ...currentSession(), commitSemanticScene, enqueueBoardMutation: boardQueue(), refreshBoardSnapshot: vi.fn() }
    const apply = bind('applyFixOnBoard', ports)
    const fixedRules = allRemoved ? [] : [{ id: 'r1', sources: [], toId: 'device', toApi: 'off' }]
    const receipt = signedReceipt(fixedRules)
    expect(await apply(7, () => Promise.resolve(receipt))).toBe(receipt)

    expect(rules.value).toEqual(fixedRules)
    expect(edges.value).toEqual(fixedRules.map(rule => rule.id))
    expect(focus.value.ruleId).toBeNull()
    expect(availability.value).toMatchObject({ canUndo: true, canRedo: false })
    expect(stale.value).toBe(true)
    expect(ports.refreshBoardSnapshot).not.toHaveBeenCalled()
  })

  it('adopts a delayed fix receipt before the newer cross-tab snapshot', async () => {
    let release!: (response: ReturnType<typeof signedReceipt>) => void
    const receipt = new Promise<ReturnType<typeof signedReceipt>>(resolve => { release = resolve })
    const rules = ref(['r1', 'r2'])
    const commit = vi.fn(({ rules: next }) => { rules.value = next })
    const refreshBoardSnapshot = vi.fn(async () => { rules.value = ['r2', 'r3']; return true })
    const ports = {
      ...currentSession(), commitSemanticScene: commit, enqueueBoardMutation: boardQueue(), refreshBoardSnapshot,
      boardLifecycleDisposed: false, document: { visibilityState: 'visible' }, boardForegroundRefreshPromise: null,
      boardRefreshRequestedWhileBusy: false
    }
    const startWrite = vi.fn(() => receipt)
    const applying = bind('applyFixOnBoard', ports)(7, startWrite)
    await flushPromises()
    expect(startWrite).toHaveBeenCalledOnce()
    const refreshing = bind('requestBoardSnapshotRefresh', ports)()
    await flushPromises()
    expect(refreshBoardSnapshot).not.toHaveBeenCalled()
    release(signedReceipt(['r2']))

    await Promise.all([applying, refreshing])
    expect(commit).toHaveBeenCalledExactlyOnceWith({ rules: ['r2'], availability: signedReceipt(['r2']) })
    expect(rules.value).toEqual(['r2', 'r3'])
  })

  it('settles a queued snapshot refresh as cancelled when its Board is left before dispatch', async () => {
    let releaseWrite!: () => void
    const queue = boardQueue()
    const pendingWrite = queue(() => new Promise<void>(resolve => { releaseWrite = resolve }))
    const retired = new Error('Board was left')
    const ports = {
      boardLifecycleDisposed: false, document: { visibilityState: 'visible' },
      boardForegroundRefreshPromise: null, boardRefreshRequestedWhileBusy: false,
      refreshBoardSnapshot: vi.fn().mockResolvedValue(true),
      isPollingAbortedError: (error: unknown) => error === retired,
      enqueueBoardMutation: (work: () => Promise<boolean>) =>
        queue(() => ports.boardLifecycleDisposed ? Promise.reject(retired) : work())
    }
    const refreshing = bind('requestBoardSnapshotRefresh', ports)()
    await flushPromises()
    ports.boardLifecycleDisposed = true
    releaseWrite()

    await pendingWrite
    await expect(refreshing).resolves.toBe(false)
    expect(ports.refreshBoardSnapshot).not.toHaveBeenCalled()
    expect(ports.boardForegroundRefreshPromise).toBeNull()
  })

  it('does not adopt a receipt after the owning Board session is retired', async () => {
    const active = ref(true)
    let release!: (response: ReturnType<typeof signedReceipt>) => void
    const receipt = new Promise<ReturnType<typeof signedReceipt>>(resolve => { release = resolve })
    const commitSemanticScene = vi.fn()
    const ports = { ...currentSession(active), commitSemanticScene, enqueueBoardMutation: boardQueue() }
    const startWrite = vi.fn(() => receipt)
    const applying = bind('applyFixOnBoard', ports)(7, startWrite)
    await flushPromises()
    expect(startWrite).toHaveBeenCalledOnce()
    active.value = false
    release(signedReceipt([]))
    expect(await applying).toBeNull()
    expect(commitSemanticScene).not.toHaveBeenCalled()
  })

  it('reads one recovery snapshot before dispatching an edit queued behind a lost apply response', async () => {
    let dropResponse!: () => void
    const response = new Promise<never>((_, reject) => {
      dropResponse = () => reject(new Error('response lost'))
    })
    let completeRead!: () => void
    const snapshot = new Promise<boolean>(resolve => { completeRead = () => resolve(true) })
    const queue = boardQueue()
    const ports = { ...currentSession(), enqueueBoardMutation: queue, commitSemanticScene: vi.fn(),
      refreshBoardSnapshot: vi.fn(() => snapshot), notifyBlocked: vi.fn(), t: (key: string) => key }
    const write = vi.fn(() => response)
    const apply = bind('applyFixOnBoard', ports)
    const applying = apply(7, write).catch((error: unknown) => error)
    await flushPromises()
    const nextEdit = vi.fn(async () => undefined)
    const following = queue(nextEdit)
    const repeated = apply(7, write)
    dropResponse()
    try {
      await flushPromises()
      expect(ports.refreshBoardSnapshot).toHaveBeenCalledOnce()
      expect(nextEdit).not.toHaveBeenCalled()
      expect(ports.commitSemanticScene).not.toHaveBeenCalled()
    } finally {
      completeRead()
      await Promise.all([applying, following, repeated])
    }
    expect(await applying).toBe('unconfirmed')
    expect(await repeated).toBeNull()
    expect(write).toHaveBeenCalledOnce()
    expect(ports.notifyBlocked).toHaveBeenCalledExactlyOnceWith('app.fixApplyOutcomeUnconfirmedAfterRefresh')
  })

  it('keeps the intended repair blocked when both its response and recovery fail', async () => {
    const ports = { ...currentSession(), enqueueBoardMutation: boardQueue(),
      refreshBoardSnapshot: vi.fn().mockResolvedValue(false),
      notifyBlocked: vi.fn(), t: (key: string) => key }
    const apply = bind('applyFixOnBoard', ports)
    const missing = vi.fn().mockRejectedValue({ response: { status: 503 } })
    expect(await apply(7, missing)).toBe('unconfirmed')
    expect(ports.refreshBoardSnapshot).toHaveBeenCalledOnce()
    expect(ports.notifyBlocked).toHaveBeenCalledExactlyOnceWith('app.fixApplyOutcomeUnknownRefreshFailed')
    expect(ports.verificationRepairBlocks.value.get(7)).toBe('app.fixApplyUnconfirmedReverify')
    expect(await apply(7, missing)).toBeNull()
    expect(missing).toHaveBeenCalledOnce()
  })

  it.each([
    { status: 409, data: { data: { reasonCode: 'MODEL_SEMANTICS_CHANGED' } } },
    { status: 503, data: { data: { reasonCode: 'FIX_APPLY_PREFLIGHT_UNAVAILABLE' } } }
  ])('leaves a proven pre-write rejection to the dialog (HTTP $status)', async response => {
    const ports = { ...currentSession(), enqueueBoardMutation: boardQueue(), refreshBoardSnapshot: vi.fn() }
    const error = { response }
    await expect(bind('applyFixOnBoard', ports)(7, vi.fn().mockRejectedValue(error))).rejects.toBe(error)
    expect(ports.refreshBoardSnapshot).not.toHaveBeenCalled()
  })

  it.each([7, 8])('places the receipt inline only when the open verdict owns its source trace (open=%i)', openTrace => {
    const ports = { verificationResult: ref({ traces: [{ id: openTrace }] }),
      verificationFixApplied: ref(false), notifySuccess: vi.fn(), t: (key: string) => key }
    bind('handleFixApplied', ports)(7)
    expect(ports.verificationFixApplied.value).toBe(openTrace === 7)
    expect(ports.notifySuccess.mock.calls).toEqual(openTrace === 7 ? [] : [['app.fixAppliedWithSignedEvidence']])
  })
})
