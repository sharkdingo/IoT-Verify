<script setup lang="ts">
import HintTooltip from '@/components/common/HintTooltip.vue'
/**
 * Accessible on/off control for board run settings.
 *
 * Uses `role="switch"` + `aria-checked` so assistive technology can read the state; the
 * hand-rolled `<button>` variants this replaces exposed no name and no state at all.
 * `label` is the control's accessible name — pass the same text the adjacent visual
 * label shows, and link the description with `describedbyId` when one exists.
 */
withDefaults(defineProps<{
  checked: boolean
  label: string
  /**
   * What being *on* means, not what colour to paint.
   *
   * The prop was `'red' | 'purple' | 'cyan' | 'blue'` — colour names, so the call site said "purple"
   * when it meant "privacy", and three of the four values were not even in the declared union yet were
   * passed anyway. `enabled` is the default because a switch's on-state carries one meaning; the two
   * exceptions are real analysis dimensions the product treats as distinct, per the convention that
   * colour distinguishes *kind*:
   *  - `adversarial` — attack analysis, which changes what the model admits
   *  - `sensitivity` — privacy/sensitivity propagation
   * A preference such as "save this run to history" is not a kind, so it takes `enabled`.
   */
  tone?: 'enabled' | 'adversarial' | 'sensitivity'
  disabled?: boolean
  title?: string
  describedbyId?: string
  testId?: string
}>(), {
  tone: 'enabled',
  disabled: false,
  title: undefined,
  describedbyId: undefined,
  testId: undefined
})

const emit = defineEmits<{ (e: 'change', checked: boolean): void }>()
</script>

<template>
  <!-- `title` stays a prop: three call sites pass a *conditional* hint that explains why the switch is
       disabled, so the hint is the caller's to decide. `HintTooltip` disables itself on an empty value, which
       is why those callers can keep passing `undefined` with no `v-if`. -->
  <HintTooltip :content="title">
    <button
      type="button"
      role="switch"
      :aria-checked="checked"
      :aria-label="label"
      :aria-describedby="describedbyId"
      :data-testid="testId"
      :disabled="disabled"
      class="iot-toggle-switch"
      :class="[`iot-toggle-switch--${tone}`, { 'iot-toggle-switch--on': checked }]"
      @click="emit('change', !checked)"
    >
      <span class="iot-toggle-switch__thumb" aria-hidden="true" />
    </button>
  </HintTooltip>
</template>

<style scoped>
.iot-toggle-switch {
  position: relative;
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  width: var(--iot-target-min);
  height: var(--iot-target-min);
  padding: 0;
  border: 0;
  border-radius: var(--iot-radius-pill);
  background: transparent;
  cursor: pointer;
}

.iot-toggle-switch::before {
  content: '';
  position: absolute;
  inset: 50% 0 auto;
  height: 1.5rem;
  transform: translateY(-50%);
  border-radius: var(--iot-radius-pill);
  background: var(--control-border);
  transition: background-color 0.2s ease;
}

.iot-toggle-switch:disabled {
  cursor: not-allowed;
  opacity: 1;
}

/* Filled tracks keep the on-state thumb readable in both themes. */
.iot-toggle-switch--on.iot-toggle-switch--enabled::before { background: var(--accent-fill); }
.iot-toggle-switch--on.iot-toggle-switch--adversarial::before { background: var(--danger-fill); }
.iot-toggle-switch--on.iot-toggle-switch--sensitivity::before { background: var(--accent-fill); }
.iot-toggle-switch--on:disabled::before { background: var(--accent-fill-disabled); }

.iot-toggle-switch:focus-visible {
  outline: 2px solid var(--accent-border);
  outline-offset: 2px;
}

.iot-toggle-switch__thumb {
  position: absolute;
  left: 0.25rem;
  width: 1rem;
  height: 1rem;
  border-radius: var(--iot-radius-pill);
  background: var(--surface-elevated);
  box-shadow: var(--shadow-raised);
  transition: transform 0.2s ease;
}

.iot-toggle-switch--on .iot-toggle-switch__thumb {
  background: var(--text-on-fill);
  transform: translateX(1.25rem);
}

@media (prefers-reduced-motion: reduce) {
  .iot-toggle-switch::before,
  .iot-toggle-switch__thumb {
    transition: none;
  }
}
</style>
