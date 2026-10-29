// @vitest-environment node
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { readThemeTokens, themeColor, luminance, contrast } from './themeTokens'

/* Muted controls need a stronger hover ink because the hover ground is darker in light mode. */

const DIRS = ['components', 'components/common', 'views']

const sources = () => {
  const files: Array<{ name: string, text: string }> = []
  for (const dir of DIRS) {
    const full = join(__dirname, '../..', dir)
    for (const entry of readdirSync(full, { withFileTypes: true })) {
      if (!entry.isFile() || !entry.name.endsWith('.vue')) continue
      files.push({ name: `${dir}/${entry.name}`, text: readFileSync(join(full, entry.name), 'utf8') })
    }
  }
  return files
}

const boardCss = () => readFileSync(join(__dirname, '..', 'board.css'), 'utf8')

/** Blank comments in place so reported line numbers stay true (see `neutralTextContrast`). */
const blankComments = (lines: string[]) => {
  const out: string[] = []
  let inBlock = false
  for (const raw of lines) {
    let line = raw
    if (inBlock) {
      const close = line.indexOf('-->') >= 0 ? line.indexOf('-->') + 3
        : (line.indexOf('*/') >= 0 ? line.indexOf('*/') + 2 : -1)
      if (close < 0) { out.push(''); continue }
      line = ' '.repeat(close) + line.slice(close)
      inBlock = false
    }
    line = line.replace(/<!--[\s\S]*?-->/g, m => ' '.repeat(m.length))
      .replace(/\/\*[\s\S]*?\*\//g, m => ' '.repeat(m.length))
    const open = Math.max(line.lastIndexOf('<!--'), line.lastIndexOf('/*'))
    if (open >= 0) { line = line.slice(0, open); inBlock = true }
    out.push(line)
  }
  return out
}

describe('neutral hover feedback', () => {
  it('never reaches for a layered slate utility as a board hover ground', () => {
    const offenders: string[] = []
    for (const { name, text } of sources()) {
      blankComments(text.split('\n')).forEach((line, index) => {
        for (const match of line.matchAll(/(^|[\s"'`:])hover:bg-(slate|gray|zinc)-(50|100|200)\b/g)) {
          // A `dark:`-prefixed occurrence is a different declaration; only the bare one is at issue.
          if (match[1] === ':') continue
          if (/dark:hover:bg-(slate|gray|zinc)-\d+/.test(line)) continue
          offenders.push(`${name}:${index + 1}  ${line.trim().slice(0, 100)}`)
        }
      })
    }
    expect(offenders).toEqual([])
  })

  it('defines shared hover roles outside workspace-specific scopes without theme patches', () => {
    const css = boardCss().replace(/\/\*[\s\S]*?\*\//g, '')
    for (const [selector, token] of [
      ['hover\\:board-control-hover:hover', '--surface-control-hover'],
      ['hover\\:board-text-strong:hover', '--text']
    ]) {
      const rule = css.split('\n').find(line => line.startsWith('.' + selector + ' {'))
      expect(rule, selector).toBeDefined()
      expect(rule).toContain('var(' + token + ')')
      expect(rule).not.toContain('!important')
    }
  })

  it('deepens light hover, brightens dark hover and keeps their text readable', () => {
    for (const theme of ['light', 'dark'] as const) {
      const tokens = readThemeTokens(theme)
      const ground = themeColor(tokens, '--surface-control')
      const hover = themeColor(tokens, '--surface-control-hover')
      const ink = themeColor(tokens, '--text')
      if (theme === 'dark') {
        expect(luminance(hover)).toBeGreaterThan(luminance(ground))
        expect(contrast(themeColor(tokens, '--text-muted'), hover)).toBeGreaterThanOrEqual(4.5)
      } else {
        expect(luminance(hover)).toBeLessThan(luminance(ground))
      }
      expect(contrast(ink, hover)).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('pairs a muted resting ink with a strengthened hover ink', () => {
    // The light ground leaves `--text-muted` at 4.23:1, so a control whose rest ink is muted must also
    // brighten its ink — otherwise the migration trades an invisible hover for an unreadable one.
    //
    // Scoped to controls that actually carry text. SC 1.4.3 does not apply to a decorative glyph, and
    // ControlCenter's two panel-toggle tiles hold nothing but an `aria-hidden` icon whose own colour is
    // set on the child — so the parent's hover ink could not reach it even if it were required. Both
    // were flagged by this rule's first version, which is what identified the exemption.
    const offenders: string[] = []
    for (const { name, text } of sources()) {
      const lines = blankComments(text.split('\n'))
      lines.forEach((line, index) => {
        if (!line.includes('hover:board-control-hover')) return
        const restsMuted = /\bboard-text-muted\b/.test(line) || /\bboard-chip-neutral\b/.test(line)
        if (!restsMuted) return
        if (/hover:board-text-(strong|info|danger|warning)\b/.test(line)) return
        // An icon-only control: within the element's own markup there is an `aria-hidden` glyph and
        // *no* text node at all. Scanning to the closing tag rather than a fixed line window, because
        // the Play button's label sits several lines below its glyph — a fixed window that stopped
        // short exempted it, and this rule then stayed green when its ink half was deleted.
        const body = (() => {
          const rest = lines.slice(index).join('\n')
          // Start after the opening tag closes. Starting at the class attribute leaves the remaining
          // attributes in the slice, and stripping tags then turns `:aria-label="t('app.collapse')"`
          // into bare text that reads as a visible label — which flagged two icon-only tiles.
          const open = rest.indexOf('>')
          const inner = open < 0 ? rest : rest.slice(open + 1)
          const end = inner.search(/<\/(button|summary|a|label)>/)
          return end < 0 ? inner.slice(0, 600) : inner.slice(0, end)
        })()
        // Any interpolation or literal text outside an aria-hidden span counts as a label.
        const withoutGlyphs = body.replace(/<span[^>]*aria-hidden="true"[^>]*>[\s\S]*?<\/span>/g, '')
        const hasText = /\{\{/.test(withoutGlyphs) || /^\s*[A-Za-z0-9]/m.test(withoutGlyphs.replace(/<[^>]*>/g, ''))
        if (!hasText) return
        offenders.push(`${name}:${index + 1}  ${line.trim().slice(0, 100)}`)
      })
    }
    expect(offenders).toEqual([])
  })
})
