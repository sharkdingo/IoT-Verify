// src/api/chat.ts - Chat API
import api from '@/api/http'
import type {
    ChatConfirmationCommand,
    ChatConfirmationKind,
    ChatExecutionStatus,
    ChatHistoryPage,
    ChatMessage,
    PersistedChatMessage,
    ChatPendingConfirmation,
    ChatSession,
    ChatSessionActivity,
    StreamCommand,
    StreamProgress,
    StreamTerminal
} from "@/types/chat"
import { ASSISTANT_ACTIONS, CLAIM_REVIEW_REASONS, STREAM_REFRESH_TARGETS } from '@/types/chat'
import { useAuth } from '@/stores/auth'
import { redirectToLogin } from '@/router/loginRedirect'
import i18n from '@/assets/i18n'

export const CHAT_ACTIVITY_TIMEOUT_MS = 2500

export type ChatStreamErrorKind =
    | 'HTTP_ERROR'
    | 'SERVER_FRAME'
    | 'MISSING_BODY'
    | 'INCOMPLETE_STREAM'
    | 'INVALID_FRAME'
    | 'UNKNOWN'

export class ChatStreamError extends Error {
    readonly serverFrame: boolean
    readonly status?: number
    readonly kind: ChatStreamErrorKind
    readonly detail?: string
    readonly reasonCode?: string
    readonly limit?: number

    constructor(message: string, options: {
        serverFrame?: boolean
        status?: number
        kind?: ChatStreamErrorKind
        detail?: string
        reasonCode?: string
        limit?: number
    } = {}) {
        super(message)
        this.name = 'ChatStreamError'
        this.serverFrame = options.serverFrame ?? false
        this.status = options.status
        this.kind = options.kind ?? (this.serverFrame ? 'SERVER_FRAME' : 'UNKNOWN')
        this.detail = options.detail
        this.reasonCode = options.reasonCode
        this.limit = options.limit
    }
}

const unpack = <T>(response: any): T => {
  return response.data.data;
};

const CHAT_EXECUTION_STATUSES: ReadonlySet<ChatExecutionStatus> = new Set([
  'COMPLETED',
  'AWAITING_CONFIRMATION',
  'PARTIAL',
  'STOPPED',
  'DISCONNECTED',
  'FAILED'
]);
const CHAT_CONFIRMATION_KINDS: ReadonlySet<ChatConfirmationKind> = new Set([
  'DESTRUCTIVE',
  'DEFAULT_TEMPLATE_RESET',
  'SCENE_REPLACEMENT'
]);

const CHAT_ROLES = new Set<ChatMessage['role']>(['user', 'assistant']);
const STREAM_PROGRESS_STAGES = new Set<StreamProgress['stage']>([
  'CONTEXT_READY',
  'TASK_RESUMED',
  'PLANNING',
  'REASONING',
  'TOOL_EXECUTION',
  'TOOL_RESULT',
  'EXECUTION_GUARD',
  'CLAIM_REVIEW',
  'WRITING_RESPONSE'
]);
const STREAM_PROGRESS_OUTCOMES = new Set<NonNullable<StreamProgress['outcome']>>([
  'USABLE',
  'PARTIAL',
  'FAILED',
  'RESULT_UNAVAILABLE',
  'CONFIRMATION_REQUIRED',
  'NO_PROGRESS',
  'EMERGENCY_LIMIT',
  'REPLACED',
  'HIDDEN'
]);

const isRecord = (value: unknown): value is Record<string, unknown> =>
  Boolean(value) && typeof value === 'object' && !Array.isArray(value);

const isOptionalString = (value: unknown) =>
  value === undefined || value === null || typeof value === 'string';

const isOptionalNonNegativeInteger = (value: unknown) =>
  value === undefined || value === null
    || (Number.isSafeInteger(value) && Number(value) >= 0);

