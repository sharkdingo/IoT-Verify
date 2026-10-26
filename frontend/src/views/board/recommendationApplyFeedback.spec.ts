// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { ScriptTarget, transpileModule } from 'typescript'
import { compile } from '@vue/compiler-dom'
import * as Vue from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import FailureDetails from '@/components/FailureDetails.vue'
import { errorDetails } from '@/utils/userMessage'
import { validateDeviceRuntimeConfig } from '@/utils/deviceRuntime'
import { materializeRuleRecommendation, materializeSpecificationRecommendationConditions, RecommendationCandidateError } from '@/utils/recommendationMaterialization'
import { REQUEST_LIMITS } from '@/constants/requestLimits'
import i18n from '@/assets/i18n'

const source = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const extract = (start: string, end: string) => {
  const first = source.indexOf(start)
  const last = source.indexOf(end, first)
  if (first < 0 || last < 0) throw new Error(`Missing production function: ${start}`)
  return source.slice(first, last)
}
const resetSource = extract('const resetRuleRecommendationResults =', 'function closeResultSurfaces()')
const handlers = extract('const createDeviceInstanceAt =', 'const openTemplateInstanceDialog =')
  + extract('const applyRecommendation =', '/* =================================================================================\n * 8.')
  + extract('const applySpecRecommendation =', '// ==== Simulation Logic ====')
  + extract('const recommendationApplyFailures =', 'const reportRecommendationFailure =')
  + extract('const keepAppliedRecommendationAdjustments =', 'const invalidateRecommendationsForSceneChange =')
  + resetSource + extract('const isDefinitiveMutationRejection =', 'type AsyncTaskStatus =')
  + extract('type RecommendationBatchState =', 'const keepAppliedRecommendationAdjustments =')
const rejection = { response: { status: 422, data: { message: 'Value outside domain', data: {
  errors: { 'conditions[0].value': 'Allowed integer range 0..100' }
} } } }
const rule = { name: 'Proposed rule', reason: 'Draft', conditions: [{ deviceId: 'lamp', attribute: 'on', targetType: 'api' }],
  command: { deviceId: 'lamp', action: 'off' } }
const spec = { name: 'Safety requirement', rationale: 'Draft', templateId: '1', aConditions: [{ deviceId: 'lamp', targetType: 'state', key: 'state', relation: '=', value: 'off' }],
  ifConditions: [], thenConditions: [] }
