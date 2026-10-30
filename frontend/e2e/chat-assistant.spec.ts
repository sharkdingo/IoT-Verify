import { type APIRequestContext, type Page } from '@playwright/test'
import {
  apiBaseURL,
  createAuthenticatedUser,
  expect,
  test,
  type AuthUser
} from './support/auth'
import type { PersistedChatMessage, StreamCommand, StreamProgress } from '../src/types/chat'
import { collapseChatSidebar } from './support/chatSidebar'

const unwrap = async <T>(response: Awaited<ReturnType<APIRequestContext['get']>>): Promise<T> => {
  expect(response.ok(), await response.text()).toBeTruthy()
  const body = await response.json()
  expect(body.code, JSON.stringify(body)).toBe(200)
  return body.data as T
}

const openWorkspace = async (page: Page, auth: AuthUser, theme: 'light' | 'dark' = 'light') => {
  await page.addInitScript(({ token, user, themeMode }) => {
    window.localStorage.setItem('iot_verify_token', token)
    window.localStorage.setItem('iot_verify_user', JSON.stringify(user))
    window.localStorage.setItem('iot_verify_theme', themeMode)
    window.localStorage.setItem('locale', 'zh-CN')
  }, {
    token: auth.token,
    themeMode: theme,
    user: {
      userId: auth.userId,
      phone: auth.phone,
      username: auth.username
    }
  })

  await page.goto('/#/board')
  await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
}

for (const { name, theme, viewport } of [
  { name: 'narrow dark', theme: 'dark' as const, viewport: { width: 390, height: 760 } }
]) {
  test(`excludes collapsed assistant history from keyboard and reading order (${name})`, async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    const session = await unwrap<{ id: string }>(await request.post(`${apiBaseURL}/api/chat/sessions`, {
      headers: { Authorization: `Bearer ${auth.token}` }
    }))
    await page.setViewportSize(viewport)
    await openWorkspace(page, auth, theme)
    await page.getByTestId('open-ai-assistant').click()
    const panel = page.getByTestId('chat-panel')
    const toggle = page.getByTestId('chat-sidebar-toggle')
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await expect(toggle).toBeFocused()
    await expect(panel.getByRole('button', { name: '新对话', exact: true })).toHaveCount(0)
    await page.keyboard.press('Tab')
    expect(await page.evaluate(() => !!document.activeElement?.closest('.sidebar'))).toBe(false)

    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await expect(panel.getByRole('button', { name: '新对话', exact: true })).toBeVisible()
    const history = page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname === `/api/chat/sessions/${session.id}/messages`)
    await page.getByTestId(`chat-session-${session.id}`).click()
    expect((await history).ok()).toBe(true)
    await expect(page.getByTestId('chat-input')).toBeEnabled()
    if (await toggle.getAttribute('aria-expanded') === 'true') await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await expect(toggle).toBeFocused()
    await expect(panel.getByRole('button', { name: '新对话', exact: true })).toHaveCount(0)
    await page.getByTestId('chat-close').click()
    await expect(panel).toBeHidden()
    await page.getByTestId('open-ai-assistant').click()
    await expect(toggle).toBeFocused()
  })
}

const installPersistedPartialChatMock = async (
  page: Page,
  sessionId: string,
  assistantContent: string,
  beforeResponse?: () => Promise<void>,
  errorMessage?: string,
  command?: StreamCommand
) => {
  let messages: PersistedChatMessage[] = []

  await page.route(`**/api/chat/sessions/${sessionId}/messages**`, async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json; charset=UTF-8',
      body: JSON.stringify({
        code: 200,
        data: { messages, nextBeforeId: null, hasMore: false }
      })
    })
  })
  await page.route('**/api/chat/completions', async route => {
    await beforeResponse?.()
    const request = route.request().postDataJSON() as {
      sessionId: string
      content: string
      turnId: string
    }
    expect(request.sessionId).toBe(sessionId)
    expect(request.turnId.trim()).not.toBe('')
    const executionTrace: StreamProgress[] = [
      { stage: 'CONTEXT_READY' },
      { stage: 'PLANNING', round: 1 },
      { stage: 'WRITING_RESPONSE', round: 1 }
    ]
    const createdAt = new Date().toISOString()
    messages = [
      {
        id: 1,
        sessionId,
        role: 'user',
        content: request.content,
        turnId: request.turnId,
        createdAt
      },
      {
        id: 2,
        sessionId,
        role: 'assistant',
        content: assistantContent,
        turnId: request.turnId,
        createdAt,
        executionStatus: 'PARTIAL',
        executionTrace
      }
    ]
    const frames = [
      ...executionTrace.map(progress => ({ progress })),
      ...(command ? [{ command }] : []),
      errorMessage ? { error: errorMessage } : { content: assistantContent },
      { terminal: { turnId: request.turnId, executionStatus: 'PARTIAL' } }
    ]
    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream; charset=UTF-8',
      body: `${frames.map(frame => `data: ${JSON.stringify(frame)}`).join('\n\n')}\n\n`
    })
  })
}

