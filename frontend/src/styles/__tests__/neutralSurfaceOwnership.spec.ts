// @vitest-environment node
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { parse } from '@vue/compiler-sfc'
import { parse as parseTemplate, type TemplateChildNode } from '@vue/compiler-dom'
import postcss from 'postcss'
import ts from 'typescript'
import { contrast, readThemeTokens, themeColor } from './themeTokens'

const src = join(__dirname, '../..')
const walk = (directory: string): string[] => readdirSync(directory, { withFileTypes: true })
  .flatMap(entry => entry.isDirectory() ? walk(join(directory, entry.name)) : [join(directory, entry.name)])
const files = walk(src)
const views = files.filter(file => file.endsWith('.vue'))
  .map(file => ({ file, source: readFileSync(file, 'utf8') }))
const board = postcss.parse(readFileSync(join(src, 'styles/board.css'), 'utf8'))
const componentStyle = (name: string) => postcss.parse(parse(readFileSync(join(src, name), 'utf8'))
  .descriptor.styles.map(block => block.content).join('\n'))
const declarations = (sheet: postcss.Root, selector: string) => {
  const values: Record<string, string> = {}
  sheet.walkRules(rule => {
    if (rule.selectors.includes(selector)) rule.walkDecls(decl => { values[decl.prop] = decl.value })
  })
  expect(Object.keys(values).length, selector).toBeGreaterThan(0)
  return values
}
const modes = (['copper', 'monochrome'] as const).flatMap(palette =>
  (['light', 'dark'] as const).map(theme => ({ palette, theme })))
const legacy = /(?:^|[\s'"`:])(?:bg|text|border|divide)-(?:slate|gray|zinc|neutral|stone)-\d+\b|\bbg-white\b/g

it('uses theme roles throughout nested view and component templates', () => {
  const offenders: string[] = []
  for (const { file, source } of views) {
    const template = parse(source).descriptor.template?.content || ''
    for (const [, classes] of template.matchAll(/:?class="([\s\S]*?)"/g)) {
      const hits = classes.match(legacy)
      if (hits) offenders.push(`${file}: ${hits.join(', ')}`)
    }
  }
  expect(offenders).toEqual([])
})

it('uses theme roles in dynamic class helpers and production script literals', () => {
  const scripts = files.filter(file => file.endsWith('.ts')
    && !/[\\/](?:__tests__|testing)[\\/]|\.(?:spec|test)\.ts$/.test(file))
    .map(file => ({ file, source: readFileSync(file, 'utf8') }))
  for (const { file, source } of views) {
    const { script, scriptSetup } = parse(source).descriptor
    for (const block of [script, scriptSetup]) {
      if (block) scripts.push({ file, source: block.content })
    }
  }
  const offenders: string[] = []
  for (const { file, source } of scripts) {
    const inspect = (node: ts.Node) => {
      if (ts.isStringLiteralLike(node) || ts.isTemplateLiteralToken(node)) {
        const hits = node.text.match(legacy)
        if (hits) offenders.push(`${file}: ${hits.join(', ')}`)
      }
      ts.forEachChild(node, inspect)
    }
    inspect(ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true))
  }
  expect(offenders).toEqual([])
})

it('has no class-keyed neutral theme repair in global or component styles', () => {
  const sheets = walk(join(src, 'styles')).filter(file => file.endsWith('.css'))
    .map(file => ({ file, text: readFileSync(file, 'utf8') }))
  for (const { file, source } of views) {
    for (const block of parse(source).descriptor.styles) sheets.push({ file, text: block.content })
  }
  const offenders: string[] = []
  for (const { file, text } of sheets) {
    postcss.parse(text).walkRules(rule => {
      if (/\.(?:bg|text|border|divide)-(?:slate|gray|zinc|neutral|stone)-\d+|\.bg-white/.test(rule.selector)) {
        offenders.push(`${file}: ${rule.selector}`)
      }
    })
  }
  expect(offenders).toEqual([])
})

it('keeps neutral, card and field defaults below utility state overrides', () => {
  for (const selector of ['.board-bg-elevated', '.board-bg-muted', '.board-bg-control',
    '.board-text-strong', '.board-text-muted', '.board-border-subtle', '.board-field', '.board-field:focus',
    ':where(.board-card)', '.board-card--muted', ':where(.iot-board .board-card-surface)']) {
    let found = false
    board.walkRules(selector, rule => {
      expect(rule.parent?.type).toBe('atrule')
      expect((rule.parent as postcss.AtRule).name).toBe('layer')
      expect((rule.parent as postcss.AtRule).params).toBe('components')
      found = true
    })
    expect(found, selector).toBe(true)
  }
  // An explicit ink role in the same layer must beat a card's default, regardless of rule order.
  board.walkRules(rule => {
    if (!/\.board-card(?:\)|\s|,|$)/.test(rule.selector) && !rule.selector.includes('.board-card-surface')) return
    expect(rule.selector).toMatch(/^:where\([^)]+\)$/)
  })
})

it('does not repaint native range, checkbox or radio controls as text fields', () => {
  for (const { file, source } of views) {
    const template = parse(source).descriptor.template?.content || ''
    for (const tag of template.matchAll(/<input\b[^>]*type="(?:range|checkbox|radio)"[^>]*>/g)) {
      expect(tag[0], file).not.toContain('board-field')
    }
  }
})

