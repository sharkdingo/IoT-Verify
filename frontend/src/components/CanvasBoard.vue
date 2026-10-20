<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import type { DeviceNode } from '../types/node'
import type { DeviceTemplate } from '../types/device'
import type { DeviceEdge } from '../types/edge'
import type { RuleDto, RuleForm } from '@/types/rule'
import { presentRule } from '@/utils/modelPresentation'
import type { CanvasPan } from '../types/canvas'
import type { ModelTokenSource } from '../types/modelToken'

import { updateEdgesForNode } from '../utils/canvas/geometry'
import { createGeneratedDeviceIcon, indexDeviceTemplates } from '../utils/device'

import { getLinkPoints, getSelfLoopPath } from '../utils/rule'

import {
  buildTraceDevicePlaybackIndex,
  buildTraceEdgePlaybackStates,
  normalizeTraceComparable,
  toTraceDeviceId,
  traceVariableMatchesName,
  type TraceEdgePlaybackState,
  type TraceDeviceLike,
  type TraceVariableLike
} from '../utils/traceEdgePlayback'
import {
  formatPlaybackSecurityLabel,
  collectPlaybackDeviceIds,
  normalizePlaybackDeviceId,
  playbackDeviceChanged,
  playbackDeviceSecurityFacts
} from '../utils/traceView'

const { t } = useI18n()

import {
  createNodeDragState,
  beginNodeDrag,
  cancelNodeDrag,
  updateNodeDrag,
  endNodeDrag,
  getNodeDragPosition
} from '../utils/canvas/nodeDrag'

import {
  createNodeResizeState,
  beginNodeResize,
  cancelNodeResize,
  updateNodeResize,
  endNodeResize,
  NODE_HEIGHT_RANGE,
  NODE_WIDTH_RANGE
} from '../utils/canvas/nodeResize'
import {
  NODE_ACCENT_COUNT,
  getNodeAccentColor,
  getNodeBorderColor,
  getNodeColorIndex,
  getNodeSurfaceColor
} from '../utils/canvas/nodePalette'
import HintTooltip from '@/components/common/HintTooltip.vue'

// Particle animation utilities
const getParticleOpacity = (index: number): string => {
  const opacities = ['opacity-80', 'opacity-60', 'opacity-40']
  return opacities[index % opacities.length]
}

// 判断是否为内部变量连线
const isInternalVariableEdge = (edge: DeviceEdge): boolean => {
  return edge.itemType === 'variable' && edge.relation === 'contains'
}

// Get particle color based on source device color (for edges)
const getParticleSize = (index: number): number => {
  const sizes = [3, 2, 2.5]
  return sizes[index % sizes.length]
}

const TRACE_FLOW_DURATION = '1.1s'
const NODE_DRAG_THRESHOLD_PX = 5

const prefersReducedMotion = ref(false)
let reducedMotionQuery: MediaQueryList | null = null

const syncReducedMotionPreference = () => {
  prefersReducedMotion.value = reducedMotionQuery?.matches === true
}

/**
 * Check if edge flow animation should render (memoized accessor).
 * Uses the same cache as getEdgePlaybackClass to avoid redundant calculations.
 */
const shouldRenderEdgeFlow = (edge: DeviceEdge) =>
  edgePlaybackStateCache.value.get(edge)?.shouldAnimate ?? false

const fallbackDeviceIcon = createGeneratedDeviceIcon('Device')

// Handle image loading errors by showing a stable inline SVG fallback.
const handleImageError = (event: Event) => {
  const img = event.target as HTMLImageElement
  img.onerror = null
  img.src = fallbackDeviceIcon
}

const traceDevicePlaybackIndex = computed(() => buildTraceDevicePlaybackIndex(
  props.highlightedTrace?.states, props.highlightedTrace?.selectedStateIndex
))

const isDeviceAttacked = (nodeId: string): boolean =>
  traceDevicePlaybackIndex.value.compromised.has(toTraceDeviceId(nodeId))

// 获取节点的当前状态
const getNodeState = (node: DeviceNode): string => {
  if (props.highlightedTrace && props.highlightedTrace.selectedStateIndex !== undefined) {
    const traceDevice = getLatestTraceDeviceForNode(node.id)
    if (!traceDevice) {
      return t('app.traceVisualization.notRepresentedInTrace')
    }
    return traceDevice.state?.trim() || t('app.traceVisualization.stateUnavailableInTrace')
  }
  return props.getNodeEffectiveState(node)
}

const hasDisplayStateMachine = (node: DeviceNode): boolean => {
  if (!isTraceActive.value) return props.hasNodeStateMachine(node)
  const traceDevice = getLatestTraceDeviceForNode(node.id)
  if (!traceDevice) return true
  return Boolean(traceDevice.mode?.trim() || traceDevice.state?.trim())
}

const getNodeDisplayState = (node: DeviceNode): string => {
  // A device with no modelled state machine has no state to show, so the pill says so in the shape a value takes
  // rather than in a sentence.
  //
  // It used to render "No state machine" — a three-word phrase in a pill sized for values like "Auto" or
  // "Working". Measured during playback on a 184×135 node: **42px of the 85px it needs, 51% lost**, so it read as
  // "No sta…" and two reviews took it for a broken or missing reading rather than a modelling fact. A value's
  // prefix identifies it; a phrase's does not.
  //
  // The full wording stays in `getNodeStateTitle` on the pill's own `title`, so nothing is unavailable — it is
  // only no longer attempting a sentence in 42 pixels.
  if (!hasDisplayStateMachine(node)) return t('app.noStateMachineShort')
  const state = getNodeState(node)
  if (!isTraceActive.value) return formatNodeModelToken(node, state)
  const traceDevice = getLatestTraceDeviceForNode(node.id)
  return traceDevice ? formatPlaybackModelToken(traceDevice.modelTokenSource, state) : state
}

const getStateDisplayClass = (node: DeviceNode): string =>
  hasDisplayStateMachine(node) ? 'state-defined' : 'state-stateless'

// 获取节点当前状态对应的图标
const getCurrentNodeIcon = (node: DeviceNode): string => {
  if (isTraceActive.value && !getLatestTraceDeviceForNode(node.id)) {
    return props.getNodeIcon(node) || fallbackDeviceIcon
  }
  const currentState = getNodeState(node) || 'Working'

  return props.getNodeIcon(node, currentState) || fallbackDeviceIcon
}

const getNodeVisualStateKey = (node: DeviceNode): string =>
  `${toTraceDeviceId(node.id)}:${getNodeState(node)}`

type PlaybackTraceVariable = TraceVariableLike & {
  modelTokenSource?: ModelTokenSource | null
}

type PlaybackTraceDevice = Omit<TraceDeviceLike, 'variables'> & {
  modelTokenSource?: ModelTokenSource | null
  variables?: PlaybackTraceVariable[]
}

const props = defineProps<{
  /** 所有设备节点（Board.vue 的 nodes.value） */
  nodes: DeviceNode[]
  /** 所有边（Board.vue 的 edges.value） */
  edges: DeviceEdge[]
  rules?: Array<RuleForm | RuleDto>
  getVisibleFrame?: () => { left: number; top: number; width: number; height: number } | null
  /** Templates provide inherited security-label defaults for the live board. */
  deviceTemplates?: DeviceTemplate[]
  /** 画布平移（Board.vue 的 canvasPan.value） */
  pan: CanvasPan
  /** 画布缩放（Board.vue 的 canvasZoom.value） */
  zoom: number
  /** 获取节点图标路径（Board.vue 传入 getNodeIcon） */
  getNodeIcon: (node: DeviceNode, stateOverride?: string) => string
  /** Whether this template has a modelled state machine. */
  hasNodeStateMachine: (node: DeviceNode) => boolean
  /** Resolve the current persisted state against the template's legal states. */
  getNodeEffectiveState: (node: DeviceNode) => string
  /** Localize canonical bundled-template identifiers without changing stored values. */
  formatNodeModelToken?: (node: DeviceNode, value: unknown) => string
  /** Format an immutable playback token strictly from its frozen source. */
  formatPlaybackModelToken?: (source: ModelTokenSource | null | undefined, value: unknown) => string
  /** 高亮显示的反例路径 */
  highlightedTrace?: {
    states: Array<{
      stateIndex?: number
      devices: PlaybackTraceDevice[]
      envVariables?: PlaybackTraceVariable[]
      rules?: number[]
      triggeredRules?: Array<{ ruleIndex: number; ruleId?: string | null; ruleLabel?: string | null }>
      compromisedAutomationLinks?: Array<{ ruleIndex: number; ruleId?: string | null; ruleLabel?: string | null }>
    }>
    selectedStateIndex?: number
    /**
     * The step at which the specification fails, when the run reports one.
     *
     * The rail already marks this step, but the rail is not where a viewer looks during playback — the
     * canvas is. Reaching the violating state used to change nothing here: autoplay stopped silently on
     * the last state, so the moment the run exists to demonstrate had no signal on the surface holding
     * the viewer's attention.
     */
    violationStateIndex?: number
    /**
     * Every step the violation occupies. A safety property fails at one state; a liveness property
     * (templates 2/5/6) fails over an infinite cycle, where no single state is at fault and the repetition
     * itself is the violation. Emphasising only the first would leave the rest of that cycle unmarked.
     */
    violationStateIndexes?: number[]
    /**
     * Device ids the violated specification is about, so the emphasis lands on the devices the property
     * concerns rather than on the whole canvas. A specification carries its bound devices
     * (`SpecificationDto.devices`), so this is the run's own claim, not an inference from the trace.
     */
    violationDeviceIds?: string[]
  } | null
  focusedNodeId?: string | null
  focusedRuleId?: string | null
  /** Model playback is a saved snapshot; prevent canvas mutations while it is visible. */
  interactionLocked?: boolean
}>()

function formatNodeModelToken(node: DeviceNode, value: unknown): string {
  return props.formatNodeModelToken?.(node, value) ?? String(value ?? '')
}

function formatPlaybackModelToken(
  source: ModelTokenSource | null | undefined,
  value: unknown
): string {
  return props.formatPlaybackModelToken?.(source, value) ?? String(value ?? '')
}

const GRID_SIZE_PX = 32

const canvasGridStyle = computed(() => {
  const gridSize = Math.max(8, GRID_SIZE_PX * props.zoom)
  const offsetX = ((props.pan.x % gridSize) + gridSize) % gridSize
  const offsetY = ((props.pan.y % gridSize) + gridSize) % gridSize
  return {
    backgroundSize: `${gridSize}px ${gridSize}px`,
    backgroundPosition: `${offsetX}px ${offsetY}px`
  }
})

// 缓存节点映射，避免在边渲染中重复查找
const nodeMap = computed(() => {
  const map = new Map<string, DeviceNode>()
  for (const node of props.nodes) {
    map.set(node.id, node)
  }
  return map
})

const templatesByName = computed(() => indexDeviceTemplates(props.deviceTemplates || []))