const device = { templateName: 'Light', suggestedLabel: 'Proposed light', reason: 'Draft', intendedUse: 'Lighting' }
type Kind = 'rule' | 'spec' | 'device'
const setup = (error?: unknown, recovered = false) => {
  const owner = { token: 'session-A', active: true, persisted: false }
  const nodes = Vue.ref<any[]>([{ id: 'lamp', templateName: 'Light', label: 'Lamp' }])
  const template = { name: 'Light', manifest: { Name: 'Light', Modes: [], APIs: [], InternalVariables: [] } }
  const mutation = { currentItems: [], currentNodes: nodes.value, affectedItem: { id: 'created-rule' },
    affectedDevices: [{ id: 'created-1', label: device.suggestedLabel }], environmentVariables: [], currentSpecifications: [], environmentChanges: [] }
  const write = async () => { if (error) throw error; return mutation }
  const queue = vi.fn((work: () => Promise<unknown>, _options?: unknown) => work())
  const dependencies: Record<string, any> = {
    ref: Vue.ref, reactive: Vue.reactive, computed: Vue.computed, errorDetails, validateDeviceRuntimeConfig, REQUEST_LIMITS, materializeRuleRecommendation,
    materializeSpecificationRecommendationConditions, RecommendationCandidateError,
    getToken: () => owner.token, isCurrentSessionToken: (token: string) => token === owner.token,
    boardAuthScopeEpoch: 0, isCurrentBoardAuthScope: () => owner.active,
    ensurePlaybackClosedForMutation: () => true, ensureBoardDataReady: () => true, ensureDeviceRuntimeCapacity: () => true,
    ensureBoardItemCapacity: () => true, ensureNestedItemCapacity: () => true, assertRulesHaveTriggers: () => true,
    boardApi: { addRule: vi.fn(write), addSpec: vi.fn(write), addNodes: vi.fn(write) },
    enqueueBoardMutation: queue, commitSemanticScene: vi.fn(),
    focusRuleOnCanvas: vi.fn(), focusCreatedDeviceNode: vi.fn(), nodes, rules: Vue.ref([]), specifications: Vue.ref<any[]>([]),
    isBoardDataReady: Vue.ref(true), isSceneReplacementInProgress: Vue.ref(false),
    isAnyRecommendationRunning: () => false,
    appliedRuleRecommendations: Vue.ref(new Set()), appliedSpecRecommendations: Vue.ref(new Set()), appliedDeviceRecommendations: Vue.ref(new Set()),
    applyingRuleRecommendations: Vue.ref(new Set()), applyingSpecRecommendations: Vue.ref(new Set()), applyingDeviceRecommendations: Vue.ref(new Set()),
    confirmRecommendedRuleSimilarity: vi.fn().mockResolvedValue(true), confirmChoice: vi.fn().mockResolvedValue(true),
    ruleExists: () => owner.persisted, specificationExists: () => false, isSameSpecification: () => owner.persisted,
    refreshRules: vi.fn(async () => { owner.persisted = recovered; return recovered }),
    refreshSpecifications: vi.fn(async () => { owner.persisted = recovered; dependencies.specifications.value = recovered ? [{}] : []; return recovered }),
    refreshDevices: vi.fn(async () => { if (recovered) nodes.value.push({ id: 'created-1', label: device.suggestedLabel }); return recovered }),
    refreshEnvironmentVariables: vi.fn().mockResolvedValue(recovered), reloadUndoAvailability: vi.fn(),
    buildSpecFormula: () => 'G off', buildSpecDeviceRefsFromConditions: () => [], recommendedSpecTemplateLabel: () => 'Never',
    getVisibleDeviceNodes: () => nodes.value, getVisibleCanvasCenterWorld: () => ({ x: 0, y: 0 }),
    getUniqueLabel: (name: string) => name, createDeviceInstanceId: () => 'created-1',
    DEFAULT_NODE_WIDTH: 176, DEFAULT_NODE_HEIGHT: 128, findTemplateByAnyName: () => template,
    t: (key: string) => key, notifyBlocked: vi.fn(), notifyError: vi.fn(), notifySuccess: vi.fn(),
    extractApiErrorMessage: (_error: unknown, fallback: string) => fallback,
    reportEnvironmentChanges: vi.fn(), locale: Vue.ref('zh-CN'), localizedErrorMessage: (_error: unknown, fallback: string) => fallback,
    handleRecommendationApplySceneChange: vi.fn(), invalidateRecommendationsForSceneChange: vi.fn(),
    invalidateRuleRecommendations: vi.fn(), invalidateDeviceRecommendations: vi.fn(),
    invalidateSpecRecommendations: vi.fn(),
    PollingAbortedError: class extends Error {},
    recommendationFailures: { rule: { message: 'A separate generation failure' } }
  }
  for (const match of resetSource.matchAll(/(\w+)\.value/g)) {
    if (match[1] !== 'recommendationBatch' && !(match[1] in dependencies)) dependencies[match[1]] = Vue.ref([])
  }
  const declarations = 'let ruleRecommendationRequestEpoch = 0, specRecommendationRequestEpoch = 0, deviceRecommendationRequestEpoch = 0, recommendationSceneGeneration = 0;\n'
  const create = new Function(...Object.keys(dependencies), transpileModule(declarations + handlers + `\nreturn {
    applyRecommendation, applySpecRecommendation, applyDeviceRecommendation, createDeviceInstanceAt,
    recommendationApplyFailures, resetRuleRecommendationResults, resetDeviceRecommendationResults, resetSpecRecommendationResults,
    preserveAppliedRecommendationAfterSceneChange, sceneGeneration: () => recommendationSceneGeneration,
    applyAllRecommendations, recommendationBatch, isRecommendationBatchLocked, recommendationBatchBlockedReason,
    batch: kind => recommendationBatches[kind] ?? null,
    retire: () => { ruleRecommendationRequestEpoch++; specRecommendationRequestEpoch++; deviceRecommendationRequestEpoch++; },
    changeScene: () => { recommendationSceneGeneration++; }
  }`, { compilerOptions: { target: ScriptTarget.ES2022 } }).outputText)
  return { ...dependencies, ...create(...Object.values(dependencies)), owner, template, queue }
}
const submit = (state: ReturnType<typeof setup>, kind: Kind) => kind === 'rule' ? state.applyRecommendation(rule, 0)
  : kind === 'spec' ? state.applySpecRecommendation(spec, 0) : state.applyDeviceRecommendation(device, 0)
