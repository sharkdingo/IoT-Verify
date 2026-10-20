import { expect, type Page } from '@playwright/test'

/** Pin the shared replay geometry on both the canvas strip and its sibling terminal. */
export const expectPlaybackControlSpacing = async (page: Page, prefix: string) => {
  await page.evaluate(async () => { await document.fonts.ready })
  await expect.poll(() => page.locator(
    `[data-testid="playback-toolbar"], [data-testid="${prefix}-host"]`
  ).evaluateAll(elements => elements.every(element => element.getAnimations({ subtree: true })
    .every(animation => animation.playState !== 'running'
      || !Number.isFinite(animation.effect?.getComputedTiming().iterations))))).toBe(true)
  const controls = page.locator(
    '[data-testid="playback-toolbar"] button:not(.playback-terminal__marker):not(.iot-info-tooltip-trigger), '
      + `[data-testid="${prefix}"] button:not(.playback-terminal__marker):not(.iot-info-tooltip-trigger)`
  ).filter({ visible: true })
  const geometry = await controls.evaluateAll(buttons => buttons.map(button => {
    const target = button.getBoundingClientRect()
    const paint = getComputedStyle(button, '::before')
    const left = target.left + Number.parseFloat(paint.left)
    const right = target.right - Number.parseFloat(paint.right)
    const content: DOMRect[] = []
    for (const node of button.childNodes) {
      if (node.nodeType === Node.TEXT_NODE && node.textContent?.trim()) {
        const range = document.createRange()
        const text = node.textContent
        range.setStart(node, text.length - text.trimStart().length)
        range.setEnd(node, text.trimEnd().length)
        content.push(range.getBoundingClientRect())
      } else if (node instanceof Element && node.checkVisibility()) {
        content.push(node.getBoundingClientRect())
      }
    }
    return {
      id: button.getAttribute('data-testid') ?? button.getAttribute('aria-label'),
      targetWidth: target.width,
      targetHeight: target.height,
      paintedHeight: Number.parseFloat(paint.height),
      paintedWidth: Number.parseFloat(paint.width),
      iconOnly: content.length === 1 && button.querySelector('.material-symbols-outlined')?.checkVisibility(),
      background: getComputedStyle(button).backgroundColor,
      contentFits: content.every(box => box.left >= left + 3.9 && box.right <= right - 3.9)
    }
  }))
  expect(geometry.length).toBeGreaterThan(0)
  for (const control of geometry) {
    expect(control.targetWidth, control.id ?? '').toBeGreaterThanOrEqual(44)
    expect(control.targetHeight, control.id ?? '').toBe(44)
    expect(control.paintedHeight, control.id ?? '').toBe(28)
    expect(control.background, control.id ?? '').toBe('rgba(0, 0, 0, 0)')
    expect(control.contentFits, control.id ?? '').toBe(true)
    if (control.iconOnly) expect(control.paintedWidth, control.id ?? '').toBe(28)
  }
  const position = page.getByTestId(`${prefix}-step-input`).locator('..')
  expect(await position.evaluate(element => Number.parseFloat(getComputedStyle(element, '::before').height))).toBe(28)
  const play = page.getByTestId(`${prefix}-play`)
  await play.hover()
  await expect(play).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)')
  expect(await play.evaluate(element => getComputedStyle(element, '::before').backgroundColor))
    .not.toBe('rgba(0, 0, 0, 0)')
  await page.mouse.move(0, 0)
}