const TOOL_RESULT_OUTCOMES: ReadonlySet<NonNullable<StreamProgress['outcome']>> = new Set([
  'USABLE',
  'PARTIAL',
  'FAILED',
  'RESULT_UNAVAILABLE',
  'CONFIRMATION_REQUIRED'
]);
const EXECUTION_GUARD_OUTCOMES: ReadonlySet<NonNullable<StreamProgress['outcome']>> = new Set([
  'NO_PROGRESS',
  'EMERGENCY_LIMIT'
]);
// Derived from the wire contract rather than repeated: a target added to types/chat.ts but missed
// here would make the validator reject a frame the server is entitled to send.
const REFRESH_TARGET_NAMES: ReadonlySet<string> = new Set(STREAM_REFRESH_TARGETS);
const ASSISTANT_ACTION_NAMES: ReadonlySet<string> = new Set(ASSISTANT_ACTIONS);
const CLAIM_REVIEW_REASON_NAMES: ReadonlySet<string> = new Set(CLAIM_REVIEW_REASONS);

const isStreamProgress = (value: unknown): value is StreamProgress => {
  if (!isRecord(value)
      || typeof value.stage !== 'string'
      || !STREAM_PROGRESS_STAGES.has(value.stage as StreamProgress['stage'])
      || !isOptionalString(value.toolName)
      || !isOptionalString(value.detail)
      || !isOptionalNonNegativeInteger(value.round)
      || !isOptionalNonNegativeInteger(value.successfulSteps)
      || !isOptionalNonNegativeInteger(value.failedSteps)
      || !isOptionalNonNegativeInteger(value.unconfirmedSteps)) {
    return false;
  }
  const outcome = value.outcome;
  if (outcome !== undefined && outcome !== null
      && (typeof outcome !== 'string'
        || !STREAM_PROGRESS_OUTCOMES.has(outcome as NonNullable<StreamProgress['outcome']>))) {
    return false;
  }
  if (value.stage === 'CLAIM_REVIEW') {
    const review = value.claimReview;
    return (outcome === 'REPLACED' || outcome === 'HIDDEN')
      && (value.toolName === undefined || value.toolName === null)
      && isRecord(review) && Object.keys(review).length === 3
      && typeof review.reasonCode === 'string' && CLAIM_REVIEW_REASON_NAMES.has(review.reasonCode)
      && typeof review.originalText === 'string' && Boolean(review.originalText.trim())
      && typeof review.matchedText === 'string' && Boolean(review.matchedText.trim())
      && review.originalText.includes(review.matchedText);
  }
  if (value.claimReview !== undefined && value.claimReview !== null) return false;
  if (value.stage === 'TOOL_RESULT') {
    return typeof outcome === 'string'
      && TOOL_RESULT_OUTCOMES.has(outcome as NonNullable<StreamProgress['outcome']>);
  }
  if (value.stage === 'EXECUTION_GUARD') {
    return typeof outcome === 'string'
      && EXECUTION_GUARD_OUTCOMES.has(outcome as NonNullable<StreamProgress['outcome']>);
  }
  return outcome === undefined || outcome === null;
};

export const hasCompletedToolEvidence = (trace: unknown): boolean => {
  if (!Array.isArray(trace) || trace.length === 0) return false;
  let hasUsableResult = false;
  let pendingExecution: { toolName: string; round: number } | null = null;
  const unresolvedPartialTools = new Set<string>();
  for (const progress of trace) {
    if (!isStreamProgress(progress) || progress.stage === 'EXECUTION_GUARD'
        || (progress.stage === 'CLAIM_REVIEW' && progress.outcome === 'HIDDEN')) return false;
    if (progress.stage === 'TOOL_EXECUTION') {
      if (pendingExecution
          || typeof progress.toolName !== 'string'
          || !progress.toolName.trim()
          || !Number.isSafeInteger(progress.round)
          || Number(progress.round) < 1) return false;
      pendingExecution = { toolName: progress.toolName, round: Number(progress.round) };
      continue;
    }
    if (progress.stage === 'TOOL_RESULT') {
      if (!pendingExecution
          || progress.toolName !== pendingExecution.toolName
          || progress.round !== pendingExecution.round) return false;
      const toolName = pendingExecution.toolName;
      pendingExecution = null;
      if (progress.outcome === 'USABLE') {
        unresolvedPartialTools.delete(toolName);
        hasUsableResult = true;
      } else if (progress.outcome === 'PARTIAL') {
        unresolvedPartialTools.add(toolName);
      } else {
        return false;
      }
    }
  }
  return hasUsableResult && pendingExecution === null && unresolvedPartialTools.size === 0;
};

