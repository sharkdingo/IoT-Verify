<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getTaskMemory } from '@/api/chatMemory'
import { getSessionHistory } from '@/api/chat'
import { subscribeBoardInvalidation } from '@/utils/boardInvalidation'
import type { ChatTaskMemory } from '@/types/chat'

const props = defineProps<{ userId: number | null; sessionId: string; busy: boolean; revision: number }>()
const { t } = useI18n()
const opened = ref(false)
const memory = ref<ChatTaskMemory | null>(null)
const loading = ref(false)
const failed = ref(false)
const source = ref('')
const sourceFailed = ref(false)
const sourceLoading = ref(false)
let epoch = 0
let sourceEpoch = 0
let request: AbortController | null = null

const refresh = async () => {
  const current = ++epoch
  request?.abort()
  if (!opened.value || props.busy) return
  request = new AbortController()
  loading.value = true
  failed.value = false
  try {
    const result = await getTaskMemory(props.sessionId, request.signal)
    if (current === epoch) memory.value = result
  } catch {
    if (current === epoch) { memory.value = null; failed.value = true }
  } finally {
    if (current === epoch) loading.value = false
  }
}
const toggle = (event: Event) => {
  const nextOpen = (event.target as HTMLDetailsElement).open
  if (nextOpen === opened.value) return
  opened.value = nextOpen
  if (opened.value) void refresh()
}
const showSource = async (messageId: number) => {
  const current = ++sourceEpoch
  source.value = ''
  sourceFailed.value = false
  sourceLoading.value = true
  try {
    const page = await getSessionHistory(props.sessionId, { beforeId: messageId + 1, limit: 1 })
    const message = page.messages.find(item => item.id === messageId && item.role === 'user')
    if (!message) throw new Error('Original source unavailable')
    if (current === sourceEpoch) source.value = message.content
  } catch {
    if (current === sourceEpoch) sourceFailed.value = true
  } finally {
    if (current === sourceEpoch) sourceLoading.value = false
  }
}
watch(() => props.sessionId, () => {
  epoch++; sourceEpoch++; request?.abort()
  memory.value = null; opened.value = false; loading.value = false; failed.value = false
  source.value = ''; sourceFailed.value = false; sourceLoading.value = false
})
const invalidate = () => {
  memory.value = null
  sourceEpoch++; source.value = ''; sourceFailed.value = false; sourceLoading.value = false
  void refresh()
}
watch(() => [props.busy, props.revision], invalidate)
watch(() => props.userId, (userId, _previous, onCleanup) => {
  onCleanup(subscribeBoardInvalidation(userId, invalidate))
}, { immediate: true })
onBeforeUnmount(() => { epoch++; sourceEpoch++; request?.abort() })
</script>
<template>
  <details class="chat-task-memory" :open="opened" @toggle="toggle" data-testid="chat-task-memory">
    <summary>{{ t('app.chat.memory.title') }}</summary>
    <div class="chat-task-memory__body">
      <p v-if="busy || loading" role="status">{{ t('app.chat.memory.loading') }}</p>
      <div v-else-if="failed" role="alert">
        <p>{{ t('app.chat.memory.failed') }}</p>
        <button type="button" data-testid="task-memory-retry" @click="refresh">{{ t('app.retry') }}</button>
      </div>
      <template v-else-if="memory">
        <template v-if="source">
          <button type="button" data-testid="task-memory-back" @click="source = ''">{{ t('app.chat.memory.back') }}</button>
          <blockquote>{{ source }}</blockquote>
        </template>
        <template v-else>
        <p v-if="memory.status !== 'CURRENT'" role="status">{{ t('app.chat.memory.partial') }}</p>
        <p v-if="memory.sceneChanged">{{ t('app.chat.memory.sceneChanged') }}</p>
        <p v-if="memory.items.length === 0 && memory.status === 'CURRENT'">{{ t('app.chat.memory.empty') }}</p>
        <ul v-else>
          <li v-for="item in memory.items" :key="item.id">
            <span class="chat-task-memory__kind">{{ t(`app.chat.memory.kinds.${item.kind}`) }}</span>
            <span>{{ item.quote }}</span>
            <button type="button" data-testid="task-memory-source" :disabled="sourceLoading" @click="showSource(item.sourceMessageId)">
              {{ t('app.chat.memory.source') }}
            </button>
          </li>
        </ul>
        <p>{{ t('app.chat.memory.correct') }}</p>
        <p v-if="sourceLoading" role="status">{{ t('app.loading') }}</p>
        <p v-if="sourceFailed" role="alert">{{ t('app.chat.memory.sourceFailed') }}</p>
        </template>
      </template>
    </div>
  </details>
</template>

<style scoped>
.chat-task-memory { border-bottom: 1px solid var(--chat-border); color: var(--chat-text); font-size: var(--iot-font-label); }
summary { cursor: pointer; padding: 0.75rem 1rem; font-weight: 600; }
.chat-task-memory__body { padding: 0 1rem 1rem; max-height: min(12rem, 28dvh); overflow: auto; overflow-wrap: anywhere; }
p { margin: 0.5rem 0; line-height: 1.6; }
ul { list-style: none; padding: 0; margin: 0; }
li { display: flex; flex-wrap: wrap; align-items: baseline; gap: 0.35rem 0.6rem; padding: 0.5rem 0; }
li > span:nth-child(2) { flex: 1; min-width: 10rem; line-height: 1.6; }
.chat-task-memory__kind { color: var(--chat-muted); }
button { color: var(--chat-text); text-decoration: underline; text-underline-offset: 0.2em; cursor: pointer; }
button:disabled { cursor: wait; }
button:focus-visible, summary:focus-visible { outline: 2px solid var(--accent-border); outline-offset: 2px; }
blockquote { margin: 0.75rem 0 0; padding: 0.75rem; background: var(--surface-muted); white-space: pre-wrap; line-height: 1.7; }
</style>
