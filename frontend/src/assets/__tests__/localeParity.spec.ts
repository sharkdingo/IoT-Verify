import { describe, expect, it } from 'vitest'
import { i18n } from '@/assets/i18n'

const flatten = (value: unknown, path = ''): [string, string][] =>
  typeof value === 'string' ? [[path, value]]
    : Object.entries(value as Record<string, unknown>)
      .flatMap(([key, child]) => flatten(child, path ? `${path}.${key}` : key))

describe('locale parity', () => {
  it.each(['zh-CN', 'en'] as const)('uses the same specification term in navigation and lists in %s', locale => {
    const table = new Map(flatten(i18n.global.messages.value[locale]))
    expect(table.get('app.specificationsTool')).toBe(table.get('app.specifications'))
  })

  it('uses one English name for counterexample exploration throughout the catalogue', () => {
    const { en } = locales()
    expect(en.get('app.fuzzSearch')).toBe('Counterexample Exploration')
    expect([...en].filter(([, value]) => /counterexample[- ]search/i.test(value))).toEqual([])
  })

  it('renders history counterexample counts with the appropriate English number form', () => {
    const previousLocale = i18n.global.locale.value
    i18n.global.locale.value = 'en'
    try {
      expect(i18n.global.t('app.historyCounterexamples', { count: 1 })).toBe('1 counterexample')
      expect(i18n.global.t('app.historyCounterexamples', { count: 2 })).toBe('2 counterexamples')
    } finally { i18n.global.locale.value = previousLocale }
  })

  const locales = () => {
    const messages = i18n.global.messages.value as Record<string, unknown>
    return { zh: new Map(flatten(messages['zh-CN'])), en: new Map(flatten(messages.en)) }
  }

  it('walks the complete catalogues rather than a small subset', () => {
    // A partial catalogue cannot certify locale parity.
    const { zh, en } = locales()
    expect(zh.size, 'zh-CN key count').toBeGreaterThan(800)
    expect(en.size, 'en key count').toBeGreaterThan(800)
  })

  it('defines every key in both locales', () => {
    const { zh, en } = locales()
    const missingInEn = [...zh.keys()].filter(k => !en.has(k))
    const missingInZh = [...en.keys()].filter(k => !zh.has(k))
    // Named, not counted: a reader needs to know which key to add.
    expect(missingInEn, 'keys present in zh-CN but missing from en').toEqual([])
    expect(missingInZh, 'keys present in en but missing from zh-CN').toEqual([])
  })

  it('uses the same interpolation placeholders in both locales', () => {
    const { zh, en } = locales()
    // The distinct set, because pluralization legitimately repeats a placeholder per form.
    const setOf = (v: string) =>
      [...new Set([...v.matchAll(/\{(\w+)\}/g)].map(m => m[1]))].sort().join(',')
    const mismatched: string[] = []
    for (const [key, value] of zh) {
      const other = en.get(key)
      if (other === undefined) continue
      for (const branch of other.split('|')) {
        if (setOf(value) !== setOf(branch)) {
          mismatched.push(`${key} [zh:${setOf(value) || 'none'} vs en:${setOf(branch) || 'none'}]`)
        }
      }
    }
    // A dropped placeholder means the number the sentence is about silently disappears for those users.
    expect(mismatched, 'placeholder sets should match').toEqual([])
  })

  it('never lets an inconclusive outcome borrow the vocabulary of a proof', () => {
    const { en } = locales()
    // The narrow, load-bearing rule. `BUDGET_EXHAUSTED` and `INCONCLUSIVE` mean the search or the check did not
    // decide; wording them as "safe" or "proven" would state a result the engine never reached.
    const claimsProof = /\b(safe|proven|proved|guaranteed|cannot happen|never happens)\b/i
    const offenders: string[] = []
    for (const [key, value] of en) {
      if (!/inconclusive|exhaust/i.test(key)) continue
      if (claimsProof.test(value)) offenders.push(`${key}: "${value.slice(0, 70)}"`)
    }
    expect(offenders, 'an undecided outcome must not read as a proof').toEqual([])
  })

  it('makes every history-boundary notice say why the undo history goes, in both locales', () => {
    const { en, zh } = locales()
    const boundaries = [
      { key: 'app.sceneClearConfirmMessage', en: /because.*old scene/, zh: /因为.*旧场景/ },
      { key: 'app.sceneImportConfirmMessage', en: /because.*old scene/i, zh: /因为.*旧场景/ },
      { key: 'app.resetDefaultTemplatesNotice', en: /because.*original templates/, zh: /因为.*原模板/ },
      { key: 'app.templateDeleteNoReferences', en: /because.*this template/, zh: /因为.*此模板/ }
    ]
    const offenders: string[] = []
    for (const [label, table] of [['en', en], ['zh', zh]] as const) {
      for (const boundary of boundaries) {
        const { key } = boundary
        const value = table.get(key)
        if (!value) {
          offenders.push(`${label}.${key} is missing`)
          continue
        }
        if (!value.includes('{historyEntries}')) offenders.push(`${label}.${key} omits the entry count`)
        if (!boundary[label].test(value)) offenders.push(`${label}.${key} states the count but not the reason`)
      }
    }
    expect(offenders, offenders.join('\n')).toEqual([])
  })

  it.each(['en', 'zh-CN'] as const)('scopes recovery promises to saved data and keeps rule removal reversible in %s', locale => {
    const { en, zh } = locales()
    const table = locale === 'en' ? en : zh
    for (const key of ['app.rulesToRemove', 'app.removeRulesFixTitle', 'app.confirmRemoveRulesFix']) {
      expect(table.get(key), `${locale}.${key}`).not.toMatch(/permanent|cannot be undone|永久|不可撤销/i)
    }
    expect(table.get('app.confirmRemoveRulesFix'), locale).toMatch(/undo|撤销/i)
    expect(table.get('auth.sessionExpiredNotice'), locale).toMatch(/unsaved|未保存/i)
    expect(table.get('auth.sessionExpiredNotice'), locale).not.toMatch(/nothing.*lost|内容没有丢失/i)
    expect(table.get('app.deleteAccountDataWarning'), locale).toMatch(/cannot.*recover|cannot.*undone|无法恢复/i)
  })
})
