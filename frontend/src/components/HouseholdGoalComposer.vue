<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { REQUEST_LIMITS } from '@/constants/requestLimits'

const props = defineProps<{ disabled: boolean; draftOccupied: boolean }>()
const emit = defineEmits<{ draft: [message: string] }>()
const { t } = useI18n()
const want = ref('')
const avoid = ref('')
const preserve = ref('')
const message = computed(() => t('household.prompt', {
  want: want.value.trim(),
  avoid: avoid.value.trim() || t('household.unspecified'),
  preserve: preserve.value.trim() || t('household.unspecified')
}))
const blockedReason = computed(() => props.disabled ? t('household.unavailable')
  : props.draftOccupied ? t('household.draftOccupied')
  : !want.value.trim() ? t('household.needGoal')
  : message.value.length > REQUEST_LIMITS.chatContentCharacters ? t('household.tooLong') : '')
const prepareDraft = () => {
  if (!blockedReason.value) emit('draft', message.value)
}
</script>

<template>
  <section class="household-goals" data-testid="household-goal-composer" aria-labelledby="household-goal-title">
    <h3 id="household-goal-title">{{ t('household.title') }}</h3>
    <p>{{ t('household.introduction') }}</p>
    <label for="household-want">{{ t('household.want') }}</label>
    <textarea id="household-want" v-model="want" data-testid="household-want" rows="2"
      :disabled="disabled" :maxlength="REQUEST_LIMITS.chatContentCharacters" :placeholder="t('household.wantPlaceholder')" />
    <div class="household-goals__constraints">
      <div>
        <label for="household-avoid">{{ t('household.avoid') }}</label>
        <textarea id="household-avoid" v-model="avoid" data-testid="household-avoid" rows="2"
          :disabled="disabled" :maxlength="REQUEST_LIMITS.chatContentCharacters" :placeholder="t('household.avoidPlaceholder')" />
      </div>
      <div>
        <label for="household-preserve">{{ t('household.preserve') }}</label>
        <textarea id="household-preserve" v-model="preserve" data-testid="household-preserve" rows="2"
          :disabled="disabled" :maxlength="REQUEST_LIMITS.chatContentCharacters" :placeholder="t('household.preservePlaceholder')" />
      </div>
    </div>
    <p id="household-draft-help" class="household-goals__help" aria-live="polite">{{ blockedReason || t('household.reviewFirst') }}</p>
    <button type="button" class="iot-action-label" data-testid="household-draft" :disabled="!!blockedReason"
      aria-describedby="household-draft-help" @click="prepareDraft">{{ t('household.draft') }}</button>
  </section>
</template>

<style scoped>
.household-goals { width: 100%; max-width: 44rem; text-align: start; color: var(--text); }
.household-goals h3 { margin: 0; font-size: var(--iot-font-control); font-weight: 600; line-height: 1.4; }
.household-goals p { margin: var(--iot-space-xs) 0 var(--iot-space-lg); font-size: var(--iot-font-label); line-height: 1.6; color: var(--text-muted); }
.household-goals label { display: block; margin: 0 0 var(--iot-space-xs); font-size: var(--iot-font-label); font-weight: 600; }
.household-goals textarea { display: block; box-sizing: border-box; width: 100%; min-height: 3.5rem; resize: vertical; border: 1px solid var(--field-border); border-radius: var(--iot-radius-control); padding: var(--iot-space-sm) var(--iot-space-md); background: var(--surface); color: var(--text); font: inherit; font-size: var(--iot-font-control); line-height: 1.5; caret-color: var(--accent-strong); }
.household-goals__constraints { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(14rem, 100%), 1fr)); gap: var(--iot-space-lg); margin-top: var(--iot-space-lg); }
.household-goals__constraints > div { min-width: 0; }
.household-goals textarea::placeholder { color: var(--text-muted); opacity: 1; }
.household-goals textarea:focus-visible, .household-goals button:focus-visible { outline: 2px solid var(--accent-strong); outline-offset: 3px; }
.household-goals .household-goals__help { margin: var(--iot-space-md) 0; }
.household-goals button { min-height: var(--iot-target-min); padding: var(--iot-space-sm) var(--iot-space-lg); border: 1px solid var(--control-border); border-radius: var(--iot-radius-control); background: var(--surface-elevated); color: var(--text); font-weight: 600; }
.household-goals button:hover:not(:disabled) { background: var(--surface-control-hover); }
.household-goals button:disabled { cursor: not-allowed; color: var(--text-muted); }
</style>