const validateStreamProgress = (value: unknown, context: string): StreamProgress => {
  if (!isStreamProgress(value)) throw new Error(`${context} is incomplete`);
  return value;
};

const validateStreamCommand = (value: unknown): StreamCommand => {
  const payloadKeys = isRecord(value) && isRecord(value.payload)
      ? Object.keys(value.payload)
      : [];
  if (!isRecord(value)
      || value.type !== 'REFRESH_DATA'
      || !isRecord(value.payload)
      || payloadKeys.length < 1
      || payloadKeys.length > 3
      || payloadKeys.some(key => key !== 'target'
          && key !== 'assistantAction'
          && key !== 'assistantSummary')
      || typeof value.payload.target !== 'string'
      || !REFRESH_TARGET_NAMES.has(value.payload.target)
      || (value.payload.assistantAction !== undefined
          && (typeof value.payload.assistantAction !== 'string'
              || !ASSISTANT_ACTION_NAMES.has(value.payload.assistantAction)))
      || (value.payload.assistantSummary !== undefined
          && (typeof value.payload.assistantSummary !== 'string'
              || value.payload.assistantSummary.trim().length === 0
              || value.payload.assistantSummary.length > 240))) {
    throw new Error('Chat stream command is incomplete');
  }
  return value as unknown as StreamCommand;
};

const validateStreamTerminal = (value: unknown, expectedTurnId: string): StreamTerminal => {
  if (!isRecord(value)
      || Object.keys(value).length !== 2
      || typeof value.turnId !== 'string'
      || value.turnId !== expectedTurnId
      || typeof value.executionStatus !== 'string'
      || !CHAT_EXECUTION_STATUSES.has(value.executionStatus as ChatExecutionStatus)) {
    throw new Error('Chat stream terminal is incomplete');
  }
  return value as unknown as StreamTerminal;
};

const validateChatHistoryMessages = (
    value: unknown,
    expectedSessionId: string
): PersistedChatMessage[] => {
  if (!Array.isArray(value)) throw new Error('Chat history messages are incomplete');
  value.forEach((candidate, index) => {
    if (!isRecord(candidate)
        || typeof candidate.role !== 'string'
        || !CHAT_ROLES.has(candidate.role as ChatMessage['role'])
        || typeof candidate.content !== 'string'
        || candidate.sessionId !== expectedSessionId
        || typeof candidate.turnId !== 'string'
        || !candidate.turnId.trim()
        || typeof candidate.createdAt !== 'string'
        || !candidate.createdAt.trim()
        || !Number.isSafeInteger(candidate.id)
        || Number(candidate.id) <= 0
        || !isOptionalNonNegativeInteger(candidate.executionElapsedSeconds)
        || (candidate.executionTrace !== undefined && candidate.executionTrace !== null
          && (!Array.isArray(candidate.executionTrace)
            || candidate.executionTrace.some(progress => !isStreamProgress(progress))))) {
      throw new Error(`Chat history message ${index} is incomplete`);
    }
    const status = candidate.executionStatus;
    if (status !== undefined && status !== null
        && (typeof status !== 'string'
            || !CHAT_EXECUTION_STATUSES.has(status as ChatExecutionStatus))) {
      throw new Error(`Chat history message ${index} has an unknown execution status`);
    }
    if (status === 'COMPLETED' && !hasCompletedToolEvidence(candidate.executionTrace)) {
      throw new Error(`Chat history message ${index} has unproven completed status`);
    }
  });
  return value as PersistedChatMessage[];
};

