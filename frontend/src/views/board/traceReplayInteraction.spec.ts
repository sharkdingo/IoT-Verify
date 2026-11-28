// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import ts from 'typescript'
import { mount } from '@vue/test-utils'
import { computed, effectScope, ref, shallowRef } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/assets/i18n'
import PlaybackTerminal from '@/components/PlaybackTerminal.vue'
import { hasPersistedVerificationTrace } from '@/utils/runResponse'
import { findPlaybackLoopRange } from '@/utils/traceView'
import { usePlaybackCursor } from './usePlaybackCursor'

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
      isSimulationPlaybackActive: ref(false),
      isAnyRecommendationRunning: vi.fn(() => false),
      ensureLiveBoardEditorClosedForPlayback: vi.fn(() => true),
      hasPersistedVerificationTrace,
      closeResultDialog: vi.fn(), startPlayback: vi.fn(), notifyBlocked: vi.fn(), t: (key: string) => key,
      openRunTarget: vi.fn()
    }
    return { ports, traces, play: bind('selectAndPlayTrace', ports) }
  }

  it.each(['NOT_REQUESTED', 'FAILED', 'OUTCOME_UNKNOWN'] as const)(
    'keeps %s evidence immediately playable without inventing a saved address', status => {
      const { ports, traces, play } = context(status)
      play(1)
      expect(ports.openRunTarget).not.toHaveBeenCalled()
      expect(ports.closeResultDialog).toHaveBeenCalledOnce()
      expect(ports.startPlayback).toHaveBeenCalledExactlyOnceWith({ kind: 'counterexample', trace: traces[1] }, 0)
    }
  )

  it('keeps an active editor and its artifact unchanged when replay is blocked', () => {
    const { ports, play } = context('SAVED')
    ports.ensureLiveBoardEditorClosedForPlayback.mockReturnValue(false)
    play(1)
    expect(ports.openRunTarget).not.toHaveBeenCalled()
    expect(ports.closeResultDialog).not.toHaveBeenCalled()
    expect(ports.startPlayback).not.toHaveBeenCalled()
  })

  it('reports the simulation before a pending recommendation when both block replay', () => {
    const { ports, play } = context('SAVED')
    ports.isSimulationPlaybackActive.value = true
    ports.isAnyRecommendationRunning.mockReturnValue(true)
    play(1)
    ports.isSimulationPlaybackActive.value = false
    play(1)
    expect(ports.notifyBlocked.mock.calls).toEqual([
      ['app.closeCurrentSimulationFirst'], ['app.recommendationGenerationInProgress']
    ])
    expect(ports.openRunTarget).not.toHaveBeenCalled()
  })
})

