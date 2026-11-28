// src/api/rules.ts - Rules API
import api from '@/api/http'
import { unpack } from './resultEnvelope'
import type {
    RecommendationAdjustmentItem,
    RecommendationFilteredItem,
    RuleRecommendation
} from '@/types/recommendation'
import { validateStandaloneRecommendationResponse } from '@/utils/recommendationResponse'
import { validateRuleRecommendationCandidate } from '@/utils/recommendationMaterialization'
import {
  markRecommendationResponseReceived,
  type OwnedRecommendationPostOptions
} from '@/utils/recommendationRequestRecovery'

export type {
    RecommendationAdjustmentItem,
    RecommendationFilteredItem,
    RuleRecommendation
} from '@/types/recommendation'

export interface RecommendRulesResponse {
  message: string
  count: number
  requestedCount: number
  validatedCount: number
  filteredCount: number
  filteredItems: RecommendationFilteredItem[]
  adjustedCount: number
  adjustedItems: RecommendationAdjustmentItem[]
  rawCandidateCount: number
  inspectedCount: number
  truncatedCount: number
  recommendations: RuleRecommendation[]
}

export const recommendRules = async (
  { requestId, signal, authToken }: Required<OwnedRecommendationPostOptions>,
  maxRecommendations: number = 5,
  language: string = 'en',
  userRequirement: string = ''
): Promise<RecommendRulesResponse> => {
  const response = await api.post<any>('/board/rules/recommend', {
    maxRecommendations, language, userRequirement, requestId
  }, {
    signal,
    timeout: 0,
    headers: { Authorization: `Bearer ${authToken}` }
  })
  try {
    return validateStandaloneRecommendationResponse<RecommendRulesResponse>(
      unpack<unknown>(response),
      'Rule recommendation',
      validateRuleRecommendationCandidate,
      true
    )
  } catch (error) {
    throw markRecommendationResponseReceived(error)
  }
}