/**
 * Validates one session row.
 *
 * `active` locks input only when this is the selected conversation, while any active row keeps the
 * shared Board protected and drives background-completion polling. A missing flag would read as
 * `undefined` → falsy → idle, hiding work and unlocking the Board while the server is still writing.
 * So an incomplete row is an error, not a default.
 */
const validateChatSession = (value: unknown, context: string): ChatSession => {
  const session = value as Partial<ChatSession> | null;
  if (!session
      || typeof session.id !== 'string' || !session.id
      || typeof session.userId !== 'number' || !Number.isFinite(session.userId)
      // `title` is nullable on the DTO, and an omitted field carries the same information as an
      // explicit null — rejecting only the absent form would throw away the whole session list over
      // one untitled row.
      || (session.title !== null && session.title !== undefined && typeof session.title !== 'string')
      || typeof session.updatedAt !== 'string'
      || typeof session.active !== 'boolean'
      || (session.latestTerminalMessageId !== null
        && (typeof session.latestTerminalMessageId !== 'number'
          || !Number.isSafeInteger(session.latestTerminalMessageId)
          || session.latestTerminalMessageId <= 0))
      || (session.latestExecutionStatus !== null
        && (typeof session.latestExecutionStatus !== 'string'
          || !CHAT_EXECUTION_STATUSES.has(session.latestExecutionStatus as ChatExecutionStatus)))
      || typeof session.hasUnreadUpdate !== 'boolean'
      || ((session.latestTerminalMessageId == null) !== (session.latestExecutionStatus == null))
      || (session.hasUnreadUpdate && session.latestTerminalMessageId == null)) {
    throw new Error(`${context} is incomplete`);
  }
  return session as ChatSession;
}

export const getSessionList = async (): Promise<ChatSession[]> => {
  const response = await api.get<any>('/chat/sessions');
  const sessions = unpack<unknown>(response);
  if (!Array.isArray(sessions)) {
    throw new Error('Chat session list response is incomplete');
  }
  return sessions.map((session, index) =>
    validateChatSession(session, `Chat session list row ${index}`));
}

export const createSession = async (signal?: AbortSignal): Promise<ChatSession> => {
  const response = await api.post<any>('/chat/sessions', null, { signal });
  return validateChatSession(unpack<unknown>(response), 'Created chat session');
}

export const getSessionHistory = async (
    sessionId: string,
    options: AbortSignal | { signal?: AbortSignal; beforeId?: number | null; limit?: number } = {}
): Promise<ChatHistoryPage> => {
  const normalized: { signal?: AbortSignal; beforeId?: number | null; limit?: number } =
      typeof AbortSignal !== 'undefined' && options instanceof AbortSignal
          ? { signal: options }
          : options as { signal?: AbortSignal; beforeId?: number | null; limit?: number };
  const params: Record<string, number> = {};
  if (normalized.beforeId != null) params.beforeId = normalized.beforeId;
  if (normalized.limit != null) params.limit = normalized.limit;
  const config: { signal?: AbortSignal; params?: Record<string, number> } = { signal: normalized.signal };
  if (Object.keys(params).length > 0) config.params = params;
  const response = await api.get<any>(`/chat/sessions/${encodeURIComponent(sessionId)}/messages`, config);
  const raw = unpack<unknown>(response);
  const nextBeforeId = isRecord(raw) ? raw.nextBeforeId : undefined;
  if (!isRecord(raw) || !Array.isArray(raw.messages)
      || (nextBeforeId !== null
        && (typeof nextBeforeId !== 'number'
          || !Number.isSafeInteger(nextBeforeId)
          || nextBeforeId <= 0))
      || typeof raw.hasMore !== 'boolean'
      || (raw.hasMore && nextBeforeId === null)) {
    throw new Error('Chat history response is incomplete');
  }
  return {
    messages: validateChatHistoryMessages(raw.messages, sessionId),
    nextBeforeId: nextBeforeId as number | null,
    hasMore: raw.hasMore
  };
}

