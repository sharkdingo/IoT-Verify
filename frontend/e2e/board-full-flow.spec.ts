import { type APIRequestContext, type Page, type Route } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import {
  apiBaseURL,
  createAuthenticatedUser,
  expect,
  test,
  type AuthUser
} from './support/auth'
import { clickUnderTooltip } from './support/tooltips'

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

const openWorkspace = async (page: Page, auth: AuthUser) => {
  await page.addInitScript(({ token, user }) => {
    window.localStorage.setItem('iot_verify_token', token)
    window.localStorage.setItem('iot_verify_user', JSON.stringify(user))
    window.localStorage.setItem('iot_verify_theme', 'light')
    window.localStorage.setItem('locale', 'en')
  }, {
    token: auth.token,
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

const addOccupancySensor = (scene: any, temperatureValue: string) => {
  scene.templates.push({
    name: 'Occupancy Sensor',
    manifest: {
      Name: 'Occupancy Sensor',
      Description: 'Stable occupancy input for condition repair',
      InternalVariables: [{
        Name: 'occupied',
        IsInside: true,
        FalsifiableWhenCompromised: false,
        Values: ['present', 'absent'],
        Trust: 'trusted',
        Privacy: 'public'
      }],
      // `occupied` is a local enum, so the template validator requires a driver for it: without one
      // it would hold a nondeterministic initial value through every benign run. Binding it to two
      // working states keeps it device-local, which is what the specs below read via `reported`.
      Modes: ['OccupancyState'],
      InitState: 'absent',
      WorkingStates: [{
        Name: 'absent',
        Description: '',
        Trust: 'trusted',
        Privacy: 'public',
        Dynamics: [{ VariableName: 'occupied', Value: 'absent' }]
      }, {
        Name: 'present',
        Description: '',
        Trust: 'trusted',
        Privacy: 'public',
        Dynamics: [{ VariableName: 'occupied', Value: 'present' }]
      }],
      Transitions: [],
      APIs: []
    }
  })
  scene.devices.push({
    id: 'occupancy_1',
    templateName: 'Occupancy Sensor',
    label: 'Living-room Occupancy Sensor',
    position: { x: 90, y: 320 },
    width: 176,
    height: 128,
    state: 'absent',
    currentStateTrust: 'trusted',
    currentStatePrivacy: 'public',
    variables: [{ name: 'occupied', value: 'absent', trust: 'trusted' }]
  })
  scene.environmentVariables.find((row: any) => row.name === 'temperature').value = temperatureValue
}

const configureRedundantUnsafeHeatingScene = (scene: any) => {
  addOccupancySensor(scene, '30')
  scene.rules = [{
    name: 'Unsafe primary heating rule',
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
    name: 'Unsafe redundant secondary heating rule',
    sources: [{
      fromId: 'temperature_1',
      fromApi: 'temperature',
      itemType: 'variable',
      relation: '>=',
      value: '29'
    }],
    toId: 'ac_1',
    toApi: 'heat'
  }, {
    name: 'At or below 35, turn heating off',
    sources: [{
      fromId: 'temperature_1',
      fromApi: 'temperature',
      itemType: 'variable',
      relation: '<=',
      value: '35'
    }],
    toId: 'ac_1',
    toApi: 'off'
  }]
  scene.specs = [{
    templateId: '3',
    aConditions: [{
      deviceId: 'occupancy_1', targetType: 'variable', variableSource: 'reported', key: 'occupied', relation: '=', value: 'absent'
    }, {
      deviceId: 'temperature_1', targetType: 'variable', variableSource: 'environment', key: 'temperature', relation: '<', value: '35'
    }, {
      deviceId: 'ac_1', targetType: 'mode', key: 'HvacMode', relation: '=', value: 'heat'
    }],
    ifConditions: [],
    thenConditions: []
  }]
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
      return
    } catch {
      await page.waitForTimeout(300)
    }
  }

  await expect(panel).toBeVisible({ timeout: 5_000 })
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

const addMotionRuleViaDialog = async (
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
  await page.getByTestId('open-rule-builder').click()
  await expect(page.getByTestId('rule-builder-dialog')).toBeVisible()

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
  await expect(page.getByTestId('rule-builder-dialog')).toBeHidden({ timeout: 10_000 })

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
  await expect(page.getByTestId('spec-condition-variable-source-falsifiable'))
    .toHaveText(/declares falsifiable when compromised/i)
  await expect(page.getByTestId('spec-condition-save')).toBeDisabled()
  // The reason names the reading. A bare visibility check would pass for the wrong reason, because the
  // value is also still empty at this point.
  await expect(page.getByTestId('spec-condition-blocked-reason'))
    .toHaveText(/actual value in the home|device reports/i)
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

  const additionalDefaultScenarios = [
    {
      name: 'fire evacuation',
      file: 'default-fire-evacuation-scene.json',
      counts: { devices: 4, environment: 2, rules: 3, specs: 5 },
      baselineViolations: 1,
      attackViolations: 4,
      animatedState: 'unlocked',
      removedRule: 'When the alarm sounds, unlock the front door for evacuation',
      repairedRuleCount: 2
    },
    {
      name: 'climate conflict',
      file: 'default-climate-conflict-scene.json',
      counts: { devices: 2, environment: 2, rules: 2, specs: 4 },
      baselineViolations: 2,
      attackViolations: 3,
      animatedState: 'heat',
      removedRule: 'Unsafe conflicting rule: when the room is hot, heat the living room',
      repairedRuleCount: 1
    },
    {
      name: 'RFID access',
      file: 'default-rfid-access-scene.json',
      counts: { devices: 3, environment: 0, rules: 2, specs: 5 },
      baselineViolations: 0,
      attackViolations: 3,
      animatedState: 'unlocked',
      removedRule: null,
      repairedRuleCount: 2
    }
  ] as const

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
        scene.specs = [{
          templateId: '4',
          aConditions: [],
          ifConditions: [{
            deviceId: 'temperature_1', targetType: 'variable', variableSource: 'environment', key: 'temperature', relation: '<', value: '35'
          }],
          thenConditions: [{
            deviceId: 'ac_1', targetType: 'mode', key: 'HvacMode', relation: '=', value: 'off'
          }]
        }, {
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
    },
    {
      name: 'unoccupied-room heating safety',
      baseFile: 'default-climate-conflict-scene.json',
      strategy: 'condition',
      counts: { devices: 3, environment: 2, rules: 1, specs: 1 },
      configure: (scene: any) => {
        addOccupancySensor(scene, '17')
        scene.rules = [{
          name: 'When it is cold, heat the room',
          sources: [{
            fromId: 'temperature_1',
            fromApi: 'temperature',
            itemType: 'variable',
            relation: '<=',
            value: '18'
          }],
          toId: 'ac_1',
          toApi: 'heat'
        }]
        scene.specs = [{
          templateId: '3',
          aConditions: [{
            deviceId: 'occupancy_1', targetType: 'variable', variableSource: 'reported', key: 'occupied', relation: '=', value: 'absent'
          }, {
            deviceId: 'ac_1', targetType: 'mode', key: 'HvacMode', relation: '=', value: 'heat'
          }],
          ifConditions: [],
          thenConditions: []
        }]
      },
      assertFix: (fix: any) => {
        expect(fix.parameterTargets).toEqual([])
      },
      isExpectedRepair: (suggestion: any) => suggestion.conditionAdjustments.some((adjustment: any) =>
        adjustment.action === 'add'
          && adjustment.attribute === 'occupied'
          && adjustment.targetType === 'variable'
          && adjustment.relation === '='
          && adjustment.value === 'present')
    }
  ] as const

  const coordinatedRepairScenarios = [
    {
      strategy: 'parameter',
      repairedRuleCount: 3,
      isExpectedRepair: (suggestion: any) => suggestion.parameterAdjustments.length === 2
        && suggestion.parameterAdjustments.every((adjustment: any) =>
          adjustment.attribute === 'temperature' && Number(adjustment.newValue) >= 36)
    },
    {
      strategy: 'condition',
      repairedRuleCount: 3,
      isExpectedRepair: (suggestion: any) => suggestion.conditionAdjustments.length === 2
        && suggestion.conditionAdjustments.every((adjustment: any) =>
          adjustment.action === 'add'
            && adjustment.attribute === 'occupied'
            && adjustment.value === 'present')
    },
    {
      strategy: 'remove',
      repairedRuleCount: 1,
      isExpectedRepair: (suggestion: any) => JSON.stringify(suggestion.removedRuleDescriptions) === JSON.stringify([
        'Unsafe primary heating rule',
        'Unsafe redundant secondary heating rule'
      ])
    }
  ] as const

  for (const scenario of additionalDefaultScenarios) {
    test(`checks the ${scenario.name} default-template scenario end to end`, async ({ page, request }) => {
      const auth = await createAuthenticatedUser(request)
      await saveEmptyBoard(request, auth)
      await openWorkspace(page, auth)

      const scenePath = path.resolve(process.cwd(), '..', 'docs', 'examples', scenario.file)
      await page.getByTestId('scene-import-file').setInputFiles(scenePath)
      await page.getByRole('dialog', { name: 'Confirm Full Scene Replacement' })
        .getByRole('button', { name: 'Replace in full' })
        .click()

      await waitForApi<any[]>(request, auth, '/api/board/nodes',
        value => value.length === scenario.counts.devices)
      await waitForApi<any[]>(request, auth, '/api/board/environment',
        value => value.length === scenario.counts.environment)
      await waitForApi<any[]>(request, auth, '/api/board/rules',
        value => value.length === scenario.counts.rules)
      await waitForApi<any[]>(request, auth, '/api/board/specs',
        value => value.length === scenario.counts.specs)

      await page.getByTestId('open-simulation-panel').click()
      await page.getByTestId('simulation-mode-sync').click()
      await page.getByTestId('simulation-privacy-toggle').click()
      const simulationResponsePromise = page.waitForResponse(response =>
        response.request().method() === 'POST'
          && new URL(response.url()).pathname === '/api/simulate/traces')
      await page.getByTestId('run-simulation').click()
      const simulation = await unwrap<any>(await simulationResponsePromise as any)
      expect(simulation.modelComplete).toBe(true)
      expect(simulation.disabledRuleCount).toBe(0)
      expect(simulation.states.length).toBeGreaterThanOrEqual(2)
      expect(JSON.stringify(simulation.states)).toContain(scenario.animatedState)
      await expect(page.getByTestId('simulation-timeline')).toBeVisible({ timeout: 45_000 })
      await page.getByTestId('simulation-timeline-play').click()
      await page.waitForTimeout(300)
      await page.getByTestId('simulation-timeline-close').click()

      await page.getByTestId('open-verification-panel').click()
      await page.getByTestId('verification-mode-sync').click()
      const baselineResponsePromise = page.waitForResponse(response =>
        response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/verify')
      await page.getByTestId('run-verification').click()
      const baseline = await unwrap<any>(await baselineResponsePromise as any)
      expect(baseline.modelComplete).toBe(true)
      expect(baseline.disabledRuleCount).toBe(0)
      expect(baseline.skippedSpecCount).toBe(0)
      expect(baseline.specResults).toHaveLength(scenario.counts.specs)
      expect(baseline.specResults.filter((result: any) => result.outcome === 'VIOLATED'))
        .toHaveLength(scenario.baselineViolations)
      await page.getByTestId('close-verification-result').click()

      await page.getByTestId('open-verification-panel').click()
      // The dock button's hint popper covers this switch; see support/tooltips.ts for the measured
      // geometry and why `force: true` made the failure less legible rather than fixing it.
      await clickUnderTooltip(page, page.getByTestId('verification-attack-toggle'))
      await expect(page.getByTestId('verification-attack-toggle'))
        .toHaveAttribute('aria-checked', 'true')
      await page.getByTestId('verification-attack-budget').fill('1')
      const attackResponsePromise = page.waitForResponse(response =>
        response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/verify')
      await page.getByTestId('run-verification').click()
      const attacked = await unwrap<any>(await attackResponsePromise as any)
      expect(attacked.modelComplete).toBe(true)
      expect(attacked.disabledRuleCount).toBe(0)
      expect(attacked.skippedSpecCount).toBe(0)
      expect(attacked.specResults.filter((result: any) => result.outcome === 'VIOLATED'))
        .toHaveLength(scenario.attackViolations)
      await page.getByTestId('close-verification-result').click()

      if (scenario.removedRule) {
        const traces = await waitForApi<any[]>(request, auth, '/api/verify/traces',
          value => value.some(trace => trace.isAttack === false))
        let selectedTrace: any = null
        let selectedFix: any = null
        for (const trace of traces.filter(item => item.isAttack === false)) {
          const fixResponse = await request.post(fixRequestUrl(trace.id), {
            headers: authHeaders(auth),
            data: { strategies: ['remove'] }
          })
          const fix = await unwrap<any>(fixResponse)
          const removal = fix.suggestions?.find((suggestion: any) =>
            suggestion.strategy === 'remove'
              && suggestion.removedRuleDescriptions?.includes(scenario.removedRule))
          if (removal) {
            selectedTrace = trace
            selectedFix = removal
            break
          }
        }
        expect(selectedTrace).toBeTruthy()
        expect(selectedFix.removedRuleDescriptions).toEqual([scenario.removedRule])

        const applyResponse = await request.post(
          `${apiBaseURL}/api/verify/traces/${selectedTrace.id}/fix/apply`,
          {
            headers: authHeaders(auth),
            data: {
              strategy: selectedFix.strategy,
              suggestion: selectedFix,
              suggestionToken: selectedFix.suggestionToken
            }
          }
        )
        const applied = await unwrap<any>(applyResponse)
        expect(applied.currentRuleCount).toBe(scenario.repairedRuleCount)

        await page.reload()
        await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
        await page.getByTestId('open-verification-panel').click()
        await page.getByTestId('verification-mode-sync').click()
        const repairedResponsePromise = page.waitForResponse(response =>
          response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/verify')
        await page.getByTestId('run-verification').click()
        const repaired = await unwrap<any>(await repairedResponsePromise as any)
        expect(repaired.modelComplete).toBe(true)
        expect(repaired.disabledRuleCount).toBe(0)
        expect(repaired.skippedSpecCount).toBe(0)
        expect(repaired.specResults.every((result: any) => result.outcome === 'SATISFIED')).toBe(true)
      }
    })
  }

  for (const scenario of targetedRepairScenarios) {
    test(`finds and applies a ${scenario.strategy} repair for ${scenario.name}`, async ({ page, request }) => {
      const auth = await createAuthenticatedUser(request)
      await saveEmptyBoard(request, auth)
      await openWorkspace(page, auth)

      const basePath = path.resolve(process.cwd(), '..', 'docs', 'examples', scenario.baseFile)
      const scene = JSON.parse(fs.readFileSync(basePath, 'utf8'))
      scenario.configure(scene)
      await page.getByTestId('scene-import-file').setInputFiles({
        name: `fix-${scenario.strategy}-scenario.json`,
        mimeType: 'application/json',
        buffer: Buffer.from(JSON.stringify(scene))
      })
      await page.getByRole('dialog', { name: 'Confirm Full Scene Replacement' })
        .getByRole('button', { name: 'Replace in full' })
        .click()

      await waitForApi<any[]>(request, auth, '/api/board/nodes',
        value => value.length === scenario.counts.devices)
      await waitForApi<any[]>(request, auth, '/api/board/environment',
        value => value.length === scenario.counts.environment)
      await waitForApi<any[]>(request, auth, '/api/board/rules',
        value => value.length === scenario.counts.rules)
      await waitForApi<any[]>(request, auth, '/api/board/specs',
        value => value.length === scenario.counts.specs)

      await page.getByTestId('open-verification-panel').click()
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
      await page.getByTestId('close-verification-result').click()

      const traces = await waitForApi<any[]>(request, auth, '/api/verify/traces', rows =>
        rows.some(trace => trace.isAttack === false && trace.states?.length >= 2))
      const trace = traces.find(row => row.isAttack === false && row.states?.length >= 2)
      expect(trace).toBeTruthy()

      const fixResponse = await request.post(fixRequestUrl(trace.id), {
        headers: authHeaders(auth),
        data: { strategies: [scenario.strategy] }
      })
      const fix = await unwrap<any>(fixResponse)
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
      const suggestion = listedRepair(fix, scenario.strategy, scenario.isExpectedRepair)

      const applyResponse = await request.post(
        `${apiBaseURL}/api/verify/traces/${trace.id}/fix/apply`,
        {
          headers: authHeaders(auth),
          data: {
            strategy: suggestion.strategy,
            suggestion,
            suggestionToken: suggestion.suggestionToken
          }
        }
      )
      const applied = await unwrap<any>(applyResponse)
      expect(applied).toMatchObject({
        applied: true,
        strategy: scenario.strategy,
        previousRuleCount: scenario.counts.rules,
        currentRuleCount: scenario.counts.rules
      })
      expect(applied.verificationEvidenceReused).toBe(true)

      await page.reload()
      await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
      await page.getByTestId('open-verification-panel').click()
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
    })
  }

  for (const scenario of coordinatedRepairScenarios) {
    test(`persists every item in a coordinated ${scenario.strategy} repair`, async ({ page, request }) => {
      const auth = await createAuthenticatedUser(request)
      await saveEmptyBoard(request, auth)
      await openWorkspace(page, auth)

      const basePath = path.resolve(
        process.cwd(), '..', 'docs', 'examples', 'default-climate-conflict-scene.json')
      const scene = JSON.parse(fs.readFileSync(basePath, 'utf8'))
      configureRedundantUnsafeHeatingScene(scene)
      await page.getByTestId('scene-import-file').setInputFiles({
        name: `coordinated-${scenario.strategy}-scenario.json`,
        mimeType: 'application/json',
        buffer: Buffer.from(JSON.stringify(scene))
      })
      await page.getByRole('dialog', { name: 'Confirm Full Scene Replacement' })
        .getByRole('button', { name: 'Replace in full' })
        .click()

      await waitForApi<any[]>(request, auth, '/api/board/nodes', rows => rows.length === 3)
      await waitForApi<any[]>(request, auth, '/api/board/environment', rows => rows.length === 2)
      await waitForApi<any[]>(request, auth, '/api/board/rules', rows => rows.length === 3)
      await waitForApi<any[]>(request, auth, '/api/board/specs', rows => rows.length === 1)

      await page.getByTestId('open-verification-panel').click()
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
        modelSnapshot: { specificationCount: 1 }
      })
      expect(baseline.specResults.filter((result: any) => result.outcome === 'VIOLATED')).toHaveLength(1)
      await page.getByTestId('close-verification-result').click()

      const traces = await waitForApi<any[]>(request, auth, '/api/verify/traces', rows =>
        rows.some(trace => trace.isAttack === false
          && trace.violatedSpec?.templateId === '3'
          && trace.states?.length >= 2))
      const trace = traces.find(row => row.isAttack === false
        && row.violatedSpec?.templateId === '3'
        && row.states?.length >= 2)
      expect(trace).toBeTruthy()

      const fixResponse = await request.post(fixRequestUrl(trace.id), {
        headers: authHeaders(auth),
        data: { strategies: [scenario.strategy] }
      })
      const fix = await unwrap<any>(fixResponse)
      expect(fix).toMatchObject({
        traceId: trace.id,
        fixable: true,
        sourceModelComplete: true,
        strategyAttempts: [{ strategy: scenario.strategy, status: 'VERIFIED' }]
      })
      const suggestion = listedRepair(fix, scenario.strategy, scenario.isExpectedRepair)

      const applyResponse = await request.post(
        `${apiBaseURL}/api/verify/traces/${trace.id}/fix/apply`,
        {
          headers: authHeaders(auth),
          data: {
            strategy: suggestion.strategy,
            suggestion,
            suggestionToken: suggestion.suggestionToken
          }
        }
      )
      const applied = await unwrap<any>(applyResponse)
      expect(applied).toMatchObject({
        applied: true,
        strategy: scenario.strategy,
        verificationEvidenceReused: true,
        previousRuleCount: 3,
        currentRuleCount: scenario.repairedRuleCount
      })
      expect(scenario.isExpectedRepair(applied.appliedSuggestion), JSON.stringify(applied.appliedSuggestion))
        .toBe(true)

      await waitForApi<any[]>(request, auth, '/api/board/rules',
        rows => rows.length === scenario.repairedRuleCount)
      await page.reload()
      await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
      await page.getByTestId('open-verification-panel').click()
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
        modelSnapshot: { specificationCount: 1 }
      })
      expect(repaired.specResults).toHaveLength(1)
      expect(repaired.specResults[0].outcome).toBe('SATISFIED')
    })
  }

  test('returns all three verified repairs for one combined counterexample', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)

    const basePath = path.resolve(process.cwd(), '..', 'docs', 'examples', 'default-climate-conflict-scene.json')
    const scene = JSON.parse(fs.readFileSync(basePath, 'utf8'))
    addOccupancySensor(scene, '30')
    scene.rules = [{
      name: 'Unsafe early heat rule',
      sources: [{
        fromId: 'temperature_1', fromApi: 'temperature', itemType: 'variable', relation: '>=', value: '28'
      }],
      toId: 'ac_1',
      toApi: 'heat'
    }, {
      name: 'At or below 35, turn the air conditioner off',
      sources: [{
        fromId: 'temperature_1', fromApi: 'temperature', itemType: 'variable', relation: '<=', value: '35'
      }],
      toId: 'ac_1',
      toApi: 'off'
    }, {
      name: 'At least 36, retain required heating',
      sources: [{
        fromId: 'temperature_1', fromApi: 'temperature', itemType: 'variable', relation: '>=', value: '36'
      }],
      toId: 'ac_1',
      toApi: 'heat'
    }]
    scene.specs = [{
      templateId: '3',
      aConditions: [{
        deviceId: 'occupancy_1', targetType: 'variable', variableSource: 'reported', key: 'occupied', relation: '=', value: 'absent'
      }, {
        deviceId: 'temperature_1', targetType: 'variable', variableSource: 'environment', key: 'temperature', relation: '<', value: '35'
      }, {
        deviceId: 'ac_1', targetType: 'mode', key: 'HvacMode', relation: '=', value: 'heat'
      }],
      ifConditions: [],
      thenConditions: []
    }, {
      templateId: '4',
      aConditions: [],
      ifConditions: [{
        deviceId: 'occupancy_1', targetType: 'variable', variableSource: 'reported', key: 'occupied', relation: '=', value: 'absent'
      }, {
        deviceId: 'temperature_1', targetType: 'variable', variableSource: 'environment', key: 'temperature', relation: '<=', value: '35'
      }],
      thenConditions: [{
        deviceId: 'ac_1', targetType: 'mode', key: 'HvacMode', relation: '=', value: 'off'
      }]
    }, {
      templateId: '4',
      aConditions: [],
      ifConditions: [{
        deviceId: 'temperature_1', targetType: 'variable', variableSource: 'environment', key: 'temperature', relation: '>=', value: '36'
      }],
      thenConditions: [{
        deviceId: 'ac_1', targetType: 'mode', key: 'HvacMode', relation: '=', value: 'heat'
      }]
    }]

    await page.getByTestId('scene-import-file').setInputFiles({
      name: 'fix-combined-three-strategy-scenario.json',
      mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify(scene))
    })
    await page.getByRole('dialog', { name: 'Confirm Full Scene Replacement' })
      .getByRole('button', { name: 'Replace in full' })
      .click()
    await waitForApi<any[]>(request, auth, '/api/board/nodes', rows => rows.length === 3)
    await waitForApi<any[]>(request, auth, '/api/board/rules', rows => rows.length === 3)
    await waitForApi<any[]>(request, auth, '/api/board/specs', rows => rows.length === 3)

    await page.getByTestId('open-verification-panel').click()
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
      modelSnapshot: { specificationCount: 3 }
    })
    expect(baseline.specResults.filter((result: any) => result.outcome === 'VIOLATED')).toHaveLength(2)
    await page.getByTestId('close-verification-result').click()

    const traces = await waitForApi<any[]>(request, auth, '/api/verify/traces', rows =>
      rows.filter(trace => trace.isAttack === false && trace.states?.length >= 2).length >= 2)
    const neverTrace = traces.find(trace => trace.isAttack === false
      && trace.violatedSpec?.templateId === '3'
      && trace.states?.length >= 2)
    expect(neverTrace).toBeTruthy()

    const strategies = ['parameter', 'condition', 'remove'] as const
    const fixResponse = await request.post(fixRequestUrl(neverTrace.id), {
      headers: authHeaders(auth),
      data: { strategies }
    })
    const fix = await unwrap<any>(fixResponse)
    expect(fix).toMatchObject({
      traceId: neverTrace.id,
      fixable: true,
      sourceModelComplete: true,
      sourceDisabledRuleCount: 0,
      sourceSkippedSpecCount: 0
    })
    expect(fix.strategyAttempts).toEqual(strategies.map(strategy =>
      expect.objectContaining({ strategy, status: 'VERIFIED' })))
    const parameter = listedRepair(fix, 'parameter', suggestion => suggestion.parameterAdjustments.some(
      (adjustment: any) => adjustment.originalValue === '28' && adjustment.newValue === '37'))
    listedRepair(fix, 'condition', suggestion => suggestion.conditionAdjustments.some((adjustment: any) =>
      adjustment.action === 'add' && adjustment.attribute === 'occupied' && adjustment.value === 'present'))
    listedRepair(fix, 'remove', suggestion =>
      JSON.stringify(suggestion.removedRuleDescriptions) === JSON.stringify(['Unsafe early heat rule']))

    const applyResponse = await request.post(
      `${apiBaseURL}/api/verify/traces/${neverTrace.id}/fix/apply`,
      {
        headers: authHeaders(auth),
        data: {
          strategy: parameter.strategy,
          suggestion: parameter,
          suggestionToken: parameter.suggestionToken
        }
      }
    )
    const applied = await unwrap<any>(applyResponse)
    expect(applied).toMatchObject({
      applied: true,
      strategy: 'parameter',
      previousRuleCount: 3,
      currentRuleCount: 3
    })

    await page.reload()
    await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
    await page.getByTestId('open-verification-panel').click()
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
      modelSnapshot: { specificationCount: 3 }
    })
    expect(repaired.specResults).toHaveLength(3)
    expect(repaired.specResults.every((result: any) => result.outcome === 'SATISFIED')).toBe(true)
  })

  test('imports devices from pasted JSON and selected CSV with precise preview validation', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)
    await openControlSection(page, 'devices')
    await waitForTemplateOption(page, 'Temperature Sensor')

    await page.getByTestId('device-create-mode-batch').click()
    await page.getByTestId('batch-device-template').selectOption('Door')
    await page.getByTestId('batch-device-prefix').fill('batch_door_')
    await page.getByTestId('batch-device-count').fill('0')
    await expect(page.getByTestId('batch-device-create')).toBeDisabled()
    await expect(page.getByTestId('batch-device-count').locator('..')).toContainText(/between 1 and 50|1 到 50/i)
    await page.getByTestId('batch-device-count').fill('51')
    await expect(page.getByTestId('batch-device-create')).toBeDisabled()
    await page.getByTestId('batch-device-count').fill('2')
    await expect(page.getByTestId('batch-device-create')).toBeEnabled()

    await page.getByTestId('device-create-mode-import').click()

    await page.getByTestId('device-import-text').fill(JSON.stringify([
      {
        template: 'Mobile Phone',
        name: 'import_phone',
        state: 'on',
        currentStateTrust: 'trusted',
        variables: [
          { name: 'location', value: 'home', trust: 'trusted' },
          { name: 'steps', value: '23', trust: 'trusted' }
        ],
        privacies: [
          { name: 'location', privacy: 'public' },
          { name: 'steps', privacy: 'public' }
        ]
      },
      {
        templateName: 'Alarm',
        label: 'import_alarm',
        state: 'both',
        currentStateTrust: 'trusted'
      }
    ]))
    await expect(page.getByTestId('device-import-create')).toBeEnabled()
    await page.getByTestId('device-import-create').click()

    let nodes = await waitForApi<any[]>(request, auth, '/api/board/nodes',
      value => value.some(node => node.label === 'import_phone')
        && value.some(node => node.label === 'import_alarm'))
    const importedPhone = nodes.find(node => node.label === 'import_phone')
    const importedAlarm = nodes.find(node => node.label === 'import_alarm')
    // `location` is a scene-level shared declaration, so it commits as an environment patch rather
    // than a per-device variable — the backend refuses it as a device variable outright (422). Only
    // the device-local `steps` stays on the node.
    expect(importedPhone.variables).toEqual([
      expect.objectContaining({ name: 'steps', value: '23', trust: 'trusted' })
    ])
    expect(importedPhone.privacies).toEqual([
      expect.objectContaining({ name: 'steps', privacy: 'public' })
    ])
    const phoneEnvironment = await waitForApi<any[]>(request, auth, '/api/board/environment',
      rows => rows.some(row => row.name === 'location'))
    expect(phoneEnvironment).toEqual(expect.arrayContaining([
      expect.objectContaining({ name: 'location', value: 'home', trust: 'trusted', privacy: 'public' })
    ]))
    expect(importedAlarm.state).toBe('both')
    expect(importedAlarm.currentStateTrust).toBe('trusted')

    await page.getByTestId('device-import-file').setInputFiles({
      name: 'devices.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from('template,name\nDoor,front_csv\nLight,hall_light_csv\n')
    })
    /*
     * Wait for the CSV's OWN preview rows, not merely for the button to be enabled.
     *
     * Reading a file is asynchronous, so between `setInputFiles` and the contents arriving the preview
     * and the button still describe the previously pasted JSON — and that payload also parses to exactly
     * two devices, so both `toBeEnabled()` and the button's "Create 2 device(s)" label are already
     * satisfied by the state this step is trying to replace. This test failed two consecutive nights on
     * that gap, re-importing the JSON as `import_phone_1`/`import_alarm_1`, and passed locally where the
     * read is fast enough to win the race. Asserting on content the old payload cannot produce is what
     * makes the wait real.
     */
    await expect(page.getByTestId('device-import-preview')).toContainText('front_csv')
    await expect(page.getByTestId('device-import-preview')).toContainText('hall_light_csv')
    await expect(page.getByTestId('device-import-create')).toBeEnabled()
    await page.getByTestId('device-import-create').click()
    nodes = await waitForApi<any[]>(request, auth, '/api/board/nodes',
      value => value.some(node => node.label === 'front_csv')
        && value.some(node => node.label === 'hall_light_csv'))
    expect(nodes.find(node => node.label === 'front_csv').templateName).toBe('Door')
    expect(nodes.find(node => node.label === 'hall_light_csv').templateName).toBe('Light')

    await page.getByTestId('device-import-text').fill(JSON.stringify({
      template: 'Door',
      name: 'front_csv'
    }))
    await expect(page.getByTestId('device-import-create')).toBeEnabled()
    await expect(page.locator('.device-preview-box')).toContainText(/renamed|重名/i)
    await page.getByTestId('device-import-create').click()
    await waitForApi<any[]>(request, auth, '/api/board/nodes',
      value => value.some(node => node.label === 'front_csv_1'))

    await page.getByTestId('device-import-text').fill(JSON.stringify({
      template: 'Temperature Sensor',
      name: 'import_temp_sensor',
      variables: [
        { name: 'temperature', value: '21', trust: 'untrusted' }
      ],
      privacies: [
        { name: 'temperature', privacy: 'public' }
      ]
    }))
    await expect(page.getByTestId('device-import-create')).toBeEnabled()
    await page.getByTestId('device-import-create').click()
    nodes = await waitForApi<any[]>(request, auth, '/api/board/nodes',
      value => value.some(node => node.label === 'import_temp_sensor'))
    expect(nodes.find(node => node.label === 'import_temp_sensor').variables || []).toHaveLength(0)
    const environment = await waitForApi<any[]>(request, auth, '/api/board/environment',
      value => value.some(variable => variable.name === 'temperature' && String(variable.value) === '21'))
    expect(environment.find(variable => variable.name === 'temperature')).toMatchObject({
      value: '21',
      trust: 'untrusted',
      privacy: 'public'
    })

    await page.getByTestId('device-import-text').fill(JSON.stringify({
      template: 'Temperature Sensor',
      name: 'bad_state',
      state: 'clear'
    }))
    await expect(page.getByTestId('device-import-create')).toBeDisabled()
    await expect(page.locator('.device-preview-box')).toContainText(/no state machine|状态机/i)

    await page.getByTestId('device-import-text').fill(JSON.stringify({
      template: 'Temperature Sensor',
      name: 'bad_duplicate',
      variables: [
        { name: 'temperature', value: '20', trust: 'trusted' },
        { name: 'temperature', value: '21', trust: 'trusted' }
      ]
    }))
    await expect(page.getByTestId('device-import-create')).toBeDisabled()
    await expect(page.locator('.device-preview-box')).toContainText(/Duplicate variable|变量重复/i)

    await page.getByTestId('device-import-text').fill(JSON.stringify({
      template: 'Missing Template',
      name: 'ghost'
    }))
    await expect(page.getByTestId('device-import-create')).toBeDisabled()
    await expect(page.locator('.device-preview-box')).toContainText(/Template not found|模板不存在/i)

    await page.getByTestId('device-import-text').fill(JSON.stringify({
      template: 'Door',
      name: 123
    }))
    await expect(page.getByTestId('device-import-create')).toBeDisabled()
    await expect(page.locator('.device-preview-box')).toContainText(/name must be a JSON string|name 必须是 JSON 字符串/i)

    await page.getByTestId('device-import-text').fill(JSON.stringify({
      template: 'Door',
      type: 'Light',
      name: 'ambiguous_type'
    }))
    await expect(page.getByTestId('device-import-create')).toBeDisabled()
    await expect(page.locator('.device-preview-box')).toContainText(/multiple aliases|多个字段/i)

    const lateInvalidRow = Array.from({ length: 10 }, (_, index) => ({
      template: 'Door',
      name: `visible_row_${index + 1}`
    }))
    lateInvalidRow.push({ template: 'Missing Template', name: 'late_error_row' })
    await page.getByTestId('device-import-text').fill(JSON.stringify(lateInvalidRow))
    await expect(page.getByTestId('device-import-create')).toBeDisabled()
    await expect(page.locator('.device-preview-box')).toContainText('late_error_row')
    await expect(page.locator('.device-preview-box')).toContainText(/Template not found|模板不存在/i)
  })

  test('AI suggestion panels wait for explicit generation and send scenario context', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)

    await expect(page.getByTestId('canvas-empty-state')).toBeVisible()
    await expect(page.getByTestId('empty-state-add-device')).toBeVisible()
    await expect(page.getByTestId('empty-state-generate-scenario')).toBeVisible()
    await expect(page.getByTestId('empty-state-import-scene')).toBeVisible()

    await page.getByTestId('open-scenario-recommendations').click()
    await expect(page.getByTestId('scenario-recommendation-panel').locator('textarea'))
      .toHaveAttribute('maxlength', '2000')
    await page.getByTestId('close-scenario-recommendations').click()

    await page.getByTestId('open-device-recommendations').click()
    await expect(page.getByTestId('device-recommendation-panel').locator('textarea'))
      .toHaveAttribute('maxlength', '2000')
    await page.getByTestId('close-device-recommendations').click()

    const recommendationRequests: string[] = []
    page.on('request', req => {
      const url = new URL(req.url())
      if (url.pathname === '/api/board/rules/recommend' || url.pathname === '/api/board/specs/recommend') {
        recommendationRequests.push(url.toString())
      }
    })

    await page.getByTestId('open-history-panel').click()
    await expect(page.getByTestId('trace-history-panel')).toBeVisible({ timeout: 10_000 })

    await page.getByTestId('open-rule-recommendations').click()
    await expect(page.getByTestId('trace-history-panel')).toBeHidden({ timeout: 5_000 })
    const rulePanel = page.getByTestId('rule-recommendation-panel')
    await expect(rulePanel).toBeVisible()
    await expect(rulePanel).toContainText(/Configure recommendation parameters|先配置推荐参数/i)
    await expect(rulePanel.locator('textarea')).toHaveAttribute('maxlength', '2000')
    await page.waitForTimeout(500)
    expect(recommendationRequests).toEqual([])

    const detailedRuleRequirement =
      `night safety for an elderly resident ${'with explicit context '.repeat(30)}`.trim()
    expect(detailedRuleRequirement.length).toBeGreaterThan(500)
    // A rule category was a model-authored label with no verification meaning, so the panel
    // offers no category control and the request must not carry one.
    await expect(rulePanel.locator('select')).toHaveCount(0)
    await rulePanel.locator('input[type="number"]').fill('3')
    await rulePanel.locator('textarea').fill(detailedRuleRequirement)
    const ruleResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      if (response.request().method() !== 'POST'
        || url.pathname !== '/api/board/rules/recommend') return false
      const body = response.request().postDataJSON() as Record<string, unknown> | null
      return body !== null
        && body.maxRecommendations === 3
        && !('category' in body)
        && body.userRequirement === detailedRuleRequirement
    })
    await page.getByTestId('generate-rule-recommendations').click()
    const ruleResponse = await ruleResponsePromise
    expect(ruleResponse.ok(), await ruleResponse.text()).toBeTruthy()
    await expect(rulePanel).toContainText(/No devices found|Add more devices|先添加设备|暂无推荐/i)

    await page.getByTestId('open-spec-recommendations').click()
    await expect(page.getByTestId('rule-recommendation-panel')).toBeHidden({ timeout: 5_000 })
    const specPanel = page.getByTestId('spec-recommendation-panel')
    await expect(specPanel).toBeVisible()
    await expect(specPanel).toContainText(/Configure recommendation parameters|先配置推荐参数/i)
    await expect(specPanel.locator('textarea')).toHaveAttribute('maxlength', '2000')
    await page.waitForTimeout(500)
    expect(recommendationRequests.filter(url => new URL(url).pathname === '/api/board/specs/recommend')).toHaveLength(0)

    await expect(specPanel.locator('select')).toHaveCount(0)
    await specPanel.locator('input[type="number"]').fill('2')
    await specPanel.locator('textarea').fill('privacy guard when motion is detected at night')
    const specResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      if (response.request().method() !== 'POST'
        || url.pathname !== '/api/board/specs/recommend') return false
      const body = response.request().postDataJSON() as Record<string, unknown> | null
      return body !== null
        && body.maxRecommendations === 2
        && !('category' in body)
        && body.userRequirement === 'privacy guard when motion is detected at night'
    })
    await page.getByTestId('generate-spec-recommendations').click()
    const specResponse = await specResponsePromise
    expect(specResponse.ok(), await specResponse.text()).toBeTruthy()
    await expect(specPanel).toContainText(/No devices found|Add more devices|先添加设备|暂无推荐/i)

    await page.getByTestId('close-spec-recommendations').click()
    const camera = await addDeviceViaLeftPanel(
      page, request, auth, 'Camera', 'recommendation_hall_camera')
    const alarm = await addDeviceViaLeftPanel(
      page, request, auth, 'Alarm', 'recommendation_hall_alarm')
    await page.getByTestId('open-rule-recommendations').click()
    await expect(rulePanel).toBeVisible()

    const normalizedRuleRoute = async (route: Route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'Success',
          data: {
            message: 'Found one validated rule recommendation.',
            count: 1,
            requestedCount: 3,
            validatedCount: 1,
            filteredCount: 0,
            filteredItems: [],
            adjustedCount: 1,
            adjustedItems: [{
              type: 'rule',
              index: 1,
              reasonCode: 'apiEventSyntaxNormalized',
              reason: "The AI expressed the API event as '= TRUE'; it was normalized to event-trigger semantics.",
              label: 'Camera activity starts the alarm',
              appliedValues: {
                sourceDevice: camera.label,
                sourceApi: 'take photo',
                removedRelation: '=',
                removedValue: 'TRUE'
              }
            }],
            rawCandidateCount: 1,
            inspectedCount: 1,
            truncatedCount: 0,
            recommendations: [{
              name: 'Camera activity starts the alarm',
              conditions: [{
                deviceId: camera.id,
                deviceName: camera.label,
                attribute: 'take photo',
                targetType: 'api'
              }],
              command: {
                deviceId: alarm.id,
                deviceName: alarm.label,
                action: 'siren'
              }
            }]
          }
        })
      })
    }
    await page.route('**/api/board/rules/recommend**', normalizedRuleRoute)
    await page.getByTestId('generate-rule-recommendations').click()
    await expect(page.getByTestId('rule-adjusted-items')).toContainText(/normalized|规范化/i)
    await rulePanel.getByRole('button', { name: /Add This Rule/i }).click()
    const savedRules = await waitForApi<any[]>(request, auth, '/api/board/rules', rules =>
      rules.some(rule => rule.ruleString === 'Camera activity starts the alarm'))
    expect(savedRules).toHaveLength(1)
    expect(savedRules[0]).toMatchObject({
      ruleString: 'Camera activity starts the alarm',
      conditions: [{
        deviceName: camera.id,
        attribute: 'take photo',
        targetType: 'api'
      }],
      command: {
        deviceName: alarm.id,
        action: 'siren'
      }
    })
    await expect(rulePanel.getByRole('button', { name: /Added to board/i })).toBeDisabled()
    await expect(rulePanel).toContainText(/Only the applied recommendation is kept/i)
    await expect(page.getByTestId('rule-candidate-accounting')).toHaveCount(0)
    await page.unroute('**/api/board/rules/recommend**', normalizedRuleRoute)

    const reconciledRuleName = 'Camera activity starts the strobe'
    const reconciledRuleRoute = async (route: Route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'Success',
          data: {
            message: 'Found one validated rule recommendation.',
            count: 1,
            requestedCount: 3,
            validatedCount: 1,
            filteredCount: 0,
            filteredItems: [],
            adjustedCount: 0,
            adjustedItems: [],
            rawCandidateCount: 1,
            inspectedCount: 1,
            truncatedCount: 0,
            recommendations: [{
              name: reconciledRuleName,
              conditions: [{
                deviceId: camera.id,
                deviceName: camera.label,
                attribute: 'take photo',
                targetType: 'api'
              }],
              command: {
                deviceId: alarm.id,
                deviceName: alarm.label,
                action: 'strobe'
              }
            }]
          }
        })
      })
    }
    const distinctSimilarityRoute = async (route: Route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'Success',
          data: {
            isSimilar: false,
            isDuplicate: false,
            requiresReview: false,
            matchedRule: null,
            similarity: 0.1,
            reasonCode: 'AI_NO_SIGNIFICANT_SIMILARITY',
            reason: 'The automation has a distinct action.',
            message: 'No significant similarity was found.'
          }
        })
      })
    }
    await page.route('**/api/board/rules/recommend**', reconciledRuleRoute)
    await page.route('**/api/board/rules/check-similarity', distinctSimilarityRoute)
    await page.getByTestId('generate-rule-recommendations').click()
    await expect(rulePanel).toContainText(reconciledRuleName)

    let markBackendPersisted!: () => void
    const backendPersisted = new Promise<void>(resolve => { markBackendPersisted = resolve })
    let releaseUnknownResponse!: () => void
    const unknownResponseGate = new Promise<void>(resolve => { releaseUnknownResponse = resolve })
    const unknownRuleCreateRoute = async (route: Route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      const response = await route.fetch()
      expect(response.ok(), await response.text()).toBeTruthy()
      markBackendPersisted()
      await unknownResponseGate
      await route.abort('connectionreset')
    }
    await page.route('**/api/board/rules', unknownRuleCreateRoute)
    await rulePanel.getByRole('button', { name: /Add This Rule/i }).click()
    await backendPersisted
    await page.getByTestId('close-rule-recommendations').click()
    releaseUnknownResponse()
    await expect(rulePanel).toBeHidden()

    const reconciledRules = await waitForApi<any[]>(request, auth, '/api/board/rules', rules =>
      rules.some(rule => rule.ruleString === reconciledRuleName))
    const reconciledRule = reconciledRules.find(rule => rule.ruleString === reconciledRuleName)!
    expect(reconciledRule).toBeTruthy()
    await page.getByTestId('inspector-tab-rules').click()
    await expect(page.getByTestId('inspector-section-rules')
      .locator(`[data-rule-id="${reconciledRule.id}"]`))
      .toContainText(reconciledRuleName)

    await page.unroute('**/api/board/rules', unknownRuleCreateRoute)
    await page.unroute('**/api/board/rules/check-similarity', distinctSimilarityRoute)
    await page.unroute('**/api/board/rules/recommend**', reconciledRuleRoute)
  })

  test('applies a reviewed scenario recommendation through atomic board replacement', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)

    const scenePath = path.resolve(
      process.cwd(), '..', 'docs', 'examples', 'default-rfid-access-scene.json')
    const scene = JSON.parse(fs.readFileSync(scenePath, 'utf8'))
    const finalCount = scene.devices.length
      + scene.environmentVariables.length
      + scene.rules.length
      + scene.specs.length
    let scenarioPostCount = 0
    const scenarioRoute = async (route: Route) => {
      scenarioPostCount += 1
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'Success',
          data: {
            message: 'Generated a validated RFID access scene draft.',
            count: finalCount,
            requestedCount: 10,
            validatedCount: finalCount,
            filteredCount: 0,
            filteredItems: [],
            adjustedCount: 0,
            adjustedItems: [],
            rawCandidateCount: finalCount,
            inspectedCount: finalCount,
            truncatedCount: 0,
            scenarioName: 'RFID access control',
            rationale: 'The final canonical draft contains retained devices, rules, and specifications.',
            objectiveTargets: { minDevices: 3, minRules: 2, minSpecs: 5 },
            objectiveStatus: 'COMPLETE',
            objectiveIssues: [],
            verificationReady: true,
            readinessIssues: [],
            semanticWarnings: [],
            scene
          }
        })
      })
    }
    await page.route('**/api/board/scenario/recommend**', scenarioRoute)

    await page.getByTestId('open-scenario-recommendations').click()
    await page.getByTestId('scenario-min-devices').fill('7')
    await page.getByTestId('generate-scenario-recommendation').click()
    await expect(page.locator('.el-message').filter({
      hasText: /minimum.*must not exceed.*maximum/i
    })).toBeVisible()
    await page.waitForTimeout(200)
    expect(scenarioPostCount).toBe(0)

    await page.getByTestId('scenario-min-devices').fill('3')
    await page.getByTestId('scenario-min-rules').fill('2')
    await page.getByTestId('scenario-min-specs').fill('5')
    await page.getByTestId('scenario-max-devices').fill('4')
    await page.getByTestId('scenario-max-rules').fill('3')
    await page.getByTestId('scenario-max-specs').fill('6')
    const recommendationRequest = page.waitForRequest(req => {
      if (req.method() !== 'POST'
        || new URL(req.url()).pathname !== '/api/board/scenario/recommend') return false
      const body = req.postDataJSON() as Record<string, unknown> | null
      return body?.minDevices === 3
        && body?.minRules === 2
        && body?.minSpecs === 5
        && body?.maxDevices === 4
        && body?.maxRules === 3
        && body?.maxSpecs === 6
    })
    await page.getByTestId('generate-scenario-recommendation').click()
    await recommendationRequest
    expect(scenarioPostCount).toBe(1)
    await expect(page.getByTestId('apply-recommended-scenario')).toBeVisible()

    await page.getByTestId('scenario-min-devices').fill('2')
    await expect(page.getByTestId('apply-recommended-scenario')).toHaveCount(0)
    await page.getByTestId('scenario-min-devices').fill('3')
    const refreshedRecommendationRequest = page.waitForRequest(req => {
      if (req.method() !== 'POST'
        || new URL(req.url()).pathname !== '/api/board/scenario/recommend') return false
      const body = req.postDataJSON() as Record<string, unknown> | null
      return body?.minDevices === 3
        && body?.minRules === 2
        && body?.minSpecs === 5
        && body?.maxDevices === 4
        && body?.maxRules === 3
        && body?.maxSpecs === 6
    })
    await page.getByTestId('generate-scenario-recommendation').click()
    await refreshedRecommendationRequest
    expect(scenarioPostCount).toBe(2)
    await expect(page.getByTestId('apply-recommended-scenario')).toBeVisible()
    await page.unroute('**/api/board/scenario/recommend**', scenarioRoute)

    await page.getByTestId('apply-recommended-scenario').click()
    const confirmation = page.getByRole('dialog', { name: 'Confirm Full Scene Replacement' })
    await expect(confirmation).toBeVisible()
    const batchResponsePromise = page.waitForResponse(response =>
      response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/board/scene')
    await confirmation.getByRole('button', { name: 'Replace in full' }).click()
    const batchResponse = await batchResponsePromise
    expect(batchResponse.ok(), await batchResponse.text()).toBeTruthy()

    const devices = await waitForApi<any[]>(request, auth, '/api/board/nodes',
      rows => rows.length === scene.devices.length)
    const rules = await waitForApi<any[]>(request, auth, '/api/board/rules',
      rows => rows.length === scene.rules.length)
    const specs = await waitForApi<any[]>(request, auth, '/api/board/specs',
      rows => rows.length === scene.specs.length)
    expect(devices.map(device => device.label)).toContain('Front-door Badge Reader')
    expect(rules.map(rule => rule.ruleString)).toContain(
      'When the badge is authorized, unlock the front door')
    expect(specs).toHaveLength(scene.specs.length)
  })

  test('exports scene JSON that imports and exports byte-identically', async ({ page, request }, testInfo) => {
    const auth = await createAuthenticatedUser(request)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)

    const motion = await addDeviceViaLeftPanel(page, request, auth, 'Motion Detector', 'roundtrip_motion')
    const alarm = await addDeviceViaLeftPanel(page, request, auth, 'Alarm', 'roundtrip_alarm')
    expect(alarm.currentStateTrust ?? null).toBeNull()
    expect(alarm.currentStatePrivacy ?? null).toBeNull()

    await addMotionRuleViaDialog(
      page, request, auth, motion.id, alarm.id, 'siren',
      'roundtrip motion triggers alarm', 1)
    const rulesBeforeReorder = await addMotionRuleViaDialog(
      page, request, auth, motion.id, alarm.id, 'strobe',
      'roundtrip motion triggers strobe', 2)

    await page.getByTestId('inspector-tab-rules').click()
    await expect(page.getByTestId('rule-execution-order-hint')).toBeVisible()
    const laterRuleId = String(rulesBeforeReorder[1].id)
    await page.locator(`[data-rule-id="${laterRuleId}"]`)
      .getByRole('button', { name: 'Move earlier' })
      .click()
    const rulesAfterReorder = await waitForApi<any[]>(request, auth, '/api/board/rules',
      rules => rules.length === 2 && String(rules[0].id) === laterRuleId)
    expect(rulesAfterReorder.map(rule => rule.ruleString)).toEqual([
      'roundtrip motion triggers strobe',
      'roundtrip motion triggers alarm'
    ])

    await openControlSection(page, 'specs')
    await page.getByTestId('spec-template-select').selectOption('5')
    await page.getByTestId('spec-add-condition-if').click()
    await fillSpecCondition(page, motion.id, 'variable', 'motion', '=', 'active', 'environment')
    await page.getByTestId('spec-add-condition-then').click()
    await fillSpecCondition(page, alarm.id, 'privacy', JSON.stringify(['state', 'AlertState']), '=', 'private')
    await page.getByTestId('spec-create').click()
    await waitForApi<any[]>(request, auth, '/api/board/specs', value => value.length === 1)

    await page.getByTestId('spec-template-select').selectOption('1')
    await page.getByTestId('spec-add-condition-a').click()
    await fillSpecCondition(page, motion.id, 'variable', 'motion', '=', 'inactive', 'environment')
    await page.getByTestId('spec-create').click()
    const specsBeforeExport = await waitForApi<any[]>(request, auth, '/api/board/specs', value => value.length === 2)
    expect(specsBeforeExport.map(spec => spec.templateId)).toEqual(['5', '1'])

    const firstDownloadPromise = page.waitForEvent('download')
    await page.getByTestId('scene-export').click()
    const firstDownload = await firstDownloadPromise
    const firstPath = await firstDownload.path()
    expect(firstPath).toBeTruthy()
    const firstJson = fs.readFileSync(firstPath!, 'utf8')
    const firstScene = JSON.parse(firstJson)
    expect(firstScene).toMatchObject({ schema: 'iot-verify.board-scene', version: 5 })
    expect(firstScene).not.toHaveProperty('exportedAt')
    expect(firstScene.devices).toHaveLength(2)
    const exportedMotion = firstScene.devices.find((device: any) => device.templateName === 'Motion Detector')
    expect(exportedMotion).not.toHaveProperty('state')
    expect(exportedMotion).not.toHaveProperty('currentStateTrust')
    expect(exportedMotion).not.toHaveProperty('currentStatePrivacy')
    expect(firstScene.rules).toHaveLength(2)
    expect(firstScene.rules.map((rule: any) => rule.name)).toEqual([
      'roundtrip motion triggers strobe',
      'roundtrip motion triggers alarm'
    ])
    expect(firstScene.specs).toHaveLength(2)
    expect(firstScene.specs.map((spec: any) => spec.templateId)).toEqual(['5', '1'])
    expect(firstScene.templates[0]).not.toHaveProperty('id')
    expect(firstScene.templates[0]).not.toHaveProperty('defaultTemplate')
    expect(firstScene.rules[0]).not.toHaveProperty('id')
    expect(firstScene.specs[0]).not.toHaveProperty('id')
    expect(firstScene.specs[0]).not.toHaveProperty('templateLabel')
    expect(firstScene.specs[0]).not.toHaveProperty('formula')
    expect(firstScene.specs[0]).not.toHaveProperty('devices')
    expect(firstScene.specs[0].ifConditions[0]).not.toHaveProperty('id')
    expect(firstScene.specs[0].ifConditions[0]).not.toHaveProperty('side')
    expect(firstScene.specs[0].ifConditions[0]).not.toHaveProperty('deviceLabel')
    expect(firstScene.specs[0].thenConditions[0]).toMatchObject({
      targetType: 'privacy',
      propertyScope: 'state',
      key: 'AlertState',
      relation: '=',
      value: 'private'
    })
    expect(JSON.stringify(firstScene.specs[0].thenConditions[0])).not.toContain('AlertState_')
    expect(firstScene.environmentVariables.length).toBeGreaterThan(0)

    await page.getByTestId('scene-clear').click()
    await page.locator('.el-message-box').getByRole('button', { name: 'Clear' }).click()
    await waitForApi<any[]>(request, auth, '/api/board/nodes', value => value.length === 0)
    await waitForApi<any[]>(request, auth, '/api/board/rules', value => value.length === 0)
    await waitForApi<any[]>(request, auth, '/api/board/specs', value => value.length === 0)

    const emptyDownloadPromise = page.waitForEvent('download')
    await page.getByTestId('scene-export').click()
    const emptyDownload = await emptyDownloadPromise
    const emptyPath = await emptyDownload.path()
    expect(emptyPath).toBeTruthy()
    const emptyJson = fs.readFileSync(emptyPath!, 'utf8')
    const emptyScene = JSON.parse(emptyJson)
    expect(emptyScene).toMatchObject({ templates: [], devices: [], environmentVariables: [], rules: [], specs: [] })
    const emptyImportPath = testInfo.outputPath('scene-empty.json')
    fs.writeFileSync(emptyImportPath, emptyJson, 'utf8')
    await page.getByTestId('scene-import-file').setInputFiles(emptyImportPath)
    const emptyImportResponsePromise = page.waitForResponse(response =>
      response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/board/scene')
    await page.getByRole('dialog', { name: 'Confirm Full Scene Replacement' })
      .getByRole('button', { name: 'Replace in full' })
      .click()
    const emptyImportResponse = await emptyImportResponsePromise
    expect(emptyImportResponse.ok(), await emptyImportResponse.text()).toBeTruthy()
    await expect(page.getByTestId('scene-import')).toBeEnabled()
    await waitForApi<any[]>(request, auth, '/api/board/nodes', value => value.length === 0)

    // 6, one past the current format: at 5 this file is VALID and would import successfully, inverting
    // the rejection this block asserts.
    const futureScene = { ...firstScene, version: 6 }
    const futurePath = testInfo.outputPath('scene-future-version.json')
    fs.writeFileSync(futurePath, JSON.stringify(futureScene, null, 2), 'utf8')
    await page.getByTestId('scene-import-file').setInputFiles(futurePath)
    await expect(page.locator('.el-message').filter({ hasText: /version 6|版本 6/i })).toBeVisible()
    await waitForApi<any[]>(request, auth, '/api/board/nodes', value => value.length === 0)

    const internalFieldScene = JSON.parse(firstJson)
    internalFieldScene.specs[0].id = 'internal-spec-id'
    const internalFieldPath = testInfo.outputPath('scene-internal-field.json')
    fs.writeFileSync(internalFieldPath, JSON.stringify(internalFieldScene, null, 2), 'utf8')
    await page.getByTestId('scene-import-file').setInputFiles(internalFieldPath)
    await expect(page.locator('.el-message').filter({ hasText: /internal or derived|内部或派生/i })).toBeVisible()
    await waitForApi<any[]>(request, auth, '/api/board/nodes', value => value.length === 0)

    const unknownFieldScene = JSON.parse(firstJson)
    unknownFieldScene.rules[0].futureConflictPolicy = 'merge'
    const unknownFieldPath = testInfo.outputPath('scene-unknown-field.json')
    fs.writeFileSync(unknownFieldPath, JSON.stringify(unknownFieldScene, null, 2), 'utf8')
    await page.getByTestId('scene-import-file').setInputFiles(unknownFieldPath)
    await expect(page.locator('.el-message').filter({ hasText: /futureConflictPolicy|不认识的字段/i })).toBeVisible()
    await waitForApi<any[]>(request, auth, '/api/board/nodes', value => value.length === 0)

    const malformedJsonPath = testInfo.outputPath('scene-malformed.json')
    fs.writeFileSync(malformedJsonPath, '{not-json', 'utf8')
    await page.getByTestId('scene-import-file').setInputFiles(malformedJsonPath)
    await expect(page.locator('.el-message').filter({ hasText: /Invalid JSON file|JSON 文件无效/i })).toBeVisible()
    await waitForApi<any[]>(request, auth, '/api/board/nodes', value => value.length === 0)

    const wrongScalarTypeScene = JSON.parse(firstJson)
    wrongScalarTypeScene.devices[0].label = 123
    const wrongScalarTypePath = testInfo.outputPath('scene-wrong-scalar-type.json')
    fs.writeFileSync(wrongScalarTypePath, JSON.stringify(wrongScalarTypeScene, null, 2), 'utf8')
    await page.getByTestId('scene-import-file').setInputFiles(wrongScalarTypePath)
    await expect(page.locator('.el-message').filter({
      hasText: /devices\[0\]\.label.*JSON string|devices\[0\]\.label.*JSON 字符串/i
    })).toBeVisible()
    await waitForApi<any[]>(request, auth, '/api/board/nodes', value => value.length === 0)

    const missingEnvironmentScene = JSON.parse(firstJson)
    missingEnvironmentScene.environmentVariables = []
    const missingEnvironmentPath = testInfo.outputPath('scene-missing-environment.json')
    fs.writeFileSync(missingEnvironmentPath, JSON.stringify(missingEnvironmentScene, null, 2), 'utf8')
    await page.getByTestId('scene-import-file').setInputFiles(missingEnvironmentPath)
    await expect(page.locator('.el-message').filter({ hasText: /missing environment variables|缺少.*环境变量/i })).toBeVisible()
    await waitForApi<any[]>(request, auth, '/api/board/nodes', value => value.length === 0)

    const emptyEnvironmentValueScene = JSON.parse(firstJson)
    emptyEnvironmentValueScene.environmentVariables[0].value = null
    const emptyEnvironmentValuePath = testInfo.outputPath('scene-empty-environment-value.json')
    fs.writeFileSync(emptyEnvironmentValuePath, JSON.stringify(emptyEnvironmentValueScene, null, 2), 'utf8')
    await page.getByTestId('scene-import-file').setInputFiles(emptyEnvironmentValuePath)
    await expect(page.locator('.el-message').filter({ hasText: /explicit non-blank value|显式填写非空 value/i })).toBeVisible()
    await waitForApi<any[]>(request, auth, '/api/board/nodes', value => value.length === 0)

    const duplicateVariableScene = JSON.parse(firstJson)
    duplicateVariableScene.devices[0].variables = [
      { name: 'duplicate_input', value: 'one' },
      { name: 'duplicate_input', value: 'two' }
    ]
    const duplicateVariablePath = testInfo.outputPath('scene-duplicate-device-variable.json')
    fs.writeFileSync(duplicateVariablePath, JSON.stringify(duplicateVariableScene, null, 2), 'utf8')
    await page.getByTestId('scene-import-file').setInputFiles(duplicateVariablePath)
    await expect(page.locator('.el-message').filter({
      hasText: /duplicate_input.*devices\[0\]\.variables|devices\[0\]\.variables.*duplicate_input/i
    })).toBeVisible()
    await waitForApi<any[]>(request, auth, '/api/board/nodes', value => value.length === 0)

    const duplicatePrivacyScene = JSON.parse(firstJson)
    duplicatePrivacyScene.devices[0].privacies = [
      { name: 'duplicate_privacy', privacy: 'private' },
      { name: 'duplicate_privacy', privacy: 'public' }
    ]
    const duplicatePrivacyPath = testInfo.outputPath('scene-duplicate-device-privacy.json')
    fs.writeFileSync(duplicatePrivacyPath, JSON.stringify(duplicatePrivacyScene, null, 2), 'utf8')
    await page.getByTestId('scene-import-file').setInputFiles(duplicatePrivacyPath)
    await expect(page.locator('.el-message').filter({
      hasText: /duplicate_privacy.*devices\[0\]\.privacies|devices\[0\]\.privacies.*duplicate_privacy/i
    })).toBeVisible()
    await waitForApi<any[]>(request, auth, '/api/board/nodes', value => value.length === 0)

    const duplicateEnvironmentScene = JSON.parse(firstJson)
    const duplicateEnvironment = {
      ...duplicateEnvironmentScene.environmentVariables[0],
      name: 'duplicate_environment'
    }
    duplicateEnvironmentScene.environmentVariables = [
      duplicateEnvironment,
      { ...duplicateEnvironment }
    ]
    const duplicateEnvironmentPath = testInfo.outputPath('scene-duplicate-environment.json')
    fs.writeFileSync(duplicateEnvironmentPath, JSON.stringify(duplicateEnvironmentScene, null, 2), 'utf8')
    await page.getByTestId('scene-import-file').setInputFiles(duplicateEnvironmentPath)
    await expect(page.locator('.el-message').filter({
      hasText: /duplicate environment variable: duplicate_environment|重复的环境变量.*duplicate_environment/i
    })).toBeVisible()
    await waitForApi<any[]>(request, auth, '/api/board/nodes', value => value.length === 0)

    const invalidScene = JSON.parse(firstJson)
    delete invalidScene.rules[0].toApi
    const invalidPath = testInfo.outputPath('scene-missing-rule-target.json')
    fs.writeFileSync(invalidPath, JSON.stringify(invalidScene, null, 2), 'utf8')
    await page.getByTestId('scene-import-file').setInputFiles(invalidPath)
    await expect(page.locator('.el-message').filter({ hasText: 'rules[0].toApi' })).toBeVisible()
    await waitForApi<any[]>(request, auth, '/api/board/nodes', value => value.length === 0)
    await waitForApi<any[]>(request, auth, '/api/board/rules', value => value.length === 0)

    const importPath = testInfo.outputPath('scene-roundtrip.json')
    fs.writeFileSync(importPath, firstJson, 'utf8')
    await page.getByTestId('scene-import-file').setInputFiles(importPath)
    await page.getByRole('dialog', { name: 'Confirm Full Scene Replacement' })
      .getByRole('button', { name: 'Replace in full' })
      .click()
    await waitForApi<any[]>(request, auth, '/api/board/nodes', value => value.length === 2)
    const importedRules = await waitForApi<any[]>(request, auth, '/api/board/rules', value => value.length === 2)
    expect(importedRules.map(rule => rule.ruleString)).toEqual([
      'roundtrip motion triggers strobe',
      'roundtrip motion triggers alarm'
    ])
    const importedSpecs = await waitForApi<any[]>(request, auth, '/api/board/specs', value => value.length === 2)
    expect(importedSpecs.map(spec => spec.templateId)).toEqual(['5', '1'])

    const secondDownloadPromise = page.waitForEvent('download')
    await page.getByTestId('scene-export').click()
    const secondDownload = await secondDownloadPromise
    const secondPath = await secondDownload.path()
    expect(secondPath).toBeTruthy()
    const secondJson = fs.readFileSync(secondPath!, 'utf8')
    expect(secondJson).toBe(firstJson)

    await page.getByTestId('open-verification-panel').click()
    await page.getByTestId('verification-mode-sync').click()
    await page.getByTestId('run-verification').click()
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('verification-result-dialog')).toContainText(/NuSMV|specification|规约/i)
  })

  test('imports the acceptance scene, animates it, contrasts attacks, applies a verified repair, and re-verifies', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)

    const scenePath = path.resolve(process.cwd(), '..', 'docs', 'examples', 'acceptance-demo-scene.json')
    await page.getByTestId('scene-import-file').setInputFiles(scenePath)
    await page.getByRole('dialog', { name: 'Confirm Full Scene Replacement' })
      .getByRole('button', { name: 'Replace in full' })
      .click()

    const importedNodes = await waitForApi<any[]>(request, auth, '/api/board/nodes', nodes => nodes.length === 4)
    expect(importedNodes.map(node => node.label).sort()).toEqual([
      'Hall Alarm', 'Hall Camera', 'Hall Light', 'Hall Motion Sensor'
    ])
    await waitForApi<any[]>(request, auth, '/api/board/environment', values => values.length === 2)
    await waitForApi<any[]>(request, auth, '/api/board/rules', rules => rules.length === 3)
    await waitForApi<any[]>(request, auth, '/api/board/specs', specs => specs.length === 5)

    await page.getByTestId('open-simulation-panel').click()
    await page.getByTestId('simulation-mode-sync').click()
    await page.getByTestId('simulation-privacy-toggle').click()
    const simulationResponsePromise = page.waitForResponse(response =>
      response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/simulate/traces')
    await page.getByTestId('run-simulation').click()
    const simulationResponse = await simulationResponsePromise
    const simulationResult = await unwrap<any>(simulationResponse as any)
    expect(simulationResult.modelComplete).toBe(true)
    expect(simulationResult.disabledRuleCount).toBe(0)
    expect(simulationResult.states.length).toBeGreaterThanOrEqual(2)
    expect(JSON.stringify(simulationResult.states)).toContain('takingphoto')
    expect(JSON.stringify(simulationResult.states)).toContain('siren')
    expect(JSON.stringify(simulationResult.states)).toContain('"on"')
    await expect(page.getByTestId('simulation-timeline')).toBeVisible({ timeout: 45_000 })
    await expect(page.getByTestId('playback-change-popover')).toBeVisible()
    await expect(page.getByTestId('playback-change-initial-state')).toBeVisible()

    const firstTriggeredStateIndex = simulationResult.states.findIndex((state: any, index: number) =>
      index > 0 && Array.isArray(state.triggeredRules) && state.triggeredRules.length > 0)
    expect(firstTriggeredStateIndex).toBeGreaterThan(0)
    await page.getByTestId('simulation-timeline-step-input').fill(String(firstTriggeredStateIndex + 1))
    await expect(page.getByTestId('playback-change-popover')).toBeVisible()
    await expect(page.getByTestId('playback-change-automation')).toContainText(
      simulationResult.states[firstTriggeredStateIndex].triggeredRules[0].ruleLabel)
    await expect(page.locator(`.particle-line[data-playback-state="${firstTriggeredStateIndex}"]`).first()).toBeVisible()

    await page.getByTestId('simulation-timeline-play').click()
    await page.waitForTimeout(500)
    await page.getByTestId('simulation-timeline-close').click()

    await page.getByTestId('open-verification-panel').click()
    await page.getByTestId('verification-mode-sync').click()
    const baselineResponsePromise = page.waitForResponse(response =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/verify')
    await page.getByTestId('run-verification').click()
    const baselineResponse = await baselineResponsePromise
    const baseline = await unwrap<any>(baselineResponse as any)
    expect(baseline).toMatchObject({
      outcome: 'VIOLATED',
      modelComplete: true,
      disabledRuleCount: 0,
      skippedSpecCount: 0,
      modelSnapshot: { specificationCount: 5 }
    })
    expect(baseline.specResults).toHaveLength(5)
    expect(baseline.specResults.filter((result: any) => result.outcome === 'VIOLATED')).toHaveLength(1)
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })
    await page.getByTestId('close-verification-result').click()

    const baselineTraces = await waitForApi<any[]>(request, auth, '/api/verify/traces', traces =>
      traces.some(trace => trace.isAttack === false
        && JSON.stringify(trace.violatedSpec || {}).includes('taking photo')))
    const baselineTrace = baselineTraces.find(trace => trace.isAttack === false
      && JSON.stringify(trace.violatedSpec || {}).includes('taking photo'))
    expect(baselineTrace.states.length).toBeGreaterThanOrEqual(2)

    await page.getByTestId('open-verification-panel').click()
    await clickUnderTooltip(page, page.getByTestId('verification-attack-toggle'))
    await expect(page.getByTestId('verification-attack-toggle'))
      .toHaveAttribute('aria-checked', 'true')
    await page.getByTestId('verification-attack-budget').fill('1')
    const attackResponsePromise = page.waitForResponse(response =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/verify')
    await page.getByTestId('run-verification').click()
    const attackResponse = await attackResponsePromise
    const attacked = await unwrap<any>(attackResponse as any)
    expect(attacked).toMatchObject({
      outcome: 'VIOLATED',
      modelComplete: true,
      attackBudget: 1,
      enablePrivacy: true,
      modelSnapshot: { specificationCount: 5 }
    })
    expect(attacked.specResults).toHaveLength(5)
    expect(attacked.specResults.filter((result: any) => result.outcome === 'VIOLATED')).toHaveLength(4)
    await page.getByTestId('close-verification-result').click()

    const fixResponse = await request.post(fixRequestUrl(baselineTrace.id), {
      headers: authHeaders(auth),
      data: { strategies: ['remove'] }
    })
    const fix = await unwrap<any>(fixResponse)
    const removal = listedRepair(fix, 'remove', suggestion => JSON.stringify(suggestion.removedRuleDescriptions)
      === JSON.stringify(['When hall motion is active, take a camera photo']))

    const applyResponse = await request.post(
      `${apiBaseURL}/api/verify/traces/${baselineTrace.id}/fix/apply`,
      {
        headers: authHeaders(auth),
        data: {
          strategy: removal.strategy,
          suggestion: removal,
          suggestionToken: removal.suggestionToken
        }
      }
    )
    const applied = await unwrap<any>(applyResponse)
    expect(applied.currentRuleCount).toBe(2)
    expect(applied.rules.map((rule: any) => rule.ruleString)).not.toContain(
      'When hall motion is active, take a camera photo')

    await page.reload()
    await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
    await page.getByTestId('open-verification-panel').click()
    await page.getByTestId('verification-mode-sync').click()
    const repairedResponsePromise = page.waitForResponse(response =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/verify')
    await page.getByTestId('run-verification').click()
    const repairedResponse = await repairedResponsePromise
    const repaired = await unwrap<any>(repairedResponse as any)
    expect(repaired).toMatchObject({
      outcome: 'SATISFIED',
      modelComplete: true,
      disabledRuleCount: 0,
      skippedSpecCount: 0,
      modelSnapshot: { specificationCount: 5 }
    })
    expect(repaired.specResults).toHaveLength(5)
    expect(repaired.specResults.every((result: any) => result.outcome === 'SATISFIED')).toBe(true)
  })

  test('withdraws counterexample actions once an applied fix makes the open verdict stale', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)

    const scenePath = path.resolve(process.cwd(), '..', 'docs', 'examples', 'acceptance-demo-scene.json')
    await page.getByTestId('scene-import-file').setInputFiles(scenePath)
    await page.getByRole('dialog', { name: 'Confirm Full Scene Replacement' })
      .getByRole('button', { name: 'Replace in full' })
      .click()
    await waitForApi<any[]>(request, auth, '/api/board/rules', rules => rules.length === 3)
    await waitForApi<any[]>(request, auth, '/api/board/specs', specs => specs.length === 5)

    await page.getByTestId('open-verification-panel').click()
    await page.getByTestId('verification-mode-sync').click()
    await page.getByTestId('run-verification').click()
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })

    // The verdict describes the model that was just verified, so it starts fresh and offers Fix.
    await expect(page.getByTestId('verification-result-stale-banner')).toHaveCount(0)
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
    await expect(page.getByTestId('verification-trace-fix-unavailable').first())
      .toContainText(/changed after this verification/i)

    // A fresh run must not inherit the stale warning.
    await page.getByTestId('close-verification-result').click()
    await page.getByTestId('open-verification-panel').click()
    await page.getByTestId('verification-mode-sync').click()
    await page.getByTestId('run-verification').click()
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })
    await expect(page.getByTestId('verification-result-stale-banner')).toHaveCount(0)
  })

  test('imports the multi-violation JSON and repairs both baseline violations through one root removal', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)

    await openControlSection(page, 'templates')
    const cameraTemplate = page.locator('.template-card[title="Camera"]').first()
    await expect(cameraTemplate).toBeVisible()
    await cameraTemplate.dragTo(page.locator('#canvas-empty-state-title'))
    await expect(page.getByTestId('template-instance-dialog')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.getByTestId('template-instance-dialog')).toBeHidden()
    await expect(page.getByTestId('empty-state-import-scene')).toBeVisible()

    const scenePath = path.resolve(process.cwd(), '..', 'docs', 'examples', 'multi-violation-repair-scene.json')
    await page.getByTestId('scene-import-file').setInputFiles(scenePath)
    await page.getByRole('dialog', { name: 'Confirm Full Scene Replacement' })
      .getByRole('button', { name: 'Replace in full' })
      .click()

    await expect(page.locator('.el-message').filter({
      hasText: 'Scene atomically replaced in full'
    })).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('.el-message').filter({
      hasText: 'The import response was not confirmed'
    })).toHaveCount(0)

    await waitForApi<any[]>(request, auth, '/api/board/nodes', nodes => nodes.length === 4)
    await waitForApi<any[]>(request, auth, '/api/board/environment', values =>
      values.length === 2 && values.some(value => value.name === 'motion' && value.trust === 'untrusted'))
    await waitForApi<any[]>(request, auth, '/api/board/rules', rules => rules.length === 3)
    await waitForApi<any[]>(request, auth, '/api/board/specs', specs => specs.length === 5)

    await page.getByTestId('open-verification-panel').click()
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
      modelSnapshot: { specificationCount: 5 }
    })
    expect(baseline.specResults).toHaveLength(5)
    expect(baseline.specResults.filter((result: any) => result.outcome === 'VIOLATED')).toHaveLength(2)
    await page.getByTestId('close-verification-result').click()

    const traces = await waitForApi<any[]>(request, auth, '/api/verify/traces', rows =>
      rows.filter(trace => trace.isAttack === false && trace.states?.length >= 2).length >= 2)
    const cameraNeverTrace = traces.find(trace => trace.isAttack === false
      && trace.violatedSpec?.templateId === '3'
      && JSON.stringify(trace.violatedSpec).includes('taking photo'))
    expect(cameraNeverTrace).toBeTruthy()

    const fixResponse = await request.post(
      fixRequestUrl(cameraNeverTrace.id),
      {
        headers: authHeaders(auth),
        data: { strategies: ['remove'] }
      }
    )
    const fix = await unwrap<any>(fixResponse)
    const removal = listedRepair(fix, 'remove', suggestion => JSON.stringify(suggestion.removedRuleDescriptions)
      === JSON.stringify(['When an untrusted hall motion signal is active, take a camera photo']))

    const applyResponse = await request.post(
      `${apiBaseURL}/api/verify/traces/${cameraNeverTrace.id}/fix/apply`,
      {
        headers: authHeaders(auth),
        data: {
          strategy: removal.strategy,
          suggestion: removal,
          suggestionToken: removal.suggestionToken
        }
      }
    )
    const applied = await unwrap<any>(applyResponse)
    expect(applied).toMatchObject({
      applied: true,
      verificationEvidenceReused: true,
      previousRuleCount: 3,
      currentRuleCount: 2
    })

    await page.reload()
    await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
    await page.getByTestId('open-verification-panel').click()
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
      modelSnapshot: { specificationCount: 5 }
    })
    expect(repaired.specResults).toHaveLength(5)
    expect(repaired.specResults.every((result: any) => result.outcome === 'SATISFIED')).toBe(true)
  })

  test('builds a board through left-panel dialogs, then simulates, verifies, replays history, and tracks async tasks', async ({ page, request }) => {
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

    const cameraRules = await addMotionRuleViaDialog(
      page, request, auth, motion.id, camera.id, 'take photo',
      'motion captures camera evidence', 1)
    expect(cameraRules[0].conditions[0].targetType).toBe('variable')
    expect(cameraRules[0].conditions[0].deviceName).toBe(motion.id)
    expect(cameraRules[0].command.action).toBe('take photo')

    const alarmRules = await addMotionRuleViaDialog(
      page, request, auth, motion.id, alarm.id, 'siren',
      'motion triggers audible alarm', 2)
    expect(alarmRules[1].command.deviceName).toBe(alarm.id)

    await openControlSection(page, 'specs')
    await page.getByTestId('spec-template-select').selectOption('3')
    await page.getByTestId('spec-add-condition-a').click()
    await fillSpecCondition(page, camera.id, 'state', null, '=', 'taking photo')
    await page.getByTestId('spec-create').click()
    await waitForApi<any[]>(request, auth, '/api/board/specs', specs => specs.length === 1)

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
    await expect(focusedSpec).toHaveClass(/ring-2/)
    await expect(focusedSpec).toBeFocused()

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
      .toHaveAttribute('aria-label', 'Open simulation settings', { timeout: 30_000 })
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
    await page.getByTestId('history-result-filter-verification').click()
    const viewTrace = page.locator('[data-testid^="view-verification-trace-"]').first()
    await expect(viewTrace).toBeVisible({ timeout: 30_000 })
    await viewTrace.click()
    await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('trace-timeline-state-0')).toBeVisible()
    await page.getByTestId('trace-timeline-state-0').click()

    expect(browserErrors).toEqual([])
  })
})
