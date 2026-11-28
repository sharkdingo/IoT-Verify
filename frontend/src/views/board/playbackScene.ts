import type { DeviceEdge } from '@/types/edge'
import type { ModelPlaybackScene } from '@/types/model'
import { getLinkPoints } from '@/utils/rule'

/** Derive the historical canvas edges from the rules captured with the run. */
export const buildPlaybackEdges = (scene: ModelPlaybackScene): DeviceEdge[] => {
  const nodesById = new Map(scene.nodes.map(node => [node.id, node]))
  const edges: DeviceEdge[] = []

  scene.rules.forEach((rule, ruleIndex) => {
    const toNode = nodesById.get(rule.command.deviceName)
    if (!toNode) return
    rule.conditions.forEach((condition, sourceIndex) => {
      const fromNode = nodesById.get(condition.deviceName)
      if (!fromNode) return
      const { fromPoint, toPoint } = getLinkPoints(fromNode, toNode)
      edges.push({
        id: `playback_edge_${rule.id ?? ruleIndex}_${sourceIndex}_${fromNode.id}`,
        from: fromNode.id,
        to: toNode.id,
        fromPos: fromPoint,
        toPos: toPoint,
        fromApi: condition.attribute,
        toApi: rule.command.action,
        itemType: condition.targetType,
        relation: condition.relation || '',
        value: condition.value ?? '',
        ruleId: rule.id == null ? undefined : String(rule.id),
        ruleIndex
      })
    })
  })

  return edges
}
