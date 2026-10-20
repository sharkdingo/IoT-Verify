import { describe, expect, it } from 'vitest'
import { readdirSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { i18n } from '@/assets/i18n'
import {
  formatBuiltInModelToken,
  formatModelTokenBySource,
  formatModelTokenForTemplate
} from '@/utils/modelTokenDisplay'

const translations: Record<string, string> = {
  'app.modelTokens.workingState': '工作状态',
  'app.modelTokens.temperature': '温度',
  'app.modelTokens.state': '状态',
  'app.modelTokens.photo': '照片',
  'app.modelTokens.content': '内容',
  'app.modelTokens.off': '关闭',
  'app.modelTokens.locked': '已锁定',
  'app.modelTokens.idle': '空闲'
}

const t = (key: string) => translations[key] || key

type JsonRecord = Record<string, unknown>

const asRecord = (value: unknown): JsonRecord | null =>
  value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as JsonRecord
    : null

const bundledManifestTokens = (): string[] => {
  const directory = resolve(process.cwd(), '../backend/src/main/resources/deviceTemplate')
  const tokens = new Set<string>(['workingState'])
  const add = (value: unknown) => {
    if (Array.isArray(value)) {
      value.forEach(add)
      return
    }
    if (typeof value !== 'string') return
    value.split(/[;,|]/).map(segment => segment.trim()).forEach(token => {
      if (token && !Number.isFinite(Number(token))) tokens.add(token)
    })
  }
  const addRecordFields = (value: unknown, fields: readonly string[]) => {
    const record = asRecord(value)
    if (!record) return
    fields.forEach(field => add(record[field]))
  }

  readdirSync(directory).filter(file => file.endsWith('.json')).forEach(file => {
    const manifest = JSON.parse(readFileSync(join(directory, file), 'utf8')) as JsonRecord
    ;['Modes', 'ImpactedVariables'].forEach(field => {
      const values = manifest[field]
      if (Array.isArray(values)) values.forEach(add)
    })
    add(manifest.InitState)
    ;['WorkingStates', 'InternalVariables', 'EnvironmentDomains'].forEach(field => {
      const values = manifest[field]
      if (!Array.isArray(values)) return
      values.forEach(value => addRecordFields(value, ['Name', 'Values']))
    })
    ;['Transitions', 'APIs'].forEach(field => {
      const values = manifest[field]
      if (!Array.isArray(values)) return
      values.forEach(value => {
        const record = asRecord(value)
        if (!record) return
        addRecordFields(record, ['Name', 'StartState', 'EndState'])
        addRecordFields(record.Trigger, ['Attribute', 'Value'])
        if (Array.isArray(record.Assignments)) {
          record.Assignments.forEach(assignment => addRecordFields(assignment, ['Attribute', 'Value']))
        }
      })
    })
  })
  return [...tokens].sort()
}

const localeMessage = (locale: 'zh-CN' | 'en', key: string): unknown => {
  let value: unknown = i18n.global.getLocaleMessage(locale)
  for (const segment of key.split('.')) {
    const record = asRecord(value)
    if (!record) return undefined
    value = record[segment]
  }
  return value
}

describe('modelTokenDisplay', () => {
  it.each(['zh-CN', 'en'] as const)('formats emitted bundled state tokens in %s without changing custom identifiers', locale => {
    i18n.global.locale.value = locale
    const translate = (key: string) => i18n.global.t(key)
    expect(formatModelTokenBySource('BUNDLED', 'takingphoto', translate)).toBe(translate('app.modelTokens.takingPhoto'))
    expect(formatModelTokenBySource('BUNDLED', 'notauthorized', translate)).toBe(translate('app.modelTokens.notAuthorized'))
    expect(formatModelTokenBySource('CUSTOM', 'takingphoto', translate)).toBe('takingphoto')
    expect(formatModelTokenBySource('UNKNOWN', 'takingphoto', translate)).toBe('takingphoto')
  })

  it('formats each segment of a bundled composite working state', () => {
    expect(formatBuiltInModelToken('off; idle', t)).toBe('关闭; 空闲')
    expect(formatBuiltInModelToken('off, locked | idle', t)).toBe('关闭, 已锁定 | 空闲')
  })

  it('preserves custom and differently-cased tokens exactly', () => {
    expect(formatBuiltInModelToken('ecoBoost', t)).toBe('ecoBoost')
    expect(formatBuiltInModelToken('OFF', t)).toBe('OFF')
    expect(formatBuiltInModelToken('living room temperature', t)).toBe('living room temperature')
  })

  it('uses the template provenance boundary for recommendation and runtime displays', () => {
    expect(formatModelTokenForTemplate({ defaultTemplate: true }, 'off', t)).toBe('关闭')
    expect(formatModelTokenForTemplate({}, 'photo', t)).toBe('photo')
    expect(formatModelTokenForTemplate({ defaultTemplate: undefined }, 'workingState', t)).toBe('workingState')
    expect(formatModelTokenForTemplate({ defaultTemplate: false }, 'off', t)).toBe('off')
    expect(formatModelTokenForTemplate(null, 'off', t)).toBe('off')
  })

  it('localizes fix tokens only for explicit bundled provenance', () => {
    expect(formatModelTokenBySource('BUNDLED', 'workingState', t)).toBe('工作状态')
    expect(formatModelTokenBySource('BUNDLED', 'off', t)).toBe('关闭')
    expect(formatModelTokenBySource('CUSTOM', 'workingState', t)).toBe('workingState')
    expect(formatModelTokenBySource('CUSTOM', 'off', t)).toBe('off')
    expect(formatModelTokenBySource('UNKNOWN', 'workingState', t)).toBe('workingState')
    expect(formatModelTokenBySource('UNKNOWN', 'off', t)).toBe('off')
  })

  it('covers every user-visible token declared by bundled manifests', () => {
    const tokens = bundledManifestTokens()
    expect(tokens.length).toBeGreaterThan(100)
    tokens.forEach(token => {
      const requestedKeys: string[] = []
      expect(formatBuiltInModelToken(token, key => {
        requestedKeys.push(key)
        return `translated:${key}`
      }), token).not.toBe(token)
      expect(requestedKeys, token).toHaveLength(1)
      for (const locale of ['zh-CN', 'en'] as const) {
        expect(localeMessage(locale, requestedKeys[0]), `${locale}: ${token}`).toEqual(expect.any(String))
      }
    })
  })
})
