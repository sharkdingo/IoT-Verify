import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Every enabled button on the board shows a pointer cursor, from one rule.
 *
 * The mechanism: `<button>` has no default pointer cursor in any browser, so every control must declare one.
 * (This comment used to say "Tailwind's preflight resets `button { cursor: default }`". That is **false** for the
 * installed version — `node_modules/tailwindcss/preflight.css` v4.1.18 contains exactly one occurrence of the word
 * `cursor`, a comment on line 379. Tailwind v4 *removed* v3's added `button { cursor: pointer }`; it does not add a
 * `default`. Same symptom, wrong cause, and a wrong cause sends the next reader looking for a reset that isn't there.)
 * Measured on a real board: **77 of 182 enabled buttons rendered `cursor: default`** — including the entire Control
 * Center tab strip and the panel collapse toggles.
 *
 * Every one of them had hover feedback, so none was undiscoverable. What was wrong is subtler and still worth fixing:
 * the cursor contradicted the appearance. For a pointer user the cursor is the fastest signal that something is
 * clickable, and on a canvas tool that matters more than on a page — a reader assumes most of the surface is inert, so
 * the affordance has to be unambiguous.
 *
 * The fix is one rule in `board.css` rather than 77 component edits, because per-component opt-in is exactly what
 * missed 77 buttons. `:not(:disabled)` is the load-bearing part: a disabled control must keep `default`, or the cursor
 * promises an interaction that will not happen.
 *
 * Also verified in the same pass, and left alone: **zero** inert elements carry `cursor: pointer`. A click that does
 * nothing is indistinguishable from a failed operation, and nothing in the board makes that promise.
 */

const boardCss = () => readFileSync(join(__dirname, '../board.css'), 'utf8')

describe('button cursor affordance', () => {
  it('sets a pointer cursor for enabled board buttons in one place', () => {
    const css = boardCss()
    // The rule must exist and must be scoped to the board, not global — the landing page has its own styling.
    expect(css, 'a board-wide button cursor rule should exist')
      .toMatch(/\.iot-board button:not\(:disabled\)/)
    /*
     * Read to the rule's closing brace rather than a fixed byte count. A `slice(at, at + 220)` window
     * broke the moment the selector list grew to cover the replay bars: the declaration was still
     * there, just past 220 characters, so a correct change reddened a passing test for no reason.
     */
    const at = css.indexOf('.iot-board button:not(:disabled)')
    const block = css.slice(at, css.indexOf('}', at) + 1)
    expect(block, 'the rule should set a pointer cursor').toMatch(/cursor:\s*pointer/)
  })

  it('excludes disabled controls, so the cursor never promises a dead interaction', () => {
    const css = boardCss()
    const at = css.indexOf('.iot-board button:not(:disabled)')
    expect(at, 'the rule should exist before checking its selector').toBeGreaterThan(-1)
    const selector = css.slice(at, css.indexOf('{', at))
    // Dropping `:not(:disabled)` is the tempting simplification, and it would give every greyed-out control a pointer.
    expect(selector, 'the selector must exclude disabled buttons').toMatch(/:not\(:disabled\)/)
    // The same care for ARIA-disabled role buttons, which have no `:disabled` state to match.
    expect(selector, 'role=button elements should exclude the aria-disabled case')
      .toMatch(/aria-disabled/)
  })

  it('covers the replay bars, which are siblings of the board rather than descendants', () => {
    /*
     * The tests above assert the rule's *shape*; this one asserts its *reach*, which is the half that
     * was missing. `.iot-board button` cannot match the two timeline hosts: they are `position: fixed`
     * siblings of `.iot-board` under `.app-main`, so a rule scoped to the board descends past them.
     *
     * Measured live on the counterexample replay bar: **10 of 12 enabled controls rendered
     * `cursor: default`** — play, close, run details, previous/next state, both state chips, and both
     * help buttons. That is the user-visible report "the buttons are clickable but the cursor does not
     * change", and every assertion above passed the whole time, because each one only looked at the
     * `.iot-board` rule it already knew about.
     *
     * This is the same sibling-node structure that stops `--board-floating-gap` resolving inside these
     * hosts (documented in `board.css` and in `frontend/CLAUDE.md`) — the second time one DOM fact has
     * produced two unrelated-looking bugs. Anything added for `.iot-board` needs asking of the hosts too.
     */
    const css = boardCss()
    const terminal = readFileSync(join(__dirname, '../../components/PlaybackTerminal.vue'), 'utf8')

    // The host class must really be outside the board, or this test is guarding a non-problem.
    expect(terminal, 'the replay bars render through board-timeline-host')
      .toContain('board-timeline-host')

    const pointerSelectors = [...css.matchAll(/([^{}]+)\{([^}]*)\}/g)]
      .filter(([, , body]) => /cursor:\s*pointer/.test(body))
      .map(([, selector]) => selector)
      .join(',')

    expect(pointerSelectors, 'enabled buttons in a replay bar need a pointer cursor')
      .toMatch(/\.board-timeline-host button:not\(:disabled\)/)
    expect(pointerSelectors, 'and so do its role=button controls')
      .toMatch(/\.board-timeline-host \[role='button'\]:not\(\[aria-disabled='true'\]\)/)
    // Disabled transport controls are routine here — the bar disables play at the last state — so the
    // exclusion matters more on this surface than on the board.
    expect(pointerSelectors, 'a disabled transport control must keep its default cursor')
      .toContain(":not(:disabled)")
  })

  it('lets a draggable device node keep grab, and does not promise drag on a locked one', () => {
    /*
     * The clickability rule and the node's drag affordance compete, and the rule won — inverting both.
     *
     * A device node is `<div class="device-node" role="button" :aria-disabled="…">`, so
     * `.iot-board [role='button']:not([aria-disabled='true'])` at specificity (0,3,0) outranked
     * `.iot-board .device-node { cursor: grab }` at (0,2,0). Measured with an injected probe:
     * **unlocked node → `pointer`, locked node → `grab`** — exactly backwards. The one draggable object
     * on the canvas invited a click, and a node locked by read-only playback invited a drag that cannot
     * happen. No `@layer` exists in these stylesheets, so source order does not rescue it.
     *
     * Fixed by raising the resting state to the same specificity and stating both states, rather than
     * weakening the clickability rule — `Board.vue` has a `<summary role="button">` that depends on it.
     */
    /*
     * Comments are stripped before parsing, and that is load-bearing rather than tidiness. A CSS comment
     * contains no braces, so a naive `([^{}]+)\{` treats the whole preceding comment as the selector —
     * and the comment above this very rule *quotes* the selector it explains. The first version of this
     * test matched the comment instead of the rule, so reverting the fix left it green: a test that
     * documented the defect and could not detect it.
     */
    const css = boardCss().replace(/\/\*[\s\S]*?\*\//g, '')
    const rules = [...css.matchAll(/([^{}]+)\{([^}]*)\}/g)]
      .map(([, selector, body]) => ({ selector: selector.trim(), body }))

    const grab = rules.find(r => /cursor:\s*grab\s*;/.test(r.body) && r.selector.includes('device-node'))
    expect(grab, 'a resting grab cursor for the device node should exist').toBeDefined()
    expect(grab!.selector, 'it must outrank the [role=button] clickability rule, so it needs the attribute')
      .toContain("[role='button']")
    expect(grab!.selector, 'and must apply only while the node is actually draggable')
      .toContain(":not([aria-disabled='true'])")

    const locked = rules.find(r =>
      r.selector.includes('device-node') && r.selector.includes("[aria-disabled='true']")
      && !r.selector.includes(':not('))
    expect(locked, 'a locked node needs its own cursor, or it inherits a drag promise').toBeDefined()
    expect(locked!.body, 'a locked node must not show grab').not.toMatch(/cursor:\s*grab/)
  })

  it('covers every button inside a dialog, not only the ones built from dialog primitives', () => {
    /*
     * `dialog.css` declares `cursor` on `.iot-dialog__close` and `.iot-dialog-btn`, which covers a
     * dialog assembled purely from those primitives and silently misses anything else inside one.
     * Measured on the verification result dialog: **2 of 13 enabled controls rendered
     * `cursor: default`** — the per-counterexample "Fix Rules" and "View", both bespoke inline-styled
     * buttons. A dialog overlay is also a sibling of `.iot-board`, so the board rule cannot reach it.
     *
     * Scoped to the overlay so the affordance is a property of "inside a dialog" rather than of one
     * class, which is what stopped these two from being covered in the first place.
     */
    const css = readFileSync(join(__dirname, '../dialog.css'), 'utf8')
    const pointerSelectors = [...css.matchAll(/([^{}]+)\{([^}]*)\}/g)]
      .filter(([, , body]) => /cursor:\s*pointer/.test(body))
      .map(([, selector]) => selector)
      .join(',')

    expect(pointerSelectors, 'any enabled button inside a dialog needs a pointer cursor')
      .toMatch(/\.iot-dialog-overlay button:not\(:disabled\)/)
    expect(pointerSelectors, 'and its role=button controls')
      .toMatch(/\.iot-dialog-overlay \[role='button'\]:not\(\[aria-disabled='true'\]\)/)
    // Disabled dialog actions are common (a blocked submit, a withheld Fix), and several already set
    // `cursor: not-allowed`; the exclusion is what lets that survive.
    expect(pointerSelectors, 'a disabled dialog action must not claim to be clickable')
      .toContain(':not(:disabled)')
  })

  it('does not hand a pointer cursor to non-interactive text', () => {
    // The inverse failure. A `cursor: pointer` on a bare span or paragraph invites a click that does nothing — and in
    // a verification tool a silent no-op is indistinguishable from a failed operation. Measured live: 0 such elements.
    // This rule keeps a blanket `* { cursor: pointer }`-style shortcut from ever being introduced.
    const css = boardCss()
    const offenders: string[] = []
    /*
     * The element selector may be scoped, and here it always is.
     *
     * The first version anchored on a bare element at the start of a line. `board.css` is board-scoped, so every
     * selector begins `.iot-board …` and that pattern could not match by construction — the check was
     * structurally incapable of failing, and the comment above recording "0 such elements" was measuring its own
     * blind spot. Verified by injecting `.iot-board p { cursor: pointer }`: the old form passed it, this one
     * reports it.
     *
     * Matching the selector's *last* simple component catches both `p {` and `.iot-board p {` while still
     * ignoring `.iot-board .some-button {`, which is a control and may legitimately have the cursor.
     */
    for (const rule of css.matchAll(/([^{}]+)\{([^}]*)\}/g)) {
      const [, selector, body] = rule
      if (!/cursor:\s*pointer/.test(body)) continue
      const hitsBareElement = selector
        .split(',')
        .map(part => part.trim().split(/[\s>+~]+/).pop() ?? '')
        .some(last => /^(\*|span|p|div|h[1-6]|li|td)$/.test(last))
      if (hitsBareElement) offenders.push(`board.css: ${selector.trim().slice(0, 70)}`)
    }
    expect(offenders, 'a pointer cursor on a bare text element promises an interaction that does not exist')
      .toEqual([])
  })
})
