import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const suite = process.argv[2] || 'smoke'
const args = process.argv.slice(3)
if (!['smoke', 'full', 'live-ai'].includes(suite)) {
  throw new Error(`Unknown E2E suite: ${suite}`)
}

// Listing and help must work offline. Actual runs check readiness before building the frontend.
if (!args.some(arg => ['--list', '--help', '-h'].includes(arg))) {
  const apiBaseURL = process.env.E2E_API_BASE_URL || 'http://127.0.0.1:8080'
  try {
    const response = await fetch(`${apiBaseURL}/api/board/snapshot`, { signal: AbortSignal.timeout(5_000) })
    if (response.status !== 401) throw new Error(`Expected HTTP 401, received ${response.status}`)
  } catch (error) {
    console.error(`E2E backend preflight failed at ${apiBaseURL}. Start the test backend or set E2E_API_BASE_URL.`)
    console.error(error instanceof Error ? error.message : String(error))
    process.exit(1)
  }
}

const child = spawn(process.execPath, [require.resolve('@playwright/test/cli'), 'test', ...args], {
  stdio: 'inherit',
  env: { ...process.env, E2E_SUITE: suite,
    ...(suite === 'live-ai' ? { RUN_LIVE_AI_E2E: '1' } : {}) }
})
child.on('error', error => { console.error(error.message); process.exitCode = 1 })
child.on('exit', code => { process.exitCode = code ?? 1 })
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => child.kill(signal))
