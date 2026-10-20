// A side preference cannot fit a wide help card on a narrow screen. Allow both axes to move.
export const tooltipPopperOptions = {
  modifiers: [
    { name: 'flip', options: { fallbackPlacements: ['top', 'bottom', 'right', 'left'] } },
    { name: 'preventOverflow', options: { padding: 8, altAxis: true, tether: false } }
  ]
}
