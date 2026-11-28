import type { Page } from '@playwright/test'
import { expect } from './auth'

export const afterHistoryRender = (page: Page) =>
  page.evaluate(() => new Promise<void>(resolve =>
    requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))

/** Hold one history response without stopping later reads of the same record. */
export const holdHistoryReply = async (
  page: Page,
  path: string | ((url: URL) => boolean),
  reply?: { status: number; json: unknown }
) => {
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  const matches = typeof path === 'string' ? (url: URL) => url.pathname === path : path
  const requested = page.waitForRequest(read => read.method() === 'GET' && matches(new URL(read.url())))
  await page.route(matches, async route => {
    const response = reply ?? { response: await route.fetch() }
    await gate
    await route.fulfill(response)
  }, { times: 1 })
  return {
    requested,
    release,
    async delivered() {
      const read = await requested
      const response = await read.response()
      expect(response, read.url()).not.toBeNull()
      expect(await response!.finished()).toBeNull()
      // The network can finish before Axios and Vue have applied the response.
      await afterHistoryRender(page)
      return response!
    }
  }
}
