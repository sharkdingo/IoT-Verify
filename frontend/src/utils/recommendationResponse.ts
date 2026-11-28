export const RECOMMENDATION_RESPONSE_INCOMPLETE_CODE = 'RECOMMENDATION_RESPONSE_INCOMPLETE'

class RecommendationResponseContractError extends Error {
  readonly code = RECOMMENDATION_RESPONSE_INCOMPLETE_CODE

  constructor(context: string, detail: string) {
    super(`${context} returned inconsistent candidate accounting: ${detail}`)
    this.name = 'RecommendationResponseContractError'
  }
}

const requireRecord = (value: unknown, context: string): Record<string, any> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new RecommendationResponseContractError(context, 'result must be an object')
  }
  return value as Record<string, any>
}

const requireArray = (result: Record<string, any>, field: string, context: string): any[] => {
  if (!Array.isArray(result[field])) {
    throw new RecommendationResponseContractError(context, `${field} must be an array`)
  }
  return result[field]
}

const requireCount = (result: Record<string, any>, field: string, context: string): number => {
  const value = result[field]
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new RecommendationResponseContractError(context, `${field} must be a non-negative integer`)
  }
  return value
}

const requireObjectiveTarget = (
  result: Record<string, any>,
  field: string,
  context: string
): number => {
  const value = result[field]
  if (!Number.isSafeInteger(value) || value < 1 || value > 10) {
    throw new RecommendationResponseContractError(
      context,
      `${field} must be an integer between 1 and 10`
    )
  }
  return value
}

const requireNonBlankText = (value: unknown, field: string, context: string): string => {
  if (typeof value !== 'string' || !value.trim()) {
    throw new RecommendationResponseContractError(context, `${field} must be non-blank text`)
  }
  return value
}

const validateAuditItems = (items: unknown[], field: 'filteredItems' | 'adjustedItems', context: string) => {
  const adjustment = field === 'adjustedItems'
  items.forEach((item, index) => {
    const prefix = `${field}[${index}]`
    const row = requireRecord(item, context)
    requireNonBlankText(row.type, `${prefix}.type`, context)
    if ((!adjustment || (row.index !== undefined && row.index !== null))
      && (!Number.isSafeInteger(row.index) || row.index < 1)) {
      const suffix = adjustment ? ' when present' : ''
      throw new RecommendationResponseContractError(context, `${prefix}.index must be a positive integer${suffix}`)
    }
    requireNonBlankText(row.reasonCode, `${prefix}.reasonCode`, context)
    requireNonBlankText(row.reason, `${prefix}.reason`, context)
    if (row.label !== undefined && row.label !== null && typeof row.label !== 'string') {
      throw new RecommendationResponseContractError(context, `${prefix}.label must be text when present`)
    }
    if (adjustment && (!row.appliedValues || typeof row.appliedValues !== 'object' || Array.isArray(row.appliedValues))) {
      throw new RecommendationResponseContractError(context, `${prefix}.appliedValues must be an object`)
    }
  })
}

const validateAccounting = (
  result: Record<string, any>,
  expectedFinalCount: number,
  context: string,
  requireAdjustments: boolean,
  expectedValidatedCount?: number
) => {
  if (typeof result.message !== 'string' || !result.message.trim()) {
    throw new RecommendationResponseContractError(context, 'message is required')
  }
  const count = requireCount(result, 'count', context)
  requireCount(result, 'requestedCount', context)
  const validatedCount = requireCount(result, 'validatedCount', context)
  const filteredCount = requireCount(result, 'filteredCount', context)
  const rawCandidateCount = requireCount(result, 'rawCandidateCount', context)
  const inspectedCount = requireCount(result, 'inspectedCount', context)
  const truncatedCount = requireCount(result, 'truncatedCount', context)
  const filteredItems = requireArray(result, 'filteredItems', context)

  if (count !== expectedFinalCount) {
    throw new RecommendationResponseContractError(context, 'count must match final returned items')
  }
  if (expectedValidatedCount !== undefined && validatedCount !== expectedValidatedCount) {
    throw new RecommendationResponseContractError(context, 'validatedCount must match kept raw candidates')
  }
  if (filteredCount !== filteredItems.length) {
    throw new RecommendationResponseContractError(context, 'filteredCount must match filteredItems')
  }
  validateAuditItems(filteredItems, 'filteredItems', context)
  if (inspectedCount !== validatedCount + filteredCount) {
    throw new RecommendationResponseContractError(context, 'inspectedCount must equal kept plus rejected candidates')
  }
  if (rawCandidateCount !== inspectedCount + truncatedCount) {
    throw new RecommendationResponseContractError(context, 'rawCandidateCount must equal inspected plus uninspected candidates')
  }

  const hasAdjustmentFields = Object.prototype.hasOwnProperty.call(result, 'adjustedCount')
    || Object.prototype.hasOwnProperty.call(result, 'adjustedItems')
  if (requireAdjustments || hasAdjustmentFields) {
    const adjustedCount = requireCount(result, 'adjustedCount', context)
    const adjustedItems = requireArray(result, 'adjustedItems', context)
    if (adjustedCount !== adjustedItems.length) {
      throw new RecommendationResponseContractError(context, 'adjustedCount must match adjustedItems')
    }
    validateAuditItems(adjustedItems, 'adjustedItems', context)
  }
}

