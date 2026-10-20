import { type Page } from '@playwright/test'
import path from 'node:path'
import { createAuthenticatedUser, expect, test, type AuthUser } from './support/auth'

test.describe.configure({ timeout: 120_000 })

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
  // 30s, matching the other fifteen board-root assertions in the suite.
  //
  // This helper carried 60s justified by "the development server's eager icon URL modules". That reason does
  // not apply: playwright.config.ts runs `npm run build && npm run preview`, so an E2E run never touches the
  // dev server. Measured against the real preview build with all eight of this spec's contexts held open,
  // mount cost is flat — 6.2–7.2s, growth 1.00x, no degradation as contexts accumulate.
  //
  // So the doubled allowance bought nothing, and an outlier timeout is worse than useless: it hides a genuine
  // slowdown in the one spec most likely to expose one. A single suite-level failure here was contention for a
  // shared machine, which 60s did not prevent and 120s would only postpone.
  await expect(page.getByTestId('board-root')).toBeVisible({ timeout: 30_000 })
  await expect(page.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })
}

const createDevice = async (page: Page, label: string) => {
  await page.getByTestId('control-tab-devices').click()
  await expect(page.getByTestId('control-section-devices')).toBeVisible()
  const templateSelect = page.getByTestId('single-device-template')
  await expect.poll(async () => templateSelect.locator('option').count()).toBeGreaterThan(1)
  const templateName = await templateSelect.locator('option').evaluateAll(options =>
    options.map(option => (option as HTMLOptionElement).value).find(Boolean) || '')
  expect(templateName).not.toBe('')
  await templateSelect.selectOption(templateName)
  await page.getByTestId('single-device-name').fill(label)
  await page.getByTestId('single-device-create').click()
  await expect(page.locator('.device-node').filter({ hasText: label })).toBeVisible({ timeout: 15_000 })
}

test('a successful Board mutation actively refreshes another visible tab', async ({ browser, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'tabsync' })
  const context = await browser.newContext()
  const writer = await context.newPage()
  await openWorkspace(writer, auth)
  const observer = await context.newPage()
  await openWorkspace(observer, auth)

  try {
    const label = `Cross tab ${Date.now()}`
    const observerRefresh = Promise.all([
      observer.waitForResponse(response =>
        response.request().method() === 'GET'
          && new URL(response.url()).pathname === '/api/board/snapshot'),
      observer.waitForResponse(response =>
        response.request().method() === 'GET'
          && new URL(response.url()).pathname === '/api/board/model-fingerprints')
    ])
    await createDevice(writer, label)
    await observerRefresh

    await expect(observer.locator('.device-node').filter({ hasText: label })).toBeVisible({ timeout: 15_000 })
  } finally {
    await context.close()
  }
})

test('a deletion preview is single-flight and closes when another tab deletes the device', async ({ browser, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'tabdelete' })
  const context = await browser.newContext()
  const writer = await context.newPage()
  await openWorkspace(writer, auth)
  const label = `Cross tab delete ${Date.now()}`
  await createDevice(writer, label)
  const observer = await context.newPage()
  await openWorkspace(observer, auth)

  let previewRequestCount = 0
  let releaseObserverPreview!: () => void
  const observerPreviewRelease = new Promise<void>(resolve => { releaseObserverPreview = resolve })
  await observer.route('**/api/board/nodes/deletion-preview?*', async route => {
    await observerPreviewRelease
    await route.continue()
  })
  observer.on('request', request => {
    if (request.method() === 'GET'
      && new URL(request.url()).pathname.endsWith('/deletion-preview')) {
      previewRequestCount += 1
    }
  })

  try {
    await observer.locator('.device-node').filter({ hasText: label }).click()
    const observerDetails = observer.getByTestId('device-dialog')
    await expect(observerDetails).toBeVisible()
    await observerDetails.getByTestId('device-delete').evaluate((button: HTMLButtonElement) => {
      button.click()
      button.click()
    })
    const observerConfirmation = observer.getByRole('dialog', { name: 'Delete device' })
    await expect(observerConfirmation).toBeVisible()
    await expect(observerConfirmation).toContainText(label)
    await expect(observerConfirmation).toContainText('Loading the current deletion impact from the server')
    await expect(observerConfirmation.getByRole('button', {
      name: 'Delete Device',
      exact: true
    })).toBeDisabled()
    await expect(observerDetails).toHaveCount(0)
    expect(previewRequestCount).toBe(1)
    releaseObserverPreview()
    await expect(observerConfirmation.getByRole('button', {
      name: 'Delete Device',
      exact: true
    })).toBeEnabled()

    await writer.locator('.device-node').filter({ hasText: label }).click()
    const writerDetails = writer.getByTestId('device-dialog')
    await expect(writerDetails).toBeVisible()
    await writerDetails.getByTestId('device-delete').click()
    const writerConfirmation = writer.getByRole('dialog', { name: 'Delete device' })
    await expect(writerConfirmation).toBeVisible()
    await expect(writerConfirmation).toContainText(label)

    const writerDeleteButton = writerConfirmation.getByRole('button', {
      name: 'Delete Device',
      exact: true
    })
    let deleteRequestCount = 0
    writer.on('request', request => {
      if (request.method() === 'POST'
        && new URL(request.url()).pathname.endsWith('/delete')) {
        deleteRequestCount += 1
      }
    })
    await expect(writerDeleteButton).toBeEnabled()
    await writerDeleteButton.evaluate((button: HTMLButtonElement) => {
      button.click()
      button.click()
    })
    await expect.poll(() => deleteRequestCount).toBe(1)

    await expect(observerConfirmation).toHaveCount(0, { timeout: 30_000 })
    await expect(observerDetails).toHaveCount(0)
    await expect(observer.locator('.device-node').filter({ hasText: label })).toHaveCount(0)
    const removalWarning = observer.getByText(
      'This device was deleted elsewhere. Related panels were closed.',
      { exact: true }
    )
    await expect(removalWarning).toBeVisible()
    await expect(removalWarning).toHaveCount(1)
    await expect(observer.getByTestId('control-tab-devices')).toBeFocused()
  } finally {
    releaseObserverPreview()
    await context.close()
  }
})

