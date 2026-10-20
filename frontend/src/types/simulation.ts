// src/types/simulation.ts - 模拟功能相关类型定义

import type { ModelPlaybackScene, RunInitiator } from './model'
import type { ModelRunSnapshot, ModelSemantics } from './modelSemantics'
import type { AsyncTaskStatus, TaskProgressStage } from './task'
import type { ModelGenerationIssue, TraceState } from './verify'
import type { RunPersistence } from './runPersistence'
import type { AttackScenario } from './attackScenario'

// 模拟请求 DTO
// Run parameters only; the scene comes from the caller's persisted board server-side.
export interface SimulationRequest {
  steps: number
  attackScenario: AttackScenario
  enablePrivacy: boolean
}

// 模拟结果 DTO
export interface SimulationResult {
  isAttack: boolean
  attackBudget: number
  enablePrivacy: boolean
  modelSemantics: ModelSemantics
  modelSnapshot: ModelRunSnapshot
  playbackScene: ModelPlaybackScene
  historyPersistence: RunPersistence
  modelComplete: boolean
  disabledRuleCount: number
  generationIssues: ModelGenerationIssue[]
  states: TraceState[]
  steps: number
  requestedSteps: number
  nusmvOutput: string
  logs: string[]
  /**
   * Saved-trajectory id, present only when the run was persisted. The backend result carries no id;
   * `Board.vue` fills it from the `SimulationTrace` it loaded (`simulateAndSave`, or the trace fetched
   * after an async task completes). Absent for preview-only runs, which are not addressable.
   */
  traceId?: number
  /**
   * Whether the saved trajectory's SMV model is available. Downloads require both a saved identity
   * and the server-reported model availability.
   */
  hasSmvModel?: boolean
}

// 模拟记录摘要 (列表用) — SimulationTraceSummaryDto 不返回 userId
export interface AvailableSimulationTraceSummary {
  id: number
  initiator: RunInitiator
  modelComplete: boolean
  disabledRuleCount: number
  generationIssues: ModelGenerationIssue[]
  requestedSteps: number
  steps: number
  isAttack: boolean
  attackBudget: number
  enablePrivacy: boolean
  createdAt: string
  modelSnapshot: ModelRunSnapshot
  dataAvailable: true
  hasSmvModel?: boolean
}

export interface UnavailableSimulationTraceSummary {
  id: number
  initiator: RunInitiator
  createdAt?: string
  dataAvailable: false
  unavailableReasonCode: 'PERSISTED_SEMANTIC_DATA_INVALID' | string
}

export type SimulationTraceSummary = AvailableSimulationTraceSummary | UnavailableSimulationTraceSummary

// 模拟记录详情 — requestJson/userId remain server-internal; context is structured.
export interface SimulationTrace extends Omit<AvailableSimulationTraceSummary, 'dataAvailable' | 'id'> {
  id?: number
  playbackScene: ModelPlaybackScene
  states: TraceState[]
  logs: string[]
  nusmvOutput: string
  modelSemantics: ModelSemantics
  historyPersistence: RunPersistence
  /** See `PersistedTrace.hasSmvModel` — the model's presence, since its content is never sent. */
  hasSmvModel?: boolean
}

// 模拟任务状态
export type SimulationTaskStatus = AsyncTaskStatus

// 模拟任务
export interface SimulationTask {
  id: number
  initiator: RunInitiator
  // userId 已删除 — 后端不返回
  status: SimulationTaskStatus
  requestedSteps: number
  steps?: number
  modelComplete?: boolean
  disabledRuleCount?: number
  generationIssues?: ModelGenerationIssue[]
  errorMessage?: string
  checkLogs?: string[]
  createdAt: string
  startedAt?: string
  completedAt?: string
  processingTimeMs?: number
  progress?: number       // 新增：0-100 进度
  progressStage?: TaskProgressStage
  isAttack: boolean
  attackBudget: number
  enablePrivacy: boolean
  modelSemantics: ModelSemantics
  modelSnapshot: ModelRunSnapshot
  simulationTraceId?: number
}

export type SimulationTaskSummary = Pick<
  SimulationTask,
  | 'id'
  | 'initiator'
  | 'status'
  | 'createdAt'
  | 'startedAt'
  | 'completedAt'
  | 'processingTimeMs'
  | 'progress'
  | 'progressStage'
  | 'isAttack'
  | 'attackBudget'
  | 'enablePrivacy'
  | 'modelSemantics'
  | 'modelSnapshot'
  | 'requestedSteps'
  | 'steps'
  | 'modelComplete'
  | 'disabledRuleCount'
  | 'generationIssues'
  | 'simulationTraceId'
  | 'errorMessage'
>