// 预计算所有边的调整后坐标，避免模板中重复计算
const edgesWithAdjustedPoints = computed(() => {
  return props.edges.map((edge, index) => {
    // During drag, use temporary position for the dragging node
    let fromNode = nodeMap.value.get(edge.from)
    let toNode = nodeMap.value.get(edge.to)

    // Create temporary node objects with drag positions if nodes are being dragged
    if (fromNode && nodeDragState.node === fromNode && nodeDragState.tempPosition) {
      fromNode = { ...fromNode, position: nodeDragState.tempPosition }
    }
    if (toNode && nodeDragState.node === toNode && nodeDragState.tempPosition) {
      toNode = { ...toNode, position: nodeDragState.tempPosition }
    }

    const adjustedPoints = getAdjustedLinkPoints(fromNode, toNode, edge)
    // Every edge layer and its label must share the node's preview geometry until pointerup commits it.
    const selfLoopPath = edge.from === edge.to && fromNode ? getSelfLoopPath(fromNode) : ''
    const labelPoint = edge.from === edge.to && fromNode
      ? { x: fromNode.position.x + fromNode.width / 2, y: fromNode.position.y - 16 }
      : {
          x: (adjustedPoints.fromPoint.x + adjustedPoints.toPoint.x) / 2,
          y: (adjustedPoints.fromPoint.y + adjustedPoints.toPoint.y) / 2 - 10
        }

    // 预计算边的样式属性，避免重复查找节点
    const sourceNode = nodeMap.value.get(edge.from)
    const colorIndex = sourceNode ? getNodeColorIndex(sourceNode.id) : 0
    const isInternal = isInternalVariableEdge(edge)
    const sourceColor = sourceNode ? getNodeAccentColor(sourceNode.id) : 'var(--iot-node-accent-0)'

    return {
      edge,
      fromNode: sourceNode,
      toNode: nodeMap.value.get(edge.to),
      adjustedPoints,
      selfLoopPath,
      labelPoint,
      index, // Pre-computed index to avoid O(E) indexOf() in template
      // 预计算样式
      baseColor: isInternal ? 'var(--text-muted)' : sourceColor,
      particleColor: isInternal ? 'var(--text-muted)' : `url(#node-gradient-${colorIndex})`,
      arrowMarker: isInternal ? '' : `url(#node-arrow-${colorIndex})`,
      particleFillColor: sourceColor
    }
  })
})

const getLatestTraceVariableValueForNode = (nodeId: string, variableName: string): string | null => {
  const variable = getLatestTraceVariableForNode(nodeId, variableName)
  return variable ? normalizeTraceComparable(variable.value) : null
}

const getLatestTraceVariableForNode = (nodeId: string, variableName: string) => {
  return traceDevicePlaybackIndex.value.getCurrentVariable(nodeId, variableName)
}

const getPreviousTraceVariableForNode = (
  nodeId: string,
  variableName: string
): PlaybackTraceVariable | null => {
  return traceDevicePlaybackIndex.value.getPreviousVariable(nodeId, variableName)
}

const getLatestTraceDeviceForNode = (nodeId: string): PlaybackTraceDevice | null =>
  traceDevicePlaybackIndex.value.current.get(toTraceDeviceId(nodeId)) || null

const getPreviousTraceDeviceForNode = (nodeId: string): PlaybackTraceDevice | null =>
  traceDevicePlaybackIndex.value.previous.get(toTraceDeviceId(nodeId)) || null

const computeEdgePlaybackState = (
  edge: DeviceEdge,
  { traceActive, linkCompromised, shouldAnimate }: TraceEdgePlaybackState
) => {
  const ruleFocused = Boolean(props.focusedRuleId && edge.ruleId === props.focusedRuleId)

  return {
    traceActive,
    linkCompromised,
    ruleFocused,
    shouldAnimate: !prefersReducedMotion.value && shouldAnimate,
    classes: {
      'edge-line--active': traceActive,
      'edge-line--compromised': linkCompromised,
      'edge-line--focused': ruleFocused,
      'edge-line--dimmed': isTraceActive.value && !traceActive && !linkCompromised && !ruleFocused
    }
  }
}

/**
 * Memoized cache of edge playback state.
 * Recalculates when edges, trace state, or trace selection changes.
 * Eliminates redundant calls: the template calls getEdgePlaybackClass() and shouldRenderEdgeFlow()
 * multiple times per edge, but this computed ensures we only calculate once per edge per render.
 */
const edgePlaybackStateCache = computed(() => {
  const cache = new Map<DeviceEdge, ReturnType<typeof computeEdgePlaybackState>>()
  for (const [edge, state] of buildTraceEdgePlaybackStates(props.edges, props.highlightedTrace)) {
    cache.set(edge, computeEdgePlaybackState(edge, state))
  }
  return cache
})

/**
 * Get edge playback CSS classes (memoized accessor).
 * Called multiple times per edge in the template but only computes once per render.
 */
const getEdgePlaybackClass = (edge: DeviceEdge) => {
  return edgePlaybackStateCache.value.get(edge)?.classes ?? {}
}

// 判断是否有反例路径动画在进行
const isTraceActive = computed(() => {
  return Boolean(
    props.highlightedTrace?.states &&
    props.highlightedTrace.selectedStateIndex !== undefined &&
    props.highlightedTrace.selectedStateIndex >= 0
  )
})

// Each selected transition gets a fresh CSS animation mount, including rapid manual jumps.
const nodeAnimationTrigger = ref<Record<string, number>>({})
let nodeAnimationResetTimer: ReturnType<typeof setTimeout> | null = null
let nodeAnimationSequence = 0

// Animate only the delta that produced the selected model state. A manual jump to
// state N still compares N with N-1, rather than with whichever state the user last viewed.
watch(() => props.highlightedTrace?.selectedStateIndex, async (newIndex, oldIndex) => {
  if (newIndex === undefined) {
    nodeAnimationSequence++
    nodeAnimationTrigger.value = {}
    if (nodeAnimationResetTimer) {
      clearTimeout(nodeAnimationResetTimer)
      nodeAnimationResetTimer = null
    }
    return
  }
  if (newIndex === oldIndex) return

  const sequence = ++nodeAnimationSequence
  nodeAnimationTrigger.value = {}
  await nextTick()
  if (sequence !== nodeAnimationSequence || props.highlightedTrace?.selectedStateIndex !== newIndex) return

  const nextTriggers: Record<string, number> = {}
  for (const node of props.nodes) {
    if (!isNodeTraceChanged(node)) continue
    const triggerKey = toTraceDeviceId(node.id)
    nextTriggers[triggerKey] = sequence
  }
  nodeAnimationTrigger.value = nextTriggers

  if (nodeAnimationResetTimer) {
    clearTimeout(nodeAnimationResetTimer)
  }
  nodeAnimationResetTimer = setTimeout(() => {
    nodeAnimationTrigger.value = {}
    nodeAnimationResetTimer = null
  }, 760)
})

const shouldAnimateTraceChange = (node: DeviceNode): boolean =>
  !!nodeAnimationTrigger.value[toTraceDeviceId(node.id)]

const representedTraceDeviceIds = computed(() => collectPlaybackDeviceIds(props.highlightedTrace?.states))
const isNodeRepresentedInTrace = (node: DeviceNode): boolean =>
  isTraceActive.value && representedTraceDeviceIds.value.has(normalizePlaybackDeviceId(node.id))

// Whether the selected state (or a prior sparse state) has authoritative data for the node.
const isNodeInTrace = (node: DeviceNode): boolean => {
  return isTraceActive.value && getLatestTraceDeviceForNode(node.id) !== null
}

const emit = defineEmits<{
  /** 背景按下，用于 Board.vue 开始画布平移 */
  (e: 'canvas-pointerdown', evt: PointerEvent): void
  /** dragover 事件，留给 Board.vue 做设备拖拽创建 */
  (e: 'canvas-dragover', evt: DragEvent): void
  /** drop 事件，留给 Board.vue 做设备拖拽创建 */
  (e: 'canvas-drop', evt: DragEvent): void
  /** mouseenter / mouseleave 用于控制 isCanvasHovered */
  (e: 'canvas-enter'): void
  (e: 'canvas-leave'): void
  /** wheel 事件仍交给 Board.vue 控制缩放 */
  (e: 'canvas-wheel', evt: WheelEvent): void
  /** 键盘或辅助技术打开节点详情 */
  (e: 'node-open', node: DeviceNode): void
  /** 鼠标、键盘或辅助技术打开节点上下文菜单 */
  (e: 'node-context', node: DeviceNode, position: { x: number; y: number }): void
  /** 键盘删除节点 */
  (e: 'node-delete', nodeId: string): void
  /** 节点拖拽或缩放结束，通知 Board.vue 持久化 nodes/edges */
  (e: 'node-moved-or-resized', nodeId: string): void
  /** Keep server snapshots from replacing a node while a pointer interaction owns it. */
  (e: 'node-layout-interaction-start', nodeId: string): void
  (e: 'node-layout-interaction-end', nodeId: string): void
}>()

/* ====== 节点拖拽状态 ====== */

const nodeDragState = reactive(createNodeDragState())
let activeDragPointerId: number | null = null
let activeDragTarget: HTMLElement | null = null
let activeDragNodeId: string | null = null
let activeDragStartPoint: { x: number; y: number } | null = null
let activeDragLastPoint: { x: number; y: number } | null = null
let activeDragMoved = false
let pendingDragSample: { event: PointerEvent; zoom: number } | null = null
let dragPreviewFrame: number | null = null

const clearDragPreview = () => {
  if (dragPreviewFrame !== null) cancelAnimationFrame(dragPreviewFrame)
  dragPreviewFrame = null
  pendingDragSample = null
}

const flushDragPreview = () => {
  const sample = pendingDragSample
  clearDragPreview()
  if (sample && sample.event.pointerId === activeDragPointerId) {
    updateNodeDrag(sample.event, nodeDragState, sample.zoom)
  }
}

const removeDragListeners = () => {
  window.removeEventListener('pointermove', onNodePointerMove)
  window.removeEventListener('pointerup', onNodePointerUp)
  window.removeEventListener('pointercancel', onNodePointerCancel)
}

const releaseDragPointer = () => {
  clearDragPreview()
  const target = activeDragTarget
  const pointerId = activeDragPointerId
  const nodeId = activeDragNodeId
  activeDragPointerId = null
  activeDragTarget = null
  activeDragNodeId = null
  activeDragStartPoint = null
  activeDragLastPoint = null
  activeDragMoved = false
  target?.removeEventListener('lostpointercapture', onNodeLostPointerCapture)
  if (target && pointerId !== null) {
    try {
      target.releasePointerCapture(pointerId)
    } catch {
      // The browser may release capture before pointerup/cancel reaches the window.
    }
  }
  removeDragListeners()
  if (nodeId) emit('node-layout-interaction-end', nodeId)
}

const onNodeLostPointerCapture = (e: PointerEvent) => {
  if (e.pointerId !== activeDragPointerId) return
  const restored = cancelNodeDrag(nodeDragState)
  if (restored) updateEdgesForNode(restored.id, props.nodes, props.edges)
  releaseDragPointer()
}

