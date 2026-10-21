/**
 * Dismiss hover and focus tooltips before clicking with normal hit testing.
 *
 * Focus ownership and delayed mounting: docs/development/known-traps.md.
 */
import { expect, type Locator, type Page } from '@playwright/test'

/** Element Plus keeps closed poppers mounted, so "gone" means hidden, not absent. */
const openPoppers = (page: Page): Locator =>
  page.locator('.iot-info-tooltip-popper:not([aria-hidden="true"])')

export const dismissHintTooltips = async (page: Page): Promise<void> => {
  await page.mouse.move(4, 4)
  await expect.poll(async () => {
    // The ARIA owner can wrap the focused control. Leave unrelated input drafts focused.
    await page.evaluate(() => {
      const focused = document.activeElement
      if (!(focused instanceof HTMLElement)) return
      for (let owner: HTMLElement | null = focused; owner; owner = owner.parentElement) {
        const descriptions = owner.getAttribute('aria-describedby')?.split(/\s+/) || []
        if (descriptions.some(id => document.getElementById(id)?.matches('.iot-info-tooltip-popper'))) {
          focused.blur()
          return
        }
      }
    })
    return openPoppers(page).count()
  }, { timeout: 5_000 }).toBe(0)
}

/** Dismiss any open hint popper, then click normally — with the hit-target check intact. */
export const clickUnderTooltip = async (page: Page, target: Locator): Promise<void> => {
  await dismissHintTooltips(page)
  await target.click()
}
