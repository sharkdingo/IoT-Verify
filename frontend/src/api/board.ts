// src/api/board.ts - Board API（自动解包Result<T>）
import api from './http';
import { PRIVACY_VALUE_SET, TRUST_VALUE_SET } from '@/utils/deviceRuntime';
import { saveBlobResponseAsFile } from '@/utils/attachmentDownload';

// 引入类型
import type {
    DeviceRecommendation,
    RecommendationAdjustmentItem,
    RecommendationFilteredItem,
    SpecificationRecommendation
} from '@/types/recommendation'
import type { DeviceNode } from '../types/node'
import type { Specification } from '../types/spec'
import type { BoardUndoAvailability, BoardUndoResult } from '../types/boardEdit'
import {
    BOARD_EDIT_ENTITY_TYPES,
    BOARD_EDIT_OPERATIONS,
    BOARD_UNDO_REASON_CODES,
    isBoardEditEntityType,
    isBoardEditOperation,
    isBoardUndoReasonCode
} from '../types/boardEdit'
import type { BoardLayoutDto } from '../types/canvas'
import type {
    DuplicateRuleReasonCode,
    RuleForm,
    RuleSimilarityReasonCode,
    RuleSourceItemType
} from '../types/rule'
import type { DeviceTemplate } from '@/types/device'
import type {
    EnvironmentVariableUpdateRequest,
    ModelEnvironmentVariable
} from '@/types/model'
import type { ModelTokenSource } from '@/types/modelToken'
import { MODEL_TOKEN_SOURCE_SET } from '@/types/modelToken'
import type { InteractiveOperationStatus, TaskCancellationResult } from '@/types/task'
import type { PortableSceneFile } from '@/types/scene'
import type {
    PersistedTrace,
    VerificationRequest,
    VerificationResult,
    VerificationRun,
    VerificationRunSummary,
    VerificationTask,
    VerificationTaskSummary
} from '@/types/verify'
import type {
    FaultLocalizationResult,
    FixApplyRequest,
    FixApplyResult,
    FixRequest,
    FixResult,
    FixSuggestion,
    PreferredRangeSelection
} from '@/types/fix'
import {
    validateFaultLocalizationResult,
    validateFixResult,
    validateFixSuggestion
} from '@/utils/fixResponse'
import { assertRuleHasTrigger } from '../utils/rule'
import { validateManifest } from '@/utils/device'
import { normalizeModelRelation } from '@/utils/modelRequest'
import {
    validateScenarioRecommendationResponse,
    validateStandaloneRecommendationResponse
} from '@/utils/recommendationResponse'
import {
    validateDeviceRecommendationCandidate,
    validateSpecificationRecommendationCandidate
} from '@/utils/recommendationMaterialization'
import {
    markRecommendationResponseReceived,
    type OwnedRecommendationPostOptions
} from '@/utils/recommendationRequestRecovery'
import {
    validateTaskCancellationResult,
    validateInteractiveOperationStatus,
    validateTaskProgress,
    validateVerificationTask,
    validateVerificationTaskSummaryList,
    validateVerificationRun,
    validateVerificationRunSummaryList,
    validateVerificationResult,
    validateVerificationTrace,
    validateVerificationTraceList
} from '@/utils/runResponse'
import {
    NODE_HEIGHT_RANGE,
    NODE_POSITION_ABS_MAX,
    NODE_WIDTH_RANGE
} from '@/utils/canvas/nodeLayout'

export type {
    DeviceRecommendation,
    RecommendationAdjustmentItem,
    RecommendationFilteredItem,
    SpecificationRecommendation
} from '@/types/recommendation'


interface RecommendationResponse<T = any> {
    message: string
    count: number
    requestedCount: number
    validatedCount: number
    filteredCount: number
    filteredItems: RecommendationFilteredItem[]
    adjustedCount?: number
    adjustedItems?: RecommendationAdjustmentItem[]
    rawCandidateCount: number
    inspectedCount: number
    truncatedCount: number
    recommendations: T[]
}

interface DeviceRecommendationResponse<T = any> extends RecommendationResponse<T> {
    adjustedCount: number
    adjustedItems: RecommendationAdjustmentItem[]
}

export interface ScenarioRecommendationResponse {
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
    scenarioName: string
    rationale: string
    objectiveTargets: ScenarioObjectiveTargets
    objectiveStatus: 'COMPLETE' | 'PARTIAL'
    objectiveIssues: ScenarioObjectiveIssue[]
    verificationReady: boolean
    readinessIssues: ScenarioReadinessIssue[]
    semanticWarnings: ScenarioSemanticWarning[]
    scene: PortableSceneFile
}

export interface ScenarioObjectiveTargets {
    minDevices: number
    minRules: number
    minSpecs: number
}

export interface ScenarioRecommendationRequest extends ScenarioObjectiveTargets {
    maxDevices: number
    maxRules: number
    maxSpecs: number
    language?: string
    userRequirement?: string
}

export interface ScenarioObjectiveIssue {
    code:
        | 'NO_DEVICES'
        | 'INSUFFICIENT_DEVICES'
        | 'NO_AUTOMATION_RULES'
        | 'INSUFFICIENT_AUTOMATION_RULES'
        | 'NO_SPECIFICATIONS'
        | 'INSUFFICIENT_SPECIFICATIONS'
    message: string
}

export interface ScenarioReadinessIssue {
    code: 'NO_DEVICES' | 'NO_SPECIFICATIONS'
    message: string
}

export interface ScenarioSemanticWarning {
    code: 'FILTERED_CANDIDATES' | 'NO_AUTOMATION_RULES' | 'UNREFERENCED_DEVICES'
    message: string
}


// These synchronous operations are bounded by the server's NuSMV/LLM/fix deadlines.
// Do not let Axios' shorter CRUD timeout report a false failure while the server is still working.
const SERVER_BOUNDED_REQUEST = { timeout: 0 } as const
const INTERACTIVE_CONTROL_REQUEST = { timeout: 2500 } as const

// 辅助函数：解包Result（后端返回 { code, message, data }）
const unpack = <T>(response: any): T => {
  return response.data.data;
};

const VALUE_BASED_RULE_SOURCE_TYPES = new Set<RuleSourceItemType>(['variable', 'mode', 'state'])

const normalizeRuleSourceType = (type?: string): RuleSourceItemType | undefined => {
    const normalized = String(type || '').trim().toLowerCase()
    return VALUE_BASED_RULE_SOURCE_TYPES.has(normalized as RuleSourceItemType) || normalized === 'api'
        ? normalized as RuleSourceItemType
        : undefined
}

const hasRuleConditionValue = (value: unknown) =>
    value !== null && value !== undefined && (typeof value !== 'string' || value.trim() !== '')

const requireRuleSourceType = (type?: RuleSourceItemType): RuleSourceItemType => {
    const normalized = normalizeRuleSourceType(type)
    if (!normalized) {
        throw new Error('Rule condition targetType is required')
    }
    return normalized
}

const requireValueBasedRuleSource = (source: any, sourceType: RuleSourceItemType) => {
    if (!source.relation || !hasRuleConditionValue(source.value)) {
        throw new Error(`Rule ${sourceType} condition requires relation and value`)
    }
    return {
        relation: normalizeModelRelation(source.relation) || String(source.relation),
        value: String(source.value)
    }
}

// 后端 RuleDto 接口（用于 API 通信）
interface BackendRuleDto {
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
        contentDevice: string | null
        content: string | null
    }
    ruleString: string | null
}

export interface BoardSemanticSnapshot {
    nodes: DeviceNode[]
    environmentVariables: ModelEnvironmentVariable[]
    rules: RuleForm[]
    specifications: Specification[]
    deviceTemplates: DeviceTemplate[]
}

/** Authoritative board state after a confirmed full-scene replacement (import or clear). */
export interface BoardSceneReplacementResult {
    nodes: DeviceNode[]
    environmentVariables: ModelEnvironmentVariable[]
    rules: RuleForm[]
    specs: Specification[]
    createdTemplates: DeviceTemplate[]
}

const fromBackendRuleDto = (rule: BackendRuleDto): RuleForm => ({
    // Keep the persisted id as a numeric string for targeted delete and batch scene export/import.
    // Client-created rules use a temporary `rule_<timestamp>` id and are sent with id=null.
    id: rule.id != null ? String(rule.id) : '',
    name: rule.ruleString || '',
    sources: (rule.conditions && rule.conditions.length > 0)
        ? rule.conditions.map((c) => {
            const sourceType = requireRuleSourceType(c.targetType)
            const shouldKeepRelation = VALUE_BASED_RULE_SOURCE_TYPES.has(sourceType)
            return {
                fromId: c.deviceName || '',
                fromApi: sourceType === 'state' ? 'state' : (c.attribute || ''),
                itemType: sourceType,
                relation: shouldKeepRelation ? (normalizeModelRelation(c.relation) || '=') : undefined,
                value: shouldKeepRelation ? c.value : undefined
            }
        })
        : [],
    toId: rule.command?.deviceName || '',
    toApi: rule.command?.action || '',
    contentDevice: rule.command?.contentDevice || undefined,
    content: rule.command?.content || undefined
})

// RuleForm -> BackendRuleDto. Shared by targeted create, checks, and explicit scene batch replacement.
// Client-created rules use a `rule_<timestamp>` id -> send null; persisted rules retain their numeric id.
const toBackendRuleDto = (rule: RuleForm): BackendRuleDto => {
    let id: number | null = null;
    if (rule.id && rule.id.startsWith('rule_')) {
        id = null;
    } else if (rule.id) {
        const num = Number(rule.id);
        if (!isNaN(num)) {
            id = num;
        }
    }
    return {
        id,
        conditions: rule.sources.map(source => {
            const sourceType = requireRuleSourceType(source.itemType)
            const shouldSendRelation = VALUE_BASED_RULE_SOURCE_TYPES.has(sourceType)
            const valueCondition = shouldSendRelation
                ? requireValueBasedRuleSource(source, sourceType)
                : null
            return {
                deviceName: String(source.fromId || ''),
                attribute: sourceType === 'state' ? 'state' : String(source.fromApi || ''),
                targetType: sourceType,
                relation: valueCondition?.relation,
                value: valueCondition?.value
            }
        }),
        command: {
            deviceName: String(rule.toId || ''),
            action: String(rule.toApi || ''),
            contentDevice: rule.contentDevice || null,
            content: rule.content || null
        },
        ruleString: String(rule.name || '')
    };
};

// Only structured specification semantics cross the write boundary. The backend rebuilds
// labels, device summaries, and the user-facing formula preview from these fields.
const toBackendSpecificationWriteDto = (spec: Specification) => ({
    id: spec.id,
    templateId: spec.templateId,
    aConditions: (spec.aConditions || []).map(condition => ({
        deviceId: condition.deviceId,
        targetType: condition.targetType,
        key: condition.key,
        ...(condition.propertyScope ? { propertyScope: condition.propertyScope } : {}),
        // Required for a variable condition and refused on any other type. Dropping it here is what
        // made every variable specification fail admission with "variableSource is required".
        ...(condition.targetType === 'variable' ? { variableSource: condition.variableSource } : {}),
        relation: normalizeModelRelation(condition.relation) || condition.relation,
        value: condition.value
    })),
    ifConditions: (spec.ifConditions || []).map(condition => ({
        deviceId: condition.deviceId,
        targetType: condition.targetType,
        key: condition.key,
        ...(condition.propertyScope ? { propertyScope: condition.propertyScope } : {}),
        // Required for a variable condition and refused on any other type. Dropping it here is what
        // made every variable specification fail admission with "variableSource is required".
        ...(condition.targetType === 'variable' ? { variableSource: condition.variableSource } : {}),
        relation: normalizeModelRelation(condition.relation) || condition.relation,
        value: condition.value
    })),
    thenConditions: (spec.thenConditions || []).map(condition => ({
        deviceId: condition.deviceId,
        targetType: condition.targetType,
        key: condition.key,
        ...(condition.propertyScope ? { propertyScope: condition.propertyScope } : {}),
        // Required for a variable condition and refused on any other type. Dropping it here is what
        // made every variable specification fail admission with "variableSource is required".
        ...(condition.targetType === 'variable' ? { variableSource: condition.variableSource } : {}),
        relation: normalizeModelRelation(condition.relation) || condition.relation,
        value: condition.value
    }))
});

/**
 * The create/delete shape of the backend's collection-mutation envelope.
 *
 * Rule reorder shares the envelope on the wire but not this type: it reports
 * `operation: "reordered"` with a null `affectedItem`, because one up/down press changes no single
 * record. `reorderRules` validates that shape itself rather than through
 * `validateCollectionMutationResult`, which requires `affectedItem`.
 */
export interface CollectionMutationResult<T> {
    operation: 'created' | 'deleted';
    affectedItem: T;
    currentItems: T[];
    currentCount: number;
    /** Undo availability after this reversible mutation, as reported by the server journal. */
    canUndo: true;
    canRedo: false;
}

type CommittedDeviceMutationResult = DeviceMutationResult & {
    operation: 'created';
    canUndo: true;
    canRedo: false;
}

type CommittedDeviceDeletionResult = DeviceDeletionResult & {
    operation: 'deleted';
    canUndo: true;
    canRedo: false;
}

export interface DuplicateRuleCheckResult {
    isDuplicate: boolean;
    requiresReview: boolean;
    matchedRule?: string | null;
    similarity: number;
    matchType: string;
    reasonCode: DuplicateRuleReasonCode;
    reason: string;
    message: string;
}

export interface RuleSimilarityResult {
    isSimilar: boolean;
    isDuplicate: boolean;
    requiresReview: boolean;
    matchedRule?: string | null;
    similarity: number;
    reasonCode: RuleSimilarityReasonCode;
    reason: string;
    message: string;
}

const DUPLICATE_RULE_REASON_CODES = new Set<DuplicateRuleReasonCode>([
    'NO_EXISTING_RULES',
    'EXACT_MATCH',
    'TRIGGER_SET_CONTAINS_OTHER',
    'SAME_TRIGGER_SHAPE_DIFFERENT_VALUES',
    'PARTIAL_TRIGGER_OVERLAP',
    'NO_MATCHING_SIGNATURE'
])

const RULE_SIMILARITY_REASON_CODES = new Set<RuleSimilarityReasonCode>([
    'NO_EXISTING_RULES',
    'AI_DUPLICATE',
    'AI_SIMILAR',
    'AI_HIGH_SCORE_REVIEW',
    'AI_NO_SIGNIFICANT_SIMILARITY'
])

const SPEC_RELATIONS = new Set(['=', '!=', '>', '<', '>=', '<=', 'in', 'not in'])

export interface EnvironmentVariableChange {
    changeType: 'ADDED' | 'UPDATED' | 'REMOVED';
    name: string;
    previousValue?: ModelEnvironmentVariable | null;
    currentValue?: ModelEnvironmentVariable | null;
    previousModelTokenSource?: ModelTokenSource;
    currentModelTokenSource?: ModelTokenSource;
}

export type EnvironmentVariableField = 'value' | 'trust' | 'privacy';

export interface EnvironmentVariablePatchResult {
    name: string;
    suppliedFields: EnvironmentVariableField[];
    changedFields: EnvironmentVariableField[];
    preservedFields: EnvironmentVariableField[];
    previousValue: ModelEnvironmentVariable;
    currentValue: ModelEnvironmentVariable;
}

