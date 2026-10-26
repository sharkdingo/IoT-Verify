// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/assets/i18n'
import PlaybackTerminal from '@/components/PlaybackTerminal.vue'
import { hasPersistedVerificationTrace } from '@/utils/runResponse'

const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
const scriptStart = board.indexOf('<script setup lang="ts">') + '<script setup lang="ts">'.length
const script = board.slice(scriptStart, board.indexOf('</script>', scriptStart))
const source = ts.createSourceFile('Board.ts', script, ts.ScriptTarget.Latest, true)

const bind = (name: string, ports: Record<string, unknown>) => {
  const declaration = source.statements.filter(ts.isVariableStatement)
    .flatMap(statement => [...statement.declarationList.declarations])
    .find(item => item.name.getText(source) === name)
  if (!declaration?.initializer) throw new Error(`Missing Board function ${name}`)
  const handler = ts.transpileModule(`const handler = ${declaration.initializer.getText(source)}`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022 }
  }).outputText
  return new Function('ports', `with (ports) { ${handler}; return handler }`)(ports)
}

beforeEach(() => { Element.prototype.scrollIntoView = vi.fn() })

describe('result-dialog counterexample replay', () => {
  const context = (status: 'SAVED' | 'NOT_REQUESTED' | 'FAILED' | 'OUTCOME_UNKNOWN') => {
    const traces = [52, 57].map(id => status === 'SAVED'
      ? { id, verificationTaskId: 19, states: [{}] }
      : { states: [{}] })
    const ports = {
      verificationResult: { value: {
        historyPersistence: status === 'SAVED' ? { status, runId: 19 }
          : status === 'NOT_REQUESTED' ? { status } : { status, reasonCode: 'HISTORY_SAVE_FAILED' }, traces
      } },
      simulationAnimationState: { value: { visible: false } },
      isAnyRecommendationRunning: vi.fn(() => false),
      ensureLiveBoardEditorClosedForPlayback: vi.fn(() => true),
      hasPersistedVerificationTrace, verificationRuns: { value: [] },
      staleDeepLink: { value: true }, navigateToRunTarget: vi.fn(), applyDeepLinkTarget: vi.fn(),
      resetPlaybackTerminal: vi.fn(), activeFuzzingFinding: { value: {} as unknown },
      savedTraces: { value: [] as unknown[] }, closeResultDialog: vi.fn(),
      openTraceAnimationAt: vi.fn(), notifyBlocked: vi.fn(), t: (key: string) => key,
      selectAndPlayVerificationTrace: vi.fn(), runIdForOpenTrace: vi.fn(),
      openRunTarget: vi.fn(), openVerificationTraceFromHistory: vi.fn()
    }
    ports.runIdForOpenTrace.mockImplementation(bind('runIdForOpenTrace', ports))
    ports.openRunTarget.mockImplementation(bind('openRunTarget', ports))
    ports.openVerificationTraceFromHistory.mockImplementation(bind('openVerificationTraceFromHistory', ports))
    return { ports, traces, play: bind('selectAndPlayTrace', ports) }
  }

  it('opens the selected saved trace through its own run address and shared loader', async () => {
    const { ports, play } = context('SAVED')
    play(1)
    expect(ports.openVerificationTraceFromHistory).toHaveBeenCalledExactlyOnceWith(57)
    await ports.openVerificationTraceFromHistory.mock.results[0]!.value

    const target = { kind: 'verification', runId: 19, traceId: 57 }
    expect(ports.navigateToRunTarget).toHaveBeenCalledExactlyOnceWith(target, 'push')
    expect(ports.applyDeepLinkTarget).toHaveBeenCalledExactlyOnceWith(target)
    expect(ports.staleDeepLink.value).toBe(false)
    expect(ports.openTraceAnimationAt).not.toHaveBeenCalled()
    expect(ports.savedTraces.value).toEqual([])
  })

  it.each(['NOT_REQUESTED', 'FAILED', 'OUTCOME_UNKNOWN'] as const)(
    'keeps %s evidence immediately playable without inventing a saved address', status => {
      const { ports, traces, play } = context(status)
      play(1)
      expect(ports.openVerificationTraceFromHistory).not.toHaveBeenCalled()
      expect(ports.navigateToRunTarget).not.toHaveBeenCalled()
      expect(ports.applyDeepLinkTarget).not.toHaveBeenCalled()
      expect(ports.savedTraces.value).toEqual(traces)
      expect(ports.savedTraces.value).not.toBe(traces)
      expect(ports.closeResultDialog).toHaveBeenCalledOnce()
      expect(ports.openTraceAnimationAt).toHaveBeenCalledExactlyOnceWith(1)
      expect(ports.resetPlaybackTerminal).toHaveBeenCalledOnce()
      expect(ports.activeFuzzingFinding.value).toBeNull()
    }
  )

  it('keeps an active editor and its artifact unchanged when replay is blocked', () => {
    const { ports, play } = context('SAVED')
    ports.ensureLiveBoardEditorClosedForPlayback.mockReturnValue(false)
    play(1)
    expect(ports.openVerificationTraceFromHistory).not.toHaveBeenCalled()
    expect(ports.navigateToRunTarget).not.toHaveBeenCalled()
    expect(ports.closeResultDialog).not.toHaveBeenCalled()
    expect(ports.openTraceAnimationAt).not.toHaveBeenCalled()
  })
})

