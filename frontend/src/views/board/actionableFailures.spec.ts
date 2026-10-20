import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { computed, h, reactive, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { baseParse, type ElementNode } from '@vue/compiler-dom'
import { describe, expect, it, vi } from 'vitest'
import FailureDetails from '@/components/FailureDetails.vue'
import ScenarioObjectiveIssues from '@/components/ScenarioObjectiveIssues.vue'
import { errorDetails, technicalErrorLines, localizedErrorMessage } from '@/utils/userMessage'
import { diagnoseJsonParseError } from '@/utils/jsonParseDiagnostics'
import i18n from '@/assets/i18n'
import { buildScenarioRecommendationTargetRequest } from '../Board.vue'
import { createSceneCodec } from './portableScene'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const setupStart = board.indexOf('<script setup')
const source = ts.createSourceFile('Board.ts', board.slice(board.indexOf('>', setupStart) + 1,
  board.indexOf('</script>', setupStart)), ts.ScriptTarget.Latest, true)
const bind = (name: string, ports: Record<string, unknown>) => {
  const declaration = source.statements.find(statement => ts.isVariableStatement(statement)
    && statement.declarationList.declarations.some(item => item.name.getText(source) === name))
  if (!declaration) throw new Error(`Missing Board function ${name}`)
  const javascript = ts.transpileModule(declaration.getText(source), {
    compilerOptions: { target: ts.ScriptTarget.ES2022 }
  }).outputText
  return new Function('ports', `with (ports) { ${javascript}; return ${name} }`)(ports)
}
const t = (key: string, params?: Record<string, unknown>) => i18n.global.t(key, params || {}, { locale: 'zh-CN' })
const rejection = (reasonCode?: string) => ({ response: { status: 409, data: {
  message: 'The board cannot contain identical rules.', data: { reasonCode }
} } })

describe('scene rejection diagnostics', () => {
  it.each([
    ['DUPLICATE_RULE', '完全相同的规则', '触发条件'],
    ['DUPLICATE_SPECIFICATION', '完全相同的规约', '检查条件'],
    [undefined, '当前数据冲突', '具体冲突原因']
  ])('uses the actual conflict reason %s without suggesting a template change', async (code, message, hint) => {
    const acknowledgeFailure = vi.fn()
    const acknowledge = vi.fn()
    await bind('showSceneImportError', { acknowledgeFailure, acknowledge, h, t, errorDetails })(rejection(code))
    const shown = acknowledgeFailure.mock.calls[0]?.[0] || acknowledge.mock.calls[0]?.[0]
    const summary = typeof shown?.message === 'string' ? shown.message : shown?.message?.children?.[0]?.children
    expect(summary).toContain(message)
    expect(shown.suggestion).toContain(hint)
    expect(shown.details).toContain('The board cannot contain identical rules.')
    expect(acknowledge).not.toHaveBeenCalled()
  })

  it('keeps JSON parser location and cause in a persistent failure without submitting', async () => {
    const acknowledgeFailure = vi.fn()
    const importScene = vi.fn()
    const input = { files: [{ size: 13, text: async () => '{\n  "a" 1\n}' }], value: 'bad.json' }
    await bind('handleSceneImportFile', { isSceneReplacementInProgress: ref(false),
      sceneImportSelectionVersion: 0, boardAuthScopeEpoch: 0, getToken: () => 'scene-owner',
      isCurrentSessionToken: () => true, isCurrentBoardAuthScope: () => true,
      MAX_SCENE_IMPORT_BYTES: 64 * 1024 * 1024, t, acknowledgeFailure, errorDetails,
      diagnoseJsonParseError, importScene, notifyError: vi.fn() })({ target: input })
    expect(acknowledgeFailure).toHaveBeenCalledOnce()
    const shown = acknowledgeFailure.mock.calls[0]![0]
    expect(shown.message).toContain('第 2 行')
    expect(shown.details[0]).toMatch(/JSON/)
    expect(shown.cause).toBe(shown.details[0])
    expect(shown.suggestion).toContain('引号、逗号和括号')
    expect(importScene).not.toHaveBeenCalled()
    expect(input.value).toBe('')
  })

  it('preserves a field-specific local validation reason without submitting', async () => {
    const acknowledgeFailure = vi.fn()
    const importScene = vi.fn()
    const input = { files: [{ size: 2, text: async () => '{}' }], value: 'bad.json' }
    await bind('handleSceneImportFile', { isSceneReplacementInProgress: ref(false),
      sceneImportSelectionVersion: 0, boardAuthScopeEpoch: 0, getToken: () => 'scene-owner',
      isCurrentSessionToken: () => true, isCurrentBoardAuthScope: () => true,
      MAX_SCENE_IMPORT_BYTES: 64 * 1024 * 1024, t, acknowledgeFailure, errorDetails,
      localizedErrorMessage, locale: ref('zh-CN'), importScene, notifyError: vi.fn(),
      normalizeSceneFile: () => { throw new Error('rules[1].toId 引用了不存在的设备：missing') }
    })({ target: input })
    expect(acknowledgeFailure).toHaveBeenCalledOnce()
    expect(acknowledgeFailure.mock.calls[0]![0].message).toContain('rules[1].toId')
    expect(acknowledgeFailure.mock.calls[0]![0].suggestion).toContain('设备引用')
    expect(importScene).not.toHaveBeenCalled()
  })
})

describe('scene file selection ownership', () => {
  const setup = () => {
    const ports = {
      sceneImportSelectionVersion: 0, boardAuthScopeEpoch: 0,
      getToken: () => 'scene-owner', isCurrentSessionToken: () => true,
      isCurrentBoardAuthScope: () => true, isSceneReplacementInProgress: ref(false),
      MAX_SCENE_IMPORT_BYTES: 64 * 1024 * 1024, t, acknowledgeFailure: vi.fn(), errorDetails,
      diagnoseJsonParseError, localizedErrorMessage, locale: ref('zh-CN'),
      importScene: vi.fn(), normalizeSceneFile: (raw: unknown) => raw,
      notifyBlocked: vi.fn(), notifyError: vi.fn()
    }
    return { ports, select: bind('handleSceneImportFile', ports) }
  }

  it.each(['valid', 'syntax', 'read'])('ignores the old %s file after a newer selection finishes', async outcome => {
    const { ports, select } = setup()
    let resolve!: (value: string) => void
    let reject!: (error: Error) => void
    const read = new Promise<string>((done, fail) => { resolve = done; reject = fail })
    const input = { files: [{ size: 2, text: () => read }], value: 'old.json' }
    const oldSelection = select({ target: input })
    input.files = [{ size: 2, text: async () => '{"name":"new"}' }]
    input.value = 'new.json'
    await select({ target: input })
    input.value = 'next-selection.json'
    if (outcome === 'read') reject(new Error('old file unavailable'))
    else resolve(outcome === 'syntax' ? '{' : '{"name":"old"}')
    await oldSelection
    expect(ports.importScene).toHaveBeenCalledOnce()
    expect(ports.importScene.mock.calls[0]![0]).toEqual({ name: 'new' })
    expect(ports.acknowledgeFailure).not.toHaveBeenCalled()
    expect(input.value).toBe('next-selection.json')
  })

  it('retires an old read even when the new file is too large', async () => {
    const { ports, select } = setup()
    let resolve!: (value: string) => void
    const read = new Promise<string>(done => { resolve = done })
    const input = { files: [{ size: 2, text: () => read }], value: 'old.json' }
    const oldSelection = select({ target: input })
    input.files = [{ size: ports.MAX_SCENE_IMPORT_BYTES + 1, text: async () => '{}' }]
    await select({ target: input })
    resolve('{"name":"old"}')
    await oldSelection
    expect(ports.notifyError).toHaveBeenCalledOnce()
    expect(ports.importScene).not.toHaveBeenCalled()
    expect(ports.acknowledgeFailure).not.toHaveBeenCalled()
  })

  it.each(['session', 'view'])('ignores a file read after its %s owner changes', async owner => {
    const { ports, select } = setup()
    let resolve!: (value: string) => void
    const input = { files: [{ size: 2, text: () => new Promise<string>(done => { resolve = done }) }], value: 'old.json' }
    const pending = select({ target: input })
    if (owner === 'session') ports.isCurrentSessionToken = () => false
    else ports.isCurrentBoardAuthScope = () => false
    resolve('{"name":"old"}')
    await pending
    expect(ports.importScene).not.toHaveBeenCalled()
    expect(ports.acknowledgeFailure).not.toHaveBeenCalled()
  })

  it('passes the same file owner to the scene replacement admission checks', async () => {
    const { ports, select } = setup()
    await select({ target: { files: [{ size: 2, text: async () => '{}' }], value: 'current.json' } })
    const guard = ports.importScene.mock.calls[0]![1] as (() => boolean) | undefined
    expect(guard).toBeTypeOf('function')
    expect(guard?.()).toBe(true)
    ports.sceneImportSelectionVersion++
    expect(guard?.()).toBe(false)
  })

  it('does not report a preview failure after the import loses admission', async () => {
    let admitted = true
    let reject!: (error: Error) => void
    const preview = new Promise((_resolve, fail) => { reject = fail })
    const notifyError = vi.fn()
    const confirmDestructive = vi.fn()
    const pending = bind('importScene', {
      ensurePlaybackClosedForMutation: () => true, isSceneReplacementInProgress: ref(false),
      hasAssistantWork: ref(false), isImportingScene: ref(false),
      waitForPendingBoardMutations: async () => {}, ensureBoardDataReady: () => true,
      boardApi: { previewBoardReplacement: () => preview }, notifyError,
      confirmDestructive, localizedErrorMessage, t, locale: ref('zh-CN'),
      BoardMutationAdmissionCancelledError: class extends Error {}
    })({}, () => admitted)
    await Promise.resolve()
    admitted = false
    reject(new Error('old preview failed'))
    expect(await pending).toBe(false)
    expect(notifyError).not.toHaveBeenCalled()
    expect(confirmDestructive).not.toHaveBeenCalled()
  })
})

describe('persistent failure presentation', () => {
  const templateStart = board.indexOf('<template>')
  const template = baseParse(board.slice(templateStart + '<template>'.length, board.lastIndexOf('</template>')))
  const find = (prefix: string): ElementNode => {
    const visit = (node: any): ElementNode | undefined => {
      if (node.type === 1 && node.loc.source.startsWith(prefix)) return node
      for (const child of node.children || []) {
        const result = visit(child)
        if (result) return result
      }
    }
    const result = visit(template)
    if (!result) throw new Error(`Missing rendered failure surface: ${prefix}`)
    return result
  }
  it.each(['zh-CN', 'en'] as const)('distinguishes a failed verification request from an inconclusive result in %s', async locale => {
    const translate = (key: string) => i18n.global.t(key, {}, { locale })
    const failure = ref('Run dependency unavailable')
    const status = bind('verificationResultStatus', {
      computed, verificationError: failure, verificationResult: ref(null), t: translate,
      getVerificationOutcome: () => 'INCONCLUSIVE',
      runFailureTitle: () => translate('app.verificationFailed'),
      verificationVerdictTone: bind('verificationVerdictTone', {})
    })
    const wrapper = mount({ template: find('<div data-testid="verification-result-header"').loc.source,
      setup: () => ({ verificationError: failure, verificationResultStatus: status, t: translate,
        dismissResultDialog: vi.fn() }) },
      { global: { stubs: { HintTooltip: { props: ['content'], template: '<slot />' } } } })
    try {
      expect(wrapper.get('h3').text()).toBe(translate('app.verificationFailed'))
      expect(wrapper.find('.iot-dialog__subtitle').exists()).toBe(false)
      expect(status.value.dialogToneClass).toBe('iot-dialog--danger')
      failure.value = ''
      await wrapper.vm.$nextTick()
      expect(wrapper.get('h3').text()).toBe(translate('app.verificationResult'))
      expect(wrapper.get('.iot-dialog__subtitle').text()).toBe(translate('app.verificationInconclusiveDetail'))
      expect(status.value.dialogToneClass).toBe('iot-dialog--warning')
    } finally { wrapper.unmount() }
  })
  it.each([
    ['rule', 'Rules', 'rule'], ['device', 'Devices', 'device'], ['spec', 'Specs', 'spec'],
    ['scenario', 'Scenario', 'scenario']
  ])('distinguishes a %s request failure from an empty successful response', (kind, suffix, prefix) => {
    const failures: Record<string, unknown> = {}
    bind('reportRecommendationFailure', { recommendationFailures: failures, errorDetails, t,
      extractRecommendationErrorMessage: (error: unknown, fallback: string) => localizedErrorMessage(error, fallback, 'zh-CN')
    })(kind, { response: { status: 503, data: { message: 'AI endpoint unavailable' } } }, '获取推荐失败')
    const state: Record<string, unknown> = { recommendationFailures: failures, t,
      [`isRecommending${suffix}`]: false, [`${prefix}RecommendationRequested`]: true,
      [`${prefix}Recommendations`]: [], recommendedScenarioScene: null, scenarioRecommendationResult: null }
    const failure = find(`<div v-if="recommendationFailures.${kind}"`).loc.source
    const empty = find(`<div v-if="!isRecommending${suffix} && ${prefix}RecommendationRequested`).loc.source
    const wrapper = mount({ template: `${failure}${empty}`, components: { FailureDetails, ScenarioObjectiveIssues }, setup: () => state })
    expect(wrapper.find(`[data-testid="${kind}-recommendation-error"]`).text()).toContain('获取推荐失败')
    expect(wrapper.text()).toContain('AI endpoint unavailable')
    expect(wrapper.text()).toContain(t('app.recommendationFailureNextStep'))
    expect(wrapper.get('[data-testid="failure-cause"]').text()).toBe('AI endpoint unavailable')
    expect(wrapper.text()).not.toContain(t('app.noRecommendationsAvailable'))
    wrapper.unmount()
  })

  it('keeps an unknown request distinct while recovery still owns the request', () => {
    const failures: Record<string, unknown> = {}
    bind('reportRecommendationFailure', { recommendationFailures: failures, errorDetails, t })(
      'rule', new Error('network disconnected'), '', true)
    const failure = failures.rule as any
    expect(failure.message).toContain(t('app.recommendationResponseLostRecovering'))
    expect(failure.suggestion).toContain('等待确认')
    expect(failure.details).toContain('network disconnected')
  })

  it('explains invalid counts beside their inputs before submission, and clears on correction', async () => {
    const filters = reactive({ minDevices: 7, maxDevices: 6, minRules: 1, maxRules: 3,
      minSpecs: 1, maxSpecs: 5, userRequirement: '' })
    const input = bind('scenarioRecommendationInput', { computed, scenarioRecommendationFilters: filters,
      t, locale: ref('zh-CN'), buildScenarioRecommendationTargetRequest,
      requireIntegerInRange: createSceneCodec(t).requireIntegerInRange })
    const submit = vi.fn()
    const counts = find('<div class="board-card grid grid-cols-1 gap-2').loc.source
    const error = find('<p v-if="scenarioRecommendationInput.error"').loc.source
    const button = find('<button\n          type="button"\n          data-testid="generate-scenario-recommendation"').loc.source
    const wrapper = mount({ template: `${counts}${error}${button}`, setup: () => ({ t,
      scenarioRecommendationInput: input, scenarioRecommendationFilters: filters,
      isRecommendingScenario: false, isSceneReplacementInProgress: false,
      isRecommendationRunningForAnother: () => false, fetchScenarioRecommendation: submit }) })
    const generate = wrapper.get('[data-testid="generate-scenario-recommendation"]')
    expect(wrapper.get('[data-testid="scenario-count-error"]').text()).toContain(t('app.devicesTool'))
    expect(wrapper.text()).not.toContain('AI 服务')
    expect(generate.attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="scenario-min-devices"]').attributes('aria-invalid')).toBe('true')
    expect(wrapper.get('[data-testid="scenario-max-devices"]').attributes('aria-describedby')).toBe('scenario-count-error')
    expect(wrapper.get('[data-testid="scenario-min-rules"]').attributes('aria-invalid')).toBeUndefined()
    await generate.trigger('click')
    expect(submit).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="scenario-min-devices"]').setValue('3')
    expect(wrapper.find('[data-testid="scenario-count-error"]').exists()).toBe(false)
    expect(generate.attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-testid="scenario-min-devices"]').attributes('aria-invalid')).toBeUndefined()
    await generate.trigger('click')
    expect(submit).toHaveBeenCalledOnce()
    await wrapper.get('[data-testid="scenario-max-specs"]').setValue('1.5')
    expect(wrapper.get('[data-testid="scenario-count-error"]').text()).toContain(t('app.maxSpecsField'))
    expect(wrapper.get('[data-testid="scenario-max-specs"]').attributes('aria-invalid')).toBe('true')
    expect(generate.attributes('disabled')).toBeDefined()
    expect(input.value.request).toBeNull()
    wrapper.unmount()
  })

  it('rejects invalid criteria before taking request ownership even if submission is invoked directly', async () => {
    const captureOwner = vi.fn()
    const recommend = vi.fn()
    await bind('fetchScenarioRecommendation', {
      isRecommendationRequestActive: () => false, isRecommendingScenario: ref(false),
      scenarioRecommendationRequestId: ref(null), isSceneReplacementInProgress: ref(false),
      ensureBoardDataReady: () => true, showScenarioRecommendationPanel: ref(true),
      canOpenRecommendationPanel: () => true, scenarioRecommendationInput: ref({ request: null }),
      captureRecommendationRequestOwner: captureOwner, boardApi: { recommendScenario: recommend }
    })()
    expect(captureOwner).not.toHaveBeenCalled()
    expect(recommend).not.toHaveBeenCalled()
  })

  it.each([true, false])('updates an uncertain card only for its current request owner (%s)', current => {
    const failure = { outcomeUnknown: true, message: '正在确认', suggestion: '等待', details: ['network disconnected'] }
    const request = ref(current ? 'request-1' : 'request-2')
    const running = vi.fn()
    const recoveries = new Map([['rule', { requestId: 'request-1', requestIdRef: request,
      abortControllerRef: ref(null), controller: null, setRunning: running }]])
    bind('releaseRecommendationTracking', {
      recommendationStopRecoveries: recoveries, recommendationFailures: { rule: failure }, t,
      recommendationRequestOwners: new Map(), recommendationOutcomeUnknownWarnings: new Set(),
      recommendationProgressRequestId: ref(null),
      requestIdAfterTerminalSettlement: (owned: string, finished: string) => owned === finished ? null : owned,
      recordRecommendationTerminalEvidence: vi.fn()
    })('rule', 'request-1')
    expect(failure.outcomeUnknown).toBe(!current)
    expect(failure.message).toContain(current ? '请求结束' : '正在确认')
    expect(request.value).toBe(current ? null : 'request-2')
    expect(failure.details).toEqual(['network disconnected'])
  })

  it.each([false, true])('does not repopulate a replaced scene with an old request error (%s)', stale => {
    let reject!: (error: Error) => void
    const post = new Promise((_resolve, fail) => { reject = fail })
    const beginRecovery = vi.fn()
    const ports: Record<string, any> = {
      isRecommendationRequestActive: () => false, isRecommendingRules: ref(false),
      ruleRecommendationRequestId: ref(null), isSceneReplacementInProgress: ref(false),
      ensureBoardDataReady: () => true, showRecommendationPanel: ref(true), canOpenRecommendationPanel: () => true,
      captureRecommendationRequestOwner: () => ({ userId: 1, authToken: 'test-owner' }),
      ruleRecommendationRequested: ref(false), ruleRecommendations: ref([]), ruleRecommendationRequestEpoch: 0,
      recommendationSceneGeneration: 0, appliedRuleRecommendations: ref(new Set()), applyingRuleRecommendations: ref(new Set()),
      ruleRecommendationAbortController: ref(null), recommendationRequestOwners: new Map(),
      recommendationProgressRequestId: ref(null), recommendationFailures: {},
      recommendationApplyFailures: { rule: {}, device: {}, spec: {} },
      crypto: { randomUUID: () => 'request-1' }, validateRecommendationCount: () => 1,
      ruleRecommendationFilters: { maxRecommendations: 1, userRequirement: '' }, locale: ref('zh-CN'),
      rulesApi: { recommendRules: () => post }, isRecommendationPostOutcomeUnknown: () => true,
      ruleRecommendationPanel: { kind: 'rule' }, beginUnknownRecommendationRecovery: beginRecovery,
      settleRecommendationPost: vi.fn()
    }
    for (const suffix of ['Message', 'FilteredCount', 'FilteredItems', 'AdjustedItems', 'RawCandidateCount',
      'InspectedCount', 'TruncatedCount', 'IsAppliedConfirmation']) ports[`ruleRecommendation${suffix}`] = ref(null)
    const pending = bind('fetchRuleRecommendations', ports)()
    if (stale) { ports.recommendationSceneGeneration++; ports.ruleRecommendationRequestEpoch++ }
    const error = new Error('network disconnected')
    reject(error)
    return pending.then(() => {
      expect(beginRecovery).toHaveBeenCalledExactlyOnceWith(ports.ruleRecommendationPanel, 'request-1', stale ? undefined : error)
    })
  })
})

