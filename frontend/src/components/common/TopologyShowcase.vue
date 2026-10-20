<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { getDeviceIconUrl } from '../../utils/device'
import { getNodeAccentColor, getNodeBorderColor, getNodeSurfaceColor } from '../../utils/canvas/nodePalette'

withDefaults(defineProps<{ compact?: boolean }>(), { compact: false })

const { t } = useI18n()
const reducedMotion = ref(true)
const viewport = ref<HTMLElement>()
const world = ref<HTMLElement>()
const sceneSize = ref({ width: 600, height: 360 })
const motionAllowed = computed(() => !reducedMotion.value)
const arrowId = `showcase-arrow-${useId()}`
let motionQuery: MediaQueryList | undefined
let resizeObserver: ResizeObserver | undefined
let frame: number | undefined
let previousTime: number | undefined
let pitch = 0
let yaw = 0
let targetPitch = 0
let targetYaw = 0

const nodes = [
  { id: 'source', x: 12, y: 50, type: 'Motion Detector', label: 'app.landingMotionSensor',
    states: [['Working', 'app.landingMotionIdle'], ['Working', 'app.landingMotionDetected'], ['Working', 'app.landingMotionDetected']] },
  { id: 'light', x: 64, y: 25, type: 'Light', label: 'app.landingLight',
    states: [['off', 'app.landingLightOff'], ['off', 'app.landingLightOff'], ['on', 'app.landingLightOn']] },
  { id: 'door', x: 64, y: 75, type: 'Door', label: 'app.landingDoor',
    states: [['locked', 'app.landingDoorLocked'], ['locked', 'app.landingDoorLocked'], ['unlocked', 'app.landingDoorUnlocked']] }
] as const
const nodeWidth = 28
const edges = computed(() => {
  const { width, height } = sceneSize.value
  const sourceX = (nodes[0].x + nodeWidth) / 100 * width
  const sourceY = nodes[0].y / 100 * height
  return nodes.slice(1).map((node, index) => {
    const x = node.x / 100 * width
    const y = node.y / 100 * height
    const bend = (sourceX + x) / 2
    const radius = Math.min(16, (x - sourceX) / 4, Math.abs(y - sourceY) / 2)
    const direction = Math.sign(y - sourceY)
    return {
      id: node.id, label: `R${index + 1}`,
      path: `M${sourceX} ${sourceY} H${bend - radius} Q${bend} ${sourceY} ${bend} ${sourceY + direction * radius} V${y - direction * radius} Q${bend} ${y} ${bend + radius} ${y} H${x}`,
      labelX: bend, labelY: (sourceY + y) / 2
    }
  })
})
const traceSteps = [
  { label: 'app.landingTraceInitial', description: 'app.landingTraceInitialDescription', changed: [] },
  { label: 'app.landingTraceTrigger', description: 'app.landingTraceTriggerDescription', changed: ['source'] },
  { label: 'app.landingTraceUnsafe', description: 'app.landingCounterexampleDescription', changed: ['light', 'door'] }
]
// The preset opens on its counterexample; replay restores every state before advancing.
const lastStep = traceSteps.length - 1
const currentStep = ref(lastStep)
const playing = ref(false)
const step = computed(() => traceSteps[currentStep.value]!)
const violated = computed(() => currentStep.value === lastStep)
let playbackTimer: ReturnType<typeof setTimeout> | undefined

const pausePlayback = () => {
  if (playbackTimer !== undefined) clearTimeout(playbackTimer)
  playbackTimer = undefined
  playing.value = false
}

const selectStep = (index: number) => {
  pausePlayback()
  currentStep.value = Math.max(0, Math.min(lastStep, index))
}

const scheduleStep = () => {
  playbackTimer = setTimeout(() => {
    playbackTimer = undefined
    currentStep.value += 1
    if (currentStep.value === lastStep) playing.value = false
    else scheduleStep()
  }, 1400)
}

const togglePlayback = () => {
  if (playing.value) return pausePlayback()
  if (currentStep.value === lastStep) currentStep.value = 0
  playing.value = true
  scheduleStep()
}

