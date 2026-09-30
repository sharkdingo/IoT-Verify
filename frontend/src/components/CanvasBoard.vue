<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import type { DeviceNode } from '../types/node'
import type { DeviceTemplate } from '../types/device'
import type { DeviceEdge } from '../types/edge'
import type { CanvasPan } from '../types/canvas'
import type { ModelTokenSource } from '../types/modelToken'

import { updateEdgesForNode } from '../utils/canvas/geometry'

import { getLinkPoints, getSelfLoopPath } from '../utils/rule'

import {
  findTraceVariableAtOrBefore,
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
  getNodeAccentColor,
  getNodeBorderColor,
  getNodeColorIndex,
  getNodeSurfaceColor
} from '../utils/canvas/nodePalette'
import { estimateCanvasTextWidth, truncateCanvasTextToWidth } from '../utils/canvas/canvasText'
import HintTooltip from '@/components/common/HintTooltip.vue'
import { normalizeModelRelation } from '@/utils/modelRequest'

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

const fallbackDeviceSvg = `<svg width="72" height="72" viewBox="0 0 72 72" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect x="14" y="12" width="44" height="48" rx="10" fill="var(--border)" stroke="var(--text-muted)" stroke-width="3"/>
  <circle cx="36" cy="32" r="10" fill="#FFFFFF" stroke="var(--text-muted)" stroke-width="3"/>
  <path d="M26 50h20" stroke="var(--text-muted)" stroke-width="4" stroke-linecap="round"/>
</svg>`

const svgDataUri = (svg: string): string =>
  `data:image/svg+xml;base64,${btoa(unescape(encodeURIComponent(svg)))}`

// Handle image loading errors by showing a stable inline SVG fallback.
const handleImageError = (event: Event) => {
  const img = event.target as HTMLImageElement
  img.onerror = null
  img.src = svgDataUri(fallbackDeviceSvg)
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
    return props.getNodeIcon(node) || svgDataUri(fallbackDeviceSvg)
  }
  const currentState = getNodeState(node) || 'Working'

  return props.getNodeIcon(node, currentState) || svgDataUri(fallbackDeviceSvg)
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
    '--canvas-grid-size': `${gridSize}px`,
    '--canvas-grid-offset-x': `${offsetX}px`,
    '--canvas-grid-offset-y': `${offsetY}px`
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

    return {
      edge,
      fromNode: sourceNode,
      toNode: nodeMap.value.get(edge.to),
      adjustedPoints,
      selfLoopPath,
      labelPoint,
      index, // Pre-computed index to avoid O(E) indexOf() in template
      // 预计算样式
      particleColor: isInternal ? 'var(--text-muted)' :
        ['url(#grad-blue)', 'url(#grad-green)', 'url(#grad-purple)', 'url(#grad-orange)',
         'url(#grad-red)', 'url(#grad-teal)', 'url(#grad-pink)', 'url(#grad-yellow)'][colorIndex],
      arrowMarker: isInternal ? '' :
        ['url(#arrow-blue)', 'url(#arrow-green)', 'url(#arrow-purple)', 'url(#arrow-orange)',
         'url(#arrow-red)', 'url(#arrow-teal)', 'url(#arrow-pink)', 'url(#arrow-yellow)'][colorIndex],
      particleFillColor: sourceNode ? getNodeAccentColor(sourceNode.id) : 'var(--iot-node-accent-0)'
    }
  })
})

const getLatestTraceVariableValueForNode = (nodeId: string, variableName: string): string | null => {
  if (!props.highlightedTrace?.states) return null
  const currentIndex = props.highlightedTrace.selectedStateIndex || 0
  const variable = findTraceVariableAtOrBefore(props.highlightedTrace, nodeId, variableName, currentIndex)
  return variable ? normalizeTraceComparable(variable.value) : null
}

const getLatestTraceVariableForNode = (nodeId: string, variableName: string) => {
  if (!props.highlightedTrace?.states) return null
  return findTraceVariableAtOrBefore(
    props.highlightedTrace,
    nodeId,
    variableName,
    props.highlightedTrace.selectedStateIndex || 0
  ) as PlaybackTraceVariable | null
}

