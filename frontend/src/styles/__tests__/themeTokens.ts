import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import postcss from 'postcss'

export const readThemeTokens = (theme: 'light' | 'dark', palette: 'copper' | 'monochrome' = 'copper'): Record<string, string> => {
  const tokens: Record<string, string> = {}
  const selectors = new Set([':root', ':root[data-theme]', `:root[data-theme='${theme}']`])
  if (theme === 'dark') selectors.add(':root:not([data-theme])')
  if (palette === 'monochrome') {
    selectors.add(":root[data-palette='monochrome']")
    selectors.add(`:root[data-palette='monochrome'][data-theme='${theme}']`)
    if (theme === 'dark') selectors.add(":root[data-palette='monochrome']:not([data-theme])")
  }
  postcss.parse(readFileSync(join(__dirname, '../base.css'), 'utf8')).walkRules(rule => {
    if (rule.selector.split(',').some(selector => selectors.has(selector.trim()))) {
      rule.walkDecls(decl => { if (decl.prop.startsWith('--')) tokens[decl.prop] = decl.value })
    }
  })
  return tokens
}

export const themeColor = (tokens: Record<string, string>, name: string): string => {
  let value = tokens[name]
  const visited = new Set<string>([name])
  while (value?.startsWith('var(')) {
    const alias = /^var\((--[\w-]+)\)$/.exec(value)?.[1]
    if (!alias || visited.has(alias)) throw new Error(`Invalid color alias: ${name} → ${value}`)
    visited.add(alias)
    value = tokens[alias]
  }
  if (!/^#[a-f\d]{6}$/i.test(value ?? '')) throw new Error(`Unresolved color: ${name} → ${value}`)
  return value
}

const channels = (hex: string) => [1, 3, 5].map(at => Number.parseInt(hex.slice(at, at + 2), 16))

export const luminance = (hex: string): number => channels(hex)
  .map(channel => channel / 255)
  .map(channel => channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
  .reduce((sum, channel, index) => sum + channel * [0.2126, 0.7152, 0.0722][index], 0)

export const contrast = (a: string, b: string): number => {
  const [low, high] = [luminance(a), luminance(b)].sort((x, y) => x - y)
  return (high + 0.05) / (low + 0.05)
}

export const mixColors = (a: string, b: string, amount: number): string => {
  const background = channels(b)
  return '#' + channels(a).map((channel, index) =>
    Math.round(channel * amount + background[index] * (1 - amount)).toString(16).padStart(2, '0'),
  ).join('')
}
