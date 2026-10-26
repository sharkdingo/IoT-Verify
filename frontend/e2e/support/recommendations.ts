import { expect, type Page } from '@playwright/test'

export type RecommendationKind = 'device' | 'rule' | 'spec'

export async function openRecommendation(page: Page, kind: RecommendationKind) {
  const toggle = page.getByTestId('control-center-toggle')
  if (await toggle.getAttribute('aria-expanded') === 'false') await toggle.click()
  await expect(toggle).toHaveAttribute('aria-expanded', 'true')
  const tab = page.getByTestId(`control-tab-${kind}s`)
  await tab.click()
  const section = page.getByTestId(`${kind}-recommendation-section`)
  if (await section.getAttribute('open') === null) {
    await page.getByTestId(`open-${kind}-recommendations`).click()
  }
  await expect(page.getByTestId(`${kind}-recommendation-panel`)).toBeVisible()
}

export async function collapseRecommendation(page: Page, kind: RecommendationKind) {
  await page.getByTestId(`open-${kind}-recommendations`).click()
  await expect(page.getByTestId(`${kind}-recommendation-panel`)).toBeHidden()
}
