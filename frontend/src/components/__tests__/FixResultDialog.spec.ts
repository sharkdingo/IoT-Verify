// @vitest-environment jsdom
import { mount, type VueWrapper } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { defineComponent, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import FixResultDialog from '../FixResultDialog.vue'
import type { FixResult, FixStrategyAttemptStatus } from '@/types/fix'
import { useAuth } from '@/stores/auth'
import { FIX_RESPONSE_INCOMPLETE_CODE } from '@/utils/fixResponse'
import { openModalDepth, registerModalSurface } from '@/composables/useBodyScrollLock'

const elementPlus = vi.hoisted(() => ({
  confirm: vi.fn(),
  success: vi.fn(),
  // `notifyBlocked` and `notifyInfo` stay separate: sharing a spy would let a blocked-action
  // warning silently downgrade to an informational toast and still satisfy the assertions below.
  warning: vi.fn(),
  info: vi.fn(),
  error: vi.fn()
}))

vi.mock('@/utils/feedback', () => ({
  notifySuccess: elementPlus.success,
  notifyBlocked: elementPlus.warning,
  notifyInfo: elementPlus.info,
  notifyError: elementPlus.error,
  confirmDestructive: elementPlus.confirm
}))

const boardApi = vi.hoisted(() => ({
  getFaultRules: vi.fn(),
  getFixRequestStatus: vi.fn(),
  fixTrace: vi.fn(),
  cancelFixRequest: vi.fn(),
  applyFix: vi.fn()
}))

vi.mock('@/api/board', () => ({ default: boardApi }))

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      app: new Proxy({}, {
        get: (_target, key) => String(key)
      })
    }
  },
  missingWarn: false,
  fallbackWarn: false
})

const provenanceI18n = createI18n({
  legacy: false,
  locale: 'zh',
  messages: {
    zh: {
      app: {
        preferredRangeTargetLabel: '{description}',
        parameterTargetFallback: '调整 {attribute} {relation} {original}',
        relationEquals: '等于',
        relationNotEquals: '不等于',
        relationGreater: '大于',
        relationLess: '小于',
        relationGreaterEqual: '大于等于',
        relationLessEqual: '小于等于',
        relationIn: '属于',
        relationNotIn: '不属于',
        addConditionAdjustment: '向 {rule} 添加 {condition}',
        removeConditionAdjustment: '从 {rule} 删除 {condition}',
        keepConditionAdjustment: '在 {rule} 保留 {condition}',
        modelTokens: {
          workingState: '工作状态',
          off: '关闭'
        }
      }
    }
  },
  missingWarn: false,
  fallbackWarn: false
})

const flush = () => new Promise(resolve => setTimeout(resolve, 0))
const authStore = useAuth()
const validToken = (signature: string) => {
  const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `header.${payload}.${signature}`
}
const defaultToken = validToken('default-fix-owner')

const deferred = <T>() => {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}

const removeResult = (): FixResult => ({
  traceId: 7,
  violatedSpecId: 'spec-1',
  faultRules: [],
  suggestions: [{
    suggestionToken: 'signed-remove-suggestion',
    strategy: 'remove',
    description: 'Permanently remove the unsafe automation',
    parameterAdjustments: [],
    conditionAdjustments: [],
    removedRuleDescriptions: ['Gas leak unlocks exit'],
    preexistingViolations: []
  }],
  strategyAttempts: [{
    strategy: 'remove',
    status: 'VERIFIED',
    reason: 'Passed forward verification.',
    alternativesComplete: true
  }],
  fixable: true,
  sourceModelComplete: true,
  sourceDisabledRuleCount: 0,
  sourceSkippedSpecCount: 0,
  sourceGenerationIssues: [],
  templateSnapshotComparison: 'UNCHANGED',
  summary: 'One verified suggestion.',
  warnings: [],
  parameterTargets: [],
  unusedPreferredRangeSelections: []
})

const parameterResult = (): FixResult => ({
  ...removeResult(),
  suggestions: [{
    suggestionToken: 'signed-parameter-suggestion',
    strategy: 'parameter',
    description: 'Adjust the gas threshold',
    parameterAdjustments: [{
      targetId: 'param_abcdefghijklmnopqrstuvwx',
      attribute: 'gas',
      relation: '>',
      originalValue: '70',
      newValue: '85',
      lowerBound: 71,
      upperBound: 100,
      description: 'Gas threshold in the exit-unlock rule',
      modelTokenSource: 'BUNDLED'
    }],
    conditionAdjustments: [],
    removedRuleDescriptions: [],
    preexistingViolations: []
  }],
  strategyAttempts: [{
    strategy: 'parameter',
    status: 'VERIFIED',
    reason: 'Passed forward verification.',
    alternativesComplete: true
  }],
  parameterTargets: [{
    targetId: 'param_abcdefghijklmnopqrstuvwx',
    attribute: 'gas',
    relation: '>',
    originalValue: '70',
    lowerBound: 0,
    upperBound: 100,
    description: 'Gas threshold in the exit-unlock rule',
    modelTokenSource: 'BUNDLED'
  }]
})

const conditionResult = (): FixResult => ({
  ...removeResult(),
  suggestions: [{
    suggestionToken: 'signed-condition-suggestion',
    strategy: 'condition',
    description: 'Only heat while the room is occupied',
    parameterAdjustments: [],
    conditionAdjustments: [{
      action: 'add',
      attribute: 'occupied',
      targetType: 'variable',
      description: 'Require the living room to be occupied',
      ruleDescription: 'When it is cold, heat the room',
      deviceLabel: 'Living-room Occupancy Sensor',
      relation: '=',
      value: 'present',
      modelTokenSource: 'CUSTOM'
    }],
    removedRuleDescriptions: [],
    preexistingViolations: []
  }],
  strategyAttempts: [{
    strategy: 'condition',
    status: 'VERIFIED',
    reason: 'Passed forward verification.',
    alternativesComplete: true
  }]
})

const jointParameterResult = (): FixResult => {
  const result = parameterResult()
  const suggestion = result.suggestions[0]!
  const first = suggestion.parameterAdjustments[0]!
  const second = {
    ...first,
    targetId: 'param_zyxwvutsrqponmlkjihgfedc',
    originalValue: '72',
    newValue: '86',
    description: 'Backup gas threshold in the exit-unlock rule'
  }
  const { newValue: _newValue, ...secondTarget } = second
  return {
    ...result,
    suggestions: [{ ...suggestion, parameterAdjustments: [first, second] }],
    parameterTargets: [
      ...result.parameterTargets,
      secondTarget
    ]
  }
}

// Two independent verified repairs of one strategy, as the server lists them: smallest change first.
const parameterAlternativesResult = (alternativesComplete = true): FixResult => {
  const joint = jointParameterResult()
  const suggestion = joint.suggestions[0]!
  const [first, second] = suggestion.parameterAdjustments
  return {
    ...joint,
    suggestions: [
      { ...suggestion, suggestionToken: 'signed-parameter-option-1', parameterAdjustments: [first!] },
      { ...suggestion, suggestionToken: 'signed-parameter-option-2', parameterAdjustments: [second!] }
    ],
    strategyAttempts: [{ ...joint.strategyAttempts[0]!, alternativesComplete }]
  }
}

const jointConditionResult = (): FixResult => {
  const result = conditionResult()
  const suggestion = result.suggestions[0]!
  const first = suggestion.conditionAdjustments[0]!
  return {
    ...result,
    suggestions: [{
      ...suggestion,
      conditionAdjustments: [
        first,
        {
          ...first,
          ruleDescription: 'Backup heating rule',
          description: 'Require occupancy on the backup heating rule'
        }
      ]
    }]
  }
}

const jointRemoveResult = (): FixResult => {
  const result = removeResult()
  const suggestion = result.suggestions[0]!
  return {
    ...result,
    suggestions: [{
      ...suggestion,
      removedRuleDescriptions: ['Primary unsafe heating rule', 'Backup unsafe heating rule']
    }]
  }
}

const duplicateNameRemoveResult = (): FixResult => {
  const result = removeResult()
  return {
    ...result,
    suggestions: [{
      ...result.suggestions[0]!,
      removedRuleDescriptions: ['Shared automation name', 'Shared automation name']
    }]
  }
}

const boundaryParameterResult = (relation: '>' | '>='): FixResult => {
  const result = parameterResult()
  const adjustment = result.suggestions[0]!.parameterAdjustments[0]!
  return {
    ...result,
    suggestions: [{
      ...result.suggestions[0]!,
      parameterAdjustments: [{
        ...adjustment,
        relation,
        newValue: '100',
        upperBound: 100
      }]
    }]
  }
}

const conditionWithoutSuggestionResult = (): FixResult => ({
  ...removeResult(),
  suggestions: [],
  strategyAttempts: [{
    strategy: 'condition',
    status: 'NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE',
    reason: 'No combination of condition changes prevents this counterexample.'
  }],
  fixable: false
})

// A parameter search that listed nothing, keeping its target catalog, ended with `status`.
const parameterOutcomeResult = (status: FixStrategyAttemptStatus): FixResult => ({
  ...parameterResult(),
  suggestions: [],
  strategyAttempts: [{ strategy: 'parameter', status, reason: 'Server reason.' }],
  fixable: false
})

const parameterBudgetExhaustedResult = () => parameterOutcomeResult('SEARCH_BUDGET_EXHAUSTED')

// No rule that fired in the counterexample can influence the violated specification.
const noFaultRulesResult = (): FixResult => ({
  ...parameterOutcomeResult('SKIPPED_NO_FAULT_RULES'),
  parameterTargets: []
})

// The backend refuses to search an incomplete source model and skips the template snapshot comparison.
const incompleteSourceModelResult = (): FixResult => ({
  ...parameterOutcomeResult('SKIPPED_INCOMPLETE_SOURCE_MODEL'),
  parameterTargets: [],
  sourceModelComplete: false,
  sourceDisabledRuleCount: 1,
  sourceGenerationIssues: [{
    issueType: 'RULE_DISABLED',
    itemLabel: 'Gas leak unlocks exit',
    reasonCode: 'RULE_UNRESOLVABLE_COMMAND_ACTION',
    reason: 'Server reason.'
  }],
  templateSnapshotComparison: 'NOT_CHECKED'
})

