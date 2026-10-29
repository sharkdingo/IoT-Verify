// @vitest-environment node
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { parse } from '@vue/compiler-sfc'
import { parse as parseTemplate, type TemplateChildNode } from '@vue/compiler-dom'
import postcss from 'postcss'
import { contrast, mixColors, readThemeTokens, themeColor } from './themeTokens'

/* Fading a filled button also fades its label. Disabled states change the fill instead. */

const SRC = join(__dirname, '../..')
const SKIP = new Set(['assets', 'testing', '__tests__'])

const sources = () => {
  const files: Array<{ name: string, text: string }> = []
  const walk = (dir: string, prefix: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name)
      if (statSync(full).isDirectory()) {
        if (!SKIP.has(entry.name)) walk(full, `${prefix}${entry.name}/`)
      } else if (/\.(css|vue)$/.test(entry.name)) {
        files.push({ name: `${prefix}${entry.name}`, text: readFileSync(full, 'utf8') })
      }
    }
  }
  walk(SRC, '')
  return files
}

const stripComments = (text: string) =>
  text.replace(/\/\*[\s\S]*?\*\//g, match => match.replace(/[^\n]/g, ' '))

/** Anything that carries the disabled state other than a global fade. */
const STATE_SIGNAL = /color-mix|saturate|grayscale|background\s*:|box-shadow\s*:\s*none|border-color\s*:/
const disabledSelector = (selector: string) => selector.replace(/:not\(:disabled\)/g, '').includes(':disabled')

describe('disabled fill legibility', () => {
  it('keeps template filled actions readable and visibly disarmed', () => {
    const offenders: string[] = []
    for (const { name, text } of sources().filter(file => file.name.endsWith('.vue'))) {
      const content = parse(text).descriptor.template?.content
      if (!content) continue
      const inspect = (node: TemplateChildNode) => {
        if (node.type !== 1) return
        if (node.tag === 'button') {
          const classes = node.props.map(prop => {
            if (prop.type === 6 && prop.name === 'class') return prop.value?.content || ''
            if (prop.type === 7 && prop.name === 'bind' && prop.arg?.type === 4 && prop.arg.content === 'class') {
              return prop.exp?.type === 4 ? prop.exp.content : ''
            }
            return ''
          }).join(' ')
          if (/\btext-white\b|var\(--text-on-fill\)/.test(classes)
            && /bg-\[color:var\(--(?:accent|danger|warning|success|info)-fill/.test(classes)) {
            if (/disabled:opacity-(?!100\b)\d+/.test(classes)) {
              offenders.push(`${name}:${node.loc.start.line} fades a filled label`)
            }
            if (/disabled:bg-\[color:var\(--(?:accent|danger|warning|success|info)-fill\)\]/.test(classes)) {
              offenders.push(`${name}:${node.loc.start.line} keeps the armed fill while disabled`)
            }
          }
        }
        node.children.forEach(inspect)
      }
      parseTemplate(content).children.forEach(inspect)
    }
    expect(offenders).toEqual([])
  })

  it('keeps disabled primary actions readable inside read-only panels', () => {
    const board = postcss.parse(readFileSync(join(SRC, 'styles/board.css'), 'utf8'))
    let opacity = 1
    let fill = ''
    board.walkRules('.board-panel--interaction-read-only button:disabled', rule => {
      rule.walkDecls('opacity', decl => { opacity = Number(decl.value) })
    })
    board.walkRules('.board-action-disarmed:disabled', rule => {
      rule.walkDecls('background-color', decl => { fill = decl.value })
    })
    const token = /^var\((--[\w-]+)\)$/.exec(fill)?.[1]
    expect(token, 'disabled primary actions need a theme-aware fill').toBeDefined()
    for (const palette of ['copper', 'monochrome'] as const) {
      for (const theme of ['light', 'dark'] as const) {
        const tokens = readThemeTokens(theme, palette)
        const paper = themeColor(tokens, '--surface-elevated')
        const ink = mixColors(themeColor(tokens, '--text-on-fill'), paper, opacity)
        const ground = mixColors(themeColor(tokens, token!), paper, opacity)
        expect(contrast(ink, ground), `${palette} ${theme} read-only primary action`).toBeGreaterThanOrEqual(4.5)
      }
    }
  })

  it('never disables a role-filled control by opacity alone', () => {
    const offenders: string[] = []

    for (const { name, text } of sources()) {
      const clean = stripComments(text)
      // Split into rules once and reuse. Matching `[^{}]*:disabled[^{}]*\{` against the whole file
      // instead backtracks catastrophically on the long sources (145ms on Board.vue, 81ms on the
      // 39KB base.css — the disproportion is the tell), which is what timed this spec out in CI.
      const rules = [...clean.matchAll(/([^{}]+)\{([^{}]*)\}/g)]

      // Base classes painted with a role fill under light ink — the combination opacity ruins.
      const filled = new Set<string>()
      for (const rule of rules) {
        const body = rule[2]
        if (!/background[^;]*var\(--(?:danger|warning|success|info|accent)-fill\)/.test(body)) continue
        if (!/color\s*:\s*(#fff(fff)?|white|var\(--text-on-fill\))/i.test(body)) continue
        for (const cls of rule[1].matchAll(/\.([a-zA-Z][a-zA-Z0-9_-]*)/g)) filled.add(cls[1])
      }
      if (!filled.size) continue

      for (const rule of rules) {
        if (!disabledSelector(rule[1])) continue
        const selector = rule[1].trim().replace(/\s+/g, ' ')
        const body = rule[2]
        if (!/opacity:\s*0?\.\d/.test(body)) continue
        if (/cursor:\s*wait/.test(body)) continue
        if (STATE_SIGNAL.test(body)) continue

        // Does this `:disabled` rule reach one of the filled classes?
        const reaches = [...filled].filter(c => selector.includes(`.${c}`))
        if (!reaches.length) continue

        // A more specific sibling may supply the desaturation for the filled variant.
        // Hover's :not(:disabled) cannot supply a disabled treatment.
        const covered = reaches.some(c => rules.some(candidate =>
          disabledSelector(candidate[1])
          && [...candidate[1].matchAll(/\.([a-zA-Z][a-zA-Z0-9_-]*)/g)].some(match => match[1] === c)
          && STATE_SIGNAL.test(candidate[2])))
        if (covered) continue

        const line = clean.slice(0, rule.index).split('\n').length
        offenders.push(`${name}:${line}  ${selector} fades a role-filled control; desaturate the fill instead`)
      }
    }

    expect(offenders).toEqual([])
  })
})