const writeFor = (state: ReturnType<typeof setup>, kind: Kind) => kind === 'rule' ? state.boardApi.addRule
  : kind === 'spec' ? state.boardApi.addSpec : state.boardApi.addNodes
const renderCandidateAction = (state: ReturnType<typeof setup>, kind: Kind, withMessage = false) => {
  const start = source.indexOf(`<div v-if="recommendationApplyFailures.${kind}[index]"`)
  const end = source.indexOf('</button>', start) + '</button>'.length
  if (start < 0 || end < start) throw new Error(`Missing production candidate action: ${kind}`)
  let message = ''
  if (withMessage) {
    const isRecommending = kind === 'rule' ? 'Rules' : kind === 'spec' ? 'Specs' : 'Devices'
    const condition = source.indexOf(`v-if="${kind}RecommendationMessage && !isRecommending${isRecommending} && ${kind}RecommendationIsAppliedConfirmation"`)
    const messageStart = source.lastIndexOf('<p', condition)
    const messageEnd = source.indexOf('</p>', condition) + '</p>'.length
    const accounting = source.indexOf(`data-testid="${kind}-candidate-accounting"`, condition)
    const accountingStart = source.lastIndexOf('<details', accounting)
    const accountingEnd = source.indexOf('</details>', accounting) + '</details>'.length
    if (condition < 0 || messageStart < 0 || accountingStart < 0) throw new Error(`Missing production recommendation message: ${kind}`)
    message = source.slice(messageStart, messageEnd) + source.slice(accountingStart, accountingEnd)
    state[`isRecommending${isRecommending}`] = false
  }
  const render = new Function('Vue', compile(`<section>${message}${source.slice(start, end)}</section>`, { mode: 'function', prefixIdentifiers: true }).code)(Vue)
  return mount({ setup: () => ({ ...state, index: 0, rec: kind === 'rule' ? rule : kind === 'spec' ? spec : device }), render },
    { global: { components: { FailureDetails } } })
}

