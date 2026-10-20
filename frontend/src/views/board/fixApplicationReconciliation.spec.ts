import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { createBoardSemanticCommit, reconcileBoardFocus, type BoardFocusState } from './semanticCommit'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const start = board.indexOf('<script setup')
const source = ts.createSourceFile('Board.ts', board.slice(board.indexOf('>', start) + 1,
  board.indexOf('</script>', start)), ts.ScriptTarget.Latest, true)
const statement = source.statements.find(node => ts.isVariableStatement(node)
  && node.declarationList.declarations.some(item => item.name.getText(source) === 'handleFixApplied'))!
const javascript = ts.transpileModule(statement.getText(source), {
  compilerOptions: { target: ts.ScriptTarget.ES2022 }
}).outputText

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
    const ports: Record<string, any> = {
      verificationResult: ref({}), showResultDialog: ref(true), verificationFixApplied: ref(false),
      boardAuthScopeEpoch: 1, isCurrentBoardAuthScope: vi.fn(() => true), notifySuccess: vi.fn(),
      rules, commitSemanticScene, syncBoardUndoAvailability,
      refreshRules: vi.fn().mockResolvedValue(false), syncRuleDerivedEdges: vi.fn(),
      notifyBlocked: vi.fn(), t: (key: string) => key, pendingFixRefreshPromise: null,
      enqueueBoardMutation: vi.fn((work: () => Promise<boolean>) => Promise.resolve().then(work))
    }
    const apply = new Function('ports', `with (ports) { ${javascript}; return handleFixApplied }`)(ports)
    const fixedRules = allRemoved ? [] : [{ id: 'r1', sources: [], toId: 'device', toApi: 'off' }]
    const result = { rules: fixedRules, canUndo: true, canRedo: false }
    apply(result)
    expect(ports.pendingFixRefreshPromise).not.toBeNull()
    await ports.pendingFixRefreshPromise

    expect(ports.refreshRules).not.toHaveBeenCalled()
    expect(rules.value).toEqual(fixedRules)
    expect(edges.value).toEqual(fixedRules.map(rule => rule.id))
    expect(focus.value.ruleId).toBeNull()
    expect(availability.value).toMatchObject({ canUndo: true, canRedo: false })
    expect(stale.value).toBe(true)
    expect(ports.notifyBlocked).not.toHaveBeenCalled()
    expect(ports.pendingFixRefreshPromise).toBeNull()
    expect(ports.verificationFixApplied.value).toBe(true)
    expect(ports.notifySuccess).not.toHaveBeenCalled()
  })

  it('uses a toast when there is no verification result to carry the apply receipt', async () => {
    const ports = {
      verificationResult: ref(null), showResultDialog: ref(false), verificationFixApplied: ref(false),
      boardAuthScopeEpoch: 1, isCurrentBoardAuthScope: vi.fn(() => true), notifySuccess: vi.fn(),
      commitSemanticScene: vi.fn(), enqueueBoardMutation: (work: () => Promise<boolean>) => work(),
      pendingFixRefreshPromise: null as Promise<boolean> | null, t: (key: string) => key
    }
    const apply = new Function('ports', `with (ports) { ${javascript}; return handleFixApplied }`)(ports)
    apply({ rules: [], canUndo: true, canRedo: false })
    await ports.pendingFixRefreshPromise
    expect(ports.verificationFixApplied.value).toBe(false)
    expect(ports.notifySuccess).toHaveBeenCalledWith('app.fixAppliedWithSignedEvidence')
  })
})
