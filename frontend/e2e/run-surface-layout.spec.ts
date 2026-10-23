import path from 'node:path'
import type { Locator, Page } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test } from './support/auth'
import { clickUnderTooltip, dismissHintTooltips } from './support/tooltips'
import { selectHistoryResultType } from './support/historyFilter'
import { expectHeaderAligned } from './support/headerAlignment'
import { expectFooterUsable } from './support/footerLayout'

const expectInsetIcon = async (button: Locator) => {
  const box = await button.evaluate(element => {
    const target = element.getBoundingClientRect()
    const face = getComputedStyle(element, '::before')
    return { width: target.width, height: target.height,
      faceWidth: Number.parseFloat(face.width), faceHeight: Number.parseFloat(face.height) }
  })
  expect(box).toEqual({ width: 44, height: 44, faceWidth: 28, faceHeight: 28 })
  await button.hover()
  await expect(button).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)')
}

const openTool = async (page: Page, kind: string) => {
  const restore = page.getByTestId('restore-action-dock')
  if (await restore.isVisible()) await restore.click()
  await clickUnderTooltip(page, page.getByTestId(`open-${kind}-panel`))
}

const expectStablePanelEnds = async (page: Page, kind: string, soft = false) => {
  await dismissHintTooltips(page)
  const panel = page.getByTestId(`${kind}-panel`)
  const positions = () => panel.evaluate(element => {
    const close = element.querySelector<HTMLButtonElement>('[data-testid^="close-"]')!
    const run = element.querySelector<HTMLButtonElement>('[data-testid^="run-"]')!
    return [close, run].map(button => {
      const box = button.getBoundingClientRect()
      const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2)
      return { top: box.top, bottom: box.bottom, reachable: button.contains(hit) }
    })
  })
  const before = await positions()
  const body = panel.locator(kind === 'fuzzing' ? ':scope > .iot-scroll-region' : '.board-run-panel__body')
  if (page.viewportSize()!.height < 600) {
    expect(await body.evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true)
  }
  await body.evaluate(element => { element.scrollTop = element.scrollHeight })
  const check = soft ? expect.soft : expect
  check(await positions(), 'scrolling parameters must not move the close or run actions').toEqual(before)
  const frame = await body.evaluate(element => {
    const bounds = element.getBoundingClientRect()
    const header = element.parentElement!.firstElementChild!.getBoundingClientRect()
    const footer = element.parentElement!.querySelector('.board-run-panel__footer')!.getBoundingClientRect()
    return { belowHeader: bounds.top >= header.bottom, aboveFooter: bounds.bottom <= footer.top }
  })
  check(frame, 'parameter content must have its own space between header and actions')
    .toEqual({ belowHeader: true, aboveFooter: true })
  const lastField = body.locator('input:not([type="hidden"]):visible, select:visible, button:visible').last()
  await lastField.scrollIntoViewIfNeeded()
  check(await lastField.evaluate(element => {
    const bounds = element.getBoundingClientRect()
    return element.contains(document.elementFromPoint(bounds.left + bounds.width / 2, bounds.top + bounds.height / 2))
  }), 'the last parameter control must remain reachable after scrolling').toBe(true)
  for (const button of before) {
    check(button.reachable, 'panel actions must remain reachable').toBe(true)
    check(button.top).toBeGreaterThanOrEqual(0)
    check(button.bottom).toBeLessThanOrEqual(page.viewportSize()!.height)
  }
  expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
}

