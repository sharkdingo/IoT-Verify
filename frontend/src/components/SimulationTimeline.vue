<script setup lang="ts">
import PlaybackTerminal from '@/components/PlaybackTerminal.vue'
import PlaybackStateDetails from '@/components/PlaybackStateDetails.vue'
import InfoTooltip from '@/components/common/InfoTooltip.vue'
import HintTooltip from '@/components/common/HintTooltip.vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TraceState } from '../types/verify'
import type { ModelSemantics, RunBoardComparison } from '../types/modelSemantics'
import { isModelSemanticsConsistent } from '../utils/modelSemantics'
import { hasRunBoardDifference } from '../utils/modelComparison'

const props = defineProps<{
  states: TraceState[]
  selectedStateIndex: number
  playing: boolean
  panelOpen?: boolean
  actualSteps?: number
  requestedSteps?: number
  modelComplete?: boolean
  disabledRuleCount?: number
  isAttack?: boolean
  attackBudget?: number
  enablePrivacy?: boolean
  /** The validated manifest from the same simulation result as the trajectory. */
  modelSemantics: ModelSemantics
  boardComparison?: RunBoardComparison
  currentRuleIds?: string[]
  currentDeviceIds?: string[]
  formatDeviceModelToken?: (device: TraceState['devices'][number], value: unknown) => string
  formatEnvironmentModelToken?: (name: string, value: unknown) => string
}>()

const emit = defineEmits<{
  seek: [index: number]
  'toggle-play': []
  'scrub-start': []
  'hide-panel': []
}>()

const { t } = useI18n()
const terminalTab = defineModel<'changes' | 'details' | 'run'>('tab', { default: 'changes' })

const currentState = computed(() => props.states[props.selectedStateIndex])
const totalStates = computed(() => props.states.length)

const currentEnvironmentVariables = computed(() => currentState.value?.envVariables || [])
const currentCompromisedAutomationLinks = computed(() => currentState.value?.compromisedAutomationLinks || [])
const currentDevices = computed(() => currentState.value?.devices || [])
const formatDeviceModelToken = (
  device: TraceState['devices'][number],
  value: unknown
): string => props.formatDeviceModelToken
  ? props.formatDeviceModelToken(device, value)
  : String(value ?? '')

const formatEnvironmentModelToken = (name: string, value: unknown): string =>
  props.formatEnvironmentModelToken
    ? props.formatEnvironmentModelToken(name, value)
    : String(value ?? '')

const modelSemanticsConsistent = computed(() => isModelSemanticsConsistent(
  props.modelSemantics,
  { isAttack: props.isAttack, attackBudget: props.attackBudget, enablePrivacy: props.enablePrivacy }
))

// Runtime compromised-point count from NuSMV globals, not the configured attack budget.
const runtimeCompromisedPointCount = computed(() => {
  if (!currentState.value?.globalVariables) return null
  const pointCount = currentState.value.globalVariables.find(v => v.name === 'compromisedPointCount')
  if (pointCount) {
    const parsed = Number.parseInt(pointCount.value, 10)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
})

// 检查当前状态是否有被攻击的设备
const hasAttackedDevices = computed(() => {
  if (!currentState.value?.devices) return false
  return currentState.value.devices.some(device => device.compromised === true)
})
const runtimeAttackLabel = computed(() => runtimeCompromisedPointCount.value === null
  ? t('app.traceVisualization.attackedBang')
  : `${t('app.traceVisualization.runtimeCompromisedPoints')}: ${runtimeCompromisedPointCount.value}`)

const getStateAriaLabel = (index: number) => {
  return `${t('app.traceVisualization.state', { index: index + 1 })} (${index + 1}/${totalStates.value})`
}
</script>

<template>
  <PlaybackTerminal
    v-model:tab="terminalTab"
    kind="simulation"
    :selected-state-index="selectedStateIndex"
    :total-states="totalStates"
    :playing="playing"
    :panel-open="panelOpen"
    :state-label="getStateAriaLabel"
    @seek="emit('seek', $event)"
    @toggle-play="emit('toggle-play')"
    @scrub-start="emit('scrub-start')"
    @hide-panel="emit('hide-panel')"
  >
    <template #help>
      <InfoTooltip :text="t('app.playbackNavigationHint')" :label="t('app.showHelpFor', { topic: t('app.playbackControls') })" test-id="simulation-timeline-snapshot-notice" />
    </template>
    <template #status>
      <HintTooltip v-if="(runtimeCompromisedPointCount !== null && runtimeCompromisedPointCount > 0) || hasAttackedDevices" :content="runtimeAttackLabel">
        <button type="button" class="playback-terminal__icon board-text-danger" data-testid="simulation-runtime-attack" :aria-label="runtimeAttackLabel" @click="terminalTab = 'details'">
          <span class="material-symbols-outlined" aria-hidden="true">gpp_bad</span>
        </button>
      </HintTooltip>
    </template>
    <template v-if="modelComplete === false || (actualSteps !== undefined && requestedSteps !== undefined && actualSteps < requestedSteps) || !modelSemanticsConsistent || hasRunBoardDifference(boardComparison)" #context>
      <div
        v-if="modelComplete === false"
        class="mb-2 rounded-lg board-surface-warning px-3 py-2 text-[length:var(--iot-font-caption)] font-medium leading-4 board-text-warning"
        data-testid="simulation-timeline-incomplete-warning"
      >
        {{ t('app.traceVisualization.modelIncompletePlayback', { rules: disabledRuleCount || 0 }) }}
      </div>

      <div
        v-if="actualSteps !== undefined && requestedSteps !== undefined && actualSteps < requestedSteps"
        class="mb-2 rounded-lg board-surface-warning px-3 py-2 text-[length:var(--iot-font-caption)] font-medium leading-4 board-text-warning"
        data-testid="simulation-timeline-short-horizon-warning"
      >
        {{ t('app.simulationStoppedBeforeRequestedSteps', { actual: actualSteps, requested: requestedSteps }) }}
      </div>

      <div
        v-if="!modelSemanticsConsistent"
        class="mb-2 rounded-lg board-surface-warning px-3 py-2 text-[length:var(--iot-font-caption)] font-semibold leading-4 board-text-warning"
      >
        {{ t('app.modelSemanticsUnavailable') }}
      </div>

      <div
        v-if="hasRunBoardDifference(boardComparison)"
        class="mb-2 rounded-lg board-surface-warning px-3 py-2 text-[length:var(--iot-font-caption)] font-medium leading-4 board-text-warning"
        data-testid="simulation-board-drift-warning"
      >
        {{ t(boardComparison === 'SEMANTICS_CHANGED' ? 'app.runBoardSemanticsChanged' : 'app.playbackBoardInputChanged') }}
      </div>
    </template>
    <template #details>
      <PlaybackStateDetails
        :devices="currentDevices"
        :environment-variables="currentEnvironmentVariables"
        :compromised-automation-links="currentCompromisedAutomationLinks"
        :current-device-ids="currentDeviceIds || []"
        :current-rule-ids="currentRuleIds || []"
        :format-device-token="formatDeviceModelToken"
        :format-environment-token="formatEnvironmentModelToken"
      />
    </template>
    <template #changes><slot name="changes" /></template>
    <template #run><slot name="run" /></template>
  </PlaybackTerminal>
</template>
