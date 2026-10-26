import { expect } from '@playwright/test'

type BoardSnapshot = {
  deviceTemplates: { id: number }[]
  [key: string]: unknown
}

export function expectSameBoardSnapshot(actual: BoardSnapshot, expected: BoardSnapshot) {
  // Template inventory order has no scene meaning. Preserve every field and all other array orders.
  const orderedInventory = (snapshot: BoardSnapshot) => ({
    ...snapshot,
    deviceTemplates: [...snapshot.deviceTemplates].sort((left, right) => left.id - right.id)
  })
  expect(orderedInventory(actual)).toEqual(orderedInventory(expected))
}
