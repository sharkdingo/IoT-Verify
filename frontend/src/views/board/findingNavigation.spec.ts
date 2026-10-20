// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { createSourceFile, isVariableStatement, ScriptKind, ScriptTarget, transpileModule } from 'typescript'
import { flushPromises, mount } from '@vue/test-utils'
import { computed, proxyRefs, ref, watch } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import FuzzingResultDialog from '@/components/FuzzingResultDialog.vue'
import i18n from '@/assets/i18n'
import type { AvailableFuzzingRunSummary } from '@/types/fuzzing'
import type { Specification } from '@/types/spec'
import { createPagedRequestCoordinator } from '@/utils/pagedRequestCoordinator'
import { createLatestBoardRequestGuard, fuzzingFindingBelongsToRun } from '../Board.vue'
import { applyBoardRunTarget, hasUnusableBoardRunParams, isSameBoardRunTarget, parseBoardRunTarget } from './runDeepLink'

const board = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const setup = board.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)![1]
const source = createSourceFile('Board.ts', setup, ScriptTarget.ES2022, true, ScriptKind.TS)
const bind = (name: string, ports: Record<string, any>) => {
  for (const statement of source.statements) {
    if (!isVariableStatement(statement)) continue
    const declaration = statement.declarationList.declarations.find(item => item.name.getText(source) === name)
    if (!declaration) continue
    const javascript = transpileModule(`const ${declaration.getText(source)}`, {
      compilerOptions: { target: ScriptTarget.ES2022 }
    }).outputText
    return new Function('ports', `with (ports) { ${javascript}; return ${name} }`)(ports)
  }
  throw new Error(`Board handler ${name} is missing`)
}

const listener = (component: string, event: string, ports: Record<string, any>) => {
  const start = board.indexOf(`<${component}`)
  const template = board.slice(start, board.indexOf('/>', start))
  const expression = new RegExp(`@${event}="([^"]+)"`).exec(template)?.[1]
  if (!expression) throw new Error(`${component} ${event} listener is missing`)
  const callback = /^[a-zA-Z_$][\w$]*$/.test(expression)
    ? expression : `($event) => (${expression})`
  return new Function('ports', `with (ports) { return ${callback} }`)(proxyRefs(ports))
}

const modelSnapshot = {
  capturedAt: '2026-10-09T10:00:00', deviceCount: 1, ruleCount: 0, specificationCount: 1,
  environmentVariableCount: 0, deviceTemplateCount: 1, modelFingerprint: 'a'.repeat(64), templatesFrozen: true as const
}
const specification: Specification = { id: 'spec-1', templateId: '1', templateLabel: 'Door remains closed',
  aConditions: [], ifConditions: [], thenConditions: [] }
const finding = { id: 11, fuzzTaskId: 10, violatedSpecId: specification.id, violatedSpec: specification,
  firstViolationStep: 1, seed: 17, createdAt: '2026-10-09T10:00:01' }
const run: AvailableFuzzingRunSummary = {
  id: 10, initiator: 'USER', explorationMode: 'BOARD_SNAPSHOT', outcome: 'FOUND_VIOLATION',
  effectiveSeed: 17, iterations: 1, generatedPaths: 1, elapsedMs: 10, modelSnapshot,
  eligibleSpecifications: { [specification.id]: specification }, modelTokenSourcesByDeviceId: {},
  eligibility: { eligibleSpecIds: [specification.id], eligibleSpecLabels: { [specification.id]: 'Door remains closed' },
    ineligibleSpecs: [], requestedSpecCount: 1, eligibleSpecCount: 1 },
  limitations: [], maxIterations: 1, pathLength: 2, populationSize: 1, createdAt: finding.createdAt,
  completedAt: finding.createdAt, findingCount: 1, dataAvailable: true,
  findings: [{ ...finding, stateCount: 2 }]
}
const replay = {
  finding: { ...finding, inputEvents: [], states: [
    { stateIndex: 0, devices: [], triggeredRules: [], compromisedAutomationLinks: [] },
    { stateIndex: 1, devices: [], triggeredRules: [], compromisedAutomationLinks: [] }
  ] },
  modelSnapshot, playbackScene: { nodes: [{ id: 'frozen-door' }], rules: [] }
}

const cleanup: Array<() => void> = []
afterEach(() => cleanup.splice(0).forEach(stop => stop()))

