import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const simulation = readFileSync(join(process.cwd(), 'src/components/SimulationTimeline.vue'), 'utf8')

describe('shared playback terminal wiring', () => {
  it('uses one layout for formal counterexamples, exploration findings and simulations', () => {
    expect(board).toContain(':kind="activeFuzzingFinding ? \'fuzzing\' : \'counterexample\'"')
    expect(simulation).toContain('<PlaybackTerminal')
    expect(simulation).toContain('kind="simulation"')
  })

  it('supplies frozen step evidence to the changes slot in both replay owners', () => {
    const changesSlots = [...board.matchAll(/<template #changes>([\s\S]*?)<\/template>/g)]
    expect(changesSlots).toHaveLength(2)
    for (const [, slot] of changesSlots) {
      expect(slot).toContain('<PlaybackChangesPanel')
      expect(slot).toContain(':changes="activePlaybackChanges"')
      expect(slot).toContain(':environment-changes="activePlaybackEnvironmentChanges"')
    }
    expect(simulation).toContain('<slot name="changes" />')
  })

  it('keeps a hidden terminal mounted and makes panel visibility independent of the playback lock', () => {
    expect(board.match(/v-show="playbackPanelOpen"/g)).toHaveLength(2)
    expect(board).toContain('data-testid="playback-panel-toggle"')
    expect(board).toContain("traceAnimationState.visible ? 'trace-timeline-close' : 'simulation-timeline-close'")
    expect(board).not.toContain('playbackChangesDismissedKey')
  })
})
