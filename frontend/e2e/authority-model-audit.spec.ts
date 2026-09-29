import { type APIRequestContext, type APIResponse, type Page } from '@playwright/test'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {
  apiBaseURL,
  createAuthenticatedUser,
  expect,
  test,
  type AuthUser
} from './support/auth'

type CapturedModelPost = {
  path: string
  payload: any
}

const authHeaders = (auth: AuthUser) => ({
  Authorization: `Bearer ${auth.token}`
})

const fixRequestUrl = (traceId: number) =>
  `${apiBaseURL}/api/verify/traces/${traceId}/fix?requestId=${crypto.randomUUID()}`

const unwrap = async <T>(response: APIResponse): Promise<T> => {
  expect(response.ok(), await response.text()).toBeTruthy()
  const body = await response.json()
  expect(body.code, JSON.stringify(body)).toBe(200)
  return body.data as T
}

const waitForApi = async <T>(
  request: APIRequestContext,
  auth: AuthUser,
  apiPath: string,
  predicate: (value: T) => boolean,
  timeoutMs = 30_000
): Promise<T> => {
  const deadline = Date.now() + timeoutMs
  let latest: T | undefined

  while (Date.now() < deadline) {
    const response = await request.get(`${apiBaseURL}${apiPath}`, { headers: authHeaders(auth) })
    latest = await unwrap<T>(response)
    if (predicate(latest)) return latest
    await new Promise(resolve => setTimeout(resolve, 500))
  }

  throw new Error(`Timed out waiting for ${apiPath}; latest=${JSON.stringify(latest)}`)
}

const waitForTask = async <T extends { status: string }>(
  request: APIRequestContext,
  auth: AuthUser,
  apiPath: string,
  timeoutMs = 120_000
): Promise<T> => waitForApi<T>(
  request,
  auth,
  apiPath,
  task => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(task.status),
  timeoutMs
)

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

const openWorkspace = async (page: Page, auth: AuthUser) => {
  await page.setViewportSize({ width: 1440, height: 900 })
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
}

/**
 * Opens a Control Center section, tolerating a transient canvas overlap.
 *
 * After a reload the board can briefly settle into the narrow layout, where `canvas-inner` paints
 * over the tab strip and intercepts pointer events. Retrying a plain `click()` cannot escape that —
 * every attempt hits the same intercepted target until the timeout. So the retry waits for the tab
 * to actually receive pointer events, and falls back to a keyboard activation, which is not subject
 * to hit-testing at all.
 */
const openControlSection = async (page: Page, section: 'templates' | 'devices' | 'rules' | 'specs') => {
  const tab = page.getByTestId(`control-tab-${section}`)
  const panel = page.getByTestId(`control-section-${section}`)
  for (let attempt = 0; attempt < 4; attempt += 1) {
    try {
      // A short per-attempt timeout surfaces interception quickly instead of burning the whole
      // 30s default on one doomed click.
      await tab.click({ timeout: 5_000 })
    } catch {
      // Hit-testing is blocked. Activate via the keyboard, which bypasses the overlay entirely.
      await tab.focus().catch(() => undefined)
      await page.keyboard.press('Enter').catch(() => undefined)
    }
    try {
      await expect(panel).toBeVisible({ timeout: 3_000 })
      return
    } catch {
      await page.waitForTimeout(250)
    }
  }
  await expect(panel).toBeVisible({ timeout: 10_000 })
}

const waitForTemplateOption = async (page: Page, templateName: string) => {
  for (let attempt = 0; attempt < 4; attempt += 1) {
    const found = await page.waitForFunction((name) => {
      const select = document.querySelector<HTMLSelectElement>('[data-testid="single-device-template"]')
      return !!select && Array.from(select.options).some(option => option.value === name)
    }, templateName, { timeout: 5_000 }).then(() => true).catch(() => false)
    if (found) return
    await openControlSection(page, 'devices')
  }
  await page.waitForFunction((name) => {
    const select = document.querySelector<HTMLSelectElement>('[data-testid="single-device-template"]')
    return !!select && Array.from(select.options).some(option => option.value === name)
  }, templateName, { timeout: 30_000 })
}

type DeviceRuntimeInput = {
  state?: string
  currentStateTrust?: string
  currentStatePrivacy?: string
  variables?: Array<{ name: string, value: string, trust?: string }>
  privacies?: Array<{ name: string, privacy: string }>
}

const fillDeviceRuntimeFields = async (page: Page, prefix: 'single-device' | 'template-instance', runtime?: DeviceRuntimeInput) => {
  if (!runtime) return

  const runtimeDetails = page.getByTestId(`${prefix}-runtime`)
  const hasRuntimeDetails = await runtimeDetails.waitFor({ state: 'visible', timeout: 2_000 })
    .then(() => true)
    .catch(() => false)
  if (!hasRuntimeDetails) return

  const isOpen = await runtimeDetails.evaluate(element => (element as HTMLDetailsElement).open)
  if (!isOpen) {
    await page.getByTestId(`${prefix}-runtime-toggle`).click()
  }

  if (runtime.state) {
    await page.getByTestId(`${prefix}-state`).selectOption(runtime.state)
  }
  if (runtime.currentStateTrust) {
    await page.getByTestId(`${prefix}-state-trust`).selectOption(runtime.currentStateTrust)
  }
  if (runtime.currentStatePrivacy) {
    await page.getByTestId(`${prefix}-state-privacy`).selectOption(runtime.currentStatePrivacy)
  }
  for (const variable of runtime.variables || []) {
    // A variable every working state constrains has no value control: it is the state's consequence and is
    // rendered read-only. Its trust label is still the user's, though, so skipping the whole entry left
    // `trust` unset and the persisted node disagreed with what this helper was asked for.
    const control = page.getByTestId(`${prefix}-variable-${variable.name}`)
    if (await control.count() > 0) {
      const tagName = await control.evaluate(element => element.tagName.toLowerCase())
      if (tagName === 'select') {
        await control.selectOption(variable.value)
      } else {
        await control.fill(variable.value)
      }
    }
    const trustControl = page.getByTestId(`${prefix}-variable-trust-${variable.name}`)
    if (variable.trust && await trustControl.count() > 0) {
      await trustControl.selectOption(variable.trust)
    }
  }
  for (const privacy of runtime.privacies || []) {
    const privacyControl = page.getByTestId(`${prefix}-privacy-${privacy.name}`)
    if (await privacyControl.count() > 0) {
      await privacyControl.selectOption(privacy.privacy)
    }
  }
}

const expectNodeRuntime = (node: any, runtime?: DeviceRuntimeInput) => {
  if (runtime?.state) expect(node.state).toBe(runtime.state)
  if (runtime?.currentStateTrust) expect(node.currentStateTrust).toBe(runtime.currentStateTrust)
  if (runtime?.currentStatePrivacy) expect(node.currentStatePrivacy).toBe(runtime.currentStatePrivacy)
  for (const variable of runtime?.variables || []) {
    if (!node.variables?.some((item: any) => item.name === variable.name)) continue
    expect(node.variables).toEqual(expect.arrayContaining([expect.objectContaining(variable)]))
  }
  for (const privacy of runtime?.privacies || []) {
    if (!node.privacies?.some((item: any) => item.name === privacy.name)) continue
    expect(node.privacies).toEqual(expect.arrayContaining([expect.objectContaining(privacy)]))
  }
}

const saveEnvironmentRuntimeFields = async (
  request: APIRequestContext,
  auth: AuthUser,
  runtime?: DeviceRuntimeInput
) => {
  if (!runtime?.variables?.length && !runtime?.privacies?.length) return

  const currentResponse = await request.get(`${apiBaseURL}/api/board/environment`, {
    headers: authHeaders(auth)
  })
  const current = await unwrap<any[]>(currentResponse)
  const byName = new Map(current.map(variable => [variable.name, variable]))
  const updates = new Map<string, {
    name: string
    expected: { value: string | null; trust: string; privacy: string }
    desired: { value?: string; trust?: string; privacy?: string }
  }>()
  const updateFor = (name: string) => {
    const currentVariable = byName.get(name)
    if (!currentVariable) return null
    let update = updates.get(name)
    if (!update) {
      update = {
        name,
        expected: {
          value: currentVariable.value ?? null,
          trust: currentVariable.trust,
          privacy: currentVariable.privacy
        },
        desired: {}
      }
      updates.set(name, update)
    }
    return update
  }

  for (const variable of runtime.variables || []) {
    const update = updateFor(variable.name)
    if (!update) continue
    update.desired.value = variable.value
    if (variable.trust) update.desired.trust = variable.trust
  }

  for (const privacy of runtime.privacies || []) {
    const update = updateFor(privacy.name)
    if (!update) continue
    update.desired.privacy = privacy.privacy
  }

  if (updates.size === 0) return

  const saveResponse = await request.post(`${apiBaseURL}/api/board/environment`, {
    headers: authHeaders(auth),
    data: Array.from(updates.values())
  })
  await unwrap(saveResponse)
}

