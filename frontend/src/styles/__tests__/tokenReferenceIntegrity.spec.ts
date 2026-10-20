import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Every `var(--x)` names a token that exists, and a fallback is never what makes it work.
 *
 * `ChatMarkdown` read `var(--chat-text-muted, var(--text-muted))`. No such token is declared anywhere —
 * `ChatView` calls it `--chat-muted` — so the reference was a typo held up by its own fallback. It rendered
 * the right colour only because `--chat-muted` happens to resolve to `var(--text-muted)` as well, so nothing
 * looked wrong and nothing would have until the chat panel's muted tone diverged from the page's. That is the
 * "silent fallback turning an unknown into apparent success" the root CLAUDE.md rules out.
 *
 * Tokens that legitimately have no declaration are the ones **injected at runtime** through an inline
 * `:style` binding — for example `--node-accent-color`. For those the
 * fallback *is* the default and must stay, so this test requires them to be provably injected rather than
 * taking their absence on trust.
 */

const SRC = join(__dirname, '../..')
const SKIP = new Set(['assets', 'testing'])

const sources = () => {
  const files: Array<{ name: string, text: string }> = []
  const walk = (dir: string, prefix: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name)
      if (statSync(full).isDirectory()) {
        if (!SKIP.has(entry.name)) walk(full, `${prefix}${entry.name}/`)
      } else if (/\.(css|vue|ts)$/.test(entry.name)) {
        files.push({ name: `${prefix}${entry.name}`, text: readFileSync(full, 'utf8') })
      }
    }
  }
  walk(SRC, '')
  return files
}

const stripComments = (text: string) =>
  text
    .replace(/\/\*[\s\S]*?\*\//g, match => match.replace(/[^\n]/g, ' '))
    .replace(/^\s*\/\/.*$/gm, '')

const declaredCustomProperties = (text: string) => Array.from(
  // A CSS declaration block may also begin inside a script's template-literal style value.
  text.matchAll(/(^|[;{`])\s*(--[a-zA-Z0-9-]+)\s*:/g), match => match[2]!
)

describe('token reference integrity', () => {
  it('recognizes CSS text declarations without treating references or comments as declarations', () => {
    const text = stripComments([
      'const style = `--scale:${zoom};--lines:3`',
      'const reference = `var(--misspelled-scale)`',
      '// const unused = `--commented:value`'
    ].join('\n'))
    expect(declaredCustomProperties(text)).toEqual(['--scale', '--lines'])
  })

  it('never references a custom property that nothing declares or injects', () => {
    const files = sources().filter(f => !f.name.includes('__tests__'))

    const declared = new Set<string>()
    const injected = new Set<string>()
    for (const { text } of files) {
      const clean = stripComments(text)
      for (const token of declaredCustomProperties(clean)) declared.add(token)
      // Injected from script: `'--name':` in a style object, or built by template literal.
      for (const m of clean.matchAll(/['"](--[a-zA-Z0-9-]+)['"]\s*:/g)) injected.add(m[1])
      for (const m of clean.matchAll(/`(--[a-zA-Z0-9-]+)\$\{/g)) injected.add(m[1])
      // Literal template style declarations are not covered by object-literal patterns.
      for (const m of clean.matchAll(/style="\s*(--[a-zA-Z0-9-]+)\s*:/g)) injected.add(m[1])
    }

    const offenders: string[] = []
    for (const { name, text } of files) {
      const clean = stripComments(text)
      clean.split(/\r?\n/).forEach((line, index) => {
        for (const m of line.matchAll(/var\(\s*(--[a-zA-Z0-9-]+)/g)) {
          const token = m[1]
          if (declared.has(token) || injected.has(token)) continue
          // A template-literal prefix (`--iot-node-accent-${i}`) is injected under a computed suffix.
          if ([...injected].some(p => token.startsWith(p))) continue
          if ([...declared].some(d => d.startsWith(token))) continue
          offenders.push(`${name}:${index + 1}  ${token} is declared nowhere and injected nowhere`)
        }
      })
    }

    expect(offenders).toEqual([])
  })
})