test('renders CJK Markdown and literal code consistently after streaming and history reload', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  const session = await unwrap<{ id: string }>(await request.post(`${apiBaseURL}/api/chat/sessions`, {
    headers: { Authorization: `Bearer ${auth.token}` }
  }))
  const markdown = [
    '场景**“家庭空气质量与舒适度联动”已成功原子替换并应用**。',
    '注意*“尚未验证”*。删除~~“旧规则”~~后继续。',
    String.raw`温度为\(x_1 + 2\)。`,
    '```echarts {}\n{"title":"场景**“测试”**"}\n```',
    '```component-json {}\n{"status":"ok"}\n```',
    '~~~text\n' + String.raw`\(x_1\) 场景**“测试”**` + '\n~~~',
    '参考**[unsafe](javascript:alert(1))**。'
  ].join('\n\n')
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await installPersistedPartialChatMock(page, session.id, markdown)
  await openWorkspace(page, auth, 'dark')
  const openSession = async () => {
    await page.getByTestId('open-ai-assistant').click()
    const toggle = page.getByTestId('chat-sidebar-toggle')
    if (await toggle.getAttribute('aria-expanded') === 'false') await toggle.click()
    await page.getByTestId(`chat-session-${session.id}`).click()
  }
  const expectRendered = async () => {
    const reply = page.locator('.ai-row').last()
    await expect(reply.locator('strong').filter({ hasText: '“家庭空气质量与舒适度联动”已成功原子替换并应用' }))
      .toHaveText('“家庭空气质量与舒适度联动”已成功原子替换并应用')
    await expect(reply.locator('em')).toHaveText('“尚未验证”')
    await expect(reply.locator('del')).toHaveText('“旧规则”')
    await expect(reply.locator('annotation')).toHaveText('x_1 + 2')
    await expect(reply.locator('.code-content')).toHaveText([
      '{"title":"场景**“测试”**"}', '{"status":"ok"}', String.raw`\(x_1\) 场景**“测试”**`
    ])
    await expect(reply.getByText('unsafe', { exact: true })).not.toHaveAttribute('href')
    await expect(page.getByTestId('chat-input')).toBeEnabled()
    expect(errors).toEqual([])
  }
  await openSession()
  await page.getByTestId('chat-input').fill('展示格式检查结果')
  await page.getByTestId('chat-send').click()
  await expectRendered()
  await page.reload()
  await openSession()
  await expectRendered()
})

