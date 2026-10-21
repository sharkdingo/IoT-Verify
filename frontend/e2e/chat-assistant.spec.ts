import path from 'node:path'
import { type APIRequestContext, type Locator, type Page } from '@playwright/test'
import {
  apiBaseURL,
  createAuthenticatedUser,
  expect,
  test,
  type AuthUser
} from './support/auth'
import type { PersistedChatMessage, StreamProgress } from '../src/types/chat'
import { collapseChatSidebar } from './support/chatSidebar'

const rgba = (value: string): [number, number, number, number] => {
  const channels = value.match(/[\d.]+/g)?.map(Number)
  if (!channels || channels.length < 3 || channels.slice(0, 3).some(Number.isNaN)) {
    throw new Error(`Expected an RGB color, received: ${value}`)
  }
  return [channels[0], channels[1], channels[2], channels[3] ?? 1]
}

const relativeLuminance = (channels: [number, number, number]): number => {
  const linear = channels.map(channel => {
    const normalized = channel / 255
    return normalized <= 0.04045
      ? normalized / 12.92
      : ((normalized + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
}

const contrastRatio = (foreground: string, background: string): number => {
  const [red, green, blue, alpha] = rgba(foreground)
  const [backgroundRed, backgroundGreen, backgroundBlue] = rgba(background)
  const foregroundLuminance = relativeLuminance([
    red * alpha + backgroundRed * (1 - alpha),
    green * alpha + backgroundGreen * (1 - alpha),
    blue * alpha + backgroundBlue * (1 - alpha)
  ])
  const backgroundLuminance = relativeLuminance([
    backgroundRed,
    backgroundGreen,
    backgroundBlue
  ])
  const lighter = Math.max(foregroundLuminance, backgroundLuminance)
  const darker = Math.min(foregroundLuminance, backgroundLuminance)
  return (lighter + 0.05) / (darker + 0.05)
}

const surfaceLuminance = (color: string) => {
  const [red, green, blue] = rgba(color)
  return relativeLuminance([red, green, blue])
}

const waitForPanelPositionToSettle = async (panel: Locator) => {
  await expect.poll(async () => {
    const bounds = await panel.boundingBox()
    if (!bounds) return Number.POSITIVE_INFINITY
    const target = await panel.evaluate(element => {
      const style = (element as HTMLElement).style
      return {
        left: Number.parseFloat(style.left),
        top: Number.parseFloat(style.top)
      }
    })
    if (!Number.isFinite(target.left) || !Number.isFinite(target.top)) {
      return Number.POSITIVE_INFINITY
    }
    return Math.max(Math.abs(bounds.x - target.left), Math.abs(bounds.y - target.top))
  }).toBeLessThan(1)
}

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
  { name: 'desktop light', theme: 'light' as const, viewport: { width: 1280, height: 800 } },
  { name: 'desktop dark', theme: 'dark' as const, viewport: { width: 1280, height: 800 } },
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
  errorMessage?: string
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

for (const { width, height, theme } of [
  { width: 1440, height: 900, theme: 'light' },
  { width: 320, height: 360, theme: 'dark' }
] as const) {
  test(`keeps assistant render recovery above the canvas (${width}, ${theme})`, async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await page.setViewportSize({ width, height })
    await openWorkspace(page, auth, theme)
    await page.getByTestId('open-ai-assistant').click()
    await expect(page.getByTestId('chat-input')).toBeVisible()
    // Fail Vue's next v-model write; trace snapshots must still be able to read the textarea.
    await page.getByTestId('chat-input').evaluate(element => {
      const value = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!
      Object.defineProperty(element, 'value', {
        configurable: true,
        get() { return value.get!.call(element) },
        set() {
          Reflect.deleteProperty(element, 'value')
          throw new Error('assistant render failure probe')
        }
      })
    })
    await page.getByTestId('chat-panel').locator('button.task-card').first().click()
    const recovery = page.getByTestId('assistant-recovery-panel')
    await expect(recovery).toBeVisible()
    const bounds = await recovery.boundingBox()
    expect(bounds!.x).toBeGreaterThanOrEqual(0)
    expect(bounds!.y).toBeGreaterThanOrEqual(0)
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width)
    expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(height)
    await expect(page.getByTestId('assistant-recovery-retry')).toBeFocused()
    await expect(recovery.getByTestId('failure-technical-details')).not.toHaveAttribute('open')
    await page.keyboard.press('Escape')
    await expect(recovery).toBeHidden()
    await expect(page.getByTestId('open-ai-assistant')).toBeFocused()
    await page.getByTestId('open-ai-assistant').click()
    await expect(recovery).toBeVisible()
    await page.getByTestId('assistant-recovery-retry').click()
    await expect(recovery).toHaveCount(0)
    await expect(page.getByTestId('chat-panel')).toBeVisible()
    await expect(page.getByTestId('chat-input')).toBeEnabled()
    await expect(page.getByTestId('chat-reconciliation-required')).toHaveCount(0)
  })
}

for (const { width, theme } of [{ width: 1440, theme: 'light' }, { width: 320, theme: 'dark' }] as const) {
  test(`keeps assistant controls legible during a pending reply at ${width}px in ${theme}`, async ({ page, request }) => {
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
    const task = panel.locator('.task-card').first()
    await page.getByTestId('chat-close').press('Tab')
    const resize = page.getByTestId('chat-resize-handle')
    if (await resize.isVisible()) await resize.press('Tab')
    await expect(task).toBeFocused()
    await expect(task).toHaveCSS('outline-width', '2px')
    await expect(task).toHaveCSS('outline-style', 'solid')
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

test('first assistant mount reconciles a session that completed after the hidden baseline', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  const sessionId = `handoff-${auth.userId}`
  let active = true
  let snapshotReads = 0
  page.on('request', incoming => {
    if (new URL(incoming.url()).pathname === '/api/board/snapshot') snapshotReads += 1
  })
  await page.route('**/api/chat/sessions', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data: [{
      id: sessionId, userId: auth.userId, title: 'Background change',
      createdAt: '2026-10-07T00:00:00Z', updatedAt: '2026-10-07T00:01:00Z',
      active, latestTerminalMessageId: active ? null : 42,
      latestExecutionStatus: active ? null : 'COMPLETED', hasUnreadUpdate: !active
    }] })
  }))
  const hydrated = page.waitForResponse(response => new URL(response.url()).pathname === '/api/board/snapshot')
  await openWorkspace(page, auth)
  await hydrated
  await expect(page.getByTestId('ai-assistant-running')).toHaveText('1')
  const beforeMount = snapshotReads
  active = false
  await page.getByTestId('open-ai-assistant').click()
  await expect(page.getByTestId('chat-panel')).toBeVisible()
  await expect.poll(() => snapshotReads).toBeGreaterThan(beforeMount)
  await expect(page.getByTestId('ai-assistant-running')).toHaveCount(0)
})

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

