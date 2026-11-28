import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { computed, h, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { baseParse, type ElementNode } from '@vue/compiler-dom'
import { describe, expect, it, vi } from 'vitest'
import RecommendationRequestControls from '@/components/RecommendationRequestControls.vue'
import { errorDetails, technicalErrorLines, localizedErrorMessage } from '@/utils/userMessage'
import { diagnoseJsonParseError } from '@/utils/jsonParseDiagnostics'
import { planRecommendationRecoveryAfterStatusFailure } from '@/utils/recommendationRequestRecovery'
import { runTrackedBoardMutation } from '@/views/Board.vue'
import { readBoardWriteReceipt as readWriteReceipt } from '@/utils/boardWriteReceipt'
import i18n from '@/assets/i18n'

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
  it('keeps recovered history and blocks a duplicate unconfirmed scene clear', async () => {
    const preview = { impactToken: 'reviewed-state', templates: [], editHistoryEntryCount: 1 }
    const write = vi.fn().mockRejectedValue(new Error('Lost response'))
    const ports = {
      boardApi: { previewBoardReplacement: vi.fn().mockResolvedValue(preview), clearBoardScene: write },
      isImportingScene: ref(false), isClearingScene: ref(false), isSceneReplacementInProgress: ref(false), hasAssistantWork: ref(false),
      ensurePlaybackClosedForMutation: () => true, ensureBoardDataReady: () => true, waitForPendingBoardMutations: vi.fn(),
      captureBoardRunOwner: () => () => true, boardAuthScopeEpoch: 0,
      getCurrentRecommendationSceneFingerprint: () => 'same scene', runTrackedBoardMutation, readWriteReceipt,
      enqueueBoardMutation: (work: () => Promise<unknown>) => work(),
      summarizeTemplateUsage: () => ({ used: 0, usedCustom: 0, custom: 0 }),
      confirmDestructive: vi.fn().mockResolvedValue(true),
      refreshBoardSnapshot: vi.fn().mockResolvedValue(true),
      invalidateForFullSceneReplacement: vi.fn(), markVerificationResultStale: vi.fn(),
      getVisibleDeviceNodes: () => [],
      environmentVariables: ref([]), rules: ref([]), specifications: ref([]),
      unconfirmedBoardReplacementTokens: new Set<string>(),
      readBoardReplacementStalePreview: () => null, notifyBlocked: vi.fn(), notifySuccess: vi.fn(), t: (key: string) => key
    }
    for (const helper of ['readBoardWriteReceipt', 'canReviewBoardReplacement', 'readBoardReplacementReceipt']) {
      Object.assign(ports, { [helper]: bind(helper, ports) })
    }
    const submit = bind('clearScene', ports)
    await submit()
    await submit()

    expect(ports.invalidateForFullSceneReplacement).not.toHaveBeenCalled()
    expect(write).toHaveBeenCalledOnce()
    expect(ports.refreshBoardSnapshot).toHaveBeenCalledOnce()
    expect(ports.confirmDestructive).toHaveBeenCalledOnce()
    expect(ports.notifySuccess).not.toHaveBeenCalled()
    expect(ports.notifyBlocked).toHaveBeenLastCalledWith('app.sceneReplacementPreviewUnconfirmed')
  })

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
    expect(shown.suggestion).toContain('重新导出')
    expect(shown.suggestion).toContain('文件提供者')
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
    expect(acknowledgeFailure.mock.calls[0]![0].suggestion).toContain('重新导出')
    expect(acknowledgeFailure.mock.calls[0]![0].suggestion).toContain('文件提供者')
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
      captureBoardRunOwner: () => () => true,
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
    await pending
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
  ])('distinguishes a %s request failure from an empty successful response', (kind, suffix, prefix) => {
    const failures: Record<string, unknown> = {}
    bind('reportRecommendationFailure', { recommendationFailures: failures, errorDetails, t,
      extractRecommendationErrorMessage: (error: unknown, fallback: string) => localizedErrorMessage(error, fallback, 'zh-CN')
    })(kind, { response: { status: 503, data: { message: 'AI endpoint unavailable' } } }, '获取推荐失败')
    const state: Record<string, unknown> = { kind, recommendationFailures: failures, t,
      [`isRecommending${suffix}`]: false, [`${prefix}RecommendationRequested`]: true, [`${prefix}Recommendations`]: [] }
    const empty = find(`<div v-if="!isRecommending${suffix} && ${prefix}RecommendationRequested`).loc.source
    const wrapper = mount({
      template: `<RecommendationRequestControls :kind="kind" requirement="" :action-disabled="false"
        :request-pending="false" :action-label="t('app.generateRecommendations')" :failure="recommendationFailures[kind]" />${empty}`,
      components: { RecommendationRequestControls },
      setup: () => state
    }, { global: { plugins: [i18n] } })
    try {
      expect(wrapper.get(`[data-testid="${kind}-recommendation-error"]`).text()).toContain('获取推荐失败')
      expect(wrapper.text()).toContain('AI endpoint unavailable')
      expect(wrapper.text()).toContain(t('app.recommendationFailureNextStep'))
      expect(wrapper.get('[data-testid="failure-cause"]').text()).toBe('AI endpoint unavailable')
      expect(wrapper.text()).not.toContain(t('app.noRecommendationsAvailable'))
    } finally { wrapper.unmount() }
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

  it('releases the foreground after a lost POST and keeps stopping the original request', () => {
    const panel = { kind: 'rule', requestId: ref('request-1'), running: ref(true) }
    const stop = vi.fn()
    const failures = {}
    const report = bind('reportRecommendationFailure', { recommendationFailures: failures, errorDetails, t })
    const markUnknown = bind('markRecommendationOutcomeUnknown', {
      recommendationFailures: failures, reportRecommendationFailure: report
    })
    bind('beginUnknownRecommendationRecovery', {
      markRecommendationOutcomeUnknown: markUnknown,
      recommendationOutcomeUnknownWarnings: new Set(), notifyBlocked: vi.fn(), t,
      stopActiveRecommendation: stop
    })(panel, 'request-1', new Error('network disconnected'))

    expect(panel.running.value).toBe(false)
    expect(panel.requestId.value).toBe('request-1')
    expect(stop).toHaveBeenCalledExactlyOnceWith(panel, { showMessage: false })
  })

  it.each([true, false])('updates an uncertain card only for its current request owner (%s)', current => {
    const failure = { outcomeUnknown: true, message: '正在确认', suggestion: '等待', details: ['network disconnected'] }
    const request = ref(current ? 'request-1' : 'request-2')
    const panel = { requestId: request, abortController: ref(null), running: ref(true) }
    const recoveries = new Map([['rule', { requestId: 'request-1', panel, controller: null }]])
    bind('releaseRecommendationTracking', {
      recommendationStopRecoveries: recoveries, recommendationFailures: { rule: failure }, t,
      recommendationRequestOwners: new Map(), recommendationOutcomeUnknownWarnings: new Set(),
      requestIdAfterTerminalSettlement: (owned: string, finished: string) => owned === finished ? null : owned,
      recordRecommendationTerminalEvidence: vi.fn()
    })('rule', 'request-1')
    expect(failure.outcomeUnknown).toBe(!current)
    expect(failure.message).toContain(current ? '请求结束' : '正在确认')
    expect(request.value).toBe(current ? null : 'request-2')
    expect(panel.running.value).toBe(!current)
    expect(failure.details).toEqual(['network disconnected'])
  })

  it('retains the stopping request when its POST returns before the worker finishes', () => {
    const requestId = 'request-stopping'
    const controller = new AbortController()
    const panel = { kind: 'rule', requestId: ref<string | null>(requestId),
      abortController: ref(controller), running: ref(true) }
    const recovery = { requestId, cancellationAccepted: false, postResponseReceived: false }
    const recoveries = new Map([['rule', recovery]])
    const release = vi.fn(() => {
      recoveries.delete('rule')
      panel.requestId.value = null
      panel.running.value = false
    })
    bind('settleRecommendationPost', {
      recommendationStopRecoveries: recoveries, releaseRecommendationTracking: release
    })(panel, requestId, controller)

    expect(panel.running.value).toBe(true)
    expect(panel.requestId.value).toBe(requestId)
    expect(recovery.postResponseReceived).toBe(true)
  })

  it.each([
    { postReturned: true, cancellationAccepted: false, settles: true },
    { postReturned: false, cancellationAccepted: false, settles: false },
    { postReturned: true, cancellationAccepted: true, settles: false }
  ])('uses a missing status only with a prior POST response and no accepted stop ($postReturned, $cancellationAccepted)',
    async ({ postReturned, cancellationAccepted, settles }) => {
      const requestId = 'request-stopping'
      const recovery = { requestId, cancellationAccepted, postResponseReceived: postReturned,
        retryNotBeforeMs: 0, consecutiveStatusFailures: 0, ownerAuthToken: 'test-owner' }
      const release = vi.fn()
      await bind('refreshRecommendationProgress', {
        boardLifecycleDisposed: false, recommendationProgressInFlightId: null,
        recommendationStopRecoveries: new Map([['rule', recovery]]),
        getRecommendationRequestOwner: () => ({ authToken: 'test-owner' }),
        cancelRecommendationAsOwner: async () => false,
        readRecommendationStatusAsOwner: async () => {
          recovery.postResponseReceived = true
          throw { response: { status: 404 } }
        },
        releaseRecommendationTracking: release,
        planRecommendationRecoveryAfterStatusFailure
      })({ kind: 'rule', requestId: ref(requestId) })

      if (settles) expect(release).toHaveBeenCalledExactlyOnceWith('rule', requestId, { terminalEvidence: 'post-terminal' })
      else {
        expect(release).not.toHaveBeenCalled()
        expect(recovery.consecutiveStatusFailures).toBe(1)
      }
    })

  it('keeps checking the original request after repeated missing status makes the stop outcome unknown', async () => {
    const requestId = 'request-1'
    const panel = { kind: 'rule', requestId: ref(requestId), running: ref(true) }
    const recovery = { requestId, cancellationAccepted: false, postResponseReceived: false,
      retryNotBeforeMs: 0, consecutiveStatusFailures: 0, ownerAuthToken: 'test-owner' }
    const cancel = vi.fn().mockResolvedValue(false)
    const read = vi.fn().mockRejectedValue({ response: { status: 404 } })
    const release = vi.fn()
    const accepted = vi.fn()
    const report = vi.fn()
    const refresh = bind('refreshRecommendationProgress', {
      boardLifecycleDisposed: false, recommendationProgressInFlightId: null,
      recommendationStopRecoveries: new Map([['rule', recovery]]),
      getRecommendationRequestOwner: () => ({ authToken: 'test-owner' }),
      cancelRecommendationAsOwner: cancel, readRecommendationStatusAsOwner: read,
      acceptRecommendationCancellation: accepted, releaseRecommendationTracking: release,
      planRecommendationRecoveryAfterStatusFailure,
      markRecommendationOutcomeUnknown: bind('markRecommendationOutcomeUnknown', {
        recommendationFailures: {}, reportRecommendationFailure: report
      })
    })
    for (let attempt = 0; attempt < 7; attempt++) await refresh(panel, true)

    expect(panel.running.value).toBe(false)
    expect(panel.requestId.value).toBe(requestId)
    expect(read.mock.calls).toEqual(Array.from({ length: 7 }, () => [requestId, 'test-owner']))
    expect(accepted).not.toHaveBeenCalled()
    expect(release).not.toHaveBeenCalled()
    expect(report).toHaveBeenCalledWith('rule', undefined, '', true)
  })

  it.each([false, true])('does not repopulate a replaced scene with an old request error (%s)', stale => {
    let reject!: (error: Error) => void
    const post = new Promise((_resolve, fail) => { reject = fail })
    const beginRecovery = vi.fn()
    const panel = {
      kind: 'rule', running: ref(false), requested: ref(false), epoch: 0,
      requestId: ref<string | null>(null), abortController: ref<AbortController | null>(null)
    }
    const ports: Record<string, any> = {
      isRecommendationRequestActive: () => false, isSceneReplacementInProgress: ref(false),
      ensureBoardDataReady: () => true, canGenerateRecommendations: () => true,
      boardLifecycleDisposed: false, currentAuthUserId: ref(1),
      captureRecommendationRequestOwner: () => ({ userId: 1, authToken: 'test-owner' }),
      recommendationSceneGeneration: 0, recommendationRequestOwners: new Map(),
      crypto: { randomUUID: () => 'request-1' },
      isRecommendationPostOutcomeUnknown: () => true, beginUnknownRecommendationRecovery: beginRecovery,
      settleRecommendationPost: vi.fn(), resetRecommendationResults: vi.fn()
    }
    const pending = bind('generateRecommendations', ports)({
      panel, requiredKeys: ['nodes', 'templates', 'rules'],
      request: () => post, adopt: vi.fn(), failureMessageKey: 'app.failedToFetchRuleRecommendations'
    })
    if (stale) { ports.recommendationSceneGeneration++; panel.epoch++ }
    const error = new Error('network disconnected')
    reject(error)
    return pending.then(() => {
      expect(beginRecovery).toHaveBeenCalledExactlyOnceWith(panel, 'request-1', stale ? undefined : error)
      expect(panel.requestId.value).toBe('request-1')
      expect(panel.running.value).toBe(true)
      expect(ports.settleRecommendationPost).not.toHaveBeenCalled()
    })
  })
})

describe('run failure details', () => {
  it.each(['verification', 'simulation'])('preserves the %s cause behind localized copy', kind => {
    const ports: Record<string, any> = {
      computed, automaticRunPresentation: () => 'foreground', verificationError: ref(null), simulationError: ref(null),
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
