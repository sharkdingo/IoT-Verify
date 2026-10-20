<script setup lang="ts">
import { ref, computed, onUnmounted, type VNode } from "vue";
import { CheckOutlined, CopyOutlined } from '@ant-design/icons-vue';
import { useI18n } from 'vue-i18n';

const props = defineProps<{
  highlightVnode: VNode;
  language: string;
}>();

const copyState = ref<'idle' | 'copied' | 'failed'>('idle');
const contentRef = ref<HTMLElement>();
const { t } = useI18n();
let copyResetTimer: ReturnType<typeof setTimeout> | null = null;

function showCopyState(state: 'copied' | 'failed') {
  copyState.value = state;
  if (copyResetTimer) clearTimeout(copyResetTimer);
  copyResetTimer = setTimeout(() => {
    copyState.value = 'idle';
    copyResetTimer = null;
  }, 2000);
}

async function copyHandle() {
  if (!contentRef.value) return;
  const text = contentRef.value.textContent || "";
  try {
    if (!navigator.clipboard?.writeText) throw new Error('Clipboard API unavailable');
    await navigator.clipboard.writeText(text);
    showCopyState('copied');
  } catch {
    showCopyState('failed');
  }
}

onUnmounted(() => {
  if (copyResetTimer) clearTimeout(copyResetTimer);
});

const langLabel = computed(() => props.language?.toUpperCase() || "TEXT");
</script>

<template>
  <div class="code-block-container">
    <div class="code-header">
      <span class="lang-label">{{ langLabel }}</span>
      <button type="button" class="copy-btn" :aria-label="t('app.copy')" @click="copyHandle">
        <span v-if="copyState === 'copied'" class="copy-text" aria-live="polite">
          <CheckOutlined /> {{ t('app.copied') }}
        </span>
        <span v-else-if="copyState === 'failed'" class="copy-text" aria-live="polite">
          <CopyOutlined /> {{ t('app.copyFailed') }}
        </span>
        <span v-else class="copy-text" aria-live="polite">
          <CopyOutlined /> {{ t('app.copy') }}
        </span>
      </button>
    </div>

    <div ref="contentRef" class="code-content">
      <component :is="props.highlightVnode" />
    </div>
  </div>
</template>

<style scoped>
.code-block-container {
  margin: var(--iot-space-lg) 0;
  border-radius: var(--iot-radius-action);
  overflow: hidden;
  border: 1px solid var(--border);
  background: var(--surface-muted);
  transition: border-color var(--iot-motion-fast);
}

/* Header shares the application surface in both themes. */
.code-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--surface-control);
  padding: var(--iot-space-xs) var(--iot-space-md);
  color: var(--text-muted);
  border-bottom: 1px solid var(--border);
  user-select: none;
}

.lang-label {
  font-size: var(--iot-font-caption);
  font-weight: 600;
  color: var(--text-muted);
}

.copy-btn {
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: var(--iot-target-min);
  min-height: var(--iot-target-min);
  font-size: var(--iot-font-caption);
  color: var(--text-muted);
  transition: color var(--iot-motion-fast);
  padding: var(--iot-space-2xs);
  border: 0;
  background: transparent;
}

.copy-btn:hover {
  color: var(--text);
}

.copy-btn:focus-visible {
  outline: 2px solid var(--accent-border);
  outline-offset: 2px;
}

.copy-text {
  display: flex;
  align-items: center;
  gap: var(--iot-space-2xs);
}

.code-content {
  overflow-x: auto;
  font-family: Consolas, Monaco, 'Andale Mono', monospace;
  font-size: var(--iot-font-control);
  line-height: 1.5;
  /* Shiki owns syntax colors; unhighlighted text follows the theme. */
  color: var(--text);
}

/* Shiki's syntax ink and background form a pair; keep the padding on that painted surface. */
:deep(pre.shiki) {
  margin: 0;
  padding: var(--iot-space-md) var(--iot-space-lg);
}
</style>
