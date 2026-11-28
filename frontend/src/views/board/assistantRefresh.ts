import type { AssistantAction, StreamRefreshTarget } from '@/types/chat'
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
 * The target names come from the stream contract in `types/chat.ts`.
 */
export interface AssistantRefreshEffects {
  /** The `defineExpose` method on the board view that reloads this target. */
  readonly method: 'refreshBoardSnapshot' | 'refreshAllBoardState' | 'refreshRunHistory'
  readonly invalidationScope: BoardInvalidationScope
}

const BOARD_SNAPSHOT: AssistantRefreshEffects = { method: 'refreshBoardSnapshot', invalidationScope: 'board' }

const EFFECTS: Record<StreamRefreshTarget, AssistantRefreshEffects> = {
  rule_list: BOARD_SNAPSHOT,
  spec_list: BOARD_SNAPSHOT,
  board_state: { method: 'refreshAllBoardState', invalidationScope: 'board' },
  device_list: BOARD_SNAPSHOT,
  environment_list: BOARD_SNAPSHOT,
  template_list: BOARD_SNAPSHOT,
  run_history: { method: 'refreshRunHistory', invalidationScope: 'run-history' }
}

const TASK_STARTED_ACTIONS = new Set<AssistantAction>([
  'VERIFICATION_TASK_STARTED', 'SIMULATION_TASK_STARTED', 'EXPLORATION_TASK_STARTED'
])

export const assistantRefreshEffects = (
  target: StreamRefreshTarget
): AssistantRefreshEffects => EFFECTS[target]

export const assistantStreamInvalidationScope = (
  target: StreamRefreshTarget,
  action?: AssistantAction
): BoardInvalidationScope => {
  if (target === 'run_history' && action && TASK_STARTED_ACTIONS.has(action)) return 'tasks'
  // RUN_HISTORY_UPDATED covers both task mutations and evidence deletion.
  return EFFECTS[target].invalidationScope
}
