<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import FailureDetails from './FailureDetails.vue'
import { useChatStore } from '@/stores/chat'
import { useModalAccessibility } from '@/composables/useModalAccessibility'
import { errorDetails } from '@/utils/userMessage'

const props = defineProps<{ error: unknown }>()
const emit = defineEmits<{ retry: [] }>()
const { t } = useI18n()
const chatStore = useChatStore()
// A crashed view cannot establish whether its last turn changed the Board.
chatStore.setReconciliationRequired(true)
const visible = computed(() => chatStore.state.visible)
const details = computed(() => errorDetails(props.error).details)
const { setDialogRef, handleModalKeydown } = useModalAccessibility(
  visible, () => chatStore.closeChat(),
  () => document.querySelector<HTMLElement>('[data-testid="open-ai-assistant"]'),
  { trapFocus: false }
)
const reloadPage = () => window.location.reload()
</script>

<template>
  <section v-show="visible" :ref="setDialogRef"
    class="assistant-recovery-panel iot-dialog iot-dialog--md iot-dialog--warning"
    data-testid="assistant-recovery-panel" role="region" :aria-label="t('app.aiAssistant')"
    tabindex="-1" @keydown="handleModalKeydown">
    <header class="iot-dialog__header">
      <div class="iot-dialog__icon" aria-hidden="true"><span class="material-symbols-outlined">smart_toy</span></div>
      <div class="iot-dialog__heading"><h2 class="iot-dialog__title">{{ t('app.chat.viewErrorTitle') }}</h2></div>
      <button type="button" class="iot-dialog__close" data-testid="assistant-recovery-close"
        :aria-label="t('app.close')" @click="chatStore.closeChat()">
        <span class="material-symbols-outlined" aria-hidden="true">close</span>
      </button>
    </header>
    <div class="iot-dialog__body iot-scroll-region" role="alert">
      <p>{{ t('app.chat.viewErrorMessage') }}</p>
      <FailureDetails :details="details" suggestion="" />
    </div>
    <footer class="iot-dialog__footer">
      <button type="button" class="iot-dialog-btn iot-dialog-btn--ghost" @click="reloadPage">{{ t('app.reloadPage') }}</button>
      <button type="button" class="iot-dialog-btn iot-dialog-btn--primary" data-testid="assistant-recovery-retry"
        autofocus @click="emit('retry')">{{ t('app.chat.reopenAssistant') }}</button>
    </footer>
  </section>
</template>

<style scoped>
/* A failed assistant must remain reachable above the canvas, including while Board data is unknown. */
.assistant-recovery-panel {
  position: fixed;
  right: var(--iot-space-lg);
  bottom: var(--iot-space-lg);
  z-index: var(--z-chat-panel);
  width: min(26rem, calc(100vw - 2rem));
}
</style>
