import type { DeviceNode } from './node'
import type { ModelEnvironmentVariable } from './model'
import type { RuleForm } from './rule'
import type { Specification } from './spec'

/**
 * Board edits that participate in undo.
 *
 * Now includes devices: deletion records the device plus every cascaded rule and specification,
 * so undo can atomically restore the entire state. Creation records just the device.
 *
 * `RULE_ORDER` is one up/down press: it changes no single record, so its journal entry holds the
 * previous ordering rather than a record snapshot.
 */
export const BOARD_EDIT_ENTITY_TYPES = [
  'DEVICE', 'ENVIRONMENT', 'RULE', 'SPECIFICATION', 'RULE_ORDER', 'RULE_SET'
] as const

export type BoardEditEntityType = typeof BOARD_EDIT_ENTITY_TYPES[number]

/** What the original edit did. The undo performs its inverse. */
export const BOARD_EDIT_OPERATIONS = ['CREATE', 'UPDATE', 'DELETE'] as const

export type BoardEditOperation = typeof BOARD_EDIT_OPERATIONS[number]

export const BOARD_UNDO_REASON_CODES = [
  'UNDONE', 'REDONE', 'NOTHING_TO_APPLY', 'AVAILABILITY_ONLY', 'HISTORY_CLEARED'
] as const

export type BoardUndoReasonCode = typeof BOARD_UNDO_REASON_CODES[number]

// Runtime guards, because the boundary parser must reject an unknown value rather than cast it: a
// renamed server code would otherwise become a typed value no consumer branches on.
export const isBoardEditEntityType = (value: unknown): value is BoardEditEntityType =>
  BOARD_EDIT_ENTITY_TYPES.includes(value as BoardEditEntityType)

export const isBoardEditOperation = (value: unknown): value is BoardEditOperation =>
  BOARD_EDIT_OPERATIONS.includes(value as BoardEditOperation)

export const isBoardUndoReasonCode = (value: unknown): value is BoardUndoReasonCode =>
  BOARD_UNDO_REASON_CODES.includes(value as BoardUndoReasonCode)

/** Whether the account currently has an edit to undo and/or one to redo. */
export interface BoardUndoAvailability {
  canUndo: boolean
  canRedo: boolean
  editHistoryRevision: string
}

export const isBoardEditHistoryRevision = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9]{1,19}:[0-9]{1,19}:[0-9]{1,19}$/.test(value)

/**
 * Outcome of an undo or redo.
 *
 * `applied: false` with `NOTHING_TO_APPLY` is a normal result, not a failure: the user pressed the
 * shortcut once more than there is history. `nodes`/`environmentVariables`/`rules`/`specs` are the
 * authoritative post-operation collections, so the client replaces its local state rather than
 * inverting anything itself, and `canUndo`/`canRedo` come from the server journal.
 */
export interface BoardUndoResult extends BoardUndoAvailability {
  applied: boolean
  entityType?: BoardEditEntityType
  originalOperation?: BoardEditOperation
  reasonCode: BoardUndoReasonCode
  nodes: DeviceNode[]
  environmentVariables: ModelEnvironmentVariable[]
  rules: RuleForm[]
  specs: Specification[]
}
