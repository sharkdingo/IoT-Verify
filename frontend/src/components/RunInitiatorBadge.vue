<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { RunInitiator } from '@/types/model'

defineProps<{
  initiator: RunInitiator
}>()

const { t } = useI18n()
</script>

<template>
  <span
    v-if="initiator !== 'USER'"
    class="board-meta-chip inline-flex max-w-full items-center gap-1 rounded-full border px-2 py-0.5 board-badge"
    :data-testid="`run-initiator-${initiator.toLowerCase()}`"
  >
    <span class="material-symbols-outlined text-[13px]" aria-hidden="true">
      {{ initiator === 'AI_ASSISTANT' ? 'smart_toy' : 'help' }}
    </span>
    <span class="truncate">
      {{ initiator === 'AI_ASSISTANT' ? t('app.runInitiatedByAssistant') : t('app.runInitiatorUnknown') }}
    </span>
  </span>
</template>