const onNodePointerDown = (e: PointerEvent, node: DeviceNode) => {
  e.preventDefault()
  // Also refuse while a resize owns a node. `isPrimary` rejects a second touch, but a pen and a
  // mouse are each primary within their own type, so the two gestures could otherwise run at once:
  // a corner resize rewrites `node.position` for tl/bl/tr while the drag snapshots its origin from
  // it, and the first pointer to lift would emit `node-layout-interaction-end`, dropping the
  // parent's "pointer owns this node" guard while the other gesture is still writing geometry.
  if (e.button !== 0 || e.isPrimary === false
      || activeDragPointerId !== null || activeResizePointerId !== null) {
    return
  }
  if (props.interactionLocked) {
    return
  }
  // 只处理节点自身拖拽，不影响画布平移（事件在模板里用了 .stop）
  beginNodeDrag(e, node, nodeDragState)
  activeDragPointerId = e.pointerId
  activeDragTarget = e.currentTarget as HTMLElement
  activeDragNodeId = node.id
  activeDragStartPoint = { x: e.clientX, y: e.clientY }
  activeDragLastPoint = { x: e.clientX, y: e.clientY }
  activeDragMoved = false
  emit('node-layout-interaction-start', node.id)
  activeDragTarget.focus({ preventScroll: true })
  try {
    activeDragTarget.setPointerCapture?.(e.pointerId)
  } catch {
    // Pointer capture is an enhancement; window listeners still complete the drag.
  }
  activeDragTarget.addEventListener('lostpointercapture', onNodeLostPointerCapture)
  window.addEventListener('pointermove', onNodePointerMove)
  window.addEventListener('pointerup', onNodePointerUp)
  window.addEventListener('pointercancel', onNodePointerCancel)
}

const onNodePointerMove = (e: PointerEvent) => {
  if (e.pointerId !== activeDragPointerId) return
  if (activeDragLastPoint) {
    activeDragLastPoint.x = e.clientX
    activeDragLastPoint.y = e.clientY
  }
  if (!activeDragMoved && activeDragStartPoint) {
    const distance = Math.hypot(
      e.clientX - activeDragStartPoint.x,
      e.clientY - activeDragStartPoint.y
    )
    if (distance < NODE_DRAG_THRESHOLD_PX) return
    activeDragMoved = true
  }
  pendingDragSample = { event: e, zoom: props.zoom }
  if (dragPreviewFrame === null) {
    dragPreviewFrame = requestAnimationFrame(() => {
      dragPreviewFrame = null
      flushDragPreview()
    })
  }
}

const onNodePointerUp = (e: PointerEvent) => {
  if (e.pointerId !== activeDragPointerId) return
  // Release can carry a newer position without a move. A stationary release must retain the
  // last sample's zoom, including a queued sample captured before the viewport changed.
  if (activeDragLastPoint
      && (e.clientX !== activeDragLastPoint.x || e.clientY !== activeDragLastPoint.y)) {
    onNodePointerMove(e)
  }
  // A release can arrive before the next paint; commit the latest pointer sample once.
  flushDragPreview()
  const movedEnough = activeDragMoved
  const moved = endNodeDrag(nodeDragState)
  if (moved) {
    // 拖拽结束立即同步更新边，确保最终位置准确
    updateEdgesForNode(moved.id, props.nodes, props.edges)
    if (movedEnough) {
      emit('node-moved-or-resized', moved.id)
    } else {
      emit('node-open', moved)
    }
  }
  releaseDragPointer()
}

const onNodePointerCancel = (e: PointerEvent) => {
  if (e.pointerId !== activeDragPointerId) return
  const restored = cancelNodeDrag(nodeDragState)
  if (restored) updateEdgesForNode(restored.id, props.nodes, props.edges)
  releaseDragPointer()
}

/* ====== 节点缩放状态 ====== */

const resizeState = createNodeResizeState()
let activeResizePointerId: number | null = null
let activeResizeTarget: HTMLElement | null = null
let activeResizeNodeId: string | null = null
let activeResizeLastPoint: { x: number; y: number } | null = null

const removeResizeListeners = () => {
  window.removeEventListener('pointermove', onPointerMoveResize)
  window.removeEventListener('pointerup', onPointerUpResize)
  window.removeEventListener('pointercancel', onPointerCancelResize)
}

const releaseResizePointer = () => {
  const target = activeResizeTarget
  const pointerId = activeResizePointerId
  const nodeId = activeResizeNodeId
  activeResizePointerId = null
  activeResizeTarget = null
  activeResizeNodeId = null
  activeResizeLastPoint = null
  target?.removeEventListener('lostpointercapture', onResizeLostPointerCapture)
  if (target && pointerId !== null) {
    try {
      target.releasePointerCapture(pointerId)
    } catch {
      // Capture may already be released by the browser after pointerup/cancel.
    }
  }
  removeResizeListeners()
  if (nodeId) emit('node-layout-interaction-end', nodeId)
}

const onResizeLostPointerCapture = (e: PointerEvent) => {
  if (e.pointerId !== activeResizePointerId) return
  const restored = cancelNodeResize(resizeState)
  if (restored) updateEdgesForNode(restored.id, props.nodes, props.edges)
  releaseResizePointer()
}

const onPointerDownResize = (
    e: PointerEvent,
    node: DeviceNode,
    dir: 'tl' | 'tr' | 'bl' | 'br'
) => {
  e.stopPropagation()
  e.preventDefault()
  if (props.interactionLocked || e.button !== 0 || e.isPrimary === false
      || activeResizePointerId !== null || activeDragPointerId !== null) return
  beginNodeResize(e, node, dir, resizeState)
  activeResizePointerId = e.pointerId
  activeResizeTarget = e.currentTarget as HTMLElement
  activeResizeNodeId = node.id
  activeResizeLastPoint = { x: e.clientX, y: e.clientY }
  emit('node-layout-interaction-start', node.id)
  try {
    activeResizeTarget.setPointerCapture?.(e.pointerId)
  } catch {
    // Pointer capture is optional; window listeners still complete the resize.
  }
  activeResizeTarget.addEventListener('lostpointercapture', onResizeLostPointerCapture)
  window.addEventListener('pointermove', onPointerMoveResize)
  window.addEventListener('pointerup', onPointerUpResize)
  window.addEventListener('pointercancel', onPointerCancelResize)
}

const onPointerMoveResize = (e: PointerEvent) => {
  if (e.pointerId !== activeResizePointerId) return
  if (activeResizeLastPoint) {
    activeResizeLastPoint.x = e.clientX
    activeResizeLastPoint.y = e.clientY
  }
  const changed = updateNodeResize(e, resizeState, props.zoom)
  if (!changed || !resizeState.node) return

  updateEdgesForNode(resizeState.node.id, props.nodes, props.edges)
}

const onPointerUpResize = (e: PointerEvent) => {
  if (e.pointerId !== activeResizePointerId) return
  if (activeResizeLastPoint
      && (e.clientX !== activeResizeLastPoint.x || e.clientY !== activeResizeLastPoint.y)) {
    onPointerMoveResize(e)
  }
  const resized = endNodeResize(resizeState)
  if (resized) {
    emit('node-moved-or-resized', resized.id)
  }
  releaseResizePointer()
}

const onPointerCancelResize = (e: PointerEvent) => {
  if (e.pointerId !== activeResizePointerId) return
  const restored = cancelNodeResize(resizeState)
  if (restored) updateEdgesForNode(restored.id, props.nodes, props.edges)
  releaseResizePointer()
}

watch(
  () => props.interactionLocked,
  locked => {
    if (!locked) return
    const restoredDrag = cancelNodeDrag(nodeDragState)
    if (restoredDrag) updateEdgesForNode(restoredDrag.id, props.nodes, props.edges)
    releaseDragPointer()
    const restoredResize = cancelNodeResize(resizeState)
    if (restoredResize) updateEdgesForNode(restoredResize.id, props.nodes, props.edges)
    releaseResizePointer()
  }
)

/**
 * Pre-computed set of bidirectional edge pairs.
 * Avoids O(E) search per edge, reducing edgesWithAdjustedPoints from O(E²) to O(E).
 */
const bidirectionalEdgePairs = computed(() => {
  const pairs = new Set<string>()
  const edgeMap = new Map<string, Set<string>>()

  // Build adjacency map: from → Set(to)
  for (const edge of props.edges) {
    if (!edgeMap.has(edge.from)) {
      edgeMap.set(edge.from, new Set())
    }
    edgeMap.get(edge.from)!.add(edge.to)
  }

  // Find bidirectional pairs
  for (const edge of props.edges) {
    const hasReverse = edgeMap.get(edge.to)?.has(edge.from)
    if (hasReverse) {
      // Use canonical key (lexicographically sorted) to represent the pair
      const key = edge.from < edge.to
        ? `${edge.from}→${edge.to}`
        : `${edge.to}→${edge.from}`
      pairs.add(key)
    }
  }

  return pairs
})

// Check if there are bidirectional edges between two nodes (now O(1) lookup)
const hasBidirectionalEdges = (fromId: string, toId: string): boolean => {
  const key = fromId < toId
    ? `${fromId}→${toId}`
    : `${toId}→${fromId}`
  return bidirectionalEdgePairs.value.has(key)
}

// Get adjusted link points for bidirectional edges
const getAdjustedLinkPoints = (fromNode: DeviceNode | undefined, toNode: DeviceNode | undefined, edge: DeviceEdge) => {
  if (!fromNode || !toNode) {
    // Fallback to original positions if nodes not found
    return {
      fromPoint: edge.fromPos || { x: 0, y: 0 },
      toPoint: edge.toPos || { x: 0, y: 0 }
    }
  }

  return getLinkPoints(fromNode, toNode, hasBidirectionalEdges(edge.from, edge.to) ? 25 : 0)
}

type NodeVisualTier = 'compact' | 'condensed' | 'expanded'

const hasValue = (value: unknown) =>
  value !== null && value !== undefined && String(value).trim() !== ''

const getNodeVisualTier = (node: DeviceNode): NodeVisualTier => {
  const screenWidth = node.width * props.zoom
  const screenHeight = node.height * props.zoom
  if (screenWidth < 100 || screenHeight < 72) return 'compact'
  if (screenWidth < 168 || screenHeight < 118) return 'condensed'
  return 'expanded'
}

const getNodeVisualTierClass = (node: DeviceNode) => {
  const screenWidth = node.width * props.zoom
  const screenHeight = node.height * props.zoom
  return [
    `device-node--${getNodeVisualTier(node)}`,
    {
      'device-node--horizontal': screenWidth >= 112 && screenWidth >= screenHeight * 1.5,
      'device-node--short': screenHeight < 48,
      'device-node--minimal': screenHeight < 32,
      'device-node--micro': screenWidth < 28 || screenHeight < 18
    }
  ]
}

const getCompactNodeLabel = (node: DeviceNode) => {
  const label = String(node.label || '').trim().replace(/\s+/g, ' ')
  // Keep the beginning of the label. CSS ellipsis then preserves the
  // differentiating prefix on touch screens instead of making similarly named
  // devices all render the same trailing words.
  return label || t('app.device')
}

