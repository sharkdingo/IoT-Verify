<script setup lang="ts">
// The wrapped control owns its accessible name; this adds a visual hint.
import { ElTooltip } from 'element-plus'
import { tooltipPopperOptions } from './tooltip'
import 'element-plus/theme-chalk/el-popper.css'
import 'element-plus/theme-chalk/el-tooltip.css'

withDefaults(defineProps<{
  /** The hint. Empty disables the tooltip, so a conditional hint needs no `v-if` at the call site. */
  content?: string | null
  placement?: 'top' | 'top-start' | 'top-end' | 'bottom' | 'bottom-start' | 'bottom-end' | 'left' | 'right'
}>(), {
  content: '',
  placement: 'top'
})
</script>

<template>
  <ElTooltip
    :content="content || ''"
    :disabled="!content"
    :placement="placement"
    :popper-options="tooltipPopperOptions"
    :trigger="['hover', 'focus']"
    :show-after="120"
    :hide-after="80"
    :enterable="false"
    :trigger-keys="[]"
    :teleported="true"
    popper-class="iot-info-tooltip-popper iot-hint-tooltip-popper"
  >
    <template #content><div class="iot-tooltip-content">{{ content }}</div></template>
    <slot />
  </ElTooltip>
</template>

<style scoped>
/* Plain hints have no interactive content; an open hint must not intercept nearby controls. */
:global(.el-popper.iot-hint-tooltip-popper) {
  pointer-events: none;
}
</style>