const paintTilt = () => {
  viewport.value?.style.setProperty('--showcase-pitch', `${pitch.toFixed(3)}deg`)
  viewport.value?.style.setProperty('--showcase-yaw', `${yaw.toFixed(3)}deg`)
}

const resetTilt = () => {
  if (frame !== undefined) cancelAnimationFrame(frame)
  frame = undefined
  previousTime = undefined
  pitch = yaw = targetPitch = targetYaw = 0
  paintTilt()
}

const advanceTilt = (time: number) => {
  frame = undefined
  // Time-based damping keeps the same response on 60Hz and high-refresh displays.
  const elapsed = previousTime === undefined ? 1000 / 60 : Math.min(time - previousTime, 64)
  previousTime = time
  const blend = 1 - Math.exp(-elapsed / 90)
  pitch += (targetPitch - pitch) * blend
  yaw += (targetYaw - yaw) * blend
  if (Math.abs(targetPitch - pitch) < 0.01 && Math.abs(targetYaw - yaw) < 0.01) {
    pitch = targetPitch
    yaw = targetYaw
    previousTime = undefined
  } else {
    frame = requestAnimationFrame(advanceTilt)
  }
  paintTilt()
}

const scheduleTilt = () => {
  if (frame === undefined) frame = requestAnimationFrame(advanceTilt)
}

const followPointer = (event: PointerEvent) => {
  if (!motionAllowed.value || event.pointerType !== 'mouse' || !viewport.value) return
  const bounds = viewport.value.getBoundingClientRect()
  if (!bounds.width || !bounds.height) return
  const x = Math.max(-1, Math.min(1, (event.clientX - bounds.left) / bounds.width * 2 - 1))
  const y = Math.max(-1, Math.min(1, (event.clientY - bounds.top) / bounds.height * 2 - 1))
  targetPitch = -y * 2
  targetYaw = x * 3
  scheduleTilt()
}

const returnToRest = () => {
  if (!motionAllowed.value) return
  targetPitch = targetYaw = 0
  scheduleTilt()
}

const syncMotion = () => {
  reducedMotion.value = motionQuery?.matches ?? true
  if (reducedMotion.value) resetTilt()
}

onMounted(() => {
  // Measure before perspective; transformed bounds would make the connections drift during tilt.
  const resizeScene = (width: number, height: number) => {
    if (width > 0 && height > 0) sceneSize.value = { width, height }
  }
  resizeScene(world.value!.clientWidth, world.value!.clientHeight)
  resizeObserver = new ResizeObserver(([entry]) => {
    if (entry) resizeScene(entry.contentRect.width, entry.contentRect.height)
  })
  resizeObserver.observe(world.value!)
  motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  syncMotion()
  motionQuery.addEventListener('change', syncMotion)
})

onUnmounted(() => {
  pausePlayback()
  resetTilt()
  resizeObserver?.disconnect()
  motionQuery?.removeEventListener('change', syncMotion)
})
</script>

