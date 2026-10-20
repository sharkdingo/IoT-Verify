import { describe, expect, it } from 'vitest'
import { decodeDeviceImportFile, DeviceImportEncodingError, parseDeviceImportCsv } from '../deviceListImport'
import fixture from '@/testing/fixtures/device-list-encoding.json'

const t = (key: string, params?: Record<string, unknown>) => `${key} ${JSON.stringify(params || {})}`

describe('device-list file decoding', () => {
  it.each(['', '\uFEFF'])('decodes UTF-8 with optional BOM and preserves Chinese and CSV quoting', bom => {
    expect(decodeDeviceImportFile(new TextEncoder().encode(bom + fixture.csv).buffer, 'utf-8')).toBe(fixture.csv)
  })

  it('decodes explicitly selected GB18030 / GBK without changing Chinese text', () => {
    const bytes = Uint8Array.from(Buffer.from(fixture.gb18030Base64, 'base64'))
    expect(decodeDeviceImportFile(bytes.buffer, 'gb18030')).toBe(fixture.csv)
  })

  it.each([
    ['utf-8', [0xbf, 0xcd, 0xcc, 0xfc]],
    ['utf-8', [0xc3, 0x28]],
    ['gb18030', [0x81]],
    ['utf-8', [0xff, 0xfe, 0x41, 0x00]],
    ['gb18030', [0xff, 0xfe, 0x41, 0x00]],
    ['gb18030', [0xef, 0xbb, 0xbf, 0x41]]
  ] as const)('rejects invalid or mismatched %s bytes instead of replacing characters', (encoding, bytes) => {
    expect(() => decodeDeviceImportFile(Uint8Array.from(bytes).buffer, encoding)).toThrow(DeviceImportEncodingError)
  })
})

describe('CSV device lists', () => {
  it('expands every runtime field into the same shape as a JSON device entry', () => {
    expect(parseDeviceImportCsv('templateName,label,state,currentStateTrust,currentStatePrivacy,variable:steps,trust:steps,privacy:steps\nMobile Phone,phone,on,trusted,private,23,untrusted,public', t))
      .toEqual([{ source: '#2', item: { templateName: 'Mobile Phone', label: 'phone', state: 'on',
        currentStateTrust: 'trusted', currentStatePrivacy: 'private',
        variables: [{ name: 'steps', value: '23', trust: 'untrusted' }],
        privacies: [{ name: 'steps', privacy: 'public' }] } }])
  })

  it('omits blank optional cells so the shared validator can apply template defaults', () => {
    expect(parseDeviceImportCsv('templateName,label,state,variable:steps,trust:steps,privacy:steps\nMobile Phone,phone,,,,', t))
      .toEqual([{ source: '#2', item: { templateName: 'Mobile Phone', label: 'phone' } }])
  })

  it('accepts name-only rows with or without a header and skips blank lines', () => {
    for (const prefix of ['', 'template,name\n']) {
      expect(parseDeviceImportCsv(`${prefix}\nLight,light\n\n`, t).map(row => row.item))
        .toEqual([{ templateName: 'Light', label: 'light' }])
    }
  })

  it('keeps an explicitly empty quoted row for validation instead of silently skipping it', () => {
    expect(parseDeviceImportCsv('Light,first\n""\nLight,last', t).map(row => row.item))
      .toEqual([{ templateName: 'Light', label: 'first' }, { templateName: '', label: '' }, { templateName: 'Light', label: 'last' }])
  })

  it('preserves commas, escaped quotes and newlines in quoted cells with physical row locations', () => {
    expect(parseDeviceImportCsv('\uFEFFtemplateName,label\r\nLight,"Hall, ""east""\nlight"\r\nLight,last', t))
      .toEqual([
        { source: '#2', item: { templateName: 'Light', label: 'Hall, "east"\nlight' } },
        { source: '#4', item: { templateName: 'Light', label: 'last' } }
      ])
  })

  it('counts CR-only newlines inside quoted Chinese names when locating the next row', () => {
    expect(parseDeviceImportCsv('templateName,label\rLight,"客厅, ""主灯""\r二楼"\rLight,走廊灯', t))
      .toEqual([
        { source: '#2', item: { templateName: 'Light', label: '客厅, "主灯"\r二楼' } },
        { source: '#4', item: { templateName: 'Light', label: '走廊灯' } }
      ])
  })

  it.each([
    ['templateName,label,unknown\nLight,light,1', 'deviceImportUnknownField'],
    ['templateName,label,state,state\nLight,light,on,off', 'deviceImportCsvDuplicateColumn'],
    ['templateName,label,variable:\nLight,light,on', 'deviceImportUnknownField'],
    ['Light,light,extra', 'deviceImportCsvColumnCount'],
    ['Light,"unfinished', 'deviceImportCsvQuoteError'],
    ['Light,bad"quote', 'deviceImportCsvInvalidQuote'],
    ['Light,"quoted"tail', 'deviceImportCsvInvalidQuote']
  ])('rejects invalid CSV without silently dropping data: %s', (text, key) => {
    const rows = parseDeviceImportCsv(text, t)
    expect(rows).toHaveLength(1)
    expect(rows[0].error).toContain(key)
    expect(rows[0].item).toBeUndefined()
  })
})
