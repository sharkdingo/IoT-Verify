// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { screenToWorld } from '../canvas/geometry'

// The canvas renders nodes under `translate(pan) scale(zoom)`, so a pointer position measured
// against the canvas element must be un-transformed before it becomes a stored node position.
// Getting this wrong drops nodes at visibly wrong places once the user has panned or zoomed.
describe('screenToWorld', () => {
  it('is the identity at the default viewport', () => {
    expect(screenToWorld(120, 80, { x: 0, y: 0 }, 1)).toEqual({ x: 120, y: 80 })
  })

  it('subtracts pan before dividing by zoom', () => {
    // A point 100px right of a pan of 40 is 60 screen px into the canvas; at 2x that is 30 world px.
    expect(screenToWorld(100, 140, { x: 40, y: 20 }, 2)).toEqual({ x: 30, y: 60 })
  })

  it('scales up when zoomed out', () => {
    expect(screenToWorld(50, 25, { x: 0, y: 0 }, 0.5)).toEqual({ x: 100, y: 50 })
  })

  it('handles negative pan (canvas scrolled past the origin)', () => {
    expect(screenToWorld(0, 0, { x: -60, y: -30 }, 1)).toEqual({ x: 60, y: 30 })
  })

  it('round-trips with the inverse transform', () => {
    const pan = { x: 37, y: -12 }
    const zoom = 1.25
    const world = screenToWorld(200, 150, pan, zoom)
    expect(world.x * zoom + pan.x).toBeCloseTo(200)
    expect(world.y * zoom + pan.y).toBeCloseTo(150)
  })
})