export interface EnvironmentMutationResult {
    operation: 'updated' | 'unchanged';
    patchResults: EnvironmentVariablePatchResult[];
    environmentVariables: ModelEnvironmentVariable[];
    environmentChanges: EnvironmentVariableChange[];
    currentCount: number;
    canUndo?: boolean;
    canRedo?: boolean;
}

export interface DeviceMutationResult {
    operation: 'created' | 'updated' | 'renamed';
    affectedDevices: DeviceNode[];
    currentNodes: DeviceNode[];
    environmentVariables: ModelEnvironmentVariable[];
    environmentChanges: EnvironmentVariableChange[];
    currentSpecifications: Specification[];
    previousLabel?: string;
    updatedSpecificationCount: number;
    currentCount: number;
    canUndo?: boolean;
    canRedo?: boolean;
}

export interface DeviceLayout {
    position: { x: number; y: number };
    width: number;
    height: number;
}

export interface DeviceRuntimeValues {
    state?: string;
    currentStateTrust?: string;
    currentStatePrivacy?: string;
    variables?: DeviceNode['variables'];
    privacies?: DeviceNode['privacies'];
}

export interface DeviceRuntimeUpdate {
    expected: DeviceRuntimeValues;
    desired: DeviceRuntimeValues;
}

export type DeviceUpdateField =
    | 'position.x' | 'position.y' | 'width' | 'height'
    | 'state' | 'currentStateTrust' | 'currentStatePrivacy' | 'variables' | 'privacies';

export interface DeviceUpdateResult {
    operation: 'updated' | 'unchanged';
    mutationType: 'layout' | 'runtime';
    changedFields: DeviceUpdateField[];
    previousDevice: DeviceNode;
    currentDevice: DeviceNode;
    currentNodes: DeviceNode[];
    currentCount: number;
    canUndo?: boolean;
    canRedo?: boolean;
}

export interface DeviceDeletionResult {
    operation: 'preview' | 'deleted';
    impactToken: string;
    deletedDevice: DeviceNode;
    removedRules: RuleForm[];
    removedSpecifications: Specification[];
    currentNodes: DeviceNode[];
    environmentVariables: ModelEnvironmentVariable[];
    environmentChanges: EnvironmentVariableChange[];
    currentRules: RuleForm[];
    currentSpecifications: Specification[];
    canUndo?: boolean;
    canRedo?: boolean;
}

export interface BoardReplacementPreview {
    impactToken: string;
    deviceCount: number;
    environmentVariableCount: number;
    ruleCount: number;
    specificationCount: number;
    editHistoryEntryCount: number;
}

export interface BoardEditHistoryClearPreview extends BoardUndoAvailability {
    impactToken: string;
    entryCount: number;
}

export interface BoardReplacementStaleData {
    reasonCode: 'BOARD_REPLACEMENT_STALE';
    currentPreview: BoardReplacementPreview;
}

export type DefaultTemplateResetChangeType =
    | 'RESTORE_MISSING'
    | 'REFRESH_DEFAULT'
    | 'REPLACE_CUSTOM_NAME_COLLISION'
    | 'REMOVE_OBSOLETE_DEFAULT'

export type DefaultTemplateResetBlockerReasonCode =
    | 'DEVICE_INSTANCE_INCOMPATIBLE'
    | 'AUTOMATION_RULE_INCOMPATIBLE'
    | 'SPECIFICATION_INCOMPATIBLE'
    | 'ENVIRONMENT_POOL_INCOMPATIBLE'
    | 'BOARD_MODEL_INCOMPATIBLE'

export interface DefaultTemplateResetResult {
    operation: 'preview' | 'reset';
    impactToken: string;
    canApply: boolean;
    editHistoryEntryCount: number;
    templateChanges: Array<{
        templateName: string;
        changeType: DefaultTemplateResetChangeType;
        semanticsChanged: boolean;
    }>;
    affectedDevices: Array<{
        deviceId: string;
        deviceLabel: string;
        templateName: string;
    }>;
    blockers: Array<{
        itemLabel: string;
        reasonCode: DefaultTemplateResetBlockerReasonCode;
        reason: string;
    }>;
    environmentChanges: EnvironmentVariableChange[];
    currentTemplates: DeviceTemplate[];
    environmentVariables: ModelEnvironmentVariable[];
}

export interface DeviceTemplateDeletionResult {
    operation: 'preview' | 'deleted';
    impactToken: string;
    canDelete: boolean;
    editHistoryEntryCount: number;
    template: DeviceTemplate;
    deletedTemplate?: DeviceTemplate;
    blockers: Array<{
        reasonCode: 'DEVICE_INSTANCE_USES_TEMPLATE' | string;
        itemId: string;
        itemLabel: string;
        reason: string;
    }>;
    currentTemplates: DeviceTemplate[];
}

export const BOARD_RESPONSE_INCOMPLETE_CODE = 'BOARD_RESPONSE_INCOMPLETE'

class BoardResponseContractError extends Error {
    readonly code = BOARD_RESPONSE_INCOMPLETE_CODE

    constructor(context: string, detail: string) {
        super(`${context} returned an incomplete authoritative result: ${detail}`)
        this.name = 'BoardResponseContractError'
    }
}

const requireResponseRecord = (value: unknown, context: string): Record<string, any> => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
        throw new BoardResponseContractError(context, 'the result must be an object')
    }
    return value as Record<string, any>
}

const requireResponseArray = <T>(value: unknown, context: string, field?: string): T[] => {
    const candidate = field
        ? requireResponseRecord(value, context)[field]
        : value
    if (!Array.isArray(candidate)) {
        throw new BoardResponseContractError(context, `${field || 'result'} must be an array`)
    }
    return candidate as T[]
}

const requireOperation = (result: Record<string, any>, expected: string, context: string) => {
    if (result.operation !== expected) {
        throw new BoardResponseContractError(context, `operation must be '${expected}'`)
    }
}

const requireCurrentCount = (
    result: Record<string, any>,
    currentItems: unknown[],
    context: string
) => {
    if (!Number.isSafeInteger(result.currentCount) || result.currentCount !== currentItems.length) {
        throw new BoardResponseContractError(context, 'currentCount must match the authoritative collection')
    }
}

const requireUniqueIdentities = <T>(
    items: T[],
    identityOf: (item: T) => string | number,
    context: string,
    field: string
) => {
    const identities = items.map(identityOf)
    if (new Set(identities).size !== identities.length) {
        throw new BoardResponseContractError(context, `${field} contains duplicate identities`)
    }
}

const requireCheckBoolean = (
    result: Record<string, any>,
    field: string,
    context: string
): boolean => {
    if (typeof result[field] !== 'boolean') {
        throw new BoardResponseContractError(context, `${field} must be boolean`)
    }
    return result[field]
}

const requireCheckText = (
    result: Record<string, any>,
    field: string,
    context: string
): string => {
    if (typeof result[field] !== 'string' || !result[field].trim()) {
        throw new BoardResponseContractError(context, `${field} must be non-blank text`)
    }
    return result[field]
}

const requireCheckSimilarity = (result: Record<string, any>, context: string): number => {
    if (typeof result.similarity !== 'number'
        || !Number.isFinite(result.similarity)
        || result.similarity < 0
        || result.similarity > 1) {
        throw new BoardResponseContractError(context, 'similarity must be a number from 0 to 1')
    }
    return result.similarity
}

const requireOptionalCheckText = (
    result: Record<string, any>,
    field: string,
    context: string
): string | null | undefined => {
    const value = result[field]
    if (value === null || value === undefined) return value
    if (typeof value !== 'string') {
        throw new BoardResponseContractError(context, `${field} must be text or null`)
    }
    return value
}

const validateDuplicateRuleCheckResult = (value: unknown): DuplicateRuleCheckResult => {
    const context = 'Duplicate-rule check'
    const result = requireResponseRecord(value, context)
    requireCheckBoolean(result, 'isDuplicate', context)
    requireCheckBoolean(result, 'requiresReview', context)
    requireOptionalCheckText(result, 'matchedRule', context)
    requireCheckSimilarity(result, context)
    requireCheckText(result, 'matchType', context)
    const reasonCode = requireCheckText(result, 'reasonCode', context)
    if (!DUPLICATE_RULE_REASON_CODES.has(reasonCode as DuplicateRuleReasonCode)) {
        throw new BoardResponseContractError(context, 'reasonCode is invalid')
    }
    requireCheckText(result, 'reason', context)
    requireCheckText(result, 'message', context)
    return result as DuplicateRuleCheckResult
}

const validateRuleSimilarityResult = (value: unknown): RuleSimilarityResult => {
    const context = 'AI rule-similarity check'
    const result = requireResponseRecord(value, context)
    const isSimilar = requireCheckBoolean(result, 'isSimilar', context)
    const isDuplicate = requireCheckBoolean(result, 'isDuplicate', context)
    const requiresReview = requireCheckBoolean(result, 'requiresReview', context)
    if (isDuplicate && !isSimilar) {
        throw new BoardResponseContractError(context, 'a duplicate result must also be similar')
    }
    requireOptionalCheckText(result, 'matchedRule', context)
    const similarity = requireCheckSimilarity(result, context)
    if (requiresReview !== (isDuplicate || isSimilar || similarity >= 0.8)) {
        throw new BoardResponseContractError(context, 'requiresReview contradicts the similarity result')
    }
    const reasonCode = requireCheckText(result, 'reasonCode', context)
    if (!RULE_SIMILARITY_REASON_CODES.has(reasonCode as RuleSimilarityReasonCode)) {
        throw new BoardResponseContractError(context, 'reasonCode is invalid')
    }
    const expectedReasonCode: RuleSimilarityReasonCode = isDuplicate
        ? 'AI_DUPLICATE'
        : isSimilar
          ? 'AI_SIMILAR'
          : similarity >= 0.8
            ? 'AI_HIGH_SCORE_REVIEW'
            : 'AI_NO_SIGNIFICANT_SIMILARITY'
    if (reasonCode !== expectedReasonCode
        && !(reasonCode === 'NO_EXISTING_RULES' && !isSimilar && !isDuplicate && similarity === 0)) {
        throw new BoardResponseContractError(context, 'reasonCode contradicts the similarity result')
    }
    requireCheckText(result, 'reason', context)
    requireCheckText(result, 'message', context)
    return result as RuleSimilarityResult
}

const validateDeviceMutationResult = (
    value: unknown,
    expectedOperation: DeviceMutationResult['operation'],
    expectedDeviceIds: string[],
    context: string
): DeviceMutationResult => {
    const result = requireResponseRecord(value, context)
    requireOperation(result, expectedOperation, context)
    const affectedDevices = requireResponseArray<DeviceNode>(result, context, 'affectedDevices')
        .map((device, index) => validateBoardNodeResult(
            device,
            `${context}.affectedDevices[${index}]`
        ))
    const currentNodes = requireResponseArray<DeviceNode>(result, context, 'currentNodes')
        .map((device, index) => validateBoardNodeResult(
            device,
            `${context}.currentNodes[${index}]`
        ))
    const environmentVariables = requireResponseArray<ModelEnvironmentVariable>(
        result,
        context,
        'environmentVariables'
    ).map((variable, index) => validateEnvironmentVariable(
        variable,
        `${context}.environmentVariables[${index}]`
    ))
    const environmentChanges = requireResponseArray<EnvironmentVariableChange>(
        result,
        context,
        'environmentChanges'
    ).map((change, index) => validateEnvironmentChangeResult(
        change,
        `${context}.environmentChanges[${index}]`
    ))
    const currentSpecifications = requireResponseArray<Specification>(
        result,
        context,
        'currentSpecifications'
    ).map((specification, index) => validateBoardSpecificationResult(
        specification,
        `${context}.currentSpecifications[${index}]`
    ))
    if (new Set(currentNodes.map(device => device.id)).size !== currentNodes.length) {
        throw new BoardResponseContractError(context, 'currentNodes contains duplicate device ids')
    }
    if (new Set(environmentVariables.map(variable => variable.name)).size !== environmentVariables.length) {
        throw new BoardResponseContractError(context, 'environmentVariables contains duplicate names')
    }
    if (new Set(environmentChanges.map(change => change.name)).size !== environmentChanges.length) {
        throw new BoardResponseContractError(context, 'environmentChanges contains duplicate names')
    }
    if (new Set(currentSpecifications.map(specification => specification.id)).size
        !== currentSpecifications.length) {
        throw new BoardResponseContractError(context, 'currentSpecifications contains duplicate ids')
    }
    requireCurrentCount(result, currentNodes, context)
    if ((expectedOperation === 'created' || expectedOperation === 'renamed')
        && (typeof result.canUndo !== 'boolean' || typeof result.canRedo !== 'boolean')) {
        throw new BoardResponseContractError(
            context,
            'committed device mutations must report boolean canUndo and canRedo values'
        )
    }
    if ((result.canUndo !== undefined && result.canUndo !== null && typeof result.canUndo !== 'boolean')
        || (result.canRedo !== undefined && result.canRedo !== null && typeof result.canRedo !== 'boolean')) {
        throw new BoardResponseContractError(context, 'canUndo and canRedo must be booleans when present')
    }
    if ((expectedOperation === 'created' || expectedOperation === 'renamed')
        && (!result.canUndo || result.canRedo)) {
        throw new BoardResponseContractError(
            context,
            'a committed device mutation must be undoable and must clear redo history'
        )
    }

    if (!Number.isSafeInteger(result.updatedSpecificationCount)
        || result.updatedSpecificationCount < 0) {
        throw new BoardResponseContractError(
            context,
            'updatedSpecificationCount must be a non-negative integer'
        )
    }

    const affectedIds = affectedDevices.map(device => String(device?.id || ''))
    if (affectedDevices.length !== expectedDeviceIds.length
        || expectedDeviceIds.some(id => !affectedIds.includes(id))) {
        throw new BoardResponseContractError(context, 'affectedDevices does not match the requested device set')
    }
    const currentIds = new Set(currentNodes.map(device => String(device?.id || '')))
    if (affectedIds.some(id => !id || !currentIds.has(id))) {
        throw new BoardResponseContractError(context, 'an affected device is absent from currentNodes')
    }
    return {
        ...result,
        affectedDevices,
        currentNodes,
        environmentVariables,
        environmentChanges,
        currentSpecifications
    } as DeviceMutationResult
}

const validateDeviceRenameResult = (
    value: unknown,
    nodeId: string,
    label: string,
    expectedLabel: string
): DeviceMutationResult => {
    const context = 'Device rename'
    const result = validateDeviceMutationResult(value, 'renamed', [nodeId], context)
    const affected = result.affectedDevices.find(device => device.id === nodeId)
    const authoritative = result.currentNodes.find(device => device.id === nodeId)
    if (affected?.label !== label || authoritative?.label !== label) {
        throw new BoardResponseContractError(
            context,
            'affectedDevices and currentNodes must contain the requested label'
        )
    }
    if (result.previousLabel !== expectedLabel) {
        throw new BoardResponseContractError(context, 'previousLabel must match expectedLabel')
    }
    if (!Number.isSafeInteger(result.updatedSpecificationCount)
        || result.updatedSpecificationCount < 0) {
        throw new BoardResponseContractError(
            context,
            'updatedSpecificationCount must be a non-negative integer'
        )
    }
    return result
}

const DEVICE_LAYOUT_FIELDS: DeviceUpdateField[] = ['position.x', 'position.y', 'width', 'height']
const DEVICE_RUNTIME_FIELDS: DeviceUpdateField[] = [
    'state', 'currentStateTrust', 'currentStatePrivacy', 'variables', 'privacies'
]

