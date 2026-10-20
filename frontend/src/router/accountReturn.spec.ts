import { describe, expect, it } from 'vitest'
import { resolveAccountReturn } from './accountReturn'

describe('account workspace return address', () => {
  it.each([
    '/board',
    '/board?run=verification:7&trace=15',
    '/board?run=simulation%3A7',
    '/board#history',
    '/board?run=exploration:4&finding=9#evidence'
  ])('preserves the complete workspace location %s', value => {
    expect(resolveAccountReturn(value)).toBe(value)
  })

  it.each([
    undefined, null, 1, true, {}, ['/board?run=simulation:7', '/account'],
    '', '/', '/account?returnTo=/board', '/404', '/board-other', '/board/child',
    'https://example.com/board', '//example.com/board', 'board',
    '/\\example.com', '/BOARD?run=simulation:7',
    '/board?run=simulation:7\n', '/board#\u0000', '/board?tab=\u007f'
  ].map(value => ({ value })))('rejects non-workspace or malformed destinations %j', ({ value }) => {
    expect(resolveAccountReturn(value)).toBe('/board')
  })
})