describe('production recommendation confirmation and candidate retirement', () => {
  for (const kind of ['rule', 'spec', 'device'] as const) {
    it.each([1, 3])(`${kind}: retires the prior candidates and shows only necessary feedback (count=%s)`, count => {
      const state = setup()
      const candidate = kind === 'rule' ? rule : kind === 'spec' ? spec : device
      const appliedIndex = count === 1 ? 0 : 1
      const recommendations = state[`${kind}Recommendations`]
      recommendations.value = count === 1 ? [candidate] : [{ name: 'Earlier draft' }, candidate, { name: 'Later draft' }]
      const applied = recommendations.value[appliedIndex]
      const name = kind === 'rule' ? 'Rule' : kind === 'spec' ? 'Spec' : 'Device'
      state[`applying${name}Recommendations`].value.add(appliedIndex)
      state.preserveAppliedRecommendationAfterSceneChange(kind, appliedIndex)
      expect(recommendations.value).toEqual([applied])
      expect(recommendations.value[0]).toBe(applied)
      expect([...state[`applied${name}Recommendations`].value]).toEqual([0])
      expect(state[`applying${name}Recommendations`].value.size).toBe(0)
      expect(state[`${kind}RecommendationIsAppliedConfirmation`].value).toBe(true)
      expect(state.sceneGeneration()).toBe(1)
      const panels = { rule: state.invalidateRuleRecommendations, device: state.invalidateDeviceRecommendations,
        spec: state.invalidateSpecRecommendations }
      expect(panels[kind]).not.toHaveBeenCalled()
      for (const [panel, close] of Object.entries(panels)) {
        if (panel !== kind) expect(close).toHaveBeenCalledOnce()
      }
      expect(state[`${kind}RecommendationMessage`].value).toBe(count === 1 ? '' : 'app.appliedRecommendationOnlyNotice')
      const wrapper = renderCandidateAction(state, kind, true)
      expect(wrapper.get('button').attributes('disabled')).toBeDefined()
      expect(wrapper.get('button').text()).toContain('app.addedToBoard')
      expect(wrapper.find('.board-surface-danger, .board-surface-warning, .board-surface-info').exists()).toBe(false)
      if (count > 1) expect(wrapper.get('.board-text-muted').text()).toBe('app.appliedRecommendationOnlyNotice')
      wrapper.unmount()
    })

    it(`${kind}: shows candidates alongside their unverified notice`, () => {
      const state = setup()
      state[`${kind}Recommendations`].value = [kind === 'rule' ? rule : kind === 'spec' ? spec : device]
      state[`${kind}RecommendationRequested`].value = true
      state.recommendationFailures = {} as any
      state[`isRecommending${kind === 'rule' ? 'Rules' : kind === 'spec' ? 'Specs' : 'Devices'}`] = false
      const notice = source.indexOf(`data-testid="${kind}-recommendations-unverified"`)
      const start = source.lastIndexOf('<p', notice)
      const list = source.indexOf('<div v-', source.indexOf('<!-- Recommendations List -->', notice))
      const openingEnd = source.indexOf('>', list) + 1
      expect(start).toBeGreaterThan(-1)
      expect(list).toBeGreaterThan(notice)
      // Compile the actual sibling conditions; the sentinel stands for the list contents.
      const markup = source.slice(start, openingEnd) + '<span data-testid="visible-candidates">Candidates</span></div>'
      const render = new Function('Vue', compile(`<section>${markup}</section>`, { mode: 'function', prefixIdentifiers: true }).code)(Vue)
      const wrapper = mount({ setup: () => state, render })
      expect(wrapper.get(`[data-testid="${kind}-recommendations-unverified"]`).text()).toBe('app.recommendationsNotVerified')
      expect(wrapper.get('[data-testid="visible-candidates"]').isVisible()).toBe(true)
      wrapper.unmount()
    })

    it(`${kind}: keeps ordinary generation messages in closed statistics instead of a warning`, () => {
      const state = setup()
      state[`${kind}RecommendationMessage`].value = 'Generation returned no candidates'
      state[`${kind}RecommendationIsAppliedConfirmation`].value = false
      const wrapper = renderCandidateAction(state, kind, true)
      const statistics = wrapper.get<HTMLDetailsElement>(`[data-testid="${kind}-candidate-accounting"]`)
      expect(statistics.element.open).toBe(false)
      expect(source.indexOf(`data-testid="${kind}-candidate-accounting"`))
        .toBeGreaterThan(source.indexOf(`data-testid="${kind}-recommendations-unverified"`))
      expect(statistics.get('p').text()).toBe('Generation returned no candidates')
      expect(wrapper.find('.board-surface-danger, .board-surface-warning, .board-surface-info').exists()).toBe(false)
      wrapper.unmount()
    })
  }
})

