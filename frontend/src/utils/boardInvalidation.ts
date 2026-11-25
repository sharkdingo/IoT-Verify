export type BoardInvalidationReason = 'http-mutation' | 'chat-tool' | 'manual'

const TYPES_BY_SCOPE = {
  board: 'BOARD_INVALIDATED',
  'run-history': 'RUN_HISTORY_INVALIDATED',
  tasks: 'TASKS_INVALIDATED'
} as const

export type BoardInvalidationScope = keyof typeof TYPES_BY_SCOPE

type BoardInvalidationWireMessage = {
  type: typeof TYPES_BY_SCOPE[BoardInvalidationScope]
  userId: number
  reason: BoardInvalidationReason
  issuedAt: number
}

type BoardInvalidationMessage = BoardInvalidationWireMessage & { scope: BoardInvalidationScope }

const CHANNEL_NAME = 'iot-verify-board-invalidation-v1'
const VALID_REASONS = new Set<BoardInvalidationReason>(['http-mutation', 'chat-tool', 'manual'])
const SCOPES_BY_TYPE = new Map<string, BoardInvalidationScope>(
  Object.entries(TYPES_BY_SCOPE).map(([scope, type]) => [type, scope as BoardInvalidationScope])
)
const listeners = new Set<(message: BoardInvalidationMessage) => void>()
let channel: BroadcastChannel | null = null

const getChannel = (): BroadcastChannel | null => {
  if (channel || typeof window === 'undefined' || typeof BroadcastChannel === 'undefined') {
    return channel
  }
  channel = new BroadcastChannel(CHANNEL_NAME)
  channel.addEventListener('message', event => {
    const message = event.data as Partial<BoardInvalidationWireMessage> | null
    const scope = message?.type && SCOPES_BY_TYPE.get(message.type)
    if (!message || !scope
      || !Number.isSafeInteger(message.userId) || Number(message.userId) <= 0
      || !VALID_REASONS.has(message.reason as BoardInvalidationReason)
      || typeof message.issuedAt !== 'number' || !Number.isFinite(message.issuedAt)) return
    const notification = { ...message as BoardInvalidationWireMessage, scope }
    listeners.forEach(listener => listener(notification))
  })
  return channel
}

export const publishBoardInvalidation = (
  userId: number | null | undefined,
  reason: BoardInvalidationReason,
  scope: BoardInvalidationScope = 'board'
) => {
  if (!Number.isSafeInteger(userId) || userId == null || userId <= 0) return
  getChannel()?.postMessage({
    type: TYPES_BY_SCOPE[scope],
    userId,
    reason,
    issuedAt: Date.now()
  } satisfies BoardInvalidationWireMessage)
}

export const subscribeBoardInvalidation = (
  userId: number | null | undefined,
  listener: (message: BoardInvalidationMessage) => void
) => {
  if (!Number.isSafeInteger(userId) || userId == null || userId <= 0) return () => undefined
  getChannel()
  const scopedListener = (message: BoardInvalidationMessage) => {
    if (message.userId === userId) listener(message)
  }
  listeners.add(scopedListener)
  return () => listeners.delete(scopedListener)
}
