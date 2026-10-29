import { type APIRequestContext, type Page } from '@playwright/test'
import path from 'node:path'
import type { VerificationResult } from '../src/types/verify'
import {
  apiBaseURL,
  createAuthenticatedUser,
  expect,
  test,
  type AuthUser
} from './support/auth'

/**
 * The SMV model download, end to end.
 *
 * This feature shipped completely unreachable: four buttons were written, and each was gated on a
 * `hasSmvModel` flag the backend never sent for that response, so none ever rendered and nothing
 * errored. The unit specs pin *where* the control may appear; only this file can prove that clicking
 * it delivers a real model. Nothing covered that before — the defect survived a green suite, a clean
 * typecheck, and a manual review.
 *
 * What each assertion is worth:
 *
 * - the button is *visible and enabled*, which is what the missing flag broke
 * - the download resolves to a file whose bytes are a NuSMV model, not an empty or HTML body — a
 *   zero-byte `.smv` would be mistaken for the checked model
 * - the model covers the scene that was submitted (its modules name the scene's devices), which is
 *   what makes it the artifact of *this* run rather than any model
 * - the artifact appears on run surfaces and not on counterexample surfaces
 */

const authHeaders = (auth: AuthUser) => ({ Authorization: `Bearer ${auth.token}` })

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
    // `AuthUser` is flat (`userId`/`phone`/`username`/`token`) — it has no `user` property. Passing
    // `auth.user` wrote the literal string "undefined", `JSON.parse` threw in the auth store, the
    // catch cleared the token, and every test landed on the login page with `board-root` never
    // mounting. The failure names `board-root`, which is not what is wrong.
  }, {
    token: auth.token,
    user: { userId: auth.userId, phone: auth.phone, username: auth.username }
  })

  await page.goto('/#/board')
  await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
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
    latest = await unwrap<T>(
      await request.get(`${apiBaseURL}${apiPath}`, { headers: authHeaders(auth) }))
    if (predicate(latest)) return latest
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error(`Timed out waiting for ${apiPath}; latest=${JSON.stringify(latest)}`)
}

const saveEmptyBoard = async (request: APIRequestContext, auth: AuthUser) => {
  const preview = await unwrap<{ impactToken: string }>(
    await request.get(`${apiBaseURL}/api/board/replacement-preview`, { headers: authHeaders(auth) }))
  const response = await request.post(`${apiBaseURL}/api/board/batch`, {
    headers: authHeaders(auth),
    data: {
      impactToken: preview.impactToken,
      nodes: [], environmentVariables: [], rules: [], specs: [], templateSnapshots: []
    }
  })
  expect(response.ok(), await response.text()).toBeTruthy()
}

const importAcceptanceScene = async (page: Page, request: APIRequestContext, auth: AuthUser) => {
  const scenePath = path.resolve(process.cwd(), '..', 'docs', 'examples', 'acceptance-demo-scene.json')
  await page.getByTestId('scene-import-file').setInputFiles(scenePath)
  await page.getByRole('dialog', { name: 'Replace the current scene?' })
    .getByRole('button', { name: 'Replace scene' })
    .click()
  await waitForApi<any[]>(request, auth, '/api/board/rules', rules => rules.length === 3)
  await waitForApi<any[]>(request, auth, '/api/board/specs', specs => specs.length === 5)
}

/**
 * Click a download control and return the delivered bytes.
 *
 * Reading the body rather than trusting the event: a failed download still fires `download`, and an
 * error page served with the right filename would satisfy a filename-only assertion.
 */
const captureDownload = async (page: Page, testId: string) => {
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 30_000 }),
    page.getByTestId(testId).click()
  ])
  expect(await download.failure(), 'the download must not fail').toBeNull()
  const stream = await download.createReadStream()
  const chunks: Buffer[] = []
  for await (const chunk of stream) chunks.push(chunk as Buffer)
  return { filename: download.suggestedFilename(), body: Buffer.concat(chunks).toString('utf8') }
}

/**
 * The bytes must be a NuSMV model of the imported scene, not merely non-empty.
 *
 * `expectsSpecifications` is the one real difference between the two run kinds, and it is a property of
 * the model rather than of the download: verification hands the generator the board's specifications
 * and emits a `CTLSPEC`/`LTLSPEC` per checked property, while a simulation executes a trajectory and is
 * given none — `SimulationServiceImpl` passes no specifications at all, so a spec block in a simulation
 * model would mean something had leaked in. Asserting the keyword unconditionally failed the simulation
 * test against correct output, which is the assertion being wrong rather than the product.
 */
const expectSceneModel = (body: string, { expectsSpecifications }: { expectsSpecifications: boolean }) => {
  expect(body.length, 'a zero-byte .smv would be mistaken for the checked model')
    .toBeGreaterThan(500)
  expect(body, 'must not be an HTML error page').not.toMatch(/^\s*<(!doctype|html)/i)
  const modules = [...body.matchAll(/^MODULE\s+(\S+)/gm)].map(match => match[1])
  expect(modules.length, 'a NuSMV model declares modules').toBeGreaterThan(1)
  expect(modules, 'including the top-level main module').toContain('main')
  // The scene's own devices, so this is the model of *this* run rather than any model.
  expect(body, 'the model covers the imported scene').toMatch(/Camera|camera/)
  if (expectsSpecifications) {
    expect(body, 'a verification model carries the specifications it checked')
      .toMatch(/CTLSPEC|LTLSPEC/)
  } else {
    expect(body, 'a simulation model is given no specifications to check')
      .not.toMatch(/CTLSPEC|LTLSPEC/)
  }
}