const getPreviousTraceVariableForNode = (
  nodeId: string,
  variableName: string
): PlaybackTraceVariable | null => {
  if (!props.highlightedTrace?.states || props.highlightedTrace.selectedStateIndex === undefined) return null
  if (props.highlightedTrace.selectedStateIndex <= 0) return null
  return findTraceVariableAtOrBefore(
    props.highlightedTrace,
    nodeId,
    variableName,
    props.highlightedTrace.selectedStateIndex - 1
  ) as PlaybackTraceVariable | null
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
let activeDragMoved = false

const removeDragListeners = () => {
  window.removeEventListener('pointermove', onNodePointerMove)
  window.removeEventListener('pointerup', onNodePointerUp)
  window.removeEventListener('pointercancel', onNodePointerCancel)
}

const releaseDragPointer = () => {
  const target = activeDragTarget
  const pointerId = activeDragPointerId
  const nodeId = activeDragNodeId
  activeDragPointerId = null
  activeDragTarget = null
  activeDragNodeId = null
  activeDragStartPoint = null
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
  if (!activeDragMoved && activeDragStartPoint) {
    const distance = Math.hypot(
      e.clientX - activeDragStartPoint.x,
      e.clientY - activeDragStartPoint.y
    )
    if (distance < NODE_DRAG_THRESHOLD_PX) return
    activeDragMoved = true
  }
  updateNodeDrag(e, nodeDragState, props.zoom)
}

const onNodePointerUp = (e: PointerEvent) => {
  if (e.pointerId !== activeDragPointerId) return
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
  const changed = updateNodeResize(e, resizeState, props.zoom)
  if (!changed || !resizeState.node) return

  updateEdgesForNode(resizeState.node.id, props.nodes, props.edges)
}

const onPointerUpResize = (e: PointerEvent) => {
  if (e.pointerId !== activeResizePointerId) return
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

  const { fromPoint, toPoint } = getLinkPoints(fromNode, toNode)

  // If bidirectional, add offset to avoid overlap
  if (hasBidirectionalEdges(edge.from, edge.to)) {
    // Determine offset based on lexicographic order of node IDs
    // This ensures: A->B always gets same offset, B->A gets opposite
    const nodes = [edge.from, edge.to].sort()
    const isFirstDirection = (edge.from === nodes[0] && edge.to === nodes[1])
    const offset = isFirstDirection ? 25 : -25 // First lexicographic direction up, second down

    // Add perpendicular offset to the line (simple up/down offset)
    return {
      fromPoint: {
        x: fromPoint.x,
        y: fromPoint.y + offset
      },
      toPoint: {
        x: toPoint.x,
        y: toPoint.y + offset
      }
    }
  }

  return { fromPoint, toPoint }
}

type NodeVisualTier = 'compact' | 'condensed' | 'expanded'

/**
 * An edge label's relation, keyed on the canonical form and using the same glyphs as the inspector.
 *
 * The map was keyed on `EQ`/`GTE`/`LTE`, which nothing persists — `RuleBuilderDialog` authors symbol form and the
 * backend canonicalises to symbols — so every key was dead and only the fallthrough ran. Meanwhile `in` rendered
 * as a translated word here and as `∈` in the inspector, for the same condition on the same rule. One operator
 * should not have two readings on two surfaces a user compares side by side.
 */
const getRelationSymbol = (relation?: string) => {
  if (!relation) return ''
  const canonical = normalizeModelRelation(relation) ?? relation
  const glyphs: Record<string, string> = {
    '=': '=',
    '!=': '≠',
    '>': '>',
    '>=': '≥',
    '<': '<',
    '<=': '≤',
    'in': '∈',
    'not in': '∉'
  }
  return glyphs[canonical] || canonical
}

const hasValue = (value: unknown) =>
  value !== null && value !== undefined && String(value).trim() !== ''

const getNodeVisualTier = (node: DeviceNode): NodeVisualTier => {
  const screenWidth = node.width * props.zoom
  const screenHeight = node.height * props.zoom
  if (screenWidth < 100 || screenHeight < 72) return 'compact'
  if (screenWidth < 168 || screenHeight < 118) return 'condensed'
  return 'expanded'
}

const getNodeVisualTierClass = (node: DeviceNode) =>
  `device-node--${getNodeVisualTier(node)}`

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
const POINTER_RESIZE_MIN_NODE_SIZE_PX = POINTER_RESIZE_TARGET_SIZE_PX + 8
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
 * The touch target stays 44 screen pixels at every zoom — that is a WCAG floor, not a preference. What adapts
 * is *where* those 44px sit relative to the corner: normally half in / half out, but on a node too small to
 * spare 22px the handle slides outward so it keeps its size without smothering the node. At zoom 1 on an 80×60
 * node the cap does not bind (35% of 60 = 21px vs a 22px natural reach, so the shift is 1px); at zoom 0.3 the
 * same node is 24×18 on screen and the handle sits 6px in / 38px out.
 *
 * Declared per node rather than once on the canvas because the cap depends on the node's own dimensions.
 */
const getNodeResizeHandleGeometry = (node: DeviceNode) => {
  const zoom = Math.max(props.zoom, 0.01)
  const nodeScreenMin = Math.min(node.width, node.height) * zoom
  const inwardPx = Math.min(
    POINTER_RESIZE_TARGET_SIZE_PX / 2,
    nodeScreenMin * POINTER_RESIZE_MAX_INWARD_FRACTION
  )
  return {
    '--resize-hit-size': `${POINTER_RESIZE_TARGET_SIZE_PX / zoom}px`,
    // Negative because each handle is positioned by its outer edge: the more negative, the farther out it sits.
    '--resize-hit-offset': `${-(POINTER_RESIZE_TARGET_SIZE_PX - inwardPx) / zoom}px`
  }
}

/**
 * Get the visual rendering position of a node.
 * During drag, returns the temporary position; otherwise returns the committed position.
 * This prevents triggering Vue reactivity on every pointermove event.
 */
const getNodeRenderPosition = (node: DeviceNode) => {
  return getNodeDragPosition(node, nodeDragState)
}

/**
 * Whether the bottom-right handle renders — the one grip that makes a node growable by pointer.
 *
 * This used to require 52 screen pixels in *both* axes, which silently locked the product's smallest node.
 * `NODE_HEIGHT_RANGE.min` is 60, so a minimum-sized node fell below the threshold at any zoom under 52/60 =
 * 0.867: at zoom 0.85 it measured 68×51 and lost every handle, one pixel short. The only remaining way to
 * grow it was Ctrl+arrow, which nothing on screen advertises — so the node was, to the user, permanently
 * stuck at its smallest size.
 *
 * A node at its minimum **in both dimensions** is exactly when a grip matters most, so those get an
 * unconditional guarantee. Nodes with one dimension at minimum but the other larger (e.g. 80×120) still use
 * the screen-space test, because a tall skinny node has room at any reasonable zoom. The collision that the
 * 52px threshold was really protecting against is now handled by `getNodeResizeHandleGeometry` capping the
 * handle's inward reach.
 */
const canPointerResizeNode = (node: DeviceNode) =>
  (node.width <= NODE_WIDTH_RANGE.min && node.height <= NODE_HEIGHT_RANGE.min)
  || (node.width * props.zoom >= POINTER_RESIZE_MIN_NODE_SIZE_PX
    && node.height * props.zoom >= POINTER_RESIZE_MIN_NODE_SIZE_PX)

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
  const template = props.deviceTemplates?.find(candidate => templateName && (
    String(candidate.name || '').trim().toLowerCase() === templateName
    || String(candidate.manifest?.Name || '').trim().toLowerCase() === templateName
  ))
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

const getFullEdgeLabel = (edge: DeviceEdge) => {
  const sourceName = edge.fromLabel || edge.from
  const targetName = edge.toLabel || edge.to
  const sourceNode = nodeMap.value.get(edge.from)
  const targetNode = nodeMap.value.get(edge.to)
  const relation = getRelationSymbol(edge.relation)
  const sourceSignal = edge.fromApi
    ? (sourceNode ? formatNodeModelToken(sourceNode, edge.fromApi) : edge.fromApi)
    : edge.itemType || t('app.condition')
  const sourceValue = sourceNode ? formatNodeModelToken(sourceNode, edge.value) : edge.value
  const condition = relation && hasValue(edge.value)
    ? `${sourceName}.${sourceSignal} ${relation} ${sourceValue}`
    : `${sourceName}.${sourceSignal}`
  const targetAction = edge.toApi && targetNode
    ? formatNodeModelToken(targetNode, edge.toApi)
    : edge.toApi
  const action = targetAction ? `${targetName}.${targetAction}` : targetName
  return `${condition} -> ${action}`
}

const getEdgeLabelText = (edge: DeviceEdge) =>
  truncateCanvasTextToWidth(getFullEdgeLabel(edge), 222)

const getEdgeLabelWidth = (edge: DeviceEdge) => {
  const textWidth = estimateCanvasTextWidth(getEdgeLabelText(edge))
  return Math.min(240, Math.max(76, textWidth + 18))
}

const hoveredEdgeId = ref<string | null>(null)
const focusedEdgeId = ref<string | null>(null)

const shouldShowEdgeLabel = (edge: DeviceEdge) =>
  !isInternalVariableEdge(edge) &&
  (hoveredEdgeId.value === edge.id || focusedEdgeId.value === edge.id)

const setHoveredEdge = (edgeId: string | null) => {
  hoveredEdgeId.value = edgeId
}

const onNodeContextInternal = (node: DeviceNode, e: MouseEvent) => {
  e.preventDefault()
  e.stopPropagation()
  if (props.interactionLocked) return
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
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    if (props.interactionLocked) return
    emit('node-open', node)
    return
  }

  if (event.key === 'Delete' || event.key === 'Backspace') {
    event.preventDefault()
    if (props.interactionLocked) return
    emit('node-delete', node.id)
    return
  }

  if (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10')) {
    event.preventDefault()
    if (props.interactionLocked) return
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
  if (props.interactionLocked) return
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
      class="canvas"
      data-testid="canvas-board"
      :style="canvasGridStyle"
      @pointerdown="(e: PointerEvent) => emit('canvas-pointerdown', e)"
      @dragover.prevent="(e: DragEvent) => emit('canvas-dragover', e)"
      @drop.prevent="(e: DragEvent) => emit('canvas-drop', e)"
      @mouseenter="() => emit('canvas-enter')"
      @mouseleave="() => emit('canvas-leave')"
      @wheel="(e: WheelEvent) => emit('canvas-wheel', e)"
  >
    <p id="canvas-node-keyboard-instructions" class="sr-only">
      {{ t('app.canvasNodeKeyboardInstructions') }}
    </p>
    <div
        class="canvas-inner"
        :style="{
        transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})`,
        transformOrigin: '0 0',
        '--canvas-zoom': zoom,
        '--resize-visual-size': `${11.2 / Math.max(zoom, 0.01)}px`
      }"
    >
      <!-- 连线层 -->
      <svg class="edge-layer">

        <defs>
          <!-- Glow filter for particle effect -->
          <filter id="glow">
            <feGaussianBlur result="coloredBlur" stdDeviation="2"></feGaussianBlur>
            <feMerge>
              <feMergeNode in="coloredBlur"></feMergeNode>
              <feMergeNode in="SourceGraphic"></feMergeNode>
            </feMerge>
          </filter>

          <!-- Gradient definitions -->
          <linearGradient id="grad-blue" x1="0%" x2="100%" y1="0%" y2="0%">
            <stop offset="0%" style="stop-color:var(--iot-node-accent-0);stop-opacity:0.2"></stop>
            <stop offset="50%" style="stop-color:var(--iot-node-accent-0);stop-opacity:1"></stop>
            <stop offset="100%" style="stop-color:var(--iot-node-accent-0);stop-opacity:0.2"></stop>
          </linearGradient>

          <linearGradient id="grad-purple" x1="0%" x2="100%" y1="0%" y2="0%">
            <stop offset="0%" style="stop-color:var(--iot-node-accent-2);stop-opacity:0.2"></stop>
            <stop offset="50%" style="stop-color:var(--iot-node-accent-2);stop-opacity:1"></stop>
            <stop offset="100%" style="stop-color:var(--iot-node-accent-2);stop-opacity:0.2"></stop>
          </linearGradient>

          <linearGradient id="grad-green" x1="0%" x2="100%" y1="0%" y2="0%">
            <stop offset="0%" style="stop-color:var(--iot-node-accent-1);stop-opacity:0.2"></stop>
            <stop offset="50%" style="stop-color:var(--iot-node-accent-1);stop-opacity:1"></stop>
            <stop offset="100%" style="stop-color:var(--iot-node-accent-1);stop-opacity:0.2"></stop>
          </linearGradient>

          <linearGradient id="grad-orange" x1="0%" x2="100%" y1="0%" y2="0%">
            <stop offset="0%" style="stop-color:var(--iot-node-accent-3);stop-opacity:0.2"></stop>
            <stop offset="50%" style="stop-color:var(--iot-node-accent-3);stop-opacity:1"></stop>
            <stop offset="100%" style="stop-color:var(--iot-node-accent-3);stop-opacity:0.2"></stop>
          </linearGradient>

          <linearGradient id="grad-red" x1="0%" x2="100%" y1="0%" y2="0%">
            <stop offset="0%" style="stop-color:var(--iot-node-accent-4);stop-opacity:0.2"></stop>
            <stop offset="50%" style="stop-color:var(--iot-node-accent-4);stop-opacity:1"></stop>
            <stop offset="100%" style="stop-color:var(--iot-node-accent-4);stop-opacity:0.2"></stop>
          </linearGradient>

          <linearGradient id="grad-teal" x1="0%" x2="100%" y1="0%" y2="0%">
            <stop offset="0%" style="stop-color:var(--iot-node-accent-5);stop-opacity:0.2"></stop>
            <stop offset="50%" style="stop-color:var(--iot-node-accent-5);stop-opacity:1"></stop>
            <stop offset="100%" style="stop-color:var(--iot-node-accent-5);stop-opacity:0.2"></stop>
          </linearGradient>

          <linearGradient id="grad-pink" x1="0%" x2="100%" y1="0%" y2="0%">
            <stop offset="0%" style="stop-color:var(--iot-node-accent-6);stop-opacity:0.2"></stop>
            <stop offset="50%" style="stop-color:var(--iot-node-accent-6);stop-opacity:1"></stop>
            <stop offset="100%" style="stop-color:var(--iot-node-accent-6);stop-opacity:0.2"></stop>
          </linearGradient>

          <linearGradient id="grad-yellow" x1="0%" x2="100%" y1="0%" y2="0%">
            <stop offset="0%" style="stop-color:var(--iot-node-accent-7);stop-opacity:0.2"></stop>
            <stop offset="50%" style="stop-color:var(--iot-node-accent-7);stop-opacity:1"></stop>
            <stop offset="100%" style="stop-color:var(--iot-node-accent-7);stop-opacity:0.2"></stop>
          </linearGradient>

          <!-- Arrow markers for different colors -->
          <marker id="arrow-blue" markerWidth="10" markerHeight="10" refX="10" refY="3" orient="auto">
            <path d="M0,0 L0,6 L9,3 z" fill="var(--iot-node-accent-0)"></path>
          </marker>
          <marker id="arrow-green" markerWidth="10" markerHeight="10" refX="10" refY="3" orient="auto">
            <path d="M0,0 L0,6 L9,3 z" fill="var(--iot-node-accent-1)"></path>
          </marker>
          <marker id="arrow-purple" markerWidth="10" markerHeight="10" refX="10" refY="3" orient="auto">
            <path d="M0,0 L0,6 L9,3 z" fill="var(--iot-node-accent-2)"></path>
          </marker>
          <marker id="arrow-orange" markerWidth="10" markerHeight="10" refX="10" refY="3" orient="auto">
            <path d="M0,0 L0,6 L9,3 z" fill="var(--iot-node-accent-3)"></path>
          </marker>
          <marker id="arrow-red" markerWidth="10" markerHeight="10" refX="10" refY="3" orient="auto">
            <path d="M0,0 L0,6 L9,3 z" fill="var(--iot-node-accent-4)"></path>
          </marker>
          <marker id="arrow-teal" markerWidth="10" markerHeight="10" refX="10" refY="3" orient="auto">
            <path d="M0,0 L0,6 L9,3 z" fill="var(--iot-node-accent-5)"></path>
          </marker>
          <marker id="arrow-pink" markerWidth="10" markerHeight="10" refX="10" refY="3" orient="auto">
            <path d="M0,0 L0,6 L9,3 z" fill="var(--iot-node-accent-6)"></path>
          </marker>
          <marker id="arrow-yellow" markerWidth="10" markerHeight="10" refX="10" refY="3" orient="auto">
            <path d="M0,0 L0,6 L9,3 z" fill="var(--iot-node-accent-7)"></path>
          </marker>
        </defs>

        <g
            v-for="edgeItem in edgesWithAdjustedPoints"
            :key="edgeItem.edge.id"
            @pointerenter="setHoveredEdge(edgeItem.edge.id)"
            @pointerleave="setHoveredEdge(null)"
        >
          <!-- Base lines removed - only showing particle effects -->
          <path
              v-if="edgeItem.edge.from === edgeItem.edge.to"
              class="edge-base-line"
              :class="getEdgePlaybackClass(edgeItem.edge)"
              :d="edgeItem.selfLoopPath"
              fill="none"
              :stroke="edgeItem.particleColor"
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
              :stroke="edgeItem.particleColor"
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
              @pointerenter="setHoveredEdge(edgeItem.edge.id)"
              @pointerleave="setHoveredEdge(null)"
              @focus="focusedEdgeId = edgeItem.edge.id"
              @blur="focusedEdgeId = null"
          />
          <line
              v-else
              class="edge-hitarea"
              :data-rule-id="edgeItem.edge.ruleId || undefined"
              role="img"
              tabindex="0"
              :aria-label="getFullEdgeLabel(edgeItem.edge)"
              :x1="edgeItem.adjustedPoints.fromPoint.x"
              :y1="edgeItem.adjustedPoints.fromPoint.y"
              :x2="edgeItem.adjustedPoints.toPoint.x"
              :y2="edgeItem.adjustedPoints.toPoint.y"
              @pointerenter="setHoveredEdge(edgeItem.edge.id)"
              @pointerleave="setHoveredEdge(null)"
              @focus="focusedEdgeId = edgeItem.edge.id"
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

          <!-- A compromised or idle automation remains visible as a static edge. -->
          <!-- Key includes selectedStateIndex to remount and restart animation on each state transition -->
          <circle
              v-if="edgeItem.edge.from !== edgeItem.edge.to && shouldRenderEdgeFlow(edgeItem.edge)"
              :key="`edge-flow-particle-${edgeItem.edge.id}-${props.highlightedTrace?.selectedStateIndex ?? -1}`"
              class="trace-flow-particle"
              :data-playback-state="props.highlightedTrace?.selectedStateIndex"
              :fill="edgeItem.particleFillColor"
              filter="url(#glow)"
              :r="getParticleSize(edgeItem.index)"
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
          <g
              v-if="shouldShowEdgeLabel(edgeItem.edge)"
              class="edge-label"
              :transform="`translate(${edgeItem.labelPoint.x} ${edgeItem.labelPoint.y})`"
          >
            <title>{{ getFullEdgeLabel(edgeItem.edge) }}</title>
            <rect
                class="edge-label__bg"
                :x="-getEdgeLabelWidth(edgeItem.edge) / 2"
                y="-10"
                :width="getEdgeLabelWidth(edgeItem.edge)"
                height="20"
                rx="10"
            />
            <text class="edge-label__text" text-anchor="middle" dominant-baseline="middle">
              {{ getEdgeLabelText(edgeItem.edge) }}
            </text>
          </g>
          <!-- For self-loops, we could add a different animation -->
        </g>
      </svg>

      <!-- 设备节点 -->
      <div
          v-for="node in nodes"
          :key="node.id"
          :data-node-id="node.id"
          class="device-node"
          tabindex="0"
          role="button"
          :aria-disabled="interactionLocked ? 'true' : undefined"
          :aria-label="getNodeAriaLabel(node)"
          aria-describedby="canvas-node-keyboard-instructions"
          :title="getNodeTitle(node)"
          :class="[getNodeVisualTierClass(node), { 'trace-active': isNodeInTrace(node) }, { 'trace-not-represented': isTraceActive && !isNodeRepresentedInTrace(node) }, { 'trace-changed': isNodeTraceChanged(node) }, { 'trace-change-pulse': shouldAnimateTraceChange(node) }, { 'device-attacked': isDeviceAttacked(node.id) }, { 'device-at-violation': isNodeAtViolation(node) }, { 'node-focused': props.focusedNodeId === node.id }, { 'cursor-default': interactionLocked }]"
          :style="{
          left: getNodeRenderPosition(node).x + 'px',
          top: getNodeRenderPosition(node).y + 'px',
          width: node.width + 'px',
          height: node.height + 'px',
          '--canvas-zoom': props.zoom,
          '--node-accent-color': getNodeAccentColor(node.id),
          backgroundColor: getNodeSurfaceColor(node.id),
          borderColor: isDeviceAttacked(node.id) ? 'var(--danger)' : getNodeBorderColor(node.id),
          ...(isNodeInTrace(node) ? { '--trace-glow-color': isDeviceAttacked(node.id) ? 'var(--danger)' : getNodeBorderColor(node.id) } : {}),
          ...getNodeResizeHandleGeometry(node)
        }"
          @pointerdown.stop="onNodePointerDown($event, node)"
          @contextmenu.stop.prevent="onNodeContextInternal(node, $event)"
          @keydown="onNodeKeydown($event, node)"
      >
        <div class="device-node-content">
          <!-- Attack indicator arrow -->
          <div 
            v-if="isDeviceAttacked(node.id)"
            class="attack-indicator"
            :title="t('app.deviceUnderAttack')"
          >
            <span class="material-symbols-outlined">arrow_downward</span>
            <span>{{ t('app.attacked') }}</span>
          </div>
          <!-- 上部分：图标 -->
          <div class="device-top-row">
            <Transition name="trace-device-icon">
              <img
                  :key="getNodeVisualStateKey(node)"
                  class="device-img"
                  :src="getCurrentNodeIcon(node)"
                  :alt="node.label"
                  draggable="false"
                  @error="handleImageError($event)"
              />
            </Transition>
          </div>
          <!-- 名字 -->
          <div class="device-label-wrapper">
            <div class="device-label" :title="node.label">
              {{ getNodeVisualTier(node) === 'compact' ? getCompactNodeLabel(node) : node.label }}
            </div>
          </div>
          <!-- 下部分：设备状态显示 -->
          <div class="device-state" :class="getStateDisplayClass(node)" :title="getNodeStateTitle(node)">
            <span class="device-state-dot"></span>
            <Transition name="trace-device-state" mode="out-in">
              <span :key="getNodeVisualStateKey(node)" class="device-state-value">{{ getNodeDisplayState(node) }}</span>
            </Transition>
          </div>
          <!--
            One call, destructured: `getNodeRuntimeBadges` was invoked twice per node per render — once for the
            `v-if` and once for the `v-for` — and it walks the trace to find each variable's previous value, so
            the duplicate was not free on a board of thirty nodes.
          -->
          <div
            v-if="getNodeRuntimeBadges(node).badges.length > 0"
            class="device-runtime-strip"
          >
            <span
                v-for="badge in getNodeRuntimeBadges(node).badges"
                :key="badge.label"
                class="device-runtime-chip"
                :class="{ 'device-runtime-chip--changed': badge.changed }"
                :title="badge.title"
            >
              <span class="device-runtime-chip__label">{{ badge.label }}</span>
              <!--
                The chip shows only the destination value, and that is deliberate.

                `getNodeRuntimeBadges` computes `previousValue`, `board.css` styles
                `.device-runtime-chip__previous` with a line-through, and `CanvasBoard.spec.ts` asserts the
                element must **not** render. That looked like an oversight, so I rendered the pair — and
                measurement showed the old assertion was right: `.device-runtime-chip--changed` is capped at
                `58cqmin`, which is **64px on a standard 150×110 node**. "Temperature 24 → 26" cannot fit
                there; it truncates to a fragment, which is worse than the destination value alone.

                The transition therefore belongs where there is room for it — the popover anchored to this
                node — while the node carries the destination value plus the `--changed` tint that says
                *this* is what moved. `badge.title` keeps the full `previous → current` for hover and for
                assistive technology, so the fact is never unavailable, only unprinted at 64px.
              -->
              <span class="device-runtime-chip__value">{{ badge.value }}</span>
            </span>
            <!--
              The remainder, named rather than dropped.

              The strip prints three variables because a node is 187px wide. That cap used to be silent, which
              was survivable only while the trace timeline repeated the same data with a complete
              `traceDeviceSummary`. Once the node is the authority for device state, a fourth variable that
              simply vanishes is a hole rather than a redundancy — so it says how many it is holding back, and
              the tooltip names them.
            -->
            <HintTooltip
              v-if="getNodeRuntimeBadges(node).hiddenVariableCount > 0"
              :content="getHiddenVariableNames(node)"
            >
              <span
                class="device-runtime-chip device-runtime-chip--overflow"
                :aria-label="t('app.traceVisualization.moreVariables', {
                  count: getNodeRuntimeBadges(node).hiddenVariableCount
                })"
              >+{{ getNodeRuntimeBadges(node).hiddenVariableCount }}</span>
            </HintTooltip>
          </div>
          <div v-if="getNodeSecurityBadges(node).length > 0" class="device-node-actions">
            <span
              v-for="badge in getNodeSecurityBadges(node)"
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
            aria-hidden="true"
            @pointerdown.stop="onPointerDownResize($event, node, 'tl')"
        ></div>
        <div
            v-if="!interactionLocked && canShowAllPointerResizeHandles(node)"
            class="resize-handle tr"
            aria-hidden="true"
            @pointerdown.stop="onPointerDownResize($event, node, 'tr')"
        ></div>
        <div
            v-if="!interactionLocked && canShowAllPointerResizeHandles(node)"
            class="resize-handle bl"
            aria-hidden="true"
            @pointerdown.stop="onPointerDownResize($event, node, 'bl')"
        ></div>
        <div
            v-if="!interactionLocked && canPointerResizeNode(node)"
            class="resize-handle br"
            aria-hidden="true"
            @pointerdown.stop="onPointerDownResize($event, node, 'br')"
        ></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.edge-hitarea {
  fill: none;
  stroke: transparent;
  stroke-width: 18;
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
  pointer-events: none;
  filter: drop-shadow(0 3px 8px rgba(15, 23, 42, 0.18));
}

.edge-label__bg {
  fill: color-mix(in srgb, var(--surface-elevated) 92%, transparent);
  stroke: color-mix(in srgb, var(--border) 88%, transparent);
  stroke-width: 1;
}

.edge-label__text {
  fill: var(--text);
  font-size: var(--iot-font-min);
  font-weight: 700;
  letter-spacing: 0;
}

/* Attack indicator - longer arrow with text */
.attack-indicator {
  position: absolute;
  top: -32px;
  left: 50%;
  transform: translateX(-50%);
  /* `var(--danger)` is the ink half of the role — the dark theme lightens it to #fca5a5, which put this
     badge's white label at 1.90:1. The fill half is theme-stable and solved for white ink.
     The gradient was `135deg, X 0%, X 100%`: gradient syntax with nothing to interpolate. It now deepens
     rather than lightens, so both stops clear AA (4.83:1 at the lit end, 5.36:1 at the shaded end) —
     mixing the fill toward white for a highlight had dropped the top stop to 4.22:1. */
  background: linear-gradient(160deg,
      var(--danger-fill) 0%,
      color-mix(in srgb, var(--danger-fill) 85%, #7f1d1d) 100%);
  color: #ffffff;
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
  gap: clamp(0.2rem, 2.4cqmin, 0.5rem);
  justify-self: center;
  min-width: 0;
  min-height: clamp(1.15rem, 13cqmin, 10rem);
  width: 82%;
  padding: clamp(0.18rem, 1.7cqmin, 1.5rem) clamp(0.4rem, 4cqmin, 4rem);
  border: 1px solid var(--border);
  border-radius: var(--iot-radius-pill);
  background: color-mix(in srgb, var(--surface-elevated) 86%, transparent);
  color: var(--text);
  font-weight: 700;
  z-index: 5;
  box-sizing: border-box;
  box-shadow: 0 1px 3px color-mix(in srgb, var(--text) 12%, transparent);
  line-height: 1;
  overflow: hidden;
}

.device-node--expanded .device-state {
  grid-area: state;
  justify-self: start;
  width: 100%;
}

.device-state-dot {
  width: clamp(0.35rem, 3.5cqmin, 0.75rem);
  height: clamp(0.35rem, 3.5cqmin, 0.75rem);
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
  /*
   * Static for the same reason as the node chips in `board.css`: `5cqmin` of a node whose measured height is
   * 110-137px is 5.5-6.9px, so the middle term never won and this rendered at its 10px floor. The `5rem`
   * ceiling made it look like a size that could grow.
   */
  font-size: var(--iot-font-min);
  line-height: 1;
}

.state-defined {
  border-color: color-mix(in srgb, var(--node-accent-color) 44%, var(--border));
  background: color-mix(in srgb, var(--node-accent-color) 13%, var(--surface-elevated));
}

.state-defined .device-state-dot {
  background: var(--node-accent-color);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--node-accent-color) 18%, transparent);
}

.state-stateless {
  background: color-mix(in srgb, var(--surface-muted) 88%, transparent);
  color: var(--text-muted);
}

.state-stateless .device-state-dot {
  background: var(--text-muted);
}

@media (prefers-reduced-motion: reduce) {
  .attack-indicator,
  .device-state-dot,
  .trace-state-dot {
    animation: none !important;
  }
}

.trace-info-card {
  position: absolute;
  bottom: -4px;
  left: 50%;
  transform: translateX(-50%);
  width: 90%;
  padding: 4px 6px;
  border-radius: var(--iot-radius-control);
  font-size: var(--iot-font-min);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  z-index: 10;
}

.trace-info-card.violated {
  background: linear-gradient(135deg, #fef2f2 0%, var(--danger-surface) 100%);
  border: 1px solid var(--danger-border);
}

.trace-info-card.intermediate {
  background: linear-gradient(135deg, #fefce8 0%, #fef9c3 100%);
  border: 1px solid var(--warning);
}

.trace-state-row {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 3px;
}

.trace-state-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.violated .trace-state-dot {
  background-color: var(--danger);
  box-shadow: 0 0 4px var(--danger);
  animation: pulse-red 1s infinite;
}

.intermediate .trace-state-dot {
  background-color: var(--warning);
  box-shadow: 0 0 4px var(--warning);
  animation: pulse-amber 1s infinite;
}

@keyframes pulse-red {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

@keyframes pulse-amber {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

.trace-state-label {
  color: var(--text-muted);
  font-weight: 500;
}

.trace-state-value {
  font-weight: bold;
}

.violated .trace-state-value {
  color: var(--danger);
}

.intermediate .trace-state-value {
  color: var(--warning);
}

.trace-variables-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-top: 3px;
  border-top: 1px dashed;
}

.violated .trace-variables-list {
  border-color: var(--danger-border);
}

.intermediate .trace-variables-list {
  border-color: var(--warning);
}

.trace-variable-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.trace-var-name {
  color: var(--text-muted);
  font-size: var(--iot-font-min);
}

.trace-var-value {
  font-weight: bold;
  /* Token, not a light-theme slate: `#1f2937` rendered dark-on-dark, and a trace value is the number the
     replay exists to show. */
  color: var(--text);
  font-size: var(--iot-font-min);
}

