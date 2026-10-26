import api from '@/api/http'
import type { ChatTaskMemory } from '@/types/chat'

export const getTaskMemory = async (sessionId: string, signal?: AbortSignal): Promise<ChatTaskMemory> => {
  const response = await api.get(`/chat/sessions/${encodeURIComponent(sessionId)}/memory`, { signal })
  const value = response.data.data
  if (!value || !['CURRENT', 'PARTIAL', 'UNAVAILABLE'].includes(value.status)
    || !Number.isSafeInteger(value.throughMessageId) || value.throughMessageId < 0
    || typeof value.sceneChanged !== 'boolean' || !Array.isArray(value.items) || value.items.length > 24
    || value.items.some((item: ChatTaskMemory['items'][number]) => !item
      || !Number.isSafeInteger(item.sourceMessageId) || item.sourceMessageId <= 0
      || item.sourceMessageId > value.throughMessageId
      || typeof item.id !== 'string' || !item.id.startsWith(`${item.sourceMessageId}:`)
      || !['GOAL', 'CONSTRAINT', 'DECISION', 'QUESTION'].includes(item.kind)
      || !['SESSION', 'SCENE'].includes(item.scope)
      || typeof item.quote !== 'string' || !item.quote.trim() || item.quote.length > 600)
    || new Set(value.items.map((item: ChatTaskMemory['items'][number]) => item.id)).size !== value.items.length) {
    throw new Error('Invalid task memory response')
  }
  return value
}