const POINTER_RESIZE_TARGET_SIZE_PX = 44
// Keep a small pointer-free center around compact nodes. A full 44px corner hit
// target is useful only when each screen dimension leaves a safe gap around it.
const POINTER_RESIZE_ALL_HANDLES_SIZE_PX = POINTER_RESIZE_TARGET_SIZE_PX * 2
/**
 * How many variable badges a node prints.
 *
 * Three, because the strip lives inside a 187px node. The number is named so the overflow count below and the
 * slice cannot drift apart — they were the same literal in two places, which is how the cap stayed silent.
 */
const VISIBLE_NODE_VARIABLES = 3

/**
 * How far a handle may reach *into* the node, as a fraction of the node's smaller screen dimension.
 *
 * A 44px handle centred on a corner puts 22px inside the node. On a node that is only 24px tall on screen,
 * those 22px cover the whole thing: every pointer-down lands on the handle, so the node can be resized but no
 * longer dragged, and the handle's own reveal-on-hover makes that state arrive exactly when the user reaches
 * for it. Capping the inward reach keeps a majority of the node body free for dragging at any zoom.
 */
const POINTER_RESIZE_MAX_INWARD_FRACTION = 0.35

/**
 * Per-node handle geometry, in model units (the canvas transform scales them to screen).
 *
 * The pointer target stays 44 screen pixels at every zoom. What adapts
 * is *where* those 44px sit relative to the corner: normally half in / half out, but on a node too small to
 * spare 22px the handle slides outward so it keeps its size without smothering the node. At zoom 1 on an 80×60
 * node the cap shifts it outward by 1px (35% of 60 = 21px vs a 22px natural reach); at zoom 0.3 the
 * same node is 24×18 on screen and the handle sits 6.3px in / 37.7px out.
 *
 * Computed per node because the cap depends on its dimensions. Direct dimensions and negative
 * margins keep the same corner anchors without propagating geometry variables into pseudo-elements.
 */
const getNodeResizeHandleGeometry = (node: DeviceNode) => {
  const zoom = Math.max(props.zoom, 0.01)
  const nodeScreenMin = Math.min(node.width, node.height) * zoom
  const inwardPx = Math.min(
    POINTER_RESIZE_TARGET_SIZE_PX / 2,
    nodeScreenMin * POINTER_RESIZE_MAX_INWARD_FRACTION
  )
  const size = `${POINTER_RESIZE_TARGET_SIZE_PX / zoom}px`
  return `width:${size};height:${size};margin:${-(POINTER_RESIZE_TARGET_SIZE_PX - inwardPx) / zoom}px`
}

/**
 * Get the visual rendering position of a node.
 * During drag, returns the temporary position; otherwise returns the committed position.
 * The preview leaves the parent's committed scene unchanged until release.
 */
const getNodeRenderPosition = (node: DeviceNode) => {
  return getNodeDragPosition(node, nodeDragState)
}

/**
 * Whether all four corners get a handle.
 *
 * Four handles need room not to crowd each other — 88 screen pixels, two touch targets, in both axes. Below
 * that the node keeps the single bottom-right grip, which is enough to resize and leaves the body clickable.
 */
const canShowAllPointerResizeHandles = (node: DeviceNode) =>
  node.width * props.zoom >= POINTER_RESIZE_ALL_HANDLES_SIZE_PX
  && node.height * props.zoom >= POINTER_RESIZE_ALL_HANDLES_SIZE_PX

/**
 * Compute runtime badges for a single node.
 * This is the expensive operation that walks trace states and builds badge data.
 * Use via `getNodeRuntimeBadges()` which provides memoization.
 */
const computeNodeRuntimeBadges = (node: DeviceNode) => {
  const traceDevice = isTraceActive.value ? getLatestTraceDeviceForNode(node.id) : null
  const configuredVariables = node.variables || []
  const traceOnlyVariables = (traceDevice?.variables || [])
    // An unobserved row carries no reading, so there is no badge to draw. Dropped here rather than in
    // the `shown` filter below, because that filter's second disjunct resurrects any row that merely
    // has an empty value — which would print `illuminance` with a blank number instead of hiding it.
    // The shared value the user wants is on the environment strip, published once and correctly.
    .filter(variable => variable.observed !== false)
    .map(variable => ({
      name: variable.name,
      value: normalizeTraceComparable(variable.value),
      trust: variable.trust,
      modelTokenSource: variable.modelTokenSource
    }))
  const candidates = isTraceActive.value ? traceOnlyVariables : configuredVariables

  const shown = candidates
    .filter(variable =>
      hasValue(variable.value) ||
      (isTraceActive.value && getLatestTraceVariableValueForNode(node.id, variable.name) !== null)
    )

  /*
   * How many variables the node cannot show.
   *
   * The strip is capped at three because a node is 187px wide, but the cap was silent: a device with a fourth
   * variable simply lost it, with nothing on screen saying so. That mattered once the trace timeline stopped
   * repeating this data — the timeline's chip carried a full `traceDeviceSummary`, so it was the fallback that
   * made the truncation survivable. Removing the duplicate without surfacing the remainder would have turned a
   * redundancy into a hole.
   *
   * None of the 45 bundled templates declares more than three local variables, so this is normally 0; a custom
   * template may declare up to `MAX_TEMPLATE_INTERNAL_VARIABLES`.
   */
  const hiddenVariableCount = Math.max(0, shown.length - VISIBLE_NODE_VARIABLES)

  const badges = shown
    .slice(0, VISIBLE_NODE_VARIABLES)
    .map(variable => {
      const traceVariable = isTraceActive.value
        ? getLatestTraceVariableForNode(node.id, variable.name)
        : null
      const traceValue = isTraceActive.value
        ? (traceVariable ? normalizeTraceComparable(traceVariable.value) : null)
        : null
      const previousTraceVariable = isTraceActive.value
        ? getPreviousTraceVariableForNode(node.id, variable.name)
        : null
      const previousTraceValue = previousTraceVariable
        ? normalizeTraceComparable(previousTraceVariable.value)
        : null
      const value = traceValue ?? String(variable.value)
      const trust = traceVariable?.trust || variable.trust
      const trustLabel = trust === 'trusted' || trust === 'untrusted'
        ? t(`app.${trust}`)
        : ''
      const changed = traceValue !== null &&
        previousTraceValue !== null &&
        previousTraceValue !== traceValue
      const displayLabel = isTraceActive.value
        ? formatPlaybackModelToken(traceVariable?.modelTokenSource, traceVariable?.name ?? variable.name)
        : formatNodeModelToken(node, variable.name)
      const displayValue = isTraceActive.value
        ? formatPlaybackModelToken(traceVariable?.modelTokenSource, value)
        : formatNodeModelToken(node, value)
      const displayPreviousValue = changed && previousTraceValue !== null
        ? formatPlaybackModelToken(previousTraceVariable?.modelTokenSource, previousTraceValue)
        : null
      return {
        label: displayLabel,
        value: displayValue,
        previousValue: displayPreviousValue,
        trust,
        changed,
        // The title carries the transition too. The visual pair is `aria-hidden` — an arrow glyph read
        // aloud between two numbers is noise — so this string is the only place a screen reader learns
        // that the value moved, and from what.
        title: displayPreviousValue
          ? `${displayLabel}: ${displayPreviousValue} → ${displayValue}${trustLabel ? ` (${trustLabel})` : ''}`
          : `${displayLabel}: ${displayValue}${trustLabel ? ` (${trustLabel})` : ''}`
      }
    })

  return { badges, hiddenVariableCount }
}

/**
 * Memoized cache of runtime badges per node.
 * Recalculates when nodes, trace state, or trace selection changes.
 * Eliminates redundant calls: the template calls getNodeRuntimeBadges() 6 times per node,
 * but this computed ensures we only calculate once per node per render.
 */
const nodeRuntimeBadgesCache = computed(() => {
  const cache = new Map<string, ReturnType<typeof computeNodeRuntimeBadges>>()
  for (const node of props.nodes) {
    cache.set(node.id, computeNodeRuntimeBadges(node))
  }
  return cache
})

/**
 * Get runtime badges for a node (memoized accessor).
 * Called 6 times per node in the template but only computes once per render.
 */
const getNodeRuntimeBadges = (node: DeviceNode) => {
  return nodeRuntimeBadgesCache.value.get(node.id) ?? { badges: [], hiddenVariableCount: 0 }
}

/**
 * The variables the node is holding back, by name.
 *
 * The `+N` chip states the count; this states which, so the fact is never unavailable — only unprinted at 64px.
 * Same division as `badge.title`, which carries the full `previous → current` the chip cannot show.
 */
const getHiddenVariableNames = (node: DeviceNode): string => {
  const { hiddenVariableCount } = getNodeRuntimeBadges(node)
  if (hiddenVariableCount <= 0) return ''
  const traceDevice = isTraceActive.value ? getLatestTraceDeviceForNode(node.id) : null
  const source = isTraceActive.value ? (traceDevice?.variables || []) : (node.variables || [])
  const names = source
    .slice(VISIBLE_NODE_VARIABLES)
    .map(variable => isTraceActive.value
      ? formatPlaybackModelToken(undefined, variable.name)
      : formatNodeModelToken(node, variable.name))
  return names.join(' · ')
}

/**
 * A node provenance pill, in the two lengths its two surfaces can actually hold.
 *
 * `label` is the full statement and belongs anywhere with room — the node's own `title`, and the
 * hover text. `shortLabel` is what the pill prints: it is 54px wide inside a 187px node, so the
 * sentence was ellipsized to a fragment there no matter what the font size was.
 */
type SecurityBadge = { kind: 'trust' | 'privacy'; label: string; shortLabel: string; title: string }

/**
 * Compute security badges for a single node.
 * This is the expensive operation that walks template manifests and builds badge data.
 * Use via `getNodeSecurityBadges()` which provides memoization.
 */