/* Device content keeps a stable footprint while trace values cross-fade. */
.device-node-content {
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto auto;
  align-items: center;
  justify-items: center;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  gap: clamp(0.15rem, 3%, 0.35rem);
  transition: transform 0.28s cubic-bezier(0.22, 1, 0.36, 1);
}

.device-top-row {
  position: relative;
}

.trace-device-icon-enter-active,
.trace-device-icon-leave-active {
  position: absolute;
  transition:
    opacity 0.24s ease,
    transform 0.38s cubic-bezier(0.22, 1, 0.36, 1),
    filter 0.3s ease;
}

.trace-device-icon-enter-from {
  opacity: 0;
  transform: translateY(5px) scale(0.9);
  filter: blur(2px);
}

.trace-device-icon-leave-to {
  opacity: 0;
  transform: translateY(-4px) scale(0.96);
  filter: blur(1px);
}

.trace-device-state-enter-active,
.trace-device-state-leave-active {
  transition: opacity 0.16s ease, transform 0.2s cubic-bezier(0.22, 1, 0.36, 1);
}

.trace-device-state-enter-from {
  opacity: 0;
  transform: translateY(3px);
}

.trace-device-state-leave-to {
  opacity: 0;
  transform: translateY(-2px);
}

@media (prefers-reduced-motion: reduce) {
  .trace-device-icon-enter-active,
  .trace-device-icon-leave-active,
  .trace-device-state-enter-active,
  .trace-device-state-leave-active {
    transition: none;
  }
}
</style>