test('a deletion confirmation closes when another tab changes the target device', async ({ browser, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'tabdeletestale' })
  const context = await browser.newContext()
  const writer = await context.newPage()
  await openWorkspace(writer, auth)
  const label = `Cross tab stale ${Date.now()}`
  await createDevice(writer, label)
  const observer = await context.newPage()
  await openWorkspace(observer, auth)

  try {
    await observer.locator('.device-node').filter({ hasText: label }).click()
    const observerDetails = observer.getByTestId('device-dialog')
    await expect(observerDetails).toBeVisible()
    await observerDetails.getByTestId('device-delete').click()
    const observerConfirmation = observer.getByRole('dialog', { name: 'Delete device' })
    await expect(observerConfirmation).toBeVisible()
    await expect(observerConfirmation.getByRole('button', {
      name: 'Delete Device',
      exact: true
    })).toBeEnabled()

    await writer.locator('.device-node').filter({ hasText: label }).click()
    const writerDetails = writer.getByTestId('device-dialog')
    await expect(writerDetails).toBeVisible()
    await writerDetails.getByTestId('device-rename').click()
    const renameDialog = writer.getByRole('dialog', { name: 'Rename device' })
    const renamedLabel = `${label} renamed`
    await renameDialog.getByPlaceholder('Enter device name').fill(renamedLabel)
    await renameDialog.getByRole('button', { name: 'Confirm' }).click()

    await expect(observerConfirmation).toHaveCount(0, { timeout: 30_000 })
    await expect(observer.getByTestId('device-dialog')).toBeVisible()
    await expect(observer.locator('.device-node').filter({ hasText: renamedLabel }))
      .toBeVisible({ timeout: 15_000 })
  } finally {
    await context.close()
  }
})

test('a delayed initial snapshot cannot suppress a newer cross-tab invalidation', async ({ browser, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'tabsyncrace' })
  const context = await browser.newContext()
  const writer = await context.newPage()
  await openWorkspace(writer, auth)
  const observer = await context.newPage()

  let snapshotRequestCount = 0
  let fingerprintRequestCount = 0
  let markInitialCaptured!: () => void
  const initialCaptured = new Promise<void>(resolve => { markInitialCaptured = resolve })
  let releaseInitialSnapshot!: () => void
  const initialSnapshotRelease = new Promise<void>(resolve => { releaseInitialSnapshot = resolve })
  await observer.route('**/api/board/snapshot', async route => {
    snapshotRequestCount += 1
    if (snapshotRequestCount !== 1) {
      await route.continue()
      return
    }
    const oldResponse = await route.fetch()
    markInitialCaptured()
    await initialSnapshotRelease
    await route.fulfill({ response: oldResponse })
  })
  observer.on('request', request => {
    if (request.method() === 'GET'
      && new URL(request.url()).pathname === '/api/board/model-fingerprints') {
      fingerprintRequestCount += 1
    }
  })

  const openingObserver = openWorkspace(observer, auth)
  await initialCaptured

  try {
    const label = `Cross tab race ${Date.now()}`
    await createDevice(writer, label)
    releaseInitialSnapshot()
    await openingObserver

    await expect.poll(() => snapshotRequestCount).toBeGreaterThanOrEqual(2)
    await expect.poll(() => fingerprintRequestCount).toBeGreaterThanOrEqual(2)
    await expect(observer.locator('.device-node').filter({ hasText: label }))
      .toBeVisible({ timeout: 15_000 })
  } finally {
    releaseInitialSnapshot()
    await context.close()
  }
})

