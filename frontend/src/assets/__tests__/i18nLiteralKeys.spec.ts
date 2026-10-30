import { readdirSync, readFileSync } from 'node:fs'
import { extname, join, relative } from 'node:path'
import { describe, expect, it } from 'vitest'
import { baseCompile } from '@intlify/message-compiler'
import { createSourceFile, forEachChild, isObjectLiteralExpression, ScriptTarget, type Node } from 'typescript'

import { i18n } from '@/assets/i18n'

const SOURCE_EXTENSIONS = new Set(['.ts', '.vue'])
const TRANSLATION_CALL = /(?<![\w$])(?:\$t|t)\(\s*(['"])([^'"\r\n]+)\1/g
// Matching only a callee named `t`/`$t` missed every key handed to an injected translator under
// another name: `traceView.ts` calls `translate('app.unknownDevice')`, so that key was absent from
// the bundle while the unit spec passed against its own stub dictionary. A dotted literal under a
// real message namespace is a key claim regardless of what consumes it, so audit the literals.
// The namespaces come from the bundle rather than a hand-kept list, so adding one to i18n.ts cannot
// leave a whole namespace unaudited.
const messageNamespaces = (): string[] =>
  Object.keys((i18n.global.messages.value as Record<string, Record<string, unknown>>)['zh-CN'])
const KEY_LITERAL = new RegExp(
  `(['"])((?:${messageNamespaces().join('|')})\\.[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*)\\1`,
  'g'
)

const sourceFiles = (directory: string): string[] => readdirSync(directory, { withFileTypes: true })
  .flatMap(entry => {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) return sourceFiles(path)
    return SOURCE_EXTENSIONS.has(extname(entry.name)) ? [path] : []
  })

const LOCALES = ['zh-CN', 'en'] as const

const messageEntries = (value: unknown, path: string[] = []): Array<[string, string]> =>
  value !== null && typeof value === 'object'
    ? Object.entries(value as Record<string, unknown>)
      .flatMap(([key, child]) => messageEntries(child, [...path, key]))
    : [[path.join('.'), String(value)]]

/** Every key the pattern claims, with the locales it fails to resolve in. */
const unresolvedKeys = (pattern: RegExp) => {
  const sourceRoot = join(process.cwd(), 'src')
  const missing: string[] = []
  let scanned = 0

  for (const file of sourceFiles(sourceRoot)) {
    if (file.endsWith('i18n.ts') || file.includes('__tests__')
        || file.endsWith('.spec.ts') || file.endsWith('.test.ts')) continue
    const content = readFileSync(file, 'utf8')
    for (const match of content.matchAll(pattern)) {
      const key = match[2]
      scanned++
      for (const locale of LOCALES) {
        if (!i18n.global.te(key, locale)) {
          missing.push(`${relative(sourceRoot, file)}: ${locale}.${key}`)
        }
      }
    }
  }

  return { missing, scanned }
}

describe('literal i18n calls', () => {
  it('use the same keys in both languages, with valid message syntax', () => {
    const messages = i18n.global.messages.value as Record<string, unknown>
    const zh = Object.fromEntries(messageEntries(messages['zh-CN']))
    const en = Object.fromEntries(messageEntries(messages.en))
    expect(Object.keys(en).sort()).toEqual(Object.keys(zh).sort())
    const errors: string[] = []
    for (const [key, text] of Object.entries(zh)) {
      for (const [locale, message] of [['zh-CN', text], ['en', en[key]]]) {
        baseCompile(message, { onError: error => { errors.push(`${locale}.${key}: ${error.message}`) } })
      }
    }
    expect(errors).toEqual([])
  })

  it('does not silently overwrite duplicate message definitions', () => {
    const source = createSourceFile('i18n.ts', readFileSync(join(process.cwd(), 'src/assets/i18n.ts'), 'utf8'), ScriptTarget.Latest, true)
    const duplicates: string[] = []
    const visit = (node: Node) => {
      if (isObjectLiteralExpression(node)) {
        const names = node.properties.filter(property => property.name).map(property => property.name!.getText(source))
        duplicates.push(...names.filter((name, index) => names.indexOf(name) !== index))
      }
      forEachChild(node, visit)
    }
    visit(source)
    expect(duplicates).toEqual([])
  })
  // Both patterns are kept because neither subsumes the other. KEY_LITERAL is anchored to namespaces
  // that exist, so it cannot see
  // `t('typo.somekey')` under a namespace that does not — which TRANSLATION_CALL reports as missing.
  it('resolve in both supported languages', () => {
    const { missing, scanned } = unresolvedKeys(TRANSLATION_CALL)

    // A scan that matches nothing asserts nothing; prove the corpus was actually walked.
    expect(scanned, 'no t()/$t() literal keys were scanned at all').toBeGreaterThan(500)
    expect(missing, `Missing literal translation keys:\n${missing.join('\n')}`).toEqual([])
  })

  it('resolve for keys passed to a renamed or injected translator', () => {
    const { missing, scanned } = unresolvedKeys(KEY_LITERAL)

    expect(scanned, 'no namespaced key literals were scanned at all').toBeGreaterThan(500)
    expect(missing, `Key literals with no translation:\n${missing.join('\n')}`).toEqual([])
  })

  // Dynamic families count by prefix; bare app.${...} lookups have a finite trust/privacy domain.
  // Lookup tables store full keys so unrelated leaf strings cannot count as product usage.
  it('are all claimed by some source file', () => {
    // Tests and coincidentally matching leaf names cannot make an unused production message necessary.
    const corpus = sourceFiles(join(process.cwd(), 'src'))
      .filter(file => !file.endsWith('i18n.ts') && !file.includes('__tests__')
        && !file.endsWith('.spec.ts') && !file.endsWith('.test.ts'))
      .map(file => readFileSync(file, 'utf8'))
      .join('\n')

    // Prefixes of dynamically constructed keys, e.g. `app.taskProgressStage_${stage}`.
    const dynamicPrefixes = [...corpus.matchAll(/[`'"]([A-Za-z][A-Za-z0-9_.]*?)\$\{/g)]
      .map(match => match[1])
      .filter(prefix => prefix.includes('.') && prefix !== 'app.')
    // `t(`app.${trust}`)` / `t(`app.${privacy}`)`: closed domains, so exactly these four.
    const bareDynamic = new Set(['app.trusted', 'app.untrusted', 'app.private', 'app.public'])

    const leafKeys = (value: unknown, path: string[] = []): string[] =>
      value !== null && typeof value === 'object'
        ? Object.entries(value as Record<string, unknown>)
          .flatMap(([key, child]) => leafKeys(child, [...path, key]))
        : [path.join('.')]

    const defined = leafKeys(
      (i18n.global.messages.value as Record<string, unknown>)['zh-CN'])
    expect(defined.length, 'the bundle should have been walked').toBeGreaterThan(1000)

    const literalKeys = new Set([...corpus.matchAll(KEY_LITERAL)].map(match => match[2]))
    const orphans = defined.filter(key =>
      !literalKeys.has(key)
      && !bareDynamic.has(key)
      && !dynamicPrefixes.some(prefix => key.startsWith(prefix))
    )

    expect(orphans, `i18n keys defined but claimed by nothing:\n${orphans.join('\n')}`).toEqual([])
  })
})
