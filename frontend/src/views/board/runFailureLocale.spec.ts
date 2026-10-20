import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { baseParse, type ElementNode } from '@vue/compiler-dom'
import { computed, ref, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import i18n from '@/assets/i18n'
import FailureDetails from '@/components/FailureDetails.vue'
import FuzzingPanel from '@/components/FuzzingPanel.vue'
import { errorDetails, technicalErrorLines } from '@/utils/userMessage'
import { getFuzzActiveTaskLimit, getFuzzStoredTaskLimit } from '@/utils/fuzzingResponse'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const start = board.indexOf('<script setup')
const source = ts.createSourceFile('Board.ts', board.slice(board.indexOf('>', start) + 1,
  board.indexOf('</script>', start)), ts.ScriptTarget.Latest, true)
const bind = (name: string, ports: Record<string, any>) => {
  const statement = source.statements.find(item => ts.isClassDeclaration(item) ? item.name?.text === name
    : ts.isVariableStatement(item) && item.declarationList.declarations.some(value => value.name.getText(source) === name))
  if (!statement) throw new Error(`Missing Board declaration ${name}`)
  return new Function('ports', `with (ports) { ${ts.transpileModule(statement.getText(source), {
    compilerOptions: { target: ts.ScriptTarget.ES2022 }
  }).outputText}; return ${name} }`)(ports)
}
const template = baseParse(board.slice(board.indexOf('<template>') + 10, board.lastIndexOf('</template>')))
const surface = (prefix: string): string => {
  const visit = (node: any): ElementNode | undefined => {
    if (node.type === 1 && node.loc.source.startsWith(prefix)) return node
    for (const child of node.children || []) {
      const found = visit(child)
      if (found) return found
    }
  }
  const found = visit(template)
  if (!found) throw new Error(`Missing failure surface ${prefix}`)
  return found.loc.source
}
const setup = () => {
  const ports: Record<string, any> = {
    computed, ref, errorDetails, technicalErrorLines, getFuzzActiveTaskLimit, getFuzzStoredTaskLimit,
    t: (key: string, params?: Record<string, unknown>) => i18n.global.t(key, params || {}),
    RUN_RESPONSE_INCOMPLETE_CODE: 'RUN_RESPONSE_INCOMPLETE', FUZZ_RESPONSE_INCOMPLETE_CODE: 'FUZZ_RESPONSE_INCOMPLETE',
    verificationError: ref(null), simulationError: ref(null), fuzzingError: ref(null),
    verificationResult: ref(null), isModelPlaybackActive: ref(false),
    dismissResultDialog: vi.fn(), dismissSimulationResultDialog: vi.fn()
  }
  for (const name of ['CompletedTaskResultUnavailableError', 'FuzzCompletedResultUnavailableError',
    'isCompletedTaskResultUnavailableError', 'isFuzzCompletedResultUnavailableError', 'fuzzTaskQuotaMessage',
    'asyncTaskQuotaMessage', 'formalOperationBusyMessage', 'makeRunFailure', 'formatRunFailure', 'runFailureTitle',
    'runFailureDiagnostics', 'fuzzingFailureDetails', 'fuzzingErrorMessage', 'presentRunFailure',
    'verificationVerdictTone', 'verificationResultStatus']) ports[name] = bind(name, ports)
  return ports
}
const open = (kind: 'verification' | 'simulation' | 'fuzzing', ports: Record<string, any>, error: unknown) => {
  const key = kind === 'fuzzing' ? 'app.fuzzSearchFailed' : `app.${kind}Failed`
  if (kind === 'fuzzing') ports.fuzzingError.value = ports.makeRunFailure(key, error)
  else ports.presentRunFailure(kind, key, error)
  if (kind === 'fuzzing') {
    return mount({ components: { FuzzingPanel }, setup: () => ports,
      template: `<FuzzingPanel :form="{ explorationMode: 'BOARD_SNAPSHOT', targetSpecIds: [], maxIterations: 2, pathLength: 2, populationSize: 1, seed: null }"
        :specifications="[]" :running="false" :progress="0" status="" :task-id="null" :cancelling="false"
        :error="fuzzingErrorMessage" :failure-details="fuzzingFailureDetails" />` }, { global: { plugins: [i18n] } })
  }
  const markup = kind === 'verification'
    ? surface('<div data-testid="verification-result-header"') + surface('<div v-if="verificationError"')
    : surface('<header class="iot-dialog__header">\n        <div class="iot-dialog__icon"><span class="material-symbols-outlined" aria-hidden="true">error</span>')
      + surface('<div class="board-run-notice board-surface-danger">')
  return mount({ components: { FailureDetails }, template: markup, setup: () => ports }, {
    global: { plugins: [i18n], stubs: { HintTooltip: { props: ['content'], template: '<slot />' } } }
  })
}
const previousLocale = i18n.global.locale.value
afterEach(() => { i18n.global.locale.value = previousLocale })

describe('run failure language and recovery', () => {
  it.each(['verification', 'simulation', 'fuzzing'] as const)('updates %s failure guidance and keeps raw diagnostics in one disclosure', async kind => {
    i18n.global.locale.value = 'zh-CN'
    const ports = setup()
    const error = { message: 'Gateway disconnected', response: { status: 422, data: {
      message: 'Configuration rejected', data: { errors: { steps: 'Must be positive' } }
    } } }
    const wrapper = open(kind, ports, error)
    try {
      for (const locale of ['zh-CN', 'en'] as const) {
        i18n.global.locale.value = locale
        await nextTick()
        expect(wrapper.get('[data-testid="failure-next-step"]').text()).toContain(i18n.global.t('app.runValidationNextStep'))
        expect(wrapper.find('[data-testid="failure-cause"]').exists()).toBe(false)
        expect(wrapper.find('[data-testid="failure-fields"]').exists()).toBe(false)
        if (kind !== 'fuzzing') expect(wrapper.findAll('p').filter(line => line.text() === i18n.global.t(`app.${kind}Failed`))).toHaveLength(0)
        const technical = wrapper.get('[data-testid="failure-technical-details"]')
        expect(technical.text()).toContain('Configuration rejected')
        expect(technical.text()).toContain('steps: Must be positive')
        expect(technical.text()).toContain('HTTP 422')
        expect(technical.attributes('open')).toBeUndefined()
        expect(wrapper.findAll('h3, [role="alert"]').filter(item => item.text() === i18n.global.t(
          kind === 'fuzzing' ? 'app.fuzzSearchFailed' : `app.${kind}Failed`))).toHaveLength(kind === 'fuzzing' ? 0 : 1)
      }
    } finally { wrapper.unmount() }
  })

  it.each(['verification', 'simulation', 'fuzzing'] as const)('does not describe a completed %s task as a failed run when its result cannot be read', async kind => {
    i18n.global.locale.value = 'zh-CN'
    const ports = setup()
    const original = { response: { status: 503, data: { message: 'Result store unavailable' } } }
    const error = kind === 'fuzzing' ? new ports.FuzzCompletedResultUnavailableError(original)
      : new ports.CompletedTaskResultUnavailableError(kind, original)
    const wrapper = open(kind, ports, error)
    try {
      for (const locale of ['zh-CN', 'en'] as const) {
        i18n.global.locale.value = locale
        await nextTick()
        expect(wrapper.text()).toContain(i18n.global.t('app.completedTaskResultUnavailable'))
        expect(wrapper.get('[data-testid="failure-next-step"]').text()).toContain(i18n.global.t('app.completedTaskResultNextStep'))
        expect(wrapper.text()).not.toContain(i18n.global.t('app.runFailureNextStep'))
        expect(wrapper.get('[data-testid="failure-technical-details"]').text()).toContain('HTTP 503')
        if (kind !== 'fuzzing') expect(wrapper.get('h3').text()).toBe(i18n.global.t(
          kind === 'verification' ? 'app.failedToLoadVerificationRun' : 'app.failedToLoadSimulationRun'))
      }
    } finally { wrapper.unmount() }
  })

  it.each(['verification', 'simulation'] as const)('keeps %s polling exhaustion distinct from execution failure', async kind => {
    const ports = setup()
    const wrapper = open(kind, ports, Object.assign(new Error('Task status polling timed out'), { code: 'TASK_POLL_TIMEOUT' }))
    try {
      for (const locale of ['zh-CN', 'en'] as const) {
        i18n.global.locale.value = locale
        await nextTick()
        expect(wrapper.get('h3').text()).toBe(i18n.global.t('app.taskStatusUnavailable'))
        expect(wrapper.text()).toContain(i18n.global.t(`app.${kind}Timeout`))
        expect(wrapper.get('[data-testid="failure-next-step"]').text()).toContain(i18n.global.t('app.taskStatusNextStep'))
      }
    } finally { wrapper.unmount() }
  })

  it.each(['verification', 'simulation', 'fuzzing'] as const)('updates %s task-limit feedback without losing the counts', async kind => {
    const ports = setup()
    const data = kind === 'fuzzing'
      ? { reasonCode: 'FUZZ_ACTIVE_TASK_LIMIT_REACHED', activeTaskCount: 3, maxActiveTasksPerUser: 3 }
      : { reasonCode: `${kind.toUpperCase()}_ACTIVE_TASK_LIMIT_REACHED`, quotaType: 'ACTIVE', taskCount: 3, maxTasksPerUser: 3 }
    const error = { response: { status: 429, data: { message: 'Active task limit reached', data } } }
    const wrapper = open(kind, ports, error)
    try {
      for (const locale of ['zh-CN', 'en'] as const) {
        i18n.global.locale.value = locale
        await nextTick()
        expect(wrapper.text()).toContain(kind === 'fuzzing'
          ? i18n.global.t('app.fuzzActiveTaskLimitReached', { active: 3, limit: 3 })
          : i18n.global.t('app.asyncTaskActiveLimitReached', { count: 3, limit: 3 }))
      }
    } finally { wrapper.unmount() }
  })

  it.each(['verification', 'simulation'] as const)('clears the previous %s failure while a watched task is retried', async kind => {
    const ports = setup()
    ports.presentRunFailure(kind, `app.${kind}Failed`, new Error('old failure'))
    let stop!: () => void
    Object.assign(ports, {
      isVerifying: ref(false), isSimulating: ref(false), verificationTasks: ref([]), simulationTasks: ref([]),
      showVerificationPanel: ref(false), showSimulationPanel: ref(false),
      asyncVerificationTask: ref({ taskId: null }), asyncSimulationTask: ref({ taskId: null }),
      asyncVerificationActive: ref(false), asyncSimulationActive: ref(false),
      verificationCancelRequested: ref(false), simulationCancelRequested: ref(false),
      cancellingVerificationTask: ref(false), cancellingSimulationTask: ref(false),
      closeHistoryPanel: vi.fn(), normalizeTaskProgress: () => 0, taskProgressStatusKey: () => 'app.taskInitializing',
      semanticSceneChangeCount: 0, isPollingAbortedError: () => true, boardLifecycleDisposed: false,
      loadTaskInbox: vi.fn(), [`pollAsync${kind === 'verification' ? 'Verification' : 'Simulation'}`]:
        () => new Promise((_resolve, reject) => { stop = () => reject(new Error('poll aborted')) })
    })
    const pending = bind(kind === 'verification' ? 'watchVerificationTask' : 'watchSimulationTask', ports)(7)
    try {
      expect(ports[`${kind}Error`].value).toBeNull()
      expect(ports.runFailureDiagnostics.value[kind]).toBeNull()
      expect(ports[kind === 'verification' ? 'showVerificationPanel' : 'showSimulationPanel'].value).toBe(true)
    } finally { stop(); await pending }
    expect(ports.loadTaskInbox).toHaveBeenCalledOnce()
  })
})
