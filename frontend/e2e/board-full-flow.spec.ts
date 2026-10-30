import { expandCreation } from './support/controlCenter'
import { type APIRequestContext, type Page } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import {
  apiBaseURL,
  createAuthenticatedUser,
  expect,
  test,
  type AuthUser
} from './support/auth'
import { clickUnderTooltip, dismissHintTooltips } from './support/tooltips'
import { selectHistoryResultType } from './support/historyFilter'
import { expectFooterUsable } from './support/footerLayout'

const authHeaders = (auth: AuthUser) => ({
  Authorization: `Bearer ${auth.token}`
})

const fixRequestUrl = (traceId: number) =>
  `${apiBaseURL}/api/verify/traces/${traceId}/fix?requestId=${crypto.randomUUID()}`

// A strategy lists every minimal repair it verified, so a scenario names the repair it expects instead
// of taking whichever option the search happened to reach first.
const listedRepair = (fix: any, strategy: string, isExpected: (suggestion: any) => boolean) => {
  const options = fix.suggestions.filter((suggestion: any) => suggestion.strategy === strategy)
  const repair = options.find(isExpected)
  expect(repair, `expected repair not among the ${strategy} options: ${JSON.stringify(options)}`).toBeTruthy()
  return repair
}

const unwrap = async <T>(response: Awaited<ReturnType<APIRequestContext['get']>>): Promise<T> => {
  expect(response.ok(), await response.text()).toBeTruthy()
  const body = await response.json()
  expect(body.code, JSON.stringify(body)).toBe(200)
  return body.data as T
}

const openWorkspace = async (page: Page, auth: AuthUser, locale = 'en', theme = 'light') => {
  await page.addInitScript(({ token, user, locale, theme }) => {
    window.localStorage.setItem('iot_verify_token', token)
    window.localStorage.setItem('iot_verify_user', JSON.stringify(user))
    window.localStorage.setItem('iot_verify_theme', theme)
    window.localStorage.setItem('locale', locale)
  }, {
    token: auth.token,
    locale,
    theme,
    user: {
      userId: auth.userId,
      phone: auth.phone,
      username: auth.username
    }
  })

  await page.goto('/#/board')
  await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
}