const normalizedDeviceRuntimeCollection = (
    field: 'variables' | 'privacies',
    value: DeviceNode[typeof field]
): string => {
    const normalized = field === 'variables'
        ? ((value as DeviceNode['variables']) ?? []).map(item => ({
            name: item.name ?? null,
            value: item.value ?? null,
            trust: item.trust ?? null
        }))
        : ((value as DeviceNode['privacies']) ?? []).map(item => ({
            name: item.name ?? null,
            privacy: item.privacy ?? null
        }))
    normalized.sort((left, right) => String(left.name).localeCompare(String(right.name)))
    return JSON.stringify(normalized)
}

const deviceUpdateFieldValue = (device: DeviceNode, field: DeviceUpdateField): unknown => {
    if (field === 'position.x') return device.position?.x ?? null
    if (field === 'position.y') return device.position?.y ?? null
    if (field === 'variables' || field === 'privacies') {
        return normalizedDeviceRuntimeCollection(field, device[field])
    }
    return device[field] ?? null
}

const sameDeviceSnapshot = (left: DeviceNode, right: DeviceNode) =>
    left.id === right.id
    && left.templateName === right.templateName
    && left.label === right.label
    && deviceUpdateFieldValue(left, 'position.x') === deviceUpdateFieldValue(right, 'position.x')
    && deviceUpdateFieldValue(left, 'position.y') === deviceUpdateFieldValue(right, 'position.y')
    && left.width === right.width
    && left.height === right.height
    && deviceUpdateFieldValue(left, 'state') === deviceUpdateFieldValue(right, 'state')
    && deviceUpdateFieldValue(left, 'currentStateTrust') === deviceUpdateFieldValue(right, 'currentStateTrust')
    && deviceUpdateFieldValue(left, 'currentStatePrivacy') === deviceUpdateFieldValue(right, 'currentStatePrivacy')
    && deviceUpdateFieldValue(left, 'variables') === deviceUpdateFieldValue(right, 'variables')
    && deviceUpdateFieldValue(left, 'privacies') === deviceUpdateFieldValue(right, 'privacies')

const validateDeviceUpdateResult = (
    value: unknown,
    mutationType: DeviceUpdateResult['mutationType'],
    nodeId: string,
    requested: DeviceLayout | DeviceRuntimeUpdate
): DeviceUpdateResult => {
    const context = mutationType === 'layout' ? 'Device layout update' : 'Device runtime update'
    const result = requireResponseRecord(value, context)
    if (result.operation !== 'updated' && result.operation !== 'unchanged') {
        throw new BoardResponseContractError(context, "operation must be 'updated' or 'unchanged'")
    }
    if (result.mutationType !== mutationType) {
        throw new BoardResponseContractError(context, `mutationType must be '${mutationType}'`)
    }
    const previousDevice = validateBoardNodeResult(
        result.previousDevice,
        `${context}.previousDevice`
    )
    const currentDevice = validateBoardNodeResult(
        result.currentDevice,
        `${context}.currentDevice`
    )
    if (previousDevice.id !== nodeId || currentDevice.id !== nodeId) {
        throw new BoardResponseContractError(context, 'previousDevice/currentDevice must match the requested device')
    }
    const currentNodes = requireResponseArray<DeviceNode>(result, context, 'currentNodes')
        .map((device, index) => validateBoardNodeResult(
            device,
            `${context}.currentNodes[${index}]`
        ))
    if (new Set(currentNodes.map(device => device.id)).size !== currentNodes.length) {
        throw new BoardResponseContractError(context, 'currentNodes contains duplicate device ids')
    }
    requireCurrentCount(result, currentNodes, context)
    const authoritative = currentNodes.find(device => device.id === nodeId)
    if (!authoritative || !sameDeviceSnapshot(authoritative, currentDevice)) {
        throw new BoardResponseContractError(context, 'currentDevice must match currentNodes')
    }

    const allowedFields = mutationType === 'layout' ? DEVICE_LAYOUT_FIELDS : DEVICE_RUNTIME_FIELDS
    if (!Array.isArray(result.changedFields)
        || result.changedFields.some((field: unknown) => !allowedFields.includes(field as DeviceUpdateField))
        || new Set(result.changedFields).size !== result.changedFields.length) {
        throw new BoardResponseContractError(context, 'changedFields contains an unsupported or duplicate field')
    }
    const actualChanged = allowedFields.filter(field => deviceUpdateFieldValue(
        previousDevice,
        field
    ) !== deviceUpdateFieldValue(currentDevice, field))
    if (actualChanged.length !== result.changedFields.length
        || actualChanged.some(field => !result.changedFields.includes(field))) {
        throw new BoardResponseContractError(context, 'changedFields must agree with previous/current devices')
    }
    if ((result.operation === 'unchanged') !== (result.changedFields.length === 0)) {
        throw new BoardResponseContractError(context, 'operation must agree with changedFields')
    }
    if (result.operation === 'updated') {
        if (result.canUndo !== true || result.canRedo !== false) {
            throw new BoardResponseContractError(
                context,
                'a committed device update must be undoable and must clear redo history'
            )
        }
    } else if ((result.canUndo !== undefined && result.canUndo !== null)
        || (result.canRedo !== undefined && result.canRedo !== null)) {
        throw new BoardResponseContractError(
            context,
            'an unchanged device update must not report new undo availability'
        )
    }

    const preservedFields = mutationType === 'layout'
        ? DEVICE_RUNTIME_FIELDS
        : DEVICE_LAYOUT_FIELDS
    if (preservedFields.some(field => deviceUpdateFieldValue(
        previousDevice,
        field
    ) !== deviceUpdateFieldValue(currentDevice, field))
        || previousDevice.id !== currentDevice.id
        || previousDevice.templateName !== currentDevice.templateName
        || previousDevice.label !== currentDevice.label) {
        throw new BoardResponseContractError(context, 'the targeted patch changed a preserved device field')
    }

    if (mutationType === 'layout') {
        const layout = requested as DeviceLayout
        if (currentDevice.position?.x !== layout.position.x
            || currentDevice.position?.y !== layout.position.y
            || currentDevice.width !== layout.width
            || currentDevice.height !== layout.height) {
            throw new BoardResponseContractError(context, 'currentDevice does not contain the requested layout')
        }
    } else {
        const runtime = (requested as DeviceRuntimeUpdate).desired
        for (const field of ['currentStateTrust', 'currentStatePrivacy'] as const) {
            if ((currentDevice[field] ?? null) !== (runtime[field] ?? null)) {
                throw new BoardResponseContractError(context, `currentDevice does not contain requested ${field}`)
            }
        }
        if (runtime.state !== undefined && currentDevice.state !== runtime.state) {
            throw new BoardResponseContractError(context, 'currentDevice does not contain the requested state')
        }
        for (const field of ['variables', 'privacies'] as const) {
            if (normalizedDeviceRuntimeCollection(field, currentDevice[field])
                !== normalizedDeviceRuntimeCollection(field, runtime[field])) {
                throw new BoardResponseContractError(context, `currentDevice does not contain requested ${field}`)
            }
        }
    }
    return { ...result, previousDevice, currentDevice, currentNodes } as DeviceUpdateResult
}

const ENVIRONMENT_FIELDS: EnvironmentVariableField[] = ['value', 'trust', 'privacy']

const requireEnvironmentFieldArray = (
    value: unknown,
    field: string,
    context: string
): EnvironmentVariableField[] => {
    if (!Array.isArray(value)
        || value.some(candidate => !ENVIRONMENT_FIELDS.includes(candidate as EnvironmentVariableField))
        || new Set(value).size !== value.length) {
        throw new BoardResponseContractError(
            context,
            `${field} must contain unique value/trust/privacy field names`
        )
    }
    return value as EnvironmentVariableField[]
}

const environmentFieldValue = (
    variable: ModelEnvironmentVariable,
    field: EnvironmentVariableField
) => variable[field] ?? null

const canonicalEnvironmentFieldValue = (
    field: EnvironmentVariableField,
    value: unknown
) => {
    if (value === null || value === undefined) return null
    if (typeof value !== 'string') return value
    const trimmed = value.trim()
    return field === 'trust' || field === 'privacy'
        ? trimmed.toLowerCase()
        : trimmed
}

const validateEnvironmentChangeResult = (
    value: unknown,
    context: string
): EnvironmentVariableChange => {
    const change = requireResponseRecord(value, context)
    if (!['ADDED', 'UPDATED', 'REMOVED'].includes(change.changeType)) {
        throw new BoardResponseContractError(context, 'changeType is invalid')
    }
    if (typeof change.name !== 'string' || !change.name.trim()) {
        throw new BoardResponseContractError(context, 'name is required')
    }
    const previous = change.previousValue === null || change.previousValue === undefined
        ? null
        : validateEnvironmentVariable(change.previousValue, `${context}.previousValue`)
    const current = change.currentValue === null || change.currentValue === undefined
        ? null
        : validateEnvironmentVariable(change.currentValue, `${context}.currentValue`)
    if (previous && previous.name !== change.name) {
        throw new BoardResponseContractError(context, 'previousValue.name must match name')
    }
    if (current && current.name !== change.name) {
        throw new BoardResponseContractError(context, 'currentValue.name must match name')
    }
    if ((change.changeType === 'ADDED' && (previous || !current))
        || (change.changeType === 'UPDATED' && (!previous || !current))
        || (change.changeType === 'REMOVED' && (!previous || current))) {
        throw new BoardResponseContractError(context, 'values contradict changeType')
    }
    for (const field of ['previousModelTokenSource', 'currentModelTokenSource']) {
        if (change[field] !== undefined && !MODEL_TOKEN_SOURCE_SET.has(change[field])) {
            throw new BoardResponseContractError(context, `${field} is invalid`)
        }
    }
    return { ...change, previousValue: previous, currentValue: current } as EnvironmentVariableChange
}

const validateEnvironmentMutationResult = (
    value: unknown,
    patches: EnvironmentVariableUpdateRequest[]
): EnvironmentMutationResult => {
    const context = 'Environment Pool update'
    const result = requireResponseRecord(value, context)
    if (result.operation !== 'updated' && result.operation !== 'unchanged') {
        throw new BoardResponseContractError(context, "operation must be 'updated' or 'unchanged'")
    }
    const patchResults = requireResponseArray<EnvironmentVariablePatchResult>(
        result,
        context,
        'patchResults'
    )
    const environmentVariables = requireResponseArray<ModelEnvironmentVariable>(
        result,
        context,
        'environmentVariables'
    ).map((variable, index) => validateEnvironmentVariable(
        variable,
        `${context}.environmentVariables[${index}]`
    ))
    const environmentChanges = requireResponseArray<EnvironmentVariableChange>(
        result,
        context,
        'environmentChanges'
    ).map((change, index) => validateEnvironmentChangeResult(
        change,
        `${context}.environmentChanges[${index}]`
    ))
    requireCurrentCount(result, environmentVariables, context)
    if ((result.operation === 'unchanged') !== (environmentChanges.length === 0)) {
        throw new BoardResponseContractError(context, 'operation must agree with environmentChanges')
    }
    if (result.operation === 'updated') {
        if (result.canUndo !== true || result.canRedo !== false) {
            throw new BoardResponseContractError(
                context,
                'a committed Environment Pool update must be undoable and must clear redo history'
            )
        }
    } else if ((result.canUndo !== undefined && result.canUndo !== null)
        || (result.canRedo !== undefined && result.canRedo !== null)) {
        throw new BoardResponseContractError(
            context,
            'an unchanged Environment Pool update must not report new undo availability'
        )
    }
    if (patchResults.length !== patches.length) {
        throw new BoardResponseContractError(context, 'patchResults must explain every submitted patch')
    }

    const currentByName = new Map(environmentVariables.map(variable => [variable.name, variable]))
    const resultByName = new Map<string, EnvironmentVariablePatchResult>()
    for (const raw of patchResults) {
        const patchResult = requireResponseRecord(raw, context) as unknown as EnvironmentVariablePatchResult
        if (typeof patchResult.name !== 'string' || !patchResult.name.trim()
            || resultByName.has(patchResult.name)) {
            throw new BoardResponseContractError(context, 'patchResults must have unique non-blank names')
        }
        const suppliedFields = requireEnvironmentFieldArray(
            patchResult.suppliedFields,
            `patchResults.${patchResult.name}.suppliedFields`,
            context
        )
        const changedFields = requireEnvironmentFieldArray(
            patchResult.changedFields,
            `patchResults.${patchResult.name}.changedFields`,
            context
        )
        const preservedFields = requireEnvironmentFieldArray(
            patchResult.preservedFields,
            `patchResults.${patchResult.name}.preservedFields`,
            context
        )
        if (!patchResult.previousValue || !patchResult.currentValue
            || patchResult.previousValue.name !== patchResult.name
            || patchResult.currentValue.name !== patchResult.name) {
            throw new BoardResponseContractError(context, 'each patch result needs matching previous/current values')
        }
        validateEnvironmentVariable(
            patchResult.previousValue,
            `${context}.${patchResult.name}.previousValue`
        )
        validateEnvironmentVariable(
            patchResult.currentValue,
            `${context}.${patchResult.name}.currentValue`
        )
        if (changedFields.some(field => !suppliedFields.includes(field))) {
            throw new BoardResponseContractError(context, 'changedFields must be a subset of suppliedFields')
        }
        const expectedPreserved = ENVIRONMENT_FIELDS.filter(field => !suppliedFields.includes(field))
        if (preservedFields.length !== expectedPreserved.length
            || expectedPreserved.some(field => !preservedFields.includes(field))) {
            throw new BoardResponseContractError(context, 'preservedFields must explain every omitted field')
        }
        if (preservedFields.some(field => environmentFieldValue(
            patchResult.previousValue,
            field
        ) !== environmentFieldValue(patchResult.currentValue, field))) {
            throw new BoardResponseContractError(context, 'a preserved field changed value')
        }
        const actualChanged = suppliedFields.filter(field => environmentFieldValue(
            patchResult.previousValue,
            field
        ) !== environmentFieldValue(patchResult.currentValue, field))
        if (actualChanged.length !== changedFields.length
            || actualChanged.some(field => !changedFields.includes(field))) {
            throw new BoardResponseContractError(context, 'changedFields must agree with previous/current values')
        }
        const authoritative = currentByName.get(patchResult.name)
        if (!authoritative || ENVIRONMENT_FIELDS.some(field =>
            environmentFieldValue(authoritative, field)
            !== environmentFieldValue(patchResult.currentValue, field))) {
            throw new BoardResponseContractError(context, 'currentValue must match environmentVariables')
        }
        resultByName.set(patchResult.name, patchResult)
    }

    for (const patch of patches) {
        const patchResult = resultByName.get(patch.name)
        if (!patchResult) {
            throw new BoardResponseContractError(context, `no patch result was returned for ${patch.name}`)
        }
        const expectedSupplied = ENVIRONMENT_FIELDS.filter(field =>
            Object.prototype.hasOwnProperty.call(patch.desired, field))
        if (patchResult.suppliedFields.length !== expectedSupplied.length
            || expectedSupplied.some(field => !patchResult.suppliedFields.includes(field))) {
            throw new BoardResponseContractError(context, `suppliedFields does not match the patch for ${patch.name}`)
        }
        for (const field of ENVIRONMENT_FIELDS) {
            const expectedValue = canonicalEnvironmentFieldValue(field, patch.expected[field])
            const previousValue = canonicalEnvironmentFieldValue(
                field,
                patchResult.previousValue[field]
            )
            if (expectedValue !== previousValue) {
                throw new BoardResponseContractError(
                    context,
                    `previousValue does not match the expected baseline for ${patch.name}`
                )
            }
            const desiredValue = patch.desired[field]
            if (Object.prototype.hasOwnProperty.call(patch.desired, field)) {
                const currentValue = canonicalEnvironmentFieldValue(
                    field,
                    patchResult.currentValue[field]
                )
                if (canonicalEnvironmentFieldValue(field, desiredValue) !== currentValue) {
                    throw new BoardResponseContractError(
                        context,
                        `currentValue does not match the desired ${field} for ${patch.name}`
                    )
                }
            }
        }
    }
    return result as EnvironmentMutationResult
}

