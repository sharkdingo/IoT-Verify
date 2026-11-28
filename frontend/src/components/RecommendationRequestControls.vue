<script setup lang="ts">
import { defineAsyncComponent } from 'vue'
import { useI18n } from 'vue-i18n'
import FailureDetails from '@/components/FailureDetails.vue'
import InfoTooltip from '@/components/common/InfoTooltip.vue'
import type { InteractiveOperationStage } from '@/types/task'
import type { ApiErrorDetails } from '@/utils/userMessage'

const RecommendationProgressStatus = defineAsyncComponent(() => import('./RecommendationProgressStatus.vue'))
const AI_RECOMMENDATION_REQUIREMENT_MAX_LENGTH = 2000
const requirementPlaceholderKeys = {
  device: 'app.deviceRecommendationPlaceholder',
  rule: 'app.ruleRecommendationPlaceholder',
  spec: 'app.specRecommendationPlaceholder'
} as const

defineProps<{
  kind: 'device' | 'rule' | 'spec'
  inputDisabled?: boolean
  actionDisabled: boolean
  requestPending: boolean
  actionLabel: string
  progress?: {
    elapsedSeconds: number
    stage: InteractiveOperationStage
    templateCount: number
    deviceCount: number
    ruleCount: number
    specCount: number
  }
  failure?: ApiErrorDetails & { message: string; suggestion: string }
}>()
const requirement = defineModel<string>('requirement', { required: true })
const emit = defineEmits<{ action: [] }>()
const { t } = useI18n()
</script>

<template>
  <div class="board-form-label rounded-lg border board-border-subtle board-bg-elevated p-2 block">
    <div class="flex items-center justify-between gap-3 pr-3">
      <label :for="`${kind}-recommendation-requirement`" class="board-form-label">{{ t('app.recommendationScenario') }}</label>
      <InfoTooltip :text="t('app.recommendationBasisHint')" :label="t('app.showHelpFor', { topic: t('app.recommendationScenario') })"
        :test-id="`${kind}-recommendation-help`" />
    </div>
    <textarea
      :id="`${kind}-recommendation-requirement`"
      v-model.trim="requirement"
      :disabled="inputDisabled"
      rows="3"
      :maxlength="AI_RECOMMENDATION_REQUIREMENT_MAX_LENGTH"
      :placeholder="t(requirementPlaceholderKeys[kind])"
      class="board-field mt-1 resize-none leading-relaxed"
    ></textarea>
  </div>

  <button
    type="button"
    :data-testid="`generate-${kind}-recommendations`"
    :disabled="actionDisabled"
    class="board-panel-submit board-action-disarmed"
    :class="progress ? 'bg-[color:var(--danger-fill)] hover:bg-[color:var(--danger-fill-hover)]' : 'bg-[color:var(--accent-fill)] hover:bg-[color:var(--accent-fill-hover)]'"
    @click="emit('action')"
  >
    <span class="material-symbols-outlined text-base">
      {{ progress ? 'stop' : requestPending ? 'refresh' : 'auto_awesome' }}
    </span>
    {{ actionLabel }}
  </button>

  <RecommendationProgressStatus v-if="progress" v-bind="progress" />

  <div v-if="failure" :data-testid="`${kind}-recommendation-error`"
    role="alert" class="rounded-lg board-surface-danger px-3 py-2 text-xs leading-5 board-text-danger">
    <p class="font-semibold">{{ failure.message }}</p>
    <FailureDetails v-bind="failure" />
  </div>
</template>