<template>
  <section
    class="topology-showcase model-preview"
    :class="{ 'topology-showcase--compact': compact }"
    :aria-label="t('app.landingPreviewTitle')"
  >
    <div class="showcase-heading">
      <span class="showcase-heading__title">
        <span class="material-symbols-outlined" aria-hidden="true">hub</span>
        {{ t('app.landingPreviewTitle') }}
      </span>
      <span class="showcase-heading__example">{{ t('app.landingExample') }}</span>
    </div>
    <p class="showcase-context">
      <span class="material-symbols-outlined" aria-hidden="true">home</span>
      {{ t('app.landingExampleContext') }}
    </p>

    <div ref="viewport" class="showcase-viewport" @pointermove="followPointer" @pointerleave="returnToRest">
      <div ref="world" class="showcase-world" :class="{ 'showcase-world--animated': motionAllowed }">
        <div class="showcase-floor" aria-hidden="true"></div>
        <svg class="showcase-connections" :viewBox="`0 0 ${sceneSize.width} ${sceneSize.height}`" aria-hidden="true">
          <defs>
            <marker v-for="active in [false, true]" :key="String(active)" :id="`${arrowId}${active ? '-active' : ''}`" viewBox="0 0 10 10" refX="9" refY="5" markerUnits="userSpaceOnUse" markerWidth="8" markerHeight="8" orient="auto">
              <path d="M1 1 L9 5 L1 9 Z" class="showcase-arrow" :class="{ 'showcase-arrow--active': active }" />
            </marker>
          </defs>
          <g v-for="edge in edges" :key="edge.id">
            <path class="showcase-edge" :class="{ 'showcase-edge--active': violated }" :d="edge.path" :marker-end="`url(#${arrowId}${violated ? '-active' : ''})`" />
            <path v-if="violated" class="showcase-signal" :d="edge.path" pathLength="100" />
          </g>
        </svg>
        <span v-for="edge in edges" :key="edge.id" class="showcase-edge-label" :style="{ left: `${edge.labelX}px`, top: `${edge.labelY}px` }" aria-hidden="true">{{ edge.label }}</span>
        <div
          v-for="node in nodes"
          :key="node.id"
          class="showcase-node preview-node"
          :style="{
            left: `${node.x}%`, top: `${node.y}%`, width: `${nodeWidth}%`,
            '--node-accent-color': getNodeAccentColor(node.id),
            '--showcase-node-surface': getNodeSurfaceColor(node.id),
            '--showcase-node-border': getNodeBorderColor(node.id)
          }"
          :class="[`showcase-node--${node.id}`, {
            'showcase-node--changed': step.changed.includes(node.id),
            'showcase-node--violation': node.id === 'door' && violated
          }]"
        >
          <span class="showcase-node__artwork">
            <Transition name="trace-device-icon">
              <img :key="node.states[currentStep]![0]" class="showcase-node__image iot-device-artwork" :src="getDeviceIconUrl(node.type, node.states[currentStep]![0])" alt="" draggable="false" />
            </Transition>
          </span>
          <span class="showcase-node__label">{{ t(node.label) }}</span>
          <span class="showcase-node__state">
            <span class="showcase-node__dot" aria-hidden="true"></span>
            <span class="device-reading-slot">
              <Transition name="trace-device-state">
                <span :key="node.states[currentStep]![1]">{{ t(node.states[currentStep]![1]) }}</span>
              </Transition>
            </span>
          </span>
          <span v-if="step.changed.includes(node.id)" class="showcase-node__change material-symbols-outlined" :aria-label="t('app.traceVisualization.changed')">sync_alt</span>
          <span class="showcase-node__port" aria-hidden="true"></span>
        </div>
      </div>
    </div>

    <div v-if="!compact" class="showcase-details">
      <div>
        <span class="showcase-details__label">{{ t('app.landingRuleLabel') }}</span>
        <p><span class="showcase-rule-id">R1</span>{{ t('app.landingRuleLight') }}</p>
        <p><span class="showcase-rule-id">R2</span>{{ t('app.landingRuleDoor') }}</p>
      </div>
      <div>
        <span class="showcase-details__label">{{ t('app.landingSpecLabel') }}</span>
        <p>{{ t('app.landingSpecExample') }}</p>
      </div>
    </div>
    <ol v-if="!compact" class="showcase-trace" :aria-label="t('app.landingTraceLabel')">
      <li v-for="(traceStep, index) in traceSteps" :key="traceStep.label">
        <button type="button" :aria-current="currentStep === index ? 'step' : undefined" @click="selectStep(index)">
          <span class="showcase-trace__number" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
          <span>{{ t(traceStep.label) }}</span>
        </button>
      </li>
    </ol>
    <div class="showcase-counterexample" :class="{ 'showcase-counterexample--unsafe': violated }" role="status" aria-live="polite" aria-atomic="true">
      <span class="material-symbols-outlined" aria-hidden="true">{{ violated ? 'warning' : 'timeline' }}</span>
      <div>
        <span class="showcase-counterexample__label">{{ t(violated ? 'app.landingCounterexampleLabel' : step.label) }}</span>
        <p>{{ t(step.description) }}</p>
      </div>
    </div>
    <div class="showcase-playback" :aria-label="t('app.landingTraceLabel')">
      <span class="showcase-playback__position">{{ String(currentStep + 1).padStart(2, '0') }} / {{ String(traceSteps.length).padStart(2, '0') }}</span>
      <div class="showcase-playback__actions">
        <button type="button" class="showcase-step-control" :aria-label="t('app.traceVisualization.previousState')" :disabled="currentStep === 0" @click="selectStep(currentStep - 1)">
          <span class="material-symbols-outlined" aria-hidden="true">chevron_left</span>
        </button>
        <button type="button" class="showcase-replay" @click="togglePlayback">
          <span class="material-symbols-outlined" aria-hidden="true">{{ playing ? 'pause' : currentStep === lastStep ? 'replay' : 'play_arrow' }}</span>
          <span>{{ t(playing ? 'app.traceVisualization.pause' : currentStep === lastStep ? 'app.landingReplayExample' : 'app.traceVisualization.play') }}</span>
        </button>
        <button type="button" class="showcase-step-control" :aria-label="t('app.traceVisualization.nextState')" :disabled="currentStep === lastStep" @click="selectStep(currentStep + 1)">
          <span class="material-symbols-outlined" aria-hidden="true">chevron_right</span>
        </button>
      </div>
    </div>
    <p class="showcase-note">{{ t('app.landingPreviewNote') }}</p>
  </section>
