import { chromium } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'

const apiBase = process.env.REAL_CHECK_API || 'http://127.0.0.1:8080/api'
const appUrl = process.env.REAL_CHECK_APP || 'http://127.0.0.1:3000/#/board'
const outDir = path.resolve('..', 'artifacts', 'playwright-real')
let cleanupAccount = null
fs.mkdirSync(outDir, { recursive: true })
for (const entry of fs.readdirSync(outDir)) {
  if (entry.endsWith('.png')) {
    fs.rmSync(path.join(outDir, entry))
  }
}

async function request(method, urlPath, body, token, okStatuses = [200]) {
  const res = await fetch(apiBase + urlPath, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {})
  })
  const text = await res.text()
  let json
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    json = { raw: text }
  }
  if (!okStatuses.includes(res.status)) {
    throw new Error(`${method} ${urlPath} HTTP ${res.status}: ${text}`)
  }
  if (json && typeof json.code === 'number' && json.code >= 400) {
    throw new Error(`${method} ${urlPath} result ${json.code}: ${text}`)
  }
  return json ? json.data : null
}

const get = (urlPath, token, ok) => request('GET', urlPath, undefined, token, ok)
const post = (urlPath, body, token, ok) => request('POST', urlPath, body, token, ok)

// Both bounds, matching the schema and the backend defaulters. A lone LowerBound is not a domain, and
// the old `'0'` fallback could submit a value outside the declared one — a probe failure that would
// read as a product bug.
const valueFor = variable => {
  if (Array.isArray(variable.Values) && variable.Values.length) return String(variable.Values[0])
  if (typeof variable.LowerBound === 'number' && typeof variable.UpperBound === 'number') {
    return String(variable.LowerBound)
  }
  throw new Error(`Variable '${variable?.Name}' declares no usable domain; cannot pick a probe value`)
}

const localVariablesFor = manifest => (manifest.InternalVariables || [])
  .filter(variable => variable?.IsInside === true)

// A local variable a WorkingState constrains is that state's consequence, so the probe must submit the
// value its own state declares — `Values[0]` submitted `thermostatOperatingState = cooling` beside state
// `auto;auto`, which declares `idle`, and both writer boundaries now refuse that pair. Mirrors
// `DeviceManifestModes.localInitialValue`; the environment path below keeps the pool's first-value rule.
const stateDeclaredValue = (manifest, stateName, variableName) => {
  const wanted = String(stateName ?? '').trim()
  if (!wanted) return null
  const state = (manifest.WorkingStates || [])
    .find(candidate => String(candidate?.Name ?? '').trim() === wanted)
  const dynamic = (state?.Dynamics || []).find(entry => entry?.VariableName === variableName)
  const value = dynamic?.Value
  return value !== undefined && value !== null && String(value).trim() !== '' ? String(value).trim() : null
}

const varsFor = (manifest, stateName) => localVariablesFor(manifest).map(variable => ({
  name: variable.Name,
  value: stateDeclaredValue(manifest, stateName, variable.Name) ?? valueFor(variable),
  trust: variable.Trust || 'trusted'
}))

const privaciesFor = manifest => [
  ...localVariablesFor(manifest).map(variable => ({ name: variable.Name, privacy: variable.Privacy || 'public' }))
]

const environmentDefinition = (manifest, name) => (
  (manifest.InternalVariables || []).find(variable =>
    variable?.Name === name && variable?.IsInside !== true)
  || (manifest.EnvironmentDomains || []).find(domain => domain?.Name === name)
)

const environmentVariablesFor = templates => {
  const definitions = new Map()
  for (const template of templates) {
    const manifest = template.manifest
    for (const variable of manifest.InternalVariables || []) {
      if (variable?.Name && variable?.IsInside !== true) {
        definitions.set(variable.Name, variable)
      }
    }
    for (const name of manifest.ImpactedVariables || []) {
      const definition = environmentDefinition(manifest, name)
      if (!definition) throw new Error(`Template ${template.name} has no environment domain for ${name}`)
      definitions.set(name, definition)
    }
  }
  return [...definitions.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([name, definition]) => ({
      name,
      value: valueFor(definition),
      trust: definition.Trust || 'untrusted',
      privacy: definition.Privacy || 'public'
    }))
}

const hasModes = manifest => Array.isArray(manifest?.Modes) && manifest.Modes.length > 0
const initialState = manifest => hasModes(manifest)
  ? (manifest.InitState || manifest.WorkingStates?.[0]?.Name || '')
  : 'Working'

const nusmvReservedWords = new Set([
  'module', 'var', 'assign', 'init', 'trans', 'define', 'spec', 'ltlspec',
  'invars', 'ivar', 'fairness', 'justice', 'compassion',
  'true', 'false', 'case', 'esac', 'next',
  'ex', 'ax', 'ef', 'af', 'eg', 'ag',
  'e', 'f', 'o', 'g', 'h', 'x', 'y', 'z', 'w', 'a', 'u', 's', 'v', 't',
  'bu', 'ebf', 'abf', 'ebg', 'abg'
])

function normalizeNuSmvDeviceName(name) {
  if (!name) return name
  let normalized = String(name).replace(/[^a-zA-Z0-9_]/g, '_').toLowerCase()
  if (!normalized || /^_+$/.test(normalized)) normalized = 'device_0'
  if (/^\d/.test(normalized)) normalized = `_${normalized}`
  return nusmvReservedWords.has(normalized) ? `_${normalized}` : normalized
}

const modelRule = rule => ({
  ...rule,
  conditions: (rule.conditions || []).map(condition => ({
    ...condition,
    deviceName: normalizeNuSmvDeviceName(condition.deviceName)
  })),
  command: rule.command
    ? {
        ...rule.command,
        deviceName: normalizeNuSmvDeviceName(rule.command.deviceName),
        contentDevice: rule.command.contentDevice
          ? normalizeNuSmvDeviceName(rule.command.contentDevice)
          : rule.command.contentDevice
      }
    : rule.command
})

async function seedScenario() {
  const suffix = String(Date.now()).slice(-8)
  const phone = `138${suffix}`
  const password = 'RealCheck!2026'
  await post('/auth/register', { phone, username: `real${suffix}`, password })
  const auth = await post('/auth/login', { identifier: phone, password })
  const token = auth.token
  const user = { userId: auth.userId, phone: auth.phone, username: auth.username }
  cleanupAccount = { token, password, confirmation: user.username }

  const templates = await get('/board/templates', token)
  const templateByName = new Map(templates.map(template => [template.manifest.Name, template]))
  const template = name => {
    const found = templateByName.get(name)
    if (!found) throw new Error(`Missing template ${name}`)
    return found
  }

  const light = template('Light')
  const door = template('Door')
  const alarm = template('Alarm')
  const thermostat = template('Thermostat')
  const windowShade = template('Window Shade')
  const temperatureSensor = template('Temperature Sensor')
  const referencedTemplates = [light, door, alarm, thermostat, windowShade, temperatureSensor]
  const environmentVariables = environmentVariablesFor(referencedTemplates)
  const templateSnapshots = referencedTemplates.map(({ name, manifest }) => ({ name, manifest }))

  const nodeSpecs = [
    { id: 'node_door_entry', label: 'Entrance_Door_Long_Label_01', template: door, x: -180, y: 110, width: 178, height: 126, state: 'locked' },
    { id: 'node_light_lobby', label: 'Lobby_Light_Ceiling_Extended_Label', template: light, x: 260, y: 190, width: 184, height: 128, state: 'off' },
    { id: 'node_alarm_core', label: 'Alarm_Core_Hallway', template: alarm, x: 720, y: -90, width: 168, height: 120, state: 'off' },
    { id: 'node_thermostat_west', label: 'Thermostat_West_Wing', template: thermostat, x: 1040, y: 420, width: 196, height: 132, state: initialState(thermostat.manifest) },
    { id: 'node_shade_south', label: 'South_Window_Shade', template: windowShade, x: 520, y: 560, width: 164, height: 118, state: 'closed' },
    { id: 'node_temperature_patio', label: 'Patio_Temperature_Sensor', template: temperatureSensor, x: 880, y: 170, width: 174, height: 124, state: initialState(temperatureSensor.manifest) }
  ]

  const nodes = nodeSpecs.map(node => {
    const modeDevice = hasModes(node.template.manifest)
    const stateDefinition = modeDevice
      ? (node.template.manifest.WorkingStates || []).find(state => state?.Name === node.state)
      : null
    return {
      id: node.id,
      templateName: node.template.manifest.Name,
      label: node.label,
      position: { x: node.x, y: node.y },
      state: modeDevice ? node.state : 'Working',
      width: node.width,
      height: node.height,
      ...(modeDevice ? {
        currentStateTrust: stateDefinition?.Trust || 'trusted',
        currentStatePrivacy: stateDefinition?.Privacy || 'public'
      } : {}),
      variables: varsFor(node.template.manifest, node.state),
      privacies: privaciesFor(node.template.manifest)
    }
  })

  const rules = [
    {
      conditions: [{ deviceName: 'node_door_entry', attribute: 'state', targetType: 'state', relation: '=', value: 'unlocked' }],
      command: { deviceName: 'node_light_lobby', action: 'on', contentDevice: null, content: null },
      ruleString: 'If the entrance door is unlocked, turn on the lobby light'
    },
    {
      conditions: [{ deviceName: 'node_thermostat_west', attribute: 'state', targetType: 'state', relation: '=', value: initialState(thermostat.manifest) }],
      command: { deviceName: 'node_shade_south', action: 'open', contentDevice: null, content: null },
      ruleString: 'If thermostat is active, open the south shade'
    }
  ]

  const specs = [
    {
      id: 'spec_light_eventually_on',
      templateId: '2',
      templateLabel: 'A will happen later',
      aConditions: [{ id: 'cond_light_on', side: 'a', deviceId: 'node_light_lobby', deviceLabel: 'Lobby_Light_Ceiling_Extended_Label', targetType: 'state', key: 'state', relation: '=', value: 'on' }],
      ifConditions: [],
      thenConditions: [],
      formula: '',
      devices: [{ deviceId: 'node_light_lobby', deviceLabel: 'Lobby_Light_Ceiling_Extended_Label', selectedApis: ['on'] }]
    },
    {
      id: 'spec_alarm_never_siren',
      templateId: '3',
      templateLabel: 'A never happens',
      aConditions: [{ id: 'cond_alarm_siren', side: 'a', deviceId: 'node_alarm_core', deviceLabel: 'Alarm_Core_Hallway', targetType: 'state', key: 'state', relation: '=', value: 'siren' }],
      ifConditions: [],
      thenConditions: [],
      formula: '',
      devices: [{ deviceId: 'node_alarm_core', deviceLabel: 'Alarm_Core_Hallway', selectedApis: ['siren'] }]
    }
  ]

  const layout = {
    canvasPan: { x: 210, y: 130 },
    canvasZoom: 0.82,
    panels: {
      control: { collapsed: false, width: 332, activeSection: 'devices' },
      inspector: { collapsed: false, width: 348, activeSection: 'devices' }
    }
  }

  const replacementPreview = await get('/board/replacement-preview', token)
  await post('/board/batch', {
    impactToken: replacementPreview.impactToken,
    nodes,
    environmentVariables,
    rules,
    specs,
    templateSnapshots
  }, token)
  await post('/board/layout', layout, token)
  const savedLayout = await get('/board/layout', token)

  const simRequest = {
    devices: nodes.map(node => {
      const templateInfo = templateByName.get(node.templateName)
      const modeDevice = hasModes(templateInfo?.manifest)
      return {
        varName: normalizeNuSmvDeviceName(node.id),
        templateName: node.templateName,
        ...(modeDevice ? {
          state: node.state,
          currentStateTrust: node.currentStateTrust
        } : {}),
        variables: node.variables,
        privacies: node.privacies
      }
    }),
    environmentVariables,
    rules: rules.map(modelRule),
    steps: 6,
    attackScenario: { mode: 'NONE', budget: 0, points: [] },
    enablePrivacy: false
  }

  const shortSimulationTrace = await post('/simulate/traces', { ...simRequest, steps: 1 }, token)
  const savedSimulationTrace = await post('/simulate/traces', simRequest, token)
  const longSimulationTrace = await post('/simulate/traces', { ...simRequest, steps: 28 }, token)
  const verificationRequest = {
    devices: simRequest.devices,
    environmentVariables,
    rules: rules.map(modelRule),
    specs: specs.map(spec => ({
      ...spec,
      devices: spec.devices.map(device => ({
        ...device,
        deviceId: normalizeNuSmvDeviceName(device.deviceId)
      })),
      aConditions: spec.aConditions.map(condition => ({
        ...condition,
        deviceId: normalizeNuSmvDeviceName(condition.deviceId)
      })),
      ifConditions: spec.ifConditions.map(condition => ({
        ...condition,
        deviceId: normalizeNuSmvDeviceName(condition.deviceId)
      })),
      thenConditions: spec.thenConditions.map(condition => ({
        ...condition,
        deviceId: normalizeNuSmvDeviceName(condition.deviceId)
      }))
    })),
    attackScenario: { mode: 'NONE', budget: 0, points: [] },
    enablePrivacy: false
  }
  const savedVerificationResult = await post('/verify', verificationRequest, token)

  return {
    token,
    user,
    savedLayout,
    savedSimulationTrace,
    shortSimulationTrace,
    longSimulationTrace,
    savedVerificationResult,
    simulationSummaries: await get('/simulate/traces', token),
    verificationRuns: await get('/verify/runs', token)
  }
}

