import { describe, expect, it } from 'vitest'
import {
  FIX_RESPONSE_INCOMPLETE_CODE,
  validateFaultLocalizationResult,
  validateFixResult,
  validateFixSuggestion
} from '../fixResponse'

const faultRule = {
  ruleString: 'When hall motion is detected, turn on the light',
  transitionNumber: 2,
  targetDeviceLabel: 'Hall light',
  targetActionLabel: 'Turn on',
  conflicting: false,
  reasonCode: 'TRIGGERED',
  reason: 'This automation fired before the violated state.',
  modelTokenSource: 'BUNDLED'
}

const parameterSuggestion = {
  suggestionToken: 'signed-parameter-suggestion',
  strategy: 'parameter',
  description: 'Change the temperature threshold from 30 to 25.',
  parameterAdjustments: [{
    targetId: 'param_abcdefghijklmnopqrstuvwx',
    attribute: 'temperature',
    relation: '>',
    originalValue: '30',
    newValue: '25',
    lowerBound: 10,
    upperBound: 40,
    description: 'Living room temperature threshold in the cooling rule',
    modelTokenSource: 'BUNDLED'
  }],
  conditionAdjustments: [],
  removedRuleDescriptions: [],
  preexistingViolations: []
}

const conditionSuggestion = {
  suggestionToken: 'signed-condition-suggestion',
  strategy: 'condition',
  description: 'Only heat while the room is occupied.',
  parameterAdjustments: [],
  conditionAdjustments: [{
    action: 'add',
    attribute: 'occupied',
    targetType: 'variable',
    description: 'Require an occupied room before heating.',
    ruleDescription: 'When it is cold, heat the room',
    deviceLabel: 'Living-room Occupancy Sensor',
    relation: '=',
    value: 'present',
    modelTokenSource: 'CUSTOM'
  }],
  removedRuleDescriptions: [],
  preexistingViolations: []
}

const removeSuggestion = {
  suggestionToken: 'signed-remove-suggestion',
  strategy: 'remove',
  description: 'Remove the automation that takes a camera photo.',
  parameterAdjustments: [],
  conditionAdjustments: [],
  removedRuleDescriptions: ['When hall motion is active, take a camera photo'],
  preexistingViolations: []
}

const localization = () => ({
  traceId: 12,
  violatedSpecId: 'spec_1',
  sourceModelComplete: true,
  sourceDisabledRuleCount: 0,
  sourceSkippedSpecCount: 0,
  sourceGenerationIssues: [],
  faultRules: [faultRule],
  summary: 'One automation was involved; this does not prove independent causation.',
  warnings: []
})

const fixResult = () => ({
  ...localization(),
  suggestions: [parameterSuggestion],
  strategyAttempts: [{
    strategy: 'parameter',
    status: 'VERIFIED',
    reason: 'The concrete suggestion passed forward verification.',
    alternativesComplete: true
  }],
  fixable: true,
  templateSnapshotComparison: 'UNCHANGED',
  summary: 'One forward-verified suggestion was found.',
  warnings: [],
  parameterTargets: [{
    targetId: 'param_abcdefghijklmnopqrstuvwx',
    attribute: 'temperature',
    relation: '>',
    originalValue: '30',
    lowerBound: 10,
    upperBound: 40,
    description: 'Living room temperature threshold in the cooling rule',
    modelTokenSource: 'BUNDLED'
  }],
  unusedPreferredRangeSelections: []
})

const fixResultFor = (suggestion: typeof parameterSuggestion | typeof conditionSuggestion | typeof removeSuggestion) => ({
  ...fixResult(),
  suggestions: [suggestion],
  strategyAttempts: [{
    strategy: suggestion.strategy,
    status: 'VERIFIED',
    reason: 'The concrete suggestion passed forward verification.',
    alternativesComplete: true
  }],
  parameterTargets: suggestion.strategy === 'parameter' ? fixResult().parameterTargets : []
})

