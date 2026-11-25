import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createBoardSemanticCommit, reconcileBoardFocus, type BoardFocusState } from './semanticCommit'

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
  isCurrentSessionToken: (expected: string) => active.value && expected === 'the original session'
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
    const ports = { ...currentSession(), commitSemanticScene, enqueueBoardMutation: boardQueue() }
    const apply = bind('applyFixOnBoard', ports)
    const fixedRules = allRemoved ? [] : [{ id: 'r1', sources: [], toId: 'device', toApi: 'off' }]
    const receipt = signedReceipt(fixedRules)
    expect(await apply(() => Promise.resolve(receipt))).toBe(receipt)

    expect(rules.value).toEqual(fixedRules)
    expect(edges.value).toEqual(fixedRules.map(rule => rule.id))
    expect(focus.value.ruleId).toBeNull()
    expect(availability.value).toMatchObject({ canUndo: true, canRedo: false })
    expect(stale.value).toBe(true)
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
    const applying = bind('applyFixOnBoard', ports)(startWrite)
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

  it('does not adopt a receipt after the owning Board session is retired', async () => {
    const active = ref(true)
    let release!: (response: ReturnType<typeof signedReceipt>) => void
    const receipt = new Promise<ReturnType<typeof signedReceipt>>(resolve => { release = resolve })
    const commitSemanticScene = vi.fn()
    const ports = { ...currentSession(active), commitSemanticScene, enqueueBoardMutation: boardQueue() }
    const startWrite = vi.fn(() => receipt)
    const applying = bind('applyFixOnBoard', ports)(startWrite)
    await flushPromises()
    expect(startWrite).toHaveBeenCalledOnce()
    active.value = false
    release(signedReceipt([]))
    expect(await applying).toBeNull()
    expect(commitSemanticScene).not.toHaveBeenCalled()
  })

  it('reconciles an uncertain response once and keeps the outcome explicitly unknown', async () => {
    const ports = { ...currentSession(), enqueueBoardMutation: boardQueue(), pendingFixRefreshPromise: null,
      refreshRules: vi.fn().mockResolvedValue(true), reloadUndoAvailability: vi.fn().mockResolvedValue(undefined),
      notifyBlocked: vi.fn(), t: (key: string) => key }
    const reconciliation = bind('handleFixOutcomeUncertain', ports)()
    expect(ports.pendingFixRefreshPromise).not.toBeNull()
    await reconciliation
    expect(ports.refreshRules).toHaveBeenCalledExactlyOnceWith('the original session')
    expect(ports.reloadUndoAvailability).toHaveBeenCalledOnce()
    expect(ports.notifyBlocked).toHaveBeenCalledExactlyOnceWith('app.fixApplyOutcomeUnconfirmedAfterRefresh')
    expect(ports.pendingFixRefreshPromise).toBeNull()
  })

  it.each([7, 8])('places the receipt inline only when the open verdict owns its source trace (open=%i)', openTrace => {
    const ports = { verificationResult: ref({ traces: [{ id: openTrace }] }),
      verificationFixApplied: ref(false), notifySuccess: vi.fn(), t: (key: string) => key }
    bind('handleFixApplied', ports)(7)
    expect(ports.verificationFixApplied.value).toBe(openTrace === 7)
    expect(ports.notifySuccess.mock.calls).toEqual(openTrace === 7 ? [] : [['app.fixAppliedWithSignedEvidence']])
  })
})
