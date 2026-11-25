import type { ModelRunSnapshot } from '@/types/modelSemantics'

export type FuzzVerificationHandoffSource = {
  runId: number
  specificationId?: string
  specificationLabel?: string
  modelSnapshot?: ModelRunSnapshot
}

export type RunPanelKind = 'verification' | 'simulation' | 'fuzzing'
export type RunPanelView = 'settings' | 'run'
export type BoardWorkflowPanel =
  | { kind: 'history' }
  | { kind: 'simulation' | 'fuzzing', view: RunPanelView }
  | { kind: 'verification', view: RunPanelView, handoff: FuzzVerificationHandoffSource | null }

export type ForegroundRunBlock = 'playback' | 'modal'
export type RunPresentation = 'foreground' | 'background' | ForegroundRunBlock

export const foregroundRunBlock = (playbackActive: boolean, modalOpen: boolean): ForegroundRunBlock | null => {
  if (playbackActive) return 'playback'
  if (modalOpen) return 'modal'
  return null
}

export const chooseRunPresentation = (selected: boolean, block: ForegroundRunBlock | null): RunPresentation =>
  selected ? block ?? 'foreground' : 'background'