it('replaces playback evidence and stops autoplay on switch and dismissal', () => {
  vi.useFakeTimers()
  const scope = effectScope()
  try {
    const ports: Record<string, any> = {
      computed, activePlayback: shallowRef(null),
      boardPanels: { control: { collapsed: false }, inspector: { collapsed: false } },
      playbackPanelOpen: ref(false), playbackTerminalTab: ref('run'),
      playbackViewportFollowsFit: false, playbackCanvasFrame: {},
      playbackCanvasZoom: ref(2), playbackCanvasPan: ref({ x: 100, y: 100 }),
      deepClone: structuredClone, refreshCurrentBoardModelFingerprints: vi.fn(), clearRunDeepLink: vi.fn()
    }
    for (const name of ['activePlaybackKind', 'activePlaybackScene', 'isModelPlaybackActive', 'currentTrace',
      'activeFuzzingFinding', 'simulationPlaybackResult', 'activePlaybackStates', 'resetPlaybackViewport',
      'startPlayback', 'closePlayback', 'dismissPlayback']) ports[name] = bind(name, ports)
    const cursor = scope.run(() => usePlaybackCursor(computed(() => ports.activePlaybackStates.value.length)))!
    Object.assign(ports, { goToState: cursor.seek, stopPlayback: cursor.stop })
    const trajectory = (id: string) => ({ playbackScene: { nodes: [{ id }] }, states: [{}, {}, {}] })
    const counterexample = trajectory('verified')
    ports.startPlayback({ kind: 'counterexample', trace: counterexample }, 2)
    expect(cursor.selectedStateIndex.value).toBe(2)
    expect(ports.isModelPlaybackActive.value).toBe(true)
    counterexample.playbackScene.nodes[0]!.id = 'edited'
    expect(ports.activePlaybackScene.value.nodes[0].id).toBe('verified')
    cursor.toggle()

    const simulation = trajectory('simulated')
    ports.startPlayback({ kind: 'simulation', result: simulation }, 0)
    vi.advanceTimersByTime(4500)
    expect(cursor.selectedStateIndex.value).toBe(0)
    expect(cursor.isPlaying.value).toBe(false)
    expect(ports.activePlaybackStates.value).toBe(simulation.states)
    expect(ports.currentTrace.value).toBeNull()
    expect(ports.activePlaybackScene.value.nodes[0].id).toBe('simulated')

    const finding = { firstViolationStep: 1 }
    ports.startPlayback({ kind: 'fuzzing', trace: trajectory('candidate'), finding }, finding.firstViolationStep)
    expect(cursor.selectedStateIndex.value).toBe(1)
    expect(ports.activeFuzzingFinding.value).toBe(finding)
    ports.playbackPanelOpen.value = false
    cursor.toggle()
    ports.dismissPlayback()
    vi.advanceTimersByTime(4500)
    expect(cursor.selectedStateIndex.value).toBe(1)
    expect(vi.getTimerCount()).toBe(0)
    expect(ports.activePlaybackStates.value).toEqual([])
    expect(ports.activeFuzzingFinding.value).toBeNull()
    expect(ports.activePlaybackScene.value).toBeNull()
    expect(ports.isModelPlaybackActive.value).toBe(false)
    expect(ports.playbackCanvasZoom.value).toBe(1)
    expect(ports.playbackCanvasPan.value).toEqual({ x: 0, y: 0 })
    expect(ports.clearRunDeepLink).toHaveBeenCalledOnce()
  } finally {
    scope.stop()
    vi.useRealTimers()
  }
})

it('uses the last loop marker for the explanation and the liveness steps', () => {
  const states = [{}, { loopStart: true }, { loopStart: true }, {}, { loopBack: true }]
  const currentTrace = ref({ states, violatedSpec: { templateId: '5' } })
  const activePlaybackStates = computed(() => currentTrace.value.states)
  const ports: Record<string, any> = {
    computed, currentTrace, activePlaybackStates, activeFuzzingFinding: ref(null), findPlaybackLoopRange
  }
  for (const name of ['LIVENESS_TEMPLATES', 'LAST_STATE_VIOLATION_TEMPLATES', 'playbackLoopRange',
    'activePlaybackLoopRange', 'counterexampleViolationStep', 'counterexampleViolationSteps']) {
    ports[name] = bind(name, ports)
  }

  expect(ports.activePlaybackLoopRange.value).toEqual({ start: 3, end: 5 })
  for (const templateId of ['2', '5', '6']) {
    currentTrace.value.violatedSpec.templateId = templateId
    expect(ports.counterexampleViolationStep.value).toBeUndefined()
    expect(ports.counterexampleViolationSteps.value).toEqual([2, 3, 4])
  }

  for (const templateId of ['1', '3', '4', '7']) {
    currentTrace.value.violatedSpec.templateId = templateId
    expect(ports.counterexampleViolationSteps.value).toEqual([4])
  }
  expect(ports.activePlaybackLoopRange.value).toEqual({ start: 3, end: 5 })

  for (const incomplete of [states.slice(0, -1), [{}, { loopBack: true }]]) {
    currentTrace.value = { states: incomplete, violatedSpec: { templateId: '5' } }
    expect(ports.activePlaybackLoopRange.value).toBeNull()
    expect(ports.counterexampleViolationSteps.value).toEqual([])
  }
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

  it('retires a running cursor when its Board scope is disposed', () => {
    vi.useFakeTimers()
    const scope = effectScope()
    try {
      const cursor = scope.run(() => usePlaybackCursor(ref(3)))!
      cursor.toggle()
      vi.advanceTimersByTime(1500)
      expect(cursor.selectedStateIndex.value).toBe(1)

      scope.stop()
      vi.advanceTimersByTime(3000)
      expect(cursor.isPlaying.value).toBe(false)
      expect(cursor.selectedStateIndex.value).toBe(1)
    } finally {
      scope.stop()
      vi.useRealTimers()
    }
  })
})