describe('run failure details', () => {
  it.each(['verification', 'simulation'])('preserves the %s cause behind localized copy', kind => {
    const ports: Record<string, any> = {
      computed, isModelPlaybackActive: ref(false), verificationError: ref(null), simulationError: ref(null),
      errorDetails, technicalErrorLines, t, notifyError: vi.fn(),
      isCompletedTaskResultUnavailableError: () => false, isFuzzCompletedResultUnavailableError: () => false,
      RUN_RESPONSE_INCOMPLETE_CODE: 'RUN_RESPONSE_INCOMPLETE', FUZZ_RESPONSE_INCOMPLETE_CODE: 'FUZZ_RESPONSE_INCOMPLETE',
      formalOperationBusyMessage: () => null, asyncTaskQuotaMessage: () => null
    }
    ports.makeRunFailure = bind('makeRunFailure', ports)
    ports.formatRunFailure = bind('formatRunFailure', ports)
    ports.runFailureDiagnostics = bind('runFailureDiagnostics', ports)
    bind('presentRunFailure', ports)(kind, `app.${kind}Failed`, new Error('NuSMV process failed to start'))
    expect(ports.runFailureDiagnostics.value[kind].details).toContain('NuSMV process failed to start')
    expect(ports.runFailureDiagnostics.value[kind].suggestion).toContain('运行历史和任务状态')
    expect(ports.runFailureDiagnostics.value[kind].cause).toBeUndefined()
  })
})