for (const variant of [
  { width: 1920, height: 1080, theme: 'light', locale: 'en' },
  { width: 1920, height: 1080, theme: 'dark', locale: 'zh-CN' },
  { width: 1280, height: 900, theme: 'light', locale: 'en' },
  { width: 1280, height: 900, theme: 'dark', locale: 'en' },
  { width: 320, height: 380, theme: 'light', locale: 'zh-CN' },
  { width: 320, height: 380, theme: 'dark', locale: 'en' },
  { width: 375, height: 500, theme: 'dark', locale: 'en' }
]) {
  test(`keeps run settings, results and history usable at ${variant.width}x${variant.height} in ${variant.theme}`, async ({ page, request }, testInfo) => {
    test.setTimeout(120_000)
    const auth = await createAuthenticatedUser(request, { usernamePrefix: 'runSurface' })
    await page.setViewportSize(variant)
    await page.addInitScript(({ auth, variant }) => {
      localStorage.setItem('iot_verify_token', auth.token)
      localStorage.setItem('iot_verify_user', JSON.stringify({ userId: auth.userId, username: auth.username, phone: auth.phone }))
      localStorage.setItem('locale', variant.locale)
      localStorage.setItem('iot_verify_theme', variant.theme)
    }, { auth, variant })
    await page.goto('/#/board')
    await expect(page.locator('.iot-board')).toBeVisible({ timeout: 60_000 })
    const imported = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/board/scene')
    await page.getByTestId('scene-import-file').setInputFiles(path.resolve(process.cwd(), '..', 'docs/examples/default-climate-conflict-scene.json'))
    await page.getByRole('dialog').getByRole('button', {
      name: variant.locale === 'en' ? 'Replace scene' : '替换场景', exact: true
    }).click()
    expect((await imported).ok()).toBe(true)
    await openTool(page, 'fuzzing')
    await page.evaluate(async () => { await document.fonts.ready })
    const fuzzing = page.getByTestId('fuzzing-panel')
    await expect(page.getByTestId('run-fuzzing')).toBeEnabled()
    await expectHeaderAligned(fuzzing.locator('header'))
    if (variant.width < 480) {
      const parameterGap = await page.getByTestId('fuzz-path-length').evaluate(input =>
        input.closest('label')!.getBoundingClientRect().top
          - document.querySelector('[data-testid="fuzz-max-iterations"]')!.closest('label')!.getBoundingClientRect().bottom)
      expect.soft(parameterGap, 'narrow parameters need separate readable rows').toBeGreaterThanOrEqual(7.5)
      const modeLines = await page.getByTestId('fuzz-mode-board').locator('..').locator('span').evaluate(element =>
        element.getBoundingClientRect().height / Number.parseFloat(getComputedStyle(element).lineHeight))
      expect.soft(modeLines, 'strategy labels must not crowd out the parameters').toBeLessThanOrEqual(2)
    }
    await page.getByTestId('fuzz-max-iterations').focus()
    await expectStablePanelEnds(page, 'fuzzing', true)
    const advanced = fuzzing.locator('details').filter({ has: page.getByTestId('fuzz-population-size') })
    if (variant.width < 480) {
      await page.getByTestId('fuzz-max-iterations').fill('5000')
      await page.getByTestId('fuzz-path-length').fill('50')
      if (await advanced.getAttribute('open') === null) await advanced.locator('summary').click()
      const rejectedWorkload = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/fuzz/workload/preview'
        && response.request().postDataJSON().maxIterations === 5000
        && response.request().postDataJSON().pathLength === 50
        && response.request().postDataJSON().populationSize === 50)
      await page.getByTestId('fuzz-population-size').fill('50')
      expect((await (await rejectedWorkload).json()).data.accepted).toBe(false)
      await expect(fuzzing.getByRole('alert').filter({ hasText: variant.locale === 'en'
        ? 'estimated workload' : '预计工作量' })).toBeVisible()
      await expect(page.getByTestId('run-fuzzing')).toBeDisabled()
      expect.soft(await fuzzing.locator(':scope > .iot-scroll-region').evaluate(element => element.clientHeight),
        'a budget rejection must leave room to read and edit a complete parameter').toBeGreaterThanOrEqual(96)
      await page.screenshot({ path: testInfo.outputPath('exploration-budget-rejected.png') })
    }
    await page.getByTestId('fuzz-max-iterations').fill('1')
    await page.getByTestId('fuzz-path-length').fill('2')
    if (await advanced.getAttribute('open') === null) await advanced.locator('summary').click()
    const workload = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/fuzz/workload/preview'
      && response.request().postDataJSON().maxIterations === 1
      && response.request().postDataJSON().pathLength === 2
      && response.request().postDataJSON().populationSize === 1)
    await page.getByTestId('fuzz-population-size').fill('1')
    expect((await (await workload).json()).data.accepted).toBe(true)
    await expect(page.getByTestId('run-fuzzing')).toBeEnabled()
    await page.screenshot({ path: testInfo.outputPath('exploration-settings.png') })
    const exploration = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/fuzz/async')
    await page.getByTestId('run-fuzzing').click()
    const accepted = await exploration
    expect(accepted.ok()).toBe(true)
    const explorationTask = (await accepted.json()).data
    await expect(page.getByTestId('fuzzing-result-dialog')).toBeVisible({ timeout: 30_000 })
    await expectHeaderAligned(page.getByTestId('fuzzing-result-dialog').locator('.iot-dialog__header'))
    const explorationFooter = page.getByTestId('fuzzing-result-dialog').locator('.iot-dialog__footer')
    if (await explorationFooter.count()) await expectFooterUsable(explorationFooter)
    const completed = await request.get(`${apiBaseURL}/api/fuzz/tasks/${explorationTask.id}`, {
      headers: { Authorization: `Bearer ${auth.token}` }
    })
    expect(completed.ok()).toBe(true)
    const explorationResult = (await completed.json()).data
    expect(explorationResult.status).toBe('COMPLETED')
    expect(explorationResult.runId).toEqual(expect.any(Number))
    await page.getByTestId('close-fuzzing-result').click()
    await openTool(page, 'verification')
    await page.evaluate(async () => { await document.fonts.ready })
    if (variant.width >= 1280) {
      const label = page.getByTestId('verification-panel').getByText(
        variant.locale === 'en' ? 'Sensitivity-label propagation' : '敏感性标签传播', { exact: true })
      await expect(label).toBeVisible()
      expect(await label.evaluate(element => element.getBoundingClientRect().height
        / Number.parseFloat(getComputedStyle(element).lineHeight))).toBeLessThanOrEqual(2)
    }
    await expectInsetIcon(page.getByTestId('close-verification-panel'))
    await expectHeaderAligned(page.getByTestId('verification-panel').locator('.board-tool-header'))
    await clickUnderTooltip(page, page.getByTestId('verification-attack-toggle'))
    const budget = page.getByTestId('verification-attack-budget')
    await expect(budget).toHaveCSS('height', '44px')
    const mode = page.getByTestId('verification-attack-mode-exact')
    await expect.poll(() => mode.evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44)
    await mode.click()
    await expect(mode).toHaveAttribute('aria-pressed', 'true')
    const points = page.getByTestId('verification-attack-points').locator('label')
    expect(await points.count()).toBeGreaterThan(0)
    for (const point of await points.all()) {
      expect(await point.evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44)
      await expect(point).toHaveCSS('opacity', '1')
    }
    await expectStablePanelEnds(page, 'verification')
    await clickUnderTooltip(page, page.getByTestId('verification-attack-toggle'))
    await page.getByTestId('verification-mode-sync').click()
    const verified = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/verify')
    await page.getByTestId('run-verification').click()
    const resultData = (await (await verified).json()).data
    expect(resultData.outcome).toBe('VIOLATED')
    const result = page.getByTestId('verification-result-dialog')
    await expect(result).toBeVisible()
    await expectHeaderAligned(result.locator('.iot-dialog__header'))
    await expectFooterUsable(result.locator('.iot-dialog__footer'))
    const logs = result.getByTestId('verification-check-logs')
    await expect(logs).toBeVisible()
    expect(await logs.getAttribute('open')).toBeNull()
    await expect(result.getByTestId('verification-trace-replay').first()).toContainText(
      variant.locale === 'en' ? 'Replay' : '回放')
    await expectInsetIcon(page.getByTestId('close-verification-result'))
    for (const label of await result.locator('.verification-summary .board-form-label').all()) {
      expect(await label.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    }
    const artifactCopy = result.locator('.verification-run-artifact__copy')
    expect(await artifactCopy.evaluate(element => element.getBoundingClientRect().width)).toBeGreaterThanOrEqual(180)
    expect(await artifactCopy.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    const traceActions = result.locator('.verification-trace__actions button')
    expect(await traceActions.count()).toBeGreaterThan(0)
    for (const action of await traceActions.all()) {
      expect(await action.evaluate(element => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44)
    }
    await page.getByTestId('verification-trace-fix').first().click()
    const fix = page.getByTestId('fix-result-dialog')
    await expect(fix).toBeVisible()
    await expectFooterUsable(fix.locator('.iot-dialog__footer'))
    await fix.locator('.iot-dialog__footer').getByRole('button', {
      name: variant.locale === 'en' ? 'Close' : '关闭', exact: true
    }).click()
    await page.getByTestId('close-verification-result').click()
    await page.goto('/#/board')
    await openTool(page, 'history')
    await page.getByTestId('history-layer-results').click()
    const history = page.getByTestId('trace-history-panel')
    await expectHeaderAligned(history.locator('.board-tool-header'))
    await expect(history.getByTestId(`open-verification-run-${resultData.historyPersistence.runId}`)).toBeVisible()
    await expect(history.getByTestId(`open-fuzzing-run-${explorationResult.runId}`)).toBeVisible()
    await expect(history.locator('.history-card__header').first(),
      'history navigation must leave the first run title and timestamp readable').toBeInViewport({ ratio: 1 })
    if (variant.width < 640 || variant.height < 600) {
      await expect(history.getByTestId('history-result-type')).toBeVisible()
      await expect(history.getByTestId('history-result-type')).toHaveValue('all')
      await expect(history.getByTestId('history-result-type')).toHaveCSS('height', '44px')
      await expect(history.locator('.history-filters')).toBeHidden()
    } else await expect(history.locator('.history-filters')).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('history-results.png') })
    expect(await history.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    await expectInsetIcon(page.getByTestId('close-history-panel'))
    await selectHistoryResultType(page, 'fuzzing')
    await page.getByTestId(`open-fuzzing-run-${explorationResult.runId}`).click()
    await expect(page.getByTestId('fuzzing-result-dialog')).toBeVisible()
    await page.getByTestId('close-fuzzing-result').click()
    await openTool(page, 'simulation')
    await expectInsetIcon(page.getByTestId('close-simulation-panel'))
    await expectHeaderAligned(page.getByTestId('simulation-panel').locator('.board-tool-header'))
    const steps = page.getByTestId('simulation-steps-input')
    await steps.fill('1')
    await steps.press('Tab')
    await expect(page.getByTestId('simulation-steps-decrease')).toBeDisabled()
    await expectInsetIcon(page.getByTestId('simulation-steps-decrease'))
    const range = page.getByTestId('simulation-steps-range')
    await expect(range).toHaveCSS('height', '44px')
    await expect(range).toHaveAccessibleName(variant.locale === 'en' ? 'Observation steps' : '观察步数')
    await range.focus()
    await range.press('ArrowRight')
    await expect(steps).toHaveValue('2')
    await dismissHintTooltips(page)
    await page.getByTestId('simulation-steps-help').focus()
    await expect(page.locator('.iot-info-tooltip-popper:not([aria-hidden="true"])'))
      .toContainText(variant.locale === 'en' ? 'state transition' : '状态转换')
    await steps.focus()
    await dismissHintTooltips(page)
    const help = page.getByTestId('simulation-privacy-help')
    await help.focus()
    const popper = page.locator('.iot-info-tooltip-popper:not([aria-hidden="true"])')
    await expect(popper).toBeVisible()
    await expect(popper).toHaveCSS('opacity', '1')
    await expect.poll(() => popper.evaluate(element => {
      const box = element.getBoundingClientRect()
      const content = element.querySelector('.iot-tooltip-content')
      return {
        withinViewport: box.left >= 7 && box.right <= innerWidth - 7
          && box.top >= 7 && box.bottom <= innerHeight - 7,
        contentFits: !!content && content.scrollWidth <= content.clientWidth + 1
      }
    })).toEqual({ withinViewport: true, contentFits: true })
    await page.getByTestId('simulation-mode-async').click()
    await expect(page.getByTestId('simulation-save-history')).toHaveCount(0)
    await page.getByTestId('simulation-mode-sync').click()
    await expect(page.getByTestId('simulation-save-history')).toBeVisible()
    await expect(page.getByTestId('simulation-save-history')).toBeEnabled()
    await expectStablePanelEnds(page, 'simulation')
    const simulated = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/simulate/traces')
    await page.getByTestId('run-simulation').click()
    const simulation = (await (await simulated).json()).data
    expect(simulation.steps).toBe(2)
    expect(simulation.states).toHaveLength(3)
    await expect(page.getByTestId('simulation-timeline-host')).toBeVisible()
    await expect(page.locator('.el-message--success'), 'the visible result needs no duplicate toast').toHaveCount(0)
    await page.getByTestId('simulation-timeline-run-tab').click()
    await expect(page.getByTestId('simulation-run-info')).toContainText(variant.locale === 'en'
      ? '2 / 2 Requested Steps' : '2 / 2 请求步数')
    await page.getByTestId('simulation-timeline-close').click()
    await openTool(page, 'simulation')
    await page.route('**/api/simulate**', route => route.request().method() === 'POST'
      ? route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({
        code: 503, message: 'Run service is temporarily unavailable.',
        data: { errors: { 'run.context': 'The run service did not accept this request.' } }
      }) }) : route.continue())
    try {
      await page.getByTestId('run-simulation').click()
      const failure = page.getByTestId('simulation-result-dialog')
      await expect(failure).toBeVisible()
      await expectHeaderAligned(failure.locator('.iot-dialog__header'))
      await expectFooterUsable(failure.locator('.iot-dialog__footer'))
      const notice = failure.locator('.board-run-notice')
      await expect(notice).toHaveCSS('font-size', '14px')
      await expect(notice).toHaveCSS('padding', '8px 12px')
      await expect(notice.getByTestId('failure-next-step')).toBeVisible()
      const technical = notice.getByTestId('failure-technical-details')
      await technical.locator('summary').click()
      await expect(technical).toContainText('run.context')
      expect(await notice.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
      await expectInsetIcon(page.getByTestId('close-simulation-result'))
    } finally {
      await page.unrouteAll({ behavior: 'wait' })
    }
  })
}
