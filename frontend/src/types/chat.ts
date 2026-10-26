// src/types/chat.ts

// Matches backend ChatSessionResponseDto.
export interface ChatTaskMemory {
    status: 'CURRENT' | 'PARTIAL' | 'UNAVAILABLE'
    throughMessageId: number
    sceneChanged: boolean
    items: Array<{
        id: string
        kind: 'GOAL' | 'CONSTRAINT' | 'DECISION' | 'QUESTION'
        scope: 'SESSION' | 'SCENE'
        sourceMessageId: number
        quote: string
    }>
}

export interface ChatSession {
    id: string
    userId: number
    title: string | null
    createdAt?: string
    updatedAt: string
    active: boolean
    latestTerminalMessageId: number | null
    latestExecutionStatus: ChatExecutionStatus | null
    hasUnreadUpdate: boolean
}

export interface ChatSessionActivity {
    sessionId: string
    active: boolean
}

export interface ChatSessionObservationBaseline {
    userId: number
    sessions: Array<Pick<ChatSession, 'id' | 'active' | 'latestTerminalMessageId'>>
    reconciliation: Promise<boolean> | null
}

export type ChatConfirmationKind = 'DESTRUCTIVE' | 'DEFAULT_TEMPLATE_RESET' | 'SCENE_REPLACEMENT'
export type ChatConfirmationAction = 'CONFIRM' | 'CANCEL'

export interface ChatConfirmationCommand {
    action: ChatConfirmationAction
    kind: ChatConfirmationKind
    confirmationId: string
}

export interface ChatProtectedAction {
    kind: ChatConfirmationKind
    confirmationId: string
    toolName: string
    target: string
}

export interface ChatPendingConfirmation {
    sessionId: string
    actions: ChatProtectedAction[]
}

export type ChatExecutionStatus =
    | 'COMPLETED'
    | 'AWAITING_CONFIRMATION'
    | 'PARTIAL'
    | 'STOPPED'
    | 'DISCONNECTED'
    | 'FAILED'

// Matches backend ChatMessageResponseDto.
export interface ChatMessage {
    id?: number
    sessionId?: string
    role: 'user' | 'assistant'
    content: string
    turnId?: string
    createdAt?: string
    // Persisted by the backend for history and populated live while streaming.
    executionTrace?: StreamProgress[]
    executionElapsedSeconds?: number
    executionStatus?: ChatExecutionStatus
}

export interface PersistedChatMessage extends ChatMessage {
    id: number
    sessionId: string
    turnId: string
    createdAt: string
}

export interface ChatHistoryPage {
    messages: PersistedChatMessage[]
    nextBeforeId: number | null
    hasMore: boolean
}

/**
 * Every board state an assistant `REFRESH_DATA` command can name.
 *
 * Single source of truth: the SSE frame validator (`api/chat.ts`) and the board's target table
 * (`views/board/assistantRefresh.ts`) both derive from this, because they used to repeat the same
 * seven strings independently — adding a target meant editing three lists, and missing one silently
 * either rejected a valid frame or skipped the refresh it asked for.
 */
export const STREAM_REFRESH_TARGETS = [
    'device_list',
    'environment_list',
    'rule_list',
    'spec_list',
    'template_list',
    'run_history',
    'board_state'
] as const

export type StreamRefreshTarget = typeof STREAM_REFRESH_TARGETS[number]

export const ASSISTANT_ACTIONS = [
    'DEVICE_ADDED',
    'DEVICE_DELETED',
    'DEVICE_UPDATED',
    'RULES_UPDATED',
    'REPAIR_APPLIED',
    'SPECIFICATIONS_UPDATED',
    'ENVIRONMENT_UPDATED',
    'SCENE_APPLIED',
    'DEFAULT_TEMPLATES_RESET',
    'TEMPLATES_UPDATED',
    'FORMAL_VERIFICATION_RUN',
    'VERIFICATION_TASK_STARTED',
    'SIMULATION_TASK_STARTED',
    'EXPLORATION_TASK_STARTED',
    'RUN_HISTORY_UPDATED',
    'BOARD_UNDONE',
    'BOARD_REDONE',
    'BOARD_CLEARED',
    'OUTCOME_RECONCILED'
] as const

export type AssistantAction = typeof ASSISTANT_ACTIONS[number]

export interface StreamCommand {
    type: 'REFRESH_DATA'
    payload: {
        target: StreamRefreshTarget
        assistantAction?: AssistantAction
        assistantSummary?: string
    }
}

export interface StreamTerminal {
    turnId: string
    executionStatus: ChatExecutionStatus
}

type StreamProgressBase = {
    toolName?: string | null
    round?: number | null
    successfulSteps?: number | null
    failedSteps?: number | null
    unconfirmedSteps?: number | null
    detail?: string | null
}

type ToolResultOutcome =
    | 'USABLE'
    | 'PARTIAL'
    | 'FAILED'
    | 'RESULT_UNAVAILABLE'
    | 'CONFIRMATION_REQUIRED'

type ExecutionGuardOutcome = 'NO_PROGRESS' | 'EMERGENCY_LIMIT'

export const CLAIM_REVIEW_REASONS = [
    'MISSING_TOOL_EVIDENCE',
    'MISSING_BOARD_SNAPSHOT',
    'MISSING_OPERATION_RECEIPT'
] as const

export interface ClaimReview {
    reasonCode: typeof CLAIM_REVIEW_REASONS[number]
    originalText: string
    matchedText: string
}

export type StreamProgress = StreamProgressBase & (
    | {
        stage: 'CONTEXT_READY' | 'TASK_RESUMED' | 'PLANNING' | 'REASONING' | 'TOOL_EXECUTION' | 'WRITING_RESPONSE'
        outcome?: null
      }
    | { stage: 'TOOL_RESULT'; outcome: ToolResultOutcome }
    | { stage: 'EXECUTION_GUARD'; outcome: ExecutionGuardOutcome }
    | { stage: 'CLAIM_REVIEW'; outcome: 'REPLACED' | 'HIDDEN'; claimReview: ClaimReview }
)

export type ChatLogoutPreparation = 'ready' | 'outcome-unknown' | 'reconciliation-failed'
