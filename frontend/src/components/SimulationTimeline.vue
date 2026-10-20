<script setup lang="ts">
import PlaybackTerminal from '@/components/PlaybackTerminal.vue'
import PlaybackStateDetails from '@/components/PlaybackStateDetails.vue'
import InfoTooltip from '@/components/common/InfoTooltip.vue'
import HintTooltip from '@/components/common/HintTooltip.vue'
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TraceState } from '../types/verify'
import type { ModelRunSnapshot, ModelSemantics, RunBoardComparison } from '../types/modelSemantics'
import type { EnvironmentValueProvenance } from '../types/model'
import { isModelSemanticsConsistent } from '../utils/modelSemantics'

const props = defineProps<{
  states: TraceState[]
  visible: boolean
  panelOpen?: boolean
  actualSteps?: number
  requestedSteps?: number
  modelComplete?: boolean
  disabledRuleCount?: number
  isAttack?: boolean
  attackBudget?: number
  enablePrivacy?: boolean
  /**
   * Required, unlike the counterexample bar's equivalent.
   *
   * `SimulationResult.modelSemantics` is non-optional, `requireAttackContext` in `runResponse.ts` rejects a
   * response whose manifest does not match the run context, and the sole opener
   * (`openSimulationAnimationFromSavedStates`) refuses to show this bar without a loaded result — so an
   * absent manifest is not a state the product can reach. Declaring it optional made 17 of the 18 mounts in
   * this component's spec describe an impossible run, each rendering the "semantics unavailable" warning
   * below as if it were an ordinary simulation.
   *
   * The trace bar keeps `modelSemantics?` for a real reason: `TraceEvidence` is shared with the
   * hand-assembled fuzz trace, which carries no manifest. There is no such union here.
   *
   * Required at the type level, still read with `?.` below — the caller narrows a nullable ref through
   * `simulationPlaybackSemantics`, and that narrowing is the one thing between here and an absent manifest.
   * So the "semantics unavailable" warning stays as its failsafe rather than being deleted as unreachable:
   * degrading to it beats describing attack and privacy semantics no run declared.
   */
  modelSemantics: ModelSemantics
  modelSnapshot?: ModelRunSnapshot
  boardComparison?: RunBoardComparison
  currentRuleIds?: string[]
  currentDeviceIds?: string[]
  formatDeviceModelToken?: (device: TraceState['devices'][number], value: unknown) => string
  formatEnvironmentModelToken?: (name: string, value: unknown) => string
}>()

const emit = defineEmits<{
  'highlight-state': [data: { states: TraceState[]; selectedStateIndex: number } | null]
  'hide-panel': []
}>()

const { t } = useI18n()
const terminalTab = defineModel<'changes' | 'details' | 'run'>('tab', { default: 'changes' })

// 当前选中的状态索引
const selectedStateIndex = ref(0)

// 当前状态
const currentState = computed(() => {
  return props.states[selectedStateIndex.value] || null
})

const previousState = computed(() => {
  if (selectedStateIndex.value <= 0) return null
  return props.states[selectedStateIndex.value - 1] || null
})

// 所有状态数量
const totalStates = computed(() => {
  return props.states?.length || 0
})

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

const getPreviousEnvValue = (name: string) =>
  previousState.value?.envVariables?.find(variable => variable.name === name)?.value

const getProvenanceForVariable = (name: string) =>
  props.modelSnapshot?.environmentProvenance?.find((p: EnvironmentValueProvenance) => p.name === name) || null

const environmentVariableTitle = (name: string, value: string) => {
  const previous = getPreviousEnvValue(name)
  const displayName = formatEnvironmentModelToken(name, name)
  const displayValue = formatEnvironmentModelToken(name, value)

  let title = previous === undefined || previous === value
    ? `${displayName}: ${displayValue}`
    : `${displayName}: ${formatEnvironmentModelToken(name, previous)} -> ${displayValue}`

  // Add concise cause explanation when value changed
  if (previous !== undefined && previous !== value) {
    const provenance = getProvenanceForVariable(name)
    if (provenance) {
      if (provenance.authorship === 'EXOGENOUS') {
        // A value no submitted device writes moved on its own. Which rule permitted that depends on
        // its type: a numeric value has a declared per-step interval, so the change is exactly what
        // the user wrote; a discrete one has no such rule, so the model lets it take any declared
        // value -- an abstraction the user needs named before they act on the trace.
        title += provenance.semantics === 'ABSTRACTION'
          ? ` (${t('app.traceVisualization.provenance.externalInput')})`
          : ` (${t('app.traceVisualization.provenance.naturalEvolution', {
              rate: provenance.naturalChangeRate ?? ''
            })})`
      } else if (provenance.authorship === 'DEVICE_CONTROLLED' && provenance.writers.length > 0) {
        const writer = provenance.writers[0]
        const writerLabel = formatDeviceModelToken?.({ varName: writer.deviceVarName } as any, writer.deviceVarName) || writer.deviceVarName
        title += ` (${t('app.traceVisualization.provenance.affectedBy', { device: writerLabel })})`
      } else if (provenance.authorship === 'COMPOSED') {
        title += ` (${t('app.traceVisualization.provenance.affectedByMultiple', { count: provenance.writers.length })})`
      }
    }
  }

  return title
}

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