const validateEnvironmentUpdateRequests = (
    value: unknown
): EnvironmentVariableUpdateRequest[] => {
    const context = 'Environment Pool update request'
    if (!Array.isArray(value) || value.length === 0) {
        throw new BoardResponseContractError(context, 'at least one update is required')
    }
    const names = new Set<string>()
    return value.map((candidate, index) => {
        const update = requireResponseRecord(candidate, `${context}[${index}]`) as unknown as EnvironmentVariableUpdateRequest
        const name = typeof update.name === 'string' ? update.name.trim() : ''
        if (!name || names.has(name)) {
            throw new BoardResponseContractError(context, `update ${index} has an invalid or duplicate name`)
        }
        names.add(name)
        if (!update.expected || typeof update.expected !== 'object'
            || typeof update.expected.trust !== 'string' || !update.expected.trust.trim()
            || typeof update.expected.privacy !== 'string' || !update.expected.privacy.trim()) {
            throw new BoardResponseContractError(context, `update ${name} has an incomplete expected baseline`)
        }
        const expectedFields = Object.keys(update.expected)
        if (expectedFields.some(field => !ENVIRONMENT_FIELDS.includes(field as EnvironmentVariableField))) {
            throw new BoardResponseContractError(context, `update ${name} has an unknown expected field`)
        }
        if (typeof update.expected.value !== 'string' || !update.expected.value.trim()) {
            throw new BoardResponseContractError(context, `update ${name} has an invalid expected value`)
        }
        if (typeof update.expected.trust === 'string'
            && !TRUST_VALUE_SET.has(update.expected.trust.trim().toLowerCase())) {
            throw new BoardResponseContractError(context, `update ${name} has an invalid expected trust`)
        }
        if (typeof update.expected.privacy === 'string'
            && !PRIVACY_VALUE_SET.has(update.expected.privacy.trim().toLowerCase())) {
            throw new BoardResponseContractError(context, `update ${name} has an invalid expected privacy`)
        }
        if (!update.desired || typeof update.desired !== 'object') {
            throw new BoardResponseContractError(context, `update ${name} has no desired patch`)
        }
        const desiredFields = Object.keys(update.desired)
        if (desiredFields.some(field => !ENVIRONMENT_FIELDS.includes(field as EnvironmentVariableField))) {
            throw new BoardResponseContractError(context, `update ${name} has an unknown desired field`)
        }
        const supplied = ENVIRONMENT_FIELDS.filter(field =>
            Object.prototype.hasOwnProperty.call(update.desired, field))
        if (supplied.length === 0) {
            throw new BoardResponseContractError(context, `update ${name} has no desired field`)
        }
        if (supplied.some(field => typeof update.desired[field] !== 'string')) {
            throw new BoardResponseContractError(context, `update ${name} has an invalid desired value`)
        }
        if (typeof update.desired.value === 'string' && !update.desired.value.trim()) {
            throw new BoardResponseContractError(context, `update ${name} has a blank desired value`)
        }
        if (typeof update.desired.trust === 'string'
            && !TRUST_VALUE_SET.has(update.desired.trust.trim().toLowerCase())) {
            throw new BoardResponseContractError(context, `update ${name} has an invalid desired trust`)
        }
        if (typeof update.desired.privacy === 'string'
            && !PRIVACY_VALUE_SET.has(update.desired.privacy.trim().toLowerCase())) {
            throw new BoardResponseContractError(context, `update ${name} has an invalid desired privacy`)
        }
        return { ...update, name }
    })
}

const validateDeviceDeletionResult = (
    value: unknown,
    expectedOperation: DeviceDeletionResult['operation'],
    expectedNodeId: string,
    context: string,
    expectedImpactToken?: string
): Record<string, any> => {
    const result = requireResponseRecord(value, context)
    requireOperation(result, expectedOperation, context)
    if (typeof result.impactToken !== 'string' || !result.impactToken.trim()) {
        throw new BoardResponseContractError(context, 'impactToken is required')
    }
    if (expectedOperation === 'deleted'
        && result.impactToken.trim() !== String(expectedImpactToken || '').trim()) {
        throw new BoardResponseContractError(context, 'impactToken does not match the confirmed preview')
    }

    const validateDevice = (candidate: unknown, field: string) =>
        validateBoardNodeResult(candidate, `${context}.${field}`)
    const validateRule = (candidate: unknown, field: string) =>
        validateBackendRuleResult(candidate, `${context}.${field}`)
    const validateSpecification = (candidate: unknown, field: string) =>
        validateBoardSpecificationResult(candidate, `${context}.${field}`)
    const validateEnvironment = (candidate: unknown, field: string) =>
        validateEnvironmentVariable(candidate, `${context}.${field}`)

    const deletedDevice = validateDevice(result.deletedDevice, 'deletedDevice')
    if (deletedDevice.id !== expectedNodeId) {
        throw new BoardResponseContractError(context, 'deletedDevice does not match the requested device')
    }

    const currentNodes = requireResponseArray(result, context, 'currentNodes')
        .map((device, index) => validateDevice(device, `currentNodes[${index}]`))
    const currentNodeIds = new Set(currentNodes.map(device => device.id))
    if (currentNodeIds.size !== currentNodes.length) {
        throw new BoardResponseContractError(context, 'currentNodes contains duplicate device ids')
    }
    if ((expectedOperation === 'preview') !== currentNodeIds.has(expectedNodeId)) {
        throw new BoardResponseContractError(
            context,
            expectedOperation === 'preview'
                ? 'currentNodes must retain the previewed device'
                : 'currentNodes must omit the deleted device'
        )
    }

    const removedRules = requireResponseArray(result, context, 'removedRules')
        .map((rule, index) => validateRule(rule, `removedRules[${index}]`))
    const currentRules = requireResponseArray(result, context, 'currentRules')
        .map((rule, index) => validateRule(rule, `currentRules[${index}]`))
    const removedSpecifications = requireResponseArray(result, context, 'removedSpecifications')
        .map((specification, index) => validateSpecification(specification, `removedSpecifications[${index}]`))
    const currentSpecifications = requireResponseArray(result, context, 'currentSpecifications')
        .map((specification, index) => validateSpecification(specification, `currentSpecifications[${index}]`))

    const validateDeletionCollection = <T>(
        removed: T[],
        current: T[],
        identityOf: (item: T) => string | number,
        field: string
    ) => {
        requireUniqueIdentities(removed, identityOf, context, `removed${field}`)
        requireUniqueIdentities(current, identityOf, context, `current${field}`)
        const removedIdentities = new Set(removed.map(identityOf))
        const currentIdentities = new Set(current.map(identityOf))
        const overlap = [...removedIdentities].some(identity => currentIdentities.has(identity))
        if (expectedOperation === 'preview') {
            if ([...removedIdentities].some(identity => !currentIdentities.has(identity))) {
                throw new BoardResponseContractError(
                    context,
                    `removed${field} must be present in current${field} during preview`
                )
            }
        } else if (overlap) {
            throw new BoardResponseContractError(
                context,
                `removed${field} must be absent from current${field} after deletion`
            )
        }
    }
    validateDeletionCollection(removedRules, currentRules, rule => Number(rule.id), 'Rules')
    validateDeletionCollection(removedSpecifications, currentSpecifications, specification => specification.id, 'Specifications')

    const environmentVariables = requireResponseArray(result, context, 'environmentVariables')
        .map((variable, index) => validateEnvironment(variable, `environmentVariables[${index}]`))
    if (new Set(environmentVariables.map(variable => variable.name)).size !== environmentVariables.length) {
        throw new BoardResponseContractError(context, 'environmentVariables contains duplicate names')
    }
    const environmentChanges = requireResponseArray<EnvironmentVariableChange>(
        result,
        context,
        'environmentChanges'
    ).map((change, index) => validateEnvironmentChangeResult(
        change,
        `${context}.environmentChanges[${index}]`
    ))
    if (new Set(environmentChanges.map(change => change.name)).size !== environmentChanges.length) {
        throw new BoardResponseContractError(context, 'environmentChanges contains duplicate names')
    }
    if (expectedOperation === 'deleted'
        && (typeof result.canUndo !== 'boolean' || typeof result.canRedo !== 'boolean')) {
        throw new BoardResponseContractError(
            context,
            'device deletion must report boolean canUndo and canRedo values'
        )
    }
    if ((result.canUndo !== undefined && result.canUndo !== null && typeof result.canUndo !== 'boolean')
        || (result.canRedo !== undefined && result.canRedo !== null && typeof result.canRedo !== 'boolean')) {
        throw new BoardResponseContractError(context, 'canUndo and canRedo must be booleans when present')
    }
    if (expectedOperation === 'deleted' && (!result.canUndo || result.canRedo)) {
        throw new BoardResponseContractError(
            context,
            'a committed device deletion must be undoable and must clear redo history'
        )
    }
    return {
        ...result,
        deletedDevice,
        currentNodes,
        removedRules,
        currentRules,
        removedSpecifications,
        currentSpecifications,
        environmentVariables,
        environmentChanges
    } as DeviceDeletionResult & { removedRules: BackendRuleDto[]; currentRules: BackendRuleDto[] }
}

const validateCollectionMutationResult = <T>(
    value: unknown,
    expectedOperation: CollectionMutationResult<T>['operation'],
    context: string,
    validateItem?: (value: unknown, context: string) => T,
    identityOf?: (item: T) => string | number,
    expectedAffectedIdentity?: string | number
): CollectionMutationResult<T> => {
    const result = requireResponseRecord(value, context)
    requireOperation(result, expectedOperation, context)
    if (typeof result.canUndo !== 'boolean' || typeof result.canRedo !== 'boolean') {
        throw new BoardResponseContractError(
            context,
            'reversible collection mutations must report boolean canUndo and canRedo values'
        )
    }
    if (!result.canUndo || result.canRedo) {
        throw new BoardResponseContractError(
            context,
            'a committed collection mutation must be undoable and must clear redo history'
        )
    }
    if (!result.affectedItem || typeof result.affectedItem !== 'object' || Array.isArray(result.affectedItem)) {
        throw new BoardResponseContractError(context, 'affectedItem is required')
    }
    const currentItems = requireResponseArray<T>(result, context, 'currentItems')
        .map((item, index) => validateItem
            ? validateItem(item, `${context}.currentItems[${index}]`)
            : item)
    const affectedItem = validateItem
        ? validateItem(result.affectedItem, `${context}.affectedItem`)
        : result.affectedItem as T
    if (identityOf) {
        requireUniqueIdentities(currentItems, identityOf, context, 'currentItems')
        const affectedIdentity = identityOf(affectedItem)
        if (expectedAffectedIdentity !== undefined
            && affectedIdentity !== expectedAffectedIdentity) {
            throw new BoardResponseContractError(
                context,
                'affectedItem does not match the requested item identity'
            )
        }
        const affectedIsCurrent = currentItems.some(item => identityOf(item) === affectedIdentity)
        if ((expectedOperation === 'created' && !affectedIsCurrent)
            || (expectedOperation === 'deleted' && affectedIsCurrent)) {
            throw new BoardResponseContractError(
                context,
                'affectedItem contradicts the authoritative currentItems collection'
            )
        }
    }
    requireCurrentCount({ ...result, currentItems }, currentItems, context)
    return { ...result, affectedItem, currentItems } as CollectionMutationResult<T>
}

/**
 * Validates an undo/redo/availability payload at the boundary.
 *
 * All four semantic collections are authoritative post-operation state, so they get the same
 * validation as a normal read: malformed or contradictory data must not enter board state.
 */
const parseBoardUndoResult = (
    value: unknown,
    expectedKind: 'availability' | 'undo' | 'redo' | 'clear'
): BoardUndoResult => {
    const context = 'Board edit undo'
    const raw = requireResponseRecord(value, context)
    if (typeof raw.applied !== 'boolean'
        || typeof raw.canUndo !== 'boolean'
        || typeof raw.canRedo !== 'boolean') {
        throw new BoardResponseContractError(
            context, 'applied, canUndo and canRedo must be booleans')
    }
    const nodes = requireResponseArray<DeviceNode>(raw, context, 'nodes')
        .map((node, index) => validateBoardNodeResult(node, `${context}.nodes[${index}]`))
    requireUniqueIdentities(nodes, node => node.id, context, 'nodes')

    const environmentVariables = requireResponseArray<ModelEnvironmentVariable>(
        raw,
        context,
        'environmentVariables'
    ).map((variable, index) => validateEnvironmentVariable(
        variable,
        `${context}.environmentVariables[${index}]`
    ))
    requireUniqueIdentities(
        environmentVariables,
        variable => variable.name,
        context,
        'environmentVariables'
    )

    const rules = requireResponseArray<BackendRuleDto>(raw, context, 'rules')
        .map((rule, index) => validateBackendRuleResult(rule, `${context}.rules[${index}]`))
    requireUniqueIdentities(rules, rule => Number(rule.id), context, 'rules')

    const specs = requireResponseArray<Specification>(raw, context, 'specs')
        .map((spec, index) => validateBoardSpecificationResult(spec, `${context}.specs[${index}]`))
    requireUniqueIdentities(specs, spec => spec.id, context, 'specs')
    // Validated rather than cast: defaulting an absent reasonCode to 'NOTHING_TO_APPLY' produced
    // `applied: true` alongside a code contradicting it, and an unknown string became a typed value
    // that lies — a consumer switching on it silently takes no branch.
    if (!isBoardUndoReasonCode(raw.reasonCode)) {
        throw new BoardResponseContractError(
            context, `reasonCode must be one of ${BOARD_UNDO_REASON_CODES.join(', ')}`)
    }
    if (raw.entityType !== undefined && raw.entityType !== null
        && !isBoardEditEntityType(raw.entityType)) {
        throw new BoardResponseContractError(
            context, `entityType must be one of ${BOARD_EDIT_ENTITY_TYPES.join(', ')}`)
    }
    if (raw.originalOperation !== undefined && raw.originalOperation !== null
        && !isBoardEditOperation(raw.originalOperation)) {
        throw new BoardResponseContractError(
            context, `originalOperation must be one of ${BOARD_EDIT_OPERATIONS.join(', ')}`)
    }
    const hasEntityMetadata = raw.entityType !== undefined && raw.entityType !== null
        && raw.originalOperation !== undefined && raw.originalOperation !== null
    const hasAnyEntityMetadata = raw.entityType !== undefined && raw.entityType !== null
        || raw.originalOperation !== undefined && raw.originalOperation !== null
    if (expectedKind === 'availability' || expectedKind === 'clear') {
        const expectedReason = expectedKind === 'availability'
            ? 'AVAILABILITY_ONLY'
            : 'HISTORY_CLEARED'
        if (raw.applied || raw.reasonCode !== expectedReason || hasAnyEntityMetadata) {
            throw new BoardResponseContractError(
                context,
                `${expectedKind} must be unapplied, use ${expectedReason}, and omit entity metadata`
            )
        }
        if (nodes.length || environmentVariables.length || rules.length || specs.length) {
            throw new BoardResponseContractError(
                context,
                `${expectedKind} collections must be empty because they are not a board snapshot`
            )
        }
        if (expectedKind === 'clear' && (raw.canUndo || raw.canRedo)) {
            throw new BoardResponseContractError(
                context,
                'cleared history must report canUndo=false and canRedo=false'
            )
        }
    } else if (raw.applied) {
        const expectedReason = expectedKind === 'undo' ? 'UNDONE' : 'REDONE'
        if (raw.reasonCode !== expectedReason || !hasEntityMetadata) {
            throw new BoardResponseContractError(
                context,
                `an applied ${expectedKind} must use ${expectedReason} and include entity metadata`
            )
        }
        const validOperation = raw.entityType === 'RULE' || raw.entityType === 'SPECIFICATION'
            ? raw.originalOperation === 'CREATE' || raw.originalOperation === 'DELETE'
            : raw.entityType === 'RULE_ORDER'
                || raw.entityType === 'RULE_SET'
                || raw.entityType === 'ENVIRONMENT'
                ? raw.originalOperation === 'UPDATE'
                : raw.entityType === 'DEVICE'
                    && (raw.originalOperation === 'CREATE'
                        || raw.originalOperation === 'UPDATE'
                        || raw.originalOperation === 'DELETE')
        if (!validOperation) {
            throw new BoardResponseContractError(
                context,
                'entityType and originalOperation do not describe a supported reversible edit'
            )
        }
        if ((expectedKind === 'undo' && !raw.canRedo)
            || (expectedKind === 'redo' && !raw.canUndo)) {
            throw new BoardResponseContractError(
                context,
                `an applied ${expectedKind} must make its inverse available`
            )
        }
    } else if (raw.reasonCode !== 'NOTHING_TO_APPLY' || hasAnyEntityMetadata) {
        throw new BoardResponseContractError(
            context,
            'an unapplied undo/redo must use NOTHING_TO_APPLY and omit entity metadata'
        )
    } else if ((expectedKind === 'undo' && raw.canUndo)
        || (expectedKind === 'redo' && raw.canRedo)) {
        throw new BoardResponseContractError(
            context,
            `NOTHING_TO_APPLY must report ${expectedKind === 'undo' ? 'canUndo' : 'canRedo'}=false`
        )
    }
    return {
        applied: raw.applied,
        entityType: raw.entityType ?? undefined,
        originalOperation: raw.originalOperation ?? undefined,
        reasonCode: raw.reasonCode,
        nodes,
        environmentVariables,
        rules: rules.map(fromBackendRuleDto),
        specs,
        canUndo: raw.canUndo,
        canRedo: raw.canRedo
    }
}

