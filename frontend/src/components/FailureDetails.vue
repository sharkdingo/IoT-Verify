<script setup lang="ts">
import { computed } from 'vue'
import { i18n } from '@/assets/i18n'

const props = defineProps<{
  message?: string
  cause?: string
  suggestion: string
  details?: readonly string[]
  fields?: Readonly<Record<string, string>>
}>()
const causeShownInFields = computed(() => Object.entries(props.fields || {}).some(([field, reason]) =>
  props.cause === reason || props.cause === `${field}: ${reason}`
))
const technicalDetails = computed(() => props.details?.filter(detail => detail !== props.cause && detail !== props.message) || [])

// MessageBox renders this outside the app tree; use the same global locale as feedback.ts.
const t = (key: string) => i18n.global.t(key)
</script>

<template>
  <div class="board-copy" data-testid="failure-guidance">
    <p v-if="cause && cause !== message && !causeShownInFields"
      class="mt-2 whitespace-pre-wrap break-words" data-testid="failure-cause">{{ cause }}</p>
    <dl v-if="Object.keys(fields || {}).length" class="mt-2 space-y-2" data-testid="failure-fields">
      <div v-for="(reason, field) in fields" :key="field">
        <dt class="break-words font-mono font-semibold">{{ field }}</dt>
        <dd class="whitespace-pre-wrap break-words">{{ reason }}</dd>
      </div>
    </dl>
    <p v-if="suggestion" class="mt-2" data-testid="failure-next-step">
      <strong>{{ t('app.failureNextStep') }}:</strong> {{ suggestion }}
    </p>
    <details v-if="technicalDetails.length" class="board-caption mt-3" data-testid="failure-technical-details">
      <summary class="cursor-pointer font-semibold">{{ t('app.technicalDetails') }}</summary>
      <ul class="mt-2 list-disc space-y-1 pl-5">
        <li v-for="(detail, index) in technicalDetails" :key="index" class="whitespace-pre-wrap break-words font-mono">{{ detail }}</li>
      </ul>
    </details>
  </div>
</template>
