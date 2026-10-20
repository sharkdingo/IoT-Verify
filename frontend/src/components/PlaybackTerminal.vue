<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, toRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import HintTooltip from '@/components/common/HintTooltip.vue'
import { useTimelineRail } from '@/composables/useTimelineRail'

const props = withDefaults(defineProps<{
  kind: 'simulation' | 'counterexample' | 'fuzzing'
  selectedStateIndex: number
  totalStates: number
  playing: boolean
  panelOpen?: boolean
  stateLabel: (index: number) => string
  violationSteps?: number[]
  violationLabel?: (index: number) => string | null
  locateLabel?: string
}>(), {
  violationSteps: () => [],
  panelOpen: true
})
const tab = defineModel<'changes' | 'details' | 'run'>('tab', { default: 'changes' })
const emit = defineEmits<{
  seek: [index: number]
  'toggle-play': []
  'locate-violation': []
  'scrub-start': []
  'hide-panel': []
}>()
const { t } = useI18n()
const prefix = computed(() => props.kind === 'simulation' ? 'simulation-timeline' : 'trace-timeline')
const panelId = computed(() => props.kind === 'simulation' ? 'simulation-playback-panel' : 'trace-playback-panel')
const title = computed(() => props.kind === 'simulation'
  ? t('app.traceVisualization.modelTracePlayback')
  : props.kind === 'fuzzing' ? t('app.fuzzFindingReplay') : t('app.traceVisualization.counterexampleTracePlayback'))
const selectedNumber = ref(String(props.selectedStateIndex + 1))
watch(() => props.selectedStateIndex, index => { selectedNumber.value = String(index + 1) })
const editSelectedNumber = (event: Event) => {
  selectedNumber.value = (event.target as HTMLInputElement).value
  emit('scrub-start')
}
const commitSelectedNumber = () => {
  const value = selectedNumber.value === '' ? NaN : Number(selectedNumber.value)
  if (!Number.isFinite(value) || props.totalStates === 0) {
    selectedNumber.value = String(props.selectedStateIndex + 1)
    return
  }
  const index = Math.min(props.totalStates - 1, Math.max(0, Math.trunc(value) - 1))
  selectedNumber.value = String(index + 1)
  if (index !== props.selectedStateIndex) emit('seek', index)
}
const railViewport = ref<HTMLDivElement | null>(null)
const railWidth = ref(640)
const visibleCapacity = computed(() => Math.max(1, Math.min(32, Math.floor((railWidth.value - 44) / 52) + 1)))
const visibleRange = computed(() => {
  const start = Math.floor(props.selectedStateIndex / visibleCapacity.value) * visibleCapacity.value
  return { start, end: Math.min(props.totalStates - 1, start + visibleCapacity.value - 1) }
})
const visibleStates = computed(() => Array.from({ length: Math.max(0, visibleRange.value.end - visibleRange.value.start + 1) },
  (_, offset) => visibleRange.value.start + offset))
const hasWindows = computed(() => props.totalStates > visibleCapacity.value)
const selectedViolationLabel = computed(() => props.violationSteps.includes(props.selectedStateIndex)
  ? props.violationLabel?.(props.selectedStateIndex) || t('app.traceViolationHere') : null)
const markerLabel = (index: number) => index === 0
  ? `${props.stateLabel(index)}, ${t('app.playbackInitialState')}` : props.stateLabel(index)
const rail = useTimelineRail({
  totalStates: toRef(props, 'totalStates'),
  selectedStateIndex: toRef(props, 'selectedStateIndex'),
  onSelectState: index => emit('seek', index),
  testIdPrefix: props.kind === 'simulation' ? 'simulation-timeline' : 'trace-timeline',
  visibleRange
})
const tabs = ['changes', 'details', 'run'] as const
const tabList = ref<HTMLDivElement | null>(null)
const revealSelectedTab = () => {
  if (!props.panelOpen) return
  void nextTick(() => tabList.value?.querySelector<HTMLButtonElement>('[aria-selected="true"]')
    ?.scrollIntoView({ behavior: 'auto', block: 'nearest', inline: 'nearest' }))
}
watch([tab, () => props.panelOpen], revealSelectedTab, { immediate: true })
let tabObserver: ResizeObserver | undefined
let railObserver: ResizeObserver | undefined
onMounted(() => {
  if (typeof ResizeObserver === 'undefined' || !tabList.value) return
  tabObserver = new ResizeObserver(revealSelectedTab)
  tabObserver.observe(tabList.value)
  if (railViewport.value) {
    railObserver = new ResizeObserver(entries => {
      if (entries[0]?.contentRect.width > 0) railWidth.value = entries[0].contentRect.width
    })
    railObserver.observe(railViewport.value)
  }
})
onBeforeUnmount(() => { tabObserver?.disconnect(); railObserver?.disconnect() })
const selectTab = (value: typeof tabs[number], focus = false) => {
  tab.value = value
  if (focus) void nextTick(() => document.getElementById(`${panelId.value}-${value}-tab`)?.focus())
}
const handleTabKey = (event: KeyboardEvent) => {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const nextIndex = (tabs.indexOf(tab.value) + (event.key === 'ArrowLeft' ? -1 : 1) + tabs.length) % tabs.length
  selectTab(event.key === 'Home' ? 'changes' : event.key === 'End' ? 'run' : tabs[nextIndex], true)
}
watch(() => props.selectedStateIndex, index => rail.revealStateButton(index, false), { immediate: true })
const scrub = (event: PointerEvent) => {
  emit('scrub-start')
  rail.scrubStateFromPointer(event)
}
</script>

