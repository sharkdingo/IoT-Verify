export type RuleSourceItemType = 'api' | 'variable' | 'mode' | 'state'

/** Rule wire shape shared by live board responses and frozen playback scenes. */
export interface RuleDto {
    id: number | null
    conditions: Array<{
        deviceName: string
        attribute: string
        targetType: RuleSourceItemType
        relation?: string
        value?: string
    }>
    command: {
        deviceName: string
        action: string
        contentDevice?: string | null
        content?: string | null
    }
    ruleString?: string | null
}

export type DuplicateRuleReasonCode =
    | 'NO_EXISTING_RULES'
    | 'EXACT_MATCH'
    | 'TRIGGER_SET_CONTAINS_OTHER'
    | 'SAME_TRIGGER_SHAPE_DIFFERENT_VALUES'
    | 'PARTIAL_TRIGGER_OVERLAP'
    | 'NO_MATCHING_SIGNATURE'

export type RuleSimilarityReasonCode =
    | 'NO_EXISTING_RULES'
    | 'AI_DUPLICATE'
    | 'AI_SIMILAR'
    | 'AI_HIGH_SCORE_REVIEW'
    | 'AI_NO_SIGNIFICANT_SIMILARITY'

export interface SourceEntry {
    fromId: string  // Source device id
    fromApi: string  // API name, variable name, mode name; full-state conditions use state
    // Maps directly to backend RuleDto.Condition.targetType.
    itemType: RuleSourceItemType
    // Required for variable, mode, and state conditions; omitted for API signal events.
    relation?: string
    value?: string
}

export interface RuleForm {
    id?: string  // 可选，添加新规则时由前端生成
    name?: string // 规则名称
    // 多个源条目，每个源有自己的 API
    sources: SourceEntry[]
    toId: string
    toApi: string
    contentDevice?: string
    content?: string
}
