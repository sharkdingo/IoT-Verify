# Frontend UI conventions

Decision records governing the board's URL surface, user feedback, undo, dialogs, action emphasis,
colour roles, type scale, depth, CSS precedence, and replay. These are **rules, not descriptions** —
when code and this document disagree, one of them is a bug. Keep them short; they exist to stop the
same argument being re-litigated per PR, and each carries the measurement that settled it.

Verified against code on 2026-09-30. Source: `frontend/src/styles/`, `frontend/src/composables/`,
`frontend/src/utils/feedback.ts`, and the spec files each rule names.

## 1. What belongs in the URL

The board's URL answers exactly one question: **which artifact is the user looking at?**
Everything else is either server-persisted workspace state or transient component state.

### Deep-linked (in the URL)

| Param | Values | Meaning |
| :--- | :--- | :--- |
| `run` | `verification:<id>`, `simulation:<id>`, `exploration:<id>` | The run whose result surface is open |
| `trace` | `<id>` | Counterexample trace being replayed (only with `run=verification:<id>`) |
| `finding` | `<id>` | Exploration finding being replayed (only with `run=exploration:<id>`) |

Rationale: each is a **server-owned entity with a stable numeric id**, it changes what the
user is looking at, and "send me the counterexample you're seeing" is a real workflow. A
link stays meaningful for anyone with access to that run.

### Deliberately *not* in the URL

- **Panel layout** — `collapsed`, `width`, `activeSection` for both side panels, plus
  `canvasPan` / `canvasZoom`. These are already persisted **server-side per user** via
  `BoardLayoutDto` (`boardApi.saveLayout`). Putting them in the URL would create a second
  source of truth for the same value and make every drag write history.
- **Tool panel visibility** — verification/simulation/exploration/recommendation panels.
  These are *input forms*, not content. A shared link that reopens someone else's
  half-filled form is noise, and they are mutually exclusive already.
- **Dialog intent** — rename, delete confirmation, device details, rule builder, logout.
  Restoring a destructive confirmation from a URL is actively unsafe.
- **Transient run state** — in-flight progress, playback frame index, animation state.
  Frequent, meaningless to another user, and would flood history.
- **Anything sensitive or bulky** — never a token, never a serialized board.

### Rules

- **One authority per value.** The URL owns `run`/`trace`/`finding`. Component state mirrors
  it one-way (URL → state); state changes navigate rather than assigning locally.
- **Opening an artifact is `push`** (back should close it). Correcting or clearing a stale
  param is `replace` (no junk history entry).
- **Invalid, stale, or unauthorized params degrade to the plain board** with one
  page-level explanation — never a fabricated empty result, never a silent redirect loop.
- **Back/forward must restore the surface**, not just the address bar.
- **A deleted run must lose its deep link, and every run kind needs that wiring.** Closing a result
  surface comes in a pair — `close*` for an internal transition (which must not touch the URL) and
  `dismiss*` for a user-facing close (which clears it). A deletion is user-facing, so it takes the
  `dismiss*` half; using `close*` left `?run=exploration:<deleted id>` in the URL, where the sync
  watcher reloaded it and answered with the unusable-link banner.
  Out-of-band deletion is the harder half. The same-tab history-panel path is *unreachable* for all
  three kinds — the two result dialogs are `aria-modal="true"`, the simulation bar disables the panel
  button through `isModelPlaybackActive`, and every opener closes the panel — so the reachable paths
  are the assistant's delete tool and another tab, and both arrive as a history reload. The
  reconciliation therefore hangs off a **successful** reload (`reconcileOpenRunAgainstHistory`), never
  off the delete handler, and it dispatches per run kind so adding a kind means adding a sibling there
  rather than another call site. Exploration and simulation were both missing for exactly that reason,
  while `delete_fuzz_run` and `delete_simulation_trace` had shipped and emitted the same `run_history`
  signal. A failed reload leaves the lists stale and must not be read as a deletion; an *empty* list
  is also a still-loading or scoped-empty history, so only absence from a populated list counts.
  A dispatcher is only as good as its wiring, and dropping one call kills a whole kind while leaving
  its function, its wording and its own tests green — measured. So the spec enumerates the declared
  `reconcileOpen*AgainstHistory` functions and requires each to be dispatched, rather than listing the
  names it already knows.
  Exploration needs a third guard, because "absent from the list" only means deleted when the list is
  the whole list. `/fuzz/runs` is the only paginated run history (`page`/`size`, default 25, ordered
  `createdAt DESC`, against a 100-run stored quota) and a reload replaces the list with page 0 — so a
  run opened from a later page is legitimately missing, and reporting that as a deletion would
  fabricate a cause exactly like the 404 above. `hasMore` is the check; verification and simulation
  return their full lists from the controller, which is why only exploration carries it.
  Simulation needs one guard the others do not: their ids come from refs cleared on close, so "has a
  run id" implies "is on screen", whereas `lastSimulationResult` deliberately outlives every surface
  (staleness belongs to the run, not the dialog) — reading the id off it alone announces that a panel
  closed while nothing is showing. And each kind explains itself in its own words: telling an
  exploration user to "re-run to get a conclusion" describes bounded search, which yields candidate
  findings, as formal verification, and a simulation is neither — what ends is a *replay* of a
  trajectory, so its copy says playback ended rather than that a panel closed.
  **A run kind has more than one surface, and a dialog id cannot see the other one.** Verification and
  exploration each reconciled only their result dialog, but opening a counterexample calls
  `closeResultDialog` and opening a finding calls `closeFuzzingResult` — so the dialog ref is null for
  the entire replay, and the reconciliation returned early on the surface most expensive to get wrong:
  an animating trace still offering Run details and the SMV download for a record the server had
  dropped. Read the run id from the replaying evidence as a fallback (`verificationTaskId` on a
  `PersistedTrace`, `fuzzTaskId` on a finding — both absent on immediate evidence, which has no run to
  reconcile against), and tear down the surface that is actually up: `dismiss*` closes a dialog that a
  replay has already closed, so a replaying kind needs the trace teardown instead. Wording follows the
  surface too, not the run kind — the dialog strings say a panel closed, which is false when what ended
  was playback. Decide *which* surface from `activePlaybackKind`, never from the evidence ref: neither ref
  implies its evidence is on screen (`currentTrace` falls back to the open dialog's traces, and
  `activeFuzzingFinding` survives a simulation replay starting over it), so reading them alone announces
  that playback ended to a user who was reading a dialog. Simulation is the exception that proves the
  shape: its id already comes from a ref that outlives every surface, which is why it carries a visibility
  guard rather than a fallback.

## 2. Feedback semantics

One intent, one mechanism. Pick by **how long the information stays true** and **whether the
user must act**.

