const HAN_CHARACTER = /[\u3400-\u9fff]/g
const ENGLISH_WORD = /\b[A-Za-z]{2,}\b/g

export const textMatchesLocale = (value: unknown, locale: string): value is string => {
  if (typeof value !== 'string' || !value.trim()) return false
  const text = value.trim()
  const hanCount = text.match(HAN_CHARACTER)?.length || 0
  const englishWordCount = text.match(ENGLISH_WORD)?.length || 0
  const hasHan = hanCount > 0
  const hasEnglishWords = englishWordCount > 0

  // User-defined device and state labels commonly use a different script from the UI.
  // Without structured metadata, mixed-script text cannot be classified safely.
  if (hasHan && hasEnglishWords) return true

  const chineseLocale = locale.toLowerCase().startsWith('zh')
  return chineseLocale ? hasHan : !hasHan
}

export const localizedTextOrFallback = (
  value: unknown,
  fallback: string,
  locale: string
): string => textMatchesLocale(value, locale) ? value.trim() : fallback

export const localizedErrorMessage = (
  error: any,
  fallback: string,
  locale: string
): string => localizedTextOrFallback(
  error?.response?.data?.message || error?.message,
  fallback,
  locale
)

export interface ApiErrorDetails {
  cause?: string
  reasonCode?: string
  details: string[]
  fields: Record<string, string>
}

/** Account forms keep raw service diagnostics inside the technical disclosure. */
export const technicalErrorLines = (diagnostics: ApiErrorDetails): string[] => {
  const fieldReasons = Object.values(diagnostics.fields)
  return [...new Set([
    ...(diagnostics.cause && !fieldReasons.includes(diagnostics.cause) ? [diagnostics.cause] : []),
    ...diagnostics.details.filter(line => !fieldReasons.includes(line)),
    ...Object.entries(diagnostics.fields).map(([field, reason]) => `${field}: ${reason}`)
  ])]
}

/** Preserve declared diagnostics separately from localized user-facing copy. */
export const errorDetails = (error: any): ApiErrorDetails => {
  const response = error?.response
  const body = response?.data
  const data = body?.data
  const details: string[] = []
  const add = (value: unknown, prefix = '') => {
    if (typeof value === 'string' && value.trim()) details.push(prefix + value)
  }
  add(body?.message)
  add(error?.message)
  if (typeof response?.status === 'number') details.push(`HTTP ${response.status}`)
  add(error?.code, 'code: ')
  add(data?.reasonCode, 'reasonCode: ')
  add(data?.errorCategory, 'errorCategory: ')
  if (Array.isArray(data?.logs)) data.logs.forEach((line: unknown) => add(line))
  const fields = data?.errors && typeof data.errors === 'object' && !Array.isArray(data.errors)
    ? Object.fromEntries(Object.entries(data.errors).filter((entry): entry is [string, string] => (
      typeof entry[1] === 'string' && Boolean(entry[1].trim())
    )))
    : {}
  const cause = [body?.message, error?.message]
    .find((value): value is string => typeof value === 'string' && Boolean(value.trim()))
  const reasonCode = typeof data?.reasonCode === 'string' ? data.reasonCode.trim() : ''
  return {
    ...(cause ? { cause: cause.trim() } : {}),
    ...(reasonCode ? { reasonCode } : {}),
    details: [...new Set(details)], fields
  }
}