test('reopens the assistant after a provider error and failed Board synchronization', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  const session = await unwrap<{ id: string }>(await request.post(`${apiBaseURL}/api/chat/sessions`, {
    headers: { Authorization: `Bearer ${auth.token}` }
  }))
  let snapshotUnavailable = false
  await page.route('**/api/board/snapshot', async route => {
    if (!snapshotUnavailable) return route.continue()
    await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({
      code: 503, message: 'Snapshot unavailable'
    }) })
  })
  const failure = 'AI 服务暂时不可用，请稍后重试。'
  await installPersistedPartialChatMock(page, session.id, failure,
    async () => { snapshotUnavailable = true }, failure)
  await openWorkspace(page, auth)
  await page.getByTestId('open-ai-assistant').click()
  await page.getByTestId('chat-sidebar-toggle').click()
  await page.getByTestId(`chat-session-${session.id}`).click()
  await page.getByTestId('chat-input').fill('请整理设备触发链路')
  await page.getByTestId('chat-send').click()
  await expect(page.getByTestId('chat-reconciliation-retry')).toBeVisible()
  await expect(page.getByTestId('chat-input')).toBeDisabled()
  await page.getByTestId('chat-close').click()
  await expect(page.getByTestId('open-ai-assistant')).toBeEnabled()
  await page.getByTestId('open-ai-assistant').click()
  await expect(page.getByTestId('chat-panel')).toBeVisible()
  await expect(page.getByTestId('chat-reconciliation-retry')).toBeVisible()
  snapshotUnavailable = false
  await page.getByTestId('chat-reconciliation-retry').click()
  await expect(page.getByTestId('chat-reconciliation-required')).toHaveCount(0)
  await expect(page.getByTestId('chat-input')).toBeEnabled()
  await expect(page.getByTestId('chat-panel').getByText(failure, { exact: true })).toBeVisible()
})

