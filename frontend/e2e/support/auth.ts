import {
  expect,
  test as base,
  type APIRequestContext,
  type APIResponse
} from '@playwright/test'
import { randomBytes, randomInt } from 'node:crypto'
import { budgetAdviceMessage } from './authBudget'

export const apiBaseURL = process.env.E2E_API_BASE_URL || 'http://127.0.0.1:8080'

export type AuthUser = {
  userId: number
  phone: string
  username: string
  token: string
}

export type TestAccountCredentials = {
  phone: string
  username: string
  password: string
}

type CreateAuthenticatedUserOptions = {
  usernamePrefix?: string
  phonePrefix?: string
}

const trackedAccounts = new WeakMap<APIRequestContext, Map<string, TestAccountCredentials>>()

const readJson = async (response: APIResponse): Promise<any | null> => {
  try {
    return await response.json()
  } catch {
    return null
  }
}

const responseDetail = (response: APIResponse, body: any | null) => {
  return `HTTP ${response.status()}${body ? ` ${JSON.stringify(body)}` : ''}`
}

const isMissingAccountLogin = (response: APIResponse, body: any | null) =>
  [401, 404].includes(response.status())
  || [401, 404].includes(Number(body?.code))

const deleteTrackedAccount = async (
  request: APIRequestContext,
  credentials: TestAccountCredentials
) => {
  const loginResponse = await request.post(`${apiBaseURL}/api/auth/login`, {
    data: {
      identifier: credentials.username,
      password: credentials.password
    }
  })
  const loginBody = await readJson(loginResponse)
  if (!loginResponse.ok() || loginBody?.code !== 200 || !loginBody?.data?.token) {
    if (isMissingAccountLogin(loginResponse, loginBody)) return
    throw new Error(`cleanup login failed for ${credentials.username}: ${responseDetail(loginResponse, loginBody)}`)
  }

  const deleteResponse = await request.delete(`${apiBaseURL}/api/auth/account`, {
    headers: { Authorization: `Bearer ${loginBody.data.token}` },
    data: {
      password: credentials.password,
      confirmation: credentials.username
    }
  })
  const deleteBody = await readJson(deleteResponse)
  if (!deleteResponse.ok() || deleteBody?.code !== 200) {
    throw new Error(`account cleanup failed for ${credentials.username}: ${responseDetail(deleteResponse, deleteBody)}`)
  }
}

export const trackTestAccount = (
  request: APIRequestContext,
  credentials: TestAccountCredentials
) => {
  const accounts = trackedAccounts.get(request)
  if (!accounts) {
    throw new Error('Test accounts require the shared test fixture from ./support/auth')
  }
  accounts.set(credentials.username, credentials)
}

export const createTestAccountCredentials = (
  options: CreateAuthenticatedUserOptions = {}
): TestAccountCredentials => {
  const usernamePrefix = (options.usernamePrefix || 'e2e').replace(/[^a-zA-Z0-9_]/g, '') || 'e2e'
  const phonePrefix = options.phonePrefix || '139'
  return {
    phone: `${phonePrefix}${String(randomInt(0, 100_000_000)).padStart(8, '0')}`,
    password: 'Pass1234!!',
    username: `${usernamePrefix}${randomBytes(8).toString('hex')}`.slice(0, 20)
  }
}

export const createAuthenticatedUser = async (
  request: APIRequestContext,
  options: CreateAuthenticatedUserOptions = {}
): Promise<AuthUser> => {
  const credentials = createTestAccountCredentials(options)
  const { phone, username, password } = credentials

  trackTestAccount(request, credentials)

  const registerResponse = await request.post(`${apiBaseURL}/api/auth/register`, {
    data: { phone, username, password }
  })
  if (registerResponse.status() === 429) {
    const body = await readJson(registerResponse)
    throw new Error(`${responseDetail(registerResponse, body)}\n${budgetAdviceMessage(body?.data?.retryAfterSeconds)}`)
  }
  expect(registerResponse.ok(), await registerResponse.text()).toBeTruthy()

  const loginResponse = await request.post(`${apiBaseURL}/api/auth/login`, {
    data: { identifier: username, password }
  })
  expect(loginResponse.ok(), await loginResponse.text()).toBeTruthy()
  const loginBody = await loginResponse.json()
  expect(loginBody.code, JSON.stringify(loginBody)).toBe(200)
  return loginBody.data as AuthUser
}

export const test = base.extend<
  { accountCleanup: void },
  { sharedReadOnlyAccount: AuthUser }
>({
  accountCleanup: [async ({ request }, use) => {
    const accounts = new Map<string, TestAccountCredentials>()
    trackedAccounts.set(request, accounts)
    try {
      await use()
    } finally {
      trackedAccounts.delete(request)
      const errors: string[] = []
      for (const credentials of [...accounts.values()].reverse()) {
        try {
          await deleteTrackedAccount(request, credentials)
        } catch (error) {
          errors.push(error instanceof Error ? error.message : String(error))
        }
      }
      if (errors.length > 0) {
        throw new Error(`Failed to clean up ${errors.length} E2E test account(s):\n${errors.join('\n')}`)
      }
    }
  }, { auto: true }],

  /**
   * One account per worker, for specs whose tests do not mutate account state.
   *
   * `accountCleanup` is per-test and *deletes* the accounts a test registered, so a
   * file-level cache of `createAuthenticatedUser` would hand later tests a token for a
   * deleted user. This fixture owns its own request context and cleanup instead, which
   * also keeps a spec well under the backend's registrations-per-hour limit.
   */
  sharedReadOnlyAccount: [async ({ playwright }, use) => {
    const request = await playwright.request.newContext()
    const accounts = new Map<string, TestAccountCredentials>()
    trackedAccounts.set(request, accounts)
    try {
      await use(await createAuthenticatedUser(request))
    } finally {
      trackedAccounts.delete(request)
      try {
        for (const credentials of [...accounts.values()].reverse()) {
          await deleteTrackedAccount(request, credentials)
        }
      } finally {
        await request.dispose()
      }
    }
  }, { scope: 'worker' }]
})

export { expect }
