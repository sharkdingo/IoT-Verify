<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { VerificationTaskSummary } from '@/types/verify'
import type { SimulationTaskSummary } from '@/types/simulation'
import InfoTooltip from '@/components/common/InfoTooltip.vue'

defineProps<{
  kind: 'verification' | 'simulation'
  task: VerificationTaskSummary | SimulationTaskSummary
}>()
const { t } = useI18n()
</script>

<template>
  <section :data-testid="`${kind}-frozen-task-summary`" class="board-card rounded-xl p-3 text-xs leading-5"
    :aria-labelledby="`${kind}-frozen-task-title`">
    <div class="flex min-w-0 items-center gap-2">
      <h4 :id="`${kind}-frozen-task-title`" class="board-section-title">
        {{ t('app.backgroundTaskSnapshot', { id: task.id }) }}
      </h4>
      <InfoTooltip :text="t('app.backgroundTaskSnapshotHint')"
        :label="t('app.showHelpFor', { topic: t('app.backgroundTask') })" />
    </div>
    <p class="mt-2 board-text-muted">{{ t(kind === 'simulation' ? 'app.runScopeWithoutSpecs' : 'app.runScopeCounts', {
      devices: task.modelSnapshot.deviceCount, rules: task.modelSnapshot.ruleCount, specs: task.modelSnapshot.specificationCount
    }) }}</p>
    <dl v-if="'requestedSteps' in task" class="mt-3">
      <dt class="board-form-label">{{ t('app.simulationSteps') }}</dt>
      <dd class="mt-1 font-semibold" data-testid="frozen-simulation-steps">{{ task.requestedSteps }}</dd>
    </dl>
    <div class="mt-3 flex flex-wrap gap-2">
      <span class="board-meta-chip">
        {{ !task.isAttack ? t('app.runAssumptionNoAttack')
          : task.modelSemantics.attackSelectionPolicy === 'EXACT_ATTACK_POINTS'
            ? t('app.runAssumptionAttackPoints', { count: task.modelSemantics.modeledAttackPointCount })
            : t('app.runAssumptionAttackBudget', { count: task.attackBudget, total: task.modelSemantics.modeledAttackPointCount }) }}
      </span>
      <span class="board-meta-chip">{{ t(task.enablePrivacy ? 'app.runAssumptionPrivacy' : 'app.runAssumptionNoPrivacy') }}</span>
    </div>
  </section>
</template>