| Situation | Mechanism | Why |
| :--- | :--- | :--- |
| Operation succeeded, result visible on screen | **nothing** | The updated UI *is* the feedback. A toast repeating it is noise. |
| Assistant changed shared state | `notifyInfo` after authoritative refresh | The result may be visible, but its AI origin is not; the receipt makes agency perceptible without blocking use. |
| Operation succeeded, result not visible | `notifySuccess` (toast) | Short, non-blocking confirmation. |
| Transient failure, retryable, non-blocking | `notifyError` (toast) | |
| Blocked by current state (e.g. playback open) | `notifyBlocked` (toast) | Explains *why* and what to close. |
| Field-level validation | **inline, next to the field** + `aria-describedby` | Never a toast: the user needs it while fixing the field. |
| Page/section failed to load; critical state unknown | **persistent banner** with retry | Must survive until resolved; a toast disappears before the user can act. |
| Destructive action needing agreement | `confirmDestructive` | Uniform title/body/danger button; resolves `false` on cancel. |
| Non-destructive decision (proceed past a warning, apply a suggestion) | `confirmChoice` | Same shape, accent button. Using `confirmDestructive` here is what made the red button meaningless. |
| Diagnostic the user cannot act on | `acknowledge` | Alert with an optional `tone`; may carry a VNode for per-field detail. |
| Permanent account deletion | `AccountDeleteDialog` | Needs password + typed confirmation, so it is a bespoke form, not a message box. |
| Background async task | task indicator / run history | Not a toast per state change. |

### Rules

- **Never `window.alert` / `confirm` / `prompt`.**
- **One mechanism per outcome.** Never a toast *and* a banner *and* an inline error for the
  same failure.
- **Toasts are for what the UI cannot already show.** Before adding one, ask what changed
  on screen. Direct device creation/rename, rule/spec creation and successful sign-in/registration
  use the updated canvas, inspector or destination page as their confirmation. Keep receipts that
  add context, such as template-reset impact counts or the signed evidence used to apply a fix.
- **Assistant agency is explicit.** Show one localized receipt only after the corresponding
  authoritative refresh succeeds. Preview, read-only, rejected, unchanged, and unaccepted
  cancellation results show no action receipt. Persisted run history labels assistant-originated
  work and unknown legacy provenance; direct user work needs no origin badge.
- Identical error toasts are grouped with a repeat count; concurrent refresh paths must not
  stack the same message over the working surface.
- **A toast sits below the header and never takes a click.** Element Plus's 16px default put
  toasts inside the fixed board nav, over undo and redo, right when the user reached for them.
  A fixed or top header therefore carries `data-toast-anchor`, and `feedback.ts` measures it
  when each toast opens. It is measured in JS because a toast is teleported to `<body>` and
  cannot read `--board-nav-height` (§7 has the same trap for fixed overlays). Under an open modal
  the toast keeps the viewport top. Close the submitting dialog before notifying; for a controlled
  `visible` prop, wait for the parent update with `nextTick`. Route-changing receipts follow the
  completed navigation. Reconciled but unconfirmed mutations retain warning semantics after closure.
  The whole toast subtree is `pointer-events: none`, so it no
  longer pauses on hover. A message the user must act on is a banner or dialog, not a toast.
  Pinned by `feedback.spec.ts`, `toastPointerTransparency.spec.ts`, `Landing.spec.ts`, the template/fix
  component specs and `board-edit-undo.spec.ts`.
- **Persistent board notices share one vertical stack below the nav.** Snapshot loading/failure
  and an unavailable shared run can coexist; neither may cover the other's explanation, Retry or
  dismiss control. The stack is a toast anchor and lets pointer events through except on its buttons.
  A retry changes the persistent state itself, without a second toast for the same result. Geometry
  and keyboard recovery are covered by `ui-contracts.spec.ts` at desktop and narrow widths.
- Same semantic ⇒ same component, wording shape, icon, button order, focus behaviour.
- Error text keeps what helps the user act; **no stack traces, no internal identifiers.**
  Raw backend diagnostics belong in Technical Details or the console.
- Prefer stable reason codes over free-text; fall back through `utils/userMessage.ts`.
- **A disabled submit needs a visible reason.** Derive both from one computed value (e.g.
  `specConditionBlockedReason`) so the button and the message can never disagree, and point
  the control at it with `aria-describedby`. If the form already blocks the action, the
  handler must not also toast — that is the duplicate-feedback rule.
- Never surface a client-side `error.message`. A transport or programming failure
  ("connect ECONNREFUSED …", a `TypeError`) is console diagnostics; show the backend's
  `message` only when `utils/userMessage.ts` says it matches the active locale, else a
  translated fallback.
- `confirmDestructive` resolves `false` on cancel and dismissal — cancelling is an ordinary
  outcome, never an exception a call site has to catch. Use `dismissOpenConfirmation()` when
  the surface that raised a confirmation goes away underneath it.

### A cue is not a selection

"Show me where that is" and "this is the item you are working on" are different states, and the board
paints them differently: a cue is a bloom plus motion on the canvas, a selection is a static ring on the
inspector row. Keep them apart.

- **A cue owns its own lifetime.** `views/board/focusHighlight.ts` retires it on a timer. The three focus
  ids used to be independent refs cleared only by whoever remembered, and five exits did not — clicking
  empty canvas, Escape, closing the device dialog, focusing another device by a different path, and simply
  moving on. One device kept a 28px bloom and an *infinitely* pulsing ring while its neighbours had none,
  which users read as a property of that device ("why do some device instances glow?"). Adding a clear per
  exit makes correctness depend on enumerating future exits; this round proved that enumeration fails.
- **Cue motion is finite and ends before the cue does.** Two pulses, not `infinite`. Perpetual motion on a
  canvas the user works in stops reading as "look here" and starts reading as a status.
- **A cue differs from a semantic mark in *form*, not intensity.** A dashed accent outline means "the board
  is pointing at this"; a solid ring or a bloom means something about the thing itself. The focus cue was a
  4px accent ring plus a 28px bloom — within 2% of `.trace-changed`, which means "this device's state changed
  at this step" — same hue, same shape, both animating a scaling ring, and they co-occur when you focus a
  device during playback. §5's "state never depends on colour alone" is doubly violated when the shape does
  not differ either.
- **One writer.** The controller is the only thing that assigns those refs, and the three targets are
  mutually exclusive by construction rather than by three hand-written clears in each of three setters.
- Deleting the focused item is the one case that must not wait for the timer — the cue would address an id
  that no longer exists. That check reuses `reconcileBoardFocus`, so "does this still exist" has one owner.

## 3. Undo, and what it is not

These are different operations with different controls. Conflating them is how a "go back" button
ends up destroying work.

| Intent | Mechanism | Scope |
| :--- | :--- | :--- |
| Reverse a persisted board edit | **Undo** (`Ctrl/Meta+Z`), server journal | Device create/update/rename/delete, Environment Pool edits, rule/spec create/delete, rule reorder, automatic-fix apply |
| Re-apply an undone edit | **Redo** (`Ctrl/Meta+Shift+Z`, `Ctrl+Y`) | Same |
| Stop work that is still running | **Cancel / Stop** on the task | Verification, simulation, exploration, fix search |
| Discard a finished run's record | **Delete** in run history | Completed runs (not reversible) |
| Hide a surface | dialog **close/dismiss** | No data change |
| Move between viewed artifacts | browser **Back/Forward**, `?run=` deep link | No data change |
| Fix a typo in a field | the field's **native** undo | Never intercepted |

### Rules

- **The server journal is the authority.** The client keeps no snapshot stack and never inverts an
  edit locally; every undo response carries the authoritative collections and the remaining
  availability, and a fresh page reads availability from the server.