function rectsOverlap(a, b, gap = 0) {
  return !(!a || !b || a.x + a.width <= b.x + gap || b.x + b.width <= a.x + gap || a.y + a.height <= b.y + gap || b.y + b.height <= a.y + gap)
}

function rectContains(outer, inner, gap = 0) {
  return Boolean(
    outer &&
    inner &&
    inner.x >= outer.x - gap &&
    inner.y >= outer.y - gap &&
    inner.x + inner.width <= outer.x + outer.width + gap &&
    inner.y + inner.height <= outer.y + outer.height + gap
  )
}

async function runUiChecks(seed) {
  const browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ viewport: { width: 1440, height: 920 }, deviceScaleFactor: 1 })
  await context.addInitScript(({ token, user }) => {
    localStorage.setItem('iot_verify_token', token)
    localStorage.setItem('iot_verify_user', JSON.stringify(user))
    localStorage.setItem('iot_verify_theme', 'light')
    localStorage.setItem('locale', 'en')
  }, { token: seed.token, user: seed.user })

  const page = await context.newPage()
  const failures = []
  const browserEvents = []
  page.on('console', msg => {
    if (['error', 'warning'].includes(msg.type())) {
      browserEvents.push(`${msg.type()}: ${msg.text()}`)
    }
  })
  page.on('pageerror', error => {
    const message = `pageerror: ${error.message}`
    browserEvents.push(message)
    failures.push(message)
  })
  const soft = (condition, message) => { if (!condition) failures.push(message) }
  const box = selector => page.locator(selector).first().boundingBox()
  const isVisible = async selector => {
    try {
      return await page.locator(selector).first().isVisible()
    } catch {
      return false
    }
  }
  const waitForVisualStability = async selectors => {
    const selectorList = Array.isArray(selectors) ? selectors : [selectors]
    await page.evaluate(({ selectorList: watchedSelectors, requiredStableFrames }) => new Promise(resolve => {
      let previousSnapshot = ''
      let stableFrames = 0
      const sample = () => {
        const snapshot = JSON.stringify(watchedSelectors.map(selector => {
          const element = document.querySelector(selector)
          if (!element) return null
          const rect = element.getBoundingClientRect()
          const style = getComputedStyle(element)
          return {
            x: rect.x,
            y: rect.y,
            width: rect.width,
            height: rect.height,
            transform: style.transform,
            opacity: style.opacity,
            display: style.display,
            visibility: style.visibility
          }
        }))
        stableFrames = snapshot === previousSnapshot ? stableFrames + 1 : 0
        previousSnapshot = snapshot
        if (stableFrames >= requiredStableFrames) {
          resolve()
        } else {
          requestAnimationFrame(sample)
        }
      }
      requestAnimationFrame(sample)
    }), { selectorList, requiredStableFrames: 3 })
  }
  const closeIfVisible = async (panelSelector, closeSelector) => {
    if (await isVisible(panelSelector)) {
      await page.locator(closeSelector).first().click({ timeout: 1000 })
      await page.locator(panelSelector).first().waitFor({ state: 'hidden', timeout: 3000 }).catch(() => {})
    }
  }
  const closeFloatingPanels = async () => {
    await closeIfVisible('[data-testid="simulation-timeline"]', '[data-testid="simulation-timeline-close"]')
    await closeIfVisible('[data-testid="simulation-panel"]', '[data-testid="close-simulation-panel"]')
    await closeIfVisible('[data-testid="verification-panel"]', '[data-testid="close-verification-panel"]')
    await closeIfVisible('[data-testid="trace-history-panel"]', '[data-testid="close-history-panel"]')
    await closeIfVisible('[data-testid="rule-recommendation-panel"]', '[data-testid="close-rule-recommendations"]')
    await closeIfVisible('[data-testid="device-recommendation-panel"]', '[data-testid="close-device-recommendations"]')
    await closeIfVisible('[data-testid="spec-recommendation-panel"]', '[data-testid="close-spec-recommendations"]')
  }
  const checkInViewport = (panelBox, label, viewport = { width: 1440, height: 920 }) => {
    soft(Boolean(panelBox), `${label} did not render a measurable box`)
    if (!panelBox) return
    soft(panelBox.x >= -1 && panelBox.y >= -1, `${label} starts outside viewport`)
    soft(panelBox.x + panelBox.width <= viewport.width + 1, `${label} overflows right edge`)
    soft(panelBox.y + panelBox.height <= viewport.height + 1, `${label} overflows bottom edge`)
  }
  const auditRootResultDialog = async (dialogSelector, closeSelector, label, viewport) => {
    await page.setViewportSize(viewport)
    await waitForVisualStability(dialogSelector)
    const metrics = await page.locator(dialogSelector).evaluate((root, closeSelectorValue) => {
      const rect = element => {
        if (!element) return null
        const value = element.getBoundingClientRect()
        return {
          x: value.x,
          y: value.y,
          width: value.width,
          height: value.height,
          right: value.right,
          bottom: value.bottom
        }
      }
      const parseRgb = value => {
        const match = String(value || '').match(/[\d.]+/g)
        return match && match.length >= 3 ? match.slice(0, 3).map(Number) : null
      }
      const luminance = color => {
        if (!color) return null
        const channels = color.map(channel => {
          const normalized = channel / 255
          return normalized <= 0.04045
            ? normalized / 12.92
            : ((normalized + 0.055) / 1.055) ** 2.4
        })
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
      }
      const surface = root.querySelector('.board-result-dialog-surface')
      const scrollBody = surface?.querySelector('[data-testid$="-scroll"], :scope > .overflow-y-auto')
      const close = root.querySelector(closeSelectorValue)
      const title = surface?.querySelector('h3')
      const surfaceStyle = surface ? getComputedStyle(surface) : null
      const titleStyle = title ? getComputedStyle(title) : null
      const surfaceLuminance = luminance(parseRgb(surfaceStyle?.backgroundColor))
      const titleLuminance = luminance(parseRgb(titleStyle?.color))
      const contrast = surfaceLuminance !== null && titleLuminance !== null
        ? (Math.max(surfaceLuminance, titleLuminance) + 0.05)
          / (Math.min(surfaceLuminance, titleLuminance) + 0.05)
        : 0
      const closeRect = rect(close)
      const closeTopmost = closeRect
        ? document.elementFromPoint(
            closeRect.x + closeRect.width / 2,
            closeRect.y + closeRect.height / 2
          )?.closest(closeSelectorValue) === close
        : false
      return {
        viewport: { width: window.innerWidth, height: window.innerHeight },
        overlay: rect(root),
        surface: rect(surface),
        close: closeRect,
        closeTopmost,
        surfaceBg: surfaceStyle?.backgroundColor || null,
        titleColor: titleStyle?.color || null,
        titleContrast: contrast,
        surfaceScrollWidth: surface?.scrollWidth || 0,
        surfaceClientWidth: surface?.clientWidth || 0,
        bodyScrollHeight: scrollBody?.scrollHeight || 0,
        bodyClientHeight: scrollBody?.clientHeight || 0,
        bodyOverflowY: scrollBody ? getComputedStyle(scrollBody).overflowY : null
      }
    }, closeSelector)
    const { surface, close, overlay } = metrics
    soft(overlay && overlay.width >= viewport.width - 1 && overlay.height >= viewport.height - 1,
      `${label} overlay does not cover ${viewport.width}x${viewport.height}: ${JSON.stringify(metrics)}`)
    soft(surface && surface.x >= -1 && surface.y >= -1
      && surface.right <= viewport.width + 1 && surface.bottom <= viewport.height + 1,
    `${label} surface escapes ${viewport.width}x${viewport.height}: ${JSON.stringify(metrics)}`)
    soft(surface && surface.height <= viewport.height * 0.92,
      `${label} leaves no viewport margin: ${JSON.stringify(metrics)}`)
    soft(close && close.x >= -1 && close.y >= -1
      && close.right <= viewport.width + 1 && close.bottom <= viewport.height + 1,
    `${label} close control is outside the viewport: ${JSON.stringify(metrics)}`)
    soft(metrics.closeTopmost, `${label} close control is occluded: ${JSON.stringify(metrics)}`)
    soft(metrics.surfaceScrollWidth <= metrics.surfaceClientWidth + 2,
      `${label} surface overflows horizontally: ${JSON.stringify(metrics)}`)
    soft(['auto', 'scroll'].includes(metrics.bodyOverflowY),
      `${label} body is not scrollable: ${JSON.stringify(metrics)}`)
    soft(metrics.titleContrast >= 4.5,
      `${label} title contrast is below WCAG AA: ${JSON.stringify(metrics)}`)
    soft(metrics.surfaceBg && metrics.surfaceBg !== 'rgb(255, 255, 255)',
      `${label} remained white in dark theme: ${JSON.stringify(metrics)}`)
    await page.screenshot({
      path: path.join(outDir, `${label.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}.png`),
      fullPage: true
    })
    return metrics
  }
  const auditNarrowFrame = async (label, {
    allowHiddenControl = false,
    allowHiddenInspector = false
  } = {}) => {
    const metrics = await page.evaluate(() => {
      const readRect = element => {
        if (!element || element.getClientRects().length === 0) return null
        const rect = element.getBoundingClientRect()
        return {
          x: rect.x,
          y: rect.y,
          width: rect.width,
          height: rect.height,
          right: rect.right,
          bottom: rect.bottom
        }
      }
      const nav = document.querySelector('.board-nav-bar')
      const visibleNavItems = nav
        ? [...nav.querySelectorAll('.logo-left, button, summary')]
          .filter(element => {
            const style = getComputedStyle(element)
            const closedDetails = element.closest('details:not([open])')
            return element.getClientRects().length > 0
              && style.display !== 'none'
              && style.visibility !== 'hidden'
              && (!closedDetails || element.tagName === 'SUMMARY')
          })
          .map(element => ({
            label: element.getAttribute('aria-label') || element.getAttribute('title') || (element.textContent || '').trim(),
            rect: readRect(element)
          }))
        : []
      return {
        viewport: { width: window.innerWidth, height: window.innerHeight },
        document: {
          scrollWidth: document.documentElement.scrollWidth,
          scrollHeight: document.documentElement.scrollHeight
        },
        nav: readRect(nav),
        control: readRect(document.querySelector('[data-testid="control-center"]')),
        inspector: readRect(document.querySelector('[data-testid="system-inspector"]')),
        visibleNavItems
      }
    })
    const { viewport, nav, control, inspector, visibleNavItems } = metrics
    soft(Boolean(nav), `${label} missing navigation: ${JSON.stringify(metrics)}`)
    soft(Boolean(control) || allowHiddenControl, `${label} missing control panel: ${JSON.stringify(metrics)}`)
    soft(Boolean(inspector) || allowHiddenInspector, `${label} missing inspector panel: ${JSON.stringify(metrics)}`)
    soft(metrics.document.scrollWidth <= viewport.width + 1,
      `${label} document overflows horizontally: ${JSON.stringify(metrics.document)}`)
    if (nav) {
      soft(nav.x >= -1 && nav.right <= viewport.width + 1 && nav.y >= -1,
        `${label} navigation overflows viewport: ${JSON.stringify(nav)}`)
    }
    for (const item of visibleNavItems) {
      soft(Boolean(item.rect && item.rect.x >= -1 && item.rect.right <= viewport.width + 1),
        `${label} navigation item overflows: ${JSON.stringify(item)}`)
    }
    for (let left = 0; left < visibleNavItems.length; left++) {
      for (let right = left + 1; right < visibleNavItems.length; right++) {
        soft(!rectsOverlap(visibleNavItems[left].rect, visibleNavItems[right].rect),
          `${label} navigation items overlap: ${JSON.stringify([visibleNavItems[left], visibleNavItems[right]])}`)
      }
    }
    if (nav && control) {
      soft(control.y >= nav.bottom - 1,
        `${label} control panel overlaps navigation: nav=${JSON.stringify(nav)} control=${JSON.stringify(control)}`)
      soft(control.x >= -1 && control.right <= viewport.width + 1,
        `${label} control panel overflows viewport: ${JSON.stringify(control)}`)
    }
    if (nav && inspector) {
      soft(inspector.y >= nav.bottom - 1,
        `${label} inspector overlaps navigation: nav=${JSON.stringify(nav)} inspector=${JSON.stringify(inspector)}`)
      soft(inspector.x >= -1 && inspector.right <= viewport.width + 1,
        `${label} inspector overflows viewport: ${JSON.stringify(inspector)}`)
    }
    if (control && inspector) {
      soft(!rectsOverlap(control, inspector, -1),
        `${label} left and right panels overlap: ${JSON.stringify({ control, inspector })}`)
    }
    return metrics
  }
  const auditActionDockReachability = async label => {
    const metrics = await page.locator('.board-floating-actions').evaluate(async rail => {
      const panel = rail.querySelector('.board-action-dock__panel')
      const buttons = panel ? [...panel.querySelectorAll('.board-tool-button')] : []
      const lastButton = buttons.at(-1)
      if (!panel || !lastButton) return null
      const readRect = element => {
        const rect = element.getBoundingClientRect()
        return {
          x: rect.x,
          y: rect.y,
          right: rect.right,
          bottom: rect.bottom,
          width: rect.width,
          height: rect.height
        }
      }
      const originalScrollTop = panel.scrollTop
      panel.scrollTop = panel.scrollHeight
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))
      const panelRect = readRect(panel)
      const lastButtonRect = readRect(lastButton)
      const centerX = lastButtonRect.x + lastButtonRect.width / 2
      const centerY = lastButtonRect.y + lastButtonRect.height / 2
      const topmost = document.elementFromPoint(centerX, centerY)
      const result = {
        panel: panelRect,
        lastButton: lastButtonRect,
        overflowY: getComputedStyle(panel).overflowY,
        scrollHeight: panel.scrollHeight,
        clientHeight: panel.clientHeight,
        scrollTop: panel.scrollTop,
        lastButtonTopmost: Boolean(topmost && (topmost === lastButton || lastButton.contains(topmost)))
      }
      panel.scrollTop = originalScrollTop
      return result
    })
    soft(Boolean(metrics), `${label} action dock has no scrollable panel or tool buttons`)
    if (!metrics) return
    const viewport = page.viewportSize()
    soft(['auto', 'scroll'].includes(metrics.overflowY),
      `${label} action dock does not expose vertical scrolling: ${JSON.stringify(metrics)}`)
    soft(metrics.scrollHeight <= metrics.clientHeight + 1 || metrics.scrollTop > 0,
      `${label} action dock cannot reach its overflowed tools: ${JSON.stringify(metrics)}`)
    soft(metrics.lastButton.y >= metrics.panel.y - 1
      && metrics.lastButton.bottom <= metrics.panel.bottom + 1,
    `${label} last action remains clipped after scrolling: ${JSON.stringify(metrics)}`)
    soft(Boolean(viewport
      && metrics.lastButton.x >= -1
      && metrics.lastButton.right <= viewport.width + 1
      && metrics.lastButton.y >= -1
      && metrics.lastButton.bottom <= viewport.height + 1),
    `${label} last action is outside the viewport after scrolling: ${JSON.stringify(metrics)}`)
    soft(metrics.lastButtonTopmost,
      `${label} last action is occluded after scrolling: ${JSON.stringify(metrics)}`)
  }
  const dismissNarrowScrim = async label => {
    const hitPoint = await page.locator('[data-testid="board-panel-scrim"]').evaluate(scrim => {
      const rect = scrim.getBoundingClientRect()
      for (let x = Math.max(1, rect.left + 2); x < Math.min(window.innerWidth, rect.right - 1); x += 8) {
        for (let y = Math.max(1, rect.top + 2); y < Math.min(window.innerHeight, rect.bottom - 1); y += 24) {
          if (document.elementFromPoint(x, y) === scrim) return { x, y }
        }
      }
      return null
    })
    soft(Boolean(hitPoint), `${label} scrim has no pointer-accessible area outside the side panels`)
    if (hitPoint) {
      await page.mouse.click(hitPoint.x, hitPoint.y)
    } else {
      const scrim = page.locator('[data-testid="board-panel-scrim"]')
      await scrim.focus()
      await scrim.press('Enter')
    }
  }
  const checkNoInlineOverflow = async (selector, label) => {
    const overflowing = await page.locator(selector).evaluateAll(elements =>
      elements
        .map(el => ({
          text: (el.textContent || '').trim(),
          isIcon: el.classList.contains('material-symbols-outlined'),
          visible: (() => {
            const rect = el.getBoundingClientRect()
            return rect.width > 0 && rect.height > 0
          })(),
          overflowX: getComputedStyle(el).overflowX,
          overflowY: getComputedStyle(el).overflowY,
          textOverflow: getComputedStyle(el).textOverflow,
          scrollWidth: el.scrollWidth,
          clientWidth: el.clientWidth,
          scrollHeight: el.scrollHeight,
          clientHeight: el.clientHeight
        }))
        .filter(item => {
          if (item.isIcon || !item.visible || !item.text) return false
          const horizontalOverflow = item.scrollWidth > item.clientWidth + 2
          const verticalOverflow = item.scrollHeight > item.clientHeight + 4
          const handlesHorizontal = item.textOverflow === 'ellipsis' || item.overflowX !== 'visible'
          const handlesVertical = item.overflowY !== 'visible'
          return (horizontalOverflow && !handlesHorizontal) || (verticalOverflow && !handlesVertical)
        })
        .slice(0, 5)
    )
    soft(overflowing.length === 0, `${label} text overflow: ${JSON.stringify(overflowing)}`)
  }
  const setCanvasZoomPercent = async percent => {
    const input = page.locator('[data-testid="canvas-map-zoom-input"]')
    await input.fill(String(percent))
    await input.dispatchEvent('change')
    await page.waitForFunction(expected => {
      const zoomInput = document.querySelector('[data-testid="canvas-map-zoom-input"]')
      return Number(zoomInput?.value) === expected
    }, percent, { timeout: 3000 })
    await waitForVisualStability('.canvas-inner')
  }
  const openAndCheckFloatingPanel = async ({ open, panel, close, screenshot, label, viewport }) => {
    await closeFloatingPanels()
    const opener = page.locator(open).first()
    await opener.click()
    await page.waitForSelector(panel, { timeout: 5000 })
    await page.waitForFunction(panelSelector => {
      const dialog = document.querySelector(panelSelector)
      return Boolean(dialog && document.activeElement && dialog.contains(document.activeElement))
    }, panel, { timeout: 3000 }).catch(() => {})
    if (screenshot) {
      await page.screenshot({ path: path.join(outDir, screenshot), fullPage: true })
    }
    const panelBox = await box(panel)
    const currentControlBox = await box('[data-testid="control-center"]')
    const currentInspectorBox = await box('[data-testid="system-inspector"]')
    const currentMapBox = await box('[data-testid="canvas-map"]')
    checkInViewport(panelBox, label, viewport || page.viewportSize() || undefined)
    soft(!rectsOverlap(panelBox, currentControlBox, -2), `${label} overlaps control panel`)
    soft(!rectsOverlap(panelBox, currentInspectorBox, -2), `${label} overlaps inspector panel`)
    soft(!currentMapBox || !rectsOverlap(panelBox, currentMapBox, -2), `${label} overlaps canvas map`)
    await checkNoInlineOverflow(`${panel} button, ${panel} label, ${panel} h3`, `${label} controls`)

    const accessibility = await page.locator(panel).first().evaluate(dialog => {
      const labelledBy = dialog.getAttribute('aria-labelledby')
      const labelledByElement = labelledBy ? document.getElementById(labelledBy) : null
      const closeButton = dialog.querySelector('[data-testid^="close-"]')
      return {
        role: dialog.getAttribute('role'),
        name: (labelledByElement?.textContent || dialog.getAttribute('aria-label') || '').trim(),
        activeIsClose: document.activeElement === closeButton
      }
    })
    soft(accessibility.role === 'dialog', `${label} role mismatch: ${accessibility.role}`)
    soft(Boolean(accessibility.name), `${label} has no accessible name`)
    soft(accessibility.activeIsClose, `${label} did not focus its close button on open`)

    const focusables = page.locator(`${panel} a[href], ${panel} button:not([disabled]), ${panel} textarea:not([disabled]), ${panel} input:not([disabled]), ${panel} select:not([disabled]), ${panel} [tabindex]:not([tabindex="-1"])`)
    const visibleFocusableIndexes = await focusables.evaluateAll(elements => elements
      .map((element, index) => ({ element, index }))
      .filter(({ element }) => {
        const style = getComputedStyle(element)
        const closedDetails = element.closest('details:not([open])')
        return element.getClientRects().length > 0
          && style.display !== 'none'
          && style.visibility !== 'hidden'
          && style.visibility !== 'collapse'
          && !element.closest('[hidden], [inert], [aria-hidden="true"]')
          && (!closedDetails || closedDetails.querySelector(':scope > summary')?.contains(element))
      })
      .map(({ index }) => index))
    soft(visibleFocusableIndexes.length > 0, `${label} has no visible focusable controls`)
    const lastVisibleFocusableIndex = visibleFocusableIndexes.at(-1)
    if (lastVisibleFocusableIndex !== undefined) {
      const lastVisibleFocusable = focusables.nth(lastVisibleFocusableIndex)
      await lastVisibleFocusable.focus()
      const focusSucceeded = await lastVisibleFocusable.evaluate(element => document.activeElement === element)
      soft(focusSucceeded, `${label} could not focus its last visible control`)
      if (!focusSucceeded) {
        await closeIfVisible(panel, close)
        return
      }
      await page.keyboard.press('Tab')
      const tabStayedInside = await page.locator(panel).first().evaluate(dialog =>
        Boolean(document.activeElement && dialog.contains(document.activeElement)))
      soft(!tabStayedInside, `${label} traps Tab despite being a non-modal floating panel`)
    }

    await page.locator(close).first().focus()
    await page.keyboard.press('Escape')
    await page.locator(panel).first().waitFor({ state: 'hidden', timeout: 3000 }).catch(() => {})
    soft(!(await isVisible(panel)), `${label} did not close on Escape`)
    if (!(await isVisible(panel))) {
      soft(await opener.evaluate(element => document.activeElement === element), `${label} did not restore focus to its opener`)
    } else {
      await closeIfVisible(panel, close)
    }
  }

  await page.goto(appUrl, { waitUntil: 'networkidle' })
  await page.waitForSelector('[data-testid="board-root"]')
  await page.waitForFunction(() => document.querySelectorAll('.device-node').length >= 6, null, { timeout: 20000 })
  await page.screenshot({ path: path.join(outDir, 'board-desktop-light.png'), fullPage: true })

  soft(await page.evaluate(() => document.documentElement.dataset.theme) === 'light', 'expected light theme')

  await setCanvasZoomPercent(100)
  const zoomInput = page.locator('[data-testid="canvas-map-zoom-input"]')
  const zoomBeforeCtrlMinus = await zoomInput.inputValue()
  const canvasBeforeCtrlMinus = await page.locator('.canvas-inner').evaluate(el => getComputedStyle(el).transform)
  await zoomInput.focus()
  await page.keyboard.press('Control+-')
  await waitForVisualStability(['[data-testid="canvas-map-zoom-input"]', '.canvas-inner'])
  const zoomAfterCtrlMinus = await zoomInput.inputValue()
  const canvasAfterCtrlMinus = await page.locator('.canvas-inner').evaluate(el => getComputedStyle(el).transform)
  soft(zoomAfterCtrlMinus === zoomBeforeCtrlMinus,
    `Ctrl+- changed the focused zoom input from ${zoomBeforeCtrlMinus} to ${zoomAfterCtrlMinus}`)
  soft(canvasAfterCtrlMinus === canvasBeforeCtrlMinus,
    `Ctrl+- changed canvas zoom while its numeric input was focused: ${canvasBeforeCtrlMinus} -> ${canvasAfterCtrlMinus}`)

  const nodeMetrics = await page.evaluate(() => ({
    nodes: document.querySelectorAll('.device-node').length,
    labels: [...document.querySelectorAll('.device-label')].map(el => ({
      text: el.textContent.trim(),
      scrollHeight: el.scrollHeight,
      clientHeight: el.clientHeight,
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
      overflow: getComputedStyle(el).overflow,
      title: el.getAttribute('title')
    })),
    states: [...document.querySelectorAll('.device-state-value')].map(el => ({
      text: el.textContent.trim(),
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
      overflow: getComputedStyle(el).overflow,
      title: el.parentElement?.getAttribute('title')
    }))
  }))
  soft(nodeMetrics.nodes >= 6, `expected >=6 nodes, got ${nodeMetrics.nodes}`)
  for (const label of nodeMetrics.labels) {
    const clipped = label.scrollHeight > label.clientHeight + 2
      || label.scrollWidth > label.clientWidth + 2
    soft(!clipped || (label.overflow !== 'visible' && label.title === label.text),
      `label overflow is not safely recoverable: ${label.text}`)
  }
  for (const state of nodeMetrics.states) {
    const clipped = state.scrollWidth > state.clientWidth + 2
    soft(!clipped || (state.overflow !== 'visible' && state.title === state.text),
      `state overflow is not safely recoverable: ${state.text}`)
  }

  const resizeNode = page.locator('.device-node[data-node-id="node_light_lobby"]')
  const readResizeMetrics = () => resizeNode.evaluate(node => {
    const rect = element => {
      const bounds = element?.getBoundingClientRect()
      return bounds
        ? { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height, right: bounds.right, bottom: bounds.bottom }
        : null
    }
    const icon = node.querySelector('.device-img')
    const label = node.querySelector('.device-label')
    const content = node.querySelector('.device-node-content')
    const nodeRect = rect(node)
    const iconRect = rect(icon)
    const contentRect = rect(content)
    return {
      node: nodeRect,
      icon: iconRect,
      content: contentRect,
      fontSize: label ? Number.parseFloat(getComputedStyle(label).fontSize) : 0,
      labelScrollWidth: label?.scrollWidth || 0,
      labelClientWidth: label?.clientWidth || 0,
      labelOverflow: label ? getComputedStyle(label).overflow : '',
      contentScrollWidth: content?.scrollWidth || 0,
      contentClientWidth: content?.clientWidth || 0,
      contentScrollHeight: content?.scrollHeight || 0,
      contentClientHeight: content?.clientHeight || 0
    }
  })
  const resizeBefore = await readResizeMetrics()
  soft(resizeBefore.node?.width >= 160 && resizeBefore.node?.width <= 210,
    `resize fixture should start near 176px wide, got ${resizeBefore.node?.width}`)
  soft(resizeBefore.node?.height >= 110 && resizeBefore.node?.height <= 150,
    `resize fixture should start near 128px high, got ${resizeBefore.node?.height}`)
  await resizeNode.hover()
  const resizeHandle = resizeNode.locator('.resize-handle.br')
  const resizeHandleBox = await resizeHandle.boundingBox()
  soft(Boolean(resizeHandleBox), 'resizable node has no measurable bottom-right handle')
  if (resizeHandleBox) {
    const startX = resizeHandleBox.x + resizeHandleBox.width / 2
    const startY = resizeHandleBox.y + resizeHandleBox.height / 2
    await page.mouse.move(startX, startY)
    await page.mouse.down()
    await page.mouse.move(startX + 250, startY + 180, { steps: 12 })
    await page.mouse.up()
  }
  await waitForVisualStability('.device-node[data-node-id="node_light_lobby"]')
  const resizeAfter = await readResizeMetrics()
  const contentContained = Boolean(
    resizeAfter.node && resizeAfter.content
    && resizeAfter.content.x >= resizeAfter.node.x - 1
    && resizeAfter.content.y >= resizeAfter.node.y - 1
    && resizeAfter.content.right <= resizeAfter.node.right + 1
    && resizeAfter.content.bottom <= resizeAfter.node.bottom + 1
  )
  const iconContained = Boolean(
    resizeAfter.node && resizeAfter.icon
    && resizeAfter.icon.x >= resizeAfter.node.x - 1
    && resizeAfter.icon.y >= resizeAfter.node.y - 1
    && resizeAfter.icon.right <= resizeAfter.node.right + 1
    && resizeAfter.icon.bottom <= resizeAfter.node.bottom + 1
  )
  soft(resizeAfter.node?.width >= (resizeBefore.node?.width || 0) + 200,
    `pointer resize did not make the node materially wider: ${resizeBefore.node?.width} -> ${resizeAfter.node?.width}`)
  soft(resizeAfter.node?.height >= (resizeBefore.node?.height || 0) + 140,
    `pointer resize did not make the node materially taller: ${resizeBefore.node?.height} -> ${resizeAfter.node?.height}`)
  soft(resizeAfter.icon?.width >= (resizeBefore.icon?.width || 0) * 1.35,
    `node icon did not grow with its container: ${resizeBefore.icon?.width} -> ${resizeAfter.icon?.width}`)
  soft(resizeAfter.fontSize >= resizeBefore.fontSize * 1.35,
    `node label font did not grow with its container: ${resizeBefore.fontSize} -> ${resizeAfter.fontSize}`)
  soft(contentContained && iconContained,
    `resized node content escaped its bounds: ${JSON.stringify(resizeAfter)}`)
  soft(resizeAfter.contentScrollWidth <= resizeAfter.contentClientWidth + 2
    && resizeAfter.contentScrollHeight <= resizeAfter.contentClientHeight + 2,
  `resized node content overflows: ${JSON.stringify(resizeAfter)}`)
  soft(resizeAfter.labelScrollWidth <= resizeAfter.labelClientWidth + 2 || resizeAfter.labelOverflow !== 'visible',
    `resized node label overflow is not contained: ${JSON.stringify(resizeAfter)}`)
  await page.screenshot({ path: path.join(outDir, 'board-node-resized-light.png'), fullPage: true })

  const controlBox = await box('[data-testid="control-center"]')
  const inspectorBox = await box('[data-testid="system-inspector"]')
  const canvasBox = await box('[data-testid="canvas-board"]')
  const mapBox = await box('[data-testid="canvas-map"]')
  const actionRailBox = await box('.board-floating-actions')
  soft(controlBox && controlBox.width >= 300 && controlBox.width <= 360, `control width unexpected: ${controlBox?.width}`)
  soft(inspectorBox && inspectorBox.width >= 320 && inspectorBox.width <= 370, `inspector width unexpected: ${inspectorBox?.width}`)
  soft(canvasBox && canvasBox.width > 1000, `canvas too narrow: ${canvasBox?.width}`)
  soft(Boolean(mapBox), 'canvas map did not render inside inspector panel')
  soft(!rectsOverlap(mapBox, controlBox, -2), 'canvas map overlaps control panel')
  soft(rectContains(inspectorBox, mapBox, 2), 'canvas map is not contained by inspector panel')
  soft(!rectsOverlap(actionRailBox, inspectorBox, -2), 'floating action rail overlaps inspector panel')
  soft(actionRailBox && actionRailBox.height < 920 * 0.7, `floating action rail too tall: ${actionRailBox?.height}`)
  soft(await page.locator('.board-floating-actions .absolute.-top-1').count() === 0, 'floating action rail still renders an unexplained corner badge')
  const toolRailAudit = await page.locator('.board-floating-actions').evaluate(rail => ({
    role: rail.getAttribute('role'),
    ariaLabel: rail.getAttribute('aria-label'),
    toggle: (() => {
      const button = rail.querySelector('[data-testid="toggle-action-dock"]')
      return button
        ? {
            ariaLabel: button.getAttribute('aria-label'),
            ariaPressed: button.getAttribute('aria-pressed'),
            ariaExpanded: button.getAttribute('aria-expanded'),
            icon: button.querySelector('.material-symbols-outlined')?.textContent?.trim() || ''
          }
        : null
    })(),
    groups: Array.from(rail.querySelectorAll('[role="group"]')).map(group => ({
      testId: group.getAttribute('data-testid'),
      ariaLabel: group.getAttribute('aria-label')
    })),
    buttons: Array.from(rail.querySelectorAll('.board-tool-button')).map(button => ({
      testId: button.getAttribute('data-testid'),
      ariaLabel: button.getAttribute('aria-label'),
      ariaPressed: button.getAttribute('aria-pressed'),
      icon: button.querySelector('.material-symbols-outlined')?.textContent?.trim() || '',
      label: button.querySelector('.board-tool-label')?.textContent?.trim() || '',
      labelVisible: (() => {
        const label = button.querySelector('.board-tool-label')
        if (!label) return false
        const rect = label.getBoundingClientRect()
        return rect.width > 0 && rect.height > 0 && getComputedStyle(label).display !== 'none'
      })()
    }))
  }))
  soft(toolRailAudit.role === 'toolbar', `floating action rail role mismatch: ${toolRailAudit.role}`)
  soft(Boolean(toolRailAudit.ariaLabel), 'floating action rail missing aria-label')
  soft(toolRailAudit.groups.length === 2, `floating action rail expected 2 groups, got ${toolRailAudit.groups.length}`)
  soft(toolRailAudit.groups.some(group => group.testId === 'run-tool-group' && group.ariaLabel), 'run tool group missing accessible name')
  soft(toolRailAudit.groups.some(group => group.testId === 'ai-tool-group' && group.ariaLabel), 'AI tool group missing accessible name')
  soft(toolRailAudit.toggle && toolRailAudit.toggle.ariaLabel && toolRailAudit.toggle.ariaExpanded !== null, `action dock toggle missing aria metadata: ${JSON.stringify(toolRailAudit.toggle)}`)
  soft(toolRailAudit.buttons.length === 8, `floating action rail expected 8 buttons, got ${toolRailAudit.buttons.length}`)
  soft(toolRailAudit.buttons.every(button => button.ariaLabel && button.ariaPressed !== null), `tool buttons missing aria metadata: ${JSON.stringify(toolRailAudit.buttons)}`)
  soft(new Set(toolRailAudit.buttons.map(button => button.icon)).size === toolRailAudit.buttons.length, `tool icons are not visually distinct: ${JSON.stringify(toolRailAudit.buttons.map(button => button.icon))}`)
  soft(toolRailAudit.buttons.every(button => button.labelVisible && button.label), `desktop tool labels are not visible: ${JSON.stringify(toolRailAudit.buttons)}`)
  await checkNoInlineOverflow('.board-floating-actions button', 'floating action buttons')

  for (const section of ['devices', 'templates', 'rules', 'specs']) {
    await page.locator(`[data-testid="control-tab-${section}"]`).click()
    await page.waitForSelector(`[data-testid="control-section-${section}"]`, { timeout: 3000 })
    await checkNoInlineOverflow('[data-testid="control-center"] button, [data-testid="control-center"] label, [data-testid="control-center"] h2, [data-testid="control-center"] h4', `control center ${section}`)
  }
  await page.locator('[data-testid="control-tab-devices"]').click()
  await checkNoInlineOverflow('[data-testid="system-inspector"] h2, [data-testid="system-inspector"] h3, [data-testid="system-inspector"] h4, [data-testid="system-inspector"] span, [data-testid="system-inspector"] p', 'system inspector')
  const inspectorTabAudit = await page.locator('[data-testid^="inspector-tab-"]').evaluateAll(tabs => tabs.map(tab => ({
    testId: tab.getAttribute('data-testid'),
    role: tab.getAttribute('role'),
    ariaSelected: tab.getAttribute('aria-selected'),
    ariaPressed: tab.getAttribute('aria-pressed'),
    text: (tab.textContent || '').trim()
  })))
  soft(inspectorTabAudit.length === 3, `system inspector expected 3 entity tabs, got ${inspectorTabAudit.length}`)
  soft(inspectorTabAudit.every(tab =>
    tab.role === 'tab' && tab.ariaSelected !== null && tab.ariaPressed === null && tab.text),
  `system inspector tabs missing or mixing selection semantics: ${JSON.stringify(inspectorTabAudit)}`)
  for (const section of ['rules', 'specs', 'devices']) {
    await page.locator(`[data-testid="inspector-tab-${section}"]`).click()
    await page.waitForSelector(`[data-testid="inspector-section-${section}"]`, { timeout: 3000 })
    await checkNoInlineOverflow(`[data-testid="inspector-section-${section}"] button, [data-testid="inspector-section-${section}"] h3, [data-testid="inspector-section-${section}"] h4, [data-testid="inspector-section-${section}"] p`, `system inspector ${section}`)
  }
  await page.locator('[data-testid="inspector-tab-specs"]').click()
  await page.waitForSelector('[data-testid="inspector-section-specs"]', { timeout: 3000 })
  await page.locator('[data-testid="inspector-add-spec"]').click()
  await page.waitForSelector('[data-testid="control-section-specs"]', { timeout: 3000 })
  await page.locator('[data-testid="inspector-tab-devices"]').click()
  await page.waitForSelector('[data-testid="inspector-section-devices"]', { timeout: 3000 })
  await page.locator('[data-testid="inspector-add-device"]').click()
  await page.waitForSelector('[data-testid="control-section-devices"]', { timeout: 3000 })

  const fallbackIcon = await page.locator('.device-img').first().evaluate(img => {
    img.setAttribute('src', '/assets/definitely-missing-device-icon.svg')
    img.dispatchEvent(new Event('error'))
    return img.getAttribute('src')
  })
  soft(fallbackIcon?.startsWith('data:image/svg+xml;base64,'), `missing device image did not fall back to inline SVG: ${fallbackIcon}`)

  const canvasBeforeFit = await page.locator('.canvas-inner').evaluate(el => getComputedStyle(el).transform)
  await page.locator('[data-testid="canvas-map-fit"]').click()
  await page.waitForFunction(previousTransform => {
    const canvas = document.querySelector('.canvas-inner')
    return Boolean(canvas && getComputedStyle(canvas).transform !== previousTransform)
  }, canvasBeforeFit, { timeout: 3000 }).catch(() => {})
  await waitForVisualStability('.canvas-inner')
  const canvasAfterFit = await page.locator('.canvas-inner').evaluate(el => getComputedStyle(el).transform)
  soft(canvasAfterFit !== canvasBeforeFit, 'canvas map fit button did not adjust viewport transform')

  const gridRendering = await page.evaluate(() => {
    const canvas = document.querySelector('[data-testid="canvas-board"]')
    const inner = document.querySelector('.canvas-inner')
    const canvasStyle = canvas ? getComputedStyle(canvas) : null
    const innerStyle = inner ? getComputedStyle(inner) : null
    return {
      canvasBg: canvasStyle?.backgroundImage,
      canvasSize: canvasStyle?.backgroundSize,
      innerBg: innerStyle?.backgroundImage
    }
  })
  soft(gridRendering.canvasBg?.includes('linear-gradient'), 'canvas grid is not painted on the viewport')
  soft(gridRendering.innerBg === 'none', `canvas inner still owns grid background: ${gridRendering.innerBg}`)

  const surfaceColorsLight = await page.evaluate(() => {
    const css = (selector, prop) => getComputedStyle(document.querySelector(selector)).getPropertyValue(prop)
    return {
      controlBg: css('[data-testid="control-center"]', 'background-image') || css('[data-testid="control-center"]', 'background-color'),
      mapBg: css('[data-testid="canvas-map"]', 'background-color')
    }
  })

  await openAndCheckFloatingPanel({
    open: '[data-testid="open-verification-panel"]',
    panel: '[data-testid="verification-panel"]',
    close: '[data-testid="close-verification-panel"]',
    screenshot: 'verification-panel-light.png',
    label: 'verification panel'
  })
  await openAndCheckFloatingPanel({
    open: '[data-testid="open-simulation-panel"]',
    panel: '[data-testid="simulation-panel"]',
    close: '[data-testid="close-simulation-panel"]',
    screenshot: 'simulation-panel-light.png',
    label: 'simulation panel'
  })
  await openAndCheckFloatingPanel({
    open: '[data-testid="open-history-panel"]',
    panel: '[data-testid="trace-history-panel"]',
    close: '[data-testid="close-history-panel"]',
    screenshot: 'history-panel-light.png',
    label: 'history panel'
  })
  await openAndCheckFloatingPanel({
    open: '[data-testid="open-rule-recommendations"]',
    panel: '[data-testid="rule-recommendation-panel"]',
    close: '[data-testid="close-rule-recommendations"]',
    screenshot: 'rule-recommendations-light.png',
    label: 'rule recommendation panel'
  })
  await openAndCheckFloatingPanel({
    open: '[data-testid="open-device-recommendations"]',
    panel: '[data-testid="device-recommendation-panel"]',
    close: '[data-testid="close-device-recommendations"]',
    screenshot: 'device-recommendations-light.png',
    label: 'device recommendation panel'
  })
  await openAndCheckFloatingPanel({
    open: '[data-testid="open-spec-recommendations"]',
    panel: '[data-testid="spec-recommendation-panel"]',
    close: '[data-testid="close-spec-recommendations"]',
    screenshot: 'spec-recommendations-light.png',
    label: 'specification recommendation panel'
  })

  const floatingPanels = [
    {
      open: '[data-testid="open-verification-panel"]',
      panel: '[data-testid="verification-panel"]',
      close: '[data-testid="close-verification-panel"]',
      label: 'verification panel'
    },
    {
      open: '[data-testid="open-simulation-panel"]',
      panel: '[data-testid="simulation-panel"]',
      close: '[data-testid="close-simulation-panel"]',
      label: 'simulation panel'
    },
    {
      open: '[data-testid="open-history-panel"]',
      panel: '[data-testid="trace-history-panel"]',
      close: '[data-testid="close-history-panel"]',
      label: 'history panel'
    },
    {
      open: '[data-testid="open-rule-recommendations"]',
      panel: '[data-testid="rule-recommendation-panel"]',
      close: '[data-testid="close-rule-recommendations"]',
      label: 'rule recommendation panel'
    },
    {
      open: '[data-testid="open-device-recommendations"]',
      panel: '[data-testid="device-recommendation-panel"]',
      close: '[data-testid="close-device-recommendations"]',
      label: 'device recommendation panel'
    },
    {
      open: '[data-testid="open-spec-recommendations"]',
      panel: '[data-testid="spec-recommendation-panel"]',
      close: '[data-testid="close-spec-recommendations"]',
      label: 'specification recommendation panel'
    }
  ]

  await page.setViewportSize({ width: 720, height: 720 })
  await waitForVisualStability(['.board-nav-bar', '[data-testid="control-center"]', '[data-testid="system-inspector"]'])
  const midWidthNavigation = await page.evaluate(() => {
    const rect = selector => {
      const element = document.querySelector(selector)
      if (!element) return null
      const box = element.getBoundingClientRect()
      return { left: box.left, right: box.right, width: box.width, height: box.height }
    }
    const menu = document.querySelector('details.scene-actions-menu')
    return {
      account: rect('[data-testid="open-account-center"]'),
      sceneMenuVisible: Boolean(menu && menu.getClientRects().length),
      visibleFullSceneActions: [...document.querySelectorAll('.scene-action-btn')]
        .filter(element => element.getClientRects().length > 0).length
    }
  })
  soft(midWidthNavigation.account
    && midWidthNavigation.account.left >= 0
    && midWidthNavigation.account.right <= 720,
  `720px navigation account is outside the viewport: ${JSON.stringify(midWidthNavigation.account)}`)
  soft(midWidthNavigation.account?.width >= 44 && midWidthNavigation.account?.height >= 44,
    `720px navigation account is below the 44px target: ${JSON.stringify(midWidthNavigation.account)}`)
  soft(midWidthNavigation.sceneMenuVisible, '720px navigation did not expose the compact scene-actions menu')
  soft(midWidthNavigation.visibleFullSceneActions === 0,
    `720px navigation still renders ${midWidthNavigation.visibleFullSceneActions} full scene actions`)

  await page.setViewportSize({ width: 1100, height: 820 })
  await waitForVisualStability(['.board-nav-bar', '[data-testid="control-center"]', '[data-testid="system-inspector"]'])
  for (const panelConfig of floatingPanels) {
    await openAndCheckFloatingPanel({
      ...panelConfig,
      viewport: { width: 1100, height: 820 },
      label: `${panelConfig.label} at 1100px`
    })
  }
  await page.setViewportSize({ width: 1440, height: 920 })
  await waitForVisualStability(['.board-nav-bar', '[data-testid="control-center"]', '[data-testid="system-inspector"]'])

  await page.locator('[data-testid="open-history-panel"]').click()
  await page.waitForSelector('[data-testid="trace-history-panel"]')
  const historyBox = await box('[data-testid="trace-history-panel"]')
  soft(historyBox && historyBox.x + historyBox.width <= 1440 && historyBox.y >= 0, 'history panel outside viewport')
  soft(!rectsOverlap(historyBox, controlBox, -2), 'history panel overlaps control panel')
  await closeIfVisible('[data-testid="trace-history-panel"]', '[data-testid="close-history-panel"]')

  await page.locator('[data-testid="open-simulation-panel"]').click()
  await page.waitForSelector('[data-testid="simulation-panel"]')
  const simulationPanelBox = await box('[data-testid="simulation-panel"]')
  soft(simulationPanelBox && simulationPanelBox.x + simulationPanelBox.width <= 1440, 'simulation panel outside viewport')
  soft(!rectsOverlap(simulationPanelBox, inspectorBox, -2), 'simulation panel overlaps inspector panel')
  await closeIfVisible('[data-testid="simulation-panel"]', '[data-testid="close-simulation-panel"]')

  const replaySimulationTrace = async (trace, label) => {
    if (!trace) return null
    await closeFloatingPanels()
    await page.locator('[data-testid="open-history-panel"]').click()
    await page.waitForSelector('[data-testid="trace-history-panel"]')
    await page.locator('[data-testid="history-layer-results"]').click()
    await page.waitForSelector('[data-testid="history-result-filter-simulation"]')
    await page.locator('[data-testid="history-result-filter-simulation"]').click()
    const replaySelector = `[data-testid="replay-simulation-trace-${trace.id}"]`
    try {
      await page.waitForSelector(replaySelector, { timeout: 10000 })
      await page.locator(replaySelector).click()
      await page.waitForSelector('[data-testid="simulation-timeline"]', { timeout: 10000 })
      await page.screenshot({ path: path.join(outDir, `simulation-timeline-${label}.png`), fullPage: true })
      const timelineBox = await box('[data-testid="simulation-timeline"]')
      const mapDuringTimelineBox = await box('[data-testid="canvas-map"]')
      soft(timelineBox && timelineBox.y + timelineBox.height <= 920, 'simulation timeline overflows bottom')
      soft(!rectsOverlap(timelineBox, controlBox, -2), 'simulation timeline overlaps control panel')
      soft(!rectsOverlap(timelineBox, inspectorBox, -2), 'simulation timeline overlaps inspector panel')
      soft(!rectsOverlap(mapDuringTimelineBox, timelineBox, -2), 'canvas map overlaps simulation timeline')
      const scroll = await page.locator('[data-testid="simulation-timeline-scroll"]').evaluate(el => ({
        scrollWidth: el.scrollWidth,
        clientWidth: el.clientWidth
      }))
      const stateCount = await page.locator('[data-testid^="simulation-timeline-state-"]').count()
      return { stateCount, scroll }
    } catch (error) {
      await page.screenshot({ path: path.join(outDir, `simulation-timeline-${label}-missing.png`), fullPage: true })
      failures.push(`simulation timeline did not appear for ${label}: ${error.message}`)
      await closeIfVisible('[data-testid="trace-history-panel"]', '[data-testid="close-history-panel"]')
      return null
    }
  }

  if (seed.savedSimulationTrace && seed.simulationSummaries.length > 0) {
    const standardTimeline = await replaySimulationTrace(seed.savedSimulationTrace, 'standard-light')
    if (standardTimeline) {
      soft(standardTimeline.stateCount >= 4, `standard timeline has too few states: ${standardTimeline.stateCount}`)
      await page.locator('[data-testid="simulation-timeline-play"]').click()
      await page.waitForFunction(() =>
        document.querySelector('[data-testid="simulation-timeline"]')?.getAttribute('data-selected-state-index') === '1',
        null,
        { timeout: 2500 }
      )
      if (standardTimeline.stateCount > 3) {
        await page.locator('[data-testid="simulation-timeline-state-3"]').click()
        await page.waitForFunction(() =>
          document.querySelector('[data-testid="simulation-timeline"]')?.getAttribute('data-selected-state-index') === '3',
          null,
          { timeout: 1000 }
        )
      }
      await closeIfVisible('[data-testid="simulation-timeline"]', '[data-testid="simulation-timeline-close"]')
    }

    const shortTimeline = await replaySimulationTrace(seed.shortSimulationTrace, 'short-light')
    if (shortTimeline) {
      soft(shortTimeline.stateCount <= 3, `short timeline should remain compact, got ${shortTimeline.stateCount} states`)
      soft(shortTimeline.scroll.scrollWidth <= shortTimeline.scroll.clientWidth + 2, 'short timeline unexpectedly scrolls horizontally')
      await closeIfVisible('[data-testid="simulation-timeline"]', '[data-testid="simulation-timeline-close"]')
    }

    const longTimeline = await replaySimulationTrace(seed.longSimulationTrace, 'long-light')
    if (longTimeline) {
      soft(longTimeline.stateCount > 15, `long timeline should have >15 states, got ${longTimeline.stateCount}`)
      soft(longTimeline.scroll.scrollWidth > longTimeline.scroll.clientWidth + 2, 'long timeline does not expose horizontal scrolling')
      await closeIfVisible('[data-testid="simulation-timeline"]', '[data-testid="simulation-timeline-close"]')
    }
  }

  await setCanvasZoomPercent(100)
  await page.evaluate(() => {
    localStorage.setItem('iot_verify_theme', 'dark')
    document.documentElement.dataset.theme = 'dark'
    document.documentElement.classList.add('dark')
  })
  await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark'
    && document.documentElement.classList.contains('dark'), null, { timeout: 3000 })
  await waitForVisualStability(['[data-testid="control-center"]', '[data-testid="system-inspector"]'])
  const contextMenuNode = page.locator('.device-node[data-node-id="node_door_entry"]')
  await contextMenuNode.focus()
  await page.keyboard.press('Shift+F10')
  const contextMenu = page.getByRole('menu')
  await contextMenu.waitFor({ state: 'visible', timeout: 3000 })
  const contextMenuItems = contextMenu.getByRole('menuitem')
  soft(await contextMenuItems.first().evaluate(element => document.activeElement === element),
    'keyboard-opened device context menu did not focus its first action')
  await page.keyboard.press('ArrowDown')
  soft(await contextMenuItems.nth(1).evaluate(element => document.activeElement === element),
    'ArrowDown did not move device context-menu focus to the next action')
  await page.keyboard.press('Escape')
  await contextMenu.waitFor({ state: 'hidden', timeout: 3000 })
  soft(await contextMenuNode.evaluate(element => document.activeElement === element),
    'Escape did not restore focus from the device context menu to its node')

  await page.keyboard.press('Shift+F10')
  await contextMenu.waitFor({ state: 'visible', timeout: 3000 })
  await page.keyboard.press('Enter')
  const renameDialog = page.locator('[aria-labelledby="rename-device-dialog-title"]')
  await renameDialog.waitFor({ state: 'visible', timeout: 3000 })
  await page.keyboard.press('Escape')
  await renameDialog.waitFor({ state: 'hidden', timeout: 3000 })
  soft(await contextMenuNode.evaluate(element => document.activeElement === element),
    'closing Rename from the device context menu did not restore node focus')

  await page.route(url => url.pathname === '/api/board/nodes/deletion-preview'
    && url.searchParams.get('nodeId') === 'node_door_entry', route => route.fulfill({
    status: 503,
    contentType: 'application/json',
    body: JSON.stringify({ code: 503, message: 'Injected preview failure', data: null })
  }), { times: 1 })
  await page.keyboard.press('Shift+F10')
  await contextMenu.waitFor({ state: 'visible', timeout: 3000 })
  await page.keyboard.press('End')
  await page.keyboard.press('Enter')
  await contextMenu.waitFor({ state: 'hidden', timeout: 3000 })
  await page.waitForFunction(() => document.activeElement?.getAttribute('data-node-id') === 'node_door_entry',
    null, { timeout: 3000 }).catch(() => {})
  soft(await contextMenuNode.evaluate(element => document.activeElement === element),
    'a failed Delete preview did not return focus to its device node')

  await page.keyboard.press('Shift+F10')
  await contextMenu.waitFor({ state: 'visible', timeout: 3000 })
  await page.keyboard.press('End')
  await page.keyboard.press('Enter')
  const deleteDialog = page.locator('[aria-labelledby="delete-device-dialog-title"]')
  await deleteDialog.waitFor({ state: 'visible', timeout: 5000 })
  await page.keyboard.press('Escape')
  await deleteDialog.waitFor({ state: 'hidden', timeout: 3000 })
  soft(await contextMenuNode.evaluate(element => document.activeElement === element),
    'closing Delete from the device context menu did not restore node focus')

  await contextMenuNode.evaluate(element => {
    element.dispatchEvent(new MouseEvent('contextmenu', {
      bubbles: true,
      cancelable: true,
      button: 2,
      clientX: window.innerWidth - 2,
      clientY: window.innerHeight - 2
    }))
  })
  await contextMenu.waitFor({ state: 'visible', timeout: 3000 })
  const edgeMenuBounds = await contextMenu.evaluate(element => {
    const rect = element.getBoundingClientRect()
    return {
      left: rect.left,
      top: rect.top,
      right: rect.right,
      bottom: rect.bottom,
      viewportWidth: window.innerWidth,
      viewportHeight: window.innerHeight
    }
  })
  soft(edgeMenuBounds.left >= 8 && edgeMenuBounds.top >= 8
    && edgeMenuBounds.right <= edgeMenuBounds.viewportWidth - 8
    && edgeMenuBounds.bottom <= edgeMenuBounds.viewportHeight - 8,
  `device context menu escaped the viewport: ${JSON.stringify(edgeMenuBounds)}`)
  await page.keyboard.press('Tab')
  await contextMenu.waitFor({ state: 'hidden', timeout: 3000 })
  soft(await contextMenuNode.evaluate(element => document.activeElement === element),
    'Tab did not close the device context menu and restore focus to its node')

  await page.screenshot({ path: path.join(outDir, 'board-dark.png'), fullPage: true })
  const darkNodeContrast = await page.evaluate(() => {
    const parseColor = value => {
      if (!value || value === 'transparent') return [0, 0, 0, 0]
      const rgb = value.match(/^rgba?\(\s*([\d.]+)(?:\s+|\s*,\s*)([\d.]+)(?:\s+|\s*,\s*)([\d.]+)(?:\s*(?:\/|,)\s*([\d.]+)%?)?\s*\)$/i)
      if (rgb) {
        const alpha = rgb[4] === undefined ? 1 : Number(rgb[4]) / (value.includes('%') ? 100 : 1)
        return [Number(rgb[1]), Number(rgb[2]), Number(rgb[3]), alpha]
      }
      const srgb = value.match(/^color\(srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)(?:\s*\/\s*([\d.]+))?\)$/i)
      if (srgb) {
        return [Number(srgb[1]) * 255, Number(srgb[2]) * 255, Number(srgb[3]) * 255, srgb[4] === undefined ? 1 : Number(srgb[4])]
      }
      return null
    }
    const composite = (foreground, background) => {
      const alpha = foreground[3] + background[3] * (1 - foreground[3])
      if (alpha <= 0) return [0, 0, 0, 0]
      return [
        (foreground[0] * foreground[3] + background[0] * background[3] * (1 - foreground[3])) / alpha,
        (foreground[1] * foreground[3] + background[1] * background[3] * (1 - foreground[3])) / alpha,
        (foreground[2] * foreground[3] + background[2] * background[3] * (1 - foreground[3])) / alpha,
        alpha
      ]
    }
    const backgroundFor = element => {
      const ancestors = []
      for (let current = element; current; current = current.parentElement) ancestors.push(current)
      let result = [255, 255, 255, 1]
      for (const current of ancestors.reverse()) {
        const color = parseColor(getComputedStyle(current).backgroundColor)
        if (color) result = composite(color, result)
      }
      return result
    }
    const luminance = color => {
      const channels = color.slice(0, 3).map(channel => {
        const normalized = channel / 255
        return normalized <= 0.04045
          ? normalized / 12.92
          : ((normalized + 0.055) / 1.055) ** 2.4
      })
      return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
    }
    const contrast = (foreground, background) => {
      const renderedForeground = composite(foreground, background)
      const foregroundLuminance = luminance(renderedForeground)
      const backgroundLuminance = luminance(background)
      return (Math.max(foregroundLuminance, backgroundLuminance) + 0.05)
        / (Math.min(foregroundLuminance, backgroundLuminance) + 0.05)
    }
    const read = (selector, kind) => [...document.querySelectorAll(selector)]
      .filter(element => {
        const style = getComputedStyle(element)
        const rect = element.getBoundingClientRect()
        return rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden'
      })
      .map(element => {
        const foreground = parseColor(getComputedStyle(element).color)
        const background = backgroundFor(element)
        return {
          kind,
          nodeId: element.closest('.device-node')?.getAttribute('data-node-id'),
          text: (element.textContent || '').trim(),
          foreground,
          background,
          ratio: foreground ? contrast(foreground, background) : 0
        }
      })
    return {
      expandedNodes: document.querySelectorAll('.device-node--expanded').length,
      privacyBadges: document.querySelectorAll('.device-node--expanded .device-node-trust--privacy').length,
      results: [
        ...read('.device-node--expanded .device-label', 'label'),
        ...read('.device-node--expanded .device-state-value', 'state'),
        ...read('.device-node--expanded .device-runtime-chip__label', 'runtime-label'),
        ...read('.device-node--expanded .device-runtime-chip__value', 'runtime-value'),
        ...read('.device-node--expanded .device-node-trust', 'security-badge')
      ]
    }
  })
  soft(darkNodeContrast.expandedNodes > 0, 'dark 100% view rendered no expanded device nodes')
  soft(darkNodeContrast.privacyBadges > 0,
    `dark contrast fixture rendered no private-data badges: ${JSON.stringify(darkNodeContrast)}`)
  soft(darkNodeContrast.results.length >= darkNodeContrast.expandedNodes * 2,
    `dark expanded nodes are missing visible label/state text: ${JSON.stringify(darkNodeContrast)}`)
  for (const result of darkNodeContrast.results) {
    soft(result.ratio >= 4.5,
      `dark ${result.kind} contrast below WCAG AA on ${result.nodeId}: ${result.ratio.toFixed(2)} (${result.text})`)
  }
  const darkColors = await page.evaluate(() => {
    const read = (selector, prop = 'backgroundColor') => {
      const el = document.querySelector(selector)
      return el ? getComputedStyle(el)[prop] : null
    }
    return {
      controlBg: read('[data-testid="control-center"]'),
      mapBg: read('[data-testid="canvas-map"]'),
      cardBg: read('.board-card-surface')
    }
  })
  soft(darkColors.controlBg && !/rgb\(255, 255, 255\)/.test(darkColors.controlBg), `dark control panel is white: ${darkColors.controlBg}`)
  soft(darkColors.mapBg && !/rgb\(255, 255, 255\)/.test(darkColors.mapBg), `dark canvas map is white: ${darkColors.mapBg}`)
  await page.locator('[data-testid="open-rule-recommendations"]').click()
  await page.waitForSelector('[data-testid="rule-recommendation-panel"]', { timeout: 5000 })
  await page.screenshot({ path: path.join(outDir, 'rule-recommendations-dark.png'), fullPage: true })
  darkColors.rulePanel = await page.locator('[data-testid="rule-recommendation-panel"]').evaluate(panel => {
    const content = panel.querySelector(':scope > .p-3')
    const title = panel.querySelector('h3')
    const subtitle = panel.querySelector('p')
    return {
      contentBg: content ? getComputedStyle(content).backgroundColor : null,
      titleColor: title ? getComputedStyle(title).color : null,
      subtitleColor: subtitle ? getComputedStyle(subtitle).color : null,
      mapVisible: Boolean(document.querySelector('[data-testid="canvas-map"]')?.getClientRects().length)
    }
  })
  soft(darkColors.rulePanel.contentBg && !/rgb\(255, 255, 255\)/.test(darkColors.rulePanel.contentBg), `dark rule panel content is white: ${darkColors.rulePanel.contentBg}`)
  soft(darkColors.rulePanel.titleColor === 'rgb(255, 255, 255)', `dark rule panel title is not white: ${darkColors.rulePanel.titleColor}`)
  soft(darkColors.rulePanel.mapVisible === false, 'canvas map remains visible while a floating panel is open')

  darkColors.resultDialogs = { simulation: [], verification: [] }
  await closeIfVisible('[data-testid="rule-recommendation-panel"]', '[data-testid="close-rule-recommendations"]')
  const darkRunTimeline = await replaySimulationTrace(seed.savedSimulationTrace, 'run-details-dark')
  if (darkRunTimeline) {
    await page.locator('[data-testid="simulation-timeline-run-details"]').click()
    await page.waitForSelector('[data-testid="simulation-result-dialog"]', { timeout: 5000 })
    for (const viewport of [
      { width: 1440, height: 920 },
      { width: 844, height: 390 },
      { width: 320, height: 568 }
    ]) {
      darkColors.resultDialogs.simulation.push(await auditRootResultDialog(
        '[data-testid="simulation-result-dialog"]',
        '[data-testid="close-simulation-result"]',
        `simulation-result-dark-${viewport.width}x${viewport.height}`,
        viewport
      ))
    }
    await page.locator('[data-testid="close-simulation-result"]').click()
    await page.locator('[data-testid="simulation-result-dialog"]').waitFor({ state: 'hidden', timeout: 3000 })
  }

  await page.setViewportSize({ width: 1440, height: 920 })
  await waitForVisualStability(['.board-nav-bar', '[data-testid="control-center"]', '[data-testid="system-inspector"]'])
  const verificationRun = seed.verificationRuns?.[0]
  soft(Boolean(verificationRun), 'real verification produced no retained run for result-dialog review')
  if (verificationRun) {
    await closeFloatingPanels()
    await page.locator('[data-testid="open-history-panel"]').click()
    await page.waitForSelector('[data-testid="trace-history-panel"]')
    await page.locator('[data-testid="history-layer-results"]').click()
    await page.locator('[data-testid="history-result-filter-verification"]').click()
    const openRunSelector = `[data-testid="open-verification-run-${verificationRun.id}"]`
    await page.waitForSelector(openRunSelector, { timeout: 10000 })
    await page.locator(openRunSelector).click()
    await page.waitForSelector('[data-testid="verification-result-dialog"]', { timeout: 10000 })
    for (const viewport of [
      { width: 1440, height: 920 },
      { width: 844, height: 390 },
      { width: 320, height: 568 }
    ]) {
      darkColors.resultDialogs.verification.push(await auditRootResultDialog(
        '[data-testid="verification-result-dialog"]',
        '[data-testid="close-verification-result"]',
        `verification-result-dark-${viewport.width}x${viewport.height}`,
        viewport
      ))
    }
    await page.locator('[data-testid="close-verification-result"]').click()
    await page.locator('[data-testid="verification-result-dialog"]').waitFor({ state: 'hidden', timeout: 3000 })
  }

  await page.setViewportSize({ width: 1440, height: 920 })
  await waitForVisualStability(['.board-nav-bar', '[data-testid="control-center"]', '[data-testid="system-inspector"]'])
  await closeFloatingPanels()
  await page.evaluate(() => {
    localStorage.setItem('iot_verify_theme', 'light')
    document.documentElement.dataset.theme = 'light'
    document.documentElement.classList.remove('dark')
  })
  await page.goto(appUrl, { waitUntil: 'networkidle' })
  await page.waitForSelector('[data-testid="board-root"]')
  await page.waitForFunction(() => document.querySelectorAll('.device-node').length >= 6, null, { timeout: 20000 })
  await page.locator('[aria-label="Switch language"]').first().click()
  await page.waitForFunction(() => localStorage.getItem('locale') === 'zh-CN', null, { timeout: 3000 })
  await page.screenshot({ path: path.join(outDir, 'board-zh-light.png'), fullPage: true })
  const zhControlText = await page.locator('[data-testid="control-center"]').innerText()
  soft(zhControlText.includes('控制中心'), 'Chinese locale did not render control center copy')
  await page.locator('[data-testid="inspector-tab-devices"]').click()
  await page.waitForSelector('[data-testid="inspector-section-devices"]', { timeout: 3000 })
  const zhBuiltInTokenAudit = await page.evaluate(() => {
    const inspectorText = document.querySelector('[data-testid="inspector-section-devices"]')?.innerText || ''
    const boardText = document.querySelector('[data-testid="board-root"]')?.innerText || ''
    const nodeStates = [...document.querySelectorAll('.device-node')].map(node => ({
      id: node.getAttribute('data-node-id'),
      state: (node.querySelector('.device-state-value')?.textContent || '').trim()
    }))
    return {
      inspectorText,
      rawInspectorTokens: inspectorText.match(/\b(?:workingState|Working|off|locked|closed|auto)\b/g) || [],
      rawBoardTokens: boardText.match(/\b(?:workingState|Working|off|locked|closed|auto)\b/g) || [],
      nodeStates
    }
  })
  soft(zhBuiltInTokenAudit.inspectorText.includes('已锁定'),
    `Chinese inspector did not localize seeded locked state: ${JSON.stringify(zhBuiltInTokenAudit)}`)
  soft(zhBuiltInTokenAudit.inspectorText.includes('关闭'),
    `Chinese inspector did not localize seeded off state: ${JSON.stringify(zhBuiltInTokenAudit)}`)
  soft(zhBuiltInTokenAudit.rawInspectorTokens.length === 0,
    `Chinese inspector leaked built-in model tokens: ${JSON.stringify(zhBuiltInTokenAudit.rawInspectorTokens)}`)
  soft(zhBuiltInTokenAudit.rawBoardTokens.length === 0,
    `Chinese board leaked seeded built-in model tokens: ${JSON.stringify(zhBuiltInTokenAudit.rawBoardTokens)}`)
  soft(!zhBuiltInTokenAudit.nodeStates.some(node => node.state === 'Working'),
    `Chinese stateless nodes still display the synthetic Working placeholder: ${JSON.stringify(zhBuiltInTokenAudit.nodeStates)}`)
  soft(zhBuiltInTokenAudit.nodeStates.some(node =>
    node.id === 'node_temperature_patio' && node.state === '无状态机'),
  `Chinese stateless node did not display the localized no-state-machine label: ${JSON.stringify(zhBuiltInTokenAudit.nodeStates)}`)
  await page.locator('[data-testid="open-rule-recommendations"]').click()
  await page.waitForSelector('[data-testid="rule-recommendation-panel"]', { timeout: 5000 })
  const zhRulePanelText = await page.locator('[data-testid="rule-recommendation-panel"]').innerText()
  soft(zhRulePanelText.includes('数量'), 'Chinese recommendation panel missing localized control labels')
  soft(!/\bCategory\b|\bCount\b|\bRefresh\b/.test(zhRulePanelText), 'Chinese recommendation panel still contains English control labels')
  await page.screenshot({ path: path.join(outDir, 'rule-recommendations-zh-light.png'), fullPage: true })
  await closeIfVisible('[data-testid="rule-recommendation-panel"]', '[data-testid="close-rule-recommendations"]')

  await page.setViewportSize({ width: 390, height: 844 })
  await page.evaluate(() => {
    localStorage.setItem('iot_verify_theme', 'dark')
    document.documentElement.dataset.theme = 'dark'
    document.documentElement.classList.add('dark')
  })
  await page.goto(appUrl, { waitUntil: 'networkidle' })
  await page.waitForSelector('[data-testid="board-root"]')
  await page.waitForFunction(() => document.querySelectorAll('.device-node').length >= 6, null, { timeout: 20000 })
  await page.waitForFunction(() => {
    const control = document.querySelector('[data-testid="control-center"]')
    const inspector = document.querySelector('[data-testid="system-inspector"]')
    if (!control || !inspector) return false
    return control.getBoundingClientRect().width <= 88
      && inspector.getBoundingClientRect().width <= 88
  }, null, { timeout: 3000 }).catch(() => {})
  await page.screenshot({ path: path.join(outDir, 'board-mobile-dark.png'), fullPage: true })
  const mobile = await page.evaluate(() => {
    const rect = selector => {
      const el = document.querySelector(selector)
      if (!el) return null
      const r = el.getBoundingClientRect()
      return { x: r.x, y: r.y, width: r.width, height: r.height, right: r.right, bottom: r.bottom }
    }
    return {
      vp: { w: window.innerWidth, h: window.innerHeight },
      control: rect('[data-testid="control-center"]'),
      inspector: rect('[data-testid="system-inspector"]'),
      map: rect('[data-testid="canvas-map"]'),
      nodes: document.querySelectorAll('.device-node').length
    }
  })
  soft(mobile.control && mobile.control.width <= 88, `mobile control panel should start collapsed, got ${mobile.control?.width}`)
  soft(mobile.inspector && mobile.inspector.width <= 88, `mobile inspector panel should start collapsed, got ${mobile.inspector?.width}`)
  soft(!mobile.map || mobile.map.right <= mobile.vp.w + 1, 'mobile canvas map overflows right edge')
  soft(mobile.nodes >= 6, 'mobile did not render seeded nodes')

  await auditNarrowFrame('390x844 collapsed layout')
  await page.locator('[data-testid="control-center"] button').first().click()
  await page.waitForFunction(() =>
    document.querySelector('[data-testid="control-center"]')?.getBoundingClientRect().width > 200,
  null, { timeout: 3000 })
  await waitForVisualStability(['[data-testid="control-center"]', '[data-testid="system-inspector"]', '[data-testid="board-panel-scrim"]'])
  const leftDrawer = await page.evaluate(() => ({
    controlWidth: document.querySelector('[data-testid="control-center"]')?.getBoundingClientRect().width || 0,
    inspectorWidth: document.querySelector('[data-testid="system-inspector"]')?.getBoundingClientRect().width || 0,
    scrimVisible: Boolean(document.querySelector('[data-testid="board-panel-scrim"]')?.getClientRects().length)
  }))
  soft(leftDrawer.controlWidth > 200 && leftDrawer.inspectorWidth <= 88,
    `390px left drawer did not exclusively expand: ${JSON.stringify(leftDrawer)}`)
  soft(leftDrawer.scrimVisible, '390px left drawer did not expose a dismissing scrim')
  await auditNarrowFrame('390x844 left drawer', { allowHiddenInspector: true })
  await page.screenshot({ path: path.join(outDir, 'board-mobile-left-drawer-dark.png'), fullPage: true })

  await dismissNarrowScrim('390x844 left drawer')
  await page.waitForFunction(() => {
    const control = document.querySelector('[data-testid="control-center"]')
    const inspector = document.querySelector('[data-testid="system-inspector"]')
    return Boolean(control && inspector
      && control.getBoundingClientRect().width <= 88
      && inspector.getBoundingClientRect().width <= 88)
  }, null, { timeout: 3000 })
  await page.locator('[data-testid="system-inspector"] button').first().click()
  await page.waitForFunction(() => {
    const control = document.querySelector('[data-testid="control-center"]')
    const inspector = document.querySelector('[data-testid="system-inspector"]')
    return Boolean(control && inspector
      && control.getBoundingClientRect().width <= 88
      && inspector.getBoundingClientRect().width > 200)
  }, null, { timeout: 3000 })
  await waitForVisualStability(['[data-testid="control-center"]', '[data-testid="system-inspector"]', '[data-testid="board-panel-scrim"]'])
  const rightDrawer = await page.evaluate(() => ({
    controlWidth: document.querySelector('[data-testid="control-center"]')?.getBoundingClientRect().width || 0,
    inspectorWidth: document.querySelector('[data-testid="system-inspector"]')?.getBoundingClientRect().width || 0,
    scrimVisible: Boolean(document.querySelector('[data-testid="board-panel-scrim"]')?.getClientRects().length)
  }))
  soft(rightDrawer.controlWidth <= 88 && rightDrawer.inspectorWidth > 200,
    `390px right drawer did not exclusively expand: ${JSON.stringify(rightDrawer)}`)
  soft(rightDrawer.scrimVisible, '390px right drawer did not retain the dismissing scrim')
  await auditNarrowFrame('390x844 right drawer', { allowHiddenControl: true })
  await page.screenshot({ path: path.join(outDir, 'board-mobile-right-drawer-dark.png'), fullPage: true })
  await dismissNarrowScrim('390x844 right drawer')
  await page.waitForFunction(() => {
    const control = document.querySelector('[data-testid="control-center"]')
    const inspector = document.querySelector('[data-testid="system-inspector"]')
    return Boolean(control && inspector
      && control.getBoundingClientRect().width <= 88
      && inspector.getBoundingClientRect().width <= 88)
  }, null, { timeout: 3000 })
  soft(!(await isVisible('[data-testid="board-panel-scrim"]')), 'mobile drawer scrim did not close both side panels')

  await page.setViewportSize({ width: 844, height: 390 })
  await waitForVisualStability(['.board-nav-bar', '[data-testid="control-center"]', '[data-testid="system-inspector"]', '.board-floating-actions'])
  await auditNarrowFrame('844x390 landscape layout')
  await auditActionDockReachability('844x390 landscape layout')
  await page.screenshot({ path: path.join(outDir, 'board-mobile-844x390-dark.png'), fullPage: true })

  await page.setViewportSize({ width: 320, height: 568 })
  await waitForVisualStability(['.board-nav-bar', '[data-testid="control-center"]', '[data-testid="system-inspector"]', '.board-floating-actions'])
  await auditNarrowFrame('320x568 collapsed layout')
  await page.locator('[data-testid="control-center"] button').first().click()
  await page.waitForFunction(() =>
    document.querySelector('[data-testid="control-center"]')?.getBoundingClientRect().width > 200,
  null, { timeout: 3000 })
  await waitForVisualStability(['[data-testid="control-center"]', '[data-testid="system-inspector"]', '[data-testid="board-panel-scrim"]'])
  await auditNarrowFrame('320x568 left drawer', { allowHiddenInspector: true })
  await page.screenshot({ path: path.join(outDir, 'board-mobile-320x568-dark.png'), fullPage: true })
  await dismissNarrowScrim('320x568 left drawer')
  await page.waitForFunction(() => {
    const control = document.querySelector('[data-testid="control-center"]')
    const inspector = document.querySelector('[data-testid="system-inspector"]')
    return Boolean(control && inspector
      && control.getBoundingClientRect().width <= 88
      && inspector.getBoundingClientRect().width <= 88)
  }, null, { timeout: 3000 })
  await waitForVisualStability(['[data-testid="control-center"]', '[data-testid="system-inspector"]'])

  await browser.close()
  return {
    failures,
    browserEvents,
    surfaceColorsLight,
    darkColors,
    darkNodeContrast,
    resizeAudit: { before: resizeBefore, after: resizeAfter },
    zhBuiltInTokenAudit
  }
}

