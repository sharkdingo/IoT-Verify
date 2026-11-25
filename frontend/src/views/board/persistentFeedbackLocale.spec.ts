import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { computed, defineComponent, nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { i18n } from '@/assets/i18n'
import { errorDetails } from '@/utils/userMessage'
import { createPagedRequestCoordinator } from '@/utils/pagedRequestCoordinator'
import type { BoardWorkflowPanel } from './runPresentation'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const start = board.indexOf('<script setup')
const setup = board.slice(board.indexOf('>', start) + 1, board.indexOf('</script>', start))
const source = ts.createSourceFile('Board.ts', setup, ts.ScriptTarget.Latest, true)
const declaration = (name: string) => source.statements.find(statement => ts.isVariableStatement(statement)
  && statement.declarationList.declarations.some(item => item.name.getText(source) === name))
const bind = (name: string, ports: Record<string, unknown>) => {
  const statement = declaration(name)
  if (!statement) throw new Error(`Missing Board declaration ${name}`)
  return new Function('ports', `with (ports) { ${ts.transpileModule(statement.getText(source), {
    compilerOptions: { target: ts.ScriptTarget.ES2022 }
  }).outputText}; return ${name} }`)(ports)
}

const bindWorkflowPanel = (ports: Record<string, any>) => {
  ports.activeWorkflowPanel = ref<BoardWorkflowPanel | null>(null)
  ports.historyPanelIntentEpoch = 0
  ports.pendingHistoryRunTarget = null
  ports.historyDetailRequests = createPagedRequestCoordinator()
  for (const name of ['showFuzzingPanel', 'tracksRun', 'fuzzingPanelTracksRun', 'retireHistoryPanelIntent',
    'openRunPanel', 'setRunPanelView', 'closeWorkflowPanel']) ports[name] = bind(name, ports)
}

const bindSettingsAdmission = (ports: Record<string, any>) => {
  ports.isVerifying = ref(false)
  ports.isSimulating = ref(false)
  ports.isModelPlaybackActive = ref(false)
  ports.isSceneReplacementInProgress = ref(false)
  ports.isAnyRecommendationRunning = () => false
  for (const name of ['isRunKindActive', 'canOpenWorkflowPanel', 'canOpenTaskSettings']) ports[name] = bind(name, ports)
}

describe('persistent workspace feedback language', () => {
  it('keeps the historical exploration open and preserves the running draft when settings reuse must wait', () => {
    const form = { targetSpecIds: ['running-target'], seed: 17 }
    const ports: Record<string, any> = {
      computed,
      isFuzzing: ref(true), fuzzingForm: form, fuzzingSettingsNotice: ref(null),
      fuzzingResult: ref({ targetSpecIds: ['historical-target'], explorationMode: 'BOARD_SNAPSHOT',
        maxIterations: 3, pathLength: 4, populationSize: 1, effectiveSeed: 42 }),
      knownFuzzEligibleSpecifications: ref([{ id: 'historical-target' }]),
      dismissFuzzingResult: vi.fn(),
      t: (key: string) => key, notifyInfo: vi.fn()
    }
    bindWorkflowPanel(ports)
    bindSettingsAdmission(ports)
    bind('reuseFuzzingSettings', ports)()
    expect(form).toEqual({ targetSpecIds: ['running-target'], seed: 17 })
    expect(ports.dismissFuzzingResult).not.toHaveBeenCalled()
    expect(ports.showFuzzingPanel.value).toBe(false)
    expect(ports.notifyInfo).toHaveBeenCalledExactlyOnceWith('app.taskSettingsBlockedByActiveRun')
  })

  it.each([true, false])('keeps recovery feedback with the visible owner instead of repeating a toast: panel visible=%s', async visible => {
    const ports: Record<string, any> = {
      computed, t: (key: string) => i18n.global.t(key), errorDetails,
      isFuzzing: ref(false), fuzzingTasks: ref([]), fuzzingWatchedTask: ref(null),
      fuzzingCancelRequested: ref(false), cancellingFuzzingTask: ref(false),
      fuzzingError: ref(null), fuzzingFailureDetails: ref(null), paperDomainStaleRecoveryActive: ref(false),
      asyncFuzzingTask: ref({ taskId: null }), fuzzingSettingsNotice: ref({ key: 'app.fuzzSettingsReused' }),
      normalizeTaskProgress: () => 0, taskProgressStatusKey: () => 'app.taskInitializing',
      trackFuzzTask: vi.fn(), closeHistoryPanel: vi.fn(), isPollingAbortedError: () => false,
      isAsyncTaskCancelledError: () => false, isFuzzTaskRecoveryPendingError: () => true,
      notifyInfo: vi.fn(), boardLifecycleDisposed: false, loadTaskInbox: vi.fn().mockResolvedValue(true)
    }
    bindWorkflowPanel(ports)
    ports.captureBoardRunOwner = () => () => !ports.boardLifecycleDisposed
    let noticeAtPoll: unknown
    ports.pollAsyncFuzzing = vi.fn(async () => {
      noticeAtPoll = ports.fuzzingSettingsNotice.value
      if (!visible) ports.closeWorkflowPanel('fuzzing')
      throw new Error('Result recovery still pending')
    })
    if (declaration('fuzzingSettingsNoticeMessage')) {
      ports.fuzzingSettingsNoticeMessage = bind('fuzzingSettingsNoticeMessage', ports)
    }
    await bind('watchFuzzingTask', ports)(7)
    expect(noticeAtPoll).toBeNull()
    const notice = ports.fuzzingSettingsNoticeMessage?.value ?? ports.fuzzingSettingsNotice.value
    expect(notice).toBe(i18n.global.t('app.fuzzResultRecoveryPending'))
    if (visible) expect(ports.notifyInfo).not.toHaveBeenCalled()
    else expect(ports.notifyInfo).toHaveBeenCalledExactlyOnceWith(i18n.global.t('app.fuzzResultRecoveryPending'))
    expect(ports.fuzzingError.value).toBeNull()
    expect(ports.loadTaskInbox).toHaveBeenCalledOnce()
  })

  it.each(['all', 'missing', 'available'] as const)('updates reused exploration settings feedback for %s targets without changing the restored scope', async scope => {
    const previousLocale = i18n.global.locale.value
    i18n.global.locale.value = 'zh-CN'
    const targetIds = scope === 'all' ? [] : scope === 'missing' ? ['live', 'removed'] : ['live']
    const restoredIds = scope === 'all' ? ['frozen'] : targetIds
    const ports: Record<string, any> = {
      computed, t: (key: string, params?: Record<string, unknown>) => i18n.global.t(key, params || {}),
      fuzzingResult: ref({ targetSpecIds: targetIds, eligibility: { eligibleSpecIds: ['frozen'], ineligibleSpecs: [] },
        explorationMode: 'BOARD_SNAPSHOT', maxIterations: 3, pathLength: 4, populationSize: 1, effectiveSeed: 42 }),
      knownFuzzEligibleSpecifications: ref([{ id: 'live' }]), fuzzingForm: {}, fuzzingSettingsNotice: ref(null),
      isFuzzing: ref(false), dismissFuzzingResult: vi.fn()
    }
    bindWorkflowPanel(ports)
    bindSettingsAdmission(ports)
    if (declaration('fuzzingSettingsNoticeMessage')) {
      ports.fuzzingSettingsNoticeMessage = bind('fuzzingSettingsNoticeMessage', ports)
    }
    const expression = board.match(/<FuzzingPanel\b[\s\S]*?:notice="([^"]+)"/)?.[1]
    if (!expression) throw new Error('Missing exploration notice binding')
    const Surface = defineComponent({ setup: () => ports, template: `<p>{{ ${expression} }}</p>` })
    const wrapper = mount(Surface)
    try {
      bind('reuseFuzzingSettings', ports)()
      const key = scope === 'all' ? 'app.fuzzSettingsReusedAllTargets'
        : scope === 'missing' ? 'app.fuzzSettingsReusedWithMissingTargets' : 'app.fuzzSettingsReused'
      for (const locale of ['zh-CN', 'en'] as const) {
        i18n.global.locale.value = locale
        await nextTick()
        expect(wrapper.text()).toBe(i18n.global.t(key, { count: 1 }))
        expect(ports.fuzzingForm.targetSpecIds).toEqual(restoredIds)
        expect(ports.fuzzingForm.seed).toBe(42)
      }
      expect(ports.showFuzzingPanel.value).toBe(true)
    } finally { wrapper.unmount(); i18n.global.locale.value = previousLocale }
  })
})