describe('counterexample manual seek', () => {
  it.each([
    { steps: [] as number[], violation: undefined, label: null },
    { steps: [2], violation: 2, label: 'Locate violation' },
    { steps: [2, 3], violation: undefined, label: 'Locate violation cycle' }
  ])('offers only the known violation location: $label', async ({ steps, violation, label }) => {
    i18n.global.locale.value = 'en'
    expect(board).toContain(':violation-steps="counterexampleViolationSteps"')
    expect(board).toContain(':locate-label="counterexampleViolationStep === undefined ?')
    expect(board).toContain('@locate-violation="goToState(counterexampleViolationSteps[0])"')
    const wrapper = mount(PlaybackTerminal, {
      props: {
        kind: 'counterexample', selectedStateIndex: 0, totalStates: 4, playing: false,
        stateLabel: index => `State ${index + 1}`, violationSteps: steps,
        locateLabel: i18n.global.t(violation === undefined
          ? 'app.traceVisualization.jumpToViolationLoop' : 'app.traceVisualization.jumpToViolation')
      },
      global: { plugins: [i18n], stubs: { Teleport: true } }
    })
    const button = wrapper.find('[data-testid="trace-timeline-jump-violation"]')
    expect(button.exists()).toBe(label !== null)
    if (label !== null) {
      expect(button.attributes('aria-label')).toBe(label)
      await button.trigger('click')
      expect(wrapper.emitted('locate-violation')).toHaveLength(1)
    }
    wrapper.unmount()
  })

  it('pauses playback and highlights the selected state from the same trace', () => {
    // Exercise the owning handler without mounting the Board's API and canvas lifecycle.
    const animation = { value: { isPlaying: true, selectedStateIndex: 0 } }
    const trace = { states: [{}, {}, {}], runId: 19 }
    const highlighted = { value: null as unknown }
    const stop = vi.fn(() => { animation.value.isPlaying = false })
    const seek = bind('goToState', {
      stopTraceAnimation: stop, totalStates: { value: 3 }, traceAnimationState: animation,
      currentTrace: { value: trace }, highlightedTrace: highlighted
    })

    seek(1)
    expect(stop).toHaveBeenCalledOnce()
    expect(animation.value.isPlaying).toBe(false)
    expect(animation.value.selectedStateIndex).toBe(1)
    expect(highlighted.value).toEqual({ ...trace, selectedStateIndex: 1 })
  })
})
