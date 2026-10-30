/**
 * Board shell geometry that more than one component has to agree on.
 *
 * These are the numbers the canvas, the two side panels and the action dock each measure against. Every one
 * of them previously existed as two or more copies — a `3.5rem` in each panel's inline style, a `56` in
 * `Board.vue`'s fit math, and a CSS token declared separately — kept in step only by comments pointing at
 * each other. That is exactly the arrangement that let the collapsed-rail token drift to 48px while the
 * rails rendered at 56px, and the dock's rail width exist as four disagreeing values at once.
 */

/**
 * Width of either side panel while collapsed, in px.
 *
 * Both collapsed rails contain exactly one 44x44 Expand button — identical content and identical purpose,
 * so a difference between them has no reason and reads as accidental rather than composed. They were 64px
 * and 48px. 56px, not 48: a 44px target in a 48px rail leaves 2px of air, which crowds the tap target
 * against the canvas edge; 56 gives a symmetric 6px inset at both vertical edges of the stage.
 */
export const COLLAPSED_PANEL_RAIL_PX = 56

/** The same value as a CSS length, for the panels' inline `width` binding. */
export const COLLAPSED_PANEL_RAIL_CSS = `${COLLAPSED_PANEL_RAIL_PX}px`

/**
 * Width of the action dock's rail, in px, per dock mode.
 *
 * `compact` and `packed` equal the collapsed side rail on purpose: each holds one 44px control plus the
 * dock panel's 1px borders and 5px padding, i.e. the same box as the rails flanking the canvas. `expanded`
 * is that plus the label column.
 *
 * One owner matters here because three different consumers read it: the CSS custom property that paints the
 * dock, the reserved corridor the floating panels are inset by, and the width `getVisibleCanvasFrame`
 * subtracts when fitting content. When those disagreed, fit-to-content placed nodes under the rail.
 */
export const ACTION_DOCK_RAIL_PX = Object.freeze({
  expanded: 140,
  compact: COLLAPSED_PANEL_RAIL_PX,
  packed: COLLAPSED_PANEL_RAIL_PX
})

/**
 * The gap the fixed board overlays leave against the side panels.
 *
 * Declared here rather than only in `board.css` because the two timeline hosts are siblings of `.iot-board`, not
 * descendants, so they cannot see a custom property scoped to it. `boardShellStyle` injects this onto them; the
 * stylesheet keeps its own `--board-floating-gap` declaration for everything *inside* the board. Two readers, one
 * value; keep the injected and stylesheet declarations aligned.
 */
export const BOARD_FLOATING_GAP_CSS = 'clamp(0.75rem, 2vw, 1rem)'