</template>

<style scoped>
.topology-showcase {
  min-width: 0;
  color: var(--text);
}

.showcase-heading,
.showcase-heading__title,
.showcase-context {
  display: flex;
  align-items: center;
  gap: var(--iot-space-sm);
}

.showcase-heading {
  justify-content: space-between;
  font-size: var(--iot-font-label);
  font-weight: 600;
}

.showcase-heading__title .material-symbols-outlined {
  font-size: 20px;
  color: var(--accent);
}

.showcase-heading__example {
  border: 1px solid var(--border-strong);
  border-radius: var(--iot-radius-control);
  padding: var(--iot-space-xs) var(--iot-space-sm);
  color: var(--text-muted);
  font-size: var(--iot-font-caption);
  font-weight: 400;
  white-space: nowrap;
}

.showcase-context {
  gap: var(--iot-space-xs);
  margin: var(--iot-space-md) 0 0;
  color: var(--text-muted);
  font-size: var(--iot-font-caption);
}

.showcase-context .material-symbols-outlined {
  font-size: 18px;
}

.showcase-viewport {
  --showcase-pitch: 0deg;
  --showcase-yaw: 0deg;
  position: relative;
  aspect-ratio: 1.6;
  min-height: 22rem;
  margin-top: var(--iot-space-md);
  perspective: 1200px;
  isolation: isolate;
}

.showcase-world {
  position: absolute;
  inset: var(--iot-space-xl) 0;
  transform-style: preserve-3d;
  transform: rotateX(calc(8deg + var(--showcase-pitch))) rotateY(var(--showcase-yaw));
}

.showcase-floor {
  position: absolute;
  inset: 0;
  border: 1px solid var(--border-strong);
  border-radius: var(--iot-radius-card);
  background-color: var(--iot-color-bg-canvas);
  background-image:
    linear-gradient(var(--iot-color-grid) 1px, transparent 1px),
    linear-gradient(90deg, var(--iot-color-grid) 1px, transparent 1px);
  background-size: var(--iot-grid-size) var(--iot-grid-size);
  box-shadow: var(--shadow-floating);
  transform: translateZ(-1px);
  transform-style: preserve-3d;
}

.showcase-floor::after {
  content: '';
  position: absolute;
  inset: 0;
  border: 1px solid var(--border);
  border-radius: inherit;
  background: var(--surface-control);
  transform: translateZ(-5px);
}

/* Nodes and connections share one plane, so every line ends at its device port. */
.showcase-connections {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  overflow: visible;
}

.showcase-edge,
.showcase-signal {
  fill: none;
  stroke: var(--control-border);
  stroke-width: 2;
  vector-effect: non-scaling-stroke;
}

.showcase-edge--active,
.showcase-signal {
  stroke: var(--accent);
}

.showcase-arrow {
  fill: var(--control-border);
}

