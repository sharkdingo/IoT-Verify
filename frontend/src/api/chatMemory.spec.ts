import { beforeEach, expect, it, vi } from 'vitest'
import { getTaskMemory } from './chatMemory'
import api from '@/api/http'
vi.mock('@/api/http', () => ({ default: { get: vi.fn() } }))
const memory = { status: 'CURRENT', throughMessageId: 5, sceneChanged: false,
  items: [{ id: '5:0', kind: 'CONSTRAINT', scope: 'SESSION', sourceMessageId: 5, quote: '不要删除门锁' }] }
beforeEach(() => vi.clearAllMocks())
it('returns validated source-bearing task memory', async () => {
  vi.mocked(api.get).mockResolvedValue({ data: { data: memory } })
  expect(await getTaskMemory('a/b')).toEqual(memory)
  expect(api.get).toHaveBeenCalledWith('/chat/sessions/a%2Fb/memory', { signal: undefined })
})
it.each([
  { ...memory, status: 'COMPLETE' }, { ...memory, sceneChanged: 'false' },
  { ...memory, items: [{ ...memory.items[0], sourceMessageId: 6 }] },
  { ...memory, items: [{ ...memory.items[0], quote: null }] },
  { ...memory, items: [{ ...memory.items[0], scope: 'UNKNOWN' }] },
])('rejects malformed memory instead of showing an empty success', async value => {
  vi.mocked(api.get).mockResolvedValue({ data: { data: value } })
  await expect(getTaskMemory('s')).rejects.toThrow()
})
