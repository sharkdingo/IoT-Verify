import type { APIResponse, Page } from '@playwright/test'
import { apiBaseURL, createAuthenticatedUser, expect, test } from './support/auth'
import { selectHistoryResultType } from './support/historyFilter'

const unwrap = async (response: APIResponse) => {
  expect(response.ok(), await response.text()).toBe(true)
  const body = await response.json()
  expect(body.code).toBe(200)
  return body.data
}
const noHints = async (page: Page) => {
  await page.mouse.move(1, 1)
  await expect(page.locator('.iot-hint-tooltip-popper:visible')).toHaveCount(0)
}
const expectControlsReachable = async (page: Page, prefix: string) => {
  for (const id of ['playback-toolbar', `${prefix}-close`, `${prefix}-hide-panel`, `${prefix}-changes-tab`]) {
    const control = page.getByTestId(id)
    await expect(control).toBeInViewport()
    await expect.poll(() => control.evaluate(element => {
      const r = element.getBoundingClientRect()
      return element.contains(document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2))
    }), id).toBe(true)
  }
}

for (const locale of ['zh-CN', 'en']) {
  for (const width of [320, 1920]) {
    test(`history result and playback focus in ${locale} at ${width}px`, async ({ page, request }, testInfo) => {
      test.setTimeout(90_000)
      const auth = await createAuthenticatedUser(request)
      const headers = { Authorization: `Bearer ${auth.token}` }
      const post = async (path: string, data: unknown) => unwrap(await request.post(`${apiBaseURL}/api/${path}`, { headers, data }))
      await post('board/nodes', { devices: [{ id: 'replay_light', templateName: 'Light',
        label: '历史回放测试灯 / Historical playback test light', position: { x: 180, y: 160 },
        width: 176, height: 128, state: 'off', variables: [], privacies: [] }], environmentVariablePatches: [] })
      await post('board/rules', { conditions: [{ deviceName: 'replay_light', targetType: 'mode',
        attribute: 'SwitchState', relation: '=', value: 'off' }], command: { deviceName: 'replay_light', action: 'on' },
        ruleString: 'Turn on when off' })
      await post('board/specs', { name: 'Safety requirement', id: 'never_on', templateId: '3', templateLabel: 'Never',
        aConditions: [{ id: 'never_a', side: 'a', deviceId: 'replay_light', targetType: 'mode',
          key: 'SwitchState', relation: '=', value: 'on' }], ifConditions: [], thenConditions: [] })
      const settings = { attackScenario: { mode: 'NONE' }, enablePrivacy: false }
      const verification = await post('verify', settings)
      expect(verification.outcome).toBe('VIOLATED')
      const runId = verification.historyPersistence.runId
      const trace = verification.traces[0]
      const simulation = await post('simulate/traces', { ...settings, steps: 5 })
      const accepted = await post('fuzz/async', { explorationMode: 'BOARD_SNAPSHOT', targetSpecIds: ['never_on'],
        maxIterations: 1, pathLength: 3, populationSize: 1, seed: 17 })
      let fuzzRunId = 0
      await expect.poll(async () => {
        const task = await unwrap(await request.get(`${apiBaseURL}/api/fuzz/tasks/${accepted.id}`, { headers }))
        fuzzRunId = task.runId || 0
        return task.status
      }).toBe('COMPLETED')
      const fuzz = await unwrap(await request.get(`${apiBaseURL}/api/fuzz/runs/${fuzzRunId}`, { headers }))
      expect(fuzz.findings.length).toBeGreaterThan(0)
      await page.setViewportSize({ width, height: width === 320 ? 500 : 1080 })
      await page.addInitScript(({ auth, locale }) => {
        localStorage.setItem('iot_verify_token', auth.token)
        localStorage.setItem('iot_verify_user', JSON.stringify(auth))
        localStorage.setItem('locale', locale)
      }, { auth, locale })
      await page.goto('/#/board')
      await expect(page.getByTestId('scene-import')).toBeEnabled()
      const historyEntry = page.getByTestId('open-history-panel')
      const openHistory = async (kind: 'verification' | 'simulation' | 'fuzzing') => {
        const restore = page.getByTestId('restore-action-dock')
        if (await restore.isVisible()) await restore.click()
        await historyEntry.click()
        await expect(page.getByTestId('trace-history-panel')).toBeFocused()
        await noHints(page)
        await selectHistoryResultType(page, kind)
      }
      await openHistory('verification')
      await page.getByTestId(`open-verification-run-${runId}`).press('Enter')
      const result = page.getByTestId('verification-result-dialog')
      await expect(result.getByRole('dialog')).toBeFocused()
      await noHints(page)
      await page.keyboard.press('Escape')
      await expect(result).toBeHidden()
      await expect(historyEntry).toBeFocused()
      await openHistory('verification')
      await page.getByTestId(`run-evidence-verification-${runId}`).locator('summary').click()
      await page.getByTestId(`view-verification-trace-${trace.id}`).press('Enter')
      const toolbar = page.getByTestId('playback-toolbar')
      await expect(toolbar).toBeFocused()
      await noHints(page)
      await expectControlsReachable(page, 'trace-timeline')
      await page.screenshot({ path: testInfo.outputPath('counterexample-focused.png') })
      await page.keyboard.press('Tab')
      await expect(page.getByTestId('trace-timeline-play')).toBeFocused()
      await expect(page.locator('.iot-hint-tooltip-popper:visible')).toBeVisible()
      await page.getByTestId(`trace-timeline-state-${trace.states.length - 1}`).press('Home')
      await expect(page.getByTestId('trace-timeline-state-0')).toBeFocused()
      await page.keyboard.press('End')
      await expect(page.getByTestId(`trace-timeline-state-${trace.states.length - 1}`)).toBeFocused()
      await page.getByTestId('trace-timeline-changes-tab').press('ArrowRight')
      await expect(page.getByTestId('trace-timeline-details-tab')).toBeFocused()
      await page.keyboard.press('End')
      await expect(page.getByTestId('trace-timeline-run-tab')).toBeFocused()
      await page.keyboard.press('Home')
      await expect(page.getByTestId('trace-timeline-changes-tab')).toBeFocused()
      await page.getByTestId('counterexample-trace-help').click()
      const help = page.locator('.iot-info-tooltip-popper:not(.iot-hint-tooltip-popper):visible')
      await expect(help).toBeVisible()
      await page.keyboard.press('Escape')
      await expect(help).toHaveCount(0)
      await expect(page.getByTestId('trace-timeline')).toBeVisible()
      await page.getByTestId('trace-timeline-hide-panel').press('Enter')
      await expect(page.getByTestId('playback-panel-toggle')).toBeFocused()
      await page.keyboard.press('Enter')
      await expect(page.getByTestId('trace-timeline-changes-tab')).toBeFocused()
      await noHints(page)
      await page.keyboard.press('Escape')
      await expect(toolbar).toBeHidden()
      await expect(historyEntry).toBeFocused()
      await openHistory('verification')
      await page.getByTestId(`view-verification-trace-${trace.id}`).click()
      await expect(toolbar).toBeFocused()
      await page.getByTestId('trace-timeline-changes-tab').press('End')
      await page.getByTestId('counterexample-open-owning-run').press('Enter')
      await expect(result.getByRole('dialog')).toBeFocused()
      await expect(toolbar).toBeHidden()
      await noHints(page)
      await page.keyboard.press('Escape')
      await expect(result).toBeHidden()
      await expect(historyEntry).toBeFocused()
      await openHistory('simulation')
      await page.getByTestId(`replay-simulation-trace-${simulation.id}`).press('Enter')
      await expect(toolbar).toBeFocused()
      await noHints(page)
      await expectControlsReachable(page, 'simulation-timeline')
      await page.getByTestId('simulation-timeline-state-0').press('End')
      await expect(page.getByTestId(`simulation-timeline-state-${simulation.states.length - 1}`)).toBeFocused()
      await page.getByTestId('simulation-timeline-changes-tab').press('End')
      await expect(page.getByTestId('simulation-timeline-run-tab')).toBeFocused()
      await noHints(page)
      await page.screenshot({ path: testInfo.outputPath('simulation-keyboard.png') })
      await page.keyboard.press('Escape')
      await expect(toolbar).toBeHidden()
      await expect(historyEntry).toBeFocused()
      await openHistory('fuzzing')
      await page.getByTestId(`open-fuzzing-run-${fuzzRunId}`).press('Enter')
      const fuzzResult = page.getByTestId('fuzzing-result-dialog')
      await expect(fuzzResult.getByRole('dialog')).toBeFocused()
      await noHints(page)
      await page.keyboard.press('Escape')
      await expect(fuzzResult).toBeHidden()
      await expect(historyEntry).toBeFocused()
      await openHistory('fuzzing')
      await page.getByTestId(`open-fuzzing-run-${fuzzRunId}`).click()
      await fuzzResult.getByTestId(`replay-fuzzing-finding-${fuzz.findings[0].id}`).press('Enter')
      await expect(toolbar).toBeFocused()
      await noHints(page)
      await expectControlsReachable(page, 'trace-timeline')
      await page.getByTestId('trace-timeline-close').press('Enter')
      await expect(toolbar).toBeHidden()
      await expect(historyEntry).toBeFocused()
      await page.keyboard.press('Shift+Tab')
      await expect(page.getByTestId('open-verification-panel')).toBeFocused()
      await page.keyboard.press('Tab')
      await expect(historyEntry).toBeFocused()
    })
  }
}
