<script setup lang="ts">
import { computed, onErrorCaptured, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import FailureDetails from './FailureDetails.vue'
import { errorDetails, technicalErrorLines } from '@/utils/userMessage'

const props = defineProps<{ resetKey?: string | number; error?: unknown }>()
const { t } = useI18n()
const capturedError = ref<unknown>(null)
const capturedModuleLoadFailure = ref(false)
const renderEpoch = ref(0)
const activeError = computed(() => props.error ?? capturedError.value)
const technicalDetails = computed(() => technicalErrorLines(errorDetails(activeError.value)))

onErrorCaptured((error, _instance, info) => {
  capturedError.value = error
  // Vue identifies loader failures in both dev and production; a cached failed import needs a new document.
  capturedModuleLoadFailure.value = info === 'async component loader' || info.endsWith('#runtime-13')
  console.error('Vue subtree failed to render:', info, error)
  return false
})

watch(() => props.resetKey, () => {
  capturedError.value = null
  capturedModuleLoadFailure.value = false
  renderEpoch.value += 1
})

const retry = () => {
  if (capturedModuleLoadFailure.value) {
    reloadPage()
    return
  }
  capturedError.value = null
  renderEpoch.value += 1
}

const reloadPage = () => window.location.reload()
</script>

<template>
  <template v-if="activeError == null">
    <div :key="renderEpoch" class="error-boundary-content">
      <slot />
    </div>
  </template>
  <slot v-else name="fallback" :error="activeError" :retry="retry">
    <section class="error-boundary-fallback" role="alert" data-testid="app-error-fallback">
      <div class="error-boundary-fallback__icon" aria-hidden="true">error_outline</div>
      <h2 class="board-panel-title">{{ t('app.viewErrorTitle') }}</h2>
      <FailureDetails class="error-boundary-fallback__details" :details="technicalDetails" suggestion="" />
      <div class="error-boundary-fallback__actions">
        <button v-if="error == null && !capturedModuleLoadFailure" type="button" class="error-boundary-fallback__button board-action-inline" @click="retry">
          {{ t('app.retry') }}
        </button>
        <button type="button" class="error-boundary-fallback__button board-action-secondary" @click="reloadPage">
          {{ t('app.reloadPage') }}
        </button>
      </div>
    </section>
  </slot>
</template>

<style scoped>
.error-boundary-content {
  display: contents;
}

.error-boundary-fallback {
  min-height: 220px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--iot-space-md);
  padding: var(--iot-space-2xl);
  color: var(--text);
  text-align: center;
  background: var(--surface);
}

.error-boundary-fallback__icon {
  color: var(--danger);
  font-family: 'Material Symbols Outlined';
  font-size: 42px;
}

.error-boundary-fallback h2 {
  margin: 0;
}

.error-boundary-fallback__details {
  width: 100%;
  min-width: 0;
  max-width: 48rem;
  text-align: left;
}

.error-boundary-fallback__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--iot-space-sm);
  margin-top: var(--iot-space-sm);
}

</style>
