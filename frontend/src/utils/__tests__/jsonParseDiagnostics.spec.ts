import { describe, expect, it } from 'vitest'

import { diagnoseJsonParseError } from '../jsonParseDiagnostics'

describe('JSON parse diagnostics', () => {
  it('locates the parser-reported offset across CRLF and UTF-16 text', () => {
    const source = '{"Name":"😀",\r\n "Modes" []}'
    let cause: unknown
    try { JSON.parse(source) } catch (error) { cause = error }
    const diagnostic = diagnoseJsonParseError(source, cause)
    expect(diagnostic.reason).toBe((cause as SyntaxError).message)
    expect(diagnostic).toMatchObject({ position: source.indexOf('['), line: 2, column: 10 })
  })

  it('retains a cause without inventing an offset when the parser did not provide one', () => {
    expect(diagnoseJsonParseError('{', new SyntaxError('Unexpected end of JSON input')))
      .toEqual({ reason: 'Unexpected end of JSON input' })
    expect(diagnoseJsonParseError('{bad', new SyntaxError('Unexpected token b')))
      .toEqual({ reason: 'Unexpected token b' })
  })

  it('accepts explicit line and column diagnostics only within the source', () => {
    expect(diagnoseJsonParseError('{\n "a" 1}', new SyntaxError('Unexpected token at line 2 column 6')))
      .toEqual({ reason: 'Unexpected token at line 2 column 6', line: 2, column: 6 })
    expect(diagnoseJsonParseError('{}', new SyntaxError('Unexpected token at line 3 column 1')))
      .toEqual({ reason: 'Unexpected token at line 3 column 1' })
  })

  it('rejects out-of-range offsets and retains the raw diagnostic', () => {
    const reason = 'Unexpected token in JSON at position 999'
    expect(diagnoseJsonParseError('{}', new SyntaxError(reason))).toEqual({ reason })
  })

  it.each(['position 1', 'at position 1', 'line 1 column 1'])(
    'does not mistake quoted input %s for a parser coordinate', source => {
      let cause: unknown
      try { JSON.parse(source) } catch (error) { cause = error }
      expect(diagnoseJsonParseError(source, cause)).toEqual({ reason: (cause as SyntaxError).message })
    }
  )
})
