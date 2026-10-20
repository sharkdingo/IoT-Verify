import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const authMocks = vi.hoisted(() => ({
  logoutIfTokenMatches: vi.fn(() => true),
  getToken: vi.fn(() => null as string | null)
}))

const routerMocks = vi.hoisted(() => ({
  push: vi.fn(),
  currentRoute: { value: { path: '/board', fullPath: '/board' } }
}))

vi.mock('@/stores/auth', () => ({
  useAuth: () => ({
    getToken: authMocks.getToken,
    logoutIfTokenMatches: authMocks.logoutIfTokenMatches
  })
}))

vi.mock('@/router', () => ({ router: routerMocks }))

vi.mock('@/api/http', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn()
  }
}))

import http from '@/api/http'
import {
  createSession,
  getPendingConfirmation,
  getSessionActivity,
  getSessionHistory,
  getSessionList,
  markSessionTerminalSeen,
  requestSessionStop,
  sendStreamChat
} from './chat'

const successfulStreamResponse = (payload: string) => {
  const reader = {
    read: vi.fn()
      .mockResolvedValueOnce({ done: false, value: new TextEncoder().encode(payload) })
      .mockResolvedValue({ done: true, value: undefined })
  }
  return { ok: true, body: { getReader: () => reader } }
}

const chunkedStreamResponse = (payload: string) => {
  const bytes = new TextEncoder().encode(payload)
  const chunks: Uint8Array[] = []
  const widths = [1, 2, 5, 3, 7]
  for (let offset = 0, index = 0; offset < bytes.length; index += 1) {
    const end = Math.min(bytes.length, offset + widths[index % widths.length])
    chunks.push(bytes.slice(offset, end))
    offset = end
  }
  let index = 0
  const reader = {
    read: vi.fn(async () => index < chunks.length
      ? { done: false, value: chunks[index++] }
      : { done: true, value: undefined })
  }
  return { ok: true, body: { getReader: () => reader } }
}

const terminalFrame = (
  turnId = 'turn-1',
  executionStatus = 'PARTIAL'
) => `data: ${JSON.stringify({ terminal: { turnId, executionStatus } })}\n\n`

