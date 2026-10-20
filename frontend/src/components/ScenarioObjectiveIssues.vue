<script setup lang="ts">
import type { ScenarioObjectiveIssue } from '@/api/board'

defineProps<{
  status: 'COMPLETE' | 'PARTIAL'
  issues: ScenarioObjectiveIssue[]
  title: string
  formatIssue: (issue: ScenarioObjectiveIssue) => string
}>()
</script>

<template>
  <div
    v-if="status === 'PARTIAL' && issues.length > 0"
    data-testid="scenario-objective-issues"
    class="board-surface-warning mt-3 flex w-full items-start gap-2 rounded-lg px-2.5 py-2 text-left board-caption"
    role="status"
  >
    <span class="material-symbols-outlined text-base" aria-hidden="true">flag</span>
    <div>
      <div class="board-section-title board-text-warning">{{ title }}</div>
      <ul class="mt-1 list-disc space-y-0.5 pl-4">
        <li v-for="issue in issues" :key="issue.code">
          {{ formatIssue(issue) }}
        </li>
      </ul>
    </div>
  </div>
</template>
