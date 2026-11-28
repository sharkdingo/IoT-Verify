<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import InfoTooltip from '@/components/common/InfoTooltip.vue'
import ToggleSwitch from '@/components/common/ToggleSwitch.vue'

const props = defineProps<{
  kind: 'verification' | 'simulation'
  disabled: boolean
  privacyRequired?: boolean
}>()
const privacy = defineModel<boolean>('privacy', { required: true })
const asyncMode = defineModel<boolean>('asyncMode', { required: true })
const { t } = useI18n()
const privacyDescriptionId = computed(() => props.privacyRequired ? `${props.kind}-privacy-required` : undefined)
</script>

<template>
  <div class="board-card p-3 rounded-xl">
    <div class="flex items-center justify-between gap-3">
      <div class="flex min-w-0 items-center gap-3">
        <div class="w-8 h-8 shrink-0 board-chip-info rounded-lg flex items-center justify-center">
          <span class="material-symbols-outlined board-text-info text-lg">security</span>
        </div>
        <label class="board-form-label">{{ t('app.privacyAnalysis') }}</label>
        <InfoTooltip
          :text="t(privacyRequired ? 'app.privacyModelRequiredHint' : 'app.privacyModelHint')"
          :label="t('app.showHelpFor', { topic: t('app.privacyAnalysis') })"
          placement="left"
          tone="sensitivity"
          :test-id="`${kind}-privacy-help`"
        />
      </div>
      <ToggleSwitch
        :checked="privacy"
        :label="t('app.privacyAnalysis')"
        tone="sensitivity"
        :test-id="`${kind}-privacy-toggle`"
        :disabled="disabled || privacyRequired"
        :describedby-id="privacyDescriptionId"
        @change="value => privacy = value"
      />
    </div>
    <p v-if="privacyDescriptionId" :id="privacyDescriptionId" :data-testid="privacyDescriptionId"
      class="mt-2 text-xs font-semibold leading-4 board-text-info">
      {{ t('app.privacyModelRequiredStatus') }}
    </p>
  </div>

  <div class="board-card p-3 rounded-xl">
    <div class="flex items-center gap-3 mb-2">
      <div class="w-8 h-8 shrink-0 board-chip-info rounded-lg flex items-center justify-center">
        <span class="material-symbols-outlined board-text-info text-lg">schedule</span>
      </div>
      <label class="board-form-label">{{ t('app.runMode') }}</label>
    </div>
    <div class="grid grid-cols-2 gap-1 rounded-lg board-bg-control p-1">
      <button
        v-for="mode in ['sync', 'async']"
        :key="mode"
        type="button"
        :disabled="disabled"
        :data-testid="`${kind}-mode-${mode}`"
        :aria-pressed="asyncMode === (mode === 'async')"
        class="board-run-mode"
        @click="asyncMode = mode === 'async'"
      >
        {{ t(mode === 'async' ? 'app.backgroundTask' : 'app.waitForResult') }}
      </button>
    </div>
    <p v-if="asyncMode" class="mt-2 text-xs leading-5 board-text-muted">
      {{ t(kind === 'verification' ? 'app.asyncVerificationModeHint' : 'app.asyncSimulationModeHint') }}
    </p>
  </div>
</template>