for (const { width, theme } of [{ width: 320, theme: 'dark' }] as const) {
  test(`keeps assistant controls legible during a pending reply at ${width}px in ${theme}`, async ({ page, request }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    const auth = await createAuthenticatedUser(request)
    const session = await unwrap<{ id: string }>(await request.post(`${apiBaseURL}/api/chat/sessions`, {
      headers: { Authorization: `Bearer ${auth.token}` }
    }))
    let releaseReply!: () => void
    const replyPending = new Promise<void>(resolve => { releaseReply = resolve })
    await installPersistedPartialChatMock(page, session.id, 'Reply received.', () => replyPending)
    await openWorkspace(page, auth, theme)
    await page.getByTestId('open-ai-assistant').click()
    const panel = page.getByTestId('chat-panel')
    await page.getByTestId('chat-sidebar-toggle').click()
    const sessionRow = page.getByTestId(`chat-session-${session.id}`)
    const deleteButton = sessionRow.locator('..').locator('.delete-btn-wrapper')
    await expect(deleteButton).toHaveCSS('opacity', '1')
    await expect.poll(() => deleteButton.evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThanOrEqual(44)
    await sessionRow.click()
    const sidebarScrim = page.getByTestId('chat-sidebar-scrim')
    if (await sidebarScrim.isVisible()) await collapseChatSidebar(page)
    const tasks = panel.getByTestId('chat-preset-task')
    const task = tasks.first()
    await page.getByTestId('chat-close').press('Tab')
    const resize = page.getByTestId('chat-resize-handle')
    if (await resize.isVisible()) await resize.press('Tab')
    const guidance = panel.getByTestId('chat-guided-goals')
    const summary = guidance.locator('summary')
    await expect(guidance).not.toHaveAttribute('open')
    await expect(panel.getByTestId('household-want')).toBeHidden()
    await expect(tasks).toHaveCount(4)
    await expect.poll(() => panel.evaluate(element => element.getAnimations({ subtree: true })
      .filter(animation => animation.playState === 'running').length)).toBe(0)
    await page.screenshot({ path: testInfo.outputPath('assistant-home.png') })
    for (const suggestion of await tasks.all()) {
      await expect(suggestion).toBeFocused()
      await expect(suggestion).toHaveCSS('outline-width', '2px')
      await expect(suggestion).toHaveCSS('outline-style', 'solid')
      await expect.poll(() => suggestion.evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44)
      await suggestion.press('Tab')
    }
    await expect(summary).toBeFocused()
    await summary.press('Enter')
    await expect(guidance).toHaveAttribute('open')
    await summary.press('Tab')
    await expect(panel.getByTestId('household-draft')).toBeDisabled()
    for (const name of ['希望发生什么', '哪些事情不能发生（选填）', '修改后仍要保留什么（选填）']) {
      const goalField = panel.getByRole('textbox', { name, exact: true })
      await expect(goalField).toBeFocused()
      await expect(goalField).toHaveCSS('outline-width', '2px')
      await expect(goalField).toHaveCSS('outline-style', 'solid')
      await goalField.press('Tab')
    }
    const input = page.getByTestId('chat-input')
    await task.click()
    await expect(input).toBeFocused()
    await expect(input).not.toHaveValue('')
    await expect.poll(() => input.evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44)
    await input.fill('show status')
    if (await resize.isVisible()) {
      const handleBounds = await resize.boundingBox()
      const sendBounds = await page.getByTestId('chat-send').boundingBox()
      expect(handleBounds).not.toBeNull()
      expect(sendBounds).not.toBeNull()
      const overlapWidth = Math.max(0, Math.min(handleBounds!.x + handleBounds!.width, sendBounds!.x + sendBounds!.width) - Math.max(handleBounds!.x, sendBounds!.x))
      const overlapHeight = Math.max(0, Math.min(handleBounds!.y + handleBounds!.height, sendBounds!.y + sendBounds!.height) - Math.max(handleBounds!.y, sendBounds!.y))
      expect(overlapWidth * overlapHeight, 'the resize target must not cover Send').toBe(0)
      expect(await page.getByTestId('chat-send').evaluate(element => {
        const bounds = element.getBoundingClientRect()
        const hit = document.elementFromPoint(bounds.right - 5, bounds.bottom - 5)
        return hit === element || element.contains(hit)
      }), 'Send owns its bottom-right corner').toBe(true)
    }
    await input.press('Tab')
    const voice = panel.locator('.tool-icon')
    await expect(voice).toBeFocused()
    await expect(voice).toHaveCSS('outline-width', '2px')
    await expect.poll(() => voice.evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44)
    try {
      await page.getByTestId('chat-send').click()
      await expect(input).toBeDisabled()
      await expect(input).toBeHidden()
      await expect(voice).toBeDisabled()
      await expect(voice).toBeHidden()
      const status = page.getByTestId('chat-response-status')
      await expect(status).toBeVisible()
      expect(await status.evaluate(element => element.closest('.messages-viewport') === null)).toBe(true)
      const stop = page.getByTestId('chat-stop')
      await expect(stop).toBeEnabled()
      await expect.poll(() => stop.evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44)
    } finally {
      releaseReply()
    }
    await expect(input).toBeEnabled()
    await expect(input).toBeVisible()
    await expect(voice).toBeEnabled()
    await expect(voice).toBeVisible()
    await expect(page.getByTestId('chat-response-status')).toBeHidden()
    const copyAction = panel.locator('.msg-actions .action-btn-small').first()
    await expect(copyAction).toBeVisible()
    await expect(copyAction.locator('..')).toHaveCSS('opacity', '1')
    await expect.poll(() => copyAction.evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44)
    expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
  })
}

test('reopening the assistant exposes existing history without creating or sending a chat', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  const session = await unwrap<{ id: string; title: string }>(
    await request.post(`${apiBaseURL}/api/chat/sessions`, {
      headers: { Authorization: `Bearer ${auth.token}` }
    })
  )

  await openWorkspace(page, auth)

  await page.getByTestId('open-ai-assistant').click()
  await expect(page.getByTestId('chat-panel')).toBeVisible()
  await page.getByTestId('chat-sidebar-toggle').click()
  await expect(page.getByTestId(`chat-session-${session.id}`)).toBeVisible()

  await collapseChatSidebar(page)
  await page.getByTestId('chat-close').click()
  await expect(page.getByTestId('chat-panel')).toBeHidden()

  await page.getByTestId('open-ai-assistant').click()
  await expect(page.getByTestId('chat-panel')).toBeVisible()
  await page.getByTestId('chat-sidebar-toggle').click()
  await expect(page.getByTestId(`chat-session-${session.id}`)).toBeVisible()
})

