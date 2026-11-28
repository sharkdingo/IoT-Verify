import type { Page } from '@playwright/test'
import { unwrap } from './support/apiResponse'
import type { TraceState } from '../src/types/verify'
import { apiBaseURL, createAuthenticatedUser, expect, test } from './support/auth'
import { selectHistoryResultType } from './support/historyFilter'
import { holdHistoryReply } from './support/historyReply'

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

for (const locale of ['zh-CN']) {
  for (const width of [320]) {
    test(`history result and playback focus in ${locale} at ${width}px`, async ({ page, request }, testInfo) => {
      test.setTimeout(90_000)
      const auth = await createAuthenticatedUser(request)
      const headers = { Authorization: `Bearer ${auth.token}` }
      const post = async (path: string, data: unknown) => unwrap(await request.post(`${apiBaseURL}/api/${path}`, { headers, data }))
      await post('board/nodes', { devices: [{ id: 'replay_light', templateName: 'Light',
        label: '历史回放测试灯 / Historical playback test light', position: { x: 180, y: 160 },
        width: 176, height: 128, state: 'off', variables: [], privacies: [] }],
        environmentVariablePatches: [{ name: 'illuminance', value: '17', trust: 'untrusted', privacy: 'public' }] })
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
      // The solver may choose several legal illuminance trajectories. Supply a saved transition
      // with a visible delta while preserving the real run identity and frozen variable provenance.
      for (const path of [`verify/traces/${trace.id}`, `simulate/traces/${simulation.id}`]) {
        await page.route(`**/api/${path}`, async route => {
          const response = await route.fetch()
          const body = await response.json()
          expect(response.ok(), JSON.stringify(body)).toBe(true)
          const states: TraceState[] = body.data.states
          expect(states.length).toBeGreaterThan(1)
          for (const [index, state] of states.entries()) {
            const illuminance = state.envVariables?.find(variable => variable.name === 'illuminance')
            expect(illuminance).toBeDefined()
            illuminance!.value = index === 0 ? '17' : '18'
          }
          await route.fulfill({ response, json: body })
        })
      }
      const expectFrozenChanges = async (prefix: string) => {
        const step = page.getByTestId(`${prefix}-step-input`)
        await step.fill('2')
        await step.press('Enter')
        const timeline = page.getByTestId(prefix)
        await expect(timeline).toHaveAttribute('data-selected-state-index', '1')
        const changes = timeline.getByTestId('playback-changes-panel')
        await expect(changes.getByTestId('playback-change-environment')).toContainText(/17\s*→\s*18/)
        await expect(changes.getByTestId('playback-change-devices')).toContainText('Historical playback test light')
      }
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
      await expect(page.getByTestId('trace-timeline-host')).toHaveAccessibleName('反例回放')
      await page.screenshot({ path: testInfo.outputPath('counterexample-focused.png') })
      await page.keyboard.press('Tab')
      await expect(page.getByTestId('trace-timeline-play')).toBeFocused()
      await expect(page.locator('.iot-hint-tooltip-popper:visible')).toBeVisible()
      await page.getByTestId(`trace-timeline-state-${trace.states.length - 1}`).press('Home')
      await expect(page.getByTestId('trace-timeline-state-0')).toBeFocused()
      await page.keyboard.press('End')
      await expect(page.getByTestId(`trace-timeline-state-${trace.states.length - 1}`)).toBeFocused()
      await expectFrozenChanges('trace-timeline')
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
      const heldSimulation = await holdHistoryReply(page, `/api/simulate/traces/${simulation.id}`)
      const replaySimulation = page.getByTestId(`replay-simulation-trace-${simulation.id}`)
      try {
        await Promise.all([heldSimulation.requested, replaySimulation.press('Enter')])
        await page.keyboard.press('Escape')
        await expect(page.getByTestId('trace-history-panel')).toBeHidden()
      } finally {
        heldSimulation.release()
      }
      expect((await heldSimulation.delivered()).ok()).toBe(true)
      await expect(toolbar).toBeHidden()
      await expect(historyEntry).toBeFocused()
      await openHistory('simulation')
      await replaySimulation.press('Enter')
      await expect(toolbar).toBeFocused()
      await noHints(page)
      await expectControlsReachable(page, 'simulation-timeline')
      await page.getByTestId('simulation-timeline-state-0').press('End')
      await expect(page.getByTestId(`simulation-timeline-state-${simulation.states.length - 1}`)).toBeFocused()
      await expectFrozenChanges('simulation-timeline')
      await page.getByTestId('simulation-timeline-changes-tab').press('End')
      await expect(page.getByTestId('simulation-timeline-run-tab')).toBeFocused()
      await noHints(page)
      await page.screenshot({ path: testInfo.outputPath('simulation-keyboard.png') })
      await page.keyboard.press('Escape')
      await expect(toolbar).toBeHidden()
      await expect(historyEntry).toBeFocused()
      await openHistory('fuzzing')
      const fuzzResult = page.getByTestId('fuzzing-result-dialog')
      const heldRun = await holdHistoryReply(page, `/api/fuzz/runs/${fuzzRunId}`)
      try {
        await page.getByTestId(`open-fuzzing-run-${fuzzRunId}`).press('Enter')
        await heldRun.requested
        await expect(fuzzResult.getByRole('dialog')).toBeFocused()
        await noHints(page)
        await page.keyboard.press('Escape')
        await expect(fuzzResult).toBeHidden()
        await expect(historyEntry).toBeFocused()
      } finally {
        heldRun.release()
      }
      expect((await heldRun.delivered()).ok()).toBe(true)
      await expect(fuzzResult).toBeHidden()
      await expect(historyEntry).toBeFocused()
      await openHistory('fuzzing')
      await page.getByTestId(`open-fuzzing-run-${fuzzRunId}`).click()
      await fuzzResult.getByTestId(`replay-fuzzing-finding-${fuzz.findings[0].id}`).press('Enter')
      await expect(toolbar).toBeFocused()
      await noHints(page)
      await expectControlsReachable(page, 'trace-timeline')
      await expect(page.getByTestId('trace-timeline-host')).toHaveAccessibleName('候选反例回放')
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