test('keeps the panel movable and resizable in a short desktop viewport', async ({ page, request }) => {
  await page.setViewportSize({ width: 1280, height: 560 })
  const auth = await createAuthenticatedUser(request)
  await openWorkspace(page, auth)

  await page.getByTestId('open-ai-assistant').click()
  const panel = page.getByTestId('chat-panel')
  await expect(panel).toBeVisible()
  await expect(page.getByTestId('chat-resize-handle')).toBeVisible()

  const initialBounds = await panel.boundingBox()
  const dragHandleBounds = await page.getByTestId('chat-drag-handle').boundingBox()
  expect(initialBounds).not.toBeNull()
  expect(dragHandleBounds).not.toBeNull()

  await page.mouse.move(dragHandleBounds!.x + dragHandleBounds!.width / 2, dragHandleBounds!.y + dragHandleBounds!.height / 2)
  await page.mouse.down()
  await page.mouse.move(dragHandleBounds!.x + dragHandleBounds!.width / 2, dragHandleBounds!.y + dragHandleBounds!.height / 2 + 60)
  await page.mouse.up()

  await expect.poll(async () => (await panel.boundingBox())?.y).toBeGreaterThan(initialBounds!.y + 40)

  const boundsBeforeResize = await panel.boundingBox()
  const resizeHandleBounds = await page.getByTestId('chat-resize-handle').boundingBox()
  expect(boundsBeforeResize).not.toBeNull()
  expect(resizeHandleBounds).not.toBeNull()

  await page.mouse.move(resizeHandleBounds!.x + resizeHandleBounds!.width / 2, resizeHandleBounds!.y + resizeHandleBounds!.height / 2)
  await page.mouse.down()
  await page.mouse.move(resizeHandleBounds!.x + resizeHandleBounds!.width / 2 - 80, resizeHandleBounds!.y + resizeHandleBounds!.height / 2 - 70)
  await page.mouse.up()

  await expect.poll(async () => (await panel.boundingBox())?.width).toBeLessThan(boundsBeforeResize!.width - 60)
  await expect.poll(async () => (await panel.boundingBox())?.height).toBeLessThan(boundsBeforeResize!.height - 50)
})