export const markSessionTerminalSeen = async (
    sessionId: string,
    terminalMessageId: number
): Promise<void> => {
  if (!Number.isSafeInteger(terminalMessageId) || terminalMessageId <= 0) {
    throw new Error('Terminal message id must be a positive safe integer');
  }
  await api.post(
    `/chat/sessions/${encodeURIComponent(sessionId)}/seen`,
    { terminalMessageId }
  );
}

export const requestSessionStop = async (
    sessionId: string,
    turnId?: string,
    authToken?: string | null
): Promise<void> => {
  await api.post(
    `/chat/sessions/${sessionId}/stop`,
    { turnId: turnId?.trim() || null },
    {
      timeout: CHAT_ACTIVITY_TIMEOUT_MS,
      ...(authToken ? { headers: { Authorization: `Bearer ${authToken}` } } : {})
    }
  );
}

export const deleteSession = async (sessionId: string): Promise<void> => {
  await api.delete(`/chat/sessions/${sessionId}`);
}

export const getSessionActivity = async (
    sessionId: string,
    options: { timeoutMs?: number; signal?: AbortSignal } = {}
): Promise<ChatSessionActivity> => {
  const response = await api.get<any>(`/chat/sessions/${sessionId}/activity`, {
    timeout: options.timeoutMs ?? CHAT_ACTIVITY_TIMEOUT_MS,
    signal: options.signal
  });
  const activity = unpack<ChatSessionActivity>(response);
  if (!activity || activity.sessionId !== sessionId || typeof activity.active !== 'boolean') {
    throw new Error('Chat session activity response is incomplete');
  }
  return activity;
}

export const getPendingConfirmation = async (
    sessionId: string,
    signal?: AbortSignal
): Promise<ChatPendingConfirmation> => {
  const response = await api.get<any>(`/chat/sessions/${sessionId}/confirmation`, { signal });
  const pending = unpack<ChatPendingConfirmation>(response);
  if (!pending
      || pending.sessionId !== sessionId
      || !Array.isArray(pending.actions)
      || pending.actions.some(action => !action || typeof action !== 'object'
        || !CHAT_CONFIRMATION_KINDS.has(action.kind)
        || typeof action.confirmationId !== 'string' || !action.confirmationId.trim()
        || typeof action.toolName !== 'string' || !action.toolName.trim()
        || typeof action.target !== 'string' || !action.target.trim())
      || new Set(pending.actions.map(action => action.kind)).size !== pending.actions.length) {
    throw new Error('Chat pending-confirmation response is incomplete');
  }
  return pending;
}