- **The journal entry commits with the edit it describes.** A journal write that could land without
  its edit (or vice versa) would offer an undo to a state that never existed.
- **Undo never overwrites newer work.** If the affected record changed after the edit was recorded,
  the undo is refused with a conflict and the board is left untouched.
- **A failed undo response is not proof that no write committed.** A non-`409` transport or response-
  contract failure is an unconfirmed outcome. Reconcile the complete authoritative snapshot through
  the board-mutation queue before reporting it; if refresh also fails, say the outcome is unknown
  and do not invite an immediate retry. A `409` proves this request wrote nothing but still requires
  refresh because the conflict proves the local board may be stale.
- **A new edit invalidates redo.** Redoing an abandoned branch would silently overwrite the new
  edit, so the branch is discarded rather than left as a trap.
- **Nothing to undo is a normal outcome**, not an error, which makes repeated presses idempotent.
  A successful undo must make redo available, a successful redo must make undo available, and a
  `NOTHING_TO_APPLY` response must mark the requested direction unavailable; reject a response that
  contradicts these invariants instead of rendering impossible button state.
- **Confirmed scene replacement/clear is a history boundary.** It can also replace template
  snapshots, so an inverse over only the four visible collections would leave hidden catalog
  effects behind. Template deletion and bundled-template reset are boundaries for the mirror reason:
  a journal device entry needs its type manifest to interpret its own attributes and values.
  Automatic-fix apply is different: it owns one ordered rule-set transition and is
  reversible as one user action.
- **A boundary confirmation must say why the history goes, not only that it does.** Discarding
  undo/redo reads as an unexplained side effect of "clear the scene" otherwise, which is how the
  mechanism gets reported as a bug. Each notice names the count *and* the reason the remaining
  entries could not be replayed — nothing left to return to for the scene boundaries, no manifest to
  interpret the snapshots for the template ones.
- **Async runs are not undoable.** Cancel, stop, and delete-result are separate, and none of them
  is spelled "undo".
- **Never intercept a keystroke in a text field, a `contenteditable` region, or during an IME
  composition.** A user fixing a typo must not resurrect a deleted rule.
- **Undoability follows the user's unit of work, not the storage shape.** Rule reorder changes no
  individual record — only their order — but it is reached through an explicit up/down button, so
  one press is one edit the user expects `Ctrl+Z` to take back. Its journal entry stores the
  previous *ordering* (`RuleOrderSnapshot`) instead of a record snapshot. Device deletion spans a
  device, cascaded rules/specifications, their positions, and Environment Pool changes, but the user
  confirmed one deletion, so it is one compound journal entry. Conversely, a change the
  user never performed as a discrete action is not a candidate just because it is easy to invert.
- **Every ordinary targeted semantic mutation goes through one owner.** `board/semanticCommit.ts` applies the
  authoritative collections and then everything derived from them — canvas edges, dangling focus,
  undo availability, verdict staleness — in a fixed order. Call sites pass the result; they do not
  hand-assemble the follow-ups. That scattering is exactly what let reorder skip undo availability
  and let undo skip the canvas edges, each masked by a later refresh that happened to repair it.
  Reaching the right state "because some later refresh runs" is correct by accident. A server-
  confirmed semantic no-op still reconciles its authoritative collection and availability but does
  not stale a valid verdict. Partial and full refreshes reuse the same focus reconciliation rule, so
  an item removed by another tab or by a response-lost mutation cannot remain selected.
- **Whoever changes the journal must re-read it.** The journal is the authority for what is
  reversible, so a path that changes it without carrying availability in its own response has to
  re-read it. Two mechanisms cover this: scene replace/clear calls
  `notifyUndoJournalCleared()`, while reversible mutations either carry availability in their
  response or explicitly reload it; any wholesale semantic reload
  (`refreshBoardSnapshot`) re-reads availability explicitly at the end. Note that a board
  invalidation does **not** cover the publishing tab: `BroadcastChannel` does not deliver a message
  to the context that posted it, and `publishBoardInvalidation` only calls `postMessage` — other
  tabs reload, the origin tab relies on its own explicit re-read. The assistant relies on that
  explicit path: its device/rule/spec tools use the same journal-recording service methods the UI
  does, so an assistant-created device or rule is exactly as reversible as a user-created one.
  Verified end-to-end
  against the real model in `e2e/live-ai-no-mock.spec.ts`.
- **Clearing unusable history is explicit.** After an undo/redo conflict, the UI may offer to clear
  the journal only after explaining that the current Board stays unchanged and all undo/redo entries
  will be removed. It first reads `/board/edits/clear-preview`; `POST /board/edits/clear` carries that
  opaque impact token, so another tab's edit/undo/redo during confirmation causes `409` instead of
  deleting newly changed history. A successful clear returns empty collections and both availability
  flags false; it is not itself an undoable Board edit.
- **The assistant cannot silently delete.** Destructive AI tool actions are gated behind an explicit
  two-step confirmation token (`AiDestructiveActionGuard`), so a single turn can create a rule but
  not remove one. Do not write tests or features that assume otherwise.
- **Availability is a query.** `GET /board/edits/availability` returns the two booleans and empty
  node, Environment Pool, rule, and specification collections; it must never be treated as an
  authoritative board update. Because this read runs outside the mutation queue, an authoritative
  mutation response invalidates every read already in flight, and only the latest-started concurrent
  read may update the affordance.

## 4. Dialogs are one surface with three sizes and four tones

Every modal is composed from [`src/styles/dialog.css`](../../frontend/src/styles/dialog.css). Nothing
builds a dialog shell locally.

The layer exists because they did. Measured across the 13 hand-rolled modals it replaced: the overlay
tint split four ways (`bg-black/60`, `bg-slate-950/20`, `bg-slate-900/60`, plus three CSS literals), the
card radius three ways against an `--iot-radius-surface` scale that already existed, the width eight ways
(380px, `w-96`, 650, 760, 800, `max-w-lg`, `max-w-6xl`, 92vw), the footer four ways (trailing, centred,
space-between, absent), and the confirm button five heights. Logout painted itself with a hardcoded navy
gradient and a pulsing red circle; the scene-clear confirmation was a stock MessageBox. A user comparing
those two reported that the product did not look like one project — which is the only symptom this class
of drift ever produces, and it is invisible one dialog at a time.

### The vocabulary

`.iot-dialog-overlay` (`--nested`, `--session`) · `.iot-dialog` + one size (`--sm` decisions, `--md`
forms, `--lg` results, `--xl` the rule builder) + optional tone (`--danger`/`--warning`/`--success`/`--info`)
· `__header` / `__icon` / `__heading` / `__title` / `__subtitle` / `__close` · `__body` · `__consequence`
· `__footer` / `__footer-aside` · `.iot-dialog-btn` (`--primary`/`--danger`/`--ghost`/`--quiet`) and
`__spinner`. Shared `<Transition name="iot-dialog">`.

### Rules

- **Tone belongs to the situation; the confirm button belongs to the action.** Set one tone modifier on
  the card and the header tile follows it. Do *not* also paint the confirm button in that tone — that gave
  the product a warning-gradient confirm, a red confirm and a blue confirm for the same "do the thing I
  came for" role. `--danger` is the sole exception, because a destructive answer must look unlike an
  ordinary one at the instant of clicking.
