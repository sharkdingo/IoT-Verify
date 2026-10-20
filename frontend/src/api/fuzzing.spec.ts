import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./http', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn()
  }
}))

import http from './http'
import fuzzingApi from './fuzzing'
import { FUZZ_PAPER_SEMANTICS_CODES } from '@/types/fuzzing'

const envelope = (data: unknown) => ({ data: { data } })

const snapshot = {
  capturedAt: '2026-07-14T10:00:00',
  deviceCount: 2,
  ruleCount: 1,
  specificationCount: 1,
  environmentVariableCount: 1,
  deviceTemplateCount: 2,
  modelFingerprint: 'a'.repeat(64),
  templatesFrozen: true
}

const task = {
  id: 9,
  initiator: 'USER',
  explorationMode: 'BOARD_SNAPSHOT',
  status: 'PENDING',
  progress: 0,
  createdAt: '2026-07-14T10:00:00',
  modelSnapshot: snapshot,
  maxIterations: 500,
  pathLength: 20,
  populationSize: 10,
  targetSpecIds: ['spec-1']
}

describe('fuzzing API', () => {
  beforeEach(() => vi.clearAllMocks())

  it('previews the paper input domain without creating a task', async () => {
    vi.mocked(http.post).mockResolvedValue(envelope({
      pathLength: 20,
      modelFingerprint: 'a'.repeat(64),
      initializationPolicy: 'RANDOM_LEGAL_PER_SEED',
      paperSemanticsCodes: [...FUZZ_PAPER_SEMANTICS_CODES],
      deviceDomains: [],
      localVariableDomains: [],
      environmentDomains: []
    }))

    await expect(fuzzingApi.previewPaperDomain(20)).resolves.toMatchObject({ pathLength: 20 })
    expect(http.post).toHaveBeenCalledWith('/fuzz/paper-domain/preview', { pathLength: 20 })
  })

  it('uses the backend workload calculation for the current frozen model', async () => {
    vi.mocked(http.post).mockResolvedValue(envelope({
      maxIterations: 1300,
      pathLength: 4,
      populationSize: 50,
      explorationMode: 'BOARD_SNAPSHOT',
      modelComplexityUnits: 49,
      estimatedWorkload: 12_740_000,
      workloadLimit: 12_500_000,
      accepted: false,
      maxAcceptedIterations: 1_275
    }))
    const request = {
      maxIterations: 1300,
      pathLength: 4,
      populationSize: 50,
      explorationMode: 'BOARD_SNAPSHOT' as const
    }

    await expect(fuzzingApi.previewWorkload(request)).resolves.toMatchObject({
      modelComplexityUnits: 49,
      accepted: false
    })
    expect(http.post).toHaveBeenCalledWith('/fuzz/workload/preview', request)
  })

  it('submits only the requested background configuration', async () => {
    vi.mocked(http.post).mockResolvedValue(envelope(task))
    const request = {
      explorationMode: 'BOARD_SNAPSHOT' as const,
      targetSpecIds: ['spec-1'],
      maxIterations: 500,
      pathLength: 20,
      populationSize: 10,
      seed: 42
    }

    await expect(fuzzingApi.startAsync(request)).resolves.toMatchObject({ id: 9, status: 'PENDING' })
    expect(http.post).toHaveBeenCalledWith('/fuzz/async', request)
    await expect(fuzzingApi.startAsync({
      ...request,
      paperDomainFingerprint: 'a'.repeat(64)
    } as any)).rejects.toThrow('incompatible input-range data')
  })

  it('requires and forwards the paper-domain fingerprint for paper-compatible runs', async () => {
    vi.mocked(http.post).mockResolvedValue(envelope({
      ...task,
      explorationMode: 'PAPER_COMPATIBLE'
    }))
    const request = {
      explorationMode: 'PAPER_COMPATIBLE' as const,
      paperDomainFingerprint: 'b'.repeat(64),
      maxIterations: 10,
      pathLength: 8,
      populationSize: 4,
      seed: 23
    }

    await expect(fuzzingApi.startAsync(request)).resolves.toMatchObject({ explorationMode: 'PAPER_COMPATIBLE' })
    expect(http.post).toHaveBeenCalledWith('/fuzz/async', request)

    await expect(fuzzingApi.startAsync({
      ...request,
      paperDomainFingerprint: undefined
    } as any)).rejects.toThrow('random-state input range')
  })

  it('uses the accepted task id for polling and cancellation', async () => {
    vi.mocked(http.get)
      .mockResolvedValueOnce(envelope({ ...task, status: 'RUNNING', progress: 35 }))
    vi.mocked(http.post).mockResolvedValue(envelope({
      taskId: 9,
      cancellationAccepted: true,
      cancellationOutcome: 'ACCEPTED',
      taskStatus: 'CANCELLED',
      executionMayStillBeStopping: true
    }))

    await expect(fuzzingApi.getTask(9)).resolves.toMatchObject({ status: 'RUNNING' })
    await expect(fuzzingApi.cancelTask(9)).resolves.toMatchObject({ cancellationAccepted: true })
    expect(http.get).toHaveBeenCalledWith('/fuzz/tasks/9')
    expect(http.post).toHaveBeenCalledWith('/fuzz/tasks/9/cancel')
  })

  it('excludes the task already watched by the current board poller', async () => {
    vi.mocked(http.get).mockResolvedValue(envelope([]))

    await fuzzingApi.getTasks([9])

    expect(http.get).toHaveBeenCalledWith('/fuzz/tasks', {
      params: { excludeTaskIds: '9', page: 0, size: 100 }
    })
  })

  it('requests exactly one explicit run-history page', async () => {
    vi.mocked(http.get).mockResolvedValue(envelope([]))

    await fuzzingApi.getRuns(2, 25)

    expect(http.get).toHaveBeenCalledWith('/fuzz/runs', {
      params: { page: 2, size: 25 }
    })
  })

  it('loads run findings separately from the history summary', async () => {
    vi.mocked(http.get).mockResolvedValue(envelope([]))

    await fuzzingApi.getRunFindings(4)

    expect(http.get).toHaveBeenCalledWith('/fuzz/runs/4/findings')
  })

  it('loads one replayable finding with its frozen run context', async () => {
    vi.mocked(http.get).mockResolvedValue(envelope({
      finding: {
        id: 4,
        fuzzTaskId: 9,
        violatedSpecId: 'spec-1',
        violatedSpec: { id: 'spec-1' },
        firstViolationStep: 0,
        states: [{ stateIndex: 0, devices: [], triggeredRules: [], compromisedAutomationLinks: [] }],
        seed: 42,
        inputEvents: [],
        createdAt: '2026-07-14T10:00:01'
      },
      modelSnapshot: snapshot,
      playbackScene: {
        nodes: [
          { id: 'sensor_1', templateName: 'Sensor', label: 'Sensor', position: { x: 20, y: 30 }, state: 'on', width: 160, height: 120 },
          { id: 'alarm_1', templateName: 'Alarm', label: 'Alarm', position: { x: 360, y: 30 }, state: 'off', width: 160, height: 120 }
        ],
        rules: [{
          id: 7,
          conditions: [{ deviceName: 'sensor_1', attribute: 'state', targetType: 'state', relation: '=', value: 'on' }],
          command: { deviceName: 'alarm_1', action: 'on' },
          ruleString: 'Sensor turns on alarm'
        }]
      }
    }))

    await expect(fuzzingApi.getFinding(4)).resolves.toMatchObject({ finding: { id: 4 } })
    expect(http.get).toHaveBeenCalledWith('/fuzz/findings/4')
  })
})
