import type { BoardReplacementPreview } from '@/api/board'

/**
 * Reads the diagnostics a rejected scene import or a stale replacement returned.
 *
 * Pure boundary parsing: a malformed payload yields "no diagnostics" rather than a half-trusted
 * object, so a caller can never render a fabricated preview or an empty error list as if the server
 * had confirmed it. Kept out of the board so these rules are testable on their own.
 */

/** Field-path → reason pairs from a validation rejection, or empty when there are none to trust. */
export const getStructuredValidationErrors = (error: unknown): Array<[string, string]> => {
  const errors = (error as any)?.response?.data?.data?.errors
  if (!errors || typeof errors !== 'object' || Array.isArray(errors)) return []
  return Object.entries(errors)
    .filter(([field, reason]) => field && typeof reason === 'string' && reason.trim())
    .map(([field, reason]) => [field, String(reason)] as [string, string])
}

/**
 * Maps a backend field path to the collection and 1-based position a user can actually find.
 *
 * `devices[]` and `nodes[]` both appear because the export format and the API disagree on the name;
 * anything unrecognised is reported as a scene-level problem rather than guessed at.
 */
const COORDINATE_PATTERNS: Array<[RegExp, string]> = [
  [/^templates\[(\d+)]/, 'app.sceneImportValidationTemplate'],
  [/^devices\[(\d+)]/, 'app.sceneImportValidationDevice'],
  [/^nodes\[(\d+)]/, 'app.sceneImportValidationDevice'],
  [/^environmentVariables\[(\d+)]/, 'app.sceneImportValidationEnvironment'],
  [/^rules\[(\d+)]/, 'app.sceneImportValidationRule'],
  [/^specs\[(\d+)]/, 'app.sceneImportValidationSpecification']
]

export const formatSceneValidationCoordinate = (
  field: string,
  t: (key: string, named?: Record<string, unknown>) => string
): string => {
  for (const [pattern, key] of COORDINATE_PATTERNS) {
    const match = field.match(pattern)
    if (match) return t(key, { index: Number(match[1]) + 1 })
  }
  return t('app.sceneImportValidationScene')
}

/**
 * The current board preview carried by a `BOARD_REPLACEMENT_STALE` rejection.
 *
 * Returns null unless the payload is complete and internally consistent: the counts drive what the
 * user is told they are about to overwrite, so a partially-parsed preview would be worse than none.
 */
export const readBoardReplacementStalePreview = (
  error: unknown
): BoardReplacementPreview | null => {
  const data = (error as any)?.response?.data?.data
  const preview = data?.currentPreview
  if (data?.reasonCode !== 'BOARD_REPLACEMENT_STALE' || !preview) return null
  if (typeof preview.impactToken !== 'string' || !preview.impactToken.trim()) return null
  const counts = [
    preview.deviceCount,
    preview.environmentVariableCount,
    preview.ruleCount,
    preview.specificationCount
  ]
  if (!counts.every(value => Number.isSafeInteger(value) && Number(value) >= 0)) return null
  return preview as BoardReplacementPreview
}