for (const action of ['CONFIRM', 'CANCEL'] as const) {
  test(`protected ${action.toLowerCase()} submits the displayed action identity`, async ({ page, request }, testInfo) => {
    const auth = await createAuthenticatedUser(request)
    const session = await unwrap<{ id: string }>(await request.post(`${apiBaseURL}/api/chat/sessions`, {
      headers: { Authorization: `Bearer ${auth.token}` }
    }))
    const confirmationId = `pending-${action}-${auth.userId}`
    let settled = false
    await installPersistedPartialChatMock(page, session.id, 'Decision recorded.', async () => { settled = true })
    await page.route(`**/api/chat/sessions/${session.id}/confirmation`, route => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: {
        sessionId: session.id,
        actions: settled ? [] : [{
          kind: 'DESTRUCTIVE', confirmationId, toolName: 'delete_device', target: 'Kitchen sensor'
        }]
      } })
    }))
    await page.route(`**/api/chat/sessions/${session.id}/seen`, route => route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data: null })
    }))
    await openWorkspace(page, auth)
    await page.getByTestId('open-ai-assistant').click()
    await page.getByTestId('chat-sidebar-toggle').click()
    await page.getByTestId(`chat-session-${session.id}`).click()
    const decision = page.getByTestId('chat-protected-confirmation')
    await expect(decision.locator('strong')).toHaveText('请确认：删除设备')
    await expect(decision.getByTestId('chat-confirmation-target')).toHaveText('Kitchen sensor')
    await page.screenshot({ path: testInfo.outputPath(`assistant-${action.toLowerCase()}-preview.png`) })
    const submitted = page.waitForRequest('**/api/chat/completions')
    await decision.getByRole('button', { name: action === 'CONFIRM' ? '确定' : '取消', exact: true }).click()
    expect((await submitted).postDataJSON().confirmation).toEqual({
      kind: 'DESTRUCTIVE', action, confirmationId
    })
    expect((await submitted).postDataJSON().content).toBe(action === 'CONFIRM'
      ? '已确认删除设备：Kitchen sensor' : '已取消删除设备：Kitchen sensor')
    await expect(decision).toHaveCount(0)
    await expect(page.getByTestId('chat-input')).toBeEnabled()
  })
}

test('closing the browser page preserves an unread terminal result until its history is rendered', async ({
  page,
  context,
  request
}) => {
  const auth = await createAuthenticatedUser(request)
  const sessionId = `offline-result-${auth.userId}`
  let active = true
  let hasUnreadUpdate = false
  const seenMessageIds: number[] = []
  const result = (data: unknown) => JSON.stringify({ code: 200, message: 'ok', data })

  await context.route('**/api/chat/sessions**', async route => {
    const requestUrl = new URL(route.request().url())
    const method = route.request().method()
    if (method === 'POST' && requestUrl.pathname.endsWith(`/${sessionId}/seen`)) {
      const body = route.request().postDataJSON() as { terminalMessageId: number }
      seenMessageIds.push(body.terminalMessageId)
      hasUnreadUpdate = false
      await route.fulfill({
        status: 200,
        contentType: 'application/json; charset=UTF-8',
        body: result(null)
      })
      return
    }
    if (requestUrl.pathname.endsWith(`/${sessionId}/messages`)) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json; charset=UTF-8',
        body: result({
          messages: [{
            id: 42,
            sessionId,
            role: 'assistant',
            content: 'The background result is available.',
            turnId: 'offline-turn',
            createdAt: '2026-07-31T12:00:00Z',
            executionStatus: 'FAILED'
          }],
          nextBeforeId: null,
          hasMore: false
        })
      })
      return
    }
    if (requestUrl.pathname.endsWith(`/${sessionId}/confirmation`)) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json; charset=UTF-8',
        body: result({ sessionId, actions: [] })
      })
      return
    }
    if (requestUrl.pathname.endsWith(`/${sessionId}/activity`)) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json; charset=UTF-8',
        body: result({ sessionId, active: false })
      })
      return
    }
    if (method === 'GET' && requestUrl.pathname.endsWith('/api/chat/sessions')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json; charset=UTF-8',
        body: result([{
          id: sessionId,
          userId: auth.userId,
          title: 'Offline result',
          createdAt: '2026-07-31T11:59:00Z',
          updatedAt: '2026-07-31T12:00:00Z',
          active,
          latestTerminalMessageId: active ? null : 42,
          latestExecutionStatus: active ? null : 'FAILED',
          hasUnreadUpdate
        }])
      })
      return
    }
    await route.fallback()
  })

  await openWorkspace(page, auth)
  await expect(page.getByTestId('ai-assistant-running')).toHaveText('1')
  await page.close()
  expect(seenMessageIds).toEqual([])

  active = false
  hasUnreadUpdate = true
  const reopenedPage = await context.newPage()
  await openWorkspace(reopenedPage, auth)
  await expect(reopenedPage.getByTestId('ai-assistant-unread')).toHaveText('1')
  await reopenedPage.getByTestId('open-ai-assistant').click()
  await reopenedPage.getByTestId('chat-sidebar-toggle').click()
  await expect(reopenedPage.getByTestId('chat-session-status')).toContainText('失败')
  await reopenedPage.getByTestId(`chat-session-${sessionId}`).click()

  await expect.poll(() => [...seenMessageIds]).toEqual([42])
  await expect(reopenedPage.getByTestId('ai-assistant-unread')).toHaveCount(0)
})

