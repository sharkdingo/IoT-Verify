import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import postcss from 'postcss'
import { NODE_ACCENT_COUNT } from '../../utils/canvas/nodePalette'
import { contrast, luminance, readThemeTokens, themeColor, mixColors } from './themeTokens'

const combinations = (['copper', 'monochrome'] as const).flatMap(palette =>
  (['light', 'dark'] as const).map(theme => ({ palette, theme })))

describe.each(combinations)('$palette $theme contrast', ({ theme, palette }) => {
  const tokens = readThemeTokens(theme, palette)
  const color = (name: string) => themeColor(tokens, `--${name}`)
  const requireContrast = (ink: string, ground: string, minimum: number) => {
    expect(contrast(color(ink), color(ground)), `${ink} on ${ground}`).toBeGreaterThanOrEqual(minimum)
  }
  const surfaces = ['surface', 'surface-muted', 'surface-elevated', 'surface-control',
    'iot-color-bg-canvas', 'accent-surface', 'danger-surface', 'warning-surface', 'success-surface']

  it('keeps ordinary and muted text readable on every shared ground', () => {
    for (const ink of ['text', 'text-muted']) {
      for (const ground of surfaces) requireContrast(ink, ground, 4.5)
    }
    requireContrast('text', 'surface-control-hover', 4.5)
  })

  it('keeps static neutral utilities readable on light controls and dark consoles', () => {
    if (theme === 'light') {
      for (const tone of [500, 600, 700]) {
        for (const ground of ['surface-elevated', 'surface-control']) {
          requireContrast(`iot-neutral-${tone}`, ground, 4.5)
        }
      }
    } else {
      requireContrast('iot-neutral-300', 'iot-neutral-900', 4.5)
      requireContrast('iot-neutral-400', 'iot-neutral-800', 4.5)
    }
  })

  it('keeps status ink readable on its own tint and on neutral controls', () => {
    for (const role of ['accent', 'danger', 'warning', 'success']) {
      for (const ground of [`${role}-surface`, 'surface-elevated', 'surface-control']) {
        requireContrast(role, ground, 4.5)
      }
      requireContrast(`${role}-border`, `${role}-surface`, 3)
    }
  })

  it('gives focus and quiet actions a visible boundary', () => {
    for (const ground of surfaces) requireContrast('accent-border', ground, 3)
    for (const ground of ['surface', 'surface-elevated', 'surface-control', 'surface-control-hover']) {
      requireContrast('control-border', ground, 3)
    }
  })

  it('keeps filled primary actions readable and distinct from their panel', () => {
    for (const fill of ['accent-fill', 'accent-fill-hover', 'accent-fill-disabled']) {
      requireContrast('text-on-fill', fill, 4.5)
      requireContrast(fill, 'surface-elevated', 3)
    }
  })

  it('keeps device tones readable on their tinted cards', () => {
    for (let tone = 0; tone < NODE_ACCENT_COUNT; tone++) {
      const accent = color(`iot-node-accent-${tone}`)
      const ground = mixColors(accent, color('surface-elevated'), Number.parseFloat(tokens['--iot-node-tint']) / 100)
      if (theme === 'light') expect(luminance(ground), `node tone ${tone} stays on bright paper`).toBeGreaterThan(0.85)
      const ink = mixColors(accent, color('text'), 0.78)
      expect(contrast(ink, ground), `node tone ${tone}`).toBeGreaterThanOrEqual(4.5)
      const line = mixColors(accent, color('iot-color-bg-canvas'), 0.82)
      expect(contrast(line, color('iot-color-bg-canvas')), `edge tone ${tone}`).toBeGreaterThanOrEqual(3)
    }
  })
})

describe.each(combinations)('$palette $theme device conflict action contrast', ({ palette, theme }) => {
  it('keeps the actual component ink readable at rest and on hover', () => {
    const source = readFileSync(join(__dirname, '../../components/DeviceDialog.vue'), 'utf8')
    const style = /<style scoped>([\s\S]*?)<\/style>/.exec(source)?.[1]
    expect(style, 'device dialog styles must be present').toBeDefined()
    const tokens = readThemeTokens(theme, palette)
    const css = postcss.parse(style!)
    for (const state of ['', ':hover']) {
      const selector = `.device-runtime-adopt-latest${state}`
      const values: Record<string, string> = {}
      css.walkRules(selector, rule => rule.walkDecls(decl => { values[decl.prop] = decl.value }))
      const paint = (property: string) => {
        const token = /^var\((--[\w-]+)\)$/.exec(values[property])?.[1]
        expect(token, `${selector} must define ${property}`).toBeDefined()
        return themeColor(tokens, token!)
      }
      expect(contrast(paint('color'), paint('background-color')), `conflict action ${state || 'rest'}`)
        .toBeGreaterThanOrEqual(4.5)
    }
  })
})


describe.each(['light', 'dark'] as const)('monochrome %s color ownership', theme => {
  it('keeps ordinary UI, node and library accent roles achromatic', () => {
    const tokens = readThemeTokens(theme, 'monochrome')
    const names = ['surface', 'surface-muted', 'surface-elevated', 'surface-control', 'surface-control-hover',
      'iot-color-bg-canvas', 'text', 'text-muted', 'accent', 'accent-fill', 'accent-fill-hover',
      'accent-fill-disabled', 'accent-surface', 'accent-border', 'control-border', 'info',
      'el-color-primary', 'el-color-primary-light-9', 'iot-node-accent-0', 'iot-node-accent-1', 'iot-node-accent-2']
    for (const name of names) {
      const hex = themeColor(tokens, '--' + name)
      const channels = [hex.slice(1, 3), hex.slice(3, 5), hex.slice(5, 7)]
      expect(new Set(channels).size, name + ' must have equal RGB channels').toBe(1)
    }
  })

  it('preserves brand and meaningful status colors across palettes', () => {
    const copper = readThemeTokens(theme, 'copper')
    const monochrome = readThemeTokens(theme, 'monochrome')
    const roles = ['brand', 'success', 'warning', 'danger']
    for (const role of roles) {
      for (const name of role === 'brand' ? [role] : [role, `${role}-surface`, `${role}-border`, `${role}-fill`]) {
        const color = themeColor(monochrome, '--' + name)
        expect(color, name).toBe(themeColor(copper, '--' + name))
        expect(new Set([color.slice(1, 3), color.slice(3, 5), color.slice(5, 7)]).size, name).toBeGreaterThan(1)
      }
    }
    expect(contrast(themeColor(monochrome, '--brand'), themeColor(monochrome, '--surface-elevated'))).toBeGreaterThanOrEqual(4.5)
  })
})

it('does not desaturate identity artwork or meaningful syntax highlighting', () => {
  const css = postcss.parse(readFileSync(join(__dirname, '../base.css'), 'utf8'))
  const desaturating: string[] = []
  css.walkDecls('filter', declaration => {
    if (/grayscale|saturate\(0\)/.test(declaration.value)) desaturating.push(declaration.toString())
  })
  expect(desaturating).toEqual([])
  const mark = readFileSync(join(__dirname, '../../components/common/ProductMark.vue'), 'utf8')
  const style = postcss.parse(/<style scoped>([\s\S]*?)<\/style>/.exec(mark)![1])
  const colors: string[] = []
  style.walkRules('.product-mark', rule => rule.walkDecls('color', declaration => { colors.push(declaration.value) }))
  expect(colors).toEqual(['var(--brand)'])
})
