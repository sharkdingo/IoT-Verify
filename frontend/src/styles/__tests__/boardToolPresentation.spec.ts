import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { parse } from '@vue/compiler-sfc'
import postcss from 'postcss'
import { contrast, readThemeTokens, themeColor } from './themeTokens'

const src = join(__dirname, '../..')
const css = postcss.parse(readFileSync(join(src, 'styles/board.css'), 'utf8'))
const templates = ['views/Board.vue', 'components/FuzzingPanel.vue', 'components/TraceHistoryPanel.vue']
  .map(file => parse(readFileSync(join(src, file), 'utf8')).descriptor.template!.content)
const combinations = (['copper', 'monochrome'] as const).flatMap(palette =>
  (['light', 'dark'] as const).map(theme => ({ palette, theme })))
const originalTheme = document.documentElement.getAttribute('data-theme')
const originalPalette = document.documentElement.getAttribute('data-palette')
const originalDark = document.documentElement.classList.contains('dark')

afterEach(() => {
  document.body.replaceChildren()
  for (const [name, value] of [['data-theme', originalTheme], ['data-palette', originalPalette]]) {
    if (value === null) document.documentElement.removeAttribute(name!)
    else document.documentElement.setAttribute(name!, value!)
  }
  document.documentElement.classList.toggle('dark', originalDark)
})

const declarations = (selector: string): Record<string, string> => {
  const values: Record<string, string> = {}
  css.walkRules(selector, rule => rule.walkDecls(declaration => { values[declaration.prop] = declaration.value }))
  expect(Object.keys(values).length, selector).toBeGreaterThan(0)
  return values
}