test('moves and resizes the assistant panel on a desktop workspace', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  await openWorkspace(page, auth)

  await page.getByTestId('open-ai-assistant').click()
  const panel = page.getByTestId('chat-panel')
  await expect(panel).toBeVisible()

  const initialBounds = await panel.boundingBox()
  const dragHandleBounds = await page.getByTestId('chat-drag-handle').boundingBox()
  expect(initialBounds).not.toBeNull()
  expect(dragHandleBounds).not.toBeNull()

  await page.mouse.move(dragHandleBounds!.x + dragHandleBounds!.width / 2, dragHandleBounds!.y + dragHandleBounds!.height / 2)
  await page.mouse.down()
  await page.mouse.move(dragHandleBounds!.x + dragHandleBounds!.width / 2 - 120, dragHandleBounds!.y + dragHandleBounds!.height / 2 + 80)
  await page.mouse.up()

  await expect.poll(async () => (await panel.boundingBox())?.x).toBeLessThan(initialBounds!.x - 100)
  await expect.poll(async () => (await panel.boundingBox())?.y).toBeGreaterThan(initialBounds!.y + 60)

  const resizedBefore = await panel.boundingBox()
  const resizeHandleBounds = await page.getByTestId('chat-resize-handle').boundingBox()
  expect(resizedBefore).not.toBeNull()
  expect(resizeHandleBounds).not.toBeNull()

  await page.mouse.move(resizeHandleBounds!.x + resizeHandleBounds!.width / 2, resizeHandleBounds!.y + resizeHandleBounds!.height / 2)
  await page.mouse.down()
  await page.mouse.move(resizeHandleBounds!.x + resizeHandleBounds!.width / 2 - 100, resizeHandleBounds!.y + resizeHandleBounds!.height / 2 - 80)
  await page.mouse.up()

  await expect.poll(async () => (await panel.boundingBox())?.width).toBeLessThan(resizedBefore!.width - 80)
  await expect.poll(async () => (await panel.boundingBox())?.height).toBeLessThan(resizedBefore!.height - 60)

  const resizedSmaller = await panel.boundingBox()
  const resizeHandleAfterShrink = await page.getByTestId('chat-resize-handle').boundingBox()
  expect(resizedSmaller).not.toBeNull()
  expect(resizeHandleAfterShrink).not.toBeNull()

  await page.mouse.move(resizeHandleAfterShrink!.x + resizeHandleAfterShrink!.width / 2, resizeHandleAfterShrink!.y + resizeHandleAfterShrink!.height / 2)
  await page.mouse.down()
  await page.mouse.move(resizeHandleAfterShrink!.x + resizeHandleAfterShrink!.width / 2 + 100, resizeHandleAfterShrink!.y + resizeHandleAfterShrink!.height / 2 + 80)
  await page.mouse.up()

  await expect.poll(async () => (await panel.boundingBox())?.width).toBeGreaterThan(resizedSmaller!.width + 80)
  await expect.poll(async () => (await panel.boundingBox())?.height).toBeGreaterThan(resizedSmaller!.height + 60)
})
