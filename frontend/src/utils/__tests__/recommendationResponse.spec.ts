// @vitest-environment node
import { describe, expect, it } from 'vitest'
import {
  RECOMMENDATION_RESPONSE_INCOMPLETE_CODE,
  validateScenarioRecommendationResponse,
  validateStandaloneRecommendationResponse
} from '../recommendationResponse'

const validStandalone = () => ({
  message: 'One candidate was kept.',
  count: 1,
  requestedCount: 3,
  validatedCount: 1,
  filteredCount: 1,
  filteredItems: [{
    type: 'rule',
    index: 2,
    reasonCode: 'unknownAction',
    reason: 'Unknown action'
  }],
  rawCandidateCount: 3,
  inspectedCount: 2,
  truncatedCount: 1,
  recommendations: [{ description: 'Keep this one' }]
})

const partialScenarioObjective = {
  objectiveTargets: { minDevices: 1, minRules: 1, minSpecs: 1 },
  objectiveStatus: 'PARTIAL',
  objectiveIssues: [
    { code: 'NO_AUTOMATION_RULES', message: 'Add an automation rule.' },
    { code: 'NO_SPECIFICATIONS', message: 'Add a formal specification.' }
  ]
}

describe('recommendation candidate accounting', () => {
  it('accepts kept, rejected, and uninspected candidates when all counts reconcile', () => {
    expect(validateStandaloneRecommendationResponse(validStandalone(), 'Rules')).toEqual(validStandalone())
  })

  it('runs candidate validation before returning a kept recommendation', () => {
    expect(() => validateStandaloneRecommendationResponse(
      validStandalone(),
      'Rules',
      () => { throw new Error('candidate is not applicable') }
    )).toThrow('candidate is not applicable')
  })

  it('requires adjustment accounting when the endpoint contract promises normalized candidates', () => {
    expect(() => validateStandaloneRecommendationResponse(
      validStandalone(),
      'Rules',
      undefined,
      true
    )).toThrow(expect.objectContaining({
      code: RECOMMENDATION_RESPONSE_INCOMPLETE_CODE
    }))

    const withAdjustments = {
      ...validStandalone(),
      adjustedCount: 1,
      adjustedItems: [{
        type: 'rule',
        index: 1,
        reasonCode: 'apiEventSyntaxNormalized',
        reason: 'Equivalent event syntax normalized.',
        label: 'Keep this one',
        appliedValues: { sourceApi: 'motionDetected' }
      }]
    }
    expect(validateStandaloneRecommendationResponse(
      withAdjustments,
      'Rules',
      undefined,
      true
    )).toEqual(withAdjustments)
  })

  it('rejects a filtered count without the same number of itemized reasons', () => {
    expect(() => validateStandaloneRecommendationResponse({
      ...validStandalone(),
      filteredItems: []
    }, 'Rules')).toThrow(expect.objectContaining({
      code: RECOMMENDATION_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('rejects candidates that disappear from raw-to-inspected accounting', () => {
    expect(() => validateStandaloneRecommendationResponse({
      ...validStandalone(),
      rawCandidateCount: 4
    }, 'Rules')).toThrow(expect.objectContaining({
      code: RECOMMENDATION_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('rejects a scenario that applied scene items it never claims to have validated', () => {
    // The accounting strip is the user's only evidence for where the applied scene came from. A
    // payload reporting zero inspected candidates while handing over four scene items would render
    // "raw 0, inspected 0, kept 0" beside four devices and rules on the canvas.
    expect(() => validateScenarioRecommendationResponse({
      message: 'Scenario generated.',
      count: 4,
      requestedCount: 4,
      validatedCount: 0,
      filteredCount: 0,
      filteredItems: [],
      adjustedCount: 0,
      adjustedItems: [],
      rawCandidateCount: 0,
      inspectedCount: 0,
      truncatedCount: 0,
      scenarioName: 'Home',
      rationale: '',
      ...partialScenarioObjective,
      verificationReady: false,
      readinessIssues: [],
      semanticWarnings: [],
      scene: {
        templates: [],
        devices: [{}, {}],
        environmentVariables: [],
        rules: [{}, {}],
        specs: []
      }
    }, 'Scenario')).toThrow(/validated/i)
  })

  it('accepts a scenario whose final count includes a synthesized environment value', () => {
    const result = {
      message: 'Scenario generated.',
      count: 2,
      requestedCount: 3,
      validatedCount: 1,
      filteredCount: 0,
      filteredItems: [],
      adjustedCount: 1,
      adjustedItems: [{
        type: 'environment',
        reasonCode: 'missingEnvironmentAdded',
        reason: 'A required environment value was added.',
        label: 'temperature',
        appliedValues: { value: '20', trust: 'trusted', privacy: 'public' }
      }],
      rawCandidateCount: 1,
      inspectedCount: 1,
      truncatedCount: 0,
      scenarioName: 'Home',
      rationale: '',
      ...partialScenarioObjective,
      verificationReady: false,
      readinessIssues: [{ code: 'NO_SPECIFICATIONS', message: 'Add a specification.' }],
      semanticWarnings: [{
        code: 'NO_AUTOMATION_RULES',
        message: 'The final draft contains no automation rules.'
      }],
      scene: {
        templates: [],
        devices: [{}],
        environmentVariables: [{}],
        rules: [],
        specs: []
      }
    }

    expect(validateScenarioRecommendationResponse(result, 'Scenario')).toEqual(result)
    expect(() => validateScenarioRecommendationResponse({
      ...result,
      objectiveStatus: 'COMPLETE',
      objectiveIssues: []
    }, 'Scenario')).toThrow(expect.objectContaining({
      code: RECOMMENDATION_RESPONSE_INCOMPLETE_CODE
    }))

    const belowExplicitTargets = {
      ...result,
      requestedCount: 5,
      objectiveTargets: { minDevices: 2, minRules: 2, minSpecs: 1 },
      objectiveIssues: [
        { code: 'INSUFFICIENT_DEVICES', message: 'At least two devices are required.' },
        { code: 'NO_AUTOMATION_RULES', message: 'At least two rules are required.' },
        { code: 'NO_SPECIFICATIONS', message: 'At least one specification is required.' }
      ]
    }
    expect(validateScenarioRecommendationResponse(
      belowExplicitTargets,
      'Scenario',
      belowExplicitTargets.objectiveTargets
    )).toEqual(belowExplicitTargets)
    expect(() => validateScenarioRecommendationResponse(
      belowExplicitTargets,
      'Scenario',
      { minDevices: 1, minRules: 1, minSpecs: 1 }
    )).toThrow(expect.objectContaining({
      code: RECOMMENDATION_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('requires scenario adjustment details to match their count', () => {
    expect(() => validateScenarioRecommendationResponse({
      message: 'Scenario generated.',
      count: 1,
      requestedCount: 3,
      validatedCount: 1,
      filteredCount: 0,
      filteredItems: [],
      adjustedCount: 1,
      adjustedItems: [],
      rawCandidateCount: 1,
      inspectedCount: 1,
      truncatedCount: 0,
      scenarioName: 'Home',
      rationale: '',
      ...partialScenarioObjective,
      verificationReady: false,
      readinessIssues: [{ code: 'NO_SPECIFICATIONS', message: 'Add a specification.' }],
      semanticWarnings: [{
        code: 'NO_AUTOMATION_RULES',
        message: 'The final draft contains no automation rules.'
      }],
      scene: { templates: [], devices: [{}], environmentVariables: [], rules: [], specs: [] }
    }, 'Scenario')).toThrow(expect.objectContaining({
      code: RECOMMENDATION_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('rejects scenario readiness that contradicts the scene', () => {
    expect(() => validateScenarioRecommendationResponse({
      message: 'Scenario generated.',
      count: 1,
      requestedCount: 3,
      validatedCount: 1,
      filteredCount: 0,
      filteredItems: [],
      adjustedCount: 0,
      adjustedItems: [],
      rawCandidateCount: 1,
      inspectedCount: 1,
      truncatedCount: 0,
      scenarioName: 'Home',
      rationale: '',
      ...partialScenarioObjective,
      verificationReady: true,
      readinessIssues: [],
      semanticWarnings: [{
        code: 'NO_AUTOMATION_RULES',
        message: 'The final draft contains no automation rules.'
      }],
      scene: { templates: [], devices: [{}], environmentVariables: [], rules: [], specs: [] }
    }, 'Scenario')).toThrow(expect.objectContaining({
      code: RECOMMENDATION_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('rejects semantic warnings that omit a filtered-candidate coverage warning', () => {
    expect(() => validateScenarioRecommendationResponse({
      message: 'Scenario generated.',
      count: 1,
      requestedCount: 3,
      validatedCount: 1,
      filteredCount: 1,
      filteredItems: [{
        type: 'rule', index: 1, reasonCode: 'invalidRuleSources', reason: 'Invalid source'
      }],
      adjustedCount: 0,
      adjustedItems: [],
      rawCandidateCount: 2,
      inspectedCount: 2,
      truncatedCount: 0,
      scenarioName: 'Home',
      rationale: 'Final retained content only.',
      ...partialScenarioObjective,
      verificationReady: false,
      readinessIssues: [{ code: 'NO_SPECIFICATIONS', message: 'Add a specification.' }],
      semanticWarnings: [{
        code: 'NO_AUTOMATION_RULES',
        message: 'The final draft contains no automation rules.'
      }],
      scene: { templates: [], devices: [{}], environmentVariables: [], rules: [], specs: [] }
    }, 'Scenario')).toThrow(expect.objectContaining({
      code: RECOMMENDATION_RESPONSE_INCOMPLETE_CODE
    }))
  })

  it('rejects a filtered candidate without an itemized reason code', () => {
    expect(() => validateStandaloneRecommendationResponse({
      ...validStandalone(),
      filteredItems: [{ type: 'rule', index: 2, reason: 'Unknown action' }]
    }, 'Rules')).toThrow(expect.objectContaining({
      code: RECOMMENDATION_RESPONSE_INCOMPLETE_CODE
    }))
  })
})