test('keeps the panel interactive when pointer capture is unavailable', async ({ page, request }) => {
  await page.addInitScript(() => {
    Object.defineProperty(HTMLElement.prototype, 'setPointerCapture', {
      configurable: true,
      value: () => { throw new DOMException('Pointer capture unavailable') }
    })
  })
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
  await page.mouse.move(dragHandleBounds!.x + dragHandleBounds!.width / 2 - 100, dragHandleBounds!.y + dragHandleBounds!.height / 2 + 60)
  await page.mouse.up()

  await expect.poll(async () => (await panel.boundingBox())?.x).toBeLessThan(initialBounds!.x - 80)
  await expect.poll(async () => (await panel.boundingBox())?.y).toBeGreaterThan(initialBounds!.y + 40)

  const boundsBeforeResize = await panel.boundingBox()
  const resizeHandleBounds = await page.getByTestId('chat-resize-handle').boundingBox()
  expect(boundsBeforeResize).not.toBeNull()
  expect(resizeHandleBounds).not.toBeNull()

  await page.mouse.move(resizeHandleBounds!.x + resizeHandleBounds!.width / 2, resizeHandleBounds!.y + resizeHandleBounds!.height / 2)
  await page.mouse.down()
  await page.mouse.move(resizeHandleBounds!.x + resizeHandleBounds!.width / 2 - 80, resizeHandleBounds!.y + resizeHandleBounds!.height / 2 - 70)
  await page.mouse.up()

  await expect.poll(async () => (await panel.boundingBox())?.width).toBeLessThan(boundsBeforeResize!.width - 60)
  await expect.poll(async () => (await panel.boundingBox())?.height).toBeLessThan(boundsBeforeResize!.height - 50)
})

test('restores desktop panel interaction after leaving the responsive layout', async ({ page, request }) => {
  await page.setViewportSize({ width: 700, height: 720 })
  const auth = await createAuthenticatedUser(request)
  await openWorkspace(page, auth)

  await page.getByTestId('open-ai-assistant').click()
  const panel = page.getByTestId('chat-panel')
  await expect(panel).toBeVisible()
  await expect(page.getByTestId('chat-resize-handle')).toBeHidden()

  await page.setViewportSize({ width: 1280, height: 720 })
  await expect(page.getByTestId('chat-resize-handle')).toBeVisible()
  await waitForPanelPositionToSettle(panel)

  const initialBounds = await panel.boundingBox()
  const dragHandleBounds = await page.getByTestId('chat-drag-handle').boundingBox()
  expect(initialBounds).not.toBeNull()
  expect(dragHandleBounds).not.toBeNull()

  await page.mouse.move(dragHandleBounds!.x + dragHandleBounds!.width / 2, dragHandleBounds!.y + dragHandleBounds!.height / 2)
  await page.mouse.down()
  await page.mouse.move(dragHandleBounds!.x + dragHandleBounds!.width / 2 + 100, dragHandleBounds!.y + dragHandleBounds!.height / 2 + 60)
  await page.mouse.up()

  await expect.poll(async () => (await panel.boundingBox())?.x).toBeGreaterThan(initialBounds!.x + 80)
  await expect.poll(async () => (await panel.boundingBox())?.y).toBeGreaterThan(initialBounds!.y + 40)

  const boundsBeforeResize = await panel.boundingBox()
  const resizeHandleBounds = await page.getByTestId('chat-resize-handle').boundingBox()
  expect(boundsBeforeResize).not.toBeNull()
  expect(resizeHandleBounds).not.toBeNull()

  await page.mouse.move(resizeHandleBounds!.x + resizeHandleBounds!.width / 2, resizeHandleBounds!.y + resizeHandleBounds!.height / 2)
  await page.mouse.down()
  await page.mouse.move(resizeHandleBounds!.x + resizeHandleBounds!.width / 2 - 80, resizeHandleBounds!.y + resizeHandleBounds!.height / 2 - 70)
  await page.mouse.up()

  await expect.poll(async () => (await panel.boundingBox())?.width).toBeLessThan(boundsBeforeResize!.width - 60)
  await expect.poll(async () => (await panel.boundingBox())?.height).toBeLessThan(boundsBeforeResize!.height - 50)
})