<template>
  <div class="board-timeline-host"
    :class="kind === 'simulation' ? 'board-timeline-host--simulation' : 'board-timeline-host--trace'"
    :data-testid="`${prefix}-host`" role="region" :aria-label="title">
    <Teleport to="#board-playback-transport" defer>
      <div class="playback-terminal__transport" role="group" :aria-label="t('app.playbackControls')">
        <HintTooltip :content="playing ? t('app.traceVisualization.pause') : t('app.traceVisualization.play')">
          <button
            type="button"
            class="playback-terminal__icon playback-terminal__play"
            :data-testid="`${prefix}-play`"
            :disabled="totalStates <= 1"
            :aria-label="playing ? t('app.traceVisualization.pause') : t('app.traceVisualization.play')"
            :aria-pressed="playing"
            @click="emit('toggle-play')"
          >
            <span class="material-symbols-outlined" aria-hidden="true">{{ playing ? 'pause' : 'play_arrow' }}</span>
          </button>
        </HintTooltip>
        <HintTooltip :content="t('app.traceVisualization.previousState')">
          <button type="button" class="playback-terminal__icon" :disabled="selectedStateIndex <= 0" :aria-label="t('app.traceVisualization.previousState')" @click="emit('seek', selectedStateIndex - 1)">
            <span class="material-symbols-outlined" aria-hidden="true">chevron_left</span>
          </button>
        </HintTooltip>
        <label class="playback-terminal__position" data-testid="playback-position">
          <span>{{ t('app.traceVisualization.stateLabel') }}</span>
          <input :value="selectedNumber" :data-testid="`${prefix}-step-input`" type="number" :min="1" :max="Math.max(totalStates, 1)" :style="{ width: `${Math.max(2, String(totalStates).length) + 1}ch` }" :disabled="totalStates === 0" :aria-label="t('app.traceVisualization.jumpToState')"
            @input="editSelectedNumber" @change="commitSelectedNumber" @blur="commitSelectedNumber" @keydown.enter.prevent="commitSelectedNumber">
          <span>/ {{ totalStates }}</span>
        </label>
        <HintTooltip :content="t('app.traceVisualization.nextState')">
          <button type="button" class="playback-terminal__icon" :disabled="selectedStateIndex >= totalStates - 1" :aria-label="t('app.traceVisualization.nextState')" @click="emit('seek', selectedStateIndex + 1)">
            <span class="material-symbols-outlined" aria-hidden="true">chevron_right</span>
          </button>
        </HintTooltip>
      </div>
    </Teleport>
    <Teleport to="#board-playback-timeline" defer>
      <div class="playback-terminal__sequence" :class="{ 'has-windows': hasWindows }" role="group" :aria-label="t('app.playbackTimeline')">
        <div class="playback-terminal__sequence-heading">
          <HintTooltip :content="t('app.playbackSavedSceneHint')">
            <span class="playback-terminal__scene-label" :data-testid="`${prefix}-scene-source`" tabindex="0">{{ t('app.playbackSavedScene') }}</span>
          </HintTooltip>
          <span v-if="selectedStateIndex === 0 && totalStates" class="playback-terminal__initial-label">{{ t('app.playbackInitialState') }}</span>
          <span v-if="selectedViolationLabel" class="playback-terminal__violation-label" :data-testid="`${prefix}-selected-violation`">{{ selectedViolationLabel }}</span>
          <span class="playback-terminal__window-label" :data-testid="`${prefix}-window-label`">{{ t('app.playbackStateWindow', { start: totalStates ? visibleRange.start + 1 : 0, end: visibleRange.end + 1, total: totalStates }) }}</span>
        </div>
        <div class="playback-terminal__sequence-track">
          <HintTooltip v-if="hasWindows" :content="t('app.playbackPreviousWindow')">
            <button type="button" class="playback-terminal__icon" :data-testid="`${prefix}-previous-window`" :disabled="visibleRange.start === 0" :aria-label="t('app.playbackPreviousWindow')" @click="emit('seek', Math.max(0, visibleRange.start - visibleCapacity))">
              <span class="material-symbols-outlined" aria-hidden="true">keyboard_double_arrow_left</span>
            </button>
          </HintTooltip>
          <div ref="railViewport" class="iot-scroll-region-x" :data-testid="`${prefix}-scroll`">
            <div class="board-playback-rail" :class="{ 'is-single': visibleStates.length === 1 }" :data-testid="`${prefix}-track`" role="group" :aria-label="t('app.traceVisualization.jumpToState')" :style="{ width: `${Math.max(visibleStates.length * 52, 44)}px` }" @pointerdown="scrub">
              <div v-if="visibleStates.length > 1" class="board-playback-rail__line" />
              <div v-if="selectedStateIndex > visibleRange.start && visibleStates.length > 1" class="board-playback-rail__line board-playback-rail__progress" :style="{ width: `calc((100% - var(--iot-target-min)) * ${(selectedStateIndex - visibleRange.start) / (visibleRange.end - visibleRange.start)})` }" />
              <div class="board-playback-rail__steps">
                <HintTooltip v-for="index in visibleStates" :key="index" :content="markerLabel(index)">
                  <button
                    type="button"
                    class="playback-terminal__marker"
                    :tabindex="index === selectedStateIndex ? 0 : -1"
                    :aria-label="markerLabel(index)"
                    :aria-current="index === selectedStateIndex ? 'step' : undefined"
                    :data-testid="`${prefix}-state-${index}`"
                    :data-violation="violationSteps.includes(index)"
                    :data-initial="index === 0"
                    @click="emit('seek', index)"
                    @keydown="rail.handleStateKeydown($event, index)"
                  >
                    <span v-if="violationSteps.includes(index)" class="playback-terminal__marker-alert" aria-hidden="true">!</span>
                    <span v-else-if="index === selectedStateIndex" class="playback-terminal__marker-dot" aria-hidden="true" />
                    <span class="playback-terminal__marker-number" aria-hidden="true">{{ index + 1 }}</span>
                  </button>
                </HintTooltip>
              </div>
            </div>
          </div>
          <HintTooltip v-if="hasWindows" :content="t('app.playbackNextWindow')">
            <button type="button" class="playback-terminal__icon" :data-testid="`${prefix}-next-window`" :disabled="visibleRange.end >= totalStates - 1" :aria-label="t('app.playbackNextWindow')" @click="emit('seek', visibleRange.end + 1)">
              <span class="material-symbols-outlined" aria-hidden="true">keyboard_double_arrow_right</span>
            </button>
          </HintTooltip>
        </div>
      </div>
    </Teleport>
    <Teleport to="#board-playback-locate" defer>
      <HintTooltip v-if="violationSteps.length" :content="locateLabel || t('app.traceVisualization.jumpToViolation')">
        <button type="button" class="playback-terminal__locate" :data-testid="`${prefix}-jump-violation`" :aria-label="locateLabel || t('app.traceVisualization.jumpToViolation')" @click="emit('locate-violation')">
          <span class="material-symbols-outlined" aria-hidden="true">my_location</span>
          <span class="playback-terminal__locate-label">{{ locateLabel || t('app.traceVisualization.jumpToViolation') }}</span>
        </button>
      </HintTooltip>
    </Teleport>
    <div class="board-timeline" :id="panelId" :data-testid="prefix" :data-selected-state-index="selectedStateIndex">
      <h2 class="sr-only">{{ title }}</h2>
      <header class="playback-terminal__header">
        <div ref="tabList" class="playback-terminal__tabs" role="tablist" :aria-label="t('app.playbackViews')" @keydown="handleTabKey">
          <button v-for="view in tabs" :key="view" type="button" role="tab"
            :id="`${panelId}-${view}-tab`" :data-testid="`${prefix}-${view}-tab`"
            :aria-selected="tab === view" :aria-controls="`${panelId}-${view}`"
            :tabindex="tab === view ? 0 : -1" @click="selectTab(view)">
            <span class="material-symbols-outlined" aria-hidden="true">{{ view === 'changes' ? 'difference' : view === 'details' ? 'tune' : 'description' }}</span>
            {{ view === 'changes' ? t('app.stepChanges') : view === 'details' ? t('app.traceVisualization.stateDetails') : t('app.playbackRunInfo') }}
          </button>
        </div>
        <div class="playback-terminal__header-actions">
          <slot name="status" />
          <span v-if="$slots.help" class="playback-terminal__help"><slot name="help" /></span>
          <HintTooltip :content="t('app.hidePlaybackPanel')">
            <button type="button" class="playback-terminal__icon" :data-testid="`${prefix}-hide-panel`" :aria-label="t('app.hidePlaybackPanel')" @click="emit('hide-panel')">
              <span class="material-symbols-outlined" aria-hidden="true">bottom_panel_close</span>
            </button>
          </HintTooltip>
        </div>
      </header>
      <div v-if="$slots.context" class="playback-terminal__context-bar iot-scroll-region"><slot name="context" /></div>
      <section v-show="tab === 'changes'" :id="`${panelId}-changes`" role="tabpanel" :aria-labelledby="`${panelId}-changes-tab`" class="playback-terminal__body iot-scroll-region">
        <slot name="changes" />
      </section>
      <section v-show="tab === 'details'" :id="`${panelId}-details`" role="tabpanel" :aria-labelledby="`${panelId}-details-tab`" class="playback-terminal__body iot-scroll-region">
        <slot name="details" />
      </section>
      <section v-show="tab === 'run'" :id="`${panelId}-run`" role="tabpanel" :aria-labelledby="`${panelId}-run-tab`" class="playback-terminal__body iot-scroll-region">
        <slot name="run" />
      </section>
    </div>
  </div>
</template>
