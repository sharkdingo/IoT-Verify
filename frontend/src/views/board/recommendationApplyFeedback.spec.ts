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

const source = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const extract = (start: string, end: string) => {
  const first = source.indexOf(start)
  const last = source.indexOf(end, first)
  if (first < 0 || last < 0) throw new Error(`Missing production function: ${start}`)
  return source.slice(first, last)
}
const resetSource = extract('const resetRuleRecommendationResults =', 'const resetScenarioRecommendationResults =')
const handlers = extract('const createDeviceInstanceAt =', 'const openTemplateInstanceDialog =')
  + extract('const applyRecommendation =', '/* =================================================================================\n * 8.')
  + extract('const applySpecRecommendation =', '// ==== Simulation Logic ====')
  + extract('const recommendationApplyFailures =', 'const reportRecommendationFailure =')
  + extract('const keepAppliedRecommendationAdjustments =', 'const invalidateRecommendationsForSceneChange =')
  + resetSource + extract('const isDefinitiveMutationRejection =', 'type AsyncTaskStatus =')
const rejection = { response: { status: 422, data: { message: 'Value outside domain', data: {
  errors: { 'conditions[0].value': 'Allowed integer range 0..100' }
} } } }
const rule = { name: 'Proposed rule', reason: 'Draft', conditions: [{ deviceId: 'lamp', attribute: 'on', targetType: 'api' }],
  command: { deviceId: 'lamp', action: 'off' } }
const spec = { rationale: 'Draft', templateId: '1', aConditions: [{ deviceId: 'lamp', targetType: 'state', key: 'state', relation: '=', value: 'off' }],
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
  const dependencies: Record<string, any> = {
    ref: Vue.ref, reactive: Vue.reactive, errorDetails, validateDeviceRuntimeConfig, REQUEST_LIMITS, materializeRuleRecommendation,
    materializeSpecificationRecommendationConditions, RecommendationCandidateError,
    getToken: () => owner.token, isCurrentSessionToken: (token: string) => token === owner.token,
    boardAuthScopeEpoch: 0, isCurrentBoardAuthScope: () => owner.active,
    ensurePlaybackClosedForMutation: () => true, ensureBoardDataReady: () => true, ensureDeviceRuntimeCapacity: () => true,
    ensureBoardItemCapacity: () => true, ensureNestedItemCapacity: () => true, assertRulesHaveTriggers: () => true,
    boardApi: { addRule: vi.fn(write), addSpec: vi.fn(write), addNodes: vi.fn(write) },
    enqueueBoardMutation: (work: () => Promise<unknown>) => work(), commitSemanticScene: vi.fn(),
    focusRuleOnCanvas: vi.fn(), focusCreatedDeviceNode: vi.fn(), nodes, rules: Vue.ref([]), specifications: Vue.ref<any[]>([]),
    isBoardDataReady: Vue.ref(true), isSceneReplacementInProgress: Vue.ref(false),
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
    closeRecommendationPanel: vi.fn(), closeDeviceRecommendationPanel: vi.fn(),
    closeSpecRecommendationPanel: vi.fn(), closeScenarioRecommendationPanel: vi.fn(),
    PollingAbortedError: class extends Error {},
    recommendationFailures: { rule: { message: 'A separate generation failure' } }
  }
  for (const match of resetSource.matchAll(/(\w+)\.value/g)) {
    if (!(match[1] in dependencies)) dependencies[match[1]] = Vue.ref([])
  }
  const declarations = 'let ruleRecommendationRequestEpoch = 0, specRecommendationRequestEpoch = 0, deviceRecommendationRequestEpoch = 0, recommendationSceneGeneration = 0;\n'
  const create = new Function(...Object.keys(dependencies), transpileModule(declarations + handlers + `\nreturn {
    applyRecommendation, applySpecRecommendation, applyDeviceRecommendation, createDeviceInstanceAt,
    recommendationApplyFailures, resetRuleRecommendationResults, resetDeviceRecommendationResults, resetSpecRecommendationResults,
    preserveAppliedRecommendationAfterSceneChange, sceneGeneration: () => recommendationSceneGeneration,
    retire: () => { ruleRecommendationRequestEpoch++; specRecommendationRequestEpoch++; deviceRecommendationRequestEpoch++; },
    changeScene: () => { recommendationSceneGeneration++; }
  }`, { compilerOptions: { target: ScriptTarget.ES2022 } }).outputText)
  return { ...dependencies, ...create(...Object.values(dependencies)), owner, template }
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
      const panels = { rule: state.closeRecommendationPanel, device: state.closeDeviceRecommendationPanel,
        spec: state.closeSpecRecommendationPanel, scenario: state.closeScenarioRecommendationPanel }
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
