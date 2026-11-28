import type { RuleSourceItemType } from './rule'

export interface DeviceEdge {
    id: string
    from: string
    to: string
    fromPos: { x: number; y: number }
    toPos: { x: number; y: number }
    // Rule-derived display metadata. DeviceEdge is a canvas display shape; visible
    // rule connections are derived from persisted rules instead of a backend edge DTO.
    fromApi?: string
    toApi?: string
    itemType?: RuleSourceItemType
    relation?: string
    value?: string
    ruleId?: string
    ruleIndex?: number
}