test('releases an interrupted panel gesture when the viewport changes', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  await openWorkspace(page, auth)

  await page.getByTestId('open-ai-assistant').click()
  const panel = page.getByTestId('chat-panel')
  const dragHandle = page.getByTestId('chat-drag-handle')
  await expect(panel).toBeVisible()

  const interruptedHandleBounds = await dragHandle.boundingBox()
  expect(interruptedHandleBounds).not.toBeNull()
  await dragHandle.dispatchEvent('pointerdown', {
    pointerId: 41,
    pointerType: 'mouse',
    isPrimary: true,
    button: 0,
    buttons: 1,
    clientX: interruptedHandleBounds!.x + interruptedHandleBounds!.width / 2,
    clientY: interruptedHandleBounds!.y + interruptedHandleBounds!.height / 2
  })
  await expect(panel).toHaveClass(/dragging/)

  await page.setViewportSize({ width: 1100, height: 680 })
  await page.setViewportSize({ width: 1280, height: 720 })
  await expect(panel).not.toHaveClass(/dragging/)

  const initialBounds = await panel.boundingBox()
  const dragHandleBounds = await dragHandle.boundingBox()
  expect(initialBounds).not.toBeNull()
  expect(dragHandleBounds).not.toBeNull()
  await dragHandle.dispatchEvent('pointerdown', {
    pointerId: 42,
    pointerType: 'mouse',
    isPrimary: true,
    button: 0,
    buttons: 1,
    clientX: dragHandleBounds!.x + dragHandleBounds!.width / 2,
    clientY: dragHandleBounds!.y + dragHandleBounds!.height / 2
  })
  await expect(panel).toHaveClass(/dragging/)
  await page.evaluate(({ x, y }) => {
    window.dispatchEvent(new PointerEvent('pointermove', {
      pointerId: 42,
      pointerType: 'mouse',
      isPrimary: true,
      buttons: 1,
      clientX: x - 100,
      clientY: y + 60
    }))
    window.dispatchEvent(new PointerEvent('pointerup', {
      pointerId: 42,
      pointerType: 'mouse',
      isPrimary: true,
      button: 0,
      clientX: x - 100,
      clientY: y + 60
    }))
  }, {
    x: dragHandleBounds!.x + dragHandleBounds!.width / 2,
    y: dragHandleBounds!.y + dragHandleBounds!.height / 2
  })

   await expect.poll(async () => (await panel.boundingBox())?.x).toBeLessThan(initialBounds!.x - 80)
  await expect.poll(async () => (await panel.boundingBox())?.y).toBeGreaterThan(initialBounds!.y + 40)
})

test('keeps a pending execution trace at the full conversation width', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  const session = await unwrap<{ id: string }>(
    await request.post(`${apiBaseURL}/api/chat/sessions`, {
      headers: { Authorization: `Bearer ${auth.token}` }
    })
  )
  let releaseResponse!: () => void
  const responseGate = new Promise<void>(resolve => {
    releaseResponse = resolve
  })

  await installPersistedPartialChatMock(page, session.id, '已完成检查。', () => responseGate)
  await openWorkspace(page, auth)

  await page.getByTestId('open-ai-assistant').click()
  await page.getByTestId('chat-sidebar-toggle').click()
  await page.getByTestId(`chat-session-${session.id}`).click()
  await page.getByTestId('chat-input').fill('请检查当前画布')
  await page.getByTestId('chat-send').click()

  const pending = page.getByTestId('chat-assistant-pending')
  await expect(pending).toBeVisible()
  const layout = await pending.evaluate(element => {
    const bubble = element.closest('article')
    const wrapper = element.closest('.msg-content-wrapper')
    const bubbleRect = bubble?.getBoundingClientRect()
    const wrapperRect = wrapper?.getBoundingClientRect()
    return {
      tagName: bubble?.tagName,
      compactClass: bubble?.classList.contains('assistant-pending-body'),
      bubbleWidth: bubbleRect?.width ?? 0,
      wrapperWidth: wrapperRect?.width ?? 0
    }
  })

  expect(layout).toMatchObject({ tagName: 'ARTICLE', compactClass: true })
  expect(layout.bubbleWidth).toBeGreaterThan(layout.wrapperWidth * 0.95)
  expect(layout.bubbleWidth).toBeLessThanOrEqual(layout.wrapperWidth + 1)

  releaseResponse()
  await expect(pending).toBeHidden()
  const executionDetails = page.locator('details.chat-execution-trace')
  await expect(executionDetails).toBeVisible()
  // The newest completed turn keeps its reasoning open, so the trace is readable without a click;
  // a deliberate collapse is then honoured rather than being re-expanded.
  await expect(page.getByTestId('chat-execution-trace')).toBeVisible()
  await executionDetails.locator('summary').click()
  await expect(page.getByTestId('chat-execution-trace')).toBeHidden()
  await expect(page.getByTestId('chat-reconciliation-required')).toHaveCount(0)
  await expect(page.getByTestId('chat-history-retry')).toHaveCount(0)
  await expect(page.getByTestId('chat-input')).toBeEnabled()
})

