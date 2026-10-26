import { describe, expect, it, vi } from 'vitest'
import {
  BoardMutationAdmissionCancelledError,
  collectBundledEnvironmentNames,
  clampFloatingMenuPosition,
  confirmHistoryDeletion,
  continueAfterDeviceDialogApproval,
  getConfirmedBoardItemStatus,
  createLatestBoardRequestGuard,
  createScopedBoardInvalidationBinding,
  focusCollapsedNarrowPanelToggle,
  formalRunReadinessIssue,
  handleRecommendationApplySceneChange,
  hasRecommendationSceneChanged,
  hasFrozenBundledTokenSource,
  invalidateFuzzingResultRequests,
  isPersistedHistoryDataInvalid,
  persistedHistoryInvalidRecordId,
  loadBoardResultWithRetry,
  reconcileBoardNodeSnapshot,
  reconcileRenameDialogSnapshot,
  recommendationSceneFingerprint,
  prepareBoardChatInteraction,
  revalidateHistoricalPlaybackAdmission,
  retainSessionUnavailableHistoryItems,
  runAdmittedBoardMutation,
  runTrackedBoardMutation,
  resolveCurrentBoardNode,
  resolveDeletionReviewDeviceDialogRestore,
  shouldReportUnusableHistoryDeepLink,
  shouldLoadVerificationEvidence,
  shouldResolveLiveTemplateForPresentation,
  shouldClearUnusableHistoryDeepLink,
  shouldRedirectNarrowPanelFocus,
  verificationTraceBelongsToRun,
  fuzzingFindingBelongsToRun,
  verificationRunContainsTrace
} from './Board.vue'
import { createPagedRequestCoordinator } from '@/utils/pagedRequestCoordinator'
import {
  isRecommendationPostOutcomeUnknown,
  isRecommendationRequestActive,
  markRecommendationResponseReceived,
  planRecommendationRecoveryAfterStatusFailure,
  prepareOwnedRecommendationForLogout,
  refreshRecommendationOwnerCredential,
  requestIdAfterTerminalSettlement
} from '@/utils/recommendationRequestRecovery'
import { sceneTemplatesCoveredByCatalog } from '@/utils/sceneTemplateCoverage'

describe('Board formal-run readiness', () => {
  const ready = {
    deviceCount: 1,
    specificationCount: 1,
    rulesHaveTriggers: true,
    simulationStepsValid: true,
    specVariableSourcesResolved: true
  }

  it('mirrors verification prerequisites before attack configuration is considered', () => {
    expect(formalRunReadinessIssue('verification', { ...ready, deviceCount: 0 }))
      .toBe('NO_DEVICES')
    expect(formalRunReadinessIssue('verification', { ...ready, specificationCount: 0 }))
      .toBe('NO_SPECIFICATIONS')
    expect(formalRunReadinessIssue('verification', { ...ready, rulesHaveTriggers: false }))
      .toBe('RULE_TRIGGER_REQUIRED')
    // An unresolved variable source blocks both kinds of run: the request would be refused at
    // admission, and neither reading can be assumed on the author's behalf.
    expect(formalRunReadinessIssue('verification', { ...ready, specVariableSourcesResolved: false }))
      .toBe('SPEC_VARIABLE_SOURCE_REQUIRED')
    expect(formalRunReadinessIssue('simulation', { ...ready, specVariableSourcesResolved: false }))
      .toBe('SPEC_VARIABLE_SOURCE_REQUIRED')
    expect(formalRunReadinessIssue('verification', ready)).toBeNull()
  })

  it('uses simulation steps instead of requiring specifications', () => {
    expect(formalRunReadinessIssue('simulation', { ...ready, specificationCount: 0 }))
      .toBeNull()
    expect(formalRunReadinessIssue('simulation', { ...ready, simulationStepsValid: false }))
      .toBe('INVALID_SIMULATION_STEPS')
  })
})

describe('Board history deletion confirmation', () => {
  it('keeps an in-flight detail request current when deletion is cancelled', async () => {
    const invalidate = vi.fn()

    // Cancelling is an ordinary outcome reported as `false`, not a thrown rejection.
    await expect(confirmHistoryDeletion(() => Promise.resolve(false), invalidate))
      .resolves.toBe(false)

    expect(invalidate).not.toHaveBeenCalled()
  })

  it('invalidates in-flight detail requests only after deletion is confirmed', async () => {
    const invalidate = vi.fn()

    await expect(confirmHistoryDeletion(() => Promise.resolve(true), invalidate))
      .resolves.toBe(true)

    expect(invalidate).toHaveBeenCalledOnce()
  })
})