.showcase-arrow--active {
  fill: var(--accent-border);
}

.showcase-edge-label {
  position: absolute;
  border: 1px solid var(--border-strong);
  border-radius: var(--iot-radius-control);
  background: var(--surface-elevated);
  padding: var(--iot-space-2xs) var(--iot-space-xs);
  color: var(--text);
  font-size: var(--iot-font-min);
  font-weight: 600;
  box-shadow: var(--shadow-raised);
  line-height: 1;
  transform: translate(-50%, -50%);
}

.showcase-signal {
  stroke-width: 3;
  stroke-dasharray: 0 120;
  stroke-linecap: round;
}

.showcase-world--animated .showcase-signal {
  animation: showcase-flow 800ms ease-out both;
}

.showcase-node {
  position: absolute;
  box-sizing: border-box;
  display: grid;
  grid-template-rows: 2.25rem 2.8em 2.5rem;
  align-content: center;
  justify-items: center;
  gap: var(--iot-space-2xs);
  min-width: 0;
  border: 1px solid var(--showcase-node-border);
  border-radius: var(--iot-radius-action);
  background: var(--showcase-node-surface);
  padding: var(--iot-space-sm);
  font-size: var(--iot-font-label);
  box-shadow: var(--shadow-floating);
  transform: translateY(-50%);
  transition: border-color var(--iot-motion-fast), background-color var(--iot-motion-fast);
}

.showcase-node--changed {
  border-color: var(--accent);
  box-shadow: var(--shadow-floating), 0 0 0 2px var(--accent-surface);
}

.showcase-node--violation {
  border-color: var(--danger-border);
  background: var(--danger-surface);
}

.showcase-node__artwork {
  position: relative;
  width: 2.25rem;
  height: 2.25rem;
}

.showcase-node__image {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.showcase-node__label {
  align-self: center;
  min-width: 0;
  max-width: 100%;
  font-size: inherit;
  font-weight: 600;
  line-height: 1.4;
  text-align: center;
  overflow-wrap: anywhere;
}

.showcase-node__state {
  display: flex;
  box-sizing: border-box;
  align-items: center;
  justify-content: center;
  gap: var(--iot-space-2xs);
  width: 100%;
  min-height: 1.5rem;
  align-self: center;
  border: 1px solid var(--border);
  border-radius: var(--iot-radius-pill);
  background: var(--surface-elevated);
  padding: var(--iot-space-2xs);
  color: var(--text);
  font-size: var(--iot-font-min);
  line-height: 1.3;
  text-align: center;
}

.showcase-node__dot,
.showcase-node__port {
  width: 6px;
  height: 6px;
  flex-shrink: 0;
  border-radius: var(--iot-radius-pill);
  background: var(--node-accent-color);
}

.showcase-node--violation .showcase-node__state {
  border-color: var(--danger-border);
  color: var(--danger);
}

.showcase-node--violation .showcase-node__dot {
  background: var(--danger);
}

.showcase-node__change {
  position: absolute;
  top: var(--iot-space-xs);
  right: var(--iot-space-xs);
  color: var(--accent);
  font-size: 16px;
}

.showcase-node__port {
  position: absolute;
  left: -4px;
  top: calc(50% - 3px);
}

.showcase-node--source .showcase-node__port {
  left: auto;
  right: -4px;
}

.showcase-details {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--iot-space-xl);
  border-top: 1px solid var(--border);
  padding-top: var(--iot-space-lg);
}

.showcase-details__label {
  color: var(--text-muted);
  font-size: var(--iot-font-caption);
}

.showcase-details p {
  margin: var(--iot-space-sm) 0 0;
  font-size: var(--iot-font-label);
  line-height: 1.65;
}

.showcase-rule-id {
  margin-right: var(--iot-space-xs);
  color: var(--text-muted);
  font-size: var(--iot-font-min);
  font-variant-numeric: tabular-nums;
}

.showcase-trace {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--iot-space-xs);
  margin: var(--iot-space-lg) 0 0;
  padding: 0;
  list-style: none;
}

.showcase-trace li {
  display: flex;
}

