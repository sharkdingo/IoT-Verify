import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

class FakeBroadcastChannel {
  static instances: FakeBroadcastChannel[] = []
  readonly posted: unknown[] = []
  private listener: ((event: MessageEvent) => void) | null = null

  constructor(readonly name: string) {
    FakeBroadcastChannel.instances.push(this)
  }

  addEventListener(_type: string, listener: (event: MessageEvent) => void) {
    this.listener = listener
  }

  postMessage(message: unknown) {
    this.posted.push(message)
  }

  emit(data: unknown) {
    this.listener?.({ data } as MessageEvent)
  }

  close() {}
}

describe('board invalidation channel', () => {
  beforeEach(() => {
    vi.resetModules()
    FakeBroadcastChannel.instances = []
    vi.stubGlobal('BroadcastChannel', FakeBroadcastChannel)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('keeps the semantic event wire shape and scopes received events by user and domain', async () => {
    const { publishBoardInvalidation, subscribeBoardInvalidation } =
      await import('../boardInvalidation')
    const received: unknown[] = []
    const unsubscribe = subscribeBoardInvalidation(7, message => received.push(message))

    const channel = FakeBroadcastChannel.instances[0]
    publishBoardInvalidation(7, 'chat-tool')
    publishBoardInvalidation(7, 'http-mutation', 'run-history')
    publishBoardInvalidation(7, 'manual', 'tasks')
    expect(channel.posted).toEqual([
      { type: 'BOARD_INVALIDATED', userId: 7, reason: 'chat-tool', issuedAt: expect.any(Number) },
      { type: 'RUN_HISTORY_INVALIDATED', userId: 7, reason: 'http-mutation', issuedAt: expect.any(Number) },
      { type: 'TASKS_INVALIDATED', userId: 7, reason: 'manual', issuedAt: expect.any(Number) }
    ])

    const [board, history, tasks] = channel.posted as Record<string, unknown>[]
    channel.emit(board)
    channel.emit({ ...history, userId: 8 })
    channel.emit({ ...tasks, type: 'UNKNOWN_INVALIDATION' })
    channel.emit(history)
    channel.emit(tasks)
    expect(received).toEqual([
      { ...board, scope: 'board' },
      { ...history, scope: 'run-history' },
      { ...tasks, scope: 'tasks' }
    ])

    unsubscribe()
    channel.emit(tasks)
    expect(received).toHaveLength(3)
  })
})