describe('automatic-fix response contracts', () => {
  it('rejects a VERIFIED attempt that produced no suggestion', () => {
    // The dialog renders the attempt's status and reason, so this payload claims a concrete
    // suggestion passed forward verification while showing the no-suggestion empty state and no
    // Apply control. `fixable` stays consistently false, so nothing else catches it.
    const base = fixResultFor(parameterSuggestion)
    expect(() => validateFixResult({
      ...base,
      fixable: false,
      suggestions: []
    }, 12, ['parameter'])).toThrow(/attempt/i)
  })


  it('accepts itemized fault localization on a complete source model', () => {
    expect(validateFaultLocalizationResult(localization(), 12)).toEqual(localization())
  })

  it('requires an incomplete source model to explain the limitation', () => {
    expect(() => validateFaultLocalizationResult({
      ...localization(),
      sourceModelComplete: false
    }, 12)).toThrow(expect.objectContaining({
      code: FIX_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('accepts a concrete forward-verified parameter suggestion', () => {
    expect(validateFixSuggestion(parameterSuggestion, 'Fix suggestion', 'parameter'))
      .toEqual(parameterSuggestion)
    expect(validateFixResult(fixResult(), 12, ['parameter'])).toEqual(fixResult())
  })

  it('accepts a verified suggestion that names the specifications the original rules already violated', () => {
    const suggestion = {
      ...parameterSuggestion,
      preexistingViolations: [{ specId: 'spec_7', templateId: '3', formulaPreview: 'CTL AG NOT (co2 > 1000)' }]
    }
    expect(validateFixSuggestion(suggestion, 'Fix suggestion', 'parameter')).toEqual(suggestion)
  })

  it('rejects pre-existing violations that are missing or malformed', () => {
    const { preexistingViolations: _omitted, ...missing } = parameterSuggestion
    expect(() => validateFixSuggestion(missing, 'Fix suggestion', 'parameter'))
      .toThrow(/preexistingViolations must be an array/)
    expect(() => validateFixSuggestion({
      ...parameterSuggestion,
      preexistingViolations: [{ specId: 'spec_7', templateId: '3', formulaPreview: '' }]
    }, 'Fix suggestion', 'parameter')).toThrow(/formulaPreview must be non-blank text/)
  })

  it('rejects a fix result that omits an array required by the backend contract', () => {
    const { unusedPreferredRangeSelections: _unused, ...incomplete } = fixResult()

    expect(() => validateFixResult(incomplete, 12, ['parameter'])).toThrow(
      expect.objectContaining({ code: FIX_RESPONSE_INCOMPLETE_CODE })
    )
  })

  it('requires explicit model-token provenance for user-visible fix rows', () => {
    const { modelTokenSource: _source, ...targetWithoutSource } = fixResult().parameterTargets[0]
    expect(() => validateFixResult({
      ...fixResult(),
      parameterTargets: [targetWithoutSource]
    }, 12, ['parameter'])).toThrow(expect.objectContaining({
      code: FIX_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it.each([
    ['parameter', parameterSuggestion],
    ['condition', conditionSuggestion],
    ['remove', removeSuggestion]
  ] as const)('accepts the concrete %s strategy response shape used by the UI', (strategy, suggestion) => {
    expect(validateFixSuggestion(suggestion, 'Fix suggestion', strategy)).toEqual(suggestion)
    const result = fixResultFor(suggestion)
    expect(validateFixResult(result, 12, [strategy])).toEqual(result)
  })

  it('accepts one default response containing all three verified strategies', () => {
    const result = {
      ...fixResult(),
      suggestions: [parameterSuggestion, conditionSuggestion, removeSuggestion],
      strategyAttempts: ['parameter', 'condition', 'remove'].map(strategy => ({
        strategy,
        status: 'VERIFIED',
        reason: 'The concrete suggestion passed forward verification.',
        alternativesComplete: true
      }))
    }

    expect(validateFixResult(result, 12, [])).toEqual(result)
  })

  it('accepts several alternatives listed by one strategy', () => {
    const second = {
      ...parameterSuggestion,
      suggestionToken: 'signed-parameter-alternative',
      parameterAdjustments: [{ ...parameterSuggestion.parameterAdjustments[0], newValue: '22' }]
    }
    const result = {
      ...fixResult(),
      suggestions: [parameterSuggestion, second],
      strategyAttempts: [{ ...fixResult().strategyAttempts[0], alternativesComplete: false }]
    }

    expect(validateFixResult(result, 12, ['parameter'])).toEqual(result)
  })

  it('requires a VERIFIED attempt to state whether its listing is complete', () => {
    const { alternativesComplete: _omitted, ...attempt } = fixResult().strategyAttempts[0]

    expect(() => validateFixResult({ ...fixResult(), strategyAttempts: [attempt] }, 12, ['parameter']))
      .toThrow(/alternativesComplete must be boolean/)
  })

  it('rejects listing completeness on an attempt that listed nothing', () => {
    expect(() => validateFixResult({
      ...fixResult(),
      suggestions: [],
      fixable: false,
      strategyAttempts: [{
        strategy: 'parameter',
        status: 'ALL_CANDIDATES_REJECTED',
        reason: 'Forward verification rejected every candidate.',
        alternativesComplete: true
      }]
    }, 12, ['parameter'])).toThrow(/alternativesComplete is only meaningful on VERIFIED/)
  })

  it('rejects a suggestion whose strategy did not report VERIFIED', () => {
    expect(() => validateFixResult({
      ...fixResult(),
      strategyAttempts: [{
        strategy: 'parameter',
        status: 'TIMED_OUT',
        reason: 'The strategy did not finish.'
      }]
    }, 12, ['parameter'])).toThrow(/a suggestion needs a VERIFIED strategy attempt/)
  })

  it('rejects a fixable flag that denies the listed suggestions', () => {
    expect(() => validateFixResult({ ...fixResult(), fixable: false }, 12, ['parameter']))
      .toThrow(/fixable must match the listed suggestions/)
  })

  it('does not trust a fixable flag without a verified suggestion', () => {
    expect(() => validateFixResult({
      ...fixResult(),
      suggestions: [],
      strategyAttempts: [{
        strategy: 'parameter',
        status: 'INCONCLUSIVE',
        reason: 'The search ended without settling every candidate.'
      }]
    }, 12, ['parameter'])).toThrow(expect.objectContaining({
      code: FIX_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('rejects suggestions derived from an explicitly incomplete source model', () => {
    expect(() => validateFixResult({
      ...fixResult(),
      sourceModelComplete: false,
      sourceDisabledRuleCount: 1,
      sourceGenerationIssues: [{
        issueType: 'RULE_DISABLED',
        itemLabel: 'Cooling rule',
        reasonCode: 'RULE_UNRESOLVABLE_TRIGGER_CONDITION',
        reason: 'The target action is unavailable.'
      }],
      warnings: ['The source model was incomplete.']
    }, 12, ['parameter'])).toThrow(expect.objectContaining({
      code: FIX_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('requires source omission counts to match itemized reasons', () => {
    expect(() => validateFixResult({
      ...fixResult(),
      sourceModelComplete: false,
      sourceSkippedSpecCount: 1,
      suggestions: [],
      fixable: false,
      strategyAttempts: [{
        strategy: 'parameter',
        status: 'SKIPPED_INCOMPLETE_SOURCE_MODEL',
        reason: 'The source model was incomplete.'
      }],
      warnings: ['The source model was incomplete.']
    }, 12, ['parameter'])).toThrow(expect.objectContaining({
      code: FIX_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('rejects specification-only trust conditions in a rule adjustment', () => {
    expect(() => validateFixSuggestion({
      suggestionToken: 'signed-condition-suggestion',
      strategy: 'condition',
      description: 'Add a trust condition',
      parameterAdjustments: [],
      conditionAdjustments: [{
        action: 'add',
        attribute: 'temperature',
        targetType: 'trust',
        description: 'Add trust predicate',
        ruleDescription: 'Cooling rule',
        deviceLabel: 'Temperature sensor',
        relation: '=',
        value: 'trusted'
      }],
      removedRuleDescriptions: []
    }, 'Fix suggestion', 'condition')).toThrow(expect.objectContaining({
      code: FIX_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('accepts a distinct candidate-model generation failure outcome', () => {
    const result = {
      ...fixResult(),
      suggestions: [],
      strategyAttempts: [{
        strategy: 'parameter',
        status: 'FAILED_MODEL_GENERATION',
        reason: 'The counterexample initial state is incomplete.'
      }],
      fixable: false,
      warnings: ['The counterexample initial state is incomplete.']
    }

    expect(validateFixResult(result, 12, ['parameter'])).toEqual(result)
  })

  it('accepts solver failure and budget exhaustion as distinct outcomes', () => {
    const solverFailure = {
      ...fixResult(),
      suggestions: [],
      strategyAttempts: [{
        strategy: 'parameter',
        status: 'FAILED_SOLVER_EXECUTION',
        reason: 'NuSMV returned incomplete output.'
      }],
      fixable: false
    }
    const budgetExhausted = {
      ...fixResult(),
      suggestions: [],
      strategyAttempts: [{
        strategy: 'parameter',
        status: 'SEARCH_BUDGET_EXHAUSTED',
        reason: 'Unchecked candidates remain.'
      }],
      fixable: false
    }

    expect(validateFixResult(solverFailure, 12, ['parameter'])).toEqual(solverFailure)
    expect(validateFixResult(budgetExhausted, 12, ['parameter'])).toEqual(budgetExhausted)
  })

  it('accepts parameter targets even when the search found no suggestion', () => {
    const result = {
      ...fixResult(),
      suggestions: [],
      strategyAttempts: [{
        strategy: 'parameter',
        status: 'NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE',
        reason: 'No threshold values within the allowed ranges prevent this counterexample.'
      }],
      fixable: false
    }

    expect(validateFixResult(result, 12, ['parameter'])).toEqual(result)
    expect(result.parameterTargets).toHaveLength(1)
  })

  // The status was split into what the search proved; a server still sending it predates that split.
  it('rejects the retired NO_VERIFIED_SUGGESTION status', () => {
    expect(() => validateFixResult({
      ...fixResult(),
      suggestions: [],
      strategyAttempts: [{
        strategy: 'parameter',
        status: 'NO_VERIFIED_SUGGESTION',
        reason: 'No candidate passed forward verification.'
      }],
      fixable: false
    }, 12, ['parameter'])).toThrow(/strategyAttempts\[0\]\.status is invalid/)
  })
})

describe('a fault rule without a preview string', () => {
  /*
   * RuleDto.ruleString has no @NotBlank and a nullable TEXT column, so a rule persists without one - verified
   * against the running API, which accepts a rule with the field omitted and echoes "ruleString": null. This
   * validator used text(row, 'ruleString'), which throws on null and rejected the *entire* fault-localization
   * response, so a fix request on a trace involving such a rule failed as "malformed result" instead of
   * returning the fix. The localised `Rule {number}` fallback belongs in the UI, which is where three other
   * components already put it for TraceRule.ruleLabel.
   */
  it('is accepted, because null is legitimate rather than a contract breach', () => {
    const withoutPreview = { ...localization(), faultRules: [{ ...faultRule, ruleString: null }] }

    expect(validateFaultLocalizationResult(withoutPreview, 12)).toEqual(withoutPreview)
  })

  it('is still rejected when the field is present but not text', () => {
    const wrongType = { ...localization(), faultRules: [{ ...faultRule, ruleString: 42 }] }

    expect(() => validateFaultLocalizationResult(wrongType, 12)).toThrowError(/ruleString/)
  })
})

describe('a conflicting fault rule', () => {
  /*
   * The whole `conflicting` branch of validateFaultRule was untested: the shared fixture only ever set
   * `conflicting: false`. It matters now because `conflictingRuleString` became nullable for the same reason
   * `ruleString` did - the conflicting rule may have no preview of its own, and `conflicting` is set
   * independently of it, so true-with-null is reachable. The server used to send the English prose
   * "another localized rule" here, which the UI interpolated into an already-translated sentence.
   */
  const conflictingRule = (overrides = {}) => ({
    ...faultRule,
    conflicting: true,
    reasonCode: 'CONFLICTING_END_STATES',
    conflictingRuleString: 'Turn light off',
    targetEndState: 'on',
    conflictingEndState: 'off',
    ...overrides
  })

  const withRule = (rule: unknown) => ({ ...localization(), faultRules: [rule] })

  it('is accepted with a preview, and with a null one', () => {
    expect(validateFaultLocalizationResult(withRule(conflictingRule()), 12))
      .toEqual(withRule(conflictingRule()))

    const unnamed = withRule(conflictingRule({ conflictingRuleString: null }))
    expect(validateFaultLocalizationResult(unnamed, 12)).toEqual(unnamed)
  })

  it('still rejects a non-string conflicting preview', () => {
    expect(() => validateFaultLocalizationResult(
      withRule(conflictingRule({ conflictingRuleString: 7 })), 12)).toThrowError(/conflictingRuleString/)
  })

  it('still requires the conflicting reason code', () => {
    expect(() => validateFaultLocalizationResult(
      withRule(conflictingRule({ reasonCode: 'TRIGGERED' })), 12)).toThrowError(/conflicting reason code/)
  })
})
