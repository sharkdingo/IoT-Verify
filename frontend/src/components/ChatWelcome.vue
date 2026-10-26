<script setup lang="ts">
import { useId, type Component } from 'vue'
import { useI18n } from 'vue-i18n'

defineProps<{
  tasks: ReadonlyArray<{ title: string; desc?: string; text: string; icon: Component }>
  disabled: boolean
}>()
const emit = defineEmits<{ select: [message: string] }>()
const { t } = useI18n()
const titleId = `chat-welcome-${useId()}`
</script>

<template>
  <section class="welcome-screen" :aria-labelledby="titleId">
    <header class="welcome-intro">
      <h2 :id="titleId">{{ t('app.chat.welcomeTitle') }}</h2>
      <p>{{ t('app.chat.welcomeHint') }}</p>
    </header>
    <ul class="task-grid" :aria-label="t('app.chat.welcomeTasks')">
      <li v-for="(task, index) in tasks" :key="index">
        <button type="button" class="task-card" data-testid="chat-preset-task"
          :disabled="disabled" @click="emit('select', task.text)">
          <component :is="task.icon" class="task-icon" aria-hidden="true" />
          <span class="task-info">
            <span class="task-title">{{ task.title }}</span>
            <span v-if="task.desc" class="task-desc">{{ task.desc }}</span>
          </span>
        </button>
      </li>
    </ul>
    <details v-if="$slots.goals" class="guided-goals" data-testid="chat-guided-goals">
      <summary>{{ t('app.chat.guidedGoals') }}</summary>
      <div class="guided-goals__body"><slot name="goals" /></div>
    </details>
  </section>
</template>

<style scoped>
.welcome-screen {
  display: flex;
  flex-direction: column;
  gap: var(--iot-space-lg);
  width: 100%;
  max-width: 44rem;
  margin: 0 auto;
  color: var(--chat-text);
}

.welcome-intro h2 {
  margin: 0;
  font-size: var(--iot-font-title);
  font-weight: 600;
  line-height: 1.4;
  text-wrap: balance;
}

.welcome-intro p {
  margin: var(--iot-space-xs) 0 0;
  color: var(--chat-muted);
  font-size: var(--iot-font-label);
  line-height: 1.6;
}

.task-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(16rem, 100%), 1fr));
  gap: var(--iot-space-xs) var(--iot-space-lg);
  margin: 0;
  padding: 0;
  list-style: none;
}

.task-grid li { min-width: 0; }

.task-card {
  display: flex;
  align-items: center;
  gap: var(--iot-space-sm);
  width: 100%;
  height: 100%;
  min-height: var(--iot-target-min);
  border: 0;
  border-bottom: 1px solid var(--chat-border);
  background: transparent;
  padding: var(--iot-space-sm) var(--iot-space-xs);
  color: var(--chat-text);
  text-align: start;
  font: inherit;
  cursor: pointer;
}

.task-card:hover:not(:disabled) { background: var(--surface-control-hover); }
.task-card:disabled { color: var(--chat-muted); cursor: not-allowed; }

.task-icon {
  flex: 0 0 auto;
  color: var(--chat-muted);
  font-size: 18px;
}

.task-info { min-width: 0; overflow-wrap: anywhere; }
.task-title { display: block; font-size: var(--iot-font-label); font-weight: 500; line-height: 1.5; }
.task-desc { display: block; color: var(--chat-muted); font-size: var(--iot-font-caption); line-height: 1.5; }

.guided-goals summary {
  align-content: center;
  min-height: var(--iot-target-min);
  color: var(--chat-text);
  font-size: var(--iot-font-label);
  cursor: pointer;
}

.guided-goals summary:hover { color: var(--chat-accent); }
.guided-goals__body { padding: var(--iot-space-sm) 0; }

.task-card:focus-visible,
.guided-goals summary:focus-visible {
  outline: 2px solid var(--accent-border);
  outline-offset: 2px;
}
</style>
