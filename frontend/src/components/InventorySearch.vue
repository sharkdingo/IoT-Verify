<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import HintTooltip from '@/components/common/HintTooltip.vue'

defineProps<{ label: string; testId: string }>()
const search = defineModel<string>({ required: true })
const { t } = useI18n()
</script>

<template>
  <label class="board-inventory-search relative block">
    <span class="material-symbols-outlined pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-sm board-text-muted" aria-hidden="true">search</span>
    <input v-model="search" :data-testid="testId" type="search" class="board-field board-field--clearable pl-8 pr-12"
      :placeholder="label" :aria-label="label" />
    <HintTooltip v-if="search" :content="t('app.clearSearch')">
      <button type="button" class="board-field-clear" :aria-label="t('app.clearSearch')" @click="search = ''">
        <span class="material-symbols-outlined text-sm" aria-hidden="true">close</span>
      </button>
    </HintTooltip>
  </label>
</template>