const validateBoardBatchResult = (value: unknown) => {
    const context = 'Scene replacement'
    const result = requireResponseRecord(value, context)
    const nodes = requireResponseArray<DeviceNode>(result, context, 'nodes')
        .map((node, index) => validateBoardNodeResult(node, `${context}.nodes[${index}]`))
    if (new Set(nodes.map(node => node.id)).size !== nodes.length) {
        throw new BoardResponseContractError(context, 'nodes contains duplicate ids')
    }
    const environmentVariables = requireResponseArray<ModelEnvironmentVariable>(result, context, 'environmentVariables')
        .map((variable, index) => validateEnvironmentVariable(variable, `${context}.environmentVariables[${index}]`))
    if (new Set(environmentVariables.map(variable => variable.name)).size !== environmentVariables.length) {
        throw new BoardResponseContractError(context, 'environmentVariables contains duplicate names')
    }
    const rules = requireResponseArray<BackendRuleDto>(result, context, 'rules')
        .map((rule, index) => validateBackendRuleResult(rule, `${context}.rules[${index}]`))
    requireUniqueIdentities(rules, rule => Number(rule.id), context, 'rules')
    const ruleIds = rules.map(rule => rule.id)
    if (new Set(ruleIds).size !== ruleIds.length) {
        throw new BoardResponseContractError(context, 'rules contains duplicate ids')
    }
    const specs = requireResponseArray<Specification>(result, context, 'specs')
        .map((spec, index) => validateBoardSpecificationResult(spec, `${context}.specs[${index}]`))
    if (new Set(specs.map(spec => spec.id)).size !== specs.length) {
        throw new BoardResponseContractError(context, 'specs contains duplicate ids')
    }
    const createdTemplates = requireResponseArray<DeviceTemplate>(result, context, 'createdTemplates')
        .map((template, index) => validateDeviceTemplateResult(template, `${context}.createdTemplates[${index}]`))
    requireUniqueIdentities(
        createdTemplates,
        template => template.name.trim().toLowerCase(),
        context,
        'createdTemplates'
    )
    requireUniqueIdentities(createdTemplates, template => Number(template.id), context, 'createdTemplates')
    return {
        ...result,
        nodes,
        environmentVariables,
        rules,
        specs,
        createdTemplates
    } as {
        nodes: DeviceNode[]
        environmentVariables: ModelEnvironmentVariable[]
        rules: BackendRuleDto[]
        specs: Specification[]
        createdTemplates: DeviceTemplate[]
    }
}

const validateBoardReplacementPreview = (value: unknown): BoardReplacementPreview => {
    const context = 'Scene replacement preview'
    const result = requireResponseRecord(value, context)
    if (typeof result.impactToken !== 'string' || !result.impactToken.trim()) {
        throw new BoardResponseContractError(context, 'impactToken is required')
    }
    for (const field of [
        'deviceCount',
        'environmentVariableCount',
        'ruleCount',
        'specificationCount',
        'editHistoryEntryCount'
    ]) {
        if (!Number.isSafeInteger(result[field]) || result[field] < 0) {
            throw new BoardResponseContractError(context, `${field} must be a non-negative integer`)
        }
    }
    return result as BoardReplacementPreview
}

const validateBoardEditHistoryClearPreview = (
    value: unknown
): BoardEditHistoryClearPreview => {
    const context = 'Undo-history clear preview'
    const result = requireResponseRecord(value, context)
    if (typeof result.impactToken !== 'string'
        || !/^[0-9a-f]{64}$/.test(result.impactToken)) {
        throw new BoardResponseContractError(context, 'impactToken must be a SHA-256 token')
    }
    if (!Number.isSafeInteger(result.entryCount) || result.entryCount < 0) {
        throw new BoardResponseContractError(context, 'entryCount must be a non-negative integer')
    }
    if (typeof result.canUndo !== 'boolean' || typeof result.canRedo !== 'boolean') {
        throw new BoardResponseContractError(context, 'canUndo and canRedo must be booleans')
    }
    if ((result.entryCount === 0) !== (!result.canUndo && !result.canRedo)) {
        throw new BoardResponseContractError(
            context,
            'entryCount must agree with undo/redo availability'
        )
    }
    return result as unknown as BoardEditHistoryClearPreview
}

const validateDeviceTemplateResult = (value: unknown, context: string): DeviceTemplate => {
    const result = requireResponseRecord(value, context)
    if (typeof result.name !== 'string' || !result.name.trim()) {
        throw new BoardResponseContractError(context, 'template name is required')
    }
    if (!result.manifest || typeof result.manifest !== 'object' || Array.isArray(result.manifest)) {
        throw new BoardResponseContractError(context, 'template manifest is required')
    }
    const manifest = result.manifest as Record<string, unknown>
    if (typeof manifest.Name !== 'string' || !manifest.Name.trim()) {
        throw new BoardResponseContractError(context, 'template manifest.Name is required')
    }
    if (manifest.Name.trim() !== result.name.trim()) {
        throw new BoardResponseContractError(context, 'template name must match manifest.Name')
    }
    const manifestValidation = validateManifest(manifest)
    if (!manifestValidation.valid) {
        throw new BoardResponseContractError(
            context,
            `template manifest is invalid${manifestValidation.code ? ` (${manifestValidation.code})` : ''}`
        )
    }
    if (!Number.isSafeInteger(result.id) || result.id <= 0) {
        throw new BoardResponseContractError(context, 'template id must be a positive integer')
    }
    if (typeof result.defaultTemplate !== 'boolean') {
        throw new BoardResponseContractError(context, 'defaultTemplate must be boolean')
    }
    return result as DeviceTemplate
}

const sameDeviceTemplateSnapshot = (left: DeviceTemplate, right: DeviceTemplate): boolean =>
    left.id === right.id
    && left.name === right.name
    && left.defaultTemplate === right.defaultTemplate
    // Both snapshots come from fields serialized by the same backend DTO, so key order is stable.
    && JSON.stringify(left.manifest) === JSON.stringify(right.manifest)

const validateDeviceTemplateDeletionResult = (
    value: unknown,
    expectedOperation: DeviceTemplateDeletionResult['operation'],
    expectedTemplateId: number,
    expectedImpactToken?: string
): DeviceTemplateDeletionResult => {
    const context = expectedOperation === 'preview'
        ? 'Device type deletion preview'
        : 'Device type deletion'
    const result = requireResponseRecord(value, context)
    requireOperation(result, expectedOperation, context)
    if (typeof result.impactToken !== 'string' || !result.impactToken.trim()) {
        throw new BoardResponseContractError(context, 'impactToken is required')
    }
    if (expectedOperation === 'deleted'
        && result.impactToken.trim() !== String(expectedImpactToken || '').trim()) {
        throw new BoardResponseContractError(context, 'impactToken does not match the confirmed preview')
    }
    if (typeof result.canDelete !== 'boolean') {
        throw new BoardResponseContractError(context, 'canDelete must be boolean')
    }
    if (!Number.isSafeInteger(result.editHistoryEntryCount) || result.editHistoryEntryCount < 0) {
        throw new BoardResponseContractError(context, 'editHistoryEntryCount must be a non-negative integer')
    }
    if (!Number.isSafeInteger(expectedTemplateId) || expectedTemplateId <= 0) {
        throw new BoardResponseContractError(context, 'the requested template id is invalid')
    }
    const template = validateDeviceTemplateResult(result.template, `${context} template`)
    if (template.id !== expectedTemplateId) {
        throw new BoardResponseContractError(context, 'template does not match the requested template')
    }
    const blockers = requireResponseArray<any>(result, context, 'blockers')
    blockers.forEach((blocker, index) => {
        const row = requireResponseRecord(blocker, `${context} blockers[${index}]`)
        for (const field of ['reasonCode', 'itemId', 'itemLabel', 'reason']) {
            if (typeof row[field] !== 'string' || !row[field].trim()) {
                throw new BoardResponseContractError(context, `blockers[${index}].${field} is required`)
            }
        }
    })
    if (result.canDelete !== (blockers.length === 0)) {
        throw new BoardResponseContractError(context, 'canDelete must match blockers')
    }
    const currentTemplates = requireResponseArray<DeviceTemplate>(result, context, 'currentTemplates')
    currentTemplates.forEach((item, index) =>
        validateDeviceTemplateResult(item, `${context} currentTemplates[${index}]`))
    if (currentTemplates.some(item => !Number.isSafeInteger(item.id) || Number(item.id) <= 0)) {
        throw new BoardResponseContractError(context, 'currentTemplates must include positive ids')
    }
    requireUniqueIdentities(
        currentTemplates,
        item => item.name.trim().toLowerCase(),
        context,
        'currentTemplates'
    )
    requireUniqueIdentities(currentTemplates, item => Number(item.id), context, 'currentTemplates')
    if (expectedOperation === 'deleted') {
        const deleted = validateDeviceTemplateResult(result.deletedTemplate, `${context} deletedTemplate`)
        if (deleted.id !== expectedTemplateId
            || !sameDeviceTemplateSnapshot(template, deleted)
            || currentTemplates.some(item => item.id === expectedTemplateId)) {
            throw new BoardResponseContractError(context, 'deletedTemplate contradicts currentTemplates')
        }
    } else {
        const current = currentTemplates.find(item => item.id === expectedTemplateId)
        if (!current || !sameDeviceTemplateSnapshot(template, current)) {
            throw new BoardResponseContractError(context, 'currentTemplates must retain the previewed template snapshot')
        }
    }
    return result as DeviceTemplateDeletionResult
}

export const parseDeviceTemplateDeletionPreview = (
    value: unknown,
    expectedTemplateId: number
): DeviceTemplateDeletionResult => validateDeviceTemplateDeletionResult(
    value,
    'preview',
    expectedTemplateId
)

const validateEnvironmentVariable = (value: unknown, context: string): ModelEnvironmentVariable => {
    const result = requireResponseRecord(value, context)
    if (typeof result.name !== 'string' || !result.name.trim()) {
        throw new BoardResponseContractError(context, 'name is required')
    }
    if (typeof result.value !== 'string' || !result.value.trim()) {
        throw new BoardResponseContractError(context, 'value must be a non-blank string')
    }
    for (const field of ['trust', 'privacy']) {
        if (typeof result[field] !== 'string' || !result[field].trim()) {
            throw new BoardResponseContractError(context, `${field} is required`)
        }
    }
    if (!TRUST_VALUE_SET.has(result.trust.trim().toLowerCase())) {
        throw new BoardResponseContractError(context, 'trust must be trusted or untrusted')
    }
    if (!PRIVACY_VALUE_SET.has(result.privacy.trim().toLowerCase())) {
        throw new BoardResponseContractError(context, 'privacy must be public or private')
    }
    return result as ModelEnvironmentVariable
}

const validateBoardNodeResult = (value: unknown, context: string): DeviceNode => {
    const node = requireResponseRecord(value, context)
    for (const field of ['id', 'templateName', 'label']) {
        if (typeof node[field] !== 'string' || !node[field].trim()) {
            throw new BoardResponseContractError(context, `${field} is required`)
        }
    }
    const position = requireResponseRecord(node.position, `${context}.position`)
    for (const coordinate of ['x', 'y']) {
        if (typeof position[coordinate] !== 'number'
            || !Number.isFinite(position[coordinate])
            || Math.abs(position[coordinate]) > NODE_POSITION_ABS_MAX) {
            throw new BoardResponseContractError(context, `position.${coordinate} is outside the supported canvas range`)
        }
    }
    for (const [field, range] of [['width', NODE_WIDTH_RANGE], ['height', NODE_HEIGHT_RANGE]] as const) {
        if (!Number.isSafeInteger(node[field]) || node[field] < range.min || node[field] > range.max) {
            throw new BoardResponseContractError(context, `${field} is outside the supported canvas range`)
        }
    }
    if (node.state !== null && node.state !== undefined && typeof node.state !== 'string') {
        throw new BoardResponseContractError(context, 'state must be text or null')
    }
    for (const [field, allowed] of [
        ['currentStateTrust', TRUST_VALUE_SET],
        ['currentStatePrivacy', PRIVACY_VALUE_SET]
    ] as const) {
        const candidate = node[field]
        if (candidate !== null && candidate !== undefined
            && (typeof candidate !== 'string' || !allowed.has(candidate.trim().toLowerCase()))) {
            throw new BoardResponseContractError(context, `${field} is invalid`)
        }
    }
    for (const field of ['variables', 'privacies'] as const) {
        const collection = node[field]
        if (collection === null || collection === undefined) continue
        if (!Array.isArray(collection)) {
            throw new BoardResponseContractError(context, `${field} must be an array`)
        }
        const names = new Set<string>()
        collection.forEach((entry: unknown, index: number) => {
            const item = requireResponseRecord(entry, `${context}.${field}[${index}]`)
            if (typeof item.name !== 'string' || !item.name.trim() || names.has(item.name)) {
                throw new BoardResponseContractError(context, `${field}[${index}].name is missing or duplicated`)
            }
            names.add(item.name)
            if (field === 'variables') {
                if (typeof item.value !== 'string' || !item.value.trim()) {
                    throw new BoardResponseContractError(context, `${field}[${index}].value is required`)
                }
                if (item.trust !== null && item.trust !== undefined
                    && (typeof item.trust !== 'string'
                        || !TRUST_VALUE_SET.has(item.trust.trim().toLowerCase()))) {
                    throw new BoardResponseContractError(context, `${field}[${index}].trust is invalid`)
                }
            } else if (typeof item.privacy !== 'string'
                || !PRIVACY_VALUE_SET.has(item.privacy.trim().toLowerCase())) {
                throw new BoardResponseContractError(context, `${field}[${index}].privacy is invalid`)
            }
        })
    }
    // Validation must not silently rewrite an authoritative server snapshot. The
    // model keeps runtime collections optional, and callers already handle their
    // absence; preserving the payload also keeps reconciliation identity-stable.
    return node as unknown as DeviceNode
}