describe.each(combinations)('$palette $theme workspace tools', ({ palette, theme }) => {
  const tokens = readThemeTokens(theme, palette)
  css.walkRules(rule => {
    if (!rule.selectors.includes(':root')) return
    rule.walkDecls(declaration => {
      if (declaration.prop.startsWith('--')) tokens[declaration.prop] = declaration.value
    })
  })
  const paint = (value: string) => {
    const token = /var\((--[\w-]+)\)/.exec(value)?.[1]
    if (token) return themeColor(tokens, token)
    if (value === '#fff' || value === 'white') return '#ffffff'
    expect(value).toMatch(/^#[\da-f]{6}$/i)
    return value
  }

  it('keeps every actual workflow heading and subtitle readable through all matching style rules', () => {
    document.documentElement.setAttribute('data-theme', theme)
    document.documentElement.setAttribute('data-palette', palette)
    document.documentElement.classList.toggle('dark', theme === 'dark')
    const host = document.createElement('div')
    host.className = 'iot-board'
    host.innerHTML = templates.join('\n')
    document.body.append(host)
    const headers = host.querySelectorAll<HTMLElement>('.board-tool-header')
    expect(headers.length).toBeGreaterThanOrEqual(4)
    const ground = paint(declarations('.iot-board .board-tool-header').background)
    for (const header of headers) {
      const heading = header.querySelector('h3')
      const subtitle = header.querySelector('p')
      expect(heading, header.outerHTML.slice(0, 300)).not.toBeNull()
      if (heading!.id === 'fuzzing-panel-title') {
        const caveat = header.querySelector('[test-id="fuzz-intro-help"]')
        expect(caveat?.getAttribute(':text'), 'candidate exploration semantics').toBe("t('app.fuzzSearchSubtitle')")
        expect(caveat?.getAttribute(':label'), 'accessible exploration help').toContain('app.showHelpFor')
      }
      for (const element of [heading!, ...(subtitle ? [subtitle] : [])]) {
        const inks: string[] = []
        css.walkRules(rule => {
          const colors = rule.nodes.filter(node => node.type === 'decl' && node.prop === 'color')
          if (!colors.length) return
          if (!rule.selectors.some(selector => {
            // Pseudo-elements cannot set their owner's ink; jsdom rejects ::marker.
            if (selector.includes('::')) return false
            // jsdom evaluates unrelated :has queries against fractional utility class names incorrectly.
            const subject = selector.replace(/:has\([^()]*\)$/, '')
            if (subject !== selector && !element.matches(subject)) return false
            return element.matches(selector)
          })) return
          for (const declaration of colors) {
            if (declaration.type === 'decl') inks.push(declaration.value)
          }
        })
        expect(inks.length, element.id || element.textContent || 'header text').toBeGreaterThan(0)
        for (const ink of inks) {
          expect(contrast(paint(ink), ground), `${element.id || 'subtitle'}: ${ink}`)
            .toBeGreaterThanOrEqual(4.5)
        }
      }
    }
  })

  it('keeps the shared controls legible and bounded at rest, on hover and when open', () => {
    const base = declarations('.iot-board .board-tool-button')
    for (const state of ['', ':hover:not(:disabled)', "[aria-pressed='true']"]) {
      const values = state ? { ...base, ...declarations('.iot-board .board-tool-button' + state) } : base
      const ground = paint(values['background-color'])
      const boundary = paint(values['border-color'] || values.border)
      expect(contrast(paint(values.color), ground), state || 'rest').toBeGreaterThanOrEqual(4.5)
      expect(contrast(boundary, ground), state || 'rest').toBeGreaterThanOrEqual(3)
      expect(contrast(boundary, themeColor(tokens, '--surface-elevated'))).toBeGreaterThanOrEqual(3)
      expect(values['box-shadow']).toBe('none')
    }
    const focus = declarations('.iot-board .board-tool-button:focus-visible')
    expect(contrast(paint(focus.outline), themeColor(tokens, '--surface-elevated'))).toBeGreaterThanOrEqual(3)
  })
})

it('uses one quiet surface and a separate open-state marker for every toolbar entry', () => {
  const host = document.createElement('div')
  host.innerHTML = templates[0]
  const buttons = host.querySelectorAll('.board-action-dock .board-tool-button')
  expect(buttons).toHaveLength(4)
  for (const button of buttons) {
    expect(button.className).not.toMatch(/board-tool-button--|\bbg-|\bshadow-/)
    expect(button.hasAttribute('style') || button.hasAttribute(':style')).toBe(false)
    expect(button.hasAttribute(':class')).toBe(false)
    expect(button.hasAttribute(':aria-pressed')).toBe(true)
  }
  expect(declarations('.iot-board .board-tool-button')['background-color']).toBe('var(--board-card-bg)')
  expect(declarations('.iot-board .board-tool-button::before').opacity).toBe('0')
  const marker = declarations(".iot-board .board-tool-button[aria-pressed='true']::before")
  expect(marker.opacity).toBe('1')
  expect(marker.transform).toBe('translateY(-50%) scaleY(1)')
  css.walkRules(rule => {
    expect(rule.selector).not.toMatch(/\.board-tool-button--(?:primary|evidence|view|suggestion)/)
  })
})

it('preserves the open marker while suppressing toolbar movement under reduced motion', () => {
  const selectors = new Map<string, Record<string, string>>()
  css.walkAtRules('media', rule => {
    if (!rule.params.includes('prefers-reduced-motion: reduce')) return
    rule.walkRules(rule => {
      for (const selector of rule.selectors) {
        const values = selectors.get(selector) || {}
        rule.walkDecls(declaration => { values[declaration.prop] = declaration.value })
        selectors.set(selector, values)
      }
    })
  })
  for (const selector of ['.iot-board .board-tool-button', '.iot-board .board-tool-button::before']) {
    expect(selectors.get(selector)?.transition).toBe('none')
  }
  expect(selectors.get('.iot-board .board-tool-button:active:not(:disabled)')?.transform).toBe('none')
  expect(declarations(".iot-board .board-tool-button[aria-pressed='true']::before").opacity).toBe('1')
})
