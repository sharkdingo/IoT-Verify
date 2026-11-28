import { computed, nextTick, onScopeDispose, readonly, ref, watch } from 'vue'
import type { BoardModelComparison, BoardSemanticSnapshot } from '@/api/board'
import type { BoardModelFingerprints } from '@/types/modelSemantics'

const compareNames = (left: { name: string }, right: { name: string }) =>
  left.name < right.name ? -1 : left.name === right.name ? 0 : 1

const serializeModelSource = (scene: BoardSemanticSnapshot) => JSON.stringify({
  devices: scene.nodes.map(node => ({
    id: node.id,
    templateName: node.templateName,
    state: node.state,
    currentStateTrust: node.currentStateTrust,
    currentStatePrivacy: node.currentStatePrivacy,
    variables: node.variables,
    privacies: node.privacies
  })).sort((left, right) => left.id.localeCompare(right.id)),
  rules: scene.rules,
  specifications: scene.specifications,
  environmentVariables: [...scene.environmentVariables].sort(compareNames),
  deviceTemplates: [...scene.deviceTemplates].sort(compareNames)
})

export const useBoardModelComparison = (
  displayedScene: () => BoardSemanticSnapshot,
  readComparison: () => Promise<BoardModelComparison>,
  hasHydratedScene: () => boolean
) => {
  const fingerprints = ref<BoardModelFingerprints | null>(null)
  const loading = ref(false)
  const modelSource = computed(() => serializeModelSource(displayedScene()))
  const adoptingSnapshot = ref(false)
  let confirmed: { source: string; fingerprints: BoardModelFingerprints } | null = null
  let requestEpoch = 0
  let disposed = false

  const invalidate = () => {
    requestEpoch += 1
    fingerprints.value = null
    loading.value = false
  }

  const refresh = async (): Promise<BoardModelFingerprints | null> => {
    if (disposed) return null
    const owner = ++requestEpoch
    confirmed = null
    fingerprints.value = null
    loading.value = true
    try {
      const comparison = await readComparison()
      if (owner !== requestEpoch || !hasHydratedScene()) return null
      const source = modelSource.value
      if (serializeModelSource(comparison.scene) !== source) return null
      confirmed = { source, fingerprints: comparison.fingerprints }
      fingerprints.value = comparison.fingerprints
      return comparison.fingerprints
    } catch {
      return null
    } finally {
      if (owner === requestEpoch) loading.value = false
    }
  }

  // Ordinary mutations still revoke evidence synchronously, including changes reverted in one tick.
  // A complete snapshot is a single adoption, so do not compare its intermediate collections.
  watch(() => adoptingSnapshot.value ? null : modelSource.value, (source, previous) => {
    if (source === null || previous === null) return
    confirmed = null
    invalidate()
    const owner = requestEpoch
    void nextTick(() => {
      if (owner === requestEpoch) void refresh()
    })
  }, { flush: 'sync' })

  const adoptSnapshot = (commit: () => void) => {
    invalidate()
    adoptingSnapshot.value = true
    try {
      commit()
    } finally {
      adoptingSnapshot.value = false
    }
    if (!hasHydratedScene()) return
    if (confirmed?.source === modelSource.value) {
      fingerprints.value = confirmed.fingerprints
    } else {
      void refresh()
    }
  }

  onScopeDispose(() => {
    disposed = true
    invalidate()
  })

  return { fingerprints: readonly(fingerprints), loading: readonly(loading), refresh, invalidate, adoptSnapshot }
}
