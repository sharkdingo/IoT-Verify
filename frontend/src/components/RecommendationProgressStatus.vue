<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { InteractiveOperationStage } from '@/types/task'

const props = defineProps<{
  elapsedSeconds: number
  stage: InteractiveOperationStage
  templateCount: number
  deviceCount: number
  ruleCount: number
  specCount: number
}>()

const { t } = useI18n()

const phaseLabel = computed(() => {
  switch (props.stage) {
    case 'QUEUED': return t('app.recommendationProgressQueued')
    case 'PREPARING_CONTEXT': return t('app.recommendationProgressSubmitting')
    case 'REQUESTING_MODEL': return t('app.recommendationProgressAnalyzing')
    case 'VALIDATING_RESULT': return t('app.recommendationProgressValidating')
    case 'CANCELLING': return t('app.recommendationProgressCancelling')
    default: return t('app.recommendationProgressStillWorking')
  }
})
</script>

<template>
  <div class="recommendation-progress" role="status" aria-live="polite">
    <div class="recommendation-progress__heading">
      <span class="material-symbols-outlined recommendation-progress__spinner" aria-hidden="true">progress_activity</span>
      <strong class="board-section-title">{{ t('app.recommendationProgressTitle') }}</strong>
      <span>{{ t('app.recommendationProgressElapsed', { seconds: elapsedSeconds }) }}</span>
    </div>
    <p>
      {{ t('app.recommendationProgressContext', {
        templates: templateCount,
        devices: deviceCount,
        rules: ruleCount,
        specs: specCount
      }) }}
    </p>
    <p class="recommendation-progress__phase">{{ phaseLabel }}</p>
  </div>
</template>

<style scoped>
.recommendation-progress {
  border-left: 3px solid var(--info);
  background: var(--surface-muted);
  padding: var(--iot-space-md);
  color: var(--text-muted);
  font-size: var(--iot-font-caption);
  line-height: 1.45;
}

.recommendation-progress__heading {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  color: var(--text);
}

.recommendation-progress__heading span:last-child {
  margin-left: auto;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

.recommendation-progress p {
  margin: 0.25rem 0 0;
}

.recommendation-progress__phase {
  color: var(--info);
  font-weight: 600;
}

.recommendation-progress__spinner {
  font-size: 1rem;
  animation: recommendation-progress-spin 1.1s linear infinite;
}

@keyframes recommendation-progress-spin {
  to { transform: rotate(360deg); }
}
</style>
