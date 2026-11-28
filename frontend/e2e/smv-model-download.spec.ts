import { type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { unwrap, waitForApi } from './support/apiResponse'
import { saveEmptyBoard } from './support/boardWorkspace'
import path from 'node:path'
import type { VerificationResult } from '../src/types/verify'
import {
  apiBaseURL,
  authHeaders,
  createAuthenticatedUser,
  seedBrowserSession,
  expect,
  test,
  type AuthUser
} from './support/auth'

/**
 * The SMV model download, end to end.
 *
 * This feature shipped completely unreachable: four buttons were written, and each was gated on a
 * `hasSmvModel` flag the backend never sent for that response. This suite exercises the rendered
 * controls, their placement and the file the browser receives.
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

const downloadModel = { name: 'Download SMV Model', exact: true }

const openWorkspace = async (page: Page, auth: AuthUser) => {
  await seedBrowserSession(page, auth, { locale: 'en', theme: 'light' })

  await page.goto('/#/board')
  await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
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
const captureDownload = async (page: Page, button: Locator) => {
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 30_000 }),
    button.click()
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
    const privacy = page.getByTestId('verification-privacy-toggle')
    await expect(privacy).toBeDisabled()
    await expect(privacy).toHaveAttribute('aria-checked', 'true')
    await expect(privacy).toHaveAttribute('aria-describedby', 'verification-privacy-required')
    await expect(page.getByTestId('verification-privacy-required')).toBeVisible()
    await expect(page.getByTestId('verification-mode-async')).toHaveAttribute('aria-pressed', 'true')
    await page.getByTestId('verification-mode-sync').click()
    await expect(page.getByTestId('verification-mode-sync')).toHaveAttribute('aria-pressed', 'true')
    const checkedResponse = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/verify', { timeout: 60_000 })
    await page.getByTestId('run-verification').click()
    // As a smoke journey this must prove checking succeeded, beyond producing a downloadable model.
    const response = await checkedResponse
    expect(response.request().postDataJSON()).toMatchObject({ enablePrivacy: true })
    const checked = await unwrap<VerificationResult>(response)
    expect(checked.modelComplete).toBe(true)
    expect(checked.specResults).toHaveLength(5)
    expect(checked.specResults.every(result => ['SATISFIED', 'VIOLATED'].includes(result.outcome))).toBe(true)
    const result = page.getByTestId('verification-result-dialog')
    await expect(result).toBeVisible({ timeout: 60_000 })

    // The artifact section is in the body, above the footer, and names what it covers.
    const artifact = page.getByTestId('verification-run-artifact')
    await expect(artifact).toBeVisible({ timeout: 30_000 })
    await expect(artifact.getByRole('heading', { name: 'Verification model', exact: true })).toBeVisible()
    await expect(artifact).toContainText('The SMV model this run checked')

    // Enabled, not merely present: the flag that was never sent is exactly what left it unusable.
    const download = result.getByRole('button', downloadModel)
    await expect(download).toHaveCount(1)
    await expect(download).toBeVisible()
    await expect(download).toBeEnabled()
    await expect(page.getByTestId('verification-result-smv-unavailable')).toHaveCount(0)

    const { filename, body } = await captureDownload(page, download)
    expect(filename).toMatch(/^verification-run-\d+\.smv$/)
    expectSceneModel(body, { expectsSpecifications: true })

    expect(checked.nusmvOutput.trim()).not.toBe('')
    await result.getByTestId('run-context-section').locator('summary').first().click()
    const diagnostics = result.getByTestId('verification-nusmv-output')
    await expect(diagnostics, 'the result exposes the solver diagnostics').toBeVisible()
    await diagnostics.locator('summary').click()
    const output = diagnostics.locator('pre')
    await expect(output).toBeVisible()
    await expect(output).toHaveText(checked.nusmvOutput)

    // The solver can complete even if another job uses the remaining history slot.
    await page.getByTestId('close-verification-result').click()
    await page.route('**/api/verify', async route => {
      const response = await route.fetch()
      const envelope = await response.json() as { data: VerificationResult }
      const { data } = envelope
      expect(data.historyPersistence.status).toBe('SAVED')
      expect(data.hasSmvModel).toBe(true)
      await route.fulfill({ response, json: {
        ...envelope,
        data: {
          ...data,
          historyPersistence: { status: 'FAILED', reasonCode: 'VERIFICATION_STORED_TASK_LIMIT_REACHED' },
          traces: data.traces.map(({ id, verificationTaskId, ...trace }) => trace)
        }
      } })
    }, { times: 1 })
    await page.getByTestId('open-verification-panel').click()
    await page.getByTestId('verification-mode-sync').click()
    await page.getByTestId('run-verification').click()
    await expect(download).toBeVisible()
    await expect(download).toBeDisabled()
    await expect(result.getByTestId('verification-result-smv-unavailable'))
      .toContainText('This run was not saved to history')
  })

  test('keeps counterexample context in the terminal with a link to the owning run', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)
    await importAcceptanceScene(page, request, auth)

    await page.getByTestId('open-verification-panel').click()
    await page.getByTestId('verification-mode-sync').click()
    await page.getByTestId('run-verification').click()
    const result = page.getByTestId('verification-result-dialog')
    await expect(result).toBeVisible({ timeout: 60_000 })

    // Run context stays inside the terminal while the timeline remains visible.
    await expect(result.getByRole('button', { name: 'Fix rules', exact: true }).first()).toBeVisible()
    await result.getByRole('button', { name: 'Replay', exact: true }).first().click()
    await expect(page.getByTestId('trace-timeline')).toBeVisible({ timeout: 30_000 })
    await page.getByTestId('trace-timeline-run-tab').click()
    const details = page.getByTestId('counterexample-run-info')
    await expect(page.getByTestId('trace-details-dialog')).toHaveCount(0)
    await expect(details).toBeVisible({ timeout: 30_000 })

    // The model is one per run; a per-counterexample copy would imply one model each.
    await expect(details.getByRole('button', downloadModel), 'the counterexample has no model download').toHaveCount(0)

    // The two kinds of fact are named and ordered: the counterexample, then the run behind it.
    await expect(details.getByTestId('counterexample-evidence')).toBeVisible()
    await expect(details.getByTestId('counterexample-run-context')).toBeVisible()
    await expect(details.getByTestId('counterexample-run-context'))
      .toContainText('Attack behavior not modeled')

    // Escalation reaches the run, where the artifact lives, and the URL follows.
    await details.getByTestId('counterexample-open-owning-run').click()
    await expect(page.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 30_000 })
    await expect(page).toHaveURL(/[?&]run=verification(%3A|:)\d+/)
    await expect(result.getByRole('button', downloadModel)).toBeEnabled({ timeout: 30_000 })
  })

  test('delivers the executed model from the simulation result', async ({ page, request }) => {
    const auth = await createAuthenticatedUser(request)
    await saveEmptyBoard(request, auth)
    await openWorkspace(page, auth)
    await importAcceptanceScene(page, request, auth)

    await page.getByTestId('open-simulation-panel').click()
    const privacy = page.getByTestId('simulation-privacy-toggle')
    await expect(privacy).toBeEnabled()
    await expect(privacy).toHaveAttribute('aria-checked', 'false')
    await privacy.click()
    await expect(privacy).toHaveAttribute('aria-checked', 'true')
    await expect(page.getByTestId('simulation-save-history')).toHaveCount(0)
    await page.getByTestId('simulation-mode-sync').click()
    await expect(page.getByTestId('simulation-mode-sync')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('simulation-save-history')).toHaveAttribute('aria-checked', 'true')
    const executed = page.waitForRequest(request => request.method() === 'POST'
      && new URL(request.url()).pathname === '/api/simulate/traces')
    await page.getByTestId('run-simulation').click()
    expect((await executed).postDataJSON()).toMatchObject({ enablePrivacy: true })

    await expect(page.getByTestId('simulation-timeline')).toBeVisible({ timeout: 60_000 })
    await page.getByTestId('simulation-timeline-run-tab').click()
    await expect(page.getByTestId('simulation-result-dialog')).toHaveCount(0)
    await expect(page.getByTestId('playback-toolbar').getByTestId('simulation-timeline-track')).toBeVisible()

    const artifact = page.getByTestId('simulation-run-artifact')
    await expect(artifact).toBeVisible({ timeout: 30_000 })
    const download = artifact.getByRole('button', downloadModel)
    await expect(download).toBeEnabled()

    const { filename, body } = await captureDownload(page, download)
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
    const checked = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/verify')
    await page.getByTestId('run-verification').click()
    const { historyPersistence } = await unwrap<VerificationResult>(await checked)
    if (historyPersistence.status !== 'SAVED') throw new Error('Expected a saved verification run')
    const { runId } = historyPersistence
    const result = page.getByTestId('verification-result-dialog')
    await expect(result).toBeVisible({ timeout: 60_000 })
    await page.getByTestId('close-verification-result').click()

    await page.getByTestId('open-history-panel').click()
    const history = page.getByTestId('trace-history-panel')
    const evidence = history.getByTestId(`run-evidence-verification-${runId}`)
    await evidence.locator('summary').click()
    await expect(evidence.getByRole('button', { name: 'Replay', exact: true }).first()).toBeVisible()
    await expect(evidence.getByRole('button', downloadModel)).toHaveCount(0)
    const runDownload = history.getByRole('button', downloadModel)
    await expect(runDownload).toHaveCount(1)
    expect((await captureDownload(page, runDownload)).filename).toBe(`verification-run-${runId}.smv`)

    // A stored verdict can outlive the model it checked.
    await page.route(`**/api/verify/runs/${runId}`, async route => {
      const response = await route.fetch()
      const envelope = await response.json()
      await route.fulfill({ response, json: { ...envelope, data: { ...envelope.data, hasSmvModel: false } } })
    })
    await history.getByTestId(`open-verification-run-${runId}`).click()
    await expect(result.getByRole('button', downloadModel)).toBeVisible()
    await expect(result.getByRole('button', downloadModel)).toBeDisabled()
    await expect(result.getByTestId('verification-result-smv-unavailable'))
      .toContainText(/no SMV model for this run:.*deleted.*without one.*history/i)
  })
})
