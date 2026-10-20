import type { Locator } from '@playwright/test'
import { expect } from './auth'

export const expectFooterUsable = async (footer: Locator) => {
  await expect.poll(() => footer.evaluate(element =>
    getComputedStyle(element.closest('.iot-dialog, .el-message-box') ?? element).transform
  ), { message: 'measure footer actions after the dialog entrance finishes' }).toBe('none')
  const layout = await footer.evaluate(element => {
    const bounds = element.getBoundingClientRect()
    const card = element.closest('.iot-dialog, .el-message-box')!
    const body = card.querySelector('.iot-dialog__body, .el-message-box__content')?.getBoundingClientRect()
    const header = card.querySelector('.iot-dialog__header, .el-message-box__header')?.getBoundingClientRect()
    const buttons = [...element.querySelectorAll<HTMLButtonElement>('button')].map(button => {
      const box = button.getBoundingClientRect()
      const icons = [...button.querySelectorAll<HTMLElement>(
        '.material-symbols-outlined, .material-icons-round, .iot-dialog-btn__spinner, svg'
      )].map(icon => icon.getBoundingClientRect())
      return {
        label: button.getAttribute('aria-label') || button.textContent?.trim(),
        left: box.left, right: box.right, top: box.top, bottom: box.bottom, height: box.height,
        aside: Boolean(button.closest('.iot-dialog__footer-aside')),
        fits: button.scrollWidth <= button.clientWidth + 1 && button.scrollHeight <= button.clientHeight + 1,
        iconsFit: icons.every(icon => icon.left >= box.left && icon.right <= box.right
          && icon.top >= box.top && icon.bottom <= box.bottom
          && Math.abs(icon.top + icon.height / 2 - box.top - box.height / 2) <= 1)
      }
    })
    return { contentSeparated: !body || (body.bottom <= bounds.top + 1 && (!header || body.top >= header.bottom - 1)),
      gap: Number.parseFloat(getComputedStyle(element).columnGap),
      rowGap: Number.parseFloat(getComputedStyle(element).rowGap),
      withinViewport: bounds.left >= 0 && bounds.right <= innerWidth
      && bounds.top >= 0 && bounds.bottom <= innerHeight, buttons }
  })
  expect.soft(layout.withinViewport, 'footer must remain inside the viewport').toBe(true)
  expect.soft(layout.contentSeparated, 'dialog content must stay between the header and footer').toBe(true)
  expect(layout.buttons.length, 'the action region must contain buttons').toBeGreaterThan(0)
  for (const button of layout.buttons) {
    expect.soft(button.height, button.label).toBeGreaterThanOrEqual(43.5)
    expect.soft(button.fits, `${button.label}: label must fit`).toBe(true)
    expect.soft(button.iconsFit, `${button.label}: icons must stay centered and inside the button`).toBe(true)
  }
  for (let index = 1; index < layout.buttons.length; index++) {
    const previous = layout.buttons[index - 1]!
    const current = layout.buttons[index]!
    const sameRow = Math.abs(previous.top + previous.height / 2 - current.top - current.height / 2) <= 1
    if (sameRow && !previous.aside && !current.aside) {
      expect.soft(current.left - previous.right, 'related actions use the footer gap').toBeCloseTo(layout.gap, 0)
    }
    if (current.top >= previous.bottom) {
      expect.soft(current.top - previous.bottom, 'wrapped actions retain the row gap')
        .toBeGreaterThanOrEqual(layout.rowGap - 0.5)
    }
    const overlap = Math.min(previous.right, current.right) > Math.max(previous.left, current.left) + 1
      && Math.min(previous.bottom, current.bottom) > Math.max(previous.top, current.top) + 1
    expect.soft(overlap, 'footer actions must not overlap').toBe(false)
  }
}