describe('chat stream lifecycle semantics', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    authMocks.getToken.mockReturnValue(null)
    authMocks.logoutIfTokenMatches.mockReturnValue(true)
    routerMocks.currentRoute.value = { path: '/board', fullPath: '/board' }
    routerMocks.push.mockResolvedValue(undefined)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('does not report a user-aborted stream as normally finished', async () => {
    const abortError = Object.assign(new Error('aborted'), { name: 'AbortError' })
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(abortError))
    const onFinish = vi.fn()
    const onError = vi.fn()

    await sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn(), onError, onFinish },
      new AbortController()
    )

    expect(onFinish).not.toHaveBeenCalled()
    expect(onError).not.toHaveBeenCalled()
  })

  it('decodes UTF-8 and SSE event boundaries split across network chunks exactly once', async () => {
    const content = '当前画布需要再检查。'
    const payload = [
      `data: ${JSON.stringify({ progress: { stage: 'CONTEXT_READY' } })}\r\n\r\n`,
      `data: ${JSON.stringify({ content })}\n\n`,
      terminalFrame('turn-1', 'PARTIAL')
    ].join('')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(chunkedStreamResponse(payload)))
    const onMessage = vi.fn()
    const onProgress = vi.fn()
    const onFinish = vi.fn()

    await sendStreamChat(
      'session-1',
      'hello',
      { onMessage, onProgress, onFinish },
      new AbortController(),
      'turn-1'
    )

    expect(onMessage).toHaveBeenCalledTimes(1)
    expect(onMessage).toHaveBeenCalledWith(content)
    expect(onProgress).toHaveBeenCalledTimes(1)
    expect(onProgress).toHaveBeenCalledWith({ stage: 'CONTEXT_READY' })
    expect(onFinish).toHaveBeenCalledTimes(1)
    expect(onFinish).toHaveBeenCalledWith({ turnId: 'turn-1', executionStatus: 'PARTIAL' })
  })

  it('binds a conversation-history load to its caller abort signal', async () => {
    vi.mocked(http.get).mockResolvedValue({
      data: { data: { messages: [], nextBeforeId: null, hasMore: false } }
    })
    const controller = new AbortController()

    await getSessionHistory('session-1', controller.signal)

    expect(http.get).toHaveBeenCalledWith(
      '/chat/sessions/session-1/messages',
      { signal: controller.signal }
    )
  })

  it.each(['id', 'sessionId', 'turnId', 'createdAt'])(
    'rejects persisted history without required %s identity',
    async field => {
      const message: Record<string, unknown> = {
        id: 1,
        sessionId: 'session-1',
        role: 'assistant',
        content: 'Saved response.',
        turnId: 'turn-1',
        createdAt: '2026-07-24T12:00:00Z',
        executionStatus: 'PARTIAL'
      }
      delete message[field]
      vi.mocked(http.get).mockResolvedValue({
        data: { data: { messages: [message], nextBeforeId: null, hasMore: false } }
      })

      await expect(getSessionHistory('session-1')).rejects.toThrow(
        'Chat history message 0 is incomplete'
      )
    }
  )

  it('rejects an unknown persisted execution status at the history boundary', async () => {
    vi.mocked(http.get).mockResolvedValue({
      data: {
        data: {
          messages: [{
            id: 1,
            sessionId: 'session-1',
            role: 'assistant',
            content: 'A future server response.',
            turnId: 'turn-1',
            createdAt: '2026-07-24T12:00:00Z',
            executionStatus: 'FUTURE_STATUS'
          }],
          nextBeforeId: null,
          hasMore: false
        }
      }
    })

    await expect(getSessionHistory('session-1')).rejects.toThrow(
      'Chat history message 0 has an unknown execution status'
    )
  })

  it('rejects completed history without persisted usable tool evidence', async () => {
    vi.mocked(http.get).mockResolvedValue({
      data: {
        data: {
          messages: [{
            id: 1,
            sessionId: 'session-1',
            role: 'assistant',
            content: 'Unproven completion.',
            turnId: 'turn-1',
            createdAt: '2026-07-24T12:00:00Z',
            executionStatus: 'COMPLETED'
          }],
          nextBeforeId: null,
          hasMore: false
        }
      }
    })

    await expect(getSessionHistory('session-1')).rejects.toThrow(
      'Chat history message 0 has unproven completed status'
    )
  })

  it('rejects completed history with an unpaired tool execution and another usable result', async () => {
    vi.mocked(http.get).mockResolvedValue({
      data: {
        data: {
          messages: [{
            id: 1,
            sessionId: 'session-1',
            role: 'assistant',
            content: 'Mismatched completion.',
            turnId: 'turn-1',
            createdAt: '2026-07-24T12:00:00Z',
            executionStatus: 'COMPLETED',
            executionTrace: [
              { stage: 'TOOL_EXECUTION', toolName: 'board_overview', round: 1 },
              { stage: 'TOOL_RESULT', toolName: 'list_rules', round: 1, outcome: 'USABLE' }
            ]
          }],
          nextBeforeId: null,
          hasMore: false
        }
      }
    })

    await expect(getSessionHistory('session-1')).rejects.toThrow(
      'Chat history message 0 has unproven completed status'
    )
  })

  it('rejects malformed persisted message content and execution traces', async () => {
    vi.mocked(http.get).mockResolvedValueOnce({
      data: {
        data: {
          messages: [{
            id: 1,
            sessionId: 'session-1',
            role: 'assistant',
            content: { text: 'not a string' },
            turnId: 'turn-1',
            createdAt: '2026-07-24T12:00:00Z'
          }],
          nextBeforeId: null,
          hasMore: false
        }
      }
    })

    await expect(getSessionHistory('session-1')).rejects.toThrow(
      'Chat history message 0 is incomplete'
    )

    vi.mocked(http.get).mockResolvedValueOnce({
      data: {
        data: {
          messages: [{
            id: 1,
            sessionId: 'session-1',
            role: 'assistant',
            content: 'Saved response',
            turnId: 'turn-1',
            createdAt: '2026-07-24T12:00:00Z',
            executionTrace: [{ stage: 'FUTURE_STAGE' }]
          }],
          nextBeforeId: null,
          hasMore: false
        }
      }
    })

    await expect(getSessionHistory('session-1')).rejects.toThrow(
      'Chat history message 0 is incomplete'
    )
  })

  it('rejects a history page containing a message from another session', async () => {
    vi.mocked(http.get).mockResolvedValue({
      data: {
        data: {
          messages: [{
            id: 1,
            sessionId: 'session-2',
            role: 'assistant',
            content: 'Do not render this in session 1.',
            turnId: 'turn-1',
            createdAt: '2026-07-24T12:00:00Z'
          }],
          nextBeforeId: null,
          hasMore: false
        }
      }
    })

    await expect(getSessionHistory('session-1')).rejects.toThrow(
      'Chat history message 0 is incomplete'
    )
  })

  it('rejects an unusable history cursor instead of paginating with corrupted state', async () => {
    vi.mocked(http.get).mockResolvedValue({
      data: {
        data: {
          messages: [{
            id: 1,
            sessionId: 'session-1',
            role: 'assistant',
            content: 'Saved response',
            turnId: 'turn-1',
            createdAt: '2026-07-24T12:00:00Z'
          }],
          nextBeforeId: null,
          hasMore: true
        }
      }
    })

    await expect(getSessionHistory('session-1')).rejects.toThrow(
      'Chat history response is incomplete'
    )
  })

  it('rejects obsolete array history and internal tool messages', async () => {
    vi.mocked(http.get).mockResolvedValueOnce({ data: { data: [] } })
    await expect(getSessionHistory('session-1')).rejects.toThrow(
      'Chat history response is incomplete'
    )

    vi.mocked(http.get).mockResolvedValueOnce({
      data: {
        data: {
          messages: [{
            id: 1,
            sessionId: 'session-1',
            role: 'tool',
            content: '{"secret":true}',
            turnId: 'turn-1',
            createdAt: '2026-07-24T12:00:00Z'
          }],
          nextBeforeId: null,
          hasMore: false
        }
      }
    })
    await expect(getSessionHistory('session-1')).rejects.toThrow(
      'Chat history message 0 is incomplete'
    )
  })

  it('uses a short independent timeout and abort signal for activity checks', async () => {
    vi.mocked(http.get).mockResolvedValue({
      data: { data: { sessionId: 'session-1', active: false } }
    })
    const controller = new AbortController()

    await getSessionActivity('session-1', { timeoutMs: 1500, signal: controller.signal })

    expect(http.get).toHaveBeenCalledWith(
      '/chat/sessions/session-1/activity',
      { timeout: 1500, signal: controller.signal }
    )
  })

  it('loads the identity and target of each pending protected action', async () => {
    vi.mocked(http.get).mockResolvedValue({
      data: { data: { sessionId: 'session-1', actions: [{ kind: 'DESTRUCTIVE', confirmationId: 'preview-1', toolName: 'delete_device', target: 'light-1' }] } }
    })
    const controller = new AbortController()

    await expect(getPendingConfirmation('session-1', controller.signal)).resolves.toEqual({
      sessionId: 'session-1',
      actions: [{ kind: 'DESTRUCTIVE', confirmationId: 'preview-1', toolName: 'delete_device', target: 'light-1' }]
    })
    expect(http.get).toHaveBeenCalledWith(
      '/chat/sessions/session-1/confirmation',
      { signal: controller.signal }
    )
  })

  it.each([
    null,
    'DESTRUCTIVE',
    [null],
    [{ kind: 'FUTURE_KIND', confirmationId: 'p', toolName: 'delete_device', target: 'X' }],
    [{ kind: 'DESTRUCTIVE', toolName: 'delete_device', target: 'X' }],
    [{ kind: 'DESTRUCTIVE', confirmationId: '', toolName: 'delete_device', target: 'X' }],
    [{ kind: 'DESTRUCTIVE', confirmationId: 'p', toolName: '', target: 'X' }],
    [{ kind: 'DESTRUCTIVE', confirmationId: 'p', toolName: 'delete_device', target: '' }],
    [
      { kind: 'DESTRUCTIVE', confirmationId: 'p', toolName: 'delete_device', target: 'X' },
      { kind: 'DESTRUCTIVE', confirmationId: 'q', toolName: 'delete_device', target: 'Y' }
    ]
  ])('rejects malformed pending protected actions: %j', async actions => {
    vi.mocked(http.get).mockResolvedValue({
      data: { data: { sessionId: 'session-1', actions } }
    })
    await expect(getPendingConfirmation('session-1')).rejects.toThrow(
      'Chat pending-confirmation response is incomplete'
    )
  })

  it('rejects the old kinds-only confirmation shape', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: { data: { sessionId: 'session-1', kinds: ['DESTRUCTIVE'] } } })
    await expect(getPendingConfirmation('session-1')).rejects.toThrow('Chat pending-confirmation response is incomplete')
  })

  it('sends the client turn id with an explicit stop request', async () => {
    vi.mocked(http.post).mockResolvedValue({ data: { data: null } })

    await requestSessionStop('session-1', 'turn-1')

    expect(http.post).toHaveBeenCalledWith(
      '/chat/sessions/session-1/stop',
      { turnId: 'turn-1' },
      { timeout: 2500 }
    )
  })

  it('uses a null turn id only when stopping a reattached remote execution', async () => {
    vi.mocked(http.post).mockResolvedValue({ data: { data: null } })

    await requestSessionStop('session-1')

    expect(http.post).toHaveBeenCalledWith(
      '/chat/sessions/session-1/stop',
      { turnId: null },
      { timeout: 2500 }
    )
  })

  it('includes the client turn id in the streaming request body', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      body: null
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn() },
      undefined,
      'turn-1'
    )).rejects.toMatchObject({ kind: 'MISSING_BODY' })

    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      sessionId: 'session-1',
      content: 'hello',
      turnId: 'turn-1',
      // The UI language must be declared, not inferred: the backend used to look for Han characters in `content`,
      // so a Chinese interface asking "hi" got English status prose. `toEqual` is exact here on purpose, so
      // dropping the field reddens this.
      locale: 'zh-CN'
    })
  })

  it('sends protected authority only through the structured confirmation field', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, body: null })
    vi.stubGlobal('fetch', fetchMock)

    await expect(sendStreamChat(
      'session-1',
      'I confirmed with the button',
      { onMessage: vi.fn() },
      undefined,
      'turn-2',
      { action: 'CONFIRM', kind: 'DESTRUCTIVE', confirmationId: 'preview-1' }
    )).rejects.toMatchObject({ kind: 'MISSING_BODY' })

    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
      sessionId: 'session-1',
      content: 'I confirmed with the button',
      turnId: 'turn-2',
      locale: 'zh-CN',
      confirmation: { action: 'CONFIRM', kind: 'DESTRUCTIVE', confirmationId: 'preview-1' }
    })
  })

  it('redirects an expired SSE session through the same login route as axios', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      statusText: 'Unauthorized',
      text: vi.fn().mockResolvedValue('{"message":"Authentication required"}')
    }))

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn() }
    )).rejects.toMatchObject({ status: 401 })

    expect(authMocks.logoutIfTokenMatches).toHaveBeenCalledWith(null)
    // `reason` is part of the shape now, and this test's own name is why it belongs here: the SSE transport must
    // redirect through *the same* route as axios. Both reach the login surface because a token was rejected, so
    // both say so — that is what lets the landing panel explain the ejection instead of leaving the user to
    // conclude the application lost their work.
    expect(routerMocks.push).toHaveBeenCalledWith({
      path: '/',
      query: { mode: 'login', redirect: '/board', reason: 'session-expired' }
    })
  })

  it('reports SSE authorization failures without logging the user out', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      statusText: 'Forbidden',
      text: vi.fn().mockResolvedValue('{"message":"Access denied"}')
    }))

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn() }
    )).rejects.toMatchObject({ status: 403 })

    expect(authMocks.logoutIfTokenMatches).not.toHaveBeenCalled()
    expect(routerMocks.push).not.toHaveBeenCalled()
  })

  it('does not let a delayed Alice SSE 401 redirect Bob to login', async () => {
    authMocks.getToken.mockReturnValue('alice-token')
    authMocks.logoutIfTokenMatches.mockReturnValue(false)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      statusText: 'Unauthorized',
      text: vi.fn().mockResolvedValue('{"message":"Authentication required"}')
    }))

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn() }
    )).rejects.toMatchObject({ status: 401 })

    expect(authMocks.logoutIfTokenMatches).toHaveBeenCalledWith('alice-token')
    expect(routerMocks.push).not.toHaveBeenCalled()
  })

  it('preserves a structured chat-history quota reason from an HTTP error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 429,
      statusText: 'Too Many Requests',
      text: vi.fn().mockResolvedValue(JSON.stringify({
        message: 'Conversation limit reached',
        data: { reasonCode: 'CHAT_HISTORY_LIMIT_REACHED' }
      }))
    }))

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn() }
    )).rejects.toMatchObject({
      kind: 'HTTP_ERROR',
      status: 429,
      reasonCode: 'CHAT_HISTORY_LIMIT_REACHED'
    })
  })

  it('preserves the configured per-user chat concurrency limit from an HTTP error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 429,
      statusText: 'Too Many Requests',
      text: vi.fn().mockResolvedValue(JSON.stringify({
        message: 'Too many chat operations',
        data: { reasonCode: 'USER_CHAT_OPERATION_BUSY', limit: 4 }
      }))
    }))

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn() }
    )).rejects.toMatchObject({
      kind: 'HTTP_ERROR',
      status: 429,
      reasonCode: 'USER_CHAT_OPERATION_BUSY',
      limit: 4
    })
  })

  it('classifies a missing response body without exposing its English parser message as UI text', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body: null }))
    const onError = vi.fn()
    const onAccepted = vi.fn()

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn(), onError, onAccepted }
    )).rejects.toMatchObject({ kind: 'MISSING_BODY' })

    expect(onAccepted).toHaveBeenCalledOnce()
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ kind: 'MISSING_BODY' }))
  })

  it('rejects a completed stream without its persisted terminal acknowledgement', async () => {
    const reader = { read: vi.fn().mockResolvedValue({ done: true, value: undefined }) }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      body: { getReader: () => reader }
    }))

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn() }
    )).rejects.toMatchObject({ kind: 'INCOMPLETE_STREAM' })
  })

  it('rejects malformed structured progress before it reaches the UI', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      'data: {"progress":{"stage":"FUTURE_STAGE"}}\n\n'
    )))
    const onProgress = vi.fn()
    const onError = vi.fn()

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn(), onProgress, onError }
    )).rejects.toMatchObject({ kind: 'INVALID_FRAME' })

    expect(onProgress).not.toHaveBeenCalled()
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ kind: 'INVALID_FRAME' }))
  })

  it('preserves exact reply-check diagnostics through streaming and saved history', async () => {
    const progress = {
      stage: 'CLAIM_REVIEW', outcome: 'REPLACED',
      claimReview: {
        reasonCode: 'MISSING_OPERATION_RECEIPT',
        originalText: ' 我已经删除所有设备。\n',
        matchedText: '我已经删除所有设备'
      }
    }
    const onProgress = vi.fn()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(chunkedStreamResponse(
      `data: ${JSON.stringify({ progress })}\n\n${terminalFrame()}`
    )))
    await sendStreamChat('session-1', '检查', { onMessage: vi.fn(), onProgress }, undefined, 'turn-1')
    expect(onProgress).toHaveBeenCalledWith(progress)
    vi.mocked(http.get).mockResolvedValue({ data: { code: 200, data: {
      messages: [{ id: 1, sessionId: 'session-1', turnId: 'turn-1', role: 'assistant', content: '请查看执行详情。',
        createdAt: '2026-10-14T00:00:00', executionStatus: 'PARTIAL', executionTrace: [progress] }],
      nextBeforeId: null, hasMore: false
    } } })
    const history = await getSessionHistory('session-1')
    expect(history.messages[0]?.executionTrace).toEqual([progress])
  })

  it.each([
    { reasonCode: 'UNKNOWN', originalText: '我已修改画布。', matchedText: '我已修改画布' },
    { reasonCode: 'MISSING_OPERATION_RECEIPT', originalText: '我已修改画布。', matchedText: '不存在的片段' },
    { reasonCode: 'MISSING_OPERATION_RECEIPT', originalText: '', matchedText: '我已修改画布' },
    { reasonCode: 'MISSING_OPERATION_RECEIPT', originalText: '我已修改画布。' },
    null
  ])('rejects incomplete or contradictory reply-check diagnostics: %j', async claimReview => {
    const onProgress = vi.fn()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      `data: ${JSON.stringify({ progress: { stage: 'CLAIM_REVIEW', outcome: 'REPLACED', claimReview } })}\n\n`
    )))
    await expect(sendStreamChat('session-1', '检查', { onMessage: vi.fn(), onProgress }))
      .rejects.toMatchObject({ kind: 'INVALID_FRAME' })
    expect(onProgress).not.toHaveBeenCalled()
  })

  it('requires an outcome that matches each progress stage', async () => {
    const invalidPayloads = [
      { progress: { stage: 'TOOL_RESULT' } },
      { progress: { stage: 'TOOL_RESULT', outcome: 'EMERGENCY_LIMIT' } },
      { progress: { stage: 'EXECUTION_GUARD', outcome: 'USABLE' } },
      { progress: { stage: 'PLANNING', outcome: 'USABLE' } }
    ]

    for (const payload of invalidPayloads) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
        `data: ${JSON.stringify(payload)}\n\n`
      )))
      await expect(sendStreamChat(
        'session-1',
        'hello',
        { onMessage: vi.fn(), onProgress: vi.fn() }
      )).rejects.toMatchObject({ kind: 'INVALID_FRAME' })
    }
  })

  it('rejects empty, non-object, and non-JSON stream frames without completing', async () => {
    const invalidPayloads = ['{}', '{"content":42}', '[]', 'plain assistant text']

    for (const payload of invalidPayloads) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
        `data: ${payload}\n\n`
      )))
      const onFinish = vi.fn()
      await expect(sendStreamChat(
        'session-1',
        'hello',
        { onMessage: vi.fn(), onFinish }
      )).rejects.toMatchObject({ kind: 'INVALID_FRAME' })
      expect(onFinish).not.toHaveBeenCalled()
    }
  })

  it('validates the complete structured frame before invoking any callback', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      'data: {"command":{"type":"REFRESH_DATA"},"progress":{"stage":"TOOL_RESULT"}}\n\n'
    )))
    const onCommand = vi.fn()

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn(), onCommand }
    )).rejects.toMatchObject({ kind: 'INVALID_FRAME' })
    expect(onCommand).not.toHaveBeenCalled()
  })

  it('accepts only documented refresh commands and targets', async () => {
    for (const command of [
      { type: 'NAVIGATE', payload: { target: 'board_state' } },
      { type: 'REFRESH_DATA', payload: { target: 'future_target' } },
      { type: 'REFRESH_DATA', payload: { target: 'board_state', extra: true } },
      { type: 'REFRESH_DATA', payload: { target: 'board_state', assistantAction: 'FUTURE_ACTION' } },
      { type: 'REFRESH_DATA', payload: { target: 'board_state', assistantSummary: '   ' } },
      { type: 'REFRESH_DATA', payload: { target: 'board_state', assistantSummary: 'x'.repeat(241) } }
    ]) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
        `data: ${JSON.stringify({ command })}\n\n`
      )))
      await expect(sendStreamChat(
        'session-1',
        'hello',
        { onMessage: vi.fn(), onCommand: vi.fn() }
      )).rejects.toMatchObject({ kind: 'INVALID_FRAME' })
    }

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      'data: {"command":{"type":"REFRESH_DATA","payload":{"target":"board_state"}}}\n\n'
      + terminalFrame()
    )))
    const onCommand = vi.fn()
    await sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn(), onCommand },
      undefined,
      'turn-1'
    )
    expect(onCommand).toHaveBeenCalledWith({
      type: 'REFRESH_DATA',
      payload: { target: 'board_state' }
    })

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      'data: {"command":{"type":"REFRESH_DATA","payload":{"target":"run_history","assistantAction":"FORMAL_VERIFICATION_RUN"}}}\n\n'
      + terminalFrame('turn-2')
    )))
    await sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn(), onCommand },
      undefined,
      'turn-2'
    )
    expect(onCommand).toHaveBeenLastCalledWith({
      type: 'REFRESH_DATA',
      payload: { target: 'run_history', assistantAction: 'FORMAL_VERIFICATION_RUN' }
    })

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      'data: {"command":{"type":"REFRESH_DATA","payload":{"target":"board_state","assistantAction":"BOARD_CLEARED","assistantSummary":"Cleared 2 devices and 3 rules."}}}\n\n'
      + terminalFrame('turn-3')
    )))
    await sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn(), onCommand },
      undefined,
      'turn-3'
    )
    expect(onCommand).toHaveBeenLastCalledWith({
      type: 'REFRESH_DATA',
      payload: {
        target: 'board_state',
        assistantAction: 'BOARD_CLEARED',
        assistantSummary: 'Cleared 2 devices and 3 rules.'
      }
    })

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      'data: {"command":{"type":"REFRESH_DATA","payload":{"target":"board_state","assistantSummary":"Cleared 4 undo/redo entries; current Board data did not change."}}}\n\n'
      + terminalFrame('turn-4')
    )))
    await sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn(), onCommand },
      undefined,
      'turn-4'
    )
    expect(onCommand).toHaveBeenLastCalledWith({
      type: 'REFRESH_DATA',
      payload: {
        target: 'board_state',
        assistantSummary: 'Cleared 4 undo/redo entries; current Board data did not change.'
      }
    })
  })

  it('accepts a complete structured progress frame', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      'data: {"progress":{"stage":"TOOL_EXECUTION","toolName":"get_board","round":1,"successfulSteps":0,"failedSteps":0,"unconfirmedSteps":0}}\n\n'
      + 'data: {"progress":{"stage":"TOOL_RESULT","toolName":"get_board","round":1,"outcome":"USABLE","successfulSteps":1,"failedSteps":0,"unconfirmedSteps":0}}\n\n'
      + terminalFrame('turn-1', 'COMPLETED')
    )))
    const onProgress = vi.fn()
    const onFinish = vi.fn()

    await sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn(), onProgress, onFinish },
      undefined,
      'turn-1'
    )

    expect(onProgress).toHaveBeenCalledWith(expect.objectContaining({
      stage: 'TOOL_RESULT',
      outcome: 'USABLE',
      successfulSteps: 1
    }))
    expect(onFinish).toHaveBeenCalledWith({
      turnId: 'turn-1',
      executionStatus: 'COMPLETED'
    })
  })

  it('accepts the documented partial tool-result outcome', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      'data: {"progress":{"stage":"TOOL_RESULT","outcome":"PARTIAL","successfulSteps":1,"failedSteps":0,"unconfirmedSteps":0}}\n\n'
      + terminalFrame()
    )))
    const onProgress = vi.fn()

    await sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn(), onProgress },
      undefined,
      'turn-1'
    )

    expect(onProgress).toHaveBeenCalledWith(expect.objectContaining({ outcome: 'PARTIAL' }))
  })

  it('treats clean EOF after ordinary frames as an incomplete accepted turn', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      'data: {"content":"partial reply"}\n\n'
    )))
    const onMessage = vi.fn()
    const onFinish = vi.fn()

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage, onFinish },
      undefined,
      'turn-1'
    )).rejects.toMatchObject({ kind: 'INCOMPLETE_STREAM' })

    expect(onMessage).toHaveBeenCalledWith('partial reply')
    expect(onFinish).not.toHaveBeenCalled()
  })

  it.each([false, true])('reports incomplete EOF only to the current request owner: %s', async remainsCurrent => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      'data: {"content":"partial reply"}\n\n'
    )))
    const controller = new AbortController()
    const onFinish = vi.fn()
    const onError = vi.fn()
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    let current = true

    try {
      await expect(sendStreamChat(
        'session-1',
        'hello',
        {
          isCurrentRequest: () => current,
          onMessage: () => { current = remainsCurrent },
          onError,
          onFinish
        },
        controller,
        'turn-1'
      )).rejects.toMatchObject({ kind: 'INCOMPLETE_STREAM' })

      expect(controller.signal.aborted).toBe(false)
      expect(onFinish).not.toHaveBeenCalled()
      expect(onError).toHaveBeenCalledTimes(remainsCurrent ? 1 : 0)
      expect(consoleError).toHaveBeenCalledTimes(remainsCurrent ? 1 : 0)
    } finally {
      consoleError.mockRestore()
    }
  })

  it('reads a persisted failed terminal before reporting the server error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      'data: {"error":"provider unavailable"}\n\n'
      + terminalFrame('turn-1', 'FAILED')
    )))
    const onMessage = vi.fn()
    const onError = vi.fn()
    const onFinish = vi.fn()

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage, onError, onFinish },
      undefined,
      'turn-1'
    )).rejects.toMatchObject({ kind: 'SERVER_FRAME', serverFrame: true })

    expect(onMessage).not.toHaveBeenCalled()
    expect(onFinish).toHaveBeenCalledWith({ turnId: 'turn-1', executionStatus: 'FAILED' })
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ kind: 'SERVER_FRAME' }))
  })

  it('rejects a completed terminal that contradicts a preceding server error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      'data: {"progress":{"stage":"TOOL_EXECUTION","toolName":"board_overview","round":1,"successfulSteps":0,"failedSteps":0,"unconfirmedSteps":0}}\n\n'
      + 'data: {"progress":{"stage":"TOOL_RESULT","toolName":"board_overview","round":1,"outcome":"USABLE","successfulSteps":1,"failedSteps":0,"unconfirmedSteps":0}}\n\n'
      + 'data: {"error":"final response failed"}\n\n'
      + terminalFrame('turn-1', 'COMPLETED')
    )))
    const onError = vi.fn()
    const onFinish = vi.fn()

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn(), onError, onFinish },
      undefined,
      'turn-1'
    )).rejects.toMatchObject({ kind: 'INVALID_FRAME' })

    expect(onFinish).not.toHaveBeenCalled()
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ kind: 'INVALID_FRAME' }))
  })

  it('does not invent a terminal acknowledgement for an unpersisted server error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      'data: {"error":"terminal record could not be saved"}\n\n'
    )))
    const onFinish = vi.fn()

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn(), onFinish },
      undefined,
      'turn-1'
    )).rejects.toMatchObject({ kind: 'SERVER_FRAME', serverFrame: true })

    expect(onFinish).not.toHaveBeenCalled()
  })

  it('preserves a parsed server error when the connection resets before its terminal frame', async () => {
    const reader = {
      read: vi.fn()
        .mockResolvedValueOnce({
          done: false,
          value: new TextEncoder().encode('data: {"error":"provider unavailable"}\n\n')
        })
        .mockRejectedValueOnce(new TypeError('connection reset'))
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      body: { getReader: () => reader }
    }))
    const onError = vi.fn()
    const onFinish = vi.fn()

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn(), onError, onFinish },
      undefined,
      'turn-1'
    )).rejects.toMatchObject({
      kind: 'SERVER_FRAME',
      serverFrame: true,
      message: 'provider unavailable'
    })

    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ kind: 'SERVER_FRAME' }))
    expect(onFinish).not.toHaveBeenCalled()
  })

  it('rejects ordinary data after a server error frame', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      'data: {"error":"provider unavailable"}\n\n'
      + 'data: {"content":"late reply"}\n\n'
      + terminalFrame('turn-1', 'FAILED')
    )))

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn() },
      undefined,
      'turn-1'
    )).rejects.toMatchObject({ kind: 'INVALID_FRAME' })
  })

  it('treats model text beginning with the former error marker as ordinary content', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      'data: {"progress":{"stage":"TOOL_EXECUTION","toolName":"board_overview","round":1,"successfulSteps":0,"failedSteps":0,"unconfirmedSteps":0}}\n\n'
      + 'data: {"progress":{"stage":"TOOL_RESULT","toolName":"board_overview","round":1,"outcome":"USABLE","successfulSteps":1,"failedSteps":0,"unconfirmedSteps":0}}\n\n'
      + 'data: {"content":"[ERROR] is a literal marker in this explanation"}\n\n'
      + terminalFrame('turn-1', 'COMPLETED')
    )))
    const onMessage = vi.fn()
    const onError = vi.fn()
    const onFinish = vi.fn()

    await sendStreamChat(
      'session-1',
      'explain the marker',
      { onMessage, onError, onFinish },
      undefined,
      'turn-1'
    )

    expect(onMessage).toHaveBeenCalledWith('[ERROR] is a literal marker in this explanation')
    expect(onError).not.toHaveBeenCalled()
    expect(onFinish).toHaveBeenCalledWith({ turnId: 'turn-1', executionStatus: 'COMPLETED' })
  })

  it('rejects terminal frames with the wrong turn, status, or shape', async () => {
    const invalidTerminals = [
      { turnId: 'other-turn', executionStatus: 'PARTIAL' },
      { turnId: 'turn-1', executionStatus: 'FUTURE_STATUS' },
      { turnId: 'turn-1' },
      { turnId: 'turn-1', executionStatus: 'PARTIAL', extra: true }
    ]

    for (const terminal of invalidTerminals) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
        `data: ${JSON.stringify({ terminal })}\n\n`
      )))
      await expect(sendStreamChat(
        'session-1',
        'hello',
        { onMessage: vi.fn() },
        undefined,
        'turn-1'
      )).rejects.toMatchObject({ kind: 'INVALID_FRAME' })
    }
  })

  it('requires one unique terminal payload as the final data frame', async () => {
    const invalidStreams = [
      `data: ${JSON.stringify({
        content: 'mixed',
        terminal: { turnId: 'turn-1', executionStatus: 'PARTIAL' }
      })}\n\n`,
      terminalFrame() + 'data: {"content":"late"}\n\n',
      terminalFrame() + terminalFrame()
    ]

    for (const payload of invalidStreams) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(payload)))
      await expect(sendStreamChat(
        'session-1',
        'hello',
        { onMessage: vi.fn() },
        undefined,
        'turn-1'
      )).rejects.toMatchObject({ kind: 'INVALID_FRAME' })
    }
  })

  it('does not accept completed terminal status without observed usable tool evidence', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      'data: {"progress":{"stage":"PLANNING","round":1}}\n\n'
      + terminalFrame('turn-1', 'COMPLETED')
    )))

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn() },
      undefined,
      'turn-1'
    )).rejects.toMatchObject({ kind: 'INVALID_FRAME' })
  })

  it('accepts completed evidence when a later round explicitly corrects the same partial tool', async () => {
    const trace = [
      { stage: 'TOOL_EXECUTION', toolName: 'recommend_scenario', round: 1 },
      { stage: 'TOOL_RESULT', toolName: 'recommend_scenario', round: 1, outcome: 'PARTIAL' },
      { stage: 'TOOL_EXECUTION', toolName: 'recommend_scenario', round: 2 },
      { stage: 'TOOL_RESULT', toolName: 'recommend_scenario', round: 2, outcome: 'USABLE' }
    ]
    const progressFrames = trace
      .map(progress => `data: ${JSON.stringify({ progress })}\n\n`)
      .join('')
    const onFinish = vi.fn()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      progressFrames + terminalFrame('turn-1', 'COMPLETED')
    )))

    await expect(sendStreamChat(
      'session-1',
      'create a complete scene',
      { onMessage: vi.fn(), onFinish },
      undefined,
      'turn-1'
    )).resolves.toBeUndefined()

    expect(onFinish).toHaveBeenCalledWith({ turnId: 'turn-1', executionStatus: 'COMPLETED' })
  })

  it('rejects completed evidence with unresolved partial, failed, or unavailable tool results', async () => {
    const unresolvedTraces = [
      [
        { stage: 'TOOL_EXECUTION', toolName: 'recommend_scenario', round: 1 },
        { stage: 'TOOL_RESULT', toolName: 'recommend_scenario', round: 1, outcome: 'PARTIAL' },
        { stage: 'TOOL_EXECUTION', toolName: 'board_overview', round: 2 },
        { stage: 'TOOL_RESULT', toolName: 'board_overview', round: 2, outcome: 'USABLE' }
      ],
      [
        { stage: 'TOOL_EXECUTION', toolName: 'board_overview', round: 1 },
        { stage: 'TOOL_RESULT', toolName: 'board_overview', round: 1, outcome: 'FAILED' },
        { stage: 'TOOL_EXECUTION', toolName: 'board_overview', round: 2 },
        { stage: 'TOOL_RESULT', toolName: 'board_overview', round: 2, outcome: 'USABLE' }
      ],
      [
        { stage: 'TOOL_EXECUTION', toolName: 'board_overview', round: 1 },
        { stage: 'TOOL_RESULT', toolName: 'board_overview', round: 1, outcome: 'RESULT_UNAVAILABLE' },
        { stage: 'TOOL_EXECUTION', toolName: 'board_overview', round: 2 },
        { stage: 'TOOL_RESULT', toolName: 'board_overview', round: 2, outcome: 'USABLE' }
      ],
      [
        { stage: 'TOOL_EXECUTION', toolName: 'recommend_scenario', round: 1 },
        { stage: 'TOOL_RESULT', toolName: 'recommend_scenario', round: 1, outcome: 'PARTIAL' },
        { stage: 'TOOL_EXECUTION', toolName: 'recommend_scenario', round: 2 },
        { stage: 'TOOL_RESULT', toolName: 'recommend_scenario', round: 2, outcome: 'USABLE' },
        { stage: 'TOOL_EXECUTION', toolName: 'recommend_scenario', round: 3 },
        { stage: 'TOOL_RESULT', toolName: 'recommend_scenario', round: 3, outcome: 'PARTIAL' }
      ]
    ]

    for (const trace of unresolvedTraces) {
      const progressFrames = trace
        .map(progress => `data: ${JSON.stringify({ progress })}\n\n`)
        .join('')
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
        progressFrames + terminalFrame('turn-1', 'COMPLETED')
      )))

      await expect(sendStreamChat(
        'session-1',
        'hello',
        { onMessage: vi.fn() },
        undefined,
        'turn-1'
      )).rejects.toMatchObject({ kind: 'INVALID_FRAME' })
    }
  })

  it('rejects completed terminal status when execution and result evidence do not pair', async () => {
    const malformedTraces = [
      [
        { stage: 'TOOL_EXECUTION', toolName: 'board_overview', round: 1 },
        { stage: 'TOOL_RESULT', toolName: 'list_rules', round: 1, outcome: 'USABLE' }
      ],
      [
        { stage: 'TOOL_EXECUTION', toolName: 'board_overview', round: 1 },
        { stage: 'TOOL_RESULT', toolName: 'board_overview', round: 2, outcome: 'USABLE' }
      ],
      [
        { stage: 'TOOL_RESULT', toolName: 'board_overview', round: 1, outcome: 'USABLE' }
      ]
    ]

    for (const trace of malformedTraces) {
      const progressFrames = trace
        .map(progress => `data: ${JSON.stringify({ progress })}\n\n`)
        .join('')
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
        progressFrames + terminalFrame('turn-1', 'COMPLETED')
      )))

      await expect(sendStreamChat(
        'session-1',
        'hello',
        { onMessage: vi.fn() },
        undefined,
        'turn-1'
      )).rejects.toMatchObject({ kind: 'INVALID_FRAME' })
    }
  })

  it('rejects null sibling payload fields instead of treating them as absent', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(successfulStreamResponse(
      `data: ${JSON.stringify({
        content: null,
        command: null,
        progress: null,
        terminal: { turnId: 'turn-1', executionStatus: 'PARTIAL' }
      })}\n\n`
    )))
    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn() },
      undefined,
      'turn-1'
    )).rejects.toMatchObject({ kind: 'INVALID_FRAME' })
  })

  it('keeps a persisted terminal success when the transport resets during close', async () => {
    const closeError = new TypeError('connection reset')
    const reader = {
      read: vi.fn()
        .mockResolvedValueOnce({
          done: false,
          value: new TextEncoder().encode(terminalFrame())
        })
        .mockRejectedValueOnce(closeError)
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      body: { getReader: () => reader }
    }))
    const onError = vi.fn()
    const onFinish = vi.fn()

    await sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn(), onError, onFinish },
      undefined,
      'turn-1'
    )

    expect(onError).not.toHaveBeenCalled()
    expect(onFinish).toHaveBeenCalledWith({ turnId: 'turn-1', executionStatus: 'PARTIAL' })
  })

  it('reports transport acceptance before parsing a successful stream', async () => {
    const reader = { read: vi.fn().mockResolvedValue({ done: true, value: undefined }) }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      body: { getReader: () => reader }
    }))
    const onAccepted = vi.fn()

    await expect(sendStreamChat(
      'session-1',
      'hello',
      { onMessage: vi.fn(), onAccepted }
    )).rejects.toMatchObject({ kind: 'INCOMPLETE_STREAM' })

    expect(onAccepted).toHaveBeenCalledOnce()
  })
})

