import { onScopeDispose, readonly, ref, type Ref } from 'vue'

export function usePlaybackCursor(totalStates: Readonly<Ref<number>>) {
  const selectedStateIndex = ref(0)
  const isPlaying = ref(false)
  let timer: ReturnType<typeof setInterval> | null = null

  const stop = () => {
    isPlaying.value = false
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  const seek = (index: number) => {
    stop()
    const step = Number.isFinite(index) ? Math.trunc(index) : 0
    selectedStateIndex.value = Math.min(Math.max(step, 0), Math.max(totalStates.value - 1, 0))
  }

  const toggle = () => {
    if (isPlaying.value) {
      stop()
      return
    }
    const lastIndex = totalStates.value - 1
    if (lastIndex < 1) return
    if (selectedStateIndex.value >= lastIndex) selectedStateIndex.value = 0

    isPlaying.value = true
    timer = setInterval(() => {
      selectedStateIndex.value++
      if (selectedStateIndex.value === lastIndex) stop()
    }, 1500)
  }

  onScopeDispose(stop)
  return { selectedStateIndex: readonly(selectedStateIndex), isPlaying: readonly(isPlaying), seek, stop, toggle }
}
