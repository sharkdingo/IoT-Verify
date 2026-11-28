import { expect, type APIRequestContext, type APIResponse } from '@playwright/test'
import { apiBaseURL, authHeaders, type AuthUser } from './auth'

export const unwrap = async <T = any>(
  response: Pick<APIResponse, 'ok' | 'text' | 'json'>
): Promise<T> => {
  expect(response.ok(), await response.text()).toBe(true)
  const body = await response.json()
  expect(body.code, JSON.stringify(body)).toBe(200)
  return body.data
}

export const waitForApi = async <T>(
  request: APIRequestContext,
  auth: AuthUser,
  apiPath: string,
  predicate: (value: T) => boolean,
  timeoutMs = 30_000
): Promise<T> => {
  const deadline = Date.now() + timeoutMs
  let latest: T | undefined
  while (Date.now() < deadline) {
    latest = await unwrap<T>(await request.get(`${apiBaseURL}${apiPath}`, { headers: authHeaders(auth) }))
    if (predicate(latest)) return latest
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error(`Timed out waiting for ${apiPath}; latest=${JSON.stringify(latest)}`)
}