describe('one-click recommendation application', () => {
  it.each(['zh-CN', 'en'] as const)('gives each recommendation kind a distinct example in %s', locale => {
    const messages = i18n.global.getLocaleMessage(locale) as any
    const placeholders = ['device', 'rule', 'spec'].map(kind => {
      const key = `${kind}RecommendationPlaceholder`
      expect(source).toContain(`:placeholder="t('app.${key}')"`)
      expect(messages.app[key]).toBeTruthy()
      return messages.app[key]
    })
    expect(new Set(placeholders).size).toBe(3)
  })
  for (const kind of ['rule', 'spec', 'device'] as const) {
    const prepare = () => {
      const state = setup()
      const candidate = kind === 'rule' ? rule : kind === 'spec' ? spec : device
      state[`${kind}Recommendations`].value = [candidate, structuredClone(candidate), structuredClone(candidate)]
      state.handleRecommendationApplySceneChange.mockImplementation((confirmed: boolean, preserve: () => void) => {
        if (confirmed) preserve()
      })
      // Invoke the production ownership callback only when a canonical mutation was committed.
      state.queue.mockImplementation(async (work: () => Promise<unknown>, options: any) => {
        const before = state.commitSemanticScene.mock.calls.length
        const result = await work()
        if (state.commitSemanticScene.mock.calls.length > before) options?.onSemanticChange?.()
        return result
      })
      return state
    }

    it(`${kind}: applies the current batch sequentially and keeps every confirmed card`, async () => {
      const state = prepare()
      expect(state.applyAllRecommendations).toBeTypeOf('function')
      await state.applyAllRecommendations(kind)
      expect(writeFor(state, kind)).toHaveBeenCalledTimes(3)
      if (kind === 'rule') expect(state.focusRuleOnCanvas).not.toHaveBeenCalled()
      if (kind === 'device') {
        const positions = state.boardApi.addNodes.mock.calls.map(([nodes]: any[]) => nodes[0].position)
        expect(positions[1].x - positions[0].x).toBeGreaterThanOrEqual(state.DEFAULT_NODE_WIDTH)
        expect(positions[2].x - positions[1].x).toBeGreaterThanOrEqual(state.DEFAULT_NODE_WIDTH)
      }
      expect(state[`${kind}Recommendations`].value).toHaveLength(3)
      expect(state.batch(kind)).toMatchObject({ kind, running: false, applied: 3, total: 3 })
      await state.applyAllRecommendations(kind)
      expect(writeFor(state, kind)).toHaveBeenCalledTimes(3)
    })

    it.each([rejection, new Error('Disconnected')])(`${kind}: stops on a rejected or unknown second write`, async error => {
      const state = prepare()
      expect(state.applyAllRecommendations).toBeTypeOf('function')
      const write = writeFor(state, kind).getMockImplementation()!
      writeFor(state, kind).mockImplementationOnce(write).mockRejectedValueOnce(error)
      await state.applyAllRecommendations(kind)
      expect(writeFor(state, kind)).toHaveBeenCalledTimes(2)
      expect(state.batch(kind)).toMatchObject({ running: false, applied: 1, total: 3 })
      expect(state.recommendationApplyFailures[kind][1]).toBeDefined()
      await state.applyAllRecommendations(kind)
      expect(writeFor(state, kind)).toHaveBeenCalledTimes(2)
    })

    if (kind === 'rule') it('stops when the user declines a rule similarity confirmation', async () => {
      const state = prepare()
      state.confirmRecommendedRuleSimilarity.mockResolvedValueOnce(true).mockResolvedValueOnce(false)
      await state.applyAllRecommendations(kind)
      expect(writeFor(state, kind)).toHaveBeenCalledOnce()
      expect(state.batch(kind)).toMatchObject({ running: false, applied: 1, total: 3 })
      expect(state.recommendationApplyFailures.rule).toEqual({})
    })

    it(`${kind}: disables repeated submissions and restores availability on regeneration`, async () => {
      const state = prepare()
      let release!: () => void
      const write = writeFor(state, kind).getMockImplementation()!
      writeFor(state, kind).mockImplementationOnce(async () => {
        await new Promise<void>(resolve => { release = resolve })
        return write()
      })
      const start = source.indexOf(`<div v-if="${kind}Recommendations.length > 1"`)
      const end = source.indexOf('<div\n              v-for=', start)
      const render = new Function('Vue', compile(source.slice(start, end), { mode: 'function', prefixIdentifiers: true }).code)(Vue)
      const wrapper = mount({ setup: () => state, render })
      const button = wrapper.get(`[data-testid="apply-all-${kind}-recommendations"]`)
      await button.trigger('click')
      await flushPromises()
      expect(button.attributes('disabled')).toBeDefined()
      expect(button.attributes('aria-busy')).toBe('true')
      await state.applyAllRecommendations(kind)
      expect(writeFor(state, kind)).toHaveBeenCalledOnce()
      release()
      await flushPromises()
      expect(state.batch(kind)).toMatchObject({ running: false, applied: 3 })
      const suffix = kind === 'rule' ? 'Rule' : kind === 'device' ? 'Device' : 'Spec'
      state[`reset${suffix}RecommendationResults`]()
      expect(state.batch(kind)).toBeNull()
      wrapper.unmount()
    })

    it.each(['scene', 'account'])(`${kind}: does not continue after %s changes during a write`, async change => {
      const state = prepare()
      expect(state.applyAllRecommendations).toBeTypeOf('function')
      const write = writeFor(state, kind).getMockImplementation()!
      writeFor(state, kind).mockImplementationOnce(async () => {
        if (change === 'scene') state.changeScene()
        else state.owner.token = 'other-session'
        return write()
      })
      await state.applyAllRecommendations(kind)
      expect(writeFor(state, kind)).toHaveBeenCalledOnce()
    })

    if (kind !== 'device') it(`${kind}: rechecks the session before a queued write starts`, async () => {
      const state = prepare()
      state.queue.mockImplementation(async (work: () => Promise<unknown>) => {
        state.owner.token = 'another-session'
        return work()
      })
      await state.applyAllRecommendations(kind)
      expect(writeFor(state, kind)).not.toHaveBeenCalled()
    })

    if (kind === 'rule') it('does not submit a rule after its confirmation outlives the session', async () => {
      const state = prepare()
      state.confirmRecommendedRuleSimilarity.mockImplementation(async () => {
        state.owner.token = 'another-session'
        return true
      })
      await state.applyAllRecommendations(kind)
      expect(state.queue).not.toHaveBeenCalled()
      expect(writeFor(state, kind)).not.toHaveBeenCalled()
    })

    if (kind === 'device') it('keeps an earlier completed batch locked when a different batch is cancelled', async () => {
      const state = prepare()
      await state.applyAllRecommendations('device')
      state.resetRuleRecommendationResults()
      state.ruleRecommendations.value = [rule, structuredClone(rule)]
      state.confirmRecommendedRuleSimilarity.mockResolvedValue(false)
      await state.applyAllRecommendations('rule')
      expect(state.isRecommendationBatchLocked('device')).toBe(true)
      expect(state.recommendationBatchBlockedReason('device')).toBe('app.recommendationBatchComplete')
      expect(state.recommendationBatchBlockedReason('rule')).toBe('app.recommendationBatchStopped')
    })
  }
})

