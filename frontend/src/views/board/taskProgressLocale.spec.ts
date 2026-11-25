import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { computed, ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { describe, expect, it, vi } from 'vitest'
import actualI18n from '@/assets/i18n'
import { createPagedRequestCoordinator } from '@/utils/pagedRequestCoordinator'
import { activeTaskProgressStage } from '@/utils/runResponse'
import { errorDetails, localizedErrorMessage } from '@/utils/userMessage'
import { chooseRunPresentation, foregroundRunBlock } from './runPresentation'

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
    asyncVerificationActive: ref(true), asyncSimulationActive: ref(true),
    activeVerificationTasks: ref([]), activeSimulationTasks: ref([]), activeFuzzingTasks: ref([]),
    verificationTasks: ref([]), simulationTasks: ref([]), fuzzingTasks: ref([]),
    normalizeTaskProgress: (value: number) => value, boardLifecycleDisposed: false,
    pendingTaskActionKeys: ref(new Set<string>()), loadTaskInbox: vi.fn().mockResolvedValue(true),
    notifyTaskCancellationResult: vi.fn(),
    acknowledgeFailure: vi.fn(), notifyError: vi.fn(), errorDetails, localizedErrorMessage,
    locale: i18n.global.locale, chooseRunPresentation, foregroundRunBlock,
    isModelPlaybackActive: ref(false), openModalDepth: ref(0),
    historyDetailRequests: createPagedRequestCoordinator(),
    verificationPanelTracksRun: ref(true), simulationPanelTracksRun: ref(true), fuzzingPanelTracksRun: ref(true)
  }
  ports.automaticRunPresentationBlock = bind('automaticRunPresentationBlock', ports)
  ports.automaticRunPresentation = bind('automaticRunPresentation', ports)
  ports.presentTaskCancellationFailure = bind('presentTaskCancellationFailure', ports)
  ports.taskActionKey = bind('taskActionKey', ports)
  ports.withTaskActionLock = bind('withTaskActionLock', ports)
  ports.taskProgressStatusKey = bind('taskProgressStatusKey', ports)
  ports.formatTaskProgressStage = bind('formatTaskProgressStage', ports)
  ports.formatWatchedTaskStatus = bind('formatWatchedTaskStatus', ports)
  ports.isWatchedTaskCancelled = bind('isWatchedTaskCancelled', ports)
  ports.ensureActiveWatchedTaskPoll = bind('ensureActiveWatchedTaskPoll', ports)
  ports.readWatchedTaskStatus = bind('readWatchedTaskStatus', ports)
  ports.applyTaskCancellationReceipt = bind('applyTaskCancellationReceipt', ports)
  for (const kind of ['Verification', 'Simulation', 'Fuzzing']) {
    ports[`async${kind}Task`] = bind(`async${kind}Task`, ports)
    ports[`async${kind}Task`].value.taskId = 7
    ports[`${kind.toLowerCase()}CancelRequested`] = ref(false)
    ports[`watched${kind}TaskIds`] = bind(`watched${kind}TaskIds`, ports)
    ports[`cancelling${kind}Task`] = bind(`cancelling${kind}Task`, ports)
    ports[`cancelWatched${kind}`] = bind(`cancelWatched${kind}`, ports)
  }
  ports.watchedTaskId = bind('watchedTaskId', ports)
  ports.isWatchedTaskCancellationPending = bind('isWatchedTaskCancellationPending', ports)
  ports.cancelTaskFromProgress = bind('cancelTaskFromProgress', ports)
  return { ports, i18n, items: bind('miniTaskItems', ports) }
}

