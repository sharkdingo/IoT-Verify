export const NODE_ACCENT_COUNT = 3

export const getNodeColorIndex = (nodeId: string): number => {
  let hash = 5381
  for (let index = 0; index < nodeId.length; index += 1) {
    hash = ((hash << 5) + hash) + nodeId.charCodeAt(index)
  }
  return Math.abs(hash) % NODE_ACCENT_COUNT
}

export const getNodeAccentColor = (nodeId: string): string =>
  `var(--iot-node-accent-${getNodeColorIndex(nodeId)})`

export const getNodeSurfaceColor = (nodeId: string): string =>
  `color-mix(in srgb, ${getNodeAccentColor(nodeId)} var(--iot-node-tint), var(--surface-elevated))`

export const getNodeBorderColor = (nodeId: string): string =>
  `color-mix(in srgb, ${getNodeAccentColor(nodeId)} 78%, var(--text))`
