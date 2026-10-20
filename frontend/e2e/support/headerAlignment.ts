import type { Locator } from '@playwright/test'
import { expect } from './auth'

export const expectHeaderAligned = async (header: Locator) => {
  const layout = await header.evaluate(element => {
    const title = element.querySelector<HTMLElement>('.iot-dialog__title, .board-panel-title')!
    const icon = element.querySelector<HTMLElement>('.iot-dialog__icon, .board-section-icon')!
    const titleBox = title.getBoundingClientRect()
    const center = (target: Element) => {
      const box = target.getBoundingClientRect()
      return box.top + box.height / 2
    }
    const controls = [...element.querySelectorAll<HTMLElement>(
      '.iot-dialog__close, .board-panel-close, .board-panel-icon-action, [data-testid="close-fuzzing-panel"], .iot-info-tooltip-trigger'
    )]
    const subtitle = element.querySelector('.iot-dialog__subtitle')?.getBoundingClientRect()
    return {
      title: title.textContent?.trim(),
      iconOffset: Math.abs(center(icon) - center(title)),
      controlOffsets: controls.map(control => Math.abs(center(control) - center(title))),
      titleFits: title.scrollWidth <= title.clientWidth + 1,
      subtitleGap: subtitle ? subtitle.top - titleBox.bottom : null
    }
  })
  expect.soft(layout.iconOffset, `${layout.title}: title and icon centers`).toBeLessThanOrEqual(1)
  for (const offset of layout.controlOffsets) {
    expect.soft(offset, `${layout.title}: title and control centers`).toBeLessThanOrEqual(1)
  }
  expect.soft(layout.titleFits, `${layout.title}: wrapped title must fit`).toBe(true)
  if (layout.subtitleGap !== null) {
    expect.soft(layout.subtitleGap, `${layout.title}: subtitle needs its own row`).toBeGreaterThanOrEqual(2)
  }
}