test('renders assistant code blocks on a readable dark surface', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  const session = await unwrap<{ id: string }>(
    await request.post(`${apiBaseURL}/api/chat/sessions`, {
      headers: { Authorization: `Bearer ${auth.token}` }
    })
  )
  const markdown = '```json\n{"status":"ok"}\n```'
  await installPersistedPartialChatMock(page, session.id, markdown)
  await openWorkspace(page, auth, 'dark')

  await page.getByTestId('open-ai-assistant').click()
  await page.getByTestId('chat-sidebar-toggle').click()
  await page.getByTestId(`chat-session-${session.id}`).click()
  await page.getByTestId('chat-input').fill('show status')
  await page.getByTestId('chat-send').click()

  const codeBlock = page.locator('.code-block-container')
  await expect(codeBlock).toBeVisible()
  await expect(codeBlock).toContainText('{"status":"ok"}')
  const executionDetails = page.locator('details.chat-execution-trace')
  await expect(executionDetails).toBeVisible()
  await expect(page.getByTestId('chat-execution-trace')).toBeVisible()
  await expect(page.getByTestId('chat-reconciliation-required')).toHaveCount(0)
  await expect(page.getByTestId('chat-history-retry')).toHaveCount(0)

  const surfaces = await codeBlock.evaluate(element => {
    const canvas = document.createElement('canvas')
    canvas.width = 1
    canvas.height = 1
    const context = canvas.getContext('2d', { willReadFrequently: true })
    if (!context) throw new Error('Canvas color normalization is unavailable')
    const toSrgb = (color: string) => {
      context.clearRect(0, 0, 1, 1)
      context.fillStyle = '#000'
      context.fillStyle = color
      context.fillRect(0, 0, 1, 1)
      const [red, green, blue, alpha] = context.getImageData(0, 0, 1, 1).data
      return `rgba(${red}, ${green}, ${blue}, ${alpha / 255})`
    }
    const codeSurface = element.querySelector<HTMLElement>('pre.shiki')
    const header = element.querySelector<HTMLElement>('.code-header')
    const language = element.querySelector<HTMLElement>('.lang-label')
    const copy = element.querySelector<HTMLElement>('.copy-btn')
    if (!codeSurface || !header || !language || !copy) throw new Error('Code block surface or controls are missing')
    const headerStyle = getComputedStyle(header)
    const codeColors = [...element.querySelectorAll<HTMLElement>('.code-content code span')]
      .filter(token => token.textContent?.trim())
      .map(token => toSrgb(getComputedStyle(token).color))
    return {
      bodyBackground: toSrgb(getComputedStyle(codeSurface).backgroundColor),
      headerBackground: toSrgb(headerStyle.backgroundColor),
      headerColors: [
        toSrgb(getComputedStyle(language).color),
        toSrgb(getComputedStyle(copy).color)
      ],
      codeColors
    }
  })
  expect(surfaceLuminance(surfaces.bodyBackground)).toBeLessThan(0.18)
  expect(surfaceLuminance(surfaces.headerBackground)).toBeLessThan(0.18)
  expect(surfaces.codeColors.length).toBeGreaterThan(0)
  for (const color of surfaces.codeColors) {
    expect(contrastRatio(color, surfaces.bodyBackground)).toBeGreaterThanOrEqual(4.5)
  }
  for (const color of surfaces.headerColors) {
    expect(contrastRatio(color, surfaces.headerBackground)).toBeGreaterThanOrEqual(4.5)
  }
})