describe('chat session list contract', () => {
  const session = (over: Record<string, unknown> = {}) => ({
    id: 'session-1',
    userId: 7,
    title: null,
    updatedAt: '2026-07-28T00:00:00Z',
    active: false,
    latestTerminalMessageId: null,
    latestExecutionStatus: null,
    hasUnreadUpdate: false,
    ...over
  })

  it('accepts a complete session list', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: { data: [
      session(),
      session({ id: 's2', active: true }),
      session({
        id: 's3',
        latestTerminalMessageId: 42,
        latestExecutionStatus: 'COMPLETED',
        hasUnreadUpdate: true
      })
    ] } })

    const sessions = await getSessionList()

    expect(sessions.map(s => s.active)).toEqual([false, true, false])
    expect(sessions[2].latestTerminalMessageId).toBe(42)
  })

  it('rejects a row missing `active` instead of reading it as idle', async () => {
    // `active` gates isAssistantBusy, which stops a second assistant mutation while one is still
    // running server-side. Absent -> undefined -> falsy would silently unlock that action, so the
    // row must be refused rather than defaulted.
    const { active: _dropped, ...withoutActive } = session()
    vi.mocked(http.get).mockResolvedValue({ data: { data: [withoutActive] } })

    await expect(getSessionList()).rejects.toThrow(/incomplete/)
  })

  it('rejects a non-boolean `active`', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: { data: [session({ active: 'true' })] } })

    await expect(getSessionList()).rejects.toThrow(/incomplete/)
  })

  it('rejects missing or contradictory persisted result visibility fields', async () => {
    const { hasUnreadUpdate: _dropped, ...withoutUnread } = session()
    vi.mocked(http.get).mockResolvedValue({ data: { data: [withoutUnread] } })
    await expect(getSessionList()).rejects.toThrow(/incomplete/)

    vi.mocked(http.get).mockResolvedValue({ data: { data: [session({
      latestTerminalMessageId: 42,
      latestExecutionStatus: null,
      hasUnreadUpdate: true
    })] } })
    await expect(getSessionList()).rejects.toThrow(/incomplete/)

    vi.mocked(http.get).mockResolvedValue({ data: { data: [session({
      latestTerminalMessageId: null,
      latestExecutionStatus: 'FAILED'
    })] } })
    await expect(getSessionList()).rejects.toThrow(/incomplete/)
  })

  it('acknowledges the exact terminal message rendered for a session', async () => {
    vi.mocked(http.post).mockResolvedValue({ data: { data: null } })

    await markSessionTerminalSeen('session/one', 42)

    expect(http.post).toHaveBeenCalledWith(
      '/chat/sessions/session%2Fone/seen',
      { terminalMessageId: 42 }
    )
  })

  it('rejects a list payload that is not an array', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: { data: { sessions: [] } } })

    await expect(getSessionList()).rejects.toThrow(/incomplete/)
  })

  it('validates a newly created session the same way', async () => {
    vi.mocked(http.post).mockResolvedValue({ data: { data: session({ id: 'fresh' }) } })
    await expect(createSession()).resolves.toMatchObject({ id: 'fresh', active: false })

    const { active: _dropped, ...withoutActive } = session()
    vi.mocked(http.post).mockResolvedValue({ data: { data: withoutActive } })
    await expect(createSession()).rejects.toThrow(/incomplete/)
  })
})
