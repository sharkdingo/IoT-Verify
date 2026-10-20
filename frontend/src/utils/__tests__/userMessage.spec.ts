import { describe, expect, it } from 'vitest'
import { errorDetails, localizedErrorMessage, localizedTextOrFallback, technicalErrorLines, textMatchesLocale } from '../userMessage'

describe('user-facing free-text language guard', () => {
  it('retains and deduplicates causes, fields and protocol details for account disclosures', () => {
    expect(technicalErrorLines({ cause: 'Already registered.',
      details: ['Already registered.', 'HTTP 409'], fields: { phone: 'Already registered.' }
    })).toEqual(['HTTP 409', 'phone: Already registered.'])
    expect(technicalErrorLines({ cause: 'Response lost.', details: ['HTTP 503'], fields: {} }))
      .toEqual(['Response lost.', 'HTTP 503'])
  })
  it('rejects an English backend sentence in a Chinese interface', () => {
    expect(textMatchesLocale('Unknown condition device: device_123', 'zh-CN')).toBe(false)
    expect(localizedErrorMessage({
      response: { data: { message: 'Unknown condition device: device_123' } }
    }, '操作失败', 'zh-CN')).toBe('操作失败')
  })

  it('keeps Chinese explanations and English device labels used inside them', () => {
    expect(localizedTextOrFallback(
      '删除设备 Air Conditioner Working State 失败，请检查 API 配置',
      '操作失败',
      'zh-CN'
    )).toBe('删除设备 Air Conditioner Working State 失败，请检查 API 配置')
  })

  it('keeps English diagnostics in an English interface', () => {
    expect(localizedTextOrFallback('The request was rejected.', 'Request failed', 'en'))
      .toBe('The request was rejected.')
  })

  it('keeps English explanations containing Chinese user-defined labels', () => {
    expect(localizedTextOrFallback(
      'The rule turns 客厅灯 on.',
      'Request failed',
      'en'
    )).toBe('The rule turns 客厅灯 on.')
  })

  it('rejects a Chinese-only backend sentence in an English interface', () => {
    expect(localizedTextOrFallback('请求被拒绝', 'Request failed', 'en'))
      .toBe('Request failed')
  })
})

describe('API failure diagnostics', () => {
  it('preserves declared raw reasons, logs and field errors without exposing arbitrary response or request data', () => {
    const error = {
      message: 'Request failed with status code 422', code: 'ERR_BAD_REQUEST',
      config: { headers: { Authorization: 'do-not-show-token' } },
      response: { status: 422, data: {
        message: 'Rule references an unknown device',
        data: {
          reasonCode: 'MODEL_INVALID', errorCategory: 'DEVICE_REFERENCE',
          logs: ['Rule references an unknown device', 'Inspect rules[0]'],
          errors: { 'rules[0].deviceName': 'Unknown device: 灯', ignored: { private: 'do-not-show' } },
          token: 'do-not-show-token', cause: 'not-a-declared-diagnostic'
        }
      } }
    }
    expect(localizedErrorMessage(error, '操作失败', 'zh-CN')).toBe('操作失败')
    expect(errorDetails(error)).toEqual({
      cause: 'Rule references an unknown device',
      reasonCode: 'MODEL_INVALID',
      details: [
        'Rule references an unknown device', 'Request failed with status code 422',
        'HTTP 422', 'code: ERR_BAD_REQUEST', 'reasonCode: MODEL_INVALID',
        'errorCategory: DEVICE_REFERENCE', 'Inspect rules[0]'
      ],
      fields: { 'rules[0].deviceName': 'Unknown device: 灯' }
    })
  })

  it('retains a local parser cause and does not invent a server status or outcome', () => {
    expect(errorDetails(new SyntaxError('Expected a comma at position 42'))).toEqual({
      cause: 'Expected a comma at position 42',
      details: ['Expected a comma at position 42'], fields: {}
    })
    expect(errorDetails(undefined)).toEqual({ details: [], fields: {} })
  })

  it('ignores malformed diagnostic collections instead of stringifying objects', () => {
    expect(errorDetails({ response: { data: { data: {
      logs: ['Useful log', null, { password: 'private' }, ''],
      errors: ['not a field map']
    } } } })).toEqual({ details: ['Useful log'], fields: {} })
  })

  it('keeps only the declared nonempty reason code for failure guidance', () => {
    expect(errorDetails({ response: { data: { data: { reasonCode: ' DUPLICATE_RULE ' } } } }))
      .toMatchObject({ reasonCode: 'DUPLICATE_RULE' })
    for (const reasonCode of ['', ' ', 422, { code: 'DUPLICATE_RULE' }]) {
      expect(errorDetails({ response: { data: { data: { reasonCode } } } }))
        .not.toHaveProperty('reasonCode')
    }
    expect(errorDetails({ message: 'DUPLICATE_RULE' })).not.toHaveProperty('reasonCode')
  })
})