const validateBackendRuleResult = (value: unknown, context: string): BackendRuleDto => {
    const rule = requireResponseRecord(value, context)
    if (!Number.isSafeInteger(rule.id) || rule.id <= 0) {
        throw new BoardResponseContractError(context, 'id must be a positive integer')
    }
    const conditions = requireResponseArray(rule.conditions, `${context}.conditions`)
    if (conditions.length === 0) throw new BoardResponseContractError(context, 'conditions cannot be empty')
    conditions.forEach((candidate: unknown, index: number) => {
        const condition = requireResponseRecord(candidate, `${context}.conditions[${index}]`)
        for (const field of ['deviceName', 'attribute', 'targetType']) {
            if (typeof condition[field] !== 'string' || !condition[field].trim()) {
                throw new BoardResponseContractError(context, `conditions[${index}].${field} is required`)
            }
        }
        const sourceType = normalizeRuleSourceType(condition.targetType)
        if (!sourceType) throw new BoardResponseContractError(context, `conditions[${index}].targetType is invalid`)
        if (sourceType === 'api') {
            if (hasRuleConditionValue(condition.relation) || hasRuleConditionValue(condition.value)) {
                throw new BoardResponseContractError(context, `conditions[${index}] API signals cannot contain values`)
            }
        } else if (typeof condition.relation !== 'string'
            || !normalizeModelRelation(condition.relation)
            || typeof condition.value !== 'string'
            || !condition.value.trim()) {
            throw new BoardResponseContractError(context, `conditions[${index}] requires relation and value`)
        }
    })
    const command = requireResponseRecord(rule.command, `${context}.command`)
    for (const field of ['deviceName', 'action']) {
        if (typeof command[field] !== 'string' || !command[field].trim()) {
            throw new BoardResponseContractError(context, `command.${field} is required`)
        }
    }
    for (const field of ['contentDevice', 'content']) {
        if (command[field] !== null && command[field] !== undefined && typeof command[field] !== 'string') {
            throw new BoardResponseContractError(context, `command.${field} must be text or null`)
        }
    }
    if (hasRuleConditionValue(command.contentDevice) !== hasRuleConditionValue(command.content)) {
        throw new BoardResponseContractError(context, 'command content fields must be provided together')
    }
    if (rule.ruleString !== null && rule.ruleString !== undefined && typeof rule.ruleString !== 'string') {
        throw new BoardResponseContractError(context, 'ruleString must be text or null')
    }
    return rule as unknown as BackendRuleDto
}

const validateBoardSpecificationResult = (value: unknown, context: string): Specification => {
    const specification = requireResponseRecord(value, context)
    if (typeof specification.id !== 'string' || !specification.id.trim()) {
        throw new BoardResponseContractError(context, 'id is required')
    }
    if (typeof specification.templateId !== 'string' || !/^[1-7]$/.test(specification.templateId)) {
        throw new BoardResponseContractError(context, 'templateId is invalid')
    }
    if (typeof specification.templateLabel !== 'string') {
        throw new BoardResponseContractError(context, 'templateLabel must be text')
    }
    if (specification.formula !== null && specification.formula !== undefined
        && typeof specification.formula !== 'string') {
        throw new BoardResponseContractError(context, 'formula must be text or null')
    }
    for (const field of ['aConditions', 'ifConditions', 'thenConditions']) {
        const conditions = requireResponseArray(specification[field], `${context}.${field}`)
        conditions.forEach((candidate: unknown, index: number) => {
            const condition = requireResponseRecord(candidate, `${context}.${field}[${index}]`)
            for (const required of ['deviceId', 'targetType', 'key', 'relation', 'value']) {
                if (typeof condition[required] !== 'string' || !condition[required].trim()) {
                    throw new BoardResponseContractError(context, `${field}[${index}].${required} is required`)
                }
            }
            if (!['state', 'mode', 'variable', 'api', 'trust', 'privacy'].includes(condition.targetType)) {
                throw new BoardResponseContractError(context, `${field}[${index}].targetType is invalid`)
            }
            // Deliberately NOT rejected when absent. A specification stored before this field existed has
            // no source, and that is a state the user can act on: the list badges it as unresolved, the
            // editor asks for a choice, and the run gate blocks with a reason. Throwing here made the whole
            // specifications collection fail to load, so the user got a permanent red banner and a Retry
            // that could never succeed — the unresolved path was unreachable for exactly the data it
            // existed for. A *present but unrecognised* value is still a contract violation: the server
            // normalizes to one of two literals, so anything else means the payload is not ours.
            if (condition.targetType === 'variable'
                && condition.variableSource !== null && condition.variableSource !== undefined
                && condition.variableSource !== 'environment' && condition.variableSource !== 'reported') {
                throw new BoardResponseContractError(context, `${field}[${index}].variableSource is invalid`)
            }
            if (!SPEC_RELATIONS.has(normalizeModelRelation(condition.relation) || '')) {
                throw new BoardResponseContractError(context, `${field}[${index}].relation is invalid`)
            }
            if (condition.side !== null && condition.side !== undefined
                && !['a', 'if', 'then'].includes(condition.side)) {
                throw new BoardResponseContractError(context, `${field}[${index}].side is invalid`)
            }
            if (['trust', 'privacy'].includes(condition.targetType)
                && !['state', 'variable'].includes(condition.propertyScope)) {
                throw new BoardResponseContractError(context, `${field}[${index}].propertyScope is required`)
            }
            if (!['trust', 'privacy'].includes(condition.targetType)
                && condition.propertyScope !== null && condition.propertyScope !== undefined) {
                throw new BoardResponseContractError(context, `${field}[${index}].propertyScope is unexpected`)
            }
        })
    }
    const devices = requireResponseArray(specification.devices, `${context}.devices`)
    devices.forEach((candidate: unknown, index: number) => {
        const device = requireResponseRecord(candidate, `${context}.devices[${index}]`)
        if (typeof device.deviceId !== 'string' || !device.deviceId.trim()
            || !Array.isArray(device.selectedApis)
            || device.selectedApis.some((name: unknown) => typeof name !== 'string')) {
            throw new BoardResponseContractError(context, `devices[${index}] is malformed`)
        }
    })
    return specification as unknown as Specification
}

const validateDefaultTemplateResetResult = (
    value: unknown,
    expectedOperation: DefaultTemplateResetResult['operation'],
    expectedImpactToken?: string
): DefaultTemplateResetResult => {
    const context = expectedOperation === 'preview'
        ? 'Default device type reset preview'
        : 'Default device type reset'
    const result = requireResponseRecord(value, context)
    requireOperation(result, expectedOperation, context)
    if (typeof result.impactToken !== 'string' || !result.impactToken.trim()) {
        throw new BoardResponseContractError(context, 'impactToken is required')
    }
    if (expectedOperation === 'reset'
        && result.impactToken.trim() !== String(expectedImpactToken || '').trim()) {
        throw new BoardResponseContractError(context, 'impactToken does not match the confirmed preview')
    }
    if (typeof result.canApply !== 'boolean') {
        throw new BoardResponseContractError(context, 'canApply must be boolean')
    }
    if (!Number.isSafeInteger(result.editHistoryEntryCount) || result.editHistoryEntryCount < 0) {
        throw new BoardResponseContractError(context, 'editHistoryEntryCount must be a non-negative integer')
    }
    const templateChanges = requireResponseArray<any>(result, context, 'templateChanges')
    const affectedDevices = requireResponseArray<any>(result, context, 'affectedDevices')
    const blockers = requireResponseArray<any>(result, context, 'blockers')
    const environmentChanges = requireResponseArray<EnvironmentVariableChange>(result, context, 'environmentChanges')
        .map((change, index) => validateEnvironmentChangeResult(
            change,
            `${context}.environmentChanges[${index}]`
        ))
    const currentTemplates = requireResponseArray<DeviceTemplate>(result, context, 'currentTemplates')
    const environmentVariables = requireResponseArray<ModelEnvironmentVariable>(result, context, 'environmentVariables')
    if (templateChanges.length === 0) {
        throw new BoardResponseContractError(context, 'templateChanges must describe the bundled defaults')
    }

    const allowedChangeTypes = new Set<DefaultTemplateResetChangeType>([
        'RESTORE_MISSING',
        'REFRESH_DEFAULT',
        'REPLACE_CUSTOM_NAME_COLLISION',
        'REMOVE_OBSOLETE_DEFAULT'
    ])
    const changedNames = new Set<string>()
    templateChanges.forEach((change, index) => {
        const row = requireResponseRecord(change, context)
        if (typeof row.templateName !== 'string' || !row.templateName.trim()) {
            throw new BoardResponseContractError(context, `templateChanges[${index}].templateName is required`)
        }
        const key = row.templateName.trim().toLocaleLowerCase()
        if (changedNames.has(key)) {
            throw new BoardResponseContractError(context, 'templateChanges contains duplicate names')
        }
        changedNames.add(key)
        if (!allowedChangeTypes.has(row.changeType)) {
            throw new BoardResponseContractError(context, `templateChanges[${index}].changeType is invalid`)
        }
        if (typeof row.semanticsChanged !== 'boolean') {
            throw new BoardResponseContractError(context, `templateChanges[${index}].semanticsChanged must be boolean`)
        }
    })
    affectedDevices.forEach((device, index) => {
        const row = requireResponseRecord(device, context)
        for (const field of ['deviceId', 'deviceLabel', 'templateName']) {
            if (typeof row[field] !== 'string' || !row[field].trim()) {
                throw new BoardResponseContractError(context, `affectedDevices[${index}].${field} is required`)
            }
        }
    })
    blockers.forEach((blocker, index) => {
        const row = requireResponseRecord(blocker, context)
        for (const field of ['itemLabel', 'reasonCode', 'reason']) {
            if (typeof row[field] !== 'string' || !row[field].trim()) {
                throw new BoardResponseContractError(context, `blockers[${index}].${field} is required`)
            }
        }
        if (![
            'DEVICE_INSTANCE_INCOMPATIBLE',
            'AUTOMATION_RULE_INCOMPATIBLE',
            'SPECIFICATION_INCOMPATIBLE',
            'ENVIRONMENT_POOL_INCOMPATIBLE',
            'BOARD_MODEL_INCOMPATIBLE'
        ].includes(row.reasonCode)) {
            throw new BoardResponseContractError(context, `blockers[${index}].reasonCode is invalid`)
        }
    })
    if (result.canApply !== (blockers.length === 0)) {
        throw new BoardResponseContractError(context, 'canApply must match blockers')
    }
    currentTemplates.forEach((template, index) =>
        validateDeviceTemplateResult(template, `${context} currentTemplates[${index}]`))
    environmentVariables.forEach((variable, index) =>
        validateEnvironmentVariable(variable, `${context} environmentVariables[${index}]`))
    requireUniqueIdentities(
        currentTemplates,
        template => template.name.trim().toLowerCase(),
        context,
        'currentTemplates'
    )
    requireUniqueIdentities(currentTemplates, template => Number(template.id), context, 'currentTemplates')
    requireUniqueIdentities(environmentVariables, variable => variable.name, context, 'environmentVariables')

    const environmentByName = new Map(environmentVariables.map(variable => [variable.name, variable]))
    if (new Set(environmentChanges.map(change => change.name)).size !== environmentChanges.length) {
        throw new BoardResponseContractError(context, 'environmentChanges contains duplicate names')
    }
    environmentChanges.forEach((row) => {
        if (expectedOperation === 'reset') {
            const current = environmentByName.get(row.name)
            if (row.changeType === 'REMOVED' ? current !== undefined : current === undefined) {
                throw new BoardResponseContractError(context, 'environmentChanges contradicts environmentVariables')
            }
        }
    })

    if (expectedOperation === 'reset') {
        if (!result.canApply || blockers.length !== 0) {
            throw new BoardResponseContractError(context, 'a committed reset cannot contain blockers')
        }
        const finalNames = new Set(currentTemplates.map(template => template.name.trim().toLocaleLowerCase()))
        for (const change of templateChanges) {
            const key = change.templateName.trim().toLocaleLowerCase()
            const shouldExist = change.changeType !== 'REMOVE_OBSOLETE_DEFAULT'
            if (finalNames.has(key) !== shouldExist) {
                throw new BoardResponseContractError(context, 'templateChanges contradicts currentTemplates')
            }
        }
    }
    return result as DefaultTemplateResetResult
}

/** A suggestion as the user reviewed it; the token authorizes it but is not part of what they chose. */
const visibleSuggestionKey = (suggestion: FixSuggestion): string => {
    const { suggestionToken: _token, ...visible } = suggestion
    return JSON.stringify(visible)
}

const validateFixApplyResult = (
    value: unknown,
    submitted: FixSuggestion
): Omit<FixApplyResult, 'rules'> & { rules: BackendRuleDto[] } => {
    const context = 'Automatic fix apply'
    const expectedStrategy = submitted.strategy
    const result = requireResponseRecord(value, context)
    if (result.applied !== true || result.verificationEvidenceReused !== true) {
        throw new BoardResponseContractError(
            context,
            'the response must confirm that verification evidence backed the applied suggestion'
        )
    }
    if (result.strategy !== expectedStrategy) {
        throw new BoardResponseContractError(context, 'strategy does not match the requested strategy')
    }
    // A strategy can list several alternatives, so a well-formed suggestion is not enough: it must be
    // the one the user picked. Both sides are the server's own serialization of the same DTO.
    const applied = validateFixSuggestion(result.appliedSuggestion, context, expectedStrategy)
    if (visibleSuggestionKey(applied) !== visibleSuggestionKey(submitted)) {
        throw new BoardResponseContractError(context, 'appliedSuggestion is not the suggestion that was submitted')
    }
    const rules = requireResponseArray<BackendRuleDto>(result, context, 'rules')
        .map((rule, index) => validateBackendRuleResult(rule, `${context}.rules[${index}]`))
    requireUniqueIdentities(rules, rule => Number(rule.id), context, 'rules')
    if (!Number.isSafeInteger(result.previousRuleCount) || result.previousRuleCount < 0
        || !Number.isSafeInteger(result.currentRuleCount)
        || result.currentRuleCount !== rules.length) {
        throw new BoardResponseContractError(context, 'rule counts must match the authoritative rules snapshot')
    }
    if ((expectedStrategy === 'remove' && result.currentRuleCount >= result.previousRuleCount)
        || (expectedStrategy !== 'remove' && result.currentRuleCount !== result.previousRuleCount)) {
        throw new BoardResponseContractError(context, 'rule counts contradict the applied strategy')
    }
    if (typeof result.message !== 'string' || !result.message.trim()) {
        throw new BoardResponseContractError(context, 'message is required')
    }
    if (result.canUndo !== true || result.canRedo !== false) {
        throw new BoardResponseContractError(
            context,
            'an applied automatic fix must be undoable and must clear redo history'
        )
    }
    return { ...result, rules } as Omit<FixApplyResult, 'rules'> & { rules: BackendRuleDto[] }
}