const computeNodeSecurityBadges = (node: DeviceNode): SecurityBadge[] => {
  if (isTraceActive.value) {
    const traceDevice = getLatestTraceDeviceForNode(node.id)
    if (!traceDevice) return []
    const facts = playbackDeviceSecurityFacts(traceDevice as Parameters<typeof playbackDeviceSecurityFacts>[0])
    const formatSecurityLabel = (label: string) => {
      if (/^([^:]+):\s*(.+)$/.test(label)) {
        return formatPlaybackSecurityLabel(
          label,
          value => formatPlaybackModelToken(traceDevice.modelTokenSource, value)
        )
      }
      const variable = traceDevice.variables?.find(candidate => traceVariableMatchesName(candidate, label))
      return formatPlaybackModelToken(variable?.modelTokenSource ?? traceDevice.modelTokenSource, label)
    }
    const formatSecurityLabels = (labels: string[]) => labels.map(formatSecurityLabel).join(', ')
    const badges: SecurityBadge[] = []
    if (facts.untrustedLabels.length > 0) {
      badges.push({
        kind: 'trust',
        label: t('app.traceVisualization.includesUntrustedSource'),
        shortLabel: t('app.traceVisualization.includesUntrustedSourceShort'),
        title: t('app.traceVisualization.untrustedLabelDetails', { labels: formatSecurityLabels(facts.untrustedLabels) })
      })
    } else if (facts.hasTrustLabels) {
      badges.push({
        kind: 'trust',
        label: t('app.traceVisualization.shownSourcesTrusted'),
        shortLabel: t('app.traceVisualization.shownSourcesTrustedShort'),
        title: t('app.traceVisualization.shownSourcesTrustedDetails')
      })
    }
    if (facts.privateLabels.length > 0) {
      badges.push({
        kind: 'privacy',
        label: t('app.traceVisualization.includesPrivateData'),
        shortLabel: t('app.traceVisualization.includesPrivateDataShort'),
        title: t('app.traceVisualization.privateLabelDetails', { labels: formatSecurityLabels(facts.privateLabels) })
      })
    }
    return badges
  }

  const templateName = String(node.templateName || '').trim().toLowerCase()
  const template = templatesByName.value.get(templateName)
  const effectiveState = props.getNodeEffectiveState(node)
  const stateDefinition = template?.manifest?.WorkingStates?.find(state => state.Name === effectiveState)
  const trustOverrides = new Map(
    (node.variables || []).map(variable => [variable.name.toLowerCase(), variable.trust])
  )
  const privacyOverrides = new Map(
    (node.privacies || []).map(entry => [entry.name.toLowerCase(), entry.privacy])
  )
  type LabelSource = 'template' | 'override'
  const withSource = (label: string, source: LabelSource) => t(
    source === 'override'
      ? 'app.traceVisualization.configuredLabelInstanceOverride'
      : 'app.traceVisualization.configuredLabelTemplateDefault',
    { label }
  )

  const trustLabels: Array<{ label: string; trust: string; source: LabelSource }> = []
  const stateTrust = node.currentStateTrust || stateDefinition?.Trust
  if (stateTrust === 'trusted' || stateTrust === 'untrusted') {
    trustLabels.push({
      label: t('app.currentStateProperty'),
      trust: stateTrust,
      source: node.currentStateTrust ? 'override' : 'template'
    })
  }
  for (const variable of template?.manifest?.InternalVariables || []) {
    if (variable.IsInside !== true) continue
    const override = trustOverrides.get(variable.Name.toLowerCase())
    const trust = override || variable.Trust
    if (trust === 'trusted' || trust === 'untrusted') {
      trustLabels.push({
        label: formatNodeModelToken(node, variable.Name),
        trust,
        source: override ? 'override' : 'template'
      })
    }
  }

  const badges: SecurityBadge[] = []
  const untrustedLabels = trustLabels
    .filter(entry => entry.trust === 'untrusted')
    .map(entry => withSource(entry.label, entry.source))
  if (untrustedLabels.length > 0) {
    badges.push({
      kind: 'trust',
      label: t('app.traceVisualization.includesUntrustedSource'),
      shortLabel: t('app.traceVisualization.includesUntrustedSourceShort'),
      title: t('app.traceVisualization.untrustedLabelDetails', { labels: untrustedLabels.join(', ') })
    })
  } else if (trustLabels.length > 0) {
    badges.push({
      kind: 'trust',
      label: t('app.traceVisualization.shownSourcesTrusted'),
      shortLabel: t('app.traceVisualization.shownSourcesTrustedShort'),
      title: t('app.traceVisualization.configuredTrustedLabelDetails', {
        labels: trustLabels.map(entry => withSource(entry.label, entry.source)).join(', ')
      })
    })
  }

  const privacyLabels = new Map<string, { label: string; privacy: string; source: LabelSource }>()
  const registerTemplatePrivacy = (name: string, privacy: string) => {
    const key = name.toLowerCase()
    const override = privacyOverrides.get(key)
    privacyLabels.set(key, {
      label: formatNodeModelToken(node, name),
      privacy: override || privacy,
      source: override ? 'override' : 'template'
    })
  }
  for (const variable of template?.manifest?.InternalVariables || []) {
    if (variable.IsInside === true) registerTemplatePrivacy(variable.Name, variable.Privacy)
  }
  for (const content of template?.manifest?.Contents || []) {
    registerTemplatePrivacy(content.Name, content.Privacy)
  }
  for (const entry of node.privacies || []) {
    const key = entry.name.toLowerCase()
    if (!privacyLabels.has(key)) {
      privacyLabels.set(key, {
        label: formatNodeModelToken(node, entry.name),
        privacy: entry.privacy,
        source: 'override'
      })
    }
  }
  const privateLabels = [...privacyLabels.values()]
    .filter(entry => entry.privacy === 'private')
    .map(entry => withSource(entry.label, entry.source))
  const statePrivacy = node.currentStatePrivacy || stateDefinition?.Privacy
  if (statePrivacy === 'private') {
    privateLabels.unshift(withSource(
      t('app.currentStateProperty'),
      node.currentStatePrivacy ? 'override' : 'template'
    ))
  }
  if (privateLabels.length > 0) {
    badges.push({
      kind: 'privacy',
      label: t('app.traceVisualization.includesPrivateData'),
      shortLabel: t('app.traceVisualization.includesPrivateDataShort'),
      title: t('app.traceVisualization.configuredPrivateLabelDetails', { labels: privateLabels.join(', ') })
    })
  }
  return badges
}

/**
 * Memoized cache of security badges per node.
 * Recalculates when nodes, trace state, or node configuration changes.
 * Eliminates redundant calls: the template calls getNodeSecurityBadges() 3 times per node,
 * but this computed ensures we only calculate once per node per render.
 */
const nodeSecurityBadgesCache = computed(() => {
  const cache = new Map<string, SecurityBadge[]>()
  for (const node of props.nodes) {
    cache.set(node.id, computeNodeSecurityBadges(node))
  }
  return cache
})

/**
 * Get security badges for a node (memoized accessor).
 * Called 3 times per node in the template but only computes once per render.
 */
const getNodeSecurityBadges = (node: DeviceNode): SecurityBadge[] => {
  return nodeSecurityBadgesCache.value.get(node.id) ?? []
}

/**
 * Memoized cache of trace device lookups.
 * Pre-computes current and previous trace devices for all nodes.
 * Eliminates redundant O(S) backward scans in watch callbacks and rendering.
 */
const traceDeviceCache = computed(() => {
  const cache = new Map<string, {
    current: ReturnType<typeof getLatestTraceDeviceForNode>
    previous: ReturnType<typeof getPreviousTraceDeviceForNode>
    changed: boolean
  }>()

  for (const node of props.nodes) {
    const current = getLatestTraceDeviceForNode(node.id)
    const previous = getPreviousTraceDeviceForNode(node.id)
    const changed = Boolean(current && playbackDeviceChanged(current, previous))

    cache.set(node.id, { current, previous, changed })
  }

  return cache
})

const isNodeTraceChanged = (node: DeviceNode) => {
  if (!isTraceActive.value) return false
  return traceDeviceCache.value.get(node.id)?.changed ?? false
}

/**
 * True while the viewer is standing on the step where the specification fails.
 *
 * Scoped to the step, not sticky: the emphasis is the answer to "what am I looking at right now", so it
 * appears on arrival and clears on leaving. Scoped to the specification's own bound devices when it
 * names any, because "the property is violated" is a claim about those devices — lighting every node
 * would say only "something is wrong here", which the viewer already knows from the rail.
 *
 * Falls back to every device present in the state when the specification binds none, so a violation is
 * never silent on this surface.
 */
const violationDeviceIdSet = computed(() => {
  const ids = props.highlightedTrace?.violationDeviceIds
  return ids && ids.length > 0 ? new Set(ids) : null
})

const isAtViolationStep = computed(() => {
  const trace = props.highlightedTrace
  if (!trace || trace.selectedStateIndex === undefined) return false
  // The set is authoritative when present: a liveness violation is a whole cycle, so equality against its
  // first step alone would clear the emphasis while the viewer is still inside the failing loop.
  const steps = trace.violationStateIndexes
  if (steps && steps.length > 0) return steps.includes(trace.selectedStateIndex)
  if (trace.violationStateIndex === undefined) return false
  return trace.violationStateIndex === trace.selectedStateIndex
})

const isNodeAtViolation = (node: DeviceNode) => {
  if (!isAtViolationStep.value) return false
  const scoped = violationDeviceIdSet.value
  return scoped ? scoped.has(node.id) : isNodeInTrace(node)
}

const getNodeStateTitle = (node: DeviceNode) => {
  const current = getNodeDisplayState(node)
  // The pill's short label is deliberately terse, so the hover carries the full sentence and says why the device
  // has no state rather than repeating the abbreviation.
  if (!hasDisplayStateMachine(node)) return t('app.noStateMachineDetail')
  const previousDevice = isTraceActive.value ? getPreviousTraceDeviceForNode(node.id) : null
  const previous = previousDevice?.state?.trim() || null
  if (previous && previous !== getNodeState(node)) {
    return `${formatPlaybackModelToken(previousDevice?.modelTokenSource, previous)} -> ${current}`
  }
  return current
}

const canvasElement = ref<HTMLElement | null>(null)
const edgePopover = ref<HTMLElement | null>(null)
const hoveredEdgeId = ref<string | null>(null)
const focusedEdgeId = ref<string | null>(null)
const dismissedEdgeId = ref<string | null>(null)
const edgePointer = ref<{ x: number; y: number } | null>(null)
const EDGE_HOVER_DELAY_MS = 400
let pendingEdgeId: string | null = null
let edgeEnterTimer: ReturnType<typeof setTimeout> | undefined
let edgeLeaveTimer: ReturnType<typeof setTimeout> | undefined
let edgeSizeObserver: ResizeObserver | undefined
const edgeViewport = reactive({ width: 0, height: 0, topInset: 0, popoverHeight: 0 })

const edgePresentations = computed(() => {
  const context = {
    t,
    resolveDevice: (id: string) => nodeMap.value.get(id),
    formatToken: (id: string, value: unknown) => {
      if (isTraceActive.value) {
        return formatPlaybackModelToken(getLatestTraceDeviceForNode(id)?.modelTokenSource, value)
      }
      const node = nodeMap.value.get(id)
      return node ? formatNodeModelToken(node, value) : String(value ?? '')
    }
  }
  const rules = props.rules || []
  const byId = new Map(rules.map((rule, index) => [String(rule.id ?? ''), index]))
  const presentations = rules.map(rule => presentRule(rule, context))
  return new Map(props.edges.map(edge => {
    const index = edge.ruleIndex ?? (edge.ruleId ? byId.get(edge.ruleId) : undefined)
    const presentation = index !== undefined && presentations[index]
      ? presentations[index]
      : presentRule({ sources: [{ fromId: edge.from, fromApi: edge.fromApi || '',
        itemType: edge.itemType || 'api', relation: edge.relation, value: edge.value }],
        toId: edge.to, toApi: edge.toApi || '' }, context)
    return [edge.id, presentation]
  }))
})