const addDeviceViaLeftPanel = async (
  page: Page,
  request: APIRequestContext,
  auth: AuthUser,
  templateName: string,
  label: string,
  runtime?: DeviceRuntimeInput
) => {
  await openControlSection(page, 'devices')
  await waitForTemplateOption(page, templateName)
  await page.getByTestId('single-device-template').selectOption(templateName)
  await page.getByTestId('single-device-name').fill(label)

  await fillDeviceRuntimeFields(page, 'single-device', runtime)

  await page.getByTestId('single-device-create').click()

  const nodes = await waitForApi<any[]>(request, auth, '/api/board/nodes',
    value => value.some(node => node.label === label))
  const node = nodes.find(item => item.label === label)
  expect(node.templateName).toBe(templateName)
  expectNodeRuntime(node, runtime)
  await saveEnvironmentRuntimeFields(request, auth, runtime)
  return node
}

const addDeviceViaTemplateDrag = async (
  page: Page,
  request: APIRequestContext,
  auth: AuthUser,
  templateName: string,
  label: string,
  dropRatioX = 0.55,
  dropRatioY = 0.5,
  runtime?: DeviceRuntimeInput
) => {
  await openControlSection(page, 'templates')
  const card = page.locator(`.template-card[title="${templateName}"]`).first()
  await expect(card).toBeVisible({ timeout: 30_000 })
  await card.scrollIntoViewIfNeeded()

  const dataTransfer = await page.evaluateHandle(() => new DataTransfer())
  await card.dispatchEvent('dragstart', { dataTransfer })
  await expect(page.getByTestId('template-drop-overlay')).toBeVisible({ timeout: 5_000 })

  const canvas = page.getByTestId('canvas-board')
  const box = await canvas.boundingBox()
  expect(box).not.toBeNull()
  const clientX = box!.x + box!.width * dropRatioX
  const clientY = box!.y + box!.height * dropRatioY
  await canvas.dispatchEvent('dragover', { dataTransfer, clientX, clientY })
  await canvas.dispatchEvent('drop', { dataTransfer, clientX, clientY })

  await expect(page.getByTestId('template-instance-dialog')).toBeVisible({ timeout: 10_000 })
  await page.getByTestId('template-instance-name').fill(label)
  await fillDeviceRuntimeFields(page, 'template-instance', runtime)
  await page.getByTestId('template-instance-confirm').click()
  await expect(page.getByTestId('template-instance-dialog')).toBeHidden({ timeout: 10_000 })

  const nodes = await waitForApi<any[]>(request, auth, '/api/board/nodes',
    value => value.some(node => node.label === label))
  const node = nodes.find(item => item.label === label)
  expect(node.templateName).toBe(templateName)
  expectNodeRuntime(node, runtime)
  await saveEnvironmentRuntimeFields(request, auth, runtime)
  return node
}

const selectRuleValue = async (page: Page, value: string | string[]) => {
  const valueControl = page.locator('#rule-source-value:visible')
  const tagName = await valueControl.evaluate(element => element.tagName.toLowerCase())
  if (tagName === 'select') {
    await valueControl.selectOption(value)
  } else {
    await valueControl.fill(Array.isArray(value) ? value.join(', ') : value)
    await valueControl.blur()
  }
}

const addRuleViaDialog = async (
  page: Page,
  request: APIRequestContext,
  auth: AuthUser,
  rule: {
    name: string
    sources: Array<{
      deviceId: string
      type: 'api' | 'variable' | 'mode' | 'state'
      key?: string
      relation?: string
      value?: string | string[]
    }>
    targetDeviceId: string
    targetAction: string
    contentDeviceId?: string
    content?: string
  },
  expectedRuleCount: number
) => {
  await openControlSection(page, 'rules')
  await page.getByTestId('open-rule-builder').click()
  await expect(page.getByTestId('rule-builder-dialog')).toBeVisible({ timeout: 10_000 })
  await page.locator('#rule-builder-name').fill(rule.name)

  for (const source of rule.sources) {
    await page.locator('#rule-source-device').selectOption(source.deviceId)
    await page.locator('#rule-source-type').selectOption(source.type)

    if (source.type === 'api') {
      await page.locator('#rule-source-api').selectOption(source.key || '')
    } else if (source.type === 'variable') {
      await page.locator('#rule-source-variable').selectOption(source.key || '')
    } else if (source.type === 'mode') {
      await page.locator('#rule-source-mode').selectOption(source.key || '')
    }

    if (source.type !== 'api') {
      await page.locator('#rule-source-condition').selectOption(source.relation || '=')
      await selectRuleValue(page, source.value || '')
    }

    const addSource = page.getByTestId('rule-add-source')
    await expect(addSource).toBeEnabled()
    await addSource.click()
  }

  await page.locator('#rule-target-device').selectOption(rule.targetDeviceId)
  await page.locator('#rule-target-action').selectOption(rule.targetAction)
  if (rule.contentDeviceId || rule.content) {
    await page.getByTestId('rule-content-device').selectOption(rule.contentDeviceId || '')
    await page.getByTestId('rule-content-name').selectOption(rule.content || '')
  }
  await page.getByTestId('rule-save').click()
  await expect(page.getByTestId('rule-builder-dialog')).toBeHidden({ timeout: 10_000 })

  return waitForApi<any[]>(request, auth, '/api/board/rules', rules => rules.length === expectedRuleCount)
}

const fillSpecCondition = async (
  page: Page,
  deviceId: string,
  type: string,
  key: string | null,
  relation: string,
  value: string | string[],
  propertyScope?: 'state' | 'variable',
  /**
   * Which value a `variable` condition asks about — `environment` for the shared pool value, `reported`
   * for what this device said. Required for `variable`: nothing is preselected, so Save stays disabled
   * until one is picked. A device-local variable offers only `reported` and auto-selects it, so passing
   * nothing is correct in that case.
   */
  variableSource?: 'environment' | 'reported'
) => {
  await expect(page.getByTestId('spec-condition-dialog')).toBeVisible({ timeout: 10_000 })
  await page.getByTestId('spec-condition-device').selectOption(deviceId)
  await page.getByTestId('spec-condition-type').selectOption(type)
  if (key) {
    const selectedKey = type === 'trust' || type === 'privacy'
      ? JSON.stringify([propertyScope, key])
      : key
    const keyControl = page.getByTestId('spec-condition-key')
    await expect.poll(async () => keyControl.locator('option').evaluateAll(
      (options, expected) => options.some(option => (option as HTMLOptionElement).value === expected),
      selectedKey
    ), { timeout: 10_000, message: `Missing ${type} property option ${selectedKey}` }).toBe(true)
    await keyControl.selectOption(selectedKey, { timeout: 10_000 })
  }
  // After the key: the reading fieldset only renders once a key is chosen, since which options exist
  // depends on whether that declaration is shared.
  if (variableSource) {
    await page.getByTestId(`spec-condition-variable-source-${variableSource}`).click()
  }
  await page.getByTestId('spec-condition-relation').selectOption(relation)
  const valueControl = page.locator('[data-testid="spec-condition-value"]:visible')
  const tagName = await valueControl.evaluate(element => element.tagName.toLowerCase())
  if (tagName === 'select') {
    await valueControl.selectOption(value)
  } else {
    await valueControl.fill(Array.isArray(value) ? value.join(', ') : value)
  }
  await page.getByTestId('spec-condition-save').click()
  await expect(page.getByTestId('spec-condition-dialog')).toBeHidden({ timeout: 10_000 })
}

const selectSpecTemplate = async (page: Page, templateId: string) => {
  const select = page.getByTestId('spec-template-select')
  await expect(select).toBeEnabled({ timeout: 10_000 })
  await select.selectOption(templateId)
}

const addNeverSpec = async (
  page: Page,
  request: APIRequestContext,
  auth: AuthUser,
  condition: { deviceId: string, type: string, key: string | null, propertyScope?: 'state' | 'variable', variableSource?: 'environment' | 'reported', relation: string, value: string | string[] },
  expectedSpecCount: number
) => {
  await openControlSection(page, 'specs')
  await selectSpecTemplate(page, '3')
  await page.getByTestId('spec-add-condition-a').click()
  await fillSpecCondition(page, condition.deviceId, condition.type, condition.key, condition.relation, condition.value, condition.propertyScope, condition.variableSource)
  await page.getByTestId('spec-create').click()
  return waitForApi<any[]>(request, auth, '/api/board/specs', specs => specs.length === expectedSpecCount)
}

const addAlwaysSpec = async (
  page: Page,
  request: APIRequestContext,
  auth: AuthUser,
  condition: { deviceId: string, type: string, key: string | null, propertyScope?: 'state' | 'variable', variableSource?: 'environment' | 'reported', relation: string, value: string | string[] },
  expectedSpecCount: number
) => {
  await openControlSection(page, 'specs')
  await selectSpecTemplate(page, '1')
  await page.getByTestId('spec-add-condition-a').click()
  await fillSpecCondition(page, condition.deviceId, condition.type, condition.key, condition.relation, condition.value, condition.propertyScope, condition.variableSource)
  await page.getByTestId('spec-create').click()
  return waitForApi<any[]>(request, auth, '/api/board/specs', specs => specs.length === expectedSpecCount)
}

