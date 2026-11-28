import { expect, it, vi } from 'vitest'

vi.mock('./http', () => ({
  default: {
    post: vi.fn()
  }
}))

import http from './http'
import { recommendRules } from './rules'
import { isRecommendationPostOutcomeUnknown } from '@/utils/recommendationRequestRecovery'

const resultEnvelope = (data: unknown) => ({ data: { data } })

it('distinguishes transport loss from a malformed body received over HTTP', async () => {
  const options = { authToken: 'alice-token', signal: new AbortController().signal }
  const post = vi.mocked(http.post)
  post.mockRejectedValueOnce(new Error('connection reset'))

  await expect(recommendRules({ ...options, requestId: 'rule-transport-loss' }))
    .rejects.toSatisfy(isRecommendationPostOutcomeUnknown)
  expect(post.mock.calls[0][2]).toMatchObject({
    timeout: 0,
    headers: { Authorization: 'Bearer alice-token' }
  })

  post.mockResolvedValueOnce(resultEnvelope({ message: 'malformed response' }))
  let validationError: unknown
  try {
    await recommendRules({ ...options, requestId: 'rule-malformed-response' })
  } catch (error) {
    validationError = error
  }
  expect(validationError).toBeInstanceOf(Error)
  expect(isRecommendationPostOutcomeUnknown(validationError)).toBe(false)
})
