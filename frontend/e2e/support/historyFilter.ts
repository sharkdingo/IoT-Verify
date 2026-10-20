import type { Page } from '@playwright/test'

export const selectHistoryResultType = async (
  page: Page, kind: 'all' | 'verification' | 'fuzzing' | 'simulation'
) => {
  const select = page.getByTestId('history-result-type')
  if (await select.isVisible()) await select.selectOption(kind)
  else await page.getByTestId(`history-result-filter-${kind}`).click()
}
