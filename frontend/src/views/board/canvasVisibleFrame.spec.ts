import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { transpileModule, ScriptTarget } from 'typescript'
import { afterEach, describe, expect, it } from 'vitest'

const board = readFileSync(join(__dirname, '../Board.vue'), 'utf8')
const start = board.indexOf('const getCanvasTopInset =')
const end = board.indexOf('const getVisibleCanvasCenterWorld =', start)
if (start < 0 || end < 0) throw new Error('Canvas frame functions are missing')
// Execute the owning functions; mounting the whole workspace would mock unrelated server state.
const readFrame = new Function('document', 'getComputedStyle', 'window', 'boardPanels',
  'effectiveControlPanelWidth', 'effectiveInspectorPanelWidth', 'actionDockReservedWidth',
  'COLLAPSED_PANEL_RAIL_PX', 'isNarrowBoardLayout',
  transpileModule(`${board.slice(start, end)}\nreturn getVisibleCanvasFrame()`, {
    compilerOptions: { target: ScriptTarget.ES2022 }
  }).outputText)

afterEach(() => { document.body.innerHTML = '' })

const visibleFrame = (overlayHeight?: number, collapsed = false, viewport = { width: 1440, height: 900 },
  openNarrowPanel?: 'control' | 'inspector') => {
  const canvas = document.createElement('div')
  canvas.className = 'canvas'
  canvas.style.paddingTop = '69px'
  const container = document.createElement('div')
  container.className = 'canvas-container'
  // The CSS layout has already removed the bottom dock from this rectangle.
  container.getBoundingClientRect = () => ({ ...viewport, height: viewport.height - (overlayHeight ?? 0) }) as DOMRect
  container.append(canvas)
  document.body.append(container)
  if (overlayHeight !== undefined) {
    const overlay = document.createElement('div')
    overlay.className = 'board-timeline-host'
    overlay.getBoundingClientRect = () => ({ height: overlayHeight }) as DOMRect
    document.body.append(overlay)
  }
  return readFrame(document, getComputedStyle, { innerHeight: viewport.height },
    { control: { collapsed: collapsed && openNarrowPanel !== 'control' },
      inspector: { collapsed: collapsed && openNarrowPanel !== 'inspector' } },
    { value: 320 }, { value: 320 }, { value: 72 }, 48,
    { value: viewport.width < 1024 || viewport.height < 600 })
}

describe('canvas visible frame', () => {
  it('reserves navigation from the shared world origin and excludes both panels and the dock', () => {
    expect(visibleFrame()).toEqual({ left: 320, top: 69, width: 728, height: 807 })
  })

  it.each([120, 224])('uses a canvas shortened by the %spx dock without reserving it twice', height => {
    expect(visibleFrame(height).height).toBe(900 - 69 - height - 24)
  })

  it('restores the complete canvas frame when the dock is hidden', () => {
    expect(visibleFrame(0).height).toBe(900 - 69 - 24)
  })

  it('reserves only the two rails when panels are collapsed', () => {
    expect(visibleFrame(undefined, true)).toEqual({ left: 48, top: 69, width: 1272, height: 807 })
  })

  it('preserves a small real viewport instead of inventing a larger available frame', () => {
    expect(visibleFrame(undefined, false, { width: 900, height: 240 }))
      .toEqual({ left: 48, top: 69, width: 732, height: 147 })
  })

  it.each(['control', 'inspector'] as const)('keeps map navigation usable while the narrow %s overlay is open', panel => {
    const viewport = { width: 375, height: 500 }
    const frame = visibleFrame(undefined, true, viewport, panel)
    expect(frame).not.toBeNull()
    expect(frame).toEqual(visibleFrame(undefined, true, viewport))
  })

  it('returns no frame when the actual stage has no usable space', () => {
    expect(visibleFrame(undefined, false, { width: 160, height: 900 })).toBeNull()
    expect(visibleFrame(384, false, { width: 1440, height: 400 })).toBeNull()
  })
})