it('gives styled fields their own role instead of a card surface', () => {
  for (const { file, source } of views) {
    const content = parse(source).descriptor.template?.content
    if (!content) continue
    const inspect = (node: TemplateChildNode) => {
      if (node.type !== 1) return
      if (['input', 'select', 'textarea', 'FieldSelect'].includes(node.tag)) {
        const classes = node.props.find(prop => prop.type === 6 && prop.name === 'class')
        if (classes?.type === 6) expect(classes.value?.content || '', file).not.toMatch(/\bboard-(?:card|bg-)/)
      }
      node.children.forEach(inspect)
    }
    parseTemplate(content).children.forEach(inspect)
  }
})

it('keeps the selected replay marker visible on its filled step', () => {
  const template = parse(readFileSync(join(src, 'components/PlaybackTerminal.vue'), 'utf8')).descriptor.template!.content
  const selected = template.match(/<span\s+v-else-if="[^"]*selectedStateIndex"\s+class="([^"]+)"/)
  expect(selected).not.toBeNull()
  expect(declarations(board, `.${selected![1]}`).background).toBe('var(--text-on-fill)')
})

describe.each(modes)('$palette $theme nested surfaces', ({ palette, theme }) => {
  const tokens = readThemeTokens(theme, palette)
  const paint = (value: string) => {
    const token = /^var\((--[\w-]+)\)$/.exec(value)?.[1]
    expect(token, value).toBeDefined()
    return themeColor(tokens, token!)
  }

  it('keeps headings, body text and fields readable against their real role surfaces', () => {
    const strong = paint(declarations(board, '.board-text-strong').color)
    const muted = paint(declarations(board, '.board-text-muted').color)
    for (const selector of ['.board-bg-elevated', '.board-bg-muted', '.board-bg-control']) {
      const ground = paint(declarations(board, selector)['background-color'])
      expect(contrast(strong, ground), selector).toBeGreaterThanOrEqual(4.5)
      expect(contrast(muted, ground), selector).toBeGreaterThanOrEqual(4.5)
    }
    const field = declarations(board, '.board-field')
    const ground = paint(field['background-color'])
    const boundary = paint(field['border-color'])
    expect(contrast(paint(field.color), ground)).toBeGreaterThanOrEqual(4.5)
    for (const adjacent of [ground, themeColor(tokens, '--surface-elevated')]) {
      expect(contrast(boundary, adjacent)).toBeGreaterThanOrEqual(3)
      expect(contrast(paint(declarations(board, '.board-field:focus')['border-color']), adjacent)).toBeGreaterThanOrEqual(3)
    }
  })

  it('keeps progress copy readable and template counts neutral', () => {
    const progress = componentStyle('components/RecommendationProgressStatus.vue')
    const box = declarations(progress, '.recommendation-progress')
    expect(contrast(paint(box.color), paint(box.background))).toBeGreaterThanOrEqual(4.5)
    const heading = declarations(progress, '.recommendation-progress__heading')
    expect(contrast(paint(heading.color), paint(box.background))).toBeGreaterThanOrEqual(4.5)
    const count = declarations(componentStyle('components/ControlCenter.vue'), '.template-group__count')
    expect(count.background).toBe('var(--surface-control)')
    expect(count.color).toBe('var(--text-muted)')
    expect(contrast(paint(count.color), paint(count.background))).toBeGreaterThanOrEqual(4.5)
  })

  it('keeps the switch thumb distinct from off and semantic on tracks', () => {
    const sheet = componentStyle('components/common/ToggleSwitch.vue')
    const off = declarations(sheet, '.iot-toggle-switch::before')
    const thumb = declarations(sheet, '.iot-toggle-switch__thumb')
    expect(contrast(paint(thumb.background), paint(off.background))).toBeGreaterThanOrEqual(3)
    const onThumb = declarations(sheet, '.iot-toggle-switch--on .iot-toggle-switch__thumb')
    for (const tone of ['enabled', 'adversarial', 'sensitivity']) {
      const on = declarations(sheet, `.iot-toggle-switch--on.iot-toggle-switch--${tone}::before`)
      expect(contrast(paint(onThumb.background), paint(on.background)), tone).toBeGreaterThanOrEqual(3)
    }
    const disabled = declarations(sheet, '.iot-toggle-switch--on:disabled::before')
    expect(contrast(paint(onThumb.background), paint(disabled.background))).toBeGreaterThanOrEqual(3)
  })

  it('keeps the disabled counterexample replay label readable', () => {
    const source = readFileSync(join(src, 'views/Board.vue'), 'utf8')
    const at = source.indexOf('data-testid="verification-trace-replay"')
    expect(at).toBeGreaterThan(-1)
    const button = source.slice(source.lastIndexOf('<button', at), source.indexOf('</button>', at))
    expect(button).toContain(':disabled="simulationAnimationState.visible"')
    expect(button).toContain('iot-dialog-btn iot-dialog-btn--primary')
    const dialog = postcss.parse(readFileSync(join(src, 'styles/dialog.css'), 'utf8'))
    const disabled = declarations(dialog, '.iot-dialog-btn--primary:disabled')
    expect(disabled.color).toBe('#fff')
    expect(contrast('#ffffff', paint(disabled.background))).toBeGreaterThanOrEqual(4.5)
  })
})