const addResponseSpec = async (
  page: Page,
  request: APIRequestContext,
  auth: AuthUser,
  ifCondition: { deviceId: string, type: string, key: string | null, variableSource?: 'environment' | 'reported', relation: string, value: string | string[] },
  thenCondition: { deviceId: string, type: string, key: string | null, variableSource?: 'environment' | 'reported', relation: string, value: string | string[] },
  expectedSpecCount: number
) => {
  await openControlSection(page, 'specs')
  await selectSpecTemplate(page, '5')
  await page.getByTestId('spec-add-condition-if').click()
  await fillSpecCondition(page, ifCondition.deviceId, ifCondition.type, ifCondition.key, ifCondition.relation, ifCondition.value, undefined, ifCondition.variableSource)
  await page.getByTestId('spec-add-condition-then').click()
  await fillSpecCondition(page, thenCondition.deviceId, thenCondition.type, thenCondition.key, thenCondition.relation, thenCondition.value, undefined, thenCondition.variableSource)
  await page.getByTestId('spec-create').click()
  return waitForApi<any[]>(request, auth, '/api/board/specs', specs => specs.length === expectedSpecCount)
}

const normalizeNuSmvDeviceName = (name: string): string => {
  const reserved = new Set([
    'module', 'var', 'assign', 'init', 'trans', 'define', 'spec', 'ltlspec',
    'invars', 'ivar', 'fairness', 'justice', 'compassion', 'true', 'false',
    'case', 'esac', 'next', 'ex', 'ax', 'ef', 'af', 'eg', 'ag',
    'e', 'f', 'o', 'g', 'h', 'x', 'y', 'z', 'w', 'a', 'u', 's', 'v', 't',
    'bu', 'ebf', 'abf', 'ebg', 'abg'
  ])
  let normalized = name.replace(/[^a-zA-Z0-9_]/g, '_').toLowerCase()
  if (!normalized || /^_+$/.test(normalized)) normalized = 'device_0'
  if (/^\d/.test(normalized)) normalized = `_${normalized}`
  return reserved.has(normalized) ? `_${normalized}` : normalized
}

const latestSmv = (
  prefix: 'nusmv_sim_' | 'nusmv_verify_',
  sinceMs: number,
  requiredSnippets: string[] = []
) => {
  const tempDir = os.tmpdir()
  const candidates = fs.readdirSync(tempDir, { withFileTypes: true })
    .filter(entry => entry.isDirectory() && entry.name.startsWith(prefix))
    .map(entry => {
      const dir = path.join(tempDir, entry.name)
      const smvPath = path.join(dir, 'model.smv')
      if (!fs.existsSync(smvPath)) return null
      const stat = fs.statSync(smvPath)
      const text = fs.readFileSync(smvPath, 'utf8')
      return { dir, smvPath, mtimeMs: stat.mtimeMs, text }
    })
    .filter((entry): entry is { dir: string, smvPath: string, mtimeMs: number, text: string } =>
      !!entry && entry.mtimeMs >= sinceMs - 2_000)
    .sort((a, b) => b.mtimeMs - a.mtimeMs)

  const matches = requiredSnippets.length > 0
    ? candidates.filter(entry => requiredSnippets.every(snippet => entry.text.includes(snippet)))
    : candidates

  expect(
    matches.length,
    `No ${prefix} SMV file generated after ${new Date(sinceMs).toISOString()} with snippets ${JSON.stringify(requiredSnippets)}`
  ).toBeGreaterThan(0)
  const hit = matches[0]
  return {
    ...hit,
    output: fs.existsSync(path.join(hit.dir, 'output.txt'))
      ? fs.readFileSync(path.join(hit.dir, 'output.txt'), 'utf8')
      : ''
  }
}

const captureModelPosts = (page: Page) => {
  const posts: CapturedModelPost[] = []
  page.on('request', request => {
    if (request.method() !== 'POST') return
    const url = new URL(request.url())
    if (!/^\/api\/(simulate|verify)(\/async|\/traces)?$/.test(url.pathname)) return
    const payload = request.postDataJSON()
    posts.push({ path: url.pathname, payload })
  })
  return posts
}

const lastCapturedPost = (posts: CapturedModelPost[], apiPath: string) =>
  [...posts].reverse().find(post => post.path === apiPath)

const waitForApiPostResponse = (page: Page, apiPath: string) =>
  page.waitForResponse(response => {
    const url = new URL(response.url())
    return url.pathname === apiPath && response.request().method() === 'POST'
  }, { timeout: 120_000 })

const openFloatingPanel = async (page: Page, openButtonTestId: string, panelTestId: string) => {
  const panel = page.getByTestId(panelTestId)
  if (!(await panel.isVisible().catch(() => false))) {
    await page.getByTestId(openButtonTestId).click()
  }
  await expect(panel).toBeVisible({ timeout: 10_000 })
}

const closeVerificationResultWhenReady = async (page: Page) => {
  const dialog = page.getByTestId('verification-result-dialog')
  await expect(dialog).toBeVisible({ timeout: 30_000 })
  await page.getByTestId('close-verification-result').click()
  await expect(dialog).toBeHidden({ timeout: 10_000 })
}

const openHistoryPanel = async (page: Page) => {
  await openFloatingPanel(page, 'open-history-panel', 'trace-history-panel')
}

// Assert the accessible state rather than a styling class so the check keeps working
// when the switch's presentation changes.
const ensureSwitchOn = async (page: Page, testId: string) => {
  const toggle = page.getByTestId(testId)
  if (await toggle.getAttribute('aria-checked') !== 'true') {
    await toggle.click()
  }
  await expect(toggle).toHaveAttribute('aria-checked', 'true')
}

const updateNodeLayouts = async (
  request: APIRequestContext,
  auth: AuthUser,
  overridesByLabel: Record<string, { position: { x: number, y: number } }>
) => {
  const nodesResponse = await request.get(`${apiBaseURL}/api/board/nodes`, { headers: authHeaders(auth) })
  const nodes = await unwrap<any[]>(nodesResponse)
  for (const node of nodes) {
    const override = overridesByLabel[node.label]
    if (!override) continue
    await unwrap(await request.put(
      `${apiBaseURL}/api/board/nodes/${encodeURIComponent(node.id)}/layout`,
      {
        headers: authHeaders(auth),
        data: {
          position: override.position,
          width: node.width,
          height: node.height
        }
      }
    ))
  }
}

const runSyncSimulation = async (page: Page) => {
  const responsePromise = waitForApiPostResponse(page, '/api/simulate/traces')
  await openFloatingPanel(page, 'open-simulation-panel', 'simulation-panel')
  await ensureSwitchOn(page, 'simulation-privacy-toggle')
  await page.getByTestId('simulation-mode-sync').click()
  await page.getByTestId('run-simulation').click()
  const response = await responsePromise
  const result = await unwrap<any>(response)
  await expect(page.getByTestId('simulation-timeline')).toBeVisible({ timeout: 60_000 })
  expect(result.states.length).toBeGreaterThan(1)
  return result
}

const runSyncVerification = async (page: Page) => {
  const responsePromise = waitForApiPostResponse(page, '/api/verify')
  await openFloatingPanel(page, 'open-verification-panel', 'verification-panel')
  await ensureSwitchOn(page, 'verification-privacy-toggle')
  await page.getByTestId('verification-mode-sync').click()
  await page.getByTestId('run-verification').click()
  const response = await responsePromise
  const result = await unwrap<any>(response)
  await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })
  return result
}

const runAsyncSimulation = async (page: Page) => {
  const responsePromise = waitForApiPostResponse(page, '/api/simulate/async')
  await openFloatingPanel(page, 'open-simulation-panel', 'simulation-panel')
  await page.getByTestId('simulation-mode-async').click()
  await page.getByTestId('run-simulation').click()
  const response = await responsePromise
  const task = await unwrap<{ id: number }>(response)
  expect(task.id).toBeGreaterThan(0)
  return task.id
}

const runAsyncVerification = async (page: Page) => {
  const responsePromise = waitForApiPostResponse(page, '/api/verify/async')
  await openFloatingPanel(page, 'open-verification-panel', 'verification-panel')
  await page.getByTestId('verification-mode-async').click()
  await page.getByTestId('run-verification').click()
  const response = await responsePromise
  const task = await unwrap<{ id: number }>(response)
  expect(task.id).toBeGreaterThan(0)
  return task.id
}

const expectTimelinePlays = async (page: Page, testId: 'simulation-timeline' | 'trace-timeline', playTestId: string) => {
  const timeline = page.getByTestId(testId)
  const before = await timeline.getAttribute('data-selected-state-index')
  await page.getByTestId(playTestId).click()
  await expect.poll(async () => timeline.getAttribute('data-selected-state-index'), {
    timeout: 5_000
  }).not.toBe(before)
  await page.getByTestId(playTestId).click()
}

