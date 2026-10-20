import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import postcss from 'postcss'
import { readThemeTokens, themeColor, luminance } from './themeTokens'

// Count text must remain readable in both themes, independently of the tab's visual treatment.

const inspector = () => readFileSync(join(__dirname, '../../components/SystemInspector.vue'), 'utf8')

describe('badge contrast', () => {
  const withoutComments = (text: string) => text.replace(/<!--[\s\S]*?-->/g, '')
  const css = postcss.parse(readFileSync(join(__dirname, '../board.css'), 'utf8'))
  const declarations = (selector: string) => {
    const values: Record<string, string> = {}
    css.walkRules(selector, rule => {
      rule.walkDecls(decl => { values[decl.prop] = decl.value })
    })
    expect(Object.keys(values).length, `missing ${selector}`).toBeGreaterThan(0)
    return values
  }
  it.each(['light', 'dark'] as const)('keeps selected and unselected counts above 4.5:1 in %s', theme => {
    const tokens = readThemeTokens(theme)
    const badge = declarations('.iot-board .board-section-tab__count')
    const selected = declarations(".iot-board .board-section-tab[aria-selected='true'] .board-section-tab__count")
    for (const values of [badge, { ...badge, ...selected }]) {
      const color = (property: string) => {
        const token = /^var\((--[\w-]+)\)$/.exec(values[property])
        expect(token, `${property} should follow the theme`).not.toBeNull()
        return luminance(themeColor(tokens, token![1]))
      }
      const ink = color('color')
      const paper = color('background')
      expect((Math.max(ink, paper) + 0.05) / (Math.min(ink, paper) + 0.05)).toBeGreaterThanOrEqual(4.5)
    }
    expect(withoutComments(inspector())).toContain('class="board-section-tab__count"')
  })

  it('never reintroduces a translucent white fill under white text anywhere in the panel', () => {
    // The sibling sweep. `bg-white/N` paired with `text-white` is the shape of the defect, wherever it appears —
    // scanning the whole file keeps a second instance from being added elsewhere and going unnoticed.
    const source = withoutComments(inspector())
    const offenders: string[] = []
    source.split('\n').forEach((line, index) => {
      if (/bg-white\/\d+/.test(line) && /text-white/.test(line)) {
        offenders.push(`SystemInspector.vue:${index + 1}`)
      }
    })
    expect(offenders, 'white text on a translucent white fill cannot reach 4.5:1 over a mid-tone accent')
      .toEqual([])
  })
})