export const validateStandaloneRecommendationResponse = <T>(
  value: unknown,
  context: string,
  validateCandidate?: (value: unknown, index: number) => void,
  requireAdjustments = false
): T => {
  const result = requireRecord(value, context)
  const recommendations = requireArray(result, 'recommendations', context)
  validateAccounting(result, recommendations.length, context, requireAdjustments, recommendations.length)
  recommendations.forEach((candidate, index) => validateCandidate?.(candidate, index))
  return result as T
}

export interface ScenarioObjectiveTargetsContract {
  minDevices: number
  minRules: number
  minSpecs: number
}

export const validateScenarioRecommendationResponse = <T>(
  value: unknown,
  context: string,
  expectedTargets?: ScenarioObjectiveTargetsContract
): T => {
  const result = requireRecord(value, context)
  if (!result.scene || typeof result.scene !== 'object' || Array.isArray(result.scene)) {
    throw new RecommendationResponseContractError(context, 'scene is required')
  }
  const scene = result.scene as Record<string, any>
  const devices = requireArray(scene, 'devices', context)
  const environmentVariables = requireArray(scene, 'environmentVariables', context)
  const rules = requireArray(scene, 'rules', context)
  const specs = requireArray(scene, 'specs', context)
  requireArray(scene, 'templates', context)
  const finalItemCount = devices.length + environmentVariables.length + rules.length + specs.length
  validateAccounting(result, finalItemCount, context, true)
  // Every applied scene item must be accounted for as either a validated candidate or a
  // server-synthesized adjustment. Without this, a payload could hand over a full scene while
  // reporting zero inspected candidates, and the accounting strip would contradict the canvas.
  // Checked as a lower bound rather than an equality: an adjustment is not required to add an item.
  const adjustedCount = requireCount(result, 'adjustedCount', context)
  if (requireCount(result, 'validatedCount', context) + adjustedCount < finalItemCount) {
    throw new RecommendationResponseContractError(
      context, 'validatedCount plus adjustedCount must account for every applied scene item')
  }
  if (typeof result.scenarioName !== 'string' || typeof result.rationale !== 'string') {
    throw new RecommendationResponseContractError(context, 'scenarioName and rationale are required')
  }
  const objectiveTargets = requireRecord(result.objectiveTargets, context)
  const minDevices = requireObjectiveTarget(objectiveTargets, 'minDevices', context)
  const minRules = requireObjectiveTarget(objectiveTargets, 'minRules', context)
  const minSpecs = requireObjectiveTarget(objectiveTargets, 'minSpecs', context)
  if (result.requestedCount !== minDevices + minRules + minSpecs) {
    throw new RecommendationResponseContractError(
      context,
      'requestedCount must equal the sum of objectiveTargets'
    )
  }
  if (expectedTargets && (
    minDevices !== expectedTargets.minDevices
    || minRules !== expectedTargets.minRules
    || minSpecs !== expectedTargets.minSpecs
  )) {
    throw new RecommendationResponseContractError(
      context,
      'objectiveTargets must match the submitted minimum counts'
    )
  }
  const objectiveStatus = requireNonBlankText(result.objectiveStatus, 'objectiveStatus', context)
  const objectiveIssues = requireArray(result, 'objectiveIssues', context)
  const expectedObjectiveCodes: string[] = []
  if (devices.length < minDevices) {
    expectedObjectiveCodes.push(devices.length === 0 ? 'NO_DEVICES' : 'INSUFFICIENT_DEVICES')
  }
  if (rules.length < minRules) {
    expectedObjectiveCodes.push(rules.length === 0
      ? 'NO_AUTOMATION_RULES'
      : 'INSUFFICIENT_AUTOMATION_RULES')
  }
  if (specs.length < minSpecs) {
    expectedObjectiveCodes.push(specs.length === 0
      ? 'NO_SPECIFICATIONS'
      : 'INSUFFICIENT_SPECIFICATIONS')
  }
  const actualObjectiveCodes = objectiveIssues.map((item, index) => {
    const issue = requireRecord(item, context)
    const code = requireNonBlankText(issue.code, `objectiveIssues[${index}].code`, context)
    requireNonBlankText(issue.message, `objectiveIssues[${index}].message`, context)
    return code
  })
  const expectedObjectiveStatus = expectedObjectiveCodes.length === 0 ? 'COMPLETE' : 'PARTIAL'
  if (objectiveStatus !== expectedObjectiveStatus
    || actualObjectiveCodes.length !== expectedObjectiveCodes.length
    || actualObjectiveCodes.some((code, index) => code !== expectedObjectiveCodes[index])) {
    throw new RecommendationResponseContractError(
      context,
      'objectiveStatus/objectiveIssues must match the returned scene'
    )
  }
  if (typeof result.verificationReady !== 'boolean') {
    throw new RecommendationResponseContractError(context, 'verificationReady must be boolean')
  }
  const readinessIssues = requireArray(result, 'readinessIssues', context)
  const expectedCodes: string[] = []
  if (devices.length === 0) expectedCodes.push('NO_DEVICES')
  if (specs.length === 0) expectedCodes.push('NO_SPECIFICATIONS')
  const actualCodes = readinessIssues.map((item, index) => {
    const issue = requireRecord(item, context)
    const code = requireNonBlankText(issue.code, `readinessIssues[${index}].code`, context)
    requireNonBlankText(issue.message, `readinessIssues[${index}].message`, context)
    return code
  })
  if (result.verificationReady !== (expectedCodes.length === 0)
    || actualCodes.length !== expectedCodes.length
    || actualCodes.some((code, index) => code !== expectedCodes[index])) {
    throw new RecommendationResponseContractError(
      context,
      'verificationReady/readinessIssues must match the returned scene'
    )
  }
  const semanticWarnings = requireArray(result, 'semanticWarnings', context)
  const actualWarningCodes = semanticWarnings.map((item, index) => {
    const warning = requireRecord(item, context)
    const code = requireNonBlankText(warning.code, `semanticWarnings[${index}].code`, context)
    requireNonBlankText(warning.message, `semanticWarnings[${index}].message`, context)
    return code
  })
  const expectedWarningCodes: string[] = []
  if (result.filteredCount > 0) expectedWarningCodes.push('FILTERED_CANDIDATES')
  if (devices.length > 0) {
    if (rules.length === 0) expectedWarningCodes.push('NO_AUTOMATION_RULES')

    const referencedDeviceIds = new Set<string>()
    const addReference = (value: unknown) => {
      if (typeof value === 'string' && value.trim()) referencedDeviceIds.add(value.trim())
    }
    rules.forEach((value) => {
      const rule = requireRecord(value, context)
      addReference(rule.toId)
      addReference(rule.contentDevice)
      if (Array.isArray(rule.sources)) {
        rule.sources.forEach((source) => addReference(requireRecord(source, context).fromId))
      }
    })
    specs.forEach((value) => {
      const spec = requireRecord(value, context)
      ;['aConditions', 'ifConditions', 'thenConditions'].forEach((field) => {
        if (Array.isArray(spec[field])) {
          spec[field].forEach((condition: unknown) => {
            addReference(requireRecord(condition, context).deviceId)
          })
        }
      })
    })
    const hasUnreferencedDevice = devices.some((value) => {
      const id = requireRecord(value, context).id
      return typeof id === 'string' && id.trim() && !referencedDeviceIds.has(id.trim())
    })
    if (hasUnreferencedDevice) expectedWarningCodes.push('UNREFERENCED_DEVICES')
  }
  if (actualWarningCodes.length !== expectedWarningCodes.length
    || actualWarningCodes.some((code, index) => code !== expectedWarningCodes[index])) {
    throw new RecommendationResponseContractError(
      context,
      'semanticWarnings must match the returned scene and filtered candidates'
    )
  }
  return result as T
}
