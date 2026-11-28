// src/utils/canvas/geometry.ts
import type { CanvasPan } from '@/types/canvas'

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
