import type { BoardUndoAvailability } from '@/types/boardEdit'
import type { DeviceNode } from '@/types/node'
import type { ModelEnvironmentVariable } from '@/types/model'
import type { RuleForm } from '@/types/rule'
import type { Specification } from '@/types/spec'

export interface BoardNodeSnapshotOptions {
  externalRefresh?: boolean
  locallyRemovedNodeId?: string
}

/**
 * The follow-ups every device/rule/specification mutation owes the rest of the board.
 *
 * These four were previously hand-assembled at each of ~10 call sites, in slightly different
 * orders and with slightly different omissions — rule reorder never reported undo availability,
 * and undo/redo never rebuilt the canvas edges. Nothing detected either gap, because a later
 * unrelated refresh usually repaired the state, which made the omissions look harmless.
 *
 * Centralising them turns "remember four things" into one call, so a new mutation cannot forget
 * one and a missing dependency is a type error rather than a silent staleness bug.
 */
export interface BoardSemanticScene {
  /** Authoritative device list, when the mutation returned one. */
  nodes?: DeviceNode[]
  nodeSnapshotOptions?: BoardNodeSnapshotOptions
  /** Authoritative Environment Pool, when a device mutation changed its required variables. */
  environmentVariables?: ModelEnvironmentVariable[]
  /** Authoritative rule list, when the mutation returned one. */
  rules?: RuleForm[]
  /** Authoritative specification list, when the mutation returned one. */
  specs?: Specification[]
  /** Undo availability the server reported, when this mutation is reversible. */
  availability?: Partial<BoardUndoAvailability>
  /** False only when the server explicitly confirmed that the request changed no model state. */
  semanticChanged?: boolean
}

export interface BoardFocusState {
  nodeId: string | null
  ruleId: string | null
  specId: string | null
}

/** Keeps inspector selection aligned with whichever authoritative collections were refreshed. */
export const reconcileBoardFocus = (
  focus: BoardFocusState,
  scene: Pick<BoardSemanticScene, 'nodes' | 'rules' | 'specs'>
): BoardFocusState => ({
  nodeId: scene.nodes && focus.nodeId
    && !scene.nodes.some(node => node.id === focus.nodeId) ? null : focus.nodeId,
  ruleId: scene.rules && focus.ruleId
    && !scene.rules.some(rule => String(rule.id ?? '') === focus.ruleId) ? null : focus.ruleId,
  specId: scene.specs && focus.specId
    && !scene.specs.some(spec => spec.id === focus.specId) ? null : focus.specId
})

export interface BoardSemanticCommitPorts {
  setNodes: (nodes: DeviceNode[], options?: BoardNodeSnapshotOptions) => void
  setEnvironmentVariables: (variables: ModelEnvironmentVariable[]) => void
  setRules: (rules: RuleForm[]) => void
  setSpecs: (specs: Specification[]) => void
  /** Canvas connection lines are derived from rules, so they are rebuilt whenever rules change. */
  syncRuleDerivedEdges: () => void
  /**
   * A semantic change invalidates any displayed verdict; it described the previous model.
   *
   * Mutations that run inside the board mutation queue are already marked stale by its
   * fingerprint comparison. Direct mutation receipts also withdraw stale verdict actions
   * as soon as their authoritative collections are installed.
   */
  markVerificationResultStale: () => void
  /** Mirrors server-reported undo availability. Omitted availability must not clear real history. */
  syncUndoAvailability: (availability: Partial<BoardUndoAvailability>) => void
  /** Drops an inspector focus whose target no longer exists in the authoritative collections. */
  clearDanglingFocus: (scene: { nodes?: DeviceNode[]; rules?: RuleForm[]; specs?: Specification[] }) => void
}

/**
 * Applies one semantic board mutation's authoritative result.
 *
 * Order matters and is fixed here rather than per call site: collections first, then everything
 * derived from them, so no derived value is ever computed from a stale collection.
 */
export const createBoardSemanticCommit = (ports: BoardSemanticCommitPorts) =>
  (scene: BoardSemanticScene): void => {
    if (scene.nodes) ports.setNodes(scene.nodes, scene.nodeSnapshotOptions)
    if (scene.environmentVariables) ports.setEnvironmentVariables(scene.environmentVariables)
    if (scene.rules) ports.setRules(scene.rules)
    if (scene.specs) ports.setSpecs(scene.specs)

    // Edges are a projection of rules, so they must be rebuilt in the same step. Relying on a
    // later refresh to do it is correct only by accident.
    if (scene.rules) ports.syncRuleDerivedEdges()

    ports.clearDanglingFocus(scene)

    // Pass the revision together with the collections the user has just seen change.
    if (scene.availability) ports.syncUndoAvailability(scene.availability)

    if (scene.semanticChanged !== false) ports.markVerificationResultStale()
  }

export type BoardSemanticCommit = ReturnType<typeof createBoardSemanticCommit>
