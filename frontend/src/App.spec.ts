import { defineComponent, h, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import { i18n } from '@/assets/i18n'
import { useChatStore } from '@/stores/chat'
import { useAuth } from '@/stores/auth'
import type { ChatLogoutPreparation, StreamCommand } from '@/types/chat'

const chatApi = vi.hoisted(() => ({
  getSessionActivity: vi.fn(),
  getSessionList: vi.fn().mockResolvedValue([]),
  requestSessionStop: vi.fn()
}))
const boardInvalidation = vi.hoisted(() => ({ publishBoardInvalidation: vi.fn() }))

vi.mock('@/utils/boardInvalidation', () => boardInvalidation)

vi.mock('@/api/chat', async importOriginal => ({
  ...await importOriginal<typeof import('@/api/chat')>(),
  getSessionActivity: chatApi.getSessionActivity,
  getSessionList: chatApi.getSessionList,
  requestSessionStop: chatApi.requestSessionStop
}))

describe('App route lifecycle', () => {
  beforeEach(() => {
    chatApi.getSessionList.mockReset().mockResolvedValue([])
    chatApi.getSessionActivity.mockReset().mockResolvedValue({ sessionId: 'session-1', active: false })
    chatApi.requestSessionStop.mockReset().mockResolvedValue(undefined)
    boardInvalidation.publishBoardInvalidation.mockReset()
    useChatStore().closeChat()
    useChatStore().setStreaming(false)
    useChatStore().setActiveCount(0)
    useChatStore().setUnreadCount(0)
    useChatStore().setReconciliationRequired(false)
  })

  it('keeps a failed assistant reachable and remounts it without clearing pending reconciliation', async () => {
    const store = useChatStore()
    const shouldThrow = ref(false)
    let mounts = 0
    const BoardRoute = defineComponent({ setup: () => () => h('button', {
      'data-testid': 'open-ai-assistant', onClick: () => store.openChat()
    }, 'Assistant') })
    const ChatViewStub = defineComponent({
      name: 'ChatView',
      setup: () => {
        mounts += 1
        return () => {
          if (shouldThrow.value) throw new Error('assistant render failure')
          return h('div', { 'data-testid': 'recovered-chat' }, 'Conversation')
        }
      }
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/board', name: 'board', component: BoardRoute }]
    })
    const auth = useAuth()
    auth.login('alice-token', { userId: 1, phone: '13800138000', username: 'alice' })
    await router.push('/board')
    await router.isReady()
    const log = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const wrapper = mount(App, {
      attachTo: document.body,
      global: { plugins: [router, i18n], stubs: { ChatView: ChatViewStub } }
    })
    try {
      await flushPromises()
      const opener = wrapper.get('[data-testid="open-ai-assistant"]')
      ;(opener.element as HTMLButtonElement).focus()
      await opener.trigger('click')
      await flushPromises()
      shouldThrow.value = true
      await flushPromises()

      const recovery = wrapper.get('[data-testid="assistant-recovery-panel"]')
      expect(recovery.isVisible()).toBe(true)
      expect(store.state.reconciliationRequired).toBe(true)
      expect(recovery.get('[role="alert"]').text()).toContain(i18n.global.t('app.chat.viewErrorMessage'))
      expect(recovery.get('[data-testid="failure-technical-details"]').attributes('open')).toBeUndefined()
      expect(document.activeElement).toBe(wrapper.get('[data-testid="assistant-recovery-retry"]').element)
      expect(wrapper.find('[data-testid="app-error-fallback"]').exists()).toBe(false)

      await recovery.trigger('keydown', { key: 'Escape' })
      await flushPromises()
      expect(store.state.visible).toBe(false)
      expect(document.activeElement).toBe(opener.element)
      await opener.trigger('click')
      await flushPromises()
      expect(recovery.isVisible()).toBe(true)
      shouldThrow.value = false
      await recovery.get('[data-testid="assistant-recovery-retry"]').trigger('click')
      await flushPromises()
      expect(wrapper.get('[data-testid="recovered-chat"]').text()).toBe('Conversation')
      expect(mounts).toBe(2)
      expect(store.state.reconciliationRequired).toBe(true)
    } finally {
      wrapper.unmount()
      log.mockRestore()
      store.closeChat()
      auth.logout()
    }
  })

  it('adopts slow hidden-session observations while coalescing refresh demand', async () => {
    const refreshAllBoardState = vi.fn().mockResolvedValue(true)
    const BoardRoute = defineComponent({
      setup(_, { expose }) {
        expose({ refreshAllBoardState })
        return () => h('div', 'board')
      }
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/board', name: 'board', component: BoardRoute }]
    })
    const auth = useAuth()
    auth.login('alice-token', { userId: 1, phone: '13800138000', username: 'alice' })
    await router.push('/board')
    await router.isReady()
    const running = {
      id: 'slow-session', userId: 1, title: 'Background work', active: true,
      latestTerminalMessageId: null, latestExecutionStatus: null, hasUnreadUpdate: false,
      updatedAt: '2026-10-20T12:00:00Z'
    }
    const completed = { ...running, active: false, latestTerminalMessageId: 42,
      latestExecutionStatus: 'COMPLETED', hasUnreadUpdate: true }
    let resolveFirst!: (value: unknown) => void
    let resolveSecond!: (value: unknown) => void
    let resolveAfterUnmount!: (value: unknown) => void
    chatApi.getSessionList
      .mockReturnValueOnce(new Promise(resolve => { resolveFirst = resolve }))
      .mockReturnValueOnce(new Promise(resolve => { resolveSecond = resolve }))
      .mockReturnValueOnce(new Promise(resolve => { resolveAfterUnmount = resolve }))
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const wrapper = mount(App, { global: { plugins: [router, i18n] } })
    const store = useChatStore()
    try {
      await flushPromises()
      window.dispatchEvent(new Event('focus'))
      window.dispatchEvent(new Event('focus'))
      await vi.advanceTimersByTimeAsync(6000)
      expect(chatApi.getSessionList).toHaveBeenCalledTimes(1)

      resolveFirst([running])
      await flushPromises()
      expect(store.state.activeCount).toBe(1)
      expect(chatApi.getSessionList).toHaveBeenCalledTimes(2)
      await vi.advanceTimersByTimeAsync(6000)
      expect(chatApi.getSessionList).toHaveBeenCalledTimes(2)

      resolveSecond([completed])
      await flushPromises()
      expect(store.state.activeCount).toBe(0)
      expect(store.state.unreadCount).toBe(1)
      expect(store.state.reconciliationRequired).toBe(false)
      expect(refreshAllBoardState).toHaveBeenCalledOnce()
      expect(chatApi.getSessionList).toHaveBeenCalledTimes(3)

      await vi.advanceTimersByTimeAsync(5000)
      wrapper.unmount()
      resolveAfterUnmount([running])
      await flushPromises()
      await vi.advanceTimersByTimeAsync(5000)
      expect(chatApi.getSessionList).toHaveBeenCalledTimes(3)
    } finally {
      if (wrapper.exists()) wrapper.unmount()
      auth.logout()
      vi.useRealTimers()
    }
  })

  it('keeps a queued hidden-session refresh within its authenticated account', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/board', name: 'board', component: defineComponent({
        setup: () => () => h('div', 'board')
      }) }]
    })
    const auth = useAuth()
    auth.login('alice-token', { userId: 1, phone: '13800138000', username: 'alice' })
    await router.push('/board')
    await router.isReady()
    let resolveAlice!: (value: unknown) => void
    chatApi.getSessionList
      .mockReturnValueOnce(new Promise(resolve => { resolveAlice = resolve }))
      .mockResolvedValue([])
    const wrapper = mount(App, { global: { plugins: [router, i18n] } })
    try {
      await flushPromises()
      window.dispatchEvent(new Event('focus'))
      auth.login('bob-token', { userId: 2, phone: '13900139000', username: 'bob' })
      await flushPromises()
      expect(chatApi.getSessionList).toHaveBeenCalledTimes(2)

      resolveAlice([{
        id: 'alice-session', userId: 1, title: 'Old account work', active: true,
        latestTerminalMessageId: 43, latestExecutionStatus: 'COMPLETED', hasUnreadUpdate: true,
        updatedAt: '2026-10-20T12:00:00Z'
      }])
      await flushPromises()
      const store = useChatStore()
      expect(store.state.activeCount).toBe(0)
      expect(store.state.unreadCount).toBe(0)
      expect(store.state.reconciliationRequired).toBe(false)
      expect(chatApi.getSessionList).toHaveBeenCalledTimes(2)
    } finally {
      wrapper.unmount()
      auth.logout()
    }
  })

  it('restores persistent assistant activity before the panel is opened', async () => {
    const BoardRoute = defineComponent({ setup: () => () => h('div', 'board') })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/board', name: 'board', component: BoardRoute }],
    })
    const auth = useAuth()
    auth.login('alice-token', {
      userId: 1,
      phone: '13800138000',
      username: 'alice',
    })
    chatApi.getSessionList.mockResolvedValue([
      {
        id: 'finished-session',
        userId: 1,
        title: 'Finished while away',
        updatedAt: '2026-07-31T12:00:00Z',
        active: false,
        latestTerminalMessageId: 11,
        latestExecutionStatus: 'COMPLETED',
        hasUnreadUpdate: true
      },
      {
        id: 'running-session',
        userId: 1,
        title: 'Still running',
        updatedAt: '2026-07-31T12:01:00Z',
        active: true,
        latestTerminalMessageId: null,
        latestExecutionStatus: null,
        hasUnreadUpdate: false
      }
    ])
    const store = useChatStore()
    store.closeChat()
    await router.push('/board')
    await router.isReady()

    const wrapper = mount(App, { global: { plugins: [router, i18n] } })
    await flushPromises()

    expect(store.state.visible).toBe(false)
    expect(store.state.activeCount).toBe(1)
    expect(store.state.unreadCount).toBe(1)
    expect(store.state.streaming).toBe(false)
    wrapper.unmount()
    auth.logout()
  })

  it('hands the last observed account baseline to the lazily mounted conversation', async () => {
    const BoardRoute = defineComponent({ setup: () => () => h('div', 'board') })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/board', name: 'board', component: BoardRoute }],
    })
    const auth = useAuth()
    auth.login('alice-token', { userId: 1, phone: '13800138000', username: 'alice' })
    chatApi.getSessionList.mockResolvedValue([{
      id: 'running-session', userId: 1, title: 'Still running', updatedAt: '2026-10-07T12:00:00Z',
      active: true, latestTerminalMessageId: 17, latestExecutionStatus: 'COMPLETED', hasUnreadUpdate: false
    }])
    const store = useChatStore()
    store.closeChat()
    await router.push('/board')
    await router.isReady()
    const ChatViewStub = defineComponent({
      name: 'ChatView',
      props: ['initialSessionObservations'],
      setup: () => () => h('div', 'chat')
    })
    const wrapper = mount(App, { global: { plugins: [router, i18n], stubs: { ChatView: ChatViewStub } } })
    try {
      await flushPromises()
      store.openChat()
      await flushPromises()
      expect(wrapper.getComponent(ChatViewStub).props('initialSessionObservations')).toEqual({
        userId: 1,
        sessions: [{ id: 'running-session', active: true, latestTerminalMessageId: 17 }],
        reconciliation: null
      })
    } finally {
      wrapper.unmount()
      store.closeChat()
      auth.logout()
    }
  })

  it('prepares account-page session end by stopping authoritative assistant work without mounting the chat panel', async () => {
    let prepareForLogout!: () => Promise<ChatLogoutPreparation>
    const AccountRoute = defineComponent({
      props: { prepareChatForLogout: Function },
      setup(props) {
        prepareForLogout = props.prepareChatForLogout as () => Promise<ChatLogoutPreparation>
        return () => h('div', 'account')
      }
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/account', name: 'account', component: AccountRoute }],
    })
    const auth = useAuth()
    auth.login('alice-token', {
      userId: 1,
      phone: '13800138000',
      username: 'alice',
    })
    const activeSession = {
      id: 'session-from-another-tab',
      userId: 1,
      title: 'Scene generation',
      updatedAt: '2026-08-01T12:00:00Z',
      active: true,
      latestTerminalMessageId: null,
      latestExecutionStatus: null,
      hasUnreadUpdate: false
    }
    chatApi.getSessionList.mockResolvedValue([activeSession])
    chatApi.getSessionActivity.mockResolvedValue({
      sessionId: activeSession.id,
      active: false
    })
    const store = useChatStore()
    store.closeChat()
    await router.push('/account')
    await router.isReady()

    const wrapper = mount(App, { global: { plugins: [router, i18n] } })
    await flushPromises()

    await expect(prepareForLogout()).resolves.toBe('ready')
    expect(chatApi.requestSessionStop).toHaveBeenCalledWith(
      activeSession.id, undefined, 'alice-token')
    expect(chatApi.getSessionActivity).toHaveBeenCalledWith(activeSession.id)
    expect(boardInvalidation.publishBoardInvalidation).toHaveBeenCalledExactlyOnceWith(1, 'chat-tool')

    wrapper.unmount()
    auth.logout()
  })

  it.each(['session list', 'stop', 'activity'] as const)(
    'abandons hidden chat logout when its account changes during %s', async stage => {
      let prepareForLogout!: () => Promise<ChatLogoutPreparation>
      const AccountRoute = defineComponent({
        props: { prepareChatForLogout: Function },
        setup(props) {
          prepareForLogout = props.prepareChatForLogout as () => Promise<ChatLogoutPreparation>
          return () => h('div', 'account')
        }
      })
      const router = createRouter({
        history: createMemoryHistory(),
        routes: [{ path: '/account', name: 'account', component: AccountRoute }],
      })
      const auth = useAuth()
      auth.login('alice-token', { userId: 1, phone: '13800138000', username: 'alice' })
      const activeSession = {
        id: 'alice-session', userId: 1, title: 'Alice work', active: true,
        updatedAt: '2026-10-08T00:00:00Z', latestTerminalMessageId: null,
        latestExecutionStatus: null, hasUnreadUpdate: false
      }
      chatApi.getSessionList.mockResolvedValue([activeSession])
      const store = useChatStore()
      store.closeChat()
      await router.push('/account')
      await router.isReady()
      const wrapper = mount(App, { global: { plugins: [router, i18n] } })
      try {
        await flushPromises()
        let resolvePending!: (value: any) => void
        const pending = new Promise(resolve => { resolvePending = resolve })
        if (stage === 'session list') chatApi.getSessionList.mockReturnValueOnce(pending)
        if (stage === 'stop') chatApi.requestSessionStop.mockReturnValueOnce(pending)
        if (stage === 'activity') chatApi.getSessionActivity.mockReturnValueOnce(pending)
        const preparation = prepareForLogout()
        await flushPromises()
        chatApi.getSessionList.mockResolvedValue([])
        auth.login('bob-token', { userId: 2, phone: '13900139000', username: 'bob' })
        await flushPromises()
        store.setReconciliationRequired(true)
        resolvePending(stage === 'session list'
          ? [{ ...activeSession, id: 'bob-session', userId: 2 }]
          : stage === 'activity' ? { sessionId: 'alice-session', active: false } : true)
        await expect(preparation).resolves.toBe('outcome-unknown')
        expect(chatApi.requestSessionStop).not.toHaveBeenCalledWith('bob-session', undefined, 'bob-token')
        if (stage === 'session list') expect(chatApi.requestSessionStop).not.toHaveBeenCalled()
        else expect(chatApi.requestSessionStop).toHaveBeenCalledWith('alice-session', undefined, 'alice-token')
        expect(boardInvalidation.publishBoardInvalidation).not.toHaveBeenCalled()
        expect(store.state.reconciliationRequired).toBe(true)
        expect(auth.getToken()).toBe('bob-token')
      } finally {
        wrapper.unmount()
        auth.logout()
      }
    }
  )

  it('rejects session-end preparation when a newer shared credential precedes its storage event', async () => {
    let prepareForLogout!: () => Promise<ChatLogoutPreparation>
    const AccountRoute = defineComponent({
      props: { prepareChatForLogout: Function },
      setup(props) {
        prepareForLogout = props.prepareChatForLogout as () => Promise<ChatLogoutPreparation>
        return () => h('div', 'account')
      }
    })
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/account', name: 'account', component: AccountRoute }
    ] })
    const auth = useAuth()
    auth.login('alice-token', { userId: 1, phone: '13800138000', username: 'alice' })
    await router.push('/account')
    const wrapper = mount(App, { global: { plugins: [router, i18n] } })
    try {
      await flushPromises()
      chatApi.getSessionList.mockClear()
      const bob = { userId: 2, phone: '13900139000', username: 'bob' }
      localStorage.setItem('iot_verify_token', 'bob-token')
      localStorage.setItem('iot_verify_user', JSON.stringify(bob))
      const sharedRecord = JSON.stringify({ token: 'bob-token', user: bob, updatedAt: Date.now() })
      localStorage.setItem('iot_verify_auth_sync', sharedRecord)

      await expect(prepareForLogout()).resolves.toBe('outcome-unknown')

      expect(chatApi.getSessionList).not.toHaveBeenCalled()
      expect(chatApi.requestSessionStop).not.toHaveBeenCalled()
      expect(boardInvalidation.publishBoardInvalidation).not.toHaveBeenCalled()
      expect(localStorage.getItem('iot_verify_auth_sync')).toBe(sharedRecord)
      expect(auth.getToken()).toBe('alice-token')
    } finally {
      wrapper.unmount()
      auth.logout()
    }
  })

  it('retains the mounted conversation across the account page and dispatches later commands to the returned Board', async () => {
    const boardMounted = vi.fn()
    const boardUnmounted = vi.fn()
    const chatMounted = vi.fn()
    const chatUnmounted = vi.fn()
    const refreshAllBoardState = vi.fn().mockResolvedValue(true)
    const mountedPreparation = vi.fn().mockResolvedValue('ready')
    let prepareForLogout!: () => Promise<ChatLogoutPreparation>
    const BoardRoute = defineComponent({
      setup(_, { expose }) {
        onMounted(boardMounted)
        onBeforeUnmount(boardUnmounted)
        expose({ refreshAllBoardState })
        return () => h('div', 'board')
      }
    })
    const AccountRoute = defineComponent({
      props: { prepareChatForLogout: Function },
      setup(props) {
        prepareForLogout = props.prepareChatForLogout as () => Promise<ChatLogoutPreparation>
        return () => h('div', 'account')
      }
    })
    const ChatViewStub = defineComponent({
      name: 'ChatView',
      props: ['boardMode', 'executeCommand'],
      setup(_, { expose }) {
        onMounted(chatMounted)
        onBeforeUnmount(chatUnmounted)
        expose({ prepareForLogout: mountedPreparation })
        return () => h('div', 'conversation')
      }
    })
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/board', name: 'board', component: BoardRoute },
      { path: '/account', name: 'account', component: AccountRoute }
    ] })
    const auth = useAuth()
    auth.login('alice-token', { userId: 1, phone: '13800138000', username: 'alice' })
    await router.push('/board?run=verification:7&trace=15')
    const wrapper = mount(App, { global: { plugins: [router, i18n], stubs: { ChatView: ChatViewStub } } })
    const store = useChatStore()
    try {
      await flushPromises()
      store.openChat()
      await flushPromises()
      const conversation = wrapper.getComponent(ChatViewStub).vm
      expect(chatMounted).toHaveBeenCalledOnce()
      await router.push({ path: '/account', query: { returnTo: '/board?run=verification:7&trace=15' } })
      await flushPromises()

      expect(boardUnmounted).toHaveBeenCalledOnce()
      expect(chatUnmounted).not.toHaveBeenCalled()
      expect(wrapper.getComponent(ChatViewStub).vm).toBe(conversation)
      expect(wrapper.getComponent(ChatViewStub).props('boardMode')).toBe(false)
      expect(store.state.visible).toBe(false)
      await expect(prepareForLogout()).resolves.toBe('ready')
      expect(mountedPreparation).toHaveBeenCalledOnce()

      const execute = wrapper.getComponent(ChatViewStub).props('executeCommand') as (command: StreamCommand) => Promise<boolean>
      await expect(execute({ type: 'REFRESH_DATA', payload: { target: 'board_state' } })).resolves.toBe(true)
      expect(refreshAllBoardState).not.toHaveBeenCalled()
      expect(boardInvalidation.publishBoardInvalidation).toHaveBeenCalledExactlyOnceWith(1, 'chat-tool')
      boardInvalidation.publishBoardInvalidation.mockClear()
      await expect(execute({ type: 'REFRESH_DATA', payload: { target: 'run_history' } })).resolves.toBe(true)
      await expect(execute({ type: 'REFRESH_DATA', payload: { target: 'not-a-target' } } as unknown as StreamCommand)).resolves.toBe(false)
      expect(boardInvalidation.publishBoardInvalidation).not.toHaveBeenCalled()

      await router.push('/board?run=verification:7&trace=15')
      await flushPromises()
      expect(boardMounted).toHaveBeenCalledTimes(2)
      expect(chatMounted).toHaveBeenCalledOnce()
      expect(wrapper.getComponent(ChatViewStub).vm).toBe(conversation)
      expect(wrapper.getComponent(ChatViewStub).props('boardMode')).toBe(true)
      await expect(execute({ type: 'REFRESH_DATA', payload: { target: 'board_state' } })).resolves.toBe(true)
      expect(refreshAllBoardState).toHaveBeenCalledOnce()
      expect(boardInvalidation.publishBoardInvalidation).toHaveBeenCalledExactlyOnceWith(1, 'chat-tool')

      let settlePreparation!: (value: ChatLogoutPreparation) => void
      mountedPreparation.mockReturnValueOnce(new Promise(resolve => { settlePreparation = resolve }))
      await router.push('/account')
      await flushPromises()
      const pendingPreparation = prepareForLogout()
      auth.login('bob-token', { userId: 2, phone: '13900139000', username: 'bob' })
      await flushPromises()
      store.setReconciliationRequired(true)
      settlePreparation('ready')
      await expect(pendingPreparation).resolves.toBe('outcome-unknown')
      expect(store.state.reconciliationRequired).toBe(true)
      expect(chatUnmounted).toHaveBeenCalledOnce()
      expect(chatMounted).toHaveBeenCalledTimes(2)
    } finally {
      wrapper.unmount()
      store.closeChat()
      auth.logout()
    }
  })

  it('reconciles a hidden completion on the account page by invalidating persisted Board state', async () => {
    const AccountRoute = defineComponent({ setup: () => () => h('div', 'account') })
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/account', name: 'account', component: AccountRoute }
    ] })
    const auth = useAuth()
    auth.login('alice-token', { userId: 1, phone: '13800138000', username: 'alice' })
    const runningSession = {
      id: 'background-session', userId: 1, title: 'Background work', active: true,
      updatedAt: '2026-10-09T00:00:00Z', latestTerminalMessageId: null,
      latestExecutionStatus: null, hasUnreadUpdate: false
    }
    chatApi.getSessionList.mockResolvedValueOnce([runningSession]).mockResolvedValue([{
      ...runningSession, active: false, latestTerminalMessageId: 33,
      latestExecutionStatus: 'COMPLETED', hasUnreadUpdate: true
    }])
    await router.push('/account')
    const wrapper = mount(App, { global: { plugins: [router, i18n] } })
    const store = useChatStore()
    try {
      await flushPromises()
      expect(store.state.activeCount).toBe(1)
      window.dispatchEvent(new Event('focus'))
      await flushPromises()

      expect(boardInvalidation.publishBoardInvalidation).toHaveBeenCalledExactlyOnceWith(1, 'chat-tool')
      expect(store.state.activeCount).toBe(0)
      expect(store.state.unreadCount).toBe(1)
      expect(store.state.reconciliationRequired).toBe(false)
      window.dispatchEvent(new Event('focus'))
      await flushPromises()
      expect(boardInvalidation.publishBoardInvalidation).toHaveBeenCalledOnce()
    } finally {
      wrapper.unmount()
      auth.logout()
    }
  })

  it('reconciles the Board when a hidden observer sees a background session finish', async () => {
    const refreshAllBoardState = vi.fn().mockResolvedValue(true)
    const BoardRoute = defineComponent({
      setup(_, { expose }) {
        expose({ refreshAllBoardState })
        return () => h('div', 'board')
      }
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/board', name: 'board', component: BoardRoute }],
    })
    const auth = useAuth()
    auth.login('alice-token', {
      userId: 1,
      phone: '13800138000',
      username: 'alice',
    })
    const runningSession = {
      id: 'background-session',
      userId: 1,
      title: 'Background work',
      updatedAt: '2026-08-01T12:00:00Z',
      active: true,
      latestTerminalMessageId: null,
      latestExecutionStatus: null,
      hasUnreadUpdate: false
    }
    chatApi.getSessionList
      .mockResolvedValueOnce([runningSession])
      .mockResolvedValue([{ ...runningSession,
        active: false,
        latestTerminalMessageId: 21,
        latestExecutionStatus: 'COMPLETED',
        hasUnreadUpdate: true
      }])
    const store = useChatStore()
    store.closeChat()
    await router.push('/board')
    await router.isReady()

    const wrapper = mount(App, { global: { plugins: [router, i18n] } })
    await flushPromises()
    expect(store.state.activeCount).toBe(1)

    window.dispatchEvent(new Event('focus'))
    await flushPromises()

    expect(refreshAllBoardState).toHaveBeenCalledOnce()
    expect(store.state.activeCount).toBe(0)
    expect(store.state.unreadCount).toBe(1)
    expect(store.state.reconciliationRequired).toBe(false)
    wrapper.unmount()
    auth.logout()
  })

  it('reconciles a terminal message that completed between hidden-session polls', async () => {
    const refreshAllBoardState = vi.fn().mockResolvedValue(true)
    const BoardRoute = defineComponent({
      setup(_, { expose }) {
        expose({ refreshAllBoardState })
        return () => h('div', 'board')
      }
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/board', name: 'board', component: BoardRoute }],
    })
    const auth = useAuth()
    auth.login('alice-token', {
      userId: 1,
      phone: '13800138000',
      username: 'alice',
    })
    chatApi.getSessionList
      .mockResolvedValueOnce([])
      .mockResolvedValue([{
        id: 'fast-background-session',
        userId: 1,
        title: 'Fast background work',
        updatedAt: '2026-08-01T12:00:01Z',
        active: false,
        latestTerminalMessageId: 25,
        latestExecutionStatus: 'COMPLETED',
        hasUnreadUpdate: true
      }])
    const store = useChatStore()
    store.closeChat()
    await router.push('/board')
    await router.isReady()

    const wrapper = mount(App, { global: { plugins: [router, i18n] } })
    await flushPromises()
    window.dispatchEvent(new Event('focus'))
    await flushPromises()

    expect(refreshAllBoardState).toHaveBeenCalledOnce()
    expect(store.state.unreadCount).toBe(1)
    expect(store.state.reconciliationRequired).toBe(false)
    wrapper.unmount()
    auth.logout()
  })

  it('keeps failed background reconciliation visible and retries it', async () => {
    const refreshAllBoardState = vi.fn()
      .mockResolvedValueOnce(false)
      .mockResolvedValueOnce(true)
    const BoardRoute = defineComponent({
      setup(_, { expose }) {
        expose({ refreshAllBoardState })
        return () => h('div', 'board')
      }
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/board', name: 'board', component: BoardRoute }],
    })
    const auth = useAuth()
    auth.login('alice-token', {
      userId: 1,
      phone: '13800138000',
      username: 'alice',
    })
    const runningSession = {
      id: 'retry-session',
      userId: 1,
      title: 'Retry background sync',
      updatedAt: '2026-08-01T12:00:00Z',
      active: true,
      latestTerminalMessageId: null,
      latestExecutionStatus: null,
      hasUnreadUpdate: false
    }
    chatApi.getSessionList
      .mockResolvedValueOnce([runningSession])
      .mockResolvedValue([{ ...runningSession,
        active: false,
        latestTerminalMessageId: 31,
        latestExecutionStatus: 'PARTIAL',
        hasUnreadUpdate: true
      }])
    const store = useChatStore()
    store.closeChat()
    await router.push('/board')
    await router.isReady()

    const wrapper = mount(App, { global: { plugins: [router, i18n] } })
    await flushPromises()
    window.dispatchEvent(new Event('focus'))
    await flushPromises()

    expect(refreshAllBoardState).toHaveBeenCalledTimes(1)
    expect(store.state.reconciliationRequired).toBe(true)

    window.dispatchEvent(new Event('focus'))
    await flushPromises()

    expect(refreshAllBoardState).toHaveBeenCalledTimes(2)
    expect(store.state.reconciliationRequired).toBe(false)
    wrapper.unmount()
    auth.logout()
  })

  it('unmounts the board route when navigation leaves the workspace', async () => {
    const boardUnmounted = vi.fn()
    const BoardRoute = defineComponent({
      setup() {
        onBeforeUnmount(boardUnmounted)
        return () => h('div', 'board')
      },
    })
    const PublicRoute = defineComponent({
      setup: () => () => h('div', 'public'),
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/board', name: 'board', component: BoardRoute },
        { path: '/', name: 'landing', component: PublicRoute, meta: { public: true } },
      ],
    })

    const auth = useAuth()
    auth.login('alice-token', {
      userId: 1,
      phone: '13800138000',
      username: 'alice',
    })
    useChatStore().closeChat()
    await router.push('/board')
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [router, i18n] } })

    await router.push('/')
    await flushPromises()
    await nextTick()

    expect(boardUnmounted).toHaveBeenCalledOnce()
    wrapper.unmount()
    auth.logout()
  })

  it('keeps a private route mounted across query changes within the same view', async () => {
    const boardMounted = vi.fn()
    const boardUnmounted = vi.fn()
    const BoardRoute = defineComponent({
      setup() {
        onMounted(boardMounted)
        onBeforeUnmount(boardUnmounted)
        return () => h('div', 'board')
      },
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/board', name: 'board', component: BoardRoute }],
    })
    const auth = useAuth()
    auth.login('alice-token', {
      userId: 1,
      phone: '13800138000',
      username: 'alice',
    })

    await router.push('/board')
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [router, i18n] } })
    await flushPromises()
    expect(boardMounted).toHaveBeenCalledOnce()

    // Query params address content *within* a view (the board's open run). Remounting on
    // every param change would discard the state the URL exists to restore.
    await router.push('/board?run=verification:12')
    await flushPromises()
    await router.push('/board?run=verification:12&trace=34')
    await flushPromises()
    await router.push('/board')
    await flushPromises()

    expect(boardUnmounted).not.toHaveBeenCalled()
    expect(boardMounted).toHaveBeenCalledOnce()

    wrapper.unmount()
    auth.logout()
  })

  it('remounts a private route when the authenticated user changes', async () => {
    const boardMounted = vi.fn()
    const boardUnmounted = vi.fn()
    const BoardRoute = defineComponent({
      setup() {
        onMounted(boardMounted)
        onBeforeUnmount(boardUnmounted)
        return () => h('div', 'board')
      },
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/board', name: 'board', component: BoardRoute }],
    })
    const auth = useAuth()
    auth.login('alice-token', {
      userId: 1,
      phone: '13800138000',
      username: 'alice',
    })

    await router.push('/board')
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [router, i18n] } })
    await flushPromises()
    expect(boardMounted).toHaveBeenCalledOnce()

    const chatStore = useChatStore()
    chatStore.setActiveCount(2)
    chatStore.setUnreadCount(1)
    chatStore.setReconciliationRequired(true)

    auth.login('bob-token', {
      userId: 2,
      phone: '13900139000',
      username: 'bob',
    })
    await nextTick()
    await flushPromises()

    expect(boardUnmounted).toHaveBeenCalledOnce()
    expect(boardMounted).toHaveBeenCalledTimes(2)
    expect(chatStore.state.activeCount).toBe(0)
    expect(chatStore.state.unreadCount).toBe(0)
    expect(chatStore.state.reconciliationRequired).toBe(false)

    auth.login('bob-token-refreshed', {
      userId: 2,
      phone: '13900139000',
      username: 'bob',
    })
    await nextTick()
    expect(boardUnmounted).toHaveBeenCalledOnce()
    expect(boardMounted).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    auth.logout()
  })

  it('unmounts a private route and redirects to login after cross-tab logout', async () => {
    const boardUnmounted = vi.fn()
    const BoardRoute = defineComponent({
      setup() {
        onBeforeUnmount(boardUnmounted)
        return () => h('div', 'private board')
      },
    })
    const LandingRoute = defineComponent({
      setup: () => () => h('div', 'login'),
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/board', name: 'board', component: BoardRoute },
        { path: '/', name: 'landing', component: LandingRoute, meta: { public: true } },
      ],
    })
    const auth = useAuth()
    auth.login('alice-token', {
      userId: 1,
      phone: '13800138000',
      username: 'alice',
    })
    await router.push('/board')
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [router, i18n] } })
    await flushPromises()

    auth.logout()
    await flushPromises()
    await nextTick()

    expect(boardUnmounted).toHaveBeenCalledOnce()
    expect(router.currentRoute.value.path).toBe('/')
    expect(router.currentRoute.value.query).toEqual({ mode: 'login', redirect: '/board' })
    expect(wrapper.text()).toContain('login')
    expect(wrapper.text()).not.toContain('private board')
    wrapper.unmount()
  })

  it('contains an initial lazy route failure until a route is successfully loaded', async () => {
    let rejectPage!: (error: Error) => void
    const page = new Promise<never>((_resolve, reject) => { rejectPage = reject })
    const RecoveredRoute = defineComponent({ setup: () => () => h('p', 'Recovered route') })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'board', component: () => page },
        { path: '/recovered', name: 'recovered', component: RecoveredRoute, meta: { public: true } }
      ]
    })
    const auth = useAuth()
    auth.login('alice-token', { userId: 1, phone: '13800138000', username: 'alice' })
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const consoleWarn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const wrapper = mount(App, { global: { plugins: [router, i18n] } })
    try {
      await flushPromises()
      expect(router.currentRoute.value.matched).toHaveLength(0)
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      const cause = 'Failed to fetch dynamically imported module: /assets/Board-test.js'
      rejectPage(new Error(cause))
      await flushPromises()

      const fallback = wrapper.get('[data-testid="app-error-fallback"]')
      expect(fallback.attributes('role')).toBe('alert')
      expect(fallback.text()).toContain(i18n.global.t('app.viewErrorTitle'))
      expect(fallback.findAll('button').map(button => button.text())).toEqual([i18n.global.t('app.reloadPage')])
      expect(fallback.find('[data-testid="failure-cause"]').exists()).toBe(false)
      expect(fallback.get('[data-testid="failure-technical-details"]').attributes('open')).toBeUndefined()
      expect(fallback.get('[data-testid="failure-technical-details"]').text()).toContain(cause)
      expect(auth.getToken()).toBe('alice-token')

      await router.push('/recovered')
      await flushPromises()
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      expect(wrapper.text()).toContain('Recovered route')
    } finally {
      wrapper.unmount()
      auth.logout()
      consoleError.mockRestore()
      consoleWarn.mockRestore()
    }
  })

  it('leaves a later route rejection to the current view and removes its router handlers on unmount', async () => {
    const cause = new Error('Failed to fetch dynamically imported module: /assets/Board-test.js')
    const LandingRoute = defineComponent({ setup: () => () => h('p', 'Current login view') })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'landing', component: LandingRoute, meta: { public: true } },
        { path: '/board', name: 'board', component: () => Promise.reject(cause) }
      ]
    })
    await router.push('/')
    await router.isReady()
    const removedErrorHandler = vi.fn()
    const removedSuccessHandler = vi.fn()
    const registerError = router.onError.bind(router)
    const registerSuccess = router.afterEach.bind(router)
    const errorRegistration = vi.spyOn(router, 'onError').mockImplementation(handler => {
      const remove = registerError(handler)
      return () => { removedErrorHandler(); remove() }
    })
    const successRegistration = vi.spyOn(router, 'afterEach').mockImplementation(handler => {
      const remove = registerSuccess(handler)
      return () => { removedSuccessHandler(); remove() }
    })
    const wrapper = mount(App, { global: { plugins: [router, i18n] } })
    try {
      await expect(router.push('/board')).rejects.toBe(cause)
      await flushPromises()
      expect(router.currentRoute.value.name).toBe('landing')
      expect(wrapper.text()).toContain('Current login view')
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      expect(errorRegistration).toHaveBeenCalledOnce()
      expect(successRegistration).toHaveBeenCalledOnce()
    } finally {
      wrapper.unmount()
      errorRegistration.mockRestore()
      successRegistration.mockRestore()
    }
    expect(removedErrorHandler).toHaveBeenCalledOnce()
    expect(removedSuccessHandler).toHaveBeenCalledOnce()
  })
})