let exitCode = 0
try {
  const seed = await seedScenario()
  const ui = await runUiChecks(seed)
  const summary = {
    layoutPanels: seed.savedLayout.panels,
    savedSimulationTraceId: seed.savedSimulationTrace?.id ?? null,
    shortSimulationTraceId: seed.shortSimulationTrace?.id ?? null,
    longSimulationTraceId: seed.longSimulationTrace?.id ?? null,
    simulationSummaries: seed.simulationSummaries.length,
    verificationOutcome: seed.savedVerificationResult?.outcome ?? null,
    verificationRuns: seed.verificationRuns.length,
    surfaceColorsLight: ui.surfaceColorsLight,
    darkColors: ui.darkColors,
    darkNodeContrast: ui.darkNodeContrast,
    resizeAudit: ui.resizeAudit,
    zhBuiltInTokenAudit: ui.zhBuiltInTokenAudit,
    browserEvents: ui.browserEvents,
    screenshots: fs.readdirSync(outDir).map(name => path.join(outDir, name)),
    failures: ui.failures
  }
  console.log(JSON.stringify(summary, null, 2))
  if (ui.failures.length) exitCode = 1
} catch (error) {
  console.error(error)
  exitCode = 1
} finally {
  if (cleanupAccount) {
    try {
      await request('DELETE', '/auth/account', {
        password: cleanupAccount.password,
        confirmation: cleanupAccount.confirmation
      }, cleanupAccount.token)
    } catch (error) {
      console.error('Failed to remove the temporary real-check account:', error)
      exitCode = 1
    }
  }
}

process.exitCode = exitCode
