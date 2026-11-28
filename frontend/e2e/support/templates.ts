import { expect, type APIRequestContext, type APIResponse } from '@playwright/test'
import type { DeviceManifest, DeviceTemplate } from '../../src/types/device'
import { apiBaseURL, type AuthUser } from './auth'

const templateResponse = async <T>(response: APIResponse): Promise<T> => {
  const body = await response.json()
  expect(response.ok(), JSON.stringify(body)).toBe(true)
  return body.data as T
}

export const shadowDefaultTemplate = async (
  request: APIRequestContext, auth: AuthUser, name: string, edit: (manifest: DeviceManifest) => void
) => {
  const headers = { Authorization: `Bearer ${auth.token}` }
  const boardUrl = `${apiBaseURL}/api/board`
  const templates = await templateResponse<DeviceTemplate[]>(await request.get(`${boardUrl}/templates`, { headers }))
  const original = templates.find(template => template.name === name)
  if (!original?.defaultTemplate) throw new Error(`Bundled template ${name} is missing`)
  const deletion = `${boardUrl}/templates/${original.id}`
  const preview = await templateResponse<{ impactToken: string }>(await request.get(`${deletion}/deletion-preview`, { headers }))
  await templateResponse(await request.post(`${deletion}/delete`, { headers, data: { impactToken: preview.impactToken } }))
  const custom = structuredClone(original.manifest)
  edit(custom)
  await templateResponse(await request.post(`${boardUrl}/templates`, { headers, data: { name: original.name, manifest: custom } }))
  return original.manifest
}