- **A tone is not a volume knob.** Template deletion wore a full-bleed red banner with a 64px icon for a
  reversible catalog edit, shouting louder than permanent account deletion. Reserve intensity for
  consequence, and express it through the tile, not the whole surface.
- **`__body` is the only part that scrolls.** The card is a bounded flex column and `__footer` is
  `flex: none`, so actions cannot be pushed below a short viewport. Putting the scroll on the card is how
  a footer ends up unreachable.
- **Primary action last, always.** A confirm button that changes position between surfaces is the single
  most legible symptom of unrelated dialogs.
- **Sizes are a scale, not a per-dialog guess.** If a dialog needs a width between two steps, it almost
  certainly needs different content.
- **No motion on a blocking prompt.** The logout dialog animated a pulsing red halo, which reads as
  urgency that logging out does not have. The entrance is a 0.18s rise and it is disabled under
  `prefers-reduced-motion`.
- **A dialog is centred at every width.** Under 640px it releases its width cap, tightens padding and
  raises actions to 44px touch targets — it does not dock to the bottom. A bottom sheet was tried and
  reverted: Element Plus MessageBox is centred by its own overlay and cannot dock, so docking the
  hand-rolled ones put the logout prompt on the bottom edge while the scene-clear confirmation floated
  mid-screen in the same app at the same width. Breakpoints split at one value (639.98/640) per §9.
- **A dialog surface is opaque.** Use `--surface-elevated`, never `--iot-color-card-bg`: that token is
  `rgba(…, 0.3)` for a card sitting *on a panel* that supplies the opacity. A dialog has only the blurred
  board behind it, and at 30% the board's own cards showed through the account-deletion form's password
  field. Blur belongs to the overlay; the card is a surface.
- **Address a dialog's controls by `data-testid`, never by an appearance class.** Several specs pinned
  `button.danger` and `.template-reset-dialog__btn.secondary`; they broke on a pure restyle while
  asserting nothing about behaviour.
- **Migrating a dialog means deleting its local rules *and* the classes that named them.** Three orphan
  classes survived this migration — a rule removed, the `class="…"` entry left behind, implying to the next
  reader that some stylesheet still cares. A class with no rule is fine only when a test or E2E spec
  addresses it as a handle.
- Element Plus MessageBox cannot carry these classes, so `base.css` sizes its buttons from the same
  `--dialog-action-height` token rather than repeating the literal — two plausible literals with no link
  between them is how five button heights coexisted here before.
  `dialogSurfaceConsistency.spec.ts` fails if a modal skips the layer (counted per dialog, not per file), if
  a surface goes translucent, if the narrow block re-docks, if the token is bypassed, or if an orphan class
  is left on markup.

## 5. Action emphasis

A user needs to know which control is *the* next step. If several controls claim that role, none of
them holds it.

This was measured, not assumed: on an empty Board, 14 of 38 visible controls carried primary-action
weight, 8 of them in the action dock, because every dock button was an opaque 700-weight fill in its
own hue. The dock already grouped run actions and AI suggestions separately in markup, but both
groups were painted identically, so the grouping was invisible.

### Rules

- **Emphasis follows consequence.** A control that drives the verifier or writes the model is
  primary and may carry a saturated fill. A control that only *proposes* a change — the AI
  suggestion tools — is secondary: card surface, hairline border, no shadow.
- **Demote rather than hide or disable.** A quieter control is still visible, focusable, named, and
  full-size. Hiding it costs discoverability, and a `disabled` element is unreachable for assistive
  technology, so neither is a substitute for lowering emphasis.
- **A category keeps its colour, but not as a fill.** The AI tools each keep their hue on the icon
  and active state. Removing the hue entirely would make four similar buttons harder to tell apart —
  the opposite of the goal. Colour distinguishes *kind*; weight distinguishes *importance*.
- **State never depends on colour alone.** The open-panel state adds an inset border alongside its
  tint, and a running tool keeps its animated halo.
- **Do not spend a new hue on a new category.** Eight saturated hues accumulated one feature at a
  time, each locally reasonable. A new dock entry belongs in an existing group at that group's
  weight.

`views/board/actionDockHierarchy.spec.ts` pins this, including that run actions must *stay* primary:
demoting everything is the same failure as emphasising everything.

### Three tiers, because the product has three kinds of action

The first pass of this rule separated *run actions* from *AI suggestions* and stopped there, which left a
second flattening inside the run group. Measured on a loaded board, four controls rendered
**byte-identical** — `rgb(37, 99, 235)` fill, 400 weight, 124x44 box, 10.4px radius, same shadow:

| Control | What it returns | Tier |
| :--- | :--- | :--- |
| **Verification** | a formal NuSMV proof or a counterexample | `--primary` — filled |
| **Simulation** | one concrete trace; proves nothing | `--evidence` — tinted + bordered |
| **Explore** | bounded *candidate* counterexamples | `--evidence` — tinted + bordered |
| **Run History** | a read-only view of past results; writes nothing | `--view` — neutral |

This is a product-semantics rule wearing a visual form. `CLAUDE.md` already requires that a fuzz finding
never be dressed as a verdict; painting Explore exactly like the verifier is that same overclaim in the
visual layer. Colour still marks the family — all three are accent, no new hue — and weight now marks the
epistemic claim. `--view` is deliberately *not* `--suggestion`, which means "this proposes a change you may
accept"; Run History proposes nothing, so borrowing that variant would misdescribe it.

**A tier is not enough on its own.** An independent review of the retiered dock ranked the four correctly and
called the hierarchy deliberate, but found a real remaining gap: Simulation and Explore "look like equivalent
run modes, and none indicates what kind of result it produces". A visual tier can say *how important*; it
cannot say *what you will get*. So each tooltip carries a second line naming the outcome — "Returns one
concrete trace — not a proof", "Bounded search for candidate counterexamples — finding none is not safety",
"Formal NuSMV result: a proof or a counterexample" — at the type floor rather than below it, because that
sentence is what stops a bounded search being read as a proof.

**A quiet tier still needs a visible edge, and "too quiet" turned out to be measurable.** Two independent
reviews of the retiered dock — one per theme — both called Run History "too quiet" and "easy to miss". That
reads like taste until you measure it: both quiet tiers have a surface almost indistinguishable from the panel
behind the dock (1.22:1 for `--evidence`, 1.10 for `--view`), so the border alone carries the boundary, and it
was **1.48:1**. WCAG asks 3:1 for a control boundary, so the reviews were describing a real failure — the edge
of the control was effectively invisible. Both borders were raised to clear 3:1 in both themes. Demoting a
control lowers its emphasis; it must not stop it reading as a control.

The measurement also **refuted** three claims from the same reviews, which is why it ran first: radius is not
uniform (5 distinct values, 55 of 115 surfaces at `0px`), elevation is already restrained (103 of 115 have no
shadow), and accent fill is not overspent (7 of 174 controls, all of them run actions or active tabs). A
converging impression is a reason to measure, not a finding.

---

## 6. Ink and paper: a role has two halves

Every semantic role token exists in two jobs, and one value cannot do both.

`--accent`, `--danger`, `--warning`, `--success` are tuned to be legible **as text on the page
ground**, which is why the dark theme *lightens* them. Used as a **fill with light ink on top**, that
tuning inverts. Measured across 60 sites in dark theme:

| Token | Dark value | White ink on it | Light theme | Verdict |
| :--- | :--- | ---: | ---: | :--- |
| `--accent` | `#60a5fa` | **2.54** | 5.17 | fails AA, dark only |
| `--accent-strong` | `#93c5fd` | **1.80** | 6.70 | fails AA, dark only |
| `--warning` | `#fcd34d` | **1.44** | 5.02 | fails AA, dark only |
| `--danger` | `#fca5a5` | **1.90** | 4.83 | fails AA, dark only |
| `--success` | `#6ee7b7` | **1.52** | 3.77 | **fails in both** |

`--accent-strong` is the *hover*, so contrast fell as the user interacted with the control. Light
theme passed almost everywhere because one dark blue happens to serve both jobs there — which is why
this survived several review passes, and why a light-only check will not find it.

### Rules

- **Fill with `--<role>-fill`; write with the bare role.** The fill halves are solved against two
  constraints at once: light ink at ≥ 4.5:1, and the fill against its panel at ≥ 3:1 so the control
  keeps a visible edge.
- **The base fill does not change with the theme; the hover must.** A ground that does not flip means
  one ink is correct everywhere and no markup has to guess. "Darker" and "brighter" swap meaning with
  the ground, so `--accent-fill-hover` is `#1d4ed8` in light and `#2f6fe4` in dark — both above AA,
  which the `--accent-strong` hover they replace was in neither theme.
- **A fill with no ink on it keeps the bare role.** Progress bars, playback rails, pulse rings, 1px
  accent stripes, the selected-step dot: their obligation is 3:1 against a *neighbour*, not against
  text. Retargeting them would darken decoration for no reason. 24 of the 84 fill sites are this case.
- **A theme-flipping ground has no correct ink — fix the ground.** The six panel banners used
  `--accent`, so black ink measured 4.06 light / 8.26 dark and white the reverse. No single markup
  could be right, and one panel title rendered at **1.44:1**. Pinning the banner to `--accent-fill`
  fixed all six at once; forking the ink per theme would have doubled the surface area of the bug.
- **Ink on a fill is measured, not eyeballed.** `text-white/85` on `--accent-fill` is 4.19 — under AA
  by a margin no one sees by looking. `/90` is 4.50.
- **A hover that names its own resting value renders nothing.** Not a contrast failure, so the contrast
  rules pass it, and invisible in a diff because the line looks like it handles hover. Eight sites had
  it, including the four Stop buttons and three "add condition" controls, so the accent buttons beside
  them lit up under the pointer while the red ones stayed inert. `--danger-fill-hover` (`#c22121`, white
  ink 5.94) and `--warning-fill-hover` (`#9e4908`, 6.16) close it. Both are theme-stable, and that is
  forced rather than chosen: `--danger-fill` sits at 4.83 under white ink with no headroom, so `#ef4444`
  measures **3.76** — the dark theme's usual brightening is unavailable and both themes darken. There is
  deliberately no `--success-fill-hover`: the only white-ink success fill is the "Applied" state, which
  is `cursor-default` and must not react. A bare `hover:` with nothing after it is the degenerate form of
  the same defect — the counterexample rail's step markers shipped with one, so the control whose entire
  job is "click to seek here" had no pointer feedback.
- **A filled action button with no hover at all is the same defect, and the rule above cannot see it.**
  Four primary buttons in Run History — Watch task, Open result, and both Replay buttons — carried
  `bg-[color:var(--accent-fill)]` and nothing else, while every *secondary* button beside them (Cancel,
  Delete, Download) did hover. In one run row the fuzz Replay button hovered and the counterexample Replay
  button directly above it did not, so a single panel gave one action two behaviours, and the button a
  user reaches for first was the inert one. Guarded by its own rule, scoped to `<button>` openings: a
  selected segment legitimately holds a fill with no hover, because there the fill states *which segment
  is selected* and reacting to the pointer would suggest it is still a choice. The verification panel's
  two attack-mode buttons are that shape and are exempt by the ternary they are written in. The exemption
  searches every branch after the fill, not just the adjacent one: the two replay-bar Play buttons nest a
  second ternary for the disabled case, so an adjacency-only test reported them as inert when the accent
  fill there means *playing* — a state, exactly what the exemption is for.
- **The neutral control needs a hover ground too, and it cannot borrow Tailwind's.** The roles above all
  had a `--*-fill-hover`; the *neutral* control — chips, panel toggles, close buttons, replay transport —
  had none, so 26 sites reached for `hover:bg-slate-100/200`, which cannot win on a board surface (§9).
  Both failure modes were measured in dark mode: where the element also carried a bare `bg-slate-*`, an
  `!important` theme remap owned the background and the hover did **nothing** (11.82:1 at rest and
  hovered — the replay bar's Play button and the history panel's Dismiss button gave no feedback at all);
  where nothing competed, the utility applied and painted rgb(241,245,249) near-white under light ink at
  **1.13:1**. `--surface-control-hover` closes it: `#273548` dark (`--text` 10.04, `--text-muted` 4.85),
  `#e8edf3` light (15.17 / 4.23). Asymmetric on purpose — dark lightens toward the viewer, light darkens.
- **A muted neutral must strengthen its ink on hover, because the light ground cannot pass alone.**
  `--text-muted` is itself tuned to 4.55:1 on `--surface-control`, so *any* darker light-theme ground puts
  it under AA; `#e8edf3` reaches 4.23 (an improvement on the 4.04 that `hover:bg-slate-200` measured, not
  a fix). So `hover:board-control-hover` on a `board-text-muted`/`board-chip-neutral` control is paired
  with `hover:board-text-strong` — the convention `SystemInspector` had already written by hand.
  `styles/__tests__/neutralHoverFeedback.spec.ts` enforces the pair, exempting icon-only controls where
  SC 1.4.3 does not apply and the glyph sets its own colour.
- **A chip role owns its resting ink, so a second ink class beside it is dead.** `board-chip-*` sets `color`
  as part of the role (`board-chip-neutral` is `--text-muted`), and `.board-text-strong` is a bare 0-1-0 rule
  sitting *earlier* in `board.css` — so the chip wins on source order, and its `.iot-board` arm wins outright.
  Confirmed in the emitted bundle (2,806 bytes apart) and then in a browser: removing `board-text-strong`
  from three controls changed the rendered ink by nothing at all, because it had never applied. It was
  introduced migrating `bg-slate-100 text-slate-700` to roles — the ground moved and the ink silently did not.
  Let the chip set the resting ink and change it on hover, which is a different rule and does apply
  (measured 5.71 → 10.04:1). A chip paired with the ink of the *same* role is merely redundant and renders
  what it says; ~60 sites do that and are fine. `roleClassVariants.spec.ts` flags only the mismatched pair.
- **An accessible name must use the same word as the label beside it.** The counterexample rail's step
  buttons announced "First violation" — the exploration wording, hardcoded — on the state whose visible
  marker read "Violation". One state, two names, on one control, and only a screen-reader user met the
  discrepancy.
- **One action, one colour, across panels that diverge.** The three recommendation panels' Apply buttons
  were amber, blue and red, each broken differently — the amber one hovered white ink onto
  `--warning-surface`, the red one onto itself. They now share the accent pair, because it is one action
  and reading it as three invites the user to think the panels do different things.