const expectTimelineNavigationAndContext = async (
  page: Page,
  testId: 'simulation-timeline' | 'trace-timeline',
  prefix: 'simulation' | 'trace'
) => {
  const timeline = page.getByTestId(testId)
  await expect(timeline).toBeVisible({ timeout: 30_000 })
  // Renamed from `${prefix}-timeline-state-details`: the two rails used ids that collided under a shared
  // suffix convention, and the panel is the step *values*, not the timeline's own details.
  const stateDetails = page.getByTestId(`${prefix}-step-values`)
  await expect(stateDetails).toBeVisible({ timeout: 30_000 })
  // Only simulation-step-values is a <details> element with a clickable <summary>: it holds the device
  // and environment tables, which are too tall to leave open on this overlay. trace-step-values is a
  // plain <div>, because it carries only the cause row.
  if (prefix === 'simulation' && !await stateDetails.evaluate(element => (element as HTMLDetailsElement).open)) {
    await stateDetails.locator(':scope > summary').click({ force: true })
  }
  // The range slider was removed in 32b22e2: it was a second x-axis over the same index as the rail,
  // which read as "two timelines". The rail took over the drag interaction the slider uniquely provided.
  // Only simulation-timeline has a testid on its scroll container; trace-timeline's scroll container
  // has no testid (the track itself is the landmark).
  if (prefix === 'simulation') {
    await expect(page.getByTestId(`${prefix}-timeline-scroll`)).toBeVisible()
  }
  // Only the simulation rail has a numeric step input. The counterexample rail's was removed: a spin box
  // asking for an absolute state index is not how anyone reads a counterexample, and the rail itself plus
  // the range control already move the cursor.
  if (prefix === 'simulation') {
    await expect(page.getByTestId(`${prefix}-timeline-step-input`)).toBeVisible()
  }
  /*
   * Only the simulation rail lists environment values; the counterexample rail no longer does.
   *
   * Three surfaces were rendering the same per-step state — the canvas nodes, the trace timeline, and the change
   * popover — and the canvas is the richest of them (value, previous value, `changed` tint, trust, security
   * pills). So the trace rail's device and environment chips were the weaker copy, and they were the dominant
   * cost: the overlay's content measured 529px inside a 318px viewport. Removing them, plus moving the
   * session-scope notice to the header, brought it to 317px with nothing hidden.
   *
   * The simulation rail still has its copy, and this assertion still covers it — but that is scope, not a
   * justification. Simulation playback also writes `highlightedTrace`, so its canvas nodes show the same
   * per-step state, which makes its env block very likely the same redundancy. It was not measured here, so it
   * was not removed here; asserting it keeps the existing behaviour pinned until someone measures that rail the
   * way this one was measured.
   */
  if (prefix === 'simulation') {
    const environment = page.getByTestId(`${prefix}-timeline-env`)
    await expect(environment).toBeVisible()
    await expect(environment).toContainText(/temperature|motion/i)
  }

  const track = page.getByTestId(`${prefix}-timeline-track`)
  // The range slider was removed in 32b22e2. State count is now derived from the number of state buttons in
  // the track (one button per state, created by v-for over states array).
  const stateCount = await track.locator('button[type="button"]').count()
  if (stateCount > 1) {
    const box = await track.boundingBox()
    expect(box).toBeTruthy()
    await track.click({ position: { x: Math.max(1, (box?.width || 2) - 2), y: Math.max(1, Math.floor((box?.height || 2) / 2)) } })
    await expect.poll(async () => timeline.getAttribute('data-selected-state-index'), {
      timeout: 5_000
    }).toBe(String(stateCount - 1))
  }

  // Move the cursor to the second state (index 1). The range slider is gone; click the second state button.
  if (stateCount > 1) {
    await track.locator('button[type="button"]').nth(1).click()
  }
  await expect.poll(async () => timeline.getAttribute('data-selected-state-index'), {
    timeout: 5_000
  }).toBe('1')

  /*
   * Which automation produced this state must not sit behind a disclosure, on either rail.
   *
   * Stated as containment rather than visibility, because this helper clicks the simulation summary
   * open further up: after that, `toBeVisible` cannot tell "outside the disclosure" from "inside it and
   * expanded", so it would pass for the very regression it is meant to catch. `toContainText` is weaker
   * still — a closed `<details>` keeps its content in the DOM. Containment holds whatever the
   * expansion state, and it is the actual requirement: the cause is chrome-free, the value tables are
   * not. Asserted past the initial state because neither rail renders a cause for state 0.
   */
  const causeRow = page.getByTestId(`${prefix}-timeline-triggered-rules`)
  await expect(causeRow).toBeVisible({ timeout: 10_000 })
  // Not "outside step-values": on the counterexample rail the cause lives inside `trace-step-values`,
  // which is a plain <div>. The requirement is that no ancestor is a disclosure at all.
  await expect(
    await causeRow.evaluate(element => element.closest('details') !== null),
    'the cause of the selected step must not sit behind a disclosure'
  ).toBe(false)

  await expect.poll(async () => page.locator('.edge-line--active').count(), {
    timeout: 5_000
  }).toBeGreaterThan(0)

  await expect.poll(async () => page.locator('.edge-label').count(), {
    timeout: 5_000
  }).toBe(0)
  const edgeHitareas = page.locator('.edge-hitarea')
  await expect(edgeHitareas.first()).toBeAttached()
  const currentEdgeMidpoint = () => edgeHitareas.evaluateAll((elements: SVGGraphicsElement[]) => {
    for (const element of elements) {
      const svg = element.ownerSVGElement
      const matrix = element.getScreenCTM()
      if (!svg || !matrix) continue
      const point = svg.createSVGPoint()
      if (element instanceof SVGLineElement) {
        point.x = (Number(element.getAttribute('x1')) + Number(element.getAttribute('x2'))) / 2
        point.y = (Number(element.getAttribute('y1')) + Number(element.getAttribute('y2'))) / 2
      } else {
        const box = element.getBBox()
        point.x = box.x + box.width / 2
        point.y = box.y + box.height / 2
      }
      const screenPoint = point.matrixTransform(matrix)
      if (
        Number.isFinite(screenPoint.x) &&
        Number.isFinite(screenPoint.y) &&
        screenPoint.x > 0 &&
        screenPoint.y > 0 &&
        screenPoint.x < window.innerWidth &&
        screenPoint.y < window.innerHeight
      ) {
        const topElement = document.elementFromPoint(screenPoint.x, screenPoint.y)
        if (topElement?.closest('.edge-layer')) {
          return { x: screenPoint.x, y: screenPoint.y }
        }
      }
    }
    return null
  })
  expect(await currentEdgeMidpoint()).toBeTruthy()
  // The midpoint is a snapshot of a canvas that may still be settling, so a single hover can land
  // on coordinates the edge has already left and reveal nothing. Re-derive and re-hover each poll.
  await expect.poll(async () => {
    const midpoint = await currentEdgeMidpoint()
    if (!midpoint) return 0
    await page.mouse.move(midpoint.x, midpoint.y)
    return page.locator('.edge-label').count()
  }, { timeout: 15_000 }).toBeGreaterThan(0)
  await page.mouse.move(4, 4)
  await expect.poll(async () => page.locator('.edge-label').count(), {
    timeout: 5_000
  }).toBe(0)

  await expect.poll(async () => page.locator('.device-node.trace-active').count(), {
    timeout: 5_000
  }).toBeGreaterThan(0)
}