// Scoped deliberately: this drives the REFRESH_DATA path with a mocked stream, so it verifies the
// *client* re-reads undo availability. The assistant's own journalling is covered end-to-end in
// live-ai-no-mock.spec.ts, which must stay the authority for that claim.
test('a rule_list refresh command makes the workspace re-read undo availability', async ({ page, request }) => {
  const auth = await createAuthenticatedUser(request)
  const headers = { Authorization: `Bearer ${auth.token}` }
  const rulesUrl = `${apiBaseURL}/api/board/rules`

  await openWorkspace(page, auth)

  // A real scene gives us device-referencing rules to delete.
  await page.getByTestId('scene-import-file').setInputFiles(path.resolve(
    process.cwd(), '..', 'docs', 'examples', 'default-climate-conflict-scene.json'))
  // Scene replacement clears the journal, so this is a clean baseline.
  await page.getByRole('dialog').getByRole('button', { name: /Replace scene|替换场景/ }).click()
  await expect(page.getByTestId('board-undo')).toBeDisabled({ timeout: 60_000 })
  await expect.poll(async () => (await unwrap<any[]>(await request.get(rulesUrl, { headers }))).length,
    { timeout: 60_000 }).toBeGreaterThan(0)
  const rules = await unwrap<any[]>(await request.get(rulesUrl, { headers }))

  // The "board has not been told yet" window below is only meaningful if the board is not allowed to
  // find out on its own. It re-reads availability for several legitimate reasons (the journal-cleared
  // re-read, a snapshot reload, the data-ready hook, tab focus), and after the out-of-band delete every
  // one of those correctly returns canUndo=true — which enables the button for a reason that has
  // nothing to do with the REFRESH_DATA command under test. Earlier attempts to order this with a
  // barrier still flaked in CI, because there is no point at which the client is guaranteed to be done
  // reading. So the reads are pinned to the pre-delete answer for the duration of the window, and
  // released just before the command is delivered.
  await page.route('**/api/board/edits/availability', route => route.fulfill({
    status: 200,
    contentType: 'application/json; charset=UTF-8',
    body: JSON.stringify({
      code: 200,
      message: 'ok',
      data: {
        applied: false, reasonCode: 'AVAILABILITY_ONLY',
        rules: [], specs: [], canUndo: false, canRedo: false
      }
    })
  }))

  // Delete through the same journal-recording endpoint the assistant's tools use. The model's
  // wording is not under test here; the contract is that an assistant-path edit is as reversible
  // as a user-path one.
  const deleted = await request.delete(`${rulesUrl}/${rules[0].id}`, { headers, data: rules[0] })
  expect(deleted.ok(), await deleted.text()).toBeTruthy()

  // The board has not been told anything yet, so it still shows no history.
  await expect(page.getByTestId('board-undo')).toBeDisabled()

  // Now deliver exactly the REFRESH_DATA command the backend emits after a rule tool runs.
  // Scoped to this test's freshly registered account: the backend allows only one active stream
  // request per chat session across all instances, so a hard-coded id made two parallel workers
  // collide — the second got a 409, its stream never delivered REFRESH_DATA, and the failure looked
  // like a broken refresh rather than a colliding fixture.
  const sessionId = `assistant-undo-session-${auth.userId}`
  const result = (data: unknown) => JSON.stringify({ code: 200, message: 'ok', data })
  // A COMPLETED terminal must be backed by a paired usable tool result — the client rejects an
  // unproven completion, and the backend's own `hasCompletedToolEvidence` guarantees it never sends
  // one. So the mock carries the execution/result pair a real rule deletion would report, in the
  // stream and in the persisted history alike.
  const toolTrace = [
    { stage: 'CONTEXT_READY' },
    { stage: 'TOOL_EXECUTION', toolName: 'manage_rule', round: 1 },
    { stage: 'TOOL_RESULT', toolName: 'manage_rule', round: 1, outcome: 'USABLE' }
  ]
  // Set once the stream is served: history read before it is an empty session, read after it holds
  // the persisted turn.
  let streamedTurnId: string | null = null
  // After the stream, the chat panel settles it: it polls `/activity` until idle, reloads history
  // and requires the turn's terminal record, then re-reads `/confirmation` — each validated against
  // its DTO. `/activity` and `/confirmation` used to get the session-list answer and history had no
  // terminal record, so settlement always failed and raised a warning toast over the header. In the
  // run where that toast was still sliding over the undo button, the click below completed without
  // an undo request and the rules never came back. Each sub-resource now answers in its own shape,
  // so settlement succeeds as it does against a real server.
  await page.route('**/api/chat/sessions**', async route => {
    const requestUrl = new URL(route.request().url())
    const method = route.request().method()
    if (method === 'POST' && requestUrl.pathname.endsWith(`/${sessionId}/seen`)) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json; charset=UTF-8',
        body: result(null)
      })
      return
    }
    if (method === 'POST' && requestUrl.pathname.endsWith('/api/chat/sessions')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json; charset=UTF-8',
        // Full ChatSessionDto shape. The client validates every field, because `active` gates whether
        // a second assistant mutation may start — an absent flag would read as idle. A partial fixture
        // is rejected at the boundary, exactly as a partial server response would be.
        body: JSON.stringify({
          code: 200,
          data: {
            id: sessionId,
            userId: auth.userId,
            title: 'undo parity',
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
            active: false,
            latestTerminalMessageId: null,
            latestExecutionStatus: null,
            hasUnreadUpdate: false
          }
        })
      })
      return
    }
    if (requestUrl.pathname.endsWith(`/${sessionId}/messages`)) {
      const createdAt = new Date().toISOString()
      const messages = streamedTurnId === null ? [] : [
        { id: 1, sessionId, role: 'user', content: 'Delete one rule.', turnId: streamedTurnId, createdAt },
        {
          id: 2,
          sessionId,
          role: 'assistant',
          content: 'Removed one rule.',
          turnId: streamedTurnId,
          createdAt,
          executionStatus: 'COMPLETED',
          executionTrace: toolTrace
        }
      ]
      await route.fulfill({
        status: 200,
        contentType: 'application/json; charset=UTF-8',
        body: result({ messages, nextBeforeId: null, hasMore: false })
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
    if (requestUrl.pathname.endsWith(`/${sessionId}/confirmation`)) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json; charset=UTF-8',
        body: result({ sessionId, actions: [] })
      })
      return
    }
    if (method === 'GET' && requestUrl.pathname.endsWith('/api/chat/sessions')) {
      // The list, which must also be a well-formed session array.
      await route.fulfill({
        status: 200,
        contentType: 'application/json; charset=UTF-8',
        body: result([])
      })
      return
    }
    await route.fallback()
  })
  await page.route('**/api/chat/completions', async route => {
    const { turnId } = route.request().postDataJSON() as { turnId: string }
    streamedTurnId = turnId
    const frames = [
      ...toolTrace.map(progress => ({ progress })),
      { command: { type: 'REFRESH_DATA', payload: { target: 'rule_list' } } },
      { content: 'Removed one rule.' },
      { terminal: { turnId, executionStatus: 'COMPLETED' } }
    ]
    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream; charset=UTF-8',
      body: `${frames.map(frame => `data: ${JSON.stringify(frame)}`).join('\n\n')}\n\n`
    })
  })

  await page.getByTestId('open-ai-assistant').click()
  const composer = page.getByTestId('chat-input')
  await expect(composer).toBeVisible({ timeout: 30_000 })
  await composer.fill('Delete one rule.')
  // `rule_list` maps to `refreshRulesFromChat`, which reloads the rule list and *then* awaits the undo
  // availability re-read. Watching for that rule fetch is what actually pins the handler: the button
  // alone does not, because `refreshBoardSnapshot` re-reads availability for its own reasons and chat
  // activity triggers one — this test passed with the target pointed at a nonexistent method until the
  // assertion below was added.
  const ruleReloadFromRefreshCommand = page.waitForRequest(request =>
    request.url().includes('/api/board/rules')
    && request.method() === 'GET', { timeout: 60_000 })
  // Release the availability pin only now, so the window above stayed deterministic.
  await page.unroute('**/api/board/edits/availability')
  await page.getByTestId('chat-send').click()
  await ruleReloadFromRefreshCommand

  // The refresh must re-read the journal, or the same edit would be undoable when the user made it
  // and not when the assistant did.
  await expect(page.getByTestId('board-undo')).toBeEnabled({ timeout: 60_000 })

  await page.getByTestId('board-undo').click()
  await expect.poll(async () => (await unwrap<any[]>(await request.get(rulesUrl, { headers }))).length,
    { timeout: 60_000 }).toBe(rules.length)
})