test('a hidden-tab invalidation is consumed by one foreground snapshot refresh', async ({ browser, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'tabsynchidden' })
  const context = await browser.newContext()
  const writer = await context.newPage()
  await openWorkspace(writer, auth)
  const observer = await context.newPage()
  await openWorkspace(observer, auth)

  let snapshotRequestCount = 0
  observer.on('request', request => {
    if (request.method() === 'GET'
      && new URL(request.url()).pathname === '/api/board/snapshot') {
      snapshotRequestCount += 1
    }
  })

  try {
    await observer.evaluate(() => {
      const state = window as Window & { __testVisibilityState?: DocumentVisibilityState }
      state.__testVisibilityState = 'hidden'
      Object.defineProperty(document, 'visibilityState', {
        configurable: true,
        get: () => state.__testVisibilityState
      })
      document.dispatchEvent(new Event('visibilitychange'))
    })

    const label = `Cross tab hidden ${Date.now()}`
    await createDevice(writer, label)
    await observer.waitForTimeout(300)
    expect(snapshotRequestCount).toBe(0)

    const foregroundRefresh = observer.waitForResponse(response =>
      response.request().method() === 'GET'
        && new URL(response.url()).pathname === '/api/board/snapshot')
    await observer.evaluate(() => {
      const state = window as Window & { __testVisibilityState?: DocumentVisibilityState }
      state.__testVisibilityState = 'visible'
      document.dispatchEvent(new Event('visibilitychange'))
      window.dispatchEvent(new Event('focus'))
    })
    await foregroundRefresh

    await expect(observer.locator('.device-node').filter({ hasText: label }))
      .toBeVisible({ timeout: 15_000 })
    await observer.waitForTimeout(300)
    expect(snapshotRequestCount).toBe(1)
  } finally {
    await context.close()
  }
})

test('switching accounts remounts the workspace and rebinds board invalidations', async ({ browser, request }) => {
  const alice = await createAuthenticatedUser(request, { usernamePrefix: 'tabsyncalice' })
  const bob = await createAuthenticatedUser(request, { usernamePrefix: 'tabsyncbob' })
  const context = await browser.newContext()
  const observer = await context.newPage()
  await openWorkspace(observer, alice)
  const storageController = await context.newPage()
  await storageController.goto('/')

  try {
    const aliceLabel = `Alice private ${Date.now()}`
    await createDevice(observer, aliceLabel)

    const bobSnapshot = observer.waitForResponse(response =>
      response.request().method() === 'GET'
        && new URL(response.url()).pathname === '/api/board/snapshot')
    await storageController.evaluate(({ token, user }) => {
      localStorage.setItem('iot_verify_token', token)
      localStorage.setItem('iot_verify_user', JSON.stringify(user))
      localStorage.setItem('iot_verify_auth_sync', JSON.stringify({
        token,
        user,
        updatedAt: Date.now()
      }))
    }, {
      token: bob.token,
      user: {
        userId: bob.userId,
        phone: bob.phone,
        username: bob.username
      }
    })
    await bobSnapshot

    await expect(observer.locator('.device-node').filter({ hasText: aliceLabel })).toHaveCount(0)
    await expect(observer.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })

    const writer = await context.newPage()
    await openWorkspace(writer, bob)
    const bobLabel = `Bob synchronized ${Date.now()}`
    const observerRefresh = observer.waitForResponse(response =>
      response.request().method() === 'GET'
        && new URL(response.url()).pathname === '/api/board/snapshot')
    await createDevice(writer, bobLabel)
    await observerRefresh

    await expect(observer.locator('.device-node').filter({ hasText: bobLabel }))
      .toBeVisible({ timeout: 15_000 })
  } finally {
    await context.close()
  }
})

