import { expect, type Page } from '@playwright/test'

export const collapseChatSidebar = async (page: Page): Promise<void> => {
  const scrim = page.getByTestId('chat-sidebar-scrim')
  await expect(scrim).toBeVisible()
  const bounds = await scrim.boundingBox()
  expect(bounds).not.toBeNull()
  // The backdrop fills the panel beneath the sidebar; its exposed right edge is the click target.
  await scrim.click({ position: { x: bounds!.width - 8, y: bounds!.height / 2 } })
  await expect(scrim).toBeHidden()
}
