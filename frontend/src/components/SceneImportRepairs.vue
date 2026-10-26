<script setup lang="ts">
import type { Translate } from '@/views/board/portableScene'
import type { prepareSceneImport } from '@/views/board/sceneImportPreparation'

// MessageBox renders outside the app root, so translation must come from its caller.
defineProps<{ plan: ReturnType<typeof prepareSceneImport>; t: Translate }>()
</script>

<template>
  <section v-if="plan.templateRenames.length || plan.variableCorrections.length"
    class="scene-import-repairs" data-testid="scene-import-repairs" :aria-label="t('app.sceneImportRepairsTitle')">
    <h3>{{ t('app.sceneImportRepairsTitle') }}</h3>
    <p v-if="plan.templateRenames.length">{{ t('app.sceneImportKeepBothHint') }}</p>
    <ul>
      <li v-for="rename in plan.templateRenames" :key="rename.from">
        {{ t('app.sceneImportKeepBothChange', rename) }}
      </li>
      <li v-for="(correction, index) in plan.variableCorrections" :key="`variable-${index}`">
        {{ t('app.sceneImportVariableCorrection', correction) }}
      </li>
    </ul>
  </section>
</template>

<style scoped>
.scene-import-repairs { overflow-wrap: anywhere; }
.scene-import-repairs h3 { font-size: inherit; font-weight: 600; margin: 0 0 0.5rem; }
.scene-import-repairs p { margin: 0 0 0.5rem; }
.scene-import-repairs ul { margin: 0; padding-inline-start: 1.25rem; }
.scene-import-repairs li + li { margin-block-start: 0.5rem; }
</style>