const mountedDialogs: VueWrapper[] = []

const mountDialog = (i18nPlugin: any = i18n) => {
  const wrapper = mount(FixResultDialog, {
    props: { visible: true, traceId: 7, violatedSpecId: 'spec-1' },
    global: { plugins: [i18nPlugin] }
  })
  mountedDialogs.push(wrapper)
  return wrapper
}

const mountPersistentDialogHost = () => {
  const Host = defineComponent({
    components: { FixResultDialog },
    setup: () => ({
      dialogRef: ref<InstanceType<typeof FixResultDialog> | null>(null),
      traceId: ref(7),
      visible: ref(true)
    }),
    template: `
      <FixResultDialog
        ref="dialogRef"
        :visible="visible"
        :trace-id="traceId"
        violated-spec-id="spec-1"
        @update:visible="visible = $event"
      />
    `
  })
  const wrapper = mount(Host, { global: { plugins: [i18n] } })
  mountedDialogs.push(wrapper)
  return wrapper
}

// Opens the dialog and runs the default (parameter) strategy against `result`.
const tryParameter = async (result: FixResult) => {
  boardApi.fixTrace.mockResolvedValueOnce(result)
  const wrapper = mountDialog()
  await flush()
  await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
  await flush()
  await wrapper.vm.$nextTick()
  return wrapper
}

const alternativesChecked = (wrapper: VueWrapper) =>
  wrapper.findAll('[data-testid="fix-alternative"] input[type="radio"]')
    .map(radio => (radio.element as HTMLInputElement).checked)

const parameterApplied = (suggestion: FixResult['suggestions'][number]) => ({
  applied: true,
  strategy: 'parameter',
  verificationEvidenceReused: true,
  appliedSuggestion: suggestion,
  previousRuleCount: 1,
  currentRuleCount: 1,
  message: 'Changed one threshold.',
  rules: []
})