const getFullEdgeLabel = (edge: DeviceEdge) => {
  const presentation = edgePresentations.value.get(edge.id)!
  return presentation.contentSource
    ? presentation.description + '. ' + t('app.contentLabelSource') + ': ' + presentation.contentSource
    : presentation.description
}

const activeEdgeDetail = computed(() => {
  const id = hoveredEdgeId.value || focusedEdgeId.value
  if (!id || dismissedEdgeId.value === id) return null
  const item = edgesWithAdjustedPoints.value.find(item => item.edge.id === id)
  if (!item || isInternalVariableEdge(item.edge)) return null
  return { ...item, presentation: edgePresentations.value.get(id)! }
})

const syncEdgePopoverSize = () => {
  const canvas = canvasElement.value
  if (!canvas) return
  edgeViewport.width = canvas.clientWidth || window.innerWidth
  edgeViewport.height = canvas.clientHeight || window.innerHeight
  edgeViewport.topInset = Number.parseFloat(getComputedStyle(canvas).paddingTop) || 0
  edgeViewport.popoverHeight = edgePopover.value?.offsetHeight || 0
}

const edgePopoverStyle = computed(() => {
  const point = activeEdgeDetail.value?.labelPoint
  if (!point) return {}
  const frame = props.getVisibleFrame
    ? props.getVisibleFrame()
    : { left: 0, top: edgeViewport.topInset, width: edgeViewport.width, height: edgeViewport.height - edgeViewport.topInset }
  if (!frame) return { display: 'none' }
  const margin = 12
  const gap = 16
  const width = Math.min(320, Math.max(0, frame.width - margin * 2))
  const maxHeight = Math.min(360, Math.max(0, frame.height - margin * 2))
  const height = Math.min(edgeViewport.popoverHeight, maxHeight)
  const pointer = hoveredEdgeId.value === activeEdgeDetail.value?.edge.id ? edgePointer.value : null
  const x = pointer?.x ?? point.x * props.zoom + props.pan.x
  const y = pointer?.y ?? point.y * props.zoom + props.pan.y
  const right = x + gap
  const left = right + width <= frame.left + frame.width - margin ? right : x - gap - width
  return {
    left: Math.min(Math.max(frame.left + margin, left), Math.max(frame.left + margin, frame.left + frame.width - width - margin)) + 'px',
    top: Math.min(Math.max(frame.top + margin, y - margin), Math.max(frame.top + margin, frame.top + frame.height - height - margin)) + 'px',
    width: width + 'px',
    maxHeight: maxHeight + 'px'
  }
})

const clearEdgeLeaveTimer = () => {
  clearTimeout(edgeLeaveTimer)
  edgeLeaveTimer = undefined
}
const clearEdgeEnterTimer = () => {
  clearTimeout(edgeEnterTimer)
  edgeEnterTimer = undefined
  pendingEdgeId = null
}
const recordEdgePointer = (event: PointerEvent) => {
  const rect = canvasElement.value?.getBoundingClientRect()
  edgePointer.value = { x: event.clientX - (rect?.left || 0), y: event.clientY - (rect?.top || 0) }
}
const trackPendingEdgePointer = (event: PointerEvent) => {
  if (pendingEdgeId) recordEdgePointer(event)
}
const setHoveredEdge = (edgeId: string | null, event?: PointerEvent) => {
  clearEdgeLeaveTimer()
  if (edgeId) {
    if (hoveredEdgeId.value === edgeId || pendingEdgeId === edgeId) return
    clearEdgeEnterTimer()
    hoveredEdgeId.value = null
    if (event) recordEdgePointer(event)
    pendingEdgeId = edgeId
    edgeEnterTimer = setTimeout(() => {
      clearEdgeEnterTimer()
      dismissedEdgeId.value = null
      hoveredEdgeId.value = edgeId
    }, EDGE_HOVER_DELAY_MS)
  } else {
    clearEdgeEnterTimer()
    // Leave a short bridge from the line to the readable, selectable card.
    edgeLeaveTimer = setTimeout(() => {
      hoveredEdgeId.value = null
    }, 120)
  }
}
const focusEdgeDetail = (edgeId: string) => {
  clearEdgeEnterTimer()
  clearEdgeLeaveTimer()
  hoveredEdgeId.value = null
  focusedEdgeId.value = edgeId
  dismissedEdgeId.value = null
}
const dismissEdgeDetail = (event?: KeyboardEvent) => {
  clearEdgeEnterTimer()
  if (!activeEdgeDetail.value) return
  event?.preventDefault()
  event?.stopPropagation()
  dismissedEdgeId.value = activeEdgeDetail.value?.edge.id || null
  clearEdgeLeaveTimer()
  hoveredEdgeId.value = null
}
watch(() => props.edges.map(edge => edge.id), ids => {
  if (pendingEdgeId && !ids.includes(pendingEdgeId)) clearEdgeEnterTimer()
  if (hoveredEdgeId.value && !ids.includes(hoveredEdgeId.value)) hoveredEdgeId.value = null
  if (focusedEdgeId.value && !ids.includes(focusedEdgeId.value)) focusedEdgeId.value = null
})
watch(edgePopover, (element, previous) => {
  if (previous) edgeSizeObserver?.unobserve(previous)
  if (element) edgeSizeObserver?.observe(element)
  syncEdgePopoverSize()
}, { flush: 'post' })
onMounted(() => {
  syncEdgePopoverSize()
  if (typeof ResizeObserver !== 'undefined') {
    edgeSizeObserver = new ResizeObserver(syncEdgePopoverSize)
    if (canvasElement.value) edgeSizeObserver.observe(canvasElement.value)
  }
  window.addEventListener('resize', syncEdgePopoverSize)
})
onBeforeUnmount(() => {
  clearEdgeEnterTimer()
  clearEdgeLeaveTimer()
  edgeSizeObserver?.disconnect()
  window.removeEventListener('resize', syncEdgePopoverSize)
})

// A held pointer owns the preview until release or cancellation; another command must not
// persist that preview, change its origin, or remove its node while the gesture is active.
const isNodeCommandBlocked = () => props.interactionLocked
  || activeDragPointerId !== null || activeResizePointerId !== null

const onNodeContextInternal = (node: DeviceNode, e: MouseEvent) => {
  e.preventDefault()
  e.stopPropagation()
  if (isNodeCommandBlocked()) return
  emit('node-context', node, { x: e.clientX, y: e.clientY })
}

const getNodeAriaLabel = (node: DeviceNode) => {
  const base = `${node.label}, ${node.templateName}, ${t('app.state')}: ${getNodeDisplayState(node)}`
  return isTraceActive.value && !isNodeRepresentedInTrace(node)
    ? `${base}. ${t('app.traceVisualization.playbackSceneDeviceNotRepresented')}`
    : base
}

const getNodeTitle = (node: DeviceNode) => {
  const details = [getNodeAriaLabel(node)]
  const security = getNodeSecurityBadges(node).map(badge => badge.label)
  if (security.length > 0) details.push(security.join(', '))
  return details.join(' - ')
}

// Geometry and focus change independently of model wording. Track formatter dependencies here
// so navigation can reuse content while runtime values, templates and locale remain reactive.
const nodeContentCache = computed(() => new Map(props.nodes.map(node => {
  const runtime = getNodeRuntimeBadges(node)
  return [node.id, {
    ariaLabel: getNodeAriaLabel(node),
    title: getNodeTitle(node),
    icon: getCurrentNodeIcon(node),
    stateKey: getNodeVisualStateKey(node),
    stateClass: getStateDisplayClass(node),
    stateTitle: getNodeStateTitle(node),
    stateText: getNodeDisplayState(node),
    runtime,
    hiddenVariableNames: getHiddenVariableNames(node),
    hiddenVariableLabel: runtime.hiddenVariableCount > 0
      ? t('app.traceVisualization.moreVariables', { count: runtime.hiddenVariableCount }) : '',
    security: getNodeSecurityBadges(node)
  }]
})))

// Position stays outside presentation so held dragging and panning reuse unchanged subtrees.
const nodePresentations = computed(() => new Map(props.nodes.map(node => {
  const attacked = isDeviceAttacked(node.id)
  const inTrace = isNodeInTrace(node)
  return [node.id, {
    ...nodeContentCache.value.get(node.id)!,
    classes: [getNodeVisualTierClass(node), {
      'trace-active': inTrace,
      'trace-not-represented': isTraceActive.value && !isNodeRepresentedInTrace(node),
      'trace-changed': isNodeTraceChanged(node),
      'trace-change-pulse': shouldAnimateTraceChange(node),
      'device-attacked': attacked,
      'device-at-violation': isNodeAtViolation(node),
      'node-focused': props.focusedNodeId === node.id,
      'cursor-default': props.interactionLocked
    }],
    // Vue compares CSS text before writing it; object styles rewrite every property even when
    // unchanged. Navigation must not rewrite node geometry and colours on each zoom frame.
    style: `width:${node.width}px;height:${node.height}px;`
      + `--node-accent-color:${getNodeAccentColor(node.id)};`
      + `background-color:${getNodeSurfaceColor(node.id)};`
      + `border-color:${attacked ? 'var(--danger)' : getNodeBorderColor(node.id)};`
      + (inTrace ? `--trace-glow-color:${attacked ? 'var(--danger)' : getNodeBorderColor(node.id)};` : ''),
    // Only card content consumes these values; keep zoom inheritance out of the resize handles.
    contentStyle: `--node-ui-scale:${Math.min(props.zoom, 1)};`
      + `--node-name-lines:${node.height * props.zoom >= 60 ? 3 : 2}`,
    resizeStyle: getNodeResizeHandleGeometry(node),
    label: getNodeVisualTier(node) === 'compact' ? getCompactNodeLabel(node) : node.label
  }]
})))

const getNodePresentation = (node: DeviceNode) => nodePresentations.value.get(node.id)!

const moveNodeByKeyboard = (node: DeviceNode, dx: number, dy: number) => {
  node.position.x += dx
  node.position.y += dy
  updateEdgesForNode(node.id, props.nodes, props.edges)
  emit('node-moved-or-resized', node.id)
}

const resizeNodeByKeyboard = (node: DeviceNode, dw: number, dh: number) => {
  const width = Math.min(NODE_WIDTH_RANGE.max, Math.max(NODE_WIDTH_RANGE.min, node.width + dw))
  const height = Math.min(NODE_HEIGHT_RANGE.max, Math.max(NODE_HEIGHT_RANGE.min, node.height + dh))
  if (width === node.width && height === node.height) return
  node.width = width
  node.height = height
  updateEdgesForNode(node.id, props.nodes, props.edges)
  emit('node-moved-or-resized', node.id)
}