.showcase-trace button {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--iot-space-xs);
  width: 100%;
  min-height: var(--iot-target-min);
  border: 1px solid var(--border-strong);
  border-radius: var(--iot-radius-action);
  background: var(--surface-control);
  padding: var(--iot-space-sm);
  color: var(--text);
  font-size: var(--iot-font-caption);
  line-height: 1.5;
  text-align: left;
  cursor: pointer;
}

.showcase-trace button[aria-current='step'] {
  border-color: var(--accent-border);
  background: var(--accent-surface);
  color: var(--accent-strong);
}

.showcase-trace__number {
  font-variant-numeric: tabular-nums;
}

.showcase-counterexample {
  display: flex;
  align-items: flex-start;
  gap: var(--iot-space-sm);
  min-height: 5rem;
  margin-top: var(--iot-space-md);
  border: 1px solid var(--border-strong);
  border-radius: var(--iot-radius-well);
  background: var(--surface-muted);
  padding: var(--iot-space-md);
  color: var(--text);
}

.showcase-counterexample--unsafe {
  border-color: var(--danger-border);
  background: var(--danger-surface);
  color: var(--danger);
}

.showcase-counterexample > .material-symbols-outlined {
  flex-shrink: 0;
  margin-top: 1px;
  font-size: 18px;
}

.showcase-counterexample__label {
  font-size: var(--iot-font-label);
  font-weight: 600;
}

.showcase-counterexample p {
  margin: var(--iot-space-2xs) 0 0;
  font-size: var(--iot-font-caption);
  line-height: 1.5;
}

.showcase-playback,
.showcase-playback__actions {
  display: flex;
  align-items: center;
  gap: var(--iot-space-xs);
}

.showcase-playback {
  justify-content: space-between;
  gap: var(--iot-space-sm);
  padding-top: var(--iot-space-sm);
}

.showcase-playback__position {
  color: var(--text-muted);
  font-size: var(--iot-font-caption);
  font-variant-numeric: tabular-nums;
}

.showcase-replay,
.showcase-step-control {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--iot-space-xs);
  min-height: var(--iot-target-min);
  min-width: var(--iot-target-min);
  border: 1px solid transparent;
  border-radius: var(--iot-radius-action);
  background: transparent;
  padding: var(--iot-space-xs);
  color: var(--text);
  font-size: var(--iot-font-caption);
  white-space: nowrap;
  cursor: pointer;
}

.showcase-replay {
  min-width: 7.5rem;
}

.showcase-replay .material-symbols-outlined,
.showcase-step-control .material-symbols-outlined {
  font-size: 20px;
}

.showcase-replay:hover,
.showcase-step-control:hover:not(:disabled),
.showcase-trace button:hover {
  background: var(--surface-control-hover);
  color: var(--text);
}

.showcase-trace button[aria-current='step']:hover {
  background: var(--accent-surface);
  color: var(--accent-strong);
}

.showcase-replay:focus-visible,
.showcase-step-control:focus-visible,
.showcase-trace button:focus-visible {
  outline: 2px solid var(--accent-border);
  outline-offset: 2px;
}

.showcase-step-control:disabled {
  color: var(--text-muted);
  cursor: not-allowed;
}

.showcase-note {
  margin: var(--iot-space-sm) 0 0;
  color: var(--text-muted);
  font-size: var(--iot-font-caption);
  line-height: 1.6;
}

.topology-showcase--compact .showcase-node {
  font-size: var(--iot-font-caption);
}

@keyframes showcase-flow {
  0% { stroke-dasharray: 0 120; stroke-dashoffset: 0; }
  20%, 65% { stroke-dasharray: 16 120; }
  100% { stroke-dasharray: 0 120; stroke-dashoffset: -100; }
}

@media (prefers-reduced-motion: reduce) {
  .showcase-world--animated .showcase-signal {
    animation: none;
  }
  .showcase-node {
    transition: none;
  }
  .showcase-world {
    transform: none;
  }
}

@media (max-width: 520px) {
  .showcase-node {
    font-size: var(--iot-font-caption);
  }
  .showcase-details {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--iot-space-lg);
  }
}
</style>