describe('Board persisted history availability', () => {
  it('marks only an explicit persisted semantic-data rejection as permanently unavailable', () => {
    expect(isPersistedHistoryDataInvalid({
      response: {
        data: {
          data: { reasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID' }
        }
      }
    })).toBe(true)
    expect(isPersistedHistoryDataInvalid({
      response: {
        data: {
          data: { reasonCode: 'SERVICE_UNAVAILABLE' }
        }
      }
    })).toBe(false)
    expect(isPersistedHistoryDataInvalid(new Error('network timeout'))).toBe(false)
  })

  it('uses a persisted record id only when the response identifies the expected record type', () => {
    const findingError = {
      response: {
        data: {
          data: {
            reasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID',
            recordType: 'FuzzFinding',
            recordId: '42'
          }
        }
      }
    }

    expect(persistedHistoryInvalidRecordId(findingError, 'FuzzFinding')).toBe(42)
    expect(persistedHistoryInvalidRecordId(findingError, 'FuzzTask')).toBeNull()
    expect(persistedHistoryInvalidRecordId({
      response: {
        data: {
          data: {
            reasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID',
            recordType: 'verification trace',
            recordId: 71
          }
        }
      }
    }, 'verification trace')).toBe(71)
    expect(persistedHistoryInvalidRecordId({
      response: { data: { data: { reasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID', recordType: 'FuzzFinding', recordId: 'zero' } } }
    }, 'FuzzFinding')).toBeNull()
  })

  it('strips a deep link only for an explicit persisted-data rejection', () => {
    const persistedDataError = {
      response: {
        data: {
          data: { reasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID' }
        }
      }
    }

    expect(shouldClearUnusableHistoryDeepLink(true, persistedDataError)).toBe(true)
    expect(shouldClearUnusableHistoryDeepLink(false, persistedDataError)).toBe(false)
    expect(shouldClearUnusableHistoryDeepLink(true, new Error('network timeout'))).toBe(false)
  })

  it('keeps confirmed unavailable history items unavailable after a summary refresh', () => {
    const summaries: Array<{ id: number; dataAvailable: boolean }> = [
      { id: 31, dataAvailable: true },
      { id: 32, dataAvailable: true }
    ]

    expect(retainSessionUnavailableHistoryItems(
      summaries,
      new Set([32]),
      summary => ({ ...summary, dataAvailable: false })
    )).toEqual([
      { id: 31, dataAvailable: true },
      { id: 32, dataAvailable: false }
    ])
  })

  it('keeps a history link retryable for transient reads but clears confirmed dead targets', () => {
    expect(shouldReportUnusableHistoryDeepLink(true, new Error('network timeout'))).toBe(false)
    expect(shouldReportUnusableHistoryDeepLink(true, { response: { status: 400 } })).toBe(false)
    expect(shouldReportUnusableHistoryDeepLink(true, { response: { status: 503 } })).toBe(false)
    expect(shouldReportUnusableHistoryDeepLink(true, { response: { status: 429 } })).toBe(false)
    expect(shouldReportUnusableHistoryDeepLink(true, { response: { status: 404 } })).toBe(true)
    expect(shouldReportUnusableHistoryDeepLink(false, { response: { status: 404 } })).toBe(false)
  })

  it('rejects a trace URL whose child evidence is not in the named verification run', () => {
    expect(verificationRunContainsTrace({ traces: [{ id: 71 }] }, 71)).toBe(true)
    expect(verificationRunContainsTrace({ traces: [{ id: 71 }] }, 72)).toBe(false)
    expect(verificationRunContainsTrace({ traces: [{}] }, 71)).toBe(false)
    expect(verificationRunContainsTrace(undefined, 71)).toBe(false)
  })

  it('keeps leaf replay scoped to the run encoded in the history URL', () => {
    expect(verificationTraceBelongsToRun({ verificationTaskId: 71 }, 71)).toBe(true)
    expect(verificationTraceBelongsToRun({ verificationTaskId: 72 }, 71)).toBe(false)
    expect(fuzzingFindingBelongsToRun({ fuzzTaskId: 81 }, 81)).toBe(true)
    expect(fuzzingFindingBelongsToRun({ fuzzTaskId: 82 }, 81)).toBe(false)
  })

  it('loads saved evidence by its count rather than treating an inconclusive conclusion as empty', () => {
    expect(shouldLoadVerificationEvidence(1)).toBe(true)
    expect(shouldLoadVerificationEvidence(0)).toBe(false)
    expect(shouldLoadVerificationEvidence(-1)).toBe(false)
    expect(shouldLoadVerificationEvidence(1.5)).toBe(false)
  })
})

describe('Board historical playback admission', () => {
  it('waits for the existing mutation queue before admitting playback', async () => {
    let releaseMutation!: () => void
    const pendingMutation = new Promise<void>(resolve => { releaseMutation = resolve })
    const recheckUiAdmission = vi.fn(() => true)

    const admission = revalidateHistoricalPlaybackAdmission({
      waitForPendingMutations: () => pendingMutation,
      isRequestCurrent: () => true,
      initialMutationEpoch: 3,
      currentMutationEpoch: () => 3,
      recheckUiAdmission
    })

    expect(recheckUiAdmission).not.toHaveBeenCalled()
    releaseMutation()
    await expect(admission).resolves.toBe('admitted')
    expect(recheckUiAdmission).toHaveBeenCalledOnce()
  })

  it('rejects playback when a board mutation was enqueued while history loaded', async () => {
    let mutationEpoch = 7
    let releaseMutation!: () => void
    const pendingMutation = new Promise<void>(resolve => { releaseMutation = resolve })
    const recheckUiAdmission = vi.fn(() => true)
    const admission = revalidateHistoricalPlaybackAdmission({
      waitForPendingMutations: () => pendingMutation,
      isRequestCurrent: () => true,
      initialMutationEpoch: mutationEpoch,
      currentMutationEpoch: () => mutationEpoch,
      recheckUiAdmission
    })

    mutationEpoch += 1
    releaseMutation()

    await expect(admission).resolves.toBe('board-changed')
    expect(recheckUiAdmission).not.toHaveBeenCalled()
  })

  it('rejects playback when an editor opens before the loaded history is presented', async () => {
    let editorOpen = false
    let releaseMutation!: () => void
    const pendingMutation = new Promise<void>(resolve => { releaseMutation = resolve })
    const admission = revalidateHistoricalPlaybackAdmission({
      waitForPendingMutations: () => pendingMutation,
      isRequestCurrent: () => true,
      initialMutationEpoch: 11,
      currentMutationEpoch: () => 11,
      recheckUiAdmission: () => !editorOpen
    })

    editorOpen = true
    releaseMutation()

    await expect(admission).resolves.toBe('ui-blocked')
  })

  it('rejects playback when its detail request becomes stale while mutations drain', async () => {
    let requestCurrent = true
    let releaseMutation!: () => void
    const pendingMutation = new Promise<void>(resolve => { releaseMutation = resolve })
    const recheckUiAdmission = vi.fn(() => true)
    const admission = revalidateHistoricalPlaybackAdmission({
      waitForPendingMutations: () => pendingMutation,
      isRequestCurrent: () => requestCurrent,
      initialMutationEpoch: 5,
      currentMutationEpoch: () => 5,
      recheckUiAdmission
    })

    requestCurrent = false
    releaseMutation()

    await expect(admission).resolves.toBe('request-stale')
    expect(recheckUiAdmission).not.toHaveBeenCalled()
  })

  it('does not wait on the mutation queue after the history request becomes stale', async () => {
    const waitForPendingMutations = vi.fn(() => Promise.resolve())

    await expect(revalidateHistoricalPlaybackAdmission({
      waitForPendingMutations,
      isRequestCurrent: () => false,
      initialMutationEpoch: 2,
      currentMutationEpoch: () => 2,
      recheckUiAdmission: () => true
    })).resolves.toBe('request-stale')

    expect(waitForPendingMutations).not.toHaveBeenCalled()
  })
})

describe('Board historical playback presentation', () => {
  it('never resolves the live template catalog while historical evidence is open', () => {
    expect(shouldResolveLiveTemplateForPresentation(true)).toBe(false)
    expect(shouldResolveLiveTemplateForPresentation(false)).toBe(true)
  })
})

describe('Board chat interaction preparation', () => {
  it('closes read-only playback before admitting a new assistant turn', () => {
    const closeTracePlayback = vi.fn()
    const closeSimulationPlayback = vi.fn()

    expect(prepareBoardChatInteraction({
      sceneReplacementInProgress: false,
      tracePlaybackVisible: true,
      simulationPlaybackVisible: true,
      closeTracePlayback,
      closeSimulationPlayback
    })).toBe(true)
    expect(closeTracePlayback).toHaveBeenCalledOnce()
    expect(closeSimulationPlayback).toHaveBeenCalledOnce()
  })

  it('keeps the assistant blocked during an atomic scene replacement', () => {
    const closeTracePlayback = vi.fn()
    const closeSimulationPlayback = vi.fn()

    expect(prepareBoardChatInteraction({
      sceneReplacementInProgress: true,
      tracePlaybackVisible: true,
      simulationPlaybackVisible: true,
      closeTracePlayback,
      closeSimulationPlayback
    })).toBe(false)
    expect(closeTracePlayback).not.toHaveBeenCalled()
    expect(closeSimulationPlayback).not.toHaveBeenCalled()
  })
})

describe('Board recommendation scene ownership', () => {
  const scene = {
    deviceTemplates: [
      { id: 1, name: 'Switch', manifest: { Name: 'Switch', Modes: ['on', 'off'] } },
      { id: 2, name: 'Sensor', manifest: { Name: 'Sensor', Modes: [] } }
    ],
    nodes: [
      {
        id: 'switch-1',
        templateName: 'Switch',
        label: 'Hall switch',
        state: 'off',
        position: { x: 10, y: 20 },
        width: 176,
        height: 128,
        variables: [
          { name: 'power', value: '0' },
          { name: 'voltage', value: '220' }
        ]
      }
    ],
    environmentVariables: [
      { name: 'temperature', value: '20', trust: 'trusted', privacy: 'public' },
      { name: 'humidity', value: '40', trust: 'trusted', privacy: 'public' }
    ],
    rules: [
      { id: '1', name: 'First rule' },
      { id: '2', name: 'Second rule' }
    ],
    specifications: [
      { id: 'spec-1', templateId: '1' },
      { id: 'spec-2', templateId: '2' }
    ]
  }

  it('keeps recommendations for layout-only changes and unordered response collections', () => {
    const reordered = {
      ...scene,
      deviceTemplates: [...scene.deviceTemplates].reverse(),
      nodes: scene.nodes.map(node => ({
        ...node,
        position: { x: 900, y: 500 },
        width: 80,
        height: 60,
        variables: [...node.variables].reverse()
      })),
      environmentVariables: [...scene.environmentVariables].reverse(),
      specifications: [...scene.specifications].reverse()
    }

    expect(hasRecommendationSceneChanged(scene, reordered)).toBe(false)
  })

  it('fences an old recommendation after a changed authoritative refresh', () => {
    const requestGuard = createLatestBoardRequestGuard()
    const requestEpoch = requestGuard.begin()
    const refreshed = {
      ...scene,
      nodes: scene.nodes.map(node => ({ ...node, state: 'on' }))
    }

    if (hasRecommendationSceneChanged(scene, refreshed)) requestGuard.invalidate()

    expect(requestGuard.isCurrent(requestEpoch)).toBe(false)
  })

  it('preserves the checked model for requirement renames but detects condition edits', () => {
    const named = { ...scene, specifications: scene.specifications.map(spec => ({ ...spec, name: 'Door safety' })) }
    const renamed = { ...named, specifications: named.specifications.map(spec => ({ ...spec, name: 'Prevent unexpected unlock' })) }
    expect(hasRecommendationSceneChanged(named, renamed)).toBe(false)
    const condition = { id: 'editor-only', side: 'a', deviceId: 'door', targetType: 'state', key: 'state', relation: '=', value: 'locked' }
    const saved = { ...named, specifications: [{ ...named.specifications[0], aConditions: [condition] }] }
    expect(hasRecommendationSceneChanged(saved, { ...saved, specifications: [{ ...saved.specifications[0],
      aConditions: [{ ...condition, id: null }]
    }] })).toBe(false)
    expect(hasRecommendationSceneChanged(named, { ...renamed, specifications: [
      { ...renamed.specifications[0], aConditions: [{ key: 'state', value: 'unlocked' }] }
    ] })).toBe(true)
  })

  it('treats rule execution-order changes as semantic changes', () => {
    expect(hasRecommendationSceneChanged(scene, {
      ...scene,
      rules: [...scene.rules].reverse()
    })).toBe(true)
  })

  it('does not invalidate on the initial authoritative hydration', () => {
    expect(hasRecommendationSceneChanged(null, scene)).toBe(false)
  })

  it('does not start a queued mutation after its recommendation generation expires', async () => {
    let sceneGeneration = 4
    const requestSceneGeneration = sceneGeneration
    const addNodes = vi.fn().mockResolvedValue({})
    sceneGeneration += 1

    await expect(runAdmittedBoardMutation(
      addNodes,
      () => requestSceneGeneration === sceneGeneration
    )).rejects.toBeInstanceOf(BoardMutationAdmissionCancelledError)
    expect(addNodes).not.toHaveBeenCalled()
  })

  it('expires an in-flight recommendation after a local environment change', async () => {
    let currentScene = scene
    let recommendationGeneration = 7
    const requestGeneration = recommendationGeneration

    await runTrackedBoardMutation(
      async () => {
        currentScene = {
          ...currentScene,
          environmentVariables: currentScene.environmentVariables.map(variable =>
            variable.name === 'temperature' ? { ...variable, value: '21' } : variable)
        }
      },
      recommendationSceneFingerprint(currentScene),
      () => recommendationSceneFingerprint(currentScene),
      () => { recommendationGeneration += 1 }
    )

    expect(requestGeneration).not.toBe(recommendationGeneration)
  })

  it('invalidates displayed recommendations after a local device or rule change', async () => {
    let currentScene = scene
    const invalidate = vi.fn()

    await runTrackedBoardMutation(
      async () => {
        currentScene = {
          ...currentScene,
          nodes: currentScene.nodes.map(node => ({ ...node, state: 'on' })),
          rules: [...currentScene.rules, { id: '3', name: 'Third rule' }]
        }
      },
      recommendationSceneFingerprint(currentScene),
      () => recommendationSceneFingerprint(currentScene),
      invalidate
    )

    expect(invalidate).toHaveBeenCalledOnce()
  })

  it('does not mark a failed recommendation as applied after an unrelated refresh changes the scene', async () => {
    let currentScene = scene
    const preserveApplied = vi.fn()
    const invalidate = vi.fn()

    await runTrackedBoardMutation(
      async () => {
        currentScene = {
          ...currentScene,
          environmentVariables: currentScene.environmentVariables.map(variable =>
            variable.name === 'temperature' ? { ...variable, value: '23' } : variable)
        }
      },
      recommendationSceneFingerprint(currentScene),
      () => recommendationSceneFingerprint(currentScene),
      () => handleRecommendationApplySceneChange(false, preserveApplied, invalidate)
    )

    expect(preserveApplied).not.toHaveBeenCalled()
    expect(invalidate).toHaveBeenCalledOnce()
  })

  it('retains only the applied confirmation after a confirmed recommendation changes the scene', async () => {
    let currentScene = scene
    const preserveApplied = vi.fn()
    const invalidate = vi.fn()

    await runTrackedBoardMutation(
      async () => {
        currentScene = {
          ...currentScene,
          rules: [...currentScene.rules, { id: '3', name: 'Applied recommendation' }]
        }
      },
      recommendationSceneFingerprint(currentScene),
      () => recommendationSceneFingerprint(currentScene),
      () => handleRecommendationApplySceneChange(true, preserveApplied, invalidate)
    )

    expect(preserveApplied).toHaveBeenCalledOnce()
    expect(invalidate).not.toHaveBeenCalled()
  })
})

describe('Board recommendation request recovery', () => {
  it('keeps a no-response POST outcome active and blocks allocation of a replacement id', () => {
    expect(isRecommendationPostOutcomeUnknown(new Error('transport lost'))).toBe(true)
    expect(isRecommendationRequestActive(false, 'request-with-unknown-outcome')).toBe(true)
    expect(isRecommendationRequestActive(false, null)).toBe(false)
  })

  it('treats HTTP and validated-response failures as terminal response evidence', () => {
    expect(isRecommendationPostOutcomeUnknown({ response: { status: 503 } })).toBe(false)
    expect(isRecommendationPostOutcomeUnknown(
      markRecommendationResponseReceived(new Error('invalid response body'))
    )).toBe(false)
  })

  it('retains the owner token across an account switch but accepts same-owner renewal', () => {
    const owner = { userId: 7, authToken: 'alice-original-token' }

    expect(refreshRecommendationOwnerCredential(owner, 8, 'bob-token')).toBe(owner)
    expect(refreshRecommendationOwnerCredential(owner, 7, 'alice-renewed-token')).toEqual({
      userId: 7,
      authToken: 'alice-renewed-token'
    })
  })

  it('accepts FINISHED status during logout when cancellation returns false', async () => {
    const cancel = vi.fn().mockResolvedValue(false)
    const readStatus = vi.fn().mockResolvedValue({
      requestId: 'recommendation-logout-1',
      state: 'FINISHED',
      stage: 'FINALIZING',
      elapsedMs: 120
    })
    const onStatusFinished = vi.fn()

    await expect(prepareOwnedRecommendationForLogout({
      requestId: 'recommendation-logout-1',
      authToken: 'alice-owner-token',
      cancel,
      readStatus,
      waitBeforeRetry: vi.fn().mockResolvedValue(undefined),
      shouldContinue: () => true,
      hasTerminalEvidence: () => false,
      onStatusFinished,
      maxAttempts: 1
    })).resolves.toBe('ready')

    expect(cancel).toHaveBeenCalledWith('recommendation-logout-1', 'alice-owner-token')
    expect(readStatus).toHaveBeenCalledWith('recommendation-logout-1', 'alice-owner-token')
    expect(onStatusFinished).toHaveBeenCalledOnce()
  })

  it('keeps logout outcome unknown when owner-auth cancellation and status cannot prove a terminal state', async () => {
    const cancel = vi.fn().mockRejectedValue(new Error('network unavailable'))
    const readStatus = vi.fn().mockRejectedValue(new Error('network unavailable'))

    await expect(prepareOwnedRecommendationForLogout({
      requestId: 'recommendation-logout-2',
      authToken: 'alice-owner-token',
      cancel,
      readStatus,
      waitBeforeRetry: vi.fn().mockResolvedValue(undefined),
      shouldContinue: () => true,
      hasTerminalEvidence: () => false,
      maxAttempts: 1
    })).resolves.toBe('outcome-unknown')

    expect(cancel).toHaveBeenCalledWith('recommendation-logout-2', 'alice-owner-token')
    expect(readStatus).toHaveBeenCalledWith('recommendation-logout-2', 'alice-owner-token')
  })

  it('does not let an old POST settlement clear a newer request', () => {
    expect(requestIdAfterTerminalSettlement('new-request', 'old-request')).toBe('new-request')
    expect(requestIdAfterTerminalSettlement('old-request', 'old-request')).toBeNull()
  })

  it('never releases recommendation ownership because status reads keep failing', () => {
    let consecutiveFailures = 0
    let lastPlan = planRecommendationRecoveryAfterStatusFailure(consecutiveFailures)
    const firstRetryDelay = lastPlan.retryDelayMs

    for (let attempt = 1; attempt < 100; attempt += 1) {
      consecutiveFailures = lastPlan.consecutiveFailures
      lastPlan = planRecommendationRecoveryAfterStatusFailure(consecutiveFailures)
    }

    expect(lastPlan).toMatchObject({
      consecutiveFailures: 100,
      releaseTracking: false
    })
    expect(lastPlan.retryDelayMs).toBeGreaterThan(firstRetryDelay)
    expect(isRecommendationRequestActive(true, 'still-owned-request')).toBe(true)
  })
})

describe('Board node snapshot reconciliation', () => {
  it('adopts authoritative ordering and membership even when retained node values are equal', () => {
    const first = { id: 'a', position: { x: 1, y: 2 }, width: 100, height: 80 }
    const second = { ...first, id: 'b', position: { x: 3, y: 4 } }
    const current = [first, second]
    const reordered = reconcileBoardNodeSnapshot(current, structuredClone([second, first]), new Map(), new Set())
    expect(reordered).not.toBe(current)
    expect(reordered).toEqual([second, first])
    expect(reordered[0]).toBe(second)
    expect(current).toEqual([first, second])
    const removed = reconcileBoardNodeSnapshot(current, [structuredClone(second)], new Map(), new Set())
    expect(removed).toEqual([second])
    expect(removed[0]).toBe(second)
  })

  it('retains equal nested runtime records while adopting layout snapshots', () => {
    const current = {
      id: 'a', label: 'Device A', position: { x: 1, y: 2 }, width: 100, height: 80,
      variables: [{ name: 'temperature', value: '20', trust: null as string | null }],
      privacies: [{ name: 'photo', privacy: 'public' }]
    }
    const incoming = structuredClone(current)
    incoming.position = { x: 10, y: 20 }
    const variables = current.variables
    const privacies = current.privacies
    const [result] = reconcileBoardNodeSnapshot([current], [incoming], new Map(), new Set())

    expect(result).toBe(current)
    expect(result).toEqual(incoming)
    expect(result.variables).toBe(variables)
    expect(result.privacies).toBe(privacies)
    expect(result.position).not.toBe(incoming.position)
    const acknowledged = [current]
    expect(reconcileBoardNodeSnapshot(acknowledged, [structuredClone(current)], new Map(), new Set()))
      .toBe(acknowledged)

    const changed = structuredClone(incoming)
    changed.variables[0] = { name: 'temperature', value: '21', trust: 'untrusted' }
    changed.privacies[0].privacy = 'private'
    reconcileBoardNodeSnapshot([current], [changed], new Map(), new Set())
    expect(current).toEqual(changed)
    expect(current.variables).not.toBe(variables)
    expect(current.privacies).not.toBe(privacies)
    expect(incoming.variables[0].value).toBe('20')
  })

  it('preserves object identity and every pending or active layout while merging server fields', () => {
    const first = { id: 'a', label: 'Old A', position: { x: 1, y: 2 }, width: 100, height: 80 }
    const second = { id: 'b', label: 'Old B', position: { x: 3, y: 4 }, width: 110, height: 90 }
    const incoming = [
      { id: 'a', label: 'Server A', position: { x: 10, y: 20 }, width: 120, height: 100 },
      { id: 'b', label: 'Server B', position: { x: 30, y: 40 }, width: 130, height: 110 }
    ]
    const pending = new Map([['a', {
      layout: { position: { x: 50, y: 60 }, width: 140, height: 120 }
    }]])

    const result = reconcileBoardNodeSnapshot([first, second], incoming, pending, new Set(['b']))

    expect(result[0]).toBe(first)
    expect(result[1]).toBe(second)
    expect(result[0]).toMatchObject({
      label: 'Server A', position: { x: 50, y: 60 }, width: 140, height: 120
    })
    expect(result[1]).toMatchObject({
      label: 'Server B', position: { x: 3, y: 4 }, width: 110, height: 90
    })
  })

  it('uses the server layout once no local interaction owns the node', () => {
    const current = { id: 'a', position: { x: 1, y: 2 }, width: 100, height: 80 }
    const incoming = { id: 'a', position: { x: 10, y: 20 }, width: 120, height: 100 }

    const [result] = reconcileBoardNodeSnapshot([current], [incoming], new Map(), new Set())

    expect(result).toBe(current)
    expect(result).toMatchObject(incoming)
  })

  it('rebinds open surfaces to the current snapshot and invalidates removed nodes', () => {
    const stale = { id: 'a', label: 'Stale label' }
    const replacement = { id: 'a', label: 'Current label' }

    expect(resolveCurrentBoardNode([replacement], stale.id)).toBe(replacement)
    expect(resolveCurrentBoardNode([], stale.id)).toBeNull()
    expect(resolveCurrentBoardNode([replacement], null)).toBeNull()
  })
})

describe('Board invalidated deletion-review restoration', () => {
  const currentNode = { id: 'device-a', label: 'Renamed elsewhere' }

  it('restores the originating details only after a board change for the same live node', () => {
    expect(resolveDeletionReviewDeviceDialogRestore(
      'board-changed',
      [currentNode],
      'device-a',
      'device-a'
    )).toBe(currentNode)
  })

  it('does not reopen details after cancellation or a submitted deletion', () => {
    expect(resolveDeletionReviewDeviceDialogRestore(
      'cancelled',
      [currentNode],
      'device-a',
      'device-a'
    )).toBeNull()
    expect(resolveDeletionReviewDeviceDialogRestore(
      'submitted',
      [currentNode],
      'device-a',
      'device-a'
    )).toBeNull()
  })

  it('does not restore a non-dialog review, a different target, or a deleted node', () => {
    expect(resolveDeletionReviewDeviceDialogRestore(
      'board-changed',
      [currentNode],
      null,
      'device-a'
    )).toBeNull()
    expect(resolveDeletionReviewDeviceDialogRestore(
      'board-changed',
      [currentNode],
      'device-a',
      'device-b'
    )).toBeNull()
    expect(resolveDeletionReviewDeviceDialogRestore(
      'board-changed',
      [],
      'device-a',
      'device-a'
    )).toBeNull()
  })
})

describe('Board rename-dialog snapshot reconciliation', () => {
  it('preserves an unsaved name and its CAS baseline while rebinding the current node', () => {
    const stale = { id: 'a', label: 'Original' }
    const current = { id: 'a', label: 'Renamed elsewhere' }

    expect(reconcileRenameDialogSnapshot([current], {
      node: stale,
      newName: 'My draft',
      originalLabel: 'Original'
    })).toEqual({
      node: current,
      newName: 'My draft',
      originalLabel: 'Original'
    })
  })

  it('adopts the current label for an untouched draft and invalidates a removed device', () => {
    const stale = { id: 'a', label: 'Original' }
    const current = { id: 'a', label: 'Renamed elsewhere' }
    const draft = { node: stale, newName: 'Original', originalLabel: 'Original' }

    expect(reconcileRenameDialogSnapshot([current], draft)).toEqual({
      node: current,
      newName: 'Renamed elsewhere',
      originalLabel: 'Renamed elsewhere'
    })
    expect(reconcileRenameDialogSnapshot([], draft)).toBeNull()
  })

  it('does not replace device details with rename until the draft-close guard approves', async () => {
    const transition = vi.fn()
    const declined = {
      prepareClose: vi.fn().mockResolvedValue(false)
    }

    await expect(continueAfterDeviceDialogApproval(declined, transition)).resolves.toBe(false)
    expect(declined.prepareClose).toHaveBeenCalledOnce()
    expect(transition).not.toHaveBeenCalled()

    const approved = {
      prepareClose: vi.fn().mockResolvedValue(true)
    }
    await expect(continueAfterDeviceDialogApproval(approved, transition)).resolves.toBe(true)
    expect(transition).toHaveBeenCalledOnce()
  })

  it('does not bypass the draft-close guard when the async dialog instance is unavailable', async () => {
    const transition = vi.fn()

    await expect(continueAfterDeviceDialogApproval(null, transition)).resolves.toBe(false)
    expect(transition).not.toHaveBeenCalled()
  })
})

describe('Board destructive-confirmation fencing', () => {
  const originalRule = {
    id: 'rule_1',
    name: 'Turn on light',
    sources: [{ fromId: 'sensor_1', fromApi: 'motion', itemType: 'api' }],
    toId: 'light_1',
    toApi: 'on'
  }

  it('rejects a confirmation after the scene generation changes', () => {
    expect(getConfirmedBoardItemStatus(
      3,
      4,
      false,
      [originalRule],
      originalRule.id,
      originalRule
    )).toBe('scene-changed')
  })

  it('rejects a reused id when the item content changed during confirmation', () => {
    const replacement = { ...originalRule, name: 'Turn off light' }
    expect(getConfirmedBoardItemStatus(
      3,
      3,
      false,
      [replacement],
      originalRule.id,
      originalRule
    )).toBe('item-changed')
  })

  it('accepts the exact item snapshot when the scene is still current', () => {
    expect(getConfirmedBoardItemStatus(
      3,
      3,
      false,
      [{ ...originalRule }],
      originalRule.id,
      { ...originalRule }
    )).toBe('current')
  })

  it('can compare only authored fields when a server display cache changes', () => {
    const expected = {
      id: 'rule_1',
      name: 'Turn on light',
      formula: 'old display cache'
    }
    const current = {
      ...expected,
      formula: 'new display cache'
    }
    const authored = (rule: typeof expected) => ({ id: rule.id, name: rule.name })

    expect(getConfirmedBoardItemStatus(
      3,
      3,
      false,
      [current],
      expected.id,
      expected,
      authored
    )).toBe('current')
  })
})

describe('Board scene-template response reconciliation', () => {
  const sensor = { name: 'Sensor', manifest: { Name: 'Sensor' } }

  it('requires every referenced scene template to exist after a successful batch response', () => {
    expect(sceneTemplatesCoveredByCatalog([sensor], [], [])).toBe(false)
    expect(sceneTemplatesCoveredByCatalog([sensor], [], [{ ...sensor, id: 7 }])).toBe(true)
  })

  it('rejects a same-name template with different semantics and unrelated created templates', () => {
    expect(sceneTemplatesCoveredByCatalog(
      [sensor],
      [{ name: 'Sensor', manifest: { Name: 'Sensor', InitState: 'offline' } }],
      []
    )).toBe(false)
    expect(sceneTemplatesCoveredByCatalog(
      [sensor],
      [sensor],
      [{ name: 'Other', manifest: { Name: 'Other' } }]
    )).toBe(false)
  })

  it('treats omitted and explicit null optional manifest fields as one server-round-trip shape', () => {
    const requested = {
      name: 'Alarm',
      manifest: {
        Name: 'Alarm',
        APIs: [{ Name: 'off', StartState: 'on', EndState: 'off', Trigger: null, Signal: true }]
      }
    }
    const returned = {
      name: 'Alarm',
      manifest: {
        APIs: [{ Signal: true, EndState: 'off', Name: 'off', StartState: 'on' }],
        Name: 'Alarm'
      }
    }

    expect(sceneTemplatesCoveredByCatalog([requested], [], [{ ...returned, id: 7 }])).toBe(true)
    expect(sceneTemplatesCoveredByCatalog(
      [requested],
      [],
      [{
        ...returned,
        id: 7,
        manifest: {
          ...returned.manifest,
          APIs: [{ Name: 'off', StartState: 'on', EndState: 'off', Signal: false }]
        }
      }]
    )).toBe(false)
  })
})

describe('Board playback token provenance', () => {
  it('uses only frozen bundled provenance and never a current-template guess', () => {
    expect(hasFrozenBundledTokenSource({ modelTokenSource: 'BUNDLED' })).toBe(true)
    expect(hasFrozenBundledTokenSource({ modelTokenSource: 'CUSTOM' })).toBe(false)
    expect(hasFrozenBundledTokenSource({ modelTokenSource: 'UNKNOWN' })).toBe(false)
    expect(hasFrozenBundledTokenSource({})).toBe(false)
  })
})

describe('Board floating-menu positioning', () => {
  it('keeps the full menu inside every viewport edge', () => {
    expect(clampFloatingMenuPosition(
      { x: 995, y: 795 },
      { width: 220, height: 180 },
      { width: 1000, height: 800 }
    )).toEqual({ x: 772, y: 612 })
    expect(clampFloatingMenuPosition(
      { x: -20, y: -10 },
      { width: 220, height: 180 },
      { width: 1000, height: 800 }
    )).toEqual({ x: 8, y: 8 })
  })
})

describe('Board completed-result recovery', () => {
  it('retries transient detail failures and returns the recovered result', async () => {
    const load = vi.fn()
      .mockRejectedValueOnce(new Error('temporary 503'))
      .mockRejectedValueOnce(new Error('temporary network failure'))
      .mockResolvedValue({ id: 17 })
    const waitBeforeRetry = vi.fn().mockResolvedValue(undefined)

    await expect(loadBoardResultWithRetry({
      load,
      shouldRetry: () => true,
      waitBeforeRetry,
      maxAttempts: 3
    })).resolves.toEqual({ id: 17 })

    expect(load).toHaveBeenCalledTimes(3)
    expect(waitBeforeRetry).toHaveBeenNthCalledWith(1, 1)
    expect(waitBeforeRetry).toHaveBeenNthCalledWith(2, 2)
  })

  it('does not retry permanent response errors', async () => {
    const error = new Error('malformed response')
    const load = vi.fn().mockRejectedValue(error)
    const waitBeforeRetry = vi.fn().mockResolvedValue(undefined)

    await expect(loadBoardResultWithRetry({
      load,
      shouldRetry: () => false,
      waitBeforeRetry,
      maxAttempts: 3
    })).rejects.toBe(error)

    expect(load).toHaveBeenCalledTimes(1)
    expect(waitBeforeRetry).not.toHaveBeenCalled()
  })

  it('bounds retries when a transient detail endpoint stays unavailable', async () => {
    const error = new Error('still unavailable')
    const load = vi.fn().mockRejectedValue(error)
    const waitBeforeRetry = vi.fn().mockResolvedValue(undefined)

    await expect(loadBoardResultWithRetry({
      load,
      shouldRetry: () => true,
      waitBeforeRetry,
      maxAttempts: 3
    })).rejects.toBe(error)

    expect(load).toHaveBeenCalledTimes(3)
    expect(waitBeforeRetry).toHaveBeenCalledTimes(2)
  })
})

describe('Board model-fingerprint request ordering', () => {
  it('rejects older responses and invalidates in-flight requests on a model change', () => {
    const guard = createLatestBoardRequestGuard()
    const older = guard.begin()
    const newer = guard.begin()

    expect(guard.isCurrent(older)).toBe(false)
    expect(guard.isCurrent(newer)).toBe(true)

    guard.invalidate()
    expect(guard.isCurrent(newer)).toBe(false)
  })

  it('keeps a newer deep-link navigation current when an older one settles', () => {
    const guard = createLatestBoardRequestGuard()
    const firstDeepLink = guard.begin()
    const secondDeepLink = guard.begin()

    expect(guard.isCurrent(firstDeepLink)).toBe(false)
    expect(guard.isCurrent(secondDeepLink)).toBe(true)
  })
})

describe('Board fuzzing-result request ownership', () => {
  it('invalidates a pending detail response when the result surface closes', () => {
    const coordinator = createPagedRequestCoordinator()
    const pendingDetail = coordinator.beginReplace()

    const nextEpoch = invalidateFuzzingResultRequests(7, coordinator.invalidate)

    expect(nextEpoch).toBe(8)
    expect(coordinator.isCurrent(pendingDetail)).toBe(false)
  })
})

describe('Board invalidation ownership', () => {
  it('unsubscribes the previous account before binding the next account', () => {
    const aliceUnsubscribe = vi.fn()
    const bobUnsubscribe = vi.fn()
    const subscribe = vi.fn()
      .mockReturnValueOnce(aliceUnsubscribe)
      .mockReturnValueOnce(bobUnsubscribe)
    const listener = vi.fn()
    const binding = createScopedBoardInvalidationBinding(subscribe, listener)

    binding.bind(1)
    binding.bind(2)

    expect(subscribe).toHaveBeenNthCalledWith(1, 1, listener)
    expect(subscribe).toHaveBeenNthCalledWith(2, 2, listener)
    expect(aliceUnsubscribe).toHaveBeenCalledOnce()
    expect(bobUnsubscribe).not.toHaveBeenCalled()

    binding.dispose()
    expect(bobUnsubscribe).toHaveBeenCalledOnce()
  })
})

describe('Board model-token provenance', () => {
  it('localizes a shared environment token only when every declaring provider is bundled', () => {
    expect(collectBundledEnvironmentNames([
      { bundled: true, names: ['weather', 'temperature'] },
      { bundled: true, names: ['weather'] }
    ])).toEqual(['weather', 'temperature'])

    expect(collectBundledEnvironmentNames([
      { bundled: true, names: ['weather', 'temperature'] },
      { bundled: false, names: ['weather', 'customReading'] }
    ])).toEqual(['temperature'])
  })

  it('does not let an unresolved provider claim bundled provenance', () => {
    expect(collectBundledEnvironmentNames([
      { bundled: false, names: [] }
    ])).toEqual([])
  })
})

describe('Board narrow-panel focus isolation', () => {
  it('keeps focus in the visible drawer, scrim, navigation, or an active modal', () => {
    const root = document.createElement('div')
    const nav = document.createElement('nav')
    nav.className = 'board-nav-bar'
    const navButton = document.createElement('button')
    nav.append(navButton)

    const panel = document.createElement('aside')
    const panelButton = document.createElement('button')
    panel.append(panelButton)

    const scrim = document.createElement('button')
    const backgroundButton = document.createElement('button')
    const modal = document.createElement('div')
    modal.setAttribute('aria-modal', 'true')
    const modalButton = document.createElement('button')
    modal.append(modalButton)
    root.append(nav, panel, scrim, backgroundButton, modal)
    document.body.append(root)

    expect(shouldRedirectNarrowPanelFocus(true, panelButton, panel, scrim)).toBe(false)
    expect(shouldRedirectNarrowPanelFocus(true, scrim, panel, scrim)).toBe(false)
    expect(shouldRedirectNarrowPanelFocus(true, navButton, panel, scrim)).toBe(false)
    expect(shouldRedirectNarrowPanelFocus(true, modalButton, panel, scrim)).toBe(false)
    const preview = document.createElement('section')
    preview.dataset.testid = 'template-preview-1'
    preview.append(document.createElement('button'))
    root.append(preview)
    expect(shouldRedirectNarrowPanelFocus(true, preview, panel, scrim)).toBe(false)
    expect(shouldRedirectNarrowPanelFocus(true, preview.firstElementChild, panel, scrim)).toBe(false)
    expect(shouldRedirectNarrowPanelFocus(true, backgroundButton, panel, scrim)).toBe(true)
    expect(shouldRedirectNarrowPanelFocus(false, backgroundButton, panel, scrim)).toBe(false)

    root.remove()
  })

  it('restores focus to the collapsed toggle after either drawer closes', () => {
    const root = document.createElement('div')
    const control = document.createElement('aside')
    control.dataset.testid = 'control-center'
    control.className = 'is-collapsed'
    const controlToggle = document.createElement('button')
    control.append(controlToggle)

    const inspector = document.createElement('aside')
    inspector.dataset.testid = 'system-inspector'
    inspector.className = 'is-collapsed'
    const inspectorToggle = document.createElement('button')
    inspector.append(inspectorToggle)
    root.append(control, inspector)
    document.body.append(root)

    expect(focusCollapsedNarrowPanelToggle('control', root)).toBe(true)
    expect(document.activeElement).toBe(controlToggle)
    expect(focusCollapsedNarrowPanelToggle('inspector', root)).toBe(true)
    expect(document.activeElement).toBe(inspectorToggle)

    root.remove()
  })
})
