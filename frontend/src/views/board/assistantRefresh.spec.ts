import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

import {
  ASSISTANT_REFRESH_TARGETS,
  assistantRefreshEffects,
  assistantStreamInvalidationScope,
  isAssistantRefreshTarget
} from './assistantRefresh'

describe('assistant refresh targets', () => {
  it('rejects an unknown target rather than guessing', () => {
    expect(isAssistantRefreshTarget('rule_list')).toBe(true)
    expect(isAssistantRefreshTarget('rules')).toBe(false)
    expect(isAssistantRefreshTarget('')).toBe(false)
    expect(isAssistantRefreshTarget(undefined)).toBe(false)
  })

  it('refreshes only the inbox for a confirmed background task start', () => {
    expect(assistantStreamInvalidationScope('run_history', 'SIMULATION_TASK_STARTED')).toBe('tasks')
  })

  it('refreshes tasks and results when a run-history change can affect either', () => {
    expect(assistantStreamInvalidationScope('run_history', 'RUN_HISTORY_UPDATED')).toBe('run-history')
    expect(assistantStreamInvalidationScope('run_history')).toBe('run-history')
  })

  it('invalidates other tabs for every target that reloads board model state', () => {
    for (const target of ASSISTANT_REFRESH_TARGETS) {
      if (target === 'run_history') continue
      expect(assistantStreamInvalidationScope(target), target).toBe('board')
    }
  })

  it('describes every declared target, so a new one cannot be silently unhandled', () => {
    for (const target of ASSISTANT_REFRESH_TARGETS) {
      expect(assistantRefreshEffects(target), target).toBeDefined()
    }
  })

  it('names methods the board view actually exposes', () => {
    // The method name is resolved by string at runtime, so a typo would make the assistant
    // silently report failure. Pin it against the board's real `defineExpose` block.
    const boardSource = readFileSync(
      resolve(dirname(fileURLToPath(import.meta.url)), '..', 'Board.vue'), 'utf8')
    const exposed = boardSource.slice(
      boardSource.indexOf('defineExpose({'),
      boardSource.indexOf('})', boardSource.indexOf('defineExpose({')))

    for (const target of ASSISTANT_REFRESH_TARGETS) {
      expect(exposed, target).toContain(`${assistantRefreshEffects(target).method}:`)
    }
  })
})

describe('parity with the backend', () => {
  it('declares exactly the targets the backend progress presenter can emit', () => {
    // ChatToolProgressPresenter owns the tool-to-refresh mapping consumed by ChatServiceImpl.
    // A target it emits but we do not declare is silently dropped as "unsupported", so the
    // workspace would keep showing stale data after a successful tool run.
    const presenter = readFileSync(resolve(
      dirname(fileURLToPath(import.meta.url)),
      '..', '..', '..', '..',
      'backend/src/main/java/cn/edu/nju/Iot_Verify/component/ai/chat/ChatToolProgressPresenter.java'
    ), 'utf8')
    const mappingStart = presenter.indexOf('public List<String> potentialRefreshTargets')
    const mappingEnd = presenter.indexOf('private String confirmedAction', mappingStart)
    const mapping = presenter.slice(mappingStart, mappingEnd)

    const emitted = [...new Set(
      [...mapping.matchAll(/"([a-z]+_(?:list|state|history))"/g)].map(match => match[1])
    )].sort()

    expect(mappingStart).toBeGreaterThanOrEqual(0)
    expect(mappingEnd).toBeGreaterThan(mappingStart)
    expect(emitted.length).toBeGreaterThan(0)
    expect([...ASSISTANT_REFRESH_TARGETS].sort()).toEqual(emitted)
  })
})
