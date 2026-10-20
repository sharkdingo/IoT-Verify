// src/utils/rule.ts
import type { DeviceEdge } from '../types/edge'
import type { DeviceNode } from '../types/node'
import type {
    DuplicateRuleReasonCode,
    RuleForm,
    RuleSimilarityReasonCode,
    SourceEntry
} from '../types/rule'

export type RuleCheckReasonKey =
    | 'app.ruleCheckNoExistingRules'
    | 'app.ruleCheckExactMatch'
    | 'app.ruleCheckContainedTriggers'
    | 'app.ruleCheckSameShape'
    | 'app.ruleCheckPartialOverlap'
    | 'app.ruleCheckNoMatchingSignature'
    | 'app.ruleSimilarityDuplicate'
    | 'app.ruleSimilaritySimilar'
    | 'app.ruleSimilarityHighScoreReview'
    | 'app.ruleSimilarityNoSignificantMatch'

const duplicateReasonKeys: Record<DuplicateRuleReasonCode, RuleCheckReasonKey> = {
    NO_EXISTING_RULES: 'app.ruleCheckNoExistingRules',
    EXACT_MATCH: 'app.ruleCheckExactMatch',
    TRIGGER_SET_CONTAINS_OTHER: 'app.ruleCheckContainedTriggers',
    SAME_TRIGGER_SHAPE_DIFFERENT_VALUES: 'app.ruleCheckSameShape',
    PARTIAL_TRIGGER_OVERLAP: 'app.ruleCheckPartialOverlap',
    NO_MATCHING_SIGNATURE: 'app.ruleCheckNoMatchingSignature'
}

const similarityReasonKeys: Record<RuleSimilarityReasonCode, RuleCheckReasonKey> = {
    NO_EXISTING_RULES: 'app.ruleCheckNoExistingRules',
    AI_DUPLICATE: 'app.ruleSimilarityDuplicate',
    AI_SIMILAR: 'app.ruleSimilaritySimilar',
    AI_HIGH_SCORE_REVIEW: 'app.ruleSimilarityHighScoreReview',
    AI_NO_SIGNIFICANT_SIMILARITY: 'app.ruleSimilarityNoSignificantMatch'
}

export const duplicateRuleReasonKey = (code: DuplicateRuleReasonCode): RuleCheckReasonKey =>
    duplicateReasonKeys[code] || 'app.ruleCheckNoMatchingSignature'

export const ruleSimilarityReasonKey = (code: RuleSimilarityReasonCode): RuleCheckReasonKey =>
    similarityReasonKeys[code] || 'app.ruleSimilarityNoSignificantMatch'

const VALUE_BASED_SOURCE_TYPES = new Set(['variable', 'mode', 'state'])

export const assertRuleHasTrigger = (rule: RuleForm, index = 0): void => {
    const ruleName = rule.name?.trim() || `Rule ${index + 1}`
    if (!rule.sources || rule.sources.length === 0) {
        throw new Error(`${ruleName}: at least one trigger source is required`)
    }

    rule.sources.forEach((source: SourceEntry, sourceIndex: number) => {
        const sourceName = `source ${sourceIndex + 1}`
        const hasAttribute = source.itemType === 'state' || !!source.fromApi
        if (!source.fromId || !hasAttribute) {
            throw new Error(`${ruleName}: ${sourceName} must select a device and trigger attribute`)
        }

        const sourceType = source.itemType
        if (!sourceType) {
            throw new Error(`${ruleName}: ${sourceName} must select a trigger type`)
        }
        if (VALUE_BASED_SOURCE_TYPES.has(sourceType)) {
            const hasRelation = !!source.relation
            const hasValue = source.value != null && String(source.value).trim().length > 0
            if (!hasRelation || !hasValue) {
                throw new Error(`${ruleName}: ${sourceName} ${sourceType} trigger requires relation and value`)
            }
        }
    })
}

/**
 * 节点重命名时：更新所有相关规则（边）上的 fromLabel / toLabel
 * @returns 是否发生了修改
 */
export const updateRulesForNodeRename = (
    rules: DeviceEdge[],
    nodeId: string,
    newLabel: string
): boolean => {
    let changed = false

    rules.forEach(edge => {
        if (edge.from === nodeId && edge.fromLabel !== newLabel) {
            edge.fromLabel = newLabel
            changed = true
        }
        if (edge.to === nodeId && edge.toLabel !== newLabel) {
            edge.toLabel = newLabel
            changed = true
        }
    })

    return changed
}

/**
 * 获取节点中心点坐标
 */
export const getNodeCenter = (node: DeviceNode) => {
    const w = node.width || 176
    const h = node.height || 128
    return {
        x: node.position.x + w / 2,
        y: node.position.y + h / 2,
    }
}

const getNodeBoundaryPoint = (node: DeviceNode, origin: DeviceNode['position'], dx: number, dy: number) => {
    const width = node.width || 176
    const height = node.height || 128
    const tx = dx === 0 ? Infinity : ((node.position.x + (dx > 0 ? width : 0)) - origin.x) / dx
    const ty = dy === 0 ? Infinity : ((node.position.y + (dy > 0 ? height : 0)) - origin.y) / dy
    const distance = Math.min(tx, ty)
    return { x: origin.x + dx * distance, y: origin.y + dy * distance }
}

/** Clip the directed line to both nodes after shifting it sideways for a reverse connection. */
export const getLinkPoints = (fromNode: DeviceNode, toNode: DeviceNode, lateralOffset = 0) => {
    const fromCenter = getNodeCenter(fromNode)
    const toCenter = getNodeCenter(toNode)

    const dx = toCenter.x - fromCenter.x
    const dy = toCenter.y - fromCenter.y
    const length = Math.hypot(dx, dy)
    if (length === 0) return { fromPoint: fromCenter, toPoint: toCenter }

    // Keep the shifted ray inside even a small node; reversing direction reverses its perpendicular.
    const offset = Math.sign(lateralOffset) * Math.min(Math.abs(lateralOffset),
        (fromNode.width || 176) / 4, (fromNode.height || 128) / 4,
        (toNode.width || 176) / 4, (toNode.height || 128) / 4)
    const shift = { x: -dy / length * offset, y: dx / length * offset }
    const fromOrigin = { x: fromCenter.x + shift.x, y: fromCenter.y + shift.y }
    const toOrigin = { x: toCenter.x + shift.x, y: toCenter.y + shift.y }

    return {
        fromPoint: getNodeBoundaryPoint(fromNode, fromOrigin, dx, dy),
        toPoint: getNodeBoundaryPoint(toNode, toOrigin, -dx, -dy)
    }
}

/**
 * 自环连线（from === to）：
 * 在节点右侧画一条“半椭圆”样式的 path
 */
export const getSelfLoopPath = (node: DeviceNode): string => {
    const w = node.width || 176
    const h = node.height || 128

    const right = node.position.x + w
    const top = node.position.y

    const startY = top + h * 0.25
    const endY   = top + h * 0.75
    const offsetX = Math.min(w, h) * 0.8
    const ctrlOffsetY = h * 0.2

    const startX = right
    const endX   = right

    const c1x = right + offsetX
    const c1y = startY - ctrlOffsetY

    const c2x = right + offsetX
    const c2y = endY + ctrlOffsetY

    return `M ${startX} ${startY} C ${c1x} ${c1y} ${c2x} ${c2y} ${endX} ${endY}`
}