### Disabled: desaturate, do not fade

`opacity` multiplies whatever it is applied to, including the label. A disabled control still has to
be readable, because it is the thing explaining why the action is unavailable.

Measured: a filled primary button at **2.42:1** while disabled, the exploration panel's "Select all"
at **2.00**, undo/redo at **1.80** — the last disabled on every fresh board, so the first state of
those controls anyone sees. The pre-existing `board-panel-submit:disabled` treatment was also wrong at
**1.75**, because it desaturated toward a *white* card, removing the contrast its ink depended on.

- Filled actions use `board-action-disarmed`, which swaps the fill for a desaturated neutral
  (`--accent-fill-disabled`, white ink 4.76) and leaves the ink opaque.
- Muted text simply stays muted; the cursor and the `disabled` attribute already carry the state.
- **The other 69 opacity-faded controls were measured and left alone.** Opacity is only a defect where
  it multiplies a value already near the floor, so an audit of the declarations would have produced 69
  pointless edits. Measure the rendered state instead.

### Structural neutrals have a floor too

Slate/gray/zinc are legitimate — they are the structural greys, not semantic roles. But `slate-400` is
**2.56:1 on white**, and it was set on 126 text elements. Body text uses the 500 step or darker in
light theme; `dark:text-slate-400` is correct on a dark card, where the same value is 5.71.

Pinned by `styles/__tests__/neutralTextContrast.spec.ts` and
`styles/__tests__/semanticColourOwnership.spec.ts`.

**The scanning unit is the enclosing `class` binding, not a line or a fixed window.** These guards have
now been defeated twice by the same shape. First a `class="…"` matcher whose `[^{}]` skipped every
`:class="[ … ]"` array — most of the buttons. Then a same-line premise, which caught **zero** of the
seven real ink/fill inversions, because in a multi-line binding the shared classes (including
`text-white`) are the first array element and the conditional fill is the second. A ±3-line window then
missed the rule panel's Apply button by one line, in shipped code. `sources()` also reads one directory
level, so `components/common/` was never scanned at all. When adding a rule here, scope it to the
binding via `enclosingBinding`, walk with `allSources()`, and recreate a real defect inside a
multi-line binding to prove the rule can fail.

### A domain value maps to one role, product-wide — and an empty class is not a role

Two failures that the token rules above do not cover, because both were about *which* role a value earns
rather than how a role is spelled.

- **A classification is not a hazard.** `private` and `untrusted` are provenance facts — `nusmv-model.md`
  is explicit that "`untrusted` does not mean the device is selected as compromised" — so they take
  `info`, and `attacked` keeps `danger` because a selected compromise genuinely is one. Amber was tried
  twice and rejected twice: two desktop reviews of an ordinary simulation read it as implying a security
  defect ("颜色和措辞像警告"), recorded at `SimulationTimeline.vue`'s privacy chip and again in
  `PlaybackChangePopover`. Reintroducing `warning` for `private` in one surface makes the product
  disagree with itself about whether an ordinary phone photo is a problem.
- **Neutral is the honest default; a role has to be earned** (`board.css`). An empty-string class branch
  is a defect, not a neutral: it renders a chip-shaped element with no chip, so the same value reads as
  two different things depending on which table it lands in. The device dialog had exactly that in two
  tables that chose *opposite* values for the empty branch, so an unstyled label meant "public" in one
  and "private" in the next. Use `board-chip-neutral` when a value carries no status.

A test that pins this should assert the chips for one value share *the same* class, not that they carry a
particular one — hardcoding the role cements it locally and lets it drift from the rest of the product.

### A section header inside a dialog is one shape

Peer sections in a dialog body use: a `w-1 h-7 shrink-0` accent bar, then a `min-w-0` wrapper holding the
`<h2>` and a `text-xs` hint at `mt-0.5`, on a `flex items-start gap-2 mb-4` row. Two things this fixes.
`h-7` is `text-lg`'s exact line box, so the bar spans the title; a fixed `h-5` bar on an `items-center`
row was correct while headers were one line and became decoration beside the hint once they were two.
And every peer section carries a hint, because explaining one of six implies the other five are
self-evident — the device dialog's variables, states, APIs and specifications tables each encode a
non-obvious ownership or capability rule that nothing on screen stated.

Pinned by `components/__tests__/DeviceDialog.spec.ts`, which walks every section and asserts the shape;
the bar is the anchor, because a class query for the header row also matched inner rows and passed for
the wrong element.

## 7. Text size: the floor is not the target, and headings need a tier

`--iot-font-min` (0.6875rem = 11px) is the smallest size any interface text may be declared at, enforced by
`styles/__tests__/typographyFloor.spec.ts`. Two things that rule does *not* say, both of which produced real
defects:

- **A heading sized just above the floor is not a heading.** The action dock's "Board tools" was `0.72rem` at
  weight 800 against `--iot-font-min`/700 group labels *beneath* it — 0.52px of difference at a heavier
  weight, so two levels of heading rendered as one and a reader saw three rows of small uppercase text with
  nothing marking which named the panel. It passed `typographyFloor` (11.52px over an 11px floor) throughout.
  A panel heading uses `0.875rem` (the tier the side-panel titles use); its subheadings use the 11px floor.
  Leave at least 2px between two levels that appear together.
- **Pick a step on the scale, not a nearby number.** `0.72rem` and `0.78rem` each appeared at multiple sites
  and matched nothing else in the product, sitting a fraction of a pixel from `0.75rem`, which twelve other
  board labels already use. A bespoke value costs a reader the ability to tell two levels apart and costs the
  next contributor a decision that was already made.
- **`text-transform: uppercase` makes text read smaller than it measures.** It costs roughly 15% of apparent
  x-height and removes word shape, so it is a poor choice for anything already near the floor, and for a
  four-character label it is most of why the label looks too small. Reserve it for the smallest
  section labels, where the loss of word shape is the point (they are scanned, not read).

Pinned by `views/board/actionDockHierarchy.spec.ts` for the dock, which is where the tier inversion was.

### A `clamp()` whose middle term can never win is a fixed size wearing responsive syntax

`cqmin` is a percentage of the *container*, so on a 110–137px canvas node `4.3cqmin` is 4.7–5.9px: the
declared floor was the rendered size at every viewport, and three node declarations printed 9.28px and 10px
under an 11px minimum. Before writing a sub-floor floor, compute what the preferred term evaluates to at the
container's real size.

If a test exempts the pattern, the exemption needs a measurement, not a comment. `typographyFloor.spec.ts`
exempted these on a claim that they "render at 16px", which was false — so the check certified the defect it
existed to catch. `--canvas-zoom` is the one legitimate exemption, because it *divides*: 11px at 1.0× becomes
14.4px at 0.4×.

### A `position: fixed` overlay cannot read a variable scoped to the board

`--board-floating-gap`, `--board-control-width` and `--board-inspector-width` are declared on `.iot-board`,
but the two timeline hosts are **siblings** of it — deliberately, so they float above every panel. Inside them
those variables do not resolve, `calc()` becomes invalid at computed-value time, and `left`/`right` fall back
to `auto`. A fixed box with both set to `auto` shrink-wraps its content at its static position, i.e. flush
against x=0.