const fixture = async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/board', name: 'board', component: { template: '<div />' } },
    { path: '/account', name: 'account', component: { template: '<div />' } }
  ] })
  await router.push('/board?run=exploration:10&keep=yes')
  const historyDetailRequests = createPagedRequestCoordinator()
  const ports: Record<string, any> = {
    router, get route() { return router.currentRoute.value },
    deepLinkTarget: computed(() => parseBoardRunTarget(router.currentRoute.value.query)),
    appliedDeepLinkTarget: { kind: 'exploration', runId: 10 }, staleDeepLink: ref(false),
    deepLinkLoadRequests: createLatestBoardRequestGuard(), historyDetailRequests,
    applyBoardRunTarget, hasUnusableBoardRunParams, isSameBoardRunTarget, isBoardDataReady: ref(true),
    isOpeningAccount: ref(false), boardLifecycleDisposed: false, boardMutationAdmissionEpoch: 0,
    ensureHistoricalPlaybackUiAdmission: () => true,
    revalidateHistoricalPlaybackAfterLoad: async (token: any) => historyDetailRequests.isCurrent(token),
    fuzzingApi: { getFinding: vi.fn().mockResolvedValue(replay), getRun: vi.fn() },
    fuzzingFindingBelongsToRun, fuzzingResult: ref(run),
    activeFuzzingFinding: ref(null), savedTraces: ref([]),
    closeResultSurfaces: () => historyDetailRequests.invalidate(), closePlaybackForNavigation: vi.fn(), closeHistoryPanel: vi.fn(),
    closeFuzzingResult: vi.fn(), openTraceAnimationAt: vi.fn(), goToState: vi.fn(),
    openFuzzingRun: vi.fn(), notifyBlocked: vi.fn(), notifyError: vi.fn(), t: (key: string) => key
  }
  for (const name of ['isCurrentDeepLinkLoad', 'navigateToRunTarget', 'openRunTarget', 'applyDeepLinkTarget',
    'loadDeepLinkTarget', 'syncBoardToDeepLink', 'reportUnusableDeepLink', 'selectAndPlayFuzzingFinding',
    'openFuzzingFinding', 'openAccount']) {
    ports[name] = bind(name, ports)
  }
  cleanup.push(watch(() => router.currentRoute.value.query, () => {
    if (router.currentRoute.value.name === 'board') void ports.syncBoardToDeepLink()
  }))
  return ports
}

describe('exploration finding navigation', () => {
  it('writes the dialog finding address before replay and preserves it when opening the account page', async () => {
    const state = await fixture()
    const addressesAtRead: string[] = []
    state.fuzzingApi.getFinding.mockImplementation(async () => {
      addressesAtRead.push(state.route.fullPath)
      return replay
    })
    const replayListener = listener('FuzzingResultDialog', 'replay', state)
    let pending: Promise<void> | undefined
    const wrapper = mount(FuzzingResultDialog, {
      props: { visible: true, run, onReplay: (...args) => { pending = replayListener(...args) } },
      global: { plugins: [i18n] }
    })
    cleanup.push(() => wrapper.unmount())
    await wrapper.get('[data-testid="replay-fuzzing-finding-11"]').trigger('click')
    await pending
    await flushPromises()
    expect(state.route.fullPath).toBe('/board?keep=yes&run=exploration:10&finding=11')
    expect(addressesAtRead).toEqual([state.route.fullPath])
    expect(state.fuzzingApi.getFinding).toHaveBeenCalledExactlyOnceWith(11)
    expect(state.fuzzingApi.getRun).not.toHaveBeenCalled()
    expect(state.openFuzzingRun).not.toHaveBeenCalled()
    expect(state.savedTraces.value[0].playbackScene).toEqual(replay.playbackScene)
    expect(state.goToState).toHaveBeenCalledWith(1)
    const returnTo = state.route.fullPath
    state.openAccount()
    await flushPromises()
    expect(state.route.name).toBe('account')
    expect(state.route.query.returnTo).toBe(returnTo)
  })

  it('uses the same finding address from history and restores the parent result on browser Back', async () => {
    const state = await fixture()
    await listener('TraceHistoryPanel', 'view-fuzzing-finding', state)(11, 10)
    await flushPromises()
    expect(state.route.query).toEqual({ keep: 'yes', run: 'exploration:10', finding: '11' })
    expect(state.fuzzingApi.getFinding).toHaveBeenCalledExactlyOnceWith(11)
    state.router.back()
    await flushPromises()
    expect(state.route.query).toEqual({ run: 'exploration:10', keep: 'yes' })
    expect(state.openFuzzingRun).toHaveBeenCalledExactlyOnceWith(10, expect.any(Object))
  })

  it('rejects a finding from another parent before rendering its frozen evidence', async () => {
    const state = await fixture()
    state.fuzzingApi.getFinding.mockResolvedValue({ ...replay, finding: { ...replay.finding, fuzzTaskId: 20 } })
    await state.openFuzzingFinding(11, 10)
    await flushPromises()
    expect(state.route.query).toEqual({ keep: 'yes' })
    expect(state.staleDeepLink.value).toBe(true)
    expect(state.savedTraces.value).toEqual([])
    expect(state.openTraceAnimationAt).not.toHaveBeenCalled()
  })

  it('keeps an older finding response from replacing a newer URL target', async () => {
    const state = await fixture()
    let settle!: (value: typeof replay) => void
    state.fuzzingApi.getFinding.mockReturnValueOnce(new Promise(resolve => { settle = resolve }))
    const earlier = state.openFuzzingFinding(11, 10)
    await flushPromises()
    state.fuzzingApi.getFinding.mockResolvedValue({ ...replay, finding: { ...replay.finding, id: 12 } })
    await state.openFuzzingFinding(12, 10)
    settle(replay)
    await earlier
    expect(state.route.query.finding).toBe('12')
    expect(state.activeFuzzingFinding.value.id).toBe(12)
    expect(state.openTraceAnimationAt).toHaveBeenCalledOnce()
  })
})
