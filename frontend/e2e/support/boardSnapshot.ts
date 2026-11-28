import { expect, type APIRequestContext } from '@playwright/test'
import type { BoardModelFingerprints } from '../../src/types/modelSemantics'
import { apiBaseURL } from './auth'

type BoardSnapshot = {
  deviceTemplates: { id: number }[]
  [key: string]: unknown
}

export async function readBoardModelFingerprints(request: APIRequestContext, token: string): Promise<BoardModelFingerprints> {
  const response = await request.get(`${apiBaseURL}/api/board/model-fingerprints`, {
    headers: { Authorization: `Bearer ${token}` }
  })
  expect(response.ok(), await response.text()).toBe(true)
  const { code, data } = await response.json()
  expect(code).toBe(200)
  const { verification, simulation, modelSemanticsVersion } = data
  return { verification, simulation, modelSemanticsVersion }
}

export function expectSameBoardSnapshot(actual: BoardSnapshot, expected: BoardSnapshot) {
  // Template inventory order has no scene meaning. Preserve every field and all other array orders.
  const orderedInventory = (snapshot: BoardSnapshot) => ({
    ...snapshot,
    deviceTemplates: [...snapshot.deviceTemplates].sort((left, right) => left.id - right.id)
  })
  expect(orderedInventory(actual)).toEqual(orderedInventory(expected))
}

/** A scene restored by undo has the same collections with a different journal head. */
export function expectSameBoardScene(actual: BoardSnapshot, expected: BoardSnapshot) {
  const { editHistory: _actualHistory, ...actualScene } = actual
  const { editHistory: _expectedHistory, ...expectedScene } = expected
  expectSameBoardSnapshot(actualScene, expectedScene)
}