test.describe('authority model full-stack audit', () => {
  test.setTimeout(900_000)
  test.beforeEach(({ page }) => {
    page.setDefaultTimeout(30_000)
  })

  test('builds multi-scene boards through UI, audits API payloads, SMV semantics, history, animation, and fix closure', async ({ page, request }) => {
    const browserErrors: string[] = []
    page.on('pageerror', error => browserErrors.push(error.message))
    page.on('console', message => {
      // Record the location too. A bare "Failed to load resource: 404" names no URL, so when this sink
      // reddens there is nothing to diagnose from — and with no trace on a non-retried run, the offending
      // request is unrecoverable afterwards.
      if (message.type() === 'error') {
        const location = message.location()
        const where = location?.url ? ` @ ${location.url}` : ''
        browserErrors.push(`${message.text()}${where}`)
      }
    })
    const capturedPosts = captureModelPosts(page)

    const auth = await createAuthenticatedUser(request)
    await waitForApi<any[]>(request, auth, '/api/board/templates',
      templates => templates.some(template => template.manifest?.Name === 'Camera')
        && templates.some(template => template.manifest?.Name === 'Thermostat')
        && templates.length >= 10,
      45_000)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)

    await openControlSection(page, 'templates')
    await page.locator('.template-card[title="Window Shade"]').first().click()
    await expect(page.locator('.template-preview')).toContainText('Window Shade', { timeout: 10_000 })
    await page.locator('.template-preview__close').click()

    const motion = await addDeviceViaLeftPanel(page, request, auth, 'Motion Detector', 'motion_entry', {
      variables: [{ name: 'motion', value: 'active', trust: 'trusted' }],
      privacies: [{ name: 'motion', privacy: 'private' }]
    })
    const camera = await addDeviceViaTemplateDrag(page, request, auth, 'Camera', 'hall_camera', 0.58, 0.34, {
      state: 'on',
      currentStateTrust: 'trusted',
      currentStatePrivacy: 'private'
    })
    const alarm = await addDeviceViaLeftPanel(page, request, auth, 'Alarm', 'night_alarm', {
      state: 'both',
      currentStateTrust: 'trusted'
    })
    const door = await addDeviceViaLeftPanel(page, request, auth, 'Door', 'front_door', {
      state: 'unlocked',
      currentStateTrust: 'trusted'
    })
    const homeMode = await addDeviceViaLeftPanel(page, request, auth, 'Home Mode', 'home_mode', {
      state: 'home;idle',
      currentStateTrust: 'trusted'
    })
    const phone = await addDeviceViaLeftPanel(page, request, auth, 'Mobile Phone', 'resident_phone', {
      state: 'on',
      variables: [
        { name: 'location', value: 'home', trust: 'trusted' },
        { name: 'steps', value: '12', trust: 'trusted' }
      ],
      privacies: [
        { name: 'location', privacy: 'public' },
        { name: 'steps', privacy: 'public' }
      ]
    })
    const tempSensor = await addDeviceViaLeftPanel(page, request, auth, 'Temperature Sensor', 'temp_sensor')
    const thermostat = await addDeviceViaLeftPanel(page, request, auth, 'Thermostat', 'main_thermostat', {
      state: 'auto;auto',
      currentStateTrust: 'trusted',
      variables: [
        { name: 'thermostatOperatingState', value: 'idle', trust: 'untrusted' },
        { name: 'thermostatSetpoint', value: '24', trust: 'untrusted' }
      ],
      privacies: [
        { name: 'thermostatOperatingState', privacy: 'public' },
        { name: 'thermostatSetpoint', privacy: 'public' }
      ]
    })
    const shade = await addDeviceViaLeftPanel(page, request, auth, 'Window Shade', 'bedroom_shade', {
      state: 'open',
      currentStateTrust: 'trusted'
    })

    await updateNodeLayouts(request, auth, {
      motion_entry: {
        position: { x: 80, y: 120 }
      },
      hall_camera: {
        position: { x: 320, y: 120 }
      },
      night_alarm: {
        position: { x: 560, y: 120 }
      },
      front_door: {
        position: { x: 80, y: 320 }
      },
      home_mode: {
        position: { x: 320, y: 320 }
      },
      resident_phone: {
        position: { x: 560, y: 320 }
      },
      temp_sensor: {
        position: { x: 80, y: 520 }
      },
      main_thermostat: {
        position: { x: 320, y: 520 }
      },
      bedroom_shade: {
        position: { x: 560, y: 520 }
      }
    })
    await page.reload()
    await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })

    await addRuleViaDialog(page, request, auth, {
      name: 'phone at home switches apartment to sleep mode',
      sources: [{ deviceId: phone.id, type: 'variable', key: 'location', relation: '=', value: 'home' }],
      targetDeviceId: homeMode.id,
      targetAction: 'set sleep mode'
    }, 1)
    await addRuleViaDialog(page, request, auth, {
      name: 'entry motion captures hallway camera evidence',
      sources: [{ deviceId: motion.id, type: 'variable', key: 'motion', relation: '=', value: 'active' }],
      targetDeviceId: camera.id,
      targetAction: 'take photo'
    }, 2)
    await addRuleViaDialog(page, request, auth, {
      name: 'entry motion triggers audible alarm',
      sources: [{ deviceId: motion.id, type: 'variable', key: 'motion', relation: '=', value: 'active' }],
      targetDeviceId: alarm.id,
      targetAction: 'siren'
    }, 3)
    await addRuleViaDialog(page, request, auth, {
      name: 'sleep mode locks the front door',
      sources: [{ deviceId: homeMode.id, type: 'mode', key: 'Mode', relation: '=', value: 'sleep' }],
      targetDeviceId: door.id,
      targetAction: 'lock'
    }, 4)
    await addRuleViaDialog(page, request, auth, {
      name: 'unlocked door sends resident alert',
      sources: [{ deviceId: door.id, type: 'state', relation: '=', value: 'unlocked' }],
      targetDeviceId: homeMode.id,
      targetAction: 'send alert message'
    }, 5)
    await addRuleViaDialog(page, request, auth, {
      name: 'high room temperature starts cooling',
      sources: [{ deviceId: tempSensor.id, type: 'variable', key: 'temperature', relation: '>', value: '28' }],
      targetDeviceId: thermostat.id,
      targetAction: 'cool'
    }, 6)
    await addRuleViaDialog(page, request, auth, {
      name: 'cooling closes the bedroom shade',
      sources: [{ deviceId: thermostat.id, type: 'mode', key: 'ThermostatMode', relation: '=', value: 'cool' }],
      targetDeviceId: shade.id,
      targetAction: 'close'
    }, 7)
    await addRuleViaDialog(page, request, auth, {
      name: 'camera photo action notifies home mode',
      sources: [{ deviceId: camera.id, type: 'api', key: 'take photo' }],
      targetDeviceId: homeMode.id,
      targetAction: 'send photo'
    }, 8)
    const rules = await addRuleViaDialog(page, request, auth, {
      name: 'camera uploads resident phone photo to cloud',
      sources: [{ deviceId: camera.id, type: 'api', key: 'take photo' }],
      targetDeviceId: phone.id,
      targetAction: 'upload to cloud',
      contentDeviceId: phone.id,
      content: 'photo'
    }, 9)
    expect(rules.length).toBe(9)

    await page.reload()
    await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })

    await addNeverSpec(page, request, auth, {
      deviceId: camera.id,
      type: 'state',
      key: null,
      relation: '=',
      value: 'taking photo'
    }, 1)
    await addResponseSpec(page, request, auth, {
      deviceId: motion.id,
      type: 'variable',
      variableSource: 'environment',
      key: 'motion',
      relation: '=',
      value: 'active'
    }, {
      deviceId: alarm.id,
      type: 'mode',
      key: 'AlertState',
      relation: '=',
      value: 'siren'
    }, 2)
    await addResponseSpec(page, request, auth, {
      deviceId: tempSensor.id,
      type: 'variable',
      variableSource: 'environment',
      key: 'temperature',
      relation: '>',
      value: '28'
    }, {
      deviceId: thermostat.id,
      type: 'mode',
      key: 'ThermostatMode',
      relation: '=',
      value: 'cool'
    }, 3)
    await addResponseSpec(page, request, auth, {
      deviceId: phone.id,
      type: 'variable',
      key: 'location',
      // `location` is a scene-level shared declaration, so the dialog offers both readings and
      // auto-selects neither; omitting this leaves the save button disabled.
      variableSource: 'environment',
      relation: '=',
      value: 'home'
    }, {
      deviceId: homeMode.id,
      type: 'mode',
      key: 'Mode',
      relation: '=',
      value: 'sleep'
    }, 4)
    await addNeverSpec(page, request, auth, {
      deviceId: tempSensor.id,
      type: 'trust',
      key: 'temperature',
      propertyScope: 'variable',
      relation: '=',
      value: 'trusted'
    }, 5)
    const specs = await addAlwaysSpec(page, request, auth, {
      deviceId: camera.id,
      type: 'privacy',
      key: 'MachineState',
      propertyScope: 'state',
      relation: '=',
      value: 'private'
    }, 6)
    expect(specs.map(spec => spec.templateId)).toEqual(['3', '5', '5', '5', '3', '1'])

    const smvName = {
      motion: normalizeNuSmvDeviceName(motion.id),
      camera: normalizeNuSmvDeviceName(camera.id),
      alarm: normalizeNuSmvDeviceName(alarm.id),
      phone: normalizeNuSmvDeviceName(phone.id),
      tempSensor: normalizeNuSmvDeviceName(tempSensor.id),
      thermostat: normalizeNuSmvDeviceName(thermostat.id),
      homeMode: normalizeNuSmvDeviceName(homeMode.id)
    }

    const simStarted = Date.now()
    const simulation = await runSyncSimulation(page)
    // The run request carries parameters only; the scene comes from the saved board. So the scene is
    // audited at its authority (the board API) and at its destination (the generated SMV), not in the
    // request body.
    const simPayload = lastCapturedPost(capturedPosts, '/api/simulate/traces')?.payload
    expect(simPayload).toBeTruthy()
    expect(simPayload.enablePrivacy).toBe(true)
    expect(simPayload.devices).toBeUndefined()
    expect(simPayload.rules).toBeUndefined()
    expect(simPayload.specs).toBeUndefined()
    const boardNodes = await waitForApi<any[]>(request, auth, '/api/board/nodes', nodes => nodes.length >= 9)
    const boardRules = await waitForApi<any[]>(request, auth, '/api/board/rules', rules => rules.length >= 9)
    const boardEnvironment = await waitForApi<any[]>(
      request, auth, '/api/board/environment', items => items.length >= 1)
    expect(boardNodes).toHaveLength(9)
    expect(boardRules).toHaveLength(9)
    expect(boardEnvironment).toEqual(expect.arrayContaining([expect.objectContaining({
      name: 'motion',
      value: 'active',
      trust: 'trusted'
    })]))
    expect(boardRules.some((rule: any) =>
      normalizeNuSmvDeviceName(rule.command?.contentDevice ?? '') === normalizeNuSmvDeviceName(phone.id)
      && rule.command?.content === 'photo')).toBeTruthy()

    const simSmv = latestSmv('nusmv_sim_', simStarted, [
      `MODULE Camera_${smvName.camera}`,
      `MODULE Thermostat_${smvName.thermostat}`
    ])
    const compactSim = simSmv.text.replace(/\s+/g, '')
    expect(simSmv.text).toContain(`MODULE MotionDetector_${smvName.motion}`)
    expect(simSmv.text).toContain(`MODULE Camera_${smvName.camera}`)
    expect(simSmv.text).toContain(`MODULE Thermostat_${smvName.thermostat}`)
    expect(simSmv.text).toContain(`${smvName.phone}.privacy_photo`)
    expect(compactSim).toContain(`${smvName.motion}.motion=active&${smvName.camera}.MachineState=on:takingphoto;`)
    expect(compactSim).toContain(`${smvName.tempSensor}.temperature>28`)
    expect(compactSim).toContain(`${smvName.thermostat}.ThermostatMode=cool`)
    expect(compactSim).toContain(`${smvName.homeMode}.Mode=sleep`)

    await expectTimelineNavigationAndContext(page, 'simulation-timeline', 'simulation')
    await expectTimelinePlays(page, 'simulation-timeline', 'simulation-timeline-play')
    await page.getByTestId('simulation-timeline-close').click()
    await waitForApi<any[]>(request, auth, '/api/simulate/traces', traces => traces.length >= 1)

    const verifyStarted = Date.now()
    const verification = await runSyncVerification(page)
    expect(verification.outcome).toBe('VIOLATED')
    expect(verification.specResults.some((result: any) => result.outcome === 'VIOLATED')).toBeTruthy()
    const verifyPayload = lastCapturedPost(capturedPosts, '/api/verify')?.payload
    expect(verifyPayload.enablePrivacy).toBe(true)
    expect(verifyPayload.specs).toBeUndefined()
    // Specifications are audited where they are authored and stored, since that is what the run reads.
    const boardSpecs = await waitForApi<any[]>(request, auth, '/api/board/specs', specs => specs.length >= 6)
    expect(boardSpecs).toHaveLength(6)
    expect(boardSpecs.some((spec: any) =>
      spec.aConditions?.some((condition: any) => condition.targetType === 'trust'
        && condition.propertyScope === 'variable' && condition.key === 'temperature'))).toBeTruthy()
    expect(boardSpecs.some((spec: any) =>
      spec.aConditions?.some((condition: any) => condition.targetType === 'privacy'
        && condition.propertyScope === 'state' && condition.key === 'MachineState'))).toBeTruthy()

    const verifySmv = latestSmv('nusmv_verify_', verifyStarted, [
      `MODULE Camera_${smvName.camera}`,
      `MODULE Thermostat_${smvName.thermostat}`,
      '-- Specifications'
    ])
    const compactVerify = verifySmv.text.replace(/\s+/g, '')
    expect(verifySmv.text).toContain('-- Specifications')
    expect(compactVerify).toContain(`CTLSPECAG!(${smvName.camera}.MachineState=takingphoto)`)
    expect(compactVerify).toContain(`CTLSPECAG((a_motion=active)->AF(${smvName.alarm}.AlertState=siren))`)
    expect(compactVerify).toContain(`CTLSPECAG((a_temperature>28)->AF(${smvName.thermostat}.ThermostatMode=cool))`)
    expect(compactVerify).toContain(`${smvName.camera}.MachineState=takingphoto&${smvName.camera}.privacy_MachineState_takingphoto=private`)

    await page.getByTestId('close-verification-result').click()
    const verificationTraces = await waitForApi<any[]>(request, auth, '/api/verify/traces',
      traces => traces.some(trace => JSON.stringify(trace.violatedSpec || {}).includes('taking photo') && trace.states?.length > 1))
    const violatingTrace = verificationTraces.find(trace => JSON.stringify(trace.violatedSpec || {}).includes('taking photo'))
    expect(violatingTrace).toBeTruthy()
    expect(violatingTrace.enablePrivacy).toBe(true)

    const asyncSimTaskId = await runAsyncSimulation(page)
    const asyncSimTask = await waitForTask<any>(request, auth, `/api/simulate/tasks/${asyncSimTaskId}`)
    expect(asyncSimTask.status).toBe('COMPLETED')
    expect(asyncSimTask.simulationTraceId).toBeTruthy()
    await expect(page.getByTestId('simulation-timeline')).toBeVisible({ timeout: 30_000 })
    await expect(page.getByTestId('simulation-timeline')).toHaveAttribute('data-selected-state-index', '0')
    await page.getByTestId('simulation-timeline-close').click()

    const asyncVerifyTaskId = await runAsyncVerification(page)
    const asyncVerifyTask = await waitForTask<any>(request, auth, `/api/verify/tasks/${asyncVerifyTaskId}`)
    expect(asyncVerifyTask.status).toBe('COMPLETED')
    expect(asyncVerifyTask.outcome).toBe('VIOLATED')
    expect(asyncVerifyTask.modelComplete).toBe(true)
    expect(asyncVerifyTask.specResults.some((result: any) => result.outcome === 'VIOLATED')).toBeTruthy()
    await waitForApi<any[]>(request, auth, `/api/verify/tasks/${asyncVerifyTaskId}/traces`,
      traces => traces.some(trace => JSON.stringify(trace.violatedSpec || {}).includes('taking photo')))
    await closeVerificationResultWhenReady(page)

    await openHistoryPanel(page)
    await page.getByTestId('history-layer-results').click()
    await page.getByTestId('history-result-filter-simulation').click()
    await expect(page.locator('[data-testid^="replay-simulation-trace-"]').first()).toBeVisible({ timeout: 30_000 })
    await page.locator('[data-testid^="replay-simulation-trace-"]').first().click()
    await expect(page.getByTestId('simulation-timeline')).toBeVisible({ timeout: 20_000 })
    await expectTimelineNavigationAndContext(page, 'simulation-timeline', 'simulation')
    await expectTimelinePlays(page, 'simulation-timeline', 'simulation-timeline-play')
    await page.getByTestId('simulation-timeline-close').click()

    await openHistoryPanel(page)
    await expect(page.getByTestId('history-layer-results')).toBeVisible({ timeout: 10_000 })
    await page.getByTestId('history-layer-results').click()
    await page.getByTestId('history-result-filter-verification').click()
    await page.getByTestId(`view-verification-trace-${violatingTrace.id}`).click()
    await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('trace-timeline-state-0')).toBeVisible()
    await expectTimelineNavigationAndContext(page, 'trace-timeline', 'trace')
    await expectTimelinePlays(page, 'trace-timeline', 'trace-timeline-play')
    await page.getByTestId('trace-timeline-close').click()

    const fixProbeResponse = await request.post(fixRequestUrl(violatingTrace.id), {
      headers: authHeaders(auth),
      data: { strategies: ['remove', 'condition'] }
    })
    const fixProbe = await unwrap<any>(fixProbeResponse)
    expect(fixProbe.fixable).toBe(true)
    expect(fixProbe.suggestions.some((suggestion: any) => suggestion.strategy === 'remove')).toBeTruthy()

    await openHistoryPanel(page)
    await page.getByTestId('history-layer-results').click()
    await page.getByTestId('history-result-filter-verification').click()
    await expect(page.getByTestId(`fix-verification-trace-${violatingTrace.id}`)).toBeVisible({ timeout: 10_000 })
    await page.getByTestId(`fix-verification-trace-${violatingTrace.id}`).click()
    await expect(page.getByTestId('fix-result-dialog')).toBeVisible({ timeout: 90_000 })
    await expect(page.getByTestId('fix-strategy-remove')).toBeVisible({ timeout: 120_000 })
    await page.getByTestId('fix-strategy-remove').click()
    await page.getByTestId('fix-try-current').click()
    await expect(page.getByTestId('fix-apply-current')).toBeVisible({ timeout: 120_000 })
    await expect(page.getByTestId('fix-apply-current')).toBeVisible({ timeout: 30_000 })
    const applyResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === `/api/verify/traces/${violatingTrace.id}/fix/apply`
        && response.request().method() === 'POST'
    }, { timeout: 120_000 })
    await page.getByTestId('fix-apply-current').click()
    await page.locator('.el-message-box').getByRole('button', { name: /Remove Rules and Apply|移除规则并应用/ }).click()
    const applyResult = await unwrap<any>(await applyResponsePromise)
    expect(applyResult.applied).toBe(true)
    await expect(page.getByTestId('fix-result-dialog')).toBeHidden({ timeout: 15_000 })

    const fixedRules = await waitForApi<any[]>(request, auth, '/api/board/rules',
      savedRules => savedRules.length < 9 && !savedRules.some(rule => rule.ruleString === 'entry motion captures hallway camera evidence'))
    expect(fixedRules.length).toBeLessThan(9)

    const postFixStarted = Date.now()
    const postFixVerification = await runSyncVerification(page)
    expect(postFixVerification.outcome).toBe('SATISFIED')
    expect(postFixVerification.modelComplete).toBe(true)
    await page.getByTestId('close-verification-result').click()
    const postFixSmv = latestSmv('nusmv_verify_', postFixStarted, [
      `MODULE Camera_${smvName.camera}`,
      `MODULE Thermostat_${smvName.thermostat}`
    ])
    expect(postFixSmv.text).not.toContain('--entry motion captures hallway camera evidence')

    const simTraces = await waitForApi<any[]>(request, auth, '/api/simulate/traces', traces => traces.length >= 2)
    const verifyRuns = await waitForApi<any[]>(request, auth, '/api/verify/runs',
      runs => runs.some(run => run.id === asyncVerifyTaskId && run.outcome === 'VIOLATED'))
    expect(simTraces.length).toBeGreaterThanOrEqual(2)
    expect(verifyRuns.some(run => run.id === asyncVerifyTaskId && run.violatedSpecCount >= 1)).toBeTruthy()

    expect(browserErrors.filter(error =>
      !error.includes('ResizeObserver loop completed with undelivered notifications'))).toEqual([])
    expect(capturedPosts.some(post => post.path === '/api/simulate/async')).toBeTruthy()
    expect(capturedPosts.some(post => post.path === '/api/verify/async')).toBeTruthy()
  })

  test('persists set-valued rule conditions from the dialog through API payloads and SMV', async ({ page, request }) => {
    const capturedPosts = captureModelPosts(page)
    const auth = await createAuthenticatedUser(request)
    await waitForApi<any[]>(request, auth, '/api/board/templates',
      templates => templates.some(template => template.manifest?.Name === 'Home Mode')
        && templates.some(template => template.manifest?.Name === 'Alarm'),
      45_000)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)

    const homeMode = await addDeviceViaLeftPanel(page, request, auth, 'Home Mode', 'home_mode_set', {
      state: 'home;idle',
      currentStateTrust: 'trusted'
    })
    const alarm = await addDeviceViaLeftPanel(page, request, auth, 'Alarm', 'set_rule_alarm', {
      state: 'both',
      currentStateTrust: 'trusted'
    })

    const rules = await addRuleViaDialog(page, request, auth, {
      name: 'night or home mode may trigger alarm check',
      sources: [{ deviceId: homeMode.id, type: 'mode', key: 'Mode', relation: 'in', value: ['sleep', 'home'] }],
      targetDeviceId: alarm.id,
      targetAction: 'siren'
    }, 1)

    expect(rules[0].conditions[0]).toMatchObject({
      deviceName: homeMode.id,
      attribute: 'Mode',
      targetType: 'mode',
      relation: 'in'
    })
    expect(rules[0].conditions[0].value).toContain('sleep')
    expect(rules[0].conditions[0].value).toContain('home')

    const stateRules = await addRuleViaDialog(page, request, auth, {
      name: 'home or sleep idle states may trigger strobe check',
      sources: [{ deviceId: homeMode.id, type: 'state', relation: 'in', value: ['home;idle', 'sleep;idle'] }],
      targetDeviceId: alarm.id,
      targetAction: 'strobe'
    }, 2)
    const stateRuleCondition = stateRules.find(rule => rule.ruleString === 'home or sleep idle states may trigger strobe check')
      ?.conditions?.[0]
    expect(stateRuleCondition).toMatchObject({
      deviceName: homeMode.id,
      attribute: 'state',
      targetType: 'state',
      relation: 'in'
    })
    expect(stateRuleCondition.value).toContain('home;idle')
    expect(stateRuleCondition.value).toContain('sleep;idle')

    const specs = await addNeverSpec(page, request, auth, {
      deviceId: homeMode.id,
      type: 'mode',
      key: 'Mode',
      relation: 'in',
      value: ['sleep', 'home']
    }, 1)
    expect(specs[0].aConditions[0]).toMatchObject({
      deviceId: homeMode.id,
      targetType: 'mode',
      key: 'Mode',
      relation: 'in'
    })
    expect(specs[0].aConditions[0].value).toContain('sleep')
    expect(specs[0].aConditions[0].value).toContain('home')
    const stateSpecs = await addNeverSpec(page, request, auth, {
      deviceId: homeMode.id,
      type: 'state',
      key: null,
      relation: 'in',
      value: ['home;idle', 'sleep;idle']
    }, 2)
    const stateSpecCondition = stateSpecs.find(spec => spec.aConditions?.[0]?.targetType === 'state')
      ?.aConditions?.[0]
    expect(stateSpecCondition).toMatchObject({
      deviceId: homeMode.id,
      targetType: 'state',
      key: 'state',
      relation: 'in'
    })
    expect(stateSpecCondition.value).toContain('home;idle')
    expect(stateSpecCondition.value).toContain('sleep;idle')

    const homeModeSmvName = normalizeNuSmvDeviceName(homeMode.id)

    const simStarted = Date.now()
    await runSyncSimulation(page)
    const simPayload = lastCapturedPost(capturedPosts, '/api/simulate/traces')?.payload
    expect(simPayload.rules).toBeUndefined()
    const boardRules = await waitForApi<any[]>(request, auth, '/api/board/rules', r => r.length >= 1)
    expect(boardRules[0].conditions[0]).toMatchObject({
      deviceName: homeMode.id,
      attribute: 'Mode',
      targetType: 'mode',
      relation: 'in'
    })
    const simSmv = latestSmv('nusmv_sim_', simStarted, [
      `${homeModeSmvName}.Mode`
    ])
    const compactSim = simSmv.text.replace(/\s+/g, '')
    expect(compactSim).toContain(`(${homeModeSmvName}.Mode=home|${homeModeSmvName}.Mode=sleep)`)
    expect(compactSim).toContain(`${homeModeSmvName}.Mode=home`)
    expect(compactSim).toContain(`${homeModeSmvName}.Mode=sleep`)
    expect(compactSim).toContain(`${homeModeSmvName}.Mode=home&${homeModeSmvName}.State=idle`)
    expect(compactSim).toContain(`${homeModeSmvName}.Mode=sleep&${homeModeSmvName}.State=idle`)
    await page.getByTestId('simulation-timeline-close').click()

    const verifyStarted = Date.now()
    const verification = await runSyncVerification(page)
    expect(verification.outcome).toBe('VIOLATED')
    expect(verification.modelComplete).toBe(true)
    expect(lastCapturedPost(capturedPosts, '/api/verify')?.payload.specs).toBeUndefined()
    const boardSpecs = await waitForApi<any[]>(request, auth, '/api/board/specs', specs => specs.length >= 1)
    expect(boardSpecs[0].aConditions[0]).toMatchObject({
      deviceId: homeMode.id,
      targetType: 'mode',
      key: 'Mode',
      relation: 'in'
    })
    expect(boardSpecs[0].aConditions[0].value).toContain('sleep')
    expect(boardSpecs[0].aConditions[0].value).toContain('home')
    const verifySmv = latestSmv('nusmv_verify_', verifyStarted, [
      `${homeModeSmvName}.Mode`
    ])
    const compactVerify = verifySmv.text.replace(/\s+/g, '')
    expect(compactVerify).toContain(`CTLSPECAG!((${homeModeSmvName}.Mode=home|${homeModeSmvName}.Mode=sleep))`)
    expect(compactVerify).toContain(`${homeModeSmvName}.Mode=home&${homeModeSmvName}.State=idle`)
    expect(compactVerify).toContain(`${homeModeSmvName}.Mode=sleep&${homeModeSmvName}.State=idle`)
    await page.getByTestId('close-verification-result').click()
  })

  test('audits kitchen emergency response semantics with a second board scenario', async ({ page, request }) => {
    const browserErrors: string[] = []
    page.on('pageerror', error => browserErrors.push(error.message))
    page.on('console', message => {
      // Record the location too. A bare "Failed to load resource: 404" names no URL, so when this sink
      // reddens there is nothing to diagnose from — and with no trace on a non-retried run, the offending
      // request is unrecoverable afterwards.
      if (message.type() === 'error') {
        const location = message.location()
        const where = location?.url ? ` @ ${location.url}` : ''
        browserErrors.push(`${message.text()}${where}`)
      }
    })
    const capturedPosts = captureModelPosts(page)

    const auth = await createAuthenticatedUser(request)
    await waitForApi<any[]>(request, auth, '/api/board/templates',
      templates => templates.some(template => template.manifest?.Name === 'Smoke Sensor')
        && templates.some(template => template.manifest?.Name === 'Gas Sensor')
        && templates.some(template => template.manifest?.Name === 'Range Hood'),
      45_000)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)

    const smoke = await addDeviceViaLeftPanel(page, request, auth, 'Smoke Sensor', 'kitchen_smoke')
    const gas = await addDeviceViaLeftPanel(page, request, auth, 'Gas Sensor', 'kitchen_gas')
    const hood = await addDeviceViaLeftPanel(page, request, auth, 'Range Hood', 'range_hood', {
      state: 'off',
      currentStateTrust: 'trusted'
    })
    const alarm = await addDeviceViaLeftPanel(page, request, auth, 'Alarm', 'kitchen_alarm', {
      state: 'off',
      currentStateTrust: 'trusted'
    })
    const light = await addDeviceViaLeftPanel(page, request, auth, 'Light', 'kitchen_light', {
      state: 'off',
      currentStateTrust: 'trusted'
    })
    const exitDoor = await addDeviceViaLeftPanel(page, request, auth, 'Door', 'kitchen_exit', {
      state: 'locked',
      currentStateTrust: 'trusted'
    })
    const waterHeater = await addDeviceViaLeftPanel(page, request, auth, 'Water Heater', 'water_heater', {
      state: 'on',
      currentStateTrust: 'trusted',
      variables: [{ name: 'waterTemperature', value: '72', trust: 'trusted' }],
      privacies: [{ name: 'waterTemperature', privacy: 'private' }]
    })
    await saveEnvironmentRuntimeFields(request, auth, {
      variables: [
        { name: 'smoke', value: 'detected', trust: 'trusted' },
        { name: 'gas', value: '85', trust: 'trusted' }
      ],
      privacies: [
        { name: 'smoke', privacy: 'public' },
        { name: 'gas', privacy: 'public' }
      ]
    })

    await updateNodeLayouts(request, auth, {
      kitchen_smoke: {
        position: { x: 100, y: 120 }
      },
      kitchen_gas: {
        position: { x: 300, y: 120 }
      },
      range_hood: {
        position: { x: 500, y: 120 }
      },
      kitchen_alarm: {
        position: { x: 100, y: 320 }
      },
      kitchen_light: {
        position: { x: 300, y: 320 }
      },
      kitchen_exit: {
        position: { x: 500, y: 320 }
      },
      water_heater: {
        position: { x: 300, y: 520 }
      }
    })
    await page.reload()
    await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })

    await addRuleViaDialog(page, request, auth, {
      name: 'smoke detection triggers kitchen alarm',
      sources: [{ deviceId: smoke.id, type: 'variable', key: 'smoke', relation: '=', value: 'detected' }],
      targetDeviceId: alarm.id,
      targetAction: 'siren'
    }, 1)
    await addRuleViaDialog(page, request, auth, {
      name: 'gas leak starts range hood',
      sources: [{ deviceId: gas.id, type: 'variable', key: 'gas', relation: '>', value: '70' }],
      targetDeviceId: hood.id,
      targetAction: 'on'
    }, 2)
    await addRuleViaDialog(page, request, auth, {
      name: 'gas leak unlocks kitchen exit',
      sources: [{ deviceId: gas.id, type: 'variable', key: 'gas', relation: '>', value: '70' }],
      targetDeviceId: exitDoor.id,
      targetAction: 'unlock'
    }, 3)
    await addRuleViaDialog(page, request, auth, {
      name: 'smoke detection turns on kitchen light',
      sources: [{ deviceId: smoke.id, type: 'variable', key: 'smoke', relation: '=', value: 'detected' }],
      targetDeviceId: light.id,
      targetAction: 'on'
    }, 4)
    await addRuleViaDialog(page, request, auth, {
      name: 'gas leak powers down water heater',
      sources: [{ deviceId: gas.id, type: 'variable', key: 'gas', relation: '>', value: '70' }],
      targetDeviceId: waterHeater.id,
      targetAction: 'off'
    }, 5)

    await addNeverSpec(page, request, auth, {
      deviceId: exitDoor.id,
      type: 'state',
      key: null,
      relation: '=',
      value: 'unlocked'
    }, 1)
    await addResponseSpec(page, request, auth, {
      deviceId: smoke.id,
      type: 'variable',
      variableSource: 'environment',
      key: 'smoke',
      relation: '=',
      value: 'detected'
    }, {
      deviceId: alarm.id,
      type: 'mode',
      key: 'AlertState',
      relation: '=',
      value: 'siren'
    }, 2)
    await addResponseSpec(page, request, auth, {
      deviceId: gas.id,
      type: 'variable',
      variableSource: 'environment',
      key: 'gas',
      relation: '>',
      value: '70'
    }, {
      deviceId: hood.id,
      type: 'mode',
      key: 'MachineState',
      relation: '=',
      value: 'on'
    }, 3)
    await addResponseSpec(page, request, auth, {
      deviceId: gas.id,
      type: 'variable',
      variableSource: 'environment',
      key: 'gas',
      relation: '>',
      value: '70'
    }, {
      deviceId: waterHeater.id,
      type: 'mode',
      key: 'MachineState',
      relation: '=',
      value: 'off'
    }, 4)
    const specs = await addNeverSpec(page, request, auth, {
      deviceId: gas.id,
      type: 'trust',
      key: 'gas',
      propertyScope: 'variable',
      relation: '=',
      value: 'untrusted'
    }, 5)
    expect(specs).toHaveLength(5)

    const smvName = {
      smoke: normalizeNuSmvDeviceName(smoke.id),
      gas: normalizeNuSmvDeviceName(gas.id),
      hood: normalizeNuSmvDeviceName(hood.id),
      alarm: normalizeNuSmvDeviceName(alarm.id),
      exitDoor: normalizeNuSmvDeviceName(exitDoor.id),
      waterHeater: normalizeNuSmvDeviceName(waterHeater.id)
    }

    const simStarted = Date.now()
    const simulation = await runSyncSimulation(page)
    expect(simulation.states.length).toBeGreaterThan(1)
    const simPayload = lastCapturedPost(capturedPosts, '/api/simulate/traces')?.payload
    expect(simPayload.devices).toBeUndefined()
    expect(simPayload.rules).toBeUndefined()
    expect(await waitForApi<any[]>(request, auth, '/api/board/nodes', n => n.length >= 7)).toHaveLength(7)
    expect(await waitForApi<any[]>(request, auth, '/api/board/rules', r => r.length >= 5)).toHaveLength(5)
    const simSmv = latestSmv('nusmv_sim_', simStarted, [
      `MODULE SmokeSensor_${smvName.smoke}`,
      `MODULE RangeHood_${smvName.hood}`
    ])
    const compactSim = simSmv.text.replace(/\s+/g, '')
    expect(simSmv.text).toContain(`MODULE SmokeSensor_${smvName.smoke}`)
    expect(simSmv.text).toContain(`MODULE GasSensor_${smvName.gas}`)
    expect(simSmv.text).toContain(`MODULE RangeHood_${smvName.hood}`)
    expect(compactSim).toContain(`next(${smvName.hood}.MachineState):=case${smvName.gas}.gas>70:on;`)
    expect(compactSim).toContain(`next(${smvName.exitDoor}.LockState):=case${smvName.gas}.gas>70:unlocked;`)
    await expectTimelinePlays(page, 'simulation-timeline', 'simulation-timeline-play')
    await page.getByTestId('simulation-timeline-close').click()

    const verifyStarted = Date.now()
    const verification = await runSyncVerification(page)
    expect(verification.outcome).toBe('VIOLATED')
    expect(verification.modelComplete).toBe(true)
    const verifyPayload = lastCapturedPost(capturedPosts, '/api/verify')?.payload
    expect(verifyPayload.devices).toBeUndefined()
    expect(verifyPayload.specs).toBeUndefined()
    expect(await waitForApi<any[]>(request, auth, '/api/board/nodes', n => n.length >= 7)).toHaveLength(7)
    expect(await waitForApi<any[]>(request, auth, '/api/board/specs', sp => sp.length >= 5)).toHaveLength(5)
    const verifySmv = latestSmv('nusmv_verify_', verifyStarted, [
      `MODULE SmokeSensor_${smvName.smoke}`,
      `MODULE RangeHood_${smvName.hood}`,
      '-- Specifications'
    ])
    const compactVerify = verifySmv.text.replace(/\s+/g, '')
    expect(compactVerify).toContain(`CTLSPECAG!(${smvName.exitDoor}.LockState=unlocked)`)
    expect(compactVerify).toContain(`CTLSPECAG((a_smoke=detected)->AF(${smvName.alarm}.AlertState=siren))`)
    expect(compactVerify).toContain(`CTLSPECAG((a_gas>70)->AF(${smvName.waterHeater}.MachineState=off))`)
    await page.getByTestId('close-verification-result').click()

    const verificationTraces = await waitForApi<any[]>(request, auth, '/api/verify/traces',
      traces => traces.some(trace => JSON.stringify(trace.violatedSpec || {}).includes('unlocked') && trace.states?.length > 1))
    const violatingTrace = verificationTraces.find(trace => JSON.stringify(trace.violatedSpec || {}).includes('unlocked'))
    expect(violatingTrace).toBeTruthy()

    const fixProbeResponse = await request.post(fixRequestUrl(violatingTrace.id), {
      headers: authHeaders(auth),
      data: { strategies: ['remove'] }
    })
    const fixProbe = await unwrap<any>(fixProbeResponse)
    expect(fixProbe.fixable).toBe(true)
    expect(fixProbe.suggestions.some((suggestion: any) => suggestion.strategy === 'remove')).toBeTruthy()

    await openHistoryPanel(page)
    await page.getByTestId('history-layer-results').click()
    await page.getByTestId('history-result-filter-verification').click()
    await page.getByTestId(`fix-verification-trace-${violatingTrace.id}`).click()
    await expect(page.getByTestId('fix-result-dialog')).toBeVisible({ timeout: 90_000 })
    await expect(page.getByTestId('fix-strategy-remove')).toBeVisible({ timeout: 120_000 })
    await page.getByTestId('fix-strategy-remove').click()
    await page.getByTestId('fix-try-current').click()
    await expect(page.getByTestId('fix-apply-current')).toBeVisible({ timeout: 120_000 })
    const applyResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === `/api/verify/traces/${violatingTrace.id}/fix/apply`
        && response.request().method() === 'POST'
    }, { timeout: 120_000 })
    await page.getByTestId('fix-apply-current').click()
    await page.locator('.el-message-box').getByRole('button', { name: /Remove Rules and Apply|移除规则并应用/ }).click()
    const applyResult = await unwrap<any>(await applyResponsePromise)
    expect(applyResult.applied).toBe(true)
    await expect(page.getByTestId('fix-result-dialog')).toBeHidden({ timeout: 15_000 })

    await waitForApi<any[]>(request, auth, '/api/board/rules',
      savedRules => savedRules.length === 4 && !savedRules.some(rule => rule.ruleString === 'gas leak unlocks kitchen exit'))
    const postFixVerification = await runSyncVerification(page)
    expect(postFixVerification.outcome).toBe('SATISFIED')
    expect(postFixVerification.modelComplete).toBe(true)
    await page.getByTestId('close-verification-result').click()

    const simTraces = await waitForApi<any[]>(request, auth, '/api/simulate/traces', traces => traces.length >= 1)
    const storedVerificationTraces = await waitForApi<any[]>(request, auth, '/api/verify/traces', traces => traces.length >= 1)
    expect(simTraces[0].steps).toBeGreaterThan(1)
    expect(storedVerificationTraces.some(trace => JSON.stringify(trace.violatedSpec || {}).includes('unlocked'))).toBeTruthy()
    expect(browserErrors.filter(error =>
      !error.includes('ResizeObserver loop completed with undelivered notifications'))).toEqual([])
  })
})