const onNodeKeydown = (event: KeyboardEvent, node: DeviceNode) => {
  const interactionBlocked = isNodeCommandBlocked()
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    if (interactionBlocked) return
    emit('node-open', node)
    return
  }

  if (event.key === 'Delete' || event.key === 'Backspace') {
    event.preventDefault()
    if (interactionBlocked) return
    emit('node-delete', node.id)
    return
  }

  if (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10')) {
    event.preventDefault()
    if (interactionBlocked) return
    const element = event.currentTarget as HTMLElement | null
    const rect = element?.getBoundingClientRect()
    emit('node-context', node, {
      x: rect ? rect.left + rect.width / 2 : 0,
      y: rect ? rect.top + rect.height / 2 : 0
    })
    return
  }

  const step = event.shiftKey ? 1 : 10
  const movement: Record<string, { dx: number; dy: number }> = {
    ArrowUp: { dx: 0, dy: -step },
    ArrowDown: { dx: 0, dy: step },
    ArrowLeft: { dx: -step, dy: 0 },
    ArrowRight: { dx: step, dy: 0 }
  }
  const delta = movement[event.key]
  if (!delta || event.repeat) return
  event.preventDefault()
  if (interactionBlocked) return
  if (event.ctrlKey || event.metaKey) {
    resizeNodeByKeyboard(node, delta.dx, delta.dy)
  } else {
    moveNodeByKeyboard(node, delta.dx, delta.dy)
  }
}

/* ====== 生命周期清理 ====== */

onBeforeUnmount(() => {
  if (nodeAnimationResetTimer) clearTimeout(nodeAnimationResetTimer)
  const restoredDrag = cancelNodeDrag(nodeDragState)
  if (restoredDrag) updateEdgesForNode(restoredDrag.id, props.nodes, props.edges)
  releaseDragPointer()
  const restored = cancelNodeResize(resizeState)
  if (restored) updateEdgesForNode(restored.id, props.nodes, props.edges)
  releaseResizePointer()
  reducedMotionQuery?.removeEventListener?.('change', syncReducedMotionPreference)
})

onMounted(() => {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
  reducedMotionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  syncReducedMotionPreference()
  reducedMotionQuery.addEventListener?.('change', syncReducedMotionPreference)
})
</script>

