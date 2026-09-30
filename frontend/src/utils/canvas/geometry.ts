// src/utils/canvas/geometry.ts
import type { DeviceNode } from '@/types/node.ts'
import type { DeviceEdge } from '@/types/edge.ts'
import type { CanvasPan } from '@/types/canvas'
import { getLinkPoints } from '../rule'

/**
 * 当某个节点移动/缩放后，重新计算与之连接的所有边的端点位置。
 */
export const updateEdgesForNode = (
    nodeId: string,
    nodes: DeviceNode[],
    edges: DeviceEdge[]
) => {
    const moved = nodes.find(n => n.id === nodeId)
    if (!moved) return

    // 构建节点查找映射，避免重复遍历
    const nodeMap = new Map<string, DeviceNode>()
    for (const node of nodes) {
        nodeMap.set(node.id, node)
    }

    edges.forEach(edge => {
        if (edge.from !== nodeId && edge.to !== nodeId) return

        const fromNode = nodeMap.get(edge.from)
        const toNode = nodeMap.get(edge.to)
        if (!fromNode || !toNode) return

        // 特殊：自环
        if (fromNode.id === toNode.id) {
            edge.fromPos = { x: fromNode.position.x, y: fromNode.position.y }
            edge.toPos = { x: fromNode.position.x, y: fromNode.position.y }
            return
        }

        const { fromPoint, toPoint } = getLinkPoints(fromNode, toNode)
        edge.fromPos = fromPoint
        edge.toPos = toPoint
    })
}

/**
 * Convert a canvas-relative screen point into world (unzoomed, unpanned) coordinates.
 *
 * The canvas renders its nodes under a `translate(pan) scale(zoom)` transform, so any pointer
 * or layout position measured against the canvas element must be un-transformed before it can
 * be stored as a node position. Callers that measure against the viewport should subtract the
 * canvas bounding rect first.
 */
export const screenToWorld = (
    screenX: number,
    screenY: number,
    pan: CanvasPan,
    zoom: number
): { x: number; y: number } => ({
    x: (screenX - pan.x) / zoom,
    y: (screenY - pan.y) / zoom
})