export default {
    // ==== 节点 ====
    getSnapshot: async (): Promise<BoardSemanticSnapshot> => {
        const context = 'Board semantic snapshot'
        const snapshot = requireResponseRecord(
            unpack<unknown>(await api.get('/board/snapshot')),
            context
        )
        const rawNodes = requireResponseArray<DeviceNode>(snapshot.nodes, `${context}.nodes`)
            .map((node, index) => validateBoardNodeResult(node, `${context}.nodes[${index}]`))
        const rawRules = requireResponseArray<BackendRuleDto>(snapshot.rules, `${context}.rules`)
            .map((rule, index) => validateBackendRuleResult(rule, `${context}.rules[${index}]`))
        const rawSpecifications = requireResponseArray<Specification>(
            snapshot.specifications,
            `${context}.specifications`
        ).map((spec, index) => validateBoardSpecificationResult(spec, `${context}.specifications[${index}]`))
        const environmentVariables = requireResponseArray<ModelEnvironmentVariable>(
            snapshot.environmentVariables,
            `${context}.environmentVariables`
        ).map((variable, index) =>
            validateEnvironmentVariable(variable, `${context}.environmentVariables[${index}]`))
        const deviceTemplates = requireResponseArray<DeviceTemplate>(
            snapshot.deviceTemplates,
            `${context}.deviceTemplates`
        ).map((template, index) =>
            validateDeviceTemplateResult(template, `${context}.deviceTemplates[${index}]`))
        requireUniqueIdentities(rawNodes, node => node.id, context, 'nodes')
        requireUniqueIdentities(environmentVariables, variable => variable.name, context, 'environmentVariables')
        requireUniqueIdentities(rawRules, rule => Number(rule.id), context, 'rules')
        requireUniqueIdentities(rawSpecifications, specification => specification.id, context, 'specifications')
        requireUniqueIdentities(
            deviceTemplates,
            template => template.name.trim().toLowerCase(),
            context,
            'deviceTemplates'
        )
        requireUniqueIdentities(deviceTemplates, template => Number(template.id), context, 'deviceTemplates')
        return {
            nodes: rawNodes,
            environmentVariables,
            rules: rawRules.map(fromBackendRuleDto),
            specifications: rawSpecifications,
            deviceTemplates
        }
    },
    getNodes: async (): Promise<DeviceNode[]> => {
        const nodes = requireResponseArray<DeviceNode>(
            unpack<unknown>(await api.get('/board/nodes')),
            'Device list'
        ).map((node, index) => validateBoardNodeResult(node, `Device list[${index}]`));
        requireUniqueIdentities(nodes, node => node.id, 'Device list', 'nodes')
        return nodes
    },
    addNodes: async (
        devices: DeviceNode[],
        environmentVariablePatches: ModelEnvironmentVariable[] = []
    ): Promise<CommittedDeviceMutationResult> => {
        const result = unpack<unknown>(await api.post('/board/nodes', {
            devices,
            environmentVariablePatches
        }));
        return validateDeviceMutationResult(
            result,
            'created',
            devices.map(device => device.id),
            'Device creation'
        ) as CommittedDeviceMutationResult
    },
    updateNodeLayout: async (nodeId: string, layout: DeviceLayout): Promise<DeviceUpdateResult> => {
        return validateDeviceUpdateResult(
            unpack<unknown>(await api.put(
                `/board/nodes/${encodeURIComponent(nodeId)}/layout`,
                layout
            )),
            'layout',
            nodeId,
            layout
        )
    },
    updateNodeRuntime: async (nodeId: string, runtime: DeviceRuntimeUpdate): Promise<DeviceUpdateResult> => {
        return validateDeviceUpdateResult(
            unpack<unknown>(await api.put(
                `/board/nodes/${encodeURIComponent(nodeId)}/runtime`,
                runtime
            )),
            'runtime',
            nodeId,
            runtime
        )
    },
    renameNode: async (
        nodeId: string,
        label: string,
        expectedLabel: string
    ): Promise<DeviceMutationResult> => {
        const result = unpack<unknown>(
            await api.patch(`/board/nodes/${encodeURIComponent(nodeId)}/label`, {
                label,
                expectedLabel
            })
        );
        return validateDeviceRenameResult(result, nodeId, label, expectedLabel)
    },
    previewNodeDeletion: async (nodeId: string): Promise<DeviceDeletionResult> => {
        const result = validateDeviceDeletionResult(
            unpack<unknown>(await api.get(`/board/nodes/${encodeURIComponent(nodeId)}/deletion-preview`)),
            'preview',
            nodeId,
            'Device deletion preview'
        ) as Omit<DeviceDeletionResult, 'removedRules' | 'currentRules'> & {
            removedRules: BackendRuleDto[];
            currentRules: BackendRuleDto[];
        };
        return {
            ...result,
            removedRules: result.removedRules.map(fromBackendRuleDto),
            currentRules: result.currentRules.map(fromBackendRuleDto)
        };
    },
    deleteNode: async (
        nodeId: string,
        impactToken: string
    ): Promise<CommittedDeviceDeletionResult> => {
        const result = validateDeviceDeletionResult(
            unpack<unknown>(await api.post(`/board/nodes/${encodeURIComponent(nodeId)}/delete`, {
                impactToken
            })),
            'deleted',
            nodeId,
            'Device deletion',
            impactToken
        ) as Omit<DeviceDeletionResult, 'removedRules' | 'currentRules'> & {
            removedRules: BackendRuleDto[];
            currentRules: BackendRuleDto[];
        };
        return {
            ...result,
            removedRules: result.removedRules.map(fromBackendRuleDto),
            currentRules: result.currentRules.map(fromBackendRuleDto)
        } as CommittedDeviceDeletionResult;
    },

    // ==== 环境变量池 ====
    getEnvironment: async (): Promise<ModelEnvironmentVariable[]> => {
        const variables = requireResponseArray<ModelEnvironmentVariable>(
            unpack<unknown>(await api.get('/board/environment')),
            'Environment Pool'
        ).map((variable, index) => validateEnvironmentVariable(
            variable,
            `Environment Pool environmentVariables[${index}]`
        ));
        requireUniqueIdentities(variables, variable => variable.name, 'Environment Pool', 'environmentVariables')
        return variables
    },
    saveEnvironment: async (variables: EnvironmentVariableUpdateRequest[]): Promise<EnvironmentMutationResult> => {
        const validated = validateEnvironmentUpdateRequests(variables)
        return validateEnvironmentMutationResult(
            unpack<unknown>(await api.post('/board/environment', validated)),
            validated
        );
    },

    // ==== 规约 ====
    getSpecs: async (): Promise<Specification[]> => {
        const specifications = requireResponseArray<Specification>(
            unpack<unknown>(await api.get('/board/specs')),
            'Specification list'
        )
            .map((spec, index) => validateBoardSpecificationResult(spec, `Specification list[${index}]`));
        requireUniqueIdentities(specifications, specification => specification.id, 'Specification list', 'specifications')
        return specifications
    },
    addSpec: async (spec: Specification): Promise<CollectionMutationResult<Specification>> => {
        return validateCollectionMutationResult<Specification>(
            unpack<unknown>(await api.post('/board/specs', toBackendSpecificationWriteDto(spec))),
            'created',
            'Specification creation',
            validateBoardSpecificationResult,
            specification => specification.id,
            spec.id
        );
    },
    removeSpec: async (spec: Specification): Promise<CollectionMutationResult<Specification>> => {
        const specId = String(spec.id || '').trim()
        if (!specId) throw new Error('Persisted specification id is required for deletion')
        return validateCollectionMutationResult<Specification>(
            unpack<unknown>(await api.delete(`/board/specs/${encodeURIComponent(specId)}`, {
                data: toBackendSpecificationWriteDto(spec)
            })),
            'deleted',
            'Specification deletion',
            validateBoardSpecificationResult,
            specification => specification.id,
            specId
        );
    },

    // ==== 规则（sources -> target） ====
    getRules: async (): Promise<RuleForm[]> => {
        const data = requireResponseArray<BackendRuleDto>(
            unpack<unknown>(await api.get('/board/rules')),
            'Rule list'
        );
        const rules = data.map((rule, index) =>
            validateBackendRuleResult(rule, `Rule list[${index}]`))
        requireUniqueIdentities(rules, rule => Number(rule.id), 'Rule list', 'rules')
        return rules.map(fromBackendRuleDto)
    },
    addRule: async (rule: RuleForm): Promise<CollectionMutationResult<RuleForm>> => {
        assertRuleHasTrigger(rule)

        const result = validateCollectionMutationResult<BackendRuleDto>(
            unpack<unknown>(await api.post('/board/rules', toBackendRuleDto(rule))),
            'created',
            'Rule creation',
            validateBackendRuleResult,
            rule => Number(rule.id)
        );
        return {
            ...result,
            affectedItem: fromBackendRuleDto(result.affectedItem),
            currentItems: result.currentItems.map(fromBackendRuleDto)
        };
    },
    /**
     * Persists a new rule execution order. Reversible, so the result carries undo availability
     * alongside the authoritative ordering.
     */
    reorderRules: async (
        expectedRuleIds: string[],
        ruleIds: string[]
    ): Promise<{ rules: RuleForm[]; canUndo: true; canRedo: false }> => {
        const toPersistedIds = (ids: string[], field: string) => ids.map(ruleId => {
            const numericId = Number(ruleId)
            if (!Number.isSafeInteger(numericId) || numericId <= 0) {
                throw new Error(`Every ${field} rule must have a persisted id before execution order can be changed`)
            }
            return numericId
        })
        const expectedPersistedIds = toPersistedIds(expectedRuleIds, 'expected')
        const persistedIds = toPersistedIds(ruleIds, 'requested')
        // Reorder is a collection-level edit, so the envelope carries no `affectedItem`; it is
        // validated here rather than through `validateCollectionMutationResult`.
        const envelope = requireResponseRecord(
            unpack<unknown>(await api.put('/board/rules/order', {
                expectedRuleIds: expectedPersistedIds,
                ruleIds: persistedIds
            })),
            'Rule reorder'
        )
        requireOperation(envelope, 'reordered', 'Rule reorder')
        if (envelope.affectedItem !== null) {
            throw new BoardResponseContractError('Rule reorder', 'affectedItem must be null')
        }
        const result = requireResponseArray<BackendRuleDto>(envelope, 'Rule reorder', 'currentItems')
            .map((rule, index) => validateBackendRuleResult(rule, `Rule reorder[${index}]`))
        if (result.length !== persistedIds.length
            || result.some((rule, index) => rule.id !== persistedIds[index])) {
            throw new BoardResponseContractError(
                'Rule reorder',
                'the authoritative order must match the requested rule ids'
            )
        }
        requireCurrentCount(envelope, result, 'Rule reorder')
        if (envelope.canUndo !== true || envelope.canRedo !== false) {
            throw new BoardResponseContractError(
                'Rule reorder',
                'a committed reorder must be undoable and must clear redo history'
            )
        }
        return {
            rules: result.map(fromBackendRuleDto),
            canUndo: envelope.canUndo,
            canRedo: envelope.canRedo
        }
    },
    removeRule: async (rule: RuleForm): Promise<CollectionMutationResult<RuleForm>> => {
        const ruleId = String(rule.id || '').trim()
        const numericId = Number(ruleId);
        if (!Number.isSafeInteger(numericId) || numericId <= 0) {
            throw new Error('Persisted rule id is required for deletion');
        }
        const expected = toBackendRuleDto(rule)
        const result = validateCollectionMutationResult<BackendRuleDto>(
            unpack<unknown>(await api.delete(`/board/rules/${numericId}`, { data: expected })),
            'deleted',
            'Rule deletion',
            validateBackendRuleResult,
            rule => Number(rule.id),
            numericId
        );
        return {
            ...result,
            affectedItem: fromBackendRuleDto(result.affectedItem),
            currentItems: result.currentItems.map(fromBackendRuleDto)
        };
    },

    /**
     * Reverses the newest reversible board edit, or re-applies the newest undone one.
     *
     * The server journal is the authority for what is reversible and for the resulting
     * availability, so nothing about local history is sent. `applied: false` means there was
     * nothing left in that direction — an ordinary outcome that makes repeated calls idempotent.
     */
    /**
     * Current undo availability, with no side effects.
     *
     * Read on board load so the affordance is restored from server state — undo history survives a
     * reload, a second tab, and a different device, so it must not be inferred from local actions.
     * Returns availability only; all semantic collections are empty because this is a query, not
     * an update, and callers must not apply them.
     */
    getBoardEditAvailability: async (): Promise<BoardUndoAvailability> => {
        const result = parseBoardUndoResult(
            unpack<unknown>(await api.get('/board/edits/availability')), 'availability')
        return { canUndo: result.canUndo, canRedo: result.canRedo }
    },

    applyBoardEditUndo: async (direction: 'undo' | 'redo'): Promise<BoardUndoResult> =>
        parseBoardUndoResult(
            unpack<unknown>(await api.post(`/board/edits/${direction}`)), direction),

    previewBoardEditHistoryClear: async (): Promise<BoardEditHistoryClearPreview> =>
        validateBoardEditHistoryClearPreview(
            unpack<unknown>(await api.get('/board/edits/clear-preview'))),

    clearBoardEditHistory: async (impactToken: string): Promise<BoardUndoAvailability> => {
        const result = parseBoardUndoResult(
            unpack<unknown>(await api.post('/board/edits/clear', { impactToken })), 'clear')
        return { canUndo: result.canUndo, canRedo: result.canRedo }
    },

    /** Returns the authoritative current-board impact that the user must confirm. */
    previewBoardReplacement: async (): Promise<BoardReplacementPreview> => {
        return validateBoardReplacementPreview(
            unpack<unknown>(await api.get('/board/replacement-preview'))
        )
    },

    /**
     * Imports a portable scene, atomically replacing the whole board.
     *
     * The validated file is sent verbatim: the server owns the portable → internal mapping, so this
     * client does not restate how portable rules and specifications become write DTOs. That mapping
     * used to be duplicated here and in the backend's chat-apply path, and the two could disagree on
     * any field — the same dropped field failed three times before both copies agreed.
     */
    importScene: async (request: {
        impactToken: string,
        scene: PortableSceneFile
    }): Promise<BoardSceneReplacementResult> => {
        if (!request.impactToken.trim()) {
            throw new Error('Scene import requires a confirmed impact token')
        }
        const saved = validateBoardBatchResult(
            unpack<unknown>(await api.post('/board/scene', {
                impactToken: request.impactToken,
                scene: request.scene
            }))
        );
        return {
            ...saved,
            rules: saved.rules.map(fromBackendRuleDto)
        };
    },

    /**
     * Clears the board atomically: every semantic collection is replaced with an empty one.
     *
     * Separate from {@link importScene} because a clear has no portable file behind it — it commits
     * empty internal collections directly, so routing it through the scene format would mean
     * inventing an empty scene document for a command that never had one.
     */
    clearBoardScene: async (impactToken: string): Promise<BoardSceneReplacementResult> => {
        if (!impactToken.trim()) {
            throw new Error('Scene clear requires a confirmed impact token')
        }
        const saved = validateBoardBatchResult(
            unpack<unknown>(await api.post('/board/batch', {
                impactToken,
                nodes: [],
                environmentVariables: [],
                rules: [],
                specs: [],
                templateSnapshots: []
            }))
        );
        return {
            ...saved,
            rules: saved.rules.map(fromBackendRuleDto)
        };
    },

    /**
     * Deterministic duplicate check used before saving a rule.
     */
    checkDuplicateRule: async (rule: RuleForm, signal?: AbortSignal): Promise<DuplicateRuleCheckResult> => {
        assertRuleHasTrigger(rule)

        const dto = toBackendRuleDto(rule);

        const response = await api.post<any>('/board/rules/check-duplicate', dto, { signal });
        return validateDuplicateRuleCheckResult(unpack<unknown>(response));
    },

    /**
     * Explicit AI semantic similarity check. This may call the configured external LLM.
     */
    checkRuleSimilarity: async (rule: RuleForm, signal?: AbortSignal): Promise<RuleSimilarityResult> => {
        assertRuleHasTrigger(rule)

        const dto = toBackendRuleDto(rule);

        const response = await api.post<any>('/board/rules/check-similarity', dto, { signal });
        return validateRuleSimilarityResult(unpack<unknown>(response));
    },

    // ==== 布局（包含面板状态、Canvas 缩放位移） ====
    getLayout: async (): Promise<BoardLayoutDto> => {
        return unpack<BoardLayoutDto>(await api.get('/board/layout'));
    },
    saveLayout: async (dto: BoardLayoutDto): Promise<BoardLayoutDto> => {
        return unpack<BoardLayoutDto>(await api.post('/board/layout', dto));
    },

    // ==== 设备模板 ====
    getDeviceTemplates: async (): Promise<DeviceTemplate[]> => {
        const templates = requireResponseArray<DeviceTemplate>(
            unpack<unknown>(await api.get('/board/templates')),
            'Device type catalog'
        );
        const validated = templates.map((template, index) =>
            validateDeviceTemplateResult(template, `Device type catalog[${index}]`));
        requireUniqueIdentities(
            validated,
            template => template.name.trim().toLowerCase(),
            'Device type catalog',
            'templates'
        )
        requireUniqueIdentities(validated, template => Number(template.id), 'Device type catalog', 'templates')
        return validated
    },
    getDeviceTemplateSchema: async (): Promise<Record<string, unknown>> => {
        return unpack<Record<string, unknown>>(await api.get('/board/templates/schema'));
    },
    addDeviceTemplate: async (tpl: DeviceTemplate): Promise<DeviceTemplate> => {
        return validateDeviceTemplateResult(
            unpack<unknown>(await api.post('/board/templates', tpl)),
            'Device type import'
        );
    },
    previewDefaultTemplateReset: async (): Promise<DefaultTemplateResetResult> => {
        return validateDefaultTemplateResetResult(
            unpack<unknown>(await api.get('/board/templates/defaults/reset-preview')),
            'preview'
        );
    },
    resetDefaultTemplates: async (impactToken: string): Promise<DefaultTemplateResetResult> => {
        return validateDefaultTemplateResetResult(
            unpack<unknown>(await api.post('/board/templates/defaults/reset', { impactToken })),
            'reset',
            impactToken
        );
    },
    previewDeviceTemplateDeletion: async (id: number): Promise<DeviceTemplateDeletionResult> => {
        return parseDeviceTemplateDeletionPreview(
            unpack<unknown>(await api.get(`/board/templates/${id}/deletion-preview`)),
            id
        );
    },
    deleteDeviceTemplate: async (id: number, impactToken: string): Promise<DeviceTemplateDeletionResult> => {
        return validateDeviceTemplateDeletionResult(
            unpack<unknown>(await api.post(`/board/templates/${id}/delete`, { impactToken })),
            'deleted',
            id,
            impactToken
        );
    },

    // ==== 验证 ====
    verify: async (req: VerificationRequest): Promise<VerificationResult> => {
        return validateVerificationResult(
            unpack<unknown>(await api.post('/verify', req, SERVER_BOUNDED_REQUEST))
        );
    },
    getTask: async (taskId: number): Promise<VerificationTask> => {
        return validateVerificationTask(
            unpack<unknown>(await api.get(`/verify/tasks/${taskId}`))
        );
    },
    getTasks: async (excludeTaskIds: number[] = []): Promise<VerificationTaskSummary[]> => {
        const params = excludeTaskIds.length > 0
            ? { excludeTaskIds: excludeTaskIds.join(',') }
            : undefined;
        return validateVerificationTaskSummaryList(
            unpack<unknown>(await api.get('/verify/tasks', { params }))
        );
    },
    deleteTask: async (taskId: number): Promise<void> => {
        return unpack<void>(await api.delete(`/verify/tasks/${taskId}`));
    },
    getVerificationRuns: async (): Promise<VerificationRunSummary[]> => {
        return validateVerificationRunSummaryList(
            unpack<unknown>(await api.get('/verify/runs'))
        );
    },
    getVerificationRun: async (runId: number): Promise<VerificationRun> => {
        return validateVerificationRun(
            unpack<unknown>(await api.get(`/verify/runs/${runId}`))
        );
    },
    getVerificationRunTraces: async (runId: number): Promise<PersistedTrace[]> => {
        return validateVerificationTraceList(
            unpack<unknown>(await api.get(`/verify/runs/${runId}/traces`)),
            runId
        );
    },
    deleteVerificationRun: async (runId: number): Promise<void> => {
        return unpack<void>(await api.delete(`/verify/runs/${runId}`));
    },
    getTaskProgress: async (taskId: number): Promise<number> => {
        return validateTaskProgress(
            unpack<unknown>(await api.get(`/verify/tasks/${taskId}/progress`)),
            'Verification task progress'
        );
    },
    cancelTask: async (taskId: number): Promise<TaskCancellationResult> => {
        return validateTaskCancellationResult(
            unpack<unknown>(await api.post(`/verify/tasks/${taskId}/cancel`)),
            taskId,
            'Verification task cancellation'
        );
    },

    // ==== 验证 Trace（反例） ====
    /*
     * No `getVerificationTraces` (`GET /verify/traces`) and no `deleteVerificationTrace`
     * (`DELETE /verify/traces/{id}`) client method.
     *
     * Both endpoints are alive and exercised — the unfiltered list by ten E2E call sites, the delete by
     * the assistant's `DeleteTraceTool` — but no client code called either method. The UI never wants
     * every counterexample the account has ever produced: it loads them per run
     * (`getVerificationRunTraces`) or per task (`getTaskTraces`), because a counterexample is only
     * meaningful under the run whose model produced it. Deleting one is likewise a run-level action
     * (`deleteVerificationRun` removes a run and its evidence together).
     *
     * Restoring either method would mean re-answering that question, not just re-adding a wrapper.
     */
    // 获取某个验证任务产生的反例 Trace（按 task 维度过滤，避免拿到旧任务/并发任务的反例）
    getTaskTraces: async (taskId: number): Promise<PersistedTrace[]> => {
        return validateVerificationTraceList(
            unpack<unknown>(await api.get(`/verify/tasks/${taskId}/traces`)),
            taskId
        );
    },
    // 获取单个 Trace
    getVerificationTrace: async (id: number): Promise<PersistedTrace> => {
        return validateVerificationTrace(
            unpack<unknown>(await api.get(`/verify/traces/${id}`))
        );
    },

    /*
     * There is deliberately no trace-keyed SMV download here.
     *
     * One model is generated per *run*, so a trace-keyed endpoint could only ever return a
     * byte-identical copy of what the run-keyed download below returns — and the run key is also the
     * only one reachable for a run where every specification held, which has no counterexample to
     * address it by. `GET /api/verify/traces/{id}/smv` has been removed server-side for that reason
     * (see `VerificationController`); do not reintroduce a client for it.
     */

    /**
     * 下载整次验证运行的 SMV 模型文件
     *
     * Keyed on the run, not a counterexample: one model per run, and a run where every specification
     * holds has no counterexample to address it by.
     */
    downloadRunSmvModel: async (runId: number): Promise<void> => {
        const response = await api.get(`/verify/runs/${runId}/smv`, {
            responseType: 'blob',
            headers: { 'Accept': 'text/plain' }
        });
        saveBlobResponseAsFile(response, `verification-run-${runId}.smv`);
    },

    // ==== 异步验证 ====
    // 发起异步验证并接收服务端真实任务快照
    verifyAsync: async (req: VerificationRequest): Promise<VerificationTask> => {
        return validateVerificationTask(
            unpack<unknown>(await api.post('/verify/async', req))
        );
    },

    // ==== 设备推荐 ====
    recommendRelatedDevices: async (
        options: OwnedRecommendationPostOptions,
        maxRecommendations: number = 5,
        language: string = 'en',
        userRequirement: string = ''
    ): Promise<DeviceRecommendationResponse<DeviceRecommendation>> => {
        const requestId = options.requestId || crypto.randomUUID()
        const response = await api.post(`/board/devices/recommend?requestId=${encodeURIComponent(requestId)}`, {
            maxRecommendations,
            language,
            userRequirement
        }, {
            signal: options.signal,
            ...SERVER_BOUNDED_REQUEST,
            headers: { Authorization: `Bearer ${options.authToken}` }
        })
        try {
            return validateStandaloneRecommendationResponse<DeviceRecommendationResponse<DeviceRecommendation>>(
                unpack<unknown>(response),
                'Device recommendation',
                validateDeviceRecommendationCandidate,
                true
            );
        } catch (error) {
            throw markRecommendationResponseReceived(error)
        }
    },

    // ==== 规约推荐 ====
    recommendSpecifications: async (
        options: OwnedRecommendationPostOptions,
        maxRecommendations: number = 5,
        language: string = 'en',
        userRequirement: string = ''
    ): Promise<RecommendationResponse<SpecificationRecommendation>> => {
        const requestId = options.requestId || crypto.randomUUID()
        const response = await api.post('/board/specs/recommend', {
            maxRecommendations, language, userRequirement, requestId
        }, {
            signal: options.signal,
            ...SERVER_BOUNDED_REQUEST,
            headers: { Authorization: `Bearer ${options.authToken}` }
        })
        try {
            return validateStandaloneRecommendationResponse<RecommendationResponse<SpecificationRecommendation>>(
                unpack<unknown>(response),
                'Specification recommendation',
                validateSpecificationRecommendationCandidate
            );
        } catch (error) {
            throw markRecommendationResponseReceived(error)
        }
    },

    // ==== 可导入、未验证的场景草案推荐 ====
    recommendScenario: async (
        request: ScenarioRecommendationRequest,
        options: OwnedRecommendationPostOptions
    ): Promise<ScenarioRecommendationResponse> => {
        const requestId = options.requestId || crypto.randomUUID()
        const response = await api.post(
            `/board/scenario/recommend?requestId=${encodeURIComponent(requestId)}`,
            request,
            {
                signal: options.signal,
                ...SERVER_BOUNDED_REQUEST,
                headers: { Authorization: `Bearer ${options.authToken}` }
            }
        )
        try {
            return validateScenarioRecommendationResponse<ScenarioRecommendationResponse>(
                unpack<unknown>(response),
                'Scenario recommendation',
                request
            );
        } catch (error) {
            throw markRecommendationResponseReceived(error)
        }
    },

    cancelRecommendation: async (requestId: string, authToken: string): Promise<boolean> => {
        return unpack<boolean>(await api.delete(
            `/board/recommendations/${encodeURIComponent(requestId)}`,
            {
                ...INTERACTIVE_CONTROL_REQUEST,
                headers: { Authorization: `Bearer ${authToken}` }
            }
        ));
    },

    getRecommendationStatus: async (
        requestId: string,
        authToken: string
    ): Promise<InteractiveOperationStatus> => {
        return validateInteractiveOperationStatus(unpack<unknown>(await api.get(
            `/board/recommendations/${encodeURIComponent(requestId)}`,
            {
                ...INTERACTIVE_CONTROL_REQUEST,
                headers: { Authorization: `Bearer ${authToken}` }
            }
        )));
    },

    // ==== 故障定位与修复 ====
    /**
     * 获取 Trace 的故障规则定位
     */
    getFaultRules: async (traceId: number): Promise<FaultLocalizationResult> => {
        return validateFaultLocalizationResult(
            unpack<unknown>(await api.get(`/verify/traces/${traceId}/fault-rules`)),
            traceId
        );
    },

    /**
     * 获取 Trace 的修复建议
     */
    fixTrace: async (
        traceId: number,
        request: FixRequest | undefined,
        options: { authToken: string; requestId?: string; signal?: AbortSignal }
    ): Promise<FixResult> => {
        const requestId = options.requestId || crypto.randomUUID()
        return validateFixResult(
            unpack<unknown>(await api.post(
                `/verify/traces/${traceId}/fix`,
                request || {},
                {
                    ...SERVER_BOUNDED_REQUEST,
                    headers: { Authorization: `Bearer ${options.authToken}` },
                    params: { requestId },
                    signal: options.signal
                }
            )),
            traceId,
            request?.strategies || []
        );
    },

    cancelFixRequest: async (requestId: string, authToken: string): Promise<boolean> => {
        return unpack<boolean>(await api.delete(
            `/verify/fix-requests/${encodeURIComponent(requestId)}`,
            {
                ...INTERACTIVE_CONTROL_REQUEST,
                headers: { Authorization: `Bearer ${authToken}` }
            }
        ));
    },

    getFixRequestStatus: async (
        requestId: string,
        authToken: string
    ): Promise<InteractiveOperationStatus> => {
        return validateInteractiveOperationStatus(unpack<unknown>(await api.get(
            `/verify/fix-requests/${encodeURIComponent(requestId)}`,
            {
                ...INTERACTIVE_CONTROL_REQUEST,
                headers: { Authorization: `Bearer ${authToken}` }
            }
        )));
    },

    /** Apply the exact signed suggestion the user reviewed after server-side drift checks. */
    applyFix: async (traceId: number, suggestion: FixSuggestion,
                     preferredRangeSelections?: PreferredRangeSelection[]): Promise<FixApplyResult> => {
        if (!suggestion.suggestionToken) {
            throw new BoardResponseContractError('Automatic fix apply', 'suggestionToken is required')
        }
        const payload: FixApplyRequest = {
            strategy: suggestion.strategy,
            suggestion,
            suggestionToken: suggestion.suggestionToken,
            preferredRangeSelections
        };
        const result = validateFixApplyResult(
            unpack<unknown>(await api.post(
                `/verify/traces/${traceId}/fix/apply`, payload, SERVER_BOUNDED_REQUEST
            )),
            suggestion
        )
        return {
            ...result,
            rules: result.rules.map(fromBackendRuleDto)
        };
    }
}