describe('candidate apply failure ownership in production Board paths', () => {
  for (const kind of ['rule', 'spec', 'device'] as const) {
    it(`${kind}: retains a rejected field beside only its candidate, without a second toast`, async () => {
      const state = setup(rejection)
      const generationFailure = state.recommendationFailures.rule
      await submit(state, kind)
      expect(state.recommendationApplyFailures[kind][0]).toMatchObject(errorDetails(rejection))
      expect(Object.keys(state.recommendationApplyFailures[kind])).toEqual(['0'])
      expect(state.recommendationFailures.rule).toBe(generationFailure)
      expect(state.notifyError).not.toHaveBeenCalled()
      expect(state.commitSemanticScene).not.toHaveBeenCalled()
      const wrapper = renderCandidateAction(state, kind)
      expect(wrapper.get('[data-testid="failure-fields"]').isVisible()).toBe(true)
      expect(wrapper.get('[data-testid="failure-fields"]').text()).toContain('conditions[0].value')
      expect(wrapper.get('[data-testid="failure-fields"]').text()).toContain('Allowed integer range 0..100')
      wrapper.unmount()
    })
    it(`${kind}: keeps an unknown outcome distinct and blocks another dispatch`, async () => {
      const state = setup(new Error('Lost response'))
      await submit(state, kind)
      expect(state.recommendationApplyFailures[kind][0].outcomeUnknown).toBe(true)
      expect(state.reloadUndoAvailability).toHaveBeenCalledOnce()
      expect(state[`applied${kind === 'rule' ? 'Rule' : kind === 'spec' ? 'Spec' : 'Device'}Recommendations`].value.size).toBe(0)
      const wrapper = renderCandidateAction(state, kind)
      expect(wrapper.get('button').attributes('disabled')).toBeDefined()
      await submit(state, kind)
      expect(writeFor(state, kind)).toHaveBeenCalledOnce()
      wrapper.unmount()
    })
    it.each(['epoch', 'scene', 'account'])(`${kind}: does not attach a late rejection after %s changes`, async change => {
      const state = setup()
      let reject!: (error: unknown) => void
      writeFor(state, kind).mockReturnValue(new Promise((_, fail) => { reject = fail }))
      const submitted = submit(state, kind)
      await flushPromises()
      expect(writeFor(state, kind)).toHaveBeenCalledOnce()
      if (change === 'epoch') state.retire()
      if (change === 'scene') state.changeScene()
      if (change === 'account') state.owner.token = 'session-B'
      reject(rejection)
      await submitted
      expect(state.recommendationApplyFailures[kind][0]).toBeUndefined()
      expect(state.notifyError).not.toHaveBeenCalled()
    })
    it(`${kind}: a read-confirmed create remains applied instead of becoming a retryable failure`, async () => {
      const state = setup(new Error('Lost response'), true)
      await submit(state, kind)
      expect(state.recommendationApplyFailures[kind][0]).toBeUndefined()
      expect(state[`applied${kind === 'rule' ? 'Rule' : kind === 'spec' ? 'Spec' : 'Device'}Recommendations`].value.has(0)).toBe(true)
      expect(state.notifyBlocked).toHaveBeenCalledOnce()
    })
    it(`${kind}: a current successful create gives visible state without a redundant receipt`, async () => {
      const state = setup()
      await submit(state, kind)
      expect(state.commitSemanticScene).toHaveBeenCalledOnce()
      if (kind === 'rule') expect(state.focusRuleOnCanvas).toHaveBeenCalledOnce()
      expect(state[`applied${kind === 'rule' ? 'Rule' : kind === 'spec' ? 'Spec' : 'Device'}Recommendations`].value.has(0)).toBe(true)
      expect(state.notifySuccess).not.toHaveBeenCalled()
    })
    it.each(['epoch', 'scene', 'account'])(`${kind}: a late confirmed write keeps only its live account's canonical state after %s changes`, async change => {
      const state = setup()
      let resolve!: (value: unknown) => void
      writeFor(state, kind).mockReturnValue(new Promise(complete => { resolve = complete }))
      const submitted = submit(state, kind)
      await flushPromises()
      expect(writeFor(state, kind)).toHaveBeenCalledOnce()
      if (change === 'epoch') state.retire()
      if (change === 'scene') state.changeScene()
      if (change === 'account') state.owner.token = 'session-B'
      resolve({ currentItems: [], currentNodes: state.nodes.value, affectedItem: { id: 'created-rule' },
        affectedDevices: [{ id: 'created-1', label: device.suggestedLabel }], environmentVariables: [],
        currentSpecifications: [], environmentChanges: [] })
      await submitted
      expect(state.commitSemanticScene).toHaveBeenCalledTimes(change === 'account' ? 0 : 1)
      if (change === 'account') expect(state.focusCreatedDeviceNode).not.toHaveBeenCalled()
      expect(state[`applied${kind === 'rule' ? 'Rule' : kind === 'spec' ? 'Spec' : 'Device'}Recommendations`].value.size).toBe(0)
      expect(state.notifySuccess).not.toHaveBeenCalled()
      expect(state.notifyError).not.toHaveBeenCalled()
    })
    it(`${kind}: resetting the generation clears candidate failures`, async () => {
      const state = setup(rejection)
      await submit(state, kind)
      const reset = kind === 'rule' ? state.resetRuleRecommendationResults : kind === 'spec'
        ? state.resetSpecRecommendationResults : state.resetDeviceRecommendationResults
      reset()
      expect(Object.keys(state.recommendationApplyFailures[kind])).toHaveLength(0)
    })
  }
  it('ordinary device creation still reports its own failure when it has no candidate owner', async () => {
    const state = setup(rejection)
    await expect(state.createDeviceInstanceAt(state.template, { x: 0, y: 0 }, 'New lamp')).rejects.toBe(rejection)
    expect(state.notifyError).toHaveBeenCalledOnce()
  })
})
