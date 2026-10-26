import type { Specification } from './spec'

/**
 * Shared recommendation-response accounting types.
 *
 * The rule / device / specification recommenders all report why candidates were dropped or
 * altered before the user sees them, using the same two shapes. They were previously declared
 * independently in `api/board.ts` and `api/rules.ts`; keeping one definition here means a
 * contract change cannot land in one copy only.
 */

/** A candidate the server rejected, with the reason code the UI maps to explanatory copy. */
export interface RecommendationFilteredItem {
  type: string
  index: number
  reasonCode: string
  reason?: string
  label?: string
}

/** A candidate the server kept but modified, carrying the values it actually applied. */
export interface RecommendationAdjustmentItem {
  type: string
  index?: number
  reasonCode: string
  reason: string
  label?: string
  appliedValues: Record<string, unknown>
}

/** One LLM-proposed automation rule, before the user applies it to the board. */
export interface RuleRecommendation {
  /** Exact user-facing rule name persisted when the candidate is applied. */
  name: string
  /** Advisory explanation shown before applying; not persisted as rule semantics. */
  reason: string
  conditions: {
    deviceId: string
    deviceLabel?: string
    deviceName: string
    attribute: string
    targetType: 'api' | 'variable' | 'mode' | 'state'
    relation?: string
    value?: string
  }[]
  command: {
    deviceId: string
    deviceLabel?: string
    deviceName: string
    action: string
    contentDevice?: string
    contentDeviceLabel?: string
    content?: string
    contentPrivacy?: 'public' | 'private'
  }
}

/** One LLM-proposed device instance, before the user adds it to the board. */
export interface DeviceRecommendation {
  templateName: string
  suggestedLabel: string
  /** Advisory recommendation context; not persisted as a device/model field. */
  intendedUse?: string
  /** Advisory recommendation context; not persisted as a device/model field. */
  suggestedPlacement?: string
  description?: string
  reason?: string
  initialState?: string
  currentStateTrust?: 'trusted' | 'untrusted'
  currentStatePrivacy?: 'public' | 'private'
  initialVariables?: Array<{
    name: string
    value: string
    trust?: 'trusted' | 'untrusted'
  }>
  initialPrivacies?: Array<{
    name: string
    privacy: 'public' | 'private'
  }>
}

/** One LLM-proposed specification, carrying the structured conditions that get persisted. */
export interface SpecificationRecommendation {
  name: string;
  /** Advisory explanation; applying persists only templateId and structured conditions. */
  rationale: string
  templateId: string
  aConditions: Specification['aConditions']
  ifConditions: Specification['ifConditions']
  thenConditions: Specification['thenConditions']
}
