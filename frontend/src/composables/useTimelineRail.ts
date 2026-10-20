import { type Ref, nextTick } from 'vue'

/**
 * Timeline rail interaction logic: pointer scrubbing, keyboard navigation, and button scrolling.
 *
 * This composable extracts the shared behavior between the counterexample trace rail (Board.vue) and
 * the simulation timeline rail (SimulationTimeline.vue). Both rails map a horizontal x-axis to a
 * discrete state index, support pointer capture for drag-scrubbing, roving tabindex for keyboard
 * navigation, and scroll-into-view for button reveal.
 *
 * The visual presentation (rail height, button size, colors, violation markers) remains in each
 * component's template — this composable owns only the *interaction algorithms*, not the styling.
 *
 * @param options.totalStates - Reactive count of states in the sequence
 * @param options.selectedStateIndex - Reactive currently-selected state index
 * @param options.onSelectState - Callback to invoke when the user selects a new state (via pointer, keyboard, or programmatic call)
 * @param options.testIdPrefix - The `data-testid` prefix for querying rail elements (e.g., 'trace-timeline' or 'simulation-timeline')
 *
 * @returns Rail interaction handlers and utilities
 */
export function useTimelineRail(options: {
  totalStates: Ref<number>
  selectedStateIndex: Ref<number>
  onSelectState: (index: number, focus?: boolean) => void
  testIdPrefix: string
  visibleRange?: Ref<{ start: number; end: number }>
}) {
  const { totalStates, selectedStateIndex, onSelectState, testIdPrefix } = options

  /**
   * Which state a horizontal position on the rail addresses.
   *
   * CSS reserves half a touch target at each end; marker centres and the progress line use that
   * same inset. Read the resolved padding so pointer seeking agrees with the rendered rail.
   */
  const stateIndexAtClientX = (track: HTMLElement, clientX: number): number => {
    const rect = track.getBoundingClientRect()
    const inset = Number.parseFloat(getComputedStyle(track).paddingLeft) || 0
    const trackLeft = rect.left + inset
    const trackWidth = Math.max(1, rect.width - inset * 2)
    const ratio = Math.min(1, Math.max(0, (clientX - trackLeft) / trackWidth))
    const first = options.visibleRange?.value.start ?? 0
    const last = options.visibleRange?.value.end ?? totalStates.value - 1
    return first + Math.round(ratio * (last - first))
  }

  /**
   * Scroll the state button at the given index into view, optionally focusing it.
   *
   * Called after keyboard navigation or the end of a pointer scrub, so the newly-selected button
   * is visible in the rail's horizontal scroll region.
   */
  const revealStateButton = (index: number, focus: boolean) => {
    void nextTick(() => {
      const button = document.querySelector<HTMLButtonElement>(
        `[data-testid="${testIdPrefix}-state-${index}"]`
      )
      if (!button) return
      const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true
      button.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth', block: 'nearest', inline: 'center' })
      if (focus) {
        button.focus()
      }
    })
  }

  /**
   * Keyboard navigation for roving tabindex pattern.
   *
   * Arrow keys move selection one step in the corresponding direction (both axes map to the
   * timeline: left/down = previous, right/up = next). Home and End jump to the first and last
   * states. The newly-selected button scrolls into view and receives focus.
   */
  const handleStateKeydown = (event: KeyboardEvent, index: number) => {
    const keyToIndex: Record<string, number> = {
      ArrowLeft: index - 1,
      ArrowDown: index - 1,
      ArrowRight: index + 1,
      ArrowUp: index + 1,
      Home: 0,
      End: totalStates.value - 1
    }
    if (!(event.key in keyToIndex)) return
    event.preventDefault()
    const lastIndex = Math.max(totalStates.value - 1, 0)
    const nextIndex = Math.min(Math.max(keyToIndex[event.key], 0), lastIndex)
    onSelectState(nextIndex)
    revealStateButton(nextIndex, true)
  }

  /**
   * The rail is the one scrub control: press to seek, drag to scrub, arrow keys to step.
   *
   * It used to seek on press only, with a separate `<input type="range">` two rows above supplying
   * the drag. Both were full-width horizontal controls mapping x to the same state index, so the
   * overlay read as two timelines for one sequence — and the slider was the weaker of the two,
   * because it cannot show where the violation sits. Capturing the pointer here gives the rail the
   * one capability the slider had, which is what made deleting it a simplification rather than a loss.
   *
   * Pointer capture matters: without it, dragging past the rail's edge (routine, since the rail
   * scrolls horizontally on a long trace) silently drops the gesture mid-scrub.
   *
   * A press that lands on a step button is that button's click; only the track itself scrubs.
   */
  const scrubStateFromPointer = (event: PointerEvent) => {
    if (totalStates.value <= 1) return
    // A press that lands on a step button is that button's click; only the track scrubs.
    if (event.target instanceof Element && event.target.closest('button')) return
    const track = event.currentTarget as HTMLElement
    const seek = (clientX: number) => {
      const nextIndex = stateIndexAtClientX(track, clientX)
      if (nextIndex !== selectedStateIndex.value) {
        onSelectState(nextIndex)
      }
    }
    seek(event.clientX)
    track.setPointerCapture(event.pointerId)
    const onMove = (move: PointerEvent) => seek(move.clientX)
    const onRelease = (release: PointerEvent) => {
      track.removeEventListener('pointermove', onMove)
      track.removeEventListener('pointerup', onRelease)
      track.removeEventListener('pointercancel', onRelease)
      if (track.hasPointerCapture(release.pointerId)) {
        track.releasePointerCapture(release.pointerId)
      }
      revealStateButton(selectedStateIndex.value, true)
    }
    track.addEventListener('pointermove', onMove)
    track.addEventListener('pointerup', onRelease)
    track.addEventListener('pointercancel', onRelease)
  }

  return {
    scrubStateFromPointer,
    handleStateKeydown,
    revealStateButton
  }
}
