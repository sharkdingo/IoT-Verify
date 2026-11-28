import { describe, expect, it } from 'vitest'

import type { DeviceNode } from '@/types/node'
import {
  beginNodeDrag,
  cancelNodeDrag,
  createNodeDragState,
  endNodeDrag,
  updateNodeDrag
} from '../nodeDrag'

const node = (): DeviceNode => ({
  id: 'node-1',
  templateName: 'Light',
  label: 'Hall light',
  state: 'off',
  position: { x: 40, y: 60 },
  width: 176,
  height: 128
})

describe('node drag cancellation', () => {
  it('restores the original position when pointer input is cancelled', () => {
    const target = node()
    const state = createNodeDragState()
    beginNodeDrag({ clientX: 100, clientY: 80 } as PointerEvent, target, state)

    updateNodeDrag({ clientX: 160, clientY: 140 } as PointerEvent, state, 2)
    expect(target.position).toEqual({ x: 40, y: 60 })
    expect(state.tempPosition).toEqual({ x: 70, y: 90 })

    expect(cancelNodeDrag(state)).toBe(target)
    // Position remains unchanged after cancel
    expect(target.position).toEqual({ x: 40, y: 60 })
    expect(state.node).toBeNull()
  })

  it('commits the temporary position when drag ends', () => {
    const target = node()
    const state = createNodeDragState()
    beginNodeDrag({ clientX: 100, clientY: 80 } as PointerEvent, target, state)

    updateNodeDrag({ clientX: 160, clientY: 140 } as PointerEvent, state, 2)
    // Position unchanged during drag
    expect(target.position).toEqual({ x: 40, y: 60 })

    expect(endNodeDrag(state)).toBe(target)
    // Position is committed after drag ends
    expect(target.position).toEqual({ x: 70, y: 90 })
    expect(state.node).toBeNull()
  })
})
