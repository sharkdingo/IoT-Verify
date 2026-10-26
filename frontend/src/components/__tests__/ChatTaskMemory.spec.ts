// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { expect, it, vi, beforeEach } from 'vitest'
import { i18n } from '@/assets/i18n'
import ChatTaskMemory from '../ChatTaskMemory.vue'
import { getTaskMemory } from '@/api/chatMemory'
import { getSessionHistory } from '@/api/chat'
import { subscribeBoardInvalidation } from '@/utils/boardInvalidation'
vi.mock('@/api/chatMemory', () => ({ getTaskMemory: vi.fn() }))
vi.mock('@/api/chat', () => ({ getSessionHistory: vi.fn() }))
vi.mock('@/utils/boardInvalidation', () => ({ subscribeBoardInvalidation: vi.fn(() => vi.fn()) }))
const memory = { status: 'CURRENT' as const, throughMessageId: 5, sceneChanged: false,
  items: [{ id: '5:0', kind: 'CONSTRAINT' as const, scope: 'SESSION' as const, sourceMessageId: 5, quote: '不要删除门锁' }] }
beforeEach(() => { vi.clearAllMocks(); vi.mocked(getTaskMemory).mockResolvedValue(memory) })
const create = () => mount(ChatTaskMemory, { props: { userId: 7, sessionId: 's1', busy: false, revision: 1 }, global: { plugins: [i18n] } })
it('starts collapsed, loads on open and lets users inspect the exact source', async () => {
  const wrapper = create()
  expect(wrapper.get('details').attributes('open')).toBeUndefined()
  expect(getTaskMemory).not.toHaveBeenCalled()
  const details = wrapper.get('details')
  ;(details.element as HTMLDetailsElement).open = true
  await details.trigger('toggle'); await flushPromises()
  expect(wrapper.text()).toContain('不要删除门锁')
  vi.mocked(getSessionHistory).mockResolvedValue({ messages: [{ id: 5, sessionId: 's1', role: 'user', turnId: 't',
    createdAt: '2026-10-26', content: '这次检查时不要删除门锁，先讨论风险。' }], nextBeforeId: null, hasMore: false })
  await wrapper.get('[data-testid="task-memory-source"]').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('这次检查时不要删除门锁，先讨论风险。')
  expect(wrapper.find('[data-testid="task-memory-source"]').exists()).toBe(false)
  await wrapper.get('[data-testid="task-memory-back"]').trigger('click')
  expect(wrapper.find('blockquote').exists()).toBe(false)
  expect(wrapper.find('[data-testid="task-memory-source"]').exists()).toBe(true)
  wrapper.unmount()
})
it('discards a stale response when the conversation changes', async () => {
  let finish!: (value: typeof memory) => void
  vi.mocked(getTaskMemory).mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  const wrapper = create()
  ;(wrapper.get('details').element as HTMLDetailsElement).open = true
  await wrapper.get('details').trigger('toggle')
  await wrapper.setProps({ sessionId: 's2' })
  finish(memory); await flushPromises()
  expect(wrapper.text()).not.toContain('不要删除门锁')
  wrapper.unmount()
})
it('shows unavailable state and offers retry on a failed read', async () => {
  vi.mocked(getTaskMemory).mockRejectedValueOnce(new Error('offline'))
  const wrapper = create()
  ;(wrapper.get('details').element as HTMLDetailsElement).open = true
  await wrapper.get('details').trigger('toggle'); await flushPromises()
  expect(wrapper.find('[role="alert"]').exists()).toBe(true)
  await wrapper.get('[data-testid="task-memory-retry"]').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('不要删除门锁')
  wrapper.unmount()
})
it('withdraws displayed scene notes when another tab replaces the scene and releases its subscription', async () => {
  const unsubscribe = vi.fn()
  vi.mocked(subscribeBoardInvalidation).mockReturnValueOnce(unsubscribe)
  const wrapper = create()
  ;(wrapper.get('details').element as HTMLDetailsElement).open = true
  await wrapper.get('details').trigger('toggle'); await flushPromises()
  expect(wrapper.text()).toContain('不要删除门锁')
  expect(subscribeBoardInvalidation).toHaveBeenCalledWith(7, expect.any(Function))
  vi.mocked(getTaskMemory).mockResolvedValueOnce({ ...memory, sceneChanged: true, items: [] })
  vi.mocked(subscribeBoardInvalidation).mock.calls[0]![1]({ type: 'BOARD_INVALIDATED', userId: 7, reason: 'http-mutation', issuedAt: 1 })
  await flushPromises()
  expect(wrapper.text()).not.toContain('不要删除门锁')
  wrapper.unmount()
  expect(unsubscribe).toHaveBeenCalledOnce()
})
it('hides old notes throughout a local scene replacement and refreshes after unlocking', async () => {
  const wrapper = create()
  ;(wrapper.get('details').element as HTMLDetailsElement).open = true
  await wrapper.get('details').trigger('toggle'); await flushPromises()
  await wrapper.setProps({ busy: true })
  expect(wrapper.text()).not.toContain('不要删除门锁')
  vi.mocked(getTaskMemory).mockResolvedValueOnce({ ...memory, sceneChanged: true, items: [] })
  await wrapper.setProps({ busy: false }); await flushPromises()
  expect(wrapper.text()).not.toContain('不要删除门锁')
  expect(getTaskMemory).toHaveBeenCalledTimes(2)
  wrapper.unmount()
})