export const sendStreamChat = async (
    sessionId: string,
    content: string,
    callbacks: {
        isCurrentRequest?: () => boolean;
        onMessage: (text: string) => void
        onCommand?: (cmd: StreamCommand) => void;
        onProgress?: (progress: StreamProgress) => void;
        onAccepted?: () => void;
        onError?: (err: any) => void
        onFinish?: (terminal: StreamTerminal) => void
    },
    controller?: AbortController,
    turnId?: string,
    confirmation?: ChatConfirmationCommand
) => {
    try {
        const { getToken, logoutIfTokenMatches } = useAuth();
        const token = getToken();
        const API_BASE = import.meta.env.VITE_API_BASE_URL || '';
        const effectiveTurnId = turnId?.trim() || globalThis.crypto.randomUUID();

        const headers: Record<string, string> = {
            'Content-Type': 'application/json'
        };

        if (token) {
            headers['Authorization'] = `Bearer ${token}`;
        }

        const response = await fetch(`${API_BASE}/api/chat/completions`, {
            method: 'POST',
            headers,
            body: JSON.stringify({
                sessionId,
                content,
                turnId: effectiveTurnId,
                // The language the user is reading in. Without it the backend inferred one by looking for Han
                // characters in `content`, which reports English for any message that happens to carry none — so a
                // Chinese interface answered "hi" with English status prose.
                locale: String(i18n.global.locale.value),
                ...(confirmation ? { confirmation } : {})
            }),
            signal: controller?.signal
        });

        if (!response.ok) {
            if (response.status === 401) {
                if (logoutIfTokenMatches(token)) {
                    await redirectToLogin();
                }
            }
            const detail = await readErrorDetail(response);
            throw new ChatStreamError(`HTTP ${response.status}: ${detail.message}`, {
                kind: 'HTTP_ERROR',
                status: response.status,
                detail: detail.message,
                reasonCode: detail.reasonCode,
                limit: detail.limit
            });
        }

        // A 2xx response crosses the synchronous rejection boundary. It can still carry
        // an outcome-unknown warning when admission cleanup could not be confirmed.
        callbacks.onAccepted?.();

        if (!response.body) {
            throw new ChatStreamError('No response body', { kind: 'MISSING_BODY' });
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';
        let terminal: StreamTerminal | null = null;
        let serverFrameError: ChatStreamError | null = null;
        const receivedProgress: StreamProgress[] = [];

        const handlePayload = (dataStr: string) => {
            if (terminal) {
                throw new ChatStreamError('Chat stream continued after its terminal frame', {
                    kind: 'INVALID_FRAME'
                });
            }
            const trimmed = dataStr.trim();
            if (!trimmed) {
                throw new ChatStreamError('Invalid server stream frame', { kind: 'INVALID_FRAME' });
            }

            let json: unknown;
            try {
                json = JSON.parse(dataStr);
            } catch {
                throw new ChatStreamError('Invalid server stream frame', { kind: 'INVALID_FRAME' });
            }
            if (!isRecord(json)) {
                throw new ChatStreamError('Invalid server stream frame', { kind: 'INVALID_FRAME' });
            }

            const allowedKeys = new Set(['content', 'error', 'command', 'progress', 'terminal']);
            if (Object.keys(json).some(key => !allowedKeys.has(key))) {
                throw new ChatStreamError('Invalid server stream frame', { kind: 'INVALID_FRAME' });
            }
            if (json.content !== undefined && typeof json.content !== 'string') {
                throw new ChatStreamError('Invalid server stream frame', { kind: 'INVALID_FRAME' });
            }
            if (json.error !== undefined
                && (typeof json.error !== 'string' || !json.error.trim())) {
                throw new ChatStreamError('Invalid server stream frame', { kind: 'INVALID_FRAME' });
            }

            const content = typeof json.content === 'string' ? json.content : '';
            const errorMessage = typeof json.error === 'string' ? json.error.trim() : '';
            let command: StreamCommand | undefined;
            let progress: StreamProgress | undefined;
            let terminalFrame: StreamTerminal | undefined;
            try {
                if (json.command !== undefined) {
                    command = validateStreamCommand(json.command);
                }
                if (json.progress !== undefined) {
                    progress = validateStreamProgress(json.progress, 'Chat stream progress');
                }
                if (json.terminal !== undefined) {
                    terminalFrame = validateStreamTerminal(json.terminal, effectiveTurnId);
                }
            } catch {
                throw new ChatStreamError('Invalid server stream frame', { kind: 'INVALID_FRAME' });
            }

            const payloadCount = Number(Boolean(content))
                + Number(Boolean(errorMessage))
                + Number(Boolean(command))
                + Number(Boolean(progress))
                + Number(Boolean(terminalFrame));
            if (payloadCount !== 1) {
                throw new ChatStreamError('Invalid server stream frame', { kind: 'INVALID_FRAME' });
            }
            if (serverFrameError && !terminalFrame) {
                throw new ChatStreamError('Chat stream continued after its error frame', {
                    kind: 'INVALID_FRAME'
                });
            }

            if (errorMessage) {
                serverFrameError = new ChatStreamError(errorMessage, {
                    kind: 'SERVER_FRAME',
                    serverFrame: true
                });
                return;
            }

            if (terminalFrame) {
                if (serverFrameError
                    && terminalFrame.executionStatus !== 'FAILED'
                    && terminalFrame.executionStatus !== 'PARTIAL') {
                    throw new ChatStreamError('Chat error frame has a contradictory terminal status', {
                        kind: 'INVALID_FRAME'
                    });
                }
                if (terminalFrame.executionStatus === 'COMPLETED'
                    && !hasCompletedToolEvidence(receivedProgress)) {
                    throw new ChatStreamError('Completed chat terminal lacks usable tool evidence', {
                        kind: 'INVALID_FRAME'
                    });
                }
                terminal = terminalFrame;
                return;
            }

            if (command) callbacks.onCommand?.(command);
            if (progress) {
                receivedProgress.push(progress);
                callbacks.onProgress?.(progress);
            }
            if (content) callbacks.onMessage(content);
        };

        const handleEvent = (rawEvent: string) => {
            const dataLines = rawEvent
                .split(/\r?\n/)
                .map(line => line.trimEnd())
                .filter(line => line.trimStart().startsWith('data:'))
                .map(line => line.replace(/^\s*data:\s?/, ''));

            if (dataLines.length > 0) {
                handlePayload(dataLines.join('\n'));
            }
        };

        const drainEvents = () => {
            while (true) {
                const match = buffer.match(/\r?\n\r?\n/);
                if (!match || match.index === undefined) break;
                const eventText = buffer.slice(0, match.index);
                buffer = buffer.slice(match.index + match[0].length);
                handleEvent(eventText);
            }
        };

        try {
            while (true) {
                const { done, value } = await reader.read();
                if (done) {
                    buffer += decoder.decode();
                    break;
                }
                buffer += decoder.decode(value, { stream: true });
                drainEvents();
            }
        } catch (readError) {
            if (readError instanceof ChatStreamError) throw readError;
            if (!terminal) {
                if (serverFrameError) throw serverFrameError;
                throw readError;
            }
            // The persisted terminal frame is the completion proof. A proxy reset while
            // closing the transport must not turn that committed success into a failure.
            buffer += decoder.decode();
        }

        drainEvents();
        if (buffer.trim()) {
            handleEvent(buffer);
        }

        if (!terminal) {
            if (serverFrameError) throw serverFrameError;
            throw new ChatStreamError('Chat stream ended before its terminal frame', {
                kind: 'INCOMPLETE_STREAM'
            });
        }

        callbacks.onFinish?.(terminal);
        if (serverFrameError) throw serverFrameError;

    } catch (error: any) {
        if (error.name === 'AbortError') {
            return;
        }
        // Stop can close the detached stream before its acknowledgement arrives.
        // The settlement owner reports that outcome; this transport still rejects.
        if (callbacks.isCurrentRequest?.() === false) throw error;
        console.error('[Chat] Streaming request error:', error.message);
        if (callbacks.onError) callbacks.onError(error);
        throw error;
    }
}

const readErrorDetail = async (response: Response) => {
    const fallback = response.statusText || 'Request failed';
    try {
        const body = await response.text();
        if (!body) return { message: fallback };
        try {
            const json = JSON.parse(body);
            return {
                message: json?.message || json?.error || fallback,
                reasonCode: typeof json?.data?.reasonCode === 'string' ? json.data.reasonCode : undefined,
                limit: Number.isSafeInteger(json?.data?.limit) && json.data.limit > 0
                    ? json.data.limit
                    : undefined
            };
        } catch {
            return { message: body.slice(0, 200) };
        }
    } catch {
        return { message: fallback };
    }
};
