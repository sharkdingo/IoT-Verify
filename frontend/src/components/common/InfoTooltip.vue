<script setup lang="ts">
import { ref } from 'vue'
import { ElTooltip } from 'element-plus'
import { tooltipPopperOptions } from './tooltip'
import 'element-plus/theme-chalk/el-popper.css'
import 'element-plus/theme-chalk/el-tooltip.css'

const helpOpen = ref(false)
const dismissHelp = (event: KeyboardEvent) => {
  if (!helpOpen.value) return
  event.preventDefault()
  event.stopPropagation()
  helpOpen.value = false
}

withDefaults(defineProps<{
  text: string
  label: string
  placement?: 'top' | 'top-start' | 'top-end' | 'bottom' | 'bottom-start' | 'bottom-end' | 'left' | 'right'
  /**
   * What the help concerns, not what colour to paint.
   *
   * `amber` was the odd one out — a colour name beside three meanings — and it was used for the
   * Environment Pool's help icon, where two independent reviews read the warm tint as an unresolved
   * warning on an otherwise informational panel. Help about ordinary state is `neutral`; `caution` is
   * reserved for help that genuinely warns, so the colour claim has to be earned.
   */
  tone?: 'neutral' | 'danger' | 'sensitivity' | 'caution'
  testId?: string
}>(), {
  placement: 'top',
  tone: 'neutral',
  testId: undefined
})
</script>

<template>
  <ElTooltip
    v-model:visible="helpOpen"
    :content="text"
    :placement="placement"
    :popper-options="tooltipPopperOptions"
    :trigger="['hover', 'focus', 'click']"
    :show-after="120"
    :hide-after="80"
    :enterable="true"
    :teleported="true"
    popper-class="iot-info-tooltip-popper"
  >
    <template #content><div class="iot-tooltip-content">{{ text }}</div></template>
    <button
      type="button"
      class="iot-info-tooltip-trigger"
      :class="`iot-info-tooltip-trigger--${tone}`"
      :data-testid="testId"
      :aria-label="label"
      @click.stop
      @keydown.esc="dismissHelp"
    >
      <span class="material-symbols-outlined" aria-hidden="true">info</span>
    </button>
  </ElTooltip>
</template>

<style scoped>
/* A 20px badge that occupies the 20px it paints.
 *
 * It used to be a `content-box` 1.5rem badge grown to a 44px target by `padding: 0.625rem` with
 * `margin: -0.625rem` cancelling the growth, on the theory that `background-clip: content-box` would keep the
 * *visible* badge small while the target stayed large. That does not hold, and `board.css` had already measured
 * why for the dock's collapse handle: `background-clip` clips the background but **not the border**, so a bordered
 * box paints at the full padding size. Measured here: **46×46px beside 16px text** — the oversized circle a reader
 * sees, while the layout was told 24px.
 *
 * Worse, `targetSizeFloor.spec.ts` asserted this exact technique, so the guard certified the defect it existed to
 * prevent.
 *
 * So it now paints what it occupies. 1.25rem rather than 1.5rem because a help badge annotates text rather than
 * competing with it. The 44px pointer target moves to a `::before` overlay, which enlarges the hit area without
 * entering the box model or the paint — the negative margin used to spend that difference on its neighbours. */
.iot-info-tooltip-trigger {
  position: relative;
  display: inline-flex;
  box-sizing: border-box;
  width: 1.25rem;
  height: 1.25rem;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 1px solid color-mix(in srgb, currentColor 28%, transparent);
  border-radius: var(--iot-radius-pill);
  /* Theme token, not `white`: the hardcoded blend made the dark override below the only rule that
     could set a usable background, which in turn outranked hover/focus and killed both cues. */
  background: color-mix(in srgb, currentColor 8%, var(--surface-elevated));
  color: var(--text-muted);
  transition: background-color 0.15s ease, border-color 0.15s ease, color 0.15s ease;
}

/* The pointer and touch target, outside the box model so it cannot push its neighbours around - which is what
   the old negative margin did (it overhung the dock edge by 2px and pressed on the button below). */
.iot-info-tooltip-trigger::before {
  content: '';
  position: absolute;
  top: 50%;
  left: 50%;
  width: 44px;
  height: 44px;
  transform: translate(-50%, -50%);
}

.iot-info-tooltip-trigger:hover,
.iot-info-tooltip-trigger:focus-visible {
  border-color: currentColor;
  background: color-mix(in srgb, currentColor 22%, var(--surface-elevated));
}

/* A real focus ring: the hover background alone is too subtle to serve as the keyboard cue, and
   suppressing the outline left keyboard users with nothing. */
.iot-info-tooltip-trigger:focus-visible {
  outline: 2px solid color-mix(in srgb, currentColor 75%, transparent);
  outline-offset: 2px;
}

.iot-info-tooltip-trigger .material-symbols-outlined {
  font-size: 0.8rem;
}

/* Token-driven rather than three private hex literals, which stayed at their light-theme values in
   dark and were invisible to the shared contrast work. */
.iot-info-tooltip-trigger--danger {
  color: var(--danger);
}

.iot-info-tooltip-trigger--sensitivity {
  color: var(--accent-strong);
}

.iot-info-tooltip-trigger--caution {
  color: var(--warning);
}

/* No dark-theme background override is needed any more: `--surface-elevated` already differs per
   theme. The old `[data-theme='dark']` rule was more specific than :hover/:focus-visible, so it
   silently disabled both in dark mode. */

</style>
