// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { isValidNormalizedUsername, normalizeAccountIdentifier } from '../accountIdentifier'

describe('account identifier normalization', () => {
  it('composes Unicode and removes surrounding Unicode whitespace', () => {
    expect(normalizeAccountIdentifier('\u00a0\u2003Cafe\u0301\u2003\u00a0')).toBe('Caf\u00e9')
  })

  it('counts Unicode code points and rejects invisible format controls', () => {
    expect(isValidNormalizedUsername('\ud83d\udca1\ud83c\udfe0\ud83d\udd12')).toBe(true)
    expect(isValidNormalizedUsername('Ali\u200bce')).toBe(false)
  })
})
