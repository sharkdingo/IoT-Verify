import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { computed, effectScope, nextTick, reactive, ref, watch } from 'vue'
import { createSourceFile, isCallExpression, isExpressionStatement, transpileModule, ScriptTarget } from 'typescript'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createLatestBoardRequestGuard, reconcileBoardNodeSnapshot } from '../Board.vue'
import { compareModelSnapshot } from '@/utils/modelComparison'
import type { ModelRunSnapshot, RunBoardComparison } from '@/types/modelSemantics'
import { errorDetails } from '@/utils/userMessage'

const board = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const start = board.indexOf('const currentBoardModelFingerprints =')
const end = board.indexOf('const fuzzRunHasBoardDrift =', start)
const snapshotStart = board.indexOf('const refreshBoardSnapshot =')
const snapshotEnd = board.indexOf('let boardForegroundRefreshPromise', snapshotStart)
if ([start, end, snapshotStart, snapshotEnd].some(index => index < 0)) {
  throw new Error('Board fingerprint or snapshot refresh state is missing')
}
const setupStart = board.indexOf('<script setup')
const setup = board.slice(board.indexOf('>', setupStart) + 1, board.indexOf('</script>', setupStart))
const source = createSourceFile('Board.ts', setup, ScriptTarget.Latest, true)
// Include every availability watcher so duplicate callers run alongside the snapshot reader.
const availabilityWatches = source.statements.filter(statement => isExpressionStatement(statement)
  && isCallExpression(statement.expression) && statement.expression.expression.getText(source) === 'watch'
  && statement.getText(source).includes('loadBoardUndoAvailability'))
  .map(statement => statement.getText(source)).join('\n')
const createState = new Function('ref', 'computed', 'watch', 'nodes', 'rules', 'specifications',
  'environmentVariables', 'deviceTemplates', 'createLatestBoardRequestGuard', 'boardApi',
  'boardLifecycleDisposed', 'boardAuthScopeEpoch', 'allBoardDataKeys', 'boardDataLoadState',
  'templatesLoading', 'isCurrentBoardAuthScope', 'replaceNodesFromServer', 'reconcileDanglingBoardFocus',
  'syncRuleDerivedEdges', 'loadBoardUndoAvailability', 'hydratedBoardAuthScopeEpoch', 'boardSnapshotError', 'errorDetails',
  'isBoardDataReady',
  transpileModule(`${board.slice(start, end)}
    ${board.slice(snapshotStart, snapshotEnd)}
    ${availabilityWatches}
    return { scope: currentBoardModelFingerprints, loading: loadingCurrentBoardModelFingerprints,
      refresh: refreshCurrentBoardModelFingerprints, invalidate: invalidateCurrentBoardModelFingerprints,
      refreshSnapshot: refreshBoardSnapshot }`, {
    compilerOptions: { target: ScriptTarget.ES2022 }
  }).outputText)

const scopes: ReturnType<typeof effectScope>[] = []
afterEach(() => scopes.splice(0).forEach(scope => scope.stop()))

const fingerprintState = () => {
  const nodes = ref([{ id: 'ac', templateName: 'AirConditioner', label: 'AC',
    width: 160, height: 120, position: { x: 10, y: 10 } }])
  const getModelFingerprints = vi.fn().mockResolvedValue(models('a'))
  const getSnapshot = vi.fn()
  const dataKeys = ['templates', 'nodes', 'environment', 'rules', 'specs']
  const loadState = reactive(Object.fromEntries(dataKeys.map(key => [key, 'loading'])))
  const loadBoardUndoAvailability = vi.fn().mockResolvedValue(undefined)
  const scope = effectScope()
  scopes.push(scope)
  const state = scope.run(() => createState(ref, computed, watch, nodes, ref([]), ref([]),
    ref([]), ref([]), createLatestBoardRequestGuard, { getModelFingerprints, getSnapshot }, false,
    0, dataKeys, loadState, ref(false), (epoch: number) => epoch === 0,
    vi.fn((loaded: typeof nodes.value) => { nodes.value = loaded }), vi.fn(), vi.fn(),
    loadBoardUndoAvailability, 0, ref(null), errorDetails,
    computed(() => dataKeys.every(key => loadState[key] === 'ready'))))!
  return { ...state, nodes, getModelFingerprints, getSnapshot, loadState, loadBoardUndoAvailability }
}

const models = (hex: string) => ({ verification: hex.repeat(64), simulation: hex.repeat(64) })

