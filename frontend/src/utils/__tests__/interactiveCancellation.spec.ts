// @vitest-environment node
import { describe, expect, it, vi } from 'vitest'
import { requestInteractiveCancellation } from '../interactiveCancellation'

describe('requestInteractiveCancellation', () => {
  it('retries when cancellation races server-side request registration', async () => {
    const cancel = vi.fn()
      .mockResolvedValueOnce(false)
      .mockResolvedValueOnce(true)
    const waitBeforeRetry = vi.fn().mockResolvedValue(undefined)

    await expect(requestInteractiveCancellation({ cancel, waitBeforeRetry }))
      .resolves.toBe(true)

    expect(cancel).toHaveBeenCalledTimes(2)
    expect(waitBeforeRetry).toHaveBeenCalledWith(1)
  })

  it('retries a transient cancellation transport failure', async () => {
    const cancel = vi.fn()
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce(true)

    await expect(requestInteractiveCancellation({
      cancel,
      waitBeforeRetry: async () => undefined
    })).resolves.toBe(true)

    expect(cancel).toHaveBeenCalledTimes(2)
  })

  it('stops retrying when the request is no longer active', async () => {
    let active = true
    const cancel = vi.fn().mockImplementation(async () => {
      active = false
      return false
    })
    const waitBeforeRetry = vi.fn().mockResolvedValue(undefined)

    await expect(requestInteractiveCancellation({
      cancel,
      waitBeforeRetry,
      shouldContinue: () => active
    })).resolves.toBe(false)

    expect(cancel).toHaveBeenCalledTimes(1)
    expect(waitBeforeRetry).not.toHaveBeenCalled()
  })
})