describe('live task status language', () => {
  it.each(['Verification', 'Simulation', 'Fuzzing'] as const)(
    'keeps the active inbox %s task visible after its local watch stops', (suffix) => {
      const { ports, items } = setup()
      const kind = suffix.toLowerCase()
      const activeTask = { id: 7, status: 'RUNNING', progress: 41 }
      ports[`active${suffix}Tasks`].value = [activeTask]
      expect(items.value.filter((item: any) => item.kind === kind)).toHaveLength(1)

      if (suffix === 'Fuzzing') ports.isFuzzing.value = false
      else ports[`async${suffix}Active`].value = false
      expect(items.value.filter((item: any) => item.kind === kind)).toEqual([
        expect.objectContaining({ id: 7, progress: 41 })
      ])
    })

  it('cancels an inbox task through its own action after the local watch stops', async () => {
    const { ports } = setup()
    const result = { taskId: 7, cancellationOutcome: 'ACCEPTED' }
    Object.assign(ports, {
      cancelWatchedSimulation: vi.fn(),
      simulationApi: { cancelTask: vi.fn().mockResolvedValue(result) }
    })
    ports.asyncSimulationActive.value = false

    await bind('cancelSimulationTaskFromInbox', ports)(7)

    expect(ports.simulationApi.cancelTask).toHaveBeenCalledExactlyOnceWith(7)
    expect(ports.cancelWatchedSimulation).not.toHaveBeenCalled()
    expect(ports.notifyTaskCancellationResult).toHaveBeenCalledExactlyOnceWith('simulation', result)
  })

  it.each([
    ['Verification', 'boardApi'], ['Simulation', 'simulationApi'], ['Fuzzing', 'fuzzingApi']
  ] as const)('shares a pending inbox %s cancellation when switching to its progress', async (suffix, api) => {
    const { ports, i18n, items } = setup()
    const kind = suffix.toLowerCase()
    const accepted = { taskId: 7, cancellationAccepted: true, cancellationOutcome: 'ACCEPTED', taskStatus: 'CANCELLED' }
    let respond!: (result: typeof accepted) => void
    const pending = new Promise<typeof accepted>(resolve => { respond = resolve })
    ports[api] = { cancelTask: vi.fn().mockReturnValueOnce(pending).mockResolvedValue(accepted) }
    const watching = suffix === 'Fuzzing' ? ports.isFuzzing : ports[`async${suffix}Active`]
    watching.value = false

    const requestedFromInbox = bind(`cancel${suffix}TaskFromInbox`, ports)(7)
    watching.value = true
    const requestedFromProgress = bind(`cancelAsync${suffix}`, ports)()
    try {
      expect(ports[api].cancelTask).toHaveBeenCalledExactlyOnceWith(7)
      expect(ports[`cancelling${suffix}Task`].value).toBe(true)
      expect(items.value.find((item: any) => item.kind === kind).status).toBe(i18n.global.t('app.taskCancelling'))
    } finally {
      respond(accepted)
      await Promise.all([requestedFromInbox, requestedFromProgress])
    }
    expect(ports[`cancelling${suffix}Task`].value).toBe(false)
  })

  it('marks the inbox row pending when the same task was cancelled from progress', async () => {
    const { ports } = setup()
    const accepted = { taskId: 7, cancellationAccepted: true, cancellationOutcome: 'ACCEPTED', taskStatus: 'CANCELLED' }
    let respond!: (result: typeof accepted) => void
    ports.simulationApi = { cancelTask: vi.fn(() => new Promise<typeof accepted>(resolve => { respond = resolve })) }
    const requestedFromProgress = bind('cancelAsyncSimulation', ports)()
    try {
      ports.simulationPanelTracksRun.value = false
      expect(ports.pendingTaskActionKeys.value.has(ports.taskActionKey('cancel', 'simulation', 7))).toBe(true)
    } finally {
      respond(accepted)
      await requestedFromProgress
    }
    expect(ports.pendingTaskActionKeys.value.size).toBe(0)
  })

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
      resolve({ taskId: 7, cancellationAccepted: true, cancellationOutcome: 'ACCEPTED', taskStatus: 'CANCELLED' })
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
    const status = () => items.value.find((entry: any) => entry.kind === kind).status
    await bind(handler, ports)()
    expect(status()).toBe(i18n.global.t('app.taskProgressStage_GENERATING_MODEL'))
    expect(ports.acknowledgeFailure).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      cause: 'gateway unavailable', suggestion: i18n.global.t('app.cancelFailureNextStep')
    }))
    expect(ports.notifyError).not.toHaveBeenCalled()
    expect(ports[`${kind}CancelRequested`].value).toBe(false)
    i18n.global.locale.value = 'zh-CN'
    expect(status()).toBe(i18n.global.t('app.taskProgressStage_GENERATING_MODEL'))
  })

  it.each([
    ['verification', 'cancelAsyncVerification', 'boardApi'],
    ['simulation', 'cancelAsyncSimulation', 'simulationApi'],
    ['fuzzing', 'cancelAsyncFuzzing', 'fuzzingApi']
  ])('uses the server-confirmed %s terminal status when cancellation loses the race', async (kind, handler, api) => {
    const { ports, i18n, items } = setup()
    ports[api] = { cancelTask: vi.fn().mockResolvedValue({ taskId: 7, cancellationAccepted: false,
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
    const pending = bind(handler, ports)()
    ports[`async${kind[0]!.toUpperCase() + kind.slice(1)}Task`].value.statusKey = 'app.taskProgressStage_PERSISTING_RESULT'
    reject(new Error('late cancellation response'))
    await pending
    expect(items.value.find((entry: any) => entry.kind === kind).status).toBe(i18n.global.t('app.taskProgressStage_PERSISTING_RESULT'))
  })

  it.each([
    { suffix: 'Verification', api: 'boardApi', transition: 'leaving progress' },
    { suffix: 'Simulation', api: 'simulationApi', transition: 'returning to progress from the inbox' },
    { suffix: 'Fuzzing', api: 'fuzzingApi', transition: 'opening a history result' }
  ] as const)('reports a late $suffix cancellation failure without taking focus after $transition', async ({ suffix, api, transition }) => {
    const { ports, i18n } = setup()
    const kind = suffix.toLowerCase()
    const panelTracksRun = ports[`${kind}PanelTracksRun`]
    let reject!: (error: Error) => void
    ports[api] = { cancelTask: vi.fn(() => new Promise((_resolve, fail) => { reject = fail })) }
    if (transition === 'returning to progress from the inbox') panelTracksRun.value = false

    const pending = bind(`cancelAsync${suffix}`, ports)()
    if (transition === 'leaving progress') panelTracksRun.value = false
    if (transition === 'returning to progress from the inbox') panelTracksRun.value = true
    if (transition === 'opening a history result') ports.historyDetailRequests.beginReplace()
    reject(new Error('gateway unavailable'))
    await pending

    expect(ports.acknowledgeFailure).not.toHaveBeenCalled()
    expect(ports.notifyError).toHaveBeenCalledExactlyOnceWith(expect.stringContaining(
      i18n.global.t('app.cancelFailureNextStep')))
    expect(ports[`${kind}CancelRequested`].value).toBe(false)
    expect(ports[`cancelling${suffix}Task`].value).toBe(false)
  })
})