// 跳转到指定状态
const goToState = (index: number) => {
  const lastIndex = Math.max(totalStates.value - 1, 0)
  selectedStateIndex.value = Math.min(Math.max(index, 0), lastIndex)
}

const selectState = (index: number) => {
  stop()
  goToState(index)
  highlightState()
}

const getStateAriaLabel = (index: number) => {
  return `${t('app.traceVisualization.state', { index: index + 1 })} (${index + 1}/${totalStates.value})`
}

// 播放动画
const isPlaying = ref(false)
let playInterval: ReturnType<typeof setInterval> | null = null

const play = () => {
  if (isPlaying.value) {
    stop()
    return
  }
  if (totalStates.value <= 1) {
    return
  }

  if (selectedStateIndex.value >= totalStates.value - 1) {
    goToState(0)
    highlightState()
  }
  
  isPlaying.value = true
  playInterval = setInterval(() => {
    if (selectedStateIndex.value < totalStates.value - 1) {
      selectedStateIndex.value++
      if (selectedStateIndex.value >= totalStates.value - 1) {
        stop()
      }
    } else {
      // 到达最后一个状态，停止播放
      stop()
    }
  }, 1500)
}

const stop = () => {
  isPlaying.value = false
  if (playInterval) {
    clearInterval(playInterval)
    playInterval = null
  }
}

// 高亮显示当前状态 - 发送完整的状态信息
const highlightState = () => {
  if (props.visible && props.states.length > 0) {
    emit('highlight-state', {
      states: props.states,
      selectedStateIndex: selectedStateIndex.value
    })
  }
}

// 监听对话框关闭
watch(() => props.visible, (newVal) => {
  if (!newVal) {
    stop()
    emit('highlight-state', null)
  } else if (props.states.length > 0) {
    goToState(0)
    highlightState()
  }
})

watch(() => props.states.length, (length) => {
  if (length <= 0) {
    selectedStateIndex.value = 0
    stop()
    emit('highlight-state', null)
    return
  }
  if (selectedStateIndex.value >= length) {
    selectedStateIndex.value = length - 1
  }
  if (props.visible) {
    highlightState()
  }
})

watch(() => props.states, (states, previousStates) => {
  if (states === previousStates) return
  stop()
  selectedStateIndex.value = 0
  if (props.visible && states.length > 0) {
    highlightState()
  }
})

onBeforeUnmount(() => {
  stop()
})

// 监听状态索引变化
watch(selectedStateIndex, () => {
  if (props.visible) {
    highlightState()

  }
})
</script>

<template>
  <PlaybackTerminal
    v-if="visible"
    v-model:tab="terminalTab"
    kind="simulation"
    :selected-state-index="selectedStateIndex"
    :total-states="totalStates"
    :playing="isPlaying"
    :panel-open="panelOpen"
    :state-label="getStateAriaLabel"
    @seek="selectState"
    @toggle-play="play"
    @scrub-start="stop"
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
    <template v-if="modelComplete === false || (actualSteps !== undefined && requestedSteps !== undefined && actualSteps < requestedSteps) || !modelSemanticsConsistent || boardComparison === 'CHANGED'" #context>
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
        v-if="boardComparison === 'CHANGED'"
        class="mb-2 rounded-lg board-surface-warning px-3 py-2 text-[length:var(--iot-font-caption)] font-medium leading-4 board-text-warning"
        data-testid="simulation-board-drift-warning"
      >
        {{ t('app.playbackBoardInputChanged') }}
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
        :environment-title="environmentVariableTitle"
      />
    </template>
    <template #changes><slot name="changes" /></template>
    <template #run><slot name="run" /></template>
  </PlaybackTerminal>
</template>