Measured: the trace overlay sat hard against the left edge with the right half of a 2556px screen empty, and
its width was *identical* at 2556 and 1440 (859.859px) — that identity is the tell, because a
corridor-positioned element must change with the viewport. On a 101-state trace the shrink-wrap reached
**3258px** and put the play button at x=2086, off-screen at a laptop viewport, so playback became unreachable.

`var(…, 1rem)` fallbacks had been hiding this, and removing them as "dead text" is what exposed it: the premise
that the gap lives at `:root` was wrong, and a test comment had recorded that wrong premise. Restoring a
fallback only hides it again — inject the variables onto the fixed element (`boardShellStyle` does this) so it
is positioned by values it can see. `boardDockGeometry.spec.ts` pins the injected value against the
stylesheet's.

**The same structure breaks *selectors*, and that half is quieter.** A rule written as
`.iot-board .board-timeline…` or `.iot-board.has-… .board-timeline-host` matches nothing: the declarations
parse, the file reads as maintained, and the surface renders with whatever the unprefixed rules happen to
give it. Three instances found so far, each with a different symptom:

| Rule | What was lost |
| :--- | :--- |
| `.iot-board button:not(:disabled)` | 10 of 12 enabled replay controls showed `cursor: default` — a user report |
| `.iot-board .board-timeline [data-testid$="-timeline-close"]` | the 44px touch floor, inside the narrow media query where it matters most; both close buttons stayed at the ~32px their padding produced |
| `.iot-board.has-playback-change-popover .board-timeline-host` | half a layout contract: the popover narrowed to 42vw on cue (it *is* inside the board) while the timeline never yielded the column, so on a short landscape viewport the two overlapped — the exact case the block exists to prevent |

Nine dead colour rules sat alongside the second one, restating the neutral-to-token mapping the unprefixed
`.board-timeline` block already performs, with *different* values — so had the prefix ever matched, the
higher-specificity dead copy would have won and contradicted the measured overlay treatment.

The third row is the one worth measuring, because "they overlapped" understates it. Against the built bundle,
with the dead form reproduced beside the shipped one:

| Viewport | Shipped | Dead form |
| :--- | :--- | :--- |
| 1280×560 | bar `[16..896]`, inspector `[912..1264]` — 16px apart | bar `[16..1264]` — inspector covers it across its full 352px |
| 1024×500 | 16px apart | full-width cover |
| 900×560 | 16px apart | full-width cover |

The inspector did not clip a corner of the replay bar; it sat on top of all of it, because the bar kept the
whole corridor. A half-matched pair rule fails this way by construction: the arm that *gives away* space is
the one that matched, so the surface politely shrinks and the other one expands into it.

The rule: for anything a replay bar needs, key off the host (`.board-timeline-host`, `.board-timeline`) or off
an attribute the host itself publishes — `data-playback-change-popover`, mirrored by both hosts the way
`boardShellStyle` mirrors the width variables. `timelineHostScope.spec.ts` rejects the board-descendant form
outright, because the mistake is the selector shape rather than any one declaration.

## 8. Depth is a scale, and it means containment

Elevation says *what kind of thing* something is, the same way the radius scale does. Three steps, and one
distance means one thing:

| Token | Means |
| :--- | :--- |
| `--shadow-raised` | a control lifting off the surface it sits on |
| `--shadow-floating` | a transient chip, popover, or canvas node above content |
| `--shadow-elevated` | a panel floating above the page |

`--shadow-elevated` used to be the only token, so anything needing a different depth wrote its own literal —
eight distinct neutral elevations in the board alone, no two agreeing. Three rules follow:

- **Never hand-write a neutral elevation.** A literal `rgba(15, 23, 42, …)` shadow does not follow the theme,
  and the dark theme's token is both deeper and more opaque for a real reason: a shadow works by darkening its
  ground, and a near-black ground has almost no headroom left, so the light theme's alphas vanish on it.
- **Pick the step that describes the thing.** The dock's hover tooltip carried `--shadow-elevated`, an
  18px/42px panel lift, on a two-line hover chip — so hovering a dock button dropped a panel-sized shadow
  across the canvas. That is not a matter of taste; the depth was making a false claim about the element.
- **Hover raises one step. It does not change hue, ground, or edge.** The canvas node used to swap its resting
  hairline (a dark line at 12% of `--text`) for `rgba(255, 255, 255, 0.72)` on hover, which inverted the edge
  from dark to white in light theme and drew a bright outline on a navy node in dark. A colour change dressed
  as a depth change reads as a glitch rather than a lift. Likewise, a control inside an already-elevated panel
  should not carry its own lift: emphasis between tiers is the fill and the border (§4), not depth — the
  verification button's stray `shadow-lg` made one of eight dock buttons float above the strip it belongs to.

One more thing that is depth-adjacent and caused the same "why is some white and some not" reaction: **two
tiers that mean the same thing sit on the same ground.** Run History and the four AI suggestions are both
"not the primary action", yet sat on `--board-control-bg` and `--board-card-bg` respectively, four rows apart
in one strip. A background change down a vertical list implies a *category* change. When you do move a
control's ground, re-measure its border — the 3:1 component minimum is against the ground, so changing the
ground invalidates the old measurement.

- **Three steps means three, not "three plus whatever else exists."** Two more elevation tokens had grown
  alongside `--shadow-elevated`, and both carried the same bug — which is what a fourth owner buys you.
  `--iot-node-shadow` and `--iot-color-card-shadow` were declared as `rgba(15, 23, 42, 0.9)` in dark theme: the
  *light* palette's navy at 90% opacity, where every dark shadow is `rgba(2, 6, 23, …)`. The node one was worse
  than a wrong colour — the node's resting rule used the scale while its four state rules (focus, focused,
  trace-active, trace-changed) used that token, so highlighting a node silently changed its base depth on top
  of adding the ring. A state should add to a depth, not replace it.
- **An edge-attached bar is not a floating panel.** The board nav is full width, flush to the top, and already
  has a bottom border marking the boundary; a panel lift under something with nowhere to float *to* reads as a
  heavy smear across the viewport. All its shadow has to say is "content scrolls under this."

Pinned by `styles/__tests__/elevationScale.spec.ts`, which covers `base.css` and `board.css`. **Twenty
hand-written elevations remain outside the board** (`ChatView`, `Landing`, `PublicHeader`, the two toggles,
`ToggleSwitch`, `AccountDeleteDialog`, and the `ControlCenter`/`CanvasBoard` scoped blocks). They are the same
defect; each needs its depth chosen and then measured on its own surface.

## 9. A scoped rule outranks a Tailwind utility on the same element

Vue compiles `<style scoped>` selectors with a `[data-v-…]` attribute, so `.foo { max-width: 100% }` is
specificity **0-2-0** while Tailwind's `.max-w-4xl` is **0-1-0**. The scoped rule wins. This is not a rare
edge: it has produced three separate user-visible defects, and each one looked correct in review from both
sides — the template states the intent, the stylesheet states a reasonable-sounding rule, and only the
rendered pixels disagree.

