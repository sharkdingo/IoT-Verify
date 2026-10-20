import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import postcss from 'postcss'
import { describe, expect, it } from 'vitest'

const css = postcss.parse(readFileSync(join(__dirname, '../board.css'), 'utf8'))
const board = readFileSync(join(__dirname, '../../views/Board.vue'), 'utf8')
const declaration = (selector: string, property: string) => {
  let value: string | undefined
  css.walkRules(selector, rule => {
    if (rule.parent?.type !== 'root') return
    rule.walkDecls(property, decl => { value = decl.value })
  })
  return value
}

describe('playback dock layout', () => {
  it('reserves the same space in the canvas and the dock without floating-card chrome', () => {
    expect(declaration('.board-timeline', 'height')).toBe('var(--board-playback-height)')
    expect(declaration('.iot-board .canvas-container', 'bottom')).toBe('var(--board-playback-height)')
    expect(declaration('.board-timeline-host', 'bottom')).toBe('0')
    expect(declaration('.board-timeline', 'border-radius')).toBe('0')
    expect(declaration('.board-timeline', 'box-shadow')).toBe('none')
    expect(declaration('.iot-board .board-playback-bar', 'left')).toBe('var(--board-control-width)')
    expect(declaration('.iot-board .board-playback-bar', 'right')).toBe('var(--board-inspector-width)')
    expect(declaration('.iot-board .board-floating-actions', 'top')).toContain('var(--board-playback-toolbar-height)')
  })

  it('allocates most of the dock to content while preserving canvas space on short screens', () => {
    const start = board.indexOf("'--board-playback-height':")
    const end = board.indexOf("'--board-playback-toolbar-height':", start)
    const heights = board.slice(start, end)
    const values = [...heights.matchAll(/clamp\(([\d.]+)rem, (\d+)dvh, (\d+)rem\)/g)]
    expect(values).toHaveLength(2)
    expect(heights).not.toContain('playbackTerminalTab')
    const dockHeight = (match: RegExpMatchArray, viewportHeight: number) =>
      Math.min(Number(match[3]) * 16, Math.max(Number(match[1]) * 16, Number(match[2]) * viewportHeight / 100))
    const shortHeight = dockHeight(values[0], 360)
    const desktopHeight = dockHeight(values[1], 900)
    // One 44px tab row leaves room for evidence; playback controls live above the canvas.
    expect(shortHeight - 44 - 1).toBeGreaterThanOrEqual(80)
    expect(shortHeight).toBeLessThanOrEqual(360 * 0.4)
    expect(desktopHeight - 44 - 1).toBeGreaterThanOrEqual(200)
    expect(desktopHeight).toBeLessThanOrEqual(900 * 0.36)
  })

  it('keeps small markers and help badges from acquiring the general control minimum', () => {
    const marker = ".playback-terminal__marker"
    expect(declaration(marker, 'width')).toBe('1.5rem')
    expect(declaration(marker, 'height')).toBe('1.5rem')
    expect(declaration(`${marker}::before`, 'width')).toBe('var(--iot-target-min)')
    expect(declaration(`${marker}::before`, 'height')).toBe('var(--iot-target-min)')
    const buttonRules: string[] = []
    css.walkRules(rule => {
      rule.walkDecls('min-height', () => {
        if (rule.selector.includes('.board-timeline button')) buttonRules.push(rule.selector)
      })
    })
    expect(buttonRules).toHaveLength(1)
    expect(buttonRules[0]).toContain(':not(.iot-info-tooltip-trigger)')
    const gapToken = declaration('.playback-terminal__run-actions', 'gap')?.match(/^var\(([^)]+)\)$/)?.[1]
    expect(gapToken).toBeDefined()
    const base = postcss.parse(readFileSync(join(__dirname, '../base.css'), 'utf8'))
    let gapValue: string | undefined
    base.walkDecls(gapToken!, decl => { gapValue = decl.value })
    // The 20px help badge extends its 44px hit area by 12px on each side.
    expect(Number.parseFloat(gapValue!) * 16).toBeGreaterThanOrEqual((44 - 20) / 2)
  })
})
