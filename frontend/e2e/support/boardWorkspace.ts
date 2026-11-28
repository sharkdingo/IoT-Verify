import { expect, type APIRequestContext, type Page } from '@playwright/test'
import { apiBaseURL, authHeaders, seedBrowserSession, type AuthUser } from './auth'
import { unwrap } from './apiResponse'

export const desktopBoardViewport = { width: 1440, height: 900 }

export const openBoardWorkspace = async (page: Page, auth: AuthUser) => {
  await page.setViewportSize(desktopBoardViewport)
  await seedBrowserSession(page, auth, { locale: 'en', theme: 'light' })
  await page.goto('/#/board')
  await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
}

export const saveEmptyBoard = async (request: APIRequestContext, auth: AuthUser) => {
  const headers = authHeaders(auth)
  const { impactToken } = await unwrap<{ impactToken: string }>(
    await request.get(`${apiBaseURL}/api/board/replacement-preview`, { headers }))
  const response = await request.post(`${apiBaseURL}/api/board/batch`, {
    headers,
    data: { impactToken, nodes: [], environmentVariables: [], rules: [], specs: [], templateSnapshots: [] }
  })
  expect(response.ok(), await response.text()).toBeTruthy()
}