<template>
  <div
      ref="canvasElement"
      class="canvas"
      data-testid="canvas-board"
      :style="canvasGridStyle"
      @pointerdown="(e: PointerEvent) => emit('canvas-pointerdown', e)"
      @dragover.prevent="(e: DragEvent) => emit('canvas-dragover', e)"
      @drop.prevent="(e: DragEvent) => emit('canvas-drop', e)"
      @mouseenter="() => emit('canvas-enter')"
      @mouseleave="() => emit('canvas-leave')"
      @wheel="(e: WheelEvent) => emit('canvas-wheel', e)"
      @keydown.esc="dismissEdgeDetail"
  >
    <p id="canvas-node-keyboard-instructions" class="sr-only">
      {{ t('app.canvasNodeKeyboardInstructions') }}
    </p>
      <!-- Translate the SVG viewport for panning; keep scaling inside for non-scaling strokes. -->
      <svg class="edge-layer" :style="{ transform: `translate(${pan.x}px, ${pan.y}px)` }">

        <defs>
          <!-- Glow filter for particle effect -->
          <filter id="glow">
            <feGaussianBlur result="coloredBlur" stdDeviation="2"></feGaussianBlur>
            <feMerge>
              <feMergeNode in="coloredBlur"></feMergeNode>
              <feMergeNode in="SourceGraphic"></feMergeNode>
            </feMerge>
          </filter>

          <linearGradient
            v-for="tone in NODE_ACCENT_COUNT" :id="`node-gradient-${tone - 1}`" :key="`gradient-${tone}`"
            x1="0%" x2="100%" y1="0%" y2="0%"
          >
            <stop offset="0%" :style="{ stopColor: `var(--iot-node-accent-${tone - 1})`, stopOpacity: 0.2 }" />
            <stop offset="50%" :style="{ stopColor: `var(--iot-node-accent-${tone - 1})`, stopOpacity: 1 }" />
            <stop offset="100%" :style="{ stopColor: `var(--iot-node-accent-${tone - 1})`, stopOpacity: 0.2 }" />
          </linearGradient>
          <marker
            v-for="tone in NODE_ACCENT_COUNT" :id="`node-arrow-${tone - 1}`" :key="`arrow-${tone}`"
            viewBox="0 0 10 10" markerUnits="userSpaceOnUse"
            :markerWidth="8 / props.zoom" :markerHeight="8 / props.zoom" refX="9" refY="5" orient="auto"
          >
            <path d="M1,1 L9,5 L1,9 z" :fill="`var(--iot-node-accent-${tone - 1})`" />
          </marker>
        </defs>

        <g data-testid="canvas-edge-world" :transform="`scale(${zoom})`">
          <g
              v-for="edgeItem in edgesWithAdjustedPoints"
              :key="edgeItem.edge.id"
              @pointerenter="setHoveredEdge(edgeItem.edge.id, $event)"
              @pointerleave="setHoveredEdge(null)"
              @pointermove="trackPendingEdgePointer"
          >
            <!-- Static connections stay visible; only delivered commands carry motion. -->
            <path
                v-if="edgeItem.edge.from === edgeItem.edge.to"
                class="edge-base-line"
                :class="getEdgePlaybackClass(edgeItem.edge)"
                :d="edgeItem.selfLoopPath"
                fill="none"
                :stroke="edgeItem.baseColor"
                :stroke-dasharray="isInternalVariableEdge(edgeItem.edge) ? '6,6' : ''"
                :marker-end="edgeItem.arrowMarker"
            />
            <line
                v-else
                class="edge-base-line"
                :class="getEdgePlaybackClass(edgeItem.edge)"
                :x1="edgeItem.adjustedPoints.fromPoint.x"
                :y1="edgeItem.adjustedPoints.fromPoint.y"
                :x2="edgeItem.adjustedPoints.toPoint.x"
                :y2="edgeItem.adjustedPoints.toPoint.y"
                fill="none"
                :stroke="edgeItem.baseColor"
                :stroke-dasharray="isInternalVariableEdge(edgeItem.edge) ? '6,6' : ''"
                :marker-end="edgeItem.arrowMarker"
            />

            <path
                v-if="edgeItem.edge.from === edgeItem.edge.to"
                class="edge-hitarea"
                :data-rule-id="edgeItem.edge.ruleId || undefined"
                :d="edgeItem.selfLoopPath"
                role="img"
                tabindex="0"
                :aria-label="getFullEdgeLabel(edgeItem.edge)"
                :aria-describedby="activeEdgeDetail?.edge.id === edgeItem.edge.id ? 'canvas-edge-details' : undefined"
                @pointerenter="setHoveredEdge(edgeItem.edge.id, $event)"
                @pointerleave="setHoveredEdge(null)"
                @focus="focusEdgeDetail(edgeItem.edge.id)"
                @blur="focusedEdgeId = null"
            />
            <line
                v-else
                class="edge-hitarea"
                :data-rule-id="edgeItem.edge.ruleId || undefined"
                role="img"
                tabindex="0"
                :aria-label="getFullEdgeLabel(edgeItem.edge)"
                :aria-describedby="activeEdgeDetail?.edge.id === edgeItem.edge.id ? 'canvas-edge-details' : undefined"
                :x1="edgeItem.adjustedPoints.fromPoint.x"
                :y1="edgeItem.adjustedPoints.fromPoint.y"
                :x2="edgeItem.adjustedPoints.toPoint.x"
                :y2="edgeItem.adjustedPoints.toPoint.y"
                @pointerenter="setHoveredEdge(edgeItem.edge.id, $event)"
                @pointerleave="setHoveredEdge(null)"
                @focus="focusEdgeDetail(edgeItem.edge.id)"
                @blur="focusedEdgeId = null"
            />

            <!-- During model playback, motion represents a backend-reported delivered automation. -->
            <!-- Key includes selectedStateIndex to remount and restart animation on each state transition -->
            <path
                v-if="edgeItem.edge.from === edgeItem.edge.to && shouldRenderEdgeFlow(edgeItem.edge)"
                :key="`edge-flow-loop-${edgeItem.edge.id}-${props.highlightedTrace?.selectedStateIndex ?? -1}`"
                class="edge-line particle-line"
                :data-playback-state="props.highlightedTrace?.selectedStateIndex"
                :class="[getParticleOpacity(edgeItem.index), getEdgePlaybackClass(edgeItem.edge)]"
                :d="edgeItem.selfLoopPath"
                fill="none"
                filter="url(#glow)"
                :stroke="edgeItem.particleColor"
                stroke-width="2"
                :stroke-dasharray="isInternalVariableEdge(edgeItem.edge) ? '5,5' : ''"
                :marker-end="edgeItem.arrowMarker"
            />
            <line
                v-else-if="shouldRenderEdgeFlow(edgeItem.edge)"
                :key="`edge-flow-line-${edgeItem.edge.id}-${props.highlightedTrace?.selectedStateIndex ?? -1}`"
                class="edge-line particle-line"
                :data-playback-state="props.highlightedTrace?.selectedStateIndex"
                :class="[getParticleOpacity(edgeItem.index), getEdgePlaybackClass(edgeItem.edge)]"
                :x1="edgeItem.adjustedPoints.fromPoint.x"
                :y1="edgeItem.adjustedPoints.fromPoint.y"
                :x2="edgeItem.adjustedPoints.toPoint.x"
                :y2="edgeItem.adjustedPoints.toPoint.y"
                fill="none"
                filter="url(#glow)"
                :stroke="edgeItem.particleColor"
                stroke-width="2"
                :stroke-dasharray="isInternalVariableEdge(edgeItem.edge) ? '5,5' : ''"
                :marker-end="edgeItem.arrowMarker"
            />

            <!-- One particle per delivered connection, replayed for the selected transition. -->
            <circle
                v-if="edgeItem.edge.from !== edgeItem.edge.to && shouldRenderEdgeFlow(edgeItem.edge)"
                :key="`edge-flow-particle-${edgeItem.edge.id}-${props.highlightedTrace?.selectedStateIndex ?? -1}`"
                class="trace-flow-particle"
                :data-playback-state="props.highlightedTrace?.selectedStateIndex"
                :fill="edgeItem.particleFillColor"
                filter="url(#glow)"
                :r="getParticleSize(edgeItem.index) / props.zoom"
            >
              <animateMotion
                  :dur="TRACE_FLOW_DURATION"
                  :path="`M ${edgeItem.adjustedPoints.fromPoint.x} ${edgeItem.adjustedPoints.fromPoint.y} L ${edgeItem.adjustedPoints.toPoint.x} ${edgeItem.adjustedPoints.toPoint.y}`"
                  repeatCount="1"
                  fill="freeze"
              />
              <animate
                  attributeName="opacity"
                  values="0;1;1;0"
                  keyTimes="0;0.12;0.8;1"
                  :dur="TRACE_FLOW_DURATION"
                  repeatCount="1"
                  fill="freeze"
              />
            </circle>
          </g>
        </g>
      </svg>

    <div
        class="canvas-inner"
        :style="{
        transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})`,
        transformOrigin: '0 0'
      }"
    >
      <!-- 设备节点 -->
      <div
          v-for="node in nodes"
          :key="node.id"
          v-memo="[getNodePresentation(node), getNodeRenderPosition(node).x, getNodeRenderPosition(node).y]"
          :data-node-id="node.id"
          class="device-node"
          tabindex="0"
          role="button"
          :aria-disabled="interactionLocked ? 'true' : undefined"
          :aria-label="getNodePresentation(node).ariaLabel"
          aria-describedby="canvas-node-keyboard-instructions"
          :title="getNodePresentation(node).title"
          :class="getNodePresentation(node).classes"
          :style="`left:${getNodeRenderPosition(node).x}px;top:${getNodeRenderPosition(node).y}px;${getNodePresentation(node).style}`"
          @pointerdown.stop="onNodePointerDown($event, node)"
          @contextmenu.stop.prevent="onNodeContextInternal(node, $event)"
          @keydown="onNodeKeydown($event, node)"
      >
        <!-- The attack marker remains outside the clipped card content. -->
        <div
          v-if="isDeviceAttacked(node.id)"
          class="attack-indicator"
          :title="t('app.deviceUnderAttack')"
        >
          <span class="material-symbols-outlined">arrow_downward</span>
          <span>{{ t('app.attacked') }}</span>
        </div>
        <div class="device-node-content" :style="getNodePresentation(node).contentStyle">
          <!-- 上部分：图标 -->
          <div class="device-top-row">
            <Transition name="trace-device-icon">
              <img
                  :key="getNodePresentation(node).stateKey"
                  class="device-img iot-device-artwork"
                  :src="getNodePresentation(node).icon"
                  :alt="node.label"
                  draggable="false"
                  @error="handleImageError($event)"
              />
            </Transition>
          </div>
          <!-- 名字 -->
          <div class="device-label-wrapper">
            <div class="device-label" :title="node.label">
              {{ getNodePresentation(node).label }}
            </div>
          </div>
          <!-- 下部分：设备状态显示 -->
          <div class="device-state" :class="getNodePresentation(node).stateClass" :title="getNodePresentation(node).stateTitle">
            <span class="device-state-dot"></span>
            <span class="device-reading-slot">
              <Transition name="trace-device-state">
                <span :key="getNodePresentation(node).stateKey" class="device-state-value">{{ getNodePresentation(node).stateText }}</span>
              </Transition>
            </span>
          </div>
          <div
            v-if="getNodePresentation(node).runtime.badges.length > 0"
            class="device-runtime-strip"
          >
            <span
                v-for="badge in getNodePresentation(node).runtime.badges"
                :key="badge.label"
                class="device-runtime-chip"
                :class="{ 'device-runtime-chip--changed': badge.changed }"
                :title="badge.title"
            >
              <span class="device-runtime-chip__label">{{ badge.label }}</span>
              <!-- Full previous/current pairs belong in the popover. -->
              <span class="device-runtime-chip__value">{{ badge.value }}</span>
            </span>
            <!-- Name the variables omitted from the compact strip. -->
            <HintTooltip
              v-if="getNodePresentation(node).runtime.hiddenVariableCount > 0"
              :content="getNodePresentation(node).hiddenVariableNames"
            >
              <span
                class="device-runtime-chip device-runtime-chip--overflow"
                :aria-label="getNodePresentation(node).hiddenVariableLabel"
              >+{{ getNodePresentation(node).runtime.hiddenVariableCount }}</span>
            </HintTooltip>
          </div>
          <div v-if="getNodePresentation(node).security.length > 0" class="device-node-actions">
            <span
              v-for="badge in getNodePresentation(node).security"
              :key="badge.kind"
              class="device-node-trust"
              :class="`device-node-trust--${badge.kind}`"
              :title="`${badge.label} — ${badge.title}`"
            >
              <!-- The pill prints the category; the node's `title` and this one carry the full statement. -->
              {{ badge.shortLabel }}
              <span class="sr-only">{{ badge.label }}</span>
            </span>
          </div>
        </div>

        <!-- 四角缩放手柄 -->
        <div
            v-if="!interactionLocked && canShowAllPointerResizeHandles(node)"
            class="resize-handle tl"
            :style="getNodePresentation(node).resizeStyle"
            aria-hidden="true"
            @pointerdown.stop="onPointerDownResize($event, node, 'tl')"
        ></div>
        <div
            v-if="!interactionLocked && canShowAllPointerResizeHandles(node)"
            class="resize-handle tr"
            :style="getNodePresentation(node).resizeStyle"
            aria-hidden="true"
            @pointerdown.stop="onPointerDownResize($event, node, 'tr')"
        ></div>
        <div
            v-if="!interactionLocked && canShowAllPointerResizeHandles(node)"
            class="resize-handle bl"
            :style="getNodePresentation(node).resizeStyle"
            aria-hidden="true"
            @pointerdown.stop="onPointerDownResize($event, node, 'bl')"
        ></div>
        <div
            v-if="!interactionLocked"
            class="resize-handle br"
            :style="getNodePresentation(node).resizeStyle"
            aria-hidden="true"
            @pointerdown.stop="onPointerDownResize($event, node, 'br')"
        ></div>
      </div>
    </div>
    <section
      v-if="activeEdgeDetail"
      id="canvas-edge-details"
      data-testid="canvas-edge-details"
      :data-edge-id="activeEdgeDetail.edge.id"
      ref="edgePopover"
      class="edge-label iot-scroll-region"
      role="tooltip"
      :style="edgePopoverStyle"
      @pointerenter="clearEdgeLeaveTimer()"
      @pointerleave="setHoveredEdge(null)"
      @pointerdown.stop
      @wheel.stop
    >
      <header class="edge-label__heading">
        <span class="material-symbols-outlined" aria-hidden="true">rule</span>
        <span>{{ t('app.edgeRuleDetails') }}</span>
      </header>
      <div class="edge-label__group">
        <h4>{{ t('app.edgeTriggerConditions') }}</h4>
        <ol class="edge-label__conditions">
          <li v-for="(condition, index) in activeEdgeDetail.presentation.conditions" :key="index">{{ condition }}</li>
        </ol>
      </div>
      <div class="edge-label__group edge-label__command">
        <h4>{{ t('app.edgeAction') }}</h4>
        <p><span>{{ activeEdgeDetail.presentation.target }}</span><strong>{{ activeEdgeDetail.presentation.action }}</strong></p>
      </div>
      <div v-if="activeEdgeDetail.presentation.contentSource" class="edge-label__group">
        <h4>{{ t('app.contentLabelSource') }}</h4>
        <p>{{ activeEdgeDetail.presentation.contentSource }}</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.edge-hitarea {
  fill: none;
  stroke: transparent;
  stroke-width: 18;
  vector-effect: non-scaling-stroke;
  stroke-linecap: round;
  pointer-events: stroke;
  cursor: help;
}

.edge-hitarea:focus-visible {
  outline: none;
  stroke: color-mix(in srgb, var(--iot-color-accent) 18%, transparent);
}

.edge-base-line,
.edge-line,
.edge-layer circle {
  pointer-events: none;
}

.edge-label {
  position: absolute;
  z-index: 30;
  box-sizing: border-box;
  padding: var(--iot-space-md);
  border: 1px solid var(--control-border);
  border-radius: var(--iot-radius-card);
  background: var(--surface-elevated);
  color: var(--text);
  box-shadow: var(--shadow-floating);
  font-size: var(--iot-font-label);
  line-height: 1.6;
  overflow-wrap: anywhere;
  user-select: text;
  touch-action: pan-y;
}

.edge-label__heading {
  display: flex;
  align-items: center;
  gap: var(--iot-space-sm);
  padding-bottom: var(--iot-space-sm);
  border-bottom: 1px solid var(--border);
  font-size: var(--iot-font-control);
  font-weight: 600;
}

.edge-label__heading .material-symbols-outlined { font-size: 1rem; }
.edge-label__group { margin-top: var(--iot-space-sm); }
.edge-label__group h4 {
  margin: 0 0 var(--iot-space-2xs);
  color: var(--text-muted);
  font-size: var(--iot-font-label);
  font-weight: 600;
}
.edge-label__group p { margin: 0; }
.edge-label__conditions {
  display: grid;
  gap: var(--iot-space-xs);
  margin: 0;
  padding-left: var(--iot-space-lg);
  list-style: decimal;
}
.edge-label__conditions li { padding-left: var(--iot-space-2xs); }
.edge-label__conditions li::marker { color: var(--text-muted); }
.edge-label__command {
  padding: var(--iot-space-sm);
  border: 1px solid var(--border);
  border-radius: var(--iot-radius-action);
  background: var(--surface-muted);
}
.edge-label__command p {
  display: flex;
  flex-wrap: wrap;
  gap: var(--iot-space-2xs) var(--iot-space-sm);
}
.edge-label__command strong { font-weight: 600; }

/* Attack indicator - longer arrow with text */
.attack-indicator {
  position: absolute;
  top: -32px;
  left: 50%;
  transform: translateX(-50%);
  /* The fill role keeps light labels readable in both modes. */
  background: linear-gradient(160deg,
      var(--danger-fill) 0%,
      color-mix(in srgb, var(--danger-fill) 85%, var(--danger-fill-hover)) 100%);
  color: var(--text-on-fill);
  padding: 3px 8px;
  border-radius: var(--iot-radius-well);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 8px color-mix(in srgb, var(--danger-fill) 50%, transparent);
  z-index: 20;
  animation: attackBounce 0.8s ease-in-out infinite;
  white-space: nowrap;
  font-size: var(--iot-font-min);
  font-weight: bold;
  gap: 3px;
  height: auto;
  width: auto;
  min-width: 50px;
}

.attack-indicator .material-symbols-outlined {
  font-size: 14px;
}

@keyframes attackBounce {
  0%, 100% {
    transform: translateX(-50%) translateY(0);
  }
  50% {
    transform: translateX(-50%) translateY(-3px);
  }
}

.device-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: calc(0.25rem / var(--node-ui-scale));
  justify-self: stretch;
  min-width: 0;
  width: 100%;
  color: var(--text);
  font-weight: 500;
  z-index: 5;
  box-sizing: border-box;
  line-height: 1.2;
  overflow: hidden;
}

.device-node--expanded .device-state {
  grid-area: state;
  justify-self: start;
  width: 100%;
}

.device-state-dot {
  width: calc(0.3125rem / var(--node-ui-scale));
  height: calc(0.3125rem / var(--node-ui-scale));
  border-radius: 50%;
  flex-shrink: 0;
  display: inline-block;
}

.device-state-value {
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: calc(var(--iot-font-caption) / var(--node-ui-scale));
  line-height: 1.2;
}

.state-defined .device-state-dot {
  background: var(--node-accent-color);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--node-accent-color) 18%, transparent);
}

.state-stateless {
  color: var(--text-muted);
}

.state-stateless .device-state-dot {
  background: var(--text-muted);
}

@media (prefers-reduced-motion: reduce) {
  .attack-indicator {
    animation: none !important;
  }
}

.device-top-row {
  position: relative;
}

</style>