- **`DeviceDialog` filled the screen.** `max-w-4xl` (896px) in the class list, and
  `.device-dialog-surface` in a scoped `max-width: 100%` list whose purpose was containing overflow in the
  dialog *body*. Measured on a 2548×1465 display: **2516×1433, 98.7% × 97.8% of the viewport**, for content
  that needs 896px. It read as the app being replaced by a settings screen rather than a panel opening over
  the board. Removing the surface from that list restored the cap (896px, 35% of the width).
- **An accent icon rendered grey.** `.device-runtime-box span { color: inherit }` — there to neutralise
  Tailwind slate utilities the markup still carries — also matched the one span asking for
  `board-text-accent`. Measured `rgb(148,163,184)` where `--accent` is `rgb(96,165,250)`.
- **A hover state half-applied.** Role-class hover variants declared in `board.css` lost to
  `.iot-board .board-side-panel .text-slate-500`; the fix was source *order*, not specificity, and two
  attempts at raising specificity failed first.

**Layers outrank specificity entirely, and that runs the other way.** Tailwind v4 emits its utilities into
`@layer utilities`; every rule in `board.css` is unlayered, and an unlayered rule beats a layered one at
*any* specificity. Proved in-browser: an unlayered `section.probeSec` (0-1-1) overrode `.bg-slate-100`. So
the two directions are not symmetric — a hand-written `board.css` rule cannot be out-specified by a
utility, and adding a `dark:` variant does not help, because the variant lands in the same losing layer.
This is why a Tailwind hover utility on a board surface is never the fix; see §6's neutral-hover rules for
the 26 sites it silently broke.

### Rules

- Do not put a `max-width`/`max-height` in a scoped block on an element that also carries a Tailwind
  `max-w-*`/`max-h-*`. Constrain the children that need containment, not the capped element itself.
  `styles/__tests__/scopedWidthOverride.spec.ts` fails on the overlap, per axis.
- A broad scoped selector (`span`, `p`, `button`) must exempt the role classes used inside it —
  `span:not(.board-text-accent)` — or the role has no way to win in that component.
- **"Defined" is not the same as "defined where this element is."** `roleClassVariants.spec.ts` matched the
  class name as a substring, so a variant declared only as `.iot-board .hover\:board-…` counted as defined
  for the two replay bars, which are *siblings* of `.iot-board`. That is the fourth defect from this one
  structure, so it is now a check: a variant class used inside a `.board-timeline` region must have at least
  one definition that is unprefixed or `.board-timeline`-scoped. The check reads the stylesheet with comments
  stripped — a prose mention of the bare selector (`board.css:1953` explains why one lost the cascade) read as
  a definition and let the first version pass a class that had none.
- When a rule that looks right does not apply, read the winner from CDP matched-styles rather than
  reasoning about specificity. At equal specificity, source order decides, and raising specificity is then
  the wrong fix.
- Dialog height caps: the siblings use `85vh`/`88vh`, which leaves visible margin so the surface reads as a
  panel over the board. `calc(100vh - 2rem)` reads as a takeover.

## 10. Replay has three surfaces, and each owns one question

Counterexample and simulation replay render onto three surfaces at once. They looked redundant — all three read
the same `currentTraceState` — and measurement showed two of the three overlaps were real while the third was
not. The split that survived measurement:

| Surface | Question it answers | Why it, and not the others |
| :--- | :--- | :--- |
| **Canvas nodes** | *What is the state now?* | Richest of the three: value, previous value, `changed` tint, trust, security pills, with `shortLabel` variants for a narrow node. It is also where the user is already looking. |
| **Timeline** | *When, and what caused it?* | Step position, the rail, and the rule that produced this state. The canvas cannot show ordering. |
| **Change popover** | *What moved, from what to what?* | The only surface with room for `previous → current`. A node's changed-chip is capped at `58cqmin` — 64px on a 150×110 node — where "Temperature 24 → 26" truncates to a fragment. |

### Rules

- **Do not repeat per-step device or environment values in the timeline.** The canvas owns them. Three surfaces
  rendering the same state cost the counterexample overlay 529px of content inside a 318px viewport, 211px of it
  hidden behind a scrollbar, while it used 44% of the width the host reserved.
- **A cap must name its remainder before anything relies on it.** The node strip prints three variables; that
  limit was silent, and the timeline's full `traceDeviceSummary` was what made it survivable. Removing the
  duplicate without surfacing the remainder would have converted a redundancy into a hole. The `+N` chip and its
  tooltip are that surfacing — verified against a five-variable template.
- **Session facts belong in the header, not in step details.** The replay-scope notice was an unconditional
  ~50-word block inside `trace-step-values`, re-read on every step, describing the whole session. It is a header
  hint now.
- **The popover is not the timeline's duplicate.** It is the only place a transition fits, and it already shrinks
  itself during playback (320px to 148px). Judge it by whether it answers the transition question, not by whether
  its inputs overlap.
- **A cap that always binds is a fixed size.** `max-height: min(44dvh, 20rem)` reads as responsive, but `20rem`
  wins on every viewport taller than ~727px. After the content dropped to 317px the cap sat 1px above it, so the
  next label would have re-armed the clipping — the ceiling has to leave headroom, or it is the working height.
- **A control that restores a surface is gated on that surface being gone, not on a past user action.** The two
  replay bars own the same "Show step changes" button and disagreed: the simulation bar reads the popover's
  visibility, the counterexample bar read `playbackChangesDismissedKey !== null`. That key is `kind:stepIndex`,
  scoped to one step on purpose — a dismissal at step 3 must not silence step 4 — so the two conditions come
  apart the moment the user scrubs: the popover returns, the key stays set, and the button offers to restore a
  panel already on screen, beside it rather than instead of it, for the rest of the session. The dismissal state
  answers *did the user ever dismiss*; the button needs *is it hidden now*. Where two surfaces own the same
  control, bind both to the same computed rather than to two things that agree today.

## 11. Canvas geometry and lookup ownership

`CanvasBoard.vue` derives every connection layer and label position from the same visual node
geometry. A held drag uses `nodeDragState.tempPosition`; ordinary connections, self-loops, SVG
hitareas and playback flow must all follow it before release. Pointerup commits the node layout
once. Cancellation, lost pointer capture and interaction locking discard the preview. Do not
write committed edge coordinates during a drag: they describe the old position and needlessly
invalidate parent scene watchers. Pointer resize and keyboard resize use the current node dimensions.
`components/__tests__/CanvasBoard.spec.ts` covers these transitions at multiple zoom levels and
`e2e/canvas-runtime-environment.spec.ts` covers held dragging, persisted layout, reload and undo
against the real backend.

Connection geometry and node lookup use cached indexes: O(N + E) when the node/connection sets
change, O(E) for connection geometry during a drag. `utils/traceEdgePlayback.ts` resolves playback
connections in O(E + T + C), where T/C are the selected step's triggered/compromised rule records.
Stable rule ids must denote one position; multiple source connections at that position are valid.
Id-less evidence matches only id-less connections at the same frozen rule position.

Current/previous device records and cumulative compromise evidence share one O(S × D) historical
index (S visited states, D device records per state), with O(1) node lookup. Membership anywhere in
the full trace is indexed separately, so a future appearance never becomes a current reading.
These bounds cover identity and geometry lookup, not variable formatting or Vue/SVG rendering.
The owning unit tests count identity accesses rather than relying on machine-dependent timings.