const waitForApi = async <T>(
  request: APIRequestContext,
  auth: AuthUser,
  path: string,
  predicate: (value: T) => boolean,
  timeoutMs = 30_000
): Promise<T> => {
  const deadline = Date.now() + timeoutMs
  let latest: T | undefined

  while (Date.now() < deadline) {
    const response = await request.get(`${apiBaseURL}${path}`, { headers: authHeaders(auth) })
    latest = await unwrap<T>(response)
    if (predicate(latest)) {
      return latest
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }

  throw new Error(`Timed out waiting for ${path}; latest=${JSON.stringify(latest)}`)
}

const saveEmptyBoard = async (request: APIRequestContext, auth: AuthUser) => {
  const previewResponse = await request.get(`${apiBaseURL}/api/board/replacement-preview`, {
    headers: authHeaders(auth)
  })
  const preview = await unwrap<{ impactToken: string }>(previewResponse)
  const response = await request.post(`${apiBaseURL}/api/board/batch`, {
    headers: authHeaders(auth),
    data: {
      impactToken: preview.impactToken,
      nodes: [],
      environmentVariables: [],
      rules: [],
      specs: [],
      templateSnapshots: []
    }
  })
  expect(response.ok(), await response.text()).toBeTruthy()
}

const waitForTemplateOption = async (page: Page, templateName: string) => {
  await page.waitForFunction((name) => {
    const select = document.querySelector<HTMLSelectElement>('[data-testid="single-device-template"]')
    return !!select && Array.from(select.options).some(option => option.value === name)
  }, templateName, { timeout: 30_000 })
}

const openControlSection = async (page: Page, section: 'devices' | 'templates' | 'rules' | 'specs') => {
  const tab = page.getByTestId(`control-tab-${section}`)
  const panel = page.getByTestId(`control-section-${section}`)

  for (let attempt = 0; attempt < 5; attempt += 1) {
    await tab.click()
    try {
      await expect(panel).toBeVisible({ timeout: 2_000 })
      await expandCreation(page, section)
      return
    } catch {
      await page.waitForTimeout(300)
    }
  }

  await expect(panel).toBeVisible({ timeout: 5_000 })
  await expandCreation(page, section)
}

const addDeviceViaLeftPanel = async (
  page: Page,
  request: APIRequestContext,
  auth: AuthUser,
  templateName: string,
  label: string
) => {
  await openControlSection(page, 'devices')
  await waitForTemplateOption(page, templateName)
  await page.getByTestId('single-device-template').selectOption(templateName)
  await page.getByTestId('single-device-name').fill(label)
  await page.getByTestId('single-device-create').click()

  const nodes = await waitForApi<any[]>(request, auth, '/api/board/nodes',
    value => value.some(node => node.label === label))
  const node = nodes.find(item => item.label === label)
  expect(node.templateName).toBe(templateName)
  return node
}

const addMotionRuleViaControlCenter = async (
  page: Page,
  request: APIRequestContext,
  auth: AuthUser,
  motionId: string,
  targetId: string,
  action: string,
  name: string,
  expectedRuleCount: number
) => {
  await openControlSection(page, 'rules')
  await expect(page.getByTestId('rule-builder')).toBeVisible()

  await page.locator('#rule-builder-name').fill(name)
  await page.locator('#rule-source-device').selectOption(motionId)
  await page.locator('#rule-source-type').selectOption('variable')
  await page.locator('#rule-source-variable').selectOption('motion')
  await page.locator('#rule-source-condition').selectOption('=')
  await page.locator('#rule-source-value').selectOption('active')

  const addSource = page.getByTestId('rule-add-source')
  await expect(addSource).toBeEnabled()
  await addSource.click()

  await page.locator('#rule-target-device').selectOption(targetId)
  await page.locator('#rule-target-action').selectOption(action)
  await page.getByTestId('rule-save').click()
  await expect(page.locator('#rule-builder-name')).toHaveValue('', { timeout: 10_000 })
  await expect(page.getByTestId('rule-builder')).toBeVisible()

  return waitForApi<any[]>(request, auth, '/api/board/rules', rules => rules.length === expectedRuleCount)
}

/**
 * Asserts the required-reading gate for a shared variable: both readings offered, neither preselected, Save
 * blocked, and the reason NAMES the reading. Passed to `fillSpecCondition` as its `beforeVariableSource`
 * hook, so the gate is proven inside a flow that then goes on to succeed — the suite would otherwise only
 * exercise the happy path it was patched into.
 */
const assertVariableSourceGate = async (page: Page) => {
  await expect(page.getByTestId('spec-condition-variable-source-environment')).toBeVisible()
  await expect(page.getByTestId('spec-condition-variable-source-reported')).toBeVisible()
  // The compromise divergence is explained where the decision is made, not only in the docs.
  await expect(page.getByTestId('spec-condition-variable-source-help')).toBeVisible()
  // And whether THIS reading can actually diverge. `motion` on a Motion Detector is declared
  // `FalsifiableWhenCompromised: true`, so the affirmative branch must be the one shown — asserting mere
  // visibility would pass on the opposite text, which tells the user the choice is inert here.
  await expect(page.getByTestId('spec-condition-variable-source-help'))
    .toHaveText(/allows the reading to be falsified after compromise/i)
  await expect(page.getByTestId('spec-condition-save')).toBeDisabled()
  // The reason names the reading. A bare visibility check would pass for the wrong reason, because the
  // value is also still empty at this point.
  await expect(page.getByTestId('spec-condition-blocked-reason'))
    .toHaveText(/environment value|device-reported value/i)
}

const fillSpecCondition = async (
  page: Page,
  deviceId: string,
  type: string,
  key: string | null,
  relation: string,
  value: string,
  /**
   * Which value a `variable` condition asks about — `environment` for the shared pool value, `reported`
   * for what this device said. Required for `variable`: nothing is preselected, so Save stays disabled
   * until one is picked. The radios only render once a key is chosen, hence the ordering below. A
   * device-local variable offers only `reported` and auto-selects it, so passing nothing is correct there.
   */
  variableSource?: 'environment' | 'reported',
  /**
   * Optional hook run after the key is chosen and before the reading is — the only moment the
   * required-reading gate is observable without leaving the dialog half-filled.
   */
  beforeVariableSource?: (page: Page) => Promise<void>
) => {
  await expect(page.getByTestId('spec-condition-dialog')).toBeVisible()
  await page.getByTestId('spec-condition-device').selectOption(deviceId)
  await page.getByTestId('spec-condition-type').selectOption(type)
  if (key) {
    await page.getByTestId('spec-condition-key').selectOption(key)
  }
  if (beforeVariableSource) {
    await beforeVariableSource(page)
  }
  if (variableSource) {
    await page.getByTestId(`spec-condition-variable-source-${variableSource}`).click()
  }
  await page.getByTestId('spec-condition-relation').selectOption(relation)
  const valueControl = page.locator('[data-testid="spec-condition-value"]:visible')
  const tagName = await valueControl.evaluate(element => element.tagName.toLowerCase())
  if (tagName === 'select') {
    await valueControl.selectOption(value)
  } else {
    await valueControl.fill(value)
  }
  await page.getByTestId('spec-condition-save').click()
  await expect(page.getByTestId('spec-condition-dialog')).toBeHidden({ timeout: 10_000 })
}

test.describe('board full-stack NuSMV user flow', () => {
  test.setTimeout(180_000)

  const targetedRepairScenarios = [
    {
      name: 'coupled climate thresholds',
      baseFile: 'default-climate-conflict-scene.json',
      strategy: 'parameter',
      counts: { devices: 2, environment: 2, rules: 2, specs: 2 },
      configure: (scene: any) => {
        scene.rules = [{
          name: 'When temperature is at least 28, heat the room',
          sources: [{
            fromId: 'temperature_1',
            fromApi: 'temperature',
            itemType: 'variable',
            relation: '>=',
            value: '28'
          }],
          toId: 'ac_1',
          toApi: 'heat'
        }, {
          name: 'Otherwise, turn the air conditioner off',
          sources: [{
            fromId: 'temperature_1',
            fromApi: 'temperature',
            itemType: 'variable',
            relation: '<',
            value: '35'
          }],
          toId: 'ac_1',
          toApi: 'off'
        }]
        scene.specs = [{ name: 'Safety requirement',
          templateId: '4',
          aConditions: [],
          ifConditions: [{
            deviceId: 'temperature_1', targetType: 'variable', variableSource: 'environment', key: 'temperature', relation: '<', value: '35'
          }],
          thenConditions: [{
            deviceId: 'ac_1', targetType: 'mode', key: 'HvacMode', relation: '=', value: 'off'
          }]
        }, { name: 'Safety requirement',
          templateId: '4',
          aConditions: [],
          ifConditions: [{
            deviceId: 'temperature_1', targetType: 'variable', variableSource: 'environment', key: 'temperature', relation: '>=', value: '35'
          }],
          thenConditions: [{
            deviceId: 'ac_1', targetType: 'mode', key: 'HvacMode', relation: '=', value: 'heat'
          }]
        }]
      },
      assertFix: (fix: any) => {
        expect(fix.parameterTargets.length).toBeGreaterThan(0)
      },
      isExpectedRepair: (suggestion: any) => suggestion.parameterAdjustments.some((adjustment: any) =>
        adjustment.attribute === 'temperature' && adjustment.originalValue === '28' && adjustment.newValue === '35')
    }
  ] as const

  // Exercise each repair strategy once; locale/viewport coverage belongs to the UI journeys.
  for (const scenario of targetedRepairScenarios) {
    const locale = 'zh-CN'
    const width = 320
    test(`finds and applies a ${scenario.strategy} repair for ${scenario.name} in ${locale} at ${width}px`, async ({ page, request }, testInfo) => {
      const auth = await createAuthenticatedUser(request)
      const chinese = locale === 'zh-CN'
      await page.setViewportSize({ width, height: width === 320 ? 500 : 1080 })
      await saveEmptyBoard(request, auth)
      await openWorkspace(page, auth, locale, width === 320 ? 'dark' : 'light')

      const basePath = path.resolve(process.cwd(), '..', 'docs', 'examples', scenario.baseFile)
      const scene = JSON.parse(fs.readFileSync(basePath, 'utf8'))
      scenario.configure(scene)
      await page.getByTestId('scene-import-file').setInputFiles({
        name: `fix-${scenario.strategy}-scenario.json`,
        mimeType: 'application/json',
        buffer: Buffer.from(JSON.stringify(scene))
      })
      await page.getByRole('dialog', { name: chinese ? '替换当前场景？' : 'Replace the current scene?' })
        .getByRole('button', { name: chinese ? '替换场景' : 'Replace scene', exact: true })
        .click()

      await waitForApi<any[]>(request, auth, '/api/board/nodes',
        value => value.length === scenario.counts.devices)
      await waitForApi<any[]>(request, auth, '/api/board/environment',
        value => value.length === scenario.counts.environment)
      await waitForApi<any[]>(request, auth, '/api/board/rules',
        value => value.length === scenario.counts.rules)
      await waitForApi<any[]>(request, auth, '/api/board/specs',
        value => value.length === scenario.counts.specs)

      const snapshot = async () => {
        const { nodes, environmentVariables, rules, specifications } = await unwrap<any>(
          await request.get(`${apiBaseURL}/api/board/snapshot`, { headers: authHeaders(auth) })
        )
        return { nodes, environmentVariables, rules, specifications }
      }
      const before = await snapshot()
      const restoreDock = page.getByTestId('restore-action-dock')
      if (await restoreDock.isVisible()) await restoreDock.click()
      await clickUnderTooltip(page, page.getByTestId('open-verification-panel'))
      await page.getByTestId('verification-mode-sync').click()
      const baselineResponsePromise = page.waitForResponse(response =>
        response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/verify')
      await page.getByTestId('run-verification').click()
      const baseline = await unwrap<any>((await baselineResponsePromise) as any)
      expect(baseline).toMatchObject({
        outcome: 'VIOLATED',
        modelComplete: true,
        disabledRuleCount: 0,
        skippedSpecCount: 0,
        modelSnapshot: { specificationCount: scenario.counts.specs }
      })
      expect(baseline.specResults.filter((result: any) => result.outcome === 'VIOLATED')).toHaveLength(1)

      const traces = await waitForApi<any[]>(request, auth, '/api/verify/traces', rows =>
        rows.some(trace => trace.isAttack === false && trace.states?.length >= 2))
      const trace = traces.find(row => row.isAttack === false && row.states?.length >= 2)
      expect(trace).toBeTruthy()

      await page.getByTestId('verification-trace-fix').click()
      const dialog = page.getByTestId('fix-result-dialog')
      await expect(dialog).toBeVisible()
      await dialog.getByTestId('fix-scope-help').focus()
      const help = page.locator('.iot-info-tooltip-popper:not([aria-hidden="true"])').filter({
        hasText: chinese ? '搜索不会修改场景。' : 'Searching does not change the scene.'
      })
      await expect(help).toBeVisible()
      await expect.poll(() => help.evaluate(element => {
        const rect = element.getBoundingClientRect()
        return element.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2))
      })).toBe(true)
      await dialog.getByTestId('fix-scope-help').press('Tab')
      await dialog.getByTestId(`fix-strategy-${scenario.strategy}`).click()
      const fixResponse = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === `/api/verify/traces/${trace.id}/fix`, { timeout: 180_000 })
      await dialog.getByTestId('fix-try-current').click()
      const fix = await unwrap<any>(await fixResponse as any)
      expect(fix).toMatchObject({
        traceId: trace.id,
        violatedSpecId: expect.not.stringMatching(/^UNKNOWN_SPEC$/),
        fixable: true,
        sourceModelComplete: true,
        sourceDisabledRuleCount: 0,
        sourceSkippedSpecCount: 0,
        strategyAttempts: [{ strategy: scenario.strategy, status: 'VERIFIED' }]
      })
      scenario.assertFix(fix)
      if (scenario.strategy === 'parameter') {
        for (const target of fix.parameterTargets) {
          expect(Number.isInteger(target.rulePosition)).toBe(true)
          expect(target.rulePosition).toBeGreaterThan(0)
          const condition = scene.rules[target.rulePosition - 1].sources.find((source: any) => source.fromApi === target.attribute)
          const device = scene.devices.find((node: any) => node.id === condition.fromId)
          expect(target.deviceLabel).toBe(device.label)
        }
        for (const adjustment of fix.suggestions.flatMap((item: any) => item.parameterAdjustments)) {
          const target = fix.parameterTargets.find((item: any) => item.targetId === adjustment.targetId)
          expect(adjustment).toMatchObject({ rulePosition: target.rulePosition, deviceLabel: target.deviceLabel })
        }
      }
      const suggestion = listedRepair(fix, scenario.strategy, scenario.isExpectedRepair)

      await expect(dialog.getByTestId('fix-apply-current')).toBeEnabled()
      if (!chinese) await expect(dialog.getByTestId('fix-fault-summary')).toHaveText(/^Related rules executed: \d+$/)
      await expectFooterUsable(dialog.locator('.iot-dialog__footer'))
      if (scenario.strategy === 'parameter') {
        for (const target of fix.parameterTargets) {
          await expect(dialog.getByTestId('fix-parameter-range-row').filter({
            hasText: `${chinese ? '规则' : 'Rule'} ${target.rulePosition} · ${target.deviceLabel}`
          })).toHaveCount(1)
        }
      }
      const options = fix.suggestions.filter((item: any) => item.strategy === scenario.strategy)
      if (options.length > 1) {
        await dialog.getByTestId('fix-alternative').nth(options.indexOf(suggestion)).getByRole('radio').check()
      }
      const proposal = options.length > 1
        ? dialog.getByTestId('fix-alternative').nth(options.indexOf(suggestion))
        : dialog.getByTestId('fix-verified-proposals')
      if (scenario.strategy === 'parameter') {
        const adjustment = suggestion.parameterAdjustments[0]
        await expect(proposal.getByText(adjustment.originalValue, { exact: true })).toBeVisible()
        const value = proposal.getByText(adjustment.newValue, { exact: true }).first()
        await value.scrollIntoViewIfNeeded()
        await expect(value).toBeInViewport()
      } else {
        const condition = proposal.getByText(chinese ? /添加条件：/ : /Add condition to/).first()
        await expect(condition).toContainText('occupied')
        await expect(condition).toContainText('present')
        await condition.scrollIntoViewIfNeeded()
        await expect(condition).toBeInViewport()
        const wordLines = await condition.evaluate(element => {
          const text = element.firstChild!
          const start = text.textContent!.indexOf('Occupancy')
          const range = document.createRange()
          range.setStart(text, start)
          range.setEnd(text, start + 'Occupancy'.length)
          return range.getClientRects().length
        })
        expect.soft(wordLines, 'ordinary device-name words must not be squeezed into fragments').toBe(1)
      }
      await dismissHintTooltips(page)
      await expectFooterUsable(dialog.locator('.iot-dialog__footer'))
      expect.soft(await proposal.evaluate(element => element.scrollWidth <= element.clientWidth + 1),
        'proposal details must fit without horizontal scrolling').toBe(true)
      const screenshot = testInfo.outputPath(`${scenario.strategy}-repair-preview.png`)
      await page.screenshot({ path: screenshot, fullPage: true })
      await testInfo.attach(`${scenario.strategy}-repair-preview`, { path: screenshot, contentType: 'image/png' })
      const applyResponse = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === `/api/verify/traces/${trace.id}/fix/apply`)
      await dialog.getByTestId('fix-apply-current').click()
      const write = await applyResponse
      expect(write.request().postDataJSON().suggestion).toEqual(suggestion)
      const applied = await unwrap<any>(write as any)
      await expect(dialog).toBeHidden()
      expect(applied).toMatchObject({
        applied: true,
        strategy: scenario.strategy,
        previousRuleCount: scenario.counts.rules,
        currentRuleCount: scenario.counts.rules
      })
      expect(applied.verificationEvidenceReused).toBe(true)
      const after = await snapshot()
      expect(after.nodes).toEqual(before.nodes)
      expect(after.environmentVariables).toEqual(before.environmentVariables)
      expect(after.specifications).toEqual(before.specifications)
      expect(after.rules).not.toEqual(before.rules)

      await page.reload()
      // Reload restores the saved run; its old verdict must stay stale until the current Board is checked.
      await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 30_000 })
      await expect(page.getByTestId('verification-result-stale-banner')).toBeVisible()
      await page.getByTestId('verification-result-reverify').click()
      await expect(page.getByTestId('board-undo')).toBeEnabled()
      await clickUnderTooltip(page, page.getByTestId('board-undo'))
      await expect.poll(snapshot).toEqual(before)
      await expect(page.getByTestId('board-redo')).toBeEnabled()
      await clickUnderTooltip(page, page.getByTestId('board-redo'))
      await expect.poll(snapshot).toEqual(after)
      await expect(page.getByTestId('board-redo')).toBeDisabled()
      await page.getByTestId('verification-mode-sync').click()
      const repairedResponsePromise = page.waitForResponse(response =>
        response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/verify')
      await page.getByTestId('run-verification').click()
      const repaired = await unwrap<any>((await repairedResponsePromise) as any)
      expect(repaired).toMatchObject({
        outcome: 'SATISFIED',
        modelComplete: true,
        disabledRuleCount: 0,
        skippedSpecCount: 0,
        modelSnapshot: { specificationCount: scenario.counts.specs }
      })
      expect(repaired.specResults).toHaveLength(scenario.counts.specs)
      expect(repaired.specResults.every((result: any) => result.outcome === 'SATISFIED')).toBe(true)
      await expect(page.getByTestId('verification-result-dialog')).toBeVisible()
      expect.soft(await page.locator(':is(.el-message--success, .el-message--info):not(.el-message-fade-leave-active)').count(),
        'earlier undo/redo receipts must not cover the new verification result').toBe(0)
      await page.screenshot({ path: testInfo.outputPath(`${scenario.strategy}-after-redo.png`) })
    })
  }

  test('withdraws counterexample actions once an applied fix makes the open verdict stale', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)

    const scenePath = path.resolve(process.cwd(), '..', 'docs', 'examples', 'household-entry-scene.json')
    await page.getByTestId('scene-import-file').setInputFiles(scenePath)
    await page.getByRole('dialog', { name: 'Replace the current scene?' })
      .getByRole('button', { name: 'Replace scene' })
      .click()
    await waitForApi<any[]>(request, auth, '/api/board/rules', rules => rules.length === 3)
    await waitForApi<any[]>(request, auth, '/api/board/specs', specs => specs.length === 3)

    await page.getByTestId('open-verification-panel').click()
    await page.getByTestId('verification-mode-sync').click()
    await page.getByTestId('run-verification').click()
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })

    // The verdict describes the model that was just verified, so it starts fresh and offers Fix.
    await expect(page.getByTestId('verification-result-stale-banner')).toHaveCount(0)
    await expect(page.getByTestId('verification-result-reverify')).toHaveCount(0)
    const traceFix = page.getByTestId('verification-trace-fix').first()
    await expect(traceFix).toBeVisible({ timeout: 30_000 })
    await traceFix.click()
    await expect(page.getByTestId('fix-result-dialog')).toBeVisible({ timeout: 30_000 })
    await expect(page.getByTestId('fix-strategy-remove')).toBeVisible({ timeout: 120_000 })
    await page.getByTestId('fix-strategy-remove').click()
    await page.getByTestId('fix-try-current').click()
    const applyFix = page.getByTestId('fix-apply-current')
    await expect(applyFix).toBeVisible({ timeout: 180_000 })
    await applyFix.click()
    // Removing rules requires an explicit confirmation before the mutation is sent.
    await page.locator('.el-message-box')
      .getByRole('button', { name: /Remove Rules and Apply|移除规则并应用/ })
      .click()

    // Applying the fix mutated the rules while the verification dialog stayed open. The verdict
    // and its counterexamples no longer describe the current board, so the UI must say so and
    // stop offering actions that imply otherwise: no Fix button, and replay is refused.
    await expect(page.getByTestId('fix-result-dialog')).toBeHidden({ timeout: 60_000 })
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible()
    await expect(page.getByTestId('verification-result-stale-banner')).toBeVisible({ timeout: 30_000 })
    await expect(page.getByTestId('verification-trace-fix')).toHaveCount(0)
    await expect(page.getByTestId('verification-result-stale-banner')).toHaveCount(1)
    await expect(page.getByTestId('verification-result-stale-banner')).toContainText(/changed after submission/i)
    await expect(page.getByTestId('verification-trace-fix-unavailable')).toHaveCount(0)

    // The stale result leads back to current-board settings, without resubmitting or reopening its URL.
    let rerunRequests = 0
    page.on('request', request => {
      if (request.method() === 'POST' && /^\/api\/verify(?:\/async)?$/.test(new URL(request.url()).pathname)) {
        rerunRequests += 1
      }
    })
    await page.getByTestId('verification-result-reverify').click()
    await expect(page.getByTestId('verification-result-dialog')).toBeHidden()
    await expect(page.getByTestId('verification-mode-sync')).toHaveAttribute('aria-pressed', 'true')
    await expect(page).not.toHaveURL(/(?:run|trace)=/)
    expect(rerunRequests).toBe(0)
    await page.getByTestId('run-verification').click()
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('verification-result-stale-banner')).toHaveCount(0)
    await expect(page.getByTestId('verification-result-reverify')).toHaveCount(0)
    expect(rerunRequests).toBe(1)
  })

  test('builds a board through left-panel dialogs, then simulates, verifies, replays history, and tracks async tasks', async ({ page, request }, testInfo) => {
    const browserErrors: string[] = []
    page.on('pageerror', error => browserErrors.push(error.message))
    page.on('console', message => {
      if (message.type() === 'error') browserErrors.push(message.text())
    })

    const auth = await createAuthenticatedUser(request)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)

    const motion = await addDeviceViaLeftPanel(page, request, auth, 'Motion Detector', 'motion_entry')
    const camera = await addDeviceViaLeftPanel(page, request, auth, 'Camera', 'privacy_camera')
    const alarm = await addDeviceViaLeftPanel(page, request, auth, 'Alarm', 'night_alarm')
    await expect(page.locator('.device-label').filter({ hasText: 'motion_entry' }).first()).toBeVisible()
    await expect(page.locator('.device-label').filter({ hasText: 'privacy_camera' }).first()).toBeVisible()
    await expect(page.locator('.device-label').filter({ hasText: 'night_alarm' }).first()).toBeVisible()

    const cameraRules = await addMotionRuleViaControlCenter(
      page, request, auth, motion.id, camera.id, 'take photo',
      'motion captures camera evidence', 1)
    expect(cameraRules[0].conditions[0].targetType).toBe('variable')
    expect(cameraRules[0].conditions[0].deviceName).toBe(motion.id)
    expect(cameraRules[0].command.action).toBe('take photo')

    const alarmRules = await addMotionRuleViaControlCenter(
      page, request, auth, motion.id, alarm.id, 'siren',
      'motion triggers audible alarm', 2)
    expect(alarmRules[1].command.deviceName).toBe(alarm.id)

    await openControlSection(page, 'specs')
    await page.getByTestId('spec-name').fill('Safety requirement')
    await page.getByTestId('spec-template-select').selectOption('3')
    await page.getByTestId('spec-add-condition-a').click()
    await fillSpecCondition(page, camera.id, 'state', null, '=', 'taking photo')
    const formulaDetails = page.getByTestId('spec-formula-details')
    await expect(page.getByTestId('spec-description')).toBeVisible()
    await expect(formulaDetails).toHaveJSProperty('open', false)
    await expect(page.getByTestId('spec-formula-expression')).toBeHidden()
    await formulaDetails.locator('summary').press('Enter')
    await expect(page.getByTestId('spec-formula-expression')).toBeVisible()
    await formulaDetails.locator('summary').press('Enter')
    await page.screenshot({ path: testInfo.outputPath('specification-draft.png') })
    await page.getByTestId('spec-create').click()
    await waitForApi<any[]>(request, auth, '/api/board/specs', specs => specs.length === 1)

    await page.getByTestId('spec-name').fill('Safety requirement')

    await page.getByTestId('spec-template-select').selectOption('5')
    await page.getByTestId('spec-add-condition-if').click()

    /*
     * The required reading is asserted inside the helper's own flow rather than by probing the dialog first:
     * an earlier version selected device/type/key up front to check the blocked state, and the subsequent
     * `fillSpecCondition` call then re-entered those selects on an already-dirty dialog and never saved.
     * `assertVariableSourceGate` runs between choosing the key and choosing the reading, which is exactly
     * the moment the gate is observable, without leaving the dialog in a half-filled state.
     */
    await fillSpecCondition(
      page, motion.id, 'variable', 'motion', '=', 'active', 'environment',
      assertVariableSourceGate
    )
    // The saved row names its reading, so the list cannot show two identical-looking conditions.
    await expect(page.getByTestId('spec-condition-row-variable-source').first()).toBeVisible()
    await page.getByTestId('spec-add-condition-then').click()
    await fillSpecCondition(page, alarm.id, 'mode', 'AlertState', '=', 'siren')
    await page.getByTestId('spec-create').click()
    const specs = await waitForApi<any[]>(request, auth, '/api/board/specs', value => value.length === 2)
    // Persisted as chosen, not silently defaulted or dropped between the dialog and the row.
    expect(specs[1].ifConditions[0].variableSource).toBe('environment')
    expect(specs.map(spec => spec.templateId)).toEqual(['3', '5'])
    const focusedSpec = page.locator(`[data-spec-id="${specs[1].id}"]`)
    await expect(focusedSpec).toBeVisible()
    await expect(focusedSpec).toBeFocused()
    await expect(focusedSpec).toHaveCSS('outline-style', 'solid')
    await expect(focusedSpec).toHaveCSS('outline-width', '2px')

    await page.getByTestId('open-simulation-panel').click()
    await page.getByTestId('simulation-mode-sync').click()
    await page.getByTestId('run-simulation').click()
    await expect(page.getByTestId('simulation-timeline')).toBeVisible({ timeout: 45_000 })
    await expect(page.getByTestId('simulation-timeline-state-0')).toBeVisible()
    await page.getByTestId('simulation-timeline-play').click()
    await page.waitForTimeout(800)
    await page.getByTestId('simulation-timeline-close').click()
    await waitForApi<any[]>(request, auth, '/api/simulate/traces', traces => traces.length >= 1)

    await page.getByTestId('open-verification-panel').click()
    await page.getByTestId('verification-mode-sync').click()
    await page.getByTestId('run-verification').click()
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('verification-result-dialog')).toContainText(/violation|failed|unsafe/i)
    await page.getByTestId('close-verification-result').click()
    const traces = await waitForApi<any[]>(request, auth, '/api/verify/traces',
      value => value.some(trace => Array.isArray(trace.states) && trace.states.length > 0))
    expect(JSON.stringify(traces[0].violatedSpec)).toContain('taking photo')

    const fixResponse = await request.post(fixRequestUrl(traces[0].id), {
      headers: authHeaders(auth),
      data: { strategies: ['remove', 'condition'] }
    })
    const fix = await unwrap<any>(fixResponse)
    expect(fix.traceId).toBe(traces[0].id)
    expect(Array.isArray(fix.faultRules)).toBeTruthy()
    expect(fix.faultRules.length).toBeGreaterThanOrEqual(1)
    expect(Array.isArray(fix.suggestions)).toBeTruthy()
    expect(fix.suggestions.length).toBeGreaterThanOrEqual(1)

    const asyncSimulationResponsePromise = page.waitForResponse(response =>
      response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/simulate/async')
    await page.getByTestId('open-simulation-panel').click()
    await page.getByTestId('simulation-mode-async').click()
    await page.getByTestId('run-simulation').click()
    const asyncSimulationTask = await unwrap<{ id: number }>(
      (await asyncSimulationResponsePromise) as any)
    const completedSimulationTask = await waitForApi<any>(
      request,
      auth,
      `/api/simulate/tasks/${asyncSimulationTask.id}`,
      task => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(task.status),
      120_000)
    expect(completedSimulationTask.status).toBe('COMPLETED')
    await expect(page.getByTestId('open-simulation-panel'))
      .toHaveAttribute('aria-label', 'Open observation settings', { timeout: 30_000 })
    if (await page.getByTestId('simulation-timeline').isVisible().catch(() => false)) {
      await page.getByTestId('simulation-timeline-close').click()
    }
    await expect(page.getByTestId('open-verification-panel')).toBeEnabled({ timeout: 30_000 })

    const asyncVerificationResponsePromise = page.waitForResponse(response =>
      response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/verify/async')
    await page.getByTestId('open-verification-panel').click()
    await page.getByTestId('verification-mode-async').click()
    await page.getByTestId('run-verification').click()
    const asyncVerificationTask = await unwrap<{ id: number }>(
      (await asyncVerificationResponsePromise) as any)
    const completedVerificationTask = await waitForApi<any>(
      request,
      auth,
      `/api/verify/tasks/${asyncVerificationTask.id}`,
      task => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(task.status),
      120_000)
    expect(completedVerificationTask.status).toBe('COMPLETED')
    await expect(page.getByTestId('open-verification-panel'))
      .toHaveAttribute('aria-label', 'Open verification settings', { timeout: 30_000 })
    if (await page.getByTestId('verification-result-dialog').isVisible().catch(() => false)) {
      await page.getByTestId('close-verification-result').click()
    }

    await page.getByTestId('open-history-panel').click()
    await expect(page.getByTestId('trace-history-panel')).toBeVisible({ timeout: 15_000 })
    await page.getByTestId('history-layer-results').click()
    await selectHistoryResultType(page, 'verification')
    await page.locator('[data-testid^="run-evidence-verification-"]').first().locator('summary').click()
    const viewTrace = page.locator('[data-testid^="view-verification-trace-"]').first()
    await expect(viewTrace).toBeVisible({ timeout: 30_000 })
    await viewTrace.click()
    await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('trace-timeline-state-0')).toBeVisible()
    await page.getByTestId('trace-timeline-state-0').click()

    const historyStep = page.getByTestId('trace-timeline-step-input')
    const historyStateCount = Number(await historyStep.getAttribute('max'))
    expect(historyStateCount).toBeGreaterThan(1)
    await page.getByTestId('trace-timeline-play').click()
    await expect(historyStep).toHaveValue(String(historyStateCount), { timeout: 30_000 })
    await expect(page.getByTestId('trace-timeline-play')).toHaveAttribute('aria-pressed', 'false')
    await page.getByTestId('trace-timeline-close').click()
    await expect(page.getByTestId('trace-timeline')).toBeHidden()

    expect(browserErrors).toEqual([])
  })
})
