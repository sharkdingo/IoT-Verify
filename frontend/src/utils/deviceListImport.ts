export const DEVICE_IMPORT_TEMPLATE_ALIASES = ['template', 'templateName', 'type'] as const
export const DEVICE_IMPORT_NAME_ALIASES = ['name', 'label', 'deviceName'] as const

export type DeviceImportEncoding = 'utf-8' | 'gb18030'
export class DeviceImportEncodingError extends Error {
  constructor() {
    super('Device-list bytes do not match the selected file encoding')
    this.name = 'DeviceImportEncodingError'
  }
}

/** Decode strictly: replacement characters can turn a damaged name into an apparently valid device. */
export const decodeDeviceImportFile = (buffer: ArrayBuffer, encoding: DeviceImportEncoding): string => {
  const bytes = new Uint8Array(buffer)
  const utf8Bom = bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf
  const utf16Bom = (bytes[0] === 0xff && bytes[1] === 0xfe) || (bytes[0] === 0xfe && bytes[1] === 0xff)
  if (utf16Bom || (utf8Bom && encoding !== 'utf-8')) throw new DeviceImportEncodingError()
  const decoder = new TextDecoder(encoding, { fatal: true })
  try { return decoder.decode(buffer) }
  catch { throw new DeviceImportEncodingError() }
}

type Translate = (key: string, params?: Record<string, unknown>) => string
export type DeviceImportInputRow = { source: string; item: unknown; error?: never }
  | { source: string; error: string; item?: never }
type CsvRecord = { line: number; columns: string[]; error?: string }

const normalizeHeader = (value: string) => value.trim().toLowerCase().replace(/[\s_-]/g, '')
const templateHeaders = new Set(DEVICE_IMPORT_TEMPLATE_ALIASES.map(normalizeHeader))
const nameHeaders = new Set(DEVICE_IMPORT_NAME_ALIASES.map(normalizeHeader))
const runtimeHeaders = new Set(['state', 'currentStateTrust', 'currentStatePrivacy'])

const csvRecords = (text: string, t: Translate): CsvRecord[] => {
  const records: CsvRecord[] = []
  let columns: string[] = []
  let cell = ''
  let quoted = false
  let quoteClosed = false
  let recordHasContent = false
  let line = 1
  let recordLine = 1
  const finishRecord = () => {
    columns.push(cell.trim())
    if (columns.length > 1 || recordHasContent) records.push({ line: recordLine, columns })
    columns = []
    cell = ''
    quoteClosed = false
    recordHasContent = false
  }
  for (let index = 0; index < text.length; index++) {
    const char = text[index]
    if (char.trim()) recordHasContent = true
    if (quoted) {
      if (char === '"') {
        if (text[index + 1] === '"') { cell += '"'; index++ }
        else { quoted = false; quoteClosed = true }
      } else {
        cell += char
        if (char === '\n' || (char === '\r' && text[index + 1] !== '\n')) line++
      }
      continue
    }
    if (char === ',' || char === '\t') {
      columns.push(cell.trim())
      cell = ''
      quoteClosed = false
    } else if (char === '\n' || char === '\r') {
      finishRecord()
      if (char === '\r' && text[index + 1] === '\n') index++
      line++
      recordLine = line
    } else if (char === '"' && !cell.trim() && !quoteClosed) {
      cell = ''
      quoted = true
    } else if (char === '"' || (quoteClosed && char.trim())) {
      records.push({ line: recordLine, columns: [], error: t('app.deviceImportCsvInvalidQuote') })
      return records
    } else cell += char
  }
  if (quoted) records.push({ line: recordLine, columns: [], error: t('app.deviceImportCsvQuoteError') })
  else finishRecord()
  return records
}

/** Expand CSV columns into the same entries as JSON; both formats use the caller's runtime validation. */
export const parseDeviceImportCsv = (text: string, t: Translate): DeviceImportInputRow[] => {
  const records = csvRecords(text.replace(/^\uFEFF/, ''), t)
  const first = records[0]
  if (!first) return []
  let headers = ['templateName', 'label']
  if (templateHeaders.has(normalizeHeader(first.columns[0] || ''))
    && nameHeaders.has(normalizeHeader(first.columns[1] || ''))) {
    headers = first.columns.map((column, index) => index === 0 ? 'templateName' : index === 1 ? 'label' : column)
    const seen = new Set<string>()
    for (const header of headers) {
      const source = `#${first.line}`
      if (seen.has(header)) return [{ source, error: t('app.deviceImportCsvDuplicateColumn', { field: header }) }]
      seen.add(header)
      if (header === 'templateName' || header === 'label' || runtimeHeaders.has(header)) continue
      const match = /^(variable|trust|privacy):(.+)$/.exec(header)
      if (!match || !match[2].trim()) return [{ source, error: t('app.deviceImportUnknownField', { source, field: header }) }]
    }
    records.shift()
  }
  return records.map(record => {
    const source = `#${record.line}`
    if (record.error) return { source, error: record.error }
    if (record.columns.slice(headers.length).some(value => value !== '')) {
      return { source, error: t('app.deviceImportCsvColumnCount', { count: headers.length }) }
    }
    const item: Record<string, unknown> = { templateName: record.columns[0] || '', label: record.columns[1] || '' }
    const variables = new Map<string, Record<string, string>>()
    const privacies: { name: string; privacy: string }[] = []
    headers.slice(2).forEach((header, index) => {
      const value = record.columns[index + 2]
      if (!value) return
      if (runtimeHeaders.has(header)) { item[header] = value; return }
      const separator = header.indexOf(':')
      const kind = header.slice(0, separator)
      const name = header.slice(separator + 1)
      if (kind === 'privacy') privacies.push({ name, privacy: value })
      else {
        const variable = variables.get(name) || { name }
        variable[kind === 'variable' ? 'value' : 'trust'] = value
        variables.set(name, variable)
      }
    })
    if (variables.size) item.variables = [...variables.values()]
    if (privacies.length) item.privacies = privacies
    return { source, item }
  })
}