test.describe('SMV model download', () => {
  test('delivers the checked model from the verification result, as a primary action', { tag: '@smoke' }, async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)
    await importAcceptanceScene(page, request, auth)

    await page.getByTestId('open-verification-panel').click()
    await page.getByTestId('verification-mode-sync').click()
    const checkedResponse = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/verify', { timeout: 60_000 })
    await page.getByTestId('run-verification').click()
    // As a smoke journey this must prove checking succeeded, beyond producing a downloadable model.
    const checked = await unwrap<VerificationResult>(await checkedResponse)
    expect(checked.modelComplete).toBe(true)
    expect(checked.specResults).toHaveLength(5)
    expect(checked.specResults.every(result => ['SATISFIED', 'VIOLATED'].includes(result.outcome))).toBe(true)
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })

    // The artifact section is in the body, above the footer, and names what it covers.
    const artifact = page.getByTestId('verification-run-artifact')
    await expect(artifact).toBeVisible({ timeout: 30_000 })
    await expect(artifact.getByRole('heading', { name: 'Verification model', exact: true })).toBeVisible()
    await expect(artifact).toContainText('The SMV model this run checked')

    // Enabled, not merely present: the flag that was never sent is exactly what left it unusable.
    const download = page.getByTestId('verification-result-download-smv')
    await expect(download).toBeVisible()
    await expect(download).toBeEnabled()
    await expect(page.getByTestId('verification-result-smv-unavailable')).toHaveCount(0)

    const { filename, body } = await captureDownload(page, 'verification-result-download-smv')
    expect(filename).toMatch(/^verification-run-\d+\.smv$/)
    expectSceneModel(body, { expectsSpecifications: true })
  })

  test('keeps counterexample context in the terminal with a link to the owning run', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)
    await importAcceptanceScene(page, request, auth)

    await page.getByTestId('open-verification-panel').click()
    await page.getByTestId('verification-mode-sync').click()
    await page.getByTestId('run-verification').click()
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })

    // Run context stays inside the terminal while the timeline remains visible.
    await page.getByTestId('verification-trace-replay').first().click()
    await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 30_000 })
    await page.getByTestId('trace-timeline-run-tab').click()
    const details = page.getByTestId('counterexample-run-info')
    await expect(page.getByTestId('trace-details-dialog')).toHaveCount(0)
    await expect(details).toBeVisible({ timeout: 30_000 })

    // The model is one per run; a per-counterexample copy would imply one model each.
    await expect(details.getByTestId('download-counterexample-smv')).toHaveCount(0)

    // The two kinds of fact are named and ordered: the counterexample, then the run behind it.
    await expect(details.getByTestId('counterexample-evidence')).toBeVisible()
    await expect(details.getByTestId('counterexample-run-context')).toBeVisible()
    await expect(details.getByTestId('counterexample-run-context'))
      .toContainText('Attack behavior not modeled')

    // Escalation reaches the run, where the artifact lives, and the URL follows.
    await details.getByTestId('counterexample-open-owning-run').click()
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 30_000 })
    await expect(page).toHaveURL(/[?&]run=verification(%3A|:)\d+/)
    await expect(page.getByTestId('verification-result-download-smv')).toBeEnabled({ timeout: 30_000 })
  })

  test('delivers the executed model from the simulation result', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)
    await importAcceptanceScene(page, request, auth)

    await page.getByTestId('open-simulation-panel').click()
    await page.getByTestId('simulation-mode-sync').click()
    await page.getByTestId('run-simulation').click()

    await expect(page.getByTestId('simulation-timeline')).toBeVisible({ timeout: 60_000 })
    await page.getByTestId('simulation-timeline-run-tab').click()
    await expect(page.getByTestId('simulation-result-dialog')).toHaveCount(0)
    await expect(page.getByTestId('playback-toolbar').getByTestId('simulation-timeline-track')).toBeVisible()

    const artifact = page.getByTestId('simulation-run-artifact')
    await expect(artifact).toBeVisible({ timeout: 30_000 })
    const download = page.getByTestId('simulation-result-download-smv')
    await expect(download).toBeEnabled()

    const { filename, body } = await captureDownload(page, 'simulation-result-download-smv')
    expect(filename).toMatch(/^simulation-trace-\d+\.smv$/)
    expectSceneModel(body, { expectsSpecifications: false })
  })

  test('offers one download per run row in history, and none per counterexample', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)
    await importAcceptanceScene(page, request, auth)

    await page.getByTestId('open-verification-panel').click()
    await page.getByTestId('verification-mode-sync').click()
    await page.getByTestId('run-verification').click()
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 60_000 })
    await page.getByTestId('close-verification-result').click()

    await page.getByTestId('open-history-panel').click()
    const runDownload = page.locator('[data-testid^="download-verification-run-smv-"]').first()
    await expect(runDownload).toBeVisible({ timeout: 30_000 })

    // The run row carries the single copy; the per-counterexample rows carry none.
    await expect(page.locator('[data-testid^="download-verification-trace-smv-"]')).toHaveCount(0)

    const [download] = await Promise.all([
      page.waitForEvent('download', { timeout: 30_000 }),
      runDownload.click()
    ])
    expect(await download.failure()).toBeNull()
    expect(download.suggestedFilename()).toMatch(/^verification-run-\d+\.smv$/)
  })
})
