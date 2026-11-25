import { STREAM_REFRESH_TARGETS, type AssistantAction } from '@/types/chat'
import type { BoardInvalidationScope } from '@/utils/boardInvalidation'

/**
 * Which board state an assistant `REFRESH_DATA` command invalidates.
 *
 * The assistant mutates the board through the same service methods the UI uses, so its edits carry
 * the same consequences — including undo availability, because its device, environment, rule, and
 * specification tools go through journal-recording write paths. Routing every target through one
 * table keeps that parity explicit instead of leaving it to remember-to-call discipline at each
 * handler.
 *
 * The target names themselves come from `types/chat.ts`, which is the wire contract; keeping a second
 * copy here let the two drift.
 */
export const ASSISTANT_REFRESH_TARGETS = STREAM_REFRESH_TARGETS

export type AssistantRefreshTarget = typeof ASSISTANT_REFRESH_TARGETS[number]

export const isAssistantRefreshTarget = (value: unknown): value is AssistantRefreshTarget =>
  typeof value === 'string' && (ASSISTANT_REFRESH_TARGETS as readonly string[]).includes(value)

export interface AssistantRefreshEffects {
  /** The `defineExpose` method on the board view that reloads this target. */
  method: string
  invalidationScope: BoardInvalidationScope
}

const EFFECTS: Record<AssistantRefreshTarget, AssistantRefreshEffects> = {
  rule_list: { method: 'refreshRules', invalidationScope: 'board' },
  spec_list: { method: 'refreshSpecifications', invalidationScope: 'board' },
  board_state: { method: 'refreshAllBoardState', invalidationScope: 'board' },
  device_list: { method: 'refreshDevices', invalidationScope: 'board' },
  environment_list: { method: 'refreshEnvironmentVariables', invalidationScope: 'board' },
  template_list: { method: 'refreshDeviceTemplates', invalidationScope: 'board' },
  run_history: { method: 'refreshRunHistory', invalidationScope: 'run-history' }
}

const TASK_STARTED_ACTIONS = new Set<AssistantAction>([
  'VERIFICATION_TASK_STARTED', 'SIMULATION_TASK_STARTED', 'EXPLORATION_TASK_STARTED'
])

export const assistantRefreshEffects = (
  target: AssistantRefreshTarget
): AssistantRefreshEffects => EFFECTS[target]

export const assistantStreamInvalidationScope = (
  target: AssistantRefreshTarget,
  action?: AssistantAction
): BoardInvalidationScope => {
  if (target === 'run_history' && action && TASK_STARTED_ACTIONS.has(action)) return 'tasks'
  // RUN_HISTORY_UPDATED covers both task mutations and evidence deletion.
  return EFFECTS[target].invalidationScope
}