// A displayed verdict describes the model that was verified. The result dialog is a modal
// overlay, so the reachable way to change the board underneath it is another tab: this tab's
// foreground/cross-tab snapshot refresh reconciles the board while the verdict stays open.
// The verdict must then stop offering actions that imply it describes the current canvas.
test('a cross-tab board change marks an open verification verdict stale', async ({ browser, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'staleverdict' })
  const context = await browser.newContext()
  const viewer = await context.newPage()
  await openWorkspace(viewer, auth)
  const writer = await context.newPage()
  await openWorkspace(writer, auth)

  try {
    // Build the smallest board that yields a violated verdict with a replayable counterexample.
    await viewer.bringToFront()
    const scenePath = path.resolve(process.cwd(), '..', 'docs', 'examples', 'acceptance-demo-scene.json')
    await viewer.getByTestId('scene-import-file').setInputFiles(scenePath)
    await viewer.getByRole('dialog', { name: 'Replace the current scene?' })
      .getByRole('button', { name: 'Replace scene' })
      .click()
    await expect(viewer.getByTestId('scene-import')).toBeEnabled({ timeout: 30_000 })

    await viewer.getByTestId('open-verification-panel').click()
    await viewer.getByTestId('verification-mode-sync').click()
    await viewer.getByTestId('run-verification').click()
    await expect(viewer.getByTestId('verification-result-dialog')).toBeVisible({ timeout: 90_000 })
    // A freshly presented verdict describes the board it was computed from.
    await expect(viewer.getByTestId('verification-result-stale-banner')).toHaveCount(0)
    await expect(viewer.getByTestId('verification-trace-fix').first())
      .toBeVisible({ timeout: 30_000 })

    // Change the board from the other tab, then return so this tab reconciles its snapshot.
    const viewerRefresh = viewer.waitForResponse(response =>
      response.request().method() === 'GET'
        && new URL(response.url()).pathname === '/api/board/snapshot')
    await writer.bringToFront()
    await createDevice(writer, `Stale probe ${Date.now()}`)
    await viewer.bringToFront()
    await viewerRefresh

    // The verdict no longer describes the reconciled board, so it says so and withdraws Fix.
    await expect(viewer.getByTestId('verification-result-dialog')).toBeVisible()
    await expect(viewer.getByTestId('verification-result-stale-banner'))
      .toBeVisible({ timeout: 30_000 })
    await expect(viewer.getByTestId('verification-trace-fix')).toHaveCount(0)
  } finally {
    await context.close()
  }
})

test('an undo refreshes another visible tab like any other board mutation', async ({ browser, request }) => {
  const auth = await createAuthenticatedUser(request, { usernamePrefix: 'tabundo' })
  const context = await browser.newContext()
  const writer = await context.newPage()
  await openWorkspace(writer, auth)

  try {
    // A scene gives both tabs real rules, so the deletion and its undo are observable.
    const scenePath = path.resolve(
      process.cwd(), '..', 'docs', 'examples', 'default-climate-conflict-scene.json'
    )
    await writer.getByTestId('scene-import-file').setInputFiles(scenePath)
    await writer.getByRole('dialog', { name: 'Replace the current scene?' })
      .getByRole('button', { name: 'Replace scene' })
      .click()
    await writer.getByTestId('inspector-tab-rules').click()
    const deleteRule = writer.getByRole('button', { name: 'Delete Rule' }).first()
    await expect(deleteRule).toBeEnabled({ timeout: 30_000 })

    await deleteRule.click()
    await writer.locator('.el-message-box').getByRole('button', { name: /Delete|删除/ }).click()
    await expect(writer.getByTestId('board-undo')).toBeEnabled({ timeout: 30_000 })

    const observer = await context.newPage()
    await openWorkspace(observer, auth)
    await observer.getByTestId('inspector-tab-rules').click()
    const observedRules = observer.getByRole('button', { name: 'Delete Rule' })
    // Wait for the freshly opened tab's rule list to render before baselining. Counting immediately
    // could capture 0 and turn a correct sync into a spurious failure.
    await expect.poll(async () => observedRules.count(), { timeout: 30_000 }).toBeGreaterThan(0)
    const afterDelete = await observedRules.count()

    // Undo changes rules and specifications, so the other tab must be invalidated exactly as it
    // is for a direct mutation — otherwise it keeps showing a board that no longer exists.
    const observerRefresh = observer.waitForResponse(response =>
      response.request().method() === 'GET'
        && new URL(response.url()).pathname === '/api/board/snapshot')
    await writer.getByTestId('board-undo').click()
    await observerRefresh

    await expect.poll(async () => observedRules.count(), { timeout: 30_000 })
      .toBe(afterDelete + 1)
  } finally {
    await context.close()
  }
})