const comparisonAnchor = board.indexOf('const simulationBoardComparison =')
const reconciliationStart = board.indexOf('\nwatch(', comparisonAnchor)
const reconciliationEnd = board.indexOf('const traceModelSemanticsConsistent =', reconciliationStart)
if (comparisonAnchor < 0 || reconciliationStart < 0 || reconciliationEnd < 0) {
  throw new Error('Board result comparison reconciliation is missing')
}
const reconcileResults = new Function('watch', 'verificationBoardComparison', 'simulationBoardComparison',
  'verificationResultStale', 'simulationResultStale', transpileModule(board.slice(reconciliationStart, reconciliationEnd), {
    compilerOptions: { target: ScriptTarget.ES2022 }
  }).outputText)

const resultStalenessState = () => {
  const verification = ref<RunBoardComparison>('UNCHANGED')
  const simulation = ref<RunBoardComparison>('UNCHANGED')
  const verificationStale = ref(false)
  const simulationStale = ref(false)
  const scope = effectScope()
  scopes.push(scope)
  scope.run(() => reconcileResults(watch, verification, simulation, verificationStale, simulationStale))
  return { verification, simulation, verificationStale, simulationStale }
}

describe('Board current model fingerprints', () => {
  it('reads fingerprints and undo availability once when a snapshot changes the semantic scene', async () => {
    const state = fingerprintState()
    state.getSnapshot.mockResolvedValueOnce({
      deviceTemplates: [], nodes: [{ ...state.nodes.value[0], state: 'on' }],
      environmentVariables: [], rules: [], specifications: []
    })
    expect(await state.refreshSnapshot()).toBe(true)
    await nextTick()
    expect(state.getModelFingerprints).toHaveBeenCalledOnce()
    expect(state.loadBoardUndoAvailability).toHaveBeenCalledOnce()
    expect(state.scope.value).toEqual(models('a'))
  })

  it('updates an already-rendered unknown scope when the current fingerprint arrives', async () => {
    const state = fingerprintState()
    expect(state.scope.value).toBeNull()
    state.getModelFingerprints.mockResolvedValue(models('a'))
    await state.refresh()
    expect(state.scope.value).toEqual(models('a'))
  })

  it.each(['a', 'b'])('withdraws an old matching fingerprint throughout a pending refresh before adopting %s', async nextModel => {
    const state = fingerprintState()
    const snapshot: ModelRunSnapshot = { capturedAt: '2026-10-09T10:00:00', deviceCount: 1,
      ruleCount: 1, specificationCount: 1, environmentVariableCount: 0, deviceTemplateCount: 1,
      templatesFrozen: true, modelFingerprint: 'a'.repeat(64) }
    const comparison = computed(() => compareModelSnapshot(snapshot, state.scope.value, 'verification'))
    await state.refresh()
    expect(comparison.value).toBe('UNCHANGED')

    let resolve!: (value: ReturnType<typeof models>) => void
    state.getModelFingerprints.mockReturnValueOnce(new Promise(done => { resolve = done }))
    const pending = state.refresh()
    expect(state.loading.value).toBe(true)
    expect(comparison.value).toBe('UNAVAILABLE')
    resolve(models(nextModel))
    await pending
    expect(state.loading.value).toBe(false)
    expect(comparison.value).toBe(nextModel === 'a' ? 'UNCHANGED' : 'CHANGED')
  })

  it('never restores an old matching fingerprint after the pending refresh fails', async () => {
    const state = fingerprintState()
    await state.refresh()
    expect(state.scope.value).toEqual(models('a'))
    let reject!: (error: Error) => void
    state.getModelFingerprints.mockReturnValueOnce(new Promise((_resolve, fail) => { reject = fail }))
    const pending = state.refresh()
    expect(state.scope.value).toBeNull()
    expect(state.loading.value).toBe(true)
    reject(new Error('comparison unavailable'))
    await pending
    expect(state.scope.value).toBeNull()
    expect(state.loading.value).toBe(false)
  })

  it.each(['success', 'failure'] as const)('ends loading when invalidation precedes a failed snapshot and the old fingerprint %s settles', async outcome => {
    const state = fingerprintState()
    await state.refresh()
    let resolve!: (value: ReturnType<typeof models>) => void
    let reject!: (error: Error) => void
    state.getModelFingerprints.mockReturnValueOnce(new Promise((done, fail) => {
      resolve = done
      reject = fail
    }))
    const older = state.refresh()
    expect(state.loading.value).toBe(true)
    state.invalidate()
    state.getSnapshot.mockRejectedValueOnce(new Error('snapshot unavailable'))
    const diagnostic = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      expect(await state.refreshSnapshot()).toBe(false)
      expect(Object.values(state.loadState).every(value => value === 'error')).toBe(true)
      if (outcome === 'success') resolve(models('a'))
      else reject(new Error('old comparison unavailable'))
      await older
      expect(state.scope.value).toBeNull()
      expect(state.loading.value).toBe(false)
      expect(state.getModelFingerprints).toHaveBeenCalledTimes(2)
      expect(state.getSnapshot).toHaveBeenCalledOnce()
    } finally {
      diagnostic.mockRestore()
    }
  })

  it.each(['success', 'failure'] as const)('preserves loading for the post-watcher request when an invalidated fingerprint %s settles', async outcome => {
    const state = fingerprintState()
    let resolveOlder!: (value: ReturnType<typeof models>) => void
    let rejectOlder!: (error: Error) => void
    let resolveNewest!: (value: ReturnType<typeof models>) => void
    state.getModelFingerprints.mockReturnValueOnce(new Promise((done, fail) => {
      resolveOlder = done
      rejectOlder = fail
    }))
    state.getModelFingerprints.mockReturnValueOnce(new Promise(done => { resolveNewest = done }))
    const older = state.refresh()
    state.nodes.value = [{ ...state.nodes.value[0], templateName: 'ChangedTemplate' }]
    expect(state.loading.value).toBe(false)
    await nextTick()
    expect(state.loading.value).toBe(true)
    if (outcome === 'success') resolveOlder(models('a'))
    else rejectOlder(new Error('old comparison unavailable'))
    await older
    expect(state.scope.value).toBeNull()
    expect(state.loading.value).toBe(true)
    resolveNewest(models('b'))
    await nextTick()
    expect(state.scope.value).toEqual(models('b'))
    expect(state.loading.value).toBe(false)
    expect(state.getModelFingerprints).toHaveBeenCalledTimes(2)
  })

  it('invalidates a known fingerprint and rejects an in-flight response after a model change', async () => {
    const state = fingerprintState()
    state.getModelFingerprints.mockResolvedValueOnce(models('a'))
    await state.refresh()
    expect(state.scope.value).toEqual(models('a'))
    let resolve!: (value: ReturnType<typeof models>) => void
    state.getModelFingerprints.mockReturnValueOnce(new Promise(done => { resolve = done }))
    const pending = state.refresh()
    state.getModelFingerprints.mockResolvedValueOnce(models('b'))
    state.nodes.value = [{ ...state.nodes.value[0], templateName: 'ChangedTemplate' }]
    expect(state.scope.value).toBeNull()
    await nextTick()
    resolve(models('a'))
    await pending
    expect(state.scope.value).toEqual(models('b'))
  })

  it('keeps the newest response and represents failed refreshes as unknown', async () => {
    const state = fingerprintState()
    let resolve!: (value: ReturnType<typeof models>) => void
    state.getModelFingerprints.mockReturnValueOnce(new Promise(done => { resolve = done }))
    const older = state.refresh()
    expect(state.loading.value).toBe(true)
    state.getModelFingerprints.mockResolvedValueOnce(models('b'))
    await state.refresh()
    expect(state.loading.value).toBe(false)
    resolve(models('a'))
    await older
    expect(state.scope.value).toEqual(models('b'))
    state.getModelFingerprints.mockRejectedValueOnce(new Error('unavailable'))
    await state.refresh()
    expect(state.scope.value).toBeNull()
    expect(state.loading.value).toBe(false)
  })

  it('keeps loading owned by the newest comparison request when an older request settles', async () => {
    const state = fingerprintState()
    let resolveOlder!: (value: ReturnType<typeof models>) => void
    let resolveNewest!: (value: ReturnType<typeof models>) => void
    state.getModelFingerprints.mockReturnValueOnce(new Promise(done => { resolveOlder = done }))
    state.getModelFingerprints.mockReturnValueOnce(new Promise(done => { resolveNewest = done }))
    const older = state.refresh()
    const newest = state.refresh()
    resolveOlder(models('a'))
    await older
    expect(state.scope.value).toBeNull()
    expect(state.loading.value).toBe(true)
    resolveNewest(models('b'))
    await newest
    expect(state.scope.value).toEqual(models('b'))
    expect(state.loading.value).toBe(false)
  })

  it('keeps comparison evidence through geometry and display-name changes', async () => {
    const state = fingerprintState()
    await state.refresh()
    state.nodes.value = [{ ...state.nodes.value[0], width: 240, label: 'Better name', position: { x: 200, y: 300 } }]
    await nextTick()
    expect(state.scope.value).toEqual(models('a'))
    expect(state.getModelFingerprints).toHaveBeenCalledTimes(1)
  })

  it('does not serialize the semantic scene on each resize or display-name update', async () => {
    const state = fingerprintState()
    await state.refresh()
    const serialize = vi.spyOn(JSON, 'stringify')
    try {
      for (let step = 0; step < 20; step++) {
        const node = state.nodes.value[0]
        node.width += 1
        node.height += 1
        node.position.x += 1
        node.position.y += 1
        node.label = `AC ${step}`
      }
      await nextTick()
      expect(serialize).not.toHaveBeenCalled()
      expect(state.scope.value).toEqual(models('a'))
      expect(state.getModelFingerprints).toHaveBeenCalledTimes(1)
    } finally {
      serialize.mockRestore()
    }
  })

  it('serializes the model once when a layout response reconciles 100 unchanged runtime records', async () => {
    const state = fingerprintState()
    const current = Array.from({ length: 100 }, (_, index) => ({
      id: `device-${index}`, templateName: 'AirConditioner', label: `AC ${index}`,
      state: 'Working', width: 160, height: 120, position: { x: index * 200, y: 10 },
      variables: [{ name: 'temperature', value: '20', trust: 'trusted' }],
      privacies: [{ name: 'photo', privacy: 'public' }]
    }))
    state.nodes.value = current
    await nextTick()
    await state.refresh()
    state.getModelFingerprints.mockClear()
    const incoming = structuredClone(current)
    incoming[42]!.position = { x: 200, y: 300 }
    const serialize = vi.spyOn(JSON, 'stringify')
    try {
      state.nodes.value = reconcileBoardNodeSnapshot(state.nodes.value, incoming, new Map(), new Set())
      const serializedModels = serialize.mock.calls.filter(([value]) => value
        && typeof value === 'object' && 'devices' in value && 'deviceTemplates' in value)
      expect(serializedModels).toHaveLength(1)
      expect(state.scope.value).toEqual(models('a'))
      await nextTick()
      expect(state.getModelFingerprints).not.toHaveBeenCalled()
      expect(state.nodes.value).toEqual(incoming)
      serialize.mockClear()
      const acknowledged = state.nodes.value
      state.nodes.value = reconcileBoardNodeSnapshot(acknowledged, structuredClone(incoming), new Map(), new Set())
      expect(state.nodes.value).toBe(acknowledged)
      expect(serialize.mock.calls.filter(([value]) => value
        && typeof value === 'object' && 'devices' in value && 'deviceTemplates' in value)).toHaveLength(0)
      expect(state.scope.value).toEqual(models('a'))
      const runtimeChanged = structuredClone(incoming)
      runtimeChanged[42]!.variables[0]!.value = '25'
      state.getModelFingerprints.mockResolvedValueOnce(models('b'))
      state.nodes.value = reconcileBoardNodeSnapshot(state.nodes.value, runtimeChanged, new Map(), new Set())
      expect(state.scope.value).toBeNull()
      await nextTick()
      expect(state.scope.value).toEqual(models('b'))
      expect(state.getModelFingerprints).toHaveBeenCalledOnce()
    } finally {
      serialize.mockRestore()
    }
  })

  it.each([
    ['id', 'other-device'],
    ['templateName', 'ChangedTemplate'],
    ['state', 'off'],
    ['currentStateTrust', 'untrusted'],
    ['currentStatePrivacy', 'private'],
    ['variables', [{ name: 'temperature', value: '25' }]],
    ['privacies', [{ name: 'photo', privacy: 'private' }]]
  ])('still invalidates comparison evidence immediately when %s changes', async (field, value) => {
    const state = fingerprintState()
    await state.refresh()
    state.getModelFingerprints.mockResolvedValueOnce(models('b'))
    Object.assign(state.nodes.value[0], { [field as string]: value })
    expect(state.scope.value).toBeNull()
    await nextTick()
    expect(state.scope.value).toEqual(models('b'))
    expect(state.getModelFingerprints).toHaveBeenCalledTimes(2)
  })

  it.each(['verification', 'simulation'] as const)('keeps a known %s match when a late run sets its conservative stale flag', async kind => {
    const state = resultStalenessState()
    state[`${kind}Stale`].value = true
    await nextTick()
    expect(state[`${kind}Stale`].value).toBe(false)
  })

  it('preserves a known difference when a result-opening path clears stale flags', async () => {
    const state = resultStalenessState()
    state.verification.value = 'CHANGED'
    state.simulation.value = 'CHANGED'
    await nextTick()
    expect(state.verificationStale.value).toBe(true)
    expect(state.simulationStale.value).toBe(true)
    state.verificationStale.value = false
    state.simulationStale.value = false
    await nextTick()
    expect(state.verificationStale.value).toBe(true)
    expect(state.simulationStale.value).toBe(true)
  })

  it('retains conservative flags while comparison evidence is unavailable or absent', async () => {
    const state = resultStalenessState()
    state.verification.value = 'UNAVAILABLE'
    state.simulation.value = 'NOT_COMPARED'
    state.verificationStale.value = true
    state.simulationStale.value = true
    await nextTick()
    expect(state.verificationStale.value).toBe(true)
    expect(state.simulationStale.value).toBe(true)
  })
})
