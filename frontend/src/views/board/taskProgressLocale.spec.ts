import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { computed, ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { describe, expect, it, vi } from 'vitest'
import actualI18n from '@/assets/i18n'
import { activeTaskProgressStage } from '@/utils/runResponse'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const start = board.indexOf('<script setup')
const source = ts.createSourceFile('Board.ts', board.slice(board.indexOf('>', start) + 1,
  board.indexOf('</script>', start)), ts.ScriptTarget.Latest, true)
const declaration = (name: string) => source.statements.find(statement => ts.isVariableStatement(statement)
  && statement.declarationList.declarations.some(item => item.name.getText(source) === name))
const bind = (name: string, ports: Record<string, any>) => {
  const statement = declaration(name)
  if (!statement) throw new Error(`Missing Board declaration ${name}`)
  const javascript = ts.transpileModule(statement.getText(source), {
    compilerOptions: { target: ts.ScriptTarget.ES2022 }
  }).outputText
  return new Function('ports', `with (ports) { ${javascript}; return ${name} }`)(ports)
}

const setup = () => {
  const i18n = createI18n({ legacy: false, locale: 'en', messages: {
    en: actualI18n.global.getLocaleMessage('en'),
    'zh-CN': actualI18n.global.getLocaleMessage('zh-CN')
  } })
  const ports: Record<string, any> = {
    ref, computed, activeTaskProgressStage, t: (key: string) => i18n.global.t(key),
    isVerifying: ref(true), isSimulating: ref(true), isFuzzing: ref(true),
    asyncVerificationActive: ref(true), asyncSimulationActive: ref(true), asyncFuzzingActive: ref(true),
    activeVerificationTasks: ref([]), activeSimulationTasks: ref([]), activeFuzzingTasks: ref([]),
    normalizeTaskProgress: (value: number) => value, boardLifecycleDisposed: false,
    notifyTaskCancellationResult: vi.fn()
  }
  ports.taskProgressStatusKey = bind('taskProgressStatusKey', ports)
  ports.formatTaskProgressStage = bind('formatTaskProgressStage', ports)
  ports.formatWatchedTaskStatus = bind('formatWatchedTaskStatus', ports)
  for (const kind of ['Verification', 'Simulation', 'Fuzzing']) {
    ports[`async${kind}Task`] = bind(`async${kind}Task`, ports)
    ports[`async${kind}Task`].value.taskId = 7
    ports[`cancelling${kind}Task`] = ref(false)
    ports[`${kind.toLowerCase()}CancelRequested`] = ref(false)
  }
  return { ports, i18n, items: bind('miniTaskItems', ports) }
}

describe('live task status language', () => {
  it.each(['verification', 'simulation', 'fuzzing'])('updates the initializing %s status without another server response', kind => {
    const { items, i18n } = setup()
    const status = () => items.value.find((item: any) => item.kind === kind).status
    expect(status()).toBe(i18n.global.t('app.taskInitializing'))
    i18n.global.locale.value = 'zh-CN'
    expect(status()).toBe(i18n.global.t('app.taskInitializing'))
    i18n.global.locale.value = 'en'
    expect(status()).toBe(i18n.global.t('app.taskInitializing'))
  })

  it.each([
    ['verification', 'pollAsyncVerification', 'boardApi', 'Verification', 'EXECUTING_MODEL_CHECKER'],
    ['simulation', 'pollAsyncSimulation', 'simulationApi', 'Simulation', 'RUNNING_SIMULATION'],
    ['fuzzing', 'pollAsyncFuzzing', 'fuzzingApi', 'Fuzzing', 'EXPLORING_CANDIDATES']
  ])('translates the received %s phase without polling again', async (kind, handler, api, suffix, stage) => {
    const { ports, i18n, items } = setup()
    const stopped = new Error('Stop this test poll')
    let stop!: (error: Error) => void
    Object.assign(ports, {
      pollingEpoch: 0, ASYNC_TASK_MAX_POLLS: 2, semanticSceneChangeCount: 0,
      throwIfPollingAborted: vi.fn(), isPollingAbortedError: () => false,
      isPermanentPollError: () => false,
      waitForNextPoll: vi.fn(() => new Promise((_resolve, reject) => { stop = reject })),
      [`upsert${suffix}TaskSummary`]: vi.fn(),
      [api]: { getTask: vi.fn().mockResolvedValue({ id: 7, status: 'RUNNING', progressStage: stage, progress: 31 }) }
    })
    const pending = bind(handler, ports)(7).catch((error: Error) => error)
    try {
      await vi.waitFor(() => expect(ports.waitForNextPoll).toHaveBeenCalledOnce())
      const item = () => items.value.find((entry: any) => entry.kind === kind)
      expect(item().status).toBe(i18n.global.t(`app.taskProgressStage_${stage}`))
      expect(item().progress).toBe(31)
      i18n.global.locale.value = 'zh-CN'
      expect(item().status).toBe(i18n.global.t(`app.taskProgressStage_${stage}`))
      expect(ports[api].getTask).toHaveBeenCalledExactlyOnceWith(7)
    } finally {
      stop(stopped)
      expect(await pending).toBe(stopped)
    }
  })

  it.each([
    ['verification', 'cancelAsyncVerification', 'boardApi'],
    ['simulation', 'cancelAsyncSimulation', 'simulationApi'],
    ['fuzzing', 'cancelAsyncFuzzing', 'fuzzingApi']
  ])('updates cancelling %s status while the cancellation request is pending', async (kind, handler, api) => {
    const { ports, i18n, items } = setup()
    let resolve!: (value: unknown) => void
    ports[api] = { cancelTask: vi.fn(() => new Promise(done => { resolve = done })) }
    const pending = bind(handler, ports)()
    try {
      const status = () => items.value.find((item: any) => item.kind === kind).status
      expect(status()).toBe(i18n.global.t('app.taskCancelling'))
      ports[`async${kind[0]!.toUpperCase() + kind.slice(1)}Task`].value.statusKey = 'app.taskProgressStage_PERSISTING_RESULT'
      expect(status()).toBe(i18n.global.t('app.taskCancelling'))
      i18n.global.locale.value = 'zh-CN'
      expect(status()).toBe(i18n.global.t('app.taskCancelling'))
      expect(ports[api].cancelTask).toHaveBeenCalledExactlyOnceWith(7)
    } finally {
      resolve({ cancellationAccepted: true, cancellationOutcome: 'ACCEPTED', taskStatus: 'CANCELLED' })
      await pending
    }
    expect(items.value.find((item: any) => item.kind === kind).status).toBe(i18n.global.t('app.taskStatusCancelled'))
  })

  it.each([
    ['verification', 'cancelAsyncVerification', 'boardApi'],
    ['simulation', 'cancelAsyncSimulation', 'simulationApi'],
    ['fuzzing', 'cancelAsyncFuzzing', 'fuzzingApi']
  ])('restores the known %s phase when cancellation cannot be confirmed', async (kind, handler, api) => {
    const { ports, i18n, items } = setup()
    ports[`async${kind[0]!.toUpperCase() + kind.slice(1)}Task`].value.statusKey = 'app.taskProgressStage_GENERATING_MODEL'
    ports[api] = { cancelTask: vi.fn().mockRejectedValue(new Error('gateway unavailable')) }
    ports.acknowledgeFailure = vi.fn()
    ports.errorDetails = () => ({ details: [], fields: {} })
    ports.localizedErrorMessage = (_error: unknown, fallback: string) => fallback
    ports.locale = i18n.global.locale
    const status = () => items.value.find((entry: any) => entry.kind === kind).status
    await bind(handler, ports)()
    expect(status()).toBe(i18n.global.t('app.taskProgressStage_GENERATING_MODEL'))
    i18n.global.locale.value = 'zh-CN'
    expect(status()).toBe(i18n.global.t('app.taskProgressStage_GENERATING_MODEL'))
    expect(ports.acknowledgeFailure).toHaveBeenCalledOnce()
    expect(ports[`${kind}CancelRequested`].value).toBe(false)
  })

  it.each([
    ['verification', 'cancelAsyncVerification', 'boardApi'],
    ['simulation', 'cancelAsyncSimulation', 'simulationApi'],
    ['fuzzing', 'cancelAsyncFuzzing', 'fuzzingApi']
  ])('uses the server-confirmed %s terminal status when cancellation loses the race', async (kind, handler, api) => {
    const { ports, i18n, items } = setup()
    ports[api] = { cancelTask: vi.fn().mockResolvedValue({ cancellationAccepted: false,
      cancellationOutcome: 'ALREADY_COMPLETED', taskStatus: 'COMPLETED' }) }
    await bind(handler, ports)()
    const status = () => items.value.find((entry: any) => entry.kind === kind).status
    expect(status()).toBe(i18n.global.t('app.taskStatusCompleted'))
    i18n.global.locale.value = 'zh-CN'
    expect(status()).toBe(i18n.global.t('app.taskStatusCompleted'))
    expect(ports[`${kind}CancelRequested`].value).toBe(false)
    expect(ports.notifyTaskCancellationResult).toHaveBeenCalledOnce()
  })

  it.each([
    ['verification', 'cancelAsyncVerification', 'boardApi'],
    ['simulation', 'cancelAsyncSimulation', 'simulationApi'],
    ['fuzzing', 'cancelAsyncFuzzing', 'fuzzingApi']
  ])('does not replace a newer %s poll phase after a failed cancellation request', async (kind, handler, api) => {
    const { ports, i18n, items } = setup()
    let reject!: (error: Error) => void
    ports[api] = { cancelTask: vi.fn(() => new Promise((_resolve, fail) => { reject = fail })) }
    Object.assign(ports, { acknowledgeFailure: vi.fn(), errorDetails: () => ({ details: [], fields: {} }),
      localizedErrorMessage: (_error: unknown, fallback: string) => fallback, locale: i18n.global.locale })
    const pending = bind(handler, ports)()
    ports[`async${kind[0]!.toUpperCase() + kind.slice(1)}Task`].value.statusKey = 'app.taskProgressStage_PERSISTING_RESULT'
    reject(new Error('late cancellation response'))
    await pending
    expect(items.value.find((entry: any) => entry.kind === kind).status).toBe(i18n.global.t('app.taskProgressStage_PERSISTING_RESULT'))
  })

})
