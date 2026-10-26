import { expect, type Page } from '@playwright/test'

/** Creation and the template library start collapsed; tests must choose the tool they use. */
export const expandControlTool = async (page: Page, testId: string) => {
  const disclosure = page.getByTestId(testId)
  if (await disclosure.getAttribute('open') === null) {
    const summary = disclosure.locator(':scope > summary')
    await expect(summary).toBeVisible()
    // At narrow widths the summary's center can be its nested help button.
    await summary.focus()
    await summary.press('Enter')
  }
  await expect(disclosure).toHaveAttribute('open', '')
}

export const expandCreation = async (page: Page, section: string) => {
  if (section === 'templates') {
    await expandControlTool(page, 'control-template-create')
    await expandControlTool(page, 'control-template-repository')
  } else {
    const kind = section === 'devices' ? 'device' : section === 'rules' ? 'rule' : 'spec'
    await expandControlTool(page, `${kind}-creation-card`)
  }
}