describe('FixResultDialog strategy workflow', () => {
  beforeEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
    // clearAllMocks keeps queued `mockResolvedValueOnce` values; a test that fails before consuming its
    // queue would hand them to the next test, turning one failure into a cascade.
    boardApi.fixTrace.mockReset()
    boardApi.applyFix.mockReset()
    authStore.logout()
    authStore.login(defaultToken, { userId: 1, phone: '13800138000', username: 'alice' })
    boardApi.getFaultRules.mockResolvedValue({
      traceId: 7,
      violatedSpecId: 'spec-1',
      sourceModelComplete: true,
      sourceDisabledRuleCount: 0,
      sourceSkippedSpecCount: 0,
      sourceGenerationIssues: [],
      faultRules: [],
      summary: 'No user-defined automation rule was localized.',
      warnings: []
    })
    boardApi.getFixRequestStatus.mockResolvedValue({
      requestId: 'request-123',
      state: 'RUNNING',
      stage: 'SEARCHING_AND_VERIFYING',
      elapsedMs: 1000
    })
    boardApi.cancelFixRequest.mockResolvedValue(true)
    elementPlus.confirm.mockResolvedValue(true)
  })

  afterEach(async () => {
    boardApi.cancelFixRequest.mockReset().mockResolvedValue(true)
    for (const wrapper of mountedDialogs.splice(0)) {
      const dialog = wrapper.findComponent(FixResultDialog)
      const exposed = dialog.exists() ? dialog.vm : wrapper.vm
      await (exposed as any).prepareForLogout?.()
      wrapper.unmount()
    }
    vi.useRealTimers()
    authStore.logout()
    await Promise.resolve()
  })

  it('opens with fault localization only and does not implicitly run an expensive strategy', async () => {
    const wrapper = mountDialog()
    await flush()

    expect(boardApi.getFaultRules).toHaveBeenCalledWith(7)
    expect(boardApi.fixTrace).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="fix-result-header"]').classes()).toContain('iot-dialog__header')
    // On open there is no attempt and no verified suggestion, so the surface states "nothing has been
    // tried yet" — informational, not a warning or a failure. The tone is now declared once on the card
    // and read by the header's icon tile, so it is asserted where it is set. Asserted as the role rather
    // than as a blue ramp with its `dark:` counterpart, which pinned the implementation and broke when
    // the theme-aware token replaced it.
    expect(wrapper.get('[role="dialog"]').classes()).toContain('iot-dialog--info')
    // `min-h-0` + `flex: 1` are `.iot-dialog__body`'s now — the constraint that makes the body the only
    // scrolling part and keeps the footer on screen.
    expect(wrapper.get('[data-testid="fix-result-scroll"]').classes()).toContain('iot-dialog__body')
    // The shared scroll primitive owns overflow and the scrollbar skin; a private per-dialog
    // scrollbar is what let three components disagree on the same class name.
    expect(wrapper.get('[data-testid="fix-result-scroll"]').classes()).toContain('iot-scroll-region')
    // The surface and its border come from the shared dialog layer's theme tokens; pinning
    // `dark:bg-slate-900` here made the test a copy of one theme's implementation.
    expect(wrapper.get('[role="dialog"]').classes()).toEqual(expect.arrayContaining([
      'iot-dialog',
      'iot-dialog--lg'
    ]))
    expect(wrapper.text()).toContain('faultLocalizationNoRuleCaveat')
    expect(wrapper.text()).not.toContain('No user-defined automation rule was localized.')
    // This dialog is raised *from* the verification result, so it must outrank an ordinary modal. That is now
    // the overlay's `--nested` modifier rather than an inline z-index utility.
    expect(wrapper.get('[data-testid="fix-result-dialog"]').classes())
      .toContain('iot-dialog-overlay--nested')
    expect(wrapper.find('[data-testid="fix-strategy-remove"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="fix-try-current"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(false)
  })

  it('does not dispatch a fix search without an initiating credential', async () => {
    authStore.logout()
    const wrapper = mountDialog()
    await flush()

    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')

    expect(boardApi.fixTrace).not.toHaveBeenCalled()
    // The selected strategy states the failure in its own panel; a toast would say it twice.
    expect(wrapper.text()).toContain('fixAuthenticationRequired')
    expect(elementPlus.error).not.toHaveBeenCalled()
  })

  it('localizes bundled fault actions while preserving custom and legacy collisions', async () => {
    boardApi.getFaultRules.mockResolvedValueOnce({
      traceId: 7,
      violatedSpecId: 'spec-1',
      sourceModelComplete: true,
      sourceDisabledRuleCount: 0,
      sourceSkippedSpecCount: 0,
      sourceGenerationIssues: [],
      faultRules: [
        {
          ruleString: 'Bundled action', transitionNumber: 1, targetDeviceLabel: 'Bundled',
          targetActionLabel: 'off', conflicting: false, reasonCode: 'TRIGGERED',
          reason: 'Triggered', modelTokenSource: 'BUNDLED'
        },
        {
          ruleString: 'Custom collision', transitionNumber: 2, targetDeviceLabel: 'Custom',
          targetActionLabel: 'off', conflicting: false, reasonCode: 'TRIGGERED',
          reason: 'Triggered', modelTokenSource: 'CUSTOM'
        },
        {
          ruleString: 'Legacy collision', transitionNumber: 3, targetDeviceLabel: 'Legacy',
          targetActionLabel: 'workingState', conflicting: false, reasonCode: 'TRIGGERED',
          reason: 'Triggered', modelTokenSource: 'UNKNOWN'
        }
      ],
      summary: 'Three actions.',
      warnings: []
    })
    const wrapper = mountDialog(provenanceI18n)
    await flush()
    await wrapper.vm.$nextTick()

    expect(wrapper.findAll('[data-testid="fix-fault-action"]').map(row => row.text()))
      .toEqual(['关闭', 'off', 'workingState'])
  })

  it('localizes condition relations without translating custom model tokens', async () => {
    const result = conditionResult()
    const base = result.suggestions[0]!.conditionAdjustments[0]!
    result.suggestions[0]!.conditionAdjustments = [
      {
        ...base,
        attribute: 'workingState',
        relation: 'not_in',
        value: 'off',
        modelTokenSource: 'BUNDLED'
      },
      {
        ...base,
        deviceLabel: 'Custom device',
        attribute: 'workingState',
        relation: 'not in',
        value: 'off',
        modelTokenSource: 'CUSTOM'
      }
    ]
    boardApi.fixTrace.mockResolvedValueOnce(result)
    const wrapper = mountDialog(provenanceI18n)
    await flush()
    await wrapper.get('[data-testid="fix-strategy-condition"]').trigger('click')
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('工作状态 不属于 关闭')
    expect(wrapper.text()).toContain('workingState 不属于 off')
  })

  it('runs only the selected strategy and keeps apply hidden until a verified response arrives', async () => {
    const pending = deferred<FixResult>()
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    const wrapper = mountDialog()
    await flush()

    await wrapper.get('[data-testid="fix-strategy-remove"]').trigger('click')
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')

    expect(boardApi.fixTrace).toHaveBeenCalledWith(7, {
      strategies: ['remove'],
      preferredRangeSelections: undefined
    }, expect.objectContaining({
      authToken: defaultToken,
      requestId: expect.any(String),
      signal: expect.anything()
    }))
    expect(wrapper.find('[data-testid="fix-strategy-loading"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(false)

    pending.resolve(removeResult())
    await flush()
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="fix-strategy-loading"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="fix-apply-current"]').attributes('disabled')).toBeUndefined()
  })

  it('protects typed preferred ranges while a search is still running', async () => {
    // Reset used to clear the rows *before* `fetchFixSuggestions` silently returned, so a click
    // during an in-flight search destroyed the user's bounds and issued no re-run — and the
    // returning result was then hidden behind "preferences changed, re-run".
    boardApi.fixTrace.mockResolvedValueOnce(parameterResult())
    const wrapper = mountDialog()
    await flush()

    await wrapper.get('[data-testid="fix-strategy-parameter"]').trigger('click')
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    // A parameter result exposes one fixed search-range row per target; narrow it.
    const bounds = wrapper.findAll('[data-testid="fix-parameter-range-row"] input[type="number"]')
    expect(bounds).toHaveLength(2)
    await bounds[0]!.setValue('18')
    await bounds[1]!.setValue('22')

    // Now start a second search and act on the range controls while it is in flight.
    const pending = deferred<FixResult>()
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await wrapper.vm.$nextTick()

    expect(boardApi.fixTrace.mock.calls.at(-1)![1].preferredRangeSelections)
      .toEqual([{ targetId: 'param_abcdefghijklmnopqrstuvwx', lower: 18, upper: 22 }])
    expect(wrapper.get('[data-testid="fix-preference-blocked"]').text())
      .toBe('fixSearchInProgress')
    expect(bounds[0]!.attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="fix-keep-original"]').attributes('disabled')).toBeDefined()

    // Both halves of the guard are asserted separately, because either alone would hide a
    // regression in the other: `trigger('click')` no-ops on a disabled button, so clicking proves
    // nothing about the handler, and the handler alone would leave a live-looking control.
    const reset = wrapper.get('[data-testid="fix-reset-ranges"]')
    expect(reset.attributes('disabled')).toBeDefined()

    const dialog = wrapper.vm as any
    const callsBefore = boardApi.fixTrace.mock.calls.length
    // Invoke the handler directly, as a stale queued event or a programmatic caller would.
    dialog.resetPreferenceRows()
    await wrapper.vm.$nextTick()

    // The decisive assertion: typed bounds survive a Reset that could not act.
    expect(dialog.preferredRangeRows[0].lower).toBe(18)
    expect(boardApi.fixTrace.mock.calls.length).toBe(callsBefore)

    pending.resolve(parameterResult())
    await flush()
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="fix-preference-blocked"]').exists()).toBe(false)
  })

  it('cancels an active search when the parent hides the dialog', async () => {
    const pending = deferred<FixResult>()
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')

    const options = boardApi.fixTrace.mock.calls[0]![2]
    expect(options.signal.aborted).toBe(false)
    await wrapper.setProps({ visible: false })
    await wrapper.vm.$nextTick()

    expect(options.signal.aborted).toBe(true)
    expect(boardApi.cancelFixRequest).toHaveBeenCalledWith(options.requestId, defaultToken)
    expect(wrapper.find('[data-testid="fix-result-dialog"]').exists()).toBe(false)
  })

  it('retries cancellation when the first DELETE beats server-side registration', async () => {
    const pending = deferred<FixResult>()
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    boardApi.cancelFixRequest
      .mockResolvedValueOnce(false)
      .mockResolvedValueOnce(true)
    const wrapper = mountDialog()
    await flush()

    vi.useFakeTimers()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    const options = boardApi.fixTrace.mock.calls[0]![2]
    await wrapper.setProps({ visible: false })
    await vi.advanceTimersByTimeAsync(50)

    expect(boardApi.cancelFixRequest).toHaveBeenCalledTimes(2)
    expect(options.signal.aborted).toBe(true)
    expect(boardApi.getFixRequestStatus).not.toHaveBeenCalled()
  })

  it('keeps tracking a search when server cancellation cannot be confirmed', async () => {
    const pending = deferred<FixResult>()
    const cancellationError = new Error('network unavailable')
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    boardApi.cancelFixRequest.mockRejectedValue(cancellationError)
    const wrapper = mountDialog()
    await flush()

    vi.useFakeTimers()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')

    const options = boardApi.fixTrace.mock.calls[0]![2]
    await wrapper.setProps({ visible: false })
    await vi.advanceTimersByTimeAsync(250)

    expect(options.signal.aborted).toBe(false)
    expect(boardApi.getFixRequestStatus).toHaveBeenCalledWith(options.requestId, defaultToken)
    expect(warn).toHaveBeenCalledWith(
      expect.stringContaining(`[Fix] Failed to cancel automatic-fix request ${options.requestId}`),
      cancellationError
    )
    expect(elementPlus.warning).toHaveBeenCalledWith('fixStopRequestMayStillBeRunning')
    vi.useRealTimers()
    pending.resolve(parameterResult())
    await flush()
    warn.mockRestore()
  })

  it('clears a cancellation request only after a terminal status is observed', async () => {
    const pending = deferred<FixResult>()
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    boardApi.cancelFixRequest.mockResolvedValue(false)
    boardApi.getFixRequestStatus.mockResolvedValueOnce({
      requestId: 'request-123',
      state: 'FINISHED',
      stage: 'CANCELLING',
      elapsedMs: 1000
    })
    const wrapper = mountDialog()
    await flush()

    vi.useFakeTimers()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')

    const options = boardApi.fixTrace.mock.calls[0]![2]
    await wrapper.setProps({ visible: false })
    await vi.advanceTimersByTimeAsync(250)

    expect(options.signal.aborted).toBe(true)
    expect(boardApi.getFixRequestStatus).toHaveBeenCalledWith(options.requestId, defaultToken)
  })

  it('gives a normal POST one polling interval to return after FINISHED', async () => {
    const pending = deferred<FixResult>()
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    boardApi.getFixRequestStatus.mockResolvedValue({
      requestId: 'request-123',
      state: 'FINISHED',
      stage: 'FINALIZING',
      elapsedMs: 1000
    })
    const wrapper = mountDialog()
    await flush()

    vi.useFakeTimers()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    const options = boardApi.fixTrace.mock.calls[0]![2]
    await vi.advanceTimersByTimeAsync(1000)
    await wrapper.vm.$nextTick()

    expect(boardApi.getFixRequestStatus).toHaveBeenCalledWith(options.requestId, defaultToken)
    expect(options.signal.aborted).toBe(false)
    expect(wrapper.get('[data-testid="fix-try-current"]').attributes('disabled')).toBeDefined()

    pending.resolve(parameterResult())
    await Promise.resolve()
    await Promise.resolve()
    await wrapper.vm.$nextTick()
    vi.useRealTimers()
    await flush()
    expect(wrapper.find('[data-testid="fix-strategy-loading"]').exists()).toBe(false)
  })

  it('releases a visibly hung POST after FINISHED remains stable through the grace interval', async () => {
    const pending = deferred<FixResult>()
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    boardApi.getFixRequestStatus.mockResolvedValue({
      requestId: 'request-123',
      state: 'FINISHED',
      stage: 'FINALIZING',
      elapsedMs: 1000
    })
    const wrapper = mountDialog()
    await flush()

    vi.useFakeTimers()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    const options = boardApi.fixTrace.mock.calls[0]![2]
    await vi.advanceTimersByTimeAsync(2000)
    await wrapper.vm.$nextTick()

    expect(options.signal.aborted).toBe(true)
    expect(wrapper.get('[data-testid="fix-try-current"]').attributes('disabled')).toBeUndefined()
    await expect((wrapper.vm as any).prepareForLogout()).resolves.toBe('ready')
  })

  it('does not let a settled old POST clear a newer request for the same strategy', async () => {
    const first = deferred<FixResult>()
    const second = deferred<FixResult>()
    boardApi.fixTrace
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
    boardApi.getFixRequestStatus.mockResolvedValue({
      requestId: 'request-123',
      state: 'FINISHED',
      stage: 'FINALIZING',
      elapsedMs: 1000
    })
    const wrapper = mountDialog()
    await flush()

    vi.useFakeTimers()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await vi.advanceTimersByTimeAsync(2000)
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    const secondOptions = boardApi.fixTrace.mock.calls[1]![2]

    first.reject(Object.assign(new Error('old transport aborted'), { name: 'CanceledError' }))
    await Promise.resolve()
    await Promise.resolve()
    await wrapper.vm.$nextTick()

    expect(secondOptions.signal.aborted).toBe(false)
    expect(wrapper.find('[data-testid="fix-strategy-loading"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="fix-try-current"]').attributes('disabled')).toBeDefined()

    vi.useRealTimers()
    second.resolve(parameterResult())
    await flush()
  })

  it('backs off but keeps recovering when the status endpoint remains unavailable', async () => {
    const pending = deferred<FixResult>()
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    boardApi.cancelFixRequest.mockResolvedValue(false)
    boardApi.getFixRequestStatus.mockRejectedValue(new Error('status unavailable'))
    const wrapper = mountDialog()
    await flush()

    vi.useFakeTimers()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    const options = boardApi.fixTrace.mock.calls[0]![2]
    await wrapper.setProps({ visible: false })
    await vi.advanceTimersByTimeAsync(31_000)

    expect(options.signal.aborted).toBe(true)
    const readsAfterRecovery = boardApi.getFixRequestStatus.mock.calls.length
    await vi.advanceTimersByTimeAsync(3000)
    expect(boardApi.getFixRequestStatus).toHaveBeenCalledTimes(readsAfterRecovery)
    await vi.advanceTimersByTimeAsync(8000)
    expect(boardApi.getFixRequestStatus.mock.calls.length).toBeGreaterThan(readsAfterRecovery)
  })

  it('releases polling and the POST transport when the dialog component is destroyed', async () => {
    const pending = deferred<FixResult>()
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    boardApi.cancelFixRequest.mockResolvedValue(false)
    const wrapper = mountDialog()
    await flush()

    vi.useFakeTimers()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    const options = boardApi.fixTrace.mock.calls[0]![2]

    wrapper.unmount()
    mountedDialogs.splice(mountedDialogs.indexOf(wrapper), 1)
    await vi.advanceTimersByTimeAsync(2_000)

    expect(options.signal.aborted).toBe(true)
    expect(boardApi.cancelFixRequest).toHaveBeenCalledTimes(20)
    expect(boardApi.cancelFixRequest).toHaveBeenCalledWith(options.requestId, defaultToken)
  })

  it('uses the request owner token when another account replaces the current session', async () => {
    const pending = deferred<FixResult>()
    const aliceToken = validToken('alice-fix-owner')
    const bobToken = validToken('bob-current')
    authStore.login(aliceToken, { userId: 1, phone: '13800138000', username: 'alice' })
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    const options = boardApi.fixTrace.mock.calls[0]![2]
    expect(options.authToken).toBe(aliceToken)

    authStore.login(bobToken, { userId: 2, phone: '13900139000', username: 'bob' })
    wrapper.unmount()
    mountedDialogs.splice(mountedDialogs.indexOf(wrapper), 1)
    await flush()

    expect(boardApi.cancelFixRequest).toHaveBeenCalledWith(options.requestId, aliceToken)
    expect(authStore.getToken()).toBe(bobToken)
    pending.reject(Object.assign(new Error('transport aborted'), { name: 'CanceledError' }))
    await Promise.resolve()
  })

  it('keeps cancellation recovery after the fix POST loses transport following DELETE=false', async () => {
    const pending = deferred<FixResult>()
    const aliceToken = validToken('alice-fix-transport-owner')
    authStore.login(aliceToken, { userId: 1, phone: '13800138000', username: 'alice' })
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    boardApi.cancelFixRequest.mockResolvedValue(false)
    const wrapper = mountDialog()
    await flush()

    vi.useFakeTimers()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    const options = boardApi.fixTrace.mock.calls[0]![2]
    await wrapper.setProps({ visible: false })
    await vi.advanceTimersByTimeAsync(250)

    pending.reject(new Error('transport lost'))
    await Promise.resolve()
    await Promise.resolve()
    await wrapper.vm.$nextTick()

    expect(options.signal.aborted).toBe(false)
    expect(boardApi.getFixRequestStatus).toHaveBeenCalledWith(options.requestId, aliceToken)
    const cancellationCallsBeforeRecoveryTick = boardApi.cancelFixRequest.mock.calls.length
    await vi.advanceTimersByTimeAsync(1000)
    expect(boardApi.cancelFixRequest.mock.calls.length).toBeGreaterThan(cancellationCallsBeforeRecoveryTick)

    await wrapper.setProps({ visible: true })
    await wrapper.vm.$nextTick()
    const retry = wrapper.get('[data-testid="fix-try-current"]')
    expect(retry.attributes('disabled')).toBeDefined()
    await retry.trigger('click')
    expect(boardApi.fixTrace).toHaveBeenCalledTimes(1)

    const logoutPreparation = (wrapper.vm as any).prepareForLogout()
    await vi.advanceTimersByTimeAsync(1000)
    await expect(logoutPreparation).resolves.toBe('outcome-unknown')
    expect(boardApi.cancelFixRequest.mock.calls.every(
      ([requestId, token]) => requestId === options.requestId && token === aliceToken
    )).toBe(true)

    boardApi.cancelFixRequest.mockResolvedValue(true)
    await expect((wrapper.vm as any).prepareForLogout()).resolves.toBe('ready')
  })

  it('does not mistake POST transport loss during logout cancellation for completion', async () => {
    const pendingPost = deferred<FixResult>()
    const firstCancellation = deferred<boolean>()
    const aliceToken = validToken('alice-fix-logout-owner')
    authStore.login(aliceToken, { userId: 1, phone: '13800138000', username: 'alice' })
    boardApi.fixTrace.mockReturnValueOnce(pendingPost.promise)
    boardApi.cancelFixRequest
      .mockReturnValueOnce(firstCancellation.promise)
      .mockResolvedValue(false)
    const wrapper = mountDialog()
    await flush()

    vi.useFakeTimers()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    const options = boardApi.fixTrace.mock.calls[0]![2]
    const logoutPreparation = (wrapper.vm as any).prepareForLogout()

    pendingPost.reject(new Error('transport lost during logout'))
    await Promise.resolve()
    await Promise.resolve()
    firstCancellation.resolve(false)
    await vi.advanceTimersByTimeAsync(1000)

    await expect(logoutPreparation).resolves.toBe('outcome-unknown')
    expect(options.signal.aborted).toBe(false)
    expect(boardApi.cancelFixRequest.mock.calls.every(
      ([requestId, token]) => requestId === options.requestId && token === aliceToken
    )).toBe(true)

    boardApi.cancelFixRequest.mockResolvedValue(true)
    await expect((wrapper.vm as any).prepareForLogout()).resolves.toBe('ready')
  })

  it('blocks a second fix id while a visible transport-loss outcome is unresolved', async () => {
    const pendingPost = deferred<FixResult>()
    boardApi.fixTrace.mockReturnValueOnce(pendingPost.promise)
    boardApi.cancelFixRequest.mockResolvedValue(false)
    const wrapper = mountDialog()
    await flush()

    vi.useFakeTimers()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    const firstRequestId = boardApi.fixTrace.mock.calls[0]![2].requestId
    pendingPost.reject(new Error('transport lost while visible'))
    await Promise.resolve()
    await Promise.resolve()
    await wrapper.vm.$nextTick()

    const retry = wrapper.get('[data-testid="fix-try-current"]')
    expect(retry.attributes('disabled')).toBeDefined()
    await retry.trigger('click')
    expect(boardApi.fixTrace).toHaveBeenCalledTimes(1)
    expect(boardApi.cancelFixRequest).toHaveBeenCalledWith(firstRequestId, defaultToken)

    boardApi.cancelFixRequest.mockResolvedValue(true)
    await expect((wrapper.vm as any).prepareForLogout()).resolves.toBe('ready')
  })

  it('keeps the hidden dialog owner available for logout and blocks a new id after reopen', async () => {
    const pendingPost = deferred<FixResult>()
    const aliceToken = validToken('alice-hidden-fix-owner')
    authStore.login(aliceToken, { userId: 1, phone: '13800138000', username: 'alice' })
    boardApi.fixTrace.mockReturnValueOnce(pendingPost.promise)
    boardApi.cancelFixRequest.mockResolvedValue(false)
    const wrapper = mountPersistentDialogHost()
    await flush()

    vi.useFakeTimers()
    let dialog = wrapper.findComponent(FixResultDialog)
    await dialog.get('[data-testid="fix-try-current"]').trigger('click')
    const options = boardApi.fixTrace.mock.calls[0]![2]

    await dialog.get('button[aria-label="close"]').trigger('click')
    expect((wrapper.vm as any).visible).toBe(false)
    expect(wrapper.findComponent(FixResultDialog).exists()).toBe(true)
    expect((wrapper.vm as any).dialogRef).toBeTruthy()

    pendingPost.reject(new Error('transport lost after dialog close'))
    await Promise.resolve()
    await Promise.resolve()
    await wrapper.vm.$nextTick()

    const logoutPreparation = (wrapper.vm as any).dialogRef.prepareForLogout()
    await vi.advanceTimersByTimeAsync(1_000)
    await expect(logoutPreparation).resolves.toBe('outcome-unknown')
    expect(options.signal.aborted).toBe(false)
    const ownedCancellationCalls = boardApi.cancelFixRequest.mock.calls
      .filter(([requestId]) => requestId === options.requestId)
    expect(ownedCancellationCalls.length).toBeGreaterThan(0)
    expect(ownedCancellationCalls.every(([, token]) => token === aliceToken)).toBe(true)

    ;(wrapper.vm as any).visible = true
    await wrapper.vm.$nextTick()
    dialog = wrapper.findComponent(FixResultDialog)
    const retry = dialog.get('[data-testid="fix-try-current"]')
    expect(retry.attributes('disabled')).toBeDefined()
    await retry.trigger('click')
    expect(boardApi.fixTrace).toHaveBeenCalledTimes(1)

    boardApi.cancelFixRequest.mockResolvedValue(true)
    await expect((wrapper.vm as any).dialogRef.prepareForLogout()).resolves.toBe('ready')
  })

  it('rejects a different trace while a hidden request still owns the dialog', async () => {
    const pending = deferred<FixResult>()
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    boardApi.cancelFixRequest.mockResolvedValue(false)
    const wrapper = mountPersistentDialogHost()
    await flush()

    vi.useFakeTimers()
    const dialog = wrapper.findComponent(FixResultDialog)
    await dialog.get('[data-testid="fix-try-current"]').trigger('click')
    await dialog.get('button[aria-label="close"]').trigger('click')
    expect((wrapper.vm as any).visible).toBe(false)
    expect((wrapper.vm as any).dialogRef.canOpenTrace(7)).toBe(true)
    expect((wrapper.vm as any).dialogRef.canOpenTrace(8)).toBe(false)

    ;(wrapper.vm as any).traceId = 8
    ;(wrapper.vm as any).visible = true
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect((wrapper.vm as any).visible).toBe(false)
    expect(elementPlus.warning).toHaveBeenCalledWith('fixTraceSwitchBlockedByActiveSearch')
    expect(boardApi.getFaultRules).not.toHaveBeenCalledWith(8)

    boardApi.cancelFixRequest.mockResolvedValue(true)
    await expect((wrapper.vm as any).dialogRef.prepareForLogout()).resolves.toBe('ready')
  })

  it.each([
    ['an HTTP rejection', { response: { status: 503, data: { message: 'Unavailable' } } }],
    ['an invalid authoritative response', { code: FIX_RESPONSE_INCOMPLETE_CODE }]
  ])('releases request tracking after %s', async (_label, rejection) => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    boardApi.fixTrace.mockRejectedValueOnce(rejection)
    const wrapper = mountDialog()
    await flush()

    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    expect(wrapper.get('[data-testid="fix-try-current"]').attributes('disabled')).toBeUndefined()
    await expect((wrapper.vm as any).prepareForLogout()).resolves.toBe('ready')
    errorSpy.mockRestore()
  })

  it('shows the server-observed automatic-fix phase while the strategy is running', async () => {
    const pending = deferred<FixResult>()
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    const wrapper = mountDialog()
    await flush()

    vi.useFakeTimers()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await vi.advanceTimersByTimeAsync(1000)
    await wrapper.vm.$nextTick()

    expect(boardApi.getFixRequestStatus).toHaveBeenCalledWith(expect.any(String), defaultToken)
    expect(wrapper.text()).toContain('fixProgressStage_SEARCHING_AND_VERIFYING')

    vi.useRealTimers()
    pending.resolve(parameterResult())
    await flush()
  })

  it('invalidates an old verified suggestion before retrying that strategy', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(parameterResult())
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(true)

    // A narrowed range is what makes a retry meaningful once a verified suggestion exists.
    await wrapper.findAll('[data-testid="fix-parameter-range-row"] input[type="number"]')[0]!.setValue('60')
    const retry = deferred<FixResult>()
    boardApi.fixTrace.mockReturnValueOnce(retry.promise)
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')

    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="fix-strategy-loading"]').exists()).toBe(true)
    // The old suggestion is gone, not merely hidden behind the "ranges changed" notice.
    expect(wrapper.find('[data-testid="fix-parameter-preferences-stale"]').exists()).toBe(false)
    retry.resolve(parameterResult())
    await flush()
  })

  it('retains independently verified suggestions while trying all three strategies', async () => {
    boardApi.fixTrace
      .mockResolvedValueOnce(parameterResult())
      .mockResolvedValueOnce(conditionResult())
      .mockResolvedValueOnce(removeResult())
    const wrapper = mountDialog()
    await flush()

    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.get('[data-testid="fix-strategy-condition"]').trigger('click')
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.get('[data-testid="fix-strategy-remove"]').trigger('click')
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    expect(boardApi.fixTrace).toHaveBeenNthCalledWith(1, 7, {
      strategies: ['parameter'],
      preferredRangeSelections: undefined
    }, expect.any(Object))
    expect(boardApi.fixTrace).toHaveBeenNthCalledWith(2, 7, {
      strategies: ['condition'],
      preferredRangeSelections: undefined
    }, expect.any(Object))
    expect(boardApi.fixTrace).toHaveBeenNthCalledWith(3, 7, {
      strategies: ['remove'],
      preferredRangeSelections: undefined
    }, expect.any(Object))

    await wrapper.get('[data-testid="fix-strategy-parameter"]').trigger('click')
    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('85')
    await wrapper.get('[data-testid="fix-strategy-condition"]').trigger('click')
    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('addConditionAdjustment')
    await wrapper.get('[data-testid="fix-strategy-remove"]').trigger('click')
    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Gas leak unlocks exit')
  })

  it.each([
    ['parameter', jointParameterResult, 'parameterAdjustments (2)'],
    ['condition', jointConditionResult, 'conditionAdjustments (2)'],
    ['remove', jointRemoveResult, 'rulesToRemove (2)']
  ] as const)('renders and submits every item in a coordinated %s suggestion', async (
    strategy,
    resultFactory,
    countText
  ) => {
    const result = resultFactory()
    boardApi.fixTrace.mockResolvedValueOnce(result)
    boardApi.applyFix.mockResolvedValueOnce({
      applied: true,
      strategy,
      verificationEvidenceReused: true,
      appliedSuggestion: result.suggestions[0],
      previousRuleCount: 3,
      currentRuleCount: strategy === 'remove' ? 1 : 3,
      message: 'Applied coordinated suggestion.',
      rules: []
    })
    const wrapper = mountDialog()
    await flush()
    if (strategy !== 'parameter') {
      await wrapper.get(`[data-testid="fix-strategy-${strategy}"]`).trigger('click')
    }
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain(countText)
    await wrapper.get('[data-testid="fix-apply-current"]').trigger('click')
    await flush()
    expect(boardApi.applyFix).toHaveBeenCalledWith(7, result.suggestions[0], undefined)
  })

  it('renders two distinct removal operations even when their display names match', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(duplicateNameRemoveResult())
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-strategy-remove"]').trigger('click')
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    const removals = wrapper.findAll('[data-testid="fix-removed-rule"]')
    expect(removals).toHaveLength(2)
    expect(removals.map(item => item.text())).toEqual([
      'Shared automation name',
      'Shared automation name'
    ])
  })

  it('lists specifications the original rules already violated beside a verified suggestion', async () => {
    const result = parameterResult()
    result.suggestions[0]!.preexistingViolations = [
      { specId: 'spec-7', templateId: '3', formulaPreview: 'CTL AG NOT (co2 > 1000)' }
    ]
    boardApi.fixTrace.mockResolvedValueOnce(result)
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    const note = wrapper.get('[data-testid="fix-preexisting-violations"]')
    expect(note.text()).toContain('fixPreexistingViolationsTitle')
    const rows = note.findAll('[data-testid="fix-preexisting-violation"]')
    expect(rows).toHaveLength(1)
    expect(rows[0]!.text()).toContain('CTL AG NOT (co2 > 1000)')
    // The row names the specification by its template, never by its internal id.
    expect(rows[0]!.text()).not.toContain('spec-7')
  })

  it('shows no pre-existing violation note when every specification holds', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(parameterResult())
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    // The verified suggestion rendered, so the note's absence is a decision rather than an empty dialog.
    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="fix-preexisting-violations"]').exists()).toBe(false)
  })

  it('states a verified outcome once and describes the strategy once', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(parameterResult())
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    const text = wrapper.text()
    const occurrences = (needle: string) => text.split(needle).length - 1
    expect(occurrences('fixAttemptReason.VERIFIED')).toBe(1)
    expect(occurrences('fixStrategyParameterDesc')).toBe(1)
  })

  it('lists every option of one strategy as one radio group, the smallest change selected', async () => {
    const wrapper = await tryParameter(parameterAlternativesResult())

    const options = wrapper.findAll('[data-testid="fix-alternative"]')
    expect(options).toHaveLength(2)
    expect(options[0]!.text()).toContain('85')
    expect(options[1]!.text()).toContain('86')
    const radios = wrapper.findAll('[data-testid="fix-alternative"] input[type="radio"]')
    // A shared name is what makes the inputs one group for arrow keys and screen readers.
    expect(new Set(radios.map(radio => radio.attributes('name')))).toEqual(new Set(['fix-alternative']))
    expect(alternativesChecked(wrapper)).toEqual([true, false])
    // Every option passed the same check, so the verdict is not repeated per option.
    expect(wrapper.text().split('fixAttemptReason.VERIFIED').length - 1).toBe(1)
  })

  it('applies the option the user selected with the radio', async () => {
    const result = parameterAlternativesResult()
    boardApi.applyFix.mockResolvedValueOnce(parameterApplied(result.suggestions[1]!))
    const wrapper = await tryParameter(result)

    await wrapper.findAll('[data-testid="fix-alternative"] input[type="radio"]')[1]!.setValue(true)
    expect(alternativesChecked(wrapper)).toEqual([false, true])
    await wrapper.get('[data-testid="fix-apply-current"]').trigger('click')
    await flush()

    expect(boardApi.applyFix).toHaveBeenCalledWith(7, result.suggestions[1], undefined)
  })

  it('selects an option when its card is clicked', async () => {
    const wrapper = await tryParameter(parameterAlternativesResult())

    await wrapper.findAll('[data-testid="fix-alternative"]')[1]!.trigger('click')

    expect(alternativesChecked(wrapper)).toEqual([false, true])
  })

  it('keeps the applied option selected while apply is running', async () => {
    const result = parameterAlternativesResult()
    const pending = deferred<ReturnType<typeof parameterApplied>>()
    boardApi.applyFix.mockReturnValueOnce(pending.promise)
    const wrapper = await tryParameter(result)

    await wrapper.get('[data-testid="fix-apply-current"]').trigger('click')
    await wrapper.vm.$nextTick()
    const radios = wrapper.findAll('[data-testid="fix-alternative"] input[type="radio"]')
    expect(radios.every(radio => radio.attributes('disabled') !== undefined)).toBe(true)
    await wrapper.findAll('[data-testid="fix-alternative"]')[1]!.trigger('click')
    expect(alternativesChecked(wrapper)).toEqual([true, false])

    pending.resolve(parameterApplied(result.suggestions[0]!))
    await flush()
    expect(boardApi.applyFix).toHaveBeenCalledWith(7, result.suggestions[0], undefined)
  })

  it('starts a retried search from its smallest option again', async () => {
    const wrapper = await tryParameter(parameterAlternativesResult())
    await wrapper.findAll('[data-testid="fix-alternative"]')[1]!.trigger('click')
    expect(alternativesChecked(wrapper)).toEqual([false, true])

    // Narrowing a range is what makes a retry meaningful once options exist.
    await wrapper.findAll('[data-testid="fix-parameter-range-row"] input[type="number"]')[0]!.setValue('60')
    boardApi.fixTrace.mockResolvedValueOnce(parameterAlternativesResult())
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    expect(alternativesChecked(wrapper)).toEqual([true, false])
  })

  it('shows a single option without selection chrome', async () => {
    const wrapper = await tryParameter(parameterResult())

    expect(wrapper.find('[data-testid="fix-alternative"]').exists()).toBe(false)
    expect(wrapper.find('input[name="fix-alternative"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('fixAlternativesTitle')
    expect(wrapper.text()).toContain('85')
    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(true)
  })

  it.each([
    ['a complete listing', () => parameterAlternativesResult(true), false],
    ['an incomplete listing of several options', () => parameterAlternativesResult(false), true],
    ['an incomplete listing of one option', (): FixResult => ({
      ...parameterResult(),
      strategyAttempts: [{ ...parameterResult().strategyAttempts[0]!, alternativesComplete: false }]
    }), true]
  ] as const)('states that options may be missing only for %s', async (_label, resultFactory, expectedNote) => {
    const wrapper = await tryParameter(resultFactory())

    expect(wrapper.find('[data-testid="fix-alternatives-incomplete"]').exists()).toBe(expectedNote)
  })

  it('lets each option state the specifications it leaves violated', async () => {
    // Forward verification computes this per candidate, so two options may differ.
    const result = parameterAlternativesResult()
    result.suggestions[1]!.preexistingViolations = [
      { specId: 'spec-7', templateId: '3', formulaPreview: 'CTL AG NOT (co2 > 1000)' }
    ]
    const wrapper = await tryParameter(result)

    const options = wrapper.findAll('[data-testid="fix-alternative"]')
    expect(options[0]!.find('[data-testid="fix-preexisting-violations"]').exists()).toBe(false)
    expect(options[1]!.get('[data-testid="fix-preexisting-violations"]').text())
      .toContain('CTL AG NOT (co2 > 1000)')
  })

  it.each([
    ['>', true],
    ['>=', false]
  ] as const)('shows an unreachable-rule warning only for a strict %s upper boundary', async (
    relation,
    expectedWarning
  ) => {
    boardApi.fixTrace.mockResolvedValueOnce(boundaryParameterResult(relation))
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="fix-parameter-unreachable-warning"]').exists())
      .toBe(expectedWarning)
  })

  it('keeps a verified parameter suggestion current until a search range is actually narrowed', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(parameterResult())
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    // Rows start at the template bounds, which express no preference.
    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="fix-reset-ranges"]').exists()).toBe(false)

    const bounds = wrapper.findAll('[data-testid="fix-parameter-range-row"] input[type="number"]')
    await bounds[0]!.setValue('60')

    expect(wrapper.find('[data-testid="fix-parameter-preferences-stale"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(false)

    // Restoring the template range makes the suggestion current again without a re-run.
    await wrapper.get('[data-testid="fix-reset-ranges"]').trigger('click')
    expect(wrapper.find('[data-testid="fix-parameter-preferences-stale"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(true)
  })

  it('sends only narrowed rows as preferred ranges on retry', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(jointParameterResult())
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    expect(boardApi.fixTrace.mock.calls[0]![1].preferredRangeSelections).toBeUndefined()
    const rows = wrapper.findAll('[data-testid="fix-parameter-range-row"]')
    expect(rows).toHaveLength(2)
    await rows[1]!.findAll('input[type="number"]')[1]!.setValue('90')

    boardApi.fixTrace.mockResolvedValueOnce(jointParameterResult())
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    const second = jointParameterResult().parameterTargets[1]!
    expect(boardApi.fixTrace.mock.calls[1]![1].preferredRangeSelections)
      .toEqual([{ targetId: second.targetId, lower: second.lowerBound, upper: 90 }])
    // The typed bound survives the catalog the re-run returns.
    expect((wrapper.findAll('[data-testid="fix-parameter-range-row"]')[1]!
      .findAll('input[type="number"]')[1]!.element as HTMLInputElement).value).toBe('90')
  })

  it('offers search ranges from parameter targets when no suggestion was found', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(parameterBudgetExhaustedResult())
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-testid="fix-parameter-range-row"]')).toHaveLength(1)
  })

  it('builds a localized bundled parameter label without exposing the backend English description', async () => {
    const result = parameterBudgetExhaustedResult()
    result.parameterTargets = result.parameterTargets.map(target => ({
      ...target,
      attribute: 'workingState',
      description: 'SERVER_FIXED_ENGLISH_DESCRIPTION',
      modelTokenSource: 'BUNDLED'
    }))
    boardApi.fixTrace.mockResolvedValueOnce(result)
    const wrapper = mountDialog(provenanceI18n)
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('调整 工作状态 大于 70')
    expect(wrapper.text()).not.toContain('SERVER_FIXED_ENGLISH_DESCRIPTION')
  })

  it('does not present an exhausted search budget as a complete no-result conclusion', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(parameterBudgetExhaustedResult())
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('fixStrategyBudgetExhaustedTitle')
    expect(wrapper.text()).toContain('fixParameterBudgetExhaustedDetail')
    expect(wrapper.find('[data-testid="fix-attempt-outcome-note"]').exists()).toBe(false)
  })

  // The same request against the same frozen snapshot reproduces the budget outcome exactly.
  it('offers a retry after an exhausted budget only once the search ranges change', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(parameterBudgetExhaustedResult())
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    expect(wrapper.find('[data-testid="fix-try-current"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="fix-try-next-strategy"]').exists()).toBe(true)

    await wrapper.findAll('[data-testid="fix-parameter-range-row"] input[type="number"]')[1]!.setValue('90')

    expect(wrapper.find('[data-testid="fix-try-current"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="fix-try-next-strategy"]').exists()).toBe(false)
  })

  it('states a full-range parameter proof as final and moves on instead of offering a retry', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(parameterOutcomeResult('NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE'))
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    const outcome = wrapper.get('[data-testid="fix-attempt-outcome"]')
    expect(outcome.text()).toContain('fixStrategyCannotRepairTitle')
    expect(outcome.text()).toContain('fixParameterNoCandidateDetail')
    expect(wrapper.get('[data-testid="fix-attempt-outcome-note"]').text()).toBe('fixProofCompleteNote')
    // Every value of the template ranges was settled, so no narrower range can find more.
    expect(wrapper.find('[data-testid="fix-parameter-ranges"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="fix-try-current"]').exists()).toBe(false)

    boardApi.fixTrace.mockResolvedValueOnce(conditionResult())
    await wrapper.get('[data-testid="fix-try-next-strategy"]').trigger('click')
    await flush()

    expect(boardApi.fixTrace.mock.calls[1]![1].strategies).toEqual(['condition'])
    expect(wrapper.get('[data-testid="fix-strategy-condition"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(true)
  })

  it('limits a narrowed parameter proof to the searched ranges and retries once they are restored', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(parameterBudgetExhaustedResult())
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.findAll('[data-testid="fix-parameter-range-row"] input[type="number"]')[1]!.setValue('90')
    boardApi.fixTrace.mockResolvedValueOnce(parameterOutcomeResult('ALL_CANDIDATES_REJECTED'))
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    expect(boardApi.fixTrace.mock.calls[1]![1].preferredRangeSelections).toHaveLength(1)
    expect(wrapper.text()).toContain('fixParameterCannotRepairInRangesTitle')
    expect(wrapper.text()).toContain('fixParameterAllRejectedDetail')
    expect(wrapper.get('[data-testid="fix-attempt-outcome-note"]').text()).toBe('fixProofNarrowedRangesNote')
    expect(wrapper.find('[data-testid="fix-parameter-ranges"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="fix-try-current"]').exists()).toBe(false)

    await wrapper.get('[data-testid="fix-reset-ranges"]').trigger('click')

    expect(wrapper.find('[data-testid="fix-try-current"]').exists()).toBe(true)
  })

  it('keeps the retry for an inconclusive search, which proves nothing', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(parameterOutcomeResult('INCONCLUSIVE'))
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    expect(wrapper.text()).toContain('fixStrategyInconclusiveTitle')
    expect(wrapper.find('[data-testid="fix-attempt-outcome-note"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="fix-parameter-ranges"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="fix-try-current"]').text()).toContain('retryFixStrategy')
  })

  it('offers no primary action once every strategy has settled without a repair', async () => {
    boardApi.fixTrace
      .mockResolvedValueOnce(parameterOutcomeResult('NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE'))
      .mockResolvedValueOnce(conditionWithoutSuggestionResult())
      .mockResolvedValueOnce({
        ...removeResult(),
        suggestions: [],
        strategyAttempts: [{ strategy: 'remove', status: 'ALL_CANDIDATES_REJECTED', reason: 'Server reason.' }],
        fixable: false
      })
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.get('[data-testid="fix-try-next-strategy"]').trigger('click')
    await flush()
    await wrapper.get('[data-testid="fix-try-next-strategy"]').trigger('click')
    await flush()

    expect(boardApi.fixTrace.mock.calls.map(call => call[1].strategies))
      .toEqual([['parameter'], ['condition'], ['remove']])
    expect(wrapper.text()).toContain('fixRemoveAllRejectedDetail')
    expect(wrapper.find('[data-testid="fix-try-current"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="fix-try-next-strategy"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(false)
    // Every strategy proved it cannot repair this over its whole space, so "yet" would promise more.
    expect(wrapper.get('[data-testid="fix-result-header"] .iot-dialog__subtitle').text()).toBe('noVerifiedOptions')
  })

  // A strategy that does not apply to this model is as final as one that proved it cannot repair it.
  it('says no option exists when one strategy does not apply and the others proved they cannot repair it', async () => {
    boardApi.fixTrace
      .mockResolvedValueOnce({ ...parameterOutcomeResult('SKIPPED_NO_PARAMETERIZABLE_VALUES'), parameterTargets: [] })
      .mockResolvedValueOnce(conditionWithoutSuggestionResult())
      .mockResolvedValueOnce({
        ...removeResult(),
        suggestions: [],
        strategyAttempts: [{ strategy: 'remove', status: 'ALL_CANDIDATES_REJECTED', reason: 'Server reason.' }],
        fixable: false
      })
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.get('[data-testid="fix-try-next-strategy"]').trigger('click')
    await flush()
    await wrapper.get('[data-testid="fix-try-next-strategy"]').trigger('click')
    await flush()

    expect(wrapper.find('[data-testid="fix-try-current"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="fix-try-next-strategy"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="fix-result-header"] .iot-dialog__subtitle').text()).toBe('noVerifiedOptions')
  })

  it('keeps a parameter at its original value for the next search and can release it', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(parameterResult())
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    const keep = wrapper.get('[data-testid="fix-keep-original"]')
    expect(keep.attributes('aria-pressed')).toBe('false')
    await keep.trigger('click')

    const bounds = wrapper.findAll('[data-testid="fix-parameter-range-row"] input[type="number"]')
    expect((bounds[0]!.element as HTMLInputElement).value).toBe('70')
    expect((bounds[1]!.element as HTMLInputElement).value).toBe('70')
    expect(keep.attributes('aria-pressed')).toBe('true')
    expect(wrapper.find('[data-testid="fix-parameter-preferences-stale"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(false)

    await keep.trigger('click')
    expect((bounds[0]!.element as HTMLInputElement).value).toBe('0')
    expect((bounds[1]!.element as HTMLInputElement).value).toBe('100')
    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(true)
  })

  it('explains why condition adjustment cannot repair the violation', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(conditionWithoutSuggestionResult())
    const wrapper = mountDialog()
    await flush()

    await wrapper.get('[data-testid="fix-strategy-condition"]').trigger('click')
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('fixStrategyCannotRepairTitle')
    expect(wrapper.text()).toContain('fixConditionNoCandidateDetail')
    expect(wrapper.get('[data-testid="fix-attempt-outcome-note"]').text()).toBe('fixProofCompleteNote')
    expect(wrapper.find('[data-testid="fix-try-current"]').exists()).toBe(false)
  })

  it('shows and applies a verified condition adjustment without destructive confirmation', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(conditionResult())
    boardApi.applyFix.mockResolvedValueOnce({
      applied: true,
      strategy: 'condition',
      verificationEvidenceReused: true,
      appliedSuggestion: conditionResult().suggestions[0],
      previousRuleCount: 1,
      currentRuleCount: 1,
      message: 'Added one guard.',
      rules: [{
        id: '21',
        name: 'When it is cold, heat the room',
        sources: [
          { fromId: 'temperature_1', fromApi: 'temperature', itemType: 'variable', relation: '<=', value: '18' },
          { fromId: 'occupancy_1', fromApi: 'occupied', itemType: 'variable', relation: '=', value: 'present' }
        ],
        toId: 'ac_1',
        toApi: 'heat'
      }]
    })
    const wrapper = mountDialog()
    await flush()

    await wrapper.get('[data-testid="fix-strategy-condition"]').trigger('click')
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('addConditionAdjustment')
    await wrapper.get('[data-testid="fix-apply-current"]').trigger('click')
    await flush()

    expect(elementPlus.confirm).not.toHaveBeenCalled()
    expect(boardApi.applyFix).toHaveBeenCalledWith(
      7,
      conditionResult().suggestions[0],
      undefined
    )
    expect(elementPlus.success).toHaveBeenCalledWith('fixAppliedWithSignedEvidence')
    expect(wrapper.emitted('applied')?.[0]?.[0]).toMatchObject({
      strategy: 'condition',
      currentRuleCount: 1
    })
  })

  it('applies the exact verified parameter adjustment and authoritative rule snapshot', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(parameterResult())
    boardApi.applyFix.mockResolvedValueOnce({
      applied: true,
      strategy: 'parameter',
      verificationEvidenceReused: true,
      appliedSuggestion: parameterResult().suggestions[0],
      previousRuleCount: 1,
      currentRuleCount: 1,
      message: 'Changed one threshold.',
      rules: [{
        id: '31',
        name: 'Adjust the gas threshold',
        sources: [{ fromId: 'gas_1', fromApi: 'gas', itemType: 'variable', relation: '>', value: '85' }],
        toId: 'exit_1',
        toApi: 'unlock'
      }]
    })
    const wrapper = mountDialog()
    await flush()

    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    await wrapper.get('[data-testid="fix-apply-current"]').trigger('click')
    await flush()

    expect(elementPlus.confirm).not.toHaveBeenCalled()
    expect(boardApi.applyFix).toHaveBeenCalledWith(
      7,
      parameterResult().suggestions[0],
      undefined
    )
    expect(elementPlus.success).toHaveBeenCalledWith('fixAppliedWithSignedEvidence')
    expect(wrapper.emitted('applied')?.[0]?.[0]).toMatchObject({
      strategy: 'parameter',
      currentRuleCount: 1
    })
  })

  it.each([0, 1])('reports signed evidence after closing its dialog with %i underlying modals', async (underlyingModals) => {
    const releaseUnderlyingModal = underlyingModals ? registerModalSurface() : () => {}
    const result = parameterResult()
    const applied = parameterApplied(result.suggestions[0]!)
    boardApi.fixTrace.mockResolvedValueOnce(result)
    boardApi.applyFix.mockResolvedValueOnce(applied)
    const wrapper = mountPersistentDialogHost()

    try {
      await flush()
      await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
      await flush()
      expect(openModalDepth.value).toBe(underlyingModals + 1)
      const dialog = wrapper.findComponent(FixResultDialog)
      const observations: unknown[] = []
      elementPlus.success.mockImplementationOnce(() => observations.push({
        depth: openModalDepth.value,
        visible: wrapper.find('[data-testid="fix-result-dialog"]').exists(),
        applied: dialog.emitted('applied')?.[0]?.[0]
      }))

      await wrapper.get('[data-testid="fix-apply-current"]').trigger('click')
      await flush()

      expect(elementPlus.success).toHaveBeenCalledWith('fixAppliedWithSignedEvidence')
      expect(observations).toEqual([{ depth: underlyingModals, visible: false, applied }])
      expect(dialog.emitted('update:visible')).toEqual([[false]])
    } finally {
      releaseUnderlyingModal()
      elementPlus.success.mockReset()
    }
  })

  it('requires confirmation before permanently removing rules', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(removeResult())
    boardApi.applyFix.mockResolvedValueOnce({
      applied: true,
      strategy: 'remove',
      verificationEvidenceReused: true,
      appliedSuggestion: removeResult().suggestions[0],
      previousRuleCount: 1,
      currentRuleCount: 0,
      message: 'Removed one rule.',
      rules: []
    })
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-strategy-remove"]').trigger('click')
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    await wrapper.get('[data-testid="fix-apply-current"]').trigger('click')
    await flush()

    expect(elementPlus.confirm).toHaveBeenCalledOnce()
    expect(boardApi.applyFix).toHaveBeenCalledWith(
      7,
      removeResult().suggestions[0],
      undefined
    )
    expect(elementPlus.success).toHaveBeenCalledWith('fixAppliedWithSignedEvidence')
    expect(elementPlus.success).not.toHaveBeenCalledWith('Removed one rule.')
    expect(wrapper.emitted('applied')?.[0]?.[0]).toMatchObject({
      applied: true,
      strategy: 'remove',
      currentRuleCount: 0,
      rules: []
    })
  })

  it('does not expose an internal device reference when fix apply is rejected', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(parameterResult())
    boardApi.applyFix.mockRejectedValueOnce({
      response: {
        status: 400,
        data: {
          message: 'rules[0].conditions[0].deviceName: Unknown condition device: device_78da526b_8cb4'
        }
      }
    })
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    await wrapper.get('[data-testid="fix-apply-current"]').trigger('click')
    await flush()

    expect(elementPlus.error).toHaveBeenCalledWith('fixDeviceReferenceUnavailable')
    expect(elementPlus.error).not.toHaveBeenCalledWith(expect.stringContaining('device_78da526b'))
    expect(wrapper.emitted('outcome-uncertain')).toBeUndefined()
    expect(wrapper.emitted('update:visible')).toBeUndefined()
  })

  it('keeps the dialog open for a definitive 503 preflight rejection', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(parameterResult())
    boardApi.applyFix.mockRejectedValueOnce({
      response: {
        status: 503,
        data: {
          message: 'Current template comparison is unavailable.',
          data: { reasonCode: 'FIX_APPLY_PREFLIGHT_UNAVAILABLE' }
        }
      }
    })
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    await wrapper.get('[data-testid="fix-apply-current"]').trigger('click')
    await flush()

    expect(elementPlus.error).toHaveBeenCalledWith('Current template comparison is unavailable.')
    expect(wrapper.emitted('outcome-uncertain')).toBeUndefined()
    expect(wrapper.emitted('update:visible')).toBeUndefined()
  })

  it('treats an unclassified 503 as an uncertain mutation outcome', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(parameterResult())
    boardApi.applyFix.mockRejectedValueOnce({
      response: { status: 503, data: { message: 'Upstream unavailable.' } }
    })
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    await wrapper.get('[data-testid="fix-apply-current"]').trigger('click')
    await flush()

    expect(wrapper.emitted('outcome-uncertain')).toHaveLength(1)
    expect(wrapper.emitted('update:visible')?.at(-1)).toEqual([false])
    expect(elementPlus.error).not.toHaveBeenCalled()
  })

  it('disables apply when the template snapshot has changed', async () => {
    boardApi.fixTrace.mockResolvedValueOnce({
      ...parameterResult(),
      templateSnapshotComparison: 'CHANGED'
    })
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    const apply = wrapper.get('[data-testid="fix-apply-current"]')
    expect(apply.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('fixTemplateSnapshotChangedLimitation')
    // A disabled submit must say why inline, and the button must point at that explanation rather
    // than leaving a screen-reader user with a dead control and no stated reason.
    const describedBy = apply.attributes('aria-describedby')
    expect(describedBy).toBe('fix-apply-readiness')
    expect(wrapper.get(`#${describedBy}`).text()).toContain('fixTemplateSnapshotChangedLimitation')
  })

  it('does not attach a blocked explanation while apply is available', async () => {
    boardApi.fixTrace.mockResolvedValueOnce(parameterResult())
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    const apply = wrapper.get('[data-testid="fix-apply-current"]')
    expect(apply.attributes('disabled')).toBeUndefined()
    // No standing precondition, so nothing to describe — an empty region would be noise.
    expect(apply.attributes('aria-describedby')).toBeUndefined()
    expect(wrapper.find('[data-testid="fix-apply-readiness"]').exists()).toBe(false)
  })

  it('explains an Apply block only while an option is listed', async () => {
    const wrapper = mountDialog()
    await flush()

    // Nothing has been searched, so there is nothing to apply and nothing to explain.
    expect(wrapper.find('[data-testid="fix-apply-readiness"]').exists()).toBe(false)

    boardApi.fixTrace.mockResolvedValueOnce(incompleteSourceModelResult())
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    expect(wrapper.find('[data-testid="fix-apply-current"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="fix-apply-readiness"]').exists()).toBe(false)
    // The refusal is attributed to the incomplete model, not to a comparison that never ran.
    expect(wrapper.text()).toContain('fixSourceModelIncompleteLimitation')
    expect(wrapper.text()).not.toContain('fixTemplateSnapshotUnavailableLimitation')
  })

  it('states a changed template snapshot once, beside the Apply it blocks', async () => {
    const wrapper = await tryParameter({ ...parameterResult(), templateSnapshotComparison: 'CHANGED' })

    expect(wrapper.text().split('fixTemplateSnapshotChangedLimitation').length - 1).toBe(1)
    expect(wrapper.get('[data-testid="fix-apply-readiness"]').text()).toBe('fixTemplateSnapshotChangedLimitation')
  })

  it.each([
    ['no fired rule can influence the specification', noFaultRulesResult],
    ['the source model is incomplete', incompleteSourceModelResult]
  ])('offers no other strategy after a dialog-wide skip because %s', async (_label, resultFactory) => {
    const result = resultFactory()
    const status = result.strategyAttempts[0]!.status
    const wrapper = await tryParameter(result)

    expect(wrapper.find('[data-testid="fix-try-current"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="fix-try-next-strategy"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="fix-result-header"] .iot-dialog__subtitle').text()).toBe('noVerifiedOptions')

    // Another strategy would only return the same skip, so its tab shows that outcome instead of Try.
    await wrapper.get('[data-testid="fix-strategy-condition"]').trigger('click')
    const outcome = wrapper.get('[data-testid="fix-attempt-outcome"]')
    expect(outcome.text()).toContain('fixStrategyNotRunTitle')
    expect(outcome.text()).toContain(`fixAttemptReason.${status}`)
    expect(wrapper.find('[data-testid="fix-try-current"]').exists()).toBe(false)
    expect(boardApi.fixTrace).toHaveBeenCalledOnce()
  })
  it.each([
    ['a cleared bound', [''], 'preferredRangeCompleteFields', [true, false]],
    ['a fractional bound', ['31.5'], 'preferredRangeIntegerBounds', [true, false]],
    ['reversed bounds', ['80', '20'], 'preferredRangeLowerBeforeUpper', [true, true]]
  ] as const)('marks %s inline and keeps Try disabled without a toast', async (
    _label,
    values,
    message,
    invalid
  ) => {
    // INCONCLUSIVE keeps both the ranges and a retry on screen.
    const wrapper = await tryParameter(parameterOutcomeResult('INCONCLUSIVE'))
    const bounds = wrapper.findAll('[data-testid="fix-parameter-range-row"] input[type="number"]')
    for (const [index, value] of values.entries()) await bounds[index]!.setValue(value)

    const issue = wrapper.get('[data-testid="fix-range-issue"]')
    expect(issue.text()).toBe(message)
    const issueId = issue.attributes('id')
    bounds.forEach((input, index) => {
      expect(input.attributes('aria-invalid')).toBe(invalid[index] ? 'true' : undefined)
      expect(input.attributes('aria-describedby')).toBe(invalid[index] ? issueId : undefined)
    })
    const tryButton = wrapper.get('[data-testid="fix-try-current"]')
    expect(tryButton.attributes('disabled')).toBeDefined()
    expect(tryButton.attributes('aria-describedby')).toBe(issueId)

    // A disabled button ignores clicks, so the handler is called the way a stale queued event would.
    await (wrapper.vm as any).trySelectedStrategy()
    expect(boardApi.fixTrace).toHaveBeenCalledOnce()
    expect(elementPlus.warning).not.toHaveBeenCalled()

    await bounds[0]!.setValue('10')
    await bounds[1]!.setValue('90')
    expect(wrapper.find('[data-testid="fix-range-issue"]').exists()).toBe(false)
    expect(bounds[0]!.attributes('aria-invalid')).toBeUndefined()
    expect(wrapper.get('[data-testid="fix-try-current"]').attributes('disabled')).toBeUndefined()
  })

  it('names every range control after its own row target', async () => {
    const wrapper = await tryParameter(jointParameterResult())

    const rows = wrapper.findAll('[data-testid="fix-parameter-range-row"]')
    expect(rows).toHaveLength(2)
    const names = new Set<string>()
    for (const row of rows) {
      const controls = [...row.findAll('input[type="number"]'), row.get('[data-testid="fix-keep-original"]')]
      for (const control of controls) {
        const [targetId, labelId] = control.attributes('aria-labelledby')!.split(' ')
        // Both referenced labels sit in this row, so each name starts with this row's target.
        expect(row.get(`[id="${targetId}"]`).text()).toContain('preferredRangeTargetLabel')
        expect(row.find(`[id="${labelId}"]`).exists()).toBe(true)
        names.add(`${targetId} ${labelId}`)
      }
    }
    expect(names.size).toBe(6)
  })

  it('names the verified mark on a strategy tab for screen readers', async () => {
    const wrapper = await tryParameter(parameterResult())

    const mark = wrapper.get('[data-testid="fix-strategy-parameter"] [data-testid="fix-strategy-verified"]')
    expect(mark.text()).toBe('fixStrategyHasVerifiedOption')
    expect(mark.classes()).toContain('sr-only')
    expect(wrapper.find('[data-testid="fix-strategy-condition"] [data-testid="fix-strategy-verified"]').exists())
      .toBe(false)
  })
  it.each([
    ['the Apply that replaces Try', parameterResult, 'verifiedSolution', '[data-testid="fix-apply-current"]'],
    ['the dialog when no action is left', noFaultRulesResult, 'fixStrategyNotRunTitle', '[role="dialog"]']
  ])('announces a settled search and hands focus to %s', async (_label, resultFactory, announced, focusTarget) => {
    const pending = deferred<FixResult>()
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    const wrapper = mount(FixResultDialog, {
      props: { visible: true, traceId: 7, violatedSpecId: 'spec-1' },
      global: { plugins: [i18n] },
      attachTo: document.body
    })
    mountedDialogs.push(wrapper)
    await flush()

    const tryButton = wrapper.get('[data-testid="fix-try-current"]')
    ;(tryButton.element as HTMLButtonElement).focus()
    await tryButton.trigger('click')
    const announcement = wrapper.get('[data-testid="fix-search-announcement"]')
    expect(announcement.attributes('aria-live')).toBe('polite')
    expect(announcement.text()).toBe('tryingFixStrategy')

    pending.resolve(resultFactory())
    await flush()
    await wrapper.vm.$nextTick()

    expect(announcement.text()).toBe(announced)
    expect(document.activeElement).toBe(wrapper.get(focusTarget).element)
  })

  it('says why Apply waits while another strategy searches', async () => {
    const wrapper = await tryParameter(parameterResult())
    const pending = deferred<FixResult>()
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    await wrapper.get('[data-testid="fix-strategy-condition"]').trigger('click')
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await wrapper.get('[data-testid="fix-strategy-parameter"]').trigger('click')

    const apply = wrapper.get('[data-testid="fix-apply-current"]')
    expect(apply.attributes('disabled')).toBeDefined()
    expect(apply.attributes('aria-describedby')).toBe('fix-apply-readiness')
    expect(wrapper.get('[data-testid="fix-apply-readiness"]').text()).toBe('fixApplyWaitsForSearch')
    // The footer owns this fact while an option is listed; the body does not repeat it.
    expect(wrapper.find('[data-testid="fix-another-strategy-running"]').exists()).toBe(false)

    // Without an option, the body states it and the disabled Try points there.
    await wrapper.get('[data-testid="fix-strategy-remove"]').trigger('click')
    expect(wrapper.find('[data-testid="fix-another-strategy-running"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="fix-try-current"]').attributes('aria-describedby'))
      .toBe('fix-another-strategy-running')

    pending.resolve(conditionResult())
    await flush()
    await wrapper.get('[data-testid="fix-strategy-parameter"]').trigger('click')

    expect(wrapper.get('[data-testid="fix-apply-current"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-testid="fix-apply-readiness"]').exists()).toBe(false)
  })
  it('toasts a failed search only when its strategy is no longer on screen', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const rejection = { response: { status: 503, data: { message: 'Unavailable' } } }
    boardApi.fixTrace.mockRejectedValueOnce(rejection)
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()

    // Still selected: the inline error card is the one statement of the failure.
    expect(wrapper.text()).toContain('fixStrategyRequestFailed')
    expect(elementPlus.error).not.toHaveBeenCalled()

    const pending = deferred<FixResult>()
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    await wrapper.get('[data-testid="fix-strategy-condition"]').trigger('click')
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await wrapper.get('[data-testid="fix-strategy-remove"]').trigger('click')
    pending.reject(rejection)
    await flush()

    expect(elementPlus.error).toHaveBeenCalledOnce()
    await wrapper.get('[data-testid="fix-strategy-condition"]').trigger('click')
    expect(wrapper.text()).toContain('fixStrategyRequestFailed')
    errorSpy.mockRestore()
  })

  it('states search progress once, in the loading panel, and keeps the header static', async () => {
    const pending = deferred<FixResult>()
    boardApi.fixTrace.mockReturnValueOnce(pending.promise)
    const wrapper = mountDialog()
    await flush()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')

    expect(wrapper.get('[data-testid="fix-strategy-loading"]').findAll('p').map(line => line.text()))
      .toEqual(['fixProgressStage_QUEUED', 'fixSearchProgress'])
    expect(wrapper.get('[data-testid="fix-result-header"] .iot-dialog__subtitle').text())
      .toBe('selectFixStrategyPrompt')

    pending.resolve(parameterResult())
    await flush()
  })

  it.each([
    ['TIMED_OUT', 'fixStrategyTimedOutTitle'],
    // The request's time limit ran out before this strategy started, so it never ran at all.
    ['SKIPPED_TIMEOUT', 'fixStrategyNotRunTitle']
  ] as const)('titles a %s attempt as %s and keeps its retry', async (status, title) => {
    const wrapper = await tryParameter(parameterOutcomeResult(status))

    const outcome = wrapper.get('[data-testid="fix-attempt-outcome"]')
    expect(outcome.text()).toContain(title)
    expect(outcome.text()).toContain(`fixAttemptReason.${status}`)
    expect(wrapper.get('[data-testid="fix-try-current"]').text()).toContain('retryFixStrategy')
  })

  it('keeps "yet" in the header while an outcome could still change on retry', async () => {
    boardApi.fixTrace
      .mockResolvedValueOnce(parameterOutcomeResult('NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE'))
      .mockResolvedValueOnce(conditionWithoutSuggestionResult())
      .mockResolvedValueOnce({
        ...removeResult(),
        suggestions: [],
        strategyAttempts: [{ strategy: 'remove', status: 'TIMED_OUT', reason: 'Server reason.' }],
        fixable: false
      })
    const wrapper = mountDialog()
    await flush()
    const subtitle = () => wrapper.get('[data-testid="fix-result-header"] .iot-dialog__subtitle').text()
    await wrapper.get('[data-testid="fix-try-current"]').trigger('click')
    await flush()
    expect(subtitle()).toBe('noVerifiedSolutionsYet')
    await wrapper.get('[data-testid="fix-try-next-strategy"]').trigger('click')
    await flush()
    await wrapper.get('[data-testid="fix-try-next-strategy"]').trigger('click')
    await flush()

    // Two proofs and a timeout: the timed-out strategy may still find an option.
    expect(wrapper.find('[data-testid="fix-try-current"]').exists()).toBe(true)
    expect(subtitle()).toBe('noVerifiedSolutionsYet')
  })
})
