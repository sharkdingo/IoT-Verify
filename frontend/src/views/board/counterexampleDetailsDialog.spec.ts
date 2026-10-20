import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const terminalAt = board.indexOf('<PlaybackTerminal')
const simulationAt = board.indexOf('<SimulationTimeline', terminalAt)
const terminal = board.slice(terminalAt, simulationAt)

describe('counterexample run information in the playback terminal', () => {
  it('removes the separate details dialog and reads the active frozen trace', () => {
    expect(terminalAt).toBeGreaterThan(-1)
    expect(simulationAt).toBeGreaterThan(terminalAt)
    expect(board).not.toContain('data-testid="trace-details-dialog"')
    expect(board).not.toContain('traceDetailsView')
    expect(terminal).toContain('<template #run>')
    expect(terminal).toContain('getTraceSpecDisplayTitle(currentTrace)')
    expect(terminal).toContain('currentTrace.checkedExpression')
    expect(terminal).not.toContain('violatedSpec?.name')
  })

  it('keeps run diagnostics, scope and the owning run reachable without copying its download', () => {
    expect(terminal).toContain('counterexample-generation-issues')
    expect(terminal).toContain('generationIssueReasonKey(issue)')
    expect(terminal).toContain('currentTrace.modelSnapshot.deviceCount')
    expect(terminal).toContain('currentTrace.enablePrivacy')
    expect(terminal).toContain('counterexample-open-owning-run')
    expect(terminal).toContain('v-if="currentTrace.verificationTaskId"')
    expect(terminal).not.toContain('download-counterexample-smv')
    expect(terminal).not.toContain('downloadVerificationTraceSmv')
    const handler = board.slice(board.indexOf('const openOwningVerificationRun'), board.indexOf('/* ===== Deep-linkable run surfaces ====='))
    expect(handler).toMatch(/openRunTarget\(\{\s*kind: 'verification'/)
    expect(handler).not.toMatch(/verificationResult\.value\s*=/)
  })

  it('does not route an exploration finding to a verification run', () => {
    const contextAt = terminal.indexOf('data-testid="counterexample-run-context"')
    expect(contextAt).toBeGreaterThan(-1)
    expect(terminal.slice(0, contextAt)).toContain('v-if="!activeFuzzingFinding"')
    expect(terminal).toContain('app.fuzzFindingReplayHint')
  })
})
