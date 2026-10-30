import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const terminalAt = board.indexOf('<PlaybackTerminal')
const simulationAt = board.indexOf('<SimulationTimeline', terminalAt)
const terminal = board.slice(terminalAt, simulationAt)

describe('counterexample run information in the playback terminal', () => {
  it('does not route an exploration finding to a verification run', () => {
    const contextAt = terminal.indexOf('data-testid="counterexample-run-context"')
    expect(contextAt).toBeGreaterThan(-1)
    expect(terminal.slice(0, contextAt)).toContain('v-if="!activeFuzzingFinding"')
    expect(terminal).toContain('app.fuzzFindingReplayHint')
  })
})
